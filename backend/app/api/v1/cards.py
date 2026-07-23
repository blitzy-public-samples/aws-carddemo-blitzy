"""Cards REST router -- modern replacement for the CardDemo credit-card screens.

This thin FastAPI router is the modern successor to THREE legacy CICS online
programs, ported 1:1 (Minimal Change Clause, AAP 0.8.1) onto HTTP/JSON:

    * ``COCRDLIC`` -- "List Credit Cards", transaction ``CCLI``, BMS map
      ``COCRDLI`` (``app/bms/COCRDLI.bms``) -> ``GET /api/v1/cards``.
    * ``COCRDSLC`` -- "Accept and process credit card detail request",
      transaction ``CCDL``, BMS map ``COCRDSL`` (``app/bms/COCRDSL.bms``) ->
      ``GET /api/v1/cards/{cardNum}``.
    * ``COCRDUPC`` -- credit-card detail/update, transaction ``CCUP``, BMS map
      ``COCRDUP`` (``app/bms/COCRDUP.bms``) -> ``PUT /api/v1/cards/{cardNum}``.

Record layout: ``app/cpy/CVACT02Y.cpy`` (``CARD-RECORD``).

Architectural role (AAP 0.4.1 / 0.4.3): this module is the HTTP interface tier
only. It is deliberately THIN -- it holds no business logic and performs no
database access. Every handler resolves its dependencies, delegates to
:class:`app.services.CardService`, and returns the service result. All field
edits, the <=7-rows-per-page browse rule (F-004), the optimistic before-image
update check (AAP 0.7.4), and the admin-vs-regular list scoping live in the
service layer.

Sensitive-data guarantees (AAP 0.7.8 / 0.4.4 -- NON-NEGOTIABLE): the card
verification value (``CARD-CVV-CD``) is NEVER returned, and the card number
(``CARD-NUM``) is always MASKED to its last four digits. These guarantees are
enforced structurally by binding the CVV-free, masking response schemas
(:class:`app.schemas.CardRead` / :class:`app.schemas.CardSummary`) as each
endpoint's ``response_model``; this router never builds a response by hand and
never references a ``cvv`` field.

Authentication: every endpoint depends on
:func:`app.core.dependencies.get_current_user`, the stateless successor to the
CICS ``COCOM01Y`` COMMAREA identity propagation (AAP 0.5.5). Domain errors the
service raises -- :class:`~app.core.exceptions.NotFoundError` (404),
:class:`~app.core.exceptions.DomainValidationError` (400/422), and
:class:`~app.core.exceptions.ConflictError` /
:class:`~app.core.exceptions.OptimisticLockError` (409) -- are intentionally NOT
caught here; they bubble to the application-wide exception handlers registered
in ``app.main``.

Authorization model (AAP 0.4.4 / 0.8.1 -- role-based, NOT per-user ownership):
CardDemo authorization is OPERATOR/ROLE-based, preserved faithfully from the
legacy system. The legacy security record ``CSUSR01Y`` (``app/cpy/CSUSR01Y.cpy``)
carries only an operator id, name, password, and a one-character type
(``SEC-USR-TYPE`` 'A'/'U') -- it has NO account or customer field, so no operator
is bound to a subset of accounts or cards. The ported model therefore
authenticates every caller (``get_current_user``) and gates admin-only surfaces
on ``user_type == 'A'`` (``require_admin``), exactly as AAP 0.4.4 specifies
("role-based rendering ... enforced on both client and server"); it deliberately
does NOT add per-user resource-ownership filtering. Introducing a
user->account/customer ownership binding would invent a relation absent from both
the copybooks and the frozen AAP, breaking the Minimal Change Clause (AAP 0.8.1).
A review finding requesting IDOR-style per-user ownership scoping is thus declined
on AAP grounds and preserved as this documented decision (see resolution report).

PAN-keyed routes (AAP 0.5.5 REST contract): ``GET``/``PUT /cards/{cardNum}`` are
mandated by the frozen AAP endpoint list (CCDL/CCUP), so they are retained rather
than removed. Their PAN exposure is mitigated within the AAP: the card number is
masked to its last four digits in every response (see above), scrubbed from logs
by the application PAN-masking log filter (AAP 0.7.8), and an account-scoped
alternative that keeps the PAN out of the URL -- ``GET``/``PUT
/cards/by-account/{acctId}`` -- is additionally provided.

Ochs conventions (AAP 0.8.2 / 0.8.3): handler names are PascalCase
(``ListCards``, ``GetCard``, ``UpdateCard``); local variables are camelCase
(``cardUpdate``, ``currentUser``); indentation is four spaces; each handler
stays small with at most four parameters. Data-carrying names (``cardNum``,
``card_num``, ...) keep the DTO/JSON contract casing. The ``/api/v1`` version
prefix is NOT hardcoded here -- it is applied when ``app.main`` mounts this
router, which itself owns only the ``/cards`` sub-path.
"""

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.dependencies import get_current_user, get_db
from app.schemas import (
    CardRead,
    CardSummary,
    CardUpdate,
    PaginatedResponse,
)
from app.services import CardListParams, CardService

# The router owns only the resource sub-path (``/cards``); the ``/api/v1``
# prefix is added by ``app.main`` when it includes this router, so no version
# prefix is hardcoded here (Ochs Rule: no hardcoded values).
router = APIRouter(prefix="/cards", tags=["cards"])


@router.get("", response_model=PaginatedResponse[CardSummary])
async def ListCards(
    session: AsyncSession = Depends(get_db),
    params: CardListParams = Depends(),
    currentUser=Depends(get_current_user),
) -> PaginatedResponse[CardSummary]:
    """List credit cards for one browse page (COCRDLIC, tx CCLI).

    Reproduces the COCRDLIC card browse. The browse inputs arrive as query
    parameters bound through :class:`app.services.CardListParams` (which extends
    :class:`app.schemas.common.PaginationParams`): the ``page`` / ``page_size``
    window -- whose default ``page_size`` of seven preserves the legacy
    seven-line browse (F-004), the service capping any larger request to that
    limit -- PLUS the two COCRDLIC search anchors ``acct_id`` and ``card_num``.
    Exposing those two filters is the fix for QA C3: the card-list search boxes
    were previously a silent no-op because the handler bound the filter-free
    ``PaginationParams``, so ``?acct_id=`` / ``?card_num=`` never reached the
    service. A blank filter value is treated as "no filter" by the service.

    Role-based scoping is the SERVICE's responsibility, not the router's: the
    authenticated user is resolved via ``Depends(get_current_user)`` and
    forwarded to :meth:`app.services.CardService.ListCards`, which reproduces
    the COCRDLIC header rule (L4-7) -- an administrator lists ALL cards (honoring
    the optional ``acct_id`` filter), while a regular user is confined to the
    cards of the account in their session context. This handler performs no
    filtering itself.

    Args:
        session: Request-scoped async database session (unit of work).
        params: Browse window and optional search filters (``page`` /
            ``page_size`` / ``acct_id`` / ``card_num``) from the query string;
            ``page_size`` defaults to seven (F-004).
        currentUser: The authenticated user, forwarded to the service so it can
            apply admin-vs-regular list scoping.

    Returns:
        A :class:`app.schemas.common.PaginatedResponse` of
        :class:`app.schemas.CardSummary` rows -- each with ``card_num`` masked
        and no CVV -- plus page metadata.
    """
    return await CardService().ListCards(session, params, currentUser)


# --------------------------------------------------------------------------- #
# By-account card detail/update (QA C1). ROUTE ORDERING IS LOAD-BEARING: these #
# literal ``/cards/by-account/{acctId}`` paths MUST be declared BEFORE the     #
# ``/cards/{cardNum}`` catch-all below, or FastAPI would match "by-account" as #
# a ``cardNum`` path value and never reach these handlers. The modern UI       #
# navigates to a card by its UNMASKED owning-account id because the list masks #
# card_num (AAP 0.7.8) -- a masked PAN can never be a valid ``/{cardNum}`` key #
# (that was the C1 dead-end). The AAP-mandated PAN-keyed endpoints below are   #
# retained unchanged.                                                          #
# --------------------------------------------------------------------------- #
@router.get("/by-account/{acctId}", response_model=CardRead)
async def GetCardByAccount(
    acctId: str,
    session: AsyncSession = Depends(get_db),
    currentUser=Depends(get_current_user),
) -> CardRead:
    """Return one card's detail by its OWNING ACCOUNT id (QA C1).

    The card-list grid displays ``card_num`` MASKED to its last four digits
    (AAP 0.7.8), so the UI cannot address card detail by the real PAN. It
    instead navigates by the unmasked ``acct_id`` shown on each row, and this
    endpoint resolves that account to its card entirely server-side (via the
    ``CARD-ACCT-ID`` alternate index) without the full PAN ever appearing in a
    URL. Delegates to :meth:`app.services.CardService.GetCardByAccount`, which
    edits the account id (blank / non-zero-11-digit), performs the AIX read, and
    raises :class:`app.core.exceptions.NotFoundError` (HTTP 404) when the
    account owns no card.

    Args:
        session: Request-scoped async database session (unit of work).
        acctId: The 11-digit owning-account id taken from the URL path.
        currentUser: The authenticated user; its presence enforces that this
            endpoint is protected by authentication.

    Returns:
        The masked, CVV-free :class:`app.schemas.CardRead` card detail.
    """
    return await CardService().GetCardByAccount(session, acctId)


@router.put("/by-account/{acctId}", response_model=CardRead)
async def UpdateCardByAccount(
    acctId: str,
    cardUpdate: CardUpdate,
    session: AsyncSession = Depends(get_db),
    currentUser=Depends(get_current_user),
) -> CardRead:
    """Update a card's editable fields addressed BY OWNING ACCOUNT (QA C1).

    The companion of :func:`GetCardByAccount`: because the by-account detail
    view returns a MASKED ``card_num``, the client has no real PAN with which to
    call the PAN-keyed ``PUT /cards/{cardNum}``. This endpoint accepts the
    unmasked account id instead and delegates to
    :meth:`app.services.CardService.UpdateCardByAccount`, which resolves the
    account to its real card number server-side and then reuses the full
    COCRDUPC update path (field edits + optimistic before-image check,
    AAP 0.7.4 + commit). The request body is validated by
    :class:`app.schemas.CardUpdate` (editable fields only -- never ``card_num``,
    ``acct_id``, or the CVV).

    Service-raised domain errors bubble to the application handlers: a blank or
    invalid account id / failed field edit / no-change ->
    :class:`app.core.exceptions.DomainValidationError` (400/422), an account
    with no card -> :class:`app.core.exceptions.NotFoundError` (404), and a
    concurrent modification ->
    :class:`app.core.exceptions.OptimisticLockError` /
    :class:`app.core.exceptions.ConflictError` (409).

    Args:
        session: Request-scoped async database session (unit of work).
        acctId: The 11-digit owning-account id taken from the URL path.
        cardUpdate: The validated new field values (editable fields only).
        currentUser: The authenticated user; its presence enforces that this
            endpoint is protected by authentication.

    Returns:
        The masked, CVV-free :class:`app.schemas.CardRead` reflecting the
        committed row.
    """
    return await CardService().UpdateCardByAccount(session, acctId, cardUpdate)


@router.get("/{cardNum}", response_model=CardRead)
async def GetCard(
    cardNum: str,
    session: AsyncSession = Depends(get_db),
    currentUser=Depends(get_current_user),
) -> CardRead:
    """Return one credit card's detail by card number (COCRDSLC, tx CCDL).

    Delegates the keyed read to :meth:`app.services.CardService.GetCard`. The
    ``cardNum`` path segment is always a string (a 16-digit PAN,
    ``CARD-NUM PIC X(16)``) and never an integer, so its significant leading
    zeros are preserved. When no card carries that number the service raises
    :class:`app.core.exceptions.NotFoundError`, which bubbles to the
    application handler as HTTP 404.

    Args:
        session: Request-scoped async database session (unit of work).
        cardNum: The 16-digit card-number key taken from the URL path.
        currentUser: The authenticated user; its presence enforces that this
            endpoint is protected by authentication.

    Returns:
        The masked, CVV-free :class:`app.schemas.CardRead` card detail.
    """
    return await CardService().GetCard(session, cardNum)


@router.put("/{cardNum}", response_model=CardRead)
async def UpdateCard(
    cardNum: str,
    cardUpdate: CardUpdate,
    session: AsyncSession = Depends(get_db),
    currentUser=Depends(get_current_user),
) -> CardRead:
    """Update a credit card's editable fields (COCRDUPC, tx CCUP).

    Delegates to :meth:`app.services.CardService.UpdateCard`, which applies the
    legacy field edits, performs the optimistic before-image concurrency check
    (AAP 0.7.4), and owns the unit-of-work commit. The request body is validated
    by :class:`app.schemas.CardUpdate` (editable fields only: embossed name,
    expiration date, and active status -- never ``card_num``, ``acct_id``, or
    the CVV).

    Service-raised domain errors bubble to the application handlers: a failed
    field edit -> :class:`app.core.exceptions.DomainValidationError` (400/422),
    an unknown card -> :class:`app.core.exceptions.NotFoundError` (404), and a
    concurrent modification -> :class:`app.core.exceptions.OptimisticLockError`
    / :class:`app.core.exceptions.ConflictError` (409).

    Args:
        session: Request-scoped async database session (unit of work).
        cardNum: The 16-digit card-number key taken from the URL path.
        cardUpdate: The validated new field values (editable fields only).
        currentUser: The authenticated user; its presence enforces that this
            endpoint is protected by authentication.

    Returns:
        The masked, CVV-free :class:`app.schemas.CardRead` reflecting the
        committed row.
    """
    return await CardService().UpdateCard(session, cardNum, cardUpdate)
