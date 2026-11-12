# Cross-cutting integration tests for the NaN/Infinity denial-of-service fix.
# Traceability: QA SECURITY finding (dest security gate). An unauthenticated
#   caller can smuggle an IEEE-754 NaN / +Infinity / -Infinity onto ANY JSON
#   body field via a literal such as ``1e400`` / ``NaN`` (Python's json.loads
#   accepts them). The value lands in ``RequestValidationError.errors()`` as the
#   offending ``input`` and FastAPI's ``JSONResponse`` renders with
#   ``json.dumps(allow_nan=False)``, which RAISES on it. Because that raise
#   happens INSIDE the 422 validation-error handler, Starlette turns the intended
#   422 into an unhandled 500 -- a pre-auth DoS.
#
#   The finding has two request-surface vectors, both pinned here end-to-end:
#     * VECTOR 1 -- a NON-FINITE FLOAT (from a numeric literal like ``1e400``) on
#       a string field, an unknown/extra field, or a numeric field. It is
#       neutralized by ``app.main._ReplaceNonFiniteFloats`` in both 422 handlers.
#     * VECTOR 2 -- a NON-FINITE STRING (``"Infinity"`` / ``"NaN"``) on a money
#       field, which ``decimal.Decimal`` would otherwise accept and then crash at
#       response serialization. It is rejected up front by
#       ``app.utils.decimal_utils.ToDecimal`` (surfaced as a clean 422).
#
# These tests drive the real endpoints through the in-process httpx ASGI client
# wired to ``app.main:app`` (see ``tests/conftest``). They assert the SECURITY
# property directly: every non-finite injection returns HTTP 422 (never 500) and
# a well-formed JSON body -- i.e. the validation-error handler stays TOTAL.
#
# Ochs Rule (AAP 0.8.2/0.8.3): module file name snake_case; PascalCase in-code
# helpers (:func:`RawJsonBody`, :func:`AssertUnprocessableNotServerError`);
# camelCase locals; ALL_UPPERCASE module constants; 4-space indentation. The
# suite runs under ``asyncio_mode = "auto"`` (plain ``async def test_*``); the
# client fixtures from the parent ``tests/conftest.py`` are consumed as-is.
"""End-to-end tests pinning the NaN/Infinity DoS fix (clean 422, never 500)."""

from __future__ import annotations

from httpx import AsyncClient

from app.core.config import settings

# ---------------------------------------------------------------------------
# Module constants (ALL_UPPERCASE per the Ochs Rule).
# ---------------------------------------------------------------------------

# Fully-qualified endpoint paths, built from settings so the API version prefix
# is never hardcoded.
LOGIN_URL = f"{settings.API_V1_PREFIX}/auth/login"
TRANSACTIONS_URL = f"{settings.API_V1_PREFIX}/transactions"

# Header that forces the server to parse the raw body as JSON (so a bare numeric
# literal such as ``1e400`` is transmitted verbatim, faithfully mirroring the
# finding's ``curl -d`` reproduction rather than any client-side re-encoding).
JSON_HEADERS = {"Content-Type": "application/json"}

# Seed-only golden-master password (README; never a real credential). Used only
# to shape a syntactically plausible login body around the injected value.
SEED_PASSWORD = "PASSWORD"

# HTTP status codes asserted below.
HTTP_UNPROCESSABLE = 422
HTTP_INTERNAL_SERVER_ERROR = 500

# Key that the 422 body must carry once the handler renders successfully.
DETAIL_KEY = "detail"


# ---------------------------------------------------------------------------
# Test helpers (PascalCase per the Ochs Rule; NOT pytest fixtures).
# ---------------------------------------------------------------------------


def RawJsonBody(fieldName: str, valueLiteral: str) -> str:
    """Return a two-field login body as raw JSON with one UNQUOTED value literal.

    Builds ``{"<fieldName>": <valueLiteral>, "password": "PASSWORD"}`` where
    ``valueLiteral`` is inserted UNQUOTED so a caller can inject a bare numeric
    literal such as ``1e400`` / ``-1e400`` / ``NaN`` exactly as an attacker would
    over the wire.

    Args:
        fieldName: The body key to attach the raw literal to (``user_id`` for the
            reported reproduction, or an unknown/extra field name).
        valueLiteral: The raw JSON token to place as the value, unquoted.

    Returns:
        The request body as a JSON text string.
    """
    return f'{{"{fieldName}": {valueLiteral}, "password": "{SEED_PASSWORD}"}}'


def RawTransactionBody(amountLiteral: str) -> str:
    """Return a complete add-transaction body as raw JSON with a literal amount.

    Every field carries a known-good value (mirroring the golden-master anchors
    used by ``test_transactions_api``); only ``tran_amt`` is set from the given
    UNQUOTED literal so a bare numeric ``1e400`` can be transmitted verbatim.

    Args:
        amountLiteral: The raw JSON token to place as ``tran_amt``, unquoted.

    Returns:
        The request body as a JSON text string.
    """
    return (
        "{"
        '"acct_id": "00000000050",'
        '"tran_type_cd": "01",'
        '"tran_cat_cd": "0001",'
        '"tran_source": "POS",'
        '"tran_desc": "TEST PURCHASE",'
        f'"tran_amt": {amountLiteral},'
        '"merchant_id": "123456789",'
        '"merchant_name": "TEST",'
        '"merchant_city": "CITY",'
        '"merchant_zip": "12345",'
        '"orig_ts": "2023-03-01",'
        '"proc_ts": "2023-03-01"'
        "}"
    )


def AssertUnprocessableNotServerError(response) -> None:
    """Assert the response is a well-formed 422 and specifically NOT a 500.

    This is the exact security property the fix restores: the non-finite value
    is neutralized so the validation-error handler renders a clean 422 body
    instead of raising and collapsing into an unhandled 500.

    Args:
        response: The httpx response returned by the endpoint under test.
    """
    assert response.status_code != HTTP_INTERNAL_SERVER_ERROR, response.text
    assert response.status_code == HTTP_UNPROCESSABLE, response.text
    # The body must be valid JSON carrying the standard error envelope, proving
    # the renderer completed rather than aborting mid-serialization.
    responseBody = response.json()
    assert DETAIL_KEY in responseBody


# ---------------------------------------------------------------------------
# VECTOR 1 -- non-finite FLOAT on the public login endpoint (the reported
# reproduction and its variants). Unauthenticated ``client`` fixture.
# ---------------------------------------------------------------------------


async def test_login_positive_infinity_on_user_id_returns_422_not_500(
    client: AsyncClient,
) -> None:
    """The exact finding reproduction: ``{"user_id": 1e400, ...}`` -> 422 not 500."""
    response = await client.post(
        LOGIN_URL, content=RawJsonBody("user_id", "1e400"), headers=JSON_HEADERS
    )
    AssertUnprocessableNotServerError(response)


async def test_login_negative_infinity_on_user_id_returns_422_not_500(
    client: AsyncClient,
) -> None:
    """A ``-1e400`` (negative infinity) literal is likewise handled cleanly."""
    response = await client.post(
        LOGIN_URL, content=RawJsonBody("user_id", "-1e400"), headers=JSON_HEADERS
    )
    AssertUnprocessableNotServerError(response)


async def test_login_nan_on_user_id_returns_422_not_500(
    client: AsyncClient,
) -> None:
    """A bare ``NaN`` literal (accepted by json.loads) is handled cleanly."""
    response = await client.post(
        LOGIN_URL, content=RawJsonBody("user_id", "NaN"), headers=JSON_HEADERS
    )
    AssertUnprocessableNotServerError(response)


async def test_login_infinity_on_unknown_extra_field_returns_422_not_500(
    client: AsyncClient,
) -> None:
    """A non-finite float on an UNKNOWN/extra field (rejected by ``extra=forbid``)
    still renders a clean 422 -- the vector that bypasses every field validator."""
    rawBody = (
        f'{{"user_id": "ADMIN001", "password": "{SEED_PASSWORD}", '
        '"injected": 1e400}'
    )
    response = await client.post(LOGIN_URL, content=rawBody, headers=JSON_HEADERS)
    AssertUnprocessableNotServerError(response)


async def test_login_infinity_numeric_password_field_is_redacted_not_500(
    client: AsyncClient,
) -> None:
    """A non-finite float on the sensitive ``password`` field is redacted AND the
    handler still returns a clean 422 (the sensitive-redaction and non-finite
    sanitization paths compose correctly)."""
    rawBody = '{"user_id": "ADMIN001", "password": 1e400}'
    response = await client.post(LOGIN_URL, content=rawBody, headers=JSON_HEADERS)
    AssertUnprocessableNotServerError(response)
    # The raw non-finite token must not survive anywhere in the body, and the
    # password value must never be echoed back (sensitive redaction).
    bodyText = response.text
    assert "Infinity" not in bodyText
    assert "1e400" not in bodyText


# ---------------------------------------------------------------------------
# VECTOR 1 (numeric) + VECTOR 2 (string) on a MONEY field, through the
# authenticated add-transaction endpoint. ``admin_client`` authenticates via the
# dependency override, so the request reaches request-body validation (the 422
# is produced there, before any database access).
# ---------------------------------------------------------------------------


async def test_add_transaction_infinity_string_amount_returns_422(
    admin_client: AsyncClient,
) -> None:
    """VECTOR 2: ``tran_amt: "Infinity"`` (a STRING that ``Decimal`` accepts) is
    rejected up front by ToDecimal as a clean 422, never accepted-then-500."""
    response = await admin_client.post(
        TRANSACTIONS_URL,
        json={
            "acct_id": "00000000050",
            "tran_type_cd": "01",
            "tran_cat_cd": "0001",
            "tran_source": "POS",
            "tran_desc": "TEST PURCHASE",
            "tran_amt": "Infinity",
            "merchant_id": "123456789",
            "merchant_name": "TEST",
            "merchant_city": "CITY",
            "merchant_zip": "12345",
            "orig_ts": "2023-03-01",
            "proc_ts": "2023-03-01",
        },
    )
    AssertUnprocessableNotServerError(response)


async def test_add_transaction_nan_string_amount_returns_422(
    admin_client: AsyncClient,
) -> None:
    """VECTOR 2 variant: ``tran_amt: "NaN"`` is rejected as a clean 422."""
    response = await admin_client.post(
        TRANSACTIONS_URL,
        json={
            "acct_id": "00000000050",
            "tran_type_cd": "01",
            "tran_cat_cd": "0001",
            "tran_source": "POS",
            "tran_desc": "TEST PURCHASE",
            "tran_amt": "NaN",
            "merchant_id": "123456789",
            "merchant_name": "TEST",
            "merchant_city": "CITY",
            "merchant_zip": "12345",
            "orig_ts": "2023-03-01",
            "proc_ts": "2023-03-01",
        },
    )
    AssertUnprocessableNotServerError(response)


async def test_add_transaction_infinity_numeric_amount_returns_422(
    admin_client: AsyncClient,
) -> None:
    """VECTOR 1 on a money field: a bare ``1e400`` numeric literal (parsed to a
    non-finite float) is rejected and the 422 handler renders cleanly."""
    response = await admin_client.post(
        TRANSACTIONS_URL,
        content=RawTransactionBody("1e400"),
        headers=JSON_HEADERS,
    )
    AssertUnprocessableNotServerError(response)
