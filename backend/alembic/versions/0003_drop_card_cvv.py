"""0003 drop card CVV column — enforce the AAP prohibition on CVV retention.

QA finding C-03 (CRITICAL): the legacy card verification value (``CARD-CVV-CD``
from copybook ``CVACT02Y``) must NEVER be retained in the modern datastore
(AAP 0.7.8). The schema authority (0001) and seed loader (0002) have been
corrected so a fresh ``alembic upgrade head`` never creates or populates a
``cvv_cd`` column. This migration is the explicit, idempotent cleanup step for
any database that was previously migrated at revision 0001 while it still
declared the sensitive column: it DROPs ``cards.cvv_cd`` if — and only if — the
column still exists, so:

  * on a fresh database (0001 already omits the column) it is a safe no-op, and
  * on a previously-migrated database it removes the retained CVV values,
    destroying them in place.

The drop is expressed with a dialect-agnostic information_schema probe followed
by ``op.drop_column`` so the migration is safe to run exactly once in any state.
There is intentionally no data preservation: the values being removed are
prohibited from existing at all, so recovering them is not a goal.

Revision ID: 0003
Revises: 0002
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# --- Alembic revision identifiers (framework-mandated lowercase/snake names;
# the Ochs PascalCase/camelCase rules do not apply to these module globals).
revision: str = "0003"
down_revision: str | None = "0002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# --- Module constants (Ochs ALL_UPPERCASE). ---
CARDS_TABLE_NAME = "cards"
CVV_COLUMN_NAME = "cvv_cd"


def _CvvColumnExists() -> bool:
    """Report whether ``cards.cvv_cd`` currently exists on the bound database.

    Uses the live SQLAlchemy inspector on the migration's bind so the check is
    dialect-agnostic and reflects the ACTUAL database state (not ORM metadata).

    Returns:
        ``True`` when the ``cards`` table has a ``cvv_cd`` column, else ``False``.
    """
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    if CARDS_TABLE_NAME not in inspector.get_table_names():
        return False
    columnNames = {column["name"] for column in inspector.get_columns(CARDS_TABLE_NAME)}
    return CVV_COLUMN_NAME in columnNames


def upgrade() -> None:
    """Drop the sensitive ``cards.cvv_cd`` column if it still exists.

    Idempotent: on a database whose schema already omits the column (a fresh
    install created by the corrected 0001) this is a no-op; on a database
    previously migrated with the column it removes it — and the retained values
    with it.
    """
    if _CvvColumnExists():
        op.drop_column(CARDS_TABLE_NAME, CVV_COLUMN_NAME)


def downgrade() -> None:
    """Intentionally irreversible: re-adding a CVV column is prohibited.

    Recreating ``cards.cvv_cd`` would reintroduce the exact sensitive-data
    retention the AAP forbids (AAP 0.7.8), so the downgrade deliberately does
    nothing. The column must never be re-created by a migration.
    """
    # No-op by design: never re-create a CVV column (AAP 0.7.8 / QA C-03).
    return None
