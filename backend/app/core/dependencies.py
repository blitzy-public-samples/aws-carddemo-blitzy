# FastAPI DI providers. Replaces app/cpy/COCOM01Y.cpy CARDDEMO-COMMAREA
# identity/role propagation (CDEMO-USER-ID, CDEMO-USER-TYPE with
# 88 CDEMO-USRTYP-ADMIN VALUE 'A' / CDEMO-USRTYP-USER VALUE 'U') via Depends().
# Identity resolved from session/JWT (app.core.security), not a COMMAREA.
"""FastAPI dependency-injection providers for the CardDemo backend.

This module is the stateless replacement for the legacy CICS ``COCOM01Y``
communication area (``CARDDEMO-COMMAREA``). On the mainframe, every online
program received the caller's identity and role by having the ``CDEMO-USER-ID``
and ``CDEMO-USER-TYPE`` fields propagated program-to-program through the
COMMAREA on each ``EXEC CICS RETURN`` / ``XCTL``. There is no COMMAREA to pass
in the modern stack: identity is re-derived **per request** from the session
cookie (or JWT bearer token) and injected into routers and services through
FastAPI ``Depends(...)`` providers.

Public providers (the dependency-injection contract imported by every router in
``app.api.v1`` and, indirectly, by the services they call):

    get_db              Yield a per-request async SQLAlchemy session
                        (unit-of-work), reusing the process-wide engine.
    get_current_user    Resolve the authenticated :class:`~app.models.user.User`
                        from the request's session cookie / bearer token -- the
                        stateless successor to COMMAREA ``CDEMO-USER-ID``.
    require_admin       Authorize admin-only routes by gating on the ported
                        role rule ``user_type == 'A'`` (legacy
                        ``CDEMO-USRTYP-ADMIN``).

Design and security constraints (Ochs Rule 0.8.2 / 0.8.3):
    * The public provider names (``get_db``, ``get_current_user``,
      ``require_admin``) are kept snake_case as an intentional, documented
      exception to the Ochs naming rule: they are the framework/AAP contract
      (AAP 0.4.3 / 0.5.1) that sibling router and ``main`` modules import by
      name. Internal helpers use PascalCase, local variables camelCase, and
      module constants ALL_UPPERCASE.
    * No secret or environment value is hardcoded. The session cookie name and
      the authentication mode are read from :data:`app.core.config.settings`.
    * Only specific exceptions are handled -- never a bare ``except``. Token
      decode failures are caught as the domain
      :class:`~app.core.exceptions.AuthenticationError` and translated to an
      HTTP 401; a SQLAlchemy failure during a request triggers an explicit
      rollback via the concrete :class:`sqlalchemy.exc.SQLAlchemyError`.
    * Identity resolution is read-only with respect to authorization: this
      module authenticates and authorizes but never mints tokens (that is
      ``app.core.security``) and never opens its own engine (that is
      ``app.db.session``).
"""

from collections.abc import AsyncIterator
from typing import NoReturn

from fastapi import Depends, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.exceptions import AuthenticationError
from app.core.security import (
    SESSION_VERSION_CLAIM,
    SUBJECT_CLAIM,
    DecodeAccessToken,
)
from app.db.session import AsyncSessionLocal
from app.models.user import User

__all__ = [
    "get_db",
    "get_current_user",
    "require_admin",
    "ADMIN_USER_TYPE",
    "REGULAR_USER_TYPE",
]

# ---------------------------------------------------------------------------
# Role constants -- faithful ports of the COCOM01Y CDEMO-USER-TYPE 88-levels.
#
# The single-character role codes come straight from the copybook condition
# names (``88 CDEMO-USRTYP-ADMIN VALUE 'A'`` / ``88 CDEMO-USRTYP-USER VALUE
# 'U'``). Capturing them as named constants keeps the admin gate self-documenting
# and avoids a magic ``'A'`` literal in the authorization check.
# ---------------------------------------------------------------------------
ADMIN_USER_TYPE = "A"
REGULAR_USER_TYPE = "U"

# ---------------------------------------------------------------------------
# Token-transport constants.
#
# ``SESSION_AUTH_MODE`` / ``JWT_AUTH_MODE`` mirror the accepted values of
# ``settings.AUTH_MODE`` (AAP 0.8.4: session baseline, JWT alternative).
# ``AUTHORIZATION_HEADER`` / ``BEARER_PREFIX`` describe the standard bearer
# scheme used when a token arrives in the HTTP ``Authorization`` header rather
# than the session cookie.
# ---------------------------------------------------------------------------
SESSION_AUTH_MODE = "session"
JWT_AUTH_MODE = "jwt"
AUTHORIZATION_HEADER = "Authorization"
BEARER_PREFIX = "Bearer "

# ---------------------------------------------------------------------------
# Human-readable HTTP error details. Kept as constants so every unauthenticated
# / unauthorized outcome returns an identical, self-documenting message.
# ---------------------------------------------------------------------------
NOT_AUTHENTICATED_DETAIL = "Not authenticated"
INVALID_CREDENTIALS_DETAIL = "Could not validate credentials"
# M-02: a token whose ``sver`` claim no longer matches the user's stored
# session_version has been revoked (logout, or a role/password change advanced
# the generation). It is rejected with the same generic 401 wording as any
# other invalid credential so a revoked token is indistinguishable from a
# tampered one to the caller.
REVOKED_CREDENTIALS_DETAIL = "Could not validate credentials"
ADMIN_REQUIRED_DETAIL = "Admin privileges required"


async def get_db() -> AsyncIterator[AsyncSession]:
    """Provide a per-request async database session (unit-of-work).

    Opens one :class:`~sqlalchemy.ext.asyncio.AsyncSession` from the shared
    :data:`app.db.session.AsyncSessionLocal` factory and yields it for the
    lifetime of the request, then closes it via the ``async with`` block. The
    process-wide engine created in ``app.db.session`` is reused; this provider
    never constructs a new engine or connection pool.

    Commit policy: the service and repository layers own ``commit()`` calls.
    This provider only guarantees that a SQLAlchemy error raised while the
    session is in use triggers an explicit ``rollback()`` (leaving no partial
    unit-of-work behind) before the error propagates to the caller.

    Yields:
        The active :class:`~sqlalchemy.ext.asyncio.AsyncSession` for the request.

    Raises:
        SQLAlchemyError: Re-raised after rollback if a database error occurs
            while the session is being used by the route handler.
    """
    async with AsyncSessionLocal() as session:
        try:
            yield session
        except SQLAlchemyError:
            await session.rollback()
            raise


def RaiseUnauthorized(detail: str, cause: Exception | None = None) -> NoReturn:
    """Raise an HTTP 401 Unauthorized error with the supplied detail.

    Centralizes the 401 response so :func:`get_current_user` stays small and
    every unauthenticated outcome -- a missing token, an invalid/expired token,
    or a token referencing an unknown user -- returns an identical status and
    shape. Annotated ``NoReturn`` so static analysis narrows control flow at the
    call sites (the function always raises).

    Args:
        detail: Human-readable message placed on the ``HTTPException``.
        cause: Optional originating exception. When provided it is chained via
            ``raise ... from cause`` to preserve the traceback; when ``None`` no
            explicit cause is attached.

    Raises:
        HTTPException: Always, with status code 401.
    """
    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail=detail,
    ) from cause


def ResolveRequestToken(request: Request) -> str | None:
    """Resolve the raw signed token carried by the request.

    Reconstructs the identity transport that COMMAREA propagation used to
    provide implicitly. Two transports are supported (AAP 0.8.4): the session
    cookie named ``settings.SESSION_COOKIE_NAME`` (the confirmed baseline) and
    the ``Authorization: Bearer <token>`` header (the JWT alternative). Both are
    read; ``settings.AUTH_MODE`` decides which is preferred when both are
    present, and the other is used as a fallback so a deployment can switch mode
    without breaking either transport.

    Args:
        request: The incoming FastAPI/Starlette request.

    Returns:
        The raw token string, or ``None`` when neither transport carries one.
    """
    cookieToken = request.cookies.get(settings.SESSION_COOKIE_NAME)
    authorizationHeader = request.headers.get(AUTHORIZATION_HEADER, "")
    bearerToken = None
    if authorizationHeader.startswith(BEARER_PREFIX):
        bearerToken = authorizationHeader[len(BEARER_PREFIX):].strip() or None
    if settings.AUTH_MODE == JWT_AUTH_MODE:
        return bearerToken or cookieToken
    return cookieToken or bearerToken


async def get_current_user(
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> User:
    """Resolve the authenticated user from the request's session/JWT.

    This is the stateless replacement for the legacy COMMAREA ``CDEMO-USER-ID``
    propagation: instead of the identity travelling program-to-program in the
    ``CARDDEMO-COMMAREA``, it is re-derived on every request from the signed
    token. The token is decoded and verified by
    :func:`app.core.security.DecodeAccessToken`, the subject (``sub``) claim is
    read as the user id, and the matching row is loaded from the ``users`` table.

    A missing token, an invalid or expired token, or a token whose subject does
    not match any user all resolve to HTTP 401 -- the last case mirrors the
    ``COSGN00C`` "User not found" outcome (``WS-RESP-CD = 13``).

    Server-side revocation (M-02): the token carries a ``sver`` (session
    version) claim minted from the user's ``session_version`` at sign-on. That
    claim is compared against the row's current ``session_version``; any
    mismatch -- or a token that predates the claim and therefore omits it --
    is rejected with HTTP 401. Incrementing ``session_version`` (on logout or a
    role/password change) thus invalidates every token issued beforehand,
    reproducing the finality that CICS sign-off gave a terminal session.

    Args:
        request: The incoming request, carrying the session cookie / bearer
            token.
        db: The request-scoped async session provided by :func:`get_db`.

    Returns:
        The authenticated :class:`~app.models.user.User` (identity + role).

    Raises:
        HTTPException: With status 401 when no valid token is present, the
            referenced user does not exist, or the token's session version has
            been revoked.
    """
    rawToken = ResolveRequestToken(request)
    if not rawToken:
        RaiseUnauthorized(NOT_AUTHENTICATED_DETAIL)
    try:
        tokenClaims = DecodeAccessToken(rawToken)
    except AuthenticationError as authError:
        RaiseUnauthorized(str(authError), authError)
    subject = tokenClaims.get(SUBJECT_CLAIM)
    result = await db.execute(select(User).where(User.user_id == subject))
    currentUser = result.scalar_one_or_none()
    if currentUser is None:
        RaiseUnauthorized(INVALID_CREDENTIALS_DETAIL)
    # M-02 revocation gate: the token's session generation must still match the
    # user's stored one. A missing claim (token minted before revocation was
    # introduced) is treated as a mismatch and rejected, failing closed.
    tokenSessionVersion = tokenClaims.get(SESSION_VERSION_CLAIM)
    if tokenSessionVersion != currentUser.session_version:
        RaiseUnauthorized(REVOKED_CREDENTIALS_DETAIL)
    return currentUser


async def require_admin(
    currentUser: User = Depends(get_current_user),
) -> User:
    """Authorize an admin-only route, gating on the ported role rule.

    Reproduces the CardDemo admin check that keyed off the COMMAREA
    ``CDEMO-USER-TYPE`` field with condition name ``CDEMO-USRTYP-ADMIN VALUE
    'A'`` (``COCOM01Y``). The authenticated user's ``user_type`` must equal
    :data:`ADMIN_USER_TYPE` (``'A'``); any other value (for example the regular
    ``'U'``) is rejected with HTTP 403. This dependency guards the admin menu
    and the user-management endpoints (CU00-CU03; AAP 0.4.4 / 0.5.5
    ``/admin/users``).

    Args:
        currentUser: The authenticated user resolved by
            :func:`get_current_user`.

    Returns:
        The same :class:`~app.models.user.User` when it is an administrator, so
        the value can be reused by the route handler.

    Raises:
        HTTPException: With status 403 when the user is not an administrator.
    """
    if currentUser.user_type != ADMIN_USER_TYPE:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=ADMIN_REQUIRED_DETAIL,
        )
    return currentUser
