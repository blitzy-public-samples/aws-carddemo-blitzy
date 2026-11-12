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
        outside the repository, and it is created when missing, with mode
        0700. Under every adapter it must be the user's own and writable by
        neither group nor others (``chmod go-w``), since anyone else who can
        write to it could swap a scenario's working directory for a link
        leading elsewhere, and readable, writable and searchable by that
        user, its owner (``chmod u+rwx``), since the runner opens it for
        reading and makes each scenario's working directory in it. Its
        resolved path must hold no backquote, ``$``, ``"`` or ``\\``, which
        a shell reads even inside the double quotes of
        the compiler commands GnuCOBOL's cobc runs, and no character that is
        not printable (a line break, a tab, another control character), which
        no one-line message can show, and it may have at most 3071 bytes, so
        the suite's deepest paths below it stay within ``PATH_MAX``. Under
        ``unikix`` its resolved path must also hold no whitespace and no
        quote, which the JVM option variables naming the deployment's
        temporary directory cannot carry, and no ``:``, at which ``COBCPY``,
        ``PATH`` and ``LD_LIBRARY_PATH``, naming the deployment's directories,
        are split. Unset, the session silently uses a
        fresh ``tempfile.mkdtemp(prefix="carddemo-")`` root. Set to a value that
        cannot be a work root (blank, inside the repository, not a directory,
        not creatable, not readable, writable and searchable by the user, not
        the user's own, writable by group or others, a path holding one of
        those characters or too long, or, under
        ``unikix``, a path holding whitespace, a quote or ``:``), it gives a warning
        naming the refused value and why, then uses the same fresh temporary
        root instead. Only when that temporary root would lie inside the
        repository, fails the same path rule, or cannot be made, is it a
        usage error. A configured root
        serves one session at a time: each scenario runs in a fresh
        ``<work root>/<scenario_id>/``, so two sessions sharing a root would
        delete each other's working directories. The session therefore holds
        ``<work root>/_session.lock`` (``harness.lock_work_root``) until it
        ends, and a second session that names a held root stops with a usage
        error naming the root and the session holding it. Give each
        concurrent session its own work root.

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

from harness import (  # noqa: E402
    MARKER_FOR_ADAPTER,
    HarnessError,
    WorkRootLock,
    check_work_root_path,
    lock_work_root,
    usable_work_root,
)

TESTS_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TESTS_ROOT.parent

ADAPTER_ENV = "CARDDEMO_ADAPTER"
WORKDIR_ENV = "CARDDEMO_TEST_WORKDIR"
WORK_ROOT_PREFIX = "carddemo-"

DEFAULT_ADAPTER = "gnucobol-batch"
# Adapters whose deployment refuses a work root whose path holds whitespace or
# a quote (adapters/unikix/deploy.py), which the JVM option variables naming
# the deployment's temporary directory cannot carry, or ':', at which COBCPY,
# PATH and LD_LIBRARY_PATH naming the deployment's directories are split, so
# the session holds the configured root's path to the same rule and replaces
# one it refuses, as it replaces any other unusable root; the set is named for
# the first part of that rule. Every adapter's root must be the user's own and
# writable by neither group nor others.
JVM_SAFE_PATH_ADAPTERS = frozenset({"unikix"})

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
# Set only when the work root is the configured one: the lock this session
# holds on it until pytest_unconfigure. A root made with mkdtemp is this
# session's alone, so it needs none.
WORK_ROOT_LOCK_KEY = pytest.StashKey[WorkRootLock]()

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


def _work_root(adapter: str = DEFAULT_ADAPTER) -> tuple[Path, bool, str | None]:
    """The session's resolved work root for ``adapter``, whether this session
    created it, and the warning to give when ``CARDDEMO_TEST_WORKDIR`` is set
    but not used (``None`` otherwise).

    A set value is used only when ``harness.usable_work_root`` accepts it: not
    blank, neither the repository nor inside it, a path holding no
    backquote, ``$``, ``"``, ``\\`` or character that is not printable and
    no longer than the work-root limit (all checked before anything is
    created, so a refused root leaves nothing behind), created with mode 0700
    when missing, a directory, the user's own and writable by neither group
    nor others, and readable, writable and searchable by the user, its owner
    (it opens as the runner opens it, and a directory can be made in it); for
    an adapter of ``JVM_SAFE_PATH_ADAPTERS`` also a path holding no
    whitespace, no quote and no ``:``. A refused or unset value
    is replaced by a fresh ``tempfile.mkdtemp(prefix="carddemo-")`` root,
    which this session owns. That root must lie outside the repository and
    pass the same path rule (``harness.check_work_root_path``) too: when the
    temporary directory lies inside the repository, no temporary root can be
    made, or the one made fails the rule (it is then removed), there is no
    work root at all, and the usage error names the refused value as well.
    It is made by ``mkdtemp``, so it is private. The configured value is used
    as given, without ``~`` expansion, as ``harness.results`` reads it to
    keep work-root paths out of result records."""
    configured = os.environ.get(WORKDIR_ENV)
    refused = None
    if configured is not None:
        try:
            return usable_work_root(configured, jvm_safe_path=adapter in JVM_SAFE_PATH_ADAPTERS), False, None
        except HarnessError as exc:
            refused = f"{WORKDIR_ENV}={configured!r} is not a usable work root: {exc}"
    try:
        _refuse_inside_repository(Path(tempfile.gettempdir()).resolve(), "the temporary directory")
        root = Path(tempfile.mkdtemp(prefix=WORK_ROOT_PREFIX)).resolve()
        try:
            check_work_root_path(root)
        except HarnessError:
            with contextlib.suppress(OSError):
                root.rmdir()
            raise
    except (OSError, pytest.UsageError, HarnessError) as exc:
        if isinstance(exc, pytest.UsageError):
            problem = str(exc)
        elif isinstance(exc, HarnessError):
            problem = f"the temporary work root cannot be used: {exc}"
        else:
            problem = f"no temporary work root can be made: {exc}"
        if refused is not None:
            problem = f"{refused}, and no fresh temporary work root can replace it: {problem}"
        raise pytest.UsageError(problem) from exc
    if refused is None:
        return root, True, None
    return root, True, f"{refused}; this session uses the fresh temporary work root {root} instead"


def _lock_work_root(root: Path) -> WorkRootLock:
    """The lock of the configured work root ``root``, held until
    ``pytest_unconfigure``. Two sessions sharing a root would empty each
    other's live scenario working directories, so a root another session
    holds is a usage error naming the root and that session, as is a lock
    that cannot be taken."""
    try:
        return lock_work_root(root)
    except HarnessError as exc:
        raise pytest.UsageError(f"{WORKDIR_ENV}: {exc}") from exc


def pytest_configure(config: pytest.Config) -> None:
    adapter = _selected_adapter()
    config.stash[ADAPTER_NAME_KEY] = adapter
    root, created, warning = _work_root(adapter)
    if not created:
        config.stash[WORK_ROOT_LOCK_KEY] = _lock_work_root(root)
    config.stash[WORK_ROOT_KEY] = root
    if created:
        config.stash[CREATED_WORK_ROOT_KEY] = root
    # Given after the root is stashed, so pytest_unconfigure still removes the
    # empty temporary root when -W turns the warning into an error.
    if warning is not None:
        config.issue_config_time_warning(pytest.PytestConfigWarning(warning), stacklevel=2)


def pytest_unconfigure(config: pytest.Config) -> None:
    lock = config.stash.get(WORK_ROOT_LOCK_KEY, None)
    if lock is not None:
        lock.release()
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
