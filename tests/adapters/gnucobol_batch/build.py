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
    Each compile's command line, stdout and stderr; generated sources; the
    evidence of what was built; the marker written last.
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
CBACT04C descriptions, and comparisons use the ASCII collating sequence.

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
after it, the set-aside of a failed build or profile, and the publication of
an on-demand profile. The compiles run outside it, so the first session to
publish wins. A failed build, whether a compile or the clock probe failed, is
kept as ``_build/<key>.failed`` and the ``BuildError`` names the unit or the
probe and its log there. A failed build is a harness error, never a gap.

Indexed files. GnuCOBOL 3.2 with Berkeley DB keeps alternate key *i* in
``<file>.i``. ``OPEN`` fails with status 35 when a declared alternate's file
is absent, and with status 30 when the reader declares ``WITH DUPLICATES`` on a
key built without them, so each file is built with exactly the keys its reader
declares: a ``KeyProfile``. One
loader and one unloader are generated per profile; ``static_profiles`` lists
the profiles compiled up front, and ``BuildCache.loader``/``unloader`` build
any other profile on demand. Both programs assign the indexed file to
``KSDS_DD`` and the record-sequential file of the profile's record length to
``SEQIN_DD`` or ``SEQOUT_DD``; pass absolute paths in ``DD_<name>``.

Loader ``KSLOAD <mode>``:

* ``LOAD``: ``OPEN OUTPUT``, then write every SEQIN record.
* ``APPEND``: ``OPEN I-O`` (``OPEN OUTPUT`` when the file is absent), then
  write every SEQIN record.
* ``CREATE``: ``OPEN OUTPUT`` and ``CLOSE``, an empty file; prints ``CREATED``.

A record whose key is already present (status 22) prints ``DUPKEY <n>`` and the
load goes on; a load ends with ``LOADED <count>``. Unloader ``KSUNLOAD PRIMARY``
or ``KSUNLOAD ALT <n>`` writes every record in primary-key order, or in the
order of the profile's 1-based alternate key ``n``, and ends with
``UNLOADED <count>``; an absent file prints ``NOFILE``. Record numbers and
counts are 9 zero-padded digits. SEQIN must hold whole records: libcob reads
a short final record with status 00 and keeps the rest of the previous
record. Failures print ``STATUS <xx> AT <n>``,
``SEQIN STATUS <xx> AT <n>``, ``<FILE> <verb> STATUS <xx>`` or ``USAGE: ...``.
Exit statuses are ``EXIT_OK``, ``EXIT_FAILED``, ``EXIT_USAGE`` and
``EXIT_NOFILE``.

Clock. ``clock_fraction_mode`` says how libcob reads the fraction of
``COB_CURRENT_DATE``: ``hundredths`` or ``nanoseconds``, as
``harness.cob_current_date`` formats it. A generated probe decides it once per
cache.

Nothing is written in the repository: sources are passed by absolute path,
every output goes to the build directory through ``-o``, and each compile
runs with its working and temporary directories inside it. Importing this
module starts no process and reads no file.
"""

from __future__ import annotations

import fcntl
import os
import re
import shlex
import shutil
import subprocess
import tempfile
import threading
from collections.abc import Iterable, Iterator, Mapping, Sequence
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
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
TEMPLATE_VERSION = "2"

# Seconds one compile or probe may take. The build is session-level, so no
# scenario deadline bounds it.
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
_SETUP_HINT = (
    "GnuCOBOL 3.2 built --with-db (Berkeley DB) must be on PATH, with its lib "
    "directory on LD_LIBRARY_PATH; see tests/README.md"
)

_MODE_FLAGS = {"executable": "-x", "module": "-m"}
_RUN_MODES = {"executable": "executable", "parmdrv": "module", "dtcdrv": "module"}
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
_COBOL_LINE_LIMIT = 72

_PARALLEL_COMPILES = max(1, min(4, os.cpu_count() or 1))


class BuildError(HarnessError):
    """The toolchain is missing or unsuitable, the build configuration is
    inconsistent, or a compile or the clock probe failed.

    ``unit`` names the compilation unit and ``log_path`` its compile log when
    the error comes from a compile, or ``CLOCKCHK`` and its probe log when the
    clock probe failed; the failed build keeps both. The runner reports a
    harness error as ``failing`` with reason ``error``; it is never turned
    into a skip."""

    def __init__(
        self, message: str, *, unit: str | None = None, log_path: Path | None = None
    ) -> None:
        super().__init__(message)
        self.unit = unit
        self.log_path = log_path


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
        primary, alternates = keys["primary"], keys.get("alternates") or []
    elif "key" in keys:
        primary, alternates = keys["key"], keys.get("alternate_keys") or []
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


def _programs(repo_root: Path) -> dict[str, Mapping[str, Any]]:
    data = _load_config(repo_root, _PROGRAMS_YAML)
    if data.get("schema_version") != 1 or not isinstance(data.get("programs"), Mapping):
        raise BuildError(f"{_PROGRAMS_YAML} must have schema_version 1 and a programs mapping")
    return dict(data["programs"])


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
        if layout not in layouts:
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
    for job_name in sorted(jobs):
        job = jobs[job_name]
        steps = job.get("steps") if isinstance(job, Mapping) else None
        if not isinstance(steps, list):
            raise BuildError(f"{_JOBS_YAML} job {job_name} has no steps list")
        for step in steps:
            if not isinstance(step, Mapping):
                raise BuildError(f"{_JOBS_YAML} job {job_name} holds a step that is not a mapping")
            for dd in (step.get("dd") or {}).values():
                if not isinstance(dd, Mapping):
                    continue
                for entry in [dd, *(dd.get("concat") or [])]:
                    if isinstance(entry, Mapping) and entry.get("dsn") and entry.get("logical"):
                        dsn_logical.setdefault(entry["dsn"], entry["logical"])
            if step.get("kind") != "idcams":
                continue
            for statement in _idcams_statements((step.get("params") or {}).get("sysin") or []):
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
        profile = KeyProfile(int(size.group(2)), (int(keys.group(2)), int(keys.group(1))))
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
        alternate = (int(keys.group(2)), int(keys.group(1)), bool(_NONUNIQUE_RE.search(statement)))
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
        for ddname, dd in sorted(((config or {}).get("dd") or {}).items()):
            if not isinstance(dd, Mapping) or dd.get("organization") != "indexed":
                continue
            if not isinstance(dd.get("keys"), Mapping):
                raise BuildError(
                    f"{_PROGRAMS_YAML} {unit} dd {ddname} is indexed but declares no keys"
                )
            profile = profile_for(dd.get("record_length"), dd["keys"])
            entry = datasets.get(dd.get("logical"))
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
        "                   OPEN I-O KFILE",
        "                   IF WS-KSTAT = '35'",
        "                       OPEN OUTPUT KFILE",
        "                   END-IF",
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
        "       1000-OPEN-SEQIN.",
        "           OPEN INPUT SFILE",
        "           IF WS-SSTAT NOT = '00'",
        "               DISPLAY 'SEQIN OPEN STATUS ' WS-SSTAT",
        "               PERFORM 9000-FAIL",
        "           END-IF",
        "           MOVE 'Y' TO WS-SOPEN.",
        "       1100-CHECK-KFILE-OPEN.",
        "           IF WS-KSTAT NOT = '00'",
        f"               DISPLAY '{KSDS_DD} OPEN STATUS ' WS-KSTAT",
        "               PERFORM 9000-FAIL",
        "           END-IF",
        "           MOVE 'Y' TO WS-KOPEN.",
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
        "                   DISPLAY 'NOFILE'",
        f"                   MOVE {EXIT_NOFILE} TO RETURN-CODE",
        "                   STOP RUN",
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
        "       2000-WRITE-RECORD.",
        "           WRITE S-REC FROM K0-REC",
        "           IF WS-SSTAT NOT = '00'",
        f"               DISPLAY '{SEQOUT_DD} STATUS ' WS-SSTAT ' AT ' WS-RECNO",
        "               PERFORM 9000-FAIL",
        "           END-IF",
        "           ADD 1 TO WS-COUNT.",
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
    try:
        completed = subprocess.run(
            list(argv), env=dict(env), stdin=subprocess.DEVNULL, capture_output=True,
            timeout=_TOOL_PROBE_TIMEOUT_S, check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise BuildError(f"cannot run {shlex.join(argv)}: {exc}. {_SETUP_HINT}") from None
    if completed.returncode != 0:
        stderr = completed.stderr.decode("utf-8", "replace").strip()
        raise BuildError(
            f"{shlex.join(argv)} exited {completed.returncode}: {stderr}. {_SETUP_HINT}"
        )
    return completed.stdout.decode("utf-8", "replace").strip()


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


def _probe_toolchain(
    path: str, ld_library_path: str, overrides: tuple[tuple[str, str], ...]
) -> _Toolchain:
    memo_key = (path, ld_library_path, overrides)
    if memo_key in _TOOLCHAINS:
        return _TOOLCHAINS[memo_key]
    cobc = shutil.which("cobc", path=path)
    if cobc is None:
        raise BuildError(f"cobc is not on PATH. {_SETUP_HINT}")
    cobc = os.path.abspath(cobc)
    # The probes run in the caller's environment: it is what lets cobc find
    # libcob, whose directory the compile environment then names explicitly.
    probe_env = {**os.environ, "LANG": "C", "LC_ALL": "C"}
    cobc_version = _tool_output([cobc, "--version"], probe_env)
    first_line = cobc_version.splitlines()[0].strip() if cobc_version else ""
    if not _COBC_VERSION_RE.match(first_line):
        raise BuildError(
            f"{cobc} reports {first_line!r}, but GnuCOBOL 3.2 is required. {_SETUP_HINT}"
        )
    info = _tool_output([cobc, "--info"], probe_env)
    if not _BDB_RE.search(info):
        raise BuildError(
            f"{cobc} has no Berkeley DB indexed file handler ('indexed file handler : BDB' is "
            f"missing from cobc --info). {_SETUP_HINT}"
        )
    entries = _info_entries(info)
    cc = shutil.which("cc", path=path) or shutil.which("gcc", path=path)
    if cc is None:
        raise BuildError(
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
    toolchain = _Toolchain(cobc, cc, cobc_version, cc_version, bin_dirs, lib_dirs, overrides)
    _TOOLCHAINS[memo_key] = toolchain
    return toolchain


# --------------------------------------------------------------------------
# Build plan and key
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class _Compile:
    """One compiler invocation. ``sources`` are repository-relative, or
    relative to the build directory when ``generated``; ``artifact`` is
    relative to the build directory."""

    name: str
    tool: str  # "cobc" or "cc"
    flags: tuple[str, ...]
    sources: tuple[str, ...]
    artifact: str
    generated: bool = False

    def key_entry(self) -> dict[str, Any]:
        flags = [*self.flags, f"-I {COPYBOOK_DIR}"] if self.tool == "cobc" else list(self.flags)
        return {
            "artifact": self.artifact,
            "flags": flags,
            "name": self.name,
            "sources": list(self.sources),
            "tool": self.tool,
        }

    def argv(self, toolchain: _Toolchain, repo_root: Path, base: Path) -> list[str]:
        source_root = base if self.generated else repo_root
        sources = [str(source_root / source) for source in self.sources]
        output = ["-o", str(base / self.artifact)]
        if self.tool == "cobc":
            include = ["-I", str(repo_root / COPYBOOK_DIR)]
            return [toolchain.cobc, *self.flags, *include, *output, *sources]
        return [toolchain.cc, *self.flags, *output, *sources]


_COBOL_EXECUTABLE = ("-x", *COMMON_FLAGS)
_COBOL_MODULE = ("-m", *COMMON_FLAGS)

# The stubs and drivers, built as their headers state. -O0 keeps the shim's
# page-zero accesses exactly as written.
_TOOL_COMPILES = (
    _Compile(
        "CEE3ABD", "cobc", _COBOL_MODULE, (f"{_STUBS_DIR}/CEE3ABD.cbl",), "modules/CEE3ABD.so"
    ),
    _Compile(
        "CEEDAYS", "cc", ("-std=c11", "-Wall", "-Wextra", "-Werror", "-shared", "-fPIC"),
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
            caller = programs.get(config.get("called_by"))
            caller_compile = (caller or {}).get("compile") or {}
            linked = _string_list(caller_compile.get("link_with"), f"{what} caller link_with")
            if unit not in linked:
                raise BuildError(f"{what} is called, but its called_by unit does not link it")
            units[unit] = {"called_by": config["called_by"], "run": run}
            continue
        compile_config = config.get("compile") or {}
        mode = compile_config.get("mode")
        if run not in _RUN_MODES or _RUN_MODES[run] != mode:
            raise BuildError(f"{what}: run {run!r} does not fit compile mode {mode!r}")
        extra_flags = _string_list(compile_config.get("extra_flags"), f"{what} extra_flags")
        for linked_unit in _string_list(compile_config.get("link_with"), f"{what} link_with"):
            linked_config = programs.get(linked_unit)
            if not isinstance(linked_config, Mapping):
                raise BuildError(f"{what} links {linked_unit}, which {_PROGRAMS_YAML} lacks")
            # One cobc call compiles the main unit and the units it links, so
            # they can only share one flag list.
            linked_flags = _string_list(
                (linked_config.get("compile") or {}).get("extra_flags"), linked_unit
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
        # the C stubs are compiled with cc directly, and the caller's COB_*
        # toolchain variables, because an override changes every compile.
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


def _run_compile(
    task: _Task, toolchain: _Toolchain, repo_root: Path, workspace: Path
) -> str | None:
    """Run one compile with ``cwd`` and ``TMPDIR`` inside ``workspace``;
    return ``None`` on success or the failure detail. The log always gets the
    command line, exit status, stdout and stderr."""
    artifact = task.base / task.job.artifact
    artifact.parent.mkdir(parents=True, exist_ok=True)
    argv = task.job.argv(toolchain, repo_root, task.base)
    env = toolchain.env(workspace, TMPDIR=str(workspace / "tmp"))
    stdout = stderr = b""
    detail: str | None = None
    try:
        completed = subprocess.run(
            argv, cwd=workspace, env=env, stdin=subprocess.DEVNULL, capture_output=True,
            timeout=COMPILE_TIMEOUT_S, check=False,
        )
        stdout, stderr = completed.stdout, completed.stderr
        status = f"exit {completed.returncode}"
        if completed.returncode != 0:
            detail = f"{task.job.tool} exited {completed.returncode}"
    except subprocess.TimeoutExpired as exc:
        stdout, stderr = exc.stdout or b"", exc.stderr or b""
        status = detail = f"timed out after {COMPILE_TIMEOUT_S} s"
    except OSError as exc:
        status = detail = f"cannot start {argv[0]}: {exc}"
    if detail is None and not artifact.is_file():
        detail = f"{task.job.tool} produced no {task.job.artifact}"
    text = "\n".join([
        f"command: {shlex.join(argv)}",
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
    return detail


def _compile_all(
    tasks: Sequence[_Task], toolchain: _Toolchain, repo_root: Path, workspace: Path
) -> None:
    (workspace / "tmp").mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=_PARALLEL_COMPILES) as pool:
        details = list(
            pool.map(lambda task: _run_compile(task, toolchain, repo_root, workspace), tasks)
        )
    shutil.rmtree(workspace / "tmp", ignore_errors=True)
    for task, detail in zip(tasks, details):
        if detail is not None:
            raise _CompileFailure(task.label, task.log.relative_to(workspace).as_posix(), detail)


def _profile_tasks(profile: KeyProfile, directory: Path) -> list[_Task]:
    """Write the generated sources of ``profile`` into ``directory`` and
    return its two compiles."""
    tasks = []
    for path, (compile_, text) in _profile_compiles(profile).items():
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


def _remove_tree(path: Path) -> None:
    shutil.rmtree(path, ignore_errors=True)


@contextmanager
def _key_lock(build_root: Path, key: str) -> Iterator[None]:
    """Hold the exclusive lock of one build key, ``<build root>/<key>.lock``.

    ``fcntl.flock`` locks an open file description, so the lock excludes other
    processes and other threads alike, and the kernel releases it when its
    holder exits, however it ends. The lock file is never deleted: removing it
    would let two sessions lock two different files of the same name."""
    path = build_root / f"{key}{_LOCK_SUFFIX}"
    try:
        descriptor = os.open(path, os.O_RDWR | os.O_CREAT | os.O_CLOEXEC, 0o666)
    except OSError as exc:
        raise BuildError(f"cannot open the build lock {path}: {exc}") from None
    try:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX)
        except OSError as exc:
            raise BuildError(f"cannot lock the build lock {path}: {exc}") from None
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
    The caller holds the key lock."""
    try:
        os.replace(workspace, final)
    except OSError as exc:
        _remove_tree(workspace)
        if not (final / _COMPLETE).is_file():
            raise BuildError(f"cannot publish the build directory {final}: {exc}") from exc


def _build_failure(failure: _CompileFailure, workspace: Path, target: Path) -> BuildError:
    """Keep the failed ``workspace`` as ``target`` and return the error naming
    the failed unit or probe and its log there. The caller holds the key lock."""
    kept = _set_aside(workspace, target)
    log = kept / failure.log
    return BuildError(failure.message(log), unit=failure.unit, log_path=log)


def _populate(
    workspace: Path, key: str, plan: _Plan, toolchain: _Toolchain, repo_root: Path
) -> None:
    for path, text in plan.generated.items():
        (workspace / path).parent.mkdir(parents=True, exist_ok=True)
        (workspace / path).write_text(text, encoding="utf-8")
    tasks = [_Task(compile_, compile_.name, workspace, workspace / "logs" / f"{compile_.name}.log")
             for compile_ in plan.compiles]
    profile_dirs = [workspace / "profiles" / profile.name for profile in plan.profiles]
    for profile, directory in zip(plan.profiles, profile_dirs):
        tasks += _profile_tasks(profile, directory)
    _compile_all(tasks, toolchain, repo_root, workspace)
    for directory in profile_dirs:
        (directory / _COMPLETE).write_text("", encoding="utf-8")
    mode = _probe_clock(toolchain, workspace)
    manifest = {
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
    there is none. A directory that is unfinished or whose manifest does not
    match is removed, so it gets rebuilt. The caller holds the key lock, so no
    other session publishes the directory between the inspection and the
    removal."""
    if not directory.exists():
        return None
    try:
        manifest = load_yaml(directory / _MANIFEST) if (directory / _COMPLETE).is_file() else None
    except Exception:  # an unreadable manifest is treated like a missing one
        manifest = None
    if (
        isinstance(manifest, Mapping)
        and manifest.get("key") == key
        and manifest.get("clock_fraction_mode") in _CLOCK_MODES
    ):
        return manifest["clock_fraction_mode"]
    stale = directory.with_name(f"{directory.name}.stale-{os.getpid()}")
    _remove_tree(_set_aside(directory, stale))
    return None


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
    ``loader`` and ``unloader`` add to it, atomically, for a new profile."""

    def __init__(
        self, *, key: str, directory: Path, repo_root: Path, toolchain: _Toolchain, plan: _Plan,
        clock_fraction_mode: str,
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

    def __repr__(self) -> str:
        return f"BuildCache(key={self.key[:16]}..., dir={self.dir})"

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
        directory = self.dir / "profiles" / profile.name
        if (directory / _COMPLETE).is_file():
            return directory
        with self._lock:
            if not (directory / _COMPLETE).is_file():
                self._build_profile(profile, directory)
        return directory

    def _build_profile(self, profile: KeyProfile, directory: Path) -> None:
        directory.parent.mkdir(parents=True, exist_ok=True)
        workspace = Path(tempfile.mkdtemp(prefix=f".{profile.name}-", dir=directory.parent))
        try:
            tasks = _profile_tasks(profile, workspace)
            _compile_all(tasks, self._toolchain, self._repo_root, workspace)
            (workspace / _COMPLETE).write_text("", encoding="utf-8")
        except _CompileFailure as failure:
            target = directory.with_name(f"{profile.name}.failed")
            with _key_lock(self.dir.parent, self.key):
                error = _build_failure(failure, workspace, target)
            raise error from None
        except BaseException:
            _remove_tree(workspace)
            raise
        # The compiles ran unlocked; only the rename is serialized, so the first
        # session to publish the profile wins and the others use its copy.
        try:
            with _key_lock(self.dir.parent, self.key):
                _publish(workspace, directory)
        except BaseException:
            _remove_tree(workspace)  # a no-op once the workspace is published
            raise


_CACHES: dict[tuple[str, str], BuildCache] = {}
_ENSURE_LOCK = threading.Lock()


def ensure_built(repo_root: Path | str, work_root: Path | str) -> BuildCache:
    """Return the build cache of ``repo_root`` under ``work_root``, compiling
    it first when no finished copy exists. Memoized per process and safe to
    call from every ``prepare``; the build itself is not bounded by any
    scenario deadline."""
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
        if cache is not None and cache.is_complete():
            return cache
        toolchain = _toolchain()
        plan = _plan(root)
        key = _build_key(root, toolchain, plan)
        build_root = work / _BUILD_SUBDIR
        build_root.mkdir(parents=True, exist_ok=True)
        final = build_root / key
        with _key_lock(build_root, key):
            mode = _complete_mode(final, key)
        if mode is None:
            # The compiles run unlocked in a private workspace, so concurrent
            # sessions build side by side and the first to publish wins.
            workspace = Path(tempfile.mkdtemp(prefix=f".{key[:16]}-", dir=build_root))
            try:
                _populate(workspace, key, plan, toolchain, root)
            except _CompileFailure as failure:
                with _key_lock(build_root, key):
                    error = _build_failure(failure, workspace, build_root / f"{key}.failed")
                raise error from None
            except BaseException:
                _remove_tree(workspace)
                raise
            try:
                with _key_lock(build_root, key):
                    _publish(workspace, final)
                    mode = _complete_mode(final, key)
            except BaseException:
                _remove_tree(workspace)  # a no-op once the workspace is published
                raise
            if mode is None:
                raise BuildError(f"the build directory {final} is incomplete after publishing")
        cache = BuildCache(
            key=key, directory=final, repo_root=root, toolchain=toolchain, plan=plan,
            clock_fraction_mode=mode,
        )
        _CACHES[memo_key] = cache
        return cache
