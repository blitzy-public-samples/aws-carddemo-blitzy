"""Extraction of the UniKix zip and the test-owned deployment of its CardDemo copy.

The ``unikix`` adapter runs the UniKix copy of CardDemo, ``migrated_app`` in
``samples/m2/unikix/UniKix_CardDemo_runtime_v1.zip``. The ``app/`` sources
cannot run unchanged on UniKix (the ``CBSTM03A`` control-block walk,
GAP-DOC-020), must not be modified, and have not been built there
(GAP-MIS-008).

The zip's own scripts cannot run as they stand: ``bin/create_project`` fixes
``BASE_DIR=$HOME/unikixdemo``, and ``bin/deploy_app`` and ``bin/compile`` set
``MIGAPP_DIR=/home/ec2-user/migrated_app``. This module performs their steps
with the paths ``env.py`` derived from the test-owned deployment root. File
copies and moves are done in Python; every UniKix command goes through
``env.CommandSet.run``, so nothing is issued outside ``commands.yaml``.

``extract_zip``
    Extracts the zip once per work root into ``<work root>/_unikix_zip/``,
    files ``0444`` and directories ``0555``, the root included before it is
    published. Nothing writes inside it afterwards; every reuse checks the
    tree against the zip, and source identities hash these originals, never
    the staging copy.
``deploy``
    Builds ``<work root>/unikix/<execution_id>/``, which replaces
    ``$HOME/unikixdemo`` as ``BASE_DIR``: a writable staging copy of
    ``migrated_app`` (``bin/compile`` deletes and moves files in its source
    directory), the ``bin/create_project`` tree, the TPE region, the
    ``bin/deploy_app`` copies, the BPE node and catalog, and the build of
    every program and map. A command that is unavailable, fails or exceeds
    its own ``timeout_s`` raises ``AdapterUnavailable`` with its entry's gap,
    so the scenarios that needed the deployment are ``blocked``.

    The deployment runs under the deadline of the scenario that asked for
    it. Every command's ``timeout_s`` is capped at the time left, the
    runtime scripts are sourced within it, and it is checked again before
    each filesystem step. Its expiry is never a command failure: it raises
    ``ScenarioTimeout``, so that scenario is ``failing`` with reason
    ``timeout``, and an ``AdapterUnavailable`` raised once it has passed
    becomes the cause of a ``ScenarioTimeout``.

Writes go only to ``<work root>/_unikix_zip`` (once, through its temporary
sibling ``_unikix_zip.tmp-<pid>``), its ``.sha256`` marker (through a
temporary sibling ``._unikix_zip.sha256.<pid>.<hex>.tmp``) and ``.lock``
file beside it, and ``<work root>/unikix/<execution_id>/``. A work root
another user owns, or that group or others can write to, is refused, and no
marker, lock, log, deployment directory, staging file or ``bin/deploy_app``
copy is written through a symbolic link. Nothing under ``app/``,
``samples/`` or ``diagrams/`` is written, the zip is only read, and
``samples/m2/mf/CardDemo_runtime.zip`` is never opened.
"""

from __future__ import annotations

import contextlib
import errno
import fcntl
import io
import os
import shutil
import stat
import sys
import threading
import time
import zipfile
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from pathlib import Path, PurePosixPath, PureWindowsPath
from typing import Any, NoReturn

_TESTS_ROOT = str(Path(__file__).resolve().parents[2])
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from adapters.base import DEFAULT_DEADLINE_S, AdapterUnavailable, ScenarioTimeout  # noqa: E402
from adapters.unikix.env import CommandResult, CommandSet, UniKixEnvironment  # noqa: E402
from harness import REPO_ROOT, HarnessError, sha256_file, sha256_hex  # noqa: E402

__all__ = [
    "APP_DIR_NAME",
    "BPE_CATALOG_FILES",
    "BPE_NODE_DIR",
    "PROJECT_DIR",
    "PROJECT_SUBDIRS",
    "VCT_FILE",
    "VCT_NODE_PATH",
    "ZIP_DIR_NAME",
    "ZIP_RELPATH",
    "Deployment",
    "deploy",
    "deployment_root",
    "extract_zip",
    "zip_path",
    "zip_sha256",
]

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

ZIP_RELPATH = "samples/m2/unikix/UniKix_CardDemo_runtime_v1.zip"
ZIP_DIR_NAME = "_unikix_zip"
APP_DIR_NAME = "migrated_app"

# bin/create_project:7-16, under BASE_DIR = the deployment root.
BPE_NODE_DIR = "bpenode"
PROJECT_DIR = "carddemo"
PROJECT_SUBDIRS = (
    "bin",
    "seqfiles",
    "vsam",
    "history",
    "jcl/ccchk_dir",
    "jcl/include",
    "jcl/ish",
    "jcl/ishp",
    "jcl/jmvs",
    "jcl/jrt",
    "jcl/mvsp",
    "jcl/prt",
    "jcl/skel",
    "jcl/skel_work",
    "jcl/xml",
    "loadlib",
    "lock",
    "maps",
    "parmlib/AWS.M2.CARDDEMO.CNTL",
    "sysoutdir",
    "tmp",
    "util",
)

# Line 1 of the copy's vct.lst ends with the BPE node of the host the zip was
# built on; the staging copy gets this deployment's $EBMHOME instead.
VCT_FILE = "catlg/tpe/vct.lst"
VCT_NODE_PATH = "/home/ec2-user/unikixdemo/bpenode"

# What commands.yaml bpe_catalog_import imports from $MIGAPP_DIR/catlg/bpe.
BPE_CATALOG_FILES = ("BPECAT.cluster.csv", "BPECAT.gdg_base.csv", "BPECAT.nonvsam.csv")

# bin/deploy_app:5-21: (staging directory, target variable, below the target).
_DEPLOY_COPIES = (
    ("jcl", "PR", "jcl/jmvs"),
    ("proc", "PR", "jcl/mvsp"),
    ("ctl", "PARMFILES", "AWS.M2.CARDDEMO.CNTL"),
    ("data/seqfiles", "SEQFILES", ""),
    ("data/vsam", "KIXDATA", ""),
    ("catlg/tpe", "KIXSYS", ""),
)

_GAP_REGION = "GAP-MIS-003"  # region creation, which makes $KIXSYS and installs catlg/tpe
_GAP_CATALOG = "GAP-MIS-007"  # the BPE catalog import
_GAP_BUILD = "GAP-MIS-008"  # the build of the UniKix copy

_PROTECTED_DIRS = ("app", "samples", "diagrams")
_STAGING_DIR = "staging"
_NAME_MAX = 255  # NAME_MAX: the longest name, in bytes, that one path component may have

# Modes, never left to the umask. The published extraction is read-only;
# its temporary sibling is the owner's alone until then; the marker, the
# lock, the logs and the deployment directories are writable only by the
# owner. A work root or deployment directory group or others can write to
# would let them plant links, so it is never trusted as it stands.
_FILE_MODE = 0o444
_DIR_MODE = 0o555
_PRIVATE_FILE_MODE = 0o600
_PRIVATE_DIR_MODE = 0o700
_OWNED_FILE_MODE = 0o644
_OWNED_DIR_MODE = 0o755
_SHARED_WRITE = stat.S_IWGRP | stat.S_IWOTH

# How many problems an extraction that does not match the zip reports.
_PROBLEMS_SHOWN = 10

# One deployment per deployment root, and so per execution id, in this
# process: the Deployment, or the exception that ended the attempt.
_ATTEMPTS: dict[str, Deployment | Exception] = {}
_ATTEMPTS_LOCK = threading.Lock()


# --------------------------------------------------------------------------
# Result
# --------------------------------------------------------------------------


@dataclass
class Deployment:
    """One test-owned deployment of the UniKix copy.

    ``root``
        ``<work root>/unikix/<execution_id>/``, the ``BASE_DIR`` of the run.
    ``staging``
        ``<root>/staging/migrated_app``, the writable copy that was built
        and deployed (``$MIGAPP_DIR``).
    ``zip_dir``
        ``<work root>/_unikix_zip``, the read-only extraction it came from.
    ``log_dir``
        ``<root>/logs/build``, holding ``<member>.log`` with the output of
        each build command of a program or map.
    ``substitutions``
        Each change made to the staging copy, as ``{file, original_sha256,
        deployed_sha256, from, to}``; ``environment.yaml`` records them as
        ``deployment_substitutions``.
    ``built_members``
        The programs compiled into ``$PR/loadlib``, in build order.
    ``maps``
        The maps compiled into ``$PR/maps``, in build order.
    ``snapshot``
        The post-deployment catalog snapshot, which ``adapter.py`` sets once
        ``bpe.py`` has taken it."""

    root: Path
    staging: Path
    zip_dir: Path
    log_dir: Path
    substitutions: list[dict[str, str]]
    built_members: list[str]
    maps: list[str]
    snapshot: Any = None


# --------------------------------------------------------------------------
# The zip
# --------------------------------------------------------------------------


def zip_path(repo_root: str | os.PathLike[str] = REPO_ROOT) -> Path:
    """The UniKix zip of the checkout at ``repo_root``."""
    return Path(repo_root) / ZIP_RELPATH


def zip_sha256(repo_root: str | os.PathLike[str] = REPO_ROOT) -> str:
    """The sha256 of the UniKix zip; ``HarnessError`` when it cannot be read."""
    try:
        return sha256_file(zip_path(repo_root))
    except OSError as exc:
        raise HarnessError(f"cannot read {ZIP_RELPATH}: {exc.strerror or exc}") from None


def deployment_root(work_root: str | os.PathLike[str], execution_id: str) -> Path:
    """``<work root>/unikix/<execution_id>``, the deployment root of one run.
    ``execution_id`` must be one plain path component: a ``str`` other than
    ``""``, ``.`` and ``..`` that holds no ``/``, ``\\``, whitespace or
    non-printable character (NUL and the other control characters among
    them), and that the file system encoding encodes in at most 255 bytes
    (``NAME_MAX``). Any other ``execution_id`` raises ``HarnessError``."""
    if (
        not isinstance(execution_id, str)
        or execution_id in ("", ".", "..")
        or "/" in execution_id
        or "\\" in execution_id
        or any(char.isspace() for char in execution_id)
        or not execution_id.isprintable()
    ):
        raise HarnessError(f"execution id {execution_id!r} is not one plain path component")
    try:
        encoded = os.fsencode(execution_id)
    except UnicodeEncodeError:
        raise HarnessError(
            f"execution id {execution_id!r} is not one plain path component"
        ) from None
    if len(encoded) > _NAME_MAX:
        raise HarnessError(
            f"execution id {execution_id!r} is longer than {_NAME_MAX} bytes, the longest name"
            " a path component may have"
        )
    return Path(os.path.abspath(work_root)) / "unikix" / execution_id


def extract_zip(
    work_root: str | os.PathLike[str], repo_root: str | os.PathLike[str] = REPO_ROOT
) -> Path:
    """Extract the UniKix zip into ``<work root>/_unikix_zip/`` and return it.

    The work root is created ``0700`` when missing; one that another user
    owns, or that group or others can write to, raises ``HarnessError``. An
    extraction whose marker ``<work root>/_unikix_zip.sha256`` names the
    zip's sha256, and whose tree matches the zip (the same entries and
    nothing else, no symbolic link, each file's bytes, files ``0444`` and
    directories ``0555``, all of the effective user), is reused unchanged;
    one with another marker or none, or that does not match, raises
    ``HarnessError`` rather than being replaced. Otherwise every entry except
    ``__MACOSX/`` and ``.DS_Store`` is extracted into a private temporary
    sibling, made read-only, root included, and renamed into place after
    the marker is written. An absolute name, a ``..`` component or a
    symbolic link anywhere in the zip raises ``HarnessError`` before
    anything is written. A marker or lock that is a symbolic link is
    refused, never followed. An exclusive ``flock`` on
    ``<work root>/_unikix_zip.lock`` serializes concurrent callers."""
    work = Path(os.path.abspath(work_root))
    repo = Path(repo_root)
    _refuse_protected(work, "work root", repo)
    try:
        work.mkdir(mode=_PRIVATE_DIR_MODE, parents=True, exist_ok=True)
    except OSError as exc:
        raise HarnessError(f"cannot create the work root {work}: {exc.strerror or exc}") from None
    _check_work_root(work)
    target = work / ZIP_DIR_NAME
    marker = work / f"{ZIP_DIR_NAME}.sha256"

    with _exclusive(work / f"{ZIP_DIR_NAME}.lock"):
        if target.exists() or target.is_symlink():
            recorded = _read_marker(marker)
            if target.is_symlink() or not target.is_dir():
                raise HarnessError(f"{target} exists and is not an extraction directory")
            # The tree is checked against the very bytes hashed, so neither a
            # replaced zip nor a damaged or planted tree is ever reused.
            data = _read_zip(repo)
            expected = sha256_hex(data)
            if recorded != expected:
                raise HarnessError(
                    f"{target} holds an extraction of another zip (marker {recorded or 'missing'}, "
                    f"{ZIP_RELPATH} is {expected}); remove it or choose another work root"
                )
            _verify_extraction(target, data)
            return target

        data = _read_zip(repo)
        # The marker hashes the very bytes extracted, so a zip replaced while
        # this runs cannot leave an extraction under the other zip's checksum.
        digest = sha256_hex(data)
        temporary = work / f"{ZIP_DIR_NAME}.tmp-{os.getpid()}"
        _remove_tree(temporary)
        try:
            try:
                with zipfile.ZipFile(io.BytesIO(data)) as archive:
                    _extract_members(archive, _safe_members(archive), temporary)
            except (zipfile.BadZipFile, OSError) as exc:
                raise HarnessError(f"cannot extract {ZIP_RELPATH}: {exc}") from None
            _make_read_only(temporary)
            # Marker before rename: a run stopped between the two leaves a
            # marker without a directory, which the next call re-extracts,
            # never a directory without a marker, which it would refuse.
            _write_marker(marker, f"{digest}\n")
            os.rename(temporary, target)
        except BaseException:
            _remove_tree(temporary)
            raise
    return target


def _read_zip(repo_root: Path) -> bytes:
    """The bytes of the UniKix zip; ``HarnessError`` when it cannot be read."""
    try:
        return zip_path(repo_root).read_bytes()
    except OSError as exc:
        raise HarnessError(f"cannot read {ZIP_RELPATH}: {exc.strerror or exc}") from None


def _safe_members(archive: zipfile.ZipFile) -> list[tuple[zipfile.ZipInfo, PurePosixPath]]:
    """The entries to extract with their relative paths. Every unsafe entry
    is reported at once in one ``HarnessError``."""
    members: list[tuple[zipfile.ZipInfo, PurePosixPath]] = []
    problems: list[str] = []
    for info in archive.infolist():
        name = info.filename
        relative = PurePosixPath(name)
        if relative.parts[:1] == ("__MACOSX",) or relative.name == ".DS_Store":
            continue
        windows = PureWindowsPath(name)
        if name.startswith(("/", "\\")) or windows.drive or windows.is_absolute():
            problems.append(f"{name!r} is absolute")
        elif ".." in PurePosixPath(name.replace("\\", "/")).parts:
            problems.append(f"{name!r} has a '..' component")
        elif stat.S_ISLNK(info.external_attr >> 16):
            problems.append(f"{name!r} is a symbolic link")
        elif not relative.parts:
            problems.append(f"{name!r} names no file")
        else:
            members.append((info, relative))
    if problems:
        raise HarnessError(f"{ZIP_RELPATH} holds unsafe entries: {'; '.join(problems)}")
    return members


def _extract_members(
    archive: zipfile.ZipFile,
    members: list[tuple[zipfile.ZipInfo, PurePosixPath]],
    destination: Path,
) -> None:
    """Write ``members`` below ``destination``, which must not exist yet.
    Directories are created ``0700`` and files ``0600`` whatever the umask,
    so nobody else can change the tree before it is made read-only. A name
    given twice, or as both a file and a directory, raises
    ``FileExistsError``; a CRC mismatch ``BadZipFile``."""
    os.mkdir(destination, _PRIVATE_DIR_MODE)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC
    for info, relative in members:
        if info.is_dir():
            _make_directories(destination, relative.parts)
            continue
        _make_directories(destination, relative.parts[:-1])
        descriptor = os.open(destination.joinpath(*relative.parts), flags, _PRIVATE_FILE_MODE)
        with os.fdopen(descriptor, "wb") as sink, archive.open(info) as source:
            shutil.copyfileobj(source, sink)


def _make_directories(
    destination: Path, parts: tuple[str, ...], mode: int = _PRIVATE_DIR_MODE
) -> None:
    """Create each missing directory of ``parts`` below ``destination`` with
    ``mode``, ``0700`` unless given, whatever the umask, one component at a
    time: ``Path.mkdir(parents=True)`` would give the parents the umask's
    mode and descend through a symbolic link. A component that exists as
    anything but a real directory, a symbolic link included, raises
    ``FileExistsError``. ``destination`` is the effective user's and nobody
    else can write below it, so the ``chmod`` reaches the directory just
    made."""
    path = destination
    for part in parts:
        path /= part
        try:
            os.mkdir(path, mode)
        except FileExistsError:
            if not stat.S_ISDIR(os.lstat(path).st_mode):
                raise
            continue
        os.chmod(path, mode)


def _make_read_only(top: Path) -> None:
    """Files ``0444`` and directories ``0555`` in ``top``, ``top`` itself
    last. The caller does this before renaming ``top`` into place, so a run
    stopped at any point never leaves a published extraction writable;
    renaming a read-only directory within its parent needs no write
    permission on it."""
    for directory, subdirectories, files in os.walk(top, topdown=False):
        for name in files:
            os.chmod(os.path.join(directory, name), _FILE_MODE)
        for name in subdirectories:
            os.chmod(os.path.join(directory, name), _DIR_MODE)
    os.chmod(top, _DIR_MODE)


def _verify_extraction(target: Path, data: bytes) -> None:
    """``HarnessError`` unless ``target`` holds exactly what extracting the
    zip ``data`` writes: the same files and directories and nothing else, no
    symbolic link or special file, each file's bytes equal to its member's,
    files ``0444`` and directories ``0555`` (``target`` included), all of
    the effective user. A damaged or tampered extraction is refused, never
    replaced."""
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            problems = _extraction_problems(target, archive)
    except (zipfile.BadZipFile, OSError) as exc:
        raise HarnessError(f"cannot check {target} against {ZIP_RELPATH}: {exc}") from None
    if problems:
        more = len(problems) - _PROBLEMS_SHOWN
        rest = f", and {more} more" if more > 0 else ""
        raise HarnessError(
            f"{target} does not match {ZIP_RELPATH}: {'; '.join(problems[:_PROBLEMS_SHOWN])}"
            f"{rest}; remove it or choose another work root"
        )


def _extraction_problems(target: Path, archive: zipfile.ZipFile) -> list[str]:
    """What keeps ``target`` from being the extraction of ``archive``, the
    extraction root first and then each entry by path; empty when nothing
    does."""
    files, directories = _expected_entries(archive)
    found, unlisted = _scan_tree(target)
    problems = _entry_problems(PurePosixPath(), os.lstat(target), directory=True) + unlisted
    for relative in sorted(files.keys() | directories | found.keys()):
        status = found.get(relative)
        if status is None:
            problems.append(f"{relative} is missing")
        elif relative in files:
            problems.extend(_file_problems(target, relative, status, archive, files[relative]))
        elif relative in directories:
            problems.extend(_entry_problems(relative, status, directory=True))
        else:
            problems.append(f"{relative} is not in the zip")
    return problems


def _expected_entries(
    archive: zipfile.ZipFile,
) -> tuple[dict[PurePosixPath, zipfile.ZipInfo], set[PurePosixPath]]:
    """The files extracting ``archive`` writes, each with its member, and the
    directories it creates: every directory entry and every parent of an
    entry."""
    files: dict[PurePosixPath, zipfile.ZipInfo] = {}
    directories: set[PurePosixPath] = set()
    for info, relative in _safe_members(archive):
        directories.update(relative.parents)
        if info.is_dir():
            directories.add(relative)
        else:
            files[relative] = info
    directories.discard(PurePosixPath())
    return files, directories


def _scan_tree(top: Path) -> tuple[dict[PurePosixPath, os.stat_result], list[str]]:
    """Every entry below ``top`` by relative path with its ``lstat``,
    descending into real directories only, so no symbolic link is followed;
    and a problem for each directory that could not be listed."""
    found: dict[PurePosixPath, os.stat_result] = {}
    problems: list[str] = []
    pending = [PurePosixPath()]
    while pending:
        relative = pending.pop()
        try:
            with os.scandir(top.joinpath(*relative.parts)) as entries:
                for entry in entries:
                    status = entry.stat(follow_symlinks=False)
                    found[relative / entry.name] = status
                    if stat.S_ISDIR(status.st_mode):
                        pending.append(relative / entry.name)
        except OSError as exc:
            problems.append(f"{_shown(relative)} cannot be listed: {exc.strerror or exc}")
    return found, problems


def _entry_problems(
    relative: PurePosixPath, status: os.stat_result, *, directory: bool
) -> list[str]:
    """How the entry ``relative`` departs from what extraction makes of a
    directory or a file: its type, its mode and its owner."""
    name = _shown(relative)
    if not (stat.S_ISDIR(status.st_mode) if directory else stat.S_ISREG(status.st_mode)):
        wanted = "a directory" if directory else "a regular file"
        return [f"{name} is {_file_type(status)}, not {wanted}"]
    problems: list[str] = []
    mode = stat.S_IMODE(status.st_mode)
    expected = _DIR_MODE if directory else _FILE_MODE
    if mode != expected:
        problems.append(f"{name} has mode {mode:04o}, not {expected:04o}")
    if status.st_uid != os.geteuid():
        problems.append(f"{name} is owned by uid {status.st_uid}, not {os.geteuid()}")
    return problems


def _shown(relative: PurePosixPath) -> str:
    """``relative`` as messages name it; the extraction root has no path."""
    return str(relative) if relative.parts else "the extraction root"


def _file_problems(
    target: Path,
    relative: PurePosixPath,
    status: os.stat_result,
    archive: zipfile.ZipFile,
    info: zipfile.ZipInfo,
) -> list[str]:
    """``_entry_problems`` of the extracted file ``relative``, plus a
    difference between its bytes and those of its member ``info``."""
    problems = _entry_problems(relative, status, directory=False)
    if not stat.S_ISREG(status.st_mode):
        return problems
    if status.st_size == info.file_size:
        try:
            content = _read_regular(target.joinpath(*relative.parts))
        except OSError as exc:
            problems.append(f"{relative} cannot be read: {exc.strerror or exc}")
            return problems
        if content == archive.read(info):
            return problems
    problems.append(f"{relative} differs from its zip member")
    return problems


def _read_regular(path: Path) -> bytes:
    """The bytes of ``path``, opened without following a symbolic link;
    ``O_NONBLOCK`` keeps a FIFO swapped in from blocking the open."""
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as handle:
        return handle.read()


def _file_type(status: os.stat_result) -> str:
    """The file type of ``status``, for messages."""
    for test, name in (
        (stat.S_ISLNK, "a symbolic link"),
        (stat.S_ISDIR, "a directory"),
        (stat.S_ISREG, "a regular file"),
    ):
        if test(status.st_mode):
            return name
    return "a special file"


def _read_marker(marker: Path) -> str | None:
    """The sha256 the marker records, or ``None`` when it is missing or
    empty. A marker that is a symbolic link, not a regular file or another
    user's raises ``HarnessError``; ``O_NONBLOCK`` keeps a planted FIFO from
    blocking the open."""
    flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK
    try:
        descriptor = os.open(marker, flags)
    except FileNotFoundError:
        return None
    except OSError as exc:
        raise _open_error("extraction marker", marker, exc) from None
    try:
        with os.fdopen(descriptor, "rb") as handle:
            problem = _ownership_problem(os.fstat(descriptor), directory=False)
            if problem:
                raise HarnessError(f"the extraction marker {marker} {problem}")
            return handle.read().decode("ascii").strip() or None
    except (OSError, UnicodeDecodeError) as exc:
        raise HarnessError(f"cannot read the extraction marker {marker}: {exc}") from None


def _write_marker(marker: Path, text: str) -> None:
    """Replace the marker with ``text`` atomically, mode ``0644``. The text
    goes to a new temporary sibling, created with ``O_EXCL|O_NOFOLLOW``, that
    ``os.replace`` swaps in; the sibling is removed when anything fails. A
    marker that exists as anything but a regular file of the effective user
    raises ``HarnessError``, so a planted link is refused, never followed."""
    try:
        status: os.stat_result | None = os.lstat(marker)
    except FileNotFoundError:
        status = None
    except OSError as exc:
        raise HarnessError(
            f"cannot inspect the extraction marker {marker}: {exc.strerror or exc}"
        ) from None
    problem = None if status is None else _ownership_problem(status, directory=False)
    if problem:
        raise HarnessError(f"the extraction marker {marker} {problem}")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC
    while True:
        temporary = marker.with_name(f".{marker.name}.{os.getpid()}.{os.urandom(4).hex()}.tmp")
        try:
            descriptor = os.open(temporary, flags, _OWNED_FILE_MODE)
            break
        except FileExistsError:
            continue
        except OSError as exc:
            raise HarnessError(
                f"cannot write the extraction marker {marker}: {exc.strerror or exc}"
            ) from None
    try:
        with os.fdopen(descriptor, "w", encoding="ascii", newline="\n") as handle:
            handle.write(text)
        os.replace(temporary, marker)
    except BaseException as exc:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(temporary)
        if isinstance(exc, OSError):
            raise HarnessError(
                f"cannot write the extraction marker {marker}: {exc.strerror or exc}"
            ) from None
        raise


@contextlib.contextmanager
def _exclusive(lock_path: Path) -> Iterator[None]:
    """Hold an exclusive ``flock`` on ``lock_path`` for the block. Closing the
    descriptor releases it. The lock is never opened through a symbolic
    link, and must be a regular file of the effective user; ``HarnessError``
    if not."""
    flags = os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_CLOEXEC
    try:
        descriptor = os.open(lock_path, flags, _OWNED_FILE_MODE)
    except OSError as exc:
        raise _open_error("extraction lock", lock_path, exc) from None
    try:
        problem = _ownership_problem(os.fstat(descriptor), directory=False)
        if problem:
            raise HarnessError(f"the extraction lock {lock_path} {problem}")
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        yield
    finally:
        os.close(descriptor)


def _remove_tree(path: Path) -> None:
    """Remove ``path``, a tree this module created, read-only parts
    included; nothing when it does not exist."""
    if path.is_symlink() or path.is_file():
        path.unlink()
        return
    if not path.exists():
        return
    for directory, _subdirectories, _files in os.walk(path):
        os.chmod(directory, 0o700)
    shutil.rmtree(path)


def _refuse_protected(path: Path, what: str, repo_root: Path) -> None:
    """``HarnessError`` when ``path`` lies in ``app/``, ``samples/`` or
    ``diagrams/`` of ``repo_root``, which the suite never writes."""
    resolved = path.resolve()
    for name in _PROTECTED_DIRS:
        protected = (repo_root / name).resolve()
        if resolved == protected or protected in resolved.parents:
            raise HarnessError(f"{what} {path} lies inside {name}/, which the suite never writes")


def _check_work_root(work: Path) -> None:
    """``HarnessError`` unless ``work`` is a directory of the effective user
    that neither group nor others can write to: in any other, someone else
    can have planted the links and files this module would write through or
    trust. A symbolic link naming it must be the user's own as well."""
    try:
        link = os.lstat(work)
        status = os.stat(work)
    except OSError as exc:
        raise HarnessError(f"cannot inspect the work root {work}: {exc.strerror or exc}") from None
    if stat.S_ISLNK(link.st_mode) and link.st_uid != os.geteuid():
        raise HarnessError(
            f"work root {work} is a symbolic link owned by uid {link.st_uid}, not "
            f"{os.geteuid()}; choose another work root"
        )
    problem = _ownership_problem(status, directory=True)
    if problem:
        raise HarnessError(f"work root {work} {problem}; choose another work root")
    if status.st_mode & _SHARED_WRITE:
        raise HarnessError(
            f"work root {work} is writable by group or others (mode "
            f"{stat.S_IMODE(status.st_mode):04o}), so they could plant links in it; "
            f"run chmod go-w on it or choose another work root"
        )


def _ownership_problem(status: os.stat_result, *, directory: bool) -> str | None:
    """Why ``status`` is not a real directory, or a regular file, of the
    effective user; ``None`` when it is."""
    if stat.S_ISLNK(status.st_mode):
        return "is a symbolic link"
    if directory and not stat.S_ISDIR(status.st_mode):
        return "is not a directory"
    if not directory and not stat.S_ISREG(status.st_mode):
        return "is not a regular file"
    if status.st_uid != os.geteuid():
        return f"is owned by uid {status.st_uid}, not {os.geteuid()}"
    return None


def _open_error(what: str, path: Path, exc: OSError) -> HarnessError:
    """The ``HarnessError`` for ``exc``, raised by opening ``path`` with
    ``O_NOFOLLOW``."""
    if exc.errno == errno.ELOOP:
        return HarnessError(f"the {what} {path} is a symbolic link")
    return HarnessError(f"cannot open the {what} {path}: {exc.strerror or exc}")


# --------------------------------------------------------------------------
# Deployment
# --------------------------------------------------------------------------


def deploy(
    env: UniKixEnvironment,
    commands: CommandSet,
    zip_dir: str | os.PathLike[str] | None = None,
    *,
    repo_root: str | os.PathLike[str] = REPO_ROOT,
    deadline: float | Callable[[], float] | None = None,
) -> Deployment:
    """Deploy and build the UniKix copy in ``env.deployment_root``.

    ``env`` is the session's ``UniKixEnvironment``, loaded with the
    deployment root ``deployment_root(work_root, execution_id)``, and
    ``commands`` its ``CommandSet``. ``zip_dir``, when given, must be what
    ``extract_zip`` returned for the same work root; either way the
    extraction is obtained through ``extract_zip``, which makes it or checks
    it against the zip. The work root must pass ``extract_zip``'s ownership
    check. ``<work root>/unikix``, the root, its ``home/``, ``logs/`` and
    ``logs/build/`` must be real directories of the effective user (made
    ``0755`` when missing, group and other write bits removed) before the
    root is checked for an earlier deployment or anything is written below
    it. No log, staging file, project directory or ``bin/deploy_app`` copy
    is written through a symbolic link. ``deadline`` is the deadline of the
    scenario that needs the deployment: a ``time.monotonic()`` value, a
    function returning the seconds left (such as ``Adapter.time_left``), or
    ``None`` for ``DEFAULT_DEADLINE_S`` from this call; anything else raises
    ``HarnessError``. In order:

    1. the staging copy ``<root>/staging/migrated_app`` (directories
       ``0755``, files ``0644``) and its ``vct.lst`` BPE node path (missing
       → GAP-MIS-003);
    2. the ``bin/create_project`` tree (directories ``0755``),
       ``region_create`` and ``region_create_script``;
    3. the ``bin/deploy_app`` copies from the staging copy, then
       ``region_install``;
    4. ``bpe_node_create`` and ``bpe_catalog_import``, then
       ``env.source_runtime_scripts()`` so later commands see ``userenv``
       and ``batchenv``;
    5. ``build_kixclt`` and ``build_vcc_online`` for every ``.cl2``,
       ``build_vcc_batch`` for every ``.cbl`` and ``build_kixbms`` for every
       ``.bms``, each output moved as ``bin/compile`` and ``bin/compbms`` move
       it (missing → GAP-MIS-008).

    A failed command raises ``AdapterUnavailable`` with its entry's gap. A
    deployment root names one execution, so a second call for it returns
    the same ``Deployment``, or raises again what ended the first attempt;
    a root that already holds a deployment this process did not make
    raises ``HarnessError``.

    Every command's ``timeout_s`` is capped at the seconds ``deadline``
    leaves, ``env.source_runtime_scripts`` is bounded by them, and the
    deadline is checked before and after the extraction and before each
    filesystem step. Once it has passed this call raises ``ScenarioTimeout``,
    with an ``AdapterUnavailable`` raised after it as its cause. A later
    call for the same root, under its own deadline, gets:

    * nothing cached when the deadline stopped the attempt before the
      staging copy, so it deploys afresh;
    * once the root holds a partial deployment, which cannot be redeployed
      this session, ``AdapterUnavailable`` with the gap of the step in
      progress and the ``ScenarioTimeout`` as its cause;
    * the ``Deployment``, when the deadline passed only after the last
      step."""
    time_left = _time_left_function(deadline)
    root = Path(os.path.abspath(env.deployment_root))
    key = str(root.resolve())
    with _ATTEMPTS_LOCK:
        previous = _ATTEMPTS.get(key)
        if isinstance(previous, Deployment):
            return previous
        if previous is not None:
            raise previous
        progress = _Progress(time_left)
        try:
            deployment = _deploy(env, commands, root, zip_dir, Path(repo_root), progress)
        except ScenarioTimeout as exc:
            # Only this caller's deadline has passed: a later scenario gets
            # the partial root as unavailable, never this timeout.
            if progress.occupied:
                stopped = AdapterUnavailable(
                    f"the deployment of {root} was stopped at an earlier scenario's deadline "
                    f"during {progress.step} ({exc}), so it cannot be redeployed this session",
                    progress.gap,
                )
                stopped.__cause__ = exc
                _ATTEMPTS[key] = stopped
            raise
        except Exception as exc:
            _ATTEMPTS[key] = exc
            if isinstance(exc, AdapterUnavailable) and time_left() <= 0:
                raise ScenarioTimeout(
                    f"scenario deadline passed during the deployment of {root}; "
                    f"superseded: {exc}"
                ) from exc
            raise
        _ATTEMPTS[key] = deployment
        if time_left() <= 0:
            raise ScenarioTimeout(
                f"scenario deadline passed during the deployment of {root}, which completed "
                f"and is kept for later scenarios"
            )
        return deployment


def _time_left_function(deadline: Any) -> Callable[[], float]:
    """``deadline`` as a seconds-left function, never negative: a
    ``time.monotonic()`` value, a function returning the seconds left (such
    as ``Adapter.time_left``), or ``None`` for ``DEFAULT_DEADLINE_S`` from
    now. A ``bool`` or any other value raises ``HarnessError``."""
    if callable(deadline):
        return lambda: max(0.0, float(deadline()))
    if deadline is None:
        end = time.monotonic() + DEFAULT_DEADLINE_S
    elif isinstance(deadline, bool) or not isinstance(deadline, (int, float)):
        raise HarnessError(f"deadline must be a monotonic time or a function, got {deadline!r}")
    else:
        end = float(deadline)
    return lambda: max(0.0, end - time.monotonic())


class _Progress:
    """The deadline of one deployment attempt and the step it has reached.

    ``step`` and ``gap`` name the step in progress, which a passed deadline
    stopped; ``occupied`` becomes true once the deployment root holds
    something a new attempt would refuse."""

    def __init__(self, time_left: Callable[[], float]) -> None:
        self.time_left = time_left
        self.step = "the checks before the staging copy"
        self.gap = _GAP_REGION
        self.occupied = False

    def check(self, when: str) -> None:
        """``ScenarioTimeout`` when the deadline has passed ``when``."""
        if self.time_left() <= 0:
            raise ScenarioTimeout(f"scenario deadline passed {when}")

    def begin(self, step: str, gap: str) -> None:
        """Enter the filesystem ``step``, whose stop leaves ``gap`` open;
        ``ScenarioTimeout`` instead when the deadline has passed."""
        self.step, self.gap = step, gap
        self.check(f"before {step}")

    def run(
        self,
        commands: CommandSet,
        entry_id: str,
        env: UniKixEnvironment,
        *,
        subject: str = "",
        **arguments: Any,
    ) -> CommandResult:
        """``commands.run(entry_id, env, **arguments)`` capped at the
        seconds left now; ``subject`` names the member or map built."""
        self.step = f"{entry_id} for {subject}" if subject else entry_id
        self.gap = commands.entry(entry_id).gap
        return commands.run(entry_id, env, deadline_left=self.time_left(), **arguments)


def _deploy(
    env: UniKixEnvironment,
    commands: CommandSet,
    root: Path,
    zip_dir: str | os.PathLike[str] | None,
    repo_root: Path,
    progress: _Progress,
) -> Deployment:
    work = Path(os.path.abspath(env.work_root))
    _refuse_protected(work, "work root", repo_root)
    _check_work_root(work)
    if root.parent.resolve() != (work / "unikix").resolve():
        raise HarnessError(f"deployment root {root} is not <work root>/unikix/<execution_id>")
    staging = root / _STAGING_DIR / APP_DIR_NAME
    _check_environment(env, root, staging)

    if zip_dir is not None and Path(zip_dir).resolve() != (work / ZIP_DIR_NAME).resolve():
        raise HarnessError(f"{zip_dir} is not the extraction {work / ZIP_DIR_NAME}")
    # Always through extract_zip, which checks the extraction against the
    # zip under its lock, so a damaged or planted tree is never staged.
    progress.check("before extracting the zip")
    extraction = extract_zip(work, repo_root)
    progress.check("after extracting the zip")
    source = extraction / APP_DIR_NAME
    if not source.is_dir():
        raise HarnessError(f"{extraction} holds no {APP_DIR_NAME}/")
    # Hardened first, each directory after its parent: env.py creates the
    # root with the umask's mode, so until then someone else could plant the
    # names checked below, or a link the writes below would follow.
    log_dir = root / "logs" / "build"
    for directory in (work / "unikix", root, root / "home", root / "logs", log_dir):
        _own_directory(directory)
    occupied = [
        name
        for name in (_STAGING_DIR, BPE_NODE_DIR, PROJECT_DIR)
        if os.path.lexists(root / name)
    ]
    if occupied:
        raise HarnessError(
            f"deployment root {root} already holds {', '.join(occupied)} from another session"
        )
    _check_log_file(root / "logs" / "commands.log")

    progress.begin("the staging copy", _GAP_REGION)
    progress.occupied = True
    _stage(source, staging)
    progress.begin(f"the {VCT_FILE} BPE node substitution", _GAP_REGION)
    substitutions = [_substitute_bpe_node(staging, env)]

    progress.begin("the bin/create_project tree", _GAP_REGION)
    _create_project_tree(root)
    progress.run(commands, "region_create", env)
    progress.run(commands, "region_create_script", env)
    progress.begin("the bin/deploy_app copies", _GAP_REGION)
    _copy_application(staging, env)
    progress.run(commands, "region_install", env)

    progress.run(commands, "bpe_node_create", env)
    _import_bpe_catalog(staging, env, commands, progress)
    # userenv and batchenv are sourced in one bash, so a stop there is
    # attributed to region creation, of which userenv, sourced first, is part.
    progress.step, progress.gap = "sourcing userenv and batchenv", _GAP_REGION
    env.source_runtime_scripts(deadline_left=progress.time_left())

    built_members = _build_programs(staging, env, commands, log_dir, progress)
    maps = _build_maps(staging, env, commands, log_dir, progress)
    return Deployment(
        root=root,
        staging=staging,
        zip_dir=extraction,
        log_dir=log_dir,
        substitutions=substitutions,
        built_members=built_members,
        maps=maps,
    )


def _check_environment(env: UniKixEnvironment, root: Path, staging: Path) -> None:
    """The paths ``env.py`` derived must be the ones this deployment builds,
    or commands would run against another tree; ``HarnessError`` if not."""
    expected = {
        "BASE_DIR": root,
        "PROJECT_ROOT": root / PROJECT_DIR,
        "EBMHOME": root / BPE_NODE_DIR,
        "MIGAPP_DIR": staging,
    }
    wrong = [
        f"{name}={env.get(name) or '(unset)'}"
        for name, path in expected.items()
        if not env.get(name) or Path(os.path.abspath(str(env.get(name)))) != path
    ]
    if wrong:
        raise HarnessError(
            f"the UniKix environment does not describe the deployment root {root}: "
            f"{', '.join(wrong)}"
        )


def _own_directory(path: Path) -> None:
    """Create ``path`` with mode ``0755`` when it is missing. It must be a
    real directory of the effective user, or ``HarnessError``. Group and
    other write bits are removed: ``env.py`` creates the root and its
    ``home/`` with the mode the umask allows. The caller passes each
    directory after its parent, the work root having passed
    ``_check_work_root``, and before it looks into ``path`` or writes below
    it, so once ``path`` passes nobody else can swap it or add an entry to
    it."""
    try:
        os.mkdir(path, _OWNED_DIR_MODE)
    except FileExistsError:
        pass
    except OSError as exc:
        raise HarnessError(
            f"cannot create the deployment directory {path}: {exc.strerror or exc}"
        ) from None
    try:
        status = os.lstat(path)
    except OSError as exc:
        raise HarnessError(
            f"cannot inspect the deployment directory {path}: {exc.strerror or exc}"
        ) from None
    problem = _ownership_problem(status, directory=True)
    if problem:
        raise HarnessError(
            f"deployment directory {path} {problem}; remove it or choose another work root"
        )
    if status.st_mode & _SHARED_WRITE:
        try:
            os.chmod(path, stat.S_IMODE(status.st_mode) & ~_SHARED_WRITE)
        except OSError as exc:
            raise HarnessError(
                f"cannot make the deployment directory {path} writable by its owner only: "
                f"{exc.strerror or exc}"
            ) from None


def _check_log_file(log: Path) -> None:
    """``HarnessError`` when ``log`` exists and is not a regular file of the
    effective user: ``env.CommandSet`` appends to ``commands.log`` by its
    path, so a planted link there would carry the log outside the root."""
    try:
        status = os.lstat(log)
    except FileNotFoundError:
        return
    except OSError as exc:
        raise HarnessError(f"cannot inspect the command log {log}: {exc.strerror or exc}") from None
    problem = _ownership_problem(status, directory=False)
    if problem:
        raise HarnessError(
            f"the command log {log} {problem}; remove it or choose another work root"
        )


def _stage(source: Path, staging: Path) -> None:
    """Copy the verified, read-only extraction ``source`` to ``staging``,
    writable by its owner: directories ``0755`` and files ``0644`` whatever
    the umask, file content only. ``staging``'s parent ``<root>/staging``,
    ``staging`` and every directory and file below it are created
    exclusively, in sorted order, and nothing is opened through a symbolic
    link, so a name that exists already, a planted link included, raises
    ``HarnessError`` and is never written through. ``source`` holds only
    directories and regular files once ``extract_zip`` has verified it; any
    other entry, or a directory that cannot be listed, raises
    ``HarnessError`` as well."""
    _claim_directory(staging.parent)
    try:
        _new_directory(staging)
        for directory, subdirectories, files in os.walk(source, onerror=_reraise):
            subdirectories.sort()
            here = Path(directory)
            target = staging / here.relative_to(source)
            for name in sorted(files):
                _copy_new_file(here / name, target / name)
            for name in subdirectories:
                status = os.lstat(here / name)
                if not stat.S_ISDIR(status.st_mode):
                    raise HarnessError(
                        f"{here / name} in the extraction is {_file_type(status)}, not a directory"
                    )
                _new_directory(target / name)
    except FileExistsError as exc:
        raise HarnessError(
            f"{exc.filename} exists already in the staging copy {staging}; it is never "
            f"written through"
        ) from None
    except OSError as exc:
        where = f"{exc.filename}: " if exc.filename else ""
        raise HarnessError(
            f"cannot stage {source} in {staging}: {where}{exc.strerror or exc}"
        ) from None


def _claim_directory(path: Path) -> None:
    """Create ``path``, a directory of the deployment root, as
    ``_new_directory`` does. One that exists already, a symbolic link
    included, belongs to another session and raises ``HarnessError``, as do
    other failures."""
    try:
        _new_directory(path)
    except FileExistsError:
        raise HarnessError(
            f"deployment root {path.parent} already holds {path.name} from another session"
        ) from None
    except OSError as exc:
        raise HarnessError(
            f"cannot create the deployment directory {path}: {exc.strerror or exc}"
        ) from None


def _new_directory(path: Path) -> None:
    """Create ``path`` with mode ``0755`` whatever the umask.
    ``FileExistsError`` when the name exists, as a symbolic link or anything
    else, which is never followed. The caller's parent directory is the
    effective user's and nobody else can write to it, so the ``chmod``
    reaches the directory just made."""
    os.mkdir(path, _OWNED_DIR_MODE)
    os.chmod(path, _OWNED_DIR_MODE)


def _copy_new_file(source: Path, destination: Path) -> None:
    """Copy the bytes of ``source``, a regular file of the extraction, to
    the new file ``destination``, mode ``0644`` whatever the umask: content
    only, as ``shutil.copyfile`` copies. ``destination`` is created with
    ``O_EXCL|O_NOFOLLOW``, so a name that exists already, a symbolic link
    included, raises ``FileExistsError`` and is never written through.
    ``source`` is opened without following a link; one that is not a regular
    file raises ``HarnessError``."""
    try:
        reader = os.open(source, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK)
    except OSError as exc:
        raise _open_error("extracted file", source, exc) from None
    with os.fdopen(reader, "rb") as handle:
        status = os.fstat(reader)
        if not stat.S_ISREG(status.st_mode):
            raise HarnessError(
                f"{source} in the extraction is {_file_type(status)}, not a regular file"
            )
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC
        writer = os.open(destination, flags, _OWNED_FILE_MODE)
        with os.fdopen(writer, "wb") as sink:
            os.fchmod(writer, _OWNED_FILE_MODE)
            shutil.copyfileobj(handle, sink)


def _reraise(error: OSError) -> NoReturn:
    """``os.walk``'s ``onerror``: a directory that cannot be listed stops the
    walk instead of being skipped."""
    raise error


def _substitute_bpe_node(staging: Path, env: UniKixEnvironment) -> dict[str, str]:
    """Replace the zip's BPE node path at the end of ``vct.lst`` line 1 with
    ``$EBMHOME``, in the staging copy only, and return the record of it."""
    path = staging / VCT_FILE
    node = str(env.path("EBMHOME"))
    if any(char in node for char in ",\r\n"):
        # vct.lst is a comma-separated list: such a path would add fields.
        raise HarnessError(
            f"$EBMHOME {node!r} cannot stand in vct.lst: it holds ',' or a line break"
        )
    try:
        original = path.read_bytes()
    except FileNotFoundError:
        raise AdapterUnavailable(
            f"the staging copy has no {VCT_FILE}, the region's resource list", _GAP_REGION
        ) from None
    first, newline, rest = original.partition(b"\n")
    carriage = b"\r" if first.endswith(b"\r") else b""
    body = first[: len(first) - len(carriage)]
    old = VCT_NODE_PATH.encode("ascii")
    if not body.endswith(old):
        raise AdapterUnavailable(
            f"{VCT_FILE} line 1 does not end with the BPE node path {VCT_NODE_PATH}, so the "
            f"region cannot be pointed at this deployment's node",
            _GAP_REGION,
        )
    deployed = body[: len(body) - len(old)] + os.fsencode(node) + carriage + newline + rest
    path.write_bytes(deployed)
    return {
        "file": f"{APP_DIR_NAME}/{VCT_FILE}",
        "original_sha256": sha256_hex(original),
        "deployed_sha256": sha256_hex(deployed),
        "from": VCT_NODE_PATH,
        "to": node,
    }


def _create_project_tree(root: Path) -> None:
    """The directories ``bin/create_project`` creates, under ``root`` as
    ``BASE_DIR``, each with mode ``0755`` whatever the umask. ``bpenode/``
    and ``carddemo/`` are created exclusively, so one that exists already
    raises ``HarnessError``. Each directory below ``carddemo/`` is created a
    component at a time; a component may exist only as a real directory,
    as an earlier entry leaves ``jcl/``, and anything else, a symbolic link
    included, raises ``HarnessError`` rather than being followed."""
    _claim_directory(root / BPE_NODE_DIR)
    project = root / PROJECT_DIR
    _claim_directory(project)
    for name in PROJECT_SUBDIRS:
        try:
            _make_directories(project, PurePosixPath(name).parts, _OWNED_DIR_MODE)
        except FileExistsError as exc:
            raise HarnessError(
                f"project directory {exc.filename} exists and is not a directory"
            ) from None
        except OSError as exc:
            raise HarnessError(
                f"cannot create the project directory {exc.filename or project / name}: "
                f"{exc.strerror or exc}"
            ) from None


def _copy_application(staging: Path, env: UniKixEnvironment) -> None:
    """The ``bin/deploy_app`` copies, from the staging copy: each regular
    file its ``cp <dir>/*`` names, content only, through
    ``_overwrite_file``, so no copy is written through a symbolic link."""
    for directory, variable, below in _DEPLOY_COPIES:
        target = env.path(variable) / below if below else env.path(variable)
        if not target.is_dir():
            if variable == "KIXSYS":
                raise AdapterUnavailable(
                    f"region_create left no region directory $KIXSYS ({target}) to copy "
                    f"catlg/tpe into",
                    _GAP_REGION,
                )
            raise HarnessError(f"deployment directory ${variable}/{below} ({target}) is missing")
        for item in sorted((staging / directory).iterdir()):
            if item.name.startswith(".") or not item.is_file():
                continue  # cp <dir>/* names neither dot files nor directories
            _overwrite_file(item, target / item.name)


def _overwrite_file(source: Path, destination: Path) -> None:
    """Copy the bytes of ``source`` to ``destination`` as ``cp`` does: a
    missing file is created with mode ``0644``, which the umask can only
    narrow, and an existing regular file, such as
    one ``region_create`` wrote in ``$KIXSYS``, is emptied and overwritten
    with its mode kept. ``destination`` is opened with ``O_NOFOLLOW`` and
    checked before it is emptied: a symbolic link, or anything but a regular
    file of the effective user, raises ``HarnessError`` with nothing written,
    and ``O_NONBLOCK`` keeps a planted FIFO from blocking the open."""
    flags = os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK
    try:
        descriptor = os.open(destination, flags, _OWNED_FILE_MODE)
    except OSError as exc:
        raise _open_error("deployed file", destination, exc) from None
    try:
        with os.fdopen(descriptor, "wb") as sink:
            problem = _ownership_problem(os.fstat(descriptor), directory=False)
            if problem:
                raise HarnessError(f"the deployed file {destination} {problem}")
            os.ftruncate(descriptor, 0)
            with open(source, "rb") as handle:
                shutil.copyfileobj(handle, sink)
    except OSError as exc:
        raise HarnessError(
            f"cannot copy {source} to {destination}: {exc.strerror or exc}"
        ) from None


def _import_bpe_catalog(
    staging: Path, env: UniKixEnvironment, commands: CommandSet, progress: _Progress
) -> None:
    """``bpe_catalog_import`` of the staging copy's ``catlg/bpe`` files, which
    the entry reads from ``$MIGAPP_DIR/catlg/bpe`` itself."""
    progress.begin("the BPE catalog file check", _GAP_CATALOG)
    directory = staging / "catlg" / "bpe"
    missing = [name for name in BPE_CATALOG_FILES if not (directory / name).is_file()]
    if missing:
        raise AdapterUnavailable(
            f"the staging copy lacks catlg/bpe/{', '.join(missing)} for the BPE catalog import",
            _GAP_CATALOG,
        )
    progress.run(commands, "bpe_catalog_import", env)


# --------------------------------------------------------------------------
# Build (bin/compile, bin/compbms)
# --------------------------------------------------------------------------


def _build_programs(
    staging: Path,
    env: UniKixEnvironment,
    commands: CommandSet,
    log_dir: Path,
    progress: _Progress,
) -> list[str]:
    """Translate and compile every ``.cl2``, then compile every ``.cbl``, as
    ``bin/compile`` does for each file; return the members in build order."""
    source = staging / "cbl"
    online = sorted(path.stem for path in source.glob("*.cl2") if path.is_file())
    batch = sorted(path.stem for path in source.glob("*.cbl") if path.is_file())
    clash = sorted(set(online) & set(batch))
    if clash:
        # kixclt writes <member>.cbl, which bin/compile deletes afterwards:
        # a batch source of the same name would be overwritten and lost.
        raise HarnessError(f"cbl/ holds both a .cl2 and a .cbl for {', '.join(clash)}")
    loadlib = env.path("PR") / "loadlib"
    built: list[str] = []
    for member in online:
        log = log_dir / f"{member}.log"
        _run_build(commands, env, "build_kixclt", log, progress, member=member)
        translated = source / f"{member}.cbl"
        if not translated.is_file():
            raise AdapterUnavailable(
                f"build_kixclt left no translated {member}.cbl to compile", _GAP_BUILD
            )
        _run_build(commands, env, "build_vcc_online", log, progress, member=member)
        progress.begin(f"the moves of member {member}'s build output", _GAP_BUILD)
        _move_compiler_output(source, member, loadlib)
        translated.unlink()
        built.append(member)
    for member in batch:
        log = log_dir / f"{member}.log"
        _run_build(commands, env, "build_vcc_batch", log, progress, member=member)
        progress.begin(f"the moves of member {member}'s build output", _GAP_BUILD)
        _move_compiler_output(source, member, loadlib)
        built.append(member)
    return built


def _build_maps(
    staging: Path,
    env: UniKixEnvironment,
    commands: CommandSet,
    log_dir: Path,
    progress: _Progress,
) -> list[str]:
    """Compile every ``.bms`` and move its ``.map`` to ``$PR/maps``, as
    ``bin/compbms`` does; return the maps in build order."""
    source = staging / "bms"
    target = env.path("PR") / "maps"
    maps: list[str] = []
    for name in sorted(path.stem for path in source.glob("*.bms") if path.is_file()):
        _run_build(commands, env, "build_kixbms", log_dir / f"{name}.log", progress, map=name)
        produced = source / f"{name}.map"
        if not produced.is_file():
            # bin/compbms only warns here, but a region without the map
            # cannot send it, so the deployment is refused instead.
            raise AdapterUnavailable(f"build_kixbms left no {name}.map for $PR/maps", _GAP_BUILD)
        progress.begin(f"the move of map {name}", _GAP_BUILD)
        shutil.move(str(produced), str(target / produced.name))
        maps.append(name)
    return maps


def _move_compiler_output(source: Path, member: str, loadlib: Path) -> None:
    """Delete ``<member>.err`` and move ``<member>.class`` and ``.list`` to
    ``$PR/loadlib`` (``bin/compile:52-56``)."""
    (source / f"{member}.err").unlink(missing_ok=True)
    outputs = [source / f"{member}{suffix}" for suffix in (".class", ".list")]
    missing = [path.name for path in outputs if not path.is_file()]
    if missing:
        raise AdapterUnavailable(
            f"the compiler left no {' or '.join(missing)} to move to $PR/loadlib", _GAP_BUILD
        )
    for path in outputs:
        shutil.move(str(path), str(loadlib / path.name))


def _run_build(
    commands: CommandSet,
    env: UniKixEnvironment,
    entry_id: str,
    log: Path,
    progress: _Progress,
    **values: str,
) -> None:
    """Run one build entry, capped at the seconds ``progress`` leaves, append
    its outcome and output to ``log``, and raise ``AdapterUnavailable`` with
    the entry's gap unless it exited 0. ``check=False`` lets a failing
    compiler's output reach the log.

    Every ``AdapterUnavailable`` names the entry, the member or map and
    the log: one the entry raised itself (failed probe, process that could
    not start, own ``timeout_s`` exceeded) keeps its gap and is the cause.
    A ``ScenarioTimeout`` is logged and raised unchanged."""
    subject = ", ".join(f"{name} {value}" for name, value in sorted(values.items()))
    try:
        result = progress.run(commands, entry_id, env, subject=subject, check=False, **values)
    except ScenarioTimeout as exc:
        _append_log(log, f"--- {entry_id} stopped at the scenario deadline: {exc} ---\n")
        raise
    except AdapterUnavailable as exc:
        _append_log(log, f"--- {entry_id} did not complete: {exc} ---\n")
        raise AdapterUnavailable(
            f"{entry_id} for {subject}: {exc.reason}; see logs/build/{log.name}", exc.gap_id
        ) from exc
    _append_log(
        log,
        f"--- {entry_id} exit status {result.returncode} ---\n"
        f"--- stdout ---\n{_terminated(result.stdout)}"
        f"--- stderr ---\n{_terminated(result.stderr)}",
    )
    if result.returncode != 0:
        raise AdapterUnavailable(
            f"{entry_id} exited {result.returncode} for {subject}; output in "
            f"logs/build/{log.name}",
            commands.entry(entry_id).gap,
        )


def _terminated(text: str) -> str:
    return text if not text or text.endswith("\n") else f"{text}\n"


def _append_log(log: Path, text: str) -> None:
    """Append ``text`` to ``log``, never through a symbolic link: the log must
    be a regular file of the effective user, or ``HarnessError``.
    ``O_NONBLOCK`` keeps a planted FIFO from blocking the open."""
    flags = (
        os.O_WRONLY | os.O_APPEND | os.O_CREAT | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK
    )
    try:
        descriptor = os.open(log, flags, _OWNED_FILE_MODE)
    except OSError as exc:
        raise _open_error("build log", log, exc) from None
    try:
        with os.fdopen(descriptor, "a", encoding="utf-8", newline="\n") as handle:
            problem = _ownership_problem(os.fstat(descriptor), directory=False)
            if problem:
                raise HarnessError(f"the build log {log} {problem}")
            handle.write(text)
    except OSError as exc:
        raise HarnessError(f"cannot write the build log {log}: {exc.strerror or exc}") from None
