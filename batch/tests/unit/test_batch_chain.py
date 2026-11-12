"""Unit tests for batch.orchestration.batch_chain (JCL chain order + wiring).

Reconciles the ported batch orchestrator against the legacy JCL chain sequence
(README.md L165-183 / AAP 0.7.6) and the FK-safe seed order. DB-free: a mocked
sessionFactory (a @contextmanager yielding a unittest.mock.Mock session) drives
the chain, so no live database is required (real-DB parity lives in integration/).
Synchronous; stdlib + sqlalchemy.exc only; never imports app.db.session.
"""

import dataclasses
import os
from contextlib import contextmanager
from unittest.mock import Mock, patch

import pytest
from sqlalchemy.exc import SQLAlchemyError

import batch.config as batch_config
import batch.orchestration.batch_chain as batch_chain

# Contractual legacy chain order (README.md L165-183 / AAP 0.7.6): TRANBKP twice; TRANCATG absent.
EXPECTED_CHAIN_STEPS = (
    "CLOSEFIL", "ACCTFILE", "CARDFILE", "XREFFILE", "CUSTFILE", "TRANBKP",
    "DISCGRP", "TCATBALF", "TRANTYPE", "DUSRSECJ", "POSTTRAN", "INTCALC",
    "TRANBKP", "COMBTRAN", "CREASTMT", "TRANIDX", "OPENFIL",
)

# FK-safe seed order (batch/loaders contract): parents before children, users last.
EXPECTED_SEED_STEPS = (
    "disclosure_group", "customers", "accounts", "cards",
    "card_xref", "transaction_type", "transaction_category",
    "tran_category_balance", "transactions", "users",
)


def _StepName(step):
    """Return a chain step's legacy job name, tolerant of field-name adaptation."""
    for attrName in ("jobName", "job_name", "name", "stepName", "step_name"):
        candidate = getattr(step, attrName, None)
        if isinstance(candidate, str) and candidate:
            return candidate
    if isinstance(step, str):
        return step
    if dataclasses.is_dataclass(step):
        for stepField in dataclasses.fields(step):
            fieldValue = getattr(step, stepField.name, None)
            if isinstance(fieldValue, str) and fieldValue:
                return fieldValue
    return str(step)


def _StepNames(steps):
    """Materialize the ordered tuple of job names for a step sequence."""
    return tuple(_StepName(step) for step in steps)


def _ActionFieldName(step):
    """Find the ChainStep field holding the callable action (None for guard steps)."""
    if not dataclasses.is_dataclass(step):
        return None
    for stepField in dataclasses.fields(step):
        fieldValue = getattr(step, stepField.name, None)
        if fieldValue is None or callable(fieldValue):
            return stepField.name
    return None


def _NeutralizeSteps(steps, overrides=None):
    """Rebuild `steps` with real actions replaced by Mocks (guard steps preserved).

    NOOP/ANALYZE steps (action is None) are kept as-is so their real branches run
    against the mock session. `overrides` maps jobName -> replacement action so an
    error case can inject a Mock(side_effect=...).
    """
    overrides = overrides or {}
    actionFieldName = _ActionFieldName(steps[0])
    if actionFieldName is None:
        pytest.skip("Cannot locate the action field on ChainStep; cannot neutralize")
    rebuiltSteps = []
    for step in steps:
        jobName = _StepName(step)
        currentAction = getattr(step, actionFieldName)
        if jobName in overrides:
            rebuiltSteps.append(
                dataclasses.replace(step, **{actionFieldName: overrides[jobName]})
            )
        elif currentAction is not None:
            rebuiltSteps.append(
                dataclasses.replace(step, **{actionFieldName: Mock(return_value=None)})
            )
        else:
            rebuiltSteps.append(step)
    return tuple(rebuiltSteps)


def _MakeMockFactory():
    """Return (sessionFactory, mockSession); factory is a contextmanager yielding one session."""
    mockSession = Mock()

    @contextmanager
    def _SessionFactory():
        yield mockSession

    return _SessionFactory, mockSession


def _RequireDataclassSteps():
    """Skip execution tests if steps are not dataclasses (cannot neutralize their actions)."""
    if not dataclasses.is_dataclass(batch_chain.CHAIN_STEPS[0]):
        pytest.skip("CHAIN_STEPS entries are not dataclasses; cannot neutralize actions")


def test_chain_step_count():
    # AAP 0.7.6: the chain has exactly 17 ordered steps.
    assert len(batch_chain.CHAIN_STEPS) == 17


def test_chain_steps_order_matches_readme():
    # README.md L165-183 / AAP 0.7.6: exact 17-step order (TRANBKP appears twice).
    assert _StepNames(batch_chain.CHAIN_STEPS) == EXPECTED_CHAIN_STEPS


def test_chain_steps_tranbkp_twice_and_no_trancatg():
    # TRANBKP occurs exactly twice; TRANCATG is NOT a chain step (it is seed-only).
    chainNames = _StepNames(batch_chain.CHAIN_STEPS)
    assert chainNames.count("TRANBKP") == 2
    assert "TRANCATG" not in chainNames


def test_seed_step_count():
    # FK-safe seed order has exactly 10 loader steps: one per table in dependency
    # order, ending with the user seed.
    assert len(batch_chain.SEED_STEPS) == 10


def test_seed_steps_fk_safe_order():
    # FK-safe seed order: disclosure_group -> ... -> users (users seeded last).
    assert _StepNames(batch_chain.SEED_STEPS) == EXPECTED_SEED_STEPS


def test_step_kind_enum_has_core_members():
    # StepKind is part of the module's public surface (guard vs data/analyze steps).
    stepKind = getattr(batch_chain, "StepKind", None)
    if stepKind is None:
        pytest.skip("StepKind is not part of the implemented public surface")
    memberNames = {member.name for member in stepKind}
    assert {"NOOP", "ANALYZE"}.issubset(memberNames)


def test_run_batch_chain_completes_all_steps_in_order():
    # AAP 0.7.6: RunBatchChain runs every step in order; completedSteps mirrors the chain.
    _RequireDataclassSteps()
    neutralizedSteps = _NeutralizeSteps(batch_chain.CHAIN_STEPS)
    sessionFactory, _mockSession = _MakeMockFactory()
    with patch.object(batch_chain, "CHAIN_STEPS", neutralizedSteps):
        chainResult = batch_chain.RunBatchChain(sessionFactory=sessionFactory)
    assert list(chainResult.completedSteps) == list(EXPECTED_CHAIN_STEPS)


def test_run_batch_chain_only_analyze_touches_session():
    # CLOSEFIL/OPENFIL are log-only no-ops (no session work); TRANIDX issues ANALYZE.
    _RequireDataclassSteps()
    neutralizedSteps = _NeutralizeSteps(batch_chain.CHAIN_STEPS)
    sessionFactory, mockSession = _MakeMockFactory()
    with patch.object(batch_chain, "CHAIN_STEPS", neutralizedSteps):
        batch_chain.RunBatchChain(sessionFactory=sessionFactory)
    # Among real (non-neutralized) branches only the ANALYZE guard calls execute().
    assert mockSession.execute.call_count == 1
    executedSql = str(mockSession.execute.call_args.args[0])
    assert "ANALYZE" in executedSql.upper()


def test_run_batch_chain_wraps_step_failure():
    # Ochs specific-exception handling: a step raising SQLAlchemyError becomes a
    # BatchChainError carrying the failing job name + original error (AAP 0.7.6 abort-on-failure).
    _RequireDataclassSteps()
    injectedError = SQLAlchemyError("boom")
    overrides = {"ACCTFILE": Mock(side_effect=injectedError)}
    neutralizedSteps = _NeutralizeSteps(batch_chain.CHAIN_STEPS, overrides=overrides)
    sessionFactory, _mockSession = _MakeMockFactory()
    with patch.object(batch_chain, "CHAIN_STEPS", neutralizedSteps):
        with pytest.raises(batch_chain.BatchChainError) as excInfo:
            batch_chain.RunBatchChain(sessionFactory=sessionFactory)
    assert excInfo.value.stepName == "ACCTFILE"
    assert isinstance(excInfo.value.originalError, SQLAlchemyError)


def test_seed_all_runs_every_loader():
    # SeedAll runs the 10 FK-safe loaders (each in its own transaction) and returns None.
    _RequireDataclassSteps()
    neutralizedSeed = _NeutralizeSteps(batch_chain.SEED_STEPS)
    sessionFactory, _mockSession = _MakeMockFactory()
    with patch.object(batch_chain, "SEED_STEPS", neutralizedSeed):
        seedResult = batch_chain.SeedAll(sessionFactory=sessionFactory)
    assert seedResult is None


# ---------------------------------------------------------------------------
# F-1: user-seed security preflight + CWD-independent SECRET_KEY hydration.
# The orchestrator must resolve/validate the backend SECRET_KEY BEFORE any step
# commits, so a misconfigured secret can never leave a non-atomic partial seed.
# ---------------------------------------------------------------------------

# A signing key comfortably above the backend MINIMUM_SECRET_KEY_LENGTH (32).
_VALID_KEY = "unit-test-secret-key-0123456789-abcdefghij"


def test_validate_user_seed_security_is_noop_without_user_step():
    # A step tuple with no InitializeUsers action must not touch the backend
    # secret at all: batch runs that never seed users stay free of SECRET_KEY.
    noUserSteps = (
        batch_chain.ChainStep("CLOSEFIL", batch_chain.StepKind.NOOP, None, "noop"),
        batch_chain.ChainStep(
            "ACCTFILE", batch_chain.StepKind.WITH_DATA_DIR, Mock(), "loader"
        ),
    )
    with patch.object(batch_chain, "EnsureBackendSecretAvailable") as ensureSpy:
        batch_chain._ValidateUserSeedSecurity(noUserSteps)
    ensureSpy.assert_not_called()


def test_validate_user_seed_security_hydrates_and_passes_with_user_step():
    # SEED_STEPS carries the real InitializeUsers action, so the preflight must
    # invoke the hydration helper and (SECRET_KEY present in the test env) pass.
    with patch.object(batch_chain, "EnsureBackendSecretAvailable") as ensureSpy:
        batch_chain._ValidateUserSeedSecurity(batch_chain.SEED_STEPS)
    ensureSpy.assert_called_once_with()


def test_validate_user_seed_security_raises_when_hash_unavailable():
    # When the backend security dependency cannot be satisfied, the preflight
    # raises BatchChainError naming the user step -- the abort that keeps the seed
    # atomic (no earlier step ever committed). Here the failure is forced by
    # replacing HashPassword with a non-callable so the preflight's own contract
    # check trips, standing in for the runtime "SECRET_KEY unset" path proven by
    # the F-1 runtime re-verification.
    import app.core.security as backendSecurity

    with patch.object(batch_chain, "EnsureBackendSecretAvailable"):
        with patch.object(backendSecurity, "HashPassword", None):
            with pytest.raises(batch_chain.BatchChainError) as excInfo:
                batch_chain._ValidateUserSeedSecurity(batch_chain.SEED_STEPS)
    assert excInfo.value.stepName == "users"


def test_ensure_backend_secret_respects_existing_env(tmp_path, monkeypatch):
    # An explicit SECRET_KEY in the environment must never be overwritten by the
    # .env file value (environment has the highest precedence).
    envFile = tmp_path / ".env"
    envFile.write_text("SECRET_KEY=file-value-should-not-win-000000000\n", encoding="utf-8")
    monkeypatch.setattr(batch_config, "_BACKEND_ENV_FILE", envFile)
    monkeypatch.setenv("SECRET_KEY", _VALID_KEY)
    batch_config.EnsureBackendSecretAvailable()
    assert os.environ["SECRET_KEY"] == _VALID_KEY


def test_ensure_backend_secret_hydrates_from_env_file_when_unset(tmp_path, monkeypatch):
    # With SECRET_KEY unset in the environment, the value is sourced from the
    # anchored backend/.env so a repo-root launch resolves it (QA finding F-1).
    envFile = tmp_path / ".env"
    envFile.write_text(f"SECRET_KEY={_VALID_KEY}\n", encoding="utf-8")
    monkeypatch.setattr(batch_config, "_BACKEND_ENV_FILE", envFile)
    monkeypatch.delenv("SECRET_KEY", raising=False)
    batch_config.EnsureBackendSecretAvailable()
    assert os.environ["SECRET_KEY"] == _VALID_KEY


def test_ensure_backend_secret_leaves_unset_when_env_file_missing(tmp_path, monkeypatch):
    # No env var and no .env file: the secret stays unset so the downstream
    # security import fails fast (which the preflight turns into an atomic abort).
    missingEnvFile = tmp_path / "nonexistent" / ".env"
    monkeypatch.setattr(batch_config, "_BACKEND_ENV_FILE", missingEnvFile)
    monkeypatch.delenv("SECRET_KEY", raising=False)
    batch_config.EnsureBackendSecretAvailable()
    assert "SECRET_KEY" not in os.environ
