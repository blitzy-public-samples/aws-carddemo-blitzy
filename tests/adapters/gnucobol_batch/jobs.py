"""Job driver and program launcher of the ``gnucobol-batch`` adapter.

Program runs (``run_program``)
    One batch unit runs as a subprocess of its own process group, with the
    scenario working directory as ``cwd`` and the fresh environment of
    ``datasets.runtime_env``: ``DD_<ddname>`` for every ddname (an absent file
    keeps its path, so the program's OPEN fails with status 35),
    ``COB_LIBRARY_PATH`` on the build cache's modules and stubs,
    ``COB_CURRENT_DATE`` from the scenario clock, ``CARDDEMO_CEEDAYS_FEEDBACK``
    when a scenario injects a token (GAP-STB-002), and, for the unit
    ``programs.yaml`` gives a ``tiot`` (CBSTM03A) and no other,
    ``LD_PRELOAD`` of the page-zero shim with ``CARDDEMO_JOB``,
    ``CARDDEMO_STEP`` and ``CARDDEMO_DDS`` (GAP-STB-003); ``LD_PRELOAD``
    names the shim relative to the working directory, because ld.so splits
    it at every space and colon, which the work root's path may hold. The
    unit is launched as ``programs.yaml`` ``run`` says: its executable,
    ``PARMDRV <unit> <parm>`` (GAP-STB-004) or ``DTCDRV``. The deadline kills
    the group (``datasets.ProcessTimeout``, a ``ScenarioTimeout``).

    ``return_code`` is the exit status, or ``None`` when the program abended:
    the ``CEE3ABD`` stub wrote ``CARDDEMO-ABEND CEE3ABD`` on stderr
    (GAP-STB-001), a signal ended the process, or libcob ended it, with a
    nonzero exit status, on the fatal error (``libcob: error: ...``, or
    ``libcob: <source>:<line>: error: ...`` under -debug) it reports for an
    I/O condition on a file that declares no FILE STATUS (``(status = nn)
    for file ...``), which Language Environment ends with an abend.
    ``messages`` are the program's ``DISPLAY`` lines in order, followed by
    the marker after a ``CEE3ABD`` abend; lines beginning ``libcob:`` stay in
    the raw evidence, where ``command.yaml`` names the fatal one as
    ``libcob_error``. libcob warnings never end a run. A driver or stub that
    reports its own failure (``CARDDEMO-PARMDRV``, ``CARDDEMO-DTCDRV``,
    ``CARDDEMO-CEEDAYS``, ``CARDDEMO-SHIM``), a shim ld.so did not preload,
    and every other fatal libcob error, such as a module or entry point not
    found, are harness problems, never program behaviour: ``HarnessError``,
    or ``AdapterUnavailable`` under GAP-MIS-002 when the shim cannot map page
    zero. They are raised once the raw evidence is written, so neither a
    later step nor a normal-end disposition follows.

Job runs (``run_jobs``)
    Each job of ``inputs.job`` runs from its ``jobs.yaml`` step table, in JCL
    order, against the scenario's one catalog; the driver never parses JCL.
    Relative generations are fixed at each job's start
    (``datasets.JobGenerations``, GAP-STB-009). Program steps resolve their
    DDs through the catalog, run through ``run_program`` and, at normal end,
    get their normal-end dispositions (``utilities.apply_normal_dispositions``,
    GAP-STB-007); utility steps run through ``utilities.run_utility``, which
    applies them itself. ``COND`` and the abnormal-termination disposition
    are recorded and never evaluated: they are JCL-semantics items run only
    on ``unikix``. The first abend stops the scenario, later steps and later
    jobs included, and no disposition is applied to the abended step.

    ``messages`` hold, per step, ``STEP <name> RC <nnnn>`` or ``STEP <name>
    ABEND``, then that step's ``DISPLAY`` lines (program steps only; the
    ``ABEND`` line stands for the ``CEE3ABD`` marker). ``return_code`` is the
    highest step return code, ``None`` once a step abended.

Evidence, all under the scenario working directory
    ``evidence/stdout.txt``, ``stderr.txt`` and ``command.yaml`` of a program
    run; ``evidence/steps/<nn>-<job>-<step>/`` of each job step, with
    ``step.yaml`` and, for a program step, its program evidence; the
    utilities' ``evidence/<nn>-<step>.sysprint.txt``; ``evidence/jobs.yaml``
    and ``evidence/catalog.yaml`` after a job run. In-stream data of a program
    step goes to ``data/instream/<nn>-<step>-<dd>.dat``. Nothing carries a
    time, a host or a path outside the working directory: ``command.yaml``
    shows working-directory paths relative and build-cache paths under
    ``<build>``.

Importing this module reads no file and starts no process.
"""

from __future__ import annotations

import functools
import os
import re
import time
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from adapters.base import AdapterUnavailable, ScenarioTimeout
from adapters.gnucobol_batch.build import BuildCache, KeyProfile, profile_for
from adapters.gnucobol_batch.datasets import (
    Catalog,
    DatasetStore,
    JobGenerations,
    ProcessResult,
    ProcessTimeout,
    program_dds,
    run_process,
    runtime_env,
    scenario_jobs,
)
from adapters.gnucobol_batch.utilities import (
    INSTREAM_RECORD_LENGTH,
    UTILITY_KINDS,
    ResolvedDD,
    StepContext,
    apply_normal_dispositions,
    run_utility,
)
from harness import (
    DEFAULT_CLOCK,
    REPO_ROOT,
    HarnessError,
    cob_current_date,
    display_path,
    dump_yaml,
    load_yaml,
    write_text,
)

__all__ = [
    "ABEND_MARKER",
    "DATEPARM",
    "DATEPARM_RECORD_LENGTH",
    "JOBS_FILE",
    "LIBCOB_PREFIX",
    "PROGRAMS_FILE",
    "RUN_MODES",
    "SCHEMA_VERSION",
    "JobOutcome",
    "JobResult",
    "ProgramOutcome",
    "StepResult",
    "called_configs",
    "cob_current_date",
    "dateparm_record",
    "job_steps",
    "load_jobs",
    "load_programs",
    "run_jobs",
    "run_program",
    "unit_config",
]

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

SCHEMA_VERSION = 1
PROGRAMS_FILE = Path(__file__).resolve().with_name("programs.yaml")
JOBS_FILE = Path(__file__).resolve().with_name("jobs.yaml")
RUN_MODES = ("executable", "parmdrv", "dtcdrv", "called")

ABEND_MARKER = "CARDDEMO-ABEND CEE3ABD"  # tests/stubs/CEE3ABD.cbl, on stderr
LIBCOB_PREFIX = "libcob:"
# Lines a driver or stub writes on stderr about its own failure.
_HARNESS_MARKERS = ("CARDDEMO-PARMDRV", "CARDDEMO-DTCDRV", "CARDDEMO-CEEDAYS", "CARDDEMO-SHIM")
_SHIM_MMAP_RE = re.compile(r"CARDDEMO-SHIM page0 mmap failed errno=([0-9]+)")
# ld.so's report of an LD_PRELOAD object it could not load, on stderr.
_PRELOAD_FAILED_RE = re.compile(
    r"ERROR: ld\.so: object '.*' from LD_PRELOAD cannot be preloaded \(.*\): ignored\."
)
# A fatal libcob runtime error on stderr, with the source location libcob adds
# under -debug; libcob then ends the run with exit status 1. Warnings never end it.
_LIBCOB_ERROR_RE = re.compile(r"libcob: (?:\S+:[0-9]+: )?error: .*")
# The fatal error libcob reports for an I/O condition on a file that declares no
# FILE STATUS, which Language Environment ends with an abend.
_LIBCOB_FILE_STATUS_RE = re.compile(r"\(status = [0-9]{2}\) for file ")

DATEPARM = "DATEPARM"
DATEPARM_RECORD_LENGTH = 80
# CBTRN03C's DATEPARM record: start date, one blank, end date [app/cbl/CBTRN03C.cbl:122-125].
_DATEPARM_RE = re.compile(r"([0-9]{4}-[0-9]{2}-[0-9]{2}) ([0-9]{4}-[0-9]{2}-[0-9]{2})")
_PLACEHOLDER_RE = re.compile(r"\{\{([^{}]*)\}\}")
_DATEPARM_PLACEHOLDERS = ("DATEPARM_START", "DATEPARM_END")

_DDNAME_RE = re.compile(r"[A-Z#@$][A-Z0-9#@$]{0,7}")
_FEEDBACK_RE = re.compile(r"[0-9A-Fa-f]{4}")
_FILE_NAME_RE = re.compile(r"[^A-Za-z0-9.#@$-]")
_IGNORED_DDS = frozenset({"STEPLIB"})  # the load library: the build cache stands for it

EVIDENCE_SUBDIR = "evidence"
INPUTS_SUBDIR = "inputs"
_STEPS_SUBDIR = "steps"
_INSTREAM_SUBDIR = "instream"
_BUILD_LABEL = "<build>"


# --------------------------------------------------------------------------
# Configuration
# --------------------------------------------------------------------------


def _load_config(path: Path, section: str) -> Mapping[str, Any]:
    document = load_yaml(path)
    shown = display_path(path)
    if not isinstance(document, Mapping):
        raise HarnessError(f"{shown} must hold a mapping")
    version = document.get("schema_version")
    if isinstance(version, bool) or version != SCHEMA_VERSION:
        raise HarnessError(
            f"{shown} has schema_version {version!r}; this driver reads {SCHEMA_VERSION}"
        )
    table = document.get(section)
    if not isinstance(table, Mapping) or not table:
        raise HarnessError(f"{shown} has no {section} mapping")
    return document


@functools.lru_cache(maxsize=None)
def load_programs() -> Mapping[str, Any]:
    """The loaded ``programs.yaml`` beside this module, read once per
    process. Callers must not change it."""
    return _load_config(PROGRAMS_FILE, "programs")


@functools.lru_cache(maxsize=None)
def load_jobs() -> Mapping[str, Any]:
    """The loaded ``jobs.yaml`` beside this module, read once per process.
    Callers must not change it."""
    return _load_config(JOBS_FILE, "jobs")


def unit_config(unit: str) -> Mapping[str, Any]:
    """The ``programs.yaml`` entry of ``unit``."""
    programs = load_programs()["programs"]
    config = programs.get(unit) if isinstance(unit, str) else None
    if not isinstance(config, Mapping):
        raise HarnessError(f"{unit!r} is not a unit of {display_path(PROGRAMS_FILE)}")
    return config


def called_configs(unit: str) -> list[Mapping[str, Any]]:
    """The ``programs.yaml`` entries of the units ``unit`` calls, whose dds
    reach its run (CBSTM03B's reach CBSTM03A's)."""
    calls = unit_config(unit).get("calls") or []
    if not isinstance(calls, list) or not all(isinstance(name, str) for name in calls):
        raise HarnessError(
            f"{display_path(PROGRAMS_FILE)}: {unit} calls must be a list of unit names"
        )
    return [unit_config(name) for name in calls]


def job_steps(job: str) -> list[Mapping[str, Any]]:
    """The ``jobs.yaml`` step table of ``job``, in JCL order."""
    jobs = load_jobs()["jobs"]
    entry = jobs.get(job) if isinstance(job, str) else None
    steps = entry.get("steps") if isinstance(entry, Mapping) else None
    if (
        not isinstance(steps, list)
        or not steps
        or not all(isinstance(step, Mapping) for step in steps)
    ):
        raise HarnessError(f"{display_path(JOBS_FILE)} has no step table for job {job!r}")
    for step in steps:
        name = step.get("step")
        if not isinstance(name, str) or not name:
            raise HarnessError(f"{display_path(JOBS_FILE)}: job {job} has a step without a name")
    return steps


# --------------------------------------------------------------------------
# Scenario parameters
# --------------------------------------------------------------------------


def _scenario_section(scenario: Any, name: str) -> Mapping[str, Any]:
    value = getattr(scenario, name, None)
    if value is None and isinstance(scenario, Mapping):
        value = scenario.get(name)
    return value if isinstance(value, Mapping) else {}


def _scenario_id(scenario: Any) -> str:
    value = getattr(scenario, "id", None)
    if value is None and isinstance(scenario, Mapping):
        value = scenario.get("id")
    return value if isinstance(value, str) and value else "the scenario"


def dateparm_record(
    scenario: Any, inputs_dir: str | os.PathLike[str] | None
) -> bytes | None:
    """The DATEPARM record of a scenario: the bytes of
    ``<inputs_dir>/DATEPARM.dat`` when the runner wrote it, else
    ``preconditions.parameters.DATEPARM`` (``YYYY-MM-DD YYYY-MM-DD``) padded
    with spaces to 80 bytes, else ``None``."""
    if inputs_dir is not None:
        source = Path(inputs_dir) / f"{DATEPARM}.dat"
        if source.is_file():
            return source.read_bytes()
    parameters = _scenario_section(scenario, "preconditions").get("parameters")
    text = parameters.get(DATEPARM) if isinstance(parameters, Mapping) else None
    if text is None:
        return None
    where = f"{_scenario_id(scenario)}: preconditions.parameters.{DATEPARM}"
    if not isinstance(text, str):
        raise HarnessError(f"{where} must be the record text, quoted, got {text!r}")
    try:
        data = text.encode("latin-1")
    except UnicodeEncodeError:
        raise HarnessError(f"{where} holds a character outside latin-1") from None
    if len(data) > DATEPARM_RECORD_LENGTH:
        raise HarnessError(f"{where} is longer than the {DATEPARM_RECORD_LENGTH}-byte record")
    return data.ljust(DATEPARM_RECORD_LENGTH, b" ")


def _dateparm_values(scenario: Any, inputs_dir: Path, job: str) -> dict[str, str]:
    """``{{DATEPARM_START}}`` and ``{{DATEPARM_END}}`` from the scenario's
    DATEPARM, the dates CORPT00C moves into the job it submits."""
    record = dateparm_record(scenario, inputs_dir)
    if record is None:
        raise HarnessError(
            f"{_scenario_id(scenario)}: job {job} takes its report dates from the scenario's "
            f"{DATEPARM} (inputs/{DATEPARM}.dat or preconditions.parameters.{DATEPARM}), and "
            f"none is given"
        )
    text = record[:DATEPARM_RECORD_LENGTH].decode("latin-1")
    match = _DATEPARM_RE.match(text)
    if match is None:
        raise HarnessError(
            f"{_scenario_id(scenario)}: the {DATEPARM} record {text.rstrip()!r} is not "
            f"'YYYY-MM-DD YYYY-MM-DD'"
        )
    return dict(zip(_DATEPARM_PLACEHOLDERS, match.groups()))


def _placeholders(value: Any) -> set[str]:
    if isinstance(value, str):
        return set(_PLACEHOLDER_RE.findall(value))
    if isinstance(value, Mapping):
        return set().union(*(_placeholders(item) for item in value.values()))
    if isinstance(value, list):
        return set().union(*(_placeholders(item) for item in value))
    return set()


def _substituted(value: Any, values: Mapping[str, str]) -> Any:
    """A copy of ``value`` with every ``{{NAME}}`` replaced by ``values[NAME]``."""
    if isinstance(value, str):
        return _PLACEHOLDER_RE.sub(lambda match: values[match.group(1)], value)
    if isinstance(value, Mapping):
        return {key: _substituted(item, values) for key, item in value.items()}
    if isinstance(value, list):
        return [_substituted(item, values) for item in value]
    return value


# --------------------------------------------------------------------------
# Paths
# --------------------------------------------------------------------------


def _checked_workdir(workdir: str | os.PathLike[str]) -> Path:
    """The scenario working directory, resolved: an existing directory that
    neither is, lies inside nor contains the repository."""
    root = Path(workdir).resolve()
    repository = REPO_ROOT.resolve()
    if root == repository or repository in root.parents or root in repository.parents:
        raise HarnessError(
            f"the scenario working directory {root} is, lies inside or contains the repository "
            f"{repository}; nothing may be written in the repository"
        )
    if not root.is_dir():
        raise HarnessError(f"the scenario working directory {root} does not exist")
    return root


def _inside(root: Path, path: str | os.PathLike[str], what: str) -> Path:
    """``path`` made absolute (a relative one is taken from ``root``) with its
    links resolved, refused unless it lies inside ``root``."""
    candidate = Path(path)
    if not candidate.is_absolute():
        candidate = root / candidate
    resolved = Path(os.path.realpath(candidate))
    if resolved != root and root not in resolved.parents:
        raise HarnessError(f"{what} {path} lies outside the scenario working directory {root}")
    return resolved


def _file_name(text: str) -> str:
    return _FILE_NAME_RE.sub("_", text)


def _shown(value: str, prefixes: Sequence[tuple[str, str]]) -> str:
    """``value`` with each directory prefix replaced by its label: working
    directory paths relative, build-cache paths under ``<build>``."""
    for prefix, label in prefixes:
        value = value.replace(prefix + os.sep, f"{label}/" if label else "")
        if value == prefix:
            value = label or "."
    return value


# --------------------------------------------------------------------------
# Program runs
# --------------------------------------------------------------------------


@dataclass
class ProgramOutcome:
    """One finished program run.

    ``return_code`` is the exit status, ``None`` when the program abended;
    ``abended`` is true after the ``CEE3ABD`` marker, a signal, or the fatal
    libcob error of an I/O condition on a file without FILE STATUS, and
    ``signal`` names the signal that ended the process, if any. ``messages``
    are the ``DISPLAY`` lines followed by the ``CEE3ABD`` marker when it was
    written; ``displays`` the ``DISPLAY`` lines alone. ``stdout`` and
    ``stderr`` are the raw bytes as captured; ``evidence`` is the evidence
    directory, relative to the working directory."""

    return_code: int | None
    abended: bool
    signal: int | None
    messages: list[str]
    stdout: bytes
    stderr: bytes
    elapsed_s: float
    displays: list[str] = field(default_factory=list)
    evidence: str = EVIDENCE_SUBDIR


def _lines(data: bytes) -> list[str]:
    """Output lines, decoded byte for byte (latin-1 keeps the ``x'00'`` fill
    of the CBACT04C descriptions), trailing spaces kept; a final newline ends
    the last line rather than starting an empty one."""
    lines = data.decode("latin-1").split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    return lines


def _argv(cache: BuildCache, unit: str, config: Mapping[str, Any], parm: Any) -> list[str]:
    run = config.get("run")
    if run == "executable":
        return [os.fspath(cache.executable(unit))]
    if run == "parmdrv":
        if parm is None:
            raise HarnessError(
                f"{unit} runs through PARMDRV and needs its PARM text "
                f"(preconditions.parameters.parm, or the parm of its job step)"
            )
        if not isinstance(parm, str):
            raise HarnessError(f"the PARM of {unit} must be text, got {parm!r}; quote it in YAML")
        if "\x00" in parm:
            raise HarnessError(
                f"the PARM of {unit} holds a NUL byte, which no command line can pass"
            )
        return [os.fspath(cache.parmdrv), unit, parm]
    if run == "dtcdrv":
        return [os.fspath(cache.dtcdrv)]
    if run == "called":
        raise HarnessError(
            f"{unit} is never launched: it runs only inside {config.get('called_by')}"
        )
    raise HarnessError(
        f"{display_path(PROGRAMS_FILE)}: {unit} has run {run!r}, not one of {', '.join(RUN_MODES)}"
    )


def _dd_environment(
    unit: str,
    config: Mapping[str, Any],
    dd_paths: Mapping[str, str | os.PathLike[str]],
    root: Path,
) -> dict[str, str]:
    """``DD_<ddname>`` for every path given. Each ddname the unit and the
    units it calls declare must have one: without ``DD_<ddname>`` libcob
    would open a file named after the ddname in the working directory."""
    if not isinstance(dd_paths, Mapping):
        raise HarnessError(
            f"the dd paths of {unit} must be a mapping, got {type(dd_paths).__name__}"
        )
    environment: dict[str, str] = {}
    for ddname in sorted(dd_paths):
        if not isinstance(ddname, str) or not _DDNAME_RE.fullmatch(ddname):
            raise HarnessError(f"{ddname!r} is not a ddname")
        path = dd_paths[ddname]
        if path is None:
            raise HarnessError(f"DD {ddname} of {unit} has no path")
        if os.fspath(path) == os.devnull:  # DD DUMMY
            environment[f"DD_{ddname}"] = os.devnull
            continue
        environment[f"DD_{ddname}"] = os.fspath(_inside(root, path, f"DD {ddname} of {unit}:"))
    declared = program_dds(config, called_configs(unit))
    missing = [ddname for ddname, _ in declared if ddname not in dd_paths]
    if missing:
        raise HarnessError(f"{unit}: no path for its DD {', '.join(missing)}")
    return environment


def _tiot_environment(
    cache: BuildCache,
    unit: str,
    config: Mapping[str, Any],
    tiot: Mapping[str, Any] | None,
    root: Path,
) -> dict[str, str]:
    """The shim's environment, for the unit ``programs.yaml`` gives a
    ``tiot`` (CBSTM03A): ``LD_PRELOAD`` applies to every process started with
    it, so no other unit gets it. ``LD_PRELOAD`` names the shim relative to
    ``root``, the run's ``cwd``, because ld.so splits it at every space and
    colon, which the work root's path may hold; a relative path that still
    holds one is refused."""
    declared = config.get("tiot")
    if declared is None:
        if tiot is not None:
            raise HarnessError(
                f"{unit} has no tiot in {display_path(PROGRAMS_FILE)}; the page-zero shim is "
                f"preloaded only into the unit that walks the TIOT"
            )
        return {}
    chosen = tiot if tiot is not None else declared
    if not isinstance(chosen, Mapping):
        raise HarnessError(f"the tiot of {unit} must be a mapping of job, step and dds")
    job, step, dds = chosen.get("job"), chosen.get("step"), chosen.get("dds")
    for what, value in (("job", job), ("step", step)):
        if not isinstance(value, str) or not value or "," in value:
            raise HarnessError(f"the tiot {what} of {unit} must be a name, got {value!r}")
    if (
        not isinstance(dds, list)
        or not dds
        or not all(isinstance(name, str) and name and "," not in name for name in dds)
    ):
        raise HarnessError(
            f"the tiot dds of {unit} must be a non-empty list of ddnames, got {dds!r}"
        )
    shim = os.path.relpath(os.path.realpath(cache.shim), root)
    if os.sep not in shim:
        shim = os.path.join(os.curdir, shim)  # without a slash ld.so would search for the name
    if any(character.isspace() or character == ":" for character in shim):
        raise HarnessError(
            f"{unit}: the page-zero shim's path from the working directory, {shim!r}, holds a "
            f"space or a colon, at which ld.so splits LD_PRELOAD; keep the build cache under "
            f"the work root or on a path without them"
        )
    return {
        "LD_PRELOAD": shim,
        "CARDDEMO_JOB": job,
        "CARDDEMO_STEP": step,
        "CARDDEMO_DDS": ",".join(dds),
    }


def _write_program_evidence(
    evidence: Path,
    prefixes: Sequence[tuple[str, str]],
    argv: Sequence[str],
    env: Mapping[str, str],
    stdout: bytes,
    stderr: bytes,
    ending: Mapping[str, Any],
) -> None:
    evidence.mkdir(parents=True, exist_ok=True)
    (evidence / "stdout.txt").write_bytes(stdout)
    (evidence / "stderr.txt").write_bytes(stderr)
    command = {
        "argv": [_shown(arg, prefixes) for arg in argv],
        "cwd": ".",
        "env": {key: _shown(value, prefixes) for key, value in env.items()},
        "ending": dict(ending),
    }
    write_text(evidence / "command.yaml", dump_yaml(command))


def _libcob_error(stderr_lines: Iterable[str], process: ProcessResult, marker: bool) -> str | None:
    """The first fatal libcob error line of a run libcob ended: one with a
    nonzero exit status, no signal and no ``CEE3ABD`` marker. ``None`` for
    any other run, whatever libcob warned."""
    if marker or process.signal is not None or not process.returncode:
        return None
    for line in stderr_lines:
        if _LIBCOB_ERROR_RE.fullmatch(line.rstrip()):
            return line.rstrip()
    return None


def _harness_failure(
    unit: str,
    stderr_lines: Iterable[str],
    process: ProcessResult,
    shown: str,
    libcob_error: str | None,
) -> None:
    """Raise for a driver or stub reporting its own failure on stderr, for a
    page-zero shim ld.so did not preload, and for a fatal libcob error that
    is no program behaviour: every one but an I/O condition on a file
    without FILE STATUS, such as a module or entry point not found."""
    status = (
        f"signal {process.signal}" if process.signal is not None
        else f"exit status {process.returncode}"
    )
    for line in stderr_lines:
        mmap = _SHIM_MMAP_RE.match(line)
        if mmap is not None:
            raise AdapterUnavailable(
                f"page zero not mappable: errno={mmap.group(1)}; the page-zero shim could not map "
                f"it in the {unit} run (evidence {shown})",
                "GAP-MIS-002",
            )
        if _PRELOAD_FAILED_RE.match(line):
            raise HarnessError(
                f"{unit}: the page-zero shim was not preloaded: {line.rstrip()} ({status}; "
                f"evidence {shown})"
            )
        if line.startswith(_HARNESS_MARKERS):
            raise HarnessError(f"{unit}: {line.rstrip()} ({status}; evidence {shown})")
    if libcob_error is not None and _LIBCOB_FILE_STATUS_RE.search(libcob_error) is None:
        raise HarnessError(
            f"{unit}: libcob ended the run: {libcob_error} ({status}; evidence {shown}); a "
            f"module or entry point the build cache supplies is missing, or the runtime failed "
            f"in a way that is no program behaviour"
        )


def run_program(
    cache: BuildCache,
    unit: str,
    dd_paths: Mapping[str, str | os.PathLike[str]],
    *,
    workdir: str | os.PathLike[str],
    deadline: float | None,
    clock: str = DEFAULT_CLOCK,
    parm: str | None = None,
    ceedays_feedback: str | None = None,
    tiot: Mapping[str, Any] | None = None,
    evidence_dir: str | os.PathLike[str] | None = None,
) -> ProgramOutcome:
    """Run one batch unit of ``programs.yaml`` and return its outcome.

    ``dd_paths`` maps each ddname to its file inside ``workdir`` (or to
    ``os.devnull`` for ``DD DUMMY``) and must cover every dd the unit and
    the units it calls declare; an absent file keeps its path. ``deadline`` is
    a ``time.monotonic()`` value. ``clock`` is ``YYYY-MM-DD HH:MM:SS.hh``,
    written to ``COB_CURRENT_DATE`` as the build cache's clock probe found
    libcob reads it. ``parm`` is the PARM text of a ``parmdrv`` unit and is
    ignored by the others, as z/OS passes a PARM a program never reads.
    ``ceedays_feedback`` is the 4-hex-digit token the ``CEEDAYS`` stub
    returns instead of computing one. ``tiot`` (``{job, step, dds}``)
    overrides the ``programs.yaml`` ``tiot`` of the unit that has one.
    ``evidence_dir`` (default ``<workdir>/evidence``) receives
    ``stdout.txt``, ``stderr.txt`` and ``command.yaml``, also when the
    deadline kills the run.

    Raises ``ProcessTimeout`` (a ``ScenarioTimeout``) at the deadline,
    ``AdapterUnavailable`` under GAP-MIS-002 when the shim cannot map page
    zero, and ``HarnessError`` for a configuration the run cannot have, a
    driver or stub that reports its own failure, a shim ld.so did not
    preload, or a fatal libcob error other than the I/O condition of a file
    without FILE STATUS (a module or entry point not found, for one), each
    after the evidence is written."""
    config = unit_config(unit)
    root = _checked_workdir(workdir)
    evidence = _inside(
        root,
        evidence_dir if evidence_dir is not None else root / EVIDENCE_SUBDIR,
        "the evidence directory",
    )
    argv = _argv(cache, unit, config, parm)
    extra: dict[str, str] = _dd_environment(unit, config, dd_paths, root)
    extra["COB_CURRENT_DATE"] = cob_current_date(clock, cache.clock_fraction_mode)
    if ceedays_feedback is not None:
        if not isinstance(ceedays_feedback, str) or not _FEEDBACK_RE.fullmatch(ceedays_feedback):
            raise HarnessError(
                f"ceedays_feedback must be 4 hex digits, quoted, got {ceedays_feedback!r}"
            )
        extra["CARDDEMO_CEEDAYS_FEEDBACK"] = ceedays_feedback
    extra.update(_tiot_environment(cache, unit, config, tiot, root))
    env = runtime_env(cache, extra)
    build = os.fspath(Path(cache.dir).resolve())
    # The build cache from the working directory too, as LD_PRELOAD names it.
    prefixes = sorted(
        (
            (os.fspath(root), ""),
            (build, _BUILD_LABEL),
            (os.path.relpath(build, root), _BUILD_LABEL),
        ),
        key=lambda item: len(item[0]),
        reverse=True,
    )
    shown = evidence.relative_to(root).as_posix() or "."
    try:
        process = run_process(argv, env=env, cwd=root, deadline=deadline)
    except ProcessTimeout as timeout:
        try:
            _write_program_evidence(
                evidence, prefixes, argv, env, timeout.stdout, timeout.stderr,
                {
                    "timeout": str(timeout),
                    "returncode": timeout.returncode,
                    "signal": timeout.signal,
                },
            )
        except (OSError, HarnessError) as failure:
            timeout.add_note(
                f"the evidence of {unit} could not be written: "
                f"{type(failure).__name__}: {failure}"
            )
        raise
    stderr_lines = _lines(process.stderr)
    marker = any(line.rstrip() == ABEND_MARKER for line in stderr_lines)
    libcob_error = _libcob_error(stderr_lines, process, marker)
    if libcob_error is not None:
        libcob_error = _shown(libcob_error, prefixes)
    # A fatal libcob error that _harness_failure lets through is the I/O
    # condition of a file without FILE STATUS: an abend, as on z/OS.
    abended = (
        marker
        or process.signal is not None
        or process.returncode is None
        or libcob_error is not None
    )
    _write_program_evidence(
        evidence, prefixes, argv, env, process.stdout, process.stderr,
        {
            "returncode": process.returncode,
            "signal": process.signal,
            "cee3abd_marker": marker,
            "libcob_error": libcob_error,
        },
    )
    _harness_failure(unit, stderr_lines, process, shown, libcob_error)
    displays = [line for line in _lines(process.stdout) if not line.startswith(LIBCOB_PREFIX)]
    return ProgramOutcome(
        return_code=None if abended else process.returncode,
        abended=abended,
        signal=process.signal,
        messages=[*displays, ABEND_MARKER] if marker else list(displays),
        stdout=process.stdout,
        stderr=process.stderr,
        elapsed_s=process.elapsed_s,
        displays=displays,
        evidence=shown,
    )



# --------------------------------------------------------------------------
# Job runs
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class StepResult:
    """One executed job step: its 1-based position among the scenario's
    steps, its job, its qualified step name, its kind and program, its return
    code (``None`` after an abend), whether it abended, and its evidence
    directory and SYSPRINT file relative to the working directory."""

    index: int
    job: str
    step: str
    kind: str
    program: str | None
    return_code: int | None
    abended: bool
    evidence: str
    sysprint: str | None = None

    @property
    def line(self) -> str:
        """The step's line in ``messages``, as both adapters write it."""
        if self.abended:
            return f"STEP {self.step} ABEND"
        return f"STEP {self.step} RC {self.return_code:04d}"

    def as_evidence(self) -> dict[str, Any]:
        return {
            "abended": self.abended,
            "evidence": self.evidence,
            "index": self.index,
            "job": self.job,
            "kind": self.kind,
            "program": self.program,
            "return_code": self.return_code,
            "step": self.step,
            "sysprint": self.sysprint,
        }


@dataclass(frozen=True)
class JobResult:
    """One job of the scenario: the highest return code of its steps run
    (``None`` once one abended), whether it abended, and how many of its
    steps ran."""

    job: str
    return_code: int | None
    abended: bool
    steps_run: int

    def as_evidence(self) -> dict[str, Any]:
        return {
            "abended": self.abended,
            "job": self.job,
            "return_code": self.return_code,
            "steps_run": self.steps_run,
        }


@dataclass
class JobOutcome:
    """What the jobs of one scenario produced: ``return_code`` is the highest
    step return code, ``None`` when a step abended; ``messages`` the step
    lines and program ``DISPLAY`` lines in run order; ``step_results`` and
    ``job_results`` what ran, for evidence."""

    return_code: int | None
    messages: list[str]
    step_results: list[StepResult]
    job_results: list[JobResult]
    elapsed_s: float

    @property
    def abended(self) -> bool:
        return any(step.abended for step in self.step_results)


def _prepared_steps(job: str, scenario: Any, inputs_dir: Path) -> list[Mapping[str, Any]]:
    """The step table of ``job`` with its placeholders filled, every step's
    kind checked, before any step of the scenario runs."""
    steps = job_steps(job)
    for step in steps:
        kind = step.get("kind")
        if kind != "program" and kind not in UTILITY_KINDS:
            raise HarnessError(
                f"{display_path(JOBS_FILE)}: job {job} step {step.get('step')} has kind {kind!r}, "
                f"not program or one of {', '.join(UTILITY_KINDS)}"
            )
    names = _placeholders(steps)
    if not names:
        return steps
    unknown = sorted(names - set(_DATEPARM_PLACEHOLDERS))
    if unknown:
        raise HarnessError(
            f"{display_path(JOBS_FILE)}: job {job} uses "
            f"{', '.join('{{' + name + '}}' for name in unknown)}, which the driver does not fill"
        )
    return _substituted(steps, _dateparm_values(scenario, inputs_dir, job))


def _check_deadline(deadline: float | None, what: str) -> None:
    if deadline is not None and time.monotonic() >= deadline:
        raise ScenarioTimeout(f"the scenario deadline passed {what}")


def _step_evidence_dir(store: DatasetStore, index: int, job: str, step: str) -> Path:
    directory = store.inside(
        store.evidence_dir / _STEPS_SUBDIR / f"{index:02d}-{_file_name(job)}-{_file_name(step)}"
    )
    directory.mkdir(parents=True, exist_ok=True)
    return directory


def _write_step_evidence(directory: Path, content: Mapping[str, Any]) -> None:
    write_text(directory / "step.yaml", dump_yaml(dict(content)))


def _write_instream(
    store: DatasetStore, index: int, step: str, ddname: str, lines: Sequence[str], where: str
) -> Path:
    """The in-stream data of a program DD as a file of 80-byte card images,
    each line padded with spaces, encoded as the utilities encode theirs."""
    records = []
    for number, line in enumerate(lines, start=1):
        if len(line) > INSTREAM_RECORD_LENGTH:
            raise HarnessError(
                f"{where}: DD {ddname} in-stream line {number} is longer than "
                f"{INSTREAM_RECORD_LENGTH} columns"
            )
        try:
            records.append(line.encode("latin-1").ljust(INSTREAM_RECORD_LENGTH, b" "))
        except UnicodeEncodeError:
            raise HarnessError(
                f"{where}: DD {ddname} in-stream line {number} holds a character outside latin-1"
            ) from None
    name = f"{index:02d}-{_file_name(step)}-{_file_name(ddname)}.dat"
    path = store.inside(store.data_dir / _INSTREAM_SUBDIR / name)
    return store.write_sequential(path, b"".join(records), INSTREAM_RECORD_LENGTH)


def _allocate(store: DatasetStore, resolved: ResolvedDD, dd: Mapping[str, Any] | None) -> None:
    """Allocate a new sequential dataset, empty, before the program runs, as
    z/OS allocates every NEW DD at step start: a program that abends before
    opening it still leaves it to the step's disposition. A dataset the
    program declares indexed is left to its ``OPEN OUTPUT``, which builds the
    file with the declared keys."""
    if resolved.path is None or store.exists(resolved.path):
        return
    if dd is not None and dd.get("organization") == "indexed":
        return
    declared_length = dd.get("record_length") if dd is not None else None
    length = resolved.record_length or declared_length or INSTREAM_RECORD_LENGTH
    store.write_sequential(resolved.path, b"", length)


def _declared_profile(dd: Mapping[str, Any], unit: str, ddname: str) -> KeyProfile:
    keys = dd.get("keys")
    if not isinstance(keys, Mapping):
        raise HarnessError(
            f"{display_path(PROGRAMS_FILE)}: {unit} DD {ddname} is indexed but declares no keys"
        )
    return profile_for(dd.get("record_length"), keys)



def _program_step(
    cache: BuildCache, ctx: StepContext, job: str, root: Path, deadline: float | None, clock: str
) -> tuple[StepResult, list[str]]:
    """Run a ``kind: program`` step bound in ``ctx``; returns its result and
    its message lines."""
    step = ctx.step or {}
    name = ctx.step_name
    where = f"job {job} step {name}"
    unit = step.get("program")
    if not isinstance(unit, str) or not unit:
        raise HarnessError(f"{where}: a program step names no program")
    config = unit_config(unit)
    declared = program_dds(config, called_configs(unit))
    declared_dds = dict(declared)
    store, catalog = ctx.store, ctx.catalog
    evidence = _step_evidence_dir(store, ctx.step_index, job, name)

    dd_paths: dict[str, str | Path] = {}
    shown_dds: dict[str, str] = {}
    new_datasets: list[ResolvedDD] = []
    for ddname in ctx.dd_names():
        dd = declared_dds.get(ddname)
        if ddname in _IGNORED_DDS and dd is None:
            shown_dds[ddname] = "not allocated: the build cache stands for the load library"
            continue
        members = ctx.resolve_dd(ddname)
        if len(members) != 1:
            if dd is not None:
                raise HarnessError(
                    f"{where}: {unit} reads DD {ddname}, a concatenation, as one file"
                )
            shown_dds[ddname] = "concatenation, not opened by the program"
            continue
        resolved = members[0]
        if resolved.sysout or resolved.member:
            what = "SYSOUT" if resolved.sysout else f"member of {resolved.coded}"
            if dd is not None:
                raise HarnessError(
                    f"{where}: DD {ddname} of {unit} is {what}, which no program file can be here"
                )
            shown_dds[ddname] = what
            continue
        if resolved.dummy:
            dd_paths[ddname] = os.devnull
            shown_dds[ddname] = "DUMMY"
            continue
        if resolved.instream is not None:
            path = _write_instream(store, ctx.step_index, name, ddname, resolved.instream, where)
            dd_paths[ddname] = path
            shown_dds[ddname] = f"in-stream data, {store.relative(path)}"
            continue
        if resolved.path is None:
            raise HarnessError(
                f"{where}: DD {ddname} codes {resolved.coded}, which names no generation; the "
                f"allocation failure z/OS reports for it is not emulated (GAP-STB-009)"
            )
        dd_paths[ddname] = resolved.path
        shown_dds[ddname] = f"{resolved.shown}, {store.relative(resolved.path)}"
        if resolved.is_new:
            new_datasets.append(resolved)
            _allocate(store, resolved, dd)
    for ddname, dd in declared:
        if ddname not in dd_paths:
            raise HarnessError(
                f"{where}: {unit} declares DD {ddname}, which the step does not code"
            )
        coded = ctx.dd(ddname).get("logical")
        if coded is not None and coded != dd.get("logical"):
            raise HarnessError(
                f"{where}: DD {ddname} holds {coded}, but {display_path(PROGRAMS_FILE)} maps "
                f"{unit} DD {ddname} to {dd.get('logical')}"
            )

    indexed = {
        ddname: _declared_profile(dd, unit, ddname)
        for ddname, dd in declared
        if dd.get("organization") == "indexed" and dd_paths[ddname] != os.devnull
    }
    keys_ready = {
        ddname: store.ensure_program_keys(
            dd_paths[ddname], profile, declared_dds[ddname].get("open")
        )
        for ddname, profile in indexed.items()
    }
    tiot = None
    if config.get("tiot") is not None:
        # TIOTJSTP is the job step's name; a procedure step's own name would be
        # TIOTPSTP, which the shim leaves blank (GAP-STB-003).
        tiot = {"job": job, "step": name.split(".")[0], "dds": ctx.dd_names()}
    parm = step.get("parm")
    if parm is None and config.get("run") == "parmdrv":
        parm = ""  # an EXEC without PARM passes a zero-length PARM
    content: dict[str, Any] = {
        "cond": step.get("cond"),  # recorded, never evaluated: a JCL-semantics item run on unikix
        "dds": shown_dds,
        "index": ctx.step_index,
        "job": job,
        "keys_ready": keys_ready,
        "kind": "program",
        "parm": parm,
        "program": unit,
        "status": "started",
        "step": name,
    }
    _write_step_evidence(evidence, content)
    outcome = run_program(
        cache, unit, dd_paths, workdir=root, deadline=deadline, clock=clock, parm=parm,
        tiot=tiot, evidence_dir=evidence,
    )

    # An OPEN OUTPUT recreates an indexed file with the program's declared keys
    # whether or not the step then ends normally, so the store records them in
    # either case; without the OPEN the file already has them.
    for ddname, profile in indexed.items():
        if declared_dds[ddname].get("open") == "output":
            store.after_program_output(dd_paths[ddname], profile)
    if outcome.abended:
        created: list[str] = []
        deleted: list[str] = []
        for resolved in new_datasets:
            if resolved.path is not None and store.exists(resolved.path):
                catalog.note_uncatalogued(
                    resolved.shown, resolved.path, f"{where} abended: no disposition applied"
                )
    else:
        created, deleted = apply_normal_dispositions(step, ctx)
    result = StepResult(
        ctx.step_index, job, name, "program", unit, outcome.return_code, outcome.abended,
        store.relative(evidence),
    )
    content.update(
        status="ended",
        return_code=outcome.return_code,
        abended=outcome.abended,
        signal=outcome.signal,
        catalog={"created": created, "deleted": deleted},
    )
    _write_step_evidence(evidence, content)
    # A CEE3ABD abend shows as the ABEND step line; the marker is not repeated.
    return result, [result.line, *outcome.displays]


def _utility_step(ctx: StepContext, job: str) -> tuple[StepResult, list[str]]:
    """Run a utility step bound in ``ctx``; its SYSPRINT goes to the
    evidence only, never to the messages."""
    step = ctx.step or {}
    name = ctx.step_name
    kind = str(step.get("kind"))
    evidence = _step_evidence_dir(ctx.store, ctx.step_index, job, name)
    content: dict[str, Any] = {
        "cond": step.get("cond"),  # recorded, never evaluated: a JCL-semantics item run on unikix
        "index": ctx.step_index,
        "job": job,
        "kind": kind,
        "status": "started",
        "step": name,
    }
    _write_step_evidence(evidence, content)
    outcome = run_utility(step, ctx)
    if isinstance(outcome.rc, bool) or not isinstance(outcome.rc, int) or outcome.rc < 0:
        raise HarnessError(
            f"job {job} step {name}: the {kind} replacement returned RC {outcome.rc!r}"
        )
    result = StepResult(
        ctx.step_index, job, name, kind, None, outcome.rc, False,
        ctx.store.relative(evidence), outcome.evidence,
    )
    content.update(
        status="ended",
        return_code=outcome.rc,
        abended=False,
        sysprint=outcome.evidence,
        catalog={"created": list(outcome.created), "deleted": list(outcome.deleted)},
    )
    _write_step_evidence(evidence, content)
    return result, [result.line]


def _write_jobs_evidence(
    store: DatasetStore, catalog: Catalog, jobs: Sequence[JobResult], steps: Sequence[StepResult]
) -> None:
    target = store.inside(store.evidence_dir / "jobs.yaml")
    write_text(
        target,
        dump_yaml({
            "jobs": [job.as_evidence() for job in jobs],
            "steps": [step.as_evidence() for step in steps],
        }),
    )
    catalog.write_evidence()


def run_jobs(
    cache: BuildCache,
    scenario: Any,
    catalog: Catalog,
    *,
    workdir: str | os.PathLike[str],
    deadline: float | None,
    clock: str = DEFAULT_CLOCK,
    inputs_dir: str | os.PathLike[str] | None = None,
    repo_root: str | os.PathLike[str] = REPO_ROOT,
) -> JobOutcome:
    """Run the jobs of the scenario's ``inputs.job`` (a name or an ordered
    list) in order against ``catalog``, the scenario's catalog
    (``datasets.Catalog.seed``), and return what they produced.

    Every job must have a ``jobs.yaml`` step table, and TRNRPT00's
    ``{{DATEPARM_START}}`` and ``{{DATEPARM_END}}`` are filled from the
    scenario's DATEPARM (``dateparm_record`` of ``inputs_dir``, default
    ``<workdir>/inputs``) before any step runs. ``clock`` is the program
    clock of ``run_program``; ``repo_root`` holds the SYSIN members the
    utilities read. The deadline is checked before every step and bounds
    every subprocess (``ScenarioTimeout``).

    Raises ``HarnessError`` (``UtilityError`` and ``DatasetError``
    included) for a step the emulation cannot run as transcribed; the step
    and job evidence written so far stays."""
    root = _checked_workdir(workdir)
    store = catalog.store
    if store.workdir != root:
        raise HarnessError(
            f"the catalog's working directory {store.workdir} is not the scenario's {root}"
        )
    names = scenario_jobs(scenario)
    if not names:
        raise HarnessError(f"{_scenario_id(scenario)} is not a job scenario: it has no inputs.job")
    inputs = Path(inputs_dir) if inputs_dir is not None else root / INPUTS_SUBDIR
    plans = [(job, _prepared_steps(job, scenario, inputs)) for job in names]
    started = time.monotonic()
    messages: list[str] = []
    step_results: list[StepResult] = []
    job_results: list[JobResult] = []
    index = 0
    failure: BaseException | None = None
    try:
        for job, steps in plans:
            generations = JobGenerations(catalog)  # relative generations fixed at job start
            run_here: list[StepResult] = []
            for step in steps:
                index += 1
                _check_deadline(deadline, f"before job {job} step {step.get('step')}")
                ctx = StepContext(
                    store, catalog, generations, catalog.dsn_map, Path(repo_root), root, index,
                    deadline, job=job,
                ).bound(step)
                if step.get("kind") == "program":
                    result, lines = _program_step(cache, ctx, job, root, deadline, clock)
                else:
                    result, lines = _utility_step(ctx, job)
                run_here.append(result)
                step_results.append(result)
                # Only step and DISPLAY lines: the unikix BPE run of the same
                # scenario reports exactly these, with no line per job, so a
                # job line here would make every scenario both adapters run
                # diverge; each job's own return code goes to evidence/jobs.yaml.
                messages.extend(lines)
                if result.abended:
                    break
            job_abended = any(result.abended for result in run_here)
            job_results.append(
                JobResult(
                    job,
                    None if job_abended else max(result.return_code or 0 for result in run_here),
                    job_abended,
                    len(run_here),
                )
            )
            if job_abended:
                break
    except BaseException as exc:
        failure = exc
        raise
    finally:
        try:
            _write_jobs_evidence(store, catalog, job_results, step_results)
        except (OSError, HarnessError) as problem:
            if failure is None:
                raise
            failure.add_note(
                f"the job evidence could not be written: {type(problem).__name__}: {problem}"
            )
    abended = any(result.abended for result in step_results)
    return JobOutcome(
        return_code=None if abended else max(result.return_code or 0 for result in step_results),
        messages=messages,
        step_results=step_results,
        job_results=job_results,
        elapsed_s=time.monotonic() - started,
    )

