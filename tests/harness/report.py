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
    runs leave them byte-identical. Nothing is regenerated, and nothing
    written, while the inventory lists another number of items than its
    frozen ``header.total`` or a gnucobol-batch record cannot be read or is
    a symbolic link: ``ReportError`` names each.

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
expectations, ``gaps.yaml``, inventory additions and closure records and,
beside the UniKix evidence, ``validation.yaml`` and the transient
``promotion.yaml`` journal of an in-progress promotion, which the import
removes once the promotion completes; on revalidation, ``validation.yaml``,
``gaps.yaml`` and the inventory's closure records. The UniKix zip is only
read, in memory.
"""

from __future__ import annotations

import argparse
import contextlib
import copy
import csv
import datetime as _dt
import errno
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
from dataclasses import dataclass, field, replace
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path
from typing import Any, Callable, Iterable, Iterator, Mapping, Sequence

import yaml

_TESTS_ROOT = str(Path(__file__).resolve().parents[1])
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from adapters.base import AdapterError, Result, after_job_observation  # noqa: E402
from harness import (  # noqa: E402
    DEFAULT_CLOCK,
    INVENTORY_DIR,
    MARKER_FOR_ADAPTER,
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
# The UniKix command registry adapters/unikix/env.py runs from: GAP-MIS-009
# closes only once every entry of it that cites GAP-MIS-009 is confirmed.
UNIKIX_COMMANDS_FILE = TESTS_ROOT / "adapters" / "unikix" / "commands.yaml"
MATRIX_FILE = REPORTS_DIR / "coverage-matrix.csv"
REGISTER_FILE = REPORTS_DIR / "gap-register.csv"
REPORT_FILE = REPORTS_DIR / "coverage-report.md"
VALIDATION_FILE = "validation.yaml"
RESULT_FILE = "result.yaml"
ENVIRONMENT_FILE = "environment.yaml"
EVIDENCE_DATASETS_DIR = "datasets"
# The most bytes an evidence YAML file (result.yaml, environment.yaml,
# validation.yaml, the promotion.yaml journal) may hold before it is parsed:
# far above any evidence file (the largest committed baseline expected.yaml,
# which a result.yaml's decoded outcome mirrors, is about 237 KB), and far
# below what exhausts memory, since loading a YAML file costs tens of times
# its size. The importer never writes a journal larger than this.
EVIDENCE_YAML_MAX_BYTES = 4 * 1024 * 1024

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
# Where each seam's limitation shows in a decoded outcome, so that a
# cross-adapter divergence is attributed to a seam only when the difference
# lies within its scope (AAP 0.3.2, line 544: jobs made only of utility steps
# are verified only as far as the replacements, GAP-STB-005 to 009; AAP 0.3.3:
# each seam's scope and limitation). The seam of each utility step kind of
# the gnucobol-batch jobs.yaml:
_SEAM_OF_UTILITY_STEP = {
    "idcams": "GAP-STB-005", "sort": "GAP-STB-006", "iebgener": "GAP-STB-007", "iefbr14": "GAP-STB-007",
    "sdsf": "GAP-STB-008",
}
_SEAM_OF_CATALOG = "GAP-STB-009"  # the emulated catalog every job's datasets go through
# The seams a gnucobol-batch unit runs through, from its programs.yaml entry:
# every unit through the compile and runtime mapping, an indexed dd through
# Berkeley DB, a PARM or CALL driver launch through PARMDRV or DTCDRV,
# CSUTLDTC's CALL 'CEEDAYS' through the CEEDAYS stub, and a TIOT through the
# page-zero shim.
_SEAM_OF_UNIT = "GAP-STB-011"
_SEAM_OF_INDEXED = "GAP-STB-010"
_SEAM_OF_DRIVER = {"parmdrv": "GAP-STB-004", "dtcdrv": "GAP-STB-004"}
_SEAM_OF_STUB_CALLER = {"CSUTLDTC": "GAP-STB-002"}
_SEAM_OF_TIOT = "GAP-STB-003"
# A job step goes through Berkeley DB (_SEAM_OF_INDEXED) when a dd or
# concatenation member names an indexed dataset, or when its IDCAMS SYSIN
# defines an indexed cluster, an alternate index or a path, builds an
# alternate index, or copies to or from an indexed dataset by name. The
# keywords are whole tokens, abbreviations included, as the IDCAMS
# replacement reads them; a cluster is indexed unless it codes an
# organization the replacement refuses.
_IDCAMS_ALIASES = {
    "DEF": "DEFINE", "BIX": "BLDINDEX", "CL": "CLUSTER", "AIX": "ALTERNATEINDEX", "NIXD": "NONINDEXED",
    "NUMD": "NUMBERED", "LIN": "LINEAR", "IDS": "INDATASET", "ODS": "OUTDATASET",
}
_INDEXED_DEFINES = frozenset({"ALTERNATEINDEX", "PATH"})
_NONINDEXED_CLUSTERS = frozenset({"NONINDEXED", "NUMBERED", "LINEAR"})
_IDCAMS_DSN_OPERANDS = frozenset({"INDATASET", "OUTDATASET"})
_IDCAMS_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
# A quoted string, a parenthesis, or a word; blanks and commas separate.
_IDCAMS_TOKEN_RE = re.compile(r"'(?:[^']|'')*'?|[()]|[^\s(),']+")
# A job step goes through the DISP emulation (utilities.py
# apply_normal_dispositions, which the job driver applies to every step that
# ends normally, program steps included) for each dataset whose normal-end
# disposition is CATLG, DELETE or UNCATLG, or whose status is NEW (coded or
# omitted) or MOD, a dataset the step may allocate: its omitted disposition
# is then DELETE, and KEEP or PASS leave it noted as uncatalogued.
_SEAM_OF_DISPOSITION = "GAP-STB-007"
_ACTED_DISPOSITIONS = frozenset({"CATLG", "DELETE", "UNCATLG"})
_ALLOCATING_STATUSES = frozenset({"NEW", "MOD"})
# The seams that produce a reserved observation on gnucobol-batch.
_SEAMS_OF_OBSERVATION = {
    "@CATALOG": ("GAP-STB-005", "GAP-STB-007", "GAP-STB-009"),
    "@REGION-FILES": ("GAP-STB-008",),
}
# The CEE3ABD stub: return_code null, and its marker among the messages.
_SEAM_OF_ABEND = "GAP-STB-001"
_ABEND_MARKER = "CARDDEMO-ABEND CEE3ABD"
# An IBM Language Environment (CEEnnnnx) or COBOL runtime (IGZnnnnx) message
# identifier: the abend and condition-handling output GAP-STB-001's
# limitation names, which the stub does not write. Any other line a runtime
# adds is business output.
_LE_DIAGNOSTIC_RE = re.compile(r"\b(?:CEE|IGZ)\d{4}[A-Z]\b")
# The same records in another order: SORT's ASCII collating, Berkeley DB key
# order, the runtime's ASCII collating sequence.
_SEAMS_OF_RECORD_ORDER = ("GAP-STB-006", "GAP-STB-010", "GAP-STB-011")
# The same records but for x'00' where the other side holds spaces: defaultbyte 0.
_SEAMS_OF_NUL_FILL = ("GAP-STB-011",)

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
# The proof contract: the job-chain proof runs on gnucobol-batch, so each
# proof scenario's file, its baseline and its current passed gnucobol-batch
# record must hold the values below.
# The jobs each scenario runs against one catalog, as its inputs.job lists them.
PROOF_JOB_SEQUENCES = {
    "BAT-POSTINT-normal-01": ("POSTTRAN", "INTCALC"),
    "BAT-CHAIN-normal-01": ("POSTTRAN", "INTCALC", "TRANBKP", "COMBTRAN", "CREASTMT", "TRANREPT"),
}
# Each job's return code, the highest of its steps: POSTTRAN rejects records
# and ends RC 4, every later job RC 0; the scenario's is the highest of all.
PROOF_JOB_RETURN_CODES = {"POSTTRAN": 4, "INTCALC": 0, "TRANBKP": 0, "COMBTRAN": 0, "CREASTMT": 0, "TRANREPT": 0}
PROOF_RETURN_CODE = 4
# The precondition datasets, each exactly {base: <fixture>} with no inline
# records: the repository files, dailytran.txt as DALYTRAN and TRANSACT-INIT
# as TRANSACT. No fault is declared.
PROOF_FIXTURES = {
    "DALYTRAN": "DALYTRAN",
    "CARDXREF": "CARDXREF",
    "ACCTDATA": "ACCTDATA",
    "TCATBALF": "TCATBALF",
    "DISCGRP": "DISCGRP",
    "CUSTDATA": "CUSTDATA",
    "TRANTYPE": "TRANTYPE",
    "TRANCATG": "TRANCATG",
    "TRANSACT": "TRANSACT-INIT",
}
PROOF_FIXTURE_SOURCES = {"DALYTRAN": "app/data/ASCII/dailytran.txt"}
# Both run at DEFAULT_CLOCK on gnucobol-batch. The chain depends on the
# absolute date (the TRANREPT INCLUDE window filters what POSTTRAN stamped),
# so it is fixed; POSTINT may state its dates relative to the run date.
PROOF_CLOCK_MODES = {"BAT-POSTINT-normal-01": ("fixed", "relative"), "BAT-CHAIN-normal-01": ("fixed",)}
PROOF_PARAMETERS = {"BAT-POSTINT-normal-01": {}, "BAT-CHAIN-normal-01": {"DATEPARM": "2022-01-01 2022-07-06"}}
# Message lines (trailing spaces aside) and how often each appears: CBTRN02C's
# counts once, CBACT04C's missing-group line once for each of the 100 TCATBAL
# records, because no account's group id is set (GAP-DEF-005).
PROOF_MESSAGE_COUNTS = (
    ("TRANSACTIONS PROCESSED :000000300", 1),
    ("TRANSACTIONS REJECTED  :000000038", 1),
    ("DISCLOSURE GROUP RECORD MISSING", 100),
)
# CBTRN02C's line for each TCATBAL record it creates, around the 17-byte key
# [app/cbl/CBTRN02C.cbl:476-477]; POSTTRAN creates 50.
PROOF_TCATBAL_CREATED = ("TCATBAL record not found for key : ", ".. Creating.")
PROOF_TCATBAL_ADDED = 50
# The step lines both adapters' job runs write, STEP <name> RC <nnnn> or
# STEP <name> ABEND; each job's steps follow its gnucobol-batch step table.
PROOF_STEP_LINE_RE = re.compile(r"STEP (\S+) (?:RC ([0-9]{4})|ABEND)")
# Every reject is over limit: reason 0102 and its text.
PROOF_REJECT_CODE = "0102"
PROOF_REJECT_TEXT = "OVERLIMIT TRANSACTION"
PROOF_REJECT_COUNT = 38
# The interest records' TRAN-IDs: INTCALC's PARM 2022071800 plus a counter.
PROOF_INTEREST_IDS = tuple(f"2022071800{number:06d}" for number in range(1, 51))
# The stage observations (adapters.base.after_job_observation), each a
# dataset as one job of the chain left it, for the states a later job
# overwrites before the end of the run. ACCTDATA at the end of POSTTRAN holds
# every account's posted balance and cycle totals [app/cbl/CBTRN02C.cbl:545-554],
# which INTCALC zeroes for accounts 1 to 49. At the end of TRANBKP,
# TRANSACT-BKUP is its backup generation of the 262 posted records, which
# TRANREPT's generation supersedes as the current one, and TRANSACT the
# cluster it redefines empty [app/jcl/TRANBKP.jcl:23-67], which COMBTRAN
# reloads.
PROOF_POSTED_ACCOUNTS = after_job_observation("POSTTRAN", "ACCTDATA")
PROOF_FIRST_BACKUP = after_job_observation("TRANBKP", "TRANSACT-BKUP")
PROOF_EMPTIED_TRANSACT = after_job_observation("TRANBKP", "TRANSACT")
# Decoded record counts. TRANSACT holds the 262 posted records, and 312 once
# the chain's COMBTRAN merges the 50 interest records in; TRANSACT-BKUP, the
# current generation, is TRANREPT's backup of those 312. Both scenarios hold
# the 50 accounts after POSTTRAN, and the chain TRANBKP's backup of the 262
# posted records and its emptied TRANSACT. TCATBALF is checked as its
# fixture's record count plus PROOF_TCATBAL_ADDED.
PROOF_RECORD_COUNTS = {
    "BAT-POSTINT-normal-01": {
        "DALYREJS": PROOF_REJECT_COUNT, "TRANSACT": 262, "ACCTDATA": 50, "SYSTRAN": len(PROOF_INTEREST_IDS),
        PROOF_POSTED_ACCOUNTS: 50,
    },
    "BAT-CHAIN-normal-01": {
        "DALYREJS": PROOF_REJECT_COUNT, "TRANSACT": 312, "ACCTDATA": 50, "SYSTRAN": len(PROOF_INTEREST_IDS),
        "TRXFL": 312, "TRANSACT-BKUP": 312,
        PROOF_POSTED_ACCOUNTS: 50, PROOF_FIRST_BACKUP: 262, PROOF_EMPTIED_TRANSACT: 0,
    },
}
# TRAN-PROC-TS of every TRANSACT record and of every record of TRANBKP's
# backup (PROOF_FIRST_BACKUP): DEFAULT_CLOCK in the DB2 timestamp format the
# programs stamp, or the run-timestamp matcher's name, which the projection
# holds where the baseline asserts the field through that matcher.
PROOF_PROC_TS = ("2022-07-06-10.00.00.000000", "run-timestamp")
# The ACCTDATA fields the baseline must expect for every account, at the end
# of the run and after POSTTRAN (PROOF_POSTED_ACCOUNTS): the balance and both
# cycle fields POSTTRAN posts and INTCALC rewrites.
PROOF_ACCOUNT_FIELDS = ("ACCT-CURR-BAL", "ACCT-CURR-CYC-CREDIT", "ACCT-CURR-CYC-DEBIT")
# The accounts INTCALC rewrites, 1 to 49, and the last one, 50, it never
# rewrites (GAP-DEF-003); _proof_account_problems and
# _proof_posted_account_problems derive both.
PROOF_UPDATED_ACCOUNTS = tuple(range(1, 50))
PROOF_UNUPDATED_ACCOUNT = 50
# Decoded number text as the projection holds it, '0.00' or '-47.88'.
PROOF_NUMBER_RE = re.compile(r"[+-]?[0-9]+(?:\.[0-9]+)?")
# The chain's catalog observation lines, '<name> <type> <generation or ->'
# (adapters.base.format_catalog): TRANBKP's and TRANREPT's FILEOUT each
# catalog one TRANSACT.BKUP(+1) generation. A reserved observation, the
# catalog or a stage observation, is asserted under file_state only, so each
# scenario's are listed apart from the datasets of PROOF_REQUIREMENTS.
PROOF_CATALOG = "@CATALOG"
PROOF_OBSERVATIONS = {
    "BAT-POSTINT-normal-01": (PROOF_POSTED_ACCOUNTS,),
    "BAT-CHAIN-normal-01": (PROOF_CATALOG, PROOF_POSTED_ACCOUNTS, PROOF_FIRST_BACKUP, PROOF_EMPTIED_TRANSACT),
}
PROOF_BKUP_GENERATION_RE = re.compile(r"AWS\.M2\.CARDDEMO\.TRANSACT\.BKUP GENERATION G[0-9]{4}V00")
PROOF_BKUP_GENERATIONS = 2
# TRXFL, the COSTM01 TRNX-RECORD keyed by card and transaction id: the 328
# bytes CREASTMT's OUTREC builds [app/jcl/CREASTMT.JCL:54] padded with spaces
# to 350 (GAP-STB-006), so the record's last 20 bytes, its FILLER under the
# decode key harness.codec.field_key gives it, are spaces.
PROOF_TRXFL_KEY = ("TRNX-CARD-NUM", "TRNX-ID")
PROOF_TRXFL_PADDING = ("TRNX-RECORD.TRNX-REST.FILLER#1", " " * 20)
# One statement for each of the 50 cards: CBSTM03A writes ST-LINE0 to
# STMTFILE and HTML-L01 to HTMLFILE once in each 5000-CREATE-STATEMENT
# [app/cbl/CBSTM03A.CBL:84-88,150,458-461,506-509].
PROOF_STATEMENTS = 50
PROOF_STATEMENT_MARK = "START OF STATEMENT"
PROOF_HTML_HEADER = "<!DOCTYPE html>"
# TRANREPT's report: each detail line begins with TRAN-REPORT-TRANS-ID X(16)
# [app/cpy/CVTRA07Y.cpy:15-16], and the totals lines begin with their labels
# [app/cpy/CVTRA07Y.cpy:50-66]: (label, fewest, most or None).
PROOF_REPORT_ID_WIDTH = 16
PROOF_REPORT_TOTALS = (("Page Total", 1, None), ("Account Total", 1, None), ("Grand Total", 1, 1))

# Tech spec Section 6.6.7.1 execution-effort table: seed label, scenarios,
# the selectors of the suite scenarios it seeds and whether only their batch
# (non-e2e) scenarios. A selector is an area code ("SEC") or a scenario-id
# prefix ("MNU-COADM01C"); a scenario matches it when its id is the selector
# or begins with the selector and "-". Authentication seeds the sign-on
# scenarios and the COADM01C admin-menu ones: the Authentication and
# Authorization Workflow of Sections 6.6.2.2.1 and 6.6.9.1, whose
# Authorization Testing exercises access to the CA00 admin menu. COMEN01C is
# seeded by none.
SEED_COUNTS = (
    ("Authentication", 5, ("SEC", "MNU-COADM01C"), False),
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
# The same projection with the probe failing: CBSTM03A, CBSTM03B and 8 of
# their items, CREASTMT, the daily cycle, COSTM01 and CUSTREC become blocked,
# and CBSTM03A's post-open fault item turns from implemented-unexecuted to
# blocked. The report shows it beside the first; differences are explained
# against the first, which is the plan's CI projection.
PLANNING_PROJECTION_NO_PAGE_ZERO = {VERIFIED: 112, FAILING: 0, BLOCKED: 520, UNEXECUTED: 8, NOT_COVERED: 44}

PYTHON_PACKAGES = ("pytest", "pluggy", "iniconfig", "packaging", "Pygments", "PyYAML")
DPKG_PACKAGES = ("build-essential", "libgmp-dev", "libncurses-dev", "libdb-dev", "s3270")
# environment.yaml keys of the UniKix version probes (adapters/unikix/env.py);
# GAP-MIS-009 closes once all five name a version and every commands.yaml
# entry citing it is confirmed (_settle_gaps).
UNIKIX_VERSION_KEYS = ("version_tpe", "version_bpe", "version_vcobol", "version_licence", "version_java")
_VERSION_FAILURE_PREFIXES = ("unavailable", "not defined", "exit status")
# The facts every environment.yaml records as non-empty text, wherever it
# nests them: the installation paths adapters/unikix/env.py requires, and
# the deployment root with the paths env.py derives from it.
_INSTALLATION_FACTS = ("UNIKIX", "INSTEBM", "VCOBOL", "KIXLICDIR", "JAVA_HOME")
_DEPLOYMENT_FACTS = ("BASE_DIR", "PROJECT_ROOT", "KIXSYS", "KIXDATA", "EBMHOME", "JOBLIB", "MBM_LOCK_DIR")
# The run-date facts environment.yaml always names, each a date or null when
# the run showed none; other run-date facts may stand beside them.
_RUN_DATE_KEYS = ("bpe_job_start_date", "first_step_screen_date")

_SUBPROCESS_TIMEOUT_S = 120
_TEXT_CLIP = 2000
_ADAPTER_ENV = "CARDDEMO_ADAPTER"
# How much of a refused CARDDEMO_ADAPTER value an error message shows.
_ADAPTER_CLIP = 40
_SELECTOR_LIST_KEYS = ("activation", "area", "kind", "level", "owner", "source_path", "name", "source", "scenario")
_SELECTOR_FLAG_KEYS = ("via_scenarios", "unikix_dependent")
_LINES_SUFFIX_RE = re.compile(r":\d+(?:-\d+)?(?:,\d+(?:-\d+)?)*$")


class ReportError(HarnessError):
    """The report inputs (gap register, evidence, a file to rewrite) do not
    have the shape the reporting rules need."""


def _clip(text: str, limit: int = _TEXT_CLIP) -> str:
    text = str(text)
    return text if len(text) <= limit else text[: limit - 3] + "..."


def _os_problem(exc: OSError, path: str | Path | None = None) -> str:
    """``exc`` on one line as ``<path>: <reason>``: the file it names, else
    ``path``, as ``display_path`` shows it, so no absolute checkout path
    reaches a message."""
    name = exc.filename if isinstance(exc.filename, (str, os.PathLike)) else path
    reason = exc.strerror or str(exc)
    return f"{display_path(name)}: {reason}" if name is not None else reason


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


# What settles a register entry, its resolving_evidence and each earlier
# closure's in superseded_evidence: the commit that did, a git object id
# abbreviated (7 to 40 hex) or in full (sha1, or sha256 as git_head returns
# it in a sha256 repository), or the execution whose evidence did.
_EVIDENCE_COMMIT_RE = re.compile(r"[0-9a-f]{7,40}|[0-9a-f]{64}")
_EXECUTION_EVIDENCE_KEYS = frozenset({"execution_id", "evidence_path", "scenario_id", "counterpart_of"})
_EVIDENCE_SHAPE = ("{execution_id, evidence_path} naming that execution's evidence (optionally scenario_id, "
                   "counterpart_of) or {commit}")


def _resolving_evidence_problem(evidence: Any) -> str | None:
    """Why ``evidence`` does not name what settled an entry, or ``None`` when
    it does. It is ``{commit}``, a git commit id, or ``{execution_id,
    evidence_path}`` with an optional ``scenario_id`` and ``counterpart_of``
    (the gnucobol-batch scenario a UniKix ``scenario_id`` stands in for, which
    the seam rule reads), every value text. ``evidence_path`` names that
    execution's evidence: ``tests/reports/unikix/<execution_id>`` or its
    ``<scenario_id>`` folder for a UniKix execution, the scenario's record
    ``tests/reports/results/<scenario_id>.yaml`` for a gnucobol-batch one."""
    if not isinstance(evidence, Mapping):
        return f"got {_clip(repr(evidence), 80)}"
    keys = set(evidence)
    if "commit" in keys:
        if keys != {"commit"}:
            return f"commit stands alone, not with {', '.join(sorted(map(repr, keys - {'commit'})))}"
        commit = evidence["commit"]
        if not isinstance(commit, str) or not _EVIDENCE_COMMIT_RE.fullmatch(commit):
            return f"commit {_clip(repr(commit), 80)} is not a git commit id of 7 to 40, or 64, lowercase hex digits"
        return None
    unknown = keys - _EXECUTION_EVIDENCE_KEYS
    if unknown:
        return f"unknown key(s) {', '.join(sorted(map(repr, unknown)))}"
    missing = [key for key in ("execution_id", "evidence_path") if key not in keys]
    if missing:
        return f"no {' and no '.join(missing)}"
    not_text = sorted(key for key, value in evidence.items() if not isinstance(value, str))
    if not_text:
        return f"{', '.join(not_text)} must be text"
    execution_id, path = evidence["execution_id"], evidence["evidence_path"]
    scenario_id, counterpart = evidence.get("scenario_id"), evidence.get("counterpart_of")
    for key, value in (("scenario_id", scenario_id), ("counterpart_of", counterpart)):
        if value is not None and not _scenarios.ID_RE.fullmatch(value):
            return f"{key} {_clip(repr(value), 80)} is not a scenario id"
    if _UNIKIX_ID_RE.fullmatch(execution_id):
        folder = f"{display_path(UNIKIX_EVIDENCE_DIR)}/{execution_id}"
        if path == folder:
            return None
        cited = path[len(folder) + 1:] if path.startswith(f"{folder}/") else ""
        if _scenarios.ID_RE.fullmatch(cited) and scenario_id in (None, cited):
            return None
        named = f"{folder}/<scenario id>" if scenario_id is None else f"{folder}/{scenario_id}"
        return f"evidence_path {_clip(repr(path), 120)} is neither {folder} nor {named}"
    if not _results._GNUCOBOL_ID_RE.fullmatch(execution_id):
        return (f"execution_id {_clip(repr(execution_id), 80)} is neither unikix-<yyyymmddThhmmssZ>-<8 hex> "
                "nor gnucobol-batch-<16 hex>")
    if counterpart is not None:
        return "counterpart_of names what a UniKix scenario_id stands in for, so it needs a UniKix execution_id"
    if scenario_id is None:
        return "a gnucobol-batch execution's evidence is its scenario's record, so it needs scenario_id"
    named = _results.evidence_path_for(_results.GNUCOBOL, scenario_id)
    if path != named:
        return f"evidence_path {_clip(repr(path), 120)} is not {named}"
    return None


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
    elif evidence is not None:
        why = _resolving_evidence_problem(evidence)
        if why is not None:
            problems.append(f"{gap_id}: resolving_evidence must be {_EVIDENCE_SHAPE}: {why}")
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
    if superseded is not None and not isinstance(superseded, list):
        problems.append(f"{gap_id}: superseded_evidence must be a list of the resolving_evidence of earlier closures")
    elif superseded is not None:
        for index, evidence in enumerate(superseded):
            why = _resolving_evidence_problem(evidence)
            if why is not None:
                problems.append(f"{gap_id}: superseded_evidence[{index}] must be {_EVIDENCE_SHAPE}: {why}")
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
# A link harness.results.refused_link names: the results folder, its parent or a record file.
_RESULTS_LINK = "is a symbolic link; result records are neither read nor written through it"


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
    appended to ``links`` when it is given, so the caller can report it.
    ``ReportError`` when the folder cannot be listed."""
    try:
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
    except OSError as exc:
        raise ReportError(f"cannot list {display_path(evidence_dir)}: {exc.strerror or exc}") from None


_ENTRY_LINK = "is a symbolic link; evidence is never read through it"


def _scenario_dirs(execution_dir: Path, links: list[Path] | None = None) -> list[Path]:
    """The scenario directories of an execution directory, in name order.
    Evidence is never read through a symbolic link, so a linked entry is
    never one of them; each is appended to ``links`` when it is given, so
    the caller can report it. ``ReportError`` when the execution directory
    cannot be listed."""
    try:
        found = []
        for entry in sorted(execution_dir.iterdir()):
            if entry.is_symlink():
                if links is not None:
                    links.append(entry)
            elif entry.is_dir():
                found.append(entry)
        return found
    except OSError as exc:
        raise ReportError(f"cannot list {display_path(execution_dir)}: {exc.strerror or exc}") from None


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
            shown = display_path(path)
            try:
                if path.is_symlink():
                    links.append(path)
                if path.is_symlink() or not path.is_file():
                    continue
                record = load_yaml(path, max_bytes=EVIDENCE_YAML_MAX_BYTES)
            except OSError as exc:
                problems.append(f"cannot read {_os_problem(exc, path)}")
                continue
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


def _frozen_total(header: Mapping[str, Any]) -> int:
    """The frozen inventory total, the inventory header's ``total``: the
    coverage denominator and the 100% claim's target, whatever the number of
    items listed beside it. A header without one, or with one that is not a
    non-negative integer (a boolean is not one), raises ``ReportError``
    naming it, so no report is computed against another denominator."""
    where = f"{display_path(_inventory.INVENTORY_FILE)} header.total"
    if "total" not in header:
        raise ReportError(f"{where} is missing; coverage is reported against the frozen total only")
    total = header["total"]
    if isinstance(total, bool) or not isinstance(total, int) or total < 0:
        raise ReportError(f"{where} {_clip(repr(total), 80)} is not a non-negative integer")
    return total


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
    # scenario id -> why its gnucobol-batch record is not read: the record
    # cannot be read or breaks the record contract, or it, or the results
    # folder holding it, is a symbolic link. Each problem is in ``problems``
    # too (once for a linked folder), and ``regenerate`` refuses while any exists.
    unread_records: dict = field(default_factory=dict)

    @property
    def total(self) -> int:
        """The frozen inventory total (``_frozen_total``), never the number
        of items listed: ``regenerate`` refuses to report while they differ."""
        return _frozen_total(self.header)

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


def _schema_problem(scenario: Any) -> str | None:
    """Why ``scenario`` fails the schema check (``harness.scenarios.validate``)
    on one line, led by its id, or ``None`` when it passes. Every verdict
    starts from that check: a live run records a scenario that fails it as
    ``failing`` with reason ``error`` before anything executes, so neither an
    imported nor a recorded outcome may pass it."""
    problems = _scenarios.validate(scenario)
    if not problems:
        return None
    return f"{scenario.id}: the scenario fails the schema check: {_clip('; '.join(problems), 600)}"


def build_state() -> State:
    """Read the inventory, the gap register, the scenarios, their baselines
    and every record, and apply the status rules. An inventory header whose
    frozen total is missing or malformed raises ``ReportError``
    (``_frozen_total``) before anything else is read. A gnucobol-batch
    record that cannot be read, breaks the record contract or is refused as
    a symbolic link (``harness.results.refused_link``: the record file, the
    results folder or its parent) counts as no record, and is recorded in
    ``unread_records`` and ``problems``. A counted passed record of a
    scenario that fails the schema check counts as failing with reason
    ``error``, and ``problems`` names the scenario."""
    inventory = _inventory.load()
    header = dict(inventory["header"])
    _frozen_total(header)
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
    unread_records: dict[str, str] = {}
    # A record that cannot be read, or one refused as a symbolic link, is not
    # an absent one: it is kept apart so that it is never taken for a run
    # that did not happen. A linked results folder is one problem.
    folder_link = _results.refused_link()
    if folder_link is not None:
        problems.append(f"{display_path(folder_link)} {_RESULTS_LINK}")
    for scenario_id, scenario in present.items():
        gnucobol = None
        if _results.GNUCOBOL in _scenarios.capable_adapters(scenario):
            link = folder_link or _results.refused_link(scenario_id)
            if link is not None:
                unread_records[scenario_id] = f"{display_path(link)} {_RESULTS_LINK}"
            else:
                try:
                    gnucobol = _results.load(scenario_id)
                except HarnessError as exc:  # its message names the record file
                    unread_records[scenario_id] = str(exc)
            if scenario_id in unread_records and folder_link is None:
                problems.append(f"{scenario_id}: gnucobol-batch record: {unread_records[scenario_id]}")
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
        # A passed record counts only while its scenario passes the schema
        # check: one edited to assert only return_code, or citing an entry the
        # register no longer holds, verifies nothing, whatever wrote the
        # record. Such a record counts as failing with reason error, in memory
        # only, and --check names the scenario. Only scenarios with a counted
        # passed record are checked.
        if any(record.get("status") == _results.STATUS_PASSED for record in counted):
            schema = _schema_problem(scenario)
            if schema is not None:
                problems.append(f"{schema}; its passed record counts as failing with reason error")
                counted = [
                    dict(record, status=_results.STATUS_FAILING, reason=_baselines.REASON_ERROR)
                    if record.get("status") == _results.STATUS_PASSED else record
                    for record in counted
                ]
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
        unread_records=unread_records,
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


def _observed_text_pattern(text: str) -> str:
    """The regular expression of ``text``, observed message text, read
    literally: as in ``_literal_pattern``, a run of spaces stands for any
    run, and every other character, ``<...>`` and ``\u2026`` included, stands
    for itself."""
    return "".join(r"\s+" if token.isspace() else re.escape(token)
                   for token in re.split(r"(\s+)", text.strip()) if token)


def _addition_pattern(text: str) -> re.Pattern[str]:
    """The pattern of an observed-message addition quoting ``text``, which
    matches ``text`` as literal text (``_observed_text_pattern``): it is what
    a screen showed, not a source template, so a ``<...>`` or ``\u2026`` in
    it is no placeholder, and reading it as one would let the addition match
    unrelated later messages, which would then never be added. A text of
    ``MIN_MESSAGE_LENGTH`` or more characters, which the source extraction
    would also take, matches as a source literal does. A shorter one matches
    only a whole observed text, never a part of a longer one, because that
    cutoff exists to keep short literals from matching nearly every message."""
    pattern = _observed_text_pattern(text)
    if len(text.strip()) >= _inventory.MIN_MESSAGE_LENGTH:
        return re.compile(pattern)
    return re.compile(rf"\A(?:{pattern})\Z")


@functools.lru_cache(maxsize=4)
def _message_patterns(texts: tuple) -> tuple:
    """``(item id, pattern)`` for each message literal of the
    ``(id, name, description, addition)`` tuples ``texts``: the text an
    item's name quotes and each quoted text of its description.

    A literal shorter than ``MIN_MESSAGE_LENGTH``, the source extraction's
    cutoff, gets no pattern, except the name of an observed-message addition
    (``addition`` true): that text was observed whatever its length, so it
    gets ``_addition_pattern``, and a later import of the same evidence
    matches the addition instead of adding it again. That pattern reads the
    addition's text literally, never as a template whose ``<...>`` or
    ``\u2026`` stand for run-time text, so a later, different message still
    matches no item and is added in turn."""
    patterns = []
    for item_id, name, description, addition in texts:
        literals = []
        start, end = name.find("'"), name.rfind("'")
        if 0 <= start < end:
            quoted = name[start + 1:end]
            if addition and quoted.strip():
                patterns.append((item_id, _addition_pattern(quoted)))
            else:
                literals.append(quoted)
        literals.extend(_QUOTED_RE.findall(description))
        for literal in literals:
            if len(literal.strip()) >= _inventory.MIN_MESSAGE_LENGTH:
                patterns.append((item_id, _literal_pattern(literal)))
    return tuple(patterns)


def _patterns_of(items: Iterable[Mapping[str, Any]]) -> tuple:
    """The message patterns of ``items`` (``_message_patterns``). An item is
    an observed-message addition when its ``source`` lies under the UniKix
    evidence folder, where ``_new_message_items`` cites the record that
    observed it."""
    evidence = f"{display_path(UNIKIX_EVIDENCE_DIR)}/"
    return _message_patterns(tuple(
        (item["id"], str(item.get("name") or ""), str(item.get("description") or ""),
         str(item.get("source") or "").startswith(evidence))
        for item in sorted(items, key=lambda item: item["id"])
    ))


def _first_line(text: str) -> str:
    """The first non-blank line of ``text`` without its surrounding blanks,
    or ``""`` when every line is blank."""
    return next((line.strip() for line in text.split("\n") if line.strip()), "")


def message_items(value: str, field_name: str, patterns: Sequence[tuple[str, re.Pattern[str]]]) -> list[str]:
    """The inventory items whose quoted message text matches an observed
    ``ERRMSG`` value (the whole value, trailing spaces aside) or appears in an
    unformatted screen's ``@text``, or matches the whole of that screen's
    first non-blank line, the text an addition made from the screen quotes."""
    if field_name == _scenarios.SCREEN_TEXT:
        # SEND TEXT wraps at column 80, so a message may run on into the next row.
        screen = value.replace("\n", "")
        # A pattern without anchors that matches the whole first line also
        # matches within the screen, so the first line adds a match only for
        # a short addition, which matches a whole text alone.
        first = _first_line(value)
        return sorted({item_id for item_id, pattern in patterns if pattern.search(screen) or pattern.fullmatch(first)})
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
            try:
                if path.is_symlink() or not path.is_file():
                    continue
                raw = load_yaml(path, max_bytes=EVIDENCE_YAML_MAX_BYTES)
            except (OSError, YAMLLoadError):
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


def _execution_pairs(records: Iterable[Mapping[str, Any]]) -> list[tuple[str, str]]:
    """One ``(execution_id, evidence_path)`` pair per deciding record, in the
    records' order: a record with neither value is left out, the value a
    record lacks is ``''`` so the two columns stay aligned, and only an exact
    repeat of an earlier pair is dropped. Execution ids are never deduplicated
    on their own, because imported UniKix records share one execution id
    across scenarios, each with its own evidence path."""
    pairs: list[tuple[str, str]] = []
    seen: set[tuple[str, str]] = set()
    for record in records:
        execution_id, path = record.get("execution_id"), record.get("evidence_path")
        if not execution_id and not path:
            continue
        pair = (str(execution_id) if execution_id else "", str(path) if path else "")
        if pair not in seen:
            seen.add(pair)
            pairs.append(pair)
    return pairs


def render_matrix(state: State) -> str:
    """``coverage-matrix.csv``: one row per inventory item in id order. The
    ``execution_id`` and ``evidence_path`` columns are paired record by
    record in scenario order (the row's ``scenario_ids``, then each
    scenario's deciding records by adapter and execution id), so the i-th
    execution id is the one recorded in the i-th evidence path."""
    rows: list[Sequence[Any]] = [MATRIX_HEADER]
    for item in state.items:
        scenario_ids = state.item_scenarios[item["id"]]
        assertions = ";".join(
            f"{scenario_id}:{','.join(_scenarios.asserted_keys(state.scenarios[scenario_id]))}"
            for scenario_id in scenario_ids
        )
        pairs = _execution_pairs(_deciding(state, scenario_ids))
        rows.append([
            item["id"], item.get("source"), ";".join(scenario_ids), assertions, state.item_status[item["id"]],
            ";".join(execution_id for execution_id, _ in pairs), ";".join(path for _, path in pairs),
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


def _page_zero_facts() -> tuple[str, str | None, int | None, str | None]:
    """The page-zero probe of the runtime environment file as stable facts,
    ``(outcome, errno, minimum, note)``.

    - ``outcome``: succeeded, failed or not run from the probe's ``ok``, a
      probe that is not a mapping counting as a bare detail; not recorded
      when the file holds no probe or cannot be read, and
      ``not recorded (no <file>)`` when there is no file.
    - ``errno``: ``errno <n> <NAME>`` from the probe's integer ``errno``,
      else from the ``errno=<n>`` of its detail; ``None`` when neither has it.
    - ``minimum``: the integer ``vm_mmap_min_addr``, else the
      ``vm.mmap_min_addr=<n>`` of the detail; ``None`` when neither has it.
    - ``note``: where the diagnostic lies, or that the file cannot be read;
      ``None`` when there is neither.

    The raw detail and a read error can hold work paths, dates and
    durations, so neither is copied: the file is named by its path relative
    to the repository."""
    path = display_path(_results.RUNTIME_DIR / _results.ENVIRONMENT_FILE)
    try:
        environment = _results.load_environment()
    except HarnessError:
        return "not recorded", None, None, f"{path} cannot be read"
    if environment is None:
        return f"not recorded (no {path})", None, None, None
    probe = environment.get("page0_probe")
    number = None
    if isinstance(probe, Mapping):
        ok = probe.get("ok")
        outcome = "succeeded" if ok is True else "failed" if ok is False else "not run"
        number = probe.get("errno")
        detail = str(probe.get("detail") or "").strip()
    else:
        outcome = "not recorded" if probe is None else "not run"
        detail = "" if probe is None else str(probe).strip()
    if isinstance(number, bool) or not isinstance(number, int):
        match = re.search(r"errno=(\d+)", detail)
        number = int(match.group(1)) if match else None
    code = None
    if number is not None:
        name = errno.errorcode.get(number)
        code = f"errno {number} {name}" if name else f"errno {number}"
    minimum = environment.get("vm_mmap_min_addr")
    if isinstance(minimum, bool) or not isinstance(minimum, int):
        match = re.search(r"vm\.mmap_min_addr=(\d+)", detail)
        minimum = int(match.group(1)) if match else None
    return outcome, code, minimum, f"diagnostic in {path}" if detail else None


def _selected_adapter() -> str:
    """The adapter ``CARDDEMO_ADAPTER`` selects, by the rule of
    ``tests/conftest.py``: gnucobol-batch when the variable is unset, else
    its value when that names an adapter. Any other value, the empty one
    included, raises ``ReportError``, whose message shows it clipped, so no
    report names an adapter that does not exist or copies environment text."""
    value = os.environ.get(_ADAPTER_ENV)
    if value is None:
        return _results.GNUCOBOL
    if value not in MARKER_FOR_ADAPTER:
        allowed = " or ".join(f"'{name}'" for name in MARKER_FOR_ADAPTER)
        raise ReportError(f"{_ADAPTER_ENV} must be {allowed} (got {_clip(value, _ADAPTER_CLIP)!r})")
    return value


def _environment_lines() -> list[str]:
    """The Environment section: the adapter ``_selected_adapter`` names, tool
    and package versions and the page-zero probe, never a date, a duration or
    a path. The probe shows its outcome, errno and vm.mmap_min_addr as
    ``_page_zero_facts`` derives them, never the runtime file's raw text,
    which it names by its relative path."""
    lines = [f"- Adapter: {_selected_adapter()}"]
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
    outcome, code, minimum, note = _page_zero_facts()
    facts = [fact for fact in (code, note) if fact]
    lines.append(f"- Page-zero probe: {outcome}" + (f" ({'; '.join(facts)})" if facts else ""))
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
    recordless = [
        scenario_id for scenario_id, status in state.statuses.items()
        if status.status == _results.STATUS_BLOCKED and not status.counted and not status.stale
        and _results.GNUCOBOL in _scenarios.capable_adapters(state.scenarios[scenario_id])
    ]
    # A record that exists but cannot be read, or is refused as a symbolic
    # link, is not a scenario this session did not run.
    unread = [scenario_id for scenario_id in recordless if scenario_id in state.unread_records]
    unrun = [scenario_id for scenario_id in recordless if scenario_id not in state.unread_records]
    if unrun:
        causes.append(f"{len(unrun)} scenarios gnucobol-batch can run have no result record (not run in this "
                      f"session); no capable adapter ran them, so they count as blocked under {GAP_NOT_RUN}.")
    if unread:
        causes.append(f"{len(unread)} scenarios gnucobol-batch can run have a result record that cannot be read "
                      "or is refused as a symbolic link (python tests/harness/report.py --check names each "
                      f"problem): " + ", ".join(unread[:10]) + (" ..." if len(unread) > 10 else "")
                      + f"; with no record read they count as blocked under {GAP_NOT_RUN}.")
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
    planned = sum(PLANNING_PROJECTION.values())
    if state.total != planned:
        causes.append(f"The frozen inventory total is {state.total}, not the {planned} of the planning freeze "
                      "(see Inventory completeness).")
    if len(state.items) != state.total:
        causes.append(f"The inventory holds {len(state.items)} items, not its frozen total {state.total}; "
                      "python tests/harness/inventory_tools.py --check reports the drift.")
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


def _seed_selects(selectors: Sequence[str], scenario_id: str) -> bool:
    return any(scenario_id == selector or scenario_id.startswith(f"{selector}-") for selector in selectors)


def _seed_of_area() -> dict[str, str]:
    shown: dict[str, str] = {}
    for label, count, selectors, batch_only in SEED_COUNTS:
        if len(selectors) == 1 and "-" not in selectors[0]:
            text = f"{count}"
        else:
            qualifier = "batch scenarios" if batch_only else f"{' and '.join(selectors)} scenarios"
            text = f"{label} {count} (shared; {qualifier})"
        for area in dict.fromkeys(selector.split("-", 1)[0] for selector in selectors):
            shown[area] = f"{shown[area]}; {text}" if area in shown else text
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
    for label, count, selectors, batch_only in SEED_COUNTS:
        suite = sum(
            1 for scenario in state.scenarios.values()
            if _seed_selects(selectors, scenario.id) and not (batch_only and scenario.level == "e2e")
        )
        where = ", ".join(selectors) + (" (batch scenarios only)" if batch_only else "")
        rows.append([label, count, where, suite])
    rows.append(["Total", SEED_TOTAL, "all areas", len(state.scenarios)])
    return rows


def render_report(state: State, proof: tuple[bool, str] | None = None) -> str:
    """``coverage-report.md``: the sections of the coverage report, in order.
    Verified items are divided by the frozen total (``State.total``), and the
    100% claim needs that many verified, status counts adding up to it and no
    open register entry, so an inventory that lists fewer or more items than
    its header can neither re-base the figure nor claim 100%."""
    proof = proof if proof is not None else check_proof(state)
    counts = state.counts()
    total = state.total
    verified = counts[VERIFIED]
    counted = sum(counts.values())
    listed = len(state.items)
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
    if listed != total:
        lines += [f"The inventory header gives a total of {total}, but the inventory holds {listed} items.", ""]
    lines += [
        "### Against the planning projection",
        "",
        "The plan projected the CI figures with every scenario present, every scenario gnucobol-batch can run "
        "recorded, the page-zero probe succeeding and no UniKix evidence imported, and gave the figures for a "
        "failing probe beside them. Differences are explained against the first projection. This report governs.",
        "",
        *_table(("Status", "This report", "Planning projection", "Projection, page-zero probe failing"),
                [[status, counts[status], PLANNING_PROJECTION[status], PLANNING_PROJECTION_NO_PAGE_ZERO[status]]
                 for status in ITEM_STATUSES]
                + [["total", total, sum(PLANNING_PROJECTION.values()),
                    sum(PLANNING_PROJECTION_NO_PAGE_ZERO.values())]]),
        "",
    ]
    for label, projection in (("Planning projection", PLANNING_PROJECTION),
                              ("Projection with the page-zero probe failing", PLANNING_PROJECTION_NO_PAGE_ZERO)):
        projected_total = sum(projection.values())
        lines.append(f"- {label}: verified / total {projection[VERIFIED]} / {projected_total} = "
                     f"{_percent(projection[VERIFIED], projected_total)}")
    lines.append("")
    if all(counts[status] == PLANNING_PROJECTION[status] for status in ITEM_STATUSES):
        lines += ["The counts equal the planning projection.", ""]
    else:
        causes = _projection_causes(state) or ["The records and scenarios as they stand give these statuses."]
        intro = (
            "The counts equal the projection with the page-zero probe failing and differ from the planning "
            "projection:"
            if all(counts[status] == PLANNING_PROJECTION_NO_PAGE_ZERO[status] for status in ITEM_STATUSES)
            else "The counts differ from the projection:"
        )
        lines += [intro, "", *(f"- {cause}" for cause in causes), ""]
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
    claim = verified == total and counted == total and total > 0 and not open_gaps
    lines += [
        *_table(("Category", "open", "closed", "total"), register_rows),
        "",
        "## 100% claim",
        "",
        # The claim stands alone on its line, exactly 'yes' or 'no', so a reader
        # or a script can match it literally; the reason follows it.
        f"100% may be claimed: {'yes' if claim else 'no'}",
        "",
        f"{verified} of {total} items are verified and {len(open_gaps)} register entries are open; the claim "
        "needs every item verified and no open entry.",
        "",
    ]
    if counted != total:
        lines += [f"The status counts add up to {counted}, not the frozen total {total}, so the claim cannot be "
                  "made until the inventory and its header agree.", ""]
    lines += [
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
            f"- {change.get('date')} {change.get('inventory_id') or change.get('gap_id')}: "
            f"{_cell(change.get('reason'))}"
            if isinstance(change, Mapping) else f"- {_cell(change)}"
            for change in changes
        ]
    else:
        lines.append(f"- none; the frozen total {total} stands")
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


def _refuse_unreportable(state: State) -> None:
    """Raise ``ReportError`` when the reports cannot be regenerated from
    ``state`` truthfully: the inventory lists another number of items than
    its frozen total (drift, which ``inventory_tools.py --check`` explains),
    or a gnucobol-batch result record cannot be read, breaks the record
    contract or is refused as a symbolic link (``State.unread_records``),
    which would otherwise count as a scenario that never ran. Every reason
    is named, each unread record by its file or the link refused. The
    regeneration, the import and the revalidation call it before they write
    anything."""
    reasons = []
    listed = len(state.items)
    if listed != state.total:
        reasons.append(f"{display_path(_inventory.INVENTORY_FILE)} header.total is {state.total} but the "
                       f"inventory holds {listed} items; run python tests/harness/inventory_tools.py --check")
    reasons.extend(dict.fromkeys(state.unread_records.values()))
    if reasons:
        raise ReportError("the reports are not regenerated, and nothing is written: " + "; ".join(reasons))


def regenerate() -> State:
    """Rewrite the coverage matrix, the gap register and the coverage report,
    and the ``scenario_ids``, ``status`` and ``gap_ids`` of every inventory
    item. Unchanged files are left untouched. The three texts are rendered
    before anything is written, so one that cannot be rendered (an invalid
    ``CARDDEMO_ADAPTER``) leaves every file as it was, and so do an
    inventory whose item count differs from its frozen total and a
    gnucobol-batch result record that cannot be read, breaks the record
    contract or is a symbolic link, which ``_refuse_unreportable`` refuses
    with ``ReportError`` naming each. Returns the state reported."""
    _refuse_links(REPORTS_DIR.parent, REPORTS_DIR, MATRIX_FILE, REGISTER_FILE, REPORT_FILE)
    state = build_state()
    _refuse_unreportable(state)
    texts = render(state)
    _write_inventory(state)
    for path, text in texts.items():
        write_text(path, text)
    return state


# --------------------------------------------------------------------------
# Checks
# --------------------------------------------------------------------------


def _page_zero_detail() -> str:
    """What the job-chain proof's page-zero reason adds: `` (<facts>)``,
    the errno, the vm.mmap_min_addr value and the note on the runtime file
    as ``_page_zero_facts`` derives them, joined by ``, ``, or ``""`` when
    none is recorded. Never the runtime file's raw text. The proof joins
    its reasons by ``; ``, so the facts never use that separator."""
    _, code, minimum, note = _page_zero_facts()
    shown = f"vm.mmap_min_addr {minimum}" if minimum is not None else None
    facts = [fact for fact in (code, shown, note) if fact]
    return f" ({', '.join(facts)})" if facts else ""


def _job_item(state: State, job: str) -> str | None:
    for item in state.items:
        if item.get("kind") == "job" and (str(item.get("name") or "").split() or [""])[0] == job:
            return item["id"]
    return None


def _proof_scenario_problems(scenario: Any) -> list[str]:
    """Why a proof scenario's file is not the proof's: ``inputs.job`` is not
    exactly its ``PROOF_JOB_SEQUENCES`` list or a program or steps come with
    it; its precondition datasets are not exactly ``PROOF_FIXTURES``, each a
    bare ``{base: <fixture>}`` whose fixture has a repository source
    (``PROOF_FIXTURE_SOURCES`` where one is named); it declares faults; or
    its clock, clock mode or parameters depart from ``DEFAULT_CLOCK``,
    ``PROOF_CLOCK_MODES`` and ``PROOF_PARAMETERS``."""
    scenario_id = scenario.id
    problems: list[str] = []
    jobs = PROOF_JOB_SEQUENCES[scenario_id]
    if scenario.inputs.get("job") != list(jobs):
        shown = " -> ".join(_scenarios.job_names(scenario)) or "no job list"
        problems.append(f"{scenario_id} runs {shown}, not the jobs {' -> '.join(jobs)}")
    others = [key for key in ("program", "steps") if key in scenario.inputs]
    if others:
        problems.append(f"{scenario_id} also has inputs.{' and inputs.'.join(others)}, but the proof runs its "
                        "jobs alone")
    datasets = scenario.preconditions.get("datasets")
    datasets = datasets if isinstance(datasets, Mapping) else {}
    missing = [name for name in PROOF_FIXTURES if name not in datasets]
    if missing:
        problems.append(f"{scenario_id} lacks the precondition dataset(s) {', '.join(missing)}")
    extra = sorted(str(name) for name in datasets if name not in PROOF_FIXTURES)
    if extra:
        problems.append(f"{scenario_id} has the precondition dataset(s) {', '.join(extra)}, which the proof "
                        "does not use")
    for name, base in PROOF_FIXTURES.items():
        if name not in datasets:
            continue
        entry = datasets[name]
        if entry != {"base": base}:
            if isinstance(entry, Mapping):
                held = f"base {entry.get('base')!r} with the key(s) {', '.join(sorted(map(str, entry)))}"
            else:
                held = f"a {type(entry).__name__}"
            problems.append(f"{scenario_id} precondition dataset {name} is {held}, not {{base: {base}}} alone")
            continue
        try:
            source = _fixtures.spec(base).source
        except HarnessError:
            problems.append(f"{scenario_id} precondition dataset {name}: {base} is not a fixture dataset")
            continue
        wanted = PROOF_FIXTURE_SOURCES.get(name)
        if source is None:
            problems.append(f"{scenario_id} precondition dataset {name}: {base} has no repository source")
        elif wanted is not None and source != wanted:
            problems.append(f"{scenario_id} precondition dataset {name} comes from {source}, not {wanted}")
    if scenario.preconditions.get("faults"):
        problems.append(f"{scenario_id} declares precondition faults, but the proof runs without any")
    clock = _scenarios.clock(scenario)
    if clock != DEFAULT_CLOCK:
        problems.append(f"{scenario_id} runs at the clock {clock!r}, not the default clock {DEFAULT_CLOCK!r}")
    mode = _scenarios.clock_mode(scenario)
    modes = PROOF_CLOCK_MODES[scenario_id]
    if mode not in modes:
        problems.append(f"{scenario_id} declares clock_mode {mode!r}, not {' or '.join(modes)}")
    parameters = scenario.preconditions.get("parameters")
    parameters = parameters if isinstance(parameters, Mapping) else {}
    for key, value in PROOF_PARAMETERS[scenario_id].items():
        if parameters.get(key) != value:
            problems.append(f"{scenario_id} has {key} {_clip(repr(parameters.get(key)), 80)}, not {value!r}")
    return problems


def _proof_baseline_problems(scenario_id: str) -> list[str]:
    """Why the proof scenario's baseline is not the proof's: it cannot be
    read, is absent or was not observed on gnucobol-batch, the authoritative
    baseline of a job chain both adapters run; or its expectation does not
    pin the proof's values (``_proof_expectation_problems``). Empty when it
    is the proof's."""
    baseline, problem = _load_baseline(scenario_id)
    if problem is not None:
        return [f"{scenario_id} has a baseline that cannot be read, and the proof needs one observed on gnucobol-batch"]
    if baseline is None:
        return [f"{scenario_id} has no baseline, and the proof needs one observed on gnucobol-batch"]
    problems: list[str] = []
    captured = baseline.capture_platform
    if captured != _results.GNUCOBOL:
        problems.append(f"{scenario_id} has a {captured} baseline, not one observed on gnucobol-batch")
    problems.extend(_proof_expectation_problems(scenario_id, getattr(baseline, "expected", None)))
    return problems


def _proof_expected_list(value: Any) -> list | None:
    """The full list an expected value pins: the value itself when it is a
    plain list, the ``records`` of a mapping, else ``None`` (a ``count``, a
    ``contains`` subsequence or ``matchers`` alone pin no full list)."""
    if isinstance(value, list):
        return value
    if isinstance(value, Mapping) and isinstance(value.get("records"), list):
        return value["records"]
    return None


def _proof_expected_shown(value: Any) -> str:
    """What an expected value that pins no full list holds, as a reason
    names it: ``a mapping of contains and count`` or ``a str``."""
    if isinstance(value, Mapping):
        keys = sorted(str(key) for key in value)
        return f"a mapping of {' and '.join(keys)}" if keys else "an empty mapping"
    return f"a {type(value).__name__}"


def _proof_expectation_problems(scenario_id: str, expected: Any) -> list[str]:
    """Why a proof baseline's expectation (``baseline.expected``, against
    which ``compare`` checks every key the scenario asserts, so a passed run
    observed it) does not pin the proof's values: ``return_code``
    ``PROOF_RETURN_CODE``; ``reject_reason_codes``, as four-digit texts
    (``102`` is ``'0102'``), exactly ``PROOF_REJECT_COUNT`` of
    ``PROOF_REJECT_CODE``; ``messages`` the complete list of lines an
    observed baseline holds; every dataset the scenario must assert
    (``PROOF_REQUIREMENTS``, under ``datasets`` or ``file_state``) and every
    reserved observation it must assert under ``file_state``
    (``PROOF_OBSERVATIONS``: the stage observations, and the chain's
    ``@CATALOG``) its full record list, a plain list or a mapping with
    ``records``; and ACCTDATA's list and the accounts' after POSTTRAN
    (``PROOF_POSTED_ACCOUNTS``) all 50 accounts each, every one naming
    ``PROOF_ACCOUNT_FIELDS``. Each key the expectation lacks or leaves
    unpinned is one reason naming it."""
    if not isinstance(expected, Mapping):
        return [f"{scenario_id}: its baseline holds no expectation, and the proof needs one that pins its "
                "return code, reject reasons, messages and datasets"]
    problems: list[str] = []
    if "return_code" not in expected:
        problems.append(f"{scenario_id}: its baseline does not expect return_code")
    else:
        code = expected["return_code"]
        if isinstance(code, bool) or code != PROOF_RETURN_CODE:
            problems.append(f"{scenario_id}: its baseline expects return_code {_clip(repr(code), 40)}, "
                            f"not {PROOF_RETURN_CODE}")
    if "reject_reason_codes" not in expected:
        problems.append(f"{scenario_id}: its baseline does not expect reject_reason_codes")
    else:
        value = expected["reject_reason_codes"]
        codes = _proof_expected_list(value)
        if codes is None:
            problems.append(f"{scenario_id}: its baseline expects reject_reason_codes as "
                            f"{_proof_expected_shown(value)}, not the full list of {PROOF_REJECT_COUNT} of "
                            f"{PROOF_REJECT_CODE}")
        else:
            texts = [f"{code:04d}" if isinstance(code, int) and not isinstance(code, bool) and 0 <= code <= 9999
                     else code for code in codes]
            if texts != [PROOF_REJECT_CODE] * PROOF_REJECT_COUNT:
                problems.append(f"{scenario_id}: its baseline expects the reject_reason_codes "
                                f"{_proof_code_counts(texts)}, not {PROOF_REJECT_COUNT} of {PROOF_REJECT_CODE}")
    if "messages" not in expected:
        problems.append(f"{scenario_id}: its baseline does not expect messages")
    elif not isinstance(expected["messages"], list):
        problems.append(f"{scenario_id}: its baseline expects messages as "
                        f"{_proof_expected_shown(expected['messages'])}, not the complete list of lines")
    _, datasets = PROOF_REQUIREMENTS[scenario_id]
    wanted_in = [(name, ("datasets", "file_state")) for name in datasets]
    wanted_in += [(name, ("file_state",)) for name in PROOF_OBSERVATIONS[scenario_id]]
    for name, keys in wanted_in:
        sections = [expected.get(section) for section in keys]
        held = [section for section in sections if isinstance(section, Mapping) and name in section]
        if not held:
            problems.append(f"{scenario_id}: its baseline does not expect {name}")
            continue
        value = held[0][name]
        records = _proof_expected_list(value)
        if records is None:
            problems.append(f"{scenario_id}: its baseline expects {name} as {_proof_expected_shown(value)}, "
                            "not its full record list")
            continue
        if name not in ("ACCTDATA", PROOF_POSTED_ACCOUNTS):
            continue
        wanted = PROOF_RECORD_COUNTS[scenario_id][name]
        if len(records) != wanted:
            problems.append(f"{scenario_id}: its baseline expects {len(records)} {name} record(s), "
                            f"not the {wanted} accounts")
        for field_name in PROOF_ACCOUNT_FIELDS:
            lacking = sum(1 for record in records if not isinstance(record, Mapping) or field_name not in record)
            if lacking:
                problems.append(f"{scenario_id}: {lacking} {name} record(s) of its baseline do not name "
                                f"{field_name}, which the proof asserts for every account")
    return problems


def _proof_step_tables() -> dict[str, tuple[str, ...]]:
    """The step names, in JCL order, of each proof job's gnucobol-batch step
    table; a job whose table cannot be read is left out."""
    try:
        document = load_yaml(_scenarios.GNUCOBOL_JOBS_FILE, missing_ok=True)
    except (HarnessError, OSError):
        return {}
    jobs = document.get("jobs") if isinstance(document, Mapping) else None
    tables: dict[str, tuple[str, ...]] = {}
    for job in PROOF_JOB_RETURN_CODES:
        entry = jobs.get(job) if isinstance(jobs, Mapping) else None
        steps = entry.get("steps") if isinstance(entry, Mapping) else None
        if not isinstance(steps, list) or not steps:
            continue
        names = tuple(step.get("step") if isinstance(step, Mapping) else None for step in steps)
        if all(isinstance(name, str) and name for name in names):
            tables[job] = names
    return tables


def _proof_step_problem(scenario_id: str, lines: Sequence[str], tables: Mapping[str, tuple[str, ...]]) -> str | None:
    """Why the step lines among ``lines`` are not, job after job of the
    scenario's sequence, each job's steps in table order without an abend
    and with the job's return code as the highest; ``None`` when they are."""
    steps = [match for match in map(PROOF_STEP_LINE_RE.fullmatch, lines) if match is not None]
    jobs = PROOF_JOB_SEQUENCES[scenario_id]
    position = 0
    for job in jobs:
        names = tables.get(job)
        if names is None:
            return f"{scenario_id}: the gnucobol-batch step table of {job} cannot be read"
        found = steps[position:position + len(names)]
        position += len(names)
        wanted = PROOF_JOB_RETURN_CODES[job]
        codes = [match.group(2) for match in found]
        if [match.group(1) for match in found] != list(names) or None in codes or max(map(int, codes)) != wanted:
            shown = ", ".join(match.group(0) for match in found) or "no step line"
            return (f"{scenario_id}: job {job} must report its steps {', '.join(names)} without an abend and "
                    f"with RC {wanted:04d} as the highest, but its messages show {shown}")
    if position < len(steps):
        return f"{scenario_id}: its messages hold {len(steps) - position} step line(s) after the job {jobs[-1]}"
    return None


def _proof_records(decoded: Mapping[str, Any], name: str) -> tuple[list | None, str]:
    """Dataset ``name`` of a decoded outcome, looked up under ``datasets``
    and then ``file_state``: its records, or ``None`` and why there are none."""
    for section in ("datasets", "file_state"):
        values = decoded.get(section)
        if isinstance(values, Mapping) and name in values:
            value = values[name]
            if isinstance(value, list):
                return value, ""
            if isinstance(value, Mapping) and _baselines.MISSING in value:
                return None, "was not produced"
            if isinstance(value, Mapping) and _baselines.ERROR in value:
                return None, "cannot be decoded"
            return None, "holds no records"
    return None, "is not in the decoded outcome"


def _proof_field(records: Sequence[Any], name: str) -> list:
    return [record.get(name) if isinstance(record, Mapping) else None for record in records]


def _proof_code_counts(codes: Any) -> str:
    if isinstance(codes, list):
        counts = Counter(str(code) for code in codes)
        return ", ".join(f"{count} of {code}" for code, count in sorted(counts.items())) or "none"
    if codes is None:
        return "absent"
    return "unreadable"


def _proof_outcome_problems(
    scenario_id: str, decoded: Mapping[str, Any], tables: Mapping[str, tuple[str, ...]],
) -> list[str]:
    """Why the decoded outcome of a proof scenario's passed gnucobol-batch
    record departs from the proof's business values: the return code; the
    message counts, the TCATBAL creation lines and the step lines of each
    job; the reject reasons; the record counts (``PROOF_RECORD_COUNTS``) and
    fields of DALYREJS, TRANSACT, TCATBALF, SYSTRAN, the accounts after
    POSTTRAN (50) and, for the chain, TRXFL, TRANSACT-BKUP, TRANBKP's backup
    (262) and the TRANSACT it emptied (0); ACCTDATA's cycle fields
    (``_proof_account_problems``); the accounts after POSTTRAN against the
    final ACCTDATA (``_proof_posted_account_problems``); and, for the chain,
    the catalog's backup generations, TRANBKP's backup, TRXFL's keys and
    padding, the statements and the report (``_proof_chain_problems``).
    Every dataset and observation the scenario must assert has to be in the
    outcome."""
    problems: list[str] = []
    return_code = decoded.get("return_code")
    if isinstance(return_code, bool) or return_code != PROOF_RETURN_CODE:
        problems.append(f"{scenario_id} has return code {_clip(repr(return_code), 40)}, not {PROOF_RETURN_CODE}")
    messages = decoded.get("messages")
    if not isinstance(messages, list):
        problems.append(f"{scenario_id}: its gnucobol-batch record holds no messages")
    else:
        lines = [line.rstrip() for line in messages if isinstance(line, str)]
        for text, wanted in PROOF_MESSAGE_COUNTS:
            seen = lines.count(text)
            if seen != wanted:
                problems.append(f"{scenario_id}: '{text}' appears {seen} time(s) in its messages, not {wanted}")
        prefix, suffix = PROOF_TCATBAL_CREATED
        seen = sum(1 for line in lines if line.startswith(prefix) and line.endswith(suffix))
        if seen != PROOF_TCATBAL_ADDED:
            problems.append(f"{scenario_id}: its messages hold {seen} TCATBAL creation line(s), "
                            f"not {PROOF_TCATBAL_ADDED}")
        step_problem = _proof_step_problem(scenario_id, lines, tables)
        if step_problem is not None:
            problems.append(step_problem)
    codes = decoded.get("reject_reason_codes")
    if codes != [PROOF_REJECT_CODE] * PROOF_REJECT_COUNT:
        problems.append(f"{scenario_id}: its reject reason codes are {_proof_code_counts(codes)}, "
                        f"not {PROOF_REJECT_COUNT} of {PROOF_REJECT_CODE}")
    counts = dict(PROOF_RECORD_COUNTS[scenario_id])
    try:
        fixture_count = _fixtures.spec(PROOF_FIXTURES["TCATBALF"]).record_count
    except HarnessError:
        fixture_count = None
    if fixture_count is None:
        problems.append(f"{scenario_id}: the TCATBALF fixture has no record count to add the "
                        f"{PROOF_TCATBAL_ADDED} created records to")
    else:
        counts["TCATBALF"] = fixture_count + PROOF_TCATBAL_ADDED
    found: dict[str, list] = {}
    for name, wanted in counts.items():
        records, why = _proof_records(decoded, name)
        if records is None:
            problems.append(f"{scenario_id}: {name} {why}")
            continue
        found[name] = records
        if len(records) != wanted:
            problems.append(f"{scenario_id}: {name} holds {len(records)} record(s), not {wanted}")
    rejects = found.get("DALYREJS")
    if rejects is not None:
        texts = _proof_field(rejects, "WS-VALIDATION-FAIL-REASON-DESC")
        wrong = sum(1 for text in texts if not isinstance(text, str) or text.rstrip() != PROOF_REJECT_TEXT)
        if wrong:
            problems.append(f"{scenario_id}: {wrong} DALYREJS record(s) lack the reason text '{PROOF_REJECT_TEXT}'")
    transact = found.get("TRANSACT")
    if transact is not None:
        ids = _proof_field(transact, "TRAN-ID")
        if not all(isinstance(key, str) for key in ids) or any(left >= right for left, right in zip(ids, ids[1:])):
            problems.append(f"{scenario_id}: TRANSACT is not in ascending TRAN-ID order")
        wrong = sum(1 for stamp in _proof_field(transact, "TRAN-PROC-TS") if stamp not in PROOF_PROC_TS)
        if wrong:
            problems.append(f"{scenario_id}: {wrong} TRANSACT record(s) have a TRAN-PROC-TS other than "
                            f"{PROOF_PROC_TS[0]}")
    interest = found.get("SYSTRAN")
    if interest is not None and len(interest) == len(PROOF_INTEREST_IDS):
        for number, (seen_id, wanted_id) in enumerate(zip(_proof_field(interest, "TRAN-ID"), PROOF_INTEREST_IDS), 1):
            if seen_id != wanted_id:
                problems.append(f"{scenario_id}: SYSTRAN record {number} has TRAN-ID {_clip(repr(seen_id), 40)}, "
                                f"not {wanted_id} ({PROOF_INTEREST_IDS[0]} to {PROOF_INTEREST_IDS[-1]} in order)")
                break
    _, required = PROOF_REQUIREMENTS[scenario_id]
    for name in required + PROOF_OBSERVATIONS[scenario_id]:
        if name in counts:
            continue
        records, why = _proof_records(decoded, name)
        if records is None:
            problems.append(f"{scenario_id}: {name} {why}")
        else:
            found[name] = records
    accounts = found.get("ACCTDATA")
    if accounts is not None:
        problems.extend(_proof_account_problems(scenario_id, accounts))
    posted = found.get(PROOF_POSTED_ACCOUNTS)
    if posted is not None:
        problems.extend(_proof_posted_account_problems(scenario_id, posted, accounts))
    problems.extend(_proof_chain_problems(scenario_id, found))
    return problems


def _proof_account_problems(scenario_id: str, accounts: Sequence[Any]) -> list[str]:
    """Why ACCTDATA's decoded accounts do not show INTCALC's update: every
    account 1 to 49 (``PROOF_UPDATED_ACCOUNTS``, by ``int(ACCT-ID)``) present
    with ``ACCT-CURR-CYC-CREDIT`` and ``ACCT-CURR-CYC-DEBIT`` both zero, and
    account 50 (``PROOF_UNUPDATED_ACCOUNT``) present without both zero. A
    value that is not decoded number text fails."""
    # CBACT04C 1050-UPDATE-ACCOUNT moves 0 to both cycle fields of each
    # account it rewrites [app/cbl/CBACT04C.cbl:350-356]. It rewrites an
    # account when the next account's TCATBAL record arrives, and the rewrite
    # meant for the last account is unreachable [app/cbl/CBACT04C.cbl:219-220]
    # (GAP-DEF-003), so accounts 1 to 49 end with zero cycle fields and
    # account 50 keeps what POSTTRAN posted: its six dailytran.txt
    # transactions, all under its 6169.00 limit, add 1501.75 to the cycle
    # credit and -47.88 to the cycle debit.
    cycles: dict[int, tuple[Decimal, Decimal]] = {}
    seen: set[int] = set()
    unreadable = 0
    for record in accounts:
        fields = record if isinstance(record, Mapping) else {}
        account_id = fields.get("ACCT-ID")
        readable_id = isinstance(account_id, str) and account_id.isascii() and account_id.isdigit()
        number = int(account_id) if readable_id else None
        if number is not None:
            seen.add(number)
        cycle = [fields.get(name) for name in ("ACCT-CURR-CYC-CREDIT", "ACCT-CURR-CYC-DEBIT")]
        if number is None or not all(isinstance(value, str) and PROOF_NUMBER_RE.fullmatch(value) for value in cycle):
            unreadable += 1
            continue
        cycles[number] = (Decimal(cycle[0]), Decimal(cycle[1]))
    problems: list[str] = []
    if unreadable:
        problems.append(f"{scenario_id}: {unreadable} ACCTDATA record(s) have an ACCT-ID, ACCT-CURR-CYC-CREDIT or "
                        "ACCT-CURR-CYC-DEBIT that is not decoded number text")
    wanted = PROOF_UPDATED_ACCOUNTS + (PROOF_UNUPDATED_ACCOUNT,)
    missing = [str(number) for number in wanted if number not in seen]
    if missing:
        problems.append(f"{scenario_id}: ACCTDATA lacks the account(s) {_clip(', '.join(missing), 200)}")
    kept = [str(number) for number in PROOF_UPDATED_ACCOUNTS if number in cycles and any(cycles[number])]
    if kept:
        problems.append(f"{scenario_id}: ACCTDATA account(s) {_clip(', '.join(kept), 200)} keep a non-zero "
                        f"ACCT-CURR-CYC-CREDIT or ACCT-CURR-CYC-DEBIT, but INTCALC zeroes both for accounts "
                        f"{PROOF_UPDATED_ACCOUNTS[0]} to {PROOF_UPDATED_ACCOUNTS[-1]}")
    last = cycles.get(PROOF_UNUPDATED_ACCOUNT)
    if last is not None and not any(last):
        problems.append(f"{scenario_id}: ACCTDATA account {PROOF_UNUPDATED_ACCOUNT} has zero ACCT-CURR-CYC-CREDIT "
                        "and ACCT-CURR-CYC-DEBIT, but INTCALC never rewrites it, so it keeps POSTTRAN's postings "
                        "(GAP-DEF-003)")
    return problems


def _proof_posted_account_problems(
    scenario_id: str, posted: Sequence[Any], final: Sequence[Any] | None
) -> list[str]:
    """Why the decoded accounts at the end of POSTTRAN
    (``PROOF_POSTED_ACCOUNTS``) do not show its postings: every account 1 to
    50 (by ``int(ACCT-ID)``) present with ``ACCT-CURR-CYC-CREDIT`` or
    ``ACCT-CURR-CYC-DEBIT`` non-zero, and account 50
    (``PROOF_UNUPDATED_ACCOUNT``) holding the ``PROOF_ACCOUNT_FIELDS`` values
    of its record in the final ACCTDATA (``final``; ``None`` when the outcome
    lacks it, which the caller reports). A value that is not decoded number
    text fails."""
    # The repository accounts start with zero cycle totals, and CBTRN02C adds
    # every posted amount to ACCT-CURR-CYC-CREDIT (amount >= 0) or
    # ACCT-CURR-CYC-DEBIT [app/cbl/CBTRN02C.cbl:545-554]; each account has
    # postings in dailytran.txt, so after POSTTRAN none has both zero, which
    # INTCALC then zeroes for accounts 1 to 49. INTCALC never rewrites account
    # 50 (GAP-DEF-003), so its final record keeps these values.
    cycles: dict[int, tuple[Decimal, Decimal]] = {}
    records: dict[int, Mapping[str, Any]] = {}
    unreadable = 0
    for record in posted:
        fields = record if isinstance(record, Mapping) else {}
        account_id = fields.get("ACCT-ID")
        readable_id = isinstance(account_id, str) and account_id.isascii() and account_id.isdigit()
        number = int(account_id) if readable_id else None
        if number is not None:
            records[number] = fields
        cycle = [fields.get(name) for name in ("ACCT-CURR-CYC-CREDIT", "ACCT-CURR-CYC-DEBIT")]
        if number is None or not all(isinstance(value, str) and PROOF_NUMBER_RE.fullmatch(value) for value in cycle):
            unreadable += 1
            continue
        cycles[number] = (Decimal(cycle[0]), Decimal(cycle[1]))
    problems: list[str] = []
    if unreadable:
        problems.append(f"{scenario_id}: {unreadable} {PROOF_POSTED_ACCOUNTS} record(s) have an ACCT-ID, "
                        "ACCT-CURR-CYC-CREDIT or ACCT-CURR-CYC-DEBIT that is not decoded number text")
    wanted = PROOF_UPDATED_ACCOUNTS + (PROOF_UNUPDATED_ACCOUNT,)
    missing = [str(number) for number in wanted if number not in records]
    if missing:
        problems.append(f"{scenario_id}: {PROOF_POSTED_ACCOUNTS} lacks the account(s) {_clip(', '.join(missing), 200)}")
    unposted = [str(number) for number in wanted if number in cycles and not any(cycles[number])]
    if unposted:
        problems.append(f"{scenario_id}: {PROOF_POSTED_ACCOUNTS} account(s) {_clip(', '.join(unposted), 200)} have "
                        "zero ACCT-CURR-CYC-CREDIT and ACCT-CURR-CYC-DEBIT, but POSTTRAN posts to every account's "
                        "cycle totals")
    last = records.get(PROOF_UNUPDATED_ACCOUNT)
    if last is not None and final is not None:
        ending = next((
            record for record in final
            if isinstance(record, Mapping) and record.get("ACCT-ID") == last.get("ACCT-ID")
        ), None)
        if ending is not None:
            changed = [name for name in PROOF_ACCOUNT_FIELDS if last.get(name) != ending.get(name)]
            if changed:
                problems.append(f"{scenario_id}: account {PROOF_UNUPDATED_ACCOUNT}'s {', '.join(changed)} differ "
                                f"between {PROOF_POSTED_ACCOUNTS} and the final ACCTDATA, but INTCALC never "
                                "rewrites it (GAP-DEF-003)")
    return problems


def _proof_chain_problems(scenario_id: str, found: Mapping[str, list]) -> list[str]:
    """Why the chain's later jobs are not shown in its decoded datasets
    (``found``, those present; a scenario without them adds nothing):
    ``@CATALOG`` lists exactly ``PROOF_BKUP_GENERATIONS`` TRANSACT.BKUP
    generations, TRANBKP's and TRANREPT's. TRANBKP's backup
    (``PROOF_FIRST_BACKUP``) is in ascending TRAN-ID order, holds none of
    the interest records ``PROOF_INTEREST_IDS``, which COMBTRAN merges in only
    after it, and every record's TRAN-PROC-TS is one of ``PROOF_PROC_TS``.
    TRXFL is in ascending
    (TRNX-CARD-NUM, TRNX-ID) order, its keys are TRANSACT's (TRAN-CARD-NUM,
    TRAN-ID) pairs and every record's FILLER is 20 spaces. STMTFILE holds
    ``PROOF_STATEMENTS`` lines with 'START OF STATEMENT' and HTMLFILE as many
    '<!DOCTYPE html>' lines. Each TRANSACT TRAN-ID begins exactly one
    TRANREPT line, and the report holds a 'Page Total' and an 'Account Total'
    line and exactly one 'Grand Total' line."""
    problems: list[str] = []
    catalog = found.get(PROOF_CATALOG)
    if catalog is not None:
        seen = sum(1 for line in catalog
                   if isinstance(line, str) and PROOF_BKUP_GENERATION_RE.fullmatch(line.rstrip()))
        if seen != PROOF_BKUP_GENERATIONS:
            problems.append(f"{scenario_id}: {PROOF_CATALOG} lists {seen} AWS.M2.CARDDEMO.TRANSACT.BKUP "
                            f"generation(s), not {PROOF_BKUP_GENERATIONS}, TRANBKP's and TRANREPT's")
    backup = found.get(PROOF_FIRST_BACKUP)
    if backup is not None:
        ids = _proof_field(backup, "TRAN-ID")
        if not all(isinstance(key, str) for key in ids) or any(left >= right for left, right in zip(ids, ids[1:])):
            problems.append(f"{scenario_id}: {PROOF_FIRST_BACKUP} is not in ascending TRAN-ID order")
        interest = [key for key in ids if key in PROOF_INTEREST_IDS]
        if interest:
            problems.append(f"{scenario_id}: {PROOF_FIRST_BACKUP} holds {len(interest)} interest record(s) "
                            f"({_clip(', '.join(interest), 120)}), but TRANBKP backs up only the posted records, "
                            "before COMBTRAN merges INTCALC's in")
        wrong = sum(1 for stamp in _proof_field(backup, "TRAN-PROC-TS") if stamp not in PROOF_PROC_TS)
        if wrong:
            problems.append(f"{scenario_id}: {wrong} {PROOF_FIRST_BACKUP} record(s) have a TRAN-PROC-TS other than "
                            f"{PROOF_PROC_TS[0]}")
    transact = found.get("TRANSACT")
    trxfl = found.get("TRXFL")
    if trxfl is not None:
        keys = list(zip(*(_proof_field(trxfl, name) for name in PROOF_TRXFL_KEY)))
        if not all(isinstance(part, str) for key in keys for part in key) or any(
            left >= right for left, right in zip(keys, keys[1:])
        ):
            problems.append(f"{scenario_id}: TRXFL is not in ascending {', '.join(PROOF_TRXFL_KEY)} order")
        if transact is not None:
            pairs = Counter(zip(_proof_field(transact, "TRAN-CARD-NUM"), _proof_field(transact, "TRAN-ID")))
            held = Counter(keys)
            extra, absent = sum((held - pairs).values()), sum((pairs - held).values())
            if extra or absent:
                problems.append(f"{scenario_id}: TRXFL's {', '.join(PROOF_TRXFL_KEY)} keys are not TRANSACT's "
                                f"TRAN-CARD-NUM, TRAN-ID pairs ({extra} TRXFL key(s) not in TRANSACT, "
                                f"{absent} TRANSACT pair(s) not in TRXFL)")
        field_name, padding = PROOF_TRXFL_PADDING
        wrong = sum(1 for value in _proof_field(trxfl, field_name) if value != padding)
        if wrong:
            problems.append(f"{scenario_id}: {wrong} TRXFL record(s) have a {field_name} other than "
                            f"{len(padding)} spaces")
    statements = found.get("STMTFILE")
    if statements is not None:
        seen = sum(1 for line in statements if isinstance(line, str) and PROOF_STATEMENT_MARK in line)
        if seen != PROOF_STATEMENTS:
            problems.append(f"{scenario_id}: STMTFILE holds {seen} line(s) with '{PROOF_STATEMENT_MARK}', not "
                            f"{PROOF_STATEMENTS}, one statement for each card")
    pages = found.get("HTMLFILE")
    if pages is not None:
        seen = sum(1 for line in pages if isinstance(line, str) and line.rstrip() == PROOF_HTML_HEADER)
        if seen != PROOF_STATEMENTS:
            problems.append(f"{scenario_id}: HTMLFILE holds {seen} '{PROOF_HTML_HEADER}' line(s), not "
                            f"{PROOF_STATEMENTS}, one statement for each card")
    listing = found.get("TRANREPT")
    if listing is not None:
        lines = [line for line in listing if isinstance(line, str)]
        if transact is not None:
            ids = {tran_id for tran_id in _proof_field(transact, "TRAN-ID") if isinstance(tran_id, str)}
            starts = Counter(line[:PROOF_REPORT_ID_WIDTH] for line in lines)
            absent = sum(1 for tran_id in ids if starts[tran_id] == 0)
            repeated = sum(1 for tran_id in ids if starts[tran_id] > 1)
            if absent or repeated:
                problems.append(f"{scenario_id}: TRANREPT lists {absent} of TRANSACT's {len(ids)} TRAN-ID(s) on no "
                                f"line and {repeated} on more than one, not each on exactly one line")
        for label, fewest, most in PROOF_REPORT_TOTALS:
            seen = sum(1 for line in lines if line.startswith(label))
            if seen < fewest or (most is not None and seen > most):
                wanted = f"exactly {most}" if most == fewest else f"at least {fewest}"
                problems.append(f"{scenario_id}: TRANREPT holds {seen} '{label}' line(s), not {wanted}")
    return problems


def check_proof(state: State | None = None) -> tuple[bool, str]:
    """The job-chain proof, which runs on gnucobol-batch. For each of
    ``BAT-POSTINT-normal-01`` and ``BAT-CHAIN-normal-01`` (``PROOF_SCENARIOS``):

    - It is ``passed`` on current records and asserts its business outcomes:
      return code, messages, reject reasons and the datasets of its jobs
      (``PROOF_REQUIREMENTS``), and under ``file_state``
      (``PROOF_OBSERVATIONS``) the accounts as POSTTRAN leaves them,
      ``@AFTER:POSTTRAN:ACCTDATA``, and for the chain its catalog,
      ``@CATALOG``, and TRANBKP's backup and emptied TRANSACT,
      ``@AFTER:TRANBKP:TRANSACT-BKUP`` and ``@AFTER:TRANBKP:TRANSACT``.
    - Its file is the proof's: ``inputs.job`` is exactly its job list
      (``PROOF_JOB_SEQUENCES``: POSTTRAN then INTCALC; the chain goes on with
      TRANBKP, COMBTRAN, CREASTMT and TRANREPT) with no program or steps. Its
      precondition datasets are exactly ``PROOF_FIXTURES``, each a bare
      repository ``base`` with no inline records: ``dailytran.txt`` as
      DALYTRAN, the repository CARDXREF, ACCTDATA, TCATBALF, DISCGRP,
      CUSTDATA, TRANTYPE and TRANCATG, and TRANSACT-INIT as TRANSACT. It
      declares no fault. It runs at ``DEFAULT_CLOCK``, and the chain has
      ``clock_mode: fixed`` and ``DATEPARM`` ``2022-01-01 2022-07-06``.
    - Its baseline was observed on gnucobol-batch, and its expectation, which
      every passed run matches on each asserted key, pins the proof's values:
      return code 4, the 38 reject reasons 0102, the complete list of
      message lines, and the full record list of every dataset and
      observation the scenario must assert, ACCTDATA's and the accounts'
      after POSTTRAN each naming ``ACCT-CURR-BAL``, ``ACCT-CURR-CYC-CREDIT``
      and ``ACCT-CURR-CYC-DEBIT`` for all 50 accounts. A count, a
      ``contains`` subsequence or matchers alone pin none of them.
    - It has a current passed gnucobol-batch record; a UniKix pass alone never
      proves it. That record's decoded outcome shows return code 4. Its
      messages hold, job after job, a step line for each step of the job's
      gnucobol-batch step table in order, ``STEP <name> RC <nnnn>`` as both
      adapters' job runs write them, with no abend and the job's RC as the
      highest (POSTTRAN 0004, every later job 0000). They show the
      ``PROOF_MESSAGE_COUNTS`` lines ``TRANSACTIONS PROCESSED :000000300`` and
      ``TRANSACTIONS REJECTED  :000000038`` once each and 'DISCLOSURE GROUP
      RECORD MISSING' 100 times, and 50 TCATBAL creation lines. DALYREJS
      holds 38 rejects, each with reason 0102 'OVERLIMIT TRANSACTION'.
      TRANSACT holds 262 records (312 after the chain's COMBTRAN) in TRAN-ID
      order, each with ``TRAN-PROC-TS`` at the default clock. TCATBALF holds
      its fixture plus 50 created records. ACCTDATA holds the 50 accounts,
      1 to 49 with ``ACCT-CURR-CYC-CREDIT`` and ``ACCT-CURR-CYC-DEBIT`` both
      zero, as INTCALC rewrites them, and account 50 without both zero, as
      INTCALC never rewrites it (GAP-DEF-003). The accounts as POSTTRAN
      leaves them are the 50, each with ``ACCT-CURR-CYC-CREDIT`` or
      ``ACCT-CURR-CYC-DEBIT`` non-zero, as POSTTRAN posts to every one, and
      account 50 with the balance and cycle fields of its final ACCTDATA
      record. SYSTRAN holds the 50 interest records ``2022071800000001`` to
      ``2022071800000050`` in order.
    - For the chain, that record also shows the later jobs. At the end of
      TRANBKP, its TRANSACT-BKUP generation holds the 262 posted records in
      ascending TRAN-ID order, none of them an interest record, each with
      ``TRAN-PROC-TS`` at the default clock, and TRANSACT holds no record.
      ``@CATALOG`` lists exactly two ``AWS.M2.CARDDEMO.TRANSACT.BKUP``
      generations, TRANBKP's and TRANREPT's, and TRANSACT-BKUP, the current
      one at the end, holds 312 records. TRXFL holds 312 records in
      ascending (TRNX-CARD-NUM, TRNX-ID) order, whose keys are TRANSACT's
      (TRAN-CARD-NUM, TRAN-ID) pairs, each padded with spaces in its 20-byte
      FILLER. STMTFILE holds 50
      'START OF STATEMENT' lines and HTMLFILE 50 '<!DOCTYPE html>' lines, one
      statement for each card. Each TRANSACT TRAN-ID begins exactly one
      TRANREPT line, and the report holds at least one 'Page Total' and one
      'Account Total' line and exactly one 'Grand Total' line.

    Finally, the BAT items they list and the POSTTRAN and INTCALC job items
    must be verified. Returns whether the proof is met and why: each unmet
    condition, joined by ``; ``, naming the scenario and what was observed
    against what is required, or the scenarios and items it verified."""
    state = state if state is not None else build_state()
    reasons: list[str] = []
    required: set[str] = set()
    tables: dict[str, tuple[str, ...]] | None = None
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
        missing += [name for name in PROOF_OBSERVATIONS[scenario_id] if f"file_state.{name}" not in asserted]
        if missing:
            reasons.append(f"{scenario_id} does not assert {', '.join(missing)}")
        reasons.extend(_proof_scenario_problems(scenario))
        reasons.extend(_proof_baseline_problems(scenario_id))
        record = next((
            entry for entry in status.counted
            if entry.get("adapter") == _results.GNUCOBOL and entry.get("status") == _results.STATUS_PASSED
        ), None)
        if record is None:
            if status.status == _results.STATUS_PASSED:
                reasons.append(f"{scenario_id} passed only on UniKix records, and the proof needs a current passed "
                               "gnucobol-batch record")
        elif not isinstance(record.get("decoded"), Mapping):
            reasons.append(f"{scenario_id}: its passed gnucobol-batch record holds no decoded outcome")
        else:
            tables = tables if tables is not None else _proof_step_tables()
            reasons.extend(_proof_outcome_problems(scenario_id, record["decoded"], tables))
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
    return True, (f"{' and '.join(PROOF_SCENARIOS)} passed on current gnucobol-batch records with the proof's "
                  "jobs, fixtures and clock and their business outcomes asserted and observed, and "
                  f"{', '.join(sorted(required))} are verified")


def check_seams(state: State | None = None) -> list[str]:
    """The seam rule: every seam of the gnucobol-batch adapter has its
    ``stub-limitation`` entry; every scenario gnucobol-batch can run cites,
    in its ``gap_ids``, the entry of each seam its program or job steps use
    (``_seam_citations``); and a closed entry holds valid resolving
    evidence, the mapping ``{execution_id, scenario_id, counterpart_of?,
    evidence_path}``:

    - ``execution_id`` names a UniKix execution whose evidence directory
      exists under ``tests/reports/unikix/`` with a current, passed and
      eligible ``validation.yaml`` for the cited ``scenario_id``.
    - The seam is characterized on gnucobol-batch by the scenario
      ``counterpart_of`` names when present (``scenario_id`` is then its
      UniKix counterpart, a different scenario), else by ``scenario_id``
      itself. That scenario uses the seam (its ``gap_ids`` cite the entry),
      gnucobol-batch runs it, its baseline was captured on gnucobol-batch and
      it has a current passed gnucobol-batch record.
    - A counterpart exercises the seam: its UniKix record ran at least one
      program that gnucobol-batch record ran, by the upper-case stems of the
      ``source_identity.units`` whose folder is ``cbl`` (the zip member
      ``migrated_app/cbl/CBTRN02C.cbl`` is the program of
      ``app/cbl/CBTRN02C.cbl``).
    - The UniKix outcome agrees with that record's decoded outcome. For the
      same scenario, every observation the gnucobol-batch decoded outcome
      holds, each ``datasets`` and ``file_state`` name on its own, is also
      in the UniKix decoded outcome and equal to it. For a counterpart, the
      characterizing scenario asserts an observation outside screens besides
      ``return_code``, which alone cannot show agreement, and the
      counterpart asserts each observation the characterizing scenario
      asserts outside screens: ``return_code``, ``reject_reason_codes`` and
      each dataset and file state are equal, and the gnucobol-batch messages
      without the CEE3ABD stub's marker line (``_CEE3ABD_MARKER``) are not
      empty and appear in order among the UniKix messages."""
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
        counterpart = evidence.get("counterpart_of")
        if counterpart is not None and (not isinstance(counterpart, str) or counterpart == scenario_id):
            problems.append(f"{gap_id}: resolving_evidence counterpart_of must name the gnucobol-batch scenario "
                            f"that {scenario_id} stands in for, not {_clip(repr(counterpart), 80)}")
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
            else:
                disagreement = _seam_disagreement(state, gap_id, execution_id, scenario, record, counterpart)
                if disagreement is not None:
                    problems.append(disagreement)
    problems.extend(_seam_citations(state))
    return problems


def _seam_citations(state: State) -> list[str]:
    """One finding per scenario gnucobol-batch can run whose ``gap_ids`` do
    not cite the entry of every seam its program or job steps use
    (``_used_seams``), naming each missing entry; a single finding instead
    when the gnucobol-batch ``programs.yaml`` or ``jobs.yaml`` cannot be read
    or holds no ``programs`` or ``jobs`` mapping, or when
    ``tests/fixtures/datasets.yaml``, whose ``ksds`` datasets are the indexed
    ones (``_indexed_dsns``), cannot be read or parsed."""
    tables: dict[str, Mapping[str, Any]] = {}
    for name, path in (("programs", _scenarios.GNUCOBOL_PROGRAMS_FILE), ("jobs", _results.GNUCOBOL_JOBS_FILE)):
        try:
            document = load_yaml(path)
        except OSError as exc:
            return [f"the seam citations cannot be checked: cannot read {_os_problem(exc, path)}"]
        except YAMLLoadError as exc:
            return [f"the seam citations cannot be checked: {_clip(exc, 300)}"]
        section = document.get(name) if isinstance(document, Mapping) else None
        if not isinstance(section, Mapping):
            return [f"the seam citations cannot be checked: {display_path(path)} holds no {name} mapping"]
        tables[name] = section
    try:
        definitions = _fixtures.specs()
    except OSError as exc:
        return [f"the seam citations cannot be checked: cannot read {_os_problem(exc, _fixtures.FIXTURES_FILE)}"]
    except HarnessError as exc:
        return [f"the seam citations cannot be checked: {_clip(exc, 300)}"]
    indexed = _indexed_dsns(tables["jobs"], {name for name, definition in definitions.items() if definition.keyed})
    described = {gap_id: what for what, gap_id in SEAMS}
    problems = []
    for scenario_id, scenario in sorted(state.scenarios.items()):
        if _results.GNUCOBOL not in _scenarios.capable_adapters(scenario):
            continue
        cited = {str(gap_id) for gap_id in scenario.gap_ids}
        missing = sorted(_used_seams(scenario, tables["programs"], tables["jobs"], indexed) - cited)
        if missing:
            several = len(missing) > 1
            named = [f"{gap_id} ({described.get(gap_id, 'a gnucobol-batch seam')})" for gap_id in missing]
            problems.append(f"{scenario_id} uses the seam{'s' if several else ''} of {_listed(named)} but its "
                            f"gap_ids do not cite {'them' if several else 'it'}")
    return problems


def _used_seams(
    scenario: Any, programs: Mapping[str, Any], jobs: Mapping[str, Any], indexed: frozenset[str]
) -> set[str]:
    """The entries of the seams gnucobol-batch runs ``scenario`` through,
    from its ``programs`` and ``jobs`` tables. Each unit executed, the
    scenario's ``inputs.program`` or the program of each ``program`` step of
    its jobs, and each unit that one ``calls`` gives ``_SEAM_OF_UNIT``,
    ``_SEAM_OF_INDEXED`` for an indexed dd, ``_SEAM_OF_DRIVER`` for its run,
    ``_SEAM_OF_STUB_CALLER`` for its name and ``_SEAM_OF_TIOT`` for a TIOT.
    Each job gives the emulated catalog and the replacement of each utility
    step kind (``_SEAM_OF_UTILITY_STEP``). Each step of a job, whatever its
    kind, gives ``_SEAM_OF_INDEXED`` for a dd or concatenation member whose
    DSN is one of the ``indexed`` DSNs (``_indexed_dsns``) and for an IDCAMS
    SYSIN that goes through indexed files (``_idcams_uses_indexed``), and
    ``_SEAM_OF_DISPOSITION`` for a dataset whose disposition the DISP
    emulation acts on (``_disposition_acted_on``). An expected
    ``return_code`` of null gives the CEE3ABD stub that ends the run."""
    seams: set[str] = set()
    units: list[str] = []
    program = scenario.inputs.get("program")
    if isinstance(program, str):
        units.append(program)
    else:
        for job in _scenarios.job_names(scenario):
            seams.add(_SEAM_OF_CATALOG)
            for step in _job_steps(jobs, job):
                kind = step.get("kind")
                if kind == "program" and isinstance(step.get("program"), str):
                    units.append(step["program"])
                elif isinstance(kind, str) and kind in _SEAM_OF_UTILITY_STEP:
                    seams.add(_SEAM_OF_UTILITY_STEP[kind])
                for entry in _step_datasets(step):
                    dsn = entry.get("dsn")
                    if isinstance(dsn, str) and dsn.upper() in indexed:
                        seams.add(_SEAM_OF_INDEXED)
                    if _disposition_acted_on(entry):
                        seams.add(_SEAM_OF_DISPOSITION)
                params = step.get("params")
                sysin = params.get("sysin") if kind == "idcams" and isinstance(params, Mapping) else None
                if isinstance(sysin, list) and _idcams_uses_indexed(sysin, indexed):
                    seams.add(_SEAM_OF_INDEXED)
    seen: set[str] = set()
    while units:
        unit = units.pop()
        config = programs.get(unit)
        if unit in seen or not isinstance(config, Mapping):
            continue
        seen.add(unit)
        seams.add(_SEAM_OF_UNIT)
        dds = config.get("dd")
        if isinstance(dds, Mapping) and any(
            isinstance(dd, Mapping) and dd.get("organization") == "indexed" for dd in dds.values()
        ):
            seams.add(_SEAM_OF_INDEXED)
        run = config.get("run")
        if isinstance(run, str) and run in _SEAM_OF_DRIVER:
            seams.add(_SEAM_OF_DRIVER[run])
        if unit in _SEAM_OF_STUB_CALLER:
            seams.add(_SEAM_OF_STUB_CALLER[unit])
        if config.get("tiot") is not None:
            seams.add(_SEAM_OF_TIOT)
        calls = config.get("calls")
        units.extend(called for called in (calls if isinstance(calls, list) else ()) if isinstance(called, str))
    if "return_code" in scenario.expected and scenario.expected["return_code"] is None:
        seams.add(_SEAM_OF_ABEND)
    return seams


def _job_steps(jobs: Mapping[str, Any], job: str) -> list[Mapping[str, Any]]:
    """The steps of ``job`` in the ``jobs`` table that are mappings; none for
    a job the table lacks or holds without a list of steps."""
    entry = jobs.get(job)
    steps = entry.get("steps") if isinstance(entry, Mapping) else None
    return [step for step in steps if isinstance(step, Mapping)] if isinstance(steps, list) else []


def _step_datasets(step: Mapping[str, Any]) -> Iterator[Mapping[str, Any]]:
    """Each dd entry of a ``jobs.yaml`` step and each member of its
    concatenation, in transcription order, as ``datasets.DsnMap`` reads
    them; an entry that is not a mapping is skipped."""
    dds = step.get("dd")
    for dd in dds.values() if isinstance(dds, Mapping) else ():
        if not isinstance(dd, Mapping):
            continue
        yield dd
        concat = dd.get("concat")
        yield from (member for member in (concat if isinstance(concat, list) else ()) if isinstance(member, Mapping))


def _indexed_dsns(jobs: Mapping[str, Any], keyed: set[str]) -> frozenset[str]:
    """The DSNs, in upper case, that a dd or concatenation member of any job
    in ``jobs`` maps to one of the ``keyed`` logical datasets (organization
    ``ksds`` in ``tests/fixtures/datasets.yaml``) or to an alternate-index
    path (``aix_path``): the datasets gnucobol-batch holds as Berkeley DB
    indexed files. ``jobs.yaml`` maps every DSN to at most one logical
    dataset, so the DSN also names the dataset where an IDCAMS command
    gives it directly."""
    return frozenset(
        entry["dsn"].upper()
        for job in jobs
        for step in _job_steps(jobs, job)
        for entry in _step_datasets(step)
        if isinstance(entry.get("dsn"), str)
        and (entry.get("aix_path") is not None or entry.get("logical") in keyed)
    )


def _disposition_acted_on(entry: Mapping[str, Any]) -> bool:
    """Whether ``utilities.apply_normal_dispositions`` acts on the dataset
    of a ``jobs.yaml`` dd entry or concatenation member: the entry names a
    dataset of the store (a DSN, and not in-stream data, ``DUMMY``,
    ``SYSOUT`` or a PDS member), and its normal-end disposition is
    ``_ACTED_DISPOSITIONS`` or its status, ``NEW`` when omitted, is one of
    ``_ALLOCATING_STATUSES``. ``disp`` is read as ``utilities._disp`` reads
    it: absent is no subparameter, each one stripped and in upper case; one
    that is not a list of text fails the step before any disposition."""
    if (
        entry.get("instream") is not None
        or entry.get("dummy")
        or entry.get("sysout") is not None
        or entry.get("member") is not None
        or not isinstance(entry.get("dsn"), str)
        or not entry["dsn"]
    ):
        return False
    raw = entry.get("disp")
    if raw is None:
        raw = []
    if not isinstance(raw, list) or not all(isinstance(part, str) for part in raw):
        return False
    disp = [part.strip().upper() for part in raw]
    status = disp[0] if disp and disp[0] else "NEW"
    normal = disp[1] if len(disp) > 1 and disp[1] else None
    return normal in _ACTED_DISPOSITIONS or status in _ALLOCATING_STATUSES


def _idcams_statements(lines: Sequence[Any]) -> Iterator[str]:
    """The commands of an IDCAMS SYSIN as the replacement reads them:
    ``/* ... */`` comments removed and a line ending in ``-`` or ``+``
    continued on the next. A line that is not text, which fails the step,
    is left out."""
    text = _IDCAMS_COMMENT_RE.sub(" ", "\n".join(line for line in lines if isinstance(line, str)))
    pending: list[str] = []
    for line in text.split("\n"):
        stripped = line.rstrip()
        if stripped.endswith(("-", "+")):
            pending.append(stripped[:-1])
            continue
        pending.append(stripped)
        yield " ".join(pending)
        pending = []
    if pending:
        yield " ".join(pending)


def _idcams_uses_indexed(lines: Sequence[Any], indexed: frozenset[str]) -> bool:
    """Whether an IDCAMS SYSIN goes through Berkeley DB indexed files: a
    command outside every parameter list (after ``IF ... THEN`` or ``ELSE``
    too) that is ``DEFINE`` of an ``ALTERNATEINDEX`` or ``PATH``, or of a
    ``CLUSTER`` whose parameter list codes none of
    ``_NONINDEXED_CLUSTERS`` (``INDEXED`` is the default, and the only
    organization the replacement emulates), or that is ``BLDINDEX``; or an
    ``INDATASET`` or ``OUTDATASET`` naming one of the ``indexed`` DSNs.
    Keywords are whole tokens, so a dataset name such as
    ``AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX`` is never one."""
    for statement in _idcams_statements(lines):
        tokens = _IDCAMS_TOKEN_RE.findall(statement)
        words = [_IDCAMS_ALIASES.get(token.upper(), token.upper()) for token in tokens]
        depth = 0
        for index, word in enumerate(words):
            if word == "(":
                depth += 1
            elif word == ")":
                depth -= 1
            elif word in _IDCAMS_DSN_OPERANDS:
                if words[index + 1:index + 2] == ["("] and tokens[index + 2:index + 3]:
                    if tokens[index + 2].upper() in indexed:
                        return True
            elif depth == 0 and word == "BLDINDEX":
                return True
            elif depth == 0 and word == "DEFINE" and index + 1 < len(words):
                kind = words[index + 1]
                if kind in _INDEXED_DEFINES:
                    return True
                if kind == "CLUSTER" and not _parameter_keywords(words, index + 2) & _NONINDEXED_CLUSTERS:
                    return True
    return False


def _parameter_keywords(words: Sequence[str], start: int) -> set[str]:
    """The words of the parameter list that opens at ``words[start]`` at
    that list's own level, without those inside a keyword's value: the
    keywords of ``DEFINE CLUSTER (...)``. Empty when no list opens there."""
    keywords: set[str] = set()
    if words[start:start + 1] != ["("]:
        return keywords
    depth = 0
    for word in words[start:]:
        if word == "(":
            depth += 1
        elif word == ")":
            depth -= 1
            if depth == 0:
                break
        elif depth == 1:
            keywords.add(word)
    return keywords


# The line the gnucobol-batch adapter adds to ``messages`` when the CEE3ABD
# stub (tests/stubs/CEE3ABD.cbl) ends a run, as the stub's stand-in for a
# Language Environment abend. The marker belongs to the test tree: whatever
# CEE3ABD a UniKix region supplies writes no such line, so a counterpart's
# messages are held to the gnucobol-batch lines without it.
_CEE3ABD_MARKER = "CARDDEMO-ABEND CEE3ABD"


def _seam_disagreement(state: State, gap_id: str, execution_id: str, scenario: Any,
                       record: Mapping[str, Any], counterpart: str | None) -> str | None:
    """Why the current, passed, eligible UniKix ``record`` of ``scenario`` in
    ``execution_id`` does not resolve the seam of ``gap_id``, or ``None``
    when it does: the gnucobol-batch scenario that characterizes the seam
    (``counterpart`` when given, else ``scenario``) uses the seam, runs on
    gnucobol-batch with a baseline captured there and a current passed
    record, and the UniKix outcome agrees with that record (``check_seams``).

    The same scenario agrees when every top-level observation of that
    record's decoded outcome, and every name of a non-empty ``datasets`` or
    ``file_state`` section on its own, is in the UniKix decoded outcome and
    equal. A counterpart must run a program of that record
    (``_unit_programs``) and assert every observation the characterizing
    scenario asserts outside screens, which must be more than
    ``return_code``. Each such observation is equal on both sides, except
    ``messages``: the gnucobol-batch lines without ``_CEE3ABD_MARKER`` must
    be non-empty, since an empty list is in order within any, and appear in
    order among the UniKix lines."""
    characterizing_id = counterpart if counterpart is not None else scenario.id
    characterizing = state.scenarios.get(characterizing_id)
    if characterizing is None:
        return f"{gap_id}: counterpart_of names {characterizing_id}, which has no scenario file"
    if gap_id not in {str(cited) for cited in characterizing.gap_ids}:
        return f"{gap_id}: {characterizing_id} does not use this seam (its gap_ids do not cite {gap_id})"
    if _results.GNUCOBOL not in _scenarios.capable_adapters(characterizing):
        return f"{gap_id}: {characterizing_id} is not run by gnucobol-batch, so nothing characterizes the seam"
    status = state.statuses.get(characterizing_id)
    reference = next((
        entry for entry in (status.counted if status is not None else ())
        if entry.get("adapter") == _results.GNUCOBOL and entry.get("status") == _results.STATUS_PASSED
    ), None)
    if reference is None:
        return (f"{gap_id}: {characterizing_id} has no current passed gnucobol-batch record, so nothing "
                "characterizes the seam")
    baseline, problem = _load_baseline(characterizing_id)
    platform = baseline.capture_platform if problem is None and baseline is not None else None
    if platform != _results.GNUCOBOL:
        shown = ("its baseline cannot be read" if problem is not None else "it has none" if baseline is None
                 else "its baseline is derived" if platform == _baselines.DERIVED
                 else f"its baseline was captured on {platform}")
        return f"{gap_id}: {characterizing_id} has no baseline captured on gnucobol-batch ({shown})"
    unikix_outcome = f"the UniKix outcome of {scenario.id} in {execution_id}"
    gnucobol_outcome = f"the gnucobol-batch record of {characterizing_id}"
    observed, characterized = record.get("decoded"), reference.get("decoded")
    if not isinstance(observed, Mapping):
        return f"{gap_id}: {unikix_outcome} carries no decoded outcome to compare"
    if not isinstance(characterized, Mapping):
        return f"{gap_id}: {gnucobol_outcome} carries no decoded outcome to compare"
    if counterpart is None:
        keys: list[str] = []
        for key in sorted(characterized, key=str):
            section = characterized[key]
            if key in ("datasets", "file_state") and isinstance(section, Mapping) and section:
                keys.extend(f"{key}.{name}" for name in sorted(section, key=str))
            else:
                keys.append(str(key))
        if not keys:
            return (f"{gap_id}: {gnucobol_outcome} holds no decoded observation, so agreement with the "
                    "gnucobol-batch characterization cannot be shown")
    else:
        programs = _unit_programs(reference)
        if not programs & _unit_programs(record):
            return (f"{gap_id}: {scenario.id} runs none of the programs of {characterizing_id} "
                    f"({', '.join(sorted(programs)) or 'none'}), so it does not exercise the seam")
        keys = [key for key in _scenarios.asserted_keys(characterizing) if not key.startswith("screens[")]
        if not keys:
            return (f"{gap_id}: {characterizing_id} asserts no observation outside screens, so agreement with the "
                    "gnucobol-batch characterization cannot be shown")
        if keys == ["return_code"]:
            return (f"{gap_id}: {characterizing_id} asserts no observation outside screens but return_code, which "
                    "alone cannot show agreement with the gnucobol-batch characterization")
        asserted = set(_scenarios.asserted_keys(scenario))
        unasserted = [key for key in keys if key not in asserted]
        if unasserted:
            return (f"{gap_id}: {scenario.id} does not assert {', '.join(unasserted)}, which the gnucobol-batch "
                    f"characterization of {characterizing_id} asserts")
    for key in keys:
        held, unikix_value = _decoded_observation(observed, key)
        reference_held, gnucobol_value = _decoded_observation(characterized, key)
        if not held or not reference_held:
            return f"{gap_id}: {unikix_outcome if not held else gnucobol_outcome} holds no {key} to compare"
        undecodable = any(
            isinstance(value, Mapping) and _baselines.ERROR in value for value in (unikix_value, gnucobol_value)
        )
        if key == "messages" and counterpart is not None:
            lines = ([line for line in gnucobol_value if line != _CEE3ABD_MARKER]
                     if isinstance(gnucobol_value, list) else gnucobol_value)
            if isinstance(lines, list) and not lines:
                besides = f" besides the CEE3ABD stub's marker {_CEE3ABD_MARKER!r}" if gnucobol_value else ""
                return (f"{gap_id}: {gnucobol_outcome} holds no messages{besides}, so the messages of "
                        f"{unikix_outcome} cannot show agreement with it")
            agrees = isinstance(unikix_value, list) and isinstance(lines, list) and _in_order(lines, unikix_value)
        else:
            agrees = unikix_value == gnucobol_value
        if undecodable or not agrees:
            return (f"{gap_id}: {unikix_outcome} differs from the gnucobol-batch characterization of "
                    f"{characterizing_id} in {key}")
    return None


def _decoded_observation(decoded: Mapping[str, Any], key: str) -> tuple[bool, Any]:
    """Whether ``decoded`` holds the observation an ``asserted_keys`` name
    gives (``datasets.<name>`` and ``file_state.<name>`` within their
    section), and its value."""
    section, dot, name = key.partition(".")
    if dot and section in ("datasets", "file_state"):
        values = decoded.get(section)
        if isinstance(values, Mapping) and name in values:
            return True, values[name]
        return False, None
    return key in decoded, decoded.get(key)


def _in_order(lines: Sequence[Any], within: Sequence[Any]) -> bool:
    """Whether every one of ``lines`` appears in ``within``, in the same
    order (an ordered subsequence; other lines may come between them)."""
    remaining = iter(within)
    return all(any(line == other for other in remaining) for line in lines)


def _unit_programs(record: Mapping[str, Any]) -> set[str]:
    """The programs ``record`` ran by its ``source_identity``: the upper-case
    file stems of its units whose parent folder is ``cbl``, so the zip member
    ``migrated_app/cbl/CBTRN02C.cbl`` and ``app/cbl/CBTRN02C.cbl`` are both
    ``CBTRN02C``. A record whose identity or units is not a mapping ran
    none."""
    identity = record.get("source_identity")
    units = identity.get("units") if isinstance(identity, Mapping) else None
    if not isinstance(units, Mapping):
        return set()
    return {Path(unit).stem.upper() for unit in units if isinstance(unit, str) and Path(unit).parent.name == "cbl"}


def _git(args: Sequence[str]) -> subprocess.CompletedProcess | None:
    return _run(["git", *args])


def _git_message(completed: subprocess.CompletedProcess) -> str:
    """git's error output in ``completed`` on one line, clipped; empty when
    git printed none."""
    return _clip(" ".join((completed.stderr or "").split()), 500)


def _protected_git(args: Sequence[str], accepted: tuple[int, ...] = (0,)) -> subprocess.CompletedProcess:
    """``git <args>`` for the protected-tree check, when it exits with one of
    ``accepted``. ``ReportError`` when git cannot start, times out or exits
    otherwise, with git's own message: the tree then cannot be checked,
    which is no finding about the tree."""
    completed = _git(args)
    command = f"git {' '.join(args[:2])}"
    if completed is None:
        raise ReportError(f"{command} cannot be run (git does not start or times out), "
                          "so the protected tree cannot be checked")
    if completed.returncode not in accepted:
        raise ReportError(f"{command} failed (exit {completed.returncode}), so the protected tree cannot be "
                          f"checked: {_git_message(completed) or 'no message'}")
    return completed


def check_protected(base: str | None = None) -> list[str]:
    """No change under ``app/``, ``samples/`` or ``diagrams/``: ``git diff``
    against the inventory header's ``protected_base`` (the starting commit)
    is empty, and so is ``git status`` of those paths.

    The findings: the base names no commit of this clone (``git rev-parse
    --verify`` exits 1), with git's message when it prints one and, only
    when ``git rev-parse --is-shallow-repository`` prints ``true``, the
    advice to fetch the full history; a change the diff lists or reports by
    its exit 1; a working-tree change the status lists. ``ReportError`` when
    git cannot start, times out, or exits otherwise (no repository, a fatal
    error, dubious ownership), with git's own message."""
    if base is None:
        try:
            base = _inventory.load()["header"].get("protected_base") or PROTECTED_BASE
        except HarnessError:
            base = PROTECTED_BASE
    paths = ("app", "samples", "diagrams")
    verify = _protected_git(["rev-parse", "--verify", "--quiet", f"{base}^{{commit}}"], accepted=(0, 1))
    if verify.returncode == 1:
        finding = f"the protected base {base} is not a commit of this clone"
        detail = _git_message(verify)
        if detail:
            finding += f": {detail}"
        # A shallow probe that fails counts as not shallow: the advice to fetch
        # the full history is given only where git says the history is cut.
        shallow = _git(["rev-parse", "--is-shallow-repository"])
        if shallow is not None and shallow.returncode == 0 and shallow.stdout.strip() == "true":
            finding += ("; the clone is shallow, so the base is not in this clone's history; fetch the full "
                        "history (actions/checkout fetch-depth: 0) and check again")
        return [finding]
    findings = []
    diff = _protected_git(["diff", "--exit-code", "--name-status", base, "--", *paths], accepted=(0, 1))
    if diff.returncode == 1 or diff.stdout.strip():
        changed = [line for line in diff.stdout.splitlines() if line.strip()]
        findings.append(f"changed since {base} under app/, samples/ or diagrams/: " + "; ".join(changed[:50]))
    status = _protected_git(["status", "--porcelain", "--untracked-files=all", "--", *paths])
    if status.stdout.strip():
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


# How many characters _gap_ids_in decodes at a time, and how many of the
# characters already scanned it scans again with the next chunk: a gap id is
# 11 characters, so one that straddles two chunks starts within the last 10.
# No gap id can overlap another (no category code holds a 'G'), so the ids a
# chunked scan finds are exactly those a scan of the whole text finds.
_GAP_SCAN_CHUNK_CHARS = 1 << 20
_GAP_SCAN_OVERLAP = len("GAP-DOC-001") - 1


def _gap_ids_in(path: Path, chunk_chars: int = _GAP_SCAN_CHUNK_CHARS) -> set[str]:
    """The gap ids ``path`` cites, decoded as ``read_text(encoding="utf-8",
    errors="replace")`` decodes it but ``chunk_chars`` characters at a time,
    so a file of any size is scanned whole in fixed memory. ``chunk_chars``
    must be a positive int, since a zero-character read would end the scan
    at once and find nothing."""
    if isinstance(chunk_chars, bool) or not isinstance(chunk_chars, int) or chunk_chars <= 0:
        raise ValueError(f"chunk_chars must be a positive int, not {chunk_chars!r}")
    found: set[str] = set()
    tail = ""
    with path.open(encoding="utf-8", errors="replace") as stream:
        while chunk := stream.read(chunk_chars):
            window = tail + chunk
            found.update(match.group() for match in GAP_ID_RE.finditer(window))
            tail = window[-_GAP_SCAN_OVERLAP:]
    return found


def _record_gap_ids() -> dict[str, list[str]]:
    """The gap ids the stored records cite: the gnucobol-batch records and
    the UniKix ``validation.yaml`` and ``result.yaml`` files, each scanned
    whole by ``_gap_ids_in`` whatever its size."""
    found: dict[str, set[str]] = {}
    paths = sorted(_results.RESULTS_DIR.glob("*.yaml")) if _results.RESULTS_DIR.is_dir() else []
    for execution_dir in _evidence_executions():
        for scenario_dir in _scenario_dirs(execution_dir):
            paths += [scenario_dir / VALIDATION_FILE, scenario_dir / RESULT_FILE]
    for path in paths:
        if path.is_symlink() or not path.is_file():
            continue
        for gap_id in _gap_ids_in(path):
            found.setdefault(gap_id, set()).add(display_path(path))
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


def _uncovered_items(state: State) -> list[str]:
    """Every item that is not verified and that no open register entry
    affects, in id order, as ``<item id> (<why>)``. Only an open entry whose
    resolved affected items (``state.affected``, the ids ``gap-register.csv``
    renders) include the item covers it: an open gap that a scenario listing
    the item merely cites does not. ``<why>`` sorts the gap ids the item
    carries into open but not affecting the item, closed, neither open nor
    closed, and not in gaps.yaml, or reads 'no register entry' when it carries
    none."""
    covered = {
        item_id
        for gap_id, item_ids in state.affected.items() if state.gaps.is_open(gap_id)
        for item_id in item_ids
    }
    lacking: list[str] = []
    for item in state.items:
        if item["status"] == VERIFIED or item["id"] in covered:
            continue
        # Name the gap ids that left the item uncovered, so the closure to revisit is plain.
        kinds: dict[str, list[str]] = {
            "open, not affecting the item": [], "closed": [], "not open": [], "not in gaps.yaml": [],
        }
        for gap_id in sorted(set(item["gap_ids"])):
            entry = state.gaps.get(gap_id)
            if entry is None:
                kinds["not in gaps.yaml"].append(gap_id)
            elif state.gaps.is_open(gap_id):
                kinds["open, not affecting the item"].append(gap_id)
            else:
                kinds["closed" if entry.get("status") == "closed" else "not open"].append(gap_id)
        named = "; ".join(f"{kind}: {', '.join(gap_ids)}" for kind, gap_ids in kinds.items() if gap_ids)
        lacking.append(f"{item['id']} ({named or 'no register entry'})")
    return lacking


def check(state: State | None = None) -> list[str]:
    """Every finding of the register and matrix rules; empty when they hold:
    one matrix row per inventory item, every item that is not verified among
    the resolved affected items of an open register entry (an open gap a
    listing scenario merely cites does not cover it), status counts adding up
    to the header total, every cited gap id registered, register entries well
    formed, the seam rule, and report files and inventory fields as
    ``regenerate`` writes them."""
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
    lacking = _uncovered_items(state)
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


def _add_gap(document: dict, category: str, unknown: str, items: Iterable[str],
             needed: str) -> tuple[str, str] | None:
    """Register in ``document`` the question ``unknown`` starts with (the
    text up to its first colon) about ``items``, so that no two entries of
    ``category`` ask it. An entry of ``category`` that already asks it keeps
    its id, an open one preferred: a closed one reopens (``_reopen``), takes
    this ``unknown`` and ``needed``, which name the evidence it reopens on,
    and lists ``items`` as well; an open one lists those of ``items`` it does
    not list yet. Only a question no entry of ``category`` asks gets a new
    open entry, with the category's next free number. Returns ``(gap_id,
    how)``, ``how`` being ``"added"``, ``"reopened"`` or ``"extended"``;
    ``None`` when an open entry already asks it about every item. Raises
    ``ReportError``, changing nothing, when the entry that asks it holds an
    ``affected_inventory_ids`` that is not a list."""
    entries = _gap_entries(document)
    question = unknown.split(":", 1)[0]
    raised = {str(item) for item in items}
    asking = [
        (gap_id, entry) for gap_id, entry in entries.items()
        if entry.get("category") == category and str(entry.get("unknown", "")).split(":", 1)[0] == question
    ]
    if asking:
        gap_id, entry = next(((gap_id, entry) for gap_id, entry in asking if entry.get("status") == "open"),
                             asking[0])
        listed = entry.get("affected_inventory_ids")
        if listed is not None and not isinstance(listed, list):
            raise ReportError(f"gaps.yaml: the affected_inventory_ids of {gap_id} is not a list, so the question "
                              f"'{_clip(question, 200)}' it asks cannot be registered for "
                              f"{_clip(', '.join(sorted(raised)), 300)}; nothing is registered")
        held = {str(item_id) for item_id in listed or []}
        if entry.get("status") == "open":
            if raised <= held:
                return None
            entry["affected_inventory_ids"] = sorted(held | raised)
            return gap_id, "extended"
        _reopen(entry)
        entry["unknown"], entry["needed"] = unknown, needed
        entry["affected_inventory_ids"] = sorted(held | raised)
        return gap_id, "reopened"
    prefix = next(code for code, name in CATEGORIES.items() if name == category)
    numbers = [
        int(match.group(2)) for match in map(_GAP_ID_FULL_RE.match, entries) if match and match.group(1) == prefix
    ]
    gap_id = f"GAP-{prefix}-{max(numbers, default=0) + 1:03d}"
    entry = {
        "affected_inventory_ids": sorted(raised),
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
    return gap_id, "added"


# --------------------------------------------------------------------------
# UniKix evidence: reading, reconstruction, comparison
# --------------------------------------------------------------------------

# How evidence.py names the .bin file of a reserved observation.
_OBSERVATION_FILES = {"_CATALOG": "@CATALOG", "_REGION-FILES": "@REGION-FILES", "_TDQ_JOBS": "@TDQ:JOBS"}
# The reason of a run whose deadline passed; its verdict prevails over its payload.
_REASON_TIMEOUT = "timeout"
# What result.yaml holds beside harness.results.RECORD_FIELDS, as
# adapters/unikix/evidence.py writes it, and the keys of its observed payload
# (the adapters.base.Result without its datasets and elapsed time).
_EVIDENCE_KEYS = ("capture_summary", "observed", "observed_messages", "screens_raw")
_OBSERVED_KEYS = ("return_code", "messages", "screens")


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
        """Whether the execution left an outcome to compare again: ``passed``
        or ``failing`` with an observed payload. Once the deadline has passed
        the run is ``failing`` with reason ``timeout`` whatever it observed,
        so that verdict stands and its payload is never compared."""
        if self.raw.get("status") == _results.STATUS_FAILING and self.raw.get("reason") == _REASON_TIMEOUT:
            return False
        decoded = self.raw.get("decoded")
        return (self.raw.get("status") in (_results.STATUS_PASSED, _results.STATUS_FAILING)
                and (self.observed is not None or (isinstance(decoded, Mapping) and bool(decoded))))


def _recorded(document: Any, keys: Sequence[str]) -> dict[str, list[Any]]:
    """Every value ``document`` records under each of ``keys``, at any depth
    of its mappings and lists, in document order: the producer of
    ``environment.yaml`` does not fix where it nests a fact."""
    found: dict[str, list[Any]] = {}

    def walk(value: Any) -> None:
        if isinstance(value, Mapping):
            for key, item in value.items():
                if str(key) in keys:
                    found.setdefault(str(key), []).append(item)
                walk(item)
        elif isinstance(value, list):
            for item in value:
                walk(item)

    walk(document)
    return found


def _environment_problems(document: Mapping[Any, Any]) -> list[str]:
    """What ``environment.yaml`` lacks of the facts a UniKix record is
    checked against: ``run_date`` mapping ``bpe_job_start_date`` and
    ``first_step_screen_date`` each to a date or null, the installation and
    deployment paths as non-empty text, and the output of every version
    probe as non-empty text (a failed probe is recorded as its failure text)."""
    problems = []
    if not document:
        problems.append("it records no fact")
    run_date = document.get("run_date")
    if not isinstance(run_date, Mapping):
        got = "nothing" if run_date is None else type(run_date).__name__
        problems.append(f"run_date must map {' and '.join(_RUN_DATE_KEYS)}, got {got}")
    else:
        absent = [key for key in _RUN_DATE_KEYS if key not in run_date]
        if absent:
            problems.append(f"run_date lacks {', '.join(absent)}")
        problems.extend(
            f"run_date.{key}: {_shown(run_date[key])} is not a date"
            for key in _RUN_DATE_KEYS
            if run_date.get(key) is not None and _date_text(run_date[key]) is None
        )
    recorded = _recorded(document, _INSTALLATION_FACTS + _DEPLOYMENT_FACTS + UNIKIX_VERSION_KEYS)
    for label, keys in (("installation facts", _INSTALLATION_FACTS), ("deployment facts", _DEPLOYMENT_FACTS)):
        unrecorded = [key for key in keys
                      if not any(isinstance(value, str) and value.strip() for value in recorded.get(key, ()))]
        if unrecorded:
            problems.append(f"{label} {', '.join(unrecorded)} not recorded as non-empty text")
    unprobed = [key for key in UNIKIX_VERSION_KEYS
                if not any(isinstance(value, str) and value.strip() for value in recorded.get(key, ()))]
    if unprobed:
        problems.append(f"version probes {', '.join(unprobed)} not recorded as non-empty text (a failed "
                        f"probe is recorded as its failure text)")
    return problems


def _read_environment(scenario_dir: Path) -> tuple[dict | None, str | None]:
    """The ``environment.yaml`` of a scenario's evidence: the one in its own
    directory, where ``adapters/unikix/evidence.py`` writes it, else one
    beside the scenario directories that covers the whole execution. It is
    refused, every problem named, unless it records the run date and the
    installation, deployment and version facts (``_environment_problems``)."""
    path = scenario_dir / ENVIRONMENT_FILE
    try:
        if not path.exists() and not path.is_symlink():
            path = scenario_dir.parent / ENVIRONMENT_FILE
        if path.is_symlink() or not path.is_file():
            return None, f"no {ENVIRONMENT_FILE} in {display_path(scenario_dir)} or its execution directory"
        document = load_yaml(path, max_bytes=EVIDENCE_YAML_MAX_BYTES)
    except OSError as exc:
        return None, f"cannot read {_os_problem(exc, path)}"
    except YAMLLoadError as exc:
        return None, str(exc)
    if not isinstance(document, Mapping):
        return None, f"{display_path(path)} does not hold a mapping"
    problems = _environment_problems(document)
    if problems:
        return None, f"{display_path(path)}: " + "; ".join(problems)
    return dict(document), None


def _evidence_datasets(scenario_dir: Path, raw: Mapping[str, Any]) -> dict[str, bytes]:
    """The bytes of every ``datasets/*.bin`` file of the evidence, under the
    name ``capture_summary.datasets`` gives it back; ``ReportError`` naming
    the folder or file that cannot be read."""
    folder = scenario_dir / EVIDENCE_DATASETS_DIR
    summary = raw.get("capture_summary")
    names = summary.get("datasets") if isinstance(summary, Mapping) else None
    names = names if isinstance(names, Mapping) else {}
    datasets = {}
    try:
        if folder.is_symlink() or not folder.is_dir():
            return {}
        for entry in sorted(folder.iterdir()):
            if entry.is_symlink() or not entry.is_file() or entry.suffix != ".bin":
                continue
            name = names.get(entry.name, names.get(entry.stem))
            if not isinstance(name, str):
                name = _OBSERVATION_FILES.get(entry.stem, entry.stem)
                if name.startswith("_"):
                    continue  # an @AIX:<path> file whose name the summary does not give back
            datasets[name] = entry.read_bytes()
    except OSError as exc:
        raise ReportError(f"cannot read {_os_problem(exc, folder)}") from None
    return datasets


def _shown(value: Any) -> str:
    """``value`` as a problem quotes it: its ``repr``, clipped."""
    return _clip(repr(value), 80)


def _text_problem(entry: Any) -> str | None:
    return None if isinstance(entry, str) else f"must be text, got {type(entry).__name__}"


def _mapping_problem(entry: Any) -> str | None:
    return None if isinstance(entry, Mapping) else f"must be a mapping, got {type(entry).__name__}"


def _screen_problem(screen: Any) -> str | None:
    """Why ``screen`` is not one step's screen of a ``Result``: a mapping of
    field names to text."""
    if not isinstance(screen, Mapping):
        return f"must map field names to text, got {type(screen).__name__}"
    for name, value in screen.items():
        if not isinstance(name, str):
            return f"must map field names to text, got the field name {_shown(name)}"
        if not isinstance(value, str):
            return f"must map field names to text, got {_clip(name, 80)}: {type(value).__name__}"
    return None


def _entries_problem(what: str, value: Any, kind: str, entry_problem: Callable[[Any], str | None]) -> str | None:
    """Why ``value`` is not a list of ``kind`` whose every entry
    ``entry_problem`` accepts, naming the first refused entry and how many
    more there are; ``None`` when it is. A text is refused as a whole, never
    read as a list of its characters."""
    if not isinstance(value, list):
        return f"{what}: must be a list of {kind}, got {type(value).__name__}"
    refused = []
    for index, entry in enumerate(value):
        problem = entry_problem(entry)
        if problem is not None:
            refused.append((index, problem))
    if not refused:
        return None
    index, problem = refused[0]
    more = f" (and {len(refused) - 1} more entries)" if len(refused) > 1 else ""
    return f"{what}[{index}]: {problem}{more}"


def _evidence_problems(raw: Mapping[Any, Any]) -> list[str]:
    """What is wrong with what ``result.yaml`` holds beside its record
    fields: a key ``adapters/unikix/evidence.py`` does not write, an
    ``observed`` payload that is not the run's ``Result`` (exactly
    ``return_code``, an integer or null for abended; ``messages``, a list of
    text; ``screens``, a list of mappings of field names to text), or a
    ``capture_summary``, ``observed_messages`` or ``screens_raw`` of another
    shape. An absent or null ``observed`` is a run that produced no
    ``Result``. Nothing is coerced or dropped: corrupt evidence is refused."""
    problems = []
    unknown = sorted(
        key if isinstance(key, str) else _shown(key) for key in raw
        if not isinstance(key, str) or (key not in _results.RECORD_FIELDS and key not in _EVIDENCE_KEYS)
    )
    if unknown:
        problems.append(f"holds {_clip(', '.join(unknown), 300)}, outside the record fields and "
                        f"{', '.join(_EVIDENCE_KEYS)}")
    observed = raw.get("observed")
    if isinstance(observed, Mapping):
        absent = [key for key in _OBSERVED_KEYS if key not in observed]
        extra = sorted(key if isinstance(key, str) else _shown(key) for key in observed if key not in _OBSERVED_KEYS)
        if absent:
            problems.append(f"observed: lacks {', '.join(absent)}")
        if extra:
            problems.append(f"observed: holds {_clip(', '.join(extra), 300)}, outside {', '.join(_OBSERVED_KEYS)}")
        code = observed.get("return_code")
        if code is not None and (isinstance(code, bool) or not isinstance(code, int)):
            problems.append(f"observed.return_code: {_shown(code)} is not an integer or null")
        for name, kind, entry_problem in (("messages", "text", _text_problem),
                                          ("screens", "field mappings", _screen_problem)):
            if name in observed:
                problem = _entries_problem(f"observed.{name}", observed[name], kind, entry_problem)
                if problem is not None:
                    problems.append(problem)
    elif observed is not None:
        problems.append(f"observed: must map {', '.join(_OBSERVED_KEYS)}, got {type(observed).__name__}")
    if "capture_summary" in raw:
        summary = raw["capture_summary"]
        names = summary.get("datasets", {}) if isinstance(summary, Mapping) else None
        if not isinstance(summary, Mapping):
            problems.append(f"capture_summary: must be a mapping, got {type(summary).__name__}")
        elif not isinstance(names, Mapping) or not all(
                isinstance(file_name, str) and isinstance(name, str) for file_name, name in names.items()):
            problems.append(f"capture_summary.datasets: must map {EVIDENCE_DATASETS_DIR}/ file names to "
                            f"dataset names")
    for name in ("observed_messages", "screens_raw"):
        if name in raw:
            problem = _entries_problem(name, raw[name], "mappings", _mapping_problem)
            if problem is not None:
                problems.append(problem)
    return problems


def read_evidence(execution_id: str, scenario_dir: Path,
                  zip_info: tuple[str, dict] | None) -> tuple[Evidence | None, str | None]:
    """The evidence of one scenario directory, or why it is rejected: an id
    outside the manifest, no complete ``result.yaml``, an ``environment.yaml``
    that is missing, unreadable or lacks a fact it must record, a
    ``result.yaml`` larger than ``EVIDENCE_YAML_MAX_BYTES``, a record that
    breaks the result contract or names another execution, scenario or
    adapter, a key or ``observed`` payload ``adapters/unikix/evidence.py``
    does not write (``_evidence_problems``), a file the importer cannot read,
    or a source identity that is not the zip's UniKix copy as committed."""
    scenario_id = scenario_dir.name
    if scenario_id not in _scenarios.ALL_IDS:
        return None, f"{scenario_id} is not an id of the closed scenario id manifest"
    path = scenario_dir / RESULT_FILE
    try:
        if path.is_symlink() or not path.is_file():
            return None, f"no {RESULT_FILE}: the evidence is incomplete"
    except OSError as exc:
        return None, f"cannot read {_os_problem(exc, path)}"
    environment, problem = _read_environment(scenario_dir)
    if environment is None:
        return None, f"environment: {problem}"
    try:
        raw = load_yaml(path, max_bytes=EVIDENCE_YAML_MAX_BYTES)
    except OSError as exc:
        return None, f"cannot read {_os_problem(exc, path)}"
    except YAMLLoadError as exc:
        return None, str(exc)
    if not isinstance(raw, Mapping):
        return None, f"{RESULT_FILE} does not hold a mapping"
    record = {name: raw.get(name) for name in _results.RECORD_FIELDS}
    problems = _results.record_problems(record) + _evidence_problems(raw)
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
    try:
        datasets = _evidence_datasets(scenario_dir, raw)
    except ReportError as exc:
        return None, str(exc)
    return Evidence(execution_id, scenario_id, scenario_dir, dict(raw), environment, datasets), None


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


def _run_date_key(scenario: Any) -> tuple[str, str]:
    """The ``run_date`` key of ``environment.yaml`` that holds the run date
    of the scenario's kind of run, and that kind: ``first_step_screen_date``
    for an online scenario (``inputs.steps``), else ``bpe_job_start_date``."""
    if isinstance(scenario.inputs.get("steps"), list):
        return "first_step_screen_date", "an online run"
    return "bpe_job_start_date", "a batch run"


def evidence_run_date(environment: Mapping[str, Any], scenario: Any) -> str | None:
    """The run date ``environment.yaml`` records for the scenario's kind of
    run, or ``None``: the screen date of the first online step for an online
    scenario, the BPE job start date for a batch one. The other kind's date
    is never used, because relative-clock matchers are judged against the
    run date of the run that produced the values."""
    run_date = environment.get("run_date")
    if not isinstance(run_date, Mapping):
        return None
    key, _kind = _run_date_key(scenario)
    return _date_text(run_date.get(key))


def _holds_matcher(value: Any) -> bool:
    """Whether ``value`` holds a matcher at any depth."""
    if _baselines.is_matcher(value):
        return True
    if isinstance(value, Mapping):
        return any(_holds_matcher(item) for item in value.values())
    if isinstance(value, list):
        return any(_holds_matcher(item) for item in value)
    return False


def _run_date_problem(evidence: Evidence, scenario: Any, baseline: Any) -> str | None:
    """Why the evidence cannot be judged for want of a run date, or ``None``:
    a matcher anywhere in the scenario's or the baseline's current expectation
    (its ``matchers`` map included) relates a value to the run date, which
    ``environment.yaml`` must then record for the scenario's kind of run."""
    expectations = [scenario.expected] + ([baseline.expected] if baseline is not None else [])
    if not any(_holds_matcher(expected) for expected in expectations):
        return None
    if evidence_run_date(evidence.environment, scenario) is not None:
        return None
    key, kind = _run_date_key(scenario)
    return (f"{scenario.id}: its expected outcome relates values to the run date, but the "
            f"{ENVIRONMENT_FILE} of {evidence.path} does not record {key}, the run date of {kind}")


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


def evidence_result(evidence: Evidence) -> Result:
    """The ``adapters.base.Result`` an execution produced, rebuilt from its
    evidence exactly as captured: the return code, messages and screens
    ``result.yaml`` keeps under ``observed`` (``read_evidence`` has refused
    any other shape), and the raw bytes of every dataset file. Nothing is
    coerced, dropped or derived from the expectation or the run date, so a
    comparison judges only what the run produced. ``ReportError`` when the
    evidence holds no ``observed`` payload: no outcome can be compared."""
    observed = evidence.observed
    if observed is None:
        raise ReportError(f"{evidence.path}: {RESULT_FILE} holds no observed return code, messages and "
                          f"screens to compare")
    try:
        return Result(
            return_code=observed["return_code"],
            datasets=dict(evidence.datasets),
            screens=[dict(screen) for screen in observed["screens"]],
            messages=list(observed["messages"]),
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise ReportError(f"{evidence.path}: {RESULT_FILE}'s observed is not a run's Result: {exc}") from None


def _asserted_steps(scenario: Any, baseline: Any) -> list[tuple[list[str], list[Any]]]:
    """The screen steps a comparison asserts, one ``(names, maps)`` per step:
    the field names the baseline's and the scenario's step name (``@map`` and
    ``@text`` included) and the ``@map`` values they give. The steps are those
    of the longer of the two ``screens`` lists, a scenario value ``baseline``
    deferring to the baseline's; none when the scenario's ``expected`` names
    no ``screens``."""
    if "screens" not in scenario.expected:
        return []
    specs = []
    for expected in (baseline.expected if baseline is not None else {}, scenario.expected):
        screens = expected.get("screens") if isinstance(expected, Mapping) else None
        if isinstance(screens, list):
            specs.append(screens)
    steps = []
    for index in range(max((len(spec) for spec in specs), default=0)):
        names: list[str] = []
        maps: list[Any] = []
        for spec in specs:
            step = spec[index] if index < len(spec) and isinstance(spec[index], Mapping) else {}
            names.extend(str(name) for name in step if str(name) not in names)
            if _scenarios.SCREEN_MAP in step and step[_scenarios.SCREEN_MAP] not in maps:
                maps.append(step[_scenarios.SCREEN_MAP])
        steps.append((names, maps))
    return steps


def _step_shortfall(screen: Any, names: Sequence[str], maps: Sequence[Any]) -> str | None:
    """What a captured step (``None`` when the evidence has no screen for
    it) lacks of the ``names`` its asserted step names, as a skip reason
    shows it (``ERRMSG, PGMNAME``), or ``None`` when it holds them all. A
    capture of a map other than the ``maps`` the step asserts lacks the
    asserted names it does not hold like any other capture, and the reason
    names both maps: ``ERRMSG, PGMNAME (captured @map TEXT, asserted
    COBIL00)``."""
    if screen is None or (isinstance(screen, Mapping) and _baselines.MISSING in screen):
        return "(no screen was captured)"
    if not isinstance(screen, Mapping):
        return "(the capture is not a field map)"
    absent = sorted(name for name in names if name not in screen)
    if not absent:
        return None
    text = ", ".join(absent)
    captured = screen.get(_scenarios.SCREEN_MAP)
    if maps and _scenarios.SCREEN_MAP in screen and captured not in maps:
        text += f" (captured @map {captured}, asserted {', '.join(str(mapset) for mapset in maps)})"
    return text


def missing_observations(evidence: Evidence, scenario: Any, baseline: Any) -> list[str]:
    """What the scenario's current ``expected`` names that the evidence does
    not hold. Only the captured ``observed`` payload and the dataset files
    count, never the decoded projection: evidence without ``observed`` holds
    nothing a comparison can judge. Screens are checked field by field:
    every step the scenario or its baseline asserts (``_asserted_steps``)
    must be captured and hold every name either of them names, ``@map`` and
    ``@text`` included, whatever map the capture shows. A shortfall is named
    by step and field: ``screens step 3 ERRMSG, PGMNAME``, with the captured
    and the asserted map where they differ."""
    expected = scenario.expected
    outcome = evidence.observed
    if outcome is None:
        return ["observed (the run's return code, messages and screens)"]
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
    captured = outcome.get("screens")
    captured = captured if isinstance(captured, list) else []
    for index, (asserted, maps) in enumerate(_asserted_steps(scenario, baseline)):
        shortfall = _step_shortfall(captured[index] if index < len(captured) else None, asserted, maps)
        if shortfall is not None:
            missing.append(f"screens step {index + 1} {shortfall}")
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


def _evidence_file(evidence: Evidence, name: str) -> Path:
    """The file ``name`` of the evidence's own scenario directory, once the
    checks every write or removal of it must pass have passed:
    ``_evidence_folder`` names any linked folder from the resolved ``tests/``
    root down or a folder resolving outside the evidence tree, the directory
    is the evidence directory of its execution and scenario, and the file is
    neither a symbolic link nor, when it exists, anything but a regular file.
    Anything else raises ``ReportError``, before anything is written."""
    _evidence_folder(evidence.execution_id, evidence.scenario_id)
    if evidence.directory != UNIKIX_EVIDENCE_DIR / evidence.execution_id / evidence.scenario_id:
        raise ReportError(f"{display_path(evidence.directory)} is not the evidence directory of "
                          f"{evidence.execution_id}/{evidence.scenario_id}; nothing is written there")
    path = evidence.directory / name
    try:
        info = path.lstat()
    except FileNotFoundError:
        return path
    except OSError as exc:
        raise ReportError(f"{display_path(path)} cannot be examined: {exc.strerror or exc}") from None
    if stat.S_ISLNK(info.st_mode):
        raise ReportError(f"{display_path(path)} is a symbolic link; nothing is written through it")
    if not stat.S_ISREG(info.st_mode):
        raise ReportError(f"{display_path(path)} is not a regular file; nothing is written to it")
    return path


def _in_evidence_folder(evidence: Evidence, name: str, action: Callable[[int, Path], None], doing: str) -> None:
    """Run ``action(folder, path)`` on the file ``name`` of the evidence's own
    scenario directory without following a symbolic link. ``validation.yaml``
    and, while a promotion is in progress, its journal are the only files
    ever written under the evidence tree, and only so: ``_evidence_file``
    first checks the folders and the file, then each folder from the
    resolved ``tests/`` root down is opened relative to its parent with
    ``O_NOFOLLOW``, so a link swapped in meanwhile fails instead of being
    followed, and ``action`` gets the last one open as ``folder``. An
    ``OSError`` raises ``ReportError`` naming the component, with ``doing``
    (``written``, ``removed``) saying what could not be done."""
    path = _evidence_file(evidence, name)
    current = TESTS_ROOT
    try:
        folder = os.open(TESTS_ROOT, _FOLDER_FLAGS)
        try:
            for part in evidence.directory.relative_to(TESTS_ROOT).parts:
                current = current / part
                parent, folder = folder, os.open(part, _FOLDER_FLAGS | os.O_NOFOLLOW, dir_fd=folder)
                os.close(parent)
            current = path
            action(folder, path)
        finally:
            os.close(folder)
    except OSError as exc:
        if os.path.islink(current):
            raise ReportError(f"{display_path(current)} is a symbolic link; nothing is {doing} through it") from None
        raise ReportError(f"{display_path(current)} cannot be {doing}: {exc.strerror or exc}") from None


def _write_evidence_file(evidence: Evidence, name: str, data: bytes) -> None:
    """Make the file ``name`` of the evidence's own scenario directory hold
    ``data``, replaced inside that folder (``_replace_file``) without
    following a symbolic link (``_in_evidence_folder``)."""
    _in_evidence_folder(evidence, name, lambda folder, path: _replace_file(folder, name, data, path), "written")


def _remove_evidence_file(evidence: Evidence, name: str) -> None:
    """Remove the file ``name`` of the evidence's own scenario directory,
    unlinked inside that folder without following a symbolic link
    (``_in_evidence_folder``); a file already gone is left so."""
    def remove(folder: int, path: Path) -> None:
        try:
            os.unlink(name, dir_fd=folder)
        except FileNotFoundError:
            pass

    _in_evidence_folder(evidence, name, remove, "removed")


def _write_validation(evidence: Evidence, record: Mapping[str, Any]) -> None:
    """Write ``validation.yaml`` into the evidence's own scenario directory
    without following a symbolic link (``_write_evidence_file``)."""
    _write_evidence_file(evidence, VALIDATION_FILE, dump_yaml(dict(record)).encode("utf-8"))


def _judge(evidence: Evidence, scenario: Any, baseline: Any) -> tuple[str, str | None, str, dict | None]:
    """Compare the evidence's observed payload with the baseline as it stands:
    ``(status, reason, diff, decoded projection)``."""
    run_date = evidence_run_date(evidence.environment, scenario)
    result = evidence_result(evidence)
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
    """The field names expected records name, in first-seen order; ``None``
    for lines, which are compared whole. A matcher stands for a whole record
    or line and names no field."""
    names: list[str] = []
    field_maps = False
    for value in values:
        if _baselines.is_matcher(value):
            continue
        if not isinstance(value, Mapping):
            return None  # line records (TEXT layouts, line observations) are compared whole
        field_maps = True
        names.extend(str(name) for name in value if str(name) not in names)
    return names if field_maps else None


class _RunDateUnknown(ReportError):
    """A matcher of an expectation being promoted has to be judged, and the
    evidence records no run date to relate the observed value to."""


def _satisfied(matcher: Any, value: Any, run_date: str | None) -> bool:
    """Whether the observed ``value`` satisfies ``matcher``: its format and
    its stated relation to the evidence's run date. A malformed matcher is
    not satisfied. ``_RunDateUnknown`` when the evidence records no run date:
    the relation cannot be judged, and taking the observed value instead
    would promote a clock value as a literal."""
    try:
        if run_date is not None:
            return _baselines.check_matcher(matcher, value, None, False, run_date) is None
        described = _baselines.Matcher.of(matcher).describe()
    except HarnessError:
        return False
    raise _RunDateUnknown(f"the clock matcher {described} cannot be judged against the observed "
                          f"{_clip(repr(value), 80)}")


def _spec_items(spec: Any, exact: bool) -> tuple[list, list]:
    """The items of a list expectation that align with the observed records
    by position (``records``, and a plain list compared exactly) and those
    that align in order (``contains``, and a plain list compared as an
    ordered subsequence)."""
    if isinstance(spec, list):
        return (list(spec), []) if exact else ([], list(spec))
    if isinstance(spec, Mapping) and not _baselines.is_matcher(spec):
        records, contains = spec.get("records"), spec.get("contains")
        return (list(records) if isinstance(records, list) else [],
                list(contains) if isinstance(contains, list) else [])
    return [], []


def _gives_matchers(item: Any) -> bool:
    """Whether the expected record or line ``item`` gives a matcher: is one
    (a whole line), or holds one as a field value."""
    return _baselines.is_matcher(item) or (
        isinstance(item, Mapping) and any(_baselines.is_matcher(value) for value in item.values()))


def _clock_match(item: Any, actual: Any, run_date: str | None) -> bool:
    """Whether the expected record or line ``item`` gives at least one
    matcher and the observed ``actual`` satisfies every one it gives."""
    if _baselines.is_matcher(item):
        return isinstance(actual, str) and _satisfied(item, actual, run_date)
    if not isinstance(item, Mapping) or not isinstance(actual, Mapping):
        return False
    matchers = [(str(name), value) for name, value in item.items() if _baselines.is_matcher(value)]
    return bool(matchers) and all(name in actual and _satisfied(value, actual[name], run_date)
                                  for name, value in matchers)


def _overlay_matchers(records: list, observed: Sequence[Any], spec: Any, exact: bool,
                      wildcards: Iterable[str], run_date: str | None) -> None:
    """Carry into ``records`` (the observed records or lines being promoted)
    each matcher the expectation ``spec`` gives for them, where none stands
    yet and the observed value satisfies it. Positional items (``records``,
    a plain list compared exactly) align by position. Ordered items
    (``contains``, a plain list compared as an ordered subsequence) align
    greedily in order, as the comparison matches them: each takes, at or
    after the record past the last one aligned, the first record that
    matches it loosely (``baselines._loose_match``: its matchers and the
    field-level ``wildcards`` match anything) and satisfies every matcher it
    gives; else, when it gives matchers, the first record that satisfies them
    all, the one the run wrote where its other values changed; else the first
    loose match, whose unsatisfied matchers are not carried. An item no
    record matches leaves the position where it is. A whole-line matcher
    replaces a line, a field matcher its field."""
    if spec is None or spec == _scenarios.BASELINE:
        return
    positional, ordered = _spec_items(spec, exact)
    aligned = list(enumerate(positional[:len(observed)]))
    loose = list(wildcards)
    position = 0
    for item in ordered:
        candidates = range(position, len(observed))
        clocked = _gives_matchers(item)
        index = next((index for index in candidates if _baselines._loose_match(item, observed[index], loose)
                      and (not clocked or _clock_match(item, observed[index], run_date))), None)
        if index is None and clocked:
            index = next((index for index in candidates if _clock_match(item, observed[index], run_date)), None)
        if index is None:
            index = next((index for index in candidates
                          if _baselines._loose_match(item, observed[index], loose)), None)
        if index is not None:
            aligned.append((index, item))
            position = index + 1
    for index, item in aligned:
        actual, record = observed[index], records[index]
        if _baselines.is_matcher(item):
            if isinstance(actual, str) and isinstance(record, str) and _satisfied(item, actual, run_date):
                records[index] = dict(item)
        elif isinstance(item, Mapping) and isinstance(record, dict) and isinstance(actual, Mapping):
            for name, value in item.items():
                name = str(name)
                if (_baselines.is_matcher(value) and name in record and not _baselines.is_matcher(record[name])
                        and _satisfied(value, actual.get(name), run_date)):
                    record[name] = dict(value)


def _section_spec(expected: Mapping[str, Any], section: str, name: Any) -> Any:
    """The value ``expected`` gives the ``datasets``/``file_state`` entry
    ``name``, or ``None`` when it gives none."""
    values = expected.get(section)
    return values.get(name) if isinstance(values, Mapping) else None


def _asserted_fields(stored: Any, own: Any) -> tuple[list, list[str] | None]:
    """The expectations of one dataset that give records, the baseline's
    (``stored``) first and then the scenario's (``own``), leaving out an
    absent value and ``baseline``; and the field names their records name
    (``_named_fields``: ``None`` for lines)."""
    specs = [spec for spec in (stored, own) if spec is not None and spec != _scenarios.BASELINE]
    items: list[Any] = []
    for spec in specs:
        positional, ordered = _spec_items(spec, True)
        items.extend(positional + ordered)
    return specs, _named_fields(items)


def _restricted_records(observed: Any, stored: Any, own: Any, top_matchers: Iterable[str],
                        run_date: str | None, where: str) -> Any:
    """The observed records of one dataset as its promoted expectation,
    restricted to the fields the baseline's and the scenario's expectations
    (``stored``, ``own``) name, with every record or field matcher they give
    carried over where the observed value satisfies it, the baseline's first
    (``_overlay_matchers``). ``observed`` holds the records as the comparison
    reads those fields (``baselines.asserted_records``): a decoded field, a
    group, a field path, one of the dataset's own fields or ``@raw``, each
    kept under the name the expectation gives it. The value keeps the form of
    the expectation it replaces, and is ``{records: ...}`` whenever the
    scenario holds its own value, which its schema takes only as a mapping;
    that form keeps every field-level ``matchers`` entry either expectation
    held, the baseline's where both name a field. ``ReportError`` when a
    record denotes no field by a name the expectations give; ``_RunDateUnknown``
    when a matcher applies to a promoted record and ``run_date`` is ``None``."""
    if not isinstance(observed, list):
        return observed
    specs, fields = _asserted_fields(stored, own)
    records: list[Any] = []
    for number, record in enumerate(observed, start=1):
        if not isinstance(record, Mapping):
            records.append(record)
            continue
        if not fields:
            records.append(dict(record))
            continue
        absent = [name for name in fields if name not in record]
        if absent:
            raise ReportError(f"{where} record {number} has no field {', '.join(absent)} as the comparison reads "
                              f"it (a decoded field, a group, a field path, one of the dataset's own fields or "
                              f"{_baselines.RAW_KEY}), which the expectation names, so its observed value cannot be "
                              f"promoted")
        records.append({name: record[name] for name in fields})
    field_level = [
        {str(name): matcher for name, matcher in spec["matchers"].items() if _baselines.is_matcher(matcher)}
        if isinstance(spec, Mapping) and isinstance(spec.get("matchers"), Mapping) else {}
        for spec in specs
    ]
    for spec, spec_matchers in zip(specs, field_level):
        _overlay_matchers(records, observed, spec, True, set(top_matchers) | set(spec_matchers), run_date)
    previous = specs[0] if specs else None
    if not isinstance(previous, Mapping) and not isinstance(own, Mapping):
        return records
    value: dict[str, Any] = {"records": records}
    matchers: dict[str, Any] = {}
    for spec_matchers in field_level:
        for name, matcher in spec_matchers.items():
            matchers.setdefault(name, dict(matcher))
    if matchers:
        if run_date is None:
            # The comparison judges a field-level matcher on every record that
            # holds its field; without a run date the first such judgement
            # raises _RunDateUnknown (a malformed matcher is left to the check).
            for name, matcher in matchers.items():
                held = [record[name] for record in records if isinstance(record, Mapping) and name in record]
                if held:
                    _satisfied(matcher, held[0], run_date)
        value["matchers"] = matchers
    return value


# How the online programs show the screen clock fields: CSDAT01Y's
# WS-CURDATE-MM-DD-YY and WS-CURTIME-HH-MM-SS.
_SCREEN_DEFAULT_FORMATS = {"CURDATE": "%m/%d/%y", "CURTIME": "%H:%M:%S"}


def _screen_value(scenario: Any, baseline: Any, index: int, name: str, value: Any, run_date: str | None) -> Any:
    """A captured screen field as its promoted expectation: the matcher the
    step or a top-level ``matchers`` entry gives it, else for a screen clock
    field the matcher of its kind (``CURDATE`` ``{match: run-date, format:
    '%m/%d/%y'}``, ``CURTIME`` ``{match: run-time}``), where the observed
    value satisfies it; otherwise the observed value."""
    matcher = _step_matcher(scenario, baseline, index, name)
    kind = _baselines.SCREEN_CLOCK_FIELDS.get(name)
    if matcher is None and kind is not None:
        matcher = {"match": kind}
        if _SCREEN_DEFAULT_FORMATS[name] != _baselines.MATCHERS[kind]:
            matcher["format"] = _SCREEN_DEFAULT_FORMATS[name]
    if matcher is not None and _satisfied(matcher, value, run_date):
        return dict(matcher)
    return value


def _observed_screens(scenario: Any, baseline: Any, screens: Any, run_date: str | None) -> list[dict]:
    """The captured screens as the promoted ``screens``: one entry per step
    the scenario or its baseline asserts (``_asserted_steps``), holding
    exactly the names either names. ``ReportError`` when a step lacks an
    asserted name, whatever map it captured (``_step_shortfall``): no
    asserted field is ever dropped or replaced by another."""
    captured = screens if isinstance(screens, list) else []
    steps = []
    for index, (names, maps) in enumerate(_asserted_steps(scenario, baseline)):
        screen = captured[index] if index < len(captured) else None
        shortfall = _step_shortfall(screen, names, maps)
        if shortfall is not None:
            raise ReportError(f"{scenario.id}: the evidence lacks screens step {index + 1} {shortfall}, which the "
                              f"expectation asserts, so no observed expectation replaces it")
        steps.append({name: _screen_value(scenario, baseline, index, name, screen[name], run_date) for name in names})
    return steps


def _check_observed(scenario: Any, baseline: Any, expected: Mapping[str, Any], result: Result,
                    run_date: str | None) -> None:
    """Refuse an observed expectation before anything is written: one that
    breaks the baseline contract, gives the scenario file a schema problem it
    does not have, or does not reproduce, through the comparison a live run
    uses, the observed outcome it was built from."""
    inline = _inline_expectation(scenario, expected)
    candidate = replace(scenario, expected=inline, data={**scenario.data, "expected": inline})
    try:
        promoted = _baselines.Baseline(scenario.id, dict(expected), baseline.manifest, {})
    except HarnessError as exc:
        raise ReportError(f"{scenario.id}: the observed expectation breaks the baseline contract: "
                          f"{_clip(exc)}") from None
    known = set(_scenarios.validate(scenario))
    added = [problem for problem in _scenarios.validate(candidate) if problem not in known]
    if added:
        raise ReportError(f"{scenario.id}: the observed expectation would break the scenario schema: "
                          + _clip("; ".join(added)))
    status, reason, diff = _baselines.compare(candidate, promoted, result, _results.UNIKIX, run_date)
    if status != _results.STATUS_PASSED:
        raise ReportError(f"{scenario.id}: the observed expectation does not reproduce the observed outcome "
                          f"({reason}), so nothing is promoted: {_clip(diff)}")


def observed_expectation(evidence: Evidence, scenario: Any, baseline: Any) -> dict:
    """The observed outcome of ``evidence`` as an ``expected.yaml``, built
    from the run's own observations (``evidence_result``, decoded), never from
    the normalized projection: exactly the keys the scenario's ``expected``
    names; each dataset's records restricted to the fields the old
    expectations named, each read under its name as the comparison reads it
    (``baselines.asserted_records``: a decoded field, a group, a field path,
    one of the dataset's own fields or ``@raw``); every screen step they
    assert, with every field they name; the union of their top-level
    ``matchers``, or the baseline's alone when the scenario names no
    ``matchers`` key; and each other matcher they held, or a screen clock
    field's, wherever the observed value satisfies it. The expectation is
    checked against the observed outcome before it is returned
    (``_check_observed``).
    ``ReportError`` when the evidence keeps no observed payload or lacks an
    asserted observation, when a matcher has to be judged and the evidence
    records no run date (``evidence_run_date``), or when the check fails.
    Nothing is written."""
    if evidence.observed is None:
        raise ReportError(f"{scenario.id}: {evidence.path}/{RESULT_FILE} keeps no observed payload, only its "
                          f"normalized projection, so no observed outcome can replace the expectation")
    run_date = evidence_run_date(evidence.environment, scenario)
    result = evidence_result(evidence)
    old = baseline.expected if baseline is not None else {}
    inline = scenario.expected
    # The field names each dataset's old expectations give its records, read
    # from the observed records as the comparison reads them.
    names = {
        (section, str(name)): _asserted_fields(_section_spec(old, section, name), value)[1]
        for section in ("datasets", "file_state") if isinstance(inline.get(section), Mapping)
        for name, value in inline[section].items()
    }
    try:
        decoded = _baselines.decode_result(scenario, result)
        asserted = _baselines.asserted_records(scenario, result, names)
    except HarnessError as exc:
        raise ReportError(f"{scenario.id}: the observed outcome cannot be decoded: {_clip(exc, 300)}") from None
    top_matchers = {
        str(name) for expected in (old, inline) if isinstance(expected.get("matchers"), Mapping)
        for name, matcher in expected["matchers"].items() if _baselines.is_matcher(matcher)
    }
    out: dict[str, Any] = {}
    try:
        for key in inline:
            if key == "matchers":
                # The baseline's matcher where both name a field, as the
                # comparison applies them; an entry that is no matcher, and a
                # scenario's 'baseline' in place of the map, add nothing.
                merged: dict[str, Any] = {}
                for expected in (old, inline):
                    held = expected.get("matchers")
                    if isinstance(held, Mapping):
                        for name, matcher in held.items():
                            if _baselines.is_matcher(matcher):
                                merged.setdefault(str(name), dict(matcher))
                out[key] = merged
            elif key in ("return_code", "reject_reason_codes"):
                out[key] = decoded.get(key)
            elif key == "messages":
                lines = decoded.get(key)
                if isinstance(lines, list):
                    lines = list(lines)
                    _overlay_matchers(lines, decoded[key], old.get(key), True, (), run_date)
                    _overlay_matchers(lines, decoded[key], inline.get(key), False, (), run_date)
                out[key] = lines
            elif key in ("datasets", "file_state"):
                own = inline[key] if isinstance(inline[key], Mapping) else {}
                out[key] = {
                    str(name): _restricted_records(asserted.get((key, str(name))), _section_spec(old, key, name),
                                                   value, top_matchers, run_date, f"{scenario.id}: {key}.{name}")
                    for name, value in own.items()
                }
            elif key == "screens":
                out[key] = _observed_screens(scenario, baseline, decoded.get(key), run_date)
    except _RunDateUnknown as exc:
        run_date_key, kind = _run_date_key(scenario)
        raise ReportError(f"{scenario.id}: the evidence records no run date for this scenario (its "
                          f"{ENVIRONMENT_FILE} run_date does not record {run_date_key}, the run date of {kind}), "
                          f"so {exc}, and nothing is promoted") from None
    stored_matchers = old.get("matchers")
    if "matchers" not in inline and isinstance(stored_matchers, Mapping):
        # A top-level map the scenario does not name, which the baseline
        # contract allows: the comparison applies it to every record and
        # screen field it names, so the promoted baseline keeps its matchers
        # and those fields keep their clock relation.
        kept = {str(name): dict(matcher) for name, matcher in stored_matchers.items()
                if _baselines.is_matcher(matcher)}
        if kept:
            out["matchers"] = kept
    _check_observed(scenario, baseline, out, result, run_date)
    return out


def _inline_expectation(scenario: Any, observed: Mapping[str, Any]) -> dict:
    """The scenario's own ``expected`` after promotion: the observed values,
    except where it defers to the baseline. Its messages are the observed
    lines no matcher stands for: the scenario schema takes message lines as
    text only, and the scenario's lines are compared as an ordered
    subsequence, while the baseline keeps every line, matchers included. A
    ``matchers`` map the scenario does not name stays the baseline's alone,
    which the comparison applies to the scenario's values as well. Its
    dataset records leave out the names the scenario schema refuses there
    and only the baseline gave (``_inline_records``)."""
    inline = scenario.expected
    out: dict[str, Any] = {}
    for key, value in observed.items():
        if key == "matchers" and key not in inline:
            continue
        if inline.get(key) == _scenarios.BASELINE:
            out[key] = _scenarios.BASELINE
        elif key == "messages" and isinstance(value, list):
            out[key] = [line for line in value if not _baselines.is_matcher(line)]
        elif key in ("datasets", "file_state") and isinstance(inline.get(key), Mapping):
            out[key] = {
                name: _scenarios.BASELINE if inline[key].get(name) == _scenarios.BASELINE
                else _inline_records(str(name), records, inline[key].get(name))
                for name, records in value.items()
            }
        else:
            out[key] = value
    return out


def _inline_records(dataset: str, value: Any, own: Any) -> Any:
    """The promoted value of the dataset ``dataset`` as the scenario's own
    expectation ``own`` takes it. Each promoted record names every field
    either old expectation named, under the name each gave, while the
    scenario schema refuses ``@raw`` in an expected record and two names of
    one field (a decoded key beside its field path). So a name only the
    baseline gave is left out of each record when it is ``@raw`` or names a
    field the record already holds under another name, the scenario's own
    name first (``baselines.field_identities``); every other name and value
    is kept as promoted."""
    records = value.get("records") if isinstance(value, Mapping) and not _baselines.is_matcher(value) else value
    if not isinstance(records, list):
        return value
    field_maps = [record for record in records if isinstance(record, Mapping) and not _baselines.is_matcher(record)]
    names = sorted({str(name) for record in field_maps for name in record})
    if not names:
        return value
    _specs, given = _asserted_fields(None, own)
    mine = set(given or ())
    identities = _baselines.field_identities(dataset, names)
    kept_records: list[Any] = []
    for record in records:
        if not isinstance(record, Mapping) or _baselines.is_matcher(record):
            kept_records.append(record)
            continue
        held = {identities[str(name)] for name in record if str(name) in mine}
        kept: dict[str, Any] = {}
        for name, field_value in record.items():
            name = str(name)
            if name not in mine:
                if name == _baselines.RAW_KEY or identities[name] in held:
                    continue
                held.add(identities[name])
            kept[name] = field_value
        kept_records.append(kept)
    return {**value, "records": kept_records} if isinstance(value, Mapping) else kept_records


def _business_datasets(expected: Mapping[str, Any]) -> list[str]:
    return sorted({
        str(name) for section in ("datasets", "file_state")
        for name in (expected.get(section) or {}) if not str(name).startswith("@")
    })


# A git commit as git rev-parse prints it (sha1 or sha256), and the UTC time
# stamp of a unikix execution id (adapters/unikix/evidence.make_execution_id).
_COMMIT_RE = re.compile(r"[0-9a-f]{40}|[0-9a-f]{64}")
_EXECUTION_STAMP_RE = re.compile(r"unikix-([0-9]{8}T[0-9]{6}Z)-[0-9a-f]{8}")


def _observed_manifest(baseline: Any, evidence: Evidence, level: str) -> dict:
    """The manifest of a baseline promoted from ``evidence``. Its capture
    facts are the execution's own: ``source_commit`` is the commit its
    ``source_identity`` names and ``capture_date`` the UTC date of its
    execution id, never the importing checkout's HEAD or day. ``ReportError``
    when either cannot be read."""
    identity = evidence.raw.get("source_identity")
    commit = identity.get("commit") if isinstance(identity, Mapping) else None
    if not (isinstance(commit, str) and _COMMIT_RE.fullmatch(commit)):
        raise ReportError(f"{evidence.path}: source_identity.commit {_clip(repr(commit), 80)} is not a git "
                          f"commit, so the promoted baseline cannot name its source commit")
    stamp = _EXECUTION_STAMP_RE.fullmatch(evidence.execution_id) if isinstance(evidence.execution_id, str) else None
    try:
        captured = _dt.datetime.strptime(stamp.group(1), "%Y%m%dT%H%M%SZ").date() if stamp else None
    except ValueError:
        captured = None
    if captured is None:
        raise ReportError(f"{evidence.path}: execution id {evidence.execution_id!r} names no UTC capture time "
                          f"unikix-<yyyymmddThhmmssZ>-<8 hex>, so the promoted baseline has no capture date")
    manifest = {
        "baseline_version": _baselines.bump(baseline.version, level),
        "source_commit": commit,
        "capture_platform": _results.UNIKIX,
        "capture_date": captured.isoformat(),
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


def _comparison_problem(evidence: Evidence, scenario: Any, baseline: Any, reason: str | None, diff: str,
                        projected: Mapping[str, Any] | None) -> str | None:
    """Why the comparison of an eligible record that would promote or diverge
    from ``baseline`` was not made, so that no outcome may be imported from
    it. Missing observations, every asserted screen field included, are
    checked before the comparison (``missing_observations``), and so is the
    run date its clock matchers need (``_run_date_problem``); this guard
    covers a comparison that ended in reason ``error`` (no baseline key,
    bytes that cannot be decoded, a malformed expectation) or gave no
    projection. ``None`` when the comparison stands: passed, or a decoded
    difference."""
    if projected is None or reason not in (None, _baselines.REASON_ASSERTION, _baselines.REASON_DIVERGENCE):
        detail = diff[len(f"{scenario.id}: "):] if diff.startswith(f"{scenario.id}: ") else diff
        return (f"{scenario.id}: the observed outcome cannot be compared with the baseline "
                f"({_clip(' '.join(detail.split()), 500)}); nothing is imported")
    return None


def _validation_path(evidence: Evidence) -> Path:
    """The ``validation.yaml`` of ``evidence``, once the checks its write
    makes before writing have passed (``_evidence_file``). A promotion runs
    it before its first write."""
    return _evidence_file(evidence, VALIDATION_FILE)


# The journal of a promotion in progress, beside the evidence's result.yaml.
# It is written before the promotion's first write and removed after its
# last, so an import that stops in between leaves it, and the next import of
# the execution completes what it records instead of judging again.
PROMOTION_FILE = "promotion.yaml"
_JOURNAL_KEYS = (
    "scenario_id", "execution_id", "from_version", "baseline_version", "level", "how", "expected", "manifest",
    "datasets", "inline_expected", "remove_tag", "gap",
)
_JOURNAL_GAP_KEYS = ("category", "unknown", "needed", "items")
_PROMOTION_LEVELS = {"minor": "observed outcome equals the derived one",
                     "major": "observed outcome replaces the derived one"}


def _journal_path(evidence: Evidence) -> Path:
    return evidence.directory / PROMOTION_FILE


def _promotion_pending(evidence: Evidence) -> bool:
    """Whether the evidence directory holds the journal of an interrupted
    promotion (a symbolic link in its place counts, and is refused)."""
    path = _journal_path(evidence)
    return path.is_symlink() or path.exists()


def _promotion_journal(evidence: Evidence, scenario: Any, baseline: Any, status: str, diff: str,
                       projected: Mapping[str, Any]) -> dict:
    """The journal of replacing the derived ``baseline`` by the eligible
    observed outcome: MINOR when the comparison passed, MAJOR (the observed
    expectation, inline as well, and a register entry quoting both outcomes)
    when it found a decoded difference; the observed manifest and the names
    of the business datasets whose bytes the evidence supplies."""
    if status == _results.STATUS_PASSED:
        expected, level = dict(baseline.expected), "minor"
    else:
        expected, level = observed_expectation(evidence, scenario, baseline), "major"
    manifest = _observed_manifest(baseline, evidence, level)
    gap = None
    if level == "major":
        from_spec = baseline.manifest.get("derived_from") == "tech-spec"
        gap = {
            "category": "doc-conflict" if from_spec else "uncertain-purpose",
            "unknown": (f"Observed outcome of {scenario.id} differs from its derived expectation: UniKix execution "
                        f"{evidence.execution_id} observed what the baseline now holds, where the derived "
                        f"expectation{' taken from the tech spec' if from_spec else ''} said otherwise. "
                        f"{_clip(diff)}"),
            "needed": ("The documentation owner's decision on which source is authoritative."
                       if from_spec else "The owner's statement of which outcome is intended."),
            "items": sorted({str(item_id) for item_id in scenario.inventory_ids}),
        }
    return {
        "scenario_id": scenario.id,
        "execution_id": evidence.execution_id,
        "from_version": baseline.version,
        "baseline_version": manifest["baseline_version"],
        "level": level,
        "how": _PROMOTION_LEVELS[level],
        "expected": expected,
        "manifest": manifest,
        "datasets": [name for name in _business_datasets(expected) if name in evidence.datasets],
        "inline_expected": _inline_expectation(scenario, expected) if level == "major" else None,
        "remove_tag": _INFERRED_TAG in scenario.tags,
        "gap": gap,
    }


def _journal_problems(journal: Any, evidence: Evidence) -> list[str]:
    """How ``journal`` is not the promotion journal of ``evidence`` that
    ``_promotion_journal`` writes."""
    if not isinstance(journal, Mapping):
        return ["it does not hold a mapping"]
    problems = []
    missing = [key for key in _JOURNAL_KEYS if key not in journal]
    if missing:
        problems.append(f"it lacks {', '.join(missing)}")
    for key, wanted in (("scenario_id", evidence.scenario_id), ("execution_id", evidence.execution_id)):
        if journal.get(key) != wanted:
            problems.append(f"{key} {_clip(repr(journal.get(key)), 80)} is not {wanted}")
    for key in ("from_version", "baseline_version", "how"):
        if not isinstance(journal.get(key), str):
            problems.append(f"{key} is not text")
    if journal.get("level") not in _PROMOTION_LEVELS:
        problems.append(f"level {_clip(repr(journal.get('level')), 80)} is not minor or major")
    manifest = journal.get("manifest")
    if not isinstance(journal.get("expected"), dict) or not isinstance(manifest, dict):
        problems.append("expected and manifest must be mappings")
    elif (manifest.get("baseline_version") != journal.get("baseline_version")
          or manifest.get("execution_id") != evidence.execution_id):
        problems.append("the manifest names another baseline version or execution")
    names = journal.get("datasets")
    if not isinstance(names, list) or not all(isinstance(name, str) for name in names):
        problems.append("datasets must list dataset names")
    inline = journal.get("inline_expected")
    if not (inline is None or isinstance(inline, dict)) or (inline is None) != (journal.get("level") == "minor"):
        problems.append("inline_expected must be the MAJOR promotion's inline expectation, null for MINOR")
    if not isinstance(journal.get("remove_tag"), bool):
        problems.append("remove_tag must be true or false")
    gap = journal.get("gap")
    if gap is not None and not (
            isinstance(gap, Mapping) and all(key in gap for key in _JOURNAL_GAP_KEYS)
            and gap["category"] in CATEGORIES.values()
            and isinstance(gap["unknown"], str) and isinstance(gap["needed"], str)
            and isinstance(gap["items"], list) and all(isinstance(item, str) for item in gap["items"])):
        problems.append(f"gap must be null or map {', '.join(_JOURNAL_GAP_KEYS)}")
    return problems


def _checked_journal(evidence: Evidence, scenario: Any) -> dict:
    """The journal of the interrupted promotion of ``evidence``, checked
    against the evidence and against the baseline on disk, which must still
    be the derived one it promotes or already hold the manifest it writes.
    A journal larger than ``EVIDENCE_YAML_MAX_BYTES`` is refused before it is
    parsed. Anything else raises ``ReportError`` naming the journal: nothing
    is guessed, and nothing is written."""
    path = _journal_path(evidence)
    shown = display_path(path)
    if path.is_symlink():
        raise ReportError(f"{shown} is a symbolic link; nothing is read or written through it")
    try:
        journal = load_yaml(path, max_bytes=EVIDENCE_YAML_MAX_BYTES)
    except (OSError, YAMLLoadError) as exc:
        raise ReportError(f"{shown} cannot be read ({_clip(exc, 300)}); complete or undo the promotion by hand") \
            from None
    problems = _journal_problems(journal, evidence)
    if problems:
        raise ReportError(f"{shown} is not the journal of a promotion of {scenario.id} by {evidence.execution_id}: "
                          + "; ".join(problems) + "; complete or undo the promotion by hand")
    manifest_path = _baselines.BASELINES_DIR / scenario.id / _baselines.MANIFEST_FILE
    if manifest_path.is_symlink():
        raise ReportError(f"{shown}: {display_path(manifest_path)} is a symbolic link; nothing is written through it")
    try:
        manifest = load_yaml(manifest_path, missing_ok=True)
    except (OSError, YAMLLoadError) as exc:
        raise ReportError(f"{shown}: {display_path(manifest_path)} cannot be read ({_clip(exc, 300)}); complete or "
                          "undo the promotion by hand") from None
    derived = (isinstance(manifest, Mapping) and manifest.get("capture_platform") == _baselines.DERIVED
               and manifest.get("baseline_version") == journal["from_version"])
    if not derived and manifest != journal["manifest"]:
        raise ReportError(f"{shown}: the baseline of {scenario.id} is neither the derived {journal['from_version']} "
                          f"it promotes nor the {journal['baseline_version']} it writes; complete or undo the "
                          "promotion by hand")
    return dict(journal)


def _promoted_text(text: str, path: Path, journal: Mapping[str, Any]) -> str:
    """Scenario file ``text`` as ``journal`` leaves it: its ``expected``
    replaced by the MAJOR promotion's inline expectation, and its
    inferred-not-observed tag removed. A file that already reads so is
    returned unchanged, so completing a promotion never edits it twice."""
    inline = journal["inline_expected"]
    data = _yaml_text_load(text)
    if inline is not None and (not isinstance(data, dict) or data.get("expected") != inline):
        note = (f"Expected outcome replaced by the observed outcome of UniKix execution {journal['execution_id']} "
                f"(baseline {journal['baseline_version']}).")
        text = _with_expected(text, inline, note, path)
    if journal["remove_tag"]:
        text = _without_tag(text, _INFERRED_TAG, path) or text
    return text


@dataclass
class _PromotionWrites:
    """The writes of one promotion journal, built and checked in memory."""

    scenario_text: str | None  # the new scenario file; None when the file already reads so
    baseline: Any  # the promoted baseline, validated
    # The register change it makes, (gap_id, how) as _add_gap returns it: an
    # entry added, a closed one reopened or an open one extended; None when an
    # open entry already asks the same about every item, as on a retry.
    gap: tuple[str, str] | None
    gaps_document: dict | None  # the register holding that change
    gaps_text: str | None  # and its gaps.yaml text


def _promotion_writes(evidence: Evidence, scenario: Any, journal: Mapping[str, Any],
                      document: dict) -> _PromotionWrites:
    """Every write of ``journal``, built and checked before the first one:
    the baseline (validated), the scenario text and the promoted scenario it
    parses to, the register entry added, reopened or extended in a copy of
    ``document`` (``_add_gap``) with its ``gaps.yaml`` text, and the
    ``validation.yaml`` record of the promoted state (its record contract).
    Every target is refused when it is a symbolic link, and the journal and
    ``validation.yaml`` pass the checks their own writes make
    (``_evidence_file``)."""
    scenario_id = scenario.id
    missing = [name for name in journal["datasets"] if name not in evidence.datasets]
    if missing:
        raise ReportError(f"{scenario_id}: the evidence holds no {', '.join(missing)} for the promoted baseline; "
                          "nothing is imported")
    datasets = {name: evidence.datasets[name] for name in journal["datasets"]}
    baseline = _baselines.Baseline(scenario_id, dict(journal["expected"]), dict(journal["manifest"]), datasets)
    original = scenario.path.read_text(encoding="utf-8")
    text = _promoted_text(original, scenario.path, journal)
    data = _yaml_text_load(text)
    data = data if isinstance(data, dict) else {}
    expected, tags = data.get("expected"), data.get("tags")
    promoted = replace(scenario, data=data, expected=expected if isinstance(expected, dict) else {},
                       tags=tuple(tags) if isinstance(tags, list) else ())
    registered = gaps_document = gaps_text = None
    gap = journal["gap"]
    if gap is not None:
        gaps_document = copy.deepcopy(document)
        registered = _add_gap(gaps_document, gap["category"], gap["unknown"], gap["items"], gap["needed"])
        if registered is not None:
            gaps_text = _gaps_text(gaps_document, GAPS_FILE.read_text(encoding="utf-8"))
    status, reason, _, projected = _judge(evidence, promoted, baseline)
    _validation(evidence, promoted, baseline, status, reason, None, projected)
    directory = _baselines.BASELINES_DIR / scenario_id
    raw_folder = directory / _baselines.DATASETS_DIR
    _refuse_links(
        scenario.path, *([GAPS_FILE] if registered is not None else []),
        _baselines.BASELINES_DIR, directory, directory / _baselines.EXPECTED_FILE,
        directory / _baselines.MANIFEST_FILE, raw_folder,
        *(raw_folder / f"{name}{_baselines.RAW_SUFFIX}" for name in sorted(datasets)),
    )
    _evidence_file(evidence, PROMOTION_FILE)
    _validation_path(evidence)
    return _PromotionWrites(text if text != original else None, baseline, registered, gaps_document, gaps_text)


class _SchemaRefusal(ReportError):
    """A record refused because its scenario fails the schema check
    (``_schema_problem``), raised once its ``validation.yaml`` records
    ``failing`` with reason ``error``, as a live run records such a scenario,
    so that the caller reports the record rejected."""


def _schema_refusal(schema: str) -> _SchemaRefusal:
    return _SchemaRefusal(f"{schema}; {VALIDATION_FILE} records failing (error), as a live run does, and it verifies "
                          "nothing until the scenario passes the check")


def _commit_promotion(evidence: Evidence, scenario: Any, journal: Mapping[str, Any], writes: _PromotionWrites,
                      document: dict, summary: ImportSummary, completing: bool) -> tuple[str, str | None]:
    """Make the writes of ``journal``, whose file is written, in the order a
    retry completes from any point: the scenario, the register change (into
    ``document`` as well, so the import's own register write keeps it), the
    baseline (derived to observed: the commit point), then the
    ``validation.yaml`` of the promoted state as it reads back, and last the
    removal of the journal. The promotion, and the entry it added, reopened
    or extended, are reported only once committed. Returns the status and
    reason written. The promoted scenario is judged only once it passes the
    schema check; otherwise ``validation.yaml`` records failing with reason
    error and ``_SchemaRefusal`` is raised after the journal is removed. Only
    a completed journal can meet that, because an import checks the
    scenario before it promotes; the check is not made before the writes,
    because a scenario whose tag an interrupted promotion has already
    removed fails it while its baseline is still the derived one."""
    scenario_id = scenario.id
    if writes.scenario_text is not None:
        write_text(scenario.path, writes.scenario_text)
    if writes.gap is not None:
        write_text(GAPS_FILE, str(writes.gaps_text))
        document.update(writes.gaps_document or {})
    _baselines.write(scenario_id, journal["expected"], journal["manifest"], writes.baseline.datasets)
    promoted = _scenarios.get(scenario_id)
    baseline, problem = _load_baseline(scenario_id)
    if problem is not None:
        raise ReportError(f"{scenario_id}: the promoted baseline cannot be read: {problem}")
    schema = _schema_problem(promoted)
    if schema is None:
        status, reason, _, projected = _judge(evidence, promoted, baseline)
    else:
        status, reason, projected = _results.STATUS_FAILING, _baselines.REASON_ERROR, None
    _write_validation(evidence, _validation(evidence, promoted, baseline, status, reason, None, projected))
    _remove_evidence_file(evidence, PROMOTION_FILE)
    how = f"{journal['how']}, completing an interrupted promotion" if completing else journal["how"]
    summary.promoted.append((scenario_id, journal["from_version"], journal["baseline_version"], how))
    if writes.gap is not None:
        gap_id, registered = writes.gap
        if registered == "added":
            summary.gaps_added.append(gap_id)
        else:
            change = "reopened" if registered == "reopened" else "lists more items"
            summary.gaps_changed.append(f"{gap_id}: {change}, the observed outcome of {scenario_id} differs from its "
                                        f"derived expectation again in {journal['execution_id']}")
    if schema is not None:
        raise _schema_refusal(schema)
    return status, reason


def _promote(evidence: Evidence, scenario: Any, baseline: Any, status: str, diff: str,
             projected: Mapping[str, Any], document: dict, summary: ImportSummary) -> tuple[str, str | None]:
    """Replace a derived baseline by the eligible observed outcome as one
    recoverable transaction: every write is built and checked in memory, the
    journal ``promotion.yaml`` is written beside the evidence, and then the
    writes it records are made. A journal larger than
    ``EVIDENCE_YAML_MAX_BYTES``, which ``_checked_journal`` would refuse to
    read back, raises ``ReportError`` before anything is written. Returns
    the status and reason of the ``validation.yaml`` written."""
    journal = _promotion_journal(evidence, scenario, baseline, status, diff, projected)
    journal_bytes = dump_yaml(journal).encode("utf-8")
    if len(journal_bytes) > EVIDENCE_YAML_MAX_BYTES:
        raise ReportError(f"{scenario.id}: the promotion journal {display_path(_journal_path(evidence))} would hold "
                          f"{len(journal_bytes)} bytes, more than the {EVIDENCE_YAML_MAX_BYTES} an evidence YAML file "
                          "may hold, so an interrupted promotion could not be completed from it; nothing is promoted")
    writes = _promotion_writes(evidence, scenario, journal, document)
    _write_evidence_file(evidence, PROMOTION_FILE, journal_bytes)
    return _commit_promotion(evidence, scenario, journal, writes, document, summary, completing=False)


def _complete_promotion(evidence: Evidence, scenario: Any, document: dict,
                        summary: ImportSummary) -> tuple[str, str | None]:
    """Complete the promotion an interrupted import journalled: its writes
    are made again from the journal (unchanged files are left alone, and the
    register entry is not added, reopened or extended twice), with no new
    judgement and so no second version bump; then the promoted state is
    judged and validated."""
    journal = _checked_journal(evidence, scenario)
    writes = _promotion_writes(evidence, scenario, journal, document)
    return _commit_promotion(evidence, scenario, journal, writes, document, summary, completing=True)


def _refuse_ineligible_completion(evidence: Evidence, scenario: Any, copy_gaps: _CopyGaps,
                                  zip_info: tuple[str, dict] | None) -> None:
    """Refuse to complete the journalled promotion of ``evidence`` when the
    record is no longer eligible for ``scenario``'s items (one became
    UniKix-copy behaviour since the interrupted import), because such
    evidence never replaces a baseline: ``ReportError`` names the journal,
    the reason and the way back, and nothing is written."""
    why = _ineligibility(evidence.raw, scenario, copy_gaps, zip_info)
    if why is None:
        return
    raise ReportError(
        f"{display_path(_journal_path(evidence))} journals an interrupted promotion of {scenario.id}, but the record "
        f"is not eligible to replace its baseline: {why}; nothing is written. The interrupted import may already have "
        f"written part of the promotion: restore {display_path(_baselines.BASELINES_DIR / scenario.id)}/ and "
        f"{display_path(scenario.path)} from version control (git checkout), then delete the journal")


def _utility_seams(scenario: Any) -> set[str]:
    """The seams a job scenario made only of utility steps is verified
    through: the replacement of each step kind its jobs run, and the
    emulated catalog. Empty for a scenario that names no job, or when a
    step of its jobs runs a program or names no kind."""
    jobs = _scenarios.job_names(scenario)
    if not jobs:
        return set()
    try:
        document = load_yaml(_results.GNUCOBOL_JOBS_FILE)
    except (OSError, YAMLLoadError) as exc:
        raise ReportError(f"{scenario.id}: the gnucobol-batch job steps cannot be read: {_clip(exc, 300)}") from None
    table = document.get("jobs") if isinstance(document, Mapping) else None
    kinds: set[str] = set()
    for job in jobs:
        entry = table.get(job) if isinstance(table, Mapping) else None
        steps = entry.get("steps") if isinstance(entry, Mapping) else None
        if not isinstance(steps, list) or not steps:
            return set()
        for step in steps:
            kind = step.get("kind") if isinstance(step, Mapping) else None
            if not isinstance(kind, str) or kind == "program":
                return set()
            kinds.add(kind)
    return {_SEAM_OF_UTILITY_STEP[kind] for kind in kinds if kind in _SEAM_OF_UTILITY_STEP} | {_SEAM_OF_CATALOG}


def _key_label(label: tuple[str, str | None]) -> str:
    return label[0] if label[1] is None else f"{label[0]}.{label[1]}"


def _differing_keys(scenario: Any, baseline: Any, result: Result,
                    run_date: str | None) -> list[tuple[str, str | None]]:
    """The keys of the scenario's ``expected`` that ``result`` fails, each
    compared alone (with the top-level ``matchers``): ``(key, None)`` for
    ``return_code``, ``messages``, ``reject_reason_codes`` and ``screens``,
    ``(section, name)`` for each dataset or observation."""
    expected = scenario.expected
    shared = {"matchers": expected["matchers"]} if "matchers" in expected else {}
    parts: list[tuple[tuple[str, str | None], dict]] = []
    for key, value in expected.items():
        if key == "matchers":
            continue
        if key in ("datasets", "file_state") and isinstance(value, Mapping):
            parts += [((str(key), str(name)), {key: {name: item}}) for name, item in value.items()]
        else:
            parts.append(((str(key), None), {key: value}))
    differing = []
    for label, alone in parts:
        status, _, _ = _baselines.compare(replace(scenario, expected={**alone, **shared}), baseline, result,
                                          _results.UNIKIX, run_date)
        if status != _results.STATUS_PASSED:
            differing.append(label)
    return differing


def _abend_messages_only(spec: Any, messages: Sequence[Any]) -> bool:
    """Whether the CEE3ABD stub explains how the observed ``messages`` differ
    from the baseline's ``spec``: ``spec`` is a plain list of text lines (a
    matcher entry, or the ``records``/``contains`` mapping, is never
    attributed) with at least one line holding the stub's marker, and
    ``messages`` without the lines that carry an LE or COBOL runtime message
    identifier equal, in order, ``spec`` without its marker lines. A business
    line added, dropped, changed or reordered lies outside the stub's
    scope."""
    if not isinstance(spec, list) or not all(isinstance(line, str) for line in spec):
        return False
    business = [line for line in spec if _ABEND_MARKER not in line]
    if len(business) == len(spec):
        return False
    observed = [line for line in messages if not (isinstance(line, str) and _LE_DIAGNOSTIC_RE.search(line))]
    return observed == business


def _unordered(records: Sequence[Any]) -> list[str]:
    """``records`` compared without their order."""
    return sorted(dump_yaml(record) for record in records)


def _spaces_for_nul(value: Any) -> Any:
    if isinstance(value, str):
        return value.replace("\x00", " ")
    if isinstance(value, Mapping):
        return {key: _spaces_for_nul(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_spaces_for_nul(item) for item in value]
    return value


def _record_seams(scenario: Any, baseline: Any, result: Result, section: str, name: str) -> set[str]:
    """The seams that explain how the decoded records of business dataset
    ``name`` differ from those the baseline's own bytes decode to: the same
    records in another order, or the same records but for ``x'00'`` where
    the other side holds spaces. Empty without the baseline's bytes, or for
    any other difference."""
    if name not in baseline.datasets:
        return set()
    try:
        captured = _baselines.decode_result(
            scenario, Result(return_code=None, datasets=dict(baseline.datasets), screens=[], messages=[]))
        observed = _baselines.decode_result(scenario, result)
        ours = (captured.get(section) or {}).get(name)
        theirs = (observed.get(section) or {}).get(name)
        if not isinstance(ours, list) or not isinstance(theirs, list) or ours == theirs:
            return set()
        if _unordered(ours) == _unordered(theirs):
            return set(_SEAMS_OF_RECORD_ORDER)
        ours, theirs = _spaces_for_nul(ours), _spaces_for_nul(theirs)
        if ours == theirs or _unordered(ours) == _unordered(theirs):
            return set(_SEAMS_OF_NUL_FILL)
    except HarnessError:
        return set()
    return set()


def _key_seams(label: tuple[str, str | None], scenario: Any, baseline: Any, result: Result) -> set[str]:
    """The seams whose scope a difference in ``label`` lies within; empty
    when no seam explains it (screens, reject reason codes and any other
    content difference)."""
    key, name = label
    stored = baseline.expected
    abend = "return_code" in stored and stored["return_code"] is None
    if key == "return_code":
        return {_SEAM_OF_ABEND} if abend else set()
    if key == "messages":
        return {_SEAM_OF_ABEND} if abend and _abend_messages_only(stored.get("messages"), result.messages) else set()
    if key in ("datasets", "file_state") and name is not None:
        if name in _SEAMS_OF_OBSERVATION:
            return set(_SEAMS_OF_OBSERVATION[name])
        if not name.startswith("@"):
            return _record_seams(scenario, baseline, result, key, name)
    return set()


def _divergence_seams(evidence: Evidence, scenario: Any, baseline: Any,
                      document: dict) -> tuple[list[tuple[str, str | None]], list[str]]:
    """The keys in which an eligible UniKix outcome differs from
    ``baseline``, and the stub-limitation entries the scenario cites (and the
    register holds) whose seams explain every one of them: the replacements
    of a job made only of utility steps, otherwise a seam per differing key
    whose scope that difference lies within. No seam when a differing key
    lies outside every cited seam's scope."""
    run_date = evidence_run_date(evidence.environment, scenario)
    result = evidence_result(evidence)
    keys = _differing_keys(scenario, baseline, result, run_date)
    entries = _gap_entries(document)
    cited = {str(gap_id) for gap_id in scenario.gap_ids if str(gap_id).startswith(_STUB_PREFIX)} & set(entries)
    if not cited or not keys:
        return keys, []
    utility = _utility_seams(scenario) & cited
    if utility:
        return keys, sorted(utility)
    explaining: set[str] = set()
    for label in keys:
        seams = _key_seams(label, scenario, baseline, result) & cited
        if not seams:
            return keys, []
        explaining |= seams
    return keys, sorted(explaining)


def _listed(words: Sequence[str]) -> str:
    return words[0] if len(words) == 1 else f"{', '.join(words[:-1])} and {words[-1]}"


def _divergence_entry(evidence: Evidence, scenario: Any, baseline: Any, diff: str,
                      document: dict) -> tuple[str, str, str]:
    """The register entry of a cross-adapter divergence, ``(category,
    unknown, needed)``: ``stub-limitation`` naming the seams when the
    difference lies within the scope of the seams the scenario cites,
    ``uncertain-purpose`` otherwise. The unknown names the differing keys."""
    keys, seams = _divergence_seams(evidence, scenario, baseline, document)
    unknown = (f"Cross-adapter divergence of {scenario.id}: UniKix execution {evidence.execution_id} "
               f"gives another decoded outcome than the {baseline.capture_platform} baseline, which "
               f"stays authoritative" + (f", in {_listed([_key_label(label) for label in keys])}" if keys else "")
               + ".")
    if not seams:
        return "uncertain-purpose", f"{unknown} {_clip(diff)}", "The owner's statement of which outcome is intended."
    entries = _gap_entries(document)
    described = {gap_id: what for what, gap_id in SEAMS}
    named = []
    for gap_id in seams:
        seam = entries[gap_id].get("seam")
        replaces = seam.get("replaces") if isinstance(seam, Mapping) else None
        named.append(f"{gap_id} ({replaces if isinstance(replaces, str) and replaces else described.get(gap_id)})")
    several = len(named) > 1
    unknown += f" The difference lies within the scope of {_listed(named)}. {_clip(diff)}"
    needed = (f"An eligible UniKix run that agrees, or the owner's decision whether the limitation"
              f"{'s' if several else ''} of {_listed(named)} explain{'' if several else 's'} the difference.")
    return "stub-limitation", unknown, needed


def _validate_evidence(evidence: Evidence, scenario: Any, document: dict | None,
                       summary: ImportSummary | None, copy_gaps: _CopyGaps,
                       zip_info: tuple[str, dict] | None, *, importing: bool) -> tuple[str, str | None] | None:
    """Write the ``validation.yaml`` of one evidence directory. Only
    ``importing`` (an import, not a revalidation) promotes an eligible
    derived baseline, or completes the promotion an interrupted import
    journalled. Given ``document``, the register the import or revalidation
    writes, a cross-adapter divergence an eligible record shows adds,
    reopens or extends its entry there, alike on import and revalidation,
    and leaves the baseline and the scenario as they are. Promotion and
    registration both need a ``summary`` to report what they change, and
    both refuse a record whose comparison could not be made.
    A record is written only when the evidence holds every observation the
    current ``expected`` names (``missing_observations``), with one
    exception: on import, a record with no outcome to compare
    (``Evidence.ran`` false: blocked, implemented-unexecuted, or failing on
    timeout) keeps its recorded status, reason and gap whatever its evidence
    holds; while revalidating it keeps them only under that rule. A
    scenario that fails the schema check is never judged and nothing is
    promoted for it: ``validation.yaml`` records failing with reason error,
    as a live run records it, and ``_SchemaRefusal`` is raised. Returns
    the status written, or ``None`` when nothing is written because the
    evidence no longer describes the scenario (its execution fingerprint
    changed, or it lacks such an observation) or a promotion is pending
    while revalidating. Raises ``HarnessError`` when the evidence cannot be
    judged: the current execution fingerprint cannot be computed, the
    baseline cannot be read, the comparison could not be made, or a
    journalled promotion can no longer complete."""
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
    elif not importing:
        if summary is not None:
            summary.skipped.append((scenario.id, "no execution fingerprint to confirm"))
        return None
    if _promotion_pending(evidence):
        if not importing or document is None or summary is None:
            if summary is not None:
                summary.skipped.append((scenario.id, "an interrupted promotion is pending; run --import-unikix "
                                        f"{evidence.execution_id} to complete it"))
            return None
        _refuse_ineligible_completion(evidence, scenario, copy_gaps, zip_info)
        return _complete_promotion(evidence, scenario, document, summary)
    baseline, problem = _load_baseline(scenario.id)
    if problem is not None:
        raise ReportError(f"{scenario.id}: the baseline cannot be read: {problem}")
    # A revalidation rewrites a record only from evidence holding every
    # observation the current expected names, a verdict with no outcome to
    # compare included; the import carries that verdict forward as recorded.
    missing = missing_observations(evidence, scenario, baseline) if evidence.ran or not importing else []
    if missing:
        if summary is not None:
            summary.skipped.append((scenario.id, f"stale: the evidence lacks {', '.join(missing)}"))
        return None
    # Every verdict starts from the schema check, as in a live run: a scenario
    # that fails it (one asserting only return_code, say) is failing with
    # reason error whatever the evidence shows, and nothing is promoted.
    schema = _schema_problem(scenario)
    if schema is not None:
        _write_validation(evidence, _validation(evidence, scenario, baseline, _results.STATUS_FAILING,
                                                _baselines.REASON_ERROR, None, None))
        raise _schema_refusal(schema)
    if not evidence.ran:
        record = _validation(evidence, scenario, baseline, str(raw.get("status")), raw.get("reason"),
                             raw.get("gap_id"), None)
        _write_validation(evidence, record)
        return record["status"], record["reason"]
    problem = _run_date_problem(evidence, scenario, baseline)
    if problem is not None:
        raise ReportError(problem)
    eligible = _ineligibility(raw, scenario, copy_gaps, zip_info) is None
    status, reason, diff, projected = _judge(evidence, scenario, baseline)
    registering = document is not None and summary is not None and eligible and baseline is not None
    promoting = registering and importing and baseline.derived
    if promoting or (registering and reason == _baselines.REASON_DIVERGENCE):
        problem = _comparison_problem(evidence, scenario, baseline, reason, diff, projected)
        if problem is not None:
            raise ReportError(problem)
        if promoting:
            return _promote(evidence, scenario, baseline, status, diff, projected, document, summary)
        # The validation is written before the entry is registered, so a record
        # refused on the way registers nothing; a retry judges again and finds
        # the entry asking the question, so it is not added twice. A divergence
        # that recurs reopens the closed entry that asked it, or adds its
        # items to the open one, under the entry's own id.
        category, unknown, needed = _divergence_entry(evidence, scenario, baseline, diff, document)
        _write_validation(evidence, _validation(evidence, scenario, baseline, status, reason, None, projected))
        registered = _add_gap(document, category, unknown, map(str, scenario.inventory_ids), needed)
        if registered is not None:
            gap_id, how = registered
            if how == "added":
                summary.gaps_added.append(gap_id)
            else:
                change = "reopened" if how == "reopened" else "lists more items"
                summary.gaps_changed.append(f"{gap_id}: {change}, cross-adapter divergence of {scenario.id} recurs "
                                            f"in {evidence.execution_id}")
        return status, reason
    record = _validation(evidence, scenario, baseline, status, reason, None, projected)
    _write_validation(evidence, record)
    return status, reason


def _versions_recorded(environment: Mapping[str, Any]) -> bool:
    """Whether ``environment.yaml`` names a version for each UniKix
    prerequisite (TPE, BPE, Visual COBOL, licence, Java): the last text it
    records under each probe's key names a version, not a failure."""
    found: dict[str, str] = {}
    for key, values in _recorded(environment, UNIKIX_VERSION_KEYS).items():
        texts = [value for value in values if isinstance(value, str)]
        if texts:
            found[key] = texts[-1]
    return all(
        found.get(key, "").strip() and not found[key].strip().lower().startswith(_VERSION_FAILURE_PREFIXES)
        for key in UNIKIX_VERSION_KEYS
    )


# The most characters of an observed message the name of its inventory
# addition quotes.
_ADDITION_QUOTE_LIMIT = 160


def _addition_text(message: ObservedMessage) -> str:
    """The text the inventory addition of ``message``, an observed message
    that matches no item, is made of: the first non-blank line of an
    ``@text`` screen, or the ``ERRMSG`` value without its surrounding blanks.
    Empty when the message holds no such text, so that it can never become
    an addition."""
    if message.field == _scenarios.SCREEN_TEXT:
        return _first_line(message.text)
    return message.text.strip()


def _new_message_items(state: State, execution_id: str, imported: Iterable[str], document: dict,
                       summary: ImportSummary) -> None:
    """Add every observed message of the execution that matches no item to
    the inventory, raising its total, and list each added item under
    GAP-UNC-012 in ``document``, reopening that entry when it is closed.
    Without a GAP-UNC-012 entry nothing is added and the import rejects the
    messages. An addition matches later messages through
    ``_addition_pattern``, in this import as in every later one, so the same
    evidence is never added twice."""
    patterns = list(_patterns_of(state.items))
    added: set[str] = set()
    additions: list[tuple[dict, str]] = []
    for scenario_id in sorted(imported):
        scenario = state.scenarios.get(scenario_id)
        path = UNIKIX_EVIDENCE_DIR / execution_id / scenario_id / RESULT_FILE
        try:
            raw = load_yaml(path, max_bytes=EVIDENCE_YAML_MAX_BYTES)
        except (OSError, YAMLLoadError):
            continue
        if scenario is None or not isinstance(raw, Mapping):
            continue
        for message in _observed_messages(path, raw, execution_id, scenario_id):
            if message_items(message.text, message.field, patterns):
                continue
            text = _addition_text(message)
            if not text or text in added:
                continue
            added.add(text)
            kind = _inventory.classify_message(_inventory.Message(text=text, origin="literal", position=0,
                                                                  span=("", 0, 0)))
            program = scenario.program_or_job
            item = {
                "area": scenario.area,
                "kind": kind,
                "name": f"{program} observed message '{_clip(text, _ADDITION_QUOTE_LIMIT)}'",
                "source": f"{display_path(path)}:{message.line}",
                "description": (f"Observed on UniKix in execution {execution_id}, scenario {scenario_id}, step "
                                f"{message.step} ({message.field}); no extraction rule produced an item for it "
                                f"({GAP_COMPLETENESS})."),
                "level": "e2e",
                "activation": "always",
                "gap_ids": [GAP_COMPLETENESS],
            }
            additions.append((item, f"unmatched observed message ({execution_id}/{scenario_id})"))
            patterns.append((text, _addition_pattern(text)))
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


def _verifying_executions(state: State, item_ids: Iterable[str]) -> set[str]:
    """The UniKix executions whose counted passed records verify any of
    ``item_ids``."""
    return {
        str(record["execution_id"])
        for item_id in item_ids
        for scenario_id in state.item_scenarios.get(item_id, ())
        for record in state.statuses[scenario_id].counted
        if record.get("adapter") == _results.UNIKIX and record.get("status") == _results.STATUS_PASSED
        and record.get("execution_id")
    }


def _closing_evidence(state: State, item_ids: Iterable[str], execution_id: str | None) -> dict | None:
    """The ``resolving_evidence`` of an inferred-not-observed entry whose items
    are all verified: the UniKix execution whose counted passed records verify
    them (the importing one when it is among them, else the latest, as ids
    embed their UTC start), else the importing execution, else the commit
    checked out. ``None`` when nothing names the evidence."""
    executions = _verifying_executions(state, item_ids)
    if execution_id is not None and (execution_id in executions or not executions):
        chosen: str | None = execution_id
    else:
        chosen = max(executions, default=None)
    if chosen is not None:
        return {"execution_id": chosen, "evidence_path": f"{display_path(UNIKIX_EVIDENCE_DIR)}/{chosen}"}
    commit = git_head()
    return None if commit == "unknown" else {"commit": commit}


# The inventory activation of an item that no input or configuration can reach.
_UNREACHABLE = "unreachable"


@dataclass
class _ClosingCheck:
    """The inventory step of closing a register entry, judged before the
    entry closes: the inventory must already hold the additions the closing
    evidence entails, and an activation that evidence does not contradict.

    The closing evidence of an entry is the importing execution
    (``execution_id``; ``None`` on revalidation) and every UniKix execution
    whose counted passed records verify the entry's items. It entails an
    addition for each observed ``ERRMSG`` or ``@text`` of those executions
    that matches no item (``unmatched_messages``), even before validation,
    that ``_new_message_items`` turns into one: a message of a present
    scenario holding text that no item's name quotes yet, so that a message
    added once but beyond what the matching sees keeps no entry open. An
    item recorded ``unreachable`` that the evidence verifies contradicts its
    activation, which the drift check holds to the extraction, so only a
    person can correct it. The evidence's messages are read once, when an
    entry first needs them."""

    state: Any
    execution_id: str | None

    @functools.cached_property
    def messages(self) -> list[ObservedMessage]:
        """The observed UniKix messages that match no item and that the
        inventory holds no addition for yet."""
        state = self.state
        quoted = set()
        for item in state.items:
            name = str(item.get("name") or "")
            start, end = name.find("'"), name.rfind("'")
            if 0 <= start < end:
                quoted.add(name[start + 1:end])
        return [
            message for message in unmatched_messages(state, UNIKIX_EVIDENCE_DIR)
            if message.scenario_id in state.scenarios and _addition_text(message)
            and _clip(_addition_text(message), _ADDITION_QUOTE_LIMIT) not in quoted
        ]

    def withheld(self, gap_id: str, held: str, members: Sequence[str]) -> str | None:
        """The line noting that ``gap_id``, whose closing ``held`` would
        allow, stays open because the inventory does not yet reflect the
        evidence that closes it, an entry affecting ``members``; ``None``
        when the inventory reflects it, so the entry may close."""
        executions = _verifying_executions(self.state, members)
        if self.execution_id is not None:
            executions.add(self.execution_id)
        observed = []
        for execution in sorted(executions):
            found = [message for message in self.messages if message.execution_id == execution]
            if found:
                observed.append(f"{len(found)} observed message(s) of {execution} match no item "
                                f"(first: '{_clip(_addition_text(found[0]), 80)}')")
        parts = [_clip("; ".join(observed), 600)] if observed else []
        activation = {item["id"]: item.get("activation") for item in self.state.items}
        contradicted = [
            item_id for item_id in members
            if activation.get(item_id) == _UNREACHABLE and self.state.item_status.get(item_id) == VERIFIED
        ]
        if contradicted:
            verb = "is" if len(contradicted) == 1 else "are"
            parts.append(f"{_clip(', '.join(contradicted), 300)} {verb} recorded unreachable but verified")
        if not parts:
            return None
        return (f"{gap_id}: left open although {held}: the inventory does not yet reflect its closing evidence: "
                f"{'; '.join(parts)}")


@dataclass
class _Closure:
    """One closing ``_close`` made in the register document, held until
    ``_write_register`` has the inventory record it: the entry and its id,
    the ``header.changes`` reason the inventory records it with, the line
    noted for the operator, and the ``status`` and ``resolving_evidence``
    the closing replaced."""

    gap_id: str
    entry: dict
    reason: str
    line: str
    replaced: dict

    def revert(self, changes: list, why: str) -> None:
        """Undo the closing: the entry is open again with the status and
        evidence it held before, its membership as the settling left it, and
        the closing's line in ``changes`` gives way to ``why`` the closing
        does not stand."""
        for key in ("status", "resolving_evidence"):
            if key in self.replaced:
                self.entry[key] = self.replaced[key]
            else:
                self.entry.pop(key, None)
        text = f"{self.gap_id}: left open, the inventory could not record its closure: {why}"
        if self.line in changes:
            changes[changes.index(self.line)] = text
        else:
            changes.append(text)


def _close(gap_id: str, entry: dict, evidence: dict, why: str, changes: list, closures: list) -> None:
    """Close ``entry`` on ``evidence``, ``{execution_id, evidence_path}`` or
    ``{commit}``: set ``status: closed`` and that ``resolving_evidence``,
    note ``<gap_id>: closed, <why>`` in ``changes``, and queue in
    ``closures`` the record of the closing's reason. Every closing the
    import and the revalidation make goes through here, and only once
    ``_ClosingCheck.withheld`` finds that the inventory already reflects the
    closing evidence: it holds the addition of every observed message of
    that evidence that matches no item, and records no item the evidence
    verifies as unreachable. The importer can author no description, and no
    affected item's description depends on the closing, so what the closing
    adds to the inventory is that reason: a ``header.changes`` closure record
    naming the entry, ``why`` and the evidence
    (``inventory_tools.append_closures``), which ``_write_register`` writes
    before ``gaps.yaml``."""
    replaced = {key: entry[key] for key in ("status", "resolving_evidence") if key in entry}
    entry["status"], entry["resolving_evidence"] = "closed", evidence
    line = f"{gap_id}: closed, {why}"
    changes.append(line)
    if "commit" in evidence:
        named = f"commit {evidence['commit']}"
    else:
        named = f"UniKix execution {evidence['execution_id']} ({evidence['evidence_path']})"
    closures.append(_Closure(gap_id, entry, f"{gap_id} closed: {why}; resolving evidence: {named}", line, replaced))


def _settle_inferred(document: dict, state: State, execution_id: str | None, changes: list,
                     closures: list, check: _ClosingCheck | None = None) -> None:
    """Judge every inferred-not-observed entry, open or closed, over its full
    membership (``narrowed_from`` once it is narrowed), appending one line
    per change to ``changes``. An entry closes, its full membership
    restored, only when every item is verified by current records and the
    inventory reflects the evidence that verifies them (``check``, a
    ``_ClosingCheck`` of ``state`` made here when not given): it holds the
    addition of every observed message of that evidence that matches no
    item, and records no affected item as unreachable. The closing goes
    through ``_close``, which queues in ``closures`` the record of its
    reason; an entry the inventory does not yet reflect stays open, its full
    membership restored, with the line saying what the inventory lacks, so
    the next import or revalidation judges it again. A closed entry with an
    item no longer verified reopens, its ``resolving_evidence`` moved to
    ``superseded_evidence``; an open entry lists exactly its unverified
    items, the earlier membership kept under ``narrowed_from``.
    ``execution_id`` names the importing execution (``None`` on
    revalidation)."""
    check = check if check is not None else _ClosingCheck(state, execution_id)
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
                withheld = None if evidence is None else check.withheld(gap_id, "every affected item is verified",
                                                                        members)
                if evidence is None:
                    changes.append(f"{gap_id}: left open although every affected item is verified: no UniKix "
                                   "execution or commit names the evidence")
                elif withheld is not None:
                    changes.append(withheld)
                else:
                    _close(gap_id, entry, evidence, "every affected item verified", changes, closures)
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


def _unconfirmed_commands(gap_id: str) -> tuple[list[str], str | None]:
    """The ids, sorted, of the ``UNIKIX_COMMANDS_FILE`` entries whose ``gap``
    is ``gap_id`` and whose ``status`` is not exactly ``confirmed``, with
    ``None``; or ``[]`` with what is wrong when the file cannot show which
    they are: it cannot be read or parsed, holds no ``entries`` mapping, or
    holds an entry that is not a mapping. A caller that needs every such
    entry confirmed withholds on either answer, so it fails closed."""
    path = UNIKIX_COMMANDS_FILE
    try:
        document = load_yaml(path)
    except OSError as exc:
        return [], _os_problem(exc, path)
    except YAMLLoadError as exc:
        return [], str(exc)
    entries = document.get("entries") if isinstance(document, dict) else None
    if not isinstance(entries, dict):
        return [], f"{display_path(path)}: holds no 'entries' mapping"
    unconfirmed = []
    for entry_id, entry in entries.items():
        if not isinstance(entry, dict):
            return [], f"{display_path(path)}: entry {entry_id!r} is not a mapping"
        if entry.get("gap") == gap_id and entry.get("status") != "confirmed":
            unconfirmed.append(str(entry_id))
    return sorted(unconfirmed), None


def _settle_gaps(document: dict, state: State, execution_id: str, environments: Iterable[Mapping[str, Any]],
                 summary: ImportSummary, closures: list) -> None:
    """Judge the inferred-not-observed entries (``_settle_inferred``), and
    GAP-MIS-009 once the installation's versions are recorded. GAP-MIS-009
    closes only once every ``commands.yaml`` entry citing it is confirmed
    as well (``_unconfirmed_commands``), since an unconfirmed entry must
    cite an open gap and only the operator confirms an entry; until then,
    or while the file cannot show it, the entry stays open, the line in
    ``summary.gaps_changed`` naming the unconfirmed entries or what is wrong
    with the file. Each closing needs the inventory to hold the additions its
    evidence entails and an activation it does not contradict
    (``_ClosingCheck``); otherwise the entry stays open, with what is
    lacking in ``summary.gaps_changed``. ``_close`` queues the inventory
    record of each closing's reason in ``closures``."""
    check = _ClosingCheck(state, execution_id)
    _settle_inferred(document, state, execution_id, summary.gaps_changed, closures, check)
    evidence = {"execution_id": execution_id, "evidence_path": f"{display_path(UNIKIX_EVIDENCE_DIR)}/{execution_id}"}
    installation = _gap_entries(document).get(GAP_NO_INSTALLATION)
    if (installation is not None and installation.get("status") == "open"
            and any(_versions_recorded(environment) for environment in environments)):
        unconfirmed, problem = _unconfirmed_commands(GAP_NO_INSTALLATION)
        shown = display_path(UNIKIX_COMMANDS_FILE)
        held = f"{GAP_NO_INSTALLATION}: left open although the installation's versions are recorded"
        if problem is not None:
            summary.gaps_changed.append(f"{held}: cannot tell whether every {shown} entry that cites it is "
                                        f"confirmed: {_clip(problem, 300)}; repair the file, then import this "
                                        "execution again")
            return
        if unconfirmed:
            one = len(unconfirmed) == 1
            summary.gaps_changed.append(
                f"{held}: {shown} {'entry' if one else 'entries'} {_clip(', '.join(unconfirmed), 300)} that "
                f"{'cites it is' if one else 'cite it are'} unconfirmed; set {'it' if one else 'each'} to "
                "status: confirmed once it has worked, then import this execution again"
            )
            return
        members = _resolve(GAP_NO_INSTALLATION, installation, _Universe.build(state.items, state.scenarios.values()))
        withheld = check.withheld(GAP_NO_INSTALLATION, "the installation's versions are recorded", members)
        if withheld is not None:
            summary.gaps_changed.append(withheld)
        else:
            _close(GAP_NO_INSTALLATION, installation, dict(evidence), "the installation's versions are recorded",
                   summary.gaps_changed, closures)


def _write_gaps(document: dict, original: str) -> None:
    _refuse_links(GAPS_FILE)
    write_text(GAPS_FILE, _gaps_text(document, original))


def _problem_text(exc: BaseException) -> str:
    """``exc`` on one line, an ``OSError`` as ``_os_problem`` shows it."""
    return _os_problem(exc) if isinstance(exc, OSError) else " ".join(str(exc).split()) or type(exc).__name__


def _write_register(document: dict, original: str, closures: Sequence[_Closure], changes: list) -> bool:
    """Write ``gaps.yaml`` from ``document`` when it differs from the file,
    the inventory update of every closing in ``closures`` recorded first
    (``inventory_tools.append_closures``), so that no entry closes without
    it. When the inventory cannot record them, every one of those closings
    is undone (``_Closure.revert``): its entry stays open and ``changes``
    says why, so the next import or revalidation judges it again. When
    ``gaps.yaml`` then cannot be read or written, withdraw only the records
    ``append_closures`` returned, under the inventory lock, preserving
    concurrent additions and every other change, and propagate the error.
    Neither file records a closing the other lacks unless that withdrawal
    also fails, which ``_withdraw_closures`` reports with both failures and
    the records left behind. Returns whether either file was written."""
    recorded: list[dict[str, Any]] = []
    try:
        if closures:
            try:
                recorded = _inventory.append_closures([(closure.gap_id, closure.reason) for closure in closures],
                                                      _inventory.INVENTORY_FILE, repo_root=REPO_ROOT)
            except (HarnessError, OSError) as exc:
                for closure in closures:
                    closure.revert(changes, _problem_text(exc))
        if document != load_yaml(GAPS_FILE):
            _write_gaps(document, original)
            return True
        return bool(recorded)
    except BaseException as exc:
        if recorded:
            _withdraw_closures(recorded, exc)
        raise


def _withdraw_closures(records: Sequence[dict[str, Any]], cause: BaseException) -> None:
    """Withdraw this transaction's exact inventory closure ``records`` once
    ``gaps.yaml`` could not record the closings (``cause``). The inventory's
    locked API preserves other writers' additions and changes. If withdrawal
    fails, raise ``ReportError`` naming both failures and the records whose
    removal could not be established, never advising a snapshot restoration
    that would discard another writer's work."""
    try:
        _inventory.withdraw_closures(records, _inventory.INVENTORY_FILE, repo_root=REPO_ROOT)
    except (HarnessError, OSError) as exc:
        raise ReportError(
            f"{display_path(GAPS_FILE)} could not be updated ({_problem_text(cause)}), and "
            f"{display_path(_inventory.INVENTORY_FILE)} could not withdraw its closure records "
            f"({_problem_text(exc)}): header.changes may still record these closings, which "
            f"{display_path(GAPS_FILE)} does not: {list(records)!r}; reconcile those records without "
            "discarding other inventory changes"
        ) from cause


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
    inferred-not-observed entries and settle GAP-MIS-009 (it closes only
    once an environment records every version and every ``commands.yaml``
    entry citing it is confirmed; the import never confirms an entry),
    closing an entry only once the inventory holds the additions the
    closing evidence entails and an activation it does not contradict.
    Record each closing's reason in the
    inventory's ``header.changes`` before ``gaps.yaml`` is written
    (``_write_register``), and regenerate the reports. An entry whose
    evidence the inventory does not yet reflect or whose closing it cannot
    record stays open, listed with the reason under ``gaps_changed``;
    that is a register outcome, not a rejected record,
    so it leaves the exit status to the records. A record whose current
    execution fingerprint cannot be computed is rejected, and so is one
    whose scenario fails the schema check, after its ``validation.yaml``
    records failing with reason error and with nothing promoted. Imports and
    revalidations run one at a time (``_exclusive``). An invalid
    ``CARDDEMO_ADAPTER``, which the final regeneration refuses, raises
    ``ReportError`` before anything is written, and so do a missing
    execution directory, one holding no scenario directory and no symbolic
    link (0 scenario records), an inventory whose item count differs from
    its frozen total and a gnucobol-batch result record that cannot be read
    or is a symbolic link, which the final regeneration refuses too
    (``_refuse_unreportable``)."""
    if not isinstance(execution_id, str) or not _UNIKIX_ID_RE.fullmatch(execution_id):
        raise ReportError(f"{execution_id!r} is not a unikix execution id unikix-<yyyymmddThhmmssZ>-<8 hex>")
    _selected_adapter()
    execution_dir = _evidence_folder(execution_id)
    try:
        if not execution_dir.is_dir():
            raise ReportError(f"no evidence directory {display_path(execution_dir)}")
    except OSError as exc:
        raise ReportError(f"cannot read {_os_problem(exc, execution_dir)}") from None
    entries: list[Path] = []
    _refuse_empty_execution(execution_dir, _scenario_dirs(execution_dir, entries), entries)
    with _exclusive():
        return _import_execution(execution_id, execution_dir)


def _refuse_empty_execution(execution_dir: Path, scenario_dirs: Sequence[Path], links: Sequence[Path]) -> None:
    """Raise ``ReportError`` when ``execution_dir`` holds no scenario
    directory (``scenario_dirs``) and no linked entry (``links``): there is
    no scenario record to import, so the import writes nothing."""
    if not scenario_dirs and not links:
        raise ReportError(f"0 scenario records found in {display_path(execution_dir)}; nothing was imported")


def _import_execution(execution_id: str, execution_dir: Path) -> ImportSummary:
    """The import itself, run while the lock is held. A state the reports
    cannot be regenerated from (``_refuse_unreportable``) and then an
    execution directory with no scenario directory and no linked entry
    (``_refuse_empty_execution``, which ``import_unikix`` also applies before
    taking the lock) raise ``ReportError`` before anything is written."""
    summary = ImportSummary(execution_id)
    original = GAPS_FILE.read_text(encoding="utf-8")
    document = load_yaml(GAPS_FILE)
    state = build_state()
    _refuse_unreportable(state)
    imported = []
    environments = []
    links: list[Path] = []
    scenario_dirs = _scenario_dirs(execution_dir, links)
    _refuse_empty_execution(execution_dir, scenario_dirs, links)
    summary.rejected.extend((link.name, _ENTRY_LINK) for link in links)
    for scenario_dir in scenario_dirs:
        evidence, why = read_evidence(execution_id, scenario_dir, state.zip_info)
        if evidence is None:
            summary.rejected.append((scenario_dir.name, why))
            continue
        scenario = _scenarios.get(evidence.scenario_id, missing_ok=True)
        if scenario is None:
            summary.rejected.append((evidence.scenario_id, "no scenario file"))
            continue
        try:
            written = _validate_evidence(evidence, scenario, document, summary, state.copy_gaps, state.zip_info,
                                         importing=True)
        except HarnessError as exc:
            summary.rejected.append((evidence.scenario_id, str(exc)))
            continue
        except OSError as exc:
            summary.rejected.append((evidence.scenario_id, _os_problem(exc)))
            continue
        environments.append(evidence.environment)
        if written is not None:
            summary.validated.append((evidence.scenario_id, *written))
            imported.append(evidence.scenario_id)
    _new_message_items(build_state(), execution_id, imported, document, summary)
    closures: list[_Closure] = []
    _settle_gaps(document, build_state(), execution_id, environments, summary, closures)
    _write_register(document, original, closures, summary.gaps_changed)
    regenerate()
    return summary


@dataclass
class RevalidationSummary:
    """What one revalidation did, for the operator. ``left`` holds the
    records that stay stale until a new run because the execution no longer
    describes the scenario; ``rejected`` holds the records that could not be
    revalidated at all, those whose observed payload could not be compared
    with the baseline and those whose scenario fails the schema check, whose
    ``validation.yaml`` records that error as a live run would.
    ``gaps_added`` and ``gaps_changed`` list each change to the register: the
    entry a cross-adapter divergence adds, reopens or extends, and an entry
    left open because the inventory could not record its closing; like a
    stale record, that leaves the exit status as it is."""

    revalidated: list = field(default_factory=list)  # (execution/scenario, status, reason)
    left: list = field(default_factory=list)  # (execution/scenario, why it stays stale)
    rejected: list = field(default_factory=list)  # (execution/scenario, why it cannot be revalidated or compared)
    gaps_added: list = field(default_factory=list)
    gaps_changed: list = field(default_factory=list)

    def lines(self) -> list[str]:
        out = [
            f"revalidated {label}: {status}" + (f" ({reason})" if reason else "")
            for label, status, reason in self.revalidated
        ]
        out += [f"left {label}: {why}" for label, why in self.left]
        out += [f"rejected {label}: {why}" for label, why in self.rejected]
        out += [f"gap added {gap_id}" for gap_id in self.gaps_added]
        out += [f"gap updated {text}" for text in self.gaps_changed]
        return out


def revalidate() -> RevalidationSummary:
    """Re-compare every ``validation.yaml`` whose execution still describes
    its scenario (the current execution fingerprint equals the one in
    ``result.yaml`` and every observation the current ``expected`` names is
    in the evidence) and rewrite it; leave every other one, stale until a new
    run; reject the evidence that fails its checks, whose scenario file is
    gone or that cannot be judged, and the records whose observed payload
    cannot be compared with the baseline or whose scenario fails the schema
    check (rewritten failing with reason error, as a live run records it,
    and reported with the comparison's diagnostic or the schema problems).
    Register each cross-adapter divergence an eligible record now shows as
    the import does: its stub-limitation or uncertain-purpose entry is
    added, reopened or extended, and no baseline or scenario changes.
    Then judge the inferred-not-observed entries of
    ``gaps.yaml`` again, closing only once the inventory holds the additions
    the closing evidence entails and an activation it does not contradict.
    Write the register when it changes, each closing's reason recorded first
    in the inventory's ``header.changes`` (an entry whose evidence the
    inventory does not yet reflect or whose closing it cannot record stays
    open, listed with the reason under ``gaps_changed``), and regenerate the
    reports when a ``validation.yaml``, ``gaps.yaml`` or the inventory
    changed. Otherwise (no ``validation.yaml`` to revalidate, or every one
    left stale, rejected before it was written or rewritten unchanged, and
    the register unchanged) nothing is written at all. A symbolic link at
    ``tests/reports`` or ``tests/reports/unikix`` raises ``ReportError``
    before anything is read or written; one in place of an execution or
    scenario directory is rejected, and reported as such. An invalid
    ``CARDDEMO_ADAPTER``, which the final regeneration refuses, raises
    ``ReportError`` before anything is read or written. An inventory whose
    item count differs from its frozen total and a gnucobol-batch result
    record that cannot be read or is a symbolic link, which the final
    regeneration refuses too (``_refuse_unreportable``), raise
    ``ReportError`` before anything is written."""
    _selected_adapter()
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


def _validation_bytes(path: Path) -> bytes | None:
    """The bytes of the ``validation.yaml`` at ``path``; ``None`` when it is
    a symbolic link, which is never read through, or cannot be read."""
    try:
        return None if path.is_symlink() else path.read_bytes()
    except OSError:
        return None


def _revalidate() -> RevalidationSummary:
    """The revalidation itself, run while the lock is held."""
    result = RevalidationSummary()
    evidence_dir = _evidence_folder()
    state = build_state()
    _refuse_unreportable(state)
    original = GAPS_FILE.read_text(encoding="utf-8")
    document = load_yaml(GAPS_FILE)
    links: list[Path] = []
    rewritten = False  # whether any validation.yaml changed
    for execution_dir in _evidence_executions(evidence_dir, links):
        for scenario_dir in _scenario_dirs(execution_dir, links):
            label = f"{execution_dir.name}/{scenario_dir.name}"
            path = scenario_dir / VALIDATION_FILE
            try:
                if not path.is_file():
                    continue
            except OSError as exc:
                result.rejected.append((label, f"cannot read {_os_problem(exc, path)}"))
                continue
            evidence, why = read_evidence(execution_dir.name, scenario_dir, state.zip_info)
            if evidence is None:
                result.rejected.append((label, why))
                continue
            summary = ImportSummary(execution_dir.name)
            before = _validation_bytes(path)
            try:
                scenario = _scenarios.get(scenario_dir.name, missing_ok=True)
                if scenario is None:
                    result.rejected.append((label, "no scenario file"))
                    continue
                written = _validate_evidence(evidence, scenario, document, summary, state.copy_gaps, state.zip_info,
                                             importing=False)
            except HarnessError as exc:
                result.rejected.append((label, str(exc)))
                continue
            except OSError as exc:
                result.rejected.append((label, _os_problem(exc)))
                continue
            finally:
                # A record refused after its validation.yaml was written (the
                # schema check's refusal) changed that file too.
                rewritten = rewritten or _validation_bytes(path) != before
            result.gaps_added.extend(summary.gaps_added)
            result.gaps_changed.extend(summary.gaps_changed)
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
    closures: list[_Closure] = []
    _settle_inferred(document, build_state(), None, result.gaps_changed, closures)
    # Regenerate only when a validation.yaml, gaps.yaml or the inventory
    # changed: otherwise this run changed nothing the reports are computed
    # from, and regenerating would rewrite only the host's Environment section.
    if _write_register(document, original, closures, result.gaps_changed) or rewritten:
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
            document = load_yaml(path, max_bytes=EVIDENCE_YAML_MAX_BYTES)
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
exits 0. Regeneration, an import and a revalidation exit 2 and write
nothing while the inventory lists another number of items than its frozen
header.total, or while a result record under tests/reports/results cannot
be read or is a symbolic link; --check lists each such problem.
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
        # An invalid CARDDEMO_ADAPTER stops every action before it reads or
        # writes anything, as it stops a pytest session at collection.
        _selected_adapter()
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
    except OSError as exc:
        print(f"report.py: error: {_os_problem(exc)}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
