# Smoke test for the CardDemo batch CLI help/usage surfaces.
#
# Regression guard for the Typer/Click ``--help`` rendering contract. Historically
# ``typer 0.15.1`` paired with ``click >= 8.2`` crashed EVERY ``--help``/usage
# rendering with ``TypeError: Parameter.make_metavar() missing 1 required
# positional argument: 'ctx'`` (former finding B / #55). The batch manifest now
# pins the UPGRADED compatible pair ``typer==0.27.0`` with ``click==8.4.2`` (QA
# finding N-04: Click 8.1.8 carried a vulnerable ``click.edit`` and is below the
# >= 8.3.3 security floor; Typer 0.27.0 uses the post-8.2 ``make_metavar``
# signature), so every help surface must still render cleanly. This test re-runs
# the exact reproduction commands as subprocesses (``python -m batch.cli ...
# --help``) and asserts they exit 0 with no traceback and no ``make_metavar`` error.
"""Subprocess smoke test asserting every batch CLI help surface renders cleanly.

The CLI is exercised through ``python -m batch.cli`` -- the documented operator
entrypoint -- in a child process with a controlled environment, so this test
mirrors real invocation and never imports the ``settings`` singleton into the
pytest process. Only lightweight help rendering is exercised; no database
connection is opened (the placeholder URLs below are never dialed).
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import pytest

# Package version string for the --version assertion (QA finding F-3). Importing
# ``batch`` only sets ``__version__`` and a sys.path shim -- it does NOT import
# the ``settings`` singleton or open a database, so it respects this module's
# "no heavy import into the pytest process" contract.
from batch import __version__

# --------------------------------------------------------------------------- #
# Module constants (ALL_UPPERCASE per the Ochs Rule).
# --------------------------------------------------------------------------- #
# ``__file__`` is ``<repo>/batch/tests/test_cli_smoke.py`` -> ``parents[2]`` is
# the repository root, which holds both the importable ``batch`` package and the
# sibling ``backend`` directory (source of the ``app.*`` package).
REPOSITORY_ROOT: Path = Path(__file__).resolve().parents[2]
BACKEND_DIR: Path = REPOSITORY_ROOT / "backend"

# Test-only placeholders for the import-time typed settings (Ochs Rule #3: these
# are NOT secrets -- they satisfy the fail-fast SECRET_KEY length check and give
# create_engine a parseable URL; no connection is ever opened during --help).
# ``setdefault`` semantics (see _BuildSmokeEnv) let a real environment win.
TEST_SECRET_KEY: str = "batch-cli-smoke-test-secret-key-0123456789"
TEST_SYNC_DATABASE_URL: str = (
    "postgresql+psycopg2://smoke:smoke@localhost:5432/carddemo_smoke_test"
)
TEST_DATABASE_URL: str = (
    "postgresql+asyncpg://smoke:smoke@localhost:5432/carddemo_smoke_test"
)

# The help surfaces from QA Finding B's reproduction, plus representative job and
# loader subcommand help. Every entry MUST render cleanly and exit 0.
HELP_SURFACES: tuple[tuple[str, ...], ...] = (
    ("--help",),
    ("job", "--help"),
    ("load", "--help"),
    ("run-all", "--help"),
    ("seed-all", "--help"),
    ("job", "post-transactions", "--help"),
    ("job", "statement-gen", "--help"),
    # QA finding M17/M18: the combine/backup/restore job help surfaces. restore-tran
    # in particular declares a REQUIRED typer.Option (``--backup-file``), the exact
    # construct behind Finding B's make_metavar crash, so its help must render cleanly.
    ("job", "combine-tran", "--help"),
    ("job", "backup-tran", "--help"),
    ("job", "restore-tran", "--help"),
    ("load", "accounts", "--help"),
    ("load", "init-users", "--help"),
)

# The exact crash signature Finding B reported; it must never reappear.
MAKE_METAVAR_ERROR_SIGNATURE: str = "make_metavar"


def _BuildSmokeEnv() -> dict:
    """Return a child-process environment for the CLI help subprocess.

    Inherits the current environment, then fills in the import-time settings the
    CLI needs (SECRET_KEY, SYNC_DATABASE_URL, DATABASE_URL) only when absent, so
    a real deployment/CI environment always wins. ``PYTHONPATH`` is prefixed with
    ``backend`` (for ``app.*``) and the repository root (for ``batch``) so the
    subprocess resolves both packages regardless of the caller's working dir.

    Returns:
        A ``dict`` suitable for ``subprocess.run(env=...)``.
    """
    smokeEnv = dict(os.environ)
    smokeEnv.setdefault("SECRET_KEY", TEST_SECRET_KEY)
    smokeEnv.setdefault("SYNC_DATABASE_URL", TEST_SYNC_DATABASE_URL)
    smokeEnv.setdefault("DATABASE_URL", TEST_DATABASE_URL)
    pythonPathParts = [str(BACKEND_DIR), str(REPOSITORY_ROOT)]
    existingPythonPath = os.environ.get("PYTHONPATH")
    if existingPythonPath:
        pythonPathParts.append(existingPythonPath)
    smokeEnv["PYTHONPATH"] = os.pathsep.join(pythonPathParts)
    return smokeEnv


def _RunCli(arguments: tuple[str, ...]) -> subprocess.CompletedProcess:
    """Run ``python -m batch.cli <arguments>`` as a child process.

    Args:
        arguments: The CLI argument tuple (for example ``("job", "--help")``).

    Returns:
        The completed process with captured text stdout/stderr.
    """
    command = [sys.executable, "-m", "batch.cli", *arguments]
    return subprocess.run(
        command,
        cwd=str(REPOSITORY_ROOT),
        env=_BuildSmokeEnv(),
        capture_output=True,
        text=True,
        timeout=60,
    )


@pytest.mark.parametrize("arguments", HELP_SURFACES, ids=lambda a: " ".join(a))
def test_help_surface_renders_cleanly(arguments: tuple[str, ...]) -> None:
    """Every ``--help`` surface exits 0, shows usage, and never crashes.

    NOTE: pytest requires ``test_``-prefixed (snake_case) discovery names, so
    this function deviates from the Ochs PascalCase rule as a justified framework
    requirement (analogous to protocol-mandated method names).
    """
    completed = _RunCli(arguments)
    combinedOutput = completed.stdout + completed.stderr
    assert completed.returncode == 0, (
        f"`python -m batch.cli {' '.join(arguments)}` exited "
        f"{completed.returncode}; output:\n{combinedOutput}"
    )
    assert "Usage" in completed.stdout, (
        f"help output for {' '.join(arguments)} lacked a Usage banner:\n"
        f"{combinedOutput}"
    )
    assert MAKE_METAVAR_ERROR_SIGNATURE not in combinedOutput, (
        f"Finding B crash signature re-appeared for {' '.join(arguments)}:\n"
        f"{combinedOutput}"
    )
    assert "Traceback" not in combinedOutput, (
        f"help rendering emitted a traceback for {' '.join(arguments)}:\n"
        f"{combinedOutput}"
    )


def test_no_args_renders_help_without_crash() -> None:
    """Bare ``python -m batch.cli`` renders help (no_args_is_help) and never crashes.

    By Typer convention ``no_args_is_help=True`` prints help and exits 2 (a usage
    exit), so the assertion targets clean help rendering rather than a specific
    zero exit: the finding was a rendering CRASH, not the usage exit code.
    """
    completed = _RunCli(())
    combinedOutput = completed.stdout + completed.stderr
    assert "Usage" in completed.stdout, (
        f"no-args invocation did not render help:\n{combinedOutput}"
    )
    assert MAKE_METAVAR_ERROR_SIGNATURE not in combinedOutput, (
        f"Finding B crash signature re-appeared on no-args invocation:\n"
        f"{combinedOutput}"
    )
    assert "Traceback" not in combinedOutput, (
        f"no-args help rendering emitted a traceback:\n{combinedOutput}"
    )


def test_version_flag_prints_version_and_exits_zero() -> None:
    """``--version`` prints ``batch.__version__`` and exits 0 without a DB.

    QA finding F-3: the batch CLI historically exposed no ``--version`` flag. It
    is now an EAGER root option whose callback echoes :data:`batch.__version__`
    and raises ``typer.Exit()`` (exit code 0) BEFORE any subcommand dispatch or
    database access, so this subprocess needs no reachable database. The printed
    line must equal the package version exactly, with no usage banner and no
    traceback.
    """
    completed = _RunCli(("--version",))
    combinedOutput = completed.stdout + completed.stderr
    assert completed.returncode == 0, (
        f"`python -m batch.cli --version` exited {completed.returncode}; "
        f"output:\n{combinedOutput}"
    )
    assert completed.stdout.strip() == __version__, (
        f"--version printed {completed.stdout.strip()!r}, expected "
        f"{__version__!r}:\n{combinedOutput}"
    )
    assert "Usage" not in completed.stdout, (
        f"--version must not render a usage banner:\n{combinedOutput}"
    )
    assert MAKE_METAVAR_ERROR_SIGNATURE not in combinedOutput, (
        f"Finding B crash signature re-appeared on --version:\n{combinedOutput}"
    )
    assert "Traceback" not in combinedOutput, (
        f"--version emitted a traceback:\n{combinedOutput}"
    )
