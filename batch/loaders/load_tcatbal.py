# Ported from IDCAMS load job app/jcl/TCATBALF.jcl (DELETE/DEFINE/REPRO of the
# transaction-category-balance VSAM KSDS, KEYS(17,0)); record layout
# app/cpy/CVTRA01Y.cpy (TRAN-CAT-BAL-RECORD, RECLN 50); seed data
# app/data/ASCII/tcatbal.txt.
"""Idempotent seed loader for the ``tran_category_balance`` table.

Modern Python port of the legacy IDCAMS load job ``app/jcl/TCATBALF.jcl``, which
performed the ``DELETE`` / ``DEFINE`` / ``REPRO`` of the transaction-category
balance VSAM KSDS (``AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS``, ``KEYS(17 0)``,
``RECORDSIZE(50 50)``). It reads the display-readable ASCII seed dataset
``app/data/ASCII/tcatbal.txt`` -- whose 50-byte fixed-width records follow the
COBOL copybook ``CVTRA01Y`` (``TRAN-CAT-BAL-RECORD``) -- and upserts each row
into the ``tran_category_balance`` table (per-account, per-category running
balances read by the interest-calculation job ported from ``CBACT04C``).

Fixed-width record layout (``CVTRA01Y``, record length 50)::

    01  TRAN-CAT-BAL-RECORD.
        05  TRAN-CAT-KEY.
            10  TRANCAT-ACCT-ID  PIC 9(11).      -> acct_id      [0:11]
            10  TRANCAT-TYPE-CD  PIC X(02).      -> tran_type_cd [11:13]
            10  TRANCAT-CD       PIC 9(04).      -> tran_cat_cd  [13:17]
        05  TRAN-CAT-BAL         PIC S9(09)V99.  -> balance      [17:28]
        05  FILLER               PIC X(22).      -> dropped      [28:50]

Numeric fidelity (AAP 0.7.1, Finding #1): ``TRAN-CAT-BAL`` is a *signed
zoned-decimal DISPLAY* field (``PIC S9(09)V99`` -- 9 integer + 2 fractional =
11 display bytes), **not** ``COMP-3`` packed decimal. It is decoded with
:func:`app.utils.decimal_utils.DecodeZonedDecimal` into an exact
:class:`decimal.Decimal`; ``float`` is never used, preserving regulatory
numeric parity. The numeric-identifier key fields (``PIC 9(n)``) are kept as
strings so their leading zeros survive exactly as stored on the mainframe.

Transaction ownership: the caller owns the unit of work. This loader receives an
already-open synchronous :class:`~sqlalchemy.orm.Session`, performs an
idempotent ``INSERT ... ON CONFLICT`` upsert keyed on the three-column composite
primary key, and never calls ``commit()`` or ``rollback()`` itself. The
orchestrator (``batch.orchestration.batch_chain``) or CLI (``batch.cli``)
composes loaders into one atomic transaction (see ``batch.db.GetSyncSession``).
Because every write is a composite-PK upsert and never a schema change, the
loader is safe to re-run; it never issues DDL (schema is owned by Alembic).

Example:
    Compose with the shared session so the caller controls the commit::

        from batch.db import GetSyncSession
        from batch.loaders.load_tcatbal import LoadTranCategoryBalances

        with GetSyncSession() as session:
            processedRows = LoadTranCategoryBalances(session)
        # transaction committed here, exactly once, on clean exit
"""

from __future__ import annotations

from decimal import Decimal
from pathlib import Path
from typing import Optional

from sqlalchemy.dialects.postgresql import insert as PostgresInsert
from sqlalchemy.orm import Session

from app.models.tran_category_balance import TranCategoryBalance
from app.utils.decimal_utils import DecodeZonedDecimal

__all__ = ["LoadTranCategoryBalances", "LoadTcatbal"]

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Name of the display-readable ASCII seed dataset (REPRO input in TCATBALF.jcl).
SEED_FILE_NAME = "tcatbal.txt"

# Fixed record length of the CVTRA01Y TRAN-CAT-BAL-RECORD (RECORDSIZE(50 50)).
RECORD_LENGTH = 50

# The ASCII seed files are single-byte; latin-1 round-trips every byte 0x00-0xFF
# without decode errors, so slicing stays byte-accurate against the fixed layout.
FILE_ENCODING = "latin-1"

# Implied fractional-digit count for the TRAN-CAT-BAL money field (``V99``).
MONEY_SCALE = 2

# Byte offsets of each field within the 50-byte fixed-width record. These mirror
# the CVTRA01Y layout exactly and are verified against app/data/ASCII/tcatbal.txt.
ACCT_ID_SLICE = slice(0, 11)
TRAN_TYPE_CD_SLICE = slice(11, 13)
TRAN_CAT_CD_SLICE = slice(13, 17)
BALANCE_SLICE = slice(17, 28)

# Ledger-owned column that the posting job MUTATES after seeding: CBTRN02C /
# batch.jobs.post_transactions._UpdateTcatbal adds each posted daily amount to
# ``balance``. That running total belongs to the ledger, not the static seed, so
# the loader's INSERT ... ON CONFLICT must NEVER reset it on a re-load; doing so
# would wipe posted category balances and break batch-chain idempotency (AAP
# 0.7.6; QA finding F-6). ``balance`` is the ONLY non-key column, so excluding it
# leaves no refreshable columns and the upsert falls through to
# ``on_conflict_do_nothing`` (a first-time insert still seeds it from the file).
LEDGER_OWNED_COLUMNS = ("balance",)


# ---------------------------------------------------------------------------
# Private helpers (module-internal; not part of the public API)
# ---------------------------------------------------------------------------


def _ParseTranCategoryBalanceRecord(record: str) -> dict[str, str | Decimal]:
    """Map one fixed-width TRAN-CAT-BAL-RECORD to tran_category_balance values.

    Args:
        record: One raw 50-byte fixed-width record from the seed dataset, with
            any line terminator already stripped.

    Returns:
        A dict keyed by the real ``tran_category_balance`` column names. The
        three composite-key fields are stripped strings (leading zeros
        preserved), and ``balance`` is an exact :class:`decimal.Decimal`
        decoded from the signed zoned-decimal DISPLAY field.
    """
    return {
        "acct_id": record[ACCT_ID_SLICE].strip(),
        "tran_type_cd": record[TRAN_TYPE_CD_SLICE].strip(),
        "tran_cat_cd": record[TRAN_CAT_CD_SLICE].strip(),
        "balance": DecodeZonedDecimal(record[BALANCE_SLICE], MONEY_SCALE),
    }


def _ResolveDataDir(dataDir: Optional[Path]) -> Path:
    """Resolve the directory that holds the ASCII seed dataset.

    Args:
        dataDir: Explicit seed directory, or ``None`` to use the repository
            default ``app/data/ASCII``.

    Returns:
        The directory :class:`~pathlib.Path` to read the seed file from. When
        ``dataDir`` is ``None`` the path is derived from this module's location
        (``batch/loaders`` -> repo root -> ``app/data/ASCII``), so the default
        works regardless of the process's current working directory.
    """
    if dataDir is not None:
        return Path(dataDir)
    repositoryRoot = Path(__file__).resolve().parents[2]
    return repositoryRoot / "app" / "data" / "ASCII"


def _UpsertRows(session: Session, rows: list[dict[str, str | Decimal]]) -> int:
    """Idempotently upsert parsed rows on the composite primary key.

    Emits a single PostgreSQL ``INSERT ... ON CONFLICT`` statement whose
    conflict target is the table's composite primary key (derived from the ORM
    model, never hardcoded). On a key collision, non-key columns are refreshed
    from the seed EXCEPT the ledger-owned running ``balance``
    (:data:`LEDGER_OWNED_COLUMNS`), which the posting job mutates and which the
    loader must never reset (AAP 0.7.6; QA finding F-6). Because ``balance`` is
    the only non-key column, excluding it leaves nothing to refresh, so an
    existing row is left untouched (``on_conflict_do_nothing``); a first-time
    insert still seeds ``balance`` from the file.

    Args:
        session: An open synchronous session. The caller owns the transaction;
            this function does not commit or roll back.
        rows: Parsed row dicts keyed by real column name.

    Returns:
        The number of rows processed from the seed dataset.
    """
    if not rows:
        return 0
    balanceTable = TranCategoryBalance.__table__
    primaryKeyColumns = [column.name for column in balanceTable.primary_key.columns]
    insertStatement = PostgresInsert(balanceTable).values(rows)
    # Refresh non-key columns on conflict EXCEPT the ledger-owned balance, which
    # the loader must never reset (see LEDGER_OWNED_COLUMNS).
    updatableColumns = {
        column.name: insertStatement.excluded[column.name]
        for column in balanceTable.columns
        if column.name not in primaryKeyColumns
        and column.name not in LEDGER_OWNED_COLUMNS
    }
    if updatableColumns:
        upsertStatement = insertStatement.on_conflict_do_update(
            index_elements=primaryKeyColumns,
            set_=updatableColumns,
        )
    else:
        upsertStatement = insertStatement.on_conflict_do_nothing(
            index_elements=primaryKeyColumns,
        )
    session.execute(upsertStatement)
    return len(rows)


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------


def LoadTranCategoryBalances(session: Session, dataDir: Optional[Path] = None) -> int:
    """Load ``app/data/ASCII/tcatbal.txt`` into ``tran_category_balance``.

    Ports the ``TCATBALF.jcl`` IDCAMS REPRO: read the fixed-width seed dataset
    and upsert every record into the table. The load is idempotent (composite-PK
    ``ON CONFLICT`` upsert) and issues no DDL. The caller owns the transaction;
    this function never commits or rolls back.

    Args:
        session: An open synchronous SQLAlchemy session supplied by the caller
            (for example ``batch.db.GetSyncSession``).
        dataDir: Optional override for the seed directory. Defaults to the
            repository's ``app/data/ASCII`` directory.

    Returns:
        The count of records processed from the seed dataset.

    Raises:
        FileNotFoundError: If the seed dataset does not exist in ``dataDir``.
        ValueError: If a non-blank record is shorter than the fixed 50-byte
            record length, or carries a malformed zoned-decimal balance.
    """
    seedFilePath = _ResolveDataDir(dataDir) / SEED_FILE_NAME
    if not seedFilePath.is_file():
        raise FileNotFoundError(
            f"tran_category_balance seed file not found: {seedFilePath}"
        )
    parsedRows: list[dict[str, str | Decimal]] = []
    with seedFilePath.open("r", encoding=FILE_ENCODING) as seedFile:
        for lineNumber, rawLine in enumerate(seedFile, start=1):
            record = rawLine.rstrip("\r\n")
            if record.strip() == "":
                continue
            if len(record) < RECORD_LENGTH:
                raise ValueError(
                    f"Malformed TRAN-CAT-BAL record at line {lineNumber}: "
                    f"expected at least {RECORD_LENGTH} bytes, got {len(record)}"
                )
            parsedRows.append(_ParseTranCategoryBalanceRecord(record))
    return _UpsertRows(session, parsedRows)


# Compatibility alias. The batch.loaders package documentation
# (batch/loaders/__init__.py) and a sibling orchestrator/CLI may reference this
# loader by the short, module-derived name ``LoadTcatbal``. It is an exact alias
# of the canonical ``LoadTranCategoryBalances`` entry point mandated by the file
# specification, so either name resolves to the identical callable.
LoadTcatbal = LoadTranCategoryBalances
