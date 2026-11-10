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
    the BPE node and their catalogs belong to this run alone.
``CommandSet``
    The only executor of UniKix commands. It runs entries of
    ``commands.yaml`` by id and nothing else. Each outcome follows the
    adapter's command rule: a missing capability (command ``null``, failed
    probe, non-zero exit, own ``timeout_s`` exceeded) is
    ``AdapterUnavailable`` with the entry's gap, and expiry of the scenario
    deadline is ``ScenarioTimeout``. ``CommandResult`` carries only the
    return code and the output, so no application outcome is ever inferred
    from a command failure.

Probe results are cached per environment *generation*. ``load`` starts
generation 0, and every ``source_runtime_scripts`` call, made once the
deployment exists, starts the next one. Probes that test the deployed tree
(``$MBMSUB``, ``$EBMHOME/batchenv``, ``$KIXSYS/unikixrc.cfg``) are therefore
re-run against it instead of keeping the answer they gave before deployment.

Every write stays inside the deployment root: the empty ``home/`` used as
``HOME``, ``logs/commands.log``, and unlinked spool files for command output.
Python 3.11 standard library and PyYAML only. Nothing here uses the network.
"""

from __future__ import annotations

import os
import re
import shlex
import shutil
import signal
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
from typing import Any

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
    "LOG_LIMIT_BYTES",
    "PROBE_TIMEOUT_CAP_S",
    "REQUIRED_INSTALLATION_VARS",
    "SOURCE_TIMEOUT_S",
    "VERSION_ENTRIES",
    "CommandEntry",
    "CommandResult",
    "CommandSet",
    "UniKixEnvironment",
]

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

ENV_VARIABLE = "CARDDEMO_UNIKIX_ENV"
COMMANDS_FILE = Path(__file__).resolve().with_name("commands.yaml")

_GAP_INSTALLATION = "GAP-MIS-009"  # no usable UniKix installation
_GAP_USERENV = "GAP-MIS-003"  # region creation, of which $KIXSYS/bin/userenv is part
_GAP_BATCHENV = "GAP-MIS-006"  # BPE submission, of which $EBMHOME/batchenv is part

# PR_BPENODE_NAME and PR_BPESUBSYS_NAME of carddemo.env.
BPE_NODE_NAME = "bpenode"
BPE_SUBSYSTEM = "sys1"

REQUIRED_INSTALLATION_VARS = ("UNIKIX", "INSTEBM", "VCOBOL", "KIXLICDIR", "JAVA_HOME")
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
)
_DEPLOYMENT_RECORD_VARS = DEPLOYMENT_PATH_VARS + ("EBMSYS", "COBCPY", "MBMSUB")

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
# An env -0 entry without "=", printed between the two snapshots of
# source_runtime_scripts.
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
    """Whether ``path`` resolves to ``root`` or below it."""
    try:
        Path(path).resolve().relative_to(root.resolve())
    except ValueError:
        return False
    return True


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
    operator's file exported, with the deployment variables replaced by the
    test-owned ones and, once the deployment exists, what ``userenv`` and
    ``batchenv`` add. ``generation`` counts those re-reads (see the module
    docstring). Build it with ``load``."""

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
        self.vars = variables
        self.generation = 0
        self.runtime_scripts = {"userenv": "not-sourced", "batchenv": "not-sourced"}
        self.sourcing_output = sourcing_output
        self._inherited_cobcpy = inherited_cobcpy

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

        ``deployment_root`` is ``<work root>/unikix/<execution_id>``; only its
        ``home/`` is created here. ``environ`` defaults to ``os.environ`` and
        supplies ``CARDDEMO_UNIKIX_ENV`` and ``PATH``, nothing else. The file
        is sourced with ``HOME`` set to that empty ``home/``, so the
        ``userenv`` and ``batchenv`` of an operator's own ``$HOME/unikixdemo``
        are never sourced.

        Raises ``AdapterUnavailable`` with GAP-MIS-009 when the variable is
        unset or names no readable file, before anything is created; when
        sourcing fails or times out; and when ``UNIKIX``, ``INSTEBM``,
        ``VCOBOL``, ``KIXLICDIR`` or ``JAVA_HOME`` is unset or missing.
        Raises ``HarnessError`` when the deployment root or a derived path
        lies outside where it must."""
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
        if root.resolve() == work.resolve() or not _inside(root, work):
            raise HarnessError(f"deployment root {root} must lie below the work root {work}")
        home = root / "home"
        home.mkdir(parents=True, exist_ok=True)

        captured, sourcing_output = _source_env_file(env_file, home, root, source)
        inherited_cobcpy = captured.get("COBCPY", "")
        variables = _settings(captured)
        variables.update(_derived(str(root), variables.get("UNIKIX", ""), inherited_cobcpy))
        variables["HOME"] = str(home)

        for name in REQUIRED_INSTALLATION_VARS:
            value = variables.get(name, "")
            if not value or not os.path.exists(value):
                raise AdapterUnavailable(f"{name} missing: {value or '(unset)'}", _GAP_INSTALLATION)

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
        return environment

    # -------------------------------------------------------------- accessors

    @property
    def log_dir(self) -> Path:
        """``<deployment root>/logs``, where ``commands.log`` is written."""
        return self.deployment_root / "logs"

    @property
    def mbmsub(self) -> str | None:
        """The BPE submission directory ``batchenv`` exports, or ``None``
        before it has been sourced."""
        return self.vars.get("MBMSUB") or None

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

    # ------------------------------------------------------ runtime scripts

    def source_runtime_scripts(self) -> dict[str, str]:
        """Merge ``$KIXSYS/bin/userenv`` and then ``$EBMHOME/batchenv sys1``
        into ``vars``, as ``carddemo.env`` sources them once they exist.

        Called by ``deploy.py`` after it has created the region and the BPE
        node. A script is sourced only when it is a file inside the
        deployment root. Both run in one bash started with the current
        ``vars``, so batchenv sees what userenv set. ``COBCPY`` is then
        extended again from whatever the scripts left, as ``bin/compile``
        would extend it in a shell where they had been sourced. Every call
        starts a new ``generation``.

        Returns the state of each script: ``sourced``, ``absent`` or
        ``outside-deployment-root``. Raises ``AdapterUnavailable`` with
        GAP-MIS-003 when userenv fails, times out or moves a deployment path
        out of the deployment root, and with GAP-MIS-006 when batchenv
        does."""
        scripts = {
            "userenv": Path(self.vars.get("KIXSYS", "")) / "bin" / "userenv",
            "batchenv": Path(self.vars.get("EBMHOME", "")) / "batchenv",
        }
        states: dict[str, str] = {}
        sourced: dict[str, str] = {}
        for key, script in scripts.items():
            if not script.is_file():
                states[key] = "absent"
            elif not _inside(script, self.deployment_root):
                states[key] = "outside-deployment-root"
            else:
                states[key] = "sourced"
                sourced[key] = str(script)

        if sourced:
            start = dict(self.vars)
            start["COBCPY"] = self._inherited_cobcpy
            # $1 userenv, $2 batchenv, $3 the snapshot mark. A failing "."
            # ends bash before the snapshot of its phase, which attributes it.
            body = (
                'set -a; if [ -n "$1" ]; then . "$1" 1>&2 || exit $?; fi; env -0; '
                f"printf '%s\\0' \"$3\"; "
                f'if [ -n "$2" ]; then . "$2" {BPE_SUBSYSTEM} 1>&2 || exit $?; fi; env -0'
            )
            argv = [
                _bash(),
                "--noprofile",
                "--norc",
                "-c",
                body,
                "carddemo-runtime",
                sourced.get("userenv", ""),
                sourced.get("batchenv", ""),
                _SNAPSHOT_MARK,
            ]
            first_gap = _GAP_USERENV if "userenv" in sourced else _GAP_BATCHENV
            self.log_dir.mkdir(parents=True, exist_ok=True)
            try:
                done = _run_process(
                    argv,
                    env=start,
                    cwd=_default_cwd(self),
                    timeout=SOURCE_TIMEOUT_S,
                    spool_dir=str(self.log_dir),
                )
            except OSError as exc:
                raise AdapterUnavailable(
                    f"cannot source the runtime scripts: {exc.strerror or exc}", first_gap
                ) from None
            snapshots = _snapshots(done.stdout)
            # One snapshot: the failure came before the mark, so from userenv.
            failed_key = "userenv" if len(snapshots) < 2 else "batchenv"
            failed_gap = _GAP_USERENV if failed_key == "userenv" else _GAP_BATCHENV
            if done.timed_out:
                raise AdapterUnavailable(
                    f"sourcing {scripts[failed_key]} exceeded {SOURCE_TIMEOUT_S}s", failed_gap
                )
            if done.returncode != 0 or len(snapshots) != 2:
                raise AdapterUnavailable(
                    f"sourcing {scripts[failed_key]} exited {done.returncode}", failed_gap
                )
            for key, gap, snapshot in (
                ("userenv", _GAP_USERENV, snapshots[0]),
                ("batchenv", _GAP_BATCHENV, snapshots[1]),
            ):
                outside = self._outside_paths(snapshot)
                if outside:
                    name, value = outside[0]
                    raise AdapterUnavailable(
                        f"{scripts[key]} moves {name} outside the deployment root: {value}", gap
                    )
            merged = _settings(snapshots[1])
            self._inherited_cobcpy = merged.get("COBCPY", "")
            merged["COBCPY"] = _cobcpy(
                self._inherited_cobcpy, merged["MIGAPP_DIR"], merged.get("UNIKIX", "")
            )
            self.vars = merged
            self.sourcing_output += _text(done.stderr)

        self.runtime_scripts = states
        self.generation += 1
        return dict(states)

    # ---------------------------------------------------------- records

    def versions(self, commands: CommandSet) -> dict[str, str]:
        """The output of every ``version_*`` entry, keyed by entry id, for
        ``environment.yaml`` (GAP-MIS-009). A failure is recorded as text and
        never raised."""
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
            text = result.stdout + result.stderr
            if result.returncode != 0:
                text = f"exit status {result.returncode}\n{text}"
            found[entry_id] = _clip(text)[0]
        return found

    def as_record(self) -> dict[str, Any]:
        """The environment as ``environment.yaml`` records it: installation
        paths, deployment paths, the environment file and its sha256, and
        the runtime script states. It is never a dump of ``vars``."""
        return {
            "env_file": str(self.env_file),
            "env_file_sha256": self.env_file_sha256,
            "installation": {name: self.vars.get(name) for name in INSTALLATION_VARS},
            "deployment": {name: self.vars.get(name) for name in _DEPLOYMENT_RECORD_VARS},
            "runtime_scripts": dict(self.runtime_scripts),
            "generation": self.generation,
            "sourcing_output": _clip(self.sourcing_output)[0],
        }


def _source_env_file(
    env_file: Path, home: Path, root: Path, source: Mapping[str, str]
) -> tuple[dict[str, str], str]:
    """Source ``env_file`` with ``set -a`` in a bash that has only ``PATH``,
    the empty ``HOME`` and ``LANG=C``. Returns the exported environment and
    the file's own output, which goes to stderr so that stdout holds only
    ``env -0``."""
    minimal = {"PATH": source.get("PATH") or os.defpath, "HOME": str(home), "LANG": "C"}
    argv = [
        _bash(),
        "--noprofile",
        "--norc",
        "-c",
        'set -a; . "$1" 1>&2; env -0',
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
    errors."""
    if text is None:
        return frozenset()
    names: set[str] = set()
    try:
        fields = list(string.Formatter().parse(text))
    except ValueError as exc:
        raise HarnessError(f"{where}: {exc}") from None
    for _literal, name, _spec, _conversion in fields:
        if name is None:
            continue
        if not _PLACEHOLDER_RE.fullmatch(name):
            raise HarnessError(
                f"{where}: {{{name}}} is not a placeholder; "
                "write $VAR for a variable, never ${VAR}"
            )
        names.add(name)
    return frozenset(names)


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
        """The caller values the command and its ``cwd`` need."""
        return _placeholders(self.command, f"{self.id}.command") | _placeholders(
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
    for key, value in (("command", command), ("cwd", cwd)):
        try:
            _placeholders(value, key)
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
    """``command`` with every value shell-quoted; ``$VAR`` is left for bash."""
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
    """Where command output is spooled and ``commands.log`` is written:
    ``<deployment root>/logs``, or the default working directory for an
    environment that names no deployment root."""
    root = getattr(env, "deployment_root", None)
    if root is None:
        return Path(_default_cwd(env))
    logs = Path(root) / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    return logs


class CommandSet:
    """The entries of ``commands.yaml`` and the single place that runs them.

    ``env`` arguments are a ``UniKixEnvironment`` or an object with the same
    ``vars`` (and optionally ``deployment_root`` and ``generation``). The log
    holds every probe and every run, and every refused run too, in order.
    It is kept in memory and appended to ``<deployment root>/logs/commands.log``
    as YAML documents. ``mark`` and ``log_since`` select the entries of one
    scenario. Calls may come from several threads."""

    def __init__(self, entries: Mapping[str, CommandEntry], path: Path) -> None:
        self.path = path
        self._entries = dict(entries)
        self._probes: dict[str, _Probe] = {}
        self._log: list[dict[str, Any]] = []
        self._lock = threading.RLock()

    @classmethod
    def load(cls, path: str | os.PathLike[str] | None = None) -> CommandSet:
        """Read ``commands.yaml`` (``schema_version: 1``, ``entries`` mapping
        id -> entry). Any schema problem raises ``HarnessError``."""
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
        entries = {
            str(entry_id): _entry(entry_id, raw, str(file))
            for entry_id, raw in raw_entries.items()
        }
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
        ``env``'s current generation is cached."""
        return self._probe(self._entries[entry_id], env, refresh=refresh, deadline=None)

    def available(self, entry_id: str, env: Any = None) -> bool:
        """The cached availability of ``entry_id``. With ``env``, an entry not
        yet probed for its current generation is probed first. Without it,
        the latest cached result is returned, or ``False`` if the entry has
        never been probed."""
        entry = self._entries[entry_id]
        if env is not None:
            return self._probe(entry, env, refresh=False, deadline=None)
        with self._lock:
            cached = self._probes.get(entry_id)
        return entry.command is not None and cached is not None and cached.ok

    def probe_reason(self, entry_id: str) -> str | None:
        """Why the latest probe of ``entry_id`` failed, ``""`` after a
        success, ``None`` before any probe."""
        entry = self._entries[entry_id]
        if entry.command is None:
            return "no command is known (command: null)"
        with self._lock:
            cached = self._probes.get(entry_id)
        return None if cached is None else cached.reason

    def _probe(
        self, entry: CommandEntry, env: Any, *, refresh: bool, deadline: float | None
    ) -> bool:
        generation = getattr(env, "generation", 0)
        with self._lock:
            cached = self._probes.get(entry.id)
        if cached is not None and cached.generation == generation and not refresh:
            return cached.ok
        if entry.command is None:
            # Nothing to probe: command null is unavailable by definition.
            ok, reason = False, "no command is known (command: null)"
        else:
            limit = float(min(entry.timeout_s, PROBE_TIMEOUT_CAP_S))
            capped = False
            if deadline is not None:
                left = deadline - time.monotonic()
                if left <= 0:
                    raise ScenarioTimeout(f"scenario deadline passed before probing {entry.id}")
                if left < limit:
                    limit, capped = left, True
            ok, reason = self._execute_probe(entry, env, limit, capped)
        with self._lock:
            self._probes[entry.id] = _Probe(generation, ok, reason)
        return ok

    def _execute_probe(
        self, entry: CommandEntry, env: Any, limit: float, capped: bool
    ) -> tuple[bool, str]:
        cwd = _default_cwd(env)
        argv = [_bash(), "--noprofile", "--norc", "-c", entry.probe]
        try:
            done = _run_process(
                argv, env=env.vars, cwd=cwd, timeout=limit, spool_dir=str(_spool_dir(env))
            )
        except OSError as exc:
            reason = f"probe could not start: {exc.strerror or exc}"
            self._record(
                env, "probe", entry.id, entry.probe, cwd, None, "not-started", 0.0, reason=reason
            )
            return False, reason
        if done.timed_out:
            outcome = "deadline" if capped else "timeout"
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
        ``values``, which are shell-quoted into the command.

        The command runs as ``bash --noprofile --norc -c <command>`` in the
        entry's ``cwd`` (``$VAR`` expanded), or else in ``$PROJECT_ROOT``.
        It is bounded by ``timeout_s``, capped at ``deadline_left``, the
        seconds left before the scenario deadline when the caller has one.

        * ``command: null`` or a failed probe raise
          ``AdapterUnavailable("<id> unavailable: <reason>", gap)`` and run
          nothing.
        * At the timeout the process group is killed. If the cap was the
          scenario deadline, ``ScenarioTimeout`` is raised. Otherwise
          ``AdapterUnavailable("<id> exceeded <timeout_s>s", gap)`` is.
        * A non-zero exit raises ``AdapterUnavailable("<id> exited <rc>",
          gap)`` when ``check`` is true and is returned otherwise.

        An unknown ``entry_id`` raises ``KeyError``, and a placeholder without
        a value raises ``HarnessError``: both are programming errors."""
        entry = self._entries[entry_id]
        texts = _values(entry, values)
        deadline: float | None = None
        if deadline_left is not None:
            if deadline_left <= 0:
                raise ScenarioTimeout(f"scenario deadline passed before {entry_id} started")
            deadline = time.monotonic() + deadline_left

        if entry.command is None or not self._probe(entry, env, refresh=False, deadline=deadline):
            reason = self.probe_reason(entry_id) or "probe failed"
            self._record(
                env, "run", entry_id, entry.command, None, None, "refused", 0.0, reason=reason
            )
            raise AdapterUnavailable(f"{entry_id} unavailable: {reason}", entry.gap)

        command = _render_command(entry.command, texts)
        if entry.cwd is None:
            cwd = _default_cwd(env)
        else:
            cwd = _expand_cwd(entry, entry.cwd, texts, env)
        left = None if deadline is None else deadline - time.monotonic()
        if left is not None and left <= 0:
            raise ScenarioTimeout(f"scenario deadline passed before {entry_id} started")
        limit = float(entry.timeout_s) if left is None else min(float(entry.timeout_s), left)
        deadline_capped = left is not None and left <= entry.timeout_s

        argv = [_bash(), "--noprofile", "--norc", "-c", command]
        try:
            done = _run_process(
                argv, env=env.vars, cwd=cwd, timeout=limit, spool_dir=str(_spool_dir(env))
            )
        except OSError as exc:
            reason = f"could not start: {exc.strerror or exc}"
            self._record(
                env, "run", entry_id, command, cwd, None, "not-started", 0.0, reason=reason
            )
            raise AdapterUnavailable(f"{entry_id} {reason}", entry.gap) from None

        if done.timed_out:
            outcome = "deadline" if deadline_capped else "timeout"
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
            raise AdapterUnavailable(f"{entry_id} exceeded {entry.timeout_s}s", entry.gap)
        # A process that was not timed out has been waited for, so it has a status.
        result = CommandResult(entry_id, int(done.returncode or 0), stdout, stderr)
        if check and not result.ok:
            raise AdapterUnavailable(f"{entry_id} exited {result.returncode}", entry.gap)
        return result

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
    ) -> None:
        """Append one log entry. Output is cut to ``LOG_LIMIT_BYTES``; the
        environment is never logged, so no exported value reaches the log."""
        out, out_cut = _clip(stdout)
        err, err_cut = _clip(stderr)
        record: dict[str, Any] = {
            "kind": kind,
            "id": entry_id,
            "command": command,
            "cwd": cwd,
            "rc": returncode,
            "outcome": outcome,
            # dump_yaml refuses floats: elapsed time is text with millisecond scale.
            "elapsed_s": f"{elapsed_s:.3f}",
            "stdout": out,
            "stderr": err,
        }
        truncated = [name for name, cut in (("stdout", out_cut), ("stderr", err_cut)) if cut]
        if truncated:
            record["truncated"] = truncated
        if reason:
            record["reason"] = reason
        root = getattr(env, "deployment_root", None)
        with self._lock:
            record["seq"] = len(self._log) + 1
            self._log.append(record)
            if root is not None:
                logs = Path(root) / "logs"
                logs.mkdir(parents=True, exist_ok=True)
                with open(logs / "commands.log", "a", encoding="utf-8", newline="\n") as handle:
                    handle.write(f"---\n{dump_yaml(record)}")
