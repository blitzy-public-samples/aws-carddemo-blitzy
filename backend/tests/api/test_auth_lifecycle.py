# End-to-end auth lifecycle tests through REAL sign-on (QA findings M-01, M-02).
# Traceability: app/cbl/COSGN00C.cbl (sign-on CC00) + the stateless session/JWT
# identity that replaces the COCOM01Y COMMAREA. These tests exercise the WHOLE
# request path with a REAL minted token (not the get_current_user override the
# other suites use), which is exactly the "complete real-token lifecycle suite"
# the QA report found missing (M-02).
"""End-to-end authentication lifecycle tests driven through real sign-on.

Unlike the other API suites -- which inject identity via the ``get_current_user``
dependency override and therefore never exercise real token minting/validation --
this suite performs an actual ``POST /auth/login``, captures the signed session
token from the ``Set-Cookie`` header, and replays it against a protected route
(``GET /menu``) and the ``POST /auth/logout`` mutation. It pins the two MAJOR
security fixes end to end:

M-01 (credential handling):
    * A wrong-CASE password is rejected (the password is verified over its exact
      bytes; the legacy FUNCTION UPPER-CASE fold is gone).
    * Repeated credential failures for one ``(user id, client IP)`` pair trip a
      lockout that returns a GENERIC HTTP 429 -- without ever revealing whether
      the user id exists -- and a subsequent success is blocked while locked.

M-02 (session lifecycle):
    * A tampered token is rejected with 401.
    * An expired token is rejected with 401.
    * A token captured before logout is rejected AFTER logout (server-side
      revocation via ``session_version``), closing the "deleted only the client
      cookie" gap.
    * A cookie-authenticated mutation (logout) is rejected with 403 when the
      Origin is missing or not allow-listed (CSRF/Origin control), and succeeds
      with an allow-listed Origin.
    * A privilege change (role update) rotates ``session_version`` and thereby
      revokes a token minted before the change.

Cookie handling note:
    Outside development the session cookie is ``Secure`` (``ENVIRONMENT=test`` in
    the suite), so httpx's client jar will not auto-resend it over the in-process
    ``http://test`` transport. Each test therefore reads the token from the login
    response and presents it EXPLICITLY as a raw ``Cookie`` request header (via
    :func:`AuthHeaders`) on the follow-up request -- which mirrors how a real
    browser (on HTTPS) would resend it, and avoids httpx's deprecated per-request
    ``cookies=`` argument.

Fixtures (from ``tests.conftest``): the unauthenticated ``client`` (real
``get_current_user`` runs), ``admin_user`` / ``regular_user`` (seeded identities
whose password hash is of the seed-only ``PASSWORD``), and the autouse
``reset_login_rate_limiter`` (guarantees a pristine throttle per test). Naming
follows the Ochs resolution: snake_case test names (pytest contract), PascalCase
helpers, camelCase locals, ALL_UPPERCASE constants.
"""

from __future__ import annotations

import pytest
from httpx import AsyncClient

from app.core.config import settings
from app.core.rate_limiter import loginRateLimiter
from app.core.security import CreateAccessToken
from app.models.user import User
from app.services.user_admin_service import UserAdminService
from app.schemas.user import UserUpdate

# ---------------------------------------------------------------------------
# Module constants (ALL_UPPERCASE per the Ochs Rule 0.8.2). Paths are built from
# settings so the API version prefix is never hardcoded.
# ---------------------------------------------------------------------------
LOGIN_PATH = f"{settings.API_V1_PREFIX}/auth/login"
LOGOUT_PATH = f"{settings.API_V1_PREFIX}/auth/logout"
MENU_PATH = f"{settings.API_V1_PREFIX}/menu"

HTTP_OK = 200
HTTP_UNAUTHORIZED = 401
HTTP_FORBIDDEN = 403
HTTP_TOO_MANY_REQUESTS = 429

DETAIL_KEY = "detail"

# Seed-only golden-master password (README); the fixtures store its bcrypt hash.
SEED_PASSWORD = "PASSWORD"
# A wrong-CASE variant of the seed password: identical letters, different case.
# It must FAIL now that the password is verified over exact bytes (M-01).
SEED_PASSWORD_WRONG_CASE = "password"
# A syntactically valid (non-blank, <= 8 char) but simply incorrect password.
WRONG_PASSWORD = "BADPASS1"

# The session cookie name and the sole allow-listed browser origin, both read
# from settings (the default origin is http://localhost:3000).
COOKIE_NAME = settings.SESSION_COOKIE_NAME
ALLOWED_ORIGIN = settings.BACKEND_CORS_ORIGINS[0]
DISALLOWED_ORIGIN = "http://evil.example"

# A mandatory acting administrator for the direct user-admin service call below
# (M-04): the service now REQUIRES a ``currentUser`` actor. Its id is distinct
# from every target so it satisfies the in-depth admin gate without tripping the
# self-action guards.
ACTING_ADMIN = User(user_id="ADMINACT", user_type="A")


async def SignIn(client: AsyncClient, userId: str, password: str) -> str:
    """Perform a real sign-on and return the minted session token.

    Posts the credentials to the login endpoint, asserts a 200, and extracts the
    signed token from the response's ``Set-Cookie`` (exposed via
    ``response.cookies`` even for a ``Secure`` cookie).

    Args:
        client: The unauthenticated ASGI client.
        userId: The user id to sign on with.
        password: The password to sign on with.

    Returns:
        The signed session-token string carried by the session cookie.
    """
    response = await client.post(
        LOGIN_PATH, json={"user_id": userId, "password": password}
    )
    assert response.status_code == HTTP_OK
    token = response.cookies.get(COOKIE_NAME)
    assert token is not None
    return token


def AuthHeaders(token: str, origin: str | None = None) -> dict[str, str]:
    """Build request headers carrying the session cookie (and optional Origin).

    The token is sent as a raw ``Cookie`` header rather than via httpx's
    per-request ``cookies=`` argument: the latter is deprecated, and -- because
    the session cookie is ``Secure`` under ``ENVIRONMENT=test`` -- httpx's client
    jar would not resend it over the in-process ``http://test`` transport anyway.
    Starlette parses the ``Cookie`` header into ``request.cookies``, so the CSRF
    middleware and ``get_current_user`` see the token exactly as a browser (on
    HTTPS) would present it. When ``origin`` is supplied it is added as the
    ``Origin`` header so cookie-authenticated mutations can pass the CSRF check.

    Args:
        token: The signed session token to present in the ``Cookie`` header.
        origin: Optional value for the ``Origin`` header (an allow-listed origin
            to pass CSRF, or a disallowed one to prove it is blocked).

    Returns:
        The headers dict to pass as ``headers=`` on the request.
    """
    headers = {"Cookie": f"{COOKIE_NAME}={token}"}
    if origin is not None:
        headers["Origin"] = origin
    return headers


# ===========================================================================
# M-02 -- real-token validation lifecycle on a protected route (GET /menu).
# ===========================================================================


@pytest.mark.asyncio
async def test_real_login_token_authorizes_protected_route(
    client: AsyncClient, admin_user: User
) -> None:
    """A freshly minted real token authorizes a protected GET (200).

    Args:
        client: The unauthenticated ASGI client (real get_current_user runs).
        admin_user: The seeded administrator (ADMIN001).
    """
    token = await SignIn(client, admin_user.user_id, SEED_PASSWORD)

    response = await client.get(MENU_PATH, headers=AuthHeaders(token))

    assert response.status_code == HTTP_OK


@pytest.mark.asyncio
async def test_missing_token_is_rejected(
    client: AsyncClient, admin_user: User
) -> None:
    """A protected route with no token returns 401.

    Args:
        client: The unauthenticated ASGI client.
        admin_user: Seeded so the users table is populated (not otherwise used).
    """
    response = await client.get(MENU_PATH)

    assert response.status_code == HTTP_UNAUTHORIZED


@pytest.mark.asyncio
async def test_tampered_token_is_rejected(
    client: AsyncClient, admin_user: User
) -> None:
    """A token whose signature has been altered returns 401 (M-02).

    Args:
        client: The unauthenticated ASGI client.
        admin_user: The seeded administrator whose real token is then tampered.
    """
    token = await SignIn(client, admin_user.user_id, SEED_PASSWORD)
    tamperedToken = token[:-3] + ("aaa" if token[-3:] != "aaa" else "bbb")

    response = await client.get(MENU_PATH, headers=AuthHeaders(tamperedToken))

    assert response.status_code == HTTP_UNAUTHORIZED


@pytest.mark.asyncio
async def test_expired_token_is_rejected(
    client: AsyncClient, admin_user: User
) -> None:
    """An expired token returns 401 (M-02).

    The token is minted with a negative lifetime so it is already expired; the
    decode fails on expiry before any session-version check, yielding 401.

    Args:
        client: The unauthenticated ASGI client.
        admin_user: The seeded administrator identity the token names.
    """
    expiredToken = CreateAccessToken(
        admin_user.user_id,
        admin_user.user_type,
        sessionVersion=admin_user.session_version,
        expiresMinutes=-1,
    )

    response = await client.get(MENU_PATH, headers=AuthHeaders(expiredToken))

    assert response.status_code == HTTP_UNAUTHORIZED


@pytest.mark.asyncio
async def test_token_without_session_version_is_rejected(
    client: AsyncClient, admin_user: User
) -> None:
    """A token that omits the ``sver`` claim is rejected (M-02, fail-closed).

    A token minted before revocation existed carries no ``sver``; the missing
    claim must be treated as a mismatch so such a token cannot bypass revocation.

    Args:
        client: The unauthenticated ASGI client.
        admin_user: The seeded administrator identity the token names.
    """
    noVersionToken = CreateAccessToken(
        admin_user.user_id,
        admin_user.user_type,
    )

    response = await client.get(MENU_PATH, headers=AuthHeaders(noVersionToken))

    assert response.status_code == HTTP_UNAUTHORIZED


@pytest.mark.asyncio
async def test_token_revoked_after_logout_is_rejected(
    client: AsyncClient, admin_user: User
) -> None:
    """A token captured before logout is rejected after logout (M-02).

    Proves server-side revocation: logging out advances the user's
    ``session_version``, so the pre-logout token -- whose frozen ``sver`` no
    longer matches -- fails on its next use even though it has not expired. The
    logout is sent with an allow-listed Origin so the CSRF guard admits it.

    Args:
        client: The unauthenticated ASGI client.
        admin_user: The seeded administrator (ADMIN001).
    """
    token = await SignIn(client, admin_user.user_id, SEED_PASSWORD)
    # Sanity: the token works before logout.
    assert (
        await client.get(MENU_PATH, headers=AuthHeaders(token))
    ).status_code == HTTP_OK

    logoutResponse = await client.post(
        LOGOUT_PATH,
        headers=AuthHeaders(token, ALLOWED_ORIGIN),
    )
    assert logoutResponse.status_code == HTTP_OK

    # The very same token is now revoked.
    afterLogout = await client.get(MENU_PATH, headers=AuthHeaders(token))
    assert afterLogout.status_code == HTTP_UNAUTHORIZED


@pytest.mark.asyncio
async def test_privilege_change_revokes_prior_token(
    client: AsyncClient, regular_user: User, db_session
) -> None:
    """A role change rotates session_version, revoking a prior token (M-02).

    A regular user signs on, obtaining a token frozen at ``session_version`` 1.
    An administrative role update advances the generation (rotation on privilege
    change); the pre-change token is then rejected on its next use.

    Args:
        client: The unauthenticated ASGI client.
        regular_user: The seeded regular user (USER0001) whose role is changed.
        db_session: The shared session used to drive the admin update directly.
    """
    token = await SignIn(client, regular_user.user_id, SEED_PASSWORD)
    assert (
        await client.get(MENU_PATH, headers=AuthHeaders(token))
    ).status_code == HTTP_OK

    # Promote the user to admin through the service (rotates session_version).
    # UserUpdate re-sends every mandatory field (the COUSR02 screen contract);
    # only user_type changes ('U' -> 'A'), which is a real edit (not a no-op) and
    # trips the role-change branch that advances session_version.
    await UserAdminService().UpdateUser(
        db_session,
        regular_user.user_id,
        UserUpdate(
            first_name=regular_user.first_name,
            last_name=regular_user.last_name,
            user_type="A",
        ),
        currentUser=ACTING_ADMIN,
    )

    afterChange = await client.get(MENU_PATH, headers=AuthHeaders(token))
    assert afterChange.status_code == HTTP_UNAUTHORIZED


# ===========================================================================
# M-02 -- CSRF / Origin enforcement for cookie-authenticated mutations.
# ===========================================================================


@pytest.mark.asyncio
async def test_cookie_mutation_without_origin_is_blocked(
    client: AsyncClient, admin_user: User
) -> None:
    """A cookie-authenticated POST with no Origin header is blocked (403, M-02).

    Args:
        client: The unauthenticated ASGI client.
        admin_user: The seeded administrator whose real cookie is presented.
    """
    token = await SignIn(client, admin_user.user_id, SEED_PASSWORD)

    response = await client.post(LOGOUT_PATH, headers=AuthHeaders(token))

    assert response.status_code == HTTP_FORBIDDEN


@pytest.mark.asyncio
async def test_cookie_mutation_with_disallowed_origin_is_blocked(
    client: AsyncClient, admin_user: User
) -> None:
    """A cookie-authenticated POST from a non-allow-listed Origin is blocked (M-02).

    Args:
        client: The unauthenticated ASGI client.
        admin_user: The seeded administrator whose real cookie is presented.
    """
    token = await SignIn(client, admin_user.user_id, SEED_PASSWORD)

    response = await client.post(
        LOGOUT_PATH,
        headers=AuthHeaders(token, DISALLOWED_ORIGIN),
    )

    assert response.status_code == HTTP_FORBIDDEN


@pytest.mark.asyncio
async def test_cookie_mutation_with_allowed_origin_succeeds(
    client: AsyncClient, admin_user: User
) -> None:
    """A cookie-authenticated POST from the allow-listed Origin succeeds (M-02).

    Args:
        client: The unauthenticated ASGI client.
        admin_user: The seeded administrator whose real cookie is presented.
    """
    token = await SignIn(client, admin_user.user_id, SEED_PASSWORD)

    response = await client.post(
        LOGOUT_PATH,
        headers=AuthHeaders(token, ALLOWED_ORIGIN),
    )

    assert response.status_code == HTTP_OK


@pytest.mark.asyncio
async def test_safe_method_needs_no_origin(
    client: AsyncClient, admin_user: User
) -> None:
    """A cookie-authenticated GET needs no Origin (CSRF applies only to writes).

    Args:
        client: The unauthenticated ASGI client.
        admin_user: The seeded administrator whose real cookie is presented.
    """
    token = await SignIn(client, admin_user.user_id, SEED_PASSWORD)

    response = await client.get(MENU_PATH, headers=AuthHeaders(token))

    assert response.status_code == HTTP_OK


# ===========================================================================
# M-01 -- password case-sensitivity and brute-force throttling through login.
# ===========================================================================


@pytest.mark.asyncio
async def test_login_rejects_wrong_case_password(
    client: AsyncClient, admin_user: User
) -> None:
    """A wrong-CASE password fails end to end through the login endpoint (M-01).

    The seed hash is of ``PASSWORD``; signing on with ``password`` must fail
    (401) because the password is verified over exact bytes, and the body is the
    generic anti-enumeration message.

    Args:
        client: The unauthenticated ASGI client.
        admin_user: The seeded administrator (ADMIN001).
    """
    response = await client.post(
        LOGIN_PATH,
        json={"user_id": admin_user.user_id, "password": SEED_PASSWORD_WRONG_CASE},
    )

    assert response.status_code == HTTP_UNAUTHORIZED


@pytest.mark.asyncio
async def test_repeated_failures_trigger_lockout(
    client: AsyncClient, admin_user: User
) -> None:
    """Consecutive credential failures trip a generic 429 lockout (M-01).

    After ``settings.LOGIN_MAX_ATTEMPTS`` wrong-password attempts for the same
    ``(user id, client IP)`` pair, the next attempt is rejected with HTTP 429 --
    and, critically, EVEN A CORRECT PASSWORD is refused while locked, proving the
    throttle short-circuits before the credential check. The 429 body does not
    reveal whether the user id exists.

    Args:
        client: The unauthenticated ASGI client.
        admin_user: The seeded administrator (ADMIN001).
    """
    for _ in range(settings.LOGIN_MAX_ATTEMPTS):
        failed = await client.post(
            LOGIN_PATH,
            json={"user_id": admin_user.user_id, "password": WRONG_PASSWORD},
        )
        assert failed.status_code == HTTP_UNAUTHORIZED

    # The pair is now locked: even the CORRECT password is refused with 429.
    lockedOut = await client.post(
        LOGIN_PATH,
        json={"user_id": admin_user.user_id, "password": SEED_PASSWORD},
    )
    assert lockedOut.status_code == HTTP_TOO_MANY_REQUESTS


@pytest.mark.asyncio
async def test_blank_field_submission_does_not_count_toward_lockout(
    client: AsyncClient, admin_user: User
) -> None:
    """Blank-field (422) submissions never advance the throttle counter (M-01).

    A missing password is a client-side edit (HTTP 422), not a password guess,
    so any number of them must leave the throttle untouched: a subsequent
    correct sign-on still succeeds.

    Args:
        client: The unauthenticated ASGI client.
        admin_user: The seeded administrator (ADMIN001).
    """
    for _ in range(settings.LOGIN_MAX_ATTEMPTS + 2):
        blank = await client.post(
            LOGIN_PATH,
            json={"user_id": admin_user.user_id, "password": ""},
        )
        # The schema rejects a blank password before the service runs (422).
        assert blank.status_code != HTTP_TOO_MANY_REQUESTS

    # The counter never advanced, so a correct sign-on still works.
    success = await client.post(
        LOGIN_PATH,
        json={"user_id": admin_user.user_id, "password": SEED_PASSWORD},
    )
    assert success.status_code == HTTP_OK


@pytest.mark.asyncio
async def test_successful_login_resets_failure_counter(
    client: AsyncClient, admin_user: User
) -> None:
    """A successful sign-on clears accumulated failures (M-01).

    Fewer-than-threshold failures followed by a success must reset the counter,
    so a later run of the same number of failures does not prematurely lock out.

    Args:
        client: The unauthenticated ASGI client.
        admin_user: The seeded administrator (ADMIN001).
    """
    belowThreshold = settings.LOGIN_MAX_ATTEMPTS - 1
    for _ in range(belowThreshold):
        await client.post(
            LOGIN_PATH,
            json={"user_id": admin_user.user_id, "password": WRONG_PASSWORD},
        )

    # A success resets the counter for this (user id, client IP) pair.
    reset = await client.post(
        LOGIN_PATH,
        json={"user_id": admin_user.user_id, "password": SEED_PASSWORD},
    )
    assert reset.status_code == HTTP_OK

    # Another below-threshold run of failures still does not lock out...
    for _ in range(belowThreshold):
        stillOpen = await client.post(
            LOGIN_PATH,
            json={"user_id": admin_user.user_id, "password": WRONG_PASSWORD},
        )
        assert stillOpen.status_code == HTTP_UNAUTHORIZED


@pytest.mark.asyncio
async def test_lockout_expires_after_window(
    client: AsyncClient, admin_user: User
) -> None:
    """A lockout expires after the configured window, re-opening sign-on (M-01).

    Uses an injected fake clock to advance time past
    ``settings.LOGIN_LOCKOUT_SECONDS`` without sleeping: after the window the
    correct password authenticates again.

    Args:
        client: The unauthenticated ASGI client.
        admin_user: The seeded administrator (ADMIN001).
    """
    fakeNow = {"value": 1000.0}
    loginRateLimiter.SetClock(lambda: fakeNow["value"])

    for _ in range(settings.LOGIN_MAX_ATTEMPTS):
        await client.post(
            LOGIN_PATH,
            json={"user_id": admin_user.user_id, "password": WRONG_PASSWORD},
        )
    # Locked now.
    locked = await client.post(
        LOGIN_PATH,
        json={"user_id": admin_user.user_id, "password": SEED_PASSWORD},
    )
    assert locked.status_code == HTTP_TOO_MANY_REQUESTS

    # Advance the clock beyond the lockout window; the window resets.
    fakeNow["value"] += settings.LOGIN_LOCKOUT_SECONDS + 1
    reopened = await client.post(
        LOGIN_PATH,
        json={"user_id": admin_user.user_id, "password": SEED_PASSWORD},
    )
    assert reopened.status_code == HTTP_OK
