# Root pytest fixtures + path/env bootstrap for the CardDemo batch test suite.
#
# Provenance: this is infrastructure for the greenfield ``batch/tests/`` tree
# (AAP 0.5.2 ``batch/tests/**``). The fixtures below drive the Python
# reimplementation of the legacy COBOL batch chain (``app/cbl`` ``CB*`` programs
# and ``app/jcl`` IDCAMS load jobs) and exist to reconcile batch outputs
# field-for-field against the legacy ``app/data`` seed files (golden-master
# parity -- AAP 0.5.2 / 0.8.1). The user-security seed maps to ``CSUSR01Y``
# (``SEC-USER-DATA``) loaded by the legacy ``DUSRSECJ`` job.
"""Root conftest for the CardDemo batch parity/unit test suite.

This is the SINGLE foundational fixtures module for ``batch/tests/`` -- every
test under ``batch/tests/unit/`` and ``batch/tests/integration/`` inherits the
fixtures defined here. It bootstraps ``sys.path`` and environment variables so
that both the backend package (``app.*``) and the batch CLI package
(``batch.*``) import cleanly when pytest is launched from the repository root,
and it then provides synchronous SQLAlchemy fixtures, filesystem-path fixtures
for the legacy seed data, an injectable ``session_factory``, and seed fixtures
that drive ``batch.loaders`` / ``batch.orchestration.SeedAll``.

The batch layer is SYNCHRONOUS. These fixtures use plain ``pytest`` with the
blocking ``psycopg2`` engine exposed by :mod:`batch.db`; they deliberately do
NOT use ``pytest-asyncio``, an ``httpx`` ASGI client, or the async
``app.db.session`` engine (that async/asyncpg path belongs to the FastAPI
request layer, never to batch).

CRITICAL ORDERING RULE (do not reorder the top of this module):
    :mod:`batch.db` binds its synchronous engine to
    ``batchSettings.SYNC_DATABASE_URL`` at IMPORT time, and importing the backend
    security layer (transitively, ``app.core.config.Settings``) requires a
    ``SECRET_KEY`` that has no default. Therefore this module MUST, at the very
    top and BEFORE importing anything from ``app`` or ``batch``:
        1. perform stdlib imports;
        2. compute the repository paths and insert them onto ``sys.path``;
        3. set the ``SECRET_KEY``, ``SYNC_DATABASE_URL`` and ``ENVIRONMENT``
           environment variables;
        4. and only THEN import ``pytest``, SQLAlchemy, ``app.*`` and ``batch.*``.
    Importing ``batch.db`` or the backend config before steps 2-3 would fail
    collection; this ordering is the number-one correctness requirement here.

Isolation model:
    A session-scoped autouse fixture creates the full schema once (from
    ``app.db.base.Base`` metadata) in a DEDICATED test database, and a
    function-scoped fixture wraps every test in an outer transaction that is
    rolled back on teardown -- so tests never contaminate one another and the
    developer's ``carddemo`` database is never touched.
"""

from __future__ import annotations

import os
import sys
from collections.abc import Callable, Iterator
from contextlib import AbstractContextManager, contextmanager
from pathlib import Path

# --------------------------------------------------------------------------- #
# Phase 1 -- sys.path bootstrap.
# --------------------------------------------------------------------------- #
# ``__file__`` resolves to ``<repo>/batch/tests/conftest.py``; ``parents[2]`` is
# therefore the repository root (parents[0]=batch/tests, parents[1]=batch,
# parents[2]=<repo>). Both the repository root (for ``import batch...``) and the
# sibling ``backend`` directory (for ``import app...``) are placed on
# ``sys.path`` so the suite imports both packages regardless of the caller's
# working directory. ``batch/__init__.py`` also carries a defensive backend
# shim, but adding both paths explicitly here means import order never depends
# on that shim firing first.
THIS_FILE = Path(__file__).resolve()
REPO_ROOT = THIS_FILE.parents[2]
BACKEND_DIR = REPO_ROOT / "backend"
for _BOOTSTRAP_PATH in (str(REPO_ROOT), str(BACKEND_DIR)):
    if _BOOTSTRAP_PATH not in sys.path:
        sys.path.insert(0, _BOOTSTRAP_PATH)

# --------------------------------------------------------------------------- #
# Phase 2 -- environment bootstrap (BEFORE importing app/batch configuration).
# --------------------------------------------------------------------------- #
# Test-only, non-secret signing key. This is an obvious placeholder used solely
# to satisfy the backend's required, no-default ``SECRET_KEY`` (and its >= 32
# character length rule) during local/CI test collection. It is NOT a hardcoded
# production secret (Ochs Rule #3): it is set with ``setdefault`` so a real
# environment/CI value always wins, and it never authenticates anything real.
TEST_SECRET_KEY = "carddemo-batch-tests-secret-key-not-for-production-use-only"
os.environ.setdefault("SECRET_KEY", TEST_SECRET_KEY)

# Force the synchronous engine onto a DEDICATED test database so the
# session-scoped ``create_all`` / ``drop_all`` can never build or drop tables in
# a development or production database. The value comes from the
# ``TEST_DATABASE_URL`` environment variable (so CI can point at its own
# Postgres) and otherwise defaults to a local ``carddemo_test`` database. The
# assignment to ``SYNC_DATABASE_URL`` is a HARD override (not ``setdefault``):
# ``batch.db.ENGINE`` must never risk binding to the real ``carddemo`` database
# and dropping its tables.
TEST_DATABASE_URL = os.environ.get(
    "TEST_DATABASE_URL",
    "postgresql+psycopg2://carddemo:carddemo@localhost:5432/carddemo_test",
)
os.environ["SYNC_DATABASE_URL"] = TEST_DATABASE_URL
os.environ.setdefault("ENVIRONMENT", "test")

# --------------------------------------------------------------------------- #
# Phase 3 -- imports (only AFTER the path + environment bootstrap above).
# --------------------------------------------------------------------------- #
# These imports intentionally follow executable bootstrap code, so ruff's E402
# ("module import not at top of file") is suppressed per import. ``import
# app.models`` is imported purely for its registration side effect -- it maps
# all 10 ORM classes onto ``Base.metadata`` -- so F401 ("imported but unused")
# is suppressed as well; it MUST run before any ``create_all``.
import pytest  # noqa: E402

from sqlalchemy import event  # noqa: E402
from sqlalchemy.orm import Session  # noqa: E402

import app.models  # noqa: E402,F401  (side effect: registers all 10 ORM tables)
from app.db.base import Base  # noqa: E402

# ``batch.db`` is bound to the TEST database because Phase 2 already forced
# ``SYNC_DATABASE_URL``. Importing it builds the engine and session factory but
# opens no socket (the engine connects lazily), so this import stays safe even
# when no PostgreSQL server is running.
import batch.db as batch_db  # noqa: E402


# --------------------------------------------------------------------------- #
# Phase 4 -- schema lifecycle (session-scoped, autouse).
# --------------------------------------------------------------------------- #
@pytest.fixture(scope="session", autouse=True)
def _create_schema() -> Iterator[None]:
    """Build the disposable test schema once per session; drop it at the end.

    Creates every table registered on ``app.db.base.Base.metadata`` (populated
    by the ``import app.models`` above) in the dedicated test database, yields
    for the duration of the test session, and drops the schema on teardown. The
    batch package deliberately owns no DDL (Alembic owns migrations in
    production), so the test suite owns its own disposable schema here.

    A live Postgres 17 test database is required: if the server is unreachable
    this fixture raises :class:`sqlalchemy.exc.OperationalError` at setup, which
    is the expected signal that integration tests need the docker-compose
    ``postgres:17`` service running. SQLite is intentionally NOT used as a
    fallback -- ``NUMERIC`` precision, ``SELECT ... FOR UPDATE`` and
    zoned-decimal parity all require real PostgreSQL semantics.

    Yields:
        None. The fixture exists for its create/drop side effects only.
    """
    Base.metadata.create_all(bind=batch_db.ENGINE)
    try:
        yield
    finally:
        # Let a failed drop raise (do not swallow it) so a broken teardown stays
        # visible; the disposable schema must not silently linger.
        Base.metadata.drop_all(bind=batch_db.ENGINE)


# --------------------------------------------------------------------------- #
# Phase 5 -- function-scoped isolated session (transaction rollback).
# --------------------------------------------------------------------------- #
@pytest.fixture
def db_session() -> Iterator[Session]:
    """Yield a fully isolated, rolled-back synchronous ``Session`` per test.

    Implements the classic SQLAlchemy "join an external transaction" recipe: a
    single connection opens one outer transaction, a :class:`Session` is bound
    to that connection, and a SAVEPOINT is started. An
    ``after_transaction_end`` listener restarts the SAVEPOINT whenever inner
    code ends it (for example if a job/loader were to commit), which keeps the
    OUTER transaction open and fully rollback-able for the whole test. On
    teardown the session is closed and the outer transaction is rolled back, so
    every write made by a test -- including via the seed fixtures and the batch
    chain -- is discarded. No test can contaminate another, and the real
    ``carddemo`` database is never touched.

    Yields:
        Session: An open, transaction-scoped SQLAlchemy ORM session.
    """
    connection = batch_db.ENGINE.connect()
    transaction = connection.begin()
    session = batch_db.SessionLocal(bind=connection)
    session.begin_nested()

    @event.listens_for(session, "after_transaction_end")
    def RestartSavepoint(sessionArg: Session, transactionArg: object) -> None:
        """Reopen the SAVEPOINT after a first-level nested transaction ends.

        Fires on every transaction-end. Only when a first-level SAVEPOINT ends
        (a nested transaction whose parent is the non-nested root) is a fresh
        SAVEPOINT started, so an inner ``commit()`` never closes the outer
        transaction. When the root transaction itself ends (at teardown), the
        guard is false and no SAVEPOINT is reopened, avoiding any restart loop.
        """
        if transactionArg.nested and not transactionArg._parent.nested:
            sessionArg.begin_nested()

    try:
        yield session
    finally:
        session.close()
        if transaction.is_active:
            transaction.rollback()
        connection.close()


# --------------------------------------------------------------------------- #
# Phase 6 -- injectable session factory.
# --------------------------------------------------------------------------- #
@pytest.fixture
def session_factory(
    db_session: Session,
) -> Callable[[], AbstractContextManager[Session]]:
    """Return a session-factory callable bound to the isolated ``db_session``.

    ``batch.orchestration.RunBatchChain`` / ``SeedAll`` and the job/loader entry
    functions accept an injectable ``sessionFactory`` -- a zero-argument callable
    returning a context manager that yields a :class:`Session` (the production
    default is :func:`batch.db.GetSyncSession`). This fixture returns such a
    callable that always yields the ONE rolled-back ``db_session``, so every
    chain step, loader and job in a test shares a single outer transaction and is
    discarded together on teardown.

    Tests inject it as ``sessionFactory=session_factory`` (for example
    ``SeedAll(dataDir=data_dir, sessionFactory=session_factory)`` or
    ``RunBatchChain(sessionFactory=session_factory)``).

    Returns:
        A zero-argument callable returning a context manager that yields the
        shared, isolated :class:`Session`.
    """

    @contextmanager
    def TestSessionFactory() -> Iterator[Session]:
        """Yield the shared session; flush (never commit/close) on clean exit.

        ``batch.orchestration._RunStep`` enters one ``with sessionFactory() as
        session:`` block per step, so this context manager is entered and exited
        once per chain step / loader. On a clean exit it flushes -- so a later
        step observes the earlier step's writes -- but it never commits and never
        closes: the ``db_session`` fixture exclusively owns commit/rollback/close
        of the outer transaction. If the wrapped block raises, control leaves via
        the ``yield`` before the flush, so the original exception propagates
        unchanged and this factory performs no rollback of its own.
        """
        yield db_session
        db_session.flush()

    return TestSessionFactory


# --------------------------------------------------------------------------- #
# Phase 7 -- legacy seed-data path fixtures (derived from REPO_ROOT).
# --------------------------------------------------------------------------- #
@pytest.fixture(scope="session")
def data_dir() -> Path:
    """Return ``<repo>/app/data/ASCII`` -- the primary golden-master seed source.

    This directory holds the nine ASCII seed files (``acctdata``, ``carddata``,
    ``cardxref``, ``custdata``, ``dailytran``, ``discgrp``, ``tcatbal``,
    ``trancatg``, ``trantype``) the loaders read and against which batch outputs
    are reconciled field-for-field. The directory must exist; a mislocated
    checkout fails loudly here rather than deep inside a loader.

    Returns:
        The :class:`~pathlib.Path` of the ASCII seed directory.
    """
    path = REPO_ROOT / "app" / "data" / "ASCII"
    if not path.is_dir():
        pytest.fail(f"ASCII seed directory not found: {path}")
    return path


@pytest.fixture(scope="session")
def ebcdic_dir() -> Path:
    """Return ``<repo>/app/data/EBCDIC`` -- the EBCDIC dataset directory.

    Returns:
        The :class:`~pathlib.Path` of the EBCDIC dataset directory.
    """
    return REPO_ROOT / "app" / "data" / "EBCDIC"


@pytest.fixture(scope="session")
def usrsec_path(ebcdic_dir: Path) -> Path:
    """Return the EBCDIC ``USRSEC`` dataset path (the user-security seed source).

    ``AWS.M2.CARDDEMO.USRSEC.PS`` is 800 bytes = 10 users x the 80-byte
    ``CSUSR01Y`` ``SEC-USER-DATA`` record, and is the REFERENCE input for
    :func:`batch.loaders.init_users.InitializeUsers` (legacy ``DUSRSECJ``).
    ``USRSEC`` has NO ASCII equivalent -- it exists only in EBCDIC -- so the user
    seed always reads this dataset (and hashes the plaintext ``SEC-USR-PWD``,
    never storing it in cleartext).

    Returns:
        The :class:`~pathlib.Path` of the EBCDIC ``USRSEC`` dataset.
    """
    return ebcdic_dir / "AWS.M2.CARDDEMO.USRSEC.PS"


# --------------------------------------------------------------------------- #
# Phase 8 -- seed fixtures (drive batch.loaders / batch.orchestration.SeedAll).
# --------------------------------------------------------------------------- #
@pytest.fixture
def seeded_db(
    db_session: Session,
    session_factory: Callable[[], AbstractContextManager[Session]],
    data_dir: Path,
) -> Session:
    """Return a fully seeded, FK-safe, isolated session via ``SeedAll``.

    Runs :func:`batch.orchestration.batch_chain.SeedAll`, which loads every table
    in foreign-key-safe order (disclosure_group -> customers -> accounts -> cards
    -> card_xref -> transaction_type -> transaction_category ->
    tran_category_balance -> transactions -> users), using the injected
    ``session_factory`` so all loaders write into the ONE rolled-back
    ``db_session``. The row counts made available match the golden-master seed:
    accounts 50, cards 50, card_xref 50, customers 50, transactions 300 (from
    ``dailytran``), disclosure_group 51, tran_category_balance 50,
    transaction_category 18, transaction_type 7, and users 10 (from ``USRSEC``).

    IMPORTANT: ``SeedAll``'s ``SEED_STEPS`` order is FK-safe and is DIFFERENT
    from ``RunBatchChain``'s ``CHAIN_STEPS`` (which preserves the legacy README
    order -- loading XREFFILE before CUSTFILE -- and therefore assumes an
    already-seeded database). Full-chain tests must seed via this fixture FIRST
    and only then call ``RunBatchChain``.

    No commit is issued here: the injected ``session_factory`` / ``db_session``
    own the transaction lifecycle and roll everything back on teardown.

    Returns:
        The seeded, isolated :class:`Session` (the same instance as
        ``db_session``).
    """
    from batch.orchestration.batch_chain import SeedAll

    SeedAll(dataDir=data_dir, sessionFactory=session_factory)
    return db_session


@pytest.fixture
def seed_users(db_session: Session, usrsec_path: Path) -> Session:
    """Seed ONLY the ``users`` table from the EBCDIC ``USRSEC`` dataset.

    A lightweight alternative to :func:`seeded_db` for the user-hash parity test:
    it decodes ``AWS.M2.CARDDEMO.USRSEC.PS`` and upserts the 10 users with bcrypt
    password hashes via :func:`batch.loaders.init_users.InitializeUsers`. The
    plaintext ``SEC-USR-PWD`` is never persisted; a consuming test can assert
    ``app.core.security.VerifyPassword("PASSWORD", row.password_hash) is True``
    and that no plaintext password is stored.

    No commit is issued here; the write is rolled back on ``db_session``
    teardown.

    Returns:
        The isolated :class:`Session` after the user seed (the same instance as
        ``db_session``).
    """
    from batch.loaders.init_users import InitializeUsers

    InitializeUsers(db_session, usrsecPath=usrsec_path)
    return db_session


# --------------------------------------------------------------------------- #
# ---- Fixture inventory (contract for batch/tests/unit and                    #
# ----  batch/tests/integration child agents) --------------------------------#
# --------------------------------------------------------------------------- #
# Public fixtures provided by this root conftest (all fixture NAMES are
# snake_case -- the documented pytest framework-contract exception to the Ochs
# camelCase-variable rule, because a fixture name is referenced by test
# arguments):
#
#   db_session: Session
#       Isolated, transaction-rolled-back synchronous session bound to the test
#       PostgreSQL. Every write is discarded at test end.
#   session_factory: Callable[[], AbstractContextManager[Session]]
#       Inject as ``sessionFactory=`` into ``RunBatchChain`` / ``SeedAll`` /
#       jobs / loaders so they share the one rolled-back transaction.
#   data_dir: Path
#       ``<repo>/app/data/ASCII`` -- the nine ASCII golden-master seed files.
#   ebcdic_dir: Path
#       ``<repo>/app/data/EBCDIC`` -- the EBCDIC dataset directory.
#   usrsec_path: Path
#       ``<repo>/app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS`` (800 bytes, 10 users).
#   seeded_db: Session
#       Fully FK-safe seeded database (via ``SeedAll``) on the isolated session.
#   seed_users: Session
#       Users-only seed (via ``InitializeUsers``) for the user-hash parity test.
#
# Also note: unit tests that only exercise ``app.utils.decimal_utils``
# (import-safe, stdlib-only) or orchestration constants with a mocked
# ``sessionFactory`` need NO database fixture at all.

