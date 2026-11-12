"""CardDemo characterization harness: shared constants and helpers.

The harness is Python 3.11 with the standard library and PyYAML only. It makes
no network calls, keeps no state between scenarios, and writes deterministic
output: canonical YAML, repository-relative paths, no floats.

Under ``pytest tests/`` the rootdir is ``tests/`` and ``pythonpath = .``, so
this package imports as ``harness`` and the adapters as ``adapters``. A script
run as ``python tests/harness/<name>.py`` starts with ``tests/harness`` on
``sys.path`` instead, so it must make ``tests/`` importable *before* it imports
this package (``bootstrap_path`` cannot be reached until then)::

    import sys
    from pathlib import Path

    _TESTS_ROOT = str(Path(__file__).resolve().parents[1])
    if _TESTS_ROOT not in sys.path:
        sys.path.insert(0, _TESTS_ROOT)

    import harness  # noqa: E402

Importing this package computes paths only: it performs no I/O, reads no
environment variable, and imports neither the harness submodules nor
``adapters``, so any submodule can import it without a cycle.
"""

from __future__ import annotations

import decimal
import errno
import fcntl
import functools
import hashlib
import io
import os
import re
import reprlib
import stat
import subprocess
import sys
import tempfile
import time
from datetime import date, datetime, timezone
from pathlib import Path, PurePath, PureWindowsPath
from typing import Any, Callable

import yaml

__all__ = [
    "ADAPTERS_DIR",
    "ADAPTER_NAMES",
    "BASELINES_DIR",
    "DEADLINE_S",
    "DEFAULT_CLOCK",
    "DIRECTORY_OPEN_FLAGS",
    "FIXTURES_FILE",
    "HarnessError",
    "INVENTORY_DIR",
    "MARKER_FOR_ADAPTER",
    "PROTECTED_BASE",
    "REPORTS_DIR",
    "REPO_ROOT",
    "RESULTS_DIR",
    "RUNTIME_DIR",
    "SCENARIOS_DIR",
    "TESTS_ROOT",
    "UNIKIX_EVIDENCE_DIR",
    "WORK_ROOT_LOCK_NAME",
    "WorkRootBusy",
    "WorkRootLock",
    "YAMLLoadError",
    "YAMLStructureError",
    "YAML_MAX_ALIAS_WEIGHT",
    "YAML_MAX_DEPTH",
    "bootstrap_path",
    "check_work_root_owner",
    "check_work_root_path",
    "cob_current_date",
    "display_path",
    "dump_yaml",
    "git_head",
    "load_yaml",
    "lock_work_root",
    "open_directory",
    "relpath",
    "sha256_file",
    "sha256_hex",
    "usable_work_root",
    "write_text",
]

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

TESTS_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = TESTS_ROOT.parent

# The commit the suite started from: the inventory header's `protected_base`,
# the base of the protected-tree check, and the bump-check fallback base.
PROTECTED_BASE = "93ebec71ff7b59a68cedd7c501afe496a8eaa68d"

# Per-scenario deadline in seconds. A scenario cannot raise it.
DEADLINE_S = 300

# Default run clock, inside the TRANREPT INCLUDE window 2022-01-01..2022-07-06.
# The hundredths are mandatory: without them libcob lets them follow the real
# clock and runs stop being reproducible.
DEFAULT_CLOCK = "2022-07-06 10:00:00.00"

ADAPTER_NAMES = ("gnucobol-batch", "unikix")
# pytest marker per adapter, as registered in tests/pytest.ini.
MARKER_FOR_ADAPTER = {"gnucobol-batch": "gnucobol_batch", "unikix": "unikix"}

INVENTORY_DIR = TESTS_ROOT / "inventory"
SCENARIOS_DIR = TESTS_ROOT / "scenarios"
BASELINES_DIR = TESTS_ROOT / "baselines"
REPORTS_DIR = TESTS_ROOT / "reports"
RESULTS_DIR = REPORTS_DIR / "results"
UNIKIX_EVIDENCE_DIR = REPORTS_DIR / "unikix"
RUNTIME_DIR = REPORTS_DIR / "runtime"  # git-ignored; outside the projection
FIXTURES_FILE = TESTS_ROOT / "fixtures" / "datasets.yaml"
ADAPTERS_DIR = TESTS_ROOT / "adapters"

# Bounds on what a YAML file may make the harness build, far above the
# committed files (at most 10 nested collections; aliases add at most 25,050,
# in DAT-CBCUS01C-recovery-03) and far below what exhausts memory, the C
# stack or the recursion limit: without them a few hundred bytes of nested
# aliases expand into gigabytes. YAML_MAX_ALIAS_WEIGHT bounds what aliases
# add beyond what the file spells out, where a scalar weighs 1 + its length
# and a collection 1 + the weights of its children.
YAML_MAX_DEPTH = 100
YAML_MAX_ALIAS_WEIGHT = 100_000


class HarnessError(Exception):
    """An error the harness itself detects: schema, fixture, cross-check,
    configuration. Harness modules subclass it for their own error kinds."""


class YAMLLoadError(HarnessError, yaml.YAMLError):
    """A YAML file ``load_yaml`` cannot load: it is not UTF-8 text or not
    valid YAML. ``path`` is the file as ``display_path`` shows it and
    ``problem`` what is wrong with it; the message is ``<path>: <problem>``.
    It is also a ``yaml.YAMLError``, so a caller that catches PyYAML's errors
    still catches it."""

    def __init__(self, path: str, problem: str) -> None:
        super().__init__(f"{path}: {problem}")
        self.path = path
        self.problem = problem


class YAMLStructureError(YAMLLoadError):
    """Valid YAML whose structure the harness refuses: a mapping key given
    twice, an alias to a collection that contains it, aliases that add more
    than ``YAML_MAX_ALIAS_WEIGHT``, or nesting deeper than ``YAML_MAX_DEPTH``."""


# --------------------------------------------------------------------------
# Clock
# --------------------------------------------------------------------------

_CLOCK_RE = re.compile(
    r"([0-9]{4})-([0-9]{2})-([0-9]{2}) ([0-9]{2}):([0-9]{2}):([0-9]{2})\.([0-9]{2})"
)
_FRACTION_MODES = ("hundredths", "nanoseconds")


def cob_current_date(clock: str, fraction_mode: str = "hundredths") -> str:
    """Convert a scenario clock ``YYYY-MM-DD HH:MM:SS.hh`` to the
    ``COB_CURRENT_DATE`` form ``YYYY/MM/DD HH:MM:SS.hh``.

    ``fraction_mode="nanoseconds"`` appends seven zeros to the hundredths, for
    a libcob that reads the fraction as nanoseconds (where ``.37`` would yield
    hundredths 00 and ``.370000000`` yields 37).
    """
    if not isinstance(clock, str):
        raise HarnessError(
            f"clock must be a string 'YYYY-MM-DD HH:MM:SS.hh', got "
            f"{type(clock).__name__} {clock!r}; quote it in YAML"
        )
    match = _CLOCK_RE.fullmatch(clock)
    if match is None:
        raise HarnessError(
            f"clock {clock!r} is not 'YYYY-MM-DD HH:MM:SS.hh'; the two digits of "
            f"hundredths are mandatory"
        )
    try:
        datetime.strptime(clock[:19], "%Y-%m-%d %H:%M:%S")
    except ValueError as exc:
        raise HarnessError(f"clock {clock!r} is not a valid date and time: {exc}") from None
    if fraction_mode not in _FRACTION_MODES:
        raise HarnessError(
            f"fraction_mode {fraction_mode!r} is not one of {', '.join(_FRACTION_MODES)}"
        )
    year, month, day, hour, minute, second, hundredths = match.groups()
    value = f"{year}/{month}/{day} {hour}:{minute}:{second}.{hundredths}"
    return value + "0000000" if fraction_mode == "nanoseconds" else value


# --------------------------------------------------------------------------
# Canonical YAML
# --------------------------------------------------------------------------


_SURROGATE_RE = re.compile("[\ud800-\udfff]")


def _float_error(data: float) -> HarnessError:
    return HarnessError(
        f"float {data!r} cannot be written: numbers are Decimal values carried "
        f"as strings with their scale, never floats"
    )


def _canonical_order(value: Any) -> tuple:
    """Sort key placing any mapping key or set element in one total order.

    A type rank comes first, then the value in its type's own order: ``None``;
    numbers (``bool``, ``int`` and ``Decimal`` together, by numeric value);
    ``str``; paths (POSIX flavour, then Windows, each in pathlib order);
    ``bytes``; dates; naive datetimes; aware datetimes (by UTC instant); tuples
    (element by element); frozensets (by their sorted elements). Distinct
    values never tie, so a sort by this key depends on the values alone, never
    on insertion or hash order. Numbers, strings, paths, bytes, dates and naive
    datetimes keep the order ``sorted`` gives them.

    A float, a ``Decimal`` NaN, an aware datetime whose UTC offset depends on
    its fold, and a value of any other type raise ``HarnessError``.
    """
    if value is None:
        return (0,)
    if isinstance(value, float):
        raise _float_error(value)
    if isinstance(value, (int, decimal.Decimal)):
        if isinstance(value, decimal.Decimal) and value.is_nan():
            raise HarnessError(
                f"Decimal {value} cannot be a mapping key or set element: NaN has no order"
            )
        return (1, value)
    if isinstance(value, str):
        return (2, value)
    if isinstance(value, PurePath):
        # Paths compare only within their flavour, and a POSIX and a Windows
        # path never compare equal, even with the same text.
        return (3, isinstance(value, PureWindowsPath), value)
    if isinstance(value, bytes):
        return (4, value)
    if isinstance(value, datetime):
        offset = value.utcoffset()
        if offset is None:
            return (6, 0, value)
        # Naive and aware datetimes cannot be compared; aware ones order by
        # UTC instant, then offset. A wall time whose offset changes with its
        # fold (in a DST fold or gap) is unequal to the same instant in any
        # other zone (PEP 495), so it would tie with a value it does not equal.
        if value.replace(fold=1 - value.fold).utcoffset() != offset:
            raise HarnessError(
                f"datetime {value} cannot be a mapping key or set element: its UTC "
                f"offset depends on its fold, so it has no canonical order"
            )
        instant = value.replace(tzinfo=None) - datetime.min - offset
        return (6, 1, instant, offset)
    if isinstance(value, date):
        return (5, value)
    if isinstance(value, tuple):
        return (7, tuple(_canonical_order(item) for item in value))
    if isinstance(value, (set, frozenset)):
        return (8, tuple(sorted(_canonical_order(item) for item in value)))
    raise HarnessError(
        f"{type(value).__name__} {value!r} cannot be a mapping key or set element: "
        f"it has no canonical order"
    )


def _node_signature(node: yaml.Node) -> tuple:
    """Identity of a represented node as a YAML mapping key.

    Two keys are the same YAML key when their nodes are equal: a scalar by tag
    and value, a sequence by tag and its items in order, a mapping by tag and
    its set of pairs. Style and emitted text play no part, so ``True`` (tag
    bool) and ``'true'`` (tag str), or ``1`` and ``'1'``, stay distinct.
    """
    if isinstance(node, yaml.ScalarNode):
        return ("scalar", node.tag, node.value)
    if isinstance(node, yaml.SequenceNode):
        return ("sequence", node.tag, tuple(_node_signature(item) for item in node.value))
    return (
        "mapping",
        node.tag,
        frozenset((_node_signature(key), _node_signature(value)) for key, value in node.value),
    )


class _CanonicalDumper(yaml.SafeDumper):
    """SafeDumper whose output depends only on the value it is given."""

    def ignore_aliases(self, data: Any) -> bool:
        # Anchors would make the text depend on object identity: the same value
        # built from shared or from copied objects would dump differently, and
        # digests and fingerprints taken over dump_yaml would change with it.
        return True

    def represent_mapping(
        self, tag: str, mapping: Any, flow_style: bool | None = None
    ) -> yaml.MappingNode:
        # PyYAML sorts the items with sorted() and keeps insertion order when
        # that raises TypeError (keys of mixed types, or None), and frozenset
        # keys, which compare by subset, stay in hash order. It writes a list
        # of pairs as given, so the canonical order is applied here instead.
        if self.sort_keys and hasattr(mapping, "items"):
            mapping = sorted(mapping.items(), key=lambda item: _canonical_order(item[0]))
        items = list(mapping.items() if hasattr(mapping, "items") else mapping)
        node = super().represent_mapping(tag, items, flow_style)
        # Distinct keys can still become equal nodes: a str and a Decimal or a
        # path with the same text, a POSIX and a Windows path with the same
        # as_posix(), a tuple and a frozenset of the same items. YAML requires
        # unique keys and the loader keeps only the last value, so a collision
        # is refused rather than written.
        seen: dict[tuple, Any] = {}
        for (key, _), (key_node, _) in zip(items, node.value, strict=True):
            signature = _node_signature(key_node)
            if signature in seen:
                first = seen[signature]
                raise HarnessError(
                    f"mapping keys {first!r} ({type(first).__name__}) and {key!r} "
                    f"({type(key).__name__}) would be written as the same YAML key; "
                    f"keys must be unique, and loading would keep only one value"
                )
            seen[signature] = key
        return node

    def represent_str(self, data: str) -> yaml.ScalarNode:
        if _SURROGATE_RE.search(data):
            # A lone surrogate is no Unicode character: the escaped text could be
            # written, but libyaml refuses to load it back.
            raise HarnessError(f"string {data!r} holds a lone surrogate and cannot be written")
        if "\x85" in data:
            # PyYAML emits NEL (x'85', cp037 x'15') raw inside quoted scalars,
            # where every loader reads it as a line break. Double quotes escape
            # it as \N, so the character survives the round trip.
            return self.represent_scalar("tag:yaml.org,2002:str", data, style='"')
        return super().represent_str(data)


def _represent_float(dumper: yaml.SafeDumper, data: float) -> yaml.Node:
    raise _float_error(data)


def _represent_decimal(dumper: yaml.SafeDumper, data: decimal.Decimal) -> yaml.Node:
    # represent_str quotes text that would load back as a number, so the
    # value returns as the same string with its scale ("504.70", not 504.7).
    return dumper.represent_str(str(data))


def _represent_path(dumper: yaml.SafeDumper, data: PurePath) -> yaml.Node:
    return dumper.represent_str(data.as_posix())


def _represent_set(dumper: yaml.SafeDumper, data: set | frozenset) -> yaml.Node:
    return dumper.represent_list(sorted(data, key=_canonical_order))


_CanonicalDumper.add_representer(str, _CanonicalDumper.represent_str)
_CanonicalDumper.add_representer(float, _represent_float)
_CanonicalDumper.add_multi_representer(float, _represent_float)
_CanonicalDumper.add_representer(decimal.Decimal, _represent_decimal)
_CanonicalDumper.add_multi_representer(PurePath, _represent_path)
_CanonicalDumper.add_representer(set, _represent_set)
_CanonicalDumper.add_representer(frozenset, _represent_set)
_CanonicalDumper.add_multi_representer(dict, yaml.SafeDumper.represent_dict)
_CanonicalDumper.add_multi_representer(list, yaml.SafeDumper.represent_list)


class _DeadlineDumper(_CanonicalDumper):
    """_CanonicalDumper that reads the clock before it represents each node,
    after it writes each scalar and after each event it emits, and raises
    ``TimeoutError`` once ``deadline``, a ``time.monotonic()`` value, has
    passed. The emitter finishes each scalar, whatever its length, in
    ``process_scalar``, so a reading follows each scalar's text at once and
    no two scalars are written between two readings. A reading costs far
    less than writing a node. The checks only read the clock, so its text is
    _CanonicalDumper's."""

    def __init__(self, stream: Any, *, deadline: float, **options: Any) -> None:
        super().__init__(stream, **options)
        self._deadline = deadline

    def _check(self) -> None:
        if time.monotonic() >= self._deadline:
            raise TimeoutError("the deadline passed while the YAML text was being written")

    def represent_data(self, data: Any) -> yaml.Node:
        self._check()
        return super().represent_data(data)

    def process_scalar(self) -> None:
        super().process_scalar()
        self._check()

    def emit(self, event: yaml.Event) -> None:
        super().emit(event)
        self._check()


# The C parser is a faster drop-in with the same safe constructors and
# resolvers; the pure-Python one is used where libyaml is absent.
_SafeLoader = getattr(yaml, "CSafeLoader", yaml.SafeLoader)


def dump_yaml(obj: Any, *, deadline: float | None = None) -> str:
    """Serialize ``obj`` as canonical YAML: sorted keys, block style, no line
    folding, ``\\n`` line breaks, no anchors.

    Mapping keys and set elements sort in one order across types: ``None``,
    numbers (``bool``, ``int`` and ``Decimal`` by value), ``str``, paths,
    ``bytes``, dates, naive then aware datetimes, tuples, frozensets, each
    type in its own order (aware datetimes by UTC instant, tuples element by
    element, frozensets by their sorted elements). The text therefore depends
    on the value alone, never on insertion or hash order. A key or element
    that is a ``Decimal`` NaN, an aware datetime in a DST fold or gap, or of
    any other type raises ``HarnessError``, as do two keys of one mapping
    written as the same YAML key, such as ``'1.5'`` and ``Decimal('1.5')``.

    ``Decimal`` becomes its string, paths their POSIX string, sets a sorted
    list, tuples a list in order. A float anywhere raises ``HarnessError``,
    and so does an integer too long to write in decimal under Python's
    integer conversion limit (``sys.get_int_max_str_digits()`` digits).
    Strings, including trailing spaces and control characters, load back
    unchanged.

    ``deadline``, a ``time.monotonic()`` value (``None``, the default, sets
    none), bounds the writing: the clock is read before it starts, at every
    node and after every scalar while it runs (``_DeadlineDumper``), and once
    the text is complete, and the builtin ``TimeoutError`` is raised once the
    deadline has passed, so text completed after it is never returned. The
    text is the same with a deadline as without one.
    """
    dumper: Any = _CanonicalDumper
    if deadline is not None:
        if isinstance(deadline, bool) or not isinstance(deadline, (int, float)):
            raise TypeError(f"a deadline is a time.monotonic() value or None, not {type(deadline).__name__}")
        if time.monotonic() >= deadline:
            raise TimeoutError("the deadline passed before the YAML text was written")
        dumper = functools.partial(_DeadlineDumper, deadline=deadline)
    try:
        text = yaml.dump(
            obj,
            Dumper=dumper,
            sort_keys=True,
            default_flow_style=False,
            allow_unicode=True,
            width=2**31 - 1,
            line_break="\n",
        )
    except yaml.YAMLError as exc:
        raise HarnessError(f"cannot write value as YAML: {exc}") from exc
    except ValueError as exc:
        # PyYAML writes an int with str(), which refuses one longer than the
        # integer conversion limit; a hex or octal YAML literal loads one.
        raise HarnessError(f"cannot write value as YAML: {exc}") from exc
    except RecursionError:
        # Anchors are never written (ignore_aliases), so a value that contains
        # itself recurses without end; load_yaml never builds one.
        raise HarnessError(
            "cannot write value as YAML: it nests too deeply or refers to itself"
        ) from None
    if deadline is not None and time.monotonic() >= deadline:
        raise TimeoutError("the deadline passed while the YAML text was being written")
    return text


def load_yaml(path: str | PurePath, missing_ok: bool = False, *, max_bytes: int | None = None) -> Any:
    """Load a UTF-8 YAML file with safe-load semantics.

    A missing file returns ``None`` when ``missing_ok`` is true and raises
    ``FileNotFoundError`` otherwise; an empty document returns ``None``.

    ``max_bytes``, when given, is the most bytes the file may hold: at most
    one byte more is read, and a longer file raises ``YAMLLoadError`` before
    anything is decoded or parsed, since loading costs many times the file's
    size in memory. It must be a non-negative ``int`` (``TypeError`` or
    ``ValueError`` otherwise); ``None`` reads the whole file.

    A file that is not UTF-8 text, not valid YAML, or holds a value the safe
    constructors cannot build (an impossible date, a ``!!int`` that is not
    an integer, a ``!!bool`` that is not a boolean) raises ``YAMLLoadError``,
    whose message is one line that names the file once, as ``display_path``
    shows it, and the line and column where they are known (PyYAML's own
    multi-line text, which repeats the file in every mark, is not used).
    Valid YAML whose structure would be
    unsafe to build or to check raises ``YAMLStructureError``: a mapping key
    given twice (YAML requires unique keys; the last one would silently win),
    an alias to a collection that contains it, collections nested deeper than
    ``YAML_MAX_DEPTH``, or aliases that add more than ``YAML_MAX_ALIAS_WEIGHT``
    to the document. The nesting is checked on the parser's events before
    anything is composed, and the rest on the composed node graph before any
    value is constructed, so no file can make the loader recurse without
    bound or expand a few bytes into gigabytes.
    """
    if max_bytes is not None:
        if isinstance(max_bytes, bool) or not isinstance(max_bytes, int):
            raise TypeError(f"max_bytes must be an int or None, got {type(max_bytes).__name__}")
        if max_bytes < 0:
            raise ValueError(f"max_bytes must not be negative, got {max_bytes}")
    file_path = Path(path)
    try:
        if max_bytes is None:
            data = file_path.read_bytes()
        else:
            with open(file_path, "rb") as handle:
                data = handle.read(max_bytes + 1)
    except FileNotFoundError:
        if missing_ok:
            return None
        raise
    shown = display_path(file_path)
    if max_bytes is not None and len(data) > max_bytes:
        raise YAMLLoadError(shown, f"holds more than {max_bytes} bytes, the most this file may hold")
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise YAMLLoadError(shown, f"not UTF-8 text: {_undecodable(data, exc.start)}") from None
    try:
        _check_written_depth(text, shown)
        loader = _loader(text, shown)
        loader.construct_object = _naming_constructor(loader, shown)
        try:
            node = loader.get_single_node()
            if node is None:
                return None
            _check_graph(loader, node, shown)
            try:
                return loader.construct_document(node)
            except _UNBUILDABLE as exc:  # raised outside every construct_object call
                raise _unbuildable(shown, None, exc) from None
        finally:
            del loader.construct_object  # the wrapper refers back to the loader
            loader.dispose()
    except YAMLLoadError:
        raise
    except yaml.YAMLError as exc:
        raise YAMLLoadError(shown, f"not valid YAML: {_yaml_problem(exc, text)}") from None
    except RecursionError:
        # Only the pure-Python composer recurses, once per level, and only
        # after the written depth is bounded: a caller already near the
        # recursion limit is the one case left.
        raise YAMLStructureError(
            shown, f"nests too deeply to load (the limit is {YAML_MAX_DEPTH} levels)"
        ) from None


_MERGE_TAG = "tag:yaml.org,2002:merge"
# A plain '=' key; flatten_mapping retags it as the string '=' when it builds
# the mapping, so it is compared as that string.
_VALUE_TAG = "tag:yaml.org,2002:value"
_COLLECTION_STARTS = (yaml.MappingStartEvent, yaml.SequenceStartEvent)
_COLLECTION_ENDS = (yaml.MappingEndEvent, yaml.SequenceEndEvent)
_KEY_REPR = reprlib.Repr()
_KEY_REPR.maxstring = _KEY_REPR.maxother = 80
# What PyYAML's safe constructors raise, rather than a YAMLError, on a value
# they cannot build: an impossible date or time, an integer longer than
# Python's digit limit, or a !!int, !!float, !!bool or !!timestamp value that
# is not one. Their messages are clipped: float() quotes its whole input.
_UNBUILDABLE = (ValueError, KeyError, AttributeError, IndexError)
_UNBUILDABLE_DETAIL_MAX = 200


def _undecodable(data: bytes, start: int) -> str:
    """Where the first byte that is not UTF-8 lies: line and character column,
    both 1-based."""
    line_start = data.rfind(b"\n", 0, start) + 1
    line = data.count(b"\n", 0, start) + 1
    column = len(data[line_start:start].decode("utf-8", errors="replace")) + 1
    return f"byte x'{data[start]:02x}' at line {line}, column {column}"


def _yaml_problem(exc: yaml.YAMLError, text: str) -> str:
    """PyYAML's error ``exc`` on one line, without the file name its marks
    repeat, because the ``YAMLLoadError`` prefix names the file once.

    A marked error reads: the context and where it began, then the problem
    and where it was found, then the note, separated by ': ', each with its
    whitespace collapsed. As in PyYAML's own text, the context's place is
    left out when it is the problem's. A character the reader refuses (one
    ``text`` holds) is placed at its line and column. Any other error is its
    own text with the whitespace collapsed."""
    if isinstance(exc, yaml.MarkedYAMLError):
        context_mark, problem_mark = exc.context_mark, exc.problem_mark
        if (
            context_mark is not None
            and exc.problem is not None
            and problem_mark is not None
            and (context_mark.name, context_mark.line, context_mark.column)
            == (problem_mark.name, problem_mark.line, problem_mark.column)
        ):
            context_mark = None
        parts = []
        placed = ((exc.context, context_mark), (exc.problem, problem_mark), (exc.note, None))
        for words, mark in placed:
            part = " ".join(str(words).split()) if words is not None else ""
            if mark is not None:
                part = f"{part} at line {mark.line + 1}, column {mark.column + 1}".lstrip()
            if part:
                parts.append(part)
        if parts:
            return ": ".join(parts)
    elif isinstance(exc, yaml.reader.ReaderError) and isinstance(exc.character, int):
        reason = " ".join(str(exc.reason).split())
        where = _reader_where(exc, text)
        return f"unacceptable character #x{exc.character:04x}: {reason} at {where}"
    return " ".join(str(exc).split()) or type(exc).__name__


def _reader_where(exc: yaml.reader.ReaderError, text: str) -> str:
    """The line and character column, both 1-based, of the character a
    ``ReaderError`` refuses in ``text``. libyaml counts its position in bytes
    of the UTF-8 text, the pure-Python reader in characters."""
    index = exc.position
    if _SafeLoader is not yaml.SafeLoader:
        index = len(text.encode("utf-8")[:index].decode("utf-8", errors="replace"))
    line_start = text.rfind("\n", 0, index) + 1
    line = text.count("\n", 0, index) + 1
    return f"line {line}, column {index - line_start + 1}"


def _loader(text: str, shown: str) -> Any:
    # Both the C and the pure-Python parser take a stream's name for their
    # marks, so PyYAML's own messages name the file, not "<unicode string>".
    stream = io.StringIO(text)
    stream.name = shown
    return _SafeLoader(stream)


def _where(node: Any) -> str:
    mark = node.start_mark
    return f"line {mark.line + 1}, column {mark.column + 1}"


def _unbuildable(shown: str, node: Any, exc: BaseException) -> YAMLLoadError:
    """The error for a value the safe constructors refused with ``exc``,
    placed at ``node``'s line and column when the node and its mark are known."""
    detail = " ".join(str(exc).split())
    if len(detail) > _UNBUILDABLE_DETAIL_MAX:
        detail = detail[: _UNBUILDABLE_DETAIL_MAX - 3] + "..."
    if getattr(node, "start_mark", None) is None:
        what = "a value"
    else:
        what = f"the value at {_where(node)}"
    return YAMLLoadError(
        shown, f"not valid YAML: {what} cannot be constructed ({type(exc).__name__}: {detail})"
    )


def _naming_constructor(loader: Any, shown: str) -> Callable[..., Any]:
    """A ``construct_object`` for ``loader``'s instance that raises
    ``YAMLLoadError`` naming the file and the node for a value the safe
    constructors refuse. They build nested values through
    ``self.construct_object``, so every node passes through it and the
    innermost failing one is reported; the ``YAMLLoadError`` is not one of
    ``_UNBUILDABLE``, so the enclosing calls pass it on unchanged."""
    construct = loader.construct_object

    def construct_object(node: yaml.Node, deep: bool = False) -> Any:
        try:
            return construct(node, deep=deep)
        except _UNBUILDABLE as exc:
            raise _unbuildable(shown, node, exc) from None

    return construct_object


def _check_written_depth(text: str, shown: str) -> None:
    """Refuse collections nested deeper than ``YAML_MAX_DEPTH`` as written.

    The parsers are iterative, but libyaml's composer recurses on the C stack:
    100,000 nested '[' (200 KB) crash the process before any check of the
    composed graph could run. So the depth is counted from the parser's
    events, and parsing stops at the first level too deep."""
    loader = _loader(text, shown)
    try:
        depth = 0
        while True:
            event = loader.get_event()
            if event is None or isinstance(event, yaml.StreamEndEvent):
                return
            if isinstance(event, _COLLECTION_STARTS):
                depth += 1
                if depth > YAML_MAX_DEPTH:
                    raise YAMLStructureError(
                        shown, f"nests deeper than {YAML_MAX_DEPTH} levels at {_where(event)}"
                    )
            elif isinstance(event, _COLLECTION_ENDS):
                depth -= 1
    finally:
        loader.dispose()


def _children(node: Any) -> list:
    if isinstance(node, yaml.MappingNode):
        return [child for pair in node.value for child in pair]
    if isinstance(node, yaml.SequenceNode):
        return list(node.value)
    return []


def _check_unique_keys(loader: Any, node: yaml.MappingNode, shown: str) -> None:
    """Refuse a scalar key given twice in one mapping, compared as the keys of
    the built dict compare (``1`` and ``0x1``, ``true`` and ``yes`` collide).
    The merge key ``<<`` is a key too: given twice, the later merge would
    silently replace the earlier one's values. The entries it merges may be
    overridden by explicit keys, so they are not compared; a quoted ``'<<'``
    is a plain string key."""
    first: dict[Any, int] = {}
    first_merge: int | None = None
    for key_node, _ in node.value:
        if key_node.tag == _MERGE_TAG:
            # flatten_mapping merges every key with this tag, whatever its kind.
            line = key_node.start_mark.line + 1
            if first_merge is not None:
                raise YAMLStructureError(
                    shown,
                    f"found duplicate key {_KEY_REPR.repr('<<')} at line {line} "
                    f"(first given at line {first_merge})",
                )
            first_merge = line
            continue
        if not isinstance(key_node, yaml.ScalarNode):
            continue
        if key_node.tag == _VALUE_TAG:
            key = key_node.value
        else:
            key = loader.construct_object(key_node, deep=True)
        line = key_node.start_mark.line + 1
        try:
            earlier = first.get(key)
        except TypeError:  # an unhashable key, which construct_mapping refuses
            continue
        if earlier is not None:
            try:
                shown_key = _KEY_REPR.repr(key)
            except ValueError:  # an integer too long to write in decimal
                shown_key = (
                    f"{_KEY_REPR.repr(key_node.value)} (an integer too long to show in decimal)"
                )
            raise YAMLStructureError(
                shown,
                f"found duplicate key {shown_key} at line {line} "
                f"(first given at line {earlier})",
            )
        first[key] = line


def _check_graph(loader: Any, root: Any, shown: str) -> None:
    """Check the composed node graph without recursion, before any value is
    built: unique mapping keys, no collection that contains an alias to
    itself, at most ``YAML_MAX_DEPTH`` nested collections with aliases
    expanded, and at most ``YAML_MAX_ALIAS_WEIGHT`` added by aliases.

    The walk is depth-first with an explicit stack; ``active`` holds the
    collections on the current path, and each node's height and expanded
    weight are computed once, so shared (aliased) nodes cost nothing more.
    A stack entry carries a collection's children once it has been entered,
    so its next appearance finishes it."""
    if isinstance(root, yaml.ScalarNode):
        return
    height: dict[int, int] = {}  # node -> collections nested in it, itself included
    weight: dict[int, int] = {}  # node -> its weight with every alias expanded
    written = 0  # the weight of every node counted once: what the file spells out
    active: set[int] = set()
    stack: list[tuple[Any, list | None]] = [(root, None)]
    while stack:
        node, children = stack.pop()
        key = id(node)
        if children is not None:
            active.discard(key)
            height[key] = 1 + max((height[id(child)] for child in children), default=0)
            if height[key] > YAML_MAX_DEPTH:
                raise YAMLStructureError(
                    shown, f"nests deeper than {YAML_MAX_DEPTH} levels at {_where(node)}"
                )
            weight[key] = 1 + sum(weight[id(child)] for child in children)
            continue
        if key in height:
            continue
        if isinstance(node, yaml.MappingNode):
            _check_unique_keys(loader, node, shown)
        written += 1
        active.add(key)
        if len(active) > YAML_MAX_DEPTH:
            raise YAMLStructureError(
                shown, f"nests deeper than {YAML_MAX_DEPTH} levels at {_where(node)}"
            )
        children = _children(node)
        stack.append((node, children))
        for child in reversed(children):
            child_key = id(child)
            if child_key in height:
                continue
            if isinstance(child, yaml.ScalarNode):
                height[child_key] = 0
                weight[child_key] = 1 + len(child.value)
                written += weight[child_key]
            elif child_key in active:
                kind = "mapping" if isinstance(child, yaml.MappingNode) else "sequence"
                raise YAMLStructureError(
                    shown,
                    f"recursive alias: the {kind} at {_where(child)} contains an alias to itself",
                )
            else:
                stack.append((child, None))
    added = weight[id(root)] - written
    if added > YAML_MAX_ALIAS_WEIGHT:
        raise YAMLStructureError(
            shown,
            f"aliases expand the document by {added} (nodes and characters); "
            f"at most {YAML_MAX_ALIAS_WEIGHT} are allowed",
        )


# --------------------------------------------------------------------------
# Files, hashes, git, paths
# --------------------------------------------------------------------------


def write_text(path: str | PurePath, text: str) -> bool:
    """Write ``text`` as UTF-8 with ``\\n`` line endings, creating parent
    directories. An identical file is left untouched, so its mtime stays
    stable. Returns whether the file was written.

    The write is atomic: a symlink is followed to the file it resolves to,
    the text goes to a new file beside that target, and ``os.replace`` swaps
    it in, so a concurrent reader sees the old or the new content, never a
    partial one. An existing target keeps its permission bits; a new one gets
    the mode the umask allows. The new file is removed when anything fails,
    and ``OSError`` (a directory in the way, no permission) propagates."""
    if not isinstance(text, str):
        raise TypeError(f"text must be str, not {type(text).__name__}")
    # Path.resolve raises RuntimeError on a symlink loop; realpath returns the
    # loop unresolved, so the read below reports it as an OSError.
    file_path = Path(os.path.realpath(path))
    data = text.encode("utf-8")
    try:
        unchanged = file_path.read_bytes() == data
    except FileNotFoundError:
        unchanged = False
    if unchanged:
        return False
    file_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        mode: int | None = stat.S_IMODE(file_path.stat().st_mode)
    except FileNotFoundError:
        mode = None
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    while True:
        unique = f"{os.getpid()}.{os.urandom(4).hex()}"
        temp_path = file_path.with_name(f".{file_path.name}.{unique}.tmp")
        try:
            descriptor = os.open(temp_path, flags, 0o666)
            break
        except FileExistsError:
            continue
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(data)
        if mode is not None:
            os.chmod(temp_path, mode)
        os.replace(temp_path, file_path)
    except BaseException:
        try:
            os.unlink(temp_path)
        except FileNotFoundError:
            pass
        raise
    return True


def sha256_hex(data: bytes | bytearray | memoryview | str) -> str:
    """Hex sha256 of bytes, or of a string encoded as UTF-8."""
    if isinstance(data, str):
        data = data.encode("utf-8")
    elif not isinstance(data, (bytes, bytearray, memoryview)):
        raise TypeError(f"cannot hash {type(data).__name__}; pass bytes or str")
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: str | PurePath, chunk_size: int = 1 << 20) -> str:
    """Hex sha256 of a file's contents, read in chunks of ``chunk_size`` bytes.

    ``chunk_size`` must be a positive integer. A bool or any other non-int
    raises ``TypeError`` and a value of zero or less raises ``ValueError``,
    both before the file is opened, since a zero-byte read would end the loop
    at once and hash a nonempty file as empty input.
    """
    if isinstance(chunk_size, bool) or not isinstance(chunk_size, int):
        raise TypeError(
            f"chunk_size must be a positive int, not {type(chunk_size).__name__} {chunk_size!r}"
        )
    if chunk_size <= 0:
        raise ValueError(f"chunk_size must be a positive int, not {chunk_size}")
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(chunk_size), b""):
            digest.update(chunk)
    return digest.hexdigest()


_COMMIT_RE = re.compile(r"[0-9a-f]{40}|[0-9a-f]{64}")


def git_head(repo_root: str | PurePath = REPO_ROOT) -> str:
    """The commit checked out at ``repo_root``, or ``"unknown"`` when git or
    the repository is unavailable. Local only; never touches a remote."""
    try:
        completed = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=str(repo_root),
            capture_output=True,
            text=True,
            check=False,
            timeout=60,
        )
    except (OSError, subprocess.SubprocessError):
        return "unknown"
    commit = completed.stdout.strip()
    if completed.returncode != 0 or not _COMMIT_RE.fullmatch(commit):
        return "unknown"
    return commit


def bootstrap_path() -> None:
    """Make ``tests/`` importable, so ``harness.*`` and ``adapters.*``
    resolve. Idempotent."""
    tests_root = str(TESTS_ROOT)
    if tests_root not in sys.path:
        sys.path.insert(0, tests_root)


def relpath(path: str | PurePath) -> str:
    """POSIX path of ``path`` relative to the repository root.

    A relative ``path`` is taken as relative to the repository root. A path
    outside the repository raises ``HarnessError``, so no absolute path can
    enter the deterministic projection.
    """
    candidate = Path(path)
    if not candidate.is_absolute():
        candidate = REPO_ROOT / candidate
    resolved = candidate.resolve()
    try:
        return resolved.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        raise HarnessError(f"{path} lies outside the repository") from None


def display_path(path: str | PurePath) -> str:
    """``path`` as messages show it: its POSIX path relative to the repository
    root when it lies inside the repository, else ``path`` as given, so a
    checkout's absolute location never reaches a message or a result.

    The message names the path the caller gave, never where a symbolic link
    leads: a relative ``path`` is taken from the current folder, ``.`` and
    ``..`` are removed lexically, and a link on the path, the named file
    included, is not followed. Only when that path lies outside the
    repository are the folders holding the file resolved (the file itself
    still is not), so a checkout reached through a linked folder is still
    shown relative to its root. It never raises.
    """
    candidate = Path(path)
    try:
        lexical = Path(os.path.abspath(candidate))
        try:
            return lexical.relative_to(REPO_ROOT).as_posix()
        except ValueError:  # outside as given; perhaps through a linked folder
            pass
        located = lexical.parent.resolve() / lexical.name
        return located.relative_to(REPO_ROOT).as_posix()
    except (ValueError, OSError, RuntimeError):  # outside, or unresolvable
        return candidate.as_posix()


# The directory usable_work_root makes in a work root, and removes at once,
# to prove that working directories can be created and removed there.
_WORK_ROOT_PROBE_PREFIX = ".carddemo-probe-"
# The write bits that make a directory writable by someone other than its
# owner; a usable work root has neither.
_SHARED_WRITE_BITS = stat.S_IWGRP | stat.S_IWOTH
# What the path of a work root admitted with jvm_safe_path never holds: the
# UniKix deployment names its temporary directory, below the work root, in JVM
# option variables, which a JVM splits at whitespace and reads quotes in. The
# same pattern as _JVM_UNSAFE_PATH_RE in adapters/unikix/env.py, kept here
# because this package does not import the adapters.
_JVM_UNSAFE_PATH_RE = re.compile(r"[\s'\"]")
# What no work root's path holds, whatever the adapter. GnuCOBOL 3.2's cobc
# runs the C compiler and the linker through /bin/sh with each path in double
# quotes, inside which the shell still reads a backquote, '$', '"' and '\', so
# a path holding one is partly run as shell text. build.py hands cobc no path
# below the work root, but any tool that composes a shell command line from
# such a path would run that text, so no root holding one is admitted.
_SHELL_QUOTED_CHARACTERS = frozenset('`$"\\')
# PATH_MAX on Linux: the most bytes a path handed to a system call may have,
# its terminating NUL included.
_PATH_MAX = 4096
# The bytes a work root's path leaves free for the longest path the suite
# makes below it. The deepest paths are the UniKix deployment's:
# unikix/<execution id>/ (32 bytes as evidence.make_execution_id makes one;
# deploy.deployment_root takes one path component of up to 255) holding the
# zip's application tree, whose deepest member is 70 bytes long, below
# staging/migrated_app/ and the project tree. The build cache and the
# scenario working directories go less than 200 bytes deep: a full
# gnucobol-batch run made none deeper than 118 bytes (a key profile's compile
# log) and 81 bytes (a job step's evidence). The reserve covers all of them
# with room to spare; every gnucobol-batch unit and integration scenario
# passes under a work root of the limit's length.
_WORK_ROOT_PATH_RESERVE = 1024
# The most bytes, in the file-system encoding, a resolved work root may have.
_WORK_ROOT_MAX_BYTES = _PATH_MAX - 1 - _WORK_ROOT_PATH_RESERVE
# How the work root, a scenario's working directory and its inputs/ are
# opened (open_directory): for reading, as a directory, never through a
# symbolic link in the last component, by no child process. The runner opens
# them so, and usable_work_root admits a root only when it opens so, so a
# root the runner cannot open is refused before any scenario runs.
DIRECTORY_OPEN_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
# What a work root's permissions must allow, as a refusal for want of them
# states it: the runner opens the root for reading, and makes, looks up and
# removes each scenario's working directory in it.
_WORK_ROOT_ACCESS_RULE = "a work root must be a directory you own and can read, write and search (chmod u+rwx)"


def open_directory(name: str | PurePath, shown: str, dir_fd: int | None = None) -> int:
    """A descriptor of the directory ``name`` (relative to ``dir_fd`` when
    given), opened with ``DIRECTORY_OPEN_FLAGS``, so a symbolic link in its
    last component is not followed; ``HarnessError`` naming ``shown`` when it
    is a link, not a directory or cannot be opened. The caller closes it."""
    try:
        return os.open(name, DIRECTORY_OPEN_FLAGS, dir_fd=dir_fd)
    except OSError as exc:
        if exc.errno in (errno.ELOOP, errno.ENOTDIR):
            # Linux gives ENOTDIR for a link that O_DIRECTORY and O_NOFOLLOW
            # both refuse; lstat only words the refusal.
            try:
                link = stat.S_ISLNK(os.lstat(name, dir_fd=dir_fd).st_mode)
            except OSError:
                link = exc.errno == errno.ELOOP
            raise HarnessError(f"{shown} is {'a symbolic link' if link else 'not a directory'}") from None
        raise HarnessError(f"{shown} cannot be opened: {exc.strerror or exc}") from None


def usable_work_root(configured: str | PurePath, *, jvm_safe_path: bool = False) -> Path:
    """The resolved work root ``configured`` names, created when missing, or
    ``HarnessError`` saying why it cannot be one.

    A work root holds every scenario's working directory, the build cache,
    the extracted UniKix zip and the UniKix deployment root, so it is usable
    only when ``configured`` is not blank; its resolved path is neither the
    repository nor inside it and passes ``check_work_root_path`` (it holds no
    backquote, ``$``, ``"``, ``\\`` or character that is not printable, and
    has at most ``_WORK_ROOT_MAX_BYTES`` bytes), both checked before anything
    is created, so a refused value leaves nothing behind; it is created when
    missing, with mode ``0o700`` (its missing parents get the default mode);
    it is a directory; it opens as the runner opens it (``open_directory``:
    for reading, as a directory, not through a symbolic link), so the
    effective user can read it; judged on that descriptor, it is the
    effective user's own and writable by neither group nor others
    (``check_work_root_owner``), since anyone else who can write to it could
    swap a scenario's working directory, or a link or file the adapters write
    through or trust, for one of their own; and a directory can be made in it
    and removed again, as each scenario's working directory is, so the user
    can write and search it. A root that cannot be opened, or in which
    permission to make a directory is denied, is refused with the rule that
    it must be readable, writable and searchable by its owner, the user
    running the suite (``chmod u+rwx``); nothing is made in one that cannot
    be opened, and the descriptor is closed on every path. The text is taken
    as given: ``~`` is not expanded, and a relative path is taken from the
    current folder. It reads no environment variable; the caller passes the
    configured text and decides what replaces a refused root.

    ``jvm_safe_path`` adds the rule the UniKix deployment
    (``adapters/unikix/deploy.py``) holds a work root's path to: the resolved
    path holds no whitespace and no quote, which the JVM option variables
    naming the deployment's temporary directory below it cannot carry. It is
    checked before ``check_work_root_path``, so a path both refuse is refused
    for the deployment's reason, worded as the deployment words it.
    """
    if not isinstance(configured, (str, PurePath)):
        raise TypeError(f"configured must be str or a path, not {type(configured).__name__}")
    text = str(configured)
    if not text.strip():
        raise HarnessError("the value is blank")
    try:
        root = Path(text).resolve()
    except (OSError, RuntimeError, ValueError) as exc:  # pathlib reports a symbolic link loop as RuntimeError
        if isinstance(exc, RuntimeError):
            reason = "its symbolic links form a loop"
        else:
            reason = getattr(exc, "strerror", None) or str(exc) or type(exc).__name__
        raise HarnessError(f"the path cannot be resolved: {reason}") from None
    if root == REPO_ROOT:
        raise HarnessError("the path is the repository itself")
    if REPO_ROOT in root.parents:
        raise HarnessError(f"the path resolves to {display_path(root)}, inside the repository")
    if jvm_safe_path:
        _check_jvm_safe_path(root)
    check_work_root_path(root)
    try:
        root.mkdir(mode=0o700, parents=True, exist_ok=True)
    except OSError as exc:
        if os.path.exists(root) and not os.path.isdir(root):
            raise HarnessError(f"{root} is not a directory") from None
        raise HarnessError(f"{root} cannot be created: {exc}") from None
    if not root.is_dir():
        raise HarnessError(f"{root} is not a directory")
    try:
        descriptor = open_directory(root, str(root))
    except HarnessError as exc:
        raise HarnessError(f"{exc}; {_WORK_ROOT_ACCESS_RULE}") from None
    try:
        try:
            status = os.fstat(descriptor)
        except OSError as exc:
            raise HarnessError(f"{root} cannot be inspected: {exc.strerror or exc}") from None
        check_work_root_owner(root, status)
    finally:
        os.close(descriptor)
    try:
        probe = tempfile.mkdtemp(prefix=_WORK_ROOT_PROBE_PREFIX, dir=root)
    except OSError as exc:
        if exc.errno == errno.EACCES:  # no write or no search permission
            raise HarnessError(
                f"no directory can be made in {root}: {exc.strerror}; {_WORK_ROOT_ACCESS_RULE}"
            ) from None
        raise HarnessError(f"{root} is not writable: {exc}") from None
    try:
        os.rmdir(probe)
    except OSError as exc:
        raise HarnessError(f"a directory made in {root} cannot be removed: {exc}") from None
    return root


def _check_jvm_safe_path(root: Path) -> None:
    """``HarnessError`` when the path ``root`` holds whitespace or a quote,
    worded as the UniKix deployment refuses such a root; nothing is read
    from the file system."""
    if _JVM_UNSAFE_PATH_RE.search(str(root)):
        raise HarnessError(
            f"{root} holds whitespace or a quote, which a JVM option variable cannot carry; "
            "choose another work root"
        )


def check_work_root_path(root: str | PurePath) -> None:
    """``HarnessError`` when the path ``root``, a resolved work root, cannot
    be one whatever the adapter; nothing is read from the file system.

    It must hold no backquote, ``$``, ``"`` or ``\\``, which a shell reads
    even inside the double quotes GnuCOBOL's cobc puts around each path of
    the C compiler commands it runs through ``/bin/sh``
    (``_SHELL_QUOTED_CHARACTERS``), and no character that is not printable (a
    line break, a tab, another control character), which no one-line log,
    message or record naming a path below the root could show; the message
    names the first such character. Its file-system encoding must have at
    most ``_WORK_ROOT_MAX_BYTES`` bytes, so that the longest path the suite
    makes below it (``_WORK_ROOT_PATH_RESERVE`` more) stays within
    ``PATH_MAX``; the message names its length and the limit. A path holding
    a character that is not printable is shown as its ``repr``."""
    text = os.fspath(root)
    shown = text if text.isprintable() else repr(text)
    for character in text:
        if character in _SHELL_QUOTED_CHARACTERS:
            raise HarnessError(
                f"{shown} holds {character!r}, which a shell reads even inside double quotes, as in the C "
                "compiler commands GnuCOBOL's cobc runs through /bin/sh; choose another work root"
            )
        if not character.isprintable():
            raise HarnessError(
                f"{shown} holds the non-printable character {character!r}, which no one-line log, message or "
                "record naming a path below the work root could show; choose another work root"
            )
    length = len(os.fsencode(text))
    if length > _WORK_ROOT_MAX_BYTES:
        raise HarnessError(
            f"the path is {length} bytes long, more than the {_WORK_ROOT_MAX_BYTES} a work root may have: the "
            f"paths the suite makes below it take up to {_WORK_ROOT_PATH_RESERVE} more, and no path may exceed "
            f"{_PATH_MAX - 1} bytes (PATH_MAX); choose a shorter work root"
        )


def check_work_root_owner(root: str | PurePath, status: os.stat_result) -> None:
    """``HarnessError`` unless ``status``, the status of the work root
    directory ``root`` (as ``os.stat`` or ``os.fstat`` gives it), is the
    effective user's own and writable by neither group nor others: anyone
    else who can write to it could plant the links and files the suite
    writes through or trusts, or swap a scenario's working directory for one
    of their own. Worded as the UniKix deployment refuses such a root."""
    if status.st_uid != os.geteuid():
        raise HarnessError(f"{root} is owned by uid {status.st_uid}, not {os.geteuid()}; choose another work root")
    if status.st_mode & _SHARED_WRITE_BITS:
        raise HarnessError(
            f"{root} is writable by group or others (mode {stat.S_IMODE(status.st_mode):04o}), so they could "
            "plant links in it; run chmod go-w on it or choose another work root"
        )


# The lock file a session holds in its configured work root for as long as it
# runs. Each scenario runs in <work root>/<scenario_id>/, which a run empties
# first, so two sessions sharing a root would delete each other's live
# working directories. Underscored like _build and _unikix_zip, so it never
# names a scenario's directory.
WORK_ROOT_LOCK_NAME = "_session.lock"
# The most of the holder's command line its record keeps, and the most of a
# record a refused session reads back.
_HOLDER_COMMAND_CHARS = 200
_HOLDER_RECORD_BYTES = 1024
# How long, in seconds, a refused session keeps re-reading an empty holder
# record, and how often. A holder writes its record just after it takes the
# lock, and a holder that ends empties it, so a session refused in between
# reads no record; the moment that takes is far below this bound. A record
# that is present ends the wait at once, so a refusal that can name its
# holder never waits.
_HOLDER_WAIT_S = 1.0
_HOLDER_POLL_S = 0.02


class WorkRootBusy(HarnessError):
    """The work root ``root`` is locked by another session, which ``holder``
    describes as its record gives it."""

    def __init__(self, root: Path, holder: str) -> None:
        self.root = root
        self.holder = holder
        super().__init__(
            f"the work root {root} is in use by another session ({holder}); give each concurrent session "
            "its own work root, or wait for that session to end"
        )


class WorkRootLock:
    """The exclusive lock ``lock_work_root`` took on a work root, held until
    ``release``, the end of a ``with`` block or the end of the process."""

    def __init__(self, root: Path, path: Path, descriptor: int) -> None:
        self.root = root
        self.path = path
        self._descriptor: int | None = descriptor

    @property
    def held(self) -> bool:
        """Whether this lock is still held."""
        return self._descriptor is not None

    def release(self) -> None:
        """Empty the holder record and give the lock up; nothing when it is
        already released. The lock file stays, so every later session locks
        the same file: removing it would let two sessions each lock a file
        of that name."""
        descriptor, self._descriptor = self._descriptor, None
        if descriptor is None:
            return
        try:
            os.ftruncate(descriptor, 0)
        except OSError:
            pass  # the record only names the holder; closing still releases the lock
        finally:
            os.close(descriptor)

    def __enter__(self) -> WorkRootLock:
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.release()


def _printable(text: str) -> str:
    """``text`` with every character that is not printable, a line break
    included, shown as ``?``, so a record stays one line."""
    return "".join(character if character.isprintable() else "?" for character in text)


def _holder_record() -> str:
    """This process as the lock's record names it: its pid, the UTC time it
    took the lock and its command line, clipped."""
    command = _printable(" ".join(sys.argv)) or "?"
    if len(command) > _HOLDER_COMMAND_CHARS:
        command = command[: _HOLDER_COMMAND_CHARS - 3] + "..."
    since = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    return f"pid {os.getpid()}, since {since}, running {command}"


def _try_lock(descriptor: int, path: Path) -> bool:
    """Whether the exclusive lock of the session lock ``path``, open as
    ``descriptor``, was taken without waiting; ``False`` when another open
    file description holds it, and ``HarnessError`` with the reason when it
    cannot be locked at all."""
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        return False
    except OSError as exc:
        raise HarnessError(f"the session lock {path} cannot be locked: {exc.strerror or exc}") from None
    return True


def _read_holder(descriptor: int, path: Path) -> str | None:
    """The first line of the holder record of the session lock ``path``,
    which another session holds, read through ``descriptor`` without taking
    the lock. A record that is present is returned at once. An empty one is
    what a holder shows between taking the lock and writing its record, so
    it is read again every ``_HOLDER_POLL_S`` seconds until a read made
    ``_HOLDER_WAIT_S`` seconds after the first, and the lock is tried before
    each re-read: ``None`` when it was taken because its holder ended
    meanwhile, and "it has not recorded itself yet" when the record stays
    empty while the lock stays held."""
    deadline = time.monotonic() + _HOLDER_WAIT_S
    while True:
        # Timed before the read, so the record is given up on only after a
        # read made once the whole bound has passed, however this session is
        # delayed between the two.
        now = time.monotonic()
        try:
            data = os.pread(descriptor, _HOLDER_RECORD_BYTES, 0)
        except OSError:
            data = b""
        holder = _printable(data.decode("utf-8", "replace").split("\n", 1)[0]).strip()
        if holder:
            return holder
        if now >= deadline:
            return "it has not recorded itself yet"
        time.sleep(min(_HOLDER_POLL_S, deadline - now))
        if _try_lock(descriptor, path):
            return None


def lock_work_root(root: str | PurePath) -> WorkRootLock:
    """Take the exclusive lock of the work root ``root`` for this session,
    or raise ``WorkRootBusy`` naming the root and the session that holds it.

    The lock is an ``fcntl.flock`` on ``<root>/_session.lock``, taken without
    waiting. It locks an open file description, so it excludes other
    processes and other descriptions in this one alike, and the kernel
    releases it when its holder exits, however it ends; the descriptor is
    close-on-exec, so no child process inherits it. The file is created
    when missing, never opened through a symbolic link, and must be a
    regular file. Once locked it holds one line naming this session
    (``_holder_record``), written over whatever a holder that ended left
    there before the file is cut to its length, so the file is never
    emptied in between. A refused session reads that line back
    (``_read_holder``): at once when it is there, and otherwise after
    waiting at most ``_HOLDER_WAIT_S`` seconds for the holder that has just
    locked to write it, taking the lock instead should that holder end
    meanwhile. ``HarnessError`` with the reason when the file cannot be
    opened, locked or written."""
    work = Path(root)
    path = work / WORK_ROOT_LOCK_NAME
    try:
        descriptor = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_CLOEXEC, 0o644)
    except OSError as exc:
        if exc.errno == errno.ELOOP:
            raise HarnessError(
                f"the session lock {path} is a symbolic link, which is never followed; remove it or choose "
                "another work root"
            ) from None
        if exc.errno == errno.EISDIR:
            raise HarnessError(
                f"the session lock {path} is not a regular file; remove it or choose another work root"
            ) from None
        raise HarnessError(f"the session lock {path} cannot be opened: {exc.strerror or exc}") from None
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise HarnessError(
                f"the session lock {path} is not a regular file; remove it or choose another work root"
            )
        # Made before the lock is taken, so a refused session finds the
        # record written as soon after the lock as possible.
        record = f"{_holder_record()}\n".encode("utf-8")
        if not _try_lock(descriptor, path):
            holder = _read_holder(descriptor, path)
            if holder is not None:
                raise WorkRootBusy(work, holder)
            record = f"{_holder_record()}\n".encode("utf-8")  # locked only now, after the wait
        try:
            written = os.pwrite(descriptor, record, 0)
            os.ftruncate(descriptor, written)
        except OSError as exc:
            raise HarnessError(
                f"this session cannot be recorded in the session lock {path}: {exc.strerror or exc}"
            ) from None
        if written != len(record):
            raise HarnessError(
                f"this session cannot be recorded in the session lock {path}: only {written} of "
                f"{len(record)} bytes were written"
            )
    except BaseException:
        os.close(descriptor)
        raise
    return WorkRootLock(work, path, descriptor)
