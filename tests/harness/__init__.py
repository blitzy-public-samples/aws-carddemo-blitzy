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
import hashlib
import os
import re
import stat
import subprocess
import sys
from datetime import date, datetime
from pathlib import Path, PurePath, PureWindowsPath
from typing import Any

import yaml

__all__ = [
    "ADAPTERS_DIR",
    "ADAPTER_NAMES",
    "BASELINES_DIR",
    "DEADLINE_S",
    "DEFAULT_CLOCK",
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
    "bootstrap_path",
    "cob_current_date",
    "dump_yaml",
    "git_head",
    "load_yaml",
    "relpath",
    "sha256_file",
    "sha256_hex",
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


class HarnessError(Exception):
    """An error the harness itself detects: schema, fixture, cross-check,
    configuration. Harness modules subclass it for their own error kinds."""


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

# The C parser is a faster drop-in with the same safe constructors and
# resolvers; the pure-Python one is used where libyaml is absent.
_SafeLoader = getattr(yaml, "CSafeLoader", yaml.SafeLoader)


def dump_yaml(obj: Any) -> str:
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
    list, tuples a list in order. A float anywhere raises ``HarnessError``.
    Strings, including trailing spaces and control characters, load back
    unchanged.
    """
    try:
        return yaml.dump(
            obj,
            Dumper=_CanonicalDumper,
            sort_keys=True,
            default_flow_style=False,
            allow_unicode=True,
            width=2**31 - 1,
            line_break="\n",
        )
    except yaml.YAMLError as exc:
        raise HarnessError(f"cannot write value as YAML: {exc}") from exc


def load_yaml(path: str | PurePath, missing_ok: bool = False) -> Any:
    """Load a UTF-8 YAML file with safe-load semantics.

    A missing file returns ``None`` when ``missing_ok`` is true and raises
    ``FileNotFoundError`` otherwise.
    """
    file_path = Path(path)
    try:
        text = file_path.read_text(encoding="utf-8")
    except FileNotFoundError:
        if missing_ok:
            return None
        raise
    return yaml.load(text, Loader=_SafeLoader)


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
