# Unit tests for app.services.user_admin_service.UserAdminService
# Traceability: app/cbl/COUSR00C.cbl (CU00), COUSR01C.cbl (CU01),
#   COUSR02C.cbl (CU02), COUSR03C.cbl (CU03)
"""Service-layer unit tests for :class:`app.services.user_admin_service.UserAdminService`.

This module pins the admin user-management business rules ported 1:1 from the
legacy CICS online programs:

    COUSR00C (CU00) -> ListUsers   -- paginated USRSEC browse.
    COUSR01C (CU01) -> AddUser     -- add a user, hashing the password.
    COUSR02C (CU02) -> UpdateUser  -- update a user, re-hashing on change.
    COUSR03C (CU03) -> DeleteUser  -- delete a user.

What these tests verify (one behavior per test):

* **Password security uplift (AAP 0.7.7).** The legacy plaintext ``SEC-USR-PWD``
  is replaced by a bcrypt ``password_hash``. ``AddUser`` stores a hash that is
  never equal to the submitted plaintext yet still verifies through
  :func:`app.core.security.VerifyPassword`, and the read/response DTOs
  (:class:`~app.schemas.user.UserRead` / ``UserSummary``) never expose a
  ``password`` or ``password_hash`` field of any kind.
* **Verbatim COBOL screen messages.** Required-field, duplicate, not-found and
  no-change messages are asserted character-for-character (including the space
  before the ellipsis in "Please modify to update ...").
* **Specific domain exceptions.** ``ConflictError`` (duplicate id),
  ``DomainValidationError`` (blank field / no-op update) and ``NotFoundError``
  (absent id) are asserted explicitly -- never a bare :class:`Exception`.

Scope note (admin gating). The "admin-only" restriction for these operations is
enforced at the router layer via ``Depends(require_admin)`` (see
``app/core/dependencies.py``), NOT inside the service methods, so these
service-level tests call the methods directly and deliberately do not assert
admin gating (that is covered by the router/authorization and integration
tests). Each service method accepts an OPTIONAL ``currentUser`` that defaults to
``None``; omitting it makes the service-level admin check a no-op, which is what
these tests do.

Execution model. The suite runs under ``asyncio_mode = "auto"`` (see
``backend/pyproject.toml``), so tests are plain ``async def`` coroutines with no
``@pytest.mark.asyncio`` decorator. Each test uses the ``db_session`` fixture
from ``tests/conftest.py`` (a function-scoped :class:`AsyncSession` whose tables
are truncated after every test) plus the seeded ``admin_user`` / ``regular_user``
fixtures where an existing row is required.

Ochs naming (AAP 0.8.2 / 0.8.3): test functions keep snake_case names (the
pytest contract), the shared helper is PascalCase (``MessageOf``), local
variables are camelCase and module constants are ALL_UPPERCASE.

Security note: the seed identities (``ADMIN001`` / ``USER0001``) and every test
password below are SEED/TEST-ONLY inputs mirroring the README golden-master
credentials -- they are never real secrets and must never be treated as such.
"""

from __future__ import annotations

import pytest

from app.core.exceptions import (
    ConflictError,
    DomainValidationError,
    NotFoundError,
)
from app.core.security import VerifyPassword
from app.models import User
from app.schemas.common import PaginationParams
from app.schemas.user import UserCreate, UserUpdate
from app.services.user_admin_service import UserAdminService

# ---------------------------------------------------------------------------
# Expected verbatim COBOL screen messages (Ochs Rule: constants ALL_UPPERCASE).
# Asserted character-for-character against the service's parity strings, which
# are themselves cited to the originating COUSR0*C program lines.
# ---------------------------------------------------------------------------
EXPECTED_FIRST_NAME_REQUIRED = "First Name can NOT be empty..."  # COUSR01C L120
EXPECTED_USER_ID_REQUIRED = "User ID can NOT be empty..."  # COUSR02C L182 / 03C L179
EXPECTED_USER_NOT_FOUND = "User ID NOT found..."  # COUSR02C L342 / 03C L289
EXPECTED_USER_ALREADY_EXISTS = "User ID already exist..."  # COUSR01C L263
EXPECTED_NO_CHANGES = "Please modify to update ..."  # COUSR02C L239 (space before "...")

# Substring proving the delete confirmation is the "deleted" variant (COUSR03C
# L320 builds "User <id> has been deleted ...").
DELETED_CONFIRMATION_FRAGMENT = "deleted"

# ---------------------------------------------------------------------------
# Seed identity mirrored from tests/conftest.py (TEST-ONLY, never a real secret).
# ---------------------------------------------------------------------------
SEED_ADMIN_USER_ID = "ADMIN001"

# ---------------------------------------------------------------------------
# New-user inputs for the write tests. Every id and password is <= 8 characters
# to honor the copybook widths SEC-USR-ID X(08) and SEC-USR-PWD X(08) enforced
# by the UserCreate/UserUpdate schemas (validators.USER_ID_LENGTH / PASSWORD_LENGTH).
# ---------------------------------------------------------------------------
NEW_USER_ID = "NEWUSER1"
NEW_USER_FIRST_NAME = "NEW"
NEW_USER_LAST_NAME = "USER"
NEW_USER_PASSWORD = "SECRET12"
NEW_USER_TYPE = "U"

MISSING_FIRST_NAME_USER_ID = "NEWUSER2"
ADD_TEST_PASSWORD = "PW123456"
ABSENT_USER_ID = "NOSUCH01"

DELETE_USER_ID = "DELME001"
DELETE_USER_FIRST_NAME = "DEL"
DELETE_USER_LAST_NAME = "ME"
DELETE_USER_PASSWORD = "PW123456"

CHANGED_FIRST_NAME = "CHANGED"

# ---------------------------------------------------------------------------
# QA finding F2 (password casing): a user created with a LOWERCASE password
# must be stored so it verifies against the uppercased form -- the write side
# applies the same FUNCTION UPPER-CASE the sign-on path applies (COSGN00C
# L135-136), so "created with P can sign on with P" holds. The lowercase input
# and its uppercase equivalent are both <= 8 chars (SEC-USR-PWD X(08)).
LOWER_CASE_USER_ID = "LOWER001"
LOWER_CASE_PASSWORD = "secret12"
LOWER_CASE_PASSWORD_UPPER = "SECRET12"

# ---------------------------------------------------------------------------
# QA finding F3 (last-administrator invariant): defined LOCALLY (never imported
# from the service) so the assertion pins the expected wording independently of
# the service constant. Verbatim match to user_admin_service.MSG_LAST_ADMIN.
EXPECTED_LAST_ADMIN = "Cannot remove the last administrator account..."

# A SECOND administrator used by the "not over-blocking" tests: with two admins
# present, demoting/deleting one is allowed because at least one admin remains.
SECOND_ADMIN_USER_ID = "ADMIN002"
SECOND_ADMIN_FIRST_NAME = "SECOND"
SECOND_ADMIN_LAST_NAME = "ADMIN"
SECOND_ADMIN_PASSWORD = "PW123456"
ADMIN_USER_TYPE = "A"
REGULAR_USER_TYPE = "U"

# Pagination window and the minimum number of users the list test seeds.
LIST_PAGE = 1
LIST_PAGE_SIZE = 7
MIN_EXPECTED_USERS = 2


def MessageOf(error):
    """Return the human-readable text carried by a CardDemo domain error.

    Every domain exception under ``app.core.exceptions`` derives from
    ``CardDemoError``, which stores its text on a ``message`` attribute and also
    forwards it to ``Exception`` (so ``str(error)`` matches). This helper reads
    ``message`` when present and falls back to ``str(error)`` otherwise, giving
    every message assertion in this module one stable accessor.

    Args:
        error: The raised exception instance (typically from ``excInfo.value``).

    Returns:
        The exception's message text as a ``str``.
    """
    return getattr(error, "message", None) or str(error)


# ===========================================================================
# Phase A -- AddUser success + password hashing (never plaintext); duplicate;
# required-field validation. (COUSR01C, CU01)
# ===========================================================================


async def test_add_user_success_hashes_password(db_session):
    """AddUser persists a bcrypt hash (never the plaintext) and leaks no password.

    Proves the AAP 0.7.7 security uplift: the stored ``password_hash`` differs
    from the submitted plaintext yet verifies through ``VerifyPassword``, and the
    returned ``UserRead`` exposes neither ``password`` nor ``password_hash``.
    """
    service = UserAdminService()
    userCreate = UserCreate(
        user_id=NEW_USER_ID,
        first_name=NEW_USER_FIRST_NAME,
        last_name=NEW_USER_LAST_NAME,
        password=NEW_USER_PASSWORD,
        user_type=NEW_USER_TYPE,
    )

    created = await service.AddUser(db_session, userCreate)

    assert created.user_id == NEW_USER_ID
    createdFields = created.model_dump()
    assert "password" not in createdFields
    assert "password_hash" not in createdFields

    stored = await db_session.get(User, NEW_USER_ID)
    assert stored is not None
    assert stored.password_hash != NEW_USER_PASSWORD
    assert VerifyPassword(NEW_USER_PASSWORD, stored.password_hash) is True


async def test_add_user_lowercase_password_is_uppercased(db_session):
    """A user created with a lowercase password can sign on with it (F2).

    QA finding F2: the sign-on path uppercases the submitted password
    (COSGN00C L135-136) before bcrypt verification, but the create path formerly
    hashed the password verbatim. A user created with "secret12" was therefore
    stored as hash("secret12") yet, at sign-on, "secret12" was uppercased to
    "SECRET12" and never matched -- a silent, permanent lockout. The write side
    now applies the same uppercasing, so the stored hash verifies against the
    UPPERCASE form (what sign-on actually checks). Asserted both ways: the stored
    hash verifies the uppercase password and NOT the raw lowercase input.
    """
    service = UserAdminService()
    userCreate = UserCreate(
        user_id=LOWER_CASE_USER_ID,
        first_name=NEW_USER_FIRST_NAME,
        last_name=NEW_USER_LAST_NAME,
        password=LOWER_CASE_PASSWORD,
        user_type=NEW_USER_TYPE,
    )

    await service.AddUser(db_session, userCreate)

    stored = await db_session.get(User, LOWER_CASE_USER_ID)
    assert stored is not None
    # What sign-on verifies (the uppercased password) must match the stored hash.
    assert VerifyPassword(LOWER_CASE_PASSWORD_UPPER, stored.password_hash) is True
    # The raw lowercase input is NOT what is stored (proving normalization ran).
    assert VerifyPassword(LOWER_CASE_PASSWORD, stored.password_hash) is False


async def test_add_user_duplicate_raises_conflict(db_session, admin_user):
    """Adding an already-existing user id raises ConflictError with the COBOL text.

    Uses the seeded ``admin_user`` (``ADMIN001``); re-adding that id reproduces
    the COUSR01C DUPKEY/DUPREC path ("User ID already exist...").
    """
    service = UserAdminService()
    userCreate = UserCreate(
        user_id=SEED_ADMIN_USER_ID,
        first_name="X",
        last_name="Y",
        password=ADD_TEST_PASSWORD,
        user_type="A",
    )

    with pytest.raises(ConflictError) as excInfo:
        await service.AddUser(db_session, userCreate)

    assert MessageOf(excInfo.value) == EXPECTED_USER_ALREADY_EXISTS


async def test_add_user_missing_first_name_raises_validation(db_session):
    """AddUser rejects a blank first name with its verbatim required-field message.

    ``UserCreate`` applies the mandatory edit at construction (its ``first_name``
    field validator raises, surfacing as ``pydantic.ValidationError``), so a
    blank first name never reaches the service through ordinary construction.
    ``model_construct`` bypasses that schema-layer edit so this test exercises
    the SERVICE's own ``_ValidateAddFields`` check and its verbatim message
    ("First Name can NOT be empty..."), which is the behavior under test here.
    """
    service = UserAdminService()
    userCreate = UserCreate.model_construct(
        user_id=MISSING_FIRST_NAME_USER_ID,
        first_name="",
        last_name=NEW_USER_LAST_NAME,
        password=ADD_TEST_PASSWORD,
        user_type=NEW_USER_TYPE,
    )

    with pytest.raises(DomainValidationError) as excInfo:
        await service.AddUser(db_session, userCreate)

    assert MessageOf(excInfo.value) == EXPECTED_FIRST_NAME_REQUIRED


# ===========================================================================
# Phase B -- GetUser: found, empty id, absent id.
# (COUSR02C / COUSR03C shared READ USRSEC pre-read)
# ===========================================================================


async def test_get_user_found(db_session, admin_user):
    """GetUser returns the requested user as a password-free UserRead."""
    service = UserAdminService()

    user = await service.GetUser(db_session, SEED_ADMIN_USER_ID)

    assert user.user_id == SEED_ADMIN_USER_ID
    userFields = user.model_dump()
    assert "password" not in userFields
    assert "password_hash" not in userFields


async def test_get_user_empty_raises_validation(db_session):
    """GetUser with a blank id raises DomainValidationError ("User ID can NOT be empty...")."""
    service = UserAdminService()

    with pytest.raises(DomainValidationError) as excInfo:
        await service.GetUser(db_session, "")

    assert MessageOf(excInfo.value) == EXPECTED_USER_ID_REQUIRED


async def test_get_user_absent_raises_not_found(db_session):
    """GetUser for a non-existent id raises NotFoundError ("User ID NOT found...")."""
    service = UserAdminService()

    with pytest.raises(NotFoundError) as excInfo:
        await service.GetUser(db_session, ABSENT_USER_ID)

    assert MessageOf(excInfo.value) == EXPECTED_USER_NOT_FOUND


# ===========================================================================
# Phase C -- UpdateUser: no-op rejected; successful change. (COUSR02C, CU02)
# ===========================================================================


async def test_update_user_no_change_raises_validation(db_session, admin_user):
    """UpdateUser rejects a no-op edit with "Please modify to update ...".

    The update DTO mirrors the seeded ``ADMIN001`` values exactly (same first
    and last name, same user type, no password), so nothing differs and the
    service raises the COUSR02C no-change message verbatim.
    """
    service = UserAdminService()
    userUpdate = UserUpdate(
        first_name=admin_user.first_name,
        last_name=admin_user.last_name,
        user_type=admin_user.user_type,
    )

    with pytest.raises(DomainValidationError) as excInfo:
        await service.UpdateUser(db_session, SEED_ADMIN_USER_ID, userUpdate)

    assert MessageOf(excInfo.value) == EXPECTED_NO_CHANGES


async def test_update_user_success(db_session, admin_user):
    """UpdateUser applies a changed first name and leaks no password field.

    Only the first name changes (last name and user type mirror the seed), so
    the change-detection guard passes and the persisted record reflects the new
    value while the returned ``UserRead`` still carries no password.
    """
    service = UserAdminService()
    userUpdate = UserUpdate(
        first_name=CHANGED_FIRST_NAME,
        last_name=admin_user.last_name,
        user_type=admin_user.user_type,
    )

    updated = await service.UpdateUser(db_session, SEED_ADMIN_USER_ID, userUpdate)

    assert updated.first_name == CHANGED_FIRST_NAME
    updatedFields = updated.model_dump()
    assert "password" not in updatedFields
    assert "password_hash" not in updatedFields


async def test_update_demote_last_admin_raises_conflict(db_session, admin_user):
    """Demoting the SOLE administrator is blocked with 409 (QA finding F3).

    The seeded ``admin_user`` (ADMIN001) is the only ``user_type='A'`` row in
    this isolated session. Changing its type to 'U' would leave the system with
    zero administrators -- a total admin lockout. The last-administrator
    invariant raises ``ConflictError`` (surfaced as HTTP 409) with the modern
    guard message before any change is persisted.
    """
    service = UserAdminService()
    userUpdate = UserUpdate(
        first_name=admin_user.first_name,
        last_name=admin_user.last_name,
        user_type=REGULAR_USER_TYPE,
    )

    with pytest.raises(ConflictError) as excInfo:
        await service.UpdateUser(db_session, SEED_ADMIN_USER_ID, userUpdate)

    assert MessageOf(excInfo.value) == EXPECTED_LAST_ADMIN
    # The invariant fires BEFORE persistence: ADMIN001 is still an admin.
    stored = await db_session.get(User, SEED_ADMIN_USER_ID)
    assert stored.user_type == ADMIN_USER_TYPE


async def test_update_demote_admin_allowed_with_second_admin(db_session, admin_user):
    """Demoting an admin is allowed while another admin remains (F3 no over-block).

    With a second administrator present, demoting ADMIN001 to 'U' still leaves
    one administrator, so the invariant must NOT fire -- proving the guard blocks
    only the genuinely last admin and never over-restricts ordinary role edits.
    """
    service = UserAdminService()
    await service.AddUser(
        db_session,
        UserCreate(
            user_id=SECOND_ADMIN_USER_ID,
            first_name=SECOND_ADMIN_FIRST_NAME,
            last_name=SECOND_ADMIN_LAST_NAME,
            password=SECOND_ADMIN_PASSWORD,
            user_type=ADMIN_USER_TYPE,
        ),
    )
    userUpdate = UserUpdate(
        first_name=admin_user.first_name,
        last_name=admin_user.last_name,
        user_type=REGULAR_USER_TYPE,
    )

    updated = await service.UpdateUser(db_session, SEED_ADMIN_USER_ID, userUpdate)

    assert updated.user_type == REGULAR_USER_TYPE


# ===========================================================================
# Phase D -- DeleteUser: success (confirmed by subsequent NotFound); absent id.
# (COUSR03C, CU03)
# ===========================================================================


async def test_delete_user_success(db_session):
    """DeleteUser removes the user; a subsequent GetUser proves it is gone.

    A fresh user is added first, then deleted. The verified service returns a
    :class:`~app.schemas.common.MessageResponse` confirmation ("User <id> has
    been deleted ...") rather than ``None``, so this test asserts that
    confirmation carries the id and the "deleted" action word. The authoritative
    post-condition of a successful delete is that the row is gone, proven by the
    ``NotFoundError`` raised on the follow-up read.
    """
    service = UserAdminService()
    seedCreate = UserCreate(
        user_id=DELETE_USER_ID,
        first_name=DELETE_USER_FIRST_NAME,
        last_name=DELETE_USER_LAST_NAME,
        password=DELETE_USER_PASSWORD,
        user_type="U",
    )
    await service.AddUser(db_session, seedCreate)

    result = await service.DeleteUser(db_session, DELETE_USER_ID)

    assert result is not None
    assert DELETE_USER_ID in result.message
    assert DELETED_CONFIRMATION_FRAGMENT in result.message

    with pytest.raises(NotFoundError):
        await service.GetUser(db_session, DELETE_USER_ID)


async def test_delete_user_absent_raises_not_found(db_session):
    """Deleting a non-existent id raises NotFoundError ("User ID NOT found...")."""
    service = UserAdminService()

    with pytest.raises(NotFoundError) as excInfo:
        await service.DeleteUser(db_session, ABSENT_USER_ID)

    assert MessageOf(excInfo.value) == EXPECTED_USER_NOT_FOUND


async def test_delete_last_admin_raises_conflict(db_session, admin_user):
    """Deleting the SOLE administrator is blocked with 409 (QA finding F3).

    Deleting the only ``user_type='A'`` row (the seeded ADMIN001) would leave
    zero administrators and permanently lock every admin-gated screen (user CRUD,
    admin menu). The last-administrator invariant raises ``ConflictError``
    (HTTP 409) and the row must survive.
    """
    service = UserAdminService()

    with pytest.raises(ConflictError) as excInfo:
        await service.DeleteUser(db_session, SEED_ADMIN_USER_ID)

    assert MessageOf(excInfo.value) == EXPECTED_LAST_ADMIN
    # The invariant fires BEFORE deletion: ADMIN001 still exists.
    stored = await db_session.get(User, SEED_ADMIN_USER_ID)
    assert stored is not None
    assert stored.user_type == ADMIN_USER_TYPE


async def test_delete_admin_allowed_with_second_admin(db_session, admin_user):
    """Deleting an admin is allowed while another admin remains (F3 no over-block).

    With two administrators present, deleting ADMIN001 leaves one admin, so the
    invariant must NOT fire -- proving the guard blocks only the last admin.
    """
    service = UserAdminService()
    await service.AddUser(
        db_session,
        UserCreate(
            user_id=SECOND_ADMIN_USER_ID,
            first_name=SECOND_ADMIN_FIRST_NAME,
            last_name=SECOND_ADMIN_LAST_NAME,
            password=SECOND_ADMIN_PASSWORD,
            user_type=ADMIN_USER_TYPE,
        ),
    )

    result = await service.DeleteUser(db_session, SEED_ADMIN_USER_ID)

    assert result is not None
    assert SEED_ADMIN_USER_ID in result.message
    with pytest.raises(NotFoundError):
        await service.GetUser(db_session, SEED_ADMIN_USER_ID)


# ===========================================================================
# Phase E -- ListUsers: paginated summaries that never leak a password.
# (COUSR00C, CU00)
# ===========================================================================


async def test_list_users_returns_summaries(db_session, admin_user, regular_user):
    """ListUsers returns UserSummary rows for the seeded users with no password.

    With both seed identities present, the first page contains at least two
    summaries and none of them expose a ``password`` or ``password_hash`` field.
    """
    service = UserAdminService()

    result = await service.ListUsers(
        db_session,
        PaginationParams(page=LIST_PAGE, page_size=LIST_PAGE_SIZE),
    )

    assert len(result.items) >= MIN_EXPECTED_USERS
    assert all(
        "password" not in item.model_dump()
        and "password_hash" not in item.model_dump()
        for item in result.items
    )
