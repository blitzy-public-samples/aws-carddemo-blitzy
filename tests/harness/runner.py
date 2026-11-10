"""Scenario execution: working directory, inputs, observation rule, deadline,
adapter calls, comparison and the result record.

Every test module runs its scenarios the same way::

    outcome = runner.run(scenario, carddemo_adapter)
    carddemo_record(outcome)          # harness.results.write(outcome)
    runner.conclude(outcome)

``run`` turns everything a scenario or an adapter can do into an ``Outcome``
whose ``status`` is ``passed``, ``failing``, ``blocked`` or
``implemented-unexecuted``; it raises only for a caller error (an adapter
whose name is not one of ``harness.ADAPTER_NAMES``, an unknown scenario id).
``conclude`` maps the outcome onto pytest: ``passed`` returns, ``blocked`` and
``implemented-unexecuted`` skip with ``"<status>: <reason> [<gap>] ..."``,
``failing`` raises ``AssertionError`` carrying the diff.

Steps of ``run``
    1. ``harness.scenarios.validate``: a schema problem is ``failing`` with
       reason ``error``, and nothing runs.
    2. Capability (``scenarios.capable_adapters``): a scenario the adapter
       cannot run is ``blocked``; ``conftest.py`` normally deselects it.
    3. ``harness.baselines.load``: comparing without a baseline is ``failing``
       with reason ``error``.
    4. A fresh ``<work root>/<scenario_id>/`` (the adapter's ``work_root``,
       never inside the repository) and the deadline: ``time.monotonic()``
       plus ``DEADLINE_S`` (300 s), set as ``adapter.deadline``.
    5. ``harness.fixtures.build``: the precondition and parameter datasets,
       written to ``inputs/`` (below). ``FixtureError`` (an unregistered
       cross-check mismatch included) is ``failing``/``error``.
    6. ``results.execution_fingerprint`` over the bytes actually written.
    7. The observation rule: every observation ``expected`` names
       (``adapters.base.required_observations``) must be one the adapter can
       produce, or the scenario is ``blocked`` under the adapter's gap for it,
       so no scenario passes on a success message alone.
    8. ``adapter.prepare`` and ``adapter.execute``; ``adapter.cleanup``
       always, in a ``finally``.
    9. ``baselines.decode_result``, ``baselines.compare`` and
       ``baselines.projection`` on the ``Result``.
    10. The result record (``results.build_record``), the adapter's evidence
        (``write_evidence``), the working directory kept only for a
        ``failing`` scenario, and the runtime file
        ``tests/reports/runtime/<id>.yaml`` (``results.write_runtime``).

Runner -> adapter files, in ``<workdir>/inputs/``
    ``<LOGICAL>.dat``
        The exact fixed-length bytes of each dataset ``fixtures.build``
        returns: ``preconditions.datasets``, ``inputs.param_files`` and the
        ``DATEPARM`` parameter. A dataset an ``open``/``unavailable`` fault
        targets is written too; the adapter leaves it out.
    ``datasets.yaml``
        ``{<LOGICAL>: {path: inputs/<LOGICAL>.dat, sha256, record_length,
        organization, key: {offset, length} | null, alternate_keys:
        [{offset, length, duplicates, name, path}], layout}}``.
    ``parameters.yaml``
        ``clock`` and ``clock_mode`` (their effective values),
        ``cob_current_date`` (``harness.cob_current_date`` of the clock),
        ``parm``, ``sessions`` (1 unless given), ``ceedays_feedback``,
        ``faults`` (the list as written, ``[]`` when absent) and
        ``param_files`` (``{<LOGICAL>: inputs/<LOGICAL>.dat}``).

Status mapping
    ``AdapterUnavailable`` -> ``blocked``/``adapter-unavailable`` with its
    gap; ``MissingPrerequisite`` -> ``implemented-unexecuted``/
    ``missing-prerequisite`` with its gap; ``ScenarioTimeout``,
    ``TimeoutError`` and ``subprocess.TimeoutExpired`` -> ``failing``/
    ``timeout``; any other exception -> ``failing``/``error``, its traceback
    in the runtime file only.

Timeout precedence
    A ``Result`` that ``execute`` returned before the deadline stands, passed
    or failing, even when ``cleanup`` then fails or overruns; the cleanup
    error goes to the runtime file. Otherwise, once the deadline has passed
    by the end of ``cleanup``, the scenario is ``failing`` with reason
    ``timeout`` whatever was raised, and what was raised goes to the runtime
    file. The deadline read back is ``adapter.deadline`` as the adapter left
    it: an adapter re-arms it after session setup, which is not scenario
    time.

Isolation
    Nothing of one run reaches the next: each scenario gets its own working
    directory, which is removed unless the scenario fails, and
    ``adapter.deadline`` is cleared after ``cleanup``. The only state kept
    across runs is which adapters have had their ``environment()`` written,
    which never affects an outcome. Inside the repository the runner writes
    only through ``harness.results`` (``write_runtime``,
    ``write_environment``).
"""

from __future__ import annotations

import datetime as _dt
import shutil
import subprocess
import sys
import threading
import time
import traceback
import warnings
import weakref
from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

_TESTS_ROOT = str(Path(__file__).resolve().parents[1])
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from adapters import base as _base  # noqa: E402
from harness import (  # noqa: E402
    ADAPTER_NAMES,
    REPO_ROOT,
    HarnessError,
    cob_current_date,
    display_path,
    dump_yaml,
    git_head,
    sha256_hex,
    write_text,
)
from harness import DEADLINE_S as _HARNESS_DEADLINE_S  # noqa: E402
from harness import baselines as _baselines  # noqa: E402
from harness import fixtures as _fixtures  # noqa: E402
from harness import results as _results  # noqa: E402
from harness import scenarios as _scenarios  # noqa: E402

__all__ = [
    "DATASETS_FILE",
    "DEADLINE_S",
    "INPUTS_DIR",
    "PARAMETERS_FILE",
    "Outcome",
    "conclude",
    "run",
]

# --------------------------------------------------------------------------
# Contract
# --------------------------------------------------------------------------

# Seconds a scenario may run. It is read at each run, so a self-test may
# lower it; nothing may raise it above harness.DEADLINE_S.
DEADLINE_S = _HARNESS_DEADLINE_S

INPUTS_DIR = "inputs"
DATASETS_FILE = "datasets.yaml"
PARAMETERS_FILE = "parameters.yaml"

PASSED = _results.STATUS_PASSED
FAILING = _results.STATUS_FAILING
BLOCKED = _results.STATUS_BLOCKED
UNEXECUTED = _results.STATUS_UNEXECUTED

REASON_ERROR = "error"
REASON_TIMEOUT = "timeout"
REASON_UNAVAILABLE = "adapter-unavailable"
REASON_MISSING = "missing-prerequisite"

_TIMEOUT_ERRORS = (_base.ScenarioTimeout, TimeoutError, subprocess.TimeoutExpired)

# The gap an observation is blocked under when the adapter names none:
# unloads, @CATALOG and @AIX:<path> GAP-MIS-007; region file inquiry
# GAP-MIS-011; the JOBS capture GAP-MIS-017; 3270 screens GAP-MIS-005.
_DEFAULT_OBSERVATION_GAPS = {
    _base.REGION_FILES: "GAP-MIS-011",
    _base.TDQ_JOBS: "GAP-MIS-017",
    _base.SCREENS: "GAP-MIS-005",
}
_DEFAULT_DATASET_GAP = "GAP-MIS-007"

# A scenario outside the adapter's capability is blocked under the gap of the
# adapter it needs: UniKix is absent (GAP-MIS-009), or the app/ units, which
# only gnucobol-batch runs standalone, have never been built on UniKix
# (GAP-MIS-008). GAP-MIS-013 (fault injection) does not explain a capability
# gap, so it is not used here.
_CAPABILITY_GAPS = (("unikix", "GAP-MIS-009"), ("gnucobol-batch", "GAP-MIS-008"))

# Adapters whose environment() has been written this session. It records
# bookkeeping only and never changes an outcome.
_ENVIRONMENT_LOCK = threading.Lock()
_ENVIRONMENT_WRITTEN: weakref.WeakSet = weakref.WeakSet()


@dataclass
class Outcome:
    """How one run of a scenario ended.

    ``adapter`` is the adapter's name. ``status`` is one of
    ``harness.results.STATUSES`` with its ``reason`` and, for ``blocked`` and
    ``implemented-unexecuted``, its ``gap_id``. ``diff`` says what differs or
    went wrong for a ``failing`` run. ``record`` is the result record
    (``results.build_record``; ``None`` only when even a ``failing`` record
    could not be built, as ``detail`` then says). ``decoded`` is
    ``baselines.decode_result`` of ``result`` (the record holds its
    projection). ``materialized`` holds the bytes written to ``inputs/``.
    ``workdir`` is the working directory kept for a ``failing`` run, else
    ``None``."""

    scenario_id: str
    adapter: str
    status: str
    reason: str | None = None
    gap_id: str | None = None
    diff: str = ""
    record: dict | None = None
    decoded: dict | None = None
    result: _base.Result | None = None
    detail: str = ""
    materialized: dict[str, bytes] = field(default_factory=dict)
    workdir: Path | None = None


def conclude(outcome: Outcome) -> None:
    """End the calling test as ``outcome`` says: return for ``passed``,
    ``pytest.skip("<status>: <reason> [<gap_id>] <detail>")`` for ``blocked``
    and ``implemented-unexecuted``, ``AssertionError`` with the diff for
    ``failing``."""
    import pytest

    status = outcome.status
    if status == PASSED:
        return
    if status in (BLOCKED, UNEXECUTED):
        message = f"{status}: {outcome.reason} [{outcome.gap_id}]"
        if outcome.detail:
            message += f" {outcome.detail}"
        pytest.skip(message)
    if status == FAILING:
        message = outcome.diff or f"{outcome.reason}: {outcome.detail}"
        if outcome.diff and outcome.detail:
            message += f"\n{outcome.detail}"
        if outcome.workdir is not None:
            message += f"\nworking directory kept: {outcome.workdir}"
        raise AssertionError(message)
    raise AssertionError(f"{outcome.scenario_id}: run ended with unknown status {status!r}")


# --------------------------------------------------------------------------
# Run state
# --------------------------------------------------------------------------


@dataclass
class _Run:
    """What one ``run`` has learned so far. ``status`` stays ``None`` until a
    step decides it."""

    scenario: Any
    adapter: Any
    name: str
    compare: bool
    started: float
    status: str | None = None
    reason: str | None = None
    gap_id: str | None = None
    diff: str = ""
    detail: str = ""
    baseline: Any = None
    workdir: Path | None = None
    materialized: dict[str, bytes] = field(default_factory=dict)
    fingerprint: str | None = None
    result: _base.Result | None = None
    decoded: dict | None = None
    projection: dict | None = None
    identity: Any = None
    execution_id: str | None = None
    # Runtime-file entries: tracebacks, cleanup errors, superseded errors.
    runtime: dict[str, Any] = field(default_factory=dict)
    notes: list[str] = field(default_factory=list)

    @property
    def scenario_id(self) -> str:
        return self.scenario.id

    def settle(
        self, status: str, reason: str | None, gap_id: str | None = None, diff: str = "", detail: str = ""
    ) -> None:
        self.status, self.reason, self.gap_id = status, reason, gap_id
        self.diff, self.detail = diff, detail

    def fail(self, reason: str, diff: str, detail: str = "") -> None:
        self.settle(FAILING, reason, None, diff, detail)

    def error(self, what: str, exc: BaseException) -> None:
        """``failing``/``error`` for an exception, its traceback kept for the
        runtime file."""
        self.runtime.setdefault("traceback", _traceback(exc))
        self.fail(REASON_ERROR, f"{self.scenario_id}: {what}: {type(exc).__name__}: {exc}")

    def note(self, key: str, text: str) -> None:
        """Something worth knowing that does not change the status."""
        self.runtime[key] = text
        self.notes.append(text)


def _traceback(exc: BaseException) -> str:
    return "".join(traceback.format_exception(type(exc), exc, exc.__traceback__))


def _adapter_name(adapter: Any) -> str:
    name = getattr(adapter, "name", None)
    if name not in ADAPTER_NAMES:
        raise HarnessError(f"adapter name {name!r} is not one of {', '.join(ADAPTER_NAMES)}")
    return name


def _scenario_object(scenario: Any) -> Any:
    if isinstance(scenario, str):
        found = _scenarios.get(scenario, missing_ok=True)
        if found is None:
            raise HarnessError(f"{scenario!r} is not a scenario of the id manifest")
        return found
    if not isinstance(getattr(scenario, "id", None), str):
        raise HarnessError(f"{type(scenario).__name__} is not a scenario: it has no id")
    return scenario


# --------------------------------------------------------------------------
# run
# --------------------------------------------------------------------------


def run(scenario: Any, adapter: Any, compare: bool = True) -> Outcome:
    """Run ``scenario`` (a ``harness.scenarios.Scenario``, or its id) on
    ``adapter`` (an ``adapters.base.Adapter``) and return its ``Outcome``.

    With ``compare`` the result is compared with the baseline, which must
    exist. Without it (``capture.py``) the run is not compared and
    ``passed`` means it executed and produced a decodable result; the record
    then carries no ``baseline_version`` when the scenario has no baseline
    yet."""
    state = _Run(_scenario_object(scenario), adapter, _adapter_name(adapter), bool(compare), time.monotonic())
    if _precheck(state):
        _execute(state)
        if state.status is None and state.result is not None:
            _judge(state)
        if state.status is None:
            state.fail(REASON_ERROR, f"{state.scenario_id}: the run ended without a result or a status")
    return _finish(state)


def _precheck(state: _Run) -> bool:
    """Schema, capability and baseline; ``False`` when the run ends here."""
    s = state.scenario
    try:
        problems = _scenarios.validate(s)
    except Exception as exc:  # validate reports rather than raises; keep that promise here
        state.error("the schema check raised", exc)
        return False
    if problems:
        state.fail(REASON_ERROR, f"{s.id}: the scenario fails the schema check:\n" + "\n".join(problems))
        return False
    try:
        capable = _scenarios.capable_adapters(s)
    except Exception as exc:
        state.error("its capable adapters cannot be derived", exc)
        return False
    if state.name not in capable:
        if not capable:
            state.fail(REASON_ERROR, f"{s.id}: no adapter can run it; its inputs map to no adapter's files")
            return False
        gap = next(gap for name, gap in _CAPABILITY_GAPS if name in capable)
        reason = f"{state.name} cannot run {s.id}; it runs on {', '.join(capable)}"
        state.settle(BLOCKED, REASON_UNAVAILABLE, gap, detail=reason)
        return False
    try:
        state.baseline = _baselines.load(s.id, missing_ok=True)
    except Exception as exc:
        state.error("its baseline cannot be read", exc)
        return False
    if state.compare and state.baseline is None:
        state.fail(
            REASON_ERROR,
            f"{s.id}: no baseline: {_baselines.BASELINES_REL}/{s.id}/ does not exist; capture or derive it first",
        )
        return False
    return True


def _fresh_workdir(adapter: Any, scenario_id: str) -> Path:
    """``<work root>/<scenario_id>/``, emptied: whatever a previous run left
    there is removed first. The work root never lies inside the
    repository."""
    if not _scenarios.ID_RE.fullmatch(scenario_id):
        raise HarnessError(f"{scenario_id!r} is not a scenario id, so it names no working directory")
    work_root = Path(getattr(adapter, "work_root")).resolve()
    repository = REPO_ROOT.resolve()
    if work_root == repository or repository in work_root.parents:
        raise HarnessError(
            f"the work root {work_root} lies inside the repository; working directories live outside it"
        )
    work_root.mkdir(parents=True, exist_ok=True)
    workdir = work_root / scenario_id
    if workdir.is_symlink() or workdir.is_file():
        workdir.unlink()
    elif workdir.exists():
        shutil.rmtree(workdir)
    workdir.mkdir()
    return workdir


def _execute(state: _Run) -> None:
    """Working directory, deadline, inputs, observation rule and the adapter
    calls, with the timeout precedence applied at the end."""
    s, adapter = state.scenario, state.adapter
    try:
        state.workdir = _fresh_workdir(adapter, s.id)
    except Exception as exc:
        state.error("its working directory cannot be created", exc)
        return
    workdir = state.workdir
    limit = min(DEADLINE_S, _HARNESS_DEADLINE_S)
    deadline = time.monotonic() + limit
    result_at: float | None = None
    raised: BaseException | None = None
    try:
        try:
            adapter.deadline = deadline
            state.materialized = _materialize(s, workdir)
            state.fingerprint = _results.execution_fingerprint(s, state.materialized, state.name)
            _check_observations(s, adapter, state.name)
            adapter.prepare(s, workdir)
            result = adapter.execute(s, workdir)
            if not isinstance(result, _base.Result):
                raise HarnessError(f"execute returned {type(result).__name__}, not an adapters.base.Result")
            state.result, result_at = result, time.monotonic()
        except _base.AdapterUnavailable as exc:
            raised = exc
            state.settle(BLOCKED, REASON_UNAVAILABLE, exc.gap_id, detail=exc.reason)
        except _base.MissingPrerequisite as exc:
            raised = exc
            state.settle(UNEXECUTED, REASON_MISSING, exc.gap_id, detail=exc.need)
        except _fixtures.FixtureError as exc:
            raised = exc
            state.error("its fixtures cannot be built", exc)
        except _TIMEOUT_ERRORS as exc:
            raised = exc
            state.runtime["traceback"] = _traceback(exc)
            state.fail(REASON_TIMEOUT, f"{s.id}: timeout: {type(exc).__name__}: {exc}")
        except Exception as exc:
            raised = exc
            state.error("the run raised", exc)
    finally:
        try:
            adapter.cleanup(s, workdir)
        except Exception as exc:
            state.runtime["cleanup_error"] = _traceback(exc)
        armed = getattr(adapter, "deadline", None)
        if isinstance(armed, (int, float)) and not isinstance(armed, bool):
            deadline = float(armed)
        try:
            adapter.deadline = None
        except AttributeError:
            pass
    if result_at is not None and result_at < deadline:
        return
    if time.monotonic() >= deadline:
        if raised is not None and "traceback" not in state.runtime:
            state.runtime["traceback"] = _traceback(raised)
        if raised is not None or state.result is not None:
            state.runtime["superseded"] = (
                f"{type(raised).__name__}: {raised}" if raised is not None else "a result returned after the deadline"
            )
        state.result = None
        state.fail(
            REASON_TIMEOUT,
            f"{s.id}: timeout: the scenario deadline of {limit} s passed",
            "what the run raised is in the runtime file" if raised is not None else "",
        )


def _materialize(scenario: Any, workdir: Path) -> dict[str, bytes]:
    """Build the scenario's datasets and write ``inputs/``: each dataset's
    bytes, ``datasets.yaml`` and ``parameters.yaml``."""
    data = _fixtures.build(scenario)
    inputs = workdir / INPUTS_DIR
    inputs.mkdir()
    listing: dict[str, Any] = {}
    for logical, payload in data.items():
        definition = _fixtures.spec(logical)
        relative = f"{INPUTS_DIR}/{logical}.dat"
        (workdir / relative).write_bytes(payload)
        key = definition.key
        listing[logical] = {
            "path": relative,
            "sha256": sha256_hex(payload),
            "record_length": definition.record_length,
            "organization": definition.organization,
            "key": None if key is None else {"offset": key.offset, "length": key.length},
            "alternate_keys": [
                {
                    "offset": alternate.offset,
                    "length": alternate.length,
                    "duplicates": alternate.duplicates,
                    "name": alternate.name,
                    "path": alternate.path,
                }
                for alternate in definition.alternate_keys
            ],
            "layout": definition.layout,
        }
    write_text(inputs / DATASETS_FILE, dump_yaml(listing))
    write_text(inputs / PARAMETERS_FILE, dump_yaml(_parameters(scenario, data)))
    return data


def _parameters(scenario: Any, data: Mapping[str, bytes]) -> dict[str, Any]:
    preconditions = scenario.preconditions
    parameters = preconditions.get("parameters")
    parameters = parameters if isinstance(parameters, Mapping) else {}
    faults = preconditions.get("faults")
    names: set[str] = set()
    param_files = scenario.inputs.get("param_files")
    if isinstance(param_files, Mapping):
        names.update(str(name) for name in param_files)
    if _fixtures.DATEPARM in parameters:
        names.add(_fixtures.DATEPARM)
    clock = _scenarios.clock(scenario)
    return {
        "clock": clock,
        "cob_current_date": cob_current_date(clock),
        "clock_mode": _scenarios.clock_mode(scenario),
        "parm": parameters.get("parm"),
        "sessions": parameters.get("sessions", 1),
        "ceedays_feedback": parameters.get("ceedays_feedback"),
        "faults": list(faults) if isinstance(faults, list) else [],
        "param_files": {name: f"{INPUTS_DIR}/{name}.dat" for name in sorted(names) if name in data},
    }


def _check_observations(scenario: Any, adapter: Any, name: str) -> None:
    """The observation rule: raise ``AdapterUnavailable`` under the
    adapter's gap for the first observation ``expected`` names that the
    adapter cannot produce."""
    for observation in sorted(_base.required_observations(scenario.expected)):
        can_observe = getattr(adapter, "can_observe", None)
        if callable(can_observe):
            available = bool(can_observe(observation))
        else:
            available = _base.observation_matches(observation, adapter.observations())
        if not available:
            raise _base.AdapterUnavailable(
                f"observation {observation} not available on {name}", _observation_gap(adapter, observation)
            )


def _observation_gap(adapter: Any, observation: str) -> str:
    hook = getattr(adapter, "observation_gap", None)
    gap = hook(observation) if callable(hook) else None
    if isinstance(gap, str) and _base.GAP_ID_RE.fullmatch(gap):
        return gap
    return _DEFAULT_OBSERVATION_GAPS.get(observation, _DEFAULT_DATASET_GAP)


# --------------------------------------------------------------------------
# Comparison
# --------------------------------------------------------------------------


def _judge(state: _Run) -> None:
    """Decode the result and, unless capturing, compare it with the
    baseline and the scenario's inline expectations."""
    s, result = state.scenario, state.result
    try:
        state.decoded = _baselines.decode_result(s, result)
        state.projection = _baselines.projection(state.decoded, s, state.baseline)
    except Exception as exc:
        state.error("its result cannot be decoded", exc)
        return
    if not state.compare:
        state.settle(PASSED, None)
        return
    try:
        status, reason, diff = _baselines.compare(s, state.baseline, result, state.name, _run_date(state))
    except Exception as exc:
        state.error("the comparison raised", exc)
        return
    state.settle(status, reason, None, diff)


def _run_date(state: _Run) -> _dt.date | None:
    """The run date matchers are judged against: the adapter's
    ``run_date(scenario)`` when it reports one, else the scenario clock's
    date on an adapter whose clock is that clock. Any other adapter without a
    reported date gets ``None``, so a relative matcher fails as "run date
    unknown" rather than being judged against a clock the adapter never
    set."""
    hook = getattr(state.adapter, "run_date", None)
    value = hook(state.scenario) if callable(hook) else None
    if isinstance(value, _dt.datetime):
        return value.date()
    if isinstance(value, _dt.date):
        return value
    if value is not None:
        raise HarnessError(f"run_date returned {type(value).__name__}, not a date")
    if state.name in _baselines.FIXED_CLOCK_ADAPTERS:
        return _dt.date.fromisoformat(_scenarios.clock(state.scenario)[:10])
    return None


# --------------------------------------------------------------------------
# Record, evidence, disposition
# --------------------------------------------------------------------------


def _finish(state: _Run) -> Outcome:
    _identify(state)
    record = _record(state)
    record = _evidence(state, record)
    kept = _dispose(state)
    _write_runtime(state, kept)
    _write_environment_once(state)
    detail = "; ".join(part for part in [state.detail, *state.notes] if part)
    return Outcome(
        scenario_id=state.scenario_id,
        adapter=state.name,
        status=state.status,
        reason=state.reason,
        gap_id=state.gap_id,
        diff=state.diff,
        record=record,
        decoded=state.decoded,
        result=state.result,
        detail=detail,
        materialized=state.materialized,
        workdir=kept,
    )


def _identify(state: _Run) -> None:
    """The execution id and, for a scenario that reached the adapter, the
    source identity. A ``passed`` run needs both."""
    s, adapter = state.scenario, state.adapter
    version = state.baseline.version if state.baseline is not None else None
    try:
        if state.name == _results.UNIKIX:
            supplied = getattr(adapter, "execution_id", None)
            if supplied is not None:
                state.execution_id = _results.execution_id(state.name, s, supplied=supplied)
        else:
            build_key = None
            hook = getattr(adapter, "build_key", None)
            if callable(hook):
                try:
                    build_key = hook(s)
                except Exception as exc:
                    state.note("build_key_error", f"build key unavailable: {type(exc).__name__}: {exc}")
            commit = git_head()
            state.execution_id = _results.execution_id(
                state.name, s, version, build_key, None if commit == "unknown" else commit
            )
    except Exception as exc:
        state.note("execution_id_error", f"execution id unavailable: {type(exc).__name__}: {exc}")
    if state.workdir is None:
        # Nothing reached the adapter: no sources ran, so there is no identity.
        return
    # The adapter's own source_identity is asked first: gnucobol-batch's is
    # results.source_identity of app/, while unikix's names the UniKix copy it
    # deploys, which results.source_identity cannot give (it returns None).
    try:
        hook = getattr(adapter, "source_identity", None)
        state.identity = hook(s) if callable(hook) else _results.source_identity(s, state.name)
    except Exception as exc:
        state.note("source_identity_error", f"source identity unavailable: {type(exc).__name__}: {exc}")


def _build(state: _Run, decoded: Any, identity: Any) -> dict:
    return _results.build_record(
        state.scenario,
        state.name,
        state.status,
        reason=state.reason,
        gap_id=state.gap_id,
        baseline_version=state.baseline.version if state.baseline is not None else None,
        decoded=decoded,
        execution_fingerprint=state.fingerprint,
        source_identity=identity,
        execution_id=state.execution_id,
    )


def _record(state: _Run) -> dict | None:
    """The result record. A record the contract refuses (a passed run
    without its execution evidence, a decoded outcome holding an absolute
    path) turns the run into ``failing``/``error`` with a record of that,
    without the refused parts."""
    try:
        return _build(state, state.projection, state.identity)
    except Exception as exc:
        state.runtime["record_error"] = _traceback(exc)
        state.fail(REASON_ERROR, f"{state.scenario_id}: the result record is refused: {exc}", state.detail)
    try:
        return _build(state, None, None)
    except Exception as exc:
        state.note("record_unbuilt", f"no result record could be built: {type(exc).__name__}: {exc}")
        return None


def _evidence(state: _Run, record: dict | None) -> dict | None:
    """Hand the record to the adapter's evidence writer, when it has one. A
    writer that fails turns the run into ``failing``/``error``."""
    writer = getattr(state.adapter, "write_evidence", None)
    if record is None or not callable(writer):
        return record
    try:
        path = writer(state.scenario, record)
    except Exception as exc:
        state.runtime["evidence_error"] = _traceback(exc)
        state.fail(REASON_ERROR, f"{state.scenario_id}: its evidence cannot be written: {exc}", state.detail)
        try:
            return _build(state, None, None)
        except Exception as rebuild:
            state.note("record_unbuilt", f"no result record could be built: {type(rebuild).__name__}: {rebuild}")
            return None
    if path is not None:
        state.runtime["evidence"] = str(path)
    return record


def _dispose(state: _Run) -> Path | None:
    """Keep the working directory of a ``failing`` run (CI uploads it);
    remove every other. Returns the kept directory."""
    workdir = state.workdir
    if workdir is None:
        return None
    if state.status == FAILING:
        return workdir
    try:
        shutil.rmtree(workdir)
    except FileNotFoundError:
        pass
    except OSError as exc:
        state.note("workdir_error", f"working directory {workdir} not removed: {exc}")
    return None


def _write_runtime(state: _Run, kept: Path | None) -> None:
    """``tests/reports/runtime/<id>.yaml``: the run's elapsed time, kept
    working directory and error details, which stay out of the record."""
    data: dict[str, Any] = {
        "scenario_id": state.scenario_id,
        "adapter": state.name,
        "status": state.status,
        "reason": state.reason,
        "gap_id": state.gap_id,
        "elapsed_s": round(time.monotonic() - state.started, 3),
        "detail": state.detail or None,
        "workdir": str(kept) if kept is not None else None,
        "elapsed_execute_s": state.result.elapsed_s if state.result is not None else None,
    }
    data.update(state.runtime)
    data = {key: value for key, value in data.items() if value is not None}
    try:
        path = _results.write_runtime(state.scenario_id, data)
    except Exception as exc:
        message = f"runtime file not written: {type(exc).__name__}: {exc}"
        state.notes.append(message)
        warnings.warn(f"{state.scenario_id}: {message}", RuntimeWarning, stacklevel=3)
        return
    if state.status == FAILING:
        state.notes.append(f"details in {display_path(path)}")


def _write_environment_once(state: _Run) -> None:
    """Write the adapter's ``environment()`` after its first run of the
    session."""
    adapter = state.adapter
    hook = getattr(adapter, "environment", None)
    if not callable(hook):
        return
    with _ENVIRONMENT_LOCK:
        try:
            if adapter in _ENVIRONMENT_WRITTEN:
                return
            _ENVIRONMENT_WRITTEN.add(adapter)
        except TypeError:
            # An adapter that cannot be weakly referenced is written every run.
            pass
        try:
            environment = hook()
            if environment is not None:
                _results.write_environment(environment)
        except Exception as exc:
            message = f"environment not written: {type(exc).__name__}: {exc}"
            state.notes.append(message)
            warnings.warn(f"{state.name}: {message}", RuntimeWarning, stacklevel=3)
