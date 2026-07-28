# Integration tests for the authentication API.
# Ported from legacy CICS online program COSGN00C (transaction CC00, signon).
# Endpoint under test: POST /api/v1/auth/login (AAP §0.5.5).
# Verifies bcrypt-hash auth (plaintext SEC-USR-PWD uplift, §0.7.7), user_id
# uppercasing (COSGN00C FUNCTION UPPER-CASE), and that NO password/password_hash
# ever appears in a response body. See AAP §0.5.1, §0.8.1.
"""Integration tests for the CardDemo sign-on endpoint (POST /api/v1/auth/login).

Ported from the legacy CICS online program ``COSGN00C`` (transaction ``CC00``,
BMS map ``COSGN00``). These tests pin the observable HTTP contract of the modern
sign-on route against the behavior of the mainframe original, exercising the
whole request path in-process: httpx ASGI client -> FastAPI router
(``app/api/v1/auth.py``) -> ``AuthService`` (``app/services/auth_service.py``) ->
``UserRepository`` -> the PostgreSQL-backed test database.

Behaviors verified (Minimal Change Clause, AAP §0.8.1):

1.  Valid credentials return HTTP 200 with the caller's identity
    (``user_id`` / ``first_name`` / ``last_name`` / ``user_type``) and -- under
    the ``session`` baseline (AAP §0.8.4) -- set the HTTP-only session cookie
    (``settings.SESSION_COOKIE_NAME``). The assertions stay tolerant of the JWT
    alternative: when a ``token_type`` is present it must be ``"bearer"``.
2.  The submitted ``user_id`` is upper-cased before the USRSEC lookup, faithfully
    reproducing the COSGN00C ``FUNCTION UPPER-CASE`` edit: signing on as
    ``admin001`` authenticates the seeded ``ADMIN001`` administrator.
3.  Invalid credentials return HTTP 401 carrying the verbatim COSGN00C messages
    (``"Wrong Password. Try again ..."`` / ``"User not found. Try again ..."``).
4.  A blank ``user_id`` or ``password`` is rejected before authentication
    (HTTP 400 or 422).
5.  A successful response never serializes the plaintext password nor a
    ``password_hash`` field -- the mandatory security uplift of AAP §0.7.7.

Fixtures are consumed from the parent ``backend/tests/conftest.py`` (this module
deliberately defines no local ``conftest``): ``client`` (the UNAUTHENTICATED
httpx ASGI client -- sign-on is the one unprotected route), ``admin_user`` and
``regular_user`` (seeded ``User`` rows whose ``"PASSWORD"`` credential is stored
hashed). The suite runs under ``asyncio_mode = "auto"``; the explicit
``@pytest.mark.asyncio`` marker (harmless in auto mode) mirrors the sibling
``tests/api`` suite.

Ochs naming (AAP §0.8.2/§0.8.3): snake_case module/file name and pytest test
functions (the framework contract); PascalCase helper functions
(``AssertIdentityPayload``); camelCase local variables (``loginPayload``,
``responseBody``); ALL_UPPERCASE module constants (``LOGIN_URL``,
``SEED_PASSWORD``).
"""

from __future__ import annotations

import pytest
from httpx import AsyncClient, Response

from app.core.config import settings
from app.models.user import User

# ---------------------------------------------------------------------------
# Endpoint + identity constants (ALL_UPPERCASE per the Ochs Rule §0.8.2). The
# login URL is built from settings.API_V1_PREFIX (mounted once by app.main) so
# the API version prefix is never hardcoded here; it resolves to
# "/api/v1/auth/login".
# ---------------------------------------------------------------------------
LOGIN_URL = f"{settings.API_V1_PREFIX}/auth/login"

# Seed identities from the README (matching the conftest seeders): ADMIN001 is
# the administrator (user_type 'A'); USER0001 is the regular user ('U').
ADMIN_ID = "ADMIN001"
REGULAR_ID = "USER0001"

# Lowercase spelling of the admin id, used to prove the COSGN00C FUNCTION
# UPPER-CASE edit -- the service upper-cases the input before the USRSEC lookup.
ADMIN_ID_LOWERCASE = "admin001"

# SEED-ONLY sample credential from the README. It is seed/test data ONLY --
# never a real secret and never hardcoded as a production credential. The value
# is stored HASHED by the conftest seeders and is never asserted in cleartext.
SEED_PASSWORD = "PASSWORD"

# A schema-valid (8-char, alphanumeric) but deliberately WRONG password, and a
# schema-valid 8-char user id that is deliberately NOT seeded. Both exercise the
# 401 failure paths without tripping the LoginRequest field validation first.
WRONG_PASSWORD = "WRONGPWD"
UNKNOWN_ID = "NOSUCH01"

# Role codes (SEC-USR-TYPE / CDEMO-USER-TYPE): 'A' admin, 'U' regular user.
ADMIN_USER_TYPE = "A"
REGULAR_USER_TYPE = "U"

# settings.AUTH_MODE baseline (AAP §0.8.4) under which the signed token travels
# in the session cookie rather than the response body, plus the OAuth2 scheme
# reported for the JWT alternative.
SESSION_AUTH_MODE = "session"
BEARER_TOKEN_TYPE = "bearer"

# Exact HTTP status codes asserted by the tests (never a broad "not 2xx" check).
HTTP_OK = 200
HTTP_UNAUTHORIZED = 401
HTTP_UNPROCESSABLE_ENTITY = 422

# Stable substrings of the verbatim COSGN00C failure messages. Matching a stable
# fragment (rather than the whole "... Try again ..." text) keeps the assertion
# robust to the {"detail": ...} / {"message": ...} envelope the handler uses.
WRONG_PASSWORD_MESSAGE = "Wrong Password"   # COSGN00C L242 (internal log only).
USER_NOT_FOUND_MESSAGE = "User not found"   # COSGN00C L249 (internal log only).
# QA Issue C8 (anti-enumeration): the wrong-password and unknown-user paths both
# return HTTP 401 with ONE shared generic message, so the response never reveals
# whether the user id or the password was the failing factor.
INVALID_CREDENTIALS_MESSAGE = "Invalid user ID or password"
USER_ID_MESSAGE = "User ID"                 # COSGN00C L120 ("Please enter User ID ...").

# Response keys that must NEVER appear in a login response body (the plaintext
# password and the stored hash), guarding the AAP §0.7.7 security uplift.
FORBIDDEN_RESPONSE_KEYS = ("password", "password_hash")


def BuildLoginPayload(userId: str, password: str) -> dict[str, str]:
    """Build the JSON body for a ``POST /auth/login`` request.

    Small PascalCase helper (Ochs §0.8.3) that keeps each test's request
    construction to a single, self-documenting call.

    Args:
        userId: The user id to submit (the COSGN00 ``USERIDI`` field).
        password: The password to submit (the COSGN00 ``PASSWDI`` field).

    Returns:
        The request body dict using the ``user_id`` / ``password`` wire keys.
    """
    return {"user_id": userId, "password": password}


def AssertNoCredentialLeak(responseBody: dict, responseText: str) -> None:
    """Assert a login response never exposes the password or its hash.

    Reproduces the AAP §0.7.7 guarantee at the API boundary: neither the
    plaintext ``SEED_PASSWORD`` nor a ``password_hash`` field may be serialized,
    whether as a JSON key or anywhere in the raw response text.

    Args:
        responseBody: The parsed JSON response body.
        responseText: The raw (unparsed) response body text.
    """
    for forbiddenKey in FORBIDDEN_RESPONSE_KEYS:
        assert forbiddenKey not in responseBody
    assert "password_hash" not in responseText.lower()
    assert SEED_PASSWORD not in responseText


def AssertIdentityPayload(
    responseBody: dict, expectedUserId: str, expectedUserType: str
) -> None:
    """Assert a successful login body carries the expected identity fields.

    Checks the four ``LoginResponse`` identity fields ported from the USRSEC
    record (``CSUSR01Y``): ``user_id`` and ``user_type`` equal the expected
    values, and ``first_name`` / ``last_name`` are present and non-empty.

    Args:
        responseBody: The parsed JSON response body.
        expectedUserId: The user id the response must echo (always UPPER-CASE).
        expectedUserType: The role code the response must carry ('A' or 'U').
    """
    assert responseBody["user_id"] == expectedUserId
    assert responseBody["user_type"] == expectedUserType
    assert responseBody.get("first_name")
    assert responseBody.get("last_name")


def AssertAuthArtifacts(response: Response, responseBody: dict) -> None:
    """Assert the auth-mode-appropriate artifacts, tolerant of session or JWT.

    Under the ``session`` baseline (AAP §0.8.4) the signed token is set as the
    HTTP-only ``settings.SESSION_COOKIE_NAME`` cookie, so its ``Set-Cookie``
    header must be present. Because the test client speaks plain HTTP
    (``http://test``) while the cookie is marked ``Secure`` outside development,
    httpx's cookie jar may not populate ``response.cookies``; the raw
    ``Set-Cookie`` header is therefore accepted as well. Independently, if the
    body carries a ``token_type`` (the JWT alternative), it must be ``"bearer"``.

    Args:
        response: The httpx response returned by the login call.
        responseBody: The parsed JSON response body.
    """
    if settings.AUTH_MODE == SESSION_AUTH_MODE:
        cookieValue = response.cookies.get(settings.SESSION_COOKIE_NAME)
        setCookieHeader = response.headers.get("set-cookie", "")
        cookieIsSet = (
            cookieValue is not None
            or settings.SESSION_COOKIE_NAME in setCookieHeader
        )
        assert cookieIsSet
    tokenType = responseBody.get("token_type")
    if tokenType:
        assert tokenType == BEARER_TOKEN_TYPE


@pytest.mark.asyncio
async def test_login_admin_success(client: AsyncClient, admin_user: User) -> None:
    """Valid administrator credentials return HTTP 200 with the admin identity.

    Args:
        client: The unauthenticated httpx ASGI client (sign-on is unprotected).
        admin_user: The seeded ADMIN001 administrator (user_type 'A').
    """
    loginPayload = BuildLoginPayload(ADMIN_ID, SEED_PASSWORD)
    response = await client.post(LOGIN_URL, json=loginPayload)

    assert response.status_code == HTTP_OK
    responseBody = response.json()
    AssertIdentityPayload(responseBody, ADMIN_ID, ADMIN_USER_TYPE)
    AssertNoCredentialLeak(responseBody, response.text)
    AssertAuthArtifacts(response, responseBody)


@pytest.mark.asyncio
async def test_login_regular_success(client: AsyncClient, regular_user: User) -> None:
    """Valid regular-user credentials return HTTP 200 with the regular identity.

    Args:
        client: The unauthenticated httpx ASGI client.
        regular_user: The seeded USER0001 regular user (user_type 'U').
    """
    loginPayload = BuildLoginPayload(REGULAR_ID, SEED_PASSWORD)
    response = await client.post(LOGIN_URL, json=loginPayload)

    assert response.status_code == HTTP_OK
    responseBody = response.json()
    AssertIdentityPayload(responseBody, REGULAR_ID, REGULAR_USER_TYPE)
    AssertNoCredentialLeak(responseBody, response.text)
    AssertAuthArtifacts(response, responseBody)


@pytest.mark.asyncio
async def test_login_uppercases_user_id(client: AsyncClient, admin_user: User) -> None:
    """A lowercase user id authenticates the uppercase seed (COSGN00C UPPER-CASE).

    Submitting ``admin001`` signs on as the seeded ``ADMIN001`` because the
    service applies the COSGN00C ``FUNCTION UPPER-CASE`` edit before the USRSEC
    lookup, and the response echoes the canonical uppercase id.

    Args:
        client: The unauthenticated httpx ASGI client.
        admin_user: The seeded ADMIN001 administrator.
    """
    loginPayload = BuildLoginPayload(ADMIN_ID_LOWERCASE, SEED_PASSWORD)
    response = await client.post(LOGIN_URL, json=loginPayload)

    assert response.status_code == HTTP_OK
    responseBody = response.json()
    assert responseBody["user_id"] == ADMIN_ID
    AssertIdentityPayload(responseBody, ADMIN_ID, ADMIN_USER_TYPE)


@pytest.mark.asyncio
async def test_login_wrong_password(client: AsyncClient, admin_user: User) -> None:
    """A wrong password for an existing user returns HTTP 401 (COSGN00C L242).

    Args:
        client: The unauthenticated httpx ASGI client.
        admin_user: The seeded ADMIN001 administrator, so the user exists and the
            flow reaches the password check rather than user-not-found.
    """
    loginPayload = BuildLoginPayload(ADMIN_ID, WRONG_PASSWORD)
    response = await client.post(LOGIN_URL, json=loginPayload)

    assert response.status_code == HTTP_UNAUTHORIZED
    assert INVALID_CREDENTIALS_MESSAGE in str(response.json())


@pytest.mark.asyncio
async def test_login_unknown_user(client: AsyncClient) -> None:
    """An unknown user id returns HTTP 401 with the generic message (L249; QA C8).

    Args:
        client: The unauthenticated httpx ASGI client. No user is seeded, so the
            USRSEC lookup misses. The surfaced message is the shared generic
            credential message (identical to the wrong-password path) so the id
            cannot be enumerated.
    """
    loginPayload = BuildLoginPayload(UNKNOWN_ID, SEED_PASSWORD)
    response = await client.post(LOGIN_URL, json=loginPayload)

    assert response.status_code == HTTP_UNAUTHORIZED
    assert INVALID_CREDENTIALS_MESSAGE in str(response.json())


@pytest.mark.asyncio
async def test_login_empty_user_id(client: AsyncClient) -> None:
    """A blank user id is rejected before authentication (HTTP 422).

    The ported precedence rejects a blank user id first (COSGN00C L120): the
    ``LoginRequest`` schema edit fires during request parsing, so the
    credentials are never checked and the API returns HTTP 422.

    Args:
        client: The unauthenticated httpx ASGI client.
    """
    loginPayload = BuildLoginPayload("", SEED_PASSWORD)
    response = await client.post(LOGIN_URL, json=loginPayload)

    assert response.status_code == HTTP_UNPROCESSABLE_ENTITY
    assert USER_ID_MESSAGE in str(response.json())


@pytest.mark.asyncio
async def test_login_empty_password(client: AsyncClient) -> None:
    """A blank password is rejected before authentication (HTTP 422).

    The ``LoginRequest`` password field edit fires during request parsing, so
    the credentials are never checked and the API returns HTTP 422.

    Args:
        client: The unauthenticated httpx ASGI client.
    """
    loginPayload = BuildLoginPayload(ADMIN_ID, "")
    response = await client.post(LOGIN_URL, json=loginPayload)

    assert response.status_code == HTTP_UNPROCESSABLE_ENTITY
    assert "password" in str(response.json()).lower()


@pytest.mark.asyncio
async def test_login_response_never_leaks_password(
    client: AsyncClient, admin_user: User
) -> None:
    """A successful login response never serializes the password or its hash.

    Guards the AAP §0.7.7 security uplift end to end: neither the plaintext
    ``SEED_PASSWORD`` nor a ``password_hash`` field appears anywhere in the raw
    response text (the bcrypt hash and the plaintext are never serialized).

    Args:
        client: The unauthenticated httpx ASGI client.
        admin_user: The seeded ADMIN001 administrator.
    """
    loginPayload = BuildLoginPayload(ADMIN_ID, SEED_PASSWORD)
    response = await client.post(LOGIN_URL, json=loginPayload)

    assert response.status_code == HTTP_OK
    assert "password_hash" not in response.text.lower()
    assert SEED_PASSWORD not in response.text
    AssertNoCredentialLeak(response.json(), response.text)
