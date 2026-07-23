"""Repository for the cards table (former VSAM KSDS CARDDAT + acct-id AIX).

Traceability:
    Legacy record layout : app/cpy/CVACT02Y.cpy  (CARD-RECORD, RECLN 150)
    Legacy VSAM file      : CARDDAT (KSDS, key = CARD-NUM PIC X(16); AIX = CARD-ACCT-ID)
    Legacy consumers      : COCRDLIC (list, <=7/page), COCRDSLC (view),
                            COCRDUPC (update), CBACT02C (batch dump)
The <=7 rows/page browse preserves COCRDLIC WS-MAX-SCREEN-LINES = 7 (F-004).

Architecture (AAP 0.4.3 repository pattern): this module is the single
data-access boundary for the ``cards`` table. It translates the legacy VSAM
verbs -- keyed ``READ``, ``STARTBR``/``READNEXT`` browse, and ``REWRITE`` -- into
SQLAlchemy 2.0 async expression-language statements so that the datastore can
change without touching business logic. It is deliberately thin: NO business
rules, NO field validation, NO domain exceptions, and NO transaction commit.
Those responsibilities belong to ``card_service`` (which ports COCRDLIC /
COCRDSLC / COCRDUPC) and to the batch card dumper (CBACT02C).

Security (AAP 0.7.8): ``card_num`` and ``cvv_cd`` are sensitive. This repository
returns full ORM rows for the caller to handle, but it never logs ``card_num``
or ``cvv_cd``. Masking ``card_num`` and never serialising ``cvv_cd`` are
schema/service/serialization concerns handled outside this layer.
"""

from __future__ import annotations

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.card import Card

# F-004 browse page size. Mirrors the COCRDLIC working-storage constant
# ``WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7`` and serves as the default row
# cap for every list/browse query (at most seven cards per screen page).
MAX_SCREEN_LINES = 7


class CardRepository:
    """Async data-access repository for the ``cards`` table.

    Encapsulates every read/write against ``cards`` behind SQLAlchemy 2.0
    expression-language statements, replacing the legacy VSAM verbs
    (``READ`` / ``STARTBR`` / ``READNEXT`` / ``REWRITE``). This layer is
    intentionally thin -- it performs NO validation, NO business rules, NO
    domain exceptions and NO transaction commits; those belong to the
    consuming service layer (``card_service``).

    All predicates are built from mapped column expressions (never raw SQL
    strings), so every bound value is parameterised and injection-safe. The
    sensitive ``card_num`` and ``cvv_cd`` values travel back inside the ORM
    row for the caller to handle; this class never logs either value.

    Ochs conventions (AAP 0.8.2 / 0.8.3): the class and its methods use
    PascalCase, local variables use camelCase, and the page-size business
    rule is an ALL_UPPERCASE module constant.
    """

    async def GetByCardNum(
        self,
        session: AsyncSession,
        cardNum: str,
    ) -> Card | None:
        """Fetch a single card by its primary key (card number).

        Ports the legacy keyed ``READ CARDDAT RIDFLD(card-num)`` performed by
        the view (COCRDSLC) and update (COCRDUPC) online programs.

        Args:
            session: The active async database session (unit of work).
            cardNum: The 16-character card-number primary key to look up.

        Returns:
            The matching :class:`~app.models.card.Card`, or ``None`` when no
            row carries that card number -- the relational equivalent of the
            COBOL ``INVALID KEY`` / ``NOTFND`` condition.
        """
        stmt = select(Card).where(Card.card_num == cardNum)
        result = await session.execute(stmt)
        return result.scalar_one_or_none()

    async def GetForUpdate(
        self,
        session: AsyncSession,
        cardNum: str,
    ) -> Card | None:
        """Fetch a card by primary key, locked ``FOR UPDATE`` (COCRDUPC READ...UPDATE).

        Emits ``SELECT ... FOR UPDATE`` -- the modern equivalent of the legacy
        ``READ CARDDAT ... UPDATE`` that COCRDUPC issues before its ``REWRITE``
        (``9200-WRITE-PROCESSING``) -- and holds the acquired row lock until the
        caller's transaction commits or rolls back. This serializes concurrent
        card updates so two independent transactions cannot lose each other's
        write (QA finding M-10, AAP 0.7.4), exactly as
        :meth:`app.repositories.account_repo.AccountRepository.GetForUpdate`
        does for the account update path.

        ``populate_existing()`` is applied so the row's column values are
        RE-READ from the database even when the instance already lives in the
        session identity map. The card-update service reads the row once to
        capture its before-image and then calls this method to lock and re-read
        it; without ``populate_existing`` the identity-mapped (stale) attributes
        would be returned and the before-image comparison could not observe a
        concurrent modification. Forcing the refresh makes the locked read
        reflect the TRUE current state so the concurrency check is sound.

        Args:
            session: The active async database session; the acquired row lock is
                held until this session commits or rolls back.
            cardNum: The 16-character card-number primary key to lock.

        Returns:
            The locked :class:`~app.models.card.Card`, or ``None`` when no row
            carries that card number (the ``INVALID KEY`` / ``NOTFND``
            condition).
        """
        stmt = (
            select(Card)
            .where(Card.card_num == cardNum)
            .with_for_update()
            .execution_options(populate_existing=True)
        )
        result = await session.execute(stmt)
        return result.scalar_one_or_none()

    async def ListByAcctId(
        self,
        session: AsyncSession,
        acctId: str,
        limit: int = MAX_SCREEN_LINES,
    ) -> list[Card]:
        """List the cards belonging to one account, ordered by card number.

        Ports the account-scoped read that the legacy programs perform via the
        ``CARD-ACCT-ID`` alternate index (AIX). ``acct_id`` is an indexed
        column on ``cards``, so this predicate rides that secondary index.

        Args:
            session: The active async database session.
            acctId: The 11-character account id whose cards are requested.
            limit: Maximum number of rows to return. Defaults to
                :data:`MAX_SCREEN_LINES` (7), preserving the COCRDLIC F-004
                one-page browse size.

        Returns:
            A possibly-empty list of :class:`~app.models.card.Card` rows,
            ordered by ``card_num`` ascending.
        """
        stmt = (
            select(Card)
            .where(Card.acct_id == acctId)
            .order_by(Card.card_num)
            .limit(limit)
        )
        result = await session.execute(stmt)
        return list(result.scalars().all())

    async def GetByAcctId(
        self,
        session: AsyncSession,
        acctId: str,
    ) -> Card | None:
        """Fetch a single card by its owning account id (``CARD-ACCT-ID`` AIX).

        Ports the account-scoped keyed read the legacy programs perform through
        the ``CARD-ACCT-ID`` alternate index when the account -- not the PAN --
        is the known key. It returns at most one row (the lowest ``card_num``
        for that account, matching the :meth:`ListByAcctId` ordering) so the
        modern by-account card view/update can resolve an account to its card
        WITHOUT ever exposing the full PAN in the URL (AAP 0.7.8). CardDemo's
        card:account relationship is 1:1 in the seed data, so the account
        uniquely identifies a card; should an account ever carry several cards,
        the first by ascending ``card_num`` is returned deterministically.

        Args:
            session: The active async database session (unit of work).
            acctId: The 11-character owning-account id to look up.

        Returns:
            The matching :class:`~app.models.card.Card`, or ``None`` when the
            account owns no card -- the relational equivalent of the COBOL
            ``INVALID KEY`` / ``NOTFND`` condition on the AIX path.
        """
        stmt = (
            select(Card)
            .where(Card.acct_id == acctId)
            .order_by(Card.card_num)
            .limit(1)
        )
        result = await session.execute(stmt)
        return result.scalars().first()

    async def ListCards(
        self,
        session: AsyncSession,
        acctId: str | None = None,
        startCardNum: str | None = None,
        limit: int = MAX_SCREEN_LINES,
    ) -> list[Card]:
        """Browse cards, optionally filtered by account and/or a start key.

        Ports the COCRDLIC forward browse -- ``STARTBR CARDDAT GTEQ
        RIDFLD(card-num)`` followed by a ``READNEXT`` loop -- together with the
        optional account filter of paragraph ``9500-FILTER-RECORDS``. Rows are
        ordered by ``card_num`` and capped at ``limit`` so a single call
        returns at most one screen page.

        This method takes four parameters excluding ``self`` (session plus
        three data parameters), which is the Ochs maximum; no further
        parameters may be added.

        Args:
            session: The active async database session.
            acctId: When provided, restrict the browse to this account id
                (indexed alternate key). ``None`` browses across all accounts.
            startCardNum: When provided, begin the browse at this card number
                using a ``>=`` predicate, mirroring the legacy ``GTEQ`` start.
                ``None`` begins at the lowest card number.
            limit: Maximum rows to return. Defaults to
                :data:`MAX_SCREEN_LINES` (7). A service may request
                ``MAX_SCREEN_LINES + 1`` to detect a following page and show
                only the first seven; this repository stays generic and never
                hardcodes that probe value.

        Returns:
            A possibly-empty list of :class:`~app.models.card.Card` rows,
            ordered by ``card_num`` ascending.
        """
        stmt = select(Card).order_by(Card.card_num).limit(limit)
        if acctId is not None:
            stmt = stmt.where(Card.acct_id == acctId)
        if startCardNum is not None:
            # STARTBR ... GTEQ: begin at the first key >= the supplied value.
            stmt = stmt.where(Card.card_num >= startCardNum)
        result = await session.execute(stmt)
        return list(result.scalars().all())

    async def CountCards(
        self,
        session: AsyncSession,
        acctId: str | None = None,
    ) -> int:
        """Count the cards visible to a browse, optionally scoped to an account.

        Supplies the exact total-row count that ``card_service`` needs to build
        a truthful paginated envelope (``total_items`` / ``total_pages`` /
        ``has_next``), replacing the fabricated estimate the look-ahead probe
        produced. When ``acctId`` is supplied the count is scoped to that
        account (matching the COCRDLIC ``9500-FILTER-RECORDS`` account filter);
        otherwise every card row is counted. The count rides the same indexed
        ``acct_id`` predicate as :meth:`ListByAcctId`, so it stays cheap.

        Args:
            session: The active async database session.
            acctId: When provided, count only the cards for this account id
                (indexed alternate key). ``None`` counts across all accounts.

        Returns:
            The total number of matching card rows (zero when none match).
        """
        stmt = select(func.count()).select_from(Card)
        if acctId is not None:
            stmt = stmt.where(Card.acct_id == acctId)
        result = await session.execute(stmt)
        return int(result.scalar_one())

    async def Update(
        self,
        session: AsyncSession,
        card: Card,
    ) -> Card:
        """Persist in-place changes to an already-attached card row.

        Ports the COCRDUPC ``READ ... UPDATE`` -> ``REWRITE CARDDAT`` cycle.
        ``card`` is expected to have been loaded via :meth:`GetByCardNum`
        within the same ``session``, so it is already tracked by the unit of
        work; flushing emits the ``UPDATE`` for its mutated attributes.
        Optimistic-lock / concurrency handling and the commit boundary are the
        service layer's responsibility, not this repository's.

        Args:
            session: The active async database session that owns ``card``.
            card: The attached, mutated :class:`~app.models.card.Card` to
                persist.

        Returns:
            The same ``card`` instance after the flush.
        """
        await session.flush()
        return card


__all__ = ["CardRepository", "MAX_SCREEN_LINES"]
