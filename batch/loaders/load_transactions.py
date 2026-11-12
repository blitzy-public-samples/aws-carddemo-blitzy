# Ported from IDCAMS load job app/jcl/TRANFILE.jcl (DELETE/DEFINE/REPRO of the
# transaction VSAM KSDS + alternate index on card_num); record layout
# app/cpy/CVTRA06Y.cpy (DALYTRAN-RECORD, RECLN 350; field-identical to the
# posted app/cpy/CVTRA05Y.cpy TRAN-RECORD); seed data app/data/ASCII/dailytran.txt.
"""Idempotent seed loader for the CardDemo ``transactions`` table.

This module is the Python reimplementation of the legacy IDCAMS load job
``TRANFILE`` (``app/jcl/TRANFILE.jcl``), which deleted, redefined and REPROed
the ``TRANSACT`` VSAM KSDS from the daily-transaction sequential seed. The seed
records follow the ``DALYTRAN-RECORD`` layout (``app/cpy/CVTRA06Y.cpy``,
RECLN=350), which is byte-for-byte identical to the posted ``TRAN-RECORD``
layout (``app/cpy/CVTRA05Y.cpy``); both are unified into the single
``transactions`` table (see :mod:`app.models.transaction`).

Load ordering
    ``transactions`` carries a foreign key ``card_num`` -> ``cards.card_num``,
    so this loader must run **last** among the data-table loaders, after the
    card loader has populated its parent rows. The orchestration chain
    (``batch.orchestration.batch_chain``) enforces that order.

Staging semantics (AAP 0.7.5)
    The daily seed represents transactions that have **not yet been posted**.
    Every brand-new row is therefore inserted with ``status = STATUS_PENDING``
    -- the ``transactions`` table defaults to ``POSTED`` (it is fundamentally the
    posted ledger), so the pending state is set explicitly here. The batch
    posting job (``CBTRN02C`` -> ``batch/jobs/post_transactions.py``) later
    promotes these rows to ``POSTED``. On a re-run, the upsert **preserves** the
    posting state of rows that already exist (see the idempotency note below), so
    a row the posting job has already promoted is never reset back to
    ``PENDING`` (QA Finding F).

Numeric and timestamp fidelity (AAP 0.7.1)
    ``tran_amt`` originates from ``PIC S9(09)V99`` -- a signed *zoned-decimal*
    DISPLAY field (NOT ``COMP-3`` packed decimal). It is decoded through
    :func:`app.utils.decimal_utils.DecodeZonedDecimal` into an exact
    :class:`decimal.Decimal`; floating point is never used. The seed exercises
    the full overpunch range including negatives. The two 26-byte timestamp
    fields are parsed through :func:`app.utils.date_utils.ParseLegacyTimestamp`;
    because that helper raises ``ValueError`` on blank input, blank timestamps
    are guarded to ``None`` first (``proc_ts`` is blank throughout the daily
    seed and therefore always loads as ``NULL``).

Transaction ownership
    The caller owns the unit of work. :func:`LoadTransactions` never calls
    ``commit`` or ``rollback``; it writes through the supplied
    :class:`~sqlalchemy.orm.Session` so the orchestrator or CLI can compose
    several loaders into one atomic transaction. The load is idempotent: rows
    are upserted with an ``ON CONFLICT (tran_id) DO UPDATE`` so re-running the
    loader refreshes existing rows rather than failing on duplicate keys -- but
    the posting-state columns (``status``, ``proc_ts`` in
    :data:`CONFLICT_PRESERVED_COLUMNS`) are excluded from that update, so a
    re-run never un-posts an already-posted transaction. The loader never issues
    DDL -- the schema is owned by Alembic.
"""

from __future__ import annotations

from datetime import datetime
from pathlib import Path
from typing import Optional

from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.orm import Session

from app.models.transaction import STATUS_PENDING, Transaction
from app.utils.date_utils import ParseLegacyTimestamp
from app.utils.decimal_utils import DecodeZonedDecimal


# --- File / record constants -------------------------------------------------
# The ASCII seed dataset (display-readable; no EBCDIC decode needed) and its
# fixed record geometry, per CVTRA06Y (RECLN=350).
SEED_FILE_NAME = "dailytran.txt"
RECORD_LENGTH = 350
FILE_ENCODING = "latin-1"
MONEY_SCALE = 2

# --- Fixed-width field slices (CVTRA06Y DALYTRAN-RECORD) ---------------------
# One ALL_UPPERCASE slice constant per copybook field. Offsets are half-open
# ``[start:stop)`` byte positions and are verified to tile the 350-byte record
# exactly; the trailing FILLER [330:350] carries no data and is dropped.
TRAN_ID_SLICE = slice(0, 16)  # DALYTRAN-ID           PIC X(16)  -> PK
TRAN_TYPE_CD_SLICE = slice(16, 18)  # DALYTRAN-TYPE-CD      PIC X(02)
TRAN_CAT_CD_SLICE = slice(18, 22)  # DALYTRAN-CAT-CD       PIC 9(04)
TRAN_SOURCE_SLICE = slice(22, 32)  # DALYTRAN-SOURCE       PIC X(10)
TRAN_DESC_SLICE = slice(32, 132)  # DALYTRAN-DESC         PIC X(100)
TRAN_AMT_SLICE = slice(132, 143)  # DALYTRAN-AMT          PIC S9(09)V99
MERCHANT_ID_SLICE = slice(143, 152)  # DALYTRAN-MERCHANT-ID  PIC 9(09)
MERCHANT_NAME_SLICE = slice(152, 202)  # DALYTRAN-MERCHANT-NAME PIC X(50)
MERCHANT_CITY_SLICE = slice(202, 252)  # DALYTRAN-MERCHANT-CITY PIC X(50)
MERCHANT_ZIP_SLICE = slice(252, 262)  # DALYTRAN-MERCHANT-ZIP PIC X(10)
CARD_NUM_SLICE = slice(262, 278)  # DALYTRAN-CARD-NUM     PIC X(16)  -> FK cards
ORIG_TS_SLICE = slice(278, 304)  # DALYTRAN-ORIG-TS      PIC X(26)
PROC_TS_SLICE = slice(304, 330)  # DALYTRAN-PROC-TS      PIC X(26)

# --- Upsert conflict policy (AAP 0.7.5 / 0.7.6; QA Finding F) -----------------
# Posting-state columns that record where a transaction sits in the
# PENDING -> POSTED lifecycle. The INSERT path still sets them for brand-new
# daily rows (every fresh row enters as STATUS_PENDING with a NULL proc_ts), but
# they are DELIBERATELY excluded from the ON CONFLICT update set: once the
# posting job (CBTRN02C -> batch/jobs/post_transactions.py) has promoted a row to
# POSTED and stamped its proc_ts, re-running this daily loader must NOT reset it
# back to PENDING / NULL. This keeps the loader coherent with the seed migration
# (which loads the posted ledger as POSTED) and strengthens the idempotency
# guarantee -- a re-run never un-posts an already-posted transaction.
CONFLICT_PRESERVED_COLUMNS = ("status", "proc_ts")

__all__ = ["LoadTransactions"]


def _OptionalTimestamp(rawValue: str) -> Optional[datetime]:
    """Return a parsed timestamp, or None when the field is blank.

    :func:`app.utils.date_utils.ParseLegacyTimestamp` raises ``ValueError`` on
    blank input, so a blank fixed-width field (common for the daily seed's
    ``proc_ts``) is mapped to ``None`` before delegating.

    Args:
        rawValue: The raw 26-byte timestamp slice, possibly space-padded.

    Returns:
        The parsed timezone-naive :class:`datetime.datetime`, or ``None`` when
        the field holds only whitespace.
    """
    trimmed = rawValue.strip()
    if not trimmed:
        return None
    return ParseLegacyTimestamp(trimmed)


def _ParseTransactionRecord(record: str) -> dict:
    """Map one fixed-width DALYTRAN-RECORD to ``transactions`` column values.

    The returned dict is keyed by the **real** ORM column names (note the
    ``merchant_*`` columns carry no ``tran_`` prefix). ``tran_amt`` is decoded
    as signed zoned decimal into a :class:`~decimal.Decimal`; the leading-zero
    string fields (``tran_cat_cd``, ``merchant_id``, ``card_num``) are kept as
    strings; and ``status`` is set explicitly to :data:`STATUS_PENDING`.

    This mapping intentionally exceeds the small-method guideline: it is a flat,
    one-line-per-field record map and must not be split, so every field stays
    visible against the copybook layout in a single place.

    Args:
        record: A single record already padded to :data:`RECORD_LENGTH`.

    Returns:
        A dict of column-name -> value ready for bulk upsert.
    """
    return {
        "tran_id": record[TRAN_ID_SLICE].strip(),
        "tran_type_cd": record[TRAN_TYPE_CD_SLICE].strip(),
        "tran_cat_cd": record[TRAN_CAT_CD_SLICE].strip(),
        "tran_source": record[TRAN_SOURCE_SLICE].strip(),
        "tran_desc": record[TRAN_DESC_SLICE].strip(),
        "tran_amt": DecodeZonedDecimal(record[TRAN_AMT_SLICE], MONEY_SCALE),
        "merchant_id": record[MERCHANT_ID_SLICE].strip(),
        "merchant_name": record[MERCHANT_NAME_SLICE].strip(),
        "merchant_city": record[MERCHANT_CITY_SLICE].strip(),
        "merchant_zip": record[MERCHANT_ZIP_SLICE].strip(),
        "card_num": record[CARD_NUM_SLICE].strip(),
        "orig_ts": _OptionalTimestamp(record[ORIG_TS_SLICE]),
        "proc_ts": _OptionalTimestamp(record[PROC_TS_SLICE]),
        "status": STATUS_PENDING,
    }


def _ResolveDataDir(dataDir: Optional[Path]) -> Path:
    """Resolve the directory that holds the ASCII seed files.

    When the caller does not pass an explicit directory, the loader defaults to
    the repository's ``app/data/ASCII`` tree, located relative to this module so
    the loader works regardless of the process working directory.

    Args:
        dataDir: An explicit seed directory, or ``None`` to use the default.

    Returns:
        The resolved :class:`~pathlib.Path` of the seed directory.
    """
    if dataDir is not None:
        return dataDir
    repositoryRoot = Path(__file__).resolve().parents[2]
    return repositoryRoot / "app" / "data" / "ASCII"


def _UpsertRows(session: Session, rows: list[dict]) -> int:
    """Bulk-upsert transaction rows on the ``tran_id`` primary key.

    Builds a single PostgreSQL ``INSERT ... ON CONFLICT (tran_id) DO UPDATE``
    statement so the load is idempotent. New rows are inserted with every column
    (including the ``status``/``proc_ts`` posting-state fields set by the row
    builder). For rows that already exist, the update refreshes the data columns
    but **preserves** the posting-state columns in
    :data:`CONFLICT_PRESERVED_COLUMNS`: an already-``POSTED`` transaction is never
    reset to ``PENDING`` (nor its ``proc_ts`` nulled) by a loader re-run, keeping
    this loader coherent with the seed migration's posted ledger (QA Finding F).
    The primary-key column set is derived from the ORM table metadata rather than
    hardcoded. The statement is executed through the caller's session; no
    transaction control happens here.

    Args:
        session: The caller-owned SQLAlchemy session.
        rows: Column-keyed dicts produced by :func:`_ParseTransactionRecord`.

    Returns:
        The number of rows submitted for upsert.
    """
    if not rows:
        return 0
    targetTable = Transaction.__table__
    primaryKeyNames = [column.name for column in targetTable.primary_key.columns]
    insertStatement = insert(targetTable).values(rows)
    # Refresh every data column on conflict, but exclude the primary key and the
    # posting-state columns so a re-run never un-posts an already-posted row.
    updatedColumns = {
        column.name: insertStatement.excluded[column.name]
        for column in targetTable.columns
        if column.name not in primaryKeyNames
        and column.name not in CONFLICT_PRESERVED_COLUMNS
    }
    upsertStatement = insertStatement.on_conflict_do_update(
        index_elements=primaryKeyNames,
        set_=updatedColumns,
    )
    session.execute(upsertStatement)
    return len(rows)


def LoadTransactions(session: Session, dataDir: Optional[Path] = None) -> int:
    """Load the daily-transaction seed into the ``transactions`` table.

    Reads the fixed-width ``dailytran.txt`` seed (``CVTRA06Y`` layout), decodes
    every record into ``transactions`` column values, and idempotently upserts
    them on ``tran_id``. Every **new** row is marked :data:`STATUS_PENDING` to
    preserve the legacy daily-staging semantics; on a re-run the posting state of
    rows that already exist is preserved (see :func:`_UpsertRows`), so an
    already-posted transaction is never reset to pending. The caller owns the
    transaction: this function performs no ``commit`` or ``rollback`` and issues
    no DDL.

    Args:
        session: An open, caller-owned SQLAlchemy :class:`~sqlalchemy.orm.Session`.
        dataDir: Optional override for the ASCII seed directory. Defaults to the
            repository's ``app/data/ASCII`` tree.

    Returns:
        The number of transaction rows processed (upserted).

    Raises:
        FileNotFoundError: If the ``dailytran.txt`` seed file is not present in
            the resolved seed directory.
        ValueError: If a record holds a non-blank timestamp or monetary field
            that cannot be decoded (propagated from the decode helpers).
    """
    seedDirectory = _ResolveDataDir(dataDir)
    seedPath = seedDirectory / SEED_FILE_NAME
    if not seedPath.is_file():
        raise FileNotFoundError(f"Transaction seed file not found: {seedPath}")

    parsedRows: list[dict] = []
    with seedPath.open("r", encoding=FILE_ENCODING) as seedFile:
        for rawLine in seedFile:
            record = rawLine.rstrip("\r\n").ljust(RECORD_LENGTH)
            if not record.strip():
                continue
            parsedRows.append(_ParseTransactionRecord(record))

    return _UpsertRows(session, parsedRows)
