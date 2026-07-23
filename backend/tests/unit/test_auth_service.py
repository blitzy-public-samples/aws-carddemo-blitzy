# Unit tests for app.services.auth_service.AuthService
# Traceability: app/cbl/COSGN00C.cbl (sign-on, tx CC00)
"""Behavioral unit tests for :class:`app.services.auth_service.AuthService`.

This suite pins the sign-on business logic ported 1:1 from the legacy CICS
program ``app/cbl/COSGN00C.cbl`` (transaction ``CC00``). It asserts the exact
things the Minimal Change Clause (technical specification section 0.8.1)
requires to be preserved byte-for-byte, plus the single mandatory security
uplift:

1.  **Validation precedence and verbatim messages.** ``COSGN00C``'s
    ``PROCESS-ENTER-KEY`` reports a blank user id (``'Please enter User ID ...'``,
    L120) *before* a blank password (``'Please enter Password ...'``, L125), and
    its ``READ-USER-SEC-FILE`` reports an unknown user
    (``'User not found. Try again ...'``, L249) and a password mismatch
    (``'Wrong Password. Try again ...'``, L242). The order-proving tests below
    assert each message with exact string equality.
2.  **User id and password uppercasing.** The legacy flow applies
    ``FUNCTION UPPER-CASE`` to *both* the entered user id and the entered
    password (L132-136) before the USRSEC lookup and the credential compare. The
    Phase C tests prove the service still does this by signing on with a
    lowercase id / password and observing success against the uppercase seed.
3.  **bcrypt verification replacing the plaintext compare.** The legacy
    ``IF SEC-USR-PWD = WS-USER-PWD`` (L223) is now
    :func:`app.core.security.VerifyPassword`; a wrong password therefore fails
    the same way, surfacing the verbatim ``'Wrong Password. Try again ...'``.

Construction note (why :func:`BuildLoginRequest` exists):
    :class:`~app.schemas.auth.LoginRequest` enforces its own field edits at
    Pydantic construction time -- the user id must be non-blank, exactly eight
    characters, and alphanumeric, and the password must be non-blank. The
    intentionally-invalid inputs used by the precedence tests (an empty user id,
    an empty password, and a short unknown id) are therefore rejected by the
    schema *before* the service ever runs, and because the request base strips
    surrounding whitespace, a single-space workaround is rejected too. To test
    the service layer's *own* precedence edits in isolation, those requests are
    built with :meth:`pydantic.BaseModel.model_construct`, which bypasses schema
    validation; valid inputs are still built normally so they continue to
    exercise the schema.

Environment and isolation:
    The tests run under ``cd backend && pytest`` with ``asyncio_mode = "auto"``
    (async tests need no marker). The parent ``backend/tests/conftest.py`` sets
    ``SECRET_KEY`` / ``ENVIRONMENT`` before any ``app.*`` import and supplies the
    ``db_session`` / ``admin_user`` / ``regular_user`` fixtures; this module
    deliberately re-sets no environment variables. Each test seeds only what it
    needs, relying on the conftest's per-test table truncation for isolation.

Ochs naming (technical specification sections 0.8.2 / 0.8.3): snake_case
test-function names (the pytest discovery contract), PascalCase helper functions
(:func:`MessageOf`, :func:`BuildLoginRequest`), camelCase local variables,
ALL_UPPERCASE module-level constants, 4-space indentation, and one asserted
behavior per test. The sample seed credentials (``ADMIN001`` / ``USER0001`` /
``PASSWORD``) are the documented, seed-only golden-master values used purely as
test inputs -- never real or production credentials.
"""

import pydantic
import pytest

from app.core.exceptions import AuthenticationError, DomainValidationError
from app.schemas.auth import LoginRequest
from app.services.auth_service import AuthService

# ---------------------------------------------------------------------------
# Verbatim COSGN00C sign-on messages (ALL_UPPERCASE constants per the Ochs
# Rule). These are defined LOCALLY -- never imported from auth_service -- so the
# assertions pin the exact expected wording independently of the service's own
# constants (an assertion against the service's constant would be a tautology).
# Under the Minimal Change Clause these strings are never reworded or re-cased.
# ---------------------------------------------------------------------------
MSG_ENTER_USER_ID = "Please enter User ID ..."  # COSGN00C L120
MSG_ENTER_PASSWORD = "Please enter Password ..."  # COSGN00C L125
MSG_USER_NOT_FOUND = "User not found. Try again ..."  # COSGN00C L249
MSG_WRONG_PASSWORD = "Wrong Password. Try again ..."  # COSGN00C L242

# QA Issue C8 (anti-enumeration): the unknown-user and wrong-password paths both
# surface ONE shared generic message to the client, so a caller cannot tell a
# bad user id from a bad password. The legacy per-path wording above is retained
# only as an internal server-side log line. Defined locally (never imported from
# the service) so the assertions still pin the expected wording independently.
MSG_INVALID_CREDENTIALS = "Invalid user ID or password. Try again ..."

# Documented seed-only sample identities (README golden-master values). They are
# test inputs only and are never treated as real credentials. The password is
# the literal "PASSWORD" (already uppercase), which lets the Phase C tests prove
# uppercasing by signing on with the lowercase "password".
SEED_ADMIN_USER_ID = "ADMIN001"
SEED_REGULAR_USER_ID = "USER0001"
SEED_PASSWORD = "PASSWORD"

# Legacy role codes (COCOM01Y 88-levels CDEMO-USRTYP-ADMIN 'A' / -USER 'U').
ADMIN_USER_TYPE = "A"
REGULAR_USER_TYPE = "U"


def MessageOf(error):
    """Return the human-readable message carried by a CardDemo domain exception.

    Every CardDemo domain error derives from ``CardDemoError``, which stores the
    text on both ``error.message`` and ``str(error)``; reading ``message`` first
    (and falling back to ``str``) keeps the assertions robust to either design.

    Args:
        error: The raised domain exception instance to read the message from.

    Returns:
        The exception's message string.
    """
    return getattr(error, "message", None) or str(error)


def BuildLoginRequest(userId, password):
    """Build a :class:`LoginRequest`, bypassing schema edits only when required.

    Valid credentials are constructed normally so they still pass through the
    schema's field edits. Intentionally-invalid inputs (an empty user id, an
    empty password, or a short unknown id) are rejected by those edits at
    construction time; for them, ``model_construct`` builds the model without
    validation so the *service's* own precedence edits are what the test
    exercises.

    Args:
        userId: The user id to place on the request (may be intentionally blank
            or the wrong length for the precedence tests).
        password: The password to place on the request (may be intentionally
            blank for the precedence tests).

    Returns:
        A :class:`LoginRequest` carrying exactly the supplied values.
    """
    try:
        return LoginRequest(user_id=userId, password=password)
    except pydantic.ValidationError:
        return LoginRequest.model_construct(user_id=userId, password=password)


# ===========================================================================
# Phase A -- validation precedence and verbatim failure messages
# (COSGN00C PROCESS-ENTER-KEY L119-129 and READ-USER-SEC-FILE L221-257).
# ===========================================================================


async def test_login_empty_user_id_raises_validation(db_session):
    """A blank user id is rejected first (COSGN00C L120)."""
    service = AuthService()
    req = BuildLoginRequest("", SEED_PASSWORD)
    with pytest.raises(DomainValidationError) as excInfo:
        await service.Login(db_session, req)
    assert MessageOf(excInfo.value) == MSG_ENTER_USER_ID


async def test_login_empty_password_raises_validation(db_session):
    """A blank password is rejected next, proving the user-id check precedes it.

    No user is seeded: reaching the password message (rather than
    user-not-found) confirms the presence edits run in the legacy order and
    before any USRSEC lookup (COSGN00C L125).
    """
    service = AuthService()
    req = BuildLoginRequest(SEED_ADMIN_USER_ID, "")
    with pytest.raises(DomainValidationError) as excInfo:
        await service.Login(db_session, req)
    assert MessageOf(excInfo.value) == MSG_ENTER_PASSWORD


async def test_login_unknown_user_raises_authentication(db_session):
    """An unknown user id is rejected (COSGN00C L249 not-found branch).

    The session is empty (no seeded user), so the USRSEC lookup misses and the
    RESP-13 (NOTFND) branch is taken. Per QA Issue C8 the surfaced message is the
    shared generic credential message (the verbatim not-found wording remains an
    internal log line only), preventing user-id enumeration.
    """
    service = AuthService()
    req = BuildLoginRequest("NOSUCH", SEED_PASSWORD)
    with pytest.raises(AuthenticationError) as excInfo:
        await service.Login(db_session, req)
    assert MessageOf(excInfo.value) == MSG_INVALID_CREDENTIALS


async def test_login_unknown_user_performs_dummy_verify(db_session, monkeypatch):
    """The unknown-user path spends one bcrypt verification (QA finding F1).

    The legacy plaintext compare returned in constant time whether or not the
    user id existed. In the modern stack a *found* user triggers a bcrypt verify
    while a *missing* user id would otherwise skip it, and that timing gap is an
    enumeration oracle. ``auth_service.Login`` closes it by calling
    ``security.VerifyPasswordDummy`` in the not-found branch. This installs a spy
    over that call and asserts it fires exactly once for an unknown user -- so the
    timing mitigation cannot be silently removed -- while the shared generic
    credential error is still raised.
    """
    dummyCalls = {"count": 0}

    def SpyDummyVerify():
        dummyCalls["count"] += 1

    monkeypatch.setattr(
        "app.services.auth_service.VerifyPasswordDummy", SpyDummyVerify
    )
    service = AuthService()
    req = BuildLoginRequest("NOSUCH", SEED_PASSWORD)
    with pytest.raises(AuthenticationError) as excInfo:
        await service.Login(db_session, req)
    assert dummyCalls["count"] == 1
    assert MessageOf(excInfo.value) == MSG_INVALID_CREDENTIALS


async def test_login_wrong_password_raises_authentication(db_session, admin_user):
    """A wrong password for an existing user is rejected (COSGN00C L242).

    The seeded ``admin_user`` is found, but bcrypt verification of the wrong
    password fails -- the modern replacement for the plaintext compare
    ``IF SEC-USR-PWD = WS-USER-PWD`` (COSGN00C L223). Per QA Issue C8 the surfaced
    message is the shared generic credential message (identical to the unknown-user
    path), so a bad password is indistinguishable from a bad user id.
    """
    service = AuthService()
    req = BuildLoginRequest(SEED_ADMIN_USER_ID, "WRONGPWD")
    with pytest.raises(AuthenticationError) as excInfo:
        await service.Login(db_session, req)
    assert MessageOf(excInfo.value) == MSG_INVALID_CREDENTIALS


# ===========================================================================
# Phase B -- successful sign-on returns the caller's identity and role
# (COSGN00C success branch L226-238; identity/role replaces COMMAREA).
# ===========================================================================


async def test_login_admin_success(db_session, admin_user):
    """A valid admin sign-on returns the admin identity and role 'A'."""
    service = AuthService()
    req = BuildLoginRequest(SEED_ADMIN_USER_ID, SEED_PASSWORD)
    response = await service.Login(db_session, req)
    assert response.user_id == SEED_ADMIN_USER_ID
    assert response.user_type == ADMIN_USER_TYPE


async def test_login_regular_success(db_session, regular_user):
    """A valid regular sign-on returns the regular identity and role 'U'."""
    service = AuthService()
    req = BuildLoginRequest(SEED_REGULAR_USER_ID, SEED_PASSWORD)
    response = await service.Login(db_session, req)
    assert response.user_id == SEED_REGULAR_USER_ID
    assert response.user_type == REGULAR_USER_TYPE


# ===========================================================================
# Phase C -- FUNCTION UPPER-CASE of both credentials (COSGN00C L132-136).
# ===========================================================================


async def test_login_user_id_is_uppercased(db_session, admin_user):
    """A lowercase user id is uppercased before the USRSEC lookup.

    Signing on as ``admin001`` resolves the uppercase-keyed ``ADMIN001`` seed
    row, and the response echoes the stored uppercase id -- proving the service
    applied ``FUNCTION UPPER-CASE`` to the user id (COSGN00C L132-134).
    """
    service = AuthService()
    req = BuildLoginRequest("admin001", SEED_PASSWORD)
    response = await service.Login(db_session, req)
    assert response.user_id == SEED_ADMIN_USER_ID


async def test_login_password_is_uppercased(db_session, admin_user):
    """A lowercase password is uppercased before bcrypt verification.

    The seed hash is of the uppercase ``PASSWORD``; signing on with the
    lowercase ``password`` succeeds only because the service uppercases it first
    (COSGN00C L135-136) -- had it not, bcrypt verification would fail and raise
    the wrong-password error instead.
    """
    service = AuthService()
    req = BuildLoginRequest(SEED_ADMIN_USER_ID, "password")
    response = await service.Login(db_session, req)
    assert response.user_id == SEED_ADMIN_USER_ID
    assert response.user_type == ADMIN_USER_TYPE
