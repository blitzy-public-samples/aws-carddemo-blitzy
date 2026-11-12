"""Baselines: loading and writing, the decoded projection, matchers, the
comparison boundary, semantic versions and the bump check.

Every scenario has one baseline directory ``tests/baselines/<id>/``:

``expected.yaml``
    The decoded expected outcome: a mapping holding exactly the keys the
    scenario's ``expected`` names (``return_code``, ``messages``,
    ``reject_reason_codes``, ``datasets``, ``file_state``, ``screens``),
    plus an optional ``matchers`` mapping. Written as canonical
    ``harness.dump_yaml`` by ``write``.
``manifest.yaml``
    ``baseline_version`` (semver text, a new baseline starts at ``1.0.0``),
    ``source_commit``, ``capture_platform`` (``gnucobol-batch``, ``unikix``
    or ``derived``), ``capture_date`` (ISO date), ``source_identity`` and,
    for observed baselines only, ``execution_id`` and
    ``execution_fingerprint``. A derived baseline may say where its
    expectation came from in ``derived_from`` (``tech-spec`` or ``source``).
``datasets/<LOGICAL>.bin``
    Observed baselines only: the raw bytes of each business dataset named in
    ``expected.datasets`` or ``expected.file_state``, as the adapter returned
    them, exactly one file per such dataset; a missing or an extra ``.bin``
    is refused. Reserved observations (``@CATALOG``, ``@AIX:<path>``,
    ``@AFTER:<JOB>:<LOGICAL>``, ``@BEFORE:<JOB>:@REGION-FILES``, ...) have
    none.

Values in ``expected.yaml``
    ``return_code`` is an integer, or null for an abend. ``messages`` is the
    complete list of lines, compared exactly, or a mapping ``{contains:
    [...], count: n}``. ``reject_reason_codes`` lists the four-character
    reason texts of the DALYREJS records in written order (``'0102'``; a
    number such as ``102`` stands for its four-digit text). A
    ``datasets``/``file_state`` entry is the list of records, compared
    exactly in length and order (indexed datasets in primary-key order,
    sequential ones as written, ``@AIX:<path>`` in alternate-key order,
    ``@AFTER:<JOB>:<LOGICAL>`` in the order of its ``<LOGICAL>``), or a
    mapping of ``records`` (that exact list), ``contains`` (an ordered
    subsequence), ``count`` (the record count) and ``matchers`` (field ->
    matcher, qualifying that field in the records given). A record is a field
    map keyed as ``harness.codec.decode_record`` reports fields (a field path,
    a group, a dataset's own ``fields`` name or ``@raw`` also resolves), or a
    text line for ``TEXT/<n>`` layouts and the line observations
    (``@CATALOG``, ``@REGION-FILES`` and its region stage observations
    ``@BEFORE:<JOB>:@REGION-FILES`` and ``@AFTER:<JOB>:@REGION-FILES``,
    ``@TDQ:JOBS``). Only the fields a record
    names are compared. ``screens`` holds one ``{field: value}`` map per step,
    ``@map`` and ``@text`` included. The top-level ``matchers`` maps a field
    name to a matcher wherever an expected record or screen names that field.
    Text compares exactly, trailing spaces included; numbers are quoted
    decoded text with their scale (``'194.00'`` is not ``'194.0'``). An
    integer carries no scale, so a record field refuses it (a scenario's
    inline integer matches only its own text: ``194`` is ``'194'``, never
    ``'194.00'`` or ``'0194'``); ``{raw: <hex>}`` is a numeric field whose
    bytes are invalid. Floats are refused.

Matchers (``{match: <kind>, format?, offset_days?}``, kinds as
``harness.scenarios.MATCH_KINDS`` defines them) stand only for values derived
from a clock the adapter cannot fix. ``format`` is a strftime format built
from ``%Y %y %m %d %j %H %M %S %f %%`` whose literal text may surround the
value (a whole line); the directives render the kind's date (the run date,
plus ``offset_days`` days for ``run-date``, or the first or last day of its
month or year) and the run time. ``run-timestamp`` defaults to the DB2 form
``YYYY-MM-DD-HH.MM.SS.ffffff`` CBTRN02C writes to ``TRAN-PROC-TS``. On
``gnucobol-batch`` the run clock is the scenario clock, so every matcher
resolves to one exact value. Elsewhere a matcher checks the format and the
stated relation to the run date the runner passes, and fails with "run date
unknown" without one.

``compare`` checks every key the scenario's ``expected`` names, twice: against
the baseline value, and against the scenario's own inline value unless that is
``baseline`` (inline ``messages`` are an ordered subsequence of the lines).
When the baseline was captured on the running adapter, every business dataset
the scenario's ``expected`` names is also compared byte for byte with its
``.bin``, and one the baseline holds no ``.bin`` for is an ``error``; on
``unikix`` the bytes of matcher fields are left out. Raw evidence is never
rewritten.

``python tests/harness/baselines.py --check-bumps --base <revision>`` checks
every baseline directory of the working tree against the same directory at
``<revision>``: a changed or removed expected value or a removed ``.bin``
needs a MAJOR bump, added assertions or added or changed ``.bin`` files (and a
change of capture platform, such as a derived baseline replaced by an
observed one) at least MINOR, any other change at least PATCH; an unchanged
directory keeps its version, a new one starts at ``1.0.0``, and a baseline is
never deleted. Expected values are compared by what they assert, so
equivalent spellings are equal (a plain list and ``{records: <list>}``,
``contains`` with as many items as ``count``, a numeric reason code and its
four-digit text), and promoting a derived baseline to an observed one that
keeps every asserted value (a ``count`` and ``contains`` replaced by all the
records) is MINOR. Exit 0 when clean, 1 with one ``<id>: <rule>`` line per
violation, 2 when the check cannot run: an unknown revision, a repository
root that is not the top level of a git work tree, or git failing. A
directory whose ``manifest.yaml`` is missing at ``<revision>`` had no version
there, so it is judged as new.

Inside the repository the module writes only under ``tests/baselines/``
(``write``'s default ``baselines_dir``): ``write`` builds ``<id>/``
complete in a staging directory ``.<id>.<pid>.<8 hex>.new`` beside it and
swaps it in (by way of a pre-image ``.<id>.<pid>.<8 hex>.old`` where the
kernel cannot exchange two names), holding an exclusive ``flock`` on
``tests/baselines/`` itself, which ``load`` and the bump check hold shared. A
writer that dies leaves such directories behind; the next ``write``, or a
``load`` that finds ``<id>`` missing beside its pre-image, puts the pre-image
back or removes them, and the bump check passes over them. Outside the
repository, the bump check (``check_bumps``, ``bump_needed``) writes each
YAML file it compares, at the base and in the working tree, to a
``<n>.yaml`` it removes once parsed, in a private ``carddemo-bumps-*``
folder removed before it returns: under ``bump_needed``'s ``scratch`` when
given, else under the work root ``CARDDEMO_TEST_WORKDIR`` names when
``harness.usable_work_root`` accepts it, else in the system temporary
directory.
"""

from __future__ import annotations

import argparse
import calendar
import contextlib
import copy
import ctypes
import datetime as _dt
import difflib
import errno
import fcntl
import functools
import hashlib
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, field
from decimal import Decimal
from pathlib import Path
from typing import Any, Iterable, Iterator, Mapping, Sequence

_TESTS_ROOT = str(Path(__file__).resolve().parents[1])
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from adapters import base as _base  # noqa: E402
from harness import (  # noqa: E402
    BASELINES_DIR,
    REPO_ROOT,
    HarnessError,
    YAMLLoadError,
    cob_current_date,
    display_path,
    dump_yaml,
    load_yaml,
    usable_work_root,
)
from harness import codec as _codec  # noqa: E402
from harness import fixtures as _fixtures  # noqa: E402
from harness import layouts as _layouts  # noqa: E402
from harness import scenarios as _scenarios  # noqa: E402

__all__ = [
    "BASELINES_DIR",
    "CAPTURE_PLATFORMS",
    "FIXED_CLOCK_ADAPTERS",
    "MATCHERS",
    "NEW_VERSION",
    "SCREEN_CLOCK_FIELDS",
    "Baseline",
    "BaselineError",
    "BumpCheckError",
    "BumpNeed",
    "Matcher",
    "asserted_records",
    "bind_matchers",
    "bump",
    "bump_needed",
    "check_bumps",
    "check_matcher",
    "compare",
    "decode_result",
    "expected_problems",
    "field_identities",
    "is_matcher",
    "load",
    "main",
    "manifest_problems",
    "parse_version",
    "projection",
    "resolve_matchers",
    "version_change",
    "write",
]

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

NEW_VERSION = "1.0.0"
BUMP_LEVELS = ("major", "minor", "patch")
DERIVED = "derived"
CAPTURE_PLATFORMS = ("gnucobol-batch", "unikix", DERIVED)

EXPECTED_FILE = "expected.yaml"
MANIFEST_FILE = "manifest.yaml"
DATASETS_DIR = "datasets"
RAW_SUFFIX = ".bin"

MANIFEST_REQUIRED = (
    "baseline_version", "source_commit", "capture_platform", "capture_date", "source_identity"
)
MANIFEST_OBSERVED = ("execution_id", "execution_fingerprint")
MANIFEST_OPTIONAL = ("derived_from",)

# The adapters whose run clock is the scenario clock (COB_CURRENT_DATE), so
# every matcher resolves to one exact value and raw bytes compare in full.
FIXED_CLOCK_ADAPTERS = frozenset({"gnucobol-batch"})

# Matcher kind -> its default format. harness.scenarios.MATCH_KINDS is the
# schema authority for the kinds and their options.
MATCHERS = {
    "run-date": "%Y-%m-%d",
    "run-time": "%H:%M:%S",
    "run-timestamp": "%Y-%m-%d-%H.%M.%S.%f",
    "run-month-start": "%Y-%m-%d",
    "run-month-end": "%Y-%m-%d",
    "run-year-start": "%Y-%m-%d",
    "run-year-end": "%Y-%m-%d",
}

# Screen field -> the matcher kind the projection shows in its place. Every
# online program moves CSDAT01Y's WS-CURDATE-MM-DD-YY and WS-CURTIME-HH-MM-SS,
# built from the run clock, to these two fields of its map before sending it
# [app/cpy/CSDAT01Y.cpy:30-41] [app/cbl/COSGN00C.cbl:190-196], so their
# observed values are the run clock whether or not a step asserts them.
SCREEN_CLOCK_FIELDS = {"CURDATE": "run-date", "CURTIME": "run-time"}

# Markers of the decoded projection for what a result does not hold.
MISSING = "@missing"
ERROR = "@error"
RAW_KEY = _codec.RAW_KEY

STATUS_PASSED = "passed"
STATUS_FAILING = "failing"
REASON_ASSERTION = "assertion"
REASON_ERROR = "error"
REASON_DIVERGENCE = "cross-adapter-divergence"

DIFF_MAX_LINES = 400
# The most characters of a diff, its truncation note included.
DIFF_MAX_CHARS = 200_000
# The most characters of one string, a mapping key included, a diff shows; a
# longer one is cut to this many and a marker counting the rest. It exceeds
# every record of tests/fixtures/datasets.yaml (at most 500 bytes) and an
# unformatted screen's @text (24 rows of 80 characters and 23 line breaks),
# so only an outsize value is cut.
_DIFF_MAX_CHARS_PER_VALUE = 2_000
_PROBLEMS_PER_KEY = 20
# A diff of a list value starts this many identical items before the first
# item at which the expected and the actual list differ.
_DIFF_CONTEXT_ITEMS = 3

# Iterations of a loop whose cost grows with the data between two readings
# of the clock against the scenario deadline (_Deadline.tick).
_DEADLINE_EVERY = 256

# The DALYREJS reason: the 4 bytes CBTRN02C moves from its PIC 9(04)
# WS-VALIDATION-FAIL-REASON [app/cbl/CBTRN02C.cbl:180-182,446-451]. They are
# read as text, because the decoded number would drop the leading zero.
REJECTS_DATASET = _base.REJECTS_DATASET
REJECT_LAYOUT = "CBTRN02C/REJECT-RECORD"
REJECT_REASON_FIELD = "WS-VALIDATION-FAIL-REASON"

_LIST_SPEC_KEYS = frozenset({"records", "contains", "count", "matchers"})
_LINE_OBSERVATIONS = frozenset({_base.CATALOG, _base.REGION_FILES})
_LOGICAL_RE = re.compile(r"^[A-Z][A-Z0-9-]*$")
_VERSION_RE = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
_REASON_TEXT_RE = re.compile(r"[0-9]{4}")
_FINGERPRINT_RE = re.compile(r"[0-9a-f]{64}")
_DECIMAL_TEXT_RE = re.compile(r"[+-]?[0-9]+(?:\.[0-9]+)?")
_INT_TEXT_RE = re.compile(r"0|-?[1-9][0-9]*")  # the text str() gives an int

# The baselines folder relative to the repository root, as git names it.
BASELINES_REL = BASELINES_DIR.relative_to(REPO_ROOT).as_posix()


class BaselineError(HarnessError):
    """A baseline directory, file or value that breaks the baseline contract."""


class BumpCheckError(HarnessError):
    """The bump check cannot run: the revision is unknown, the repository root
    is not the top level of a git work tree, or git fails."""


# --------------------------------------------------------------------------
# Semantic versions
# --------------------------------------------------------------------------


def parse_version(version: Any) -> tuple[int, int, int]:
    """``"1.2.3"`` -> ``(1, 2, 3)``. Anything that is not ``MAJOR.MINOR.PATCH``
    text without leading zeros, or holds a part too long to read as an
    integer, raises ``BaselineError``."""
    match = _VERSION_RE.fullmatch(version) if isinstance(version, str) else None
    if match is None:
        raise BaselineError(
            f"baseline_version {_show(version)} is not a semantic version MAJOR.MINOR.PATCH (quote it)"
        )
    try:
        major, minor, patch = (int(part) for part in match.groups())
    except ValueError:
        raise BaselineError(
            f"baseline_version {_show(version)} is too large to read: a part has more than "
            f"{sys.get_int_max_str_digits()} digits, Python's integer conversion limit"
        ) from None
    return major, minor, patch


def bump(version: str, level: str) -> str:
    """``version`` raised by one ``level`` (``major``, ``minor`` or ``patch``),
    resetting the lower parts: ``bump("1.2.3", "minor") == "1.3.0"``."""
    major, minor, patch = parse_version(version)
    if level == "major":
        return f"{major + 1}.0.0"
    if level == "minor":
        return f"{major}.{minor + 1}.0"
    if level == "patch":
        return f"{major}.{minor}.{patch + 1}"
    raise BaselineError(f"bump level {level!r} is not one of {', '.join(BUMP_LEVELS)}")


def version_change(base: str, new: str) -> str:
    """How ``new`` differs from ``base``: ``major``, ``minor`` or ``patch``
    (the highest part raised), ``none`` when equal, ``decrease`` when lower."""
    old, current = parse_version(base), parse_version(new)
    if current == old:
        return "none"
    if current < old:
        return "decrease"
    if current[0] > old[0]:
        return "major"
    if current[1] > old[1]:
        return "minor"
    return "patch"


_LEVEL_RANK = {"none": 0, "patch": 1, "minor": 2, "major": 3}
_PATCH, _MINOR, _MAJOR = 1, 2, 3
_RANK_NAME = {_PATCH: "PATCH", _MINOR: "MINOR", _MAJOR: "MAJOR"}


# --------------------------------------------------------------------------
# Matchers
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class _Directive:
    """One strftime directive a matcher format may hold: whether it renders
    the date or the time, the text it matches, and its rendering."""

    part: str  # "date" or "time"
    pattern: str
    render: Any  # (date | datetime) -> str
    maximum: int | None = None  # the highest value a time part may hold


_DIRECTIVES = {
    "Y": _Directive("date", r"[0-9]{4}", lambda value: f"{value.year:04d}"),
    "y": _Directive("date", r"[0-9]{2}", lambda value: f"{value.year % 100:02d}"),
    "m": _Directive("date", r"[0-9]{2}", lambda value: f"{value.month:02d}"),
    "d": _Directive("date", r"[0-9]{2}", lambda value: f"{value.day:02d}"),
    "j": _Directive("date", r"[0-9]{3}", lambda value: f"{value.timetuple().tm_yday:03d}"),
    "H": _Directive("time", r"[0-9]{2}", lambda value: f"{value.hour:02d}", 23),
    "M": _Directive("time", r"[0-9]{2}", lambda value: f"{value.minute:02d}", 59),
    "S": _Directive("time", r"[0-9]{2}", lambda value: f"{value.second:02d}", 59),
    "f": _Directive("time", r"[0-9]{6}", lambda value: f"{value.microsecond:06d}", 999_999),
}

# The largest run-date offset in days (3652058): the span of the dates
# datetime.date represents, so no run date can carry a larger one.
_OFFSET_DAYS_MAX = (_dt.date.max - _dt.date.min).days


@functools.lru_cache(maxsize=None)
def _format_parts(fmt: str) -> tuple[tuple[str, str], ...]:
    """``fmt`` as ``("text", literal)`` and ``("directive", letter)`` parts.
    Only numeric directives are allowed: they render the same under every
    locale, so a format matches the same text on every platform."""
    parts: list[tuple[str, str]] = []
    literal: list[str] = []
    index = 0
    while index < len(fmt):
        char = fmt[index]
        if char != "%":
            literal.append(char)
            index += 1
            continue
        if index + 1 >= len(fmt):
            raise BaselineError(f"matcher format {fmt!r} ends with a lone '%'")
        letter = fmt[index + 1]
        index += 2
        if letter == "%":
            literal.append("%")
            continue
        if letter not in _DIRECTIVES:
            raise BaselineError(
                f"matcher format {fmt!r} holds %{letter}; use only "
                f"{' '.join('%' + name for name in _DIRECTIVES)} and %%"
            )
        if literal:
            parts.append(("text", "".join(literal)))
            literal = []
        parts.append(("directive", letter))
    if literal:
        parts.append(("text", "".join(literal)))
    return tuple(parts)


@functools.lru_cache(maxsize=None)
def _format_regex(fmt: str) -> re.Pattern[str]:
    pattern = "".join(
        f"({_DIRECTIVES[value].pattern})" if kind == "directive" else re.escape(value)
        for kind, value in _format_parts(fmt)
    )
    return re.compile(pattern, re.DOTALL)


def is_matcher(value: Any) -> bool:
    """Whether ``value`` is a matcher ``{match: <kind>, ...}``."""
    return isinstance(value, Mapping) and "match" in value


def _parse_clock(clock: Any) -> _dt.datetime:
    """The scenario clock ``YYYY-MM-DD HH:MM:SS.hh`` as a datetime whose
    microseconds are the hundredths times 10000, the precision COBOL's
    ``CURRENT-DATE`` carries."""
    cob_current_date(clock)  # raises HarnessError for anything malformed
    moment = _dt.datetime.strptime(clock[:19], "%Y-%m-%d %H:%M:%S")
    return moment.replace(microsecond=int(clock[20:22]) * 10_000)


def _as_date(value: Any) -> _dt.date | None:
    if value is None:
        return None
    if isinstance(value, _dt.datetime):
        return value.date()
    if isinstance(value, _dt.date):
        return value
    if isinstance(value, str):
        try:
            return _dt.date.fromisoformat(value.strip()[:10])
        except ValueError:
            pass
    raise HarnessError(f"run date {value!r} is not a date or an ISO date YYYY-MM-DD")


@dataclass(frozen=True)
class Matcher:
    """A validated matcher: its kind, strftime format and day offset."""

    kind: str
    format: str
    offset_days: int = 0

    @classmethod
    def of(cls, value: Any) -> "Matcher":
        """The ``Matcher`` of a matcher mapping; a malformed one raises
        ``BaselineError`` naming what is wrong."""
        if not is_matcher(value):
            raise BaselineError(f"{_show(value)} is not a matcher {{match: <kind>, ...}}")
        kind = value["match"]
        options = _scenarios.MATCH_KINDS.get(kind) if isinstance(kind, str) else None
        if options is None:
            raise BaselineError(
                f"matcher kind {_show(kind)} is not one of {', '.join(_scenarios.MATCH_KINDS)}"
            )
        unknown = sorted(_key_text(key) for key in value if key != "match" and key not in options)
        if unknown:
            raise BaselineError(
                f"matcher {kind} takes {', '.join(sorted(options)) or 'no option'}, "
                f"not {', '.join(unknown)}"
            )
        fmt = value.get("format", MATCHERS[kind])
        if not isinstance(fmt, str) or not fmt:
            raise BaselineError(f"matcher {kind}: format must be a non-empty strftime format")
        _format_parts(fmt)
        offset = value.get("offset_days", 0)
        if isinstance(offset, bool) or not isinstance(offset, int):
            raise BaselineError(f"matcher {kind}: offset_days must be a whole number of days")
        if abs(offset) > _OFFSET_DAYS_MAX:
            # The offset itself is not shown: it may be too long to render.
            raise BaselineError(
                f"matcher {kind}: offset_days must lie within -{_OFFSET_DAYS_MAX}..+{_OFFSET_DAYS_MAX} days, "
                f"the span of the dates {_dt.date.min.isoformat()} to {_dt.date.max.isoformat()}"
            )
        return cls(kind, fmt, offset)

    def target_date(self, run_date: _dt.date) -> _dt.date:
        """The date the matcher's date directives render, for ``run_date``.
        Raises ``BaselineError`` naming the matcher, the run date and the
        offset when ``run_date`` plus ``offset_days`` falls outside the dates
        ``datetime.date`` can represent (0001-01-01 to 9999-12-31)."""
        if self.kind == "run-date":
            try:
                return run_date + _dt.timedelta(days=self.offset_days)
            except OverflowError as exc:
                raise BaselineError(
                    f"matcher {self.describe()}: the run date {run_date.isoformat()} "
                    f"{self.offset_days:+d} days lies outside the dates "
                    f"{_dt.date.min.isoformat()} to {_dt.date.max.isoformat()}"
                ) from exc
        if self.kind == "run-month-start":
            return run_date.replace(day=1)
        if self.kind == "run-month-end":
            return run_date.replace(day=calendar.monthrange(run_date.year, run_date.month)[1])
        if self.kind == "run-year-start":
            return _dt.date(run_date.year, 1, 1)
        if self.kind == "run-year-end":
            return _dt.date(run_date.year, 12, 31)
        return run_date  # run-time, run-timestamp

    def describe(self) -> str:
        """The matcher as diffs show it, e.g. ``<run-date %m/%d/%y>``."""
        offset = f" {self.offset_days:+d} days" if self.offset_days else ""
        return f"<{self.kind}{offset} {self.format}>"

    def resolve(self, clock: str | _dt.datetime) -> str:
        """The one value the matcher stands for under a fixed run clock:
        the format rendered with the kind's date and the clock's time (the
        hundredths padded to six digits for ``%f``)."""
        moment = clock if isinstance(clock, _dt.datetime) else _parse_clock(clock)
        value = _dt.datetime.combine(self.target_date(moment.date()), moment.time())
        return "".join(
            _DIRECTIVES[text].render(value) if kind == "directive" else text
            for kind, text in _format_parts(self.format)
        )

    def check(
        self,
        actual: Any,
        *,
        exact: bool,
        clock: str | _dt.datetime | None = None,
        run_date: Any = None,
    ) -> str | None:
        """Why ``actual`` does not satisfy the matcher, or ``None`` when it
        does. ``exact``: it must equal ``resolve(clock)``. Otherwise it must
        have the format, its date parts must render the kind's date for
        ``run_date`` and its time parts must be a valid time of day."""
        if not isinstance(actual, str):
            return f"expected text matching {self.describe()}, got {_show(actual)}"
        if exact:
            if clock is None:
                raise HarnessError(f"matcher {self.kind}: an exact comparison needs the run clock")
            wanted = self.resolve(clock)
            if actual == wanted:
                return None
            return f"expected {_show(wanted)} ({self.describe()} under the clock {clock}), got {_show(actual)}"
        match = _format_regex(self.format).fullmatch(actual)
        if match is None:
            return f"{_show(actual)} does not have the format {self.describe()}"
        directives = [text for kind, text in _format_parts(self.format) if kind == "directive"]
        day = _as_date(run_date)
        target = None if day is None else self.target_date(day)
        for letter, text in zip(directives, match.groups()):
            directive = _DIRECTIVES[letter]
            if directive.part == "time":
                if int(text) > directive.maximum:
                    return f"{_show(actual)} holds %{letter} {text}, which is not a valid time part"
                continue
            if target is None:
                return f"run date unknown: {_show(actual)} cannot be related to {self.describe()}"
            if text != directive.render(target):
                return (
                    f"{_show(actual)} is not {self.describe()} of the run date {day.isoformat()}: "
                    f"%{letter} should be {directive.render(target)}"
                )
        return None


def check_matcher(
    matcher: Any,
    actual: Any,
    clock: str | _dt.datetime | None,
    exact: bool,
    run_date: Any = None,
) -> str | None:
    """Why ``actual`` does not satisfy the matcher mapping ``matcher``, or
    ``None``: exactly its value under ``clock`` when ``exact``, otherwise its
    format and its relation to ``run_date`` (``Matcher.check``)."""
    return Matcher.of(matcher).check(actual, exact=exact, clock=clock, run_date=run_date)


def resolve_matchers(value: Any, clock: str | _dt.datetime, exact: bool = True) -> Any:
    """``value`` (a matcher, or data holding matchers) with every matcher
    validated and, when ``exact``, replaced by the one text it stands for
    under the fixed ``clock``: ``{match: run-timestamp}`` under the default
    clock ``2022-07-06 10:00:00.00`` is ``2022-07-06-10.00.00.000000``.
    Without ``exact`` no matcher has a single value, so matchers are kept as
    they are; ``check_matcher`` then judges each observed value against the
    run date. The input is not modified."""
    if is_matcher(value):
        matcher = Matcher.of(value)
        return matcher.resolve(clock) if exact else dict(value)
    if isinstance(value, Mapping):
        return {key: resolve_matchers(item, clock, exact) for key, item in value.items()}
    if isinstance(value, list):
        return [resolve_matchers(item, clock, exact) for item in value]
    return value


def _show(value: Any, limit: int = 160) -> str:
    """``repr(value)`` clipped to ``limit`` characters. It never raises: an
    integer too long for Python's decimal conversion limit, alone or inside a
    container, is shown by its size instead."""
    try:
        text = repr(value)
    except ValueError:
        if isinstance(value, int):
            # log10(2) ~ 0.30103: the digit count without a decimal conversion.
            text = f"<int of about {abs(value).bit_length() * 30103 // 100000 + 1} digits>"
        else:
            text = f"<{type(value).__name__} holding an integer too long to show>"
    return text if len(text) <= limit else text[: limit - 3] + "..."


def _key_text(key: Any) -> str:
    """A mapping key, or another value a message writes as text, as messages
    name it: ``str(key)``, or ``_show(key)`` for an integer too long to write
    in decimal."""
    try:
        return str(key)
    except ValueError:
        return _show(key)


# --------------------------------------------------------------------------
# The baseline contract
# --------------------------------------------------------------------------


def _floats(value: Any, where: str) -> Iterable[str]:
    """Paths of every float under ``value``; the data comes from load_yaml or
    from decoded results, so its depth is bounded."""
    if isinstance(value, float):
        yield where
    elif isinstance(value, Mapping):
        for key, item in value.items():
            yield from _floats(item, f"{where}.{_key_text(key)}" if where else _key_text(key))
    elif isinstance(value, list):
        for index, item in enumerate(value):
            yield from _floats(item, f"{where}[{index}]")


def _check_matcher_at(value: Any, where: str, problems: list[str]) -> None:
    try:
        Matcher.of(value)
    except BaselineError as exc:
        problems.append(f"{where}: {exc}")


def _check_field_value(value: Any, where: str, problems: list[str]) -> None:
    """A value of an expected record field: the decoded text (a number with
    its scale, as ``harness.codec.to_plain`` renders it), a matcher or
    ``{raw: <hex>}``. An integer carries no scale, so it is refused."""
    if is_matcher(value):
        _check_matcher_at(value, where, problems)
    elif isinstance(value, Mapping):
        if set(value) != {"raw"} or not isinstance(value["raw"], str):
            problems.append(f"{where}: a mapping value is a matcher or {{raw: <hex>}}")
    elif isinstance(value, int) and not isinstance(value, bool):
        problems.append(
            f"{where}: {_show(value)} is an integer; "
            "write the decoded text with its scale, quoted (text compares exactly)"
        )
    elif not isinstance(value, str):
        problems.append(f"{where}: {_show(value)} must be text, a matcher or {{raw: <hex>}}")


def _check_items(items: Any, where: str, problems: list[str], item_check: Any) -> None:
    if not isinstance(items, list):
        problems.append(f"{where}: must be a list")
        return
    for index, item in enumerate(items):
        item_check(item, f"{where}[{index}]", problems)


def _check_record(record: Any, where: str, problems: list[str]) -> None:
    if isinstance(record, str):
        return
    if is_matcher(record):
        _check_matcher_at(record, where, problems)
    elif isinstance(record, Mapping):
        for name, value in record.items():
            if not isinstance(name, str):
                problems.append(f"{where}: field key {_show(name)} must be text")
            else:
                _check_field_value(value, f"{where}.{name}", problems)
    else:
        problems.append(f"{where}: a record is a field map, a text line or a matcher")


def _check_line(line: Any, where: str, problems: list[str]) -> None:
    if is_matcher(line):
        _check_matcher_at(line, where, problems)
    elif not isinstance(line, str):
        problems.append(f"{where}: {_show(line)} must be a line of text or a matcher")


def _check_reason(code: Any, where: str, problems: list[str]) -> None:
    if isinstance(code, str) and _REASON_TEXT_RE.fullmatch(code):
        return
    if isinstance(code, int) and not isinstance(code, bool) and 0 <= code <= 9999:
        return
    problems.append(f"{where}: {_show(code)} is not a four-digit reason text such as '0102'")


def _check_list_spec(
    value: Any, where: str, problems: list[str], item_check: Any, matchers_ok: bool
) -> None:
    """A list value: the exact list, or ``{records?, contains?, count?,
    matchers?}`` asserting at least one of the first three."""
    if isinstance(value, list):
        _check_items(value, where, problems, item_check)
        return
    if value == _scenarios.BASELINE:
        problems.append(f"{where}: expected.yaml holds the value itself, not 'baseline'")
        return
    if not isinstance(value, Mapping):
        problems.append(f"{where}: must be a list or a mapping of records, contains, count and matchers")
        return
    allowed = _LIST_SPEC_KEYS if matchers_ok else _LIST_SPEC_KEYS - {"matchers"}
    unknown = sorted(_key_text(key) for key in value if key not in allowed)
    if unknown:
        problems.append(f"{where}: unknown key(s) {', '.join(unknown)}; allowed: {', '.join(sorted(allowed))}")
    if not ({"records", "contains", "count"} & set(value)):
        problems.append(f"{where}: asserts nothing; give records, contains or count")
    for key in ("records", "contains"):
        if key in value:
            _check_items(value[key], f"{where}.{key}", problems, item_check)
    if "count" in value:
        count = value["count"]
        if isinstance(count, bool) or not isinstance(count, int) or count < 0:
            problems.append(f"{where}.count: must be a record count, 0 or more")
    if "matchers" in value:
        _check_matcher_map(value["matchers"], f"{where}.matchers", problems)


def _check_matcher_map(value: Any, where: str, problems: list[str]) -> None:
    if not isinstance(value, Mapping):
        problems.append(f"{where}: must be a mapping of field -> matcher")
        return
    for name, matcher in value.items():
        if not isinstance(name, str):
            problems.append(f"{where}: field key {_show(name)} must be text")
        else:
            _check_matcher_at(matcher, f"{where}.{name}", problems)


def _check_screen(screen: Any, where: str, problems: list[str]) -> None:
    if not isinstance(screen, Mapping):
        problems.append(f"{where}: a screen is a mapping of field -> value naming @map")
        return
    for name, value in screen.items():
        if not isinstance(name, str):
            problems.append(f"{where}: field key {_show(name)} must be text")
        elif is_matcher(value):
            _check_matcher_at(value, f"{where}.{name}", problems)
        elif not isinstance(value, str):
            problems.append(f"{where}.{name}: {_show(value)} must be text or a matcher")


def expected_problems(expected: Any) -> list[str]:
    """Every way ``expected`` (the content of an ``expected.yaml``) breaks the
    contract the module docstring describes; empty when it conforms."""
    if not isinstance(expected, Mapping):
        return ["(document): must be a mapping of the asserted keys"]
    problems = [
        f"{where}: is a float; write numbers as quoted decimal text with their scale, as decoded"
        for where in _floats(expected, "")
    ]
    unknown = sorted(_key_text(key) for key in expected if key not in _scenarios.EXPECTED_KEYS)
    if unknown:
        problems.append(
            f"(document): unknown key(s) {', '.join(unknown)}; allowed: {', '.join(_scenarios.EXPECTED_KEYS)}"
        )
    if "return_code" in expected:
        code = expected["return_code"]
        if code is not None and (isinstance(code, bool) or not isinstance(code, int)):
            problems.append("return_code: must be an integer, or null for an abend")
    if "messages" in expected:
        _check_list_spec(expected["messages"], "messages", problems, _check_line, False)
    if "reject_reason_codes" in expected:
        _check_list_spec(expected["reject_reason_codes"], "reject_reason_codes", problems, _check_reason, False)
    for section in ("datasets", "file_state"):
        if section not in expected:
            continue
        value = expected[section]
        if not isinstance(value, Mapping):
            problems.append(f"{section}: must be a mapping of dataset -> expected content")
            continue
        for name, item in value.items():
            if not isinstance(name, str) or not name:
                problems.append(f"{section}: dataset name {_show(name)} must be text")
                continue
            _check_list_spec(item, f"{section}.{name}", problems, _check_record, True)
    if "screens" in expected:
        _check_items(expected["screens"], "screens", problems, _check_screen)
    if "matchers" in expected:
        _check_matcher_map(expected["matchers"], "matchers", problems)
    return problems


def manifest_problems(manifest: Any, has_raw: bool) -> list[str]:
    """Every way ``manifest`` breaks the manifest contract; ``has_raw`` says
    whether the baseline holds ``datasets/*.bin`` files."""
    if not isinstance(manifest, Mapping):
        return ["(document): must be a mapping"]
    problems = [f"{where}: is a float" for where in _floats(manifest, "")]
    allowed = set(MANIFEST_REQUIRED) | set(MANIFEST_OBSERVED) | set(MANIFEST_OPTIONAL)
    unknown = sorted(str(key) for key in manifest if key not in allowed)
    if unknown:
        problems.append(f"(document): unknown key(s) {', '.join(unknown)}; allowed: {', '.join(sorted(allowed))}")
    for key in MANIFEST_REQUIRED:
        if key not in manifest:
            problems.append(f"{key}: is required")
    if "baseline_version" in manifest:
        try:
            parse_version(manifest["baseline_version"])
        except BaselineError as exc:
            problems.append(f"baseline_version: {exc}")
    platform = manifest.get("capture_platform")
    if "capture_platform" in manifest and platform not in CAPTURE_PLATFORMS:
        problems.append(f"capture_platform: {platform!r} is not one of {', '.join(CAPTURE_PLATFORMS)}")
    commit = manifest.get("source_commit")
    if "source_commit" in manifest and (not isinstance(commit, str) or not commit.strip()):
        problems.append("source_commit: must be the commit text")
    if "capture_date" in manifest and not _iso_date(manifest["capture_date"]):
        problems.append(f"capture_date: {_show(manifest['capture_date'])} is not an ISO date YYYY-MM-DD")
    if "source_identity" in manifest and not isinstance(manifest["source_identity"], Mapping):
        problems.append("source_identity: must be a mapping")
    if "derived_from" in manifest and not isinstance(manifest["derived_from"], str):
        problems.append("derived_from: must be text such as tech-spec or source")
    if platform == DERIVED:
        present = [key for key in MANIFEST_OBSERVED if key in manifest]
        if present:
            problems.append(f"{', '.join(present)}: only observed baselines carry them")
        if has_raw:
            problems.append("datasets/: a derived baseline holds no raw bytes until it is observed")
    elif platform in CAPTURE_PLATFORMS:
        for key in MANIFEST_OBSERVED:
            if key not in manifest:
                problems.append(f"{key}: is required for a baseline observed on {platform}")
        execution_id = manifest.get("execution_id")
        if "execution_id" in manifest and (not isinstance(execution_id, str) or not execution_id.strip()):
            problems.append("execution_id: must be the execution id text")
        fingerprint = manifest.get("execution_fingerprint")
        if "execution_fingerprint" in manifest and not (
            isinstance(fingerprint, str) and _FINGERPRINT_RE.fullmatch(fingerprint)
        ):
            problems.append("execution_fingerprint: must be a lower-case sha256 hex digest")
    return problems


def _raw_set_problems(expected: Any, manifest: Any, names: Iterable[str]) -> list[str]:
    """For a baseline observed on a capture platform, every business dataset
    ``expected.datasets`` or ``expected.file_state`` names that has no
    ``.bin`` among ``names``, and every ``.bin`` of ``names`` no such
    dataset names, in name order. Reserved observations (``@...``) need none.
    A derived baseline holds no ``.bin`` at all, which ``manifest_problems``
    checks; a malformed ``expected`` or ``manifest`` is reported there too."""
    if not isinstance(expected, Mapping) or not isinstance(manifest, Mapping):
        return []
    platform = manifest.get("capture_platform")
    if platform == DERIVED or platform not in CAPTURE_PLATFORMS:
        return []
    wanted = {
        name
        for section in ("datasets", "file_state")
        if isinstance(expected.get(section), Mapping)
        for name in expected[section]
        if isinstance(name, str) and name and not name.startswith("@")
    }
    held = set(names)
    return [
        f"datasets/{name}{RAW_SUFFIX}: missing; a baseline observed on {platform} holds the raw bytes of every "
        f"business dataset its expected.datasets and expected.file_state name"
        if name in wanted
        else f"datasets/{name}{RAW_SUFFIX}: {name} is not a business dataset expected.datasets or "
        f"expected.file_state names"
        for name in sorted(wanted ^ held)
    ]


def _iso_date(value: Any) -> bool:
    if isinstance(value, _dt.datetime):
        return False
    if isinstance(value, _dt.date):
        return True
    if not isinstance(value, str) or len(value) != 10:
        return False
    try:
        _dt.date.fromisoformat(value)
    except ValueError:
        return False
    return True


def _check_id(baseline_id: Any) -> str:
    """A baseline id is a scenario id; the pattern also keeps every path built
    from it inside the baselines folder."""
    if not isinstance(baseline_id, str) or not _scenarios.ID_RE.fullmatch(baseline_id):
        raise BaselineError(f"{baseline_id!r} is not a scenario id <AREA>-<PROGRAM-OR-JOB>-<type>-<nn>")
    return baseline_id


@dataclass(frozen=True)
class Baseline:
    """One baseline: ``expected`` (the content of ``expected.yaml``),
    ``manifest``, ``datasets`` (logical name -> raw expected bytes) and the
    directory it was read from (``None`` for one built in memory, as
    ``capture.py`` does before writing). An observed baseline holds exactly
    one raw dataset per business dataset its ``expected.datasets`` and
    ``expected.file_state`` name, a derived one none. Construction validates
    the contract and raises ``BaselineError`` listing every problem."""

    id: str
    expected: dict
    manifest: dict
    datasets: dict = field(default_factory=dict)
    path: Path | None = None

    def __post_init__(self) -> None:
        _check_id(self.id)
        label = display_path(self.path) if self.path is not None else f"baseline {self.id}"
        if not isinstance(self.datasets, Mapping):
            raise BaselineError(f"{label}: datasets must map logical names to bytes")
        raw: dict[str, bytes] = {}
        problems: list[str] = []
        for name, data in self.datasets.items():
            if not isinstance(name, str) or not _LOGICAL_RE.fullmatch(name):
                problems.append(f"datasets/{name}{RAW_SUFFIX}: raw datasets are business datasets ^[A-Z][A-Z0-9-]*$")
            elif not isinstance(data, (bytes, bytearray)):
                problems.append(f"datasets/{name}{RAW_SUFFIX}: must hold bytes, not {type(data).__name__}")
            else:
                raw[name] = bytes(data)
        named = [name for name in self.datasets if isinstance(name, str) and _LOGICAL_RE.fullmatch(name)]
        object.__setattr__(self, "datasets", dict(sorted(raw.items())))
        problems.extend(f"{EXPECTED_FILE}: {problem}" for problem in expected_problems(self.expected))
        problems.extend(f"{MANIFEST_FILE}: {problem}" for problem in manifest_problems(self.manifest, bool(raw)))
        problems.extend(_raw_set_problems(self.expected, self.manifest, named))
        if problems:
            raise BaselineError(f"{label}: " + "; ".join(problems))

    @property
    def version(self) -> str:
        return self.manifest["baseline_version"]

    @property
    def capture_platform(self) -> str:
        return self.manifest["capture_platform"]

    @property
    def derived(self) -> bool:
        return self.capture_platform == DERIVED


# --------------------------------------------------------------------------
# Loading and writing
# --------------------------------------------------------------------------


def _refuse_links(paths: Iterable[Path], what: Path) -> None:
    """Baselines are read and written only through real directories: a
    symbolic link on the way is refused, never followed."""
    for path in paths:
        if path.is_symlink():
            raise BaselineError(f"{display_path(what)} is not used: {display_path(path)} is a symbolic link")


def _read_yaml(path: Path) -> Any:
    if path.is_symlink():
        raise BaselineError(f"{display_path(path)} is not read: it is a symbolic link")
    if not path.exists():
        raise BaselineError(f"{display_path(path)} is missing")
    if not stat.S_ISREG(path.stat().st_mode):
        raise BaselineError(f"{display_path(path)} is not a regular file")
    try:
        return load_yaml(path)
    except YAMLLoadError as exc:
        raise BaselineError(str(exc)) from None


# The names a file-by-file writer (``harness.write_text`` and the like) gives
# its temp files, ``.<file>.<pid>.<8 hex>.tmp``: ``_RAW_TEMP_RE`` for a
# ``<LOGICAL>.bin``, and ``_TEMP_RE`` for any of the three artifacts. ``load``
# ignores the raw ones in ``datasets/``; ``write`` accepts both in the
# directory it replaces and leaves them out of the new one.
_TEMP_TAIL = r"\.([0-9]+)\.[0-9a-f]{8}\.tmp"
_RAW_NAME = r"[A-Z][A-Z0-9-]*" + re.escape(RAW_SUFFIX)
_RAW_FILE_RE = re.compile(_RAW_NAME)
_RAW_TEMP_RE = re.compile(rf"\.{_RAW_NAME}{_TEMP_TAIL}")
_TEMP_RE = re.compile(rf"\.(?:{re.escape(EXPECTED_FILE)}|{re.escape(MANIFEST_FILE)}|{_RAW_NAME}){_TEMP_TAIL}")

# A writer's publication directories beside the baselines:
# ``.<id>.<pid>.<8 hex>.new`` stages the new baseline, and
# ``.<id>.<pid>.<8 hex>.old`` holds the one it replaces while a fallback swap
# runs. No scenario id starts with a dot, so neither can be taken for a
# baseline. Group 1 is the id, group 2 the kind.
_PUBLICATION_RE = re.compile(r"\.([^./]+)\.[0-9]+\.[0-9a-f]{8}\.(new|old)")

# flock is not supported there: a reader goes on unlocked, a writer is refused.
_NO_FLOCK = (errno.ENOLCK, errno.EOPNOTSUPP)
# What a reader can find instead of a baselines folder to lock (nothing, a
# symbolic link, a file); it reads unlocked, and its own checks report them.
_NOTHING_TO_LOCK = (errno.ENOENT, errno.ELOOP, errno.ENOTDIR)

_RENAME_EXCHANGE = 2  # the renameat2 flag of <linux/fs.h> that swaps two names


def _entries(folder: Path) -> list[Path]:
    """The entries of ``folder`` in name order; none when it does not
    exist."""
    try:
        return sorted(folder.iterdir())
    except FileNotFoundError:
        return []


def _publication(name: str) -> tuple[str, str] | None:
    """``(id, kind)`` when ``name`` is a writer's staging (``new``) or
    pre-image (``old``) directory name, else ``None``."""
    match = _PUBLICATION_RE.fullmatch(name)
    if match is None or not _scenarios.ID_RE.fullmatch(match.group(1)):
        return None
    return match.group(1), match.group(2)


@contextlib.contextmanager
def _locked(root: Path, exclusive: bool, error: type[HarnessError] = BaselineError) -> Iterator[int | None]:
    """Hold ``flock`` on the baselines folder ``root`` itself, so no lock file
    is created: exclusive for a writer, which publishes only while it holds
    it, and shared for a reader, so that it never reads across a publication.
    Yields the folder's descriptor, which the writer names its staging and
    pre-image directories relative to, or ``None`` for a reader that finds
    no folder there (nothing, a symbolic link or a file). A reader on a
    filesystem without ``flock`` goes on unlocked; a writer that cannot lock
    raises ``error`` before anything is written. A process holding the
    exclusive lock must not ask for the shared one: ``flock`` would wait
    forever."""
    descriptor: int | None = None
    try:
        descriptor = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    except OSError as exc:
        if exclusive or exc.errno not in _NOTHING_TO_LOCK:
            raise error(f"cannot lock {display_path(root)}: {exc.strerror or exc}") from None
    if descriptor is None:
        yield None
        return
    try:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH)
        except OSError as exc:
            if exclusive or exc.errno not in _NO_FLOCK:
                raise error(f"cannot lock {display_path(root)}: {exc.strerror or exc}") from None
        yield descriptor
    finally:
        os.close(descriptor)  # closing the descriptor releases the lock


def _remove_entry(descriptor: int, name: str) -> None:
    """Remove the entry ``name`` of the locked baselines folder: a directory
    with everything in it, anything else (a symbolic link included) as the
    entry itself. A link is never followed."""
    try:
        mode = os.stat(name, dir_fd=descriptor, follow_symlinks=False).st_mode
    except FileNotFoundError:
        return
    if stat.S_ISDIR(mode):
        shutil.rmtree(name, dir_fd=descriptor)
    else:
        os.unlink(name, dir_fd=descriptor)


def _recover(descriptor: int) -> None:
    """Undo what writers that died left in the locked baselines folder. Only
    a writer holding the exclusive lock has staging or pre-image
    directories, so under that lock every one was left by a writer that
    died: a pre-image whose ``<id>`` is missing (a fallback swap killed
    between its two renames) is renamed back, so ``<id>`` is again the last
    complete baseline, and every other one (an unfinished publication, or
    the replaced baseline a finished one had yet to remove) is removed."""
    names = set(os.listdir(descriptor))
    for name in sorted(names):
        owner = _publication(name)
        if owner is None:
            continue
        baseline_id, kind = owner
        if kind == "old" and baseline_id not in names:
            if stat.S_ISDIR(os.stat(name, dir_fd=descriptor, follow_symlinks=False).st_mode):
                os.rename(name, baseline_id, src_dir_fd=descriptor, dst_dir_fd=descriptor)
                names.add(baseline_id)
                continue
        _remove_entry(descriptor, name)


def _interrupted(descriptor: int, baseline_id: str) -> bool:
    """Whether a writer died between the two renames of a fallback swap of
    ``baseline_id``: ``<id>`` is missing beside its pre-image."""
    try:
        os.stat(baseline_id, dir_fd=descriptor, follow_symlinks=False)
        return False
    except FileNotFoundError:
        pass
    return any(_publication(name) == (baseline_id, "old") for name in os.listdir(descriptor))


def _read_datasets(folder: Path) -> dict[str, bytes]:
    if folder.is_symlink():
        raise BaselineError(f"{display_path(folder)} is not read: it is a symbolic link")
    if not folder.exists():
        return {}
    if not folder.is_dir():
        raise BaselineError(f"{display_path(folder)} is not a folder of <LOGICAL>{RAW_SUFFIX} datasets")
    datasets: dict[str, bytes] = {}
    for entry in sorted(folder.iterdir()):
        shown = display_path(entry)
        if entry.is_symlink():
            raise BaselineError(f"{shown} is not read: it is a symbolic link")
        if _RAW_TEMP_RE.fullmatch(entry.name):
            continue  # an unfinished or interrupted write's temp file, not a dataset
        if not (entry.is_file() and entry.suffix == RAW_SUFFIX and _LOGICAL_RE.fullmatch(entry.stem)):
            raise BaselineError(f"{shown} is not a <LOGICAL>{RAW_SUFFIX} dataset")
        datasets[entry.stem] = entry.read_bytes()
    return datasets


def load(
    baseline_id: str, missing_ok: bool = False, baselines_dir: str | Path = BASELINES_DIR
) -> Baseline | None:
    """The baseline of ``baseline_id`` from ``<baselines_dir>/<id>/``.

    A missing directory returns ``None`` when ``missing_ok`` and raises
    ``BaselineError`` otherwise. A directory that exists but breaks the
    contract (a missing or unreadable ``expected.yaml`` or ``manifest.yaml``,
    a float in ``expected.yaml``, a file in ``datasets/`` that is not
    ``<LOGICAL>.bin``, a symbolic link) always raises ``BaselineError``.

    The read holds the shared lock on ``<baselines_dir>`` (``_locked``), so
    it never overlaps a ``write``'s publication and sees each baseline
    whole. When ``<id>`` is missing beside a pre-image
    ``.<id>.<pid>.<8 hex>.old`` (a writer died between the two renames of
    its fallback swap), it takes the exclusive lock instead, puts the
    pre-image back (``_recover``) and reads that last complete baseline.
    Staging and pre-image directories are never read, and a temp file
    ``.<LOGICAL>.bin.<pid>.<8 hex>.tmp`` in ``datasets/`` is ignored, not
    refused."""
    root = Path(baselines_dir)
    directory = root / _check_id(baseline_id)
    _refuse_links((root, directory), directory)
    with _locked(root, exclusive=False) as descriptor:
        if descriptor is None or not _interrupted(descriptor, baseline_id):
            return _read(root, baseline_id, missing_ok)
    with _locked(root, exclusive=True) as descriptor:
        _recover(descriptor)
        return _read(root, baseline_id, missing_ok)


def _read(root: Path, baseline_id: str, missing_ok: bool) -> Baseline | None:
    """``load`` without the lock, for a caller that holds it: ``write``
    reads back under its exclusive lock, on which a shared one would wait
    forever."""
    directory = root / baseline_id
    _refuse_links((root, directory), directory)
    if not directory.exists():
        if missing_ok:
            return None
        raise BaselineError(f"no baseline for {baseline_id}: {display_path(directory)} does not exist")
    if not directory.is_dir():
        raise BaselineError(f"{display_path(directory)} is not a baseline directory")
    expected = _read_yaml(directory / EXPECTED_FILE)
    manifest = _read_yaml(directory / MANIFEST_FILE)
    datasets = _read_datasets(directory / DATASETS_DIR)
    return Baseline(baseline_id, expected, manifest, datasets, directory)


@dataclass(frozen=True)
class _Held:
    """A file of the baseline directory a ``write`` replaces."""

    path: Path
    status: os.stat_result
    data: bytes


@dataclass(frozen=True)
class _Current:
    """The baseline directory a ``write`` replaces: the permission bits of
    the directory (``None`` when there is none yet) and of its ``datasets/``
    (``None`` without one), its artifacts by their path inside it, and
    whether it holds temp files the new directory leaves out."""

    mode: int | None = None
    raw_mode: int | None = None
    files: dict = field(default_factory=dict)
    temps: bool = False

    def holds(self, files: Mapping[str, bytes]) -> bool:
        """Whether the directory holds exactly ``files``, byte for byte, and
        nothing else."""
        has_raw = any(name.startswith(DATASETS_DIR + "/") for name in files)
        return (
            self.mode is not None
            and not self.temps
            and (self.raw_mode is not None) == has_raw
            and set(self.files) == set(files)
            and all(self.files[name].data == data for name, data in files.items())
        )


def _foreign(directory: Path, entry: Path, mode: int) -> BaselineError:
    """The refusal to replace ``directory`` because of ``entry``."""
    what = "a symbolic link" if stat.S_ISLNK(mode) else "not part of a baseline"
    return BaselineError(
        f"{display_path(directory)} is not replaced: {display_path(entry)} is {what}; a baseline directory holds "
        f"only {EXPECTED_FILE}, {MANIFEST_FILE} and {DATASETS_DIR}/<LOGICAL>{RAW_SUFFIX}, so move it away first"
    )


def _current(directory: Path) -> _Current:
    """What the baseline ``directory`` holds, read under the exclusive lock.
    Anything but its artifacts and writers' temp files is refused, so a
    ``write`` never drops a file it does not own."""
    try:
        status = directory.lstat()
    except FileNotFoundError:
        return _Current()
    if not stat.S_ISDIR(status.st_mode):
        what = "a symbolic link" if stat.S_ISLNK(status.st_mode) else "not a baseline directory"
        raise BaselineError(f"{display_path(directory)} is not replaced: it is {what}")
    files: dict[str, _Held] = {}
    raw_mode: int | None = None
    temps = False
    for entry in _entries(directory):
        held = entry.lstat()
        if entry.name in (EXPECTED_FILE, MANIFEST_FILE) and stat.S_ISREG(held.st_mode):
            files[entry.name] = _Held(entry, held, entry.read_bytes())
        elif entry.name == DATASETS_DIR and stat.S_ISDIR(held.st_mode):
            raw_mode = stat.S_IMODE(held.st_mode)
            for raw in _entries(entry):
                raw_held = raw.lstat()
                if not stat.S_ISREG(raw_held.st_mode):
                    raise _foreign(directory, raw, raw_held.st_mode)
                if _RAW_FILE_RE.fullmatch(raw.name):
                    files[f"{DATASETS_DIR}/{raw.name}"] = _Held(raw, raw_held, raw.read_bytes())
                elif _RAW_TEMP_RE.fullmatch(raw.name):
                    temps = True
                else:
                    raise _foreign(directory, raw, raw_held.st_mode)
        elif stat.S_ISREG(held.st_mode) and _TEMP_RE.fullmatch(entry.name):
            temps = True
        else:
            raise _foreign(directory, entry, held.st_mode)
    return _Current(stat.S_IMODE(status.st_mode), raw_mode, files, temps)


def _stage_file(target: Path, data: bytes, held: _Held | None) -> None:
    """Put ``data`` at ``target`` in a staging directory. A file the replaced
    directory ``held`` with exactly these bytes is hard-linked, so it keeps
    its inode and modification time; where no link can be made, a copy gets
    the old modification time back. A changed file keeps the old file's
    permission bits, a new one gets the mode the umask allows."""
    if held is not None and held.data == data:
        try:
            os.link(held.path, target, follow_symlinks=False)
            return
        except OSError:
            pass  # no hard link here (a filesystem without them, a link limit): copy the file
    descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC, 0o666)
    with os.fdopen(descriptor, "wb") as handle:
        handle.write(data)
    if held is not None:
        os.chmod(target, stat.S_IMODE(held.status.st_mode))
        if held.data == data:
            os.utime(target, ns=(held.status.st_atime_ns, held.status.st_mtime_ns))


def _stage(descriptor: int, root: Path, baseline_id: str, files: Mapping[str, bytes], current: _Current) -> str:
    """Build the complete new baseline directory of ``baseline_id`` in a
    staging directory ``.<id>.<pid>.<8 hex>.new`` of the locked baselines
    folder ``root`` and return its name. ``files`` maps each file's path
    inside the directory to its bytes; the directories get the replaced
    ones' permission bits once they are filled."""
    while True:
        name = f".{baseline_id}.{os.getpid()}.{os.urandom(4).hex()}.new"
        try:
            os.mkdir(name, dir_fd=descriptor)
            break
        except FileExistsError:
            continue
    stage = root / name
    raw_folder = stage / DATASETS_DIR
    for relative, data in files.items():
        target = stage / relative
        if target.parent == raw_folder and not raw_folder.is_dir():
            raw_folder.mkdir()
        _stage_file(target, data, current.files.get(relative))
    if current.raw_mode is not None and raw_folder.is_dir():
        os.chmod(raw_folder, current.raw_mode)
    if current.mode is not None:
        os.chmod(stage, current.mode)
    return name


@functools.lru_cache(maxsize=None)
def _renameat2() -> Any:
    """The C library's ``renameat2``, or ``None`` where it has none."""
    try:
        function = ctypes.CDLL(None, use_errno=True).renameat2
    except (AttributeError, OSError):
        return None
    function.argtypes = (ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint)
    function.restype = ctypes.c_int
    return function


def _exchange(descriptor: int, first: str, second: str) -> bool:
    """Swap the entries ``first`` and ``second`` of the locked baselines
    folder in one step (Linux ``renameat2`` with ``RENAME_EXCHANGE``), so
    each name always names a complete directory. ``False``, with nothing
    changed, when the C library, the kernel or the filesystem cannot; any
    other failure raises ``OSError``."""
    function = _renameat2()
    if function is None:
        return False
    if function(descriptor, os.fsencode(first), descriptor, os.fsencode(second), _RENAME_EXCHANGE) == 0:
        return True
    code = ctypes.get_errno()
    if code in (errno.ENOSYS, errno.EINVAL):
        return False
    raise OSError(code, os.strerror(code), second)


def _publish(descriptor: int, baseline_id: str, stage: str, replacing: bool) -> None:
    """Put the staging directory ``stage`` in place as ``<id>`` in one step:
    exchanged with the current ``<id>``, which the stage name then holds and
    which is removed, or, for a first baseline, renamed into place. Where no
    exchange is possible, ``<id>`` is renamed to a pre-image
    ``.<id>.<pid>.<8 hex>.old``, the stage to ``<id>``, and the pre-image is
    removed; a writer killed between the two renames leaves the pre-image,
    which ``_recover`` puts back."""
    if not replacing:
        os.rename(stage, baseline_id, src_dir_fd=descriptor, dst_dir_fd=descriptor)
        return
    if _exchange(descriptor, stage, baseline_id):
        _remove_entry(descriptor, stage)
        return
    pre_image = f".{baseline_id}.{os.getpid()}.{os.urandom(4).hex()}.old"
    os.rename(baseline_id, pre_image, src_dir_fd=descriptor, dst_dir_fd=descriptor)
    os.rename(stage, baseline_id, src_dir_fd=descriptor, dst_dir_fd=descriptor)
    _remove_entry(descriptor, pre_image)


def write(
    baseline_id: str,
    expected: Mapping[str, Any],
    manifest: Mapping[str, Any],
    datasets: Mapping[str, bytes] | None = None,
    baselines_dir: str | Path = BASELINES_DIR,
) -> Baseline:
    """Write the baseline of ``baseline_id`` as ``<baselines_dir>/<id>/``
    and return it as read back.

    ``expected.yaml`` and ``manifest.yaml`` are canonical ``dump_yaml``
    text; each ``datasets`` entry becomes ``datasets/<LOGICAL>.bin`` holding
    exactly its bytes, and the directory holds nothing else (no ``datasets``
    folder without raw datasets). The content is validated first, so nothing
    is written for a baseline ``load`` would refuse. Used by ``capture.py``
    and ``report.py --import-unikix``; the bump check judges the change.

    The directory is published whole. Holding the exclusive lock on
    ``<baselines_dir>`` (``_locked``), the writer first undoes what writers
    that died left there (``_recover``), then builds the complete new
    directory in a staging directory ``.<id>.<pid>.<8 hex>.new`` beside it
    and swaps it in (``_publish``), so ``<id>`` names the last complete
    baseline until the swap and the new one after it, and a first baseline
    appears only once complete. An interrupted or failed write leaves the
    last complete baseline, or none, and a retry needs no cleanup;
    concurrent writers take turns. Unchanged files are hard links to the
    replaced ones, so they keep their inode and modification time; a
    changed file keeps the replaced file's permission bits and each
    directory the replaced directory's; a directory that already holds
    exactly these files is left as it is. The replaced directory may hold
    only its artifacts and writers' temp files
    (``.<file>.<pid>.<8 hex>.tmp``, left out of the new one). Anything else
    in it, a symbolic link, an ``<id>`` that is not a directory and a lock
    that cannot be taken raise ``BaselineError`` before anything is
    written."""
    root = Path(baselines_dir)
    directory = root / _check_id(baseline_id)
    raw = dict(datasets or {})
    candidate = Baseline(baseline_id, dict(expected), dict(manifest), raw, directory)
    _refuse_links((root, directory, directory / EXPECTED_FILE, directory / MANIFEST_FILE), directory)
    raw_folder = directory / DATASETS_DIR
    _refuse_links((raw_folder,), raw_folder)
    for name in sorted(raw):
        target = raw_folder / f"{name}{RAW_SUFFIX}"
        _refuse_links((target,), target)
    files = _artifact_bytes(candidate)
    root.mkdir(parents=True, exist_ok=True)
    with _locked(root, exclusive=True) as descriptor:
        _recover(descriptor)
        current = _current(directory)
        if not current.holds(files):
            try:
                stage = _stage(descriptor, root, baseline_id, files, current)
                _publish(descriptor, baseline_id, stage, current.mode is not None)
            except BaseException:
                with contextlib.suppress(OSError):
                    _recover(descriptor)  # removes the stage, or puts the pre-image back
                raise
        written = _read(root, baseline_id, missing_ok=True)
    if written is None:
        raise BaselineError(f"{display_path(directory)} vanished while it was written")
    return written


# --------------------------------------------------------------------------
# The scenario deadline
# --------------------------------------------------------------------------

# What a _Deadline names as interrupted, by the step that reads it.
_PROJECTING = "projecting the decoded outcome"
_RENDERING = "rendering the comparison diff"


class _Deadline:
    """The scenario deadline ``decode_result``, ``projection`` and
    ``compare`` run under: a ``time.monotonic()`` value, or ``None`` for
    none. ``check`` reads the clock; ``tick``, called once per iteration of
    a loop whose cost grows with the data, reads it every
    ``_DEADLINE_EVERY`` calls, so a run inside its budget pays next to
    nothing. Past the deadline either raises ``adapters.base.ScenarioTimeout``
    naming what it interrupted; it is no ``HarnessError``, so no comparison
    step turns it into a finding."""

    __slots__ = ("at", "_calls")

    def __init__(self, at: float | None) -> None:
        if at is not None and (isinstance(at, bool) or not isinstance(at, (int, float))):
            raise TypeError(f"a deadline is a time.monotonic() value or None, not {type(at).__name__}")
        self.at = at
        self._calls = 0

    def check(self, what: str) -> None:
        if self.at is not None and time.monotonic() >= self.at:
            raise _base.ScenarioTimeout(f"the scenario deadline passed while {what}")

    def tick(self, what: str) -> None:
        if self.at is None:
            return
        self._calls += 1
        if self._calls >= _DEADLINE_EVERY:
            self._calls = 0
            self.check(what)


_UNBOUNDED = _Deadline(None)


# --------------------------------------------------------------------------
# The decoded projection
# --------------------------------------------------------------------------

_ABSENT = object()  # a field a record does not hold


@functools.lru_cache(maxsize=None)
def _key_paths(layout: str) -> dict[str, str]:
    """``decode_record`` key (without subscripts) -> the field's path, so an
    expected record may name a field by either."""
    entries = _layouts.record(layout)["fields"]
    return {_codec.field_key(entry, layout): entry["path"] for entry in entries}


@functools.lru_cache(maxsize=None)
def _is_text_layout(layout: str) -> bool:
    return _layouts.record(layout).get("kind") == "text"


@dataclass
class _View:
    """One asserted dataset or observation of a result: its records in
    comparison order (a field map per record, or a line of text), with the
    raw bytes and delivered position of each where it has them."""

    name: str
    records: list = field(default_factory=list)
    raw: list[bytes] | None = None
    order: list[int] = field(default_factory=list)
    record_length: int | None = None
    layout: str | None = None
    own_fields: tuple = ()
    problem: str | None = None  # why the records are not there
    problem_kind: str = MISSING  # MISSING (not produced) or ERROR (not decodable)
    _extended: dict[int, dict] = field(default_factory=dict)

    def value(self) -> Any:
        """The decoded projection of the view."""
        if self.problem is not None:
            return {self.problem_kind: True if self.problem_kind == MISSING else self.problem}
        return self.records

    def field_value(self, index: int, name: str) -> Any:
        """Field ``name`` of record ``index``: its decoded value, else the
        value of a group, a field path, a dataset's own field or ``@raw``
        read from the raw record, else ``_ABSENT``."""
        record = self.records[index]
        if isinstance(record, dict) and name in record:
            return record[name]
        if self.raw is None:
            return _ABSENT
        if index not in self._extended:
            self._extended[index] = self._extend(self.raw[index])
        return self._extended[index].get(name, _ABSENT)

    def _extend(self, raw: bytes) -> dict:
        values: dict[str, Any] = {}
        if self.layout is not None:
            paths = _key_paths(self.layout)
            decoded = _codec.decode_record(raw, self.layout, leaves_only=False)
            for key, value in decoded.items():
                plain = _codec.to_plain(value)
                values[key] = plain
                base, bracket, subscripts = key.partition("(")
                path = paths.get(base)
                if path is not None:
                    values.setdefault(path + bracket + subscripts, plain)
        for own in self.own_fields:
            try:
                value = _codec.decode(raw[own.offset : own.offset + own.length], own.descriptor())
            except _codec.CodecError:
                continue
            values.setdefault(own.name, _codec.to_plain(value))
        values[RAW_KEY] = raw.decode(_codec.ENCODINGS["ascii"])
        return values


def _aix_spec(path: str) -> _fixtures.DatasetSpec:
    """The dataset whose records an ``@AIX:<path>`` observation holds: the
    dataset named ``path`` if one is defined, else the base dataset of the
    alternate-index path."""
    definitions = _fixtures.specs()
    if path in definitions:
        return definitions[path]
    for definition in definitions.values():
        if any(key.path == path for key in definition.alternate_keys):
            return definition
    raise _fixtures.FixtureError(
        f"{_base.AIX_PREFIX}{path} names no alternate-index path of tests/fixtures/datasets.yaml"
    )


def _dataset_view(name: str, data: bytes | None, deadline: _Deadline = _UNBOUNDED) -> _View:
    """Decode one dataset or observation of a result (ASCII bytes, the
    encoding of both adapters' files), reading ``deadline`` as each record is
    ordered and decoded."""
    view = _View(name)
    if data is None:
        view.problem = f"{name} was not produced"
        return view
    # A region stage observation is @REGION-FILES at one point of the run,
    # so it decodes as @REGION-FILES's lines do.
    if name in _LINE_OBSERVATIONS or _base.parse_region_stage(name) is not None:
        lines = data.decode(_codec.ENCODINGS["ascii"]).split("\n")
        if lines and lines[-1] == "":
            lines.pop()
        view.records = lines
        view.order = list(range(len(lines)))
        return view
    try:
        sort = False
        if name == _base.TDQ_JOBS:
            view.record_length = _base.TDQ_RECORD_LENGTH
            view.layout = f"TEXT/{_base.TDQ_RECORD_LENGTH}"
            definition = None
        else:
            if name.startswith(_base.AIX_PREFIX):
                definition = _aix_spec(name[len(_base.AIX_PREFIX) :])
            else:
                # A stage observation holds its logical dataset at the end of a
                # job, so it decodes, and sorts, exactly as that dataset does.
                stage = _base.parse_after_job(name)
                definition = _fixtures.spec(stage[1] if stage is not None else name)
                sort = definition.keyed
            view.record_length = definition.record_length
            view.layout = definition.layout
            view.own_fields = tuple(definition.fields)
        raw = _codec.split_records(data, view.record_length, dataset=name)
        order = list(range(len(raw)))
        what = f"decoding {name}"
        if sort and definition is not None:
            keyed = definition

            def key(index: int) -> Any:
                deadline.tick(what)
                return keyed.key_of(raw[index])

            # Indexed datasets compare in primary-key order whatever order an
            # unloader delivered them in; equal keys keep their order.
            order.sort(key=key)
        view.order = order
        view.raw = [raw[index] for index in order]
        text = view.layout is not None and _is_text_layout(view.layout)
        records = []
        for position, index in enumerate(order):
            deadline.tick(what)
            if view.layout is None:
                records.append(view.raw[position].decode(_codec.ENCODINGS["ascii"]))
                continue
            decoded = _codec.decode_record(view.raw[position], view.layout, dataset=name, record=index + 1)
            records.append(next(iter(decoded.values())) if text else _codec.to_plain(decoded))
        view.records = records
    except HarnessError as exc:
        view.records, view.raw, view.order = [], None, []
        view.problem, view.problem_kind = f"{name} cannot be decoded: {exc}", ERROR
    return view


def _reject_codes(result: _base.Result, deadline: _Deadline = _UNBOUNDED) -> list[str] | dict | None:
    """The DALYREJS reason texts in written order, ``None`` when the result
    holds no DALYREJS, or an ``@error`` marker when its records cannot be
    split; ``deadline`` is read as each record is."""
    data = result.datasets.get(REJECTS_DATASET)
    if data is None:
        return None
    try:
        record_length = _fixtures.spec(REJECTS_DATASET).record_length
        reason = _layouts.field_map(REJECT_LAYOUT)[REJECT_REASON_FIELD]
        records = _codec.split_records(data, record_length, dataset=REJECTS_DATASET)
    except (HarnessError, KeyError) as exc:
        return {ERROR: f"{REJECTS_DATASET} reason codes cannot be read: {exc}"}
    start, end = reason["offset"], reason["offset"] + reason["length"]
    what = f"reading the {REJECTS_DATASET} reason codes"
    codes = []
    for record in records:
        deadline.tick(what)
        codes.append(record[start:end].decode(_codec.ENCODINGS["ascii"]))
    return codes


@dataclass
class _Decoded:
    decoded: dict
    views: dict  # (section, name) -> _View


def _decode(scenario: Any, result: _base.Result, deadline: _Deadline = _UNBOUNDED) -> _Decoded:
    expected = scenario.expected if isinstance(scenario.expected, Mapping) else {}
    decoded: dict[str, Any] = {"return_code": result.return_code}
    views: dict[tuple[str, str], _View] = {}
    if "messages" in expected:
        decoded["messages"] = list(result.messages)
    if "reject_reason_codes" in expected:
        decoded["reject_reason_codes"] = _reject_codes(result, deadline)
    for section in ("datasets", "file_state"):
        names = expected.get(section)
        if not isinstance(names, Mapping):
            continue
        decoded[section] = {}
        for name in names:
            view = _dataset_view(str(name), result.datasets.get(str(name)), deadline)
            views[(section, str(name))] = view
            decoded[section][str(name)] = view.value()
    if "screens" in expected:
        screens: list[dict] = []
        for screen in result.screens:
            deadline.tick("decoding the screens")
            screens.append(dict(screen))
        wanted = expected["screens"]
        steps = len(wanted) if isinstance(wanted, list) else 0
        screens.extend({MISSING: True} for _ in range(steps - len(screens)))
        decoded["screens"] = screens
    return _Decoded(decoded, views)


def decode_result(scenario: Any, result: _base.Result, *, deadline: float | None = None) -> dict:
    """The decoded projection of ``result`` for what ``scenario`` asserts.

    ``return_code`` always; ``messages`` and ``screens`` as delivered (no
    trimming; a step without a screen is ``{"@missing": true}``);
    ``reject_reason_codes`` the DALYREJS reason texts (``None`` without
    DALYREJS); and each ``datasets``/``file_state`` key the scenario names
    decoded through ``tests/inventory/layouts.yaml``: field maps of plain
    values (``harness.codec.to_plain``) or lines for ``TEXT/<n>`` layouts,
    indexed datasets in primary-key order, sequential ones and ``@AIX:<path>``
    as delivered, ``@AFTER:<JOB>:<LOGICAL>`` through the layout and in the
    order of its ``<LOGICAL>``, ``@CATALOG``, ``@REGION-FILES`` and its
    region stage observations (``@BEFORE:<JOB>:@REGION-FILES``,
    ``@AFTER:<JOB>:@REGION-FILES``) as their lines. A dataset the result
    lacks is ``{"@missing": true}``; one that
    cannot be decoded is ``{"@error": <reason>}``. The result is plain
    YAML-safe data; raw bytes are never changed.

    ``deadline`` is the scenario deadline as a ``time.monotonic()`` value
    (``None``, the default, sets none): it is read on entry and as records
    are decoded, and once it has passed ``adapters.base.ScenarioTimeout``
    names what was being decoded."""
    clock = _Deadline(deadline)
    clock.check("decoding the result")
    return _decode(scenario, result, clock).decoded


def asserted_records(
    scenario: Any, result: _base.Result, names: Mapping[tuple[str, str], Sequence[str] | None]
) -> dict[tuple[str, str], Any]:
    """The records of ``result`` as ``compare`` reads an expected record's
    fields: ``(section, name)`` of each ``datasets``/``file_state`` entry the
    scenario's ``expected`` names -> one value per record, in
    ``decode_result`` order.

    Where ``names`` gives the entry field names, each record is ``{field:
    value}`` holding every one of them the record denotes as
    ``_View.field_value`` reads it: its decoded value, else, read from the raw
    record, a group, a field path with its COBOL subscripts, one of the
    dataset's own ``fields`` or ``@raw``. A name the record does not denote
    is left out, and a line no raw record stands behind (``@CATALOG``,
    ``@REGION-FILES``) stays the line. Every other record is as
    ``decode_result`` gives it, and an entry whose records ``compare`` cannot
    read (not produced, not decodable) is its ``decode_result`` marker. The
    values are plain YAML-safe data; raw bytes are never changed. A raw
    record a named group or own field cannot be decoded from raises
    ``HarnessError``, as it does in ``compare``."""
    bundle = _decode(scenario, result)
    out: dict[tuple[str, str], Any] = {}
    for key, view in bundle.views.items():
        fields = list(names.get(key) or ())
        if view.problem is not None or not fields:
            out[key] = view.value()
            continue
        records: list[Any] = []
        for index, record in enumerate(view.records):
            if isinstance(record, str) and view.raw is None:
                records.append(record)
                continue
            read: dict[str, Any] = {}
            for name in fields:
                value = view.field_value(index, name)
                if value is not _ABSENT:
                    read[name] = value
            records.append(read)
        out[key] = records
    return out


def field_identities(dataset: str, names: Iterable[str]) -> dict[str, str]:
    """Each of ``names`` -> the field it denotes in a record of the
    ``datasets``/``file_state`` entry ``dataset``, as ``compare`` reads it,
    so the names of one field map to one value: a field path, with its COBOL
    subscripts, maps to the ``decode_record`` key of that field with the same
    subscripts (``ACCOUNT-RECORD.ACCT-CURR-BAL`` and ``ACCT-CURR-BAL`` both to
    ``ACCT-CURR-BAL``, a group's path to its key); every other name, a
    dataset's own field and ``@raw`` included, maps to itself. An entry
    decoded without a layout (the line observations), or one
    ``tests/fixtures/datasets.yaml`` does not define, maps every name to
    itself."""
    # The layout compare decodes the entry by: _dataset_view's, read from a
    # result holding none of its records.
    layout = _dataset_view(dataset, b"").layout
    paths = _path_keys(layout) if layout is not None else {}
    identities: dict[str, str] = {}
    for name in names:
        base, bracket, subscripts = name.partition("(")
        key = paths.get(base)
        identities[name] = name if key is None else key + bracket + subscripts
    return identities


# --------------------------------------------------------------------------
# Comparison
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class _Context:
    """How matchers are judged in one comparison."""

    exact: bool
    clock: str
    run_date: _dt.date | None
    global_matchers: Mapping[str, Any]  # field name -> matcher mapping

    def check(self, matcher: Any, actual: Any) -> str | None:
        return check_matcher(matcher, actual, self.clock, self.exact, self.run_date)


def _plain_problem(expected: Any, actual: Any) -> str | None:
    """Why ``actual`` is not the literal ``expected``: text exactly (trailing
    spaces included); an integer as the same integer (``return_code``) or as
    exactly its own text at scale 0 (``194`` is ``'194'``, never ``'194.00'``,
    ``'0194'``, ``'+194'`` or ``'-0'`` for ``0``); anything else equal and of
    the same type."""
    if isinstance(expected, bool) or expected is None:
        same = actual is expected
    elif isinstance(expected, int):
        if isinstance(actual, bool):
            same = False
        elif isinstance(actual, int):
            same = actual == expected
        elif isinstance(actual, str):
            try:
                text = str(expected)
            except ValueError:
                # Too long to write in decimal: only the same number's integer
                # text equals it, which Decimal compares without that limit.
                same = bool(_INT_TEXT_RE.fullmatch(actual)) and Decimal(actual) == expected
                return None if same else f"expected {_show(expected)}, got {_show(actual)}"
            same = actual == text
            if not same and _DECIMAL_TEXT_RE.fullmatch(actual) and Decimal(actual) == expected:
                return (
                    f"expected {_show(expected)}, got {_show(actual)}: an integer stands only for the text "
                    f"{_show(text)}; write the value as decoded, with its scale"
                )
        else:
            same = False
    else:
        same = type(actual) is type(expected) and actual == expected
    return None if same else f"expected {_show(expected)}, got {_show(actual)}"


def _value_problem(expected: Any, actual: Any, ctx: _Context) -> str | None:
    if actual is _ABSENT:
        return "the field does not exist in the result"
    if is_matcher(expected):
        return ctx.check(expected, actual)
    return _plain_problem(expected, actual)


def _display(value: Any) -> Any:
    """``value`` as diffs show it: matchers become their description."""
    if is_matcher(value):
        try:
            return Matcher.of(value).describe()
        except BaselineError:
            return _show(value)
    if isinstance(value, Mapping):
        return {key: _display(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_display(item) for item in value]
    return value


def _shown(spec: Any, got: Any, problem: str | None) -> Any:
    """The actual side of a diff: what the expectation shows when it holds,
    so only differences stand out, else the observed value."""
    if problem is None:
        return _display(spec)
    return "<absent>" if got is _ABSENT else got


def _record_check(
    expected: Any, view: _View, index: int, ctx: _Context, field_matchers: Mapping[str, Any]
) -> tuple[str | None, Any]:
    """Compare record ``index`` of ``view`` with an expected record over the
    fields it names; returns the problem (or ``None``) and the record as the
    diff shows it. A field-level matcher (the entry's ``matchers`` or the
    top-level ``matchers``) replaces the literal of a field it names."""
    if isinstance(expected, Mapping) and not is_matcher(expected):
        problems: list[str] = []
        shown: dict[str, Any] = {}
        for name, literal in expected.items():
            got = view.field_value(index, name)
            matcher = field_matchers.get(name) or ctx.global_matchers.get(name)
            spec = literal if is_matcher(literal) or matcher is None else matcher
            problem = _value_problem(spec, got, ctx)
            if problem is not None:
                problems.append(f"{name}: {problem}")
            shown[name] = _shown(spec, got, problem)
        return ("; ".join(problems) or None), shown
    record = view.records[index]
    got = record if isinstance(record, str) else view.field_value(index, RAW_KEY)
    problem = _value_problem(expected, got, ctx)
    return problem, _shown(expected, got, problem)


def _union_fields(records: Sequence[Any]) -> list[str] | None:
    names: dict[str, None] = {}
    for record in records:
        if isinstance(record, Mapping) and not is_matcher(record):
            names.update(dict.fromkeys(record))
    return list(names) if names else None


def _restricted(view: _View, index: int, names: list[str] | None) -> Any:
    record = view.records[index]
    if names is None or not isinstance(record, dict):
        return record
    shown = {}
    for name in names:
        got = view.field_value(index, name)
        shown[name] = "<absent>" if got is _ABSENT else got
    return shown


def _normalized(spec: Any, exact: bool) -> tuple[dict, bool]:
    """A list value as ``{records|contains, count, matchers}`` and whether it
    was a plain list (``exact``: records; otherwise contains)."""
    if isinstance(spec, list):
        return ({"records": spec} if exact else {"contains": spec}), True
    if isinstance(spec, Mapping):
        return dict(spec), False
    raise BaselineError(f"{_show(spec)} is not a list or a mapping of records, contains, count and matchers")


def _list_check(
    spec: Any, view: _View, ctx: _Context, exact: bool, label: str, deadline: _Deadline = _UNBOUNDED
) -> tuple[list[str], Any, Any]:
    """Compare a list of records or lines with a list value (``exact``: a
    plain list is the exact records, otherwise an ordered subsequence),
    reading ``deadline`` at each record compared. Returns the problems and
    both sides as the diff shows them."""
    if view.problem is not None:
        return [f"{label}: {view.problem}"], _display(spec), view.value()
    specs, plain = _normalized(spec, exact)
    field_matchers = dict(specs.get("matchers") or {})
    count = len(view.records)
    what = f"comparing {label}"
    problems: list[str] = []
    expected_shown: dict[str, Any] = {}
    actual_shown: dict[str, Any] = {}
    if "count" in specs:
        expected_shown["count"] = specs["count"]
        actual_shown["count"] = count
        if specs["count"] != count:
            problems.append(f"{label}: {count} record(s), expected count {_show(specs['count'])}")
    if "records" in specs:
        records = list(specs["records"])
        union = _union_fields(records)
        expected_shown["records"] = _display(records)
        rows = []
        if len(records) != count:
            problems.append(f"{label}: {count} record(s), expected exactly {len(records)}")
        for index in range(count):
            deadline.tick(what)
            if index >= len(records):
                rows.append(_restricted(view, index, union))
                continue
            problem, shown = _record_check(records[index], view, index, ctx, field_matchers)
            if problem is not None:
                problems.append(f"{label} record {index + 1}: {problem}")
            rows.append(shown)
        actual_shown["records"] = rows
    if "contains" in specs:
        wanted = list(specs["contains"])
        expected_shown["contains"] = _display(wanted)
        rows = []
        position = 0  # records before it are matched or passed over
        for number, item in enumerate(wanted, start=1):
            found = None
            for index in range(position, count):
                deadline.tick(what)
                problem, shown = _record_check(item, view, index, ctx, field_matchers)
                if problem is None:
                    found = (index, shown)
                    break
            if found is None:
                after = f"after record {position}" if position else "in any record"
                problems.append(f"{label}: contains[{number}] {_show(_display(item))} is not found {after}")
                rows.append("<not found>")
                continue
            position = found[0] + 1
            rows.append(found[1])
        actual_shown["contains"] = rows
    if plain:
        key = "records" if exact else "contains"
        return problems, expected_shown[key], actual_shown[key]
    return problems, expected_shown, actual_shown


def _lines_view(name: str, lines: Sequence[Any]) -> _View:
    return _View(name, records=list(lines), order=list(range(len(lines))))


def _screens_check(
    spec: Any, screens: Sequence[Mapping[str, Any]], ctx: _Context, label: str, deadline: _Deadline = _UNBOUNDED
) -> tuple[list[str], Any, Any]:
    """Compare the captured screens step by step over the fields each
    expected step names, ``@map`` and ``@text`` included, reading
    ``deadline`` at each screen."""
    if not isinstance(spec, list):
        raise BaselineError(f"{label}: must be a list with one field map per step")
    problems: list[str] = []
    rows: list[Any] = []
    what = f"comparing {label}"
    if len(screens) != len(spec):
        problems.append(f"{label}: {len(screens)} screen(s) captured, expected {len(spec)}")
    for number, expected in enumerate(spec, start=1):
        deadline.tick(what)
        actual = screens[number - 1] if number <= len(screens) else {MISSING: True}
        if MISSING in actual:
            problems.append(f"{label} step {number}: no screen was captured")
            rows.append({MISSING: True})
            continue
        shown: dict[str, Any] = {}
        for name, literal in expected.items():
            got = actual.get(name, _ABSENT)
            matcher = ctx.global_matchers.get(name)
            value = literal if is_matcher(literal) or matcher is None else matcher
            problem = _value_problem(value, got, ctx)
            if problem is not None:
                problems.append(f"{label} step {number} {name}: {problem}")
            shown[name] = _shown(value, got, problem)
        rows.append(shown)
    for screen in screens[len(spec) :]:
        deadline.tick(what)
        rows.append(dict(screen))
    return problems, _display(spec), rows


def _reason_text(code: Any) -> Any:
    """A numeric reason code as its four-digit text (``102`` -> ``'0102'``);
    anything else, and an integer too long to write in decimal, unchanged."""
    if isinstance(code, int) and not isinstance(code, bool):
        try:
            return f"{code:04d}"
        except ValueError:
            pass
    return code


def _reason_texts(codes: Any) -> Any:
    """Reason codes as their four-digit texts (``102`` -> ``'0102'``)."""
    if isinstance(codes, list):
        return [_reason_text(code) for code in codes]
    if isinstance(codes, Mapping) and not is_matcher(codes):
        return {key: (_reason_texts(value) if key in ("records", "contains") else value) for key, value in codes.items()}
    return codes


def _matcher_map(value: Any) -> dict[str, Mapping[str, Any]]:
    if not isinstance(value, Mapping):
        return {}
    return {_key_text(name): matcher for name, matcher in value.items() if is_matcher(matcher)}


def _loose_match(item: Any, record: Any, wildcards: Iterable[str]) -> bool:
    """Whether ``record`` matches ``item`` with every matcher (and every field
    a field-level matcher qualifies) taken as a wildcard: the alignment the
    projection and the raw comparison use, which needs no run date."""
    if is_matcher(item):
        return isinstance(record, str)
    if isinstance(item, Mapping):
        if not isinstance(record, dict):
            return False
        loose = set(wildcards)
        return all(
            name in record
            and (is_matcher(value) or name in loose or _plain_problem(value, record[name]) is None)
            for name, value in item.items()
        )
    return isinstance(record, str) and _plain_problem(item, record) is None


def _list_marks(
    spec: Any,
    records: Sequence[Any],
    global_matchers: Mapping[str, Any],
    exact: bool,
    every_record: bool,
    deadline: _Deadline = _UNBOUNDED,
) -> dict[int, dict[str, str]]:
    """Record index -> {field (``*`` for a whole line): matcher kind} for
    every value the expectation ``spec`` gives as a matcher: positional for
    ``records``, by loose alignment for ``contains``, and in every record for
    field-level matchers (where the record holds the field, unless
    ``every_record``). ``deadline`` is read at each record."""
    if spec is None or spec == _scenarios.BASELINE:
        return {}
    try:
        specs, _ = _normalized(spec, exact)
    except BaselineError:
        return {}
    marks: dict[int, dict[str, str]] = {}
    field_level = {**global_matchers, **_matcher_map(specs.get("matchers"))}
    for index, record in enumerate(records):
        deadline.tick(_PROJECTING)
        for name, matcher in field_level.items():
            if every_record or (isinstance(record, dict) and name in record):
                marks.setdefault(index, {})[name] = matcher["match"]

    def mark(index: int, item: Any) -> None:
        if is_matcher(item):
            marks.setdefault(index, {})["*"] = item["match"]
        elif isinstance(item, Mapping):
            for name, value in item.items():
                if is_matcher(value):
                    marks.setdefault(index, {})[name] = value["match"]

    if isinstance(specs.get("records"), list):
        for index, item in enumerate(specs["records"][: len(records)]):
            deadline.tick(_PROJECTING)
            mark(index, item)
    if isinstance(specs.get("contains"), list):
        position = 0
        for item in specs["contains"]:
            for index in range(position, len(records)):
                deadline.tick(_PROJECTING)
                if _loose_match(item, records[index], field_level):
                    mark(index, item)
                    position = index + 1
                    break
    return marks


def _apply_marks(records: list, marks: Mapping[int, Mapping[str, str]], deadline: _Deadline = _UNBOUNDED) -> None:
    for index, names in marks.items():
        deadline.tick(_PROJECTING)
        record = records[index]
        if isinstance(record, str) and "*" in names:
            records[index] = names["*"]
        elif isinstance(record, dict):
            for name, kind in names.items():
                if name in record:
                    record[name] = kind


def _section_value(expected: Any, section: str, name: str) -> Any:
    values = expected.get(section) if isinstance(expected, Mapping) else None
    return values.get(name) if isinstance(values, Mapping) else None


def _copied(value: Any, deadline: _Deadline, memo: dict[int, Any] | None = None) -> Any:
    """``copy.deepcopy(value)``, reading ``deadline`` at each item of every
    list a mapping or list of ``value`` holds. Lists and dicts down to those
    items are copied here, the items and everything else by
    ``copy.deepcopy`` under one shared memo, so an object held twice is
    copied once, as ``copy.deepcopy`` copies it."""
    memo = {} if memo is None else memo
    if type(value) is list:
        if id(value) in memo:
            return memo[id(value)]
        items: list = []
        memo[id(value)] = items
        for item in value:
            deadline.tick(_PROJECTING)
            items.append(copy.deepcopy(item, memo))
        return items
    if type(value) is dict:
        if id(value) in memo:
            return memo[id(value)]
        mapping: dict = {}
        memo[id(value)] = mapping
        for key, item in value.items():
            mapping[copy.deepcopy(key, memo)] = _copied(item, deadline, memo)
        return mapping
    return copy.deepcopy(value, memo)


def projection(
    decoded: Mapping[str, Any], scenario: Any, baseline: Baseline | None = None, *, deadline: float | None = None
) -> dict:
    """The deterministic projection stored in result records: ``decoded``
    (from ``decode_result``) with every field whose expected value is a
    matcher, in the baseline or in the scenario, holding the matcher's kind
    (``run-timestamp``) instead of the observed value. The screen clock
    fields (``SCREEN_CLOCK_FIELDS``) hold their kind on every captured screen
    whether or not the step names them, because their observed value is the
    run clock; a matcher the step or a top-level ``matchers`` entry names
    still decides the kind shown. Nothing else changes: no value is trimmed,
    case-folded, reordered or dropped.

    ``deadline`` is the scenario deadline as a ``time.monotonic()`` value
    (``None``, the default, sets none): it is read on entry and as records
    are copied and marked, and once it has passed
    ``adapters.base.ScenarioTimeout`` names the projection."""
    clock = _Deadline(deadline)
    clock.check(_PROJECTING)
    out = _copied(dict(decoded), clock)
    inline = scenario.expected if isinstance(scenario.expected, Mapping) else {}
    stored = baseline.expected if baseline is not None else {}
    global_matchers = {**_matcher_map(stored.get("matchers")), **_matcher_map(inline.get("matchers"))}
    messages = out.get("messages")
    if isinstance(messages, list):
        for spec, exact in ((stored.get("messages"), True), (inline.get("messages"), False)):
            _apply_marks(messages, _list_marks(spec, messages, {}, exact, False, clock), clock)
    for section in ("datasets", "file_state"):
        values = out.get(section)
        if not isinstance(values, dict):
            continue
        for name, records in values.items():
            if not isinstance(records, list):
                continue
            for spec in (_section_value(stored, section, name), _section_value(inline, section, name)):
                _apply_marks(records, _list_marks(spec, records, global_matchers, True, False, clock), clock)
    screens = out.get("screens")
    if isinstance(screens, list):
        for screen in screens:
            if not isinstance(screen, dict) or MISSING in screen:
                continue
            for name, kind in SCREEN_CLOCK_FIELDS.items():
                if name in screen:
                    screen[name] = kind
        for spec in (stored.get("screens"), inline.get("screens")):
            steps = spec if isinstance(spec, list) else []
            for index, screen in enumerate(screens):
                if not isinstance(screen, dict) or MISSING in screen:
                    continue
                step = steps[index] if index < len(steps) and isinstance(steps[index], Mapping) else {}
                for name in list(screen):
                    value = step.get(name)
                    if is_matcher(value):
                        screen[name] = value["match"]
                    elif name in global_matchers:
                        screen[name] = global_matchers[name]["match"]
    return out


_WHOLE_LINE = "*"  # the binding key of a matcher that stands for a whole line


@functools.lru_cache(maxsize=None)
def _path_keys(layout: str) -> dict[str, str]:
    """A field's path (without subscripts) -> its ``decode_record`` key: the
    inverse of ``_key_paths``."""
    return {path: key for key, path in _key_paths(layout).items()}


def _leaf_key(view: _View, index: int, name: str, where: str) -> str | None:
    """The key under which record ``index`` of ``view``, a field map, holds the
    one decoded field ``name`` denotes as ``_View.field_value`` reads it:
    ``name`` itself when the record holds it, else the key of the field whose
    path, with the same COBOL subscripts, ``name`` is. ``None`` when ``name``
    denotes nothing in the record. A group, a dataset's own field and ``@raw``
    are read from the raw record, so no decoded field can hold their matcher:
    ``BaselineError`` naming ``where`` and what ``name`` denotes."""
    record = view.records[index]
    if name in record:
        return name
    base, bracket, subscripts = name.partition("(")
    if view.layout is not None:
        key = _path_keys(view.layout).get(base)
        if key is not None and key + bracket + subscripts in record:
            return key + bracket + subscripts
    if view.field_value(index, name) is _ABSENT:
        return None
    if name == RAW_KEY:
        denoted = "the whole raw record"
    elif view.layout is not None and (base in _key_paths(view.layout) or base in _path_keys(view.layout)):
        denoted = f"a group of {view.layout}"
    else:
        denoted = f"one of the {view.name} dataset's own fields"
    raise BaselineError(
        f"{where} names {name}, {denoted}, not one decoded field, so its matcher cannot be placed in the record"
    )


def _own_matchers(item: Any, source: str) -> list[tuple[str, str, Mapping[str, Any]]]:
    """``(source, name, matcher)`` for every matcher the scenario record
    ``item`` gives; a whole-line matcher has the name ``*``."""
    if is_matcher(item):
        return [(source, _WHOLE_LINE, item)]
    if isinstance(item, Mapping):
        return [(source, _key_text(name), value) for name, value in item.items() if is_matcher(value)]
    return []


def _bind_level(
    placed: Iterable[tuple[str, str, str, Mapping[str, Any]]], where: str
) -> dict[str, Mapping[str, Any]]:
    """Decoded key -> matcher for the matchers of one level, each given as
    ``(source, name, key, matcher)``. Two names of the level giving one field
    different matchers are refused: the field can hold only one."""
    chosen: dict[str, tuple[str, str, Mapping[str, Any]]] = {}
    for source, name, key, matcher in placed:
        earlier = chosen.setdefault(key, (source, name, matcher))
        if dict(earlier[2]) != dict(matcher):
            raise BaselineError(
                f"{where}: {earlier[0]} gives {key} the matcher {_show(dict(earlier[2]))} as {earlier[1]}, and "
                f"{source} gives it {_show(dict(matcher))} as {name}; one field holds one matcher"
            )
    return {key: matcher for key, (_, _, matcher) in chosen.items()}


def _entry_bindings(spec: Any, view: _View, ctx: _Context, label: str) -> list[dict[str, Mapping[str, Any]]]:
    """``bind_matchers`` for one ``datasets``/``file_state`` entry, labelled
    ``label``: the scenario's value ``spec`` over its decoded ``view``."""
    if view.problem is not None:
        return []
    count = len(view.records)
    own: list[list[tuple[str, str, Mapping[str, Any]]]] = [[] for _ in range(count)]
    entry: dict[str, Mapping[str, Any]] = {}
    if spec != _scenarios.BASELINE:
        try:
            specs, _ = _normalized(spec, True)
        except BaselineError as exc:
            raise BaselineError(f"{label}: {exc}") from None
        entry = _matcher_map(specs.get("matchers"))
        if isinstance(specs.get("records"), list):
            for index, item in enumerate(specs["records"][:count]):
                own[index].extend(_own_matchers(item, f"records[{index + 1}]"))
        if isinstance(specs.get("contains"), list):
            position = 0  # as _list_check aligns: records before it are matched or passed over
            for number, item in enumerate(specs["contains"], start=1):
                given = _own_matchers(item, f"contains[{number}]")
                found = next(
                    (at for at in range(position, count) if _record_check(item, view, at, ctx, entry)[0] is None),
                    None,
                )
                if found is None:
                    if given:
                        after = f" after record {position}" if position else ""
                        raise BaselineError(
                            f"{label}: contains[{number}] matches no observed record{after}, so its matchers "
                            f"({', '.join(sorted({name for _, name, _ in given}))}) cannot be placed"
                        )
                    continue
                position = found + 1
                own[found].extend(given)
    levels = (  # lowest precedence first: the top-level matchers, then the entry's
        [("expected.matchers", name, matcher) for name, matcher in ctx.global_matchers.items()],
        [(f"{label}.matchers", name, matcher) for name, matcher in entry.items()],
    )
    held: set[str] = set()  # the entry's names some record holds
    bindings: list[dict[str, Mapping[str, Any]]] = []
    for index, record in enumerate(view.records):
        where = f"{label} record {index + 1}"
        if isinstance(record, str):
            fields = sorted({name for _, name, _ in own[index] if name != _WHOLE_LINE})
            if fields:
                raise BaselineError(
                    f"{where}: is a line, so the scenario's matchers for {', '.join(fields)} name no field"
                )
            bindings.append(_bind_level([(s, name, name, m) for s, name, m in own[index]], where))
            continue
        if any(name == _WHOLE_LINE for _, name, _ in own[index]):
            raise BaselineError(f"{where}: the scenario gives a whole-line matcher for a field-map record")
        from_top, from_entry, from_own = (
            [(s, name, _leaf_key(view, index, name, f"{where}: {s}"), m) for s, name, m in named]
            for named in (*levels, own[index])
        )
        unplaced = sorted({name for _, name, key, _ in from_own if key is None})
        if unplaced:
            raise BaselineError(
                f"{where}: the scenario's matchers for {', '.join(unplaced)} name no decoded field of the record; "
                f"name each field as it decodes or by its field path"
            )
        held.update(name for _, name, key, _ in from_entry if key is not None)
        binding: dict[str, Mapping[str, Any]] = {}
        for placed in (from_top, from_entry, from_own):
            binding.update(_bind_level([item for item in placed if item[2] is not None], where))
        bindings.append(binding)
    unused = sorted(name for name in entry if name not in held)
    if view.records and unused:
        raise BaselineError(
            f"{label}.matchers: {', '.join(unused)} names no decoded field of any record; "
            f"name each field as it decodes or by its field path"
        )
    return bindings


def bind_matchers(
    scenario: Any, result: _base.Result, adapter_name: str, run_date: Any = None
) -> dict[tuple[str, str], list[dict[str, Mapping[str, Any]]]]:
    """Where the scenario's matchers stand in the decoded ``result``:
    ``(section, name)`` of each ``datasets``/``file_state`` entry the
    scenario's ``expected`` names -> one ``{decoded key: matcher}`` per
    record, in ``decode_result`` order (``*`` is the key of a whole line).

    Each matcher binds where ``compare`` checks it: a ``records`` item's by
    position; a ``contains`` item's at the record ``compare`` aligns the item
    with, the first one after the previous item's that it matches under
    ``adapter_name``'s clock (exact on ``FIXED_CLOCK_ADAPTERS``, else related
    to ``run_date``); the entry's ``matchers`` and the top-level ``matchers``
    at every record holding the field. Names are read as ``compare`` reads
    them: a decoded key, or a field path with its COBOL subscripts, which
    binds to that field's decoded key. A field takes its record's own matcher
    first, then the entry's, then the top-level one. An entry whose records
    ``compare`` cannot read (not produced, not decodable) binds nothing, and
    an inline ``baseline`` binds only the top-level matchers.

    ``BaselineError`` for a matcher no decoded field can hold: a name denoting
    a group, a dataset's own field or ``@raw``; a record's own name its record
    does not hold, or an entry's name no record holds; a field matcher for a
    line or a whole-line matcher for a field map; a ``contains`` item with a
    matcher that matches no record; two names of one level giving one field
    different matchers. A top-level name no record of an entry holds binds
    nothing there: it may name a field of another dataset or of a screen."""
    expected = scenario.expected if isinstance(scenario.expected, Mapping) else {}
    bundle = _decode(scenario, result)
    clock = _scenarios.clock(scenario)
    _parse_clock(clock)
    ctx = _Context(
        adapter_name in FIXED_CLOCK_ADAPTERS, clock, _as_date(run_date), _matcher_map(expected.get("matchers"))
    )
    bound: dict[tuple[str, str], list[dict[str, Mapping[str, Any]]]] = {}
    for section in ("datasets", "file_state"):
        names = expected.get(section)
        if not isinstance(names, Mapping):
            continue
        for name, spec in names.items():
            key = (section, str(name))
            bound[key] = _entry_bindings(spec, bundle.views[key], ctx, f"{section}.{name}")
    return bound


@functools.lru_cache(maxsize=None)
def _layout_span(layout: str, key: str) -> tuple[int, int] | None:
    """The bytes of field ``key`` (a decode_record key or a path, with COBOL
    subscripts for table occurrences) in a record of ``layout``."""
    match = re.fullmatch(r"(?P<base>.+?)(?:\((?P<indices>[0-9]+(?:,[0-9]+)*)\))?", key)
    if match is None:
        return None
    fields = _layouts.field_map(layout)
    entry = fields.get(match["base"])
    if entry is None:
        return None
    indices = [int(text) for text in match["indices"].split(",")] if match["indices"] else []
    parts = entry["path"].split(".")
    strides = []
    for depth in range(1, len(parts) + 1):
        ancestor = fields.get(".".join(parts[:depth]))
        if ancestor is not None and ancestor.get("occurs"):
            strides.append(ancestor["length"])
    if len(strides) != len(indices):
        return None
    offset = entry["offset"] + sum((index - 1) * stride for index, stride in zip(indices, strides))
    return offset, offset + entry["length"]


def _field_span(view: _View, name: str) -> tuple[int, int] | None:
    if name in ("*", RAW_KEY):
        return (0, view.record_length) if view.record_length else None
    if view.layout is not None:
        span = _layout_span(view.layout, name)
        if span is not None:
            return span
    for own in view.own_fields:
        if own.name == name:
            return own.offset, own.offset + own.length
    return None


def _excluded_ranges(
    view: _View, marks: Mapping[int, Mapping[str, str]], deadline: _Deadline = _UNBOUNDED
) -> list[tuple[int, int]]:
    """Byte ranges of the delivered dataset that matcher fields occupy. A
    field whose bytes cannot be located excludes its whole record, so a value
    the adapter's clock decided never fails the raw comparison."""
    if not view.record_length or view.raw is None:
        return []
    ranges = []
    what = f"locating the matcher fields of {view.name}"
    for index, names in marks.items():
        deadline.tick(what)
        start = view.order[index] * view.record_length
        for name in names:
            span = _field_span(view, name) or (0, view.record_length)
            ranges.append((start + span[0], start + span[1]))
    return ranges


def _first_difference(left: bytes, right: bytes, deadline: _Deadline = _UNBOUNDED) -> int:
    limit = min(len(left), len(right))
    step = 4096
    for start in range(0, limit, step):
        deadline.tick("comparing raw bytes")
        end = min(start + step, limit)
        if left[start:end] != right[start:end]:
            return next(index for index in range(start, end) if left[index] != right[index])
    return limit


def _raw_problem(
    name: str,
    expected: bytes,
    actual: bytes | None,
    record_length: int | None,
    excluded: Sequence[tuple[int, int]],
    deadline: _Deadline = _UNBOUNDED,
) -> str | None:
    """Byte-for-byte comparison of a dataset with its ``.bin``; ``excluded``
    ranges are left out of both, and ``deadline`` is read as they are. Neither
    side is modified."""
    if actual is None:
        return f"datasets/{name}{RAW_SUFFIX}: {name} was not produced"
    left, right = expected, actual
    if excluded:
        masked_left, masked_right = bytearray(expected), bytearray(actual)
        what = f"masking the matcher fields of datasets/{name}{RAW_SUFFIX}"
        for start, end in excluded:
            deadline.tick(what)
            for buffer in (masked_left, masked_right):
                stop = min(end, len(buffer))
                if start < stop:
                    buffer[start:stop] = bytes(stop - start)
        left, right = bytes(masked_left), bytes(masked_right)
    if left == right:
        return None
    offset = _first_difference(left, right, deadline)
    where = f"byte offset {offset}"
    if record_length:
        where += f" (record {offset // record_length + 1}, byte {offset % record_length + 1})"
    return (
        f"datasets/{name}{RAW_SUFFIX}: {len(expected)} byte(s) expected, {len(actual)} observed; "
        f"first difference at {where}"
    )


@dataclass
class _Finding:
    """One failed check: ``kind`` is ``error`` (the comparison cannot be
    made), ``baseline`` or ``inline`` (a decoded value differs from the
    baseline's or the scenario's), or ``raw`` (bytes differ)."""

    kind: str
    key: str
    problems: list[str]
    expected: Any = _ABSENT
    actual: Any = _ABSENT


_FINDING_TITLES = {
    "error": "cannot be compared",
    "baseline": "differs from the baseline",
    "inline": "differs from the scenario's expected value",
    "raw": "raw bytes differ from the baseline",
}


def _clipped_text(text: str) -> str:
    """``text`` as a diff shows it: whole up to ``_DIFF_MAX_CHARS_PER_VALUE``
    characters, else cut to that many and a marker counting the rest."""
    if len(text) <= _DIFF_MAX_CHARS_PER_VALUE:
        return text
    left_out = len(text) - _DIFF_MAX_CHARS_PER_VALUE
    return f"{text[:_DIFF_MAX_CHARS_PER_VALUE]}... ({left_out} more character(s) not shown)"


def _clipped(value: Any, deadline: _Deadline) -> Any:
    """``value`` with every string in it, mapping keys included, cut as a
    diff shows it (``_clipped_text``), so its YAML costs what the diff can
    show however long its strings are. Lists, tuples and dicts are copied
    down to their strings, reading ``deadline`` at each item; every other
    value is kept. A key whose cut text an earlier key of the same mapping
    already has is kept whole, so no entry is lost."""
    if isinstance(value, str):
        return _clipped_text(value)
    if isinstance(value, (list, tuple)):
        items = []
        for item in value:
            deadline.tick(_RENDERING)
            items.append(_clipped(item, deadline))
        return items
    if isinstance(value, dict):
        mapping: dict[Any, Any] = {}
        for key, item in value.items():
            deadline.tick(_RENDERING)
            shown = _clipped_text(key) if isinstance(key, str) else key
            mapping[key if shown is not key and shown in mapping else shown] = _clipped(item, deadline)
        return mapping
    return value


def _yaml_lines(value: Any, deadline: _Deadline = _UNBOUNDED) -> list[str]:
    """The lines of ``value`` as a diff writes it: its YAML, with every string
    cut first (``_clipped``), written under ``deadline``, past which
    ``adapters.base.ScenarioTimeout`` names the diff. A value YAML cannot
    hold is shown by ``_show`` instead."""
    shown = _clipped(value, deadline)
    try:
        return dump_yaml(shown, deadline=deadline.at).splitlines()
    except TimeoutError as exc:
        raise _base.ScenarioTimeout(f"the scenario deadline passed while {_RENDERING}") from exc
    except HarnessError:  # an integer too long to write in decimal, among others
        return [_show(shown, 10_000)]


def _first_unequal(left: Sequence[Any], right: Sequence[Any], deadline: _Deadline) -> int:
    """The first index at which ``left`` and ``right`` hold different items:
    the shorter one's length when it is the other's beginning."""
    limit = min(len(left), len(right))
    for index in range(limit):
        deadline.tick(_RENDERING)
        if left[index] != right[index]:
            return index
    return limit


def _window_end(items: Sequence[Any], start: int, deadline: _Deadline) -> int:
    """Where the diff window of ``items`` that begins at ``start`` ends: after
    the item whose YAML, as the diff writes it (``_yaml_lines``), brings the
    window to ``DIFF_MAX_LINES`` lines or ``DIFF_MAX_CHARS`` characters, or
    at the end of ``items``."""
    lines, chars, end = 0, 0, start
    while end < len(items) and lines < DIFF_MAX_LINES and chars < DIFF_MAX_CHARS:
        deadline.tick(_RENDERING)
        written = _yaml_lines([items[end]], deadline)
        lines += len(written)
        chars += sum(len(line) + 1 for line in written)
        end += 1
    return end


def _cut_lists(left: Any, right: Any, deadline: _Deadline) -> tuple[Any, Any, str | None]:
    """``left`` (expected) and ``right`` (actual) cut to a diff window, and
    what the window leaves out; both unchanged, with ``None``, when each list
    fits a window whole. The window of a list begins ``_DIFF_CONTEXT_ITEMS``
    items before the first one at which the two lists differ (the start of a
    list whose other side is no list, which is kept whole) and ends where
    ``_window_end`` says."""
    sides = [("expected", left), ("actual", right)]
    lists = {name: side for name, side in sides if isinstance(side, list)}
    if all(_window_end(side, 0, deadline) == len(side) for side in lists.values()):
        return left, right, None
    first = _first_unequal(left, right, deadline) if len(lists) == 2 else 0
    start = max(0, first - _DIFF_CONTEXT_ITEMS)
    ends = {name: _window_end(side, start, deadline) for name, side in lists.items()}
    cut = [side[start : ends[name]] if name in ends else side for name, side in sides]
    left_out = [f"{start} identical leading item(s)"] if start else []
    trailing = [f"{len(side) - ends[name]} {name}" for name, side in sides if name in ends and len(side) > ends[name]]
    if trailing:
        left_out.append(f"{', '.join(trailing)} trailing item(s)")
    return cut[0], cut[1], f"{' and '.join(left_out)} not shown"


def _bounded(expected: Any, actual: Any, deadline: _Deadline) -> tuple[Any, Any, list[str]]:
    """``expected`` and ``actual`` as a diff of ``DIFF_MAX_LINES`` lines and
    ``DIFF_MAX_CHARS`` characters can show them, with one line per list cut
    saying what it leaves out (``_cut_lists``): a list value, or the
    ``records`` and ``contains`` lists of a mapping value. A list that fits
    is kept whole, so a small value is shown in full."""
    if isinstance(expected, list) or isinstance(actual, list):
        left, right, note = _cut_lists(expected, actual, deadline)
        return left, right, [note] if note is not None else []
    if not isinstance(expected, dict) and not isinstance(actual, dict):
        return expected, actual, []
    sides = [dict(side) if isinstance(side, dict) else side for side in (expected, actual)]
    notes: list[str] = []
    for key in ("records", "contains"):
        held = [side.get(key) if isinstance(side, dict) else None for side in sides]
        left, right, note = _cut_lists(held[0], held[1], deadline)
        for side, value in zip(sides, (left, right)):
            if isinstance(side, dict) and key in side:
                side[key] = value
        if note is not None:
            notes.append(f"{key}: {note}")
    return sides[0], sides[1], notes


def _render(findings: Sequence[_Finding], scenario_id: Any, deadline: _Deadline = _UNBOUNDED) -> str:
    """The human-readable diff: per failing key a title naming
    ``scenario_id`` and the key, its problems, then a unified diff of the
    expected and the actual value as YAML, truncated at ``DIFF_MAX_LINES``
    lines and then at ``DIFF_MAX_CHARS`` characters, each cut ending in a
    line counting what it leaves out. Each value is cut before it is written,
    its lists to a window (``_bounded``) and its strings to
    ``_DIFF_MAX_CHARS_PER_VALUE`` characters (``_yaml_lines``), so the diff
    costs what it can show however long the lists and strings are;
    ``deadline`` is read while it is built and its YAML written."""
    lines: list[str] = []
    for finding in findings:
        deadline.tick(_RENDERING)
        lines.append(f"{scenario_id}: {finding.key}: {_FINDING_TITLES[finding.kind]}")
        listed = finding.problems[:_PROBLEMS_PER_KEY]
        lines.extend(f"  - {problem}" for problem in listed)
        if len(finding.problems) > len(listed):
            lines.append(f"  - ... and {len(finding.problems) - len(listed)} more")
        if finding.expected is _ABSENT or not isinstance(finding.expected, (list, dict)):
            continue
        source = "baseline" if finding.kind == "baseline" else "scenario"
        expected, actual, notes = _bounded(finding.expected, finding.actual, deadline)
        lines.extend(f"  ... {note}" for note in notes)
        lines.extend(
            difflib.unified_diff(
                _yaml_lines(expected, deadline),
                _yaml_lines(actual, deadline),
                fromfile=f"expected {finding.key} ({source})",
                tofile=f"actual {finding.key}",
                lineterm="",
            )
        )
    if len(lines) > DIFF_MAX_LINES:
        hidden = len(lines) - DIFF_MAX_LINES
        lines = lines[:DIFF_MAX_LINES] + [f"... diff truncated: {hidden} more line(s) not shown"]
    text = "\n".join(lines)
    if len(text) <= DIFF_MAX_CHARS:
        return text
    # The note's length grows with the count it gives, so the cut leaves room
    # for the longest note, the whole text's count, and falls at a line break
    # where there is one.
    room = DIFF_MAX_CHARS - len(f"\n... diff truncated: {len(text)} more character(s) not shown")
    cut = text.rfind("\n", 0, room + 1)
    cut = cut if cut > 0 else room
    return f"{text[:cut]}\n... diff truncated: {len(text) - cut} more character(s) not shown"


class _Checker:
    """Collects the findings of one comparison."""

    def __init__(self) -> None:
        self.findings: list[_Finding] = []

    def add(self, kind: str, key: str, problems: list[str], expected: Any = _ABSENT, actual: Any = _ABSENT) -> None:
        if problems:
            self.findings.append(_Finding(kind, key, problems, expected, actual))

    def run(self, kind: str, key: str, check: Any) -> None:
        """Run ``check() -> (problems, expected_shown, actual_shown)``; a
        malformed expectation becomes an ``error`` finding for that key."""
        try:
            problems, expected, actual = check()
        except HarnessError as exc:
            self.add("error", key, [f"{key}: {exc}"])
            return
        self.add(kind, key, problems, expected, actual)


def _findings(
    scenario: Any, baseline: Baseline | None, result: Any, adapter_name: str, run_date: Any,
    deadline: _Deadline = _UNBOUNDED,
) -> list[_Finding]:
    checker = _Checker()
    scenario_id = getattr(scenario, "id", "?")
    if baseline is None:
        checker.add("error", "baseline", [f"no baseline for {scenario_id}: tests/baselines/{scenario_id}/ is missing"])
        return checker.findings
    if not isinstance(result, _base.Result):
        checker.add("error", "result", [f"no adapters.base.Result to compare, got {type(result).__name__}"])
        return checker.findings
    expected = scenario.expected
    if not isinstance(expected, Mapping):
        checker.add("error", "expected", ["the scenario's expected is not a mapping"])
        return checker.findings
    stored = baseline.expected
    bundle = _decode(scenario, result, deadline)
    decoded = bundle.decoded
    clock = _scenarios.clock(scenario)
    _parse_clock(clock)
    exact = adapter_name in FIXED_CLOCK_ADAPTERS
    day = _as_date(run_date)
    scenario_matchers = _matcher_map(expected.get("matchers"))
    stored_matchers = _matcher_map(stored.get("matchers"))
    contexts = {
        "baseline": _Context(exact, clock, day, {**scenario_matchers, **stored_matchers}),
        "inline": _Context(exact, clock, day, {**stored_matchers, **scenario_matchers}),
    }

    def sources(key: str, stored_values: Mapping[str, Any], name: str, inline_value: Any) -> list[tuple[str, Any]]:
        """The expectations ``key`` is checked against: the baseline's value
        (an ``error`` when the baseline lacks it) and the scenario's own,
        unless that defers to the baseline."""
        found = []
        if name in stored_values:
            found.append(("baseline", stored_values[name]))
        else:
            checker.add("error", key, [f"the baseline lacks {key}"])
        if inline_value != _scenarios.BASELINE:
            found.append(("inline", inline_value))
        return found

    if "return_code" in expected:
        for kind, spec in sources("return_code", stored, "return_code", expected["return_code"]):
            problem = _value_problem(spec, decoded["return_code"], contexts[kind])
            checker.add(kind, "return_code", [f"return_code: {problem}"] if problem else [])

    if "messages" in expected:
        lines = _lines_view("messages", decoded["messages"])
        for kind, spec in sources("messages", stored, "messages", expected["messages"]):
            checker.run(kind, "messages", lambda spec=spec, kind=kind: _list_check(
                spec, lines, contexts[kind], kind == "baseline", "messages", deadline))

    if "reject_reason_codes" in expected:
        codes = decoded["reject_reason_codes"]
        found = sources("reject_reason_codes", stored, "reject_reason_codes", expected["reject_reason_codes"])
        if codes is None:
            checker.add(found[0][0] if found else "inline", "reject_reason_codes", [
                f"reject_reason_codes: {REJECTS_DATASET} was not produced, so no reason code can be read"])
        elif isinstance(codes, Mapping):
            checker.add("error", "reject_reason_codes", [f"reject_reason_codes: {codes.get(ERROR)}"])
        else:
            view = _lines_view("reject_reason_codes", codes)
            for kind, spec in found:
                checker.run(kind, "reject_reason_codes", lambda spec=spec, kind=kind: _list_check(
                    _reason_texts(spec), view, contexts[kind], True, "reject_reason_codes", deadline))

    for section in ("datasets", "file_state"):
        if section not in expected:
            continue
        names = expected[section]
        if not isinstance(names, Mapping):
            checker.add("error", section, [f"the scenario's expected.{section} is not a mapping"])
            continue
        stored_section = stored.get(section) if isinstance(stored.get(section), Mapping) else {}
        for name, inline_value in names.items():
            key = f"{section}.{name}"
            view = bundle.views[(section, str(name))]
            if view.problem is not None and view.problem_kind == ERROR:
                checker.add("error", key, [f"{key}: {view.problem}"])
                continue
            for kind, spec in sources(key, stored_section, str(name), inline_value):
                checker.run(kind, key, lambda spec=spec, kind=kind, view=view, key=key: _list_check(
                    spec, view, contexts[kind], True, key, deadline))

    if "screens" in expected:
        screens = decoded["screens"]
        for kind, spec in sources("screens", stored, "screens", expected["screens"]):
            checker.run(kind, "screens", lambda spec=spec, kind=kind: _screens_check(
                spec, screens, contexts[kind], "screens", deadline))

    if baseline.capture_platform == adapter_name:
        compared: set[str] = set()
        for section in ("datasets", "file_state"):
            names = expected.get(section)
            for name in names if isinstance(names, Mapping) else ():
                name = str(name)
                if name.startswith("@") or name in compared:
                    continue
                compared.add(name)
                if name not in baseline.datasets:
                    checker.add("error", f"{section}.{name}", [
                        f"datasets/{name}{RAW_SUFFIX}: the baseline observed on {adapter_name} holds no raw bytes "
                        f"for {name}, so it cannot be compared byte for byte"])
                    continue
                view = bundle.views[(section, name)]
                excluded: list[tuple[int, int]] = []
                if not exact:
                    for spec in (_section_value(stored, section, name), _section_value(expected, section, name)):
                        excluded.extend(_excluded_ranges(view, _list_marks(
                            spec, view.records, {**scenario_matchers, **stored_matchers}, True, True, deadline),
                            deadline))
                problem = _raw_problem(
                    name, baseline.datasets[name], result.datasets.get(name), view.record_length, excluded, deadline)
                checker.add("raw", f"{section}.{name}", [problem] if problem else [])
    return checker.findings


def compare(
    scenario: Any,
    baseline: Baseline | None,
    result: _base.Result,
    adapter_name: str,
    run_date: Any = None,
    *,
    deadline: float | None = None,
) -> tuple[str, str | None, str]:
    """Compare a run's ``result`` with the scenario's baseline and inline
    expectations; returns ``(status, reason, diff)``.

    Every key the scenario's ``expected`` names is checked against the
    baseline value (which must exist) and, unless it is ``baseline``, against
    the scenario's own value; when the baseline was captured on
    ``adapter_name`` every business dataset the scenario's ``expected`` names
    is also compared byte for byte with its ``.bin`` (on an adapter whose
    clock is not fixed, without the bytes of matcher fields), and one without
    a ``.bin`` fails with reason ``error``; reserved observations
    (``@CATALOG``, ``@AIX:<path>``, ``@AFTER:<JOB>:<LOGICAL>``,
    ``@BEFORE:<JOB>:@REGION-FILES``, ...) hold no ``.bin`` and are compared
    decoded only. Matchers resolve exactly on
    ``FIXED_CLOCK_ADAPTERS`` and are judged against ``run_date`` elsewhere.

    ``("passed", None, "")`` when every check holds. Otherwise ``failing``
    with reason ``error`` (no baseline, a key the baseline lacks, a result
    that cannot be decoded, a malformed expectation), else
    ``cross-adapter-divergence`` when a decoded value differs from a
    baseline observed on another adapter (that baseline stays
    authoritative), else ``assertion``; ``diff`` names the scenario and
    says what differs. The diff shows each key's problems (at most
    ``_PROBLEMS_PER_KEY``) and a unified diff of the two values as YAML in at
    most ``DIFF_MAX_LINES`` lines and ``DIFF_MAX_CHARS`` characters in all,
    with a line counting what a cut leaves out; of a list longer than that it
    shows the items around the first difference, with a line counting the
    items left out before and after them, and of a string longer than
    ``_DIFF_MAX_CHARS_PER_VALUE`` characters that many, with a marker
    counting the rest. The cuts change only the diff, never the status, the
    reason or a problem.

    ``deadline`` is the scenario deadline as a ``time.monotonic()`` value
    (``None``, the default, sets none): it is read on entry and throughout
    the decoding, the checks and the diff, its YAML included, and once it has
    passed ``adapters.base.ScenarioTimeout`` names what was interrupted,
    whatever the comparison had found so far."""
    scenario_id = getattr(scenario, "id", "?")
    clock = _Deadline(deadline)
    clock.check("comparing the result")
    try:
        findings = _findings(scenario, baseline, result, adapter_name, run_date, clock)
    except HarnessError as exc:
        return STATUS_FAILING, REASON_ERROR, f"{scenario_id}: comparison error: {exc}"
    if not findings:
        return STATUS_PASSED, None, ""
    kinds = {finding.kind for finding in findings}
    if "error" in kinds:
        reason = REASON_ERROR
    elif "baseline" in kinds and baseline is not None and baseline.capture_platform not in (adapter_name, DERIVED):
        reason = REASON_DIVERGENCE
    else:
        reason = REASON_ASSERTION
    return STATUS_FAILING, reason, _render(findings, scenario_id, clock)


# --------------------------------------------------------------------------
# Bump check
# --------------------------------------------------------------------------

_GIT_TIMEOUT_S = 120


class _GitFailed(BumpCheckError):
    """git ran and exited nonzero: ``returncode`` is its exit status and
    ``detail`` its error output on one line, empty when git printed none."""

    def __init__(self, message: str, returncode: int, detail: str) -> None:
        super().__init__(message)
        self.returncode = returncode
        self.detail = detail


def _git(root: Path, args: Sequence[str], stdin: bytes | None = None) -> bytes:
    """Run a local git command in ``root``; never touches a remote. git that
    cannot start or times out raises ``BumpCheckError`` with the cause; git
    exiting nonzero raises ``_GitFailed`` with git's message."""
    try:
        completed = subprocess.run(
            ["git", *args],
            cwd=str(root),
            input=stdin,
            capture_output=True,
            check=False,
            timeout=_GIT_TIMEOUT_S,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        raise BumpCheckError(f"git {' '.join(args[:2])} cannot run in {display_path(root)}: {exc}") from None
    if completed.returncode != 0:
        detail = " ".join(completed.stderr.decode("utf-8", errors="replace").split())
        raise _GitFailed(
            f"git {' '.join(args[:2])} failed in {display_path(root)}: {detail or 'no message'}",
            completed.returncode,
            detail,
        )
    return completed.stdout


def _check_work_tree(root: Path) -> None:
    """Raise ``BumpCheckError`` unless ``root`` is the top level of a git work
    tree, because the check reads ``tests/baselines`` relative to it both on
    disk and through git. git that cannot start or times out propagates."""
    shown = display_path(root)
    named = "this checkout" if shown == "." else f"the repository root {shown}"
    try:
        is_folder = stat.S_ISDIR(root.stat().st_mode)
    except (FileNotFoundError, NotADirectoryError):
        raise BumpCheckError(f"{named} does not exist") from None
    except OSError as exc:
        raise BumpCheckError(f"{named} cannot be read: {exc.strerror or type(exc).__name__}") from None
    if not is_folder:
        raise BumpCheckError(f"{named} is not a directory")
    try:
        output = _git(root, ["rev-parse", "--show-toplevel"])
    except _GitFailed as exc:
        reason = exc.detail or f"git rev-parse --show-toplevel exited {exc.returncode} with no message"
        raise BumpCheckError(f"git cannot use {named} as a work tree: {reason}") from None
    top_text = os.fsdecode(output.removesuffix(b"\n"))
    if not top_text:
        raise BumpCheckError(f"git cannot use {named} as a work tree: git rev-parse --show-toplevel printed nothing")
    top = Path(top_text)
    try:
        same = top.samefile(root)
    except OSError:
        same = False
    if not same:
        top_shown = display_path(top)
        raise BumpCheckError(
            f"{named} lies inside the git work tree whose top level is "
            f"{'this checkout' if top_shown == '.' else top_shown}; give that top level as the repository root"
        )


def _is_shallow(root: Path) -> bool:
    """Whether the repository at ``root`` is a shallow clone; ``False`` when
    git cannot tell, so the probe never hides the error it qualifies."""
    try:
        return _git(root, ["rev-parse", "--is-shallow-repository"]).strip() == b"true"
    except BumpCheckError:
        return False


def _resolve_commit(base: Any, root: Path) -> str:
    """The full commit id ``base`` names in the work tree whose top level is
    ``root``. ``BumpCheckError`` when ``base`` is not revision text, ``root``
    is not such a top level, ``base`` names no commit (with the fetch-depth
    hint only in a shallow clone) or git fails."""
    if not isinstance(base, str) or not base.strip() or base.startswith("-") or any(c in base for c in "\0\n\r"):
        raise BumpCheckError(f"{base!r} is not a revision")
    _check_work_tree(root)
    try:
        commit = _git(root, ["rev-parse", "--verify", "--quiet", f"{base}^{{commit}}"]).decode().strip()
    except _GitFailed as exc:
        if exc.returncode != 1:
            raise
        where = display_path(root)
        message = f"{base!r} is not a commit of {'this checkout' if where == '.' else where}"
        if exc.detail:
            message += f": {exc.detail}"
        if _is_shallow(root):
            message += "; the clone is shallow: fetch the full history (fetch-depth: 0)"
        raise BumpCheckError(message) from None
    if not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", commit):
        raise BumpCheckError(f"{base!r} resolves to {commit!r}, which is not a commit id")
    return commit


def _artifact(relative: str) -> bool:
    """Whether a path inside a baseline directory is one of its artifacts."""
    if relative in (EXPECTED_FILE, MANIFEST_FILE):
        return True
    folder, _, name = relative.partition("/")
    return folder == DATASETS_DIR and "/" not in name and name.endswith(RAW_SUFFIX)


def _base_tree(root: Path, commit: str, folder: str = BASELINES_REL) -> dict[str, dict[str, tuple[str, str]]]:
    """``id -> {artifact: (mode, object id)}`` of every baseline at ``commit``
    that lies in ``folder`` (the baselines folder, or one baseline directory
    in it, as git names them). An empty mapping is confirmed absence: git
    listed the folder and found nothing; git failing raises."""
    listing = _git(root, ["ls-tree", "-r", "-z", commit, "--", folder])
    tree: dict[str, dict[str, tuple[str, str]]] = {}
    prefix = BASELINES_REL + "/"
    for entry in listing.split(b"\0"):
        if not entry:
            continue
        meta, _, raw_path = entry.partition(b"\t")
        mode, kind, oid = meta.decode().split()
        path = raw_path.decode("utf-8", errors="surrogateescape")
        if kind != "blob" or not path.startswith(prefix):
            continue
        baseline_id, _, relative = path[len(prefix):].partition("/")
        if baseline_id and _artifact(relative):
            tree.setdefault(baseline_id, {})[relative] = (mode, oid)
    return tree


def _working_tree(folder: Path) -> tuple[dict[str, dict[str, Path]], list[str]]:
    """``id -> {artifact: path}`` of every baseline directory in the working
    tree (untracked and modified files included), and the problems found:
    symbolic links are reported, never followed. A writer's staging or
    pre-image directory is no baseline and is passed over."""
    tree: dict[str, dict[str, Path]] = {}
    problems: list[str] = []
    if folder.is_symlink():
        return tree, [f"{BASELINES_REL}: a symbolic link, not the baselines folder"]
    if not folder.is_dir():
        return tree, problems
    for directory in sorted(folder.iterdir()):
        if _publication(directory.name) is not None:
            continue
        if directory.is_symlink():
            problems.append(f"{directory.name}: the baseline directory is a symbolic link")
            continue
        if not directory.is_dir():
            continue
        files: dict[str, Path] = {}
        candidates = [directory / EXPECTED_FILE, directory / MANIFEST_FILE]
        raw_folder = directory / DATASETS_DIR
        if raw_folder.is_symlink():
            problems.append(f"{directory.name}: {DATASETS_DIR} is a symbolic link")
        elif raw_folder.is_dir():
            candidates.extend(sorted(raw_folder.glob(f"*{RAW_SUFFIX}")))
        for path in candidates:
            relative = path.relative_to(directory).as_posix()
            if path.is_symlink():
                problems.append(f"{directory.name}: {relative} is a symbolic link")
            elif path.is_file():
                files[relative] = path
        tree[directory.name] = files
    return tree, problems


def _object_id(data: bytes, length: int) -> str:
    """The git blob id of ``data`` in the repository's object format."""
    header = b"blob %d\0" % len(data)
    digest = hashlib.sha256 if length == 64 else hashlib.sha1
    return digest(header + data).hexdigest()


def _cat_blobs(root: Path, oids: Iterable[str]) -> dict[str, bytes]:
    """The content of each blob, read in one ``git cat-file --batch``."""
    wanted = sorted(set(oids))
    if not wanted:
        return {}
    output = _git(root, ["cat-file", "--batch"], stdin="".join(f"{oid}\n" for oid in wanted).encode())
    blobs: dict[str, bytes] = {}
    position = 0
    for oid in wanted:
        end = output.index(b"\n", position)
        header = output[position:end].decode().split()
        if len(header) != 3 or header[1] != "blob":
            raise BumpCheckError(f"git cat-file cannot read blob {oid}: {' '.join(header)}")
        size = int(header[2])
        blobs[oid] = output[end + 1 : end + 1 + size]
        position = end + 1 + size + 1
    return blobs


class _YamlReader:
    """Parses YAML through ``harness.load_yaml``, which reads files, so base
    revision content is written to a private temporary folder first."""

    def __init__(self, folder: Path) -> None:
        self.folder = folder
        self.count = 0

    def parse(self, data: bytes) -> Any:
        self.count += 1
        path = self.folder / f"{self.count}.yaml"
        path.write_bytes(data)
        try:
            return load_yaml(path)
        except YAMLLoadError as exc:
            raise BaselineError(exc.problem) from None
        finally:
            path.unlink()


def _same(left: Any, right: Any) -> bool:
    """Equal values of equal types, the way canonical YAML writes them."""
    try:
        return dump_yaml(left) == dump_yaml(right)
    except HarnessError:
        return left == right


def _generic_unkept(x: Any, y: Any, where: str) -> str | None:
    """The path of the first assertion of ``y`` that ``x`` does not imply,
    read as plain data, or ``None`` when it implies them all: a matcher or a
    scalar must be the same value, a mapping must hold every key of ``y``
    and a list must be as long and agree item by item."""
    label = where or "(document)"
    if is_matcher(x) or is_matcher(y):
        return None if _same(x, y) else label
    if isinstance(x, Mapping) and isinstance(y, Mapping):
        for key, value in y.items():
            child = f"{where}.{_key_text(key)}" if where else _key_text(key)
            if key not in x:
                return child
            missing = _generic_unkept(x[key], value, child)
            if missing is not None:
                return missing
        return None
    if isinstance(x, list) and isinstance(y, list):
        if len(x) != len(y):
            return f"{label} ({len(y)} items became {len(x)})"
        for index, (mine, theirs) in enumerate(zip(x, y)):
            missing = _generic_unkept(mine, theirs, f"{where}[{index}]")
            if missing is not None:
                return missing
        return None
    return None if _same(x, y) else label


@dataclass(frozen=True)
class _ListForm:
    """What a list value asserts: its exact ``records`` (or ``None``), its
    ordered subsequence ``contains`` (or ``None``), the record ``counts`` it
    states and its field-level ``matchers``."""

    records: list | None
    contains: list | None
    counts: frozenset[int]
    matchers: Mapping[str, Mapping[str, Any]]


def _list_form(value: Any) -> _ListForm | None:
    """The ``_ListForm`` of a list value, the same for every spelling of one
    assertion: a plain list and ``{records: <list>}`` are the exact list, and
    so is ``{count: n, contains: <n items>}``, because an ordered subsequence
    of n items in n records is the whole list (``{count: 0}`` is the empty
    list). ``None`` unless the value is a list, or a mapping of list keys
    whose ``records`` and ``contains`` are lists, whose ``count`` is an
    integer and whose ``matchers`` maps fields to matchers."""
    if isinstance(value, list):
        return _ListForm(list(value), None, frozenset({len(value)}), {})
    if not isinstance(value, Mapping) or not set(value) <= _LIST_SPEC_KEYS:
        return None
    if any(key in value and not isinstance(value[key], list) for key in ("records", "contains")):
        return None
    count = value.get("count")
    if "count" in value and (isinstance(count, bool) or not isinstance(count, int)):
        return None
    matchers = value.get("matchers", {})
    if not isinstance(matchers, Mapping) or len(_matcher_map(matchers)) != len(matchers):
        return None
    records, contains = value.get("records"), value.get("contains")
    if records is None and count == len(contains or []):
        records, contains = list(contains or []), None
    counts = {count} if "count" in value else set()
    if records is not None:
        counts.add(len(records))
    return _ListForm(records, contains, frozenset(counts), _matcher_map(matchers))


def _effective(record: Mapping[Any, Any], name: Any, matchers: tuple[Mapping[str, Any], Mapping[str, Any]]) -> Any:
    """The value ``compare`` holds field ``name`` of an expected record to, as
    ``_record_check`` chooses it from the ``(entry, top-level)`` matcher maps:
    a matcher the record writes, else the entry's matcher for the field, else
    the document's, else the literal."""
    literal = record[name]
    if is_matcher(literal):
        return literal
    entry, top = matchers
    matcher = entry.get(name) or top.get(name)
    return literal if matcher is None else matcher


def _record_unkept(
    x: Any,
    y: Any,
    x_matchers: tuple[Mapping[str, Any], Mapping[str, Any]],
    y_matchers: tuple[Mapping[str, Any], Mapping[str, Any]],
) -> str | None:
    """Where expected record ``x`` fails to assert what record ``y`` does:
    ``None`` when it asserts all of it, ``.<field>`` for the first field of a
    field map ``y`` that ``x`` does not name with the same effective value
    (``_effective``, under each record's own matcher maps), ``""`` when ``y``
    is a text line or a whole-record matcher that ``x`` is not."""
    if isinstance(y, Mapping) and not is_matcher(y):
        if not isinstance(x, Mapping) or is_matcher(x):
            return ""
        for name in y:
            if name not in x or not _same(_effective(x, name, x_matchers), _effective(y, name, y_matchers)):
                return f".{_key_text(name)}"
        return None
    return None if _same(x, y) else ""


def _subsequence_gap(
    pool: Sequence[Any],
    items: Sequence[Any],
    x_matchers: tuple[Mapping[str, Any], Mapping[str, Any]],
    y_matchers: tuple[Mapping[str, Any], Mapping[str, Any]],
) -> int | None:
    """The index of the first of ``items`` that no record of ``pool`` after
    the one the previous item matched asserts (``_record_unkept``), or
    ``None`` when ``items`` is an ordered subsequence of ``pool``. Matching
    each item at its earliest record leaves the most records to the rest, so
    the search finds a subsequence whenever one exists."""
    position = 0
    for index, item in enumerate(items):
        for candidate in range(position, len(pool)):
            if _record_unkept(pool[candidate], item, x_matchers, y_matchers) is None:
                position = candidate + 1
                break
        else:
            return index
    return None


def _entry_matcher_kept(form: _ListForm, name: str, matcher: Mapping[str, Any], top: Mapping[str, Any]) -> bool:
    """Whether the list ``form``, under its document's top-level matchers
    ``top``, asserts what an entry-level matcher ``name: matcher`` does. An
    entry matcher replaces the literal of every record naming the field and
    marks the field in every actual record, so it is kept by the same
    field-level matcher (the entry's, else the document's) or by exact
    records that are all field maps naming the field with ``matcher`` as its
    effective value: every actual record is then one of them (none when the
    list is empty), whereas a matcher in some records marks only those."""
    if _same(form.matchers.get(name) or top.get(name), matcher):
        return True
    return form.records is not None and all(
        isinstance(record, Mapping)
        and not is_matcher(record)
        and name in record
        and _same(_effective(record, name, (form.matchers, top)), matcher)
        for record in form.records
    )


def _list_unkept(x: Any, y: Any, where: str, x_top: Mapping[str, Any], y_top: Mapping[str, Any]) -> str | None:
    """The path of the first assertion of list value ``y`` that list value
    ``x`` does not imply, or ``None``; ``x_top`` and ``y_top`` are their
    documents' top-level matchers. Every record count ``y`` states must be
    one ``x`` states; exact ``records`` need exact records of ``x`` as long,
    each asserting its counterpart; ``contains`` must be an ordered
    subsequence of the records or the ``contains`` of ``x``; and every
    entry-level matcher of ``y`` must be kept by ``x``: by the same matcher
    for the field in its entry or its document's top-level matchers, or by
    exact records that all name the field with that matcher as its effective
    value (``_entry_matcher_kept``). A value ``_list_form`` cannot read
    compares as plain data."""
    fx, fy = _list_form(x), _list_form(y)
    if fx is None or fy is None:
        return _generic_unkept(x, y, where)
    lost = sorted(fy.counts - fx.counts)
    if lost:
        if fx.counts and not fx.counts & fy.counts:
            became = " or ".join(_key_text(n) for n in sorted(fx.counts))
            return f"{where} ({_key_text(lost[0])} items became {became})"
        return f"{where}.count"
    x_matchers, y_matchers = (fx.matchers, x_top), (fy.matchers, y_top)
    if fy.records is not None:
        if fx.records is None:
            return f"{where}.records"
        if len(fx.records) != len(fy.records):
            return f"{where} ({len(fy.records)} items became {len(fx.records)})"
        for index, (mine, theirs) in enumerate(zip(fx.records, fy.records)):
            field = _record_unkept(mine, theirs, x_matchers, y_matchers)
            if field is not None:
                return f"{where}[{index}]{field}"
    if fy.contains:
        gaps = [
            _subsequence_gap(pool, fy.contains, x_matchers, y_matchers)
            for pool in (fx.records, fx.contains)
            if pool is not None
        ]
        if None not in gaps:
            return f"{where}.contains[{max(gaps, default=0)}]"
    for name, matcher in fy.matchers.items():
        if not _entry_matcher_kept(fx, name, matcher, x_top):
            return f"{where}.matchers.{name}"
    return None


def _section_unkept(x: Any, y: Any, where: str, x_top: Mapping[str, Any], y_top: Mapping[str, Any]) -> str | None:
    """``_list_unkept`` over every dataset the ``datasets`` or ``file_state``
    mapping ``y`` names; a dataset ``x`` lacks is itself the path."""
    if not isinstance(x, Mapping) or not isinstance(y, Mapping):
        return _generic_unkept(x, y, where)
    for name, value in y.items():
        place = f"{where}.{_key_text(name)}"
        missing = place if name not in x else _list_unkept(x[name], value, place, x_top, y_top)
        if missing is not None:
            return missing
    return None


def _screens_unkept(x: Any, y: Any, where: str, x_top: Mapping[str, Any], y_top: Mapping[str, Any]) -> str | None:
    """The path of the first screen field ``y`` asserts that ``x`` does not:
    ``x`` needs as many steps, each naming every field of its counterpart with
    the same effective value (only top-level matchers apply, as in
    ``_screens_check``). Anything but two lists of field maps compares as
    plain data."""
    if not all(
        isinstance(side, list) and all(isinstance(step, Mapping) and not is_matcher(step) for step in side)
        for side in (x, y)
    ):
        return _generic_unkept(x, y, where)
    if len(x) != len(y):
        return f"{where} ({len(y)} items became {len(x)})"
    for index, (mine, theirs) in enumerate(zip(x, y)):
        field = _record_unkept(mine, theirs, ({}, x_top), ({}, y_top))
        if field is not None:
            return f"{where}[{index}]{field}"
    return None


def _unkept(x: Any, y: Any) -> str | None:
    """The path of the first assertion of the expected document ``y`` that
    document ``x`` does not imply, or ``None`` when ``x`` asserts everything
    ``y`` does. A key ``y`` holds and ``x`` lacks is itself the path. Keys
    are read as ``compare`` reads them: ``messages``, ``reject_reason_codes``
    (as four-digit texts) and each ``datasets`` or ``file_state`` entry are
    list values, ``screens`` is judged step by step, and ``return_code``, the
    top-level ``matchers`` and any other key as plain data."""
    if not isinstance(x, Mapping) or not isinstance(y, Mapping):
        return _generic_unkept(x, y, "")
    x_top, y_top = _matcher_map(x.get("matchers")), _matcher_map(y.get("matchers"))
    for key, value in y.items():
        where = _key_text(key)
        if key not in x:
            return where
        other = x[key]
        if key == "reject_reason_codes":
            missing = _list_unkept(_reason_texts(other), _reason_texts(value), where, x_top, y_top)
        elif key == "messages":
            missing = _list_unkept(other, value, where, x_top, y_top)
        elif key in ("datasets", "file_state"):
            missing = _section_unkept(other, value, where, x_top, y_top)
        elif key == "screens":
            missing = _screens_unkept(other, value, where, x_top, y_top)
        else:
            missing = _generic_unkept(other, value, where)
        if missing is not None:
            return missing
    return None


def _outcome_change(base: Any, new: Any) -> tuple[int, str]:
    """How the expected document ``new`` changes the outcome ``base``
    asserts, judged by what each asserts rather than by how it is written:
    ``(0, "")`` when each implies the other (``_unkept``), ``(_MINOR, path)``
    when ``new`` keeps every assertion of ``base`` and adds the one at
    ``path``, ``(_MAJOR, path)`` when it changes or drops the assertion of
    ``base`` at ``path``.

    A plain list, ``{records: <list>}`` and ``{count: n, contains: <n
    items>}`` are one exact list, ``102`` is ``'0102'``, a literal that a
    field-level matcher replaces asserts nothing, and an entry-level matcher
    is the same matcher written as the field's value in every record of an
    exact list, so rewriting one spelling as another changes nothing. An
    entry-level matcher moved to an equal top-level matcher is kept, and the
    top-level matcher only adds. An added field, key, dataset, count or
    ``contains`` item only adds, and so does promoting a derived baseline to
    an observed one that keeps every asserted value (``{count: 50, contains:
    [r]}`` to the 50 records, ``r`` among them). An exact list of another
    length, a record added to an exact list, a changed or removed value,
    field, dataset or key, a matcher added over a literal a record names or
    replaced by a literal, and the reverse of a promotion change the
    outcome."""
    lost = _unkept(new, base)
    if lost is not None:
        return _MAJOR, lost
    added = _unkept(base, new)
    return (0, "") if added is None else (_MINOR, added)


def _parsed(reader: _YamlReader, name: str, data: bytes, where: str) -> Any:
    """The YAML artifact ``name`` read ``where`` (at the base, in the working
    tree); ``BaselineError`` naming both when it cannot be read."""
    try:
        return reader.parse(data)
    except BaselineError as exc:
        raise BaselineError(f"{name} {where} cannot be read: {exc}") from None


def _required_bump(
    base_files: Mapping[str, tuple[str, str]],
    work_bytes: Mapping[str, bytes],
    blobs: Mapping[str, bytes],
    reader: _YamlReader,
) -> tuple[int, str, dict, dict]:
    """The lowest bump the change of one baseline needs and why, with its
    base and working manifests. ``work_bytes`` holds the working artifacts'
    content by the names ``base_files`` uses."""
    base_manifest = _parsed(reader, MANIFEST_FILE, blobs[base_files[MANIFEST_FILE][1]], "at the base")
    new_manifest = _parsed(reader, MANIFEST_FILE, work_bytes[MANIFEST_FILE], "in the working tree")
    if not isinstance(base_manifest, Mapping) or not isinstance(new_manifest, Mapping):
        raise BaselineError(f"{MANIFEST_FILE} is not a mapping")
    reasons: list[tuple[int, str]] = []
    base_expected_id = base_files[EXPECTED_FILE][1]
    if _object_id(work_bytes[EXPECTED_FILE], len(base_expected_id)) != base_expected_id:
        change, where = _outcome_change(
            _parsed(reader, EXPECTED_FILE, blobs[base_expected_id], "at the base"),
            _parsed(reader, EXPECTED_FILE, work_bytes[EXPECTED_FILE], "in the working tree"),
        )
        if change == _MAJOR:
            reasons.append((_MAJOR, f"the decoded expected outcome changed at {where}"))
        elif change == _MINOR:
            reasons.append((_MINOR, f"assertions were added at {where}"))
        else:
            reasons.append((_PATCH, f"{EXPECTED_FILE} was rewritten without changing a value"))
    base_raw = {name for name in base_files if name.startswith(DATASETS_DIR + "/")}
    work_raw = {name for name in work_bytes if name.startswith(DATASETS_DIR + "/")}
    for name in sorted(base_raw - work_raw):
        reasons.append((_MAJOR, f"{name} was removed"))
    for name in sorted(work_raw - base_raw):
        reasons.append((_MINOR, f"{name} was added"))
    for name in sorted(base_raw & work_raw):
        oid = base_files[name][1]
        if _object_id(work_bytes[name], len(oid)) != oid:
            reasons.append((_MINOR, f"{name} changed"))
    old_platform, new_platform = base_manifest.get("capture_platform"), new_manifest.get("capture_platform")
    if old_platform != new_platform:
        reasons.append(
            (_MINOR, f"capture_platform changed from {_key_text(old_platform)} to {_key_text(new_platform)}")
        )
    metadata = {key: value for key, value in base_manifest.items() if key != "baseline_version"}
    if not _same(metadata, {key: value for key, value in new_manifest.items() if key != "baseline_version"}):
        reasons.append((_PATCH, f"{MANIFEST_FILE} metadata changed"))
    if not reasons:
        reasons.append((_PATCH, f"{MANIFEST_FILE} changed"))
    level, why = max(reasons, key=lambda item: item[0])
    return level, why, dict(base_manifest), dict(new_manifest)


def _resolved_root(repo_root: str | Path) -> Path:
    """``repo_root`` resolved; ``BumpCheckError`` naming it when it cannot be."""
    try:
        return Path(repo_root).resolve()
    except (OSError, RuntimeError, ValueError) as exc:  # pathlib reports a symbolic link loop as RuntimeError
        shown = display_path(repo_root)
        named = "this checkout" if shown == "." else f"the repository root {shown}"
        if isinstance(exc, RuntimeError):
            reason = "its symbolic links form a loop"
        else:
            reason = getattr(exc, "strerror", None) or str(exc) or type(exc).__name__
        raise BumpCheckError(f"{named} cannot be resolved: {reason}") from None


def _shown_base(base: str, commit: str) -> str:
    """The base revision as the check's messages name it."""
    return base if base == commit else f"{base} ({commit[:12]})"


def _reader_folder(scratch: str | Path | None = None) -> tempfile.TemporaryDirectory:
    """The private temporary folder of a ``_YamlReader``: under ``scratch``
    when given (an existing directory), else under the work root
    ``CARDDEMO_TEST_WORKDIR`` names when ``harness.usable_work_root`` accepts
    it, else the system's."""
    if scratch is None:
        try:
            scratch = usable_work_root(os.environ.get("CARDDEMO_TEST_WORKDIR", ""))
        except HarnessError:
            scratch = None
    return tempfile.TemporaryDirectory(prefix="carddemo-bumps-", dir=scratch)


def _unchanged(base_files: Mapping[str, tuple[str, str]], work_bytes: Mapping[str, bytes]) -> bool:
    """Whether the working artifacts are exactly the base's, file for file."""
    return set(work_bytes) == set(base_files) and all(
        _object_id(data, len(base_files[name][1])) == base_files[name][1] for name, data in work_bytes.items()
    )


def check_bumps(base: str, repo_root: str | Path = REPO_ROOT) -> list[str]:
    """Every baseline version rule the working tree of ``repo_root`` breaks
    against revision ``base``, one ``<id>: <rule>`` text per violation; empty
    when every directory under ``tests/baselines/`` is bumped as its change
    requires (the rules are in the module docstring). An unknown revision, a
    ``repo_root`` that is not the top level of a git work tree, or git
    failing, raises ``BumpCheckError``."""
    root = _resolved_root(repo_root)
    commit = _resolve_commit(base, root)
    at_base = _base_tree(root, commit)
    # The working tree is listed and read under the shared lock, so no write publishes while it is judged.
    with _locked(root / BASELINES_REL, exclusive=False, error=BumpCheckError):
        in_work, problems = _working_tree(root / BASELINES_REL)
        violations = list(problems)
        shown_base = _shown_base(base, commit)
        pending: dict[str, tuple[dict, dict[str, bytes]]] = {}
        wanted: set[str] = set()
        for baseline_id in sorted(set(at_base) | set(in_work)):
            base_files = at_base.get(baseline_id, {})
            work_files = in_work.get(baseline_id)
            if not base_files:
                if work_files:
                    pending[baseline_id] = ({}, {})
                continue
            if work_files is None:
                violations.append(
                    f"{baseline_id}: present at {shown_base} but deleted from the working tree; "
                    f"baselines are never deleted"
                )
                continue
            lost = [name for name in (EXPECTED_FILE, MANIFEST_FILE) if name in base_files and name not in work_files]
            if lost:
                violations.append(f"{baseline_id}: {', '.join(lost)} present at {shown_base} is missing")
                continue
            if MANIFEST_FILE not in base_files:
                # Without its manifest the directory had no baseline version at the
                # base, so there is no version to bump from: it is judged as new.
                pending[baseline_id] = ({}, {})
                continue
            missing = [name for name in (EXPECTED_FILE, MANIFEST_FILE) if name not in base_files]
            if missing:
                violations.append(
                    f"{baseline_id}: {', '.join(missing)} is missing at {shown_base}, so the change cannot be judged"
                )
                continue
            work_bytes = {name: path.read_bytes() for name, path in work_files.items()}
            if _unchanged(base_files, work_bytes):
                continue
            pending[baseline_id] = (base_files, work_bytes)
            wanted.update(oid for name, (_, oid) in base_files.items() if not name.startswith(DATASETS_DIR + "/"))
        blobs = _cat_blobs(root, wanted)
        with _reader_folder() as folder:
            reader = _YamlReader(Path(folder))
            for baseline_id, (base_files, work_bytes) in sorted(pending.items()):
                violations.extend(
                    _judge(baseline_id, base_files, in_work[baseline_id], work_bytes, blobs, reader, shown_base)
                )
    return violations


def _judge(
    baseline_id: str,
    base_files: Mapping[str, tuple[str, str]],
    work_files: Mapping[str, Path],
    work_bytes: Mapping[str, bytes],
    blobs: Mapping[str, bytes],
    reader: _YamlReader,
    shown_base: str,
) -> list[str]:
    """The violations of one new or changed baseline."""
    if not base_files:
        path = work_files.get(MANIFEST_FILE)
        if path is None:
            return [f"{baseline_id}: {MANIFEST_FILE} is missing, so the new baseline has no version"]
        try:
            manifest = reader.parse(path.read_bytes())
            version = manifest.get("baseline_version") if isinstance(manifest, Mapping) else None
            parse_version(version)
        except BaselineError as exc:
            return [f"{baseline_id}: {MANIFEST_FILE} cannot be read: {exc}"]
        if version != NEW_VERSION:
            return [
                f"{baseline_id}: a new baseline (absent at {shown_base}) starts at {NEW_VERSION}, "
                f"found {version}"
            ]
        return []
    try:
        level, why, base_manifest, new_manifest = _required_bump(base_files, work_bytes, blobs, reader)
        old_version = base_manifest.get("baseline_version")
        new_version = new_manifest.get("baseline_version")
        change = version_change(old_version, new_version)
    except BaselineError as exc:
        return [f"{baseline_id}: {exc}"]
    if change == "decrease":
        return [f"{baseline_id}: baseline_version decreased from {old_version} to {new_version}"]
    if _LEVEL_RANK[change] < level:
        return [
            f"{baseline_id}: {why}, which needs a {_RANK_NAME[level]} bump from {old_version} "
            f"(found {new_version})"
        ]
    return []


_LEVEL_NAMES = {rank: name for name, rank in _LEVEL_RANK.items()}


@dataclass(frozen=True)
class BumpNeed:
    """How the bump check judges a baseline against a revision that holds it
    (``bump_needed``). ``base_version`` and ``base_platform`` are that
    revision's ``baseline_version`` and ``capture_platform``; ``level`` is
    the lowest bump the change needs: ``none`` for a byte-identical
    directory, else ``patch``, ``minor`` or ``major``, the levels ``bump``
    takes; ``reason`` is the change that needs it."""

    base_version: str
    base_platform: Any
    level: str
    reason: str

    def allows(self, bump_level: str) -> bool:
        """Whether a bump at ``bump_level`` (``major``, ``minor`` or
        ``patch``) is at least the level the change needs."""
        return _LEVEL_RANK[bump_level] >= _LEVEL_RANK[self.level]


def _artifact_bytes(candidate: Baseline) -> dict[str, bytes]:
    """What ``write`` puts in the baseline directory of ``candidate``, by
    each file's path inside that directory."""
    files = {
        EXPECTED_FILE: dump_yaml(dict(candidate.expected)).encode("utf-8"),
        MANIFEST_FILE: dump_yaml(dict(candidate.manifest)).encode("utf-8"),
    }
    for name, data in candidate.datasets.items():
        files[f"{DATASETS_DIR}/{name}{RAW_SUFFIX}"] = bytes(data)
    return files


def bump_needed(
    candidate: Baseline, base: str = "HEAD", repo_root: str | Path = REPO_ROOT, *, scratch: str | Path | None = None
) -> BumpNeed | None:
    """How ``check_bumps`` against revision ``base`` would judge
    ``tests/baselines/<id>/`` of ``repo_root`` holding exactly the files
    ``write`` makes of ``candidate``. ``None`` when git lists that directory
    at ``base`` without a ``manifest.yaml``, which the check judges as a new
    baseline that must be ``1.0.0``. The base's YAML is parsed in a temporary
    folder, removed before the return, under ``scratch`` when given (an
    existing directory, such as a caller's work root), else where
    ``check_bumps`` puts its own.

    ``BaselineError`` when the change cannot be judged: ``base`` holds the
    manifest but no ``expected.yaml``, or a manifest or version that cannot
    be read. ``BumpCheckError`` when ``base`` is no commit, ``repo_root`` is
    not the top level of a git work tree, or git fails in any way, the
    listing and the blob reads included: a failure is never taken for
    absence."""
    root = _resolved_root(repo_root)
    commit = _resolve_commit(base, root)
    shown_base = _shown_base(base, commit)
    folder = f"{BASELINES_REL}/{candidate.id}"
    base_files = _base_tree(root, commit, folder).get(candidate.id, {})
    if MANIFEST_FILE not in base_files:
        return None
    if EXPECTED_FILE not in base_files:
        raise BaselineError(f"{folder}/{EXPECTED_FILE} is missing at {shown_base}, so the change cannot be judged")
    work_bytes = _artifact_bytes(candidate)
    blobs = _cat_blobs(root, (oid for name, (_, oid) in base_files.items() if not name.startswith(DATASETS_DIR + "/")))
    with _reader_folder(scratch) as reader_folder:
        reader = _YamlReader(Path(reader_folder))
        if _unchanged(base_files, work_bytes):
            level, reason = 0, f"{folder}/ holds exactly what {shown_base} holds"
            manifest_id = base_files[MANIFEST_FILE][1]
            base_manifest = _parsed(reader, MANIFEST_FILE, blobs[manifest_id], f"at {shown_base}")
        else:
            level, reason, base_manifest, _ = _required_bump(base_files, work_bytes, blobs, reader)
    if not isinstance(base_manifest, Mapping):
        raise BaselineError(f"{folder}/{MANIFEST_FILE} at {shown_base} is not a mapping")
    version = base_manifest.get("baseline_version")
    try:
        parse_version(version)
    except BaselineError as exc:
        raise BaselineError(f"{folder}/{MANIFEST_FILE} at {shown_base}: {exc}") from None
    return BumpNeed(version, base_manifest.get("capture_platform"), _LEVEL_NAMES[level], reason)


# --------------------------------------------------------------------------
# Command line
# --------------------------------------------------------------------------

_EPILOG = """\
examples (from the repository root):
  python tests/harness/baselines.py --check-bumps --base HEAD
  python tests/harness/baselines.py --check-bumps --base 93ebec71ff7b59a68cedd7c501afe496a8eaa68d

exit status: 0 when every baseline is bumped as its change requires, 1 with one
'<id>: <rule>' line per violation, 2 when the check cannot run: an unknown
revision, a repository root that is not the top level of a git work tree, or
git failing.
"""


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="baselines.py",
        description="Check baseline version bumps against a prior revision (AAP 0.4.5).",
        epilog=_EPILOG,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--check-bumps", action="store_true", help="compare tests/baselines/ with the same folder at --base"
    )
    parser.add_argument("--base", metavar="REVISION", help="the prior revision (commit, branch or tag)")
    parser.add_argument(
        "--repo-root", metavar="PATH", default=str(REPO_ROOT), help="the repository to check (default: this one)"
    )
    args = parser.parse_args(argv)
    if not args.check_bumps:
        parser.error("nothing to do: give --check-bumps --base <revision>")
    if args.base is None:
        parser.error("--check-bumps needs --base <revision>")
    try:
        violations = check_bumps(args.base, args.repo_root)
    except BumpCheckError as exc:
        print(f"baselines.py: {exc}", file=sys.stderr)
        return 2
    for violation in violations:
        print(violation)
    if violations:
        print(f"baselines.py: {len(violations)} bump violation(s) against {args.base}", file=sys.stderr)
        return 1
    print(f"baselines.py: every baseline is bumped as its change requires against {args.base}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
