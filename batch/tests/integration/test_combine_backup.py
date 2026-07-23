# Integration tests for the two CardDemo batch finalization jobs, reconciled
# against their legacy JCL (Ochs Rule -- each ported unit references its origin):
#   * batch.jobs.combine_tran.CombineTransactions
#       <- app/jcl/COMBTRAN.jcl + app/ctl/REPROCT.ctl
#       (SORT FIELDS=(TRAN-ID,A) merge of the backup + system transactions into
#        the master transaction ledger).
#   * batch.jobs.backup_tran.BackupTransactions
#       <- app/jcl/TRANBKP.jcl
#       (GDG-generation snapshot of the master transactions; the job runs TWICE
#        in the batch chain per AAP 0.7.6).
#
# Every test below carries a comment naming the exact JCL / control card it
# reconciles against. The legacy app/ tree is REFERENCE-only and is never
# modified.
#
# Synchronous only: the batch layer is blocking (psycopg2). This module never
# imports the async FastAPI request-layer machinery -- no async database-session
# engine, async PostgreSQL driver, async pytest plugin, ASGI HTTP client,
# standard-library event loop, or SQLAlchemy async ORM extension.
"""DB-backed integration tests for the COMBTRAN + TRANBKP finalization jobs.

These tests drive the two finalization jobs against a real PostgreSQL 17 test
database using the shared, rolled-back synchronous ``db_session`` and the
``record_builder`` helper (both provided by the batch conftests), plus pytest's
builtin ``tmp_path`` for the backup output directory. They encode the DB-port
contracts verified directly from the job modules:

* :func:`~batch.jobs.combine_tran.CombineTransactions` is a read-only, idempotent
  finalization / verification pass: it selects every transaction ordered by
  ``tran_id`` ascending (the legacy ``SORT FIELDS=(TRAN-ID,A)`` key) and returns
  the combined count without deleting, re-inserting, or reordering any row.
* :func:`~batch.jobs.backup_tran.BackupTransactions` writes every transaction to
  a fresh generation-suffixed CSV (GDG ``(+1)`` semantics), masking the card
  number to its last four digits and serializing ``tran_amt`` exactly from
  :class:`~decimal.Decimal`; it never empties or deletes the source table.

All monetary literals are exact :class:`~decimal.Decimal` values; floating point
is never used (AAP 0.7.1).
"""

import csv
from decimal import Decimal
from pathlib import Path

import pytest  # noqa: F401  (conventional pytest test-module import per the file spec)
from sqlalchemy import func, select

from app.models import Transaction
from batch.jobs.backup_tran import CSV_HEADER, BackupTransactions
from batch.jobs.combine_tran import CombineTransactions

# --------------------------------------------------------------------------- #
# Module constants (Ochs ALL_UPPERCASE).
# --------------------------------------------------------------------------- #
# A known 16-digit PAN used by the card-masking test. Its last four digits are
# CARD_LAST4; the full PAN must never appear in the backup file, only the masked
# form (which retains CARD_LAST4).
CARD_NUM = "4859452612877065"
CARD_LAST4 = "7065"

# Default 16-digit PAN used by _BuildTransactionsOnCard when a test does not
# request an explicit card number. Deliberately distinct from CARD_NUM and does
# NOT contain CARD_LAST4, so the dedicated masking test is never contaminated by
# an unrelated card. (BuildCard requires a card number -- it has no default.)
DEFAULT_CARD_NUM = "1111222233334444"

# Glob for the generation-suffixed backup files written by BackupTransactions
# (mirrors batch.jobs.backup_tran.BACKUP_GLOB: "transact_bkup_<NNNN>.csv").
BACKUP_GLOB = "transact_bkup_*.csv"

# Distinct 16-character transaction primary keys (TRAN-ID PIC X(16)); used to
# build rows and to exercise the ascending SORT-key ordering.
TRAN_ID_ONE = "0000000000000001"
TRAN_ID_TWO = "0000000000000002"
TRAN_ID_THREE = "0000000000000003"


# --------------------------------------------------------------------------- #
# Module-level helpers (Ochs PascalCase methods; <= 4 parameters each).
# --------------------------------------------------------------------------- #
def _CountTransactions(session):
    """Count rows in the transactions table within the current transaction.

    Args:
        session: The open, rolled-back synchronous test session.

    Returns:
        The number of rows currently visible in the ``transactions`` table.
    """
    return session.execute(select(func.count()).select_from(Transaction)).scalar_one()


def _BuildTransactionsOnCard(recordBuilder, tranSpecs, cardNum=None):
    """Build one account + one card and one transaction per spec dict.

    Adapts to the concrete ``RecordBuilder`` API from the integration conftest,
    where ``BuildCard(cardNum, acctId, ...)`` and
    ``BuildPendingTransaction(tranId, cardNum, tranAmt, ...)`` both take required
    positional arguments. When ``cardNum`` is omitted the shared
    :data:`DEFAULT_CARD_NUM` is used so the transaction card foreign key is always
    satisfied.

    Args:
        recordBuilder: The ``RecordBuilder`` bound to the rolled-back session.
        tranSpecs: A list of dicts, each with keys ``"tran_id"`` and
            ``"tran_amt"`` (an exact :class:`~decimal.Decimal`).
        cardNum: Optional explicit 16-digit card number; defaults to
            :data:`DEFAULT_CARD_NUM`.

    Returns:
        A ``(card, builtTransactions)`` tuple: the created card row and the list
        of created transaction rows in build order.
    """
    account = recordBuilder.BuildAccount()
    effectiveCardNum = cardNum if cardNum is not None else DEFAULT_CARD_NUM
    card = recordBuilder.BuildCard(effectiveCardNum, account.acct_id)
    builtTransactions = []
    for spec in tranSpecs:
        builtTransaction = recordBuilder.BuildPendingTransaction(
            spec["tran_id"],
            effectiveCardNum,
            spec["tran_amt"],
        )
        builtTransactions.append(builtTransaction)
    return card, builtTransactions


def _ReadBackupRows(tmpPath):
    """Read the single generation backup file into ``(header, rows)``.

    Locates the one ``transact_bkup_*.csv`` file in ``tmpPath`` and parses it with
    :class:`csv.DictReader`, so a test can reconcile the backup FIELD-BY-FIELD
    against the source rows rather than merely asserting a substring is present
    (QA finding M-14). Returns the raw header list (to assert exact column order)
    and the rows as ``dict`` objects keyed by column name.

    Args:
        tmpPath: The ``tmp_path`` directory the backup was written to.

    Returns:
        A ``(header, rows)`` tuple: the ordered list of column names and the list
        of row dicts (one per transaction, in file order).
    """
    backupFiles = list(Path(tmpPath).glob(BACKUP_GLOB))
    assert len(backupFiles) == 1
    with backupFiles[0].open(newline="", encoding="utf-8") as backupFile:
        reader = csv.reader(backupFile)
        header = next(reader)
        rows = [dict(zip(header, record)) for record in reader]
    return header, rows


# --------------------------------------------------------------------------- #
# CombineTransactions <- app/jcl/COMBTRAN.jcl + app/ctl/REPROCT.ctl
# --------------------------------------------------------------------------- #
def test_combine_returns_total_count_out_of_order(db_session, record_builder):
    # COMBTRAN.jcl / REPROCT.ctl: SORT FIELDS=(TRAN-ID,A) merges the backup +
    # system transactions in ascending TRAN-ID order. Rows are built OUT of
    # tran_id order so the ordered finalization pass is genuinely exercised.
    _BuildTransactionsOnCard(
        record_builder,
        [
            {"tran_id": TRAN_ID_THREE, "tran_amt": Decimal("30.00")},
            {"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("10.00")},
            {"tran_id": TRAN_ID_TWO, "tran_amt": Decimal("20.00")},
        ],
    )
    assert CombineTransactions(db_session) == 3


def test_combine_does_not_mutate_table(db_session, record_builder):
    # COMBTRAN.jcl: in the DB port there is no separate BKUP/SYSTRAN dataset to
    # physically merge, so the job is a read-only verification pass and must never
    # delete, re-insert, or reorder rows.
    _BuildTransactionsOnCard(
        record_builder,
        [
            {"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("10.00")},
            {"tran_id": TRAN_ID_TWO, "tran_amt": Decimal("20.00")},
            {"tran_id": TRAN_ID_THREE, "tran_amt": Decimal("30.00")},
        ],
    )
    idsBefore = set(db_session.execute(select(Transaction.tran_id)).scalars().all())
    countBefore = _CountTransactions(db_session)

    CombineTransactions(db_session)
    db_session.expire_all()

    countAfter = _CountTransactions(db_session)
    assert countAfter == countBefore
    assert countAfter == 3
    idsAfter = set(db_session.execute(select(Transaction.tran_id)).scalars().all())
    assert idsAfter == idsBefore
    assert db_session.get(Transaction, TRAN_ID_ONE).tran_amt == Decimal("10.00")


def test_combine_idempotent_rerun(db_session, record_builder):
    # COMBTRAN.jcl: the finalization pass reads/verifies only, so re-running it
    # yields an identical count and leaves the table unchanged.
    _BuildTransactionsOnCard(
        record_builder,
        [
            {"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("10.00")},
            {"tran_id": TRAN_ID_TWO, "tran_amt": Decimal("20.00")},
            {"tran_id": TRAN_ID_THREE, "tran_amt": Decimal("30.00")},
        ],
    )
    firstRun = CombineTransactions(db_session)
    secondRun = CombineTransactions(db_session)
    assert firstRun == 3
    assert secondRun == 3

    db_session.expire_all()
    assert _CountTransactions(db_session) == 3


def test_combine_empty_table_returns_zero(db_session):
    # COMBTRAN.jcl: an empty master (no BKUP/SYSTRAN input rows) combines to a
    # zero count. db_session starts EMPTY, so no rows are built here.
    assert CombineTransactions(db_session) == 0


# --------------------------------------------------------------------------- #
# BackupTransactions <- app/jcl/TRANBKP.jcl
# --------------------------------------------------------------------------- #
def test_backup_writes_generation_suffixed_csv(db_session, record_builder, tmp_path):
    # TRANBKP.jcl: REPRO the master into a NEW GDG generation TRANSACT.BKUP(+1).
    # The DB port writes a single generation-suffixed CSV per run.
    _BuildTransactionsOnCard(
        record_builder,
        [
            {"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("10.00")},
            {"tran_id": TRAN_ID_TWO, "tran_amt": Decimal("20.00")},
            {"tran_id": TRAN_ID_THREE, "tran_amt": Decimal("30.00")},
        ],
    )
    writtenCount = BackupTransactions(db_session, tmp_path)
    assert writtenCount == 3

    backupFiles = list(tmp_path.glob(BACKUP_GLOB))
    assert len(backupFiles) == 1
    assert backupFiles[0].read_text().strip() != ""


def test_backup_does_not_empty_table(db_session, record_builder, tmp_path):
    # TRANBKP.jcl: the DB port intentionally OMITS the legacy VSAM DELETE/DEFINE
    # (Alembic owns DDL), so the backup snapshot must never empty or delete the
    # source transactions table. <- KEY parity assertion.
    _BuildTransactionsOnCard(
        record_builder,
        [
            {"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("10.00")},
            {"tran_id": TRAN_ID_TWO, "tran_amt": Decimal("20.00")},
            {"tran_id": TRAN_ID_THREE, "tran_amt": Decimal("30.00")},
        ],
    )
    BackupTransactions(db_session, tmp_path)
    db_session.expire_all()

    assert _CountTransactions(db_session) == 3


def test_backup_masks_card_number(db_session, record_builder, tmp_path):
    # TRANBKP.jcl + AAP 0.7.8 (sensitive-data masking): the full PAN must never be
    # written to the backup; only the last four digits are retained.
    _BuildTransactionsOnCard(
        record_builder,
        [{"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("100.00")}],
        cardNum=CARD_NUM,
    )
    BackupTransactions(db_session, tmp_path)

    backupFiles = list(tmp_path.glob(BACKUP_GLOB))
    assert len(backupFiles) == 1
    content = backupFiles[0].read_text()
    assert CARD_NUM not in content
    assert CARD_LAST4 in content


def test_backup_serializes_decimal_amount(db_session, record_builder, tmp_path):
    # TRANBKP.jcl: tran_amt is serialized exactly from Decimal, never via float
    # (no artifact such as 504.7700000001).
    _BuildTransactionsOnCard(
        record_builder,
        [{"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("504.77")}],
    )
    BackupTransactions(db_session, tmp_path)

    backupFiles = list(tmp_path.glob(BACKUP_GLOB))
    content = backupFiles[0].read_text()
    assert "504.77" in content


def test_backup_rerun_keeps_table_intact(db_session, record_builder, tmp_path):
    # TRANBKP.jcl: the job runs TWICE in the batch chain (AAP 0.7.6) -- once
    # before posting and once after interest. Both runs snapshot every row and
    # neither empties the source table.
    _BuildTransactionsOnCard(
        record_builder,
        [
            {"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("10.00")},
            {"tran_id": TRAN_ID_TWO, "tran_amt": Decimal("20.00")},
            {"tran_id": TRAN_ID_THREE, "tran_amt": Decimal("30.00")},
        ],
    )
    firstRun = BackupTransactions(db_session, tmp_path)
    secondRun = BackupTransactions(db_session, tmp_path)
    assert firstRun == 3
    assert secondRun == 3


def test_backup_row_field_parity_and_masking(db_session, record_builder, tmp_path):
    # QA finding M-14: the backup is a masked, non-restorable SNAPSHOT REPORT, so
    # its correctness guarantee is FIELD-LEVEL content parity with the live table
    # (not byte-for-byte legacy restore). This asserts the exact CSV header order
    # and every field of a fully-known transaction row -- with the PAN masked to
    # its last four digits -- rather than merely checking a substring is present.
    _BuildTransactionsOnCard(
        record_builder,
        [{"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("123.45")}],
        cardNum=CARD_NUM,
    )
    BackupTransactions(db_session, tmp_path)

    header, rows = _ReadBackupRows(tmp_path)
    assert header == list(CSV_HEADER)          # exact column set AND order
    assert len(rows) == 1
    row = rows[0]
    # Field-by-field parity with the staged source row (RecordBuilder defaults).
    assert row["tran_id"] == TRAN_ID_ONE
    assert row["tran_type_cd"] == "01"
    assert row["tran_cat_cd"] == "0001"
    assert row["tran_source"] == "POS"
    assert row["tran_desc"] == "TEST TRANSACTION"
    assert row["tran_amt"] == "123.45"         # exact Decimal scale, never float
    assert row["merchant_id"] == "000000001"
    assert row["merchant_name"] == "TEST MERCHANT"
    assert row["merchant_city"] == "TEST CITY"
    assert row["merchant_zip"] == "00000"
    assert row["status"] == "PENDING"
    # PAN masked to last four (AAP 0.7.8): 12 mask chars + last 4, full PAN absent.
    assert row["card_num"] == "*" * (len(CARD_NUM) - 4) + CARD_LAST4
    assert CARD_NUM not in row["card_num"]
    # orig_ts serialized as ISO-8601. The transactions.orig_ts column is
    # TIMESTAMPTZ, so the value round-trips timezone-aware (UTC "+00:00"); the
    # backup's _FormatTimestamp mirrors this via datetime.isoformat(). Assert
    # exact parity against the live source row (not a hand-built naive literal),
    # which is precisely the field-level parity guarantee this test documents.
    sourceTran = db_session.get(Transaction, TRAN_ID_ONE)
    assert row["orig_ts"] == sourceTran.orig_ts.isoformat()
    # proc_ts is None on a PENDING row and collapses to an empty string.
    assert sourceTran.proc_ts is None
    assert row["proc_ts"] == ""


def test_backup_excludes_full_pan_cvv_and_ssn(db_session, record_builder, tmp_path):
    # QA finding M-14: the snapshot must never leak sensitive data. It is scoped
    # to the transaction record only -- it never joins the customer or exposes a
    # CVV (structurally impossible after C-03) -- so a customer SSN staged in the
    # same graph, the full PAN, and any CVV-like literal are all absent from the
    # backup. A customer with a known SSN is staged to make the SSN exclusion a
    # real (not vacuous) assertion.
    account = record_builder.BuildAccount()
    record_builder.BuildCustomer(
        overrides={"cust_id": "000000123", "ssn": "123456789"}
    )
    record_builder.BuildCard(CARD_NUM, account.acct_id)
    # Link the customer to this card/account so its SSN is genuinely reachable in
    # the graph -- making the SSN-exclusion assertion non-vacuous.
    record_builder.BuildXref(CARD_NUM, "000000123", account.acct_id)
    record_builder.BuildPendingTransaction(TRAN_ID_ONE, CARD_NUM, Decimal("100.00"))

    BackupTransactions(db_session, tmp_path)

    backupFiles = list(tmp_path.glob(BACKUP_GLOB))
    assert len(backupFiles) == 1
    content = backupFiles[0].read_text(encoding="utf-8")
    assert CARD_NUM not in content        # full PAN never written (masked)
    assert "123456789" not in content     # customer SSN never in a transaction backup
    assert "747" not in content           # a representative CVV literal is absent

    # Sanity: the single staged transaction still exists after the backup (the
    # snapshot never empties or deletes the source table) and one file was written.
    db_session.expire_all()
    assert _CountTransactions(db_session) == 1
    assert len(list(tmp_path.glob(BACKUP_GLOB))) == 1
