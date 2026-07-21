# Unit tests for app.core.config configuration hardening.
# Traceability: these tests encode the QA runtime checkpoint's fail-fast /
#   no-secret-leak contract for the typed environment configuration
#   (app/core/config.py, AAP rule-mandated per Ochs Rule #3 "no hardcoding
#   secrets" and Rule #5 "no sensitive values in logs"). Each finding
#   (CONFIG-1..7) is reproduced exactly as the QA agent did: a fresh Python
#   process is spawned with a controlled environment and the config module is
#   imported, asserting that malformed configuration fails fast with a specific,
#   non-secret error naming the bad key -- and that secrets never render in
#   cleartext through repr()/str()/model_dump().
#
# Subprocess isolation is MANDATORY here: ``app.core.config`` builds its
# ``settings`` singleton once, at import time, from the process environment, so
# each scenario needs its own interpreter with its own environment. The
# subprocess runs in a scratch working directory that contains NO ``.env`` file,
# so pydantic-settings sees only the variables each test injects (never the
# repository's backend/.env). No password hashing is ever performed here: the
# out-of-range BCRYPT_ROUNDS cases assert import-time rejection only, so a bad
# cost can never hang the test run.
#
# Ochs naming (AAP 0.8.2 / 0.8.3): snake_case test-function names (the pytest
# discovery contract) and file name; camelCase local variables; ALL_UPPERCASE
# module-level constants; 4-space indentation; one asserted behavior per test.

import os
import subprocess
import sys
from pathlib import Path

# Backend package root (parents: unit -> tests -> backend), added to PYTHONPATH
# of every spawned probe so ``import app.core.config`` resolves.
BACKEND_ROOT = str(Path(__file__).resolve().parents[2])

# A valid signing key comfortably above MINIMUM_SECRET_KEY_LENGTH (32). Reused as
# the baseline "good" SECRET_KEY for every scenario that varies a different key.
VALID_SECRET_KEY = "unit-test-secret-key-0123456789-abcdefghij"

# A canary secret whose exact bytes must never appear in rendered output. Chosen
# to be long enough to satisfy the length rule so CONFIG-6 tests a valid config.
CANARY_SECRET_KEY = "CANARY-SECRET-VALUE-0123456789-abcdefghij"

# A database URL whose password (``canarydbpw``) must never render in cleartext.
CANARY_DATABASE_URL = "postgresql+asyncpg://dbuser:canarydbpw@localhost:5432/db"

# Probe that merely imports the config module (triggering ``settings=Settings()``).
# A rejected configuration makes this exit non-zero with the offending field
# named in the traceback on stderr.
IMPORT_PROBE = "import app.core.config"

# Timeout (seconds) for each probe. Import + validation is sub-second; this only
# guards against an unexpected hang and never runs any bcrypt hashing.
PROBE_TIMEOUT_SECONDS = 30


def _RunConfigProbe(extraEnv, probeCode, workingDir):
    """Import the config module in a fresh process with a controlled environment.

    Args:
        extraEnv: Mapping of environment variables to inject on top of the
            minimal base environment.
        probeCode: The Python source executed with ``python -c``.
        workingDir: A directory with no ``.env`` file used as the process CWD so
            pydantic-settings reads only the injected variables.

    Returns:
        The completed :class:`subprocess.CompletedProcess` (stdout/stderr captured).
    """
    probeEnv = {
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "PYTHONPATH": BACKEND_ROOT,
        "ENVIRONMENT": "test",
    }
    probeEnv.update(extraEnv)
    completed = subprocess.run(
        [sys.executable, "-c", probeCode],
        cwd=str(workingDir),
        env=probeEnv,
        capture_output=True,
        text=True,
        timeout=PROBE_TIMEOUT_SECONDS,
    )
    return completed


# ---------------------------------------------------------------------------
# CONFIG-1 / CONFIG-3 -- SECRET_KEY blank or too short must fail fast.
# ---------------------------------------------------------------------------


def test_blank_secret_key_is_rejected(tmp_path):
    completed = _RunConfigProbe({"SECRET_KEY": ""}, IMPORT_PROBE, tmp_path)
    assert completed.returncode != 0
    assert "SECRET_KEY" in completed.stderr


def test_short_secret_key_is_rejected(tmp_path):
    completed = _RunConfigProbe({"SECRET_KEY": "x"}, IMPORT_PROBE, tmp_path)
    assert completed.returncode != 0
    assert "SECRET_KEY" in completed.stderr


def test_valid_secret_key_is_accepted(tmp_path):
    completed = _RunConfigProbe({"SECRET_KEY": VALID_SECRET_KEY}, IMPORT_PROBE, tmp_path)
    assert completed.returncode == 0


def test_unset_secret_key_still_fails_fast(tmp_path):
    # AAP compliance-matrix #7: a genuinely-unset SECRET_KEY fails fast.
    completed = _RunConfigProbe({}, IMPORT_PROBE, tmp_path)
    assert completed.returncode != 0
    assert "SECRET_KEY" in completed.stderr


# ---------------------------------------------------------------------------
# CONFIG-2 -- BCRYPT_ROUNDS must be bounded (no silent weakening, no hang).
# The out-of-range cases assert IMPORT-TIME rejection; no hashing is performed.
# ---------------------------------------------------------------------------


def test_bcrypt_rounds_too_low_is_rejected(tmp_path):
    completed = _RunConfigProbe(
        {"SECRET_KEY": VALID_SECRET_KEY, "BCRYPT_ROUNDS": "0"},
        IMPORT_PROBE,
        tmp_path,
    )
    assert completed.returncode != 0
    assert "BCRYPT_ROUNDS" in completed.stderr


def test_bcrypt_rounds_too_high_is_rejected(tmp_path):
    completed = _RunConfigProbe(
        {"SECRET_KEY": VALID_SECRET_KEY, "BCRYPT_ROUNDS": "99"},
        IMPORT_PROBE,
        tmp_path,
    )
    assert completed.returncode != 0
    assert "BCRYPT_ROUNDS" in completed.stderr


def test_bcrypt_rounds_in_range_is_accepted(tmp_path):
    completed = _RunConfigProbe(
        {"SECRET_KEY": VALID_SECRET_KEY, "BCRYPT_ROUNDS": "12"},
        IMPORT_PROBE,
        tmp_path,
    )
    assert completed.returncode == 0


# ---------------------------------------------------------------------------
# CONFIG-4 -- AUTH_MODE restricted to the supported strategies (AAP 0.8.4).
# ---------------------------------------------------------------------------


def test_unsupported_auth_mode_is_rejected(tmp_path):
    completed = _RunConfigProbe(
        {"SECRET_KEY": VALID_SECRET_KEY, "AUTH_MODE": "banana"},
        IMPORT_PROBE,
        tmp_path,
    )
    assert completed.returncode != 0
    assert "AUTH_MODE" in completed.stderr


def test_jwt_auth_mode_is_accepted(tmp_path):
    completed = _RunConfigProbe(
        {"SECRET_KEY": VALID_SECRET_KEY, "AUTH_MODE": "jwt"},
        IMPORT_PROBE,
        tmp_path,
    )
    assert completed.returncode == 0


# ---------------------------------------------------------------------------
# CONFIG-5 -- malformed CORS origins must be rejected, valid ones parsed.
# ---------------------------------------------------------------------------


def test_malformed_cors_origin_is_rejected(tmp_path):
    completed = _RunConfigProbe(
        {"SECRET_KEY": VALID_SECRET_KEY, "BACKEND_CORS_ORIGINS": "not-a-url"},
        IMPORT_PROBE,
        tmp_path,
    )
    assert completed.returncode != 0
    assert "BACKEND_CORS_ORIGINS" in completed.stderr


def test_valid_cors_origins_are_parsed(tmp_path):
    probeCode = (
        "import app.core.config as C; "
        "print('ORIGIN_COUNT=' + str(len(C.settings.BACKEND_CORS_ORIGINS)))"
    )
    completed = _RunConfigProbe(
        {"SECRET_KEY": VALID_SECRET_KEY, "BACKEND_CORS_ORIGINS": "http://a,https://b:8080"},
        probeCode,
        tmp_path,
    )
    assert completed.returncode == 0
    assert "ORIGIN_COUNT=2" in completed.stdout


# ---------------------------------------------------------------------------
# CONFIG-7 -- DEBUG is secure-by-default (False) when unset.
# ---------------------------------------------------------------------------


def test_debug_defaults_to_false_when_unset(tmp_path):
    probeCode = "import app.core.config as C; print('DEBUG=' + str(C.settings.DEBUG))"
    completed = _RunConfigProbe({"SECRET_KEY": VALID_SECRET_KEY}, probeCode, tmp_path)
    assert completed.returncode == 0
    assert "DEBUG=False" in completed.stdout


# ---------------------------------------------------------------------------
# CONFIG-6 -- secrets must never render in cleartext via repr/str/model_dump.
# ---------------------------------------------------------------------------


def test_secrets_are_not_rendered_in_cleartext(tmp_path):
    probeCode = (
        "import app.core.config as C; "
        "print(repr(C.settings)); "
        "print(str(C.settings)); "
        "print(C.settings.model_dump()); "
        "print(C.settings.model_dump(mode='json'))"
    )
    completed = _RunConfigProbe(
        {"SECRET_KEY": CANARY_SECRET_KEY, "DATABASE_URL": CANARY_DATABASE_URL},
        probeCode,
        tmp_path,
    )
    assert completed.returncode == 0
    renderedOutput = completed.stdout + completed.stderr
    assert CANARY_SECRET_KEY not in renderedOutput
    assert "canarydbpw" not in renderedOutput
    # The masked placeholder proves the SecretStr wrapper is engaged.
    assert "**********" in completed.stdout
