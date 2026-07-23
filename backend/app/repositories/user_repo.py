"""Repository for the users table (former VSAM KSDS USRSEC).

Traceability:
    Legacy record layout : app/cpy/CSUSR01Y.cpy  (SEC-USER-DATA, RECLN 80)
    Legacy VSAM file      : USRSEC (KSDS, key = SEC-USR-ID PIC X(08))
    Legacy consumers      : COSGN00C (signon), COUSR00C-03C (user CRUD)
Passwords are stored ONLY as password_hash; hashing/verification is
performed in the service layer (app/core/security.py), never here.
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user import User

# Default admin user-list page size. The legacy browse program COUSR00C
# (STARTBR/READNEXT over the VSAM USRSEC file) rendered roughly ten rows per
# 3270 screen; the exact page size is chosen by the service layer and passed
# through the ``limit`` argument, so this constant is only the repository-level
# default when the caller does not specify one.
DEFAULT_PAGE_SIZE = 20

# Administrator role code (legacy COCOM01Y ``88 CDEMO-USRTYP-ADMIN VALUE 'A'``).
# Defined locally so this data-access module stays self-contained; the same
# single-character code is intentionally repeated (with the same citation) in
# app.core.dependencies and app.schemas.auth.
ADMIN_USER_TYPE = "A"


class UserRepository:
    """Async data-access layer for the ``users`` table (legacy VSAM USRSEC).

    Each method is a thin translation of a CICS/VSAM file verb used by the
    legacy security programs into a SQLAlchemy 2.0 async statement::

        EXEC CICS READ (COSGN00C)                       -> GetByUserId
        EXEC CICS STARTBR + READNEXT (COUSR00C)         -> ListUsers
        EXEC CICS WRITE (COUSR01C)                      -> Create
        EXEC CICS READ ... UPDATE + REWRITE (COUSR02C)  -> Update
        EXEC CICS READ ... UPDATE + DELETE (COUSR03C)   -> Delete

    The class is data access only and holds no state: no business rules, no
    field validation, no domain-exception translation, no authentication or
    role gating, and no transaction ``commit`` (the caller owns the unit of
    work). Password hashing and verification are service-layer concerns
    (``app.core.security``); this class reads and writes only the already
    hashed ``password_hash`` and never handles, logs, or returns a plaintext
    password.
    """

    async def GetByUserId(
        self, session: AsyncSession, userId: str
    ) -> User | None:
        """Fetch a single user by primary key, or ``None`` when absent.

        Ports the COSGN00C ``READ-USER-SEC-FILE`` paragraph (``EXEC CICS READ
        USRSEC RIDFLD(WS-USER-ID)``): a RESP of ``NORMAL`` returns the record
        and a RESP of ``NOTFND`` maps to ``None``.

        Args:
            session: Active async unit-of-work session.
            userId: Eight-character user id (``SEC-USR-ID``); the ``users`` PK.

        Returns:
            The matching :class:`~app.models.user.User`, or ``None`` when no
            row has that ``user_id``.
        """
        stmt = select(User).where(User.user_id == userId)
        result = await session.execute(stmt)
        return result.scalar_one_or_none()

    async def ListUsers(
        self,
        session: AsyncSession,
        startUserId: str | None = None,
        limit: int = DEFAULT_PAGE_SIZE,
    ) -> list[User]:
        """Return an ordered page of users, optionally starting at a key.

        Ports the COUSR00C browse (``STARTBR`` positioned greater-than-or-equal
        followed by ``READNEXT``): rows are ordered by ``user_id`` and, when a
        start key is supplied, restricted to ids greater than or equal to it,
        emulating the VSAM ``GTEQ`` browse position.

        Args:
            session: Active async unit-of-work session.
            startUserId: Optional inclusive lower-bound user id (``GTEQ``).
            limit: Maximum number of rows to return (page size).

        Returns:
            A list of :class:`~app.models.user.User` in ascending ``user_id``
            order; empty when no rows match.
        """
        stmt = select(User).order_by(User.user_id).limit(limit)
        if startUserId is not None:
            stmt = stmt.where(User.user_id >= startUserId)
        result = await session.execute(stmt)
        return list(result.scalars().all())

    async def CountAdminsForUpdate(self, session: AsyncSession) -> int:
        """Count administrator users, locking their rows ``FOR UPDATE``.

        Supports the last-administrator invariant (QA finding F3): the service
        must not demote or delete an administrator when doing so would leave zero
        administrators, which would irrecoverably lock everyone out of the
        admin-only user-management screens (COUSR00C-03C). There was no legacy
        equivalent -- the mainframe allowed the security file to be emptied -- so
        this is a modern security-uplift guard (AAP 0.1.1).

        Selecting the administrator rows ``FOR UPDATE`` serializes concurrent
        removals: a second transaction attempting a competing demotion/deletion
        blocks until the first commits, then re-evaluates the ``user_type = 'A'``
        predicate against the freshly committed data (PostgreSQL READ COMMITTED
        EvalPlanQual re-check), so it observes the already-reduced administrator
        set and cannot also drive the count to zero.

        The rows are counted in Python rather than via ``SELECT count(*) ... FOR
        UPDATE`` because PostgreSQL rejects ``FOR UPDATE`` alongside an aggregate;
        the administrator set is tiny (a handful of security users), so
        materializing it is negligible. This method takes row locks but performs
        no write and never commits -- the caller owns the unit of work, and the
        locks are released when that transaction commits or rolls back.

        Args:
            session: Active async unit-of-work session. The acquired ``FOR
                UPDATE`` locks are held until this session's transaction ends.

        Returns:
            The number of users whose ``user_type`` is the administrator code
            ``'A'``.
        """
        stmt = (
            select(User)
            .where(User.user_type == ADMIN_USER_TYPE)
            .with_for_update()
        )
        result = await session.execute(stmt)
        return len(result.scalars().all())

    async def Create(self, session: AsyncSession, user: User) -> User:
        """Insert a new user row and return the persisted entity.

        Ports the COUSR01C ``WRITE-USER-SEC-FILE`` paragraph (``EXEC CICS WRITE
        USRSEC``). The ``user`` entity arrives fully built by the service
        layer, already carrying a hashed ``password_hash`` (never a plaintext
        password). The row is flushed (not committed) so the caller's unit of
        work retains control of the transaction boundary, then refreshed to
        reflect the persisted state.

        A duplicate primary key raises ``sqlalchemy.exc.IntegrityError`` on
        flush -- the modern equivalent of the legacy ``DUPKEY`` / ``DUPREC``
        response. It is intentionally propagated unchanged for the service
        layer to translate into a domain error; this data-access layer performs
        no exception translation.

        Args:
            session: Active async unit-of-work session.
            user: Fully-built user entity to persist.

        Returns:
            The same :class:`~app.models.user.User`, refreshed after the flush.
        """
        session.add(user)
        await session.flush()
        await session.refresh(user)
        return user

    async def Update(self, session: AsyncSession, user: User) -> User:
        """Persist pending changes to an attached user and return it.

        Ports the COUSR02C ``READ ... UPDATE`` followed by ``REWRITE USRSEC``.
        The ``user`` is expected to have been loaded via :meth:`GetByUserId`
        in the same session, so it is attached and its field mutations are
        pending; flushing emits the ``UPDATE`` without committing (the caller
        owns the commit).

        Args:
            session: Active async unit-of-work session.
            user: Attached user entity whose fields have been modified.

        Returns:
            The same :class:`~app.models.user.User` after the flush.
        """
        await session.flush()
        return user

    async def Delete(self, session: AsyncSession, user: User) -> None:
        """Delete an attached user row.

        Ports the COUSR03C ``READ ... UPDATE`` followed by ``DELETE USRSEC``.
        The ``user`` is expected to have been loaded via :meth:`GetByUserId`
        in the same session; it is marked for deletion and flushed without
        committing, leaving the transaction boundary to the caller.

        Args:
            session: Active async unit-of-work session.
            user: Attached user entity to remove.

        Returns:
            ``None``.
        """
        await session.delete(user)
        await session.flush()


__all__ = ["UserRepository", "DEFAULT_PAGE_SIZE", "ADMIN_USER_TYPE"]
