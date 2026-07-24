# Ported from legacy JCL app/jcl/TRANBKP.jcl (CardDemo). Function: snapshot the
# POSTED transactions to a generation-suffixed, restore-capable backup file (GDG
# semantics), and restore that file back into the table on demand. Does NOT
# delete/redefine the table.
#
# Legacy job structure (app/jcl/TRANBKP.jcl):
#   * STEP05R -- REPRO the master TRANSACT VSAM KSDS into a NEW GDG generation
#     TRANSACT.BKUP(+1) (LRECL 350). This snapshot is PRESERVED here.
#   * STEP05  -- IDCAMS DELETE of the master cluster + alternate index.
#   * STEP10  -- IDCAMS DEFINE that recreates an empty master cluster.
# STEP05 (delete) and STEP10 (define/recreate) are INTENTIONALLY OMITTED in this
# port: emptying and redefining the store is a non-portable VSAM housekeeping
# artifact. The relational schema is owned exclusively by Alembic
# (backend/alembic/versions/*), so this job never deletes/empties the
# ``transactions`` table and never issues DDL -- exactly as the CLOSEFIL/OPENFIL
# IEFBR14 no-ops become no-ops in the Python batch chain (AAP 0.7.5, 0.7.6). The
# legacy job runs twice in the chain (once before posting, once after interest);
# each invocation produces a fresh, distinct backup generation.
"""Transaction backup + restore job -- the Python port of ``app/jcl/TRANBKP.jcl``.

This module reproduces the *snapshot* half of the legacy TRANBKP job: it writes
every POSTED row currently in the ``transactions`` table to a new,
generation-suffixed CSV file, mirroring the mainframe GDG semantics where each
REPRO created the next relative generation ``TRANSACT.BKUP(+1)``. The destructive
half of the legacy job (DELETE the VSAM cluster, then DEFINE an empty
replacement) is deliberately not reproduced -- see the module header and AAP
0.7.5 / 0.7.6.

Restore-capable, full-fidelity backup (QA finding M18)
    The legacy REPRO produced a byte-for-byte copy of the master that could be
    REPRO'd back to reconstruct the cluster. This port preserves that restore
    capability rather than degrading the backup to a masked, non-restorable
    snapshot:

    * The full transaction record is written verbatim, INCLUDING the full
      ``card_num`` (PAN). This is what makes the file restore-capable: masking
      the PAN would make it impossible to recreate the source rows. The AAP
      masks the PAN only "in the UI and responses" (AAP 0.7.8); an operator-only
      backup dataset is neither, and the legacy dataset carried the full PAN.
    * Sensitive exposure is prevented by FILE-SYSTEM PERMISSIONS rather than by
      masking (QA finding M13): the backup directory is created owner-only
      (``0700``) and every backup file is published owner-only (``0600``) via
      :func:`~batch.jobs.output_safety.AtomicWritePath`, so the full-PAN data is
      never group- or world-readable.
    * :func:`RestoreTransactions` reads a backup file and idempotently upserts
      its rows back into the ``transactions`` table (``INSERT ... ON CONFLICT DO
      UPDATE`` on the ``tran_id`` primary key), so a backup can be replayed to
      reconstruct the ledger and re-running the restore converges to the same
      state. The integration tests reconcile a backup->delete->restore round trip
      field-for-field.

    The ``cvv`` is not part of the transaction record and is never written; the
    customer ``ssn`` lives on a different table and never appears here.

POSTED-only master (QA finding M16)
    Only POSTED rows are backed up. The legacy TRANBKP snapshotted the posted
    ``TRANSACT`` master; PENDING daily-staging rows and validation-REJECTED rows
    live outside that master and must never enter the backup (nor be recreated by
    a restore).

Generation semantics
    Each backup run allocates the next monotonically increasing generation number
    by scanning the output directory for existing ``transact_bkup_*.csv`` files
    and adding one (mirroring the ``(+1)`` relative-generation reference). A run
    therefore always produces a brand-new file and never overwrites or corrupts a
    prior generation, which keeps the job safely re-runnable across the two points
    it is invoked in the batch chain and preserves prior generations for
    point-in-time restore.

Transaction ownership
    The caller owns the unit of work for BOTH operations. :func:`BackupTransactions`
    receives an already-open :class:`~sqlalchemy.orm.Session` (typically from
    ``batch.db.GetSyncSession``) and is strictly READ-ONLY with respect to the
    database: it never calls ``commit``, ``rollback`` or ``close``, never mutates
    rows, and never issues DDL; its only side effect is writing the backup file.
    :func:`RestoreTransactions` DOES mutate the database (it upserts rows) but
    still never commits, rolls back, closes the session, or issues DDL -- the
    caller owns the transaction boundary.

Numeric fidelity
    ``tran_amt`` originates from ``PIC S9(09)V99`` and is carried end to end as a
    :class:`decimal.Decimal` (``NUMERIC(11, 2)``); it is serialized with ``str``
    on backup and parsed back with :class:`~decimal.Decimal` on restore so its
    exact scale is preserved and floating point is never introduced (AAP 0.7.1).
"""

from __future__ import annotations

import csv
import logging
from datetime import datetime
from decimal import Decimal
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as PgInsert
from sqlalchemy.orm import Session

from app.models.transaction import STATUS_POSTED, Transaction

from batch.jobs.output_safety import AtomicWritePath, SafeCsvWriter, SecureDirectory

# Module logger. Configuration (handlers, level, formatting) is owned by the CLI
# / orchestration entrypoint, not hardcoded here (Ochs Rule #3).
LOGGER = logging.getLogger(__name__)

# --- Backup-file naming (GDG generation) ------------------------------------
# The generation-suffixed backup files are named ``transact_bkup_<NNNN>.csv``.
# The prefix/suffix and glob are defined once so the writer and the generation
# scanner agree on a single authoritative pattern.
BACKUP_FILE_PREFIX = "transact_bkup_"
BACKUP_FILE_SUFFIX = ".csv"
BACKUP_GLOB = f"{BACKUP_FILE_PREFIX}*{BACKUP_FILE_SUFFIX}"

# Zero-padding width for the generation number (e.g. 1 -> "0001"). Numbers that
# exceed the width are rendered in full, so the sequence never truncates.
GENERATION_PADDING = 4

# Default backup location, relative to the repository root, used when the caller
# does not pass an explicit ``outputDir``. Kept as path segments so the platform
# separator is applied by ``Path.joinpath``. The leading ``out`` segment places
# the default under the gitignored ``out/`` tree so an unqualified backup (for
# example from ``run-all``) never spills untracked files into the working tree
# (QA Finding E).
DEFAULT_BACKUP_SUBDIR = ("out", "backups", "transactions")

# Text encoding for the CSV backup. UTF-8 is the modern default; the stored
# values are ordinary text decoded at load time from the ASCII/EBCDIC seeds.
FILE_ENCODING = "utf-8"

# CSV column order. Mirrors the ``transactions`` record layout (CVTRA05Y) and
# must stay in lockstep with the row builder in :func:`_BuildBackupRow` and the
# restore parser in :func:`_BuildRestoreValues`.
CSV_HEADER = (
    "tran_id",
    "tran_type_cd",
    "tran_cat_cd",
    "tran_source",
    "tran_desc",
    "tran_amt",
    "merchant_id",
    "merchant_name",
    "merchant_city",
    "merchant_zip",
    "card_num",
    "orig_ts",
    "proc_ts",
    "status",
)

# Non-key columns updated by the restore upsert on a primary-key conflict (every
# CSV column except the ``tran_id`` primary key). Defined once so the upsert and
# the CSV header can never drift apart.
_RESTORE_UPDATE_COLUMNS = tuple(column for column in CSV_HEADER if column != "tran_id")

# CSV columns that carry a nullable TIMESTAMPTZ value; an empty cell restores to
# SQL NULL rather than an empty string.
_TIMESTAMP_COLUMNS = ("orig_ts", "proc_ts")

__all__ = ["BackupTransactions", "RestoreTransactions"]


def _ResolveOutputDir(outputDir: str | Path | None) -> Path:
    """Resolve the directory that will hold the backup generations.

    When the caller passes an explicit location it is honored verbatim (after
    normalization to :class:`~pathlib.Path`). Otherwise the job defaults to the
    repository's gitignored ``out/backups/transactions`` tree, located relative
    to this module so the default is stable regardless of the process working
    directory and never leaves untracked files in the working tree.

    Args:
        outputDir: An explicit output directory (``str`` or ``Path``), or
            ``None`` to use the repository-relative default.

    Returns:
        The resolved backup :class:`~pathlib.Path` (not yet created on disk).
    """
    if outputDir is not None:
        return Path(outputDir)
    repositoryRoot = Path(__file__).resolve().parents[2]
    return repositoryRoot.joinpath(*DEFAULT_BACKUP_SUBDIR)


def _ResolveGeneration(outputDir: Path) -> str:
    """Compute the next backup generation identifier (GDG ``(+1)`` semantics).

    Scans ``outputDir`` for existing ``transact_bkup_<NNNN>.csv`` files, parses
    their numeric generation suffix, and returns the next value (highest found
    plus one) as a zero-padded string. Non-numeric or malformed suffixes are
    ignored so a stray file never derails the sequence. When no prior generation
    exists the first identifier is ``"0001"``.

    Args:
        outputDir: The (already-created) directory holding prior generations.

    Returns:
        The next generation identifier, zero-padded to
        :data:`GENERATION_PADDING` digits.
    """
    highestGeneration = 0
    for existingFile in outputDir.glob(BACKUP_GLOB):
        generationText = existingFile.stem[len(BACKUP_FILE_PREFIX):]
        if generationText.isdigit():
            highestGeneration = max(highestGeneration, int(generationText))
    nextGeneration = highestGeneration + 1
    return f"{nextGeneration:0{GENERATION_PADDING}d}"


def _FormatTimestamp(value: datetime | None) -> str:
    """Serialize a timezone-aware timestamp to ISO-8601 text.

    The ``orig_ts`` and ``proc_ts`` columns are optional, so a ``None`` value
    serializes to an empty string rather than the literal ``"None"``. The
    ISO-8601 text round-trips exactly through :func:`_ParseTimestamp` on restore.

    Args:
        value: The ``orig_ts`` / ``proc_ts`` value, or ``None``.

    Returns:
        The ISO-8601 string for a real timestamp, or ``""`` when absent.
    """
    if isinstance(value, datetime):
        return value.isoformat()
    return ""


def _BuildBackupRow(transaction: Transaction) -> list[str]:
    """Render one transaction as a list of CSV field values (full fidelity).

    Field order matches :data:`CSV_HEADER` exactly. ``tran_amt`` is serialized
    from its :class:`~decimal.Decimal` value via ``str`` so the exact scale is
    preserved and floating point is never introduced (AAP 0.7.1). The FULL
    ``card_num`` is written so the backup is restore-capable (QA finding M18);
    the file is protected by owner-only permissions rather than by masking (QA
    finding M13). The nullable text/timestamp fields collapse to an empty string
    when absent.

    Args:
        transaction: A mapped :class:`~app.models.transaction.Transaction` row.

    Returns:
        The ordered CSV field values for a single backup line.
    """
    return [
        transaction.tran_id,
        transaction.tran_type_cd,
        transaction.tran_cat_cd,
        transaction.tran_source or "",
        transaction.tran_desc or "",
        str(transaction.tran_amt),
        transaction.merchant_id or "",
        transaction.merchant_name or "",
        transaction.merchant_city or "",
        transaction.merchant_zip or "",
        transaction.card_num,
        _FormatTimestamp(transaction.orig_ts),
        _FormatTimestamp(transaction.proc_ts),
        transaction.status,
    ]


def _WriteBackupCsv(backupPath: Path, transactions: list[Transaction]) -> int:
    """Write the POSTED transaction snapshot to ``backupPath`` as CSV.

    Emits the :data:`CSV_HEADER` row followed by one full-fidelity,
    Decimal-faithful row per transaction. The file is published atomically and
    restricted to owner-only ``0600`` (QA finding M13); a mid-write failure
    leaves no partial file at ``backupPath``. File-I/O failures surface as
    :class:`OSError`, which is logged with context and re-raised (never
    swallowed) so the orchestrator can react.

    Args:
        backupPath: The full path of the generation file to create.
        transactions: The transactions to serialize, already ordered by the
            caller.

    Returns:
        The number of transaction rows written (excluding the header).

    Raises:
        OSError: If the backup file cannot be opened or written.
    """
    try:
        # Publish atomically (F-4): a mid-write failure leaves no partial file at
        # backupPath, and the finished file is owner-only 0600 (M13). Every cell
        # is neutralized against CSV formula injection (F-3, CWE-1236) while exact
        # Decimal amounts pass through unchanged.
        with AtomicWritePath(backupPath) as stagingPath:
            with stagingPath.open("w", encoding=FILE_ENCODING, newline="") as backupFile:
                csvWriter = SafeCsvWriter(csv.writer(backupFile))
                csvWriter.writerow(CSV_HEADER)
                for transaction in transactions:
                    csvWriter.writerow(_BuildBackupRow(transaction))
    except OSError:
        LOGGER.exception("Failed writing transaction backup to %s", backupPath)
        raise
    return len(transactions)


def BackupTransactions(session: Session, outputDir: str | Path | None = None) -> int:
    """Snapshot the POSTED ``transactions`` to a new generation backup file.

    Ports ``app/jcl/TRANBKP.jcl``: reads every POSTED transaction (QA finding
    M16) and writes it to a fresh generation-suffixed CSV (GDG ``(+1)``
    semantics). The legacy delete-and-redefine steps are intentionally omitted --
    this job never mutates the database (no delete, no schema change, no
    ``commit``/``rollback``/``close``); the caller owns the transaction and the
    schema is owned by Alembic. The snapshot is ordered deterministically by
    ``tran_id`` so backups are reproducible and comparable across runs.

    This produces a FULL-FIDELITY, RESTORE-CAPABLE backup (QA finding M18): the
    full ``card_num`` is written so :func:`RestoreTransactions` can reconstruct
    the source rows verbatim. The file is protected by owner-only ``0600``
    permissions in an owner-only ``0700`` directory (QA finding M13) rather than
    by masking; the ``cvv`` is never present (not part of the record) and the
    customer ``ssn`` lives on another table and never appears here.

    Args:
        session: An open, caller-owned SQLAlchemy
            :class:`~sqlalchemy.orm.Session`. Used read-only; the caller retains
            responsibility for committing/closing.
        outputDir: Optional destination directory for the backup file. When
            ``None`` (the default) the repository's ``backups/transactions`` tree
            is used. The directory is created owner-only (``0700``) if it does not
            already exist.

    Returns:
        The number of POSTED transactions written to the backup file.

    Raises:
        OSError: If the output directory cannot be created or the backup file
            cannot be written.
    """
    LOGGER.info("START OF EXECUTION OF TRANBKP")

    backupDirectory = SecureDirectory(_ResolveOutputDir(outputDir))

    generation = _ResolveGeneration(backupDirectory)
    backupPath = backupDirectory / f"{BACKUP_FILE_PREFIX}{generation}{BACKUP_FILE_SUFFIX}"

    statement = (
        select(Transaction)
        .where(Transaction.status == STATUS_POSTED)
        .order_by(Transaction.tran_id)
    )
    transactions = session.execute(statement).scalars().all()

    backupCount = _WriteBackupCsv(backupPath, transactions)
    LOGGER.info(
        "Backed up %d POSTED transaction(s) to generation %s (%s)",
        backupCount,
        generation,
        backupPath,
    )

    LOGGER.info("END OF EXECUTION OF TRANBKP")
    return backupCount


# --------------------------------------------------------------------------- #
# Restore (QA finding M18) -- replay a backup file back into the table.
# --------------------------------------------------------------------------- #
def _ParseTimestamp(text: str) -> datetime | None:
    """Parse an ISO-8601 backup cell back into a timezone-aware datetime.

    The inverse of :func:`_FormatTimestamp`: an empty cell restores to ``None``
    (SQL NULL), and any other value is parsed with
    :meth:`datetime.datetime.fromisoformat`, which round-trips the exact
    timezone-aware instant that :meth:`~datetime.datetime.isoformat` emitted.

    Args:
        text: The raw CSV cell value for a timestamp column.

    Returns:
        The parsed :class:`~datetime.datetime`, or ``None`` when the cell is
        empty.
    """
    if not text:
        return None
    return datetime.fromisoformat(text)


def _BuildRestoreValues(row: dict[str, str]) -> dict[str, object]:
    """Convert one parsed backup CSV row into ORM-column values for the upsert.

    Reverses :func:`_BuildBackupRow`: ``tran_amt`` is parsed as an exact
    :class:`~decimal.Decimal` (never float, AAP 0.7.1), the timestamp columns are
    parsed via :func:`_ParseTimestamp` (empty -> ``None``), and every other
    column is carried across as its stored text. The full ``card_num`` is
    restored verbatim so the reconstructed row is byte-identical to the source.

    Args:
        row: One backup line as a ``{column: value}`` mapping (from
            :class:`csv.DictReader`), keyed by :data:`CSV_HEADER`.

    Returns:
        A mapping of ``transactions`` column name to its typed value, suitable
        for an ``INSERT`` / upsert.
    """
    values: dict[str, object] = {}
    for column in CSV_HEADER:
        rawValue = row[column]
        if column == "tran_amt":
            values[column] = Decimal(rawValue)
        elif column in _TIMESTAMP_COLUMNS:
            values[column] = _ParseTimestamp(rawValue)
        else:
            values[column] = rawValue
    return values


def RestoreTransactions(session: Session, backupPath: str | Path) -> int:
    """Replay a backup file back into the ``transactions`` table (idempotent).

    Reads a backup produced by :func:`BackupTransactions` and upserts each row
    into ``transactions`` with ``INSERT ... ON CONFLICT (tran_id) DO UPDATE`` (QA
    finding M18), so a lost or partially edited ledger can be reconstructed from
    the backup and re-running the restore converges to the same state (the
    operation is idempotent). Because the full ``card_num`` and exact
    :class:`~decimal.Decimal` amounts are preserved in the backup, the restored
    rows are byte-identical to the rows that were backed up.

    The caller owns the transaction boundary: this function mutates rows via the
    upsert but never ``commit``s, ``rollback``s, ``close``s the session, or
    issues DDL. The backed-up rows carry the FK ``card_num``, so the referenced
    cards must exist (they do in a real restore, where only the transaction
    ledger was lost).

    Args:
        session: An open, caller-owned SQLAlchemy
            :class:`~sqlalchemy.orm.Session`.
        backupPath: Path to a backup CSV previously written by
            :func:`BackupTransactions`.

    Returns:
        The number of transaction rows restored (upserted).

    Raises:
        OSError: If the backup file cannot be opened or read.
        FileNotFoundError: If ``backupPath`` does not exist (a specific subclass
            of :class:`OSError`).
    """
    LOGGER.info("START OF RESTORE FROM %s", backupPath)
    resolvedPath = Path(backupPath)
    restoredCount = 0
    with resolvedPath.open("r", encoding=FILE_ENCODING, newline="") as backupFile:
        reader = csv.DictReader(backupFile)
        for row in reader:
            values = _BuildRestoreValues(row)
            insertStatement = PgInsert(Transaction).values(**values)
            upsertStatement = insertStatement.on_conflict_do_update(
                index_elements=[Transaction.tran_id],
                set_={
                    column: insertStatement.excluded[column]
                    for column in _RESTORE_UPDATE_COLUMNS
                },
            )
            session.execute(upsertStatement)
            restoredCount += 1
    LOGGER.info("Restored %d transaction(s) from %s", restoredCount, resolvedPath)
    return restoredCount
