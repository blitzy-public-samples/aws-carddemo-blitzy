"""Repository for the customers table (former VSAM KSDS CUSTDATA).

Traceability:
    Legacy record layout : app/cpy/CVCUS01Y.cpy  (CUSTOMER-RECORD, RECLN 500)
    Legacy VSAM file      : CUSTDATA (KSDS, key = CUST-ID PIC 9(09))
    Legacy consumers      : CBCUS01C (dump), COACTVWC/COACTUPC (account+customer view/update)
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.customer import Customer

# Default upper bound on rows returned by a single ListCustomers page. Mirrors
# the bounded sequential browse of CUSTDATA (CBCUS01C) rather than an unbounded
# table scan; callers may override the limit per request.
DEFAULT_PAGE_SIZE = 100


class CustomerRepository:
    """Async data-access layer for the ``customers`` table.

    Each method ports one VSAM access verb against the former ``CUSTDATA`` KSDS
    to its SQLAlchemy 2.0 equivalent: keyed ``READ`` -> primary-key ``select``;
    ``READ ... UPDATE`` -> ``SELECT ... FOR UPDATE``; ``REWRITE`` -> ``flush``;
    and sequential browse -> an ordered, limited ``select``.

    This layer is intentionally thin. It performs data access only and holds no
    business logic, validation, domain exceptions, response masking, or
    transaction control -- the caller owns ``commit``/``rollback``. The full
    customer row is returned as stored; SSN masking is a response-layer concern
    and is never applied or logged here.

    Instances are stateless and safe to reuse; the caller supplies the
    per-request :class:`~sqlalchemy.ext.asyncio.AsyncSession`.
    """

    async def GetByCustId(self, session: AsyncSession, custId: str) -> Customer | None:
        """Return the customer whose primary key is ``custId``, or ``None``.

        Ports the keyed ``EXEC CICS READ`` of ``CUSTDATA`` by ``RIDFLD(CUST-ID)``
        used for the read-only account+customer view (``COACTVWC``,
        9400-GETCUSTDATA-BYCUST) and the batch dump (``CBCUS01C``). A missing
        row yields ``None``, mirroring the legacy ``INVALID KEY`` (VSAM
        file-status '23') branch instead of raising.
        """
        stmt = select(Customer).where(Customer.cust_id == custId)
        return (await session.execute(stmt)).scalar_one_or_none()

    async def GetForUpdate(self, session: AsyncSession, custId: str) -> Customer | None:
        """Fetch a customer with a pessimistic row lock, or ``None``.

        Ports the ``READ CUSTDATA ... UPDATE`` that ``COACTUPC`` issues to lock
        the customer record for its READ-UPDATE -> REWRITE cycle (AAP 0.7.4).
        The ``.with_for_update()`` clause emits ``SELECT ... FOR UPDATE`` so a
        concurrent updater blocks until this unit of work commits, reproducing
        the CICS/VSAM record-lock serialization that prevented lost updates.
        """
        stmt = select(Customer).where(Customer.cust_id == custId).with_for_update()
        return (await session.execute(stmt)).scalar_one_or_none()

    async def Update(self, session: AsyncSession, customer: Customer) -> Customer:
        """Persist pending changes on an attached ``customer`` (``REWRITE``).

        Ports ``REWRITE CUSTDATA`` from ``COACTUPC``. The ``customer`` instance
        must already be attached to ``session`` (fetched via
        :meth:`GetForUpdate` or :meth:`GetByCustId` in the same session), so the
        flush sends the pending ``UPDATE`` to the database. This method does NOT
        commit; the caller owns the surrounding transaction boundary.
        """
        await session.flush()
        return customer

    async def ListCustomers(
        self,
        session: AsyncSession,
        startCustId: str | None = None,
        limit: int = DEFAULT_PAGE_SIZE,
    ) -> list[Customer]:
        """Return customers ordered by ``cust_id``, at most ``limit`` rows.

        Ports the sequential browse of ``CUSTDATA`` (``CBCUS01C``,
        1000-CUSTFILE-GET-NEXT). When ``startCustId`` is supplied the browse
        resumes at that key (``cust_id >= startCustId``), reproducing VSAM
        ``STARTBR``/``READNEXT`` keyed positioning for forward pagination.
        """
        stmt = select(Customer).order_by(Customer.cust_id).limit(limit)
        if startCustId is not None:
            stmt = stmt.where(Customer.cust_id >= startCustId)
        return list((await session.execute(stmt)).scalars().all())
