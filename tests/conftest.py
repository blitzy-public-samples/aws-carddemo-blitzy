"""pytest wiring of the CardDemo characterization suite.

``pytest tests/`` runs the scenario modules ``tests/test_<level>_<area>.py``
on one execution adapter, writes each scenario's result record, and
regenerates the coverage matrix, the gap register, the coverage report and
the inventory's report fields when the session ends. ``tests/pytest.ini``
makes ``tests/`` the rootdir, so the harness imports as ``harness`` and the
adapters as ``adapters``.

Environment
    ``CARDDEMO_ADAPTER``
        ``gnucobol-batch`` (the default when unset) or ``unikix``. Any other
        value, the empty one included, is a usage error.
    ``CARDDEMO_TEST_WORKDIR``
        The work root: the per-scenario working directories, the build cache,
        the extracted UniKix zip and the UniKix deployment root. It must lie
        outside the repository, and it is created when missing. Unset, the
        session silently uses a fresh ``tempfile.mkdtemp(prefix="carddemo-")``
        root. Set to a value that cannot be a work root (blank, inside the
        repository, not a directory, not creatable or not writable), it gives
        a warning naming the refused value and why, then uses the same fresh
        temporary root instead. Only when that temporary root would lie inside
        the repository, or cannot be made, is it a usage error.

Options
    ``--carddemo-order=forward|reverse``
        Order of the selected tests. ``reverse``, used by the second CI run,
        proves that the outcome does not depend on order.
    ``--carddemo-no-report``
        Skip the report regeneration at session end (local partial runs; CI
        never passes it). It does not stop result writing:
        ``harness.results.write`` still writes each run's record under
        ``tests/reports/results/``, and a unikix run its evidence under
        ``tests/reports/unikix/``.
    ``--carddemo-baseline-base=<revision>``
        The revision the baseline version-bump check compares with
        (default ``HEAD``).

Fixtures
    ``carddemo_adapter_name``, ``carddemo_work_root``, ``carddemo_repo_root``
    and ``carddemo_baseline_base`` hold the session's settings.
    ``carddemo_adapter`` is the selected adapter, created when the first
    scenario test needs it and closed at session end, so a run of the harness
    self-tests alone builds nothing. ``carddemo_record(outcome)`` writes the
    outcome's result record (``harness.results.write``); a scenario module
    calls it before ``harness.runner.conclude``, so blocked and
    implemented-unexecuted scenarios get their record too.

Selection
    A scenario test is a test parametrized over ``scenario``. A scenario test
    without the selected adapter's marker is deselected and reported through
    ``pytest_deselected``; it gets no result record, and ``harness/report.py``
    reports its scenario ``blocked`` with reason ``adapter-unavailable``.
    Every other test, the harness self-tests included, is never deselected
    here. ``-m`` and ``-k`` stay pytest's own.

Nothing here writes into the repository: result records and reports are
written by ``harness.results`` and ``harness.report``, and everything else a
run produces stays in the work root, except the assertion-rewrite cache of
this file, which pytest writes to the git-ignored ``tests/__pycache__/``
before ``sys.dont_write_bytecode`` is set here (an interpreter started with
``-B`` or ``PYTHONDONTWRITEBYTECODE`` writes none).
"""

from __future__ import annotations

import contextlib
import importlib
import os
import sys
import tempfile
import traceback
from collections.abc import Callable, Iterator
from pathlib import Path
from typing import TYPE_CHECKING, Any

import pytest

if TYPE_CHECKING:
    from adapters.base import Adapter

# Keep the bytecode of every module imported after this line out of the
# checkout: the harness and the adapters, and the test modules, whose
# assertion-rewrite cache pytest skips while the flag is set. pytest imports
# this file before the line runs, so this file's own assertion-rewrite cache
# is written to the git-ignored tests/__pycache__/ unless the interpreter
# starts with -B or PYTHONDONTWRITEBYTECODE. The flag is this interpreter's
# alone: a Python child process the suite starts passes -B or
# PYTHONDONTWRITEBYTECODE itself.
sys.dont_write_bytecode = True

from harness import MARKER_FOR_ADAPTER, HarnessError, usable_work_root  # noqa: E402

TESTS_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TESTS_ROOT.parent

ADAPTER_ENV = "CARDDEMO_ADAPTER"
WORKDIR_ENV = "CARDDEMO_TEST_WORKDIR"
WORK_ROOT_PREFIX = "carddemo-"

DEFAULT_ADAPTER = "gnucobol-batch"

# Adapter name -> (module, class). The module is imported only when a scenario
# test first needs the adapter, so selecting one adapter never imports the
# other's modules.
ADAPTER_CLASSES: dict[str, tuple[str, str]] = {
    "gnucobol-batch": ("adapters.gnucobol_batch.adapter", "GnuCOBOLBatchAdapter"),
    "unikix": ("adapters.unikix.adapter", "UniKixAdapter"),
}

ADAPTER_NAME_KEY = pytest.StashKey[str]()
WORK_ROOT_KEY = pytest.StashKey[Path]()
# Set only when this session made the work root with mkdtemp.
CREATED_WORK_ROOT_KEY = pytest.StashKey[Path]()

SCENARIO_PARAMETER = "scenario"


# --------------------------------------------------------------------------
# Options and configuration
# --------------------------------------------------------------------------


def pytest_addoption(parser: pytest.Parser) -> None:
    group = parser.getgroup("carddemo", "CardDemo characterization suite")
    group.addoption(
        "--carddemo-order",
        choices=("forward", "reverse"),
        default="forward",
        help="order of the selected tests; 'reverse', used by the second CI run, "
        "proves order independence (default: forward)",
    )
    group.addoption(
        "--carddemo-no-report",
        action="store_true",
        default=False,
        help="do not regenerate the coverage matrix, gap register, coverage report and "
        "inventory report fields at session end; result records are still written "
        "(local partial runs; never used in CI)",
    )
    group.addoption(
        "--carddemo-baseline-base",
        default="HEAD",
        metavar="REVISION",
        help="revision the baseline version-bump check compares with (default: HEAD)",
    )


def _selected_adapter() -> str:
    """The adapter ``CARDDEMO_ADAPTER`` selects: the default when the variable
    is unset, else its value. Any value but an adapter name, the empty one
    included, is a usage error."""
    value = os.environ.get(ADAPTER_ENV)
    if value is None:
        return DEFAULT_ADAPTER
    if value not in ADAPTER_CLASSES:
        allowed = " or ".join(f"'{name}'" for name in ADAPTER_CLASSES)
        raise pytest.UsageError(f"{ADAPTER_ENV} must be {allowed} (got {value!r})")
    return value


def _refuse_inside_repository(path: Path, what: str) -> None:
    if path == REPO_ROOT or REPO_ROOT in path.parents:
        raise pytest.UsageError(
            f"{what} ({path}) lies inside the repository {REPO_ROOT}; the work root, which holds the "
            "working directories, the build cache, the extracted UniKix zip and the UniKix "
            "deployment root, must lie outside it"
        )


def _work_root() -> tuple[Path, bool, str | None]:
    """The session's resolved work root, whether this session created it, and
    the warning to give when ``CARDDEMO_TEST_WORKDIR`` is set but not used
    (``None`` otherwise).

    A set value is used only when ``harness.usable_work_root`` accepts it: not
    blank, neither the repository nor inside it (checked before anything is
    created, so a refused root leaves nothing behind), created when missing,
    a directory, and writable. A refused or unset value is replaced by a fresh
    ``tempfile.mkdtemp(prefix="carddemo-")`` root, which this session owns.
    That root must lie outside the repository too: when the temporary
    directory lies inside it, or no temporary root can be made, there is no
    work root at all, and the usage error names the refused value as well.
    The configured value is used as given, without ``~`` expansion, as
    ``harness.results`` reads it to keep work-root paths out of result
    records."""
    configured = os.environ.get(WORKDIR_ENV)
    refused = None
    if configured is not None:
        try:
            return usable_work_root(configured), False, None
        except HarnessError as exc:
            refused = f"{WORKDIR_ENV}={configured!r} is not a usable work root: {exc}"
    try:
        _refuse_inside_repository(Path(tempfile.gettempdir()).resolve(), "the temporary directory")
        root = Path(tempfile.mkdtemp(prefix=WORK_ROOT_PREFIX)).resolve()
    except (OSError, pytest.UsageError) as exc:
        problem = str(exc) if isinstance(exc, pytest.UsageError) else f"no temporary work root can be made: {exc}"
        if refused is not None:
            problem = f"{refused}, and no fresh temporary work root can replace it: {problem}"
        raise pytest.UsageError(problem) from exc
    if refused is None:
        return root, True, None
    return root, True, f"{refused}; this session uses the fresh temporary work root {root} instead"


def pytest_configure(config: pytest.Config) -> None:
    config.stash[ADAPTER_NAME_KEY] = _selected_adapter()
    root, created, warning = _work_root()
    config.stash[WORK_ROOT_KEY] = root
    if created:
        config.stash[CREATED_WORK_ROOT_KEY] = root
    # Given after the root is stashed, so pytest_unconfigure still removes the
    # empty temporary root when -W turns the warning into an error.
    if warning is not None:
        config.issue_config_time_warning(pytest.PytestConfigWarning(warning), stacklevel=2)


def pytest_unconfigure(config: pytest.Config) -> None:
    root = config.stash.get(CREATED_WORK_ROOT_KEY, None)
    if root is None:
        return
    # A default work root this session made and left empty (a collect-only
    # or harness-only run) is removed. One holding the build cache or a kept
    # failing working directory stays for inspection, and a root that cannot
    # be listed or removed is left as it is: it lies outside the repository
    # and changes no outcome.
    with contextlib.suppress(OSError):
        if not any(root.iterdir()):
            root.rmdir()


def pytest_report_header(config: pytest.Config) -> list[str]:
    return [
        f"carddemo adapter: {config.stash[ADAPTER_NAME_KEY]}",
        f"carddemo work root: {config.stash[WORK_ROOT_KEY]}",
    ]


# --------------------------------------------------------------------------
# Fixtures
# --------------------------------------------------------------------------


@pytest.fixture(scope="session")
def carddemo_adapter_name(pytestconfig: pytest.Config) -> str:
    """The selected adapter's name: ``gnucobol-batch`` or ``unikix``."""
    return pytestconfig.stash[ADAPTER_NAME_KEY]


@pytest.fixture(scope="session")
def carddemo_work_root(pytestconfig: pytest.Config) -> Path:
    """The session's work root, outside the repository."""
    return pytestconfig.stash[WORK_ROOT_KEY]


@pytest.fixture(scope="session")
def carddemo_repo_root() -> Path:
    """The repository root."""
    return REPO_ROOT


@pytest.fixture(scope="session")
def carddemo_baseline_base(pytestconfig: pytest.Config) -> str:
    """The revision of ``--carddemo-baseline-base`` for the bump check."""
    return pytestconfig.getoption("--carddemo-baseline-base")


@pytest.fixture(scope="session")
def carddemo_adapter(carddemo_adapter_name: str, carddemo_work_root: Path) -> Iterator[Adapter]:
    """The selected adapter for the whole session, closed when it ends."""
    module_name, class_name = ADAPTER_CLASSES[carddemo_adapter_name]
    adapter_class = getattr(importlib.import_module(module_name), class_name)
    adapter = adapter_class(work_root=carddemo_work_root, repo_root=REPO_ROOT)
    # Result records and evidence are attributed by the adapter's name, so a
    # class registered under the wrong name must not run anything.
    if getattr(adapter, "name", None) != carddemo_adapter_name:
        raise TypeError(
            f"{module_name}.{class_name} is named {getattr(adapter, 'name', None)!r}, "
            f"not {carddemo_adapter_name!r}"
        )
    yield adapter
    # Not guarded: a close that raises (a region or BPE left running on
    # unikix) fails the session teardown, which pytest reports as an error
    # at the teardown of the last test and as a failed exit status.
    adapter.close()


@pytest.fixture
def carddemo_record(request: pytest.FixtureRequest) -> Callable[[Any], Path | None]:
    """``record(outcome)``: keep ``outcome`` on the test item as
    ``carddemo_outcome`` and write its result record through
    ``harness.results.write``, returning the record's path (``None`` where
    the adapter's record is its own evidence)."""
    from harness import results

    def record(outcome: Any) -> Path | None:
        request.node.carddemo_outcome = outcome
        return results.write(outcome)

    return record


# --------------------------------------------------------------------------
# Collection
# --------------------------------------------------------------------------


def _is_scenario_item(item: pytest.Item) -> bool:
    callspec = getattr(item, "callspec", None)
    return callspec is not None and SCENARIO_PARAMETER in callspec.params


# Last, so the reversal applies to the order every other plugin has left.
@pytest.hookimpl(trylast=True)
def pytest_collection_modifyitems(session: pytest.Session, config: pytest.Config, items: list[pytest.Item]) -> None:
    marker = MARKER_FOR_ADAPTER[config.stash[ADAPTER_NAME_KEY]]
    kept: list[pytest.Item] = []
    deselected: list[pytest.Item] = []
    for item in items:
        if _is_scenario_item(item) and item.get_closest_marker(marker) is None:
            deselected.append(item)
        else:
            kept.append(item)
    if deselected:
        config.hook.pytest_deselected(items=deselected)
        items[:] = kept
    if config.getoption("--carddemo-order") == "reverse":
        items.reverse()


# --------------------------------------------------------------------------
# Session end
# --------------------------------------------------------------------------


def _show_report_failure(config: pytest.Config, text: str) -> None:
    reporter = config.pluginmanager.get_plugin("terminalreporter")
    if reporter is None:
        sys.stderr.write(text)
        return
    # The progress line is still open here: the terminal reporter ends it only
    # after every pytest_sessionfinish implementation has run.
    reporter.write_line("")
    reporter.write_sep("=", "carddemo report regeneration failed", red=True)
    reporter.write_line(text.rstrip("\n"), red=True)


def pytest_sessionfinish(session: pytest.Session, exitstatus: int | pytest.ExitCode) -> None:
    """Regenerate the coverage matrix, the gap register, the coverage report
    and the inventory's per-item status from the recorded results
    (``harness.report.regenerate``). A failed regeneration fails the session,
    so CI never passes on stale reports."""
    config = session.config
    if config.getoption("--carddemo-no-report") or config.option.collectonly:
        return
    try:
        from harness import report

        report.regenerate()
    except Exception:
        _show_report_failure(config, traceback.format_exc())
        if session.exitstatus == pytest.ExitCode.OK:
            session.exitstatus = pytest.ExitCode.TESTS_FAILED
