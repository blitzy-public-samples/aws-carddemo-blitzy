# backend/app/repositories/xref_repo.py
# =============================================================================
# Ported from the legacy VSAM KSDS CARDXREF file (copybook CVACT03Y,
# CARD-XREF-RECORD, RECLN 50). Thin, data-access-only repository: it issues
# SQLAlchemy ORM SELECT statements against the ``card_xref`` table through a
# caller-supplied AsyncSession and returns ORM rows (or ``None``). It holds NO
# business logic, NO validation, NO domain exceptions, and issues NO commit --
# the transaction-posting "invalid card" outcome (validation code 100, from
# CBTRN02C) is decided by the SERVICE layer when this repository returns
# ``None`` (AAP sections 0.4.3 and 0.7.3).
# =============================================================================
"""Repository for the card_xref table (former VSAM KSDS CARDXREF + AIX).

Traceability:
    Legacy record layout : app/cpy/CVACT03Y.cpy  (CARD-XREF-RECORD, RECLN 50)
    Legacy VSAM file      : CARDXREF (key = XREF-CARD-NUM PIC X(16);
                            AIX on XREF-ACCT-ID [CXACAIX] and XREF-CUST-ID)
    Legacy consumers      : CBACT03C (dump), CBTRN02C (by-card -> posting code 100),
                            COACTVWC / COTRN02C (by-account via CXACAIX)

The four query axes exposed here map one-to-one onto the legacy VSAM access
paths for the card cross-reference file:

* :meth:`CardXrefRepository.GetByCardNum` -- keyed read on the primary KSDS key
  ``XREF-CARD-NUM`` (CBTRN02C ``1500-A-LOOKUP-XREF`` and COTRN02C
  ``READ-CCXREF-FILE``). A miss returns ``None``; the *service* is what turns
  that miss into posting validation code 100 ("INVALID CARD NUMBER FOUND").
* :meth:`CardXrefRepository.ListByAcctId` -- alternate-index read on
  ``XREF-ACCT-ID`` via the ``CXACAIX`` path (COACTVWC ``9200-GETCARDXREF-BYACCT``
  and COTRN02C ``READ-CXACAIX-FILE``).
* :meth:`CardXrefRepository.ListByCustId` -- alternate-index read on
  ``XREF-CUST-ID`` (the customer cross-reference lookup).
* :meth:`CardXrefRepository.ListXrefs` -- sequential browse of the whole file
  in key order (CBACT03C ``1000-XREFFILE-GET-NEXT``), with an optional resume
  key that mirrors a VSAM ``STARTBR`` reposition.

Data-access only: this module never commits, never raises a domain exception,
and encodes no business rule. It always predicates and orders on the *real*
DDL column :attr:`~app.models.card_xref.CardXref.xref_card_num` rather than the
ORM synonym ``card_num``, so the emitted SQL matches the physical schema
exactly and avoids any synonym ambiguity (AAP model contract, section 0.5.1).
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.card_xref import CardXref

# Default upper bound on the number of rows returned by the list / browse
# methods. It caps the former VSAM-style sequential and alternate-index reads so
# that a single call can never stream an entire table into memory; callers page
# by passing an explicit ``limit`` (and, for the sequential browse, a
# ``startCardNum`` resume key). ALL_UPPERCASE per the Ochs naming rule.
DEFAULT_PAGE_SIZE = 200


class CardXrefRepository:
    """Async data-access object for the ``card_xref`` cross-reference table.

    Encapsulates every SQL access path to the former VSAM ``CARDXREF`` file so
    that services and batch jobs depend on this narrow, injection-safe surface
    instead of composing SQL themselves. All methods are ``async`` and accept a
    caller-managed :class:`~sqlalchemy.ext.asyncio.AsyncSession`; the repository
    neither opens nor closes the session and never calls ``commit`` or
    ``rollback`` -- transaction control belongs to the caller's unit of work
    (AAP section 0.4.3).

    Every predicate is expressed with typed ORM column comparisons (never raw
    string SQL), so caller-supplied identifiers are always bound as parameters
    and can never be used for SQL injection.
    """

    async def GetByCardNum(self, session: AsyncSession, cardNum: str) -> CardXref | None:
        """Fetch a single cross-reference row by its card number (primary key).

        Legacy equivalent: the keyed ``READ XREF-FILE RIDFLD(card-num)`` in
        CBTRN02C ``1500-A-LOOKUP-XREF`` and COTRN02C ``READ-CCXREF-FILE``. The
        card number is the primary KSDS key, so at most one row can match.

        Args:
            session: Active async session that owns the transaction. It is not
                committed, rolled back, or closed by this method.
            cardNum: The 16-character card number (``XREF-CARD-NUM``) to read.

        Returns:
            The matching :class:`~app.models.card_xref.CardXref`, or ``None``
            when no row exists. A ``None`` result is what the service layer maps
            to posting validation code 100; this repository assigns no code.
        """
        stmt = select(CardXref).where(CardXref.xref_card_num == cardNum)
        result = await session.execute(stmt)
        return result.scalar_one_or_none()

    async def ListByAcctId(
        self,
        session: AsyncSession,
        acctId: str,
        limit: int = DEFAULT_PAGE_SIZE,
    ) -> list[CardXref]:
        """List cross-reference rows for one account (``CXACAIX`` alternate index).

        Legacy equivalent: the account-keyed alternate-index read in COACTVWC
        ``9200-GETCARDXREF-BYACCT`` and COTRN02C ``READ-CXACAIX-FILE``. Rows are
        ordered by the card-number primary key for deterministic output.

        Args:
            session: Active async session that owns the transaction.
            acctId: The 11-character account identifier (``XREF-ACCT-ID``).
            limit: Maximum number of rows to return. Defaults to
                :data:`DEFAULT_PAGE_SIZE`.

        Returns:
            A list of matching :class:`~app.models.card_xref.CardXref` rows,
            empty when the account has no cross-reference entries.
        """
        stmt = (
            select(CardXref)
            .where(CardXref.acct_id == acctId)
            .order_by(CardXref.xref_card_num)
            .limit(limit)
        )
        result = await session.execute(stmt)
        return list(result.scalars().all())

    async def ListByCustId(
        self,
        session: AsyncSession,
        custId: str,
        limit: int = DEFAULT_PAGE_SIZE,
    ) -> list[CardXref]:
        """List cross-reference rows for one customer (customer alternate index).

        Legacy equivalent: the customer-keyed alternate-index read on
        ``XREF-CUST-ID``. Rows are ordered by the card-number primary key for
        deterministic output.

        Args:
            session: Active async session that owns the transaction.
            custId: The 9-character customer identifier (``XREF-CUST-ID``).
            limit: Maximum number of rows to return. Defaults to
                :data:`DEFAULT_PAGE_SIZE`.

        Returns:
            A list of matching :class:`~app.models.card_xref.CardXref` rows,
            empty when the customer has no cross-reference entries.
        """
        stmt = (
            select(CardXref)
            .where(CardXref.cust_id == custId)
            .order_by(CardXref.xref_card_num)
            .limit(limit)
        )
        result = await session.execute(stmt)
        return list(result.scalars().all())

    async def ListXrefs(
        self,
        session: AsyncSession,
        startCardNum: str | None = None,
        limit: int = DEFAULT_PAGE_SIZE,
    ) -> list[CardXref]:
        """Browse the cross-reference table sequentially by card number.

        Legacy equivalent: the sequential ``XREFFILE`` browse driven by CBACT03C
        ``1000-XREFFILE-GET-NEXT``, which reads every record in key order. The
        optional ``startCardNum`` behaves like a VSAM ``STARTBR`` reposition,
        resuming the browse at (or after) a given key so callers can page
        through the entire file.

        Args:
            session: Active async session that owns the transaction.
            startCardNum: Inclusive lower-bound card number to resume from. When
                ``None`` (the default) the browse starts at the first row.
            limit: Maximum number of rows to return. Defaults to
                :data:`DEFAULT_PAGE_SIZE`.

        Returns:
            A list of :class:`~app.models.card_xref.CardXref` rows in ascending
            card-number order, at most ``limit`` long.
        """
        stmt = (
            select(CardXref)
            .order_by(CardXref.xref_card_num)
            .limit(limit)
        )
        if startCardNum is not None:
            stmt = stmt.where(CardXref.xref_card_num >= startCardNum)
        result = await session.execute(stmt)
        return list(result.scalars().all())
