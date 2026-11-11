"""BPE jobs and dataset services of the ``unikix`` adapter.

``BatchService`` runs CardDemo jobs on the test-owned BPE node and provides
every dataset service the adapter needs: fixture loads, post-run unloads,
``@CATALOG``, ``@AIX:<path>`` and the catalog reset. All of it is JCL, either
a member of the UniKix copy or a job this module renders from a
``commands.yaml`` template, and every command goes through
``env.CommandSet.run``, so nothing is issued outside ``commands.yaml``.

A job runs in three commands: ``bpe_translate`` (``mvstrans``), the
submitting entry (``bpe_submit``, or the dataset or catalog entry that reuses
its command) and ``bpe_sysout`` polled once per second until the job's
sysout shows it completed. Waiting for a job is application time: it has no
limit of its own and is bounded only by the scenario deadline, whose expiry
raises ``ScenarioTimeout``. Without a scenario deadline (session setup and
cleanup) a job is waited for ``DEFAULT_DEADLINE_S`` seconds at most, and a
job still running then is a missing capability (GAP-MIS-006).

The outcome of a job is read from its sysout files only: step return codes,
abends and the program ``DISPLAY`` lines. An application outcome is never
inferred from a command failure, and a sysout this module cannot read
completely (no step outcome, step names that are not those of
``resources.yaml``, a ``DISPLAY`` file that names no step) is
``AdapterUnavailable`` under GAP-MIS-006, never a guess.

Generated members are written only to ``$PR/jcl/jmvs``, staged and unloaded
records only to the files of the ``CDTEST.STAGE.<qualifier>`` and
``CDTEST.UNLOAD.<qualifier>`` datasets and non-VSAM preconditions only to
their ``$SEQFILES`` files: every path is checked to lie inside the deployment
root first. The dataset services run only while the region files are closed,
which the adapter guarantees, and never use ``CLOSEFIL``, ``OPENFIL`` or an
SDSF step for region control (``SDSF.cbl`` is a no-op, GAP-DOC-031).

Scenarios run serially (AAP 0.4.6), so one ``BatchService`` serves one
session from one thread. Python 3.11 standard library and PyYAML only.
"""

from __future__ import annotations

import contextlib
import datetime as _dt
import fnmatch
import functools
import math
import os
import re
import sys
import time
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

_TESTS_ROOT = str(Path(__file__).resolve().parents[2])
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from adapters.base import (  # noqa: E402
    AIX_PREFIX,
    CATALOG,
    DEFAULT_DEADLINE_S,
    AdapterUnavailable,
    MissingPrerequisite,
    ScenarioTimeout,
    format_catalog,
    format_fixed_records,
)
from adapters.unikix.env import CommandResult, CommandSet, UniKixEnvironment  # noqa: E402
from harness import FIXTURES_FILE, HarnessError, load_yaml  # noqa: E402

__all__ = [
    "CARD_WIDTH",
    "DEFAULT_GDG_LIMIT",
    "JOB_PREFIXES",
    "RESOURCES_FILE",
    "STAGING_PREFIX",
    "TEST_HLQ",
    "UNLOAD_PREFIX",
    "AixPathResource",
    "BatchOutcome",
    "BatchService",
    "CatalogEntry",
    "DatasetResource",
    "JobResource",
    "JobRun",
    "JobStep",
    "KeySpec",
    "Resources",
    "StepOutcome",
    "card_image",
    "parse_listing",
    "trnrpt00_jcl",
]

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

RESOURCES_FILE = Path(__file__).resolve().with_name("resources.yaml")

CARD_WIDTH = 80
TEST_HLQ = "CDTEST."
STAGING_PREFIX = "CDTEST.STAGE."
UNLOAD_PREFIX = "CDTEST.UNLOAD."
# LIMIT(5) of the copy's jcl/DEFGDGB and BPECAT.gdg_base.csv.
DEFAULT_GDG_LIMIT = 5

# Member-name prefixes of the generated jobs; the four digits that follow come
# from a per-session counter of each prefix.
JOB_PREFIXES = {
    "define": "CDTD",
    "load": "CDTL",
    "unload": "CDTU",
    "catalog": "CDTC",
    "reset": "CDTR",
}

_GAP_SUBMISSION = "GAP-MIS-006"  # BPE submission, completion and sysout
_GAP_QUIESCENCE = "GAP-MIS-004"  # quiescence: no unfinished BPE job
_GAP_DATASETS = "GAP-MIS-007"  # dataset load, unload and catalog listing

_KINDS = ("cluster", "nonvsam", "gdg")
# Where a non-VSAM dataset precondition can be written: datasets the BPE
# catalog import or define_staging catalogues. One that only a job of the
# application catalogues has no entry before that job runs.
_PRECATALOGUED = frozenset({"BPECAT.nonvsam.csv", "commands.yaml dataset_define_staging"})

_NAME_RE = re.compile(r"^[A-Z@#$][A-Z0-9@#$]{0,7}$")
_QUALIFIER_RE = re.compile(r"^[A-Z][A-Z0-9]{0,7}$")
_DSN_RE = re.compile(r"^[A-Z@#$][A-Z0-9@#$-]{0,7}(\.[A-Z@#$][A-Z0-9@#$-]{0,7})*$")
_DATE_RE = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
_SHELL_VAR_RE = re.compile(r"\$([A-Za-z_][A-Za-z0-9_]*)")
_EXEC_RE = re.compile(r"^//(?P<name>[A-Z@#$][A-Z0-9@#$]{0,7})\s+EXEC\s", re.IGNORECASE)
_SYSIN_RE = re.compile(r"^//SYSIN\s+DD\s+(?:\*|DATA)(?:\s|,|$)", re.IGNORECASE)
_JOB_CARD_RE = re.compile(r"^//(?P<name>[A-Z@#$][A-Z0-9@#$]{0,7})\s+JOB\b", re.IGNORECASE)
_TOKEN_RE = re.compile(r"[A-Z0-9@#$]+")

# IDCAMS statements reused from the copy's load members.
_DEFINE_CLUSTER_RE = re.compile(r"^\s*DEFINE\s+CLUSTER\b", re.IGNORECASE)
_DEFINE_AIX_RE = re.compile(r"^\s*DEFINE\s+(?:ALTERNATEINDEX|AIX)\b", re.IGNORECASE)
_DEFINE_PATH_RE = re.compile(r"^\s*DEFINE\s+PATH\b", re.IGNORECASE)
_BLDINDEX_RE = re.compile(r"^\s*BLDINDEX\b", re.IGNORECASE)

# The catalog vocabulary of @CATALOG (adapters.base.CATALOG_TYPES). LISTCAT
# also reports the DATA and INDEX components of clusters and alternate
# indexes; they are parts of those entries, not entries of their own, and
# gnucobol-batch lists none, so they are left out.
_COMPONENT_TYPES = frozenset({"DATA", "INDEX"})
_LISTED_TYPES = frozenset({"CLUSTER", "AIX", "PATH", "GDG", "NONVSAM"})
# IDCAMS DELETE entry type of each catalog type, and the order the reset
# deletes them in: generations before their GDG base, paths and alternate
# indexes before their cluster.
_DELETE_TYPES = {
    "GENERATION": "NONVSAM",
    "PATH": "PATH",
    "AIX": "ALTERNATEINDEX",
    "CLUSTER": "CLUSTER",
    "NONVSAM": "NONVSAM",
    "GDG": "GENERATIONDATAGROUP",
}
_DELETE_ORDER = tuple(_DELETE_TYPES)

# The record an unload file holds before its unload job runs. A file that
# still holds it afterwards was not written by the job, so its bytes are
# never taken for the dataset.
_SENTINEL_TEXT = b"CDTEST-UNLOAD-SENTINEL "
_SENTINEL_FILL = b"\xff"


# --------------------------------------------------------------------------
# resources.yaml
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class KeySpec:
    """A key as resources.yaml gives it: 0-based ``offset`` and ``length``."""

    offset: int
    length: int

    def of(self, record: bytes) -> bytes:
        return record[self.offset : self.offset + self.length]


@dataclass(frozen=True)
class DatasetResource:
    """One ``datasets`` entry of resources.yaml.

    ``define_from`` is ``(member, step)`` of the DEFINE CLUSTER statement and
    ``aix_from`` ``(member, steps)`` of the DEFINE ALTERNATEINDEX, DEFINE PATH
    and BLDINDEX statements, for clusters; ``path`` the ``$SEQFILES`` file of a
    non-VSAM dataset; ``limit`` the LIMIT of a GDG base."""

    logical: str
    kind: str
    dsn: str
    record_length: int
    qualifier: str
    key: KeySpec | None = None
    aix: tuple[str, ...] = ()
    define_from: tuple[str, str] | None = None
    aix_from: tuple[str, tuple[str, ...]] | None = None
    path: str | None = None
    catalogued_by: str | None = None
    limit: int | None = None

    @property
    def staging_dsn(self) -> str:
        return STAGING_PREFIX + self.qualifier

    @property
    def unload_dsn(self) -> str:
        return UNLOAD_PREFIX + self.qualifier


@dataclass(frozen=True)
class AixPathResource:
    """One ``aix_paths`` entry: ``dsn`` is the PATH an unload reads in
    alternate-key order, ``aix_dsn`` the alternate index loads rebuild."""

    name: str
    base: str
    dsn: str
    aix_dsn: str
    record_length: int
    qualifier: str
    key: KeySpec

    @property
    def unload_dsn(self) -> str:
        return UNLOAD_PREFIX + self.qualifier


@dataclass(frozen=True)
class JobStep:
    """One executed step of a job, named as ``messages`` names it."""

    name: str
    exec: str
    proc: str | None = None
    cond: str | None = None
    parm: str | None = None

    @property
    def program(self) -> str | None:
        """The program of ``EXEC PGM=<program>``, else ``None``."""
        kind, _, operand = self.exec.partition("=")
        if kind.strip().upper() != "PGM":
            return None
        return operand.strip() or None


@dataclass(frozen=True)
class JobResource:
    """One ``jobs`` entry. ``member`` is the copy member (``jcl/<NAME>``), or
    ``None`` for a job this module renders from ``template``."""

    name: str
    member: str | None
    card_name: str
    steps: tuple[JobStep, ...]
    programs: tuple[str, ...]
    generated: bool = False
    template: tuple[str, ...] = ()

    @property
    def member_name(self) -> str:
        """The member name under ``$PR/jcl/jmvs``."""
        return self.member.rsplit("/", 1)[-1] if self.member else self.name


@dataclass(frozen=True)
class Resources:
    """The parts of resources.yaml this module reads. Build it with
    ``Resources.load``; every lookup of a name the file does not map raises
    ``HarnessError``."""

    path: Path
    datasets: Mapping[str, DatasetResource]
    aix_paths: Mapping[str, AixPathResource]
    jobs: Mapping[str, JobResource]
    region_files: tuple[str, ...]
    tdq: str
    not_mapped: Mapping[str, str]

    @classmethod
    def load(cls, path: str | os.PathLike[str] | None = None) -> Resources:
        """resources.yaml, parsed once per path and checked against
        ``tests/fixtures/datasets.yaml``: every mapped logical dataset must be
        defined there with the same record length and primary key, and every
        alternate-index path with the same base and key. A file that cannot be
        read, is not valid or fails these checks raises ``HarnessError``."""
        return _load_resources(str(Path(path).resolve() if path else RESOURCES_FILE))

    def dataset(self, logical: str) -> DatasetResource:
        found = self.datasets.get(logical)
        if found is not None:
            return found
        if logical in self.not_mapped:
            raise HarnessError(f"{logical} has no UniKix counterpart: {self.not_mapped[logical]}")
        raise HarnessError(f"{logical!r} is not a logical dataset of {self.path.name}")

    def aix_path(self, name: str) -> AixPathResource:
        found = self.aix_paths.get(name)
        if found is None:
            raise HarnessError(f"{name!r} is not an alternate-index path of {self.path.name}")
        return found

    def job(self, name: str) -> JobResource:
        found = self.jobs.get(name)
        if found is None:
            raise HarnessError(f"{name!r} is not a job of {self.path.name}")
        return found

    def gdg_limit(self, base: str) -> int:
        """The LIMIT of the GDG base ``base``: its resources.yaml value, or
        ``DEFAULT_GDG_LIMIT`` for a base the file does not map."""
        for dataset in self.datasets.values():
            if dataset.kind == "gdg" and dataset.dsn == base and dataset.limit:
                return dataset.limit
        return DEFAULT_GDG_LIMIT


@functools.cache
def _load_resources(path: str) -> Resources:
    file = Path(path)
    try:
        data = load_yaml(file)
    except OSError as exc:
        raise HarnessError(f"{file}: cannot read: {exc.strerror or exc}") from None
    if not isinstance(data, Mapping) or data.get("schema_version") != 1:
        raise HarnessError(f"{file}: expected a mapping with schema_version: 1")
    problems: list[str] = []
    datasets = _parse_datasets(data.get("datasets"), problems)
    aix_paths = _parse_aix_paths(data.get("aix_paths"), problems)
    jobs = _parse_jobs(data.get("jobs"), problems)
    region_files = tuple(
        str(item.get("name"))
        for item in data.get("region_files") or ()
        if isinstance(item, Mapping)
    )
    tdq = data.get("tdq")
    tdq_name = str(tdq.get("name")) if isinstance(tdq, Mapping) and tdq.get("name") else ""
    not_mapped = data.get("not_mapped") or {}
    if not isinstance(not_mapped, Mapping):
        problems.append("not_mapped must map logical names to reasons")
        not_mapped = {}
    _check_references(datasets, aix_paths, not_mapped, problems)
    _check_against_fixtures(datasets, aix_paths, problems)
    if problems:
        raise HarnessError(f"{file}:\n  " + "\n  ".join(problems))
    return Resources(
        path=file,
        datasets=datasets,
        aix_paths=aix_paths,
        jobs=jobs,
        region_files=region_files,
        tdq=tdq_name,
        not_mapped={str(name): str(reason) for name, reason in not_mapped.items()},
    )


def _int(value: Any, what: str, problems: list[str], minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        problems.append(f"{what} must be an integer >= {minimum}, got {value!r}")
        return minimum
    return value


def _text(
    value: Any, what: str, problems: list[str], pattern: re.Pattern[str] | None = None
) -> str:
    if not isinstance(value, str) or not value or (pattern and not pattern.fullmatch(value)):
        problems.append(f"{what} is not valid: {value!r}")
        return ""
    return value


def _key(value: Any, what: str, problems: list[str]) -> KeySpec:
    if not isinstance(value, Mapping):
        problems.append(f"{what} must be {{offset, length}}, got {value!r}")
        return KeySpec(0, 1)
    return KeySpec(
        _int(value.get("offset"), f"{what}.offset", problems),
        _int(value.get("length"), f"{what}.length", problems, minimum=1),
    )


def _member_step(value: Any, what: str, problems: list[str], many: bool) -> Any:
    """``{member, step}`` as ``(member, step)``, or ``{member, steps}`` as
    ``(member, (step, ...))`` when ``many``."""
    if not isinstance(value, Mapping):
        problems.append(f"{what} must be a mapping, got {value!r}")
        return None
    member = _text(value.get("member"), f"{what}.member", problems)
    if member and not member.startswith("jcl/"):
        problems.append(f"{what}.member must be a jcl/ member of the copy, got {member!r}")
    if not many:
        return member, _text(value.get("step"), f"{what}.step", problems, _NAME_RE)
    steps = value.get("steps")
    if not isinstance(steps, list) or not steps:
        problems.append(f"{what}.steps must be a non-empty list")
        return member, ()
    return member, tuple(_text(step, f"{what}.steps", problems, _NAME_RE) for step in steps)


def _parse_datasets(raw: Any, problems: list[str]) -> dict[str, DatasetResource]:
    if not isinstance(raw, Mapping) or not raw:
        problems.append("datasets must be a non-empty mapping")
        return {}
    found: dict[str, DatasetResource] = {}
    for logical, entry in raw.items():
        where = f"datasets.{logical}"
        if not isinstance(entry, Mapping):
            problems.append(f"{where} must be a mapping")
            continue
        kind = entry.get("kind")
        if kind not in _KINDS:
            problems.append(f"{where}.kind must be one of {', '.join(_KINDS)}, got {kind!r}")
            continue
        common = {
            "logical": str(logical),
            "kind": kind,
            "dsn": _text(entry.get("dsn"), f"{where}.dsn", problems, _DSN_RE),
            "record_length": _int(
                entry.get("record_length"), f"{where}.record_length", problems, 1
            ),
            "qualifier": _text(
                entry.get("qualifier"), f"{where}.qualifier", problems, _QUALIFIER_RE
            ),
        }
        if kind == "cluster":
            aix = entry.get("aix") or []
            if not isinstance(aix, list):
                problems.append(f"{where}.aix must be a list")
                aix = []
            found[str(logical)] = DatasetResource(
                **common,
                key=_key(entry.get("key"), f"{where}.key", problems),
                aix=tuple(str(name) for name in aix),
                define_from=_member_step(
                    entry.get("define_statements_from"),
                    f"{where}.define_statements_from",
                    problems,
                    many=False,
                ),
                aix_from=_member_step(
                    entry.get("aix_statements_from"),
                    f"{where}.aix_statements_from",
                    problems,
                    many=True,
                )
                if aix
                else None,
            )
        elif kind == "nonvsam":
            found[str(logical)] = DatasetResource(
                **common,
                path=_text(entry.get("path"), f"{where}.path", problems),
                catalogued_by=_text(entry.get("catalogued_by"), f"{where}.catalogued_by", problems),
            )
        else:
            found[str(logical)] = DatasetResource(
                **common, limit=_int(entry.get("limit"), f"{where}.limit", problems, 1)
            )
    return found


def _parse_aix_paths(raw: Any, problems: list[str]) -> dict[str, AixPathResource]:
    if not isinstance(raw, Mapping):
        problems.append("aix_paths must be a mapping")
        return {}
    found: dict[str, AixPathResource] = {}
    for name, entry in raw.items():
        where = f"aix_paths.{name}"
        if not isinstance(entry, Mapping):
            problems.append(f"{where} must be a mapping")
            continue
        found[str(name)] = AixPathResource(
            name=str(name),
            base=_text(entry.get("base"), f"{where}.base", problems),
            dsn=_text(entry.get("dsn"), f"{where}.dsn", problems, _DSN_RE),
            aix_dsn=_text(entry.get("aix_dsn"), f"{where}.aix_dsn", problems, _DSN_RE),
            record_length=_int(entry.get("record_length"), f"{where}.record_length", problems, 1),
            qualifier=_text(entry.get("qualifier"), f"{where}.qualifier", problems, _QUALIFIER_RE),
            key=_key(entry.get("key"), f"{where}.key", problems),
        )
    return found


def _parse_jobs(raw: Any, problems: list[str]) -> dict[str, JobResource]:
    if not isinstance(raw, Mapping) or not raw:
        problems.append("jobs must be a non-empty mapping")
        return {}
    found: dict[str, JobResource] = {}
    for name, entry in raw.items():
        where = f"jobs.{name}"
        if not isinstance(entry, Mapping):
            problems.append(f"{where} must be a mapping")
            continue
        generated = entry.get("generated") is True
        member = entry.get("member")
        if generated:
            template = entry.get("template")
            if member is not None or not isinstance(template, list) or not template:
                problems.append(f"{where}: a generated job has member null and a template list")
                template = []
            records = tuple(str(record) for record in template)
        else:
            member = _text(member, f"{where}.member", problems)
            if member and not member.startswith("jcl/"):
                problems.append(f"{where}.member must be a jcl/ member, got {member!r}")
            records = ()
        steps: list[JobStep] = []
        for index, step in enumerate(entry.get("steps") or ()):
            if not isinstance(step, Mapping):
                problems.append(f"{where}.steps[{index}] must be a mapping")
                continue
            steps.append(
                JobStep(
                    name=_text(step.get("name"), f"{where}.steps[{index}].name", problems),
                    exec=_text(step.get("exec"), f"{where}.steps[{index}].exec", problems),
                    proc=step.get("proc"),
                    cond=step.get("cond"),
                    parm=step.get("parm"),
                )
            )
        if not steps:
            problems.append(f"{where}.steps must list the executed steps")
        found[str(name)] = JobResource(
            name=str(name),
            member=member or None,
            card_name=_text(entry.get("card_name"), f"{where}.card_name", problems, _NAME_RE),
            steps=tuple(steps),
            programs=tuple(str(program) for program in entry.get("programs") or ()),
            generated=generated,
            template=records,
        )
    return found


def _check_references(
    datasets: Mapping[str, DatasetResource],
    aix_paths: Mapping[str, AixPathResource],
    not_mapped: Mapping[str, Any],
    problems: list[str],
) -> None:
    """Qualifiers unique; every ``aix`` name an ``aix_paths`` entry of that
    base and back; no name both mapped and ``not_mapped``."""
    owners: dict[str, str] = {}
    for name, qualifier in [(d.logical, d.qualifier) for d in datasets.values()] + [
        (f"aix_paths.{p.name}", p.qualifier) for p in aix_paths.values()
    ]:
        if qualifier and qualifier in owners:
            problems.append(f"qualifier {qualifier} is used by both {owners[qualifier]} and {name}")
        owners[qualifier] = name
    for dataset in datasets.values():
        for name in dataset.aix:
            path = aix_paths.get(name)
            if path is None or path.base != dataset.logical:
                problems.append(
                    f"datasets.{dataset.logical}.aix names {name}, no path of that base"
                )
    for path in aix_paths.values():
        base = datasets.get(path.base)
        if base is None or base.kind != "cluster" or path.name not in base.aix:
            problems.append(f"aix_paths.{path.name}.base {path.base} does not list it in its aix")
        elif path.record_length != base.record_length:
            problems.append(f"aix_paths.{path.name}.record_length differs from its base's")
    for name in sorted(set(datasets) & set(not_mapped)):
        problems.append(f"{name} is both in datasets and in not_mapped")


def _check_against_fixtures(
    datasets: Mapping[str, DatasetResource],
    aix_paths: Mapping[str, AixPathResource],
    problems: list[str],
) -> None:
    """The mapping must describe the same records as the logical datasets
    the runner hands over, or a load or unload would cut them wrongly."""
    try:
        data = load_yaml(FIXTURES_FILE)
    except OSError as exc:
        problems.append(f"{FIXTURES_FILE.name} cannot be read: {exc.strerror or exc}")
        return
    entries = data.get("datasets") if isinstance(data, Mapping) else None
    if not isinstance(entries, list):
        problems.append(f"{FIXTURES_FILE.name} holds no datasets list")
        return
    fixtures = {entry.get("name"): entry for entry in entries if isinstance(entry, Mapping)}
    alternate_keys: dict[str, tuple[str, Any]] = {}
    for name, entry in fixtures.items():
        for alternate in entry.get("alternate_keys") or ():
            if isinstance(alternate, Mapping):
                alternate_keys[str(alternate.get("path"))] = (str(name), alternate)
    for dataset in datasets.values():
        fixture = fixtures.get(dataset.logical)
        if fixture is None:
            problems.append(f"datasets.{dataset.logical} is not defined in {FIXTURES_FILE.name}")
            continue
        if fixture.get("record_length") != dataset.record_length:
            problems.append(
                f"datasets.{dataset.logical}.record_length {dataset.record_length} differs from "
                f"{FIXTURES_FILE.name}'s {fixture.get('record_length')}"
            )
        if dataset.kind == "cluster":
            key = fixture.get("key") if isinstance(fixture.get("key"), Mapping) else {}
            if (key.get("offset"), key.get("length")) != (dataset.key.offset, dataset.key.length):
                problems.append(f"datasets.{dataset.logical}.key differs from {FIXTURES_FILE.name}")
    for path in aix_paths.values():
        base, alternate = alternate_keys.get(path.name, (None, {}))
        if base != path.base or (alternate.get("offset"), alternate.get("length")) != (
            path.key.offset,
            path.key.length,
        ):
            problems.append(
                f"aix_paths.{path.name} does not match the alternate key {FIXTURES_FILE.name} "
                f"gives that path"
            )


# --------------------------------------------------------------------------
# JCL text
# --------------------------------------------------------------------------


def card_image(lines: Iterable[str], where: str) -> str:
    """``lines`` as card images: trailing blanks removed, each right-padded
    with spaces to ``CARD_WIDTH`` and ended by ``\\n``. A line longer than
    ``CARD_WIDTH`` columns, or one that is not ASCII, raises
    ``HarnessError``."""
    cards: list[str] = []
    for number, line in enumerate(lines, 1):
        text = line.rstrip()
        if len(text) > CARD_WIDTH:
            raise HarnessError(
                f"{where}: line {number} has {len(text)} columns, more than {CARD_WIDTH}: {text!r}"
            )
        if not text.isascii():
            raise HarnessError(f"{where}: line {number} is not ASCII: {text!r}")
        cards.append(text.ljust(CARD_WIDTH) + "\n")
    return "".join(cards)


def _split_dates(start: str, end: str) -> tuple[str, str]:
    for value in (start, end):
        if not isinstance(value, str) or not _DATE_RE.fullmatch(value):
            raise HarnessError(f"a TRNRPT00 date must be YYYY-MM-DD, got {value!r}")
        try:
            _dt.date.fromisoformat(value)
        except ValueError:
            raise HarnessError(f"a TRNRPT00 date must be a valid date, got {value!r}") from None
    return start, end


def trnrpt00_jcl(start: str, end: str, resources: Resources | None = None) -> str:
    """The job ``CORPT00C`` writes to the TDQ ``JOBS`` for the report window
    ``start``..``end`` (``YYYY-MM-DD``): resources.yaml ``jobs.TRNRPT00.template``
    with ``{start}`` and ``{end}`` filled, one 80-byte card per record, the
    final ``/*EOF`` record included as ``CORPT00C`` writes it
    [app/cbl/CORPT00C.cbl:84-127,497-505]. Records are never dropped, so a
    blank record would stay a blank card."""
    start, end = _split_dates(start, end)
    job = (resources or Resources.load()).job("TRNRPT00")
    if not job.generated:
        raise HarnessError("resources.yaml jobs.TRNRPT00 must be a generated job")
    try:
        records = [record.format(start=start, end=end) for record in job.template]
    except (KeyError, IndexError, ValueError) as exc:
        raise HarnessError(f"jobs.TRNRPT00.template cannot be rendered: {exc!r}") from None
    return card_image(records, "jobs.TRNRPT00.template")


def _member_steps(text: str) -> list[tuple[str, list[str]]]:
    """The steps of a JCL member in order, each ``(name, sysin)`` with the
    in-stream ``//SYSIN DD *`` lines of the step, trailing blanks removed.
    In-stream data ends at ``/*`` or at the next ``//`` statement."""
    steps: list[tuple[str, list[str]]] = []
    current: tuple[str, list[str]] | None = None
    collecting = False
    for raw in text.splitlines():
        line = raw.rstrip()
        if collecting:
            if not line.startswith(("/*", "//")):
                current[1].append(line)  # type: ignore[index]
                continue
            collecting = False
            if line.startswith("/*"):
                continue
        match = _EXEC_RE.match(line)
        if match:
            current = (match.group("name").upper(), [])
            steps.append(current)
        elif current is not None and _SYSIN_RE.match(line):
            collecting = True
    return steps


def _statements(lines: Sequence[str]) -> list[list[str]]:
    """IDCAMS statements of in-stream ``lines``: a line ending in ``-`` or
    ``+`` continues the statement on the next line. Blank lines are
    skipped."""
    statements: list[list[str]] = []
    current: list[str] | None = None
    for line in lines:
        if not line.strip():
            continue
        current = [line] if current is None else current + [line]
        if not line.endswith(("-", "+")):
            statements.append(current)
            current = None
    if current is not None:
        statements.append(current)
    return statements


def _names(statement: Sequence[str], keyword: str, dsn: str) -> bool:
    """Whether ``statement`` holds ``<keyword>(<dsn>)``."""
    joined = " ".join(statement)
    return (
        re.search(rf"\b{keyword}\s*\(\s*{re.escape(dsn)}\s*\)", joined, re.IGNORECASE) is not None
    )


def _exec_steps(jcl: str) -> list[str]:
    """The ``EXEC`` step names of a JCL text, in order."""
    return [match.group("name").upper() for match in map(_EXEC_RE.match, jcl.splitlines()) if match]


def _job_card_name(jcl: str) -> str | None:
    for line in jcl.splitlines():
        match = _JOB_CARD_RE.match(line)
        if match:
            return match.group("name").upper()
    return None


# --------------------------------------------------------------------------
# Catalog
# --------------------------------------------------------------------------


@dataclass(frozen=True, order=True)
class CatalogEntry:
    """One entry of the catalog in the ``@CATALOG`` vocabulary: a GDG
    generation is ``(<base>, GENERATION, G<nnnn>V<nn>)``, every other entry
    ``(<name>, <type>, None)``."""

    name: str
    type: str
    generation: str | None = None

    @property
    def dsn(self) -> str:
        """The DSN IDCAMS deletes: ``<base>.<generation>`` for a generation."""
        return f"{self.name}.{self.generation}" if self.type == "GENERATION" else self.name

    @property
    def observation(self) -> tuple[str, str, str | None]:
        return (self.name, self.type, self.generation)

    def __str__(self) -> str:
        return f"{self.name} {self.type} {self.generation or '-'}"


def parse_listing(
    text: str, entry_re: re.Pattern[str], generation_re: re.Pattern[str]
) -> tuple[CatalogEntry, ...]:
    """The catalog entries of a LISTCAT listing, sorted and unique.

    ``entry_re`` reads one top-level entry per line (groups ``type`` and
    ``name``), ``generation_re`` the generation in a name (group
    ``generation``). DATA and INDEX components are left out, a NONVSAM entry
    named ``<base>.G<nnnn>V<nn>`` of a GDG base the listing holds is that
    base's generation, and ``CDTEST.*`` entries, the test staging datasets,
    are left out: they are not application state."""
    raw: list[tuple[str, str]] = []
    for match in entry_re.finditer(text):
        kind, name = match.group("type").upper(), match.group("name").upper()
        if kind in _COMPONENT_TYPES or name.startswith(TEST_HLQ):
            continue
        if kind not in _LISTED_TYPES:
            known = ", ".join(sorted(_LISTED_TYPES))
            raise HarnessError(f"LISTCAT entry {name} has the type {kind}, outside {known}")
        raw.append((kind, name))
    bases = {name for kind, name in raw if kind == "GDG"}
    entries: set[CatalogEntry] = set()
    for kind, name in raw:
        found = generation_re.search(name) if kind == "NONVSAM" else None
        if found is not None and name[: found.start()] in bases:
            entries.add(
                CatalogEntry(name[: found.start()], "GENERATION", found.group("generation"))
            )
        else:
            entries.add(CatalogEntry(name, kind))
    return tuple(sorted(entries))


def _catalog_bytes(entries: Iterable[CatalogEntry]) -> bytes:
    try:
        return format_catalog(entry.observation for entry in entries)
    except ValueError as exc:
        raise HarnessError(f"the catalog listing cannot be rendered as {CATALOG}: {exc}") from None


# --------------------------------------------------------------------------
# Job runs
# --------------------------------------------------------------------------


@dataclass
class StepOutcome:
    """One step BPE ran: its name as BPE reports it, every procedure
    qualifier included; ``rc``, or ``None`` with ``abend_code`` for an
    abend; the ``DISPLAY`` lines of a program step.

    A step inside a procedure keeps its dotted name even when the procedure
    has a single step (``STEP05R.PRC001``), the rule of resources.yaml
    ``jobs`` and of the ``gnucobol-batch`` jobs.yaml, so that both adapters
    write the same ``STEP`` lines for the same job."""

    name: str
    rc: int | None
    abend_code: str | None = None
    display: list[str] = field(default_factory=list)

    @property
    def line(self) -> str:
        """The step's line of ``messages``, as the ``gnucobol-batch`` job
        driver writes it."""
        if self.rc is None:
            return f"STEP {self.name} ABEND"
        return f"STEP {self.name} RC {self.rc:04d}"


@dataclass
class JobRun:
    """One submitted job. ``name`` is the scenario job name or the generated
    member, ``card_name`` the name on its JOB card, which BPE reports.
    ``sysout`` maps each sysout file, by its path below
    ``$PROJECT_ROOT/sysoutdir``, to its bytes, unchanged."""

    name: str
    member: str
    card_name: str
    entry_id: str
    job_number: str
    job: JobResource | None = None
    completed: bool = False
    steps: list[StepOutcome] = field(default_factory=list)
    sysout: dict[str, bytes] = field(default_factory=dict)
    start_date: str | None = None

    @property
    def label(self) -> str:
        return f"{self.card_name} (BPE job {self.job_number})"

    @property
    def abended(self) -> bool:
        return any(step.rc is None for step in self.steps)

    @property
    def messages(self) -> list[str]:
        lines: list[str] = []
        for step in self.steps:
            lines.append(step.line)
            lines.extend(step.display)
        return lines


@dataclass(frozen=True)
class BatchOutcome:
    """What ``run_jobs`` produced: ``return_code`` is the highest step code
    over every executed job, ``None`` when a step abended; ``messages`` the
    ``STEP`` lines and ``DISPLAY`` lines of every job in order, with no job
    header line; ``run_date`` the BPE start date of the first job
    (``YYYY-MM-DD``), or ``None`` when its sysout gives none."""

    return_code: int | None
    messages: list[str]
    runs: tuple[JobRun, ...]

    @classmethod
    def of(cls, runs: Sequence[JobRun]) -> BatchOutcome:
        steps = [step for run in runs for step in run.steps]
        if any(step.rc is None for step in steps):
            return_code: int | None = None
        else:
            return_code = max(step.rc for step in steps)  # type: ignore[type-var]
        return cls(return_code, [line for run in runs for line in run.messages], tuple(runs))

    @property
    def run_date(self) -> str | None:
        return self.runs[0].start_date if self.runs else None


def _finite(number: float) -> bool:
    """Whether the int or float ``number`` is a finite float: false for NaN,
    an infinity and an int beyond the float range."""
    try:
        return math.isfinite(number)
    except OverflowError:
        return False


class _Budget:
    """The time one service call may take.

    With a scenario ``deadline`` (a ``time.monotonic()`` value) every command
    is capped at the time left and the end of the wait for a job raises
    ``ScenarioTimeout``. Without one, commands are bounded by their own
    ``timeout_s`` only, and a job is waited for ``DEFAULT_DEADLINE_S`` seconds
    from the start of the call; one still running then is a missing
    capability, because no scenario deadline expired."""

    def __init__(self, deadline: float | None) -> None:
        if deadline is not None and (
            isinstance(deadline, bool)
            or not isinstance(deadline, (int, float))
            or not _finite(deadline)
        ):
            raise HarnessError(
                f"deadline must be a time.monotonic() value or None, got {deadline!r}"
            )
        self.deadline = None if deadline is None else float(deadline)
        self.wait_until = (
            self.deadline if self.deadline is not None else time.monotonic() + DEFAULT_DEADLINE_S
        )

    def command_left(self, what: str) -> float | None:
        if self.deadline is None:
            return None
        left = self.deadline - time.monotonic()
        if left <= 0:
            raise ScenarioTimeout(f"scenario deadline passed before {what}")
        return left

    def wait_left(self) -> float:
        return self.wait_until - time.monotonic()

    def expired(self, what: str) -> Exception:
        if self.deadline is not None:
            return ScenarioTimeout(f"scenario deadline passed: {what}")
        return AdapterUnavailable(f"{what} within {DEFAULT_DEADLINE_S}s", _GAP_SUBMISSION)


def _text_lines(data: bytes) -> list[str]:
    """The lines of a sysout file: split at ``\\n``, a trailing ``\\r``
    removed, the empty string after a final ``\\n`` dropped. Bytes are read
    as latin-1, so every byte stays one character."""
    lines = data.decode("latin-1").split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    return [line[:-1] if line.endswith("\r") else line for line in lines]


def _align(reported: Sequence[str], listed: Sequence[JobStep]) -> list[int] | None:
    """For each reported step name, in order, the index of the listed step
    it is; ``None`` when the names are not the listed names in their order
    (a step BPE did not run is simply absent)."""
    indexes: list[int] = []
    position = 0
    for name in reported:
        while position < len(listed) and listed[position].name != name:
            position += 1
        if position == len(listed):
            return None
        indexes.append(position)
        position += 1
    return indexes


def _owner(relative: str, steps: Sequence[StepOutcome]) -> int | None:
    """The step whose output the sysout file ``relative`` is: the step every
    component of whose dotted name stands as a token in the file's path, the
    most qualified when several do, the first of equals; the only step when
    the job ran one; else ``None``."""
    tokens = set(_TOKEN_RE.findall(relative.upper()))
    best: tuple[int, int] | None = None
    for index, step in enumerate(steps):
        parts = step.name.upper().split(".")
        if all(part in tokens for part in parts) and (best is None or len(parts) > best[1]):
            best = (index, len(parts))
    if best is not None:
        return best[0]
    return 0 if len(steps) == 1 else None


# --------------------------------------------------------------------------
# The service
# --------------------------------------------------------------------------


class BatchService:
    """BPE jobs and dataset services for one session of the ``unikix``
    adapter.

    ``env`` is the session's ``UniKixEnvironment`` once the deployment exists
    (or an object with the same ``vars`` and ``deployment_root``), ``commands``
    its ``CommandSet``. Every operation takes the scenario ``deadline`` as a
    ``time.monotonic()`` value, or ``None`` outside a scenario (see
    ``_Budget``). Construction runs nothing and writes nothing."""

    def __init__(
        self,
        env: UniKixEnvironment,
        commands: CommandSet,
        resources: Resources | None = None,
        *,
        poll_interval: float = 1.0,
    ) -> None:
        if isinstance(poll_interval, bool) or not isinstance(poll_interval, (int, float)):
            raise HarnessError(f"poll_interval must be a number of seconds, got {poll_interval!r}")
        if not _finite(poll_interval) or poll_interval <= 0:
            raise HarnessError(
                f"poll_interval must be a positive, finite number of seconds, got {poll_interval!r}"
            )
        self.env = env
        self.commands = commands
        self.resources = resources if resources is not None else Resources.load()
        self.poll_interval = float(poll_interval)
        self._counters = dict.fromkeys(JOB_PREFIXES.values(), 0)
        self._unfinished: dict[str, JobRun] = {}
        self._evidence: dict[str, bytes] = {}
        self._job_numbers: list[str] = []
        self._models: dict[str, list[tuple[str, list[str]]]] = {}
        self._staging_defined = False

    # ------------------------------------------------------------- paths

    def _var(self, name: str) -> str:
        value = self.env.vars.get(name)
        if not value:
            raise HarnessError(f"UniKix variable {name} is not set")
        return value

    @property
    def _root(self) -> Path:
        return Path(self.env.deployment_root).resolve()

    def _inside(self, path: Path) -> bool:
        try:
            Path(os.path.realpath(path)).relative_to(self._root)
        except ValueError:
            return False
        return True

    def _checked(self, path: Path, what: str) -> Path:
        """``path``, which must be absolute and resolve, symlinks followed,
        inside the deployment root (AAP 0.4.6); ``HarnessError`` if not."""
        if not path.is_absolute() or not self._inside(path):
            raise HarnessError(
                f"{what} {path} does not lie inside the deployment root {self._root}"
            )
        return path

    def _expand(self, template: str, *, for_format: bool = False) -> str:
        """``template`` with each ``$VAR`` replaced from the environment; with
        ``for_format`` the values have their braces doubled, so that a later
        ``str.format`` fills only the template's own placeholders."""

        def value(match: re.Match[str]) -> str:
            text = self._var(match.group(1))
            return text.replace("{", "{{").replace("}", "}}") if for_format else text

        return _SHELL_VAR_RE.sub(value, template)

    def _dataset_file(self, entry_id: str, dsn: str) -> Path:
        """Where the catalogued dataset ``dsn`` lands: the entry's
        ``parse.path``, bash variables first, then ``str.format``."""
        template = self._parse(entry_id, "path")
        try:
            path = Path(self._expand(template, for_format=True).format(dsn=dsn))
        except (KeyError, IndexError, ValueError) as exc:
            raise HarnessError(f"{entry_id}.parse.path cannot be rendered: {exc!r}") from None
        return self._checked(path, f"the file of {dsn}")

    def _write_file(self, path: Path, data: bytes) -> None:
        """Replace ``path`` with ``data`` atomically, inside the deployment root."""
        self._checked(path, "the file")
        if not path.parent.is_dir():
            raise HarnessError(f"{path.parent} does not exist: the deployment is incomplete")
        temporary = path.with_name(f".{path.name}.{os.getpid()}.{os.urandom(4).hex()}.tmp")
        try:
            with open(temporary, "xb") as handle:
                handle.write(data)
            os.replace(temporary, path)
        except BaseException:
            with contextlib.suppress(FileNotFoundError):
                os.unlink(temporary)
            raise

    # ---------------------------------------------------------- commands

    def _run(self, entry_id: str, budget: _Budget, **values: str | int) -> CommandResult:
        return self.commands.run(
            entry_id, self.env, deadline_left=budget.command_left(entry_id), **values
        )

    def _parse(self, entry_id: str, key: str) -> str:
        value = self.commands.entry(entry_id).parse.get(key)
        if not value:
            raise HarnessError(f"commands.yaml {entry_id} has no parse.{key}")
        return value

    def _regex(self, entry_id: str, key: str, **values: str) -> re.Pattern[str]:
        text = self._parse(entry_id, key)
        try:
            return re.compile(text.format(**values) if values else text)
        except (KeyError, IndexError, ValueError, re.error) as exc:
            raise HarnessError(
                f"commands.yaml {entry_id}.parse.{key} is not usable: {exc}"
            ) from None

    def _glob(self, entry_id: str, key: str, relative: str) -> bool:
        return fnmatch.fnmatchcase(relative, self._parse(entry_id, key))

    def _template(self, entry_id: str, key: str) -> str:
        value = self.commands.entry(entry_id).template.get(key)
        if not value:
            raise HarnessError(f"commands.yaml {entry_id} has no template.{key}")
        return value

    def _fragments(self, entry_id: str, key: str, items: Iterable[Mapping[str, Any]]) -> str:
        """The ``key`` fragment rendered once per item, final newlines
        removed, joined into one slot value."""
        template = self._template(entry_id, key)
        try:
            return "\n".join(template.format_map(item).rstrip("\n") for item in items)
        except (KeyError, IndexError, ValueError) as exc:
            raise HarnessError(f"{entry_id}.template.{key} cannot be rendered: {exc!r}") from None

    def _render(self, entry_id: str, values: Mapping[str, Any]) -> str:
        """The entry's ``job`` template as card images. Each slot stands
        alone on its line, so lines left empty by an empty slot are dropped."""
        try:
            text = self._template(entry_id, "job").format_map(values)
        except (KeyError, IndexError, ValueError) as exc:
            raise HarnessError(f"{entry_id}.template.job cannot be rendered: {exc!r}") from None
        return card_image(
            (line for line in text.split("\n") if line.strip()), f"{entry_id}.template.job"
        )

    # -------------------------------------------------------------- jobs

    def next_member(self, kind: str) -> str:
        """The next generated member name of ``kind`` (a ``JOB_PREFIXES``
        key): its prefix and a four-digit per-session counter. A full UniKix
        run submits a few thousand generated jobs, so the counter wraps from
        9999 to 0001; every earlier job of the name has completed or been
        cancelled by then, because jobs run one at a time."""
        prefix = JOB_PREFIXES.get(kind)
        if prefix is None:
            raise HarnessError(f"generated job kind must be one of {', '.join(JOB_PREFIXES)}")
        self._counters[prefix] = self._counters[prefix] % 9999 + 1
        return f"{prefix}{self._counters[prefix]:04d}"

    def _write_member(self, member: str, jcl_text: str) -> None:
        """Write a generated member to ``$PR/jcl/jmvs/<member>``; every line
        must already be at most ``CARD_WIDTH`` columns of ASCII."""
        if not _NAME_RE.fullmatch(member):
            raise HarnessError(f"member name {member!r} is not a JCL member name")
        card_image(jcl_text.splitlines(), f"member {member}")
        directory = Path(self._var("PR")) / "jcl" / "jmvs"
        data = jcl_text.encode("ascii")
        self._write_file(directory / member, data)
        self._evidence[f"jcl/{member}"] = data

    def submit(
        self,
        job_name: str,
        jcl_text: str | None = None,
        *,
        entry_id: str = "bpe_submit",
        deadline: float | None = None,
    ) -> JobRun:
        """Submit a job and return its ``JobRun`` with the BPE job number.

        ``job_name`` is a ``resources.yaml`` job, run from its copy member, or
        a member this module generated, whose ``jcl_text`` is written to
        ``$PR/jcl/jmvs`` first (``TRNRPT00`` is both). The member is translated
        with ``bpe_translate`` and submitted with ``entry_id``, the
        ``bpe_submit`` command or a dataset or catalog entry that reuses it.
        The job number is read with that entry's ``parse.job_number``; none
        found is ``AdapterUnavailable`` (GAP-MIS-006)."""
        return self._submit(job_name, jcl_text, entry_id, _Budget(deadline))

    def _submit(
        self, job_name: str, jcl_text: str | None, entry_id: str, budget: _Budget
    ) -> JobRun:
        job = self.resources.jobs.get(job_name)
        if jcl_text is None:
            if job is None or job.generated:
                raise HarnessError(f"{job_name} is not a copy member job of resources.yaml")
            member, card_name = job.member_name, job.card_name
        else:
            if job is not None and not job.generated:
                raise HarnessError(
                    f"{job_name} runs from its copy member, which is never rewritten"
                )
            member = job_name
            card_name = job.card_name if job is not None else (_job_card_name(jcl_text) or member)
            self._write_member(member, jcl_text)
        self._run("bpe_translate", budget, job=member)
        result = self._run(entry_id, budget, job=member)
        match = self._regex(entry_id, "job_number").search(result.stdout)
        number = match.groupdict().get("job_number") if match else None
        if not number:
            raise AdapterUnavailable(
                f"job number not found in the output of {entry_id} for member {member}",
                _GAP_SUBMISSION,
            )
        run = JobRun(job_name, member, card_name, entry_id, str(int(number)), job=job)
        self._unfinished[run.job_number] = run
        self._job_numbers.append(run.job_number)
        return run

    def wait(self, run: JobRun, deadline: float | None = None) -> JobRun:
        """Poll ``bpe_sysout`` once per ``poll_interval`` until the sysout of
        ``run`` shows it completed, then read its outcome into ``run``.

        The steps come from ``step_rc`` and ``step_abend`` matches in the
        sysout files other than the program ``DISPLAY`` files, in file and
        line order; an abend reported on the line of a return code wins. A
        job of resources.yaml must report its listed step names in their
        order (a step BPE did not run is absent), and its program steps get
        the lines of the ``display_files`` that name them. Every sysout file
        goes to the evidence unchanged, also when the wait ends unfinished."""
        return self._wait(run, _Budget(deadline))

    def _wait(self, run: JobRun, budget: _Budget) -> JobRun:
        files: dict[str, bytes] = {}
        try:
            while True:
                files = self._job_files(run, budget)
                if files and self._completed(files):
                    break
                left = budget.wait_left()
                if left <= 0:
                    raise budget.expired(f"job {run.label} did not complete")
                time.sleep(min(self.poll_interval, left))
        finally:
            self._keep_evidence(run, files)
        run.sysout, run.completed = files, True
        self._unfinished.pop(run.job_number, None)
        run.steps = self._parse_steps(files)
        run.start_date = self._start_date(files)
        if not run.steps:
            raise AdapterUnavailable(
                f"job {run.label} completed, but its sysout shows no step outcome", _GAP_SUBMISSION
            )
        if run.job is not None:
            self._attach_display(run, run.job)
        return run

    def _sysout_dir(self) -> Path:
        return self._checked(Path(self._var("PROJECT_ROOT")) / "sysoutdir", "the sysout directory")

    def _job_files(self, run: JobRun, budget: _Budget) -> dict[str, bytes]:
        """The sysout files of ``run`` by path below the sysout directory:
        every regular file of each listed entry ``parse.job_dir`` matches, a
        directory walked without following links."""
        listing = self._run("bpe_sysout", budget).stdout.replace("\r", "")
        pattern = self._regex("bpe_sysout", "job_dir", job_number=run.job_number)
        root = self._sysout_dir()
        files: dict[str, bytes] = {}
        for match in pattern.finditer(listing):
            entry = (match.groupdict().get("entry") or "").strip()
            if not entry or entry in (".", "..") or "/" in entry:
                continue
            top = root / entry
            if top.is_symlink() or not self._inside(top):
                continue
            if top.is_file():
                files[entry] = top.read_bytes()
            elif top.is_dir():
                for directory, subdirectories, names in os.walk(top):
                    subdirectories.sort()
                    for name in sorted(names):
                        path = Path(directory) / name
                        if path.is_file() and not path.is_symlink():
                            files[path.relative_to(root).as_posix()] = path.read_bytes()
        return dict(sorted(files.items()))

    def _completed(self, files: Mapping[str, bytes]) -> bool:
        pattern = self._regex("bpe_sysout", "completed")
        return any(pattern.search(data.decode("latin-1")) for data in files.values())

    def _is_display(self, relative: str) -> bool:
        return self._glob("bpe_sysout", "display_files", relative)

    def _parse_steps(self, files: Mapping[str, bytes]) -> list[StepOutcome]:
        rc_re = self._regex("bpe_sysout", "step_rc")
        abend_re = self._regex("bpe_sysout", "step_abend")
        found: list[tuple[int, int, int, str, str, str]] = []
        for index, (relative, data) in enumerate(files.items()):
            if self._is_display(relative):
                continue
            text = data.decode("latin-1")
            for kind, pattern, group in (("rc", rc_re, "rc"), ("abend", abend_re, "code")):
                for match in pattern.finditer(text):
                    groups = match.groupdict()
                    step, value = groups.get("step"), groups.get(group)
                    if not step or value is None:
                        raise HarnessError(
                            f"commands.yaml bpe_sysout.parse.step_{kind} needs the groups step "
                            f"and {group}"
                        )
                    line = text.count("\n", 0, match.start())
                    found.append((index, line, match.start(), kind, step.upper(), value.upper()))
        abended = {
            (index, line, step) for index, line, _, kind, step, _ in found if kind == "abend"
        }
        steps: list[StepOutcome] = []
        for index, line, _, kind, step, value in sorted(found):
            if kind == "abend":
                steps.append(StepOutcome(step, None, value))
            elif (index, line, step) not in abended:
                steps.append(StepOutcome(step, int(value)))
        return steps

    def _start_date(self, files: Mapping[str, bytes]) -> str | None:
        """The BPE job start date as ``YYYY-MM-DD``, or ``None``."""
        pattern = self._regex("bpe_sysout", "start_date")
        for data in files.values():
            match = pattern.search(data.decode("latin-1"))
            if match and match.groupdict().get("date"):
                text = re.sub(r"[/.]", "-", match.group("date"))
                try:
                    return _dt.date.fromisoformat(text).isoformat()
                except ValueError:
                    continue
        return None

    def _keep_evidence(self, run: JobRun, files: Mapping[str, bytes]) -> None:
        for relative, data in files.items():
            self._evidence[f"{run.job_number}/{relative}"] = data

    def take_evidence(self) -> dict[str, bytes]:
        """The evidence since the last call, keyed as ``evidence.Capture.sysout``
        names it (relative to the evidence's ``sysout/``): every sysout file of
        each job waited for as ``<job_number>/<file>`` and every generated
        member as ``jcl/<member>``, bytes unchanged. The store is then
        emptied, so each scenario takes its own."""
        evidence, self._evidence = self._evidence, {}
        return evidence

    def take_job_numbers(self) -> list[str]:
        """The BPE job number of each job submitted since the last call, in
        submission order (``evidence.Capture.job_numbers``)."""
        numbers, self._job_numbers = self._job_numbers, []
        return numbers

    def _attach_display(self, run: JobRun, job: JobResource) -> None:
        """Check the reported steps of a resources.yaml job and give each of
        its program steps the ``DISPLAY`` lines of the files that name it.
        Lines of a file that belongs to a utility step (a SORT's SYSOUT) are
        not ``DISPLAY`` output and are left out."""
        reported = [step.name for step in run.steps]
        indexes = _align(reported, job.steps)
        if indexes is None:
            raise AdapterUnavailable(
                f"job {run.label} reported the steps {', '.join(reported)}, which are not the "
                f"steps {', '.join(step.name for step in job.steps)} of resources.yaml in order",
                _GAP_SUBMISSION,
            )
        programs = {
            position
            for position, listed in enumerate(indexes)
            if job.steps[listed].program in job.programs
        }
        if not programs:
            return
        for relative, data in run.sysout.items():
            if not self._is_display(relative):
                continue
            owner = _owner(relative, run.steps)
            if owner is None:
                raise AdapterUnavailable(
                    f"job {run.label}: the sysout file {relative} names none of its steps, so its "
                    f"DISPLAY lines cannot be given to a step",
                    _GAP_SUBMISSION,
                )
            if owner in programs:
                run.steps[owner].display.extend(_text_lines(data))

    def _confirm(self, run: JobRun, jcl_text: str, what: str, gap: str) -> None:
        """A generated job succeeded only when every step of its JCL ran
        (a ``PROC`` step as any step it qualifies) and none abended or ended
        above RC 4."""
        names = [step.name for step in run.steps]
        missing = [
            step
            for step in _exec_steps(jcl_text)
            if not any(name == step or name.startswith(step + ".") for name in names)
        ]
        failed = [step.line for step in run.steps if step.rc is None or step.rc > 4]
        if missing or failed:
            details = failed + [f"STEP {step} not reported" for step in missing]
            raise AdapterUnavailable(f"{what} failed in job {run.label}: {'; '.join(details)}", gap)

    def _run_generated(
        self,
        kind: str,
        entry_id: str,
        values: Mapping[str, Any],
        what: str,
        budget: _Budget,
        *,
        member: str | None = None,
        submit_entry: str | None = None,
    ) -> JobRun:
        """Render ``entry_id``'s template as a new member, submit it through
        the entry's own command (or ``submit_entry``'s, for a template whose
        entry has no command of its own), wait for it and confirm it
        succeeded; a failure is ``AdapterUnavailable`` with ``entry_id``'s gap."""
        member = member or self.next_member(kind)
        jcl_text = self._render(entry_id, {**values, "job": member})
        run = self._submit(member, jcl_text, submit_entry or entry_id, budget)
        self._wait(run, budget)
        self._confirm(run, jcl_text, what, self.commands.entry(entry_id).gap)
        return run

    def run_jobs(
        self,
        jobs: str | Sequence[str],
        deadline: float | None = None,
        *,
        dateparm: str | None = None,
    ) -> BatchOutcome:
        """Run a scenario's ``inputs.job``: one job, or an ordered list (a
        chain) submitted each after the previous completed, stopping after
        the first job in which a step abended. ``TRNRPT00`` is rendered with
        ``trnrpt00_jcl`` from ``dateparm`` (``preconditions.parameters.DATEPARM``,
        ``"YYYY-MM-DD YYYY-MM-DD"``)."""
        if isinstance(jobs, str):
            names = [jobs]
        elif isinstance(jobs, Sequence):
            names = list(jobs)
        else:  # not iterable, or unordered like a set: it gives no chain order
            names = []
        if not names or not all(isinstance(name, str) for name in names):
            raise HarnessError(f"inputs.job must name one job or a list of jobs, got {jobs!r}")
        budget = _Budget(deadline)
        runs: list[JobRun] = []
        for name in names:
            job = self.resources.job(name)
            jcl_text = None
            if job.generated:
                parts = dateparm.split() if isinstance(dateparm, str) else []
                if len(parts) != 2:
                    raise HarnessError(
                        f"{name} needs preconditions.parameters.DATEPARM as "
                        f"'YYYY-MM-DD YYYY-MM-DD', got {dateparm!r}"
                    )
                jcl_text = trnrpt00_jcl(parts[0], parts[1], self.resources)
            run = self._submit(name, jcl_text, "bpe_submit", budget)
            runs.append(self._wait(run, budget))
            if run.abended:
                break
        return BatchOutcome.of(runs)

    def cancel_unfinished(self) -> None:
        """Cancel every submitted job not seen completed (``bpe_cancel``),
        then confirm that BPE lists no unfinished job. It runs after a
        scenario's deadline may have passed, so its commands are bounded by
        their own ``timeout_s`` only."""
        for number in list(self._unfinished):
            self.commands.run("bpe_cancel", self.env, job_number=number)
            self._unfinished.pop(number, None)
        self.confirm_no_active_jobs()

    def confirm_no_active_jobs(self) -> None:
        """Half of the quiescence check: ``bpe_active_jobs`` must list no
        unfinished job, else ``AdapterUnavailable`` (GAP-MIS-004)."""
        output = self.commands.run("bpe_active_jobs", self.env).stdout
        listed = [
            f"{match.groupdict().get('name') or '?'} ({match.group('job_number')})"
            for match in self._regex("bpe_active_jobs", "job").finditer(output)
        ]
        if listed:
            raise AdapterUnavailable(
                f"BPE still lists unfinished jobs: {', '.join(listed)}", _GAP_QUIESCENCE
            )

    # ---------------------------------------------------------- datasets

    def _staging_datasets(self) -> list[tuple[str, int]]:
        """``(dsn, lrecl)`` of every dataset ``define_staging`` catalogues: the
        staging dataset of each cluster and GDG, the unload dataset of each
        dataset and alternate-index path, and each non-VSAM dataset no job or
        data file creates (``AWS.M2.CARDDEMO.DATEPARM``, GAP-MIS-001)."""
        found: dict[str, int] = {}
        for dataset in self.resources.datasets.values():
            if dataset.kind in ("cluster", "gdg"):
                found[dataset.staging_dsn] = dataset.record_length
            found[dataset.unload_dsn] = dataset.record_length
            if (
                dataset.kind == "nonvsam"
                and dataset.catalogued_by == "commands.yaml dataset_define_staging"
            ):
                found[dataset.dsn] = dataset.record_length
        for path in self.resources.aix_paths.values():
            found[path.unload_dsn] = path.record_length
        return sorted(found.items())

    def define_staging(self, *, deadline: float | None = None) -> None:
        """Catalogue the test staging and unload datasets once after
        deployment (``dataset_define_staging``), then check that each landed
        as a file where ``parse.path`` says. No file may be there before the
        job, so the check shows the path template is right."""
        if self._staging_defined:
            return
        budget = _Budget(deadline)
        datasets = self._staging_datasets()
        files = {dsn: self._dataset_file("dataset_define_staging", dsn) for dsn, _ in datasets}
        present = [dsn for dsn, path in files.items() if os.path.lexists(path)]
        if present:
            raise HarnessError(
                f"the staging datasets are already defined in this deployment: {', '.join(present)}"
            )
        statements = self._fragments(
            "dataset_define_staging",
            "dd",
            (
                {"ddname": f"DD{number:04d}", "dsn": dsn, "lrecl": lrecl}
                for number, (dsn, lrecl) in enumerate(datasets, 1)
            ),
        )
        self._run_generated(
            "define",
            "dataset_define_staging",
            {"dd_statements": statements},
            "the definition of the test datasets",
            budget,
        )
        missing = [f"{dsn} at {path}" for dsn, path in files.items() if not path.is_file()]
        if missing:
            raise AdapterUnavailable(
                f"catalogued datasets have no file where dataset_define_staging.parse.path "
                f"says: {', '.join(missing)}",
                _GAP_DATASETS,
            )
        self._staging_defined = True

    @staticmethod
    def _records(logical: str, data: bytes, record_length: int) -> list[bytes]:
        if not isinstance(data, (bytes, bytearray)):
            raise HarnessError(f"{logical} data must be bytes, got {type(data).__name__}")
        if len(data) % record_length:
            raise HarnessError(
                f"{logical} holds {len(data)} bytes, not a multiple of its record length "
                f"{record_length}"
            )
        return [
            bytes(data[start : start + record_length])
            for start in range(0, len(data), record_length)
        ]

    def _staging_file(self, dsn: str) -> Path:
        path = self._dataset_file("dataset_define_staging", dsn)
        if not path.is_file():
            raise HarnessError(f"{dsn} has no file at {path}: define_staging has not run")
        return path

    def write_sequential(self, logical: str, data: bytes) -> None:
        """Write a non-VSAM precondition as exact fixed-length records, without
        terminators, to the dataset's ``$SEQFILES`` file, the physical form
        of the zip's own ``data/seqfiles``."""
        dataset = self.resources.dataset(logical)
        if dataset.kind != "nonvsam":
            raise HarnessError(f"{logical} is a {dataset.kind}, not a non-VSAM dataset")
        self._records(logical, data, dataset.record_length)
        if dataset.catalogued_by not in _PRECATALOGUED:
            raise MissingPrerequisite(
                f"{logical} ({dataset.dsn}) is catalogued only by {dataset.catalogued_by}, so no "
                f"precondition can provide it before that job runs",
                _GAP_DATASETS,
            )
        path = self._checked(Path(self._expand(dataset.path or "")), f"the file of {dataset.dsn}")
        self._write_file(path, bytes(data))

    def _cluster(self, logical: str) -> DatasetResource:
        dataset = self.resources.dataset(logical)
        if dataset.kind != "cluster":
            raise HarnessError(f"{logical} is a {dataset.kind}, not a cluster")
        return dataset

    def _model(self, member: str) -> list[tuple[str, list[str]]]:
        """The steps of the copy member ``jcl/<NAME>`` as deployed to
        ``$PR/jcl/jmvs``, read once per session."""
        if member not in self._models:
            path = self._checked(
                Path(self._var("PR")) / "jcl" / "jmvs" / member.rsplit("/", 1)[-1], "the member"
            )
            try:
                text = path.read_bytes().decode("latin-1")
            except OSError as exc:
                raise HarnessError(
                    f"cannot read the load model {path}: {exc.strerror or exc}"
                ) from None
            self._models[member] = _member_steps(text)
        return self._models[member]

    def _step_statements(self, member: str, step: str) -> list[list[str]]:
        for name, sysin in self._model(member):
            if name == step:
                return _statements(sysin)
        raise HarnessError(f"the load model {member} has no step {step}")

    def _define_statements(self, dataset: DatasetResource) -> str:
        """The DEFINE CLUSTER statement of the cluster's load model, verbatim.
        Other statements of the step (the DELETE and SET MAXCC of DUSRSECJ
        STEP02 and CREASTMT DELDEF01) are not reused."""
        member, step = dataset.define_from  # type: ignore[misc]
        found = [
            statement
            for statement in self._step_statements(member, step)
            if _DEFINE_CLUSTER_RE.match(statement[0])
        ]
        if len(found) != 1 or not _names(found[0], "NAME", dataset.dsn):
            raise HarnessError(
                f"{member} {step} must hold exactly one DEFINE CLUSTER of {dataset.dsn}, "
                f"found {len(found)}"
            )
        return "\n".join(found[0])

    def _aix_statements(self, dataset: DatasetResource, build: bool) -> str:
        """The DEFINE ALTERNATEINDEX and DEFINE PATH statements, and with
        ``build`` the BLDINDEX statements, of the cluster's alternate-index
        model steps, verbatim and in their order."""
        member, steps = dataset.aix_from  # type: ignore[misc]
        kinds = [_DEFINE_AIX_RE, _DEFINE_PATH_RE] + ([_BLDINDEX_RE] if build else [])
        found = [
            statement
            for step in steps
            for statement in self._step_statements(member, step)
            if any(kind.match(statement[0]) for kind in kinds)
        ]
        for name in dataset.aix:
            path = self.resources.aix_path(name)
            checks = [
                (_DEFINE_AIX_RE, "NAME", path.aix_dsn),
                (_DEFINE_PATH_RE, "NAME", path.dsn),
            ] + ([(_BLDINDEX_RE, "OUTDATASET", path.aix_dsn)] if build else [])
            for kind, keyword, dsn in checks:
                if not any(kind.match(s[0]) and _names(s, keyword, dsn) for s in found):
                    raise HarnessError(
                        f"{member} {', '.join(steps)} hold no {keyword}({dsn}) statement"
                    )
        return "\n".join(line for statement in found for line in statement)

    def _cluster_values(self, dataset: DatasetResource, build: bool) -> dict[str, str]:
        """The slot values of ``dataset_load`` (``build``) and
        ``dataset_empty``. The copy's SDSF steps are never part of them."""
        entry_id = "dataset_load" if build else "dataset_empty"
        aixes = [self.resources.aix_path(name) for name in dataset.aix]
        aix_step = ""
        if aixes:
            aix_step = self._fragments(
                entry_id, "aix_step", [{"aix_statements": self._aix_statements(dataset, build)}]
            )
        return {
            "cluster_dsn": dataset.dsn,
            "qualifier": dataset.qualifier,
            "define_statements": self._define_statements(dataset),
            "aix_delete_statements": self._fragments(
                entry_id, "aix_delete", [{"aix_dsn": path.aix_dsn} for path in aixes]
            ),
            "aix_step": aix_step,
        }

    def load_dataset(self, logical: str, data: bytes, *, deadline: float | None = None) -> None:
        """Delete, define and load the cluster ``logical`` with ``data``
        (``dataset_load``): the records are staged in ``CDTEST.STAGE.<q>``,
        the DEFINE statements come verbatim from the cluster's load model, and
        a cluster with alternate indexes gets its DEFINE ALTERNATEINDEX,
        DEFINE PATH and BLDINDEX statements. A step above RC 4 after the
        MAXCC reset, or an abend, is ``AdapterUnavailable`` (GAP-MIS-007).

        No records means an empty cluster (``empty_cluster``): BLDINDEX
        cannot run over an empty base. REPRO into a KSDS needs ascending
        unique primary keys, the order ``harness.fixtures`` builds keyed
        datasets in; any other order raises ``HarnessError``."""
        dataset = self._cluster(logical)
        records = self._records(logical, data, dataset.record_length)
        if not records:
            self.empty_cluster(logical, deadline=deadline)
            return
        key = dataset.key or KeySpec(0, dataset.record_length)
        for number in range(1, len(records)):
            if key.of(records[number]) <= key.of(records[number - 1]):
                raise HarnessError(
                    f"{logical} record {number + 1} breaks the ascending unique primary-key order "
                    f"that REPRO into a KSDS requires"
                )
        budget = _Budget(deadline)
        values = self._cluster_values(dataset, build=True)
        self._write_file(self._staging_file(dataset.staging_dsn), bytes(data))
        self._run_generated("load", "dataset_load", values, f"the load of {logical}", budget)

    def empty_cluster(self, logical: str, *, deadline: float | None = None) -> None:
        """Delete and define the cluster ``logical`` empty, with its
        alternate indexes and paths (``dataset_empty``)."""
        dataset = self._cluster(logical)
        values = self._cluster_values(dataset, build=False)
        self._run_generated(
            "load", "dataset_empty", values, f"the empty definition of {logical}", _Budget(deadline)
        )

    def delete_dataset(self, logical: str, *, deadline: float | None = None) -> None:
        """Leave the dataset ``logical`` out, for a batch ``open`` fault with
        condition ``unavailable`` (``dataset_delete``): a cluster with its
        alternate indexes, a non-VSAM dataset, or every catalogued generation
        of a GDG, whose base stays, so that its ``(0)`` resolves to nothing."""
        dataset = self.resources.dataset(logical)
        budget = _Budget(deadline)
        if dataset.kind == "cluster":
            targets = [(dataset.dsn, "CLUSTER")] + [
                (self.resources.aix_path(name).aix_dsn, "ALTERNATEINDEX") for name in dataset.aix
            ]
        elif dataset.kind == "nonvsam":
            targets = [(dataset.dsn, "NONVSAM")]
        else:
            targets = [
                (entry.dsn, "NONVSAM")
                for entry in self._list_catalog(budget)
                if entry.type == "GENERATION" and entry.name == dataset.dsn
            ]
            if not targets:
                return
        statements = self._fragments(
            "dataset_delete", "delete", [{"dsn": dsn, "entry_type": kind} for dsn, kind in targets]
        )
        self._run_generated(
            "load",
            "dataset_delete",
            {"delete_statements": statements},
            f"the deletion of {logical}",
            budget,
        )

    def create_generation(
        self, logical: str, data: bytes, *, deadline: float | None = None
    ) -> None:
        """Create a GDG precondition, such as ``TRANSACT-BKUP`` or ``SYSTRAN``
        for ``COMBTRAN``, as a new generation ``(+1)`` of its base from the
        staged records (``dataset_create_generation``), so that the job under
        test reads it as ``(0)``."""
        dataset = self.resources.dataset(logical)
        if dataset.kind != "gdg":
            raise HarnessError(f"{logical} is a {dataset.kind}, not a GDG")
        self._records(logical, data, dataset.record_length)
        budget = _Budget(deadline)
        self._write_file(self._staging_file(dataset.staging_dsn), bytes(data))
        self._run_generated(
            "load",
            "dataset_create_generation",
            {"qualifier": dataset.qualifier, "lrecl": dataset.record_length, "base": dataset.dsn},
            f"the new generation of {logical}",
            budget,
        )

    def unload(self, logical: str, *, deadline: float | None = None) -> bytes:
        """The records of the dataset ``logical`` as exact bytes, through
        ``dataset_unload`` into ``CDTEST.UNLOAD.<q>``: a cluster in
        primary-key order, a non-VSAM dataset as written, a GDG's newest
        generation ``(0)``."""
        dataset = self.resources.dataset(logical)
        source = f"{dataset.dsn}(0)" if dataset.kind == "gdg" else dataset.dsn
        return self._unload(
            source, dataset.qualifier, dataset.record_length, logical, _Budget(deadline)
        )

    def unload_aix(self, path: str, *, deadline: float | None = None) -> bytes:
        """The ``@AIX:<path>`` observation: the base cluster's records read
        through the PATH, in alternate-key order."""
        resource = self.resources.aix_path(path)
        data = self._unload(
            resource.dsn,
            resource.qualifier,
            resource.record_length,
            f"{AIX_PREFIX}{path}",
            _Budget(deadline),
        )
        return format_fixed_records(
            self._records(path, data, resource.record_length), resource.record_length
        )

    def _unload(
        self, source_dsn: str, qualifier: str, record_length: int, what: str, budget: _Budget
    ) -> bytes:
        """Unload ``source_dsn`` and return the unload file's bytes.

        Before the job the file holds one sentinel record naming the job; a
        file that still holds it afterwards was not written by the unload
        (its records lie elsewhere, or were appended), which is
        ``AdapterUnavailable`` (GAP-MIS-007) rather than a wrong outcome."""
        dsn = UNLOAD_PREFIX + qualifier
        path = self._dataset_file("dataset_unload", dsn)
        if not path.is_file():
            raise HarnessError(f"{dsn} has no file at {path}: define_staging has not run")
        member = self.next_member("unload")
        sentinel = (_SENTINEL_TEXT + member.encode("ascii")).ljust(record_length, _SENTINEL_FILL)
        sentinel = sentinel[:record_length]
        self._write_file(path, sentinel)
        self._run_generated(
            "unload",
            "dataset_unload",
            {"source_dsn": source_dsn, "qualifier": qualifier},
            f"the unload of {what}",
            budget,
            member=member,
        )
        data = path.read_bytes()
        records = self._records(what, data, record_length)
        if sentinel in records:
            raise AdapterUnavailable(
                f"the unload of {what} did not replace {path}: its records are not where "
                f"dataset_unload.parse.path says",
                _GAP_DATASETS,
            )
        return data

    # ----------------------------------------------------------- catalog

    def list_catalog(self, *, deadline: float | None = None) -> tuple[CatalogEntry, ...]:
        """The catalog entries a ``catalog_list`` job reports
        (``parse_listing``), sorted. Its LISTCAT SYSPRINT reaches the evidence
        unchanged with the job's other sysout files."""
        return self._list_catalog(_Budget(deadline))

    def _list_catalog(self, budget: _Budget) -> tuple[CatalogEntry, ...]:
        run = self._run_generated("catalog", "catalog_list", {}, "the catalog listing", budget)
        listings = [
            relative
            for relative in run.sysout
            if self._glob("catalog_list", "listing_files", relative)
        ]
        if not listings:
            raise AdapterUnavailable(
                f"job {run.label} wrote no sysout file matching catalog_list.parse.listing_files",
                _GAP_DATASETS,
            )
        text = "\n".join(run.sysout[relative].decode("latin-1") for relative in listings)
        entry_re = self._regex("catalog_list", "entry")
        if not {"type", "name"} <= set(entry_re.groupindex):
            raise HarnessError(
                "commands.yaml catalog_list.parse.entry needs the groups type and name"
            )
        generation_re = self._regex("catalog_list", "generation")
        if "generation" not in generation_re.groupindex:
            raise HarnessError(
                "commands.yaml catalog_list.parse.generation needs the group generation"
            )
        return parse_listing(text, entry_re, generation_re)

    def catalog(self, *, deadline: float | None = None) -> bytes:
        """The ``@CATALOG`` observation, rendered by ``format_catalog``."""
        return _catalog_bytes(self._list_catalog(_Budget(deadline)))

    def snapshot_catalog(self, *, deadline: float | None = None) -> frozenset[CatalogEntry]:
        """The catalog after deployment and ``define_staging``, which
        ``reset_catalog`` restores before every scenario."""
        return frozenset(self._list_catalog(_Budget(deadline)))

    def reset_catalog(
        self, snapshot: Iterable[CatalogEntry], *, deadline: float | None = None
    ) -> None:
        """Restore the catalog to ``snapshot``.

        With ``catalog_reset`` available, as probed within the time left
        before the scenario deadline, its command does it. Otherwise one
        generated IDCAMS job deletes every listed entry the snapshot lacks
        (generations first, then paths, alternate indexes, clusters, non-VSAM
        datasets and GDG bases) and re-defines every snapshot GDG base that is
        missing, with its LIMIT and SCRATCH as the copy's jcl/DEFGDGB defines
        them. The catalog is then listed again, and any remaining difference
        raises ``HarnessError("catalog reset not confirmed: ...")``, so that
        the adapter applies its GAP-MIS-004 rule.

        Every listed name counts, not only the ``AWS.M2.CARDDEMO`` ones:
        ``catalog_list`` lists level ``AWS`` so that ``DEFCUST``'s
        ``AWS.CUSTDATA.CLUSTER`` is application state that ``@CATALOG`` shows
        and this reset removes."""
        target = frozenset(snapshot)
        budget = _Budget(deadline)
        if self.commands.available(
            "catalog_reset",
            self.env,
            deadline_left=budget.command_left("probing catalog_reset"),
        ):
            self._run("catalog_reset", budget)
        else:
            current = set(self._list_catalog(budget))
            extra = sorted(current - target, key=lambda e: (_DELETE_ORDER.index(e.type), e))
            missing_bases = sorted(entry.name for entry in target - current if entry.type == "GDG")
            if extra or missing_bases:
                deletes = self._fragments(
                    "catalog_reset",
                    "delete",
                    [
                        {"dsn": entry.dsn, "entry_type": _DELETE_TYPES[entry.type]}
                        for entry in extra
                    ],
                )
                defines = self._fragments(
                    "catalog_reset",
                    "define_gdg",
                    [
                        {"base": base, "limit": self.resources.gdg_limit(base)}
                        for base in missing_bases
                    ],
                )
                self._run_generated(
                    "reset",
                    "catalog_reset",
                    {"delete_statements": deletes, "define_gdg_statements": defines},
                    "the catalog reset",
                    budget,
                    submit_entry="bpe_submit",
                )
        current = set(self._list_catalog(budget))
        if current != target:
            extra = ", ".join(str(entry) for entry in sorted(current - target))
            missing = ", ".join(str(entry) for entry in sorted(target - current))
            raise HarnessError(f"catalog reset not confirmed: extra [{extra}], missing [{missing}]")

    def present(self, name: str, entries: Iterable[CatalogEntry]) -> bool:
        """Whether the logical dataset or ``@AIX:<path>`` ``name`` exists in
        the catalog ``entries``: a cluster or non-VSAM dataset by its entry, a
        GDG by at least one generation, a path by its PATH entry and its base
        cluster."""
        listed = {(entry.name, entry.type) for entry in entries}
        if name.startswith(AIX_PREFIX):
            path = self.resources.aix_path(name[len(AIX_PREFIX) :])
            base = self.resources.dataset(path.base)
            return (path.dsn, "PATH") in listed and (base.dsn, "CLUSTER") in listed
        dataset = self.resources.dataset(name)
        kind = {"cluster": "CLUSTER", "nonvsam": "NONVSAM", "gdg": "GENERATION"}[dataset.kind]
        return (dataset.dsn, kind) in listed

    def observe(self, names: Iterable[str], *, deadline: float | None = None) -> dict[str, bytes]:
        """The observations ``names`` after a run, as raw bytes: logical
        datasets through ``unload``, ``@AIX:<path>`` through ``unload_aix`` and
        ``@CATALOG`` through ``catalog``. The catalog is listed once; a dataset
        or path it does not hold is left out, because ``Result.datasets``
        carries only what exists after the run. Any other reserved name is
        another module's (``@REGION-FILES``, ``@TDQ:JOBS``) and raises
        ``HarnessError``."""
        wanted = sorted(set(names))
        for name in wanted:
            if name.startswith("@") and name != CATALOG and not name.startswith(AIX_PREFIX):
                raise HarnessError(f"{name} is not an observation of the BPE dataset services")
        if not wanted:
            return {}
        budget = _Budget(deadline)
        entries = self._list_catalog(budget)
        found: dict[str, bytes] = {}
        for name in wanted:
            if name == CATALOG:
                found[name] = _catalog_bytes(entries)
            elif not self.present(name, entries):
                continue
            elif name.startswith(AIX_PREFIX):
                found[name] = self.unload_aix(name[len(AIX_PREFIX) :], deadline=budget.deadline)
            else:
                found[name] = self.unload(name, deadline=budget.deadline)
        return found
