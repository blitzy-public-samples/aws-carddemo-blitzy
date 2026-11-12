"""Accounts REST router for the CardDemo backend (``app.api.v1.accounts``).

Modern, thin HTTP surface that replaces two legacy CICS online COBOL programs
of the CardDemo application:

    * ``COACTVWC`` -- "Accept and process Account View request"
      (transaction ``CAVW``, BMS map ``COACTVW`` in ``app/bms/COACTVW.bms``)
      -> ``GET /accounts/{acctId}``.
    * ``COACTUPC`` -- "Accept and process ACCOUNT UPDATE"
      (transaction ``CAUP``, BMS map ``COACTUP`` in ``app/bms/COACTUP.bms``)
      -> ``PUT /accounts/{acctId}``.

The underlying record layout is the VSAM account master ``app/cpy/CVACT01Y.cpy``
joined to its owning customer ``app/cpy/CVCUS01Y.cpy`` (resolved through the
card cross-reference), surfaced to clients as the ``AccountDetail`` schema.

Architectural role (AAP 0.4.1 layered architecture): this module is a *thin*
router. It performs no business logic and no direct database access -- the
account-view assembly, the field edits, and the READ-for-UPDATE -> REWRITE
optimistic-lock semantics (AAP 0.7.4) all live in
:class:`app.services.account_service.AccountService`. Each handler resolves its
dependencies (an async unit-of-work session and the authenticated user),
forwards the request to the service, and returns the service's typed
``AccountDetail`` response, so raw ORM rows never leak across the HTTP boundary.

Routing note: the router is mounted with the bare ``/accounts`` prefix; the
``/api/v1`` version segment is applied centrally when ``app.main`` includes the
router (``settings.API_V1_PREFIX``), so it is never hardcoded here. The final
externally visible paths are therefore ``/api/v1/accounts/{acctId}``.

Exception policy: the domain errors raised by the service --
:class:`~app.core.exceptions.NotFoundError` (HTTP 404),
:class:`~app.core.exceptions.DomainValidationError` (HTTP 400/422), and
:class:`~app.core.exceptions.ConflictError` /
:class:`~app.core.exceptions.OptimisticLockError` (HTTP 409) -- are allowed to
propagate to the application-level handlers registered in ``app.main``. This
module never catches them (no local ``try``/``except``, no bare ``except``),
keeping the routing layer purely declarative.

Authorization model (AAP 0.4.4 / 0.8.1 -- role-based, NOT per-user ownership):
authorization here is OPERATOR/ROLE-based, preserved faithfully from the legacy
system. The security record ``CSUSR01Y`` binds an operator only to a type
('A'/'U'), never to a set of accounts/customers, so every caller is
authenticated (``get_current_user``) and admin-only surfaces are gated on
``user_type == 'A'`` (``require_admin``) exactly as AAP 0.4.4 requires, WITHOUT
per-user resource-ownership filtering. Adding a user->account ownership binding
would invent a relation absent from the copybooks and the frozen AAP, breaking
the Minimal Change Clause (AAP 0.8.1); a review finding requesting IDOR-style
ownership scoping is therefore declined on AAP grounds (documented decision --
see ``app.api.v1.cards`` and the resolution report).
"""

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.dependencies import get_db, get_current_user
from app.services import AccountService
from app.schemas import AccountDetail, AccountUpdate

# ---------------------------------------------------------------------------
# Router object. The prefix is the bare resource segment only; the ``/api/v1``
# version prefix is supplied by ``app.main`` at include time (see module
# docstring), never hardcoded here. The ``accounts`` tag groups both endpoints
# together in the generated OpenAPI documentation.
# ---------------------------------------------------------------------------
router = APIRouter(prefix="/accounts", tags=["accounts"])


@router.get("/{acctId}", response_model=AccountDetail)
async def GetAccount(
    acctId: str,
    session: AsyncSession = Depends(get_db),
    currentUser=Depends(get_current_user),
) -> AccountDetail:
    """Return the combined account + customer view (COACTVWC, tx CAVW).

    Modern equivalent of the legacy account-view screen (BMS map ``COACTVW``):
    given an account identifier, return the account master joined to its owning
    customer as a single :class:`AccountDetail` payload. All lookup and view
    assembly is performed by :class:`AccountService`; this handler only forwards
    the request and returns the typed result.

    Args:
        acctId: The 11-digit account identifier taken from the path. Typed
            ``str`` to preserve significant leading zeros -- the VSAM
            ``ACCT-ID PIC 9(11)`` maps to ``VARCHAR(11)`` (AAP 0.1.2 / 0.7) --
            and never an ``int``.
        session: The request-scoped async unit-of-work session provided by
            ``Depends(get_db)``.
        currentUser: The authenticated user injected by
            ``Depends(get_current_user)``. A valid session/JWT is required, so
            this endpoint is protected.

    Returns:
        The :class:`AccountDetail` for ``acctId`` with its embedded customer.

    Raises:
        NotFoundError: Propagated (HTTP 404) when no cross-reference, account,
            or customer row exists for ``acctId``.
    """
    return await AccountService().GetAccount(session, acctId)


@router.put("/{acctId}", response_model=AccountDetail)
async def UpdateAccount(
    acctId: str,
    accountUpdate: AccountUpdate,
    session: AsyncSession = Depends(get_db),
    currentUser=Depends(get_current_user),
) -> AccountDetail:
    """Update an account under the optimistic-lock flow (COACTUPC, tx CAUP).

    Modern equivalent of the legacy account-maintenance screen (BMS map
    ``COACTUP``). The validated :class:`AccountUpdate` body carries only the
    editable account fields; the immutable ``acct_id`` comes from the path and
    never the body. All field edits and the persistence are performed by
    :class:`AccountService`.

    Optimistic-lock conflict (AAP 0.7.4): the legacy ``COACTUPC`` performs a
    READ-for-UPDATE -> REWRITE with a file-status / before-image check to detect
    a concurrent modification. The service reproduces this with a database
    transaction (``SELECT ... FOR UPDATE`` / before-image compare) and raises
    :class:`~app.core.exceptions.ConflictError` /
    :class:`~app.core.exceptions.OptimisticLockError` on a lost update. This
    handler deliberately does not implement or catch that check -- it lets the
    exception propagate to the ``app.main`` handler, which maps it to
    **HTTP 409**.

    Args:
        acctId: The 11-digit account identifier taken from the path (typed
            ``str`` to preserve leading zeros; never an ``int``).
        accountUpdate: The validated partial-update payload. Field lengths,
            numeric ranges, and required-field edits are enforced by the
            :class:`AccountUpdate` Pydantic schema, so no manual re-validation
            is performed here.
        session: The request-scoped async unit-of-work session provided by
            ``Depends(get_db)``; the service owns the ``commit``.
        currentUser: The authenticated user injected by
            ``Depends(get_current_user)``. A valid session/JWT is required, so
            this endpoint is protected.

    Returns:
        The refreshed :class:`AccountDetail` (account + embedded customer).

    Raises:
        NotFoundError: Propagated (HTTP 404) when the account, cross-reference,
            or customer row is missing.
        DomainValidationError: Propagated (HTTP 400/422) when a field edit fails
            or no change is detected relative to the current record.
        ConflictError: Propagated (HTTP 409) on a concurrent-modification /
            optimistic-lock conflict (this includes the more specific
            :class:`~app.core.exceptions.OptimisticLockError`).
    """
    return await AccountService().UpdateAccount(session, acctId, accountUpdate)
