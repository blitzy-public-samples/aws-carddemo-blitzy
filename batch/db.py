# Batch DB access layer. Replaces legacy VSAM DD allocation / CICS file control
# (JCL `ACCTDAT`, `CARDDAT`, `XREF`, `TRANSACT`, `TCATBALF` DD statements) with a
# synchronous PostgreSQL connection. Sync (psycopg2) is used because the batch
# chain is a synchronous CLI process, distinct from the backend's async request
# path.
#
# Schema ownership: this module deliberately does NOT import `app.models.*` and
# does NOT issue any DDL (no `CREATE TABLE`, no `Base.metadata.create_all`). The
# database schema is owned exclusively by Alembic (`backend/alembic/versions/*`);
# batch code only reads and writes rows through the ORM `Session` created here.
"""Synchronous SQLAlchemy session factory for the CardDemo batch package.

This module is the single database-access seam for the greenfield ``batch/``
tree. Every batch job (``batch/jobs/*.py``) and every seed loader
(``batch/loaders/*.py``), along with the orchestration chain
(``batch/orchestration/batch_chain.py``) and the CLI (``batch/cli.py``), obtains
its :class:`~sqlalchemy.orm.Session` from here rather than constructing engines
of its own.

It is the **synchronous counterpart** to the backend's async
``app/db/session.py`` (which owns the ``asyncpg`` engine and
``AsyncSessionLocal``). The split is intentional: the batch chain runs as a
plain synchronous CLI process, so it uses the blocking ``psycopg2`` driver and a
classic :class:`~sqlalchemy.orm.Session`, while the FastAPI request path stays
fully async. The ``SYNC_DATABASE_URL`` environment variable is reserved for
exactly this purpose (Alembic migrations and the batch loaders), and
``backend/.env.example`` documents it as the psycopg2 URL for batch.

Configuration is environment-driven (Ochs Rule #3 — no hardcoded secrets): the
connection string is read from
:data:`batch.config.batchSettings.SYNC_DATABASE_URL` and never hardcoded here.
The batch package owns its own settings model (:mod:`batch.config`) rather than
importing the backend :data:`app.core.config.settings` singleton, so a batch
command launched from the repository root as ``python -m batch.cli`` needs ONLY
``SYNC_DATABASE_URL`` — it does **not** require the backend-only ``SECRET_KEY``
(QA finding #60). Engine construction is lazy with respect to the network —
importing this module builds the engine and session factory but does **not**
open a database connection, so lightweight contexts (``python -m py_compile``,
unit tests, ``--help`` on the CLI) can import ``batch.db`` without a live
PostgreSQL server.

Transaction-ownership contract:
    Callers own the transaction boundary via ``with GetSyncSession() as
    session:``. Job and loader entry functions receive an already-open
    ``session``, perform their work, and must **not** call ``commit()`` or
    ``rollback()`` themselves. :func:`GetSyncSession` commits once on success and
    rolls back on error, giving each chain step / CLI subcommand its own
    transaction — mirroring the legacy per-JCL-step commit semantics and keeping
    jobs idempotent and re-runnable.

Public API:
    * :data:`ENGINE` — the process-wide synchronous :class:`~sqlalchemy.engine.Engine`.
    * :data:`SessionLocal` — the configured :class:`~sqlalchemy.orm.sessionmaker` factory.
    * :func:`GetSyncSession` — the transactional unit-of-work context manager.

Example:
    Compose several loaders into one atomic unit of work::

        from batch.db import GetSyncSession
        from batch.loaders.load_accounts import LoadAccounts

        with GetSyncSession() as session:
            LoadAccounts(session)          # loader does NOT commit
        # transaction is committed here, exactly once, on clean exit
"""

import os
from collections.abc import Iterator
from contextlib import contextmanager

from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from batch.config import batchSettings

__all__ = ["ENGINE", "SessionLocal", "GetSyncSession", "FormatConciseError"]


# SQLAlchemy appends the offending statement and every bound parameter to its
# exception text (``\n[SQL: ...]\n[parameters: {...}]``). That dump is stripped
# from batch error output so failures stay concise and never echo bound values
# such as card numbers (QA Finding D; AAP 0.7.8 masking intent).
SQL_DETAIL_MARKER: str = "\n[SQL:"


def FormatConciseError(batchError: BaseException) -> str:
    """Return a concise, single-diagnostic string for a batch runtime error.

    SQLAlchemy's ``str(error)`` appends the full offending statement and every
    bound parameter. That dump is stripped here so a logged or user-facing
    failure retains only the core driver diagnostic (a connection failure, or a
    constraint violation and its ``DETAIL`` line, and so on) -- keeping error
    output specific, atomic, and free of bound values such as card numbers.
    Errors without the marker (for example a plain ``ValueError``) are returned
    unchanged. This is the single error-formatting seam shared by the CLI
    entrypoint (``batch.cli``) and the orchestration chain
    (``batch.orchestration.batch_chain``).

    Args:
        batchError: The batch runtime exception to format.

    Returns:
        The error text up to (but excluding) the ``[SQL: ...]`` dump, with
        surrounding whitespace stripped.
    """
    message = str(batchError)
    markerIndex = message.find(SQL_DETAIL_MARKER)
    if markerIndex != -1:
        message = message[:markerIndex]
    return message.strip()


# Module-level synchronous engine, created exactly once at import time.
#
# * ``batchSettings.SYNC_DATABASE_URL`` is the psycopg2 URL (e.g.
#   ``postgresql+psycopg2://user:pass@host:5432/carddemo``). We intentionally use
#   the SYNC url, never an asyncpg ``DATABASE_URL`` (that async URL is owned by
#   the backend's async engine). It is a pydantic ``SecretStr`` (so the embedded
#   credentials never render in cleartext), so the raw URL is read with
#   ``.get_secret_value()`` before it reaches ``create_engine`` — consistent with
#   ``backend/app/db/session.py`` and ``backend/alembic/env.py``.
# * ``pool_pre_ping=True`` issues a lightweight liveness check before handing out
#   a pooled connection, guarding against stale/dropped connections across the
#   long-running batch chain.
# * ``future=True`` selects SQLAlchemy 2.0 style semantics.
# * ``echo`` is intentionally NOT hardcoded: ``batchSettings`` exposes no SQL-echo
#   flag, so echo is omitted (Ochs Rule #3 — no hardcoded configuration).
# * ``create_engine`` is lazy: it validates the URL and prepares the connection
#   pool but does not open a socket, so importing this module never requires a
#   live database.
ENGINE = create_engine(
    batchSettings.SYNC_DATABASE_URL.get_secret_value(),
    pool_pre_ping=True,
    future=True,
)


# Session factory for the batch package. The ``SessionLocal`` name mirrors the
# backend's ``AsyncSessionLocal``; because it is a class-like factory rather than
# an ordinary variable, PascalCase-style naming is appropriate (Ochs).
#
# * ``autoflush=False`` — batch code flushes explicitly / on commit, so implicit
#   mid-unit-of-work flushes never surprise a job.
# * ``autocommit=False`` — the caller controls the transaction boundary (see
#   :func:`GetSyncSession`); no per-statement autocommit.
# * ``expire_on_commit=False`` — ORM instances remain usable (attributes stay
#   loaded) after commit, which job result summaries and golden-master parity
#   assertions rely on.
# * ``future=True`` / ``class_=Session`` — SQLAlchemy 2.0 style classic Session.
SessionLocal = sessionmaker(
    bind=ENGINE,
    autoflush=False,
    autocommit=False,
    expire_on_commit=False,
    future=True,
    class_=Session,
)


# --------------------------------------------------------------------------- #
# Optional test-database safety guard (QA Finding C).
# --------------------------------------------------------------------------- #
# The batch loaders and jobs issue mutating SQL (idempotent upserts and account
# balance updates). They are datastore-agnostic -- they act on whatever database
# ``settings.SYNC_DATABASE_URL`` points at -- so an operator who mis-points that
# URL could mutate an unintended database. This guard lets a caller OPT IN to a
# defensive check that refuses to run unless the target database name is a
# dedicated test database. It is env-driven (Ochs Rule #3 -- no hardcoded
# configuration) and DISABLED by default, so production and development runs are
# completely unaffected (no behavior change); only when explicitly enabled does
# it change behavior.
# Canonical, documented environment variable that opts the guard IN. Named with
# the ``CARDDEMO_`` project prefix so the SAME variable reads naturally across the
# backend, batch and docs (QA finding I6): the operator sets exactly ONE
# documented name and it is honored. ``docs/batch.md`` and ``README.md`` document
# this name; enabling it against a non-test database now protects that data
# instead of being silently ignored.
REQUIRE_TEST_DB_ENV_VAR = "CARDDEMO_REQUIRE_TEST_DB"

# Backward-compatible legacy alias for :data:`REQUIRE_TEST_DB_ENV_VAR`. Earlier
# builds (and any CI/harness that already exports it) used this name, so it is
# still honored -- enabling the guard through it never silently stops protecting
# data. The canonical ``CARDDEMO_REQUIRE_TEST_DB`` is the documented form; this
# alias is retained purely so existing callers keep working (QA finding I6).
LEGACY_REQUIRE_TEST_DB_ENV_VAR = "BATCH_REQUIRE_TEST_DB"

# Both names are consulted; either one, when truthy, enables the guard. The
# canonical name is listed first so it is the one surfaced in diagnostics.
_REQUIRE_TEST_DB_ENV_VARS = (REQUIRE_TEST_DB_ENV_VAR, LEGACY_REQUIRE_TEST_DB_ENV_VAR)

# String values (case-insensitive, surrounding whitespace ignored) that turn the
# guard ON. Any other value -- including unset -- leaves the guard OFF.
GUARD_TRUTHY_VALUES = frozenset({"1", "true", "yes", "on"})

# Suffix that marks a database name as a dedicated (non-production) test database.
TEST_DB_NAME_SUFFIX = "_test"


def _IsTestDbGuardEnabled() -> bool:
    """Return whether the test-database guard has been opted in via the environment.

    Reads the canonical :data:`REQUIRE_TEST_DB_ENV_VAR` and the legacy
    :data:`LEGACY_REQUIRE_TEST_DB_ENV_VAR` alias; the guard is enabled when
    EITHER one holds a value in :data:`GUARD_TRUTHY_VALUES`. Absent or any other
    value on both leaves the guard disabled, preserving the default
    (production-safe) behavior (QA finding I6).

    Returns:
        ``True`` when the guard is enabled through either name, otherwise ``False``.
    """
    for envVarName in _REQUIRE_TEST_DB_ENV_VARS:
        rawValue = os.environ.get(envVarName, "")
        if rawValue.strip().lower() in GUARD_TRUTHY_VALUES:
            return True
    return False


def _AssertTestDatabase() -> None:
    """Refuse to proceed against a non-test database when the guard is enabled.

    When :data:`REQUIRE_TEST_DB_ENV_VAR` is truthy, the configured database name
    (read from the engine URL) must end with :data:`TEST_DB_NAME_SUFFIX`; if it
    does not, a specific :class:`ValueError` is raised BEFORE any session is
    opened -- so no mutating statement can reach a database that was not
    explicitly designated for testing. When the guard is disabled this is a
    no-op. A specific exception is raised, never a catch-all (Ochs Rule #5).

    Raises:
        ValueError: If the guard is enabled and the target database name does not
            end with :data:`TEST_DB_NAME_SUFFIX`.
    """
    if not _IsTestDbGuardEnabled():
        return
    databaseName = ENGINE.url.database or ""
    if not databaseName.endswith(TEST_DB_NAME_SUFFIX):
        raise ValueError(
            f"{REQUIRE_TEST_DB_ENV_VAR} is enabled but the target database "
            f"'{databaseName}' is not a test database (its name must end with "
            f"'{TEST_DB_NAME_SUFFIX}'). Refusing to run to protect non-test data."
        )


@contextmanager
def GetSyncSession() -> Iterator[Session]:
    """Yield a transactional synchronous ``Session`` as a unit of work.

    Opens a new :class:`~sqlalchemy.orm.Session` from :data:`SessionLocal`,
    yields it to the caller, and manages the transaction boundary on their
    behalf: on clean exit the session is committed exactly once; on any error the
    session is rolled back and the original exception is re-raised; and the
    session is always closed. This is the single transaction primitive shared by
    the batch jobs, loaders, orchestration chain, and CLI.

    Callers must treat the yielded ``session`` as read/write scratch within one
    transaction and must **not** commit or roll back themselves — doing so would
    break the per-step transaction semantics this helper guarantees.

    Yields:
        Session: An open, transaction-scoped SQLAlchemy ORM session.

    Raises:
        ValueError: If the optional test-database guard is enabled
            (:data:`REQUIRE_TEST_DB_ENV_VAR`) and the target database is not a
            test database. This is checked BEFORE the session opens, so no
            statement runs (see :func:`_AssertTestDatabase`).
        Exception: Re-raises, unchanged, whatever the caller's block raised.
            No exception is ever caught here (Ochs "specific exceptions only",
            QA M-31): the rollback is driven by a ``finally`` block guarded by a
            ``committed`` flag, so a failed unit of work is rolled back while the
            original, specific exception type (and traceback) propagates to the
            caller intact. It is a rollback guard, not a catch-all handler.

    Example:
        >>> from sqlalchemy import text
        >>> with GetSyncSession() as session:
        ...     session.execute(text("SELECT 1")).scalar()
        1
    """
    # Optional opt-in safety check (no-op unless BATCH_REQUIRE_TEST_DB is set):
    # refuse to open a unit of work against a non-test database, before any
    # session or statement exists (QA Finding C).
    _AssertTestDatabase()
    session = SessionLocal()
    # `committed` gates the rollback so NO exception is caught here (Ochs
    # "specific exceptions only", QA M-31). If the caller's block or the commit
    # raises, `committed` stays False and the `finally` rolls back the partial
    # unit of work; the original, specific exception propagates untouched because
    # it is never intercepted. On clean completion `committed` is True and the
    # rollback is skipped. The session is always closed.
    committed = False
    try:
        yield session
        session.commit()
        committed = True
    finally:
        if not committed:
            session.rollback()
        session.close()
