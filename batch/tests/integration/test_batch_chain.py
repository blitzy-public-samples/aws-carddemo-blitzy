# Full-chain golden-master integration test for the CardDemo batch chain.
#
# Provenance / traceability (Ochs Rule -- ported unit references its origin):
#   * Legacy job chain order: README.md [L165-183] and AAP 0.7.6.
#   * Orchestrator under test: batch.orchestration.batch_chain (the modern,
#     greenfield replacement for the legacy JCL batch chain; see app/jcl/*.jcl,
#     e.g. POSTTRAN.jcl -> CBTRN02C posting, INTCALC.jcl -> CBACT04C interest).
#
# This module reconciles batch.orchestration.batch_chain against that legacy
# order. It verifies that RunBatchChain executes every step in the EXACT legacy
# sequence, is STRUCTURALLY idempotent, records the quiesce/index no-op steps,
# and -- critically -- documents and enforces the FK-ORDER CAVEAT (the chain
# loads XREFFILE before CUSTFILE, so it assumes an already-seeded database).
"""Golden-master integration tests for the CardDemo batch orchestration chain.

These tests drive :func:`batch.orchestration.batch_chain.RunBatchChain` end to
end against a real PostgreSQL 17 test database (``carddemo_test``) and reconcile
its behavior with the legacy JCL job chain documented in the legacy ``README.md``
[L165-183] and AAP 0.7.6.

Synchronous only: the batch layer is blocking (``psycopg2``); this module never
imports the async FastAPI request-layer machinery.

FK-ORDER CAVEAT (the central contract these tests encode):
    ``RunBatchChain``'s :data:`~batch.orchestration.batch_chain.CHAIN_STEPS`
    preserves the legacy README order exactly, which loads ``XREFFILE`` (step 4)
    BEFORE ``CUSTFILE`` (step 5). Because ``card_xref.cust_id`` carries a hard
    foreign key to ``customers.cust_id``, running the chain against a fresh /
    unseeded database fails at the ``XREFFILE`` step. Therefore every full-chain
    test here MUST call :func:`~batch.orchestration.batch_chain.SeedAll` first
    (the foreign-key-safe :data:`~batch.orchestration.batch_chain.SEED_STEPS`
    order, functionally the root ``seeded_db`` fixture) and only THEN call
    ``RunBatchChain`` -- injecting the conftest ``session_factory`` into BOTH so
    every step shares the one rolled-back test transaction. The negative test
    :func:`test_chain_on_unseeded_db_raises_fk_order_error` locks this caveat in.

Idempotency here is STRUCTURAL only. ``INTCALC`` legitimately re-accrues interest
on a second run (legacy-parity behavior), so monetary state is NOT re-run-stable;
these tests assert an identical ``completedSteps`` sequence, never identical
balances or transaction counts.
"""

import pytest
from sqlalchemy import func, select

from app.models import Transaction
from batch.orchestration.batch_chain import (
    CHAIN_STEPS,
    SEED_STEPS,
    BatchChainError,
    ChainResult,
    RunBatchChain,
    SeedAll,
)

# --------------------------------------------------------------------------- #
# Module constants (ALL_UPPERCASE per the Ochs constant rule). The expected
# chain order is transcribed verbatim from README.md [L165-183] / AAP 0.7.6.
# TRANBKP intentionally appears twice (a pre-post snapshot and a post-interest
# snapshot), so the list has 17 entries.
# --------------------------------------------------------------------------- #
EXPECTED_CHAIN_STEPS = [
    "CLOSEFIL", "ACCTFILE", "CARDFILE", "XREFFILE", "CUSTFILE",
    "TRANBKP", "DISCGRP", "TCATBALF", "TRANTYPE", "DUSRSECJ",
    "POSTTRAN", "INTCALC", "TRANBKP", "COMBTRAN", "CREASTMT",
    "TRANIDX", "OPENFIL",
]
EXPECTED_CHAIN_STEP_COUNT = 17
# 10 FK-safe seed steps: one loader per table in dependency order, ending with
# the user seed, so SeedAll bootstraps a fresh database without violating any
# foreign-key constraint.
EXPECTED_SEED_STEP_COUNT = 10
NOOP_STEP_NAMES = ("CLOSEFIL", "OPENFIL", "TRANIDX")
FK_ORDER_FAILING_STEP = "XREFFILE"


# --------------------------------------------------------------------------- #
# Module-level helper (PascalCase per the Ochs method rule).
# --------------------------------------------------------------------------- #
def _CountTransactions(session):
    """Count rows in the transactions table within the current transaction.

    Args:
        session: The isolated, transaction-scoped SQLAlchemy ORM session.

    Returns:
        The number of ``transactions`` rows visible in the current transaction.
    """
    return session.execute(
        select(func.count()).select_from(Transaction)
    ).scalar_one()


def test_full_chain_completes_in_expected_order(session_factory, db_session):
    """Run the full chain and assert the exact legacy 17-step order executed.

    Cites the legacy ``README.md`` [L165-183] / AAP 0.7.6 chain order.

    FK-ORDER CAVEAT: ``CHAIN_STEPS`` loads ``XREFFILE`` (step 4) before
    ``CUSTFILE`` (step 5), and ``card_xref.cust_id`` is a hard FK to
    ``customers.cust_id``. On a fresh database the chain would therefore abort at
    ``XREFFILE``; so this test seeds first (FK-safe ``SEED_STEPS`` via
    :func:`SeedAll`, equivalent to the root ``seeded_db`` fixture) and only then
    runs the chain. Injecting ``session_factory`` into BOTH keeps every step on
    the single rolled-back test transaction.
    """
    # Seed FIRST (FK-safe SEED_STEPS == seeded_db), then run the chain -- both on
    # the shared, rolled-back transaction via the injected session_factory.
    SeedAll(sessionFactory=session_factory)
    result = RunBatchChain(sessionFactory=session_factory)

    assert isinstance(result, ChainResult)
    # Exact legacy order, 17 entries with TRANBKP appearing twice.
    assert result.completedSteps == EXPECTED_CHAIN_STEPS

    # The chain actually executed against seeded data: POSTTRAN/INTCALC produced
    # rows, so the transactions table is non-empty. expire_all() drops any
    # identity-map caching so the count reflects the flushed chain writes.
    db_session.expire_all()
    assert _CountTransactions(db_session) > 0


def test_chain_and_seed_step_definitions_match_contract():
    """Assert the orchestration step definitions match the structural contract.

    Cites AAP 0.7.6 (the structural contract of the orchestration module: the
    17-step legacy chain and the 10-step FK-safe seed order).
    """
    # CHAIN_STEPS encodes the 17-step legacy README chain (TRANBKP twice).
    assert len(CHAIN_STEPS) == EXPECTED_CHAIN_STEP_COUNT
    # SEED_STEPS encodes the 10-table FK-safe seed order.
    assert len(SEED_STEPS) == EXPECTED_SEED_STEP_COUNT
    # The local expectation list mirrors the same 17-step contract.
    assert len(EXPECTED_CHAIN_STEPS) == EXPECTED_CHAIN_STEP_COUNT
    # Backup runs twice: before posting (step 6) AND after interest (step 13).
    assert EXPECTED_CHAIN_STEPS.count("TRANBKP") == 2


def test_chain_is_idempotent(session_factory):
    """Assert a re-run yields an identical step sequence (structural idempotency).

    Cites AAP 0.7.6 (the chain is re-runnable -> identical step sequence). Only
    the step sequence is asserted stable; balances are NOT (see comment below).
    """
    # Seed once (FK-safe), then run the chain twice on the shared transaction.
    SeedAll(sessionFactory=session_factory)
    firstResult = RunBatchChain(sessionFactory=session_factory)
    secondResult = RunBatchChain(sessionFactory=session_factory)

    assert firstResult.completedSteps == EXPECTED_CHAIN_STEPS
    assert secondResult.completedSteps == firstResult.completedSteps
    # Do NOT assert identical balances or transaction counts across the re-run:
    # INTCALC legitimately re-accrues interest on the second run (expected
    # legacy-parity behavior), so monetary state is NOT re-run-stable. Only the
    # completed-step sequence is asserted stable here (structural idempotency).


def test_chain_records_noop_steps(session_factory):
    """Assert the quiesce/index guard steps are recorded in the chain outcome.

    Cites AAP 0.7.6: ``CLOSEFIL``/``OPENFIL`` are legacy IEFBR14 file-quiesce
    no-ops and ``TRANIDX`` is the alternate-index rebuild that PostgreSQL keeps
    automatically (executed here as an ``ANALYZE`` guard). All three still appear
    in the completed-step record.
    """
    # Seed first (FK-safe), then run the full chain on the shared transaction.
    SeedAll(sessionFactory=session_factory)
    result = RunBatchChain(sessionFactory=session_factory)

    # Every guard/no-op step is still recorded as a completed step.
    for noopName in NOOP_STEP_NAMES:
        assert noopName in result.completedSteps


def test_chain_on_unseeded_db_raises_fk_order_error(session_factory):
    """Assert the chain aborts at XREFFILE on a fresh, unseeded database.

    Cites AAP 0.7.6 FK-ORDER CAVEAT. This runs the chain WITHOUT seeding first
    (no :func:`SeedAll`), so the shared ``db_session`` transaction is empty.

    ``CLOSEFIL`` (step 1) is a no-op; ``ACCTFILE`` (step 2) and ``CARDFILE``
    (step 3) load fine (``accounts.group_id`` is a nullable logical reference,
    not a hard FK, and ``cards.acct_id`` targets the just-loaded ``accounts``).
    ``XREFFILE`` (step 4), however, inserts ``card_xref`` rows whose ``cust_id``
    foreign key targets ``customers`` -- which are not loaded until ``CUSTFILE``
    (step 5) -- so the chain aborts at ``XREFFILE``. This is precisely why the
    other tests seed first.
    """
    with pytest.raises(BatchChainError) as excInfo:
        RunBatchChain(sessionFactory=session_factory)

    # The failing step is XREFFILE (it loads before CUSTFILE in the legacy order).
    assert excInfo.value.stepName == FK_ORDER_FAILING_STEP
    # The wrapped, specific underlying error (the FK IntegrityError) is preserved.
    assert excInfo.value.originalError is not None
