# CardDemo -- Alembic migration environment (ASYNC template).
#
# This is the standard ``alembic init -t async`` environment, customized for the
# CardDemo backend (AAP 0.4.2 "async template"). Alembic imports and executes
# this module on every ``alembic upgrade`` / ``downgrade`` / ``revision`` command
# to wire the application's SQLAlchemy metadata and database URL into Alembic.
#
# Three design points are load-bearing:
#   1. NO HARDCODED SECRET/DSN (Ochs Rule #3): ``alembic.ini``'s ``sqlalchemy.url``
#      is intentionally EMPTY. The connection URL is injected at runtime from
#      ``app.core.config.settings.DATABASE_URL`` (a pydantic SecretStr, read via
#      ``.get_secret_value()``), so no credential ever lives in a tracked file.
#   2. ``import app.models`` is MANDATORY: importing the models package registers
#      all 11 ORM tables on ``Base.metadata`` as a side effect, so
#      ``target_metadata = Base.metadata`` sees every table for autogenerate.
#   3. NAMING EXCEPTION (Ochs Rule 0.8.2 / 0.8.3): the module-level hook functions
#      ``run_migrations_offline``, ``run_migrations_online``, and their shared
#      helper ``do_run_migrations`` are kept snake_case as an intentional,
#      documented exception to the Ochs PascalCase-methods rule. These are the
#      Alembic framework contract -- ``alembic`` invokes ``run_migrations_offline``
#      / ``run_migrations_online`` by name as the env.py entry points, and the
#      async template calls ``do_run_migrations`` via ``connection.run_sync(...)``
#      -- so their names are framework-mandated, not free choices (AAP 0.8.3).
#
# Execution driver: the async (asyncpg) engine, per the AAP mandate. Online
# migrations build an AsyncEngine and run the synchronous migration routine
# through ``connection.run_sync(...)`` inside ``asyncio.run(...)``.
"""Alembic environment for the CardDemo backend (async engine, env-driven URL)."""

import asyncio
from logging.config import fileConfig

from sqlalchemy import pool, text
from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import AsyncConnection, async_engine_from_config

from alembic import context

from app.core.config import settings
from app.db.base import Base
import app.models  # noqa: F401 - side effect: registers all 11 tables on Base.metadata

# Alembic Config object -- provides access to the values within ``alembic.ini``
# (the ``[alembic]`` section plus the logging configuration sections).
config = context.config

# Inject the database URL at runtime from the environment-driven settings,
# overriding the intentionally EMPTY ``sqlalchemy.url`` in ``alembic.ini``. This
# is the mechanism that keeps every connection string / secret out of tracked
# files (Ochs Rule #3). ``DATABASE_URL`` is a pydantic ``SecretStr``, so the raw
# value is read with ``.get_secret_value()``; it carries the async asyncpg driver
# used by the online migration engine below.
config.set_main_option("sqlalchemy.url", settings.DATABASE_URL.get_secret_value())

# Interpret the logging configuration from the ini file, but only when Alembic
# was invoked with a real config file present (guarded so programmatic use
# without a file does not raise).
if config.config_file_name is not None:
    fileConfig(config.config_file_name)

# Target metadata for 'autogenerate' support. ``Base.metadata`` is populated with
# all 11 CardDemo tables thanks to the mandatory ``import app.models`` above, and
# it carries the deterministic constraint-naming convention defined in
# ``app.db.base``, so generated migrations use stable pk_/fk_/ix_/uq_/ck_ names.
target_metadata = Base.metadata

# QA finding #11 -- concurrent-migration serialization.
#
# Two ``alembic upgrade head`` processes racing against the SAME fresh database
# previously let the loser run its DDL concurrently and surface a raw
# asyncpg ``UniqueViolationError`` (exit 1) once the winner had already created a
# table. To make a concurrent invocation a graceful no-op instead, online
# migrations take a PostgreSQL SESSION-LEVEL advisory lock on this fixed,
# application-wide key BEFORE running any migration and release it afterwards.
# A second process BLOCKS on the lock until the first COMMITS, then proceeds,
# reads the already-current schema version, finds no pending revisions, and exits
# 0. The key is an arbitrary but STABLE signed 63-bit integer; the ``carddemo``
# database is single-tenant, so there is no risk of colliding with an unrelated
# advisory lock. Offline mode (SQL script emission, no live connection) never
# takes the lock -- there is no concurrent DDL to serialize there.
MIGRATION_ADVISORY_LOCK_KEY = 6_442_509_913_017_442_311


async def _AcquireMigrationLock(connection: AsyncConnection) -> None:
    """Take the session-level advisory lock that serializes online migrations.

    Blocks until the lock is granted (a concurrent migration holds it until that
    process commits). The lock is acquired in its own committed statement so it
    is held at the SESSION level -- independent of, and surviving, the migration
    transaction that Alembic opens next via ``context.begin_transaction()``.
    Session-level advisory locks are NOT released by ``COMMIT`` (only by an
    explicit unlock or session end), so committing here does not drop it.

    Args:
        connection: The live async connection driving the migration; the lock
            and the migrations MUST share this one connection to serialize.
    """
    await connection.execute(
        text("SELECT pg_advisory_lock(:lockKey)"),
        {"lockKey": MIGRATION_ADVISORY_LOCK_KEY},
    )
    await connection.commit()


async def _ReleaseMigrationLock(connection: AsyncConnection) -> None:
    """Release the session-level advisory lock taken by :func:`_AcquireMigrationLock`.

    Explicit release lets a waiting migration proceed immediately rather than
    waiting for connection teardown. The lock would also auto-release when the
    session ends (engine dispose), so this is best-effort, deterministic cleanup.

    Args:
        connection: The same connection the lock was acquired on.
    """
    await connection.execute(
        text("SELECT pg_advisory_unlock(:lockKey)"),
        {"lockKey": MIGRATION_ADVISORY_LOCK_KEY},
    )
    await connection.commit()


def run_migrations_offline() -> None:
    """Run migrations in 'offline' mode (emit SQL to a script, no DBAPI).

    Configures the context with only a URL (no live connection) so Alembic
    renders migration statements as SQL. ``literal_binds=True`` inlines bound
    parameters, and ``compare_type=True`` sharpens autogenerate fidelity for the
    exact NUMERIC(p,s) / CHAR(n) / VARCHAR(n) / TIMESTAMPTZ column types this
    schema relies on.
    """
    url = config.get_main_option("sqlalchemy.url")
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        compare_type=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def do_run_migrations(connection: Connection) -> None:
    """Configure the migration context on a live connection and run migrations.

    Shared by the async online path: :func:`run_migrations_online` acquires an
    async connection and hands its synchronous facade to this function via
    ``connection.run_sync(...)``, so the standard synchronous Alembic migration
    API can be used unchanged.

    Args:
        connection: A synchronous-facing SQLAlchemy ``Connection`` supplied by
            ``AsyncConnection.run_sync``.
    """
    context.configure(
        connection=connection,
        target_metadata=target_metadata,
        compare_type=True,
    )
    with context.begin_transaction():
        context.run_migrations()


async def run_migrations_online() -> None:
    """Run migrations in 'online' mode using an async (asyncpg) engine.

    Builds an ``AsyncEngine`` from the ini section -- which now carries the
    runtime-injected ``sqlalchemy.url`` (the asyncpg DSN from settings) -- using
    ``NullPool`` (no connection reuse is wanted for a short-lived migration
    process), opens a connection, and drives the synchronous migration routine
    through ``run_sync``. The engine is always disposed before returning.

    Concurrency (QA finding #11): a session-level advisory lock is taken on the
    connection BEFORE the migrations run and released in a ``finally`` after, so
    two racing ``alembic upgrade`` processes serialize -- the loser blocks until
    the winner commits, then finds no pending revisions and exits cleanly instead
    of racing the DDL. See :data:`MIGRATION_ADVISORY_LOCK_KEY`.
    """
    connectable = async_engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    async with connectable.connect() as connection:
        await _AcquireMigrationLock(connection)
        try:
            await connection.run_sync(do_run_migrations)
        finally:
            await _ReleaseMigrationLock(connection)
    await connectable.dispose()


# Entry dispatch: Alembic runs this module top-to-bottom. Offline mode emits SQL
# without a DBAPI connection; online mode drives the async engine coroutine.
if context.is_offline_mode():
    run_migrations_offline()
else:
    asyncio.run(run_migrations_online())
