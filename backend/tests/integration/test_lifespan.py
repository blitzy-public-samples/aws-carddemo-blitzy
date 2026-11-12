# Integration tests for the ASGI application lifespan wrapper in app/main.py.
# These close QA finding F-02: the production Lifespan context manager (startup
# installs the sensitive-data PAN masking log filter; shutdown disposes the
# async database engine's connection pool) was never executed by the suite,
# because the shared httpx ``client`` fixture drives the app through
# ``ASGITransport`` WITHOUT a lifespan manager. Rather than add a new
# ``asgi-lifespan`` dependency (absent from the AAP dependency inventory, AAP
# 0.6.1), these tests drive the real ``Lifespan(app)`` context manager directly
# and assert both its startup and shutdown effects, exercising app/main.py lines
# 154/155 (startup + yield) and 159/160/161/162 (shutdown dispose + error guard).
#
# The app/ tree is production code and is NOT modified: the fix is a NEW test
# that executes the existing production lifespan. All global logging state that
# startup mutates is snapshotted and restored so nothing leaks across tests.
"""Integration tests for the CardDemo ASGI lifespan startup/shutdown (F-02).

``Lifespan`` (app/main.py) is an ``@asynccontextmanager``: entering it runs the
startup half (``InstallPanMaskingFilter()``) and exiting it runs the shutdown
half (``await engine.dispose()`` inside a ``SQLAlchemyError`` guard). Driving it
with ``async with`` therefore executes the entire production lifespan without a
live database -- the engine's ``dispose`` is spied -- so the startup filter
install and the shutdown dispose (and its error branch) are each asserted.

Coding conventions (AAP 0.8.2/0.8.3 Ochs Rule): helper callables use PascalCase,
local variables use camelCase, module constants use ALL_UPPERCASE, and test
function names plus pytest fixture names stay snake_case (the documented pytest
framework-contract exception). asyncio_mode is "auto", so ``async def`` tests
need no explicit marker.
"""

import logging

from sqlalchemy.exc import SQLAlchemyError

import app.main as main_module
from app.core.log_masking import PanMaskingFilter, TARGET_LOGGER_NAMES

# The uvicorn access logger is the canonical PAN leak path (it records the
# request line, which can contain a card-number path segment); startup must
# attach the masking filter to it (AAP 0.7.8; QA findings F8/M-06).
UVICORN_ACCESS_LOGGER_NAME = "uvicorn.access"

# Message raised by the shutdown-error spy to prove the SQLAlchemyError guard
# around engine.dispose() swallows a teardown failure instead of propagating it.
DISPOSE_ERROR_MESSAGE = "simulated connection-pool dispose failure"


# --------------------------------------------------------------------------- #
# Global-logging-state snapshot/restore helpers (Ochs PascalCase; <= 4 params).
# InstallPanMaskingFilter mutates process-wide logging singletons (loggers,
# their handlers, and logging.lastResort); these helpers guarantee the test
# leaves that global state exactly as it found it.
# --------------------------------------------------------------------------- #
def _CollectFilterTargets():
    """Return every logging object the lifespan startup may attach a filter to.

    Returns:
        A list of filterable objects: each target logger, each of that logger's
        already-installed handlers, and ``logging.lastResort`` when present.
    """
    filterTargets = []
    for loggerName in TARGET_LOGGER_NAMES:
        targetLogger = logging.getLogger(loggerName)
        filterTargets.append(targetLogger)
        filterTargets.extend(targetLogger.handlers)
    if logging.lastResort is not None:
        filterTargets.append(logging.lastResort)
    return filterTargets


def _SnapshotFilters(filterTargets):
    """Capture a shallow copy of each target's current ``filters`` list.

    Args:
        filterTargets: The filterable objects to snapshot.

    Returns:
        A list of ``(target, originalFilters)`` pairs for later restoration.
    """
    return [(target, list(target.filters)) for target in filterTargets]


def _RestoreFilters(filterSnapshot):
    """Restore every target's ``filters`` list from a prior snapshot.

    Args:
        filterSnapshot: The ``(target, originalFilters)`` pairs to restore.
    """
    for target, originalFilters in filterSnapshot:
        target.filters = list(originalFilters)


def _StripMaskingFilters(filterTargets):
    """Remove any existing masking filter so the install is observable.

    Args:
        filterTargets: The filterable objects to clean.
    """
    for target in filterTargets:
        target.filters = [
            existingFilter
            for existingFilter in target.filters
            if not isinstance(existingFilter, PanMaskingFilter)
        ]


def _HasMaskingFilter(filterable):
    """Report whether a masking filter is attached to a filterable object.

    Args:
        filterable: A logger or handler to inspect.

    Returns:
        ``True`` if any attached filter is a :class:`PanMaskingFilter`.
    """
    return any(isinstance(item, PanMaskingFilter) for item in filterable.filters)


# --------------------------------------------------------------------------- #
# Async-engine stand-ins. The real SQLAlchemy AsyncEngine forbids replacing its
# ``dispose`` attribute, so instead of patching the method the tests swap the
# module-level ``engine`` reference that ``Lifespan`` resolves at call time for
# one of these fakes. Their ``dispose`` deliberately matches the SQLAlchemy
# AsyncEngine external API name that app.main invokes (an integration-contract
# name, the documented naming exception).
# --------------------------------------------------------------------------- #
class _RecordingEngine:
    """A minimal async-engine stand-in that counts ``dispose`` invocations."""

    def __init__(self):
        self.disposeCallCount = 0

    async def dispose(self, *args, **kwargs):
        """Record one shutdown dispose call (no real pool to release)."""
        self.disposeCallCount += 1


class _FailingEngine:
    """An async-engine stand-in whose ``dispose`` raises to exercise the guard."""

    async def dispose(self, *args, **kwargs):
        """Raise a specific SQLAlchemyError to drive the shutdown error branch."""
        raise SQLAlchemyError(DISPOSE_ERROR_MESSAGE)


# =========================================================================== #
# app/main.py::Lifespan
# =========================================================================== #
async def test_lifespan_startup_installs_filter_and_shutdown_disposes_engine(monkeypatch):
    # Drives the real production lifespan: startup must install the PAN masking
    # filter on the uvicorn.access logger, and shutdown must await the async
    # engine's dispose(). The engine's dispose is spied so no live database is
    # required and the shared engine is not actually torn down.
    filterTargets = _CollectFilterTargets()
    filterSnapshot = _SnapshotFilters(filterTargets)
    _StripMaskingFilters(filterTargets)

    recordingEngine = _RecordingEngine()
    monkeypatch.setattr(main_module, "engine", recordingEngine)
    accessLogger = logging.getLogger(UVICORN_ACCESS_LOGGER_NAME)
    try:
        # Precondition: no masking filter and no dispose yet.
        assert not _HasMaskingFilter(accessLogger)
        assert recordingEngine.disposeCallCount == 0

        async with main_module.Lifespan(main_module.app):
            # Startup ran: the masking filter is now installed; shutdown has not.
            assert _HasMaskingFilter(accessLogger)
            assert recordingEngine.disposeCallCount == 0

        # Shutdown ran exactly once: the engine pool was disposed.
        assert recordingEngine.disposeCallCount == 1
    finally:
        _RestoreFilters(filterSnapshot)


async def test_lifespan_shutdown_swallows_sqlalchemy_dispose_error(monkeypatch, caplog):
    # The shutdown guard catches a SPECIFIC SQLAlchemyError from dispose() and
    # logs it (never a bare except, Ochs Rule), so a teardown hiccup cannot
    # propagate out of the lifespan. Exercises app/main.py's except branch.
    filterTargets = _CollectFilterTargets()
    filterSnapshot = _SnapshotFilters(filterTargets)

    monkeypatch.setattr(main_module, "engine", _FailingEngine())
    try:
        with caplog.at_level(logging.WARNING):
            # Entering and exiting the lifespan must NOT raise even though
            # dispose() fails: the error is caught and logged instead.
            async with main_module.Lifespan(main_module.app):
                pass
        assert DISPOSE_ERROR_MESSAGE in caplog.text
    finally:
        _RestoreFilters(filterSnapshot)
