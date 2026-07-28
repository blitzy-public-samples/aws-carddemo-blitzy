"""Repository for the tran_category_balance table (former VSAM KSDS TCATBALF).

Traceability:
    Legacy record layout : app/cpy/CVTRA01Y.cpy  (TRAN-CAT-BAL-RECORD, RECLN 50)
    Legacy VSAM file      : TCATBALF (KSDS, composite key = acct_id + tran_type_cd + tran_cat_cd)
    Legacy consumers      : app/cbl/CBTRN02C.cbl (posting), app/cbl/CBACT04C.cbl (interest)
Balances use Decimal / NUMERIC(11,2); never float (AAP 0.7.1).

This module is a THIN data-access layer only. It maps the legacy VSAM verbs
issued against the ``TCATBALF`` KSDS onto SQLAlchemy 2.0 async primitives:

    * ``READ TCATBAL-FILE ... KEY IS FD-TRAN-CAT-KEY``  -> ``GetByKey``
      (keyed read; app/cbl/CBTRN02C.cbl L474, app/cbl/CBACT04C.cbl L326)
    * ``WRITE FD-TRAN-CAT-BAL-RECORD``                  -> ``Create``
      (new category-balance record; app/cbl/CBTRN02C.cbl L510)
    * ``REWRITE FD-TRAN-CAT-BAL-RECORD``                -> ``Update``
      (rewrite accumulated balance; app/cbl/CBTRN02C.cbl L528)

It holds NO business logic, NO validation, and NO domain exceptions, and it
never commits. In the legacy posting program the insert-vs-update (upsert)
choice is made by the caller: ``CBTRN02C`` issues ``WRITE`` only when the keyed
``READ`` reports "record not found" and ``REWRITE`` otherwise. That decision
therefore stays with the service layer here; this repository exposes ``Create``
and ``Update`` as separate primitives and never auto-decides between them. The
owning service controls the unit-of-work (commit / rollback).
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.tran_category_balance import TranCategoryBalance

# Default cap on rows returned by ``ListByAcctId``. A single account has only a
# small, bounded number of transaction-category balances, so 200 returns every
# category row for one account while still bounding an otherwise open scan.
# Callers may override it per call.
DEFAULT_PAGE_SIZE = 200


class TranCategoryBalanceRepository:
    """Async data-access repository for :class:`TranCategoryBalance` rows.

    Ports the ``TCATBALF`` KSDS access performed by ``CBTRN02C`` (posting) and
    ``CBACT04C`` (interest calculation). Every method receives the caller's
    :class:`~sqlalchemy.ext.asyncio.AsyncSession`, so the service layer owns the
    surrounding transaction and no method commits. Balances are handled purely
    as :class:`decimal.Decimal` (the ORM column is ``NUMERIC(11, 2)``) and are
    never coerced to ``float`` (AAP 0.7.1, Finding #1).
    """

    async def GetByKey(
        self,
        session: AsyncSession,
        acctId: str,
        tranTypeCd: str,
        tranCatCd: str,
    ) -> TranCategoryBalance | None:
        """Fetch one balance row by its full composite key, or ``None``.

        Ports the keyed ``READ TCATBAL-FILE`` on ``FD-TRAN-CAT-KEY`` issued by
        ``CBTRN02C`` (L474) and ``CBACT04C`` (L326). The three key predicates are
        applied in the exact COBOL field order: ``acct_id`` -> ``tran_type_cd``
        -> ``tran_cat_cd``.

        Args:
            session: Active async unit-of-work supplied by the service layer.
            acctId: Account identifier (``TRANCAT-ACCT-ID``); leading zeros kept.
            tranTypeCd: Two-character transaction type code (``TRANCAT-TYPE-CD``).
            tranCatCd: Transaction category code (``TRANCAT-CD``); zeros kept.

        Returns:
            The matching :class:`TranCategoryBalance`, or ``None`` when no row has
            that composite key (the legacy "record not found" condition).
        """
        stmt = select(TranCategoryBalance).where(
            TranCategoryBalance.acct_id == acctId,
            TranCategoryBalance.tran_type_cd == tranTypeCd,
            TranCategoryBalance.tran_cat_cd == tranCatCd,
        )
        return (await session.execute(stmt)).scalar_one_or_none()

    async def ListByAcctId(
        self,
        session: AsyncSession,
        acctId: str,
        limit: int = DEFAULT_PAGE_SIZE,
    ) -> list[TranCategoryBalance]:
        """Return an account's category balances, ordered by type then category.

        Supports the per-account rollup reads that ``CBACT04C`` performs while
        walking an account's transaction-category balances. Rows are ordered by
        ``tran_type_cd`` then ``tran_cat_cd`` for deterministic output and are
        capped at ``limit`` rows.

        Args:
            session: Active async unit-of-work supplied by the service layer.
            acctId: Account identifier whose category balances are requested.
            limit: Maximum number of rows to return (defaults to
                :data:`DEFAULT_PAGE_SIZE`).

        Returns:
            A list of :class:`TranCategoryBalance` rows (possibly empty).
        """
        stmt = (
            select(TranCategoryBalance)
            .where(TranCategoryBalance.acct_id == acctId)
            .order_by(
                TranCategoryBalance.tran_type_cd,
                TranCategoryBalance.tran_cat_cd,
            )
            .limit(limit)
        )
        return list((await session.execute(stmt)).scalars().all())

    async def Create(
        self,
        session: AsyncSession,
        balanceRow: TranCategoryBalance,
    ) -> TranCategoryBalance:
        """Insert a new category-balance row (legacy ``WRITE TCATBAL-FILE``).

        Ports ``WRITE FD-TRAN-CAT-BAL-RECORD`` (``CBTRN02C`` L510), which the
        legacy program issues only when the keyed ``READ`` reported "record not
        found". The caller passes a fully-populated ORM entity (bundling all
        field values into one object keeps the parameter count within the Ochs
        limit); this method persists it without deciding insert-vs-update.

        The row is added and flushed so the composite key and any database-side
        defaults are validated inside the caller's transaction, then refreshed so
        the returned instance reflects the persisted state. This method does NOT
        commit -- the service layer owns the unit-of-work. A composite-key
        collision surfaces as :class:`sqlalchemy.exc.IntegrityError`, which is
        allowed to propagate to the service layer for translation; this thin
        repository raises no domain exceptions and swallows nothing.

        Args:
            session: Active async unit-of-work supplied by the service layer.
            balanceRow: Fully-built :class:`TranCategoryBalance` to persist.

        Returns:
            The same instance, refreshed from the database after the flush.
        """
        session.add(balanceRow)
        await session.flush()
        await session.refresh(balanceRow)
        return balanceRow

    async def Update(
        self,
        session: AsyncSession,
        balanceRow: TranCategoryBalance,
    ) -> TranCategoryBalance:
        """Persist changes to an attached balance row (legacy ``REWRITE``).

        Ports ``REWRITE FD-TRAN-CAT-BAL-RECORD`` (``CBTRN02C`` L528), which writes
        back an accumulated balance. ``balanceRow`` is assumed to have been loaded
        via :meth:`GetByKey` in the SAME session, so it is already attached and
        its mutated columns are pending; a single flush emits the ``UPDATE``. This
        method does NOT commit -- the service layer owns the unit-of-work.

        Args:
            session: Active async unit-of-work supplied by the service layer.
            balanceRow: Session-attached :class:`TranCategoryBalance` carrying the
                pending change (typically a mutated ``balance``).

        Returns:
            The same instance after the pending ``UPDATE`` is flushed.
        """
        await session.flush()
        return balanceRow
