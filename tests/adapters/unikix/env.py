"""UniKix environment and command runner of the ``unikix`` adapter.

It does two things, and only on an operator host. CI never sets
``CARDDEMO_UNIKIX_ENV``, so there every ``unikix`` scenario is ``blocked``
under GAP-MIS-009 and this module writes nothing.

``UniKixEnvironment``
    Sources the operator's environment file, which has the format of the
    zip's ``bin/carddemo.env``, in a clean bash. It then replaces that file's
    ``BASE_DIR=$HOME/unikixdemo`` with the test-owned deployment root
    ``<work root>/unikix/<execution_id>/`` and recomputes every variable
    derived from it with the definitions of ``carddemo.env``, so the region,
    the BPE node and their catalogs belong to this run alone. Once the
    deployment exists, ``source_runtime_scripts`` merges the deployment's
    ``userenv``, which it sources itself, and its ``batchenv``, which the
    ``bpe_env`` entry sources through ``CommandSet.capture``.
``CommandSet``
    The only executor of UniKix commands. It runs entries of
    ``commands.yaml`` by id and nothing else: ``run`` returns what a command
    printed, ``capture`` the environment a command that sources a script
    leaves. Each outcome follows the adapter's command rule: a missing
    capability (command ``null``, failed probe, non-zero exit, own
    ``timeout_s`` exceeded) is
    ``AdapterUnavailable`` with the entry's gap while the scenario deadline
    has not passed, and expiry of the deadline is ``ScenarioTimeout``. The
    deadline takes precedence: it is checked again once a probe or command
    has ended and been logged, so an outcome reached after it, failure or
    success, is ``ScenarioTimeout``, with any superseded
    ``AdapterUnavailable`` kept only as its cause and in the command log.
    A command or ``cwd`` that uses ``$MBMSUB``, the BPE submission
    directory, runs only while ``MBMSUB``, which only the deployment's
    ``batchenv`` supplies, resolves (symlinks followed) inside the
    deployment root; otherwise it is ``AdapterUnavailable`` too.
    ``CommandResult`` carries only the return code and the output, so no
    application outcome is ever inferred from a command failure.

Probe results are cached per environment *generation*. ``load`` starts
generation 0, and every ``source_runtime_scripts`` call, made once the
deployment exists, starts the next one (``capture`` probes its entry afresh
for that next generation). Probes that test the deployed tree
(``$MBMSUB``, ``$EBMHOME/batchenv``, ``$KIXSYS/unikixrc.cfg``) are therefore
re-run against it instead of keeping the answer they gave before deployment.
The ``$MBMSUB`` containment check is never cached: it is made on every probe
and run, so a submission directory changed within a generation is seen at
once.

Every write stays inside the deployment root: the empty ``home/`` used as
``HOME``, ``logs/commands.log`` and ``logs/sourcing.log``, unlinked spool
files for command output, and ``tmp/``, the temporary directory of every
command and both sourcing stages.
``load`` makes the root, ``home/``, ``tmp/`` and ``logs/`` real directories
of the effective user before it sources anything; every process start
checks the root, ``tmp/`` and ``logs/`` again, every log write the root and
``logs/``, and a log file is never opened through a link, so no write goes
through a planted link.
``TMPDIR``, ``TMP`` and ``TEMP`` name ``tmp/``; ``_JAVA_OPTIONS`` is exactly
``-Djava.io.tmpdir=<it> -XX:+PerfDisableSharedMem``, and ``JAVA_TOOL_OPTIONS``
(and ``JDK_JAVA_OPTIONS`` when set) end with the same options, because a JVM
ignores ``TMPDIR`` and HotSpot otherwise writes ``/tmp/hsperfdata_<user>``;
HotSpot reads ``_JAVA_OPTIONS`` after the command line, so it wins over a
vendor script's own ``-Djava.io.tmpdir``. They are set again at the start
of every process, and every bash started for the deployment holds
``TMPDIR``, ``TMP``, ``TEMP`` and ``_JAVA_OPTIONS`` read-only
(``_TEMP_HOLD``): the environment file, userenv, batchenv, a command and a
script it sources cannot change them. A runtime script that leaves JVM
options naming another ``java.io.tmpdir``, or with an unmatched quote,
makes its stage unavailable.

Evidence gets nonsecret facts only. ``as_record``, the source of
``environment.yaml``, holds the environment file and its sha256, the
installation and deployment paths, the runtime script states and the
generation, with ``JAVA_TOOL_OPTIONS`` and ``_JAVA_OPTIONS`` shown as the
owned temporary settings and every other option as ``REDACTED``; it never
dumps ``vars``, which keep the operator's options. ``redact`` hides those
other options, of the environment a command ran with and of every JVM's
``Picked up`` line, and the value of a hidden ``-D`` property wherever a
property listing shows it, in every command log entry, every version
probe's text and every build log (``deploy.py``). What the environment
file, userenv and batchenv print while sourced stays in the deployment
root's ``logs/``: ``sourcing.log`` holds all of it, and batchenv's is also
the ``stderr`` of its ``bpe_env`` command log entry, which the evidence
copy withholds (``render_evidence_log``). BPE job sysout, generated JCL
and raw observations are raw evidence, kept unchanged and not redacted.
Python 3.11 standard library and PyYAML only. Nothing here uses the network.
"""

from __future__ import annotations

import errno
import os
import re
import shlex
import shutil
import signal
import stat
import string
import subprocess
import sys
import tempfile
import threading
import time
from collections.abc import Iterator, Mapping
from dataclasses import dataclass, field
from pathlib import Path
from types import MappingProxyType
from typing import Any, NoReturn

_TESTS_ROOT = str(Path(__file__).resolve().parents[2])
if _TESTS_ROOT not in sys.path:
    sys.path.insert(0, _TESTS_ROOT)

from adapters.base import GAP_ID_RE, AdapterUnavailable, ScenarioTimeout  # noqa: E402
from harness import HarnessError, dump_yaml, load_yaml, sha256_file  # noqa: E402

__all__ = [
    "BPE_NODE_NAME",
    "BPE_SUBSYSTEM",
    "COMMANDS_FILE",
    "DEPLOYMENT_PATH_VARS",
    "ENV_VARIABLE",
    "INSTALLATION_VARS",
    "LOG_ELAPSED_KEY",
    "LOG_LIMIT_BYTES",
    "PROBE_TIMEOUT_CAP_S",
    "REDACTED",
    "REQUIRED_INSTALLATION_VARS",
    "SOURCE_TIMEOUT_S",
    "VERSION_ENTRIES",
    "CommandEntry",
    "CommandResult",
    "CommandSet",
    "UniKixEnvironment",
    "redact",
    "redact_jvm_options",
]

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

ENV_VARIABLE = "CARDDEMO_UNIKIX_ENV"
COMMANDS_FILE = Path(__file__).resolve().with_name("commands.yaml")

_GAP_INSTALLATION = "GAP-MIS-009"  # no usable UniKix installation
_GAP_USERENV = "GAP-MIS-003"  # region creation, of which $KIXSYS/bin/userenv is part
_GAP_BATCHENV = "GAP-MIS-006"  # BPE submission, of which $EBMHOME/batchenv is part
# The commands.yaml entry that sources $EBMHOME/batchenv.
_BPE_ENV_ENTRY = "bpe_env"

# PR_BPENODE_NAME and PR_BPESUBSYS_NAME of carddemo.env.
BPE_NODE_NAME = "bpenode"
BPE_SUBSYSTEM = "sys1"

REQUIRED_INSTALLATION_VARS = ("UNIKIX", "INSTEBM", "VCOBOL", "KIXLICDIR", "JAVA_HOME")
# The installation directories a search path names, so load refuses one whose
# path holds os.pathsep, and _runtime_escape a userenv or batchenv that moves
# one to such a path: COBCPY ends with $UNIKIX/copy, and the environment
# file puts $UNIKIX/bin and $VCOBOL/bin on PATH and $VCOBOL/lib on
# LD_LIBRARY_PATH. INSTEBM, KIXLICDIR and JAVA_HOME are named by no search
# path, so a ':' in them breaks nothing.
_SEARCH_PATH_INSTALLATION_VARS = ("UNIKIX", "VCOBOL")
# TPE_AGENT is recorded but not required: nothing the suite runs uses it.
INSTALLATION_VARS = REQUIRED_INSTALLATION_VARS + ("TPE_AGENT",)
# Every path this module derives; each must lie inside the deployment root.
DEPLOYMENT_PATH_VARS = (
    "BASE_DIR",
    "PROJECT_ROOT",
    "PR",
    "KIXSYS",
    "KIXDATA",
    "EBMHOME",
    "JOBLIB",
    "MBM_LOCK_DIR",
    "MIGAPP_DIR",
    "PARMFILES",
    "SEQFILES",
    "HOME",
    "TMPDIR",
    "TMP",
    "TEMP",
)
# environment.yaml records these as they are, then the always-set JVM option
# variables, redacted (as_record).
_DEPLOYMENT_RECORD_VARS = DEPLOYMENT_PATH_VARS + (
    "EBMSYS",
    "COBCPY",
    "MBMSUB",
)

# The owned temporary directory <deployment root>/tmp. Native tools take it
# from TMPDIR, or TMP and TEMP. A JVM ignores all three: on Linux
# java.io.tmpdir defaults to /tmp, so it is passed as an option, and HotSpot
# writes /tmp/hsperfdata_<user>/<pid> whatever java.io.tmpdir says unless
# shared performance memory is disabled.
_TEMP_DIR_NAME = "tmp"
_TEMP_DIR_MODE = 0o700
# <deployment root>/logs: commands.log, sourcing.log and the spool files of
# command output. sourcing.log holds what the environment file, userenv and
# batchenv print while sourced, which never reaches the evidence.
_LOG_DIR_NAME = "logs"
_COMMANDS_LOG = "commands.log"
_SOURCING_LOG = "sourcing.log"
_OWNED_DIR_MODE = 0o755
_SHARED_WRITE = stat.S_IWGRP | stat.S_IWOTH
_TEMP_VARS = ("TMPDIR", "TMP", "TEMP")
# Every JVM reads JAVA_TOOL_OPTIONS; the java launcher reads JDK_JAVA_OPTIONS
# after it and HotSpot reads _JAVA_OPTIONS after the command line, so only
# _JAVA_OPTIONS wins over a -Djava.io.tmpdir a vendor script passes itself.
# The last occurrence of an option wins, so the owned options go last in each.
_JVM_OPTION_VARS = ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
# Both are always set; _JAVA_OPTIONS holds the owned options alone.
_JVM_ALWAYS_SET = ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS")
_JVM_OWNED_ONLY = "_JAVA_OPTIONS"
_JVM_TMPDIR = "-Djava.io.tmpdir="
_JVM_PERF_OFF = "-XX:+PerfDisableSharedMem"
_JVM_PERF_ON = "-XX:-PerfDisableSharedMem"
_HSPERFDATA = "/tmp/hsperfdata_<user>"
# What separates the options of a JVM option variable: C isspace().
_JVM_SPACE = frozenset(" \t\n\v\f\r")
_JVM_QUOTES = frozenset("'\"")
# What evidence-bound text shows in place of every JVM option other than the
# owned temporary settings, and of such a -D property's value in a property
# listing (redact_jvm_options, redact).
REDACTED = "<redacted>"
# The line every JVM prints on stderr for each JVM option variable it reads,
# "Picked up JAVA_TOOL_OPTIONS: <value>" and "Picked up _JAVA_OPTIONS: <value>"
# from HotSpot, "NOTE: Picked up JDK_JAVA_OPTIONS: <value>" from the java
# launcher; matched anywhere in a line, so a prefix stays as printed.
_JVM_ECHO_RE = re.compile(
    r"(Picked up (?:" + "|".join(re.escape(name) for name in _JVM_OPTION_VARS) + r"): )([^\r\n]*)"
)
# Where a JVM option stands as a word of its own: after the start, whitespace,
# a quote or "=" (NAME=<option>, '<option>'), and before the end, whitespace or
# a quote.
_JVM_TOKEN_BEFORE = r"(?<![^\s'\"=])"
_JVM_TOKEN_AFTER = r"(?![^\s'\"])"
# A JVM option variable is split at whitespace and reads quotes. deploy.py's
# work-root check uses this pattern too; harness/__init__.py holds the same
# one for the owner-only work-root admission, since the harness does not
# import the adapters.
_JVM_UNSAFE_PATH_RE = re.compile(r"[\s'\"]")
# Why no deployment directory may lie below a path holding os.pathsep: COBCPY
# names the staging copy's copybook folders below the deployment root, the
# environment file puts $HOME/bin (home/ below the root) on PATH, the vendor's
# userenv may add more of the root's folders to PATH and LD_LIBRARY_PATH, and
# each of those lists is split at os.pathsep, so a folder whose path holds it
# is named by none. load refuses such a deployment root and deploy.py's
# work-root check such a work root, each naming it first; harness/__init__.py
# holds the same text for the owner-only work-root admission, since the
# harness does not import the adapters.
_PATH_LIST_UNSAFE = (
    f"holds {os.pathsep!r}, at which COBCPY, PATH and LD_LIBRARY_PATH are split, so the search "
    "paths naming the deployment's directories below it would break; choose another work root"
)
# The first statement of every bash this module starts for a deployment: what
# that bash runs or sources afterwards (the environment file, userenv,
# batchenv, a command, a script a command sources) can then neither assign,
# export nor unset the four temporary-file settings it names.
_TEMP_HOLD = "readonly TMPDIR TMP TEMP _JAVA_OPTIONS"

VERSION_ENTRIES = (
    "version_tpe",
    "version_bpe",
    "version_vcobol",
    "version_java",
    "version_licence",
    "version_s3270",
)

SOURCE_TIMEOUT_S = 60
PROBE_TIMEOUT_CAP_S = 30
LOG_LIMIT_BYTES = 64 * 1024
# The key of a log entry's elapsed seconds. The in-memory log and
# <deployment root>/logs/commands.log keep it; the copy in a scenario's
# committed evidence leaves it out (render_evidence_log), because elapsed
# times go only to the git-ignored tests/reports/runtime/ (AAP 0.4.6).
LOG_ELAPSED_KEY = "elapsed_s"

_COMMAND_STATUSES = ("confirmed", "unconfirmed")
_ENTRY_ID_RE = re.compile(r"^[a-z][a-z0-9_]*$")
_PLACEHOLDER_RE = re.compile(r"^[a-z][a-z0-9_]*$")
_SHELL_VAR_RE = re.compile(r"\$([A-Za-z_][A-Za-z0-9_]*)")
_REQUIRED_KEYS = ("action", "command", "probe", "timeout_s", "gap", "status", "source", "notes")
_OPTIONAL_KEYS = ("cwd", "parse", "template")

# Variables of the capturing bash itself, not settings of the environment file.
_SHELL_ARTIFACTS = frozenset({"_", "SHLVL", "PWD", "OLDPWD"})
# A non-interactive bash sources $BASH_ENV (and sh mode $ENV) even under
# --noprofile --norc, which would bring back startup files from outside the
# deployment, so neither is passed on.
_STARTUP_FILE_VARS = frozenset({"BASH_ENV", "ENV"})
# An env -0 entry without "=", printed right before the env -0 of a sourced
# script, so an environment is taken only from a shell that reached it.
_SNAPSHOT_MARK = "--carddemo-runtime-snapshot--"


# --------------------------------------------------------------------------
# Processes
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class _Completed:
    returncode: int | None  # None when the process was killed at its timeout
    stdout: bytes
    stderr: bytes
    elapsed_s: float
    timed_out: bool


def _bash() -> str:
    return shutil.which("bash") or "/bin/bash"


def _kill_group(process: subprocess.Popen) -> None:
    """Terminate, then kill, the process group of ``process``. The group is
    the child's own, because it was started with ``start_new_session``."""
    group = process.pid
    for sig, grace in ((signal.SIGTERM, 2.0), (signal.SIGKILL, 10.0)):
        try:
            os.killpg(group, sig)
        except OSError:
            pass  # the group has already gone
        try:
            process.wait(timeout=grace)
        except subprocess.TimeoutExpired:
            continue


def _run_process(
    argv: list[str], *, env: Mapping[str, str], cwd: str, timeout: float, spool_dir: str
) -> _Completed:
    """Run ``argv`` in a new session, its output spooled to unlinked files.

    Files are used instead of pipes because a UniKix start command can leave a
    daemon that inherits its stdout. Reading a pipe to EOF would then wait for
    the daemon rather than for the command. Only the process group is killed
    at the timeout, so a daemon that detached itself keeps running, as a
    started region must."""
    started = time.monotonic()
    with (
        tempfile.TemporaryFile(dir=spool_dir) as out,
        tempfile.TemporaryFile(dir=spool_dir) as err,
    ):
        process = subprocess.Popen(
            argv,
            env=dict(env),
            cwd=cwd,
            stdin=subprocess.DEVNULL,
            stdout=out,
            stderr=err,
            start_new_session=True,
        )
        timed_out = False
        try:
            returncode: int | None = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            _kill_group(process)
            returncode, timed_out = None, True
        except BaseException:
            _kill_group(process)
            raise
        elapsed = time.monotonic() - started
        out.seek(0)
        err.seek(0)
        return _Completed(returncode, out.read(), err.read(), elapsed, timed_out)


def _text(data: bytes) -> str:
    return data.decode("utf-8", errors="replace")


def _clip(text: str) -> tuple[str, bool]:
    """``text`` cut to ``LOG_LIMIT_BYTES`` of UTF-8, and whether it was cut."""
    data = text.encode("utf-8")
    if len(data) <= LOG_LIMIT_BYTES:
        return text, False
    return data[:LOG_LIMIT_BYTES].decode("utf-8", errors="ignore"), True


def _snapshots(data: bytes) -> list[dict[str, str]]:
    """Parse ``env -0`` output into environments, one per snapshot, split at
    ``_SNAPSHOT_MARK`` entries. Values keep undecodable bytes as surrogate
    escapes, so they reach child processes unchanged."""
    snapshots: list[dict[str, str]] = [{}]
    for item in data.split(b"\0"):
        if not item:
            continue
        entry = os.fsdecode(item)
        if entry == _SNAPSHOT_MARK:
            snapshots.append({})
            continue
        name, sep, value = entry.partition("=")
        if sep and name:
            snapshots[-1][name] = value
    return snapshots


def _settings(variables: Mapping[str, str]) -> dict[str, str]:
    return {
        name: value
        for name, value in variables.items()
        if name not in _SHELL_ARTIFACTS and name not in _STARTUP_FILE_VARS
    }


def _inside(path: str | os.PathLike[str], root: Path) -> bool:
    """Whether ``path`` resolves to ``root`` or below it. A path that cannot
    be resolved, such as a symlink loop, is not."""
    try:
        Path(path).resolve().relative_to(root.resolve())
    except (ValueError, OSError, RuntimeError):
        return False
    return True


def _mbmsub_refusal(value: str | None, root: str | os.PathLike[str] | None) -> str:
    """Why ``value`` may not serve as ``MBMSUB``, the directory every BPE
    submission runs in, or ``""`` when it may. It must be set, which only
    the deployment's batchenv does, absolute, and resolve (symlinks
    followed) to the deployment ``root`` or below it, so a submission never
    reaches another node's BPE catalogs."""
    if not value:
        return "MBMSUB is not set: the deployment's batchenv has not exported it"
    if not os.path.isabs(value):
        return f"MBMSUB is not an absolute path: {value}"
    if root is None:
        return f"MBMSUB {value} cannot be checked: the environment names no deployment root"
    if not _inside(value, Path(root)):
        return f"MBMSUB {value} does not resolve to the deployment root {root} or below"
    return ""


def _own_directory(path: Path, mode: int = _OWNED_DIR_MODE) -> Path:
    """``path``, created with ``mode`` when it is missing. It must then be a
    real directory, not a symbolic link, of the effective user, or
    ``HarnessError`` names it; group and other write bits are removed.
    Passed each directory after its parent, before anything is written
    below it, so no write is sent elsewhere through a planted link.
    ``deploy.py`` checks the same directories again before it deploys."""
    try:
        os.mkdir(path, mode)
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
    if stat.S_ISLNK(status.st_mode):
        problem = "is a symbolic link"
    elif not stat.S_ISDIR(status.st_mode):
        problem = "is not a directory"
    elif status.st_uid != os.geteuid():
        problem = f"is owned by uid {status.st_uid}, not {os.geteuid()}"
    else:
        problem = ""
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
    return path


def _temp_dir(root: str | os.PathLike[str]) -> Path:
    """``<root>/tmp``, the owned temporary directory, checked again with the
    root before every process (``_own_directory``): either is created when
    missing (``tmp/`` ``0700``), and a link or another user's directory that
    replaced it since ``load`` raises ``HarnessError``."""
    return _own_directory(_own_directory(Path(root)) / _TEMP_DIR_NAME, _TEMP_DIR_MODE)


def _log_dir(root: str | os.PathLike[str]) -> Path:
    """``<root>/logs``, where command output is spooled and ``commands.log``
    is written, checked again with the root before every process and every
    log write (``_own_directory``): either is created when missing (``logs/``
    ``0755``), and a link or another user's directory that replaced it since
    ``load`` raises ``HarnessError``."""
    return _own_directory(_own_directory(Path(root)) / _LOG_DIR_NAME)


def _append_log(log: Path, text: str) -> None:
    """Append ``text`` to the file ``log``, created when missing, never
    through a symbolic link: one planted there raises ``HarnessError`` naming
    it, and nothing is written."""
    try:
        descriptor = os.open(log, os.O_WRONLY | os.O_APPEND | os.O_CREAT | os.O_NOFOLLOW, 0o666)
    except OSError as exc:
        if exc.errno == errno.ELOOP:
            raise HarnessError(
                f"the log file {log} is a symbolic link; remove it or choose another work root"
            ) from None
        raise
    with open(descriptor, "a", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


class _UnmatchedQuote(ValueError):
    """A JVM option variable holds a quote it never closes. HotSpot and the
    java launcher refuse to start on one."""


def _jvm_options(value: str) -> list[tuple[str, str]]:
    """The options of the JVM option variable ``value`` as ``(raw,
    decoded)`` pairs, split as HotSpot splits ``JAVA_TOOL_OPTIONS`` and
    ``_JAVA_OPTIONS`` and the java launcher ``JDK_JAVA_OPTIONS``: whitespace
    separates options, and a single or double quote groups everything up to
    the next same quote, whitespace included, anywhere in an option
    (``-Dx="a b"``). ``raw`` is the option as written, ``decoded`` the
    option the JVM gets, its quotes removed. No backslash escapes. A quote
    that is never closed raises ``_UnmatchedQuote``."""
    options: list[tuple[str, str]] = []
    index, end = 0, len(value)
    while True:
        while index < end and value[index] in _JVM_SPACE:
            index += 1
        if index == end:
            return options
        start = index
        decoded: list[str] = []
        while index < end and value[index] not in _JVM_SPACE:
            char = value[index]
            if char in _JVM_QUOTES:
                close = value.find(char, index + 1)
                if close < 0:
                    raise _UnmatchedQuote()
                decoded.append(value[index + 1 : close])
                index = close + 1
            else:
                decoded.append(char)
                index += 1
        options.append((value[start:index], "".join(decoded)))


def _unmatched_quote(variables: Mapping[str, str]) -> str | None:
    """The first JVM option variable of ``variables`` with a quote it never
    closes (``_jvm_options``), or ``None``."""
    for name in _JVM_OPTION_VARS:
        try:
            _jvm_options(variables.get(name, ""))
        except _UnmatchedQuote:
            return name
    return None


def _temp_option(decoded: str) -> bool:
    """Whether the decoded JVM option sets ``java.io.tmpdir`` or
    ``PerfDisableSharedMem``, which only the owned options may set."""
    return decoded.startswith(_JVM_TMPDIR) or decoded in (_JVM_PERF_OFF, _JVM_PERF_ON)


def _contain_temp(variables: dict[str, str], temp_dir: str | os.PathLike[str]) -> dict[str, str]:
    """Point every temporary-file setting of ``variables`` at ``temp_dir``,
    in place, and return ``variables``.

    ``TMPDIR``, ``TMP`` and ``TEMP`` become ``temp_dir``, and
    ``_JAVA_OPTIONS`` exactly the owned options ``-Djava.io.tmpdir=<temp_dir>
    -XX:+PerfDisableSharedMem`` (see ``_TEMP_DIR_NAME``). ``JAVA_TOOL_OPTIONS``
    always, and ``JDK_JAVA_OPTIONS`` when it is set, keep each option as
    written except those that set ``java.io.tmpdir`` or
    ``PerfDisableSharedMem`` (read as a JVM reads them, ``_jvm_options``),
    one space apart, and end with the owned options. Applying it again
    changes nothing. ``HarnessError`` when ``temp_dir`` holds whitespace or
    a quote, which a JVM option variable would split or decode, or when one
    of those variables has an unmatched quote: ``load`` and
    ``_runtime_escape`` refuse such a value, so only a later change to
    ``vars`` brings one here."""
    path = os.fspath(temp_dir)
    if _JVM_UNSAFE_PATH_RE.search(path):
        raise HarnessError(
            f"the temporary directory {path!r} holds whitespace or a quote, which a JVM "
            "option variable cannot carry; choose another work root"
        )
    for name in _TEMP_VARS:
        variables[name] = path
    owned = [f"{_JVM_TMPDIR}{path}", _JVM_PERF_OFF]
    for name in _JVM_OPTION_VARS:
        if name == _JVM_OWNED_ONLY:
            variables[name] = " ".join(owned)
            continue
        if name not in _JVM_ALWAYS_SET and name not in variables:
            continue  # JAVA_TOOL_OPTIONS reaches every JVM; JDK_JAVA_OPTIONS matters only when set
        try:
            options = _jvm_options(variables.get(name, ""))
        except _UnmatchedQuote:
            raise HarnessError(f"UniKix variable {name} has an unmatched quote") from None
        kept = [raw for raw, decoded in options if not _temp_option(decoded)]
        variables[name] = " ".join(kept + owned)
    return variables


def _jvm_temp_escape(variables: Mapping[str, str], root: Path) -> str:
    """How the JVM option variables of ``variables``, which hold no
    unmatched quote (``_unmatched_quote``), let a JVM write a temporary file
    outside ``root``, or ``""`` when they do not. Each option is read as the
    JVM gets it, quotes removed (``_jvm_options``). ``JAVA_TOOL_OPTIONS``,
    which every JVM reads, and ``_JAVA_OPTIONS``, which HotSpot reads after
    the command line, must name a ``java.io.tmpdir`` inside ``root`` last and
    disable shared performance memory last. ``JDK_JAVA_OPTIONS``, read
    between them, must not do otherwise last."""
    for name in _JVM_OPTION_VARS:
        value = variables.get(name)
        always = name in _JVM_ALWAYS_SET
        if value is None and not always:
            continue
        options = [decoded for _raw, decoded in _jvm_options(value or "")]
        tmpdirs = [option[len(_JVM_TMPDIR) :] for option in options if option.startswith(_JVM_TMPDIR)]
        if tmpdirs:
            last = tmpdirs[-1]
            if not os.path.isabs(last) or not _inside(last, root):
                return f"{name} sets {_JVM_TMPDIR}{last} last"
        elif always:
            return f"{name} sets no -Djava.io.tmpdir, so java.io.tmpdir is /tmp"
        perf = [option for option in options if option in (_JVM_PERF_OFF, _JVM_PERF_ON)]
        if perf and perf[-1] == _JVM_PERF_ON:
            return f"{name} sets {_JVM_PERF_ON} last, so HotSpot writes {_HSPERFDATA}"
        if not perf and always:
            return f"{name} lacks {_JVM_PERF_OFF}, so HotSpot writes {_HSPERFDATA}"
    return ""


def _shown_option(raw: str, decoded: str) -> bool:
    """Whether evidence may show the JVM option written ``raw``, which a JVM
    reads as ``decoded``: only an owned temporary setting, one that sets
    ``java.io.tmpdir`` or ``PerfDisableSharedMem`` (``_temp_option``),
    written without quotes."""
    return raw == decoded and _temp_option(decoded)


def redact_jvm_options(value: str) -> str:
    """The JVM option variable ``value`` as evidence shows it: each option,
    read as a JVM reads it (``_jvm_options``), kept as written when it is an
    owned temporary setting (``_shown_option``) and ``REDACTED`` otherwise,
    one space apart. A value with an unmatched quote, such as a clipped
    echo, is ``REDACTED`` whole; a blank one is ``""``. Applying it again
    changes nothing."""
    try:
        options = _jvm_options(value)
    except _UnmatchedQuote:
        return REDACTED
    return " ".join(raw if _shown_option(raw, decoded) else REDACTED for raw, decoded in options)


def redact(text: str, variables: Mapping[str, str]) -> str:
    """``text``, output of a command that ran with the environment
    ``variables``, with every JVM option but the owned temporary settings
    hidden. Each option of a JVM option variable of ``variables`` that
    ``redact_jvm_options`` hides, as written and as a JVM reads it, becomes
    ``REDACTED`` wherever it stands as a word of its own (``_JVM_TOKEN_BEFORE``,
    ``_JVM_TOKEN_AFTER``), as in a ``set -x`` trace or an ``env`` listing; a
    variable with an unmatched quote is hidden whole. Next, for each hidden
    option that a JVM reads as ``-D<name>=<value>``, ``<value>`` becomes
    ``REDACTED`` wherever a property listing shows it, as ``<name> =
    <value>`` (``java -XshowSettings:properties``) or ``<name>=<value>``:
    ``<name>`` after anything but a word character, ``.`` or ``-``, spaces
    or tabs around ``=``, ``<value>`` before the end, whitespace or a quote
    (``_JVM_TOKEN_AFTER``), the name and ``=`` kept as printed. Then the
    value of every JVM's ``Picked up <variable>: <value>`` line becomes what
    ``redact_jvm_options`` shows, which also hides options a command set for
    itself. Text that holds no such option or property value is returned
    unchanged, and applying it again changes nothing."""
    hidden: set[str] = set()
    # (name, value) of each hidden -D<name>=<value>, as a JVM reads it.
    properties: set[tuple[str, str]] = set()
    for name in _JVM_OPTION_VARS:
        value = variables.get(name)
        if not value:
            continue
        try:
            options = _jvm_options(value)
        except _UnmatchedQuote:
            hidden.add(value.strip())
            continue
        for raw, decoded in options:
            if not _shown_option(raw, decoded):
                hidden.update((raw, decoded))
                key, equals, setting = decoded.removeprefix("-D").partition("=")
                if decoded.startswith("-D") and key and equals and setting:
                    properties.add((key, setting))
    hidden.discard("")
    hidden.discard(REDACTED)
    if hidden:
        # One alternation, longest first, so an option is hidden whole even
        # where a shorter one is a prefix of it.
        words = "|".join(re.escape(word) for word in sorted(hidden, key=lambda word: (-len(word), word)))
        pattern = re.compile(f"{_JVM_TOKEN_BEFORE}(?:{words}){_JVM_TOKEN_AFTER}")
        text = pattern.sub(REDACTED, text)
    if properties:
        # One alternative per property, longest first, so a value is hidden
        # whole even where a shorter value of the same name is a prefix of
        # it; each captures what stays, the name and "=" as printed.
        listed = "|".join(
            f"({re.escape(key)}[ \\t]*=[ \\t]*){re.escape(setting)}"
            for key, setting in sorted(properties, key=lambda pair: (-len(pair[0]) - len(pair[1]), pair))
        )
        pattern = re.compile(f"(?<![\\w.-])(?:{listed}){_JVM_TOKEN_AFTER}")
        text = pattern.sub(lambda match: match.group(match.lastindex) + REDACTED, text)
    return _JVM_ECHO_RE.sub(lambda match: match.group(1) + redact_jvm_options(match.group(2)), text)


def _derived(root: str, unikix: str, inherited_cobcpy: str) -> dict[str, str]:
    """The deployment variables, with the definitions of ``carddemo.env``
    under ``BASE_DIR=<deployment root>``. ``PR``, ``PARMFILES`` and
    ``SEQFILES``, which ``carddemo.env`` leaves unset, are those the zip's
    ``bin/deploy_app`` and ``bin/compile`` expect. ``COBCPY`` extends the
    inherited value as ``bin/compile:19`` does, empty first element
    included."""
    project = f"{root}/carddemo"
    migapp = f"{root}/staging/migrated_app"
    return {
        "BASE_DIR": root,
        "PROJECT_ROOT": project,
        "PR_BPENODE_NAME": BPE_NODE_NAME,
        "PR_BPESUBSYS_NAME": BPE_SUBSYSTEM,
        "MIGAPP_DIR": migapp,
        "KIXSYS": f"{project}/kixsys",
        "KIXDATA": f"{project}/vsam",
        "EBMHOME": f"{root}/{BPE_NODE_NAME}",
        "EBMSYS": BPE_SUBSYSTEM,
        "JOBLIB": f"{project}/loadlib",
        "MBM_LOCK_DIR": f"{project}/lock",
        "PR": project,
        "PARMFILES": f"{project}/parmlib",
        "SEQFILES": f"{project}/seqfiles",
        "COBCPY": _cobcpy(inherited_cobcpy, migapp, unikix),
    }


def _cobcpy(inherited: str, migapp: str, unikix: str) -> str:
    return f"{inherited}:{migapp}/cpy:{migapp}/cpy-bms:{unikix}/copy"


# --------------------------------------------------------------------------
# Environment
# --------------------------------------------------------------------------


class UniKixEnvironment:
    """The environment every UniKix command of one session runs with.

    ``vars`` is the complete environment passed to each command: what the
    operator's file exported except ``MBMSUB``, with the deployment
    variables replaced by the test-owned ones and, once the deployment
    exists, what ``userenv`` and ``batchenv`` add. Each re-read of those
    scripts starts again from what ``load`` produced, so repeated re-reads
    never accumulate. ``generation`` counts them (see the module docstring).
    Build it with ``load``."""

    def __init__(
        self,
        *,
        env_file: Path,
        env_file_sha256: str,
        work_root: Path,
        deployment_root: Path,
        variables: dict[str, str],
        inherited_cobcpy: str,
        sourcing_output: str,
    ) -> None:
        self.env_file = env_file
        self.env_file_sha256 = env_file_sha256
        self.work_root = work_root
        self.deployment_root = deployment_root
        self.home = deployment_root / "home"
        self.temp_dir = deployment_root / _TEMP_DIR_NAME
        self.vars = variables
        self.generation = 0
        self.runtime_scripts = {"userenv": "not-sourced", "batchenv": "not-sourced"}
        self.sourcing_output = sourcing_output
        self._inherited_cobcpy = inherited_cobcpy
        # The environment load produced, which every source_runtime_scripts call starts from.
        self._load_vars = dict(variables)
        self._load_inherited_cobcpy = inherited_cobcpy

    def __repr__(self) -> str:
        # Never the variables: an operator's file may export anything.
        return (
            f"UniKixEnvironment(env_file={str(self.env_file)!r}, "
            f"deployment_root={str(self.deployment_root)!r}, generation={self.generation})"
        )

    # ----------------------------------------------------------------- load

    @classmethod
    def load(
        cls,
        work_root: str | os.PathLike[str],
        deployment_root: str | os.PathLike[str],
        *,
        environ: Mapping[str, str] | None = None,
    ) -> UniKixEnvironment:
        """Source ``$CARDDEMO_UNIKIX_ENV`` and derive the deployment variables.

        ``deployment_root`` is ``<work root>/unikix/<execution_id>``. Before
        the file is sourced, each directory from below the work root down to
        the root, its ``home/``, its ``tmp/`` (``0700``) and its ``logs/`` is
        created when missing and must be a real directory of the effective
        user, never a symbolic link (``_own_directory``). ``environ`` defaults to
        ``os.environ`` and supplies ``CARDDEMO_UNIKIX_ENV`` and ``PATH``,
        nothing else. The file is sourced with ``HOME`` set to that empty
        ``home/``, so the ``userenv`` and ``batchenv`` of an operator's own
        ``$HOME/unikixdemo`` are never sourced, and with ``TMPDIR``, ``TMP``,
        ``TEMP`` and ``_JAVA_OPTIONS`` owned (``_contain_temp``) and
        read-only, so the file cannot change or unset them while it is
        sourced. Whatever the file exports, ``HOME`` is then ``home/`` and
        every temporary-file setting points at ``tmp/``, the JVM's
        ``java.io.tmpdir`` included. What the file prints is kept in
        ``sourcing_output`` and appended, its JVM options hidden
        (``redact``), to ``logs/sourcing.log`` (``_log_sourcing``); neither
        reaches the evidence.

        Raises ``AdapterUnavailable`` with GAP-MIS-009 when the variable is
        unset or names no readable file, before anything is created; when
        sourcing the file returns non-zero (a syntax error, which stops bash
        reading it, or a failing last command, such as one that assigns,
        exports or unsets ``TMPDIR``, ``TMP``, ``TEMP`` or ``_JAVA_OPTIONS``)
        or times out; when the file leaves ``JAVA_TOOL_OPTIONS`` or
        ``JDK_JAVA_OPTIONS`` with an unmatched quote, on which no JVM starts;
        and when ``UNIKIX``, ``INSTEBM``, ``VCOBOL``, ``KIXLICDIR`` or
        ``JAVA_HOME`` is unset, is not an absolute path, is missing or is not
        a directory, or when ``UNIKIX`` or ``VCOBOL``, whose directories
        ``COBCPY``, ``PATH`` and ``LD_LIBRARY_PATH`` name, holds
        ``os.pathsep`` (``_SEARCH_PATH_INSTALLATION_VARS``).
        Raises ``HarnessError``, naming the path, when the
        deployment root does not lie below the work root or its path holds
        ``os.pathsep`` (``:``), at which ``COBCPY``, ``PATH`` and
        ``LD_LIBRARY_PATH`` are split (``_PATH_LIST_UNSAFE``), both after the
        variable's checks and before anything is created; when one of the
        directories above is a link, not a directory or another user's, or a
        derived path lies outside the root, when ``tmp/`` holds whitespace
        or a quote, and when ``logs/sourcing.log`` cannot be written
        (``append_log``)."""
        source = os.environ if environ is None else environ
        setting = (source.get(ENV_VARIABLE) or "").strip()
        if not setting:
            raise AdapterUnavailable(
                f"{ENV_VARIABLE} not set: no UniKix installation is named for this run",
                _GAP_INSTALLATION,
            )
        env_file = Path(os.path.abspath(setting))
        if not env_file.is_file() or not os.access(env_file, os.R_OK):
            raise AdapterUnavailable(
                f"{ENV_VARIABLE} names no readable file: {env_file}", _GAP_INSTALLATION
            )

        work = Path(os.path.abspath(work_root))
        root = Path(os.path.abspath(deployment_root))
        try:
            below = root.relative_to(work).parts
        except ValueError:
            below = ()
        if not below or root.resolve() == work.resolve() or not _inside(root, work):
            raise HarnessError(f"deployment root {root} must lie below the work root {work}")
        # Every deployment variable is derived from this path, COBCPY's
        # copybook folders included, so one holding os.pathsep is refused
        # before anything is created below it.
        if os.pathsep in str(root):
            raise HarnessError(f"deployment root {root} {_PATH_LIST_UNSAFE}")
        # Every directory below the work root that the file, and later every
        # command, writes in is a real one of this user before the file is
        # sourced, so no write goes through a link planted there.
        work.mkdir(parents=True, exist_ok=True)
        directory = work
        for part in below:
            directory = _own_directory(directory / part)
        home = _own_directory(root / "home")
        temp = _own_directory(root / _TEMP_DIR_NAME, _TEMP_DIR_MODE)
        # The session-start probes spool output and append commands.log here
        # before deploy.py checks the deployment tree again.
        _own_directory(root / _LOG_DIR_NAME)

        captured, sourcing_output = _source_env_file(env_file, home, temp, root, source)
        inherited_cobcpy = captured.get("COBCPY", "")
        variables = _settings(captured)
        quoted = _unmatched_quote(variables)
        if quoted:
            raise AdapterUnavailable(
                f"{quoted} set by {env_file} has an unmatched quote", _GAP_INSTALLATION
            )
        # The BPE submission directory is the test node's, which only the
        # deployment's batchenv supplies. A value the file exported, or left
        # from a batchenv it sourced, names another node's directory, so
        # MBMSUB stays unset until source_runtime_scripts brings it back.
        variables.pop("MBMSUB", None)
        variables.update(_derived(str(root), variables.get("UNIKIX", ""), inherited_cobcpy))
        variables["HOME"] = str(home)
        _contain_temp(variables, temp)

        for name in REQUIRED_INSTALLATION_VARS:
            value = variables.get(name, "")
            # Absoluteness first, so the verdict never depends on the caller's cwd.
            if value and not os.path.isabs(value):
                reason = f"{name} is not an absolute path: {value}"
            elif not value or not os.path.exists(value):
                reason = f"{name} missing: {value or '(unset)'}"
            elif not os.path.isdir(value):
                reason = f"{name} is not a directory: {value}"
            elif name in _SEARCH_PATH_INSTALLATION_VARS and os.pathsep in value:
                reason = (
                    f"{name} {value} holds {os.pathsep!r}, at which COBCPY, PATH and LD_LIBRARY_PATH "
                    "are split, so the search paths naming its directories would break; choose an "
                    f"installation path without {os.pathsep!r}"
                )
            else:
                continue
            raise AdapterUnavailable(reason, _GAP_INSTALLATION)

        environment = cls(
            env_file=env_file,
            env_file_sha256=sha256_file(env_file),
            work_root=work,
            deployment_root=root,
            variables=variables,
            inherited_cobcpy=inherited_cobcpy,
            sourcing_output=sourcing_output,
        )
        outside = environment._outside_paths(variables)
        if outside:
            raise HarnessError(
                "derived paths outside the deployment root "
                f"{root}: {', '.join(f'{name}={value}' for name, value in outside)}"
            )
        environment._log_sourcing(env_file, sourcing_output, variables)
        return environment

    # -------------------------------------------------------------- accessors

    @property
    def log_dir(self) -> Path:
        """``<deployment root>/logs``, where ``commands.log`` is written."""
        return self.deployment_root / _LOG_DIR_NAME

    def append_log(self, name: str, text: str) -> Path:
        """Append ``text`` to the file ``name`` in ``log_dir`` and return its
        path. The root and ``log_dir`` are checked again first (``_log_dir``)
        and the file is never opened through a link (``_append_log``), so a
        link or another user's directory planted since ``load`` raises
        ``HarnessError`` and nothing is written."""
        log = _log_dir(self.deployment_root) / name
        _append_log(log, text)
        return log

    def redact(self, text: str) -> str:
        """``text``, output of a command run with ``vars``, with every JVM
        option but the owned temporary settings hidden (``redact``)."""
        return redact(text, self.vars)

    def _log_sourcing(self, script: Path, output: str, variables: Mapping[str, str]) -> None:
        """Append ``output``, what sourcing ``script`` printed, to
        ``logs/sourcing.log`` under a ``--- <script> ---`` line, its JVM
        options hidden as ``variables``, the environment the script left,
        name them (``redact``); nothing when it printed nothing. A failing
        write raises ``HarnessError`` (``append_log``)."""
        if not output:
            return
        text = redact(output, variables)
        if not text.endswith("\n"):
            text += "\n"
        self.append_log(_SOURCING_LOG, f"--- {script} ---\n{text}")

    @property
    def mbmsub(self) -> str | None:
        """The BPE submission directory ``batchenv`` exports, or ``None``
        while it is unset (before batchenv has been sourced, or when it
        exports none), is not an absolute path, or does not resolve, symlinks
        followed, to the deployment root or below. ``CommandSet`` refuses
        every command that uses ``$MBMSUB`` in the same cases."""
        value = self.vars.get("MBMSUB")
        return None if _mbmsub_refusal(value, self.deployment_root) else value

    def get(self, name: str, default: str | None = None) -> str | None:
        return self.vars.get(name, default)

    def path(self, name: str) -> Path:
        """The variable ``name`` as a path; ``HarnessError`` when unset."""
        value = self.vars.get(name)
        if not value:
            raise HarnessError(f"UniKix variable {name} is not set")
        return Path(value)

    def _outside_paths(self, variables: Mapping[str, str]) -> list[tuple[str, str]]:
        return [
            (name, variables.get(name) or "(unset)")
            for name in DEPLOYMENT_PATH_VARS
            if not variables.get(name) or not _inside(variables[name], self.deployment_root)
        ]

    def _runtime_escape(self, snapshot: Mapping[str, str]) -> str:
        """Why a runtime script's ``snapshot`` cannot be kept, as the rest of
        ``"<script> ..."``, or ``""``: it moves a derived path that is unset or
        outside the deployment root, moves one, or ``UNIKIX`` or ``VCOBOL``
        (``_SEARCH_PATH_INSTALLATION_VARS``), to a path holding
        ``os.pathsep``, at which ``COBCPY`` (extended from ``MIGAPP_DIR`` and
        ``UNIKIX``), ``PATH`` and ``LD_LIBRARY_PATH`` are split, leaves a JVM
        option variable with an unmatched quote (``_unmatched_quote``), on
        which no JVM starts, or moves the JVM's temporary files elsewhere
        (``_jvm_temp_escape``).
        ``TMPDIR``, ``TMP``, ``TEMP`` and ``_JAVA_OPTIONS`` are held
        read-only while the script runs (``_TEMP_HOLD``), so they are checked
        only as a defence. Such a stage is refused rather than repaired: a
        command that sources the script again itself, as ``region_install``
        sources ``userenv``, would run with what it sets."""
        outside = self._outside_paths(snapshot)
        if outside:
            name, value = outside[0]
            return f"moves {name} outside the deployment root: {value}"
        for name in DEPLOYMENT_PATH_VARS:
            value = snapshot[name]
            if os.pathsep in value:
                return (
                    f"moves {name} to {value}, which holds {os.pathsep!r}, at which COBCPY, PATH "
                    "and LD_LIBRARY_PATH are split"
                )
        for name in _SEARCH_PATH_INSTALLATION_VARS:
            value = snapshot.get(name, "")
            if os.pathsep in value:
                return (
                    f"moves {name} to {value}, which holds {os.pathsep!r}, at which COBCPY, PATH "
                    "and LD_LIBRARY_PATH are split"
                )
        quoted = _unmatched_quote(snapshot)
        if quoted:
            return f"leaves {quoted} with an unmatched quote"
        jvm = _jvm_temp_escape(snapshot, self.deployment_root)
        if jvm:
            return f"moves the JVM's temporary files outside the deployment root: {jvm}"
        return ""

    # ------------------------------------------------------ runtime scripts

    def source_runtime_scripts(
        self, commands: CommandSet, *, deadline_left: float | None = None
    ) -> dict[str, str]:
        """Merge ``$KIXSYS/bin/userenv`` and then ``$EBMHOME/batchenv sys1``
        into ``vars``, as ``carddemo.env`` sources them once they exist.

        Called by ``deploy.py`` with the session's ``commands``: once
        ``region_create_script`` has made userenv, so that userenv is vetted
        before ``region_install`` sources it, and again once the BPE node
        exists, when batchenv is sourced too. A script is sourced only when
        it is a file inside the deployment root. userenv, which no
        ``commands.yaml`` entry names, is sourced here in a bash of its own,
        bounded by ``SOURCE_TIMEOUT_S``, starting from the environment
        ``load`` produced with its inherited ``COBCPY``. batchenv is sourced
        by running the ``bpe_env`` entry through ``commands.capture`` in what
        userenv exported, so batchenv sees what userenv set, the entry's
        probe gates it, its ``timeout_s`` bounds it, and an operator's
        override of its command is what runs; ``$EBMHOME`` is then the one
        userenv left. A script that extends a variable, such as
        ``PATH=<dir>:$PATH``, extends it once however often this is called.
        Each bash starts in ``PROJECT_ROOT`` once that exists, with the owned
        temporary directory (``_contain_temp``) and ``TMPDIR``, ``TMP``,
        ``TEMP`` and ``_JAVA_OPTIONS`` read-only (``_TEMP_HOLD``): a script
        line that assigns, exports or unsets one fails with ``readonly
        variable`` and changes nothing, so a temporary file the script makes
        afterwards lands in ``tmp/``; only as its last line does it fail the
        script's ``.``. ``COBCPY`` is then extended
        again from whatever the scripts left, as ``bin/compile`` would extend
        it in a shell where they had been sourced, and the temporary-file
        settings are pointed at ``tmp/`` again. When neither script is
        sourced, ``vars`` returns to what ``load`` produced. Every call starts
        a new ``generation``. What each script printed is kept in
        ``sourcing_output`` and appended, its JVM options hidden as the
        environment that script left names them (``redact``), to
        ``logs/sourcing.log`` (``_log_sourcing``); neither reaches the
        evidence. A write that fails raises ``HarnessError`` before ``vars``
        changes.

        Returns the state of each script: ``sourced``, ``absent``,
        ``outside-deployment-root`` or, for batchenv, ``unavailable`` when
        ``bpe_env`` has ``command: null`` or its probe fails. Raises
        ``AdapterUnavailable`` with GAP-MIS-003 when userenv fails, times
        out, moves a deployment path or the JVM's temporary files out of the
        deployment root, moves a deployment path, ``UNIKIX`` or ``VCOBOL`` to
        one holding ``os.pathsep``, such as a ``MIGAPP_DIR`` or ``UNIKIX``
        whose copybook folders ``COBCPY`` would then split, or leaves a JVM
        option variable with an unmatched quote (``_runtime_escape``), and
        with GAP-MIS-006 when batchenv does or ``bpe_env`` exits non-zero or
        exceeds its own ``timeout_s``.
        ``MBMSUB`` is kept as the scripts leave it and is not checked here;
        it stays unset unless they export it. ``CommandSet`` refuses each
        command that uses ``$MBMSUB``, under that entry's own gap
        (GAP-MIS-006 for ``bpe_submit``, GAP-MIS-007 for the dataset and
        catalog entries), while it does not resolve inside the deployment
        root; the commands that do not use it (region, listener, build) stay
        available.

        ``deadline_left`` is the seconds left before the scenario deadline
        when the caller has one; ``None`` bounds each stage by its own limit
        alone. With it, each stage is bounded by the smaller of the two, and
        the deadline takes precedence as it does in ``CommandSet.run``:
        ``ScenarioTimeout`` is raised when it has passed before a stage
        starts, when it stopped one, and when it passed while one ran, the
        ``AdapterUnavailable`` it supersedes kept as its ``__cause__``.
        ``vars`` and ``generation`` are unchanged whenever this raises."""
        deadline: float | None = None
        if deadline_left is not None:
            if deadline_left <= 0:
                raise ScenarioTimeout(
                    "scenario deadline passed before the runtime scripts were sourced"
                )
            deadline = time.monotonic() + deadline_left
        during = "the sourcing of the runtime scripts"
        temp = _temp_dir(self.deployment_root)
        start = dict(self._load_vars)
        start["COBCPY"] = self._load_inherited_cobcpy
        _contain_temp(start, temp)
        current = start
        output = ""
        states: dict[str, str] = {}
        # Each sourced script, what it printed and the environment it left.
        printed: list[tuple[Path, str, dict[str, str]]] = []

        userenv = Path(start.get("KIXSYS", "")) / "bin" / "userenv"
        states["userenv"] = self._script_state(userenv)
        if states["userenv"] == "sourced":
            current, stderr = self._source_userenv(userenv, start, deadline, during)
            self._refuse_escape(userenv, current, _GAP_USERENV, deadline, during)
            output += stderr
            printed.append((userenv, stderr, current))

        batchenv = Path(current.get("EBMHOME", "")) / "batchenv"
        states["batchenv"] = self._script_state(batchenv)
        if states["batchenv"] == "sourced":
            left = None if deadline is None else deadline - time.monotonic()
            if left is not None and left <= 0:
                raise ScenarioTimeout(f"scenario deadline passed before {batchenv} was sourced")
            captured = commands.capture(_BPE_ENV_ENTRY, self, start=current, deadline_left=left)
            if captured is None:
                states["batchenv"] = "unavailable"
            else:
                current, stderr = captured
                self._refuse_escape(batchenv, current, _GAP_BATCHENV, deadline, during)
                output += stderr
                printed.append((batchenv, stderr, current))

        if _expired(deadline):
            raise ScenarioTimeout(f"scenario deadline passed during {during}")
        # Before vars changes, so a failing write leaves this call without effect.
        for script, stderr, variables in printed:
            self._log_sourcing(script, stderr, variables)
        if current is start:
            self.vars = dict(self._load_vars)
            self._inherited_cobcpy = self._load_inherited_cobcpy
        else:
            merged = dict(current)
            self._inherited_cobcpy = merged.get("COBCPY", "")
            merged["COBCPY"] = _cobcpy(
                self._inherited_cobcpy, merged["MIGAPP_DIR"], merged.get("UNIKIX", "")
            )
            self.vars = _contain_temp(merged, temp)
            self.sourcing_output += output

        self.runtime_scripts = states
        self.generation += 1
        return dict(states)

    def _script_state(self, script: Path) -> str:
        """``sourced`` when ``script`` is a file inside the deployment root,
        else ``absent`` or ``outside-deployment-root``."""
        if not script.is_file():
            return "absent"
        if not _inside(script, self.deployment_root):
            return "outside-deployment-root"
        return "sourced"

    def _source_userenv(
        self, script: Path, start: dict[str, str], deadline: float | None, during: str
    ) -> tuple[dict[str, str], str]:
        """Source ``script`` with ``set -a`` in a bash started from ``start``,
        ``TMPDIR``, ``TMP``, ``TEMP`` and ``_JAVA_OPTIONS`` read-only
        (``_TEMP_HOLD``), and return what it exported (``_settings``) and its
        output, which goes to stderr. A failing ``.`` ends bash before the
        environment is printed. Bounded by ``SOURCE_TIMEOUT_S`` capped at ``deadline``; a
        failure or timeout is ``AdapterUnavailable`` with GAP-MIS-003, unless
        the deadline stopped it or has passed (``ScenarioTimeout``)."""
        body = (
            f'{_TEMP_HOLD}; set -a; . "$1" 1>&2 || exit $?; '
            f"printf '%s\\0' {shlex.quote(_SNAPSHOT_MARK)}; env -0"
        )
        argv = [_bash(), "--noprofile", "--norc", "-c", body, "carddemo-userenv", str(script)]
        spool = _log_dir(self.deployment_root)
        # The directory _default_cwd picks for the load-time environment; the
        # line above has checked the deployment root again.
        project = start.get("PROJECT_ROOT", "")
        cwd = project if os.path.isdir(project) else str(self.deployment_root)
        left = None if deadline is None else deadline - time.monotonic()
        if left is not None and left <= 0:
            raise ScenarioTimeout(f"scenario deadline passed before {script} was sourced")
        limit = float(SOURCE_TIMEOUT_S) if left is None else min(float(SOURCE_TIMEOUT_S), left)
        deadline_capped = left is not None and left <= SOURCE_TIMEOUT_S
        try:
            done = _run_process(argv, env=start, cwd=cwd, timeout=limit, spool_dir=str(spool))
        except OSError as exc:
            _unavailable(
                AdapterUnavailable(f"cannot source {script}: {exc.strerror or exc}", _GAP_USERENV),
                deadline,
                during,
            )
        if done.timed_out:
            if deadline_capped:
                raise ScenarioTimeout(
                    f"sourcing {script} was stopped at the scenario deadline after {limit:.0f}s"
                )
            _unavailable(
                AdapterUnavailable(f"sourcing {script} exceeded {SOURCE_TIMEOUT_S}s", _GAP_USERENV),
                deadline,
                during,
            )
        snapshots = _snapshots(done.stdout)
        if done.returncode != 0 or len(snapshots) != 2:
            _unavailable(
                AdapterUnavailable(f"sourcing {script} exited {done.returncode}", _GAP_USERENV),
                deadline,
                during,
            )
        return _settings(snapshots[1]), _text(done.stderr)

    def _refuse_escape(
        self,
        script: Path,
        snapshot: Mapping[str, str],
        gap: str,
        deadline: float | None,
        during: str,
    ) -> None:
        """``AdapterUnavailable`` with ``gap`` when the environment
        ``script`` left cannot be kept (``_runtime_escape``);
        ``ScenarioTimeout`` instead once the deadline has passed."""
        escape = self._runtime_escape(snapshot)
        if escape:
            _unavailable(AdapterUnavailable(f"{script} {escape}", gap), deadline, during)

    # ---------------------------------------------------------- records

    def versions(self, commands: CommandSet) -> dict[str, str]:
        """What every ``version_*`` entry reported, keyed by entry id, for
        ``environment.yaml`` (GAP-MIS-009). Each value is bounded diagnostic
        text, not the output verbatim: stdout and stderr combined, every JVM
        option but the owned temporary settings hidden (``redact``), then cut
        to ``LOG_LIMIT_BYTES``. Every value is non-empty text, as the evidence
        import requires. A failure is recorded as text and never raised:
        ``not defined in commands.yaml``, ``unavailable: <reason>``, or
        ``exit status <rc>`` followed by the output. A probe that exits 0
        and prints nothing, or only whitespace (``ls -1`` of an empty
        directory), is recorded as ``no output: <id> exited 0 and printed
        nothing`` (or ``only whitespace``), which names no version."""
        found: dict[str, str] = {}
        for entry_id in VERSION_ENTRIES:
            try:
                result = commands.run(entry_id, self, check=False)
            except KeyError:
                found[entry_id] = "not defined in commands.yaml"
                continue
            except (AdapterUnavailable, ScenarioTimeout, HarnessError) as exc:
                found[entry_id] = f"unavailable: {exc}"
                continue
            text = self.redact(result.stdout + result.stderr)
            if result.returncode != 0:
                text = f"exit status {result.returncode}\n{text}"
            elif not text.strip():
                printed = "only whitespace" if text else "nothing"
                text = f"no output: {entry_id} exited 0 and printed {printed}"
            found[entry_id] = _clip(text)[0]
        return found

    def as_record(self) -> dict[str, Any]:
        """The environment as ``environment.yaml`` records it, nonsecret facts
        only: the environment file and its sha256, the installation paths,
        the deployment paths (the temporary directory included) with
        ``EBMSYS``, ``COBCPY`` and ``MBMSUB``, then the ``JAVA_TOOL_OPTIONS``
        and ``_JAVA_OPTIONS`` that carry the temporary directory to every
        JVM, shown as the owned temporary settings with every other option
        ``REDACTED`` (``redact_jvm_options``; null when unset), the runtime
        script states, the generation, and ``sourcing_log``, the path of
        ``logs/sourcing.log`` once sourcing printed anything, else null.
        What the environment file, userenv and batchenv printed is kept out:
        it stays in that log, inside the work root. It is never a dump of
        ``vars``."""
        deployment: dict[str, str | None] = {
            name: self.vars.get(name) for name in _DEPLOYMENT_RECORD_VARS
        }
        for name in _JVM_ALWAYS_SET:
            value = self.vars.get(name)
            deployment[name] = None if value is None else redact_jvm_options(value)
        return {
            "env_file": str(self.env_file),
            "env_file_sha256": self.env_file_sha256,
            "installation": {name: self.vars.get(name) for name in INSTALLATION_VARS},
            "deployment": deployment,
            "runtime_scripts": dict(self.runtime_scripts),
            "generation": self.generation,
            "sourcing_log": str(self.log_dir / _SOURCING_LOG) if self.sourcing_output else None,
        }


def _source_env_file(
    env_file: Path, home: Path, temp: Path, root: Path, source: Mapping[str, str]
) -> tuple[dict[str, str], str]:
    """Source ``env_file`` with ``set -a`` in a bash that has only ``PATH``,
    the empty ``HOME``, ``LANG=C`` and the owned temporary directory
    ``temp`` (``_contain_temp``), with ``TMPDIR``, ``TMP``, ``TEMP`` and
    ``_JAVA_OPTIONS`` read-only (``_TEMP_HOLD``), so the file cannot change
    or unset them while it runs and its own temporary files land in
    ``temp``. Returns the exported
    environment and the file's own output, which goes to stderr so that
    stdout holds only ``env -0``.

    Sourcing fails when the file exits non-zero or ``.`` returns non-zero:
    after a syntax error, which stops bash reading the file, or a failing last
    command. bash then exits with that status before ``env -0``, the rule
    ``source_runtime_scripts`` applies to each script. A command that fails
    earlier in the file does not fail ``.``, as in the operator's own shell,
    so that file is accepted and the command's message stays in the returned
    output. An assignment, ``export`` or ``unset`` of a read-only variable is
    such a command: bash reports ``readonly variable`` and goes on. A
    failure, a timeout or an empty environment raises ``AdapterUnavailable``
    with GAP-MIS-009."""
    minimal = _contain_temp(
        {"PATH": source.get("PATH") or os.defpath, "HOME": str(home), "LANG": "C"}, temp
    )
    argv = [
        _bash(),
        "--noprofile",
        "--norc",
        "-c",
        f'{_TEMP_HOLD}; set -a; . "$1" 1>&2 || exit $?; env -0',
        "carddemo-env",
        str(env_file),
    ]
    try:
        done = _run_process(
            argv, env=minimal, cwd=str(home), timeout=SOURCE_TIMEOUT_S, spool_dir=str(root)
        )
    except OSError as exc:
        raise AdapterUnavailable(
            f"cannot source {env_file}: {exc.strerror or exc}", _GAP_INSTALLATION
        ) from None
    if done.timed_out:
        raise AdapterUnavailable(
            f"sourcing {env_file} exceeded {SOURCE_TIMEOUT_S}s", _GAP_INSTALLATION
        )
    if done.returncode != 0:
        raise AdapterUnavailable(
            f"sourcing {env_file} exited {done.returncode}", _GAP_INSTALLATION
        )
    captured = _snapshots(done.stdout)[0]
    if "PATH" not in captured:
        raise AdapterUnavailable(
            f"sourcing {env_file} produced no environment", _GAP_INSTALLATION
        )
    return captured, _text(done.stderr)


# --------------------------------------------------------------------------
# Commands
# --------------------------------------------------------------------------


def _placeholders(text: str | None, where: str) -> frozenset[str]:
    """The ``{name}`` placeholders of ``text``. ``commands.yaml`` allows no
    other braces, so ``${VAR}``, positional fields and unbalanced braces are
    errors. A conversion or format spec (``{file!r}``, ``{file:>8}``) is an
    error too: it would change a value after it was quoted or checked."""
    if text is None:
        return frozenset()
    names: set[str] = set()
    try:
        fields = list(string.Formatter().parse(text))
    except ValueError as exc:
        raise HarnessError(f"{where}: {exc}") from None
    for _literal, name, spec, conversion in fields:
        if name is None:
            continue
        if not _PLACEHOLDER_RE.fullmatch(name):
            raise HarnessError(
                f"{where}: {{{name}}} is not a placeholder; "
                "write $VAR for a variable, never ${VAR}"
            )
        if spec or conversion is not None:
            written = name + ("" if conversion is None else f"!{conversion}")
            written += f":{spec}" if spec else ""
            raise HarnessError(
                f"{where}: {{{written}}} carries a conversion or format spec, which would "
                f"change its value after it was quoted or checked; write {{{name}}}"
            )
        names.add(name)
    return frozenset(names)


# The quoting scan renders each placeholder as _MARK + name + _MARK, so a
# command must hold no NUL of its own.
_MARK = "\0"
_MARK_RE = re.compile("\0([^\0]*)\0")
_SHELL_NAME_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
_ASSIGNMENT_WORD_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(\[[^\]]*\])?\+?=")
# Reserved words and builtins after which the next word still names a command.
_COMMAND_PREFIX_WORDS = frozenset(
    ("!", "{", "if", "then", "elif", "else", "while", "until", "do", "time", "coproc", "exec",
     "command", "builtin")
)
_SPECIAL_PARAMETERS = frozenset("@*#?-$!0123456789")
_ARITHMETIC = "inside an arithmetic expression, which evaluates its value"
_UNFOLLOWED_QUOTE = "after a single quote in ${...} or arithmetic, which the loader cannot follow"
_UNFOLLOWED_CASE = "after a case statement inside $( ) or ( ), which the loader cannot follow"
_UNFOLLOWED_HEREDOC = "after a << here-document, which the loader cannot follow"
_QUOTING_ADVICE = (
    "shell quoting keeps a value one word of data only where its placeholder stands unquoted, "
    "as a whole word or part of one, e.g. kixfile close {file} or kixclt {member}.cl2"
)


@dataclass
class _PlainContext:
    """One plain context of a quoting scan: the top level, ``$(...)`` or
    ``(...)``, with the state of its current word."""

    nested: bool  # closed by ")"
    inherited: str | None  # why every placeholder here is misplaced, from the enclosing word
    command_pos: bool = True  # the next word names a command
    target: bool = False  # the next word is the target of a redirection
    cond: bool = False  # inside [[ ... ]]
    word: str = ""  # the current word: unquoted characters, "\x01" for anything else
    names: list[str] = field(default_factory=list)  # placeholders accepted in the word
    fd_word: bool = True  # the word holds only digits and placeholders
    tilde: bool = False  # the word is in a ~ tilde prefix
    bracket: bool = False  # the word is inside [...]
    braces: int = 0  # unclosed { of a brace expansion in the word


class _QuotingScan:
    """A lexical scan, after bash, of a command whose placeholders are
    rendered as marks.

    ``shlex.quote`` makes a value exactly one shell word of data only in a
    plain context (the top level, ``$(...)``, ``(...)``), and only where bash
    does not read the unquoted word specially. ``misplaced`` collects
    ``(name, context)`` for every placeholder anywhere else: inside quotes,
    backquotes, ``${...}``, arithmetic or a comment; right after ``$``,
    ``$NAME`` or a backslash; as a command name; in a ``[[ ]]`` conditional,
    a brace expansion, ``[...]``, a ``~`` prefix; or as a file descriptor
    number. Where the scan cannot follow bash, ``lost`` refuses every later
    placeholder: after a here-document operator ``<<`` or ``<<-`` (a ``<<<``
    here-string is a plain word), a ``case`` inside ``$( )`` or ``( )``, or a
    single quote inside ``${...}`` or arithmetic."""

    def __init__(self, text: str) -> None:
        self.text = text
        self.pos = 0
        self.misplaced: list[tuple[str, str]] = []
        self.lost: str | None = None

    def peek(self, offset: int = 1) -> str:
        index = self.pos + offset
        return self.text[index] if index < len(self.text) else ""

    def lose(self, reason: str) -> None:
        if self.lost is None:
            self.lost = reason

    def record(self, name: str, context: str | None) -> bool:
        """Note ``name`` as misplaced in ``context``, unless ``context`` is
        ``None`` and the scan is not lost; return whether it was accepted."""
        context = self.lost or context
        if context is None:
            return True
        self.misplaced.append((name, context))
        return False

    def mark(self, context: str | None) -> tuple[str, bool]:
        """Consume the mark at ``pos``; return its name and whether it was
        accepted."""
        start = self.pos + 1
        end = self.text.index(_MARK, start)
        self.pos = end + 1
        name = self.text[start:end]
        return name, self.record(name, context)

    def marks(self, start: int, end: int, context: str) -> None:
        for match in _MARK_RE.finditer(self.text, start, end):
            self.record(match.group(1), context)

    def escape(self) -> None:
        """Step over a backslash and what it escapes, never into a mark."""
        self.pos += 1 if self.peek() in ("", _MARK) else 2

    # ------------------------------------------------------- plain contexts

    def plain(self, nested: bool, inherited: str | None = None) -> None:
        """Scan a plain context up to its closing ``)`` (``nested``) or the
        end of the text."""
        ctx = _PlainContext(nested, inherited)
        text = self.text
        while self.pos < len(text):
            char = text[self.pos]
            if char == _MARK:
                self.placeholder(ctx)
            elif char == ")":
                self.end_word(ctx)
                self.pos += 1
                if nested:
                    break
                ctx.command_pos = True  # the end of a case pattern
            elif char in " \t":
                self.end_word(ctx)
                self.pos += 1
            elif char == "\n":
                self.end_word(ctx)
                ctx.command_pos, ctx.target = True, False
                self.pos += 1
            elif char == "&" and self.peek() == ">":
                self.end_word(ctx)
                self.pos += 1
                self.redirect(ctx)
            elif char in ";&|":
                self.end_word(ctx)
                ctx.command_pos, ctx.target = True, False
                self.pos += 1
            elif char in "<>":
                self.redirect(ctx)
            elif char == "(":
                self.group(ctx)
            elif char == "#" and not ctx.word:
                self.comment()
            elif char == "\\":
                self.plain_escape(ctx)
            elif char == "$":
                self.plain_dollar(ctx)
            elif char in "'\"`":
                self.pos += 1
                if char == "'":
                    self.single()
                elif char == '"':
                    self.double()
                else:
                    self.escaped("`", "inside backquotes `...`")
                ctx.word, ctx.fd_word = ctx.word + "\x01", False
            else:
                self.literal(ctx, char)

    @staticmethod
    def word_context(ctx: _PlainContext) -> str | None:
        """Why a placeholder at this point of the current word is misplaced,
        or ``None``."""
        if ctx.inherited:
            return ctx.inherited
        if ctx.cond:
            return "inside a [[ ]] conditional, which matches patterns and evaluates arithmetic"
        if ctx.braces:
            return "inside a {...} brace expansion, which splits its value at commas"
        if ctx.bracket:
            return "inside [...] in a word, a glob bracket or an arithmetic subscript"
        if ctx.tilde:
            return "in a ~ tilde prefix, where its value would name a user"
        return None

    def placeholder(self, ctx: _PlainContext) -> None:
        context = self.word_context(ctx)
        if context is None and not ctx.word and ctx.command_pos and not ctx.target:
            context = "at the start of a command, where its value would name the command"
        name, accepted = self.mark(context)
        if accepted:
            ctx.names.append(name)
        ctx.word += "\x01"

    def literal(self, ctx: _PlainContext, char: str) -> None:
        if char == "{":
            # A lone { is the reserved word; any other opens a brace expansion.
            if ctx.word or self.peek() not in ("", " ", "\t", "\n"):
                ctx.braces += 1
        elif char == "}":
            ctx.braces = max(ctx.braces - 1, 0)
        elif char in "[]":
            ctx.bracket = char == "["
        elif char == "~":
            ctx.tilde = ctx.tilde or not ctx.word or ctx.word[-1] in "=:"
        elif char == "/":
            ctx.tilde = False
        ctx.word += char
        ctx.fd_word = ctx.fd_word and char in "0123456789"
        self.pos += 1

    def end_word(self, ctx: _PlainContext) -> None:
        """Close the current word and note what it means for the next one."""
        word = ctx.word
        if word:
            if ctx.target:
                ctx.target = False
            elif word == "[[":
                ctx.cond, ctx.command_pos = True, False
            elif word == "]]":
                ctx.cond = False
            elif word not in _COMMAND_PREFIX_WORDS and not _ASSIGNMENT_WORD_RE.match(word):
                ctx.command_pos = False
            if word == "case" and ctx.nested:
                # Its patterns end in ")" too, which this scan would take for the close.
                self.lose(_UNFOLLOWED_CASE)
        ctx.word, ctx.names, ctx.fd_word = "", [], True
        ctx.tilde = ctx.bracket = False
        ctx.braces = 0

    def plain_escape(self, ctx: _PlainContext) -> None:
        following = self.peek()
        if following == "\n":
            self.pos += 2  # a line continuation joins the lines
            return
        if following == _MARK:
            self.pos += 1
            self.mark("right after a backslash, which would escape its opening quote")
        else:
            self.escape()
        ctx.word, ctx.fd_word = ctx.word + "\x01", False

    def plain_dollar(self, ctx: _PlainContext) -> None:
        following = self.peek()
        if following == "'":
            self.pos += 2
            self.escaped("'", "inside ANSI-C quotes $'...'")
        elif following == '"':
            self.pos += 2
            self.double()
        elif following == _MARK:
            self.pos += 1
            self.mark("right after $, where its quoted value would become $'...' or a variable")
        elif not self.expansion():
            variable = _SHELL_NAME_RE.match(self.text, self.pos + 1)
            if variable:
                self.pos = variable.end()
                if self.peek(0) == _MARK:
                    self.mark(
                        f"right after ${variable.group()}, where its value would extend the "
                        "variable name"
                    )
            else:
                self.pos += 2 if following and following in _SPECIAL_PARAMETERS else 1
        ctx.word, ctx.fd_word = ctx.word + "\x01", False

    def group(self, ctx: _PlainContext) -> None:
        """Scan ``((...))``, or ``(...)``: a subshell, array or pattern list."""
        inherited = self.word_context(ctx)
        self.end_word(ctx)
        if self.peek() == "(":
            self.pos += 2
            self.expression("(", "))", _ARITHMETIC)
        else:
            self.pos += 1
            self.plain(nested=True, inherited=inherited)
        ctx.command_pos = True

    def redirect(self, ctx: _PlainContext) -> None:
        """Scan the redirection operator at ``pos``."""
        if ctx.word and ctx.fd_word:
            # Digits right before < or > are the file descriptor it redirects.
            for name in ctx.names:
                self.record(
                    name, "right before a < or > redirection, where a number names a descriptor"
                )
            ctx.word = ""
        self.end_word(ctx)
        text = self.text
        if text.startswith("<<<", self.pos):
            self.pos += 3
        elif text.startswith("<<", self.pos):
            # bash ends a here-document body by rules this scan does not follow (line
            # joining, every byte of the delimiter kept), so it refuses everything after <<.
            self.pos += 3 if text.startswith("<<-", self.pos) else 2
            self.lose(_UNFOLLOWED_HEREDOC)
        elif self.peek() == "(":
            self.pos += 2
            self.plain(nested=True)  # a process substitution, a word of its own
            ctx.word, ctx.fd_word = "\x01", False
            return
        else:
            self.pos += 1
            if self.peek(0) in ("&", ">", "|"):
                self.pos += 1
        ctx.target = True

    def comment(self) -> None:
        end = self.text.find("\n", self.pos)
        end = len(self.text) if end < 0 else end
        self.marks(self.pos, end, "inside a # comment, which a newline in its value would end")
        self.pos = end

    # ------------------------------------------------- every other context

    def expansion(self) -> bool:
        """At a ``$``: scan the ``$((...))``, ``$(...)``, ``$[...]`` or
        ``${...}`` that starts here and return ``True``; return ``False``,
        consuming nothing, for any other ``$``."""
        text, pos = self.text, self.pos
        if text.startswith("$((", pos):
            self.pos += 3
            self.expression("(", "))", _ARITHMETIC)
        elif text.startswith("$(", pos):
            self.pos += 2
            self.plain(nested=True)
        elif text.startswith("$[", pos):
            self.pos += 2
            self.expression("[", "]", _ARITHMETIC)
        elif text.startswith("${", pos):
            self.pos += 2
            self.expression("{", "}", "inside a ${...} expansion")
        else:
            return False
        return True

    def dollar(self) -> None:
        """At a ``$`` outside a plain context: scan the expansion it starts,
        or step over it and the special parameter it names, so that ``$$(``
        is never taken for ``$(``."""
        if not self.expansion():
            following = self.peek()
            self.pos += 2 if following and following in _SPECIAL_PARAMETERS else 1

    def single(self) -> None:
        end = self.text.find("'", self.pos)
        end = len(self.text) if end < 0 else end
        self.marks(self.pos, end, "inside single quotes '...'")
        self.pos = min(end + 1, len(self.text))

    def double(self, context: str = 'inside double quotes "..."') -> None:
        text = self.text
        while self.pos < len(text):
            char = text[self.pos]
            if char == '"':
                self.pos += 1
                return
            if char == _MARK:
                self.mark(context)
            elif char == "\\":
                self.escape()
            elif char == "`":
                self.pos += 1
                self.escaped("`", "inside backquotes `...`")
            elif char == "$":
                self.dollar()
            else:
                self.pos += 1

    def escaped(self, closer: str, context: str) -> None:
        """Scan ANSI-C quotes or backquotes up to the first unescaped
        ``closer``; bash ends backquotes there even inside quotes."""
        text = self.text
        while self.pos < len(text):
            char = text[self.pos]
            if char == closer:
                self.pos += 1
                return
            if char == _MARK:
                self.mark(context)
            elif char == "\\":
                self.escape()
            else:
                self.pos += 1

    def expression(self, opener: str, closer: str, context: str) -> None:
        """Scan ``${...}``, ``$((...))``, ``((...))`` or ``$[...]`` up to
        ``closer``; bare ``opener`` characters nest, as in bash. A single
        quote, which bash may or may not take as quoting here, or a lone
        ``)`` where ``))`` should close, loses the scan."""
        text = self.text
        shut = closer[0]
        depth = 0
        while self.pos < len(text):
            char = text[self.pos]
            if char == _MARK:
                self.mark(context)
                continue
            if char == shut and not depth:
                if text.startswith(closer, self.pos):
                    self.pos += len(closer)
                else:
                    self.lose("after an arithmetic expression the loader cannot follow")
                    self.pos += 1
                return
            if char == opener:
                depth += 1
            elif char == shut:
                depth -= 1
            elif char == "'":
                self.lose(_UNFOLLOWED_QUOTE)
            elif char == "\\":
                self.escape()
                continue
            elif char == '"':
                self.pos += 1
                self.double(context)
                continue
            elif char == "`":
                self.pos += 1
                self.escaped("`", context)
                continue
            elif char == "$":
                self.dollar()
                continue
            self.pos += 1


def _command_placeholders(command: str | None, where: str) -> frozenset[str]:
    """The placeholders of ``command``, each of which must stand where
    ``shlex.quote`` keeps its value exactly one shell word of data (see
    ``_QuotingScan``). Every misplaced placeholder is reported in one
    ``HarnessError``. ``cwd`` is never held to this: no shell parses it."""
    names = _placeholders(command, where)
    if command is None:
        return names
    if _MARK in command:
        raise HarnessError(f"{where} holds a NUL character")
    scan = _QuotingScan(command.format_map({name: f"{_MARK}{name}{_MARK}" for name in names}))
    try:
        scan.plain(nested=False)
    except RecursionError:
        too_deep = "in a command nested too deeply to scan"
        scan.misplaced = [(name, too_deep) for name in sorted(names)]
    if scan.misplaced:
        found = ", ".join(
            f"{{{name}}} stands {context}" for name, context in dict.fromkeys(scan.misplaced)
        )
        raise HarnessError(f"{where}: {found}; {_QUOTING_ADVICE}")
    return names


@dataclass(frozen=True)
class CommandEntry:
    """One entry of ``commands.yaml``. ``parse`` and ``template`` are read by
    their callers (``bpe.py``, ``adapter.py``) and never by this module."""

    id: str
    action: str
    command: str | None
    probe: str
    timeout_s: int
    gap: str
    status: str
    source: str
    notes: str
    cwd: str | None = None
    parse: Mapping[str, str] = field(default_factory=lambda: MappingProxyType({}))
    template: Mapping[str, str] = field(default_factory=lambda: MappingProxyType({}))

    @property
    def confirmed(self) -> bool:
        return self.status == "confirmed"

    @property
    def placeholders(self) -> frozenset[str]:
        """The caller values the command and its ``cwd`` need. A command
        placeholder that does not stand where shell quoting holds raises
        ``HarnessError``, so an entry built without ``CommandSet.load`` is
        refused too, before anything runs."""
        return _command_placeholders(self.command, f"{self.id}.command") | _placeholders(
            self.cwd, f"{self.id}.cwd"
        )


@dataclass(frozen=True)
class CommandResult:
    """What one command produced: its exit status and its output, nothing
    inferred from them."""

    entry_id: str
    returncode: int
    stdout: str
    stderr: str

    @property
    def ok(self) -> bool:
        return self.returncode == 0


@dataclass(frozen=True)
class _Probe:
    generation: int
    ok: bool
    reason: str


@dataclass(frozen=True)
class _EnvironmentView:
    """The environment ``CommandSet.capture`` probes and runs an entry in:
    ``vars`` of its own, under the deployment root and the generation of the
    environment that will hold what it captures."""

    vars: dict[str, str]
    deployment_root: Path | None
    generation: int


def _entry(entry_id: Any, raw: Any, where: str) -> CommandEntry:
    """Validate one ``commands.yaml`` entry; every problem is reported at once."""
    if not isinstance(entry_id, str) or not _ENTRY_ID_RE.fullmatch(entry_id):
        raise HarnessError(f"{where}: entry id {entry_id!r} is not lower_snake_case")
    where = f"{where}: {entry_id}"
    if not isinstance(raw, Mapping):
        raise HarnessError(f"{where} must be a mapping")
    problems: list[str] = []
    unknown = sorted(set(raw) - set(_REQUIRED_KEYS) - set(_OPTIONAL_KEYS))
    if unknown:
        problems.append(f"unknown keys {', '.join(map(str, unknown))}")
    missing = [key for key in _REQUIRED_KEYS if key not in raw]
    if missing:
        problems.append(f"missing keys {', '.join(missing)}")

    def text(key: str, optional: bool = False) -> str | None:
        value = raw.get(key)
        if value is None and optional:
            return None
        if not isinstance(value, str) or not value.strip():
            if key in raw or not optional:
                problems.append(f"{key} must be a non-empty string{' or null' if optional else ''}")
            return None
        return value

    def table(key: str) -> Mapping[str, str]:
        value = raw.get(key)
        if value is None:
            return MappingProxyType({})
        if not isinstance(value, Mapping) or not all(
            isinstance(name, str) and isinstance(item, str) for name, item in value.items()
        ):
            problems.append(f"{key} must map names to strings")
            return MappingProxyType({})
        return MappingProxyType(dict(value))

    command = text("command", optional=True)
    probe = text("probe")
    timeout_s = raw.get("timeout_s")
    if isinstance(timeout_s, bool) or not isinstance(timeout_s, int) or timeout_s <= 0:
        problems.append(f"timeout_s must be a positive integer, got {timeout_s!r}")
    gap = raw.get("gap")
    if not isinstance(gap, str) or not GAP_ID_RE.fullmatch(gap):
        problems.append(f"gap must be a gap id like GAP-MIS-003, got {gap!r}")
    status = raw.get("status")
    if status not in _COMMAND_STATUSES:
        problems.append(f"status must be one of {', '.join(_COMMAND_STATUSES)}, got {status!r}")
    action, source, notes = text("action"), text("source"), text("notes")
    cwd = text("cwd", optional=True)
    parse, template = table("parse"), table("template")
    for key, value, check in (
        ("command", command, _command_placeholders),
        ("cwd", cwd, _placeholders),
    ):
        try:
            check(value, key)
        except HarnessError as exc:
            problems.append(str(exc))
    if problems:
        raise HarnessError(f"{where}: {'; '.join(problems)}")
    return CommandEntry(
        id=entry_id,
        action=str(action),
        command=command,
        probe=str(probe),
        timeout_s=int(timeout_s),
        gap=str(gap),
        status=str(status),
        source=str(source),
        notes=str(notes),
        cwd=cwd,
        parse=parse,
        template=template,
    )


def _values(entry: CommandEntry, values: Mapping[str, Any]) -> dict[str, str]:
    """The caller's placeholder values as text. A placeholder without a value
    is a programming error and raises ``HarnessError``; values the entry does
    not use are allowed."""
    missing = sorted(entry.placeholders - set(values))
    if missing:
        raise HarnessError(f"{entry.id}: no value for {', '.join('{' + n + '}' for n in missing)}")
    texts: dict[str, str] = {}
    for name, value in values.items():
        if isinstance(value, bool) or not isinstance(value, (str, int)):
            raise TypeError(f"{entry.id}: value of {{{name}}} must be str or int, got {value!r}")
        text = str(value)
        if "\0" in text:
            raise HarnessError(f"{entry.id}: value of {{{name}}} holds a NUL character")
        texts[name] = text
    return texts


def _render_command(command: str, values: Mapping[str, str]) -> str:
    """``command`` with every value shell-quoted; ``$VAR`` is left for bash.

    ``shlex.quote`` makes a value one word of data only where its placeholder
    stands unquoted in plain shell context, so ``command`` must be one that
    ``_command_placeholders`` accepted, as ``CommandEntry.placeholders`` has
    checked before every run. The value is still only data to the command
    it reaches: a command that re-parses its arguments (``eval``, ``sh -c``)
    would run it."""
    return command.format_map({name: shlex.quote(value) for name, value in values.items()})


def _expand_cwd(entry: CommandEntry, cwd: str, values: Mapping[str, str], env: Any) -> str:
    """The entry's working directory ``cwd``, ``$VAR`` expanded from
    ``env.vars``.

    No shell parses a working directory, so a value is substituted as given
    rather than shell-quoted (quotes would become part of the path) and must
    be one plain path component. A variable that is unset, or a directory
    that does not exist, makes the command unavailable."""
    for name in _placeholders(cwd, f"{entry.id}.cwd"):
        value = values[name]
        if not value or value in (".", "..") or "/" in value or "$" in value:
            raise HarnessError(
                f"{entry.id}: value {value!r} of {{{name}}} is not one path component"
            )
    rendered = cwd.format_map(values)

    def variable(match: re.Match[str]) -> str:
        value = env.vars.get(match.group(1))
        if not value:
            raise AdapterUnavailable(
                f"{entry.id} unavailable: working-directory variable ${match.group(1)} is not set",
                entry.gap,
            )
        return value

    expanded = _SHELL_VAR_RE.sub(variable, rendered)
    if not os.path.isabs(expanded):
        expanded = os.path.join(_default_cwd(env), expanded)
    if not os.path.isdir(expanded):
        raise AdapterUnavailable(
            f"{entry.id} unavailable: working directory {expanded} does not exist", entry.gap
        )
    return expanded


def _default_cwd(env: Any) -> str:
    """``$PROJECT_ROOT`` once it exists, else the deployment root, so no
    command runs in the caller's directory (the repository, under pytest)."""
    project = env.vars.get("PROJECT_ROOT")
    if project and os.path.isdir(project):
        return str(project)
    root = getattr(env, "deployment_root", None)
    if root is not None and os.path.isdir(root):
        return str(root)
    raise HarnessError("the UniKix environment has neither PROJECT_ROOT nor a deployment root")


def _spool_dir(env: Any) -> Path:
    """Where command output is spooled: ``<deployment root>/logs``, checked
    again with the root (``_log_dir``), or the default working directory for
    an environment that names no deployment root."""
    root = getattr(env, "deployment_root", None)
    if root is None:
        return Path(_default_cwd(env))
    return _log_dir(root)


def _process_env(env: Any) -> dict[str, str]:
    """The environment a process of ``env`` starts with: ``env.vars`` with
    every temporary-file setting pointed again at ``<deployment root>/tmp``
    (``_contain_temp``), which is checked again first (``_temp_dir``), so no
    change to ``vars`` lets a command write temporary files elsewhere. An
    environment that names no deployment root passes ``vars`` unchanged."""
    variables = dict(env.vars)
    root = getattr(env, "deployment_root", None)
    if root is not None:
        _contain_temp(variables, _temp_dir(root))
    return variables


def _shell(script: str, env: Any, *args: str) -> list[str]:
    """``bash --noprofile --norc -c <script> [args]`` for a process of
    ``env``. When ``env`` names a deployment root, so that ``_process_env``
    contains its temporary files, ``_TEMP_HOLD`` runs first, on a line of its
    own: ``script``, and every script it sources in that bash, then cannot
    change ``TMPDIR``, ``TMP``, ``TEMP`` or ``_JAVA_OPTIONS``. An assignment,
    ``export`` or ``unset`` of one fails with bash's ``readonly variable``
    (a failed plain assignment also skips the rest of its line) and the
    owned value stays. Only the shell is held: a program it starts gets the
    owned values and may change them for its own children."""
    if getattr(env, "deployment_root", None) is not None:
        script = f"{_TEMP_HOLD}\n{script}"
    return [_bash(), "--noprofile", "--norc", "-c", script, *args]


def _submission_refusal(entry: CommandEntry, env: Any) -> str:
    """Why ``entry`` may not run in ``env`` from its BPE submission
    directory now (``_mbmsub_refusal``), or ``""``. Only an entry whose
    command or ``cwd`` uses ``$MBMSUB`` is checked."""
    if not any(
        match.group(1) == "MBMSUB"
        for text in (entry.command, entry.cwd)
        if text
        for match in _SHELL_VAR_RE.finditer(text)
    ):
        return ""
    return _mbmsub_refusal(env.vars.get("MBMSUB"), getattr(env, "deployment_root", None))


def _expired(deadline: float | None) -> bool:
    """Whether the absolute ``time.monotonic()`` ``deadline`` has passed."""
    return deadline is not None and time.monotonic() >= deadline


def _unavailable(error: AdapterUnavailable, deadline: float | None, entry_id: str) -> NoReturn:
    """Raise ``error``, a missing capability, unless the scenario
    ``deadline`` has passed meanwhile. Then the outcome is
    ``ScenarioTimeout``, and ``error`` remains only as its cause and in the
    command log."""
    if _expired(deadline):
        raise ScenarioTimeout(
            f"scenario deadline passed during {entry_id}; superseded: {error}"
        ) from error
    raise error from None


class CommandSet:
    """The entries of ``commands.yaml`` and the single place that runs them.

    ``env`` arguments are a ``UniKixEnvironment`` or an object with the same
    ``vars`` (and optionally ``deployment_root`` and ``generation``). The log
    holds every probe and every run, and every refused run too, in order.
    It is kept in memory and appended to ``<deployment root>/logs/commands.log``
    as YAML documents, each entry's output with every JVM option but the
    owned temporary settings hidden (``redact``). ``mark`` and ``log_since``
    select the entries of one scenario, and ``render_evidence_log`` renders
    them for its evidence without their elapsed times and without what a
    sourced script printed. Calls may come from several threads."""

    def __init__(self, entries: Mapping[str, CommandEntry], path: Path) -> None:
        self.path = path
        self._entries = dict(entries)
        self._probes: dict[str, _Probe] = {}
        # The latest submission-directory refusal of each entry, which
        # probe_reason and available report; every call checks again.
        self._refusals: dict[str, str] = {}
        self._log: list[dict[str, Any]] = []
        self._lock = threading.RLock()

    @classmethod
    def load(cls, path: str | os.PathLike[str] | None = None) -> CommandSet:
        """Read ``commands.yaml`` (``schema_version: 1``, ``entries`` mapping
        id -> entry). Any schema problem raises ``HarnessError``, which names
        every entry that has one."""
        file = Path(path) if path is not None else COMMANDS_FILE
        data = load_yaml(file)
        if not isinstance(data, Mapping) or data.get("schema_version") != 1:
            raise HarnessError(f"{file}: expected a mapping with schema_version: 1")
        unknown = sorted(set(data) - {"schema_version", "entries"})
        if unknown:
            raise HarnessError(f"{file}: unknown top-level keys {', '.join(map(str, unknown))}")
        raw_entries = data.get("entries")
        if not isinstance(raw_entries, Mapping) or not raw_entries:
            raise HarnessError(f"{file}: entries must be a non-empty mapping of id -> entry")
        entries: dict[str, CommandEntry] = {}
        problems: list[str] = []
        for entry_id, raw in raw_entries.items():
            try:
                entries[str(entry_id)] = _entry(entry_id, raw, str(file))
            except HarnessError as exc:
                problems.append(str(exc))
        if problems:
            raise HarnessError("\n".join(problems))
        return cls(entries, file)

    # -------------------------------------------------------------- entries

    def entry(self, entry_id: str) -> CommandEntry:
        """The entry ``entry_id``; ``KeyError`` for an unknown id."""
        return self._entries[entry_id]

    __getitem__ = entry

    def __contains__(self, entry_id: object) -> bool:
        return entry_id in self._entries

    def __iter__(self) -> Iterator[str]:
        return iter(sorted(self._entries))

    def __len__(self) -> int:
        return len(self._entries)

    @property
    def ids(self) -> tuple[str, ...]:
        return tuple(sorted(self._entries))

    def confirmed(self, entry_id: str) -> bool:
        """Whether an operator has confirmed the entry (``status: confirmed``)."""
        return self._entries[entry_id].confirmed

    # --------------------------------------------------------------- probes

    def probe_all(self, env: Any, *, refresh: bool = False) -> dict[str, bool]:
        """Probe every entry once for ``env``'s current generation and return
        ``{id: available}``. Results are cached; ``refresh`` probes again."""
        return {entry_id: self.probe(entry_id, env, refresh=refresh) for entry_id in self.ids}

    def probe(self, entry_id: str, env: Any, *, refresh: bool = False) -> bool:
        """Whether ``entry_id`` is available, probing it unless a result for
        ``env``'s current generation is cached. An entry that uses
        ``$MBMSUB`` is unavailable while ``MBMSUB`` does not resolve inside
        the deployment root, which is checked on every call."""
        return self._probe(self._entries[entry_id], env, refresh=refresh, deadline=None)[0]

    def available(
        self, entry_id: str, env: Any = None, *, deadline_left: float | None = None
    ) -> bool:
        """The cached availability of ``entry_id``. With ``env``, an entry not
        yet probed for its current generation is probed first, and the
        ``$MBMSUB`` check of ``probe`` is made. Without it, the latest result
        is returned, or ``False`` if the entry has never been probed, and
        nothing runs.

        ``deadline_left`` is, as for ``run``, the seconds left before the
        scenario deadline when the caller has one. The probe is then capped
        at that time, and ``ScenarioTimeout`` is raised when the deadline has
        passed before, while or after it runs. A probe that fails within the
        deadline makes the entry unavailable: ``False``. Without
        ``deadline_left`` the probe is bounded by its own limit only."""
        entry = self._entries[entry_id]
        if env is not None:
            deadline: float | None = None
            if deadline_left is not None:
                if deadline_left <= 0:
                    raise ScenarioTimeout(f"scenario deadline passed before probing {entry_id}")
                deadline = time.monotonic() + deadline_left
            return self._probe(entry, env, refresh=False, deadline=deadline)[0]
        with self._lock:
            cached = self._probes.get(entry_id)
            refused = entry_id in self._refusals
        return entry.command is not None and not refused and cached is not None and cached.ok

    def probe_reason(self, entry_id: str) -> str | None:
        """Why the latest probe or ``$MBMSUB`` check of ``entry_id`` failed,
        ``""`` after a success, ``None`` before any probe."""
        entry = self._entries[entry_id]
        if entry.command is None:
            return "no command is known (command: null)"
        with self._lock:
            refusal = self._refusals.get(entry_id)
            cached = self._probes.get(entry_id)
        if refusal:
            return refusal
        return None if cached is None else cached.reason

    def _probe(
        self, entry: CommandEntry, env: Any, *, refresh: bool, deadline: float | None
    ) -> tuple[bool, str]:
        """Whether ``entry`` is available for ``env``, and why not.

        An entry that uses ``$MBMSUB`` is first held to the deployment root
        (``_submission_refusal``). That check is made on every call and never
        cached, so the cache holds only what the probe command found. The
        probe's verdict is cached for ``env``'s generation. When the probe
        ran under a scenario ``deadline`` that has passed by the time it is
        logged and cached, ``ScenarioTimeout`` is raised whatever it found."""
        generation = getattr(env, "generation", 0)
        refusal = "" if entry.command is None else _submission_refusal(entry, env)
        with self._lock:
            if refusal:
                self._refusals[entry.id] = refusal
            else:
                self._refusals.pop(entry.id, None)
            cached = self._probes.get(entry.id)
        if refusal:
            return False, refusal
        if cached is not None and cached.generation == generation and not refresh:
            return cached.ok, cached.reason
        if entry.command is None:
            # Nothing to probe: command null is unavailable by definition.
            ok, reason = False, "no command is known (command: null)"
        else:
            own_limit = float(min(entry.timeout_s, PROBE_TIMEOUT_CAP_S))
            limit = own_limit
            if deadline is not None:
                left = deadline - time.monotonic()
                if left <= 0:
                    raise ScenarioTimeout(f"scenario deadline passed before probing {entry.id}")
                limit = min(own_limit, left)
            ok, reason = self._execute_probe(entry, env, limit, limit < own_limit, deadline)
        with self._lock:
            self._probes[entry.id] = _Probe(generation, ok, reason)
        if _expired(deadline):
            if ok:
                raise ScenarioTimeout(f"scenario deadline passed while probing {entry.id}")
            _unavailable(
                AdapterUnavailable(f"{entry.id} unavailable: {reason}", entry.gap),
                deadline,
                entry.id,
            )
        return ok, reason

    def _execute_probe(
        self, entry: CommandEntry, env: Any, limit: float, capped: bool, deadline: float | None
    ) -> tuple[bool, str]:
        """Run the probe of ``entry`` for at most ``limit`` seconds and log it.
        ``capped`` means ``limit`` is the time left before ``deadline``. A
        probe the deadline stopped has no verdict to cache, so it raises
        ``ScenarioTimeout``; one that exceeded its own limit has failed."""
        cwd = _default_cwd(env)
        argv = _shell(entry.probe, env)
        try:
            done = _run_process(
                argv, env=_process_env(env), cwd=cwd, timeout=limit, spool_dir=str(_spool_dir(env))
            )
        except OSError as exc:
            reason = f"probe could not start: {exc.strerror or exc}"
            self._record(
                env, "probe", entry.id, entry.probe, cwd, None, "not-started", 0.0, reason=reason
            )
            return False, reason
        if done.timed_out:
            # Killing a group that ignores SIGTERM can outlast the deadline too.
            outcome = "deadline" if capped or _expired(deadline) else "timeout"
        else:
            outcome = "completed"
        self._record(
            env, "probe", entry.id, entry.probe, cwd, done.returncode, outcome, done.elapsed_s,
            stdout=_text(done.stdout), stderr=_text(done.stderr),
        )
        if done.timed_out:
            if capped:
                raise ScenarioTimeout(f"probe of {entry.id} was stopped at the scenario deadline")
            return False, f"probe `{entry.probe}` exceeded {limit:g}s"
        if done.returncode != 0:
            return False, f"probe `{entry.probe}` exited {done.returncode}"
        return True, ""

    # ------------------------------------------------------------------ run

    def run(
        self,
        entry_id: str,
        env: Any,
        *,
        deadline_left: float | None = None,
        check: bool = True,
        **values: str | int,
    ) -> CommandResult:
        """Run the entry ``entry_id`` with ``env`` and the placeholder
        ``values``. Each is shell-quoted into the command as exactly one word
        of data, which holds because a placeholder stands only unquoted in
        plain shell context (``_command_placeholders``).

        The command runs as ``bash --noprofile --norc -c <command>`` in the
        entry's ``cwd`` (``$VAR`` expanded), or else in ``$PROJECT_ROOT``,
        with ``env.vars`` and, when ``env`` names a deployment root, every
        temporary-file setting pointed again at its ``tmp/``
        (``_process_env``) and held read-only in that bash (``_shell``), so
        neither the command nor a script it sources can move them; the log
        holds the command as rendered, without that hold. The probe runs the
        same way. It is bounded by
        ``timeout_s``, capped at ``deadline_left``, the seconds left before
        the scenario deadline when the caller has one.

        * ``command: null``, a failed probe, a ``cwd`` whose variable is
          unset or whose directory is missing, or, for an entry whose command
          or ``cwd`` uses ``$MBMSUB``, an ``MBMSUB`` that is unset, relative
          or does not resolve (symlinks followed) inside the deployment root
          raise ``AdapterUnavailable("<id> unavailable: <reason>", gap)`` and
          run nothing. ``bpe_submit`` therefore gives GAP-MIS-006, and the
          dataset and catalog entries that reuse its command GAP-MIS-007.
        * At the timeout the process group is killed. If the cap was the
          scenario deadline, ``ScenarioTimeout`` is raised. Otherwise
          ``AdapterUnavailable("<id> exceeded <timeout_s>s", gap)`` is.
        * A non-zero exit raises ``AdapterUnavailable("<id> exited <rc>",
          gap)`` when ``check`` is true and is returned otherwise.

        The scenario deadline takes precedence. Every outcome is logged
        first; if the deadline has passed by then, because the probe, the
        kill of a process group that ignores SIGTERM, or the log itself took
        that long, ``ScenarioTimeout`` is raised instead of returning the
        result or raising the ``AdapterUnavailable``, which is kept as its
        ``__cause__`` and in the log.

        An unknown ``entry_id`` raises ``KeyError``, and a placeholder without
        a value raises ``HarnessError``: both are programming errors. A
        command placeholder that stands anywhere else than unquoted in plain
        shell context also raises ``HarnessError``, before anything runs."""
        entry = self._entries[entry_id]
        texts = _values(entry, values)
        deadline: float | None = None
        if deadline_left is not None:
            if deadline_left <= 0:
                raise ScenarioTimeout(f"scenario deadline passed before {entry_id} started")
            deadline = time.monotonic() + deadline_left

        available, reason = self._probe(entry, env, refresh=False, deadline=deadline)
        if entry.command is None or not available:
            reason = reason or "probe failed"
            self._record(
                env, "run", entry_id, entry.command, None, None, "refused", 0.0, reason=reason
            )
            _unavailable(
                AdapterUnavailable(f"{entry_id} unavailable: {reason}", entry.gap),
                deadline,
                entry_id,
            )

        command = _render_command(entry.command, texts)
        if entry.cwd is None:
            cwd = _default_cwd(env)
        else:
            try:
                cwd = _expand_cwd(entry, entry.cwd, texts, env)
            except AdapterUnavailable as exc:
                self._record(
                    env, "run", entry_id, command, None, None, "refused", 0.0,
                    reason=exc.reason.removeprefix(f"{entry_id} unavailable: "),
                )
                _unavailable(exc, deadline, entry_id)
        left = None if deadline is None else deadline - time.monotonic()
        if left is not None and left <= 0:
            raise ScenarioTimeout(f"scenario deadline passed before {entry_id} started")
        limit = float(entry.timeout_s) if left is None else min(float(entry.timeout_s), left)
        deadline_capped = left is not None and left <= entry.timeout_s

        argv = _shell(command, env)
        try:
            done = _run_process(
                argv, env=_process_env(env), cwd=cwd, timeout=limit, spool_dir=str(_spool_dir(env))
            )
        except OSError as exc:
            reason = f"could not start: {exc.strerror or exc}"
            self._record(
                env, "run", entry_id, command, cwd, None, "not-started", 0.0, reason=reason
            )
            _unavailable(AdapterUnavailable(f"{entry_id} {reason}", entry.gap), deadline, entry_id)

        if done.timed_out:
            # Killing a group that ignores SIGTERM can outlast the deadline too.
            outcome = "deadline" if deadline_capped or _expired(deadline) else "timeout"
        else:
            outcome = "completed"
        stdout, stderr = _text(done.stdout), _text(done.stderr)
        self._record(
            env, "run", entry_id, command, cwd, done.returncode, outcome, done.elapsed_s,
            stdout=stdout, stderr=stderr,
        )
        if done.timed_out:
            if deadline_capped:
                raise ScenarioTimeout(
                    f"{entry_id} was stopped at the scenario deadline after {limit:.0f}s"
                )
            _unavailable(
                AdapterUnavailable(f"{entry_id} exceeded {entry.timeout_s}s", entry.gap),
                deadline,
                entry_id,
            )
        # A process that was not timed out has been waited for, so it has a status.
        result = CommandResult(entry_id, int(done.returncode or 0), stdout, stderr)
        if check and not result.ok:
            _unavailable(
                AdapterUnavailable(f"{entry_id} exited {result.returncode}", entry.gap),
                deadline,
                entry_id,
            )
        if _expired(deadline):
            raise ScenarioTimeout(
                f"scenario deadline passed during {entry_id}; superseded: its exit status "
                f"{result.returncode}"
            )
        return result

    # -------------------------------------------------------------- capture

    def capture(
        self,
        entry_id: str,
        env: Any,
        *,
        start: Mapping[str, str],
        deadline_left: float | None = None,
    ) -> tuple[dict[str, str], str] | None:
        """Run the entry ``entry_id`` as a script sourced into ``start`` and
        return ``(environment, stderr)``: what the shell has exported when
        the command ends, and the command's output. ``None`` when the entry
        is unavailable: ``command: null``, or a probe that failed.

        ``env`` supplies the deployment root and the generation; ``start``
        is the environment of the next generation, which the entry is probed
        in afresh and runs with. The command runs as ``bash --noprofile
        --norc -c 'set -a; { <command>\\n} 1>&2 || exit $?; env -0'`` in the
        entry's ``cwd``, or else in ``$PROJECT_ROOT``, with the owned
        temporary directory held read-only as for ``run``, so the script it
        sources cannot move ``TMPDIR``, ``TMP``, ``TEMP`` or
        ``_JAVA_OPTIONS``, bounded by ``timeout_s`` capped
        at ``deadline_left``. Its output goes to stderr, which is returned
        as printed and logged in an entry marked ``sourcing`` (``_record``),
        which the evidence copy withholds (``render_evidence_log``); stdout
        is the exported environment and is never logged.
        The environment returned has the shell's own variables, ``BASH_ENV``
        and ``ENV`` removed.

        Outcomes follow ``run`` with ``check`` true: a refusal is logged; the
        own timeout raises ``AdapterUnavailable("<id> exceeded
        <timeout_s>s", gap)``, a non-zero exit, or a command that ends the
        shell before the environment is printed,
        ``AdapterUnavailable("<id> exited <rc>", gap)``; the scenario
        deadline takes precedence as it does there. An entry with
        placeholders raises ``HarnessError``: no caller value is passed."""
        entry = self._entries[entry_id]
        if entry.placeholders:
            names = ", ".join("{" + name + "}" for name in sorted(entry.placeholders))
            raise HarnessError(f"{entry_id}: an entry captured as a script takes no {names}")
        deadline: float | None = None
        if deadline_left is not None:
            if deadline_left <= 0:
                raise ScenarioTimeout(f"scenario deadline passed before {entry_id} started")
            deadline = time.monotonic() + deadline_left
        view = _EnvironmentView(
            dict(start), getattr(env, "deployment_root", None), getattr(env, "generation", 0) + 1
        )

        available, reason = self._probe(entry, view, refresh=True, deadline=deadline)
        if entry.command is None or not available:
            reason = reason or "probe failed"
            self._record(
                view, "run", entry_id, entry.command, None, None, "refused", 0.0, reason=reason
            )
            if _expired(deadline):
                raise ScenarioTimeout(
                    f"scenario deadline passed during {entry_id}; superseded: {entry_id} "
                    f"unavailable: {reason}"
                )
            return None

        if entry.cwd is None:
            cwd = _default_cwd(view)
        else:
            try:
                cwd = _expand_cwd(entry, entry.cwd, {}, view)
            except AdapterUnavailable as exc:
                self._record(
                    view, "run", entry_id, entry.command, None, None, "refused", 0.0,
                    reason=exc.reason.removeprefix(f"{entry_id} unavailable: "),
                )
                _unavailable(exc, deadline, entry_id)
        left = None if deadline is None else deadline - time.monotonic()
        if left is not None and left <= 0:
            raise ScenarioTimeout(f"scenario deadline passed before {entry_id} started")
        limit = float(entry.timeout_s) if left is None else min(float(entry.timeout_s), left)
        deadline_capped = left is not None and left <= entry.timeout_s

        # The newline ends a trailing comment; the mark shows env -0 was reached.
        body = (
            f"set -a; {{\n{entry.command}\n}} 1>&2 || exit $?; "
            f"printf '%s\\0' {shlex.quote(_SNAPSHOT_MARK)}; env -0"
        )
        argv = _shell(body, view, f"carddemo-{entry_id}")
        try:
            done = _run_process(
                argv,
                env=_process_env(view),
                cwd=cwd,
                timeout=limit,
                spool_dir=str(_spool_dir(view)),
            )
        except OSError as exc:
            reason = f"could not start: {exc.strerror or exc}"
            self._record(
                view, "run", entry_id, entry.command, cwd, None, "not-started", 0.0, reason=reason
            )
            _unavailable(AdapterUnavailable(f"{entry_id} {reason}", entry.gap), deadline, entry_id)

        if done.timed_out:
            # Killing a group that ignores SIGTERM can outlast the deadline too.
            outcome = "deadline" if deadline_capped or _expired(deadline) else "timeout"
        else:
            outcome = "completed"
        stderr = _text(done.stderr)
        self._record(
            view, "run", entry_id, entry.command, cwd, done.returncode, outcome, done.elapsed_s,
            stderr=stderr, sourcing=True,
        )
        if done.timed_out:
            if deadline_capped:
                raise ScenarioTimeout(
                    f"{entry_id} was stopped at the scenario deadline after {limit:.0f}s"
                )
            _unavailable(
                AdapterUnavailable(f"{entry_id} exceeded {entry.timeout_s}s", entry.gap),
                deadline,
                entry_id,
            )
        snapshots = _snapshots(done.stdout)
        if done.returncode != 0 or len(snapshots) != 2:
            _unavailable(
                AdapterUnavailable(f"{entry_id} exited {done.returncode}", entry.gap),
                deadline,
                entry_id,
            )
        if _expired(deadline):
            raise ScenarioTimeout(
                f"scenario deadline passed during {entry_id}; superseded: its exit status "
                f"{done.returncode}"
            )
        return _settings(snapshots[1]), stderr

    # ------------------------------------------------------------------ log

    @property
    def log(self) -> list[dict[str, Any]]:
        """Copies of every log entry so far, in order."""
        with self._lock:
            return [dict(record) for record in self._log]

    def mark(self) -> int:
        """A position in the log; ``log_since(mark)`` returns what follows."""
        with self._lock:
            return len(self._log)

    def log_since(self, mark: int) -> list[dict[str, Any]]:
        """Copies of the log entries recorded after ``mark``."""
        with self._lock:
            return [dict(record) for record in self._log[mark:]]

    @staticmethod
    def render_log(records: list[dict[str, Any]]) -> str:
        """``records`` as the YAML documents ``commands.log`` holds."""
        return "".join(f"---\n{dump_yaml(record)}" for record in records)

    @staticmethod
    def render_evidence_log(records: list[dict[str, Any]]) -> str:
        """``records`` as the YAML documents of the ``commands.log`` copy in a
        scenario's evidence: ``render_log`` of each record without its
        ``LOG_ELAPSED_KEY``. A ``sourcing`` record, which holds what a sourced
        script printed, keeps none of it: a non-empty ``stderr`` becomes
        ``""``, ``withheld`` lists ``stderr``, and ``truncated`` no longer
        names it (the key goes when nothing else is left in it). Every other
        field is kept. The evidence is committed: elapsed times go only to
        the git-ignored ``tests/reports/runtime/`` (AAP 0.4.6), and a sourced
        script's output only to the work root's ``logs/``. ``records`` is not
        changed."""
        rendered: list[dict[str, Any]] = []
        for record in records:
            shown = {key: value for key, value in record.items() if key != LOG_ELAPSED_KEY}
            if shown.get("sourcing") and shown.get("stderr"):
                shown["stderr"] = ""
                shown["withheld"] = ["stderr"]
                truncated = [name for name in shown.get("truncated", ()) if name != "stderr"]
                if truncated:
                    shown["truncated"] = truncated
                else:
                    shown.pop("truncated", None)
            rendered.append(shown)
        return CommandSet.render_log(rendered)

    def _record(
        self,
        env: Any,
        kind: str,
        entry_id: str,
        command: str | None,
        cwd: str | None,
        returncode: int | None,
        outcome: str,
        elapsed_s: float,
        *,
        stdout: str = "",
        stderr: str = "",
        reason: str = "",
        sourcing: bool = False,
    ) -> None:
        """Append one log entry. Output passes ``redact`` with the
        environment the command ran with, ``env.vars``, and is then cut to
        ``LOG_LIMIT_BYTES``. The environment itself is never logged, but a
        command can print what it holds, and every JVM echoes its option
        variables, so of the JVM options the log keeps only the owned
        temporary settings. A ``sourcing`` entry holds what a sourced script
        printed, which ``render_evidence_log`` keeps out of the evidence.
        ``commands.log`` is written only once the root and ``logs/`` pass
        again (``_log_dir``), never through a link (``_append_log``); either
        failing raises ``HarnessError`` with the entry kept in memory alone."""
        variables = getattr(env, "vars", {})
        out, out_cut = _clip(redact(stdout, variables))
        err, err_cut = _clip(redact(stderr, variables))
        record: dict[str, Any] = {
            "kind": kind,
            "id": entry_id,
            "command": command,
            "cwd": cwd,
            "rc": returncode,
            "outcome": outcome,
            # dump_yaml refuses floats: elapsed time is text with millisecond scale.
            LOG_ELAPSED_KEY: f"{elapsed_s:.3f}",
            "stdout": out,
            "stderr": err,
        }
        truncated = [name for name, cut in (("stdout", out_cut), ("stderr", err_cut)) if cut]
        if truncated:
            record["truncated"] = truncated
        if reason:
            record["reason"] = reason
        if sourcing:
            record["sourcing"] = True
        root = getattr(env, "deployment_root", None)
        with self._lock:
            record["seq"] = len(self._log) + 1
            self._log.append(record)
            if root is not None:
                _append_log(_log_dir(root) / _COMMANDS_LOG, f"---\n{dump_yaml(record)}")
