# Real-authentication evidence for the protected RESOURCE APIs (QA finding
# M-20). The other resource suites (accounts / cards / transactions / users /
# billpay / reports) authenticate their protected-success paths through the
# ``get_current_user`` dependency OVERRIDE (fixtures ``admin_client`` /
# ``regular_client``), which is convenient for narrow authorization checks but
# BYPASSES the production credential path. This suite closes that evidence gap:
# it signs on through the REAL ``POST /auth/login`` endpoint and chains the
# ISSUED session credential into own / other / admin protected resource
# requests, so the genuine chain runs end to end -- cookie parsing
# (ResolveRequestToken), token decode + claims extraction (DecodeAccessToken),
# the M-02 session-version (sver) check inside get_current_user, the
# require_admin role gate, and the CSRF Origin guard on cookie-authenticated
# writes.
#
# Provenance / traceability (Ochs Rule): the routes exercised port the CardDemo
# online programs -- COMEN01C (``GET /menu``), COADM01C (``GET /admin/menu``),
# COACTVWC (``GET /accounts/{acctId}``, tx CAVW), and COUSR00C/COUSR01C
# (``GET``/``POST /admin/users``, admin-gated CU00/CU01). This file is
# REFERENCE-derived only; the legacy ``app/`` tree is never modified.
#
# Role model (QA finding C-02, AAP 0.4.4 / 0.8.1): legacy CardDemo authorization
# is ROLE-based (operators ADMIN001='A' / USER0001='U'; there is NO per-user
# account-ownership binding), and the frozen AAP mandates preserving that model.
# "own / other / admin" is therefore expressed here as role scope: a regular
# user reads role-accessible resources (own scope) but is forbidden from
# admin-gated resources (the "other/elevated" scope), while an administrator
# reaches both. Each assertion pins one EXACT status code (M-21 discipline).
"""End-to-end real-login authorization evidence for protected resource APIs.

Unlike the override-driven resource suites, every test here presents a
genuinely ISSUED credential (from a live ``POST /auth/login``) on the base
``client`` -- so the real ``get_current_user`` / ``require_admin`` / CSRF chain
is exercised, not a dependency override. Safe GET requests carry only the
cookie; state-changing POSTs additionally carry an allow-listed ``Origin`` so
the CSRF guard passes and the ONLY remaining gate is authorization.

Fixtures consumed (from ``tests/conftest.py``): ``client`` (base unauthenticated
ASGI client whose ``get_db`` override shares the per-test session),
``admin_user`` / ``regular_user`` (seeded identities), ``admin_auth_headers`` /
``regular_auth_headers`` (cookie headers carrying a real issued token),
``real_login`` (async callable that logs in and returns cookie[/Origin] headers),
and ``seed_data`` (golden-master rows for the account data route). Test function
and fixture parameter names are ``snake_case`` -- the documented pytest
framework-contract exception.
"""

from __future__ import annotations

import pytest
from httpx import AsyncClient

from app.core.config import settings

# ---------------------------------------------------------------------------
# Module constants (ALL_UPPERCASE per the Ochs Rule 0.8.2). Every path is built
# from ``settings.API_V1_PREFIX`` so the ``/api/v1`` mount prefix is never
# hardcoded; the seed identities/password come from the README golden master.
# ---------------------------------------------------------------------------
MENU_PATH = f"{settings.API_V1_PREFIX}/menu"
ADMIN_MENU_PATH = f"{settings.API_V1_PREFIX}/admin/menu"
USERS_PATH = f"{settings.API_V1_PREFIX}/admin/users"
ACCOUNTS_PATH = f"{settings.API_V1_PREFIX}/accounts"

# Golden-master account seeded from ``app/data/ASCII/acctdata.txt`` row 1; its
# significant leading zeros (the VSAM ACCT-ID key) are preserved as a string.
SEED_ACCT_ID = "00000000001"
ACCOUNT_VIEW_PATH = f"{ACCOUNTS_PATH}/{SEED_ACCT_ID}"

# Seed identities/password (README golden master; the fixtures store the bcrypt
# hash -- never a real credential).
SEED_ADMIN_USER_ID = "ADMIN001"
SEED_REGULAR_USER_ID = "USER0001"
SEED_PASSWORD = "PASSWORD"

# A brand-new user id created by the admin real-login write-path evidence. It is
# absent from the seed data, so the create cannot collide with a seeded row.
NEW_USER_ID = "TSTUSR20"

# The sole allow-listed browser origin (settings default http://localhost:3000).
# Required on cookie-authenticated writes so the CSRF Origin guard passes,
# leaving authorization as the only gate a POST test can trip.
ALLOWED_ORIGIN = settings.BACKEND_CORS_ORIGINS[0]

# Exact status codes (Ochs no-magic-numbers; M-21 exact-status discipline).
HTTP_OK = 200
HTTP_CREATED = 201
HTTP_UNAUTHORIZED = 401
HTTP_FORBIDDEN = 403


def _MakeUserCreatePayload() -> dict:
    """Return a fresh, fully valid ``UserCreate`` body for ``POST /admin/users``.

    The body is deliberately VALID (all required fields, an allowed
    ``user_type``) so that a 403 in the regular-user write test can only be the
    admin gate -- never a disguised 422 body-validation error.

    Returns:
        A new dict suitable as the JSON body of ``POST /admin/users``.
    """
    return {
        "user_id": NEW_USER_ID,
        "first_name": "Real",
        "last_name": "Login",
        "password": SEED_PASSWORD,
        "user_type": "U",
    }


# ===========================================================================
# Admin real-login -- reaches admin-gated and shared resources (own+admin scope).
# ===========================================================================
@pytest.mark.asyncio
async def test_admin_real_login_reaches_admin_menu(
    client: AsyncClient, admin_auth_headers: dict
) -> None:
    """A real admin token authorizes the admin menu (COADM01C, 200).

    Args:
        client: The base ASGI client (real get_current_user / require_admin run).
        admin_auth_headers: Cookie headers carrying a real ADMIN001 token.
    """
    response = await client.get(ADMIN_MENU_PATH, headers=admin_auth_headers)

    assert response.status_code == HTTP_OK


@pytest.mark.asyncio
async def test_admin_real_login_reaches_users_list(
    client: AsyncClient, admin_auth_headers: dict
) -> None:
    """A real admin token authorizes the admin user list (COUSR00C, 200).

    Args:
        client: The base ASGI client.
        admin_auth_headers: Cookie headers carrying a real ADMIN001 token.
    """
    response = await client.get(USERS_PATH, headers=admin_auth_headers)

    assert response.status_code == HTTP_OK


@pytest.mark.asyncio
async def test_admin_real_login_reaches_seeded_account(
    client: AsyncClient, admin_auth_headers: dict, seed_data: None
) -> None:
    """A real admin token authorizes the account view (COACTVWC / CAVW, 200).

    Args:
        client: The base ASGI client.
        admin_auth_headers: Cookie headers carrying a real ADMIN001 token.
        seed_data: Golden-master rows so ``SEED_ACCT_ID`` exists.
    """
    response = await client.get(ACCOUNT_VIEW_PATH, headers=admin_auth_headers)

    assert response.status_code == HTTP_OK


@pytest.mark.asyncio
async def test_admin_real_login_creates_user_write_path(
    client: AsyncClient, admin_user, real_login
) -> None:
    """A real admin token authorizes a WRITE on an admin resource (COUSR01C, 201).

    Exercises the full cookie-authenticated mutation path under a genuine
    credential: the CSRF Origin guard passes (allow-listed Origin) and the
    require_admin gate admits the administrator, so the create returns 201.

    Args:
        client: The base ASGI client.
        admin_user: The seeded administrator to sign on as.
        real_login: Async callable that logs in and returns cookie+Origin headers.
    """
    headers = await real_login(
        SEED_ADMIN_USER_ID, SEED_PASSWORD, origin=ALLOWED_ORIGIN
    )

    response = await client.post(
        USERS_PATH, json=_MakeUserCreatePayload(), headers=headers
    )

    assert response.status_code == HTTP_CREATED


# ===========================================================================
# Regular real-login -- reaches role-accessible resources (own scope).
# ===========================================================================
@pytest.mark.asyncio
async def test_regular_real_login_reaches_regular_menu(
    client: AsyncClient, regular_auth_headers: dict
) -> None:
    """A real regular token authorizes the regular menu (COMEN01C, 200).

    Args:
        client: The base ASGI client.
        regular_auth_headers: Cookie headers carrying a real USER0001 token.
    """
    response = await client.get(MENU_PATH, headers=regular_auth_headers)

    assert response.status_code == HTTP_OK


@pytest.mark.asyncio
async def test_regular_real_login_reaches_seeded_account(
    client: AsyncClient, regular_auth_headers: dict, seed_data: None
) -> None:
    """A real regular token reads a seeded account (role-based; CAVW, 200).

    Under the preserved role-based model (C-02) the account view is gated by
    authentication only (``get_current_user``), with no per-user ownership
    binding, so a regular user reads it successfully.

    Args:
        client: The base ASGI client.
        regular_auth_headers: Cookie headers carrying a real USER0001 token.
        seed_data: Golden-master rows so ``SEED_ACCT_ID`` exists.
    """
    response = await client.get(ACCOUNT_VIEW_PATH, headers=regular_auth_headers)

    assert response.status_code == HTTP_OK


# ===========================================================================
# Regular real-login -- FORBIDDEN on admin-gated resources ("other/elevated").
# The real user_type claim -- not an override -- drives these 403s.
# ===========================================================================
@pytest.mark.asyncio
async def test_regular_real_login_forbidden_on_admin_menu(
    client: AsyncClient, regular_auth_headers: dict
) -> None:
    """A real regular token is forbidden from the admin menu (F-002, 403).

    Args:
        client: The base ASGI client.
        regular_auth_headers: Cookie headers carrying a real USER0001 token.
    """
    response = await client.get(ADMIN_MENU_PATH, headers=regular_auth_headers)

    assert response.status_code == HTTP_FORBIDDEN


@pytest.mark.asyncio
async def test_regular_real_login_forbidden_on_users_list(
    client: AsyncClient, regular_auth_headers: dict
) -> None:
    """A real regular token is forbidden from the admin user list (F-002, 403).

    Args:
        client: The base ASGI client.
        regular_auth_headers: Cookie headers carrying a real USER0001 token.
    """
    response = await client.get(USERS_PATH, headers=regular_auth_headers)

    assert response.status_code == HTTP_FORBIDDEN


@pytest.mark.asyncio
async def test_regular_real_login_forbidden_on_user_create(
    client: AsyncClient, regular_user, real_login
) -> None:
    """A real regular token is forbidden from an admin WRITE (F-002, 403).

    The Origin is allow-listed (so CSRF passes) and the body is fully valid (so
    a 422 is impossible), leaving the require_admin gate as the ONLY thing that
    can reject -- proving the real ``user_type='U'`` claim, not an override,
    drives the 403 on a state-changing admin resource request.

    Args:
        client: The base ASGI client.
        regular_user: The seeded regular user to sign on as.
        real_login: Async callable that logs in and returns cookie+Origin headers.
    """
    headers = await real_login(
        SEED_REGULAR_USER_ID, SEED_PASSWORD, origin=ALLOWED_ORIGIN
    )

    response = await client.post(
        USERS_PATH, json=_MakeUserCreatePayload(), headers=headers
    )

    assert response.status_code == HTTP_FORBIDDEN


# ===========================================================================
# No credential -- protected resources reject with 401 (missing-token path).
# ===========================================================================
@pytest.mark.asyncio
async def test_unauthenticated_denied_on_regular_menu(
    client: AsyncClient,
) -> None:
    """A protected GET with NO credential returns 401 (real missing-token path).

    Args:
        client: The base (unauthenticated) ASGI client.
    """
    response = await client.get(MENU_PATH)

    assert response.status_code == HTTP_UNAUTHORIZED


@pytest.mark.asyncio
async def test_unauthenticated_denied_on_seeded_account(
    client: AsyncClient, seed_data: None
) -> None:
    """An account GET with NO credential returns 401 even when the row exists.

    Seeding proves the 401 is the authentication gate, not a 404: the resource
    is present, yet an unauthenticated caller never reaches it.

    Args:
        client: The base (unauthenticated) ASGI client.
        seed_data: Golden-master rows so ``SEED_ACCT_ID`` exists.
    """
    response = await client.get(ACCOUNT_VIEW_PATH)

    assert response.status_code == HTTP_UNAUTHORIZED


@pytest.mark.asyncio
async def test_unauthenticated_denied_on_users_list(
    client: AsyncClient,
) -> None:
    """The admin user list with NO credential returns 401.

    Args:
        client: The base (unauthenticated) ASGI client.
    """
    response = await client.get(USERS_PATH)

    assert response.status_code == HTTP_UNAUTHORIZED
