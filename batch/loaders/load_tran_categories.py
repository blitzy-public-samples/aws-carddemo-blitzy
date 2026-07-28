# Ported from IDCAMS load job app/jcl/TRANCATG.jcl (DELETE/DEFINE/REPRO of the
# transaction-category VSAM KSDS); record layout app/cpy/CVTRA04Y.cpy
# (TRAN-CAT-RECORD, RECLN 60); seed data app/data/ASCII/trancatg.txt.
"""Seed loader for the ``transaction_category`` reference table.

This module is the modern port of the legacy IDCAMS load job
``app/jcl/TRANCATG.jcl`` that refreshed the ``TRANCATG`` VSAM KSDS from the
flat file ``AWS.M2.CARDDEMO.TRANCATG.PS``. It reads the display-readable ASCII
seed ``app/data/ASCII/trancatg.txt`` and upserts one row per record into the
PostgreSQL ``transaction_category`` table modelled by
:class:`app.models.transaction_category.TransactionCategory`.

``transaction_category`` is a foundational reference (lookup) table: it has a
composite primary key ``(tran_type_cd, tran_cat_cd)`` and declares no foreign
keys, so this loader has no upstream data dependency and can run early in the
batch chain.

Legacy record layout (``app/cpy/CVTRA04Y.cpy``, RECLN = 60 bytes)::

    01  TRAN-CAT-RECORD.
        05  TRAN-CAT-KEY.
            10  TRAN-TYPE-CD        PIC X(02).   -> tran_type_cd  [0:2]
            10  TRAN-CAT-CD         PIC 9(04).   -> tran_cat_cd   [2:6]
        05  TRAN-CAT-TYPE-DESC      PIC X(50).   -> tran_cat_type_desc [6:56]
        05  FILLER                  PIC X(04).   -> dropped (parity only) [56:60]

``TRAN-CAT-CD`` is a COBOL numeric picture, but its leading zeros are
significant (for example ``"0001"``), and the ORM column is a fixed-width
``VARCHAR(4)`` string. It is therefore loaded as a stripped string and never
coerced with :func:`int`, so the zero padding survives the round trip.

Transaction ownership:
    The caller owns the transaction boundary. :func:`LoadTranCategories`
    performs its writes on the ``session`` handed to it and never calls
    ``commit`` or ``rollback``; the orchestrator
    (``batch.orchestration.batch_chain``) or CLI (``batch.cli``) composes one or
    more loaders into a single atomic unit of work and decides when to commit.

Idempotency:
    Rows are written with a PostgreSQL ``INSERT ... ON CONFLICT`` upsert keyed
    on the table's primary key, so re-running the loader refreshes existing rows
    rather than failing on duplicates. The loader never issues DDL: the schema
    is owned exclusively by Alembic (``backend/alembic/versions/*``).
"""

from __future__ import annotations

from pathlib import Path
from typing import Optional

from sqlalchemy.dialects.postgresql import insert as PgInsert
from sqlalchemy.orm import Session

from app.models.transaction_category import TransactionCategory

# Basename of the display-readable ASCII seed file (REPRO INFILE in TRANCATG.jcl).
SEED_FILE_NAME = "trancatg.txt"

# Fixed record length of the legacy TRAN-CAT-RECORD (CVTRA04Y RECLN = 60 bytes).
RECORD_LENGTH = 60

# The ASCII seed is single-byte; latin-1 maps every byte 1:1 to a code point and
# never raises on decode, so fixed-width column offsets stay byte-accurate.
FILE_ENCODING = "latin-1"

# Default seed directory: <repo-root>/app/data/ASCII. This file lives at
# <repo-root>/batch/loaders/, so three ``.parent`` hops reach the repository root.
DEFAULT_DATA_DIR = Path(__file__).resolve().parent.parent.parent / "app" / "data" / "ASCII"

# Column offsets within the fixed-width record (0-based, end-exclusive), matching
# the CVTRA04Y byte layout above. The 4-byte trailing FILLER [56:60] is dropped.
TRAN_TYPE_CD_SLICE = slice(0, 2)
TRAN_CAT_CD_SLICE = slice(2, 6)
TRAN_CAT_TYPE_DESC_SLICE = slice(6, 56)


def _ResolveDataDir(dataDir: Optional[Path]) -> Path:
    """Return the seed directory, defaulting to :data:`DEFAULT_DATA_DIR`.

    Args:
        dataDir: An explicit directory holding the seed file, or ``None`` to use
            the packaged default ``app/data/ASCII`` location.

    Returns:
        The directory that should contain :data:`SEED_FILE_NAME`.
    """
    if dataDir is None:
        return DEFAULT_DATA_DIR
    return dataDir


def _ParseTranCategoryRecord(record: str) -> dict[str, str]:
    """Parse one fixed-width record into a ``transaction_category`` row dict.

    The returned dict is keyed by the exact ORM column names so it can be handed
    straight to the upsert statement. ``tran_cat_cd`` is intentionally kept as a
    stripped string to preserve its significant leading zeros.

    Args:
        record: A single record already normalised to at least
            :data:`RECORD_LENGTH` characters.

    Returns:
        A dict with keys ``tran_type_cd``, ``tran_cat_cd`` and
        ``tran_cat_type_desc``.
    """
    return {
        "tran_type_cd": record[TRAN_TYPE_CD_SLICE].strip(),
        "tran_cat_cd": record[TRAN_CAT_CD_SLICE].strip(),
        "tran_cat_type_desc": record[TRAN_CAT_TYPE_DESC_SLICE].strip(),
    }


def _UpsertRows(session: Session, rowValues: list[dict[str, str]]) -> None:
    """Idempotently upsert ``transaction_category`` rows on the composite PK.

    Builds a single PostgreSQL ``INSERT ... ON CONFLICT`` statement. The primary
    key columns are derived generically from the model's table metadata, so a
    single- or multi-column primary key is handled automatically (here the
    composite ``(tran_type_cd, tran_cat_cd)`` key). On conflict, every non-key
    column is refreshed from the proposed row; when the table has no non-key
    column, the statement degrades to ``ON CONFLICT DO NOTHING``.

    Args:
        session: An open synchronous session owned by the caller. This function
            executes on it but never commits or rolls back.
        rowValues: The parsed row dicts to write; a no-op when empty.
    """
    if not rowValues:
        return

    tableColumns = TransactionCategory.__table__.columns
    primaryKeyColumns = TransactionCategory.__table__.primary_key.columns
    pkColumnNames = [column.name for column in primaryKeyColumns]
    nonPkColumnNames = [
        column.name for column in tableColumns if column.name not in pkColumnNames
    ]

    insertStmt = PgInsert(TransactionCategory).values(rowValues)
    if nonPkColumnNames:
        upsertStmt = insertStmt.on_conflict_do_update(
            index_elements=pkColumnNames,
            set_={name: insertStmt.excluded[name] for name in nonPkColumnNames},
        )
    else:
        upsertStmt = insertStmt.on_conflict_do_nothing(index_elements=pkColumnNames)

    session.execute(upsertStmt)


def LoadTranCategories(session: Session, dataDir: Optional[Path] = None) -> int:
    """Load the ``transaction_category`` reference table from the ASCII seed.

    Reads every fixed-width record from ``<dataDir>/trancatg.txt``, parses it
    into a ``transaction_category`` row, and upserts the whole batch on the
    table's composite primary key. Re-running the loader is safe and simply
    refreshes existing rows (idempotent ON CONFLICT upsert).

    Args:
        session: An open synchronous :class:`~sqlalchemy.orm.Session`. The caller
            owns the transaction; this function never commits or rolls back.
        dataDir: Directory containing :data:`SEED_FILE_NAME`. Defaults to the
            packaged ``app/data/ASCII`` directory when ``None``.

    Returns:
        The number of seed records processed (rows upserted).

    Raises:
        FileNotFoundError: If the seed file does not exist under ``dataDir``.
    """
    seedDir = _ResolveDataDir(dataDir)
    seedPath = seedDir / SEED_FILE_NAME
    if not seedPath.is_file():
        raise FileNotFoundError(f"Transaction-category seed file not found: {seedPath}")

    rowValues: list[dict[str, str]] = []
    with seedPath.open("r", encoding=FILE_ENCODING) as seedFile:
        for rawLine in seedFile:
            if not rawLine.strip():
                # Skip blank lines (trailing newline at EOF, stray separators).
                continue
            record = rawLine.rstrip("\r\n").ljust(RECORD_LENGTH)
            rowValues.append(_ParseTranCategoryRecord(record))

    _UpsertRows(session, rowValues)
    return len(rowValues)


__all__ = ["LoadTranCategories"]
