# Ported from COBOL VSAM KSDS TRANCATG (record copybook app/cpy/CVTRA04Y.cpy,
# TRAN-CAT-RECORD, RECLN 60). Reference-only origin (never modified). This module
# is the data-access layer for the transaction_category reference table and
# realizes the AAP 0.4.3 repository pattern: one repository per former VSAM file,
# translating the legacy keyed READ / STARTBR-READNEXT verbs into SQLAlchemy 2.0
# async selects. It holds NO business logic, NO validation, NO domain exceptions,
# NO session creation, and NO commit -- callers (transaction_service / batch jobs)
# own transactions and rules.
"""Repository for the transaction_category table (former VSAM KSDS TRANCATG).

Traceability:
    Legacy record layout : app/cpy/CVTRA04Y.cpy  (TRAN-CAT-RECORD, RECLN 60)
    Legacy VSAM file      : TRANCATG (KSDS, composite key = TRAN-TYPE-CD + TRAN-CAT-CD)
Encapsulates all SQLAlchemy access (AAP 0.4.3 repository pattern).

The transaction-category reference file supplied a human-readable description for
each ``(transaction-type, transaction-category)`` pairing. In the legacy system
the online transaction browse/detail flows and the posting/report batch programs
issued keyed ``READ TRANCATG`` calls (and sequential ``STARTBR``/``READNEXT``
browses) against this KSDS to label transaction categories. Those access paths
are re-expressed here as parameterized SQLAlchemy ``select`` statements executed
on a caller-supplied :class:`~sqlalchemy.ext.asyncio.AsyncSession`.

Design contract (thin data-access only):

* Every method receives an already-open ``AsyncSession`` from the caller (the
  dependency-injected ``get_db`` session in the API layer, or a batch session).
  This module never opens, closes, commits, or rolls back a session -- the
  unit-of-work boundary belongs to the service/job that owns the request.
* Only read operations are exposed for this reference (lookup) table; they return
  ORM :class:`~app.models.transaction_category.TransactionCategory` instances,
  ``None``, or a ``list`` -- never DTOs, tuples, or raw rows.
* All predicates are built from mapped-column expression objects, so the emitted
  SQL is always parameterized (injection-safe); no textual/string SQL is used.
* The composite-key predicate order mirrors the 6-byte VSAM key layout exactly:
  ``TRAN-TYPE-CD`` (2) then ``TRAN-CAT-CD`` (4).
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.transaction_category import TransactionCategory

# Default upper bound on rows returned by an unfiltered browse. The legacy
# TRANCATG reference file is small and fully enumerated by callers; this cap
# keeps a defensive limit on sequential reads that replace the COBOL
# STARTBR/READNEXT browse, and is overridable per call.
DEFAULT_PAGE_SIZE = 100


class TransactionCategoryRepository:
    """Async data-access repository for :class:`TransactionCategory` rows.

    Modern equivalent of the keyed and sequential access paths that the legacy
    online and batch COBOL programs used against the VSAM ``TRANCATG`` KSDS
    (record copybook ``CVTRA04Y``). Each public method maps a former VSAM verb
    onto a SQLAlchemy 2.0 async statement:

    * :meth:`GetByKey` -> keyed ``READ TRANCATG`` on the composite key.
    * :meth:`ListTransactionCategories` -> ``STARTBR``/``READNEXT`` browse.

    The repository is stateless and therefore safe to instantiate once and share
    across requests; all mutable state (the transaction) lives on the caller's
    :class:`~sqlalchemy.ext.asyncio.AsyncSession`.
    """

    async def GetByKey(
        self,
        session: AsyncSession,
        tranTypeCd: str,
        tranCatCd: str,
    ) -> TransactionCategory | None:
        """Fetch a single transaction-category row by its composite key.

        Modern equivalent of the legacy keyed ``READ TRANCATG`` against the
        VSAM KSDS. The two key components are matched in their exact VSAM order
        (transaction type first, then category), producing a parameterized,
        injection-safe lookup on the composite primary key.

        Args:
            session: Caller-owned async session; not committed or closed here.
            tranTypeCd: Two-character transaction-type code (``TRAN-TYPE-CD``),
                the first component of the composite key.
            tranCatCd: Four-character, zero-padded transaction-category code
                (``TRAN-CAT-CD``), the second component of the composite key.

        Returns:
            The matching :class:`TransactionCategory`, or ``None`` when no row
            has the requested key (the SQL analogue of an ``INVALID KEY``
            condition on the legacy ``READ``).
        """
        stmt = select(TransactionCategory).where(
            TransactionCategory.tran_type_cd == tranTypeCd,
            TransactionCategory.tran_cat_cd == tranCatCd,
        )
        result = await session.execute(stmt)
        return result.scalar_one_or_none()

    async def ListTransactionCategories(
        self,
        session: AsyncSession,
        limit: int = DEFAULT_PAGE_SIZE,
    ) -> list[TransactionCategory]:
        """List transaction-category rows in composite-key order.

        Modern equivalent of a full ``STARTBR``/``READNEXT`` browse of the VSAM
        ``TRANCATG`` KSDS. Rows are ordered by the composite key (transaction
        type, then category) so the sequence matches the legacy ascending-key
        browse, and the result set is capped by ``limit``.

        Args:
            session: Caller-owned async session; not committed or closed here.
            limit: Maximum number of rows to return; defaults to
                :data:`DEFAULT_PAGE_SIZE`.

        Returns:
            A list of :class:`TransactionCategory` instances (possibly empty)
            in ascending composite-key order.
        """
        stmt = (
            select(TransactionCategory)
            .order_by(
                TransactionCategory.tran_type_cd,
                TransactionCategory.tran_cat_cd,
            )
            .limit(limit)
        )
        result = await session.execute(stmt)
        return list(result.scalars().all())


__all__ = ["TransactionCategoryRepository", "DEFAULT_PAGE_SIZE"]
