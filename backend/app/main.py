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
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy.exc import SQLAlchemyError

from app.core.config import settings
from app.core.exceptions import (
    AuthenticationError,
    AuthorizationError,
    CardDemoError,
    ConflictError,
    DomainValidationError,
    NotFoundError,
    TransactionPostingError,
)
from app.db.session import engine

# Module-level logger for infrastructure events only (for example, a failure
# while disposing the database engine at shutdown). Business events are logged
# by the service layer, not here.
_LOGGER = logging.getLogger(__name__)

# Root-level readiness/liveness probe path and its static response body. The
# health route is intentionally cheap and dependency-free -- it never touches
# the database -- so orchestrators can poll it without loading the pool.
HEALTH_ROUTE_PATH = "/health"
HEALTH_RESPONSE: dict[str, str] = {"status": "ok"}

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

    Startup is intentionally minimal: the async engine and session factory are
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
    # Startup: nothing heavy to do (no migrations, no eager connect).
    yield
    # Shutdown: release the async connection pool. A specific SQLAlchemy error
    # is caught and logged (never a bare except) so a teardown hiccup cannot
    # mask the real shutdown reason.
    try:
        await engine.dispose()
    except SQLAlchemyError as disposeError:
        _LOGGER.warning("Failed to dispose the database engine on shutdown: %s", disposeError)


def _ConfigureMiddleware(fastapiApp: FastAPI) -> None:
    """Register cross-cutting middleware on the application.

    Adds Starlette's CORS middleware so the Next.js frontend -- whose origin is
    listed in ``settings.BACKEND_CORS_ORIGINS`` (for example
    ``http://localhost:3000``) -- may call the API with credentials.
    ``allow_credentials`` is enabled because the session-cookie baseline
    requires the browser to send the auth cookie cross-origin. No other
    middleware is registered here: session/JWT decoding is performed by the
    ``get_current_user`` dependency, not by an application-level middleware.

    Args:
        fastapiApp: The application instance to configure.
    """
    fastapiApp.add_middleware(
        CORSMiddleware,
        allow_origins=settings.BACKEND_CORS_ORIGINS,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )


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


def _RegisterExceptionHandlers(fastapiApp: FastAPI) -> None:
    """Register domain-exception handlers that map errors to HTTP responses.

    Each general domain error is mapped to its status code via
    :data:`_DOMAIN_ERROR_STATUS`; the transaction-posting family is handled
    separately so its numeric reject code and description are preserved.

    Args:
        fastapiApp: The application instance to register handlers on.
    """
    for exceptionType, statusCode in _DOMAIN_ERROR_STATUS.items():
        fastapiApp.add_exception_handler(exceptionType, _MakeDomainErrorHandler(statusCode))
    fastapiApp.add_exception_handler(TransactionPostingError, _HandlePostingReject)


def _RegisterHealthRoute(fastapiApp: FastAPI) -> None:
    """Register the dependency-free readiness/liveness probe route.

    Args:
        fastapiApp: The application instance to register the route on.
    """

    @fastapiApp.get(HEALTH_ROUTE_PATH, tags=["health"])
    async def _HealthCheck() -> dict[str, str]:
        """Return a static readiness payload without touching the database."""
        return dict(HEALTH_RESPONSE)


def create_application() -> FastAPI:
    """Create and configure the CardDemo FastAPI application.

    Builds the FastAPI instance from environment-driven settings, attaches the
    async lifespan, configures CORS middleware, mounts the v1 API routers under
    ``settings.API_V1_PREFIX``, registers domain-exception handlers, and adds a
    health-check route. This is the documented composition-root factory (AAP
    section 0.4.3); its framework-conventional public name ``create_application``
    is kept intentionally (an explicit exception to the Ochs PascalCase rule for
    methods, per AAP section 0.8.3).

    Returns:
        A fully configured :class:`fastapi.FastAPI` instance ready to serve.
    """
    fastapiApp = FastAPI(
        title=settings.PROJECT_NAME,
        debug=settings.DEBUG,
        lifespan=Lifespan,
    )
    _ConfigureMiddleware(fastapiApp)
    _MountRouters(fastapiApp)
    _RegisterExceptionHandlers(fastapiApp)
    _RegisterHealthRoute(fastapiApp)
    return fastapiApp


# Module-level ASGI application. HARD CONTRACT: ``uvicorn app.main:app`` (used by
# the docker-compose backend service and backend/Dockerfile) resolves this exact
# attribute name. Do not rename or remove it. It is built once, at import time.
app = create_application()
