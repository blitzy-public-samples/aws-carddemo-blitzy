# Ported from IDCAMS load job app/jcl/CUSTFILE.jcl (DELETE/DEFINE/REPRO of the
# customer VSAM KSDS); record layout app/cpy/CVCUS01Y.cpy
# (CUSTOMER-RECORD, RECLN 500); seed data app/data/ASCII/custdata.txt.
"""Seed loader for the ``customers`` table.

Modern port of the legacy IDCAMS load job ``CUSTFILE`` (``app/jcl/CUSTFILE.jcl``)
that refreshed the CUSTDATA VSAM KSDS by ``DELETE`` / ``DEFINE`` / ``REPRO`` of a
fixed-width sequential seed dataset. Here the same seed data
(``app/data/ASCII/custdata.txt``) is decoded per the ``CUSTOMER-RECORD`` copybook
layout (``app/cpy/CVCUS01Y.cpy``, RECLN 500) and upserted into the PostgreSQL
``customers`` table backed by :class:`app.models.customer.Customer`.

FK-safe ordering
    ``customers`` carries no outgoing foreign key, but it is the *parent* of both
    ``accounts`` and ``card_xref``. This loader therefore has to run BEFORE those
    two in the seed chain, mirroring the legacy ordering in which ``CUSTFILE``
    precedes ``ACCTFILE`` and ``XREFFILE``.

Transaction ownership
    :func:`LoadCustomers` never commits or rolls back. It writes through the
    caller-supplied synchronous :class:`~sqlalchemy.orm.Session`, so the batch
    orchestrator or the CLI can compose several loaders into a single atomic unit
    of work (see :func:`batch.db.GetSyncSession`).

Idempotency
    Rows are applied with a PostgreSQL ``INSERT ... ON CONFLICT (cust_id) DO
    UPDATE`` upsert, so re-running the loader converges to the same state without
    duplicate-key errors. This module issues no DDL whatsoever; the schema is
    owned exclusively by Alembic (``backend/alembic/versions/*``).

Numeric fidelity
    There are no monetary or interest-rate fields on this record, so no
    zoned-decimal / ``Decimal`` handling is required (and ``DecodeZonedDecimal``
    is deliberately NOT imported). ``cust_id`` and ``ssn`` are COBOL
    ``PIC 9(n)`` identifiers kept as strings so their leading zeros survive
    byte-for-byte; ``fico_credit_score`` is an ordinary integer and
    ``date_of_birth`` a native ``date``.
"""

from __future__ import annotations

from datetime import date
from pathlib import Path
from typing import Any, Optional

from sqlalchemy.dialects.postgresql import insert as PostgresInsert
from sqlalchemy.orm import Session

from app.models.customer import Customer
from app.utils.date_utils import ParseLegacyDate


# --- Seed-file constants -----------------------------------------------------
# Name of the ASCII seed file. It is the display-readable stand-in for the
# EBCDIC .PS dataset the legacy REPRO copied into the VSAM KSDS.
SEED_FILE_NAME = "custdata.txt"

# Fixed COBOL record length of CUSTOMER-RECORD (CVCUS01Y): RECLN 500. Short lines
# are right-padded to this width so the trailing fixed-width fields still decode.
RECORD_LENGTH = 500

# The ASCII seed rows are single-byte text; latin-1 round-trips every byte value
# 0x00-0xFF without decode errors, matching the legacy single-byte code page.
FILE_ENCODING = "latin-1"

# Repository-root-relative default location of the ASCII seed datasets. This file
# lives at <repo>/batch/loaders/load_customers.py, so ``parents[2]`` is <repo>.
DEFAULT_DATA_DIR = Path(__file__).resolve().parents[2] / "app" / "data" / "ASCII"


# --- Field slices (0-indexed), one per CUSTOMER-RECORD field (CVCUS01Y) -------
# Each constant is the byte range of one COBOL field. The trailing
# ``FILLER PIC X(168)`` at [332:500] carries no business meaning and is dropped;
# it existed only to pad the record to its fixed 500-byte length.
CUST_ID_SLICE = slice(0, 9)                  # CUST-ID PIC 9(09)   -> cust_id (PK)
FIRST_NAME_SLICE = slice(9, 34)              # CUST-FIRST-NAME PIC X(25)
MIDDLE_NAME_SLICE = slice(34, 59)            # CUST-MIDDLE-NAME PIC X(25)
LAST_NAME_SLICE = slice(59, 84)              # CUST-LAST-NAME PIC X(25)
ADDR_LINE_1_SLICE = slice(84, 134)           # CUST-ADDR-LINE-1 PIC X(50)
ADDR_LINE_2_SLICE = slice(134, 184)          # CUST-ADDR-LINE-2 PIC X(50)
ADDR_LINE_3_SLICE = slice(184, 234)          # CUST-ADDR-LINE-3 PIC X(50)
ADDR_STATE_CD_SLICE = slice(234, 236)        # CUST-ADDR-STATE-CD PIC X(02)
ADDR_COUNTRY_CD_SLICE = slice(236, 239)      # CUST-ADDR-COUNTRY-CD PIC X(03)
ADDR_ZIP_SLICE = slice(239, 249)             # CUST-ADDR-ZIP PIC X(10)
PHONE_NUM_1_SLICE = slice(249, 264)          # CUST-PHONE-NUM-1 PIC X(15)
PHONE_NUM_2_SLICE = slice(264, 279)          # CUST-PHONE-NUM-2 PIC X(15)
SSN_SLICE = slice(279, 288)                  # CUST-SSN PIC 9(09) -> ssn (string)
GOVT_ISSUED_ID_SLICE = slice(288, 308)       # CUST-GOVT-ISSUED-ID PIC X(20)
DATE_OF_BIRTH_SLICE = slice(308, 318)        # CUST-DOB-YYYY-MM-DD PIC X(10)
EFT_ACCOUNT_ID_SLICE = slice(318, 328)       # CUST-EFT-ACCOUNT-ID PIC X(10)
PRI_CARD_HOLDER_IND_SLICE = slice(328, 329)  # CUST-PRI-CARD-HOLDER-IND PIC X(01)
FICO_CREDIT_SCORE_SLICE = slice(329, 332)    # CUST-FICO-CREDIT-SCORE PIC 9(03)


def _OptionalText(rawValue: str) -> Optional[str]:
    """Return the trimmed text, or None when the field is blank."""
    trimmed = rawValue.strip()
    if not trimmed:
        return None
    return trimmed


def _OptionalDate(rawValue: str) -> Optional[date]:
    """Return a parsed date, or None when the field is blank."""
    trimmed = rawValue.strip()
    if not trimmed:
        return None
    return ParseLegacyDate(trimmed)


def _OptionalInt(rawValue: str) -> Optional[int]:
    """Return an int, or None when the field is blank."""
    trimmed = rawValue.strip()
    if not trimmed:
        return None
    return int(trimmed)


def _ResolveDataDir(dataDir: Optional[Path]) -> Path:
    """Return the caller's data directory, or the packaged default.

    Args:
        dataDir: An explicit ASCII seed directory, or None to fall back to the
            repository default (``app/data/ASCII``).

    Returns:
        The directory expected to contain the ``custdata.txt`` seed file.
    """
    if dataDir is not None:
        return dataDir
    return DEFAULT_DATA_DIR


def _ParseCustomerRecord(record: str) -> dict[str, Any]:
    """Decode one fixed-width CUSTOMER-RECORD line into a column dict.

    The returned mapping is keyed by the exact ``customers`` column names on
    :class:`app.models.customer.Customer`. ``cust_id`` and ``ssn`` stay strings
    so their leading zeros survive (they are COBOL ``PIC 9(n)`` identifiers, not
    arithmetic values); ``date_of_birth`` becomes a real ``date`` and
    ``fico_credit_score`` a real ``int``. Required text fields are trimmed
    directly, while genuinely optional fields collapse blanks to ``None``.

    Args:
        record: A single CUSTOMER-RECORD line, already right-padded to
            :data:`RECORD_LENGTH`.

    Returns:
        A dict mapping each ``customers`` column name to its decoded value.
    """
    return {
        "cust_id": record[CUST_ID_SLICE].strip(),
        "first_name": record[FIRST_NAME_SLICE].strip(),
        "middle_name": _OptionalText(record[MIDDLE_NAME_SLICE]),
        "last_name": record[LAST_NAME_SLICE].strip(),
        "addr_line_1": record[ADDR_LINE_1_SLICE].strip(),
        "addr_line_2": _OptionalText(record[ADDR_LINE_2_SLICE]),
        "addr_line_3": _OptionalText(record[ADDR_LINE_3_SLICE]),
        "addr_state_cd": record[ADDR_STATE_CD_SLICE].strip(),
        "addr_country_cd": record[ADDR_COUNTRY_CD_SLICE].strip(),
        "addr_zip": record[ADDR_ZIP_SLICE].strip(),
        "phone_num_1": record[PHONE_NUM_1_SLICE].strip(),
        "phone_num_2": record[PHONE_NUM_2_SLICE].strip(),
        "ssn": record[SSN_SLICE].strip(),
        "govt_issued_id": record[GOVT_ISSUED_ID_SLICE].strip(),
        "date_of_birth": _OptionalDate(record[DATE_OF_BIRTH_SLICE]),
        "eft_account_id": _OptionalText(record[EFT_ACCOUNT_ID_SLICE]),
        "pri_card_holder_ind": record[PRI_CARD_HOLDER_IND_SLICE].strip(),
        "fico_credit_score": _OptionalInt(record[FICO_CREDIT_SCORE_SLICE]),
    }


def _UpsertRows(session: Session, customerRows: list[dict[str, Any]]) -> None:
    """Bulk-upsert customer rows on the primary key, idempotently.

    Emits a single PostgreSQL ``INSERT ... ON CONFLICT DO UPDATE`` statement so
    repeated runs converge to the same rows without duplicate-key errors. The
    conflict target and the updated column set are derived from the mapped table
    rather than hardcoded, so this stays correct if the model gains or drops a
    column. The insert is issued at the Core/``Table`` level, which never
    triggers ORM mapper configuration.

    Args:
        session: The caller-owned synchronous session (never committed here).
        customerRows: Column dicts produced by :func:`_ParseCustomerRecord`.
    """
    if not customerRows:
        return
    customerTable = Customer.__table__
    insertStatement = PostgresInsert(customerTable).values(customerRows)
    primaryKeyNames = [column.name for column in customerTable.primary_key.columns]
    updatedColumns = {
        column.name: insertStatement.excluded[column.name]
        for column in customerTable.columns
        if column.name not in primaryKeyNames
    }
    upsertStatement = insertStatement.on_conflict_do_update(
        index_elements=primaryKeyNames,
        set_=updatedColumns,
    )
    session.execute(upsertStatement)


def LoadCustomers(session: Session, dataDir: Optional[Path] = None) -> int:
    """Load the customer master seed into the ``customers`` table.

    Reads ``custdata.txt`` from the resolved data directory, decodes every
    fixed-width CUSTOMER-RECORD line, and upserts the rows through the
    caller-supplied session. The caller owns the transaction, so this function
    performs no ``commit`` or ``rollback`` of its own.

    Args:
        session: An open synchronous session that owns the transaction.
        dataDir: The ASCII seed directory; defaults to ``app/data/ASCII``.

    Returns:
        The number of customer rows processed (upserted).

    Raises:
        FileNotFoundError: If the ``custdata.txt`` seed file does not exist.
        ValueError: If a populated date-of-birth field is not ``YYYY-MM-DD`` or a
            populated FICO field is not numeric.
    """
    seedPath = _ResolveDataDir(dataDir) / SEED_FILE_NAME
    if not seedPath.is_file():
        raise FileNotFoundError(f"Customer seed file not found: {seedPath}")
    customerRows: list[dict[str, Any]] = []
    with seedPath.open(encoding=FILE_ENCODING) as seedFile:
        for rawLine in seedFile:
            record = rawLine.rstrip("\r\n").ljust(RECORD_LENGTH)
            if not record.strip():
                continue
            customerRows.append(_ParseCustomerRecord(record))
    _UpsertRows(session, customerRows)
    return len(customerRows)
