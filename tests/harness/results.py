"""Scenario result records: shape, execution id, source identity, execution
fingerprint and persistence.

A result record is a plain mapping holding exactly ``RECORD_FIELDS``::

    scenario_id, adapter, execution_id, status, reason, gap_id,
    baseline_version, scenario_digest, source_identity,
    execution_fingerprint, decoded, evidence_path

``status`` is one of ``STATUSES``. ``reason`` is null for ``passed`` and one of
``REASONS_FOR_STATUS[status]`` otherwise. ``gap_id`` names the
``tests/inventory/gaps.yaml`` entry of a ``blocked`` or
``implemented-unexecuted`` record and is null for the other statuses.
``scenario_digest`` is ``harness.scenarios.digest`` of the scenario as it ran,
and ``baseline_version`` the version of the baseline it was compared with, so
``is_current`` can tell a record that still describes the scenario and
baseline from a ``stale`` one. ``decoded`` is the deterministic projection of
``harness.baselines.projection`` (null when nothing ran).

Where records live
    ``gnucobol-batch`` records are written by ``write`` to
    ``tests/reports/results/<id>.yaml``, one of the four paths that two
    consecutive full runs must leave byte-identical. A record therefore holds
    canonical YAML only: no float, no date or time object, no elapsed time and
    no absolute path. Its ``evidence_path`` is the record itself. Elapsed
    times, the kept working directory of a failing scenario and exception
    details go to the git-ignored ``tests/reports/runtime/``
    (``write_runtime``), as do the facts the adapter reports about its
    environment (``write_environment``). ``unikix`` records are never written
    here: the raw record is the immutable evidence ``result.yaml`` under
    ``tests/reports/unikix/<execution_id>/<id>/``, and the record the status
    rules read is the ``validation.yaml`` beside it.

Identities
    ``execution_id`` of a gnucobol-batch run is ``gnucobol-batch-`` and the
    first 16 hex digits of the sha256 of the commit, the scenario id, the
    baseline version and the build key, so it is the same for every run of the
    same sources. A unikix id comes from its adapter and is passed through.
    ``source_identity`` is what a gnucobol-batch run executed: the ``app``
    tree, the commit and the sha256 of every program, copybook, job source,
    procedure and control card involved. ``execution_fingerprint`` is the
    sha256 of everything that decides what was executed: the adapter name,
    the scenario's ``inputs``, and its preconditions with each dataset replaced
    by the sha256 of the bytes actually materialized. ``expected``, tags, gap
    ids and inventory ids are outside it, so a record whose fingerprint is
    unchanged can be re-compared after an assertion or metadata change.

The module writes only under ``tests/reports/results/`` and
``tests/reports/runtime/``.
"""

from __future__ import annotations

import datetime as _dt
import re
import sys
import threading
from collections.abc import Iterator, Mapping
from decimal import Decimal
from pathlib import Path, PurePath, PurePosixPath
from typing import Any

_TESTS_ROOT = str(Path(__file__).resolve().parents[1])
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from harness import (  # noqa: E402
    ADAPTER_NAMES,
    ADAPTERS_DIR,
    REPO_ROOT,
    RESULTS_DIR,
    RUNTIME_DIR,
    UNIKIX_EVIDENCE_DIR,
    HarnessError,
    YAMLLoadError,
    display_path,
    dump_yaml,
    git_head,
    load_yaml,
    sha256_file,
    sha256_hex,
    write_text,
)
from harness import baselines as _baselines  # noqa: E402
from harness import fixtures as _fixtures  # noqa: E402
from harness import inventory_tools as _inventory_tools  # noqa: E402
from harness import scenarios as _scenarios  # noqa: E402

__all__ = [
    "ENVIRONMENT_FILE",
    "GNUCOBOL",
    "GNUCOBOL_JOBS_FILE",
    "REASONS",
    "REASONS_FOR_STATUS",
    "RECORD_FIELDS",
    "RESULTS_DIR",
    "RUNTIME_DIR",
    "SOURCE_TREES",
    "STATUSES",
    "STATUS_BLOCKED",
    "STATUS_FAILING",
    "STATUS_PASSED",
    "STATUS_UNEXECUTED",
    "UNIKIX",
    "ResultError",
    "build_record",
    "evidence_path_for",
    "execution_fingerprint",
    "execution_id",
    "is_current",
    "load",
    "load_all",
    "load_environment",
    "record_problems",
    "source_identity",
    "write",
    "write_environment",
    "write_runtime",
]

# --------------------------------------------------------------------------
# Contract
# --------------------------------------------------------------------------

RECORD_FIELDS = (
    "scenario_id",
    "adapter",
    "execution_id",
    "status",
    "reason",
    "gap_id",
    "baseline_version",
    "scenario_digest",
    "source_identity",
    "execution_fingerprint",
    "decoded",
    "evidence_path",
)

STATUS_PASSED = "passed"
STATUS_FAILING = "failing"
STATUS_BLOCKED = "blocked"
STATUS_UNEXECUTED = "implemented-unexecuted"
STATUSES = (STATUS_PASSED, STATUS_FAILING, STATUS_BLOCKED, STATUS_UNEXECUTED)

REASONS = (
    "assertion",
    "error",
    "timeout",
    "cross-adapter-divergence",
    "adapter-unavailable",
    "missing-prerequisite",
    "stale",
)

# The reasons each status takes: AdapterUnavailable makes a scenario blocked,
# MissingPrerequisite (or a record that no longer matches its scenario and
# baseline) implemented-unexecuted, and every failure of a run that had an
# outcome failing.
REASONS_FOR_STATUS = {
    STATUS_PASSED: (None,),
    STATUS_FAILING: ("assertion", "error", "timeout", "cross-adapter-divergence"),
    STATUS_BLOCKED: ("adapter-unavailable",),
    STATUS_UNEXECUTED: ("missing-prerequisite", "stale"),
}
_GAP_STATUSES = frozenset({STATUS_BLOCKED, STATUS_UNEXECUTED})

GNUCOBOL = "gnucobol-batch"
UNIKIX = "unikix"
SOURCE_TREES = ("app", "unikix-copy")
_IDENTITY_KEYS = frozenset({"tree", "commit", "zip_sha256", "units"})

GNUCOBOL_JOBS_FILE = ADAPTERS_DIR / "gnucobol_batch" / "jobs.yaml"
ENVIRONMENT_FILE = "environment.yaml"
_GNUCOBOL_ID_PREFIX = "gnucobol-batch-"
_GNUCOBOL_ID_DIGITS = 16

# Repository-relative folders, as evidence paths name them.
_RESULTS_REL = RESULTS_DIR.relative_to(REPO_ROOT).as_posix()
_UNIKIX_REL = UNIKIX_EVIDENCE_DIR.relative_to(REPO_ROOT).as_posix()
_PROGRAM_DIR = "app/cbl"
_JCL_DIR = "app/jcl"
_PROGRAM_SUFFIXES = frozenset({".cbl"})
_JCL_SUFFIXES = frozenset({".jcl"})

# TRNRPT00 is not an app/jcl member: CORPT00C writes its JCL to the JOBS queue
# [app/cbl/CORPT00C.cbl:84-127] and the job runs procedure TRANREPT. These are
# its sources when no step table lists them.
_GENERATED_JOB_SOURCES = {"TRNRPT00": ("app/cbl/CORPT00C.cbl", "app/proc/TRANREPT.prc")}

_GAP_ID_RE = re.compile(r"GAP-(DOC|DEF|STB|MIS|INF|UNC)-[0-9]{3}")
_SHA256_RE = re.compile(r"[0-9a-f]{64}")
_GNUCOBOL_ID_RE = re.compile(rf"{_GNUCOBOL_ID_PREFIX}[0-9a-f]{{{_GNUCOBOL_ID_DIGITS}}}")
_UNIKIX_ID_RE = re.compile(r"unikix-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}")
# A scenario id as a path component: what any scenario file stem can be, and
# never a separator or "..". write and load further restrict ids to the manifest.
_SAFE_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
_LINES_SUFFIX_RE = re.compile(r":[0-9]+(?:-[0-9]+)?(?:,[0-9]+(?:-[0-9]+)?)*$")
# A static CALL of another unit: CBSTM03A calls CBSTM03B this way
# [app/cbl/CBSTM03A.CBL:351]. Targets outside app/cbl (CEE3ABD, CEEDAYS) are
# test-tree stubs, not app sources.
_CALL_RE = re.compile(r"(?<![A-Za-z0-9-])CALL\s+(['\"])([A-Za-z0-9#$@-]+)\1", re.IGNORECASE)

_REPO_TEXT = str(REPO_ROOT)


class ResultError(HarnessError):
    """A result record, or what it is built or read from, that breaks the
    result record contract."""


# --------------------------------------------------------------------------
# Plain, deterministic data
# --------------------------------------------------------------------------


def _plain(value: Any, where: str) -> Any:
    """``value`` as plain YAML data that loads back equal: ``None``, ``bool``,
    ``int``, ``str``, lists and mappings with ``str`` or ``int`` keys.
    ``Decimal`` becomes its text, with its scale, as ``dump_yaml`` writes it;
    tuples become lists. A float, a date or time, bytes, a path or any other
    object raises ``ResultError``: none of them belongs in the deterministic
    projection."""
    if value is None or isinstance(value, (bool, int, str)):
        return value
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, Mapping):
        plain: dict[Any, Any] = {}
        for key, item in value.items():
            if isinstance(key, bool) or not isinstance(key, (str, int)):
                raise ResultError(f"{where}: key {key!r} is not text or an integer")
            plain[key] = _plain(item, f"{where}.{key}")
        return plain
    if isinstance(value, (list, tuple)):
        return [_plain(item, f"{where}[{index}]") for index, item in enumerate(value)]
    if isinstance(value, float):
        raise ResultError(f"{where}: float {value!r}; numbers are decimal text, never floats")
    if isinstance(value, (_dt.date, _dt.time)):
        raise ResultError(f"{where}: {type(value).__name__} {value!s}; a record holds no wall-clock date or time")
    raise ResultError(f"{where}: {type(value).__name__} is not plain YAML data")


def _texts(value: Any, where: str) -> Iterator[tuple[str, str]]:
    """Every string of a plain value, keys included, with where it lies."""
    if isinstance(value, str):
        yield where, value
    elif isinstance(value, Mapping):
        for key, item in value.items():
            if isinstance(key, str):
                yield f"{where}.{key} (key)", key
            yield from _texts(item, f"{where}.{key}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            yield from _texts(item, f"{where}[{index}]")


def _relative_posix(text: Any) -> bool:
    """Whether ``text`` is a repository-relative POSIX path that stays inside
    the repository: not absolute, no backslash, no empty, ``.`` or ``..`` part."""
    if not isinstance(text, str) or not text or text.startswith("/") or "\\" in text:
        return False
    return all(part not in ("", ".", "..") for part in text.split("/"))


def _scenario_id(scenario: Any) -> str:
    """The id of a scenario object, or the id itself."""
    value = scenario if isinstance(scenario, str) else getattr(scenario, "id", None)
    if not isinstance(value, str) or not _SAFE_ID_RE.fullmatch(value):
        raise ResultError(f"{value!r} is not a scenario id")
    return value


def _adapter_name(adapter_name: Any) -> str:
    if adapter_name not in ADAPTER_NAMES:
        raise ResultError(f"adapter {adapter_name!r} is not one of {', '.join(ADAPTER_NAMES)}")
    return adapter_name


# --------------------------------------------------------------------------
# Record shape
# --------------------------------------------------------------------------


def evidence_path_for(adapter_name: str, scenario_id: str, execution_id: str | None = None) -> str | None:
    """The repository-relative ``evidence_path`` of a record.

    gnucobol-batch: ``tests/reports/results/<id>.yaml``, the record itself
    (the working directory a failing scenario keeps lies outside the
    repository and goes only to the runtime file). unikix:
    ``tests/reports/unikix/<execution_id>/<id>``, or ``None`` while the
    execution id is unknown."""
    adapter_name = _adapter_name(adapter_name)
    scenario_id = _scenario_id(scenario_id)
    if adapter_name == GNUCOBOL:
        return f"{_RESULTS_REL}/{scenario_id}.yaml"
    if execution_id is None:
        return None
    if not isinstance(execution_id, str) or not _UNIKIX_ID_RE.fullmatch(execution_id):
        raise ResultError(f"{execution_id!r} is not a unikix execution id unikix-<yyyymmddThhmmssZ>-<8 hex>")
    # Written without a trailing separator: adapters/unikix/evidence.py forces
    # this exact form into result.yaml, and validation.yaml and the coverage
    # matrix must name the same directory the same way.
    return f"{_UNIKIX_REL}/{execution_id}/{scenario_id}"


def _status_problems(status: Any, reason: Any, gap_id: Any) -> list[str]:
    if status not in STATUSES:
        return [f"status: {status!r} is not one of {', '.join(STATUSES)}"]
    problems = []
    allowed = REASONS_FOR_STATUS[status]
    if reason not in allowed:
        if status == STATUS_PASSED:
            problems.append(f"reason: a passed record has no reason, got {reason!r}")
        else:
            problems.append(f"reason: {reason!r} is not one of {', '.join(allowed)} for a {status} record")
    if status in _GAP_STATUSES:
        if not (isinstance(gap_id, str) and _GAP_ID_RE.fullmatch(gap_id)):
            problems.append(f"gap_id: a {status} record names its gap GAP-<CAT>-<nnn>, got {gap_id!r}")
    elif gap_id is not None:
        problems.append(f"gap_id: only blocked and implemented-unexecuted records name a gap, got {gap_id!r}")
    return problems


def _execution_id_problems(adapter: Any, value: Any) -> list[str]:
    if value is None:
        return []
    if adapter == GNUCOBOL and not (isinstance(value, str) and _GNUCOBOL_ID_RE.fullmatch(value)):
        return [f"execution_id: {value!r} is not gnucobol-batch-<16 hex>"]
    if adapter == UNIKIX and not (isinstance(value, str) and _UNIKIX_ID_RE.fullmatch(value)):
        return [f"execution_id: {value!r} is not unikix-<yyyymmddThhmmssZ>-<8 hex>"]
    return []


def _identity_problems(identity: Any) -> list[str]:
    if identity is None:
        return []
    if not isinstance(identity, Mapping):
        return ["source_identity: must be a mapping {tree, commit, units}"]
    problems = []
    unknown = sorted(str(key) for key in identity if key not in _IDENTITY_KEYS)
    if unknown:
        problems.append(f"source_identity: unknown key(s) {', '.join(unknown)}")
    tree = identity.get("tree")
    if tree not in SOURCE_TREES:
        problems.append(f"source_identity.tree: {tree!r} is not one of {', '.join(SOURCE_TREES)}")
    commit = identity.get("commit")
    if "commit" in identity and not (isinstance(commit, str) and commit.strip()):
        problems.append("source_identity.commit: must be the commit text")
    if tree == "unikix-copy" and not (
        isinstance(identity.get("zip_sha256"), str) and _SHA256_RE.fullmatch(identity["zip_sha256"])
    ):
        problems.append("source_identity.zip_sha256: a unikix-copy identity names the zip's sha256")
    units = identity.get("units")
    if not isinstance(units, Mapping):
        problems.append("source_identity.units: must map repository-relative paths to sha256")
        return problems
    for path, digest in units.items():
        if not _relative_posix(path):
            problems.append(f"source_identity.units: {path!r} is not a repository-relative path")
        elif not (isinstance(digest, str) and _SHA256_RE.fullmatch(digest)):
            problems.append(f"source_identity.units.{path}: {digest!r} is not a sha256 hex digest")
    return problems


def record_problems(record: Any) -> list[str]:
    """Every way ``record`` breaks the result record contract; empty when it
    conforms. Checked: exactly ``RECORD_FIELDS``; the status, reason and gap
    id agree; the version, digest, fingerprint and execution id have their
    forms; ``source_identity`` units and ``evidence_path`` are
    repository-relative (a gnucobol-batch record names itself); ``decoded`` is
    plain data; and no text holds the checkout's absolute location."""
    if not isinstance(record, Mapping):
        return ["(record): must be a mapping of the result record fields"]
    problems: list[str] = []
    missing = [name for name in RECORD_FIELDS if name not in record]
    if missing:
        problems.append(f"(record): missing field(s) {', '.join(missing)}")
    unknown = sorted(str(key) for key in record if key not in RECORD_FIELDS)
    if unknown:
        problems.append(f"(record): unknown field(s) {', '.join(unknown)}; the fields are {', '.join(RECORD_FIELDS)}")
    scenario_id = record.get("scenario_id")
    if not (isinstance(scenario_id, str) and _SAFE_ID_RE.fullmatch(scenario_id)):
        problems.append(f"scenario_id: {scenario_id!r} is not a scenario id")
        scenario_id = None
    adapter = record.get("adapter")
    if adapter not in ADAPTER_NAMES:
        problems.append(f"adapter: {adapter!r} is not one of {', '.join(ADAPTER_NAMES)}")
    problems.extend(_status_problems(record.get("status"), record.get("reason"), record.get("gap_id")))
    version = record.get("baseline_version")
    if version is not None:
        try:
            _baselines.parse_version(version)
        except _baselines.BaselineError as exc:
            problems.append(f"baseline_version: {exc}")
    digest = record.get("scenario_digest")
    if not (isinstance(digest, str) and _SHA256_RE.fullmatch(digest)):
        problems.append(f"scenario_digest: {digest!r} is not a sha256 hex digest")
    fingerprint = record.get("execution_fingerprint")
    if fingerprint is not None and not (isinstance(fingerprint, str) and _SHA256_RE.fullmatch(fingerprint)):
        problems.append(f"execution_fingerprint: {fingerprint!r} is not a sha256 hex digest")
    problems.extend(_execution_id_problems(adapter, record.get("execution_id")))
    problems.extend(_identity_problems(record.get("source_identity")))
    path = record.get("evidence_path")
    if path is not None and not _relative_posix(path):
        problems.append(f"evidence_path: {path!r} is not a repository-relative path")
    elif adapter == GNUCOBOL and scenario_id is not None:
        wanted = evidence_path_for(GNUCOBOL, scenario_id)
        if path != wanted:
            problems.append(f"evidence_path: a gnucobol-batch record names itself, {wanted}, not {path!r}")
    decoded = record.get("decoded")
    if decoded is not None and not isinstance(decoded, Mapping):
        problems.append(f"decoded: must be the decoded projection mapping or null, not {type(decoded).__name__}")
    plain: Any = None
    try:
        plain = _plain(dict(record), "(record)")
    except ResultError as exc:
        problems.append(str(exc))
    except RecursionError:
        problems.append("(record): nests too deeply or refers to itself")
    if plain is not None:
        for where, text in _texts(plain, "(record)"):
            if text == _REPO_TEXT or f"{_REPO_TEXT}/" in text:
                problems.append(f"{where}: holds the checkout's absolute path; records name paths relative to it")
    return problems


def build_record(
    scenario: Any,
    adapter_name: str,
    status: str,
    reason: str | None = None,
    gap_id: str | None = None,
    baseline_version: str | None = None,
    decoded: Mapping[str, Any] | None = None,
    execution_fingerprint: str | None = None,
    source_identity: Mapping[str, Any] | None = None,
    execution_id: str | None = None,
    evidence_path: str | None = None,
) -> dict:
    """The result record of one run of ``scenario`` on ``adapter_name``.

    ``scenario_digest`` is ``harness.scenarios.digest(scenario)``.
    ``evidence_path`` defaults to ``evidence_path_for``; a gnucobol-batch
    record always names itself. ``decoded`` and ``source_identity`` are
    copied as plain data (``Decimal`` as its text). The record is checked with
    ``record_problems`` and ``ResultError`` lists every problem, so an
    unknown status or reason, a missing gap id, a float, a date or an absolute
    path never reaches a written record."""
    scenario_id = _scenario_id(scenario)
    adapter_name = _adapter_name(adapter_name)
    if evidence_path is None:
        evidence_path = evidence_path_for(adapter_name, scenario_id, execution_id)
    try:
        record = {
            "scenario_id": scenario_id,
            "adapter": adapter_name,
            "execution_id": execution_id,
            "status": status,
            "reason": reason,
            "gap_id": gap_id,
            "baseline_version": baseline_version,
            "scenario_digest": _scenarios.digest(scenario),
            "source_identity": _plain(source_identity, "source_identity"),
            "execution_fingerprint": execution_fingerprint,
            "decoded": _plain(decoded, "decoded"),
            "evidence_path": evidence_path,
        }
    except RecursionError:
        raise ResultError(f"{scenario_id}: the decoded outcome nests too deeply or refers to itself") from None
    except ResultError as exc:
        raise ResultError(f"{scenario_id}: result record: {exc}") from None
    problems = record_problems(record)
    if problems:
        raise ResultError(f"{scenario_id}: result record: " + "; ".join(problems))
    return record


# --------------------------------------------------------------------------
# Identities
# --------------------------------------------------------------------------


def execution_id(
    adapter_name: str,
    scenario: Any,
    baseline_version: str | None = None,
    build_key: str | None = None,
    commit: str | None = None,
    *,
    supplied: str | None = None,
) -> str:
    """The execution id of one run.

    gnucobol-batch: ``gnucobol-batch-`` and the first 16 hex digits of the
    sha256 of the commit (``git_head()`` when not given), the scenario id, the
    baseline version and the build key joined by line breaks, a missing
    version or key counting as empty text. It is the same for every run of the
    same sources, so two consecutive runs write identical records. unikix:
    the per-session id its adapter makes, passed as ``supplied`` and returned
    once its form ``unikix-<yyyymmddThhmmssZ>-<8 hex>`` is checked."""
    adapter_name = _adapter_name(adapter_name)
    scenario_id = _scenario_id(scenario)
    if adapter_name == UNIKIX:
        if not isinstance(supplied, str) or not _UNIKIX_ID_RE.fullmatch(supplied):
            raise ResultError(
                f"{scenario_id}: a unikix execution id comes from its adapter as "
                f"unikix-<yyyymmddThhmmssZ>-<8 hex>, got {supplied!r}"
            )
        return supplied
    if supplied is not None:
        raise ResultError(f"{scenario_id}: gnucobol-batch derives its execution id; none is supplied")
    for name, value in (("baseline_version", baseline_version), ("build_key", build_key), ("commit", commit)):
        if value is not None and not isinstance(value, str):
            raise ResultError(f"{scenario_id}: {name} must be text, not {type(value).__name__}")
    if commit is None:
        commit = git_head()
    text = "\n".join([commit, scenario_id, baseline_version or "", build_key or ""])
    return _GNUCOBOL_ID_PREFIX + sha256_hex(text)[:_GNUCOBOL_ID_DIGITS]


def _member(folder: Path, name: str, suffixes: frozenset[str]) -> Path | None:
    """The file of ``folder`` whose stem is ``name`` and whose extension is one
    of ``suffixes``, both without regard to case (``CBSTM03A.CBL``,
    ``CREASTMT.JCL``); ``None`` when there is none."""
    if not folder.is_dir():
        return None
    wanted = name.upper()
    found = [
        entry for entry in sorted(folder.iterdir())
        if entry.is_file() and entry.stem.upper() == wanted and entry.suffix.lower() in suffixes
    ]
    if len(found) > 1:
        raise ResultError(f"{name} has more than one source in {display_path(folder)}: "
                          + ", ".join(entry.name for entry in found))
    return found[0] if found else None


def _inside(path: Path, root: Path, what: str) -> Path:
    """``path`` resolved, refused unless it is a regular file inside ``root``."""
    resolved = path.resolve()
    try:
        resolved.relative_to(root)
    except ValueError:
        raise ResultError(f"{what}: {display_path(path)} lies outside the repository") from None
    if not resolved.is_file():
        raise ResultError(f"{what}: {display_path(path)} is not a file of the repository")
    return resolved


def _program_paths(program: str, root: Path, required: bool) -> set[Path]:
    """The sources a run of ``program`` executes: the program and every
    copybook its COPY statements reach (``inventory_tools.copy_closure``),
    and the same for each app/cbl unit it CALLs, transitively. An unknown
    ``program`` raises when ``required`` and contributes nothing otherwise."""
    program_dir = root / _PROGRAM_DIR
    paths: set[Path] = set()
    seen: set[str] = set()
    pending = [program.upper()]
    while pending:
        unit = pending.pop()
        if unit in seen:
            continue
        seen.add(unit)
        source = _member(program_dir, unit, _PROGRAM_SUFFIXES)
        if source is None:
            if required:
                raise ResultError(f"program {unit} has no source in {_PROGRAM_DIR}")
            continue
        paths.update(_inventory_tools.copy_closure(source, root))
        for line in _inventory_tools.resolve_copies(source, root):
            for match in _CALL_RE.finditer(line.text):
                target = match.group(2).upper()
                if target not in seen and _member(program_dir, target, _PROGRAM_SUFFIXES) is not None:
                    pending.append(target)
    return paths


_JOBS_LOCK = threading.Lock()
_JOBS_CACHE: dict[str, tuple[tuple[int, int], dict]] = {}


def _job_table(jobs_file: Path) -> dict | None:
    """The ``jobs`` mapping of the gnucobol-batch step tables, re-read when
    the file changes; ``None`` when the file does not exist."""
    try:
        status = jobs_file.stat()
    except FileNotFoundError:
        return None
    stamp = (status.st_mtime_ns, status.st_size)
    key = str(jobs_file)
    with _JOBS_LOCK:
        hit = _JOBS_CACHE.get(key)
    if hit is not None and hit[0] == stamp:
        return hit[1]
    try:
        document = load_yaml(jobs_file)
    except YAMLLoadError as exc:
        raise ResultError(str(exc)) from None
    jobs = document.get("jobs") if isinstance(document, Mapping) else None
    if not isinstance(jobs, Mapping):
        raise ResultError(f"{display_path(jobs_file)}: holds no jobs mapping of step tables")
    table = dict(jobs)
    with _JOBS_LOCK:
        _JOBS_CACHE[key] = (stamp, table)
    return table


def _listed_path(value: Any, root: Path, where: str) -> Path:
    """A source a step table names, ``<path>`` or ``<path>:<lines>``, as a
    resolved file of the repository."""
    if not isinstance(value, str):
        raise ResultError(f"{where}: {value!r} is not a repository path")
    text = _LINES_SUFFIX_RE.sub("", value)
    if not _relative_posix(text):
        raise ResultError(f"{where}: {value!r} is not a repository-relative path")
    return _inside(root / PurePosixPath(text), root, where)


def _step_paths(job: str, index: int, step: Any, root: Path) -> set[Path]:
    """The sources one step-table step executes: its procedure, the members
    its DDs read (``REPROCT``) and, for a program step, the program's units."""
    where = f"jobs.{job}.steps[{index}]"
    if not isinstance(step, Mapping):
        raise ResultError(f"{where}: must be a mapping")
    paths: set[Path] = set()
    proc = step.get("proc")
    if isinstance(proc, Mapping) and proc.get("source") is not None:
        paths.add(_listed_path(proc["source"], root, f"{where}.proc.source"))
    dds = step.get("dd")
    if isinstance(dds, Mapping):
        for ddname, spec in dds.items():
            entries = [spec] if isinstance(spec, Mapping) else []
            if entries and isinstance(spec.get("concat"), list):
                entries.extend(item for item in spec["concat"] if isinstance(item, Mapping))
            for entry in entries:
                member = entry.get("member")
                if isinstance(member, Mapping) and member.get("source") is not None:
                    paths.add(_listed_path(member["source"], root, f"{where}.dd.{ddname}.member.source"))
    if step.get("kind") == "program":
        program = step.get("program")
        if not isinstance(program, str):
            raise ResultError(f"{where}: a program step names its program")
        paths |= _program_paths(program, root, required=True)
    return paths


def _job_paths(job: str, table: Mapping | None, root: Path) -> set[Path]:
    """The sources a run of ``job`` executes. With a step table: the job's
    source, its procedures and every step's sources. Without one, only the
    job's own JCL (``app/jcl/<JOB>``, or the sources of the generated
    ``TRNRPT00``); an unknown job contributes nothing."""
    entry = table.get(job) if table is not None else None
    if entry is None:
        if job in _GENERATED_JOB_SOURCES:
            candidates = [root / relative for relative in _GENERATED_JOB_SOURCES[job]]
        else:
            candidates = [_member(root / _JCL_DIR, job, _JCL_SUFFIXES)]
        return {
            _inside(candidate, root, f"job {job}")
            for candidate in candidates
            if candidate is not None and candidate.is_file()
        }
    where = f"jobs.{job}"
    if not isinstance(entry, Mapping):
        raise ResultError(f"{where}: must be a step table mapping")
    paths: set[Path] = set()
    if entry.get("source") is not None:
        paths.add(_listed_path(entry["source"], root, f"{where}.source"))
    procs = entry.get("procs") or []
    if not isinstance(procs, list):
        raise ResultError(f"{where}.procs: must be a list of procedure sources")
    for index, proc in enumerate(procs):
        paths.add(_listed_path(proc, root, f"{where}.procs[{index}]"))
    steps = entry.get("steps") or []
    if not isinstance(steps, list):
        raise ResultError(f"{where}.steps: must be a list of steps")
    for index, step in enumerate(steps):
        paths |= _step_paths(job, index, step, root)
    return paths


def source_identity(
    scenario: Any,
    adapter_name: str,
    repo_root: str | PurePath = REPO_ROOT,
    *,
    jobs_file: str | PurePath = GNUCOBOL_JOBS_FILE,
) -> dict | None:
    """What a run of ``scenario`` executed, for result records and observed
    baseline manifests.

    gnucobol-batch runs the ``app`` tree: ``{tree: app, commit: <HEAD of
    repo_root>, units: {<repository-relative path>: <sha256>}}``, the units
    sorted. For ``inputs.program`` they are the program's sources (its COPY
    closure, and that of each app/cbl unit it CALLs, so CBSTM03A brings
    CBSTM03B). For ``inputs.job``, each job of the chain in turn, they come
    from its ``jobs.yaml`` step table: the job's source (``TRNRPT00``:
    ``app/cbl/CORPT00C.cbl``), its procedures (``REPROC``, ``TRANREPT``), the
    members its DDs read (``REPROCT``) and the units of its program steps;
    without a step table, the job's JCL alone. A scenario naming neither ran
    nothing here and has no units.

    unikix returns ``None``: its evidence writer records the identity of the
    UniKix copy it deployed (``tree: unikix-copy``, ``zip_sha256``, units)."""
    adapter_name = _adapter_name(adapter_name)
    if adapter_name == UNIKIX:
        return None
    root = Path(repo_root).resolve()
    inputs = getattr(scenario, "inputs", None)
    inputs = inputs if isinstance(inputs, Mapping) else {}
    paths: set[Path] = set()
    program = inputs.get("program")
    if isinstance(program, str):
        paths = _program_paths(program, root, required=False)
    else:
        jobs = _scenarios.job_names(scenario)
        table = _job_table(Path(jobs_file)) if jobs else None
        for job in jobs:
            paths |= _job_paths(job, table, root)
    units: dict[str, str] = {}
    for path in paths:
        try:
            relative = path.relative_to(root).as_posix()
        except ValueError:
            raise ResultError(f"{display_path(path)} lies outside the repository") from None
        units[relative] = sha256_file(path)
    return {"tree": "app", "commit": git_head(root), "units": dict(sorted(units.items()))}


def execution_fingerprint(scenario: Any, materialized: Mapping[str, bytes] | None, adapter_name: str) -> str:
    """The sha256 of everything that decides what a run of ``scenario``
    executed: the adapter name, the scenario's ``inputs``, and its
    preconditions with each materialized dataset (parameter files and
    ``DATEPARM`` included) as the sha256 of its bytes
    (``fixtures.materialized_digest``), its parameters and its faults.

    Parameters carry their effective ``clock`` and ``clock_mode``
    (``harness.scenarios.clock``/``clock_mode``), so an omitted value and its
    explicit default fingerprint alike and a change of the default clock is a
    change of what ran. An absent ``faults`` is the empty list. ``expected``,
    tags, gap ids and inventory ids are outside the fingerprint, so a change
    to them leaves a captured run re-comparable; any change to what executes
    makes it ``stale``."""
    adapter_name = _adapter_name(adapter_name)
    scenario_id = _scenario_id(scenario)
    preconditions = getattr(scenario, "preconditions", None)
    preconditions = preconditions if isinstance(preconditions, Mapping) else {}
    parameters = preconditions.get("parameters")
    parameters = dict(parameters) if isinstance(parameters, Mapping) else {}
    parameters["clock"] = _scenarios.clock(scenario)
    parameters["clock_mode"] = _scenarios.clock_mode(scenario)
    faults = preconditions.get("faults")
    inputs = getattr(scenario, "inputs", None)
    try:
        datasets = _fixtures.materialized_digest({} if materialized is None else materialized)
    except _fixtures.FixtureError as exc:
        raise ResultError(f"{scenario_id}: materialized datasets: {exc}") from None
    payload = {
        "adapter": adapter_name,
        "inputs": dict(inputs) if isinstance(inputs, Mapping) else {},
        "preconditions": {
            "datasets": datasets,
            "faults": list(faults) if isinstance(faults, list) else [],
            "parameters": parameters,
        },
    }
    return sha256_hex(dump_yaml(payload))


# --------------------------------------------------------------------------
# Persistence
# --------------------------------------------------------------------------


def _writable_folder(folder: Path) -> Path:
    """``folder`` for writing, refused when it or its parent is a symbolic
    link: records are written only into the real ``tests/reports`` tree."""
    for path in (folder.parent, folder):
        if path.is_symlink():
            raise ResultError(f"{display_path(path)} is a symbolic link; nothing is written through it")
    return folder


def write(outcome: Any, *, results_dir: str | PurePath = RESULTS_DIR) -> Path | None:
    """Write the result record of a gnucobol-batch ``outcome`` to
    ``<results_dir>/<scenario_id>.yaml`` as canonical YAML and return its path;
    an unchanged record leaves the file untouched.

    ``outcome`` carries ``adapter`` (the adapter's name, or the adapter),
    ``scenario_id`` and ``record`` (``build_record``). Nothing is written, and
    ``None`` returned, for any other adapter (a unikix run's record is its
    immutable evidence ``result.yaml``, and the record the status rules read
    is ``validation.yaml``), and for a scenario outside the closed id manifest
    or one gnucobol-batch cannot run, so the folder only ever holds records of
    the scenarios gnucobol-batch can run. A record that breaks the contract
    raises ``ResultError``."""
    adapter = getattr(outcome, "adapter", None)
    name = adapter if isinstance(adapter, str) else getattr(adapter, "name", None)
    if name != GNUCOBOL:
        return None
    scenario_id = getattr(outcome, "scenario_id", None)
    if not isinstance(scenario_id, str) or scenario_id not in _scenarios.ALL_IDS:
        return None
    scenario = _scenarios.get(scenario_id, missing_ok=True)
    if scenario is None or GNUCOBOL not in _scenarios.capable_adapters(scenario):
        return None
    record = getattr(outcome, "record", None)
    if record is None:
        raise ResultError(f"{scenario_id}: the gnucobol-batch outcome carries no result record")
    problems = record_problems(record)
    if isinstance(record, Mapping):
        if record.get("scenario_id") != scenario_id:
            problems.append(f"scenario_id: the record is for {record.get('scenario_id')!r}")
        if record.get("adapter") != GNUCOBOL:
            problems.append(f"adapter: the record is for {record.get('adapter')!r}")
    if problems:
        raise ResultError(f"{scenario_id}: result record: " + "; ".join(problems))
    folder = _writable_folder(Path(results_dir))
    path = folder / f"{scenario_id}.yaml"
    if path.is_symlink():
        raise ResultError(f"{display_path(path)} is a symbolic link; the record is not written through it")
    write_text(path, dump_yaml(dict(record)))
    return path


def _read_record(path: Path, scenario_id: str) -> dict | None:
    """The record at ``path``; ``None`` when there is none. A symbolic link
    counts as no record and is never followed."""
    if path.is_symlink() or not path.exists():
        return None
    if not path.is_file():
        raise ResultError(f"{display_path(path)} is not a result record file")
    try:
        record = load_yaml(path)
    except YAMLLoadError as exc:
        raise ResultError(str(exc)) from None
    problems = record_problems(record)
    if isinstance(record, Mapping) and record.get("scenario_id") != scenario_id:
        problems.append(f"scenario_id: {record.get('scenario_id')!r} is not the file's id {scenario_id}")
    if problems:
        raise ResultError(f"{display_path(path)}: " + "; ".join(problems))
    return dict(record)


def load(scenario_id: str, *, results_dir: str | PurePath = RESULTS_DIR) -> dict | None:
    """The gnucobol-batch record of ``scenario_id``, or ``None`` when it has
    none. Only ids of the closed manifest are looked up. A record file that
    breaks the contract raises ``ResultError``."""
    if not isinstance(scenario_id, str) or scenario_id not in _scenarios.ALL_IDS:
        return None
    return _read_record(Path(results_dir) / f"{scenario_id}.yaml", scenario_id)


def load_all(*, results_dir: str | PurePath = RESULTS_DIR) -> dict[str, dict]:
    """Every gnucobol-batch record, by scenario id in sorted order. Files that
    are not ``<manifest id>.yaml`` are not records (``scenarios.check_manifest``
    reports them); symbolic links are not followed."""
    folder = Path(results_dir)
    if folder.is_symlink() or not folder.is_dir():
        return {}
    records: dict[str, dict] = {}
    for path in sorted(folder.iterdir()):
        if path.suffix != ".yaml" or path.stem not in _scenarios.ALL_IDS:
            continue
        record = _read_record(path, path.stem)
        if record is not None:
            records[path.stem] = record
    return dict(sorted(records.items()))


def is_current(record: Any, scenario: Any, baseline: Any) -> bool:
    """Whether ``record`` still describes ``scenario`` and its ``baseline``
    as they stand: it is the scenario's record, its ``baseline_version`` is
    the baseline's version (``None`` when the scenario has no baseline) and
    its ``scenario_digest`` the scenario's digest. A record that is not
    current is ``stale`` and counts as ``implemented-unexecuted``."""
    if not isinstance(record, Mapping):
        return False
    try:
        scenario_id = _scenario_id(scenario)
        digest = _scenarios.digest(scenario)
    except HarnessError:
        return False
    version = baseline.version if baseline is not None else None
    return (
        record.get("scenario_id") == scenario_id
        and record.get("baseline_version") == version
        and record.get("scenario_digest") == digest
    )


def _runtime_value(value: Any) -> Any:
    """``value`` as YAML for the runtime files, which lie outside the
    deterministic projection: floats as their ``repr``, paths, dates and
    exceptions as text, bytes decoded with replacement, and any other object
    as its ``repr``."""
    if value is None or isinstance(value, (bool, int)):
        return value
    if isinstance(value, str):
        # A lone surrogate (an undecodable file name) cannot be written as YAML.
        return value.encode("utf-8", "backslashreplace").decode("utf-8")
    if isinstance(value, float):
        return repr(value)
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, PurePath):
        return _runtime_value(str(value))
    if isinstance(value, (_dt.date, _dt.time)):
        return value.isoformat()
    if isinstance(value, BaseException):
        return _runtime_value(f"{type(value).__name__}: {value}")
    if isinstance(value, (bytes, bytearray, memoryview)):
        return bytes(value).decode("utf-8", "replace")
    if isinstance(value, Mapping):
        return {_runtime_value(str(key)): _runtime_value(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_runtime_value(item) for item in value]
    if isinstance(value, (set, frozenset)):
        return sorted((_runtime_value(item) for item in value), key=repr)
    return _runtime_value(repr(value))


def _write_runtime_file(folder: Path, name: str, data: Any) -> Path:
    if not isinstance(data, Mapping):
        raise ResultError(f"runtime data for {name} must be a mapping, not {type(data).__name__}")
    try:
        text = dump_yaml(_runtime_value(data))
    except RecursionError:
        raise ResultError(f"runtime data for {name} nests too deeply or refers to itself") from None
    path = _writable_folder(folder) / name
    if path.is_symlink():
        raise ResultError(f"{display_path(path)} is a symbolic link; nothing is written through it")
    write_text(path, text)
    return path


def write_runtime(scenario_id: str, data: Mapping[str, Any], *, runtime_dir: str | PurePath = RUNTIME_DIR) -> Path:
    """Write ``<runtime_dir>/<scenario_id>.yaml``: what a run leaves outside
    its record, such as ``elapsed_s``, the absolute path of the working
    directory a failing scenario keeps, and exception and cleanup details.
    The folder is git-ignored and outside the deterministic projection."""
    if not isinstance(scenario_id, str) or not _SAFE_ID_RE.fullmatch(scenario_id):
        raise ResultError(f"{scenario_id!r} is not a scenario id")
    return _write_runtime_file(Path(runtime_dir), f"{scenario_id}.yaml", data)


def write_environment(env: Mapping[str, Any], *, runtime_dir: str | PurePath = RUNTIME_DIR) -> Path:
    """Write ``<runtime_dir>/environment.yaml``: the facts an adapter reports
    about its environment (``cobc --version``, the page-zero probe result,
    ``vm.mmap_min_addr``), read for the Environment section of
    ``coverage-report.md``."""
    return _write_runtime_file(Path(runtime_dir), ENVIRONMENT_FILE, env)


def load_environment(*, runtime_dir: str | PurePath = RUNTIME_DIR) -> dict | None:
    """The mapping ``write_environment`` wrote last, or ``None`` when there
    is none (a symbolic link is not followed)."""
    path = Path(runtime_dir) / ENVIRONMENT_FILE
    if path.is_symlink() or not path.is_file():
        return None
    try:
        document = load_yaml(path)
    except YAMLLoadError as exc:
        raise ResultError(str(exc)) from None
    if not isinstance(document, Mapping):
        raise ResultError(f"{display_path(path)}: must hold a mapping")
    return dict(document)
