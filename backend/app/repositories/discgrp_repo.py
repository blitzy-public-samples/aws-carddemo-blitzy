"""Repository for the disclosure_group table (former VSAM KSDS DISCGRP).

Traceability:
    Legacy record layout : app/cpy/CVTRA02Y.cpy  (DIS-GROUP-RECORD, RECLN 50)
    Legacy VSAM file      : DISCGRP (KSDS, composite key = group_id + tran_type_cd + tran_cat_cd)
    Legacy consumer       : app/cbl/CBACT04C.cbl (interest-rate lookup)
Interest-rate values use Decimal / NUMERIC(6,2); never float (AAP 0.7.1 Finding #2).
DEFAULT-group fallback is service logic, not implemented here.
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.disclosure_group import DisclosureGroup

# Default row cap for the full-table browse query. It mirrors the modest
# cardinality of the legacy DISCGRP KSDS reference dataset; callers may override
# it per request. Declared as an ALL_UPPERCASE module constant per the Ochs rule.
DEFAULT_PAGE_SIZE = 200


class DisclosureGroupRepository:
    """Async data-access for the ``disclosure_group`` table (legacy ``DISCGRP``).

    This repository is deliberately thin: it performs keyed and ordered reads
    only. It holds no business logic, no validation, no domain exceptions, and
    never commits. Transaction control, and the CBACT04C ``DEFAULT``-group
    fallback that retries a missing key against a ``'DEFAULT'`` group, both
    belong to the service/job layer and are intentionally absent here (AAP scope
    boundary). Interest-rate values flow through as :class:`decimal.Decimal`
    (column type ``NUMERIC(6, 2)``) and are never coerced to a binary float.
    """

    async def GetByKey(
        self,
        session: AsyncSession,
        groupId: str,
        tranTypeCd: str,
        tranCatCd: str,
    ) -> DisclosureGroup | None:
        """Fetch a single disclosure-group row by its exact composite key.

        Modern equivalent of the keyed ``READ DISCGRP`` on ``FD-DISCGRP-KEY`` in
        the CBACT04C paragraph ``1200-GET-INTEREST-RATE``. The ``WHERE`` predicate
        order mirrors the VSAM composite key exactly -- ``group_id`` then
        ``tran_type_cd`` then ``tran_cat_cd`` -- and the real ``group_id`` column
        is queried rather than its ``acct_group_id`` ORM synonym. When no row
        matches (the legacy ``INVALID KEY`` / file-status ``'23'`` case) this
        returns ``None``; the ``'DEFAULT'``-group retry is a service concern and
        is not performed here.

        Args:
            session: Active async unit-of-work session. This method neither
                begins nor commits a transaction.
            groupId: Account group id (``DIS-ACCT-GROUP-ID``, up to 10 chars).
            tranTypeCd: Transaction type code (``DIS-TRAN-TYPE-CD``, 2 chars).
            tranCatCd: Transaction category code (``DIS-TRAN-CAT-CD``, 4 chars).

        Returns:
            The matching :class:`DisclosureGroup`, or ``None`` if absent.
        """
        stmt = select(DisclosureGroup).where(
            DisclosureGroup.group_id == groupId,
            DisclosureGroup.tran_type_cd == tranTypeCd,
            DisclosureGroup.tran_cat_cd == tranCatCd,
        )
        result = await session.execute(stmt)
        return result.scalar_one_or_none()

    async def ListDisclosureGroups(
        self,
        session: AsyncSession,
        limit: int = DEFAULT_PAGE_SIZE,
    ) -> list[DisclosureGroup]:
        """List disclosure-group rows ordered by the composite key.

        Provides a stable, key-ordered browse over the reference table for
        seed verification and administrative listing. The ``ORDER BY`` follows
        the VSAM key sequence (``group_id`` then ``tran_type_cd`` then
        ``tran_cat_cd``) so results are deterministic and reconcilable against
        the legacy dataset. No pagination cursor is exposed; ``limit`` simply
        caps the returned rows.

        Args:
            session: Active async unit-of-work session. This method neither
                begins nor commits a transaction.
            limit: Maximum number of rows to return; defaults to
                :data:`DEFAULT_PAGE_SIZE`.

        Returns:
            A list of :class:`DisclosureGroup` rows in composite-key order.
        """
        stmt = (
            select(DisclosureGroup)
            .order_by(
                DisclosureGroup.group_id,
                DisclosureGroup.tran_type_cd,
                DisclosureGroup.tran_cat_cd,
            )
            .limit(limit)
        )
        result = await session.execute(stmt)
        return list(result.scalars().all())
