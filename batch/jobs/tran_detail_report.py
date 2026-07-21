# Ported from legacy COBOL batch program CBTRN03C.cbl (CardDemo). Function:
# date-range transaction detail report grouped by card/account with
# page/account/grand totals.
"""Date-range transaction detail report (port of COBOL ``CBTRN03C``).

This batch job reproduces the CardDemo daily transaction detail report. The
legacy program read the ``TRANSACT`` VSAM file sequentially, filtered rows by a
``DATEPARM`` start/end date, control-broke on the card number, and wrote a
banded report with three levels of totals (page, per-account, and grand). This
Python port preserves that behavior against the modern PostgreSQL schema, while
the physical *layout* is intentionally redesigned as machine-readable CSV per
the AAP (the columns and the three total levels are preserved; the fixed 133-
byte print line is not).

Report structure (mirrors ``CVTRA07Y``):

* A title row and a ``Date Range`` row (the configured start/end, or ``ALL``).
* A repeating 8-column detail band -- ``TransID``, ``AccountID``, ``TypeCd``,
  ``TypeDesc``, ``CatCd``, ``CatDesc``, ``Source``, ``Amount`` -- reproducing
  the legacy ``1120-WRITE-DETAIL`` paragraph.
* Three total levels, all accumulated as exact :class:`~decimal.Decimal`:
  a **page total** emitted every :data:`PAGE_SIZE` detail lines (with the
  column header re-printed for the next page), a **per-account total** emitted
  on each card control break, and a single **grand total** at the end.

Fidelity notes (see AAP sections 0.7.1, 0.8.1):

* The date filter matches the legacy string comparison on ``TRAN-PROC-TS(1:10)``
  exactly: the ``YYYY-MM-DD`` date portion of ``proc_ts`` must fall between the
  start and end dates **inclusive**. ISO date strings compare chronologically,
  so the comparison is performed on ``proc_ts.isoformat()[:10]``.
* Every monetary total is a :class:`~decimal.Decimal`; floating point is never
  used, preserving regulatory numeric parity.
* The card number is sensitive data: wherever it surfaces (the per-account
  total band) it is masked to its last four characters. The card verification
  value is never referenced or emitted.

Transaction ownership:
    The caller owns the unit of work. :func:`ReportTransactionDetail` receives
    an already-open, caller-owned :class:`~sqlalchemy.orm.Session` (typically
    from ``batch.db.GetSyncSession``), performs only reads, and never calls
    ``commit``/``rollback``/``close`` and never issues DDL. The report is
    therefore inherently idempotent; when a file is written it uses a
    deterministic filename so re-runs regenerate cleanly.

Coding conventions:
    Per the Ochs Rule, public callables use PascalCase, local variables use
    camelCase, and module constants use ALL_UPPERCASE; file/module names remain
    snake_case (AAP 0.8.3).

Example:
    The caller obtains an open, caller-owned session from the batch package's
    synchronous session factory and owns its transaction boundary. Generate the
    report for a single month into ``./reports``::

        with sessionFactory() as session:  # caller-owned unit of work
            written = ReportTransactionDetail(
                session,
                outputDir="./reports",
                dateRange=("2023-01-01", "2023-01-31"),
            )
        # ``written`` is the number of detail lines emitted.
"""

from __future__ import annotations

import csv
import logging
from decimal import Decimal
from pathlib import Path
from typing import Protocol

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.transaction import Transaction
from app.models.card_xref import CardXref
from app.models.transaction_type import TransactionType
from app.models.transaction_category import TransactionCategory

# Module logger. Named for this module so batch log configuration can target it.
LOGGER = logging.getLogger(__name__)

__all__ = ["ReportTransactionDetail", "PAGE_SIZE"]


# ---------------------------------------------------------------------------
# Constants (ALL_UPPERCASE per the Ochs Rule)
# ---------------------------------------------------------------------------

# Detail lines per page before a page total is emitted and the header re-printed
# (legacy ``WS-PAGE-SIZE PIC 9(03) VALUE 20``).
PAGE_SIZE = 20

# Human-readable report title (legacy ``REPT-LONG-NAME`` in CVTRA07Y).
REPORT_TITLE = "Daily Transaction Report"

# Deterministic output filename so re-runs overwrite rather than accumulate.
REPORT_FILE_NAME = "tran_detail_report.csv"

# Sentinel shown in the header when no date range is supplied (report all rows).
ALL_DATES_LABEL = "ALL"

# Row labels for the three total bands (legacy REPORT-*-TOTALS structures).
PAGE_TOTAL_LABEL = "Page Total"
ACCOUNT_TOTAL_LABEL = "Account Total"
GRAND_TOTAL_LABEL = "Grand Total"

# The eight detail columns, in the exact order of legacy ``1120-WRITE-DETAIL``.
REPORT_COLUMNS = (
    "TransID",
    "AccountID",
    "TypeCd",
    "TypeDesc",
    "CatCd",
    "CatDesc",
    "Source",
    "Amount",
)

# Number of columns; used to right-align total amounts under the Amount column.
COLUMN_COUNT = len(REPORT_COLUMNS)

# The exact-zero starting value for every Decimal accumulator (never float).
ZERO_AMOUNT = Decimal("0")

# Quantum used to render amounts with a fixed two-decimal scale.
AMOUNT_QUANTUM = Decimal("0.01")


# ---------------------------------------------------------------------------
# Small collaborators
# ---------------------------------------------------------------------------


class _RowSink(Protocol):
    """Structural type for the row sink shared by the report writers.

    Both :class:`csv.writer` instances and :class:`_NullWriter` satisfy this
    single-method interface, so the report band helpers can be written once and
    driven by either sink (persist-to-CSV or discard-and-count).
    """

    def writerow(self, row: list[str]) -> object:
        """Consume one already-formatted report row."""
        ...


class _ReportState:
    """Mutable running-total and control-break state for one report run.

    Grouping the accumulators and control-break bookkeeping into a single object
    lets every report helper stay within the Ochs four-parameter limit while
    threading shared state through the run.

    Attributes:
        pageTotal: Running total for the current page (reset each page).
        accountTotal: Running total for the current card (reset each break).
        grandTotal: Running total across every emitted detail line.
        currentCardNum: The card number of the group currently being reported,
            or ``None`` before the first detail line (the control-break "first
            time" sentinel).
        linesOnPage: Count of detail lines written on the current page.
        detailLineCount: Total detail lines written across the whole report;
            this is the value returned to the caller.
    """

    def __init__(self) -> None:
        self.pageTotal = ZERO_AMOUNT
        self.accountTotal = ZERO_AMOUNT
        self.grandTotal = ZERO_AMOUNT
        self.currentCardNum: str | None = None
        self.linesOnPage = 0
        self.detailLineCount = 0


class _NullWriter:
    """A ``csv.writer``-compatible sink that discards every row.

    Used when the caller supplies no ``outputDir``: the report engine can then
    run a single code path (accumulating totals and counting detail lines for
    the return value and the log summary) without persisting a file.
    """

    def writerow(self, row: list[str]) -> None:
        """Discard a single report row.

        Args:
            row: The row that would have been written; intentionally ignored.
        """
        return None


# ---------------------------------------------------------------------------
# Pure helpers
# ---------------------------------------------------------------------------


def _WithinDateRange(tran: Transaction, dateRange: tuple[str, str] | None) -> bool:
    """Report whether a transaction falls within the inclusive date range.

    Reproduces the legacy test ``TRAN-PROC-TS(1:10) >= WS-START-DATE AND
    <= WS-END-DATE`` by comparing the ``YYYY-MM-DD`` date portion of
    ``proc_ts``. ISO date strings order chronologically, so a plain string
    comparison matches the mainframe semantics exactly.

    Args:
        tran: The transaction under consideration.
        dateRange: An inclusive ``(startDate, endDate)`` pair of ``YYYY-MM-DD``
            strings, or ``None`` to accept every transaction.

    Returns:
        ``True`` if the transaction should appear in the report.
    """
    if dateRange is None:
        return True
    startDate, endDate = dateRange
    if tran.proc_ts is None:
        return False
    procDatePortion = tran.proc_ts.isoformat()[:10]
    return startDate <= procDatePortion <= endDate


def _MaskCardNumber(cardNum: str | None) -> str:
    """Mask a card number to its last four characters.

    The card number is a sensitive primary account number; only its final four
    characters may be surfaced (AAP 0.7.8).

    Args:
        cardNum: The raw card number, or ``None``.

    Returns:
        ``"****1234"`` for a full number, the trimmed value when it is four
        characters or fewer, or an empty string when ``cardNum`` is ``None``.
    """
    if cardNum is None:
        return ""
    trimmed = str(cardNum).strip()
    if len(trimmed) <= 4:
        return trimmed
    return f"****{trimmed[-4:]}"


def _FormatAmount(amount: Decimal) -> str:
    """Render a Decimal amount as a fixed two-decimal string.

    Args:
        amount: The exact monetary value to render.

    Returns:
        The amount quantized to two decimals, e.g. ``"-919.00"``. Quantization
        keeps the value an exact :class:`~decimal.Decimal`; no float is used.
    """
    return str(amount.quantize(AMOUNT_QUANTUM))


def _ResolveDescriptions(session: Session, tran: Transaction) -> tuple[str, str, str]:
    """Resolve the descriptive fields for one transaction.

    Looks up the owning account (via the card cross-reference), the transaction
    type description, and the transaction category description. Each lookup uses
    :meth:`Session.get`, which consults the session identity map first, so the
    repeated same-key lookups typical of a card-grouped report are served from
    memory rather than re-querying (avoiding the N+1 pattern).

    Args:
        session: The caller-owned, read-only session.
        tran: The transaction whose descriptors are required.

    Returns:
        A ``(accountId, typeDesc, catDesc)`` tuple; any component that cannot be
        resolved is returned as an empty string.
    """
    xref = session.get(CardXref, tran.card_num)
    accountId = xref.acct_id if xref is not None else ""
    tranType = session.get(TransactionType, tran.tran_type_cd)
    typeDesc = tranType.tran_type_desc if tranType is not None else ""
    tranCat = session.get(TransactionCategory, (tran.tran_type_cd, tran.tran_cat_cd))
    catDesc = tranCat.tran_cat_type_desc if tranCat is not None else ""
    return accountId, typeDesc, catDesc


# ---------------------------------------------------------------------------
# Report band writers
# ---------------------------------------------------------------------------


def _WriteColumnHeader(writer: _RowSink) -> None:
    """Write the eight-column detail header row.

    Args:
        writer: The active row sink.
    """
    writer.writerow(list(REPORT_COLUMNS))


def _WriteReportTitle(writer: _RowSink, dateRange: tuple[str, str] | None) -> None:
    """Write the report title, the date-range band, and the first header.

    Args:
        writer: The active row sink.
        dateRange: The inclusive ``(startDate, endDate)`` pair, or ``None`` when
            every date is included (rendered as :data:`ALL_DATES_LABEL`).
    """
    startText = dateRange[0] if dateRange is not None else ALL_DATES_LABEL
    endText = dateRange[1] if dateRange is not None else ALL_DATES_LABEL
    writer.writerow([REPORT_TITLE])
    writer.writerow(["Date Range:", startText, "to", endText])
    writer.writerow([])
    _WriteColumnHeader(writer)


def _TotalRow(label: str, amount: Decimal, maskedCard: str = "") -> list[str]:
    """Build a total-band row with the amount under the Amount column.

    Args:
        label: The band label (page/account/grand).
        amount: The total to render in the final (Amount) column.
        maskedCard: Optional already-masked card number shown in the AccountID
            column to identify the group a per-account total closes.

    Returns:
        A list of :data:`COLUMN_COUNT` cells: the label, an optional masked
        card, blank filler, and the formatted amount last.
    """
    row = [""] * COLUMN_COUNT
    row[0] = label
    row[1] = maskedCard
    row[COLUMN_COUNT - 1] = _FormatAmount(amount)
    return row


def _WriteDetailLine(
    writer: _RowSink,
    tran: Transaction,
    descriptions: tuple[str, str, str],
    state: _ReportState,
) -> None:
    """Write one detail line and accumulate the three total levels.

    Reproduces legacy ``1120-WRITE-DETAIL``: emits the eight-column detail row,
    then adds the transaction amount to the page, account, and grand totals and
    advances the page and detail counters.

    Args:
        writer: The active row sink.
        tran: The transaction to report.
        descriptions: The ``(accountId, typeDesc, catDesc)`` triple resolved by
            :func:`_ResolveDescriptions`.
        state: The shared running-total/control-break state (mutated in place).
    """
    accountId, typeDesc, catDesc = descriptions
    amount = tran.tran_amt if tran.tran_amt is not None else ZERO_AMOUNT
    writer.writerow([
        tran.tran_id,
        accountId,
        tran.tran_type_cd,
        typeDesc,
        tran.tran_cat_cd,
        catDesc,
        tran.tran_source or "",
        _FormatAmount(amount),
    ])
    state.pageTotal += amount
    state.accountTotal += amount
    state.grandTotal += amount
    state.linesOnPage += 1
    state.detailLineCount += 1


def _WritePageTotals(writer: _RowSink, state: _ReportState) -> None:
    """Emit the page-total band and reset the page accumulators.

    Args:
        writer: The active row sink.
        state: The shared state; ``pageTotal`` and ``linesOnPage`` are reset.
    """
    writer.writerow(_TotalRow(PAGE_TOTAL_LABEL, state.pageTotal))
    state.pageTotal = ZERO_AMOUNT
    state.linesOnPage = 0


def _WritePageBreak(writer: _RowSink, state: _ReportState) -> None:
    """Close a full page: emit its total, then re-print the column header.

    Reproduces the legacy page-break behavior (``1110-WRITE-PAGE-TOTALS``
    followed by a fresh header) that occurs once a page fills.

    Args:
        writer: The active row sink.
        state: The shared state; page accumulators are reset by the delegate.
    """
    _WritePageTotals(writer, state)
    _WriteColumnHeader(writer)


def _WriteAccountTotals(writer: _RowSink, state: _ReportState) -> None:
    """Emit the per-account total band and reset the account accumulator.

    Reproduces legacy ``1120-WRITE-ACCOUNT-TOTALS``. The card number of the
    group being closed is shown masked to its last four characters.

    Args:
        writer: The active row sink.
        state: The shared state; ``accountTotal`` is reset to zero.
    """
    maskedCard = _MaskCardNumber(state.currentCardNum)
    writer.writerow(_TotalRow(ACCOUNT_TOTAL_LABEL, state.accountTotal, maskedCard))
    state.accountTotal = ZERO_AMOUNT


def _WriteGrandTotals(writer: _RowSink, state: _ReportState) -> None:
    """Emit the single grand-total band (legacy ``1110-WRITE-GRAND-TOTALS``).

    Args:
        writer: The active row sink.
        state: The shared state supplying ``grandTotal``.
    """
    writer.writerow(_TotalRow(GRAND_TOTAL_LABEL, state.grandTotal))


def _WriteFinalTotals(writer: _RowSink, state: _ReportState) -> None:
    """Close the report: final account total, final page total, grand total.

    The legacy program omitted the final card's account total at end-of-file;
    the redesigned report emits it for completeness so every card group carries
    its subtotal. The grand total is always written, even for an empty report.

    Args:
        writer: The active row sink.
        state: The shared state supplying the pending totals.
    """
    if state.detailLineCount > 0:
        _WriteAccountTotals(writer, state)
        if state.linesOnPage > 0:
            _WritePageTotals(writer, state)
    _WriteGrandTotals(writer, state)


# ---------------------------------------------------------------------------
# Report engine and public entrypoint
# ---------------------------------------------------------------------------


def _RunReport(
    session: Session,
    writer: _RowSink,
    dateRange: tuple[str, str] | None,
) -> int:
    """Drive the full control-break report against the given writer.

    Streams transactions ordered by card number then transaction id (so the
    control break on card produces one account total per card), applies the
    inclusive date-range filter, and emits the detail and total bands.

    Args:
        session: The caller-owned, read-only session.
        writer: The active row sink (a ``csv.writer`` or :class:`_NullWriter`).
        dateRange: The inclusive ``(startDate, endDate)`` pair, or ``None``.

    Returns:
        The number of detail lines written.
    """
    state = _ReportState()
    _WriteReportTitle(writer, dateRange)
    statement = select(Transaction).order_by(Transaction.card_num, Transaction.tran_id)
    for tran in session.execute(statement).scalars():
        if not _WithinDateRange(tran, dateRange):
            continue
        if state.currentCardNum is not None and tran.card_num != state.currentCardNum:
            _WriteAccountTotals(writer, state)
        state.currentCardNum = tran.card_num
        if state.linesOnPage >= PAGE_SIZE:
            _WritePageBreak(writer, state)
        descriptions = _ResolveDescriptions(session, tran)
        _WriteDetailLine(writer, tran, descriptions, state)
    _WriteFinalTotals(writer, state)
    return state.detailLineCount


def ReportTransactionDetail(
    session: Session,
    outputDir: str | Path | None = None,
    dateRange: tuple[str, str] | None = None,
) -> int:
    """Generate the CardDemo transaction detail report (port of ``CBTRN03C``).

    Reads posted transactions through the supplied session, groups them by card
    with a control break, and writes the eight-column detail band plus page,
    per-account, and grand totals. When ``outputDir`` is provided the report is
    written as CSV to ``<outputDir>/<REPORT_FILE_NAME>`` using a deterministic
    filename (so re-runs regenerate cleanly); when it is ``None`` the report is
    computed and summarized to the log without persisting a file.

    The caller owns the transaction: this function performs only reads and never
    commits, rolls back, closes the session, or issues DDL.

    Args:
        session: An open, caller-owned SQLAlchemy :class:`~sqlalchemy.orm.Session`.
        outputDir: Directory to write the CSV report into, or ``None`` to log a
            summary only. Created if it does not exist.
        dateRange: Optional inclusive ``(startDate, endDate)`` pair of
            ``YYYY-MM-DD`` strings; transactions are filtered on the date
            portion of ``proc_ts``. ``None`` includes every transaction.

    Returns:
        The number of transaction detail lines written.

    Raises:
        OSError: If ``outputDir`` cannot be created or the report file cannot be
            opened for writing (propagated unchanged to the caller).
    """
    LOGGER.info("START OF EXECUTION OF PROGRAM CBTRN03C")
    if outputDir is None:
        detailLineCount = _RunReport(session, _NullWriter(), dateRange)
        LOGGER.info("Report generated (not persisted): %d detail line(s)", detailLineCount)
    else:
        reportDirectory = Path(outputDir)
        reportDirectory.mkdir(parents=True, exist_ok=True)
        reportPath = reportDirectory / REPORT_FILE_NAME
        with reportPath.open("w", newline="", encoding="utf-8") as reportFile:
            writer = csv.writer(reportFile)
            detailLineCount = _RunReport(session, writer, dateRange)
        LOGGER.info("Report written to %s: %d detail line(s)", reportPath, detailLineCount)
    LOGGER.info("END OF EXECUTION OF PROGRAM CBTRN03C")
    return detailLineCount
