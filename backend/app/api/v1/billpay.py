"""Bill Payment REST router (CardDemo backend, API v1).

Modern replacement for the legacy CICS online program ``COBIL00C``
("Bill Payment - Pay account balance in full"), CICS transaction id ``CB00``,
BMS mapset/map ``COBIL00`` (``app/bms/COBIL00.bms``). The 3270 green-screen
send/receive flow of the original program is re-expressed as two thin HTTP/JSON
endpoints; every business rule is delegated to
:class:`app.services.billpay_service.BillPayService`.

Legacy origin (AAP 0.5.1 / 0.8.1, Minimal Change Clause):
    * ``app/cbl/COBIL00C.cbl`` -- the CICS bill-payment program. Its
      ``PROCESS-ENTER-KEY`` paragraph (read the account, display the balance and
      available credit and -- on a 'Y' confirmation -- post a pay-in-full
      payment transaction and zero the current balance) maps to the two route
      handlers below.

Architecture (AAP 0.4.1 / 0.4.3): this module is a THIN router. It holds no
business logic and performs no database access -- it re-validates nothing
beyond the Pydantic request DTO and simply forwards each call to
``BillPayService``. The available-credit computation, the pay-in-full behavior,
and the payment-transaction insert all live in the service layer.

Business rule F-006 (AAP 0.8.1) -- available credit::

    available_credit = credit_limit - current_balance

is computed by the service, never here. It is intentionally DISTINCT from the
batch posting over-limit rule (reject code 102, AAP 0.7.3), which instead uses
``curr_cyc_credit - curr_cyc_debit + tran_amt`` compared against the credit
limit. The two rules are never conflated; this router performs neither
computation -- it forwards the request.

Exception policy (AAP 0.8.1): the domain errors the service raises --
:class:`~app.core.exceptions.NotFoundError` (HTTP 404),
:class:`~app.core.exceptions.DomainValidationError` (HTTP 400/422) and
:class:`~app.core.exceptions.ConflictError` (HTTP 409) -- are allowed to
propagate to the application-level exception handlers registered in
``app.main``. This router contains no ``try``/``except`` block and no bare
``except``.

Naming conventions (Ochs Rule, AAP 0.8.2 / 0.8.3): the module/file name is
snake_case; the route-handler methods are PascalCase (``GetBillPayInfo``,
``PayBill``); local and parameter identifiers are camelCase (``billPayRequest``,
``currentUser``, ``acctId``); module constants are ALL_UPPERCASE. The ``router``
object and the injected dependency callables (``get_db``, ``get_current_user``)
keep their framework / AAP-contract names.

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

# Ported from CICS online program COBIL00C (app/cbl/COBIL00C.cbl), tx CB00,
# BMS map COBIL00. Thin router only: the F-006 available-credit rule and the
# pay-in-full posting live in BillPayService and are preserved verbatim under
# the Minimal Change Clause (AAP 0.8.1); F-006 is deliberately NOT conflated
# with the batch posting over-limit rule (reject code 102, AAP 0.7.3).

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.dependencies import get_db, get_current_user
from app.schemas import BillPayRequest, BillPayResponse
from app.services import BillPayService

# HTTP status returned by the pay-bill action. Paying a bill mutates an existing
# account (it posts a payment and updates the balance); it does NOT create a new
# REST resource, so the collection POST returns 200 OK rather than 201 Created.
# Held as a named constant (Ochs Rule 0.8.2) so the intent is self-documenting
# and the value is not a bare magic number at the decorator.
PAYMENT_OK_STATUS = 200

router = APIRouter(prefix="/billpay", tags=["billpay"])


@router.get("/{acctId}", response_model=BillPayResponse)
async def GetBillPayInfo(
    acctId: str,
    session: AsyncSession = Depends(get_db),
    currentUser=Depends(get_current_user),
) -> BillPayResponse:
    """Return an account's pre-payment bill-pay view (COBIL00C initial screen).

    Provides the read-only figures the legacy screen showed before a payment:
    the current balance, the credit limit, and the F-006 available credit
    (``credit_limit - current_balance``, computed in the service). This is the
    modern equivalent of the COBIL00C account read (``READ-ACCTDAT-FILE``) that
    populated the bill-pay map. The router forwards to the service and performs
    no computation of its own.

    Args:
        acctId: The 11-digit account identifier taken from the path. Kept as a
            string (``VARCHAR(11)``) so significant leading zeros are preserved;
            it is never parsed to an integer.
        session: The request-scoped async database session (injected by
            :func:`app.core.dependencies.get_db`).
        currentUser: The authenticated user (injected by
            :func:`app.core.dependencies.get_current_user`); its presence gates
            the endpoint so only authenticated callers may reach it.

    Returns:
        A :class:`~app.schemas.billpay.BillPayResponse` carrying the account's
        current balance, credit limit, and F-006 available credit.

    Raises:
        NotFoundError: If no account exists for ``acctId`` -- mapped to HTTP 404
            by the application exception handlers in ``app.main``.
    """
    return await BillPayService().GetBillPayInfo(session, acctId)


@router.post("", response_model=BillPayResponse, status_code=PAYMENT_OK_STATUS)
async def PayBill(
    billPayRequest: BillPayRequest,
    session: AsyncSession = Depends(get_db),
    currentUser=Depends(get_current_user),
) -> BillPayResponse:
    """Post a pay-in-full bill payment (COBIL00C, tx CB00).

    Forwards the validated request to
    :meth:`app.services.billpay_service.BillPayService.PayBill`, which -- on a
    'Y' confirmation -- posts the payment transaction and zeroes the current
    balance in one committed unit of work (the COBIL00C ``PROCESS-ENTER-KEY``
    confirm path). Returns HTTP 200: paying a bill is an action against an
    existing account, not the creation of a new REST resource, so 201 is
    deliberately not used.

    Business rule F-006 (AAP 0.8.1): the available credit carried on the
    response is ``credit_limit - current_balance``, computed by the service.
    This is intentionally DISTINCT from the batch posting over-limit rule
    (reject code 102, AAP 0.7.3), which uses
    ``curr_cyc_credit - curr_cyc_debit + tran_amt`` against the credit limit.
    The router conflates neither rule and computes neither value; it forwards
    the request. Input validation is performed by the
    :class:`~app.schemas.billpay.BillPayRequest` DTO and is not repeated here.

    Args:
        billPayRequest: The validated bill-payment request DTO (account id and
            confirmation flag only). COBIL00C pays the full current balance, so
            there is no partial-payment field; unexpected fields (for example a
            ``payment_amount``) are rejected with HTTP 422 (``extra="forbid"``).
        session: The request-scoped async database session (injected by
            :func:`app.core.dependencies.get_db`).
        currentUser: The authenticated user (injected by
            :func:`app.core.dependencies.get_current_user`); its presence gates
            the endpoint so only authenticated callers may reach it.

    Returns:
        A :class:`~app.schemas.billpay.BillPayResponse` carrying the resulting
        balance, credit limit, F-006 available credit, applied payment amount,
        the posted transaction id, and a confirmation message.

    Raises:
        NotFoundError: If the account (or the card cross-reference used to post
            the payment) does not exist -- HTTP 404.
        DomainValidationError: If a ported field/business edit fails, for
            example an invalid confirmation flag or nothing to pay -- HTTP
            400/422.
    """
    return await BillPayService().PayBill(session, billPayRequest)
