"""Report DTOs.

Source: app/cpy-bms/CORPT00.CPY (request), app/cpy/CVTRA07Y.cpy (detail rows + page/account/grand totals).

Pydantic v2 request/response schemas for the transaction *report* feature of the
modernized CardDemo backend (CICS transaction ``CR00``, legacy program
``CORPT00C``). The request shape is ported from the report-request screen
``CORPT00`` (``app/cpy-bms/CORPT00.CPY``); the on-screen result table and its
page/account/grand totals are ported from the ``TRANSACTION-DETAIL-REPORT``
layout in ``app/cpy/CVTRA07Y.cpy``. The monetary column originates from the
posted-transaction record ``TRAN-RECORD`` (``app/cpy/CVTRA05Y.cpy``).

Reporting redesign (authorized behavior-adjacent change, tech-spec AAP 0.1.1
Goal 3 and 0.8.4): the legacy program wrote its output to a TDQ/GDG text+HTML
statement dataset. The modern feature instead returns a structured JSON payload
that drives an on-screen Material UI table plus downloadable CSV and PDF
renderings. The response DTO defined here is therefore the single source that
both the on-screen table and the CSV/PDF generators in ``report_service`` read
from -- no other output channel is introduced.

Fidelity rules preserved here (see AAP 0.7):

* ``tran_amt`` and every total originate from ``PIC S9(09)V99`` / edited
  ``-ZZZ,ZZZ,ZZZ.ZZ`` money fields and are exposed as exact
  :class:`~decimal.Decimal` values mapped to ``NUMERIC(11, 2)``. Floating point
  is rejected outright so binary rounding can never contaminate a currency
  value (AAP 0.7.1).
* ``tran_type_desc`` and ``tran_cat_desc`` reproduce the *report* truncation
  widths: the 50-character type/category descriptions are trimmed to 15 and 29
  characters respectively (the exact widths of ``TRAN-REPORT-TYPE-DESC`` and
  ``TRAN-REPORT-CAT-DESC`` in ``CVTRA07Y``).
* Identifier and code fields (``tran_id``, ``acct_id``, ``tran_cat_cd``) remain
  fixed-width strings so leading zeros survive; they are never coerced to
  ``int``.

This module imports only from :mod:`app.schemas.common` and :mod:`app.utils`;
it deliberately imports no sibling schema module. Although
:class:`app.schemas.transaction.TransactionSummary` is a declared dependency, it
is intentionally not reused: the ``CVTRA07Y`` report row carries its own trimmed
column set (``acct_id`` plus the truncated type/category descriptions, and no
masked ``card_num``), so a dedicated :class:`TransactionReportRow` is defined
here instead. In-code identifiers follow the Ochs Rule (PascalCase
classes/methods, camelCase locals, ALL_UPPERCASE constants); field and module
names remain snake_case (AAP 0.8.3).
"""

# Ported from the BMS symbolic map CORPT00.CPY (report request: the MONTHLY /
# YEARLY / CUSTOM X(1) radio flags, the SDT*/EDT* MM/DD/YYYY date parts, and the
# CONFIRM X(1) flag) and the reporting copybook CVTRA07Y.cpy (the
# TRANSACTION-DETAIL-REPORT detail row and the page/account/grand total lines).
# The underlying money column comes from CVTRA05Y.cpy (TRAN-AMT S9(09)V99).
# Numeric semantics (exact Decimal, never float) come from app.utils.decimal_utils;
# date validation/parsing from app.utils.date_utils; the Y/N and numeric-id edits
# from app.utils.validators. No business logic lives here -- these are pure
# data-transfer objects that reproduce the legacy field widths, numeric
# precision, and accept/reject validation of the report screen and report line.

from datetime import date
from decimal import Decimal, InvalidOperation
from enum import Enum
from typing import Optional

from pydantic import Field, field_validator, model_validator

from app.schemas.common import OrmBase, RequestBase
from app.utils import date_utils, decimal_utils, validators

__all__ = [
    "ReportType",
    "TransactionReportRow",
    "ReportRequest",
    "ReportResponse",
]

# ---------------------------------------------------------------------------
# Field-width constants (Ochs Rule: ALL_UPPERCASE). Detail-row widths come from
# CVTRA07Y (TRANSACTION-DETAIL-REPORT); the shared identifier widths
# (TRAN-REPORT-TRANS-ID / TRAN-REPORT-ACCOUNT-ID) are reused from
# app.utils.validators so a single definition governs every schema.
# ---------------------------------------------------------------------------
TRAN_TYPE_CD_MAX_LENGTH = 2          # TRAN-REPORT-TYPE-CD PIC X(02).
TRAN_TYPE_DESC_MAX_LENGTH = 15       # TRAN-REPORT-TYPE-DESC PIC X(15) (trimmed from 50).
TRAN_CAT_CD_LENGTH = 4               # TRAN-REPORT-CAT-CD PIC 9(04) (digits, zero-padded).
TRAN_CAT_DESC_MAX_LENGTH = 29        # TRAN-REPORT-CAT-DESC PIC X(29) (trimmed from 50).
TRAN_SOURCE_MAX_LENGTH = 10          # TRAN-REPORT-SOURCE PIC X(10).

# Report title carried over from CVTRA07Y REPORT-NAME-HEADER (REPT-LONG-NAME
# PIC X(41) VALUE 'Daily Transaction Report'). Exposed as the default
# report_name so a response is self-describing when the service does not
# override it.
REPORT_LONG_NAME = "Daily Transaction Report"

# ---------------------------------------------------------------------------
# Field labels used to build human-readable validation messages. Defined once
# so wording stays consistent across every schema class in this module.
# ---------------------------------------------------------------------------
LABEL_TRAN_CAT_CD = "Transaction Category Code"
LABEL_CONFIRM = "Confirm"

# Failure messages raised directly by this module (the utils helpers supply the
# rest verbatim from the legacy edit paragraphs).
MSG_AMOUNT_NOT_DECIMAL = (
    "Amount must be an exact decimal value (floating point is not accepted)."
)
MSG_DATE_RANGE_ORDER = "Start date must not be after end date."

# Yes/No tokens callers may send for the CONFIRM flag; matches the legacy
# CORPT00 CONFIRM X(1) accept set once upper-cased.
CONFIRM_EMPTY = ""


# ---------------------------------------------------------------------------
# Private helpers (module-internal; not part of the public API). They follow
# the codebase convention of _PascalCase private functions and delegate the
# actual edit logic to app.utils so the legacy accept/reject behavior is shared
# rather than re-implemented here.
# ---------------------------------------------------------------------------


def _EnsureValid(result: validators.ValidationResult, value: str) -> str:
    """Return ``value`` when a validation result passed; raise otherwise.

    Centralizes the ``if not result.isValid: raise ValueError(result.message)``
    pattern shared by every field edit, keeping the individual validators to a
    single expression. Accepts either a
    :class:`app.utils.validators.ValidationResult` or a
    :class:`app.utils.date_utils.DateValidationResult`; both expose the same
    ``isValid`` / ``message`` attributes.

    Args:
        result: The validation result to inspect.
        value: The value to return when the result is valid.

    Returns:
        The unchanged ``value`` when ``result.isValid`` is True.

    Raises:
        ValueError: Carrying ``result.message`` when the edit failed.
    """
    if not result.isValid:
        raise ValueError(result.message)
    return value


def _CoerceAmount(value: object) -> Decimal:
    """Coerce a monetary input to :class:`~decimal.Decimal`, rejecting float.

    Routes the raw value through :func:`app.utils.decimal_utils.ToDecimal`,
    which accepts ``str`` / ``int`` / ``Decimal`` and rejects ``float`` and
    ``bool`` with :class:`TypeError`. This guarantees that a report amount or
    total never silently absorbs binary floating-point rounding (AAP 0.7.1).
    Used as a ``mode="before"`` validator so the check runs before Pydantic's
    own numeric coercion.

    Args:
        value: The raw amount input (``str``, ``int``, or ``Decimal``).

    Returns:
        The value as an exact :class:`~decimal.Decimal`.

    Raises:
        ValueError: When the value is a ``float`` / ``bool`` or is otherwise not
            a parseable decimal number.
    """
    try:
        return decimal_utils.ToDecimal(value)
    except (TypeError, ValueError, InvalidOperation) as conversionError:
        raise ValueError(MSG_AMOUNT_NOT_DECIMAL) from conversionError


def _CheckNumericId(fieldLabel: str, value: Optional[str], length: int) -> Optional[str]:
    """Validate a fixed-length numeric identifier (digits only, zeros kept).

    Numeric code fields such as ``tran_cat_cd`` are stored as zero-padded digit
    strings and must never be coerced to ``int`` (that would drop significant
    leading zeros), so the value must be all ASCII digits AND exactly ``length``
    characters. An absent optional value (``None``) passes through unchanged.

    Args:
        fieldLabel: Human-readable field name used in the failure message.
        value: The candidate identifier (or ``None`` for an absent field).
        length: The exact required digit count.

    Returns:
        The unchanged ``value``.

    Raises:
        ValueError: When the value is present but not exactly ``length`` digits.
    """
    if value is None:
        return value
    return _EnsureValid(validators.ValidateNumericId(fieldLabel, value, length), value)


def _TruncateText(value: object, maxLength: int) -> object:
    """Truncate an over-long string to ``maxLength`` characters.

    Reproduces the report's fixed-width truncation: ``CVTRA07Y`` renders the
    50-character transaction type and category descriptions into narrower
    columns (15 and 29 characters), so any longer source text is trimmed to the
    column width rather than rejected. Non-string inputs (``None`` for an absent
    optional field, or any value Pydantic will coerce) pass through untouched so
    this helper is safe as a ``mode="before"`` validator.

    Args:
        value: The raw field input.
        maxLength: The report column width to trim to.

    Returns:
        The truncated string, or ``value`` unchanged when it is not a ``str``.
    """
    if not isinstance(value, str):
        return value
    if len(value) <= maxLength:
        return value
    return value[:maxLength]


def _ComposeReportDate(value: object) -> object:
    """Validate and normalize a report date input to a :class:`~datetime.date`.

    The report request stores composed ``date`` objects (the router composes the
    ``MM``/``DD``/``YYYY`` screen parts of ``CORPT00`` before building the DTO),
    but string inputs are also accepted for JSON callers. A string is validated
    with the full legacy edit chain via
    :func:`app.utils.date_utils.ValidateDate` (century 19/20, month 1-12, day
    1-31 with month-length and leap-year rules, plus the calendar backstop) and
    then parsed into a native ``date``; both the ``YYYY-MM-DD`` and 8-digit
    ``CCYYMMDD`` legacy layouts are supported. Any non-string value (an existing
    ``date``/``datetime`` from the router, or ``None``) passes through so
    Pydantic performs its native validation.

    Args:
        value: The raw date input (``date``, ``datetime``, ``str``, or ``None``).

    Returns:
        A :class:`~datetime.date` for string inputs, or ``value`` unchanged for
        Pydantic to finish validating.

    Raises:
        ValueError: Carrying the legacy edit message when a string fails the
            date validation chain.
    """
    if not isinstance(value, str):
        return value
    dateText = value.strip()
    _EnsureValid(date_utils.ValidateDate(dateText), dateText)
    if "-" in dateText:
        return date_utils.ParseLegacyDate(dateText)
    return date(int(dateText[0:4]), int(dateText[4:6]), int(dateText[6:8]))


def _NormalizeConfirm(value: Optional[str]) -> Optional[str]:
    """Normalize the optional CONFIRM flag to ``'Y'`` / ``'N'`` or ``None``.

    Mirrors the legacy ``CORPT00`` ``CONFIRM PIC X(1)`` field: an absent or
    blank value means "not confirmed" (``None``); any supplied value is
    upper-cased and validated against the legacy Y/N accept set via
    :func:`app.utils.validators.ValidateYesNo`.

    Args:
        value: The candidate confirm flag (already whitespace-stripped by the
            request base config), or ``None``.

    Returns:
        ``'Y'`` or ``'N'`` for a supplied flag, or ``None`` when not confirmed.

    Raises:
        ValueError: When a non-blank value is neither ``Y`` nor ``N``.
    """
    if value is None or value == CONFIRM_EMPTY:
        return None
    normalizedConfirm = value.upper()
    return _EnsureValid(
        validators.ValidateYesNo(LABEL_CONFIRM, normalizedConfirm),
        normalizedConfirm,
    )


# ---------------------------------------------------------------------------
# Public API -- report type enumeration
# ---------------------------------------------------------------------------


class ReportType(str, Enum):
    """The three report ranges offered by the report screen (``CORPT00``).

    The ``CORPT00`` map presents three mutually exclusive ``PIC X(1)`` radio
    flags -- ``MONTHLY``, ``YEARLY``, and ``CUSTOM`` -- exactly one of which is
    selected. ``CORPT00C`` maps the selected flag to the human-readable label
    used on the report header, so the enum *values* are those labels
    (``'Monthly'`` / ``'Yearly'`` / ``'Custom'``). Subclassing ``str`` keeps the
    member interchangeable with its label in JSON payloads and CSV/PDF output.

    Per the Ochs Rule the enum class is PascalCase and its members are
    ALL_UPPERCASE.
    """

    MONTHLY = "Monthly"
    YEARLY = "Yearly"
    CUSTOM = "Custom"

    @classmethod
    def _missing_(cls, value: object) -> Optional["ReportType"]:
        """Resolve a report type case-insensitively by label or member name.

        Lets callers supply any of ``'Monthly'``, ``'monthly'``, ``'MONTHLY'``
        (the member name), or the raw screen flag casing without a mismatch,
        while still rejecting genuinely unknown values (returning ``None`` makes
        :class:`~enum.Enum` raise the standard ``ValueError``).

        Args:
            value: The raw value that did not match a member directly.

        Returns:
            The matching :class:`ReportType`, or ``None`` when nothing matches.
        """
        if isinstance(value, str):
            normalizedValue = value.strip().lower()
            for member in cls:
                if normalizedValue in (member.value.lower(), member.name.lower()):
                    return member
        return None


# ---------------------------------------------------------------------------
# Public API -- report detail row (CVTRA07Y TRANSACTION-DETAIL-REPORT)
# ---------------------------------------------------------------------------


class TransactionReportRow(OrmBase):
    """One rendered row of the transaction report (``CVTRA07Y``).

    Carries exactly the columns the ``TRANSACTION-DETAIL-REPORT`` line renders,
    in report order: the transaction id, the owning account id, the transaction
    type code and its (report-trimmed) description, the category code and its
    (report-trimmed) description, the source channel, and the signed amount.
    Wrapped in :class:`ReportResponse.rows`, it feeds the on-screen Material UI
    table and the CSV/PDF generators in ``report_service``.

    Numeric fidelity: ``tran_amt`` is an exact :class:`~decimal.Decimal`
    (``NUMERIC(11, 2)``, from ``TRAN-AMT S9(09)V99``); floating point is
    rejected. The two description fields reproduce the report's fixed-width
    truncation (15 and 29 characters) rather than rejecting longer source text.
    """

    tran_id: str = Field(
        ...,
        max_length=validators.TRAN_ID_LENGTH,
        description="Transaction identifier (TRAN-REPORT-TRANS-ID X(16)).",
    )
    acct_id: str = Field(
        ...,
        max_length=validators.ACCT_ID_LENGTH,
        description="Owning account identifier (TRAN-REPORT-ACCOUNT-ID X(11)).",
    )
    tran_type_cd: str = Field(
        ...,
        max_length=TRAN_TYPE_CD_MAX_LENGTH,
        description="Transaction type code (TRAN-REPORT-TYPE-CD X(02)).",
    )
    tran_type_desc: Optional[str] = Field(
        default=None,
        max_length=TRAN_TYPE_DESC_MAX_LENGTH,
        description=(
            "Transaction type description (TRAN-REPORT-TYPE-DESC X(15)); the "
            "50-char type description trimmed to the report column width."
        ),
    )
    tran_cat_cd: str = Field(
        ...,
        max_length=TRAN_CAT_CD_LENGTH,
        description="Transaction category code (TRAN-REPORT-CAT-CD 9(04)); 4 digits.",
    )
    tran_cat_desc: Optional[str] = Field(
        default=None,
        max_length=TRAN_CAT_DESC_MAX_LENGTH,
        description=(
            "Transaction category description (TRAN-REPORT-CAT-DESC X(29)); the "
            "50-char category description trimmed to the report column width."
        ),
    )
    tran_source: Optional[str] = Field(
        default=None,
        max_length=TRAN_SOURCE_MAX_LENGTH,
        description="Transaction source channel (TRAN-REPORT-SOURCE X(10)).",
    )
    tran_amt: Decimal = Field(
        ...,
        description=(
            "Signed transaction amount (TRAN-REPORT-AMT / TRAN-AMT S9(09)V99) as "
            "an exact NUMERIC(11,2) Decimal; floating point is rejected."
        ),
    )

    @field_validator("tran_cat_cd")
    @classmethod
    def CheckTranCatCd(cls, value: str) -> str:
        """Validate ``tran_cat_cd`` is exactly ``TRAN_CAT_CD_LENGTH`` digits."""
        return _CheckNumericId(LABEL_TRAN_CAT_CD, value, TRAN_CAT_CD_LENGTH)

    @field_validator("tran_type_desc", mode="before")
    @classmethod
    def TrimTypeDesc(cls, value: object) -> object:
        """Trim the type description to the report column width (15)."""
        return _TruncateText(value, TRAN_TYPE_DESC_MAX_LENGTH)

    @field_validator("tran_cat_desc", mode="before")
    @classmethod
    def TrimCatDesc(cls, value: object) -> object:
        """Trim the category description to the report column width (29)."""
        return _TruncateText(value, TRAN_CAT_DESC_MAX_LENGTH)

    @field_validator("tran_amt", mode="before")
    @classmethod
    def CoerceTranAmount(cls, value: object) -> Decimal:
        """Coerce ``tran_amt`` to an exact ``Decimal`` and reject float input."""
        return _CoerceAmount(value)


# ---------------------------------------------------------------------------
# Public API -- report request (CORPT00 report-request screen)
# ---------------------------------------------------------------------------


class ReportRequest(RequestBase):
    """Request to generate a transaction report (``CORPT00`` / ``CORPT00C``).

    Reproduces the report-request screen: a report type (the mutually exclusive
    ``MONTHLY`` / ``YEARLY`` / ``CUSTOM`` radio flags collapse to a single
    :class:`ReportType`), an inclusive start and end date, and the optional
    ``CONFIRM`` flag. The screen collects each date as separate ``MM`` / ``DD``
    / ``YYYY`` parts (``SDT*`` / ``EDT*``); the router composes those parts into
    ``date`` objects before building this DTO, while string dates from JSON
    callers are validated through the legacy date-edit chain.

    ``RequestBase`` forbids unexpected fields (input sanitization, Ochs Rule),
    so a request carrying anything beyond these fields is rejected.
    """

    report_type: ReportType = Field(
        ...,
        description=(
            "Report range selected on CORPT00 (MONTHLY/YEARLY/CUSTOM radio "
            "flags), carried as its CORPT00C label."
        ),
    )
    start_date: date = Field(
        ...,
        description="Inclusive range start (SDTMM/SDTDD/SDTYYYY composed to a date).",
    )
    end_date: date = Field(
        ...,
        description="Inclusive range end (EDTMM/EDTDD/EDTYYYY composed to a date).",
    )
    confirm: Optional[str] = Field(
        default=None,
        description="Optional confirmation flag (CONFIRM X(1)); 'Y' or 'N' when supplied.",
    )

    @field_validator("start_date", "end_date", mode="before")
    @classmethod
    def ComposeDates(cls, value: object) -> object:
        """Validate/normalize a date input via the legacy date-edit chain."""
        return _ComposeReportDate(value)

    @field_validator("confirm")
    @classmethod
    def CheckConfirm(cls, value: Optional[str]) -> Optional[str]:
        """Normalize the optional CONFIRM flag to 'Y' / 'N' or ``None``."""
        return _NormalizeConfirm(value)

    @model_validator(mode="after")
    def CheckDateRange(self) -> "ReportRequest":
        """Enforce ``start_date <= end_date`` for the requested range.

        The legacy ``CUSTOM`` report requires both dates with the start on or
        before the end; ``MONTHLY`` / ``YEARLY`` ranges are derived by the
        router and also satisfy this ordering, so the rule is applied uniformly.
        """
        if self.start_date > self.end_date:
            raise ValueError(MSG_DATE_RANGE_ORDER)
        return self


# ---------------------------------------------------------------------------
# Public API -- report response (CVTRA07Y detail rows + totals)
# ---------------------------------------------------------------------------


class ReportResponse(OrmBase):
    """The generated transaction report: header, detail rows, and totals.

    Assembles the pieces ``CVTRA07Y`` renders as report lines into one JSON
    payload: the echoed report type and inclusive date range, the ordered detail
    :attr:`rows`, and the running page / account / grand totals
    (``REPT-PAGE-TOTAL`` / ``REPT-ACCOUNT-TOTAL`` / ``REPT-GRAND-TOTAL``, each
    ``PIC +ZZZ,ZZZ,ZZZ.ZZ``). This single payload is the source for the
    on-screen table and for the CSV and PDF renderings produced by
    ``report_service`` (the authorized reporting redesign, AAP 0.8.4).

    Every total is an exact :class:`~decimal.Decimal`; floating point is
    rejected so the summed currency values remain regulator-grade exact.
    """

    report_type: ReportType = Field(
        ...,
        description="The report range this response was generated for.",
    )
    start_date: date = Field(
        ...,
        description="Inclusive range start echoed from the request.",
    )
    end_date: date = Field(
        ...,
        description="Inclusive range end echoed from the request.",
    )
    report_name: Optional[str] = Field(
        default=None,
        description=(
            "Report title (CVTRA07Y REPT-LONG-NAME); defaults to "
            f"'{REPORT_LONG_NAME}' when the service does not override it."
        ),
    )
    rows: list[TransactionReportRow] = Field(
        default_factory=list,
        description="Ordered report detail rows (CVTRA07Y TRANSACTION-DETAIL-REPORT).",
    )
    page_total: Decimal = Field(
        default=Decimal("0.00"),
        description="Running page total (CVTRA07Y REPT-PAGE-TOTAL +ZZZ,ZZZ,ZZZ.ZZ).",
    )
    account_total: Decimal = Field(
        default=Decimal("0.00"),
        description="Per-account subtotal (CVTRA07Y REPT-ACCOUNT-TOTAL +ZZZ,ZZZ,ZZZ.ZZ).",
    )
    grand_total: Decimal = Field(
        default=Decimal("0.00"),
        description="Overall report total (CVTRA07Y REPT-GRAND-TOTAL +ZZZ,ZZZ,ZZZ.ZZ).",
    )

    @field_validator("page_total", "account_total", "grand_total", mode="before")
    @classmethod
    def CoerceTotals(cls, value: object) -> Decimal:
        """Coerce each total to an exact ``Decimal`` and reject float input."""
        return _CoerceAmount(value)
