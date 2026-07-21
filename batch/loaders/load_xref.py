# batch/loaders/load_xref.py
# Ported from IDCAMS load job app/jcl/XREFFILE.jcl (DELETE/DEFINE/REPRO of the
# card cross-reference VSAM KSDS + alternate indexes on acct_id/cust_id);
# record layout app/cpy/CVACT03Y.cpy (CARD-XREF-RECORD); seed data
# app/data/ASCII/cardxref.txt.
"""Idempotent seed loader for the ``card_xref`` cross-reference table.

This module is the modern port of the mainframe IDCAMS job ``XREFFILE.jcl``,
which deleted, redefined and REPRO-loaded the ``CARDXREF`` VSAM KSDS (plus a
non-unique alternate index on the account id) from a flat file. In the modern
three-tier stack that VSAM cluster becomes the PostgreSQL ``card_xref`` table
(:class:`app.models.card_xref.CardXref`), and the flat-file REPRO becomes this
bulk, restartable upsert of the display-readable ASCII seed
``app/data/ASCII/cardxref.txt``.

``card_xref`` is the card-to-customer-and-account cross reference. Every row's
three columns are foreign keys, so this loader MUST run only after the parent
seeds have been loaded -- ``load_cards`` (``card_num``), ``load_customers``
(``cust_id``) and ``load_accounts`` (``acct_id``) -- otherwise the foreign-key
constraints reject the rows. The legacy batch chain enforces the same ordering
by refreshing ``CARDFILE``/``CUSTFILE``/``ACCTFILE`` before ``XREFFILE``.

Record layout (COBOL copybook ``CVACT03Y``, ``01 CARD-XREF-RECORD``)::

    05  XREF-CARD-NUM  PIC X(16).  -> xref_card_num  columns [0:16]  (PK)
    05  XREF-CUST-ID   PIC 9(09).  -> cust_id        columns [16:25] (FK)
    05  XREF-ACCT-ID   PIC 9(11).  -> acct_id        columns [25:36] (FK)
    05  FILLER         PIC X(14).  -> dropped (record padding only)

Although the copybook declares ``RECLN 50`` with a trailing 14-byte ``FILLER``,
the ASCII seed file carries **exactly 36 bytes per record with no trailing
filler** (file size 1850 = 50 rows x 36 bytes + 50 newlines). :data:`RECORD_LENGTH`
is therefore ``36``; the absent filler is never read.

Type fidelity (AAP 0.7.1): the two identifier fields are numeric ``PIC 9(n)`` on
the mainframe but are loaded as **stripped strings**, never integers, so their
leading zeros are preserved to match the ``VARCHAR`` columns of ``card_xref``
and the foreign-key values in ``cards``/``customers``/``accounts``. There are no
money or date fields in this record, so no zoned-decimal or date decoding is
required here.

Transaction ownership:
    The caller owns the transaction boundary. :func:`LoadCardXref` operates on
    the ``session`` it is handed and never calls ``commit`` or ``rollback``, so
    the orchestrator (``batch.orchestration.batch_chain``) or the CLI
    (``batch.cli``) can compose several loaders into one atomic unit of work via
    ``batch.db.GetSyncSession``.

Idempotency:
    Rows are written with a PostgreSQL ``INSERT ... ON CONFLICT (xref_card_num)
    DO UPDATE`` upsert, so re-running the loader converges to the same state
    without raising duplicate-key errors. The loader never issues DDL; the
    schema is owned exclusively by Alembic.

Public API:
    * :func:`LoadCardXref` -- load the seed and return the number of rows
      processed.

Example:
    Load the cross reference inside a caller-owned unit of work::

        from batch.db import GetSyncSession
        from batch.loaders.load_xref import LoadCardXref

        with GetSyncSession() as session:
            rowCount = LoadCardXref(session)   # loader does NOT commit
        # the transaction is committed here, exactly once, on clean exit
"""

from __future__ import annotations

from pathlib import Path
from typing import Optional

from sqlalchemy.dialects.postgresql import insert as PgInsert
from sqlalchemy.orm import Session

from app.models.card_xref import CardXref

__all__ = ["LoadCardXref"]


# Name of the display-readable ASCII seed file this loader consumes. It is the
# ASCII equivalent of the legacy ``AWS.M2.CARDDEMO.CARDXREF.PS`` dataset that
# ``XREFFILE.jcl`` REPRO-copied into the VSAM cluster.
SEED_FILE_NAME = "cardxref.txt"

# Fixed record width, in characters, of a single CARD-XREF-RECORD in the ASCII
# seed. This is 36 (XREF-CARD-NUM 16 + XREF-CUST-ID 9 + XREF-ACCT-ID 11) and
# intentionally NOT the copybook's 50-byte RECLN: the trailing 14-byte FILLER is
# absent from the ASCII seed and must not be read.
RECORD_LENGTH = 36

# Byte-preserving text encoding used to read the seed. ``latin-1`` maps every
# single byte 0x00-0xFF to exactly one code point, so fixed-width column offsets
# stay byte-accurate even if a stray non-ASCII byte appears; no bytes are lost or
# combined the way a multibyte codec such as UTF-8 could.
FILE_ENCODING = "latin-1"

# Column offsets of the three CARD-XREF-RECORD fields, expressed as slice objects
# so the parser reads them declaratively. Derived directly from CVACT03Y and
# verified against the seed file.
XREF_CARD_NUM_SLICE = slice(0, 16)   # XREF-CARD-NUM PIC X(16)  -> xref_card_num
CUST_ID_SLICE = slice(16, 25)        # XREF-CUST-ID  PIC 9(09)  -> cust_id
ACCT_ID_SLICE = slice(25, 36)        # XREF-ACCT-ID  PIC 9(11)  -> acct_id

# Path components, relative to the repository root, of the directory that holds
# the ASCII seed files. Kept as a tuple so :func:`_ResolveDataDir` can join them
# onto the discovered repository root in an OS-independent way.
DATA_DIR_PARTS = ("app", "data", "ASCII")


def _ParseCardXrefRecord(record: str) -> dict:
    """Map one fixed-width CARD-XREF-RECORD to card_xref column values."""
    return {
        "xref_card_num": record[XREF_CARD_NUM_SLICE].strip(),
        "cust_id": record[CUST_ID_SLICE].strip(),
        "acct_id": record[ACCT_ID_SLICE].strip(),
    }


def _ResolveDataDir(dataDir: Optional[Path] = None) -> Path:
    """Resolve the directory that contains the ASCII seed files.

    Args:
        dataDir: An explicit seed-data directory. When provided it is used
            verbatim (coerced to :class:`~pathlib.Path`), which lets tests and
            the CLI point the loader at a fixture directory. When ``None`` the
            directory is derived from this module's location so the loader works
            regardless of the process's current working directory.

    Returns:
        The resolved ``app/data/ASCII`` directory. The path is returned even if
        it does not exist on disk; existence of the seed *file* is validated by
        :func:`LoadCardXref`.
    """
    if dataDir is not None:
        return Path(dataDir)
    # This module lives at ``<repo_root>/batch/loaders/load_xref.py``; two parent
    # hops from the ``loaders`` directory reach the repository root.
    repoRoot = Path(__file__).resolve().parents[2]
    return repoRoot.joinpath(*DATA_DIR_PARTS)


def _UpsertRows(session: Session, rows: list[dict]) -> int:
    """Bulk-upsert parsed ``card_xref`` rows idempotently.

    Builds a single PostgreSQL ``INSERT ... ON CONFLICT ... DO UPDATE`` statement
    keyed on the table's real primary key and executes it on the caller's
    session. The primary-key and updatable-column names are derived from
    :class:`~app.models.card_xref.CardXref`'s table metadata, so the real DDL
    column name (``xref_card_num``) is used -- never the ORM-only ``card_num``
    synonym -- and the function stays correct if the table definition changes.

    Args:
        session: An open, caller-owned SQLAlchemy :class:`~sqlalchemy.orm.Session`.
            This function issues the statement but does not commit or roll back.
        rows: Parsed rows, each a mapping of real column name to value as
            produced by :func:`_ParseCardXrefRecord`.

    Returns:
        The number of rows submitted for upsert.
    """
    if not rows:
        return 0
    tableObj = CardXref.__table__
    pkColumnNames = [column.name for column in tableObj.primary_key.columns]
    updateColumnNames = [
        column.name
        for column in tableObj.columns
        if column.name not in pkColumnNames
    ]
    insertStmt = PgInsert(tableObj).values(rows)
    if updateColumnNames:
        # On a primary-key collision, refresh the non-key columns from the row
        # that would have been inserted (``excluded``), making the load
        # restartable without duplicate-key failures.
        insertStmt = insertStmt.on_conflict_do_update(
            index_elements=pkColumnNames,
            set_={name: insertStmt.excluded[name] for name in updateColumnNames},
        )
    else:
        # Degenerate case (a key-only table): nothing to update, so a colliding
        # key is simply left untouched.
        insertStmt = insertStmt.on_conflict_do_nothing(
            index_elements=pkColumnNames,
        )
    session.execute(insertStmt)
    return len(rows)


def LoadCardXref(session: Session, dataDir: Optional[Path] = None) -> int:
    """Load the card cross-reference seed into the ``card_xref`` table.

    Reads the fixed-width ASCII seed ``cardxref.txt``, parses each
    ``CARD-XREF-RECORD`` into its three ``card_xref`` columns, and idempotently
    upserts them. This is the modern equivalent of the ``XREFFILE.jcl`` REPRO
    step. The caller owns the transaction: this function never commits or rolls
    back, and it never issues DDL.

    Args:
        session: An open, caller-owned SQLAlchemy :class:`~sqlalchemy.orm.Session`.
        dataDir: Optional override for the directory containing ``cardxref.txt``.
            Defaults to the repository's ``app/data/ASCII`` directory.

    Returns:
        The number of cross-reference rows processed (parsed and upserted).

    Raises:
        FileNotFoundError: If ``cardxref.txt`` is not present in the resolved
            data directory.
    """
    seedDir = _ResolveDataDir(dataDir)
    seedPath = seedDir / SEED_FILE_NAME
    if not seedPath.is_file():
        raise FileNotFoundError(
            f"CardXref seed file not found: {seedPath}. Expected the ASCII seed "
            f"'{SEED_FILE_NAME}' ported from app/data/ASCII (see XREFFILE.jcl)."
        )
    parsedRows: list[dict] = []
    with seedPath.open("r", encoding=FILE_ENCODING) as seedFile:
        for rawLine in seedFile:
            # Drop only the line terminator, then pad short lines to the full
            # record width so the fixed-width column slices are always safe.
            strippedLine = rawLine.rstrip("\r\n")
            if not strippedLine.strip():
                # Skip wholly blank lines (e.g. a trailing newline at EOF).
                continue
            record = strippedLine.ljust(RECORD_LENGTH)
            parsedRows.append(_ParseCardXrefRecord(record))
    return _UpsertRows(session, parsedRows)
