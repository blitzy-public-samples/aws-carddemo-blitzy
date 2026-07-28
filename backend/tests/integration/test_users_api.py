# Integration tests for the admin user-management API.
# Ported from legacy CICS online programs COUSR00C (list, CU00), COUSR01C (add,
# CU01), COUSR02C (update, CU02), COUSR03C (delete, CU03).
# Endpoints: GET/POST/PUT/DELETE /api/v1/admin/users (AAP §0.5.5).
# All endpoints are admin-gated (require_admin, F-002): regular users get 403.
# Verifies CRUD happy paths, duplicate->409, and that password/password_hash
# NEVER appear in any response. Record layout CSUSR01Y (plaintext SEC-USR-PWD
# uplifted to bcrypt password_hash, §0.7.7).
"""End-to-end integration tests for the admin ``/admin/users`` REST surface.

These tests exercise the fully wired FastAPI application (``app.main:app``)
through an in-process httpx ASGI client, against a real PostgreSQL-backed
session, so they validate the router -> service -> repository -> ORM stack as a
whole rather than any single layer in isolation.

Coverage maps 1:1 to the four legacy admin user programs (Minimal Change
Clause, AAP 0.8.1):

    * ``COUSR00C`` (CU00) -- list users        -> ``GET  /admin/users``
    * ``COUSR01C`` (CU01) -- add a user         -> ``POST /admin/users``
    * ``COUSR02C`` (CU02) -- update a user      -> ``PUT  /admin/users/{userId}``
    * ``COUSR03C`` (CU03) -- delete a user      -> ``DELETE /admin/users/{userId}``

Three behaviors are pinned across the suite:

    1.  Admin gating (F-002): every verb is guarded by the router-level
        ``Depends(require_admin)``; a regular (``user_type='U'``) caller receives
        HTTP 403 and an unauthenticated caller receives HTTP 401.
    2.  CRUD parity: the happy-path create/read/update/delete flow returns the
        exact success statuses (201 create, 200 read/update, 204 delete) and the
        legacy-parity error statuses (409 duplicate, 404 missing, 422 no-change).
    3.  Secret safety (AAP 0.7.7 / 0.7.8): the plaintext ``SEC-USR-PWD`` is
        uplifted to a bcrypt ``password_hash`` that is never echoed -- no
        response body (or its raw text) may contain ``password`` or
        ``password_hash``.

Fixtures come from the parent ``backend/tests/conftest.py`` (there is no local
``conftest`` for this package): ``admin_client`` (authenticated as the seeded
``ADMIN001``), ``regular_client`` (authenticated as the seeded ``USER0001``),
``client`` (unauthenticated), ``admin_user`` / ``regular_user`` (the seeded ORM
rows), and ``db_session`` (the same session the request handlers use, so the
tests can inspect created/updated/deleted rows directly).

Ochs naming (AAP 0.8.2 / 0.8.3): the module/file name is snake_case and the
pytest test functions keep snake_case names as the framework contract; helper
functions are PascalCase (``AssertNoPasswordKeys``), local variables camelCase
(``createPayload`` / ``responseBody``), and module constants ALL_UPPERCASE
(``USERS_URL`` / ``NEW_USER_ID`` / ``SEED_PASSWORD``).
"""

from __future__ import annotations

import pytest
from httpx import AsyncClient
from sqlalchemy import select

from app.core.config import settings
from app.models.user import User

# Mark every coroutine test in this module as an asyncio test. Under
# ``asyncio_mode = "auto"`` pytest-asyncio would collect these anyway, but the
# explicit module-level marker documents the async contract (mirroring the
# per-function marker used in ``tests/api/test_auth_logout.py``) and keeps the
# ``pytest`` import load-bearing.
pytestmark = pytest.mark.asyncio

# ---------------------------------------------------------------------------
# Endpoint + identity constants (Ochs Rule 0.8.2: constants are ALL_UPPERCASE).
#
# USERS_URL is assembled from settings so the version prefix (``/api/v1``) is
# never hardcoded here; ``app.main`` mounts the users router under
# ``settings.API_V1_PREFIX`` + the router's own ``/admin/users`` prefix.
# ---------------------------------------------------------------------------
USERS_URL = f"{settings.API_V1_PREFIX}/admin/users"
# Sign-on endpoint, used by the QA Issue 18 create->sign-on round-trip below to
# prove a freshly created credential is usable (no "dead" credential).
LOGIN_URL = f"{settings.API_V1_PREFIX}/auth/login"

# 8-character id (SEC-USR-ID X(08)) for a user created within a single test.
NEW_USER_ID = "TSTUSR01"
# SEED-ONLY golden-master password from the README; NEVER a real credential and
# never asserted anywhere in cleartext beyond being sent as request input.
SEED_PASSWORD = "PASSWORD"
# The seeded administrator id (the ``admin_user`` fixture, ``user_type='A'``).
ADMIN_ID = "ADMIN001"
# An 8-character id guaranteed absent from the ``users`` table (404 fixtures).
NOSUCH_ID = "NOSUCH99"

# ---------------------------------------------------------------------------
# Precise HTTP status codes (asserted exactly -- never a broad "not 2xx" check).
# ---------------------------------------------------------------------------
HTTP_OK = 200
HTTP_CREATED = 201
HTTP_NO_CONTENT = 204
HTTP_BAD_REQUEST = 400
HTTP_UNAUTHORIZED = 401
HTTP_FORBIDDEN = 403
HTTP_NOT_FOUND = 404
HTTP_CONFLICT = 409
HTTP_UNPROCESSABLE = 422

# Accepted band for a rejected-input response: FastAPI request validation and
# the ported ``DomainValidationError`` both surface as 422, but 400 is accepted
# too so the assertion stays robust to the mapping (AAP: "missing -> 400/422").
INPUT_REJECTED_STATUSES = (HTTP_BAD_REQUEST, HTTP_UNPROCESSABLE)


# ===========================================================================
# Helpers (PascalCase per Ochs; these are NOT fixtures -- call them directly).
# ===========================================================================
def AssertNoPasswordKeys(payload: object) -> None:
    """Recursively assert no ``password`` / ``password_hash`` key is present.

    Walks a decoded JSON payload (nested dicts and lists) and fails if any
    mapping key contains the substring ``password`` in any case -- which covers
    both ``password`` and ``password_hash``. This enforces the security
    invariant (AAP 0.7.7 / 0.7.8) that the read DTOs :class:`UserRead` /
    :class:`UserSummary` never expose a secret.

    Args:
        payload: A JSON-decoded value (``dict`` / ``list`` / scalar).
    """
    if isinstance(payload, dict):
        for itemKey, itemValue in payload.items():
            assert "password" not in str(itemKey).lower(), (
                f"response leaked a password-related key: {itemKey!r}"
            )
            AssertNoPasswordKeys(itemValue)
    elif isinstance(payload, list):
        for listItem in payload:
            AssertNoPasswordKeys(listItem)


def MakeCreatePayload() -> dict:
    """Return a fresh, valid ``UserCreate`` request body.

    All five mandatory add-user fields (COUSR01C order) are supplied. Callers
    copy-and-tweak a single field for their scenario, so this helper itself
    stays at zero parameters (Ochs Rule: <= 4 parameters per method).

    Returns:
        A new dict suitable as the JSON body of ``POST /admin/users``.
    """
    return {
        "user_id": NEW_USER_ID,
        "first_name": "Test",
        "last_name": "User",
        "password": SEED_PASSWORD,
        "user_type": "U",
    }


def MakeUpdatePayload() -> dict:
    """Return a fresh, valid ``UserUpdate`` request body.

    The three mandatory update fields are supplied; ``password`` is optional on
    update and is intentionally omitted so an unchanged password is left as-is.
    The name/type defaults match :func:`MakeCreatePayload`, so a caller that
    changes exactly one field produces a genuine, detectable change.

    Returns:
        A new dict suitable as the JSON body of ``PUT /admin/users/{userId}``.
    """
    return {
        "first_name": "Test",
        "last_name": "User",
        "user_type": "U",
    }



# ===========================================================================
# Phase 2 -- Admin gating (F-002). A regular user is rejected with HTTP 403 on
# EVERY verb, proving the router-level ``Depends(require_admin)`` gate; an
# unauthenticated caller is rejected with HTTP 401. The POST/PUT bodies are
# deliberately VALID so the ONLY thing that can fail is the admin gate -- a 403
# here therefore cannot be a disguised body-validation 422.
# ===========================================================================
async def test_users_list_forbidden_for_regular(regular_client: AsyncClient) -> None:
    """GET /admin/users is forbidden for a regular user (COUSR00C admin gate)."""
    responseBody = await regular_client.get(USERS_URL)

    assert responseBody.status_code == HTTP_FORBIDDEN


async def test_users_create_forbidden_for_regular(regular_client: AsyncClient) -> None:
    """POST /admin/users is forbidden for a regular user (COUSR01C admin gate).

    The body is a valid ``UserCreate`` so the 403 can only come from the admin
    gate, never from request-body validation.
    """
    createPayload = MakeCreatePayload()

    responseBody = await regular_client.post(USERS_URL, json=createPayload)

    assert responseBody.status_code == HTTP_FORBIDDEN


async def test_users_get_forbidden_for_regular(regular_client: AsyncClient) -> None:
    """GET /admin/users/{id} is forbidden for a regular user (admin gate)."""
    responseBody = await regular_client.get(f"{USERS_URL}/{ADMIN_ID}")

    assert responseBody.status_code == HTTP_FORBIDDEN


async def test_users_update_forbidden_for_regular(regular_client: AsyncClient) -> None:
    """PUT /admin/users/{id} is forbidden for a regular user (COUSR02C gate).

    The body is a valid ``UserUpdate`` so the 403 can only come from the admin
    gate, never from request-body validation.
    """
    updatePayload = MakeUpdatePayload()

    responseBody = await regular_client.put(
        f"{USERS_URL}/{ADMIN_ID}",
        json=updatePayload,
    )

    assert responseBody.status_code == HTTP_FORBIDDEN


async def test_users_delete_forbidden_for_regular(regular_client: AsyncClient) -> None:
    """DELETE /admin/users/{id} is forbidden for a regular user (COUSR03C gate)."""
    responseBody = await regular_client.delete(f"{USERS_URL}/{ADMIN_ID}")

    assert responseBody.status_code == HTTP_FORBIDDEN


async def test_users_list_requires_auth(client: AsyncClient) -> None:
    """GET /admin/users requires authentication (401 without a session).

    The unauthenticated ``client`` carries no session cookie / bearer token, so
    ``get_current_user`` (which ``require_admin`` depends on) rejects the request
    with HTTP 401 before any handler body runs.
    """
    responseBody = await client.get(USERS_URL)

    assert responseBody.status_code == HTTP_UNAUTHORIZED


# ===========================================================================
# Phase 3 -- Admin CRUD happy paths. The ``admin_client`` fixture authenticates
# as ADMIN001 and transitively seeds that administrator row, so admin-gated
# routes succeed. The shared ``db_session`` lets a test read back the exact rows
# the request handlers created / deleted.
# ===========================================================================
async def test_users_list_admin_ok(
    admin_client: AsyncClient,
    admin_user: User,
    regular_user: User,
) -> None:
    """GET /admin/users returns a password-free page listing the seeded users.

    Reproduces the COUSR00C USRSEC browse. Both seeded identities (ADMIN001 and
    USER0001) must appear among the paginated items, the envelope must carry the
    ``items`` list, and no row may expose a password.

    Args:
        admin_client: Client authenticated as the administrator.
        admin_user: The seeded administrator row (ensures ADMIN001 exists).
        regular_user: The seeded regular row (ensures USER0001 also exists).
    """
    response = await admin_client.get(USERS_URL)

    assert response.status_code == HTTP_OK
    responseBody = response.json()
    assert "items" in responseBody
    AssertNoPasswordKeys(responseBody)

    listedIds = {item["user_id"] for item in responseBody["items"]}
    assert admin_user.user_id in listedIds
    assert regular_user.user_id in listedIds


async def test_users_search_spans_all_pages_not_just_current(
    admin_client: AsyncClient,
    admin_user: User,
    regular_user: User,
) -> None:
    """GET /admin/users?user_id=<prefix> searches the WHOLE table (QA I23).

    Regression for the "search filters only the current page" defect: the admin
    "Search User ID" box previously narrowed only the rows already loaded on the
    client, so a matching id sitting on any other page was reported "not found".
    The search now runs server-side over the entire user table.

    The scenario forces the target off the current page. A distinctive user
    ``ZEBRA001`` sorts LAST, and ``page_size=1`` makes page 1 of the UNfiltered
    browse contain only ``ADMIN001`` (``ZEBRA001`` is on a later page). Searching
    for the ``ZEBRA`` prefix must still return ``ZEBRA001`` on page 1 -- proving
    the search spans all pages -- and the pagination metadata must describe the
    FILTERED set (``total_items == 1``).

    Args:
        admin_client: Client authenticated as the administrator.
        admin_user: The seeded administrator row (ensures ADMIN001 exists).
        regular_user: The seeded regular row (ensures USER0001 also exists).
    """
    searchUserId = "ZEBRA001"
    createPayload = MakeCreatePayload()
    createPayload["user_id"] = searchUserId
    createResponse = await admin_client.post(USERS_URL, json=createPayload)
    assert createResponse.status_code == HTTP_CREATED

    # Baseline: page 1 of the UNfiltered browse (page_size=1) is ADMIN001, so
    # the target is genuinely NOT on the current page.
    unfilteredFirstPage = await admin_client.get(
        USERS_URL, params={"page": 1, "page_size": 1}
    )
    assert unfilteredFirstPage.status_code == HTTP_OK
    unfilteredIds = {row["user_id"] for row in unfilteredFirstPage.json()["items"]}
    assert searchUserId not in unfilteredIds

    # Search for the target's prefix on page 1 (same tiny page window): the
    # whole-table server-side search must surface it despite it living on a
    # later page of the unfiltered browse.
    searchResponse = await admin_client.get(
        USERS_URL, params={"page": 1, "page_size": 1, "user_id": "ZEBRA"}
    )
    assert searchResponse.status_code == HTTP_OK
    searchBody = searchResponse.json()
    AssertNoPasswordKeys(searchBody)
    searchIds = {row["user_id"] for row in searchBody["items"]}
    assert searchUserId in searchIds
    # Pagination metadata reflects the FILTERED set, not the whole table.
    assert searchBody["total_items"] == 1


async def test_users_search_is_case_insensitive(
    admin_client: AsyncClient,
    admin_user: User,
) -> None:
    """GET /admin/users?user_id=zeb matches ``ZEBRA001`` (case-insensitive I23).

    The stored ids are canonical uppercase, so the search intentionally uses a
    case-insensitive ``ILIKE 'prefix%'`` predicate -- a lowercase search term
    still finds the uppercase id (matching the prior client filter, which
    lower-cased both sides).

    Args:
        admin_client: Client authenticated as the administrator.
        admin_user: The seeded administrator row (ensures ADMIN001 exists).
    """
    searchUserId = "ZEBRA001"
    createPayload = MakeCreatePayload()
    createPayload["user_id"] = searchUserId
    createResponse = await admin_client.post(USERS_URL, json=createPayload)
    assert createResponse.status_code == HTTP_CREATED

    response = await admin_client.get(USERS_URL, params={"user_id": "zeb"})

    assert response.status_code == HTTP_OK
    matchedIds = {row["user_id"] for row in response.json()["items"]}
    assert searchUserId in matchedIds


async def test_users_search_escapes_like_wildcards(
    admin_client: AsyncClient,
    admin_user: User,
) -> None:
    """GET /admin/users?user_id=ZEB%25 treats ``%`` literally (injection-safe I23).

    The prefix search uses ``autoescape=True``, so a SQL ``LIKE`` wildcard in the
    operator's input is matched LITERALLY rather than as "match anything" (Ochs:
    sanitize/validate user-supplied data). ``ZEBRA001`` starts with ``ZEB`` but
    NOT with the literal text ``ZEB%``, so an escaped-wildcard search returns no
    rows -- if the ``%`` leaked through unescaped it would match ``ZEBRA001`` and
    the assertion would fail.

    Args:
        admin_client: Client authenticated as the administrator.
        admin_user: The seeded administrator row (ensures ADMIN001 exists).
    """
    createPayload = MakeCreatePayload()
    createPayload["user_id"] = "ZEBRA001"
    createResponse = await admin_client.post(USERS_URL, json=createPayload)
    assert createResponse.status_code == HTTP_CREATED

    response = await admin_client.get(USERS_URL, params={"user_id": "ZEB%"})

    assert response.status_code == HTTP_OK
    responseBody = response.json()
    assert responseBody["items"] == []
    assert responseBody["total_items"] == 0


async def test_create_user_ok(admin_client: AsyncClient, db_session) -> None:
    """POST /admin/users creates a user (201) and stores a bcrypt password_hash.

    Reproduces the COUSR01C add-user flow. The response is a password-free
    :class:`UserRead`; the persisted row must carry a bcrypt ``password_hash``
    that is NOT the submitted plaintext (AAP 0.7.7).

    Args:
        admin_client: Client authenticated as the administrator.
        db_session: The shared session used to inspect the created row.
    """
    createPayload = MakeCreatePayload()

    response = await admin_client.post(USERS_URL, json=createPayload)

    assert response.status_code == HTTP_CREATED
    responseBody = response.json()
    assert responseBody["user_id"] == NEW_USER_ID
    assert responseBody["user_type"] == "U"
    AssertNoPasswordKeys(responseBody)

    # Inspect the persisted row directly: the password must be stored as a
    # bcrypt hash (a self-describing ``$2`` modular-crypt string), never as the
    # plaintext that was submitted.
    result = await db_session.execute(
        select(User).where(User.user_id == NEW_USER_ID)
    )
    createdUser = result.scalar_one_or_none()
    assert createdUser is not None
    assert createdUser.password_hash
    assert createdUser.password_hash != SEED_PASSWORD
    assert createdUser.password_hash.startswith("$2")


async def test_create_user_rejects_embedded_space_id(
    admin_client: AsyncClient,
) -> None:
    """POST /admin/users rejects an embedded-space user id (QA Issue 18).

    A user id is a database key, a URL path segment, and a token subject, so an
    interior space is an ambiguous, non-canonical identifier. The stricter
    no-space identifier edit rejects it with HTTP 422 (previously accepted).

    Args:
        admin_client: Client authenticated as the administrator.
    """
    createPayload = MakeCreatePayload()
    createPayload["user_id"] = "AB CD001"

    response = await admin_client.post(USERS_URL, json=createPayload)

    assert response.status_code == HTTP_UNPROCESSABLE


async def test_create_user_rejects_one_character_password(
    admin_client: AsyncClient,
) -> None:
    """POST /admin/users rejects a too-short (weak) password (QA Issue 18).

    SEC-USR-PWD is a fixed-width ``PIC X(08)`` field, so the exact-8 edit adds a
    meaningful minimum length symmetric with the exactly-8 user id. A
    one-character password (previously accepted) is now rejected with HTTP 422.

    Args:
        admin_client: Client authenticated as the administrator.
    """
    createPayload = MakeCreatePayload()
    createPayload["password"] = "x"

    response = await admin_client.post(USERS_URL, json=createPayload)

    assert response.status_code == HTTP_UNPROCESSABLE


async def test_create_lowercase_id_canonicalizes_and_signs_on(
    admin_client: AsyncClient, client: AsyncClient
) -> None:
    """A user created with a lower-case id is stored UPPERCASE and can sign on.

    QA Issue 18 (canonical identifier policy): the sign-on path applies the
    COSGN00C ``FUNCTION UPPER-CASE`` edit before its USRSEC lookup, so a
    lower-case id stored verbatim used to be an unusable ("dead") credential --
    the stored lower-case key never matched the uppercased sign-on lookup.
    Create now canonicalizes the id to upper case, so the stored key matches the
    sign-on lookup and the freshly created account authenticates. The password
    is verified over its EXACT bytes (M-01), so its case is preserved.

    Args:
        admin_client: Client authenticated as the administrator (creates).
        client: Unauthenticated client used to sign on as the new user.
    """
    lowerCaseId = "lower001"
    canonicalId = "LOWER001"
    createPayload = MakeCreatePayload()
    createPayload["user_id"] = lowerCaseId

    createResponse = await admin_client.post(USERS_URL, json=createPayload)

    # The stored / echoed id is the canonical uppercase form, not the input.
    assert createResponse.status_code == HTTP_CREATED
    assert createResponse.json()["user_id"] == canonicalId

    # The freshly created credential signs on (no dead credential). Sign on with
    # the ORIGINAL lower-case id: the sign-on path uppercases it to the same key.
    loginResponse = await client.post(
        LOGIN_URL, json={"user_id": lowerCaseId, "password": SEED_PASSWORD}
    )

    assert loginResponse.status_code == HTTP_OK
    assert loginResponse.json()["user_id"] == canonicalId


async def test_get_user_ok(admin_client: AsyncClient) -> None:
    """GET /admin/users/{id} returns the created user's identity (no password).

    Reproduces the ``READ USRSEC`` pre-read shared by COUSR02C / COUSR03C. The
    user is created first (there is no seed fixture for it), then read back.

    Args:
        admin_client: Client authenticated as the administrator.
    """
    createResponse = await admin_client.post(USERS_URL, json=MakeCreatePayload())
    assert createResponse.status_code == HTTP_CREATED

    response = await admin_client.get(f"{USERS_URL}/{NEW_USER_ID}")

    assert response.status_code == HTTP_OK
    responseBody = response.json()
    assert responseBody["user_id"] == NEW_USER_ID
    assert responseBody["first_name"] == "Test"
    assert responseBody["last_name"] == "User"
    assert responseBody["user_type"] == "U"
    AssertNoPasswordKeys(responseBody)


async def test_update_user_ok(admin_client: AsyncClient) -> None:
    """PUT /admin/users/{id} applies a genuine change and returns 200.

    Reproduces the COUSR02C update flow. The user is created, then updated with
    a changed ``first_name``; the response reflects the new value and exposes no
    password.

    Args:
        admin_client: Client authenticated as the administrator.
    """
    createResponse = await admin_client.post(USERS_URL, json=MakeCreatePayload())
    assert createResponse.status_code == HTTP_CREATED

    updatePayload = MakeUpdatePayload()
    updatePayload["first_name"] = "Updated"

    response = await admin_client.put(
        f"{USERS_URL}/{NEW_USER_ID}",
        json=updatePayload,
    )

    assert response.status_code == HTTP_OK
    responseBody = response.json()
    assert responseBody["user_id"] == NEW_USER_ID
    assert responseBody["first_name"] == "Updated"
    AssertNoPasswordKeys(responseBody)


async def test_delete_user_ok(admin_client: AsyncClient, db_session) -> None:
    """DELETE /admin/users/{id} removes the user (204) and a re-read 404s.

    Reproduces the COUSR03C delete flow. After a 204 No Content response, the
    row must be gone from the database and a subsequent GET must return 404.

    Args:
        admin_client: Client authenticated as the administrator.
        db_session: The shared session used to confirm the row is gone.
    """
    createResponse = await admin_client.post(USERS_URL, json=MakeCreatePayload())
    assert createResponse.status_code == HTTP_CREATED

    response = await admin_client.delete(f"{USERS_URL}/{NEW_USER_ID}")

    assert response.status_code == HTTP_NO_CONTENT

    # The row must be physically gone (the delete committed on the shared
    # session), and a fresh read of the same id must surface a 404.
    result = await db_session.execute(
        select(User).where(User.user_id == NEW_USER_ID)
    )
    assert result.scalar_one_or_none() is None

    getResponse = await admin_client.get(f"{USERS_URL}/{NEW_USER_ID}")
    assert getResponse.status_code == HTTP_NOT_FOUND



# ===========================================================================
# Phase 4 -- Error paths (precise status + verbatim legacy-parity messages).
# ===========================================================================
async def test_create_duplicate_user_conflict(admin_client: AsyncClient) -> None:
    """POST a duplicate user id returns 409 with the COUSR01C parity message.

    ADMIN001 is already seeded by the ``admin_client`` fixture, so adding it
    again reproduces the COUSR01C DUPKEY branch -> "User ID already exist...".

    Args:
        admin_client: Client authenticated as the administrator.
    """
    createPayload = MakeCreatePayload()
    createPayload["user_id"] = ADMIN_ID
    createPayload["user_type"] = "A"

    response = await admin_client.post(USERS_URL, json=createPayload)

    assert response.status_code == HTTP_CONFLICT
    assert "already exist" in response.text


async def test_create_user_missing_field(admin_client: AsyncClient) -> None:
    """POST with a blank mandatory field is rejected (400/422).

    A blank ``first_name`` is caught by the ported mandatory edit
    (``validators.ValidateRequired``) that the ``UserCreate`` schema runs before
    the service, so the surfaced message is the schema-level "First name must be
    supplied." (the service's "First Name can NOT be empty..." text is
    pre-empted). The assertion therefore checks the field is named rather than
    the exact service wording.

    Args:
        admin_client: Client authenticated as the administrator.
    """
    createPayload = MakeCreatePayload()
    createPayload["first_name"] = ""

    response = await admin_client.post(USERS_URL, json=createPayload)

    assert response.status_code in INPUT_REJECTED_STATUSES
    responseText = response.text.lower()
    assert "first name" in responseText or "first_name" in responseText


async def test_get_user_not_found(admin_client: AsyncClient) -> None:
    """GET an unknown user id returns 404 with the COUSR02C parity message.

    Args:
        admin_client: Client authenticated as the administrator.
    """
    response = await admin_client.get(f"{USERS_URL}/{NOSUCH_ID}")

    assert response.status_code == HTTP_NOT_FOUND
    assert "not found" in response.text.lower()


async def test_update_user_not_found(admin_client: AsyncClient) -> None:
    """PUT an unknown user id returns 404 (COUSR02C READ NOTFND branch).

    Args:
        admin_client: Client authenticated as the administrator.
    """
    updatePayload = MakeUpdatePayload()

    response = await admin_client.put(
        f"{USERS_URL}/{NOSUCH_ID}",
        json=updatePayload,
    )

    assert response.status_code == HTTP_NOT_FOUND
    assert "not found" in response.text.lower()


async def test_delete_user_not_found(admin_client: AsyncClient) -> None:
    """DELETE an unknown user id returns 404 (COUSR03C READ NOTFND branch).

    Args:
        admin_client: Client authenticated as the administrator.
    """
    response = await admin_client.delete(f"{USERS_URL}/{NOSUCH_ID}")

    assert response.status_code == HTTP_NOT_FOUND
    assert "not found" in response.text.lower()


async def test_update_user_no_change(admin_client: AsyncClient) -> None:
    """PUT with identical values is rejected 422 (COUSR02C no-change guard).

    Reproduces the COUSR02C "Please modify to update ..." guard: creating a user
    and then re-sending its exact current values (name/type unchanged, password
    omitted) must be rejected rather than silently succeeding as a no-op.

    Args:
        admin_client: Client authenticated as the administrator.
    """
    createResponse = await admin_client.post(USERS_URL, json=MakeCreatePayload())
    assert createResponse.status_code == HTTP_CREATED

    # MakeUpdatePayload() intentionally mirrors MakeCreatePayload()'s
    # name/type, so this PUT changes nothing and must trip the guard.
    response = await admin_client.put(
        f"{USERS_URL}/{NEW_USER_ID}",
        json=MakeUpdatePayload(),
    )

    assert response.status_code in INPUT_REJECTED_STATUSES
    assert "Please modify to update" in response.text



# ===========================================================================
# Phase 5 -- No-secret-leak sweep. The strongest, most direct assertion of the
# AAP 0.7.7 / 0.7.8 invariant: neither the structured payload nor its raw text
# may expose a password on any user response.
# ===========================================================================
async def test_users_never_leak_password(admin_client: AsyncClient) -> None:
    """No user response exposes ``password`` / ``password_hash`` (list + detail).

    Sweeps both the list endpoint (COUSR00C) and the single-user endpoint (the
    COUSR02C/03C pre-read) for the seeded administrator, asserting both the
    decoded structure (via :func:`AssertNoPasswordKeys`) and the raw response
    text are free of any password token.

    Args:
        admin_client: Client authenticated as the administrator.
    """
    listResponse = await admin_client.get(USERS_URL)
    assert listResponse.status_code == HTTP_OK
    AssertNoPasswordKeys(listResponse.json())
    assert "password" not in listResponse.text.lower()

    detailResponse = await admin_client.get(f"{USERS_URL}/{ADMIN_ID}")
    assert detailResponse.status_code == HTTP_OK
    AssertNoPasswordKeys(detailResponse.json())
    assert "password" not in detailResponse.text.lower()
