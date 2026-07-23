# CardDemo authentication router (thin HTTP<->service adapter). Ports
# app/cbl/COSGN00C.cbl -- "Signon Screen for the CardDemo Application", CICS
# transaction CC00, BMS map COSGN00. All sign-on business logic lives in
# app.services.AuthService and app.core.security; this router holds none.
"""CardDemo authentication router.

Ported from COBOL online program COSGN00C (CICS tx CC00, BMS map COSGN00).
Signon screen -> stateless REST login. Business logic lives in
app.services.auth_service.AuthService; this router only translates HTTP<->service.

The single ``POST /login`` endpoint is the one unauthenticated route in the API
(it is how a caller authenticates), so it deliberately does NOT depend on
``get_current_user`` / ``require_admin``. On success it returns the caller's
identity (``LoginResponse``) and, under the ``session`` baseline (AAP 0.8.4),
sets an HTTP-only session cookie carrying a signed token; under the ``jwt``
alternative the service already placed a bearer token on the response body and
the router sets no cookie. Invalid credentials raise
``app.core.exceptions.AuthenticationError`` inside the service, which is left to
bubble up to the application-level handler registered in ``app.main`` (it is
mapped to HTTP 401); this router never catches it.

This sub-router owns only the ``/auth`` resource prefix; the version-1 API
prefix (``settings.API_V1_PREFIX``) is applied once by ``app.main`` when the
router is mounted, so it is never hardcoded here. The mounted login path is
therefore ``settings.API_V1_PREFIX`` + ``/auth/login``.
"""

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.dependencies import ResolveRequestToken, get_db
from app.core.exceptions import AuthenticationError
from app.core.rate_limiter import UNKNOWN_CLIENT_HOST, loginRateLimiter
from app.core.security import CreateAccessToken
from app.schemas import LoginRequest, LoginResponse, MessageResponse
from app.services import AuthService

# ---------------------------------------------------------------------------
# Module constants (ALL_UPPERCASE per the Ochs Rule 0.8.2). Naming these keeps
# the handler free of magic-string literals and makes the auth-mode / cookie
# policy self-documenting.
# ---------------------------------------------------------------------------

# settings.AUTH_MODE value (AAP 0.8.4 baseline) under which the signed token is
# carried in the session cookie rather than the response body. Mirrors the
# "session" member of the settings.AUTH_MODE Literal.
SESSION_AUTH_MODE = "session"

# SameSite policy for the session cookie: "lax" lets top-level navigations send
# the cookie while withholding it on cross-site subrequests (a CSRF mitigation).
SESSION_COOKIE_SAMESITE = "lax"

# settings.ENVIRONMENT value for local development. Outside development the
# session cookie is marked Secure (HTTPS-only); in development it is not, so the
# cookie still works over plain-HTTP localhost.
DEVELOPMENT_ENVIRONMENT = "development"

# Path attribute the session cookie is scoped to. It must be identical on
# set_cookie (login) and delete_cookie (logout): a browser only removes a cookie
# when the delete directive's path (and SameSite/Secure) match those used to set
# it, so the value is named once here and reused by both handlers.
SESSION_COOKIE_PATH = "/"

# Confirmation text returned by POST /logout. Kept as a named constant (Ochs
# no-magic-strings) rather than an inline literal in the handler.
LOGOUT_MESSAGE = "Signed out successfully."

# Generic 429 detail returned when the login throttle is engaged (QA finding
# M-01). It is intentionally uniform and does not reveal whether the user id
# exists or how many attempts remain, preserving the anti-enumeration posture of
# the sign-on flow (the failure counter itself lives in app.core.rate_limiter).
LOGIN_THROTTLED_DETAIL = "Too many failed sign-on attempts. Try again later."


def _ResolveClientHost(request: Request) -> str:
    """Return the request's client host, or a stable sentinel when unavailable.

    Starlette populates ``request.client`` from the ASGI ``client`` scope, but
    some transports (notably httpx's ``ASGITransport`` used in tests) leave it
    ``None``. Falling back to :data:`~app.core.rate_limiter.UNKNOWN_CLIENT_HOST`
    keeps the throttle key well-defined in every environment.

    Args:
        request: The incoming sign-on request.

    Returns:
        The peer host string, or the unknown-host sentinel.
    """
    if request.client is None:
        return UNKNOWN_CLIENT_HOST
    return request.client.host or UNKNOWN_CLIENT_HOST


router = APIRouter(prefix="/auth", tags=["auth"])


@router.post(
    "/login",
    response_model=LoginResponse,
    status_code=status.HTTP_200_OK,
)
async def Login(
    loginRequest: LoginRequest,
    request: Request,
    response: Response,
    session: AsyncSession = Depends(get_db),
) -> LoginResponse:
    """Authenticate a sign-on request (COSGN00C / CC00).

    Delegates credential verification to ``AuthService`` (the ported COSGN00C
    sign-on logic) and returns the authenticated caller's identity. Under the
    ``session`` baseline the signed token is set as an HTTP-only cookie; under
    the ``jwt`` alternative the service already placed a bearer token on the
    response body and no cookie is set.

    Brute-force throttling (QA finding M-01): the ``(user id, client IP)`` pair
    is checked against :data:`~app.core.rate_limiter.loginRateLimiter` BEFORE the
    credential check. While the pair is locked out the endpoint returns a generic
    HTTP 429 without ever verifying the password. A genuine credential failure
    (``AuthenticationError``) registers one attempt; a blank-field edit
    (``DomainValidationError``) does not, because it is not a password guess; a
    success clears the pair's counter.

    Args:
        loginRequest: The submitted sign-on credentials (user id + password),
            validated and sanitized by the ``LoginRequest`` schema.
        request: The incoming request, read to derive the client host for the
            throttle key.
        response: The outgoing response, used to set the session cookie under
            the session baseline.
        session: The request-scoped async database session provided by
            ``get_db``; forwarded to the service and never used directly here.

    Returns:
        The authenticated caller's identity as a ``LoginResponse``. In JWT mode
        it also carries the bearer ``access_token`` / ``token_type``; under the
        session baseline those fields are ``None`` and the token travels in the
        cookie instead.

    Raises:
        HTTPException: With status 429 when the ``(user id, client IP)`` pair is
            currently locked out by the login throttle.
        AuthenticationError: If the credentials are invalid. It is intentionally
            not caught here (only observed to advance the throttle counter, then
            re-raised); ``app.main`` maps it to an HTTP 401 response.
    """
    # M-01 throttle: reject a locked-out (user id, client IP) pair up front so a
    # brute-force run cannot even reach the password check.
    throttleKey = loginRateLimiter.BuildKey(
        loginRequest.user_id, _ResolveClientHost(request)
    )
    if loginRateLimiter.IsLocked(throttleKey):
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=LOGIN_THROTTLED_DETAIL,
        )
    try:
        loginResponse = await AuthService().Login(session, loginRequest)
    except AuthenticationError:
        # A real credential failure counts toward the lockout. A blank-field
        # DomainValidationError is deliberately NOT caught here, so it does not
        # advance the counter (it is a client edit, not a password guess).
        loginRateLimiter.RegisterFailure(throttleKey)
        raise
    # Successful sign-on: clear any accumulated failures for this pair.
    loginRateLimiter.RegisterSuccess(throttleKey)
    if settings.AUTH_MODE == SESSION_AUTH_MODE:
        # Session baseline: mint the signed token and set it as an HTTP-only
        # cookie. The token is deliberately NOT echoed in the response body
        # (loginResponse.access_token stays None), keeping it out of JS reach.
        # M-02: embed the user's current session generation as the ``sver``
        # claim (carried on loginResponse, excluded from the JSON body) so the
        # cookie is revocable server-side -- logout and role/password changes
        # advance session_version, which get_current_user then rejects.
        sessionToken = CreateAccessToken(
            loginResponse.user_id,
            loginResponse.user_type,
            sessionVersion=loginResponse.session_version,
        )
        response.set_cookie(
            key=settings.SESSION_COOKIE_NAME,
            value=sessionToken,
            httponly=True,
            samesite=SESSION_COOKIE_SAMESITE,
            secure=settings.ENVIRONMENT != DEVELOPMENT_ENVIRONMENT,
            max_age=settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
            path=SESSION_COOKIE_PATH,
        )
        return loginResponse
    # JWT alternative: AuthService already populated access_token / token_type
    # on loginResponse, so the bearer token travels in the response body and no
    # session cookie is set here.
    return loginResponse


@router.post(
    "/logout",
    response_model=MessageResponse,
    status_code=status.HTTP_200_OK,
)
async def Logout(
    request: Request,
    response: Response,
    session: AsyncSession = Depends(get_db),
) -> MessageResponse:
    """Sign out the caller, revoking the session server-side (COSGN00C exit).

    Authentication is stateless: identity is carried by the signed token in the
    HTTP-only ``settings.SESSION_COOKIE_NAME`` cookie (or a bearer header under
    the JWT alternative). Logging out performs BOTH halves of a real sign-off
    (M-02):

    1. Server-side revocation -- the token's subject has its ``session_version``
       advanced (:meth:`AuthService.RevokeSession`), so every token minted before
       this call, whose frozen ``sver`` claim no longer matches, is rejected by
       ``get_current_user`` on its next use. This closes the gap where a captured
       pre-logout cookie remained valid because only the client copy was deleted.
    2. Client-side deletion -- the browser is instructed to delete the session
       cookie so it stops presenting the (now-revoked) credential.

    This endpoint deliberately takes NO authentication dependency: logout must
    succeed (be idempotent) even when the token is already missing or expired, so
    calling it is always safe and never itself returns 401. Revocation is
    best-effort for the same reason -- an absent or undecodable token revokes
    nothing and the confirmation is still returned.

    The delete directive repeats the login cookie's ``path``, ``samesite`` and
    ``secure`` attributes verbatim; a browser only removes a cookie when these
    match the ones used to set it, so a mismatch would silently leave the cookie
    (and thus the session) in place.

    Args:
        request: The incoming request, read (never mutated) to recover the token
            to revoke from the session cookie / bearer header.
        response: The outgoing response, used to emit the cookie-deletion
            ``Set-Cookie`` header.
        session: The request-scoped async session provided by :func:`get_db`,
            used to persist the ``session_version`` bump.

    Returns:
        A ``MessageResponse`` confirming sign-out. The confirmation is returned
        regardless of whether a session cookie was actually present or the token
        was still valid, preserving idempotency.
    """
    # Server-side revocation first (M-02): advance the subject's session_version
    # so the presented token -- and any sibling token for the same user -- can no
    # longer authenticate. Best-effort/idempotent: a missing or already-invalid
    # token revokes nothing (see AuthService.RevokeSession).
    rawToken = ResolveRequestToken(request)
    await AuthService().RevokeSession(session, rawToken)
    # Only the session baseline sets a cookie, so only it needs to clear one.
    # Under the JWT alternative there is no server-set cookie to remove (the
    # client discards its bearer token), so this is a no-op body.
    if settings.AUTH_MODE == SESSION_AUTH_MODE:
        response.delete_cookie(
            key=settings.SESSION_COOKIE_NAME,
            path=SESSION_COOKIE_PATH,
            samesite=SESSION_COOKIE_SAMESITE,
            secure=settings.ENVIRONMENT != DEVELOPMENT_ENVIRONMENT,
            httponly=True,
        )
    return MessageResponse(message=LOGOUT_MESSAGE)
