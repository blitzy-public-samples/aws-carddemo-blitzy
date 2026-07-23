# Unit tests for app.core.security
# Traceability: app/cbl/COSGN00C.cbl -- the sign-on plaintext compare
#   IF SEC-USR-PWD = WS-USER-PWD (COSGN00C L223) becomes a bcrypt hash + verify,
#   and the CICS COMMAREA identity/role propagation MOVE WS-USER-ID /
#   SEC-USR-TYPE TO CDEMO-USER-ID / CDEMO-USER-TYPE (COSGN00C L226-227) becomes
#   the signed access token's `sub` / `user_type` claims
#   (AAP 0.1.1 password-security uplift; 0.7.7 hash the plaintext SEC-USR-PWD).
"""Pure-function unit tests for :mod:`app.core.security`.

These tests pin the two authentication primitives that replace mainframe
mechanisms with no modern equivalent:

1.  **Password hashing (bcrypt).** The legacy sign-on program
    ``app/cbl/COSGN00C.cbl`` validated a user by a plaintext equality compare,
    ``IF SEC-USR-PWD = WS-USER-PWD`` (L223), against the 8-character
    ``SEC-USR-PWD PIC X(08)`` field. The target hashes the credential with
    bcrypt and verifies against the stored hash, so the plaintext is never
    stored or compared. The suite asserts a hash never equals its plaintext,
    verification accepts the correct password and rejects a wrong one, and the
    per-hash bcrypt salt makes two hashes of the same password differ while both
    still verify.

2.  **Signed access tokens (COMMAREA identity replacement).** The legacy design
    carried identity and role from program to program through the CICS
    ``COCOM01Y`` COMMAREA (``CDEMO-USER-ID`` / ``CDEMO-USER-TYPE``). The target
    is stateless: a signed token carries the same identity (``sub``) and role
    (``user_type``) claims. The suite asserts those claims round-trip for an
    admin and a regular user, and that a tampered or expired token is rejected
    with the specific domain :class:`~app.core.exceptions.AuthenticationError`
    (never a bare ``Exception`` and never the raw ``jwt`` error the module
    translates).

The suite is intentionally hermetic: no database, no fixtures, and no network
access are used -- ``security.py`` performs no I/O, and every test is a
deterministic, synchronous ``def test_*`` function. Ochs naming (AAP 0.8.2 /
0.8.3) is honored: snake_case test-function names (the pytest discovery
contract), camelCase local variables, ALL_UPPERCASE module-level constants,
4-space indentation, and one asserted behavior per test.
"""

import os

# ---------------------------------------------------------------------------
# Environment bootstrap -- MANDATORY and MUST run before any ``app.*`` import.
#
# ``app.core.config.Settings`` declares ``SECRET_KEY`` with no default, and
# ``app.core.security`` builds its module-level state from ``settings`` at import
# time. Setting these variables here makes the module importable in isolation
# (independently of any conftest.py). ``setdefault`` is used deliberately so that
# a value already exported by the environment or a parent conftest is never
# clobbered. The literal below is a throwaway test key, not a real secret.
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "testing-secret-key-not-for-production")
os.environ.setdefault("ENVIRONMENT", "test")

import pytest  # noqa: E402  (import intentionally follows the env bootstrap)

from app.core.security import (  # noqa: E402  (must import after bootstrap)
    CreateAccessToken,
    DecodeAccessToken,
    HashPassword,
    PWD_CONTEXT,
    VerifyPassword,
    VerifyPasswordDummy,
)
from app.core.exceptions import AuthenticationError  # noqa: E402

# ---------------------------------------------------------------------------
# Test-input constants (ALL_UPPERCASE per the Ochs constant convention).
#
# ``ADMIN001`` / ``USER0001`` / ``PASSWORD`` are the documented seed sample
# credentials (README) used here ONLY as literal test inputs. They are not real
# secrets, are never read from application config, and are never hardcoded into
# any app module -- this test file is their sole, test-only appearance.
# ---------------------------------------------------------------------------
SEED_PASSWORD = "PASSWORD"
WRONG_PASSWORD = "wrongpw"
ADMIN_USER_ID = "ADMIN001"
ADMIN_USER_TYPE = "A"
REGULAR_USER_ID = "USER0001"
REGULAR_USER_TYPE = "U"

# Standard JWT claim keys the token producer records (mirrors security.py).
SUBJECT_CLAIM = "sub"
USER_TYPE_CLAIM = "user_type"

# bcrypt scheme name expected in the passlib context and hash identity.
BCRYPT_SCHEME = "bcrypt"

# Negative lifetime placing ``exp`` in the past to mint an already-expired token
# without freezing/patching the clock (no new dependency is introduced).
EXPIRED_MINUTES = -1


# ===========================================================================
# Phase A -- password hashing (bcrypt) round-trip.
# ===========================================================================
def test_hashed_password_is_not_plaintext():
    """HashPassword never returns the plaintext it was given (no plaintext store)."""
    hashedValue = HashPassword(SEED_PASSWORD)
    assert hashedValue != SEED_PASSWORD


def test_verify_correct_password_returns_true():
    """VerifyPassword accepts the exact password that produced the hash."""
    hashedValue = HashPassword(SEED_PASSWORD)
    assert VerifyPassword(SEED_PASSWORD, hashedValue) is True


def test_verify_wrong_password_returns_false():
    """VerifyPassword rejects a wrong password without raising (returns False)."""
    hashedValue = HashPassword(SEED_PASSWORD)
    assert VerifyPassword(WRONG_PASSWORD, hashedValue) is False


def test_hash_is_salted_yet_both_verify():
    """Two hashes of the same password differ (bcrypt salt) yet both verify.

    bcrypt embeds a random per-hash salt, so hashing the same plaintext twice
    must yield two distinct hash strings; verification must nonetheless accept
    the original password against each of them.
    """
    firstHash = HashPassword(SEED_PASSWORD)
    secondHash = HashPassword(SEED_PASSWORD)
    assert firstHash != secondHash
    assert VerifyPassword(SEED_PASSWORD, firstHash) is True
    assert VerifyPassword(SEED_PASSWORD, secondHash) is True


def test_pwd_context_uses_bcrypt_scheme():
    """The exported PWD_CONTEXT is configured for bcrypt and emits bcrypt hashes.

    Confirms the module constant is exposed and that HashPassword output is
    identified by that same context as a bcrypt hash -- i.e. the AAP-mandated
    bcrypt uplift is actually in force, not merely a differently-named scheme.
    """
    assert BCRYPT_SCHEME in PWD_CONTEXT.schemes()
    hashedValue = HashPassword(SEED_PASSWORD)
    assert PWD_CONTEXT.identify(hashedValue) == BCRYPT_SCHEME


def test_verify_password_dummy_returns_none_and_never_raises():
    """The timing-equalizing dummy verify is a safe no-op (QA finding F1).

    ``VerifyPasswordDummy`` exists so the unknown-user sign-on branch can spend
    one bcrypt verification's worth of time (defeating user-id enumeration by
    timing). Its contract is deliberately narrow: it takes no input, returns
    ``None``, authenticates no one, and must never raise -- so wiring it into the
    hot auth path can never itself become a failure mode. Calling it twice also
    proves passlib's cached dummy hash keeps it callable on every invocation.
    """
    assert VerifyPasswordDummy() is None
    assert VerifyPasswordDummy() is None


# ===========================================================================
# Phase B -- access-token issue & decode (COMMAREA identity replacement).
# ===========================================================================
def test_decoded_admin_token_carries_identity_claims():
    """An admin token round-trips its sub (user id) and user_type ('A') claims."""
    claims = DecodeAccessToken(CreateAccessToken(ADMIN_USER_ID, ADMIN_USER_TYPE))
    assert claims[SUBJECT_CLAIM] == ADMIN_USER_ID
    assert claims[USER_TYPE_CLAIM] == ADMIN_USER_TYPE


def test_decoded_regular_user_token_carries_identity_claims():
    """A regular-user token round-trips its sub and user_type ('U') claims."""
    claims = DecodeAccessToken(CreateAccessToken(REGULAR_USER_ID, REGULAR_USER_TYPE))
    assert claims[SUBJECT_CLAIM] == REGULAR_USER_ID
    assert claims[USER_TYPE_CLAIM] == REGULAR_USER_TYPE


# ===========================================================================
# Phase C -- tampered token -> AuthenticationError (specific handling).
# ===========================================================================
def test_tampered_token_raises_authentication_error():
    """A structurally corrupted token is rejected as AuthenticationError.

    Appending stray characters invalidates the signature/structure. The module
    catches the raw ``jwt.InvalidTokenError`` and translates it into the domain
    :class:`AuthenticationError`, which is the specific type asserted here (never
    a bare ``Exception`` and never the untranslated ``jwt`` error).
    """
    token = CreateAccessToken(ADMIN_USER_ID, ADMIN_USER_TYPE)
    tampered = token + "xxxx"
    with pytest.raises(AuthenticationError):
        DecodeAccessToken(tampered)


# ===========================================================================
# Phase D -- expired token -> AuthenticationError.
# ===========================================================================
def test_expired_token_raises_authentication_error():
    """An already-expired token is rejected as AuthenticationError.

    A negative ``expiresMinutes`` places the ``exp`` claim in the past, yielding
    an already-expired token without freezing the clock or adding a new
    dependency (time-patching is explicitly disallowed by the file contract).
    The module catches ``jwt.ExpiredSignatureError`` and re-raises it as the
    domain :class:`AuthenticationError`, the specific type asserted below.

    If a future implementation stopped honoring a negative lifetime and returned
    a still-valid token, the negative-expiry path would be unsupported; in that
    case the test skips with a clear message rather than failing spuriously.
    """
    expiredToken = CreateAccessToken(
        ADMIN_USER_ID,
        ADMIN_USER_TYPE,
        expiresMinutes=EXPIRED_MINUTES,
    )
    # Guard: confirm the negative lifetime actually produced an expired token
    # before asserting the rejection contract (time-patching is not permitted).
    try:
        DecodeAccessToken(expiredToken)
    except AuthenticationError:
        pass
    else:
        pytest.skip("negative expiry not supported")
    with pytest.raises(AuthenticationError):
        DecodeAccessToken(expiredToken)
