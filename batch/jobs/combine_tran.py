# Ported from legacy JCL app/jcl/COMBTRAN.jcl + control card app/ctl/REPROCT.ctl
# (CardDemo). Function: merge backup + system (interest) transactions sorted by
# tran_id ascending into a single combined master ledger file.
"""Transaction-combine (SORT/REPRO) pass for the CardDemo batch chain.

Legacy behavior (app/jcl/COMBTRAN.jcl + app/ctl/REPROCT.ctl)
    The mainframe ``COMBTRAN`` job ran in two IDCAMS/SORT steps:

    * ``STEP05R`` executed ``PGM=SORT`` with ``SORTIN`` concatenating the backup
      of posted transactions ``AWS.M2.CARDDEMO.TRANSACT.BKUP(0)`` with the
      system/interest transactions ``AWS.M2.CARDDEMO.SYSTRAN(0)`` (produced by
      the INTCALC job). Using ``SYMNAMES`` field ``TRAN-ID,1,16,CH`` and control
      card ``SORT FIELDS=(TRAN-ID,A)`` it wrote a single ascending-by-``TRAN-ID``
      stream to ``AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)``.
    * ``STEP10`` executed ``PGM=IDCAMS`` and ``REPRO``ed that combined sequential
      file into the master ``AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS`` (the generic
      ``REPRO INFILE(FILEIN) OUTFILE(FILEOUT)`` shape captured in
      ``app/ctl/REPROCT.ctl``).

    Net legacy effect: merge the backup + system transactions, sorted by
    ``tran_id`` ascending, into the master ``TRANSACT`` KSDS.

DB-port rationale and the M17 correction
    In the relational target the posted transactions (written by
    ``batch/jobs/post_transactions.py`` -> ``CBTRN02C``) and the system/interest
    transactions (written by ``batch/jobs/interest_calc.py`` -> ``CBACT04C``,
    carrying ``tran_source == "System"``) already coexist as rows in the single
    ``transactions`` table (:mod:`app.models.transaction`), so there is no
    physical REPRO into a separate KSDS master to perform.

    An earlier port modeled this as a read-only count/no-op, but that produced no
    combined artifact and therefore was not a faithful SORT/REPRO (QA finding
    M17). This module now performs a REAL combine: it materializes the merged
    master ledger to a single ascending-by-``tran_id`` CSV file -- the direct
    relational analogue of ``TRANSACT.COMBINED`` -- and reconciles the number of
    rows written against the authoritative POSTED row count in the database.
    :func:`CombineTransactions`:

    * selects the POSTED transactions **only** (QA finding M16): the legacy SORT
      merged the posted BKUP with the posted SYSTRAN interest rows, so PENDING
      daily-staging rows and validation-REJECTED rows never belong in the
      combined master;
    * orders them by ``tran_id`` ascending, preserving the legacy
      ``SORT FIELDS=(TRAN-ID,A)`` key, and guards against a duplicate key
      (impossible because ``tran_id`` is the primary key, so a mismatch is logged
      rather than raised to keep re-runs safe);
    * writes them to a combined CSV via :func:`~batch.jobs.output_safety.AtomicWritePath`
      (atomic publication) restricted to owner-only ``0600`` in an owner-only
      ``0700`` directory (QA finding M13); and
    * reconciles the written count against ``COUNT(*) WHERE status = POSTED`` and
      returns the combined count.

Transaction ownership
    The caller owns the unit of work. This module receives an already-open
    :class:`~sqlalchemy.orm.Session` (created by ``batch.db.GetSyncSession``) and
    is strictly READ-ONLY with respect to the database: it never commits, rolls
    back, closes the session, mutates rows, or issues DDL. Its only side effect
    is writing the combined ledger file. It is idempotent and safe to re-run,
    mirroring the legacy job's re-runnable SORT/REPRO semantics -- a re-run
    deterministically rewrites the same combined content.

Numeric and sensitive-data fidelity
    ``tran_amt`` originates from ``PIC S9(09)V99`` and is carried end to end as a
    :class:`decimal.Decimal` (``NUMERIC(11, 2)``); it is serialized with ``str``
    so its exact scale is preserved and floating point is never introduced (AAP
    0.7.1). The combined file is the faithful relational analogue of the legacy
    ``TRANSACT.COMBINED`` master copy, so it carries the full transaction record
    (including the full ``card_num``) to remain a coherent master ledger; the
    file is protected by owner-only ``0600`` permissions rather than by masking
    (QA finding M13). The sensitive ``cvv`` is not part of the transaction record
    and is never present.
"""

from __future__ import annotations

import csv
import logging
from collections.abc import Iterable, Iterator
from datetime import datetime
from pathlib import Path

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models.transaction import STATUS_POSTED, Transaction

from batch.jobs.output_safety import AtomicWritePath, SafeCsvWriter, SecureDirectory

# Module logger (Ochs ALL_UPPERCASE module constant). Named after the module so
# batch log output is attributable to the COMBTRAN port.
LOGGER = logging.getLogger(__name__)

__all__ = ["CombineTransactions"]


# --------------------------------------------------------------------------- #
# Module constants (ALL_UPPERCASE per the Ochs Rule; no magic values).
# --------------------------------------------------------------------------- #

# Deterministic name of the combined master-ledger file (the relational
# analogue of the legacy ``TRANSACT.COMBINED`` sequential dataset). A single
# canonical file is rewritten atomically on each run, so the finalization pass
# stays idempotent (a re-run reproduces identical content).
COMBINED_FILE_NAME = "transact_combined.csv"

# Text encoding for the combined CSV. UTF-8 is the modern default; the stored
# values are ordinary text decoded at load time from the ASCII/EBCDIC seeds.
FILE_ENCODING = "utf-8"

# Server-side fetch size for the ordered master scan. The walk is streamed in
# fixed batches via ``yield_per`` (QA finding MODERATE-6) so the full ledger is
# never materialized in memory at once; streaming changes only the fetch
# granularity -- the ordered rows visited and the rows written are identical to
# a full materialization.
STREAM_CHUNK_SIZE = 1000

# Default combined-output location, relative to the repository root, used when
# the caller does not pass an explicit ``outputDir``. Kept as path segments so
# the platform separator is applied by ``Path.joinpath``. The leading ``out``
# segment places the default under the gitignored ``out/`` tree so an
# unqualified combine never spills untracked files into the working tree.
DEFAULT_COMBINED_SUBDIR = ("out", "combined", "transactions")

# CSV column order. Mirrors the ``transactions`` record layout (CVTRA05Y) and
# must stay in lockstep with the row builder in :func:`_BuildCombinedRow`.
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


def _ResolveOutputDir(outputDir: str | Path | None) -> Path:
    """Resolve the directory that will hold the combined ledger file.

    When the caller passes an explicit location it is honored verbatim (after
    normalization to :class:`~pathlib.Path`). Otherwise the job defaults to the
    repository's gitignored ``out/combined/transactions`` tree, located relative
    to this module so the default is stable regardless of the process working
    directory and never leaves untracked files in the working tree.

    Args:
        outputDir: An explicit output directory (``str`` or ``Path``), or
            ``None`` to use the repository-relative default.

    Returns:
        The resolved combined-output :class:`~pathlib.Path` (not yet created).
    """
    if outputDir is not None:
        return Path(outputDir)
    repositoryRoot = Path(__file__).resolve().parents[2]
    return repositoryRoot.joinpath(*DEFAULT_COMBINED_SUBDIR)


def _IterateOrdered(session: Session) -> Iterator[Transaction]:
    """Yield the POSTED transactions ordered by ``tran_id`` ascending.

    Preserves the legacy ``SORT FIELDS=(TRAN-ID,A)`` key by ordering on the
    primary key ``tran_id`` ascending, restricted to POSTED rows so PENDING
    daily-staging rows and validation-REJECTED rows are excluded from the
    combined master (QA finding M16). The scan is streamed server-side in fixed
    batches via ``yield_per`` (QA finding MODERATE-6) so the full ledger is never
    materialized in memory at once; the ordered rows delivered are identical to a
    full materialization. Each row is walked once to assert a duplicate-free
    sequence; because ``tran_id`` is the primary key a physical duplicate cannot
    actually occur, so the guard only documents the SORT/merge intent and logs a
    warning (never raises) to keep the pass safe to re-run.

    Args:
        session: An open, caller-owned SQLAlchemy session. Used read-only.

    Yields:
        The POSTED :class:`~app.models.transaction.Transaction` rows in ascending
        ``tran_id`` order.
    """
    statement = (
        select(Transaction)
        .where(Transaction.status == STATUS_POSTED)
        .order_by(Transaction.tran_id.asc())
        .execution_options(yield_per=STREAM_CHUNK_SIZE)
    )

    previousTranId = None
    for currentTransaction in session.execute(statement).scalars():
        currentTranId = currentTransaction.tran_id
        if previousTranId is not None and currentTranId == previousTranId:
            LOGGER.warning(
                "COMBTRAN detected duplicate tran_id during merge: %s",
                currentTranId,
            )
        previousTranId = currentTranId
        yield currentTransaction


def _FormatTimestamp(value: datetime | None) -> str:
    """Serialize a timezone-aware timestamp to ISO-8601 text.

    The ``orig_ts`` and ``proc_ts`` columns are optional (a row may never have
    carried a processing timestamp), so a ``None`` value serializes to an empty
    string rather than the literal ``"None"``.

    Args:
        value: The ``orig_ts`` / ``proc_ts`` value, or ``None``.

    Returns:
        The ISO-8601 string for a real timestamp, or ``""`` when absent.
    """
    if isinstance(value, datetime):
        return value.isoformat()
    return ""


def _BuildCombinedRow(transaction: Transaction) -> list[str]:
    """Render one transaction as a list of CSV field values (full fidelity).

    Field order matches :data:`CSV_HEADER` exactly. ``tran_amt`` is serialized
    from its :class:`~decimal.Decimal` value via ``str`` so the exact scale is
    preserved and floating point is never introduced (AAP 0.7.1). The full
    ``card_num`` is written so the combined file is a coherent master-ledger
    copy (the legacy ``TRANSACT.COMBINED`` was a full-fidelity sequential image);
    the file is protected by owner-only permissions (QA finding M13) rather than
    by masking. The nullable text/timestamp fields collapse to an empty string
    when absent.

    Args:
        transaction: A mapped :class:`~app.models.transaction.Transaction` row.

    Returns:
        The ordered CSV field values for a single combined line.
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
        transaction.card_num or "",
        _FormatTimestamp(transaction.orig_ts),
        _FormatTimestamp(transaction.proc_ts),
        transaction.status,
    ]


def _WriteCombinedCsv(combinedPath: Path, transactions: Iterable[Transaction]) -> int:
    """Write the merged, ordered transaction ledger to ``combinedPath`` as CSV.

    Emits the :data:`CSV_HEADER` row followed by one Decimal-faithful row per
    transaction (already ordered ascending by ``tran_id`` by the caller). The
    file is published atomically and restricted to owner-only ``0600`` (QA
    findings M13 / atomic publication); a mid-write failure leaves no partial
    file at ``combinedPath``. The ``transactions`` iterable is consumed once and
    streamed straight to the CSV writer, so a ``yield_per`` generator (QA finding
    MODERATE-6) is serialized without materializing the whole ledger in memory.

    Args:
        combinedPath: The full path of the combined file to create.
        transactions: The POSTED transactions to serialize, ordered by the
            caller. Consumed once; may be a streaming generator.

    Returns:
        The number of transaction rows written (excluding the header).

    Raises:
        OSError: If the combined file cannot be opened or written.
    """
    rowsWritten = 0
    try:
        with AtomicWritePath(combinedPath) as stagingPath:
            with stagingPath.open("w", encoding=FILE_ENCODING, newline="") as combinedFile:
                csvWriter = SafeCsvWriter(csv.writer(combinedFile))
                csvWriter.writerow(CSV_HEADER)
                for transaction in transactions:
                    csvWriter.writerow(_BuildCombinedRow(transaction))
                    rowsWritten += 1
    except OSError:
        LOGGER.exception("Failed writing combined transaction ledger to %s", combinedPath)
        raise
    return rowsWritten


def CombineTransactions(session: Session, outputDir: str | Path | None = None) -> int:
    """Merge the POSTED transaction ledger to a combined file and return its count.

    Faithful port of the legacy ``COMBTRAN`` SORT + REPRO job (see the module
    docstring). Selects the POSTED transactions ordered ascending by ``tran_id``
    (``SORT FIELDS=(TRAN-ID,A)`` parity; QA finding M16 excludes PENDING/REJECTED
    rows), writes them to a single combined CSV -- the relational analogue of the
    legacy ``TRANSACT.COMBINED`` master -- and reconciles the number of rows
    written against the authoritative database ``COUNT(*) WHERE status = POSTED``
    (the modern equivalent of confirming the "REPRO into master" copied every
    combined row). Any divergence is logged, not raised, keeping the pass
    read-only with respect to the database and safe to re-run.

    The caller owns the transaction boundary: this function performs no
    ``commit``, ``rollback``, ``close``, row mutation, or DDL against the
    database. Its only side effect is writing the combined ledger file, which is
    published atomically and secured to owner-only permissions (QA finding M13).

    Args:
        session: An open, caller-owned SQLAlchemy
            :class:`~sqlalchemy.orm.Session` (from ``batch.db.GetSyncSession``).
        outputDir: Optional destination directory for the combined file. When
            ``None`` (the default) the repository's gitignored
            ``out/combined/transactions`` tree is used. The directory is created
            (owner-only ``0700``) if it does not already exist.

    Returns:
        The combined transaction count -- the number of POSTED rows written to
        the combined ledger file.

    Raises:
        OSError: If the output directory cannot be created or the combined file
            cannot be written.
    """
    LOGGER.info("START OF EXECUTION OF COMBTRAN")

    combinedDirectory = SecureDirectory(_ResolveOutputDir(outputDir))
    combinedPath = combinedDirectory / COMBINED_FILE_NAME

    orderedTransactions = _IterateOrdered(session)
    combinedCount = _WriteCombinedCsv(combinedPath, orderedTransactions)

    # Authoritative DB-side POSTED count. Equals ``combinedCount`` in normal
    # operation (single synchronous transaction, no mutation); a mismatch would
    # indicate a concurrent writer and is surfaced as a warning to preserve
    # idempotency and confirm the "REPRO into master" copied every combined row.
    authoritativeCount = session.execute(
        select(func.count())
        .select_from(Transaction)
        .where(Transaction.status == STATUS_POSTED)
    ).scalar_one()
    if authoritativeCount != combinedCount:
        LOGGER.warning(
            "COMBTRAN count mismatch: combined file wrote %d POSTED rows, COUNT(*) = %d",
            combinedCount,
            authoritativeCount,
        )

    LOGGER.info(
        "Combined %d POSTED transaction(s) into %s",
        combinedCount,
        combinedPath,
    )
    LOGGER.info("END OF EXECUTION OF COMBTRAN")
    return combinedCount
