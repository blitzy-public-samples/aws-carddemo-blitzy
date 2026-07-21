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

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.transaction import Transaction

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
    ) -> list[Transaction]:
        """Browse transactions forward by ascending id (page-forward).

        Ports the ``STARTBR`` (GTEQ) + ``READNEXT`` browse in COTRN00C
        (PROCESS-PAGE-FORWARD). When ``startTranId`` is supplied the browse
        resumes at the first id greater than or equal to it, matching the
        ``RIDFLD(TRAN-ID)`` positioning of the legacy start-browse.

        Args:
            session: Active async database session.
            startTranId: Inclusive lower-bound id to resume the browse from, or
                ``None`` to start at the first row.
            limit: Maximum number of rows to return (page size).

        Returns:
            Transactions ordered by ``tran_id``, at most ``limit`` rows.
        """
        stmt = select(Transaction).order_by(Transaction.tran_id).limit(limit)
        if startTranId is not None:
            stmt = stmt.where(Transaction.tran_id >= startTranId)
        result = await session.execute(stmt)
        return list(result.scalars().all())

    async def ListByCardNum(
        self,
        session: AsyncSession,
        cardNum: str,
        limit: int = DEFAULT_PAGE_SIZE,
    ) -> list[Transaction]:
        """List a card's transactions via the card-number alternate index.

        Ports the ``TRAN-CARD-NUM`` alternate-index read used to filter by card
        (CT00) and to drive statement generation (CBSTM03A / CBSTM03B). The
        predicate targets the indexed ``card_num`` column so the browse rides
        the secondary index just as the legacy VSAM AIX did.

        Args:
            session: Active async database session.
            cardNum: 16-character card number (``TRAN-CARD-NUM``).
            limit: Maximum number of rows to return (page size).

        Returns:
            The card's transactions ordered by ``tran_id``, at most ``limit``
            rows.
        """
        stmt = (
            select(Transaction)
            .where(Transaction.card_num == cardNum)
            .order_by(Transaction.tran_id)
            .limit(limit)
        )
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
