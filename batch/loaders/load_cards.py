# Ported from IDCAMS load job app/jcl/CARDFILE.jcl (DELETE/DEFINE/REPRO of the
# card VSAM KSDS); record layout app/cpy/CVACT02Y.cpy
# (CARD-RECORD, RECLN 150); seed data app/data/ASCII/carddata.txt.
"""Idempotent seed loader for the CardDemo ``cards`` table.

This module is the Python reimplementation of the legacy IDCAMS load job
``app/jcl/CARDFILE.jcl``, which deleted, redefined and re-populated the
``CARDDATA`` VSAM KSDS (primary key ``CARD-NUM``, alternate index on
``CARD-ACCT-ID``) by ``REPRO``-ing the fixed-width flat file into the cluster.
Here that same seed step is expressed as a single bulk ``INSERT ... ON CONFLICT
DO UPDATE`` against the relational ``cards`` table, reading the display-readable
``app/data/ASCII/carddata.txt`` dataset (AAP 0.5.2, 0.7.7).

Record layout (COBOL ``CVACT02Y`` ``CARD-RECORD``, fixed record length 150)::

    CARD-NUM            PIC X(16)   -> card_num        [0:16]   (primary key)
    CARD-ACCT-ID        PIC 9(11)   -> acct_id         [16:27]  (FK -> accounts)
    CARD-CVV-CD         PIC 9(03)   -> (NOT persisted) [27:30]  (dropped, C-03)
    CARD-EMBOSSED-NAME  PIC X(50)   -> embossed_name   [30:80]
    CARD-EXPIRAION-DATE PIC X(10)   -> expiration_date [80:90]  (DATE, nullable)
    CARD-ACTIVE-STATUS  PIC X(01)   -> active_status   [90:91]
    FILLER              PIC X(59)   -> dropped         [91:150]

Numeric-looking key fields (``card_num``, ``acct_id``) are loaded as
stripped **strings**, never integers, so that the leading zeros carried by the
legacy zoned-decimal display fields survive the migration verbatim (AAP 0.1.2
type-mapping rule; ``acct_id`` in the seed is always zero-padded to 11 digits).

Load ordering: ``cards.acct_id`` is a foreign key to ``accounts.acct_id``, so
this loader MUST run after ``load_accounts`` in the batch chain (AAP 0.7.6).

Transaction ownership: the caller owns the unit of work. This loader operates on
the ``Session`` handed to it and never calls ``commit`` or ``rollback`` itself,
so the orchestrator (``batch.orchestration.batch_chain``) or CLI (``batch.cli``)
can compose several loaders into one atomic, re-runnable transaction. The upsert
is idempotent, so re-running the loader against an already-seeded table leaves
the row set unchanged.

Schema ownership: this module never issues DDL. The ``cards`` table is created
and owned exclusively by the Alembic migrations
(``backend/alembic/versions/*``); the loader only writes rows.

Security (AAP 0.7.8; QA finding C-03): the card verification value
(``CARD-CVV-CD``) is NEVER persisted -- the target ``cards`` table has no CVV
column, so the source slice is read past and dropped. This module also never
logs or prints any field value -- most importantly never the full card number.

Public API:
    * :func:`LoadCards` -- read the seed dataset and upsert every row, returning
      the number of records processed.
"""

from __future__ import annotations

from datetime import date
from pathlib import Path
from typing import Optional

from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.orm import Session

from app.models.card import Card
from app.utils.date_utils import ParseLegacyDate


# --- Fixed-width record geometry (COBOL CVACT02Y CARD-RECORD, RECLN 150) -----
# The seed dataset stores one 150-byte record per line. Field boundaries are
# expressed as ``slice`` constants so the parse helper reads exactly like the
# COBOL 05-level field list and any future correction is a one-line change.
SEED_FILE_NAME = "carddata.txt"
RECORD_LENGTH = 150
FILE_ENCODING = "latin-1"

CARD_NUM_SLICE = slice(0, 16)
ACCT_ID_SLICE = slice(16, 27)
# record[27:30] is the source CARD-CVV-CD slice. It is documented here for
# fixed-width layout parity ONLY and is intentionally never sliced/persisted
# (QA finding C-03, AAP 0.7.8): no CVV is retained anywhere in the target.
EMBOSSED_NAME_SLICE = slice(30, 80)
EXPIRATION_DATE_SLICE = slice(80, 90)
ACTIVE_STATUS_SLICE = slice(90, 91)

# Repo-root-relative location of the legacy ASCII seed datasets. This module is
# ``<repo-root>/batch/loaders/load_cards.py``, so ``parents[2]`` is the repo
# root (parents[0]=loaders, parents[1]=batch, parents[2]=root).
_DEFAULT_DATA_DIR = Path(__file__).resolve().parents[2] / "app" / "data" / "ASCII"


def _OptionalDate(dateText: str) -> Optional[date]:
    """Parse an optional legacy date field, treating blanks as ``None``.

    The COBOL ``CARD-EXPIRAION-DATE PIC X(10)`` field is space-filled when no
    date is present. A space-filled slice must therefore map to SQL ``NULL``
    rather than raise, while a populated ``YYYY-MM-DD`` value is parsed strictly.

    Args:
        dateText: The raw fixed-width date slice (may be blank/space-filled).

    Returns:
        The parsed :class:`datetime.date`, or ``None`` when the field is blank.

    Raises:
        ValueError: If a non-blank value is not a valid ``YYYY-MM-DD`` date
            (propagated unchanged from :func:`app.utils.date_utils.ParseLegacyDate`).
    """
    strippedText = dateText.strip()
    if not strippedText:
        return None
    return ParseLegacyDate(strippedText)


def _ParseCardRecord(record: str) -> dict[str, object]:
    """Map one fixed-width CARD-RECORD to ``cards`` column values.

    Field boundaries follow COBOL copybook ``CVACT02Y`` exactly. The numeric-key
    fields (``card_num``, ``acct_id``) are kept as stripped strings so leading
    zeros are preserved; they are deliberately NOT converted to integers. The
    source CVV slice and the trailing ``FILLER`` are ignored.

    The card verification value (``CARD-CVV-CD``) is DELIBERATELY NOT LOADED
    (QA finding C-03, AAP 0.7.8): the target ``cards`` table has no CVV column,
    so the 3-byte source slice is read past and never persisted.

    Args:
        record: A single 150-character fixed-width card record.

    Returns:
        A dict keyed by the real ``cards`` column names, ready to be bulk-upserted.
    """
    return {
        "card_num": record[CARD_NUM_SLICE].strip(),
        "acct_id": record[ACCT_ID_SLICE].strip(),
        # CVV_CD_SLICE (record[27:30]) is intentionally NOT read: no CVV is
        # persisted to the cards table (QA finding C-03, AAP 0.7.8).
        "embossed_name": record[EMBOSSED_NAME_SLICE].strip(),
        "expiration_date": _OptionalDate(record[EXPIRATION_DATE_SLICE]),
        "active_status": record[ACTIVE_STATUS_SLICE].strip(),
    }


def _ResolveDataDir(dataDir: Optional[Path]) -> Path:
    """Resolve the directory that holds the ASCII seed datasets.

    Args:
        dataDir: An explicit data directory (used by tests and alternate
            deployments), or ``None`` to fall back to the in-repository
            ``app/data/ASCII`` directory.

    Returns:
        The resolved seed-data directory as a :class:`~pathlib.Path`.
    """
    if dataDir is not None:
        return Path(dataDir)
    return _DEFAULT_DATA_DIR


def _UpsertRows(session: Session, rows: list[dict[str, object]]) -> int:
    """Bulk-upsert parsed card rows, keyed on the ``cards`` primary key.

    Emits a single PostgreSQL ``INSERT ... ON CONFLICT DO UPDATE`` so the load is
    idempotent: existing rows (same ``card_num``) are refreshed and new rows are
    inserted. The conflict target and the updated columns are derived from the
    :class:`~app.models.card.Card` table metadata rather than hardcoded, so the
    primary key definition lives in exactly one place (the model).

    The caller owns the transaction; this function neither commits nor rolls back.

    Args:
        session: An open synchronous SQLAlchemy session.
        rows: Parsed row dicts keyed by real ``cards`` column names.

    Returns:
        The number of rows written (equal to ``len(rows)``); ``0`` when there is
        nothing to load.
    """
    if not rows:
        return 0
    cardTable = Card.__table__
    primaryKeyNames = [column.name for column in cardTable.primary_key.columns]
    insertStatement = insert(cardTable).values(rows)
    updateColumns = {
        column.name: insertStatement.excluded[column.name]
        for column in cardTable.columns
        if column.name not in primaryKeyNames
    }
    upsertStatement = insertStatement.on_conflict_do_update(
        index_elements=primaryKeyNames,
        set_=updateColumns,
    )
    session.execute(upsertStatement)
    return len(rows)


def LoadCards(session: Session, dataDir: Optional[Path] = None) -> int:
    """Load the ``cards`` seed dataset into PostgreSQL, idempotently.

    Reimplements the ``CARDFILE`` IDCAMS ``REPRO`` step: read every fixed-width
    record from ``carddata.txt``, map it to ``cards`` column values, and bulk
    upsert. Short lines are right-padded to the fixed record length before
    slicing (defensive), and fully blank lines are skipped. The file is read with
    the ``latin-1`` codec so every byte round-trips without decode errors.

    This loader must run after ``load_accounts`` because ``cards.acct_id`` is a
    foreign key to ``accounts.acct_id``. It owns no transaction: the caller (the
    orchestration chain or CLI) commits or rolls back.

    Args:
        session: An open synchronous SQLAlchemy session supplied by the caller.
        dataDir: Optional override for the seed-data directory; defaults to the
            in-repository ``app/data/ASCII`` directory.

    Returns:
        The number of card records processed.

    Raises:
        FileNotFoundError: If the ``carddata.txt`` seed file is absent from the
            resolved data directory. The error message names the resolved path.
    """
    resolvedDir = _ResolveDataDir(dataDir)
    seedPath = resolvedDir / SEED_FILE_NAME
    parsedRows: list[dict[str, object]] = []
    try:
        with open(seedPath, "r", encoding=FILE_ENCODING) as seedFile:
            for rawLine in seedFile:
                normalizedLine = rawLine.rstrip("\r\n").ljust(RECORD_LENGTH)
                if not normalizedLine.strip():
                    continue
                parsedRows.append(_ParseCardRecord(normalizedLine))
    except FileNotFoundError as missingFileError:
        raise FileNotFoundError(
            f"Card seed file not found at '{seedPath}'. Expected the legacy "
            f"ASCII dataset '{SEED_FILE_NAME}' under data directory "
            f"'{resolvedDir}'."
        ) from missingFileError
    return _UpsertRows(session, parsedRows)


__all__ = ["LoadCards"]
