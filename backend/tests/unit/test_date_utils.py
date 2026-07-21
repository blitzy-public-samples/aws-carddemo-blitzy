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

from app.utils.date_utils import (
    FormatLegacyDate,
    IsLeapYear,
    ParseLegacyDate,
    ParseLegacyTimestamp,
    ValidateDate,
    ValidateDateOfBirth,
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
