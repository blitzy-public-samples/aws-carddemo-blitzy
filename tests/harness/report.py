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
    A ``validation.yaml`` is a record only beside that raw evidence: a
    regular ``result.yaml`` and the ``environment.yaml`` of its scenario or
    execution directory; one whose evidence is missing or unreadable is a
    ``--check`` problem and counts nowhere. An imported record is current
    only under the fingerprint rule too: a recorded ``execution_fingerprint``
    must equal the scenario's as it stands (one that cannot be computed
    matches none), and a passed record always carries one by the record
    contract, so a change to what executes (fixture bytes, the effective
    clock, faults, inputs) leaves it stale until a new run, whatever
    ``validation.yaml`` still says; a record without one is decided by its
    version and digest alone. A UniKix record counts only when it is
    *eligible* (``unikix_eligible``): it ran the UniKix copy of the zip as
    committed, its source identity holds the zip's hash of every unit the
    scenario executes (the programs, copybooks, maps, job sources and
    resource lists of its steps or jobs) and of each listed item's UniKix
    counterpart, and none of the scenario's items passes through a part of
    the copy that differs from ``app/`` (GAP-DOC-020 to GAP-DOC-032; the
    ``fct.lst`` items of GAP-DOC-030 count when the record ran the zip's
    ``fct.lst``). Eligibility is judged first: an ineligible record, current
    or stale, is reported as UniKix-copy behaviour, is neither counted nor
    stale, and never verifies an ``app/`` item.

    A scenario is ``failing`` when any counted record fails, else ``passed``
    when any passed, else ``blocked`` when it has no stale record and either
    every counted record is blocked or it has no counted record (no capable
    adapter ran it: reason ``adapter-unavailable``), else
    ``implemented-unexecuted``. A stale record counts as
    ``implemented-unexecuted`` in that decision, and the reason ``stale``
    names only a record that exists. An item takes the worst status of the
    scenarios that list it (failing > blocked > implemented-unexecuted >
    verified); an item no scenario lists is ``not-covered``.

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

The module writes only under ``tests/``: the report files and the
inventory's report fields; on import, baselines, scenario tags and
expectations, ``gaps.yaml``, inventory additions and ``validation.yaml``
beside the UniKix evidence; on revalidation, ``validation.yaml`` and
``gaps.yaml``. The UniKix zip is only read, in memory.
"""

from __future__ import annotations

import argparse
import contextlib
import csv
import datetime as _dt
import fcntl
import fnmatch
import functools
import importlib.metadata
import io
import os
import platform
import re
import stat
import subprocess
import sys
import zipfile
from collections import Counter
from dataclasses import dataclass, field
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path
from typing import Any, Callable, Iterable, Iterator, Mapping, Sequence

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
    "item_statuses",
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
# Matched only with fullmatch: '$' would also accept a name ending in a newline.
_UNIKIX_ID_RE = re.compile(r"unikix-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}")

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
GAP_NOT_RUN = "GAP-MIS-018"
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
    problems.extend(_history_problems(gap_id, entry))
    return problems


def _history_problems(gap_id: str, entry: Mapping[str, Any]) -> list[str]:
    """How an entry's ``narrowed_from`` (the membership an inferred-not-observed
    entry held before it was narrowed) and ``superseded_evidence`` (the
    resolving evidence of earlier closures) break the register contract."""
    problems = []
    narrowed = entry.get("narrowed_from")
    if narrowed is not None:
        if entry.get("category") != CATEGORIES["INF"]:
            problems.append(f"{gap_id}: only an inferred-not-observed entry is narrowed, so only it has narrowed_from")
        if not isinstance(narrowed, Mapping) or set(narrowed) - {"affected_inventory_ids", "affected"}:
            problems.append(f"{gap_id}: narrowed_from must map affected_inventory_ids (and affected, when the entry "
                            "had clauses) to what the entry listed before it was narrowed")
        else:
            ids = narrowed.get("affected_inventory_ids", [])
            if not isinstance(ids, list) or not all(isinstance(item_id, str) for item_id in ids):
                problems.append(f"{gap_id}: narrowed_from.affected_inventory_ids must be a list of inventory ids")
            clauses = narrowed.get("affected", [])
            if not isinstance(clauses, list):
                problems.append(f"{gap_id}: narrowed_from.affected must be a list of selector clauses")
            else:
                for index, clause in enumerate(clauses):
                    problems.extend(f"{gap_id}: narrowed_from.affected[{index}]: {problem}"
                                    for problem in clause_problems(clause))
    superseded = entry.get("superseded_evidence")
    if superseded is not None and (not isinstance(superseded, list)
                                   or not all(isinstance(evidence, (Mapping, str)) for evidence in superseded)):
        problems.append(f"{gap_id}: superseded_evidence must be a list of the resolving_evidence of earlier closures")
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


_EVIDENCE_LINK = "is a symbolic link; UniKix evidence is neither read nor written through it"


def _linked_component(path: Path) -> Path | None:
    """The first component of ``path``, walked one at a time down from the
    resolved ``tests/`` root, that is a symbolic link, else ``None``. A path
    outside that root is only checked itself."""
    try:
        parts = path.relative_to(TESTS_ROOT).parts
    except ValueError:
        return path if path.is_symlink() else None
    current = TESTS_ROOT
    for part in parts:
        current = current / part
        if current.is_symlink():
            return current
    return None


def _evidence_folder(*names: str) -> Path:
    """``tests/reports/unikix/``, or the folder below it that ``names`` lead
    to, checked before any UniKix evidence is read or written there: each
    name is one plain path component, no component from the resolved
    ``tests/`` root down is a symbolic link, and the real path lies inside
    ``tests/reports/unikix/``. Anything else raises ``ReportError`` naming
    the offending name or link. The folder itself need not exist."""
    for name in names:
        if not isinstance(name, str) or name in ("", ".", "..") or "/" in name or "\0" in name:
            raise ReportError(f"{name!r} is not a single folder name under {display_path(UNIKIX_EVIDENCE_DIR)}")
    path = UNIKIX_EVIDENCE_DIR.joinpath(*names)
    try:
        link = _linked_component(path)
        real = os.path.realpath(path)
    except OSError as exc:
        raise ReportError(f"{display_path(path)} cannot be examined: {exc.strerror or exc}") from None
    if link is not None:
        raise ReportError(f"{display_path(link)} {_EVIDENCE_LINK}")
    root = str(UNIKIX_EVIDENCE_DIR)
    if real != root and not real.startswith(root + os.sep):
        raise ReportError(f"{display_path(path)} resolves outside {display_path(UNIKIX_EVIDENCE_DIR)}")
    return path


def _evidence_executions(evidence_dir: Path = UNIKIX_EVIDENCE_DIR, links: list[Path] | None = None) -> list[Path]:
    """The execution directories under ``tests/reports/unikix/``, in name
    order; files (the folder's README) and symbolic links are not executions.
    Nothing is listed, so nothing is read, when a component from the
    ``tests/`` root down (``tests/reports``, ``tests/reports/unikix``) is a
    symbolic link. An entry named as an execution that is a symbolic link is
    appended to ``links`` when it is given, so the caller can report it."""
    if _linked_component(evidence_dir) is not None or not evidence_dir.is_dir():
        return []
    executions = []
    for entry in sorted(evidence_dir.iterdir()):
        if not _UNIKIX_ID_RE.fullmatch(entry.name):
            continue
        if entry.is_symlink():
            if links is not None:
                links.append(entry)
        elif entry.is_dir():
            executions.append(entry)
    return executions


_ENTRY_LINK = "is a symbolic link; evidence is never read through it"


def _scenario_dirs(execution_dir: Path, links: list[Path] | None = None) -> list[Path]:
    """The scenario directories of an execution directory, in name order.
    Evidence is never read through a symbolic link, so a linked entry is
    never one of them; each is appended to ``links`` when it is given, so
    the caller can report it."""
    found = []
    for entry in sorted(execution_dir.iterdir()):
        if entry.is_symlink():
            if links is not None:
                links.append(entry)
        elif entry.is_dir():
            found.append(entry)
    return found


def _unikix_record_problems(record: Any, execution_id: str, scenario_id: str) -> list[str]:
    """Every way the record of the scenario directory ``scenario_id`` of the
    execution directory ``execution_id`` is not a valid UniKix record of it:
    it breaks the result record contract, names another adapter, execution
    or scenario, or the directory is not named by an id of the closed
    scenario id manifest."""
    issues = _results.record_problems(record)
    if isinstance(record, Mapping):
        if record.get("adapter") != _results.UNIKIX:
            issues.append(f"adapter: {record.get('adapter')!r} is not unikix")
        if record.get("execution_id") != execution_id:
            issues.append(f"execution_id: {record.get('execution_id')!r} is not {execution_id}")
        if record.get("scenario_id") != scenario_id:
            issues.append(f"scenario_id: {record.get('scenario_id')!r} is not {scenario_id}")
    if scenario_id not in _scenarios.ALL_IDS:
        issues.append(f"{scenario_id} is not an id of the closed scenario id manifest")
    return issues


# The members of a decoded outcome: harness.baselines.decode_result always
# records the return code and adds the others the scenario asserts.
_DECODED_MEMBERS = ("return_code", "messages", "screens", "reject_reason_codes", "datasets", "file_state")
_MARKER_FORMS = {_baselines.MISSING: '{"@missing": true}', _baselines.ERROR: '{"@error": <text>}'}
_RECORDS_FORMS = 'a list of records, {"@missing": true} or {"@error": <text>}'


def _kind(value: Any) -> str:
    """The kind of a refused decoded value, as a problem names it."""
    return "null" if value is None else type(value).__name__


def _key_text(key: Any) -> str:
    """A key of a decoded outcome as a problem's path shows it: printable
    text as it is, other text as its repr, any other key by its kind."""
    if isinstance(key, str):
        return _clip(key if key.isprintable() else repr(key), 80)
    return f"<{_kind(key)} key>"


def _items_problems(items: list, where: str, wanted: str, kinds: tuple[type, ...]) -> list[str]:
    """The first item of the list ``items`` at ``where`` that is none of
    ``kinds``, with the count of the others like it, so a corrupt list of any
    length is one problem."""
    refused = [index for index, item in enumerate(items) if not isinstance(item, kinds)]
    if not refused:
        return []
    more = f" ({len(refused) - 1} more item(s) like it)" if len(refused) > 1 else ""
    return [f"{where}[{refused[0]}]: must be {wanted}, not {_kind(items[refused[0]])}{more}"]


def _marker_problems(value: Mapping, where: str, markers: tuple[str, ...]) -> list[str]:
    """Why the mapping ``value`` at ``where`` is not exactly one of the
    projection's ``markers``: ``{"@missing": true}`` (nothing was produced)
    or ``{"@error": <text>}`` (it cannot be decoded)."""
    keys = list(value)
    if len(keys) == 1 and keys[0] in markers:
        mark = value[keys[0]]
        if keys[0] == _baselines.MISSING and mark is not True:
            return [f"{where}.{_baselines.MISSING}: must be true"]
        if keys[0] == _baselines.ERROR and not isinstance(mark, str):
            return [f"{where}.{_baselines.ERROR}: must be text, not {_kind(mark)}"]
        return []
    forms = " or ".join(_MARKER_FORMS[marker] for marker in markers)
    held = ", ".join(sorted(_key_text(key) for key in keys)) or "no key"
    return [f"{where}: a mapping here must be exactly {forms}, not one holding {held}"]


def _screens_problems(screens: Any) -> list[str]:
    """Why ``screens`` is not one entry per step: a mapping of field names to
    text (``@map`` and ``@text`` included), or ``{"@missing": true}`` for a
    step without a screen."""
    if not isinstance(screens, list):
        return [f"decoded.screens: must be a list of screens, not {_kind(screens)}"]
    problems = []
    for index, screen in enumerate(screens):
        where = f"decoded.screens[{index}]"
        if not isinstance(screen, Mapping):
            problems.append(f"{where}: must be a mapping of field names to text or "
                            f"{_MARKER_FORMS[_baselines.MISSING]}, not {_kind(screen)}")
        elif _baselines.MISSING in screen:
            problems.extend(_marker_problems(screen, where, (_baselines.MISSING,)))
        else:
            for name, value in screen.items():
                if not isinstance(name, str):
                    problems.append(f"{where}: field name {_key_text(name)} is not text")
                elif not isinstance(value, str):
                    problems.append(f"{where}.{_key_text(name)}: must be text, not {_kind(value)}")
    return problems


def _section_problems(section: Any, where: str) -> list[str]:
    """Why the ``datasets`` or ``file_state`` member at ``where`` does not map
    each dataset or observation name to its records (decoded field maps, or
    lines of text for TEXT layouts and line observations), to
    ``{"@missing": true}`` or to ``{"@error": <text>}``."""
    if not isinstance(section, Mapping):
        return [f"{where}: must be a mapping of dataset names to their records, not {_kind(section)}"]
    problems = []
    for name, value in section.items():
        if not isinstance(name, str) or not name:
            problems.append(f"{where}: {repr(name) if name == '' else _key_text(name)} is not a dataset name")
            continue
        path = f"{where}.{_key_text(name)}"
        if isinstance(value, list):
            problems.extend(_items_problems(value, path, "a record field map or a line of text", (Mapping, str)))
        elif isinstance(value, Mapping):
            problems.extend(_marker_problems(value, path, (_baselines.MISSING, _baselines.ERROR)))
        else:
            problems.append(f"{path}: must be {_RECORDS_FORMS}, not {_kind(value)}")
    return problems


def _decoded_problems(decoded: Any) -> list[str]:
    """Every way the decoded outcome of a record is not the normalized
    projection ``harness.baselines.decode_result`` and ``projection`` give,
    each led by its path (``decoded.messages[1]``). ``null`` (a blocked,
    unexecuted or error record) has none; a value that is not a mapping is
    ``results.record_problems``' to report. A mapping holds ``return_code``
    (an integer or null) and nothing but the members a scenario asserts:
    ``messages`` (a list of text), ``reject_reason_codes`` (a list of text,
    null without DALYREJS, or ``{"@error": <text>}``), ``screens``
    (``_screens_problems``) and ``datasets`` and ``file_state``
    (``_section_problems``). A matcher field holds its kind, which is text,
    so the shape is the same with or without matchers."""
    if not isinstance(decoded, Mapping):
        return []
    problems = []
    unknown = sorted(_key_text(key) for key in decoded if key not in _DECODED_MEMBERS)
    if unknown:
        problems.append(f"decoded: unknown member(s) {', '.join(unknown)}; the members are "
                        f"{', '.join(_DECODED_MEMBERS)}")
    if "return_code" not in decoded:
        problems.append("decoded.return_code: missing; a decoded outcome always records the return code")
    else:
        code = decoded["return_code"]
        if code is not None and (isinstance(code, bool) or not isinstance(code, int)):
            problems.append(f"decoded.return_code: must be an integer or null, not {_kind(code)}")
    if "messages" in decoded:
        messages = decoded["messages"]
        if isinstance(messages, list):
            problems.extend(_items_problems(messages, "decoded.messages", "text", (str,)))
        else:
            problems.append(f"decoded.messages: must be a list of text, not {_kind(messages)}")
    if "reject_reason_codes" in decoded:
        codes, where = decoded["reject_reason_codes"], "decoded.reject_reason_codes"
        if isinstance(codes, list):
            problems.extend(_items_problems(codes, where, "text", (str,)))
        elif isinstance(codes, Mapping):
            problems.extend(_marker_problems(codes, where, (_baselines.ERROR,)))
        elif codes is not None:
            problems.append(f"{where}: must be a list of text, null or {_MARKER_FORMS[_baselines.ERROR]}, "
                            f"not {_kind(codes)}")
    if "screens" in decoded:
        problems.extend(_screens_problems(decoded["screens"]))
    for section in ("datasets", "file_state"):
        if section in decoded:
            problems.extend(_section_problems(decoded[section], f"decoded.{section}"))
    return problems


def load_unikix_records(evidence_dir: Path = UNIKIX_EVIDENCE_DIR) -> tuple[list[UnikixRecord], list[str]]:
    """Every ``validation.yaml`` that keeps the result record contract and
    lies beside its raw evidence (a regular ``result.yaml``, and the
    ``environment.yaml`` the import reads: the scenario directory's, else
    the execution directory's), and the problems of those that do not, which
    ``check`` reports. A symbolic link on the way to the evidence
    (``tests/reports``, ``tests/reports/unikix``), in place of an execution
    or scenario directory or in place of a ``validation.yaml`` is a problem
    too, and nothing is read through it."""
    found: list[UnikixRecord] = []
    problems: list[str] = []
    link = _linked_component(evidence_dir)
    if link is not None:
        problems.append(f"{display_path(link)} {_EVIDENCE_LINK}")
    links: list[Path] = []
    for execution_dir in _evidence_executions(evidence_dir, links):
        for scenario_dir in _scenario_dirs(execution_dir, links):
            path = scenario_dir / VALIDATION_FILE
            if path.is_symlink():
                links.append(path)
            if path.is_symlink() or not path.is_file():
                continue
            shown = display_path(path)
            try:
                record = load_yaml(path)
            except YAMLLoadError as exc:
                problems.append(f"{shown}: {exc}")
                continue
            issues = _unikix_record_problems(record, execution_dir.name, scenario_dir.name)
            if issues:
                problems.append(f"{shown}: " + "; ".join(issues))
                continue
            # An item leaves blocked only on the immutable raw evidence the
            # import read; a verdict whose evidence is gone does not count.
            missing = []
            result = scenario_dir / RESULT_FILE
            if result.is_symlink() or not result.is_file():
                missing.append(f"no {RESULT_FILE} beside it")
            _, why = _read_environment(scenario_dir)
            if why is not None:
                missing.append(why)
            if missing:
                problems.append(f"{shown}: the raw evidence ({RESULT_FILE} / {ENVIRONMENT_FILE}) is missing or "
                                f"unreadable: {'; '.join(missing)}; the record does not count")
                continue
            found.append(UnikixRecord(execution_dir.name, scenario_dir.name, dict(record), path))
    problems.extend(f"{display_path(path)} {_ENTRY_LINK}" for path in links)
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


# Where the UniKix copy keeps what a run executes, inside the zip.
_ZIP_APP_DIR = "migrated_app/"
_ZIP_PROGRAM_DIR = _ZIP_APP_DIR + "cbl/"
_ZIP_MAP_COPY_DIR = _ZIP_APP_DIR + "cpy-bms/"
_ZIP_TPE_DIR = _ZIP_APP_DIR + "catlg/tpe/"
_ZIP_BPE_DIR = _ZIP_APP_DIR + "catlg/bpe/"
# Online programs are .cl2 (translated at build); batch programs, CSUTLDTC and SDSF .cbl.
_ZIP_PROGRAM_SUFFIXES = (".cl2", ".cbl")
# A COPY name resolves to a copybook, else to a symbolic map copybook.
_ZIP_COPY_DIRS = ("cpy/", "cpy-bms/")
_ZIP_SOURCE_DIRS = (_ZIP_PROGRAM_DIR, _ZIP_APP_DIR + "cpy/", _ZIP_MAP_COPY_DIR)
# The member that is the UniKix counterpart of a source in each app/ folder,
# formatted with the source's upper-cased stem; app/cbl resolves through
# _zip_program and app/csd through _CSD_KIND_LISTS.
_COUNTERPART_OF = {
    "app/cpy": "cpy/{}",
    "app/cpy-bms": "cpy-bms/{}",
    "app/bms": "bms/{}.bms",
    "app/jcl": "jcl/{}",
    "app/proc": "proc/{}",
    "app/ctl": "ctl/{}",
}
# The catlg/tpe list that defines each kind of CSD DEFINE in the UniKix copy;
# a LIBRARY has none.
_CSD_KIND_LISTS = {
    "FILE": "fct.lst",
    "TRANSACTION": "pct.lst",
    "PROGRAM": "ppt.lst",
    "MAPSET": "ppt.lst",
    "TDQUEUE": "dct.lst",
}
_ZIP_SOURCE_PREFIX = _inventory.UNIKIX_ZIP + ":"
_FIRST_LINE_RE = re.compile(r":(\d+)(?:-\d+)?(?:,\d+(?:-\d+)?)*$")
_UNIT_COPY_RE = re.compile(r"(?<![A-Za-z0-9-])COPY\s+(['\"]?)([A-Za-z0-9][A-Za-z0-9-]*)\1", re.I)
_UNIT_CALL_RE = re.compile(r"(?<![A-Za-z0-9-])CALL\s+(['\"])([A-Za-z0-9#$@-]+)\1", re.I)
_STAMPED: dict[str, tuple[tuple[int, int], Any]] = {}


@dataclass(frozen=True)
class _ZipUnits:
    """The executable sources of the UniKix copy as one zip holds them."""

    names: frozenset  # every file member, __MACOSX/ resource forks skipped
    references: dict  # cbl, cpy or cpy-bms member -> frozenset of the members it COPYs or CALLs
    map_programs: dict  # cpy-bms member -> frozenset of the cbl members that COPY it


def _zip_program(program: str, names: frozenset) -> str | None:
    """The zip member holding the source of ``program``, or ``None``."""
    for suffix in _ZIP_PROGRAM_SUFFIXES:
        member = f"{_ZIP_PROGRAM_DIR}{program.upper()}{suffix}"
        if member in names:
            return member
    return None


def _source_references(text: str, names: frozenset) -> frozenset:
    """The zip members a fixed-format COBOL source COPYs (``cpy/<NAME>``, else
    ``cpy-bms/<NAME>``) or CALLs by literal (a ``cbl`` program), read from
    columns 8-72 of its lines that are not comments (``*`` or ``/`` in
    column 7). A name the zip lacks, such as the CICS-supplied ``DFHAID`` and
    ``DFHBMSCA`` or a runtime routine, is no unit of the copy."""
    found = set()
    for line in text.splitlines():
        if len(line) > 6 and line[6] in "*/":
            continue
        area = line[7:72]
        for match in _UNIT_COPY_RE.finditer(area):
            name = match.group(2).upper()
            for folder in _ZIP_COPY_DIRS:
                if f"{_ZIP_APP_DIR}{folder}{name}" in names:
                    found.add(f"{_ZIP_APP_DIR}{folder}{name}")
                    break
        for match in _UNIT_CALL_RE.finditer(area):
            member = _zip_program(match.group(2), names)
            if member is not None:
                found.add(member)
    return frozenset(found)


@functools.lru_cache(maxsize=4)
def _zip_units(zip_sha256: str, path: str) -> _ZipUnits:
    """The members of the UniKix zip whose sha256 is ``zip_sha256`` and the
    COPY and CALL references of its programs and copybooks, read in memory
    (texts decoded as latin-1). ``ReportError`` when the file at ``path`` is
    not that zip."""
    with zipfile.ZipFile(path) as archive:
        if sha256_file(path) != zip_sha256:
            raise ReportError(f"{display_path(path)} is not the zip whose sha256 is {zip_sha256}")
        infos = [
            info for info in archive.infolist()
            if not info.is_dir() and not info.filename.startswith(_ZIP_SKIPPED_PREFIX)
        ]
        names = frozenset(info.filename for info in infos)
        texts = {
            info.filename: archive.read(info).decode("latin-1")
            for info in infos if info.filename.startswith(_ZIP_SOURCE_DIRS)
        }
    references = {member: _source_references(text, names) for member, text in texts.items()}
    map_programs: dict[str, set[str]] = {}
    for member, referenced in references.items():
        if member.startswith(_ZIP_PROGRAM_DIR):
            for reference in referenced:
                if reference.startswith(_ZIP_MAP_COPY_DIR):
                    map_programs.setdefault(reference, set()).add(member)
    return _ZipUnits(names, references, {name: frozenset(owners) for name, owners in map_programs.items()})


def _stamped(path: Path, build: Callable[[Path], Any]) -> Any:
    """``build(path)``, built again only when the file's modification time or
    size changes. ``OSError`` when the file cannot be examined."""
    status = path.stat()
    stamp = (status.st_mtime_ns, status.st_size)
    key = f"{build.__qualname__}:{path}"
    hit = _STAMPED.get(key)
    if hit is not None and hit[0] == stamp:
        return hit[1]
    value = build(path)
    _STAMPED[key] = (stamp, value)
    return value


def _unikix_resources(path: Path) -> tuple[dict, dict]:
    """The ``transactions`` and ``jobs`` mappings of the unikix adapter's
    ``resources.yaml``."""
    document = load_yaml(path)
    sections = []
    for name in ("transactions", "jobs"):
        section = document.get(name) if isinstance(document, Mapping) else None
        if not isinstance(section, Mapping):
            raise ReportError(f"{display_path(path)}: {name} is not a mapping")
        sections.append(dict(section))
    return sections[0], sections[1]


def _inventory_sources(path: Path) -> dict[str, str]:
    """Each inventory item's ``source``, by id."""
    return {
        str(item["id"]): str(item.get("source") or "")
        for item in _inventory.load(path).get("items") or []
        if isinstance(item, Mapping) and item.get("id") is not None
    }


def _csd_entries(path: Path) -> tuple:
    """Every ``DEFINE`` of the CSD at ``path`` (``app/csd/CARDDEMO.CSD``)."""
    return tuple(_inventory.parse_csd(REPO_ROOT))


def _item_counterpart(item_id: str, source: str, names: frozenset, csd: Sequence) -> tuple[str | None, str | None]:
    """The zip member that is the UniKix counterpart of an inventory item's
    ``source``, or why the copy has none. ``(None, None)`` for a source that
    is no executed unit: documentation, and data files, which fixtures
    replace."""
    path = _source_path(source)
    if path.startswith(_ZIP_SOURCE_PREFIX):
        member = path[len(_ZIP_SOURCE_PREFIX):]
        return (member, None) if member in names else (None, f"{item_id}: {member} is not a member of the zip")
    folder, _, file_name = path.rpartition("/")
    stem = file_name.split(".", 1)[0].upper()
    if folder == "app/cbl":
        member = _zip_program(stem, names)
        return (member, None) if member else (None, f"{item_id}: program {stem} has no source in the zip")
    if folder in _COUNTERPART_OF:
        member = _ZIP_APP_DIR + _COUNTERPART_OF[folder].format(stem)
        return (member, None) if member in names else (None, f"{item_id}: {member}, the counterpart of {path}, "
                                                             "is not a member of the zip")
    if path == _inventory.CSD_PATH:
        match = _FIRST_LINE_RE.search(source)
        first = int(match.group(1)) if match else None
        entry = next((entry for entry in csd if first is not None and entry.first <= first <= entry.last), None)
        listing = _CSD_KIND_LISTS.get(entry.kind) if entry is not None else None
        if listing is None:
            return None, f"{item_id} ({source}) has no counterpart among the UniKix copy's resource lists"
        member = _ZIP_TPE_DIR + listing
        return (member, None) if member in names else (None, f"{item_id}: {member} is not a member of the zip")
    return None, None


def _names(value: Any, where: str, problems: list[str]) -> list[str]:
    """The names of a ``resources.yaml`` list; a value of another shape is a
    problem."""
    if value is None:
        return []
    if not isinstance(value, list) or not all(isinstance(name, str) for name in value):
        problems.append(f"{where} is not a list of names")
        return []
    return list(value)


def _scenario_units(scenario: Any, zip_units: _ZipUnits) -> tuple[set[str], list[str]]:
    """The zip members a UniKix run of ``scenario`` executes, and what of it
    the zip does not hold.

    * Steps (an empty list included): every ``catlg/tpe`` resource list,
      which region creation installs; the program of each step's transid
      (``resources.yaml`` ``transactions``); for each map a step or expected
      screen names (``TEXT`` aside), its ``bms`` source, its ``cpy-bms``
      copybook and the program that copies that copybook.
    * Jobs: each job's member, procedures, control members and programs
      (``resources.yaml`` ``jobs``), and every ``catlg/bpe`` catalog list.
    * ``inputs.program``: that program.
    * Every listed inventory item: its source's UniKix counterpart
      (``_item_counterpart``); an id the inventory lacks is reported by
      ``build_state``.
    * The COPY and CALL closure of every program and copybook so required."""
    names = zip_units.names
    required: set[str] = set()
    problems: list[str] = []

    def program(name: str, why: str) -> None:
        member = _zip_program(name, names)
        if member is None:
            problems.append(f"program {name} ({why}) has no source in the zip")
        else:
            required.add(member)

    def member(relative: str, why: str) -> None:
        name = _ZIP_APP_DIR + relative
        if name in names:
            required.add(name)
        else:
            problems.append(f"{name} ({why}) is not a member of the zip")

    inputs = scenario.inputs if isinstance(scenario.inputs, Mapping) else {}
    expected = scenario.expected if isinstance(scenario.expected, Mapping) else {}
    steps = inputs.get("steps")
    jobs = _scenarios.job_names(scenario)
    transactions: dict = {}
    job_entries: dict = {}
    if isinstance(steps, list) or jobs:
        transactions, job_entries = _stamped(_scenarios.UNIKIX_RESOURCES_FILE, _unikix_resources)
    if isinstance(steps, list):
        required.update(name for name in names if name.startswith(_ZIP_TPE_DIR) and name.endswith(".lst"))
        maps = []
        for step in steps:
            if not isinstance(step, Mapping):
                continue
            transid = step.get("transid")
            if isinstance(transid, str):
                entry = transactions.get(transid)
                target = entry.get("program") if isinstance(entry, Mapping) else None
                if isinstance(target, str):
                    program(target, f"transid {transid}")
                else:
                    problems.append(f"transid {transid} names no program in "
                                    f"{display_path(_scenarios.UNIKIX_RESOURCES_FILE)}")
            maps.append(step.get(_scenarios.SCREEN_MAP))
        screens = expected.get("screens")
        if isinstance(screens, list):
            maps.extend(screen.get(_scenarios.SCREEN_MAP) for screen in screens if isinstance(screen, Mapping))
        for name in sorted({value.upper() for value in maps if isinstance(value, str)} - {_scenarios.TEXT_SCREEN}):
            member(f"bms/{name}.bms", f"map {name}")
            member(f"cpy-bms/{name}", f"map {name}")
            owners = zip_units.map_programs.get(_ZIP_MAP_COPY_DIR + name)
            if owners:
                required.update(owners)
            else:
                problems.append(f"no program of the zip copies {_ZIP_MAP_COPY_DIR}{name} (map {name})")
    for job in jobs:
        entry = job_entries.get(job)
        if not isinstance(entry, Mapping):
            problems.append(f"job {job} is not in {display_path(_scenarios.UNIKIX_RESOURCES_FILE)} jobs")
            continue
        source = entry.get("member")
        if isinstance(source, str):
            member(source, f"job {job}")
        elif source is not None:
            problems.append(f"jobs.{job}.member is not a member name")
        for proc in _names(entry.get("procs"), f"jobs.{job}.procs", problems):
            member(f"proc/{proc.upper()}", f"job {job}")
        for control in _names(entry.get("ctl"), f"jobs.{job}.ctl", problems):
            member(f"ctl/{control.upper()}", f"job {job}")
        for name in _names(entry.get("programs"), f"jobs.{job}.programs", problems):
            program(name, f"job {job}")
    if jobs:
        required.update(name for name in names if name.startswith(_ZIP_BPE_DIR) and name.endswith(".csv"))
    if isinstance(inputs.get("program"), str):
        program(inputs["program"], "inputs.program")
    item_ids = sorted(set(map(str, scenario.inventory_ids)))
    if item_ids:
        sources = _stamped(_inventory.INVENTORY_FILE, _inventory_sources)
        csd = _stamped(REPO_ROOT / _inventory.CSD_PATH, _csd_entries)
        for item_id in item_ids:
            if item_id not in sources:
                continue
            counterpart, problem = _item_counterpart(item_id, sources[item_id], names, csd)
            if counterpart is not None:
                required.add(counterpart)
            if problem is not None:
                problems.append(problem)
    pending = sorted(required)
    while pending:
        for reference in zip_units.references.get(pending.pop(), ()):
            if reference not in required:
                required.add(reference)
                pending.append(reference)
    return required, problems


def _required_units(scenario: Any, zip_sha256: str) -> tuple[set[str], str | None]:
    """``_scenario_units`` of ``scenario`` in the zip whose sha256 is
    ``zip_sha256``, or why they cannot all be derived; never raises."""
    try:
        required, problems = _scenario_units(scenario, _zip_units(zip_sha256, str(UNIKIX_ZIP)))
    except OSError as exc:  # shown repository-relative: no absolute path reaches the report
        where = display_path(exc.filename) if exc.filename else display_path(UNIKIX_ZIP)
        return set(), f"the units {scenario.id} executes cannot be derived: {where}: {exc.strerror or exc}"
    except (EOFError, RuntimeError, ValueError, zipfile.BadZipFile, HarnessError) as exc:
        return set(), f"the units {scenario.id} executes cannot be derived: {_clip(exc, 300)}"
    if problems:
        return required, (f"the units {scenario.id} executes are not all in the UniKix copy: "
                          f"{_clip('; '.join(sorted(set(problems))))}")
    if not required:
        return required, f"{scenario.id} names no steps, job or program the UniKix copy runs"
    return required, None


def identity_problem(identity: Any, zip_info: tuple[str, dict] | None = None,
                     scenario: Any = None) -> str | None:
    """Why a record's ``source_identity`` is not the UniKix copy of the zip as
    committed, or ``None`` when it is: tree ``unikix-copy``, the zip's sha256,
    a non-empty ``units`` mapping whose every hash equals that zip member's
    and, given the ``scenario``, units that hold every zip member a run of it
    executes (``_scenario_units``), so each of those members' hashes is the
    zip's. Never raises: an input that cannot be read is a problem."""
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
    if not units:
        return "source_identity.units is empty: it names no executed unit of the zip"
    for member, unit_digest in sorted(units.items()):
        if member not in members:
            return f"source_identity unit {member} is not a member of the zip"
        if members[member] != unit_digest:
            return f"source_identity unit {member} differs from the zip's member"
    if scenario is None:
        return None
    required, problem = _required_units(scenario, digest)
    if problem is not None:
        return problem
    missing = sorted(required - set(units))
    if missing:
        return (f"source_identity.units does not cover what {scenario.id} executes: it lacks "
                f"{_clip(', '.join(missing))}")
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
    problem = identity_problem(record.get("source_identity"), zip_info, scenario)
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
    ``None`` when it is eligible (``unikix_eligible``): a source identity
    that is not the zip's UniKix copy as committed or lacks the zip's hash of
    a unit the scenario executes or of a listed item's UniKix counterpart
    (``identity_problem``), or an item that passes through copy code that
    differs from ``app/``."""
    state = state if state is not None else build_state()
    return _ineligibility(record, scenario, state.copy_gaps, state.zip_info)


def unikix_eligible(record: Mapping[str, Any], scenario: Any, state: "State | None" = None) -> bool:
    """Whether a UniKix record counts toward ``scenario``'s items: it ran the
    zip's UniKix copy as committed (tree, zip sha256, unit hashes), its
    identity holds the zip's hash of every unit the scenario executes (the
    programs, copybooks, maps, job sources and resource lists of its steps or
    jobs) and of each listed item's UniKix counterpart, and none of the
    scenario's items passes through copy code that differs from ``app/``
    (GAP-DOC-020 to GAP-DOC-032), except the GAP-DOC-030 ``fct.lst`` files,
    which a record that ran the zip's ``fct.lst`` verifies."""
    return unikix_ineligibility(record, scenario, state) is None


# --------------------------------------------------------------------------
# Status rules
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class ScenarioStatus:
    """A scenario's status and what decided it. ``records`` are the counted
    current records whose status is the scenario's; ``stale`` names the
    records that no longer describe the scenario and baseline (an imported
    one also by its execution fingerprint); ``ineligible`` holds every
    imported UniKix record excluded as UniKix-copy behaviour, current or
    stale, with why. An ineligible record is never also counted or stale."""

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
    and eligible when UniKix) and its ``stale`` ones (no longer current):
    failing when any counted record fails, else passed when any passed, else
    blocked when there is no stale record and either every counted record is
    blocked (every run raised ``AdapterUnavailable``) or none exists (no
    capable adapter ran it, or only ineligible UniKix records exist: reason
    ``adapter-unavailable``), else implemented-unexecuted. A stale record
    counts as implemented-unexecuted, so blocked records never outweigh it;
    the reason ``stale`` is given only when such a record exists."""
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
    if counted and set(by_status) == {_results.STATUS_BLOCKED} and not stale:
        first = by_status[_results.STATUS_BLOCKED][0]
        return ScenarioStatus(scenario_id, _results.STATUS_BLOCKED, first.get("reason"), first.get("gap_id"),
                              tuple(by_status[_results.STATUS_BLOCKED]), *extra)
    records = tuple(by_status.get(_results.STATUS_UNEXECUTED, ()))
    if stale and not records:
        # A stale record counts as implemented-unexecuted, so current blocked
        # records beside it do not make every run blocked.
        return ScenarioStatus(scenario_id, _results.STATUS_UNEXECUTED, "stale", None, (), *extra)
    if counted:
        first = records[0] if records else counted[0]
        return ScenarioStatus(scenario_id, _results.STATUS_UNEXECUTED, first.get("reason"), first.get("gap_id"),
                              records, *extra)
    # No counted or stale record, so no capable adapter ran it: in CI every
    # scenario only unikix can run is deselected, and one gnucobol-batch can
    # run has none when the session did not run it. The gap names what is
    # missing: GAP_NOT_RUN when gnucobol-batch is among the capable adapters
    # (its unwritten record, whether or not unikix can run it too); else
    # GAP_APP_NOT_ON_UNIKIX when UniKix evidence exists but none of it may
    # verify the app/ items (an app/ build there); else GAP_NO_INSTALLATION.
    capable = _scenarios.capable_adapters(scenario)
    if _results.GNUCOBOL in capable:
        gap = GAP_NOT_RUN
    elif ineligible:
        gap = GAP_APP_NOT_ON_UNIKIX
    else:
        gap = GAP_NO_INSTALLATION
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


def item_statuses(
    statuses: Mapping[str, ScenarioStatus | str],
    item_scenarios: Mapping[str, Iterable[str]],
) -> dict[str, str]:
    """The status of every item in ``item_scenarios`` (item id -> the ids of
    the scenarios that list it), in its order. ``statuses`` maps a scenario id
    to its ``ScenarioStatus`` or to its status string. An item takes the worst
    status of the scenarios that list it, failing > blocked >
    implemented-unexecuted > verified, a ``passed`` scenario giving
    ``verified``; an item no scenario lists is ``not-covered``."""

    def status_of(item_id: str, scenario_id: str) -> str:
        if scenario_id not in statuses:
            raise ReportError(f"{item_id}: listing scenario {scenario_id} has no status")
        value = statuses[scenario_id]
        status = value.status if isinstance(value, ScenarioStatus) else value
        if status not in _ITEM_STATUS_OF:
            raise ReportError(f"{scenario_id}: {status!r} is not a scenario status")
        return status

    return {
        item_id: _item_status(status_of(item_id, scenario_id) for scenario_id in scenario_ids)
        for item_id, scenario_ids in item_scenarios.items()
    }


@dataclass
class _CurrentFingerprint:
    """A scenario's unikix execution fingerprint as it stands, computed on
    first use and then reused, so the scenario's fixtures are built at most
    once however many of its records are judged."""

    scenario: Any

    @functools.cached_property
    def value(self) -> str | None:
        """``current_fingerprint``'s digest; ``None`` when it cannot be
        computed."""
        return current_fingerprint(self.scenario)[0]


def _imported_is_current(record: Mapping[str, Any], scenario: Any, baseline: Any,
                         fingerprint: _CurrentFingerprint) -> bool:
    """Whether an imported UniKix record (its ``validation.yaml``) still
    describes ``scenario``: current by ``baseline_version`` and
    ``scenario_digest`` (``harness.results.is_current``), and under the
    fingerprint rule, which alone lets a re-comparison stand for a run. A
    recorded ``execution_fingerprint`` must equal the scenario's as it stands,
    so a change to anything that affects execution (fixture bytes, the
    effective clock, faults, inputs) leaves the record stale until a new run,
    whatever ``validation.yaml`` still says; a fingerprint that cannot be
    computed cannot be matched. A record without one is decided by its version
    and digest alone, whatever its status: a passed record always carries one
    (the record contract refuses it otherwise, so it is never current), and a
    failing one written without it keeps its failure."""
    if not _results.is_current(record, scenario, baseline):
        return False
    recorded = record.get("execution_fingerprint")
    return recorded is None or fingerprint.value == recorded


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
            fingerprint = _CurrentFingerprint(scenario)
            for entry in imported:
                # Eligibility first: UniKix-copy behaviour is reported as such,
                # current or stale, and never enters either status aggregation.
                why = _ineligibility(entry.record, scenario, copy_gaps, zip_info)
                if why is not None:
                    ineligible.append((entry.record, why))
                elif problem is None and _imported_is_current(entry.record, scenario, baseline, fingerprint):
                    counted.append(entry.record)
                else:
                    stale.append(display_path(entry.path))
        statuses[scenario_id] = scenario_status(scenario, counted, stale, ineligible)

    item_scenarios = {item["id"]: universe.listed_by.get(item["id"], []) for item in items}
    item_status = item_statuses(statuses, item_scenarios)
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
        if status.status == _results.STATUS_BLOCKED and not status.counted and not status.stale
        and _results.GNUCOBOL in _scenarios.capable_adapters(state.scenarios[scenario_id])
    ]
    if unrun:
        causes.append(f"{len(unrun)} scenarios gnucobol-batch can run have no result record (not run in this "
                      f"session); no capable adapter ran them, so they count as blocked under {GAP_NOT_RUN}.")
    stale = [
        status for status in state.statuses.values()
        if status.status == _results.STATUS_UNEXECUTED and status.reason == "stale" and not status.records
    ]
    only_stale = sum(1 for status in stale if not status.counted)
    if only_stale:
        causes.append(f"{only_stale} scenarios have only stale result records (run against another scenario, "
                      "baseline or execution input); they count as implemented-unexecuted.")
    if len(stale) > only_stale:
        causes.append(f"{len(stale) - only_stale} scenarios have stale result records beside current blocked "
                      "ones; a stale record counts as implemented-unexecuted, so they are not blocked.")
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
        if not isinstance(execution_id, str) or not _UNIKIX_ID_RE.fullmatch(execution_id):
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
        if problem is not None or not _imported_is_current(record, scenario, baseline,
                                                           _CurrentFingerprint(scenario)):
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
        narrowed = entry.get("narrowed_from")
        earlier = narrowed.get("affected_inventory_ids") if isinstance(narrowed, Mapping) else None
        unknown = sorted(
            item_id for item_id in (earlier if isinstance(earlier, list) else [])
            if isinstance(item_id, str) and item_id not in state.item_status
        )
        if unknown:
            findings.append(f"{gap_id}: narrowed_from.affected_inventory_ids names ids outside the inventory: "
                            f"{', '.join(unknown)}")
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
    try:
        scenario = _scenarios.get(scenario_id, missing_ok=True)
    except HarnessError as exc:
        return None, f"the scenario file cannot be read: {exc}"
    problem = identity_problem(record["source_identity"], zip_info, scenario)
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


_FOLDER_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC


def _replace_file(folder: int, name: str, data: bytes, path: Path) -> None:
    """Make the file ``name`` of the open folder ``folder`` (shown as
    ``path``) hold ``data`` without following a symbolic link. A link or a
    non-regular file of that name is refused; an identical file is left
    untouched, so its mtime stays stable; otherwise ``data`` goes to a new
    file beside it that ``os.replace`` swaps in, keeping an existing file's
    permission bits, and the new file is removed when anything fails."""
    try:
        info: os.stat_result | None = os.stat(name, dir_fd=folder, follow_symlinks=False)
    except FileNotFoundError:
        info = None
    if info is not None and stat.S_ISLNK(info.st_mode):
        raise ReportError(f"{display_path(path)} is a symbolic link; nothing is written through it")
    if info is not None and not stat.S_ISREG(info.st_mode):
        raise ReportError(f"{display_path(path)} is not a regular file; nothing is written to it")
    if info is not None and info.st_size == len(data):
        descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, dir_fd=folder)
        with os.fdopen(descriptor, "rb") as handle:
            if stat.S_ISREG(os.fstat(handle.fileno()).st_mode) and handle.read(len(data) + 1) == data:
                return
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC
    while True:
        temp = f".{name}.{os.getpid()}.{os.urandom(4).hex()}.tmp"
        try:
            descriptor = os.open(temp, flags, 0o666, dir_fd=folder)
            break
        except FileExistsError:
            continue
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(data)
            if info is not None:
                os.fchmod(handle.fileno(), stat.S_IMODE(info.st_mode))
        os.replace(temp, name, src_dir_fd=folder, dst_dir_fd=folder)
    except BaseException:
        try:
            os.unlink(temp, dir_fd=folder)
        except FileNotFoundError:
            pass
        raise


def _write_validation(evidence: Evidence, record: Mapping[str, Any]) -> None:
    """Write ``validation.yaml`` into the evidence's own scenario directory,
    the one file ever written under the evidence tree, without following a
    symbolic link: ``_evidence_folder`` first names any linked folder, then
    each folder from the resolved ``tests/`` root down is opened relative to
    its parent with ``O_NOFOLLOW``, so a link swapped in meanwhile fails
    instead of being written through, and the file is replaced inside the
    last one (``_replace_file``)."""
    path = evidence.directory / VALIDATION_FILE
    _evidence_folder(evidence.execution_id, evidence.scenario_id)
    if evidence.directory != UNIKIX_EVIDENCE_DIR / evidence.execution_id / evidence.scenario_id:
        raise ReportError(f"{display_path(evidence.directory)} is not the evidence directory of "
                          f"{evidence.execution_id}/{evidence.scenario_id}; nothing is written there")
    data = dump_yaml(dict(record)).encode("utf-8")
    current = TESTS_ROOT
    try:
        folder = os.open(TESTS_ROOT, _FOLDER_FLAGS)
        try:
            for part in evidence.directory.relative_to(TESTS_ROOT).parts:
                current = current / part
                parent, folder = folder, os.open(part, _FOLDER_FLAGS | os.O_NOFOLLOW, dir_fd=folder)
                os.close(parent)
            current = path
            _replace_file(folder, VALIDATION_FILE, data, path)
        finally:
            os.close(folder)
    except OSError as exc:
        if os.path.islink(current):
            raise ReportError(f"{display_path(current)} is a symbolic link; nothing is written through it") from None
        raise ReportError(f"{display_path(current)} cannot be written: {exc.strerror or exc}") from None


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
    is written because the evidence no longer describes the scenario. Raises
    ``HarnessError`` when the evidence cannot be judged: the current
    execution fingerprint cannot be computed or the baseline cannot be read."""
    raw = evidence.raw
    fingerprint = raw.get("execution_fingerprint")
    if fingerprint is not None:
        current, problem = current_fingerprint(scenario)
        if problem is not None:
            raise ReportError(f"{scenario.id}: the current execution fingerprint cannot be computed: {problem}")
        if current != fingerprint:
            if summary is not None:
                summary.skipped.append((scenario.id, "stale: the scenario's inputs or preconditions changed since "
                                                     "this execution"))
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


def _new_message_items(state: State, execution_id: str, imported: Iterable[str], document: dict,
                       summary: ImportSummary) -> None:
    """Add every observed message of the execution that matches no item to
    the inventory, raising its total, and list each added item under
    GAP-UNC-012 in ``document``, reopening that entry when it is closed.
    Without a GAP-UNC-012 entry nothing is added and the import rejects the
    messages."""
    patterns = list(_patterns_of(state.items))
    added: set[str] = set()
    additions: list[tuple[dict, str]] = []
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
            additions.append((item, f"unmatched observed message ({execution_id}/{scenario_id})"))
            patterns.append((text, _literal_pattern(text)))
    if not additions:
        return
    entry = _gap_entries(document).get(GAP_COMPLETENESS)
    listed = entry.get("affected_inventory_ids", []) if entry is not None else None
    if not isinstance(listed, list):
        where = (f"gaps.yaml has no {GAP_COMPLETENESS} entry to register them under" if entry is None
                 else f"the affected_inventory_ids of {GAP_COMPLETENESS} in gaps.yaml is not a list")
        summary.rejected.append(("-", f"{len(additions)} observed message(s) match no inventory item, but {where}; "
                                      "nothing was added"))
        return
    assigned = []
    for item, reason in additions:
        try:
            assigned.extend(_inventory.append_items([item], reason))
        except HarnessError as exc:
            # The items already added are still registered below, so none is left without its entry.
            summary.rejected.append(("-", f"inventory addition {item['name']}: {exc}"))
            break
    if not assigned:
        return
    summary.items_added.extend(assigned)
    entry["affected_inventory_ids"] = sorted({str(item_id) for item_id in listed} | set(assigned))
    if entry.get("status") == "closed":
        _reopen(entry)
        summary.gaps_changed.append(f"{GAP_COMPLETENESS}: reopened, {len(assigned)} observed message(s) of "
                                    f"{execution_id} match no inventory item")
    summary.gaps_changed.append(f"{GAP_COMPLETENESS}: lists the added item(s) {', '.join(assigned)}")


def _reopen(entry: dict) -> None:
    """Reopen a closed entry on contrary evidence; the evidence that closed it
    moves to ``superseded_evidence``, so the entry keeps its history."""
    history = entry.get("superseded_evidence")
    history = list(history) if isinstance(history, list) else ([] if history is None else [history])
    if entry.get("resolving_evidence") is not None:
        history.append(entry["resolving_evidence"])
    entry["status"], entry["resolving_evidence"] = "open", None
    if history:
        entry["superseded_evidence"] = history


def _membership(entry: Mapping[str, Any]) -> dict:
    """The ``affected_inventory_ids`` and ``affected`` clauses an entry holds,
    as ``narrowed_from`` keeps them."""
    kept = {"affected_inventory_ids": list(entry.get("affected_inventory_ids") or [])}
    if entry.get("affected"):
        kept["affected"] = list(entry["affected"])
    return kept


def _restore(entry: dict) -> None:
    """Give a narrowed entry back the membership it held before it was narrowed."""
    earlier = entry.pop("narrowed_from")
    entry["affected_inventory_ids"] = list(earlier.get("affected_inventory_ids") or [])
    if earlier.get("affected"):
        entry["affected"] = list(earlier["affected"])
    else:
        entry.pop("affected", None)


def _closing_evidence(state: State, item_ids: Iterable[str], execution_id: str | None) -> dict | None:
    """The ``resolving_evidence`` of an inferred-not-observed entry whose items
    are all verified: the UniKix execution whose counted passed records verify
    them (the importing one when it is among them, else the latest, as ids
    embed their UTC start), else the importing execution, else the commit
    checked out. ``None`` when nothing names the evidence."""
    executions = {
        str(record["execution_id"])
        for item_id in item_ids
        for scenario_id in state.item_scenarios.get(item_id, ())
        for record in state.statuses[scenario_id].counted
        if record.get("adapter") == _results.UNIKIX and record.get("status") == _results.STATUS_PASSED
        and record.get("execution_id")
    }
    if execution_id is not None and (execution_id in executions or not executions):
        chosen: str | None = execution_id
    else:
        chosen = max(executions, default=None)
    if chosen is not None:
        return {"execution_id": chosen, "evidence_path": f"{display_path(UNIKIX_EVIDENCE_DIR)}/{chosen}"}
    commit = git_head()
    return None if commit == "unknown" else {"commit": commit}


def _settle_inferred(document: dict, state: State, execution_id: str | None, changes: list) -> None:
    """Judge every inferred-not-observed entry, open or closed, over its full
    membership (``narrowed_from`` once it is narrowed), appending one line
    per change to ``changes``. An entry closes, its full membership
    restored, only when every item is verified by current records; a closed
    entry with an item no longer verified reopens, its
    ``resolving_evidence`` moved to ``superseded_evidence``; an open entry
    lists exactly its unverified items, the earlier membership kept under
    ``narrowed_from``. ``execution_id`` names the importing execution
    (``None`` on revalidation)."""
    universe = _Universe.build(state.items, state.scenarios.values())
    for gap_id, entry in _gap_entries(document).items():
        if entry.get("category") != CATEGORIES["INF"] or entry.get("status") not in GAP_STATUSES:
            continue
        narrowed = entry.get("narrowed_from")
        if narrowed is not None and not isinstance(narrowed, Mapping):
            continue  # check() reports the malformed entry; it is left as it is
        members = _resolve(gap_id, narrowed if narrowed is not None else entry, universe)
        if not members:
            continue
        before = _resolve(gap_id, entry, universe)
        unverified = [item_id for item_id in members if state.item_status.get(item_id) != VERIFIED]
        if not unverified:
            if entry.get("status") == "open":
                evidence = _closing_evidence(state, members, execution_id)
                if evidence is None:
                    changes.append(f"{gap_id}: left open although every affected item is verified: no UniKix "
                                   "execution or commit names the evidence")
                else:
                    entry["status"], entry["resolving_evidence"] = "closed", evidence
                    changes.append(f"{gap_id}: closed, every affected item verified")
            elif narrowed is not None:
                changes.append(f"{gap_id}: its full membership is restored, every affected item verified")
            if narrowed is not None:
                _restore(entry)
            continue
        if entry.get("status") == "closed":
            _reopen(entry)
            changes.append(f"{gap_id}: reopened, {len(unverified)} of its {len(members)} items are no longer "
                           f"verified: {_clip(', '.join(unverified), 300)}")
        if len(unverified) < len(members):
            if narrowed is None:
                entry["narrowed_from"] = _membership(entry)
            entry["affected_inventory_ids"] = unverified
            entry.pop("affected", None)
        elif narrowed is not None:
            _restore(entry)
        after = _resolve(gap_id, entry, universe)
        returned = sorted(set(after) - set(before))
        if returned:
            changes.append(f"{gap_id}: lists {len(returned)} more item(s) that are not verified "
                           f"({_clip(', '.join(returned), 300)}); {len(after)} items in all are still unverified")
        elif after != before:
            changes.append(f"{gap_id}: narrowed to the {len(after)} items still unverified")


def _settle_gaps(document: dict, state: State, execution_id: str, environments: Iterable[Mapping[str, Any]],
                 summary: ImportSummary) -> None:
    """Judge the inferred-not-observed entries (``_settle_inferred``), and
    close GAP-MIS-009 once the installation's versions are recorded."""
    _settle_inferred(document, state, execution_id, summary.gaps_changed)
    evidence = {"execution_id": execution_id, "evidence_path": f"{display_path(UNIKIX_EVIDENCE_DIR)}/{execution_id}"}
    installation = _gap_entries(document).get(GAP_NO_INSTALLATION)
    if (installation is not None and installation.get("status") == "open"
            and any(_versions_recorded(environment) for environment in environments)):
        installation["status"], installation["resolving_evidence"] = "closed", dict(evidence)
        summary.gaps_changed.append(f"{GAP_NO_INSTALLATION}: closed, the installation's versions are recorded")


def _write_gaps(document: dict, original: str) -> None:
    _refuse_links(GAPS_FILE)
    write_text(GAPS_FILE, _gaps_text(document, original))


@contextlib.contextmanager
def _exclusive() -> Iterator[None]:
    """Hold an exclusive lock on ``tests/`` while an import or a revalidation
    reads and rewrites ``gaps.yaml``, the scenarios, baselines,
    ``validation.yaml`` files and reports, so imports and revalidations run
    one at a time and none loses another's update; a second one waits for
    the first. The lock is taken on the directory itself, because the files
    are replaced rather than rewritten in place, so no lock file is created;
    the inventory's own lock still guards each addition."""
    try:
        descriptor = os.open(TESTS_ROOT, os.O_RDONLY | os.O_DIRECTORY)
    except OSError as exc:
        raise ReportError(f"cannot lock {display_path(TESTS_ROOT)}: {exc.strerror or exc}") from None
    try:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX)
        except OSError as exc:
            raise ReportError(f"cannot lock {display_path(TESTS_ROOT)}: {exc.strerror or exc}") from None
        yield
    finally:
        os.close(descriptor)  # closing the descriptor releases the lock


def import_unikix(execution_id: str) -> ImportSummary:
    """Import the evidence of one UniKix execution: check each record's
    source identity and its ``environment.yaml`` (a record that
    fails is reported, not imported), promote each eligible derived baseline
    its scenario ran (MINOR when the observed outcome equals it, MAJOR with a
    register entry when it differs), register cross-adapter divergences,
    write ``validation.yaml`` beside each ``result.yaml``, add observed
    messages no item matches to the inventory and to GAP-UNC-012, judge the
    inferred-not-observed entries and settle GAP-MIS-009, and regenerate the
    reports. A record whose current execution fingerprint cannot be computed
    is rejected. Imports and revalidations run one at a time (``_exclusive``)."""
    if not isinstance(execution_id, str) or not _UNIKIX_ID_RE.fullmatch(execution_id):
        raise ReportError(f"{execution_id!r} is not a unikix execution id unikix-<yyyymmddThhmmssZ>-<8 hex>")
    execution_dir = _evidence_folder(execution_id)
    if not execution_dir.is_dir():
        raise ReportError(f"no evidence directory {display_path(execution_dir)}")
    with _exclusive():
        return _import_execution(execution_id, execution_dir)


def _import_execution(execution_id: str, execution_dir: Path) -> ImportSummary:
    """The import itself, run while the lock is held."""
    summary = ImportSummary(execution_id)
    original = GAPS_FILE.read_text(encoding="utf-8")
    document = load_yaml(GAPS_FILE)
    state = build_state()
    imported = []
    environments = []
    links: list[Path] = []
    scenario_dirs = _scenario_dirs(execution_dir, links)
    summary.rejected.extend((link.name, _ENTRY_LINK) for link in links)
    for scenario_dir in scenario_dirs:
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
    _new_message_items(build_state(), execution_id, imported, document, summary)
    _settle_gaps(document, build_state(), execution_id, environments, summary)
    if document != load_yaml(GAPS_FILE):
        _write_gaps(document, original)
    regenerate()
    return summary


@dataclass
class RevalidationSummary:
    """What one revalidation did, for the operator. ``left`` holds the
    records that stay stale until a new run because the execution no longer
    describes the scenario; ``rejected`` holds the records that could not be
    revalidated at all and those whose observed payload could not be compared
    with the baseline, whose ``validation.yaml`` records that comparison
    error as a live run would."""

    revalidated: list = field(default_factory=list)  # (execution/scenario, status, reason)
    left: list = field(default_factory=list)  # (execution/scenario, why it stays stale)
    rejected: list = field(default_factory=list)  # (execution/scenario, why it cannot be revalidated or compared)
    gaps_changed: list = field(default_factory=list)

    def lines(self) -> list[str]:
        out = [
            f"revalidated {label}: {status}" + (f" ({reason})" if reason else "")
            for label, status, reason in self.revalidated
        ]
        out += [f"left {label}: {why}" for label, why in self.left]
        out += [f"rejected {label}: {why}" for label, why in self.rejected]
        out += [f"gap updated {text}" for text in self.gaps_changed]
        return out


def revalidate() -> RevalidationSummary:
    """Re-compare every ``validation.yaml`` whose execution still describes
    its scenario (the current execution fingerprint equals the one in
    ``result.yaml`` and every observation the current ``expected`` names is
    in the evidence) and rewrite it; leave every other one, stale until a new
    run; reject the evidence that fails its checks, whose scenario file is
    gone or that cannot be judged, and the records whose observed payload
    cannot be compared with the baseline (rewritten failing with reason
    error, as a live run records it, and reported with the comparison's
    diagnostic). Then judge the inferred-not-observed entries of
    ``gaps.yaml`` again, write it when they change, and regenerate the
    reports. A symbolic link at ``tests/reports`` or ``tests/reports/unikix``
    raises ``ReportError`` before anything is read or written; one in place
    of an execution or scenario directory is rejected, and reported as such."""
    with _exclusive():
        return _revalidate()


def _comparison_error(evidence: Evidence, scenario: Any) -> str | None:
    """Why the observed payload of ``evidence`` cannot be compared with the
    scenario's baseline, on one line; ``None`` when it can, so that an error
    reason written for it is the execution's own verdict, which revalidation
    keeps like any other status."""
    if not evidence.ran:
        return None
    fallback = "the comparison reported an error"
    baseline, problem = _load_baseline(scenario.id)
    if problem is None:
        try:
            _, reason, problem, _ = _judge(evidence, scenario, baseline)
        except HarnessError:
            problem = fallback
        else:
            if reason != _baselines.REASON_ERROR:
                return None
    lines = (line.strip().removeprefix("- ").strip() for line in str(problem).splitlines())
    return _clip("; ".join(line for line in lines if line) or fallback, 600)


def _revalidate() -> RevalidationSummary:
    """The revalidation itself, run while the lock is held."""
    result = RevalidationSummary()
    evidence_dir = _evidence_folder()
    state = build_state()
    original = GAPS_FILE.read_text(encoding="utf-8")
    document = load_yaml(GAPS_FILE)
    links: list[Path] = []
    for execution_dir in _evidence_executions(evidence_dir, links):
        for scenario_dir in _scenario_dirs(execution_dir, links):
            if not (scenario_dir / VALIDATION_FILE).is_file():
                continue
            label = f"{execution_dir.name}/{scenario_dir.name}"
            evidence, why = read_evidence(execution_dir.name, scenario_dir, state.zip_info)
            if evidence is None:
                result.rejected.append((label, why))
                continue
            summary = ImportSummary(execution_dir.name)
            try:
                scenario = _scenarios.get(scenario_dir.name, missing_ok=True)
                if scenario is None:
                    result.rejected.append((label, "no scenario file"))
                    continue
                written = _validate_evidence(evidence, scenario, None, summary, state.copy_gaps, state.zip_info)
            except HarnessError as exc:
                result.rejected.append((label, str(exc)))
                continue
            if written is None:
                result.left.append((label, "; ".join(why for _, why in summary.skipped)))
                continue
            error = _comparison_error(evidence, scenario) if written[1] == _baselines.REASON_ERROR else None
            if error is None:
                result.revalidated.append((label, *written))
            else:
                result.rejected.append((label, f"the observed payload could not be compared ({written[0]}, reason "
                                               f"{written[1]} written to {VALIDATION_FILE}): {error}"))
    result.rejected.extend((link.relative_to(evidence_dir).as_posix(), _ENTRY_LINK) for link in links)
    _settle_inferred(document, build_state(), None, result.gaps_changed)
    if document != load_yaml(GAPS_FILE):
        _write_gaps(document, original)
    regenerate()
    return result


def _execution_records(execution_id: str) -> tuple[dict[str, dict], list[str]]:
    """The valid record of each scenario directory of one UniKix execution,
    by scenario id, and the problems of every scenario that has none, each
    led by ``<execution_id>/<scenario>``. A scenario's record is its
    ``validation.yaml`` when it has one, else its ``result.yaml``, whose
    result record fields are taken. Either must keep the result record
    contract, name the unikix adapter, this execution and its own
    directory, which must be named by an id of the closed scenario id
    manifest, and hold a decoded outcome of the projection's shape
    (``_decoded_problems``). A symbolic link (a scenario directory or the
    record file), a missing, unreadable or invalid record is a problem of
    that scenario alone, never followed or compared. An id that is not an execution id, a
    linked folder on the way and a missing execution directory raise
    ``ReportError``."""
    if not isinstance(execution_id, str) or not _UNIKIX_ID_RE.fullmatch(execution_id):
        raise ReportError(f"{execution_id!r} is not a unikix execution id unikix-<yyyymmddThhmmssZ>-<8 hex>")
    directory = _evidence_folder(execution_id)
    if not directory.is_dir():
        raise ReportError(f"no evidence directory {display_path(directory)}")
    records: dict[str, dict] = {}
    problems: list[str] = []
    links: list[Path] = []
    for scenario_dir in _scenario_dirs(directory, links):
        scenario_id = scenario_dir.name
        label = f"{execution_id}/{scenario_id}"
        if scenario_id not in _scenarios.ALL_IDS:
            problems.append(f"{label}: {scenario_id} is not an id of the closed scenario id manifest")
            continue
        path = scenario_dir / VALIDATION_FILE
        if not path.is_symlink() and not path.exists():
            path = scenario_dir / RESULT_FILE
        if path.is_symlink():
            problems.append(f"{label}: {path.name} {_ENTRY_LINK}")
            continue
        if not path.exists():
            problems.append(f"{label}: holds neither {VALIDATION_FILE} nor {RESULT_FILE}")
            continue
        if not path.is_file():
            problems.append(f"{label}: {path.name} is not a file")
            continue
        try:
            document = load_yaml(path)
        except YAMLLoadError as exc:
            problems.append(f"{label}: {exc}")
            continue
        except OSError as exc:
            problems.append(f"{label}: {path.name} cannot be read: {exc.strerror or exc}")
            continue
        record = document
        if path.name == RESULT_FILE and isinstance(document, Mapping):
            record = {name: document[name] for name in _results.RECORD_FIELDS if name in document}
        issues = _unikix_record_problems(record, execution_id, scenario_id)
        if isinstance(record, Mapping):
            issues.extend(_decoded_problems(record.get("decoded")))
        if issues:
            problems.append(f"{label}: {path.name}: " + "; ".join(issues))
            continue
        records[scenario_id] = dict(record)
    problems.extend(f"{execution_id}/{link.name}: {_ENTRY_LINK}" for link in links)
    return records, problems


def compare_executions(first: str, second: str) -> list[str]:
    """The differences between two UniKix executions, compared through their
    normalized projections: each scenario's decoded outcome and status, from
    its valid ``validation.yaml``, else ``result.yaml``
    (``_execution_records``). The findings are every scenario whose record
    cannot be compared, every scenario with a valid record in only one of
    the two, each status and decoded outcome that differs and, when no
    scenario has a valid record in both, that nothing was compared; no
    finding means the two executions agree."""
    left, left_problems = _execution_records(first)
    right, right_problems = _execution_records(second)
    findings = left_problems + (right_problems if second != first else [])
    for scenario_id in sorted(set(left) ^ set(right)):
        findings.append(f"{scenario_id}: only in {first if scenario_id in left else second}")
    common = sorted(set(left) & set(right))
    for scenario_id in common:
        a, b = left[scenario_id], right[scenario_id]
        if a.get("status") != b.get("status"):
            findings.append(f"{scenario_id}: status {a.get('status')} in {first}, {b.get('status')} in {second}")
        decoded_a, decoded_b = a.get("decoded") or {}, b.get("decoded") or {}
        keys = sorted(
            key for key in set(decoded_a) | set(decoded_b) if decoded_a.get(key) != decoded_b.get(key)
        )
        if keys:
            findings.append(f"{scenario_id}: decoded outcome differs in {', '.join(map(str, keys))}")
    if not common:
        findings.append(f"no scenario has a valid record in both {first} and {second}; nothing was compared")
    return findings


# --------------------------------------------------------------------------
# Command line
# --------------------------------------------------------------------------

_EPILOG = """\
Without an option the matrix, the register, the coverage report and the
inventory's report fields are regenerated from the recorded results. Exit
status: 0 when the action succeeds and finds nothing, 1 when a check finds a
problem, an import rejects a record, a revalidation rejects evidence or
cannot revalidate a record, or two executions differ, 2 when the action
cannot run. A revalidation that only leaves records stale until a new run
exits 0.
"""


def _print_findings(title: str, findings: Sequence[str]) -> int:
    if not findings:
        print(f"{title}: ok")
        return 0
    print(f"{title}: {len(findings)} finding(s)")
    for finding in findings:
        print(f"  - {finding}")
    return 1


def _execution_id_argument(value: str) -> str:
    """The argparse type of an execution id: ``value`` when it is a whole
    UniKix execution id, else a usage error, so an empty, blank or path-like
    id never reaches the import or the comparison."""
    if not _UNIKIX_ID_RE.fullmatch(value):
        raise argparse.ArgumentTypeError(f"{value!r} is not a unikix execution id unikix-<yyyymmddThhmmssZ>-<8 hex>")
    return value


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
    group.add_argument("--import-unikix", metavar="EXECUTION_ID", type=_execution_id_argument,
                       help="import the evidence of a UniKix execution")
    group.add_argument("--revalidate", action="store_true", help="re-compare imported UniKix evidence")
    group.add_argument("--compare-executions", nargs=2, metavar=("ID1", "ID2"), type=_execution_id_argument,
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
        if args.import_unikix is not None:
            summary = import_unikix(args.import_unikix)
            print("\n".join(summary.lines()))
            return 1 if summary.rejected else 0
        if args.revalidate:
            revalidation = revalidate()
            print("\n".join(revalidation.lines()) or "no validation.yaml to revalidate")
            return 1 if revalidation.rejected else 0
        if args.compare_executions is not None:
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
