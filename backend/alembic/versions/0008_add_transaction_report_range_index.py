"""0008 add transactions report-range composite index (status + effective date).

QA finding H1 / MINOR-5 (report-range Seq Scan + external-disk sort): the
transaction report (``CORPT00C`` -> ``TransactionRepository.ListPostedInDateRange``)
selects POSTED transactions whose effective date -- the UTC calendar date of
``COALESCE(proc_ts, orig_ts)`` -- falls in an inclusive window, then orders by
``tran_id``. Before this migration the ``transactions`` table carried only
``pk_transactions`` (tran_id) and ``ix_transactions_card_num`` (card_num), so
that query had no usable index: PostgreSQL fell back to a full sequential scan of
the table plus an external (on-disk) sort, whose cost grows with the whole table
rather than with the selected window.

This migration adds a composite index whose columns match the query's predicate
exactly, so a selective date window is served by an index range scan instead of a
full scan (and a small, in-memory sort rather than an external one):

    ix_transactions_status_effdate
        ON transactions (
            status,
            (CAST(timezone('UTC', COALESCE(proc_ts, orig_ts)) AS date))
        )

Immutability / result-preservation note (AAP 0.8.1 / Ochs behavior preservation):
    The effective date is written as ``CAST(timezone('UTC', COALESCE(proc_ts,
    orig_ts)) AS date)`` -- explicitly the UTC calendar date -- NOT a bare
    ``COALESCE(proc_ts, orig_ts)::date``. A bare ``timestamptz::date`` cast is
    only STABLE (it resolves under the session time zone), so PostgreSQL refuses
    to build a functional index on it ("functions in index expression must be
    marked IMMUTABLE"). ``timezone('UTC', timestamptz)`` returns a plain
    ``timestamp`` and, with a constant zone, is IMMUTABLE, and ``timestamp::date``
    is IMMUTABLE, so the whole expression can be indexed. Under this deployment
    -- server and every connection run at ``Etc/UTC`` -- the UTC-explicit
    expression yields byte-identical dates to the former ``::date`` cast for every
    row (verified: zero mismatches across the seeded ledger), so the matching
    repository change is purely an optimization and preserves the exact report
    result. The leading ``status`` column lets the same index also satisfy the
    ``status = 'POSTED'`` equality that scopes the report to the posted ledger.

The step is idempotent (``CREATE INDEX IF NOT EXISTS`` / ``DROP INDEX IF
EXISTS``) so it is safe to run once from any prior state and is fully reversible.

Revision ID: 0008
Revises: 0007
"""

from collections.abc import Sequence

from alembic import op

# --- Alembic revision identifiers (framework-mandated lowercase/snake names;
# the Ochs PascalCase/camelCase rules do not apply to these module globals).
revision: str = "0008"
down_revision: str | None = "0007"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# --- Module constants (Ochs ALL_UPPERCASE). ---
TRANSACTIONS_TABLE_NAME = "transactions"
REPORT_RANGE_INDEX_NAME = "ix_transactions_status_effdate"

# The composite index expression. The second element is the IMMUTABLE UTC
# effective-date expression, written textually identical to the compiled
# ``TransactionRepository.ListPostedInDateRange`` predicate so the planner
# matches the query to this index.
CREATE_REPORT_RANGE_INDEX_SQL = (
    f"CREATE INDEX IF NOT EXISTS {REPORT_RANGE_INDEX_NAME} "
    f"ON {TRANSACTIONS_TABLE_NAME} "
    "(status, (CAST(timezone('UTC', COALESCE(proc_ts, orig_ts)) AS date)))"
)
DROP_REPORT_RANGE_INDEX_SQL = f"DROP INDEX IF EXISTS {REPORT_RANGE_INDEX_NAME}"


def upgrade() -> None:
    """Create the composite report-range index if it is not already present.

    Idempotent via ``CREATE INDEX IF NOT EXISTS``: safe to run once from any
    prior state. The index backs the posted-plus-date-window report query,
    replacing a full sequential scan and external-disk sort with an index range
    scan (QA finding H1 / MINOR-5).
    """
    op.execute(CREATE_REPORT_RANGE_INDEX_SQL)


def downgrade() -> None:
    """Drop the composite report-range index.

    The exact reverse of :func:`upgrade`, guarded with ``DROP INDEX IF EXISTS``
    so it is safe when the index is already absent. The report query still
    returns identical rows afterward; only its execution plan reverts to the
    pre-index sequential scan.
    """
    op.execute(DROP_REPORT_RANGE_INDEX_SQL)
