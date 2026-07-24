# CardDemo batch CLI. Replaces JCL job submission -- each legacy JCL job
# (``app/jcl/*.jcl``) becomes a CLI subcommand, and the full-chain command
# reproduces the README batch sequence (README.md L165-183).
# Reference: ``app/proc/REPROC.prc``, ``app/proc/TRANREPT.prc``.
"""Typer command-line entrypoint for the CardDemo batch package.

This module is the single operator-facing surface for the Python
reimplementation of the legacy COBOL/JCL batch chain. It is intentionally a
*thin* dispatcher: every subcommand opens exactly one synchronous SQLAlchemy
unit of work through :func:`batch.db.GetSyncSession` and delegates to a
module-level entry function in ``batch.jobs``, ``batch.loaders`` or
``batch.orchestration`` -- no business logic lives here.

Invocation model
----------------
Run from the repository root as::

    python -m batch.cli --help
    python -m batch.cli seed-all              # bootstrap a fresh database
    python -m batch.cli run-all               # full legacy chain, in order
    python -m batch.cli job post-transactions --run-date 2022-07-06
    python -m batch.cli load accounts --data-dir app/data/ASCII

The backend ``app`` package must be importable; ``batch/__init__.py`` adds the
sibling ``backend/`` directory to ``sys.path`` as a fallback so a fresh checkout
works without ``pip install -e backend``. There is no batch container in
``docker-compose.yml`` -- batch is on-demand/manual.

Command groups
--------------
* ``job``  -- individual batch jobs, one-to-one with the legacy ``CB*`` programs.
* ``load`` -- data loaders, one-to-one with the legacy IDCAMS load jobs.
* ``run-all`` / ``seed-all`` -- top-level orchestration over the whole chain.

Coding conventions follow the Ochs Rule: command functions are PascalCase (with
explicit kebab-case CLI names), local variables are camelCase, and module
constants are ALL_UPPERCASE.
"""

from __future__ import annotations

import logging
import os
import sys
from datetime import date
from pathlib import Path
from typing import Optional

import typer
from sqlalchemy.exc import SQLAlchemyError

# Shared, configuration-free PAN log-masking filter reused from the backend
# (app.core.log_masking has NO SECRET_KEY/config dependency, so importing it does
# not reintroduce the backend-only secret requirement). It is installed on the
# batch root log handlers so that any full PAN that ever reaches a log record is
# masked to its last four digits as defense-in-depth (QA finding M13); the print
# jobs additionally mask PAN/SSN at the source before emitting them.
from app.core.log_masking import PanMaskingFilter

# Shared, dependency-free request-correlation primitive reused from the backend
# (QA finding M-32). The batch run inherits a correlation id from the
# CARDDEMO_CORRELATION_ID environment variable when an orchestrator (or the API
# tier) supplies one -- so a single id spans both tiers -- and stamps it on every
# batch log record via the correlation filter, mirroring how the API tier tags its
# request logs. Like log_masking, this module has NO config/SECRET_KEY dependency.
from app.core.correlation import (
    CORRELATION_ID_ENV_VAR,
    BindCorrelationId,
    InstallCorrelationIdLogFilter,
    SanitizeCorrelationId,
)

# --- Batch module public contract (imports restricted to the batch package) ---
# Transactional session factory (owns commit/rollback per unit of work) and the
# shared concise-error formatter (strips SQLAlchemy's SQL/parameter dump).
from batch.db import FormatConciseError, GetSyncSession

# Whole-chain orchestration (BatchChainError carries the failing legacy job name).
from batch.orchestration.batch_chain import BatchChainError, RunBatchChain, SeedAll

# Batch jobs (1:1 with the legacy CB* COBOL programs).
from batch.jobs.post_transactions import (
    DEFAULT_REJECT_DIR,
    PostingResult,
    PostTransactions,
)
from batch.jobs.interest_calc import CalculateInterest
from batch.jobs.statement_gen import GenerateStatements
from batch.jobs.print_account import PrintAccounts
from batch.jobs.print_card import PrintCards
from batch.jobs.print_xref import PrintCardXref
from batch.jobs.print_customer import PrintCustomers
from batch.jobs.read_daily_tran import ReadDailyTransactions
from batch.jobs.tran_detail_report import ReportTransactionDetail
from batch.jobs.combine_tran import CombineTransactions
from batch.jobs.backup_tran import BackupTransactions, RestoreTransactions

# Data loaders (1:1 with the legacy IDCAMS load jobs).
from batch.loaders.load_accounts import LoadAccounts
from batch.loaders.load_cards import LoadCards
from batch.loaders.load_customers import LoadCustomers
from batch.loaders.load_xref import LoadCardXref
from batch.loaders.load_transactions import LoadTransactions
from batch.loaders.load_disclosure_groups import LoadDisclosureGroups
from batch.loaders.load_tran_categories import LoadTranCategories
from batch.loaders.load_tran_types import LoadTranTypes
from batch.loaders.load_tcatbal import LoadTranCategoryBalances
from batch.loaders.init_users import InitializeUsers


# --------------------------------------------------------------------------- #
# Module constants (ALL_UPPERCASE per the Ochs Rule).
# --------------------------------------------------------------------------- #
# ``__file__`` is ``<repo>/batch/cli.py`` -> ``.parent.parent`` is ``<repo>`` ->
# ``app/data/ASCII`` holds the display-readable ASCII seed files (the primary
# loader source; EBCDIC is only used where no ASCII equivalent exists).
DEFAULT_DATA_DIR: Path = Path(__file__).resolve().parent.parent / "app" / "data" / "ASCII"

# Default destination for generated statements, reports and backups. The output
# jobs create this directory (``mkdir(parents=True, exist_ok=True)``) on demand.
DEFAULT_OUTPUT_DIR: Path = Path(__file__).resolve().parent.parent / "out"

# Environment variable that overrides the default (INFO) logging level.
LOG_LEVEL_ENV_VAR: str = "BATCH_LOG_LEVEL"

# Batch log line format. The ``[%(correlation_id)s]`` field is populated by the
# correlation log filter (QA finding M-32) installed in ``_ConfigureLogging`` so
# every batch line can be tied to the run's correlation id (and, when inherited
# from CARDDEMO_CORRELATION_ID, to the API-tier request that triggered it).
BATCH_LOG_FORMAT: str = "%(asctime)s %(levelname)s [%(correlation_id)s] %(name)s: %(message)s"

# Process exit code emitted when transaction posting produced at least one
# reject. Mirrors the legacy CBTRN02C ``MOVE 4 TO RETURN-CODE`` when
# WS-REJECT-COUNT > 0 (QA finding #56), so downstream job schedulers see the same
# non-zero return code the mainframe chain raised.
REJECT_EXIT_CODE: int = 4

LOGGER = logging.getLogger("batch.cli")


# --------------------------------------------------------------------------- #
# Typer application objects and command groups.
# --------------------------------------------------------------------------- #
# ``pretty_exceptions_enable=False`` suppresses Typer's multi-hundred-line Rich
# traceback for uncaught runtime errors; the module entrypoint (see ``__main__``
# below) catches the specific batch exceptions and emits one clean stderr line
# with exit code 1 instead (QA Finding D). Parameter-level errors continue to
# render Typer's own concise usage message and exit 2.
app = typer.Typer(
    help="CardDemo batch CLI -- Python reimplementation of the legacy COBOL/JCL batch chain.",
    no_args_is_help=True,
    pretty_exceptions_enable=False,
)

jobApp = typer.Typer(
    help="Individual batch jobs (1:1 with legacy CB* COBOL programs).",
    no_args_is_help=True,
    pretty_exceptions_enable=False,
)

loadApp = typer.Typer(
    help="Data loaders (1:1 with legacy IDCAMS load jobs).",
    no_args_is_help=True,
    pretty_exceptions_enable=False,
)

app.add_typer(jobApp, name="job")
app.add_typer(loadApp, name="load")


# --------------------------------------------------------------------------- #
# Small, self-contained helpers.
# --------------------------------------------------------------------------- #
def _ParseRunDate(runDate: Optional[str]) -> Optional[date]:
    """Parse an ISO ``YYYY-MM-DD`` run date, rejecting bad input cleanly.

    A ``None`` value passes through unchanged so downstream jobs apply their
    legacy default (the current timestamp). Any malformed value raises
    :class:`typer.BadParameter`, which Typer renders as a non-zero parameter
    error rather than an unhandled traceback.
    """
    if runDate is None:
        return None
    try:
        return date.fromisoformat(runDate)
    except ValueError as exc:
        raise typer.BadParameter(
            f"Run date must be ISO format YYYY-MM-DD, got: {runDate!r}"
        ) from exc


def _ConfigureLogging(verbose: bool) -> None:
    """Configure root logging for the batch run (DEBUG when ``verbose``).

    The level defaults to INFO, is overridable through the ``BATCH_LOG_LEVEL``
    environment variable, and is forced to DEBUG when ``--verbose`` is supplied.
    The correlation log filter is attached to the batch handlers immediately after
    ``basicConfig`` (before any record is formatted) so the ``%(correlation_id)s``
    field in :data:`BATCH_LOG_FORMAT` is always populated (QA finding M-32).
    """
    envLevel = os.environ.get(LOG_LEVEL_ENV_VAR, "INFO").upper()
    logLevel = logging.DEBUG if verbose else getattr(logging, envLevel, logging.INFO)
    logging.basicConfig(
        level=logLevel,
        format=BATCH_LOG_FORMAT,
    )
    # Attach the correlation filter FIRST so the correlation_id format token is
    # populated on every record the batch handlers emit (QA finding M-32), then the
    # PAN-masking redaction (QA finding M13), then bind this run's correlation id.
    InstallCorrelationIdLogFilter()
    _InstallLogRedaction()
    _BindBatchCorrelationId()


def _BindBatchCorrelationId() -> None:
    """Bind this batch run's correlation id for cross-tier propagation (M-32).

    The id is inherited from the :data:`CORRELATION_ID_ENV_VAR`
    (``CARDDEMO_CORRELATION_ID``) environment variable when an orchestrator or the
    API tier supplies one, so a single id can span both tiers; an absent or
    invalid value yields a fresh, server-generated id via
    :func:`SanitizeCorrelationId`. Once bound, the id is stamped on every
    subsequent batch log record by the correlation filter installed in
    :func:`_ConfigureLogging`.
    """
    inheritedId = os.environ.get(CORRELATION_ID_ENV_VAR)
    correlationId = SanitizeCorrelationId(inheritedId)
    BindCorrelationId(correlationId)
    LOGGER.debug("Batch run correlation id: %s", correlationId)


def _InstallLogRedaction() -> None:
    """Attach the PAN-masking filter to every root log handler (QA finding M13).

    Installing the filter on the root logger's HANDLERS (rather than on a single
    logger) masks every record that flows through batch logging, because all
    batch module loggers propagate to root. Any full PAN that inadvertently
    reaches a log message or its arguments is rewritten to its last four digits
    before the record is formatted, as defense-in-depth on top of the explicit
    masking the print jobs already perform. The filter is idempotent: a handler
    that already carries a :class:`PanMaskingFilter` is left untouched, so
    repeated calls (for example across tests) never stack duplicate filters.
    """
    for handler in logging.getLogger().handlers:
        hasFilter = any(
            isinstance(existingFilter, PanMaskingFilter)
            for existingFilter in handler.filters
        )
        if not hasFilter:
            handler.addFilter(PanMaskingFilter())


# --------------------------------------------------------------------------- #
# Job subcommands (11) -- registered under ``job`` (1:1 with the CB* programs).
# --------------------------------------------------------------------------- #
@jobApp.command("post-transactions")
def PostTransactionsCommand(
    runDate: Optional[str] = typer.Option(
        None, "--run-date", help="Business run date (YYYY-MM-DD)."
    ),
    rejectDir: Path = typer.Option(
        DEFAULT_REJECT_DIR, "--reject-dir", file_okay=False,
        help="Directory for the protected 430-byte reject sink (created 0700).",
    ),
) -> None:
    """Post daily transactions (legacy CBTRN02C / POSTTRAN.jcl).

    Echoes only a safe summary (counts plus the opaque reject-sink path) -- the
    raw reject records, which contain unmasked card numbers, are written only to
    the protected sink and are never printed or logged (QA finding #28). Exits
    with code 4 when any transaction was rejected, mirroring the legacy
    ``RETURN-CODE = 4`` (QA finding #56).
    """
    parsedDate = _ParseRunDate(runDate)
    with GetSyncSession() as session:
        result = PostTransactions(session, runDate=parsedDate, rejectDir=rejectDir)
    # ``result`` renders a safe summary (counts + sink path); no PAN is emitted.
    typer.echo(f"post-transactions complete: {result}")
    if result.transactionsRejected > 0:
        raise typer.Exit(code=REJECT_EXIT_CODE)


@jobApp.command("interest-calc")
def CalculateInterestCommand(
    runDate: Optional[str] = typer.Option(
        None, "--run-date", help="Business run date (YYYY-MM-DD)."
    ),
) -> None:
    """Calculate monthly interest (legacy CBACT04C / INTCALC.jcl)."""
    parsedDate = _ParseRunDate(runDate)
    with GetSyncSession() as session:
        result = CalculateInterest(session, runDate=parsedDate)
    typer.echo(f"interest-calc complete: {result}")


@jobApp.command("statement-gen")
def GenerateStatementsCommand(
    outputDir: Path = typer.Option(
        DEFAULT_OUTPUT_DIR, "--output-dir", file_okay=False,
        help="Directory for CSV/PDF statement output.",
    ),
) -> None:
    """Generate customer statements as CSV + PDF (legacy CBSTM03A/CBSTM03B / CREASTMT.jcl)."""
    with GetSyncSession() as session:
        result = GenerateStatements(session, outputDir=outputDir)
    typer.echo(f"statement-gen complete: {result}")


@jobApp.command("print-account")
def PrintAccountsCommand() -> None:
    """Print the account master (legacy CBACT01C)."""
    with GetSyncSession() as session:
        count = PrintAccounts(session)
    typer.echo(f"print-account complete: {count} rows")


@jobApp.command("print-card")
def PrintCardsCommand() -> None:
    """Print the card master (legacy CBACT02C)."""
    with GetSyncSession() as session:
        count = PrintCards(session)
    typer.echo(f"print-card complete: {count} rows")


@jobApp.command("print-xref")
def PrintCardXrefCommand() -> None:
    """Print the card/account/customer cross-reference (legacy CBACT03C)."""
    with GetSyncSession() as session:
        count = PrintCardXref(session)
    typer.echo(f"print-xref complete: {count} rows")


@jobApp.command("print-customer")
def PrintCustomersCommand() -> None:
    """Print the customer master (legacy CBCUS01C)."""
    with GetSyncSession() as session:
        count = PrintCustomers(session)
    typer.echo(f"print-customer complete: {count} rows")


@jobApp.command("read-daily-tran")
def ReadDailyTransactionsCommand() -> None:
    """Read and summarize the daily transaction file (legacy CBTRN01C)."""
    with GetSyncSession() as session:
        count = ReadDailyTransactions(session)
    typer.echo(f"read-daily-tran complete: {count} rows")


@jobApp.command("tran-detail-report")
def ReportTransactionDetailCommand(
    outputDir: Path = typer.Option(
        DEFAULT_OUTPUT_DIR, "--output-dir", file_okay=False,
        help="Directory for the transaction detail report.",
    ),
) -> None:
    """Produce the transaction detail report (legacy CBTRN03C / TRANREPT.prc)."""
    with GetSyncSession() as session:
        count = ReportTransactionDetail(session, outputDir=outputDir)
    typer.echo(f"tran-detail-report complete: {count} rows")


@jobApp.command("combine-tran")
def CombineTransactionsCommand(
    outputDir: Path = typer.Option(
        DEFAULT_OUTPUT_DIR, "--output-dir", file_okay=False,
        help="Directory for the combined transaction ledger file.",
    ),
) -> None:
    """Combine POSTED transactions into the master ledger file (legacy COMBTRAN.jcl / REPROCT.ctl)."""
    with GetSyncSession() as session:
        count = CombineTransactions(session, outputDir=outputDir)
    typer.echo(f"combine-tran complete: {count} rows")


@jobApp.command("backup-tran")
def BackupTransactionsCommand(
    outputDir: Path = typer.Option(
        DEFAULT_OUTPUT_DIR, "--output-dir", file_okay=False,
        help="Directory for the transaction backup file.",
    ),
) -> None:
    """Back up the POSTED transaction master (legacy TRANBKP.jcl)."""
    with GetSyncSession() as session:
        count = BackupTransactions(session, outputDir=outputDir)
    typer.echo(f"backup-tran complete: {count} rows")


@jobApp.command("restore-tran")
def RestoreTransactionsCommand(
    backupFile: Path = typer.Option(
        ..., "--backup-file", exists=True, dir_okay=False, readable=True,
        help="Path to a transact_bkup_*.csv file written by backup-tran.",
    ),
) -> None:
    """Restore transactions from a backup file (idempotent replay of TRANBKP output)."""
    with GetSyncSession() as session:
        count = RestoreTransactions(session, backupFile)
    typer.echo(f"restore-tran complete: {count} rows")


# --------------------------------------------------------------------------- #
# Loader subcommands (10) -- registered under ``load`` (1:1 with IDCAMS jobs).
# --------------------------------------------------------------------------- #
@loadApp.command("accounts")
def LoadAccountsCommand(
    dataDir: Path = typer.Option(
        DEFAULT_DATA_DIR, "--data-dir", exists=True, file_okay=False,
        help="Directory containing ASCII seed files.",
    ),
) -> None:
    """Load the account master seed (legacy ACCTFILE IDCAMS job)."""
    with GetSyncSession() as session:
        count = LoadAccounts(session, dataDir=dataDir)
    typer.echo(f"load accounts complete: {count} rows")


@loadApp.command("cards")
def LoadCardsCommand(
    dataDir: Path = typer.Option(
        DEFAULT_DATA_DIR, "--data-dir", exists=True, file_okay=False,
        help="Directory containing ASCII seed files.",
    ),
) -> None:
    """Load the card master seed (legacy CARDFILE IDCAMS job)."""
    with GetSyncSession() as session:
        count = LoadCards(session, dataDir=dataDir)
    typer.echo(f"load cards complete: {count} rows")


@loadApp.command("customers")
def LoadCustomersCommand(
    dataDir: Path = typer.Option(
        DEFAULT_DATA_DIR, "--data-dir", exists=True, file_okay=False,
        help="Directory containing ASCII seed files.",
    ),
) -> None:
    """Load the customer master seed (legacy CUSTFILE IDCAMS job)."""
    with GetSyncSession() as session:
        count = LoadCustomers(session, dataDir=dataDir)
    typer.echo(f"load customers complete: {count} rows")


@loadApp.command("xref")
def LoadCardXrefCommand(
    dataDir: Path = typer.Option(
        DEFAULT_DATA_DIR, "--data-dir", exists=True, file_okay=False,
        help="Directory containing ASCII seed files.",
    ),
) -> None:
    """Load the card cross-reference seed (legacy XREFFILE IDCAMS job)."""
    with GetSyncSession() as session:
        count = LoadCardXref(session, dataDir=dataDir)
    typer.echo(f"load xref complete: {count} rows")


@loadApp.command("transactions")
def LoadTransactionsCommand(
    dataDir: Path = typer.Option(
        DEFAULT_DATA_DIR, "--data-dir", exists=True, file_okay=False,
        help="Directory containing ASCII seed files.",
    ),
) -> None:
    """Load the transaction seed (legacy TRANFILE IDCAMS job)."""
    with GetSyncSession() as session:
        count = LoadTransactions(session, dataDir=dataDir)
    typer.echo(f"load transactions complete: {count} rows")


@loadApp.command("disclosure-groups")
def LoadDisclosureGroupsCommand(
    dataDir: Path = typer.Option(
        DEFAULT_DATA_DIR, "--data-dir", exists=True, file_okay=False,
        help="Directory containing ASCII seed files.",
    ),
) -> None:
    """Load the disclosure group seed (legacy DISCGRP IDCAMS job)."""
    with GetSyncSession() as session:
        count = LoadDisclosureGroups(session, dataDir=dataDir)
    typer.echo(f"load disclosure-groups complete: {count} rows")


@loadApp.command("tran-categories")
def LoadTranCategoriesCommand(
    dataDir: Path = typer.Option(
        DEFAULT_DATA_DIR, "--data-dir", exists=True, file_okay=False,
        help="Directory containing ASCII seed files.",
    ),
) -> None:
    """Load the transaction category seed (legacy TRANCATG IDCAMS job)."""
    with GetSyncSession() as session:
        count = LoadTranCategories(session, dataDir=dataDir)
    typer.echo(f"load tran-categories complete: {count} rows")


@loadApp.command("tran-types")
def LoadTranTypesCommand(
    dataDir: Path = typer.Option(
        DEFAULT_DATA_DIR, "--data-dir", exists=True, file_okay=False,
        help="Directory containing ASCII seed files.",
    ),
) -> None:
    """Load the transaction type seed (legacy TRANTYPE IDCAMS job)."""
    with GetSyncSession() as session:
        count = LoadTranTypes(session, dataDir=dataDir)
    typer.echo(f"load tran-types complete: {count} rows")


@loadApp.command("tcatbal")
def LoadTranCategoryBalancesCommand(
    dataDir: Path = typer.Option(
        DEFAULT_DATA_DIR, "--data-dir", exists=True, file_okay=False,
        help="Directory containing ASCII seed files.",
    ),
) -> None:
    """Load the transaction category balance seed (legacy TCATBALF IDCAMS job)."""
    with GetSyncSession() as session:
        count = LoadTranCategoryBalances(session, dataDir=dataDir)
    typer.echo(f"load tcatbal complete: {count} rows")


@loadApp.command("init-users")
def InitializeUsersCommand() -> None:
    """Initialize user security with hashed passwords (legacy DUSRSECJ / USRSEC.PS)."""
    with GetSyncSession() as session:
        count = InitializeUsers(session)
    typer.echo(f"init-users complete: {count} users")


# --------------------------------------------------------------------------- #
# Top-level orchestration commands.
# --------------------------------------------------------------------------- #
@app.command("run-all")
def RunAllCommand(
    runDate: Optional[str] = typer.Option(
        None, "--run-date", help="Business run date (YYYY-MM-DD)."
    ),
    dataDir: Path = typer.Option(
        DEFAULT_DATA_DIR, "--data-dir", exists=True, file_okay=False,
        help="Directory containing ASCII seed files.",
    ),
    outputDir: Path = typer.Option(
        DEFAULT_OUTPUT_DIR, "--output-dir", file_okay=False,
        help="Destination for chain artifacts (backups/statements); "
             "the default is the gitignored out/ directory.",
    ),
) -> None:
    """Run the full batch chain in legacy README order.

    Executes the 17 steps preserved from the legacy chain
    (CLOSEFIL -> ACCTFILE -> CARDFILE -> XREFFILE -> CUSTFILE -> TRANBKP ->
    DISCGRP -> TCATBALF -> TRANTYPE -> DUSRSECJ -> POSTTRAN -> INTCALC ->
    TRANBKP -> COMBTRAN -> CREASTMT -> TRANIDX -> OPENFIL). Assumes an
    already-seeded database; run ``seed-all`` first to bootstrap a fresh one.

    The backup (TRANBKP) and statement (CREASTMT) steps write their artifacts
    under ``--output-dir`` (default: the gitignored ``out/`` directory), so a
    full run never leaves untracked files in the working tree (QA Finding E).
    """
    parsedDate = _ParseRunDate(runDate)
    result = RunBatchChain(runDate=parsedDate, dataDir=dataDir, outputDir=outputDir)
    rejectedTotal = 0
    for stepName, stepValue in result.stepResults:
        # Each stepValue renders only a safe summary; a PostingResult never
        # exposes reject-record content (QA finding #28).
        typer.echo(f"  {stepName}: {stepValue}")
        if isinstance(stepValue, PostingResult):
            rejectedTotal += stepValue.transactionsRejected
    typer.echo(f"run-all complete: {len(result.completedSteps)} steps")
    # Aggregate the posting return code across the chain: if any POSTTRAN step
    # rejected a transaction, surface the legacy RETURN-CODE = 4 (QA finding #56).
    if rejectedTotal > 0:
        typer.echo(f"posting rejected {rejectedTotal} transaction(s)")
        raise typer.Exit(code=REJECT_EXIT_CODE)


@app.command("seed-all")
def SeedAllCommand(
    dataDir: Path = typer.Option(
        DEFAULT_DATA_DIR, "--data-dir", exists=True, file_okay=False,
        help="Directory containing ASCII seed files.",
    ),
) -> None:
    """Seed every table in foreign-key-safe order (initial bootstrap).

    Delegates to :func:`batch.orchestration.batch_chain.SeedAll`, which runs each
    loader in its own transaction; per-loader row counts are emitted to the log.
    """
    SeedAll(dataDir=dataDir)
    typer.echo("seed-all complete (see log for per-loader counts)")


# --------------------------------------------------------------------------- #
# Root callback (logging setup) and module entrypoint.
# --------------------------------------------------------------------------- #
@app.callback()
def Main(
    verbose: bool = typer.Option(
        False, "--verbose", "-v", help="Enable DEBUG-level logging."
    ),
) -> None:
    """CardDemo batch CLI -- configure logging, then dispatch to a subcommand."""
    _ConfigureLogging(verbose)


def _RunCli() -> None:
    """Run the Typer app, reporting runtime failures as a clean stderr message.

    Typer/Click raise :class:`SystemExit` for their normal terminations (help
    exits 0; parameter errors exit 2 after Click prints its own concise usage
    message). Those propagate untouched, so those surfaces keep their existing
    behavior. Only the batch *runtime* exceptions are intercepted here, per the
    Ochs Rule's specific-exception requirement (never a catch-all):

    * :class:`~batch.orchestration.batch_chain.BatchChainError` -- a chain step
      failed (carries the failing legacy job name);
    * :class:`~sqlalchemy.exc.SQLAlchemyError` -- database/connection failure;
    * :class:`OSError` -- artifact file I/O failure (for example the output dir);
    * :class:`ValueError` -- record/field validation failure.

    Each is emitted as a concise ``ERROR: <message>`` diagnostic with exit code 1
    and no Rich traceback (QA Finding D). The message cannot leak credentials:
    the database URL is a :class:`~pydantic.SecretStr` and psycopg2 masks the
    password in its own error text; :func:`batch.db.FormatConciseError`
    additionally strips the bound-parameter dump so seed values such as card
    numbers never surface.
    """
    try:
        app()
    except (BatchChainError, SQLAlchemyError, OSError, ValueError) as cliError:
        typer.echo(f"ERROR: {FormatConciseError(cliError)}", err=True)
        sys.exit(1)


if __name__ == "__main__":
    _RunCli()
