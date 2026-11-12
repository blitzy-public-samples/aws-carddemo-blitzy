"""Compilation and build cache of the ``gnucobol-batch`` adapter.

``ensure_built(repo_root, work_root)`` compiles, once per build key, everything
the adapter runs into ``<work root>/_build/<build key>/``:

``bin/<UNIT>``
    The executable batch units (``cobc -x``): CBACT01C, CBACT02C, CBACT03C,
    CBCUS01C, CBTRN01C, CBTRN02C, CBTRN03C, and CBSTM03A built together with
    CBSTM03B (main first, ``-fbinary-byteorder=native``).
``modules/``
    The ``COB_LIBRARY_PATH`` directory: ``CBACT04C.so`` and ``CSUTLDTC.so``
    (``cobc -m``, their ``PROCEDURE DIVISION USING`` linkage), and the stubs
    ``CEE3ABD.so`` (GAP-STB-001) and ``CEEDAYS.so`` (GAP-STB-002).
``bin/PARMDRV``, ``bin/DTCDRV``
    The drivers that supply the z/OS PARM linkage to CBACT04C and the CALL
    interface of CSUTLDTC (GAP-STB-004).
``lib/psa_tiot_shim.so``, ``bin/page0_probe``
    The page-zero PSA/TCB/TIOT shim preloaded into CBSTM03A (GAP-STB-003) and
    the probe that decides whether page zero is mappable (GAP-MIS-002).
``profiles/<profile name>/KSLOAD``, ``KSUNLOAD``
    The generated indexed-file loader and unloader of one ``KeyProfile``.
``logs/<unit>.log``, ``src/``, ``manifest.yaml``, ``COMPLETE``
    Each compile's command line, working directory, status, stdout and
    stderr; generated sources; the evidence of what was built, with the
    sha256 of every artifact under ``artifacts``; the marker written last.
``logs/CLOCKCHK.probe.log``
    Each clock-probe run's command line, ``COB_CURRENT_DATE``, status, stdout
    and stderr: the evidence of ``clock_fraction_mode``.

Compile options (GAP-STB-011). Every COBOL compile uses ``COMMON_FLAGS`` plus
``-I app/cpy``. They reproduce the Enterprise COBOL build of
``samples/proc/BUILDBAT.prc``, ``PARM=(APOST,LIST,MAP,NUMBER,NOSEQ)`` with the
default ``TRUNC(STD)``: ``-std=ibm`` for the dialect, ``-fsign=EBCDIC`` so the
overpunch ``0000005047G`` reads +504.77 (without it POSTTRAN rejects 299 of 300
records as 102), ``-ftab-width=4`` for the tab characters of ``CUSTREC``, and
``-fbinary-truncate`` for TRUNC(STD), which ``ibm-strict.conf`` turns off. The
mapping stays a stand-in: ``defaultbyte 0`` gives the ``x'00'`` fill of the
CBACT04C descriptions, and comparisons use the ASCII collating sequence. A
unit may add only the flags of ``_EXTRA_FLAGS``, matched exactly (today
``-fbinary-byteorder=native``, for CBSTM03A and the CBSTM03B it links); any
other ``compile.extra_flags`` entry of a unit or of a unit it links, such as
``-o``, ``-t``, ``-T``, ``-P``, ``--save-temps``, ``--conf``, ``-A`` or
``-Q``, which choose where cobc writes or forward options to the C compiler or
linker, is a ``BuildError`` before anything is written or compiled.

Build key. ``compute_build_key`` hashes, with repository-relative names only,
the unit sources of ``programs.yaml``, every file of ``app/cpy``, every stub of
``tests/stubs``, every generated source and ``TEMPLATE_VERSION``, the static
key-profile list, each compile's flags and the exact ``cobc --version`` output,
so the key is the same on every checkout of one commit with one toolchain.

Atomic cache. A build runs in a private temporary directory under ``_build``
and is renamed to its final name only when complete, so concurrent sessions
on one work root either reuse a finished cache or discard their own copy. The
key lock ``_build/<key>.lock``, an ``fcntl.flock`` that excludes other
processes and threads and is never deleted, serializes everything that
inspects or renames the key's directories: the check of ``_build/<key>/`` with
the removal of an unfinished or mismatched one, its publication and the check
after it, the set-aside of a failed or interrupted build or profile, and the
publication of an on-demand profile. The compiles run outside it, so the
first session to publish wins. A failed build, whether a compile or the clock
probe failed, is kept as ``_build/<key>.failed`` and the ``BuildError`` names
the unit or the probe and its log there. A build interrupted by
``KeyboardInterrupt``, SIGTERM, SIGHUP or any other exception is kept there
too, with the logs its compiles wrote, before the exception goes on or the
signal is acted on; an on-demand profile is kept as
``profiles/<profile>.failed`` in either case. A ``.failed`` directory is never
complete, so the next session rebuilds. Within a session a failed build is
not retried: every later call with the same repository and work root raises
the same ``BuildError`` and compiles nothing. A failed build is a harness
error, never a gap. A missing or unsuitable toolchain is not a failed build:
the probe of ``cobc`` and ``cc``, made once per session, raises
``ToolchainUnavailable`` before anything is written or compiled, and every
later call raises it again without probing; the adapter reports that missing
runtime as ``blocked`` under its registered gap. Each probe runs in a process
group of its own, killed whole when the probe has not finished within
``_TOOL_PROBE_TIMEOUT_S``.

Integrity. A finished directory is reused only when every sha256 its manifest
records matches its file, checked once per session when the cache is loaded.
A truncated, altered or missing artifact, or a manifest without the hashes,
makes the directory stale, so it is removed and rebuilt. An on-demand profile
keeps its own ``profiles/<profile>/manifest.yaml``, checked on its first use
in a session; on a mismatch it is set aside under the key lock, removed and
rebuilt. Below the work root every component of the cache is refused at
admission when it is unsound and re-verified by identity before every
reuse, creation, set-aside, removal or publication. ``_build`` is created
private (mode 0700) when missing and admitted only as a real directory of the
effective user that neither group nor others can write to, never a symbolic
link, before anything is created, reused, published or removed in it; one an
earlier session created with mode 0755 is admitted. The key lock is opened
without following a link and must be the user's regular file, and
``_build/<key>/``, ``profiles/`` and ``profiles/<profile>/`` are refused when
they are symbolic links or no directories. The identities, ``(st_dev,
st_ino)``, of ``_build`` as admitted and of ``_build/<key>/`` once found or
published finished are recorded, and both are re-admitted, without following
a link, before the cache is reused: whenever ``ensure_built`` would return
the memoized cache and on every ``loader`` and ``unloader`` call, a profile
already checked included. ``_build`` is re-admitted in every key lock
section, before the lock file is opened and once it is held, and before a
build workspace is created; a profile's creation, set-aside, removal and
publication re-admit both and ``profiles/`` first. A component that is now a
symbolic link, no directory, another user's or a different directory than
the one admitted is refused. Each refusal is a ``BuildError`` naming the
component, a configuration error that is never memoized, and nothing is
read, written or removed through it; a workspace reachable only through a
refused component is left where it is. A memoized cache whose ``_build`` or
``_build/<key>/`` was removed is built afresh by the next ``ensure_built``.
These checks run before each such step, not while a build writes into its
own workspace: a swap made while the compiles run can receive what they and
the build then write there, and is refused at the next check, before
anything is set aside, removed or published. Verification never takes a
link's target as its trust root: a directory that is, or is reached through,
a symbolic link is not intact.

Indexed files. GnuCOBOL 3.2 with Berkeley DB keeps alternate key *i* in
``<file>.i``. ``OPEN INPUT`` fails with status 35 when a declared alternate's
file is absent, where ``OPEN I-O`` creates it holding only the records written
after it, and ``OPEN`` fails with status 30 when the reader declares ``WITH
DUPLICATES`` on a key built without them, so each file is built with exactly
the keys its reader declares: a ``KeyProfile``. One
loader and one unloader are generated per profile; ``static_profiles`` lists
the profiles compiled up front, and ``BuildCache.loader``/``unloader`` build
any other profile on demand. Both programs assign the indexed file to
``KSDS_DD`` and the record-sequential file of the profile's record length to
``SEQIN_DD`` or ``SEQOUT_DD``; pass absolute paths in ``DD_<name>``.

Loader ``KSLOAD <mode>``:

* ``LOAD``: ``OPEN OUTPUT``, then write every SEQIN record.
* ``APPEND``: ``OPEN OUTPUT`` when the file is absent; otherwise ``OPEN
  INPUT``, which needs the file of every declared alternate, then ``OPEN
  I-O``; then write every SEQIN record. A present file without one is refused
  and left untouched: re-key a file by unloading it and running ``LOAD`` with
  the new profile.
* ``CREATE``: ``OPEN OUTPUT`` and ``CLOSE``, an empty file; prints ``CREATED``.

SEQIN must hold whole records: before LOAD or APPEND opens the indexed file, a
SEQIN whose size is not a multiple of the record length prints ``SEQIN SIZE
<bytes> NOT A MULTIPLE OF <length>`` and nothing is loaded; an empty SEQIN
loads nothing. A record whose key is already present (status 22) prints
``DUPKEY <n>`` and the load goes on; a load ends with ``LOADED <count>``.
Unloader ``KSUNLOAD PRIMARY`` or ``KSUNLOAD ALT <n>`` writes every record in
primary-key order, or in the order of the profile's 1-based alternate key
``n``, and ends with ``UNLOADED <count>``; ``NOFILE`` means that the file
itself is absent. When ``OPEN INPUT`` gives status 35 for a present file, both
programs print ``KSDSOUT OPEN STATUS 35 ALTERNATE INDEX MISSING``, or
``KSDSOUT OPEN STATUS 35`` for a profile without alternates. Record numbers
and counts are 9 zero-padded digits, ``<bytes>`` 20. Failures print ``STATUS
<xx> AT <n>``, ``SEQIN STATUS <xx> AT <n>``, ``<FILE> <verb> STATUS <xx>``,
the lines above, ``<FILE> CHECK STATUS <rc>`` when ``CBL_CHECK_FILE_EXIST``
cannot size SEQIN or tell whether the indexed file exists (``<rc>`` its
signed 9-digit return code), or ``USAGE: ...``.
Exit statuses are ``EXIT_OK``, ``EXIT_FAILED`` after a failure line,
``EXIT_USAGE`` after ``USAGE:`` and ``EXIT_NOFILE`` after ``NOFILE``.

Clock. ``clock_fraction_mode`` says how libcob reads the fraction of
``COB_CURRENT_DATE``: ``hundredths`` or ``nanoseconds``, as
``harness.cob_current_date`` formats it. A generated probe decides it once per
cache.

Nothing is written in the repository: each compile runs with the build
directory as its working directory and ``TMPDIR=tmp`` inside it, and gets
its output (``-o``) and generated sources by their paths relative to the
build directory, so no text of the work root reaches the shell commands cobc
runs the C compiler and the linker through; repository sources and ``-I
app/cpy`` are passed by absolute path. A cobc compile whose arguments or
``TMPDIR`` hold a character such a shell command would read (a backquote,
``$``, ``"`` or ``\\``) or one that is not printable, which only a checkout
or cobc path can hold, is refused unrun and fails the build with a
``BuildError`` naming the argument and the character. Each compile log names
the build directory ``<build>``. Nothing is written outside the build
directory either. Every ``programs.yaml`` unit key must be a batch program
name, one to eight upper-case letters and digits starting with a letter
(``_UNIT_ID_RE``), because it names the unit's artifact and log; any other
key, one holding ``/`` or ``..`` included, is a ``BuildError`` before
anything is written. Independently, each compile's artifact, each compile
log and each generated source must be a non-empty relative path without a
``..`` part, so it stays lexically inside the directory being built; it is
checked before any directory is created, any file written or any compiler
started, and a ``BuildError`` names the first that is not. Importing this
module starts no process and reads no file.
"""

from __future__ import annotations

import fcntl
import functools
import inspect
import os
import re
import shlex
import shutil
import signal
import stat
import subprocess
import tempfile
import threading
import time
from collections.abc import Callable, Iterable, Iterator, Mapping, Sequence
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any

from harness import (
    HarnessError,
    YAMLLoadError,
    cob_current_date,
    dump_yaml,
    load_yaml,
    sha256_file,
    sha256_hex,
)

__all__ = [
    "COMMON_FLAGS",
    "COMPILE_TIMEOUT_S",
    "EXIT_FAILED",
    "EXIT_NOFILE",
    "EXIT_OK",
    "EXIT_USAGE",
    "KSDS_DD",
    "LOADER_MODES",
    "SEQIN_DD",
    "SEQOUT_DD",
    "TEMPLATE_VERSION",
    "UNLOADER_MODES",
    "BuildCache",
    "BuildError",
    "KeyProfile",
    "ToolchainUnavailable",
    "compute_build_key",
    "ensure_built",
    "profile_for",
    "static_profiles",
]

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

# The dialect flags of every COBOL compile; "-I app/cpy" is added per call.
COMMON_FLAGS = ("-std=ibm", "-fsign=EBCDIC", "-ftab-width=4", "-fbinary-truncate")
COPYBOOK_DIR = "app/cpy"

# Changed whenever the generated loader, unloader or clock-probe programs, or
# the layout of the build directory, change in a way the hashed sources do not
# show.
TEMPLATE_VERSION = "3"

# Seconds one compile or probe may take. The build is session-level, so no
# scenario deadline bounds it. A compile that overruns COMPILE_TIMEOUT_S is
# killed with its whole process group, the C compiler, assembler and linker
# cobc started, as is a toolchain probe that overruns _TOOL_PROBE_TIMEOUT_S.
COMPILE_TIMEOUT_S = 600
_TOOL_PROBE_TIMEOUT_S = 60

# The ddnames the generated loader and unloader assign.
KSDS_DD = "KSDSOUT"
SEQIN_DD = "SEQIN"
SEQOUT_DD = "SEQOUT"
LOADER_MODES = ("LOAD", "APPEND", "CREATE")
UNLOADER_MODES = ("PRIMARY", "ALT")

# Exit statuses of the generated loader and unloader.
EXIT_OK = 0
EXIT_FAILED = 12
EXIT_USAGE = 16
EXIT_NOFILE = 35

_PROGRAMS_YAML = "tests/adapters/gnucobol_batch/programs.yaml"
_JOBS_YAML = "tests/adapters/gnucobol_batch/jobs.yaml"
_DATASETS_YAML = "tests/fixtures/datasets.yaml"
_LAYOUTS_YAML = "tests/inventory/layouts.yaml"
_STUBS_DIR = "tests/stubs"

_COMPLETE = "COMPLETE"
_MANIFEST = "manifest.yaml"
_BUILD_SUBDIR = "_build"
_LOCK_SUFFIX = ".lock"
# The mode of a build root ensure_built creates, and the bits that refuse
# one: a directory others can write to is one where they can plant links.
_PRIVATE_DIR_MODE = 0o700
_SHARED_WRITE = stat.S_IWGRP | stat.S_IWOTH
_REFUSAL_HINT = "remove it or choose another work root"
_SETUP_HINT = (
    "GnuCOBOL 3.2 built --with-db (Berkeley DB) must be on PATH, with its lib "
    "directory on LD_LIBRARY_PATH; see tests/README.md"
)

_MODE_FLAGS = {"executable": "-x", "module": "-m"}
_RUN_MODES = {"executable": "executable", "parmdrv": "module", "dtcdrv": "module"}
# The flags a programs.yaml unit may add to its compile, matched exactly: only
# -fbinary-byteorder=native, which CBSTM03A and the CBSTM03B it links need
# because TIOT-INDEX is a POINTER redefined as binary. An argv list keeps a flag
# from the shell, not from cobc: -o, -t, -T, -P, --save-temps, --conf, -I, -L,
# -A, -Q and -j choose output, listing, temporary, configuration or search
# paths, forward options to the C compiler or linker, or run the program, so
# any flag not listed here is refused before anything is compiled.
_EXTRA_FLAGS = frozenset({"-fbinary-byteorder=native"})
_LOADER_PROGRAM = "KSLOAD"
_UNLOADER_PROGRAM = "KSUNLOAD"
_CLOCK_PROGRAM = "CLOCKCHK"
_CLOCK_PROBE = "2022-07-06 10:00:00.37"
_CLOCK_EXPECTED = "2022070610000037"  # FUNCTION CURRENT-DATE(1:16) of _CLOCK_PROBE
_CLOCK_MODES = ("hundredths", "nanoseconds")

# Toolchain variables that cobc honours from the environment. A compile keeps
# only the ones the caller sets, and the key hashes them, so an operator's
# toolchain configuration survives the minimal compile environment.
_TOOLCHAIN_VARIABLES = (
    "COB_CC", "COB_CFLAGS", "COB_LDFLAGS", "COB_LIBS", "COB_CONFIG_DIR", "COB_COPY_DIR",
)

_COBC_VERSION_RE = re.compile(r"^cobc \(GnuCOBOL\) 3\.2(\.0)?$")
_BDB_RE = re.compile(r"^indexed file handler\s*:\s*BDB\b", re.MULTILINE)
_INFO_ENTRY_RE = re.compile(r"^(\S.*?)\s+:\s?(.*)$")
_SHA256_RE = re.compile(r"[0-9a-f]{64}")  # an ``artifacts`` digest, matched whole
# A programs.yaml unit key, matched whole: a batch program name of one to
# eight upper-case letters and digits, starting with a letter. The key becomes
# the artifact bin/<UNIT> or modules/<UNIT>.so and the log logs/<UNIT>.log, so
# no other key, one holding "/" or ".." included, is admitted.
_UNIT_ID_RE = re.compile(r"[A-Z][A-Z0-9]{0,7}")
_COBOL_LINE_LIMIT = 72

_PARALLEL_COMPILES = max(1, min(4, os.cpu_count() or 1))


class BuildError(HarnessError):
    """The toolchain is missing or unsuitable, the build configuration is
    inconsistent, a compile or the clock probe failed, or a SIGTERM or SIGHUP
    stopped the compiles or a toolchain probe and the caller's handler let the
    process go on.

    ``unit`` names the compilation unit and ``log_path`` its compile log when
    the error comes from a compile, or ``CLOCKCHK`` and its probe log when the
    clock probe failed; the failed build keeps both. A missing or unsuitable
    toolchain is the subclass ``ToolchainUnavailable``; every other
    ``BuildError`` is a harness error, which the runner reports as
    ``failing`` with reason ``error`` and never turns into a skip."""

    def __init__(
        self, message: str, *, unit: str | None = None, log_path: Path | None = None
    ) -> None:
        super().__init__(message)
        self.unit = unit
        self.log_path = log_path


class ToolchainUnavailable(BuildError):
    """The toolchain the build needs is absent or unsuitable: ``cobc`` is not
    on ``PATH``, is not GnuCOBOL 3.2 or lacks the Berkeley DB indexed file
    handler, no C compiler is on ``PATH``, or one of their version or
    ``--info`` probes cannot run or exits non-zero. Nothing was compiled. It
    is a missing runtime, which the adapter reports ``blocked`` under its
    registered gap, not a harness error."""


# --------------------------------------------------------------------------
# Key profiles
# --------------------------------------------------------------------------


def _non_negative_int(value: Any, what: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise BuildError(f"{what} must be a non-negative integer, got {value!r}")
    return value


@dataclass(frozen=True, order=True)
class KeyProfile:
    """The record length and the keys an indexed file is built with.

    ``primary`` is ``(offset, length)`` and each alternate ``(offset, length,
    duplicates)``, with 0-based offsets, in the order the reader's SELECT
    declares them: alternate ``i`` (1-based) is ``<file>.i`` on disk and
    ``ALT i`` for the unloader."""

    record_length: int
    primary: tuple[int, int]
    alternates: tuple[tuple[int, int, bool], ...] = ()

    def __post_init__(self) -> None:
        length = _non_negative_int(self.record_length, "record_length")
        if length == 0:
            raise BuildError("record_length must be positive")
        primary = tuple(self.primary)
        if len(primary) != 2:
            raise BuildError(f"primary key must be (offset, length), got {self.primary!r}")
        alternates = []
        for alternate in self.alternates:
            alternate = tuple(alternate)
            if len(alternate) != 3 or not isinstance(alternate[2], bool):
                raise BuildError(
                    f"alternate key must be (offset, length, duplicates), got {alternate!r}"
                )
            alternates.append(alternate)
        for offset, key_length in [primary, *(alt[:2] for alt in alternates)]:
            _non_negative_int(offset, "key offset")
            if _non_negative_int(key_length, "key length") == 0 or offset + key_length > length:
                raise BuildError(
                    f"key at offset {offset} with length {key_length} does not fit a "
                    f"{length}-byte record"
                )
        object.__setattr__(self, "primary", primary)
        object.__setattr__(self, "alternates", tuple(alternates))

    @property
    def name(self) -> str:
        """Canonical id, ``L<len>-P<off>x<len>[-A<off>x<len><D|U>...]``."""
        offset, length = self.primary
        text = f"L{self.record_length}-P{offset}x{length}"
        for alt_offset, alt_length, duplicates in self.alternates:
            text += f"-A{alt_offset}x{alt_length}{'D' if duplicates else 'U'}"
        return text

    def with_alternates(self, alternates: Iterable[Sequence[Any]]) -> KeyProfile:
        """The same record length and primary key with other alternates;
        ``with_alternates(())`` is the primary-only profile."""
        return KeyProfile(self.record_length, self.primary, tuple(tuple(alt) for alt in alternates))


def _key_pair(key: Any, what: str) -> tuple[int, int]:
    if not isinstance(key, Mapping):
        raise BuildError(f"{what} must be a mapping with offset and length, got {key!r}")
    return (
        _non_negative_int(key.get("offset"), f"{what} offset"),
        _non_negative_int(key.get("length"), f"{what} length"),
    )


def profile_for(record_length: int, keys: Mapping[str, Any]) -> KeyProfile:
    """Build a ``KeyProfile`` from a key declaration.

    ``keys`` is a ``programs.yaml`` dd ``keys`` mapping (``primary``,
    ``alternates``), a ``datasets.yaml`` dataset entry (``key``,
    ``alternate_keys``), or a bare ``{offset, length}`` primary key."""
    if not isinstance(keys, Mapping):
        raise BuildError(f"key declaration must be a mapping, got {keys!r}")
    if "primary" in keys:
        primary, alternates = keys["primary"], _entries(keys.get("alternates"), "alternates")
    elif "key" in keys:
        primary = keys["key"]
        alternates = _entries(keys.get("alternate_keys"), "alternate_keys")
    elif "offset" in keys and "length" in keys:
        primary, alternates = keys, []
    else:
        raise BuildError(f"key declaration has no primary key: {dict(keys)!r}")
    parsed = []
    for alternate in alternates:
        offset, length = _key_pair(alternate, "alternate key")
        duplicates = alternate.get("duplicates")
        if not isinstance(duplicates, bool):
            raise BuildError(f"alternate key duplicates must be true or false, got {duplicates!r}")
        parsed.append((offset, length, duplicates))
    return KeyProfile(record_length, _key_pair(primary, "primary key"), tuple(parsed))


# --------------------------------------------------------------------------
# Configuration
# --------------------------------------------------------------------------


def _load_config(repo_root: Path, relative: str) -> Any:
    path = repo_root / relative
    try:
        data = load_yaml(path)
    except FileNotFoundError:
        raise BuildError(f"{relative} is missing") from None
    except YAMLLoadError as exc:  # its message names the file, and the line where known
        raise BuildError(str(exc)) from None
    except Exception as exc:  # unreadable: permissions, a directory, an I/O error
        raise BuildError(f"cannot read {relative}: {exc}") from exc
    if not isinstance(data, Mapping):
        raise BuildError(f"{relative} does not hold a mapping")
    return data


def _section(value: Any, what: str) -> Mapping[str, Any]:
    """A configuration value that must be a mapping: an absent one (``None``)
    is empty, and any other value raises ``BuildError`` naming ``what``."""
    if value is None:
        return {}
    if not isinstance(value, Mapping):
        raise BuildError(f"{what} must be a mapping, got {value!r}")
    return value


def _entries(value: Any, what: str) -> list[Any]:
    """A configuration value that must be a list: an absent one (``None``) is
    empty, and any other value raises ``BuildError`` naming ``what``."""
    if value is None:
        return []
    if not isinstance(value, list):
        raise BuildError(f"{what} must be a list, got {value!r}")
    return value


def _define_number(digits: str, what: str) -> int:
    """The digits of a ``jobs.yaml`` DEFINE operand as a number. ``int``
    refuses one of more digits than Python's integer string conversion limit
    (``sys.get_int_max_str_digits()``, 4300 by default) with ``ValueError``;
    that refusal raises ``BuildError`` naming ``what``."""
    try:
        return int(digits)
    except ValueError:
        raise BuildError(
            f"{what} has {len(digits)} digits, too many to read as a number"
        ) from None


def _programs(repo_root: Path) -> dict[str, Mapping[str, Any]]:
    """The ``programs`` mapping of ``programs.yaml``, every key of which is a
    unit id (``_UNIT_ID_RE``); any other key is refused before anything is
    planned or written."""
    data = _load_config(repo_root, _PROGRAMS_YAML)
    if data.get("schema_version") != 1 or not isinstance(data.get("programs"), Mapping):
        raise BuildError(f"{_PROGRAMS_YAML} must have schema_version 1 and a programs mapping")
    programs = dict(data["programs"])
    for unit in programs:
        if not isinstance(unit, str) or not _UNIT_ID_RE.fullmatch(unit):
            raise BuildError(
                f"{_PROGRAMS_YAML} unit {unit!r} is not a batch program name: one to eight "
                f"upper-case letters and digits, starting with a letter"
            )
    return programs


def _datasets(repo_root: Path) -> dict[str, Mapping[str, Any]]:
    data = _load_config(repo_root, _DATASETS_YAML)
    entries = data.get("datasets")
    if not isinstance(entries, list):
        raise BuildError(f"{_DATASETS_YAML} has no datasets list")
    result = {}
    for entry in entries:
        if not isinstance(entry, Mapping) or not isinstance(entry.get("name"), str):
            raise BuildError(f"{_DATASETS_YAML} holds a dataset without a name: {entry!r}")
        result[entry["name"]] = entry
    return result


def _layout_lengths(repo_root: Path) -> dict[str, int]:
    records = _load_config(repo_root, _LAYOUTS_YAML).get("records")
    if not isinstance(records, Mapping):
        raise BuildError(f"{_LAYOUTS_YAML} has no records mapping")
    return {
        name: record["length"]
        for name, record in records.items()
        if isinstance(record, Mapping) and isinstance(record.get("length"), int)
    }


def _dataset_record_length(name: str, entry: Mapping[str, Any], layouts: Mapping[str, int]) -> int:
    length = _non_negative_int(entry.get("record_length"), f"dataset {name} record_length")
    layout = entry.get("layout")
    if layout is not None:
        if not isinstance(layout, str) or layout not in layouts:
            raise BuildError(f"dataset {name} names layout {layout}, which {_LAYOUTS_YAML} lacks")
        if layouts[layout] != length:
            raise BuildError(
                f"dataset {name} has record_length {length} but layout {layout} is "
                f"{layouts[layout]} bytes"
            )
    return length


# IDCAMS statements continue on the next line after a trailing '-' or '+'.
_DEFINE_RE = re.compile(r"\bDEFINE\s+(CLUSTER|ALTERNATEINDEX|AIX)\b")
_NAME_RE = re.compile(r"\bNAME\s*\(\s*([^\s()]+)\s*\)")
_RELATE_RE = re.compile(r"\bRELATE\s*\(\s*([^\s()]+)\s*\)")
# Both IDCAMS pairs are written "(a b)" or "(a,b)"; KEYS is length first, then offset.
_KEYS_RE = re.compile(r"\bKEYS\s*\(\s*(\d+)\s*[\s,]\s*(\d+)\s*\)")
_RECORDSIZE_RE = re.compile(r"\bRECORDSIZE\s*\(\s*(\d+)\s*[\s,]\s*(\d+)\s*\)")
_NONUNIQUE_RE = re.compile(r"\bNONUNIQUEKEY\b")


def _idcams_statements(lines: Iterable[Any]) -> list[str]:
    statements: list[str] = []
    pending: list[str] = []
    for raw in lines:
        if not isinstance(raw, str):
            raise BuildError(f"{_JOBS_YAML} holds a sysin line that is not text: {raw!r}")
        line = raw.rstrip()
        if line.endswith(("-", "+")):
            pending.append(line[:-1])
            continue
        pending.append(line)
        statement = " ".join(pending).strip()
        if statement:
            statements.append(statement)
        pending = []
    if pending and " ".join(pending).strip():
        statements.append(" ".join(pending).strip())
    return statements


def _job_defines(jobs: Mapping[str, Any]) -> tuple[list[tuple[str, str, str]], dict[str, str]]:
    """Every DEFINE CLUSTER and DEFINE ALTERNATEINDEX statement of the step
    tables as ``(job, kind, statement)``, and each DD's DSN mapped to its
    logical dataset."""
    defines: list[tuple[str, str, str]] = []
    dsn_logical: dict[str, str] = {}
    for job_name in jobs:
        if not isinstance(job_name, str):
            raise BuildError(f"{_JOBS_YAML} job name {job_name!r} is not text")
    for job_name in sorted(jobs):
        job = jobs[job_name]
        steps = job.get("steps") if isinstance(job, Mapping) else None
        if not isinstance(steps, list):
            raise BuildError(f"{_JOBS_YAML} job {job_name} has no steps list")
        for step in steps:
            if not isinstance(step, Mapping):
                raise BuildError(f"{_JOBS_YAML} job {job_name} holds a step that is not a mapping")
            where = f"{_JOBS_YAML} job {job_name} step {step.get('step')}"
            for ddname, dd in _section(step.get("dd"), f"{where} dd").items():
                if not isinstance(dd, Mapping):
                    continue
                for entry in [dd, *_entries(dd.get("concat"), f"{where} dd {ddname} concat")]:
                    if isinstance(entry, Mapping) and entry.get("dsn") and entry.get("logical"):
                        dsn, logical = entry["dsn"], entry["logical"]
                        if not isinstance(dsn, str) or not isinstance(logical, str):
                            raise BuildError(
                                f"{where} dd {ddname}: dsn and logical must be text, got "
                                f"{dsn!r} and {logical!r}"
                            )
                        dsn_logical.setdefault(dsn, logical)
            if step.get("kind") != "idcams":
                continue
            params = _section(step.get("params"), f"{where} params")
            for statement in _idcams_statements(_entries(params.get("sysin"), f"{where} sysin")):
                match = _DEFINE_RE.search(statement)
                if match:
                    kind = "CLUSTER" if match.group(1) == "CLUSTER" else "ALTERNATEINDEX"
                    defines.append((job_name, kind, statement[match.start():]))
    return defines, dsn_logical


def _job_profiles(
    jobs: Mapping[str, Any], datasets: Mapping[str, Mapping[str, Any]], layouts: Mapping[str, int]
) -> set[KeyProfile]:
    defines, dsn_logical = _job_defines(jobs)
    clusters: dict[str, KeyProfile] = {}
    aixes: list[tuple[str, str]] = []
    for job_name, kind, statement in defines:
        name = _NAME_RE.search(statement)
        keys = _KEYS_RE.search(statement)
        if name is None:
            raise BuildError(
                f"{_JOBS_YAML} job {job_name}: DEFINE {kind} without NAME: {statement}"
            )
        if kind == "ALTERNATEINDEX":
            aixes.append((job_name, statement))
            continue
        if keys is None:
            continue  # not a KSDS: no indexed file to build
        size = _RECORDSIZE_RE.search(statement)
        if size is None:
            raise BuildError(
                f"{_JOBS_YAML} job {job_name}: DEFINE CLUSTER {name.group(1)} has KEYS "
                f"but no RECORDSIZE"
            )
        where = f"{_JOBS_YAML} job {job_name}: DEFINE CLUSTER {name.group(1)}"
        profile = KeyProfile(
            _define_number(size.group(2), f"{where} RECORDSIZE"),
            (
                _define_number(keys.group(2), f"{where} KEYS offset"),
                _define_number(keys.group(1), f"{where} KEYS length"),
            ),
        )
        if clusters.setdefault(name.group(1), profile) != profile:
            raise BuildError(
                f"{_JOBS_YAML}: cluster {name.group(1)} is defined with two key profiles, "
                f"{clusters[name.group(1)].name} and {profile.name}"
            )
    profiles = set(clusters.values())
    for job_name, statement in aixes:
        keys = _KEYS_RE.search(statement)
        relate = _RELATE_RE.search(statement)
        if keys is None or relate is None:
            raise BuildError(
                f"{_JOBS_YAML} job {job_name}: DEFINE ALTERNATEINDEX needs KEYS and "
                f"RELATE: {statement}"
            )
        base = clusters.get(relate.group(1))
        if base is None:
            logical = dsn_logical.get(relate.group(1))
            entry = datasets.get(logical) if logical else None
            if entry is None or entry.get("organization") != "ksds":
                raise BuildError(
                    f"{_JOBS_YAML} job {job_name}: the base cluster {relate.group(1)} of an "
                    f"alternate index is neither defined in a job nor a ksds dataset"
                )
            record_length = _dataset_record_length(logical, entry, layouts)
            base = profile_for(record_length, {"key": entry.get("key")})
        where = f"{_JOBS_YAML} job {job_name}: DEFINE ALTERNATEINDEX"
        alternate = (
            _define_number(keys.group(2), f"{where} KEYS offset"),
            _define_number(keys.group(1), f"{where} KEYS length"),
            bool(_NONUNIQUE_RE.search(statement)),
        )
        profiles.add(base.with_alternates([alternate]))
    return profiles


def static_profiles(repo_root: Path | str) -> tuple[KeyProfile, ...]:
    """The key profiles compiled during ``ensure_built``, sorted.

    The union of each indexed dd's declaration in ``programs.yaml``; each
    ``ksds`` dataset of ``datasets.yaml``, primary key only and with all its
    alternate keys; and each DEFINE CLUSTER, and each DEFINE ALTERNATEINDEX
    with its base cluster's primary key, of the ``jobs.yaml`` step tables."""
    root = Path(repo_root).resolve()
    datasets = _datasets(root)
    layouts = _layout_lengths(root)
    profiles: set[KeyProfile] = set()
    for unit, config in sorted(_programs(root).items()):
        dds = _section(
            _section(config, f"{_PROGRAMS_YAML} unit {unit}").get("dd"),
            f"{_PROGRAMS_YAML} {unit} dd",
        )
        for ddname in dds:
            if not isinstance(ddname, str):
                raise BuildError(f"{_PROGRAMS_YAML} {unit} dd name {ddname!r} is not text")
        for ddname, dd in sorted(dds.items()):
            if not isinstance(dd, Mapping) or dd.get("organization") != "indexed":
                continue
            if not isinstance(dd.get("keys"), Mapping):
                raise BuildError(
                    f"{_PROGRAMS_YAML} {unit} dd {ddname} is indexed but declares no keys"
                )
            profile = profile_for(dd.get("record_length"), dd["keys"])
            logical = dd.get("logical")
            if logical is not None and not isinstance(logical, str):
                raise BuildError(
                    f"{_PROGRAMS_YAML} {unit} dd {ddname} logical must be a dataset name, "
                    f"got {logical!r}"
                )
            entry = datasets.get(logical)
            dataset_length = (
                _dataset_record_length(entry["name"], entry, layouts) if entry is not None else None
            )
            if dataset_length not in (None, profile.record_length):
                raise BuildError(
                    f"{_PROGRAMS_YAML} {unit} dd {ddname} has record_length "
                    f"{profile.record_length} but dataset {entry['name']} is {dataset_length} bytes"
                )
            profiles.add(profile)
    for name, entry in sorted(datasets.items()):
        if entry.get("organization") != "ksds":
            continue
        profile = profile_for(_dataset_record_length(name, entry, layouts), entry)
        profiles.add(profile)
        profiles.add(profile.with_alternates(()))
    jobs = _load_config(root, _JOBS_YAML).get("jobs")
    if not isinstance(jobs, Mapping):
        raise BuildError(f"{_JOBS_YAML} has no jobs mapping")
    profiles |= _job_profiles(jobs, datasets, layouts)
    return tuple(sorted(profiles))


# --------------------------------------------------------------------------
# Generated COBOL: loader, unloader, clock probe
# --------------------------------------------------------------------------


def _cobol(lines: Iterable[str]) -> str:
    """Join fixed-format lines (code in columns 8-72) into a source text."""
    text = []
    for line in lines:
        if len(line) > _COBOL_LINE_LIMIT:
            raise BuildError(f"generated COBOL line exceeds column {_COBOL_LINE_LIMIT}: {line!r}")
        text.append(line)
    return "\n".join(text) + "\n"


def _kfile_select(profile: KeyProfile) -> list[str]:
    lines = [
        f"           SELECT KFILE ASSIGN TO {KSDS_DD}",
        "               ORGANIZATION IS INDEXED",
        "               ACCESS MODE IS DYNAMIC",
        "               RECORD KEY IS K0-KEY",
    ]
    for index, (_, _, duplicates) in enumerate(profile.alternates, start=1):
        lines.append(f"               ALTERNATE RECORD KEY IS K{index}-KEY")
        if duplicates:
            lines.append("                   WITH DUPLICATES")
    lines.append("               FILE STATUS IS WS-KSTAT.")
    return lines


def _kfile_fd(profile: KeyProfile) -> list[str]:
    # One 01-level per key over the same record area, so keys may overlap.
    lines = [f"       FD  KFILE RECORD CONTAINS {profile.record_length} CHARACTERS."]
    keys = [profile.primary, *(alternate[:2] for alternate in profile.alternates)]
    for index, (offset, length) in enumerate(keys):
        lines.append(f"       01  K{index}-REC.")
        if offset:
            lines.append(f"           05  FILLER              PIC X({offset}).")
        lines.append(f"           05  K{index}-KEY{' ' * (15 - len(str(index)))}PIC X({length}).")
        rest = profile.record_length - offset - length
        if rest:
            lines.append(f"           05  FILLER              PIC X({rest}).")
    return lines


def _header(program: str, profile: KeyProfile, purpose: str) -> list[str]:
    return [
        "      *****************************************************************",
        f"      * {program} for key profile",
        f"      *   {profile.name}",
        f"      * {purpose}",
        "      * Generated by tests/adapters/gnucobol_batch/build.py,",
        f"      * template version {TEMPLATE_VERSION}.",
        "      *****************************************************************",
        "       IDENTIFICATION DIVISION.",
        f"       PROGRAM-ID. {program}.",
        "       ENVIRONMENT DIVISION.",
        "       INPUT-OUTPUT SECTION.",
        "       FILE-CONTROL.",
    ]


_COMMON_WORKING_STORAGE = [
    "       WORKING-STORAGE SECTION.",
    "       01  WS-KSTAT                PIC XX VALUE SPACES.",
    "       01  WS-SSTAT                PIC XX VALUE SPACES.",
    "       01  WS-ARGS                 PIC X(80) VALUE SPACES.",
    "       01  WS-MODE                 PIC X(10) VALUE SPACES.",
    "       01  WS-ARG2                 PIC X(10) VALUE SPACES.",
    "       01  WS-ARG3                 PIC X(10) VALUE SPACES.",
    "       01  WS-RECNO                PIC 9(9) VALUE 0.",
    "       01  WS-COUNT                PIC 9(9) VALUE 0.",
    "       01  WS-EOF                  PIC X VALUE 'N'.",
    "       01  WS-KOPEN                PIC X VALUE 'N'.",
    "       01  WS-SOPEN                PIC X VALUE 'N'.",
    "       01  WS-CHECK-NAME           PIC X(8) VALUE SPACES.",
    "       01  WS-CHECK-DETAILS.",
    "           05  WS-CHECK-SIZE       PIC X(8) COMP-X.",
    "           05  WS-CHECK-DATE       PIC X(4) COMP-X.",
    "           05  WS-CHECK-TIME       PIC X(4) COMP-X.",
    "       01  WS-CHECK-RC             PIC S9(9) VALUE 0",
    "                                   SIGN LEADING SEPARATE.",
]

_PARSE_ARGUMENTS = [
    "           ACCEPT WS-ARGS FROM COMMAND-LINE",
    "           UNSTRING WS-ARGS DELIMITED BY ALL SPACE",
    "               INTO WS-MODE WS-ARG2 WS-ARG3",
    "           END-UNSTRING",
]

_FAIL_PARAGRAPH = [
    "       9000-FAIL.",
    "           IF WS-KOPEN = 'Y'",
    "               CLOSE KFILE",
    "           END-IF",
    "           IF WS-SOPEN = 'Y'",
    "               CLOSE SFILE",
    "           END-IF",
    f"           MOVE {EXIT_FAILED} TO RETURN-CODE",
    "           STOP RUN.",
    "       9100-USAGE.",
    "           DISPLAY 'USAGE: ' WS-ARGS",
    f"           MOVE {EXIT_USAGE} TO RETURN-CODE",
    "           STOP RUN.",
]


def _check_paragraphs(profile: KeyProfile) -> list[str]:
    """``8000-CHECK-FILE`` and ``8100-KFILE-PRESENT-35`` of both programs.

    ``CBL_CHECK_FILE_EXIST`` maps the ASSIGN name in WS-CHECK-NAME as OPEN
    does and sets WS-CHECK-SIZE; its RETURN-CODE (0 present, 35 absent) is
    kept in WS-CHECK-RC and reset, so it never becomes the exit status.
    ``OPEN INPUT`` gives status 35 for an absent file and for a present one
    without a declared alternate's file; 8100 reports the second case, which
    a profile without alternates cannot attribute to a missing index."""
    missing_index = ["               ' ALTERNATE INDEX MISSING'"] if profile.alternates else []
    return [
        "       8000-CHECK-FILE.",
        "           CALL 'CBL_CHECK_FILE_EXIST' USING WS-CHECK-NAME",
        "               WS-CHECK-DETAILS",
        "           END-CALL",
        "           MOVE RETURN-CODE TO WS-CHECK-RC",
        "           MOVE 0 TO RETURN-CODE.",
        "       8100-KFILE-PRESENT-35.",
        f"           DISPLAY '{KSDS_DD} OPEN STATUS 35'",
        *missing_index,
        "           PERFORM 9000-FAIL.",
    ]


def _loader_source(profile: KeyProfile) -> str:
    """The COBOL source of the loader of ``profile``."""
    length = profile.record_length
    return _cobol([
        *_header(_LOADER_PROGRAM, profile, "LOAD | APPEND | CREATE an indexed file from SEQIN."),
        *_kfile_select(profile),
        f"           SELECT SFILE ASSIGN TO {SEQIN_DD}",
        "               ORGANIZATION IS SEQUENTIAL",
        "               FILE STATUS IS WS-SSTAT.",
        "       DATA DIVISION.",
        "       FILE SECTION.",
        *_kfile_fd(profile),
        f"       FD  SFILE RECORD CONTAINS {length} CHARACTERS.",
        f"       01  S-REC                   PIC X({length}).",
        *_COMMON_WORKING_STORAGE,
        "       PROCEDURE DIVISION.",
        "       0000-MAIN.",
        *_PARSE_ARGUMENTS,
        "           IF WS-ARG2 NOT = SPACES",
        "               PERFORM 9100-USAGE",
        "           END-IF",
        "           EVALUATE WS-MODE",
        "               WHEN 'LOAD'",
        "                   PERFORM 1000-OPEN-SEQIN",
        "                   OPEN OUTPUT KFILE",
        "                   PERFORM 1100-CHECK-KFILE-OPEN",
        "                   PERFORM 2000-COPY-RECORDS",
        "               WHEN 'APPEND'",
        "                   PERFORM 1000-OPEN-SEQIN",
        "                   PERFORM 1200-OPEN-KFILE-APPEND",
        "                   PERFORM 1100-CHECK-KFILE-OPEN",
        "                   PERFORM 2000-COPY-RECORDS",
        "               WHEN 'CREATE'",
        "                   OPEN OUTPUT KFILE",
        "                   PERFORM 1100-CHECK-KFILE-OPEN",
        "                   PERFORM 3000-CLOSE-KFILE",
        "                   DISPLAY 'CREATED'",
        "               WHEN OTHER",
        "                   PERFORM 9100-USAGE",
        "           END-EVALUATE",
        "           STOP RUN.",
        # libcob reads a short final record with status 00 and keeps the rest
        # of the previous one, so SEQIN must hold whole records; this runs
        # before KFILE is opened, which OPEN OUTPUT would recreate.
        "       1000-OPEN-SEQIN.",
        "           OPEN INPUT SFILE",
        "           IF WS-SSTAT NOT = '00'",
        "               DISPLAY 'SEQIN OPEN STATUS ' WS-SSTAT",
        "               PERFORM 9000-FAIL",
        "           END-IF",
        "           MOVE 'Y' TO WS-SOPEN",
        f"           MOVE '{SEQIN_DD}' TO WS-CHECK-NAME",
        "           PERFORM 8000-CHECK-FILE",
        "           IF WS-CHECK-RC NOT = 0",
        f"               DISPLAY '{SEQIN_DD} CHECK STATUS ' WS-CHECK-RC",
        "               PERFORM 9000-FAIL",
        "           END-IF",
        f"           IF FUNCTION MOD(WS-CHECK-SIZE, {length}) NOT = 0",
        f"               DISPLAY '{SEQIN_DD} SIZE ' WS-CHECK-SIZE",
        f"                   ' NOT A MULTIPLE OF {length}'",
        "               PERFORM 9000-FAIL",
        "           END-IF.",
        "       1100-CHECK-KFILE-OPEN.",
        "           IF WS-KSTAT NOT = '00'",
        f"               DISPLAY '{KSDS_DD} OPEN STATUS ' WS-KSTAT",
        "               PERFORM 9000-FAIL",
        "           END-IF",
        "           MOVE 'Y' TO WS-KOPEN.",
        # OPEN I-O on a present file would create a missing alternate's file
        # holding only the appended records, so OPEN INPUT probes it first.
        "       1200-OPEN-KFILE-APPEND.",
        f"           MOVE '{KSDS_DD}' TO WS-CHECK-NAME",
        "           PERFORM 8000-CHECK-FILE",
        "           EVALUATE WS-CHECK-RC",
        "               WHEN 35",
        "                   OPEN OUTPUT KFILE",
        "               WHEN 0",
        "                   OPEN INPUT KFILE",
        "                   EVALUATE WS-KSTAT",
        "                       WHEN '00'",
        "                           MOVE 'Y' TO WS-KOPEN",
        "                           PERFORM 3000-CLOSE-KFILE",
        "                           OPEN I-O KFILE",
        "                       WHEN '35'",
        "                           PERFORM 8100-KFILE-PRESENT-35",
        "                   END-EVALUATE",
        "               WHEN OTHER",
        f"                   DISPLAY '{KSDS_DD} CHECK STATUS ' WS-CHECK-RC",
        "                   PERFORM 9000-FAIL",
        "           END-EVALUATE.",
        "       2000-COPY-RECORDS.",
        "           PERFORM UNTIL WS-EOF = 'Y'",
        "               READ SFILE",
        "               EVALUATE WS-SSTAT",
        "                   WHEN '00'",
        "                       ADD 1 TO WS-RECNO",
        "                       PERFORM 2100-WRITE-RECORD",
        "                   WHEN '10'",
        "                       MOVE 'Y' TO WS-EOF",
        "                   WHEN OTHER",
        "                       ADD 1 TO WS-RECNO",
        "                       DISPLAY 'SEQIN STATUS ' WS-SSTAT",
        "                           ' AT ' WS-RECNO",
        "                       PERFORM 9000-FAIL",
        "               END-EVALUATE",
        "           END-PERFORM",
        "           CLOSE SFILE",
        "           MOVE 'N' TO WS-SOPEN",
        "           IF WS-SSTAT NOT = '00'",
        f"               DISPLAY '{SEQIN_DD} CLOSE STATUS ' WS-SSTAT",
        "               PERFORM 9000-FAIL",
        "           END-IF",
        "           PERFORM 3000-CLOSE-KFILE",
        "           DISPLAY 'LOADED ' WS-COUNT.",
        "       2100-WRITE-RECORD.",
        "           WRITE K0-REC FROM S-REC",
        "           EVALUATE WS-KSTAT",
        "               WHEN '00'",
        "               WHEN '02'",
        "                   ADD 1 TO WS-COUNT",
        "               WHEN '22'",
        "                   DISPLAY 'DUPKEY ' WS-RECNO",
        "               WHEN OTHER",
        "                   DISPLAY 'STATUS ' WS-KSTAT ' AT ' WS-RECNO",
        "                   PERFORM 9000-FAIL",
        "           END-EVALUATE.",
        "       3000-CLOSE-KFILE.",
        "           CLOSE KFILE",
        "           MOVE 'N' TO WS-KOPEN",
        "           IF WS-KSTAT NOT = '00'",
        f"               DISPLAY '{KSDS_DD} CLOSE STATUS ' WS-KSTAT",
        "               PERFORM 9000-FAIL",
        "           END-IF.",
        *_check_paragraphs(profile),
        *_FAIL_PARAGRAPH,
    ])


def _unloader_source(profile: KeyProfile) -> str:
    """The COBOL source of the unloader of ``profile``."""
    length = profile.record_length
    select_alternate = []
    start_alternate = []
    for index in range(1, len(profile.alternates) + 1):
        select_alternate += [
            f"                       WHEN '{index}'",
            f"                           MOVE {index} TO WS-ALT",
        ]
        start_alternate += [
            f"               WHEN {index}",
            f"                   MOVE LOW-VALUES TO K{index}-KEY",
            f"                   START KFILE KEY IS NOT LESS THAN K{index}-KEY",
        ]
    # Without alternates "ALT <n>" falls to the usage error of WHEN OTHER.
    alternate_argument = [
        "               WHEN WS-MODE = 'ALT' AND WS-ARG3 = SPACES",
        "                   EVALUATE WS-ARG2",
        *select_alternate,
        "                       WHEN OTHER",
        "                           PERFORM 9100-USAGE",
        "                   END-EVALUATE",
    ] if profile.alternates else []
    return _cobol([
        *_header(_UNLOADER_PROGRAM, profile, "PRIMARY | ALT <n>: copy an indexed file to SEQOUT."),
        *_kfile_select(profile),
        f"           SELECT SFILE ASSIGN TO {SEQOUT_DD}",
        "               ORGANIZATION IS SEQUENTIAL",
        "               FILE STATUS IS WS-SSTAT.",
        "       DATA DIVISION.",
        "       FILE SECTION.",
        *_kfile_fd(profile),
        f"       FD  SFILE RECORD CONTAINS {length} CHARACTERS.",
        f"       01  S-REC                   PIC X({length}).",
        *_COMMON_WORKING_STORAGE,
        "       01  WS-ALT                  PIC 9(2) VALUE 0.",
        "       PROCEDURE DIVISION.",
        "       0000-MAIN.",
        *_PARSE_ARGUMENTS,
        "           EVALUATE TRUE",
        "               WHEN WS-MODE = 'PRIMARY' AND WS-ARG2 = SPACES",
        "                   MOVE 0 TO WS-ALT",
        *alternate_argument,
        "               WHEN OTHER",
        "                   PERFORM 9100-USAGE",
        "           END-EVALUATE",
        "           OPEN INPUT KFILE",
        "           EVALUATE WS-KSTAT",
        "               WHEN '00'",
        "                   MOVE 'Y' TO WS-KOPEN",
        "               WHEN '35'",
        "                   PERFORM 1000-CHECK-NOFILE",
        "               WHEN OTHER",
        f"                   DISPLAY '{KSDS_DD} OPEN STATUS ' WS-KSTAT",
        "                   PERFORM 9000-FAIL",
        "           END-EVALUATE",
        "           OPEN OUTPUT SFILE",
        "           IF WS-SSTAT NOT = '00'",
        f"               DISPLAY '{SEQOUT_DD} OPEN STATUS ' WS-SSTAT",
        "               PERFORM 9000-FAIL",
        "           END-IF",
        "           MOVE 'Y' TO WS-SOPEN",
        "           EVALUATE WS-ALT",
        "               WHEN 0",
        "                   MOVE LOW-VALUES TO K0-KEY",
        "                   START KFILE KEY IS NOT LESS THAN K0-KEY",
        *start_alternate,
        "           END-EVALUATE",
        "           EVALUATE WS-KSTAT",
        "               WHEN '00'",
        "                   CONTINUE",
        "               WHEN '23'",
        "                   MOVE 'Y' TO WS-EOF",
        "               WHEN OTHER",
        f"                   DISPLAY '{KSDS_DD} START STATUS ' WS-KSTAT",
        "                   PERFORM 9000-FAIL",
        "           END-EVALUATE",
        "           PERFORM UNTIL WS-EOF = 'Y'",
        "               READ KFILE NEXT RECORD",
        "               EVALUATE WS-KSTAT",
        "                   WHEN '00'",
        "                   WHEN '02'",
        "                       ADD 1 TO WS-RECNO",
        "                       PERFORM 2000-WRITE-RECORD",
        "                   WHEN '10'",
        "                       MOVE 'Y' TO WS-EOF",
        "                   WHEN OTHER",
        "                       ADD 1 TO WS-RECNO",
        "                       DISPLAY 'STATUS ' WS-KSTAT ' AT ' WS-RECNO",
        "                       PERFORM 9000-FAIL",
        "               END-EVALUATE",
        "           END-PERFORM",
        "           CLOSE KFILE",
        "           MOVE 'N' TO WS-KOPEN",
        "           IF WS-KSTAT NOT = '00'",
        f"               DISPLAY '{KSDS_DD} CLOSE STATUS ' WS-KSTAT",
        "               PERFORM 9000-FAIL",
        "           END-IF",
        "           CLOSE SFILE",
        "           MOVE 'N' TO WS-SOPEN",
        "           IF WS-SSTAT NOT = '00'",
        f"               DISPLAY '{SEQOUT_DD} CLOSE STATUS ' WS-SSTAT",
        "               PERFORM 9000-FAIL",
        "           END-IF",
        "           DISPLAY 'UNLOADED ' WS-COUNT",
        "           STOP RUN.",
        # NOFILE only when the file itself is absent.
        "       1000-CHECK-NOFILE.",
        f"           MOVE '{KSDS_DD}' TO WS-CHECK-NAME",
        "           PERFORM 8000-CHECK-FILE",
        "           EVALUATE WS-CHECK-RC",
        "               WHEN 35",
        "                   DISPLAY 'NOFILE'",
        f"                   MOVE {EXIT_NOFILE} TO RETURN-CODE",
        "                   STOP RUN",
        "               WHEN 0",
        "                   PERFORM 8100-KFILE-PRESENT-35",
        "               WHEN OTHER",
        f"                   DISPLAY '{KSDS_DD} CHECK STATUS ' WS-CHECK-RC",
        "                   PERFORM 9000-FAIL",
        "           END-EVALUATE.",
        "       2000-WRITE-RECORD.",
        "           WRITE S-REC FROM K0-REC",
        "           IF WS-SSTAT NOT = '00'",
        f"               DISPLAY '{SEQOUT_DD} STATUS ' WS-SSTAT ' AT ' WS-RECNO",
        "               PERFORM 9000-FAIL",
        "           END-IF",
        "           ADD 1 TO WS-COUNT.",
        *_check_paragraphs(profile),
        *_FAIL_PARAGRAPH,
    ])


def _clock_probe_source() -> str:
    return _cobol([
        "      * Prints FUNCTION CURRENT-DATE, so build.py can tell how libcob",
        "      * reads the fraction of COB_CURRENT_DATE.",
        "       IDENTIFICATION DIVISION.",
        f"       PROGRAM-ID. {_CLOCK_PROGRAM}.",
        "       PROCEDURE DIVISION.",
        "           DISPLAY FUNCTION CURRENT-DATE",
        "           STOP RUN.",
    ])


# --------------------------------------------------------------------------
# Toolchain
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class _Toolchain:
    cobc: str
    cc: str
    cobc_version: str
    cc_version: str
    bin_dirs: tuple[str, ...]
    lib_dirs: tuple[str, ...]
    overrides: tuple[tuple[str, str], ...]

    def env(self, home: Path, **extra: str) -> dict[str, str]:
        """A fresh environment for a compile or probe; nothing else of the
        caller's environment is inherited."""
        env = {
            "PATH": os.pathsep.join(self.bin_dirs),
            "LANG": "C",
            "LC_ALL": "C",
            "TZ": "UTC",
            "HOME": str(home),
        }
        if self.lib_dirs:
            env["LD_LIBRARY_PATH"] = os.pathsep.join(self.lib_dirs)
        env.update(self.overrides)
        env.update(extra)
        return env


def _tool_output(argv: Sequence[str], env: Mapping[str, str]) -> str:
    """The stdout of one toolchain probe (``cobc --version``, ``cobc --info``,
    ``cc --version``). A probe that cannot start, exits non-zero or does not
    finish within ``_TOOL_PROBE_TIMEOUT_S`` leaves the toolchain unusable:
    ``ToolchainUnavailable``.

    The probe starts a new session, so it and every process it starts form a
    process group of their own. When it overruns, or an exception such as
    ``KeyboardInterrupt`` interrupts the wait, that group is killed, its
    output drained and its leader reaped within ``_REAP_TIMEOUT_S``
    (``_kill_group``), so no process of the probe outlives it; the error
    names any teardown problem, and the interrupting exception is re-raised.
    In the main thread SIGTERM and SIGHUP interrupt the wait the same way, as
    ``_Terminated`` (``_stop_signals_raise``); once the previous dispositions
    are restored, the signal is acted on as its disposition would have
    (``_resume_signal``): under ``SIG_DFL`` the process dies by it, and a
    handler that lets the process go on gets ``BuildError``. A stopped probe
    is no verdict on the toolchain, so ``_probe_toolchain`` does not memoize
    that error."""
    command = shlex.join(argv)
    stopped: tuple[int, Any] | None = None
    try:
        with _stop_signals_raise():
            try:
                process = subprocess.Popen(
                    list(argv), env=dict(env), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE, start_new_session=True,
                )
            except OSError as exc:
                raise ToolchainUnavailable(f"cannot run {command}: {exc}. {_SETUP_HINT}") from None
            try:
                stdout, stderr = process.communicate(timeout=_TOOL_PROBE_TIMEOUT_S)
            except subprocess.TimeoutExpired as exc:
                _, _, note = _kill_group(process, exc.stdout or b"", exc.stderr or b"")
                raise ToolchainUnavailable(
                    f"cannot run {command}: it did not finish within {_TOOL_PROBE_TIMEOUT_S} "
                    f"s{note}. {_SETUP_HINT}"
                ) from None
            except BaseException:
                # KeyboardInterrupt, SIGTERM or SIGHUP (_Terminated), or any other
                # exception: kill the probe's group, then let it go on.
                _kill_group(process, b"", b"")
                raise
    except _Terminated as exc:
        stopped = exc.signum, exc.disposition
    if stopped is not None:
        # Outside the handler, so what follows does not chain _Terminated.
        _resume_signal(*stopped)
    if process.returncode != 0:
        errors = stderr.decode("utf-8", "replace").strip()
        raise ToolchainUnavailable(
            f"{command} exited {process.returncode}: {errors}. {_SETUP_HINT}"
        )
    return stdout.decode("utf-8", "replace").strip()


def _info_entries(info: str) -> dict[str, str]:
    """``cobc --info`` as ``{name: value}``; continuation lines are joined."""
    entries: dict[str, str] = {}
    name = None
    for line in info.splitlines():
        match = _INFO_ENTRY_RE.match(line)
        if match:
            name = match.group(1).strip()
            entries[name] = match.group(2).strip()
        elif name is not None and line.startswith((" ", "\t")) and line.strip():
            entries[name] += " " + line.strip()
        else:
            name = None
    return entries


def _existing_dirs(candidates: Iterable[str]) -> tuple[str, ...]:
    result: list[str] = []
    for candidate in candidates:
        if candidate and candidate not in result and os.path.isdir(candidate):
            result.append(candidate)
    return tuple(result)


def _toolchain() -> _Toolchain:
    overrides = tuple(
        (name, os.environ[name]) for name in _TOOLCHAIN_VARIABLES if name in os.environ
    )
    return _probe_toolchain(
        os.environ.get("PATH", os.defpath), os.environ.get("LD_LIBRARY_PATH", ""), overrides
    )


_TOOLCHAINS: dict[tuple[str, str, tuple[tuple[str, str], ...]], _Toolchain] = {}
# The message of each probe that found the toolchain unusable, by the same key.
_TOOLCHAIN_FAILURES: dict[tuple[str, str, tuple[tuple[str, str], ...]], str] = {}
_TOOLCHAIN_LOCK = threading.Lock()


def _probe_toolchain(
    path: str, ld_library_path: str, overrides: tuple[tuple[str, str], ...]
) -> _Toolchain:
    """The toolchain ``path``, ``ld_library_path`` and ``overrides`` give,
    probed once per session: a later call returns the toolchain found, or
    raises a fresh ``ToolchainUnavailable`` with the first probe's message,
    and starts no process. Concurrent callers wait for the one probe."""
    memo_key = (path, ld_library_path, overrides)
    with _TOOLCHAIN_LOCK:
        if memo_key in _TOOLCHAINS:
            return _TOOLCHAINS[memo_key]
        if memo_key in _TOOLCHAIN_FAILURES:
            raise ToolchainUnavailable(_TOOLCHAIN_FAILURES[memo_key])
        try:
            toolchain = _find_toolchain(path, ld_library_path, overrides)
        except ToolchainUnavailable as exc:
            _TOOLCHAIN_FAILURES[memo_key] = str(exc)
            raise
        _TOOLCHAINS[memo_key] = toolchain
        return toolchain


def _find_toolchain(
    path: str, ld_library_path: str, overrides: tuple[tuple[str, str], ...]
) -> _Toolchain:
    """Probe ``cobc`` and ``cc`` on ``path``, unmemoized; ``ToolchainUnavailable``
    when either is missing or unsuitable."""
    cobc = shutil.which("cobc", path=path)
    if cobc is None:
        raise ToolchainUnavailable(f"cobc is not on PATH. {_SETUP_HINT}")
    cobc = os.path.abspath(cobc)
    # The probes run in the caller's environment: it is what lets cobc find
    # libcob, whose directory the compile environment then names explicitly.
    probe_env = {**os.environ, "LANG": "C", "LC_ALL": "C"}
    cobc_version = _tool_output([cobc, "--version"], probe_env)
    first_line = cobc_version.splitlines()[0].strip() if cobc_version else ""
    if not _COBC_VERSION_RE.match(first_line):
        raise ToolchainUnavailable(
            f"{cobc} reports {first_line!r}, but GnuCOBOL 3.2 is required. {_SETUP_HINT}"
        )
    info = _tool_output([cobc, "--info"], probe_env)
    if not _BDB_RE.search(info):
        raise ToolchainUnavailable(
            f"{cobc} has no Berkeley DB indexed file handler ('indexed file handler : BDB' is "
            f"missing from cobc --info). {_SETUP_HINT}"
        )
    entries = _info_entries(info)
    cc = shutil.which("cc", path=path) or shutil.which("gcc", path=path)
    if cc is None:
        raise ToolchainUnavailable(
            f"no C compiler (cc or gcc) is on PATH; the stubs and cobc need one. {_SETUP_HINT}"
        )
    cc = os.path.abspath(cc)
    cc_version = _tool_output([cc, "--version"], probe_env)
    cob_cc = dict(overrides).get("COB_CC") or entries.get("COB_CC", "")
    cob_cc_words = cob_cc.split()
    cob_cc_path = shutil.which(cob_cc_words[0], path=path) if cob_cc_words else None
    bin_dirs = _existing_dirs([
        os.path.dirname(cobc), os.path.dirname(cc), os.path.dirname(cob_cc_path or ""),
        "/usr/local/bin", "/usr/bin", "/bin",
    ])
    lib_dirs = _existing_dirs([
        *(word[2:] for word in entries.get("COB_LIBS", "").split() if word.startswith("-L")),
        *ld_library_path.split(os.pathsep),
    ])
    return _Toolchain(cobc, cc, cobc_version, cc_version, bin_dirs, lib_dirs, overrides)


# --------------------------------------------------------------------------
# Build plan and key
# --------------------------------------------------------------------------


def _destination(relative: Any, what: str) -> str:
    """``relative``, a path a build writes relative to the directory it builds
    in, when it is a non-empty relative POSIX path with no ``..`` part, so that
    directory joined with it stays lexically inside it. Anything else raises
    ``BuildError`` naming ``what`` and the path."""
    if isinstance(relative, str) and "\0" not in relative:
        path = PurePosixPath(relative)
        if path.parts and not path.is_absolute() and ".." not in path.parts:
            return relative
    raise BuildError(f"{what} {relative!r} is not a relative path inside the build directory")


def _inside(path: Path, directory: Path, what: str) -> None:
    """Raise ``BuildError`` naming ``what`` and ``path``, a path a build
    writes, unless it lies lexically below ``directory`` (``_destination``)."""
    try:
        relative = path.relative_to(directory).as_posix()
    except ValueError:
        raise BuildError(f"{what} {str(path)!r} is not inside the build directory") from None
    _destination(relative, what)


@dataclass(frozen=True)
class _Compile:
    """One compiler invocation. ``sources`` are repository-relative, or
    relative to the build directory when ``generated``; ``artifact`` is
    relative to the build directory. The artifact and generated sources are
    checked (``_destination``) when the compile is made, so no compile whose
    output would leave the build directory exists."""

    name: str
    tool: str  # "cobc" or "cc"
    flags: tuple[str, ...]
    sources: tuple[str, ...]
    artifact: str
    generated: bool = False

    def __post_init__(self) -> None:
        _destination(self.artifact, f"the artifact of {self.name}")
        if self.generated:
            for source in self.sources:
                _destination(source, f"the generated source of {self.name}")

    def key_entry(self) -> dict[str, Any]:
        flags = [*self.flags, f"-I {COPYBOOK_DIR}"] if self.tool == "cobc" else list(self.flags)
        return {
            "artifact": self.artifact,
            "flags": flags,
            "name": self.name,
            "sources": list(self.sources),
            "tool": self.tool,
        }

    def argv(self, toolchain: _Toolchain, repo_root: Path, base: Path, cwd: Path) -> list[str]:
        """The command line, run with ``cwd`` as its working directory. The
        output and the generated sources lie in ``base``, inside ``cwd``, and
        are named relative to ``cwd``, so no text of the work root reaches
        the compiler (cobc puts its paths into the shell commands it runs);
        repository sources and ``-I app/cpy`` are named by absolute path."""
        if self.generated:
            sources = [(base / source).relative_to(cwd).as_posix() for source in self.sources]
        else:
            sources = [str(repo_root / source) for source in self.sources]
        output = ["-o", (base / self.artifact).relative_to(cwd).as_posix()]
        if self.tool == "cobc":
            include = ["-I", str(repo_root / COPYBOOK_DIR)]
            return [toolchain.cobc, *self.flags, *include, *output, *sources]
        return [toolchain.cc, *self.flags, *output, *sources]


_COBOL_EXECUTABLE = ("-x", *COMMON_FLAGS)
_COBOL_MODULE = ("-m", *COMMON_FLAGS)

# The stubs and drivers, built as their headers state: CEE3ABD and CEEDAYS as
# cobc -m modules, PARMDRV and DTCDRV as cobc -x executables, the shim and
# page0_probe with cc. CEEDAYS's -A options keep its C warning gate, because
# cobc puts -Wno-unused and -Wno-pointer-sign ahead of them. -O0 keeps the
# shim's page-zero accesses exactly as written.
_TOOL_COMPILES = (
    _Compile(
        "CEE3ABD", "cobc", _COBOL_MODULE, (f"{_STUBS_DIR}/CEE3ABD.cbl",), "modules/CEE3ABD.so"
    ),
    _Compile(
        "CEEDAYS", "cobc", ("-m", "-A", "-std=c11 -Wall -Wextra -Wunused -Wpointer-sign -Werror"),
        (f"{_STUBS_DIR}/ceedays.c",), "modules/CEEDAYS.so",
    ),
    _Compile("PARMDRV", "cobc", _COBOL_EXECUTABLE, (f"{_STUBS_DIR}/PARMDRV.cbl",), "bin/PARMDRV"),
    _Compile("DTCDRV", "cobc", _COBOL_EXECUTABLE, (f"{_STUBS_DIR}/DTCDRV.cbl",), "bin/DTCDRV"),
    _Compile(
        "psa_tiot_shim", "cc", ("-O0", "-Wall", "-Werror", "-shared", "-fPIC"),
        (f"{_STUBS_DIR}/psa_tiot_shim.c",), "lib/psa_tiot_shim.so",
    ),
    _Compile(
        "page0_probe", "cc", ("-O0", "-Wall", "-Werror", "-DPAGE0_PROBE"),
        (f"{_STUBS_DIR}/psa_tiot_shim.c",), "bin/page0_probe",
    ),
    _Compile(
        _CLOCK_PROGRAM, "cobc", _COBOL_EXECUTABLE, (f"src/{_CLOCK_PROGRAM}.cbl",),
        f"bin/{_CLOCK_PROGRAM}", generated=True,
    ),
)


def _profile_compiles(profile: KeyProfile) -> dict[str, tuple[_Compile, str]]:
    """The loader and unloader compiles of ``profile`` with their generated
    sources, relative to the profile's own directory."""
    return {
        f"src/{program}.cbl": (
            _Compile(
                program, "cobc", _COBOL_EXECUTABLE, (f"src/{program}.cbl",), program,
                generated=True,
            ),
            source(profile),
        )
        for program, source in (
            (_LOADER_PROGRAM, _loader_source),
            (_UNLOADER_PROGRAM, _unloader_source),
        )
    }


@dataclass(frozen=True)
class _Plan:
    units: dict[str, dict[str, str]]
    compiles: tuple[_Compile, ...]
    profiles: tuple[KeyProfile, ...]
    generated: dict[str, str]


def _repo_source(repo_root: Path, relative: Any, what: str) -> str:
    if (
        not isinstance(relative, str)
        or not relative
        or Path(relative).is_absolute()
        or ".." in Path(relative).parts
    ):
        raise BuildError(f"{what}: source {relative!r} is not a repository-relative path")
    if not (repo_root / relative).is_file():
        raise BuildError(f"{what}: source {relative} does not exist")
    return relative


def _string_list(value: Any, what: str) -> list[str]:
    if value is None:
        return []
    if not isinstance(value, list) or not all(isinstance(item, str) and item for item in value):
        raise BuildError(f"{what} must be a list of strings, got {value!r}")
    return list(value)


def _extra_flags(value: Any, what: str) -> list[str]:
    """A unit's ``compile.extra_flags``, refused unless each is one of
    ``_EXTRA_FLAGS``."""
    flags = _string_list(value, what)
    for flag in flags:
        if flag not in _EXTRA_FLAGS:
            raise BuildError(
                f"{what}: flag {flag!r} is not allowed; a unit may add only "
                f"{', '.join(sorted(_EXTRA_FLAGS))}"
            )
    return flags


def _plan(repo_root: Path) -> _Plan:
    programs = _programs(repo_root)
    units: dict[str, dict[str, str]] = {}
    compiles: list[_Compile] = []
    for unit in sorted(programs):
        config = programs[unit]
        if not isinstance(config, Mapping):
            raise BuildError(f"{_PROGRAMS_YAML} unit {unit} is not a mapping")
        what = f"{_PROGRAMS_YAML} unit {unit}"
        sources = [
            _repo_source(repo_root, path, what)
            for path in _string_list(config.get("sources"), f"{what} sources")
        ]
        if not sources:
            raise BuildError(f"{what} has no sources")
        run = config.get("run")
        if run == "called":
            called_by = config.get("called_by")
            caller = programs.get(called_by) if isinstance(called_by, str) else None
            if not isinstance(caller, Mapping):
                raise BuildError(
                    f"{what} is called, but its called_by {called_by!r} names no unit of "
                    f"{_PROGRAMS_YAML}"
                )
            caller_compile = _section(
                caller.get("compile"), f"{_PROGRAMS_YAML} unit {called_by} compile"
            )
            linked = _string_list(caller_compile.get("link_with"), f"{what} caller link_with")
            if unit not in linked:
                raise BuildError(f"{what} is called, but its called_by unit does not link it")
            units[unit] = {"called_by": config["called_by"], "run": run}
            continue
        compile_config = _section(config.get("compile"), f"{what} compile")
        mode = compile_config.get("mode")
        if not isinstance(run, str) or run not in _RUN_MODES or _RUN_MODES[run] != mode:
            raise BuildError(f"{what}: run {run!r} does not fit compile mode {mode!r}")
        extra_flags = _extra_flags(compile_config.get("extra_flags"), f"{what} extra_flags")
        for linked_unit in _string_list(compile_config.get("link_with"), f"{what} link_with"):
            linked_config = programs.get(linked_unit)
            if not isinstance(linked_config, Mapping):
                raise BuildError(f"{what} links {linked_unit}, which {_PROGRAMS_YAML} lacks")
            # One cobc call compiles the main unit and the units it links, so
            # they can only share one flag list.
            linked_compile = _section(
                linked_config.get("compile"), f"{_PROGRAMS_YAML} unit {linked_unit} compile"
            )
            linked_flags = _extra_flags(
                linked_compile.get("extra_flags"),
                f"{_PROGRAMS_YAML} unit {linked_unit} extra_flags",
            )
            if linked_flags != extra_flags:
                raise BuildError(
                    f"{what} links {linked_unit}, whose extra_flags {linked_flags} differ "
                    f"from {extra_flags}"
                )
            sources += [
                _repo_source(repo_root, path, linked_unit)
                for path in _string_list(linked_config.get("sources"), f"{linked_unit} sources")
            ]
        artifact = f"bin/{unit}" if mode == "executable" else f"modules/{unit}.so"
        flags = (_MODE_FLAGS[mode], *COMMON_FLAGS, *extra_flags)
        compiles.append(_Compile(unit, "cobc", flags, tuple(sources), artifact))
        units[unit] = {"artifact": artifact, "run": run}
    for tool in _TOOL_COMPILES:
        if tool.name in programs:
            raise BuildError(
                f"{_PROGRAMS_YAML} unit {tool.name} collides with a build tool of the same name"
            )
        if not tool.generated:
            _repo_source(repo_root, tool.sources[0], tool.name)
    compiles.extend(_TOOL_COMPILES)
    return _Plan(
        units=units,
        compiles=tuple(compiles),
        profiles=static_profiles(repo_root),
        generated={f"src/{_CLOCK_PROGRAM}.cbl": _clock_probe_source()},
    )


def _directory_hashes(repo_root: Path, relative: str) -> dict[str, str]:
    directory = repo_root / relative
    if not directory.is_dir():
        raise BuildError(f"{relative} is missing")
    return {
        f"{relative}/{path.name}": sha256_file(path)
        for path in sorted(directory.iterdir(), key=lambda item: item.name)
        if path.is_file()
    }


def _build_key(repo_root: Path, toolchain: _Toolchain, plan: _Plan) -> str:
    generated = {path: sha256_hex(text) for path, text in plan.generated.items()}
    for profile in plan.profiles:
        for path, (_, text) in _profile_compiles(profile).items():
            generated[f"profiles/{profile.name}/{path}"] = sha256_hex(text)
    material = {
        # Beside the sources, options and cobc --version: cc --version, because
        # the shim and page0_probe are compiled with cc directly, and the
        # caller's COB_* toolchain variables, because an override changes
        # every compile.
        "cc_version": toolchain.cc_version,
        "cobc_version": toolchain.cobc_version,
        "compiles": [compile_.key_entry() for compile_ in plan.compiles],
        "copybooks": _directory_hashes(repo_root, COPYBOOK_DIR),
        "generated": generated,
        "profiles": [profile.name for profile in plan.profiles],
        "sources": {
            source: sha256_file(repo_root / source)
            for compile_ in plan.compiles
            if not compile_.generated
            for source in compile_.sources
        },
        "stubs": _directory_hashes(repo_root, _STUBS_DIR),
        "template_version": TEMPLATE_VERSION,
        "toolchain_overrides": dict(toolchain.overrides),
    }
    return sha256_hex(dump_yaml(material))


def compute_build_key(repo_root: Path | str) -> str:
    """The build key of ``repo_root`` with the toolchain on ``PATH``. Probes
    ``cobc`` and ``cc``; compiles nothing."""
    root = Path(repo_root).resolve()
    return _build_key(root, _toolchain(), _plan(root))


# --------------------------------------------------------------------------
# Compiling
# --------------------------------------------------------------------------


class _CompileFailure(Exception):
    """A compile failed; ``log`` is relative to the directory being built."""

    def __init__(self, unit: str, log: str, detail: str) -> None:
        super().__init__(unit, log, detail)
        self.unit, self.log, self.detail = unit, log, detail

    def message(self, log: Path) -> str:
        """The ``BuildError`` text, naming ``log``, the retained log."""
        return f"cannot compile {self.unit}: {self.detail}; log {log}"


class _ProbeFailure(_CompileFailure):
    """The clock probe, run once every compile succeeded, could not tell how
    libcob reads ``COB_CURRENT_DATE``; ``unit`` is the probe program and
    ``log`` its probe log. The build fails, and is kept, as for a compile."""

    def message(self, log: Path) -> str:
        return f"the clock probe {self.unit} failed: {self.detail}; log {log}"


@dataclass(frozen=True)
class _Task:
    job: _Compile
    label: str  # how errors name the unit
    base: Path  # directory holding the artifact and generated sources
    log: Path


# How often a running compile checks its deadline and its cancellation, and
# how long the kill of a compile may take to drain its output and reap its
# leader before the output is closed unread and an unreaped leader retained.
_COMPILE_POLL_S = 0.1
_REAP_TIMEOUT_S = 10

# The TMPDIR of every compile, relative to its working directory, the build
# workspace: cobc names its intermediate files by this path in the shell
# commands it runs, so they carry no text of the work root.
_COMPILE_TMPDIR = "tmp"
# What no argument of a cobc compile, and not its TMPDIR, may hold. cobc runs
# the C compiler and the linker through /bin/sh with each path in double
# quotes, inside which the shell still reads a backquote, '$', '"' and '\', so
# an argument holding one would be partly run as shell text. The same set as
# harness._SHELL_QUOTED_CHARACTERS, by which such a work root is refused; a
# compile can still meet one in the absolute paths of the checkout it names.
_SHELL_QUOTED_CHARACTERS = frozenset('`$"\\')

# The compiles whose leader was still unreaped _REAP_TIMEOUT_S after the kill
# of their group, such as one stuck in the kernel with the kill pending. They
# are held here, never dropped unreaped, until _reap_retained reaps them.
_UNREAPED: set[subprocess.Popen[bytes]] = set()
_UNREAPED_LOCK = threading.Lock()


class _Cancel:
    """The stop request of one compile. Once set, a compile that has not
    started runs nothing and a running one has its process group killed;
    ``reason`` is what its log gives."""

    def __init__(self) -> None:
        self._event = threading.Event()
        self.reason = ""

    def set(self, reason: str) -> None:
        """Request the stop; the first reason stays."""
        if not self._event.is_set():
            self.reason = reason
            self._event.set()

    def is_set(self) -> bool:
        return self._event.is_set()


def _signal_group(process: subprocess.Popen[bytes]) -> str:
    """Send SIGKILL to the process group of ``process``, which it leads, and
    return a note for the log status, empty unless the signal failed.

    The group is signalled only while its leader is unreaped: until then the
    leader's pid, the group id, cannot be reused, so no other group is hit. A
    failure other than a group already gone is noted, never raised, and the
    group is not taken to be gone."""
    if process.returncode is not None:
        return ""
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass  # no process of the group is left to signal
    except OSError as exc:
        return f"; cannot kill process group {process.pid}: {exc}"
    return ""


def _reap_retained() -> None:
    """Retry every compile in ``_UNREAPED`` without waiting: one whose leader
    is reaped now is dropped, and one still unreaped has its group signalled
    again and stays, also when that signal fails."""
    with _UNREAPED_LOCK:
        for process in list(_UNREAPED):
            if process.poll() is None:
                _signal_group(process)
            if process.poll() is not None:
                _UNREAPED.discard(process)


def _kill_group(
    process: subprocess.Popen[bytes], stdout: bytes, stderr: bytes
) -> tuple[bytes, bytes, str]:
    """Kill the process group of ``process`` (``_signal_group``), then drain
    its output and reap its leader within ``_REAP_TIMEOUT_S``; return the
    stdout and stderr read with a note for the log status, empty unless the
    teardown left something unresolved. ``stdout`` and ``stderr`` are the
    output read before the kill, returned for a stream the drain yields
    nothing of.

    Output still open at the bound is closed unread. When the leader is then
    reaped, a process that left the group held the output; when the leader is
    still unreaped, the process is retained in ``_UNREAPED``, so it is never
    lost, and the note names its group and the bound. The compiles retained earlier
    are retried first (``_reap_retained``). Nothing here waits past the
    bound."""
    _reap_retained()
    note = _signal_group(process)
    try:
        drained_stdout, drained_stderr = process.communicate(timeout=_REAP_TIMEOUT_S)
    except subprocess.TimeoutExpired as exc:
        # The output read so far. A stream nothing was read from carries none,
        # as does a reap that times out once the output has ended, so the
        # output read before the kill stands.
        if exc.stdout is not None:
            stdout = exc.stdout
        if exc.stderr is not None:
            stderr = exc.stderr
        for pipe in (process.stdout, process.stderr):
            if pipe is not None:
                pipe.close()
        if process.poll() is None:
            with _UNREAPED_LOCK:
                _UNREAPED.add(process)
            return stdout, stderr, (
                f"{note}; process group {process.pid} still unreaped {_REAP_TIMEOUT_S} s after "
                f"the kill: its output was closed unread and the process is retained to be "
                f"reaped later"
            )
        # A process that left the group still holds the pipes.
        note += f"; output still open {_REAP_TIMEOUT_S} s after the kill was closed unread"
        return stdout, stderr, note
    return drained_stdout or b"", drained_stderr or b"", note


def _shell_refusal(argv: Sequence[str], tmpdir: str) -> str | None:
    """Why the cobc compile ``argv``, run with ``TMPDIR`` set to ``tmpdir``,
    must not run, or ``None`` when it may: the first argument, or the TMPDIR,
    holding a character of ``_SHELL_QUOTED_CHARACTERS``, which a shell would
    read in cobc's C compiler commands, or one that is not printable (a line
    break, a tab, another control character), which no one-line compile log
    could show, named with that character. Only the absolute paths of cobc
    and of the checkout can hold one; the paths below the work root are
    relative."""
    for what, value in [*(("argument", element) for element in argv), ("TMPDIR", tmpdir)]:
        for character in value:
            if character in _SHELL_QUOTED_CHARACTERS:
                return (
                    f"not run: the {what} {value!r} holds {character!r}, which a shell reads even inside the "
                    "double quotes of the C compiler commands cobc runs through /bin/sh"
                )
            if not character.isprintable():
                return (
                    f"not run: the {what} {value!r} holds the non-printable character {character!r}, which no "
                    "one-line compile log could show"
                )
    return None


def _write_compile_log(
    task: _Task, argv: Sequence[str], status: str, stdout: bytes, stderr: bytes, workspace: Path
) -> None:
    text = "\n".join([
        f"command: {shlex.join(argv)}",
        f"cwd: {workspace}",
        f"status: {status}",
        "--- stdout ---",
        stdout.decode("utf-8", "replace").rstrip("\n"),
        "--- stderr ---",
        stderr.decode("utf-8", "replace").rstrip("\n"),
        "",
    ])
    # The workspace is renamed when complete, so the log names it <build>.
    task.log.parent.mkdir(parents=True, exist_ok=True)
    task.log.write_text(text.replace(str(workspace), "<build>"), encoding="utf-8")


def _run_compile(
    task: _Task, toolchain: _Toolchain, repo_root: Path, workspace: Path, cancel: _Cancel
) -> str | None:
    """Run one compile with ``workspace`` as its working directory and
    ``TMPDIR`` set to ``_COMPILE_TMPDIR``; return ``None`` on success or the
    failure detail. The log always gets the command line, its working
    directory, status, stdout and stderr, with ``workspace`` shown as
    ``<build>``.

    The compiler gets its output, its generated sources and its ``TMPDIR``
    relative to ``workspace`` (``_Compile.argv``), so no text of the work
    root reaches the shell commands cobc runs; only ``HOME`` names the
    workspace by its absolute path, and cobc puts no environment value into
    those commands. A cobc compile one of whose arguments, or whose
    ``TMPDIR``, holds a character a shell would read there, or one that is
    not printable (``_shell_refusal``), runs nothing: its log gets the
    refusal, which names the argument and the character, as its status, and
    the refusal is the failure detail, so the build fails with a
    ``BuildError`` naming the unit, the refusal and the log.

    The compiler starts a new session, so it and every process it starts form
    a process group of their own. When ``COMPILE_TIMEOUT_S`` passes or
    ``cancel`` is set, that group is killed, its output drained and its leader
    reaped within ``_REAP_TIMEOUT_S`` (``_kill_group``), and the log keeps the
    output read until then. A teardown problem, such as a failed signal or a
    leader still unreaped and so retained, is added to the log status and to
    the failure detail. An exception while waiting, such as
    ``KeyboardInterrupt``, does the same and is re-raised once the log is
    written. A compile cancelled before it starts runs nothing and logs why."""
    artifact = task.base / task.job.artifact
    argv = task.job.argv(toolchain, repo_root, task.base, workspace)
    if cancel.is_set():
        skipped = f"not run: {cancel.reason}"
        _write_compile_log(task, argv, skipped, b"", b"", workspace)
        return skipped
    env = toolchain.env(workspace, TMPDIR=_COMPILE_TMPDIR)
    if task.job.tool == "cobc":
        refused = _shell_refusal(argv, env["TMPDIR"])
        if refused is not None:
            _write_compile_log(task, argv, refused, b"", b"", workspace)
            return refused
    artifact.parent.mkdir(parents=True, exist_ok=True)
    stdout = stderr = b""
    detail: str | None = None
    try:
        process = subprocess.Popen(
            argv, cwd=workspace, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, start_new_session=True,
        )
    except OSError as exc:
        status = detail = f"cannot start {argv[0]}: {exc}"
    else:
        deadline = time.monotonic() + COMPILE_TIMEOUT_S
        try:
            while True:
                try:
                    stdout, stderr = process.communicate(
                        timeout=min(_COMPILE_POLL_S, max(0.0, deadline - time.monotonic()))
                    )
                except subprocess.TimeoutExpired as exc:
                    # The output read so far, kept should the kill drain none.
                    # A wait that times out once the output has ended carries
                    # none, so what was read before stands.
                    stdout, stderr = exc.stdout or stdout, exc.stderr or stderr
                    if cancel.is_set():
                        detail = f"cancelled: {cancel.reason}"
                    elif time.monotonic() >= deadline:
                        detail = f"timed out after {COMPILE_TIMEOUT_S} s"
                    else:
                        continue
                    stdout, stderr, note = _kill_group(process, stdout, stderr)
                    status = detail = detail + note
                else:
                    status = f"exit {process.returncode}"
                    if process.returncode != 0:
                        detail = f"{task.job.tool} exited {process.returncode}"
                break
        except BaseException as exc:
            stdout, stderr, note = _kill_group(process, stdout, stderr)
            _write_compile_log(
                task, argv, f"interrupted by {type(exc).__name__}{note}", stdout, stderr,
                workspace,
            )
            raise
    if detail is None and not artifact.is_file():
        detail = f"{task.job.tool} produced no {task.job.artifact}"
    _write_compile_log(task, argv, status, stdout, stderr, workspace)
    return detail


class _Terminated(BaseException):
    """SIGTERM or SIGHUP, raised in the main thread while ``_compile_all``
    waits for the compiles or ``_tool_output`` for a toolchain probe;
    ``disposition`` is the signal's action before the build or the probe."""

    def __init__(self, signum: int, disposition: Any) -> None:
        super().__init__(signum, disposition)
        self.signum, self.disposition = signum, disposition


_STOP_SIGNALS = (signal.SIGTERM, signal.SIGHUP)


@contextmanager
def _stop_signals_raise() -> Iterator[None]:
    """While the body runs, turn the first SIGTERM or SIGHUP into
    ``_Terminated`` and restore the replaced dispositions on exit.

    The compiles and the toolchain probes run in sessions of their own, so a
    termination signal sent to the caller's process group no longer reaches
    them, and the default action would end the interpreter before it kills
    their groups. A signal that is ignored, or whose handler was set outside
    Python (``None``), is left alone. Only the main thread may set handlers,
    so in any other thread nothing changes. A repeat during the cleanup the
    first one starts is dropped; a signal caught while the dispositions are
    being restored is sent again once they are."""
    previous: dict[int, Any] = {}
    raised: list[int] = []
    late: list[int] = []
    restoring = False

    def handler(signum: int, frame: Any) -> None:
        if restoring:
            late.append(signum)
        elif not raised:
            raised.append(signum)
            raise _Terminated(signum, previous[signum])

    try:
        if threading.current_thread() is threading.main_thread():
            for signum in _STOP_SIGNALS:
                disposition = signal.getsignal(signum)
                if disposition is not None and disposition is not signal.SIG_IGN:
                    previous[signum] = disposition
                    signal.signal(signum, handler)
        yield
    finally:
        restoring = True
        for signum, disposition in previous.items():
            signal.signal(signum, disposition)
        if late and not raised:
            os.kill(os.getpid(), late[0])


def _resume_signal(signum: int, disposition: Any) -> None:
    """Act on ``signum``, which stopped a build or a toolchain probe, as
    ``disposition``, its restored action from before them, would have: under
    ``SIG_DFL`` the process dies by the signal, and a Python handler is called
    and decides. When the process survives, ``BuildError`` reports the stopped
    build."""
    if disposition is signal.SIG_DFL:
        os.kill(os.getpid(), signum)
    else:
        disposition(signum, inspect.currentframe())
    raise BuildError(f"the build stopped on {signal.Signals(signum).name}")


def _check_tasks(tasks: Iterable[_Task], workspace: Path) -> None:
    """Raise ``BuildError`` naming the first artifact or log of ``tasks`` that
    does not lie lexically inside ``workspace`` (``_inside``)."""
    for task in tasks:
        _inside(task.base / task.job.artifact, workspace, f"the artifact of {task.label}")
        _inside(task.log, workspace, f"the log of {task.label}")


def _compile_all(
    tasks: Sequence[_Task], toolchain: _Toolchain, repo_root: Path, workspace: Path
) -> None:
    """Run ``tasks`` on ``_PARALLEL_COMPILES`` threads and raise
    ``_CompileFailure`` for the first failed task in task order.

    Once a task fails, every later task is cancelled: one not yet started runs
    nothing, and a running one has its process group killed, each logging
    why. Earlier tasks still finish, so the failure reported is the one a run
    without cancellation would report. An exception in the calling thread,
    such as ``KeyboardInterrupt``, cancels every task and is re-raised once
    every running task has killed its process group and, within
    ``_REAP_TIMEOUT_S``, reaped its leader or retained it unreaped
    (``_kill_group``), so the wait for the tasks is bounded. In the main
    thread SIGTERM and SIGHUP do the same as ``_Terminated``
    (``_stop_signals_raise``), raised once the previous dispositions are
    restored. Every log a task wrote stays in ``workspace``; the caller keeps
    it and then acts on a ``_Terminated`` as the signal's disposition would
    have (``_resume_signal``). The compiles an earlier build retained
    unreaped are retried first (``_reap_retained``).

    Before any directory is created or any compile started, every task's
    artifact and log must lie lexically inside ``workspace``
    (``_check_tasks``)."""
    _check_tasks(tasks, workspace)
    _reap_retained()
    (workspace / _COMPILE_TMPDIR).mkdir(parents=True, exist_ok=True)
    cancels = [_Cancel() for _ in tasks]
    first: int | None = None
    first_detail = ""
    try:
        with _stop_signals_raise(), ThreadPoolExecutor(max_workers=_PARALLEL_COMPILES) as pool:
            try:
                futures = [
                    pool.submit(_run_compile, task, toolchain, repo_root, workspace, cancel)
                    for task, cancel in zip(tasks, cancels)
                ]
                indexes = {future: index for index, future in enumerate(futures)}
                pending = set(futures)
                while pending:
                    done, pending = wait(pending, return_when=FIRST_COMPLETED)
                    for future in done:
                        index = indexes[future]
                        detail = future.result()
                        # Only tasks after an earlier failure are cancelled, so
                        # a cancelled task's failure is never the one reported.
                        if detail is None or cancels[index].is_set():
                            continue
                        if first is None or index < first:
                            first, first_detail = index, detail
                            reason = f"the build failed at {tasks[index].label}"
                            for later in cancels[index + 1:]:
                                later.set(reason)
            except BaseException as exc:
                cause = (signal.Signals(exc.signum).name if isinstance(exc, _Terminated)
                         else type(exc).__name__)
                reason = f"the build stopped on {cause}"
                for cancel in cancels:
                    cancel.set(reason)
                pool.shutdown(wait=True, cancel_futures=True)
                raise
    finally:
        shutil.rmtree(workspace / _COMPILE_TMPDIR, ignore_errors=True)
    if first is not None:
        task = tasks[first]
        raise _CompileFailure(task.label, task.log.relative_to(workspace).as_posix(), first_detail)


def _profile_tasks(profile: KeyProfile, directory: Path) -> list[_Task]:
    """Write the generated sources of ``profile`` into ``directory`` and
    return its two compiles. Each source path is checked (``_destination``)
    before either is written."""
    tasks = []
    compiles = _profile_compiles(profile)
    for path in compiles:
        _destination(path, f"the generated source of key profile {profile.name}")
    for path, (compile_, text) in compiles.items():
        source = directory / path
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(text, encoding="utf-8")
        tasks.append(_Task(compile_, f"{compile_.name} of key profile {profile.name}", directory,
                           directory / "logs" / f"{compile_.name}.log"))
    return tasks


def _probe_clock(toolchain: _Toolchain, directory: Path) -> str:
    """Run the clock probe with hundredths 37 in each COB_CURRENT_DATE form and
    return the first mode whose FUNCTION CURRENT-DATE shows them.

    ``logs/CLOCKCHK.probe.log`` gets each run's command line,
    ``COB_CURRENT_DATE``, status, result, stdout and stderr, and is kept on
    success as the evidence of the mode. When no form shows the hundredths,
    or the probe cannot run, ``_ProbeFailure`` names that log."""
    program = directory / "bin" / _CLOCK_PROGRAM
    argv = [str(program)]
    log = directory / "logs" / f"{_CLOCK_PROGRAM}.probe.log"
    blocks = [
        f"clock probe {_CLOCK_PROGRAM}: a match is FUNCTION CURRENT-DATE(1:16) "
        f"{_CLOCK_EXPECTED} for the clock {_CLOCK_PROBE}",
    ]
    seen: list[str] = []
    found: str | None = None
    completed_every_run = True
    for mode in _CLOCK_MODES:
        value = cob_current_date(_CLOCK_PROBE, mode)
        stdout = stderr = b""
        started = True
        try:
            completed = subprocess.run(
                argv, cwd=directory, env=toolchain.env(directory, COB_CURRENT_DATE=value),
                stdin=subprocess.DEVNULL, capture_output=True, timeout=_TOOL_PROBE_TIMEOUT_S,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            stdout, stderr = exc.stdout or b"", exc.stderr or b""
            status = f"timed out after {_TOOL_PROBE_TIMEOUT_S} s with partial output"
            completed_every_run = False
        except OSError as exc:
            status = f"cannot start {argv[0]}: {exc}"
            started = completed_every_run = False
        else:
            stdout, stderr = completed.stdout, completed.stderr
            status = f"exit {completed.returncode}"
            if (
                completed.returncode == 0
                and stdout.decode("latin-1").strip()[:16] == _CLOCK_EXPECTED
            ):
                found = mode
        output = stdout.decode("utf-8", "replace").rstrip("\n")
        errors = stderr.decode("utf-8", "replace").rstrip("\n")
        blocks.append("\n".join([
            f"--- attempt: {mode} ---",
            f"command: {shlex.join(argv)}",
            f"COB_CURRENT_DATE: {value}",
            f"status: {status}",
            f"result: {'match' if found == mode else 'no match'}",
            "--- stdout ---",
            output,
            "--- stderr ---",
            errors,
        ]))
        if found is not None:
            break
        summary = f"COB_CURRENT_DATE={value!r}: {status}"
        if started:
            summary += f", stdout {output.strip()!r}"
        if errors.strip():
            summary += f", stderr {errors.strip()!r}"
        seen.append(summary)
    # The workspace is renamed when complete or failed, so the log names it <build>.
    log.parent.mkdir(parents=True, exist_ok=True)
    log.write_text("\n".join([*blocks, ""]).replace(str(directory), "<build>"), encoding="utf-8")
    if found is not None:
        return found
    reason = (
        "libcob does not report the hundredths of COB_CURRENT_DATE in either form, so runs "
        "cannot be made reproducible"
        if completed_every_run
        else "it did not complete in every COB_CURRENT_DATE form, so how libcob reads the "
        "fraction is unknown"
    )
    detail = f"{reason}: {'; '.join(seen)}".replace(str(directory), "<build>")
    raise _ProbeFailure(_CLOCK_PROGRAM, log.relative_to(directory).as_posix(), detail)


class _ContainmentError(BuildError):
    """A build cache component that is a symbolic link, of another kind than
    it must be, another user's, or, for the build root, writable by group or
    others; or a build root or build directory that is no longer the
    directory admitted, because it was removed or another was put in its
    place. It is refused before anything is read, created, published or
    removed through it, so nothing reaches a link's target. It is a
    configuration error of the work root, so it is never memoized."""


def _kind_problem(mode: int, *, directory: bool) -> str | None:
    """Why ``mode``, taken without following a link, is not that of a real
    directory, or of a regular file; ``None`` when it is."""
    if stat.S_ISLNK(mode):
        return "is a symbolic link"
    if directory and not stat.S_ISDIR(mode):
        return "is not a directory"
    if not directory and not stat.S_ISREG(mode):
        return "is not a regular file"
    return None


def _ownership_problem(status: os.stat_result, *, directory: bool) -> str | None:
    """Why ``status``, taken without following a link, is not a real
    directory, or a regular file, of the effective user; ``None`` when it
    is."""
    problem = _kind_problem(status.st_mode, directory=directory)
    if problem is None and status.st_uid != os.geteuid():
        problem = f"is owned by uid {status.st_uid}, not {os.geteuid()}"
    return problem


def _open_refusal(what: str, path: Path, exc: OSError, *, directory: bool) -> BuildError:
    """The error for ``exc``, raised by opening ``path`` with ``O_NOFOLLOW``:
    a ``_ContainmentError`` when ``os.lstat`` shows a symbolic link or
    another kind of file than ``path`` must be, which the errno does not
    tell (``O_DIRECTORY`` gives ``ENOTDIR`` for a link), else a
    ``BuildError`` naming the errno."""
    try:
        problem = _kind_problem(os.lstat(path).st_mode, directory=directory)
    except OSError:
        problem = None
    if problem is not None:
        return _ContainmentError(f"the {what} {path} {problem}; {_REFUSAL_HINT}")
    return BuildError(f"cannot open the {what} {path}: {exc.strerror or exc}")


def _directory_identity(path: Path, what: str) -> tuple[int, int] | None:
    """The identity, ``(st_dev, st_ino)``, of ``path`` when it is a real
    directory of the effective user, inspected without following a link;
    ``None`` when nothing is there. A symbolic link, whatever it names, any
    other kind of file and another user's directory raise
    ``_ContainmentError`` naming ``what``: the cache never reads, writes or
    removes anything through them."""
    try:
        status = os.lstat(path)
    except FileNotFoundError:
        return None
    except OSError as exc:
        raise BuildError(f"cannot inspect the {what} {path}: {exc.strerror or exc}") from None
    problem = _ownership_problem(status, directory=True)
    if problem is not None:
        raise _ContainmentError(f"the {what} {path} {problem}; {_REFUSAL_HINT}")
    return status.st_dev, status.st_ino


def _real_directory(path: Path, what: str) -> bool:
    """Whether ``path`` exists as a real directory of the effective user,
    inspected without following a link; ``False`` when nothing is there. What
    ``_directory_identity`` refuses raises ``_ContainmentError`` naming
    ``what``."""
    return _directory_identity(path, what) is not None


def _check_admitted(
    path: Path, what: str, admitted: tuple[int, int], *, missing_ok: bool = False
) -> bool:
    """Re-admit ``path``, the ``what`` (build root or build directory) whose
    identity was ``admitted`` when it was first admitted: ``True`` when it is
    still that real directory of the effective user, inspected without
    following a link (``_directory_identity``). A symbolic link, any other
    kind of file, another user's directory and a different directory put in
    its place raise ``_ContainmentError`` naming ``what``, so nothing is
    reused, created, set aside, published or removed through it. A ``path``
    removed since is ``False`` with ``missing_ok`` and refused without it."""
    identity = _directory_identity(path, what)
    if identity is None:
        if missing_ok:
            return False
        raise _ContainmentError(
            f"the {what} {path} was removed after it was admitted; nothing is created in "
            f"its place"
        )
    if identity != admitted:
        raise _ContainmentError(
            f"the {what} {path} is no longer the directory admitted; {_REFUSAL_HINT}"
        )
    return True


def _admit_build_root(work: Path) -> tuple[Path, tuple[int, int]]:
    """``<work>/_build``, created with ``_PRIVATE_DIR_MODE`` (and the work
    root with it) when missing, once it is admitted, with its identity
    ``(st_dev, st_ino)`` as admitted, which ``_check_admitted`` holds it to
    afterwards: opened with ``O_DIRECTORY|O_NOFOLLOW``, it must be a real
    directory of the effective user that neither group nor others can write
    to. A symbolic link, whatever it names, any other kind of file, another
    user's directory and a shared-writable one raise ``_ContainmentError``
    before anything is created, reused, published or removed in it, so
    nothing reaches a link's target. A build root an earlier session created
    with mode 0755 is admitted."""
    build_root = work / _BUILD_SUBDIR
    try:
        work.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        raise BuildError(f"cannot create the work root {work}: {exc.strerror or exc}") from None
    try:
        os.mkdir(build_root, _PRIVATE_DIR_MODE)
    except FileExistsError:
        pass  # whatever is there is admitted or refused below, never followed
    except OSError as exc:
        raise BuildError(
            f"cannot create the build root {build_root}: {exc.strerror or exc}"
        ) from None
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
    try:
        descriptor = os.open(build_root, flags)
    except OSError as exc:
        raise _open_refusal("build root", build_root, exc, directory=True) from None
    try:
        status = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    problem = _ownership_problem(status, directory=True)
    if problem is not None:
        raise _ContainmentError(f"the build root {build_root} {problem}; {_REFUSAL_HINT}")
    if status.st_mode & _SHARED_WRITE:
        raise _ContainmentError(
            f"the build root {build_root} is writable by group or others (mode "
            f"{stat.S_IMODE(status.st_mode):04o}), so they could plant links in it; run "
            f"chmod go-w on it or choose another work root"
        )
    return build_root, (status.st_dev, status.st_ino)


def _remove_tree(path: Path) -> None:
    shutil.rmtree(path, ignore_errors=True)


@contextmanager
def _key_lock(build_root: Path, key: str, verify: Callable[[], object]) -> Iterator[None]:
    """Hold the exclusive lock of one build key, ``<build root>/<key>.lock``.

    ``fcntl.flock`` locks an open file description, so the lock excludes other
    processes and other threads alike, and the kernel releases it when its
    holder exits, however it ends. The lock file is never deleted: removing it
    would let two sessions lock two different files of the same name. It is
    opened with ``O_NOFOLLOW`` and must be a regular file of the effective
    user: a planted link is refused (``_ContainmentError``), never followed,
    and its target is not created. ``verify`` re-admits by identity the
    components the holder works in (``_check_admitted``: the build root, and
    for a ``BuildCache`` its build directory and ``profiles/``) before the
    lock file is opened, so none is created through a swapped build root,
    and again once the lock is held, so nothing the holder inspects, sets
    aside, removes or publishes goes through one; what it raises releases
    the lock and propagates."""
    verify()
    path = build_root / f"{key}{_LOCK_SUFFIX}"
    flags = os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_CLOEXEC
    try:
        descriptor = os.open(path, flags, 0o666)
    except OSError as exc:
        raise _open_refusal("build lock", path, exc, directory=False) from None
    try:
        problem = _ownership_problem(os.fstat(descriptor), directory=False)
        if problem is not None:
            raise _ContainmentError(f"the build lock {path} {problem}; {_REFUSAL_HINT}")
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX)
        except OSError as exc:
            raise BuildError(f"cannot lock the build lock {path}: {exc}") from None
        verify()
        yield
    finally:
        os.close(descriptor)


def _set_aside(directory: Path, target: Path) -> Path:
    """Rename ``directory`` to ``target``, replacing an older one; return the
    path the directory ends up at."""
    _remove_tree(target)
    try:
        os.replace(directory, target)
    except OSError:
        return directory
    return target


def _publish(workspace: Path, final: Path) -> None:
    """Rename a finished workspace to its final name. When another session
    published the same name first, its copy is used and this one dropped.
    A ``final`` that is a symbolic link or no directory is refused
    (``_ContainmentError``) and nothing is published or reused through it.
    The caller holds the key lock."""
    _real_directory(final, "build directory")
    try:
        os.replace(workspace, final)
    except OSError as exc:
        _remove_tree(workspace)
        if not (_real_directory(final, "build directory") and (final / _COMPLETE).is_file()):
            raise BuildError(f"cannot publish the build directory {final}: {exc}") from exc


def _build_failure(failure: _CompileFailure, workspace: Path, target: Path) -> BuildError:
    """Keep the failed ``workspace`` as ``target`` and return the error naming
    the failed unit or probe and its log there. The caller holds the key lock."""
    kept = _set_aside(workspace, target)
    log = kept / failure.log
    return BuildError(failure.message(log), unit=failure.unit, log_path=log)


def _discard(workspace: Path, verify: Callable[[], object]) -> None:
    """Remove ``workspace``, a temporary directory of a build, once ``verify``
    re-admits the components it lies in (see ``_key_lock``). When ``verify``
    refuses one, ``workspace`` is reachable only through a swapped component,
    so it is left where it is and nothing is removed through it."""
    try:
        verify()
    except BuildError:
        return
    _remove_tree(workspace)


def _keep_unfinished(
    workspace: Path, target: Path, build_root: Path, key: str, verify: Callable[[], object]
) -> None:
    """Keep ``workspace``, which an exception other than a compile failure
    stopped, as ``target`` with the logs its compiles wrote, taking the key
    lock, with ``verify``, for the rename. ``target`` is never complete, so it
    is never published or reused. When the lock or the rename fails, the
    workspace is removed instead (``_discard``) and nothing is raised: the
    caller re-raises the exception that stopped the build."""
    try:
        with _key_lock(build_root, key, verify):
            kept = _set_aside(workspace, target)
    except Exception:  # BuildError from the lock; the stopping exception goes on
        kept = workspace
    if kept != target:
        _discard(workspace, verify)


def _artifact_hashes(directory: Path) -> dict[str, str]:
    """The ``artifacts`` a manifest of ``directory`` records: the sha256 of
    every regular file under it by its POSIX path relative to it, in path
    order, leaving out the directory's own manifest and COMPLETE marker. A
    subdirectory that cannot be listed raises ``OSError`` rather than leaving
    its files out."""

    def refuse(error: OSError) -> None:
        raise error

    hashes: dict[str, str] = {}
    for parent, _, names in os.walk(directory, onerror=refuse):
        for name in names:
            path = Path(parent) / name
            relative = path.relative_to(directory).as_posix()
            if relative not in (_MANIFEST, _COMPLETE) and stat.S_ISREG(os.lstat(path).st_mode):
                hashes[relative] = sha256_file(path)
    return dict(sorted(hashes.items()))


def _artifacts_intact(directory: Path, artifacts: Any) -> bool:
    """Whether ``artifacts``, as read from a manifest of ``directory``, is a
    non-empty mapping of safe relative paths (neither absolute nor holding a
    ``..`` part) to sha256 hex digests, and each path names a regular file
    inside ``directory`` with that digest. Files it does not list, such as a
    profile built on demand later, are not looked at. The trust root is
    ``directory`` itself, never a link's target: a ``directory`` that is a
    symbolic link, is reached through one, or is no directory is not
    intact."""
    if not isinstance(artifacts, Mapping) or not artifacts:
        return False
    try:
        root = directory.resolve(strict=True)
        if root != Path(os.path.abspath(directory)) or not stat.S_ISDIR(
            os.lstat(directory).st_mode
        ):
            return False
        for relative, digest in artifacts.items():
            if not (
                isinstance(relative, str)
                and isinstance(digest, str)
                and _SHA256_RE.fullmatch(digest)
            ):
                return False
            parts = PurePosixPath(relative)
            if not relative or parts.is_absolute() or ".." in parts.parts:
                return False
            path = directory / relative
            if (
                not stat.S_ISREG(os.lstat(path).st_mode)
                or root not in path.resolve(strict=True).parents
                or sha256_file(path) != digest
            ):
                return False
    except (OSError, RuntimeError, ValueError):  # missing or unreadable; a symlink loop; a NUL
        return False
    return True


def _profile_intact(directory: Path, name: str) -> bool:
    """Whether ``directory`` is a finished on-demand profile ``name``: it holds
    COMPLETE and a manifest of that profile whose ``artifacts`` all match."""
    try:
        manifest = load_yaml(directory / _MANIFEST) if (directory / _COMPLETE).is_file() else None
    except Exception:  # an unreadable manifest is treated like a missing one
        manifest = None
    return (
        isinstance(manifest, Mapping)
        and manifest.get("profile") == name
        and _artifacts_intact(directory, manifest.get("artifacts"))
    )


def _populate(
    workspace: Path, key: str, plan: _Plan, toolchain: _Toolchain, repo_root: Path
) -> None:
    tasks = [_Task(compile_, compile_.name, workspace, workspace / "logs" / f"{compile_.name}.log")
             for compile_ in plan.compiles]
    # Every compile's artifact and log, and every generated source path, is
    # checked before anything is written.
    _check_tasks(tasks, workspace)
    for path in plan.generated:
        _destination(path, "the generated source")
    for path, text in plan.generated.items():
        (workspace / path).parent.mkdir(parents=True, exist_ok=True)
        (workspace / path).write_text(text, encoding="utf-8")
    profile_dirs = [workspace / "profiles" / profile.name for profile in plan.profiles]
    for profile, directory in zip(plan.profiles, profile_dirs):
        tasks += _profile_tasks(profile, directory)
    _compile_all(tasks, toolchain, repo_root, workspace)
    for directory in profile_dirs:
        (directory / _COMPLETE).write_text("", encoding="utf-8")
    mode = _probe_clock(toolchain, workspace)
    manifest = {
        # Everything the build wrote, the clock probe's log included.
        "artifacts": _artifact_hashes(workspace),
        "cc_version": toolchain.cc_version,
        "clock_fraction_mode": mode,
        "cobc_version": toolchain.cobc_version,
        "compiles": [compile_.key_entry() for compile_ in plan.compiles],
        "key": key,
        "profiles": [profile.name for profile in plan.profiles],
        "template_version": TEMPLATE_VERSION,
        "units": plan.units,
    }
    (workspace / _MANIFEST).write_text(dump_yaml(manifest), encoding="utf-8")
    (workspace / _COMPLETE).write_text("", encoding="utf-8")


def _complete_mode(directory: Path, key: str) -> str | None:
    """The clock fraction mode of a finished build directory, or ``None`` when
    there is none. A directory is finished when it holds COMPLETE and a
    manifest of ``key`` with a clock fraction mode whose ``artifacts`` all
    match their files (``_artifacts_intact``). Any other directory, whether
    unfinished, of another key, or holding a truncated, altered or missing
    artifact or a manifest without the hashes, is removed, so it gets rebuilt;
    files the manifest does not list, such as profiles built on demand, are
    not looked at. A ``directory`` that is a symbolic link, whatever it
    names, or no directory is refused (``_ContainmentError``) before
    anything is read, reused or removed through it. The caller holds the key
    lock, so no other session publishes the directory between the inspection
    and the removal."""
    if not _real_directory(directory, "build directory"):
        return None
    try:
        manifest = load_yaml(directory / _MANIFEST) if (directory / _COMPLETE).is_file() else None
    except Exception:  # an unreadable manifest is treated like a missing one
        manifest = None
    if (
        isinstance(manifest, Mapping)
        and manifest.get("key") == key
        and manifest.get("clock_fraction_mode") in _CLOCK_MODES
        and _artifacts_intact(directory, manifest.get("artifacts"))
    ):
        return manifest["clock_fraction_mode"]
    stale = directory.with_name(f"{directory.name}.stale-{os.getpid()}")
    _remove_tree(_set_aside(directory, stale))
    return None


def _admit_finished(directory: Path, key: str) -> tuple[str, tuple[int, int]] | None:
    """The clock fraction mode of the finished build directory ``directory``
    (``_complete_mode``) with its identity, ``(st_dev, st_ino)``, taken
    without following a link as it is admitted, or ``None`` when there is no
    finished directory. The caller holds the key lock."""
    mode = _complete_mode(directory, key)
    identity = None if mode is None else _directory_identity(directory, "build directory")
    return None if mode is None or identity is None else (mode, identity)


# --------------------------------------------------------------------------
# The build cache
# --------------------------------------------------------------------------


class BuildCache:
    """A finished build directory, ``<work root>/_build/<key>/``.

    Attributes: ``key`` (hex build key), ``dir``, ``module_dir`` (for
    ``COB_LIBRARY_PATH``: CBACT04C.so, CSUTLDTC.so, CEE3ABD.so, CEEDAYS.so),
    ``parmdrv``, ``dtcdrv``, ``shim`` (the ``LD_PRELOAD`` library), ``probe``
    (``page0_probe``), ``cobc_version`` (the full ``cobc --version`` text),
    ``cobc_bin_dir`` and ``lib_dirs`` (for a subprocess's ``PATH`` and
    ``LD_LIBRARY_PATH``), ``clock_fraction_mode`` (``hundredths`` or
    ``nanoseconds``, for ``harness.cob_current_date``) and ``profiles`` (the
    static key profiles). The directory is read-only to its users; only
    ``loader`` and ``unloader`` add to it, atomically, for a new profile.
    Its artifacts, the static profiles' included, were checked against the
    manifest when it was loaded (``_complete_mode``). A profile built on
    demand carries its own manifest, checked on the profile's first use by
    this instance: a profile whose manifest is missing or any of whose
    artifacts does not match is set aside, removed and built afresh.

    The cache holds the identities, ``(st_dev, st_ino)``, of the build root
    ``_build`` and of the build directory as they were admitted:
    ``admitted``, as ``ensure_built`` passes them, or else taken here without
    following a link. Every ``loader`` and ``unloader`` call, the fast path of
    a profile already checked included, and every creation, set-aside,
    removal or publication of a profile, under the key lock, first re-admits
    both by those identities (``_readmit``) and then checks ``profiles/`` and
    ``profiles/<profile>/``: a build root or build directory that is now a
    symbolic link, no directory, another user's, removed or a different
    directory, and a ``profiles/`` or ``profiles/<profile>/`` that is a
    symbolic link or no directory, are refused (``_ContainmentError``)
    before any profile is reused, created, set aside or published through
    them. A swap made while a profile's compiles run can receive what the
    build writes into its workspace, and is refused at the next check,
    before that workspace is set aside, removed or published."""

    def __init__(
        self, *, key: str, directory: Path, repo_root: Path, toolchain: _Toolchain, plan: _Plan,
        clock_fraction_mode: str,
        admitted: tuple[tuple[int, int], tuple[int, int]] | None = None,
    ) -> None:
        self.key = key
        self.dir = directory
        self.module_dir = directory / "modules"
        self.parmdrv = directory / "bin" / "PARMDRV"
        self.dtcdrv = directory / "bin" / "DTCDRV"
        self.shim = directory / "lib" / "psa_tiot_shim.so"
        self.probe = directory / "bin" / "page0_probe"
        self.cobc_version = toolchain.cobc_version
        self.cobc_bin_dir = Path(toolchain.cobc).parent
        self.lib_dirs = tuple(Path(entry) for entry in toolchain.lib_dirs)
        self.clock_fraction_mode = clock_fraction_mode
        self.profiles = plan.profiles
        self._units = plan.units
        self._logs = {compile_.name for compile_ in plan.compiles}
        self._repo_root = repo_root
        self._toolchain = toolchain
        self._lock = threading.Lock()
        self._static_profiles = frozenset(profile.name for profile in plan.profiles)
        # The on-demand profiles checked or built by this instance; written
        # only under _lock.
        self._verified_profiles: set[str] = set()
        # The identities of the build root and the build directory as they
        # were admitted; the cache is made only while both still are those.
        if admitted is None:
            admitted = (
                self._identity(directory.parent, "build root"),
                self._identity(directory, "build directory"),
            )
        self._admitted = admitted
        self._readmit()

    def __repr__(self) -> str:
        return f"BuildCache(key={self.key[:16]}..., dir={self.dir})"

    @staticmethod
    def _identity(path: Path, what: str) -> tuple[int, int]:
        """The identity of ``path``, which must be a real directory of the
        effective user (``_directory_identity``); a missing one is a
        ``BuildError``."""
        identity = _directory_identity(path, what)
        if identity is None:
            raise BuildError(f"the {what} {path} does not exist")
        return identity

    def _readmit(self, *, missing_ok: bool = False) -> bool:
        """Re-admit the build root ``_build`` and this build directory by the
        identities they were admitted with (``_check_admitted``): ``True``
        when both still are those real directories of the effective user. A
        symbolic link, any other kind of file, another user's directory and a
        different directory in the place of either raise
        ``_ContainmentError`` naming it, before anything is reused, created,
        set aside, published or removed through it. One removed since is
        ``False`` with ``missing_ok`` and refused without it."""
        root, directory = self._admitted
        return _check_admitted(
            self.dir.parent, "build root", root, missing_ok=missing_ok
        ) and _check_admitted(self.dir, "build directory", directory, missing_ok=missing_ok)

    def is_complete(self) -> bool:
        return (self.dir / _COMPLETE).is_file()

    def executable(self, unit: str) -> Path:
        """The executable of a ``run: executable`` unit; CBSTM03A's holds
        CBSTM03B."""
        entry = self._units.get(unit)
        if entry is None:
            raise BuildError(f"{unit} is not a unit of {_PROGRAMS_YAML}")
        if entry["run"] == "called":
            raise BuildError(f"{unit} has no executable: it runs only inside {entry['called_by']}")
        if entry["run"] != "executable":
            raise BuildError(
                f"{unit} is the module {entry['artifact']}; it runs through "
                f"{'PARMDRV' if entry['run'] == 'parmdrv' else 'DTCDRV'}"
            )
        return self.dir / entry["artifact"]

    def log_path(self, unit: str) -> Path:
        """The compile log of a unit, stub or driver; a called unit's is its
        caller's, which compiled it."""
        entry = self._units.get(unit)
        if entry is not None and entry["run"] == "called":
            unit = entry["called_by"]
        if unit not in self._logs:
            raise BuildError(f"{unit} is not compiled by this build")
        return self.dir / "logs" / f"{unit}.log"

    def loader(self, profile: KeyProfile) -> Path:
        """The loader executable of ``profile`` (``LOADER_MODES``)."""
        return self._profile_dir(profile) / _LOADER_PROGRAM

    def unloader(self, profile: KeyProfile) -> Path:
        """The unloader executable of ``profile`` (``UNLOADER_MODES``)."""
        return self._profile_dir(profile) / _UNLOADER_PROGRAM

    def _profile_dir(self, profile: KeyProfile) -> Path:
        if not isinstance(profile, KeyProfile):
            raise BuildError(f"a KeyProfile is required, got {profile!r}")
        name = profile.name
        directory = self.dir / "profiles" / name
        # Before every use, the fast path below included: the path returned is
        # executed, so it must lie in the directories admitted.
        self._check_profile_components(directory)
        # A static profile was checked with the whole directory when the cache
        # was loaded; an on-demand one is checked here on its first use. The
        # first test runs unlocked, the second under _lock, which every write
        # of _verified_profiles holds.
        if (directory / _COMPLETE).is_file() and (
            name in self._static_profiles or name in self._verified_profiles
        ):
            return directory
        with self._lock:
            if name not in self._static_profiles and not (
                name in self._verified_profiles and (directory / _COMPLETE).is_file()
            ):
                self._drop_unverified_profile(name, directory)
            if not (directory / _COMPLETE).is_file():
                self._build_profile(profile, directory)
            if name not in self._static_profiles:
                self._verified_profiles.add(name)
        return directory

    def _check_profile_components(self, directory: Path) -> None:
        """Re-admit the build root and this build directory by identity
        (``_readmit``), then refuse ``profiles/`` and ``directory``, one
        ``profiles/<profile>/`` of this cache, with ``_ContainmentError`` when
        either is a symbolic link or no directory; nothing is read through
        them. Either of the last two may be missing."""
        self._readmit()
        if _real_directory(directory.parent, "profiles directory"):
            _real_directory(directory, "profile directory")

    def _drop_unverified_profile(self, name: str, directory: Path) -> None:
        """Set aside and remove ``directory``, the on-demand profile ``name``,
        unless it is finished with every artifact matching its manifest
        (``_profile_intact``), so the caller builds it afresh; ``_publish``
        would otherwise keep the existing copy. The check is repeated under the
        key lock, once the lock has re-admitted the components
        (``_check_profile_components``), before the removal, so a copy another
        session has just published intact is kept. The caller holds
        ``_lock``."""
        if not os.path.lexists(directory) or _profile_intact(directory, name):
            return
        verify = functools.partial(self._check_profile_components, directory)
        with _key_lock(self.dir.parent, self.key, verify):
            if not os.path.lexists(directory) or _profile_intact(directory, name):
                return
            stale = directory.with_name(f"{name}.stale-{os.getpid()}")
            _remove_tree(_set_aside(directory, stale))

    def _build_profile(self, profile: KeyProfile, directory: Path) -> None:
        # The components are re-admitted (_check_profile_components) before
        # profiles/ and the workspace are created, and by the key lock before
        # the workspace is set aside, published or removed.
        verify = functools.partial(self._check_profile_components, directory)
        verify()
        directory.parent.mkdir(parents=True, exist_ok=True)
        verify()
        workspace = Path(tempfile.mkdtemp(prefix=f".{profile.name}-", dir=directory.parent))
        target = directory.with_name(f"{profile.name}.failed")
        stopped: tuple[int, Any] | None = None
        try:
            tasks = _profile_tasks(profile, workspace)
            _compile_all(tasks, self._toolchain, self._repo_root, workspace)
            manifest = {"artifacts": _artifact_hashes(workspace), "profile": profile.name}
            (workspace / _MANIFEST).write_text(dump_yaml(manifest), encoding="utf-8")
            (workspace / _COMPLETE).write_text("", encoding="utf-8")
        except _CompileFailure as failure:
            with _key_lock(self.dir.parent, self.key, verify):
                error = _build_failure(failure, workspace, target)
            raise error from None
        except BaseException as exc:
            # KeyboardInterrupt, SIGTERM or SIGHUP (_Terminated), or any other
            # exception: keep the logs written so far, then let it go on.
            _keep_unfinished(workspace, target, self.dir.parent, self.key, verify)
            if not isinstance(exc, _Terminated):
                raise
            stopped = exc.signum, exc.disposition
        if stopped is not None:
            # Outside the handler, so what follows does not chain _Terminated.
            _resume_signal(*stopped)
        # The compiles ran unlocked; only the rename is serialized, so the first
        # session to publish the profile wins and the others use its copy.
        try:
            with _key_lock(self.dir.parent, self.key, verify):
                _publish(workspace, directory)
        except BaseException:
            _discard(workspace, verify)  # a no-op once the workspace is published
            raise


_CACHES: dict[tuple[str, str], BuildCache] = {}
# The message, unit and log path of the BuildError that ended each failed
# build attempt, by the same key.
_BUILD_FAILURES: dict[tuple[str, str], tuple[str, str | None, Path | None]] = {}
_ENSURE_LOCK = threading.Lock()


def _remember_failure(memo_key: tuple[str, str], error: BuildError) -> BuildError:
    """Record ``error``, which ends the build attempt of ``memo_key``, and
    return it."""
    _BUILD_FAILURES[memo_key] = (str(error), error.unit, error.log_path)
    return error


def ensure_built(repo_root: Path | str, work_root: Path | str) -> BuildCache:
    """Return the build cache of ``repo_root`` under ``work_root``, compiling
    it first when no finished copy exists. Memoized per process and safe to
    call from every ``prepare``; the build itself is not bounded by any
    scenario deadline.

    A failed build is not retried in the process: once a compile or the clock
    probe failed, or the finished build could not be published, every later
    call with the same repository and work root raises a fresh ``BuildError``
    with the same message, unit and log path, and compiles nothing. A missing
    toolchain is memoized by its probe instead (``_probe_toolchain``); an
    interrupted build, a configuration error and a work root inside the
    repository are not memoized.

    Only ``work_root`` itself is taken as given, symbolic links resolved;
    below it every component is refused at admission when it is unsound and
    re-verified by identity before every reuse, creation, set-aside, removal
    or publication. ``_build`` is created with mode 0700 when missing and
    admitted only as a real directory of the effective user that neither
    group nor others can write to (``_admit_build_root``); the key lock,
    ``_build/<key>/`` and its ``profiles`` must not be symbolic links. The
    identities, ``(st_dev, st_ino)``, of ``_build`` as admitted and of
    ``_build/<key>/`` once found or published finished are kept on the
    cache. ``_build`` is re-admitted by its identity in every key lock
    section, before the lock file is opened and once it is held, and before
    the build workspace is created; both are re-admitted when the cache is
    made, by every call that would return the memoized cache, and by every
    ``loader`` and ``unloader`` call (``BuildCache``). A component that is
    now a symbolic link, no directory, another user's or a different
    directory raises ``BuildError`` naming it, never ``ToolchainUnavailable``,
    before anything is created, reused, set aside, published or removed
    through it; it is a configuration error, so it is not memoized and the
    next call checks again. A memoized cache whose ``_build`` or
    ``_build/<key>/`` was removed, or which is no longer complete, is
    admitted and built afresh. These checks run before each such step, not
    while a build writes into its own workspace: a swap made while the
    compiles run can receive what they and the build then write there, and
    is refused at the next check, before anything is set aside, removed or
    published."""
    root = Path(repo_root).resolve()
    work = Path(work_root).resolve()
    if work == root or root in work.parents:
        raise BuildError(
            f"the work root {work} lies inside the repository {root}; nothing may be "
            f"written there"
        )
    memo_key = (str(root), str(work))
    with _ENSURE_LOCK:
        cache = _CACHES.get(memo_key)
        # The memoized cache is re-admitted by identity before it is reused:
        # a build root or build directory swapped since raises; one removed
        # since, or a cache no longer complete, is admitted and built afresh.
        if cache is not None and cache._readmit(missing_ok=True) and cache.is_complete():
            return cache
        remembered = _BUILD_FAILURES.get(memo_key)
        if remembered is not None:
            message, unit, log_path = remembered
            raise BuildError(message, unit=unit, log_path=log_path) from None
        toolchain = _toolchain()
        plan = _plan(root)
        key = _build_key(root, toolchain, plan)
        build_root, admitted_root = _admit_build_root(work)
        # Re-admits the build root by identity: in each key lock section,
        # before the lock file is opened and once it is held, and before the
        # workspace is created.
        verify = functools.partial(_check_admitted, build_root, "build root", admitted_root)
        final = build_root / key
        with _key_lock(build_root, key, verify):
            finished = _admit_finished(final, key)
        if finished is None:
            # The compiles run unlocked in a private workspace, so concurrent
            # sessions build side by side and the first to publish wins.
            verify()
            workspace = Path(tempfile.mkdtemp(prefix=f".{key[:16]}-", dir=build_root))
            failed = build_root / f"{key}.failed"
            stopped: tuple[int, Any] | None = None
            try:
                _populate(workspace, key, plan, toolchain, root)
            except _CompileFailure as failure:
                with _key_lock(build_root, key, verify):
                    error = _build_failure(failure, workspace, failed)
                raise _remember_failure(memo_key, error) from None
            except BaseException as exc:
                # KeyboardInterrupt, SIGTERM or SIGHUP (_Terminated), or any other
                # exception: keep the logs written so far, then let it go on.
                _keep_unfinished(workspace, failed, build_root, key, verify)
                if not isinstance(exc, _Terminated):
                    raise
                stopped = exc.signum, exc.disposition
            if stopped is not None:
                # Outside the handler, so what follows does not chain _Terminated.
                _resume_signal(*stopped)
            try:
                with _key_lock(build_root, key, verify):
                    _publish(workspace, final)
                    finished = _admit_finished(final, key)
            except BaseException as exc:
                _discard(workspace, verify)  # a no-op once the workspace is published
                # The publication or its lock failed; a refused component is a
                # configuration error of the work root, never memoized.
                if isinstance(exc, BuildError) and not isinstance(exc, _ContainmentError):
                    _remember_failure(memo_key, exc)
                raise
            if finished is None:
                error = BuildError(f"the build directory {final} is incomplete after publishing")
                raise _remember_failure(memo_key, error)
        mode, admitted_directory = finished
        # The cache re-admits both identities when it is made, so a build root
        # or build directory swapped since they were admitted is refused here.
        cache = BuildCache(
            key=key, directory=final, repo_root=root, toolchain=toolchain, plan=plan,
            clock_fraction_mode=mode, admitted=(admitted_root, admitted_directory),
        )
        _CACHES[memo_key] = cache
        return cache
