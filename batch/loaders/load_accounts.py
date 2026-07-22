# Ported from IDCAMS load job app/jcl/ACCTFILE.jcl (DELETE/DEFINE/REPRO of the
# account VSAM KSDS, KEYS(11,0)); record layout app/cpy/CVACT01Y.cpy
# (ACCOUNT-RECORD, RECLN 300); seed data app/data/ASCII/acctdata.txt.
"""Idempotent seed loader for the CardDemo ``accounts`` table.

This module is the Python reimplementation of the legacy mainframe IDCAMS load
job ``ACCTFILE`` (``app/jcl/ACCTFILE.jcl``), which deleted, redefined, and then
``REPRO``-loaded the ``ACCTDATA`` VSAM KSDS (``KEYS(11,0)``, ``RECORDSIZE(300
300)``) from the sequential seed dataset. Here that flat, fixed-width dataset is
read from ``app/data/ASCII/acctdata.txt`` and upserted into the modern
PostgreSQL ``accounts`` table behind :class:`app.models.account.Account`.

Record fidelity
    Each 300-byte record follows the ``ACCOUNT-RECORD`` copybook ``CVACT01Y``.
    The byte offsets encoded in the ``*_SLICE`` constants below are the
    authoritative, data-verified positions (validated against all 50 seed rows):
    the three ``PIC X(10)`` date fields (open/expiration/reissue) sit *between*
    ``cash_credit_limit`` and the two cycle amounts, so the cycle fields live at
    ``[78:90]``/``[90:102]`` -- not at the draft ``[48:60]``/``[60:72]``.

Numeric fidelity
    The five monetary fields are signed zoned-decimal DISPLAY (``PIC
    S9(10)V99``), NOT ``COMP-3`` packed decimal (AAP section 0.7.1, Finding #1).
    They are decoded through :func:`app.utils.decimal_utils.DecodeZonedDecimal`
    into exact :class:`decimal.Decimal` values; ``float`` is never used, because
    binary floating point would violate the regulatory numeric-parity
    requirement for currency amounts.

Transaction ownership
    :func:`LoadAccounts` receives an already-open synchronous
    :class:`sqlalchemy.orm.Session` and performs its work within the caller's
    transaction. It never calls ``commit`` or ``rollback`` -- the orchestrator
    (``batch.orchestration.batch_chain``) or the CLI (``batch.cli``) owns the
    unit-of-work boundary via ``batch.db.GetSyncSession`` so several loaders can
    compose into one atomic transaction. The upsert is idempotent (``INSERT ...
    ON CONFLICT`` on the primary key), so the loader is safely re-runnable, and
    it never issues DDL -- the schema is owned exclusively by Alembic.

Load ordering
    ``accounts`` references ``disclosure_group`` logically via ``group_id`` and
    is itself referenced by ``cards`` / ``card_xref``. This loader must therefore
    run AFTER ``customers`` / ``disclosure_group`` and BEFORE ``cards`` /
    ``card_xref`` in the batch chain.

Example:
    Compose the loader inside a caller-owned unit of work::

        from batch.db import GetSyncSession
        from batch.loaders.load_accounts import LoadAccounts

        with GetSyncSession() as session:
            rowsProcessed = LoadAccounts(session)   # loader does NOT commit
        # transaction is committed here, exactly once, on clean exit
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from pathlib import Path
from typing import Optional

from sqlalchemy.dialects.postgresql import insert as PostgresInsert
from sqlalchemy.dialects.sqlite import insert as SqliteInsert
from sqlalchemy.orm import Session

from app.models.account import Account
from app.utils.date_utils import ParseLegacyDate
from app.utils.decimal_utils import DecodeZonedDecimal

__all__ = ["LoadAccounts"]

# ---------------------------------------------------------------------------
# Constants (Ochs Rule: ALL_UPPERCASE). The ``*_SLICE`` constants are the
# AUTHORITATIVE, data-verified byte offsets of the 300-byte ACCOUNT-RECORD
# (copybook CVACT01Y). The field widths sum exactly to the record length:
#   11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12 + 10 + 10 + 178 = 300.
# ---------------------------------------------------------------------------

# Seed dataset file name (the ASCII REPRO input for the ACCTFILE load job).
SEED_FILE_NAME = "acctdata.txt"

# Fixed record length of ACCOUNT-RECORD (CVACT01Y RECLN 300; VSAM RECORDSIZE 300).
RECORD_LENGTH = 300

# The ASCII seed files are display-readable single-byte text; latin-1 maps every
# byte 0x00-0xFF 1:1 to a code point so no byte is ever lost on read.
FILE_ENCODING = "latin-1"

# Implied fractional-digit count for every ``V99`` monetary field.
MONEY_SCALE = 2

# --- Field byte offsets within the 300-byte ACCOUNT-RECORD (CVACT01Y). ---
# ACCT-ID PIC 9(11) -> VARCHAR(11) string (leading zeros preserved; PRIMARY KEY).
ACCT_ID_SLICE = slice(0, 11)
# ACCT-ACTIVE-STATUS PIC X(01) -> CHAR(1).
ACTIVE_STATUS_SLICE = slice(11, 12)
# ACCT-CURR-BAL PIC S9(10)V99 -> NUMERIC(12,2) (signed zoned decimal, 12 bytes).
CURR_BAL_SLICE = slice(12, 24)
# ACCT-CREDIT-LIMIT PIC S9(10)V99 -> NUMERIC(12,2).
CREDIT_LIMIT_SLICE = slice(24, 36)
# ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 -> NUMERIC(12,2).
CASH_CREDIT_LIMIT_SLICE = slice(36, 48)
# ACCT-OPEN-DATE PIC X(10) -> DATE (nullable).
OPEN_DATE_SLICE = slice(48, 58)
# ACCT-EXPIRAION-DATE (sic) PIC X(10) -> DATE (nullable).
EXPIRATION_DATE_SLICE = slice(58, 68)
# ACCT-REISSUE-DATE PIC X(10) -> DATE (nullable).
REISSUE_DATE_SLICE = slice(68, 78)
# ACCT-CURR-CYC-CREDIT PIC S9(10)V99 -> NUMERIC(12,2) (cycle field, NOT [48:60]).
CURR_CYC_CREDIT_SLICE = slice(78, 90)
# ACCT-CURR-CYC-DEBIT PIC S9(10)V99 -> NUMERIC(12,2).
CURR_CYC_DEBIT_SLICE = slice(90, 102)
# ACCT-ADDR-ZIP PIC X(10) -> VARCHAR(10) (see the seed quirk in _ParseAccountRecord).
ADDR_ZIP_SLICE = slice(102, 112)
# ACCT-GROUP-ID PIC X(10) -> VARCHAR(10) (see the seed quirk in _ParseAccountRecord).
GROUP_ID_SLICE = slice(112, 122)
# FILLER PIC X(178) at [122:300] is intentionally dropped (record padding only).

# Ledger-owned account columns that the batch chain MUTATES after seeding: the
# posting job (CBTRN02C / batch.jobs.post_transactions._UpdateAccount) adds each
# daily amount to curr_bal and to curr_cyc_credit/curr_cyc_debit, and the
# interest job (CBACT04C / batch.jobs.interest_calc) further increases curr_bal.
# These running balances belong to the ledger, not to the static seed, so the
# loader's INSERT ... ON CONFLICT must NEVER reset them on a re-load; doing so
# would wipe posted balances and break batch-chain idempotency (AAP 0.7.6; QA
# finding F-6). Static account attributes (active_status, credit_limit,
# cash_credit_limit, the dates, addr_zip, group_id) are safe to refresh and are
# therefore NOT excluded. credit_limit/cash_credit_limit are static account
# terms set at account setup -- posting/interest never mutate them -- so they
# remain refreshable despite also being monetary.
LEDGER_OWNED_COLUMNS = ("curr_bal", "curr_cyc_credit", "curr_cyc_debit")


# ---------------------------------------------------------------------------
# Private helpers (module-internal; not part of the public API).
# ---------------------------------------------------------------------------


def _OptionalText(rawText: str) -> Optional[str]:
    """Return the stripped field text, or ``None`` when it is blank/spaces.

    Mirrors the legacy treatment of an all-spaces fixed-width field as "no
    value", so a blank column becomes SQL ``NULL`` rather than an empty string.

    Args:
        rawText: The raw fixed-width field slice.

    Returns:
        The stripped text, or ``None`` when the field holds only whitespace.
    """
    strippedText = rawText.strip()
    return strippedText if strippedText else None


def _OptionalDate(rawText: str) -> Optional[date]:
    """Parse a legacy ``YYYY-MM-DD`` date field, guarding blanks to ``None``.

    The account record leaves lifecycle dates blank (spaces) when the event has
    not occurred. Because :func:`app.utils.date_utils.ParseLegacyDate` raises on
    a blank string, this helper guards the empty case first and only parses a
    non-blank value.

    Args:
        rawText: The raw fixed-width ``PIC X(10)`` date field slice.

    Returns:
        The parsed :class:`datetime.date`, or ``None`` when the field is blank.
    """
    strippedText = rawText.strip()
    if not strippedText:
        return None
    return ParseLegacyDate(strippedText)


def _DecodeMoney(rawValue: str) -> Decimal:
    """Decode one signed zoned-decimal DISPLAY money field to an exact ``Decimal``.

    Thin wrapper over :func:`app.utils.decimal_utils.DecodeZonedDecimal` that
    applies the fixed :data:`MONEY_SCALE` (2) shared by every ``PIC S9(10)V99``
    account amount. The sign is carried as an overpunch in the final byte and the
    decimal point is implied; the result is always a :class:`decimal.Decimal`
    (never ``float``), preserving exact regulatory numeric parity.

    Args:
        rawValue: The raw 12-byte fixed-width monetary field slice.

    Returns:
        The decoded amount as a :class:`decimal.Decimal` with scale 2.
    """
    return DecodeZonedDecimal(rawValue, MONEY_SCALE)


def _ParseAccountRecord(record: str) -> dict:
    """Map one fixed-width ACCOUNT-RECORD to ``accounts`` column values.

    The returned dict is keyed by the real ``accounts`` column names (confirmed
    against :class:`app.models.account.Account`), so it can be handed directly to
    the ORM-table upsert.

    Args:
        record: A single 300-byte ``ACCOUNT-RECORD`` line (newline stripped).

    Returns:
        A dict of column-name -> decoded value for one account row.
    """
    return {
        # ACCT-ID: keep as text so the 11-digit leading zeros survive (PK).
        "acct_id": record[ACCT_ID_SLICE].strip(),
        "active_status": record[ACTIVE_STATUS_SLICE].strip(),
        # All five monetary fields: signed zoned-decimal DISPLAY -> Decimal.
        "curr_bal": _DecodeMoney(record[CURR_BAL_SLICE]),
        "credit_limit": _DecodeMoney(record[CREDIT_LIMIT_SLICE]),
        "cash_credit_limit": _DecodeMoney(record[CASH_CREDIT_LIMIT_SLICE]),
        "open_date": _OptionalDate(record[OPEN_DATE_SLICE]),
        "expiration_date": _OptionalDate(record[EXPIRATION_DATE_SLICE]),
        "reissue_date": _OptionalDate(record[REISSUE_DATE_SLICE]),
        "curr_cyc_credit": _DecodeMoney(record[CURR_CYC_CREDIT_SLICE]),
        "curr_cyc_debit": _DecodeMoney(record[CURR_CYC_DEBIT_SLICE]),
        # QUIRK (verified in ALL 50 seed rows): per the copybook byte positions,
        # ACCT-ADDR-ZIP holds the literal "A000000000" and ACCT-GROUP-ID is
        # blank/spaces in every row. This is byte-faithfully preserved -- the
        # values are NOT relocated or "corrected": addr_zip is stored as-is
        # ("A000000000"), and the blank group_id becomes None, mirroring the
        # legacy behavior where interest-calc falls back to the DEFAULT
        # disclosure group when the account group-id is empty.
        "addr_zip": record[ADDR_ZIP_SLICE].strip(),
        "group_id": _OptionalText(record[GROUP_ID_SLICE]),
    }


def _ResolveDataDir(dataDir: Optional[Path]) -> Path:
    """Resolve the directory that holds the ASCII seed files.

    Args:
        dataDir: An explicit override for the seed directory. When ``None``, the
            default ``<repo-root>/app/data/ASCII`` directory is used, derived
            relative to this module (``batch/loaders/load_accounts.py`` ->
            two levels up is the repository root).

    Returns:
        The resolved :class:`pathlib.Path` of the seed directory.
    """
    if dataDir is not None:
        return dataDir
    repositoryRoot = Path(__file__).resolve().parents[2]
    return repositoryRoot / "app" / "data" / "ASCII"


def _ResolveInsertBuilder(session: Session):
    """Return the dialect-specific INSERT builder that supports ON CONFLICT.

    The production datastore is PostgreSQL, but the golden-master and unit
    suites may bind an in-memory SQLite engine. Both dialects expose an identical
    ``insert(...).on_conflict_do_update(index_elements=..., set_=...)`` API, so
    this selects the matching builder from the session's bound engine.

    Args:
        session: The active synchronous ORM session (must be engine-bound).

    Returns:
        The dialect ``insert`` construct factory (PostgreSQL by default, SQLite
        when the session is bound to a SQLite engine).
    """
    dialectName = session.get_bind().dialect.name
    if dialectName == "sqlite":
        return SqliteInsert
    return PostgresInsert


def _UpsertRows(session: Session, rows: list) -> int:
    """Idempotently upsert account rows via ``INSERT ... ON CONFLICT``.

    The conflict target is the ``accounts`` primary key, derived generically from
    ``Account.__table__.primary_key.columns`` (``acct_id``). On a key collision,
    every non-primary-key column EXCEPT the ledger-owned running balances
    (:data:`LEDGER_OWNED_COLUMNS`) is refreshed from the incoming seed value.
    The running balances (``curr_bal``, ``curr_cyc_credit``, ``curr_cyc_debit``)
    are deliberately left untouched on a re-load: they are mutated by the posting
    and interest jobs, and resetting them to the seed would erase posted activity
    and break batch-chain idempotency (AAP 0.7.6; QA finding F-6). A first-time
    insert still populates them from the seed, so a fresh load is unchanged.
    The statement is executed within the caller's transaction; this function
    never commits, rolls back, or issues DDL.

    Args:
        session: The active synchronous ORM session (caller owns the transaction).
        rows: The parsed account rows, each a column-name -> value dict.

    Returns:
        The number of rows submitted for upsert.
    """
    if not rows:
        return 0
    primaryKeyNames = [column.name for column in Account.__table__.primary_key.columns]
    insertBuilder = _ResolveInsertBuilder(session)
    insertStatement = insertBuilder(Account).values(rows)
    # Refresh every non-key column on conflict EXCEPT the ledger-owned running
    # balances, which the loader must never reset (see LEDGER_OWNED_COLUMNS).
    assignableColumns = {
        column.name: insertStatement.excluded[column.name]
        for column in Account.__table__.columns
        if column.name not in primaryKeyNames
        and column.name not in LEDGER_OWNED_COLUMNS
    }
    if assignableColumns:
        conflictStatement = insertStatement.on_conflict_do_update(
            index_elements=primaryKeyNames,
            set_=assignableColumns,
        )
    else:
        # No refreshable columns remain (defensive: accounts always has static
        # columns, so this branch is not reached today). Keep the existing row
        # untouched rather than resetting the ledger-owned balances.
        conflictStatement = insertStatement.on_conflict_do_nothing(
            index_elements=primaryKeyNames,
        )
    session.execute(conflictStatement)
    return len(rows)


# ---------------------------------------------------------------------------
# Public API.
# ---------------------------------------------------------------------------


def LoadAccounts(session: Session, dataDir: Optional[Path] = None) -> int:
    """Load the account seed dataset into the ``accounts`` table (idempotent).

    Ports the ``ACCTFILE`` IDCAMS ``REPRO`` step: reads the fixed-width ASCII
    seed file, decodes each 300-byte ``ACCOUNT-RECORD`` per copybook
    ``CVACT01Y``, and upserts the rows into ``accounts`` on the ``acct_id``
    primary key. The caller owns the transaction boundary; this function issues
    no ``commit``/``rollback`` and creates no schema.

    Args:
        session: An open synchronous :class:`sqlalchemy.orm.Session`. The caller
            is responsible for committing or rolling back.
        dataDir: Optional override for the directory containing
            :data:`SEED_FILE_NAME`. Defaults to ``<repo-root>/app/data/ASCII``.

    Returns:
        The number of account rows processed (parsed and submitted for upsert).

    Raises:
        FileNotFoundError: If the seed file cannot be found in the resolved data
            directory.
        ValueError: If any non-blank record does not match the fixed 300-byte
            length, or if a field fails zoned-decimal / date decoding.
    """
    seedPath = _ResolveDataDir(dataDir) / SEED_FILE_NAME
    if not seedPath.is_file():
        raise FileNotFoundError(f"Account seed file not found: {seedPath}")

    parsedRows = []
    with seedPath.open("r", encoding=FILE_ENCODING) as seedFile:
        for lineNumber, rawLine in enumerate(seedFile, start=1):
            # Strip only the line terminator so significant trailing spaces in
            # the fixed-width record are preserved for slicing.
            record = rawLine.rstrip("\r\n")
            if not record.strip():
                # Tolerate stray blank lines (e.g. a trailing newline at EOF).
                continue
            if len(record) != RECORD_LENGTH:
                raise ValueError(
                    f"{SEED_FILE_NAME} line {lineNumber}: expected a "
                    f"{RECORD_LENGTH}-byte ACCOUNT-RECORD, got {len(record)} bytes"
                )
            parsedRows.append(_ParseAccountRecord(record))

    return _UpsertRows(session, parsedRows)
