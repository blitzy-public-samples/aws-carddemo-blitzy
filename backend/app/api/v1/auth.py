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

from fastapi import APIRouter, Depends, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.dependencies import get_db
from app.core.security import CreateAccessToken
from app.schemas import LoginRequest, LoginResponse
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

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post(
    "/login",
    response_model=LoginResponse,
    status_code=status.HTTP_200_OK,
)
async def Login(
    loginRequest: LoginRequest,
    response: Response,
    session: AsyncSession = Depends(get_db),
) -> LoginResponse:
    """Authenticate a sign-on request (COSGN00C / CC00).

    Delegates credential verification to ``AuthService`` (the ported COSGN00C
    sign-on logic) and returns the authenticated caller's identity. Under the
    ``session`` baseline the signed token is set as an HTTP-only cookie; under
    the ``jwt`` alternative the service already placed a bearer token on the
    response body and no cookie is set.

    Args:
        loginRequest: The submitted sign-on credentials (user id + password),
            validated and sanitized by the ``LoginRequest`` schema.
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
        AuthenticationError: If the credentials are invalid. It is intentionally
            not caught here; ``app.main`` maps it to an HTTP 401 response.
    """
    loginResponse = await AuthService().Login(session, loginRequest)
    if settings.AUTH_MODE == SESSION_AUTH_MODE:
        # Session baseline: mint the signed token and set it as an HTTP-only
        # cookie. The token is deliberately NOT echoed in the response body
        # (loginResponse.access_token stays None), keeping it out of JS reach.
        sessionToken = CreateAccessToken(
            loginResponse.user_id,
            loginResponse.user_type,
        )
        response.set_cookie(
            key=settings.SESSION_COOKIE_NAME,
            value=sessionToken,
            httponly=True,
            samesite=SESSION_COOKIE_SAMESITE,
            secure=settings.ENVIRONMENT != DEVELOPMENT_ENVIRONMENT,
            max_age=settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
        )
        return loginResponse
    # JWT alternative: AuthService already populated access_token / token_type
    # on loginResponse, so the bearer token travels in the response body and no
    # session cookie is set here.
    return loginResponse
