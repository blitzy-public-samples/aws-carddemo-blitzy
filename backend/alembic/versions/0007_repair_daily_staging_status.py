"""0007 repair mislabeled daily-staging transaction status (POSTED -> PENDING).

QA finding C02 (CRITICAL): migration ``0002`` seeded the CVTRA06Y daily
transactions (``app/data/ASCII/dailytran.txt``) into the ``transactions`` table
with ``status = 'POSTED'`` even though every row carries a BLANK ``proc_ts``.
``dailytran.txt`` is the legacy *daily-transaction* file -- the INPUT to posting
(AAP 0.7.5) -- and the posting job (CBTRN02C / POSTTRAN) is what stamps
``proc_ts`` and flips the row to POSTED (AAP 0.7.3). A POSTED row with a NULL
``proc_ts`` is therefore incoherent: it represents a daily-staging row that was
mislabeled POSTED, bypassing the POSTTRAN lifecycle.

``0002`` now seeds these rows as PENDING for fresh databases. This migration
repairs any database already advanced past ``0002`` with the old POSTED value,
using ``proc_ts IS NULL`` as the precise discriminator between a genuinely
posted transaction (always stamped with a processing timestamp) and a
mislabeled daily-staging row.

Safety / reversibility:
    * ``upgrade`` flips ``POSTED`` -> ``PENDING`` ONLY for rows whose
      ``proc_ts`` is NULL, so it never touches a genuinely posted transaction
      (which always has a stamped ``proc_ts``). It is idempotent: on a fresh
      database already seeded PENDING by the updated ``0002`` there are no such
      rows and the statement is a no-op.
    * ``downgrade`` reverses exactly those rows (``PENDING`` -> ``POSTED`` where
      ``proc_ts`` is NULL), restoring the prior state.

Revision ID: 0007
Revises: 0006
"""

from collections.abc import Sequence

from alembic import op

# --- Alembic revision identifiers (framework-mandated lowercase/snake names;
# the Ochs PascalCase/camelCase rules do not apply to these module globals).
revision: str = "0007"
down_revision: str | None = "0006"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# --- Module constants (Ochs ALL_UPPERCASE). ---
POSTED_STATUS = "POSTED"
PENDING_STATUS = "PENDING"

# proc_ts IS NULL precisely identifies a mislabeled daily-staging row: a truly
# posted transaction is always stamped with a processing timestamp by POSTTRAN.
REPAIR_TO_PENDING_SQL = (
    f"UPDATE transactions SET status = '{PENDING_STATUS}' "
    f"WHERE status = '{POSTED_STATUS}' AND proc_ts IS NULL"
)
REVERT_TO_POSTED_SQL = (
    f"UPDATE transactions SET status = '{POSTED_STATUS}' "
    f"WHERE status = '{PENDING_STATUS}' AND proc_ts IS NULL"
)


def upgrade() -> None:
    """Flip mislabeled daily-staging rows (POSTED with NULL proc_ts) to PENDING.

    Idempotent and precise: only rows lacking a processing timestamp are
    affected, so genuinely posted transactions are never altered.
    """
    op.execute(REPAIR_TO_PENDING_SQL)


def downgrade() -> None:
    """Reverse the repair: restore PENDING rows with NULL proc_ts to POSTED."""
    op.execute(REVERT_TO_POSTED_SQL)
