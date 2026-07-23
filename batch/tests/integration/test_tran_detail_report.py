# DB-backed integration tests for the CardDemo transaction-detail report job,
# reconciled against the legacy COBOL program it ports (Ochs Rule: every ported
# unit references its origin). This closes the sixth and largest slice of QA
# finding F-03 (six in-scope batch jobs at 0.0% coverage):
#
#   * batch.jobs.tran_detail_report.ReportTransactionDetail <- app/cbl/CBTRN03C.cbl
#
# Every test runs the report against a real PostgreSQL 17 test database using
# the shared, rolled-back synchronous ``db_session`` / ``seeded_db`` /
# ``record_builder`` fixtures from the batch conftests. Assertions cover the
# CSV title / date-range band / column header, the eight report columns, the
# control-break page / account / grand totals as exact ``Decimal`` values, the
# inclusive date-range filter (including its boundary dates), the no-output-dir
# summary-only path, and the sensitive-data rule that a full card number (PAN)
# is never written while its masked last-four form is (AAP 0.7.8).
#
# The legacy app/ tree is REFERENCE-only and is never modified. Synchronous
# only: the batch layer is blocking (psycopg2); this module imports no async
# machinery.
"""Integration tests for the CardDemo transaction detail report job (F-03).

These exercise ``ReportTransactionDetail`` (the port of CBTRN03C) end to end
against seeded PostgreSQL and against controlled, builder-created rows so that
a regression -- a wrong detail count, a broken control-break subtotal, an
off-by-one date-range boundary, a dropped banner, or (critically) an unmasked
card number -- is caught by an executing test rather than shipping undetected.

Coding conventions (AAP 0.8.2/0.8.3 Ochs Rule): helper callables use PascalCase,
local variables use camelCase, module constants use ALL_UPPERCASE, and test
function names plus pytest fixture names stay snake_case (the documented pytest
framework-contract exception).
"""

import csv
import logging
from datetime import datetime
from decimal import Decimal

from sqlalchemy import func, select

from app.models import Transaction
from batch.jobs.tran_detail_report import ReportTransactionDetail

# --------------------------------------------------------------------------- #
# Golden-master expectations (the ``wc -l`` of app/data/ASCII/dailytran.txt and
# its distinct card-number count). Mirrors test_loaders.py.
# --------------------------------------------------------------------------- #
EXPECTED_TRANSACTION_COUNT = 300
EXPECTED_DISTINCT_CARD_COUNT = 50

# --------------------------------------------------------------------------- #
# Report contract values, stated independently of the module under test so the
# assertions verify the specification rather than echoing the module's own
# constants (CBTRN03C: title, date-range band, eight-column header, totals).
# --------------------------------------------------------------------------- #
REPORT_FILE_NAME = "tran_detail_report.csv"
EXPECTED_REPORT_TITLE = "Daily Transaction Report"
EXPECTED_HEADER = [
    "TransID",
    "AccountID",
    "TypeCd",
    "TypeDesc",
    "CatCd",
    "CatDesc",
    "Source",
    "Amount",
]
DATE_RANGE_LABEL = "Date Range:"
DATE_RANGE_SEPARATOR = "to"
ALL_DATES_LABEL = "ALL"
ACCOUNT_TOTAL_LABEL = "Account Total"
GRAND_TOTAL_LABEL = "Grand Total"

# Execution banners emitted by the ported program (verbatim for log parity).
BANNER_START = "START OF EXECUTION OF PROGRAM CBTRN03C"
BANNER_END = "END OF EXECUTION OF PROGRAM CBTRN03C"
NOT_PERSISTED_MARKER = "not persisted"

# A real seed card that owns transactions (six daily rows) so its masked form
# appears in that card's Account Total band; its full PAN must never appear.
SEED_TRAN_CARD_NUM = "9805583408996588"
SEED_TRAN_CARD_MASKED = "****6588"

# Controlled two-card control-break scenario (obviously-fake PANs). Card A sorts
# before card B, so A's account total closes on the control break and B's closes
# at end of file.
CTRL_CARD_A = "4111111111111111"
CTRL_CARD_A_MASKED = "****1111"
CTRL_CARD_B = "4222222222222222"
CTRL_CARD_B_MASKED = "****2222"
CTRL_ACCT_A = "00000000009"
CTRL_ACCT_B = "00000000010"
CTRL_TRAN_A1 = "0000000000000001"
CTRL_TRAN_A2 = "0000000000000002"
CTRL_TRAN_B1 = "0000000000000003"
CTRL_AMT_A1 = Decimal("10.00")
CTRL_AMT_A2 = Decimal("20.00")
CTRL_AMT_B1 = Decimal("5.00")
CTRL_ACCOUNT_TOTAL_A = "30.00"
CTRL_ACCOUNT_TOTAL_B = "5.00"
CTRL_GRAND_TOTAL = "35.00"
CTRL_DETAIL_LINE_COUNT = 3

# Inclusive date-range boundary scenario: four dated transactions on one card,
# only the two on the window edges are retained (start and end are inclusive).
BOUNDARY_CARD = "4333333333333333"
BOUNDARY_ACCT = "00000000011"
DATE_WINDOW = ("2024-01-01", "2024-01-31")
BOUNDARY_TRAN_BEFORE = "0000000000000010"
BOUNDARY_TRAN_START = "0000000000000011"
BOUNDARY_TRAN_END = "0000000000000012"
BOUNDARY_TRAN_AFTER = "0000000000000013"
BOUNDARY_TS_BEFORE = datetime(2023, 12, 31, 12, 0, 0)
BOUNDARY_TS_START = datetime(2024, 1, 1, 0, 0, 0)
BOUNDARY_TS_END = datetime(2024, 1, 31, 23, 59, 59)
BOUNDARY_TS_AFTER = datetime(2024, 2, 1, 0, 0, 0)
BOUNDARY_AMT = Decimal("1.00")
EXPECTED_IN_WINDOW = 2

# An empty-result date window: every seeded proc_ts is NULL, so the range
# filter excludes all 300 rows and only the zeroed grand total is written.
EMPTY_WINDOW = ("2020-01-01", "2020-12-31")
ZERO_TOTAL = "0.00"


# --------------------------------------------------------------------------- #
# Module-level helpers (Ochs PascalCase; <= 4 parameters each).
# --------------------------------------------------------------------------- #
def _CountTransactions(session):
    """Return the number of transaction rows visible in the test transaction.

    Args:
        session: The open, rolled-back synchronous test session.

    Returns:
        The current ``transactions`` row count as an ``int``.
    """
    return session.execute(select(func.count()).select_from(Transaction)).scalar_one()


def _ReadCsvRows(reportPath):
    """Parse a written report CSV into a list of row (cell-list) values.

    Args:
        reportPath: The :class:`~pathlib.Path` of the report file to read.

    Returns:
        A list of rows, each a list of string cells (blank lines become ``[]``).
    """
    with reportPath.open(newline="", encoding="utf-8") as handle:
        return list(csv.reader(handle))


def _RowsWithLabel(rows, label):
    """Return every report row whose first cell equals ``label``.

    Args:
        rows: The parsed CSV rows.
        label: The band label to match in column zero (e.g. ``"Grand Total"``).

    Returns:
        The matching rows, in file order.
    """
    return [row for row in rows if row and row[0] == label]


def _BuildLinkedCard(recordBuilder, cardNum, acctId):
    """Create one account and one card on it, returning the card row.

    Args:
        recordBuilder: The ``RecordBuilder`` bound to the rolled-back session.
        cardNum: The 16-character card number to create.
        acctId: The account id to own the card.

    Returns:
        The created :class:`~app.models.card.Card` row.
    """
    recordBuilder.BuildAccount(acctId)
    return recordBuilder.BuildCard(cardNum, acctId)


# =========================================================================== #
# CBTRN03C -> batch.jobs.tran_detail_report.ReportTransactionDetail
# =========================================================================== #
def test_report_no_output_dir_returns_seeded_count(seeded_db, caplog):
    # CBTRN03C with no output directory: the report streams every transaction,
    # returns the detail-line count, and logs a summary WITHOUT persisting a
    # file (the _NullWriter path). On the golden-master seed the count is 300.
    assert _CountTransactions(seeded_db) == EXPECTED_TRANSACTION_COUNT
    with caplog.at_level(logging.INFO):
        detailLines = ReportTransactionDetail(seeded_db)
    assert detailLines == EXPECTED_TRANSACTION_COUNT
    assert BANNER_START in caplog.text
    assert BANNER_END in caplog.text
    assert NOT_PERSISTED_MARKER in caplog.text


def test_report_writes_csv_with_title_header_and_all_dates_band(seeded_db, tmp_path):
    # CBTRN03C with an output directory: the CSV opens with the report title,
    # the date-range band (ALL/ALL when unfiltered), a blank spacer, and the
    # eight-column header; the returned count matches the golden-master total.
    detailLines = ReportTransactionDetail(seeded_db, outputDir=tmp_path)
    assert detailLines == EXPECTED_TRANSACTION_COUNT
    reportPath = tmp_path / REPORT_FILE_NAME
    assert reportPath.is_file()
    rows = _ReadCsvRows(reportPath)
    assert rows[0] == [EXPECTED_REPORT_TITLE]
    assert rows[1] == [DATE_RANGE_LABEL, ALL_DATES_LABEL, DATE_RANGE_SEPARATOR, ALL_DATES_LABEL]
    assert rows[2] == []
    assert rows[3] == EXPECTED_HEADER
    # Exactly one grand-total band always closes the report.
    assert len(_RowsWithLabel(rows, GRAND_TOTAL_LABEL)) == 1


def test_report_masks_pan_and_never_writes_full_pan(seeded_db, tmp_path):
    # AAP 0.7.8: a real seed card that owns transactions appears only in its
    # masked ****last-four form (in that card's Account Total band); its full
    # sixteen-digit PAN is never written to the report anywhere.
    ReportTransactionDetail(seeded_db, outputDir=tmp_path)
    csvText = (tmp_path / REPORT_FILE_NAME).read_text(encoding="utf-8")
    assert SEED_TRAN_CARD_NUM not in csvText
    assert SEED_TRAN_CARD_MASKED in csvText


def test_report_date_range_includes_inclusive_boundaries(record_builder, db_session, tmp_path):
    # CBTRN03C date filter reproduces TRAN-PROC-TS(1:10) >= WS-START-DATE AND
    # <= WS-END-DATE: of four dated transactions, only the two ON the window's
    # start and end dates are retained (both boundaries inclusive); the one the
    # day before and the one the day after are excluded.
    _BuildLinkedCard(record_builder, BOUNDARY_CARD, BOUNDARY_ACCT)
    record_builder.BuildPendingTransaction(
        BOUNDARY_TRAN_BEFORE, BOUNDARY_CARD, BOUNDARY_AMT, overrides={"proc_ts": BOUNDARY_TS_BEFORE}
    )
    record_builder.BuildPendingTransaction(
        BOUNDARY_TRAN_START, BOUNDARY_CARD, BOUNDARY_AMT, overrides={"proc_ts": BOUNDARY_TS_START}
    )
    record_builder.BuildPendingTransaction(
        BOUNDARY_TRAN_END, BOUNDARY_CARD, BOUNDARY_AMT, overrides={"proc_ts": BOUNDARY_TS_END}
    )
    record_builder.BuildPendingTransaction(
        BOUNDARY_TRAN_AFTER, BOUNDARY_CARD, BOUNDARY_AMT, overrides={"proc_ts": BOUNDARY_TS_AFTER}
    )
    detailLines = ReportTransactionDetail(db_session, outputDir=tmp_path, dateRange=DATE_WINDOW)
    assert detailLines == EXPECTED_IN_WINDOW
    rows = _ReadCsvRows(tmp_path / REPORT_FILE_NAME)
    # The date-range band echoes the requested window verbatim.
    assert rows[1] == [DATE_RANGE_LABEL, DATE_WINDOW[0], DATE_RANGE_SEPARATOR, DATE_WINDOW[1]]


def test_report_control_break_totals_are_exact_decimal(record_builder, db_session, tmp_path):
    # CBTRN03C control break: two cards (A: 10.00 + 20.00, B: 5.00) yield two
    # Account Total bands -- 30.00 for the masked card A and 5.00 for card B --
    # and one Grand Total of 35.00, each an EXACT Decimal (never float).
    _BuildLinkedCard(record_builder, CTRL_CARD_A, CTRL_ACCT_A)
    _BuildLinkedCard(record_builder, CTRL_CARD_B, CTRL_ACCT_B)
    record_builder.BuildPendingTransaction(CTRL_TRAN_A1, CTRL_CARD_A, CTRL_AMT_A1)
    record_builder.BuildPendingTransaction(CTRL_TRAN_A2, CTRL_CARD_A, CTRL_AMT_A2)
    record_builder.BuildPendingTransaction(CTRL_TRAN_B1, CTRL_CARD_B, CTRL_AMT_B1)
    detailLines = ReportTransactionDetail(db_session, outputDir=tmp_path)
    assert detailLines == CTRL_DETAIL_LINE_COUNT
    rows = _ReadCsvRows(tmp_path / REPORT_FILE_NAME)
    accountRows = _RowsWithLabel(rows, ACCOUNT_TOTAL_LABEL)
    assert len(accountRows) == 2
    totalsByMaskedCard = {row[1]: row[-1] for row in accountRows}
    assert totalsByMaskedCard[CTRL_CARD_A_MASKED] == CTRL_ACCOUNT_TOTAL_A
    assert totalsByMaskedCard[CTRL_CARD_B_MASKED] == CTRL_ACCOUNT_TOTAL_B
    grandRows = _RowsWithLabel(rows, GRAND_TOTAL_LABEL)
    assert len(grandRows) == 1
    assert grandRows[0][-1] == CTRL_GRAND_TOTAL
    # Neither fabricated full PAN is ever written to the report.
    csvText = (tmp_path / REPORT_FILE_NAME).read_text(encoding="utf-8")
    assert CTRL_CARD_A not in csvText
    assert CTRL_CARD_B not in csvText


def test_report_empty_date_window_returns_zero_with_grand_total(seeded_db, tmp_path):
    # CBTRN03C over a window that matches nothing: every seeded proc_ts is NULL,
    # so the inclusive filter excludes all 300 rows, no Account Total bands are
    # written, and only the zeroed Grand Total closes the report.
    detailLines = ReportTransactionDetail(seeded_db, outputDir=tmp_path, dateRange=EMPTY_WINDOW)
    assert detailLines == 0
    rows = _ReadCsvRows(tmp_path / REPORT_FILE_NAME)
    grandRows = _RowsWithLabel(rows, GRAND_TOTAL_LABEL)
    assert len(grandRows) == 1
    assert grandRows[0][-1] == ZERO_TOTAL
    assert _RowsWithLabel(rows, ACCOUNT_TOTAL_LABEL) == []
