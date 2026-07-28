# Async SQLAlchemy engine + session factory. Replaces the legacy VSAM KSDS
# connection/DD-name layer (see app/catlg/LISTCAT.txt clusters
# ACCTDATA/CARDDATA/CARDXREF/CUSTDATA/DISCGRP/TRANSACT) with a single PostgreSQL
# 17 connection. The connection string comes ONLY from the environment via
# app.core.config.settings.DATABASE_URL (Ochs Rule #3, AAP §0.5.5) — never a JCL
# DD statement and never a hardcoded credential.
"""Async database engine and session factory for the CardDemo backend.

This module owns the SQLAlchemy 2.0 **async** database foundation: it builds the
process-wide :data:`engine` (an ``AsyncEngine`` backed by the ``asyncpg`` driver)
and the :data:`AsyncSessionLocal` factory (an ``async_sessionmaker``) that the
rest of the application uses to obtain :class:`~sqlalchemy.ext.asyncio.AsyncSession`
objects — one session per request (AAP §0.4.3, "Unit-of-work / transaction: one
async session per request").

It replaces the mainframe original's VSAM KSDS connection / DD-name access layer.
Where the legacy programs opened cataloged clusters such as
``AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS`` (cataloged in ``app/catlg/LISTCAT.txt``)
through JCL DD statements, the modernized backend opens a single PostgreSQL
connection whose URL is supplied entirely by the environment through
:data:`app.core.config.settings` (Ochs Rule #3 — no hardcoded connection strings
or secrets; AAP §0.5.5).

Public contract (imported by name from other modules — do not rename):
    * :data:`engine` — the ``AsyncEngine``. ``app.main`` disposes of it on
      shutdown via ``await engine.dispose()`` in its lifespan handler.
    * :data:`AsyncSessionLocal` — the ``async_sessionmaker``. ``app.core.dependencies.get_db``
      opens a unit-of-work with ``async with AsyncSessionLocal() as session:``.

Import safety (critical):
    Constructing the engine is lazy — :func:`~sqlalchemy.ext.asyncio.create_async_engine`
    only builds the engine object and a lazy connection pool; it does **not** open
    a socket. Importing this module therefore performs **no** I/O and needs **no**
    running database, so Alembic migrations, the ``batch`` CLI, and unit tests can
    import ``app.*`` freely. No connection is opened, and no schema is created, at
    module import time.

Design boundaries:
    * This module is async-only. It reads the async ``DATABASE_URL`` from
      ``settings`` and never the synchronous psycopg2 URL, which is owned by
      ``backend/alembic/env.py`` and ``batch/loaders/*``.
    * ``get_db`` is intentionally **not** defined here; it belongs to
      ``app.core.dependencies`` (which imports :data:`AsyncSessionLocal` from
      here). The dependency direction is one-way: dependencies -> session -> config.
    * The declarative ``Base`` is intentionally **not** imported; ``app.db.base``
      and ``app.db.session`` are independent modules.
    * Engine disposal is owned by ``app.main`` (lifespan shutdown); this module
      registers no process-exit shutdown hooks.
"""

import asyncio

import asyncpg
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.core.config import settings

# Process-wide async engine. Built from the environment-supplied async (asyncpg)
# URL; the value is resolved once, at import time, from ``settings`` so there is
# exactly one connection pool per process.
#
# ``pool_pre_ping=True`` issues a lightweight liveness check when a connection is
# checked out of the pool, transparently recycling connections dropped by the
# database or an intervening proxy — this avoids stale-connection errors after
# idle periods without any application-level retry logic.
#
# ``future=True`` selects the SQLAlchemy 2.0 execution style (always-on in 2.0;
# stated explicitly for clarity). ``echo=False`` keeps SQL statement logging off
# by default (there is no dedicated echo setting in ``config.py``).
#
# ``connect_args`` supplies asyncpg-level, environment-tunable timeouts (QA
# finding #7 — bounded behavior under a slow/frozen PostgreSQL "grey failure")
# for the NORMAL request path that borrows pooled connections:
#   * ``timeout``         bounds connection establishment, so opening a socket to
#                         an unreachable or frozen server cannot hang
#                         (settings.DB_CONNECT_TIMEOUT_SECONDS).
#   * ``command_timeout`` bounds every statement, so a query issued to a frozen
#                         server is aborted with a ``TimeoutError`` rather than
#                         blocking indefinitely (settings.DB_COMMAND_TIMEOUT_SECONDS).
# These protect ordinary request handlers from an indefinitely blocked worker.
#
# The ``/health/ready`` probe deliberately does NOT rely on the pool: a socket
# frozen inside ``pool_pre_ping``'s checkout is bridged through SQLAlchemy's
# greenlet<->asyncpg adapter, and a blocking read there is NOT cancellable by
# ``asyncio.wait_for`` (empirically it hung indefinitely under ``docker pause``).
# Readiness therefore uses its own dedicated, driver-bounded connection — see
# :func:`CheckDatabaseReady` below. The values are never hardcoded (Ochs Rule
# #3) and are validated as strictly positive at startup by ``app.core.config``.
#
# IMPORT-SAFE: this call does not open a socket — the pool connects lazily on
# first use, so importing this module never requires a running PostgreSQL.
engine = create_async_engine(
    settings.DATABASE_URL.get_secret_value(),
    pool_pre_ping=True,
    future=True,
    echo=False,
    connect_args={
        "timeout": settings.DB_CONNECT_TIMEOUT_SECONDS,
        "command_timeout": settings.DB_COMMAND_TIMEOUT_SECONDS,
    },
)

# Async session factory bound to :data:`engine`. Callers open a unit of work with
# ``async with AsyncSessionLocal() as session:`` (see ``app.core.dependencies.get_db``).
#
# ``expire_on_commit=False`` keeps ORM attributes loaded after ``commit()`` so a
# just-committed instance can be serialized into a response DTO without a second
# round-trip to the database (required by the folder spec). ``autoflush=False``
# gives the service/repository layer explicit, predictable control over when
# pending changes are flushed.
AsyncSessionLocal = async_sessionmaker(
    bind=engine,
    class_=AsyncSession,
    expire_on_commit=False,
    autoflush=False,
)

# ---------------------------------------------------------------------------
# Readiness probe (QA findings #6 and #7)
# ---------------------------------------------------------------------------
# The readiness probe deliberately BYPASSES the shared pool above and opens its
# own dedicated, short-lived asyncpg connection. This is a correctness
# requirement, not an optimization: under a frozen ("grey failure") database the
# pooled path runs ``pool_pre_ping`` through SQLAlchemy's greenlet<->asyncpg
# bridge, and a socket read that blocks there is NOT cancellable by
# ``asyncio.wait_for`` — a ``docker pause``d server froze ``/health/ready``
# indefinitely (verified: a wrapped pooled probe hung for >300s). A native
# asyncpg connection is bounded by the driver's OWN ``timeout`` (connect phase)
# and ``command_timeout`` (query phase), so it returns a prompt failure instead
# of hanging (verified: a paused server yields ``TimeoutError`` in ~the timeout).
#
# The probe query targets the core ``users`` table rather than a bare
# ``SELECT 1`` (QA finding #6): a reachable-but-unmigrated database MUST report
# NOT ready, and ``users`` exists under both the Alembic-migrated runtime schema
# and the ``create_all`` test schema, so the check is valid in every environment.
READINESS_PROBE_SQL = "SELECT 1 FROM users LIMIT 1"

# SQLAlchemy async dialect marker stripped to turn the configured URL
# (``postgresql+asyncpg://...``) into the libpq-style URI that
# :func:`asyncpg.connect` accepts (``postgresql://...``).
_ASYNCPG_DIALECT_SUFFIX = "+asyncpg"

# Upper bound, in seconds, for closing the short-lived readiness connection.
# A graceful close issues a terminate handshake; against a frozen server even
# that can block, so it is bounded and falls back to a forceful, local-only
# ``terminate()``. One second is ample for a healthy close and negligible when
# the server is already gone.
CONNECTION_CLOSE_TIMEOUT_SECONDS = 1.0


class DatabaseNotReadyError(RuntimeError):
    """Raised when the readiness probe cannot confirm a ready database.

    Carries only a short, sanitized reason (the driver exception's type name),
    never the connection string, credentials, or query values, so the caller can
    log it and return a 503 without leaking configuration (Ochs Rule #3).
    """


def _BuildReadinessDsn() -> str:
    """Return a native asyncpg DSN derived from the configured async URL.

    Strips the ``+asyncpg`` SQLAlchemy dialect marker so the value is the plain
    ``postgresql://`` URI that :func:`asyncpg.connect` parses. The URL is read
    from the environment via ``settings`` and never hardcoded (Ochs Rule #3).
    """
    asyncUrl = settings.DATABASE_URL.get_secret_value()
    return asyncUrl.replace(_ASYNCPG_DIALECT_SUFFIX, "", 1)


async def _CloseReadinessConnection(connection: asyncpg.Connection) -> None:
    """Close the short-lived readiness connection without ever hanging.

    A graceful close issues I/O and can itself block against a frozen server, so
    it is bounded by ``CONNECTION_CLOSE_TIMEOUT_SECONDS`` and falls back to a
    non-blocking ``terminate()`` (which drops the socket locally).

    Args:
        connection: The asyncpg connection opened by :func:`CheckDatabaseReady`.
    """
    try:
        await asyncio.wait_for(
            connection.close(),
            timeout=CONNECTION_CLOSE_TIMEOUT_SECONDS,
        )
    except (OSError, TimeoutError, asyncpg.PostgresError):
        connection.terminate()


async def CheckDatabaseReady() -> None:
    """Assert the database is reachable AND migrated, within a bounded time.

    Opens a dedicated short-lived asyncpg connection that BYPASSES the shared
    pool (see the module note above) with ``settings.HEALTH_READY_TIMEOUT_SECONDS``
    applied as BOTH the connect and command timeout, then runs the schema-aware
    probe query. Returns ``None`` when the database is ready.

    Raises:
        DatabaseNotReadyError: If the server is unreachable or frozen (the
            connect/command timeout elapses), the connection is refused, or the
            schema is missing (the ``users`` table does not exist). The original
            driver error is chained (``from``) but only its type name is carried
            in the message, so no connection string or credential is exposed.
    """
    readyTimeout = settings.HEALTH_READY_TIMEOUT_SECONDS
    try:
        connection = await asyncpg.connect(
            dsn=_BuildReadinessDsn(),
            timeout=readyTimeout,
            command_timeout=readyTimeout,
        )
    except (OSError, TimeoutError, asyncpg.PostgresError) as connectError:
        raise DatabaseNotReadyError(type(connectError).__name__) from connectError
    try:
        await connection.fetchval(READINESS_PROBE_SQL)
    except (OSError, TimeoutError, asyncpg.PostgresError) as queryError:
        raise DatabaseNotReadyError(type(queryError).__name__) from queryError
    finally:
        await _CloseReadinessConnection(connection)


# Public API of this module: the engine/session factory plus the readiness
# probe that ``app.main`` calls from its ``/health/ready`` handler.
__all__ = [
    "READINESS_PROBE_SQL",
    "AsyncSessionLocal",
    "CheckDatabaseReady",
    "DatabaseNotReadyError",
    "engine",
]
