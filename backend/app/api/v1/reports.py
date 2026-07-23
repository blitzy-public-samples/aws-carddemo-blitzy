"""Transaction report API router (CICS online program ``CORPT00C``, tx ``CR00``).

Modern FastAPI replacement for the legacy CICS online program ``CORPT00C``
("Print Transaction reports by submitting batch job from online using extra
partition TDQ"), CICS transaction id ``CR00``, BMS map ``CORPT00``
(``app/bms/CORPT00.bms``). Traceability per AAP 0.5.1 / 0.8.1.

Authorized reporting redesign (AAP 0.4.4 / 0.8.4): the legacy program wrote its
output to a TDQ/GDG text+HTML statement dataset submitted as a batch job to the
internal reader. This single, explicitly authorized behavior-adjacent change
replaces that channel with three synchronous representations of the *same*
report payload -- an on-screen table (JSON, the default), a downloadable CSV,
and a downloadable PDF -- selected by the ``format`` query parameter.

Layering (AAP 0.4.1): this module is a THIN router. It performs no business
logic and touches no database. Report-type resolution (the MONTHLY / YEARLY /
CUSTOM date-range factory ported from the legacy ``PROCESS-ENTER-KEY`` EVALUATE),
the transaction aggregation, the running page/account/grand totals, and the
CSV/PDF rendering all live in
:class:`app.services.report_service.ReportService`; the router only forwards the
validated request and returns the chosen representation. Domain errors
(:class:`~app.core.exceptions.DomainValidationError` -> HTTP 400/422 and
:class:`~app.core.exceptions.NotFoundError` -> HTTP 404) are raised by the
service layer and translated centrally by the ``app.main`` exception handlers,
so this module deliberately contains no ``try`` / ``except``.

Ochs Rule (AAP 0.8.2 / 0.8.3): the module/file name stays snake_case while the
route handler is PascalCase (``GetTransactionReport``), local variables are
camelCase (``reportRequest``, ``csvText``, ``pdfBytes``), and module constants
are ALL_UPPERCASE. The handler binds its query parameters into a single
:class:`~app.schemas.report.ReportRequest` DTO so it stays within the Ochs
four-parameter limit, and no secret or URL is hardcoded.

Authorization model (AAP 0.4.4 / 0.8.1 -- role-based, NOT per-user ownership):
authorization here is OPERATOR/ROLE-based, preserved faithfully from the legacy
system. The security record ``CSUSR01Y`` binds an operator only to a type
('A'/'U'), never to a set of accounts/customers, so every caller is
authenticated (``get_current_user``) and admin-only surfaces are gated on
``user_type == 'A'`` (``require_admin``) exactly as AAP 0.4.4 requires, WITHOUT
per-user resource-ownership filtering. Adding a user->account ownership binding
would invent a relation absent from the copybooks and the frozen AAP, breaking
the Minimal Change Clause (AAP 0.8.1); a review finding requesting IDOR-style
ownership or account scoping is therefore declined on AAP grounds (documented
decision -- see ``app.api.v1.cards`` and the resolution report).
"""

from enum import Enum

from fastapi import APIRouter, Depends, Query
from fastapi.responses import Response, StreamingResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.dependencies import get_current_user, get_db
from app.schemas import ReportRequest, ReportResponse
from app.services import ReportService

# ---------------------------------------------------------------------------
# Download rendering constants (Ochs Rule: ALL_UPPERCASE). The CSV and PDF
# representations are returned as browser file downloads, so each carries an
# explicit media type and an ``attachment`` ``Content-Disposition`` naming the
# saved file. These attachment file names are the only literal strings the
# router emits and are acceptable, non-sensitive literals per the agent
# contract (they are not secrets or URLs).
# ---------------------------------------------------------------------------
CSV_MEDIA_TYPE = "text/csv"
PDF_MEDIA_TYPE = "application/pdf"
CONTENT_DISPOSITION_HEADER = "Content-Disposition"
CSV_CONTENT_DISPOSITION = "attachment; filename=transaction_report.csv"
PDF_CONTENT_DISPOSITION = "attachment; filename=transaction_report.pdf"


class ReportFormat(str, Enum):
    """The representation of the transaction report requested by the caller.

    Models the authorized reporting redesign (AAP 0.4.4 / 0.8.4) as a closed set
    of output formats, so an unrecognized value is rejected automatically by
    FastAPI (HTTP 422) with no manual validation in the handler:

    * ``JSON`` -- the default; the structured payload that drives the on-screen
      Material UI table.
    * ``CSV`` -- a downloadable comma-separated rendering.
    * ``PDF`` -- a downloadable tabular PDF rendering.

    Subclassing ``str`` keeps each member interchangeable with its lowercase
    wire value (``"json"`` / ``"csv"`` / ``"pdf"``) in the query string.
    """

    JSON = "json"
    CSV = "csv"
    PDF = "pdf"


router = APIRouter(prefix="/reports", tags=["reports"])


@router.get("/transactions", response_model=None)
async def GetTransactionReport(
    session: AsyncSession = Depends(get_db),
    reportRequest: ReportRequest = Depends(),
    currentUser=Depends(get_current_user),
    reportFormat: ReportFormat = Query(
        ReportFormat.JSON,
        alias="format",
        description=(
            "Report representation to return: 'json' (default, on-screen "
            "table), 'csv' (download), or 'pdf' (download)."
        ),
    ),
) -> ReportResponse | Response:
    """Generate the transaction report and return it in the requested format.

    Modern replacement for ``CORPT00C`` (tx ``CR00``). The MONTHLY / YEARLY /
    CUSTOM report-type factory, the transaction aggregation, and the running
    page / account / grand totals are all resolved inside
    :meth:`app.services.report_service.ReportService.GenerateReport`; this
    handler only forwards the validated request and then selects a
    representation. The three ``reportFormat`` branches realize the authorized
    reporting redesign (AAP 0.4.4 / 0.8.4): JSON drives the on-screen table
    while CSV and PDF are returned as file downloads.

    The route intentionally declares ``response_model=None`` and a
    ``ReportResponse | Response`` return type because it returns more than one
    representation: for JSON it returns the
    :class:`~app.schemas.report.ReportResponse` model directly (FastAPI
    serializes it), while for CSV and PDF it returns raw streaming / binary
    responses that must bypass response-model coercion.

    Args:
        session: The request-scoped async database session, injected by
            :func:`app.core.dependencies.get_db` and passed straight through to
            the service (the router opens no session and runs no query itself).
        reportRequest: The validated report request (report type plus the
            inclusive start / end dates and the optional confirm flag), bound
            from the query string into a
            :class:`~app.schemas.report.ReportRequest` so the handler stays
            within the Ochs four-parameter limit.
        currentUser: The authenticated user resolved by
            :func:`app.core.dependencies.get_current_user`; its presence
            enforces authentication on this route (the stateless successor to
            the legacy COMMAREA identity check).
        reportFormat: The requested representation (``json`` / ``csv`` /
            ``pdf``), taken from the ``format`` query parameter and defaulting to
            JSON.

    Returns:
        The :class:`~app.schemas.report.ReportResponse` for the JSON
        representation, a :class:`~fastapi.responses.StreamingResponse` carrying
        ``text/csv`` for the CSV download, or a
        :class:`~fastapi.responses.Response` carrying ``application/pdf`` for the
        PDF download.

    Raises:
        DomainValidationError: Propagated from the service when the request is
            invalid (for example an unparseable or out-of-order date range);
            translated to HTTP 400 / 422 by the ``app.main`` handlers.
        NotFoundError: Propagated from the service when a referenced record is
            absent; translated to HTTP 404 by the ``app.main`` handlers.
    """
    reportService = ReportService()
    report = await reportService.GenerateReport(session, reportRequest)
    if reportFormat is ReportFormat.CSV:
        csvText = reportService.RenderCsv(report)
        return StreamingResponse(
            iter([csvText]),
            media_type=CSV_MEDIA_TYPE,
            headers={CONTENT_DISPOSITION_HEADER: CSV_CONTENT_DISPOSITION},
        )
    elif reportFormat is ReportFormat.PDF:
        pdfBytes = reportService.RenderPdf(report)
        return Response(
            content=pdfBytes,
            media_type=PDF_MEDIA_TYPE,
            headers={CONTENT_DISPOSITION_HEADER: PDF_CONTENT_DISPOSITION},
        )
    else:
        return report
