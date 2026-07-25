"""Transaction report service.

Ported 1:1 from legacy CICS online program CORPT00C (tx CR00). Implements the
report-type factory (Monthly / Yearly / Custom date ranges). Per the authorized
reporting redesign (§0.8.4), replaces the legacy TDQ/GDG text+HTML batch output
with an on-screen table plus CSV (stdlib csv) and PDF (reportlab) downloads.
Monetary sums are Decimal (never float). See §0.5.1, §0.7.1, §0.8.4.

Legacy source references (REFERENCE only, never modified):
    * app/cbl/CORPT00C.cbl   -- online report-request program (tx CR00). Its
      PROCESS-ENTER-KEY paragraph selects the report range (MONTHLY / YEARLY /
      CUSTOM) and its SUBMIT-JOB-TO-INTRDR paragraph gates on a Y/N confirm
      before submitting the print job. Both behaviors are ported here.
    * app/cpy/CVTRA05Y.cpy    -- TRAN-RECORD (the posted-transaction row whose
      TRAN-AMT S9(09)V99 feeds every report amount and total).
    * app/cpy/CVTRA03Y.cpy    -- TRAN-TYPE-RECORD (type-code description lookup).
    * app/cpy/CVTRA04Y.cpy    -- TRAN-CAT-RECORD (category-code description lookup).

Design patterns applied (AAP 0.4.3):
    * Report-type factory: ``ResolveDateRange`` dispatches on ``ReportType`` to a
      per-type range builder, mirroring the legacy EVALUATE on the MONTHLY /
      YEARLY / CUSTOM screen flags.
    * Report-format factory: ``RenderCsv`` and ``RenderPdf`` are two renderers
      over the single :class:`~app.schemas.report.ReportResponse` payload, the
      modern replacement for the legacy TDQ/GDG text+HTML statement output.

The service is read-only: it never commits, and it never mutates a persisted
row. The caller owns the unit-of-work (the dependency-injected async session).

Ochs Rule (AAP 0.8.2 / 0.8.3): the module/file name is snake_case; the class and
its methods are PascalCase; locals are camelCase; module constants are
ALL_UPPERCASE. Only specific exceptions are caught, never a bare ``except``.
"""

from __future__ import annotations

import calendar
import csv
import io
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal
from typing import Callable, Optional

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.exceptions import DomainValidationError
from app.models.transaction import Transaction
from app.repositories import (
    TransactionCategoryRepository,
    TransactionRepository,
    TransactionTypeRepository,
)
from app.schemas import (
    ReportRequest,
    ReportResponse,
    ReportType,
    TransactionReportRow,
)
from app.utils import csv_safety, date_utils, decimal_utils

# ---------------------------------------------------------------------------
# Report-name labels (Ochs Rule: ALL_UPPERCASE). These are the exact
# WS-REPORT-NAME literals CORPT00C moves for each report range
# ([CORPT00C L214 'Monthly'], [L240 'Yearly'], [L433 'Custom']); they drive the
# confirm prompt and the rendered report title so the modern wording matches the
# legacy screen text.
# ---------------------------------------------------------------------------
MONTHLY_LABEL = "Monthly"
YEARLY_LABEL = "Yearly"
CUSTOM_LABEL = "Custom"

# Suffix appended to the report-name label to form the rendered report title,
# e.g. "Monthly Transaction Report" (CSV/PDF heading of the redesign, §0.8.4).
REPORT_TITLE_SUFFIX = "Transaction Report"

# Accepted confirm tokens after the schema normalizes the CORPT00 CONFIRM X(1)
# flag to upper case ('Y'/'N'/None). CORPT00C only submits the print job when
# CONFIRM = 'Y'/'y' [CORPT00C L478]; 'N'/'n' cancels [L480].
CONFIRM_YES = "Y"
CONFIRM_NO = "N"

# Upper bound on the number of rows a single report may materialize. This is a
# transport/safety concern (NOT a business rule from COBOL): the report query is
# scoped in SQL to POSTED transactions whose effective date falls in the
# requested range (see ``TransactionRepository.ListPostedInDateRange``), and this
# bound caps the worst-case result set so an accidental very-wide custom range
# can never materialize an unbounded number of rows into memory. The AAP sample
# data is tiny; this bound only guards against a pathological range.
MAX_REPORT_ROWS = 100000

# Upper bounds used to batch-load the two small reference tables (transaction
# types and transaction categories) up front, once per report, instead of one
# lookup per distinct code inside the row-build loop. Both tables are bounded by
# tiny code spaces (``tran_type`` is CHAR(2); a category is that type plus a
# 4-char code), so these limits sit far above any real row count and simply mean
# "load every reference row in a single query". Should a table ever exceed its
# bound, the per-row resolvers still fall back to a keyed lookup, so correctness
# never depends on these numbers -- only the query count does.
REFERENCE_TYPE_LIMIT = 1000
REFERENCE_CATEGORY_LIMIT = 10000

# Column headings for the CSV rendering (stdlib csv). One entry per exported
# TransactionReportRow field, in report order.
CSV_HEADER = (
    "Transaction ID",
    "Account ID",
    "Type Code",
    "Type Description",
    "Category Code",
    "Category Description",
    "Source",
    "Amount",
)

# Labels for the totals rows appended to the CSV/PDF renderings. They correspond
# to the CVTRA07Y report control-break lines REPT-PAGE-TOTAL / REPT-ACCOUNT-TOTAL
# / REPT-GRAND-TOTAL (see app/schemas/report.py).
PAGE_TOTAL_LABEL = "Page Total"
ACCOUNT_TOTAL_LABEL = "Account Total"
GRAND_TOTAL_LABEL = "Grand Total"

# Two-decimal money display format. Applied to a Decimal (Decimal implements the
# 'f' format spec with exact decimal arithmetic, so NO float conversion occurs).
MONEY_DISPLAY_FORMAT = "{:.2f}"

# Confirm-prompt fragments. CORPT00C builds 'Please confirm to print the ' +
# WS-REPORT-NAME + ' report...' when CONFIRM is blank [CORPT00C L465-469]; the
# service surfaces the identical text when the print is not positively confirmed.
CONFIRM_PROMPT_PREFIX = "Please confirm to print the "
CONFIRM_PROMPT_SUFFIX = " report..."

# Verbatim validation messages surfaced by the service (Ochs Rule ALL_UPPERCASE
# constant names; the string values are preserved exactly from CORPT00C for
# golden-master parity).
MSG_SELECT_REPORT_TYPE = "Select a report type to print report..."   # [CORPT00C L438]
MSG_START_NOT_VALID_DATE = "Start Date - Not a valid date..."         # [CORPT00C L400]
MSG_END_NOT_VALID_DATE = "End Date - Not a valid date..."             # [CORPT00C L420]

# Defensive message for the (FK-guaranteed impossible) case where a transaction's
# card -- and therefore its owning account id -- cannot be resolved. Not a legacy
# string: the legacy report joined the account id through the sort/symbol file,
# an always-present relationship the PostgreSQL foreign key now enforces.
MSG_ACCT_UNRESOLVED_PREFIX = "Unable to resolve account for transaction "

# Per-field CUSTOM date edits from CORPT00C, kept as a single authoritative,
# traceable catalog. In the modern stack the report request arrives as composed
# ``date`` objects that Pydantic has already validated field-by-field
# (app/schemas/report.py), so these per-part edits are enforced upstream at the
# schema/router boundary that still holds the raw MM/DD/YYYY screen parts. They
# are recorded here verbatim so the mapping from each legacy edit paragraph to
# its modern enforcement point stays documented and a router may reuse the exact
# wording. The service itself enforces the composed-date edits (MSG_*_NOT_VALID_DATE
# above) plus the start<=end ordering (also enforced by the schema).
CUSTOM_DATE_EDIT_MESSAGES = {
    "start_month_empty": "Start Date - Month can NOT be empty...",   # [CORPT00C L261]
    "start_day_empty": "Start Date - Day can NOT be empty...",       # [CORPT00C L268]
    "start_year_empty": "Start Date - Year can NOT be empty...",     # [CORPT00C L275]
    "end_month_empty": "End Date - Month can NOT be empty...",       # [CORPT00C L282]
    "end_day_empty": "End Date - Day can NOT be empty...",           # [CORPT00C L289]
    "end_year_empty": "End Date - Year can NOT be empty...",         # [CORPT00C L296]
    "start_month_invalid": "Start Date - Not a valid Month...",      # [CORPT00C L331]
    "start_day_invalid": "Start Date - Not a valid Day...",          # [CORPT00C L340]
    "start_year_invalid": "Start Date - Not a valid Year...",        # [CORPT00C L348]
    "end_month_invalid": "End Date - Not a valid Month...",          # [CORPT00C L357]
    "end_day_invalid": "End Date - Not a valid Day...",              # [CORPT00C L366]
    "end_year_invalid": "End Date - Not a valid Year...",            # [CORPT00C L374]
}


@dataclass
class _ReportLookups:
    """Per-request memoization caches for the report row build.

    Building a report row needs three reference lookups whose inputs repeat
    heavily across a range of transactions: the owning account id (per card
    number), the transaction-type description (per type code), and the
    transaction-category description (per type+category key). Caching each in a
    dict collapses what would otherwise be one database round-trip per row into
    one per distinct key, without changing any result.

    The object is created fresh inside :meth:`ReportService.GenerateReport` and
    passed to the row builders, so no state leaks between requests. Grouping the
    three caches into this single object also keeps the helper methods within the
    Ochs four-parameter limit.

    Attributes:
        acctIdByCardNum: Maps a 16-char ``card_num`` to its owning 11-char
            ``acct_id`` (resolved via the transaction's ``card`` relationship).
        typeDescByCode: Maps a 2-char ``tran_type_cd`` to its (possibly ``None``)
            type description from ``transaction_type``.
        catDescByKey: Maps a ``(tran_type_cd, tran_cat_cd)`` tuple to its
            (possibly ``None``) category description from ``transaction_category``.
    """

    acctIdByCardNum: dict[str, str] = field(default_factory=dict)
    typeDescByCode: dict[str, Optional[str]] = field(default_factory=dict)
    catDescByKey: dict[tuple[str, str], Optional[str]] = field(default_factory=dict)


class ReportService:
    """Transaction-report business logic (1:1 port of CORPT00C, tx CR00).

    Orchestrates the transaction, transaction-type, and transaction-category
    repositories to produce a :class:`~app.schemas.report.ReportResponse` for a
    requested date range, then renders that response as CSV or PDF. All amounts
    and totals are exact :class:`~decimal.Decimal` values; floating point is
    never used (AAP 0.7.1).

    The class is stateless apart from its (stateless) repository instances, so a
    single instance is safe to construct once and reuse. It never opens or
    commits a session -- every data-access method receives the caller's
    :class:`~sqlalchemy.ext.asyncio.AsyncSession`.
    """

    def __init__(self, clock: Callable[[], date] = date.today) -> None:
        """Wire up the repositories this service depends on.

        Mirrors the legacy program's fixed set of VSAM files: ``TRANSACT``
        (transactions), ``TRANTYPE`` (type descriptions), and ``TRANCATG``
        (category descriptions). The repositories are stateless data-access
        objects; constructing them opens no connection.

        Args:
            clock: A zero-argument callable returning "today" as a ``date``. It
                is injected only so the MONTHLY / YEARLY range builders are
                deterministic under test (a test may pass a frozen clock such as
                ``lambda: date(2026, 7, 21)``); production leaves the default
                :func:`datetime.date.today`, so runtime behavior is unchanged.
                This replaces the previous direct ``date.today()`` calls that the
                report ranges could not be tested against a fixed date (M-08).
        """
        self.transactionRepository = TransactionRepository()
        self.transactionTypeRepository = TransactionTypeRepository()
        self.transactionCategoryRepository = TransactionCategoryRepository()
        self._clock = clock

    # -----------------------------------------------------------------------
    # METHOD 1 -- report-type factory: resolve the inclusive [start, end] range
    # -----------------------------------------------------------------------
    def ResolveDateRange(self, reportRequest: ReportRequest) -> tuple[date, date]:
        """Resolve the inclusive date range for the requested report type.

        Ports the ``PROCESS-ENTER-KEY`` EVALUATE in CORPT00C that maps the
        selected MONTHLY / YEARLY / CUSTOM screen flag to a start and end date.
        Implemented as a small factory that dispatches on
        :class:`~app.schemas.report.ReportType` to a per-type range builder
        (design-pattern application, AAP 0.4.3).

        Args:
            reportRequest: The validated report request carrying the report type
                and (for CUSTOM) the user-supplied start and end dates.

        Returns:
            A ``(startDate, endDate)`` tuple of native ``date`` objects, both
            inclusive.

        Raises:
            DomainValidationError: When the report type is unrecognized
                (``MSG_SELECT_REPORT_TYPE``, [CORPT00C L438]) or, for CUSTOM,
                when a composed date fails the legacy date edit
                (``MSG_START_NOT_VALID_DATE`` / ``MSG_END_NOT_VALID_DATE``).
        """
        rangeFactory = {
            ReportType.MONTHLY: self._MonthlyRange,
            ReportType.YEARLY: self._YearlyRange,
            ReportType.CUSTOM: self._CustomRange,
        }
        rangeBuilder = rangeFactory.get(reportRequest.report_type)
        if rangeBuilder is None:
            raise DomainValidationError(MSG_SELECT_REPORT_TYPE)
        return rangeBuilder(reportRequest)

    def _MonthlyRange(self, reportRequest: ReportRequest) -> tuple[date, date]:
        """Build the current-month range (CORPT00C 'Monthly' branch, L214-234).

        Reproduces the legacy computation: the start is the first day of the
        current month (DD forced to '01') and the end is the last day of the
        current month (the legacy program advances to the first of the next month
        and subtracts one day; ``calendar.monthrange`` yields the same last day).
        The ``reportRequest`` argument is unused for this fixed range but is
        accepted so every factory branch shares one signature.

        Args:
            reportRequest: The report request (unused; present for a uniform
                factory signature).

        Returns:
            The first and last calendar day of the current month.
        """
        today = self._clock()
        lastDay = calendar.monthrange(today.year, today.month)[1]
        startDate = date(today.year, today.month, 1)
        endDate = date(today.year, today.month, lastDay)
        return (startDate, endDate)

    def _YearlyRange(self, reportRequest: ReportRequest) -> tuple[date, date]:
        """Build the current-year range (CORPT00C 'Yearly' branch, L240-251).

        Reproduces the legacy computation: the start is January 1st (MM='01',
        DD='01') and the end is December 31st (MM='12', DD='31') of the current
        year. The ``reportRequest`` argument is unused for this fixed range but
        is accepted for a uniform factory signature.

        Args:
            reportRequest: The report request (unused; present for a uniform
                factory signature).

        Returns:
            January 1st and December 31st of the current year.
        """
        currentYear = self._clock().year
        startDate = date(currentYear, 1, 1)
        endDate = date(currentYear, 12, 31)
        return (startDate, endDate)

    def _CustomRange(self, reportRequest: ReportRequest) -> tuple[date, date]:
        """Return the user-supplied CUSTOM range (CORPT00C 'Custom' branch).

        The report request delivers ``start_date`` / ``end_date`` as composed
        ``date`` objects that Pydantic has already validated field-by-field and
        ordered (start <= end). This method re-applies the legacy date validity
        edit (via :func:`app.utils.date_utils.ValidateDate`) as a defensive
        backstop, surfacing the verbatim 'Not a valid date...' messages
        ([CORPT00C L400 / L420]) if a date somehow fails.

        Args:
            reportRequest: The report request carrying the user-supplied
                ``start_date`` and ``end_date``.

        Returns:
            The user-supplied ``(start_date, end_date)`` tuple.

        Raises:
            DomainValidationError: With the verbatim start/end 'Not a valid
                date...' message when a composed date fails the legacy edit.
        """
        startDate = reportRequest.start_date
        endDate = reportRequest.end_date
        self._AssertValidDate(startDate, MSG_START_NOT_VALID_DATE)
        self._AssertValidDate(endDate, MSG_END_NOT_VALID_DATE)
        return (startDate, endDate)

    def _AssertValidDate(self, dateValue: date, failureMessage: str) -> None:
        """Validate a composed date through the legacy date edit chain.

        Formats the ``date`` back to the legacy ``YYYY-MM-DD`` text and runs it
        through :func:`app.utils.date_utils.ValidateDate`, which reproduces the
        CSUTLDTC / CSUTLDPY century, month, day, month-length, and leap-year
        edits. A composed ``date`` is always a real calendar date, so this is a
        defensive parity check; on the (unexpected) failure it raises the
        supplied verbatim legacy message.

        Args:
            dateValue: The composed date to re-validate.
            failureMessage: The verbatim CORPT00C message to raise on failure.

        Raises:
            DomainValidationError: Carrying ``failureMessage`` when the date
                fails the legacy edit chain.
        """
        validation = date_utils.ValidateDate(date_utils.FormatLegacyDate(dateValue))
        if not validation.isValid:
            raise DomainValidationError(failureMessage)

    # -----------------------------------------------------------------------
    # METHOD 2 -- generate the report (rows + Decimal totals). READ-ONLY.
    # -----------------------------------------------------------------------
    async def GenerateReport(
        self, session: AsyncSession, reportRequest: ReportRequest
    ) -> ReportResponse:
        """Generate the transaction report for the requested range.

        Ports the CORPT00C flow: confirm the print gesture, resolve the date
        range for the selected report type, gather the matching transactions,
        label each with its type/category description and owning account, and
        accumulate the page/account/grand totals. The legacy program then
        submitted a batch print job; per the authorized redesign (§0.8.4) this
        returns a structured payload that drives the on-screen table and the
        CSV/PDF renderers instead.

        This method is strictly read-only: it issues only ``SELECT`` queries
        through the repositories and never commits or mutates a row.

        Args:
            session: The caller-owned async database session (unit-of-work).
            reportRequest: The validated report request.

        Returns:
            A fully populated :class:`~app.schemas.report.ReportResponse`.

        Raises:
            DomainValidationError: When the print is not positively confirmed,
                the report type is unrecognized, or a CUSTOM date fails the
                legacy edit.
        """
        self._CheckConfirmation(reportRequest)
        startDate, endDate = self.ResolveDateRange(reportRequest)
        transactions = await self._FetchTransactionsInRange(session, startDate, endDate)
        lookups = _ReportLookups()
        await self._PreloadDescriptions(session, lookups)
        reportRows: list[TransactionReportRow] = []
        for transaction in transactions:
            reportRows.append(self._BuildRow(transaction, lookups))
        # Order rows by owning account (then transaction id) so the CVTRA07Y
        # account control break groups each account's rows contiguously. This
        # makes the account subtotal deterministic (each account forms exactly
        # one adjacent group) instead of depending on the order rows happen to
        # arrive in, which previously left the account total holding only the
        # last adjacent run of the final account (M-08).
        reportRows.sort(key=self._ControlBreakSortKey)
        pageTotal, accountTotal, grandTotal = self._AccumulateTotals(reportRows)
        return ReportResponse(
            report_type=reportRequest.report_type,
            start_date=startDate,
            end_date=endDate,
            report_name=f"{reportRequest.report_type.value} {REPORT_TITLE_SUFFIX}",
            rows=reportRows,
            page_total=pageTotal,
            account_total=accountTotal,
            grand_total=grandTotal,
        )

    async def _PreloadDescriptions(
        self, session: AsyncSession, lookups: _ReportLookups
    ) -> None:
        """Batch-load the type/category reference tables into the row caches.

        The transaction-type and transaction-category tables are tiny fixed
        reference sets (``TRANTYPE`` / ``TRANCATG``). Loading each in full with a
        single ``SELECT`` and seeding the per-request caches here means every
        :meth:`_ResolveTypeDesc` / :meth:`_ResolveCatDesc` call in the row-build
        loop is a pure in-memory hit, rather than issuing one keyed query per
        distinct code the first time it is seen (the report N+1). This changes
        only the query count, not the result: the caches hold exactly the
        descriptions the per-row resolvers would have fetched, and a code that is
        somehow absent from its reference table still falls through to the
        resolver's keyed lookup and yields the same ``None`` it always did.

        The owning account id (per card) is not preloaded here -- it is supplied
        by the :func:`~sqlalchemy.orm.selectinload` eager load on the report
        query (:meth:`TransactionRepository.ListPostedInDateRange`), which
        batch-loads every card in one round-trip.

        Args:
            session: The caller-owned async database session.
            lookups: The per-request caches to seed in place.
        """
        typeRows = await self.transactionTypeRepository.ListTransactionTypes(
            session, limit=REFERENCE_TYPE_LIMIT
        )
        for typeRow in typeRows:
            lookups.typeDescByCode[typeRow.tran_type] = typeRow.tran_type_desc
        categoryRows = await self.transactionCategoryRepository.ListTransactionCategories(
            session, limit=REFERENCE_CATEGORY_LIMIT
        )
        for categoryRow in categoryRows:
            categoryKey = (categoryRow.tran_type_cd, categoryRow.tran_cat_cd)
            lookups.catDescByKey[categoryKey] = categoryRow.tran_cat_type_desc

    def _CheckConfirmation(self, reportRequest: ReportRequest) -> None:
        """Enforce the CORPT00C print-confirm gesture (SUBMIT-JOB-TO-INTRDR).

        The schema normalizes the CONFIRM X(1) flag to ``'Y'`` / ``'N'`` /
        ``None``. CORPT00C only submits the print job when CONFIRM = 'Y'/'y'
        [CORPT00C L478]; a blank flag re-prompts 'Please confirm to print the
        <name> report...' [L464-469] and 'N'/'n' cancels [L480]. Here an explicit
        decline ('N') blocks generation with that same verbatim prompt, while an
        absent flag (``None``) proceeds so a router may own the confirm gesture
        (a two-step confirm re-sends the request with ``confirm='Y'``).

        Args:
            reportRequest: The report request whose ``confirm`` flag is checked.

        Raises:
            DomainValidationError: With the verbatim 'Please confirm to print
                the <name> report...' text when the print is explicitly declined.
        """
        if reportRequest.confirm == CONFIRM_NO:
            reportName = reportRequest.report_type.value
            message = f"{CONFIRM_PROMPT_PREFIX}{reportName}{CONFIRM_PROMPT_SUFFIX}"
            raise DomainValidationError(message)

    async def _FetchTransactionsInRange(
        self, session: AsyncSession, startDate: date, endDate: date
    ) -> list[Transaction]:
        """Gather every POSTED transaction whose effective date is in the range.

        The date range and the POSTED-status filter are pushed to SQL (see
        :meth:`TransactionRepository.ListPostedInDateRange`), so the query
        materializes only the posted rows whose effective date
        (``COALESCE(proc_ts, orig_ts)``) falls in the inclusive
        ``[startDate, endDate]`` window -- the modern equivalent of the legacy
        report's TRAN-PROC-DT selection over posted ledger rows. This replaces
        the former full-table keyset walk that fetched every row and filtered by
        date in Python (unbounded materialization; no status filter), and it
        bounds the result at ``MAX_REPORT_ROWS`` (M-08).

        The Python inclusive-window check (:meth:`_CollectInRange`) is retained
        as an exact backstop: under the container's UTC session the SQL
        ``COALESCE(...)::date`` filter and the Python ``.date()`` check agree, so
        it removes nothing the SQL kept, but it keeps the inclusive-window rule
        explicit and independent of the database's date-cast semantics.

        Args:
            session: The caller-owned async database session.
            startDate: Inclusive range start.
            endDate: Inclusive range end.

        Returns:
            The matching POSTED transactions in ascending ``tran_id`` order.
        """
        candidates = await self.transactionRepository.ListPostedInDateRange(
            session, startDate, endDate, limit=MAX_REPORT_ROWS
        )
        matched: list[Transaction] = []
        self._CollectInRange(candidates, startDate, endDate, matched)
        return matched

    def _CollectInRange(
        self,
        transactions: list[Transaction],
        startDate: date,
        endDate: date,
        matched: list[Transaction],
    ) -> None:
        """Append the transactions whose effective date is within the range.

        Extracted from the paging loop to keep it small (Ochs Rule) and to make
        the inclusive-window predicate explicit and testable.

        Args:
            transactions: One page of candidate transactions.
            startDate: Inclusive range start.
            endDate: Inclusive range end.
            matched: The accumulator that in-range transactions are appended to.
        """
        for transaction in transactions:
            effectiveDate = self._EffectiveDate(transaction)
            if effectiveDate is not None and startDate <= effectiveDate <= endDate:
                matched.append(transaction)

    def _EffectiveDate(self, transaction: Transaction) -> Optional[date]:
        """Return the date a transaction is filtered by, or ``None`` if unknown.

        The legacy report selected on the processing timestamp (TRAN-PROC-TS ->
        TRAN-PROC-DT); this uses ``proc_ts`` when present and falls back to
        ``orig_ts`` otherwise. Both columns are nullable, so an unset pair yields
        ``None`` (the transaction is then excluded from every range).

        Args:
            transaction: The transaction whose effective date is derived.

        Returns:
            The processing (or, failing that, origination) ``date``, or ``None``.
        """
        effectiveTimestamp: Optional[datetime] = transaction.proc_ts or transaction.orig_ts
        if effectiveTimestamp is None:
            return None
        return effectiveTimestamp.date()

    def _BuildRow(
        self, transaction: Transaction, lookups: _ReportLookups
    ) -> TransactionReportRow:
        """Build one report row from a transaction plus its reference lookups.

        Resolves the owning account id and the type/category descriptions from
        the per-request caches, all populated before the row-build loop -- the
        card via the report query's ``selectinload`` eager load and the
        type/category descriptions via :meth:`_PreloadDescriptions`. Every
        resolve is therefore a pure in-memory read, so the loop issues no
        database queries at all (the report N+1 is eliminated). The description
        widths (<=15 / <=29) and the exact-``Decimal`` amount are enforced by the
        schema validators, so this method passes the raw values straight through.

        Args:
            transaction: The source transaction row (with ``card`` eager-loaded).
            lookups: The per-request lookup caches, fully populated up front.

        Returns:
            The assembled report row.
        """
        acctId = self._ResolveAcctId(transaction, lookups)
        typeDesc = self._ResolveTypeDesc(transaction, lookups)
        catDesc = self._ResolveCatDesc(transaction, lookups)
        return TransactionReportRow(
            tran_id=transaction.tran_id,
            acct_id=acctId,
            tran_type_cd=transaction.tran_type_cd,
            tran_type_desc=typeDesc,
            tran_cat_cd=transaction.tran_cat_cd,
            tran_cat_desc=catDesc,
            tran_source=transaction.tran_source,
            tran_amt=transaction.tran_amt,
        )

    def _ResolveAcctId(
        self, transaction: Transaction, lookups: _ReportLookups
    ) -> str:
        """Resolve (and cache) the account id owning a transaction's card.

        The transaction record carries ``card_num`` but not the account id (the
        legacy report joined it through the sort/symbol file). The owning account
        is read from the transaction's eager-loaded ``card`` relationship: the
        report query batch-loads every card in a single round-trip with
        :func:`~sqlalchemy.orm.selectinload`
        (:meth:`TransactionRepository.ListPostedInDateRange`), so the related row
        is already present and is read here as a plain attribute access -- no
        per-card ``SELECT`` and no forbidden async lazy-load. This replaces the
        former per-transaction :meth:`AsyncSession.refresh`, which issued one
        query for every distinct card (the report N+1); the resolved
        ``card.acct_id`` is identical, only the query count changes. The value is
        still memoized by card number so each card's attribute is touched once.

        Args:
            transaction: The transaction whose owning account is resolved.
            lookups: The per-request memoization caches.

        Returns:
            The 11-character ``acct_id`` owning the transaction's card.

        Raises:
            DomainValidationError: When the card (and therefore the account id)
                cannot be resolved -- a foreign-key-guaranteed impossibility that
                is nonetheless handled defensively.
        """
        cardNum = transaction.card_num
        if cardNum in lookups.acctIdByCardNum:
            return lookups.acctIdByCardNum[cardNum]
        card = transaction.card
        if card is None:
            raise DomainValidationError(
                f"{MSG_ACCT_UNRESOLVED_PREFIX}{transaction.tran_id}"
            )
        lookups.acctIdByCardNum[cardNum] = card.acct_id
        return card.acct_id

    def _ResolveTypeDesc(
        self, transaction: Transaction, lookups: _ReportLookups
    ) -> Optional[str]:
        """Return a transaction's type description from the preloaded cache.

        :meth:`_PreloadDescriptions` loads the whole ``transaction_type``
        reference table into ``lookups.typeDescByCode`` before the row-build
        loop, so this is a pure dict read: a known code yields its description
        and an unknown code yields ``None`` (the schema renders a blank
        description column, matching an unlabeled legacy code). Because the cache
        already holds every reference row, no per-row query is issued -- the
        previous per-distinct-code ``GetByTranType`` fallback is gone while the
        returned value is unchanged.

        Args:
            transaction: The transaction whose type description is resolved.
            lookups: The per-request caches, preloaded with all type rows.

        Returns:
            The type description, or ``None`` when the code has no reference row.
        """
        return lookups.typeDescByCode.get(transaction.tran_type_cd)

    def _ResolveCatDesc(
        self, transaction: Transaction, lookups: _ReportLookups
    ) -> Optional[str]:
        """Return a transaction's category description from the preloaded cache.

        :meth:`_PreloadDescriptions` loads the whole ``transaction_category``
        reference table into ``lookups.catDescByKey`` (keyed by the composite
        ``(tran_type_cd, tran_cat_cd)``) before the row-build loop, so this is a
        pure dict read: a known pairing yields its description and an unknown
        pairing yields ``None``. Because the cache already holds every reference
        row, no per-row query is issued -- the previous per-distinct-pairing
        ``GetByKey`` fallback is gone while the returned value is unchanged.

        Args:
            transaction: The transaction whose category description is resolved.
            lookups: The per-request caches, preloaded with all category rows.

        Returns:
            The category description, or ``None`` when the pairing has no
            reference row.
        """
        return lookups.catDescByKey.get(
            (transaction.tran_type_cd, transaction.tran_cat_cd)
        )

    def _ControlBreakSortKey(
        self, row: TransactionReportRow
    ) -> tuple[str, str]:
        """Return the ``(acct_id, tran_id)`` sort key for control-break ordering.

        Rows are sorted by owning account first, then transaction id, so every
        account's rows are contiguous for the CVTRA07Y account control break
        (and the ordering is fully deterministic for identical inputs).

        Args:
            row: The report row whose sort key is derived.

        Returns:
            A ``(acct_id, tran_id)`` tuple used as the stable sort key.
        """
        return (row.acct_id, row.tran_id)

    def _AccumulateTotals(
        self, reportRows: list[TransactionReportRow]
    ) -> tuple[Decimal, Decimal, Decimal]:
        """Accumulate the page, account, and grand totals as exact ``Decimal``.

        Reproduces the CVTRA07Y report control-break totals over the single
        returned payload. The rows are pre-sorted by ``(acct_id, tran_id)`` in
        :meth:`GenerateReport`, so each account forms exactly one contiguous
        control-break group: the grand total sums every row; the page total
        equals the grand total (the redesign returns all rows as one logical
        page); and the account total is the complete subtotal of the final
        account group (the accumulator resets on each account change and, because
        the rows are account-ordered, ends holding that last account's full
        subtotal deterministically rather than only its last adjacent run).
        Every running sum starts from ``Decimal('0')`` and each amount is routed
        through :func:`app.utils.decimal_utils.ToDecimal`; the finalized totals
        are truncated to cents with
        :func:`app.utils.decimal_utils.TruncateToCents`. Floating point is never
        used.

        Args:
            reportRows: The account-ordered report rows to total.

        Returns:
            A ``(pageTotal, accountTotal, grandTotal)`` tuple of ``Decimal``.
        """
        grandTotal = Decimal("0")
        accountTotal = Decimal("0")
        lastAcctId: Optional[str] = None
        for row in reportRows:
            amount = decimal_utils.ToDecimal(row.tran_amt)
            grandTotal += amount
            if row.acct_id != lastAcctId:
                accountTotal = Decimal("0")
                lastAcctId = row.acct_id
            accountTotal += amount
        pageTotal = grandTotal
        return (
            decimal_utils.TruncateToCents(pageTotal),
            decimal_utils.TruncateToCents(accountTotal),
            decimal_utils.TruncateToCents(grandTotal),
        )

    # -----------------------------------------------------------------------
    # METHOD 3 -- CSV rendering (authorized redesign, §0.8.4; stdlib csv)
    # -----------------------------------------------------------------------
    def RenderCsv(self, report: ReportResponse) -> str:
        """Render a report as CSV text using the standard-library ``csv`` module.

        Part of the authorized reporting redesign (§0.8.4): the legacy TDQ/GDG
        text+HTML statement output is replaced by a downloadable CSV built with
        ``csv.writer`` over an in-memory :class:`io.StringIO` (no third-party CSV
        library). The header row is followed by one row per report row and then
        the page/account/grand total rows. Monetary values are formatted from
        their ``Decimal`` with :meth:`_FormatMoney` (never via ``float``). The
        caller (router) streams the returned text as a file download.

        Args:
            report: The generated report payload to render.

        Returns:
            The complete CSV document as a single ``str``.
        """
        buffer = io.StringIO()
        # Wrap the stdlib csv.writer in the SafeCsvWriter so every emitted cell
        # is passed through CSV formula-injection neutralization (CWE-1236):
        # any free-text field (type/category description, source) that begins
        # with a spreadsheet formula trigger character is prefixed with an
        # apostrophe, while numeric amounts -- including negative values -- pass
        # through byte-for-byte unchanged (M-07; AAP 0.7.1 exact decimals).
        writer = csv_safety.SafeCsvWriter(csv.writer(buffer))
        writer.writerow(CSV_HEADER)
        for row in report.rows:
            writer.writerow(self._CsvDataRow(row))
        for label, amount in self._TotalRows(report):
            writer.writerow(self._CsvTotalRow(label, amount))
        return buffer.getvalue()

    def _CsvDataRow(self, row: TransactionReportRow) -> tuple[str, ...]:
        """Build one CSV data row from a report row (blank for absent optionals).

        Args:
            row: The report row to serialize.

        Returns:
            An 8-column tuple aligned with ``CSV_HEADER``.
        """
        return (
            row.tran_id,
            row.acct_id,
            row.tran_type_cd,
            row.tran_type_desc or "",
            row.tran_cat_cd,
            row.tran_cat_desc or "",
            row.tran_source or "",
            self._FormatMoney(row.tran_amt),
        )

    def _CsvTotalRow(self, label: str, amount: Decimal) -> tuple[str, ...]:
        """Build a labeled CSV totals row aligned to the last two columns.

        Args:
            label: The total label (page / account / grand).
            amount: The total amount to format.

        Returns:
            An 8-column tuple with the label and formatted amount in the final
            two columns and the rest blank.
        """
        return ("", "", "", "", "", "", label, self._FormatMoney(amount))

    # -----------------------------------------------------------------------
    # METHOD 4 -- PDF rendering (authorized redesign, §0.8.4; reportlab)
    # -----------------------------------------------------------------------
    def RenderPdf(self, report: ReportResponse) -> bytes:
        """Render a report as a tabular PDF document using ``reportlab``.

        The second renderer of the report-format factory (§0.4.3) and the other
        half of the authorized reporting redesign (§0.8.4). Builds a titled,
        tabular PDF -- title (report name), date-range subtitle, a table of the
        report rows under ``CSV_HEADER`` headings, and the page/account/grand
        total rows -- and returns the document as ``bytes`` for the router to
        stream as a download. ``reportlab`` is imported lazily so merely
        importing this module never pulls in the PDF toolkit. Monetary values are
        formatted from their ``Decimal`` (never via ``float``).

        Args:
            report: The generated report payload to render.

        Returns:
            The complete PDF document as ``bytes``.
        """
        from reportlab.lib import colors
        from reportlab.lib.pagesizes import landscape, letter
        from reportlab.lib.styles import getSampleStyleSheet
        from reportlab.platypus import (
            Paragraph,
            SimpleDocTemplate,
            Spacer,
            Table,
            TableStyle,
        )

        buffer = io.BytesIO()
        document = SimpleDocTemplate(buffer, pagesize=landscape(letter))
        styleSheet = getSampleStyleSheet()
        titleText = report.report_name or f"{report.report_type.value} {REPORT_TITLE_SUFFIX}"
        subtitleText = f"Date range: {report.start_date} to {report.end_date}"
        reportTable = Table(self._PdfTableData(report))
        reportTable.setStyle(TableStyle(self._PdfTableStyleSpec(colors)))
        story = [
            Paragraph(titleText, styleSheet["Title"]),
            Paragraph(subtitleText, styleSheet["Normal"]),
            Spacer(1, 12),
            reportTable,
        ]
        document.build(story)
        return buffer.getvalue()

    def _PdfTableData(self, report: ReportResponse) -> list[tuple[str, ...]]:
        """Build the PDF table matrix (header, data rows, totals) as plain data.

        Returns only built-in tuples/strings -- it never touches ``reportlab`` --
        so the table content can be assembled and unit-tested without the PDF
        toolkit, and both renderers share the same row/total formatting.

        Args:
            report: The report payload whose rows and totals populate the table.

        Returns:
            A list of 8-column string tuples: the header, one row per report
            row, then the three total rows.
        """
        tableData: list[tuple[str, ...]] = [CSV_HEADER]
        for row in report.rows:
            tableData.append(self._CsvDataRow(row))
        for label, amount in self._TotalRows(report):
            tableData.append(self._CsvTotalRow(label, amount))
        return tableData

    def _PdfTableStyleSpec(self, colorsModule: object) -> list:
        """Build the reportlab TableStyle command list for the report table.

        Isolated so ``RenderPdf`` stays small and the styling is stated once. The
        header band uses the Material UI primary color (``#1976d2``, AAP 0.3.2)
        for visual continuity with the web UI; the amount column is right-aligned.

        Args:
            colorsModule: The lazily-imported ``reportlab.lib.colors`` module.

        Returns:
            The list of reportlab ``TableStyle`` command tuples.
        """
        return [
            ("BACKGROUND", (0, 0), (-1, 0), colorsModule.HexColor("#1976d2")),
            ("TEXTCOLOR", (0, 0), (-1, 0), colorsModule.white),
            ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
            ("ALIGN", (-1, 0), (-1, -1), "RIGHT"),
            ("GRID", (0, 0), (-1, -1), 0.5, colorsModule.grey),
            ("FONTSIZE", (0, 0), (-1, -1), 8),
        ]

    def _TotalRows(
        self, report: ReportResponse
    ) -> tuple[tuple[str, Decimal], ...]:
        """Return the ordered (label, amount) pairs for the three total rows.

        Shared by the CSV and PDF renderers so the totals footer stays identical
        across both output formats.

        Args:
            report: The report payload carrying the three totals.

        Returns:
            The page, account, and grand total ``(label, amount)`` pairs.
        """
        return (
            (PAGE_TOTAL_LABEL, report.page_total),
            (ACCOUNT_TOTAL_LABEL, report.account_total),
            (GRAND_TOTAL_LABEL, report.grand_total),
        )

    def _FormatMoney(self, value: Decimal) -> str:
        """Format a ``Decimal`` amount to two decimals without using ``float``.

        Applies ``MONEY_DISPLAY_FORMAT`` directly to the ``Decimal``. Python's
        ``Decimal.__format__`` evaluates the ``'f'`` presentation type with exact
        decimal arithmetic, so no binary floating-point value is ever created
        (AAP 0.7.1).

        Args:
            value: The monetary amount to format.

        Returns:
            The amount as a fixed two-decimal string, e.g. ``"-919.00"``.
        """
        return MONEY_DISPLAY_FORMAT.format(value)


__all__ = ["ReportService"]
