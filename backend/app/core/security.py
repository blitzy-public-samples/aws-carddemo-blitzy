# Auth primitives. Ports app/cbl/COSGN00C.cbl sign-on: the legacy plaintext
# compare IF SEC-USR-PWD = WS-USER-PWD (COSGN00C L223) becomes bcrypt hash
# verification. Legacy user record app/cpy/CSUSR01Y.cpy stored SEC-USR-PWD
# PIC X(08) plaintext -> replaced by password_hash. Never store/return plaintext.
"""Authentication primitives for the CardDemo FastAPI backend.

This module is the single, self-contained home for the backend's low-level
authentication building blocks. It has two responsibilities, both of which
replace mainframe mechanisms that no longer have a modern equivalent:

1.  **Password hashing** — the legacy CICS program ``app/cbl/COSGN00C.cbl``
    validated a sign-on by reading the ``USRSEC`` VSAM file and performing a
    plaintext equality compare, ``IF SEC-USR-PWD = WS-USER-PWD`` (COSGN00C
    L223), against the 8-character ``SEC-USR-PWD PIC X(08)`` field of
    ``app/cpy/CSUSR01Y.cpy``. Storing or comparing plaintext passwords is
    unacceptable in the target (AAP 0.1.1 / 0.7.7, Ochs Rule #3), so that
    compare becomes a bcrypt hash verification here and the stored credential
    becomes ``password_hash``.

2.  **Signed access tokens** — the legacy design propagated identity and role
    (``CDEMO-USER-ID`` / ``CDEMO-USER-TYPE``) from program to program through
    the CICS ``COCOM01Y`` COMMAREA. The target is stateless: this module mints
    and verifies a signed token that carries the same identity and role claims.
    A single signed token serves both supported auth modes (AAP 0.8.4): under
    the ``session`` baseline the token is stored in the session cookie named
    ``settings.SESSION_COOKIE_NAME``; under ``jwt`` it is returned as a bearer
    token. The claim set is identical, so ``app/core/dependencies.py`` can
    decode either uniformly.

Design and security constraints (Ochs Rule):
    * No secret is hardcoded. The signing key, algorithm, token lifetime and
      bcrypt cost are read exclusively from :data:`app.core.config.settings`.
    * Only specific exceptions are handled: JWT decode failures are caught as
      the concrete ``jwt.ExpiredSignatureError`` / ``jwt.InvalidTokenError`` and
      translated into the domain :class:`~app.core.exceptions.AuthenticationError`;
      no bare ``except`` is ever used.
    * ``HashPassword`` and ``VerifyPassword`` are pure over their inputs — they
      hash/verify the exact bytes handed to them and never apply any
      normalization (in particular, never the legacy password uppercasing). The
      password is hashed and verified over its EXACT bytes on every path — the
      seed loader (``batch/loaders/init_users.py``), the admin create/update
      paths (``app/services/user_admin_service.py``), and the login service
      (``app/services/auth_service.py``) — so credential case entropy is fully
      preserved (QA finding M-01; the legacy ``FUNCTION UPPER-CASE`` password
      fold at COSGN00C L135-136 is intentionally not reproduced). See
      :func:`NormalizeUserId` for the one normalization helper this module does
      expose (for the user id, which is the uppercase VSAM key — never for the
      password).
    * Plaintext passwords are never logged, stored, or returned.

This module performs no I/O: it opens no database connection and constructs no
engine. Importing it only builds the module-level bcrypt context and reads
already-loaded settings.
"""

from datetime import datetime, timedelta, timezone

import jwt
from passlib.context import CryptContext

from app.core.config import settings
from app.core.exceptions import AuthenticationError

__all__ = [
    "PWD_CONTEXT",
    "HashPassword",
    "VerifyPassword",
    "VerifyPasswordDummy",
    "CreateAccessToken",
    "CreateSessionToken",
    "DecodeAccessToken",
    "NormalizeUserId",
    "SUBJECT_CLAIM",
    "USER_TYPE_CLAIM",
    "SESSION_VERSION_CLAIM",
]

# ---------------------------------------------------------------------------
# Token claim keys (ALL_UPPERCASE constants; single source of truth so the
# token producer here and the token consumer in dependencies.py never drift).
# ---------------------------------------------------------------------------
SUBJECT_CLAIM = "sub"           # standard JWT subject = CDEMO-USER-ID
USER_TYPE_CLAIM = "user_type"   # role claim carrying legacy CDEMO-USER-TYPE ('A'/'U')
ISSUED_AT_CLAIM = "iat"         # standard JWT issued-at
EXPIRATION_CLAIM = "exp"        # standard JWT expiration
# Session-generation claim (M-02): the user's ``session_version`` at mint time.
# get_current_user rejects a token whose ``sver`` no longer matches the stored
# value, so incrementing session_version (logout / role or password change)
# revokes every outstanding token for that user.
SESSION_VERSION_CLAIM = "sver"

# Human-readable error surfaced when a token is missing, expired, tampered
# with, or otherwise undecodable. Kept generic on purpose: it must not reveal
# whether the failure was expiry versus signature to a caller.
INVALID_TOKEN_MESSAGE = "Invalid or expired session/token"

# ---------------------------------------------------------------------------
# Password hashing context (passlib + bcrypt backend).
#
# The bcrypt work factor is taken from settings.BCRYPT_ROUNDS (never hardcoded),
# so the cost encoded in every produced hash matches the deployment's
# configuration. ``deprecated="auto"`` lets passlib flag hashes produced with an
# outdated cost/scheme as needing rehash without breaking verification of
# existing hashes.
# ---------------------------------------------------------------------------
PWD_CONTEXT = CryptContext(
    schemes=["bcrypt"],
    deprecated="auto",
    bcrypt__rounds=settings.BCRYPT_ROUNDS,
)


def HashPassword(plainPassword: str) -> str:
    """Hash a plaintext password with bcrypt.

    Uses the module :data:`PWD_CONTEXT`, whose cost is ``settings.BCRYPT_ROUNDS``.
    Ports the plaintext ``SEC-USR-PWD`` credential of ``app/cpy/CSUSR01Y.cpy``
    to the target's hashed ``password_hash`` (AAP 0.7.7). The plaintext input is
    never logged, stored, or returned.

    Args:
        plainPassword: The plaintext password to hash.

    Returns:
        The bcrypt hash string (a self-describing ``$2b$`` modular-crypt string
        that embeds the algorithm, configured cost, and salt).
    """
    hashedPassword = PWD_CONTEXT.hash(plainPassword)
    return hashedPassword


def VerifyPassword(plainPassword: str, passwordHash: str) -> bool:
    """Verify a plaintext password against a stored bcrypt hash.

    This is the modern replacement for the legacy plaintext equality compare
    ``IF SEC-USR-PWD = WS-USER-PWD`` (COSGN00C L223): instead of comparing raw
    strings, the candidate password is hashed with the salt embedded in
    ``passwordHash`` and compared in constant time by passlib.

    The function is pure over its inputs — it applies no normalization (the
    legacy password uppercasing is intentionally *not* performed here or on any
    other path; the password is hashed/verified over its exact bytes everywhere,
    preserving case entropy per QA finding M-01). A wrong password yields
    ``False`` rather than an exception.

    Args:
        plainPassword: The candidate plaintext password supplied at sign-on.
        passwordHash: The previously stored bcrypt hash to check against.

    Returns:
        ``True`` if the password matches the hash, otherwise ``False``.
    """
    isValid = PWD_CONTEXT.verify(plainPassword, passwordHash)
    return isValid


def VerifyPasswordDummy() -> None:
    """Consume one bcrypt verification's worth of time without a real hash.

    Timing-attack mitigation for the sign-on path (QA finding F1). The legacy
    plaintext compare ``IF SEC-USR-PWD = WS-USER-PWD`` (COSGN00C L223) returned
    in constant, negligible time whether or not the user id existed, because the
    record was already resident. In the modern stack a *successful* USRSEC lookup
    is followed by a bcrypt :func:`VerifyPassword` (hundreds of milliseconds at
    the configured cost), whereas a *missing* user id would otherwise skip that
    work and return almost instantly. That timing difference is a reliable oracle
    for enumerating valid user ids.

    Calling this in the user-not-found branch of ``auth_service.Login`` performs
    a bcrypt verification against a throwaway internal hash (passlib's
    :meth:`~passlib.context.CryptContext.dummy_verify`, computed at the same
    ``settings.BCRYPT_ROUNDS`` cost as a real verify) so both the found and
    not-found paths consume equivalent time. It always fails internally and is a
    no-op with respect to authentication: it takes no input, returns nothing, and
    never authenticates anyone -- its ONLY purpose is to equalize wall-clock time.

    Returns:
        ``None``. The verification result is intentionally discarded; the value
        of the call is its (constant) execution time, not any boolean.
    """
    # passlib caches the dummy hash after the first call, so this reliably takes
    # one bcrypt verification at the configured cost on every invocation.
    PWD_CONTEXT.dummy_verify()


def NormalizeUserId(userId: str) -> str:
    """Normalize a user id to its canonical uppercase, trimmed form.

    The legacy sign-on uppercases the user id before using it as the ``USRSEC``
    VSAM key (``FUNCTION UPPER-CASE``, COSGN00C L132-134); ids such as
    ``ADMIN001`` / ``USER0001`` are uppercase keys. This helper preserves that
    behavior for callers (the login service and dependencies) that resolve a
    user by id, keeping the key semantics identical to the mainframe.

    It is deliberately provided only for the *user id*, never for the password:
    passwords are hashed/verified over their exact bytes (see
    :func:`VerifyPassword`).

    Args:
        userId: The raw user id as received from the client.

    Returns:
        The user id uppercased and stripped of surrounding whitespace.
    """
    return userId.strip().upper()


def CreateAccessToken(
    subject: str,
    userType: str,
    sessionVersion: int | None = None,
    expiresMinutes: int | None = None,
) -> str:
    """Create a signed access token carrying identity and role claims.

    Replaces the legacy ``COCOM01Y`` COMMAREA propagation of identity and role:
    the token embeds the user id as the standard ``sub`` claim and the legacy
    ``CDEMO-USER-TYPE`` ('A' admin / 'U' user) as the ``user_type`` claim, plus
    standard ``iat`` and ``exp`` claims. It is signed with ``settings.SECRET_KEY``
    using ``settings.ALGORITHM`` (never a hardcoded key).

    When ``sessionVersion`` is supplied it is embedded as the ``sver`` claim
    (M-02): ``get_current_user`` compares that claim against the user's current
    ``session_version`` and rejects the token when they differ, so the token can
    be revoked server-side by incrementing the stored generation (logout, or a
    role/password change). All app sign-on paths supply it; it is optional only
    so low-level decode/claim unit tests can mint a bare token.

    The same signed token serves both auth modes (AAP 0.8.4): it is stored in
    the ``settings.SESSION_COOKIE_NAME`` cookie under the ``session`` baseline or
    returned as a bearer token under ``jwt``.

    Args:
        subject: The user id to record as the token subject (``sub``).
        userType: The legacy user-type / role code ('A' or 'U') recorded as the
            ``user_type`` claim.
        sessionVersion: The user's current session generation, embedded as the
            ``sver`` claim when not ``None`` (the server-side revocation anchor).
        expiresMinutes: Optional token lifetime in minutes. When ``None``, the
            configured ``settings.ACCESS_TOKEN_EXPIRE_MINUTES`` is used.

    Returns:
        The encoded, signed token as a ``str``.
    """
    lifetimeMinutes = (
        expiresMinutes
        if expiresMinutes is not None
        else settings.ACCESS_TOKEN_EXPIRE_MINUTES
    )
    issuedAt = datetime.now(timezone.utc)
    expireAt = issuedAt + timedelta(minutes=lifetimeMinutes)
    tokenPayload = {
        SUBJECT_CLAIM: subject,
        USER_TYPE_CLAIM: userType,
        ISSUED_AT_CLAIM: issuedAt,
        EXPIRATION_CLAIM: expireAt,
    }
    if sessionVersion is not None:
        tokenPayload[SESSION_VERSION_CLAIM] = sessionVersion
    encodedToken = jwt.encode(
        tokenPayload,
        settings.SECRET_KEY.get_secret_value(),
        algorithm=settings.ALGORITHM,
    )
    return encodedToken


def CreateSessionToken(
    subject: str,
    userType: str,
    sessionVersion: int | None = None,
    expiresMinutes: int | None = None,
) -> str:
    """Create the signed token used by the session baseline.

    Thin, intention-revealing alias of :func:`CreateAccessToken` that lets the
    ``session``-baseline call sites in ``auth_service.py`` and ``main.py`` read
    naturally ("create a session token") without duplicating any logic. The
    token and its claims are identical to :func:`CreateAccessToken`; under the
    session baseline this value is stored in the ``settings.SESSION_COOKIE_NAME``
    cookie.

    Args:
        subject: The user id to record as the token subject (``sub``).
        userType: The legacy user-type / role code ('A' or 'U').
        sessionVersion: The user's current session generation, embedded as the
            ``sver`` claim when not ``None`` (M-02 revocation anchor). Forwarded
            verbatim to :func:`CreateAccessToken`.
        expiresMinutes: Optional lifetime in minutes; defaults to
            ``settings.ACCESS_TOKEN_EXPIRE_MINUTES``.

    Returns:
        The encoded, signed token as a ``str``.
    """
    return CreateAccessToken(
        subject,
        userType,
        sessionVersion=sessionVersion,
        expiresMinutes=expiresMinutes,
    )


def DecodeAccessToken(token: str) -> dict:
    """Decode and verify a signed access token, returning its claims.

    Verifies the signature and expiry using ``settings.SECRET_KEY`` and
    ``settings.ALGORITHM``. This is the counterpart to :func:`CreateAccessToken`
    consumed by ``app/core/dependencies.py`` (``get_current_user``) to rebuild
    the caller's identity and role from the ``sub`` / ``user_type`` claims.

    Only the specific PyJWT failure types are handled — an expired token
    (``jwt.ExpiredSignatureError``) and any other invalid token
    (``jwt.InvalidTokenError``, the base of all decode errors including bad
    signature or malformed structure). Both are translated to the domain
    :class:`~app.core.exceptions.AuthenticationError`; no bare ``except`` is
    used. ``ExpiredSignatureError`` is caught first because it is a subclass of
    ``InvalidTokenError``.

    Args:
        token: The encoded token string to decode and verify.

    Returns:
        The decoded claims as a ``dict`` (contains ``sub``, ``user_type``,
        ``iat``, and ``exp``).

    Raises:
        AuthenticationError: If the token is expired, has an invalid signature,
            or is otherwise malformed/undecodable.
    """
    try:
        tokenClaims = jwt.decode(
            token,
            settings.SECRET_KEY.get_secret_value(),
            algorithms=[settings.ALGORITHM],
        )
    except jwt.ExpiredSignatureError as expiredError:
        raise AuthenticationError(INVALID_TOKEN_MESSAGE) from expiredError
    except jwt.InvalidTokenError as invalidError:
        raise AuthenticationError(INVALID_TOKEN_MESSAGE) from invalidError
    return tokenClaims
