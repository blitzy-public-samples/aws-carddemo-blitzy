"""Legacy CardDemo date and timestamp validation, parsing, and formatting.

This module ports the mainframe CardDemo date logic to pure Python. It
reproduces, byte-for-byte in behavior, the accept/reject decisions made by the
legacy COBOL date edits so that migrated services, schemas, batch jobs, and
golden-master parity tests observe identical validation semantics.

The public API is intentionally split into three concerns:

* Component edits (``EditYear``, ``EditMonth``, ``EditDay``,
  ``EditDayMonthYear``) mirror the individual COBOL paragraphs and each return a
  :class:`DateValidationResult`.
* High-level validators (``ValidateDate``, ``ValidateDateOfBirth``) orchestrate
  the component edits and add the calendar-correctness backstop that the legacy
  program delegated to the IBM Language Environment ``CEEDAYS`` service.
* Conversion helpers (``ParseLegacyDate``, ``FormatLegacyDate``,
  ``ParseLegacyTimestamp``, ``FormatLegacyTimestamp``) translate between the
  legacy fixed-width text layouts and native :class:`datetime.date` /
  :class:`datetime.datetime` values.

The module is deliberately pure, dependency-light, import-safe, and
side-effect-free: it imports only the Python standard library and never reads
the wall clock at import time (``date.today()`` is evaluated exclusively inside
:func:`ValidateDateOfBirth`).
"""

# Ported from CSUTLDTC.cbl (LE CEEDAYS date-validation wrapper), CSUTLDPY.cpy
# (PROCEDURE DIVISION date-edit paragraphs), and CSUTLDWY.cpy (working-storage
# 88-level ranges). The COBOL CALL "CEEDAYS" calendar check becomes native
# Python date construction (datetime.date), whose ValueError signals an
# impossible calendar date.

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime
from typing import Final

# ---------------------------------------------------------------------------
# Legacy text layouts (see AAP "Legacy field facts").
# ---------------------------------------------------------------------------
# X(10) date fields are stored as "YYYY-MM-DD" (e.g. acctdata.txt "2014-11-20").
LEGACY_DATE_FORMAT: Final = "%Y-%m-%d"
# X(26) timestamp fields are "YYYY-MM-DD HH:MM:SS.ffffff" (dailytran.txt).
LEGACY_TIMESTAMP_FORMAT: Final = "%Y-%m-%d %H:%M:%S.%f"
# Documented fallback for timestamps that arrive without the fractional part.
LEGACY_TIMESTAMP_FALLBACK_FORMAT: Final = "%Y-%m-%d %H:%M:%S"

# Fixed field widths carried over from the copybooks.
DATE_TEXT_LENGTH: Final = 10
TIMESTAMP_TEXT_LENGTH: Final = 26
# The COBOL edits operate on the compact CCYYMMDD (8-digit) form.
COMPACT_DATE_LENGTH: Final = 8

# ---------------------------------------------------------------------------
# Validation ranges (CSUTLDWY.cpy 88-level values).
# ---------------------------------------------------------------------------
# CSUTLDWY: THIS-CENTURY VALUE 20, LAST-CENTURY VALUE 19.
VALID_CENTURIES: Final = frozenset({19, 20})
# CSUTLDWY: WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12.
MONTHS_WITH_31_DAYS: Final = frozenset({1, 3, 5, 7, 8, 10, 12})
# CSUTLDWY: WS-FEBRUARY VALUE 2.
FEBRUARY: Final = 2
# CSUTLDWY: WS-VALID-MONTH VALUES 1 THROUGH 12.
MIN_MONTH: Final = 1
MAX_MONTH: Final = 12
# CSUTLDWY: WS-VALID-DAY VALUES 1 THROUGH 31.
MIN_DAY: Final = 1
MAX_DAY: Final = 31
# CSUTLDPY EDIT-DAY-MONTH-YEAR leap-year divisors: 400 for century years
# (2-digit YY == 0), otherwise 4.
CENTURY_LEAP_DIVISOR: Final = 400
STANDARD_LEAP_DIVISOR: Final = 4

# ---------------------------------------------------------------------------
# Result messages. Wording is preserved from CSUTLDPY.cpy / CSUTLDTC.cbl so the
# text can surface to callers with parity to the legacy program.
# ---------------------------------------------------------------------------
MSG_DATE_REQUIRED: Final = "Date must be supplied."
MSG_DATE_MALFORMED: Final = "Date must be YYYY-MM-DD or CCYYMMDD."
MSG_YEAR_REQUIRED: Final = "Year must be supplied."
MSG_YEAR_NOT_NUMERIC: Final = "must be 4 digit number."
MSG_CENTURY_INVALID: Final = "Century is not valid."
MSG_MONTH_REQUIRED: Final = "Month must be supplied."
MSG_MONTH_RANGE: Final = "Month must be a number between 1 and 12."
MSG_DAY_REQUIRED: Final = "Day must be supplied."
MSG_DAY_RANGE: Final = "day must be a number between 1 and 31."
MSG_NO_31: Final = "Cannot have 31 days in this month."
MSG_NO_30_FEB: Final = "Cannot have 30 days in this month."
MSG_NOT_LEAP: Final = "Not a leap year.Cannot have 29 days in this month."
MSG_FUTURE_DOB: Final = "cannot be in the future"
MSG_INVALID: Final = "Date is invalid"
MSG_VALID: Final = "Date is valid"


@dataclass(frozen=True)
class DateValidationResult:
    """Immutable outcome of a date edit.

    Grouping the outcome into a single value keeps every validator at a single
    return object (honoring the Ochs "<=4 parameters" rule).

    Attributes:
        isValid: True when the edited value passed the check.
        message: Human-readable status; ``MSG_VALID`` on success, otherwise the
            legacy failure wording that explains the rejection.
    """

    isValid: bool
    message: str


def _IsNumericText(text: str) -> bool:
    """Report whether ``text`` is a non-empty run of ASCII digits 0-9.

    This mirrors the COBOL ``IS NUMERIC`` / ``TEST-NUMVAL`` guards while
    rejecting Unicode digit look-alikes (e.g. superscripts) that ``str.isdigit``
    would otherwise accept, keeping downstream ``int(...)`` calls safe.

    Args:
        text: Candidate text, already stripped of surrounding whitespace.

    Returns:
        True if every character is an ASCII decimal digit and ``text`` is
        non-empty; False otherwise.
    """
    return text.isascii() and text.isdigit()


def _ExtractCompactDate(text: str) -> str | None:
    """Reduce a legacy date string to its 8-digit CCYYMMDD form.

    Accepts both the X(10) ``YYYY-MM-DD`` layout and a raw 8-digit
    ``CCYYMMDD`` string, matching the two forms the ported callers pass. Any
    other shape is treated as malformed input and rejected.

    Args:
        text: Stripped date text to normalize.

    Returns:
        The 8-character CCYYMMDD string when the input is well-formed, or None
        when it cannot be normalized to eight digits.
    """
    candidate = None
    if len(text) == DATE_TEXT_LENGTH and text[4] == "-" and text[7] == "-":
        candidate = text[0:4] + text[5:7] + text[8:10]
    elif len(text) == COMPACT_DATE_LENGTH:
        candidate = text
    if candidate is not None and _IsNumericText(candidate):
        return candidate
    return None


def IsLeapYear(year: int) -> bool:
    """Return whether ``year`` is a leap year using the CSUTLDPY divisor rule.

    CSUTLDPY ``EDIT-DAY-MONTH-YEAR`` selects divisor 400 for century years
    (2-digit ``YY`` == 0) and divisor 4 otherwise, then treats a zero remainder
    as a leap year. That is exactly equivalent to the proleptic Gregorian rule
    (century years must be divisible by 400, other years by 4).

    Args:
        year: Four-digit Gregorian year.

    Returns:
        True when ``year`` is a leap year; False otherwise.
    """
    yearWithinCentury = year % 100
    divisor = CENTURY_LEAP_DIVISOR if yearWithinCentury == 0 else STANDARD_LEAP_DIVISOR
    return year % divisor == 0


def EditYear(ccyy: str) -> DateValidationResult:
    """Validate a 4-digit CCYY value (CSUTLDPY ``EDIT-YEAR-CCYY``).

    Enforces, in the legacy order: value supplied, value is a 4-digit number,
    and century is 19 or 20.

    Args:
        ccyy: Candidate century+year text (expected four ASCII digits).

    Returns:
        A :class:`DateValidationResult` carrying the first failing message, or
        ``MSG_VALID`` when the year passes every check.
    """
    yearText = ccyy.strip() if ccyy is not None else ""
    if not yearText:
        return DateValidationResult(False, MSG_YEAR_REQUIRED)
    if not _IsNumericText(yearText) or len(yearText) != 4:
        return DateValidationResult(False, MSG_YEAR_NOT_NUMERIC)
    century = int(yearText[0:2])
    if century not in VALID_CENTURIES:
        return DateValidationResult(False, MSG_CENTURY_INVALID)
    return DateValidationResult(True, MSG_VALID)


def EditMonth(monthValue: str) -> DateValidationResult:
    """Validate a month value (CSUTLDPY ``EDIT-MONTH``).

    Enforces: value supplied, numeric, and within 1..12. Both the non-numeric
    and out-of-range rejections surface the same legacy wording, matching the
    COBOL paragraph.

    Args:
        monthValue: Candidate month text (expected one or two ASCII digits).

    Returns:
        A :class:`DateValidationResult` with the failing message or
        ``MSG_VALID``.
    """
    monthText = monthValue.strip() if monthValue is not None else ""
    if not monthText:
        return DateValidationResult(False, MSG_MONTH_REQUIRED)
    if not _IsNumericText(monthText):
        return DateValidationResult(False, MSG_MONTH_RANGE)
    monthNumber = int(monthText)
    if monthNumber < MIN_MONTH or monthNumber > MAX_MONTH:
        return DateValidationResult(False, MSG_MONTH_RANGE)
    return DateValidationResult(True, MSG_VALID)


def EditDay(dayValue: str) -> DateValidationResult:
    """Validate a day value (CSUTLDPY ``EDIT-DAY``).

    Enforces: value supplied, numeric, and within 1..31. The month-specific
    limits (28/29/30) are applied later by :func:`EditDayMonthYear`, mirroring
    the two-stage COBOL edit.

    Args:
        dayValue: Candidate day text (expected one or two ASCII digits).

    Returns:
        A :class:`DateValidationResult` with the failing message or
        ``MSG_VALID``.
    """
    dayText = dayValue.strip() if dayValue is not None else ""
    if not dayText:
        return DateValidationResult(False, MSG_DAY_REQUIRED)
    if not _IsNumericText(dayText):
        return DateValidationResult(False, MSG_DAY_RANGE)
    dayNumber = int(dayText)
    if dayNumber < MIN_DAY or dayNumber > MAX_DAY:
        return DateValidationResult(False, MSG_DAY_RANGE)
    return DateValidationResult(True, MSG_VALID)


def EditDayMonthYear(dateParts: tuple[int, int, int]) -> DateValidationResult:
    """Validate the day/month/year combination (CSUTLDPY ``EDIT-DAY-MONTH-YEAR``).

    Applies the cross-field rules the individual component edits cannot see:
    31-day months, the February-30 impossibility, and the February-29 leap-year
    test. ``dateParts`` is a single tuple to keep the signature at one
    parameter (Ochs "<=4 parameters" rule).

    Args:
        dateParts: Ordered ``(year, month, day)`` integers, each already
            confirmed in range by the component edits.

    Returns:
        A :class:`DateValidationResult` with the failing message or
        ``MSG_VALID``.
    """
    yearNumber, monthNumber, dayNumber = dateParts
    if dayNumber == MAX_DAY and monthNumber not in MONTHS_WITH_31_DAYS:
        return DateValidationResult(False, MSG_NO_31)
    if monthNumber == FEBRUARY and dayNumber == 30:
        return DateValidationResult(False, MSG_NO_30_FEB)
    if monthNumber == FEBRUARY and dayNumber == 29:
        if not IsLeapYear(yearNumber):
            return DateValidationResult(False, MSG_NOT_LEAP)
    return DateValidationResult(True, MSG_VALID)


def _RunDateEdits(compactDate: str) -> DateValidationResult:
    """Run the year/month/day/combination edits in the legacy order.

    Short-circuits on the first failing edit, mirroring the COBOL
    ``GO TO ...-EXIT`` control flow that abandons the remaining edits once an
    error is found.

    Args:
        compactDate: An 8-digit CCYYMMDD string (validated by the caller).

    Returns:
        The first failing :class:`DateValidationResult`, or ``MSG_VALID`` when
        every component edit passes.
    """
    ccyyText = compactDate[0:4]
    monthText = compactDate[4:6]
    dayText = compactDate[6:8]
    yearResult = EditYear(ccyyText)
    if not yearResult.isValid:
        return yearResult
    monthResult = EditMonth(monthText)
    if not monthResult.isValid:
        return monthResult
    dayResult = EditDay(dayText)
    if not dayResult.isValid:
        return dayResult
    return EditDayMonthYear((int(ccyyText), int(monthText), int(dayText)))


def _ValidateCalendarDate(compactDate: str) -> DateValidationResult:
    """Apply the calendar-correctness backstop (CSUTLDPY ``EDIT-DATE-LE``).

    The legacy program made a final ``CALL "CEEDAYS"`` after the field edits to
    catch any impossible date that slipped through; severity ``0`` meant valid.
    The Python equivalent constructs a :class:`datetime.date`, whose successful
    construction is the calendar-correctness proof and whose ``ValueError``
    signals an invalid calendar date.

    Args:
        compactDate: An 8-digit CCYYMMDD string that already passed the edits.

    Returns:
        ``MSG_VALID`` when the date constructs successfully, otherwise an
        invalid result carrying ``MSG_INVALID``.
    """
    yearNumber = int(compactDate[0:4])
    monthNumber = int(compactDate[4:6])
    dayNumber = int(compactDate[6:8])
    try:
        date(yearNumber, monthNumber, dayNumber)
    except ValueError:
        return DateValidationResult(False, MSG_INVALID)
    return DateValidationResult(True, MSG_VALID)


def ValidateDate(dateText: str) -> DateValidationResult:
    """Validate a required legacy date string (CSUTLDPY ``EDIT-DATE-CCYYMMDD``).

    Accepts the X(10) ``YYYY-MM-DD`` layout or a raw 8-digit ``CCYYMMDD``
    string. Callers pass required date fields here; a blank value is therefore
    rejected as "must be supplied". Optional/nullable columns should be
    null-checked by the caller before invoking this validator.

    The check order reproduces the COBOL edits exactly: normalize, run the
    year/month/day/combination edits (short-circuiting on first failure), then
    apply the calendar backstop.

    Args:
        dateText: The date text to validate.

    Returns:
        A :class:`DateValidationResult`; ``isValid`` is True only when every
        legacy edit and the calendar backstop pass.
    """
    if dateText is None:
        return DateValidationResult(False, MSG_DATE_REQUIRED)
    normalizedText = dateText.strip()
    if not normalizedText:
        return DateValidationResult(False, MSG_DATE_REQUIRED)
    compactDate = _ExtractCompactDate(normalizedText)
    if compactDate is None:
        return DateValidationResult(False, MSG_DATE_MALFORMED)
    editResult = _RunDateEdits(compactDate)
    if not editResult.isValid:
        return editResult
    return _ValidateCalendarDate(compactDate)


def ValidateDateOfBirth(dateText: str) -> DateValidationResult:
    """Validate a date of birth (CSUTLDPY ``EDIT-DATE-OF-BIRTH``).

    A DOB must first be a valid calendar date and then must not lie in the
    future. The legacy rule ``WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY``
    accepts a DOB only when today is strictly greater than the birth date, so a
    DOB equal to today is rejected as future-dated. ``date.today()`` is read
    only here, inside the function, never at import time.

    Args:
        dateText: The birth-date text to validate.

    Returns:
        A :class:`DateValidationResult`; invalid with ``MSG_FUTURE_DOB`` when the
        date is today or later, otherwise the underlying validation result.
    """
    baseResult = ValidateDate(dateText)
    if not baseResult.isValid:
        return baseResult
    compactDate = _ExtractCompactDate(dateText.strip())
    birthDate = date(int(compactDate[0:4]), int(compactDate[4:6]), int(compactDate[6:8]))
    if birthDate < date.today():
        return DateValidationResult(True, MSG_VALID)
    return DateValidationResult(False, MSG_FUTURE_DOB)


def ParseLegacyDate(dateText: str) -> date:
    """Strictly parse a legacy ``YYYY-MM-DD`` date string into a ``date``.

    This is the strict, exception-raising counterpart to :func:`ValidateDate`;
    callers that want a boolean-style outcome should use :func:`ValidateDate`.
    Only the ``ValueError`` raised by :func:`datetime.datetime.strptime` is
    caught, and it is re-raised with clearer context rather than swallowed.

    Args:
        dateText: A ``YYYY-MM-DD`` date string.

    Returns:
        The parsed :class:`datetime.date`.

    Raises:
        ValueError: If ``dateText`` is None or does not match ``YYYY-MM-DD``.
    """
    if dateText is None:
        raise ValueError("Legacy date text must not be None.")
    try:
        return datetime.strptime(dateText.strip(), LEGACY_DATE_FORMAT).date()
    except ValueError as parseError:
        raise ValueError(f"Invalid legacy date '{dateText}': {parseError}") from parseError


def FormatLegacyDate(dateValue: date) -> str:
    """Format a ``date`` as legacy X(10) ``YYYY-MM-DD`` text.

    Args:
        dateValue: The date to render.

    Returns:
        The ``YYYY-MM-DD`` string form of ``dateValue``.
    """
    return dateValue.strftime(LEGACY_DATE_FORMAT)


def ParseLegacyTimestamp(timestampText: str) -> datetime:
    """Parse a legacy X(26) timestamp string into a ``datetime``.

    The primary layout is ``YYYY-MM-DD HH:MM:SS.ffffff`` (26 characters). A
    documented fallback accepts the same value without the fractional seconds.
    Only ``ValueError`` from :func:`datetime.datetime.strptime` is caught; if no
    layout matches, a clear ``ValueError`` is raised.

    Args:
        timestampText: The timestamp text to parse.

    Returns:
        The parsed :class:`datetime.datetime`.

    Raises:
        ValueError: If ``timestampText`` is None or matches no supported layout.
    """
    if timestampText is None:
        raise ValueError("Legacy timestamp text must not be None.")
    cleanedText = timestampText.strip()
    for timestampFormat in (LEGACY_TIMESTAMP_FORMAT, LEGACY_TIMESTAMP_FALLBACK_FORMAT):
        try:
            return datetime.strptime(cleanedText, timestampFormat)
        except ValueError:
            continue
    raise ValueError(f"Invalid legacy timestamp '{timestampText}'.")


def FormatLegacyTimestamp(timestampValue: datetime) -> str:
    """Format a ``datetime`` as the 26-character legacy timestamp text.

    Python's ``%f`` directive always emits six fractional digits, reproducing
    the legacy ``.000000`` suffix exactly.

    Args:
        timestampValue: The datetime to render.

    Returns:
        The ``YYYY-MM-DD HH:MM:SS.ffffff`` string form of ``timestampValue``.
    """
    return timestampValue.strftime(LEGACY_TIMESTAMP_FORMAT)

