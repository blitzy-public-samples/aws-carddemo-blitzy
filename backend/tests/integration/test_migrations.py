"""Migration-from-empty-database CI suite (finding M-18).

Root cause this suite closes
----------------------------
Every other backend test builds its schema from ``Base.metadata.create_all``
(see the ``_create_schema`` fixture in ``tests/conftest.py``). That path never
executes the Alembic migrations, so the migration scripts and the ORM models can
drift apart indefinitely and the whole test suite still passes green. A column
added to a model but forgotten in a migration -- or a column dropped by a
migration but still declared on a model -- would ship to production undetected.

What this suite proves
----------------------
It provisions a *dedicated, empty, disposable* PostgreSQL database, runs the real
``alembic upgrade head`` against it in a subprocess (the only faithful reproduction
of how CI/production actually apply migrations), and then asserts that the
resulting live schema matches ``Base.metadata`` exactly -- table for table,
column for column, primary key, foreign key and index. It additionally verifies
the deterministic seed data counts, guards the C-03 invariant that ``cards`` never
regains a CVV column, and confirms ``alembic downgrade base`` cleanly removes every
model table.

Why a subprocess
----------------
``alembic/env.py`` dispatches the online migration through ``asyncio.run(...)``.
Calling it in-process from inside pytest's own running event loop raises
``RuntimeError: asyncio.run() cannot be called from a running event loop``. A
subprocess is therefore mandatory -- and it is also exactly how migrations run in
CI, so the test exercises the production code path rather than a fake.

Isolation and safety
---------------------
Each throwaway database is named ``carddemo_migtest_<random>`` (the substring
``test`` marks it unmistakably disposable, mirroring the C-01 safety token). It is
created against the ``postgres`` maintenance database, used, and dropped in a
``finally`` block, so a failure never leaks a database and never touches the
application database or the shared ``*_test`` database used by the rest of the
suite. These tests are synchronous (plain psycopg2 + subprocess); they do not use
the async ``db_session`` fixtures at all.
"""

import os
import subprocess
import sys
import uuid

import pytest
from sqlalchemy import create_engine, inspect, text
from sqlalchemy.engine import make_url

from app.db.base import Base
import app.models  # noqa: F401 - side effect: registers all tables on Base.metadata

# --- Locations / invocation -------------------------------------------------
# ``backend/`` is where ``alembic.ini`` lives and is the working directory the
# migration subprocess must run from. This file is
# ``backend/tests/integration/test_migrations.py`` so the backend root is three
# parents up.
BACKEND_DIR = os.path.abspath(
    os.path.join(os.path.dirname(__file__), os.pardir, os.pardir)
)

# Base async URL is read from TEST_DATABASE_URL only (never the application
# DATABASE_URL), matching the resolution rule enforced in ``tests/conftest.py``.
# Each isolated migration database is derived from this identity (same host, port
# and credentials, a different database name).
DEFAULT_TEST_DATABASE_URL = (
    "postgresql+asyncpg://carddemo:carddemo@localhost:5432/carddemo_test"
)
BASE_TEST_DATABASE_URL = os.environ.get(
    "TEST_DATABASE_URL", DEFAULT_TEST_DATABASE_URL
)

# PostgreSQL maintenance database used to CREATE/DROP the throwaway databases
# (CREATE DATABASE cannot run inside a transaction, so the maintenance engine
# runs in AUTOCOMMIT).
MAINTENANCE_DATABASE = "postgres"

# The ``test`` substring is what proves the throwaway database is disposable, so
# it is embedded in every generated name.
ISOLATED_DATABASE_PREFIX = "carddemo_migtest_"

# Alembic revision targets.
ALEMBIC_UPGRADE_TARGET = "head"
ALEMBIC_DOWNGRADE_TARGET = "base"
ALEMBIC_VERSION_TABLE = "alembic_version"

# Hard ceiling for the migration subprocess. The full 0001->0005 chain (schema +
# seed of ~600 rows) completes in a few seconds locally; five minutes is a large
# safety margin that still prevents a hung process from stalling CI forever.
SUBPROCESS_TIMEOUT_SECONDS = 300

# Minimum SECRET_KEY length required by ``app.core.config``; the migration
# subprocess imports settings, so a valid key must be present in its environment.
MINIMUM_SECRET_KEY_LENGTH = 32
FALLBACK_TEST_SECRET_KEY = "migration-suite-testing-secret-key-0123456789"

# The complete set of ORM tables the migrated schema must contain, derived
# directly from the models so it can never silently disagree with them.
MODEL_TABLE_NAMES = frozenset(Base.metadata.tables)

# Column names that must NEVER appear on ``cards`` -- the C-03 invariant that CVV
# is not persisted. Any of these reappearing signals a security regression.
FORBIDDEN_CARD_COLUMNS = frozenset({"cvv", "cvv_cd", "card_cvv_cd"})

# Authoritative post-seed row counts. These are the fixed reference-data volumes
# from ``app/data`` (ASCII seed files, plus the 10 EBCDIC users). They were
# verified to agree simultaneously with (a) the live migrated database and (b) the
# conftest ASCII loader row counts, so any future divergence is a genuine
# migration/seed regression this suite must catch.
EXPECTED_SEED_COUNTS = {
    "accounts": 50,
    "cards": 50,
    "customers": 50,
    "card_xref": 50,
    "users": 10,
    "transactions": 300,
    "disclosure_group": 51,
    "tran_category_balance": 50,
    "transaction_type": 7,
    "transaction_category": 18,
}


# --- URL / naming helpers ---------------------------------------------------
def _GenerateIsolatedDatabaseName() -> str:
    """Return a unique, provably disposable name for a throwaway database.

    Returns:
        A name of the form ``carddemo_migtest_<hex>`` -- unique per call (so
        parallel CI clones never collide) and containing the ``test`` token that
        marks it disposable.
    """
    return f"{ISOLATED_DATABASE_PREFIX}{uuid.uuid4().hex[:12]}"


def _BuildIsolatedUrls(isolatedName: str) -> tuple[str, str]:
    """Derive the async and sync URLs for an isolated database.

    The host, port and credentials are inherited from
    :data:`BASE_TEST_DATABASE_URL`; only the database name is swapped.

    Args:
        isolatedName: The isolated database name.

    Returns:
        A ``(asyncUrl, syncUrl)`` tuple: the asyncpg URL Alembic consumes and the
        psycopg2 URL used for schema reflection.
    """
    baseUrl = make_url(BASE_TEST_DATABASE_URL)
    asyncUrl = baseUrl.set(database=isolatedName)
    syncUrl = asyncUrl.set(drivername="postgresql+psycopg2")
    return (asyncUrl.render_as_string(hide_password=False),
            syncUrl.render_as_string(hide_password=False))


def _MaintenanceUrl() -> str:
    """Return the sync (psycopg2) URL of the maintenance database.

    Returns:
        A psycopg2 URL pointing at the ``postgres`` maintenance database, used to
        CREATE and DROP the throwaway databases.
    """
    baseUrl = make_url(BASE_TEST_DATABASE_URL).set(
        drivername="postgresql+psycopg2", database=MAINTENANCE_DATABASE
    )
    return baseUrl.render_as_string(hide_password=False)


def _CreateIsolatedDatabase(isolatedName: str) -> None:
    """Create a fresh, empty isolated database, dropping any stale namesake first.

    Args:
        isolatedName: The database to (re)create.
    """
    maintenanceEngine = create_engine(
        _MaintenanceUrl(), isolation_level="AUTOCOMMIT"
    )
    try:
        with maintenanceEngine.connect() as connection:
            connection.execute(
                text(f'DROP DATABASE IF EXISTS "{isolatedName}"')
            )
            connection.execute(text(f'CREATE DATABASE "{isolatedName}"'))
    finally:
        maintenanceEngine.dispose()


def _DropIsolatedDatabase(isolatedName: str) -> None:
    """Drop an isolated database if it exists (best-effort teardown).

    Args:
        isolatedName: The database to remove.
    """
    maintenanceEngine = create_engine(
        _MaintenanceUrl(), isolation_level="AUTOCOMMIT"
    )
    try:
        with maintenanceEngine.connect() as connection:
            connection.execute(
                text(f'DROP DATABASE IF EXISTS "{isolatedName}"')
            )
    finally:
        maintenanceEngine.dispose()


def _MigrationChildEnvironment(isolatedAsyncUrl: str, isolatedSyncUrl: str) -> dict:
    """Build the environment for the Alembic subprocess.

    Inherits the current environment (so PATH, PG* and an already-present
    SECRET_KEY carry over) and overrides the database URLs to target the isolated
    database. ``ENVIRONMENT`` is pinned to ``test`` and a valid SECRET_KEY is
    guaranteed so ``app.core.config`` imports successfully inside the child.

    Args:
        isolatedAsyncUrl: asyncpg URL the online migration engine connects with.
        isolatedSyncUrl: psycopg2 URL (kept consistent for any sync consumer).

    Returns:
        The environment dictionary for :func:`subprocess.run`.
    """
    childEnv = dict(os.environ)
    childEnv["DATABASE_URL"] = isolatedAsyncUrl
    childEnv["SYNC_DATABASE_URL"] = isolatedSyncUrl
    childEnv["ENVIRONMENT"] = "test"
    existingSecret = childEnv.get("SECRET_KEY", "")
    if len(existingSecret.strip()) < MINIMUM_SECRET_KEY_LENGTH:
        childEnv["SECRET_KEY"] = FALLBACK_TEST_SECRET_KEY
    return childEnv


def _RunAlembic(isolatedAsyncUrl: str, isolatedSyncUrl: str, target: str) -> None:
    """Run ``alembic <upgrade|downgrade> <target>`` in a subprocess.

    Uses the current interpreter (``python -m alembic``) so the venv's Alembic and
    the application package are on the path, with the working directory set to
    ``backend/`` where ``alembic.ini`` lives.

    Args:
        isolatedAsyncUrl: asyncpg URL for the isolated database.
        isolatedSyncUrl: psycopg2 URL for the isolated database.
        target: Either an ``upgrade`` target (e.g. ``head``) or a ``downgrade``
            target (e.g. ``base``); the direction is inferred from the value.

    Raises:
        RuntimeError: If the migration subprocess exits with a non-zero code; the
            message includes the captured stdout/stderr (Alembic logs revision ids
            only, never credentials) to make CI failures diagnosable.
    """
    direction = "downgrade" if target == ALEMBIC_DOWNGRADE_TARGET else "upgrade"
    completed = subprocess.run(
        [sys.executable, "-m", "alembic", direction, target],
        cwd=BACKEND_DIR,
        env=_MigrationChildEnvironment(isolatedAsyncUrl, isolatedSyncUrl),
        capture_output=True,
        text=True,
        timeout=SUBPROCESS_TIMEOUT_SECONDS,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"alembic {direction} {target} failed "
            f"(exit {completed.returncode}).\n"
            f"--- stdout ---\n{completed.stdout}\n"
            f"--- stderr ---\n{completed.stderr}"
        )


# --- Model-derived expected schema (never hardcoded lists) ------------------
def _ModelColumnNames(tableName: str) -> set:
    """Return the set of column names the model declares for ``tableName``."""
    return {column.name for column in Base.metadata.tables[tableName].columns}


def _ModelPrimaryKey(tableName: str) -> list:
    """Return the ordered primary-key column names the model declares."""
    return [column.name
            for column in Base.metadata.tables[tableName].primary_key.columns]


def _ModelForeignKeys(tableName: str) -> set:
    """Return ``{(localColumn, referredTable), ...}`` the model declares."""
    table = Base.metadata.tables[tableName]
    return {(foreignKey.parent.name, foreignKey.column.table.name)
            for foreignKey in table.foreign_keys}


def _ModelIndexNames(tableName: str) -> set:
    """Return the explicit index names the model declares for ``tableName``."""
    return {index.name for index in Base.metadata.tables[tableName].indexes}


# --- Reflection helpers -----------------------------------------------------
def _ReflectedForeignKeys(inspector, tableName: str) -> set:
    """Return ``{(localColumn, referredTable), ...}`` present in the live schema."""
    reflected = set()
    for foreignKey in inspector.get_foreign_keys(tableName):
        referredTable = foreignKey["referred_table"]
        for localColumn in foreignKey["constrained_columns"]:
            reflected.add((localColumn, referredTable))
    return reflected


def _ReflectedIndexNames(inspector, tableName: str) -> set:
    """Return the index names present in the live schema for ``tableName``."""
    return {index["name"] for index in inspector.get_indexes(tableName)}


# --- Fixtures ---------------------------------------------------------------
@pytest.fixture(scope="module")
def migrated_sync_url():
    """Provision an empty DB, ``alembic upgrade head`` it, yield its sync URL.

    Module-scoped so the (few-second) migrate-and-seed runs once and is shared by
    every read-only assertion in this file. The database is always dropped in the
    teardown, even if a test fails.

    Yields:
        The psycopg2 URL of the freshly migrated isolated database.
    """
    isolatedName = _GenerateIsolatedDatabaseName()
    isolatedAsyncUrl, isolatedSyncUrl = _BuildIsolatedUrls(isolatedName)
    _CreateIsolatedDatabase(isolatedName)
    try:
        _RunAlembic(isolatedAsyncUrl, isolatedSyncUrl, ALEMBIC_UPGRADE_TARGET)
        yield isolatedSyncUrl
    finally:
        _DropIsolatedDatabase(isolatedName)


@pytest.fixture(scope="module")
def migrated_inspector(migrated_sync_url):
    """Yield a SQLAlchemy ``Inspector`` bound to the migrated database.

    Yields:
        An ``Inspector`` reflecting the live migrated schema.
    """
    engine = create_engine(migrated_sync_url)
    try:
        yield inspect(engine)
    finally:
        engine.dispose()


# --- Tests: schema parity (migration output == Base.metadata) ---------------
def test_migration_creates_exactly_the_model_tables(migrated_inspector):
    """``alembic upgrade head`` must create every model table (plus alembic_version).

    This is the headline drift guard: the live table set produced purely by the
    migrations must equal the model table set, with only Alembic's own bookkeeping
    table as an addition.
    """
    reflectedTables = set(migrated_inspector.get_table_names())
    assert ALEMBIC_VERSION_TABLE in reflectedTables, (
        "alembic_version table is missing -- migrations were not stamped"
    )
    modelTablesInSchema = reflectedTables - {ALEMBIC_VERSION_TABLE}
    assert modelTablesInSchema == set(MODEL_TABLE_NAMES), (
        "Migrated table set does not match Base.metadata. "
        f"missing={set(MODEL_TABLE_NAMES) - modelTablesInSchema} "
        f"unexpected={modelTablesInSchema - set(MODEL_TABLE_NAMES)}"
    )


def test_migration_columns_match_models(migrated_inspector):
    """Each migrated table must expose exactly the columns its model declares.

    Catches the two classic drift defects: a column added to a model but not to a
    migration (missing here) and a column left in the schema by a migration but
    removed from the model (unexpected here).
    """
    for tableName in sorted(MODEL_TABLE_NAMES):
        reflectedColumns = {
            column["name"] for column in migrated_inspector.get_columns(tableName)
        }
        expectedColumns = _ModelColumnNames(tableName)
        assert reflectedColumns == expectedColumns, (
            f"Column drift on {tableName!r}: "
            f"missing={expectedColumns - reflectedColumns} "
            f"unexpected={reflectedColumns - expectedColumns}"
        )


def test_migration_primary_keys_match_models(migrated_inspector):
    """Each migrated table's primary key must match the model's, order included."""
    for tableName in sorted(MODEL_TABLE_NAMES):
        reflectedPk = migrated_inspector.get_pk_constraint(tableName)
        reflectedColumns = reflectedPk.get("constrained_columns", [])
        expectedColumns = _ModelPrimaryKey(tableName)
        assert reflectedColumns == expectedColumns, (
            f"Primary-key drift on {tableName!r}: "
            f"expected {expectedColumns}, migrated schema has {reflectedColumns}"
        )


def test_migration_foreign_keys_match_models(migrated_inspector):
    """Every model foreign key must exist in the migrated schema.

    This proves referential integrity is created by the *migrations*, not merely
    declared on the models -- including the ``card_xref`` relationships.
    """
    for tableName in sorted(MODEL_TABLE_NAMES):
        expectedForeignKeys = _ModelForeignKeys(tableName)
        if not expectedForeignKeys:
            continue
        reflectedForeignKeys = _ReflectedForeignKeys(migrated_inspector, tableName)
        assert expectedForeignKeys <= reflectedForeignKeys, (
            f"Foreign-key drift on {tableName!r}: "
            f"missing {expectedForeignKeys - reflectedForeignKeys}"
        )


def test_migration_has_no_account_groups_table(migrated_inspector):
    """Regression pin for C01: the extra account_groups table must NOT exist.

    The AAP fixes the data model at exactly ten tables (AAP 0.5.1). The earlier
    ``account_groups`` registry (added by 0004 and removed by 0006) violated that
    contract, so the migrated schema must contain no such table and
    ``accounts.group_id`` must be a plain column with no foreign key.
    """
    tableNames = set(migrated_inspector.get_table_names())
    assert "account_groups" not in tableNames, (
        "C01 regression: the forbidden account_groups table is present in the "
        "migrated schema (the AAP mandates exactly ten tables)"
    )
    reflectedForeignKeys = _ReflectedForeignKeys(migrated_inspector, "accounts")
    assert not any(target == "account_groups" for _, target in reflectedForeignKeys), (
        "C01 regression: accounts.group_id must be a plain indexed column, not a "
        "foreign key to account_groups"
    )


def test_migration_indexes_match_models(migrated_inspector):
    """Every explicit model index must be present in the migrated schema."""
    for tableName in sorted(MODEL_TABLE_NAMES):
        expectedIndexes = _ModelIndexNames(tableName)
        if not expectedIndexes:
            continue
        reflectedIndexes = _ReflectedIndexNames(migrated_inspector, tableName)
        assert expectedIndexes <= reflectedIndexes, (
            f"Index drift on {tableName!r}: "
            f"missing {expectedIndexes - reflectedIndexes}"
        )


def test_migration_cards_has_no_cvv_column(migrated_inspector):
    """C-03 regression pin: the migrated ``cards`` table must never carry CVV."""
    reflectedColumns = {
        column["name"] for column in migrated_inspector.get_columns("cards")
    }
    leakedColumns = FORBIDDEN_CARD_COLUMNS & reflectedColumns
    assert not leakedColumns, (
        f"C-03 regression: forbidden CVV column(s) {leakedColumns} present on "
        "the migrated cards table"
    )


# --- Tests: seed data (0002_seed_data) --------------------------------------
def test_migration_seeds_expected_row_counts(migrated_sync_url):
    """The seed migration must load the exact authoritative reference-data volumes.

    Confirms 0002_seed_data populates every table to the fixed counts from
    ``app/data`` (ASCII seeds + 10 EBCDIC users), so a truncated or duplicated
    seed is caught.
    """
    engine = create_engine(migrated_sync_url)
    try:
        with engine.connect() as connection:
            for tableName, expectedCount in sorted(EXPECTED_SEED_COUNTS.items()):
                actualCount = connection.execute(
                    text(f"SELECT count(*) FROM {tableName}")
                ).scalar_one()
                assert actualCount == expectedCount, (
                    f"Seed-count drift on {tableName!r}: "
                    f"expected {expectedCount}, migrated schema has {actualCount}"
                )
    finally:
        engine.dispose()


def test_migration_seeds_daily_transactions_as_pending_staging(migrated_sync_url):
    """Regression pin for C02: seeded daily transactions are PENDING staging.

    ``dailytran.txt`` is the legacy CVTRA06Y daily-transaction file -- the INPUT
    to posting (AAP 0.7.5) -- and every row has a blank ``proc_ts`` (the
    processing timestamp POSTTRAN stamps only when it posts a row). After the
    full migration chain (0002 seeds PENDING; 0007 repairs any legacy POSTED
    rows) every seeded transaction must therefore be PENDING with a NULL
    ``proc_ts``; none may be POSTED with no processing timestamp (the incoherent
    state C02 flagged).
    """
    engine = create_engine(migrated_sync_url)
    try:
        with engine.connect() as connection:
            postedWithoutProcTs = connection.execute(
                text(
                    "SELECT count(*) FROM transactions "
                    "WHERE status = 'POSTED' AND proc_ts IS NULL"
                )
            ).scalar_one()
            assert postedWithoutProcTs == 0, (
                "C02 regression: found POSTED transactions with a NULL proc_ts "
                f"({postedWithoutProcTs}); daily-staging rows must be PENDING"
            )
            pendingCount = connection.execute(
                text("SELECT count(*) FROM transactions WHERE status = 'PENDING'")
            ).scalar_one()
            assert pendingCount == 300, (
                "C02 regression: expected 300 PENDING daily-staging transactions, "
                f"migrated schema has {pendingCount}"
            )
    finally:
        engine.dispose()


def test_migration_stamps_a_single_alembic_version(migrated_sync_url):
    """After ``upgrade head`` exactly one revision must be stamped (non-empty)."""
    engine = create_engine(migrated_sync_url)
    try:
        with engine.connect() as connection:
            stampedVersions = connection.execute(
                text(f"SELECT version_num FROM {ALEMBIC_VERSION_TABLE}")
            ).scalars().all()
        assert len(stampedVersions) == 1, (
            f"Expected exactly one stamped revision, found {stampedVersions}"
        )
        assert stampedVersions[0].strip(), "Stamped revision id is blank"
    finally:
        engine.dispose()


# --- Tests: downgrade -------------------------------------------------------
def test_migration_downgrade_base_removes_every_model_table():
    """``alembic downgrade base`` must cleanly remove all model tables.

    Manages its own throwaway database (independent of the shared module fixture):
    upgrade to head, confirm the tables exist, downgrade to base, confirm none of
    the model tables remain. Proves the migrations' ``downgrade()`` paths are
    real and reversible, not stubs.
    """
    isolatedName = _GenerateIsolatedDatabaseName()
    isolatedAsyncUrl, isolatedSyncUrl = _BuildIsolatedUrls(isolatedName)
    _CreateIsolatedDatabase(isolatedName)
    try:
        _RunAlembic(isolatedAsyncUrl, isolatedSyncUrl, ALEMBIC_UPGRADE_TARGET)
        engine = create_engine(isolatedSyncUrl)
        try:
            tablesAfterUpgrade = set(inspect(engine).get_table_names())
        finally:
            engine.dispose()
        assert set(MODEL_TABLE_NAMES) <= tablesAfterUpgrade, (
            "Upgrade did not create all model tables before downgrade test"
        )

        _RunAlembic(isolatedAsyncUrl, isolatedSyncUrl, ALEMBIC_DOWNGRADE_TARGET)
        engine = create_engine(isolatedSyncUrl)
        try:
            tablesAfterDowngrade = set(inspect(engine).get_table_names())
        finally:
            engine.dispose()
        survivingModelTables = set(MODEL_TABLE_NAMES) & tablesAfterDowngrade
        assert not survivingModelTables, (
            "downgrade base left model tables behind: "
            f"{sorted(survivingModelTables)}"
        )
    finally:
        _DropIsolatedDatabase(isolatedName)
