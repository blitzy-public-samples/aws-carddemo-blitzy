"""Repository for the transactions table (former VSAM KSDS TRANSACT + card-num AIX).

Traceability:
    Legacy record layout : app/cpy/CVTRA05Y.cpy  (TRAN-RECORD, RECLN 350;
                            daily input CVTRA06Y DALYTRAN, RECLN 350)
    Legacy VSAM file      : TRANSACT (key = TRAN-ID PIC X(16); AIX = TRAN-CARD-NUM)
    Legacy consumers      : COTRN00C (list), COTRN01C (view), COTRN02C (add),
                            CORPT00C (report), CBTRN01C/02C/03C (batch)
Amounts use Decimal / NUMERIC(11,2); timestamps are TIMESTAMPTZ; never float.

This module is intentionally a *thin* data-access layer. It carries no business
rules, no field validation, no domain exceptions, and never commits. The next-id
arithmetic (max + 1), the batch posting validations (reject codes 100-103 / 109),
and the PENDING -> POSTED status transition all live in the service/job layer --
mirroring the split between the legacy VSAM I/O paragraphs and the surrounding
COBOL PROCEDURE DIVISION logic.
"""

from __future__ import annotations

from datetime import date

from sqlalchemy import Date, cast, func, literal_column, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.transaction import STATUS_POSTED, Transaction

# Default browse page size. The legacy COTRN00C transaction-list screen showed
# roughly ten rows per 3270 page; the modern service layer chooses the actual
# page size and passes it through ``limit``. This constant only supplies a safe
# bound when a caller does not specify one.
DEFAULT_PAGE_SIZE = 20


class TransactionRepository:
    """Async data-access for :class:`~app.models.transaction.Transaction`.

    Each method maps one-to-one onto a legacy VSAM access path against the
    ``TRANSACT`` KSDS (and its ``TRAN-CARD-NUM`` alternate index). SQLAlchemy
    ``select`` expressions replace the CICS ``READ`` / ``STARTBR`` / ``READNEXT``
    / ``READPREV`` / ``WRITE`` verbs. Every predicate is a parameterised
    expression construct (never string SQL), so the layer is injection-safe by
    design. No method commits; the caller owns the unit-of-work boundary.
    """

    async def GetByTranId(
        self, session: AsyncSession, tranId: str
    ) -> Transaction | None:
        """Fetch a single transaction by its primary key.

        Ports the keyed ``EXEC CICS READ DATASET(TRANSACT) RIDFLD(TRAN-ID)`` in
        COTRN01C (READ-TRANSACT-FILE, the view screen).

        Args:
            session: Active async database session.
            tranId: 16-character transaction id (``TRAN-ID``).

        Returns:
            The matching :class:`Transaction`, or ``None`` when no row exists
            (the legacy ``INVALID KEY`` / "Transaction ID NOT found" path).
        """
        stmt = select(Transaction).where(Transaction.tran_id == tranId)
        result = await session.execute(stmt)
        return result.scalar_one_or_none()

    async def ListTransactions(
        self,
        session: AsyncSession,
        startTranId: str | None = None,
        limit: int = DEFAULT_PAGE_SIZE,
        offset: int = 0,
    ) -> list[Transaction]:
        """Browse transactions forward by ascending id (page-forward).

        Ports the ``STARTBR`` (GTEQ) + ``READNEXT`` browse in COTRN00C
        (PROCESS-PAGE-FORWARD). When ``startTranId`` is supplied the browse
        resumes at the first id greater than or equal to it, matching the
        ``RIDFLD(TRAN-ID)`` positioning of the legacy start-browse.

        ``offset`` skips a fixed number of leading rows in the ordered result so
        the caller can request one page directly at the database (``ORDER BY
        tran_id OFFSET n LIMIT page_size``) instead of fetching every row up to
        the page and slicing in Python. Because the ordering is the stable
        ``tran_id`` key, ``OFFSET (page-1)*page_size LIMIT page_size`` returns the
        exact same rows the former fetch-then-slice produced -- only the number
        of rows transferred and materialized changes (the deep-pagination
        amplification is removed). ``offset`` and ``startTranId`` are independent
        positioning tools; the service uses one or the other.

        Args:
            session: Active async database session.
            startTranId: Inclusive lower-bound id to resume the browse from, or
                ``None`` to start at the first row.
            limit: Maximum number of rows to return (page size).
            offset: Number of leading ordered rows to skip before the page;
                ``0`` (the default) starts at the first row.

        Returns:
            Transactions ordered by ``tran_id``, at most ``limit`` rows, after
            skipping ``offset`` leading rows.
        """
        stmt = select(Transaction).order_by(Transaction.tran_id).limit(limit)
        if startTranId is not None:
            stmt = stmt.where(Transaction.tran_id >= startTranId)
        if offset > 0:
            stmt = stmt.offset(offset)
        result = await session.execute(stmt)
        return list(result.scalars().all())

    async def ListPostedInDateRange(
        self,
        session: AsyncSession,
        startDate: date,
        endDate: date,
        limit: int,
    ) -> list[Transaction]:
        """Return POSTED transactions whose effective date is within the range.

        Scopes the CORPT00C transaction report to the posted ledger over a date
        window, pushing both predicates into SQL so the service never fetches
        the whole table into memory:

        * ``status == POSTED`` -- excludes PENDING daily-staging rows and
          REJECTED rows, matching the legacy report that read only the posted
          ``TRANSACT`` ledger (the daily/pending rows live in the staging
          lifecycle, AAP 0.7.5).
        * ``CAST(timezone('UTC', COALESCE(proc_ts, orig_ts)) AS date)`` BETWEEN
          ``startDate`` and ``endDate`` (inclusive) -- the effective-date
          selection ported from the legacy TRAN-PROC-DT window. ``proc_ts`` is
          preferred and ``orig_ts`` is the fallback, mirroring the service's
          effective-date rule. The UTC calendar date is taken explicitly (rather
          than via a bare, session-time-zone-dependent ``::date`` cast) so the
          expression is IMMUTABLE and can be served by the composite index added
          in migration 0006; under this UTC deployment it agrees, byte for byte,
          with both the former ``::date`` cast and the Python ``datetime.date()``
          the service uses as its inclusive-window backstop.

        Rows are ordered by ``tran_id`` for a stable, deterministic result and
        bounded by ``limit`` so a pathological range cannot materialize an
        unbounded number of rows (M-08). Every predicate is a parameterized
        expression construct (never string SQL), so the query is injection-safe.

        The owning :class:`~app.models.card.Card` of each transaction is
        eager-loaded with :func:`~sqlalchemy.orm.selectinload`. The report
        (CORPT00C) labels every line with the account id reached through the
        transaction's ``card`` relationship; without eager loading that
        relationship would be fetched one row at a time (a per-card ``SELECT``
        for every distinct card in the window -- the classic N+1). ``selectinload``
        instead issues a single additional ``SELECT ... WHERE card_num IN (...)``
        that batch-loads every needed card, collapsing up to hundreds of
        per-card round-trips into one while returning the identical
        ``card.acct_id`` values (behavior-preserving; only the query count
        changes). It is also the async-safe eager strategy: the related rows are
        loaded inside the session's greenlet, so the service reads
        ``transaction.card`` as a plain attribute access with no forbidden async
        lazy-load.

        Args:
            session: Active async database session.
            startDate: Inclusive lower bound of the effective-date window.
            endDate: Inclusive upper bound of the effective-date window.
            limit: Maximum number of rows to return (safety bound).

        Returns:
            The matching POSTED transactions ordered by ``tran_id``, at most
            ``limit`` rows, each with its ``card`` relationship eager-loaded.
        """
        # Effective report date == the UTC calendar date of COALESCE(proc_ts,
        # orig_ts). The UTC normalization is written explicitly as
        # ``timezone('UTC', ...)`` (equivalently ``... AT TIME ZONE 'UTC'``)
        # rather than a bare ``::date`` cast for two reasons, both preserving the
        # exact result under this UTC deployment: (1) it makes the expression
        # IMMUTABLE, which a bare ``timestamptz::date`` is not (it is only
        # STABLE, resolving under the session time zone), so it can back a
        # functional index; and (2) it matches the composite index
        # ``ix_transactions_status_effdate`` (status, this expression) added in
        # migration 0006, letting the planner satisfy the posted-plus-date-window
        # filter with an index range scan instead of a full sequential scan and
        # external-disk sort (QA finding H1 / MINOR-5). Because the server and
        # every connection run at UTC, this yields byte-identical dates to the
        # former ``COALESCE(...)::date`` for every row.
        # ``literal_column("'UTC'")`` emits the zone as a SQL literal (``timezone(
        # 'UTC', ...)``) rather than a bound parameter, so the expression is
        # textually identical to the indexed expression in migration 0006 and the
        # planner can match it to ``ix_transactions_status_effdate``. 'UTC' is a
        # fixed constant (not user input), so this introduces no injection risk.
        effectiveDate = cast(
            func.timezone(
                literal_column("'UTC'"),
                func.coalesce(Transaction.proc_ts, Transaction.orig_ts),
            ),
            Date,
        )
        stmt = (
            select(Transaction)
            .where(Transaction.status == STATUS_POSTED)
            .where(effectiveDate >= startDate)
            .where(effectiveDate <= endDate)
            .order_by(Transaction.tran_id)
            .limit(limit)
            .options(selectinload(Transaction.card))
        )
        result = await session.execute(stmt)
        return list(result.scalars().all())

    async def ListByCardNum(
        self,
        session: AsyncSession,
        cardNum: str,
        limit: int = DEFAULT_PAGE_SIZE,
        offset: int = 0,
    ) -> list[Transaction]:
        """List a card's transactions via the card-number alternate index.

        Ports the ``TRAN-CARD-NUM`` alternate-index read used to filter by card
        (CT00) and to drive statement generation (CBSTM03A / CBSTM03B). The
        predicate targets the indexed ``card_num`` column so the browse rides
        the secondary index just as the legacy VSAM AIX did.

        ``offset`` skips a fixed number of leading rows in the ordered result so
        one page can be fetched directly (``ORDER BY tran_id OFFSET n LIMIT
        page_size``) rather than fetching every row up to the page and slicing in
        Python; on the stable ``tran_id`` ordering this returns the identical
        rows the former fetch-then-slice produced.

        Args:
            session: Active async database session.
            cardNum: 16-character card number (``TRAN-CARD-NUM``).
            limit: Maximum number of rows to return (page size).
            offset: Number of leading ordered rows to skip before the page;
                ``0`` (the default) starts at the first row.

        Returns:
            The card's transactions ordered by ``tran_id``, at most ``limit``
            rows, after skipping ``offset`` leading rows.
        """
        stmt = (
            select(Transaction)
            .where(Transaction.card_num == cardNum)
            .order_by(Transaction.tran_id)
            .limit(limit)
        )
        if offset > 0:
            stmt = stmt.offset(offset)
        result = await session.execute(stmt)
        return list(result.scalars().all())

    async def GetMaxTranId(self, session: AsyncSession) -> str | None:
        """Return the highest existing transaction id, or ``None`` if empty.

        Ports the ``MOVE HIGH-VALUES`` + ``STARTBR`` + ``READPREV`` + ``ENDBR``
        sequence in COTRN02C (ADD-TRANSACTION) that positions past end-of-file
        and reads the last record to discover the current maximum id. The
        subsequent ``ADD 1`` increment that forms the *next* id is deliberately
        left to the service layer; this method performs data access only.

        Args:
            session: Active async database session.

        Returns:
            The maximum ``tran_id`` currently stored, or ``None`` when the table
            holds no rows.
        """
        stmt = select(func.max(Transaction.tran_id))
        result = await session.execute(stmt)
        return result.scalar_one_or_none()

    async def Insert(
        self, session: AsyncSession, transaction: Transaction
    ) -> Transaction:
        """Persist a fully-built transaction row (no commit).

        Ports ``WRITE TRANSACT`` in COTRN02C (WRITE-TRANSACT-FILE) and the batch
        posting write in CBTRN02C (2900-WRITE-TRANSACTION-FILE). The caller
        constructs the complete entity -- id, amount, timestamps and status --
        exactly as the COBOL programs build ``TRAN-RECORD`` before writing it;
        ``status`` is intentionally not set here so it relies on the model /
        server default unless the service assigns it on the entity.

        The row is flushed so server-side defaults and constraint checks run,
        then refreshed so the returned entity reflects persisted state. The
        transaction boundary (commit / rollback) is owned by the caller's
        unit-of-work, so this method never commits. A duplicate ``tran_id`` or a
        card-number foreign-key violation surfaces as
        :class:`sqlalchemy.exc.IntegrityError` -- the modern equivalent of the
        legacy ``DUPKEY`` / ``INVALID KEY`` write condition -- and propagates
        unchanged to the caller for handling.

        Args:
            session: Active async database session.
            transaction: The fully-populated :class:`Transaction` entity to add.

        Returns:
            The persisted entity, refreshed from the database.
        """
        session.add(transaction)
        await session.flush()
        await session.refresh(transaction)
        return transaction


__all__ = ["TransactionRepository", "DEFAULT_PAGE_SIZE"]
