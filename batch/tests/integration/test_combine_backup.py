# Integration tests for the two CardDemo batch finalization jobs, reconciled
# against their legacy JCL (Ochs Rule -- each ported unit references its origin):
#   * batch.jobs.combine_tran.CombineTransactions
#       <- app/jcl/COMBTRAN.jcl + app/ctl/REPROCT.ctl
#       (SORT FIELDS=(TRAN-ID,A) merge of the POSTED backup + system transactions
#        into a single combined master ledger FILE; QA findings M16 + M17).
#   * batch.jobs.backup_tran.BackupTransactions / RestoreTransactions
#       <- app/jcl/TRANBKP.jcl
#       (GDG-generation, restore-capable, full-fidelity snapshot of the POSTED
#        master transactions; the job runs TWICE in the batch chain per AAP 0.7.6;
#        QA findings M16 + M18 + M13).
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
builtin ``tmp_path`` for the output directory. They encode the DB-port contracts
verified directly from the job modules, INCLUDING the QA remediations:

* :func:`~batch.jobs.combine_tran.CombineTransactions` performs a real SORT/REPRO
  merge (QA finding M17): it selects the POSTED transactions only (QA finding
  M16), orders them ascending by ``tran_id`` (the legacy ``SORT FIELDS=(TRAN-ID,A)``
  key), materializes them to a single combined ledger CSV, reconciles the written
  count against ``COUNT(*) WHERE status = POSTED``, and never mutates the table.
* :func:`~batch.jobs.backup_tran.BackupTransactions` writes every POSTED
  transaction (QA finding M16) to a fresh generation-suffixed CSV (GDG ``(+1)``
  semantics), FULL-FIDELITY including the full ``card_num`` so the backup is
  restore-capable (QA finding M18), secured to owner-only ``0600`` in an
  owner-only ``0700`` directory (QA finding M13); it never empties the table.
* :func:`~batch.jobs.backup_tran.RestoreTransactions` idempotently replays a
  backup file back into the table, reconstructing the exact rows (QA finding M18).

All monetary literals are exact :class:`~decimal.Decimal` values; floating point
is never used (AAP 0.7.1).
"""

import csv
import os
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

import pytest  # noqa: F401  (conventional pytest test-module import per the file spec)
from sqlalchemy import delete, func, select

from app.models import Transaction
from app.models.transaction import STATUS_PENDING, STATUS_POSTED, STATUS_REJECTED
from batch.jobs.backup_tran import (
    BACKUP_GLOB,
    CSV_HEADER,
    BackupTransactions,
    RestoreTransactions,
)
from batch.jobs.combine_tran import COMBINED_FILE_NAME, CombineTransactions
from batch.jobs.output_safety import SECURE_DIR_MODE, SECURE_FILE_MODE

# --------------------------------------------------------------------------- #
# Module constants (Ochs ALL_UPPERCASE).
# --------------------------------------------------------------------------- #
# A known 16-digit PAN used by the full-PAN / restore tests. Its last four digits
# are CARD_LAST4. Because the backup is now RESTORE-CAPABLE (QA finding M18), the
# FULL PAN is expected in the backup file (secured by 0600 permissions, M13),
# not a masked form.
CARD_NUM = "4859452612877065"
CARD_LAST4 = "7065"

# Default 16-digit PAN used by _BuildTransactionsOnCard when a test does not
# request an explicit card number. Deliberately distinct from CARD_NUM.
# (BuildCard requires a card number -- it has no default.)
DEFAULT_CARD_NUM = "1111222233334444"

# Distinct 16-character transaction primary keys (TRAN-ID PIC X(16)); used to
# build rows and to exercise the ascending SORT-key ordering.
TRAN_ID_ONE = "0000000000000001"
TRAN_ID_TWO = "0000000000000002"
TRAN_ID_THREE = "0000000000000003"

# A representative CVV literal that must never appear in a transaction backup
# (the CVV is not part of the transaction record; QA finding M18 / AAP 0.7.8).
CVV_LITERAL = "747"

# A known customer SSN staged to prove the SSN-exclusion assertion is non-vacuous
# (the SSN lives on the customers table, never in a transaction backup).
CUSTOMER_SSN = "123456789"


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


def _CountPosted(session):
    """Count POSTED rows in the transactions table within the current transaction.

    Args:
        session: The open, rolled-back synchronous test session.

    Returns:
        The number of POSTED rows currently visible in the ``transactions`` table.
    """
    return session.execute(
        select(func.count()).select_from(Transaction).where(Transaction.status == STATUS_POSTED)
    ).scalar_one()


def _BuildTransactionsOnCard(recordBuilder, tranSpecs, cardNum=None):
    """Build one account + one card and one transaction per spec dict.

    Adapts to the concrete ``RecordBuilder`` API from the integration conftest,
    where ``BuildCard(cardNum, acctId, ...)`` and
    ``BuildPendingTransaction(tranId, cardNum, tranAmt, overrides=...)`` take
    required positional arguments. Each spec may carry an optional ``"status"``
    (defaulting to :data:`STATUS_POSTED`, since the finalization jobs operate on
    the POSTED master) and an optional ``"proc_ts"``; both are applied through the
    builder's ``overrides``. When ``cardNum`` is omitted the shared
    :data:`DEFAULT_CARD_NUM` is used so the transaction card foreign key is always
    satisfied.

    Args:
        recordBuilder: The ``RecordBuilder`` bound to the rolled-back session.
        tranSpecs: A list of dicts, each with keys ``"tran_id"`` and
            ``"tran_amt"`` (an exact :class:`~decimal.Decimal`), plus optional
            ``"status"`` and ``"proc_ts"``.
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
        overrides = {"status": spec.get("status", STATUS_POSTED)}
        if "proc_ts" in spec:
            overrides["proc_ts"] = spec["proc_ts"]
        builtTransaction = recordBuilder.BuildPendingTransaction(
            spec["tran_id"],
            effectiveCardNum,
            spec["tran_amt"],
            overrides=overrides,
        )
        builtTransactions.append(builtTransaction)
    return card, builtTransactions


def _ReadCsvRows(csvPath):
    """Parse a batch CSV into ``(header, rows)`` for field-by-field reconciliation.

    Reads ``csvPath`` with :class:`csv.reader`, returning the raw header list (to
    assert exact column order) and the data rows as ``dict`` objects keyed by
    column name (so a test reconciles fields rather than substrings).

    Args:
        csvPath: The path of the CSV file to parse.

    Returns:
        A ``(header, rows)`` tuple: the ordered list of column names and the list
        of row dicts (one per data line, in file order).
    """
    with Path(csvPath).open(newline="", encoding="utf-8") as csvFile:
        reader = csv.reader(csvFile)
        header = next(reader)
        rows = [dict(zip(header, record)) for record in reader]
    return header, rows


def _ReadBackupRows(tmpPath):
    """Read the single generation backup file into ``(header, rows)``.

    Locates the one ``transact_bkup_*.csv`` file in ``tmpPath`` and parses it,
    so a test can reconcile the backup FIELD-BY-FIELD against the source rows.

    Args:
        tmpPath: The ``tmp_path`` directory the backup was written to.

    Returns:
        A ``(header, rows)`` tuple (see :func:`_ReadCsvRows`).
    """
    backupFiles = list(Path(tmpPath).glob(BACKUP_GLOB))
    assert len(backupFiles) == 1
    return _ReadCsvRows(backupFiles[0])


# --------------------------------------------------------------------------- #
# CombineTransactions <- app/jcl/COMBTRAN.jcl + app/ctl/REPROCT.ctl
# --------------------------------------------------------------------------- #
def test_combine_returns_posted_count_out_of_order(db_session, record_builder, tmp_path):
    # COMBTRAN.jcl / REPROCT.ctl: SORT FIELDS=(TRAN-ID,A) merges the POSTED backup
    # + system transactions in ascending TRAN-ID order. Rows are built OUT of
    # tran_id order so the ordered merge is genuinely exercised.
    _BuildTransactionsOnCard(
        record_builder,
        [
            {"tran_id": TRAN_ID_THREE, "tran_amt": Decimal("30.00")},
            {"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("10.00")},
            {"tran_id": TRAN_ID_TWO, "tran_amt": Decimal("20.00")},
        ],
    )
    assert CombineTransactions(db_session, tmp_path) == 3
    # A real combined ledger file is produced (QA finding M17), not a no-op count.
    assert (tmp_path / COMBINED_FILE_NAME).exists()


def test_combine_writes_sorted_posted_ledger_file(db_session, record_builder, tmp_path):
    # QA finding M17 + M16: COMBTRAN materializes a REAL combined master-ledger
    # file of the POSTED transactions ordered ascending by tran_id (the legacy
    # SORT FIELDS=(TRAN-ID,A) key), not a documented count/no-op.
    _BuildTransactionsOnCard(
        record_builder,
        [
            {"tran_id": TRAN_ID_TWO, "tran_amt": Decimal("20.00")},
            {"tran_id": TRAN_ID_THREE, "tran_amt": Decimal("30.00")},
            {"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("10.00")},
        ],
    )
    combinedCount = CombineTransactions(db_session, tmp_path)
    assert combinedCount == 3

    header, rows = _ReadCsvRows(tmp_path / COMBINED_FILE_NAME)
    assert header == list(CSV_HEADER)                      # exact column set AND order
    # Rows written ascending by tran_id (SORT FIELDS=(TRAN-ID,A) parity).
    assert [row["tran_id"] for row in rows] == [TRAN_ID_ONE, TRAN_ID_TWO, TRAN_ID_THREE]
    # Exact Decimal scale preserved end to end (never float).
    assert [row["tran_amt"] for row in rows] == ["10.00", "20.00", "30.00"]


def test_combine_excludes_pending_and_rejected(db_session, record_builder, tmp_path):
    # QA finding M16: the combined MASTER ledger must contain POSTED rows only;
    # PENDING daily-staging rows and validation-REJECTED rows are excluded (they
    # never belonged in the legacy posted TRANSACT master that COMBTRAN merged).
    _BuildTransactionsOnCard(
        record_builder,
        [
            {"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("10.00"), "status": STATUS_POSTED},
            {"tran_id": TRAN_ID_TWO, "tran_amt": Decimal("20.00"), "status": STATUS_PENDING},
            {"tran_id": TRAN_ID_THREE, "tran_amt": Decimal("30.00"), "status": STATUS_REJECTED},
        ],
    )
    combinedCount = CombineTransactions(db_session, tmp_path)
    assert combinedCount == 1                              # only the POSTED row

    _, rows = _ReadCsvRows(tmp_path / COMBINED_FILE_NAME)
    assert [row["tran_id"] for row in rows] == [TRAN_ID_ONE]
    assert all(row["status"] == STATUS_POSTED for row in rows)


def test_combine_does_not_mutate_table(db_session, record_builder, tmp_path):
    # COMBTRAN.jcl: the combine pass is read-only WITH RESPECT TO THE DATABASE --
    # it materializes a file but must never delete, re-insert, or reorder rows.
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

    CombineTransactions(db_session, tmp_path)
    db_session.expire_all()

    countAfter = _CountTransactions(db_session)
    assert countAfter == countBefore
    assert countAfter == 3
    idsAfter = set(db_session.execute(select(Transaction.tran_id)).scalars().all())
    assert idsAfter == idsBefore
    assert db_session.get(Transaction, TRAN_ID_ONE).tran_amt == Decimal("10.00")


def test_combine_idempotent_rerun(db_session, record_builder, tmp_path):
    # COMBTRAN.jcl: the finalization pass reads/verifies and deterministically
    # rewrites the same combined file, so re-running it yields an identical count
    # and leaves the table unchanged (a single combined file, atomically rewritten).
    _BuildTransactionsOnCard(
        record_builder,
        [
            {"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("10.00")},
            {"tran_id": TRAN_ID_TWO, "tran_amt": Decimal("20.00")},
            {"tran_id": TRAN_ID_THREE, "tran_amt": Decimal("30.00")},
        ],
    )
    firstRun = CombineTransactions(db_session, tmp_path)
    secondRun = CombineTransactions(db_session, tmp_path)
    assert firstRun == 3
    assert secondRun == 3
    # Deterministic single combined file (atomic overwrite), not a new generation.
    assert len(list(tmp_path.glob("transact_combined*.csv"))) == 1

    db_session.expire_all()
    assert _CountTransactions(db_session) == 3


def test_combine_empty_table_returns_zero(db_session, tmp_path):
    # COMBTRAN.jcl: an empty master (no POSTED input rows) combines to a zero
    # count with a header-only file. db_session starts EMPTY, so no rows are built.
    assert CombineTransactions(db_session, tmp_path) == 0
    header, rows = _ReadCsvRows(tmp_path / COMBINED_FILE_NAME)
    assert header == list(CSV_HEADER)
    assert rows == []


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


def test_backup_writes_full_pan_for_restore(db_session, record_builder, tmp_path):
    # QA finding M18: the backup is now RESTORE-CAPABLE, so the FULL PAN is written
    # (not masked) -- masking would make the source rows impossible to reconstruct.
    # Sensitive exposure is prevented by 0600 file permissions (M13), not masking.
    _BuildTransactionsOnCard(
        record_builder,
        [{"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("100.00")}],
        cardNum=CARD_NUM,
    )
    BackupTransactions(db_session, tmp_path)

    header, rows = _ReadBackupRows(tmp_path)
    assert len(rows) == 1
    assert rows[0]["card_num"] == CARD_NUM                 # full PAN, restore-capable
    assert "*" not in rows[0]["card_num"]                  # not masked


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
    # before posting and once after interest. Both runs snapshot every POSTED row
    # into a distinct generation and neither empties the source table.
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
    # Two distinct generations preserved (GDG (+1) never overwrites a prior one).
    assert len(list(tmp_path.glob(BACKUP_GLOB))) == 2


def test_backup_row_field_parity_full_pan(db_session, record_builder, tmp_path):
    # QA finding M18: the backup is a full-fidelity, restore-capable copy, so its
    # correctness guarantee is FIELD-LEVEL parity with the live table INCLUDING the
    # full card_num. This asserts the exact CSV header order and every field of a
    # fully-known transaction row (PAN unmasked for restore).
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
    assert row["status"] == "POSTED"
    # Full PAN preserved (QA finding M18): restore-capable, not masked.
    assert row["card_num"] == CARD_NUM
    # orig_ts serialized as ISO-8601 and round-trips exactly against the live row.
    sourceTran = db_session.get(Transaction, TRAN_ID_ONE)
    assert row["orig_ts"] == sourceTran.orig_ts.isoformat()
    # proc_ts is None on this row and collapses to an empty string.
    assert sourceTran.proc_ts is None
    assert row["proc_ts"] == ""


def test_backup_excludes_pending_and_rejected(db_session, record_builder, tmp_path):
    # QA finding M16: the backup snapshots the POSTED master ONLY; PENDING
    # daily-staging rows and validation-REJECTED rows are excluded.
    _BuildTransactionsOnCard(
        record_builder,
        [
            {"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("10.00"), "status": STATUS_POSTED},
            {"tran_id": TRAN_ID_TWO, "tran_amt": Decimal("20.00"), "status": STATUS_PENDING},
            {"tran_id": TRAN_ID_THREE, "tran_amt": Decimal("30.00"), "status": STATUS_REJECTED},
        ],
    )
    writtenCount = BackupTransactions(db_session, tmp_path)
    assert writtenCount == 1

    _, rows = _ReadBackupRows(tmp_path)
    assert [row["tran_id"] for row in rows] == [TRAN_ID_ONE]
    assert all(row["status"] == STATUS_POSTED for row in rows)


def test_backup_file_and_dir_secure_modes(db_session, record_builder, tmp_path):
    # QA finding M13: the backup directory is owner-only (0700) and each backup
    # file is owner-only (0600), so full-PAN data is never group/world-readable.
    _BuildTransactionsOnCard(
        record_builder,
        [{"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("10.00")}],
        cardNum=CARD_NUM,
    )
    BackupTransactions(db_session, tmp_path)

    backupFiles = list(tmp_path.glob(BACKUP_GLOB))
    assert len(backupFiles) == 1
    fileMode = os.stat(backupFiles[0]).st_mode & 0o777
    dirMode = os.stat(tmp_path).st_mode & 0o777
    assert fileMode == SECURE_FILE_MODE        # 0600
    assert dirMode == SECURE_DIR_MODE          # 0700


def test_backup_excludes_cvv_and_ssn(db_session, record_builder, tmp_path):
    # QA finding M18 / AAP 0.7.8: the full PAN IS written (restore-capable), but
    # the CVV (not part of the transaction record) and the customer SSN (a
    # different table) must never appear in a transaction backup. A customer with a
    # known SSN is linked to the card so the SSN-exclusion assertion is non-vacuous.
    account = record_builder.BuildAccount()
    record_builder.BuildCustomer(overrides={"cust_id": "000000123", "ssn": CUSTOMER_SSN})
    record_builder.BuildCard(CARD_NUM, account.acct_id)
    record_builder.BuildXref(CARD_NUM, "000000123", account.acct_id)
    record_builder.BuildPendingTransaction(
        TRAN_ID_ONE, CARD_NUM, Decimal("100.00"), overrides={"status": STATUS_POSTED}
    )

    BackupTransactions(db_session, tmp_path)

    backupFiles = list(tmp_path.glob(BACKUP_GLOB))
    assert len(backupFiles) == 1
    content = backupFiles[0].read_text(encoding="utf-8")
    assert CARD_NUM in content            # full PAN present (restore-capable, M18)
    assert CUSTOMER_SSN not in content    # customer SSN never in a transaction backup
    assert CVV_LITERAL not in content     # a representative CVV literal is absent

    db_session.expire_all()
    assert _CountTransactions(db_session) == 1
    assert len(list(tmp_path.glob(BACKUP_GLOB))) == 1


# --------------------------------------------------------------------------- #
# RestoreTransactions <- app/jcl/TRANBKP.jcl (restore capability, QA finding M18)
# --------------------------------------------------------------------------- #
def test_restore_round_trip_reconciles(db_session, record_builder, tmp_path):
    # QA finding M18: back up the POSTED ledger, lose it, and restore it from the
    # backup file -- the restored rows reconcile field-for-field with the source
    # (full PAN and exact Decimal amounts preserved).
    knownProcTs = datetime(2024, 1, 15, 9, 30, 0, tzinfo=timezone.utc)
    _BuildTransactionsOnCard(
        record_builder,
        [
            {"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("10.00"), "proc_ts": knownProcTs},
            {"tran_id": TRAN_ID_TWO, "tran_amt": Decimal("20.55"), "proc_ts": knownProcTs},
        ],
        cardNum=CARD_NUM,
    )
    # Reload the source rows DB-normalized before capturing them, so the
    # TIMESTAMPTZ values are the timezone-aware instants PostgreSQL returns (not
    # the raw builder inputs); the restored rows are compared against these same
    # normalized values after their own reload, keeping the comparison exact.
    db_session.flush()
    db_session.expire_all()
    sourceById = {
        tran.tran_id: (tran.tran_amt, tran.card_num, tran.status, tran.orig_ts, tran.proc_ts)
        for tran in db_session.execute(select(Transaction)).scalars().all()
    }

    BackupTransactions(db_session, tmp_path)
    backupFile = next(iter(tmp_path.glob(BACKUP_GLOB)))

    # Simulate the ledger loss the legacy DELETE/DEFINE caused (cards remain, so
    # the FK is satisfied on restore).
    db_session.execute(delete(Transaction))
    db_session.flush()
    assert _CountTransactions(db_session) == 0

    restoredCount = RestoreTransactions(db_session, backupFile)
    db_session.flush()
    db_session.expire_all()

    assert restoredCount == 2
    assert _CountTransactions(db_session) == 2
    for tranId, expected in sourceById.items():
        restored = db_session.get(Transaction, tranId)
        assert restored is not None
        expectedAmt, expectedCard, expectedStatus, expectedOrig, expectedProc = expected
        assert restored.tran_amt == expectedAmt          # exact Decimal, never float
        assert restored.card_num == expectedCard         # full PAN reconstructed
        assert restored.status == expectedStatus
        assert restored.orig_ts == expectedOrig          # timezone-aware round trip
        assert restored.proc_ts == expectedProc


def test_restore_is_idempotent(db_session, record_builder, tmp_path):
    # QA finding M18: restoring the same backup twice converges to the same state
    # (INSERT ... ON CONFLICT DO UPDATE), never raising a duplicate-key error nor
    # duplicating rows.
    _BuildTransactionsOnCard(
        record_builder,
        [
            {"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("10.00")},
            {"tran_id": TRAN_ID_TWO, "tran_amt": Decimal("20.00")},
        ],
        cardNum=CARD_NUM,
    )
    BackupTransactions(db_session, tmp_path)
    backupFile = next(iter(tmp_path.glob(BACKUP_GLOB)))

    firstRestore = RestoreTransactions(db_session, backupFile)
    secondRestore = RestoreTransactions(db_session, backupFile)
    db_session.flush()
    db_session.expire_all()

    assert firstRestore == 2
    assert secondRestore == 2
    assert _CountTransactions(db_session) == 2
    assert db_session.get(Transaction, TRAN_ID_ONE).tran_amt == Decimal("10.00")
