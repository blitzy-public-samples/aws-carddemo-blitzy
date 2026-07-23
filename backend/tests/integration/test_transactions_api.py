# Integration tests for the transactions API.
# Ported from legacy CICS online programs COTRN00C (list, CT00), COTRN01C
# (view, CT01), COTRN02C (add, CT02); add-transaction posting validations reuse
# the batch posting validator CBTRN02C (1500-VALIDATE-TRAN / 2800-UPDATE-ACCOUNT-REC).
# Endpoints: GET /api/v1/transactions, GET /api/v1/transactions/{tranId},
# POST /api/v1/transactions (AAP §0.5.5). Verifies pagination, posting reject
# codes 100/101/102/103/109 (exact strings; 101 vs 109 DISTINCT, §0.7.3), and
# card_num masking. Record layouts CVTRA05Y (posted) / CVTRA06Y (daily).
"""Integration tests for the CardDemo transactions REST API.

These tests exercise the three transaction endpoints end-to-end through the
in-process httpx ASGI client wired to ``app.main:app`` (see ``tests/conftest``),
against a real PostgreSQL-backed test database so that ``Decimal``/``NUMERIC``
precision, foreign keys and ``TIMESTAMPTZ`` behave exactly as in production:

* ``GET /api/v1/transactions`` -- paginated browse (COTRN00C / CT00).
* ``GET /api/v1/transactions/{tranId}`` -- single-row view (COTRN01C / CT01).
* ``POST /api/v1/transactions`` -- add + post (COTRN02C / CT02), whose posting
  validations reuse the batch validator ``CBTRN02C`` (reject codes 100-103 and
  the additional 109).

Fidelity contracts pinned here (AAP §0.7):

* Money is asserted as an EXACT :class:`~decimal.Decimal` via
  ``Decimal(str(...))`` -- never a float/approx comparison (§0.7.1).
* ``card_num`` is masked to its last four digits in every response and ``cvv``
  is never present (§0.7.8).
* Posting rejections carry the verbatim UPPERCASE reject ``description`` and the
  numeric ``code``; codes 101 and 109 share the description text but are
  DISTINCT numeric codes, so the numeric code (not the text) tells them apart
  (§0.7.3).

Architecture note that shapes several assertions below: the online add path
(``COTRN02C``) resolves the owning card/account FIRST (VALIDATE-INPUT-KEY-FIELDS)
and only then runs the ``CBTRN02C`` posting validations. Combined with the
database foreign keys (``card_xref`` -> ``cards``/``accounts``,
``transactions`` -> ``cards``), a request can reach the posting stage only with a
card and account that already exist. Therefore the over-limit (102) and
expiration (103) rejects are reproducible end-to-end over HTTP, while the
card-lookup (100), account-lookup (101) and account-update (109) rejects cannot
be provoked through the HTTP surface with referentially-valid data -- a missing
card/account is caught earlier as an HTTP 404 key-field edit. For 100/101/109
the tests therefore assert BOTH the real HTTP resolve behavior AND the exact
posting-reject catalog (the ``code``/``description`` that ``app.main`` serializes
into the 422 body for reconciliation against the batch ``DALYREJS`` record),
which is the faithful, deterministic way to pin those codes.

Ochs Rule (AAP §0.8.2/§0.8.3): the module name is snake_case; in-code helpers
use PascalCase (:func:`FindPostingCode`, :func:`BuildTransactionPayload`), local
variables camelCase (``responseBody``, ``tranId``, ``createPayload``) and module
constants ALL_UPPERCASE. All monetary assertions use exact ``Decimal``.
"""

from __future__ import annotations

from decimal import Decimal

import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

# app.core.exceptions is a declared dependency: the posting-reject exception
# family is the single source of truth for the reject code<->description pairing
# (CBTRN02C) that app.main serializes into the HTTP 422 body. Importing the
# concrete subclasses lets the 100/101/109 tests assert that catalog exactly.
from app.core.config import settings
from app.core.exceptions import (
    AccountNotFoundError,
    AccountUpdateFailedError,
    InvalidCardNumberError,
)
from app.models.account import Account

# ---------------------------------------------------------------------------
# Module constants (ALL_UPPERCASE per the Ochs Rule §0.8.2).
# ---------------------------------------------------------------------------

# Fully-qualified transactions collection path: the version-1 API prefix
# (mounted once by app.main) plus the router's own resource path. Built from
# settings so the API version prefix is never hardcoded here.
TRANSACTIONS_URL = f"{settings.API_V1_PREFIX}/transactions"

# Precise HTTP statuses asserted by the tests (never a broad "not 2xx" check).
HTTP_OK = 200
HTTP_CREATED = 201
HTTP_UNAUTHORIZED = 401
HTTP_NOT_FOUND = 404
HTTP_UNPROCESSABLE = 422

# Posting reject reason codes (CBTRN02C 1500-VALIDATE-TRAN / 2800-UPDATE-ACCOUNT-REC).
# 101 and 109 are DISTINCT codes that share the SAME description text (§0.7.3).
CODE_INVALID_CARD = 100
CODE_ACCT_NOT_FOUND = 101
CODE_OVERLIMIT = 102
CODE_EXPIRED = 103
CODE_UPDATE_FAILED = 109

# Verbatim UPPERCASE reject descriptions (character-for-character from CBTRN02C
# L386/L398/L411/L418/L557; never reword, re-case, or re-punctuate).
DESC_INVALID_CARD = "INVALID CARD NUMBER FOUND"
DESC_ACCT_NOT_FOUND = "ACCOUNT RECORD NOT FOUND"
DESC_OVERLIMIT = "OVERLIMIT TRANSACTION"
DESC_EXPIRED = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"

# Known-good golden-master seed anchors (from app/data/ASCII/cardxref.txt +
# acctdata.txt). The valid card resolves to a non-expired-window account used
# for the success path; the over-limit account has a low credit limit and zero
# cycle credit/debit, and expires 2025-05-20.
VALID_CARD_NUM = "0500024453765740"       # -> acct 00000000050 (expires 2023-03-09)
VALID_ACCT_ID = "00000000050"             # curr_bal 492.00, credit_limit 6169.00
OVERLIMIT_ACCT_ID = "00000000001"         # credit_limit 2020.00, cyc 0/0, exp 2025-05-20

# An account id that exists in NEITHER the accounts table NOR the cross-reference
# (only 00000000001..00000000050 are seeded), used to exercise the real "account
# id not found" key-field edit (HTTP 404) of the online add path.
UNKNOWN_ACCT_ID = "99999999999"
# A syntactically valid 16-digit card number with no cross-reference row.
UNKNOWN_CARD_NUM = "9999999999999999"
# A 16-digit transaction id that is never assigned by the seed data.
MISSING_TRAN_ID = "9999999999999999"

# Number of leading digits masked on a 16-digit card number (12 stars + last 4).
MASKED_PREFIX = "*" * 12

# Amount posted by the success path and the exact expected balance delta.
SUCCESS_TRAN_AMT = "10.00"
SUCCESS_BALANCE_DELTA = Decimal("10.00")
# An amount large enough to exceed OVERLIMIT_ACCT_ID's 2020.00 credit limit.
OVERLIMIT_TRAN_AMT = "9999999.00"

# Original-transaction dates. The success date sits inside VALID_ACCT_ID's
# non-expired window (<= 2023-03-09); the future date is AFTER OVERLIMIT_ACCT_ID's
# 2025-05-20 expiration to trigger the code-103 reject.
ORIG_DATE_WITHIN_WINDOW = "2023-03-01"
ORIG_DATE_BEFORE_EXPIRY = "2024-01-01"
ORIG_DATE_AFTER_EXPIRY = "2099-01-01"


# ---------------------------------------------------------------------------
# Test helpers (PascalCase per the Ochs Rule; NOT pytest fixtures -- called
# directly). They keep each test small (<= ~20 lines) and free of duplication.
# ---------------------------------------------------------------------------
def FindPostingCode(payload: object) -> int | None:
    """Extract the numeric posting-reject code from a JSON error body.

    The ``app.main`` posting-reject handler places the reason code at the top
    level (``body["code"]``); this helper also tolerates a nested
    ``body["detail"]["code"]`` shape so the extraction stays robust if the error
    envelope is ever restructured. Returns the code as an ``int`` (the reject
    reason codes are 100-103 and 109), or ``None`` when no code is present.

    Args:
        payload: The parsed JSON error body (typically a ``dict``).

    Returns:
        The numeric posting code, or ``None`` when the body carries none.
    """
    if not isinstance(payload, dict):
        return None
    candidate = payload.get("code")
    if candidate is None and isinstance(payload.get("detail"), dict):
        candidate = payload["detail"].get("code")
    if candidate is None:
        return None
    try:
        return int(candidate)
    except (TypeError, ValueError):
        return None


def BuildTransactionPayload(**overrides: object) -> dict:
    """Build a valid ``TransactionCreate`` request body, applying overrides.

    Provides the full set of operator-entered add-transaction fields with
    known-good defaults (identifying the account via ``acct_id``; ``card_num`` is
    omitted so the account path is used). Every field is a JSON-friendly value:
    ``tran_amt`` is a STRING so its exact decimal precision survives transport
    (a float would violate §0.7.1). Callers override any field by keyword; pass
    ``acct_id=None`` to drop the account key and drive the card path instead.

    Args:
        **overrides: Field values that replace the defaults (a value of ``None``
            is preserved and serialized as JSON ``null``).

    Returns:
        A ``dict`` suitable for ``client.post(..., json=<dict>)``.
    """
    createPayload: dict = {
        "acct_id": VALID_ACCT_ID,
        "tran_type_cd": "01",
        "tran_cat_cd": "0001",
        "tran_source": "POS",
        "tran_desc": "TEST PURCHASE",
        "tran_amt": SUCCESS_TRAN_AMT,
        "merchant_id": "123456789",
        "merchant_name": "TEST",
        "merchant_city": "CITY",
        "merchant_zip": "12345",
        "orig_ts": ORIG_DATE_WITHIN_WINDOW,
        "proc_ts": ORIG_DATE_WITHIN_WINDOW,
    }
    createPayload.update(overrides)
    return createPayload


async def PostValidTransaction(adminClient: AsyncClient, **overrides: object) -> dict:
    """POST one valid transaction and return the parsed 201 response body.

    A small convenience wrapper used by the list/view tests to seed one or more
    posted rows through the real add path (``seed_data`` intentionally does not
    seed transactions). It asserts the create succeeded so a downstream failure
    is attributed to the add path, not to the test's own setup.

    Args:
        adminClient: The authenticated (admin) httpx ASGI client.
        **overrides: Optional field overrides forwarded to
            :func:`BuildTransactionPayload`.

    Returns:
        The created transaction's :class:`TransactionRead` body as a ``dict``.
    """
    createPayload = BuildTransactionPayload(**overrides)
    response = await adminClient.post(TRANSACTIONS_URL, json=createPayload)
    assert response.status_code == HTTP_CREATED, response.text
    return response.json()


async def ReadAccountBalance(dbSession: AsyncSession, acctId: str) -> Decimal:
    """Read one account's current balance directly from the database.

    Uses a column-only ``SELECT`` (not an ORM-entity load) so each call issues a
    fresh query and never returns a stale identity-map object after the service
    commits -- letting the success test compare the exact pre/post balance delta.

    Args:
        dbSession: The shared async session the request handlers also use.
        acctId: The 11-digit account identifier to read.

    Returns:
        The account's ``curr_bal`` as an exact :class:`~decimal.Decimal`.
    """
    result = await dbSession.execute(
        select(Account.curr_bal).where(Account.acct_id == acctId)
    )
    return result.scalar_one()


# ===========================================================================
# Phase 2 -- list (COTRN00C / CT00) + view (COTRN01C / CT01)
# ===========================================================================
@pytest.mark.asyncio
async def test_transactions_list_paginated(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """GET /transactions returns a masked, well-formed paginated page (CT00).

    Seeds two posted rows through the add path, then asserts the paginated
    envelope shape (all metadata keys present, page slice within ``page_size``)
    and that every listed ``card_num`` is masked to its last four digits.
    """
    await PostValidTransaction(admin_client)
    await PostValidTransaction(admin_client)

    response = await admin_client.get(TRANSACTIONS_URL)

    assert response.status_code == HTTP_OK
    responseBody = response.json()
    for metadataKey in (
        "items",
        "page",
        "page_size",
        "total_items",
        "total_pages",
        "has_next",
        "has_previous",
    ):
        assert metadataKey in responseBody
    assert len(responseBody["items"]) <= responseBody["page_size"]
    assert responseBody["total_items"] >= 2
    for transactionItem in responseBody["items"]:
        assert transactionItem["card_num"].startswith(MASKED_PREFIX)


@pytest.mark.asyncio
async def test_transactions_list_requires_auth(client: AsyncClient) -> None:
    """GET /transactions without a session is rejected with HTTP 401 (CT00).

    Uses ONLY the unauthenticated ``client`` fixture: co-requesting an
    authenticated client would install a global ``get_current_user`` override
    that leaks into this request, so the auth check must be exercised in
    isolation.
    """
    response = await client.get(TRANSACTIONS_URL)

    assert response.status_code == HTTP_UNAUTHORIZED


@pytest.mark.asyncio
async def test_get_transaction_detail(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """GET /transactions/{tranId} returns the masked detail row (CT01).

    Posts one transaction, picks its id from the list page, then views it and
    asserts the card number is masked and no ``cvv`` value is ever present in the
    response (§0.7.8).
    """
    await PostValidTransaction(admin_client)

    listResponse = await admin_client.get(TRANSACTIONS_URL)
    assert listResponse.status_code == HTTP_OK
    listItems = listResponse.json()["items"]
    assert listItems, "expected at least one posted transaction to view"
    tranId = listItems[0]["tran_id"]

    detailResponse = await admin_client.get(f"{TRANSACTIONS_URL}/{tranId}")

    assert detailResponse.status_code == HTTP_OK
    responseBody = detailResponse.json()
    assert responseBody["tran_id"] == tranId
    assert responseBody["card_num"].startswith(MASKED_PREFIX)
    assert "cvv" not in detailResponse.text.lower()


@pytest.mark.asyncio
async def test_get_transaction_not_found(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """GET /transactions/{tranId} for an unknown id returns HTTP 404 (CT01).

    Reproduces COTRN01C's "Transaction ID NOT found..." outcome (L285) as an
    HTTP 404 with that verbatim wording surfaced in the response body.
    """
    response = await admin_client.get(f"{TRANSACTIONS_URL}/{MISSING_TRAN_ID}")

    assert response.status_code == HTTP_NOT_FOUND
    assert "NOT found" in response.text


@pytest.mark.asyncio
async def test_get_transaction_blank_id(admin_client: AsyncClient) -> None:
    """GET /transactions/{blank} is rejected before lookup (CT01, L149).

    A whitespace id fails COTRN01C's VALIDATE-INPUT (Tran ID can NOT be empty)
    which the service raises as ``DomainValidationError`` -> HTTP 422. That
    exact status is asserted together with the verbatim empty-id message.
    """
    response = await admin_client.get(f"{TRANSACTIONS_URL}/ ")

    assert response.status_code == HTTP_UNPROCESSABLE
    assert "can NOT be empty" in response.text


# ===========================================================================
# Phase 3 -- add transaction: success (HTTP 201) (COTRN02C / CT02)
# ===========================================================================
@pytest.mark.asyncio
async def test_add_transaction_success(
    admin_client: AsyncClient,
    seed_data: None,
    db_session: AsyncSession,
) -> None:
    """POST /transactions posts a valid transaction and updates the balance.

    Adds a $10.00 purchase to the non-expired account 00000000050, asserting the
    HTTP 201 result masks ``card_num``, returns the exact ``Decimal`` amount and a
    16-character ``tran_id``, and -- mirroring CBTRN02C 2800-UPDATE-ACCOUNT-REC --
    increases the account ``curr_bal`` by exactly the transaction amount.
    """
    balanceBefore = await ReadAccountBalance(db_session, VALID_ACCT_ID)

    createPayload = BuildTransactionPayload(tran_amt=SUCCESS_TRAN_AMT)
    response = await admin_client.post(TRANSACTIONS_URL, json=createPayload)

    assert response.status_code == HTTP_CREATED, response.text
    responseBody = response.json()
    assert responseBody["card_num"].startswith(MASKED_PREFIX)
    assert Decimal(str(responseBody["tran_amt"])) == Decimal(SUCCESS_TRAN_AMT)
    tranId = responseBody["tran_id"]
    assert isinstance(tranId, str) and len(tranId) == 16

    balanceAfter = await ReadAccountBalance(db_session, VALID_ACCT_ID)
    assert balanceAfter - balanceBefore == SUCCESS_BALANCE_DELTA


# ===========================================================================
# Phase 4 -- add transaction: posting rejections (exact code + description)
# ===========================================================================
@pytest.mark.asyncio
async def test_add_transaction_invalid_card_100(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """Unknown card number: HTTP 404 resolve + code-100 reject catalog.

    The online COTRN02C resolves the card FIRST (VALIDATE-INPUT-KEY-FIELDS), so a
    syntactically valid but unknown card surfaces as HTTP 404 "Card Number NOT
    found..." before any posting check runs. The CBTRN02C batch posting path
    (1500-A-LOOKUP-XREF) instead rejects the same condition with code 100; that
    exact reject code + verbatim description -- what ``app.main`` serializes into
    the 422 body -- is asserted here so code 100 is pinned deterministically.
    """
    createPayload = BuildTransactionPayload(acct_id=None, card_num=UNKNOWN_CARD_NUM)
    response = await admin_client.post(TRANSACTIONS_URL, json=createPayload)

    assert response.status_code == HTTP_NOT_FOUND
    assert "NOT found" in response.text

    postingError = InvalidCardNumberError()
    assert postingError.code == CODE_INVALID_CARD
    assert postingError.description == DESC_INVALID_CARD


@pytest.mark.asyncio
async def test_add_transaction_account_not_found_101(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """Unknown account id: HTTP 404 resolve + code-101 reject catalog.

    An account id absent from the cross-reference fails COTRN02C's key-field edit
    (READ-CXACAIX) as HTTP 404 "Account ID NOT found...". Because ``card_xref``
    carries a foreign key to ``accounts``, the CBTRN02C 1500-B-LOOKUP-ACCT
    condition (code 101) cannot be provoked over HTTP with valid data, so the
    exact reject code + verbatim description is asserted from the posting catalog.
    Code 101 is DISTINCT from 109 despite the identical text (see the dedicated
    distinctness test).
    """
    createPayload = BuildTransactionPayload(acct_id=UNKNOWN_ACCT_ID, card_num=None)
    response = await admin_client.post(TRANSACTIONS_URL, json=createPayload)

    assert response.status_code == HTTP_NOT_FOUND
    assert "NOT found" in response.text

    postingError = AccountNotFoundError()
    assert postingError.code == CODE_ACCT_NOT_FOUND
    assert postingError.description == DESC_ACCT_NOT_FOUND


@pytest.mark.asyncio
async def test_add_transaction_overlimit_102(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """POST an over-limit amount: HTTP 422 with posting code 102 (CBTRN02C).

    Submits an amount far above account 00000000001's 2020.00 credit limit so
    ``curr_cyc_credit - curr_cyc_debit + amt > credit_limit`` (L406-414),
    asserting the precise 422 status, the numeric code via ``FindPostingCode`` and
    the verbatim UPPERCASE description.
    """
    createPayload = BuildTransactionPayload(
        acct_id=OVERLIMIT_ACCT_ID,
        card_num=None,
        tran_amt=OVERLIMIT_TRAN_AMT,
        orig_ts=ORIG_DATE_BEFORE_EXPIRY,
        proc_ts=ORIG_DATE_BEFORE_EXPIRY,
    )
    response = await admin_client.post(TRANSACTIONS_URL, json=createPayload)

    assert response.status_code == HTTP_UNPROCESSABLE
    responseBody = response.json()
    assert FindPostingCode(responseBody) == CODE_OVERLIMIT
    assert DESC_OVERLIMIT in str(responseBody)


@pytest.mark.asyncio
async def test_add_transaction_after_expiration_103(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """POST after account expiration: HTTP 422 with posting code 103 (CBTRN02C).

    Submits a small amount (so the over-limit edit passes) with an original date
    of 2099-01-01, AFTER account 00000000001's 2025-05-20 expiration
    (L415-421), asserting the precise 422 status, the numeric code via
    ``FindPostingCode`` and the verbatim UPPERCASE description.
    """
    createPayload = BuildTransactionPayload(
        acct_id=OVERLIMIT_ACCT_ID,
        card_num=None,
        tran_amt=SUCCESS_TRAN_AMT,
        orig_ts=ORIG_DATE_AFTER_EXPIRY,
        proc_ts=ORIG_DATE_AFTER_EXPIRY,
    )
    response = await admin_client.post(TRANSACTIONS_URL, json=createPayload)

    assert response.status_code == HTTP_UNPROCESSABLE
    responseBody = response.json()
    assert FindPostingCode(responseBody) == CODE_EXPIRED
    assert DESC_EXPIRED in str(responseBody)


@pytest.mark.asyncio
async def test_add_transaction_overlimit_and_expired_posts_103(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """A doubly-failing posting (over-limit AND expired) rejects 103, not 102.

    Submits to account 00000000001 an amount far above its 2020.00 credit limit
    AND an original date of 2099-01-01 -- after its 2025-05-20 expiration -- so
    BOTH CBTRN02C edits fail at once. 1500-B-LOOKUP-ACCT sets reason 102 then
    overwrites it with 103 in a SEPARATE sequential ``IF`` (last-write-wins), so
    the end-to-end reject MUST be code 103 with NO trace of the 102 description.
    This pins the precedence over HTTP through the real router + service +
    database (AAP 0.8.1 exact-parity), complementing the pure-unit
    ``test_run_posting_validation_overlimit_and_expired_prefers_103``.
    """
    createPayload = BuildTransactionPayload(
        acct_id=OVERLIMIT_ACCT_ID,
        card_num=None,
        tran_amt=OVERLIMIT_TRAN_AMT,
        orig_ts=ORIG_DATE_AFTER_EXPIRY,
        proc_ts=ORIG_DATE_AFTER_EXPIRY,
    )
    response = await admin_client.post(TRANSACTIONS_URL, json=createPayload)

    assert response.status_code == HTTP_UNPROCESSABLE
    responseBody = response.json()
    assert FindPostingCode(responseBody) == CODE_EXPIRED
    assert DESC_EXPIRED in str(responseBody)
    # The over-limit reason (102) must be fully overwritten, not merely tie-broken.
    assert DESC_OVERLIMIT not in str(responseBody)


def test_posting_codes_101_and_109_are_distinct() -> None:
    """Codes 101 and 109 are DISTINCT despite identical descriptions (§0.7.3).

    A static guard documenting the CBTRN02C nuance: 1500-B-LOOKUP-ACCT raises
    101 and 2800-UPDATE-ACCOUNT-REC raises 109, both reading "ACCOUNT RECORD NOT
    FOUND". The numeric code -- not the text -- distinguishes them, so any client
    reconciling against the batch reject record must key on the code.
    """
    assert CODE_ACCT_NOT_FOUND != CODE_UPDATE_FAILED
    assert CODE_ACCT_NOT_FOUND == 101
    assert CODE_UPDATE_FAILED == 109

    error101 = AccountNotFoundError()
    error109 = AccountUpdateFailedError()
    assert error101.code == CODE_ACCT_NOT_FOUND
    assert error109.code == CODE_UPDATE_FAILED
    assert error101.description == DESC_ACCT_NOT_FOUND
    assert error109.description == DESC_ACCT_NOT_FOUND
    assert error101.description == error109.description


# ===========================================================================
# Phase 5 -- add transaction: field validation
# ===========================================================================
@pytest.mark.asyncio
async def test_add_transaction_missing_amount(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """POST without ``tran_amt`` is rejected as an unprocessable request.

    ``tran_amt`` is required by ``TransactionCreate``; omitting it triggers the
    schema's "field required" edit, surfaced by app.main as HTTP 422 with the
    offending field identified in the error body.
    """
    createPayload = BuildTransactionPayload()
    createPayload.pop("tran_amt")

    response = await admin_client.post(TRANSACTIONS_URL, json=createPayload)

    assert response.status_code == HTTP_UNPROCESSABLE
    assert "tran_amt" in response.text


@pytest.mark.asyncio
async def test_add_transaction_bad_amount_format(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """POST a non-numeric ``tran_amt`` is rejected as unprocessable.

    ``"abc"`` cannot be coerced to an exact ``Decimal`` (§0.7.1: floating point
    and non-numeric input are rejected outright), so the schema edit fires as
    HTTP 422 identifying ``tran_amt`` and the decimal-value requirement.
    """
    createPayload = BuildTransactionPayload(tran_amt="abc")

    response = await admin_client.post(TRANSACTIONS_URL, json=createPayload)

    assert response.status_code == HTTP_UNPROCESSABLE
    assert "tran_amt" in response.text
    assert "decimal" in response.text.lower()


@pytest.mark.asyncio
async def test_add_transaction_requires_auth(client: AsyncClient) -> None:
    """POST /transactions without a session is rejected with HTTP 401 (CT02).

    Uses ONLY the unauthenticated ``client`` fixture so the authentication gate
    is exercised in isolation (no leaked identity override), asserting the
    precise 401 status.
    """
    createPayload = BuildTransactionPayload()

    response = await client.post(TRANSACTIONS_URL, json=createPayload)

    assert response.status_code == HTTP_UNAUTHORIZED
