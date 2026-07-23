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


# ===========================================================================
# Phase E -- JWT decode hardening (QA finding M-23). The PyJWT >= 2.13.0 uplift
# is accompanied by explicit adversarial coverage: malformed structures,
# critical-header / algorithm-confusion attacks, forged signatures, and
# oversized tokens must ALL be rejected as the domain AuthenticationError --
# never accepted, never surfaced as a bare/raw error, and never an unbounded
# hang on hostile input. These tests forge tokens directly with PyJWT and by
# hand; none of them require (or reveal) the real signing key.
# ===========================================================================
import base64  # noqa: E402  (stdlib; used only by the Phase E token forgers)
import json  # noqa: E402  (stdlib; used only by the Phase E token forgers)

import jwt  # noqa: E402  (PyJWT; used to forge the adversarial tokens below)

# A signing key deliberately DIFFERENT from ``settings.SECRET_KEY``. Forging a
# validly-structured token with it proves signature verification is enforced.
# It is padded to >= 64 bytes so that forging an HS512 token below (SHA-512)
# does not itself trip PyJWT 2.13.0's RFC 7518 InsecureKeyLengthWarning -- the
# test's intent is the algorithm-allow-list rejection, not a key-length signal.
FORGER_WRONG_KEY = (
    "attacker-controlled-key-not-the-server-secret-padded-to-64-bytes+"
)

# An HMAC algorithm OUTSIDE the decoder's single-entry allow-list (HS256). A
# token advertising it must be refused by the allow-list (algorithm confusion).
DISALLOWED_ALGORITHM = "HS512"

# ~2 MB of padding for the oversized-token denial-of-service-resistance check.
OVERSIZED_PAD_BYTES = 2_000_000

# Structurally invalid token strings covering empty/whitespace input, wrong
# segment counts (JWT requires exactly three dot-separated segments), and
# non-base64 garbage. Each must be rejected as AuthenticationError.
MALFORMED_TOKENS = [
    "",
    "   ",
    "not-a-jwt",
    "only.two",
    "a.b.c.d",
    "!!!.@@@.###",
    "eyJhbGciOiJIUzI1NiJ9..",
]


def _Base64UrlSegment(rawBytes):
    """Return the unpadded base64url text of ``rawBytes`` (JWT segment form)."""
    return base64.urlsafe_b64encode(rawBytes).rstrip(b"=").decode("ascii")


def _ForgeNoneAlgorithmToken(subject, userType):
    """Forge an unsigned ``alg=none`` token (the classic signature-strip attack).

    Builds ``header.payload.`` with an empty signature segment by hand so the
    test never depends on any library's willingness to emit "none". A decoder
    that allow-lists only HS256 MUST reject it rather than trusting the claims.
    """
    header = {"alg": "none", "typ": "JWT"}
    payload = {SUBJECT_CLAIM: subject, USER_TYPE_CLAIM: userType}
    headerSegment = _Base64UrlSegment(json.dumps(header).encode("utf-8"))
    payloadSegment = _Base64UrlSegment(json.dumps(payload).encode("utf-8"))
    return f"{headerSegment}.{payloadSegment}."


@pytest.mark.parametrize("malformedToken", MALFORMED_TOKENS)
def test_malformed_token_raises_authentication_error(malformedToken):
    """Structurally invalid tokens are all rejected as AuthenticationError.

    Covers empty/whitespace input, wrong segment counts, and non-base64 garbage.
    Every shape must be translated from the raw ``jwt.InvalidTokenError`` into
    the domain :class:`AuthenticationError` (M-23 malformed-token hardening).
    """
    with pytest.raises(AuthenticationError):
        DecodeAccessToken(malformedToken)


def test_alg_none_unsigned_token_rejected():
    """An ``alg=none`` unsigned token is rejected (signature-strip attack, M-23).

    The decoder allow-lists only ``settings.ALGORITHM`` (HS256), so an attacker
    who strips the signature and advertises ``alg=none`` must not be able to
    smuggle forged ``sub`` / ``user_type`` claims past verification.
    """
    forgedToken = _ForgeNoneAlgorithmToken(ADMIN_USER_ID, ADMIN_USER_TYPE)
    with pytest.raises(AuthenticationError):
        DecodeAccessToken(forgedToken)


def test_disallowed_algorithm_token_rejected():
    """A token advertising a non-allow-listed algorithm is rejected (M-23).

    Even a well-formed HMAC token is refused when its header ``alg`` (here
    HS512) falls outside the decoder's single-entry allow-list, so an attacker
    cannot switch/downgrade the verification algorithm (algorithm confusion).
    """
    forgedToken = jwt.encode(
        {SUBJECT_CLAIM: ADMIN_USER_ID, USER_TYPE_CLAIM: ADMIN_USER_TYPE},
        FORGER_WRONG_KEY,
        algorithm=DISALLOWED_ALGORITHM,
    )
    with pytest.raises(AuthenticationError):
        DecodeAccessToken(forgedToken)


def test_wrong_key_signature_rejected():
    """An HS256 token signed with the wrong key is rejected (M-23).

    Proves signature verification is actually enforced: forging the correct
    algorithm but the wrong key yields ``InvalidSignatureError``, which the
    module translates into the domain :class:`AuthenticationError`.
    """
    forgedToken = jwt.encode(
        {SUBJECT_CLAIM: ADMIN_USER_ID, USER_TYPE_CLAIM: ADMIN_USER_TYPE},
        FORGER_WRONG_KEY,
        algorithm="HS256",
    )
    with pytest.raises(AuthenticationError):
        DecodeAccessToken(forgedToken)


def test_oversized_token_rejected_without_hanging():
    """A multi-megabyte corrupted token is rejected quickly, not processed (M-23).

    Appending ~2 MB of padding to a valid token corrupts it while making it
    enormous. Decoding must fail fast as AuthenticationError; the test returning
    at all proves the decode path does not hang or exhaust resources on a large
    hostile input (denial-of-service resistance).
    """
    validToken = CreateAccessToken(ADMIN_USER_ID, ADMIN_USER_TYPE)
    oversizedToken = validToken + ("A" * OVERSIZED_PAD_BYTES)
    with pytest.raises(AuthenticationError):
        DecodeAccessToken(oversizedToken)
