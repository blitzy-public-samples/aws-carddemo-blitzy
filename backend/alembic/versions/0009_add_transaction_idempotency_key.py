"""0009 add transactions.idempotency_key + partial unique index (exactly-once add).

QA finding "Rapid Transaction Add confirmation creates duplicate financial
transactions" (MAJOR, Financial Integrity / Concurrency): two identical
concurrent ``POST /transactions`` requests each received a distinct
``MAX(tran_id)+1`` identifier and each applied a balance delta, so one user
operation produced TWO transactions and DOUBLE the intended balance change. The
legacy CICS/3270 terminal could not double-submit the way a web button can, so
the mainframe never needed this guard; the modern web tier does (AAP 0.7.4 --
"concurrency semantics must be preserved": the CICS enclave serialized these
posts and the web tier must too).

This migration adds the storage the server-enforced idempotency guard relies on:

    transactions.idempotency_key   VARCHAR(64) NULL
        A 64-character SHA-256 digest of the caller's ``Idempotency-Key`` header
        (when supplied) or a deterministic fingerprint of the request's business
        content. It is a MODERN OPERATIONAL column, NOT a CVTRA05Y/CVTRA06Y field,
        and is never surfaced in an API response.

    uq_transactions_idempotency_key   UNIQUE (idempotency_key)
                                      WHERE idempotency_key IS NOT NULL
        A PARTIAL unique index so two identical concurrent adds collapse to ONE
        committed row: the second committer collides on the index, rolls back its
        transaction (voiding its balance updates), and returns the winner's row.
        The ``WHERE ... IS NOT NULL`` predicate scopes the constraint to online
        adds; the ~300 seeded rows and every batch-posted / daily-staging row
        carry NULL and are therefore untouched -- no legacy TRAN-RECORD semantics
        change (AAP 0.8.1 Minimal Change Clause).

Both steps are guarded (``_ColumnExists`` probe; ``CREATE ... INDEX IF NOT
EXISTS``) so the migration is idempotent and safe to run once from any prior
state, and both are fully reversible. The identical column + partial unique index
are declared on the SQLAlchemy model (``app.models.transaction``) so a
``create_all``-built schema (the test suite) matches an Alembic-migrated database
exactly.

Revision ID: 0009
Revises: 0008
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

# --- Alembic revision identifiers (framework-mandated lowercase/snake names;
# the Ochs PascalCase/camelCase rules do not apply to these module globals).
revision: str = "0009"
down_revision: str | None = "0008"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# --- Module constants (Ochs ALL_UPPERCASE). ---
TRANSACTIONS_TABLE_NAME = "transactions"
IDEMPOTENCY_KEY_COLUMN_NAME = "idempotency_key"
# SHA-256 hex digest length; the header/fingerprint is always hashed to exactly
# this many characters before storage, so VARCHAR(64) is an exact fit.
IDEMPOTENCY_KEY_LENGTH = 64
IDEMPOTENCY_UNIQUE_INDEX_NAME = "uq_transactions_idempotency_key"

# Partial UNIQUE index: enforced only for rows an online add tagged with a
# non-NULL key, so pre-existing seeded/batch/daily rows (all NULL) never collide.
CREATE_IDEMPOTENCY_INDEX_SQL = (
    f"CREATE UNIQUE INDEX IF NOT EXISTS {IDEMPOTENCY_UNIQUE_INDEX_NAME} "
    f"ON {TRANSACTIONS_TABLE_NAME} ({IDEMPOTENCY_KEY_COLUMN_NAME}) "
    f"WHERE {IDEMPOTENCY_KEY_COLUMN_NAME} IS NOT NULL"
)
DROP_IDEMPOTENCY_INDEX_SQL = f"DROP INDEX IF EXISTS {IDEMPOTENCY_UNIQUE_INDEX_NAME}"


def _ColumnExists(tableName: str, columnName: str) -> bool:
    """Report whether ``columnName`` currently exists on ``tableName``.

    Args:
        tableName: The physical table name to probe.
        columnName: The column name to look for on that table.

    Returns:
        ``True`` when the table has a column of that name on the migration's
        bind, else ``False`` (including when the table itself is absent).
    """
    inspector = sa.inspect(op.get_bind())
    if tableName not in inspector.get_table_names():
        return False
    existingNames = {column["name"] for column in inspector.get_columns(tableName)}
    return columnName in existingNames


def upgrade() -> None:
    """Add ``transactions.idempotency_key`` and its partial unique index.

    Idempotent: the nullable column is added only when absent, and the partial
    unique index is created with ``IF NOT EXISTS``. Existing rows remain NULL, so
    the new constraint changes no stored data and the added column preserves the
    legacy TRAN-RECORD semantics (the column is a modern operational guard, never
    a copybook field).
    """
    if not _ColumnExists(TRANSACTIONS_TABLE_NAME, IDEMPOTENCY_KEY_COLUMN_NAME):
        op.add_column(
            TRANSACTIONS_TABLE_NAME,
            sa.Column(
                IDEMPOTENCY_KEY_COLUMN_NAME,
                sa.String(IDEMPOTENCY_KEY_LENGTH),
                nullable=True,
            ),
        )
    op.execute(CREATE_IDEMPOTENCY_INDEX_SQL)


def downgrade() -> None:
    """Drop the partial unique index and the ``idempotency_key`` column.

    The exact reverse of :func:`upgrade`, each step guarded so it is safe when the
    object is already absent. Posting still works afterward; only the
    server-enforced exactly-once guard is removed.
    """
    op.execute(DROP_IDEMPOTENCY_INDEX_SQL)
    if _ColumnExists(TRANSACTIONS_TABLE_NAME, IDEMPOTENCY_KEY_COLUMN_NAME):
        op.drop_column(TRANSACTIONS_TABLE_NAME, IDEMPOTENCY_KEY_COLUMN_NAME)
