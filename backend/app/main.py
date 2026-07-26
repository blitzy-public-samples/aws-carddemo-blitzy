# Composition root for the modernized CardDemo backend. This module is the
# single wiring point that assembles the ported online programs
# (app/cbl/CO*C: COSGN00C, COMEN01C, COADM01C, COACTVWC, COACTUPC, COCRDLIC,
# COCRDSLC, COCRDUPC, COTRN00C, COTRN01C, COTRN02C, CORPT00C, COBIL00C and
# COUSR00C-03C) into one FastAPI application. Each program's business logic
# lives in its own service module (app/services/*_service.py) and is exposed
# through the versioned routers mounted below; this file owns only the
# composition, never the business logic itself.
"""FastAPI application factory and ASGI entrypoint for the CardDemo backend.

This module defines the :func:`create_application` application factory and the
async :func:`Lifespan` context manager, wires cross-cutting middleware (CORS),
mounts the versioned v1 API routers under ``settings.API_V1_PREFIX``, registers
domain-exception handlers, and exposes the module-level :data:`app` object that
the ASGI server runs.

Serving contract:
    The container image and root ``docker-compose.yml`` start the process with
    ``uvicorn app.main:app --host 0.0.0.0 --port 8000``. The module-level
    :data:`app` attribute defined at the bottom of this file is therefore a
    hard, external contract: it must exist and be a :class:`fastapi.FastAPI`
    instance. It is built exactly once, at import, by
    :func:`create_application`.

Configuration:
    Every tunable value -- project title, API prefix, allowed CORS origins and
    the debug flag -- is read from :data:`app.core.config.settings`
    (``pydantic-settings``, sourced from the environment). No secret, host,
    port, or origin is hardcoded here (Ochs Rule #3).

Example:
    Run the API locally with the ASGI server::

        uvicorn app.main:app --host 0.0.0.0 --port 8000

    Or construct an isolated instance inside a test::

        from app.main import create_application

        testApp = create_application()
"""

import logging
import math
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from contextvars import ContextVar
from urllib.parse import urlsplit

from fastapi import FastAPI, Request, status
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.openapi.utils import get_openapi
from fastapi.responses import JSONResponse, Response
from fastapi.routing import APIRoute
from pydantic import ValidationError as PydanticValidationError
from sqlalchemy.exc import DataError, SQLAlchemyError
from starlette.datastructures import Headers, MutableHeaders
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.config import settings
from app.core.correlation import (
    INGRESS_HEADER_NAMES,
    REQUEST_STATE_ATTRIBUTE,
    RESPONSE_HEADER_NAME,
    BindCorrelationId,
    GetCorrelationId,
    InstallCorrelationIdLogFilter,
    ResetCorrelationId,
    SanitizeCorrelationId,
)
from app.core.exceptions import (
    AuthenticationError,
    AuthorizationError,
    CardDemoError,
    ConflictError,
    DomainValidationError,
    NotFoundError,
    TransactionPostingError,
)
from app.core.log_masking import InstallPanMaskingFilter
from app.db.session import CheckDatabaseReady, DatabaseNotReadyError, engine

# Module-level logger for infrastructure events only (for example, a failure
# while disposing the database engine at shutdown). Business events are logged
# by the service layer, not here.
_LOGGER = logging.getLogger(__name__)

# Root-level readiness/liveness probe path and its static response body. The
# health route is intentionally cheap and dependency-free -- it never touches
# the database -- so orchestrators can poll it without loading the pool.
HEALTH_ROUTE_PATH = "/health"
HEALTH_RESPONSE: dict[str, str] = {"status": "ok"}

# Root-level READINESS probe path (QA finding F5). Unlike the liveness route, the
# readiness route asserts the backend can actually serve traffic by querying a
# core application table in PostgreSQL: it returns 200 only when the database is
# reachable AND the schema is present, and 503 otherwise, so docker-compose (and
# any orchestrator) can gate dependent services on a genuinely ready backend
# rather than a merely running process.
HEALTH_READY_ROUTE_PATH = "/health/ready"
HEALTH_READY_OK: dict[str, str] = {"status": "ready"}
HEALTH_READY_UNAVAILABLE: dict[str, str] = {"status": "unavailable"}
# The readiness probe itself (schema-aware query + bounded, pool-bypassing
# connection) lives in ``app.db.session.CheckDatabaseReady`` (QA findings #6 and
# #7); this module only maps its success/failure to 200/503 in the route below.

# Field names whose submitted value must never be echoed back in a 422 request
# validation response (QA finding F7). FastAPI's default RequestValidationError
# handler reflects the offending ``input`` verbatim; for these fields that would
# leak a secret (a password too long/short, a CVV, an SSN, a bearer token), so
# the custom handler below replaces their ``input`` with a fixed placeholder.
SENSITIVE_FIELD_NAMES: frozenset[str] = frozenset(
    {"password", "cvv", "cvv_cd", "ssn", "secret", "secret_key", "token", "access_token"}
)
# Placeholder substituted for any sensitive submitted value in a 422 body.
SENSITIVE_VALUE_PLACEHOLDER = "***redacted***"

# Placeholder substituted for any non-JSON-compliant float (IEEE-754 NaN,
# +Infinity, -Infinity) encountered anywhere in a validation-error payload
# before it is serialized (QA SECURITY finding: NaN/Infinity DoS). Python's
# ``json.loads`` ACCEPTS these values from a request body via a literal such as
# ``1e400``/``NaN``, so a caller can smuggle one onto ANY field -- including an
# unknown/extra field that bypasses every field validator -- and it then lands
# in ``RequestValidationError.errors()`` as the offending ``input``. The 422
# handlers below render with FastAPI's ``JSONResponse`` (``json.dumps`` with
# ``allow_nan=False``), which RAISES ``ValueError`` on such a value; because that
# raise happens INSIDE the exception handler, Starlette turns the intended 422
# into an unhandled 500 -- a pre-auth denial-of-service. Neutralizing the value
# with this placeholder keeps the handler total.
NON_FINITE_FLOAT_PLACEHOLDER = "<non-finite>"

# Mapping from a general domain-exception type to the HTTP status code it
# surfaces as. Because Starlette resolves handlers along the exception's MRO,
# the ``ConflictError`` handler also covers its subclass ``OptimisticLockError``
# (the COACTUPC optimistic-lock conflict), while the separately registered
# ``TransactionPostingError`` handler covers every posting-reject subclass
# (CBTRN02C reason codes 100-103 and 109).
_DOMAIN_ERROR_STATUS: dict[type[CardDemoError], int] = {
    NotFoundError: status.HTTP_404_NOT_FOUND,
    AuthenticationError: status.HTTP_401_UNAUTHORIZED,
    AuthorizationError: status.HTTP_403_FORBIDDEN,
    DomainValidationError: status.HTTP_422_UNPROCESSABLE_CONTENT,
    ConflictError: status.HTTP_409_CONFLICT,
}


@asynccontextmanager
async def Lifespan(fastapiApp: FastAPI) -> AsyncIterator[None]:
    """Manage async startup and shutdown for the application.

    Startup installs the sensitive-data masking log filter (QA findings F8 and
    M-06) across every server log path -- the root, uvicorn, uvicorn.error,
    uvicorn.access and sqlalchemy.engine loggers, their handlers, and
    ``logging.lastResort`` -- deferred to here so uvicorn's access/error handlers
    already exist -- and is otherwise minimal: the async engine and session factory are
    already constructed lazily at import of ``app.db.session`` (no socket is
    opened until first use), and the database schema is owned by Alembic
    migrations -- it is never created here. Shutdown disposes the engine's
    connection pool so the process exits cleanly without leaking PostgreSQL
    connections.

    Args:
        fastapiApp: The application instance FastAPI passes in. It is unused
            because every managed resource is a module-level singleton.

    Yields:
        Control back to FastAPI for the lifetime of the running application.
    """
    # Startup: install the sensitive-data masking log filter across the server's
    # loggers and handlers now that uvicorn's access/error handlers exist (QA
    # findings F8 and M-06). This masks card numbers and redacts secret-bearing
    # structured extras on every record field -- message, args, exception
    # traceback (exc_info/exc_text), stack_info and extras -- across the root,
    # uvicorn, sqlalchemy.engine loggers and lastResort, before any handler emits
    # them; the install is idempotent across restarts.
    InstallPanMaskingFilter()
    # Also install the correlation-id log filter (QA finding M-32) across the same
    # server log paths, so every record carries the current request's
    # ``correlation_id`` for structured logs. Deferred to here for the same reason
    # as the masking filter -- uvicorn's access/error handlers already exist -- and
    # is likewise idempotent.
    InstallCorrelationIdLogFilter()
    yield
    # Shutdown: release the async connection pool. A specific SQLAlchemy error
    # is caught and logged (never a bare except) so a teardown hiccup cannot
    # mask the real shutdown reason.
    try:
        await engine.dispose()
    except SQLAlchemyError as disposeError:
        _LOGGER.warning("Failed to dispose the database engine on shutdown: %s", disposeError)


# QA finding F5 (Online Security Gate): static security response headers applied
# to EVERY response by SecurityHeadersMiddleware. Only ``frame-ancestors`` is set
# in the CSP (not a restrictive ``default-src``) so the Swagger UI at ``/docs``
# keeps loading. ``Server`` overrides uvicorn's banner with a static token to
# avoid version/implementation disclosure. HSTS is intentionally NOT in this list
# -- it is added separately and only in production (there is no HTTPS in dev).
_SECURITY_HEADERS: tuple[tuple[str, str], ...] = (
    ("X-Content-Type-Options", "nosniff"),
    ("X-Frame-Options", "DENY"),
    ("Content-Security-Policy", "frame-ancestors 'none'"),
    ("Referrer-Policy", "no-referrer"),
    ("Cache-Control", "no-store"),
    ("Pragma", "no-cache"),
    ("Server", "CardDemo"),
)
# HSTS value used only when ``settings.ENVIRONMENT == 'production'`` (HTTPS).
_HSTS_HEADER_NAME = "Strict-Transport-Security"
_HSTS_HEADER_VALUE = "max-age=31536000; includeSubDomains"


class SecurityHeadersMiddleware:
    """Pure ASGI middleware that attaches security headers to every response.

    Implemented at the ASGI layer (not as a ``BaseHTTPMiddleware``) so the
    headers are attached to EVERY response type -- including the streaming CSV
    (``StreamingResponse``) and binary PDF (``Response``) report downloads, and
    error responses -- rather than only JSON bodies. This closes the systemic
    missing-security-header gap the Online Security Gate reported (QA finding
    F5): MIME-sniffing (``X-Content-Type-Options``), clickjacking
    (``X-Frame-Options`` + CSP ``frame-ancestors``), referrer leakage
    (``Referrer-Policy``), and -- most material -- caching of sensitive PII and
    financial CSV/PDF downloads (``Cache-Control``/``Pragma``). HSTS is added
    only in production; in development there is no HTTPS, so it is omitted per
    the finding's note.

    The middleware edits the ``http.response.start`` message headers via
    :class:`~starlette.datastructures.MutableHeaders`; non-HTTP scopes (for
    example lifespan) pass straight through untouched.
    """

    def __init__(self, app: ASGIApp) -> None:
        """Store the wrapped ASGI application.

        Args:
            app: The next ASGI application in the middleware chain.
        """
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        """Wrap ``send`` so security headers are injected on the response start.

        Args:
            scope: The ASGI connection scope.
            receive: The ASGI receive callable.
            send: The ASGI send callable to forward messages to.
        """
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        async def SendWithSecurityHeaders(message: Message) -> None:
            if message["type"] == "http.response.start":
                responseHeaders = MutableHeaders(scope=message)
                for headerName, headerValue in _SECURITY_HEADERS:
                    responseHeaders[headerName] = headerValue
                if settings.ENVIRONMENT == "production":
                    responseHeaders[_HSTS_HEADER_NAME] = _HSTS_HEADER_VALUE
            await send(message)

        await self.app(scope, receive, SendWithSecurityHeaders)


def _ReadIngressCorrelationId(scope: Scope) -> str | None:
    """Return the first client-supplied correlation id from the request headers.

    Scans the ASGI ``scope`` headers (a list of lowercased ``(name, value)`` byte
    pairs) for the ingress header names in :data:`INGRESS_HEADER_NAMES`, in order,
    and returns the RAW value of the first match. Validation/sanitization is the
    caller's responsibility (:func:`SanitizeCorrelationId`), keeping this helper a
    pure lookup.

    Args:
        scope: The ASGI HTTP connection scope.

    Returns:
        The raw header value decoded as UTF-8 (latin-1 fallback for a non-UTF-8
        byte string), or ``None`` when no ingress correlation header is present.
    """
    rawHeaders = scope.get("headers") or []
    for headerName, headerValue in rawHeaders:
        if headerName.decode("latin-1").lower() in INGRESS_HEADER_NAMES:
            try:
                return headerValue.decode("utf-8")
            except UnicodeDecodeError:
                return headerValue.decode("latin-1")
    return None


class CorrelationIdMiddleware:
    """Pure ASGI middleware that binds and echoes a per-request correlation id.

    For every HTTP request it resolves a correlation id -- a validated,
    sanitized client-supplied ``X-Request-ID`` / ``X-Correlation-ID`` ingress
    header when offered, otherwise a fresh server-generated id -- binds it to the
    request-scoped context variable (so every coroutine and log record while the
    request is handled carries it), and echoes it on the response
    :data:`~app.core.correlation.RESPONSE_HEADER_NAME` header so the caller can
    correlate its request with the server logs (QA finding M-32).

    Implemented at the ASGI layer (like :class:`SecurityHeadersMiddleware`, not as
    a ``BaseHTTPMiddleware``) so the id is bound before ANY inner layer runs and is
    echoed on EVERY response type -- JSON, the streaming CSV / binary PDF report
    downloads, and error responses. Registered as the OUTERMOST middleware so the
    id is available to CORS, CSRF, the routers, and the exception handlers alike.
    Non-HTTP scopes (for example lifespan) pass straight through untouched.
    """

    def __init__(self, app: ASGIApp) -> None:
        """Store the wrapped ASGI application.

        Args:
            app: The next ASGI application in the middleware chain.
        """
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        """Bind the correlation id for the request and echo it on the response.

        Args:
            scope: The ASGI connection scope.
            receive: The ASGI receive callable.
            send: The ASGI send callable to forward messages to.
        """
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        correlationId = SanitizeCorrelationId(_ReadIngressCorrelationId(scope))
        # Stash the id on the ASGI scope state so request-scoped code AND the
        # catch-all HTTP 500 handler (which runs in Starlette's OUTER
        # ServerErrorMiddleware, above this layer, after this middleware's context
        # binding has been reset) can read the SAME id via ``request.state``.
        scope.setdefault("state", {})[REQUEST_STATE_ATTRIBUTE] = correlationId
        resetToken = BindCorrelationId(correlationId)

        async def SendWithCorrelationId(message: Message) -> None:
            if message["type"] == "http.response.start":
                responseHeaders = MutableHeaders(scope=message)
                responseHeaders[RESPONSE_HEADER_NAME] = correlationId
            await send(message)

        try:
            await self.app(scope, receive, SendWithCorrelationId)
        finally:
            # Restore the prior context value so a reused task/context never leaks
            # this request's id into an unrelated scope.
            ResetCorrelationId(resetToken)


# ---------------------------------------------------------------------------
# CSRF / Origin-check policy constants (QA finding M-02).
#
# HTTP methods that are "safe" (read-only / non-mutating) per RFC 9110 and never
# require a CSRF origin check: a cross-site GET/HEAD/OPTIONS cannot itself change
# server state, and OPTIONS in particular is the CORS preflight, which must pass
# through so the browser's real request can proceed.
# ---------------------------------------------------------------------------
_CSRF_SAFE_METHODS: frozenset[str] = frozenset({"GET", "HEAD", "OPTIONS", "TRACE"})
# Session-baseline auth mode (mirrors settings.AUTH_MODE): the CSRF check applies
# only to the cookie transport, because a bearer token in the Authorization
# header is not attached automatically by the browser and so is not forgeable
# cross-site the way an ambient cookie is.
_CSRF_SESSION_AUTH_MODE = "session"
# Request headers consulted to establish the initiating origin, in preference
# order: the Origin header is authoritative and present on all modern cross-site
# state-changing requests; the Referer is the fallback when Origin is absent.
_ORIGIN_HEADER_NAME = "origin"
_REFERER_HEADER_NAME = "referer"
# Generic 403 body returned when the origin check fails. It intentionally does
# not echo the offending origin (no reflection) and reads uniformly whether the
# origin was missing or merely not allow-listed.
_CSRF_FAILED_DETAIL = "Origin check failed"


def _ExtractOrigin(headerValue: str | None) -> str | None:
    """Reduce an Origin/Referer header to a bare ``scheme://host[:port]`` origin.

    The Origin header is already an origin, but the Referer is a full URL
    (``scheme://host[:port]/path?query``); both are normalized here to the
    origin triple so they can be compared against the allow-list on equal terms.
    A value that cannot be parsed into both a scheme and a host yields ``None``
    (treated as "no usable origin" by the caller, which then blocks).

    Args:
        headerValue: The raw Origin or Referer header value, or ``None``.

    Returns:
        The normalized ``scheme://netloc`` origin, or ``None`` when the value is
        absent or not a parseable absolute URL.
    """
    if not headerValue:
        return None
    parsed = urlsplit(headerValue)
    if not parsed.scheme or not parsed.netloc:
        return None
    return f"{parsed.scheme}://{parsed.netloc}"


class CsrfProtectionMiddleware(BaseHTTPMiddleware):
    """Origin-based CSRF guard for cookie-authenticated state changes (M-02).

    Closes the QA M-02 gap that no CSRF/Origin control existed for cookie
    mutations. Under the session-cookie baseline the browser attaches the auth
    cookie to EVERY same-site and cross-site request automatically, so a
    malicious page could drive a state-changing call on the victim's behalf. The
    ``SameSite=lax`` attribute on the cookie already blocks the cross-site cases
    the browser recognizes; this middleware adds defense in depth by verifying,
    for every unsafe cookie-authenticated request, that the initiating origin is
    one the deployment explicitly trusts.

    The check runs ONLY when all three conditions hold, so it never interferes
    with legitimate traffic or the JWT alternative:

    1. ``settings.AUTH_MODE`` is the session baseline (cookie transport); the
       bearer-header alternative is not ambiently attached and is exempt.
    2. The request carries the session cookie (``settings.SESSION_COOKIE_NAME``);
       an unauthenticated request has no session to abuse.
    3. The method is state-changing (not one of :data:`_CSRF_SAFE_METHODS`); the
       CORS preflight ``OPTIONS`` therefore passes straight through.

    When the check applies, the request's Origin (or, failing that, Referer)
    must resolve to an origin present in ``settings.BACKEND_CORS_ORIGINS`` -- the
    same allow-list CORS enforces. A missing/unparseable origin OR one that is
    not allow-listed is rejected with a generic HTTP 403 that does not reflect
    the offending value (failing closed).
    """

    async def dispatch(
        self,
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]],
    ) -> Response:
        """Reject disallowed-origin cookie mutations; pass everything else on.

        Args:
            request: The incoming request being evaluated.
            call_next: The downstream handler to invoke when the request is
                allowed to proceed.

        Returns:
            The downstream response when the request is safe/allowed, or a
            generic HTTP 403 :class:`~fastapi.responses.JSONResponse` when the
            origin check applies and fails.

        Note:
            The snake_case method name ``dispatch`` is an intentional, documented
            exception to the Ochs PascalCase-methods rule (Ochs 0.8.2 / 0.8.3):
            it is the Starlette ``BaseHTTPMiddleware`` framework contract -- the
            ASGI stack invokes ``dispatch(request, call_next)`` by name -- so the
            name is framework-mandated, not a free choice. The class's own private
            helpers (``_RequiresOriginCheck``, ``_OriginAllowed``) remain PascalCase.
        """
        if self._RequiresOriginCheck(request) and not self._OriginAllowed(request):
            return JSONResponse(
                status_code=status.HTTP_403_FORBIDDEN,
                content={"detail": _CSRF_FAILED_DETAIL},
            )
        return await call_next(request)

    def _RequiresOriginCheck(self, request: Request) -> bool:
        """Report whether this request must pass the origin check.

        Args:
            request: The incoming request.

        Returns:
            ``True`` only when the session baseline is active, a session cookie
            is present, and the method is state-changing; ``False`` otherwise.
        """
        if settings.AUTH_MODE != _CSRF_SESSION_AUTH_MODE:
            return False
        if request.method in _CSRF_SAFE_METHODS:
            return False
        return settings.SESSION_COOKIE_NAME in request.cookies

    def _OriginAllowed(self, request: Request) -> bool:
        """Report whether the request's origin is on the trusted allow-list.

        The Origin header is preferred; the Referer is the fallback when Origin
        is absent. The resolved origin must match one of
        ``settings.BACKEND_CORS_ORIGINS`` exactly (after normalization). A
        missing or unparseable origin returns ``False`` (fail closed).

        Args:
            request: The incoming request.

        Returns:
            ``True`` when a usable origin is present and allow-listed, else
            ``False``.
        """
        rawOrigin = request.headers.get(_ORIGIN_HEADER_NAME)
        if rawOrigin is None:
            rawOrigin = request.headers.get(_REFERER_HEADER_NAME)
        requestOrigin = _ExtractOrigin(rawOrigin)
        if requestOrigin is None:
            return False
        return requestOrigin in settings.BACKEND_CORS_ORIGINS


# Response-compression tuning (QA finding M-02 performance: uncompressed JSON
# list/report payloads reached ~9.8 MB). Only bodies at or above
# ``GZIP_MINIMUM_SIZE`` bytes are compressed, so tiny responses -- the health
# probes, the sign-on result, single-record views -- are left untouched and pay
# no CPU cost. ``GZIP_COMPRESS_LEVEL`` is a deliberate mid-range zlib level: the
# compression runs synchronously in the ASGI send path, so a moderate level
# keeps the event-loop cost low (consistent with the CRITICAL-#1 event-loop
# discipline) while still shrinking large JSON by more than an order of
# magnitude.
GZIP_MINIMUM_SIZE = 1000
GZIP_COMPRESS_LEVEL = 6


def _ConfigureMiddleware(fastapiApp: FastAPI) -> None:
    """Register cross-cutting middleware on the application.

    Middleware is registered inner-to-outer (Starlette treats the LAST
    ``add_middleware`` call as the OUTERMOST layer), producing this response
    chain from outermost to innermost: ``CorrelationId`` -> ``SecurityHeaders``
    -> ``CORS`` -> ``CsrfProtection`` -> ``GZip`` -> routers.

    * :class:`~fastapi.middleware.gzip.GZipMiddleware` is added FIRST so it is
      the INNERMOST layer, wrapping the routers directly. It compresses the
      response body (setting ``Content-Encoding: gzip``, ``Vary:
      Accept-Encoding`` and the compressed ``Content-Length``) only when the
      client advertises ``Accept-Encoding: gzip`` AND the body is at least
      :data:`GZIP_MINIMUM_SIZE` bytes -- eliminating the multi-megabyte
      uncompressed JSON payloads (QA finding M-02) while leaving small responses
      (the health probes, the sign-on result, single-record views) and clients
      that do not negotiate gzip byte-for-byte unchanged. It MUST be innermost:
      Starlette's ``GZipMiddleware`` only honors ``minimum_size`` when it
      receives the router's response as a single, complete body message
      (``more_body`` False); the OUTER :class:`CsrfProtectionMiddleware` is a
      ``BaseHTTPMiddleware`` that re-emits every response as a stream, which
      would otherwise force the streaming-compression path and compress even
      tiny bodies. The compressed body is transparently inflated by the
      browser/HTTP client, so the CSV (``StreamingResponse``) and PDF (binary
      ``Response``) downloads decode to exactly the same bytes as before.
    * :class:`CsrfProtectionMiddleware` is added next so its origin check for
      cookie mutations (QA finding M-02) runs just before the router (only GZip
      is inner of it), yet its 403 response still travels back out through CORS
      (gaining the CORS headers a browser needs to read it) and SecurityHeaders.
    * Starlette's CORS middleware is added next so the Next.js frontend -- whose
      origin is listed in ``settings.BACKEND_CORS_ORIGINS`` (for example
      ``http://localhost:3000``) -- may call the API with credentials.
      ``allow_credentials`` is enabled because the session-cookie baseline
      requires the browser to send the auth cookie cross-origin, and CORS being
      OUTER of CSRF means the preflight ``OPTIONS`` is answered here without ever
      reaching the CSRF or GZip layers. Session/JWT decoding is still performed
      by the ``get_current_user`` dependency, not by middleware.
    * :class:`SecurityHeadersMiddleware` is added next so it wraps every outgoing
      response -- including CORS preflight responses, the CSRF 403, the
      gzip-compressed JSON, and the streaming CSV/PDF downloads -- with the
      security headers required by QA finding F5.
    * :class:`CorrelationIdMiddleware` is added LAST so it is the OUTERMOST layer
      (QA finding M-32): it binds the request-scoped correlation id BEFORE any
      inner layer or exception handler runs -- so CORS, CSRF, the routers, the
      log records, and :func:`_HandleUnexpectedError` all observe the same id --
      and echoes that id on the ``X-Request-ID`` response header of every
      response type.

    Args:
        fastapiApp: The application instance to configure.
    """
    fastapiApp.add_middleware(
        GZipMiddleware,
        minimum_size=GZIP_MINIMUM_SIZE,
        compresslevel=GZIP_COMPRESS_LEVEL,
    )
    fastapiApp.add_middleware(CsrfProtectionMiddleware)
    fastapiApp.add_middleware(
        CORSMiddleware,
        allow_origins=settings.BACKEND_CORS_ORIGINS,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    fastapiApp.add_middleware(SecurityHeadersMiddleware)
    fastapiApp.add_middleware(CorrelationIdMiddleware)


def _MountRouters(fastapiApp: FastAPI) -> None:
    """Mount all version-1 API routers under the configured prefix.

    Prefers the aggregate ``api_router`` assembled in ``app.api.v1`` (a single
    include) and falls back to including each router module's own ``router``
    when that aggregate is unavailable. Both paths mount the identical set of
    routes under ``settings.API_V1_PREFIX`` (``/api/v1``), producing the REST
    surface enumerated in AAP section 0.5.5.

    Args:
        fastapiApp: The application instance onto which routers are mounted.
    """
    apiPrefix = settings.API_V1_PREFIX
    try:
        # PREFERRED: one aggregate router composed in app/api/v1/__init__.py.
        from app.api.v1 import api_router

        fastapiApp.include_router(api_router, prefix=apiPrefix)
    except ImportError:
        # Documented fallback: include each router module's own `router`
        # object. This yields the same mounted routes as the aggregate.
        from app.api.v1 import (
            accounts,
            auth,
            billpay,
            cards,
            menu,
            reports,
            transactions,
            users,
        )

        routerModules = (auth, menu, accounts, cards, transactions, reports, billpay, users)
        for routerModule in routerModules:
            fastapiApp.include_router(routerModule.router, prefix=apiPrefix)


def _MakeDomainErrorHandler(
    statusCode: int,
) -> Callable[[Request, CardDemoError], Awaitable[JSONResponse]]:
    """Build a JSON exception handler that responds with a fixed status code.

    Args:
        statusCode: The HTTP status code the returned handler responds with.

    Returns:
        An async handler that renders the raised domain error's message as a
        JSON ``{"detail": ...}`` body under ``statusCode``.
    """

    async def _DomainErrorHandler(request: Request, exc: CardDemoError) -> JSONResponse:
        return JSONResponse(status_code=statusCode, content={"detail": exc.message})

    return _DomainErrorHandler


async def _HandlePostingReject(request: Request, exc: TransactionPostingError) -> JSONResponse:
    """Render a transaction-posting reject as an HTTP 422 response.

    Preserves the exact legacy reject ``code`` and ``description`` (CBTRN02C
    reason codes 100-103 and 109) in the response body so a client can reconcile
    them against the batch reject record.

    Args:
        request: The incoming request. Unused; required by the handler contract.
        exc: The raised posting-reject exception carrying ``code`` and
            ``description``.

    Returns:
        A JSON response with status 422 and the reject code and description.
    """
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
        content={"detail": exc.message, "code": exc.code, "description": exc.description},
    )


def _NameIsSensitive(fieldName: object) -> bool:
    """Report whether a field name identifies sensitive input to be redacted.

    Args:
        fieldName: A candidate name, typically one element of a validation
            error's ``loc`` tuple or a key of a submitted body object.

    Returns:
        ``True`` when ``fieldName`` is a string whose lowercase form is in
        :data:`SENSITIVE_FIELD_NAMES`; ``False`` otherwise.
    """
    return isinstance(fieldName, str) and fieldName.lower() in SENSITIVE_FIELD_NAMES


def _LocationIsSensitive(errorLocation: object) -> bool:
    """Report whether a validation error's location points at a sensitive field.

    Args:
        errorLocation: The ``loc`` value of one Pydantic validation error, for
            example ``("body", "password")``.

    Returns:
        ``True`` when any element of the location is a sensitive field name.
    """
    if not isinstance(errorLocation, (list, tuple)):
        return False
    return any(_NameIsSensitive(locationPart) for locationPart in errorLocation)


def _RedactSensitiveContainers(submittedInput: object) -> object:
    """Recursively redact sensitive keys inside a submitted ``input`` container.

    Handles the case where the offending value is the whole request body (a
    mapping) rather than a single field -- for example a body-level type error
    whose ``input`` is the entire object, which could otherwise echo a
    ``password`` key. Scalars are returned unchanged (a sensitive scalar is
    redacted by the caller via the error location instead).

    Args:
        submittedInput: The ``input`` value reflected by a validation error.

    Returns:
        The value with any nested sensitive keys replaced by
        :data:`SENSITIVE_VALUE_PLACEHOLDER`; non-container values are returned
        unchanged.
    """
    if isinstance(submittedInput, dict):
        return {
            itemKey: (
                SENSITIVE_VALUE_PLACEHOLDER
                if _NameIsSensitive(itemKey)
                else _RedactSensitiveContainers(itemValue)
            )
            for itemKey, itemValue in submittedInput.items()
        }
    if isinstance(submittedInput, (list, tuple)):
        return [_RedactSensitiveContainers(listItem) for listItem in submittedInput]
    return submittedInput


def _SanitizeOneValidationError(rawError: dict) -> dict:
    """Return a copy of one validation error with any sensitive input redacted.

    Two leak paths are closed: a field-level error whose ``loc`` names a
    sensitive field has its scalar ``input`` replaced wholesale, and any other
    error whose ``input`` is a container has its nested sensitive keys redacted.

    Args:
        rawError: One entry from ``RequestValidationError.errors()``.

    Returns:
        A shallow copy of the error with its ``input`` sanitized.
    """
    sanitizedError = dict(rawError)
    if "input" not in sanitizedError:
        return sanitizedError
    if _LocationIsSensitive(sanitizedError.get("loc", ())):
        sanitizedError["input"] = SENSITIVE_VALUE_PLACEHOLDER
    else:
        sanitizedError["input"] = _RedactSensitiveContainers(sanitizedError["input"])
    return sanitizedError


def _ReplaceNonFiniteFloats(value: object) -> object:
    """Recursively replace non-JSON-compliant floats with a safe placeholder.

    IEEE-754 ``NaN``, ``Infinity`` and ``-Infinity`` are accepted by Python's
    ``json.loads`` -- so a request body may legally carry one through a literal
    such as ``1e400`` or ``NaN`` on any field -- but are rejected by
    ``json.dumps(allow_nan=False)``, which is exactly the encoder FastAPI's
    :class:`~fastapi.responses.JSONResponse` uses. If such a value survives into
    the body of a validation-error handler, the response renderer raises
    :class:`ValueError` *inside* the handler and Starlette converts the intended
    422 into an unhandled 500 (a pre-auth denial-of-service). Walking the
    already-encoded error payload here and substituting
    :data:`NON_FINITE_FLOAT_PLACEHOLDER` for every non-finite ``float`` keeps the
    handler total while preserving the exact response shape for every other
    value.

    Args:
        value: An arbitrary value drawn from a validation-error payload -- a
            scalar, or a ``dict``/``list``/``tuple`` nesting other values.

    Returns:
        The value with every non-finite ``float`` (at any nesting depth)
        replaced by :data:`NON_FINITE_FLOAT_PLACEHOLDER`; all other values are
        returned structurally unchanged.
    """
    if isinstance(value, float):
        if math.isfinite(value):
            return value
        return NON_FINITE_FLOAT_PLACEHOLDER
    if isinstance(value, dict):
        return {
            itemKey: _ReplaceNonFiniteFloats(itemValue)
            for itemKey, itemValue in value.items()
        }
    if isinstance(value, (list, tuple)):
        return [_ReplaceNonFiniteFloats(listItem) for listItem in value]
    return value


async def _HandleRequestValidationError(
    request: Request, exc: RequestValidationError
) -> JSONResponse:
    """Return a 422 whose body never echoes a sensitive submitted value (F7).

    FastAPI's default request-validation handler reflects each offending
    ``input`` verbatim; for a password/CVV/SSN/token field that reflection is a
    sensitive-data leak. This replacement preserves the exact default response
    shape (status 422, ``{"detail": [...]}`` of Pydantic errors, JSON-encoded
    identically) while redacting only the sensitive inputs.

    Args:
        request: The incoming request. Unused; required by the handler contract.
        exc: The raised request-validation error carrying the per-field errors.

    Returns:
        A JSON 422 response with the sanitized validation error list.
    """
    sanitizedErrors = [_SanitizeOneValidationError(rawError) for rawError in exc.errors()]
    # Sanitize NaN/Infinity floats (which may sit on any field, including an
    # unknown/extra one) as the OUTERMOST step, after ``jsonable_encoder`` has
    # produced the final JSON-able structure, so no non-finite float reaches
    # ``json.dumps(allow_nan=False)`` and turns this 422 into a 500 (QA SECURITY
    # finding: NaN/Infinity DoS).
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
        content={"detail": _ReplaceNonFiniteFloats(jsonable_encoder(sanitizedErrors))},
    )


async def _HandleValidationError(
    request: Request, exc: PydanticValidationError
) -> JSONResponse:
    """Render a raw ``pydantic.ValidationError`` as an HTTP 422 response.

    FastAPI automatically converts a ``RequestValidationError`` (validation of
    the request body/query/path against an endpoint signature) into a 422, but
    a bare ``pydantic.ValidationError`` raised *inside* a dependency callable --
    for example the ``GET /reports/transactions`` date-range model validator,
    which rejects an inverted ``start_date > end_date`` range -- is not
    intercepted by that machinery and would otherwise surface as an unhandled
    500. This handler restores the correct client contract: the same 422 shape
    FastAPI produces for request validation (``{"detail": [ ... ]}``), keeping
    the Pydantic schema validators as the single source of truth for edits.

    Args:
        request: The incoming request. Unused; required by the handler contract.
        exc: The raised Pydantic validation error carrying the field errors.

    Returns:
        A JSON response with status 422 and the structured validation errors.
    """
    # Sanitize NaN/Infinity floats as the OUTERMOST step (see
    # ``_HandleRequestValidationError``): a bare ``ValidationError`` can carry a
    # non-finite ``input`` just as a request-validation error can, and
    # ``json.dumps(allow_nan=False)`` would otherwise raise here and turn this
    # 422 into a 500 (QA SECURITY finding: NaN/Infinity DoS).
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
        content={"detail": _ReplaceNonFiniteFloats(jsonable_encoder(exc.errors()))},
    )


async def _HandleUnexpectedError(request: Request, exc: Exception) -> JSONResponse:
    """Render any otherwise-unhandled exception as a sanitized HTTP 500.

    This is the last-resort safety net. Every anticipated error is already
    mapped to a precise status by the domain handlers above (or, for request
    validation, by FastAPI itself), so reaching here means a genuinely
    unexpected fault. The full exception -- including its traceback -- is logged
    server-side under the request's correlation id for diagnosis, while the client
    receives only a generic body carrying that same id. This never leaks a stack
    trace, file path, SQL statement, or bound parameter (which may include a card
    number) to the caller, closing the information-disclosure exposure that a
    debug-mode error page would otherwise create.

    The correlation id is the one bound by :class:`CorrelationIdMiddleware` for
    this request (QA finding M-32), so the id in the log line, the id in this
    response body, and the id echoed on the ``X-Request-ID`` response header all
    match -- letting an operator tie a client-reported id straight to the
    server-side traceback. (Previously an id was minted here in isolation, tied to
    nothing else.) Because this catch-all handler runs in Starlette's OUTER
    ``ServerErrorMiddleware`` -- above ``CorrelationIdMiddleware``, whose
    context-variable binding has already been reset as the exception unwound -- the
    id is recovered from ``request.state`` (stashed on the shared ASGI scope by the
    middleware), with the context variable as a fallback. The ``X-Request-ID``
    header is set explicitly on this response because the outer middleware sends
    this error response without passing it back through the correlation layer.

    Args:
        request: The incoming request, used to recover the correlation id and to
            log the method and path.
        exc: The unhandled exception.

    Returns:
        A JSON response with status 500 and a generic detail plus the request's
        correlation id (also echoed on the ``X-Request-ID`` header). The response
        body is intentionally free of any internal detail.
    """
    correlationId = getattr(request.state, REQUEST_STATE_ATTRIBUTE, None) or GetCorrelationId()
    _LOGGER.error(
        "Unhandled error [%s] on %s %s",
        correlationId,
        request.method,
        request.url.path,
        exc_info=exc,
    )
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content={"detail": "Internal Server Error", "correlation_id": correlationId},
        headers={RESPONSE_HEADER_NAME: correlationId},
    )


async def _HandleRecursionError(request: Request, exc: RecursionError) -> JSONResponse:
    """Map an over-nested request body to a bounded 400 (QA finding F4).

    A pathologically deeply-nested JSON body (thousands of levels) exhausts
    Python's recursion limit while the body is parsed and validated, raising
    ``RecursionError`` before any route code runs. Without this handler it would
    fall through to the catch-all and surface as a generic 500; mapping it to a
    400 keeps malformed input bounded as a client error (Ochs rule: no generic
    500 on malformed input). The body is deliberately generic and never reflects
    the offending payload.

    Args:
        request: The incoming request, used only to log the method and path.
        exc: The raised recursion error. Unused beyond the handler contract.

    Returns:
        A JSON response with status 400 and a generic detail.
    """
    _LOGGER.warning(
        "Rejected over-nested request body on %s %s",
        request.method,
        request.url.path,
    )
    return JSONResponse(
        status_code=status.HTTP_400_BAD_REQUEST,
        content={"detail": "Malformed request: input structure is too deeply nested."},
    )


async def _HandleDataError(request: Request, exc: DataError) -> JSONResponse:
    """Map a value the database rejects to a bounded 422 (QA finding F4).

    Defense in depth behind the schema-level control-character guard
    (``RequestBase._RejectControlCharacters``): should any user-supplied value
    the validators do not cover reach PostgreSQL and be rejected at the driver
    level (for example an embedded NUL in a text column -- asyncpg raises
    ``DataError``), this maps that driver error to a 422 instead of letting it
    reach the catch-all as a generic 500. The body is generic and never echoes
    the offending value or any SQL/driver text.

    Args:
        request: The incoming request, used only to log the method and path.
        exc: The raised SQLAlchemy data error. Unused beyond the contract.

    Returns:
        A JSON response with status 422 and a generic detail.
    """
    _LOGGER.warning(
        "Rejected database-invalid value on %s %s",
        request.method,
        request.url.path,
    )
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
        content={"detail": "Unprocessable value in request."},
    )


def _RegisterExceptionHandlers(fastapiApp: FastAPI) -> None:
    """Register domain-exception handlers that map errors to HTTP responses.

    Each general domain error is mapped to its status code via
    :data:`_DOMAIN_ERROR_STATUS`; the transaction-posting family is handled
    separately so its numeric reject code and description are preserved.
    Infrastructure handlers complete the surface: a ``pydantic.ValidationError``
    handler that restores the 422 contract for validation errors raised inside
    dependency callables (the inverted report date range); two malformed-input
    handlers (QA finding F4) that bound a ``RecursionError`` from an over-nested
    body to a 400 and a SQLAlchemy ``DataError`` from a database-rejected value
    to a 422; and a last-resort catch-all ``Exception`` handler that sanitizes
    every otherwise-unhandled fault into a generic 500 -- preventing
    traceback/SQL/PAN disclosure to the client. The catch-all is only reached
    when FastAPI is not running in debug mode, which is why ``create_application``
    no longer forwards ``settings.DEBUG`` to the FastAPI constructor.

    Args:
        fastapiApp: The application instance to register handlers on.
    """
    for exceptionType, statusCode in _DOMAIN_ERROR_STATUS.items():
        fastapiApp.add_exception_handler(exceptionType, _MakeDomainErrorHandler(statusCode))
    fastapiApp.add_exception_handler(TransactionPostingError, _HandlePostingReject)
    fastapiApp.add_exception_handler(RequestValidationError, _HandleRequestValidationError)
    fastapiApp.add_exception_handler(PydanticValidationError, _HandleValidationError)
    # QA finding F4: bound two malformed-input faults that would otherwise reach
    # the catch-all as a generic 500. Both are more specific than ``Exception``,
    # so Starlette resolves them first along the exception MRO.
    fastapiApp.add_exception_handler(RecursionError, _HandleRecursionError)
    fastapiApp.add_exception_handler(DataError, _HandleDataError)
    fastapiApp.add_exception_handler(Exception, _HandleUnexpectedError)


def _RegisterHealthRoute(fastapiApp: FastAPI) -> None:
    """Register the dependency-free readiness/liveness probe route.

    Args:
        fastapiApp: The application instance to register the route on.
    """

    @fastapiApp.get(HEALTH_ROUTE_PATH, tags=["health"])
    async def _HealthCheck() -> dict[str, str]:
        """Return a static readiness payload without touching the database."""
        return dict(HEALTH_RESPONSE)

    @fastapiApp.get(HEALTH_READY_ROUTE_PATH, tags=["health"])
    async def _ReadinessCheck() -> JSONResponse:
        """Return 200 when the database is reachable and migrated; 503 otherwise.

        Delegates the actual check to
        :func:`app.db.session.CheckDatabaseReady`, which opens a dedicated,
        pool-bypassing asyncpg connection bounded by the driver's own connect
        and command timeouts (``settings.HEALTH_READY_TIMEOUT_SECONDS``) and runs
        the schema-aware probe query. This endpoint therefore ALWAYS answers
        promptly, even against a frozen/grey-failed database whose TCP socket is
        open but never responds -- the failure mode that a wrapped POOLED probe
        could not bound, because a socket frozen inside ``pool_pre_ping`` is
        bridged through greenlet and is not cancellable by ``asyncio.wait_for``
        (QA finding #7). A reachable-but-unmigrated database is likewise reported
        NOT ready (QA finding #6).

        A single, specific :class:`~app.db.session.DatabaseNotReadyError` is
        caught (never a bare except): the DB layer has already collapsed every
        underlying driver fault -- connect/command timeout against a frozen
        server, refused/failed connection (``OSError``), or a missing ``users``
        table on an unmigrated schema (``asyncpg.PostgresError``) -- into this one
        sanitized signal. It maps to 503 ``unavailable`` and logs the reason, so
        an outage, a frozen database, or an unmigrated schema all surface as
        "not ready" promptly, rather than as a hang or a served 500.
        """
        try:
            await CheckDatabaseReady()
        except DatabaseNotReadyError as readinessError:
            _LOGGER.warning("Readiness probe failed; database unavailable: %s", readinessError)
            return JSONResponse(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                content=dict(HEALTH_READY_UNAVAILABLE),
            )
        return JSONResponse(status_code=status.HTTP_200_OK, content=dict(HEALTH_READY_OK))



# ---------------------------------------------------------------------------
# OpenAPI security + error documentation (QA Issue 7).
#
# FastAPI's default schema documents only the happy-path 2xx responses and the
# 422 request-validation shape; it advertises no authentication requirement and
# no error statuses. The post-processor below enriches the generated document
# so it matches the real runtime contract: it declares the two accepted
# authentication schemes, marks every non-public operation as requiring
# authentication, and documents each operation's true error statuses (401 on
# authenticated ops, 403 on admin ops, 404 on resource lookups, 409 on
# conflict-capable ops). The status sets are derived from the actual service
# behavior (the ``NotFoundError``/``ConflictError``/``OptimisticLockError``
# raise sites), so the documentation never advertises an error an operation
# cannot return.
# ---------------------------------------------------------------------------

SESSION_SECURITY_SCHEME_NAME = "SessionCookie"
BEARER_SECURITY_SCHEME_NAME = "BearerAuth"

# Dependency callable names used to classify an operation's authentication tier.
_ADMIN_DEPENDENCY_NAME = "require_admin"
_USER_DEPENDENCY_NAME = "get_current_user"

# The v1 mount prefix (for example ``/api/v1``); sourced from settings so the
# operation keys below never hardcode the prefix.
_V1_PREFIX = settings.API_V1_PREFIX

# (METHOD, PATH) operations that can return 404 -- every resource lookup that
# raises ``NotFoundError`` (11 operations, cross-checked against the services).
_LOOKUP_OPERATIONS: frozenset[tuple[str, str]] = frozenset(
    {
        ("GET", f"{_V1_PREFIX}/accounts/{{acctId}}"),
        ("PUT", f"{_V1_PREFIX}/accounts/{{acctId}}"),
        ("GET", f"{_V1_PREFIX}/billpay/{{acctId}}"),
        ("POST", f"{_V1_PREFIX}/billpay"),
        ("GET", f"{_V1_PREFIX}/cards/{{cardNum}}"),
        ("PUT", f"{_V1_PREFIX}/cards/{{cardNum}}"),
        ("GET", f"{_V1_PREFIX}/transactions/{{tranId}}"),
        ("POST", f"{_V1_PREFIX}/transactions"),
        ("GET", f"{_V1_PREFIX}/admin/users/{{userId}}"),
        ("PUT", f"{_V1_PREFIX}/admin/users/{{userId}}"),
        ("DELETE", f"{_V1_PREFIX}/admin/users/{{userId}}"),
    }
)

# (METHOD, PATH) operations that can return 409 -- every conflict-capable path
# (optimistic-lock mismatch, tran-id race exhaustion, duplicate user, or the
# F3 last-administrator invariant on admin demotion/deletion). POST /billpay is
# included because its pay-in-full posting assigns MAX(tran_id)+1 and, under a
# cross-account race, exhausting the bounded retry surfaces as a 409 (M04).
_CONFLICT_OPERATIONS: frozenset[tuple[str, str]] = frozenset(
    {
        ("PUT", f"{_V1_PREFIX}/accounts/{{acctId}}"),
        ("PUT", f"{_V1_PREFIX}/cards/{{cardNum}}"),
        ("POST", f"{_V1_PREFIX}/transactions"),
        ("POST", f"{_V1_PREFIX}/billpay"),
        ("POST", f"{_V1_PREFIX}/admin/users"),
        ("PUT", f"{_V1_PREFIX}/admin/users/{{userId}}"),
        ("DELETE", f"{_V1_PREFIX}/admin/users/{{userId}}"),
    }
)

# Shared response body shape for the domain error handlers ({"detail": "..."}).
_ERROR_DETAIL_SCHEMA: dict[str, object] = {
    "type": "object",
    "properties": {"detail": {"type": "string"}},
}

# A request satisfied by EITHER accepted scheme (session cookie or bearer JWT).
_SECURITY_REQUIREMENT: list[dict[str, list[str]]] = [
    {SESSION_SECURITY_SCHEME_NAME: []},
    {BEARER_SECURITY_SCHEME_NAME: []},
]


def _BuildSecuritySchemes() -> dict[str, dict[str, str]]:
    """Return the OpenAPI ``securitySchemes`` for both accepted auth modes.

    Returns:
        A mapping declaring the session-cookie ``apiKey`` scheme (whose cookie
        name is taken from ``settings.SESSION_COOKIE_NAME``) and the bearer-JWT
        ``http`` scheme, matching the two ``AUTH_MODE`` baselines.
    """
    return {
        SESSION_SECURITY_SCHEME_NAME: {
            "type": "apiKey",
            "in": "cookie",
            "name": settings.SESSION_COOKIE_NAME,
            "description": "Session cookie issued by POST /auth/login (session baseline).",
        },
        BEARER_SECURITY_SCHEME_NAME: {
            "type": "http",
            "scheme": "bearer",
            "bearerFormat": "JWT",
            "description": "Bearer JWT returned by POST /auth/login when AUTH_MODE=jwt.",
        },
    }


def _ErrorResponse(description: str) -> dict[str, object]:
    """Build a JSON error-response object for the shared ``detail`` shape.

    Args:
        description: Human-readable description of when the status occurs.

    Returns:
        An OpenAPI response object carrying the ``{"detail": "..."}`` schema.
    """
    return {
        "description": description,
        "content": {"application/json": {"schema": _ERROR_DETAIL_SCHEMA}},
    }


def _RouteAuthLevel(route: APIRoute) -> str:
    """Classify a route as ``PUBLIC``, ``AUTH``, or ``ADMIN`` by its guards.

    Args:
        route: The API route to classify.

    Returns:
        ``"ADMIN"`` when the route depends on ``require_admin``, ``"AUTH"`` when
        it depends on ``get_current_user``, otherwise ``"PUBLIC"``.
    """
    dependencyNames = {
        getattr(dependency.call, "__name__", "")
        for dependency in route.dependant.dependencies
    }
    if _ADMIN_DEPENDENCY_NAME in dependencyNames:
        return "ADMIN"
    if _USER_DEPENDENCY_NAME in dependencyNames:
        return "AUTH"
    return "PUBLIC"


def _DecorateOperation(operation: dict, httpMethod: str, routePath: str, authLevel: str) -> None:
    """Add the ``security`` requirement and error responses to one operation.

    Args:
        operation: The OpenAPI operation object to enrich in place.
        httpMethod: The uppercase HTTP method (for example ``"GET"``).
        routePath: The full route path including the v1 prefix.
        authLevel: The route's tier from :func:`_RouteAuthLevel`.
    """
    responses = operation.setdefault("responses", {})
    if authLevel in ("AUTH", "ADMIN"):
        operation["security"] = _SECURITY_REQUIREMENT
        responses.setdefault(
            "401", _ErrorResponse("Authentication required, or the session/token is invalid.")
        )
    if authLevel == "ADMIN":
        responses.setdefault(
            "403", _ErrorResponse("Administrator privileges are required (user_type='A').")
        )
    if (httpMethod, routePath) in _LOOKUP_OPERATIONS:
        responses.setdefault(
            "404", _ErrorResponse("The requested resource does not exist.")
        )
    if (httpMethod, routePath) in _CONFLICT_OPERATIONS:
        responses.setdefault(
            "409",
            _ErrorResponse("Conflict: concurrent modification or a duplicate resource."),
        )


def _ApplySecurityAndErrors(fastapiApp: FastAPI, schema: dict) -> None:
    """Enrich every documented operation with security + error responses.

    Args:
        fastapiApp: The application whose routes drive the classification.
        schema: The generated OpenAPI document, mutated in place.
    """
    paths = schema.get("paths", {})
    for route in fastapiApp.routes:
        if not isinstance(route, APIRoute):
            continue
        pathItem = paths.get(route.path)
        if pathItem is None:
            continue
        authLevel = _RouteAuthLevel(route)
        for httpMethod in route.methods:
            operation = pathItem.get(httpMethod.lower())
            if operation is None:
                continue
            _DecorateOperation(operation, httpMethod, route.path, authLevel)


def _BuildOpenApiSchema(fastapiApp: FastAPI) -> dict:
    """Generate and cache the enriched OpenAPI document (QA Issue 7).

    Builds the base document with :func:`fastapi.openapi.utils.get_openapi`, then
    injects the security schemes and per-operation security + error responses.
    The result is memoized on ``fastapiApp.openapi_schema`` so it is computed
    once.

    Args:
        fastapiApp: The application to document.

    Returns:
        The enriched OpenAPI schema dictionary.
    """
    if fastapiApp.openapi_schema:
        return fastapiApp.openapi_schema
    schema = get_openapi(
        title=fastapiApp.title,
        version=fastapiApp.version,
        description=fastapiApp.description,
        routes=fastapiApp.routes,
    )
    schema.setdefault("components", {})["securitySchemes"] = _BuildSecuritySchemes()
    _ApplySecurityAndErrors(fastapiApp, schema)
    fastapiApp.openapi_schema = schema
    return schema


def _RegisterOpenApi(fastapiApp: FastAPI) -> None:
    """Install the custom OpenAPI generator on the application (QA Issue 7).

    Args:
        fastapiApp: The application whose ``openapi`` callable is replaced.
    """

    def CustomOpenApi() -> dict:
        """Return the enriched, memoized OpenAPI document."""
        return _BuildOpenApiSchema(fastapiApp)

    fastapiApp.openapi = CustomOpenApi


def create_application() -> FastAPI:
    """Create and configure the CardDemo FastAPI application.

    Builds the FastAPI instance from environment-driven settings, attaches the
    async lifespan, configures CORS middleware, mounts the v1 API routers under
    ``settings.API_V1_PREFIX``, registers domain-exception handlers, and adds a
    health-check route. This is the documented composition-root factory (AAP
    section 0.4.3); its framework-conventional public name ``create_application``
    is kept intentionally (an explicit exception to the Ochs PascalCase rule for
    methods, per AAP section 0.8.3).

    The FastAPI ``debug`` flag is intentionally never enabled from
    ``settings.DEBUG``. Starlette's debug mode renders unhandled exceptions as
    an HTML/plain-text traceback page -- exposing file paths, SQL statements,
    and bound parameters (which can include a card number) to the client -- and
    additionally bypasses the registered catch-all exception handler. Keeping
    ``debug`` at its secure default (``False``) closes that disclosure exposure
    and lets :func:`_HandleUnexpectedError` sanitize every 500. ``settings.DEBUG``
    remains available for verbose application-level logging without ever
    weakening the HTTP error surface.

    Returns:
        A fully configured :class:`fastapi.FastAPI` instance ready to serve.
    """
    # Gate the interactive docs and raw OpenAPI schema by environment (QA
    # finding #10): a local profile serves them; staging/production disables all
    # three so ``/docs``, ``/redoc`` and ``/openapi.json`` return 404, reducing
    # API-surface disclosure. Passing ``None`` for a URL is FastAPI's supported
    # way to unmount that route. An explicit ``ENABLE_API_DOCS`` env value
    # overrides the derivation (see ``settings.ApiDocsEnabled``).
    docsEnabled = settings.ApiDocsEnabled
    fastapiApp = FastAPI(
        title=settings.PROJECT_NAME,
        lifespan=Lifespan,
        docs_url="/docs" if docsEnabled else None,
        redoc_url="/redoc" if docsEnabled else None,
        openapi_url="/openapi.json" if docsEnabled else None,
    )
    _ConfigureMiddleware(fastapiApp)
    _MountRouters(fastapiApp)
    _RegisterExceptionHandlers(fastapiApp)
    _RegisterHealthRoute(fastapiApp)
    _RegisterOpenApi(fastapiApp)
    return fastapiApp


# Module-level ASGI application. HARD CONTRACT: ``uvicorn app.main:app`` (used by
# the docker-compose backend service and backend/Dockerfile) resolves this exact
# attribute name. Do not rename or remove it. It is built once, at import time.
app = create_application()
