# Ported from IDCAMS load job app/jcl/DISCGRP.jcl (DELETE/DEFINE/REPRO of the
# disclosure-group VSAM KSDS); record layout app/cpy/CVTRA02Y.cpy
# (DIS-GROUP-RECORD, RECLN 50); seed data app/data/ASCII/discgrp.txt.
"""Idempotent seed loader for the CardDemo ``disclosure_group`` reference table.

Modern port of the legacy IDCAMS load job ``app/jcl/DISCGRP.jcl`` that deleted,
(re)defined, and ``REPRO``-copied the flat ``DISCGRP`` dataset into the
``AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS`` cluster. The mainframe cluster declared a
16-byte primary key at RKP 0 (``KEYS(16 0)``) over 50-byte fixed records
(``RECORDSIZE(50 50)``); those bytes are the three key fields of the
``DIS-GROUP-RECORD`` copybook layout (``app/cpy/CVTRA02Y.cpy``).

Each record maps to one row of the ``disclosure_group`` table -- a read-mostly
reference table whose composite primary key is the ``(group_id, tran_type_cd,
tran_cat_cd)`` triple and whose single payload column ``interest_rate`` carries
the monthly rate consumed by the interest-calculation batch job
(``CBACT04C`` -> ``batch/jobs/interest_calc.py``). Because that rate drives a
regulated finance-charge computation, exact-decimal fidelity is mandatory: the
rate is decoded from signed zoned-decimal DISPLAY into :class:`decimal.Decimal`
via :func:`app.utils.decimal_utils.DecodeZonedDecimal` and never touches a
binary ``float`` (AAP 0.7.1 Finding #1, 0.7.2 Finding #2).

Legacy record layout (``app/cpy/CVTRA02Y.cpy``, verified -- RECLN 50)::

    01  DIS-GROUP-RECORD.
        05  DIS-GROUP-KEY.                        *> 16-byte composite key, RKP=0
            10  DIS-ACCT-GROUP-ID  PIC X(10).     *> [0:10]  -> group_id
            10  DIS-TRAN-TYPE-CD   PIC X(02).     *> [10:12] -> tran_type_cd
            10  DIS-TRAN-CAT-CD    PIC 9(04).     *> [12:16] -> tran_cat_cd (str)
        05  DIS-INT-RATE           PIC S9(04)V99. *> [16:22] -> interest_rate
        05  FILLER                 PIC X(28).     *> [22:50] dropped (padding)

Transaction ownership:
    The caller owns the transaction boundary. :func:`LoadDisclosureGroups`
    receives an already-open synchronous :class:`~sqlalchemy.orm.Session`,
    performs a single bulk idempotent upsert, and returns the number of rows
    processed. It never calls ``commit`` or ``rollback`` -- the orchestrator
    (``batch.orchestration.batch_chain``) or CLI (``batch.cli``) composes
    loaders into one atomic unit of work via ``batch.db.GetSyncSession``.

Idempotency:
    Re-running the loader is safe. Rows are written with a PostgreSQL
    ``INSERT ... ON CONFLICT DO UPDATE`` keyed on the composite primary key, so
    a second run overwrites the ``interest_rate`` of existing rows rather than
    raising a duplicate-key error. The loader never issues DDL; the schema is
    owned exclusively by Alembic (``backend/alembic/versions/*``).

Example:
    >>> from batch.db import GetSyncSession
    >>> from batch.loaders.load_disclosure_groups import LoadDisclosureGroups
    >>> with GetSyncSession() as session:
    ...     rowsLoaded = LoadDisclosureGroups(session)   # loader does NOT commit
    >>> # transaction is committed by GetSyncSession on clean exit
"""

from __future__ import annotations

from decimal import Decimal
from pathlib import Path
from typing import Optional

from sqlalchemy.dialects.postgresql import insert as PgInsert
from sqlalchemy.orm import Session

from app.models.disclosure_group import DisclosureGroup
from app.utils.decimal_utils import DecodeZonedDecimal

# ---------------------------------------------------------------------------
# Constants (ALL_UPPERCASE per the Ochs Rule)
# ---------------------------------------------------------------------------

# Seed file name within the ASCII data directory (legacy DISCGRP flat file).
SEED_FILE_NAME = "discgrp.txt"

# Fixed record length of DIS-GROUP-RECORD (RECLN 50 / RECORDSIZE(50 50)).
RECORD_LENGTH = 50

# The ASCII seed datasets are display-readable single-byte text; latin-1 maps
# every byte 1:1 to a code point, so no byte is ever lost when reading (unlike
# strict UTF-8, which would reject high-bit overpunch/sign bytes).
FILE_ENCODING = "latin-1"

# Implied fractional-digit count for the V99 signed zoned-decimal rate field.
MONEY_SCALE = 2

# Default seed directory: <repo-root>/app/data/ASCII. This file lives at
# <repo-root>/batch/loaders/, so three ``.parent`` hops reach the repo root.
DEFAULT_DATA_DIR = Path(__file__).resolve().parent.parent.parent / "app" / "data" / "ASCII"

# Fixed-width field slices into a 50-byte DIS-GROUP-RECORD (0-based, end-exclusive).
GROUP_ID_SLICE = slice(0, 10)  # DIS-ACCT-GROUP-ID PIC X(10)
TRAN_TYPE_CD_SLICE = slice(10, 12)  # DIS-TRAN-TYPE-CD  PIC X(02)
TRAN_CAT_CD_SLICE = slice(12, 16)  # DIS-TRAN-CAT-CD   PIC 9(04)
INTEREST_RATE_SLICE = slice(16, 22)  # DIS-INT-RATE      PIC S9(04)V99


# ---------------------------------------------------------------------------
# Private helpers (module-internal; not part of the public API)
# ---------------------------------------------------------------------------


def _ParseDisclosureGroupRecord(record: str) -> dict[str, str | Decimal]:
    """Map one fixed-width DIS-GROUP-RECORD to disclosure_group column values.

    The returned mapping is keyed by the **real** ORM column names of
    :class:`~app.models.disclosure_group.DisclosureGroup` (``group_id``, not the
    ``acct_group_id`` synonym), so it feeds a SQLAlchemy Core insert directly.

    Args:
        record: A single 50-byte record, already right-padded to
            :data:`RECORD_LENGTH`.

    Returns:
        A dict with keys ``group_id``, ``tran_type_cd``, ``tran_cat_cd`` (all
        stripped strings; ``tran_cat_cd`` keeps its leading zeros) and
        ``interest_rate`` (an exact :class:`decimal.Decimal`, never a float).
    """
    return {
        "group_id": record[GROUP_ID_SLICE].strip(),
        "tran_type_cd": record[TRAN_TYPE_CD_SLICE].strip(),
        "tran_cat_cd": record[TRAN_CAT_CD_SLICE].strip(),
        "interest_rate": DecodeZonedDecimal(record[INTEREST_RATE_SLICE], MONEY_SCALE),
    }


def _ResolveDataDir(dataDir: Optional[Path]) -> Path:
    """Resolve the effective ASCII seed directory, verifying it exists.

    Args:
        dataDir: An explicit directory to load from, or ``None`` to use
            :data:`DEFAULT_DATA_DIR`.

    Returns:
        The resolved seed directory as a :class:`~pathlib.Path`.

    Raises:
        FileNotFoundError: If the resolved directory does not exist.
    """
    resolvedDir = DEFAULT_DATA_DIR if dataDir is None else Path(dataDir)
    if not resolvedDir.is_dir():
        raise FileNotFoundError(f"Seed data directory not found: {resolvedDir}")
    return resolvedDir


def _UpsertRows(session: Session, rowValues: list[dict[str, str | Decimal]]) -> None:
    """Bulk idempotent upsert of parsed rows on the composite primary key.

    Derives the conflict target from the model's primary-key columns, so the
    composite ``(group_id, tran_type_cd, tran_cat_cd)`` key is handled
    automatically without hardcoding key names. Non-key columns are refreshed
    from the proposed (``excluded``) row on conflict, making re-runs idempotent.

    Args:
        session: An open synchronous session; the caller owns its transaction.
        rowValues: The parsed rows to write, keyed by real column names.
    """
    if not rowValues:
        return
    disclosureGroupTable = DisclosureGroup.__table__
    primaryKeyColumns = [column.name for column in disclosureGroupTable.primary_key.columns]
    insertStatement = PgInsert(disclosureGroupTable).values(rowValues)
    updatableColumns = {
        column.name: insertStatement.excluded[column.name]
        for column in disclosureGroupTable.columns
        if column.name not in primaryKeyColumns
    }
    upsertStatement = insertStatement.on_conflict_do_update(
        index_elements=primaryKeyColumns,
        set_=updatableColumns,
    )
    session.execute(upsertStatement)


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------


def LoadDisclosureGroups(session: Session, dataDir: Optional[Path] = None) -> int:
    """Load the ``disclosure_group`` reference table from ``discgrp.txt``.

    Reads the fixed-width ASCII seed file, decodes each 50-byte record per the
    ``CVTRA02Y`` layout (with the signed zoned-decimal interest rate decoded to
    an exact :class:`decimal.Decimal`), and performs a single bulk idempotent
    upsert on the composite primary key.

    The caller owns the transaction: this function never commits or rolls back.

    Args:
        session: An open synchronous :class:`~sqlalchemy.orm.Session`.
        dataDir: Optional override for the ASCII seed directory; defaults to
            :data:`DEFAULT_DATA_DIR`.

    Returns:
        The number of records processed (non-blank rows read from the seed file).

    Raises:
        FileNotFoundError: If the seed directory or the ``discgrp.txt`` file is
            missing.
        ValueError: If a record carries a malformed zoned-decimal rate (raised
            by :func:`app.utils.decimal_utils.DecodeZonedDecimal`).
    """
    seedPath = _ResolveDataDir(dataDir) / SEED_FILE_NAME
    if not seedPath.is_file():
        raise FileNotFoundError(f"Disclosure-group seed file not found: {seedPath}")

    rowValues: list[dict[str, str | Decimal]] = []
    with seedPath.open(encoding=FILE_ENCODING) as seedFile:
        for rawLine in seedFile:
            record = rawLine.rstrip("\r\n").ljust(RECORD_LENGTH)
            if record.strip() == "":
                continue
            rowValues.append(_ParseDisclosureGroupRecord(record))

    _UpsertRows(session, rowValues)
    return len(rowValues)
