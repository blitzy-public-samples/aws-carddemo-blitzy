"""Coverage reporting: status rules, coverage matrix, gap register, coverage
report, acceptance checks and the import of UniKix evidence.

Coverage is functional: the number of ``verified`` inventory items divided
by the frozen inventory total (``tests/inventory/inventory.yaml``
``header.total``). Code coverage, test counts and pass rates are never
reported as coverage.

Status rules
    A result record counts only while it is *current*
    (``harness.results.is_current``): its ``baseline_version`` and
    ``scenario_digest`` match the scenario and baseline as they stand. A
    record that is not current is ``stale`` and counts as
    ``implemented-unexecuted``. The records are the gnucobol-batch records
    under ``tests/reports/results/`` and the ``validation.yaml`` files of
    imported UniKix evidence under ``tests/reports/unikix/<execution_id>/
    <scenario_id>/`` (``result.yaml`` beside it is raw, immutable evidence).
    A UniKix record counts only when it is *eligible* (``unikix_eligible``):
    it ran the UniKix copy of the zip as committed, and none of the
    scenario's items passes through a part of the copy that differs from
    ``app/`` (GAP-DOC-020 to GAP-DOC-032; the ``fct.lst`` items of
    GAP-DOC-030 count when the record ran the zip's ``fct.lst``). An
    ineligible record is reported as UniKix-copy behaviour and never
    verifies an ``app/`` item.

    A scenario is ``failing`` when any counted record fails, else ``passed``
    when any passed, else ``blocked`` when every counted record is blocked or
    no capable adapter ran it, else ``implemented-unexecuted``. An item takes
    the worst status of the scenarios that list it (failing > blocked >
    implemented-unexecuted > verified); an item no scenario lists is
    ``not-covered``.

Outputs (``regenerate``, run at the end of every pytest session and by
``python tests/harness/report.py``)
    ``tests/reports/coverage-matrix.csv``, ``tests/reports/gap-register.csv``
    and ``tests/reports/coverage-report.md``, plus the ``scenario_ids``,
    ``status`` and ``gap_ids`` of every inventory item. The three report
    files are deterministic: sorted rows and keys, ``\\n`` line endings, no
    elapsed time, absolute path or wall-clock date, so two consecutive full
    runs leave them byte-identical.

Command line (from the repository root)::

    python tests/harness/report.py                      # regenerate
    python tests/harness/report.py --check              # register/matrix rules
    python tests/harness/report.py --check-protected    # app/ samples/ diagrams/ untouched
    python tests/harness/report.py --check-proof        # the job-chain proof
    python tests/harness/report.py --import-unikix <execution_id>
    python tests/harness/report.py --revalidate
    python tests/harness/report.py --compare-executions <id1> <id2>

The module writes only under ``tests/``: the report files, the inventory's
report fields and, on import, baselines, scenario tags and expectations,
``gaps.yaml``, inventory additions and ``validation.yaml`` beside the UniKix
evidence. The UniKix zip is only read, in memory.
"""

from __future__ import annotations

import argparse
import csv
import datetime as _dt
import fnmatch
import functools
import importlib.metadata
import io
import os
import platform
import re
import subprocess
import sys
import zipfile
from collections import Counter
from dataclasses import dataclass, field
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

import yaml

_TESTS_ROOT = str(Path(__file__).resolve().parents[1])
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from adapters.base import AdapterError, Result  # noqa: E402
from harness import (  # noqa: E402
    INVENTORY_DIR,
    PROTECTED_BASE,
    REPO_ROOT,
    REPORTS_DIR,
    TESTS_ROOT,
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
from harness import inventory_tools as _inventory  # noqa: E402
from harness import results as _results  # noqa: E402
from harness import scenarios as _scenarios  # noqa: E402

__all__ = [
    "GAPS_FILE",
    "MATRIX_FILE",
    "REGISTER_FILE",
    "REPORT_FILE",
    "SEAMS",
    "GapRegister",
    "ReportError",
    "ScenarioStatus",
    "State",
    "build_state",
    "check",
    "check_proof",
    "check_protected",
    "check_seams",
    "compare_executions",
    "import_unikix",
    "load_gaps",
    "main",
    "referenced_gap_ids",
    "regenerate",
    "resolve_affected",
    "revalidate",
    "unikix_eligible",
    "unikix_ineligibility",
]

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

GAPS_FILE = INVENTORY_DIR / "gaps.yaml"
MATRIX_FILE = REPORTS_DIR / "coverage-matrix.csv"
REGISTER_FILE = REPORTS_DIR / "gap-register.csv"
REPORT_FILE = REPORTS_DIR / "coverage-report.md"
VALIDATION_FILE = "validation.yaml"
RESULT_FILE = "result.yaml"
ENVIRONMENT_FILE = "environment.yaml"
EVIDENCE_DATASETS_DIR = "datasets"

MATRIX_HEADER = (
    "inventory_id", "implementation_locations", "scenario_ids", "assertions",
    "status", "execution_id", "evidence_path",
)
REGISTER_HEADER = (
    "gap_id", "category", "unknown", "affected_inventory_ids",
    "resolving_evidence", "needed", "status",
)

VERIFIED = "verified"
FAILING = "failing"
BLOCKED = "blocked"
UNEXECUTED = "implemented-unexecuted"
NOT_COVERED = "not-covered"
ITEM_STATUSES = (VERIFIED, FAILING, BLOCKED, UNEXECUTED, NOT_COVERED)
# Worst first: an item takes the worst status of the scenarios listing it.
_ITEM_SEVERITY = {FAILING: 0, BLOCKED: 1, UNEXECUTED: 2, VERIFIED: 3}
_ITEM_STATUS_OF = {
    _results.STATUS_PASSED: VERIFIED,
    _results.STATUS_FAILING: FAILING,
    _results.STATUS_BLOCKED: BLOCKED,
    _results.STATUS_UNEXECUTED: UNEXECUTED,
}

CATEGORIES = {
    "UNC": "uncertain-purpose",
    "MIS": "missing-input",
    "INF": "inferred-not-observed",
    "STB": "stub-limitation",
    "DOC": "doc-conflict",
    "DEF": "suspected-defect",
}
CATEGORY_ORDER = (
    "uncertain-purpose", "missing-input", "inferred-not-observed",
    "stub-limitation", "doc-conflict", "suspected-defect",
)
GAP_STATUSES = ("open", "closed")
GAP_ID_RE = re.compile(r"GAP-(DOC|DEF|STB|MIS|INF|UNC)-\d{3}")
_GAP_ID_FULL_RE = re.compile(r"^GAP-(DOC|DEF|STB|MIS|INF|UNC)-(\d{3})$")
_UNIKIX_ID_RE = re.compile(r"^unikix-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$")

# The seams of the gnucobol-batch adapter and the stub-limitation entry each
# must have (the seam rule).
SEAMS = (
    ("tests/stubs/CEE3ABD.cbl", "GAP-STB-001"),
    ("tests/stubs/ceedays.c", "GAP-STB-002"),
    ("tests/stubs/psa_tiot_shim.c", "GAP-STB-003"),
    ("tests/stubs/PARMDRV.cbl and tests/stubs/DTCDRV.cbl", "GAP-STB-004"),
    ("IDCAMS replacement (tests/adapters/gnucobol_batch/utilities.py)", "GAP-STB-005"),
    ("SORT replacement (tests/adapters/gnucobol_batch/utilities.py)", "GAP-STB-006"),
    ("IEBGENER, IEFBR14 and DISP (tests/adapters/gnucobol_batch/utilities.py)", "GAP-STB-007"),
    ("SDSF no-op (tests/adapters/gnucobol_batch/utilities.py)", "GAP-STB-008"),
    ("catalog and GDG emulation (tests/adapters/gnucobol_batch/datasets.py)", "GAP-STB-009"),
    ("Berkeley DB indexed files in place of VSAM", "GAP-STB-010"),
    ("compile and runtime option mapping (tests/adapters/gnucobol_batch/build.py)", "GAP-STB-011"),
)

# The entries whose affected items pass through a part of the UniKix copy
# that differs from app/; a UniKix record of such an item never verifies it.
UNIKIX_COPY_GAPS = tuple(f"GAP-DOC-{number:03d}" for number in range(20, 33))
# Its items are the UniKix-only fct.lst files, whose inventoried source is the
# UniKix resource list itself, so a record that ran that very list verifies them.
FCT_GAP = "GAP-DOC-030"
UNIKIX_ZIP = REPO_ROOT / _inventory.UNIKIX_ZIP
FCT_MEMBER = _inventory.FCT_MEMBER
_ZIP_SKIPPED_PREFIX = "__MACOSX/"

GAP_NO_INSTALLATION = "GAP-MIS-009"
GAP_APP_NOT_ON_UNIKIX = "GAP-MIS-008"
GAP_PAGE_ZERO = "GAP-MIS-002"
GAP_COMPLETENESS = "GAP-UNC-012"

PROOF_SCENARIOS = ("BAT-POSTINT-normal-01", "BAT-CHAIN-normal-01")
_PROOF_DATASETS = ("DALYREJS", "TRANSACT", "TCATBALF", "ACCTDATA", "SYSTRAN")
PROOF_REQUIREMENTS = {
    "BAT-POSTINT-normal-01": (("return_code", "messages", "reject_reason_codes"), _PROOF_DATASETS),
    "BAT-CHAIN-normal-01": (
        ("return_code", "messages", "reject_reason_codes"),
        _PROOF_DATASETS + ("TRANSACT-BKUP", "TRXFL", "STMTFILE", "HTMLFILE", "TRANREPT"),
    ),
}
PROOF_JOBS = ("POSTTRAN", "INTCALC")

# Tech spec Section 6.6.7.1 execution-effort table: seed label, scenarios,
# the suite areas it seeds and whether only their batch (non-e2e) scenarios.
SEED_COUNTS = (
    ("Authentication", 5, ("SEC",), False),
    ("User Administration", 12, ("USR",), False),
    ("Account Management", 15, ("ACT",), False),
    ("Card Management", 18, ("CRD",), False),
    ("Transaction Processing", 25, ("TRN",), False),
    ("Bill Payment", 10, ("BIL",), False),
    ("Batch Jobs", 15, ("DAT", "FIN", "RPT", "UTL"), True),
    ("Integration Workflows", 10, ("BAT",), False),
)
SEED_TOTAL = 110

# The CI figures the plan projected, with the page-zero probe succeeding.
PLANNING_PROJECTION = {VERIFIED: 125, FAILING: 0, BLOCKED: 506, UNEXECUTED: 9, NOT_COVERED: 44}

PYTHON_PACKAGES = ("pytest", "pluggy", "iniconfig", "packaging", "Pygments", "PyYAML")
DPKG_PACKAGES = ("build-essential", "libgmp-dev", "libncurses-dev", "libdb-dev", "s3270")
# environment.yaml keys of the UniKix version probes (adapters/unikix/env.py);
# GAP-MIS-009 closes once all five name a version.
UNIKIX_VERSION_KEYS = ("version_tpe", "version_bpe", "version_vcobol", "version_licence", "version_java")
_VERSION_FAILURE_PREFIXES = ("unavailable", "not defined", "exit status")

_SUBPROCESS_TIMEOUT_S = 120
_TEXT_CLIP = 2000
_SELECTOR_LIST_KEYS = ("activation", "area", "kind", "level", "owner", "source_path", "name", "source", "scenario")
_SELECTOR_FLAG_KEYS = ("via_scenarios", "unikix_dependent")
_LINES_SUFFIX_RE = re.compile(r":\d+(?:-\d+)?(?:,\d+(?:-\d+)?)*$")


class ReportError(HarnessError):
    """The report inputs (gap register, evidence, a file to rewrite) do not
    have the shape the reporting rules need."""


def _clip(text: str, limit: int = _TEXT_CLIP) -> str:
    text = str(text)
    return text if len(text) <= limit else text[: limit - 3] + "..."


def _yaml_text_load(text: str) -> Any:
    """``text`` parsed with the safe loader ``harness.load_yaml`` uses, to
    verify an edited file before it replaces the original."""
    loader = getattr(yaml, "CSafeLoader", yaml.SafeLoader)
    return yaml.load(text, Loader=loader)  # noqa: S506 - a safe loader


# --------------------------------------------------------------------------
# Gap register
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class GapRegister:
    """``tests/inventory/gaps.yaml``: the whole ``document``, its entries by
    gap id in id order, and what is wrong with its shape."""

    document: dict
    entries: dict
    problems: tuple = ()

    def get(self, gap_id: str) -> dict | None:
        return self.entries.get(gap_id)

    def is_open(self, gap_id: str) -> bool:
        entry = self.entries.get(gap_id)
        return entry is not None and entry.get("status") == "open"


def _entry_id(entry: Mapping[str, Any]) -> Any:
    return entry.get("gap_id", entry.get("id"))


def load_gaps(path: str | Path = GAPS_FILE) -> GapRegister:
    """The gap register. ``gaps`` is a list of entries (id key ``gap_id`` or
    ``id``) or a mapping of gap id to entry. A shape problem is recorded in
    ``problems`` (``check`` reports it); only an unreadable file raises."""
    try:
        document = load_yaml(path)
    except FileNotFoundError:
        raise ReportError(f"{display_path(path)} does not exist") from None
    except YAMLLoadError as exc:
        raise ReportError(str(exc)) from None
    if not isinstance(document, dict):
        raise ReportError(f"{display_path(path)}: must hold a mapping with a 'gaps' list")
    problems: list[str] = []
    section = document.get("gaps")
    raw: list[tuple[Any, Any]] = []
    if isinstance(section, dict):
        raw = [(gap_id, entry) for gap_id, entry in section.items()]
    elif isinstance(section, list):
        raw = [(_entry_id(entry) if isinstance(entry, dict) else None, entry) for entry in section]
    else:
        problems.append("gaps.yaml: 'gaps' must be a list of entries or a mapping of gap id -> entry")
    entries: dict[str, dict] = {}
    for position, (gap_id, entry) in enumerate(raw):
        if not isinstance(entry, dict):
            problems.append(f"gaps.yaml: entry {position} is not a mapping")
            continue
        if not isinstance(gap_id, str) or not _GAP_ID_FULL_RE.match(gap_id):
            problems.append(f"gaps.yaml: entry {position} has no gap id GAP-<CAT>-<nnn> (got {gap_id!r})")
            continue
        if gap_id in entries:
            problems.append(f"gaps.yaml: {gap_id} is defined twice")
            continue
        entries[gap_id] = entry
    return GapRegister(document, dict(sorted(entries.items())), tuple(problems))


def _entry_problems(gap_id: str, entry: Mapping[str, Any]) -> list[str]:
    """How one register entry breaks the register contract."""
    problems = []
    prefix = _GAP_ID_FULL_RE.match(gap_id).group(1)
    category = entry.get("category")
    if category not in CATEGORIES.values():
        problems.append(f"{gap_id}: category {category!r} is not one of {', '.join(CATEGORY_ORDER)}")
    elif category != CATEGORIES[prefix]:
        problems.append(f"{gap_id}: the prefix {prefix} names {CATEGORIES[prefix]}, not {category}")
    status = entry.get("status")
    if status not in GAP_STATUSES:
        problems.append(f"{gap_id}: status {status!r} is not open or closed")
    for key in ("unknown", "needed"):
        if not isinstance(entry.get(key), str) or not entry[key].strip():
            problems.append(f"{gap_id}: {key} must say what is unknown and what would settle it")
    evidence = entry.get("resolving_evidence")
    if status == "closed" and not evidence:
        problems.append(f"{gap_id}: a closed entry names its resolving_evidence")
    if evidence is not None and not isinstance(evidence, (Mapping, str)):
        problems.append(f"{gap_id}: resolving_evidence must be a mapping (execution_id, scenario_id, "
                        "evidence_path, or commit) or null")
    ids = entry.get("affected_inventory_ids", [])
    if not isinstance(ids, list) or not all(isinstance(item_id, str) for item_id in ids):
        problems.append(f"{gap_id}: affected_inventory_ids must be a list of inventory ids")
    clauses = entry.get("affected", [])
    if clauses is None:
        clauses = []
    if not isinstance(clauses, list):
        problems.append(f"{gap_id}: affected must be a list of selector clauses")
    else:
        for index, clause in enumerate(clauses):
            problems.extend(f"{gap_id}: affected[{index}]: {problem}" for problem in clause_problems(clause))
    return problems


# --------------------------------------------------------------------------
# Affected items: explicit ids united with selector clauses
# --------------------------------------------------------------------------


@dataclass
class _Universe:
    """What selector clauses are matched against: the inventory items and
    the scenarios that list them."""

    items: dict  # item id -> item, in id order
    listed_by: dict  # item id -> sorted scenario ids listing it
    citing: dict  # gap id -> item ids listed by scenarios whose gap_ids cite it
    unikix_dependent: frozenset

    @classmethod
    def build(cls, items: Iterable[Mapping[str, Any]], scenario_list: Iterable[Any]) -> "_Universe":
        by_id = {item["id"]: item for item in sorted(items, key=lambda item: item["id"])}
        listed: dict[str, set[str]] = {}
        citing: dict[str, set[str]] = {}
        dependent: set[str] = set()
        for scenario in scenario_list:
            item_ids = [str(item_id) for item_id in scenario.inventory_ids]
            for item_id in item_ids:
                listed.setdefault(item_id, set()).add(scenario.id)
            for gap_id in scenario.gap_ids:
                citing.setdefault(str(gap_id), set()).update(item_ids)
            if _scenarios.only_unikix(scenario):
                dependent.update(item_ids)
        return cls(
            by_id,
            {item_id: sorted(ids) for item_id, ids in listed.items()},
            {gap_id: set(ids) for gap_id, ids in citing.items()},
            frozenset(dependent),
        )


def _values(value: Any) -> list[str]:
    if isinstance(value, (list, tuple)):
        return [str(entry) for entry in value]
    return [str(value)]


def clause_problems(clause: Any) -> list[str]:
    """Why a selector clause cannot be matched; empty when it is valid. The
    grammar is the union of the register's ``selector_keys`` (activation,
    area, kind, level, owner, source_path, unikix_dependent, via_scenarios)
    and the glob keys ``name``, ``source`` and ``scenario``."""
    if not isinstance(clause, dict) or not clause:
        return ["a selector clause is a non-empty mapping of selector keys"]
    problems = []
    for key, value in clause.items():
        if key in _SELECTOR_FLAG_KEYS:
            if value is not True:
                problems.append(f"{key} takes only true")
        elif key in _SELECTOR_LIST_KEYS:
            if isinstance(value, (list, tuple)) and not value:
                problems.append(f"{key} names no value")
            elif isinstance(value, (dict, bool)) or value is None:
                problems.append(f"{key} takes a value or a list of values")
        else:
            known = ", ".join(sorted(_SELECTOR_LIST_KEYS + _SELECTOR_FLAG_KEYS))
            problems.append(f"unknown selector key {key!r}; known: {known}")
    return problems


def _source_path(source: str) -> str:
    return _LINES_SUFFIX_RE.sub("", source)


def _key_matches(key: str, value: Any, item: Mapping[str, Any], gap_id: str, universe: _Universe) -> bool:
    item_id = item["id"]
    if key == "via_scenarios":
        return item_id in universe.citing.get(gap_id, ())
    if key == "unikix_dependent":
        return item_id in universe.unikix_dependent
    wanted = _values(value)
    name = str(item.get("name") or "")
    source = str(item.get("source") or "")
    if key == "area":
        return _inventory.item_area(item) in wanted
    if key in ("kind", "level", "activation"):
        return str(item.get(key)) in wanted
    if key == "owner":
        return (name.split() or [""])[0] in wanted
    if key == "source_path":
        path = _source_path(source)
        return any(path.startswith(entry) if entry.endswith("/") else path == entry for entry in wanted)
    if key == "name":
        return any(fnmatch.fnmatchcase(name, pattern) for pattern in wanted)
    if key == "source":
        return any(fnmatch.fnmatchcase(source, pattern) for pattern in wanted)
    if key == "scenario":
        return any(
            fnmatch.fnmatchcase(scenario_id, pattern)
            for scenario_id in universe.listed_by.get(item_id, ())
            for pattern in wanted
        )
    return False


def _resolve(gap_id: str, entry: Mapping[str, Any], universe: _Universe) -> list[str]:
    ids = entry.get("affected_inventory_ids") or []
    resolved = {item_id for item_id in ids if isinstance(item_id, str) and item_id in universe.items}
    clauses = entry.get("affected") or []
    for clause in clauses if isinstance(clauses, list) else []:
        if clause_problems(clause):
            continue  # check() reports it; a broken clause matches nothing
        for item in universe.items.values():
            if all(_key_matches(key, value, item, gap_id, universe) for key, value in clause.items()):
                resolved.add(item["id"])
    return sorted(resolved)


def resolve_affected(
    gap: Mapping[str, Any],
    inventory: Mapping[str, Any] | None = None,
    scenario_list: Iterable[Any] | None = None,
    gap_id: str | None = None,
) -> list[str]:
    """The sorted inventory ids a register entry affects: its
    ``affected_inventory_ids`` that exist in the inventory, united with the
    items any clause of ``affected`` matches (keys within one clause AND,
    clauses OR, a list-valued key matches any of its values). ``gap_id``
    names an entry of a register whose ``gaps`` is a mapping (its entries
    carry no id of their own); ``via_scenarios`` matches through it."""
    inventory = inventory if inventory is not None else _inventory.load()
    if scenario_list is None:
        scenario_list = _present_scenarios()[0].values()
    universe = _Universe.build(inventory["items"], scenario_list)
    return _resolve(str(gap_id if gap_id is not None else _entry_id(gap)), gap, universe)


# --------------------------------------------------------------------------
# Scenarios, baselines and records
# --------------------------------------------------------------------------


def _present_scenarios() -> tuple[dict, list[str]]:
    """The scenario of every manifest id that has a file (manifest level
    first), in id order, and the problems of files that cannot be read."""
    present: dict[str, Any] = {}
    problems: list[str] = []
    for scenario_id in sorted(_scenarios.ALL_IDS):
        try:
            scenario = _scenarios.get(scenario_id, missing_ok=True)
        except HarnessError as exc:
            problems.append(f"{scenario_id}: scenario file cannot be read: {exc}")
            continue
        if scenario is not None:
            present[scenario_id] = scenario
    return present, problems


def _load_baseline(scenario_id: str) -> tuple[Any, str | None]:
    """The scenario's baseline (``None`` when it has none) and the problem
    that made it unreadable."""
    try:
        return _baselines.load(scenario_id, missing_ok=True), None
    except HarnessError as exc:
        return None, str(exc)


@dataclass(frozen=True)
class UnikixRecord:
    """One ``validation.yaml``: the record the status rules read for a
    scenario of one UniKix execution."""

    execution_id: str
    scenario_id: str
    record: dict
    path: Path


def _evidence_executions(evidence_dir: Path = UNIKIX_EVIDENCE_DIR) -> list[Path]:
    """The execution directories under ``tests/reports/unikix/``, in name
    order; files (the folder's README) and symbolic links are not executions."""
    if evidence_dir.is_symlink() or not evidence_dir.is_dir():
        return []
    return [
        entry for entry in sorted(evidence_dir.iterdir())
        if entry.is_dir() and not entry.is_symlink() and _UNIKIX_ID_RE.match(entry.name)
    ]


def _scenario_dirs(execution_dir: Path) -> list[Path]:
    return [
        entry for entry in sorted(execution_dir.iterdir())
        if entry.is_dir() and not entry.is_symlink()
    ]


def load_unikix_records(evidence_dir: Path = UNIKIX_EVIDENCE_DIR) -> tuple[list[UnikixRecord], list[str]]:
    """Every ``validation.yaml`` that keeps the result record contract, and
    the problems of those that do not."""
    found: list[UnikixRecord] = []
    problems: list[str] = []
    for execution_dir in _evidence_executions(evidence_dir):
        for scenario_dir in _scenario_dirs(execution_dir):
            path = scenario_dir / VALIDATION_FILE
            if path.is_symlink() or not path.is_file():
                continue
            shown = display_path(path)
            try:
                record = load_yaml(path)
            except YAMLLoadError as exc:
                problems.append(f"{shown}: {exc}")
                continue
            issues = _results.record_problems(record)
            if isinstance(record, Mapping):
                if record.get("adapter") != _results.UNIKIX:
                    issues.append(f"adapter: {record.get('adapter')!r} is not unikix")
                if record.get("execution_id") != execution_dir.name:
                    issues.append(f"execution_id: {record.get('execution_id')!r} is not {execution_dir.name}")
                if record.get("scenario_id") != scenario_dir.name:
                    issues.append(f"scenario_id: {record.get('scenario_id')!r} is not {scenario_dir.name}")
            if scenario_dir.name not in _scenarios.ALL_IDS:
                issues.append(f"{scenario_dir.name} is not an id of the closed scenario id manifest")
            if issues:
                problems.append(f"{shown}: " + "; ".join(issues))
                continue
            found.append(UnikixRecord(execution_dir.name, scenario_dir.name, dict(record), path))
    return found, problems


# --------------------------------------------------------------------------
# UniKix source identity and eligibility
# --------------------------------------------------------------------------


@functools.lru_cache(maxsize=4)
def _zip_members(zip_sha256: str, path: str) -> dict:
    """sha256 of every file member of the UniKix zip whose sha256 is
    ``zip_sha256``, read in memory; ``__MACOSX/`` resource forks skipped."""
    with zipfile.ZipFile(path) as archive:
        if sha256_file(path) != zip_sha256:
            raise ReportError(f"{display_path(path)} changed while it was read")
        return {
            info.filename: sha256_hex(archive.read(info))
            for info in archive.infolist()
            if not info.is_dir() and not info.filename.startswith(_ZIP_SKIPPED_PREFIX)
        }


def zip_identity(path: Path = UNIKIX_ZIP) -> tuple[str, dict] | None:
    """The UniKix zip's sha256 and member hashes, or ``None`` when the zip
    cannot be read."""
    try:
        digest = sha256_file(path)
        return digest, _zip_members(digest, str(path))
    except (OSError, zipfile.BadZipFile, ReportError):
        return None


def identity_problem(identity: Any, zip_info: tuple[str, dict] | None = None) -> str | None:
    """Why a record's ``source_identity`` is not the UniKix copy of the zip as
    committed, or ``None`` when it is: tree ``unikix-copy``, the zip's sha256,
    and every unit hash equal to that zip member's."""
    if not isinstance(identity, Mapping):
        return "the record names no source identity"
    if identity.get("tree") != "unikix-copy":
        return f"source_identity.tree is {identity.get('tree')!r}, not unikix-copy"
    zip_info = zip_info if zip_info is not None else zip_identity()
    if zip_info is None:
        return f"{display_path(UNIKIX_ZIP)} cannot be read"
    digest, members = zip_info
    if identity.get("zip_sha256") != digest:
        return f"source_identity.zip_sha256 is not the sha256 of {display_path(UNIKIX_ZIP)} ({digest})"
    units = identity.get("units")
    if not isinstance(units, Mapping):
        return "source_identity.units is not a mapping of zip members to sha256"
    for member, unit_digest in sorted(units.items()):
        if member not in members:
            return f"source_identity unit {member} is not a member of the zip"
        if members[member] != unit_digest:
            return f"source_identity unit {member} differs from the zip's member"
    return None


@dataclass(frozen=True)
class _CopyGaps:
    """The items whose behaviour the UniKix copy changes, by entry."""

    changed: dict  # gap id -> frozenset of item ids (GAP-DOC-020..032 except 030)
    fct_items: frozenset  # the items of GAP-DOC-030


def _copy_gaps(gaps: GapRegister, affected: Mapping[str, Sequence[str]]) -> _CopyGaps:
    changed = {
        gap_id: frozenset(affected.get(gap_id, ()))
        for gap_id in UNIKIX_COPY_GAPS
        if gap_id != FCT_GAP and gap_id in gaps.entries
    }
    return _CopyGaps(changed, frozenset(affected.get(FCT_GAP, ())))


def _ineligibility(record: Mapping[str, Any], scenario: Any, copy_gaps: _CopyGaps,
                   zip_info: tuple[str, dict] | None) -> str | None:
    problem = identity_problem(record.get("source_identity"), zip_info)
    if problem is not None:
        return problem
    units = record["source_identity"].get("units") or {}
    for item_id in sorted(set(map(str, scenario.inventory_ids))):
        changed_by = sorted(gap_id for gap_id, items in copy_gaps.changed.items() if item_id in items)
        if changed_by:
            return (f"{item_id} passes through UniKix-copy code that differs from app/ "
                    f"({', '.join(changed_by)})")
        if item_id in copy_gaps.fct_items:
            fct_digest = (zip_info[1] if zip_info else {}).get(FCT_MEMBER)
            if units.get(FCT_MEMBER) != fct_digest or fct_digest is None:
                return f"{item_id} is a {FCT_MEMBER} file and the record did not run the zip's {FCT_MEMBER}"
    return None


def unikix_ineligibility(record: Mapping[str, Any], scenario: Any, state: "State | None" = None) -> str | None:
    """Why a UniKix record cannot count toward ``scenario``'s items, or
    ``None`` when it is eligible (``unikix_eligible``)."""
    state = state if state is not None else build_state()
    return _ineligibility(record, scenario, state.copy_gaps, state.zip_info)


def unikix_eligible(record: Mapping[str, Any], scenario: Any, state: "State | None" = None) -> bool:
    """Whether a UniKix record counts toward ``scenario``'s items: it ran the
    zip's UniKix copy as committed (tree, zip sha256, unit hashes), and none
    of the scenario's items passes through copy code that differs from
    ``app/`` (GAP-DOC-020 to GAP-DOC-032), except the GAP-DOC-030 ``fct.lst``
    files, which a record that ran the zip's ``fct.lst`` verifies."""
    return unikix_ineligibility(record, scenario, state) is None


# --------------------------------------------------------------------------
# Status rules
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class ScenarioStatus:
    """A scenario's status and what decided it. ``records`` are the counted
    current records whose status is the scenario's; ``stale`` names records
    that no longer match the scenario and baseline; ``ineligible`` holds the
    current UniKix records excluded as UniKix-copy behaviour."""

    scenario_id: str
    status: str
    reason: str | None
    gap_id: str | None
    records: tuple = ()
    stale: tuple = ()
    ineligible: tuple = ()
    counted: tuple = ()


def _record_order(record: Mapping[str, Any]) -> tuple:
    return (str(record.get("adapter")), str(record.get("execution_id")))


def scenario_status(
    scenario: Any,
    counted: Sequence[Mapping[str, Any]],
    stale: Sequence[str] = (),
    ineligible: Sequence[tuple[Mapping[str, Any], str]] = (),
) -> ScenarioStatus:
    """The status rules for one scenario over its ``counted`` records (current,
    and eligible when UniKix): failing when any fails, else passed when any
    passed, else blocked when every one is blocked or none exists and no
    capable adapter ran it, else implemented-unexecuted."""
    scenario_id = scenario.id
    counted = sorted(counted, key=_record_order)
    ineligible = sorted(ineligible, key=lambda pair: _record_order(pair[0]))
    by_status: dict[str, list[Mapping[str, Any]]] = {}
    for record in counted:
        by_status.setdefault(str(record.get("status")), []).append(record)
    extra = (tuple(stale), tuple(ineligible), tuple(counted))
    for status in (_results.STATUS_FAILING, _results.STATUS_PASSED):
        if status in by_status:
            first = by_status[status][0]
            return ScenarioStatus(scenario_id, status, first.get("reason"), None, tuple(by_status[status]), *extra)
    if counted and set(by_status) == {_results.STATUS_BLOCKED}:
        first = by_status[_results.STATUS_BLOCKED][0]
        return ScenarioStatus(scenario_id, _results.STATUS_BLOCKED, first.get("reason"), first.get("gap_id"),
                              tuple(by_status[_results.STATUS_BLOCKED]), *extra)
    if counted:
        records = tuple(by_status.get(_results.STATUS_UNEXECUTED, ()))
        first = records[0] if records else counted[0]
        return ScenarioStatus(scenario_id, _results.STATUS_UNEXECUTED, first.get("reason"), first.get("gap_id"),
                              records, *extra)
    capable = _scenarios.capable_adapters(scenario)
    if stale:
        return ScenarioStatus(scenario_id, _results.STATUS_UNEXECUTED, "stale", None, (), *extra)
    if _results.GNUCOBOL in capable:
        # CI runs every scenario gnucobol-batch can run; a missing record
        # means a partial run, not a missing capability.
        return ScenarioStatus(scenario_id, _results.STATUS_UNEXECUTED, "stale", None, (), *extra)
    # No capable adapter ran it: in CI every scenario only unikix can run is
    # deselected and has no record. Once UniKix evidence exists but none of
    # it may verify the app/ items, what is missing is an app/ build there.
    gap = GAP_APP_NOT_ON_UNIKIX if ineligible else GAP_NO_INSTALLATION
    return ScenarioStatus(scenario_id, _results.STATUS_BLOCKED, "adapter-unavailable", gap, (), *extra)


@dataclass
class State:
    """Everything the report is computed from, and the statuses it gives."""

    header: dict
    items: list  # inventory items with their report fields rewritten, in id order
    gaps: GapRegister
    affected: dict  # gap id -> resolved item ids
    scenarios: dict  # manifest id -> scenario, present files only
    statuses: dict  # scenario id -> ScenarioStatus
    item_status: dict  # item id -> status
    item_scenarios: dict  # item id -> sorted scenario ids
    unikix_dependent: list
    chain_scenarios: frozenset
    copy_gaps: _CopyGaps
    zip_info: tuple | None
    unikix_records: list
    problems: list = field(default_factory=list)
    baseline_problems: dict = field(default_factory=dict)

    @property
    def total(self) -> int:
        return len(self.items)

    def counts(self, item_ids: Iterable[str] | None = None) -> dict[str, int]:
        ids = self.item_status if item_ids is None else item_ids
        counter = Counter(self.item_status[item_id] for item_id in ids)
        return {status: counter.get(status, 0) for status in ITEM_STATUSES}


def _item_status(scenario_statuses: Iterable[str]) -> str:
    mapped = [_ITEM_STATUS_OF[status] for status in scenario_statuses]
    if not mapped:
        return NOT_COVERED
    return min(mapped, key=_ITEM_SEVERITY.__getitem__)


def build_state() -> State:
    """Read the inventory, the gap register, the scenarios, their baselines
    and every record, and apply the status rules."""
    inventory = _inventory.load()
    header = dict(inventory["header"])
    items = sorted((dict(item) for item in inventory["items"]), key=lambda item: item["id"])
    gaps = load_gaps()
    problems = list(gaps.problems)
    present, scenario_problems = _present_scenarios()
    problems.extend(scenario_problems)
    universe = _Universe.build(items, present.values())
    affected = {gap_id: _resolve(gap_id, entry, universe) for gap_id, entry in gaps.entries.items()}
    copy_gaps = _copy_gaps(gaps, affected)
    zip_info = zip_identity()

    unikix_records, record_problems = load_unikix_records()
    problems.extend(record_problems)
    by_scenario: dict[str, list[UnikixRecord]] = {}
    for entry in unikix_records:
        by_scenario.setdefault(entry.scenario_id, []).append(entry)

    statuses: dict[str, ScenarioStatus] = {}
    baseline_problems: dict[str, str] = {}
    for scenario_id, scenario in present.items():
        gnucobol = None
        if _results.GNUCOBOL in _scenarios.capable_adapters(scenario):
            try:
                gnucobol = _results.load(scenario_id)
            except HarnessError as exc:
                problems.append(f"{scenario_id}: gnucobol-batch record: {exc}")
        imported = by_scenario.get(scenario_id, [])
        counted: list[dict] = []
        stale: list[str] = []
        ineligible: list[tuple[dict, str]] = []
        if gnucobol is not None or imported:
            baseline, problem = _load_baseline(scenario_id)
            if problem is not None:
                baseline_problems[scenario_id] = problem
            if gnucobol is not None:
                if problem is None and _results.is_current(gnucobol, scenario, baseline):
                    counted.append(gnucobol)
                else:
                    stale.append(_results.evidence_path_for(_results.GNUCOBOL, scenario_id))
            for entry in imported:
                if problem is not None or not _results.is_current(entry.record, scenario, baseline):
                    stale.append(display_path(entry.path))
                    continue
                why = _ineligibility(entry.record, scenario, copy_gaps, zip_info)
                if why is None:
                    counted.append(entry.record)
                else:
                    ineligible.append((entry.record, why))
        statuses[scenario_id] = scenario_status(scenario, counted, stale, ineligible)

    item_scenarios = {item["id"]: universe.listed_by.get(item["id"], []) for item in items}
    item_status = {
        item_id: _item_status(statuses[scenario_id].status for scenario_id in scenario_ids)
        for item_id, scenario_ids in item_scenarios.items()
    }
    gaps_of_item: dict[str, set[str]] = {item["id"]: set() for item in items}
    for gap_id, item_ids in affected.items():
        for item_id in item_ids:
            gaps_of_item[item_id].add(gap_id)
    for item_id, scenario_ids in item_scenarios.items():
        for scenario_id in scenario_ids:
            gaps_of_item[item_id].update(map(str, present[scenario_id].gap_ids))
            status = statuses[scenario_id]
            if status.status != _results.STATUS_PASSED and status.gap_id:
                gaps_of_item[item_id].add(status.gap_id)
            gaps_of_item[item_id].update(
                str(record["gap_id"]) for record in status.counted
                if record.get("status") != _results.STATUS_PASSED and record.get("gap_id")
            )
    for item in items:
        item["scenario_ids"] = list(item_scenarios[item["id"]])
        item["status"] = item_status[item["id"]]
        item["gap_ids"] = sorted(gaps_of_item[item["id"]])
    for scenario_id, scenario in present.items():
        unknown = sorted(str(item_id) for item_id in scenario.inventory_ids if item_id not in universe.items)
        if unknown:
            problems.append(f"{scenario_id}: lists inventory id(s) {', '.join(unknown)} that the inventory lacks")

    chain = frozenset(
        scenario_id for scenario_id, scenario in present.items() if "chain" in _scenarios.marker_names(scenario)
    )
    return State(
        header=header,
        items=items,
        gaps=gaps,
        affected=affected,
        scenarios=present,
        statuses=statuses,
        item_status=item_status,
        item_scenarios=item_scenarios,
        unikix_dependent=sorted(universe.unikix_dependent & set(universe.items)),
        chain_scenarios=chain,
        copy_gaps=copy_gaps,
        zip_info=zip_info,
        unikix_records=unikix_records,
        problems=problems,
        baseline_problems=baseline_problems,
    )


# --------------------------------------------------------------------------
# Observed messages against the inventory (inventory completeness)
# --------------------------------------------------------------------------

_QUOTED_RE = re.compile(r"'([^']{6,})'")
_PLACEHOLDER_RE = re.compile(r"<[^<>]*>|\u2026")


def _literal_pattern(literal: str) -> re.Pattern[str]:
    """A message literal as a pattern: each ``<VARIABLE>`` (or ``\u2026``)
    part stands for any run-time text, and a run of spaces for any run,
    because composed messages pad their fixed-width parts."""
    parts = _PLACEHOLDER_RE.split(literal.strip())
    return re.compile(".*?".join(
        "".join(r"\s+" if token.isspace() else re.escape(token) for token in re.split(r"(\s+)", part) if token)
        for part in parts
    ), re.S)


@functools.lru_cache(maxsize=4)
def _message_patterns(texts: tuple) -> tuple:
    patterns = []
    for item_id, name, description in texts:
        literals = []
        start, end = name.find("'"), name.rfind("'")
        if 0 <= start < end:
            literals.append(name[start + 1:end])
        literals.extend(_QUOTED_RE.findall(description))
        for literal in literals:
            if len(literal.strip()) >= _inventory.MIN_MESSAGE_LENGTH:
                patterns.append((item_id, _literal_pattern(literal)))
    return tuple(patterns)


def _patterns_of(items: Iterable[Mapping[str, Any]]) -> tuple:
    return _message_patterns(tuple(
        (item["id"], str(item.get("name") or ""), str(item.get("description") or ""))
        for item in sorted(items, key=lambda item: item["id"])
    ))


def message_items(value: str, field_name: str, patterns: Sequence[tuple[str, re.Pattern[str]]]) -> list[str]:
    """The inventory items whose quoted message text matches an observed
    ``ERRMSG`` value (the whole value, trailing spaces aside) or appears in an
    unformatted screen's ``@text``."""
    if field_name == _scenarios.SCREEN_TEXT:
        # SEND TEXT wraps at column 80, so a message may run on into the next row.
        screen = value.replace("\n", "")
        return sorted({item_id for item_id, pattern in patterns if pattern.search(screen)})
    text = value.strip()
    return sorted({item_id for item_id, pattern in patterns if pattern.fullmatch(text)})


@dataclass(frozen=True)
class ObservedMessage:
    execution_id: str
    scenario_id: str
    step: Any
    field: str
    text: str
    line: int


def _observed_messages(result_path: Path, raw: Mapping[str, Any], execution_id: str,
                       scenario_id: str) -> list[ObservedMessage]:
    entries = raw.get("observed_messages")
    if not isinstance(entries, list):
        return []
    try:
        lines = result_path.read_text(encoding="utf-8").split("\n")
    except OSError:
        lines = []
    found = []
    for entry in entries:
        if not isinstance(entry, Mapping) or not isinstance(entry.get("value"), str):
            continue
        value = entry["value"]
        if not value.strip():
            continue
        probe = value.strip().split("\n")[0][:40]
        line = next((number for number, text in enumerate(lines, 1) if probe and probe in text), 1)
        found.append(ObservedMessage(execution_id, scenario_id, entry.get("step"), str(entry.get("field")),
                                     value, line))
    return found


def unmatched_messages(state: State, evidence_dir: Path = UNIKIX_EVIDENCE_DIR) -> list[ObservedMessage]:
    """Every observed ``ERRMSG`` or ``@text`` of the UniKix evidence that
    matches no inventory item, in execution, scenario and step order."""
    patterns = _patterns_of(state.items)
    unmatched = []
    for execution_dir in _evidence_executions(evidence_dir):
        for scenario_dir in _scenario_dirs(execution_dir):
            path = scenario_dir / RESULT_FILE
            if path.is_symlink() or not path.is_file():
                continue
            try:
                raw = load_yaml(path)
            except YAMLLoadError:
                continue
            if not isinstance(raw, Mapping):
                continue
            for message in _observed_messages(path, raw, execution_dir.name, scenario_dir.name):
                if not message_items(message.text, message.field, patterns):
                    unmatched.append(message)
    return unmatched


# --------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------


def _csv(rows: Iterable[Sequence[Any]]) -> str:
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    for row in rows:
        writer.writerow(["" if value is None else str(value) for value in row])
    return buffer.getvalue()


def _deciding(state: State, scenario_ids: Iterable[str]) -> list[Mapping[str, Any]]:
    return [record for scenario_id in scenario_ids for record in state.statuses[scenario_id].records]


def render_matrix(state: State) -> str:
    """``coverage-matrix.csv``: one row per inventory item in id order."""
    rows: list[Sequence[Any]] = [MATRIX_HEADER]
    for item in state.items:
        scenario_ids = state.item_scenarios[item["id"]]
        assertions = ";".join(
            f"{scenario_id}:{','.join(_scenarios.asserted_keys(state.scenarios[scenario_id]))}"
            for scenario_id in scenario_ids
        )
        records = _deciding(state, scenario_ids)
        execution_ids = sorted({str(record["execution_id"]) for record in records if record.get("execution_id")})
        paths = sorted({str(record["evidence_path"]) for record in records if record.get("evidence_path")})
        rows.append([
            item["id"], item.get("source"), ";".join(scenario_ids), assertions,
            state.item_status[item["id"]], ";".join(execution_ids), ";".join(paths),
        ])
    return _csv(rows)


def _render_evidence(evidence: Any) -> str:
    if evidence is None:
        return ""
    if isinstance(evidence, Mapping):
        return ";".join(f"{key}={'' if value is None else value}" for key, value in sorted(evidence.items(), key=str))
    return str(evidence)


def render_register(state: State) -> str:
    """``gap-register.csv``: one row per register entry in id order, its
    affected items resolved."""
    rows: list[Sequence[Any]] = [REGISTER_HEADER]
    for gap_id, entry in state.gaps.entries.items():
        rows.append([
            gap_id, entry.get("category"), entry.get("unknown"), ";".join(state.affected.get(gap_id, ())),
            _render_evidence(entry.get("resolving_evidence")), entry.get("needed"), entry.get("status"),
        ])
    return _csv(rows)


def _cell(value: Any) -> str:
    return str(value).replace("|", "\\|").replace("\r", " ").replace("\n", " ")


def _table(header: Sequence[str], rows: Iterable[Sequence[Any]], text_columns: Iterable[int] = (0,)) -> list[str]:
    left = set(text_columns)
    align = ["---" if index in left else "---:" for index in range(len(header))]
    lines = ["| " + " | ".join(_cell(value) for value in header) + " |", "|" + "|".join(align) + "|"]
    lines.extend("| " + " | ".join(_cell(value) for value in row) + " |" for row in rows)
    return lines


def _percent(numerator: int, denominator: int) -> str:
    if denominator <= 0:
        return "n/a"
    value = (Decimal(numerator) * 100 / Decimal(denominator)).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)
    return f"{value}%"


def _status_row(label: str, counts: Mapping[str, int]) -> list[Any]:
    return [label, *(counts[status] for status in ITEM_STATUSES), sum(counts.values())]


_ENVIRONMENT_HEADING = "\n## Environment\n"
_STATUS_HEADER = ("verified", "failing", "blocked", "implemented-unexecuted", "not-covered", "total")


def _run(argv: Sequence[str]) -> subprocess.CompletedProcess | None:
    try:
        return subprocess.run(list(argv), capture_output=True, text=True, check=False,
                              timeout=_SUBPROCESS_TIMEOUT_S, cwd=str(REPO_ROOT))
    except (OSError, subprocess.SubprocessError):
        return None


def _environment_lines() -> list[str]:
    """The Environment section: tool and package versions and the page-zero
    probe, never a date, a duration or a path."""
    lines = [f"- Adapter: {os.environ.get('CARDDEMO_ADAPTER') or 'gnucobol-batch'}"]
    cobc = _run(["cobc", "--version"])
    first = (cobc.stdout.splitlines() or [""])[0].strip() if cobc is not None and cobc.returncode == 0 else ""
    lines.append(f"- `cobc --version`: {first or 'cobc is not available on PATH'}")
    lines.append(f"- Python: {platform.python_version()}")
    packages = []
    for name in PYTHON_PACKAGES:
        try:
            packages.append(f"{name} {importlib.metadata.version(name)}")
        except importlib.metadata.PackageNotFoundError:
            packages.append(f"{name} not installed")
    lines.append(f"- Python packages: {', '.join(packages)}")
    dpkg = _run(["dpkg-query", "-W", "-f=${Package} ${Version}\\n", *DPKG_PACKAGES])
    if dpkg is None:
        lines.append("- Toolchain packages (dpkg-query): dpkg-query is not available")
    else:
        found = {}
        for line in dpkg.stdout.splitlines():
            name, _, version = line.partition(" ")
            if name and version.strip():
                found[name.split(":")[0]] = version.strip()
        shown = [f"{name} {found[name]}" if name in found else f"{name} not installed" for name in DPKG_PACKAGES]
        lines.append(f"- Toolchain packages (dpkg-query): {', '.join(shown)}")
    problem = None
    try:
        environment = _results.load_environment()
    except HarnessError as exc:
        environment, problem = None, _clip(exc, 200)
    if problem is not None:
        lines.append(f"- Page-zero probe: the runtime environment file cannot be read ({problem})")
        lines.append("- vm.mmap_min_addr: not recorded")
    elif environment is None:
        lines.append("- Page-zero probe: not recorded (no tests/reports/runtime/environment.yaml)")
        lines.append("- vm.mmap_min_addr: not recorded")
    else:
        probe = environment.get("page0_probe")
        if isinstance(probe, Mapping):
            outcome = {True: "succeeded", False: "failed"}.get(probe.get("ok"), "not run")
            detail = str(probe.get("detail") or "").strip()
            lines.append(f"- Page-zero probe: {outcome}" + (f" ({_clip(detail, 300)})" if detail else ""))
        else:
            lines.append(f"- Page-zero probe: {_clip(probe, 300) if probe is not None else 'not recorded'}")
        minimum = environment.get("vm_mmap_min_addr")
        lines.append(f"- vm.mmap_min_addr: {minimum if minimum is not None else 'not recorded'}")
    lines.append(f"- Commit: {git_head()}")
    return lines


def _projection_causes(state: State) -> list[str]:
    """Why this report's counts can differ from the planning projection."""
    causes = []
    missing = sorted(set(_scenarios.ALL_IDS) - set(state.scenarios))
    if missing:
        by_level = Counter(
            level for level, ids in _scenarios.MANIFEST.items() for scenario_id in missing if scenario_id in ids
        )
        shown = ", ".join(f"{by_level[level]} {level}" for level in _scenarios.LEVELS if by_level[level])
        causes.append(f"{len(missing)} scenario files of the id manifest are absent ({shown}); "
                      "the items only they would list are not-covered.")
    unrun = [
        scenario_id for scenario_id, status in state.statuses.items()
        if status.reason == "stale" and not status.records
        and _results.GNUCOBOL in _scenarios.capable_adapters(state.scenarios[scenario_id])
    ]
    if unrun:
        causes.append(f"{len(unrun)} scenarios gnucobol-batch can run have no current result record "
                      "(not run in this session, or run against another scenario or baseline version); "
                      "they count as implemented-unexecuted.")
    if state.baseline_problems:
        causes.append(f"{len(state.baseline_problems)} scenarios with records have a baseline that cannot be "
                      "read, so their records are not current.")
    page_zero = sorted(scenario_id for scenario_id, status in state.statuses.items() if status.gap_id == GAP_PAGE_ZERO)
    if page_zero:
        causes.append(f"{len(page_zero)} scenarios are blocked under {GAP_PAGE_ZERO} because the page-zero "
                      "probe failed: " + ", ".join(page_zero) + ".")
    failing = sorted(scenario_id for scenario_id, status in state.statuses.items()
                     if status.status == _results.STATUS_FAILING)
    if failing:
        causes.append(f"{len(failing)} scenarios are failing: " + ", ".join(failing) + ".")
    counted = sum(
        1 for status in state.statuses.values() for record in status.counted
        if record.get("adapter") == _results.UNIKIX
    )
    if counted:
        causes.append(f"{counted} imported UniKix records count toward the statuses.")
    if state.total != sum(PLANNING_PROJECTION.values()):
        causes.append(f"The inventory holds {state.total} items, not the {sum(PLANNING_PROJECTION.values())} "
                      "of the planning freeze (see Inventory completeness).")
    return causes


def _ids_by_area(item_ids: Iterable[str]) -> list[str]:
    grouped: dict[str, list[str]] = {}
    for item_id in sorted(item_ids):
        grouped.setdefault(item_id.split("-")[1], []).append(item_id)
    order = {area: index for index, area in enumerate(_inventory.AREAS)}
    return [
        f"- {area} ({len(ids)}): {', '.join(ids)}"
        for area, ids in sorted(grouped.items(), key=lambda pair: order.get(pair[0], len(order)))
    ]


_COMPLETENESS_RULES = (
    "- Source items are counted from the repository listings (`app/cbl`, `app/jcl`, `app/proc`, `app/ctl`, "
    "`app/bms`, `app/cpy`, `app/data`) and the CSD; behaviour items come from the batch, JCL and cross-job "
    "tables and from four extraction rules applied to the online programs after every COPY is resolved: "
    "screen messages, navigation paths, the COACTUPC field edits, and side effects and content.",
    "- Literal and `STRING` extraction cannot see a message whose text is built wholly from variables at run "
    "time.",
    "- The reference check that marks an 88-level message condition `unused` cannot see a condition set "
    "through a `REDEFINES` or a group move.",
    "- `python tests/harness/inventory_tools.py --check` recounts the source items, re-runs the rules and "
    "repeats the Db2, IMS and MQ searches; it fails on any drift from the frozen header.",
)


def _level_rows(state: State) -> list[list[Any]]:
    chain_items = {
        item_id for item_id, scenario_ids in state.item_scenarios.items()
        if any(scenario_id in state.chain_scenarios for scenario_id in scenario_ids)
    }
    groups: dict[str, list[str]] = {"unit": [], "integration": [], "integration (chain)": [], "e2e": []}
    for item in state.items:
        level = str(item.get("level"))
        if level == "integration" and item["id"] in chain_items:
            level = "integration (chain)"
        groups.setdefault(level, []).append(item["id"])
    return [_status_row(level, state.counts(ids)) for level, ids in groups.items()]


def _seed_of_area() -> dict[str, str]:
    shown = {}
    for label, count, areas, batch_only in SEED_COUNTS:
        for area in areas:
            shown[area] = f"{count}" if len(areas) == 1 else f"{label} {count} (shared; batch scenarios)"
    return shown


def _area_rows(state: State) -> list[list[Any]]:
    items_of: dict[str, list[str]] = {area: [] for area in _inventory.AREAS}
    for item in state.items:
        items_of.setdefault(_inventory.item_area(item), []).append(item["id"])
    scenarios_of = Counter(scenario.area for scenario in state.scenarios.values())
    seeds = _seed_of_area()
    rows = []
    for area, ids in items_of.items():
        counts = state.counts(ids)
        rows.append([area, *(counts[status] for status in ITEM_STATUSES), len(ids),
                     scenarios_of.get(area, 0), seeds.get(area, "-")])
    return rows


def _seed_rows(state: State) -> list[list[Any]]:
    rows = []
    for label, count, areas, batch_only in SEED_COUNTS:
        suite = sum(
            1 for scenario in state.scenarios.values()
            if scenario.area in areas and not (batch_only and scenario.level == "e2e")
        )
        where = ", ".join(areas) + (" (batch scenarios only)" if batch_only else "")
        rows.append([label, count, where, suite])
    rows.append(["Total", SEED_TOTAL, "all areas", len(state.scenarios)])
    return rows


def render_report(state: State, proof: tuple[bool, str] | None = None) -> str:
    """``coverage-report.md``: the sections of the coverage report, in order."""
    proof = proof if proof is not None else check_proof(state)
    counts = state.counts()
    total = state.total
    verified = counts[VERIFIED]
    header_total = state.header.get("total")
    open_gaps = [gap_id for gap_id in state.gaps.entries if state.gaps.is_open(gap_id)]
    lines = [
        "# CardDemo characterization coverage report",
        "",
        "Generated by `tests/harness/report.py`. Coverage is functional: verified inventory items divided by "
        "the frozen inventory total. Code coverage, test counts and pass rates are not coverage.",
        "",
        "## Summary",
        "",
        *_table(("Status", "Items"), [[status, counts[status]] for status in ITEM_STATUSES] + [["total", total]]),
        "",
        f"Verified / total: {verified} / {total} = {_percent(verified, total)}",
        "",
    ]
    if header_total != total:
        lines += [f"The inventory header gives a total of {header_total}, but the inventory holds {total} items.", ""]
    lines += [
        "### Against the planning projection",
        "",
        "The plan projected the CI figures with every scenario present, every scenario gnucobol-batch can run "
        "recorded, the page-zero probe succeeding and no UniKix evidence imported. This report governs.",
        "",
        *_table(("Status", "This report", "Planning projection"),
                [[status, counts[status], PLANNING_PROJECTION[status]] for status in ITEM_STATUSES]
                + [["total", total, sum(PLANNING_PROJECTION.values())]]),
        "",
    ]
    if all(counts[status] == PLANNING_PROJECTION[status] for status in ITEM_STATUSES):
        lines += ["The counts equal the planning projection.", ""]
    else:
        causes = _projection_causes(state) or ["The records and scenarios as they stand give these statuses."]
        lines += ["The counts differ from the projection:", "", *(f"- {cause}" for cause in causes), ""]
    lines += [
        "## By level",
        "",
        "Integration items listed by a job-chain scenario (marker `chain`) have their own row.",
        "",
        *_table(("Level", *_STATUS_HEADER), _level_rows(state) + [_status_row("total", counts)]),
        "",
        "## By area",
        "",
        "Scenarios counts the suite's scenario files of the area; the last column is the tech-spec seed count "
        "of Section 6.6.7.1.",
        "",
        *_table(("Area", *_STATUS_HEADER, "Scenarios", "Tech-spec seed"), _area_rows(state), text_columns=(0, 8)),
        "",
        "### Tech-spec seed counts (Section 6.6.7.1)",
        "",
        "The tech spec's manual scenarios seed the inventory; they are not its bound.",
        "",
        *_table(("Seed", "Seed scenarios", "Suite areas", "Suite scenarios"), _seed_rows(state), text_columns=(0, 2)),
        "",
        "## By kind",
        "",
        *_table(("Kind", *_STATUS_HEADER), [
            _status_row(kind, state.counts([item["id"] for item in state.items if item.get("kind") == kind]))
            for kind in _inventory.KINDS
        ]),
        "",
        "## UniKix-dependent items",
        "",
        f"{len(state.unikix_dependent)} items are listed by at least one scenario that only the unikix adapter "
        "can run.",
        "",
        *_ids_by_area(state.unikix_dependent),
        "",
        "## UniKix-copy behavior",
        "",
    ]
    copy_rows = [
        [scenario_id, record.get("execution_id"), record.get("status"), why]
        for scenario_id, status in state.statuses.items() for record, why in status.ineligible
    ]
    if copy_rows:
        lines += [
            "These imported UniKix records exercise behaviour the UniKix copy changes, or did not run the zip "
            "as committed. They are reported here and never verify an `app/` item.",
            "",
            *_table(("Scenario", "Execution", "Record status", "Why it does not count"), copy_rows,
                    text_columns=range(4)),
            "",
        ]
    else:
        lines += ["No imported UniKix record exercises behaviour that the UniKix copy changes.", ""]
    met, reason = proof
    lines += [
        "## Job-chain proof",
        "",
        f"{'met' if met else 'unmet'}: {reason}",
        "",
        "## Register summary",
        "",
    ]
    by_category = {category: Counter() for category in CATEGORY_ORDER}
    for entry in state.gaps.entries.values():
        by_category.setdefault(str(entry.get("category")), Counter())[str(entry.get("status"))] += 1
    register_rows = [
        [category, tally.get("open", 0), tally.get("closed", 0), sum(tally.values())]
        for category, tally in by_category.items()
    ]
    register_rows.append([
        "total", len(open_gaps), sum(row[2] for row in register_rows), len(state.gaps.entries),
    ])
    claim = verified == total and total > 0 and not open_gaps
    lines += [
        *_table(("Category", "open", "closed", "total"), register_rows),
        "",
        "## 100% claim",
        "",
        f"100% may be claimed: {'yes' if claim else 'no'} ({verified} of {total} items verified; "
        f"{len(open_gaps)} register entries open)",
        "",
        "## Inventory completeness",
        "",
        *_COMPLETENESS_RULES,
        "",
        "Changes since the freeze:",
        "",
    ]
    changes = state.header.get("changes") or []
    if changes:
        lines += [
            f"- {change.get('date')} {change.get('inventory_id')}: {_cell(change.get('reason'))}"
            if isinstance(change, Mapping) else f"- {_cell(change)}"
            for change in changes
        ]
    else:
        lines.append(f"- none; the frozen total {header_total} stands")
    lines += ["", "Observed screen messages that match no inventory item:", ""]
    unmatched = unmatched_messages(state)
    if unmatched:
        lines += [
            f"- {message.execution_id}/{message.scenario_id} step {message.step} {message.field}: "
            f"{_cell(_clip(' '.join(message.text.split()), 200))}"
            for message in unmatched
        ]
    elif _evidence_executions():
        lines.append("- none in the imported UniKix evidence")
    else:
        lines.append(f"- none observed yet: no UniKix evidence exists, so {GAP_COMPLETENESS} stays open until a "
                     "full unikix run reports no unmatched message")
    lines += ["", _ENVIRONMENT_HEADING.strip(), "", *_environment_lines(), ""]
    return "\n".join(lines)


def render(state: State) -> dict[Path, str]:
    """The text of the three report files."""
    return {
        MATRIX_FILE: render_matrix(state),
        REGISTER_FILE: render_register(state),
        REPORT_FILE: render_report(state),
    }


def _refuse_links(*paths: Path) -> None:
    for path in paths:
        if path.is_symlink():
            raise ReportError(f"{display_path(path)} is a symbolic link; nothing is written through it")


def _write_inventory(state: State) -> bool:
    return _inventory.write({"header": state.header, "items": state.items})


def regenerate() -> State:
    """Rewrite the coverage matrix, the gap register and the coverage report,
    and the ``scenario_ids``, ``status`` and ``gap_ids`` of every inventory
    item. Unchanged files are left untouched. Returns the state reported."""
    _refuse_links(REPORTS_DIR.parent, REPORTS_DIR, MATRIX_FILE, REGISTER_FILE, REPORT_FILE)
    state = build_state()
    _write_inventory(state)
    for path, text in render(state).items():
        write_text(path, text)
    return state


# --------------------------------------------------------------------------
# Checks
# --------------------------------------------------------------------------


def _page_zero_detail() -> str:
    try:
        environment = _results.load_environment()
    except HarnessError:
        return ""
    probe = environment.get("page0_probe") if environment else None
    if isinstance(probe, Mapping) and probe.get("detail"):
        return f" ({_clip(str(probe['detail']).strip(), 300)})"
    return ""


def _job_item(state: State, job: str) -> str | None:
    for item in state.items:
        if item.get("kind") == "job" and (str(item.get("name") or "").split() or [""])[0] == job:
            return item["id"]
    return None


def check_proof(state: State | None = None) -> tuple[bool, str]:
    """The job-chain proof: ``BAT-POSTINT-normal-01`` and
    ``BAT-CHAIN-normal-01`` both passed on current records, each asserts its
    business outcomes (return code, messages, reject reasons and the datasets
    of the chain), and the BAT items they list and the POSTTRAN and INTCALC
    job items are verified. Returns whether it is met and why."""
    state = state if state is not None else build_state()
    reasons: list[str] = []
    required: set[str] = set()
    for scenario_id in PROOF_SCENARIOS:
        scenario = state.scenarios.get(scenario_id)
        if scenario is None:
            reasons.append(f"{scenario_id} has no scenario file")
            continue
        status = state.statuses[scenario_id]
        if status.status == _results.STATUS_BLOCKED and status.gap_id == GAP_PAGE_ZERO:
            reasons.append(f"{scenario_id} is blocked under {GAP_PAGE_ZERO}: the page-zero probe failed"
                           f"{_page_zero_detail()}")
        elif status.status == _results.STATUS_FAILING:
            reasons.append(f"{scenario_id} is failing ({status.reason}); its result record and the runtime "
                           "detail name the failing assertion")
        elif status.status != _results.STATUS_PASSED:
            gap = f", {status.gap_id}" if status.gap_id else ""
            reasons.append(f"{scenario_id} is {status.status} ({status.reason}{gap})")
        asserted = set(_scenarios.asserted_keys(scenario))
        sections, datasets = PROOF_REQUIREMENTS[scenario_id]
        missing = [section for section in sections if section not in asserted]
        missing += [
            name for name in datasets
            if f"datasets.{name}" not in asserted and f"file_state.{name}" not in asserted
        ]
        if missing:
            reasons.append(f"{scenario_id} does not assert {', '.join(missing)}")
        required.update(str(item_id) for item_id in scenario.inventory_ids if str(item_id).startswith("INV-BAT-"))
    for job in PROOF_JOBS:
        item_id = _job_item(state, job)
        if item_id is None:
            reasons.append(f"the inventory has no {job} job item")
        else:
            required.add(item_id)
    unverified = sorted(item_id for item_id in required if state.item_status.get(item_id) != VERIFIED)
    if unverified:
        reasons.append("not verified: " + ", ".join(
            f"{item_id} ({state.item_status.get(item_id, 'not in the inventory')})" for item_id in unverified
        ))
    if reasons:
        return False, "; ".join(reasons)
    return True, (f"{' and '.join(PROOF_SCENARIOS)} passed on current records with their business outcomes "
                  f"asserted, and {', '.join(sorted(required))} are verified")


def check_seams(state: State | None = None) -> list[str]:
    """The seam rule: every seam of the gnucobol-batch adapter has its
    ``stub-limitation`` entry, and a closed one names an ``execution_id``
    whose evidence directory exists under ``tests/reports/unikix/`` with a
    current, passed, eligible ``validation.yaml`` for the cited scenario."""
    state = state if state is not None else build_state()
    problems = []
    for seam, gap_id in SEAMS:
        entry = state.gaps.get(gap_id)
        if entry is None:
            problems.append(f"{seam}: no register entry {gap_id}")
            continue
        if entry.get("category") != "stub-limitation":
            problems.append(f"{gap_id} ({seam}): category {entry.get('category')!r} is not stub-limitation")
        if entry.get("status") != "closed":
            continue
        evidence = entry.get("resolving_evidence")
        execution_id = evidence.get("execution_id") if isinstance(evidence, Mapping) else None
        scenario_id = evidence.get("scenario_id") if isinstance(evidence, Mapping) else None
        if not isinstance(execution_id, str) or not _UNIKIX_ID_RE.match(execution_id):
            problems.append(f"{gap_id} is closed without the execution_id of a UniKix run")
            continue
        if not (UNIKIX_EVIDENCE_DIR / execution_id).is_dir():
            problems.append(f"{gap_id}: no evidence directory tests/reports/unikix/{execution_id}")
            continue
        if not isinstance(scenario_id, str):
            problems.append(f"{gap_id}: resolving_evidence names no scenario_id")
            continue
        found = [entry for entry in state.unikix_records
                 if entry.execution_id == execution_id and entry.scenario_id == scenario_id]
        scenario = state.scenarios.get(scenario_id)
        if not found or scenario is None:
            problems.append(f"{gap_id}: {execution_id} holds no valid {VALIDATION_FILE} for {scenario_id}")
            continue
        record = found[0].record
        baseline, problem = _load_baseline(scenario_id)
        if problem is not None or not _results.is_current(record, scenario, baseline):
            problems.append(f"{gap_id}: the {VALIDATION_FILE} of {scenario_id} in {execution_id} is not current")
        elif record.get("status") != _results.STATUS_PASSED:
            problems.append(f"{gap_id}: the {VALIDATION_FILE} of {scenario_id} in {execution_id} is "
                            f"{record.get('status')}, not passed")
        else:
            why = _ineligibility(record, scenario, state.copy_gaps, state.zip_info)
            if why is not None:
                problems.append(f"{gap_id}: the record of {scenario_id} in {execution_id} is not eligible: {why}")
    return problems


def _git(args: Sequence[str]) -> subprocess.CompletedProcess | None:
    return _run(["git", *args])


def check_protected(base: str | None = None) -> list[str]:
    """No change under ``app/``, ``samples/`` or ``diagrams/``: ``git diff``
    against the inventory header's ``protected_base`` (the starting commit)
    is empty, and so is ``git status`` of those paths."""
    if base is None:
        try:
            base = _inventory.load()["header"].get("protected_base") or PROTECTED_BASE
        except HarnessError:
            base = PROTECTED_BASE
    paths = ("app", "samples", "diagrams")
    verify = _git(["rev-parse", "--verify", "--quiet", f"{base}^{{commit}}"])
    if verify is None:
        return ["git cannot be run, so the protected tree cannot be checked"]
    if verify.returncode != 0:
        return [f"the protected base {base} is not in this clone's history; fetch the full history "
                "(actions/checkout fetch-depth: 0) and check again"]
    findings = []
    diff = _git(["diff", "--exit-code", "--name-status", base, "--", *paths])
    if diff is None or diff.returncode not in (0, 1):
        detail = _clip(diff.stderr.strip(), 500) if diff is not None else "git cannot be run"
        findings.append(f"git diff against {base} failed: {detail}")
    elif diff.returncode == 1 or diff.stdout.strip():
        changed = [line for line in diff.stdout.splitlines() if line.strip()]
        findings.append(f"changed since {base} under app/, samples/ or diagrams/: " + "; ".join(changed[:50]))
    status = _git(["status", "--porcelain", "--untracked-files=all", "--", *paths])
    if status is None or status.returncode != 0:
        detail = _clip(status.stderr.strip(), 500) if status is not None else "git cannot be run"
        findings.append(f"git status failed: {detail}")
    elif status.stdout.strip():
        changed = [line for line in status.stdout.splitlines() if line.strip()]
        findings.append("working-tree changes under app/, samples/ or diagrams/: " + "; ".join(changed[:50]))
    return findings


def referenced_gap_ids(tests_root: str | Path = TESTS_ROOT) -> dict[str, list[str]]:
    """Every gap id cited in ``tests/**/*.py`` and ``tests/**/*.yaml``
    outside ``tests/reports/``, with the files citing it."""
    root = Path(tests_root)
    reports = root / "reports"
    found: dict[str, set[str]] = {}
    for path in sorted(root.rglob("*")):
        if path.suffix not in (".py", ".yaml") or path.is_symlink() or not path.is_file():
            continue
        if reports in path.parents or "__pycache__" in path.parts:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in GAP_ID_RE.finditer(text):
            found.setdefault(match.group(), set()).add(display_path(path))
    return {gap_id: sorted(files) for gap_id, files in sorted(found.items())}


def _record_gap_ids() -> dict[str, list[str]]:
    """The gap ids the stored records cite: the gnucobol-batch records and
    the UniKix ``validation.yaml`` and ``result.yaml`` files."""
    found: dict[str, set[str]] = {}
    paths = sorted(_results.RESULTS_DIR.glob("*.yaml")) if _results.RESULTS_DIR.is_dir() else []
    for execution_dir in _evidence_executions():
        for scenario_dir in _scenario_dirs(execution_dir):
            paths += [scenario_dir / VALIDATION_FILE, scenario_dir / RESULT_FILE]
    for path in paths:
        if path.is_symlink() or not path.is_file():
            continue
        for match in GAP_ID_RE.finditer(path.read_text(encoding="utf-8", errors="replace")):
            found.setdefault(match.group(), set()).add(display_path(path))
    return {gap_id: sorted(files) for gap_id, files in sorted(found.items())}


def _matrix_findings(state: State) -> list[str]:
    if not MATRIX_FILE.is_file():
        return [f"{display_path(MATRIX_FILE)} does not exist; run python tests/harness/report.py"]
    text = MATRIX_FILE.read_text(encoding="utf-8")
    # The assertions cell of an item many scenarios list (a program, map or
    # copybook) outgrows csv's default field limit of 128 KiB.
    limit = csv.field_size_limit()
    csv.field_size_limit(max(limit, len(text)))
    try:
        rows = list(csv.reader(io.StringIO(text)))
    finally:
        csv.field_size_limit(limit)
    if not rows or tuple(rows[0]) != MATRIX_HEADER:
        return [f"{display_path(MATRIX_FILE)}: the header is not {','.join(MATRIX_HEADER)}"]
    ids = [row[0] for row in rows[1:] if row]
    findings = []
    duplicates = sorted(item_id for item_id, count in Counter(ids).items() if count > 1)
    if duplicates:
        findings.append(f"coverage matrix: duplicate rows for {', '.join(duplicates)}")
    inventory_ids = {item["id"] for item in state.items}
    missing, extra = sorted(inventory_ids - set(ids)), sorted(set(ids) - inventory_ids)
    if missing:
        findings.append(f"coverage matrix: no row for {len(missing)} inventory items: {', '.join(missing[:20])}")
    if extra:
        findings.append(f"coverage matrix: rows for ids outside the inventory: {', '.join(extra[:20])}")
    return findings


def check(state: State | None = None) -> list[str]:
    """Every finding of the register and matrix rules; empty when they hold:
    one matrix row per inventory item, an open register entry for every item
    that is not verified, status counts adding up to the header total, every
    cited gap id registered, register entries well formed, the seam rule,
    and report files and inventory fields as ``regenerate`` writes them."""
    state = state if state is not None else build_state()
    findings = list(state.problems)
    for gap_id, entry in state.gaps.entries.items():
        findings.extend(_entry_problems(gap_id, entry))
        listed = entry.get("affected_inventory_ids")
        unknown = sorted(
            item_id for item_id in (listed if isinstance(listed, list) else [])
            if item_id not in state.item_status
        )
        if unknown:
            findings.append(f"{gap_id}: affected_inventory_ids names ids outside the inventory: {', '.join(unknown)}")
    findings.extend(_matrix_findings(state))
    counts = state.counts()
    if sum(counts.values()) != state.header.get("total"):
        findings.append(f"the status counts add up to {sum(counts.values())}, not the header total "
                        f"{state.header.get('total')}")
    lacking = [
        item["id"] for item in state.items
        if item["status"] != VERIFIED and not any(state.gaps.is_open(gap_id) for gap_id in item["gap_ids"])
    ]
    if lacking:
        findings.append(f"{len(lacking)} items that are not verified have no open register entry: "
                        + ", ".join(lacking[:40]) + (" ..." if len(lacking) > 40 else ""))
    cited: dict[str, set[str]] = {}
    for gap_id, files in referenced_gap_ids().items():
        cited.setdefault(gap_id, set()).update(files)
    for gap_id, files in _record_gap_ids().items():
        cited.setdefault(gap_id, set()).update(files)
    for scenario_id, scenario in state.scenarios.items():
        for gap_id in scenario.gap_ids:
            cited.setdefault(str(gap_id), set()).add(f"scenario {scenario_id}")
    for item in state.items:
        for gap_id in item["gap_ids"]:
            cited.setdefault(gap_id, set()).add(f"item {item['id']}")
    for gap_id, where in sorted(cited.items()):
        if gap_id not in state.gaps.entries:
            findings.append(f"{gap_id} is cited ({', '.join(sorted(where)[:5])}) but is not in gaps.yaml")
    findings.extend(check_seams(state))
    for path, text in render(state).items():
        try:
            current = path.read_text(encoding="utf-8")
        except OSError:
            current = None
        if path == REPORT_FILE and current is not None:
            # The Environment section describes the host that ran the check,
            # not the records, so it never makes the report stale.
            current, text = current.split(_ENVIRONMENT_HEADING)[0], text.split(_ENVIRONMENT_HEADING)[0]
        if current != text:
            findings.append(f"{display_path(path)} is not what the current records give; run "
                            "python tests/harness/report.py")
    on_disk = {item["id"]: item for item in _inventory.load()["items"]}
    behind = [
        item["id"] for item in state.items
        if any(on_disk.get(item["id"], {}).get(name) != item[name] for name in _inventory.REPORT_FIELDS)
    ]
    if behind:
        findings.append(f"{len(behind)} inventory items carry report fields the records no longer give "
                        f"({', '.join(behind[:10])}); run python tests/harness/report.py")
    return findings


# --------------------------------------------------------------------------
# Editing curated YAML files without losing their comments
# --------------------------------------------------------------------------

_TOP_KEY_RE = re.compile(r"""^(?:[A-Za-z_@][\w@.-]*|'[^']*'|"[^"]*"):(?:\s|$)""")
_ENTRY_GAP_ID_RE = re.compile(r"""^(?:- |  )gap_id: ['"]?(GAP-[A-Z]{3}-\d{3})['"]?\s*$""")


def _top_level_block(lines: list[str], key: str) -> tuple[int, int] | None:
    """The lines ``[start, end)`` of top-level ``key``'s block; comment and
    blank lines just before the next top-level key belong to that key."""
    pattern = re.compile(rf"^{re.escape(key)}:(?:\s|$)")
    start = next((index for index, line in enumerate(lines) if pattern.match(line)), None)
    if start is None:
        return None
    end = next((index for index in range(start + 1, len(lines)) if _TOP_KEY_RE.match(lines[index])), len(lines))
    while end - 1 > start and (lines[end - 1].startswith("#") or not lines[end - 1].strip()):
        end -= 1
    return start, end


def _verified_text(text: str, wanted: Any, path: Path) -> str:
    try:
        parsed = _yaml_text_load(text)
    except yaml.YAMLError as exc:
        raise ReportError(f"{display_path(path)}: the edited text is not valid YAML: {_clip(exc, 300)}") from None
    if parsed != wanted:
        raise ReportError(f"{display_path(path)}: the edit would not give the intended content; edit it by hand")
    return text


def _without_tag(text: str, tag: str, path: Path) -> str | None:
    """Scenario file ``text`` without ``tag`` in its ``tags`` list, comments
    kept; ``None`` when the file does not carry the tag."""
    data = _yaml_text_load(text)
    tags = data.get("tags") if isinstance(data, dict) else None
    if not isinstance(tags, list) or tag not in tags:
        return None
    wanted = dict(data)
    wanted["tags"] = [entry for entry in tags if entry != tag]
    lines = text.split("\n")
    block = _top_level_block(lines, "tags")
    if block is None:
        raise ReportError(f"{display_path(path)}: no top-level tags block to edit")
    start, end = block
    flow = re.match(r"^tags:\s*\[.*\]\s*(#.*)?$", lines[start])
    if flow:
        lines[start] = "tags: [" + ", ".join(wanted["tags"]) + "]" + (f"  {flow.group(1)}" if flow.group(1) else "")
    else:
        item_re = re.compile(rf"""^\s*-\s*['"]?{re.escape(tag)}['"]?\s*(?:#.*)?$""")
        kept = [line for line in lines[start + 1:end] if not item_re.match(line)]
        if not any(re.match(r"^\s*-\s", line) for line in kept):
            lines[start] = "tags: []"
        lines[start + 1:end] = kept
    return _verified_text("\n".join(lines), wanted, path)


def _with_expected(text: str, expected: Mapping[str, Any], note: str, path: Path) -> str:
    """Scenario file ``text`` with its top-level ``expected`` block replaced
    by ``expected`` (canonical YAML under a ``note`` comment); the rest of
    the file, comments included, is kept."""
    data = _yaml_text_load(text)
    wanted = dict(data)
    wanted["expected"] = dict(expected)
    lines = text.split("\n")
    body = [f"# {note}", *dump_yaml({"expected": dict(expected)}).rstrip("\n").split("\n")]
    block = _top_level_block(lines, "expected")
    if block is None:
        lines = [*lines[:-1], *body, ""] if lines and lines[-1] == "" else [*lines, *body]
    else:
        lines[block[0]:block[1]] = body
    return _verified_text("\n".join(lines), wanted, path)


def _entry_gap_id(lines: Sequence[str], index: int) -> str | None:
    for position in range(index, len(lines)):
        line = lines[position]
        if position > index and (line.startswith("- ") or _TOP_KEY_RE.match(line)):
            return None
        match = _ENTRY_GAP_ID_RE.match(line)
        if match:
            return match.group(1)
    return None


def _comment_blocks(text: str) -> tuple[list[str], dict[tuple[str, str], list[str]]]:
    """The leading comment of ``gaps.yaml`` and every other comment block,
    keyed by the entry (gap id) or top-level key it stands before."""
    lines = text.split("\n")
    index = 0
    header: list[str] = []
    while index < len(lines) and lines[index].startswith("#"):
        header.append(lines[index])
        index += 1
    blocks: dict[tuple[str, str], list[str]] = {}
    pending: list[str] = []
    for position in range(index, len(lines)):
        line = lines[position]
        if line.startswith("#") or not line.strip():
            # Blank lines travel with the block they precede, so the spacing
            # before a top-level key survives a rewrite.
            pending.append(line)
            continue
        if not pending:
            continue
        if line.startswith("- "):
            gap_id = _entry_gap_id(lines, position)
            if gap_id is not None:
                blocks[("entry", gap_id)] = pending
                pending = []
        elif _TOP_KEY_RE.match(line):
            blocks[("key", line.split(":", 1)[0])] = pending
            pending = []
        else:
            # A blank line inside an entry belongs to its value, not to a block.
            pending = [kept for kept in pending if kept.strip()]
    return header, blocks


def _gaps_text(document: Mapping[str, Any], original: str) -> str:
    """``document`` as canonical YAML with the comments of ``original`` put
    back before the entries and keys they annotated."""
    header, blocks = _comment_blocks(original)
    body = dump_yaml(dict(document)).rstrip("\n").split("\n")
    out = list(header)
    for index, line in enumerate(body):
        if line.startswith("- "):
            gap_id = _entry_gap_id(body, index)
            out.extend(blocks.get(("entry", gap_id or ""), []))
        elif _TOP_KEY_RE.match(line):
            out.extend(blocks.get(("key", line.split(":", 1)[0]), []))
        out.append(line)
    return _verified_text("\n".join(out) + "\n", dict(document), GAPS_FILE)


def _gap_list(document: dict) -> list[dict] | dict:
    section = document.get("gaps")
    if not isinstance(section, (list, dict)):
        raise ReportError("gaps.yaml: 'gaps' must be a list of entries or a mapping")
    return section


def _gap_entries(document: dict) -> dict[str, dict]:
    section = _gap_list(document)
    if isinstance(section, dict):
        return {str(gap_id): entry for gap_id, entry in section.items() if isinstance(entry, dict)}
    return {str(_entry_id(entry)): entry for entry in section if isinstance(entry, dict)}


def _add_gap(document: dict, category: str, unknown: str, items: Iterable[str], needed: str) -> str | None:
    """Append an open entry of ``category`` with the next free number and
    return its id; ``None`` when an open entry already asks the question
    ``unknown`` starts with (the text up to its first colon)."""
    entries = _gap_entries(document)
    question = unknown.split(":", 1)[0]
    for entry in entries.values():
        if (entry.get("category") == category and entry.get("status") == "open"
                and str(entry.get("unknown", "")).split(":", 1)[0] == question):
            return None
    prefix = next(code for code, name in CATEGORIES.items() if name == category)
    numbers = [
        int(match.group(2)) for match in map(_GAP_ID_FULL_RE.match, entries) if match and match.group(1) == prefix
    ]
    gap_id = f"GAP-{prefix}-{max(numbers, default=0) + 1:03d}"
    entry = {
        "affected_inventory_ids": sorted(set(items)),
        "category": category,
        "gap_id": gap_id,
        "needed": needed,
        "resolving_evidence": None,
        "status": "open",
        "unknown": unknown,
    }
    section = _gap_list(document)
    if isinstance(section, dict):
        section[gap_id] = {key: value for key, value in entry.items() if key != "gap_id"}
    else:
        section.append(entry)
    return gap_id


# --------------------------------------------------------------------------
# UniKix evidence: reading, reconstruction, comparison
# --------------------------------------------------------------------------

# How evidence.py names the .bin file of a reserved observation.
_OBSERVATION_FILES = {"_CATALOG": "@CATALOG", "_REGION-FILES": "@REGION-FILES", "_TDQ_JOBS": "@TDQ:JOBS"}
_SCREEN_DEFAULT_FORMATS = {"CURDATE": "%m/%d/%y", "CURTIME": "%H:%M:%S"}


@dataclass
class Evidence:
    """One scenario's evidence of one UniKix execution."""

    execution_id: str
    scenario_id: str
    directory: Path
    raw: dict  # result.yaml
    environment: dict
    datasets: dict  # observation or logical name -> bytes

    @property
    def path(self) -> str:
        return f"{display_path(UNIKIX_EVIDENCE_DIR)}/{self.execution_id}/{self.scenario_id}"

    @property
    def observed(self) -> Mapping[str, Any] | None:
        """``result.yaml``'s ``observed``: the return code, messages and
        screens of the run's ``Result`` (its datasets are ``datasets/``)."""
        observed = self.raw.get("observed")
        return observed if isinstance(observed, Mapping) else None

    @property
    def ran(self) -> bool:
        decoded = self.raw.get("decoded")
        return (self.raw.get("status") in (_results.STATUS_PASSED, _results.STATUS_FAILING)
                and (self.observed is not None or (isinstance(decoded, Mapping) and bool(decoded))))


def _read_environment(scenario_dir: Path) -> tuple[dict | None, str | None]:
    """The ``environment.yaml`` of a scenario's evidence: the one in its own
    directory, where ``adapters/unikix/evidence.py`` writes it, else one
    beside the scenario directories that covers the whole execution."""
    path = scenario_dir / ENVIRONMENT_FILE
    if not path.exists() and not path.is_symlink():
        path = scenario_dir.parent / ENVIRONMENT_FILE
    if path.is_symlink() or not path.is_file():
        return None, f"no {ENVIRONMENT_FILE} in {display_path(scenario_dir)} or its execution directory"
    try:
        document = load_yaml(path)
    except YAMLLoadError as exc:
        return None, str(exc)
    if not isinstance(document, Mapping):
        return None, f"{display_path(path)} does not hold a mapping"
    run_date = document.get("run_date")
    if run_date is not None and not isinstance(run_date, Mapping):
        return None, f"{display_path(path)}: run_date must map bpe_job_start_date and first_step_screen_date"
    return dict(document), None


def _evidence_datasets(scenario_dir: Path, raw: Mapping[str, Any]) -> dict[str, bytes]:
    folder = scenario_dir / EVIDENCE_DATASETS_DIR
    if folder.is_symlink() or not folder.is_dir():
        return {}
    summary = raw.get("capture_summary")
    names = summary.get("datasets") if isinstance(summary, Mapping) else None
    names = names if isinstance(names, Mapping) else {}
    datasets = {}
    for entry in sorted(folder.iterdir()):
        if entry.is_symlink() or not entry.is_file() or entry.suffix != ".bin":
            continue
        name = names.get(entry.name, names.get(entry.stem))
        if not isinstance(name, str):
            name = _OBSERVATION_FILES.get(entry.stem, entry.stem)
            if name.startswith("_"):
                continue  # an @AIX:<path> file whose name the summary does not give back
        datasets[name] = entry.read_bytes()
    return datasets


def read_evidence(execution_id: str, scenario_dir: Path,
                  zip_info: tuple[str, dict] | None) -> tuple[Evidence | None, str | None]:
    """The evidence of one scenario directory, or why it is rejected: an id
    outside the manifest, no complete ``result.yaml``, an ``environment.yaml``
    that is missing or unreadable, a record that breaks the result contract
    or names another execution, scenario or adapter, or a source identity
    that is not the zip's UniKix copy as committed."""
    scenario_id = scenario_dir.name
    if scenario_id not in _scenarios.ALL_IDS:
        return None, f"{scenario_id} is not an id of the closed scenario id manifest"
    path = scenario_dir / RESULT_FILE
    if path.is_symlink() or not path.is_file():
        return None, f"no {RESULT_FILE}: the evidence is incomplete"
    environment, problem = _read_environment(scenario_dir)
    if environment is None:
        return None, f"environment: {problem}"
    try:
        raw = load_yaml(path)
    except YAMLLoadError as exc:
        return None, str(exc)
    if not isinstance(raw, Mapping):
        return None, f"{RESULT_FILE} does not hold a mapping"
    record = {name: raw.get(name) for name in _results.RECORD_FIELDS}
    problems = _results.record_problems(record)
    if record["adapter"] != _results.UNIKIX:
        problems.append(f"adapter {record['adapter']!r} is not unikix")
    if record["execution_id"] != execution_id:
        problems.append(f"execution_id {record['execution_id']!r} is not {execution_id}")
    if record["scenario_id"] != scenario_id:
        problems.append(f"scenario_id {record['scenario_id']!r} is not {scenario_id}")
    if problems:
        return None, f"{RESULT_FILE}: " + "; ".join(problems)
    problem = identity_problem(record["source_identity"], zip_info)
    if problem is not None:
        return None, f"source identity: {problem}"
    return Evidence(execution_id, scenario_id, scenario_dir, dict(raw), environment,
                    _evidence_datasets(scenario_dir, raw)), None


def _date_text(value: Any) -> str | None:
    if isinstance(value, _dt.datetime):
        return value.date().isoformat()
    if isinstance(value, _dt.date):
        return value.isoformat()
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip()
    for form in ("%Y-%m-%d", "%m/%d/%y", "%m/%d/%Y", "%Y%m%d"):
        try:
            return _dt.datetime.strptime(text[:10] if form == "%Y-%m-%d" else text, form).date().isoformat()
        except ValueError:
            continue
    return None


def evidence_run_date(environment: Mapping[str, Any], scenario: Any) -> str | None:
    """The run date ``environment.yaml`` records for the scenario's kind of
    run: the screen date of the first online step, or the BPE job start date."""
    run_date = environment.get("run_date")
    if not isinstance(run_date, Mapping):
        return None
    online = isinstance(scenario.inputs.get("steps"), list)
    keys = ("first_step_screen_date", "bpe_job_start_date")
    if not online:
        keys = keys[::-1]
    for key in keys:
        text = _date_text(run_date.get(key))
        if text is not None:
            return text
    return None


def _step_matcher(scenario: Any, baseline: Any, index: int, name: str) -> Mapping[str, Any] | None:
    for expected in (baseline.expected if baseline is not None else {}, scenario.expected):
        screens = expected.get("screens") if isinstance(expected, Mapping) else None
        step = screens[index] if isinstance(screens, list) and index < len(screens) else None
        if isinstance(step, Mapping) and _baselines.is_matcher(step.get(name)):
            return step[name]
    for expected in (baseline.expected if baseline is not None else {}, scenario.expected):
        matchers = expected.get("matchers") if isinstance(expected, Mapping) else None
        if isinstance(matchers, Mapping) and _baselines.is_matcher(matchers.get(name)):
            return matchers[name]
    return None


def evidence_result(evidence: Evidence, scenario: Any, baseline: Any, run_date: str | None) -> Result:
    """The ``adapters.base.Result`` an execution produced, rebuilt from its
    evidence: the return code, messages and screens ``result.yaml`` keeps
    under ``observed``, and the raw bytes of every dataset file.

    Evidence without ``observed`` is rebuilt from its decoded outcome
    instead. There a screen field held as a matcher kind took its value from
    the region clock (the run clock, whose date the evidence records as the
    run date), so it is rendered from that date: the evidence keeps no other
    trace of it."""
    observed = evidence.observed
    if observed is not None:
        code = observed.get("return_code")
        return Result(
            return_code=code if isinstance(code, int) and not isinstance(code, bool) else None,
            datasets=dict(evidence.datasets),
            screens=[{str(name): value for name, value in screen.items()}
                     for screen in observed.get("screens") or [] if isinstance(screen, Mapping)],
            messages=[line for line in observed.get("messages") or [] if isinstance(line, str)],
        )
    decoded = evidence.raw.get("decoded") or {}
    code = decoded.get("return_code")
    return_code = code if isinstance(code, int) and not isinstance(code, bool) else None
    messages = [line for line in decoded.get("messages") or [] if isinstance(line, str)]
    moment = None
    if run_date is not None:
        moment = _dt.datetime.combine(_dt.date.fromisoformat(run_date), _dt.time())
    screens = []
    for index, screen in enumerate(decoded.get("screens") or []):
        if not isinstance(screen, Mapping) or _baselines.MISSING in screen:
            break
        rebuilt = {}
        for name, value in screen.items():
            if not isinstance(value, str):
                continue
            matcher = _step_matcher(scenario, baseline, index, str(name))
            kind = _baselines.SCREEN_CLOCK_FIELDS.get(str(name))
            if moment is not None and matcher is not None and value == matcher.get("match"):
                value = _baselines.Matcher.of(matcher).resolve(moment)
            elif moment is not None and kind is not None and value == kind:
                value = moment.strftime(_SCREEN_DEFAULT_FORMATS[str(name)])
            rebuilt[str(name)] = value
        screens.append(rebuilt)
    return Result(return_code=return_code, datasets=dict(evidence.datasets), screens=screens, messages=messages)


def missing_observations(evidence: Evidence, scenario: Any) -> list[str]:
    """What the scenario's current ``expected`` names that the evidence does
    not hold."""
    expected = scenario.expected
    outcome = evidence.observed or evidence.raw.get("decoded") or {}
    missing = []
    if "return_code" in expected and "return_code" not in outcome:
        missing.append("return_code")
    if "messages" in expected and not isinstance(outcome.get("messages"), list):
        missing.append("messages")
    if "reject_reason_codes" in expected and _baselines.REJECTS_DATASET not in evidence.datasets:
        missing.append("reject_reason_codes")
    for section in ("datasets", "file_state"):
        names = expected.get(section)
        for name in names if isinstance(names, Mapping) else ():
            if str(name) not in evidence.datasets:
                missing.append(f"{section}.{name}")
    wanted = expected.get("screens")
    if isinstance(wanted, list) and wanted:
        captured = outcome.get("screens")
        captured = captured if isinstance(captured, list) else []
        complete = [screen for screen in captured[:len(wanted)]
                    if isinstance(screen, Mapping) and _baselines.MISSING not in screen]
        if len(complete) < len(wanted):
            missing.append(f"screens ({len(complete)} of {len(wanted)} steps)")
    return missing


def current_fingerprint(scenario: Any) -> tuple[str | None, str | None]:
    """The scenario's execution fingerprint on unikix as it stands, or why
    it cannot be computed."""
    try:
        materialized = _fixtures.build(scenario)
        return _results.execution_fingerprint(scenario, materialized, _results.UNIKIX), None
    except (HarnessError, AdapterError) as exc:
        return None, _clip(exc, 300)


def _validation(evidence: Evidence, scenario: Any, baseline: Any, status: str, reason: str | None,
                gap_id: str | None, decoded: Mapping[str, Any] | None) -> dict:
    record = {
        "scenario_id": evidence.scenario_id,
        "adapter": _results.UNIKIX,
        "execution_id": evidence.execution_id,
        "status": status,
        "reason": reason,
        "gap_id": gap_id if status in (_results.STATUS_BLOCKED, _results.STATUS_UNEXECUTED) else None,
        "baseline_version": baseline.version if baseline is not None else None,
        "scenario_digest": _scenarios.digest(scenario),
        "source_identity": evidence.raw.get("source_identity"),
        "execution_fingerprint": evidence.raw.get("execution_fingerprint"),
        "decoded": dict(decoded) if decoded is not None else None,
        "evidence_path": evidence.raw.get("evidence_path") or evidence.path,
    }
    problems = _results.record_problems(record)
    if problems:
        raise ReportError(f"{evidence.path}: {VALIDATION_FILE} would break the record contract: "
                          + "; ".join(problems))
    return record


def _write_validation(evidence: Evidence, record: Mapping[str, Any]) -> None:
    path = evidence.directory / VALIDATION_FILE
    _refuse_links(evidence.directory, path)
    write_text(path, dump_yaml(dict(record)))


def _judge(evidence: Evidence, scenario: Any, baseline: Any) -> tuple[str, str | None, str, dict | None]:
    """Compare the evidence's observed payload with the baseline as it stands:
    ``(status, reason, diff, decoded projection)``."""
    run_date = evidence_run_date(evidence.environment, scenario)
    result = evidence_result(evidence, scenario, baseline, run_date)
    status, reason, diff = _baselines.compare(scenario, baseline, result, _results.UNIKIX, run_date)
    try:
        projected = _baselines.projection(_baselines.decode_result(scenario, result), scenario, baseline)
    except HarnessError as exc:
        return _results.STATUS_FAILING, "error", f"{scenario.id}: {exc}", None
    return status, reason, diff, projected


# --------------------------------------------------------------------------
# UniKix import: promotion, divergence, revalidation
# --------------------------------------------------------------------------

_INFERRED_TAG = "inferred-not-observed"
_STUB_PREFIX = "GAP-STB-"


def _named_fields(values: Iterable[Any]) -> list[str] | None:
    names: list[str] = []
    for value in values:
        if not isinstance(value, Mapping):
            return None  # line records (TEXT layouts, line observations) are compared whole
        names.extend(str(name) for name in value if str(name) not in names)
    return names


def _restricted_records(observed: Any, old: Any) -> Any:
    """The observed records restricted to the fields the old expectation of
    the dataset named, with its field matchers carried over."""
    if not isinstance(observed, list):
        return observed
    if isinstance(old, list):
        specs, matchers, spec_form = old, {}, False
    elif isinstance(old, Mapping):
        specs = list(old.get("records") or []) + list(old.get("contains") or [])
        matchers, spec_form = dict(old.get("matchers") or {}), True
    else:
        return observed
    fields = _named_fields(specs)
    records = observed
    if fields:
        records = [
            {name: record[name] for name in fields if name in record} if isinstance(record, Mapping) else record
            for record in observed
        ]
    if spec_form:
        value: dict[str, Any] = {"records": records}
        if matchers:
            value["matchers"] = matchers
        return value
    for record, spec in zip(records, specs):
        if isinstance(record, dict) and isinstance(spec, Mapping):
            record.update({name: spec[name] for name in record if _baselines.is_matcher(spec.get(name))})
    return records


def observed_expectation(scenario: Any, baseline: Any, projected: Mapping[str, Any]) -> dict:
    """The observed outcome as an ``expected.yaml``: exactly the keys the
    scenario's ``expected`` names, the records and screen fields the old
    expectation named, and the matchers it held for clock-derived values."""
    old = baseline.expected if baseline is not None else {}
    inline = scenario.expected
    out: dict[str, Any] = {}
    for key in inline:
        if key == "matchers":
            out[key] = dict(old.get("matchers") or inline.get("matchers") or {})
        elif key in ("return_code", "messages", "reject_reason_codes"):
            out[key] = projected.get(key)
        elif key in ("datasets", "file_state"):
            observed = projected.get(key) or {}
            section = {}
            for name in inline[key] or {}:
                previous = (old.get(key) or {}).get(name)
                if previous is None or previous == _scenarios.BASELINE:
                    previous = (inline.get(key) or {}).get(name)
                section[str(name)] = _restricted_records(observed.get(str(name)), previous)
            out[key] = section
        elif key == "screens":
            captured = projected.get("screens") or []
            steps = []
            for index, screen in enumerate(captured[:len(inline["screens"])]):
                names: list[str] = []
                for spec in (old.get("screens") or [], inline["screens"]):
                    step = spec[index] if index < len(spec) and isinstance(spec[index], Mapping) else {}
                    names.extend(str(name) for name in step if str(name) not in names)
                entry = {}
                for name in names:
                    if name not in screen:
                        continue
                    matcher = _step_matcher(scenario, baseline, index, name)
                    entry[name] = dict(matcher) if matcher is not None and screen[name] == matcher.get("match") \
                        else screen[name]
                steps.append(entry)
            out[key] = steps
    return out


def _inline_expectation(scenario: Any, observed: Mapping[str, Any]) -> dict:
    """The scenario's own ``expected`` after promotion: the observed values,
    except where it defers to the baseline."""
    inline = scenario.expected
    out: dict[str, Any] = {}
    for key, value in observed.items():
        if inline.get(key) == _scenarios.BASELINE:
            out[key] = _scenarios.BASELINE
        elif key in ("datasets", "file_state") and isinstance(inline.get(key), Mapping):
            out[key] = {
                name: _scenarios.BASELINE if inline[key].get(name) == _scenarios.BASELINE else records
                for name, records in value.items()
            }
        else:
            out[key] = value
    return out


def _business_datasets(expected: Mapping[str, Any]) -> list[str]:
    return sorted({
        str(name) for section in ("datasets", "file_state")
        for name in (expected.get(section) or {}) if not str(name).startswith("@")
    })


def _observed_manifest(baseline: Any, evidence: Evidence, level: str) -> dict:
    manifest = {
        "baseline_version": _baselines.bump(baseline.version, level),
        "source_commit": git_head(),
        "capture_platform": _results.UNIKIX,
        "capture_date": _dt.date.today().isoformat(),
        "source_identity": evidence.raw.get("source_identity"),
        "execution_id": evidence.execution_id,
        "execution_fingerprint": evidence.raw.get("execution_fingerprint"),
    }
    return manifest


@dataclass
class ImportSummary:
    """What one import did, for the operator."""

    execution_id: str
    validated: list = field(default_factory=list)  # (scenario id, status, reason)
    rejected: list = field(default_factory=list)  # (scenario id or '-', why)
    skipped: list = field(default_factory=list)  # (scenario id, why nothing was written)
    promoted: list = field(default_factory=list)  # (scenario id, old version, new version, how)
    gaps_added: list = field(default_factory=list)
    gaps_changed: list = field(default_factory=list)
    items_added: list = field(default_factory=list)

    def lines(self) -> list[str]:
        out = [f"import of {self.execution_id}:"]
        out += [
            f"  validated {sid}: {status}" + (f" ({reason})" if reason else "")
            for sid, status, reason in self.validated
        ]
        out += [f"  promoted {sid}: {old} -> {new} ({how})" for sid, old, new, how in self.promoted]
        out += [f"  rejected {sid}: {why}" for sid, why in self.rejected]
        out += [f"  skipped {sid}: {why}" for sid, why in self.skipped]
        out += [f"  gap added {gap_id}" for gap_id in self.gaps_added]
        out += [f"  gap updated {text}" for text in self.gaps_changed]
        out += [f"  inventory item added {item_id}" for item_id in self.items_added]
        return out


def _promote(evidence: Evidence, scenario: Any, baseline: Any, status: str, diff: str,
             projected: Mapping[str, Any], document: dict, summary: ImportSummary) -> None:
    """Replace a derived baseline by the eligible observed outcome. Every new
    text is built and checked before anything is written."""
    scenario_id = scenario.id
    if status == _results.STATUS_PASSED:
        expected, level, how = dict(baseline.expected), "minor", "observed outcome equals the derived one"
    else:
        expected = observed_expectation(scenario, baseline, projected)
        level, how = "major", "observed outcome replaces the derived one"
    datasets = {name: evidence.datasets[name] for name in _business_datasets(expected) if name in evidence.datasets}
    manifest = _observed_manifest(baseline, evidence, level)
    _baselines.Baseline(scenario_id, expected, manifest, datasets)  # raises BaselineError before any write
    original = scenario.path.read_text(encoding="utf-8")
    text = original
    if level == "major":
        note = (f"Expected outcome replaced by the observed outcome of UniKix execution {evidence.execution_id} "
                f"(baseline {manifest['baseline_version']}).")
        text = _with_expected(text, _inline_expectation(scenario, expected), note, scenario.path)
    text = _without_tag(text, _INFERRED_TAG, scenario.path) or text
    _refuse_links(scenario.path)
    _baselines.write(scenario_id, expected, manifest, datasets)
    if text != original:
        write_text(scenario.path, text)
    summary.promoted.append((scenario_id, baseline.version, manifest["baseline_version"], how))
    if level == "major":
        from_spec = baseline.manifest.get("derived_from") == "tech-spec"
        category = "doc-conflict" if from_spec else "uncertain-purpose"
        unknown = (f"Observed outcome of {scenario_id} differs from its derived expectation: UniKix execution "
                   f"{evidence.execution_id} observed what the baseline now holds, where the derived "
                   f"expectation{' taken from the tech spec' if from_spec else ''} said otherwise. "
                   f"{_clip(diff)}")
        needed = ("The documentation owner's decision on which source is authoritative."
                  if from_spec else "The owner's statement of which outcome is intended.")
        gap_id = _add_gap(document, category, unknown, map(str, scenario.inventory_ids), needed)
        if gap_id:
            summary.gaps_added.append(gap_id)


def _validate_evidence(evidence: Evidence, scenario: Any, document: dict | None,
                       summary: ImportSummary | None, copy_gaps: _CopyGaps,
                       zip_info: tuple[str, dict] | None) -> tuple[str, str | None] | None:
    """Write the ``validation.yaml`` of one evidence directory; with a
    ``document`` (import), promote an eligible derived baseline and register
    a divergence first. Returns the status written, or ``None`` when nothing
    is written because the evidence no longer describes the scenario."""
    raw = evidence.raw
    fingerprint = raw.get("execution_fingerprint")
    if fingerprint is not None:
        current, problem = current_fingerprint(scenario)
        if current != fingerprint:
            why = problem or "the scenario's inputs or preconditions changed since this execution"
            if summary is not None:
                summary.skipped.append((scenario.id, f"stale: {why}"))
            return None
    elif document is None:
        if summary is not None:
            summary.skipped.append((scenario.id, "no execution fingerprint to confirm"))
        return None
    baseline, problem = _load_baseline(scenario.id)
    if problem is not None:
        raise ReportError(f"{scenario.id}: the baseline cannot be read: {problem}")
    if not evidence.ran:
        record = _validation(evidence, scenario, baseline, str(raw.get("status")), raw.get("reason"),
                             raw.get("gap_id"), None)
        _write_validation(evidence, record)
        return record["status"], record["reason"]
    missing = missing_observations(evidence, scenario)
    if missing:
        if summary is not None:
            summary.skipped.append((scenario.id, f"stale: the evidence lacks {', '.join(missing)}"))
        return None
    eligible = _ineligibility(raw, scenario, copy_gaps, zip_info) is None
    status, reason, diff, projected = _judge(evidence, scenario, baseline)
    if document is not None and summary is not None and eligible and baseline is not None and projected:
        if baseline.derived:
            _promote(evidence, scenario, baseline, status, diff, projected, document, summary)
            scenario = _scenarios.get(scenario.id)
            baseline, problem = _load_baseline(scenario.id)
            if problem is not None:
                raise ReportError(f"{scenario.id}: the promoted baseline cannot be read: {problem}")
            status, reason, diff, projected = _judge(evidence, scenario, baseline)
        elif reason == _baselines.REASON_DIVERGENCE:
            stub = any(str(gap_id).startswith(_STUB_PREFIX) for gap_id in scenario.gap_ids)
            category = "stub-limitation" if stub else "uncertain-purpose"
            unknown = (f"Cross-adapter divergence of {scenario.id}: UniKix execution {evidence.execution_id} "
                       f"gives another decoded outcome than the {baseline.capture_platform} baseline, which "
                       f"stays authoritative. {_clip(diff)}")
            needed = ("An eligible UniKix run that agrees, or the owner's decision whether the stub's "
                      "limitation explains the difference." if stub
                      else "The owner's statement of which outcome is intended.")
            gap_id = _add_gap(document, category, unknown, map(str, scenario.inventory_ids), needed)
            if gap_id:
                summary.gaps_added.append(gap_id)
    record = _validation(evidence, scenario, baseline, status, reason, None, projected)
    _write_validation(evidence, record)
    return status, reason


def _versions_recorded(environment: Mapping[str, Any]) -> bool:
    """Whether ``environment.yaml`` names a version for each UniKix
    prerequisite (TPE, BPE, Visual COBOL, licence, Java)."""
    found: dict[str, str] = {}

    def walk(value: Any) -> None:
        if isinstance(value, Mapping):
            for key, item in value.items():
                if str(key) in UNIKIX_VERSION_KEYS and isinstance(item, str):
                    found[str(key)] = item
                walk(item)
        elif isinstance(value, list):
            for item in value:
                walk(item)

    walk(environment)
    return all(
        found.get(key, "").strip() and not found[key].strip().lower().startswith(_VERSION_FAILURE_PREFIXES)
        for key in UNIKIX_VERSION_KEYS
    )


def _new_message_items(state: State, execution_id: str, imported: Iterable[str], summary: ImportSummary) -> None:
    """Add every observed message of the execution that matches no item to
    the inventory, raising its total (GAP-UNC-012)."""
    patterns = list(_patterns_of(state.items))
    added: set[str] = set()
    for scenario_id in sorted(imported):
        scenario = state.scenarios.get(scenario_id)
        path = UNIKIX_EVIDENCE_DIR / execution_id / scenario_id / RESULT_FILE
        try:
            raw = load_yaml(path)
        except (OSError, YAMLLoadError):
            continue
        if scenario is None or not isinstance(raw, Mapping):
            continue
        for message in _observed_messages(path, raw, execution_id, scenario_id):
            if message_items(message.text, message.field, patterns):
                continue
            if message.field == _scenarios.SCREEN_TEXT:
                text = next((line.strip() for line in message.text.split("\n") if line.strip()), "")
            else:
                text = message.text.strip()
            if not text or text in added:
                continue
            added.add(text)
            kind = _inventory.classify_message(_inventory.Message(text=text, origin="literal", position=0,
                                                                  span=("", 0, 0)))
            program = scenario.program_or_job
            item = {
                "area": scenario.area,
                "kind": kind,
                "name": f"{program} observed message '{_clip(text, 160)}'",
                "source": f"{display_path(path)}:{message.line}",
                "description": (f"Observed on UniKix in execution {execution_id}, scenario {scenario_id}, step "
                                f"{message.step} ({message.field}); no extraction rule produced an item for it "
                                f"({GAP_COMPLETENESS})."),
                "level": "e2e",
                "activation": "always",
                "gap_ids": [GAP_COMPLETENESS],
            }
            assigned = _inventory.append_items(
                [item], f"unmatched observed message ({execution_id}/{scenario_id})")
            summary.items_added.extend(assigned)
            patterns.append((assigned[0], _literal_pattern(text)))


def _settle_gaps(document: dict, state: State, execution_id: str, environments: Iterable[Mapping[str, Any]],
                 summary: ImportSummary) -> None:
    """Close or narrow the inferred-not-observed entries the import verified,
    and close GAP-MIS-009 once the installation's versions are recorded."""
    evidence = {"execution_id": execution_id, "evidence_path": f"{display_path(UNIKIX_EVIDENCE_DIR)}/{execution_id}"}
    for gap_id, entry in _gap_entries(document).items():
        if entry.get("category") != "inferred-not-observed" or entry.get("status") != "open":
            continue
        resolved = state.affected.get(gap_id, [])
        unverified = [item_id for item_id in resolved if state.item_status.get(item_id) != VERIFIED]
        if resolved and not unverified:
            entry["status"], entry["resolving_evidence"] = "closed", dict(evidence)
            summary.gaps_changed.append(f"{gap_id}: closed, every affected item verified")
        elif resolved and len(unverified) < len(resolved):
            entry["affected_inventory_ids"] = unverified
            entry.pop("affected", None)
            summary.gaps_changed.append(f"{gap_id}: narrowed to the {len(unverified)} items still unverified")
    installation = _gap_entries(document).get(GAP_NO_INSTALLATION)
    if (installation is not None and installation.get("status") == "open"
            and any(_versions_recorded(environment) for environment in environments)):
        installation["status"], installation["resolving_evidence"] = "closed", dict(evidence)
        summary.gaps_changed.append(f"{GAP_NO_INSTALLATION}: closed, the installation's versions are recorded")


def _write_gaps(document: dict, original: str) -> None:
    _refuse_links(GAPS_FILE)
    write_text(GAPS_FILE, _gaps_text(document, original))


def import_unikix(execution_id: str) -> ImportSummary:
    """Import the evidence of one UniKix execution: check each record's
    source identity and its ``environment.yaml`` (a record that
    fails is reported, not imported), promote each eligible derived baseline
    its scenario ran (MINOR when the observed outcome equals it, MAJOR with a
    register entry when it differs), register cross-adapter divergences,
    write ``validation.yaml`` beside each ``result.yaml``, settle the
    inferred-not-observed entries and GAP-MIS-009, add observed messages no
    item matches to the inventory, and regenerate the reports."""
    if not isinstance(execution_id, str) or not _UNIKIX_ID_RE.match(execution_id):
        raise ReportError(f"{execution_id!r} is not a unikix execution id unikix-<yyyymmddThhmmssZ>-<8 hex>")
    execution_dir = UNIKIX_EVIDENCE_DIR / execution_id
    if execution_dir.is_symlink() or not execution_dir.is_dir():
        raise ReportError(f"no evidence directory {display_path(execution_dir)}")
    summary = ImportSummary(execution_id)
    original = GAPS_FILE.read_text(encoding="utf-8")
    document = load_yaml(GAPS_FILE)
    state = build_state()
    imported = []
    environments = []
    for scenario_dir in _scenario_dirs(execution_dir):
        evidence, why = read_evidence(execution_id, scenario_dir, state.zip_info)
        if evidence is None:
            summary.rejected.append((scenario_dir.name, why))
            continue
        environments.append(evidence.environment)
        scenario = _scenarios.get(evidence.scenario_id, missing_ok=True)
        if scenario is None:
            summary.rejected.append((evidence.scenario_id, "no scenario file"))
            continue
        try:
            written = _validate_evidence(evidence, scenario, document, summary, state.copy_gaps, state.zip_info)
        except HarnessError as exc:
            summary.rejected.append((evidence.scenario_id, str(exc)))
            continue
        if written is not None:
            summary.validated.append((evidence.scenario_id, *written))
            imported.append(evidence.scenario_id)
    _new_message_items(build_state(), execution_id, imported, summary)
    _settle_gaps(document, build_state(), execution_id, environments, summary)
    if document != load_yaml(GAPS_FILE):
        _write_gaps(document, original)
    regenerate()
    return summary


def revalidate() -> list[str]:
    """Re-compare every ``validation.yaml`` whose execution still describes
    its scenario (the current execution fingerprint equals the one in
    ``result.yaml`` and every observation the current ``expected`` names is
    in the evidence) and rewrite it; leave every other one, stale until a new
    run. Then regenerate the reports. Returns what was done, one line each."""
    lines = []
    state = build_state()
    for execution_dir in _evidence_executions():
        for scenario_dir in _scenario_dirs(execution_dir):
            if not (scenario_dir / VALIDATION_FILE).is_file():
                continue
            label = f"{execution_dir.name}/{scenario_dir.name}"
            evidence, why = read_evidence(execution_dir.name, scenario_dir, state.zip_info)
            scenario = _scenarios.get(scenario_dir.name, missing_ok=True) if evidence is not None else None
            if evidence is None or scenario is None:
                lines.append(f"left {label}: {why or 'no scenario file'}")
                continue
            summary = ImportSummary(execution_dir.name)
            try:
                written = _validate_evidence(evidence, scenario, None, summary, state.copy_gaps, state.zip_info)
            except HarnessError as exc:
                lines.append(f"left {label}: {exc}")
                continue
            if written is None:
                lines.append(f"left {label}: " + "; ".join(why for _, why in summary.skipped))
            else:
                lines.append(f"revalidated {label}: {written[0]}" + (f" ({written[1]})" if written[1] else ""))
    regenerate()
    return lines


def _execution_records(execution_id: str) -> dict[str, dict]:
    directory = UNIKIX_EVIDENCE_DIR / execution_id
    if not _UNIKIX_ID_RE.match(execution_id) or directory.is_symlink() or not directory.is_dir():
        raise ReportError(f"no evidence directory {display_path(directory)}")
    records = {}
    for scenario_dir in _scenario_dirs(directory):
        for name in (VALIDATION_FILE, RESULT_FILE):
            path = scenario_dir / name
            if path.is_file() and not path.is_symlink():
                try:
                    record = load_yaml(path)
                except YAMLLoadError as exc:
                    raise ReportError(str(exc)) from None
                if isinstance(record, Mapping):
                    records[scenario_dir.name] = dict(record)
                    break
    return records


def compare_executions(first: str, second: str) -> list[str]:
    """The differences between two UniKix executions, compared through their
    normalized projections: for every scenario both ran, its status and its
    decoded outcome (``validation.yaml``, else ``result.yaml``)."""
    left, right = _execution_records(first), _execution_records(second)
    differences = []
    for scenario_id in sorted(set(left) & set(right)):
        a, b = left[scenario_id], right[scenario_id]
        if a.get("status") != b.get("status"):
            differences.append(f"{scenario_id}: status {a.get('status')} in {first}, {b.get('status')} in {second}")
        decoded_a, decoded_b = a.get("decoded") or {}, b.get("decoded") or {}
        keys = sorted(
            key for key in set(decoded_a) | set(decoded_b) if decoded_a.get(key) != decoded_b.get(key)
        )
        if keys:
            differences.append(f"{scenario_id}: decoded outcome differs in {', '.join(map(str, keys))}")
    return differences


# --------------------------------------------------------------------------
# Command line
# --------------------------------------------------------------------------

_EPILOG = """\
Without an option the matrix, the register, the coverage report and the
inventory's report fields are regenerated from the recorded results. Exit
status: 0 when the action succeeds and finds nothing, 1 when a check finds a
problem, an import rejects a record or two executions differ, 2 when the
action cannot run.
"""


def _print_findings(title: str, findings: Sequence[str]) -> int:
    if not findings:
        print(f"{title}: ok")
        return 0
    print(f"{title}: {len(findings)} finding(s)")
    for finding in findings:
        print(f"  - {finding}")
    return 1


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="report.py",
        description="Regenerate and check the CardDemo functional coverage reports.",
        epilog=_EPILOG,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--check", action="store_true", help="check the register and matrix rules")
    group.add_argument("--check-protected", action="store_true",
                       help="check that nothing under app/, samples/ or diagrams/ changed")
    group.add_argument("--check-proof", action="store_true", help="check the job-chain proof")
    group.add_argument("--import-unikix", metavar="EXECUTION_ID", help="import the evidence of a UniKix execution")
    group.add_argument("--revalidate", action="store_true", help="re-compare imported UniKix evidence")
    group.add_argument("--compare-executions", nargs=2, metavar=("ID1", "ID2"),
                       help="compare two UniKix executions through their normalized projections")
    args = parser.parse_args(argv)
    try:
        if args.check:
            return _print_findings("report check", check())
        if args.check_protected:
            return _print_findings("protected tree", check_protected())
        if args.check_proof:
            met, reason = check_proof()
            print(f"job-chain proof: {'met' if met else 'unmet'}: {reason}")
            return 0 if met else 1
        if args.import_unikix:
            summary = import_unikix(args.import_unikix)
            print("\n".join(summary.lines()))
            return 1 if summary.rejected else 0
        if args.revalidate:
            print("\n".join(revalidate()) or "no validation.yaml to revalidate")
            return 0
        if args.compare_executions:
            first, second = args.compare_executions
            return _print_findings(f"{first} against {second}", compare_executions(first, second))
        state = regenerate()
        counts = state.counts()
        print(f"verified {counts[VERIFIED]} / {state.total} = {_percent(counts[VERIFIED], state.total)}; "
              + ", ".join(f"{status} {counts[status]}" for status in ITEM_STATUSES[1:]))
        return 0
    except HarnessError as exc:
        print(f"report.py: error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
