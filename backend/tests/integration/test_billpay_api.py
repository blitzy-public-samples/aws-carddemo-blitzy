# Integration tests for the bill-payment API.
# Ported from legacy CICS online program COBIL00C (transaction CB00).
# Endpoints: GET /api/v1/billpay/{acctId}, POST /api/v1/billpay (AAP §0.5.5).
# Verifies F-006 available_credit = credit_limit - curr_bal (exact Decimal),
# pay-in-full zeroes curr_bal and posts a TYPE '02' 'BILL PAYMENT - ONLINE'
# transaction, and the verbatim confirm/empty-balance guards. Money is Decimal.
"""End-to-end integration tests for the CardDemo bill-payment API.

These tests exercise the two HTTP endpoints that replace the legacy CICS
online program ``COBIL00C`` (transaction ``CB00``, BMS map ``COBIL00``):

* ``GET  /api/v1/billpay/{acctId}`` -- the read-only "initial screen" view that
  surfaces the current balance, the credit limit, and the F-006 available
  credit (``credit_limit - curr_bal``).
* ``POST /api/v1/billpay``          -- the confirm/pay action. On a ``'Y'``
  confirmation it posts a pay-in-full payment transaction (``TRAN-TYPE-CD`` =
  ``'02'``, ``TRAN-DESC`` = ``'BILL PAYMENT - ONLINE'``) and zeroes the
  balance; on ``'N'`` it posts nothing and returns the "confirm to pay" prompt.

The suite is intentionally a golden-master parity harness rather than a mock
test: it runs against the PostgreSQL-backed test database (Decimal fidelity is
mandatory for currency, AAP §0.7.1) and consumes the shared fixtures published
by the parent ``backend/tests/conftest.py`` -- there is deliberately NO local
``conftest.py``. Because the ``client`` fixture overrides ``get_db`` to yield
the very same ``db_session`` a test holds, every mutation performed by an
endpoint (the posted transaction, the zeroed balance) is directly verifiable
through ``db_session`` after the call returns.

Money is always asserted as an exact :class:`decimal.Decimal` (never ``float``/
approximate): each JSON money value is routed through :func:`ToDecimal`, which
re-parses it via ``Decimal(str(...))`` so no binary floating-point rounding can
enter a monetary comparison.

Naming conventions (Ochs Rule, AAP §0.8.2 / §0.8.3): the module/file name is
snake_case; the module-level helper is PascalCase (:func:`ToDecimal`); local and
parameter identifiers are camelCase (``payload``, ``responseBody``, ``tranId``);
module constants are ALL_UPPERCASE. The pytest fixture parameter names
(``admin_client``, ``client``, ``seed_data``, ``db_session``) keep their
snake_case contract names because pytest resolves fixtures by name.
"""

from __future__ import annotations

from decimal import Decimal

import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.models.account import Account
from app.models.transaction import Transaction

# ---------------------------------------------------------------------------
# Module constants (ALL_UPPERCASE per the Ochs Rule §0.8.2).
# ---------------------------------------------------------------------------

# Fully-qualified bill-pay resource path: the version-1 API prefix (mounted once
# by app.main) plus the billpay sub-router's own prefix. Built from settings so
# the API version prefix is never hardcoded here (Ochs Rule §0.8.2).
BILLPAY_URL = f"{settings.API_V1_PREFIX}/billpay"

# Golden-master account under test. app/data/ASCII/acctdata.txt row 1 decodes
# (signed zoned decimal, AAP §0.7.1) to curr_bal 194.00 and credit_limit
# 2020.00; it has a card cross-reference (card 9680294154603697) so the
# pay-in-full path can resolve a card to post the payment against.
SEED_ACCT_ID = "00000000001"

# An 11-digit account id that is never seeded, used to assert the 404 lookup.
MISSING_ACCT_ID = "99999999999"

# VERIFIED golden-master figures for SEED_ACCT_ID (asserted as EXACT Decimal).
EXPECTED_CURR_BAL = Decimal("194.00")
EXPECTED_CREDIT_LIMIT = Decimal("2020.00")
# F-006: available_credit = credit_limit - curr_bal = 2020.00 - 194.00.
EXPECTED_AVAILABLE = Decimal("1826.00")
# The balance after a successful pay-in-full (COBIL00C zeroes the balance).
ZERO_BALANCE = Decimal("0.00")

# Verbatim COBIL00C payment-transaction literals (AAP §0.7 / §0.8.1).
PAYMENT_TRAN_TYPE = "02"                    # MOVE '02' TO TRAN-TYPE-CD          [L220]
PAYMENT_TRAN_DESC = "BILL PAYMENT - ONLINE"  # MOVE '...' TO TRAN-DESC           [L223]

# TRAN-ID is PIC X(16): the posted transaction id is a zero-padded 16-char string.
TRAN_ID_LENGTH = 16

# HTTP status codes asserted PRECISELY (never a broad "not 2xx").
HTTP_OK = 200                # GET view and POST pay-bill both return 200 (POST is NOT 201).
HTTP_UNAUTHORIZED = 401      # Unauthenticated access is rejected by get_current_user.
HTTP_NOT_FOUND = 404         # A missing account (or card xref) maps to NotFoundError -> 404.
# A ported field/business edit failure maps to DomainValidationError (422) or is
# rejected by the Pydantic request DTO (422); 400 is accepted for parity with
# the AAP contract, which documents the validation family as 400/422.
VALIDATION_ERROR_STATUSES = (400, 422)


def ToDecimal(jsonValue: object) -> Decimal:
    """Convert a JSON-decoded money value to an exact :class:`Decimal`.

    Every monetary field in a :class:`~app.schemas.billpay.BillPayResponse` is
    compared as an exact ``Decimal`` (AAP §0.7.1). Routing the raw JSON value
    (a string or, depending on the serializer, a number) through
    ``Decimal(str(...))`` guarantees the comparison never passes through a
    binary ``float``. ``Decimal`` equality is by numeric value, so a serialized
    ``"194.0"`` still compares equal to ``Decimal("194.00")``.

    Args:
        jsonValue: A money value taken straight from a decoded JSON response
            body or an ORM attribute.

    Returns:
        The value as an exact :class:`decimal.Decimal`.
    """
    return Decimal(str(jsonValue))


# ===========================================================================
# Phase 2 -- GET bill-pay info (F-006 available credit, exact Decimal).
# ===========================================================================


@pytest.mark.asyncio
async def test_get_billpay_info_available_credit(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """GET returns the balance, limit, and F-006 available credit exactly.

    Reproduces the COBIL00C initial-screen read (``READ-ACCTDAT-FILE`` populating
    the bill-pay map): the response must carry the golden-master balance
    (194.00), credit limit (2020.00), and the F-006 available credit
    (2020.00 - 194.00 = 1826.00), each asserted as an exact ``Decimal``.

    Args:
        admin_client: The authenticated (admin) httpx ASGI client.
        seed_data: Golden-master seeder (loads the account under test).
    """
    response = await admin_client.get(f"{BILLPAY_URL}/{SEED_ACCT_ID}")

    assert response.status_code == HTTP_OK
    responseBody = response.json()
    assert responseBody["acct_id"] == SEED_ACCT_ID
    assert ToDecimal(responseBody["curr_bal"]) == EXPECTED_CURR_BAL
    assert ToDecimal(responseBody["credit_limit"]) == EXPECTED_CREDIT_LIMIT
    # F-006: available_credit = credit_limit - curr_bal = 2020.00 - 194.00.
    assert ToDecimal(responseBody["available_credit"]) == EXPECTED_AVAILABLE


@pytest.mark.asyncio
async def test_get_billpay_info_not_found(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """GET on an unseeded account id returns HTTP 404 (NotFoundError).

    The COBIL00C account read that fails ``INVALID KEY`` maps to the service
    ``NotFoundError`` and, via the app exception handlers, to HTTP 404.

    Args:
        admin_client: The authenticated (admin) httpx ASGI client.
        seed_data: Golden-master seeder (present so only the target id is absent).
    """
    response = await admin_client.get(f"{BILLPAY_URL}/{MISSING_ACCT_ID}")

    assert response.status_code == HTTP_NOT_FOUND


@pytest.mark.asyncio
async def test_billpay_requires_auth(client: AsyncClient) -> None:
    """An unauthenticated GET is rejected with HTTP 401.

    Both bill-pay endpoints depend on ``get_current_user``; the unauthenticated
    ``client`` fixture carries no identity, so the dependency rejects the call
    with HTTP 401 before any account lookup occurs.

    Args:
        client: The unauthenticated httpx ASGI client (no identity override).
    """
    response = await client.get(f"{BILLPAY_URL}/{SEED_ACCT_ID}")

    assert response.status_code == HTTP_UNAUTHORIZED


# ===========================================================================
# Phase 3 -- POST pay-in-full (200, zeroes balance, posts a TYPE '02' tran).
# ===========================================================================


@pytest.mark.asyncio
async def test_paybill_confirmed_pays_in_full(
    admin_client: AsyncClient,
    seed_data: None,
    db_session: AsyncSession,
) -> None:
    """A confirmed POST pays the full balance and posts a TYPE '02' transaction.

    Reproduces the COBIL00C confirmed path (``PROCESS-ENTER-KEY`` with
    ``CONFIRMI='Y'``): the endpoint posts a ``'BILL PAYMENT - ONLINE'`` payment
    for the full current balance, subtracts it from the balance (leaving 0.00),
    and returns HTTP 200 (NOT 201 -- paying a bill acts on an existing account,
    it does not create a REST resource). The response figures, the posted
    transaction, and the zeroed account row are all asserted as exact
    ``Decimal`` / literal values.

    Args:
        admin_client: The authenticated (admin) httpx ASGI client.
        seed_data: Golden-master seeder (account + card cross-reference).
        db_session: The shared async session used to verify the posted
            transaction and the zeroed balance directly in the database.
    """
    payload = {"acct_id": SEED_ACCT_ID, "confirm": "Y"}

    response = await admin_client.post(BILLPAY_URL, json=payload)

    # POST pay-bill returns 200, never 201.
    assert response.status_code == HTTP_OK
    responseBody = response.json()
    # Balance is zeroed; available credit becomes the full limit (2020.00 - 0.00);
    # the amount applied equals the pre-payment balance (194.00 paid in full).
    assert ToDecimal(responseBody["curr_bal"]) == ZERO_BALANCE
    assert ToDecimal(responseBody["available_credit"]) == EXPECTED_CREDIT_LIMIT
    assert ToDecimal(responseBody["payment_amount"]) == EXPECTED_CURR_BAL

    # The posted transaction id is a non-empty, 16-character string (TRAN-ID X(16)).
    tranId = responseBody["tran_id"]
    assert isinstance(tranId, str)
    assert tranId.strip() != ""
    assert len(tranId) == TRAN_ID_LENGTH

    # Verify the posted transaction directly in the database (shared session).
    postedTran = await db_session.get(Transaction, tranId)
    assert postedTran is not None
    assert postedTran.tran_type_cd == PAYMENT_TRAN_TYPE
    assert postedTran.tran_desc == PAYMENT_TRAN_DESC
    assert ToDecimal(postedTran.tran_amt) == EXPECTED_CURR_BAL

    # Verify the account balance was zeroed by the payment (COMPUTE ... - TRAN-AMT).
    updatedAccount = await db_session.get(Account, SEED_ACCT_ID)
    assert updatedAccount is not None
    assert ToDecimal(updatedAccount.curr_bal) == ZERO_BALANCE


# ===========================================================================
# Phase 4 -- Guards (verbatim messages / precise status; no posting on reject).
# ===========================================================================


@pytest.mark.asyncio
async def test_paybill_unconfirmed_no_posting(
    admin_client: AsyncClient,
    seed_data: None,
    db_session: AsyncSession,
) -> None:
    """An 'N' confirmation returns the "confirm to pay" prompt and posts nothing.

    Reproduces the COBIL00C unconfirmed branch: when ``CONFIRMI`` is not ``'Y'``
    the program surfaces ``'Confirm to make a bill payment...'`` and posts no
    transaction. The endpoint returns HTTP 200 with that message, a ``None``
    transaction id, and the account balance left unchanged (verified directly).

    Args:
        admin_client: The authenticated (admin) httpx ASGI client.
        seed_data: Golden-master seeder (loads the account under test).
        db_session: The shared async session used to confirm no mutation
            occurred (the balance is still 194.00).
    """
    payload = {"acct_id": SEED_ACCT_ID, "confirm": "N"}

    response = await admin_client.post(BILLPAY_URL, json=payload)

    assert response.status_code == HTTP_OK
    responseBody = response.json()
    # Verbatim COBIL00C prompt (L237); asserted as a substring of the body.
    assert "Confirm to make a bill payment" in str(responseBody)
    # No transaction was posted, so the response carries no transaction id.
    assert responseBody.get("tran_id") in (None, "")

    # The balance must be UNCHANGED (still the seeded 194.00) -- nothing posted.
    unchangedAccount = await db_session.get(Account, SEED_ACCT_ID)
    assert unchangedAccount is not None
    assert ToDecimal(unchangedAccount.curr_bal) == EXPECTED_CURR_BAL


@pytest.mark.asyncio
async def test_paybill_nothing_to_pay(
    admin_client: AsyncClient,
    seed_data: None,
    db_session: AsyncSession,
) -> None:
    """A confirmed payment on a zero-balance account is rejected ("nothing to pay").

    Reproduces the COBIL00C guard: when ``ACCT-CURR-BAL <= ZEROS`` the program
    surfaces ``'You have nothing to pay...'``. The zero balance is arranged
    deterministically by setting the seeded account's ``curr_bal`` to 0.00 on
    the shared session before the call, so the service sees a paid-off account
    and rejects the payment with a 400/422 validation status.

    Args:
        admin_client: The authenticated (admin) httpx ASGI client.
        seed_data: Golden-master seeder (loads the account under test).
        db_session: The shared async session used to force the zero balance.
    """
    # Force a paid-off balance on the shared session so the endpoint (which reads
    # the same session) sees curr_bal == 0.00 and rejects "nothing to pay".
    seededAccount = await db_session.get(Account, SEED_ACCT_ID)
    assert seededAccount is not None
    seededAccount.curr_bal = ZERO_BALANCE
    await db_session.flush()

    payload = {"acct_id": SEED_ACCT_ID, "confirm": "Y"}
    response = await admin_client.post(BILLPAY_URL, json=payload)

    assert response.status_code in VALIDATION_ERROR_STATUSES
    # Verbatim COBIL00C message (L201): 'You have nothing to pay...'.
    assert "nothing to pay" in str(response.json())


@pytest.mark.asyncio
async def test_paybill_invalid_confirm(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """A confirmation flag other than 'Y'/'N' is rejected with a 400/422 status.

    COBIL00C surfaces ``'Invalid value. Valid values are (Y/N)...'`` from its
    service-layer edit; for a POST body the Pydantic ``confirm`` edit runs first
    and surfaces the equivalent "... must be Y or N." wording. Either layer's
    message pins the same Y/N rule, so the assertion accepts whichever is
    surfaced (the AAP marks this message "where surfaced").

    Args:
        admin_client: The authenticated (admin) httpx ASGI client.
        seed_data: Golden-master seeder (present per the AAP fixture list).
    """
    payload = {"acct_id": SEED_ACCT_ID, "confirm": "X"}

    response = await admin_client.post(BILLPAY_URL, json=payload)

    assert response.status_code in VALIDATION_ERROR_STATUSES
    bodyText = str(response.json()).lower()
    assert "valid values are (y/n)" in bodyText or "y or n" in bodyText


@pytest.mark.asyncio
async def test_paybill_empty_acct(admin_client: AsyncClient) -> None:
    """An empty account id is rejected with a 400/422 status.

    COBIL00C surfaces ``'Acct ID can NOT be empty...'`` from its service-layer
    guard; for a POST body the Pydantic ``acct_id`` length edit runs first and
    surfaces the field name (``acct_id``). Either surfacing pins the empty
    account rule, so the assertion accepts whichever is present (the AAP marks
    this message "where surfaced").

    Args:
        admin_client: The authenticated (admin) httpx ASGI client.
    """
    payload = {"acct_id": "", "confirm": "Y"}

    response = await admin_client.post(BILLPAY_URL, json=payload)

    assert response.status_code in VALIDATION_ERROR_STATUSES
    bodyText = str(response.json()).lower()
    assert "acct id" in bodyText or "acct_id" in bodyText


@pytest.mark.asyncio
async def test_paybill_account_not_found(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """A confirmed payment against an unseeded account returns HTTP 404.

    A well-formed but non-existent account id passes request validation, reaches
    the service, and fails the account lookup -- the COBIL00C ``INVALID KEY``
    path -- surfacing as ``NotFoundError`` -> HTTP 404.

    Args:
        admin_client: The authenticated (admin) httpx ASGI client.
        seed_data: Golden-master seeder (present so only the target id is absent).
    """
    payload = {"acct_id": MISSING_ACCT_ID, "confirm": "Y"}

    response = await admin_client.post(BILLPAY_URL, json=payload)

    assert response.status_code == HTTP_NOT_FOUND


# ===========================================================================
# Phase 5 -- F-006 available credit is NOT the posting over-limit formula.
# ===========================================================================


@pytest.mark.asyncio
async def test_available_credit_uses_f006_formula(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """Available credit is credit_limit - curr_bal (F-006), not the 102 rule.

    Documentation guard (AAP §0.7.3): bill payment computes available credit as
    ``credit_limit - curr_bal`` (F-006), which is intentionally DISTINCT from
    the batch posting over-limit rule (reject code 102) that uses
    ``curr_cyc_credit - curr_cyc_debit + tran_amt`` against the credit limit.
    For the seeded account F-006 yields 2020.00 - 194.00 = 1826.00; the test
    computes that expectation from the constants and asserts the GET response
    matches it exactly.

    Args:
        admin_client: The authenticated (admin) httpx ASGI client.
        seed_data: Golden-master seeder (loads the account under test).
    """
    # F-006 arithmetic, computed here so the rule is explicit and self-checking.
    computedAvailable = EXPECTED_CREDIT_LIMIT - EXPECTED_CURR_BAL
    assert computedAvailable == EXPECTED_AVAILABLE

    response = await admin_client.get(f"{BILLPAY_URL}/{SEED_ACCT_ID}")

    assert response.status_code == HTTP_OK
    responseBody = response.json()
    availableCredit = ToDecimal(responseBody["available_credit"])
    assert availableCredit == computedAvailable
    assert availableCredit == EXPECTED_AVAILABLE


# ===========================================================================
# Phase 6 -- M-09 contract: no partial-payment field; replay is rejected.
# ===========================================================================


@pytest.mark.asyncio
async def test_paybill_rejects_payment_amount_field(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """POSTing a ``payment_amount`` is rejected at the API boundary (M-09).

    COBIL00C pays the FULL current balance and has no partial-payment field, so
    the request schema (``extra="forbid"``) rejects any ``payment_amount``
    rather than silently accepting and ignoring it. The rejection is a 400/422
    validation status and identifies the offending field.

    Args:
        admin_client: The authenticated (admin) httpx ASGI client.
        seed_data: Golden-master seeder (present per the AAP fixture list).
    """
    payload = {"acct_id": SEED_ACCT_ID, "confirm": "Y", "payment_amount": "50.00"}

    response = await admin_client.post(BILLPAY_URL, json=payload)

    assert response.status_code in VALIDATION_ERROR_STATUSES
    assert "payment_amount" in str(response.json()).lower()


@pytest.mark.asyncio
async def test_paybill_replay_rejected(
    admin_client: AsyncClient,
    seed_data: None,
    db_session: AsyncSession,
) -> None:
    """A replayed confirmed payment is rejected: the balance is paid only once.

    Reproduces the COBIL00C replay guard end-to-end (M-09): the first confirmed
    POST pays the full balance (HTTP 200, balance -> 0.00); an immediate,
    identical POST re-reads the now-zero balance and is rejected with the
    verbatim "nothing to pay" message and a 400/422 status. Exactly ONE new
    transaction is posted across the two submissions (measured as the id delta,
    so it is robust to whatever the golden-master seed already loaded) and the
    balance is exactly zero -- the pay-in-full contract is inherently
    idempotent, so a duplicate submission can never drive the balance negative.

    Args:
        admin_client: The authenticated (admin) httpx ASGI client.
        seed_data: Golden-master seeder (account + card cross-reference).
        db_session: The shared async session used to measure the posted-
            transaction delta and confirm the settled balance directly.
    """
    payload = {"acct_id": SEED_ACCT_ID, "confirm": "Y"}

    # Baseline set of transaction ids before any payment is posted.
    beforeRows = await db_session.execute(select(Transaction.tran_id))
    beforeIds = set(beforeRows.scalars().all())

    firstResponse = await admin_client.post(BILLPAY_URL, json=payload)
    assert firstResponse.status_code == HTTP_OK
    assert ToDecimal(firstResponse.json()["curr_bal"]) == ZERO_BALANCE

    secondResponse = await admin_client.post(BILLPAY_URL, json=payload)
    assert secondResponse.status_code in VALIDATION_ERROR_STATUSES
    assert "nothing to pay" in str(secondResponse.json()).lower()

    # Exactly ONE payment transaction was posted despite two submissions, and
    # the balance settled at zero (never driven negative by the duplicate).
    afterRows = await db_session.execute(select(Transaction.tran_id))
    newIds = set(afterRows.scalars().all()) - beforeIds
    assert len(newIds) == 1
    settledAccount = await db_session.get(Account, SEED_ACCT_ID)
    assert ToDecimal(settledAccount.curr_bal) == ZERO_BALANCE
