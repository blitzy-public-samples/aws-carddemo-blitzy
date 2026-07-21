# Unit tests for app.utils.decimal_utils
# Traceability: COBOL signed zoned-decimal DISPLAY money fields PIC S9(n)V99.
#   - app/cpy/CVACT01Y.cpy  L7-9,L13-14  ACCT-CURR-BAL / ACCT-CREDIT-LIMIT /
#                                        ACCT-CASH-CREDIT-LIMIT / ACCT-CURR-CYC-CREDIT /
#                                        ACCT-CURR-CYC-DEBIT  PIC S9(10)V99  (NOT COMP-3)
#   - app/cpy/CVTRA05Y.cpy  L10          TRAN-AMT  PIC S9(09)V99
#   - app/cbl/CBACT04C.cbl  L464-465     COMPUTE WS-MONTHLY-INT =
#                                        (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
#                                        (NO ROUNDED phrase => ROUND_DOWN truncation)
#
# Verifies AAP section 0.7.1 (Finding #1: monetary fields are signed
# zoned-decimal DISPLAY, not packed COMP-3) and section 0.7.2 (the legacy
# monthly-interest COMPUTE carries no ROUNDED phrase, so it truncates toward
# zero to two decimals).
#
# These are pure-function tests: NO database, NO fixtures, NO network, NO
# conftest imports. They are synchronous plain ``def test_*`` functions -- the
# project-wide asyncio_mode="auto" does not apply because none of them are
# coroutines. Ochs naming (AAP section 0.8.2 / 0.8.3) is honored: snake_case
# test-function names (the pytest discovery contract), camelCase local
# variables, ALL_UPPERCASE module-level constants, 4-space indentation, and one
# asserted behavior per test. Every money assertion compares with ``==``
# against a string-constructed ``Decimal`` literal; ``pytest.approx`` is never
# used for a monetary or interest value.

from decimal import Decimal

import pytest

from app.utils.decimal_utils import (
    DecodeZonedDecimal,
    ToDecimal,
    TruncateToCents,
)

# Fixed monthly-interest divisor taken verbatim from CBACT04C.cbl L465
# (percent-to-fraction /100 combined with the annual-to-monthly /12 => /1200).
# Constructed from an int so the divisor can never introduce binary float.
INTEREST_MONTHLY_DIVISOR = Decimal(1200)


# ---------------------------------------------------------------------------
# Phase A -- DecodeZonedDecimal overpunch (positive)
# ---------------------------------------------------------------------------


def test_decode_positive_overpunch_trailing_open_brace():
    # Trailing '{' is the positive overpunch for the digit 0; with scale=2 the
    # implied "V99" decimal point falls before the final two digits.
    rawValue = "00000001940{"
    decodedAmount = DecodeZonedDecimal(rawValue, 2)
    assert decodedAmount == Decimal("194.00")


def test_decode_positive_overpunch_g_is_plus_seven():
    # 'G' is the positive overpunch for the digit 7.
    rawValue = "0000005047G"
    decodedAmount = DecodeZonedDecimal(rawValue, 2)
    assert decodedAmount == Decimal("504.77")


def test_decode_positive_overpunch_h_is_plus_eight():
    # 'H' is the positive overpunch for the digit 8.
    rawValue = "0000000678H"
    decodedAmount = DecodeZonedDecimal(rawValue, 2)
    assert decodedAmount == Decimal("67.88")


def test_decode_all_zeros_plain_digit_is_positive():
    # A plain trailing ASCII digit (no overpunch byte) is treated as positive,
    # so an all-zero field decodes to exactly 0.00.
    rawValue = "00000000000"
    decodedAmount = DecodeZonedDecimal(rawValue, 2)
    assert decodedAmount == Decimal("0.00")


# ---------------------------------------------------------------------------
# Phase B -- DecodeZonedDecimal overpunch (negative)
# ---------------------------------------------------------------------------


def test_decode_negative_overpunch_trailing_close_brace():
    # Trailing '}' is the negative overpunch for the digit 0.
    rawValue = "0000009190}"
    decodedAmount = DecodeZonedDecimal(rawValue, 2)
    assert decodedAmount == Decimal("-919.00")


def test_decode_negative_overpunch_j_is_minus_one():
    # 'J' is the negative overpunch for the digit 1, so the constructed field
    # decodes to a negative magnitude with the final digit 1.
    rawValue = "0000000123J"
    decodedAmount = DecodeZonedDecimal(rawValue, 2)
    assert decodedAmount == Decimal("-12.31")
    assert decodedAmount < 0


# ---------------------------------------------------------------------------
# Phase C -- DecodeZonedDecimal error handling
# ---------------------------------------------------------------------------


def test_decode_illegal_sign_nibble_raises_value_error():
    # 'Z' in the final byte is neither a legal overpunch character nor a plain
    # ASCII digit, so decoding must raise ValueError (specific, not Exception).
    rawValue = "0000000000Z"
    with pytest.raises(ValueError):
        DecodeZonedDecimal(rawValue, 2)


# ---------------------------------------------------------------------------
# Phase D -- ToDecimal float rejection (regulatory, AAP section 0.7.1)
# ---------------------------------------------------------------------------


def test_to_decimal_accepts_str_and_int():
    # str and int are safe exact inputs and convert without float contamination.
    fromString = ToDecimal("194.00")
    fromInt = ToDecimal(194)
    assert fromString == Decimal("194.00")
    assert fromInt == Decimal("194")


def test_to_decimal_rejects_float_type_error():
    # Passing a Python float MUST raise TypeError and never silently coerce:
    # binary floating-point rounding is a compliance failure for monetary
    # values (AAP section 0.7.1). This is the single most important assertion
    # in this file.
    floatValue = 194.00
    with pytest.raises(TypeError):
        ToDecimal(floatValue)


# ---------------------------------------------------------------------------
# Phase E -- TruncateToCents (ROUND_DOWN, AAP section 0.7.2)
# ---------------------------------------------------------------------------


def test_truncate_positive_truncates_not_rounds():
    # 1.999 truncates DOWN to 1.99; it must NOT round-half-up to 2.00.
    assert TruncateToCents(Decimal("1.999")) == Decimal("1.99")


def test_truncate_negative_toward_zero():
    # ROUND_DOWN truncates toward zero, so -1.999 becomes -1.99 (not -2.00).
    assert TruncateToCents(Decimal("-1.999")) == Decimal("-1.99")


def test_truncate_trailing_zero_stays():
    # A value already at two decimals is returned unchanged.
    assert TruncateToCents(Decimal("1.990")) == Decimal("1.99")


# ---------------------------------------------------------------------------
# Phase F -- Interest-formula parity with CBACT04C.cbl L464-465
# ---------------------------------------------------------------------------


def test_interest_formula_truncates_cbact04c_parity():
    # Reproduce the legacy COMPUTE verbatim:
    #     COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
    # The COMPUTE has no ROUNDED phrase, so the result truncates toward zero.
    # Every operand is a Decimal, so binary float never enters the expression.
    # (1000.00 * 19.99) / 1200 = 16.65833..., which truncates DOWN to 16.65 --
    # a value deliberately chosen so naive round-half-up (16.66) would differ,
    # proving ROUND_DOWN semantics.
    balance = Decimal("1000.00")
    rate = Decimal("19.99")
    monthlyInterest = TruncateToCents((balance * rate) / INTEREST_MONTHLY_DIVISOR)
    assert monthlyInterest == Decimal("16.65")
