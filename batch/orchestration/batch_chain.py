# CardDemo batch chain orchestrator.
# Reproduces the legacy JCL batch sequence (README.md L165-183 / AAP 0.7.6):
#   CLOSEFIL -> ACCTFILE -> CARDFILE -> XREFFILE -> CUSTFILE -> TRANBKP ->
#   DISCGRP -> TCATBALF -> TRANTYPE -> DUSRSECJ -> POSTTRAN -> INTCALC ->
#   TRANBKP -> COMBTRAN -> CREASTMT -> TRANIDX -> OPENFIL
# Each step maps to a legacy JCL job (app/jcl/*.jcl). CLOSEFIL/OPENFIL are CICS
# file-quiesce IEFBR14 no-ops; TRANIDX is a VSAM alternate-index rebuild that
# PostgreSQL maintains automatically (guard/ANALYZE). See app/proc/REPROC.prc.
#
# TRANBKP intentionally appears twice (steps 6 and 13): a pre-post snapshot and a
# post-interest snapshot, mirroring the legacy JCL. TRANCATG is NOT part of this
# online chain -- it is only exercised by SeedAll when bootstrapping a fresh DB.
"""Ordered, per-step-transactional orchestration of the CardDemo batch chain.

This module is the modern, greenfield replacement for the legacy JCL batch job
chain. It does not re-implement any business logic: it imports the verified
entry functions from the sibling ``batch/jobs`` and ``batch/loaders`` packages
and the transactional session factory from :mod:`batch.db`, then drives them in
the exact legacy order.

Design (declarative, DRY, Ochs-compliant):
    A single immutable :data:`CHAIN_STEPS` tuple of :class:`ChainStep`
    descriptors encodes the contractual order. A tiny dispatch helper
    (:func:`_InvokeStepAction`) selects the correct calling convention per step
    from its :class:`StepKind`, and :func:`_RunStep` wraps every step in its own
    ``with sessionFactory() as session:`` unit of work. The same machinery backs
    :func:`SeedAll` via :data:`SEED_STEPS`, so there is exactly one execution
    path and no per-step wrapper-function sprawl.

Transaction boundary:
    Each step opens and owns exactly one transaction (one commit boundary per
    step), mirroring the legacy per-JCL-step semantics: a failed step rolls back
    only its own work. Entry functions never commit or close;
    :func:`batch.db.GetSyncSession` does that for them.

Re-runnability (AAP 0.7.6; QA finding F-6):
    The posting/balance layer is re-runnable without corruption -- the loaders
    preserve ledger-owned running balances (they never reset ``curr_bal``, the
    cycle totals, or the ``tran_category_balance`` balance on a re-load), and the
    posting job marks rejects terminally (``REJECTED``) so no daily row is posted
    twice or re-attempted. Interest, by contrast, RE-ACCRUES on every run by
    design (AAP 0.7.2 / F-5, faithful to CBACT04C), so a full-chain re-run is NOT
    a pure no-op; reseed a fresh database via :func:`SeedAll` for reconcilable
    golden-master output.

Public API:
    * :func:`RunBatchChain` -- run the full 17-step chain in legacy order.
    * :func:`SeedAll` -- seed every table in foreign-key-safe order.
    * :class:`ChainResult` -- printable ordered outcome of a chain run.
    * :class:`BatchChainError` -- raised (with the failing legacy job name) when
      a step fails; it aborts the chain.

Schema ownership:
    This module never issues DDL. Alembic owns all schema
    (``backend/alembic/versions/*``). The only SQL emitted directly here is a
    lightweight ``ANALYZE`` for the TRANIDX guard.
"""

from __future__ import annotations

import enum
import logging
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Callable, Optional

from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from batch.config import EnsureBackendSecretAvailable
from batch.db import FormatConciseError, GetSyncSession
from batch.jobs.backup_tran import BackupTransactions
from batch.jobs.combine_tran import CombineTransactions
from batch.jobs.interest_calc import CalculateInterest
from batch.jobs.post_transactions import PostTransactions
from batch.jobs.statement_gen import GenerateStatements
from batch.loaders.init_users import InitializeUsers
from batch.loaders.load_accounts import LoadAccounts
from batch.loaders.load_cards import LoadCards
from batch.loaders.load_customers import LoadCustomers
from batch.loaders.load_disclosure_groups import LoadDisclosureGroups
from batch.loaders.load_tcatbal import LoadTranCategoryBalances
from batch.loaders.load_tran_categories import LoadTranCategories
from batch.loaders.load_tran_types import LoadTranTypes
from batch.loaders.load_transactions import LoadTransactions
from batch.loaders.load_xref import LoadCardXref

# Module logger. Conventionally uppercase to satisfy the Ochs constant rule; the
# CLI/application configures handlers -- this module only emits records.
LOGGER = logging.getLogger(__name__)

__all__ = [
    "RunBatchChain",
    "SeedAll",
    "ChainResult",
    "ChainStep",
    "ChainContext",
    "StepKind",
    "BatchChainError",
    "CHAIN_STEPS",
    "SEED_STEPS",
]


class StepKind(enum.Enum):
    """How a chain step's entry function is invoked (which context arg it gets).

    The chain mixes several calling conventions: some steps are pure guards with
    no job function, some take only the ``session``, and the rest take exactly
    one run-scoped keyword (``dataDir``, ``runDate`` or ``outputDir``). Encoding
    that convention as an enum keeps :data:`CHAIN_STEPS` declarative and confines
    all dispatch logic to :func:`_InvokeStepAction`.
    """

    NOOP = "noop"
    ANALYZE = "analyze"
    SESSION_ONLY = "session_only"
    WITH_DATA_DIR = "with_data_dir"
    WITH_RUN_DATE = "with_run_date"
    WITH_OUTPUT_DIR = "with_output_dir"


class BatchChainError(RuntimeError):
    """Raised when a chain step fails; carries the failed legacy job name.

    Attributes:
        stepName: The legacy JCL job name of the step that failed (for example
            ``"POSTTRAN"``), so callers and logs can pinpoint the failure.
        originalError: The specific underlying exception that triggered the
            failure, preserved for diagnosis (also chained via ``raise ... from``).
    """

    def __init__(self, stepName: str, originalError: Exception) -> None:
        """Store the failing step name and its cause, then build the message.

        Args:
            stepName: Legacy job name of the failing step.
            originalError: The specific exception raised by the step.
        """
        self.stepName = stepName
        self.originalError = originalError
        super().__init__(
            f"Batch chain step '{stepName}' failed: {originalError}"
        )


@dataclass(frozen=True)
class ChainStep:
    """One ordered chain step: legacy job name, how to invoke it, description.

    Attributes:
        jobName: The legacy JCL job name (also used as the SeedAll table label).
        stepKind: The :class:`StepKind` that selects the calling convention.
        action: The entry callable to invoke, or ``None`` for guard steps
            (:data:`StepKind.NOOP` / :data:`StepKind.ANALYZE`).
        description: A short human-readable summary for logging.
    """

    jobName: str
    stepKind: StepKind
    action: Optional[Callable]
    description: str


@dataclass
class ChainContext:
    """Run-scoped parameters threaded to each step (keeps helpers <= 4 params).

    Bundling the run parameters into one object is exactly what keeps every
    helper at or below the Ochs four-parameter limit: steps receive this single
    ``context`` rather than a growing list of positional arguments.

    Attributes:
        runDate: Optional run date passed straight through to POSTTRAN/INTCALC;
            those jobs own parsing/defaulting. May be a ``date`` or a ``str``
            (the CLI may pass a string) -- this module never inspects it.
        dataDir: Optional data directory for the loaders, or ``None`` so each
            loader falls back to its own default.
        outputDir: Optional output directory for TRANBKP/CREASTMT artifacts.
    """

    runDate: Optional[date] = None
    dataDir: Optional[Path] = None
    outputDir: Optional[Path] = None


@dataclass
class ChainResult:
    """Ordered outcome of a full chain run (note: TRANBKP appears twice).

    Attributes:
        completedSteps: Legacy job names in execution order. Because TRANBKP runs
            twice, ``"TRANBKP"`` appears twice in this list.
        stepResults: ``(jobName, returnValue)`` pairs in execution order, where
            ``returnValue`` is whatever the step's entry function returned
            (row counts, result objects, or ``None`` for guard steps).
    """

    completedSteps: list = field(default_factory=list)
    stepResults: list = field(default_factory=list)

    def __str__(self) -> str:
        """Render an ordered, human-readable summary for CLI echoing.

        ``batch/cli.py`` echoes the chain outcome, so this produces a compact,
        one-line-per-step report rather than the default dataclass ``repr``.
        """
        if not self.stepResults:
            return "ChainResult(no steps run)"
        summaryLines = [
            f"ChainResult({len(self.completedSteps)} steps completed):"
        ]
        for stepIndex, (jobName, stepValue) in enumerate(
            self.stepResults, start=1
        ):
            # Each stepValue renders only a safe summary: the POSTTRAN step's
            # PostingResult exposes counts and an opaque reject-sink path, never
            # reject-record content (QA finding #28).
            summaryLines.append(f"  {stepIndex:>2}. {jobName} -> {stepValue}")
        return "\n".join(summaryLines)


# Contractual batch chain order (README.md L165-183 / AAP 0.7.6). DO NOT REORDER,
# sort, or dedupe -- TRANBKP intentionally appears twice (steps 6 and 13).
CHAIN_STEPS = (
    ChainStep("CLOSEFIL", StepKind.NOOP, None,
              "Quiesce CICS files (legacy IEFBR14 no-op)"),
    ChainStep("ACCTFILE", StepKind.WITH_DATA_DIR, LoadAccounts,
              "Load account master"),
    ChainStep("CARDFILE", StepKind.WITH_DATA_DIR, LoadCards,
              "Load card master"),
    ChainStep("XREFFILE", StepKind.WITH_DATA_DIR, LoadCardXref,
              "Load card cross-reference"),
    ChainStep("CUSTFILE", StepKind.WITH_DATA_DIR, LoadCustomers,
              "Load customer master"),
    ChainStep("TRANBKP", StepKind.WITH_OUTPUT_DIR, BackupTransactions,
              "Backup transactions (pre-post snapshot)"),
    ChainStep("DISCGRP", StepKind.WITH_DATA_DIR, LoadDisclosureGroups,
              "Load disclosure groups"),
    ChainStep("TCATBALF", StepKind.WITH_DATA_DIR, LoadTranCategoryBalances,
              "Load transaction-category balances"),
    ChainStep("TRANTYPE", StepKind.WITH_DATA_DIR, LoadTranTypes,
              "Load transaction types"),
    ChainStep("DUSRSECJ", StepKind.SESSION_ONLY, InitializeUsers,
              "Initialize users (hashed passwords)"),
    ChainStep("POSTTRAN", StepKind.WITH_RUN_DATE, PostTransactions,
              "Post daily transactions"),
    ChainStep("INTCALC", StepKind.WITH_RUN_DATE, CalculateInterest,
              "Calculate interest"),
    ChainStep("TRANBKP", StepKind.WITH_OUTPUT_DIR, BackupTransactions,
              "Backup transactions (post-interest snapshot)"),
    ChainStep("COMBTRAN", StepKind.WITH_OUTPUT_DIR, CombineTransactions,
              "Combine transactions (sort by tran_id ascending) into master ledger file"),
    ChainStep("CREASTMT", StepKind.WITH_OUTPUT_DIR, GenerateStatements,
              "Generate statements (CSV + PDF)"),
    ChainStep("TRANIDX", StepKind.ANALYZE, None,
              "Index rebuild -> PostgreSQL ANALYZE guard"),
    ChainStep("OPENFIL", StepKind.NOOP, None,
              "Open CICS files (legacy IEFBR14 no-op)"),
)

# Foreign-key-safe seed order (per the batch/loaders contract). This reuses the
# ChainStep machinery. It deliberately differs from CHAIN_STEPS: the README chain
# loads XREFFILE before CUSTFILE, yet card_xref has an FK to customers, so on a
# FRESH/EMPTY database the chain order would violate that FK. RunBatchChain must
# preserve the legacy order exactly (it is contractual and assumes an ALREADY
# SEEDED database). The loaders are idempotent ON CONFLICT upserts that refresh
# static columns but PRESERVE ledger-owned running balances (accounts
# curr_bal/curr_cyc_credit/curr_cyc_debit and tcatbal balance), and the posting
# job marks rejects terminally (REJECTED) so they are never re-attempted; the
# posting/balance layer is therefore safe to re-run without double-counting or
# balance resets (AAP 0.7.6; QA finding F-6). Interest, however, RE-ACCRUES on
# every run by design (AAP 0.7.2 / F-5, faithful to CBACT04C fresh-generation
# semantics), so a full-chain re-run is NOT a pure no-op -- reseed via SeedAll
# for reconcilable golden-master results. SeedAll (FK-safe order below) is the
# correct entry point for bootstrapping a fresh database.
SEED_STEPS = (
    ChainStep("disclosure_group", StepKind.WITH_DATA_DIR, LoadDisclosureGroups,
              "Seed disclosure groups"),
    ChainStep("customers", StepKind.WITH_DATA_DIR, LoadCustomers,
              "Seed customers"),
    ChainStep("accounts", StepKind.WITH_DATA_DIR, LoadAccounts,
              "Seed accounts"),
    ChainStep("cards", StepKind.WITH_DATA_DIR, LoadCards,
              "Seed cards"),
    ChainStep("card_xref", StepKind.WITH_DATA_DIR, LoadCardXref,
              "Seed card cross-reference"),
    ChainStep("transaction_type", StepKind.WITH_DATA_DIR, LoadTranTypes,
              "Seed transaction types"),
    ChainStep("transaction_category", StepKind.WITH_DATA_DIR, LoadTranCategories,
              "Seed transaction categories"),
    ChainStep("tran_category_balance", StepKind.WITH_DATA_DIR,
              LoadTranCategoryBalances, "Seed transaction-category balances"),
    ChainStep("transactions", StepKind.WITH_DATA_DIR, LoadTransactions,
              "Seed transactions"),
    ChainStep("users", StepKind.SESSION_ONLY, InitializeUsers,
              "Seed users (EBCDIC decode, hashed passwords)"),
)


def _CoerceOptionalPath(pathValue):
    """Return ``pathValue`` as a ``Path``, or ``None`` to defer to a default.

    Shared by the ``dataDir`` (loader source) and ``outputDir`` (artifact
    destination) run-scoped parameters: both are optional and, when omitted,
    defer to a downstream default rather than a value hardcoded here (Ochs
    Rule #3).

    Args:
        pathValue: A path-like value or ``None``.

    Returns:
        A :class:`pathlib.Path`, or ``None`` when ``pathValue`` is ``None``.
    """
    if pathValue is None:
        return None
    return Path(pathValue)


def _InvokeStepAction(step: ChainStep, session: Session,
                      context: ChainContext) -> object:
    """Invoke one step's entry function with the correct context argument.

    Dispatches on :attr:`ChainStep.stepKind`. Guard steps (NOOP) do nothing;
    ANALYZE emits a lightweight ``ANALYZE`` (the only SQL this module issues);
    the remaining kinds call the step's ``action`` with exactly the one keyword
    argument that entry function expects.

    Args:
        step: The step descriptor being executed.
        session: The already-open transactional session for this step.
        context: Run-scoped parameters (runDate/dataDir/outputDir).

    Returns:
        Whatever the step's entry function returned, or ``None`` for guards.

    Raises:
        BatchChainError: If ``step.stepKind`` is not a recognized kind.
    """
    if step.stepKind is StepKind.NOOP:
        return None
    if step.stepKind is StepKind.ANALYZE:
        session.execute(text("ANALYZE"))
        return None
    if step.stepKind is StepKind.SESSION_ONLY:
        return step.action(session)
    if step.stepKind is StepKind.WITH_DATA_DIR:
        return step.action(session, dataDir=context.dataDir)
    if step.stepKind is StepKind.WITH_RUN_DATE:
        return step.action(session, runDate=context.runDate)
    if step.stepKind is StepKind.WITH_OUTPUT_DIR:
        return step.action(session, outputDir=context.outputDir)
    raise BatchChainError(
        step.jobName, ValueError(f"Unknown step kind {step.stepKind}")
    )


def _RunStep(step: ChainStep, context: ChainContext,
             sessionFactory: Callable) -> object:
    """Run one step in its own transaction; log boundaries; wrap failures.

    Opens exactly one ``with sessionFactory() as session:`` unit of work for the
    step (one commit boundary per step). On success the step's return value is
    logged and returned; on failure the specific error is logged and re-raised as
    a :class:`BatchChainError` carrying the failing job name, which aborts the
    chain.

    Args:
        step: The step descriptor to execute.
        context: Run-scoped parameters threaded to the step.
        sessionFactory: A callable returning a context manager that yields a
            transactional :class:`~sqlalchemy.orm.Session`. Defaults are injected
            by the public entry points; tests pass their own factory.

    Returns:
        The step's return value (row/result counts, a result object, or ``None``).

    Raises:
        BatchChainError: Wrapping any :class:`~sqlalchemy.exc.SQLAlchemyError`,
            :class:`OSError` (covers ``FileNotFoundError``) or :class:`ValueError`
            raised by the step. Only these specific exceptions are caught (Ochs).
    """
    LOGGER.info("START STEP %s - %s", step.jobName, step.description)
    try:
        with sessionFactory() as session:
            stepValue = _InvokeStepAction(step, session, context)
    except (SQLAlchemyError, OSError, ValueError) as stepError:
        # Log a concise diagnostic (FormatConciseError strips SQLAlchemy's
        # SQL/parameter dump) so the failure stays specific and never echoes
        # bound values such as card numbers (QA Finding D; AAP 0.7.8 masking).
        LOGGER.error("STEP %s FAILED: %s", step.jobName,
                     FormatConciseError(stepError))
        raise BatchChainError(step.jobName, stepError) from stepError
    # str(stepValue) here renders only a safe summary -- the POSTTRAN step's
    # PostingResult never exposes reject-record content/PANs (QA finding #28).
    LOGGER.info("END STEP %s -> %s", step.jobName, stepValue)
    return stepValue


def _ValidateUserSeedSecurity(steps) -> None:
    """Fail fast when a user-seed step is present but its security prereqs fail.

    The user-seed step (``InitializeUsers`` -- the :data:`SEED_STEPS` ``users``
    step and the :data:`CHAIN_STEPS` ``DUSRSECJ`` step) hashes the seed passwords
    with bcrypt through the backend security module, which requires a valid
    ``SECRET_KEY``. Because every step commits its own transaction, discovering an
    unset or invalid ``SECRET_KEY`` only when the user step finally runs would
    leave all of the earlier steps already committed -- a non-atomic partial seed
    (QA finding F-1). This preflight resolves the very same backend security
    dependency the user seed will use, BEFORE any step opens a transaction, so a
    misconfigured secret aborts the run cleanly with nothing written.

    It is a deliberate no-op when ``steps`` contains no user-seed step, so batch
    runs that never touch authentication stay free of any ``SECRET_KEY``
    requirement (only :func:`~batch.loaders.init_users.InitializeUsers` needs it).

    Args:
        steps: The ordered step tuple about to be executed (:data:`SEED_STEPS`
            or :data:`CHAIN_STEPS`).

    Raises:
        BatchChainError: If a user-seed step is present and the backend security
            dependency cannot be satisfied (unset/invalid ``SECRET_KEY``, or the
            security module cannot be imported); ``.stepName`` names the user
            step so the failure is attributable to the exact chain position.
    """
    userSeedStep = next(
        (step for step in steps if step.action is InitializeUsers), None
    )
    if userSeedStep is None:
        return
    # Make the backend SECRET_KEY discoverable CWD-independently (F-1) before the
    # backend config singleton is built: export it from the anchored backend/.env
    # unless it is already set in the environment. This mirrors how batch resolves
    # SYNC_DATABASE_URL and leaves app.core.config's own .env behavior untouched.
    EnsureBackendSecretAvailable()
    try:
        # Mirror the user seed's own lazy dependency: importing app.core.security
        # instantiates the backend app.core.config.settings singleton, which
        # requires a valid SECRET_KEY. Resolving it here surfaces a missing/weak
        # secret up front rather than mid-chain, and the callable check keeps the
        # imported symbol genuinely exercised.
        from app.core.security import HashPassword

        if not callable(HashPassword):
            raise ValueError("app.core.security.HashPassword is not callable")
    except (ImportError, ValueError) as securityError:
        LOGGER.error(
            "PREFLIGHT %s FAILED: %s",
            userSeedStep.jobName,
            FormatConciseError(securityError),
        )
        raise BatchChainError(
            userSeedStep.jobName, securityError
        ) from securityError


def RunBatchChain(runDate=None, dataDir=None, outputDir=None,
                  sessionFactory=GetSyncSession) -> ChainResult:
    """Run the full CardDemo batch chain in the exact legacy README order.

    Executes all 17 steps of :data:`CHAIN_STEPS` in order, each inside its own
    transaction. The first exception aborts the chain (steps after a failure do
    not run). ``runDate`` is threaded, unchanged, to POSTTRAN/INTCALC; ``dataDir``
    is threaded to the loaders (or ``None`` to use their defaults); ``outputDir``
    is threaded to the artifact-producing steps (TRANBKP/CREASTMT).

    Note:
        This preserves the contractual legacy order, which loads XREFFILE before
        CUSTFILE. It therefore assumes an ALREADY-SEEDED database. To bootstrap a
        FRESH database, call :func:`SeedAll` first (foreign-key-safe order).

        Re-running the chain on a seeded database does not corrupt the
        posting/balance layer: the loaders refresh only static columns and
        PRESERVE ledger-owned running balances (they never reset ``curr_bal``,
        the cycle totals, or the ``tran_category_balance`` balance), and the
        posting job marks rejects terminally so no daily row is posted twice or
        re-attempted (AAP 0.7.6; QA finding F-6). It is NOT, however, a pure
        no-op: interest RE-ACCRUES on every run by design (AAP 0.7.2 / F-5,
        faithful to CBACT04C). For reconcilable golden-master output, run the
        chain once against a freshly seeded database rather than re-running it in
        place.

    Args:
        runDate: Optional run date passed straight through to POSTTRAN/INTCALC.
        dataDir: Optional data directory for the loaders; ``None`` uses defaults.
        outputDir: Optional destination directory for the backup (TRANBKP) and
            statement (CREASTMT) artifacts. When ``None``, each producing job
            falls back to its own gitignored default under ``out/`` so the chain
            never spills untracked files into the working tree (QA Finding E).
        sessionFactory: Transactional session context-manager factory; defaults
            to :func:`batch.db.GetSyncSession`. Injectable for tests.

    Returns:
        ChainResult: The ordered per-step outcome (printable).

    Raises:
        BatchChainError: If the up-front user-seed security preflight fails
            (missing/invalid ``SECRET_KEY``) or any step fails; ``.stepName``
            names the failing job. The preflight runs before any step commits, so
            a secret misconfiguration aborts the run with nothing written (F-1).
    """
    context = ChainContext(
        runDate=runDate,
        dataDir=_CoerceOptionalPath(dataDir),
        outputDir=_CoerceOptionalPath(outputDir),
    )
    # Fail fast on a missing/invalid SECRET_KEY BEFORE any step commits, so the
    # DUSRSECJ user seed can never fail part way through an already-committed
    # chain (QA finding F-1). No-op when the chain has no user-seed step.
    _ValidateUserSeedSecurity(CHAIN_STEPS)
    result = ChainResult()
    LOGGER.info("START OF BATCH CHAIN (%d steps)", len(CHAIN_STEPS))
    for step in CHAIN_STEPS:
        stepValue = _RunStep(step, context, sessionFactory)
        result.completedSteps.append(step.jobName)
        result.stepResults.append((step.jobName, stepValue))
    LOGGER.info("END OF BATCH CHAIN")
    return result


def SeedAll(dataDir=None, sessionFactory=GetSyncSession) -> None:
    """Seed all tables in foreign-key-safe order (each loader in its own txn).

    This is the correct entry point for bootstrapping a FRESH/EMPTY database. It
    runs the loaders of :data:`SEED_STEPS` in dependency order
    (disclosure_group -> customers -> accounts -> cards -> card_xref ->
    transaction_type -> transaction_category -> tran_category_balance ->
    transactions -> users) so no foreign-key constraint is violated. Each loader
    runs in its own transaction. The loaders are idempotent ON CONFLICT upserts,
    so re-running SeedAll never errors or duplicates rows; on a FRESH/EMPTY
    database every row is a first-time insert, so ledger-owned running balances
    are populated from the seed. Note that re-running SeedAll on an
    already-mutated database refreshes static columns but deliberately does NOT
    reset ledger-owned running balances back to the seed (they are ledger-owned;
    AAP 0.7.6, QA finding F-6); recreate the schema first for a clean reset.

    Args:
        dataDir: Optional data directory for the loaders; ``None`` uses defaults.
        sessionFactory: Transactional session context-manager factory; defaults
            to :func:`batch.db.GetSyncSession`. Injectable for tests.

    Raises:
        BatchChainError: If the up-front user-seed security preflight fails
            (missing/invalid ``SECRET_KEY``) or any loader fails; ``.stepName``
            names the failing step. The preflight runs before any loader commits,
            so a secret misconfiguration leaves the database untouched (F-1).
    """
    context = ChainContext(dataDir=_CoerceOptionalPath(dataDir))
    # Fail fast on a missing/invalid SECRET_KEY BEFORE any loader commits, so the
    # user seed can never fail after the other 9 tables are already committed --
    # the non-atomic partial seed of QA finding F-1. No-op when there is no
    # user-seed step.
    _ValidateUserSeedSecurity(SEED_STEPS)
    LOGGER.info("START OF SEED-ALL (%d loaders)", len(SEED_STEPS))
    for step in SEED_STEPS:
        _RunStep(step, context, sessionFactory)
    LOGGER.info("END OF SEED-ALL")
