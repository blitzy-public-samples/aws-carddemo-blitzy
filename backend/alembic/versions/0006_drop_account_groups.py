"""0006 drop the account_groups registry table + accounts.group_id FK.

QA finding C01 (CRITICAL): migration ``0004`` introduced an eleventh table,
``account_groups`` (plus a foreign key ``accounts.group_id -> account_groups``),
to give ``accounts.group_id`` a single-column FK target. That extra table
violates the AAP's exact **ten-table** data-model contract (AAP 0.5.1, which
enumerates exactly ten models) and has no basis in the legacy system:
``ACCT-GROUP-ID`` (``CVACT01Y``) was a free-text field on the account record and
a key prefix into ``DISCGRP`` -- the mainframe never carried a standalone
"account groups" dataset.

This migration reverses ``0004``: it drops the ``accounts.group_id`` foreign key
and the ``account_groups`` table, leaving ``group_id`` as the faithful plain
indexed ``VARCHAR(10)`` column defined in ``0001`` (the index it carries is the
one created there and is intentionally preserved for the interest-calc
account-group lookups). The result is the AAP-mandated ten-table schema.

Safety / reversibility:
    * ``upgrade`` (forward): drops the FK only when present and the table only
      when present, so it converges any prior state -- a fresh chain that just
      ran ``0004`` (table exists), or a database already advanced to head with
      the table -- to the ten-table target exactly once.
    * ``downgrade`` (rollback): recreates the ``account_groups`` registry,
      re-seeds it from ``SELECT DISTINCT group_id FROM disclosure_group``, and
      re-adds the foreign key -- byte-for-byte the state ``0004`` produced -- so
      the pair ``0004``/``0006`` is a clean, fully reversible round trip.

Revision ID: 0006
Revises: 0005
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# --- Alembic revision identifiers (framework-mandated lowercase/snake names;
# the Ochs PascalCase/camelCase rules do not apply to these module globals).
revision: str = "0006"
down_revision: str | None = "0005"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# --- Module constants (Ochs ALL_UPPERCASE). Mirror the names 0004 used so the
# forward/rollback round trip targets exactly the objects 0004 created. ---
ACCOUNT_GROUPS_TABLE_NAME = "account_groups"
ACCOUNTS_TABLE_NAME = "accounts"
DISCLOSURE_GROUP_TABLE_NAME = "disclosure_group"
GROUP_ID_COLUMN_NAME = "group_id"
GROUP_ID_LENGTH = 10
ACCOUNT_GROUPS_PK_NAME = "pk_account_groups"
ACCOUNTS_GROUP_FK_NAME = "fk_accounts_group_id_account_groups"

# INSERT ... SELECT DISTINCT used only by downgrade() to restore the registry to
# exactly the set of groups the seeded disclosure_group rows define.
SEED_FROM_DISCLOSURE_SQL = (
    f"INSERT INTO {ACCOUNT_GROUPS_TABLE_NAME} ({GROUP_ID_COLUMN_NAME}) "
    f"SELECT DISTINCT {GROUP_ID_COLUMN_NAME} FROM {DISCLOSURE_GROUP_TABLE_NAME} "
    f"WHERE {GROUP_ID_COLUMN_NAME} IS NOT NULL "
    f"ON CONFLICT ({GROUP_ID_COLUMN_NAME}) DO NOTHING"
)


def _TableExists(tableName: str) -> bool:
    """Report whether ``tableName`` currently exists on the bound database.

    Args:
        tableName: The physical table name to probe.

    Returns:
        ``True`` when the table exists on the migration's bind, else ``False``.
    """
    inspector = sa.inspect(op.get_bind())
    return tableName in inspector.get_table_names()


def _ForeignKeyExists(tableName: str, constraintName: str) -> bool:
    """Report whether a named foreign key exists on ``tableName``.

    Args:
        tableName: The child table that would carry the foreign key.
        constraintName: The foreign-key constraint name to look for.

    Returns:
        ``True`` when a foreign key of that name exists, else ``False``.
    """
    inspector = sa.inspect(op.get_bind())
    if tableName not in inspector.get_table_names():
        return False
    existingNames = {fk.get("name") for fk in inspector.get_foreign_keys(tableName)}
    return constraintName in existingNames


def upgrade() -> None:
    """Drop the accounts FK and the account_groups registry (reverse of 0004).

    Idempotent: the foreign key is dropped only when present and the table only
    when present, so the migration converges any prior state to the AAP
    ten-table schema exactly once. ``accounts.group_id`` reverts to the plain
    indexed column defined by ``0001``.
    """
    if _ForeignKeyExists(ACCOUNTS_TABLE_NAME, ACCOUNTS_GROUP_FK_NAME):
        op.drop_constraint(
            ACCOUNTS_GROUP_FK_NAME, ACCOUNTS_TABLE_NAME, type_="foreignkey"
        )
    if _TableExists(ACCOUNT_GROUPS_TABLE_NAME):
        op.drop_table(ACCOUNT_GROUPS_TABLE_NAME)


def downgrade() -> None:
    """Recreate the account_groups registry, re-seed it, and re-add the FK.

    Restores exactly the state migration ``0004`` produced, so the
    ``0004``/``0006`` pair is a clean, fully reversible round trip. Each step is
    guarded so the downgrade is safe when an object already exists.
    """
    if not _TableExists(ACCOUNT_GROUPS_TABLE_NAME):
        op.create_table(
            ACCOUNT_GROUPS_TABLE_NAME,
            sa.Column(GROUP_ID_COLUMN_NAME, sa.String(GROUP_ID_LENGTH), nullable=False),
            sa.PrimaryKeyConstraint(GROUP_ID_COLUMN_NAME, name=ACCOUNT_GROUPS_PK_NAME),
        )

    op.execute(SEED_FROM_DISCLOSURE_SQL)

    if not _ForeignKeyExists(ACCOUNTS_TABLE_NAME, ACCOUNTS_GROUP_FK_NAME):
        op.create_foreign_key(
            ACCOUNTS_GROUP_FK_NAME,
            ACCOUNTS_TABLE_NAME,
            ACCOUNT_GROUPS_TABLE_NAME,
            [GROUP_ID_COLUMN_NAME],
            [GROUP_ID_COLUMN_NAME],
        )
