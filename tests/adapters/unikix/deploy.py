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
    files ``0444`` and directories ``0555``. Nothing writes inside it
    afterwards, and source identities hash these originals, never the
    staging copy.
``deploy``
    Builds ``<work root>/unikix/<execution_id>/``, which replaces
    ``$HOME/unikixdemo`` as ``BASE_DIR``: a writable staging copy of
    ``migrated_app`` (``bin/compile`` deletes and moves files in its source
    directory), the ``bin/create_project`` tree, the TPE region, the
    ``bin/deploy_app`` copies, the BPE node and catalog, and the build of
    every program and map. A command that is unavailable, fails or exceeds
    its own ``timeout_s`` raises ``AdapterUnavailable`` with its entry's gap,
    so the scenarios that needed the deployment are ``blocked``.

Writes go only to ``<work root>/_unikix_zip`` (once), its ``.sha256`` marker
and ``.lock`` file beside it, and ``<work root>/unikix/<execution_id>/``.
Nothing under ``app/``, ``samples/`` or ``diagrams/`` is written, the zip is
only read, and ``samples/m2/mf/CardDemo_runtime.zip`` is never opened.
"""

from __future__ import annotations

import contextlib
import fcntl
import io
import os
import shutil
import stat
import sys
import threading
import zipfile
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path, PurePosixPath, PureWindowsPath
from typing import Any

_TESTS_ROOT = str(Path(__file__).resolve().parents[2])
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from adapters.base import AdapterUnavailable  # noqa: E402
from adapters.unikix.env import CommandSet, UniKixEnvironment  # noqa: E402
from harness import REPO_ROOT, HarnessError, sha256_file, sha256_hex, write_text  # noqa: E402

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
    An ``execution_id`` that is not one plain path component raises
    ``HarnessError``."""
    if (
        not isinstance(execution_id, str)
        or execution_id in ("", ".", "..")
        or "/" in execution_id
        or "\\" in execution_id
        or any(char.isspace() for char in execution_id)
    ):
        raise HarnessError(f"execution id {execution_id!r} is not one plain path component")
    return Path(os.path.abspath(work_root)) / "unikix" / execution_id


def extract_zip(
    work_root: str | os.PathLike[str], repo_root: str | os.PathLike[str] = REPO_ROOT
) -> Path:
    """Extract the UniKix zip into ``<work root>/_unikix_zip/`` and return it.

    An extraction whose marker ``<work root>/_unikix_zip.sha256`` names the
    zip's sha256 is reused unchanged; one with another marker, or none, raises
    ``HarnessError`` rather than being replaced. Otherwise every entry except
    ``__MACOSX/`` and ``.DS_Store`` is extracted into a temporary sibling,
    made read-only and renamed into place. An absolute name, a ``..``
    component or a symbolic link anywhere in the zip raises ``HarnessError``
    before anything is written. An exclusive ``flock`` on
    ``<work root>/_unikix_zip.lock`` serializes concurrent callers."""
    work = Path(os.path.abspath(work_root))
    repo = Path(repo_root)
    _refuse_protected(work, "work root", repo)
    work.mkdir(parents=True, exist_ok=True)
    target = work / ZIP_DIR_NAME
    marker = work / f"{ZIP_DIR_NAME}.sha256"

    with _exclusive(work / f"{ZIP_DIR_NAME}.lock"):
        if target.exists() or target.is_symlink():
            recorded = _read_marker(marker)
            if target.is_symlink() or not target.is_dir():
                raise HarnessError(f"{target} exists and is not an extraction directory")
            expected = zip_sha256(repo)
            if recorded != expected:
                raise HarnessError(
                    f"{target} holds an extraction of another zip (marker {recorded or 'missing'}, "
                    f"{ZIP_RELPATH} is {expected}); remove it or choose another work root"
                )
            return target

        source = zip_path(repo)
        try:
            data = source.read_bytes()
        except OSError as exc:
            raise HarnessError(f"cannot read {ZIP_RELPATH}: {exc.strerror or exc}") from None
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
            write_text(marker, f"{digest}\n")
            os.rename(temporary, target)
        except BaseException:
            _remove_tree(temporary)
            raise
        os.chmod(target, 0o555)
    return target


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
    """Write ``members`` below ``destination``, which must not exist yet. A
    name given twice raises ``FileExistsError``; a CRC mismatch
    ``BadZipFile``."""
    destination.mkdir()
    for info, relative in members:
        path = destination.joinpath(*relative.parts)
        if info.is_dir():
            path.mkdir(parents=True, exist_ok=True)
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        with archive.open(info) as source, open(path, "xb") as sink:
            shutil.copyfileobj(source, sink)


def _make_read_only(top: Path) -> None:
    """Files ``0444`` and directories ``0555`` below ``top``; ``top`` itself
    stays writable until it has been renamed into place."""
    for directory, subdirectories, files in os.walk(top, topdown=False):
        for name in files:
            os.chmod(os.path.join(directory, name), 0o444)
        for name in subdirectories:
            os.chmod(os.path.join(directory, name), 0o555)


def _read_marker(marker: Path) -> str | None:
    try:
        return marker.read_text(encoding="ascii").strip() or None
    except FileNotFoundError:
        return None
    except (OSError, UnicodeDecodeError) as exc:
        raise HarnessError(f"cannot read the extraction marker {marker}: {exc}") from None


@contextlib.contextmanager
def _exclusive(lock_path: Path) -> Iterator[None]:
    """Hold an exclusive ``flock`` on ``lock_path`` for the block. Closing the
    descriptor releases it."""
    descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT, 0o644)
    try:
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


# --------------------------------------------------------------------------
# Deployment
# --------------------------------------------------------------------------


def deploy(
    env: UniKixEnvironment,
    commands: CommandSet,
    zip_dir: str | os.PathLike[str] | None = None,
    *,
    repo_root: str | os.PathLike[str] = REPO_ROOT,
) -> Deployment:
    """Deploy and build the UniKix copy in ``env.deployment_root``.

    ``env`` is the session's ``UniKixEnvironment``, loaded with the
    deployment root ``deployment_root(work_root, execution_id)``, and
    ``commands`` its ``CommandSet``. ``zip_dir`` is what ``extract_zip``
    returned for the same work root; ``None`` extracts it here. In order:

    1. the staging copy ``<root>/staging/migrated_app`` and its ``vct.lst``
       BPE node path (missing → GAP-MIS-003);
    2. the ``bin/create_project`` tree, ``region_create`` and
       ``region_create_script``;
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
    raises ``HarnessError``."""
    root = Path(os.path.abspath(env.deployment_root))
    key = str(root.resolve())
    with _ATTEMPTS_LOCK:
        previous = _ATTEMPTS.get(key)
        if isinstance(previous, Deployment):
            return previous
        if previous is not None:
            raise previous
        try:
            deployment = _deploy(env, commands, root, zip_dir, Path(repo_root))
        except Exception as exc:
            _ATTEMPTS[key] = exc
            raise
        _ATTEMPTS[key] = deployment
        return deployment


def _deploy(
    env: UniKixEnvironment,
    commands: CommandSet,
    root: Path,
    zip_dir: str | os.PathLike[str] | None,
    repo_root: Path,
) -> Deployment:
    work = Path(os.path.abspath(env.work_root))
    _refuse_protected(work, "work root", repo_root)
    if root.parent.resolve() != (work / "unikix").resolve():
        raise HarnessError(f"deployment root {root} is not <work root>/unikix/<execution_id>")
    staging = root / _STAGING_DIR / APP_DIR_NAME
    _check_environment(env, root, staging)

    extraction = extract_zip(work, repo_root) if zip_dir is None else Path(zip_dir)
    if extraction.resolve() != (work / ZIP_DIR_NAME).resolve():
        raise HarnessError(f"{extraction} is not the extraction {work / ZIP_DIR_NAME}")
    source = extraction / APP_DIR_NAME
    if not source.is_dir():
        raise HarnessError(f"{extraction} holds no {APP_DIR_NAME}/")
    occupied = [
        name for name in (_STAGING_DIR, BPE_NODE_DIR, PROJECT_DIR) if (root / name).exists()
    ]
    if occupied:
        raise HarnessError(
            f"deployment root {root} already holds {', '.join(occupied)} from another session"
        )
    log_dir = root / "logs" / "build"
    for directory in (root / "home", log_dir):
        directory.mkdir(parents=True, exist_ok=True)

    _stage(source, staging)
    substitutions = [_substitute_bpe_node(staging, env)]

    _create_project_tree(root)
    commands.run("region_create", env)
    commands.run("region_create_script", env)
    _copy_application(staging, env)
    commands.run("region_install", env)

    commands.run("bpe_node_create", env)
    _import_bpe_catalog(staging, env, commands)
    env.source_runtime_scripts()

    built_members = _build_programs(staging, env, commands, log_dir)
    maps = _build_maps(staging, env, commands, log_dir)
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


def _stage(source: Path, staging: Path) -> None:
    """Copy the read-only extraction to ``staging`` and make it writable.
    ``copyfile`` copies content only, and ``copytree`` copies each
    directory's read-only mode, so both are made user-writable afterwards."""
    staging.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source, staging, copy_function=shutil.copyfile)
    for directory, _subdirectories, files in os.walk(staging):
        os.chmod(directory, stat.S_IMODE(os.stat(directory).st_mode) | stat.S_IRWXU)
        for name in files:
            path = os.path.join(directory, name)
            os.chmod(path, stat.S_IMODE(os.stat(path).st_mode) | stat.S_IRUSR | stat.S_IWUSR)


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
    ``BASE_DIR``."""
    (root / BPE_NODE_DIR).mkdir(parents=True, exist_ok=True)
    for name in PROJECT_SUBDIRS:
        (root / PROJECT_DIR / name).mkdir(parents=True, exist_ok=True)


def _copy_application(staging: Path, env: UniKixEnvironment) -> None:
    """The ``bin/deploy_app`` copies, from the staging copy: each regular
    file its ``cp <dir>/*`` names, content only."""
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
            shutil.copyfile(item, target / item.name)


def _import_bpe_catalog(staging: Path, env: UniKixEnvironment, commands: CommandSet) -> None:
    """``bpe_catalog_import`` of the staging copy's ``catlg/bpe`` files, which
    the entry reads from ``$MIGAPP_DIR/catlg/bpe`` itself."""
    directory = staging / "catlg" / "bpe"
    missing = [name for name in BPE_CATALOG_FILES if not (directory / name).is_file()]
    if missing:
        raise AdapterUnavailable(
            f"the staging copy lacks catlg/bpe/{', '.join(missing)} for the BPE catalog import",
            _GAP_CATALOG,
        )
    commands.run("bpe_catalog_import", env)


# --------------------------------------------------------------------------
# Build (bin/compile, bin/compbms)
# --------------------------------------------------------------------------


def _build_programs(
    staging: Path, env: UniKixEnvironment, commands: CommandSet, log_dir: Path
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
        _run_build(commands, env, "build_kixclt", log, member=member)
        translated = source / f"{member}.cbl"
        if not translated.is_file():
            raise AdapterUnavailable(
                f"build_kixclt left no translated {member}.cbl to compile", _GAP_BUILD
            )
        _run_build(commands, env, "build_vcc_online", log, member=member)
        _move_compiler_output(source, member, loadlib)
        translated.unlink()
        built.append(member)
    for member in batch:
        _run_build(commands, env, "build_vcc_batch", log_dir / f"{member}.log", member=member)
        _move_compiler_output(source, member, loadlib)
        built.append(member)
    return built


def _build_maps(
    staging: Path, env: UniKixEnvironment, commands: CommandSet, log_dir: Path
) -> list[str]:
    """Compile every ``.bms`` and move its ``.map`` to ``$PR/maps``, as
    ``bin/compbms`` does; return the maps in build order."""
    source = staging / "bms"
    target = env.path("PR") / "maps"
    maps: list[str] = []
    for name in sorted(path.stem for path in source.glob("*.bms") if path.is_file()):
        _run_build(commands, env, "build_kixbms", log_dir / f"{name}.log", map=name)
        produced = source / f"{name}.map"
        if not produced.is_file():
            # bin/compbms only warns here, but a region without the map
            # cannot send it, so the deployment is refused instead.
            raise AdapterUnavailable(f"build_kixbms left no {name}.map for $PR/maps", _GAP_BUILD)
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
    commands: CommandSet, env: UniKixEnvironment, entry_id: str, log: Path, **values: str
) -> None:
    """Run one build entry, append its outcome and output to ``log``, and
    raise ``AdapterUnavailable`` with the entry's gap unless it exited 0.
    ``check=False`` lets a failing compiler's output reach the log."""
    try:
        result = commands.run(entry_id, env, check=False, **values)
    except AdapterUnavailable as exc:
        _append_log(log, f"--- {entry_id} not run: {exc} ---\n")
        raise
    _append_log(
        log,
        f"--- {entry_id} exit status {result.returncode} ---\n"
        f"--- stdout ---\n{_terminated(result.stdout)}"
        f"--- stderr ---\n{_terminated(result.stderr)}",
    )
    if result.returncode != 0:
        subject = ", ".join(f"{name} {value}" for name, value in sorted(values.items()))
        raise AdapterUnavailable(
            f"{entry_id} exited {result.returncode} for {subject}; output in "
            f"logs/build/{log.name}",
            commands.entry(entry_id).gap,
        )


def _terminated(text: str) -> str:
    return text if not text or text.endswith("\n") else f"{text}\n"


def _append_log(log: Path, text: str) -> None:
    with open(log, "a", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
