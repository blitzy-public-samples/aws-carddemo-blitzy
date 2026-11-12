"""0004 add account_groups registry + accounts.group_id FK (referential integrity).

QA finding M-16 (MAJOR): ``accounts.group_id`` (``ACCT-GROUP-ID`` from copybook
``CVACT01Y``) was only indexed, but the AAP requires it to be a real FOREIGN KEY
(AAP 0.5.1 accounts row: "group_id FK"; AAP 0.8.1: "Preserve referential
integrity"). The only other group-bearing table, ``disclosure_group``, has a
COMPOSITE primary key ``(group_id, tran_type_cd, tran_cat_cd)``, so ``group_id``
alone cannot be a foreign-key target there.

This migration introduces the enforceable parent/reference the finding directs:

  1. Creates the ``account_groups`` registry table whose sole primary key is
     ``group_id`` -- the single-column FK target ``accounts.group_id`` needs.
  2. Populates it with the distinct account-group identifiers already present in
     the seeded ``disclosure_group`` table (``SELECT DISTINCT group_id``), so the
     registry is exactly the set of groups the golden-master dataset defines
     (``A000000000``, ``DEFAULT``, ``ZEROAPR``) and every existing/created account
     that references a disclosure-known group satisfies the constraint. Seeded
     accounts carry a NULL ``group_id`` (the CVACT01Y seed quirk), and NULLs are
     exempt from the FK check.
  3. Adds the ``fk_accounts_group_id_account_groups`` foreign key.

Each step is guarded by a live information_schema/inspector probe so the
migration is idempotent and safe to run exactly once in any prior state (fresh
chain, or a database already advanced past 0001 without the FK).

Revision ID: 0004
Revises: 0003
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# --- Alembic revision identifiers (framework-mandated lowercase/snake names;
# the Ochs PascalCase/camelCase rules do not apply to these module globals).
revision: str = "0004"
down_revision: str | None = "0003"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# --- Module constants (Ochs ALL_UPPERCASE). ---
ACCOUNT_GROUPS_TABLE_NAME = "account_groups"
ACCOUNTS_TABLE_NAME = "accounts"
DISCLOSURE_GROUP_TABLE_NAME = "disclosure_group"
GROUP_ID_COLUMN_NAME = "group_id"
GROUP_ID_LENGTH = 10
ACCOUNT_GROUPS_PK_NAME = "pk_account_groups"
ACCOUNTS_GROUP_FK_NAME = "fk_accounts_group_id_account_groups"

# INSERT ... SELECT DISTINCT that populates the registry from the already-seeded
# disclosure_group rows. NULL guard is defensive (disclosure_group.group_id is a
# NOT NULL PK part, but the WHERE keeps the statement correct in any state).
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
    """Create the account_groups registry, seed it, and add the accounts FK.

    Idempotent: the table is created only when absent, the seed uses
    ``ON CONFLICT DO NOTHING``, and the foreign key is added only when a
    same-named constraint is not already present.
    """
    if not _TableExists(ACCOUNT_GROUPS_TABLE_NAME):
        op.create_table(
            ACCOUNT_GROUPS_TABLE_NAME,
            sa.Column(GROUP_ID_COLUMN_NAME, sa.String(GROUP_ID_LENGTH), nullable=False),
            sa.PrimaryKeyConstraint(GROUP_ID_COLUMN_NAME, name=ACCOUNT_GROUPS_PK_NAME),
        )

    # Populate the registry from the distinct groups already seeded into
    # disclosure_group (migration 0002). Safe to re-run (ON CONFLICT DO NOTHING).
    op.execute(SEED_FROM_DISCLOSURE_SQL)

    if not _ForeignKeyExists(ACCOUNTS_TABLE_NAME, ACCOUNTS_GROUP_FK_NAME):
        op.create_foreign_key(
            ACCOUNTS_GROUP_FK_NAME,
            ACCOUNTS_TABLE_NAME,
            ACCOUNT_GROUPS_TABLE_NAME,
            [GROUP_ID_COLUMN_NAME],
            [GROUP_ID_COLUMN_NAME],
        )


def downgrade() -> None:
    """Drop the accounts FK and the account_groups registry table.

    The reverse of :func:`upgrade`, guarded so it is safe when the objects are
    already absent. ``accounts.group_id`` reverts to an indexed-only column.
    """
    if _ForeignKeyExists(ACCOUNTS_TABLE_NAME, ACCOUNTS_GROUP_FK_NAME):
        op.drop_constraint(
            ACCOUNTS_GROUP_FK_NAME, ACCOUNTS_TABLE_NAME, type_="foreignkey"
        )
    if _TableExists(ACCOUNT_GROUPS_TABLE_NAME):
        op.drop_table(ACCOUNT_GROUPS_TABLE_NAME)
