"""Immutable evidence of the ``unikix`` adapter's scenario runs.

UniKix scenarios never run in CI, so the evidence an operator run writes, and
later commits, is their only record. ``EvidenceWriter`` writes it to
``tests/reports/unikix/<execution_id>/<scenario_id>/``, the one place outside
the work root a ``unikix`` run writes to. It is raw data, excluded from the
deterministic projection: its execution id holds a UTC time by design, and its
files hold installation paths. ``harness/report.py --import-unikix`` reads it
and writes ``validation.yaml`` beside ``result.yaml``; this module never does.

Each scenario directory holds:

``datasets/<NAME>.bin``
    The bytes of each dataset and observation, unchanged. A name becomes a
    file name by replacing every character outside ``[A-Za-z0-9._-]`` with
    ``_`` (``@CATALOG`` -> ``_CATALOG.bin``, ``@AIX:<path>`` ->
    ``_AIX_<path>.bin``, ``@AFTER:<JOB>:<LOGICAL>`` ->
    ``_AFTER_<JOB>_<LOGICAL>.bin``, ``@REGION-FILES`` -> ``_REGION-FILES.bin``,
    ``@TDQ:JOBS`` -> ``_TDQ_JOBS.bin``); ``result.yaml`` maps each file name
    back to its name under ``capture_summary.datasets``.
``sysout/<path>``
    BPE sysout, generated JCL, the scenario's command log (without elapsed
    times, ``env.CommandSet.render_evidence_log``) and build-log excerpts,
    unchanged. ``capture_summary.sysout`` maps each path back to
    the name the adapter gave it.
``screens.txt``
    The screens for people to read: a ``--- step <n> session <s> @map <map>
    ---`` line per captured screen, then its rows with trailing spaces
    trimmed. It is the only trimmed copy and is never compared.
``environment.yaml``
    ``Capture.environment`` (installation and deployment paths, version
    probes, deployment substitutions, ``s3270`` and the listener address) and
    ``run_date``: ``bpe_job_start_date`` and ``first_step_screen_date``, the
    run date the relative-clock matchers are judged against (null when the
    scenario showed none), plus any other run-date facts the drivers record.
``result.yaml``
    Written last, so its presence means the evidence is complete. It holds
    every field of ``harness.results.RECORD_FIELDS`` from the record, with
    ``adapter: unikix``, the writer's ``execution_id`` and the
    ``evidence_path`` of this directory, and besides them:

    * ``observed``: ``return_code``, ``messages`` and ``screens`` of the
      ``adapters.base.Result`` the run produced (null when none was), which,
      with ``datasets/``, reconstructs that Result for import and
      revalidation. ``elapsed_s`` is left out.
    * ``observed_messages``: every ``ERRMSG`` and unformatted-screen
      ``@text`` value seen, in order, for the import's search for messages
      that match no inventory item (GAP-UNC-012).
    * ``screens_raw``: each captured screen with its rows exactly as read.
    * ``capture_summary``: ``datasets`` and ``sysout`` (file -> name),
      ``steps`` (the step, session, map and attempt of each captured screen)
      and ``job_numbers`` (the BPE job numbers).

Evidence is never overwritten or deleted. The writer claims a scenario
directory by creating it exclusively, so only the write whose creation
succeeds puts files in it and two writes never mix theirs; a scenario
directory that already exists, empty or not, is refused with ``HarnessError``
and kept as found. Every file is created exclusively. Evidence stays inside
``reports/unikix/``: the writer opens the tests root once and reaches every
folder below it (``reports/``, ``unikix/``, the execution and scenario
folders and the folders in them) through the descriptor of the folder above,
with ``O_DIRECTORY|O_NOFOLLOW``, and creates each file in its folder's
descriptor with ``O_EXCL|O_NOFOLLOW``, so a symbolic link in place of any of
those folders or files is refused, never followed. The folders it creates
get mode ``0755`` and its files ``0644``, whatever the umask. An existing
``reports/``, ``unikix/`` or execution folder must be a real folder of the
effective user, and loses its group and other write bits before anything is
created or looked up in it, so once a write has passed a folder nobody else
can swap it for a link or add an entry to it. The tests root itself is
trusted as found: whoever can write it can change the suite. Evidence is
written only for an id of the closed scenario id manifest
(``harness.scenarios.ALL_IDS``) and a record that conforms to the result
record contract as a stored record (``harness.results.record_problems``),
the checks the import applies. Everything to be written is checked before
the first file is created, so a refused write leaves no file behind.
"""

from __future__ import annotations

import contextlib
import errno
import os
import re
import socket
import stat
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from datetime import datetime, timezone
from hashlib import sha256
from pathlib import Path
from typing import Any

from adapters.base import Result
from harness import TESTS_ROOT, HarnessError, display_path, dump_yaml
from harness import results as _results
from harness import scenarios as _scenarios

__all__ = [
    "DATASETS_DIR",
    "ENVIRONMENT_FILE",
    "EXECUTION_ID_RE",
    "RESULT_FILE",
    "SCREENS_FILE",
    "SYSOUT_DIR",
    "Capture",
    "EvidenceWriter",
    "dataset_file_name",
    "evidence_path",
    "evidence_root",
    "make_execution_id",
]

RESULT_FILE = "result.yaml"
SCREENS_FILE = "screens.txt"
ENVIRONMENT_FILE = "environment.yaml"
DATASETS_DIR = "datasets"
SYSOUT_DIR = "sysout"

# The form harness.results accepts for a unikix execution id.
EXECUTION_ID_RE = re.compile(r"unikix-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}")
# A scenario id as harness.results accepts it in an evidence path; evidence
# is further written only for the ids of the manifest (harness.scenarios.ALL_IDS).
_SCENARIO_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
_UNSAFE_CHAR_RE = re.compile(r"[^A-Za-z0-9._-]")
_HOSTNAME_DIGITS = 8

# The run-date facts environment.yaml always names (AAP 0.4.6, clock on unikix).
_RUN_DATE_KEYS = ("bpe_job_start_date", "first_step_screen_date")
# What capture_summary.steps keeps of each screens_raw entry.
_STEP_KEYS = ("attempt", "step", "session", "map")

# Modes, never left to the umask: every evidence folder is writable by its
# owner alone, so nobody else can swap one for a link or add an entry to it.
_DIR_MODE = 0o755
_FILE_MODE = 0o644
_SHARED_WRITE = stat.S_IWGRP | stat.S_IWOTH
# Each folder below the tests root is opened relative to the folder above and
# never through a symbolic link; each file is created new, never through one.
_DIR_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
_FILE_FLAGS = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC


# --------------------------------------------------------------------------
# Identifiers
# --------------------------------------------------------------------------


def make_execution_id(now: datetime | None = None, hostname: str | None = None) -> str:
    """``unikix-<UTC yyyymmddThhmmssZ>-<first 8 hex of sha256(hostname)>``.

    ``now`` defaults to the current UTC time and must be timezone-aware, since
    a naive time names no instant; ``hostname`` defaults to
    ``socket.gethostname()``. Pure: no file is read or written."""
    if now is None:
        now = datetime.now(timezone.utc)
    elif not isinstance(now, datetime):
        raise TypeError(f"now must be a datetime, got {type(now).__name__}")
    elif now.utcoffset() is None:
        raise ValueError(f"now must be timezone-aware, got the naive {now.isoformat()}")
    if hostname is None:
        hostname = socket.gethostname()
    elif not isinstance(hostname, str):
        raise TypeError(f"hostname must be a str, got {type(hostname).__name__}")
    utc = now.astimezone(timezone.utc)
    # Formatted field by field: strftime's %Y leaves years before 1000 unpadded.
    stamp = (
        f"{utc.year:04d}{utc.month:02d}{utc.day:02d}"
        f"T{utc.hour:02d}{utc.minute:02d}{utc.second:02d}Z"
    )
    digest = sha256(hostname.encode("utf-8")).hexdigest()[:_HOSTNAME_DIGITS]
    return f"unikix-{stamp}-{digest}"


def _path_component(value: Any, what: str) -> str:
    """``value`` when it can name one directory: a non-empty string without
    ``/``, ``\\``, ``..``, whitespace or control characters."""
    if not isinstance(value, str) or not value:
        raise ValueError(f"{what} must be a non-empty string, got {value!r}")
    if (
        "/" in value
        or "\\" in value
        or ".." in value
        or any(char.isspace() or not char.isprintable() for char in value)
    ):
        raise ValueError(
            f"{what} {value!r} cannot name a directory: it holds '/', '\\', '..', "
            f"whitespace or a control character"
        )
    return value


def _execution_id(value: Any) -> str:
    _path_component(value, "execution_id")
    if not EXECUTION_ID_RE.fullmatch(value):
        raise ValueError(
            f"execution_id {value!r} is not unikix-<yyyymmddThhmmssZ>-<8 hex> (make_execution_id)"
        )
    return value


def _scenario_id(value: Any) -> str:
    """``value`` when it is an id of the closed scenario id manifest, the
    only ids evidence directories are created for."""
    _path_component(value, "scenario_id")
    if not _SCENARIO_ID_RE.fullmatch(value):
        raise ValueError(f"scenario_id {value!r} is not a scenario id")
    if value not in _scenarios.ALL_IDS:
        raise ValueError(
            f"scenario_id {value!r} is not an id of the closed scenario id manifest (harness.scenarios.ALL_IDS)"
        )
    return value


def _tests_root(tests_root: str | os.PathLike[str] | None) -> Path:
    return TESTS_ROOT if tests_root is None else Path(tests_root)


def evidence_root(tests_root: str | os.PathLike[str] | None, execution_id: str) -> Path:
    """``<tests_root>/reports/unikix/<execution_id>``; ``tests_root`` ``None``
    is the suite's own ``tests/`` folder (``harness.TESTS_ROOT``)."""
    return _tests_root(tests_root) / "reports" / "unikix" / _execution_id(execution_id)


def evidence_path(execution_id: str, scenario_id: str) -> str:
    """``tests/reports/unikix/<execution_id>/<scenario_id>``: the
    repository-relative POSIX ``evidence_path`` of the scenario's records.
    ``ValueError`` when ``scenario_id`` is not an id of the manifest
    (``harness.scenarios.ALL_IDS``) or ``execution_id`` lacks its form."""
    scenario_id = _scenario_id(scenario_id)
    execution_id = _execution_id(execution_id)
    # One definition of the form, shared with validation.yaml and the matrix.
    return _results.evidence_path_for(_results.UNIKIX, scenario_id, execution_id)


def dataset_file_name(name: str) -> str:
    """The ``datasets/`` file name of a dataset or observation ``name``: every
    character outside ``[A-Za-z0-9._-]`` becomes ``_``, then ``.bin``."""
    if not isinstance(name, str) or not name:
        raise HarnessError(f"dataset names must be non-empty strings, got {name!r}")
    return _UNSAFE_CHAR_RE.sub("_", name) + ".bin"


def _sysout_path(name: Any) -> str:
    """The ``sysout/``-relative POSIX path of a sysout file ``name``. Empty
    and ``.`` parts are dropped, a part of dots only becomes underscores, and
    every other character outside ``[A-Za-z0-9._-]`` becomes ``_``, so no name
    reaches outside ``sysout/``."""
    if not isinstance(name, str) or not name:
        raise HarnessError(f"sysout names must be non-empty strings, got {name!r}")
    parts = []
    for part in name.replace("\\", "/").split("/"):
        if part in ("", "."):
            continue
        if set(part) == {"."}:
            part = "_" * len(part)
        parts.append(_UNSAFE_CHAR_RE.sub("_", part))
    if not parts:
        raise HarnessError(f"sysout name {name!r} names no file")
    return "/".join(parts)


# --------------------------------------------------------------------------
# Capture
# --------------------------------------------------------------------------


@dataclass
class Capture:
    """What the adapter observes of one scenario, filled during ``prepare``
    and ``execute`` and written by ``EvidenceWriter``.

    ``screens_raw``
        ``{step, session, map, rows, attempt?}`` per captured screen, ``rows``
        the 24 rows of 80 characters as read (``adapters.unikix.tpe``).
    ``datasets``
        Logical dataset or observation name (``ACCTDATA``, ``@CATALOG``,
        ``@AIX:<path>``, ``@AFTER:<JOB>:<LOGICAL>``, ``@REGION-FILES``,
        ``@TDQ:JOBS``) -> the exact bytes put in ``Result.datasets``.
    ``sysout``
        Relative file name -> bytes (text is written as UTF-8): BPE sysout,
        generated JCL, the scenario's command log, build-log excerpts.
    ``observed_messages``
        ``{step, session, field, value, attempt?}`` for every ``ERRMSG`` and
        unformatted-screen ``@text`` value seen, in order.
    ``environment``
        The installation and deployment facts ``environment.yaml`` records.
    ``run_date``
        ``bpe_job_start_date`` and ``first_step_screen_date``, plus any other
        run-date facts (``day_changes``).
    ``job_numbers``
        The BPE job number of each submitted job, in submission order.
    ``result``
        The ``adapters.base.Result`` that ``execute`` returned, which the
        adapter sets so the evidence can reconstruct it; ``None`` when the
        run produced none. Its datasets are written with ``datasets``."""

    screens_raw: list[dict[str, Any]] = field(default_factory=list)
    datasets: dict[str, bytes] = field(default_factory=dict)
    sysout: dict[str, bytes] = field(default_factory=dict)
    observed_messages: list[dict[str, Any]] = field(default_factory=list)
    environment: dict[str, Any] = field(default_factory=dict)
    run_date: dict[str, Any] = field(default_factory=dict)
    job_numbers: list[Any] = field(default_factory=list)
    # Carried in the capture because the adapter's write_evidence hook receives
    # only the record, while the import has to rebuild the Result (return code,
    # messages, screens) to compare and revalidate it.
    result: Result | None = None


# --------------------------------------------------------------------------
# Writer
# --------------------------------------------------------------------------


def _bytes(value: Any, what: str, *, text_ok: bool = False) -> bytes:
    if isinstance(value, (bytes, bytearray, memoryview)):
        return bytes(value)
    if text_ok and isinstance(value, str):
        try:
            return value.encode("utf-8")
        except UnicodeEncodeError as exc:
            raise HarnessError(f"{what}: text holds {value[exc.start]!r}, which is not UTF-8") from None
    kinds = "bytes or str" if text_ok else "bytes"
    raise HarnessError(f"{what} must hold {kinds}, got {type(value).__name__}")


def _mapping(value: Any, what: str) -> Mapping:
    if not isinstance(value, Mapping):
        raise HarnessError(f"{what} must be a mapping, got {type(value).__name__}")
    return value


def _sequence(value: Any, what: str) -> Sequence:
    if isinstance(value, (str, bytes, bytearray)) or not isinstance(value, Sequence):
        raise HarnessError(f"{what} must be a list, got {type(value).__name__}")
    return value


def _kept(descriptors: contextlib.ExitStack, descriptor: int) -> int:
    """``descriptor``, closed when ``descriptors`` closes."""
    descriptors.callback(os.close, descriptor)
    return descriptor


def _open_tests_root(path: Path) -> int:
    """A descriptor of the tests root ``path``, the one folder every evidence
    folder is reached from. A symbolic link in ``path`` is followed on
    purpose: whoever can write ``tests/`` or a folder above it can already
    rewrite the suite's own code, so the tests root is trusted as found."""
    try:
        return os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
    except OSError as exc:
        raise HarnessError(f"cannot open the tests root {display_path(path)}: {exc.strerror or exc}") from None


def _open_folder(parent: int, name: str, shown: Path, *, created: bool) -> int:
    """A descriptor of the folder ``name`` of the open folder ``parent``,
    opened with ``O_DIRECTORY|O_NOFOLLOW`` and vetted before anything is
    created or looked up in it; ``shown`` is its path for messages. A
    symbolic link or a file in its place, or a folder another user owns,
    raises ``HarnessError``. A folder this write ``created`` gets mode
    ``0755`` whatever the umask; an existing one loses its group and other
    write bits. The change goes through the descriptor, so it reaches the
    folder that was vetted, and once the folder passes nobody else can swap
    it for a link or add an entry to it."""
    try:
        folder = os.open(name, _DIR_FLAGS, dir_fd=parent)
    except OSError as exc:
        if exc.errno in (errno.ENOTDIR, errno.ELOOP):
            raise HarnessError(
                f"{display_path(shown)} is not a folder of its own: evidence is never written "
                f"through a symbolic link or over a file"
            ) from None
        raise HarnessError(
            f"cannot open the evidence folder {display_path(shown)}: {exc.strerror or exc}"
        ) from None
    try:
        try:
            status = os.fstat(folder)
        except OSError as exc:
            raise HarnessError(
                f"cannot inspect the evidence folder {display_path(shown)}: {exc.strerror or exc}"
            ) from None
        if not stat.S_ISDIR(status.st_mode):
            raise HarnessError(
                f"{display_path(shown)} is not a folder of its own: evidence is never written "
                f"through a symbolic link or over a file"
            )
        euid = os.geteuid()
        if status.st_uid != euid:
            raise HarnessError(
                f"{display_path(shown)} is owned by uid {status.st_uid}, not {euid}: evidence is "
                f"written only into folders of its own user"
            )
        mode = stat.S_IMODE(status.st_mode)
        wanted = _DIR_MODE if created else mode & ~_SHARED_WRITE
        if wanted != mode:
            try:
                os.fchmod(folder, wanted)
            except OSError as exc:
                raise HarnessError(
                    f"cannot make the evidence folder {display_path(shown)} writable by its owner "
                    f"only: {exc.strerror or exc}"
                ) from None
    except BaseException:
        os.close(folder)
        raise
    return folder


def _set_new_folder_mode(parent: int, name: str, shown: Path) -> None:
    """Give the folder ``name`` this write has just created in the open
    folder ``parent`` mode ``0755``, before it is opened: a umask that takes
    its owner's read bit would otherwise make ``_open_folder`` fail on it.
    ``shown`` is its path for messages."""
    # Set by name relative to ``parent``, which is the tests root, trusted as
    # found, or a folder already opened without following a link, owned by
    # the effective user and stripped of group and other write, so only this
    # user can have replaced the name since its creation. The folder is then
    # opened with O_NOFOLLOW and vetted by _open_folder.
    try:
        os.chmod(name, _DIR_MODE, dir_fd=parent)
    except OSError as exc:
        raise HarnessError(
            f"cannot create the evidence folder {display_path(shown)}: {exc.strerror or exc}"
        ) from None


def _own_folder(parent: int, name: str, shown: Path) -> int:
    """The folder ``name`` of the open folder ``parent``, created unless it
    exists, as a vetted descriptor (``_open_folder``); ``shown`` is its path
    for messages. ``reports/``, ``unikix/`` and the execution folder are
    reached this way, and so are ``datasets/``, ``sysout/`` and each folder
    below ``sysout/`` in the claimed scenario folder."""
    try:
        os.mkdir(name, _DIR_MODE, dir_fd=parent)
    except FileExistsError:
        created = False
    except OSError as exc:
        raise HarnessError(
            f"cannot create the evidence folder {display_path(shown)}: {exc.strerror or exc}"
        ) from None
    else:
        _set_new_folder_mode(parent, name, shown)
        created = True
    return _open_folder(parent, name, shown, created=created)


def _occupant(parent: int, name: str) -> str:
    """What already stands at ``name`` in the open folder ``parent``, as the
    refusal of a scenario folder names it; nothing is followed through a
    symbolic link. A folder that vanishes or cannot be looked into is taken
    as another write's, complete or not."""
    unknown = "another write owns it or left it incomplete"
    try:
        status = os.stat(name, dir_fd=parent, follow_symlinks=False)
    except OSError:
        return unknown
    if stat.S_ISLNK(status.st_mode):
        return "a symbolic link stands in its place"
    if not stat.S_ISDIR(status.st_mode):
        return "a file stands in its place"
    try:
        folder = os.open(name, _DIR_FLAGS, dir_fd=parent)
    except OSError:
        return unknown
    try:
        os.stat(RESULT_FILE, dir_fd=folder, follow_symlinks=False)
    except OSError:
        return unknown
    finally:
        os.close(folder)
    return f"its {RESULT_FILE} already exists"


def _claim_folder(parent: int, name: str, shown: Path) -> int:
    """Create the scenario folder ``name`` in the open execution folder
    ``parent`` and return it as a vetted descriptor (``_open_folder``);
    ``shown`` is its path for messages. The creation is exclusive, so of
    several writes of one scenario only the one that creates the folder
    writes into it. Whatever already stands there (a folder, empty or not, a
    file or a symbolic link, which is never followed) is refused and kept as
    found."""
    try:
        os.mkdir(name, _DIR_MODE, dir_fd=parent)
    except FileExistsError:
        raise HarnessError(
            f"evidence is immutable: {display_path(shown)} already exists ({_occupant(parent, name)}); "
            f"it is kept as found"
        ) from None
    except OSError as exc:
        raise HarnessError(
            f"cannot create the evidence folder {display_path(shown)}: {exc.strerror or exc}"
        ) from None
    # A failure here leaves the claimed folder empty, and a later write of
    # the scenario refuses it as another write's.
    _set_new_folder_mode(parent, name, shown)
    return _open_folder(parent, name, shown, created=True)


def _create(folder: int, name: str, shown: Path, data: bytes) -> None:
    """Write ``data`` to the new file ``name`` of the open folder ``folder``,
    mode ``0644`` whatever the umask; ``shown`` is its path for messages. The
    file is created with ``O_EXCL|O_NOFOLLOW``, so an existing file, or a
    link in its place, is never replaced or written through."""
    try:
        descriptor = os.open(name, _FILE_FLAGS, _FILE_MODE, dir_fd=folder)
    except FileExistsError:
        raise HarnessError(f"evidence is immutable: {display_path(shown)} already exists") from None
    except OSError as exc:
        raise HarnessError(f"cannot write evidence {display_path(shown)}: {exc.strerror or exc}") from None
    try:
        with os.fdopen(descriptor, "wb") as handle:
            os.fchmod(descriptor, _FILE_MODE)
            handle.write(data)
    except OSError as exc:
        raise HarnessError(f"cannot write evidence {display_path(shown)}: {exc.strerror or exc}") from None


class EvidenceWriter:
    """Writes the evidence of each scenario of one execution under
    ``<tests_root>/reports/unikix/<execution_id>/``; ``tests_root`` ``None``
    is ``harness.TESTS_ROOT``. The writer holds no state between writes."""

    def __init__(self, tests_root: str | os.PathLike[str] | None, execution_id: str) -> None:
        self.tests_root = _tests_root(tests_root)
        self.execution_id = _execution_id(execution_id)
        self.root = evidence_root(self.tests_root, self.execution_id)

    def write(self, scenario: Any, record: Mapping[str, Any], capture: Capture) -> str:
        """Write the evidence of ``scenario`` (a scenario object or its id)
        from its result ``record`` and ``capture``, and return its
        repository-relative path ``tests/reports/unikix/<execution_id>/<id>``.

        ``ValueError`` when the id is not an id of the closed scenario id
        manifest (``harness.scenarios.ALL_IDS``), the only ids evidence is
        written for. ``HarnessError`` when the scenario's directory already
        exists, empty or not (the write claims it by creating it exclusively,
        so of two writes of one scenario the second is refused and the files
        are the first's alone), when the record names another scenario,
        adapter or execution or a field outside ``RECORD_FIELDS``, when it
        breaks the result record contract (``harness.results.record_problems``
        of a stored record), or when the capture holds something that cannot
        be written. Nothing is written in those cases. ``HarnessError`` also
        when the tests root cannot be opened, or a folder on the way is not a
        real folder of the effective user (a symbolic link, a file or another
        user's folder), which is refused before anything is created in it."""
        scenario_id = _scenario_id(scenario if isinstance(scenario, str) else getattr(scenario, "id", None))
        if not isinstance(capture, Capture):
            raise TypeError(f"capture must be an evidence Capture, got {type(capture).__name__}")
        relative = evidence_path(self.execution_id, scenario_id)
        try:
            files = self._files(scenario_id, record, capture, relative)
        except HarnessError as exc:
            raise HarnessError(f"{scenario_id}: evidence not written: {exc}") from None

        # Each folder is reached through the descriptor of the folder above
        # it, from the tests root opened once, and each file is created in
        # its own folder's descriptor, so a link swapped in for any of them
        # is refused, never followed.
        with contextlib.ExitStack() as descriptors:
            folder = _kept(descriptors, _open_tests_root(self.tests_root))
            shown = self.tests_root
            for name in ("reports", "unikix", self.execution_id):
                shown = shown / name
                folder = _kept(descriptors, _own_folder(folder, name, shown))
            directory = self.root / scenario_id
            # Folder path in the scenario directory ("" for itself) -> its
            # descriptor, so each folder is created and opened once.
            folders = {"": _kept(descriptors, _claim_folder(folder, scenario_id, directory))}
            # Both folders exist in every evidence directory, empty or not.
            for folder_name in (DATASETS_DIR, SYSOUT_DIR):
                folders[folder_name] = _kept(
                    descriptors, _own_folder(folders[""], folder_name, directory / folder_name)
                )
            for relative_file, data in files:
                parts = relative_file.split("/")
                parent = ""
                for part in parts[:-1]:
                    path = f"{parent}/{part}" if parent else part
                    if path not in folders:
                        folders[path] = _kept(descriptors, _own_folder(folders[parent], part, directory / path))
                    parent = path
                _create(folders[parent], parts[-1], directory / relative_file, data)
        return relative

    def _files(
        self, scenario_id: str, record: Mapping[str, Any], capture: Capture, relative: str
    ) -> list[tuple[str, bytes]]:
        """Every file of the scenario's evidence as ``(path in the scenario
        directory, bytes)``, in writing order, ``result.yaml`` last."""
        datasets = self._datasets(capture)
        sysout = self._sysout(capture)
        screens_raw, steps, screens_text = self._screens(capture.screens_raw)
        observed_messages = [
            dict(_mapping(item, f"observed_messages[{index}]"))
            for index, item in enumerate(_sequence(capture.observed_messages, "observed_messages"))
        ]
        job_numbers = list(_sequence(capture.job_numbers, "job_numbers"))

        environment = dict(_mapping(capture.environment, "environment"))
        if "run_date" in environment:
            raise HarnessError("environment holds its own run_date; the capture's run_date is written there")
        run_date: dict[Any, Any] = {key: None for key in _RUN_DATE_KEYS}
        run_date.update(_mapping(capture.run_date, "run_date"))
        environment["run_date"] = run_date

        result = self._record(scenario_id, record, relative)
        result.update(
            {
                "capture_summary": {
                    "datasets": {name: logical for name, (logical, _) in datasets.items()},
                    "job_numbers": job_numbers,
                    "steps": steps,
                    "sysout": {path: name for path, (name, _) in sysout.items()},
                },
                "observed": self._observed(capture.result),
                "observed_messages": observed_messages,
                "screens_raw": screens_raw,
            }
        )

        files = [(f"{DATASETS_DIR}/{name}", data) for name, (_, data) in sorted(datasets.items())]
        files += [(f"{SYSOUT_DIR}/{path}", data) for path, (_, data) in sorted(sysout.items())]
        files.append((SCREENS_FILE, _bytes(screens_text, SCREENS_FILE, text_ok=True)))
        files.append((ENVIRONMENT_FILE, _yaml(environment, ENVIRONMENT_FILE)))
        files.append((RESULT_FILE, _yaml(result, RESULT_FILE)))
        return files

    def _record(self, scenario_id: str, record: Any, relative: str) -> dict[str, Any]:
        """The ``RECORD_FIELDS`` of ``result.yaml``: the record's values, a
        missing field as null. ``adapter``, ``execution_id`` and
        ``scenario_id`` are this evidence's, and ``evidence_path`` its
        directory; ``source_identity`` and ``execution_fingerprint`` are kept
        as the record holds them. ``result.yaml`` is a stored record, so the
        fields must pass ``harness.results.record_problems`` as one, the check
        the import applies to it: a status, reason or gap id that disagree, a
        passed record without its source identity, fingerprint or decoded
        outcome, or any other break of the contract raises ``HarnessError``
        listing every problem."""
        record = _mapping(record, "the result record")
        unknown = sorted(str(name) for name in record if name not in _results.RECORD_FIELDS)
        if unknown:
            raise HarnessError(
                f"the result record holds {', '.join(unknown)}, outside RECORD_FIELDS "
                f"({', '.join(_results.RECORD_FIELDS)})"
            )
        fields = {name: record.get(name) for name in _results.RECORD_FIELDS}
        # A record built for another run would be filed under the wrong
        # evidence, so a contradicting value is refused rather than replaced.
        for name, own in (
            ("scenario_id", scenario_id),
            ("adapter", _results.UNIKIX),
            ("execution_id", self.execution_id),
        ):
            if fields[name] is not None and fields[name] != own:
                raise HarnessError(f"the result record names {name} {fields[name]!r}, this evidence is {own!r}")
            fields[name] = own
        fields["evidence_path"] = relative
        # Immutable evidence the import would refuse could only be redone
        # under a new execution id, so it is never written.
        problems = _results.record_problems(fields)
        if problems:
            raise HarnessError("the result record breaks the result record contract: " + "; ".join(problems))
        return fields

    @staticmethod
    def _observed(result: Any) -> dict[str, Any] | None:
        """What ``result.yaml`` keeps of the run's ``Result``: everything but
        the datasets, written to ``datasets/``, and ``elapsed_s``."""
        if result is None:
            return None
        if not isinstance(result, Result):
            raise HarnessError(f"capture.result must be an adapters.base.Result, got {type(result).__name__}")
        return {
            "return_code": result.return_code,
            "messages": list(result.messages),
            "screens": [dict(screen) for screen in result.screens],
        }

    @staticmethod
    def _datasets(capture: Capture) -> dict[str, tuple[str, bytes]]:
        """``datasets/`` file name -> (dataset or observation name, bytes),
        from the capture and its ``Result``, which must agree on every name
        both hold."""
        merged: dict[str, bytes] = {}
        for name, data in _mapping(capture.datasets, "datasets").items():
            dataset_file_name(name)
            merged[name] = _bytes(data, f"dataset {name}")
        if isinstance(capture.result, Result):
            for name, data in capture.result.datasets.items():
                data = _bytes(data, f"Result dataset {name}")
                if name in merged and merged[name] != data:
                    raise HarnessError(
                        f"dataset {name}: the capture and the Result hold different bytes "
                        f"({len(merged[name])} and {len(data)} bytes)"
                    )
                merged[name] = data
        files: dict[str, tuple[str, bytes]] = {}
        for name in sorted(merged):
            file_name = dataset_file_name(name)
            if file_name in files:
                raise HarnessError(
                    f"datasets {files[file_name][0]!r} and {name!r} would both be written as "
                    f"{DATASETS_DIR}/{file_name}"
                )
            files[file_name] = (name, merged[name])
        return files

    @staticmethod
    def _sysout(capture: Capture) -> dict[str, tuple[str, bytes]]:
        """``sysout/``-relative path -> (name the adapter gave, bytes)."""
        files: dict[str, tuple[str, bytes]] = {}
        for name, data in _mapping(capture.sysout, "sysout").items():
            path = _sysout_path(name)
            if path in files:
                raise HarnessError(
                    f"sysout files {files[path][0]!r} and {name!r} would both be written as {SYSOUT_DIR}/{path}"
                )
            files[path] = (name, _bytes(data, f"sysout {name}", text_ok=True))
        for path in files:
            parts = path.split("/")
            for depth in range(1, len(parts)):
                folder = "/".join(parts[:depth])
                if folder in files:
                    raise HarnessError(
                        f"sysout {files[folder][0]!r} would be written as {SYSOUT_DIR}/{folder}, the folder "
                        f"of sysout {files[path][0]!r}"
                    )
        return files

    @staticmethod
    def _screens(entries: Any) -> tuple[list[dict[Any, Any]], list[dict[str, Any]], str]:
        """``screens_raw`` as written, its ``capture_summary.steps`` and the
        text of ``screens.txt``."""
        raw: list[dict[Any, Any]] = []
        steps: list[dict[str, Any]] = []
        lines: list[str] = []
        for index, entry in enumerate(_sequence(entries, "screens_raw")):
            entry = _mapping(entry, f"screens_raw[{index}]")
            rows = _sequence(entry.get("rows"), f"screens_raw[{index}].rows")
            if not all(isinstance(row, str) for row in rows):
                raise HarnessError(f"screens_raw[{index}].rows must hold str rows")
            raw.append({**entry, "rows": list(rows)})
            steps.append({key: entry[key] for key in _STEP_KEYS if key in entry})
            header = f"--- step {entry.get('step')} session {entry.get('session')} @map {entry.get('map')}"
            if "attempt" in entry:
                header += f" attempt {entry['attempt']}"
            lines.append(header + " ---")
            # Trimmed for reading only: screens_raw in result.yaml keeps every space.
            lines.extend(row.rstrip(" ") for row in rows)
            lines.append("")
        text = "\n".join(lines) if lines else "(no screen was captured)\n"
        return raw, steps, text


def _yaml(value: Any, what: str) -> bytes:
    """``value`` as canonical YAML (``harness.dump_yaml``), UTF-8."""
    try:
        return dump_yaml(value).encode("utf-8")
    except HarnessError as exc:
        raise HarnessError(f"{what}: {exc}") from None
