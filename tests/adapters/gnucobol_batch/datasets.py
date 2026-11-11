"""Datasets of the ``gnucobol-batch`` adapter: materialization, unload, the
per-scenario catalog and its GDG emulation.

Process helpers
    ``run_process`` runs one subprocess in its own process group under the
    scenario deadline (a ``time.monotonic()`` value, as the runner sets
    ``Adapter.deadline``) and kills the whole group when the deadline passes,
    raising ``ProcessTimeout``, an ``adapters.base.ScenarioTimeout`` that
    carries the output captured until then. A group stays registered until
    its leader is reaped and the group is confirmed empty, every step of that
    bounded. A group is signalled only while its leader is unreaped, as the
    leader's pid, alive or a zombie, then reserves the group id; the check
    and the signal hold one lock that every reap of a leader holds too, and
    once the leader is reaped the group is only probed, so no signal reaches
    a group that has since been given the id. ``kill_all`` ends the groups
    still registered: every one for ``Adapter.close``, and for
    ``Adapter.cleanup`` only those started in the scenario's working
    directory or below it (``within``), so one scenario's cleanup never ends
    another's programs. It raises ``DatasetError`` for any group of its scope
    it cannot confirm, which stays registered.
    ``runtime_env`` builds the fresh environment every program, loader and
    unloader runs with; nothing of the caller's environment is inherited,
    and ``COB_FILE_PATH`` is never set, so the absolute ``DD_<ddname>``
    paths are used as given.

``DatasetStore``
    The physical files of one scenario, all under ``<workdir>/data/``.
    Sequential files hold exact fixed-length records. Indexed files are
    GnuCOBOL 3.2 Berkeley DB files (GAP-STB-010), loaded and unloaded through
    the generated ``KSLOAD``/``KSUNLOAD`` of ``build.py``. Berkeley DB keeps
    the primary key in ``<file>`` and alternate key *i* (1-based, in SELECT
    order) in ``<file>.i``, so every file is built with a ``KeyProfile`` and
    the store records it (``data/profiles.yaml`` is its evidence). Observed
    with GnuCOBOL 3.2: ``OPEN INPUT`` fails with status 35 when a declared
    alternate's file is absent; a reader declaring ``WITH DUPLICATES`` on an
    alternate built without them gets status 30, while a file built with
    duplicates opens for a reader that declares the key unique (a READ NEXT
    through that key then never reaches the end of file); a reader declaring
    fewer alternates ignores the extra ``.N`` files, which then go stale if
    it writes. ``ensure_program_keys`` therefore re-keys each file to
    the program's own declaration before the program opens it, and ``OPEN
    OUTPUT`` recreates a loaded file, dropping its records, as VSAM never
    does for a loaded KSDS (GAP-STB-010).

``DsnMap``
    The logical-dataset to DSN map of ``jobs.yaml`` (each dd and concatenation
    member), and the alternate-index and path DSNs its ``DEFINE
    ALTERNATEINDEX`` and ``DEFINE PATH`` statements name. Dataset definitions
    (record length, organization, keys, alternate keys with their ``@AIX``
    path names) come from ``tests/fixtures/datasets.yaml``.

``Catalog`` and ``JobGenerations`` (GAP-STB-009)
    The catalog of one scenario: CLUSTER, AIX, PATH, GDG, GENERATION and
    NONVSAM entries. ``Catalog.seed`` starts a job scenario from the GDG
    bases of ``jobs.yaml`` ``catalog_seed`` its jobs reference without
    defining, plus the precondition datasets its jobs use, leaving out the
    targets of ``open``/``unavailable`` faults (``Catalog.left_out``); it
    accepts such a fault only when a use of the run reaches the absent
    dataset before the run creates it itself, and refuses every other one
    with ``MissingPrerequisite`` (GAP-MIS-013) before seeding anything. Relative
    generations are fixed per job, as z/OS fixes them at job start: ``(0)`` is
    the newest generation when the job started, and every ``(+1)`` of one job
    is one new generation. ``catalog_new`` catalogues a new generation and
    rolls off the oldest past ``LIMIT``: deleted under ``SCRATCH``, only
    uncatalogued under ``NOSCRATCH``; ``EMPTY`` rolls off every older one.
    Nothing else of GDG management (expiration, deferred roll-in, ``PURGE``,
    ``FIFO``) is emulated. ``catalog_observation`` and ``aix_observation`` give
    the ``@CATALOG`` and ``@AIX:<path>`` observations; an alternate index
    always reflects the base cluster's current records, as ``UPGRADE`` keeps
    it on VSAM.

Program runs
    ``materialize_program`` lays out the datasets of one unit (and the units it
    calls) as ``data/<LOGICAL>`` before the run, and ``read_back_program``
    reads its output and I-O datasets afterwards: sequential files as written,
    indexed files unloaded in primary-key order.

Every path this module creates lies inside the scenario working directory
(``DatasetError`` otherwise); nothing is written in the repository, and no
state is shared between scenarios except the read-only dataset definitions.
Importing this module performs no I/O and starts no process.
"""

from __future__ import annotations

import os
import re
import select
import selectors
import shutil
import signal
import subprocess
import threading
import time
from collections.abc import Callable, Iterable, Iterator, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from adapters.base import (
    CATALOG_TYPES,
    DEFAULT_DEADLINE_S,
    MissingPrerequisite,
    ScenarioTimeout,
    format_catalog,
    format_fixed_records,
)
from adapters.gnucobol_batch.build import (
    EXIT_NOFILE,
    EXIT_OK,
    KSDS_DD,
    SEQIN_DD,
    SEQOUT_DD,
    BuildCache,
    KeyProfile,
    profile_for,
)
from harness import FIXTURES_FILE, REPO_ROOT, HarnessError, dump_yaml, load_yaml, write_text

__all__ = [
    "AIX",
    "CLUSTER",
    "GDG",
    "GENERATION",
    "NONVSAM",
    "PATH",
    "AlternateKeyDef",
    "Catalog",
    "DatasetDef",
    "DatasetError",
    "DatasetStore",
    "DsnMap",
    "Entry",
    "GenerationRef",
    "JobGenerations",
    "ProcessResult",
    "ProcessTimeout",
    "dataset_specs",
    "generation_name",
    "idcams_statements",
    "kill_all",
    "materialize_program",
    "program_dds",
    "read_back_program",
    "run_process",
    "runtime_env",
    "scenario_jobs",
    "split_generation",
    "unavailable_targets",
]

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

CLUSTER, AIX, PATH, GDG, GENERATION, NONVSAM = CATALOG_TYPES

DATA_DIR = "data"
EVIDENCE_DIR = "evidence"
INPUTS_DIR = "inputs"
_WORK_SUBDIR = ".work"  # loader input and unloader output, removed after each use
_PROFILES_EVIDENCE = "profiles.yaml"
_REKEY_LOG = "rekey.log"
_CATALOG_EVIDENCE = "catalog.yaml"

# DSN and logical names as files: qualifiers of [A-Z0-9#@$-] joined by dots. A
# final qualifier of digits only would collide with Berkeley DB's <file>.<i>
# alternate-key files, so it is refused (no z/OS qualifier starts with a digit).
_NAME_RE = re.compile(r"^[A-Z0-9#@$][A-Z0-9#@$-]*(?:\.[A-Z0-9#@$][A-Z0-9#@$-]*)*$")
_NAME_MAX = 200
_COMPONENT_RE = re.compile(r"^(?P<base>.+)\.(?P<index>[1-9][0-9]*)$")
_GENERATION_RE = re.compile(r"^(?P<base>.+)\.G(?P<number>[0-9]{4})V00$")
_IGNORED_FILES = re.compile(r"^(?:__db\..*|.*\.lock)$")  # Berkeley DB environment files

_DUPKEY_RE = re.compile(r"^DUPKEY ([0-9]{9})\s*$")
_LOADED_RE = re.compile(r"^LOADED ([0-9]{9})\s*$")
_UNLOADED_RE = re.compile(r"^UNLOADED ([0-9]{9})\s*$")

_OPEN_MODES = ("input", "output", "io", "extend")
_WRITE_MODES = frozenset({"output", "io", "extend"})
_REAP_TIMEOUT_S = 10
_REAP_POLL_S = 0.05  # interval of the probes that confirm a killed group is empty
_EXIT_POLL_MIN_S = 0.0005  # first interval of the wait for a leader's exit, as in Popen.wait
_EXIT_POLL_MAX_S = 0.05  # its longest interval; each one doubles the previous
_READ_SIZE = 32768  # bytes read from a subprocess pipe at a time
_MAX_GENERATION = 9999


class DatasetError(HarnessError):
    """A dataset could not be materialized, loaded, unloaded, re-keyed or
    catalogued as the emulation requires, a configuration file is
    inconsistent, or a path would leave the scenario working directory. The
    runner reports it as ``failing`` with reason ``error``: it is a harness
    problem, never program behaviour and never a registered gap."""


# --------------------------------------------------------------------------
# Process helpers
# --------------------------------------------------------------------------


@dataclass
class ProcessResult:
    """One finished subprocess. ``returncode`` is ``None`` when a signal ended
    it, and ``signal`` then names the signal."""

    returncode: int | None
    signal: int | None
    stdout: bytes
    stderr: bytes
    elapsed_s: float


class ProcessTimeout(ScenarioTimeout):
    """The scenario deadline passed while ``run_process`` waited, and the
    process group was killed. As a ``ScenarioTimeout`` it makes the scenario
    ``failing`` with reason ``timeout``; its attributes are the raw evidence.

    ``argv`` is the command. ``stdout`` and ``stderr`` hold every byte read
    before the kill and from the pipes after it; ``output_complete`` is false
    when the pipes could not be read to their end after the kill, because one
    was still open ``_REAP_TIMEOUT_S`` later (held by a process that left the
    group or is stuck in the kernel) or reading failed; the detail says which.
    ``elapsed_s`` is the time from the start to the deadline. ``returncode``
    and ``signal`` describe the reaped leader as in ``ProcessResult``; both
    are ``None`` while it is unreaped. ``teardown_problems`` is empty when the
    leader was reaped and the group confirmed empty; otherwise it says what
    failed, and ``retained`` is true: the group stays registered for
    ``kill_all``. Every argument has a default only so that the exception
    copies and pickles as any other does."""

    def __init__(
        self,
        detail: str = "",
        *,
        argv: Sequence[str] = (),
        stdout: bytes = b"",
        stderr: bytes = b"",
        output_complete: bool = True,
        elapsed_s: float = 0.0,
        returncode: int | None = None,
        signal: int | None = None,
        teardown_problems: Sequence[str] = (),
        retained: bool = False,
    ) -> None:
        super().__init__(detail)
        self.argv = tuple(argv)
        self.stdout = stdout
        self.stderr = stderr
        self.output_complete = output_complete
        self.elapsed_s = elapsed_s
        self.returncode = returncode
        self.signal = signal
        self.teardown_problems = tuple(teardown_problems)
        self.retained = retained


# Process groups started by run_process, by group id (the leader's pid). An
# entry leaves only when its leader is reaped and its group confirmed empty,
# so a group whose teardown failed stays for kill_all to retry. A group is
# signalled only while its leader is unreaped, because the leader's pid,
# alive or a zombie, then reserves the id; once the leader is reaped, the id
# is free for any new process as soon as the last member ends, so the group
# is only probed. A new leader given a registered id replaces that entry,
# which proves the old group ended: the kernel reuses a pid only once no
# process has it as its pid, group id or session id. _LIVE_CWD holds, by the
# same id, the real path of the working directory run_process started the
# group in; it is set, replaced and dropped with the _LIVE entry under
# _LIVE_LOCK, so kill_all(within=...) can end only the groups started in one
# scenario's working directory.
_LIVE: dict[int, subprocess.Popen[bytes]] = {}
_LIVE_CWD: dict[int, Path] = {}
_LIVE_LOCK = threading.Lock()
# Held by every reap of a run_process leader (_reap) and by every signal to
# its group (_kill_unreaped), so the check that the leader is unreaped and
# the signal are one step against any reap; never held across a wait, and
# never together with _LIVE_LOCK.
_REAP_LOCK = threading.Lock()


def _release(pgid: int, process: subprocess.Popen[bytes]) -> None:
    """Drop the confirmed group of ``process`` from ``_LIVE``, with its
    working directory, unless a newer leader holds its id."""
    with _LIVE_LOCK:
        if _LIVE.get(pgid) is process:
            del _LIVE[pgid]
            del _LIVE_CWD[pgid]


def _owns(pgid: int, process: subprocess.Popen[bytes]) -> bool:
    """Whether ``process`` still holds the ``_LIVE`` entry of ``pgid``. An
    entry it no longer holds was released once its group was confirmed, or
    replaced by a newer leader given the id; either way its group ended."""
    with _LIVE_LOCK:
        return _LIVE.get(pgid) is process


def _program(process: subprocess.Popen[bytes]) -> str:
    return Path(os.fspath(process.args[0])).name


def _status(code: int | None) -> tuple[int | None, int | None]:
    """``(returncode, signal)`` of a ``Popen.returncode``, as in
    ``ProcessResult``; ``(None, None)`` for a process not yet reaped."""
    if code is None:
        return None, None
    return (code, None) if code >= 0 else (None, -code)


def _kill_group(pgid: int) -> str:
    """Send SIGKILL to process group ``pgid``. Only ``_kill_unreaped`` calls
    it, holding ``_REAP_LOCK`` with the group's leader unreaped, so the id is
    still this group's. Returns why it failed, or "" when it was sent or the
    group has no member left (ESRCH). A failure, EPERM included, leaves
    members unsignalled and is never taken for success; a success may still
    have skipped members the kernel would not let this process signal, so
    only ``_group_left`` shows the group ended."""
    try:
        os.killpg(pgid, signal.SIGKILL)
    except ProcessLookupError:
        return ""  # ESRCH: no member is left to signal
    except OSError as exc:
        return f"SIGKILL to process group {pgid} failed: {exc}"
    return ""


def _group_left(pgid: int) -> str:
    """Why process group ``pgid`` may still have members, or "" once it has
    none. ``killpg(pgid, 0)`` only checks and sends nothing: ESRCH proves
    the group empty, success or EPERM shows a member alive, and any other
    error leaves it unknown. A member that has exited counts until its
    parent reaps it."""
    try:
        os.killpg(pgid, 0)
    except ProcessLookupError:
        return ""
    except PermissionError:
        return f"process group {pgid} has members this process may not signal"
    except OSError as exc:
        return f"process group {pgid} cannot be probed: {exc}"
    return f"process group {pgid} still has members"


def _unreaped(process: subprocess.Popen[bytes]) -> bool:
    """Whether the leader ``process`` is unreaped, alive or a zombie, so that
    its pid still reserves its group id; the caller holds ``_REAP_LOCK``.
    The kernel confirms it: ``waitid`` with ``WNOWAIT`` reaps nothing and
    fails with ECHILD once the pid is no longer a child of this process."""
    if process.returncode is not None:
        return False
    try:
        os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
    except ChildProcessError:
        return False
    return True


def _kill_unreaped(process: subprocess.Popen[bytes], pgid: int) -> str:
    """Send SIGKILL to process group ``pgid`` if its leader ``process`` is
    unreaped, the check and the signal under ``_REAP_LOCK``, the only way
    the group is signalled. A reaped leader no longer reserves the id, so
    its group is left unsignalled. Returns why the signal failed, or ""
    (``_kill_group``)."""
    with _REAP_LOCK:
        if not _unreaped(process):
            return ""
        return _kill_group(pgid)


def _reap(process: subprocess.Popen[bytes]) -> bool:
    """Reap the leader ``process`` if it has exited, under ``_REAP_LOCK``,
    the only way a ``run_process`` leader is reaped; ``Popen.poll`` never
    blocks. Returns whether it is reaped."""
    with _REAP_LOCK:
        return process.poll() is not None


def _exited(process: subprocess.Popen[bytes], until: float) -> bool:
    """Wait, without reaping, until the leader ``process`` has exited or
    ``time.monotonic()`` reaches ``until``; returns whether it exited. It is
    polled with ``waitid`` and ``WNOWAIT``, which leaves an exited leader a
    zombie that still reserves its group id, at intervals doubling from
    ``_EXIT_POLL_MIN_S`` to ``_EXIT_POLL_MAX_S`` as in ``Popen.wait``.
    ECHILD means it was already reaped, by another thread through ``_reap``,
    and its ``returncode`` stands."""
    delay = _EXIT_POLL_MIN_S
    while True:
        if process.returncode is not None:
            return True
        try:
            state = os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
            if state is not None:
                return True
        except ChildProcessError:
            return True
        remaining = until - time.monotonic()
        if remaining <= 0:
            return False
        time.sleep(min(delay, remaining))
        delay = min(delay * 2, _EXIT_POLL_MAX_S)


def _terminate(process: subprocess.Popen[bytes], pgid: int) -> tuple[str, ...]:
    """End process group ``pgid``, which ``process`` leads, within
    ``_REAP_TIMEOUT_S``: kill it while its leader is unreaped, wait for the
    leader's exit, reap it and confirm the group is empty. Returns the
    teardown problems, none once the leader is reaped and the group
    confirmed empty; a failure is reported, never raised.

    The SIGKILL (``_kill_unreaped``) is sent only while the leader is
    unreaped, alive or a zombie, and reaches every member then in the group;
    a leader already reaped, by this call or an earlier one, is never
    signalled. After the reap the group is only probed (``_group_left``)
    until it is empty: once its last member ends the id may pass to an
    unrelated group, so a member that outlives the bound is reported, never
    signalled. A group whose ``_LIVE`` entry ``process`` no longer holds
    (``_owns``) has ended and counts as confirmed."""
    until = time.monotonic() + _REAP_TIMEOUT_S
    problems = [_kill_unreaped(process, pgid)]
    if not (_exited(process, until) and _reap(process)):
        problems.append(f"leader {process.pid} not reaped within {_REAP_TIMEOUT_S} s")
        return tuple(problem for problem in problems if problem)
    while _owns(pgid, process) and (left := _group_left(pgid)):
        if time.monotonic() >= until:
            problems.append(f"{left} after {_REAP_TIMEOUT_S} s")
            return tuple(problem for problem in problems if problem)
        time.sleep(_REAP_POLL_S)
    return ()


def _close_pipes(process: subprocess.Popen[bytes]) -> None:
    # Nothing is ever written through the stdin buffer, so closing never writes.
    for pipe in (process.stdin, process.stdout, process.stderr):
        if pipe is not None:
            pipe.close()


def _pump(
    process: subprocess.Popen[bytes],
    output: Mapping[str, list[bytes]],
    until: float,
    stdin_bytes: bytes = b"",
) -> bool:
    """Write ``stdin_bytes`` to the input pipe of ``process`` and read its
    stdout and stderr pipes into ``output`` (chunks under ``"stdout"`` and
    ``"stderr"``), closing each pipe when it is done, until all are done or
    ``time.monotonic()`` reaches ``until``. Returns whether all are done; the
    others stay open. An input the child stops reading by closing it is done,
    as in ``Popen.communicate``. The chunks belong to the caller, so nothing
    read is lost whatever ends the exchange."""
    view = memoryview(stdin_bytes)
    offset = 0
    with selectors.DefaultSelector() as selector:
        if process.stdin is not None and not process.stdin.closed:
            if view:
                selector.register(process.stdin, selectors.EVENT_WRITE)
            else:
                process.stdin.close()
        for name, pipe in (("stdout", process.stdout), ("stderr", process.stderr)):
            if pipe is not None and not pipe.closed:
                selector.register(pipe, selectors.EVENT_READ, name)
        while selector.get_map():
            remaining = until - time.monotonic()
            if remaining <= 0:
                return False
            for key, _events in selector.select(remaining):
                if key.data is None:  # the input pipe
                    try:
                        offset += os.write(key.fd, view[offset : offset + select.PIPE_BUF])
                    except BrokenPipeError:
                        offset = len(view)  # the child closed its input
                    if offset >= len(view):
                        selector.unregister(key.fileobj)
                        key.fileobj.close()
                    continue
                data = os.read(key.fd, _READ_SIZE)
                if data:
                    output[key.data].append(data)
                else:
                    selector.unregister(key.fileobj)
                    key.fileobj.close()
    return True


def _drain(process: subprocess.Popen[bytes], output: Mapping[str, list[bytes]]) -> str:
    """Read what is left in the stdout and stderr pipes of a killed group's
    leader into ``output`` until both end or ``_REAP_TIMEOUT_S`` passes.
    Returns "" when both ended, else why the capture is incomplete; the
    caller closes the pipes still open."""
    try:
        ended = _pump(process, output, time.monotonic() + _REAP_TIMEOUT_S)
    except OSError as exc:
        return f"reading its output after the kill failed: {exc}"
    if ended:
        return ""
    return f"its output was still open {_REAP_TIMEOUT_S} s after the kill and was closed"


def run_process(
    argv: Sequence[str | os.PathLike[str]],
    *,
    env: Mapping[str, str],
    cwd: str | os.PathLike[str],
    deadline: float | None = None,
    stdin_bytes: bytes | None = None,
) -> ProcessResult:
    """Run ``argv`` in a new session (its own process group) and wait for it.

    ``env`` is the complete environment (``runtime_env`` builds one); when it
    names no ``HOME``, ``HOME`` is ``cwd``. ``stdin_bytes``, when given, is
    written to the program's input, which is then closed; otherwise the input
    is ``/dev/null``. ``deadline`` is a ``time.monotonic()`` value; without
    one the wait is bounded by ``DEFAULT_DEADLINE_S``. The run lasts until
    the leader exits and both output pipes end. A plain ``ScenarioTimeout``
    is raised without starting anything when the deadline has already passed.

    The group is registered in ``_LIVE`` from the start until its leader is
    reaped and the group is confirmed empty, so ``kill_all`` can end it from
    another thread or retry a teardown that failed. It is registered with the
    real path of ``cwd``, so ``kill_all(within=...)`` ends it only for a
    directory that is ``cwd`` or holds it. The leader's exit is awaited
    without reaping it, so its pid keeps reserving the group id. A teardown
    (``_terminate``) kills the group with SIGKILL while the leader
    is unreaped, reaps the leader and then only probes the group until it is
    empty, within ``_REAP_TIMEOUT_S``, and never raises:

    - When the deadline passes, the pipes are read for ``_REAP_TIMEOUT_S``
      more after the teardown, and ``ProcessTimeout`` is raised with all the
      output captured and the teardown's outcome.
    - Any other exception (``KeyboardInterrupt`` included) propagates
      unchanged after the teardown, with a note when the teardown is
      incomplete.
    - Members a finished leader leaves in its group are killed while the
      exited leader is still an unreaped zombie. If the teardown fails, the
      result is still returned, as an outcome produced before the deadline
      stands, and ``kill_all`` reports the group."""
    args = [os.fspath(arg) for arg in argv]
    if not args:
        raise DatasetError("run_process needs a command")
    environment = {str(key): str(value) for key, value in env.items()}
    environment.setdefault("HOME", os.fspath(cwd))
    where = Path(os.path.realpath(os.fspath(cwd)))
    name = Path(args[0]).name
    started = time.monotonic()
    limit = deadline if deadline is not None else started + DEFAULT_DEADLINE_S
    if limit - started <= 0:
        raise ScenarioTimeout(f"scenario deadline passed before {name} started")
    try:
        process = subprocess.Popen(
            args,
            env=environment,
            cwd=os.fspath(cwd),
            stdin=subprocess.PIPE if stdin_bytes is not None else subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,
        )
    except OSError as exc:
        raise DatasetError(f"cannot start {args[0]}: {exc}") from exc
    pgid = process.pid
    with _LIVE_LOCK:
        _LIVE[pgid] = process
        _LIVE_CWD[pgid] = where
    output: dict[str, list[bytes]] = {"stdout": [], "stderr": []}
    try:
        finished = _pump(process, output, limit, stdin_bytes or b"") and _exited(process, limit)
    except BaseException as exc:
        try:
            problems = _terminate(process, pgid)
        finally:
            _close_pipes(process)
        if problems:
            exc.add_note(
                f"{name}: teardown incomplete, process group {pgid} stays registered for "
                f"kill_all: {'; '.join(problems)}"
            )
        else:
            _release(pgid, process)
        raise
    elapsed = time.monotonic() - started
    if not finished:
        try:
            problems = _terminate(process, pgid)
            unread = _drain(process, output)
        finally:
            _close_pipes(process)
        detail = f"{name} killed with its process group at the scenario deadline"
        if unread:
            detail += f"; {unread}"
        if problems:
            detail += (
                f"; teardown incomplete, the group stays registered for kill_all: "
                f"{'; '.join(problems)}"
            )
        else:
            _release(pgid, process)
        returncode, killed_by = _status(process.returncode)
        raise ProcessTimeout(
            detail,
            argv=args,
            stdout=b"".join(output["stdout"]),
            stderr=b"".join(output["stderr"]),
            output_complete=not unread,
            elapsed_s=elapsed,
            returncode=returncode,
            signal=killed_by,
            teardown_problems=problems,
            retained=bool(problems),
        )
    # The leader has exited unreaped (unless a concurrent kill_all reaped it),
    # so its zombie reserves the group id: kill the members it left in the
    # group, then reap it and probe the group. An unconfirmed teardown keeps
    # the group registered for kill_all to report; this outcome, produced
    # before the deadline, stands.
    if not _terminate(process, pgid):
        _release(pgid, process)
    returncode, killed_by = _status(process.returncode)
    return ProcessResult(
        returncode=returncode,
        signal=killed_by,
        stdout=b"".join(output["stdout"]),
        stderr=b"".join(output["stderr"]),
        elapsed_s=elapsed,
    )


def kill_all(within: str | os.PathLike[str] | None = None) -> None:
    """End the process groups ``run_process`` registered: one still running
    in another thread, or one whose teardown could not be confirmed.
    Without ``within`` that is every registered group (``Adapter.close``).
    With it, only the groups started in the directory ``within`` names or
    below it, both compared as real paths, so a cleanup given one scenario's
    working directory never ends another scenario's group or one started in
    a directory above it (``Adapter.cleanup``).

    Each is torn down as ``_terminate`` does, within ``_REAP_TIMEOUT_S``, and
    released: killed only while its leader is unreaped, its leader reaped,
    and the group confirmed empty; the group of a leader already reaped is
    only probed, never signalled. An entry of the snapshot that its process
    no longer holds, because its run released it or a newer leader was
    given the id, has ended and is skipped. A group that cannot be confirmed
    stays registered for a later call, and once every group in scope has
    been tried, ``DatasetError`` names each with its id, program and
    problems. With no group in scope it does nothing."""
    scope = None if within is None else Path(os.path.realpath(os.fspath(within)))
    with _LIVE_LOCK:
        live = [(pgid, process, _LIVE_CWD[pgid]) for pgid, process in _LIVE.items()]
    unconfirmed: list[str] = []
    for pgid, process, where in live:
        if scope is not None and where != scope and scope not in where.parents:
            continue
        if not _owns(pgid, process):
            continue
        problems = _terminate(process, pgid)
        if problems:
            unconfirmed.append(f"group {pgid} ({_program(process)}): {'; '.join(problems)}")
        else:
            _release(pgid, process)
    if unconfirmed:
        shown = "" if scope is None else f" started in {scope}"
        raise DatasetError(
            f"kill_all could not confirm the end of these process groups{shown}, which stay "
            "registered: " + " | ".join(unconfirmed)
        )


def runtime_env(
    cache: BuildCache,
    extra: Mapping[str, Any] | None = None,
    *,
    home: str | os.PathLike[str] | None = None,
) -> dict[str, str]:
    """The environment of a batch program, loader or unloader run.

    A fresh mapping: ``PATH`` is the cobc bin directory then ``/usr/bin`` and
    ``/bin``; ``LD_LIBRARY_PATH`` the GnuCOBOL library directories;
    ``LANG``/``LC_ALL`` ``C``; ``TZ`` ``UTC``; ``COB_LIBRARY_PATH`` the module
    directory (CBACT04C, CSUTLDTC and the CEE3ABD and CEEDAYS stubs); ``HOME``
    is ``home`` when given, else ``run_process`` sets the working directory.
    ``COB_FILE_PATH`` is never set, so relative names would not be prefixed;
    callers pass absolute ``DD_<ddname>`` paths. ``extra`` comes last."""
    path_dirs: list[str] = []
    for directory in (os.fspath(cache.cobc_bin_dir), "/usr/bin", "/bin"):
        if directory not in path_dirs:
            path_dirs.append(directory)
    env = {
        "PATH": os.pathsep.join(path_dirs),
        "LANG": "C",
        "LC_ALL": "C",
        "TZ": "UTC",
        "COB_LIBRARY_PATH": os.fspath(cache.module_dir),
    }
    if cache.lib_dirs:
        env["LD_LIBRARY_PATH"] = os.pathsep.join(os.fspath(entry) for entry in cache.lib_dirs)
    if home is not None:
        env["HOME"] = os.fspath(home)
    for key, value in (extra or {}).items():
        if not isinstance(key, str) or not key or "=" in key:
            raise DatasetError(f"environment variable name {key!r} is not valid")
        env[key] = os.fspath(value) if isinstance(value, os.PathLike) else str(value)
    return env


# --------------------------------------------------------------------------
# Dataset definitions (tests/fixtures/datasets.yaml)
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class AlternateKeyDef:
    """An alternate key of a ``ksds`` dataset, 0-based; ``path`` is the
    ``@AIX:<path>`` name of its path (the region file of the path)."""

    name: str | None
    offset: int
    length: int
    duplicates: bool
    path: str | None


@dataclass(frozen=True)
class DatasetDef:
    """The part of one ``datasets.yaml`` entry this module needs."""

    name: str
    record_length: int
    organization: str  # ksds | sequential
    key: tuple[int, int] | None
    alternate_keys: tuple[AlternateKeyDef, ...]

    @property
    def keyed(self) -> bool:
        return self.organization == "ksds"

    @property
    def profile(self) -> KeyProfile | None:
        """The dataset's own key profile, primary key and every alternate key
        in definition order; ``None`` for a sequential dataset."""
        if not self.keyed or self.key is None:
            return None
        return KeyProfile(
            self.record_length,
            self.key,
            tuple((alt.offset, alt.length, alt.duplicates) for alt in self.alternate_keys),
        )


def _non_negative(value: Any, what: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise DatasetError(f"{what} must be a non-negative integer, got {value!r}")
    return value


def _parse_definition(entry: Any, shown: str) -> DatasetDef:
    if not isinstance(entry, Mapping) or not isinstance(entry.get("name"), str):
        raise DatasetError(f"{shown} holds a dataset without a name: {entry!r}")
    name = entry["name"]
    record_length = _non_negative(entry.get("record_length"), f"{shown} {name} record_length")
    if record_length == 0:
        raise DatasetError(f"{shown} {name} record_length must be positive")
    organization = entry.get("organization")
    if organization not in ("ksds", "sequential"):
        raise DatasetError(f"{shown} {name} organization must be ksds or sequential")
    key = None
    alternates: list[AlternateKeyDef] = []
    if organization == "ksds":
        raw_key = entry.get("key")
        if not isinstance(raw_key, Mapping):
            raise DatasetError(f"{shown} {name} is ksds but has no key")
        key = (
            _non_negative(raw_key.get("offset"), f"{shown} {name} key offset"),
            _non_negative(raw_key.get("length"), f"{shown} {name} key length"),
        )
        for raw in entry.get("alternate_keys") or []:
            if not isinstance(raw, Mapping) or not isinstance(raw.get("duplicates"), bool):
                raise DatasetError(f"{shown} {name} has a malformed alternate key: {raw!r}")
            alternates.append(
                AlternateKeyDef(
                    name=raw.get("name"),
                    offset=_non_negative(raw.get("offset"), f"{shown} {name} alternate offset"),
                    length=_non_negative(raw.get("length"), f"{shown} {name} alternate length"),
                    duplicates=raw["duplicates"],
                    path=raw.get("path"),
                )
            )
    definition = DatasetDef(name, record_length, organization, key, tuple(alternates))
    try:
        definition.profile  # noqa: B018 - validates the keys against the record length
    except HarnessError as exc:
        raise DatasetError(f"{shown} {name}: {exc}") from None
    return definition


_SPECS_LOCK = threading.Lock()
_SPECS_CACHE: dict[str, tuple[tuple[int, int], dict[str, DatasetDef]]] = {}


def dataset_specs(path: str | os.PathLike[str] | None = None) -> dict[str, DatasetDef]:
    """Every logical dataset of ``tests/fixtures/datasets.yaml`` (or
    ``path``), by name. The parsed file is cached until it changes; the
    returned mapping is a copy.

    The adapter reads the file itself with ``harness.load_yaml`` rather than
    through ``harness.fixtures``, so that the adapter layer depends only on
    ``harness``, ``adapters.base`` and ``build``, and only on the fields it
    uses: record length, organization and keys."""
    file_path = Path(path) if path is not None else FIXTURES_FILE
    try:
        status = file_path.stat()
    except FileNotFoundError:
        raise DatasetError(
            f"{file_path} is missing: the logical datasets are defined there"
        ) from None
    stamp = (status.st_mtime_ns, status.st_size)
    with _SPECS_LOCK:
        hit = _SPECS_CACHE.get(str(file_path))
    if hit is not None and hit[0] == stamp:
        return dict(hit[1])
    document = load_yaml(file_path)
    entries = document.get("datasets") if isinstance(document, Mapping) else None
    if not isinstance(entries, list):
        raise DatasetError(f"{file_path.name} has no datasets list")
    parsed: dict[str, DatasetDef] = {}
    for entry in entries:
        definition = _parse_definition(entry, file_path.name)
        if definition.name in parsed:
            raise DatasetError(f"{file_path.name} defines {definition.name} twice")
        parsed[definition.name] = definition
    with _SPECS_LOCK:
        _SPECS_CACHE[str(file_path)] = (stamp, parsed)
    return dict(parsed)


# --------------------------------------------------------------------------
# Names and faults
# --------------------------------------------------------------------------


def _checked_name(name: Any) -> str:
    if (
        not isinstance(name, str)
        or len(name) > _NAME_MAX
        or not _NAME_RE.fullmatch(name)
        or name.rsplit(".", 1)[-1].isdigit()
    ):
        raise DatasetError(
            f"{name!r} cannot name a dataset file: it must be qualifiers of A-Z, 0-9, #, @, $ "
            f"and - joined by dots, the last not all digits"
        )
    return name


def generation_name(base: str, number: int) -> str:
    """The absolute name of generation ``number`` of the GDG ``base``:
    ``<base>.G<nnnn>V00``."""
    if (
        isinstance(number, bool)
        or not isinstance(number, int)
        or not 1 <= number <= _MAX_GENERATION
    ):
        raise DatasetError(
            f"generation number of {base} must be 1-{_MAX_GENERATION}, got {number!r}"
        )
    return f"{_checked_name(base)}.G{number:04d}V00"


def split_generation(name: str) -> tuple[str, int] | None:
    """``(base, number)`` of an absolute generation name, else ``None``."""
    match = _GENERATION_RE.fullmatch(name)
    if match is None or int(match.group("number")) == 0:
        return None
    return match.group("base"), int(match.group("number"))


def unavailable_targets(faults: Iterable[Mapping[str, Any]] | None) -> set[str]:
    """The logical datasets left out by ``open``/``unavailable`` faults.

    That is the one fault ``gnucobol-batch`` produces: the dataset is neither
    materialized nor catalogued, so the program's OPEN fails with status 35.
    The adapter refuses every other fault with ``MissingPrerequisite(...,
    "GAP-MIS-013")`` before preparing anything; one that still reaches this
    module is refused the same way, never silently ignored. A target the run
    never needs cannot be left out, and the adapter and ``Catalog.seed``
    refuse it the same way: in a program run, one named by no INPUT, I-O or
    EXTEND dd; in a job run, one that no use reaches before the run creates
    it itself, because the jobs never use it, only create it or read only
    what they created (``Catalog.seed``)."""
    targets: set[str] = set()
    for fault in faults or ():
        if not isinstance(fault, Mapping):
            raise DatasetError(f"a fault must be a mapping, got {fault!r}")
        target = fault.get("target")
        if fault.get("operation") == "open" and fault.get("condition") == "unavailable":
            if not isinstance(target, str) or not target:
                raise DatasetError(f"an open/unavailable fault names no target: {dict(fault)!r}")
            targets.add(target)
            continue
        raise MissingPrerequisite(
            f"{fault.get('operation')} fault ({fault.get('condition')}) on {target}: no "
            f"gnucobol-batch mechanism injects a failure after a successful open",
            "GAP-MIS-013",
        )
    return targets


# --------------------------------------------------------------------------
# DatasetStore: the physical files of one scenario
# --------------------------------------------------------------------------


def _profile_text(profile: KeyProfile | None) -> str:
    return profile.name if profile is not None else "-"


class DatasetStore:
    """The data files of one scenario, under ``<workdir>/data/``.

    ``cache`` is the ``build.BuildCache`` whose loaders and unloaders build and
    read indexed files; ``deadline_fn`` returns the scenario deadline (a
    ``time.monotonic()`` value, or ``None``) for every subprocess, so a load
    or unload that overruns it raises ``ScenarioTimeout``. ``workdir`` may
    not be the repository, lie inside it or contain it (``DatasetError``,
    with or without a cache), and every path the store writes, its metadata
    included, must lie inside ``workdir``."""

    def __init__(
        self,
        workdir: str | os.PathLike[str],
        cache: BuildCache | None,
        deadline_fn: Callable[[], float | None] | None = None,
    ) -> None:
        self.workdir = Path(workdir).resolve()
        # ``inside`` trusts the working directory as its boundary, and work on
        # sequential files needs no build cache, so ``ensure_built``'s own
        # repository guard may never have run: the store checks for itself.
        repository = REPO_ROOT.resolve()
        if (
            self.workdir == repository
            or repository in self.workdir.parents
            or self.workdir in repository.parents
        ):
            raise DatasetError(
                f"the scenario working directory {self.workdir} is, lies inside or contains "
                f"the repository {repository}; nothing may be written in the repository"
            )
        self.cache = cache
        self.data_dir = self.workdir / DATA_DIR
        self.evidence_dir = self.workdir / EVIDENCE_DIR
        self._deadline_fn = deadline_fn
        self._profiles: dict[Path, KeyProfile] = {}
        self._serial = 0

    def __repr__(self) -> str:
        return f"DatasetStore({self.workdir})"

    # -- paths -------------------------------------------------------------

    def inside(self, path: str | os.PathLike[str]) -> Path:
        """``path`` made absolute (relative paths are taken from the working
        directory), refused with ``DatasetError`` unless it lies inside it."""
        candidate = Path(path)
        if not candidate.is_absolute():
            candidate = self.workdir / candidate
        resolved = Path(os.path.realpath(candidate))
        if resolved != self.workdir and self.workdir not in resolved.parents:
            raise DatasetError(f"{path} lies outside the scenario working directory {self.workdir}")
        return resolved

    def path_for(self, name: str) -> Path:
        """The file of a dataset named by its DSN (with a generation suffix
        ``.G<nnnn>V00`` for a generation) or its logical name. Nothing is
        created."""
        return self.inside(self.data_dir / _checked_name(name))

    def relative(self, path: str | os.PathLike[str]) -> str:
        """``path`` relative to the working directory, as evidence shows it."""
        return self.inside(path).relative_to(self.workdir).as_posix()

    def _deadline(self) -> float | None:
        return self._deadline_fn() if self._deadline_fn is not None else None

    def _work_file(self, purpose: str) -> Path:
        self._serial += 1
        directory = self.inside(self.data_dir / _WORK_SUBDIR)
        directory.mkdir(parents=True, exist_ok=True)
        return directory / f"{self._serial:06d}.{purpose}.seq"

    def _require_cache(self) -> BuildCache:
        if self.cache is None:
            raise DatasetError(
                "indexed files need the gnucobol-batch build cache, and none was given"
            )
        return self.cache

    # -- components and profiles -------------------------------------------

    def components(self, path: str | os.PathLike[str]) -> list[Path]:
        """The files of a dataset: ``path`` itself when present, then its
        alternate-key files ``<path>.<i>`` in index order. Berkeley DB
        environment files (``__db.*``, ``*.lock``) are never components."""
        target = self.inside(path)
        found: list[tuple[int, Path]] = []
        if target.is_file():
            found.append((0, target))
        if target.parent.is_dir():
            for entry in target.parent.iterdir():
                if _IGNORED_FILES.fullmatch(entry.name):
                    continue
                match = _COMPONENT_RE.fullmatch(entry.name)
                if match and match.group("base") == target.name and entry.is_file():
                    found.append((int(match.group("index")), entry))
        return [entry for _, entry in sorted(found)]

    def exists(self, path: str | os.PathLike[str]) -> bool:
        return self.inside(path).is_file()

    def remove(self, path: str | os.PathLike[str]) -> None:
        """Delete a dataset: ``path`` and every ``<path>.<i>``, and forget its
        key profile. An absent dataset is not an error."""
        target = self.inside(path)
        for component in self.components(target):
            component.unlink()
        if self._profiles.pop(target, None) is not None:
            self._write_profiles()

    def profile(self, path: str | os.PathLike[str]) -> KeyProfile | None:
        """The key profile the indexed file at ``path`` was built with, or
        ``None`` when the store did not build it."""
        return self._profiles.get(self.inside(path))

    def record_profile(self, path: str | os.PathLike[str], profile: KeyProfile) -> None:
        """Record that the indexed file at ``path`` now has ``profile``: for a
        file a program created (``after_program_output``) or a copy made
        outside the store."""
        if not isinstance(profile, KeyProfile):
            raise DatasetError(f"a KeyProfile is required, got {profile!r}")
        self._profiles[self.inside(path)] = profile
        self._write_profiles()

    def _write_profiles(self) -> None:
        """Rewrite ``data/profiles.yaml``. The file and its directory are
        resolved and confined before anything is created, because
        ``write_text`` follows a symlink and writes beside its target: a link
        leading outside the working directory, dangling or not, is refused
        with ``DatasetError`` and its target is neither created nor changed."""
        records = {self.relative(path): profile.name for path, profile in self._profiles.items()}
        destination = self.inside(self.data_dir / _PROFILES_EVIDENCE)
        self.inside(destination.parent).mkdir(parents=True, exist_ok=True)
        write_text(destination, dump_yaml(records))

    def log_rekey(self, line: str) -> None:
        """Append one line to ``evidence/rekey.log``. The log and its
        directory are confined, as for ``data/profiles.yaml``, before
        anything is created."""
        destination = self.inside(self.evidence_dir / _REKEY_LOG)
        self.inside(destination.parent).mkdir(parents=True, exist_ok=True)
        with open(destination, "a", encoding="utf-8") as log:
            log.write(line + "\n")

    # -- sequential files --------------------------------------------------

    def write_sequential(
        self, path: str | os.PathLike[str], data: bytes, record_length: int
    ) -> Path:
        """Write ``data`` as a fixed-length sequential file: the exact bytes,
        which must be whole records of ``record_length``."""
        if isinstance(data, bytearray):
            data = bytes(data)
        if not isinstance(data, bytes):
            raise DatasetError(f"sequential data must be bytes, got {type(data).__name__}")
        length = _non_negative(record_length, "record_length")
        if length == 0 or len(data) % length:
            raise DatasetError(
                f"{len(data)} bytes are not whole {record_length}-byte records for "
                f"{Path(path).name}"
            )
        target = self.inside(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        self.remove(target)
        target.write_bytes(data)
        return target

    def read_sequential(self, path: str | os.PathLike[str]) -> bytes:
        """The bytes of a sequential file, exactly as written."""
        target = self.inside(path)
        try:
            return target.read_bytes()
        except FileNotFoundError:
            raise DatasetError(f"{self.relative(target)} does not exist") from None

    def copy_sequential(
        self, source: str | os.PathLike[str], path: str | os.PathLike[str], record_length: int
    ) -> Path:
        """Copy a sequential file (an ``inputs/`` file, say) to ``path``;
        ``source`` is read, never changed."""
        return self.write_sequential(path, self.read_sequential(source), record_length)

    # -- indexed files -----------------------------------------------------

    def _run_tool(
        self, executable: Path, args: Sequence[str], dds: Mapping[str, Path]
    ) -> ProcessResult:
        cache = self._require_cache()
        env = runtime_env(cache, {f"DD_{name}": os.fspath(path) for name, path in dds.items()})
        self.data_dir.mkdir(parents=True, exist_ok=True)
        return run_process(
            [executable, *args], env=env, cwd=self.data_dir, deadline=self._deadline()
        )

    @staticmethod
    def _output_text(outcome: ProcessResult) -> str:
        text = outcome.stdout.decode("latin-1") + outcome.stderr.decode("latin-1")
        status = (
            f"exit {outcome.returncode}"
            if outcome.returncode is not None
            else f"signal {outcome.signal}"
        )
        return f"{status}: {' | '.join(line for line in text.splitlines() if line.strip())}"

    def _checked_input(
        self, path: Path, profile: KeyProfile, data: bytes | None
    ) -> tuple[BuildCache, bytes | None]:
        """The build cache and ``data`` as ``bytes`` for a ``KSLOAD`` of
        ``path`` with ``profile``, once the cache is present, ``profile`` is a
        ``KeyProfile`` and ``data`` (``None`` for ``CREATE``) is whole records
        of its length; ``DatasetError`` otherwise. No file is touched, so a
        caller checks this before it replaces anything."""
        cache = self._require_cache()
        if not isinstance(profile, KeyProfile):
            raise DatasetError(f"a KeyProfile is required, got {profile!r}")
        if data is not None:
            if isinstance(data, bytearray):
                data = bytes(data)
            if not isinstance(data, bytes) or len(data) % profile.record_length:
                raise DatasetError(
                    f"{len(data) if isinstance(data, bytes) else type(data).__name__} is not whole "
                    f"{profile.record_length}-byte records for {self.relative(path)}"
                )
        return cache, data

    def _loader(
        self, mode: str, path: Path, profile: KeyProfile, data: bytes | None
    ) -> tuple[list[int], int | None]:
        """Run ``KSLOAD <mode>``; returns the duplicate record numbers (1-based
        SEQIN positions) and the loaded count (``None`` for ``CREATE``)."""
        cache, data = self._checked_input(path, profile, data)
        dds = {KSDS_DD: path}
        seqin: Path | None = None
        if data is not None:
            seqin = self._work_file("load")
            seqin.write_bytes(data)
            dds[SEQIN_DD] = seqin
        try:
            outcome = self._run_tool(cache.loader(profile), [mode], dds)
        finally:
            if seqin is not None:
                seqin.unlink(missing_ok=True)
        lines = [line.strip() for line in outcome.stdout.decode("latin-1").splitlines()]
        duplicates = [int(m.group(1)) for m in map(_DUPKEY_RE.match, lines) if m]
        loaded = next((int(m.group(1)) for m in map(_LOADED_RE.match, lines) if m), None)
        if data is None:
            complete = "CREATED" in lines
        else:
            complete = loaded is not None and (
                loaded + len(duplicates) == len(data) // profile.record_length
            )
        if outcome.returncode != EXIT_OK or not complete:
            raise DatasetError(
                f"KSLOAD {mode} of {self.relative(path)} with profile {profile.name} failed "
                f"({self._output_text(outcome)})"
            )
        return duplicates, loaded

    def _duplicates_outcome(
        self, path: Path, profile: KeyProfile, duplicates: list[int], on_duplicate: str
    ) -> list[int]:
        if duplicates and on_duplicate == "error":
            raise DatasetError(
                f"loading {self.relative(path)} with profile {profile.name} met duplicate keys "
                f"at input records {', '.join(str(number) for number in duplicates)}"
            )
        return duplicates

    @staticmethod
    def _check_on_duplicate(on_duplicate: str) -> None:
        if on_duplicate not in ("error", "report"):
            raise DatasetError(f"on_duplicate must be 'error' or 'report', got {on_duplicate!r}")

    def load_indexed(
        self,
        path: str | os.PathLike[str],
        data: bytes,
        profile: KeyProfile,
        *,
        on_duplicate: str = "error",
    ) -> list[int]:
        """Build the indexed file at ``path`` from scratch with ``profile`` and
        the fixed-length records ``data``, in order (``KSLOAD LOAD``).

        A record whose primary or alternate key is already present is not
        loaded. With ``on_duplicate="error"`` that raises ``DatasetError``
        carrying the record numbers; with ``"report"`` the load goes on, as
        IDCAMS REPRO does, and the 1-based input record numbers are
        returned.

        ``on_duplicate``, the build cache, ``profile`` and the record length
        of ``data`` are checked before any file is touched. The files of a
        dataset already at ``path`` are then moved aside, not deleted: a load
        that fails or is interrupted (``DatasetError``, ``ScenarioTimeout``,
        ``KeyboardInterrupt``) removes what it built and puts them back, with
        their recorded profile unchanged, before its exception propagates
        (``_recover``). Recording the new profile is part of the load, so a
        load whose profile cannot be recorded is undone in the same way, and
        the files set aside are deleted only once it is recorded
        (``_commit``). A completed load replaces all of them, so no
        ``<path>.<i>`` of the earlier dataset survives it."""
        self._check_on_duplicate(on_duplicate)
        target = self.inside(path)
        self._checked_input(target, profile, data)
        target.parent.mkdir(parents=True, exist_ok=True)
        previous = self._profiles.get(target)
        backup, saved = self._set_aside(target)
        try:
            duplicates, _ = self._loader("LOAD", target, profile, data)
            self._duplicates_outcome(target, profile, duplicates, on_duplicate)
        except BaseException as error:
            self._recover(target, saved, backup, error)
            raise
        self._commit(target, saved, backup, profile, previous)
        return duplicates

    def create_indexed(self, path: str | os.PathLike[str], profile: KeyProfile) -> Path:
        """Create an empty indexed file with ``profile`` (``KSLOAD CREATE``),
        replacing any file at ``path``. As in ``load_indexed``, the arguments
        are checked first and a creation that fails or is interrupted, the
        recording of its profile included (``_commit``), puts the previous
        files back with their recorded profile."""
        target = self.inside(path)
        self._checked_input(target, profile, None)
        target.parent.mkdir(parents=True, exist_ok=True)
        previous = self._profiles.get(target)
        backup, saved = self._set_aside(target)
        try:
            self._loader("CREATE", target, profile, None)
        except BaseException as error:
            self._recover(target, saved, backup, error)
            raise
        self._commit(target, saved, backup, profile, previous)
        return target

    def append_indexed(
        self,
        path: str | os.PathLike[str],
        data: bytes,
        profile: KeyProfile | None = None,
        *,
        on_duplicate: str = "error",
    ) -> list[int]:
        """Add records to the indexed file at ``path`` (``KSLOAD APPEND``),
        creating it with ``profile`` when absent.

        A present file is written with the profile it was built with, so all
        its alternate keys stay current; ``profile``, when given, must have the
        same record length and primary key. Duplicates behave as in
        ``load_indexed``."""
        self._check_on_duplicate(on_duplicate)
        target = self.inside(path)
        recorded = self.profile(target)
        if self.exists(target):
            if recorded is None:
                raise DatasetError(f"{self.relative(target)} exists but was not built by the store")
            if profile is not None and (
                profile.record_length != recorded.record_length
                or profile.primary != recorded.primary
            ):
                raise DatasetError(
                    f"{self.relative(target)} is {recorded.name}; it cannot take records of "
                    f"{profile.name}"
                )
            use = recorded
        else:
            if profile is None:
                raise DatasetError(f"{self.relative(target)} is absent and no profile was given")
            target.parent.mkdir(parents=True, exist_ok=True)
            self.remove(target)  # stale alternate-key files of an earlier dataset
            use = profile
        duplicates, _ = self._loader("APPEND", target, use, data)
        self.record_profile(target, use)
        return self._duplicates_outcome(target, use, duplicates, on_duplicate)

    def _alternate_index(self, profile: KeyProfile, alternate: int | Sequence[int]) -> int:
        if isinstance(alternate, int) and not isinstance(alternate, bool):
            if not 1 <= alternate <= len(profile.alternates):
                raise DatasetError(f"profile {profile.name} has no alternate key {alternate}")
            return alternate
        wanted = tuple(alternate)[:2]
        for index, (offset, length, _) in enumerate(profile.alternates, start=1):
            if (offset, length) == wanted:
                return index
        raise DatasetError(f"profile {profile.name} has no alternate key at {wanted!r}")

    def unload_indexed(
        self,
        path: str | os.PathLike[str],
        profile: KeyProfile | None = None,
        alternate: int | Sequence[int] | None = None,
    ) -> bytes:
        """Every record of the indexed file at ``path``, concatenated.

        Without ``alternate`` the records come in primary-key order
        (``KSUNLOAD PRIMARY``). ``alternate`` is a 1-based index into the
        profile's alternates or an ``(offset, length)`` pair: the records are
        read through that key (``KSUNLOAD ALT <n>``), the read order is checked
        to be non-decreasing in it, and records with equal alternate keys are
        put in primary-key order, the order BLDINDEX gives a non-unique
        alternate index of a KSDS. Berkeley DB itself keeps duplicates in
        insertion order (GAP-STB-010). ``profile`` defaults to the recorded
        one."""
        target = self.inside(path)
        recorded = self.profile(target)
        use = profile if profile is not None else recorded
        if use is None:
            raise DatasetError(
                f"{self.relative(target)} has no recorded key profile to unload it with"
            )
        index = self._alternate_index(use, alternate) if alternate is not None else None
        if index is not None and recorded is not None:
            # Read through a key declared without duplicates over an index built
            # with them, GnuCOBOL 3.2's READ NEXT never reaches the end of file.
            offset, length, duplicates = use.alternates[index - 1]
            built = [alt[2] for alt in recorded.alternates if alt[:2] == (offset, length)]
            if built and built[0] != duplicates:
                raise DatasetError(
                    f"{self.relative(target)} carries alternate key {offset}/{length} "
                    f"{'with' if built[0] else 'without'} duplicates; it cannot be read through "
                    f"profile {use.name}"
                )
        seqout = self._work_file("unload")
        args = ["PRIMARY"] if index is None else ["ALT", str(index)]
        try:
            outcome = self._run_tool(
                self._require_cache().unloader(use), args, {KSDS_DD: target, SEQOUT_DD: seqout}
            )
            data = seqout.read_bytes() if seqout.is_file() else b""
        finally:
            seqout.unlink(missing_ok=True)
        lines = outcome.stdout.decode("latin-1").splitlines()
        if outcome.returncode == EXIT_NOFILE:
            raise DatasetError(f"{self.relative(target)} does not exist")
        count = next((int(m.group(1)) for m in map(_UNLOADED_RE.match, lines) if m), None)
        length = use.record_length
        if outcome.returncode != EXIT_OK or count is None or count * length != len(data):
            raise DatasetError(
                f"KSUNLOAD {' '.join(args)} of {self.relative(target)} with profile {use.name} "
                f"failed ({self._output_text(outcome)})"
            )
        if index is None:
            return data
        offset, key_length, _ = use.alternates[index - 1]
        records = [data[start : start + length] for start in range(0, len(data), length)]
        keys = [record[offset : offset + key_length] for record in records]
        if any(later < earlier for earlier, later in zip(keys, keys[1:], strict=False)):
            raise DatasetError(
                f"KSUNLOAD ALT {index} of {self.relative(target)} returned records out of "
                f"alternate-key order"
            )
        primary_offset, primary_length = use.primary
        records.sort(
            key=lambda record: (
                record[offset : offset + key_length],
                record[primary_offset : primary_offset + primary_length],
            )
        )
        return b"".join(records)

    def rekey(self, path: str | os.PathLike[str], profile: KeyProfile) -> bool:
        """Rebuild the indexed file at ``path`` with ``profile``: unload it in
        primary-key order with its recorded profile and load the records with
        the new one. Returns whether the file now has ``profile``.

        When the new profile cannot hold every record (duplicate alternate
        keys under a key declared without duplicates), the previous files are
        put back unchanged, the failure goes to ``evidence/rekey.log`` and
        ``False`` is returned: the program that needed the profile then opens
        the file as it is, and that outcome is pinned. A different record
        length or primary key cannot be re-keyed (``DatasetError``).

        The previous files are moved aside, not deleted, until the rebuild
        has succeeded and its profile is recorded; a rebuild that fails or is
        interrupted, the recording of its profile included (``_commit``),
        puts them back with their recorded profile before its exception
        propagates. Their backup is deleted only after a rebuild whose
        profile is recorded or a confirmed restoration. When they cannot be
        put back, the recovery copies stay under ``data/.work`` and
        ``evidence/rekey.log`` names them; a propagating exception carries
        that line as a note, and on the duplicate-key path, where none
        propagates, the line is raised as ``DatasetError`` (``_recover``)."""
        target = self.inside(path)
        recorded = self.profile(target)
        if recorded is None:
            raise DatasetError(
                f"{self.relative(target)} has no recorded key profile to re-key from"
            )
        if recorded == profile:
            return True
        if recorded.record_length != profile.record_length or recorded.primary != profile.primary:
            raise DatasetError(
                f"{self.relative(target)} is {recorded.name}; re-keying keeps the record length "
                f"and primary key, so it cannot become {profile.name}"
            )
        kept = len(profile.alternates)
        if kept < len(recorded.alternates) and recorded.alternates[:kept] == profile.alternates:
            # Only trailing alternates go: drop their files, nothing to reload.
            self._drop_alternates_beyond(target, kept)
            self.record_profile(target, profile)
            self.log_rekey(f"{self.relative(target)} {recorded.name} -> {profile.name}: truncated")
            return True
        data = self.unload_indexed(target, recorded)
        backup, saved = self._set_aside(target)
        try:
            duplicates, _ = self._loader("LOAD", target, profile, data)
        except BaseException as error:
            self._recover(target, saved, backup, error)
            raise
        if duplicates:
            self._recover(target, saved, backup)
            self.log_rekey(
                f"{self.relative(target)} {recorded.name} -> {profile.name}: failed, duplicate "
                f"keys at records {', '.join(str(number) for number in duplicates)}; kept "
                f"{recorded.name}"
            )
            return False
        self._commit(target, saved, backup, profile, recorded)
        self.log_rekey(f"{self.relative(target)} {recorded.name} -> {profile.name}: rebuilt")
        return True

    def _set_aside(self, target: Path) -> tuple[Path, list[tuple[Path, Path]]]:
        """Move the files of the dataset at ``target`` into a new backup
        directory under ``data/.work`` (renames, on the same filesystem), so
        ``target`` can be rebuilt in place. Returns the directory and the
        ``(component, copy)`` pairs ``_recover`` restores from. When a rename
        fails, the files already moved go back before the error propagates."""
        backup = self._work_file("backup").with_suffix(".dir")
        backup.mkdir()
        saved: list[tuple[Path, Path]] = []
        try:
            saved = [(component, backup / component.name) for component in self.components(target)]
            for component, copy in saved:
                component.rename(copy)
        except BaseException as error:
            self._recover(target, saved, backup, error, built=False)
            raise
        return backup, saved

    def _restore(
        self, target: Path, saved: list[tuple[Path, Path]], *, built: bool = True
    ) -> None:
        """Rename the copies ``_set_aside`` made back to their components.
        Once a rebuild has begun (``built``), every file at ``target`` is its
        work and is removed first; before that, a component whose copy was
        never made is still the original and stays."""
        if built:
            for component in self.components(target):
                component.unlink()
        for component, copy in saved:
            if copy.exists():
                copy.rename(component)

    def _recover(
        self,
        target: Path,
        saved: list[tuple[Path, Path]],
        backup: Path,
        error: BaseException | None = None,
        *,
        built: bool = True,
    ) -> None:
        """Restore the files ``_set_aside`` moved from ``target`` to
        ``backup`` (``_restore``), then delete ``backup``. ``error`` is the
        exception propagating, if any, which the caller re-raises.

        A restoration that fails keeps ``backup`` with the recovery copies it
        still holds, and ``evidence/rekey.log`` names them with the failure.
        ``error`` then goes on unchanged, carrying that line as a note;
        without ``error`` the failure is raised as ``DatasetError`` naming the
        copies. An interrupt during the restoration (``KeyboardInterrupt``,
        ``SystemExit``) propagates itself, with that line as a note."""
        try:
            self._restore(target, saved, built=built)
        except BaseException as failure:
            line = (
                f"{self._shown(target)}: restoring the files set aside failed ({failure!r}); "
                f"recovery copies kept: {self._retained(saved)}"
            )
            if not isinstance(failure, Exception):
                failure.add_note(line)
                self._log_safely(line, failure)
                raise
            if error is None:
                raised = DatasetError(line)
                self._log_safely(line, raised)
                raise raised from failure
            error.add_note(line)
            self._log_safely(line, error)
            return
        self._discard(target, backup, error)

    def _commit(
        self,
        target: Path,
        saved: list[tuple[Path, Path]],
        backup: Path,
        profile: KeyProfile,
        previous: KeyProfile | None,
    ) -> None:
        """Record ``profile`` for the rebuilt ``target``, then delete
        ``backup``, whose copies the rebuild has replaced.

        The commit is the last step of the replacement, not a step after it.
        When ``profile`` cannot be recorded (``data/profiles.yaml`` refused
        or unwritable, an interrupt), ``previous``, the profile recorded
        before the rebuild, is put back in memory as it was, or the entry is
        dropped when there was none, and ``data/profiles.yaml`` is rewritten
        from it, since an interrupt can land after the new profile reached
        the file; a rewrite refused or unwritable (``HarnessError``,
        ``OSError``) goes on the exception as a note and never replaces it,
        while an interrupt during the rewrite propagates itself after the
        files are restored. ``_recover`` then removes the rebuilt files and puts
        the ones in ``saved`` back, keeping and reporting ``backup`` when it
        cannot, and the exception propagates unchanged. ``backup`` is
        deleted on success only once ``profile`` is recorded."""
        try:
            self.record_profile(target, profile)
        except BaseException as error:
            if previous is None:
                self._profiles.pop(target, None)
            else:
                self._profiles[target] = previous
            try:
                try:
                    self._write_profiles()
                except (HarnessError, OSError) as failure:
                    error.add_note(
                        f"{DATA_DIR}/{_PROFILES_EVIDENCE} could not be rewritten for the restored "
                        f"profiles ({failure!r})"
                    )
            finally:
                self._recover(target, saved, backup, error)
            raise
        self._discard(target, backup)

    def _discard(self, target: Path, backup: Path, error: BaseException | None = None) -> None:
        """Delete ``backup`` once the files of ``target`` no longer need its
        copies. A failure leaves it in place and goes to ``evidence/rekey.log``
        as a cleanup failure; it never replaces ``error``, the exception
        propagating, if any."""
        try:
            shutil.rmtree(backup)
        except OSError as failure:
            self._log_safely(
                f"{self._shown(target)}: cleanup of {self._shown(backup)} failed ({failure!r}); "
                f"it is left in place",
                error,
            )

    def _log_safely(self, line: str, error: BaseException | None) -> None:
        """``log_rekey(line)``. When the log cannot take the line, the line
        and that failure go on ``error``, the exception propagating, as a
        note, so ``error`` is never replaced; without ``error`` they are
        raised as ``DatasetError``."""
        try:
            self.log_rekey(line)
        except Exception as failure:
            message = f"evidence/{_REKEY_LOG} could not record {line!r} ({failure!r})"
            if error is None:
                raise DatasetError(message) from failure
            error.add_note(message)

    def _shown(self, path: Path) -> str:
        """``path`` as evidence names it (``relative``), or as given when
        that fails: a line written on a failure path never raises itself."""
        try:
            return self.relative(path)
        except (DatasetError, OSError, ValueError):
            return os.fspath(path)

    def _retained(self, saved: list[tuple[Path, Path]]) -> str:
        """The recovery copies of ``saved`` still in their backup directory,
        as evidence names them, or ``none``."""
        kept: list[str] = []
        for _, copy in saved:
            try:
                present = copy.exists()
            except OSError:
                present = True  # unknown: named rather than lost track of
            if present:
                kept.append(self._shown(copy))
        return ", ".join(kept) if kept else "none"

    def _drop_alternates_beyond(self, target: Path, count: int) -> None:
        for component in self.components(target):
            match = _COMPONENT_RE.fullmatch(component.name)
            if match and int(match.group("index")) > count:
                component.unlink()

    def ensure_program_keys(
        self,
        path: str | os.PathLike[str],
        declared_profile: KeyProfile,
        open_mode: str = "input",
    ) -> bool:
        """Give the indexed file at ``path`` the keys a program's SELECT
        declares, before the program opens it. Returns whether the file has
        them afterwards; an absent file is left to the program's OPEN.

        The primary key and the program's alternates, in its order and with
        its duplicates flags, become alternates ``1..k`` of the file, because
        GnuCOBOL finds alternate *i* in ``<file>.i``. For ``open_mode``
        ``input`` the file's other alternates follow, so its paths stay
        readable; for ``io``, ``output`` and ``extend`` the file keeps exactly
        the declared keys, since a program writes only the indexes it declares
        and any other would go stale (alternate indexes are rebuilt from the
        base records when observed, ``aix_observation``). This is how CBACT04C
        opens CARDXREF with alternate 25/11 without duplicates, where the
        dataset's alternate index is NONUNIQUEKEY. Each re-key is logged to
        ``evidence/rekey.log``."""
        if open_mode not in _OPEN_MODES:
            raise DatasetError(
                f"open_mode must be one of {', '.join(_OPEN_MODES)}, got {open_mode!r}"
            )
        target = self.inside(path)
        if not self.exists(target):
            return False
        recorded = self.profile(target)
        if recorded is None:
            raise DatasetError(f"{self.relative(target)} exists but was not built by the store")
        if (
            recorded.record_length != declared_profile.record_length
            or recorded.primary != declared_profile.primary
        ):
            raise DatasetError(
                f"{self.relative(target)} is {recorded.name}, but the program declares "
                f"{declared_profile.name}: record length and primary key must agree"
            )
        declared = declared_profile.alternates
        extras: list[tuple[int, int, bool]] = []
        if open_mode == "input":
            claimed = {(offset, length) for offset, length, _ in declared}
            extras = [alt for alt in recorded.alternates if alt[:2] not in claimed]
        wanted = declared_profile.with_alternates([*declared, *extras])
        if wanted == recorded:
            return True
        return self.rekey(target, wanted)

    def after_program_output(
        self, path: str | os.PathLike[str], declared_profile: KeyProfile
    ) -> None:
        """After a program opened the indexed file at ``path`` OUTPUT, which
        recreates it with the program's declared keys: record that profile and
        remove the ``<path>.<i>`` files beyond its alternates."""
        target = self.inside(path)
        if not self.exists(target):
            return
        self._drop_alternates_beyond(target, len(declared_profile.alternates))
        self.record_profile(target, declared_profile)


# --------------------------------------------------------------------------
# DsnMap: logical datasets and DSNs of jobs.yaml
# --------------------------------------------------------------------------

_STATEMENT_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
_DEFINE_AIX_RE = re.compile(r"^\s*DEFINE\s+(?:ALTERNATEINDEX|AIX)\b")
_DEFINE_PATH_RE = re.compile(r"^\s*DEFINE\s+PATH\b")
_DEFINE_GDG_RE = re.compile(r"^\s*DEFINE\s+(?:GENERATIONDATAGROUP|GDG)\b")
_PARAM_RE = {
    keyword: re.compile(rf"\b{keyword}\s*\(\s*([^\s(),]+)\s*\)")
    for keyword in ("NAME", "RELATE", "PATHENTRY")
}
# IDCAMS KEYS is length first, then offset, written "(a b)" or "(a,b)".
_KEYS_RE = re.compile(r"\bKEYS\s*\(\s*(\d+)\s*[\s,]\s*(\d+)\s*\)")
_NONUNIQUE_RE = re.compile(r"\bNONUNIQUEKEY\b")
# A data set name of two or more qualifiers in an IDCAMS command.
_DSN_TOKEN_RE = re.compile(r"[A-Z0-9#@$][A-Z0-9#@$-]*(?:\.[A-Z0-9#@$][A-Z0-9#@$-]*)+")
_DEFINE_RE = re.compile(r"^\s*DEFINE\b")
# DISP statuses that allocate a left-out (absent) dataset, statuses that open an
# existing one, and the normal dispositions after which a dataset a NEW or MOD dd
# allocated no longer depends on whether it existed before (Catalog.seed).
_CREATING_STATUSES = frozenset({"NEW", "MOD"})
_READING_STATUSES = frozenset({"SHR", "OLD"})
_REPLACING_DISPOSITIONS = frozenset({"CATLG", "DELETE", "UNCATLG"})


def _jobs_mapping(jobs_cfg: Mapping[str, Any]) -> Mapping[str, Any]:
    """The ``jobs`` mapping of a loaded ``jobs.yaml``, or ``jobs_cfg`` itself
    when it already is that mapping."""
    if not isinstance(jobs_cfg, Mapping):
        raise DatasetError(f"jobs.yaml must hold a mapping, got {type(jobs_cfg).__name__}")
    jobs = jobs_cfg.get("jobs") if "jobs" in jobs_cfg else jobs_cfg
    if not isinstance(jobs, Mapping):
        raise DatasetError("jobs.yaml has no jobs mapping")
    return jobs


def _job_steps(jobs: Mapping[str, Any], job_name: str) -> list[Mapping[str, Any]]:
    job = jobs.get(job_name)
    steps = job.get("steps") if isinstance(job, Mapping) else None
    if not isinstance(steps, list) or not all(isinstance(step, Mapping) for step in steps):
        raise DatasetError(f"jobs.yaml job {job_name} has no list of steps")
    return steps


def _dd_entries(step: Mapping[str, Any]) -> Iterator[tuple[str, Mapping[str, Any]]]:
    """``(ddname, entry)`` for every DD of a step and every member of a
    concatenation, in transcription order."""
    dds = step.get("dd") or {}
    if not isinstance(dds, Mapping):
        raise DatasetError(f"jobs.yaml step {step.get('step')} has a dd that is not a mapping")
    for ddname, dd in dds.items():
        if not isinstance(dd, Mapping):
            raise DatasetError(f"jobs.yaml step {step.get('step')} dd {ddname} is not a mapping")
        yield str(ddname), dd
        for member in dd.get("concat") or []:
            if not isinstance(member, Mapping):
                raise DatasetError(
                    f"jobs.yaml step {step.get('step')} dd {ddname} has a concatenation member "
                    f"that is not a mapping"
                )
            yield str(ddname), member


def idcams_statements(lines: Iterable[Any]) -> list[str]:
    """IDCAMS commands of a SYSIN: ``/* ... */`` comments removed and lines
    ending in ``-`` or ``+`` joined with the next."""
    text_lines: list[str] = []
    for raw in lines:
        if not isinstance(raw, str):
            raise DatasetError(f"an IDCAMS SYSIN line must be text, got {raw!r}")
        text_lines.append(raw)
    text = _STATEMENT_COMMENT_RE.sub(" ", "\n".join(text_lines))
    statements: list[str] = []
    pending: list[str] = []
    for line in text.split("\n"):
        stripped = line.rstrip()
        if stripped.endswith(("-", "+")):
            pending.append(stripped[:-1])
            continue
        pending.append(stripped)
        statement = " ".join(pending).strip()
        if statement:
            statements.append(statement)
        pending = []
    if pending and " ".join(pending).strip():
        statements.append(" ".join(pending).strip())
    return statements


def _step_statements(step: Mapping[str, Any]) -> list[str]:
    if step.get("kind") != "idcams":
        return []
    params = step.get("params") or {}
    return idcams_statements(params.get("sysin") or []) if isinstance(params, Mapping) else []


def _param(statement: str, keyword: str) -> str | None:
    match = _PARAM_RE[keyword].search(statement)
    return match.group(1) if match else None


@dataclass(frozen=True)
class _AixDefinition:
    name: str
    relate: str
    offset: int
    length: int
    unique: bool


class DsnMap:
    """Logical datasets, DSNs and alternate-index paths of ``jobs.yaml``.

    Built from every dd and concatenation member (``dsn``, ``gdg``,
    ``logical``, ``aix_path``) and every ``DEFINE ALTERNATEINDEX`` and
    ``DEFINE PATH`` statement of the step tables. A logical dataset with two
    DSNs, or with and without a generation, a DSN of two logical datasets, or
    a path DSN of two ``@AIX`` names raises ``DatasetError`` when the map is
    built. ``specs`` are the ``datasets.yaml`` definitions (read when not
    given)."""

    def __init__(
        self, jobs_cfg: Mapping[str, Any], specs: Mapping[str, DatasetDef] | None = None
    ) -> None:
        jobs = _jobs_mapping(jobs_cfg)
        self.specs: dict[str, DatasetDef] = dict(specs) if specs is not None else dataset_specs()
        self._dsn_of: dict[str, tuple[str, bool]] = {}
        self._logical_of: dict[str, str] = {}
        self._path_names: dict[str, str] = {}  # path DSN -> @AIX name (aix_path dd entries)
        self._aixes: dict[str, _AixDefinition] = {}
        self._paths: dict[str, str] = {}  # path DSN -> AIX DSN
        for job_name in sorted(jobs):
            for step in _job_steps(jobs, job_name):
                where = f"jobs.yaml {job_name} {step.get('step')}"
                for ddname, entry in _dd_entries(step):
                    self._add_dd(entry, f"{where} {ddname}")
                for statement in _step_statements(step):
                    self._add_statement(statement, where)

    def _add_dd(self, entry: Mapping[str, Any], where: str) -> None:
        dsn, logical, aix_path = entry.get("dsn"), entry.get("logical"), entry.get("aix_path")
        if logical is not None:
            if not isinstance(dsn, str) or not isinstance(logical, str):
                raise DatasetError(f"{where}: a logical dataset needs a DSN")
            if logical not in self.specs:
                raise DatasetError(f"{where}: {logical} is not defined in datasets.yaml")
            value = (_checked_name(dsn), entry.get("gdg") is not None)
            if self._dsn_of.setdefault(logical, value) != value:
                raise DatasetError(
                    f"{where}: {logical} is {dsn} (generation {value[1]}) here but "
                    f"{self._dsn_of[logical][0]} (generation {self._dsn_of[logical][1]}) elsewhere"
                )
            if self._logical_of.setdefault(dsn, logical) != logical:
                raise DatasetError(
                    f"{where}: {dsn} holds {logical} here but {self._logical_of[dsn]} elsewhere"
                )
        if aix_path is not None:
            if not isinstance(dsn, str) or not isinstance(aix_path, str):
                raise DatasetError(f"{where}: an aix_path entry needs a DSN")
            if self._path_names.setdefault(dsn, aix_path) != aix_path:
                raise DatasetError(
                    f"{where}: {dsn} is path {aix_path} here but {self._path_names[dsn]} elsewhere"
                )

    def _add_statement(self, statement: str, where: str) -> None:
        if _DEFINE_AIX_RE.match(statement):
            name, relate = _param(statement, "NAME"), _param(statement, "RELATE")
            keys = _KEYS_RE.search(statement)
            if name is None or relate is None or keys is None:
                raise DatasetError(f"{where}: DEFINE ALTERNATEINDEX needs NAME, RELATE and KEYS")
            definition = _AixDefinition(
                name,
                relate,
                int(keys.group(2)),
                int(keys.group(1)),
                not _NONUNIQUE_RE.search(statement),
            )
            if self._aixes.setdefault(name, definition) != definition:
                raise DatasetError(f"{where}: alternate index {name} is defined two ways")
        elif _DEFINE_PATH_RE.match(statement):
            name, entry = _param(statement, "NAME"), _param(statement, "PATHENTRY")
            if name is None or entry is None:
                raise DatasetError(f"{where}: DEFINE PATH needs NAME and PATHENTRY")
            if self._paths.setdefault(name, entry) != entry:
                raise DatasetError(f"{where}: path {name} is defined over two entries")

    # -- lookups -----------------------------------------------------------

    def logicals(self) -> list[str]:
        """Every logical dataset a dd of ``jobs.yaml`` names, sorted."""
        return sorted(self._dsn_of)

    def dsn_for(self, logical: str) -> tuple[str, bool] | None:
        """``(dsn, is_gdg)`` of a logical dataset, or ``None`` when no dd of
        ``jobs.yaml`` names it (``DTCIN``, for one)."""
        return self._dsn_of.get(logical)

    def logical_for(self, dsn: str) -> str | None:
        """The logical dataset a DSN (a GDG base for a generation) holds."""
        split = split_generation(dsn)
        return self._logical_of.get(dsn) or (self._logical_of.get(split[0]) if split else None)

    def alternate_for(self, path_name: str) -> tuple[str, AlternateKeyDef] | None:
        """The base logical dataset and the alternate key whose ``@AIX`` path
        is ``path_name`` in ``datasets.yaml``."""
        for name in sorted(self.specs):
            for alternate in self.specs[name].alternate_keys:
                if alternate.path == path_name:
                    return name, alternate
        return None

    def aix_dsn_for(self, path_name: str) -> str | None:
        """The alternate-index DSN of an ``@AIX`` path name: through an
        ``aix_path`` dd's path DSN, or through the ``DEFINE ALTERNATEINDEX``
        that relates the base dataset's cluster with that key."""
        for path_dsn, name in sorted(self._path_names.items()):
            if name == path_name and path_dsn in self._paths:
                return self._paths[path_dsn]
        found = self.alternate_for(path_name)
        base = self.dsn_for(found[0]) if found else None
        if found is None or base is None:
            return None
        _, alternate = found
        for name, definition in sorted(self._aixes.items()):
            if definition.relate == base[0] and (definition.offset, definition.length) == (
                alternate.offset,
                alternate.length,
            ):
                return name
        return None

    def path_dsn_for(self, path_name: str) -> str | None:
        """The path DSN of an ``@AIX`` path name."""
        for path_dsn, name in sorted(self._path_names.items()):
            if name == path_name:
                return path_dsn
        aix = self.aix_dsn_for(path_name)
        return next((dsn for dsn, entry in sorted(self._paths.items()) if entry == aix), None)

    def path_for_dsn(self, dsn: str) -> str | None:
        """The ``@AIX`` path name of a path or alternate-index DSN."""
        if dsn in self._path_names:
            return self._path_names[dsn]
        definition = self._aixes.get(self._paths.get(dsn, dsn))
        logical = self._logical_of.get(definition.relate) if definition else None
        spec = self.specs.get(logical) if logical else None
        if definition is None or spec is None:
            return None
        for alternate in spec.alternate_keys:
            if (alternate.offset, alternate.length) == (definition.offset, definition.length):
                return alternate.path
        return None


# --------------------------------------------------------------------------
# Catalog entries
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class Entry:
    """One catalog entry. ``type`` is one of ``CATALOG_TYPES``.

    ``path`` is the data file (CLUSTER, NONVSAM, GENERATION); ``profile`` the
    keys a CLUSTER was defined with (the store records the keys the file
    physically has); ``record_length`` the LRECL or RECORDSIZE. AIX entries
    carry ``relate`` (the base cluster), ``keys`` ``(offset, length)``,
    ``unique`` and ``upgrade``; PATH entries ``pathentry``; GDG bases
    ``limit``, ``scratch`` and ``empty``; GENERATION entries are named
    ``<base>.G<nnnn>V00`` and carry ``base`` and ``number``. ``logical`` is the
    logical dataset the entry holds, where one is known."""

    name: str
    type: str
    path: Path | None = None
    profile: KeyProfile | None = None
    record_length: int | None = None
    logical: str | None = None
    relate: str | None = None
    keys: tuple[int, int] | None = None
    unique: bool | None = None
    upgrade: bool | None = None
    pathentry: str | None = None
    reuse: bool | None = None
    limit: int | None = None
    scratch: bool | None = None
    empty: bool | None = None
    base: str | None = None
    number: int | None = None

    def __post_init__(self) -> None:
        if self.type not in CATALOG_TYPES:
            raise DatasetError(f"catalog type of {self.name} must be one of {CATALOG_TYPES}")
        _checked_name(self.name)
        if self.type == GENERATION:
            if (
                self.base is None
                or self.number is None
                or generation_name(self.base, self.number) != self.name
            ):
                raise DatasetError(f"generation entry {self.name} must be named <base>.G<nnnn>V00")
        if self.type == AIX and (self.relate is None or self.keys is None):
            raise DatasetError(f"alternate index {self.name} needs relate and keys")
        if self.type == PATH and self.pathentry is None:
            raise DatasetError(f"path {self.name} needs a pathentry")

    @property
    def generation(self) -> str | None:
        """``G<nnnn>V00`` of a GENERATION entry, else ``None``."""
        return f"G{self.number:04d}V00" if self.type == GENERATION and self.number else None

    @property
    def observation(self) -> tuple[str, str, str | None]:
        """The ``format_catalog`` entry: a generation is listed under its GDG
        base name with its generation, every other entry under its name."""
        if self.type == GENERATION:
            return (self.base or self.name, GENERATION, self.generation)
        return (self.name, self.type, None)

    # -- factories ---------------------------------------------------------

    @classmethod
    def cluster(
        cls,
        name: str,
        path: Path,
        profile: KeyProfile,
        *,
        logical: str | None = None,
        reuse: bool = False,
    ) -> Entry:
        return cls(
            name,
            CLUSTER,
            path=path,
            profile=profile,
            record_length=profile.record_length,
            logical=logical,
            reuse=reuse,
        )

    @classmethod
    def aix(
        cls,
        name: str,
        relate: str,
        keys: tuple[int, int],
        *,
        unique: bool,
        upgrade: bool = True,
        record_length: int | None = None,
    ) -> Entry:
        return cls(
            name,
            AIX,
            relate=relate,
            keys=(int(keys[0]), int(keys[1])),
            unique=unique,
            upgrade=upgrade,
            record_length=record_length,
        )

    @classmethod
    def path_entry(cls, name: str, pathentry: str) -> Entry:
        return cls(name, PATH, pathentry=pathentry)

    @classmethod
    def gdg(
        cls, name: str, *, limit: int | None, scratch: bool = False, empty: bool = False
    ) -> Entry:
        if limit is not None and (
            isinstance(limit, bool) or not isinstance(limit, int) or limit < 1
        ):
            raise DatasetError(f"GDG {name} LIMIT must be a positive integer, got {limit!r}")
        return cls(name, GDG, limit=limit, scratch=bool(scratch), empty=bool(empty))

    @classmethod
    def generation_entry(
        cls,
        base: str,
        number: int,
        path: Path,
        *,
        record_length: int | None = None,
        logical: str | None = None,
    ) -> Entry:
        return cls(
            generation_name(base, number),
            GENERATION,
            path=path,
            record_length=record_length,
            logical=logical,
            base=base,
            number=number,
        )

    @classmethod
    def nonvsam(
        cls, name: str, path: Path, *, record_length: int | None = None, logical: str | None = None
    ) -> Entry:
        return cls(name, NONVSAM, path=path, record_length=record_length, logical=logical)

    def evidence(self, store: DatasetStore) -> dict[str, Any]:
        """The entry as ``evidence/catalog.yaml`` shows it: set attributes
        only, the file relative to the working directory."""
        shown: dict[str, Any] = {"name": self.name, "type": self.type}
        for attribute in (
            "record_length",
            "logical",
            "relate",
            "unique",
            "upgrade",
            "pathentry",
            "reuse",
            "limit",
            "scratch",
            "empty",
            "base",
            "number",
        ):
            value = getattr(self, attribute)
            if value is not None:
                shown[attribute] = value
        if self.path is not None:
            shown["path"] = store.relative(self.path)
        if self.profile is not None:
            shown["profile"] = self.profile.name
        if self.keys is not None:
            shown["keys"] = {"offset": self.keys[0], "length": self.keys[1]}
        return shown


# --------------------------------------------------------------------------
# Catalog (GAP-STB-009)
# --------------------------------------------------------------------------


def _scenario_part(scenario: Any, name: str) -> Mapping[str, Any]:
    value = getattr(scenario, name, None)
    if value is None and isinstance(scenario, Mapping):
        value = scenario.get(name)
    return value if isinstance(value, Mapping) else {}


def scenario_jobs(scenario: Any) -> list[str]:
    """The jobs of a job scenario's ``inputs.job``, a name or an ordered
    list; empty for a program scenario."""
    job = _scenario_part(scenario, "inputs").get("job")
    names = [job] if isinstance(job, str) else list(job) if isinstance(job, list) else []
    if not all(isinstance(name, str) and name for name in names):
        raise DatasetError(f"inputs.job must be a job name or a list of names, got {job!r}")
    return names


def _input_datasets(inputs_dir: Path) -> list[str]:
    """The logical datasets the runner wrote to ``inputs/``: the names of
    ``inputs/datasets.yaml`` and every ``inputs/<NAME>.dat``, where the file
    exists."""
    names: set[str] = set()
    listing = (
        load_yaml(inputs_dir / "datasets.yaml", missing_ok=True) if inputs_dir.is_dir() else None
    )
    if isinstance(listing, Mapping):
        names.update(str(name) for name in listing)
    if inputs_dir.is_dir():
        names.update(entry.stem for entry in inputs_dir.glob("*.dat") if entry.is_file())
    return sorted(name for name in names if (inputs_dir / f"{name}.dat").is_file())


def _program_open_modes(
    step: Mapping[str, Any], programs: Mapping[str, Any], where: str
) -> dict[str, Any]:
    """``{ddname: open mode}`` of the ``programs.yaml`` unit a program step
    runs and of the units it calls; a unit ``programs`` lacks raises
    ``DatasetError``."""
    unit = step.get("program")
    config = programs.get(unit) if isinstance(unit, str) else None
    if not isinstance(config, Mapping):
        raise DatasetError(f"{where}: program {unit!r} is not a unit of programs.yaml")
    calls = config.get("calls") or []
    if not isinstance(calls, list):
        raise DatasetError(f"{where}: programs.yaml {unit} calls must be a list of unit names")
    called: list[Mapping[str, Any]] = []
    for name in calls:
        called_cfg = programs.get(name) if isinstance(name, str) else None
        if not isinstance(called_cfg, Mapping):
            raise DatasetError(
                f"{where}: {unit} calls {name!r}, which is not a unit of programs.yaml"
            )
        called.append(called_cfg)
    return {ddname: dd.get("open") for ddname, dd in program_dds(config, called)}


def _coded_disposition(entry: Mapping[str, Any], where: str) -> tuple[str, str]:
    """``(status, normal disposition)`` of a dd entry: an omitted status is
    ``NEW``, and an omitted normal disposition is ``DELETE`` after ``NEW`` or
    ``MOD`` (a left-out dataset is absent, so ``MOD`` allocates it) and
    ``KEEP`` otherwise, as the utilities apply them."""
    raw = entry.get("disp")
    if raw is not None and (
        not isinstance(raw, list) or not all(isinstance(part, str) for part in raw)
    ):
        raise DatasetError(f"{where}: disp must be a list of subparameters, got {raw!r}")
    disp = [part.strip().upper() for part in raw or []]
    status = disp[0] if disp and disp[0] else "NEW"
    if len(disp) > 1 and disp[1]:
        return status, disp[1]
    return status, "DELETE" if status in _CREATING_STATUSES else "KEEP"


def _observed_absences(
    names: Sequence[str],
    jobs: Mapping[str, Any],
    programs: Mapping[str, Any],
    mapping: DsnMap,
    gdg_definitions: Mapping[str, Mapping[str, Any]],
    left_out: Iterable[str],
) -> set[str]:
    """The left-out logical datasets whose absence a use of the run reaches
    before the run creates them itself (see ``Catalog.seed``).

    The jobs are walked in order, each job's steps in JCL order, and in a
    step first every dd entry (concatenation members included, as z/OS
    allocates them at step start), then its IDCAMS commands. A dd that the
    step's program opens OUTPUT (``programs`` gives the open modes) writes;
    every other ``SHR`` or ``OLD`` dd reads.

    A dataset that is no generation is replaced by a ``NEW`` or ``MOD`` dd
    whose normal disposition catalogues, deletes or uncatalogues it, or by
    the IDCAMS ``DEFINE`` of its name: from then on its state no longer
    depends on the fault. A program's OUTPUT through ``SHR`` or ``OLD``
    writes a file the catalog still lacks and replaces nothing. Before it is
    replaced, every other dd and every other IDCAMS command naming it
    observes its absence.

    A GDG logical would be seeded as the only generation of its base. A
    ``(+n)`` names only generations of the run. ``(0)`` or ``(-n)`` names the
    seeded one when it is ``(-k)`` after k earlier jobs catalogued a new
    generation of the base and its ``LIMIT`` has not rolled the seeded one
    off; a dd so naming it observes its absence when it reads. An IDCAMS
    command naming the base observes it until a ``DEFINE`` redefines the
    base."""
    targets: dict[str, tuple[str, bool]] = {}
    for logical in sorted(set(left_out)):
        found = mapping.dsn_for(logical)
        if found is not None:
            targets[logical] = found
    observed: set[str] = set()
    replaced: set[str] = set()  # datasets the run created anew, GDG bases it redefined
    created_before: dict[str, int] = {}  # GDG logical -> generations earlier jobs catalogued
    for job_name in names:
        created_here: dict[str, set[int]] = {}
        for step in _job_steps(jobs, job_name):
            where = f"jobs.yaml {job_name} {step.get('step')}"
            modes = (
                _program_open_modes(step, programs, where) if step.get("kind") == "program" else {}
            )
            for ddname, entry in _dd_entries(step):
                logical = entry.get("logical")
                if not isinstance(logical, str) or logical not in targets:
                    continue
                dsn, is_gdg = targets[logical]
                status, normal = _coded_disposition(entry, f"{where} {ddname}")
                writes = modes.get(ddname) == "output"
                if is_gdg:
                    relative = _relative_number(entry.get("gdg"))
                    if relative > 0:
                        if status in _CREATING_STATUSES and normal == "CATLG":
                            created_here.setdefault(logical, set()).add(relative)
                        continue
                    before = created_before.get(logical, 0)
                    limit = (gdg_definitions.get(dsn) or {}).get("limit")
                    if limit is not None and (
                        isinstance(limit, bool) or not isinstance(limit, int) or limit < 1
                    ):
                        raise DatasetError(
                            f"GDG {dsn} LIMIT must be a positive integer, got {limit!r}"
                        )
                    if (
                        logical not in replaced
                        and (limit is None or before < limit)
                        and relative == -before
                        and status in _READING_STATUSES
                        and not writes
                    ):
                        observed.add(logical)
                    continue
                if logical in replaced or logical in observed:
                    continue
                if status in _CREATING_STATUSES or writes:
                    if status in _CREATING_STATUSES and normal in _REPLACING_DISPOSITIONS:
                        replaced.add(logical)
                    continue
                observed.add(logical)
            for statement in _step_statements(step):
                defined = _param(statement, "NAME") if _DEFINE_RE.match(statement) else None
                for token in _DSN_TOKEN_RE.findall(statement):
                    logical = mapping.logical_for(token)
                    if logical not in targets or logical in replaced or logical in observed:
                        continue
                    if defined is not None and token == defined == targets[logical][0]:
                        replaced.add(logical)
                    else:
                        observed.add(logical)
        for logical, relatives in created_here.items():
            created_before[logical] = created_before.get(logical, 0) + len(relatives)
    return observed


class Catalog:
    """The catalog of one scenario (GAP-STB-009).

    ``store`` holds the files, ``dsn_map`` maps logical datasets to DSNs, and
    ``gdg_definitions`` gives the attributes of GDG bases by name (``jobs.yaml``
    ``catalog_seed``), used when a program run catalogues a generation of a
    base no job defined. Entries are kept by absolute name; a generation is
    ``<base>.G<nnnn>V00``. ``left_out`` holds the logical datasets ``seed``
    left out for an ``open``/``unavailable`` fault, whose absence is the
    scenario's declared fault. The catalog never touches a file outside the
    store."""

    def __init__(
        self,
        store: DatasetStore,
        dsn_map: DsnMap,
        gdg_definitions: Mapping[str, Mapping[str, Any]] | None = None,
    ) -> None:
        self.store = store
        self.dsn_map = dsn_map
        self.gdg_definitions = {
            str(name): dict(value) for name, value in (gdg_definitions or {}).items()
        }
        self._entries: dict[str, Entry] = {}
        self.log: list[str] = []
        self.uncatalogued: list[dict[str, str]] = []
        self.left_out: frozenset[str] = frozenset()

    def __repr__(self) -> str:
        return f"Catalog({len(self._entries)} entries, {self.store.workdir})"

    # -- seeding -----------------------------------------------------------

    @staticmethod
    def gdg_seed(jobs_cfg: Mapping[str, Any]) -> dict[str, dict[str, Any]]:
        """The ``catalog_seed.gdg_bases`` of a loaded ``jobs.yaml``, by name."""
        seed = jobs_cfg.get("catalog_seed") if isinstance(jobs_cfg, Mapping) else None
        bases = seed.get("gdg_bases") if isinstance(seed, Mapping) else None
        if bases is None:
            return {}
        if not isinstance(bases, list):
            raise DatasetError("jobs.yaml catalog_seed.gdg_bases must be a list")
        result: dict[str, dict[str, Any]] = {}
        for base in bases:
            if not isinstance(base, Mapping) or not isinstance(base.get("name"), str):
                raise DatasetError(
                    f"jobs.yaml catalog_seed holds a GDG base without a name: {base!r}"
                )
            result[base["name"]] = {
                "limit": base.get("limit"),
                "scratch": bool(base.get("scratch", False)),
                "empty": bool(base.get("empty", False)),
            }
        return result

    @classmethod
    def seed(
        cls,
        scenario: Any,
        jobs_cfg: Mapping[str, Any],
        inputs_dir: str | os.PathLike[str] | None,
        store: DatasetStore,
        faults: Iterable[Mapping[str, Any]] | None = None,
        *,
        programs: Mapping[str, Any],
        dsn_map: DsnMap | None = None,
    ) -> Catalog:
        """The starting catalog of a job scenario.

        1. The GDG bases of ``catalog_seed`` that a ``gdg`` dd of the
           scenario's jobs references and no ``DEFINE GENERATIONDATAGROUP`` of
           those jobs defines (DEFGDGB, DALYREJS and REPTFILE start without
           the bases they define).
        2. Each precondition dataset in ``inputs/`` whose logical dataset a dd
           or an IDCAMS command of the scenario's jobs names (TRANIDX names
           TRANSACT only in its SYSIN): a sequential one as NONVSAM, a ksds
           as a CLUSTER loaded with the ``datasets.yaml`` keys (primary and
           alternates), a GDG logical (TRANSACT-BKUP, SYSTRAN, ...) as
           generation ``G0001V00`` of its base. No AIX or PATH is catalogued:
           the jobs that build them define them.

        A dataset an ``open``/``unavailable`` fault targets is neither
        materialized nor catalogued, and a precondition neither a dd nor an
        IDCAMS command of the jobs uses is ignored; both are logged in
        ``evidence/catalog.yaml``, and ``left_out`` holds the targets. Such a
        fault is accepted only when a use of the run reaches the absent
        dataset before the run creates it itself: a ``SHR`` or ``OLD`` dd its
        step's program does not open OUTPUT, or an IDCAMS command other than
        the ``DEFINE`` of its name, before a ``NEW`` or ``MOD`` dd catalogues,
        deletes or uncatalogues it or a ``DEFINE`` defines it; for a GDG
        logical, a ``SHR`` or ``OLD`` read of the relative generation that
        still names the generation it would be seeded as (``(0)`` before any
        job catalogued a new one, ``(-k)`` after k jobs did, within the
        base's ``LIMIT``), or an IDCAMS command naming the base before a
        ``DEFINE`` redefines it. ``programs`` is the ``programs.yaml``
        ``programs`` table, which gives each program step's open modes; a
        step whose unit it lacks raises ``DatasetError``. Leaving out a
        dataset the jobs never use, only create (``NEW`` or ``MOD`` dds, dds
        their programs open OUTPUT, IDCAMS ``DEFINE``, new generations) or
        read only as the run itself created it fails no OPEN, so such a fault
        raises ``MissingPrerequisite(..., "GAP-MIS-013")`` before any GDG base
        or dataset is seeded. ``faults`` defaults to the scenario's
        ``preconditions.faults``."""
        jobs = _jobs_mapping(jobs_cfg)
        names = scenario_jobs(scenario)
        if not names:
            raise DatasetError("Catalog.seed needs a job scenario (inputs.job)")
        unknown = [name for name in names if name not in jobs]
        if unknown:
            raise DatasetError(f"jobs.yaml has no step table for {', '.join(unknown)}")
        if not isinstance(programs, Mapping):
            raise DatasetError(
                f"Catalog.seed needs the programs.yaml programs table, got "
                f"{type(programs).__name__}"
            )
        mapping = dsn_map if dsn_map is not None else DsnMap(jobs_cfg)
        catalog = cls(store, mapping, cls.gdg_seed(jobs_cfg))
        if faults is None:
            faults = _scenario_part(scenario, "preconditions").get("faults") or []
        left_out = unavailable_targets(faults)

        referenced: set[str] = set()
        defined: set[str] = set()
        used: set[str] = set()
        for job_name in names:
            for step in _job_steps(jobs, job_name):
                for _, entry in _dd_entries(step):
                    if entry.get("gdg") is not None and isinstance(entry.get("dsn"), str):
                        referenced.add(entry["dsn"])
                    if isinstance(entry.get("logical"), str):
                        used.add(entry["logical"])
                for statement in _step_statements(step):
                    gdg_name = (
                        _param(statement, "NAME") if _DEFINE_GDG_RE.match(statement) else None
                    )
                    if gdg_name is not None:
                        defined.add(gdg_name)
                    # A dataset an IDCAMS command names without a DD (TRANIDX's
                    # BLDINDEX INDATASET, a RELATE) is used by the job as well.
                    for dsn in _DSN_TOKEN_RE.findall(statement):
                        logical = mapping.logical_for(dsn)
                        if logical is not None:
                            used.add(logical)
        observed = _observed_absences(
            names, jobs, programs, mapping, catalog.gdg_definitions, left_out
        )
        refused = sorted(left_out - observed)
        if refused:
            listed = ", ".join(names)
            several = len(names) > 1
            unused = [name for name in refused if name not in used]
            created = [name for name in refused if name in used]
            them = "it" if len(refused) == 1 else "them"
            causes = []
            if unused:
                named = ", ".join(unused) if created else them
                causes.append(f"no dd or IDCAMS command of {listed} uses {named}")
            if created:
                named = ", ".join(created) if unused else them
                create, read, their = (
                    ("create", "read", "their") if several else ("creates", "reads", "its")
                )
                causes.append(
                    f"{listed} only {create} {named} or {read} only what the run itself created "
                    f"(NEW or MOD dds, dds {their} programs open OUTPUT, IDCAMS DEFINE, new "
                    f"generations)"
                )
            raise MissingPrerequisite(
                f"open fault (unavailable) on {', '.join(refused)}: {', and '.join(causes)}, "
                f"so leaving {them} out fails no OPEN of the run",
                "GAP-MIS-013",
            )
        catalog.left_out = frozenset(left_out)
        for name in sorted(referenced - defined):
            attributes = catalog.gdg_definitions.get(name)
            if attributes is None:
                catalog.log.append(f"GDG base {name} is referenced but catalog_seed lacks it")
                continue
            catalog.define(Entry.gdg(name, **attributes))
            catalog.log.append(f"seeded GDG base {name}")

        directory = Path(inputs_dir) if inputs_dir is not None else store.workdir / INPUTS_DIR
        source_dir = store.inside(directory)
        for logical in _input_datasets(source_dir):
            if logical in left_out:
                catalog.log.append(f"left out {logical}: open fault, condition unavailable")
            elif logical not in used:
                catalog.log.append(
                    f"ignored precondition {logical}: no dd or IDCAMS command of "
                    f"{', '.join(names)} uses it"
                )
            else:
                catalog.seed_dataset(logical, store.read_sequential(source_dir / f"{logical}.dat"))
        return catalog

    def seed_dataset(self, logical: str, data: bytes) -> Entry:
        """Materialize and catalogue one precondition dataset under the DSN of
        its logical dataset (see ``seed``)."""
        spec = self.dsn_map.specs.get(logical)
        if spec is None:
            raise DatasetError(
                f"{logical} is not a logical dataset of tests/fixtures/datasets.yaml"
            )
        target = self.dsn_map.dsn_for(logical)
        dsn, is_gdg = target if target is not None else (logical, False)
        if is_gdg:
            base = self.get(dsn)
            if base is None or base.type != GDG:
                raise DatasetError(
                    f"precondition {logical} is a generation of {dsn}, which is not a catalogued "
                    f"GDG base when the scenario starts"
                )
            number = (self.newest_number(dsn) or 0) + 1
            path = self.store.path_for(generation_name(dsn, number))
            self.store.write_sequential(path, data, spec.record_length)
            entry = Entry.generation_entry(
                dsn, number, path, record_length=spec.record_length, logical=logical
            )
        elif spec.keyed:
            profile = spec.profile
            if profile is None:
                raise DatasetError(f"{logical} is a ksds without a primary key")
            path = self.store.path_for(dsn)
            self.store.load_indexed(path, data, profile)
            entry = Entry.cluster(dsn, path, profile, logical=logical)
        else:
            path = self.store.path_for(dsn)
            self.store.write_sequential(path, data, spec.record_length)
            entry = Entry.nonvsam(dsn, path, record_length=spec.record_length, logical=logical)
        self.register(entry)
        self.log.append(f"seeded {logical} as {entry.type} {entry.name}")
        return entry

    # -- entries -----------------------------------------------------------

    def get(self, name: str) -> Entry | None:
        return self._entries.get(name)

    def __contains__(self, name: object) -> bool:
        return name in self._entries

    def entries(self) -> list[Entry]:
        """Every entry, by name."""
        return [self._entries[name] for name in sorted(self._entries)]

    def define(self, entry: Entry) -> Entry:
        """Add a new entry; a name already catalogued raises ``DatasetError``
        (IDCAMS reports it as a duplicate data set name, LASTCC 12)."""
        if entry.name in self._entries:
            raise DatasetError(f"{entry.name} is already catalogued")
        self._entries[entry.name] = entry
        return entry

    def register(self, entry: Entry) -> Entry:
        """Add or replace an entry."""
        self._entries[entry.name] = entry
        return entry

    def uncatalog(self, name: str) -> Entry | None:
        """Remove an entry and keep its file; returns the entry."""
        return self._entries.pop(name, None)

    def delete(self, name: str, *, scratch: bool = True) -> list[str]:
        """Remove an entry and what depends on it, returning the removed names.

        A CLUSTER takes its alternate indexes and their paths with it, an AIX
        its paths, a GDG base its generations. With ``scratch`` the data files
        go too (a CLUSTER's alternate-key files included); without it they
        stay on disk, uncatalogued."""
        entry = self._entries.get(name)
        if entry is None:
            return []
        removed: list[str] = []
        if entry.type == CLUSTER:
            for aix in self.aixes_of(name):
                removed += self.delete(aix.name, scratch=scratch)
        elif entry.type == AIX:
            for path in self.paths_of(name):
                removed += self.delete(path.name, scratch=scratch)
        elif entry.type == GDG:
            for generation in self.generations(name):
                removed += self.delete(generation.name, scratch=scratch)
        del self._entries[name]
        removed.append(name)
        if scratch and entry.path is not None:
            self.store.remove(entry.path)
        return removed

    def aixes_of(self, cluster: str) -> list[Entry]:
        return [entry for entry in self.entries() if entry.type == AIX and entry.relate == cluster]

    def paths_of(self, aix: str) -> list[Entry]:
        return [entry for entry in self.entries() if entry.type == PATH and entry.pathentry == aix]

    def base_cluster(self, name: str) -> Entry | None:
        """The CLUSTER a DSN reaches: itself, the base of an AIX, or the base
        of a PATH's alternate index (a path dd opens the base cluster)."""
        entry = self._entries.get(name)
        if entry is not None and entry.type == PATH and entry.pathentry is not None:
            entry = self._entries.get(entry.pathentry)
        if entry is not None and entry.type == AIX and entry.relate is not None:
            entry = self._entries.get(entry.relate)
        return entry if entry is not None and entry.type == CLUSTER else None

    def path_for(self, name: str) -> Path:
        """The file of a catalogued dataset, else the file a new dataset of
        that name gets."""
        entry = self._entries.get(name)
        if entry is not None and entry.path is not None:
            return entry.path
        return self.store.path_for(name)

    # -- generations -------------------------------------------------------

    def generations(self, base: str) -> list[Entry]:
        """The catalogued generations of a GDG base, oldest first."""
        found = [
            entry
            for entry in self._entries.values()
            if entry.type == GENERATION and entry.base == base
        ]
        return sorted(found, key=lambda entry: entry.number or 0)

    def newest_generation(self, base: str) -> Entry | None:
        generations = self.generations(base)
        return generations[-1] if generations else None

    def newest_number(self, base: str) -> int | None:
        newest = self.newest_generation(base)
        return newest.number if newest is not None else None

    def catalog_new(self, entry: Entry) -> list[str]:
        """Catalogue a dataset created with ``DISP=(NEW,CATLG)`` at normal step
        end; returns the generations rolled off.

        A GENERATION needs its catalogued GDG base. When the base then holds
        more generations than its ``LIMIT``, the oldest roll off (all older
        ones under ``EMPTY``): their files are deleted under ``SCRATCH`` and
        kept under ``NOSCRATCH``, where each kept file is recorded as
        uncatalogued (``note_uncatalogued``). A name already catalogued is
        replaced, and the replacement is logged; the allocation failure z/OS
        gives a duplicate NEW dataset is not emulated (GAP-STB-007)."""
        if entry.name in self._entries:
            self.log.append(f"replaced catalogued {entry.name}")
        if entry.type != GENERATION:
            self._entries[entry.name] = entry
            return []
        base = self._entries.get(entry.base or "")
        if base is None or base.type != GDG:
            raise DatasetError(
                f"{entry.name} is a generation of {entry.base}, which is not a catalogued GDG base"
            )
        self._entries[entry.name] = entry
        generations = self.generations(base.name)
        if base.limit is None or len(generations) <= base.limit:
            return []
        older = [generation for generation in generations if generation.name != entry.name]
        excess = len(generations) - base.limit
        rolled = older if base.empty else older[:excess]
        for generation in rolled:
            del self._entries[generation.name]
            if base.scratch:
                if generation.path is not None:
                    self.store.remove(generation.path)
                self.log.append(f"rolled off {generation.name}: scratched")
            else:
                self.note_uncatalogued(
                    generation.name,
                    generation.path,
                    f"rolled off under NOSCRATCH when {entry.name} was catalogued",
                )
                self.log.append(f"rolled off {generation.name}: uncatalogued, file kept")
        return [generation.name for generation in rolled]

    def note_uncatalogued(self, name: str, path: Path | None, reason: str) -> None:
        """Record a dataset left on disk without a catalog entry, such as the
        NEW datasets of a step that abended (no disposition is applied)."""
        self.uncatalogued.append(
            {
                "name": name,
                "path": self.store.relative(path) if path is not None else "-",
                "reason": reason,
            }
        )

    # -- program runs ------------------------------------------------------

    def register_program_datasets(
        self,
        unit_cfg: Mapping[str, Any],
        called_cfgs: Iterable[Mapping[str, Any]] = (),
        dd_paths: Mapping[str, Path] | None = None,
    ) -> list[Entry]:
        """Catalogue the datasets of a program run that exist on disk, so
        ``@CATALOG`` is defined for unit scenarios too. Each is named by the
        DSN ``jobs.yaml`` gives its logical dataset (the logical name when it
        has none): indexed files as CLUSTER with their recorded keys,
        sequential files as NONVSAM, and a GDG logical as generation
        ``G0001V00`` of its base, whose attributes come from ``catalog_seed``.
        Call it after the run; it may be called again."""
        registered: list[Entry] = []
        for ddname, dd in program_dds(unit_cfg, called_cfgs):
            path = (dd_paths or {}).get(ddname)
            logical = dd.get("logical")
            if path is None or not isinstance(logical, str) or not self.store.exists(path):
                continue
            target = self.dsn_map.dsn_for(logical)
            dsn, is_gdg = target if target is not None else (logical, False)
            record_length = dd.get("record_length")
            if dd.get("organization") == "indexed":
                profile = self.store.profile(path) or profile_for(record_length, dd["keys"])
                entry = Entry.cluster(dsn, Path(path), profile, logical=logical)
            elif is_gdg:
                if dsn not in self._entries:
                    attributes = self.gdg_definitions.get(dsn, {"limit": None})
                    self.register(Entry.gdg(dsn, **attributes))
                entry = Entry.generation_entry(
                    dsn, 1, Path(path), record_length=record_length, logical=logical
                )
            else:
                entry = Entry.nonvsam(dsn, Path(path), record_length=record_length, logical=logical)
            registered.append(self.register(entry))
        return registered

    # -- observations ------------------------------------------------------

    def catalog_observation(self) -> bytes:
        """The ``@CATALOG`` observation (``adapters.base.format_catalog``):
        one line per entry, ``<name> <type> <generation or ->``, sorted, a
        generation under its base name. The snapshot goes to
        ``evidence/catalog.yaml``."""
        self.write_evidence()
        return format_catalog(entry.observation for entry in self.entries())

    def aix_observation(self, path_name: str) -> bytes | None:
        """The ``@AIX:<path>`` observation: the base cluster's records read
        through the alternate index that the catalogued path ``path_name``
        (a ``datasets.yaml`` ``alternate_keys[].path``) reaches, in
        alternate-key order, ties in primary-key order.

        The path resolves to its AIX through the catalog and the AIX to its
        base cluster. When the base file does not physically carry that key
        (a program re-keyed or recreated it), it is rebuilt from the base's
        current records first, as UPGRADE keeps a VSAM alternate index
        current. ``None`` when the name is unknown, or the path, its AIX or
        its base cluster is not catalogued."""
        found = self.dsn_map.alternate_for(path_name)
        target = self.dsn_map.dsn_for(found[0]) if found else None
        if found is None or target is None:
            return None
        alternate = found[1]
        wanted = (alternate.offset, alternate.length)
        aix = next(
            (
                entry
                for entry in self.aixes_of(target[0])
                if entry.keys == wanted and self.paths_of(entry.name)
            ),
            None,
        )
        base = self.base_cluster(target[0])
        if aix is None or base is None or base.path is None or not self.store.exists(base.path):
            return None
        physical = self.store.profile(base.path)
        if physical is None:
            raise DatasetError(f"{base.name} has no recorded key profile")
        if not any(alt[:2] == wanted for alt in physical.alternates):
            rebuilt = physical.with_alternates([*physical.alternates, (*wanted, not aix.unique)])
            if not self.store.rekey(base.path, rebuilt):
                raise DatasetError(
                    f"the records of {base.name} hold duplicate keys at {wanted[0]}/{wanted[1]}, "
                    f"which its unique alternate index {aix.name} cannot index"
                )
            physical = rebuilt
        data = self.store.unload_indexed(base.path, physical, wanted)
        length = physical.record_length
        return format_fixed_records(
            (data[start : start + length] for start in range(0, len(data), length)), length
        )

    def read_logical(self, logical: str) -> bytes | None:
        """The bytes of a logical dataset as the catalog holds it when called
        (the end of the scenario, or the end of one job for an
        ``@AFTER:<JOB>:<LOGICAL>`` observation): a CLUSTER unloaded in
        primary-key order, a sequential dataset as written, a GDG logical as
        its newest catalogued generation. ``None`` when it is not catalogued
        or its file is absent."""
        target = self.dsn_map.dsn_for(logical)
        dsn, is_gdg = target if target is not None else (logical, False)
        entry = self.newest_generation(dsn) if is_gdg else self._entries.get(dsn)
        if entry is None or entry.path is None or not self.store.exists(entry.path):
            return None
        if entry.type == CLUSTER:
            return self.store.unload_indexed(entry.path)
        return self.store.read_sequential(entry.path)

    def snapshot(self) -> dict[str, Any]:
        """Entries, uncatalogued datasets and the log, as evidence shows them."""
        return {
            "entries": [entry.evidence(self.store) for entry in self.entries()],
            "log": list(self.log),
            "uncatalogued": list(self.uncatalogued),
        }

    def write_evidence(self) -> Path:
        """Write ``evidence/catalog.yaml``."""
        target = self.store.inside(self.store.evidence_dir / _CATALOG_EVIDENCE)
        write_text(target, dump_yaml(self.snapshot()))
        return target


# --------------------------------------------------------------------------
# Relative generations of one job
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class GenerationRef:
    """A resolved relative generation: ``name`` is ``<base>.G<nnnn>V00`` and
    ``path`` its file; ``catalogued`` says whether the catalog held it when it
    was resolved (a ``(+1)`` is catalogued only at normal end of the step that
    creates it)."""

    base: str
    relative: int
    number: int
    name: str
    path: Path
    catalogued: bool

    @property
    def generation(self) -> str:
        return f"G{self.number:04d}V00"

    def entry(self, *, record_length: int | None = None, logical: str | None = None) -> Entry:
        """The GENERATION entry ``Catalog.catalog_new`` takes for it."""
        return Entry.generation_entry(
            self.base, self.number, self.path, record_length=record_length, logical=logical
        )


def _relative_number(gdg: Any) -> int:
    text = str(gdg).strip()
    if not re.fullmatch(r"[+-]?[0-9]{1,3}", text):
        raise DatasetError(f"relative generation must be like +1, 0 or -1, got {gdg!r}")
    return int(text)


class JobGenerations:
    """The relative generations of one job, fixed as z/OS fixes them when the
    job starts.

    The newest generation of every GDG base is taken when the object is
    created (at job start): ``(0)`` is that generation and ``(-n)`` the n-th
    older one, for the whole job, even after a step adds a newer one. The
    first ``(+n)`` of a base allocates the generations after the newest one
    of that moment, and every later ``(+n)`` of the job returns the same one
    (TRANREPT writes ``TRANSACT.BKUP(+1)`` in one step and sorts it in the
    next)."""

    def __init__(self, catalog: Catalog) -> None:
        self.catalog = catalog
        self._at_start: dict[str, list[int]] = {}
        for entry in catalog.entries():
            if entry.type == GDG:
                self._at_start[entry.name] = [
                    generation.number or 0 for generation in catalog.generations(entry.name)
                ]
        self._new_from: dict[str, int] = {}

    def resolve(self, dsn: str, gdg: Any) -> GenerationRef | None:
        """The generation ``dsn(gdg)`` names in this job, or ``None`` when
        there is none: ``(0)`` or ``(-n)`` of a base without that many
        generations at job start (or one removed since), and any reference to
        a base that is not a catalogued GDG. The caller turns ``None`` into
        the allocation failure z/OS reports."""
        relative = _relative_number(gdg)
        base = self.catalog.get(dsn)
        if base is None or base.type != GDG:
            return None
        if relative <= 0:
            numbers = self._at_start.get(dsn, [])
            if len(numbers) < 1 - relative:
                return None
            number = numbers[relative - 1]
            name = generation_name(dsn, number)
            entry = self.catalog.get(name)
            if entry is None:
                return None
            return GenerationRef(dsn, relative, number, name, self.catalog.path_for(name), True)
        if dsn not in self._new_from:
            self._new_from[dsn] = self.catalog.newest_number(dsn) or 0
        number = self._new_from[dsn] + relative
        if number > _MAX_GENERATION:
            raise DatasetError(
                f"{dsn} has no generation number left after G{_MAX_GENERATION:04d}V00"
            )
        name = generation_name(dsn, number)
        return GenerationRef(
            dsn, relative, number, name, self.catalog.path_for(name), name in self.catalog
        )


# --------------------------------------------------------------------------
# Program runs
# --------------------------------------------------------------------------


def program_dds(
    unit_cfg: Mapping[str, Any], called_cfgs: Iterable[Mapping[str, Any]] = ()
) -> list[tuple[str, Mapping[str, Any]]]:
    """``(ddname, dd)`` of a ``programs.yaml`` unit and of the units it calls
    (CBSTM03B's dds reach the CBSTM03A run), in that order. A ddname declared
    twice must name the same logical dataset."""
    result: list[tuple[str, Mapping[str, Any]]] = []
    seen: dict[str, str | None] = {}
    for config in [unit_cfg, *called_cfgs]:
        if not isinstance(config, Mapping):
            raise DatasetError(f"a programs.yaml unit must be a mapping, got {config!r}")
        dds = config.get("dd") or {}
        if not isinstance(dds, Mapping):
            raise DatasetError("a programs.yaml unit's dd must be a mapping")
        for ddname in sorted(dds):
            dd = dds[ddname]
            if not isinstance(dd, Mapping):
                raise DatasetError(f"programs.yaml dd {ddname} is not a mapping")
            logical = dd.get("logical")
            if ddname in seen:
                if seen[ddname] != logical:
                    raise DatasetError(
                        f"programs.yaml dd {ddname} names {seen[ddname]} and {logical} in "
                        f"linked units"
                    )
                continue
            seen[ddname] = logical
            result.append((str(ddname), dd))
    return result


def _declared_profile(dd: Mapping[str, Any], ddname: str) -> KeyProfile:
    keys = dd.get("keys")
    if not isinstance(keys, Mapping):
        raise DatasetError(f"programs.yaml dd {ddname} is indexed but declares no keys")
    return profile_for(dd.get("record_length"), keys)


def _with_duplicates(profile: KeyProfile) -> KeyProfile:
    return profile.with_alternates(
        [(offset, length, True) for offset, length, _ in profile.alternates]
    )


def _load_for_program(store: DatasetStore, path: Path, data: bytes, declared: KeyProfile) -> None:
    """Load a program's indexed input with its declared keys. When records
    share an alternate key the program declares unique, every record is kept
    by building that key with duplicates instead, which a reader declaring it
    unique still opens and reads by key; the substitution is logged to
    ``evidence/rekey.log``."""
    if not store.load_indexed(path, data, declared, on_duplicate="report"):
        return
    fallback = _with_duplicates(declared)
    store.load_indexed(path, data, fallback)
    store.log_rekey(
        f"{store.relative(path)} {declared.name} could not hold every record (duplicate alternate "
        f"keys); loaded as {fallback.name}"
    )


def materialize_program(
    unit_cfg: Mapping[str, Any],
    called_cfgs: Iterable[Mapping[str, Any]],
    workdir: str | os.PathLike[str],
    inputs_dir: str | os.PathLike[str] | None,
    faults: Iterable[Mapping[str, Any]] | None,
    *,
    store: DatasetStore,
) -> dict[str, Path]:
    """Lay out the datasets of a program run; returns ``{ddname: path}`` for
    every dd of the unit and the units it calls.

    Each dataset is ``data/<LOGICAL>`` (two dds of one logical dataset share
    the file). An ``input``, ``io`` or ``extend`` dd takes the first of its
    ``logical`` and ``also_accepts`` names that has ``inputs/<name>.dat``:
    sequential data is copied, indexed data loaded with the keys the
    program's SELECT declares. With none present the path stays absent, so
    the program's OPEN fails with status 35, as the ``recovery-01`` scenarios
    pin. An ``output`` dd is only allocated, except that an indexed one is
    loaded from its precondition when given (CBTRN02C's ``OPEN OUTPUT``
    recreates TRANFILE anyway, GAP-STB-010). A dataset an
    ``open``/``unavailable`` fault targets is left out even when provided.
    ``store`` is the scenario's ``DatasetStore`` and ``workdir`` its working
    directory."""
    if Path(workdir).resolve() != store.workdir:
        raise DatasetError(f"materialize_program: {workdir} is not the store's working directory")
    source_dir = store.inside(
        Path(inputs_dir) if inputs_dir is not None else store.workdir / INPUTS_DIR
    )
    left_out = unavailable_targets(faults)
    paths: dict[str, Path] = {}
    laid_out: dict[str, tuple[Path, str, KeyProfile | None]] = {}
    for ddname, dd in program_dds(unit_cfg, called_cfgs):
        logical = dd.get("logical")
        organization = dd.get("organization")
        open_mode = dd.get("open")
        if not isinstance(logical, str) or organization not in ("indexed", "sequential"):
            raise DatasetError(
                f"programs.yaml dd {ddname} needs a logical dataset and an organization"
            )
        if open_mode not in _OPEN_MODES:
            raise DatasetError(f"programs.yaml dd {ddname} has open mode {open_mode!r}")
        declared = _declared_profile(dd, ddname) if organization == "indexed" else None
        if logical in laid_out:
            path, first_organization, first_profile = laid_out[logical]
            if (first_organization, first_profile) != (organization, declared):
                raise DatasetError(f"programs.yaml dds of {logical} declare it two ways")
            paths[ddname] = path
            continue
        path = store.path_for(logical)
        store.remove(path)
        laid_out[logical] = (path, organization, declared)
        paths[ddname] = path
        if logical in left_out:
            continue
        candidates = (
            [logical] if open_mode == "output" else [logical, *(dd.get("also_accepts") or [])]
        )
        source = next(
            (
                source_dir / f"{name}.dat"
                for name in candidates
                if name not in left_out and (source_dir / f"{name}.dat").is_file()
            ),
            None,
        )
        if source is None or (open_mode == "output" and declared is None):
            continue
        if declared is not None:
            _load_for_program(store, path, store.read_sequential(source), declared)
        else:
            store.copy_sequential(source, path, dd.get("record_length"))
    return paths


def read_back_program(
    unit_cfg: Mapping[str, Any],
    called_cfgs: Iterable[Mapping[str, Any]],
    dd_paths: Mapping[str, Path],
    *,
    store: DatasetStore,
) -> dict[str, bytes]:
    """The output and I-O datasets of a finished program run, by logical
    name: sequential files exactly as written, indexed files unloaded in
    primary-key order with the keys the program declares. A dataset whose
    file does not exist (the program never opened it) is left out. An indexed
    file the program opened OUTPUT is first recorded with its declared keys
    (``after_program_output``)."""
    datasets: dict[str, bytes] = {}
    for ddname, dd in program_dds(unit_cfg, called_cfgs):
        open_mode = dd.get("open")
        path = dd_paths.get(ddname)
        logical = dd.get("logical")
        if open_mode not in _WRITE_MODES or path is None or not isinstance(logical, str):
            continue
        if not store.exists(path):
            continue
        if dd.get("organization") == "indexed":
            declared = _declared_profile(dd, ddname)
            if open_mode == "output" or store.profile(path) is None:
                store.after_program_output(path, declared)
            datasets[logical] = store.unload_indexed(path, declared)
        else:
            datasets[logical] = store.read_sequential(path)
    return dict(sorted(datasets.items()))
