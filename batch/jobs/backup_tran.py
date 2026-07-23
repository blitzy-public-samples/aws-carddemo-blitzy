# Ported from legacy JCL app/jcl/TRANBKP.jcl (CardDemo). Function: snapshot the
# transactions table to a generation-suffixed backup file (GDG semantics). Does
# NOT delete/redefine the table.
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
"""Transaction backup job -- the Python port of ``app/jcl/TRANBKP.jcl``.

This module reproduces the *snapshot* half of the legacy TRANBKP job: it writes
every row currently in the ``transactions`` table to a new, generation-suffixed
CSV file, mirroring the mainframe GDG semantics where each REPRO created the next
relative generation ``TRANSACT.BKUP(+1)``. The destructive half of the legacy job
(DELETE the VSAM cluster, then DEFINE an empty replacement) is deliberately not
reproduced -- see the module header and AAP 0.7.5 / 0.7.6.

Non-restorable snapshot semantics (QA finding M-14)
    This artifact is an operational, human-readable *snapshot report*, NOT a
    byte-for-byte restore source. Two deliberate divergences from the legacy
    VSAM REPRO make that explicit and are, together, self-consistent:

    * The PAN (``card_num``) is MASKED to its last four digits (AAP 0.7.8), so
      the file cannot reconstruct the full card number and therefore cannot be
      replayed to recreate the source ``transactions`` rows verbatim.
    * The legacy REPRO's restore purpose is structurally obsolete here. The
      legacy DELETE/DEFINE steps that emptied and recreated the VSAM cluster --
      the only reason a restore was ever needed -- are intentionally omitted in
      this port (Alembic owns the relational schema; this job never empties or
      drops the table). Because the source is never destroyed, there is nothing
      to restore FROM this file, so a restorable (unmasked/tokenized) backup
      would trade a real sensitive-data exposure for a capability the port does
      not use.

    The snapshot is consequently exempt from byte-for-byte golden-master parity
    with the legacy REPRO output (no legacy backup dataset ships in ``app/data``
    to reconcile against); its guarantees are instead field-level content parity
    with the live table (every row present, exact ``Decimal`` amounts, masked
    PAN, no CVV/SSN) and never mutating the source -- all asserted by the
    integration tests.

Generation semantics
    Each run allocates the next monotonically increasing generation number by
    scanning the output directory for existing ``transact_bkup_*.csv`` files and
    adding one (mirroring the ``(+1)`` relative-generation reference). A run
    therefore always produces a brand-new file and never overwrites or corrupts a
    prior generation, which keeps the job safely re-runnable across the two
    points it is invoked in the batch chain.

Transaction ownership
    The caller owns the unit of work. :func:`BackupTransactions` receives an
    already-open :class:`~sqlalchemy.orm.Session` (typically from
    ``batch.db.GetSyncSession``) and is strictly read-only with respect to the
    database: it never calls ``commit``, ``rollback`` or ``close``, never deletes
    rows, and never issues DDL. Its only side effect is writing the backup file.

Numeric and sensitive-data fidelity
    ``tran_amt`` originates from ``PIC S9(09)V99`` and is carried end to end as a
    :class:`decimal.Decimal` (``NUMERIC(11, 2)``); it is serialized with ``str``
    so its exact scale is preserved and floating point is never introduced (AAP
    0.7.1). The ``card_num`` field is masked to its last four digits in the
    backup output for sensitive-data compliance (AAP 0.7.8); ``cvv`` is not part
    of the transaction record and is never written.
"""

from __future__ import annotations

import csv
import logging
from datetime import datetime
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.transaction import Transaction

from batch.jobs.output_safety import AtomicWritePath, SafeCsvWriter

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

# --- Sensitive-data masking (AAP 0.7.8) -------------------------------------
# Only the final digits of a card number are retained in the backup; everything
# preceding them is replaced with the mask character.
CARD_UNMASKED_DIGITS = 4
CARD_MASK_CHARACTER = "*"

# CSV column order. Mirrors the ``transactions`` record layout (CVTRA05Y) and
# must stay in lockstep with the row builder in :func:`_BuildBackupRow`.
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

__all__ = ["BackupTransactions"]


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


def _MaskCardNumber(cardNumber: str) -> str:
    """Mask a card number, retaining only its final digits (AAP 0.7.8).

    All characters preceding the last :data:`CARD_UNMASKED_DIGITS` are replaced
    with :data:`CARD_MASK_CHARACTER`. Values shorter than the unmasked window are
    returned unchanged (there is nothing left to hide), and a blank/absent value
    yields an empty string.

    Args:
        cardNumber: The raw card number from the transaction record.

    Returns:
        The masked card number safe for inclusion in the backup file.
    """
    if not cardNumber:
        return ""
    visibleDigits = cardNumber[-CARD_UNMASKED_DIGITS:]
    maskedLength = len(cardNumber) - len(visibleDigits)
    return f"{CARD_MASK_CHARACTER * maskedLength}{visibleDigits}"


def _FormatTimestamp(value: datetime | None) -> str:
    """Serialize a timezone-aware timestamp to ISO-8601 text.

    The ``orig_ts`` and ``proc_ts`` columns are optional (a daily row may never
    have carried a processing timestamp), so a ``None`` value serializes to an
    empty string rather than the literal ``"None"``.

    Args:
        value: The ``orig_ts`` / ``proc_ts`` value, or ``None``.

    Returns:
        The ISO-8601 string for a real timestamp, or ``""`` when absent.
    """
    if isinstance(value, datetime):
        return value.isoformat()
    return ""


def _BuildBackupRow(transaction: Transaction) -> list[str]:
    """Render one transaction as a list of CSV field values.

    Field order matches :data:`CSV_HEADER` exactly. ``tran_amt`` is serialized
    from its :class:`~decimal.Decimal` value via ``str`` so the exact scale is
    preserved and floating point is never introduced (AAP 0.7.1); ``card_num`` is
    masked to its last four digits (AAP 0.7.8); and the nullable text/timestamp
    fields collapse to an empty string when absent.

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
        _MaskCardNumber(transaction.card_num),
        _FormatTimestamp(transaction.orig_ts),
        _FormatTimestamp(transaction.proc_ts),
        transaction.status,
    ]


def _WriteBackupCsv(backupPath: Path, transactions: list[Transaction]) -> int:
    """Write the transaction snapshot to ``backupPath`` as CSV.

    Emits the :data:`CSV_HEADER` row followed by one masked, Decimal-faithful row
    per transaction. File-I/O failures surface as :class:`OSError`, which is
    logged with context and re-raised (never swallowed) so the orchestrator can
    react.

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
        # backupPath. Every cell is neutralized against CSV formula injection
        # (F-3, CWE-1236) while exact Decimal amounts pass through unchanged.
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
    """Snapshot the ``transactions`` table to a new generation backup file.

    Ports ``app/jcl/TRANBKP.jcl``: reads every current transaction and writes it
    to a fresh generation-suffixed CSV (GDG ``(+1)`` semantics). The legacy
    delete-and-redefine steps are intentionally omitted -- this job never mutates
    the database (no delete, no schema change, no ``commit``/``rollback``/
    ``close``); the caller owns the transaction and the schema is owned by
    Alembic. The snapshot is ordered deterministically by ``tran_id`` so backups
    are reproducible and comparable across runs.

    This produces a MASKED, NON-RESTORABLE operational snapshot report, not a
    restore source: the ``card_num`` is written masked to its last four digits
    (AAP 0.7.8) and the CVV/full SSN are never present, so the file cannot
    recreate the source rows verbatim. That is intentional and safe here because
    the port never empties the source table (the legacy DELETE/DEFINE steps are
    omitted), so no restore is ever required (QA finding M-14; see the module
    docstring).

    Args:
        session: An open, caller-owned SQLAlchemy
            :class:`~sqlalchemy.orm.Session`. Used read-only; the caller retains
            responsibility for committing/closing.
        outputDir: Optional destination directory for the backup file. When
            ``None`` (the default) the repository's ``backups/transactions`` tree
            is used. The directory is created if it does not already exist.

    Returns:
        The number of transactions written to the backup file.

    Raises:
        OSError: If the output directory cannot be created or the backup file
            cannot be written.
    """
    LOGGER.info("START OF EXECUTION OF TRANBKP")

    backupDirectory = _ResolveOutputDir(outputDir)
    backupDirectory.mkdir(parents=True, exist_ok=True)

    generation = _ResolveGeneration(backupDirectory)
    backupPath = backupDirectory / f"{BACKUP_FILE_PREFIX}{generation}{BACKUP_FILE_SUFFIX}"

    statement = select(Transaction).order_by(Transaction.tran_id)
    transactions = session.execute(statement).scalars().all()

    backupCount = _WriteBackupCsv(backupPath, transactions)
    LOGGER.info(
        "Backed up %d transaction(s) to generation %s (%s)",
        backupCount,
        generation,
        backupPath,
    )

    LOGGER.info("END OF EXECUTION OF TRANBKP")
    return backupCount
