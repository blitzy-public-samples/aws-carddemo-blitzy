# Unit tests for app.services.report_service.ReportService
# Traceability: app/cbl/CORPT00C.cbl (CR00 transaction reports)
"""Unit tests for the transaction-report service (``ReportService``).

Exercises the CR00 report feature ported from ``app/cbl/CORPT00C.cbl``:

* the ``ReportType`` enum labels (Monthly / Yearly / Custom);
* ``ResolveDateRange`` for the Monthly, Yearly and Custom ranges (pure, no DB);
* the CUSTOM ``start_date <= end_date`` ordering rule enforced by the Pydantic
  ``ReportRequest`` model validator (raising a pydantic ``ValidationError``);
* the CSV and PDF renderings of the authorized reporting redesign (AAP 0.8.4),
  driven by a hand-built ``ReportResponse`` (pure, no DB);
* ``GenerateReport`` accumulating exact :class:`~decimal.Decimal` totals over a
  seeded PostgreSQL graph (the only database-backed test).

Numeric fidelity is asserted with exact ``Decimal`` equality -- never
``pytest.approx`` and never a ``float`` total -- matching the regulatory
exact-decimal requirement (AAP 0.7.1). The three pure methods
(``ResolveDateRange`` / ``RenderCsv`` / ``RenderPdf``) and the schema validation
are covered by plain synchronous ``def`` tests; only the ``GenerateReport`` test
is ``async def`` and consumes the shared ``db_session`` fixture (the suite runs
under ``asyncio_mode = "auto"``).

In-code identifiers follow the Ochs Rule (AAP 0.8.2 / 0.8.3): snake_case test
functions, camelCase locals, ALL_UPPERCASE module constants, four-space
indentation, and one behavior asserted per test.
"""

import calendar
import csv
from datetime import date, datetime, timezone
from decimal import Decimal

import pytest
from pydantic import ValidationError

from app.models import (
    Account,
    Card,
    CardXref,
    Customer,
    Transaction,
    TransactionCategory,
    TransactionType,
)
from app.models.transaction import STATUS_PENDING, STATUS_POSTED
from app.schemas.report import (
    ReportRequest,
    ReportResponse,
    ReportType,
    TransactionReportRow,
)
from app.services.report_service import ReportService

# ---------------------------------------------------------------------------
# Expected ReportType labels (the exact CORPT00C screen wording; Ochs Rule
# ALL_UPPERCASE constants). These are the enum *values* the frontend and the
# CSV/PDF headings render.
# ---------------------------------------------------------------------------
EXPECTED_MONTHLY_LABEL = "Monthly"
EXPECTED_YEARLY_LABEL = "Yearly"
EXPECTED_CUSTOM_LABEL = "Custom"

# ---------------------------------------------------------------------------
# CUSTOM date-range fixtures shared by the resolve-custom test, the
# start-after-end rejection test, and the hand-built sample report. A valid,
# ascending pair; the rejection test deliberately passes them swapped.
# ---------------------------------------------------------------------------
CUSTOM_START_DATE = date(2024, 1, 1)
CUSTOM_END_DATE = date(2024, 3, 31)

# Inclusive range for the GenerateReport DB test: the full calendar year that
# contains the seeded transactions' effective date (2024-02-15).
REPORT_RANGE_START = date(2024, 1, 1)
REPORT_RANGE_END = date(2024, 12, 31)

# ---------------------------------------------------------------------------
# Hand-built ReportResponse row values for the CSV/PDF render tests. Every
# amount is an exact Decimal so the rendered output is deterministic and free
# of floating point. tran_cat_cd MUST be exactly four digits (schema edit).
# ---------------------------------------------------------------------------
SAMPLE_TRAN_ID = "0000000000000042"
SAMPLE_ACCT_ID = "00000000001"
SAMPLE_TRAN_TYPE_CD = "01"
SAMPLE_TRAN_TYPE_DESC = "PURCHASE"
SAMPLE_TRAN_CAT_CD = "0001"
SAMPLE_TRAN_CAT_DESC = "RETAIL"
SAMPLE_TRAN_SOURCE = "POS"
SAMPLE_TRAN_AMT = Decimal("12.34")
SAMPLE_REPORT_TOTAL = Decimal("12.34")

# The two-decimal money rendering of SAMPLE_TRAN_AMT that must appear in the CSV
# text (formatted with the same '.2f' spec the service applies to a Decimal).
EXPECTED_CSV_AMOUNT_TEXT = "12.34"

# Valid PDF magic header proving the bytes came from reportlab.
PDF_MAGIC_HEADER = b"%PDF"

# ---------------------------------------------------------------------------
# Seed graph values for the GenerateReport DB test (FK-safe chain
# Customer -> Account -> Card -> CardXref, plus reference type/category rows and
# two posted transactions on the seeded card). Identifiers keep their fixed
# widths (leading zeros preserved). The two amounts sum to an exact 30.00.
# ---------------------------------------------------------------------------
SEED_CUST_ID = "000000001"
SEED_ACCT_ID = "00000000001"
SEED_CARD_NUM = "4111111111111111"
SEED_TRAN_TYPE_CD = "01"
SEED_TRAN_CAT_CD = "0001"
SEED_TRAN_ID_ONE = "0000000000000001"
SEED_TRAN_ID_TWO = "0000000000000002"
SEED_TRAN_AMT_ONE = Decimal("10.00")
SEED_TRAN_AMT_TWO = Decimal("20.00")
SEED_EXPECTED_GRAND_TOTAL = Decimal("30.00")
# Seeded effective timestamps are tz-aware UTC (never naive). A naive datetime
# written to a TIMESTAMPTZ column is encoded by asyncpg using the host process
# TZ, which shifts the stored UTC calendar date at extreme offsets (e.g. noon
# becomes the previous day at +14 or the next day at -12) and makes date-window
# assertions host-dependent. Pinning tzinfo=UTC fixes the stored instant so the
# SQL `::date` filter (transaction_repo) and the Python `.date()` grouping
# (report_service) agree at every host TZ (QA finding F-01 / M-08).
SEED_ORIG_TS = datetime(2024, 2, 15, 12, 0, 0, tzinfo=timezone.utc)
SEED_EXPECTED_ROW_COUNT = 2


def _BuildSampleReport() -> ReportResponse:
    """Build a single-row ``ReportResponse`` for the CSV/PDF render tests.

    The one detail row and all three totals carry exact ``Decimal`` amounts, so
    the rendered CSV/PDF is deterministic and never touches floating point. The
    row populates every required ``TransactionReportRow`` field with values that
    satisfy the schema edits (four-digit ``tran_cat_cd``, width-bounded codes).

    Returns:
        A :class:`~app.schemas.report.ReportResponse` holding one detail row and
        page/account/grand totals all equal to ``SAMPLE_REPORT_TOTAL``.
    """
    sampleRow = TransactionReportRow(
        tran_id=SAMPLE_TRAN_ID,
        acct_id=SAMPLE_ACCT_ID,
        tran_type_cd=SAMPLE_TRAN_TYPE_CD,
        tran_type_desc=SAMPLE_TRAN_TYPE_DESC,
        tran_cat_cd=SAMPLE_TRAN_CAT_CD,
        tran_cat_desc=SAMPLE_TRAN_CAT_DESC,
        tran_source=SAMPLE_TRAN_SOURCE,
        tran_amt=SAMPLE_TRAN_AMT,
    )
    return ReportResponse(
        report_type=ReportType.CUSTOM,
        start_date=CUSTOM_START_DATE,
        end_date=CUSTOM_END_DATE,
        rows=[sampleRow],
        page_total=SAMPLE_REPORT_TOTAL,
        account_total=SAMPLE_REPORT_TOTAL,
        grand_total=SAMPLE_REPORT_TOTAL,
    )


# ===========================================================================
# Phase A -- ReportType enum (pure, no DB).
# ===========================================================================


def test_report_type_values():
    """ReportType members carry the exact CORPT00C screen labels."""
    assert ReportType.MONTHLY.value == EXPECTED_MONTHLY_LABEL
    assert ReportType.YEARLY.value == EXPECTED_YEARLY_LABEL
    assert ReportType.CUSTOM.value == EXPECTED_CUSTOM_LABEL


# ===========================================================================
# Phase B -- ResolveDateRange (pure sync, no DB).
# ===========================================================================


def test_resolve_monthly_range():
    """MONTHLY resolves to the first and last calendar day of this month."""
    request = ReportRequest(
        report_type=ReportType.MONTHLY,
        start_date=date.today(),
        end_date=date.today(),
    )
    start, end = ReportService().ResolveDateRange(request)
    today = date.today()
    lastDay = calendar.monthrange(today.year, today.month)[1]
    assert start == date(today.year, today.month, 1)
    assert end == date(today.year, today.month, lastDay)


def test_resolve_yearly_range():
    """YEARLY resolves to January 1st .. December 31st of the current year."""
    request = ReportRequest(
        report_type=ReportType.YEARLY,
        start_date=date.today(),
        end_date=date.today(),
    )
    start, end = ReportService().ResolveDateRange(request)
    today = date.today()
    assert start == date(today.year, 1, 1)
    assert end == date(today.year, 12, 31)


def test_resolve_custom_range():
    """CUSTOM returns the request's supplied start and end dates verbatim."""
    request = ReportRequest(
        report_type=ReportType.CUSTOM,
        start_date=CUSTOM_START_DATE,
        end_date=CUSTOM_END_DATE,
    )
    resolvedRange = ReportService().ResolveDateRange(request)
    assert resolvedRange == (CUSTOM_START_DATE, CUSTOM_END_DATE)


# ===========================================================================
# Phase C -- CUSTOM ordering validation (pydantic ValidationError).
# ===========================================================================


def test_custom_start_after_end_rejected():
    """A CUSTOM request whose start date is after its end date is rejected."""
    # Pass the ascending pair swapped so start_date (03-31) > end_date (01-01);
    # the ReportRequest model validator must reject it with a ValidationError.
    with pytest.raises(ValidationError):
        ReportRequest(
            report_type=ReportType.CUSTOM,
            start_date=CUSTOM_END_DATE,
            end_date=CUSTOM_START_DATE,
        )


# ===========================================================================
# Phase D -- CSV / PDF rendering (pure, no DB; hand-built ReportResponse).
# ===========================================================================


def test_render_csv_smoke():
    """RenderCsv returns CSV text containing the amount and the transaction id."""
    report = _BuildSampleReport()
    csvText = ReportService().RenderCsv(report)
    assert isinstance(csvText, str)
    assert EXPECTED_CSV_AMOUNT_TEXT in csvText
    assert SAMPLE_TRAN_ID in csvText


def test_render_pdf_smoke():
    """RenderPdf returns non-empty bytes beginning with the %PDF header."""
    report = _BuildSampleReport()
    pdfBytes = ReportService().RenderPdf(report)
    assert isinstance(pdfBytes, (bytes, bytearray))
    assert len(pdfBytes) > 0
    assert pdfBytes[:4] == PDF_MAGIC_HEADER


# ===========================================================================
# Phase E -- GenerateReport totals are exact Decimal (DB-backed, async).
# ===========================================================================


async def test_generate_report_totals_are_decimal(db_session):
    """GenerateReport totals are exact Decimals summing the in-range rows.

    Seeds an FK-safe graph (Customer -> Account -> Card -> CardXref) plus the
    reference type/category rows and two posted transactions on the seeded card,
    both dated inside the requested range, then asserts the grand total is an
    exact ``Decimal("30.00")`` (never a float) and that both rows are reported.
    """
    customer = Customer(
        cust_id=SEED_CUST_ID,
        first_name="JANE",
        last_name="DOE",
        addr_line_1="1 MAIN ST",
    )
    account = Account(
        acct_id=SEED_ACCT_ID,
        active_status="Y",
        curr_bal=Decimal("0.00"),
        credit_limit=Decimal("5000.00"),
        cash_credit_limit=Decimal("1000.00"),
        curr_cyc_credit=Decimal("0.00"),
        curr_cyc_debit=Decimal("0.00"),
    )
    card = Card(
        card_num=SEED_CARD_NUM,
        acct_id=SEED_ACCT_ID,
        embossed_name="JANE DOE",
        active_status="Y",
    )
    xref = CardXref(
        xref_card_num=SEED_CARD_NUM,
        cust_id=SEED_CUST_ID,
        acct_id=SEED_ACCT_ID,
    )
    tranType = TransactionType(
        tran_type=SEED_TRAN_TYPE_CD,
        tran_type_desc="PURCHASE",
    )
    tranCategory = TransactionCategory(
        tran_type_cd=SEED_TRAN_TYPE_CD,
        tran_cat_cd=SEED_TRAN_CAT_CD,
        tran_cat_type_desc="RETAIL",
    )
    db_session.add_all([customer, account, card, xref, tranType, tranCategory])
    # autoflush is disabled on db_session, so push the parent rows to the DB
    # before the child transactions reference them via their foreign key.
    await db_session.flush()

    firstTran = Transaction(
        tran_id=SEED_TRAN_ID_ONE,
        tran_type_cd=SEED_TRAN_TYPE_CD,
        tran_cat_cd=SEED_TRAN_CAT_CD,
        tran_amt=SEED_TRAN_AMT_ONE,
        card_num=SEED_CARD_NUM,
        tran_source=SAMPLE_TRAN_SOURCE,
        orig_ts=SEED_ORIG_TS,
    )
    secondTran = Transaction(
        tran_id=SEED_TRAN_ID_TWO,
        tran_type_cd=SEED_TRAN_TYPE_CD,
        tran_cat_cd=SEED_TRAN_CAT_CD,
        tran_amt=SEED_TRAN_AMT_TWO,
        card_num=SEED_CARD_NUM,
        tran_source=SAMPLE_TRAN_SOURCE,
        orig_ts=SEED_ORIG_TS,
    )
    db_session.add_all([firstTran, secondTran])
    await db_session.flush()

    reportRequest = ReportRequest(
        report_type=ReportType.CUSTOM,
        start_date=REPORT_RANGE_START,
        end_date=REPORT_RANGE_END,
    )
    report = await ReportService().GenerateReport(db_session, reportRequest)

    assert isinstance(report.grand_total, Decimal)
    assert report.grand_total == SEED_EXPECTED_GRAND_TOTAL
    assert len(report.rows) >= SEED_EXPECTED_ROW_COUNT


# ===========================================================================
# Phase F -- M-08 report semantics: deterministic (injected) clock, POSTED-only
# scoping, inclusive date boundaries, deterministic account control-break
# totals, and M-07 CSV formula-injection neutralization end-to-end through the
# report renderer. These replace the previous live-clock / empty-data tests that
# could not detect any of these defects.
# ===========================================================================

# A frozen "today" so the MONTHLY / YEARLY ranges are deterministic. July 21,
# 2026 is the QA cutoff date the finding calls for (M-08).
FROZEN_TODAY = date(2026, 7, 21)
EXPECTED_MONTHLY_START = date(2026, 7, 1)
EXPECTED_MONTHLY_END = date(2026, 7, 31)
EXPECTED_YEARLY_START = date(2026, 1, 1)
EXPECTED_YEARLY_END = date(2026, 12, 31)

# A report-type-description cell that is a live spreadsheet formula (<= 15 chars,
# the TRAN-TYPE-DESC X(15) width) and a formula-leading source cell (<= 10 chars,
# TRAN-SOURCE X(10)); both must be neutralized in the rendered CSV (M-07).
INJECTION_TYPE_DESC = "=1+2+3"
INJECTION_SOURCE = "@evil"
EXPECTED_INJECTION_TYPE_DESC = "'=1+2+3"
EXPECTED_INJECTION_SOURCE = "'@evil"

# Second account used to prove the account control-break totals. Its id sorts
# AFTER the first account's id, so it is the final control-break group whose
# complete subtotal the account total must equal.
SEED_ACCT_ID_TWO = "00000000002"
SEED_CARD_NUM_TWO = "4222222222222222"
SEED_CUST_ID_TWO = "000000002"
SEED_TRAN_ID_THREE = "0000000000000003"
SEED_TRAN_AMT_THREE = Decimal("5.00")
# Grand = 10 + 20 + 5; account total = the final account's (ACCT_TWO) subtotal.
MULTI_ACCT_GRAND_TOTAL = Decimal("35.00")
MULTI_ACCT_LAST_ACCOUNT_TOTAL = Decimal("5.00")
MULTI_ACCT_ROW_COUNT = 3

# Inclusive custom boundary window and four transactions straddling it: one on
# each boundary day (included) and one just outside each end (excluded). The
# effective timestamps are tz-aware UTC (see SEED_ORIG_TS): a naive noon on a
# boundary day would be encoded via the host TZ and slide onto the adjacent
# calendar date at extreme offsets, flipping which rows fall inside the window
# and making this boundary assertion host-dependent (QA finding F-01 / M-08).
BOUNDARY_RANGE_START = date(2024, 2, 10)
BOUNDARY_RANGE_END = date(2024, 2, 20)
BOUNDARY_TS_ON_START = datetime(2024, 2, 10, 12, 0, 0, tzinfo=timezone.utc)
BOUNDARY_TS_ON_END = datetime(2024, 2, 20, 12, 0, 0, tzinfo=timezone.utc)
BOUNDARY_TS_BEFORE = datetime(2024, 2, 9, 12, 0, 0, tzinfo=timezone.utc)
BOUNDARY_TS_AFTER = datetime(2024, 2, 21, 12, 0, 0, tzinfo=timezone.utc)
BOUNDARY_EXPECTED_ROW_COUNT = 2

# Status-filter window and amounts: a POSTED and a PENDING row both dated in
# range; only the POSTED row may appear in the report.
STATUS_POSTED_AMT = Decimal("20.00")
STATUS_PENDING_AMT = Decimal("999.00")


def _SeedReferenceRows(session):
    """Seed the shared transaction type and category reference rows.

    Args:
        session: The isolated async session to add the reference rows to.
    """
    session.add_all(
        [
            TransactionType(
                tran_type=SEED_TRAN_TYPE_CD,
                tran_type_desc="PURCHASE",
            ),
            TransactionCategory(
                tran_type_cd=SEED_TRAN_TYPE_CD,
                tran_cat_cd=SEED_TRAN_CAT_CD,
                tran_cat_type_desc="RETAIL",
            ),
        ]
    )


def _SeedAccountWithCard(session, acctId, cardNum, custId):
    """Seed one FK-safe Customer -> Account -> Card -> CardXref chain.

    Args:
        session: The isolated async session to add the rows to.
        acctId: The 11-char account id (also the CardXref account id).
        cardNum: The 16-char card number owned by the account.
        custId: The 9-char customer id linked through the CardXref.
    """
    session.add_all(
        [
            Customer(
                cust_id=custId,
                first_name="JANE",
                last_name="DOE",
                addr_line_1="1 MAIN ST",
            ),
            Account(
                acct_id=acctId,
                active_status="Y",
                curr_bal=Decimal("0.00"),
                credit_limit=Decimal("5000.00"),
                cash_credit_limit=Decimal("1000.00"),
                curr_cyc_credit=Decimal("0.00"),
                curr_cyc_debit=Decimal("0.00"),
            ),
            Card(
                card_num=cardNum,
                acct_id=acctId,
                embossed_name="JANE DOE",
                active_status="Y",
            ),
            CardXref(
                xref_card_num=cardNum,
                cust_id=custId,
                acct_id=acctId,
            ),
        ]
    )


# ---------------------------------------------------------------------------
# Injected clock -- deterministic MONTHLY / YEARLY ranges (pure, no DB).
# ---------------------------------------------------------------------------


def test_monthly_range_uses_injected_clock():
    """MONTHLY range is the frozen month, independent of the wall clock (M-08)."""
    request = ReportRequest(
        report_type=ReportType.MONTHLY,
        start_date=FROZEN_TODAY,
        end_date=FROZEN_TODAY,
    )
    start, end = ReportService(clock=lambda: FROZEN_TODAY).ResolveDateRange(request)
    assert start == EXPECTED_MONTHLY_START
    assert end == EXPECTED_MONTHLY_END


def test_yearly_range_uses_injected_clock():
    """YEARLY range is the frozen year, independent of the wall clock (M-08)."""
    request = ReportRequest(
        report_type=ReportType.YEARLY,
        start_date=FROZEN_TODAY,
        end_date=FROZEN_TODAY,
    )
    start, end = ReportService(clock=lambda: FROZEN_TODAY).ResolveDateRange(request)
    assert start == EXPECTED_YEARLY_START
    assert end == EXPECTED_YEARLY_END


# ---------------------------------------------------------------------------
# M-07 -- CSV formula injection is neutralized end-to-end through RenderCsv.
# ---------------------------------------------------------------------------


def test_render_csv_neutralizes_injected_fields():
    """A formula-leading description/source is neutralized in the rendered CSV.

    Builds a report whose type description is a live formula and whose source
    begins with '@'; the rendered CSV must carry the apostrophe-prefixed,
    inert forms so a spreadsheet cannot evaluate them (M-07).
    """
    injectedRow = TransactionReportRow(
        tran_id=SAMPLE_TRAN_ID,
        acct_id=SAMPLE_ACCT_ID,
        tran_type_cd=SAMPLE_TRAN_TYPE_CD,
        tran_type_desc=INJECTION_TYPE_DESC,
        tran_cat_cd=SAMPLE_TRAN_CAT_CD,
        tran_cat_desc=SAMPLE_TRAN_CAT_DESC,
        tran_source=INJECTION_SOURCE,
        tran_amt=SAMPLE_TRAN_AMT,
    )
    report = ReportResponse(
        report_type=ReportType.CUSTOM,
        start_date=CUSTOM_START_DATE,
        end_date=CUSTOM_END_DATE,
        rows=[injectedRow],
        page_total=SAMPLE_REPORT_TOTAL,
        account_total=SAMPLE_REPORT_TOTAL,
        grand_total=SAMPLE_REPORT_TOTAL,
    )

    csvText = ReportService().RenderCsv(report)
    parsedRows = list(csv.reader(csvText.splitlines()))
    dataRow = parsedRows[1]

    assert EXPECTED_INJECTION_TYPE_DESC in dataRow
    assert EXPECTED_INJECTION_SOURCE in dataRow
    # The exact amount is preserved (never neutralized, never a float).
    assert EXPECTED_CSV_AMOUNT_TEXT in dataRow


# ---------------------------------------------------------------------------
# POSTED-only scoping, inclusive boundaries, deterministic control-break totals.
# ---------------------------------------------------------------------------


async def test_generate_report_excludes_non_posted(db_session):
    """Only POSTED transactions appear; a PENDING in-range row is excluded (M-08).

    Seeds one POSTED and one PENDING transaction on the same card, both dated in
    the requested range. The report must contain exactly the POSTED row and its
    grand total must exclude the PENDING amount.
    """
    _SeedReferenceRows(db_session)
    _SeedAccountWithCard(db_session, SEED_ACCT_ID, SEED_CARD_NUM, SEED_CUST_ID)
    await db_session.flush()
    db_session.add_all(
        [
            Transaction(
                tran_id=SEED_TRAN_ID_ONE,
                tran_type_cd=SEED_TRAN_TYPE_CD,
                tran_cat_cd=SEED_TRAN_CAT_CD,
                tran_amt=STATUS_POSTED_AMT,
                card_num=SEED_CARD_NUM,
                tran_source=SAMPLE_TRAN_SOURCE,
                orig_ts=SEED_ORIG_TS,
                status=STATUS_POSTED,
            ),
            Transaction(
                tran_id=SEED_TRAN_ID_TWO,
                tran_type_cd=SEED_TRAN_TYPE_CD,
                tran_cat_cd=SEED_TRAN_CAT_CD,
                tran_amt=STATUS_PENDING_AMT,
                card_num=SEED_CARD_NUM,
                tran_source=SAMPLE_TRAN_SOURCE,
                orig_ts=SEED_ORIG_TS,
                status=STATUS_PENDING,
            ),
        ]
    )
    await db_session.flush()

    reportRequest = ReportRequest(
        report_type=ReportType.CUSTOM,
        start_date=REPORT_RANGE_START,
        end_date=REPORT_RANGE_END,
    )
    report = await ReportService().GenerateReport(db_session, reportRequest)

    assert len(report.rows) == 1
    assert report.rows[0].tran_id == SEED_TRAN_ID_ONE
    assert report.grand_total == STATUS_POSTED_AMT


async def test_generate_report_includes_inclusive_boundaries(db_session):
    """Rows on the start and end boundary days are in; just-outside rows are out.

    Seeds four POSTED transactions dated on the start boundary, on the end
    boundary, one day before, and one day after; only the two boundary rows fall
    in the inclusive ``[start, end]`` window (M-08 boundary/timezone behavior).
    """
    _SeedReferenceRows(db_session)
    _SeedAccountWithCard(db_session, SEED_ACCT_ID, SEED_CARD_NUM, SEED_CUST_ID)
    await db_session.flush()
    db_session.add_all(
        [
            Transaction(
                tran_id="0000000000000010",
                tran_type_cd=SEED_TRAN_TYPE_CD,
                tran_cat_cd=SEED_TRAN_CAT_CD,
                tran_amt=Decimal("1.00"),
                card_num=SEED_CARD_NUM,
                tran_source=SAMPLE_TRAN_SOURCE,
                orig_ts=BOUNDARY_TS_ON_START,
            ),
            Transaction(
                tran_id="0000000000000011",
                tran_type_cd=SEED_TRAN_TYPE_CD,
                tran_cat_cd=SEED_TRAN_CAT_CD,
                tran_amt=Decimal("2.00"),
                card_num=SEED_CARD_NUM,
                tran_source=SAMPLE_TRAN_SOURCE,
                orig_ts=BOUNDARY_TS_ON_END,
            ),
            Transaction(
                tran_id="0000000000000012",
                tran_type_cd=SEED_TRAN_TYPE_CD,
                tran_cat_cd=SEED_TRAN_CAT_CD,
                tran_amt=Decimal("4.00"),
                card_num=SEED_CARD_NUM,
                tran_source=SAMPLE_TRAN_SOURCE,
                orig_ts=BOUNDARY_TS_BEFORE,
            ),
            Transaction(
                tran_id="0000000000000013",
                tran_type_cd=SEED_TRAN_TYPE_CD,
                tran_cat_cd=SEED_TRAN_CAT_CD,
                tran_amt=Decimal("8.00"),
                card_num=SEED_CARD_NUM,
                tran_source=SAMPLE_TRAN_SOURCE,
                orig_ts=BOUNDARY_TS_AFTER,
            ),
        ]
    )
    await db_session.flush()

    reportRequest = ReportRequest(
        report_type=ReportType.CUSTOM,
        start_date=BOUNDARY_RANGE_START,
        end_date=BOUNDARY_RANGE_END,
    )
    report = await ReportService().GenerateReport(db_session, reportRequest)

    reportedIds = {row.tran_id for row in report.rows}
    assert len(report.rows) == BOUNDARY_EXPECTED_ROW_COUNT
    assert reportedIds == {"0000000000000010", "0000000000000011"}
    # Grand total sums only the two in-range boundary rows (1.00 + 2.00).
    assert report.grand_total == Decimal("3.00")


async def test_generate_report_control_break_totals_are_deterministic(db_session):
    """Grand total sums all rows; account total is the final account's subtotal.

    Seeds two accounts (10.00 + 20.00 on the first; 5.00 on the second) whose
    ids order the second account last. After the deterministic control-break
    sort the account total equals the second account's complete subtotal (5.00),
    the grand total sums all three rows (35.00), and the page total equals the
    grand total (M-08).
    """
    _SeedReferenceRows(db_session)
    _SeedAccountWithCard(db_session, SEED_ACCT_ID, SEED_CARD_NUM, SEED_CUST_ID)
    _SeedAccountWithCard(
        db_session, SEED_ACCT_ID_TWO, SEED_CARD_NUM_TWO, SEED_CUST_ID_TWO
    )
    await db_session.flush()
    db_session.add_all(
        [
            Transaction(
                tran_id=SEED_TRAN_ID_ONE,
                tran_type_cd=SEED_TRAN_TYPE_CD,
                tran_cat_cd=SEED_TRAN_CAT_CD,
                tran_amt=SEED_TRAN_AMT_ONE,
                card_num=SEED_CARD_NUM,
                tran_source=SAMPLE_TRAN_SOURCE,
                orig_ts=SEED_ORIG_TS,
            ),
            Transaction(
                tran_id=SEED_TRAN_ID_TWO,
                tran_type_cd=SEED_TRAN_TYPE_CD,
                tran_cat_cd=SEED_TRAN_CAT_CD,
                tran_amt=SEED_TRAN_AMT_TWO,
                card_num=SEED_CARD_NUM,
                tran_source=SAMPLE_TRAN_SOURCE,
                orig_ts=SEED_ORIG_TS,
            ),
            Transaction(
                tran_id=SEED_TRAN_ID_THREE,
                tran_type_cd=SEED_TRAN_TYPE_CD,
                tran_cat_cd=SEED_TRAN_CAT_CD,
                tran_amt=SEED_TRAN_AMT_THREE,
                card_num=SEED_CARD_NUM_TWO,
                tran_source=SAMPLE_TRAN_SOURCE,
                orig_ts=SEED_ORIG_TS,
            ),
        ]
    )
    await db_session.flush()

    reportRequest = ReportRequest(
        report_type=ReportType.CUSTOM,
        start_date=REPORT_RANGE_START,
        end_date=REPORT_RANGE_END,
    )
    report = await ReportService().GenerateReport(db_session, reportRequest)

    assert len(report.rows) == MULTI_ACCT_ROW_COUNT
    assert report.grand_total == MULTI_ACCT_GRAND_TOTAL
    assert report.account_total == MULTI_ACCT_LAST_ACCOUNT_TOTAL
    assert report.page_total == MULTI_ACCT_GRAND_TOTAL
    # Rows are account-ordered: the final account's row sorts last.
    assert report.rows[-1].acct_id == SEED_ACCT_ID_TWO
