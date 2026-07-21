# Ported from legacy JCL app/jcl/COMBTRAN.jcl + control card app/ctl/REPROCT.ctl
# (CardDemo). Function: merge backup + system (interest) transactions sorted by
# tran_id ascending into the master transactions table.
"""Transaction-combine finalization pass for the CardDemo batch chain.

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

DB-port rationale (locked in AAP Phase 7)
    In the relational target there are no separate ``BKUP`` / ``SYSTRAN``
    datasets to physically merge. Posted transactions (written by
    ``batch/jobs/post_transactions.py`` -> ``CBTRN02C``) and interest/system
    transactions (written by ``batch/jobs/interest_calc.py`` -> ``CBACT04C``,
    carrying ``tran_source == "System"``) already coexist as rows in the single
    ``transactions`` table (:mod:`app.models.transaction`). The physical
    "concatenate two files and REPRO into the master" therefore collapses to a
    no-op against the database, because every combined row is already present.

    :func:`CombineTransactions` consequently becomes a deterministic, idempotent
    *finalization / verification* pass that:

    * selects **all** transactions ordered by ``tran_id`` ascending, preserving
      the legacy ``SORT FIELDS=(TRAN-ID,A)`` key;
    * verifies a coherent, duplicate-free ordering (the guard documents the
      SORT/merge intent -- ``tran_id`` is the primary key so physical duplicates
      cannot exist, and a mismatch is logged rather than raised so re-runs stay
      safe); and
    * returns the combined transaction count.

Transaction ownership
    The caller owns the unit of work. This module receives an already-open
    :class:`~sqlalchemy.orm.Session` (created by ``batch.db.GetSyncSession``) and
    performs **read-only** work: it never commits, rolls back, closes the
    session, mutates rows, or issues DDL. It is inherently idempotent and safe to
    re-run, mirroring the legacy job's re-runnable SORT/REPRO semantics.
"""

from __future__ import annotations

import logging

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models.transaction import Transaction

# Module logger (Ochs ALL_UPPERCASE module constant). Named after the module so
# batch log output is attributable to the COMBTRAN port.
LOGGER = logging.getLogger(__name__)

__all__ = ["CombineTransactions"]


def _IterateOrdered(session: Session) -> list[Transaction]:
    """Return every transaction ordered by ``tran_id`` ascending.

    Preserves the legacy ``SORT FIELDS=(TRAN-ID,A)`` key by ordering on the
    primary key ``tran_id`` ascending, then walks the ordered result once to
    assert a duplicate-free sequence. Because ``tran_id`` is the primary key a
    physical duplicate cannot actually occur; the guard therefore only documents
    the SORT/merge intent and logs a warning (never raises) so that re-running
    the finalization pass remains safe and side-effect free.

    Args:
        session: An open, caller-owned SQLAlchemy session. Used read-only.

    Returns:
        The list of :class:`~app.models.transaction.Transaction` rows in
        ascending ``tran_id`` order.
    """
    statement = select(Transaction).order_by(Transaction.tran_id.asc())
    orderedTransactions = list(session.execute(statement).scalars().all())

    previousTranId = None
    for currentTransaction in orderedTransactions:
        currentTranId = currentTransaction.tran_id
        if previousTranId is not None and currentTranId == previousTranId:
            LOGGER.warning(
                "COMBTRAN detected duplicate tran_id during merge: %s",
                currentTranId,
            )
        previousTranId = currentTranId

    return orderedTransactions


def CombineTransactions(session: Session) -> int:
    """Finalize the combined transaction ledger and return its row count.

    Deterministic, idempotent port of the legacy ``COMBTRAN`` SORT + REPRO job
    (see the module docstring). Selects all transactions ordered ascending by
    ``tran_id`` (``SORT FIELDS=(TRAN-ID,A)`` parity), verifies the ordering is
    duplicate-free, and cross-checks the ordered-pass row count against the
    authoritative database ``COUNT(*)`` -- the modern equivalent of confirming
    the "REPRO into master" copied every combined row. Any divergence is logged,
    not raised, keeping the pass read-only and safe to re-run.

    The caller owns the transaction boundary: this function performs no
    ``commit``, ``rollback``, ``close``, row mutation, or DDL.

    Args:
        session: An open, caller-owned SQLAlchemy
            :class:`~sqlalchemy.orm.Session` (from ``batch.db.GetSyncSession``).

    Returns:
        The combined transaction count -- the number of rows observed by the
        ordered finalization pass.
    """
    LOGGER.info("START OF EXECUTION OF COMBTRAN")

    orderedTransactions = _IterateOrdered(session)
    combinedCount = len(orderedTransactions)

    # Authoritative DB-side count. Equals ``combinedCount`` in normal operation
    # (single synchronous transaction, no mutation); a mismatch would indicate a
    # concurrent writer and is surfaced as a warning to preserve idempotency.
    authoritativeCount = session.execute(select(func.count()).select_from(Transaction)).scalar_one()
    if authoritativeCount != combinedCount:
        LOGGER.warning(
            "COMBTRAN count mismatch: ordered pass saw %d rows, COUNT(*) = %d",
            combinedCount,
            authoritativeCount,
        )

    LOGGER.info("END OF EXECUTION OF COMBTRAN")
    return combinedCount
