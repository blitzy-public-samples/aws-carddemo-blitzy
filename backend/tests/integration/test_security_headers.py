# Integration tests for the application-wide security response headers.
# Traceability: QA finding F5 ("Online Security Gate"). The API previously
# returned no browser-protection headers on any response, so a browser client
# had no MIME-sniffing, clickjacking, referrer, or caching protection. A pure
# ASGI SecurityHeadersMiddleware (app.main.SecurityHeadersMiddleware) now
# attaches a fixed header set to EVERY response -- JSON, error, and the report
# StreamingResponse/Response bodies alike -- because it wraps the ASGI ``send``
# rather than depending on any route or response class.
#
# These are end-to-end tests: the httpx client drives the real ASGI app through
# the full middleware stack, so a regression that unwires the middleware (or
# narrows it to a subset of routes) fails here. Ochs naming (AAP 0.8.2 / 0.8.3)
# is honored: snake_case test-function names + file name, camelCase locals,
# ALL_UPPERCASE module constants, 4-space indentation.

import pytest
from httpx import AsyncClient

from app.core.config import settings

# The mount prefix (``/api/v1``) so the login URL matches app.main's mount.
LOGIN_URL = f"{settings.API_V1_PREFIX}/auth/login"
HEALTH_URL = "/health"
# An unmapped path used to prove even a 404 error response carries the headers.
UNMAPPED_URL = f"{settings.API_V1_PREFIX}/does-not-exist"

HTTP_OK = 200
HTTP_NOT_FOUND = 404

# The exact security header name/value pairs the middleware must set on every
# response (see app.main._SECURITY_HEADERS). Header names are matched
# case-insensitively by httpx; the values are asserted verbatim.
EXPECTED_SECURITY_HEADERS = {
    "x-content-type-options": "nosniff",
    "x-frame-options": "DENY",
    "content-security-policy": "frame-ancestors 'none'",
    "referrer-policy": "no-referrer",
    "cache-control": "no-store",
    "pragma": "no-cache",
    "server": "CardDemo",
}

# HSTS is emitted ONLY in production; the test environment is "test", so it must
# be absent (asserting the environment gate, not just the header set).
HSTS_HEADER_NAME = "strict-transport-security"

# Seed-only sample admin credentials (README golden-master); never real secrets.
SEED_ADMIN_USER_ID = "ADMIN001"
SEED_PASSWORD = "PASSWORD"


def AssertSecurityHeaders(response) -> None:
    """Assert every expected security header is present with its exact value.

    Args:
        response: The httpx ``Response`` returned by a client call. Its
            case-insensitive ``headers`` mapping is checked against every entry
            in :data:`EXPECTED_SECURITY_HEADERS`, and HSTS is asserted absent
            (the non-production gate).
    """
    for headerName, expectedValue in EXPECTED_SECURITY_HEADERS.items():
        assert response.headers.get(headerName) == expectedValue, (
            f"{headerName} expected {expectedValue!r}, "
            f"got {response.headers.get(headerName)!r}"
        )
    assert HSTS_HEADER_NAME not in response.headers


@pytest.mark.asyncio
async def test_health_response_carries_security_headers(client: AsyncClient) -> None:
    """The dependency-free /health 200 carries the full security header set."""
    response = await client.get(HEALTH_URL)

    assert response.status_code == HTTP_OK
    AssertSecurityHeaders(response)


@pytest.mark.asyncio
async def test_not_found_response_carries_security_headers(
    client: AsyncClient,
) -> None:
    """Even a 404 error response carries the headers (middleware wraps send).

    Proves the guarantee is not route-scoped: an unmatched path, whose response
    is produced by the framework rather than a CardDemo handler, is still
    decorated by the outermost SecurityHeadersMiddleware.
    """
    response = await client.get(UNMAPPED_URL)

    assert response.status_code == HTTP_NOT_FOUND
    AssertSecurityHeaders(response)


@pytest.mark.asyncio
async def test_login_response_carries_security_headers(
    client: AsyncClient,
    admin_user,
) -> None:
    """A successful sign-on 200 carries the headers on the JSON body path."""
    response = await client.post(
        LOGIN_URL,
        json={"user_id": SEED_ADMIN_USER_ID, "password": SEED_PASSWORD},
    )

    assert response.status_code == HTTP_OK
    AssertSecurityHeaders(response)
