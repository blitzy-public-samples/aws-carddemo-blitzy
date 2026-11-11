"""The adapter contract of the CardDemo characterization suite.

Every execution adapter implements ``Adapter``. Data flows one way through
three layers:

1. Scenarios (``tests/scenarios/<level>/<id>.yaml``) are platform neutral.
   They name logical datasets, the reserved observation names defined here,
   program and job names, transaction ids, AID keys and BMS field names, and
   never an adapter, a ddname, a DSN or a host.
2. An adapter turns one scenario into a run on one platform and returns a
   ``Result`` holding raw bytes, screen fields and messages exactly as the
   platform produced them. Each adapter maps the scenario vocabulary through
   its own files (``programs.yaml`` and ``jobs.yaml`` for ``gnucobol-batch``,
   ``resources.yaml`` and ``commands.yaml`` for ``unikix``). Adapters never
   read baselines.
3. The harness decodes the ``Result`` through ``layouts.yaml``, compares it
   with the baseline and reports (``harness/baselines.py``,
   ``harness/report.py``).

``harness/runner.py`` owns the status mapping:

* ``AdapterUnavailable(reason, gap_id)``: the runtime, a platform command or
  an observation the scenario expects is unavailable, so the scenario is
  ``blocked``.
* ``MissingPrerequisite(need, gap_id)``: the runtime is available, but a
  dataset, access or fault the preconditions need cannot be provided, so the
  scenario is ``implemented-unexecuted``.
* Expiry of the per-scenario deadline (``ScenarioTimeout``, or any exception
  raised once the deadline has passed) makes the scenario ``failing`` with
  reason ``timeout``.

Both exceptions carry a mandatory gap id of ``tests/inventory/gaps.yaml``, so
every scenario that cannot run names the register entry that says why.

Observations carried in ``Result.datasets`` besides business datasets, which
are keyed by their logical names:

``@CATALOG``
    One line per catalogued DSN, ``<name> <type> <generation>``, sorted
    (``format_catalog``).
``@AIX:<path>``
    The fixed-length records read through an alternate-index path,
    concatenated in alternate-key order (``format_fixed_records``).
``@AFTER:<JOB>:<LOGICAL>``
    The raw bytes of logical dataset ``<LOGICAL>`` as the adapter reads it at
    the end of job ``<JOB>`` of the scenario's ``inputs.job`` chain: after the
    job's last step ran, whether it ended normally or abended, and before the
    next job starts. A cluster comes unloaded in primary-key order, a
    sequential dataset as written, a GDG logical as its newest catalogued
    generation at that moment. It is absent when ``<JOB>`` never ran (an
    earlier job abended) or ``<LOGICAL>`` is not catalogued or has no file
    then, and acquiring it never changes what the jobs do
    (``after_job_observation``, ``parse_after_job``).
``@REGION-FILES``
    One line per region file, ``<name> open|closed enabled|disabled``, sorted
    by name (``format_region_files``).
``@TDQ:JOBS``
    The 80-byte records the region wrote to the TDQ ``JOBS``, concatenated in
    write order (``format_fixed_records``).

Each entry of ``Result.screens`` carries the reserved fields ``@map`` (the
identified map, or ``TEXT`` for an unformatted screen) and, for an
unformatted screen, ``@text`` (``screen_text``).

This module imports nothing from ``harness`` at module level, because
``harness/fixtures.py`` imports it. It writes no file, starts no process and
uses no network.
"""

from __future__ import annotations

import abc
import re
import time
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, ClassVar

__all__ = [
    "AFTER_PREFIX",
    "AIX_PREFIX",
    "BUSINESS_DATASETS",
    "CATALOG",
    "CATALOG_TYPES",
    "DEFAULT_DEADLINE_S",
    "GAP_ID_RE",
    "REGION_FILES",
    "REJECTS_DATASET",
    "SCREENS",
    "SCREEN_COLS",
    "SCREEN_MAP",
    "SCREEN_ROWS",
    "SCREEN_TEXT",
    "TDQ_JOBS",
    "TDQ_RECORD_LENGTH",
    "UNFORMATTED_MAP",
    "Adapter",
    "AdapterError",
    "AdapterUnavailable",
    "MissingPrerequisite",
    "Result",
    "ScenarioTimeout",
    "after_job_observation",
    "format_catalog",
    "format_fixed_records",
    "format_region_files",
    "marker_name",
    "observation_matches",
    "parse_after_job",
    "required_observations",
    "screen_text",
]

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

# Seconds left when no deadline is set. The runner always sets one, of the
# same 300 seconds, before it calls an adapter.
DEFAULT_DEADLINE_S = 300

# Reserved observation names: keys of Result.datasets and of a scenario's
# expected.datasets / expected.file_state.
CATALOG = "@CATALOG"
AIX_PREFIX = "@AIX:"
AFTER_PREFIX = "@AFTER:"  # @AFTER:<JOB>:<LOGICAL>, a dataset at the end of one job of the chain
REGION_FILES = "@REGION-FILES"
TDQ_JOBS = "@TDQ:JOBS"

# Reserved screen fields: keys of each Result.screens entry.
SCREEN_MAP = "@map"
SCREEN_TEXT = "@text"
UNFORMATTED_MAP = "TEXT"  # the @map of an unformatted (SEND TEXT) screen
SCREEN_ROWS = 24
SCREEN_COLS = 80
TDQ_RECORD_LENGTH = 80

# Capability tokens that Adapter.observations() may return besides the
# reserved names. They are never dataset names. BUSINESS_DATASETS stands for
# every logical dataset name (a key without a leading "@"); SCREENS stands for
# per-step screen captures with @map and @text. A pattern ending in "*"
# matches by prefix, as in "@AIX:*".
BUSINESS_DATASETS = "@DATASETS"
SCREENS = "@SCREENS"

# The logical dataset whose records carry the CBTRN02C reject reason codes
# that expected.reject_reason_codes asserts (harness/baselines.py reads them
# from it), so asserting reject codes needs this observation even when a
# scenario names no DALYREJS of its own.
REJECTS_DATASET = "DALYREJS"

CATALOG_TYPES = ("CLUSTER", "AIX", "PATH", "GDG", "GENERATION", "NONVSAM")

GAP_ID_RE = re.compile(r"^GAP-(DOC|DEF|STB|MIS|INF|UNC)-\d{3}$")

_ADAPTER_NAME_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
_GENERATION_RE = re.compile(r"^G\d{4}V00$")
_SCREEN_FIELDS = frozenset({SCREEN_MAP, SCREEN_TEXT})


# --------------------------------------------------------------------------
# Exceptions
# --------------------------------------------------------------------------


def _checked(text: Any, gap_id: Any, what: str) -> tuple[str, str]:
    if not isinstance(text, str) or not text.strip():
        raise ValueError(f"{what} must be a non-empty string, got {text!r}")
    if not isinstance(gap_id, str) or not GAP_ID_RE.fullmatch(gap_id):
        raise ValueError(f"gap_id must be a gap id like GAP-MIS-009, got {gap_id!r}")
    return text, gap_id


class AdapterError(Exception):
    """Base of the exceptions that end a scenario run with a register entry.

    ``gap_id`` names the ``tests/inventory/gaps.yaml`` entry that records why
    the scenario could not run."""

    gap_id: str


class AdapterUnavailable(AdapterError):
    """The runtime, a platform command, or an observation the scenario expects
    is unavailable: the scenario is ``blocked``.

    For example, ``AdapterUnavailable("page zero not mappable: ...",
    "GAP-MIS-002")``, or ``AdapterUnavailable("CARDDEMO_UNIKIX_ENV is not set",
    "GAP-MIS-009")``. Both arguments are mandatory."""

    def __init__(self, reason: str, gap_id: str) -> None:
        self.reason, self.gap_id = _checked(reason, gap_id, "reason")
        super().__init__(reason, gap_id)

    def __str__(self) -> str:
        return f"{self.reason} [{self.gap_id}]"


class MissingPrerequisite(AdapterError):
    """The runtime is available, but a dataset, access or fault the
    scenario's preconditions need cannot be provided: the scenario is
    ``implemented-unexecuted``.

    For example, a batch read, write, rewrite or close fault, which no adapter
    can inject, raises ``MissingPrerequisite(need, "GAP-MIS-013")``; an online
    record lock raises it with ``GAP-MIS-014``. Both arguments are
    mandatory."""

    def __init__(self, need: str, gap_id: str) -> None:
        self.need, self.gap_id = _checked(need, gap_id, "need")
        super().__init__(need, gap_id)

    def __str__(self) -> str:
        return f"{self.need} [{self.gap_id}]"


class ScenarioTimeout(Exception):
    """Raised by an adapter when the scenario deadline has passed while it
    waits on a subprocess, a poll or a 3270 wait. The runner reports the
    scenario ``failing`` with reason ``timeout``; it does the same for any
    exception raised after the deadline."""

    def __init__(self, detail: str = "") -> None:
        self.detail = detail
        super().__init__(detail)

    def __str__(self) -> str:
        return self.detail or "scenario deadline passed"


# --------------------------------------------------------------------------
# Result
# --------------------------------------------------------------------------


@dataclass
class Result:
    """What one execution of a scenario produced, as the platform produced it.

    ``return_code``
        The program's exit status or the highest step return code of a job;
        ``None`` means abended (the ``CEE3ABD`` marker or a signal on
        ``gnucobol-batch``, an ABEND on ``unikix``).
    ``datasets``
        Logical dataset name or reserved observation name (``@CATALOG``,
        ``@AIX:<path>``, ``@AFTER:<JOB>:<LOGICAL>``, ``@REGION-FILES``,
        ``@TDQ:JOBS``) -> raw bytes, never decoded.
    ``screens``
        One mapping per step: BMS field name -> value, plus ``@map`` and, for
        an unformatted screen, ``@text``.
    ``messages``
        In order: program ``DISPLAY`` lines, ``STEP <name> RC <nnnn>`` lines
        of a job, or the ``ERRMSG`` value of each step.
    ``elapsed_s``
        Wall time of the execution. It is never part of the deterministic
        projection.

    Decoding and comparison belong to ``harness/baselines.py``."""

    return_code: int | None = None
    datasets: dict[str, bytes] = field(default_factory=dict)
    screens: list[dict[str, str]] = field(default_factory=list)
    messages: list[str] = field(default_factory=list)
    elapsed_s: float = 0.0

    def __post_init__(self) -> None:
        if self.return_code is not None and (
            isinstance(self.return_code, bool) or not isinstance(self.return_code, int)
        ):
            raise TypeError(f"return_code must be an int or None, got {self.return_code!r}")

        if not isinstance(self.datasets, Mapping):
            raise TypeError(f"datasets must be a mapping, got {type(self.datasets).__name__}")
        datasets: dict[str, bytes] = {}
        for name, value in self.datasets.items():
            if not isinstance(name, str) or not name:
                raise TypeError(f"dataset names must be non-empty strings, got {name!r}")
            if isinstance(value, bytearray):
                value = bytes(value)
            elif not isinstance(value, bytes):
                raise TypeError(f"dataset {name} must hold bytes, got {type(value).__name__}")
            datasets[name] = value
        self.datasets = datasets

        if isinstance(self.screens, (str, bytes)) or not isinstance(self.screens, Sequence):
            raise TypeError(
                f"screens must be a list of mappings, got {type(self.screens).__name__}"
            )
        for index, screen in enumerate(self.screens):
            if not isinstance(screen, Mapping):
                raise TypeError(f"screens[{index}] must be a mapping, got {type(screen).__name__}")
            for name, value in screen.items():
                if not isinstance(name, str) or not isinstance(value, str):
                    raise TypeError(
                        f"screens[{index}] maps field names to str values, got {name!r}: {value!r}"
                    )
        self.screens = list(self.screens)

        if isinstance(self.messages, (str, bytes)) or not isinstance(self.messages, Sequence):
            raise TypeError(f"messages must be a list of str, got {type(self.messages).__name__}")
        for index, message in enumerate(self.messages):
            if not isinstance(message, str):
                raise TypeError(f"messages[{index}] must be str, got {type(message).__name__}")
        self.messages = list(self.messages)

        if isinstance(self.elapsed_s, bool) or not isinstance(self.elapsed_s, (int, float)):
            raise TypeError(f"elapsed_s must be a number, got {self.elapsed_s!r}")
        self.elapsed_s = float(self.elapsed_s)


# --------------------------------------------------------------------------
# Canonical observation encoders: both adapters build the reserved
# observations through these, so the same platform state gives the same bytes.
# --------------------------------------------------------------------------


def _token(value: Any, what: str) -> str:
    """A name that is one space-free ASCII token, as the line formats need."""
    if not isinstance(value, str) or not value or any(char.isspace() for char in value):
        raise ValueError(f"{what} must be a non-empty string without whitespace, got {value!r}")
    if not value.isascii():
        raise ValueError(f"{what} must be ASCII, got {value!r}")
    return value


def format_catalog(entries: Iterable[tuple[str, str, str | None]]) -> bytes:
    """The ``@CATALOG`` observation.

    Each entry is ``(name, type, generation)``. ``type`` is one of
    ``CATALOG_TYPES``. A ``GENERATION`` entry names the GDG base DSN and
    carries its generation as ``G<nnnn>V00``; every other type carries
    ``None`` (or ``"-"``). Lines are ``<name> <type> <generation or ->``,
    ASCII, sorted, without duplicates."""
    lines: set[str] = set()
    for entry in entries:
        name, kind, generation = entry
        _token(name, "catalog name")
        if kind not in CATALOG_TYPES:
            raise ValueError(f"catalog type of {name} must be one of {CATALOG_TYPES}, got {kind!r}")
        if kind == "GENERATION":
            if not isinstance(generation, str) or not _GENERATION_RE.fullmatch(generation):
                raise ValueError(f"generation of {name} must be G<nnnn>V00, got {generation!r}")
        elif generation not in (None, "-"):
            raise ValueError(f"{kind} entry {name} carries no generation, got {generation!r}")
        lines.add(f"{name} {kind} {generation or '-'}\n")
    return "".join(sorted(lines)).encode("ascii")


def format_region_files(entries: Iterable[tuple[str, bool, bool]]) -> bytes:
    """The ``@REGION-FILES`` observation.

    Each entry is ``(name, is_open, is_enabled)`` for one region file. Lines
    are ``<name> open|closed enabled|disabled``, ASCII, sorted by name. A
    region file has one state, so a name given twice is an error."""
    lines: dict[str, str] = {}
    for entry in entries:
        name, is_open, is_enabled = entry
        _token(name, "region file name")
        if not isinstance(is_open, bool) or not isinstance(is_enabled, bool):
            raise ValueError(f"open and enabled states of {name} must be bool, got {entry!r}")
        if name in lines:
            raise ValueError(f"region file {name} is listed twice")
        state = f"{'open' if is_open else 'closed'} {'enabled' if is_enabled else 'disabled'}"
        lines[name] = f"{name} {state}\n"
    return "".join(lines[name] for name in sorted(lines)).encode("ascii")


def format_fixed_records(records: Iterable[bytes], length: int) -> bytes:
    """Concatenate fixed-length records in the order given: ``@TDQ:JOBS``
    (``TDQ_RECORD_LENGTH``, in write order) and ``@AIX:<path>`` (the base
    cluster's record length, in alternate-key order). A record of any other
    length is an error, never padded or cut."""
    if isinstance(length, bool) or not isinstance(length, int) or length <= 0:
        raise ValueError(f"record length must be a positive int, got {length!r}")
    parts: list[bytes] = []
    for index, record in enumerate(records):
        if not isinstance(record, (bytes, bytearray)):
            raise TypeError(f"record {index} must be bytes, got {type(record).__name__}")
        if len(record) != length:
            raise ValueError(f"record {index} is {len(record)} bytes, expected {length}")
        parts.append(bytes(record))
    return b"".join(parts)


def screen_text(rows: Sequence[str]) -> str:
    """The ``@text`` value of an unformatted screen.

    Exactly ``SCREEN_ROWS`` rows of ``SCREEN_COLS`` characters joined with
    ``"\\n"``: each given row is right-padded with spaces, missing rows are
    blank, and every space is kept. More rows, a longer row or a row holding a
    line break is an error. ``adapters/unikix/tpe.py`` captures this value and
    derived baselines author it."""
    if isinstance(rows, str) or not isinstance(rows, Sequence):
        raise TypeError(f"rows must be a sequence of str, got {type(rows).__name__}")
    if len(rows) > SCREEN_ROWS:
        raise ValueError(f"a screen has {SCREEN_ROWS} rows, got {len(rows)}")
    lines: list[str] = []
    for index, row in enumerate(rows):
        if not isinstance(row, str):
            raise TypeError(f"row {index + 1} must be str, got {type(row).__name__}")
        if "\n" in row or "\r" in row:
            raise ValueError(f"row {index + 1} holds a line break")
        if len(row) > SCREEN_COLS:
            raise ValueError(
                f"row {index + 1} is {len(row)} characters, a screen has {SCREEN_COLS}"
            )
        lines.append(row.ljust(SCREEN_COLS))
    lines.extend(" " * SCREEN_COLS for _ in range(SCREEN_ROWS - len(lines)))
    return "\n".join(lines)


def marker_name(adapter_name: str) -> str:
    """The pytest marker of an adapter: its name with ``-`` as ``_``
    (``gnucobol-batch`` -> ``gnucobol_batch``)."""
    return adapter_name.replace("-", "_")


def _stage_part(value: Any, what: str) -> str:
    """One part of a stage observation name: a non-empty string without
    ``:``, which separates the parts, or whitespace."""
    if (
        not isinstance(value, str)
        or not value
        or ":" in value
        or any(char.isspace() for char in value)
    ):
        raise ValueError(
            f"{what} must be a non-empty string without ':' or whitespace, got {value!r}"
        )
    return value


def after_job_observation(job: str, logical: str) -> str:
    """The stage observation name ``@AFTER:<job>:<logical>``: logical dataset
    ``logical`` at the end of job ``job`` of the scenario's job chain.

    ``job`` and ``logical`` are non-empty names without ``:`` or whitespace,
    and ``logical`` does not start with ``@``: a stage observation holds a
    business dataset, never another observation. Anything else is a
    ``ValueError``."""
    _stage_part(job, "the job of a stage observation")
    _stage_part(logical, "the logical dataset of a stage observation")
    if logical.startswith("@"):
        raise ValueError(
            f"the logical dataset of a stage observation must not start with '@', got {logical!r}"
        )
    return f"{AFTER_PREFIX}{job}:{logical}"


def parse_after_job(key: Any) -> tuple[str, str] | None:
    """``(job, logical)`` of a stage observation name, the inverse of
    ``after_job_observation``; ``None`` for anything that is not a
    well-formed ``@AFTER:<JOB>:<LOGICAL>`` (another name, a value that is not
    a string, a missing or empty part, a third part, or a ``<LOGICAL>`` that
    starts with ``@``)."""
    if not isinstance(key, str) or not key.startswith(AFTER_PREFIX):
        return None
    job, separator, logical = key[len(AFTER_PREFIX) :].partition(":")
    if not separator:
        return None
    try:
        after_job_observation(job, logical)
    except ValueError:
        return None
    return job, logical


def observation_matches(key: str, patterns: Iterable[str]) -> bool:
    """Whether ``key`` (a logical dataset, a reserved observation, a reserved
    screen field or ``SCREENS``) is covered by the ``patterns`` an adapter's
    ``observations()`` returns.

    * A logical dataset (no leading ``@``) matches ``BUSINESS_DATASETS`` or
      its own name.
    * ``@map`` and ``@text`` match ``SCREENS``.
    * A reserved key matches an exact pattern, a pattern ending in ``*`` by
      prefix (``"@AIX:*"``, ``"@AFTER:*"``), or a pattern ending in ``:``,
      which names a whole family (``AIX_PREFIX`` covers every
      ``@AIX:<path>``, ``AFTER_PREFIX`` every ``@AFTER:<JOB>:<LOGICAL>``)."""
    available = frozenset(patterns)
    if key in _SCREEN_FIELDS:
        return SCREENS in available
    if not key.startswith("@"):
        return BUSINESS_DATASETS in available or key in available
    if key in available:
        return True
    for pattern in available:
        if pattern.endswith("*") and key.startswith(pattern[:-1]):
            return True
        if pattern.endswith(":") and key.startswith(pattern):
            return True
    return False


def required_observations(expected: Mapping[str, Any] | Any) -> set[str]:
    """The observations a scenario's ``expected`` section needs: the keys of
    ``expected.datasets`` and ``expected.file_state``, plus ``SCREENS`` when
    ``expected.screens`` is non-empty, plus ``REJECTS_DATASET`` whenever
    ``expected`` has a ``reject_reason_codes`` key (a list, an empty list or
    ``'baseline'``), because the reason codes are read from its records.

    ``expected`` is the section itself or a scenario carrying it as
    ``.expected``. The runner's observation rule checks each name with
    ``Adapter.can_observe`` before ``prepare`` and raises
    ``AdapterUnavailable(reason, adapter.observation_gap(name))`` for one the
    adapter cannot produce, so a scenario never passes on a success message
    alone."""
    if not isinstance(expected, Mapping):
        expected = getattr(expected, "expected", None)
    if not isinstance(expected, Mapping):
        return set()
    names: set[str] = set()
    for section in ("datasets", "file_state"):
        value = expected.get(section)
        if isinstance(value, Mapping):
            names.update(str(name) for name in value)
    if expected.get("screens"):
        names.add(SCREENS)
    if "reject_reason_codes" in expected:
        names.add(REJECTS_DATASET)
    return names



# --------------------------------------------------------------------------
# Adapter
# --------------------------------------------------------------------------


def _abstract_names(cls: type) -> set[str]:
    """The abstract methods ``cls`` leaves unimplemented. ``__init_subclass__``
    runs before ``abc`` sets ``cls.__abstractmethods__``, so this repeats the
    computation ``abc`` makes."""
    names = {
        name for name, value in vars(cls).items() if getattr(value, "__isabstractmethod__", False)
    }
    for base in cls.__bases__:
        for name in getattr(base, "__abstractmethods__", ()):
            if getattr(getattr(cls, name, None), "__isabstractmethod__", False):
                names.add(name)
    return names


class Adapter(abc.ABC):
    """One execution platform for scenarios.

    The runner (``harness/runner.py``) drives every scenario the same way: it
    creates a fresh ``<work root>/<scenario_id>/`` working directory, writes
    the materialized preconditions into ``<workdir>/inputs/``, sets
    ``deadline``, checks the observation rule with ``can_observe``, then calls
    ``prepare`` and ``execute``, and always ``cleanup`` in a ``finally``.

    A concrete subclass sets ``name`` (lower-case words joined by ``-``, such
    as ``gnucobol-batch`` or ``unikix``). Any adapter that implements this
    class and supplies its own mapping files runs the same scenarios and
    baselines unchanged. Construction must not raise for a missing runtime,
    create files or directories, or start processes: a missing runtime
    surfaces as ``AdapterUnavailable`` from ``prepare`` or ``execute``."""

    name: ClassVar[str] = ""

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        if _abstract_names(cls):
            return
        if not isinstance(cls.name, str) or not _ADAPTER_NAME_RE.fullmatch(cls.name):
            raise TypeError(
                f"adapter {cls.__qualname__} must set name to lower-case words joined by '-', "
                f"got {cls.name!r}"
            )

    def __init__(self, work_root: Path | str, repo_root: Path | str) -> None:
        self.work_root = Path(work_root)
        self.repo_root = Path(repo_root)
        # time.monotonic() value after which the scenario has timed out; the
        # runner sets it before prepare.
        self.deadline: float | None = None
        # unikix sets a per-session id; gnucobol-batch leaves None, because
        # harness/results.py derives its execution id.
        self.execution_id: str | None = None

    @property
    def marker(self) -> str:
        """The pytest marker of this adapter's scenarios."""
        return marker_name(self.name)

    def time_left(self) -> float:
        """Seconds until the scenario deadline, never negative;
        ``DEFAULT_DEADLINE_S`` while no deadline is set."""
        if self.deadline is None:
            return float(DEFAULT_DEADLINE_S)
        return max(0.0, self.deadline - time.monotonic())

    def check_deadline(self, what: str = "") -> float:
        """``time_left()``, raising ``ScenarioTimeout(what)`` once it is 0.
        Adapters call it before and while waiting on any subprocess, poll or
        3270 wait, and bound each wait by the value it returns."""
        left = self.time_left()
        if left <= 0.0:
            raise ScenarioTimeout(what)
        return left

    def capable(self, scenario: Any) -> bool:
        """Whether this adapter can run ``scenario``. The single capability
        rule is ``harness.scenarios.capable_adapters``; subclasses do not
        override this."""
        from harness.scenarios import capable_adapters  # harness imports this module

        return self.name in capable_adapters(scenario)

    def can_observe(self, key: str) -> bool:
        """Whether this adapter can produce the observation ``key`` (a name
        from ``required_observations``)."""
        return observation_matches(key, self.observations())

    @abc.abstractmethod
    def observations(self) -> set[str]:
        """The observation patterns this adapter can produce: any of
        ``BUSINESS_DATASETS``, ``CATALOG``, ``AIX_PREFIX + "*"``,
        ``AFTER_PREFIX + "*"``, ``REGION_FILES``, ``TDQ_JOBS`` and ``SCREENS``
        (``observation_matches`` defines how they match)."""

    @abc.abstractmethod
    def observation_gap(self, name: str) -> str | None:
        """The gap id to raise for an observation ``name`` this adapter
        cannot produce, or ``None`` for one it can. On ``unikix`` these are
        GAP-MIS-011 for ``@REGION-FILES``, GAP-MIS-017 for ``@TDQ:JOBS`` and
        GAP-MIS-007 for ``@CATALOG``, ``@AIX:<path>`` and dataset unloads."""

    @abc.abstractmethod
    def prepare(self, scenario: Any, workdir: Path) -> None:
        """Materialize the scenario's preconditions for this platform from
        ``<workdir>/inputs/<LOGICAL>.dat`` (exact fixed-length bytes written
        by the runner), ``inputs/datasets.yaml`` and
        ``inputs/parameters.yaml``. Writes stay inside the work root.

        Raises ``AdapterUnavailable`` when the runtime or a command is
        unavailable, and ``MissingPrerequisite`` when a dataset, access or
        fault cannot be provided (a batch fault other than an ``open``
        ``unavailable`` one raises it with GAP-MIS-013)."""

    @abc.abstractmethod
    def execute(self, scenario: Any, workdir: Path) -> Result:
        """Run the scenario's ``inputs`` and return what the platform
        produced: every dataset and observation the scenario's ``expected``
        names that exists after the run, as raw bytes.

        Raises ``AdapterUnavailable``, ``MissingPrerequisite``, or
        ``ScenarioTimeout`` when the deadline passes while waiting."""

    @abc.abstractmethod
    def cleanup(self, scenario: Any, workdir: Path) -> None:
        """Release what ``prepare`` and ``execute`` acquired: processes,
        sessions, jobs, per-scenario state. The runner always calls it, after
        success, failure and timeout alike. It must be idempotent and must not
        delete ``workdir``, which the runner removes or keeps."""

    @abc.abstractmethod
    def source_identity(self, scenario: Any) -> dict:
        """What the scenario ran, for result records and observed manifests:
        ``{tree: "app" | "unikix-copy", commit, zip_sha256 (unikix-copy
        only), units: {path: sha256}}``, with ``units`` covering each
        executed program, copybook, map, job source and resource list."""

    def build_key(self, scenario: Any = None) -> str | None:
        """A content hash of what this adapter built for the run, which the
        execution id incorporates; ``None`` when the adapter builds
        nothing."""
        return None

    def write_evidence(self, scenario: Any, record: dict) -> str | None:
        """Write the raw evidence of a run beside its result record and return
        its path; ``None`` when the adapter keeps no evidence of its own."""
        return None

    def close(self) -> None:
        """Release session-wide resources. Called once at session end; the
        base adapter holds none."""
        return None
