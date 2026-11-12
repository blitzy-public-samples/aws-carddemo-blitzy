"""0005 add users.session_version (server-side token revocation anchor).

QA finding M-02 (MAJOR): logout only deleted the client cookie, so a captured
pre-logout token stayed valid on protected routes -- there was no server-side
revocation, token version, or privilege-change rotation. This migration adds the
revocation anchor the fix relies on.

``users.session_version`` is a NOT NULL integer generation counter (ported as the
new ``User.session_version`` mapped column, model constant ``INITIAL_SESSION_VERSION
= 1``). Each minted token freezes the user's current value as its ``sver`` claim;
``get_current_user`` rejects any token whose ``sver`` no longer equals the stored
value. Advancing the counter -- on logout (``AuthService.RevokeSession``) or a
role/password change (``UserAdminService._ApplyChanges``) -- therefore invalidates
every token issued beforehand, reproducing the finality CICS sign-off gave a
terminal session.

A ``server_default`` of ``'1'`` backfills every pre-existing row (so already-seeded
users become version 1, exactly matching a freshly seeded database) and lets the
column be added NOT NULL in one step without a table rewrite gap. New rows take the
same default at the database level, so a direct SQL insert that omits the column is
still valid.

The step is guarded by a live inspector probe on the column so the migration is
idempotent and safe to run once from any prior state (a fresh 0001->0004 chain, or
a database already advanced past this revision).

Revision ID: 0005
Revises: 0004
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# --- Alembic revision identifiers (framework-mandated lowercase/snake names;
# the Ochs PascalCase/camelCase rules do not apply to these module globals).
revision: str = "0005"
down_revision: str | None = "0004"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# --- Module constants (Ochs ALL_UPPERCASE). ---
USERS_TABLE_NAME = "users"
SESSION_VERSION_COLUMN_NAME = "session_version"
# Mirrors app.models.user.INITIAL_SESSION_VERSION: the generation every user
# starts at, applied both as the backfill for existing rows and as the ongoing
# database-level default for inserts that omit the column.
INITIAL_SESSION_VERSION = 1
SESSION_VERSION_SERVER_DEFAULT = str(INITIAL_SESSION_VERSION)


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
    """Add ``users.session_version`` NOT NULL with a server default of 1.

    Idempotent: the column is added only when it is not already present. The
    ``server_default`` backfills existing rows to generation 1 so an already-seeded
    database and a freshly seeded one are identical after this step.
    """
    if not _ColumnExists(USERS_TABLE_NAME, SESSION_VERSION_COLUMN_NAME):
        op.add_column(
            USERS_TABLE_NAME,
            sa.Column(
                SESSION_VERSION_COLUMN_NAME,
                sa.Integer(),
                nullable=False,
                server_default=SESSION_VERSION_SERVER_DEFAULT,
            ),
        )


def downgrade() -> None:
    """Drop ``users.session_version``.

    The reverse of :func:`upgrade`, guarded so it is safe when the column is
    already absent. Tokens no longer carry a revocation anchor after this.
    """
    if _ColumnExists(USERS_TABLE_NAME, SESSION_VERSION_COLUMN_NAME):
        op.drop_column(USERS_TABLE_NAME, SESSION_VERSION_COLUMN_NAME)
