# Integration tests for the POST /auth/logout endpoint (app/api/v1/auth.py).
# Traceability: app/cbl/COSGN00C.cbl -- the legacy CICS sign-off cleared the
#   COMMAREA identity so no subsequent screen acted as the signed-on user. The
#   stateless target carries identity in the HTTP-only session cookie, so signing
#   off means instructing the browser to delete that cookie; once cleared, the
#   get_current_user dependency rejects further requests with HTTP 401.
#   (QA issue #17: logout previously did not invalidate the session because there
#   was no backend endpoint to clear the cookie.)
"""Integration tests for the sign-out endpoint.

These tests pin the behavior the frontend logout flow depends on:

1.  ``POST /auth/logout`` is unauthenticated and idempotent -- it succeeds with
    HTTP 200 and a :class:`~app.schemas.MessageResponse` body even when no
    session cookie is present, so calling it is always safe.
2.  Under the ``session`` baseline it emits a cookie-deletion ``Set-Cookie``
    header for ``settings.SESSION_COOKIE_NAME`` whose attributes match those
    used at login (``Path``/``SameSite``), so the browser actually removes the
    cookie. Without this the cookie would remain valid and the next
    authenticated request would still succeed (the QA #17 defect).

The test uses the unauthenticated ``client`` fixture from ``tests.conftest``
(an httpx ASGI client wired to ``app.main:app``); logout takes no auth
dependency, so no identity override is required.
"""

from __future__ import annotations

import pytest
from httpx import AsyncClient

from app.api.v1.auth import LOGOUT_MESSAGE
from app.core.config import settings

# ---------------------------------------------------------------------------
# Module constants (ALL_UPPERCASE per the Ochs Rule 0.8.2).
# ---------------------------------------------------------------------------

# Fully-qualified logout path: the version-1 API prefix (mounted once by
# app.main) plus the auth sub-router's own resource path. Built from settings so
# the API version prefix is never hardcoded here.
LOGOUT_PATH = f"{settings.API_V1_PREFIX}/auth/logout"

# HTTP status a successful, idempotent logout returns.
HTTP_OK = 200

# Session-baseline value of settings.AUTH_MODE under which a cookie is cleared.
SESSION_AUTH_MODE = "session"


@pytest.mark.asyncio
async def test_logout_returns_ok_and_confirmation_message(client: AsyncClient) -> None:
    """Logout succeeds with HTTP 200 and the confirmation message body.

    Args:
        client: The unauthenticated httpx ASGI client wired to ``app.main:app``.
    """
    response = await client.post(LOGOUT_PATH)

    assert response.status_code == HTTP_OK
    assert response.json() == {"message": LOGOUT_MESSAGE}


@pytest.mark.asyncio
async def test_logout_is_idempotent_without_a_session_cookie(
    client: AsyncClient,
) -> None:
    """Logout succeeds even when no session cookie is sent (idempotency).

    The endpoint takes no authentication dependency, so a caller with an already
    missing/expired session can still sign out cleanly (never a 401 from logout
    itself).

    Args:
        client: The unauthenticated httpx ASGI client (carries no cookie).
    """
    response = await client.post(LOGOUT_PATH)

    assert response.status_code == HTTP_OK
    assert response.json()["message"] == LOGOUT_MESSAGE


@pytest.mark.asyncio
async def test_logout_clears_the_session_cookie_under_session_baseline(
    client: AsyncClient,
) -> None:
    """Logout emits a cookie-deletion Set-Cookie for the session cookie.

    Under the ``session`` baseline the response must instruct the browser to
    delete ``settings.SESSION_COOKIE_NAME`` (empty value + an immediate
    expiry / ``Max-Age=0``) with the same ``Path`` used at login, so the browser
    genuinely drops the cookie. This is the mechanism that invalidates the
    session for QA issue #17.

    Args:
        client: The unauthenticated httpx ASGI client wired to ``app.main:app``.
    """
    if settings.AUTH_MODE != SESSION_AUTH_MODE:
        pytest.skip("Cookie deletion only applies under the session baseline.")

    response = await client.post(LOGOUT_PATH)

    # httpx exposes every Set-Cookie header; find the one for our session cookie.
    setCookieHeaders = response.headers.get_list("set-cookie")
    sessionDirectives = [
        header
        for header in setCookieHeaders
        if header.startswith(f"{settings.SESSION_COOKIE_NAME}=")
    ]
    assert sessionDirectives, "logout must emit a Set-Cookie for the session cookie"

    directive = sessionDirectives[0]
    # Deletion is expressed as an empty value plus an expiry directive. Starlette
    # emits both Max-Age=0 and an Expires in the past; assert the durable signal.
    assert 'Max-Age=0' in directive or 'max-age=0' in directive.lower()
    # The Path must match the login cookie's Path so the browser scopes the
    # deletion to the same cookie it set.
    assert 'Path=/' in directive
