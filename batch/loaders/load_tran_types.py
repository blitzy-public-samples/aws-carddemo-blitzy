# Ported from IDCAMS load job app/jcl/TRANTYPE.jcl (DELETE/DEFINE/REPRO of the
# transaction-type VSAM KSDS); record layout app/cpy/CVTRA03Y.cpy
# (TRAN-TYPE-RECORD, RECLN 60); seed data app/data/ASCII/trantype.txt.
"""Seed loader for the transaction_type table (legacy TRANTYPE VSAM KSDS).

Modern Python port of the legacy IDCAMS load job ``app/jcl/TRANTYPE.jcl``, which
performed a ``DELETE`` / ``DEFINE`` / ``REPRO`` cycle to (re)populate the
``AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS`` cluster from the flat file
``AWS.M2.CARDDEMO.TRANTYPE.PS``. Here the VSAM KSDS is replaced by the
PostgreSQL ``transaction_type`` reference table and the flat file by the
display-readable ASCII seed ``app/data/ASCII/trantype.txt``.

``transaction_type`` is a small, standalone lookup table with no foreign keys,
so it is one of the foundational reference tables loaded early in the FK-safe
seed order. Each fixed-width 60-byte record maps to one row:

    01  TRAN-TYPE-RECORD.                RECLN = 60
        05  TRAN-TYPE        PIC X(02).   -> tran_type       [0:2]  (primary key)
        05  TRAN-TYPE-DESC   PIC X(50).   -> tran_type_desc  [2:52]
        05  FILLER           PIC X(08).   -> dropped          [52:60]

The loader is idempotent: it issues a PostgreSQL
``INSERT ... ON CONFLICT (tran_type) DO UPDATE`` so re-running it is safe and
mirrors the legacy job's drop-and-recreate (``DELETE`` + ``DEFINE`` + ``REPRO``)
semantics without ever touching schema (Alembic owns all DDL).

Transaction ownership:
    The caller owns the transaction. :func:`LoadTranTypes` never calls
    ``commit()`` or ``rollback()``; it executes its work against the provided
    synchronous :class:`~sqlalchemy.orm.Session` so the orchestrator
    (``batch.orchestration.batch_chain``) or CLI (``batch.cli``) can compose
    several loaders into a single atomic unit of work.
"""

from __future__ import annotations

from pathlib import Path
from typing import Optional

from sqlalchemy.dialects.postgresql import insert as PgInsert
from sqlalchemy.orm import Session

from app.models.transaction_type import TransactionType

# Default seed directory: <repo-root>/app/data/ASCII. This file lives at
# <repo-root>/batch/loaders/load_tran_types.py, so three ``.parent`` hops
# (loaders -> batch -> repo root) reach the repository root before descending
# into the ASCII seed folder. The ASCII datasets are display-readable, so no
# EBCDIC decode is required for this reference table.
DEFAULT_DATA_DIR = Path(__file__).resolve().parent.parent.parent / "app" / "data" / "ASCII"

# Seed file name (legacy flat file AWS.M2.CARDDEMO.TRANTYPE.PS).
SEED_FILE_NAME = "trantype.txt"

# Fixed record length of TRAN-TYPE-RECORD (CVTRA03Y RECLN = 60), matching the
# VSAM RECORDSIZE(60 60) declared in TRANTYPE.jcl.
RECORD_LENGTH = 60

# ``latin-1`` guarantees a 1 byte == 1 character mapping for byte-accurate
# fixed-width slicing. The seed data is pure ASCII, so this decode never raises.
FILE_ENCODING = "latin-1"

# 0-indexed byte slices into each fixed-width record, per copybook CVTRA03Y.
TRAN_TYPE_SLICE = slice(0, 2)
TRAN_TYPE_DESC_SLICE = slice(2, 52)


def _ResolveDataDir(dataDir: Optional[Path]) -> Path:
    """Return the seed directory, defaulting to the repo ASCII data folder."""
    if dataDir is None:
        return DEFAULT_DATA_DIR
    return Path(dataDir)


def _ParseTranTypeRecord(record: str) -> dict:
    """Map one fixed-width TRAN-TYPE-RECORD to transaction_type column values."""
    return {
        "tran_type": record[TRAN_TYPE_SLICE].strip(),
        "tran_type_desc": record[TRAN_TYPE_DESC_SLICE].strip(),
    }


def _UpsertRows(session: Session, rowValues: list) -> None:
    """Idempotently upsert rows keyed on the primary key (caller owns the txn)."""
    statement = PgInsert(TransactionType).values(rowValues)
    primaryKeyColumns = [column.name for column in TransactionType.__table__.primary_key.columns]
    updateColumns = {
        column.name: statement.excluded[column.name]
        for column in TransactionType.__table__.columns
        if not column.primary_key
    }
    if updateColumns:
        statement = statement.on_conflict_do_update(
            index_elements=primaryKeyColumns,
            set_=updateColumns,
        )
    else:
        statement = statement.on_conflict_do_nothing(index_elements=primaryKeyColumns)
    session.execute(statement)


def LoadTranTypes(session: Session, dataDir: Optional[Path] = None) -> int:
    """Load trantype.txt into the transaction_type table. Returns rows processed."""
    seedDir = _ResolveDataDir(dataDir)
    seedPath = seedDir / SEED_FILE_NAME
    if not seedPath.is_file():
        raise FileNotFoundError(f"Transaction-type seed file not found: {seedPath}")
    rowValues = []
    with seedPath.open("r", encoding=FILE_ENCODING) as seedFile:
        for rawLine in seedFile:
            record = rawLine.rstrip("\r\n").ljust(RECORD_LENGTH)
            if not record.strip():
                continue
            rowValues.append(_ParseTranTypeRecord(record))
    if rowValues:
        _UpsertRows(session, rowValues)
    return len(rowValues)
