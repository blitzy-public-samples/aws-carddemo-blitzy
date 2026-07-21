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
fully async. The backend reserves ``settings.SYNC_DATABASE_URL`` for exactly this
purpose (Alembic migrations and the batch loaders), and ``backend/.env.example``
documents it as the psycopg2 URL for batch.

Configuration is environment-driven (Ochs Rule #3 — no hardcoded secrets): the
connection string is read from :data:`app.core.config.settings.SYNC_DATABASE_URL`
and never hardcoded here. Engine construction is lazy with respect to the
network — importing this module builds the engine and session factory but does
**not** open a database connection, so lightweight contexts (``python -m
py_compile``, unit tests, ``--help`` on the CLI) can import ``batch.db`` without
a live PostgreSQL server. The only import-time requirement is that the
environment variables ``settings`` needs (notably ``SECRET_KEY``, which has no
default) are present.

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

from collections.abc import Iterator
from contextlib import contextmanager

from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.core.config import settings

__all__ = ["ENGINE", "SessionLocal", "GetSyncSession"]


# Module-level synchronous engine, created exactly once at import time.
#
# * ``settings.SYNC_DATABASE_URL`` is the psycopg2 URL (e.g.
#   ``postgresql+psycopg2://user:pass@host:5432/carddemo``). We intentionally use
#   the SYNC url, never ``settings.DATABASE_URL`` (that asyncpg URL is owned by
#   the backend's async engine). It is a pydantic ``SecretStr`` (so the embedded
#   credentials never render in cleartext), so the raw URL is read with
#   ``.get_secret_value()`` before it reaches ``create_engine`` — consistent with
#   ``backend/app/db/session.py`` and ``backend/alembic/env.py``.
# * ``pool_pre_ping=True`` issues a lightweight liveness check before handing out
#   a pooled connection, guarding against stale/dropped connections across the
#   long-running batch chain.
# * ``future=True`` selects SQLAlchemy 2.0 style semantics.
# * ``echo`` is intentionally NOT hardcoded: ``settings`` exposes no SQL-echo
#   flag, so echo is omitted (Ochs Rule #3 — no hardcoded configuration).
# * ``create_engine`` is lazy: it validates the URL and prepares the connection
#   pool but does not open a socket, so importing this module never requires a
#   live database.
ENGINE = create_engine(
    settings.SYNC_DATABASE_URL.get_secret_value(),
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
        Exception: Re-raises, unchanged, whatever the caller's block raised.
            The broad ``except Exception`` below is the one justified broad guard
            in this codebase: it does not swallow the error — it performs a
            unit-of-work rollback and then immediately ``raise``s, so the
            original, specific exception type (and traceback) propagates to the
            caller intact. It is a rollback guard, not a catch-all handler.

    Example:
        >>> from sqlalchemy import text
        >>> with GetSyncSession() as session:
        ...     session.execute(text("SELECT 1")).scalar()
        1
    """
    session = SessionLocal()
    try:
        yield session
        session.commit()
    except Exception:
        # Unit-of-work rollback guard: undo any partial writes from the failed
        # block, then re-raise so the caller sees the original specific error.
        session.rollback()
        raise
    finally:
        session.close()
