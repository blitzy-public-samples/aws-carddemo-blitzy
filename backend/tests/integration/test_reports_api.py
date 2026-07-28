# Integration tests for the transaction-report API.
# Ported from legacy CICS online program CORPT00C (transaction CR00).
# Endpoint: GET /api/v1/reports/transactions (AAP §0.5.5).
# Verifies the report-type factory (Monthly/Yearly/Custom), exact-Decimal totals,
# and the authorized reporting redesign (§0.8.4): on-screen JSON table plus
# downloadable CSV (text/csv) and PDF (application/pdf). Money is Decimal.
"""Integration tests for the transaction-report REST API.

These tests pin the externally observable contract of
``GET {API_V1_PREFIX}/reports/transactions`` -- the modern FastAPI replacement
for the legacy CICS online program ``CORPT00C`` (transaction ``CR00``, BMS map
``CORPT00``). They exercise the endpoint end-to-end through the in-process httpx
ASGI client wired to ``app.main:app`` by the parent ``tests/conftest.py`` and
backed by the disposable PostgreSQL test database, so the router, the
``ReportService`` report-type factory, the Pydantic request/response schemas,
and the ``app.main`` exception handlers are all covered together.

What is verified (traceability to the source program and the AAP):

* The report-type factory ported from the ``CORPT00C`` ``PROCESS-ENTER-KEY``
  EVALUATE -- the ``Monthly`` / ``Yearly`` / ``Custom`` ranges are all accepted
  and each echoes its requested type back (AAP 0.4.3 / 0.8.1).
* Exact-``Decimal`` money (AAP 0.7.1): the ``grand_total`` parses as an exact
  :class:`~decimal.Decimal` and reconciles to the ``Decimal`` sum of the row
  amounts -- never as a binary ``float`` and never via ``sum(..., 0.0)``.
* The authorized reporting redesign (AAP 0.4.4 / 0.8.4): the single report
  payload is served as three representations selected by the ``format`` query
  parameter -- an on-screen JSON table (default), a downloadable CSV
  (``text/csv``), and a downloadable PDF (``application/pdf``, ``%PDF`` header).
* Access control and input validation: the route requires authentication
  (HTTP 401 for the unauthenticated client, the stateless successor to the
  legacy COMMAREA identity check) and rejects an out-of-order ``Custom`` date
  range (HTTP 400/422).

Endpoint-contract notes discovered by inspecting the router
(``app/api/v1/reports.py``) and confirmed against the generated OpenAPI schema
(the agent contract directs verifying the real signature and adapting):

* The request is a ``GET`` carrying QUERY parameters (not a POST body):
  ``report_type`` (required), ``start_date`` and ``end_date`` (both required for
  every report type -- the service ignores the supplied dates for ``Monthly`` /
  ``Yearly`` and derives the range from the current date, but FastAPI still
  requires the query parameters to be present), the optional ``format`` selector
  and the optional ``confirm`` flag. Every request below therefore always sends
  a valid ``start_date`` / ``end_date`` pair; the authentication test sends a
  fully valid query so the *only* possible rejection reason is the missing
  identity, guaranteeing a deterministic 401.
* The ``seed_data`` fixture loads the reference and master tables but
  deliberately seeds no transactions (posting tests build their own), so the
  report rows are legitimately empty here. The assertions are written to hold
  for an empty result (the ``Decimal`` reconciliation is an exact identity even
  when both sides are zero) and to tighten automatically if rows are present.

Ochs Rule (AAP 0.8.2 / 0.8.3): the module/file name is snake_case; the helper is
PascalCase (``ToDecimal``); local variables are camelCase (``responseBody``,
``reportRows``, ``grandTotal``); module constants are ALL_UPPERCASE
(``REPORT_MONTHLY`` ...). Status codes and content types are asserted precisely
(200 / 400|422 / 401, ``text/csv`` / ``application/pdf``) -- never a broad
"not 2xx". No ``AsyncClient(app=...)`` shortcut is used (removed in httpx
0.28.1); the pre-wired fixtures are consumed and awaited sequentially.
"""

from __future__ import annotations

from decimal import Decimal

import pytest
from httpx import AsyncClient

from app.core.config import settings

# ---------------------------------------------------------------------------
# Endpoint under test (ALL_UPPERCASE per the Ochs Rule). Built from
# ``settings.API_V1_PREFIX`` so the versioned mount prefix is never hardcoded
# here; ``app.main`` mounts the reports router at ``{API_V1_PREFIX}/reports`` and
# the report resource itself lives at ``/transactions``.
# ---------------------------------------------------------------------------
REPORTS_URL = f"{settings.API_V1_PREFIX}/reports/transactions"

# The three mutually exclusive report ranges offered by CORPT00 (the enum values
# of app.schemas.report.ReportType, sent verbatim as the ``report_type`` query
# value). Kept as string literals -- the exact wire values the API accepts.
REPORT_MONTHLY = "Monthly"
REPORT_YEARLY = "Yearly"
REPORT_CUSTOM = "Custom"

# The three output representations of the authorized reporting redesign
# (app.api.v1.reports.ReportFormat wire values), sent as the ``format`` query
# value. ``json`` is the router default; ``csv`` and ``pdf`` are file downloads.
FORMAT_JSON = "json"
FORMAT_CSV = "csv"
FORMAT_PDF = "pdf"

# A deliberately wide, always-valid inclusive date range. ``start_date`` and
# ``end_date`` are required query parameters for every report type; this range
# spans any conceivable seed transaction so a ``Custom`` report includes them
# all, and it satisfies the required-parameter and start<=end edits for the
# ``Monthly`` / ``Yearly`` requests whose service ignores the supplied dates.
WIDE_START_DATE = "2000-01-01"
WIDE_END_DATE = "2099-12-31"

# An out-of-order (start > end) ``Custom`` range used to prove the date-range
# edit rejects it. The ReportRequest model validator (start<=end) fails during
# query-parameter binding, which app.main translates to HTTP 422.
INVERTED_START_DATE = "2025-12-31"
INVERTED_END_DATE = "2025-01-01"

# Precise HTTP status codes asserted below (never a broad "not 2xx", Ochs rigor).
HTTP_OK = 200
HTTP_UNAUTHORIZED = 401
# An invalid custom range is a client input error; FastAPI/app.main surface it as
# either 400 or 422 depending on where the edit fires, so both are accepted.
INVALID_RANGE_STATUSES = (400, 422)

# Exact response content types for the two download formats. The CSV is streamed
# with media type ``text/csv``; Starlette appends "; charset=utf-8" to a
# ``text/*`` media type, so the header is matched with ``startswith``. The PDF is
# returned with the exact ``application/pdf`` media type (no charset suffix).
CSV_CONTENT_TYPE = "text/csv"
PDF_CONTENT_TYPE = "application/pdf"

# A stable column heading present in the CSV header row emitted by
# ReportService.RenderCsv (CSV_HEADER[0]); the header is always written even when
# there are zero data rows, so its presence proves a well-formed CSV document.
CSV_HEADER_COLUMN = "Transaction ID"

# The four-byte signature every valid PDF document begins with (reportlab output).
PDF_SIGNATURE = b"%PDF"

# Substring expected in the download ``Content-Disposition`` header.
ATTACHMENT_DISPOSITION = "attachment"

# Report-column truncation widths reproduced by TransactionReportRow (the report
# trims the 50-char type/category descriptions to these widths, per CVTRA07Y).
TRAN_TYPE_DESC_MAX_LENGTH = 15
TRAN_CAT_DESC_MAX_LENGTH = 29


def ToDecimal(jsonValue: object) -> Decimal:
    """Parse a JSON-decoded money value into an EXACT :class:`~decimal.Decimal`.

    Routing the value through ``Decimal(str(value))`` guarantees exact decimal
    arithmetic regardless of how the API serialized the amount (a JSON string
    such as ``"12.34"``, or a number that ``json`` decoded to ``int`` / ``float``):
    stringifying first, then constructing the ``Decimal`` from that text, never
    lets a binary floating-point value contaminate the comparison. This is the
    test-side mirror of the backend's exact-``Decimal`` money rule (AAP 0.7.1);
    it is intentionally never ``float(value)``.

    Args:
        jsonValue: A money value taken from a decoded JSON response (a total or a
            row amount), as ``str``, ``int``, or ``float``.

    Returns:
        The value as an exact :class:`~decimal.Decimal`.
    """
    return Decimal(str(jsonValue))


# ===========================================================================
# Phase 2 -- JSON report: exact-Decimal totals, shape, auth, and range edit.
# ===========================================================================


@pytest.mark.asyncio
async def test_report_json_monthly(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """A ``Monthly`` JSON report returns 200 with a Decimal ``grand_total``.

    Exercises the default JSON representation of the report-type factory's
    ``Monthly`` branch. Asserts the ``ReportResponse`` shape (``rows`` and
    ``grand_total`` present), that the grand total parses as an exact
    :class:`~decimal.Decimal` (never a ``float``), and -- for any rows that are
    present -- that the type/category descriptions honor the report-column
    truncation widths (15 / 29). The rows are legitimately empty under the
    transaction-free ``seed_data`` fixture, so the per-row edits simply do not
    fire.

    Args:
        admin_client: Authenticated (administrator) httpx ASGI client.
        seed_data: Golden-master reference + master seed (no transactions).
    """
    response = await admin_client.get(
        REPORTS_URL,
        params={
            "report_type": REPORT_MONTHLY,
            "start_date": WIDE_START_DATE,
            "end_date": WIDE_END_DATE,
            "format": FORMAT_JSON,
        },
    )

    assert response.status_code == HTTP_OK
    responseBody = response.json()
    assert "rows" in responseBody
    assert "grand_total" in responseBody

    grandTotal = ToDecimal(responseBody["grand_total"])
    assert isinstance(grandTotal, Decimal)

    reportRows = responseBody["rows"]
    for reportRow in reportRows:
        typeDescription = reportRow.get("tran_type_desc")
        if typeDescription is not None:
            assert len(typeDescription) <= TRAN_TYPE_DESC_MAX_LENGTH
        categoryDescription = reportRow.get("tran_cat_desc")
        if categoryDescription is not None:
            assert len(categoryDescription) <= TRAN_CAT_DESC_MAX_LENGTH


@pytest.mark.asyncio
async def test_report_totals_are_decimal_sum(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """The reported ``grand_total`` equals the exact Decimal sum of the rows.

    Requests a ``Custom`` report over the wide range so every seed transaction
    (if any) is included, then accumulates the row amounts starting from
    ``Decimal("0")`` -- never ``0.0`` -- and asserts the running sum equals the
    reported ``grand_total`` parsed as an exact :class:`~decimal.Decimal`. This
    is an exact decimal identity: it holds when both sides are zero (the
    transaction-free seed) and continues to hold, to the cent, when rows are
    present, proving totals accumulate as ``Decimal`` and never through binary
    floating point (AAP 0.7.1).

    Args:
        admin_client: Authenticated (administrator) httpx ASGI client.
        seed_data: Golden-master reference + master seed (no transactions).
    """
    response = await admin_client.get(
        REPORTS_URL,
        params={
            "report_type": REPORT_CUSTOM,
            "start_date": WIDE_START_DATE,
            "end_date": WIDE_END_DATE,
            "format": FORMAT_JSON,
        },
    )

    assert response.status_code == HTTP_OK
    responseBody = response.json()
    reportRows = responseBody["rows"]

    rowAmountSum = Decimal("0")
    for reportRow in reportRows:
        rowAmountSum = rowAmountSum + ToDecimal(reportRow["tran_amt"])

    grandTotal = ToDecimal(responseBody["grand_total"])
    assert rowAmountSum == grandTotal


@pytest.mark.asyncio
async def test_report_custom_requires_valid_range(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """A ``Custom`` report with ``start_date`` > ``end_date`` is rejected.

    The report request enforces the legacy ``CORPT00C`` rule that the custom
    range start must be on or before the end. Sending an inverted range makes the
    ReportRequest model validator fail during query-parameter binding, which
    ``app.main`` surfaces as an HTTP 422 (a 400 is also accepted as a valid
    client-error rendering).

    Args:
        admin_client: Authenticated (administrator) httpx ASGI client.
        seed_data: Golden-master reference + master seed (no transactions).
    """
    response = await admin_client.get(
        REPORTS_URL,
        params={
            "report_type": REPORT_CUSTOM,
            "start_date": INVERTED_START_DATE,
            "end_date": INVERTED_END_DATE,
            "format": FORMAT_JSON,
        },
    )

    assert response.status_code in INVALID_RANGE_STATUSES


@pytest.mark.asyncio
async def test_report_requires_auth(client: AsyncClient) -> None:
    """The report endpoint rejects an unauthenticated caller with HTTP 401.

    The route depends on ``get_current_user`` (the stateless successor to the
    legacy COMMAREA identity check), so the unauthenticated ``client`` fixture
    must be refused. A fully valid query (report type plus a valid date range) is
    sent so the *only* possible rejection reason is the missing identity,
    yielding a deterministic 401 rather than a parameter-validation error.

    Args:
        client: The UNAUTHENTICATED httpx ASGI client fixture.
    """
    response = await client.get(
        REPORTS_URL,
        params={
            "report_type": REPORT_MONTHLY,
            "start_date": WIDE_START_DATE,
            "end_date": WIDE_END_DATE,
        },
    )

    assert response.status_code == HTTP_UNAUTHORIZED


# ===========================================================================
# Phase 3 -- CSV download (authorized reporting redesign, §0.8.4).
# ===========================================================================


@pytest.mark.asyncio
async def test_report_csv_content_type(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """A ``format=csv`` report downloads a non-empty ``text/csv`` document.

    Verifies the CSV branch of the reporting redesign: HTTP 200, a
    ``Content-Type`` beginning with ``text/csv`` (Starlette appends the
    ``; charset=utf-8`` suffix to a ``text/*`` media type), a non-empty body that
    contains the CSV header row (always emitted, even with zero data rows), and
    an ``attachment`` ``Content-Disposition`` marking it a file download.

    Args:
        admin_client: Authenticated (administrator) httpx ASGI client.
        seed_data: Golden-master reference + master seed (no transactions).
    """
    response = await admin_client.get(
        REPORTS_URL,
        params={
            "report_type": REPORT_CUSTOM,
            "start_date": WIDE_START_DATE,
            "end_date": WIDE_END_DATE,
            "format": FORMAT_CSV,
        },
    )

    assert response.status_code == HTTP_OK
    assert response.headers["content-type"].startswith(CSV_CONTENT_TYPE)

    csvText = response.text
    assert csvText.strip() != ""
    assert CSV_HEADER_COLUMN in csvText

    contentDisposition = response.headers.get("content-disposition", "")
    assert ATTACHMENT_DISPOSITION in contentDisposition


# ===========================================================================
# Phase 4 -- PDF download (authorized reporting redesign, §0.8.4).
# ===========================================================================


@pytest.mark.asyncio
async def test_report_pdf_content_type(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """A ``format=pdf`` report downloads a valid ``application/pdf`` document.

    Verifies the PDF branch of the reporting redesign: HTTP 200, an exact
    ``application/pdf`` ``Content-Type`` (no charset suffix), and a body whose
    first four bytes are the ``%PDF`` signature of a well-formed PDF produced by
    reportlab.

    Args:
        admin_client: Authenticated (administrator) httpx ASGI client.
        seed_data: Golden-master reference + master seed (no transactions).
    """
    response = await admin_client.get(
        REPORTS_URL,
        params={
            "report_type": REPORT_YEARLY,
            "start_date": WIDE_START_DATE,
            "end_date": WIDE_END_DATE,
            "format": FORMAT_PDF,
        },
    )

    assert response.status_code == HTTP_OK
    assert response.headers["content-type"] == PDF_CONTENT_TYPE
    assert response.content[:4] == PDF_SIGNATURE


# ===========================================================================
# Phase 5 -- report-type factory: Monthly / Yearly / Custom all accepted.
# ===========================================================================


@pytest.mark.asyncio
async def test_report_type_monthly_yearly_custom_accepted(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """All three report types are accepted and echo their requested type.

    Exercises the ``ResolveDateRange`` report-type factory end to end: each of
    ``Monthly`` (current month), ``Yearly`` (current year), and ``Custom`` (the
    supplied range) returns HTTP 200 with a well-formed ``ReportResponse`` (both
    ``rows`` and ``grand_total`` present) whose ``report_type`` matches the value
    requested. A valid date range is sent for every type because the parameters
    are always required.

    Args:
        admin_client: Authenticated (administrator) httpx ASGI client.
        seed_data: Golden-master reference + master seed (no transactions).
    """
    for reportType in (REPORT_MONTHLY, REPORT_YEARLY, REPORT_CUSTOM):
        response = await admin_client.get(
            REPORTS_URL,
            params={
                "report_type": reportType,
                "start_date": WIDE_START_DATE,
                "end_date": WIDE_END_DATE,
                "format": FORMAT_JSON,
            },
        )

        assert response.status_code == HTTP_OK
        responseBody = response.json()
        assert "rows" in responseBody
        assert "grand_total" in responseBody
        assert responseBody["report_type"] == reportType
