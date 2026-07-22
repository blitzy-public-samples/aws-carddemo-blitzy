# Integration tests for the POST /auth/login endpoint (app/api/v1/auth.py).
# Traceability: app/cbl/COSGN00C.cbl PROCESS-ENTER-KEY + READ-USER-SEC-FILE.
#   (QA issue C8: the legacy port surfaced two DIFFERENT 401 bodies -- "User not
#   found. Try again ..." versus "Wrong Password. Try again ..." -- which let an
#   attacker enumerate which user ids exist. AAP 0.1.1 makes closing that oracle
#   mandatory, so the unknown-user and wrong-password paths now return ONE shared
#   generic message. These tests pin that indistinguishability.)
"""Integration tests for the sign-on endpoint's credential-error responses.

These tests pin the QA Issue C8 security fix: the unknown-user and the
wrong-password sign-on failures MUST be indistinguishable to the caller -- the
same HTTP 401 status AND the same response body -- so the API cannot be used as
a user-id enumeration oracle. They also confirm the fix did not regress the
happy path (valid seed credentials still authenticate).

The tests use the unauthenticated ``client`` fixture from ``tests.conftest`` (an
httpx ASGI client wired to ``app.main:app`` that shares the test ``db_session``)
together with the ``admin_user`` fixture, which seeds the administrator identity
(``ADMIN001``) into that same session so the request handler can read it.
"""

from __future__ import annotations

import pytest
from httpx import AsyncClient

from app.core.config import settings
from app.models.user import User
from app.services.auth_service import (
    MSG_INVALID_CREDENTIALS,
    MSG_USER_NOT_FOUND,
    MSG_WRONG_PASSWORD,
)

# ---------------------------------------------------------------------------
# Module constants (ALL_UPPERCASE per the Ochs Rule 0.8.2).
# ---------------------------------------------------------------------------

# Fully-qualified login path: the version-1 API prefix (mounted once by
# app.main) plus the auth sub-router's own resource path. Built from settings so
# the API version prefix is never hardcoded here.
LOGIN_PATH = f"{settings.API_V1_PREFIX}/auth/login"

# HTTP status codes asserted below.
HTTP_OK = 200
HTTP_UNAUTHORIZED = 401

# Key of the error message in the domain-error JSON body ({"detail": "..."}).
DETAIL_KEY = "detail"

# SEED-ONLY golden-master password from the README (never a real credential);
# the ``admin_user`` fixture stores its bcrypt hash. Mirrors conftest.SEED_PASSWORD.
SEED_PASSWORD = "PASSWORD"

# A syntactically valid (exactly 8 alphanumeric characters, per the LoginRequest
# schema edit) user id that is NOT seeded, so the service reaches the
# user-not-found branch rather than a schema-validation 422.
UNKNOWN_USER_ID = "ZZZZ9999"

# A syntactically valid (non-blank, <= 8 chars) but incorrect password, so the
# service reaches the wrong-password branch for an existing user.
WRONG_PASSWORD = "BADPASS1"


@pytest.mark.asyncio
async def test_login_succeeds_with_seed_admin_credentials(
    client: AsyncClient, admin_user: User
) -> None:
    """Valid seed credentials still authenticate (happy path not regressed).

    Args:
        client: The unauthenticated httpx ASGI client wired to ``app.main:app``.
        admin_user: The seeded administrator identity (``ADMIN001``).
    """
    response = await client.post(
        LOGIN_PATH,
        json={"user_id": admin_user.user_id, "password": SEED_PASSWORD},
    )

    assert response.status_code == HTTP_OK
    body = response.json()
    assert body["user_id"] == admin_user.user_id
    assert body["user_type"] == admin_user.user_type


@pytest.mark.asyncio
async def test_login_unknown_user_returns_generic_401(
    client: AsyncClient, admin_user: User
) -> None:
    """An unknown user id returns HTTP 401 with the generic message.

    ``admin_user`` is requested so the ``users`` relation is populated and the
    lookup cleanly returns "no such id" (rather than an infrastructure error).

    Args:
        client: The unauthenticated httpx ASGI client.
        admin_user: Seeds ``ADMIN001`` so the table is non-empty.
    """
    response = await client.post(
        LOGIN_PATH,
        json={"user_id": UNKNOWN_USER_ID, "password": SEED_PASSWORD},
    )

    assert response.status_code == HTTP_UNAUTHORIZED
    assert response.json()[DETAIL_KEY] == MSG_INVALID_CREDENTIALS
    # The legacy enumeration-leaking message must NOT reach the caller.
    assert response.json()[DETAIL_KEY] != MSG_USER_NOT_FOUND


@pytest.mark.asyncio
async def test_login_wrong_password_returns_generic_401(
    client: AsyncClient, admin_user: User
) -> None:
    """A wrong password for an existing user returns the generic 401.

    Args:
        client: The unauthenticated httpx ASGI client.
        admin_user: The seeded administrator identity (``ADMIN001``).
    """
    response = await client.post(
        LOGIN_PATH,
        json={"user_id": admin_user.user_id, "password": WRONG_PASSWORD},
    )

    assert response.status_code == HTTP_UNAUTHORIZED
    assert response.json()[DETAIL_KEY] == MSG_INVALID_CREDENTIALS
    # The legacy enumeration-leaking message must NOT reach the caller.
    assert response.json()[DETAIL_KEY] != MSG_WRONG_PASSWORD


@pytest.mark.asyncio
async def test_login_unknown_user_and_wrong_password_are_indistinguishable(
    client: AsyncClient, admin_user: User
) -> None:
    """The two failure responses are byte-for-byte identical (the C8 core).

    An unknown user id and a wrong password for an existing user MUST produce the
    same status code and the same body, so the endpoint cannot be used to tell
    which user ids exist.

    Args:
        client: The unauthenticated httpx ASGI client.
        admin_user: The seeded administrator identity (``ADMIN001``).
    """
    unknownUserResponse = await client.post(
        LOGIN_PATH,
        json={"user_id": UNKNOWN_USER_ID, "password": SEED_PASSWORD},
    )
    wrongPasswordResponse = await client.post(
        LOGIN_PATH,
        json={"user_id": admin_user.user_id, "password": WRONG_PASSWORD},
    )

    assert unknownUserResponse.status_code == wrongPasswordResponse.status_code
    assert unknownUserResponse.status_code == HTTP_UNAUTHORIZED
    assert unknownUserResponse.json() == wrongPasswordResponse.json()
    assert unknownUserResponse.json()[DETAIL_KEY] == MSG_INVALID_CREDENTIALS
