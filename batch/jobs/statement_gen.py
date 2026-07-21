# Ported from legacy COBOL batch programs CBSTM03A.CBL + CBSTM03B.CBL + JCL app/jcl/CREASTMT.JCL (CardDemo). Function: generate per-account statements. AUTHORIZED REDESIGN: legacy text+HTML -> CSV + PDF.
"""Per-account statement generation for the CardDemo batch chain.

This module is the modern Python port of the legacy mainframe statement job. On
z/OS the job was three cooperating artifacts:

* ``app/cbl/CBSTM03A.CBL`` -- the statement *driver*. It walked the card
  cross-reference (``CARDXREF``) one record at a time and, for every card,
  fetched the owning customer and account and that card's transactions, then
  wrote a formatted statement. Its ``PROCEDURE DIVISION`` paragraphs
  ``1000-MAINLINE`` (loop), ``2000-CUSTFILE-GET``, ``3000-ACCTFILE-GET``,
  ``4000-TRNXFILE-GET`` and ``5000-CREATE-STATEMENT`` map directly onto the
  functions below.
* ``app/cbl/CBSTM03B.CBL`` -- a generic VSAM file-I/O subroutine that
  ``CBSTM03A`` ``CALL``-ed to read the ``XREF``/``CUST``/``ACCT``/``TRNX`` files
  by key. In the modern three-tier stack that subroutine has **no separate
  module**: it is *subsumed* here by SQLAlchemy ORM queries against the passed
  :class:`~sqlalchemy.orm.Session` (``session.get`` for keyed reads,
  ``select(...)`` for the ordered transaction browse).
* ``app/jcl/CREASTMT.JCL`` -- the JCL that sorted ``TRANSACT`` by
  (card number, transaction id), allocated the ``XREFFILE``/``CUSTFILE``/
  ``ACCTFILE``/``TRNXFILE`` input DDs, and produced the ``STMTFILE`` (plain
  text) and ``HTMLFILE`` (HTML) statement datasets.

Authorized redesign (AAP section 0.8.4): the single behavior-adjacent change the
prompt permits is replacing the legacy **plain-text + HTML** statement output
with **CSV + PDF**. Every monetary value and every business rule is preserved
exactly -- only the rendering format changes. One statement is produced per
cross-reference row, exactly as ``1000-MAINLINE`` looped once per ``CARDXREF``
record, and the transaction summary is ordered by transaction id.

Numeric fidelity (AAP section 0.7.1): all monetary values -- the per-transaction
amount, the account current balance, and the accumulated statement total -- flow
through :class:`decimal.Decimal`. Floating point is never used, because binary
rounding would violate the regulatory numeric-parity requirement. Currency is
rendered with a fixed two-decimal format directly from ``Decimal``.

Sensitive data (AAP section 0.7.8): the card number (a PAN) is masked to its last
four digits everywhere it appears -- in the statement body *and* in the output
file names -- and the card verification value (``cvv_cd``) is never read or
emitted under any circumstances. The customer SSN is likewise never placed on the
statement.

Transaction ownership: :func:`GenerateStatements` receives an already-open,
caller-owned :class:`~sqlalchemy.orm.Session` (typically from
``batch.db.GetSyncSession``). It is strictly read-only with respect to the
database -- it issues no writes and it never calls ``commit``, ``rollback`` or
``close``; the caller owns the transaction boundary. Its only side effect is
writing statement files to the output directory.

Idempotency: statement file names are deterministic per card
(``statement_<acct_id>_<last4>.csv`` / ``.pdf``), so re-running the job simply
overwrites the previous run's files and converges to the same output.

Public API:
    * :func:`GenerateStatements` -- generate every account statement and return a
      :class:`StatementResult` summary.
    * :class:`StatementResult` -- the returned summary dataclass (count, the CSV
      and PDF paths written, and the grand total across all statements).

Example:
    Generate statements inside a caller-owned unit of work::

        from batch.db import GetSyncSession
        from batch.jobs.statement_gen import GenerateStatements

        with GetSyncSession() as session:
            summary = GenerateStatements(session)   # job does NOT commit
        print(summary.statementsGenerated, summary.totalAmount)
"""

from __future__ import annotations

import csv
import logging
from dataclasses import dataclass, field
from decimal import Decimal
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.account import Account
from app.models.card import Card
from app.models.card_xref import CardXref
from app.models.customer import Customer
from app.models.transaction import Transaction

__all__ = ["GenerateStatements", "StatementResult"]


# Module logger. The batch chain configures handlers/levels at its entry point
# (batch.cli); this module only emits records and never configures logging.
LOGGER = logging.getLogger(__name__)


# --- Bank header ---------------------------------------------------------------
# Ported verbatim from the CBSTM03A HTML header (condition names HTML-L16/L17/L18
# in app/cbl/CBSTM03A.CBL). These are static branding lines, not secrets.
BANK_NAME = "Bank of XYZ"
BANK_ADDRESS_LINE = "410 Terry Ave N"
BANK_CITY_LINE = "Seattle WA 99999"


# --- Statement section markers -------------------------------------------------
# Ported from the CBSTM03A STATEMENT-LINES group (ST-LINE0/6/11/15). The legacy
# banners were built from repeated '*' fill; here they are plain, readable labels.
START_OF_STATEMENT_MARKER = "*** START OF STATEMENT ***"
BASIC_DETAILS_TITLE = "Basic Details"
TRANSACTION_SUMMARY_TITLE = "TRANSACTION SUMMARY"
END_OF_STATEMENT_MARKER = "*** END OF STATEMENT ***"


# --- Basic-details labels (ported from ST-LINE7/ST-LINE8/ST-LINE9/ST-LINE14A) --
LABEL_ACCOUNT_ID = "Account ID"
LABEL_CURRENT_BALANCE = "Current Balance"
LABEL_FICO_SCORE = "FICO Score"
LABEL_CARD_NUMBER = "Card Number"
LABEL_CARD_HOLDER = "Card Holder"
LABEL_TOTAL_EXP = "Total EXP"


# --- Transaction-summary column headers (ported from ST-LINE13) ----------------
COLUMN_TRAN_ID = "Tran ID"
COLUMN_TRAN_DETAILS = "Tran Details"
COLUMN_TRAN_AMOUNT = "Tran Amount"


# --- Fixed-width layout constants ----------------------------------------------
# TRAN_DESC_MAX_WIDTH mirrors ST-TRANDT PIC X(49): the transaction description is
# truncated to 49 characters on the statement. The *_COLUMN_WIDTH values are the
# left-justified column widths used only for the monospaced PDF text layout; the
# CSV keeps the columns as discrete cells.
TRAN_DESC_MAX_WIDTH = 49
TRAN_ID_COLUMN_WIDTH = 18
TRAN_DESC_COLUMN_WIDTH = 51
LABEL_COLUMN_WIDTH = 20


# --- Card masking (AAP section 0.7.8) ------------------------------------------
CARD_MASK_PREFIX = "****"
CARD_LAST_DIGITS = 4
# Fallback last-four token used only when a cross-reference row somehow carries no
# card number; keeps file names deterministic without ever exposing a real PAN.
CARD_LAST_DIGITS_FALLBACK = "0000"


# --- Default output location ---------------------------------------------------
# Runtime artifact directory, relative to the repository root. It replaces the
# legacy JCL STMTFILE/HTMLFILE DD datasets. The generated statement files are
# runtime output (not source) and are created on demand.
DEFAULT_OUTPUT_DIR_PARTS = ("batch", "output", "statements")


# --- PDF layout constants ------------------------------------------------------
# A monospaced font keeps the ported column alignment intact in the PDF. Margins
# and line height are expressed in PDF points (1 point = 1/72 inch).
PDF_FONT_NAME = "Courier"
PDF_FONT_SIZE = 9
PDF_MARGIN_POINTS = 54
PDF_LINE_HEIGHT_POINTS = 12


@dataclass
class StatementResult:
    """Summary of a :func:`GenerateStatements` run.

    Grouping the return values in a single dataclass keeps the public function
    signature small (Ochs Rule: return one object instead of a wide tuple).

    Attributes:
        statementsGenerated: The number of statements written (one per
            cross-reference row processed).
        csvPaths: Absolute paths, as strings, of every CSV statement written,
            in generation order.
        pdfPaths: Absolute paths, as strings, of every PDF statement written,
            in generation order.
        totalAmount: The exact :class:`decimal.Decimal` sum of every
            transaction amount across all statements. Never a float.
    """

    statementsGenerated: int = 0
    csvPaths: list[str] = field(default_factory=list)
    pdfPaths: list[str] = field(default_factory=list)
    totalAmount: Decimal = Decimal("0")


@dataclass
class _StatementContext:
    """Immutable bundle of the data needed to render one statement.

    The legacy CSV/PDF writers would each have needed six positional arguments
    (output directory, cross-reference, customer, account, card and the
    transactions plus their total). Bundling them here keeps every writer to a
    single parameter, honoring the Ochs "at most four parameters" rule.

    Attributes:
        outputDir: The resolved directory the statement files are written to.
        xref: The cross-reference row driving this statement (source of the
            card number, customer id and account id).
        customer: The owning customer, or ``None`` when the master record is
            missing (rendered as blanks rather than aborting the run).
        account: The owning account, or ``None`` when the master record is
            missing.
        card: The plastic-card record, or ``None`` when it cannot be read. Used
            only for the embossed cardholder name; ``cvv_cd`` is never touched.
        trans: The card's transactions, ordered by transaction id.
        statementTotal: The exact :class:`decimal.Decimal` sum of ``trans``
            amounts for this statement.
    """

    outputDir: Path
    xref: CardXref
    customer: Customer | None
    account: Account | None
    card: Card | None
    trans: list[Transaction]
    statementTotal: Decimal


# -----------------------------------------------------------------------------
# Value / formatting helpers (small, single-purpose -- Ochs)
# -----------------------------------------------------------------------------
def _SafeText(value: str | None) -> str:
    """Return a trimmed string, or an empty string for ``None``.

    Args:
        value: A possibly-``None`` text field read from an ORM attribute.

    Returns:
        ``value.strip()`` when a value is present, otherwise ``""``. This keeps
        the statement rendering robust when optional master-record fields (a
        middle name, an address overflow line) are absent.
    """
    if value is None:
        return ""
    return value.strip()


def _MaskCardNumber(cardNumber: str | None) -> str:
    """Mask a card number to its last four digits for display.

    The full PAN is sensitive (AAP section 0.7.8) and must never appear in
    statement content. This renders it as the ``****`` prefix followed by the
    last four characters, e.g. ``****1234``.

    Args:
        cardNumber: The full card number, or ``None``.

    Returns:
        The masked representation. When ``cardNumber`` is empty or ``None`` the
        bare mask prefix is returned so nothing sensitive is ever emitted.
    """
    if not cardNumber:
        return CARD_MASK_PREFIX
    lastDigits = cardNumber[-CARD_LAST_DIGITS:]
    return f"{CARD_MASK_PREFIX}{lastDigits}"


def _CardLastDigits(cardNumber: str | None) -> str:
    """Return the last four characters of a card number for file naming.

    Args:
        cardNumber: The full card number, or ``None``.

    Returns:
        The last four characters, or :data:`CARD_LAST_DIGITS_FALLBACK` when the
        card number is missing. Only the last four digits ever reach a file
        name -- the full PAN never does.
    """
    if not cardNumber:
        return CARD_LAST_DIGITS_FALLBACK
    return cardNumber[-CARD_LAST_DIGITS:]


def _FormatCurrency(amount: Decimal | None) -> str:
    """Format a monetary :class:`~decimal.Decimal` to a fixed two-decimal string.

    Formatting is applied directly to the ``Decimal`` value, so no intermediate
    ``float`` is ever created and exact cents are preserved (AAP section 0.7.1).

    Args:
        amount: The monetary value, or ``None`` (treated as zero).

    Returns:
        The amount with exactly two decimal places, e.g. ``"194.00"`` or
        ``"-919.00"`` for a credit.
    """
    if amount is None:
        amount = Decimal("0")
    return f"{amount:.2f}"


def _ComposeCustomerName(customer: Customer | None) -> str:
    """Join the customer's first, middle and last name with single spaces.

    Reproduces the CBSTM03A ``STRING ... DELIMITED BY ' '`` build of ``ST-NAME``:
    each name part is trimmed and blank parts are dropped, so an absent middle
    name does not introduce a double space.

    Args:
        customer: The customer record, or ``None``.

    Returns:
        The composed full name, or ``""`` when no customer is available.
    """
    if customer is None:
        return ""
    nameParts = (customer.first_name, customer.middle_name, customer.last_name)
    presentParts = [_SafeText(part) for part in nameParts if _SafeText(part)]
    return " ".join(presentParts)


def _ComposeAddressLine3(customer: Customer | None) -> str:
    """Compose the third address line from city/state/country/zip fields.

    Reproduces the CBSTM03A ``ST-ADD3`` build, which concatenated address line
    3, the state code, the country code and the zip, each single-space
    delimited and with blanks removed.

    Args:
        customer: The customer record, or ``None``.

    Returns:
        The composed address line, or ``""`` when no customer is available.
    """
    if customer is None:
        return ""
    addressParts = (
        customer.addr_line_3,
        customer.addr_state_cd,
        customer.addr_country_cd,
        customer.addr_zip,
    )
    presentParts = [_SafeText(part) for part in addressParts if _SafeText(part)]
    return " ".join(presentParts)


def _ResolveOutputDir(outputDir: str | Path | None) -> Path:
    """Resolve and create the statement output directory.

    Args:
        outputDir: An explicit target directory (``str`` or
            :class:`~pathlib.Path`), or ``None`` to use the repository default
            ``batch/output/statements``. The default is derived from this
            module's location so it is independent of the current working
            directory.

    Returns:
        The resolved directory as a :class:`~pathlib.Path`. The directory (and
        any missing parents) is created if it does not already exist.
    """
    if outputDir is not None:
        resolvedDir = Path(outputDir)
    else:
        # This module lives at <repo_root>/batch/jobs/statement_gen.py; two
        # parent hops from the jobs directory reach the repository root.
        repoRoot = Path(__file__).resolve().parents[2]
        resolvedDir = repoRoot.joinpath(*DEFAULT_OUTPUT_DIR_PARTS)
    resolvedDir.mkdir(parents=True, exist_ok=True)
    return resolvedDir


def _BuildStatementFilename(context: _StatementContext, suffix: str) -> str:
    """Build a deterministic, PAN-safe statement file name.

    The name embeds the account id and the masked last four card digits, so
    re-running the job overwrites the prior file for the same card (idempotent)
    while never exposing the full card number.

    Args:
        context: The statement context (source of the account id and card
            number).
        suffix: The file extension including the leading dot (``".csv"`` or
            ``".pdf"``).

    Returns:
        A file name such as ``statement_00000000010_1234.csv``.
    """
    if context.account is not None:
        acctId = _SafeText(context.account.acct_id)
    else:
        acctId = _SafeText(context.xref.acct_id)
    lastDigits = _CardLastDigits(context.xref.xref_card_num)
    return f"statement_{acctId}_{lastDigits}{suffix}"



# -----------------------------------------------------------------------------
# Statement content builders (shared by both the CSV and PDF writers so the two
# formats always carry identical data -- only the rendering differs)
# -----------------------------------------------------------------------------
def _BuildHeaderRows(context: _StatementContext) -> list[list[str]]:
    """Build the bank header and customer name/address block.

    Reproduces CBSTM03A ``ST-LINE0`` (start banner), the HTML bank header, and
    ``ST-LINE1``-``ST-LINE4`` (customer name and three address lines).

    Args:
        context: The statement context.

    Returns:
        The header rows, each a list of cells (single-cell rows here).
    """
    customer = context.customer
    addrLine1 = _SafeText(customer.addr_line_1) if customer else ""
    addrLine2 = _SafeText(customer.addr_line_2) if customer else ""
    return [
        [START_OF_STATEMENT_MARKER],
        [BANK_NAME],
        [BANK_ADDRESS_LINE],
        [BANK_CITY_LINE],
        [],
        [_ComposeCustomerName(customer)],
        [addrLine1],
        [addrLine2],
        [_ComposeAddressLine3(customer)],
        [],
    ]


def _BuildBasicDetailRows(context: _StatementContext) -> list[list[str]]:
    """Build the "Basic Details" block (account id, balance, FICO, card).

    Reproduces CBSTM03A ``ST-LINE6``-``ST-LINE9`` and augments them with the
    masked card number and embossed cardholder name. The current balance is
    formatted from the account's exact :class:`~decimal.Decimal` (never float);
    the FICO score is a plain integer.

    Args:
        context: The statement context.

    Returns:
        The basic-detail rows as label/value cell lists.
    """
    account = context.account
    customer = context.customer
    currentBalance = _FormatCurrency(account.curr_bal if account else None)
    if customer is not None and customer.fico_credit_score is not None:
        ficoScore = str(customer.fico_credit_score)
    else:
        ficoScore = ""
    cardHolder = _SafeText(context.card.embossed_name) if context.card else ""
    return [
        [BASIC_DETAILS_TITLE],
        [LABEL_ACCOUNT_ID, _SafeText(context.xref.acct_id)],
        [LABEL_CURRENT_BALANCE, currentBalance],
        [LABEL_FICO_SCORE, ficoScore],
        [LABEL_CARD_NUMBER, _MaskCardNumber(context.xref.xref_card_num)],
        [LABEL_CARD_HOLDER, cardHolder],
        [],
    ]


def _BuildTransactionRows(context: _StatementContext) -> list[list[str]]:
    """Build the transaction-summary table and the statement total.

    Reproduces CBSTM03A ``ST-LINE11``/``ST-LINE13`` (title + column headers),
    one ``ST-LINE14`` per transaction (id, description truncated to 49 chars,
    amount) and ``ST-LINE14A`` (Total EXP). Amounts are formatted from exact
    :class:`~decimal.Decimal` values.

    Args:
        context: The statement context.

    Returns:
        The transaction-summary rows: title, header, one row per transaction,
        the total row, and a trailing blank separator.
    """
    rows: list[list[str]] = [
        [TRANSACTION_SUMMARY_TITLE],
        [COLUMN_TRAN_ID, COLUMN_TRAN_DETAILS, COLUMN_TRAN_AMOUNT],
    ]
    for tran in context.trans:
        tranDetails = _SafeText(tran.tran_desc)[:TRAN_DESC_MAX_WIDTH]
        rows.append(
            [_SafeText(tran.tran_id), tranDetails, _FormatCurrency(tran.tran_amt)]
        )
    rows.append([LABEL_TOTAL_EXP, "", _FormatCurrency(context.statementTotal)])
    rows.append([])
    return rows


def _BuildStatementRows(context: _StatementContext) -> list[list[str]]:
    """Assemble the full, canonical statement content as rows of cells.

    This is the single source of truth for statement content; both the CSV and
    the PDF writers render from it, guaranteeing the two formats stay identical.

    Args:
        context: The statement context.

    Returns:
        Every statement row, top to bottom, ending with the end-of-statement
        marker. Each row is a list of string cells (an empty list is a blank
        separator line).
    """
    rows: list[list[str]] = []
    rows.extend(_BuildHeaderRows(context))
    rows.extend(_BuildBasicDetailRows(context))
    rows.extend(_BuildTransactionRows(context))
    rows.append([END_OF_STATEMENT_MARKER])
    return rows


def _RenderRowText(row: list[str]) -> str:
    """Render one content row as a single fixed-width line for the PDF.

    The column widths reproduce the monospaced alignment of the legacy text
    statement: label rows left-justify the label; transaction rows left-justify
    the id and description columns before the amount.

    Args:
        row: A content row (list of cells) from :func:`_BuildStatementRows`.

    Returns:
        The row rendered as a single string. A blank row renders as ``""``.
    """
    if not row:
        return ""
    if len(row) == 1:
        return row[0]
    if len(row) == 2:
        return f"{row[0]:<{LABEL_COLUMN_WIDTH}}{row[1]}"
    return (
        f"{row[0]:<{TRAN_ID_COLUMN_WIDTH}}"
        f"{row[1]:<{TRAN_DESC_COLUMN_WIDTH}}"
        f"{row[2]}"
    )


# -----------------------------------------------------------------------------
# Format writers (CSV via stdlib; PDF via reportlab imported lazily)
# -----------------------------------------------------------------------------
def _WriteStatementCsv(context: _StatementContext) -> Path:
    """Write one statement as a CSV file.

    Uses the standard-library :mod:`csv` module to emit the canonical statement
    rows (header, customer block, basic details, transaction summary and
    total). The file name is deterministic and PAN-safe.

    Args:
        context: The statement context.

    Returns:
        The path of the written CSV file.

    Raises:
        OSError: If the CSV file cannot be created or written.
    """
    csvPath = context.outputDir / _BuildStatementFilename(context, ".csv")
    rows = _BuildStatementRows(context)
    try:
        with csvPath.open("w", encoding="utf-8", newline="") as csvFile:
            writer = csv.writer(csvFile)
            writer.writerows(rows)
    except OSError as fileError:
        LOGGER.error("Failed writing CSV statement %s: %s", csvPath, fileError)
        raise
    return csvPath


def _WriteStatementPdf(context: _StatementContext) -> Path:
    """Write one statement as a PDF file.

    ``reportlab`` is imported lazily *inside* this function so that importing
    the module never hard-fails in an environment where reportlab is absent;
    only actually generating a PDF requires it. The same canonical rows used by
    the CSV writer are rendered as fixed-width monospaced lines, with automatic
    page breaks.

    Args:
        context: The statement context.

    Returns:
        The path of the written PDF file.

    Raises:
        OSError: If the PDF file cannot be created or written.
    """
    from reportlab.lib.pagesizes import letter
    from reportlab.pdfgen import canvas

    pdfPath = context.outputDir / _BuildStatementFilename(context, ".pdf")
    pageHeight = letter[1]
    try:
        pdfCanvas = canvas.Canvas(str(pdfPath), pagesize=letter)
        pdfCanvas.setFont(PDF_FONT_NAME, PDF_FONT_SIZE)
        yPosition = pageHeight - PDF_MARGIN_POINTS
        for row in _BuildStatementRows(context):
            if yPosition <= PDF_MARGIN_POINTS:
                pdfCanvas.showPage()
                pdfCanvas.setFont(PDF_FONT_NAME, PDF_FONT_SIZE)
                yPosition = pageHeight - PDF_MARGIN_POINTS
            pdfCanvas.drawString(PDF_MARGIN_POINTS, yPosition, _RenderRowText(row))
            yPosition -= PDF_LINE_HEIGHT_POINTS
        pdfCanvas.save()
    except OSError as fileError:
        LOGGER.error("Failed writing PDF statement %s: %s", pdfPath, fileError)
        raise
    return pdfPath



# -----------------------------------------------------------------------------
# Per-statement data assembly + public driver
# -----------------------------------------------------------------------------
def _BuildStatementContext(
    session: Session, outputDir: Path, xref: CardXref
) -> _StatementContext:
    """Assemble the data for one statement from the database.

    Subsumes the CBSTM03A keyed reads (``2000-CUSTFILE-GET``,
    ``3000-ACCTFILE-GET``) and the transaction browse (``4000-TRNXFILE-GET``)
    that the legacy program delegated to the CBSTM03B I/O subroutine. All reads
    go through the caller-owned ``session``; nothing is committed. A missing
    customer or account is logged and rendered as blanks rather than aborting
    the whole run (the legacy program abended instead).

    Args:
        session: The open, caller-owned SQLAlchemy session.
        outputDir: The resolved statement output directory.
        xref: The cross-reference row driving this statement.

    Returns:
        A fully populated :class:`_StatementContext`, including the exact
        :class:`~decimal.Decimal` sum of the card's transaction amounts.
    """
    customer = session.get(Customer, xref.cust_id)      # 2000-CUSTFILE-GET
    account = session.get(Account, xref.acct_id)        # 3000-ACCTFILE-GET
    card = session.get(Card, xref.xref_card_num)        # cardholder / masked PAN
    maskedCard = _MaskCardNumber(xref.xref_card_num)
    if customer is None:
        LOGGER.warning("Customer %s not found for card %s", xref.cust_id, maskedCard)
    if account is None:
        LOGGER.warning("Account %s not found for card %s", xref.acct_id, maskedCard)
    trans = list(                                       # 4000-TRNXFILE-GET
        session.execute(
            select(Transaction)
            .where(Transaction.card_num == xref.xref_card_num)
            .order_by(Transaction.tran_id)
        )
        .scalars()
        .all()
    )
    # Accumulate with a Decimal("0") seed so the total is always an exact
    # Decimal (never a float), matching the legacy WS-TOTAL-AMT accumulation.
    statementTotal = sum((tran.tran_amt for tran in trans), Decimal("0"))
    return _StatementContext(
        outputDir=outputDir,
        xref=xref,
        customer=customer,
        account=account,
        card=card,
        trans=trans,
        statementTotal=statementTotal,
    )


def GenerateStatements(
    session: Session, outputDir: str | Path | None = None
) -> StatementResult:
    """Generate a CSV and PDF statement for every card cross-reference.

    Modern port of the CBSTM03A ``1000-MAINLINE`` loop: it walks the card
    cross-reference in card-number order and, for each row, gathers the owning
    customer and account and that card's transactions, then writes one CSV and
    one PDF statement. Monetary values flow through :class:`~decimal.Decimal`
    throughout; the card number is masked and the CVV is never emitted.

    The function is read-only with respect to the database and does not manage
    the transaction: it never commits, rolls back, or closes ``session`` -- the
    caller owns the unit of work (for example ``batch.db.GetSyncSession``).

    Args:
        session: An open, caller-owned SQLAlchemy :class:`~sqlalchemy.orm.Session`.
        outputDir: Optional directory to write statements into. When ``None``
            the repository default ``batch/output/statements`` is used and
            created if missing.

    Returns:
        A :class:`StatementResult` summarizing how many statements were written,
        the CSV and PDF paths, and the exact grand total across all statements.
    """
    LOGGER.info("START OF EXECUTION OF PROGRAM CBSTM03A")
    result = StatementResult()
    resolvedDir = _ResolveOutputDir(outputDir)
    xrefs = session.execute(
        select(CardXref).order_by(CardXref.xref_card_num)
    ).scalars().all()
    for xref in xrefs:
        context = _BuildStatementContext(session, resolvedDir, xref)
        csvPath = _WriteStatementCsv(context)
        pdfPath = _WriteStatementPdf(context)
        result.csvPaths.append(str(csvPath))
        result.pdfPaths.append(str(pdfPath))
        result.statementsGenerated += 1
        result.totalAmount += context.statementTotal
    LOGGER.info("END OF EXECUTION OF PROGRAM CBSTM03A")
    return result

