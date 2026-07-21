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
from datetime import date
from pathlib import Path
from typing import Optional

import typer

# --- Batch module public contract (imports restricted to the batch package) ---
# Transactional session factory (owns commit/rollback per unit of work).
from batch.db import GetSyncSession

# Whole-chain orchestration.
from batch.orchestration.batch_chain import RunBatchChain, SeedAll

# Batch jobs (1:1 with the legacy CB* COBOL programs).
from batch.jobs.post_transactions import PostTransactions
from batch.jobs.interest_calc import CalculateInterest
from batch.jobs.statement_gen import GenerateStatements
from batch.jobs.print_account import PrintAccounts
from batch.jobs.print_card import PrintCards
from batch.jobs.print_xref import PrintCardXref
from batch.jobs.print_customer import PrintCustomers
from batch.jobs.read_daily_tran import ReadDailyTransactions
from batch.jobs.tran_detail_report import ReportTransactionDetail
from batch.jobs.combine_tran import CombineTransactions
from batch.jobs.backup_tran import BackupTransactions

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

LOGGER = logging.getLogger("batch.cli")


# --------------------------------------------------------------------------- #
# Typer application objects and command groups.
# --------------------------------------------------------------------------- #
app = typer.Typer(
    help="CardDemo batch CLI -- Python reimplementation of the legacy COBOL/JCL batch chain.",
    no_args_is_help=True,
)

jobApp = typer.Typer(
    help="Individual batch jobs (1:1 with legacy CB* COBOL programs).",
    no_args_is_help=True,
)

loadApp = typer.Typer(
    help="Data loaders (1:1 with legacy IDCAMS load jobs).",
    no_args_is_help=True,
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
    """
    envLevel = os.environ.get(LOG_LEVEL_ENV_VAR, "INFO").upper()
    logLevel = logging.DEBUG if verbose else getattr(logging, envLevel, logging.INFO)
    logging.basicConfig(
        level=logLevel,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )


# --------------------------------------------------------------------------- #
# Job subcommands (11) -- registered under ``job`` (1:1 with the CB* programs).
# --------------------------------------------------------------------------- #
@jobApp.command("post-transactions")
def PostTransactionsCommand(
    runDate: Optional[str] = typer.Option(
        None, "--run-date", help="Business run date (YYYY-MM-DD)."
    ),
) -> None:
    """Post daily transactions (legacy CBTRN02C / POSTTRAN.jcl)."""
    parsedDate = _ParseRunDate(runDate)
    with GetSyncSession() as session:
        result = PostTransactions(session, runDate=parsedDate)
    typer.echo(f"post-transactions complete: {result}")


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
def CombineTransactionsCommand() -> None:
    """Combine system + daily transactions (legacy COMBTRAN.jcl / REPROCT.ctl)."""
    with GetSyncSession() as session:
        count = CombineTransactions(session)
    typer.echo(f"combine-tran complete: {count} rows")


@jobApp.command("backup-tran")
def BackupTransactionsCommand(
    outputDir: Path = typer.Option(
        DEFAULT_OUTPUT_DIR, "--output-dir", file_okay=False,
        help="Directory for the transaction backup file.",
    ),
) -> None:
    """Back up the transaction master (legacy TRANBKP.jcl)."""
    with GetSyncSession() as session:
        count = BackupTransactions(session, outputDir=outputDir)
    typer.echo(f"backup-tran complete: {count} rows")


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
) -> None:
    """Run the full batch chain in legacy README order.

    Executes the 17 steps preserved from the legacy chain
    (CLOSEFIL -> ACCTFILE -> CARDFILE -> XREFFILE -> CUSTFILE -> TRANBKP ->
    DISCGRP -> TCATBALF -> TRANTYPE -> DUSRSECJ -> POSTTRAN -> INTCALC ->
    TRANBKP -> COMBTRAN -> CREASTMT -> TRANIDX -> OPENFIL). Assumes an
    already-seeded database; run ``seed-all`` first to bootstrap a fresh one.
    """
    parsedDate = _ParseRunDate(runDate)
    result = RunBatchChain(runDate=parsedDate, dataDir=dataDir)
    for stepName, stepValue in result.stepResults:
        typer.echo(f"  {stepName}: {stepValue}")
    typer.echo(f"run-all complete: {len(result.completedSteps)} steps")


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


if __name__ == "__main__":
    app()
