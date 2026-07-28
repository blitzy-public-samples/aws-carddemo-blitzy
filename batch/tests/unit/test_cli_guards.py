"""Unit tests for the Phase-10 batch CLI safety guards and configuration fixes.

DB-free regression tests that lock in four QA findings resolved in the batch
tier. Each test targets a small, pure seam so no live PostgreSQL is required
(real-DB parity lives in ``batch/tests/integration/``):

* **I6** -- the destructive-database guard must honor the CANONICAL, documented
  ``CARDDEMO_REQUIRE_TEST_DB`` variable AND the legacy ``BATCH_REQUIRE_TEST_DB``
  alias, and must refuse a non-``_test`` database while allowing a ``_test`` one.
* **I7** -- standalone ``load init-users`` must make the backend ``SECRET_KEY``
  discoverable (via :func:`EnsureBackendSecretAvailable`) BEFORE opening its
  session, mirroring the full-chain preflight, so it works on its own.
* **I15** -- the seed ``--data-dir`` default must be overridable through
  ``CARDDEMO_DATA_DIR`` and, when the directory is missing, produce a clear,
  actionable error instead of a confusing ``site-packages`` path.
* **I19** -- a malformed ``tran_amt`` in a restore CSV must surface as a clean
  :class:`ValueError`, never a raw :class:`decimal.InvalidOperation`.

Synchronous; stdlib + pytest + unittest.mock only.
"""

from decimal import Decimal, InvalidOperation
from pathlib import Path
from unittest.mock import Mock, patch

import pytest

import batch.cli as batch_cli
import batch.db as batch_db
from batch.jobs.backup_tran import CSV_HEADER, _BuildRestoreValues

# A canonical, well-formed restore row (all CSV_HEADER columns present). Individual
# tests copy this and mutate a single field so the fixture stays single-sourced.
_VALID_RESTORE_ROW = {
    "tran_id": "9999999999999999",
    "tran_type_cd": "01",
    "tran_cat_cd": "05",
    "tran_source": "POS",
    "tran_desc": "unit-test restore row",
    "tran_amt": "123.45",
    "merchant_id": "000000001",
    "merchant_name": "TEST MERCHANT",
    "merchant_city": "TESTCITY",
    "merchant_zip": "00000",
    "card_num": "9680294154603697",
    "orig_ts": "",
    "proc_ts": "",
    "status": "POSTED",
}


# --------------------------------------------------------------------------- #
# I6 -- destructive-database guard: canonical + legacy env var, refuse/allow.
# --------------------------------------------------------------------------- #
def test_guard_enabled_by_canonical_env_var(monkeypatch):
    """The documented CARDDEMO_REQUIRE_TEST_DB=true enables the guard (I6)."""
    monkeypatch.delenv(batch_db.LEGACY_REQUIRE_TEST_DB_ENV_VAR, raising=False)
    monkeypatch.setenv(batch_db.REQUIRE_TEST_DB_ENV_VAR, "true")
    assert batch_db._IsTestDbGuardEnabled() is True


def test_guard_enabled_by_legacy_alias(monkeypatch):
    """The legacy BATCH_REQUIRE_TEST_DB alias still enables the guard (I6)."""
    monkeypatch.delenv(batch_db.REQUIRE_TEST_DB_ENV_VAR, raising=False)
    monkeypatch.setenv(batch_db.LEGACY_REQUIRE_TEST_DB_ENV_VAR, "1")
    assert batch_db._IsTestDbGuardEnabled() is True


def test_guard_disabled_when_neither_env_var_set(monkeypatch):
    """With neither name set (nor truthy) the guard stays OFF (default-safe)."""
    monkeypatch.delenv(batch_db.REQUIRE_TEST_DB_ENV_VAR, raising=False)
    monkeypatch.delenv(batch_db.LEGACY_REQUIRE_TEST_DB_ENV_VAR, raising=False)
    assert batch_db._IsTestDbGuardEnabled() is False
    # A non-truthy value must NOT enable it either.
    monkeypatch.setenv(batch_db.REQUIRE_TEST_DB_ENV_VAR, "false")
    assert batch_db._IsTestDbGuardEnabled() is False


def test_guard_refuses_non_test_database_and_names_canonical_var(monkeypatch):
    """Enabled guard + non-``_test`` DB name raises, naming the canonical var (I6)."""
    monkeypatch.setenv(batch_db.REQUIRE_TEST_DB_ENV_VAR, "yes")
    with patch("batch.db.ENGINE") as mockEngine:
        mockEngine.url.database = "carddemo"
        with pytest.raises(ValueError) as excInfo:
            batch_db._AssertTestDatabase()
    # The diagnostic surfaces the documented canonical name, not the legacy alias.
    assert batch_db.REQUIRE_TEST_DB_ENV_VAR in str(excInfo.value)
    assert "Refusing to run" in str(excInfo.value)


def test_guard_allows_test_database(monkeypatch):
    """Enabled guard + a ``_test`` DB name proceeds without raising (I6)."""
    monkeypatch.setenv(batch_db.REQUIRE_TEST_DB_ENV_VAR, "on")
    with patch("batch.db.ENGINE") as mockEngine:
        mockEngine.url.database = "carddemo_test"
        # Must not raise.
        batch_db._AssertTestDatabase()


def test_guard_disabled_is_a_noop_even_for_non_test_db(monkeypatch):
    """A disabled guard never blocks, regardless of the target DB name (I6)."""
    monkeypatch.delenv(batch_db.REQUIRE_TEST_DB_ENV_VAR, raising=False)
    monkeypatch.delenv(batch_db.LEGACY_REQUIRE_TEST_DB_ENV_VAR, raising=False)
    with patch("batch.db.ENGINE") as mockEngine:
        mockEngine.url.database = "carddemo"
        # Guard OFF -> no-op even against a production-looking name.
        batch_db._AssertTestDatabase()


# --------------------------------------------------------------------------- #
# I15 -- seed data directory: env override + clear missing-directory message.
# --------------------------------------------------------------------------- #
def test_require_data_dir_returns_existing_directory(tmp_path):
    """An existing directory is returned unchanged (I15)."""
    assert batch_cli._RequireDataDir(tmp_path) == tmp_path


def test_require_data_dir_missing_raises_actionable_error(tmp_path):
    """A missing directory raises a clear, actionable ValueError (I15)."""
    missingDir = tmp_path / "no_such_seed_dir"
    with pytest.raises(ValueError) as excInfo:
        batch_cli._RequireDataDir(missingDir)
    message = str(excInfo.value)
    # The message must tell the operator exactly how to proceed.
    assert "--data-dir" in message
    assert batch_cli.DATA_DIR_ENV_VAR in message
    assert str(missingDir) in message


def test_compute_default_data_dir_honors_env_override(monkeypatch, tmp_path):
    """CARDDEMO_DATA_DIR overrides the repo-relative default (I15)."""
    monkeypatch.setenv(batch_cli.DATA_DIR_ENV_VAR, str(tmp_path))
    assert batch_cli._ComputeDefaultDataDir() == Path(str(tmp_path))


def test_compute_default_data_dir_falls_back_to_repo_path(monkeypatch):
    """Without the override, the default is the repo-relative app/data/ASCII (I15)."""
    monkeypatch.delenv(batch_cli.DATA_DIR_ENV_VAR, raising=False)
    computed = batch_cli._ComputeDefaultDataDir()
    assert computed.parts[-3:] == ("app", "data", "ASCII")


# --------------------------------------------------------------------------- #
# I7 -- standalone init-users resolves SECRET_KEY before opening its session.
# --------------------------------------------------------------------------- #
def test_init_users_command_resolves_secret_before_session():
    """``load init-users`` calls EnsureBackendSecretAvailable BEFORE the session (I7)."""
    callOrder = []

    def _recordEnsure():
        callOrder.append("ensure")

    def _enterSession():
        callOrder.append("session")
        return Mock()

    sessionContext = Mock()
    sessionContext.__enter__ = Mock(side_effect=_enterSession)
    sessionContext.__exit__ = Mock(return_value=False)

    with patch(
        "batch.cli.EnsureBackendSecretAvailable", side_effect=_recordEnsure
    ) as ensureMock, patch(
        "batch.cli.GetSyncSession", return_value=sessionContext
    ), patch(
        "batch.cli.InitializeUsers", return_value=10
    ) as initMock:
        batch_cli.InitializeUsersCommand()

    ensureMock.assert_called_once()
    initMock.assert_called_once()
    # The secret must be resolved BEFORE the database session is opened.
    assert callOrder == ["ensure", "session"]


# --------------------------------------------------------------------------- #
# I19 -- malformed restore amount surfaces as a clean ValueError.
# --------------------------------------------------------------------------- #
def test_build_restore_values_valid_amount_is_decimal():
    """A well-formed tran_amt is parsed to an exact Decimal (never float) (I19)."""
    values = _BuildRestoreValues(dict(_VALID_RESTORE_ROW))
    assert values["tran_amt"] == Decimal("123.45")
    assert isinstance(values["tran_amt"], Decimal)


def test_build_restore_values_malformed_amount_raises_value_error():
    """A malformed tran_amt raises ValueError, not decimal.InvalidOperation (I19)."""
    badRow = dict(_VALID_RESTORE_ROW)
    badRow["tran_amt"] = "NOT_A_DECIMAL"
    with pytest.raises(ValueError) as excInfo:
        _BuildRestoreValues(badRow)
    # Regression guard: it must be a ValueError (caught by the CLI boundary), and
    # specifically NOT a raw decimal.InvalidOperation (which would escape as a
    # traceback). InvalidOperation is not a subclass of ValueError.
    assert not isinstance(excInfo.value, InvalidOperation)
    assert "tran_amt" in str(excInfo.value)


def test_csv_header_contract_unchanged():
    """The restore parser keys off CSV_HEADER; guard its stability for the tests."""
    assert "tran_amt" in CSV_HEADER
    assert set(_VALID_RESTORE_ROW) == set(CSV_HEADER)
