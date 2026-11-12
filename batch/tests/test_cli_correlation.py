# Batch correlation-id propagation test (QA finding M-32).
#
# The batch tier inherits a correlation id from the CARDDEMO_CORRELATION_ID
# environment variable when an orchestrator (or the API tier) supplies one -- so a
# single id can span both tiers -- and stamps it on every batch log line via the
# correlation filter installed in ``batch.cli._ConfigureLogging``. This test runs
# the logging setup in a CHILD process (``python -c``) so the process-global
# ``logging.basicConfig`` and the correlation context variable never leak into the
# pytest process, mirroring the isolation approach of ``test_cli_smoke.py``.
"""Subprocess tests for batch correlation-id inheritance and log stamping (M-32)."""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

# --------------------------------------------------------------------------- #
# Module constants (ALL_UPPERCASE per the Ochs Rule).
# --------------------------------------------------------------------------- #
# ``__file__`` is ``<repo>/batch/tests/test_cli_correlation.py`` -> ``parents[2]``
# is the repository root, which holds both the importable ``batch`` package and
# the sibling ``backend`` directory (source of the ``app.*`` package).
REPOSITORY_ROOT: Path = Path(__file__).resolve().parents[2]
BACKEND_DIR: Path = REPOSITORY_ROOT / "backend"

# Test-only placeholders for the import-time typed settings (Ochs Rule #3: these
# are NOT secrets -- they satisfy the fail-fast SECRET_KEY length check and give
# the sync engine a parseable URL; no connection is ever opened by the probe).
TEST_SECRET_KEY: str = "batch-cli-correlation-test-secret-0123456789"
TEST_SYNC_DATABASE_URL: str = (
    "postgresql+psycopg2://probe:probe@localhost:5432/carddemo_probe_test"
)
TEST_DATABASE_URL: str = (
    "postgresql+asyncpg://probe:probe@localhost:5432/carddemo_probe_test"
)

# Probe: configure batch logging, then emit one INFO line whose format carries the
# ``[%(correlation_id)s]`` field. The line is written to stderr by basicConfig.
PROBE_MARKER: str = "PROBE-CORRELATION-LINE"
PROBE_LOGGER_NAME: str = "batch.jobs.probe"
PROBE_CODE: str = (
    "import logging\n"
    "from batch import cli\n"
    "cli._ConfigureLogging(verbose=False)\n"
    f"logging.getLogger({PROBE_LOGGER_NAME!r}).info({PROBE_MARKER!r})\n"
)

# Captures the correlation-id token from a batch log line of the shape
# ``<ts> INFO [<id>] batch.jobs.probe: PROBE-CORRELATION-LINE``.
_PROBE_LINE_PATTERN = re.compile(
    rf"\[([^\]]+)\] {re.escape(PROBE_LOGGER_NAME)}: {re.escape(PROBE_MARKER)}"
)

# A freshly generated id is a 32-character lowercase-hex uuid4 (dashes removed).
_GENERATED_ID_PATTERN = re.compile(r"^[0-9a-f]{32}$")


def _BuildProbeEnv(extraEnv: dict) -> dict:
    """Return a child-process environment for the correlation probe.

    Inherits the current environment, forces ``ENVIRONMENT=test`` and fills in the
    import-time settings the batch import needs (only when absent, so a real CI
    environment still wins), prefixes ``PYTHONPATH`` with ``backend`` (for
    ``app.*``) and the repository root (for ``batch``), and finally applies
    ``extraEnv`` (which may set or clear ``CARDDEMO_CORRELATION_ID``).

    Args:
        extraEnv: Environment overrides applied last (highest precedence).

    Returns:
        A ``dict`` suitable for ``subprocess.run(env=...)``.
    """
    probeEnv = dict(os.environ)
    probeEnv["ENVIRONMENT"] = "test"
    probeEnv.setdefault("SECRET_KEY", TEST_SECRET_KEY)
    probeEnv.setdefault("SYNC_DATABASE_URL", TEST_SYNC_DATABASE_URL)
    probeEnv.setdefault("DATABASE_URL", TEST_DATABASE_URL)
    pythonPathParts = [str(BACKEND_DIR), str(REPOSITORY_ROOT)]
    existingPythonPath = os.environ.get("PYTHONPATH")
    if existingPythonPath:
        pythonPathParts.append(existingPythonPath)
    probeEnv["PYTHONPATH"] = os.pathsep.join(pythonPathParts)
    probeEnv.update(extraEnv)
    return probeEnv


def _RunProbe(extraEnv: dict) -> str:
    """Run the logging probe as a child process and return the captured id token.

    Args:
        extraEnv: Environment overrides (for example the inherited correlation id
            under test, or its removal).

    Returns:
        The correlation-id token captured from the probe's stderr log line.
    """
    completed = subprocess.run(
        [sys.executable, "-c", PROBE_CODE],
        cwd=str(REPOSITORY_ROOT),
        env=_BuildProbeEnv(extraEnv),
        capture_output=True,
        text=True,
        timeout=60,
    )
    assert completed.returncode == 0, completed.stderr
    match = _PROBE_LINE_PATTERN.search(completed.stderr)
    assert match is not None, (
        f"probe log line not found in stderr:\n{completed.stderr}"
    )
    return match.group(1)


def test_batch_inherits_a_valid_correlation_id_from_the_environment() -> None:
    """A valid ``CARDDEMO_CORRELATION_ID`` is honored and stamped on batch logs."""
    capturedId = _RunProbe({"CARDDEMO_CORRELATION_ID": "orch-run-42"})
    assert capturedId == "orch-run-42"


def test_batch_rejects_an_invalid_inherited_id_and_generates_fresh() -> None:
    """An invalid inherited id is rejected and replaced with a fresh id."""
    capturedId = _RunProbe({"CARDDEMO_CORRELATION_ID": "bad id with spaces"})
    assert capturedId != "bad id with spaces"
    assert _GENERATED_ID_PATTERN.match(capturedId)


def test_batch_generates_a_fresh_id_when_none_is_inherited() -> None:
    """With no inherited id, the batch run mints a fresh id for its logs."""
    capturedId = _RunProbe({"CARDDEMO_CORRELATION_ID": ""})
    assert _GENERATED_ID_PATTERN.match(capturedId)
