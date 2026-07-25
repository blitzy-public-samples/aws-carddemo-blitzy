# Unit tests for app.utils.date_utils
# Traceability: app/cbl/CSUTLDTC.cbl (date validation via LE CEEDAYS)
"""Pure-function unit tests for the legacy CardDemo date utilities.

These tests exercise :mod:`app.utils.date_utils`, the Python port of the COBOL
date-validation utility ``CSUTLDTC`` (a thin wrapper over the IBM Language
Environment ``CEEDAYS`` service). They verify the calendar / leap-year /
century edits and the legacy date/timestamp parse-format round trips so that
migrated services, schemas, and batch jobs observe identical accept/reject
semantics to the mainframe program.

The suite is intentionally hermetic: no database, no fixtures, and no network
access are used. Every function under test is a deterministic pure function,
with the sole exception of :func:`ValidateDateOfBirth`, whose "not in the
future" rule reads the wall clock via ``date.today()``; the future-date test
below therefore derives its input dynamically from ``date.today()`` rather than
hardcoding a year that could eventually drift into the past.
"""

from datetime import date, datetime

import pytest

from app.utils.date_utils import (
    MSG_CENTURY_INVALID,
    MSG_DATE_MALFORMED,
    MSG_DATE_REQUIRED,
    MSG_DAY_RANGE,
    MSG_DAY_REQUIRED,
    MSG_INVALID,
    MSG_MONTH_RANGE,
    MSG_MONTH_REQUIRED,
    MSG_NO_31,
    MSG_YEAR_NOT_NUMERIC,
    MSG_YEAR_REQUIRED,
    EditDay,
    EditDayMonthYear,
    EditMonth,
    EditYear,
    FormatLegacyDate,
    FormatLegacyTimestamp,
    IsLeapYear,
    ParseLegacyDate,
    ParseLegacyTimestamp,
    ValidateDate,
    ValidateDateOfBirth,
    _ValidateCalendarDate,
)

# ---------------------------------------------------------------------------
# Phase A - ValidateDate happy path.
# ---------------------------------------------------------------------------


def test_validate_date_accepts_standard_date():
    """A well-formed, unambiguous calendar date is accepted."""
    assert ValidateDate("2014-11-20").isValid is True


def test_validate_date_accepts_leap_day_2020():
    """February 29 is accepted in 2020, an ordinary (divisible-by-4) leap year."""
    assert ValidateDate("2020-02-29").isValid is True


def test_validate_date_accepts_leap_day_2000():
    """February 29 is accepted in 2000; a century year divisible by 400 is a leap year."""
    assert ValidateDate("2000-02-29").isValid is True


# ---------------------------------------------------------------------------
# Phase B - ValidateDate rejections.
# ---------------------------------------------------------------------------


def test_validate_date_rejects_invalid_century():
    """A century outside VALID_CENTURIES (19/20) is rejected; 1850 has century 18."""
    assert ValidateDate("1850-01-01").isValid is False


def test_validate_date_rejects_non_leap_feb_29():
    """February 29 is rejected in 2021, which is not a leap year."""
    assert ValidateDate("2021-02-29").isValid is False


def test_validate_date_rejects_non_400_century_year():
    """February 29 is rejected in 1900; divisible by 100 but not 400 means not a leap year."""
    assert ValidateDate("1900-02-29").isValid is False


def test_validate_date_rejects_february_thirty():
    """February can never have 30 days, so 2021-02-30 is rejected."""
    assert ValidateDate("2021-02-30").isValid is False


def test_validate_date_rejection_has_message():
    """A rejected date carries a non-empty, human-readable legacy message."""
    result = ValidateDate("1850-01-01")
    assert result.isValid is False
    assert isinstance(result.message, str)
    assert result.message


# ---------------------------------------------------------------------------
# Phase C - IsLeapYear pure Gregorian rule (returns real bools).
# ---------------------------------------------------------------------------


def test_is_leap_year_2000_true():
    """2000 is divisible by 400, so it is a leap year."""
    assert IsLeapYear(2000) is True


def test_is_leap_year_1900_false():
    """1900 is divisible by 100 but not 400, so it is not a leap year."""
    assert IsLeapYear(1900) is False


def test_is_leap_year_2024_true():
    """2024 is divisible by 4 (non-century), so it is a leap year."""
    assert IsLeapYear(2024) is True


def test_is_leap_year_2023_false():
    """2023 is not divisible by 4, so it is not a leap year."""
    assert IsLeapYear(2023) is False


# ---------------------------------------------------------------------------
# Phase D - Legacy date parse/format round trip (LEGACY_DATE_FORMAT).
# ---------------------------------------------------------------------------


def test_parse_legacy_date_returns_date():
    """A legacy YYYY-MM-DD string parses into the matching date object."""
    assert ParseLegacyDate("2014-11-20") == date(2014, 11, 20)


def test_format_legacy_date_returns_text():
    """A date object formats back into the legacy YYYY-MM-DD text."""
    assert FormatLegacyDate(date(2014, 11, 20)) == "2014-11-20"


def test_legacy_date_round_trip():
    """Parsing then formatting reproduces the original legacy date text exactly."""
    assert FormatLegacyDate(ParseLegacyDate("2014-11-20")) == "2014-11-20"


# ---------------------------------------------------------------------------
# Phase E - Legacy timestamp parse (LEGACY_TIMESTAMP_FORMAT).
# ---------------------------------------------------------------------------


def test_parse_legacy_timestamp_zero_microseconds():
    """A legacy 26-char timestamp with a zero fractional part parses exactly."""
    parsedTimestamp = ParseLegacyTimestamp("2022-06-10 19:27:53.000000")
    assert parsedTimestamp == datetime(2022, 6, 10, 19, 27, 53)


# ---------------------------------------------------------------------------
# Phase F - ValidateDateOfBirth future rejection.
# ---------------------------------------------------------------------------


def test_validate_date_of_birth_rejects_future_date():
    """A date of birth in the future is rejected.

    The future date is derived dynamically from ``date.today()`` (one year
    ahead) so the assertion never rots into the past; it is never hardcoded.
    """
    today = date.today()
    futureDate = FormatLegacyDate(today.replace(year=today.year + 1))
    assert ValidateDateOfBirth(futureDate).isValid is False


# ---------------------------------------------------------------------------
# Phase G - EditDayMonthYear: the "31 days in a 30-day month" rejection.
#
# These pin the CSUTLDPY EDIT-DAY-MONTH-YEAR "NO-31" branch (MSG_NO_31). They
# assert the EXACT legacy message so the test fails if that branch ever stops
# rejecting -- a plain isValid-only assertion on ValidateDate would survive the
# mutation because the calendar backstop (Phase H) also rejects the same date.
# ---------------------------------------------------------------------------


def test_edit_day_month_year_rejects_31_in_april():
    """April (a 30-day month) cannot have a 31st, rejected with MSG_NO_31."""
    result = EditDayMonthYear((2021, 4, 31))
    assert result.isValid is False
    assert result.message == MSG_NO_31


def test_edit_day_month_year_rejects_31_in_june():
    """June (a 30-day month) cannot have a 31st, rejected with MSG_NO_31."""
    result = EditDayMonthYear((2021, 6, 31))
    assert result.isValid is False
    assert result.message == MSG_NO_31


def test_edit_day_month_year_rejects_31_in_september():
    """September (a 30-day month) cannot have a 31st, rejected with MSG_NO_31."""
    result = EditDayMonthYear((2021, 9, 31))
    assert result.isValid is False
    assert result.message == MSG_NO_31


def test_edit_day_month_year_rejects_31_in_november():
    """November (a 30-day month) cannot have a 31st, rejected with MSG_NO_31."""
    result = EditDayMonthYear((2021, 11, 31))
    assert result.isValid is False
    assert result.message == MSG_NO_31


def test_edit_day_month_year_accepts_31_in_january():
    """January is a 31-day month, so day 31 passes the combination edit."""
    assert EditDayMonthYear((2021, 1, 31)).isValid is True


def test_validate_date_rejects_31_in_30_day_month_with_no31_message():
    """Through the public API, a day-31/30-day-month date reports MSG_NO_31.

    This pins the NO_31 branch at the ValidateDate level: if EDIT-DAY-MONTH-YEAR
    stopped rejecting, the message would fall through to the calendar backstop's
    MSG_INVALID, so asserting the exact MSG_NO_31 text catches the regression.
    """
    result = ValidateDate("2021-04-31")
    assert result.isValid is False
    assert result.message == MSG_NO_31


# ---------------------------------------------------------------------------
# Phase H - _ValidateCalendarDate: the CEEDAYS calendar-correctness backstop.
#
# The legacy program made a final CALL "CEEDAYS" to reject any impossible date
# that slipped past the field edits; the Python port constructs datetime.date
# and maps its ValueError to MSG_INVALID. This branch is exercised directly
# because every impossible date is normally caught earlier by the field edits.
# ---------------------------------------------------------------------------


def test_validate_calendar_date_rejects_impossible_date():
    """An impossible calendar date fails date() construction and returns MSG_INVALID."""
    result = _ValidateCalendarDate("20210431")
    assert result.isValid is False
    assert result.message == MSG_INVALID


def test_validate_calendar_date_accepts_real_calendar_date():
    """A genuine calendar date constructs successfully and is reported valid."""
    assert _ValidateCalendarDate("20141120").isValid is True


# ---------------------------------------------------------------------------
# Phase I - EditYear component edit (CSUTLDPY EDIT-YEAR-CCYY).
# ---------------------------------------------------------------------------


def test_edit_year_rejects_blank():
    """A supplied-but-empty year is rejected with MSG_YEAR_REQUIRED."""
    assert EditYear("").message == MSG_YEAR_REQUIRED


def test_edit_year_rejects_whitespace_only():
    """A whitespace-only year strips to empty and is rejected as required."""
    assert EditYear("   ").message == MSG_YEAR_REQUIRED


def test_edit_year_rejects_non_numeric():
    """A non-numeric year is rejected with MSG_YEAR_NOT_NUMERIC."""
    assert EditYear("20AB").message == MSG_YEAR_NOT_NUMERIC


def test_edit_year_rejects_wrong_length():
    """A numeric year that is not exactly four digits is rejected."""
    assert EditYear("202").message == MSG_YEAR_NOT_NUMERIC


def test_edit_year_rejects_invalid_century():
    """A century outside 19/20 (VALID_CENTURIES) is rejected with MSG_CENTURY_INVALID."""
    assert EditYear("1850").message == MSG_CENTURY_INVALID


def test_edit_year_accepts_valid_year():
    """A four-digit year in a valid century passes the edit."""
    assert EditYear("2021").isValid is True


# ---------------------------------------------------------------------------
# Phase J - EditMonth component edit (CSUTLDPY EDIT-MONTH).
# ---------------------------------------------------------------------------


def test_edit_month_rejects_blank():
    """A supplied-but-empty month is rejected with MSG_MONTH_REQUIRED."""
    assert EditMonth("").message == MSG_MONTH_REQUIRED


def test_edit_month_rejects_non_numeric():
    """A non-numeric month surfaces the shared MSG_MONTH_RANGE wording."""
    assert EditMonth("AB").message == MSG_MONTH_RANGE


def test_edit_month_rejects_above_twelve():
    """A month above 12 is out of range (MSG_MONTH_RANGE)."""
    assert EditMonth("13").message == MSG_MONTH_RANGE


def test_edit_month_rejects_zero():
    """Month 0 is below the 1..12 range (MSG_MONTH_RANGE)."""
    assert EditMonth("00").message == MSG_MONTH_RANGE


def test_edit_month_accepts_valid_month():
    """A month within 1..12 passes the edit."""
    assert EditMonth("12").isValid is True


# ---------------------------------------------------------------------------
# Phase K - EditDay component edit (CSUTLDPY EDIT-DAY).
# ---------------------------------------------------------------------------


def test_edit_day_rejects_blank():
    """A supplied-but-empty day is rejected with MSG_DAY_REQUIRED."""
    assert EditDay("").message == MSG_DAY_REQUIRED


def test_edit_day_rejects_non_numeric():
    """A non-numeric day surfaces the shared MSG_DAY_RANGE wording."""
    assert EditDay("XY").message == MSG_DAY_RANGE


def test_edit_day_rejects_above_thirty_one():
    """A day above 31 is out of range (MSG_DAY_RANGE)."""
    assert EditDay("32").message == MSG_DAY_RANGE


def test_edit_day_rejects_zero():
    """Day 0 is below the 1..31 range (MSG_DAY_RANGE)."""
    assert EditDay("00").message == MSG_DAY_RANGE


def test_edit_day_accepts_valid_day():
    """A day within 1..31 passes the component edit (month limits applied later)."""
    assert EditDay("31").isValid is True


# ---------------------------------------------------------------------------
# Phase L - ValidateDate normalization and short-circuit control flow.
# ---------------------------------------------------------------------------


def test_validate_date_accepts_compact_ccyymmdd():
    """The raw 8-digit CCYYMMDD form is accepted, mirroring the ported callers."""
    assert ValidateDate("20141120").isValid is True


def test_validate_date_compact_matches_dashed_form():
    """The compact and X(10) dashed forms yield the identical validation result."""
    assert ValidateDate("20141120") == ValidateDate("2014-11-20")


def test_validate_date_rejects_none():
    """A None date is rejected as must-be-supplied (MSG_DATE_REQUIRED)."""
    assert ValidateDate(None).message == MSG_DATE_REQUIRED


def test_validate_date_rejects_whitespace_only():
    """A whitespace-only date strips to empty and is rejected as required."""
    assert ValidateDate("   ").message == MSG_DATE_REQUIRED


def test_validate_date_rejects_malformed_shape():
    """A value that is neither YYYY-MM-DD nor CCYYMMDD is rejected as malformed."""
    assert ValidateDate("2014/11/20").message == MSG_DATE_MALFORMED


def test_validate_date_short_circuits_on_bad_month():
    """A bad month stops the edit chain and returns the month message."""
    assert ValidateDate("2021-13-01").message == MSG_MONTH_RANGE


def test_validate_date_short_circuits_on_bad_day():
    """A bad day stops the edit chain and returns the day message."""
    assert ValidateDate("2021-12-32").message == MSG_DAY_RANGE


# ---------------------------------------------------------------------------
# Phase M - ValidateDateOfBirth base-validation propagation and past accept.
# ---------------------------------------------------------------------------


def test_validate_date_of_birth_propagates_base_rejection():
    """A DOB that fails the underlying date edit surfaces that same rejection."""
    assert ValidateDateOfBirth("2021-13-01").message == MSG_MONTH_RANGE


def test_validate_date_of_birth_accepts_clearly_past_date():
    """A birth date safely in the past is accepted (not future-dated)."""
    assert ValidateDateOfBirth("1990-01-01").isValid is True


# ---------------------------------------------------------------------------
# Phase N - ParseLegacyDate strict parsing (None and malformed raise ValueError).
# ---------------------------------------------------------------------------


def test_parse_legacy_date_rejects_none():
    """A None input raises a specific ValueError (never returns a date)."""
    with pytest.raises(ValueError, match="must not be None"):
        ParseLegacyDate(None)


def test_parse_legacy_date_rejects_invalid_text():
    """A non-parseable date raises ValueError with contextual wording."""
    with pytest.raises(ValueError, match="Invalid legacy date"):
        ParseLegacyDate("2021-13-40")


# ---------------------------------------------------------------------------
# Phase O - ParseLegacyTimestamp (None, fallback layout, no-match) + format.
# ---------------------------------------------------------------------------


def test_parse_legacy_timestamp_rejects_none():
    """A None timestamp raises a specific ValueError."""
    with pytest.raises(ValueError, match="must not be None"):
        ParseLegacyTimestamp(None)


def test_parse_legacy_timestamp_accepts_fallback_without_fraction():
    """A timestamp without a fractional part parses via the documented fallback."""
    parsedTimestamp = ParseLegacyTimestamp("2022-06-10 19:27:53")
    assert parsedTimestamp == datetime(2022, 6, 10, 19, 27, 53)


def test_parse_legacy_timestamp_rejects_unparseable():
    """A timestamp matching no supported layout raises a specific ValueError."""
    with pytest.raises(ValueError, match="Invalid legacy timestamp"):
        ParseLegacyTimestamp("garbage")


def test_format_legacy_timestamp_emits_six_fraction_digits():
    """A datetime formats to the 26-char legacy text with a .000000 suffix."""
    formattedText = FormatLegacyTimestamp(datetime(2022, 6, 10, 19, 27, 53))
    assert formattedText == "2022-06-10 19:27:53.000000"

