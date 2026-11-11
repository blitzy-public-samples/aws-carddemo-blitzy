"""``UniKixAdapter``: CardDemo scenarios on a test-owned NTT DATA UniKix region.

The adapter runs on an operator host with UniKix TPE and BPE, named by
``CARDDEMO_UNIKIX_ENV``. CI never sets that variable, so there every
``unikix`` scenario ends ``blocked`` under GAP-MIS-009 before anything is
created, and no evidence is written. The adapter uses no test-tree stub:
utilities, ``SDSF.cbl``, the LE services ``CEE3ABD`` and ``CEEDAYS``
(GAP-MIS-010) and region actions are whatever the installation supplies.

Session
    Started by the first ``prepare``: the environment (``env.py``), every
    ``commands.yaml`` probe, the read-only zip extraction and the test-owned
    deployment (``deploy.py``), the region, BPE and its subsystem, the
    TN3270 listener, the BPE staging datasets and the post-deployment catalog
    snapshot (``bpe.py``), and the version probes. Session setup is not
    scenario time: its commands are bounded by their own ``timeout_s``, and
    the scenario deadline is re-armed when it ends. A failure is kept and
    raised again for every later scenario.
Observations
    Before the session, every observation is declared, so that ``prepare``
    raises the real cause (GAP-MIS-009 without UniKix). A blocked session
    declares every observation too, so that its GAP-MIS-004 cause is raised.
    Otherwise only those whose ``commands.yaml`` entries probed available
    are: datasets, ``@AIX:<path>``, ``@AFTER:<JOB>:<LOGICAL>`` and
    ``@CATALOG`` through BPE jobs (GAP-MIS-007), ``@REGION-FILES`` through
    ``region_file_inquire`` (GAP-MIS-011), ``@TDQ:JOBS`` through
    ``jobs_capture`` (GAP-MIS-017) and screens through the checked loopback
    listener and ``s3270`` (GAP-MIS-005). ``@AFTER:<JOB>:<LOGICAL>`` is
    ``<LOGICAL>`` unloaded when ``<JOB>`` of the chain has completed, normally
    or abended, and before the next job is submitted, with the region files
    closed and then reopened; it is omitted when ``<JOB>`` never ran or the
    catalog does not hold ``<LOGICAL>`` then, and a job the chain does not
    run exactly once is a ``HarnessError`` before anything reaches the
    region. ``@TDQ:JOBS`` is correlated within a window opened just
    before the first step or job: no job ``jobs_capture`` lists then is ever
    attributed to the run, and a new ``TRNRPT00`` job counts only when its
    listed submission time, to the listing's precision, lies wholly within
    the steps. A job the precision cannot place inside or outside the steps,
    or a listing line that is not a job line, is GAP-MIS-017.
Before each scenario
    Quiescence is confirmed (no active region task, no unfinished BPE job),
    then the region is reset: its files closed, every cluster of
    ``resources.yaml`` reloaded from the scenario's preconditions or emptied,
    the catalog restored to the snapshot, sequential and GDG preconditions
    written, batch ``open``/``unavailable`` faults applied, ``JOBS`` emptied
    and enabled, every file reopened and enabled. A missing command raises
    ``AdapterUnavailable`` with its own gap, and a sequential dataset no
    precondition can provide raises ``MissingPrerequisite``, both before
    any of this is attempted.
After each scenario
    Every ``s3270`` session is ended. After each scenario that reached the
    region, whatever it returned, unfinished BPE jobs are cancelled and
    quiescence is confirmed again; the scenario's own outcome stands.
Blocked session
    A reset or quiescence check that was attempted and not confirmed, by a
    failure, a timeout or a missing prerequisite, stops the region and
    blocks the session: every remaining scenario raises GAP-MIS-004. A reset
    ended by ``AdapterUnavailable``, ``HarnessError`` or ``OSError`` raises
    GAP-MIS-004 for its own scenario too; one ended by a timeout or another
    error keeps that outcome.
Outcomes
    A command whose probe failed, that exits non-zero or exceeds its own
    ``timeout_s`` is ``AdapterUnavailable`` with its entry's gap; expiry of
    the scenario deadline is ``ScenarioTimeout``. No application outcome is
    ever inferred from a command failure. Region files are never closed or
    opened through ``CLOSEFIL``, ``OPENFIL`` or SDSF steps, because the copy's
    ``SDSF.cbl`` only displays ``DUMMY PROGRAM FOR SDSF`` (GAP-DOC-031), and
    CSD resources come from the ``catlg/tpe`` lists installed at region
    creation, never from ``CBADMCDJ``: a job with a ``DFHCSDUP`` step, alone
    or in a chain, has no confirmed UniKix equivalent and is
    ``AdapterUnavailable`` under GAP-MIS-016 before anything reaches the
    region.
Evidence
    Every scenario that passed session start gets immutable evidence under
    ``tests/reports/unikix/<execution_id>/<scenario_id>/`` (``evidence.py``);
    everything else the adapter writes lies inside the work root. Each raw
    observation joins the evidence as soon as it is acquired, so bytes read
    before a later failure are kept unchanged. A UniKix
    outcome never changes a baseline or this adapter: divergence and
    eligibility are decided by ``harness/report.py``.

Scenarios run serially, so one adapter instance serves one session from one
thread. Python 3.11 standard library and PyYAML only.
"""

from __future__ import annotations

import datetime as _dt
import functools
import io
import math
import re
import sys
import time
import zipfile
from collections.abc import Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

_TESTS_ROOT = str(Path(__file__).resolve().parents[2])
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

import harness  # noqa: E402
from adapters.base import (  # noqa: E402
    AFTER_PREFIX,
    AIX_PREFIX,
    BUSINESS_DATASETS,
    CATALOG,
    REGION_FILES,
    SCREEN_MAP,
    SCREEN_TEXT,
    SCREENS,
    TDQ_JOBS,
    TDQ_RECORD_LENGTH,
    Adapter,
    AdapterUnavailable,
    MissingPrerequisite,
    Result,
    format_fixed_records,
    format_region_files,
    parse_after_job,
    required_observations,
)
from adapters.unikix import bpe, deploy, evidence, tpe  # noqa: E402
from adapters.unikix import env as unikix_env  # noqa: E402
from harness import HarnessError, load_yaml, sha256_file, sha256_hex  # noqa: E402
from harness import inventory_tools as _inventory  # noqa: E402
from harness import scenarios as _scenarios  # noqa: E402

__all__ = ["UniKixAdapter"]

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

_GAP_QUIESCENCE = "GAP-MIS-004"  # quiescence or the reset not confirmed
_GAP_LISTENER = "GAP-MIS-005"  # the TN3270 listener and everything reached through it
_GAP_DATASETS = "GAP-MIS-007"  # dataset unloads, @CATALOG, @AIX:<path>
_GAP_REGION_CONTROL = "GAP-MIS-011"  # region file and TDQ control and inquiry
_GAP_BATCH_FAULT = "GAP-MIS-013"  # no batch fault after a successful open
_GAP_ONLINE_FAULT = "GAP-MIS-014"  # no record lock, IOERR or forced abend in the region
_GAP_CLOCK = "GAP-MIS-015"  # no confirmed way to set the UniKix clock
_GAP_CSD = "GAP-MIS-016"  # no confirmed UniKix equivalent of the CSD utility
_GAP_JOBS_CAPTURE = "GAP-MIS-017"  # the jobs the region submits through JOBS

# The z/OS CICS CSD utility CBADMCDJ runs against OEM.CICSTS.DFHCSD
# [app/jcl/CBADMCDJ.jcl:27-30]. The region's CSD resources come from the
# catlg/tpe lists installed at region creation, so a job step running it is
# never submitted.
_CSD_UTILITY = "DFHCSDUP"

# Everything the adapter can declare before its session has started.
_STATIC_OBSERVATIONS = frozenset(
    {
        BUSINESS_DATASETS,
        SCREENS,
        CATALOG,
        AIX_PREFIX + "*",
        AFTER_PREFIX + "*",
        REGION_FILES,
        TDQ_JOBS,
    }
)
_SCREEN_NAMES = frozenset({SCREENS, SCREEN_MAP, SCREEN_TEXT})

# Every generated BPE job runs through these three entries (bpe.py).
_BATCH_ENTRIES = ("bpe_translate", "bpe_submit", "bpe_sysout")
_CATALOG_ENTRIES = _BATCH_ENTRIES + ("catalog_list",)
_UNLOAD_ENTRIES = _CATALOG_ENTRIES + ("dataset_unload",)
_FILE_CONTROL = ("region_file_close", "region_file_open", "region_file_enable")
_TDQ_CONTROL = ("tdq_empty", "tdq_enable")
_REGION_RESTART = ("region_stop", "region_start")
_BPE_RESTART = ("bpe_stop", "bpe_start")
_SESSION_STARTS = ("region_start", "bpe_start", "bpe_system")
# The adapter attribute that flags what each session start may have started.
_START_FLAGS = {"region_start": "_region_started", "bpe_start": "_bpe_started"}

# An online fault without `after` is applied before this step index, i.e.
# after the sign-on step (AAP 0.4.3 "Online fault mechanisms").
_DEFAULT_FAULT_STEP = 1
_ONLINE_FAULT_GAPS = {"locked": _GAP_ONLINE_FAULT, "io-error": _GAP_ONLINE_FAULT,
                      "abend": _GAP_ONLINE_FAULT}

# The job CORPT00C writes to the TDQ JOBS [app/cbl/CORPT00C.cbl:84].
_REGION_JOB = "TRNRPT00"
# How long the JOBS capture keeps listing for a job the region wrote during
# the steps: the listing may show it only after the last AID returned. This
# bounds the listing alone; a submission time after the last step never
# counts. Bounded by the scenario deadline as well.
_JOBS_SETTLE_S = 10.0
_JOBS_POLL_S = 1.0

# The tail of each build log copied into a scenario's evidence.
_BUILD_LOG_EXCERPT_BYTES = 4096

_INPUTS_DIR = "inputs"
_DATASETS_FILE = "datasets.yaml"

# Where the UniKix copy keeps what a run executes, inside the zip. The unit
# selection mirrors harness/report.py's eligibility rule, so a record's
# source identity covers every member a run of its scenario executes.
_APP = deploy.APP_DIR_NAME + "/"
_ZIP_SKIPPED = "__MACOSX/"
_PROGRAM_DIR = _APP + "cbl/"
_SOURCE_DIRS = (_PROGRAM_DIR, _APP + "cpy/", _APP + "cpy-bms/")
_COPY_DIRS = ("cpy/", "cpy-bms/")
_PROGRAM_SUFFIXES = (".cl2", ".cbl")
_COPY_RE = re.compile(r"(?<![A-Za-z0-9-])COPY\s+(['\"]?)([A-Za-z0-9][A-Za-z0-9-]*)\1", re.I)
_CALL_RE = re.compile(r"(?<![A-Za-z0-9-])CALL\s+(['\"])([A-Za-z0-9#$@-]+)\1", re.I)
# The member that is the UniKix counterpart of an app/ source, by folder,
# formatted with the source's upper-case stem; app/cbl and app/csd resolve
# through _ZipIndex.program and _CSD_LISTS.
_APP_COUNTERPARTS = {
    "app/cpy": "cpy/{}",
    "app/cpy-bms": "cpy-bms/{}",
    "app/bms": "bms/{}.bms",
    "app/jcl": "jcl/{}",
    "app/proc": "proc/{}",
    "app/ctl": "ctl/{}",
}
# The catlg/tpe list that defines each kind of CSD DEFINE in the copy; a
# LIBRARY has none.
_CSD_LISTS = {
    "FILE": "fct.lst",
    "TRANSACTION": "pct.lst",
    "PROGRAM": "ppt.lst",
    "MAPSET": "ppt.lst",
    "TDQUEUE": "dct.lst",
}

# A BPE submission time as jobs_capture lists it: a date of three numbers and
# a time of hours and minutes, seconds and a fraction optional.
_SUBMITTED_RE = re.compile(
    r"\s*(\d{1,4})([-/.])(\d{1,2})\2(\d{1,4})[ T](\d{1,2}):(\d{2})(?::(\d{2})(?:[.,:](\d+))?)?\s*"
)
# The finest precision a listed submission time is read to: datetime's.
_MICROSECOND_DIGITS = 6
# How much of a listing line a refusal quotes.
_SHOWN_LINE_CHARS = 120
# CURDATE as CSDAT01Y shows it.
_SCREEN_DATE_RE = re.compile(r"(\d{2})/(\d{2})/(\d{2})")


def _unbounded() -> float:
    """The seconds-left function of session setup, which no scenario deadline
    bounds: ``deploy.deploy`` then caps each command at its own ``timeout_s``
    only (AAP 0.4.2 "Command outcomes")."""
    return math.inf


# --------------------------------------------------------------------------
# Source identity: the zip's members and the units a scenario executes
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class _ZipIndex:
    """The UniKix zip's file members with their sha256 and the COPY and CALL
    references of its programs and copybooks, read in memory."""

    digest: str
    hashes: Mapping[str, str]
    references: Mapping[str, frozenset[str]]

    def program(self, name: str) -> str | None:
        """The member holding the source of program ``name``, or ``None``."""
        for suffix in _PROGRAM_SUFFIXES:
            member = f"{_PROGRAM_DIR}{name.upper()}{suffix}"
            if member in self.hashes:
                return member
        return None

    def closure(self, members: Iterable[str]) -> set[str]:
        """``members`` with every copybook and program they COPY or CALL,
        transitively."""
        found = set(members)
        pending = sorted(found)
        while pending:
            for reference in self.references.get(pending.pop(), ()):
                if reference not in found:
                    found.add(reference)
                    pending.append(reference)
        return found


def _references(text: str, names: frozenset[str]) -> frozenset[str]:
    """The members a fixed-format COBOL source COPYs (``cpy/<NAME>``, else
    ``cpy-bms/<NAME>``) or CALLs by literal, read from columns 8-72 of its
    lines that are not comments. A name the zip lacks (``DFHAID``, a runtime
    routine such as ``CEEDAYS``) is no unit of the copy."""
    found: set[str] = set()
    for line in text.splitlines():
        if len(line) > 6 and line[6] in "*/":
            continue
        area = line[7:72]
        for match in _COPY_RE.finditer(area):
            name = match.group(2).upper()
            for folder in _COPY_DIRS:
                member = f"{_APP}{folder}{name}"
                if member in names:
                    found.add(member)
                    break
        for match in _CALL_RE.finditer(area):
            for suffix in _PROGRAM_SUFFIXES:
                member = f"{_PROGRAM_DIR}{match.group(2).upper()}{suffix}"
                if member in names:
                    found.add(member)
                    break
    return frozenset(found)


@functools.lru_cache(maxsize=2)
def _zip_index(path: str, digest: str) -> _ZipIndex:
    """The ``_ZipIndex`` of the zip at ``path`` whose sha256 is ``digest``;
    ``HarnessError`` when the file holds other bytes or is no zip. Nothing is
    extracted, so it serves scenarios that never reached a session."""
    try:
        data = Path(path).read_bytes()
    except OSError as exc:
        raise HarnessError(f"cannot read {deploy.ZIP_RELPATH}: {exc.strerror or exc}") from None
    if sha256_hex(data) != digest:
        raise HarnessError(f"{deploy.ZIP_RELPATH} changed while its source identity was read")
    hashes: dict[str, str] = {}
    texts: dict[str, str] = {}
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for info in archive.infolist():
                if info.is_dir() or info.filename.startswith(_ZIP_SKIPPED):
                    continue
                content = archive.read(info)
                hashes[info.filename] = sha256_hex(content)
                if info.filename.startswith(_SOURCE_DIRS):
                    texts[info.filename] = content.decode("latin-1")
    except (zipfile.BadZipFile, OSError, EOFError) as exc:
        raise HarnessError(f"cannot read {deploy.ZIP_RELPATH}: {exc}") from None
    names = frozenset(hashes)
    references = {member: _references(text, names) for member, text in texts.items()}
    return _ZipIndex(digest, hashes, references)


@functools.lru_cache(maxsize=1)
def _item_sources(inventory_file: str) -> dict[str, str]:
    """Each inventory item's ``source``, by id."""
    return {
        str(item["id"]): str(item.get("source") or "")
        for item in _inventory.items(_inventory.load(inventory_file))
        if isinstance(item, Mapping) and item.get("id") is not None
    }


@functools.lru_cache(maxsize=2)
def _csd_entries(repo_root: str) -> tuple[Any, ...]:
    return tuple(_inventory.parse_csd(Path(repo_root)))


@functools.lru_cache(maxsize=2)
def _resource_jobs(path: str) -> dict[str, Mapping[str, Any]]:
    """``resources.yaml`` ``jobs`` as written, for the ``procs`` and ``ctl``
    members that ``bpe.Resources`` does not keep."""
    document = load_yaml(path)
    jobs = document.get("jobs") if isinstance(document, Mapping) else None
    if not isinstance(jobs, Mapping):
        raise HarnessError(f"{bpe.RESOURCES_FILE.name}: jobs is not a mapping")
    return {str(name): entry for name, entry in jobs.items() if isinstance(entry, Mapping)}


def _names(value: Any) -> list[str]:
    return [str(name) for name in value] if isinstance(value, list) else []


def _counterpart(source: str, index: _ZipIndex, repo_root: Path) -> str | None:
    """The zip member that is the UniKix counterpart of an inventory item's
    ``source``, or ``None`` for a source that is no executed unit of the copy
    (documentation, data files, which fixtures replace, a CSD LIBRARY)."""
    try:
        path, ranges = _inventory.parse_source(source)
    except HarnessError:
        return None
    zip_prefix = _inventory.UNIKIX_ZIP + ":"
    if path.startswith(zip_prefix):
        member = path[len(zip_prefix):]
        return member if member in index.hashes else None
    folder, _, file_name = path.rpartition("/")
    stem = file_name.split(".", 1)[0].upper()
    if folder == "app/cbl":
        return index.program(stem)
    if folder in _APP_COUNTERPARTS:
        member = _APP + _APP_COUNTERPARTS[folder].format(stem)
        return member if member in index.hashes else None
    if path == _inventory.CSD_PATH and ranges:
        first = ranges[0][0]
        for entry in _csd_entries(str(repo_root)):
            if entry.first <= first <= entry.last:
                listing = _CSD_LISTS.get(entry.kind)
                member = f"{_APP}catlg/tpe/{listing}" if listing else None
                return member if member in index.hashes else None
    return None


# --------------------------------------------------------------------------
# Dates and times reported by the platform
# --------------------------------------------------------------------------


def _submitted_at(text: str) -> tuple[_dt.datetime, _dt.timedelta] | None:
    """A ``jobs_capture`` submission time as a naive local datetime and the
    precision it is given in; ``None`` when it is not one. A listing cuts the
    time it shows, so the instant lies from the datetime up to, not including,
    the datetime plus the precision: a minute without seconds, a second with
    seconds and no fraction, and 10**-d seconds with a fraction of d digits,
    read to the microsecond at most (further digits are cut). A four-digit
    first number is a year (Y-M-D); a four-digit last number is a year after
    M/D with ``/`` and after D.M or D-M otherwise."""
    match = _SUBMITTED_RE.fullmatch(text)
    if match is None:
        return None
    first, separator, middle, last, hour, minute, second, fraction = match.groups()
    if len(first) == 4:
        year, month, day = first, middle, last
    elif len(last) == 4:
        month, day = (first, middle) if separator == "/" else (middle, first)
        year = last
    else:
        return None
    digits = (fraction or "")[:_MICROSECOND_DIGITS]
    try:
        at = _dt.datetime(
            int(year),
            int(month),
            int(day),
            int(hour),
            int(minute),
            int(second or 0),
            int(digits.ljust(_MICROSECOND_DIGITS, "0")),
        )
    except ValueError:
        return None
    if second is None:
        precision = _dt.timedelta(minutes=1)
    elif digits:
        precision = _dt.timedelta(microseconds=10 ** (_MICROSECOND_DIGITS - len(digits)))
    else:
        precision = _dt.timedelta(seconds=1)
    return at, precision


def _iso_date(value: Any) -> _dt.date | None:
    """``value`` as an ISO date (``bpe.BatchOutcome.run_date``), or ``None``
    when it is not one, so the next run-date source is used."""
    if not isinstance(value, str):
        return None
    try:
        return _dt.date.fromisoformat(value)
    except ValueError:
        return None


def _screen_date(shown: str, today: _dt.date) -> _dt.date | None:
    """The date a ``CURDATE`` value ``mm/dd/yy`` shows, with the century that
    puts its year nearest ``today``'s, as ``tpe.py`` reads it: the region runs
    on this host and its clock cannot be fixed (GAP-MIS-015)."""
    match = _SCREEN_DATE_RE.fullmatch(shown)
    if match is None:
        return None
    month, day, short_year = (int(part) for part in match.groups())
    century = today.year - today.year % 100
    year = min(
        (century + offset + short_year for offset in (-100, 0, 100)),
        key=lambda candidate: abs(candidate - today.year),
    )
    try:
        return _dt.date(year, month, day)
    except ValueError:
        return None


# --------------------------------------------------------------------------
# Per-scenario plan
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class _OnlineFault:
    """An online ``unavailable`` fault: ``entry`` run with
    ``{placeholder: target}`` before step index ``step`` of every attempt."""

    step: int
    target: str
    entry: str
    placeholder: str


@dataclass
class _Plan:
    """What the reset before a scenario and its run need, settled before any
    UniKix work so that a missing capability or prerequisite is raised while
    the region is still untouched."""

    scenario_id: str
    online: bool
    steps: list[Any]
    sessions: int
    jobs: tuple[str, ...]
    dateparm: str | None
    loads: dict[str, bytes] = field(default_factory=dict)
    empties: list[str] = field(default_factory=list)
    sequential: list[tuple[str, bytes]] = field(default_factory=list)
    generations: list[tuple[str, bytes]] = field(default_factory=list)
    deleted: list[str] = field(default_factory=list)
    online_faults: list[_OnlineFault] = field(default_factory=list)
    # The @AFTER:<JOB>:<LOGICAL> observations expected, by job: (name,
    # logical) pairs in name order (UniKixAdapter._stage_observations).
    stages: dict[str, list[tuple[str, str]]] = field(default_factory=dict)


@dataclass
class _JobsWindow:
    """The submission window of a run whose ``@TDQ:JOBS`` is expected.

    ``known`` holds the job numbers ``jobs_capture`` listed just before the
    first step, and ``first`` the instant the first step began, taken after
    that listing. ``last`` is the instant the last step ended, set once when
    the run returns and never advanced while the capture keeps listing."""

    known: frozenset[int]
    first: _dt.datetime
    last: _dt.datetime | None = None


def _again(error: BaseException) -> BaseException:
    """A fresh copy of the session-setup failure ``error`` to raise for a
    later scenario, with the same reason and gap, so every scenario of the
    session reports the same cause."""
    if isinstance(error, AdapterUnavailable):
        copy: BaseException = AdapterUnavailable(error.reason, error.gap_id)
    elif isinstance(error, HarnessError):
        copy = HarnessError(str(error))
    else:
        return error
    copy.__cause__ = error
    return copy


def _never_started(commands: unikix_env.CommandSet, entry_id: str, mark: int) -> bool:
    """Whether the run of ``entry_id`` logged after ``mark`` certainly started
    nothing: it was logged, and only as ``refused`` or ``not-started``. A run
    that started may have launched a daemon, and so may one the log does not
    show, since a run interrupted before its outcome was logged leaves no
    record either."""
    outcomes = [
        record.get("outcome")
        for record in commands.log_since(mark)
        if record.get("kind") == "run" and record.get("id") == entry_id
    ]
    return bool(outcomes) and all(outcome in ("refused", "not-started") for outcome in outcomes)


# --------------------------------------------------------------------------
# The adapter
# --------------------------------------------------------------------------


class UniKixAdapter(Adapter):
    """The ``unikix`` adapter (pytest marker ``unikix``).

    Construction reads and writes nothing and starts no process; the session
    starts with the first ``prepare``. ``capable`` is the base rule
    (``harness.scenarios.capable_adapters``): every scenario with
    ``inputs.steps`` and every job of ``resources.yaml`` ``jobs``."""

    name = "unikix"

    # Constructed as Cls(work_root=..., repo_root=...) by tests/conftest.py:
    # the signature of adapters.base.Adapter, which takes no other option.
    def __init__(self, work_root: Path | str, repo_root: Path | str) -> None:
        super().__init__(work_root, repo_root)
        self.execution_id = evidence.make_execution_id()
        # Session, set by _start_session.
        self._env: unikix_env.UniKixEnvironment | None = None
        self._commands: unikix_env.CommandSet | None = None
        self._deployment: deploy.Deployment | None = None
        self._batch: bpe.BatchService | None = None
        self._versions: dict[str, str] = {}
        self._snapshot: Any = None
        self._session_error: BaseException | None = None
        self._session_ready = False
        # The session's own commands and sysout go into the evidence of the
        # first scenario that gets a capture.
        self._setup_unreported = False
        self._region_started = False
        self._bpe_started = False
        self._address: tpe.TN3270Address | None = None
        self._s3270: str | None = None
        self._listener_error: AdapterUnavailable | None = None
        # GAP-MIS-004: why quiescence or a reset was not confirmed; every
        # remaining scenario of the session is then blocked.
        self._broken: str | None = None
        # The adapter stopped the region and has not started it again.
        self._region_down = False
        # Per scenario.
        self._capture: evidence.Capture | None = None
        self._capture_id: str | None = None
        self._log_mark = 0
        self._plan: _Plan | None = None
        self._touched = False
        self._result: Result | None = None
        self._clock_set_for: str | None = None

    # ------------------------------------------------------------ session

    def _start_session(self) -> None:
        if self._session_ready:
            return
        if self._session_error is not None:
            raise _again(self._session_error)
        try:
            self._setup_session()
        except Exception as exc:
            error = self._release_failed_setup(exc)
            self._session_error = error
            if error is exc:
                raise
            raise error from exc
        finally:
            # Session setup is not scenario time: the scenario that asked for
            # it keeps its full deadline, which harness/runner.py reads back.
            self.deadline = time.monotonic() + harness.DEADLINE_S
        self._session_ready = True
        self._setup_unreported = True

    def _setup_session(self) -> None:
        root = deploy.deployment_root(self.work_root, self.execution_id)
        environment = unikix_env.UniKixEnvironment.load(self.work_root, root)
        commands = unikix_env.CommandSet.load()
        self._env, self._commands = environment, commands
        commands.probe_all(environment)
        zip_dir = deploy.extract_zip(self.work_root, self.repo_root)
        self._deployment = deploy.deploy(
            environment, commands, zip_dir, repo_root=self.repo_root, deadline=_unbounded
        )
        # The deployment sourced userenv and batchenv, which starts a new
        # probe generation: probes of the deployed tree run again against it.
        commands.probe_all(environment)
        for entry_id in _SESSION_STARTS:
            # Flagged before the command runs: a start can launch a detached
            # daemon and then fail or time out, and close() and
            # _release_failed_setup stop only what is flagged. bpe_system runs
            # inside BPE, which bpe_start's flag covers.
            flag = _START_FLAGS.get(entry_id)
            if flag is not None:
                setattr(self, flag, True)
            mark = commands.mark()
            try:
                commands.run(entry_id, environment)
            except BaseException:
                if flag is not None and _never_started(commands, entry_id, mark):
                    setattr(self, flag, False)
                raise
        # A listener that cannot be used blocks the online scenarios only
        # (GAP-MIS-005, or GAP-MIS-009 without s3270); batch scenarios still run.
        self._listener_error = self._start_listener()
        batch = bpe.BatchService(environment, commands)
        # Kept before its first job, so that a setup job left unfinished or
        # unidentified by a failure is still cancelled (_release_failed_setup).
        self._batch = batch
        batch.define_staging()
        self._snapshot = batch.snapshot_catalog()
        self._deployment.snapshot = self._snapshot
        self._versions = environment.versions(commands)

    def _release_failed_setup(self, exc: Exception) -> Exception:
        """Release, best effort, what the session setup that failed with
        ``exc`` may have left running: every setup job not seen completed or
        not identified is cancelled (``bpe.cancel_unfinished``), then BPE and
        the region are stopped where the setup may have started them
        (``_stop_started``). Each command is bounded by its own ``timeout_s``
        only, never by a scenario deadline.

        Returns the failure the session keeps: ``exc`` itself, unchanged, when
        no part of the release failed. Otherwise what could not be released is
        added to it. An ``AdapterUnavailable`` is replaced by one with the same
        gap whose reason ends with it, ``exc`` as its ``__cause__``, so this
        and every later scenario stay ``blocked`` under that gap and report
        it; any other exception gets it as a note, which its traceback shows."""
        problems: list[str] = []
        if self._batch is not None and self._batch.pending:
            try:
                self._batch.cancel_unfinished()
            except Exception as cancel_error:  # the setup failure stands whatever this did
                problems.append(f"cancelling the unfinished BPE jobs failed: {cancel_error}")
        problems.extend(self._stop_started())
        if not problems:
            return exc
        text = "releasing the partially started session: " + "; ".join(problems)
        if isinstance(exc, AdapterUnavailable):
            error = AdapterUnavailable(f"{exc.reason}; {text}", exc.gap_id)
            error.__cause__ = exc
            return error
        exc.add_note(text)
        return exc

    def _start_listener(self) -> AdapterUnavailable | None:
        """Start the TN3270 listener and check it against
        ``CARDDEMO_TN3270_ADDR``: a loopback address whose port is one the
        region's ``unikixrc.cfg`` listens on, and ``s3270`` on ``PATH``.
        Returns why online scenarios cannot run, or ``None``."""
        try:
            address = tpe.address_from_env()
            self._run("listener_start", bounded=False)
            output = self._run("listener_port", bounded=False).stdout
            pattern = self._pattern("listener_port", "port", ("port",))
            ports = sorted({int(match.group("port")) for match in pattern.finditer(output)})
            if not ports:
                raise AdapterUnavailable(
                    "listener_port found no TNServer*ListenPort in $KIXSYS/unikixrc.cfg",
                    _GAP_LISTENER,
                )
            if address.port not in ports:
                raise AdapterUnavailable(
                    f"{tpe.ADDRESS_ENV} names port {address.port}, but the region's listener "
                    f"listens on {', '.join(map(str, ports))}",
                    _GAP_LISTENER,
                )
            self._s3270 = tpe.find_s3270()
        except AdapterUnavailable as exc:
            return exc
        self._address = address
        return None

    # ------------------------------------------------------- observations

    def _cached(self, entry_id: str) -> bool:
        """The availability the session's probes found, without running one."""
        return self._commands is not None and self._commands.available(entry_id)

    def _can_close_region_cached(self) -> bool:
        return all(map(self._cached, _FILE_CONTROL)) or all(map(self._cached, _REGION_RESTART))

    def _observation_entries(self, name: str) -> tuple[str, ...]:
        """The ``commands.yaml`` entries observation ``name`` needs."""
        if name in _SCREEN_NAMES:
            return ()
        if name == REGION_FILES:
            return ("region_file_inquire",)
        if name == TDQ_JOBS:
            return ("jobs_capture",)
        if name == CATALOG:
            return _CATALOG_ENTRIES
        return _UNLOAD_ENTRIES

    def observations(self) -> set[str]:
        if not self._session_ready or self._broken is not None:
            # Declared in full, so that prepare raises the real cause first:
            # GAP-MIS-009 without a UniKix installation, or the GAP-MIS-004 of
            # a session that a reset or quiescence not confirmed has blocked.
            return set(_STATIC_OBSERVATIONS)
        found: set[str] = set()
        if self._can_close_region_cached():
            # BPE unloads and listings run only while the region files are closed.
            if all(map(self._cached, _CATALOG_ENTRIES)):
                found.add(CATALOG)
            if all(map(self._cached, _UNLOAD_ENTRIES)):
                found.update({BUSINESS_DATASETS, AIX_PREFIX + "*", AFTER_PREFIX + "*"})
        if self._cached("region_file_inquire"):
            found.add(REGION_FILES)
        if self._cached("jobs_capture"):
            found.add(TDQ_JOBS)
        if self._listener_error is None:
            found.add(SCREENS)
        return found

    def observation_gap(self, name: str) -> str | None:
        if self.can_observe(name):
            return None
        if name in _SCREEN_NAMES:
            return _GAP_LISTENER
        if name == REGION_FILES:
            return _GAP_REGION_CONTROL
        if name == TDQ_JOBS:
            return _GAP_JOBS_CAPTURE
        # @CATALOG, every @AIX:<path>, every @AFTER:<JOB>:<LOGICAL> and the
        # business datasets.
        return _GAP_DATASETS

    def _unobservable(self, name: str) -> str:
        """Why observation ``name`` cannot be produced in this session."""
        if name in _SCREEN_NAMES:
            cause = self._listener_error.reason if self._listener_error else "unknown"
            return f"observation {name} is not available on unikix: {cause}"
        missing = [
            f"{entry_id}: {self._commands.probe_reason(entry_id) or 'its probe failed'}"
            for entry_id in self._observation_entries(name)
            if not self._cached(entry_id)
        ]
        if name not in (REGION_FILES, TDQ_JOBS) and not self._can_close_region_cached():
            missing.append(
                "the region files cannot be closed for BPE: neither region file control "
                f"({', '.join(_FILE_CONTROL)}) nor {' and '.join(_REGION_RESTART)} is available"
            )
        return f"observation {name} is not available on unikix: {'; '.join(missing)}"

    # ----------------------------------------------------------- commands

    def _left(self, what: str, bounded: bool) -> float | None:
        """The seconds a command may take: the time left before the scenario
        deadline (``ScenarioTimeout`` once it has passed), or ``None`` outside
        scenario time, where a command is bounded by its own ``timeout_s``."""
        return self.check_deadline(what) if bounded else None

    def _run(self, entry_id: str, *, bounded: bool = True, **values: str | int) -> Any:
        return self._commands.run(
            entry_id, self._env, deadline_left=self._left(entry_id, bounded), **values
        )

    def _have(self, entry_id: str, *, bounded: bool = True) -> bool:
        return self._commands.available(
            entry_id, self._env, deadline_left=self._left(f"probing {entry_id}", bounded)
        )

    def _missing(self, entry_id: str) -> AdapterUnavailable:
        """The missing capability of an entry that is unavailable."""
        reason = self._commands.probe_reason(entry_id) or "its probe failed"
        return AdapterUnavailable(
            f"{entry_id} unavailable: {reason}", self._commands.entry(entry_id).gap
        )

    def _require(self, *entry_ids: str) -> None:
        for entry_id in entry_ids:
            if not self._have(entry_id):
                raise self._missing(entry_id)

    def _pattern(self, entry_id: str, key: str, groups: Sequence[str]) -> re.Pattern[str]:
        """The compiled ``parse.<key>`` regex of ``entry_id``, which must hold
        the named ``groups``; ``HarnessError`` otherwise."""
        text = self._commands.entry(entry_id).parse.get(key)
        if not text:
            raise HarnessError(f"commands.yaml {entry_id}.parse.{key} is missing")
        try:
            pattern = re.compile(text)
        except re.error as exc:
            raise HarnessError(f"commands.yaml {entry_id}.parse.{key} is not a regex: {exc}") from None
        absent = [group for group in groups if group not in pattern.groupindex]
        if absent:
            raise HarnessError(
                f"commands.yaml {entry_id}.parse.{key} lacks the group(s) {', '.join(absent)}"
            )
        return pattern

    def _note(self, name: str, text: str) -> None:
        """Append ``text`` to the adapter's own note ``name`` in the
        scenario's evidence."""
        if self._capture is None:
            return
        key = f"adapter/{name}.txt"
        previous = self._capture.sysout.get(key, "")
        if isinstance(previous, bytes):
            previous = previous.decode("utf-8", "replace")
        self._capture.sysout[key] = f"{previous}{text.rstrip()}\n"

    def _keep(self, name: str, data: bytes) -> None:
        """Put the raw observation ``data`` of ``name`` in the scenario's
        evidence as soon as it is acquired, so the bytes stay there unchanged
        when a later acquisition, reopen or wait fails."""
        if self._capture is not None:
            self._capture.datasets[name] = data

    # ----------------------------------------------------- region control

    def _file_control(self, *, bounded: bool = True) -> bool:
        return all(self._have(entry_id, bounded=bounded) for entry_id in _FILE_CONTROL)

    def _stop_region(self, *, bounded: bool = True) -> None:
        self._run("region_stop", bounded=bounded)
        self._region_down = True

    def _start_region(self, *, bounded: bool = True) -> None:
        self._run("region_start", bounded=bounded)
        self._region_down = False
        if self._listener_error is None:
            # The listener serves the region it was started for.
            self._run("listener_start", bounded=bounded)

    def _close_region_files(self) -> None:
        """Close every region file, as BPE dataset jobs need; without region
        file control the region is stopped instead."""
        if self._region_down:
            return
        if self._file_control():
            for name in self._batch.resources.region_files:
                self._run("region_file_close", file=name)
        else:
            self._stop_region()

    def _open_region_files(self) -> None:
        """Reopen and enable every region file, starting the region first when
        it was stopped."""
        if self._region_down:
            self._start_region()
            if not self._file_control():
                return
        for name in self._batch.resources.region_files:
            self._run("region_file_open", file=name)
            self._run("region_file_enable", file=name)

    def _reset_jobs_queue(self) -> None:
        """Empty and enable the TDQ ``JOBS``; without TDQ control the region
        restart that reopens the files resets it."""
        if self._region_down:
            return
        if all(self._have(entry_id) for entry_id in _TDQ_CONTROL):
            queue = self._batch.resources.tdq
            self._run("tdq_empty", queue=queue)
            self._run("tdq_enable", queue=queue)
        else:
            self._stop_region()

    # --------------------------------------------------------- quiescence

    def _quiescence_modes(self, *, bounded: bool = True) -> tuple[str, str]:
        """How quiescence is confirmed: the region by ``region_quiesce_query``
        or, while it is unavailable, by stopping it (``down`` when it already
        is); BPE by ``bpe_active_jobs`` or, while it is unavailable, by a BPE
        restart. Neither way available is a missing capability."""
        if self._region_down:
            region = "down"
        elif self._have("region_quiesce_query", bounded=bounded):
            region = "query"
        elif all(self._have(entry_id, bounded=bounded) for entry_id in _REGION_RESTART):
            region = "stop"
        else:
            missing = self._missing("region_quiesce_query")
            raise AdapterUnavailable(
                f"region quiescence cannot be confirmed: {missing.reason}, and "
                f"{' and '.join(_REGION_RESTART)} cannot stand in for it",
                missing.gap_id,
            )
        if self._have("bpe_active_jobs", bounded=bounded):
            batch = "query"
        elif all(self._have(entry_id, bounded=bounded) for entry_id in _BPE_RESTART):
            batch = "restart"
        else:
            missing = self._missing("bpe_active_jobs")
            raise AdapterUnavailable(
                f"BPE quiescence cannot be confirmed: {missing.reason}, and "
                f"{' and '.join(_BPE_RESTART)} cannot stand in for it",
                missing.gap_id,
            )
        return region, batch

    def _confirm_quiescence(self, *, bounded: bool = True) -> None:
        """No active region task and no unfinished BPE job. Every BPE job not
        seen completed is cancelled first (``bpe.cancel_unfinished``).

        Only an empty or all-blank ``region_quiesce_query`` output shows the
        region idle. Every other line must be an active task that
        ``parse.active`` reads, searched within that line: a line it cannot
        read makes the whole output unreadable rather than counting as idle.
        Listed tasks and unreadable output are ``AdapterUnavailable``
        (GAP-MIS-004), which the callers turn into a blocked session
        (``_break``). ``bounded`` (before a scenario) caps every command, BPE's
        included, at the time left before the scenario deadline, whose expiry
        is ``ScenarioTimeout``; unbounded (cleanup after a timeout or a
        failure) only their own ``timeout_s`` bounds them."""
        region, batch = self._quiescence_modes(bounded=bounded)
        if region == "query":
            output = self._run("region_quiesce_query", bounded=bounded).stdout
            pattern = self._pattern("region_quiesce_query", "active", ("task",))
            found: set[str] = set()
            unreadable: list[str] = []
            for line in output.splitlines():
                if not line.strip():
                    continue
                match = pattern.search(line)
                task = match.group("task") if match is not None else None
                if task:
                    found.add(task)
                else:
                    unreadable.append(line)
            tasks = sorted(found)
            if unreadable:
                listed = f"; it also lists active tasks: {', '.join(tasks)}" if tasks else ""
                raise AdapterUnavailable(
                    f"region_quiesce_query printed {unreadable[0]!r}, which parse.active cannot "
                    f"read: only empty output shows the region idle{listed}",
                    _GAP_QUIESCENCE,
                )
            if tasks:
                raise AdapterUnavailable(
                    f"the region still lists active tasks: {', '.join(tasks)}", _GAP_QUIESCENCE
                )
        elif region == "stop":
            # Stopping the region ends every task; the reset starts it again.
            self._stop_region(bounded=bounded)
        if batch == "query":
            self._batch.cancel_unfinished(deadline=self.deadline if bounded else None)
        else:
            for entry_id in _BPE_RESTART:
                self._run(entry_id, bounded=bounded)
            if self._have("bpe_system", bounded=bounded):
                self._run("bpe_system", bounded=bounded)

    def _break(self, reason: str) -> AdapterUnavailable:
        """Stop the region, best effort, and block every remaining scenario of
        the session under GAP-MIS-004 (AAP 0.4.6): none may run on leftover
        state."""
        if not self._region_down and self._commands is not None:
            try:
                if self._commands.available("region_stop"):
                    self._run("region_stop", bounded=False)
                    self._region_down = True
            except Exception as exc:  # the block below stands whatever the stop did
                reason = f"{reason}; stopping the region failed as well: {exc}"
        self._broken = reason
        self._note("adapter-errors", reason)
        return AdapterUnavailable(reason, _GAP_QUIESCENCE)

    # -------------------------------------------------------------- reset

    def _reset(self, plan: _Plan) -> None:
        """Confirm quiescence, then reset the region to the scenario's
        preconditions (AAP 0.4.6)."""
        self._confirm_quiescence()
        # Non-VSAM preconditions are $SEQFILES files the region never opens,
        # so they are written before the region files are closed. A dataset
        # no precondition can provide was refused by _plan_for
        # (bpe.check_sequential), before this reset was attempted.
        for logical, data in plan.sequential:
            self._batch.write_sequential(logical, data)
        self._close_region_files()
        for logical, data in plan.loads.items():
            self._batch.load_dataset(logical, data, deadline=self.deadline)
        for logical in plan.empties:
            self._batch.empty_cluster(logical, deadline=self.deadline)
        self._batch.reset_catalog(self._snapshot, deadline=self.deadline)
        for logical, data in plan.generations:
            self._batch.create_generation(logical, data, deadline=self.deadline)
        for logical in plan.deleted:
            self._batch.delete_dataset(logical, deadline=self.deadline)
        self._reset_jobs_queue()
        self._open_region_files()

    def _attempt_reset(self, plan: _Plan) -> None:
        """``_reset``, whose failure once attempted blocks the session: a
        quiescence check or reset that did not finish leaves the region in a
        state no later scenario may run on, so ``_break`` stops the region and
        every later scenario raises GAP-MIS-004. ``AdapterUnavailable``,
        ``HarnessError`` and ``OSError`` end this scenario under GAP-MIS-004
        as well. Any other error keeps its own outcome for this scenario:
        ``ScenarioTimeout`` stays ``failing`` with reason ``timeout``, and
        ``MissingPrerequisite`` stays ``implemented-unexecuted``."""
        self._touched = True
        try:
            self._reset(plan)
        except (AdapterUnavailable, HarnessError, OSError) as exc:
            raise self._break(
                f"the reset before {plan.scenario_id} was not confirmed: {exc}"
            ) from exc
        except Exception as exc:
            self._break(
                f"the reset before {plan.scenario_id} was not confirmed: "
                f"{type(exc).__name__}: {exc}"
            )
            raise

    # ------------------------------------------------------------ prepare

    def prepare(self, scenario: Any, workdir: Path) -> None:
        workdir = Path(workdir)
        self._plan, self._result, self._touched = None, None, False
        self._start_session()
        # This run's evidence starts here; the session-starting scenario also
        # carries the commands and BPE sysout of the session setup.
        self._capture, self._capture_id = evidence.Capture(), scenario.id
        self._log_mark = 0 if self._setup_unreported else self._commands.mark()
        self._take_batch_evidence("session/")
        self._setup_unreported = False
        if self._broken is not None:
            raise AdapterUnavailable(
                f"region not quiescent or reset not confirmed earlier in this session: "
                f"{self._broken}",
                _GAP_QUIESCENCE,
            )
        steps = scenario.inputs.get("steps")
        online = isinstance(steps, list)
        jobs = _scenarios.job_names(scenario)
        if not online and not jobs:
            raise HarnessError(f"{scenario.id}: unikix runs inputs.steps or inputs.job, nothing else")
        resolved = [self._batch.resources.job(job) for job in jobs]
        # Checked before anything reaches the region: a job, alone or in a
        # chain, with a step running the CSD utility is never submitted.
        csd_steps = dict.fromkeys(
            f"{resource.name} step {step.name}"
            for resource in resolved
            for step in resource.steps
            if (step.program or "").upper() == _CSD_UTILITY
        )
        if csd_steps:
            raise AdapterUnavailable(
                f"{_CSD_UTILITY} has no confirmed UniKix equivalent, so {', '.join(csd_steps)} "
                "cannot run; the region's CSD resources come from the catlg/tpe lists installed "
                "at region creation",
                _GAP_CSD,
            )
        if online and steps:
            tpe.check_sign_on(steps, scenario.expected.get("screens"))
            if self._listener_error is not None:
                raise AdapterUnavailable(self._listener_error.reason, self._listener_error.gap_id)
        # The runner's observation rule saw the full set declared before the
        # session started; it is applied again to what the session can produce.
        for name in sorted(required_observations(scenario.expected)):
            if not self.can_observe(name):
                raise AdapterUnavailable(self._unobservable(name), self.observation_gap(name))
        fixed = _scenarios.clock_mode(scenario) == "fixed"
        if fixed and not (self._commands.confirmed("clock_set") and self._have("clock_set")):
            raise AdapterUnavailable(
                "a fixed clock needs a confirmed clock_set: "
                f"{self._commands.probe_reason('clock_set') or 'it is not confirmed'}",
                _GAP_CLOCK,
            )
        plan = self._plan_for(scenario, workdir, online, steps if online else [], jobs)
        self._plan = plan
        self._attempt_reset(plan)
        if fixed:
            self._run("clock_set", clock=_scenarios.clock(scenario))
            self._clock_set_for = scenario.id
        self._capture.job_numbers.extend(self._batch.take_job_numbers())

    def _inputs(self, workdir: Path) -> dict[str, bytes]:
        """The precondition datasets the runner wrote: ``inputs/datasets.yaml``
        names each one and its ``inputs/<LOGICAL>.dat``."""
        listing = load_yaml(workdir / _INPUTS_DIR / _DATASETS_FILE, missing_ok=True) or {}
        if not isinstance(listing, Mapping):
            raise HarnessError(f"{_INPUTS_DIR}/{_DATASETS_FILE} is not a mapping")
        root = workdir.resolve()
        data: dict[str, bytes] = {}
        for logical, entry in listing.items():
            relative = entry.get("path") if isinstance(entry, Mapping) else None
            if not isinstance(relative, str):
                raise HarnessError(f"{_INPUTS_DIR}/{_DATASETS_FILE}: {logical} names no path")
            path = (workdir / relative).resolve()
            if root not in path.parents:
                raise HarnessError(f"{_INPUTS_DIR}/{_DATASETS_FILE}: {logical} lies outside {workdir}")
            data[str(logical)] = path.read_bytes()
        return data

    def _plan_for(
        self, scenario: Any, workdir: Path, online: bool, steps: list[Any], jobs: tuple[str, ...]
    ) -> _Plan:
        """The reset and run of ``scenario``, with every entry they need
        checked available first."""
        parameters = scenario.preconditions.get("parameters")
        parameters = parameters if isinstance(parameters, Mapping) else {}
        dateparm = parameters.get("DATEPARM")
        plan = _Plan(
            scenario_id=scenario.id,
            online=online,
            steps=list(steps),
            sessions=parameters.get("sessions", 1),
            jobs=jobs,
            dateparm=dateparm if isinstance(dateparm, str) else None,
        )
        plan.stages = self._stage_observations(
            scenario.id, jobs, required_observations(scenario.expected)
        )
        unprovidable = self._plan_faults(scenario, plan)
        resources = self._batch.resources
        for logical, data in sorted(self._inputs(workdir).items()):
            dataset = resources.dataset(logical)
            if len(data) % dataset.record_length:
                raise HarnessError(
                    f"{scenario.id}: {logical} holds {len(data)} bytes, not whole records of "
                    f"{dataset.record_length}"
                )
            if logical in plan.deleted:
                continue  # an open/unavailable fault leaves it out
            if dataset.kind == "cluster":
                plan.loads[logical] = data
            elif dataset.kind == "nonvsam":
                plan.sequential.append((logical, data))
            else:
                plan.generations.append((logical, data))
        plan.empties = [
            dataset.logical
            for dataset in resources.datasets.values()
            if dataset.kind == "cluster"
            and dataset.logical not in plan.loads
            and dataset.logical not in plan.deleted
        ]
        needed = list(_CATALOG_ENTRIES)
        if any(plan.loads.values()):
            needed.append("dataset_load")
        if plan.empties or not all(plan.loads.values()):
            needed.append("dataset_empty")
        if plan.generations:
            needed.append("dataset_create_generation")
        if plan.deleted:
            needed.append("dataset_delete")
        self._require(*needed)
        self._quiescence_modes()
        if not self._file_control() or not all(map(self._have, _TDQ_CONTROL)):
            self._require(*_REGION_RESTART)
        # A platform that cannot run the scenario at all leaves it blocked
        # (above); only on one that can is a fault it cannot provide reported.
        if unprovidable is not None:
            raise unprovidable
        # A dataset no precondition can provide is refused before any reset
        # work, because a reset that fails once attempted blocks the session.
        for logical, data in plan.sequential:
            self._batch.check_sequential(logical, data)
        return plan

    @staticmethod
    def _stage_observations(
        scenario_id: str, jobs: tuple[str, ...], wanted: Iterable[str]
    ) -> dict[str, list[tuple[str, str]]]:
        """The ``@AFTER:<JOB>:<LOGICAL>`` observations ``expected`` names, by
        job: ``(name, logical)`` pairs in name order. A name that is not a
        well-formed stage observation (``adapters.base.parse_after_job``) is
        not produced. One that names a job ``jobs`` (the scenario's
        ``inputs.job``; empty for ``inputs.steps``) does not run, or runs more
        than once, has no single end to unload the dataset at: the schema
        check (``harness.scenarios.validate``) refuses both, and so does this
        guard, with ``HarnessError``, before anything reaches the region."""
        stages: dict[str, list[tuple[str, str]]] = {}
        for name in sorted(wanted):
            parsed = parse_after_job(name)
            if parsed is None:
                continue
            job, logical = parsed
            runs = jobs.count(job)
            if runs != 1:
                if not jobs:
                    shown = f"the scenario runs no job, so no end of {job} exists"
                elif runs == 0:
                    shown = (
                        f"inputs.job ({', '.join(jobs)}) does not run {job}, so no end of it exists"
                    )
                else:
                    shown = (
                        f"inputs.job ({', '.join(jobs)}) runs {job} {runs} times, so its end is "
                        "not one point of the run"
                    )
                raise HarnessError(
                    f"{scenario_id}: expected names {name}, but {shown} to unload {logical} at"
                )
            stages.setdefault(job, []).append((name, logical))
        return stages

    def _plan_faults(self, scenario: Any, plan: _Plan) -> MissingPrerequisite | None:
        """Put ``preconditions.faults`` into ``plan`` as the adapter can
        provide them (AAP 0.4.3 "Online fault mechanisms") and return the
        first it cannot provide, as ``MissingPrerequisite`` under the gap of
        its class, for ``_plan_for`` to raise."""
        faults = scenario.preconditions.get("faults") or []
        if not isinstance(faults, list):
            raise HarnessError(f"{scenario.id}: preconditions.faults is not a list")
        unprovidable: MissingPrerequisite | None = None
        for index, fault in enumerate(faults):
            try:
                self._plan_fault(scenario.id, plan, index, fault)
            except MissingPrerequisite as exc:
                unprovidable = unprovidable or exc
        return unprovidable

    def _plan_fault(self, scenario_id: str, plan: _Plan, index: int, fault: Any) -> None:
        """Put fault ``index`` into ``plan``; ``MissingPrerequisite`` when no
        mechanism provides it."""
        if not isinstance(fault, Mapping):
            raise HarnessError(f"{scenario_id}: preconditions.faults[{index}] is not a mapping")
        target, operation = str(fault.get("target")), fault.get("operation")
        condition, after = fault.get("condition"), fault.get("after")
        what = f"{condition} {operation} fault on {target}"
        if condition == "notfnd":
            return  # the precondition datasets omit the record
        if not plan.online:
            if operation == "open" and condition == "unavailable":
                self._batch.resources.dataset(target)
                plan.deleted.append(target)
                return
            raise MissingPrerequisite(
                f"batch {what}: no mechanism injects a fault after a successful open",
                _GAP_BATCH_FAULT,
            )
        if condition in _ONLINE_FAULT_GAPS:
            raise MissingPrerequisite(
                f"online {what}: no mechanism holds a record lock, returns IOERR or forces an "
                f"abend in the TPE region",
                _ONLINE_FAULT_GAPS[condition],
            )
        if condition != "unavailable":
            raise HarnessError(f"{scenario_id}: faults[{index}] has the condition {condition!r}")
        plan.online_faults.append(self._online_fault(scenario_id, plan, target, after, what))

    def _online_fault(
        self, scenario_id: str, plan: _Plan, target: str, after: Any, what: str
    ) -> _OnlineFault:
        # `after` counts operations on the target, which no region command can
        # count; the adapter applies the fault at a step boundary instead,
        # before step index `after` (1 when absent: after the sign-on step), as
        # the scenarios that set it describe. A count that reaches past the
        # last step names no boundary, so that fault cannot be provided.
        step = _DEFAULT_FAULT_STEP if after is None else after
        if isinstance(step, bool) or not isinstance(step, int) or step < 0:
            raise HarnessError(f"{scenario_id}: the {what} has `after` {after!r}")
        if step >= len(plan.steps):
            raise MissingPrerequisite(
                f"online {what}: applied only between steps, and `after` {step} places it past "
                f"the last of {len(plan.steps)} step(s)",
                _GAP_REGION_CONTROL,
            )
        resources = self._batch.resources
        if target == resources.tdq:
            candidates, placeholder = ("tdq_disable",), "queue"
        elif target in resources.region_files:
            # A disabled file is what the derived baselines assume (CICS RESP
            # DISABLED); a closed one (NOTOPEN) is out of service too, so
            # closing stands in when disabling is unavailable.
            candidates, placeholder = ("region_file_disable", "region_file_close"), "file"
        else:
            raise HarnessError(
                f"{scenario_id}: the {what} names neither a region file of "
                f"{bpe.RESOURCES_FILE.name} nor the TDQ {resources.tdq}"
            )
        for entry_id in candidates:
            if self._have(entry_id):
                return _OnlineFault(step, target, entry_id, placeholder)
        raise MissingPrerequisite(
            f"online {what}: {'; '.join(self._missing(entry).reason for entry in candidates)}",
            _GAP_REGION_CONTROL,
        )

    # ------------------------------------------------------------ execute

    def execute(self, scenario: Any, workdir: Path) -> Result:
        workdir = Path(workdir)
        plan, capture = self._plan, self._capture
        if plan is None or capture is None or plan.scenario_id != scenario.id:
            raise HarnessError(f"{scenario.id}: execute without a prepare of the same scenario")
        self.check_deadline("before the run")
        started = time.monotonic()
        expected = required_observations(scenario.expected)
        return_code: int | None = None  # an online run has no return code
        screens: list[dict[str, str]] = []
        messages: list[str] = []
        jobs_window: _JobsWindow | None = None
        # The @AFTER:<JOB>:<LOGICAL> bytes acquired between the jobs; an
        # online run has none (_stage_observations refused any).
        staged: dict[str, bytes] = {}
        if plan.online:
            if TDQ_JOBS in expected:
                jobs_window = self._jobs_window()
            if plan.steps:

                def restart() -> None:
                    # Every step is driven again from the first and only that
                    # attempt's results are returned, so the window opens
                    # again: a job of the abandoned attempt is then a known one.
                    nonlocal jobs_window
                    self._attempt_reset(plan)
                    if jobs_window is not None:
                        jobs_window = self._jobs_window()

                outcome = tpe.drive(
                    plan.steps,
                    plan.sessions,
                    self.time_left,
                    capture,
                    self._fault_applier(plan),
                    workdir=workdir,
                    address=self._address,
                    expected_screens=scenario.expected.get("screens"),
                    restart=restart,
                )
                screens, messages = list(outcome.screens), list(outcome.messages)
            # An empty steps list observes the region state the reset left.
        else:
            if REGION_FILES in expected:
                # The state before the jobs, beside the @REGION-FILES after them
                # (CFG-OPENFIL-normal-01, CFG-CLOSEFIL-normal-01).
                capture.sysout["adapter/region-files-before.txt"] = self._region_files()
            if TDQ_JOBS in expected:
                jobs_window = self._jobs_window()

            def after_job(position: int, job: str) -> None:
                # The datasets as the job just completed left them, before a
                # later job of the chain rewrites, deletes or rolls them.
                staged.update(self._observe_stage(plan.stages.get(job, ())))

            batch = self._batch.run_jobs(
                list(plan.jobs),
                self.deadline,
                dateparm=plan.dateparm,
                after_job=after_job if plan.stages else None,
            )
            return_code, messages = batch.return_code, list(batch.messages)
            if batch.run_date is not None:
                capture.run_date["bpe_job_start_date"] = batch.run_date
        if jobs_window is not None:
            # The last step or job has ended; the window is never widened after.
            jobs_window.last = _dt.datetime.now()
        capture.job_numbers.extend(self._batch.take_job_numbers())
        datasets = self._observe(expected, jobs_window)
        datasets.update(staged)
        result = Result(
            return_code=return_code,
            datasets=datasets,
            screens=screens,
            messages=messages,
            elapsed_s=time.monotonic() - started,
        )
        # capture.datasets already holds each of these bytes (_observe,
        # _observe_stage).
        capture.result = result
        capture.job_numbers.extend(self._batch.take_job_numbers())
        self._result = result
        return result

    def _fault_applier(self, plan: _Plan) -> Callable[[int], None] | None:
        """The ``before_step`` callback of ``tpe.drive``: each online fault is
        applied before its step, in every attempt (a restart re-enables it)."""
        by_step: dict[int, list[_OnlineFault]] = {}
        for fault in plan.online_faults:
            by_step.setdefault(fault.step, []).append(fault)
        if not by_step:
            return None

        def before_step(index: int) -> None:
            for fault in by_step.get(index, ()):
                self._run(fault.entry, **{fault.placeholder: fault.target})
                self._note("faults", f"before steps[{index}]: {fault.entry} {fault.target}")

        return before_step

    def _observe(self, names: Iterable[str], window: _JobsWindow | None) -> dict[str, bytes]:
        """Every observation ``expected`` names after the last step or job
        (AAP 0.4.2), as raw bytes. Region state is read first, ``@TDQ:JOBS``
        within ``window``; BPE unloads then run with the region files closed,
        which are reopened after. Each observation goes into the evidence as
        soon as it is acquired (``_keep``), so a later failure leaves the bytes
        read before it there; the ones returned are those same bytes. The
        ``@AFTER:<JOB>:<LOGICAL>`` names are acquired at the end of their job
        (``_observe_stage``), never here; a malformed one is not produced."""
        wanted = sorted(
            name for name in set(names) - _SCREEN_NAMES if not name.startswith(AFTER_PREFIX)
        )
        found: dict[str, bytes] = {}
        if REGION_FILES in wanted:
            found[REGION_FILES] = self._region_files()
            self._keep(REGION_FILES, found[REGION_FILES])
        if TDQ_JOBS in wanted:
            if window is None:
                raise HarnessError("@TDQ:JOBS is observed only within a window opened before the run")
            # Kept by _jobs_queue before it waits for the jobs it captured.
            found[TDQ_JOBS] = self._jobs_queue(window)
        batch_names = [name for name in wanted if name not in (REGION_FILES, TDQ_JOBS)]
        if batch_names:
            self._close_region_files()
            found.update(self._batch.observe(batch_names, deadline=self.deadline, keep=self._keep))
            self._open_region_files()
        return found

    def _observe_stage(self, stage: Iterable[tuple[str, str]]) -> dict[str, bytes]:
        """The stage observations ``stage``, the ``(name, logical)`` pairs of
        one job (``_Plan.stages``), at the end of that job and before the next
        is submitted, as raw bytes under their stage names. Each ``<LOGICAL>``
        is unloaded through BPE as ``_observe`` unloads, with the region files
        closed, which are reopened after; one the catalog does not hold then
        is left out. Each goes into the evidence under its stage name as soon
        as it is acquired (``_keep``), so a later failure leaves it there. A
        job without stage observations leaves the region files as they are."""
        names = {logical: name for name, logical in stage}
        if not names:
            return {}
        self._close_region_files()
        found = self._batch.observe(
            sorted(names),
            deadline=self.deadline,
            keep=lambda logical, data: self._keep(names[logical], data),
        )
        self._open_region_files()
        return {names[logical]: data for logical, data in found.items()}

    def _region_files(self) -> bytes:
        """``@REGION-FILES`` for the 14 ``fct.lst`` files, from
        ``region_file_inquire``: ``Ope`` is open and ``Clo`` closed, ``Ena``
        enabled and ``Dis`` or ``Une`` disabled, case aside. An inquiry that
        lists a file twice or leaves one out is no observation."""
        gap = self._commands.entry("region_file_inquire").gap
        pattern = self._pattern("region_file_inquire", "file", ("name", "open", "enabled"))
        output = self._run("region_file_inquire").stdout
        wanted = self._batch.resources.region_files
        states: dict[str, tuple[bool, bool]] = {}
        for match in pattern.finditer(output):
            name = match.group("name").upper()
            if name not in wanted:
                continue
            if name in states:
                raise AdapterUnavailable(f"region_file_inquire listed {name} twice", gap)
            states[name] = (
                match.group("open").upper().startswith("OPE"),
                match.group("enabled").upper().startswith("ENA"),
            )
        missing = [name for name in wanted if name not in states]
        if missing:
            raise AdapterUnavailable(
                f"region_file_inquire did not list {', '.join(missing)}", gap
            )
        return format_region_files((name, *states[name]) for name in wanted)

    def _jobs_listing(self, name: str) -> list[tuple[int, str, str]]:
        """Every job ``jobs_capture`` lists, as (job number, upper-cased job
        name, submission time as listed), its output kept unchanged in the
        evidence as ``adapter/tdq-jobs/<name>``. Every nonblank line must be
        one job line as ``parse.job`` reads it from the line's start, and no
        job number may appear twice: anything else is no listing
        (GAP-MIS-017), never one without jobs. Blank output lists no job."""
        gap = self._commands.entry("jobs_capture").gap
        pattern = self._pattern("jobs_capture", "job", ("job_number", "name", "submitted"))
        # Listing is the run without a job number; printing one job's JCL is
        # the run with it (commands.yaml jobs_capture notes).
        output = self._run("jobs_capture", job_number="").stdout
        if self._capture is not None:
            self._capture.sysout[f"adapter/tdq-jobs/{name}"] = output
        jobs: list[tuple[int, str, str]] = []
        seen: set[int] = set()
        for line in output.splitlines():
            if not line.strip():
                continue
            match = pattern.match(line)
            fields = match.group("job_number", "name", "submitted") if match else None
            if fields is None or None in fields or not fields[0].isdecimal():
                shown = line[:_SHOWN_LINE_CHARS] + ("..." if len(line) > _SHOWN_LINE_CHARS else "")
                raise AdapterUnavailable(
                    f"jobs_capture listed a line that is not a job line as parse.job reads it: "
                    f"{shown!r}",
                    gap,
                )
            number_text, job_name, submitted = fields
            number = int(number_text)
            if number in seen:
                raise AdapterUnavailable(f"jobs_capture listed job {number} more than once", gap)
            seen.add(number)
            jobs.append((number, job_name.upper(), submitted))
        return jobs

    def _jobs_window(self) -> _JobsWindow:
        """Open the ``@TDQ:JOBS`` window as the first step or job begins:
        every job ``jobs_capture`` already lists, none of which is ever
        attributed to this run, then the instant the first step begins."""
        known = frozenset(number for number, _, _ in self._jobs_listing("listing-before-steps.txt"))
        first = _dt.datetime.now()
        return _JobsWindow(known, first)

    def _jobs_queue(self, window: _JobsWindow) -> bytes:
        """``@TDQ:JOBS``: the 80-byte records of each job named ``TRNRPT00``
        that BPE received from the region during the steps, in job-number
        order.

        A candidate is a ``TRNRPT00`` job absent from the listing taken before
        the first step (``window.known``); a job in that listing never is.
        Absence does not place a candidate after that listing, since a job may
        be listed only after it was submitted, so its listed time ``at``
        decides alone. Cut to the listing's precision ``p``, it says the job
        was submitted from ``at`` up to ``at + p``. The candidate is captured
        only when that span lies wholly within the steps: ``at`` no earlier
        than the instant the first step began (``window.first``) and ``at + p``
        no later than the instant the last step ended (``window.last``). It is
        left out, with a note, when ``at + p`` is no later than
        ``window.first`` or ``at`` is after ``window.last``. Any other
        candidate's span reaches across one of those instants, so that the
        precision cannot place it inside or outside the steps: it is no
        observation (GAP-MIS-017), never a captured job.

        The listing is taken again, for at most ``_JOBS_SETTLE_S``, until it
        shows a candidate captured or one that cannot be placed, because a job
        submitted during the steps may be listed only after the last AID
        returned; this decides only when listing stops, never which submission
        times belong to the run. Every captured job is registered with the
        batch service (``BatchService.adopt``) before any JCL is read or any
        job waited for, so cleanup cancels each one not seen completed. Each
        captured job's JCL and number then go into the evidence, and so do the
        ``@TDQ:JOBS`` bytes (``_keep``) before each job is waited for. Its
        completion and sysout (``_await_region_job``) are required: a sysout
        the batch service cannot poll or read is ``AdapterUnavailable`` under
        its own gap, never a ``@TDQ:JOBS`` without it."""
        if window.last is None:
            raise HarnessError("the JOBS window was not closed when the last step ended")
        gap = self._commands.entry("jobs_capture").gap
        card = self._batch.resources.job(_REGION_JOB).card_name.upper()
        first, last = window.first, window.last
        settle_until = time.monotonic() + _JOBS_SETTLE_S
        noted: set[int] = set()
        while True:
            selected: list[int] = []
            for number, name, submitted in self._jobs_listing("listing.txt"):
                if name != card or number in window.known:
                    continue
                parsed = _submitted_at(submitted)
                if parsed is None:
                    raise AdapterUnavailable(
                        f"jobs_capture listed {card} job {number} with the submission time "
                        f"{submitted!r}, which cannot be read",
                        gap,
                    )
                at, precision = parsed
                listed = f"{card} job {number} listed as submitted {submitted.strip()!r}"
                to = f"to {precision.total_seconds():g} s"
                if at + precision <= first:
                    outside = f"before the window opened at {first.isoformat(' ')}"
                elif at > last:
                    outside = f"after the last step ended at {last.isoformat(' ')}"
                elif first <= at and at + precision <= last:
                    selected.append(number)
                    continue
                else:
                    raise AdapterUnavailable(
                        f"{listed} ({to}) cannot be placed within the steps from "
                        f"{first.isoformat(' ')} to {last.isoformat(' ')}: the listing's precision "
                        "cannot show whether it was submitted during the steps",
                        gap,
                    )
                if number not in noted:
                    noted.add(number)
                    self._note("tdq-jobs/correlation", f"{listed} ({to}) is not captured: {outside}")
            if selected or time.monotonic() >= settle_until:
                break
            time.sleep(min(_JOBS_POLL_S, self.check_deadline("waiting for the JOBS capture")))
        numbers = sorted(selected)
        # Registered before any JCL is read or any job waited for, so that a
        # failure or timeout on one job leaves the others for cleanup to cancel.
        runs = {number: self._batch.adopt(card, str(number), "jobs_capture") for number in numbers}
        records: list[bytes] = []
        for number in numbers:
            text = self._run("jobs_capture", job_number=str(number)).stdout
            self._capture.sysout[f"adapter/tdq-jobs/{number}.jcl"] = text
            try:
                cards = bpe.card_image(text.splitlines(), f"the JCL of BPE job {number}")
            except HarnessError as exc:
                raise AdapterUnavailable(
                    f"jobs_capture printed JCL that is not 80-column card images: {exc}", gap
                ) from exc
            records.extend(line.encode("ascii") for line in cards.splitlines())
            self._capture.job_numbers.append(str(number))
        data = format_fixed_records(records, TDQ_RECORD_LENGTH)
        self._keep(TDQ_JOBS, data)
        for number in numbers:
            self._await_region_job(runs[number])
        return data

    def _await_region_job(self, run: bpe.JobRun) -> None:
        """Wait, within the scenario deadline, for a job the region submitted,
        which ``_jobs_queue`` registered with the batch service
        (``BatchService.adopt``) before any wait. Its completion and sysout
        are required for ``@TDQ:JOBS``, whose records its job number links to
        that sysout in the evidence. A sysout the batch service cannot poll or
        read is noted, then raised as the same ``AdapterUnavailable`` under
        its own gap; the deadline's passing is ``ScenarioTimeout``. A job not
        seen completed stays registered, so cleanup cancels it."""
        try:
            self._batch.wait(run, deadline=self.deadline)
        except AdapterUnavailable as exc:
            state = "completed" if run.completed else "not seen completed"
            self._note(
                "adapter-errors", f"BPE job {run.job_number} ({run.card_name}) {state}: {exc}"
            )
            raise

    # ------------------------------------------------------------ cleanup

    def _cancel_after_break(self, what: str) -> None:
        """Cancel, best effort, every BPE job of ``what`` not seen completed
        or not identified (``bpe.cancel_unfinished``), once the session is
        blocked: ``_break`` has stopped the region, best effort, and a job that
        no longer runs cannot change a dataset of a later scenario, but the
        scenario's unfinished BPE work is still cancelled (AAP 0.4.6). Bounded
        by the commands' own ``timeout_s`` only, since the scenario deadline
        may have passed. A failure is noted in the evidence; the session stays
        blocked whatever it did. Never raises."""
        if self._batch is None or not self._batch.pending:
            return
        try:
            self._batch.cancel_unfinished()
        except Exception as exc:  # the block stands whatever the cancellation did
            self._note(
                "adapter-errors",
                f"cancelling the BPE jobs of {what} after the session was blocked failed: {exc}",
            )

    def cleanup(self, scenario: Any, workdir: Path) -> None:
        """End every ``s3270`` session. For every run that reached the region,
        whatever it returned, cancel every unfinished BPE job and confirm
        quiescence again, bounded by the commands' own ``timeout_s``: a
        returned ``Result`` is no verdict, since an abended job or a later
        failed assertion is still a failure. The cancellation is attempted
        whether or not the region is confirmed: a region that lists tasks,
        prints unreadable output or cannot be queried blocks the session
        (``_break``), and its BPE jobs are then cancelled all the same
        (``_cancel_after_break``), as are those of a run whose reset blocked
        the session. What fails here acts only through the quiescence rule
        (GAP-MIS-004 for the remaining scenarios); the run's outcome stands.
        Never raises."""
        tpe.close_all()
        if not self._touched:
            return
        self._touched = False
        what = str(getattr(scenario, "id", scenario))
        if self._broken is None:
            try:
                self._confirm_quiescence(bounded=False)
                return
            except Exception as exc:  # cleanup never raises; the session is blocked instead
                self._break(f"quiescence after {what} not confirmed: {exc}")
        self._cancel_after_break(what)

    # ----------------------------------------------------------- identity

    def _zip(self) -> _ZipIndex:
        return _zip_index(str(deploy.zip_path(self.repo_root)), deploy.zip_sha256(self.repo_root))

    def _units(self, scenario: Any, index: _ZipIndex) -> set[str]:
        """The zip members a run of ``scenario`` executes, with the COPY and
        CALL closure of its programs and copybooks."""
        names = index.hashes
        inputs = scenario.inputs if isinstance(scenario.inputs, Mapping) else {}
        units: set[str] = set()
        if isinstance(inputs.get("steps"), list):
            # Any XCTL target can be reached at run time, so an online run
            # executes the whole online copy and the resource lists the region
            # was created from.
            for member in names:
                relative = member[len(_APP):] if member.startswith(_APP) else None
                if relative is None or relative.rsplit("/", 1)[-1].startswith("."):
                    continue
                if (
                    (relative.startswith("cbl/") and relative.endswith(".cl2"))
                    or relative == "cbl/CSUTLDTC.cbl"
                    or (relative.startswith("bms/") and relative.endswith(".bms"))
                    or relative.startswith(("cpy/", "cpy-bms/"))
                    or (relative.startswith("catlg/tpe/") and relative.endswith(".lst"))
                ):
                    units.add(member)
        jobs = _scenarios.job_names(scenario)
        resource_jobs = _resource_jobs(str(bpe.RESOURCES_FILE)) if jobs else {}
        for job in jobs:
            entry = resource_jobs.get(job, {})
            member = entry.get("member")
            if isinstance(member, str):
                units.add(_APP + member)
            units.update(f"{_APP}proc/{name.upper()}" for name in _names(entry.get("procs")))
            units.update(f"{_APP}ctl/{name.upper()}" for name in _names(entry.get("ctl")))
            units.update(filter(None, map(index.program, _names(entry.get("programs")))))
        if jobs:
            units.update(
                member for member in names
                if member.startswith(_APP + "catlg/bpe/") and member.endswith(".csv")
            )
        program = inputs.get("program")
        if isinstance(program, str) and index.program(program):
            units.add(index.program(program))
        observed = required_observations(scenario.expected)
        if REGION_FILES in observed:
            units.add(_APP + "catlg/tpe/fct.lst")
        if TDQ_JOBS in observed:
            units.add(_APP + "catlg/tpe/dct.lst")
        # Every listed item's UniKix counterpart, so the record covers what
        # harness/report.py requires of it: a copybook that decodes a job's
        # output, a CSD resource and its catlg/tpe list.
        sources = _item_sources(str(_inventory.INVENTORY_FILE))
        for item_id in sorted(set(map(str, scenario.inventory_ids))):
            if item_id in sources:
                counterpart = _counterpart(sources[item_id], index, self.repo_root)
                if counterpart is not None:
                    units.add(counterpart)
        return {member for member in index.closure(units) if member in names}

    def source_identity(self, scenario: Any) -> dict:
        """``{tree: unikix-copy, commit, zip_sha256, units}`` (AAP 0.4.5): the
        hashes of the zip's original members, read in memory, so it holds
        before any session as well; keys sorted."""
        index = self._zip()
        return {
            "commit": harness.git_head(self.repo_root),
            "tree": "unikix-copy",
            "units": {member: index.hashes[member] for member in sorted(self._units(scenario, index))},
            "zip_sha256": index.digest,
        }

    def build_key(self, scenario: Any = None) -> str | None:
        """What this adapter would deploy and issue: the zip and
        ``commands.yaml``."""
        commands = sha256_file(unikix_env.COMMANDS_FILE)
        return sha256_hex(f"{deploy.zip_sha256(self.repo_root)}\n{commands}\n")

    def run_date(self, scenario: Any) -> _dt.date | None:
        """The run date the relative matchers are judged against (AAP 0.4.6):
        the BPE start date of the first job, else the first step's screen
        date, else the scenario clock when a confirmed ``clock_set`` set it;
        ``None`` when the run showed none."""
        capture = self._capture if self._capture_id == getattr(scenario, "id", None) else None
        if capture is None:
            return None
        started = _iso_date(capture.run_date.get("bpe_job_start_date"))
        if started is not None:
            return started
        shown = capture.run_date.get("first_step_screen_date")
        if isinstance(shown, str):
            found = _screen_date(shown, _dt.date.today())
            if found is not None:
                return found
        if self._clock_set_for == scenario.id:
            return _dt.date.fromisoformat(_scenarios.clock(scenario)[:10])
        return None

    # ----------------------------------------------------------- evidence

    def _take_batch_evidence(self, prefix: str = "") -> None:
        if self._batch is None or self._capture is None:
            return
        for name, data in sorted(self._batch.take_evidence().items()):
            self._capture.sysout[prefix + name] = data

    def _environment_record(self) -> dict[str, Any]:
        """``Capture.environment``: installation and deployment paths, the
        environment file's hash, every command's availability, the version
        probes, the deployment substitutions, ``s3270`` and the listener."""
        record: dict[str, Any] = dict(self._env.as_record()) if self._env is not None else {}
        commands = self._commands
        record.update(
            {
                "execution_id": self.execution_id,
                "commands_sha256": sha256_file(commands.path) if commands is not None else None,
                "commands": {
                    entry_id: {
                        "status": commands.entry(entry_id).status,
                        "available": commands.available(entry_id),
                        "reason": commands.probe_reason(entry_id) or None,
                    }
                    for entry_id in commands.ids
                }
                if commands is not None
                else {},
                "versions": dict(self._versions),
                "deployment_substitutions": [
                    dict(item) for item in (self._deployment.substitutions if self._deployment else [])
                ],
                "s3270": self._s3270,
                "listener": {
                    "address": str(self._address) if self._address is not None else None,
                    "problem": str(self._listener_error) if self._listener_error is not None else None,
                },
                "session_blocked": self._broken,
            }
        )
        return record

    def _build_logs(self, scenario: Any) -> dict[str, bytes]:
        """The tail of the build log of every program and map the scenario
        executes (``deploy.Deployment.log_dir``)."""
        if self._deployment is None:
            return {}
        logs: dict[str, bytes] = {}
        for member in sorted(self._units(scenario, self._zip())):
            folder, _, file_name = member[len(_APP):].rpartition("/")
            if folder not in ("cbl", "bms"):
                continue
            stem = file_name.rsplit(".", 1)[0]
            path = Path(self._deployment.log_dir) / f"{stem}.log"
            try:
                data = path.read_bytes()
            except FileNotFoundError:
                continue
            except OSError as exc:
                data = f"(build log unreadable: {exc.strerror or exc})\n".encode()
            if len(data) > _BUILD_LOG_EXCERPT_BYTES:
                kept = data[-_BUILD_LOG_EXCERPT_BYTES:]
                data = f"(last {len(kept)} of {len(data)} bytes)\n".encode() + kept
            logs[f"adapter/build/{stem}.log"] = data
        return logs

    def write_evidence(self, scenario: Any, record: dict) -> str | None:
        """Write the scenario's evidence once, only for a scenario that
        passed session start; a scenario blocked before it writes nothing
        outside the work root."""
        if self._capture is None or self._capture_id != getattr(scenario, "id", None):
            return None
        capture = self._capture
        self._take_batch_evidence()
        capture.job_numbers.extend(self._batch.take_job_numbers() if self._batch else [])
        capture.environment = self._environment_record()
        records = self._commands.log_since(self._log_mark)
        if records:
            capture.sysout["adapter/commands.log"] = unikix_env.CommandSet.render_log(records)
        capture.sysout.update(self._build_logs(scenario))
        self._capture, self._capture_id = None, None
        return evidence.EvidenceWriter(harness.TESTS_ROOT, self.execution_id).write(
            scenario, record, capture
        )

    # -------------------------------------------------------------- close

    def _stop_started(self) -> list[str]:
        """Stop the region, unless the adapter has stopped it since, and BPE
        where the session may have started them, through ``region_stop`` and
        ``bpe_stop`` bounded by their own ``timeout_s``. Best effort: a stop
        that is unavailable or fails does not keep the other from running.
        Returns what could not be stopped, one line each."""
        stops = []
        if self._region_started and not self._region_down:
            stops.append("region_stop")
        if self._bpe_started:
            stops.append("bpe_stop")
        problems: list[str] = []
        for entry_id in stops:
            try:
                if not self._commands.available(entry_id):
                    reason = self._commands.probe_reason(entry_id) or "its probe failed"
                    problems.append(f"{entry_id} unavailable: {reason}; it may still run")
                    continue
                self._run(entry_id, bounded=False)
            except Exception as exc:  # the other stop runs whatever this one did
                problems.append(f"{entry_id} failed: {exc}")
                continue
            if entry_id == "region_stop":
                self._region_down = True
            else:
                self._bpe_started = False
        return problems

    def close(self) -> None:
        """Stop the region and BPE and end every ``s3270`` session, best
        effort; the deployment root stays in the work root (AAP 0.4.6)."""
        tpe.close_all()
        if self._commands is None or self._env is None:
            return
        # close is best effort and never raises: what could not be stopped
        # has no scenario left to report it.
        self._stop_started()
