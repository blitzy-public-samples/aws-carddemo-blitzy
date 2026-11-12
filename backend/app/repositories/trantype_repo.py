"""Repository for the transaction_type table (former VSAM KSDS TRANTYPE).

Traceability:
    Legacy record layout : app/cpy/CVTRA03Y.cpy  (TRAN-TYPE-RECORD, RECLN 60)
    Legacy VSAM file      : TRANTYPE (KSDS, key = TRAN-TYPE PIC X(02))
Encapsulates all SQLAlchemy access so the datastore can change without
touching the service layer (AAP 0.4.3 repository pattern).
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.transaction_type import TransactionType

# Neutral technical paging default for the sequential-browse method. This is a
# transport/pagination concern, NOT a business rule ported from COBOL: the
# service layer may override it by passing an explicit ``limit`` argument.
DEFAULT_PAGE_SIZE = 50


class TransactionTypeRepository:
    """Async data-access for the ``transaction_type`` reference table.

    Modern equivalent of the VSAM ``TRANTYPE`` KSDS access performed by the
    legacy COBOL online/batch programs (record layout ``CVTRA03Y``). Every
    method is a thin, read-only, parameterized SQLAlchemy statement: this class
    owns persistence only and holds no business logic, no validation, and no
    transaction control. Callers inject the :class:`AsyncSession`; the
    repository never opens, commits, or closes it.
    """

    async def GetByTranType(
        self, session: AsyncSession, tranType: str
    ) -> TransactionType | None:
        """Return the transaction type for ``tranType`` or ``None`` if absent.

        Replaces the legacy keyed ``EXEC CICS READ TRANTYPE RIDFLD(tran-type)``
        (a VSAM KSDS random read on the two-character type code). The predicate
        is bound as a parameter (``==``), so it is injection-safe.
        """
        stmt = select(TransactionType).where(
            TransactionType.tran_type == tranType
        )
        result = await session.execute(stmt)
        return result.scalar_one_or_none()

    async def ListTransactionTypes(
        self, session: AsyncSession, limit: int = DEFAULT_PAGE_SIZE
    ) -> list[TransactionType]:
        """Return transaction-type rows ordered by code, capped at ``limit``.

        Replaces the legacy sequential browse of the ``TRANTYPE`` VSAM file
        (``STARTBR`` / ``READNEXT``). Ordering by the primary key gives the
        stable, ascending sequence the mainframe browse produced.
        """
        stmt = (
            select(TransactionType)
            .order_by(TransactionType.tran_type)
            .limit(limit)
        )
        result = await session.execute(stmt)
        return list(result.scalars().all())


__all__ = ["DEFAULT_PAGE_SIZE", "TransactionTypeRepository"]
