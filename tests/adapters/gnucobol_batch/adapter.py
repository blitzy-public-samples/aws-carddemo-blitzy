"""``GnuCOBOLBatchAdapter``: the ``gnucobol-batch`` execution adapter.

It runs the batch side of CardDemo on Linux with GnuCOBOL 3.2, implementing
``adapters.base.Adapter``:

Program scenarios (``inputs.program``)
    The unit of ``programs.yaml`` runs standalone on the scenario's fixture
    datasets (``datasets.materialize_program``), through ``jobs.run_program``:
    its executable, ``PARMDRV`` (CBACT04C, with the scenario's ``parm``) or
    ``DTCDRV`` (CSUTLDTC). ``Result.datasets`` holds the unit's output and I-O
    datasets (``datasets.read_back_program``) and every other dataset of the
    unit the scenario's ``expected`` names.
Job scenarios (``inputs.job``, one job or a chain)
    The ``jobs.yaml`` step tables run against one per-scenario catalog
    (``datasets.Catalog.seed``, ``jobs.run_jobs``). ``Result.datasets`` holds
    every logical dataset the jobs' DDs map, the preconditions name or
    ``expected`` names that exists at the end, a GDG logical as its newest
    generation.

Observations: business datasets, ``@CATALOG`` and ``@AIX:<path>``. Business
datasets are read back on every run, whether or not ``expected`` names them,
as the program and job paragraphs above describe, with every other dataset
of the run that ``expected`` names and that exists. ``@CATALOG`` and
``@AIX:<path>`` are produced only when ``expected`` names them, and an
``@AIX:<path>`` whose path, alternate index or base cluster is not catalogued
is omitted. Screens, ``@REGION-FILES`` and ``@TDQ:JOBS`` need a UniKix
region and are never produced here.

Faults: an ``open`` fault with condition ``unavailable`` leaves its dataset
out, so the program's OPEN fails with status 35; every other fault raises
``MissingPrerequisite(need, "GAP-MIS-013")``, since nothing here injects a
failure after a successful open. An ``open``/``unavailable`` fault is
refused the same way when the run never needs its target. A program run
needs only the logical and ``also_accepts`` names of the dds that the unit,
or a unit it calls, opens INPUT, I-O or EXTEND: no SELECT in ``app/cbl`` is
OPTIONAL, so such an OPEN fails on a missing file, while OPEN OUTPUT
creates one. A job run needs only the datasets that a dd or IDCAMS command
of its jobs uses. Every fault is refused before any dataset is laid out.

Page zero (GAP-MIS-002): a scenario that runs CBSTM03A (a unit with a
``tiot`` in ``programs.yaml``), directly or as a step of its jobs, needs the
page-zero shim. ``page0_probe`` from the build cache decides once per session
whether page zero can be mapped; when it cannot, the scenario raises
``AdapterUnavailable(reason, "GAP-MIS-002")`` with the reason
``page zero not mappable: <errno>; vm.mmap_min_addr=<n>``, ``<errno>`` the
number the probe reports and ``<n>`` the minimum, each ``unknown`` when there
is none. The adapter only reads ``/proc/sys/vm/mmap_min_addr`` and never
changes a kernel setting; that value alone is never taken as proof.

Session and state: the build (``build.ensure_built``) and the probe are
session-level work, not scenario time, so ``prepare`` moves the scenario
deadline on by the time they took, whether they succeed or raise; a deadline
already past when ``prepare`` began stays past. A missing or unsuitable
toolchain (GAP-MIS-019) is a missing runtime: every scenario raises
``AdapterUnavailable`` and ``build_key`` is ``None``; a failed build is a
harness error, never a gap. Everything a scenario writes lies in its
working directory; the build cache under ``<work root>/_build/`` is the only
state shared between scenarios. Construction builds nothing, starts no
process and creates no file or directory.
"""

from __future__ import annotations

import os
import re
import tempfile
import threading
import time
import warnings
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from adapters.base import (
    AIX_PREFIX,
    BUSINESS_DATASETS,
    CATALOG,
    REGION_FILES,
    SCREEN_MAP,
    SCREEN_TEXT,
    SCREENS,
    TDQ_JOBS,
    Adapter,
    AdapterUnavailable,
    MissingPrerequisite,
    Result,
    required_observations,
)
from adapters.gnucobol_batch import build as _build
from adapters.gnucobol_batch import datasets as _datasets
from adapters.gnucobol_batch import jobs as _jobs
from harness import DEFAULT_CLOCK, REPO_ROOT, HarnessError

__all__ = ["ADAPTER_NAME", "GnuCOBOLBatchAdapter"]

ADAPTER_NAME = "gnucobol-batch"

_WORKDIR_ENV = "CARDDEMO_TEST_WORKDIR"
_WORK_ROOT_PREFIX = "carddemo-"
_INPUTS_DIR = "inputs"
_EVIDENCE_DIR = "evidence"

_GAP_PAGE_ZERO = "GAP-MIS-002"
_GAP_UNIKIX = "GAP-MIS-009"
_GAP_REGION_FILES = "GAP-MIS-011"
_GAP_JOBS_CAPTURE = "GAP-MIS-017"
_GAP_TOOLCHAIN = "GAP-MIS-019"

_OBSERVATIONS = frozenset({BUSINESS_DATASETS, CATALOG, AIX_PREFIX + "*"})
_SCREEN_NAMES = frozenset({SCREENS, SCREEN_MAP, SCREEN_TEXT})

_MMAP_MIN_ADDR = Path("/proc/sys/vm/mmap_min_addr")
_PROBE_TIMEOUT_S = 60
_PROBE_OK_PREFIX = "page0 mappable"
_PROBE_ERRNO_RE = re.compile(r"^errno=([0-9]+)")
_PROBE_MINIMUM_RE = re.compile(r";\s*vm\.mmap_min_addr=(\S*)\s*$")
_BUILD_UNAVAILABLE = "not run: the gnucobol-batch build is unavailable"


def _mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _section(scenario: Any, name: str) -> Mapping[str, Any]:
    """A section of the scenario object (``inputs``, ``preconditions``,
    ``expected``), or of a scenario given as a mapping."""
    value = getattr(scenario, name, None)
    if value is None and isinstance(scenario, Mapping):
        value = scenario.get(name)
    return _mapping(value)


def _scenario_id(scenario: Any) -> str:
    value = getattr(scenario, "id", None)
    if value is None and isinstance(scenario, Mapping):
        value = scenario.get("id")
    if not isinstance(value, str) or not value:
        raise HarnessError(f"a scenario without an id cannot run on {ADAPTER_NAME}: {scenario!r}")
    return value


def _read_mmap_min_addr() -> int | None:
    """``vm.mmap_min_addr`` as the kernel reports it, read only."""
    try:
        return int(_MMAP_MIN_ADDR.read_text(encoding="ascii").strip())
    except (OSError, ValueError):
        return None


@dataclass(frozen=True)
class _ProbeResult:
    """What ``page0_probe`` found. ``detail`` is its line, which carries no
    path, date or duration; ``errno`` the number that line starts with;
    ``minimum`` the ``vm.mmap_min_addr`` in force."""

    ok: bool
    detail: str
    errno: int | None
    minimum: int | None

    @property
    def reason(self) -> str:
        """The ``AdapterUnavailable`` reason of a failed probe,
        ``page zero not mappable: <errno>; vm.mmap_min_addr=<n>``: ``<errno>``
        is ``errno`` (``0`` for a misplaced mapping or a read-back mismatch)
        and ``<n>`` is ``minimum``, each ``unknown`` when there is none (a
        timeout, a signal before any line, no output). ``detail`` keeps the
        probe's own words."""
        shown_errno = self.errno if self.errno is not None else "unknown"
        shown = self.minimum if self.minimum is not None else "unknown"
        return f"page zero not mappable: {shown_errno}; vm.mmap_min_addr={shown}"

    def as_environment(self) -> dict[str, Any]:
        return {"ok": self.ok, "detail": self.detail, "errno": self.errno}


@dataclass
class _ScenarioState:
    """What ``prepare`` laid out for ``execute``: ``unit`` and its configs and
    ``dd_paths`` for a program scenario, ``catalog`` for a job scenario."""

    cache: _build.BuildCache
    store: _datasets.DatasetStore
    workdir: Path
    clock: str
    parameters: Mapping[str, Any]
    unit: str | None = None
    unit_cfg: Mapping[str, Any] | None = None
    called_cfgs: tuple[Mapping[str, Any], ...] = ()
    dd_paths: Mapping[str, Path] | None = None
    jobs: tuple[str, ...] = ()
    catalog: _datasets.Catalog | None = None


def _job_logicals(job_names: Iterable[str]) -> set[str]:
    """Every logical dataset a DD (or a concatenation member) of the jobs'
    step tables maps."""
    names: set[str] = set()
    for job in job_names:
        for step in _jobs.job_steps(job):
            for dd in _mapping(step.get("dd")).values():
                entries = [dd, *(dd.get("concat") or [])] if isinstance(dd, Mapping) else []
                for entry in entries:
                    logical = entry.get("logical") if isinstance(entry, Mapping) else None
                    if isinstance(logical, str):
                        names.add(logical)
    return names


def _runs_page_zero_unit(unit: str) -> bool:
    """Whether ``unit`` walks the z/OS control blocks from address 0: the
    unit ``programs.yaml`` gives a ``tiot`` (CBSTM03A)."""
    return _jobs.unit_config(unit).get("tiot") is not None


class GnuCOBOLBatchAdapter(Adapter):
    """The ``gnucobol-batch`` adapter (pytest marker ``gnucobol_batch``).

    ``work_root`` defaults to ``CARDDEMO_TEST_WORKDIR``, else to a directory
    ``tempfile.mkdtemp(prefix="carddemo-")`` creates when the work root is
    first needed; ``repo_root`` defaults to ``harness.REPO_ROOT``. ``capable``
    is the base rule (``harness.scenarios.capable_adapters``)."""

    name = ADAPTER_NAME

    def __init__(
        self, work_root: Path | str | None = None, repo_root: Path | str | None = None
    ) -> None:
        self._lock = threading.RLock()
        self._work_root: Path | None = None
        if work_root is None:
            configured = os.environ.get(_WORKDIR_ENV, "")
            work_root = configured if configured.strip() else None
        # The base contract forbids creating anything at construction, so a
        # default temporary work root is created only on first use (the
        # work_root property); Path() is a placeholder that touches nothing.
        super().__init__(
            Path(work_root) if work_root is not None else Path(),
            REPO_ROOT if repo_root is None else repo_root,
        )
        if work_root is None:
            self._work_root = None
        self._cache: _build.BuildCache | None = None
        self._build_attempted = False
        self._build_key: str | None = None
        self._probe_result: _ProbeResult | None = None
        self._environment_written = False
        self._states: dict[str, _ScenarioState] = {}

    # ---------------------------------------------------------------- session

    @property
    def work_root(self) -> Path:
        with self._lock:
            if self._work_root is None:
                self._work_root = Path(tempfile.mkdtemp(prefix=_WORK_ROOT_PREFIX))
            return self._work_root

    @work_root.setter
    def work_root(self, value: Path | str | None) -> None:
        self._work_root = None if value is None else Path(value)

    def _current_deadline(self) -> float | None:
        return self.deadline

    def _ensure_cache(self) -> tuple[_build.BuildCache, bool]:
        """The session's build cache and whether this call built or first
        loaded it. A missing or unsuitable toolchain
        (``build.ToolchainUnavailable``) is a missing runtime:
        ``AdapterUnavailable`` under GAP-MIS-019, so the scenario is
        ``blocked``. Every other ``BuildError`` propagates: a failed build is
        a harness error, never a registered gap."""
        with self._lock:
            self._build_attempted = True
            try:
                cache = _build.ensure_built(self.repo_root, self.work_root)
            except _build.ToolchainUnavailable as exc:
                raise AdapterUnavailable(str(exc), _GAP_TOOLCHAIN) from exc
            fresh = cache is not self._cache
            self._cache = cache
            self._build_key = cache.key
            return cache, fresh

    def _run_probe(self, cache: _build.BuildCache) -> _ProbeResult:
        """Run ``page0_probe`` once. A probe that cannot finish within
        ``_PROBE_TIMEOUT_S`` counts as page zero not mappable."""
        try:
            outcome = _datasets.run_process(
                [cache.probe],
                env=_datasets.runtime_env(cache),
                cwd=self.work_root,
                deadline=time.monotonic() + _PROBE_TIMEOUT_S,
            )
        except _datasets.ProcessTimeout:
            return _ProbeResult(
                False, f"page0_probe did not finish within {_PROBE_TIMEOUT_S} s", None,
                _read_mmap_min_addr(),
            )
        text = outcome.stdout.decode("latin-1")
        line = next((entry.strip() for entry in text.splitlines() if entry.strip()), "")
        minimum = _read_mmap_min_addr()
        if minimum is None:
            reported = _PROBE_MINIMUM_RE.search(line)
            if reported is not None and reported.group(1).isdigit():
                minimum = int(reported.group(1))
        errno_match = _PROBE_ERRNO_RE.match(line)
        errno = int(errno_match.group(1)) if errno_match else None
        if outcome.signal is not None:
            detail = f"page0_probe ended by signal {outcome.signal}" + (f"; {line}" if line else "")
            return _ProbeResult(False, detail, errno, minimum)
        ok = outcome.returncode == 0 and line.startswith(_PROBE_OK_PREFIX)
        if not line:
            line = f"page0_probe exited with status {outcome.returncode} and printed nothing"
        return _ProbeResult(ok, line, errno, minimum)

    def _probe(self, cache: _build.BuildCache) -> tuple[_ProbeResult, bool]:
        """The session's probe result and whether this call ran the probe.
        The first result is recorded in the runtime environment file, which
        feeds the Environment section of ``coverage-report.md``."""
        with self._lock:
            result = self._probe_result
            fresh = result is None
            if result is None:
                result = self._run_probe(cache)
                self._probe_result = result
        self._record_environment()  # returns at once after a successful write
        return result, fresh

    def _record_environment(self) -> None:
        """Write ``environment()`` once the probe has run. A write that fails
        is a warning and is retried after the next probe call, as
        harness/runner.py treats its own write: bookkeeping never changes a
        scenario's outcome."""
        from harness import results  # imports the harness modules, so only when needed

        writer = getattr(results, "write_environment", None)
        if not callable(writer):
            return
        with self._lock:
            if self._environment_written:
                return
            self._environment_written = True
        try:
            writer(self.environment())
        except (OSError, HarnessError) as exc:
            with self._lock:
                self._environment_written = False
            warnings.warn(
                f"{ADAPTER_NAME}: environment not written: {type(exc).__name__}: {exc}",
                RuntimeWarning,
                stacklevel=2,
            )

    def warm_up(self) -> None:
        """Build the cache and run the page-zero probe now, before any
        scenario deadline runs; ``prepare`` otherwise does both on first use.
        Raises ``AdapterUnavailable`` (GAP-MIS-019) when the toolchain is
        unavailable, and ``BuildError`` when the build fails."""
        cache, _ = self._ensure_cache()
        self._probe(cache)

    def _needs_page_zero(self, scenario: Any) -> bool:
        program = _section(scenario, "inputs").get("program")
        if isinstance(program, str):
            return _runs_page_zero_unit(program)
        for job in _datasets.scenario_jobs(scenario):
            for step in _jobs.job_steps(job):
                unit = step.get("program")
                if step.get("kind") != "program" or not isinstance(unit, str):
                    continue
                if _runs_page_zero_unit(unit):
                    return True
        return False

    # ----------------------------------------------------------- observations

    def observations(self) -> set[str]:
        return set(_OBSERVATIONS)

    def observation_gap(self, name: str) -> str | None:
        if self.can_observe(name):
            return None
        if name == REGION_FILES:
            return _GAP_REGION_FILES
        if name == TDQ_JOBS:
            return _GAP_JOBS_CAPTURE
        if name in _SCREEN_NAMES:
            return _GAP_UNIKIX
        return None

    # ---------------------------------------------------------------- prepare

    def _checked_workdir(self, workdir: Path | str) -> Path:
        """The scenario working directory, resolved: an existing directory
        below the work root, where every write of the scenario stays."""
        root = Path(workdir).resolve()
        work_root = self.work_root.resolve()
        if work_root not in root.parents:
            raise HarnessError(
                f"the working directory {root} does not lie below the work root {work_root}"
            )
        if not root.is_dir():
            raise HarnessError(f"the working directory {root} does not exist")
        return root

    def _extend_deadline(self, seconds: float) -> None:
        if self.deadline is not None:
            self.deadline += seconds

    def prepare(self, scenario: Any, workdir: Path) -> None:
        scenario_id = _scenario_id(scenario)
        root = self._checked_workdir(workdir)
        started = time.monotonic()
        try:
            cache, built = self._ensure_cache()
            probe, probed = self._probe(cache) if self._needs_page_zero(scenario) else (None, False)
        except BaseException:
            # A build or probe that fails took session time as well, so what it
            # raises keeps its own outcome (blocked, or failing with reason
            # error) instead of a timeout; a deadline already past stays past.
            self._extend_deadline(time.monotonic() - started)
            raise
        if built or probed:
            # The build and the probe are session setup, not scenario time: the
            # scenario keeps its whole deadline, which harness/runner.py reads back.
            self._extend_deadline(time.monotonic() - started)
        # Page zero is decided before the faults, so a CBSTM03A fault case is
        # blocked with the rest of CBSTM03A when the probe fails.
        if probe is not None and not probe.ok:
            raise AdapterUnavailable(probe.reason, _GAP_PAGE_ZERO)
        preconditions = _section(scenario, "preconditions")
        faults = preconditions.get("faults") or []
        if not isinstance(faults, list):
            raise HarnessError(
                f"{scenario_id}: preconditions.faults must be a list, got {faults!r}"
            )
        # MissingPrerequisite (GAP-MIS-013) for any fault but open/unavailable,
        # before a dataset is touched.
        left_out = _datasets.unavailable_targets(faults)
        parameters = _mapping(preconditions.get("parameters"))
        clock = parameters.get("clock", DEFAULT_CLOCK)
        _jobs.cob_current_date(clock)  # refuses a clock without its hundredths
        self.check_deadline(f"{scenario_id}: before its datasets were laid out")
        state = _ScenarioState(
            cache=cache,
            store=_datasets.DatasetStore(root, cache, self._current_deadline),
            workdir=root,
            clock=clock,
            parameters=parameters,
        )
        inputs_dir = root / _INPUTS_DIR
        program = _section(scenario, "inputs").get("program")
        if isinstance(program, str):
            self._prepare_program(scenario, state, program, inputs_dir, faults, left_out)
        else:
            self._prepare_jobs(scenario, scenario_id, state, inputs_dir, faults, left_out)
        with self._lock:
            self._states[scenario_id] = state

    def _prepare_program(
        self,
        scenario: Any,
        state: _ScenarioState,
        program: str,
        inputs_dir: Path,
        faults: list,
        left_out: set[str],
    ) -> None:
        unit_cfg = _jobs.unit_config(program)
        called = tuple(_jobs.called_configs(program))
        # Leaving a dataset out fails an OPEN only for a dd of the run opened
        # INPUT, I-O or EXTEND, under the names materialize_program tries (its
        # logical, then its also_accepts): no SELECT in app/cbl is OPTIONAL, so
        # such an OPEN ends with status 35, while OPEN OUTPUT creates the file.
        # A target that only OUTPUT dds name, or that no dd names, cannot be
        # provided, so it is refused before a dataset is laid out.
        dds = _datasets.program_dds(unit_cfg, called)
        opened = {
            name
            for _, dd in dds
            if dd.get("open") != "output"
            for name in [dd.get("logical"), *(dd.get("also_accepts") or [])]
        }
        created = {dd.get("logical") for _, dd in dds if dd.get("open") == "output"}
        refused = sorted(left_out - opened)
        if refused:
            units = " or ".join([program, *(unit_cfg.get("calls") or [])])
            unmapped = [name for name in refused if name not in created]
            output_only = [name for name in refused if name in created]
            them = "it" if len(refused) == 1 else "them"
            causes = []
            if unmapped:
                named = ", ".join(unmapped) if output_only else them
                causes.append(f"no DD of {units} maps {named}")
            if output_only:
                named = ", ".join(output_only) if unmapped else them
                creates = "it" if len(output_only) == 1 else "them"
                causes.append(f"{units} opens {named} only OUTPUT, which creates {creates}")
            raise MissingPrerequisite(
                f"open fault (unavailable) on {', '.join(refused)}: {', and '.join(causes)}, "
                f"so leaving {them} out fails no OPEN of the run",
                "GAP-MIS-013",
            )
        store = state.store
        dd_paths = _datasets.materialize_program(
            unit_cfg, called, state.workdir, inputs_dir, faults, store=store
        )
        # materialize_program only reserves the path of an output dataset, and
        # libcob's OPEN OUTPUT fails with status 30 when its directory is absent:
        # CBSTM03A opens STMTFILE and HTMLFILE before any input exists.
        store.inside(store.data_dir).mkdir(parents=True, exist_ok=True)
        # The runner writes inputs/DATEPARM.dat from preconditions.parameters
        # .DATEPARM; a caller that does not still gets the record from the
        # parameter, unless an open/unavailable fault leaves DATEPARM out.
        for ddname, dd in _datasets.program_dds(unit_cfg, called):
            path = dd_paths.get(ddname)
            if dd.get("logical") != _jobs.DATEPARM or _jobs.DATEPARM in left_out:
                continue
            if path is None or store.exists(path):
                continue
            record = _jobs.dateparm_record(scenario, inputs_dir)
            if record is not None:
                length = dd.get("record_length") or _jobs.DATEPARM_RECORD_LENGTH
                store.write_sequential(path, record, length)
        state.unit = program
        state.unit_cfg = unit_cfg
        state.called_cfgs = called
        state.dd_paths = dd_paths

    def _prepare_jobs(
        self,
        scenario: Any,
        scenario_id: str,
        state: _ScenarioState,
        inputs_dir: Path,
        faults: list,
        left_out: set[str],
    ) -> None:
        names = tuple(_datasets.scenario_jobs(scenario))
        if not names:
            raise HarnessError(f"{scenario_id} names neither inputs.program nor inputs.job")
        jobs_cfg = _jobs.load_jobs()
        dsn_map = _datasets.DsnMap(jobs_cfg)
        catalog = _datasets.Catalog.seed(
            scenario, jobs_cfg, inputs_dir, state.store, faults, dsn_map=dsn_map
        )
        # AWS.M2.CARDDEMO.DATEPARM comes from the scenario (GAP-MIS-001): from
        # inputs/DATEPARM.dat, which Catalog.seed catalogues, or else from the
        # preconditions.parameters.DATEPARM text.
        target = dsn_map.dsn_for(_jobs.DATEPARM)
        if (
            target is not None
            and _jobs.DATEPARM not in left_out
            and _jobs.DATEPARM in _job_logicals(names)
            and catalog.get(target[0]) is None
        ):
            record = _jobs.dateparm_record(scenario, inputs_dir)
            if record is not None:
                catalog.seed_dataset(_jobs.DATEPARM, record)
        state.jobs = names
        state.catalog = catalog

    # ---------------------------------------------------------------- execute

    def execute(self, scenario: Any, workdir: Path) -> Result:
        scenario_id = _scenario_id(scenario)
        with self._lock:
            state = self._states.get(scenario_id)
        if state is None:
            raise HarnessError(f"{scenario_id}: execute was called before prepare laid it out")
        if self._checked_workdir(workdir) != state.workdir:
            raise HarnessError(
                f"{scenario_id}: execute was given another working directory than prepare"
            )
        wanted = required_observations(_section(scenario, "expected"))
        started = time.monotonic()
        self.check_deadline(f"{scenario_id}: before it ran")
        if state.unit is not None:
            return_code, messages, data = self._execute_program(state, wanted)
        else:
            return_code, messages, data = self._execute_jobs(scenario, state, wanted)
        return Result(
            return_code=return_code,
            datasets=dict(sorted(data.items())),
            screens=[],
            messages=messages,
            elapsed_s=time.monotonic() - started,
        )

    def _execute_program(
        self, state: _ScenarioState, wanted: set[str]
    ) -> tuple[int | None, list[str], dict[str, bytes]]:
        unit, unit_cfg, dd_paths = state.unit, state.unit_cfg, state.dd_paths
        if unit is None or unit_cfg is None or dd_paths is None:
            raise HarnessError("a program scenario reached execute without its unit laid out")
        outcome = _jobs.run_program(
            state.cache,
            unit,
            dd_paths,
            workdir=state.workdir,
            deadline=self.deadline,
            clock=state.clock,
            parm=state.parameters.get("parm"),
            ceedays_feedback=state.parameters.get("ceedays_feedback"),
            tiot=unit_cfg.get("tiot"),
            evidence_dir=state.workdir / _EVIDENCE_DIR,
        )
        store = state.store
        data = _datasets.read_back_program(unit_cfg, state.called_cfgs, dd_paths, store=store)
        # An input the scenario asserts on is read back too; one whose file was
        # never there stays absent, so its decoded value is @missing.
        for ddname, dd in _datasets.program_dds(unit_cfg, state.called_cfgs):
            logical, path = dd.get("logical"), dd_paths.get(ddname)
            if logical not in wanted or logical in data or path is None or not store.exists(path):
                continue
            if dd.get("organization") == "indexed":
                data[logical] = store.unload_indexed(path)
            else:
                data[logical] = store.read_sequential(path)
        if CATALOG in wanted or any(name.startswith(AIX_PREFIX) for name in wanted):
            jobs_cfg = _jobs.load_jobs()
            catalog = _datasets.Catalog(
                store, _datasets.DsnMap(jobs_cfg), _datasets.Catalog.gdg_seed(jobs_cfg)
            )
            catalog.register_program_datasets(unit_cfg, state.called_cfgs, dd_paths)
            data.update(self._catalog_observations(catalog, wanted))
        return outcome.return_code, list(outcome.messages), data

    def _execute_jobs(
        self, scenario: Any, state: _ScenarioState, wanted: set[str]
    ) -> tuple[int | None, list[str], dict[str, bytes]]:
        catalog = state.catalog
        if catalog is None or not state.jobs:
            raise HarnessError("a job scenario reached execute without its catalog")
        outcome = _jobs.run_jobs(
            state.cache,
            scenario,
            catalog,
            workdir=state.workdir,
            deadline=self.deadline,
            clock=state.clock,
            inputs_dir=state.workdir / _INPUTS_DIR,
            repo_root=self.repo_root,
        )
        names = _job_logicals(state.jobs)
        preconditions, inputs = _section(scenario, "preconditions"), _section(scenario, "inputs")
        names.update(str(name) for name in _mapping(preconditions.get("datasets")))
        names.update(str(name) for name in _mapping(inputs.get("param_files")))
        if _jobs.DATEPARM in state.parameters:
            names.add(_jobs.DATEPARM)
        names.update(name for name in wanted if not name.startswith("@"))
        data: dict[str, bytes] = {}
        for logical in sorted(names):
            value = catalog.read_logical(logical)
            if value is not None:
                data[logical] = value
        data.update(self._catalog_observations(catalog, wanted))
        return outcome.return_code, list(outcome.messages), data

    @staticmethod
    def _catalog_observations(catalog: _datasets.Catalog, wanted: set[str]) -> dict[str, bytes]:
        """``@AIX:<path>`` for each path ``expected`` names whose path, index
        and base cluster are catalogued (omitted otherwise), and ``@CATALOG``
        when ``expected`` names it."""
        found: dict[str, bytes] = {}
        for name in sorted(wanted):
            if name.startswith(AIX_PREFIX):
                value = catalog.aix_observation(name[len(AIX_PREFIX):])
                if value is not None:
                    found[name] = value
        if CATALOG in wanted:
            found[CATALOG] = catalog.catalog_observation()
        return found

    # ---------------------------------------------------------------- cleanup

    def cleanup(self, scenario: Any, workdir: Path) -> None:
        scenario_id = getattr(scenario, "id", None)
        if scenario_id is None and isinstance(scenario, Mapping):
            scenario_id = scenario.get("id")
        with self._lock:
            if isinstance(scenario_id, str):
                self._states.pop(scenario_id, None)
        # Ends any process group still registered, a timed-out or interrupted
        # program's included; with none it does nothing.
        _datasets.kill_all()

    def close(self) -> None:
        with self._lock:
            self._states.clear()
        _datasets.kill_all()

    # --------------------------------------------------------------- identity

    def source_identity(self, scenario: Any) -> dict:
        from harness import results  # imports the harness modules, so only when needed

        identity = results.source_identity(scenario, ADAPTER_NAME, self.repo_root)
        if identity is None:
            raise HarnessError(f"harness.results gives no {ADAPTER_NAME} source identity")
        return identity

    def build_key(self, scenario: Any = None) -> str | None:
        """The session build key: the content hash ``build.py`` names the
        cache by, the same for every run of the same sources and toolchain.
        ``None`` while the toolchain is unavailable, since nothing can be
        built, so a scenario blocked under GAP-MIS-019 keeps that verdict and
        its execution id; any other ``BuildError`` propagates."""
        with self._lock:
            if self._build_key is None:
                try:
                    self._build_key = _build.compute_build_key(self.repo_root)
                except _build.ToolchainUnavailable:
                    return None
            return self._build_key

    def environment(self) -> dict[str, Any] | None:
        """The facts of this adapter's environment for the Environment section
        of ``coverage-report.md``: no path, date or duration. ``None`` until
        the session has tried to build, so the runner asks again later; once
        the cache exists the page-zero probe is run if no scenario needed it."""
        with self._lock:
            cache, attempted, probe = self._cache, self._build_attempted, self._probe_result
        if cache is None:
            if not attempted:
                return None
            return {
                "adapter": ADAPTER_NAME,
                "cobc_version": None,
                "page0_probe": {"ok": None, "detail": _BUILD_UNAVAILABLE, "errno": None},
                "vm_mmap_min_addr": _read_mmap_min_addr(),
            }
        if probe is None:
            probe, _ = self._probe(cache)
        lines = [line.strip() for line in cache.cobc_version.splitlines() if line.strip()]
        return {
            "adapter": ADAPTER_NAME,
            "cobc_version": lines[0] if lines else None,
            "page0_probe": probe.as_environment(),
            "vm_mmap_min_addr": probe.minimum,
        }
