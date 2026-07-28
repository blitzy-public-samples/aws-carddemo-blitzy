"""Repository for the accounts table (former VSAM KSDS ACCTDAT).

Traceability:
    Legacy record layout : app/cpy/CVACT01Y.cpy  (ACCOUNT-RECORD, RECLN 300)
    Legacy VSAM file      : ACCTDAT (KSDS, key = ACCT-ID PIC 9(11))
    Legacy consumers      : COACTVWC (view), COACTUPC (READ-UPDATE->REWRITE),
                            CBACT01C (batch dump), CBTRN02C (posting acct lookup)
GetForUpdate implements the SELECT ... FOR UPDATE lock that preserves the
COACTUPC optimistic/pessimistic record-lock semantics (AAP 0.7.4).
Monetary fields use Decimal / NUMERIC(12,2); never float (AAP 0.7.1).

Layer boundary (AAP 0.4.3 -- repository pattern):
    This module is a THIN data-access layer. It only translates the legacy VSAM
    verbs of the ``ACCTDAT`` KSDS into SQLAlchemy statements -- keyed ``READ``
    (``GetByAcctId``), ``READ ... UPDATE`` (``GetForUpdate``), ``REWRITE``
    (``Update``), and the sequential ``ACCTFILE`` browse (``ListAccounts``). It
    holds NO business logic: available-credit / over-limit arithmetic, the
    optimistic change-detection compare, and posting reason-code assignment
    (100-103, 109) all live in the service layer (``account_service``,
    ``billpay_service``, ``transaction_service`` and the ``post_transactions``
    batch job). The repository never opens a transaction boundary of its own --
    it never commits or rolls back; commit ownership stays with the per-request
    unit-of-work session supplied by the caller (AAP 0.4.3).
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.account import Account

__all__ = ["AccountRepository", "DEFAULT_PAGE_SIZE"]

# Default page size for the sequential account browse (``ListAccounts``). The
# legacy CBACT01C dump walks the whole KSDS; the modern port pages the scan so a
# single call bounds its result set and memory footprint. Callers may override.
DEFAULT_PAGE_SIZE = 100


class AccountRepository:
    """Data-access gateway for the ``accounts`` table (VSAM ``ACCTDAT`` KSDS).

    Each method maps one legacy VSAM access path to an injection-safe SQLAlchemy
    statement built purely from ORM column expressions (never string SQL). The
    repository is stateless: the active :class:`~sqlalchemy.ext.asyncio.AsyncSession`
    is passed in per call, so one instance is safe to share across requests and
    the transaction/commit boundary always remains with the caller.
    """

    async def GetByAcctId(
        self,
        session: AsyncSession,
        acctId: str,
    ) -> Account | None:
        """Fetch one account by primary key, or ``None`` when it does not exist.

        Modern equivalent of the keyed ``EXEC CICS READ DATASET(ACCTDAT)
        RIDFLD(acct-id)`` in COACTVWC (paragraph 9300-GETACCTDATA-BYACCT) and of
        the ``READ ACCOUNT-FILE ... INVALID KEY`` lookup in CBTRN02C (paragraph
        1500-B-LOOKUP-ACCT). This is a lock-free read; the caller decides how to
        treat a missing row -- for example the posting service maps a ``None``
        result to validation reason code 101 -- so no domain exception is raised
        here.

        Args:
            session: Active async unit-of-work session.
            acctId: The 11-character account identifier; leading zeros are
                significant and preserved (the VSAM key is stored as text).

        Returns:
            The matching :class:`~app.models.account.Account`, or ``None`` if no
            row has that ``acct_id``.
        """
        stmt = select(Account).where(Account.acct_id == acctId)
        return (await session.execute(stmt)).scalar_one_or_none()

    async def GetForUpdate(
        self,
        session: AsyncSession,
        acctId: str,
    ) -> Account | None:
        """Fetch an account row locked for update, or ``None`` when absent.

        Emits ``SELECT ... FOR UPDATE`` via :meth:`Select.with_for_update`, the
        modern equivalent of ``EXEC CICS READ FILE(ACCTDAT) UPDATE`` in COACTUPC
        (paragraph 9600-WRITE-PROCESSING), which locks the record before the
        subsequent ``REWRITE``. Holding the row lock for the remainder of the
        transaction reproduces the legacy READ-for-UPDATE -> REWRITE
        serialization and prevents the lost-update anomaly that CICS/VSAM record
        locking previously guaranteed (AAP 0.7.4). The change-detection compare
        and the field mutation are performed by the service, not here.

        The ``populate_existing=True`` execution option is essential to that
        guarantee. The legacy ``9600-WRITE-PROCESSING`` re-reads the record under
        the lock straight ``INTO ACCOUNT-RECORD`` (COACTUPC L3894-3902), i.e. the
        locked read *overwrites* the working-storage copy with the current
        committed image. A caller may already have loaded this same ``acct_id``
        lock-free earlier in the request (for example the posting flow's initial
        lookup via :meth:`GetByAcctId`), leaving a stale instance in the session
        identity map. Without ``populate_existing`` SQLAlchemy would hand that
        cached, pre-lock instance back and silently discard the freshly locked
        row's column values -- so a balance mutation would build on a stale
        starting value and the concurrent increment would be lost. Setting
        ``populate_existing=True`` forces the attributes of the identity-mapped
        instance to be refreshed from the row read under the lock, faithfully
        matching the COBOL ``READ ... UPDATE INTO`` re-read and closing the
        lost-update window (AAP 0.7.4, 0.8.1 behavior preservation).

        Args:
            session: Active async unit-of-work session; the acquired row lock is
                held until this session commits or rolls back.
            acctId: The 11-character account identifier to lock.

        Returns:
            The locked :class:`~app.models.account.Account`, or ``None`` if no
            row has that ``acct_id``.
        """
        stmt = (
            select(Account)
            .where(Account.acct_id == acctId)
            .with_for_update()
            .execution_options(populate_existing=True)
        )
        return (await session.execute(stmt)).scalar_one_or_none()

    async def Update(
        self,
        session: AsyncSession,
        account: Account,
    ) -> Account:
        """Flush a modified, session-attached account so its UPDATE is emitted.

        Modern equivalent of ``EXEC CICS REWRITE FILE(ACCTDAT)`` in COACTUPC and
        of ``REWRITE FD-ACCTFILE-REC`` in CBTRN02C (paragraph
        2800-UPDATE-ACCOUNT-REC). The ``account`` is assumed to have been fetched
        via :meth:`GetForUpdate` within this same ``session`` (already attached
        and row-locked) and already mutated by the service. Only
        :meth:`~sqlalchemy.ext.asyncio.AsyncSession.flush` is issued: the UPDATE
        is sent to the database, but the surrounding transaction is intentionally
        NOT committed -- commit/rollback ownership stays with the per-request
        unit-of-work. A failing rewrite (the legacy reason code 109 path) surfaces
        as a SQLAlchemy error for the service to translate; it is not caught here.

        Args:
            session: The session that owns the attached, locked ``account``.
            account: The mutated :class:`~app.models.account.Account` to persist.

        Returns:
            The same :class:`~app.models.account.Account` instance, now flushed.
        """
        await session.flush()
        return account

    async def ListAccounts(
        self,
        session: AsyncSession,
        startAcctId: str | None = None,
        limit: int = DEFAULT_PAGE_SIZE,
    ) -> list[Account]:
        """Return accounts in ascending key order for a sequential browse.

        Modern equivalent of the sequential ``READ ACCTFILE-FILE INTO
        ACCOUNT-RECORD`` in CBACT01C (paragraph 1000-ACCTFILE-GET-NEXT), whose
        file is declared ``ORGANIZATION IS INDEXED`` / ``ACCESS MODE IS
        SEQUENTIAL`` -- i.e. rows are returned in ascending ``ACCT-ID`` order.
        ``startAcctId`` gives key-positioned resume (``acct_id >= startAcctId``)
        so the batch account dump / print job can page through the file with
        keyset pagination; ``limit`` bounds the page size.

        Args:
            session: Active async unit-of-work session.
            startAcctId: Inclusive lower-bound account id to resume the scan
                from, or ``None`` to start at the first key.
            limit: Maximum number of rows to return (defaults to
                :data:`DEFAULT_PAGE_SIZE`).

        Returns:
            A list of :class:`~app.models.account.Account` ordered by
            ``acct_id``; empty when no rows match.
        """
        stmt = select(Account).order_by(Account.acct_id).limit(limit)
        if startAcctId is not None:
            stmt = stmt.where(Account.acct_id >= startAcctId)
        return list((await session.execute(stmt)).scalars().all())
