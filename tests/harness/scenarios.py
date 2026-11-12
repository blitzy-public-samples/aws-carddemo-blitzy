"""Scenario model, schema check, closed id manifest and adapter capability.

A scenario is one platform-neutral YAML mapping at
``tests/scenarios/<level>/<id>.yaml`` (``level`` is ``unit``, ``integration``
or ``e2e``). Its file stem is its id, ``<AREA>-<PROGRAM-OR-JOB>-<type>-<nn>``,
and the set of ids is closed (``MANIFEST``). Test modules call
``load(level, area)`` and parametrize one test over the result with the marks
``marker_names`` gives; ``tests/test_unit_harness.py`` runs ``validate`` on every
scenario and ``check_manifest`` on the tree. ``capable_adapters`` is the single
capability rule: the runner, the report and the conftest call it and never
re-derive it.

Schema (``validate`` is the authority; every key not listed here is refused)

``id``, ``level``, ``type``
    Optional restatements of the file stem, the folder and the id's type; when
    present they must agree with them. ``description`` is optional free text.
``inventory_ids``
    Non-empty list of ``tests/inventory/inventory.yaml`` ids, primary item
    first; the primary item's area is the scenario's area.
``tags``
    Subset of ``observed-only`` (needs an open ``uncertain-purpose`` gap among
    ``gap_ids``), ``suspected-defect`` (needs a ``GAP-DEF`` id) and
    ``inferred-not-observed`` (needs a ``GAP-INF`` id). A scenario whose
    baseline manifest says ``capture_platform: derived`` carries
    ``inferred-not-observed``.
``gap_ids``
    Ids of ``tests/inventory/gaps.yaml``.
``preconditions``
    ``datasets``: logical name (``tests/fixtures/datasets.yaml``) ->
    ``{base?: <logical with repository data>, records?: [<field map>],
    omit_keys?: [<primary key text>]}``; record keys are field keys of the
    dataset's layout (names, or paths, with ``(n)`` subscripts for table
    occurrences), names of the dataset's own ``fields``, or ``@raw``, and no
    two keys of a record name one field. Values are what ``harness.codec``
    encodes for the field (text for text fields, decimal text or integers for
    numbers), precondition values of two views of the same bytes
    (``REDEFINES``) agree on them, and subscripts stay within the OCCURS
    bounds; expected records also take a matcher, or ``{raw: <hex>}`` for
    invalid numeric bytes. ``parameters``: ``clock``
    (``'YYYY-MM-DD HH:MM:SS.hh'``, default ``DEFAULT_CLOCK``), ``clock_mode``
    (``fixed`` default, or ``relative``), ``parm`` (the EXEC PARM text),
    ``DATEPARM`` (the 80-byte ``YYYY-MM-DD YYYY-MM-DD`` record),
    ``sessions`` (1 or 2), ``ceedays_feedback`` (4 hex digits). ``faults``:
    ``[{target, operation, condition, after?}]`` whose target is a logical
    dataset, a region file or the TDQ of ``tests/adapters/unikix/resources.yaml``,
    and whose ``after`` is the number of the fault's ``operation`` requests on
    the target that succeed before it (0 or more), never a step index: ``read``
    counts READ, READ UPDATE, STARTBR, READNEXT and READPREV, ``write`` WRITE
    and ``rewrite`` REWRITE.
``inputs``
    Exactly one of ``program`` (unit level; an ``app/cbl`` member),
    ``job`` (integration level; an ``app/jcl`` member or ``TRNRPT00``, or an
    ordered list of them run against one catalog) and ``steps`` (e2e level;
    ``[{transid?, aid, fields?, session?, '@map'?}]`` where ``@map`` is the map
    expected before the fields are entered). A field value is text, or a
    run-date input ``{run-date: {part?: year|month|day, format?: <strftime>,
    offset_days?: <days>}}`` (``RUN_DATE_INPUT``, only with ``clock_mode:
    relative``) for input whose outcome depends on its relation to the run
    date: the adapter types the run date plus ``offset_days`` days, either
    one part of it as zero-padded digits or in ``format`` (default
    ``%Y-%m-%d``) (``input_text``). Batch inputs may add ``param_files``:
    logical name -> list of record lines.
``expected``
    ``return_code`` (integer, ``null`` for an abend, or ``baseline``),
    ``messages`` (list of text, or ``baseline``), ``reject_reason_codes``
    (list of CBTRN02C reason codes, as quoted four-digit text ``'0102'`` or
    the number ``102``; or ``baseline``), ``datasets`` (logical name -> value),
    ``file_state`` (logical name or reserved observation -> value; a record
    of ``@CATALOG``, ``@REGION-FILES`` or ``@TDQ:JOBS`` is a line of text or
    a whole-line matcher; ``@AFTER:<JOB>:<LOGICAL>`` is logical dataset
    ``<LOGICAL>`` of ``tests/fixtures/datasets.yaml`` at the end of job
    ``<JOB>``, which ``inputs.job`` runs exactly once, and its records are
    those of ``<LOGICAL>``; ``@BEFORE:<JOB>:@REGION-FILES`` and
    ``@AFTER:<JOB>:@REGION-FILES`` are ``@REGION-FILES`` just before job
    ``<JOB>`` is submitted and at its end, which also needs a ``<JOB>`` that
    ``inputs.job`` runs exactly once, and their records are lines as those
    of ``@REGION-FILES``),
    ``screens`` (one field map per step, each naming ``@map``; ``@text``, the
    full screen as one string, 24 rows of 80 characters joined by line breaks
    with every space kept (``adapters.base.screen_text``), only with
    ``@map: TEXT``) and ``matchers`` (name -> matcher). A dataset value is
    ``baseline`` or a mapping of ``records``, ``contains``, ``count`` and
    ``matchers`` that asserts ``records`` (``[]`` for an empty dataset), a
    ``count`` or ``contains`` with at least one record; ``matchers`` only
    qualify fields of the records given, so alone they assert nothing. At
    least one business outcome beyond ``return_code`` is asserted.

Matchers (``{match: <kind>, ...}``, ``MATCH_KINDS``) stand only for values that
come from a clock the adapter cannot fix, so they need ``clock_mode: relative``.
A line of a line observation may itself be a matcher whose ``format`` holds the
whole line around the date. Run-date inputs (``RUN_DATE_INPUT``,
``input_text``) need ``clock_mode: relative`` too: they are how a step
enters a date that only has meaning against the run date, such as the day
before it, and the expected screens assert its echo with the run-date matcher
of the same offset and format. Platform names (DSNs, ``DD_`` mappings, host:port
addresses, absolute paths) never appear under ``preconditions``, ``inputs`` or
``expected``, except inside the ``@CATALOG`` and ``@TDQ:JOBS`` observations,
whose lines are catalog entries and job JCL as the platform reports them. Sign-on steps use only the
demo users ``ADMIN001`` and ``USER0001`` with ``PASSWORD``; ``NOSUCHID`` is the
invented id of ``SEC-COSGN00C-negative-04``.

Loading never raises for a schema problem, so collection cannot crash on a bad
scenario. A file whose YAML structure ``harness.load_yaml`` refuses (a key
given twice, a recursive alias, nesting or alias expansion beyond its bounds)
loads as an empty scenario whose load problem ``validate`` reports; only a
file that is not UTF-8 text or not valid YAML raises ``ScenarioError``, naming
the file. Only regular files are read as scenarios: a symbolic link (the file,
its level folder or the scenarios folder) is never followed, ``load_file``
reports such a path in ``load_problems`` unread, and ``check_manifest``
reports it. ``get`` looks up only ids of the closed manifest. Parsed scenarios
are cached per file and re-read when the file changes. Callers share the
cached objects and must not modify their mappings.
"""

from __future__ import annotations

import datetime as _dt
import re
import stat
import sys
import threading
from dataclasses import dataclass, replace
from pathlib import Path, PurePosixPath
from typing import Any, Callable, Iterable, Iterator, TypeVar

import yaml

_TESTS_ROOT = str(Path(__file__).resolve().parents[1])
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from harness import (  # noqa: E402
    ADAPTER_NAMES,
    ADAPTERS_DIR,
    DEFAULT_CLOCK,
    FIXTURES_FILE,
    INVENTORY_DIR,
    MARKER_FOR_ADAPTER,
    REPO_ROOT,
    SCENARIOS_DIR,
    TESTS_ROOT,
    YAML_MAX_DEPTH,
    HarnessError,
    YAMLLoadError,
    YAMLStructureError,
    cob_current_date,
    display_path,
    dump_yaml,
    load_yaml,
    sha256_hex,
)
from harness import codec as _codec  # noqa: E402
from harness import layouts as _layouts  # noqa: E402

__all__ = [
    "ALL_IDS",
    "AREAS",
    "DEFAULT_CLOCK",
    "FIXED_IDS",
    "ID_RE",
    "LEVELS",
    "MANIFEST",
    "MARKER_FOR_ADAPTER",
    "MATCH_KINDS",
    "RESERVED_OBSERVATIONS",
    "RUN_DATE_INPUT",
    "RUN_DATE_INPUT_DEFAULT_FORMAT",
    "RUN_DATE_INPUT_PARTS",
    "Scenario",
    "ScenarioError",
    "asserted_keys",
    "capable_adapters",
    "check_manifest",
    "clock",
    "clock_mode",
    "digest",
    "get",
    "input_text",
    "is_run_date_input",
    "jcl_semantics_items",
    "job_names",
    "load",
    "load_all",
    "load_file",
    "marker_names",
    "only_unikix",
    "run_date_input_problem",
    "validate",
]

# --------------------------------------------------------------------------
# Vocabulary
# --------------------------------------------------------------------------

LEVELS = ("unit", "integration", "e2e")
AREAS = ("SEC", "MNU", "USR", "ACT", "CRD", "TRN", "BIL", "RPT", "FIN", "DAT", "UTL", "CFG", "BAT")
SCENARIO_TYPES = ("normal", "alternate", "boundary", "negative", "recovery")
TAGS = ("observed-only", "suspected-defect", "inferred-not-observed")

ID_RE = re.compile(
    r"^(?P<area>[A-Z]{3})-(?P<unit>[A-Z0-9]+)-"
    r"(?P<type>normal|alternate|boundary|negative|recovery)-(?P<nn>\d{2,3})$"
)
# COACTUPC has 130 negative scenarios, so only its negatives number with three digits.
_THREE_DIGIT_PREFIX = "ACT-COACTUPC-negative-"

# The input form each level runs: unit scenarios run a batch unit, integration
# scenarios a job (or a chain of jobs), e2e scenarios 3270 steps.
_LEVEL_INPUT = {"unit": "program", "integration": "job", "e2e": "steps"}

_TOP_KEYS = frozenset(
    {"id", "level", "type", "description", "inventory_ids", "tags", "gap_ids",
     "preconditions", "inputs", "expected"}
)
_REQUIRED_TOP_KEYS = ("inventory_ids", "tags", "gap_ids", "preconditions", "inputs", "expected")
_PRECONDITION_KEYS = frozenset({"datasets", "parameters", "faults"})
_DATASET_ENTRY_KEYS = frozenset({"base", "records", "omit_keys"})
PARAMETER_KEYS = ("clock", "clock_mode", "parm", "DATEPARM", "sessions", "ceedays_feedback")
CLOCK_MODES = ("fixed", "relative")
FAULT_OPERATIONS = ("open", "read", "write", "rewrite", "close")
FAULT_CONDITIONS = ("io-error", "notfnd", "locked", "unavailable", "abend")
_FAULT_KEYS = frozenset({"target", "operation", "condition", "after"})
_INPUT_KEYS = frozenset({"program", "job", "steps", "param_files"})
_STEP_KEYS = frozenset({"transid", "aid", "fields", "session", "@map"})
AIDS = frozenset({"ENTER", "CLEAR", "PA1", "PA2", "PA3"} | {f"PF{n}" for n in range(1, 25)})
EXPECTED_KEYS = (
    "return_code", "datasets", "file_state", "screens", "reject_reason_codes", "messages", "matchers"
)
_DATASET_VALUE_KEYS = frozenset({"records", "contains", "count", "matchers"})

BASELINE = "baseline"  # "compare with the baseline": the value lives in expected.yaml
RAW_KEY = "@raw"  # a precondition record given as its whole text image

OBS_CATALOG = "@CATALOG"
OBS_REGION_FILES = "@REGION-FILES"
OBS_TDQ_JOBS = "@TDQ:JOBS"
OBS_AIX_PREFIX = "@AIX:"
# @AFTER:<JOB>:<LOGICAL>: logical dataset <LOGICAL> at the end of job <JOB> of the chain.
OBS_AFTER_PREFIX = "@AFTER:"
# @BEFORE:<JOB>:@REGION-FILES and @AFTER:<JOB>:@REGION-FILES: the region files
# just before job <JOB> of the chain is submitted and at its end.
OBS_BEFORE_PREFIX = "@BEFORE:"
RESERVED_OBSERVATIONS = (
    OBS_CATALOG, OBS_REGION_FILES, OBS_TDQ_JOBS, OBS_AIX_PREFIX + "<path>", OBS_AFTER_PREFIX + "<JOB>:<LOGICAL>",
    OBS_BEFORE_PREFIX + "<JOB>:" + OBS_REGION_FILES, OBS_AFTER_PREFIX + "<JOB>:" + OBS_REGION_FILES,
)
# Observations whose lines are platform text (catalog entries, the JCL of the
# job the region wrote to JOBS): they hold DSNs by nature, so the platform
# neutrality check reads past them.
_PLATFORM_TEXT_OBSERVATIONS = frozenset({OBS_CATALOG, OBS_TDQ_JOBS})
_LINE_OBSERVATIONS = frozenset({OBS_CATALOG, OBS_REGION_FILES, OBS_TDQ_JOBS})
SCREEN_MAP = "@map"
SCREEN_TEXT = "@text"
TEXT_SCREEN = "TEXT"  # the @map of an unformatted (SEND TEXT) screen

# Matcher kind -> the options it accepts besides `match`. Each stands for a
# value the program derives from a clock the adapter cannot fix; on
# gnucobol-batch the run date is the fixed clock, so every one resolves to an
# exact value. `format` is a strftime format whose literal text may surround
# the date (a whole report or JCL line); the defaults are %Y-%m-%d for dates,
# %H:%M:%S for run-time and %Y-%m-%d-%H.%M.%S.%f for run-timestamp.
MATCH_KINDS = {
    "run-date": frozenset({"offset_days", "format"}),  # the run date plus offset_days days
    "run-time": frozenset({"format"}),  # a valid time of day
    "run-timestamp": frozenset({"format"}),  # a timestamp whose date part is the run date
    "run-month-start": frozenset({"format"}),  # first day of the run date's month
    "run-month-end": frozenset({"format"}),  # last day of the run date's month
    "run-year-start": frozenset({"format"}),  # 1 January of the run date's year
    "run-year-end": frozenset({"format"}),  # 31 December of the run date's year
}

# A step field value standing for a date relative to the run date, the input
# counterpart of a run-date matcher: an outcome that hinges on the typed date's
# relation to the run date (a date of birth equal to it is refused, the day
# before it accepted) cannot be written as fixed text while unikix cannot fix
# its clock (GAP-MIS-015), so like a matcher it needs clock_mode: relative.
# {run-date: {part?: <part>, format?: <strftime>, offset_days?: <days>}} types
# the run date plus offset_days days: with part, that part as zero-padded
# digits of the given width; otherwise in format (default %Y-%m-%d), which
# holds only the numeric date directives, so the text typed is the same under
# every locale. part and format exclude each other.
RUN_DATE_INPUT = "run-date"
RUN_DATE_INPUT_PARTS = {"year": 4, "month": 2, "day": 2}
RUN_DATE_INPUT_DEFAULT_FORMAT = "%Y-%m-%d"
_RUN_DATE_INPUT_KEYS = frozenset({"part", "format", "offset_days"})
_RUN_DATE_INPUT_DIRECTIVES = frozenset("Ymdyj%")

# Sign-on: only the demo users the README documents, against a local region.
SIGN_ON_MAP = "COSGN00"
# CC00 starts COSGN00C [app/csd/CARDDEMO.CSD:378-379], which sends COSGN00 first [app/cbl/COSGN00C.cbl:80-83,145-153].
SIGN_ON_TRANSID = "CC00"
SIGN_ON_USERS = ("ADMIN001", "USER0001")
SIGN_ON_PASSWORD = "PASSWORD"
INVENTED_USER = "NOSUCHID"
INVENTED_USER_SCENARIO = "SEC-COSGN00C-negative-04"

# The job chain whose expected outcome depends on the absolute date: the
# TRANREPT INCLUDE window filters the timestamps POSTTRAN has just written.
FIXED_CLOCK_SCENARIOS = frozenset({"BAT-CHAIN-normal-01"})

# The job CORPT00C writes to the JOBS queue [app/cbl/CORPT00C.cbl:84-127]; it has
# no app/jcl member of its own.
GENERATED_JOBS = frozenset({"TRNRPT00"})

# The reasons CBTRN02C moves to its PIC 9(04) WS-VALIDATION-FAIL-REASON
# [app/cbl/CBTRN02C.cbl:181,385,397,410,417,556]. A scenario gives each as its
# quoted four-digit text ('0102') or as the number (102).
_REJECT_REASON_CODES = frozenset({100, 101, 102, 103, 109})
_REJECT_REASON_TEXTS = frozenset(f"{code:04d}" for code in _REJECT_REASON_CODES)

PROGRAM_DIR = REPO_ROOT / "app" / "cbl"
JCL_DIR = REPO_ROOT / "app" / "jcl"
CSD_FILE = REPO_ROOT / "app" / "csd" / "CARDDEMO.CSD"
INVENTORY_FILE = INVENTORY_DIR / "inventory.yaml"
GAPS_FILE = INVENTORY_DIR / "gaps.yaml"
GNUCOBOL_PROGRAMS_FILE = ADAPTERS_DIR / "gnucobol_batch" / "programs.yaml"
GNUCOBOL_JOBS_FILE = ADAPTERS_DIR / "gnucobol_batch" / "jobs.yaml"
UNIKIX_RESOURCES_FILE = ADAPTERS_DIR / "unikix" / "resources.yaml"


class ScenarioError(HarnessError):
    """A scenario file that cannot be parsed as YAML."""


# --------------------------------------------------------------------------
# The closed id manifest
# --------------------------------------------------------------------------

# (prefix, ranges): "type-a..b" stands for every number from a to b, written
# with as many digits as a.
_READ_PROGRAM_CASES = "normal-01 boundary-01 recovery-01..03"
_UNIT_SPEC = (
    ("DAT-CBACT01C", _READ_PROGRAM_CASES),
    ("DAT-CBACT02C", _READ_PROGRAM_CASES),
    ("DAT-CBACT03C", _READ_PROGRAM_CASES),
    ("DAT-CBCUS01C", _READ_PROGRAM_CASES),
    ("TRN-CBTRN01C", "normal-01 boundary-01 negative-01..02 recovery-01..03"),
    ("TRN-CBTRN02C", "normal-01 alternate-01 boundary-01..03 negative-01..05 recovery-01..05"),
    ("FIN-CBACT04C", "normal-01 alternate-01..02 boundary-01..03 negative-01 recovery-01..05"),
    ("RPT-CBTRN03C", "normal-01..02 boundary-01..05 negative-01..03 recovery-01..04"),
    ("RPT-CBSTM03A", "normal-01..02 boundary-01..03 recovery-01..03"),
    ("UTL-CSUTLDTC", "normal-01..02 boundary-01..02 negative-01..14"),
)
_LOAD_AND_READ_JOBS = (
    "ACCTFILE", "CARDFILE", "CUSTFILE", "XREFFILE", "TRANFILE", "DISCGRP", "TCATBALF",
    "TRANCATG", "TRANTYPE", "TRANIDX", "TRANBKP", "COMBTRAN", "DUSRSECJ", "DEFGDGB",
    "DALYREJS", "REPTFILE", "DEFCUST", "READACCT", "READCARD", "READCUST", "READXREF",
)
_INTEGRATION_SPEC = (
    *((f"DAT-{job}", "normal-01") for job in _LOAD_AND_READ_JOBS),
    ("TRN-POSTTRAN", "normal-01"),
    ("FIN-INTCALC", "normal-01"),
    ("RPT-TRANREPT", "normal-01"),
    ("RPT-TRNRPT00", "normal-01"),
    ("RPT-CREASTMT", "normal-01"),
    ("RPT-PRTCATBL", "normal-01"),
    ("BAT-CHAIN", "normal-01"),
    ("BAT-POSTINT", "normal-01"),
    # Run only by unikix: CICS-region jobs, JCL semantics, the README run order.
    ("CFG-CBADMCDJ", "normal-01"),
    ("CFG-OPENFIL", "normal-01"),
    ("CFG-CLOSEFIL", "normal-01"),
    ("DAT-ACCTFILE", "recovery-01"),
    ("DAT-POSTTRAN", "recovery-01 alternate-01"),
    ("DAT-TRANBKP", "recovery-01"),
    ("DAT-XREFFILE", "alternate-01"),
    ("RPT-CREASTMT", "recovery-01"),
    ("RPT-TRNRPT00", "alternate-01"),
    ("BAT-RUNORDER", "normal-01"),
)
_E2E_SPEC = (
    ("SEC-COSGN00C", "normal-01..02 alternate-01 negative-01..05 recovery-01"),
    ("MNU-COMEN01C", "alternate-01..12 negative-01..02"),
    ("MNU-COADM01C", "alternate-01..06 negative-01..02"),
    ("USR-COUSR00C", "normal-01 alternate-01..09 negative-01..02 recovery-01 boundary-01..02"),
    ("USR-COUSR01C", "normal-01..02 alternate-01..02 negative-01..07 recovery-01"),
    ("USR-COUSR02C", "normal-01..02 alternate-01..04 negative-01..07 recovery-01..02"),
    ("USR-COUSR03C", "normal-01..02 alternate-01..03 negative-01..03 recovery-01..02"),
    ("ACT-COACTVWC", "normal-01 alternate-01..03 negative-01..08 recovery-01..05"),
    ("ACT-COACTUPC", "normal-01..04 alternate-01..06 negative-001..130 recovery-01..09 boundary-01..28"),
    ("CRD-COCRDLIC", "normal-01 alternate-01..09 negative-01..05 recovery-01..04 boundary-01..02"),
    ("CRD-COCRDSLC", "normal-01 alternate-01..03 negative-01..08 recovery-01..04"),
    ("CRD-COCRDUPC", "normal-01..03 alternate-01..06 negative-01..12 recovery-01..06 boundary-01..08"),
    ("TRN-COTRN00C", "normal-01 alternate-01..08 negative-01..03 recovery-01 boundary-01..02"),
    ("TRN-COTRN01C", "normal-01 alternate-01..02 negative-01..03 recovery-01"),
    ("TRN-COTRN02C", "normal-01..03 alternate-01..04 negative-01..28 recovery-01..04 boundary-01..03"),
    ("BIL-COBIL00C", "normal-01..04 alternate-01..03 negative-01..07 recovery-01..05 boundary-01..03"),
    ("RPT-CORPT00C", "normal-01..05 alternate-01..03 negative-01..17 recovery-01 boundary-01..05"),
    ("CFG-FCT", "normal-01"),
)
# The e2e ids the test plan fixes by name; every unit and integration id is fixed too.
_E2E_FIXED = (
    "SEC-COSGN00C-normal-01", "SEC-COSGN00C-normal-02", "SEC-COSGN00C-negative-01",
    "SEC-COSGN00C-negative-02", "SEC-COSGN00C-negative-03", "SEC-COSGN00C-negative-04",
    "SEC-COSGN00C-negative-05", "SEC-COSGN00C-alternate-01", "SEC-COSGN00C-recovery-01",
    "MNU-COADM01C-alternate-02",
    "USR-COUSR01C-normal-01", "USR-COUSR00C-boundary-01", "USR-COUSR00C-boundary-02",
    "ACT-COACTVWC-recovery-01", "ACT-COACTUPC-alternate-02",
    "CRD-COCRDUPC-alternate-02", "CRD-COCRDLIC-boundary-01", "CRD-COCRDLIC-boundary-02",
    "TRN-COTRN02C-normal-01", "TRN-COTRN02C-negative-03",
    "TRN-COTRN00C-boundary-01", "TRN-COTRN00C-boundary-02",
    "BIL-COBIL00C-normal-01",
    "RPT-CORPT00C-normal-01", "RPT-CORPT00C-normal-02", "RPT-CORPT00C-normal-03",
    "CFG-FCT-normal-01",
)
_MANIFEST_SIZES = {"unit": 94, "integration": 40, "e2e": 467}

_RANGE_RE = re.compile(r"(?P<type>[a-z]+)-(?P<first>\d+)(?:\.\.(?P<last>\d+))?")


def _expand(spec: Iterable[tuple[str, str]]) -> list[str]:
    ids: list[str] = []
    for prefix, ranges in spec:
        for token in ranges.split():
            match = _RANGE_RE.fullmatch(token)
            if match is None:
                raise HarnessError(f"manifest range {prefix} {token!r} is not 'type-a' or 'type-a..b'")
            first = match["first"]
            last = match["last"] or first
            for number in range(int(first), int(last) + 1):
                ids.append(f"{prefix}-{match['type']}-{number:0{len(first)}d}")
    return ids


def _build_manifest() -> dict[str, frozenset[str]]:
    manifest: dict[str, frozenset[str]] = {}
    seen: set[str] = set()
    for level, spec in (("unit", _UNIT_SPEC), ("integration", _INTEGRATION_SPEC), ("e2e", _E2E_SPEC)):
        ids = _expand(spec)
        repeated = {item for index, item in enumerate(ids) if item in ids[:index]}
        duplicates = sorted(repeated | (set(ids) & seen))
        if duplicates:
            raise HarnessError(f"manifest ids listed twice: {', '.join(duplicates)}")
        if len(ids) != _MANIFEST_SIZES[level]:
            raise HarnessError(
                f"the {level} manifest holds {len(ids)} ids, not {_MANIFEST_SIZES[level]}"
            )
        for item in ids:
            match = ID_RE.fullmatch(item)
            if match is None or match["area"] not in AREAS:
                raise HarnessError(f"manifest id {item} does not follow the id pattern")
            if (len(match["nn"]) == 3) != item.startswith(_THREE_DIGIT_PREFIX):
                raise HarnessError(f"manifest id {item} has the wrong number of digits")
        seen.update(ids)
        manifest[level] = frozenset(ids)
    return manifest


MANIFEST = _build_manifest()
ALL_IDS = frozenset().union(*MANIFEST.values())
_LEVEL_OF = {item: level for level, ids in MANIFEST.items() for item in ids}
FIXED_IDS = frozenset(MANIFEST["unit"] | MANIFEST["integration"] | set(_E2E_FIXED))

if len(ALL_IDS) != sum(_MANIFEST_SIZES.values()):
    raise HarnessError(f"the manifest holds {len(ALL_IDS)} ids, not {sum(_MANIFEST_SIZES.values())}")
if not FIXED_IDS <= ALL_IDS:
    raise HarnessError(f"fixed ids outside the manifest: {', '.join(sorted(FIXED_IDS - ALL_IDS))}")


# --------------------------------------------------------------------------
# Model and loading
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class Scenario:
    """One parsed scenario file. ``id`` is the file stem and ``level`` the
    folder; ``area``, ``program_or_job``, ``type`` and ``nn`` come from the id
    (empty when the stem does not follow the id pattern). ``inventory_ids``,
    ``tags``, ``gap_ids``, ``preconditions``, ``inputs`` and ``expected`` are
    views of ``data`` (the whole parsed file), empty where the file's value has
    the wrong shape; ``validate`` reports such problems from ``data``.
    ``load_problems`` holds what made the document itself unusable."""

    id: str
    level: str
    area: str
    program_or_job: str
    type: str
    nn: str
    inventory_ids: tuple
    tags: tuple
    gap_ids: tuple
    preconditions: dict
    inputs: dict
    expected: dict
    data: dict
    path: Path
    load_problems: tuple = ()

    # The generated hash would hash the mappings and fail; equal scenarios
    # share their id and path, so hashing those keeps hash and equality aligned.
    def __hash__(self) -> int:
        return hash((self.id, str(self.path)))


def _scenario(path: Path, level: str, document: Any, problem: str | None = None) -> Scenario:
    """The scenario of ``document``; ``problem`` is a load problem found
    before it (the structure ``load_yaml`` refused, with ``document`` empty)."""
    problems: list[str] = [problem] if problem else []
    if isinstance(document, dict):
        data = document
    else:
        data = {}
        problems.append(
            "the file is empty" if document is None
            else f"the file holds a {type(document).__name__}, not a mapping"
        )
    stem = path.stem
    match = ID_RE.fullmatch(stem)
    if match is not None:
        area, unit, kind, nn = match["area"], match["unit"], match["type"], match["nn"]
    else:
        parts = stem.split("-")
        area, unit, kind, nn = parts[0], (parts[1] if len(parts) > 1 else ""), "", ""

    def sequence(key: str) -> tuple:
        value = data.get(key)
        return tuple(value) if isinstance(value, list) else ()

    def mapping(key: str) -> dict:
        value = data.get(key)
        return value if isinstance(value, dict) else {}

    return Scenario(
        id=stem,
        level=level,
        area=area,
        program_or_job=unit,
        type=kind,
        nn=nn,
        inventory_ids=sequence("inventory_ids"),
        tags=sequence("tags"),
        gap_ids=sequence("gap_ids"),
        preconditions=mapping("preconditions"),
        inputs=mapping("inputs"),
        expected=mapping("expected"),
        data=data,
        path=path,
        load_problems=tuple(problems),
    )


def _refusal(path: Path) -> str | None:
    """Why ``path`` (``<scenarios folder>/<level>/<name>``) is not read as a
    scenario file, or ``None`` when it is read: the scenarios folder (the
    file's folder's parent), the level folder (the file's folder) or the file
    is a symbolic link, or the file is not a regular file. The folders are
    examined before the file, outermost first, so nothing under a linked
    folder is examined; folders above the scenarios folder are not examined,
    so a tests root reached through a linked path still loads. Raises
    ``OSError`` when the file cannot be examined (``FileNotFoundError`` when
    it is absent)."""
    if path.parent.parent.is_symlink():
        return "the scenarios folder is a symbolic link, so the file was not read"
    if path.parent.is_symlink():
        return f"the level folder {path.parent.name} is a symbolic link, so the file was not read"
    mode = path.lstat().st_mode
    if stat.S_ISLNK(mode):
        return "the file is a symbolic link, not a regular file, so it was not read"
    if not stat.S_ISREG(mode):
        return "the path is not a regular file, so it was not read"
    return None


def load_file(path: str | Path, level: str | None = None) -> Scenario:
    """Parse one scenario file; ``level`` defaults to its folder's name.

    Raises ``ScenarioError``, naming the file, only when the file is not UTF-8
    text or not valid YAML, and ``FileNotFoundError`` when it is absent. A file
    whose YAML structure ``load_yaml`` refuses (a key given twice, a recursive
    alias, nesting or alias expansion beyond its bounds) gives a scenario with
    empty data whose load problem ``validate`` reports, so collection goes on.
    Only a regular file whose level folder and scenarios folder are not
    symbolic links is read: a symbolic link (whatever it points to) at any of
    the three, or any other kind of file, gives an empty scenario whose
    ``load_problems`` say why it was not read. Folders above the scenarios
    folder are not examined."""
    file_path = Path(path)
    level = level or file_path.parent.name
    refusal = _refusal(file_path)
    if refusal is not None:
        return replace(_scenario(file_path, level, {}), load_problems=(refusal,))
    problem = None
    try:
        document = load_yaml(file_path)
    except YAMLStructureError as exc:
        document, problem = {}, exc.problem
    except YAMLLoadError as exc:
        raise ScenarioError(str(exc)) from None
    return _scenario(file_path, level, document, problem)


_LOCK = threading.Lock()
_FILE_CACHE: dict[tuple[str, str], tuple[tuple[int, int], Scenario]] = {}


def _stamp(path: Path) -> tuple[int, int] | None:
    try:
        status = path.stat()
    except FileNotFoundError:
        return None
    return status.st_mtime_ns, status.st_size


def _regular_file(path: Path) -> bool:
    """Whether ``path`` itself is a regular file; a symbolic link never is,
    whatever it points to."""
    try:
        return stat.S_ISREG(path.lstat().st_mode)
    except OSError:
        return False


def _scenario_file(path: Path) -> bool:
    """Whether ``path`` (``<scenarios folder>/<level>/<name>``) is read as a
    scenario file: a ``.yaml`` file that ``_refusal`` does not refuse, the
    rule ``load_file`` applies. An absent or unexaminable path is not one."""
    if path.suffix != ".yaml":
        return False
    try:
        return _refusal(path) is None
    except OSError:
        return False


def _cached_file(path: Path, level: str) -> Scenario:
    stamp = _stamp(path)
    key = (str(path), level)
    with _LOCK:
        hit = _FILE_CACHE.get(key)
    if hit is not None and hit[0] == stamp:
        return hit[1]
    scenario = load_file(path, level)
    with _LOCK:
        _FILE_CACHE[key] = (stamp, scenario)
    return scenario


def _level_files(level: str, scenarios_dir: Path, prefix: str = "") -> list[Path]:
    folder = scenarios_dir / level
    if scenarios_dir.is_symlink() or folder.is_symlink() or not folder.is_dir():
        return []
    return sorted(
        path for path in folder.iterdir()
        if path.stem.startswith(prefix) and _scenario_file(path)
    )


def load(level: str, area: str) -> list[Scenario]:
    """Every scenario of ``tests/scenarios/<level>/<AREA>-*.yaml``, sorted by id.
    Files outside the manifest are included, so ``validate`` can refuse them."""
    if level not in LEVELS:
        raise ValueError(f"level {level!r} is not one of {', '.join(LEVELS)}")
    code = str(area).upper()
    if code not in AREAS:
        raise ValueError(f"area {area!r} is not one of {', '.join(AREAS)}")
    scenarios = [_cached_file(path, level) for path in _level_files(level, SCENARIOS_DIR, f"{code}-")]
    return sorted(scenarios, key=lambda scenario: scenario.id)


def load_all() -> list[Scenario]:
    """Every scenario of every level, sorted by id (then level)."""
    scenarios = [
        _cached_file(path, level) for level in LEVELS for path in _level_files(level, SCENARIOS_DIR)
    ]
    return sorted(scenarios, key=lambda scenario: (scenario.id, scenario.level))


def get(scenario_id: str, missing_ok: bool = False) -> Scenario | None:
    """The scenario with this id, looked up in its manifest level first, then
    in the other level folders (so a misplaced file is still found). Only ids
    of the closed manifest are looked up: no path is built from any other
    value, and a symbolic link counts as no file. An id outside the manifest,
    like a missing file, raises ``KeyError``, or returns ``None`` with
    ``missing_ok``."""
    if not isinstance(scenario_id, str) or scenario_id not in ALL_IDS:
        if missing_ok:
            return None
        raise KeyError(f"{scenario_id!r} is not an id of the closed scenario id manifest")
    levels = [_LEVEL_OF[scenario_id]]
    levels += [level for level in LEVELS if level not in levels]
    for level in levels:
        path = SCENARIOS_DIR / level / f"{scenario_id}.yaml"
        if _scenario_file(path):
            return _cached_file(path, level)
    if missing_ok:
        return None
    raise KeyError(f"no scenario file for {scenario_id} under tests/scenarios")


# --------------------------------------------------------------------------
# Reference data, re-read when its file changes
# --------------------------------------------------------------------------

_T = TypeVar("_T")
_REFERENCE_CACHE: dict[tuple[str, str], tuple[tuple[int, int] | None, Any]] = {}


def _reference(path: Path, build: Callable[[Path], _T]) -> _T:
    stamp = _stamp(path)
    key = (str(path), build.__qualname__)
    with _LOCK:
        hit = _REFERENCE_CACHE.get(key)
    if hit is not None and hit[0] == stamp:
        return hit[1]
    value = build(path)
    with _LOCK:
        _REFERENCE_CACHE[key] = (stamp, value)
    return value


def _named_entries(section: Any, label: str) -> dict[str, Any]:
    """A YAML section as ``{name: entry}``: a mapping as is, a list by each
    entry's ``name`` (or the entry itself when it is a string). A name given
    twice raises ``HarnessError`` naming ``label`` (the file), rather than
    letting the last entry silently win; ``fixtures`` refuses it as well."""
    named: list[tuple[str, Any]] = []
    if isinstance(section, dict):
        named = [(str(name), entry) for name, entry in section.items()]
    elif isinstance(section, list):
        for entry in section:
            if isinstance(entry, str):
                named.append((entry, {}))
            elif isinstance(entry, dict) and isinstance(entry.get("name"), str):
                named.append((entry["name"], entry))
    entries: dict[str, Any] = {}
    for name, entry in named:
        if name in entries:
            raise HarnessError(f"{label}: {name} is defined twice")
        entries[name] = entry
    return entries


def _section(path: Path, name: str) -> dict[str, Any]:
    """The entries of the top-level ``name`` section, or the top-level keys
    when the file has no such section; empty when the file is missing."""
    document = load_yaml(path, missing_ok=True)
    if not isinstance(document, dict):
        return {}
    if name in document:
        return _named_entries(document[name], display_path(path))
    return {str(key): value for key, value in document.items() if key != "schema_version"}


def _build_inventory(path: Path) -> dict[str, dict]:
    document = load_yaml(path, missing_ok=True)
    items = document.get("items") if isinstance(document, dict) else None
    return {
        item["id"]: item for item in (items or [])
        if isinstance(item, dict) and isinstance(item.get("id"), str)
    }


def _build_gaps(path: Path) -> dict[str, dict]:
    document = load_yaml(path, missing_ok=True)
    section = document.get("gaps") if isinstance(document, dict) else None
    if isinstance(section, dict):
        return {str(gap_id): entry for gap_id, entry in section.items() if isinstance(entry, dict)}
    gaps: dict[str, dict] = {}
    for entry in section or []:
        if isinstance(entry, dict):
            gap_id = entry.get("gap_id", entry.get("id"))
            if isinstance(gap_id, str):
                gaps[gap_id] = entry
    return gaps


def _build_datasets(path: Path) -> dict[str, dict]:
    return {
        name: (entry if isinstance(entry, dict) else {})
        for name, entry in _section(path, "datasets").items()
    }


def _build_programs(path: Path) -> dict[str, Any]:
    return _section(path, "programs")


def _build_jobs(path: Path) -> frozenset[str]:
    return frozenset(_section(path, "jobs"))


@dataclass(frozen=True)
class _Resources:
    jobs: frozenset[str]
    region_files: frozenset[str]
    tdq: frozenset[str]


def _build_resources(path: Path) -> _Resources:
    document = load_yaml(path, missing_ok=True)
    if not isinstance(document, dict):
        return _Resources(frozenset(), frozenset(), frozenset())
    label = display_path(path)
    tdq = document.get("tdq")
    queues = _named_entries(tdq if isinstance(tdq, list) else [tdq] if isinstance(tdq, dict) else [], label)
    return _Resources(
        jobs=frozenset(_section(path, "jobs")),
        region_files=frozenset(_named_entries(document.get("region_files"), label)),
        tdq=frozenset(queues),
    )


_CSD_TRANSACTION_RE = re.compile(r"^\s*DEFINE\s+TRANSACTION\(([^)\s]+)\)", re.MULTILINE)


def _build_transactions(path: Path) -> frozenset[str]:
    try:
        text = path.read_text(encoding="latin-1")
    except FileNotFoundError:
        return frozenset()
    return frozenset(name.upper() for name in _CSD_TRANSACTION_RE.findall(text))


def _member_stems(path: Path) -> frozenset[str]:
    if not path.is_dir():
        return frozenset()
    return frozenset(entry.stem.upper() for entry in path.iterdir() if entry.is_file())


def _inventory() -> dict[str, dict]:
    return _reference(INVENTORY_FILE, _build_inventory)


def _gaps() -> dict[str, dict]:
    return _reference(GAPS_FILE, _build_gaps)


def _datasets() -> dict[str, dict]:
    return _reference(FIXTURES_FILE, _build_datasets)


def _gnucobol_programs() -> dict[str, Any]:
    return _reference(GNUCOBOL_PROGRAMS_FILE, _build_programs)


def _gnucobol_jobs() -> frozenset[str]:
    return _reference(GNUCOBOL_JOBS_FILE, _build_jobs)


def _resources() -> _Resources:
    return _reference(UNIKIX_RESOURCES_FILE, _build_resources)


def _transactions() -> frozenset[str]:
    return _reference(CSD_FILE, _build_transactions)


def _programs_in_app() -> frozenset[str]:
    return _reference(PROGRAM_DIR, _member_stems)


def _jobs_in_app() -> frozenset[str]:
    return _reference(JCL_DIR, _member_stems)


def _screen_fields() -> dict[str, frozenset[str]]:
    """Mapset stem -> the names of its BMS fields (``layouts.yaml`` screens)."""
    screens = _layouts.load().get("screens") or {}
    return {
        str(stem): frozenset(
            entry["name"] for entry in (screen.get("fields") or [])
            if isinstance(entry, dict) and isinstance(entry.get("name"), str)
        )
        for stem, screen in screens.items()
        if isinstance(screen, dict)
    }


# A record key: a field key, then one 1-based subscript per OCCURS level.
_KEY_RE = re.compile(r"^(?P<base>.+?)(?:\((?P<indices>[0-9]+(?:,[0-9]+)*)\))?$")
# harness.fixtures materializes every record in this encoding (its
# _FIXTURE_ENCODING), so precondition values are checked as they will be encoded.
_RECORD_ENCODING = "ascii"


@dataclass(frozen=True)
class _RecordField:
    """A field a record key can name: its ``harness.codec`` descriptor, the
    OCCURS counts of the field and of each group above it (outermost first;
    empty outside any table) and, for a dataset's own field, its own layout."""

    descriptor: dict[str, Any]
    dimensions: tuple[int, ...] = ()
    layout: str | None = None


def _layout_fields(layout: str) -> dict[str, _RecordField] | None:
    """The fields a record of ``layout`` can name, by the keys
    ``layouts.field_map`` gives (paths, and names unique in the record), or
    ``None`` for a layout ``tests/inventory/layouts.yaml`` lacks. A layout file
    that cannot be read raises, so ``validate`` reports it."""
    _layouts.load()
    try:
        entries = _layouts.record(layout)["fields"]
        keys = _layouts.field_map(layout)
    except HarnessError:
        return None
    # The rule harness.codec lays tables out by: a field's OCCURS count follows its groups'.
    dimensions: dict[str, tuple[int, ...]] = {}
    for entry in entries:
        path = entry["path"]
        occurs = entry.get("occurs") or 0
        dimensions[path] = dimensions.get(path.rpartition(".")[0], ()) + ((occurs,) if occurs else ())
    return {key: _RecordField(entry, dimensions[entry["path"]]) for key, entry in keys.items()}


def _own_descriptor(entry: dict) -> dict[str, Any]:
    """The ``harness.codec`` descriptor of a datasets.yaml ``fields`` entry. Its
    defaults mirror harness.fixtures (``DatasetField.descriptor`` and the type
    its ``_fields`` assumes), which imports this module and so cannot be imported."""
    layout = entry.get("layout")
    type_ = entry.get("type", "group" if layout else "alphanumeric")
    length, digits = entry.get("length"), entry.get("digits")
    if digits is None and _is_int(length) and type_ in ("zoned", "zoned-signed"):
        digits = length
    elif digits is None and _is_int(length) and type_ == "comp-3":
        digits = length * 2 - 1
    scale = entry.get("scale")
    return {
        "name": entry["name"],
        "type": type_,
        "length": length,
        "digits": digits,
        "scale": 0 if scale is None else scale,
        "signed": type_ == "zoned-signed",
    }


def _record_fields(
    spec: dict | None,
) -> tuple[dict[str, _RecordField] | None, dict[str, _RecordField]] | None:
    """The fields a record of a dataset can name: its layout's (``None`` when
    ``tests/inventory/layouts.yaml`` lacks the layout it names) and, by name,
    the dataset's own ``fields`` (the inner structure datasets.yaml gives line
    data such as DATEPARM and DTCIN); ``None`` when it names neither."""
    if not spec:
        return None
    layout = spec.get("layout")
    entries = spec.get("fields")
    own = {
        entry["name"]: _RecordField(
            _own_descriptor(entry), (), entry["layout"] if isinstance(entry.get("layout"), str) else None
        )
        for entry in (entries if isinstance(entries, list) else [])
        if isinstance(entry, dict) and isinstance(entry.get("name"), str)
    }
    if not isinstance(layout, str) and not own:
        return None
    return (_layout_fields(layout) if isinstance(layout, str) else {}), own


def _repository_backed(entry: dict) -> bool:
    """Whether a ``datasets.yaml`` entry's ``source`` is repository data: a
    relative path under ``app/`` without ``..`` that, symbolic links followed,
    stays inside the repository. An absent file still counts: the fixture
    build reports it as a missing prerequisite."""
    source = entry.get("source")
    if not isinstance(source, str):
        return False
    path = PurePosixPath(source.strip())
    if path.is_absolute() or ".." in path.parts or len(path.parts) < 2 or path.parts[0] != "app":
        return False
    root = REPO_ROOT.resolve()
    try:
        return root.joinpath(*path.parts).resolve().is_relative_to(root)
    except (OSError, RuntimeError, ValueError):
        # A symbolic link loop (RuntimeError on Python 3.11), an unreadable
        # path, or one holding a NUL byte.
        return False


def _aix_paths() -> dict[str, str]:
    """Alternate-index path -> its base dataset, from the ``alternate_keys`` of
    ``tests/fixtures/datasets.yaml``; ``@AIX:<path>`` names these paths."""
    paths: dict[str, str] = {}
    for name, entry in _datasets().items():
        for key in entry.get("alternate_keys") or []:
            if isinstance(key, dict) and isinstance(key.get("path"), str):
                paths.setdefault(key["path"], name)
    return paths


# --------------------------------------------------------------------------
# Capability and markers
# --------------------------------------------------------------------------

_JCL_SEMANTICS_KINDS = frozenset({"recovery", "config"})
_JCL_SOURCE_PREFIXES = ("app/jcl/", "app/proc/")


def jcl_semantics_items() -> frozenset[str]:
    """The inventory's JCL-semantics items: integration-level ``recovery`` and
    ``config`` items whose source is JCL or a procedure (COND, DISP, MAXCC,
    GDG rolls, AIX/PATH, proc resolution, region actions, CSD install).

    Only a real job scheduler exercises them; the gnucobol-batch job driver
    records COND and DISP and never evaluates them. A scenario that lists one
    of these items is therefore never gnucobol-batch capable, although its jobs
    are in the driver's step tables."""
    return frozenset(
        item_id for item_id, item in _inventory().items()
        if item.get("level") == "integration"
        and item.get("kind") in _JCL_SEMANTICS_KINDS
        and str(item.get("source") or "").startswith(_JCL_SOURCE_PREFIXES)
    )


def job_names(s: Scenario) -> tuple[str, ...]:
    """``inputs.job`` as an ordered tuple: one job, or the chain in order;
    empty when the scenario names no job or the value is malformed."""
    job = s.inputs.get("job")
    if isinstance(job, str):
        return (job,)
    if isinstance(job, list) and job and all(isinstance(name, str) for name in job):
        return tuple(job)
    return ()


def capable_adapters(s: Scenario) -> tuple[str, ...]:
    """The adapters that can run ``s``, in ``ADAPTER_NAMES`` order.

    ``gnucobol-batch``: ``inputs.program`` is a unit of its ``programs.yaml``
    that is launched rather than only called, or every job of ``inputs.job`` is
    in its ``jobs.yaml`` -- unless the scenario lists a JCL-semantics item
    (``jcl_semantics_items``). ``unikix``: every job of ``inputs.job`` is in
    its ``resources.yaml`` ``jobs``, or the scenario has ``inputs.steps`` (an
    empty list included). A missing mapping file grants no capability."""
    capable: set[str] = set()
    jobs = job_names(s)
    program = s.inputs.get("program")
    if not set(map(str, s.inventory_ids)) & jcl_semantics_items():
        if isinstance(program, str):
            programs = _gnucobol_programs()
            entry = programs.get(program)
            launched = not (isinstance(entry, dict) and entry.get("run") == "called")
            if program in programs and launched:
                capable.add("gnucobol-batch")
        elif jobs and all(job in _gnucobol_jobs() for job in jobs):
            capable.add("gnucobol-batch")
    if jobs and all(job in _resources().jobs for job in jobs):
        capable.add("unikix")
    if isinstance(s.inputs.get("steps"), list):
        capable.add("unikix")
    return tuple(name for name in ADAPTER_NAMES if name in capable)


def marker_names(s: Scenario) -> list[str]:
    """pytest markers of ``s``: its level, ``chain`` for a job chain that
    gnucobol-batch runs, then one marker per capable adapter."""
    adapters = capable_adapters(s)
    names = [s.level]
    if isinstance(s.inputs.get("job"), list) and "gnucobol-batch" in adapters:
        names.append("chain")
    names.extend(MARKER_FOR_ADAPTER[name] for name in adapters)
    return names


def only_unikix(s: Scenario) -> bool:
    """Whether only ``unikix`` can run ``s`` (its items are UniKix-dependent)."""
    return capable_adapters(s) == ("unikix",)


# --------------------------------------------------------------------------
# Accessors
# --------------------------------------------------------------------------


def _parameters(s: Scenario) -> dict:
    parameters = s.preconditions.get("parameters")
    return parameters if isinstance(parameters, dict) else {}


def clock(s: Scenario) -> str:
    """The run clock ``YYYY-MM-DD HH:MM:SS.hh``: the scenario's, or ``DEFAULT_CLOCK``."""
    value = _parameters(s).get("clock")
    return value if isinstance(value, str) else DEFAULT_CLOCK


def clock_mode(s: Scenario) -> str:
    """``fixed`` (the default) or ``relative``, as the scenario declares it."""
    value = _parameters(s).get("clock_mode")
    return value if isinstance(value, str) else "fixed"


def is_run_date_input(value: Any) -> bool:
    """Whether a step field value is a run-date input (``RUN_DATE_INPUT``)
    rather than text; ``run_date_input_problem`` says whether it is valid."""
    return isinstance(value, dict) and RUN_DATE_INPUT in value


def run_date_input_problem(value: Any) -> str | None:
    """Why ``value`` is not a valid run-date input ``{run-date: {part?:
    year|month|day, format?: <strftime>, offset_days?: <days>}}``, or ``None``
    when it is one."""
    shape = (
        f"{{{RUN_DATE_INPUT}: {{part?: {'|'.join(RUN_DATE_INPUT_PARTS)}, format?: <strftime>, "
        "offset_days?: <days>}}"
    )
    if not isinstance(value, dict) or list(value) != [RUN_DATE_INPUT]:
        return f"must be text, or a run-date input {shape}"
    spec = value[RUN_DATE_INPUT]
    if not isinstance(spec, dict):
        return f"must be a run-date input {shape}"
    unknown = sorted(str(key) for key in spec if key not in _RUN_DATE_INPUT_KEYS)
    if unknown:
        return (
            f"has unknown run-date key(s) {', '.join(unknown)}; "
            f"allowed: {', '.join(sorted(_RUN_DATE_INPUT_KEYS))}"
        )
    if "part" in spec and "format" in spec:
        return "takes part or format, not both"
    if "part" in spec and not _member(spec["part"], RUN_DATE_INPUT_PARTS):
        return f"part {spec['part']!r} is not one of {', '.join(RUN_DATE_INPUT_PARTS)}"
    if "format" in spec:
        fmt = spec["format"]
        if not isinstance(fmt, str) or not fmt:
            return "format must be a strftime format"
        for directive in re.findall(r"%(.?)", fmt):
            if directive not in _RUN_DATE_INPUT_DIRECTIVES:
                return f"format {fmt!r} may hold only the directives %Y %m %d %y %j and %%"
    if "offset_days" in spec and not _is_int(spec["offset_days"]):
        return "offset_days must be a whole number of days"
    return None


def input_text(value: Any, run_date: _dt.date) -> str:
    """The text a step field value types: text as it stands, and for a
    run-date input ``run_date`` plus ``offset_days`` days, as its ``part`` in
    zero-padded digits (``RUN_DATE_INPUT_PARTS`` gives the width) or in
    ``format`` (default ``RUN_DATE_INPUT_DEFAULT_FORMAT``). Raises
    ``HarnessError`` for any other value or a date out of range."""
    if isinstance(value, str):
        return value
    problem = run_date_input_problem(value)
    if problem is not None:
        raise HarnessError(f"step field value {value!r} {problem}")
    spec = value[RUN_DATE_INPUT]
    try:
        day = run_date + _dt.timedelta(days=spec.get("offset_days", 0))
    except OverflowError as exc:
        raise HarnessError(
            f"run-date input {value!r}: {run_date} plus the offset is out of range"
        ) from exc
    if "part" in spec:
        return f"{getattr(day, spec['part']):0{RUN_DATE_INPUT_PARTS[spec['part']]}d}"
    return day.strftime(spec.get("format", RUN_DATE_INPUT_DEFAULT_FORMAT))


def _natural_key(text: str) -> tuple:
    return tuple(
        (int(part), "") if part.isdigit() else (-1, part) for part in re.split(r"(\d+)", text)
    )


def asserted_keys(s: Scenario) -> list[str]:
    """Canonical names of what ``s`` asserts, sorted: ``return_code``,
    ``messages``, ``reject_reason_codes``, ``datasets.<LOGICAL>``,
    ``file_state.<LOGICAL or observation>`` and ``screens[<step>].<FIELD>``."""
    expected = s.expected
    keys = {section for section in ("return_code", "messages", "reject_reason_codes") if section in expected}
    for section in ("datasets", "file_state"):
        value = expected.get(section)
        if isinstance(value, dict):
            keys.update(f"{section}.{name}" for name in value)
    screens = expected.get("screens")
    if isinstance(screens, list):
        for index, entry in enumerate(screens):
            if isinstance(entry, dict):
                keys.update(f"screens[{index}].{name}" for name in entry)
    return sorted(keys, key=_natural_key)


def digest(s: Scenario) -> str:
    """The ``scenario_digest`` of result records: sha256 of the canonical YAML
    of the whole file. A record is current only while this is unchanged.
    Data that refers to itself or nests too deeply raises ``HarnessError``."""
    return sha256_hex(dump_yaml(s.data))


# --------------------------------------------------------------------------
# Schema check: shared helpers
# --------------------------------------------------------------------------

# The <where> of a problem with the file as a whole, and of reference data
# (inventory, gaps, fixtures, adapter mapping files) that cannot be read.
_DOCUMENT = "(document)"
_REFERENCE_DATA = "(reference data)"


class _Problems:
    """The problems of one scenario, each ``<file>: <where>: <problem>``: the
    scenario file as ``display_path`` shows it (its stem is the id) and the
    key path, ``(document)`` when none is given."""

    def __init__(self, s: Scenario) -> None:
        self.file = display_path(s.path)
        self.items: list[str] = []

    def add(self, where: str, message: str) -> None:
        self.items.append(f"{self.file}: {where or _DOCUMENT}: {message}")


def _unknown(problems: _Problems, where: str, mapping: dict, allowed: Iterable[str]) -> None:
    allowed = frozenset(allowed)
    unknown = sorted(str(key) for key in mapping if key not in allowed)
    if unknown:
        problems.add(
            where, f"unknown key(s) {', '.join(unknown)}; allowed: {', '.join(sorted(allowed))}"
        )


def _unsafe_shape(data: dict) -> tuple[str, str] | None:
    """``(where, problem)`` for the first mapping or list in ``data`` that
    contains itself or nests deeper than ``YAML_MAX_DEPTH`` levels, ``None``
    when there is none.

    ``load_yaml`` refuses both in a file, but a scenario can also be built in
    memory. The walk uses an explicit stack, with ``active`` holding the
    containers on the current path, and visits each container once, so it
    neither recurses nor repeats shared parts. ``_walk`` and ``_texts``
    recurse, so ``_validate`` runs them only on data this accepted."""
    height: dict[int, int] = {}  # container -> containers nested in it, itself included
    active: set[int] = set()
    stack: list[tuple[Any, str, list | None]] = [(data, "", None)]
    while stack:
        value, where, children = stack.pop()
        key = id(value)
        if children is not None:
            active.discard(key)
            height[key] = 1 + max((height[id(child)] for child in children), default=0)
            if height[key] > YAML_MAX_DEPTH:
                return where or _DOCUMENT, f"nests deeper than {YAML_MAX_DEPTH} levels"
            continue
        if key in height:
            continue
        active.add(key)
        if len(active) > YAML_MAX_DEPTH:
            return where or _DOCUMENT, f"nests deeper than {YAML_MAX_DEPTH} levels"
        if isinstance(value, dict):
            items = [(f"{where}.{name}" if where else str(name), item) for name, item in value.items()]
        else:
            items = [(f"{where}[{index}]", item) for index, item in enumerate(value)]
        children = [item for _, item in items if isinstance(item, (dict, list))]
        stack.append((value, where, children))
        for child_where, item in reversed(items):
            if not isinstance(item, (dict, list)) or id(item) in height:
                continue
            if id(item) in active:
                return child_where, "refers to a mapping or list that contains it (a recursive alias)"
            stack.append((item, child_where, None))
    return None


def _walk(value: Any, where: str) -> Iterator[tuple[str, Any]]:
    """``(path, value)`` for ``value`` and everything under it. Recursive:
    ``_validate`` runs it only on data ``_unsafe_shape`` accepted."""
    yield where, value
    if isinstance(value, dict):
        for key, item in value.items():
            yield from _walk(item, f"{where}.{key}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            yield from _walk(item, f"{where}[{index}]")


def _texts(value: Any, where: str, skip: Callable[[str], bool]) -> Iterator[tuple[str, str]]:
    """Every string under ``value``, mapping keys included, except below the
    paths ``skip`` accepts. Recursive: ``_validate`` runs it only on data
    ``_unsafe_shape`` accepted."""
    if isinstance(value, str):
        yield where, value
    elif isinstance(value, dict):
        for key, item in value.items():
            child = f"{where}.{key}"
            if isinstance(key, str):
                yield child, key
            if not skip(child):
                yield from _texts(item, child, skip)
    elif isinstance(value, list):
        for index, item in enumerate(value):
            yield from _texts(item, f"{where}[{index}]", skip)


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _member(value: Any, names: Iterable[str]) -> bool:
    """Whether ``value`` is one of ``names``; a value that is not text never is."""
    return isinstance(value, str) and value in names


def _is_matcher(value: Any) -> bool:
    return isinstance(value, dict) and "match" in value


def _reason_code_problem(value: Any) -> str | None:
    """Why ``value`` is not a CBTRN02C reason code, or ``None`` when it is one."""
    if (_is_int(value) and value in _REJECT_REASON_CODES) or _member(value, _REJECT_REASON_TEXTS):
        return None
    octal = f"0{value:o}" if _is_int(value) and value >= 0 else ""
    if octal in _REJECT_REASON_TEXTS:
        return f"{value} is how YAML 1.1 reads an unquoted {octal}, as an octal number; quote it: '{octal}'"
    texts = ", ".join(f"'{text}'" for text in sorted(_REJECT_REASON_TEXTS))
    return f"{value!r} is not a CBTRN02C reason code; give one of {texts} quoted, or its number such as 102"


def _clip(text: str, limit: int = 60) -> str:
    return text if len(text) <= limit else text[: limit - 3] + "..."


_PLATFORM_PATTERNS = (
    ("a data set name", re.compile(r"\b(?:AWS|OEM)\.[A-Z0-9#@$]{1,8}\.")),
    ("a ddname mapping", re.compile(r"\bDD_[A-Za-z0-9#@$]")),
    # A single-label host 'label:port' counts when its port of one to five digits does not start with 0 and does not
    # run on into a word character, '%', ':' or '-', into '.' or ',' before a word character, or into '/' before a
    # digit. So 'tpehost:2001' is an address at the end, before a space, '!', '?', a closing '.', ', ', ')', ';',
    # a quote or '/x', while 'width:70%', 'font:12px', 'TstDate:2022-07-18', 'Time:10:00:00', 'EXP:1234.56-',
    # 'Total:12,345.67', 'Date:12/31/22' and 'Reas:0000' are not.
    (
        "a host:port address",
        re.compile(
            r"(?<![\w.-])(?:(?:localhost|\d{1,3}(?:\.\d{1,3}){3}|\[[0-9A-Fa-f:]+\]"
            r"|[A-Za-z][A-Za-z0-9-]*(?:\.[A-Za-z][A-Za-z0-9-]*)+):\d{1,5}(?!\d)"
            r"|[A-Za-z][A-Za-z0-9-]*:[1-9]\d{0,4}(?![\w%:-]|[.,]\w|/\d))"
        ),
    ),
    # A path starts the text or follows a space, quote, '=', '(', '[', ',', ';' or ':', and is '~/', a drive such
    # as 'C:\' or a '/' followed by anything but '/', '*' or a space: '/tmp', '/@data' and '/opt/x' are paths, and
    # the JCL '//' and '/*', '</p>', 'Y/N' and '%m/%d/%y' are not. Nor is a lone '/' followed by a closing mark
    # ('"`>)]) and then only closing marks, ',', ';' or '.' up to a space or the end, as in x '/' y or '<br />'.
    (
        "an absolute path",
        re.compile(r"(?:^|(?<=[\s'\"=(\[,;:]))(?:/(?=[^/*\s])(?![>)\]'\"`][>)\]'\"`,;.]*(?:\s|$))|~/|[A-Za-z]:\\)"),
    ),
)


def _tests_root_of(s: Scenario) -> Path:
    """The tests root a scenario file lies under (``<root>/scenarios/<level>/``),
    so a tree checked from another root reads its own baselines."""
    parents = s.path.parents
    if len(parents) >= 3 and parents[1].name == "scenarios":
        return parents[2]
    return TESTS_ROOT


def _string_list(problems: _Problems, data: dict, key: str) -> list[str]:
    if key not in data:
        return []
    value = data[key]
    if not isinstance(value, list):
        problems.add(key, "must be a list")
        return []
    items: list[str] = []
    for index, item in enumerate(value):
        if not isinstance(item, str):
            problems.add(f"{key}[{index}]", f"{item!r} must be text")
        elif item in items:
            problems.add(f"{key}[{index}]", f"{item} is listed twice")
        else:
            items.append(item)
    return items


# --------------------------------------------------------------------------
# Schema check: identity, inventory, tags and gaps
# --------------------------------------------------------------------------


def _check_identity(s: Scenario, problems: _Problems) -> None:
    match = ID_RE.fullmatch(s.id)
    if match is None:
        problems.add("id", f"{s.id!r} does not follow <AREA>-<PROGRAM-OR-JOB>-<type>-<nn>")
    else:
        if match["area"] not in AREAS:
            problems.add("id", f"area {match['area']} is not one of {', '.join(AREAS)}")
        if (len(match["nn"]) == 3) != s.id.startswith(_THREE_DIGIT_PREFIX):
            problems.add("id", "nn has three digits only in ACT-COACTUPC-negative-nnn, two elsewhere")
    if s.level not in LEVELS:
        problems.add("level", f"folder {s.level!r} is not one of {', '.join(LEVELS)}")
    manifest_level = _LEVEL_OF.get(s.id)
    if manifest_level is None:
        problems.add("id", "is not in the closed scenario id manifest")
    elif manifest_level != s.level:
        problems.add("level", f"the manifest places {s.id} under {manifest_level}, not {s.level}")
    data = s.data
    for key, actual, origin in (("id", s.id, "the file stem"), ("level", s.level, "the folder")):
        if key in data and data[key] != actual:
            problems.add(key, f"{data[key]!r} differs from {origin} {actual!r}")
    if "type" in data:
        if s.type and data["type"] != s.type:
            problems.add("type", f"{data['type']!r} differs from the id's type {s.type!r}")
        elif data["type"] not in SCENARIO_TYPES:
            problems.add("type", f"{data['type']!r} is not one of {', '.join(SCENARIO_TYPES)}")
    if "description" in data and not isinstance(data["description"], str):
        problems.add("description", "must be text")


def _check_inventory_ids(s: Scenario, problems: _Problems) -> None:
    if "inventory_ids" not in s.data:
        return
    value = s.data["inventory_ids"]
    if not isinstance(value, list) or not value:
        problems.add("inventory_ids", "must be a non-empty list of inventory ids, primary item first")
        return
    inventory = _inventory()
    for item_id in _string_list(problems, s.data, "inventory_ids"):
        if item_id not in inventory:
            problems.add("inventory_ids", f"{item_id} is not an id of tests/inventory/inventory.yaml")
    primary = value[0]
    match = re.fullmatch(r"INV-([A-Z]{3})-\d{3}", primary) if isinstance(primary, str) else None
    if match is not None and s.area in AREAS and match[1] != s.area:
        problems.add(
            "inventory_ids[0]",
            f"the primary item {primary} belongs to area {match[1]}, but the scenario's area is {s.area}",
        )


def _check_tags_and_gaps(s: Scenario, problems: _Problems) -> None:
    tags = _string_list(problems, s.data, "tags")
    for tag in tags:
        if tag not in TAGS:
            problems.add("tags", f"{tag!r} is not one of {', '.join(TAGS)}")
    gap_ids = _string_list(problems, s.data, "gap_ids")
    gaps = _gaps()
    for gap_id in gap_ids:
        if gap_id not in gaps:
            problems.add("gap_ids", f"{gap_id} is not an entry of tests/inventory/gaps.yaml")
    if "observed-only" in tags and not any(
        gaps.get(gap_id, {}).get("category") == "uncertain-purpose"
        and gaps.get(gap_id, {}).get("status") == "open"
        for gap_id in gap_ids
    ):
        problems.add("tags", "observed-only needs an open uncertain-purpose gap among gap_ids")
    if "suspected-defect" in tags and not any(gap_id.startswith("GAP-DEF-") for gap_id in gap_ids):
        problems.add("tags", "suspected-defect needs a GAP-DEF entry among gap_ids")
    # Every inferred expectation has an inferred-not-observed register entry
    # naming what would settle it, so the tag cites one.
    if "inferred-not-observed" in tags and not any(gap_id.startswith("GAP-INF-") for gap_id in gap_ids):
        problems.add("tags", "inferred-not-observed needs a GAP-INF entry among gap_ids")
    if s.id not in ALL_IDS:
        # _check_identity reports the id; no baseline path is built from it.
        return
    baselines = _tests_root_of(s) / "baselines"
    manifest_path = baselines / s.id / "manifest.yaml"
    named = f"tests/baselines/{s.id}/manifest.yaml"
    chain = (
        (baselines, "tests/baselines"),
        (manifest_path.parent, f"tests/baselines/{s.id}"),
        (manifest_path, named),
    )
    for path, label in chain:
        if path.is_symlink():
            problems.add("baseline", f"{named} is not read: {label} is a symbolic link")
            return
    if manifest_path.exists() and not _regular_file(manifest_path):
        problems.add("baseline", f"{named} is not read: it is not a regular file")
        return
    try:
        manifest = load_yaml(manifest_path, missing_ok=True)
    except YAMLLoadError as exc:
        problems.add("baseline", str(exc))  # the message names the manifest
        return
    if (
        isinstance(manifest, dict)
        and manifest.get("capture_platform") == "derived"
        and "inferred-not-observed" not in tags
    ):
        problems.add("tags", "the baseline is derived, so the scenario carries inferred-not-observed")


# --------------------------------------------------------------------------
# Schema check: preconditions
# --------------------------------------------------------------------------


def _unknown_layout(layout: str) -> str:
    return f"layout {layout} is not a record of tests/inventory/layouts.yaml, so its fields cannot be checked"


def _check_raw(problems: _Problems, where: str, value: Any, length: Any) -> None:
    """An ``@raw`` record image: text of at most ``length`` characters (when
    known) that the record encoding can hold."""
    codec = _codec.ENCODINGS[_RECORD_ENCODING]
    if not isinstance(value, str):
        problems.add(where, "must be the record's text")
    elif _is_int(length) and len(value) > length:
        problems.add(where, f"holds {len(value)} characters; a record has {length}")
    else:
        try:
            value.encode(codec)
        except UnicodeEncodeError:
            problems.add(where, f"holds a character {codec} cannot encode")


def _check_value(problems: _Problems, where: str, value: Any, field: _RecordField, expected: bool) -> None:
    """A record value: one ``harness.codec`` encodes for the field, or, in an
    expected record, a matcher or ``{raw: <hex>}`` for invalid numeric bytes. A
    precondition value of a dataset field with its own layout may be a field
    map of that layout."""
    if isinstance(value, float) or _is_matcher(value):
        # _validate reports every float, and _check_matchers every matcher.
        return
    shown = _clip(repr(value))
    if field.layout and isinstance(value, dict) and not expected:
        fields = _layout_fields(field.layout)
        if fields is None:
            problems.add(where, _unknown_layout(field.layout))
            return
        named: dict[tuple[str, tuple[int, ...]], str] = {}
        encodable: dict[str, Any] = {}
        for key, item in value.items():
            if key == RAW_KEY:
                _check_raw(problems, f"{where}.{RAW_KEY}", item, field.descriptor.get("length"))
            elif not isinstance(key, str):
                problems.add(where, f"field key {key!r} must be text")
            elif _check_field(
                problems, f"{where}.{key}", key, item, fields, {},
                f"is not a field of layout {field.layout}", expected, named,
            ):
                encodable[key] = item
        # harness.fixtures encodes this map as a record of the field's own layout.
        _check_views(problems, where, encodable, field.layout)
        return
    kind = field.descriptor.get("type")
    if expected and isinstance(value, dict) and set(value) == {"raw"}:
        if kind in _codec.TEXT_TYPES:
            problems.add(where, f"value {shown}: a text field never decodes to {{raw: <hex>}}; give its text")
        elif kind == "binary":
            problems.add(where, f"value {shown}: a binary field decodes every byte pattern to a number")
        else:
            try:
                _codec.encode(_codec.Invalid(value["raw"]), field.descriptor, _RECORD_ENCODING)
            except _codec.CodecError as exc:
                problems.add(where, f"value {shown} is not the field's invalid bytes: {exc.reason}")
        return
    if isinstance(value, (dict, list)):
        container = "a mapping" if isinstance(value, dict) else "a list"
        problems.add(
            where,
            f"value {shown} is {container}; give the field's text or number"
            + (", a matcher, or {raw: <hex>} for invalid numeric bytes" if expected else ""),
        )
        return
    try:
        _codec.encode(value, field.descriptor, _RECORD_ENCODING)
    except _codec.CodecError as exc:
        verdict = "is never a decoded value of the field" if expected else "cannot be encoded"
        problems.add(where, f"value {shown} {verdict}: {exc.reason}")


def _check_field(
    problems: _Problems,
    where: str,
    key: str,
    value: Any,
    layout_fields: dict[str, _RecordField],
    own_fields: dict[str, _RecordField],
    unknown: str,
    expected: bool,
    named: dict[tuple[str, tuple[int, ...]], str],
) -> bool:
    """One ``key: value`` of a record field map. The key names a field, with
    one subscript per OCCURS level, each within its bounds, and no layout field
    an earlier key of the record names (``named``: field path and subscripts ->
    that key), as ``harness.codec.encode_record`` requires; then the value.
    True when the key names a layout field and its value is a plain value that
    passes, so the record can compare it with other views of the same bytes."""
    match = _KEY_RE.fullmatch(key)
    base = match["base"] if match else key
    field = layout_fields.get(base)
    own = own_fields.get(base)
    # harness.fixtures resolves a key the same way: to the dataset's own field
    # when the layout lacks it, or when that field has a layout and the value is a mapping.
    if own is not None and (field is None or (own.layout and isinstance(value, dict))):
        field = own
    if match is None or field is None:
        problems.add(where, unknown)
        return False
    texts = match["indices"].split(",") if match["indices"] else []
    shape = f"{base}({','.join(f'1..{count}' for count in field.dimensions)})"
    if texts and not field.dimensions:
        problems.add(where, "is not a table item, so it takes no subscript")
        return False
    if len(texts) != len(field.dimensions):
        problems.add(where, f"is a table item: name one occurrence with a subscript per OCCURS level, {shape}")
        return False
    indices: list[int] = []
    for text, count in zip(texts, field.dimensions):
        # A subscript with more digits than its count is out of range, so only
        # short texts are converted: int() refuses a text of more than 4300 digits.
        digits = text.lstrip("0") or "0"
        if len(digits) > len(str(count)) or not 1 <= int(digits) <= count:
            problems.add(where, f"subscript {_clip(digits)} lies outside 1..{count} of {shape}")
            return False
        indices.append(int(digits))
    canonical = f"{base}({','.join(str(index) for index in indices)})" if indices else base
    if key != canonical:
        problems.add(where, f"write the key as {canonical}")
        return False
    layout_field = field is not own
    if layout_field:
        # A field's name and its path name one field occurrence: harness.codec
        # refuses the later key before it encodes any value.
        first = named.setdefault((field.descriptor["path"], tuple(indices)), key)
        if first != key:
            problems.add(where, f"names the same field as {first}")
            return False
    reported = len(problems.items)
    _check_value(problems, where, value, field, expected)
    # Floats, matchers and {raw: <hex>} are not values the codec encodes as given.
    return layout_field and len(problems.items) == reported and not isinstance(value, (float, dict))


def _check_views(problems: _Problems, where: str, values: dict[str, Any], layout: str) -> None:
    """Precondition values of fields that share bytes without one containing
    the other (two REDEFINES views) agree on those bytes. The rule is
    ``harness.codec.encode_record``'s: it refuses the later field of a pair
    that disagrees, which is reported at its key and left out so the other
    pairs are still compared. ``values`` holds only layout keys whose key and
    value passed their own checks, so no problem is reported twice."""
    remaining = dict(values)
    while remaining:
        try:
            _codec.encode_record(remaining, layout, _RECORD_ENCODING)
        except _codec.CodecError as exc:
            if not (isinstance(exc.field, str) and exc.field in remaining):
                problems.add(where, f"cannot be encoded as a record of layout {layout}: {exc}")
                return
            problems.add(f"{where}.{exc.field}", exc.reason)
            del remaining[exc.field]
        else:
            return


def _check_record(
    problems: _Problems,
    where: str,
    record: Any,
    spec: dict | None,
    allow_raw: bool,
    allow_text: bool,
    expected: bool = False,
) -> None:
    """A record given as a field map of the dataset ``spec`` describes (or as a
    text line where the dataset is line data). ``expected`` marks a record of
    ``expected``, whose values may also be matchers or ``{raw: <hex>}``. No
    two keys name one field; a precondition record's values of two views of
    the same bytes must also agree, as the fixture build requires. Expected
    values are compared after decoding, so their views are not compared."""
    if isinstance(record, str) and allow_text:
        return
    if not isinstance(record, dict):
        shape = "a field map or a text line" if allow_text else "a field map of the dataset's layout"
        problems.add(where, f"must be {shape}")
        return
    fields = _record_fields(spec)
    layout_fields, own_fields = fields if fields is not None else ({}, {})
    if layout_fields is None:
        problems.add(where, _unknown_layout(spec["layout"]))
        layout_fields = {}
    named: dict[tuple[str, tuple[int, ...]], str] = {}
    encodable: dict[str, Any] = {}
    for key, value in record.items():
        if allow_raw and key == RAW_KEY:
            _check_raw(problems, f"{where}.{RAW_KEY}", value, spec.get("record_length") if spec else None)
            continue
        if not isinstance(key, str):
            problems.add(where, f"field key {key!r} must be text")
        elif (layout_fields or own_fields) and _check_field(
            problems, f"{where}.{key}", key, value, layout_fields, own_fields,
            f"is not a field of layout {spec.get('layout')} or of the dataset", expected, named,
        ):
            encodable[key] = value
    if encodable and not expected:
        # Only layout keys are collected, so spec names a layout here; the
        # dataset's own fields overlay the record after it is encoded.
        _check_views(problems, where, encodable, spec["layout"])


def _check_precondition_dataset(problems: _Problems, name: Any, entry: Any) -> None:
    where = f"preconditions.datasets.{name}"
    datasets = _datasets()
    spec = datasets.get(name) if isinstance(name, str) else None
    if spec is None:
        problems.add(where, f"{name!r} is not a logical dataset of tests/fixtures/datasets.yaml")
    if not isinstance(entry, dict):
        problems.add(where, "must be a mapping {base?, records?, omit_keys?} ({} for an empty dataset)")
        return
    _unknown(problems, where, entry, _DATASET_ENTRY_KEYS)
    if "base" in entry:
        base = entry["base"]
        base_spec = datasets.get(base) if isinstance(base, str) else None
        if base_spec is None:
            problems.add(f"{where}.base", f"{base!r} is not a logical dataset of tests/fixtures/datasets.yaml")
        elif not _repository_backed(base_spec):
            problems.add(
                f"{where}.base",
                f"{base} holds no repository data (source {base_spec.get('source')!r}); "
                "build it from records instead",
            )
    records = entry.get("records", [])
    if not isinstance(records, list):
        problems.add(f"{where}.records", "must be a list of field maps")
    else:
        for index, record in enumerate(records):
            _check_record(problems, f"{where}.records[{index}]", record, spec, True, False)
    if "omit_keys" in entry:
        omit = entry["omit_keys"]
        if not isinstance(omit, list) or not all(isinstance(key, str) for key in omit):
            problems.add(f"{where}.omit_keys", "must be a list of primary-key texts")
        if "base" not in entry:
            problems.add(f"{where}.omit_keys", "drops records of the base, so the entry needs base")


def _check_parameters(problems: _Problems, parameters: dict) -> None:
    where = "preconditions.parameters"
    _unknown(problems, where, parameters, PARAMETER_KEYS)
    if "clock" in parameters:
        try:
            cob_current_date(parameters["clock"])
        except HarnessError as exc:
            problems.add(f"{where}.clock", str(exc))
    if "clock_mode" in parameters and parameters["clock_mode"] not in CLOCK_MODES:
        problems.add(f"{where}.clock_mode", f"{parameters['clock_mode']!r} is not fixed or relative")
    if "parm" in parameters:
        parm = parameters["parm"]
        if not isinstance(parm, str) or len(parm) > 100:
            problems.add(f"{where}.parm", "must be the EXEC PARM text, quoted, of at most 100 characters")
    if "DATEPARM" in parameters:
        length = _datasets().get("DATEPARM", {}).get("record_length")
        limit = length if _is_int(length) else 80
        dateparm = parameters["DATEPARM"]
        if not isinstance(dateparm, str) or len(dateparm) > limit:
            problems.add(f"{where}.DATEPARM", f"must be the record text, of at most {limit} characters")
    if "sessions" in parameters:
        sessions = parameters["sessions"]
        if not (_is_int(sessions) and sessions in (1, 2)):
            problems.add(f"{where}.sessions", "must be 1 or 2")
    if "ceedays_feedback" in parameters:
        token = parameters["ceedays_feedback"]
        if not isinstance(token, str) or re.fullmatch(r"[0-9A-Fa-f]{4}", token) is None:
            problems.add(f"{where}.ceedays_feedback", "must be the feedback token as 4 hex digits, quoted")


def _check_fault(problems: _Problems, index: int, fault: Any) -> None:
    where = f"preconditions.faults[{index}]"
    if not isinstance(fault, dict):
        problems.add(where, "must be a mapping {target, operation, condition, after?}")
        return
    _unknown(problems, where, fault, _FAULT_KEYS)
    for key in ("target", "operation", "condition"):
        if key not in fault:
            problems.add(where, f"{key} is required")
    if "target" in fault:
        target = fault["target"]
        resources = _resources()
        # A region file, or the JOBS queue: 'Unable to Write TDQ (JOBS)...' is
        # reached by making the queue unavailable.
        known = isinstance(target, str) and (
            target in _datasets() or target in resources.region_files or target in resources.tdq
        )
        if not known:
            problems.add(
                f"{where}.target",
                f"{target!r} is not a logical dataset, a region file or the TDQ of the adapters' mapping",
            )
    if "operation" in fault and fault["operation"] not in FAULT_OPERATIONS:
        problems.add(f"{where}.operation", f"{fault['operation']!r} is not one of {', '.join(FAULT_OPERATIONS)}")
    if "condition" in fault and fault["condition"] not in FAULT_CONDITIONS:
        problems.add(f"{where}.condition", f"{fault['condition']!r} is not one of {', '.join(FAULT_CONDITIONS)}")
    if "after" in fault and not (_is_int(fault["after"]) and fault["after"] >= 0):
        problems.add(f"{where}.after", "must be a number of operations, 0 or more")


def _check_preconditions(s: Scenario, problems: _Problems) -> int:
    """Check ``preconditions``; returns the declared number of sessions."""
    if "preconditions" not in s.data:
        return 1
    preconditions = s.data["preconditions"]
    if not isinstance(preconditions, dict):
        problems.add("preconditions", "must be a mapping of datasets, parameters and faults")
        return 1
    _unknown(problems, "preconditions", preconditions, _PRECONDITION_KEYS)
    datasets = preconditions.get("datasets", {})
    if not isinstance(datasets, dict):
        problems.add("preconditions.datasets", "must be a mapping of logical dataset -> entry ({} for none)")
    else:
        for name, entry in datasets.items():
            _check_precondition_dataset(problems, name, entry)
    parameters = preconditions.get("parameters", {})
    if not isinstance(parameters, dict):
        problems.add("preconditions.parameters", "must be a mapping ({} for none)")
        parameters = {}
    else:
        _check_parameters(problems, parameters)
    faults = preconditions.get("faults", [])
    if not isinstance(faults, list):
        problems.add("preconditions.faults", "must be a list ([] for none)")
    else:
        for index, fault in enumerate(faults):
            _check_fault(problems, index, fault)
    sessions = parameters.get("sessions", 1)
    return sessions if sessions in (1, 2) and _is_int(sessions) else 1


# --------------------------------------------------------------------------
# Schema check: inputs
# --------------------------------------------------------------------------


def _session(step: Any) -> Any:
    return step.get("session", 1) if isinstance(step, dict) else 1


def _previous_screen_map(steps: list, screens: list, index: int) -> str | None:
    """The ``@map`` the previous step of the same session expects to end on."""
    session = _session(steps[index])
    for earlier in range(index - 1, -1, -1):
        if _session(steps[earlier]) == session:
            screen = screens[earlier] if earlier < len(screens) else None
            mapset = screen.get(SCREEN_MAP) if isinstance(screen, dict) else None
            return mapset if isinstance(mapset, str) else None
    return None


def _input_map(steps: list, screens: list, index: int) -> str | None:
    """The map a step's fields are entered on: for a step entering
    ``SIGN_ON_TRANSID``, the sign-on map COSGN00C sends, whatever ``@map`` it
    declares; else its own ``@map``; else, unless it starts a transaction, the
    map its session's previous step ends on."""
    step = steps[index]
    if step.get("transid") == SIGN_ON_TRANSID:
        return SIGN_ON_MAP
    declared = step.get(SCREEN_MAP)
    if isinstance(declared, str):
        return declared
    if "transid" in step:
        return None
    return _previous_screen_map(steps, screens, index)


def _check_sign_on(s: Scenario, problems: _Problems, where: str, fields: dict) -> None:
    # Only a sign-on entry is a credential. COUSR01 and COUSR02 also have USERID
    # and PASSWD fields, holding the user record being maintained (TESTU001), so
    # the demo-user rule applies to steps entering CC00 (always on COSGN00,
    # whatever @map they declare), to steps entered on COSGN00, and to steps
    # whose input map is unknown. A blank user id signs nobody on. A value that
    # is not text is reported by _check_steps, except a run-date input,
    # which is well formed there but is no demo user.
    if "USERID" in fields:
        user = fields["USERID"]
        allowed = (
            (not isinstance(user, str) and not is_run_date_input(user))
            or (isinstance(user, str) and not user.strip()) or user in SIGN_ON_USERS
            or (user == INVENTED_USER and s.id == INVENTED_USER_SCENARIO)
        )
        if not allowed:
            problems.add(
                f"{where}.fields.USERID",
                f"{user!r} is not a demo user: sign on as ADMIN001 or USER0001 "
                f"(the invented {INVENTED_USER} only in {INVENTED_USER_SCENARIO})",
            )
    if "PASSWD" in fields and not (s.area == "SEC" and s.type == "negative"):
        if fields["PASSWD"] != SIGN_ON_PASSWORD:
            problems.add(
                f"{where}.fields.PASSWD",
                f"sign-on uses the documented password {SIGN_ON_PASSWORD} outside SEC negative scenarios",
            )


def _check_steps(s: Scenario, problems: _Problems, steps: Any, sessions: int, screens: list) -> None:
    if not isinstance(steps, list):
        problems.add("inputs.steps", "must be a list of steps ([] for a region-state observation)")
        return
    screen_fields = _screen_fields()
    any_field = frozenset().union(*screen_fields.values())
    transactions = _transactions()
    for index, step in enumerate(steps):
        where = f"inputs.steps[{index}]"
        if not isinstance(step, dict):
            problems.add(where, "must be a mapping {transid?, aid, fields?, session?, '@map'?}")
            continue
        _unknown(problems, where, step, _STEP_KEYS)
        if "aid" not in step:
            problems.add(where, "aid is required")
        elif not _member(step["aid"], AIDS):
            problems.add(f"{where}.aid", f"{step['aid']!r} is not ENTER, CLEAR, PA1-PA3 or PF1-PF24")
        if "transid" in step and not _member(step["transid"], transactions):
            problems.add(f"{where}.transid", f"{step['transid']!r} is not a TRANSACTION of app/csd/CARDDEMO.CSD")
        if "session" in step and not (_is_int(step["session"]) and 1 <= step["session"] <= sessions):
            problems.add(
                f"{where}.session", f"must be a session from 1 to {sessions} (preconditions.parameters.sessions)"
            )
        declared = step.get(SCREEN_MAP)
        if SCREEN_MAP in step and not (_member(declared, screen_fields) or declared == TEXT_SCREEN):
            problems.add(f"{where}.@map", f"{declared!r} is not a mapset of tests/inventory/layouts.yaml or TEXT")
        elif SCREEN_MAP in step and step.get("transid") == SIGN_ON_TRANSID and declared != SIGN_ON_MAP:
            problems.add(
                f"{where}.@map", f"{declared!r}: {SIGN_ON_TRANSID} starts COSGN00C, which sends {SIGN_ON_MAP}"
            )
        previous = _previous_screen_map(steps, screens, index)
        if isinstance(declared, str) and "transid" not in step and previous is not None and previous != declared:
            problems.add(
                f"{where}.@map", f"expects {declared}, but the previous step of its session ends on {previous}"
            )
        fields = step.get("fields", {})
        if not isinstance(fields, dict):
            problems.add(f"{where}.fields", "must be a mapping of BMS field -> text")
            continue
        input_map = _input_map(steps, screens, index)
        if input_map is not None and input_map not in screen_fields and input_map != TEXT_SCREEN:
            input_map = None  # an unknown map, reported above: check the fields as on any map
        if input_map == TEXT_SCREEN and fields:
            problems.add(f"{where}.fields", "cannot be entered on an unformatted (TEXT) screen")
        known = screen_fields.get(input_map) if input_map is not None else None
        for name, value in fields.items():
            if known is not None and name not in known:
                problems.add(f"{where}.fields.{name}", f"is not a field of map {input_map}")
            elif known is None and input_map != TEXT_SCREEN and name not in any_field:
                problems.add(f"{where}.fields.{name}", "is not a BMS field of any map")
            if is_run_date_input(value):
                problem = run_date_input_problem(value)
                if problem is not None:
                    problems.add(f"{where}.fields.{name}", problem)
                elif clock_mode(s) != "relative":
                    problems.add(
                        f"{where}.fields.{name}",
                        "a run-date input stands for a clock the adapter cannot fix, so it needs "
                        "clock_mode: relative; under the fixed clock write the exact value",
                    )
            elif not isinstance(value, str):
                problems.add(
                    f"{where}.fields.{name}",
                    f"{value!r} must be text (quote it) or a run-date input {{{RUN_DATE_INPUT}: ...}}",
                )
        if input_map in (None, SIGN_ON_MAP):
            _check_sign_on(s, problems, where, fields)


def _check_param_files(problems: _Problems, value: Any, online: bool) -> None:
    where = "inputs.param_files"
    if online:
        problems.add(where, "is a batch input; steps take no parameter files")
    if not isinstance(value, dict):
        problems.add(where, "must be a mapping of logical dataset -> list of record lines")
        return
    datasets = _datasets()
    for name, lines in value.items():
        spec = datasets.get(name) if isinstance(name, str) else None
        if spec is None:
            problems.add(f"{where}.{name}", "is not a logical dataset of tests/fixtures/datasets.yaml")
        if not isinstance(lines, list) or not all(isinstance(line, str) for line in lines):
            problems.add(f"{where}.{name}", "must be a list of record lines (text)")
            continue
        length = spec.get("record_length") if spec else None
        for index, line in enumerate(lines):
            if _is_int(length) and len(line) > length:
                problems.add(f"{where}.{name}[{index}]", f"is longer than the {length}-byte record")


def _check_inputs(s: Scenario, problems: _Problems, sessions: int, screens: list) -> None:
    if "inputs" not in s.data:
        return
    inputs = s.data["inputs"]
    if not isinstance(inputs, dict):
        problems.add("inputs", "must be a mapping holding program, job or steps")
        return
    _unknown(problems, "inputs", inputs, _INPUT_KEYS)
    forms = [key for key in ("program", "job", "steps") if key in inputs]
    if len(forms) != 1:
        problems.add("inputs", f"must hold exactly one of program, job and steps, not {', '.join(forms) or 'none'}")
    level_form = _LEVEL_INPUT.get(s.level)
    if len(forms) == 1 and level_form is not None and forms[0] != level_form:
        problems.add("inputs", f"{s.level} scenarios run inputs.{level_form}, not inputs.{forms[0]}")
    if "program" in inputs and not _member(inputs["program"], _programs_in_app()):
        problems.add("inputs.program", f"{inputs['program']!r} is not a program of app/cbl (its member name)")
    if "job" in inputs:
        job = inputs["job"]
        names = [job] if isinstance(job, str) else job if isinstance(job, list) else []
        if not names:
            problems.add("inputs.job", "must be a job name or a non-empty ordered list of job names")
        known = _jobs_in_app() | GENERATED_JOBS
        for index, name in enumerate(names):
            if not isinstance(name, str) or name not in known:
                where = "inputs.job" if isinstance(job, str) else f"inputs.job[{index}]"
                problems.add(where, f"{name!r} is not an app/jcl member or {', '.join(sorted(GENERATED_JOBS))}")
    if "param_files" in inputs:
        _check_param_files(problems, inputs["param_files"], "steps" in inputs)
    if "steps" in inputs:
        _check_steps(s, problems, inputs["steps"], sessions, screens)


# --------------------------------------------------------------------------
# Schema check: expected outcomes, matchers, platform neutrality
# --------------------------------------------------------------------------


def _dataset_asserts(value: Any) -> bool:
    """Whether an expected dataset value asserts the dataset's content, judged
    on its well-formed parts: ``baseline``, a ``records`` list (``[]`` asserts
    an empty dataset), a ``count``, or ``contains`` holding at least one record
    (a non-empty field map or a text line). ``matchers`` only qualify fields of
    the records given, so alone they assert nothing."""
    if value == BASELINE:
        return True
    if not isinstance(value, dict):
        return False
    count, contains = value.get("count"), value.get("contains")
    return (
        isinstance(value.get("records"), list)
        or (_is_int(count) and count >= 0)
        or (isinstance(contains, list) and any(
            isinstance(record, str) or (isinstance(record, dict) and bool(record)) for record in contains
        ))
    )


def _stage_logical(problems: _Problems, where: str, name: str, jobs: tuple[str, ...]) -> str | None:
    """The ``<LOGICAL>`` of a stage observation ``@AFTER:<JOB>:<LOGICAL>``
    that ``expected.file_state`` names, or ``None`` with its problems: the
    name is malformed, ``inputs.job`` (``jobs``) does not run ``<JOB>`` or
    runs it more than once, so its end is not one point of the run, or
    ``<LOGICAL>`` is not a logical dataset of tests/fixtures/datasets.yaml.
    Well-formedness is ``adapters.base.parse_after_job``'s, the rule the
    adapters read the name by."""
    from adapters.base import parse_after_job  # adapters.base imports this module

    parsed = parse_after_job(name)
    if parsed is None:
        problems.add(
            where,
            f"is not a stage observation {OBS_AFTER_PREFIX}<JOB>:<LOGICAL>: <JOB> and <LOGICAL> are "
            "non-empty names without ':' or whitespace, and <LOGICAL> does not start with '@' "
            f"(the region files at the end of a job are {OBS_AFTER_PREFIX}<JOB>:{OBS_REGION_FILES})",
        )
        return None
    job, logical = parsed
    once = _stage_job(problems, where, job, jobs, "end")
    known = _member(logical, _datasets())
    if not known:
        problems.add(where, f"names {logical!r}, which is not a logical dataset of tests/fixtures/datasets.yaml")
    return logical if once and known else None


def _stage_job(problems: _Problems, where: str, job: str, jobs: tuple[str, ...], point: str) -> bool:
    """Whether ``inputs.job`` (``jobs``) runs ``job`` exactly once, so that
    its ``point`` (``start`` or ``end``) is one point of the run, as a stage
    observation of ``job`` needs; adds the problem when it does not."""
    runs = jobs.count(job)
    if runs == 0:
        ran = f"inputs.job runs {', '.join(jobs)}" if jobs else "the scenario runs no job"
        problems.add(where, f"names job {job}, which the scenario does not run ({ran})")
    elif runs > 1:
        problems.add(
            where,
            f"names job {job}, which inputs.job lists {runs} times, so the {point} of {job} is not one point "
            "of the run; a stage observation needs a job the chain runs exactly once",
        )
    return runs == 1


def _region_stage_job(problems: _Problems, where: str, name: str, jobs: tuple[str, ...]) -> bool:
    """Whether a region stage observation ``@BEFORE:<JOB>:@REGION-FILES`` or
    ``@AFTER:<JOB>:@REGION-FILES`` that ``expected.file_state`` names is well
    formed and names a ``<JOB>`` that ``inputs.job`` (``jobs``) runs exactly
    once, so the start or end of ``<JOB>`` is one point of the run; adds the
    problems otherwise. Well-formedness is
    ``adapters.base.parse_region_stage``'s, the rule the adapters read the
    name by."""
    from adapters.base import parse_region_stage  # adapters.base imports this module

    parsed = parse_region_stage(name)
    if parsed is None:
        problems.add(
            where,
            f"is not a region stage observation {OBS_BEFORE_PREFIX}<JOB>:{OBS_REGION_FILES} or "
            f"{OBS_AFTER_PREFIX}<JOB>:{OBS_REGION_FILES}: <JOB> is a non-empty name without ':' or whitespace",
        )
        return False
    stage, job = parsed
    return _stage_job(problems, where, job, jobs, "start" if stage == "before" else "end")


def _check_expected_dataset(
    problems: _Problems, where: str, name: Any, value: Any, reserved_ok: bool, jobs: tuple[str, ...] = ()
) -> None:
    """One ``expected.datasets`` or ``expected.file_state`` entry.
    ``reserved_ok`` admits the reserved observations (``file_state`` only);
    ``jobs`` is the scenario's ``inputs.job`` in order (``job_names``), which
    a stage observation ``@AFTER:<JOB>:<LOGICAL>``, ``@BEFORE:<JOB>:@REGION-FILES``
    or ``@AFTER:<JOB>:@REGION-FILES`` must run ``<JOB>`` of exactly once."""
    datasets = _datasets()
    spec: dict | None = None
    text_ok = lines_only = False
    if _member(name, datasets):
        spec = datasets[name]
        layout = spec.get("layout")
        text_ok = isinstance(layout, str) and layout.startswith("TEXT/")
    elif reserved_ok and _member(name, _LINE_OBSERVATIONS):
        text_ok = lines_only = True
    elif reserved_ok and isinstance(name, str) and name.startswith(OBS_AIX_PREFIX):
        base = _aix_paths().get(name[len(OBS_AIX_PREFIX):])
        if base is None:
            problems.add(where, "names no alternate-index path of tests/fixtures/datasets.yaml")
            return
        # Records read through an alternate-index path are the base dataset's records.
        spec = datasets[base]
    elif reserved_ok and isinstance(name, str) and (
        name.startswith(OBS_BEFORE_PREFIX)
        or (name.startswith(OBS_AFTER_PREFIX) and name.endswith(":" + OBS_REGION_FILES))
    ):
        # A region stage observation is @REGION-FILES at one point of the
        # run, so its records are lines as @REGION-FILES's are; they are
        # checked even when the job is not one point, which is reported apart.
        _region_stage_job(problems, where, name, jobs)
        text_ok = lines_only = True
    elif reserved_ok and isinstance(name, str) and name.startswith(OBS_AFTER_PREFIX):
        logical = _stage_logical(problems, where, name, jobs)
        if logical is None:
            return
        # A stage observation holds its logical dataset's records at the end of
        # the job, so they are checked as that dataset's own are.
        spec = datasets[logical]
        layout = spec.get("layout")
        text_ok = isinstance(layout, str) and layout.startswith("TEXT/")
    else:
        observations = f"; reserved observations: {', '.join(RESERVED_OBSERVATIONS)}" if reserved_ok else (
            "; reserved observations belong under file_state"
        )
        problems.add(where, f"{name!r} is not a logical dataset of tests/fixtures/datasets.yaml{observations}")
        return
    if value == BASELINE:
        return
    if not isinstance(value, dict):
        problems.add(where, "must be 'baseline' or a mapping of records, contains, count and matchers")
        return
    _unknown(problems, where, value, _DATASET_VALUE_KEYS)
    if not _dataset_asserts(value):
        problems.add(
            where,
            "asserts nothing: give records ([] for an empty dataset), count, contains with at least one record, "
            "or 'baseline'; matchers alone assert nothing",
        )
    for key in ("records", "contains"):
        if key not in value:
            continue
        if not isinstance(value[key], list):
            problems.add(f"{where}.{key}", "must be a list of records")
            continue
        for index, record in enumerate(value[key]):
            if lines_only:
                # A line may also be a whole-line matcher, as harness.baselines
                # compares it: the JCL CORPT00C writes to JOBS carries dates
                # derived from the region clock (run month or year) inside
                # fixed text, such as the DATEPARM record of a monthly report,
                # which only a matcher format can state; _check_matchers
                # checks its kind and options.
                if not (isinstance(record, str) or _is_matcher(record)):
                    problems.add(f"{where}.{key}[{index}]", "must be a line of text or a matcher {match: ...}")
            else:
                _check_record(problems, f"{where}.{key}[{index}]", record, spec, False, text_ok, expected=True)
    if "count" in value and not (_is_int(value["count"]) and value["count"] >= 0):
        problems.add(f"{where}.count", "must be a record count, 0 or more")
    if "matchers" in value and not (
        isinstance(value["matchers"], dict) and all(_is_matcher(item) for item in value["matchers"].values())
    ):
        problems.add(f"{where}.matchers", "must be a mapping of field -> matcher {match: ...}")


def _screen_text_fault(value: Any) -> str | None:
    """Why ``value`` is not an ``@text`` a conforming adapter can return, or
    ``None``: that value is the full screen as one string, ``SCREEN_ROWS`` rows
    of ``SCREEN_COLS`` characters joined by ``"\\n"`` with every space kept
    (``adapters.base.screen_text``), and it is compared exactly."""
    from adapters.base import SCREEN_COLS, SCREEN_ROWS  # adapters.base imports this module

    shape = f"the full screen as one string, {SCREEN_ROWS} rows of {SCREEN_COLS} characters joined by line breaks"
    if not isinstance(value, str):
        kind = "a list of rows" if isinstance(value, list) else type(value).__name__
        return f"must be {shape} (adapters.base.screen_text), not {kind}"
    if "\r" in value:
        return f"holds a carriage return; it must be {shape}"
    rows = value.split("\n")
    if len(rows) != SCREEN_ROWS:
        after = ", with no line break after the last row" if value.endswith("\n") else ""
        return f"holds {len(rows)} row{'' if len(rows) == 1 else 's'}; it must be {shape}{after}"
    for number, row in enumerate(rows, start=1):
        if len(row) != SCREEN_COLS:
            return f"row {number} is {len(row)} characters; it must be {shape}, every space kept"
    return None


def _check_screens(problems: _Problems, screens: Any, steps: Any) -> bool:
    """Check ``expected.screens``; returns whether it asserts anything."""
    where = "expected.screens"
    if not isinstance(screens, list):
        problems.add(where, "must be a list with one field map per step")
        return False
    if not isinstance(steps, list):
        problems.add(where, "asserts screens, which only inputs.steps produce")
    elif len(screens) != len(steps):
        problems.add(where, f"holds {len(screens)} entries for {len(steps)} steps; give one per step")
    screen_fields = _screen_fields()
    for index, entry in enumerate(screens):
        entry_where = f"{where}[{index}]"
        if not isinstance(entry, dict):
            problems.add(entry_where, "must be a field map naming @map")
            continue
        mapset = entry.get(SCREEN_MAP)
        if SCREEN_MAP not in entry:
            problems.add(entry_where, "names no @map: every step names the map it expects")
        elif not (_member(mapset, screen_fields) or mapset == TEXT_SCREEN):
            problems.add(f"{entry_where}.@map", f"{mapset!r} is not a mapset of tests/inventory/layouts.yaml or TEXT")
        for name, value in entry.items():
            field_where = f"{entry_where}.{name}"
            if name == SCREEN_MAP:
                continue
            if name == SCREEN_TEXT:
                if mapset != TEXT_SCREEN:
                    problems.add(field_where, "belongs to an unformatted screen (@map: TEXT)")
                fault = _screen_text_fault(value)
                if fault:
                    problems.add(field_where, fault)
                continue
            if mapset == TEXT_SCREEN:
                problems.add(field_where, "an unformatted screen has no fields; assert @text")
            elif _member(mapset, screen_fields) and name not in screen_fields[mapset]:
                problems.add(field_where, f"is not a field of map {mapset}")
            if not (isinstance(value, str) or _is_matcher(value)):
                problems.add(field_where, f"{value!r} must be text (or a matcher); quote it")
    return bool(screens)


def _check_expected(s: Scenario, problems: _Problems, steps: Any) -> None:
    if "expected" not in s.data:
        return
    expected = s.data["expected"]
    if not isinstance(expected, dict):
        problems.add("expected", "must be a mapping of expected outcomes")
        return
    _unknown(problems, "expected", expected, EXPECTED_KEYS)
    outcome = False
    if "return_code" in expected:
        code = expected["return_code"]
        if not (code is None or code == BASELINE or _is_int(code)):
            problems.add("expected.return_code", "must be a number, null for an abend, or 'baseline'")
    if "messages" in expected:
        messages = expected["messages"]
        if messages == BASELINE:
            outcome = True
        elif isinstance(messages, list) and all(isinstance(line, str) for line in messages):
            outcome = outcome or bool(messages)
        else:
            problems.add("expected.messages", "must be a list of text lines or 'baseline'")
    if "reject_reason_codes" in expected:
        codes = expected["reject_reason_codes"]
        if codes == BASELINE:
            outcome = True
        elif isinstance(codes, list):
            faults = [(index, _reason_code_problem(code)) for index, code in enumerate(codes)]
            for index, fault in faults:
                if fault:
                    problems.add(f"expected.reject_reason_codes[{index}]", fault)
            # An empty list asserts that no record is rejected.
            outcome = outcome or not any(fault for _, fault in faults)
        else:
            problems.add(
                "expected.reject_reason_codes",
                "must be a list of CBTRN02C reason codes ('0102' or 102) or 'baseline'",
            )
    for section in ("datasets", "file_state"):
        if section not in expected:
            continue
        value = expected[section]
        if not isinstance(value, dict):
            problems.add(f"expected.{section}", "must be a mapping of dataset -> expected content")
            continue
        for name, item in value.items():
            _check_expected_dataset(
                problems, f"expected.{section}.{name}", name, item, section == "file_state", job_names(s)
            )
        outcome = outcome or any(_dataset_asserts(item) for item in value.values())
    if "screens" in expected:
        outcome = _check_screens(problems, expected["screens"], steps) or outcome
    elif isinstance(steps, list) and steps:
        problems.add("expected.screens", "is required: one entry per step, each naming the map it expects (@map)")
    if "matchers" in expected and not (
        isinstance(expected["matchers"], dict) and all(_is_matcher(item) for item in expected["matchers"].values())
    ):
        problems.add("expected.matchers", "must be a mapping of name -> matcher {match: ...}")
    if not outcome:
        problems.add(
            "expected",
            "asserts no business outcome beyond return_code: add messages, reject_reason_codes, "
            "datasets, file_state or screens",
        )


def _check_matchers(s: Scenario, problems: _Problems) -> None:
    relative = clock_mode(s) == "relative"
    for where, value in _walk(s.data.get("expected"), "expected"):
        if not _is_matcher(value):
            continue
        kind = value["match"]
        options = MATCH_KINDS.get(kind) if isinstance(kind, str) else None
        if options is None:
            problems.add(f"{where}.match", f"{kind!r} is not one of {', '.join(MATCH_KINDS)}")
        else:
            _unknown(problems, where, value, options | {"match"})
            if "offset_days" in value and not _is_int(value["offset_days"]):
                problems.add(f"{where}.offset_days", "must be a whole number of days")
            if "format" in value and not isinstance(value["format"], str):
                problems.add(f"{where}.format", "must be a strftime format")
        if not relative:
            problems.add(
                where,
                "a matcher stands for a clock the adapter cannot fix, so it needs "
                "clock_mode: relative; under the fixed clock write the exact value",
            )
    for section in ("preconditions", "inputs"):
        for where, value in _walk(s.data.get(section), section):
            if _is_matcher(value):
                problems.add(where, "matchers belong under expected only")


_NEUTRALITY_EXEMPT = frozenset(f"expected.file_state.{name}" for name in _PLATFORM_TEXT_OBSERVATIONS)


def _check_neutrality(s: Scenario, problems: _Problems) -> None:
    for section in ("preconditions", "inputs", "expected"):
        for where, text in _texts(s.data.get(section), section, _NEUTRALITY_EXEMPT.__contains__):
            for label, pattern in _PLATFORM_PATTERNS:
                if pattern.search(text):
                    problems.add(
                        where,
                        f"{_clip(text)!r} looks like {label}; scenarios name only logical datasets, "
                        "observations, programs, jobs, transactions, AID keys and BMS fields",
                    )
                    break


def validate(s: Scenario) -> list[str]:
    """Every schema problem of ``s`` as ``<file>: <where>: <problem>``; empty
    when the scenario is valid. ``<file>`` is the scenario file, relative to
    the repository root when it lies inside it, and ``<where>`` the key path,
    ``(document)`` for the file as a whole. Reference data that cannot be read
    is reported under ``(reference data)`` rather than raised."""
    problems = _Problems(s)
    try:
        _validate(s, problems)
    except (HarnessError, OSError, yaml.YAMLError) as exc:
        problems.add(_REFERENCE_DATA, f"reference data could not be read: {exc}")
    except RecursionError:
        # _unsafe_shape bounds the scenario's own data first; this keeps the
        # promise to report rather than raise should anything still recurse.
        problems.add(_DOCUMENT, "the scenario data nests too deeply to check")
    return problems.items


def _validate(s: Scenario, problems: _Problems) -> None:
    for message in s.load_problems:
        problems.add(_DOCUMENT, message)
    data = s.data
    unsafe = _unsafe_shape(data)
    if unsafe is not None:
        # The checks below recurse into the data, so none of them runs.
        problems.add(*unsafe)
        return
    _check_identity(s, problems)
    _unknown(problems, _DOCUMENT, data, _TOP_KEYS)
    for key in _REQUIRED_TOP_KEYS:
        if key not in data:
            problems.add(key, "is required")
    for key, value in data.items():
        for where, item in _walk(value, str(key)):
            if isinstance(item, float):
                problems.add(where, f"{item!r} is a float; write numbers as quoted decimal text with their scale")
    _check_inventory_ids(s, problems)
    _check_tags_and_gaps(s, problems)
    sessions = _check_preconditions(s, problems)
    expected = data.get("expected")
    screens = expected.get("screens") if isinstance(expected, dict) else None
    _check_inputs(s, problems, sessions, screens if isinstance(screens, list) else [])
    _check_expected(s, problems, s.inputs.get("steps") if "steps" in s.inputs else None)
    _check_matchers(s, problems)
    _check_neutrality(s, problems)
    adapters = capable_adapters(s)
    if not adapters:
        problems.add(
            "inputs",
            "no adapter can run this scenario: its program, job or steps match neither "
            "gnucobol_batch/programs.yaml and jobs.yaml nor unikix/resources.yaml",
        )
    elif "unikix" in adapters and clock_mode(s) != "relative" and s.id not in FIXED_CLOCK_SCENARIOS:
        problems.add(
            "preconditions.parameters.clock_mode",
            "unikix can run this scenario but cannot fix its clock (GAP-MIS-015), so it declares relative",
        )


# --------------------------------------------------------------------------
# Manifest check of the tree
# --------------------------------------------------------------------------


def _listed(problems: list[str], where: str, what: str, ids: Iterable[str]) -> None:
    names = sorted(ids)
    if names:
        problems.append(f"{where}: {len(names)} {what}: {', '.join(names)}")


def _check_scenario_files(root: Path, problems: list[str]) -> None:
    """(a) and (e): the scenario files against the manifest."""
    folder = root / "scenarios"
    found: dict[str, set[str]] = {level: set() for level in LEVELS}
    # A symbolic link is reported and never followed, so its files count as missing.
    if folder.is_symlink():
        problems.append("scenarios: a symbolic link, not the scenarios folder")
    elif not folder.is_dir():
        problems.append("scenarios: the folder is missing")
    else:
        for entry in sorted(folder.iterdir()):
            if entry.is_symlink():
                problems.append(f"scenarios/{entry.name}: a symbolic link, not a level folder ({', '.join(LEVELS)})")
                continue
            if not (entry.is_dir() and entry.name in LEVELS):
                problems.append(f"scenarios/{entry.name}: not a level folder ({', '.join(LEVELS)})")
                continue
            for path in sorted(entry.iterdir()):
                if path.is_symlink():
                    problems.append(
                        f"scenarios/{entry.name}/{path.name}: a symbolic link, not a scenario file <id>.yaml"
                    )
                elif _scenario_file(path):
                    found[entry.name].add(path.stem)
                else:
                    problems.append(f"scenarios/{entry.name}/{path.name}: not a scenario file <id>.yaml")
    misplaced: set[str] = set()
    for level in LEVELS:
        wrong = {stem for stem in found[level] - MANIFEST[level] if stem in ALL_IDS}
        for stem in sorted(wrong):
            problems.append(
                f"scenarios/{level}/{stem}.yaml: the manifest places it under scenarios/{_LEVEL_OF[stem]}/"
            )
        misplaced |= wrong
        _listed(problems, f"scenarios/{level}", "file(s) outside the manifest", found[level] - ALL_IDS)
    for level in LEVELS:
        missing = MANIFEST[level] - found[level] - misplaced
        _listed(problems, f"scenarios/{level}", "manifest id(s) without a file", missing)
    present = set().union(*found.values())
    _listed(problems, "scenarios", "fixed id(s) without a file", FIXED_IDS - present)


def _check_baseline_dirs(root: Path, problems: list[str]) -> None:
    """(b): one baseline directory per manifest id, holding expected.yaml and
    manifest.yaml, and raw datasets only under datasets/*.bin. A symbolic link
    is reported and never followed, so a linked baseline directory counts as
    missing and a linked expected.yaml or manifest.yaml as lacking."""
    folder = root / "baselines"
    present: set[str] = set()
    lacking: list[str] = []
    if folder.is_symlink():
        problems.append("baselines: a symbolic link, not the baselines folder")
    elif folder.is_dir():
        for entry in sorted(folder.iterdir()):
            if entry.is_symlink():
                problems.append(f"baselines/{entry.name}: a symbolic link, not a baseline directory")
                continue
            if not entry.is_dir():
                problems.append(f"baselines/{entry.name}: not a baseline directory")
                continue
            present.add(entry.name)
            if entry.name not in ALL_IDS:
                continue
            for required in ("expected.yaml", "manifest.yaml"):
                if not _regular_file(entry / required):
                    lacking.append(f"{entry.name}/{required}")
            for item in sorted(entry.iterdir()):
                if item.is_symlink():
                    problems.append(f"baselines/{entry.name}/{item.name}: a symbolic link, not part of a baseline")
                    continue
                if item.name in ("expected.yaml", "manifest.yaml"):
                    continue
                if item.name != "datasets" or not item.is_dir():
                    problems.append(f"baselines/{entry.name}/{item.name}: not part of a baseline")
                    continue
                for raw in sorted(item.iterdir()):
                    if raw.is_symlink():
                        problems.append(
                            f"baselines/{entry.name}/datasets/{raw.name}: a symbolic link, not a <LOGICAL>.bin dataset"
                        )
                    elif not (raw.is_file() and raw.suffix == ".bin"):
                        problems.append(f"baselines/{entry.name}/datasets/{raw.name}: not a <LOGICAL>.bin dataset")
    _listed(problems, "baselines", "directory(ies) outside the manifest", present - ALL_IDS)
    _listed(problems, "baselines", "manifest id(s) without a baseline directory", ALL_IDS - present)
    _listed(problems, "baselines", "required file(s) missing", lacking)


def _reports_folder(root: Path, name: str, what: str, problems: list[str]) -> Path | None:
    """``root/reports/<name>`` when it is a folder to check, else ``None``:
    absent or not a folder, or ``reports`` or the folder itself is a symbolic
    link, which is reported and never followed."""
    reports = root / "reports"
    folder = reports / name
    if reports.is_symlink():
        problems.append(f"reports: a symbolic link, so reports/{name} is not checked")
        return None
    if folder.is_symlink():
        problems.append(f"reports/{name}: a symbolic link, not the {what} folder")
        return None
    return folder if folder.is_dir() else None


def _located_scenario(root: Path, scenario_id: str) -> Scenario | None:
    """The scenario of manifest id ``scenario_id`` under ``root/scenarios``:
    its file in the manifest level folder or, failing that, in another level
    folder, or ``None`` when no level folder holds a scenario file for it.
    Raises ``ScenarioError`` when the file does not load."""
    level = _LEVEL_OF[scenario_id]
    # A misplaced scenario file is reported under (a); its capability still counts here.
    for other in [level] + [other for other in LEVELS if other != level]:
        path = root / "scenarios" / other / f"{scenario_id}.yaml"
        if _scenario_file(path):
            return _cached_file(path, other)
    return None


def _check_result_files(root: Path, problems: list[str]) -> None:
    """(c): a result record for every manifest id that gnucobol-batch can
    run, and only for those. A symbolic link is reported and never followed,
    so a linked record counts as missing, and every record does when
    ``reports`` or ``reports/results`` is a link, absent or not a folder. An
    id whose scenario file is missing (reported under (a)) or does not load
    (the schema check reports it) cannot be judged, so no record is required
    for it."""
    folder = _reports_folder(root, "results", "result records", problems)
    recorded: set[str] = set()
    entries = sorted(folder.iterdir()) if folder is not None else []
    for path in entries:
        where = f"reports/results/{path.name}"
        if path.is_symlink():
            problems.append(f"{where}: a symbolic link, not a result record <id>.yaml")
            continue
        if not (path.is_file() and path.suffix == ".yaml"):
            problems.append(f"{where}: not a result record <id>.yaml")
            continue
        level = _LEVEL_OF.get(path.stem)
        if level is None:
            problems.append(f"{where}: {path.stem} is not in the manifest")
            continue
        recorded.add(path.stem)
        try:
            scenario = _located_scenario(root, path.stem)
        except ScenarioError as exc:
            problems.append(f"{where}: {exc}")
            continue
        if scenario is None:
            problems.append(f"{where}: no scenario file scenarios/{level}/{path.stem}.yaml")
            continue
        if "gnucobol-batch" not in capable_adapters(scenario):
            problems.append(f"{where}: gnucobol-batch cannot run {path.stem}, so it has no result record")
    missing: set[str] = set()
    for scenario_id in ALL_IDS - recorded:
        try:
            scenario = _located_scenario(root, scenario_id)
        except ScenarioError:
            continue
        if scenario is not None and "gnucobol-batch" in capable_adapters(scenario):
            missing.add(scenario_id)
    _listed(problems, "reports/results", "result record(s) missing for id(s) gnucobol-batch can run", missing)


def _check_evidence_dirs(root: Path, problems: list[str]) -> None:
    """(d): UniKix evidence only for manifest ids. A symbolic link is reported
    and never followed."""
    folder = _reports_folder(root, "unikix", "UniKix evidence", problems)
    if folder is None:
        return
    for execution in sorted(folder.iterdir()):
        if execution.is_symlink():
            problems.append(f"reports/unikix/{execution.name}: a symbolic link, not an evidence folder or file")
            continue
        if not execution.is_dir():
            continue
        for entry in sorted(execution.iterdir()):
            if entry.is_symlink():
                problems.append(
                    f"reports/unikix/{execution.name}/{entry.name}: a symbolic link, not a scenario evidence folder"
                )
            elif entry.is_dir() and entry.name not in ALL_IDS:
                problems.append(f"reports/unikix/{execution.name}/{entry.name}: not a scenario id of the manifest")


def check_manifest(tests_root: str | Path = TESTS_ROOT) -> list[str]:
    """Every departure of the tree under ``tests_root`` from the closed id
    manifest: (a) scenario files per level folder (missing, outside the
    manifest, misplaced, or not ``<id>.yaml``); (b) baseline directories, each
    with ``expected.yaml`` and ``manifest.yaml``; (c) result records, one for
    every scenario gnucobol-batch can run and none for any other (missing,
    outside the manifest, for a scenario gnucobol-batch cannot run, or not
    ``<id>.yaml``); (d) UniKix evidence directories; (e) the fixed ids. In
    (a) to (d) a symbolic link, whatever it points to, is a departure and is
    never followed: the ``scenarios``, ``baselines``, ``reports``,
    ``reports/results`` or ``reports/unikix`` folder, or an entry of
    ``scenarios``, a level folder, ``baselines``, a baseline directory or its
    ``datasets``, ``reports/results``, ``reports/unikix`` or an execution
    folder. Nothing under a linked folder is checked, a linked scenario file,
    baseline directory or result record does not count for its id, and a
    linked ``expected.yaml`` or ``manifest.yaml`` counts as missing. Empty
    when the tree conforms."""
    root = Path(tests_root)
    problems: list[str] = []
    _check_scenario_files(root, problems)
    _check_baseline_dirs(root, problems)
    _check_result_files(root, problems)
    _check_evidence_dirs(root, problems)
    return problems
