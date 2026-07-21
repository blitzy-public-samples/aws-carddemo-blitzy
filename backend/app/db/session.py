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
# IMPORT-SAFE: this call does not open a socket — the pool connects lazily on
# first use, so importing this module never requires a running PostgreSQL.
engine = create_async_engine(
    settings.DATABASE_URL,
    pool_pre_ping=True,
    future=True,
    echo=False,
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

# Public API of this module: exactly the two names other layers import.
__all__ = ["engine", "AsyncSessionLocal"]
