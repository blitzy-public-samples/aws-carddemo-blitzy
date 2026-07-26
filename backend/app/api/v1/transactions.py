"""Transactions API router (``app.api.v1.transactions``).

Modern REST re-expression of THREE legacy CardDemo CICS online COBOL programs,
each ported 1:1 (Minimal Change Clause, AAP 0.8.1) and surfaced here as one thin
HTTP router over the :class:`~app.services.TransactionService` business layer:

    * ``COTRN00C`` -- "List Transactions from TRANSACT file", transaction CT00,
      BMS map ``COTRN00`` (``app/bms/COTRN00.bms``) -> :func:`ListTransactions`.
    * ``COTRN01C`` -- "View a Transaction from TRANSACT file", transaction CT01,
      BMS map ``COTRN01`` (``app/bms/COTRN01.bms``) -> :func:`GetTransaction`.
    * ``COTRN02C`` -- "Add a new Transaction to TRANSACT file", transaction CT02,
      BMS map ``COTRN02`` (``app/bms/COTRN02.bms``) -> :func:`AddTransaction`.

Record layout: ``app/cpy/CVTRA05Y.cpy`` (TRAN-RECORD). The add path reuses the
batch posting validator ``app/cbl/CBTRN02C.cbl`` (``1500-VALIDATE-TRAN`` reject
codes 100-103 and the ``2800-UPDATE-ACCOUNT-REC`` code 109); those rules live in
``TransactionService`` and are never re-implemented here.

Layering (AAP 0.4.1): this module is a THIN router. It performs NO business
logic, NO posting validation, and NO database access -- it binds each request to
the service and returns the response schemas. ``card_num`` is masked in every
response by the schemas themselves (AAP 0.7.8). The identity/role propagation
the legacy ``COCOM01Y`` COMMAREA carried program-to-program is replaced by the
``get_current_user`` dependency (AAP 0.5.5): every endpoint requires an
authenticated user.

Error handling is centralized. Domain exceptions raised by the service --
``NotFoundError`` (-> HTTP 404), ``DomainValidationError`` (-> 400/422), and
``TransactionPostingError`` with its subclasses (-> HTTP 422 carrying the reject
``code`` plus the verbatim ``description``) -- bubble unmodified to the
application-level handlers registered in ``app.main``. This router therefore
contains no ``try`` / ``except`` block.

In-code identifiers follow the Ochs Rule (PascalCase methods, camelCase locals);
the module/file name stays snake_case (AAP 0.8.3).

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

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.dependencies import get_current_user, get_db
from app.schemas import (
    PaginatedResponse,
    PaginationParams,
    TransactionCreate,
    TransactionRead,
    TransactionSummary,
)
from app.services import TransactionService

# The router prefix is the resource collection only ("/transactions"). The
# versioned API segment is applied once by ``app.main`` when this router is
# mounted (through the aggregate v1 router), so it is deliberately not repeated
# or hardcoded here.
router = APIRouter(prefix="/transactions", tags=["transactions"])


@router.get(
    "",
    response_model=PaginatedResponse[TransactionSummary],
    summary="List transactions (COTRN00C / CT00)",
)
async def ListTransactions(
    session: AsyncSession = Depends(get_db),
    params: PaginationParams = Depends(),
    currentUser=Depends(get_current_user),
) -> PaginatedResponse[TransactionSummary]:
    """List posted transactions as one paginated page (COTRN00C, CT00).

    Ports the ``COTRN00C`` transaction-list browse. Pagination via the ``page``
    and ``page_size`` query parameters replaces the 3270 PF7/PF8 scroll, and the
    per-row projection masks ``card_num`` by schema. The endpoint requires an
    authenticated caller: ``currentUser`` is injected so the request carries the
    resolved identity (the stateless successor to the ``COCOM01Y`` COMMAREA).
    Any role-based scoping of the browse belongs to the service layer, so this
    router stays thin and applies no filtering of its own.

    Args:
        session: Request-scoped async database session (dependency-injected).
        params: Page number and page size describing the requested window.
        currentUser: The authenticated user; injected to enforce authentication.

    Returns:
        A :class:`~app.schemas.PaginatedResponse` of
        :class:`~app.schemas.TransactionSummary` rows (``card_num`` masked) with
        accurate page metadata.
    """
    return await TransactionService().ListTransactions(session, params)


@router.get(
    "/{tranId}",
    response_model=TransactionRead,
    summary="View a transaction (COTRN01C / CT01)",
)
async def GetTransaction(
    tranId: str,
    session: AsyncSession = Depends(get_db),
    currentUser=Depends(get_current_user),
) -> TransactionRead:
    """Fetch a single transaction by its identifier (COTRN01C, CT01).

    Ports ``COTRN01C``'s view flow. ``tranId`` is bound as a string because
    ``TRAN-ID`` is ``PIC X(16)`` -- a fixed-width identifier whose leading
    characters are significant -- so it must never be coerced to an integer. A
    missing row raises ``NotFoundError`` in the service, which bubbles to the
    application handler as HTTP 404.

    Args:
        tranId: The 16-character transaction identifier (``TRAN-ID X(16)``).
        session: Request-scoped async database session (dependency-injected).
        currentUser: The authenticated user; injected to enforce authentication.

    Returns:
        The :class:`~app.schemas.TransactionRead` detail for the row
        (``card_num`` masked).
    """
    return await TransactionService().GetTransaction(session, tranId)


@router.post(
    "",
    response_model=TransactionRead,
    status_code=status.HTTP_201_CREATED,
    summary="Add a transaction (COTRN02C / CT02)",
)
async def AddTransaction(
    transactionCreate: TransactionCreate,
    session: AsyncSession = Depends(get_db),
    currentUser=Depends(get_current_user),
) -> TransactionRead:
    """Add a new transaction and post it to the account (COTRN02C, CT02).

    Ports ``COTRN02C``. Every field edit (lengths, numeric ranges, and the
    ``tran_amt`` exact ``Decimal``) is enforced by
    :class:`~app.schemas.TransactionCreate`, so the router performs no
    re-validation. The service assigns the next ``TRAN-ID``, inserts the row,
    and posts the account balance change atomically.

    Posting validation is preserved exactly from the batch validator
    ``CBTRN02C`` (``1500-VALIDATE-TRAN`` and ``2800-UPDATE-ACCOUNT-REC``). A
    failed check raises a ``TransactionPostingError`` subclass carrying the
    legacy reject ``code`` and verbatim UPPERCASE ``description``. The five
    documented rejects (AAP 0.7.3) are, by reject code:

        * 100 -- "INVALID CARD NUMBER FOUND"
        * 101 -- "ACCOUNT RECORD NOT FOUND"
        * 102 -- "OVERLIMIT TRANSACTION"
        * 103 -- "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"
        * 109 -- "ACCOUNT RECORD NOT FOUND"

    The router implements none of these checks (they live in the service and in
    ``app.core.exceptions``); it simply lets the exception bubble to the
    application-level handler in ``app.main``, which maps every
    ``TransactionPostingError`` to HTTP 422 with the code and description in the
    response body.

    Args:
        transactionCreate: The validated add-transaction request DTO.
        session: Request-scoped async database session (dependency-injected).
        currentUser: The authenticated user; injected to enforce authentication.

    Returns:
        The :class:`~app.schemas.TransactionRead` of the newly posted
        transaction (``card_num`` masked). Responds with HTTP 201 Created.
    """
    return await TransactionService().AddTransaction(session, transactionCreate)
