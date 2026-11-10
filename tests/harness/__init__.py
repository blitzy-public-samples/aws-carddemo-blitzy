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
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path, PurePath
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


class _CanonicalDumper(yaml.SafeDumper):
    """SafeDumper whose output depends only on the value it is given."""

    def ignore_aliases(self, data: Any) -> bool:
        # Anchors would make the text depend on object identity: the same value
        # built from shared or from copied objects would dump differently, and
        # digests and fingerprints taken over dump_yaml would change with it.
        return True

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
    raise HarnessError(
        f"float {data!r} cannot be written: numbers are Decimal values carried "
        f"as strings with their scale, never floats"
    )


def _represent_decimal(dumper: yaml.SafeDumper, data: decimal.Decimal) -> yaml.Node:
    # represent_str quotes text that would load back as a number, so the
    # value returns as the same string with its scale ("504.70", not 504.7).
    return dumper.represent_str(str(data))


def _represent_path(dumper: yaml.SafeDumper, data: PurePath) -> yaml.Node:
    return dumper.represent_str(data.as_posix())


def _represent_set(dumper: yaml.SafeDumper, data: set | frozenset) -> yaml.Node:
    try:
        items = sorted(data)
    except TypeError:
        items = sorted(data, key=lambda item: (type(item).__name__, repr(item)))
    return dumper.represent_list(items)


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
    stable. Returns whether the file was written."""
    if not isinstance(text, str):
        raise TypeError(f"text must be str, not {type(text).__name__}")
    file_path = Path(path)
    data = text.encode("utf-8")
    try:
        unchanged = file_path.read_bytes() == data
    except FileNotFoundError:
        unchanged = False
    if unchanged:
        return False
    file_path.parent.mkdir(parents=True, exist_ok=True)
    with open(file_path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    return True


def sha256_hex(data: bytes | bytearray | memoryview | str) -> str:
    """Hex sha256 of bytes, or of a string encoded as UTF-8."""
    if isinstance(data, str):
        data = data.encode("utf-8")
    elif not isinstance(data, (bytes, bytearray, memoryview)):
        raise TypeError(f"cannot hash {type(data).__name__}; pass bytes or str")
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: str | PurePath, chunk_size: int = 1 << 20) -> str:
    """Hex sha256 of a file's contents, read in chunks."""
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
