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
    DecodeSignedAmount,
    DecodeZonedDecimal,
    EncodeZonedDecimal,
    Quantize,
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


# ---------------------------------------------------------------------------
# Phase G -- DecodeZonedDecimal defensive input guards
#
# Each test targets ONE guard raise-branch and asserts BOTH the specific
# exception type AND a unique substring of its message. Asserting the message
# is what makes these mutation-killing: if a guard were removed, a later
# backstop might still raise, but with a DIFFERENT message (or a different
# exception type), so the ``match=`` assertion would fail.
# ---------------------------------------------------------------------------


def test_decode_rejects_non_str_input():
    # A non-str payload cannot carry zoned-decimal bytes; reject with TypeError.
    with pytest.raises(TypeError, match="must be str"):
        DecodeZonedDecimal(12345, 2)


def test_decode_rejects_negative_scale():
    # A negative implied-fraction scale is nonsensical; reject with ValueError.
    with pytest.raises(ValueError, match="scale must be non-negative"):
        DecodeZonedDecimal("0000001940{", -1)


def test_decode_rejects_blank_field():
    # An all-blank fixed-width field has no digits to decode; reject.
    with pytest.raises(ValueError, match="blank"):
        DecodeZonedDecimal("      ", 2)


def test_decode_rejects_non_numeric_payload():
    # An ASCII letter in the payload fails the digit check with a distinct
    # "Non-numeric" message (not the later "Invalid zoned-decimal value").
    with pytest.raises(ValueError, match="Non-numeric"):
        DecodeZonedDecimal("1234A6789{", 2)


def test_decode_rejects_unicode_digit_payload():
    # A superscript digit passes str.isdigit() but is rejected by Decimal(),
    # so the InvalidOperation is caught and re-raised as a clean ValueError.
    # decimal.InvalidOperation is NOT a subclass of ValueError, so removing the
    # try/except would let it escape and fail this pytest.raises(ValueError).
    rawValue = "12\u00b2456789{"
    with pytest.raises(ValueError, match="Invalid zoned-decimal value"):
        DecodeZonedDecimal(rawValue, 2)


# ---------------------------------------------------------------------------
# Phase H -- DecodeZonedDecimal scale-zero path + DecodeSignedAmount wrapper
# ---------------------------------------------------------------------------


def test_decode_scale_zero_has_no_implied_point():
    # With scale=0 there is no implied decimal point; the digits decode whole.
    assert DecodeZonedDecimal("00012{", 0) == Decimal("120")


def test_decode_signed_amount_applies_money_scale():
    # The money convenience wrapper decodes with the fixed MONEY_SCALE (2).
    assert DecodeSignedAmount("00000001940{") == Decimal("194.00")


# ---------------------------------------------------------------------------
# Phase I -- EncodeZonedDecimal (round trip, truncation, guards)
# ---------------------------------------------------------------------------


def test_encode_positive_amount_matches_byte_layout():
    # 194.00 in 12 digits, scale 2 -> "00000001940" + '{' (+0 overpunch).
    assert EncodeZonedDecimal(Decimal("194.00"), 12, 2) == "00000001940{"


def test_encode_negative_amount_matches_byte_layout():
    # -919.00 in 11 digits, scale 2 -> "0000009190" + '}' (-0 overpunch).
    assert EncodeZonedDecimal(Decimal("-919.00"), 11, 2) == "0000009190}"


def test_encode_then_decode_round_trips_negative():
    # Encoding then decoding reproduces the original signed magnitude exactly.
    encodedField = EncodeZonedDecimal(Decimal("-919.00"), 11, 2)
    assert DecodeZonedDecimal(encodedField, 2) == Decimal("-919.00")


def test_encode_truncates_toward_zero_before_padding():
    # Encoding applies ROUND_DOWN truncation, so 194.009 encodes like 194.00.
    truncatedField = EncodeZonedDecimal(Decimal("194.009"), 12, 2)
    assert truncatedField == EncodeZonedDecimal(Decimal("194.00"), 12, 2)


def test_encode_rejects_negative_scale():
    # A negative scale is invalid on the encode path as well.
    with pytest.raises(ValueError, match="scale must be non-negative"):
        EncodeZonedDecimal(Decimal("1"), 11, -1)


def test_encode_rejects_non_positive_total_digits():
    # totalDigits must be strictly positive to hold at least the sign digit.
    with pytest.raises(ValueError, match="totalDigits must be positive"):
        EncodeZonedDecimal(Decimal("1"), 0, 2)


def test_encode_rejects_value_overflowing_field_width():
    # A value needing more digit positions than totalDigits is rejected.
    with pytest.raises(ValueError, match="overflows"):
        EncodeZonedDecimal(Decimal("123456789012"), 11, 2)


# ---------------------------------------------------------------------------
# Phase J -- ToDecimal remaining guards (bool rejection, unparseable string)
# ---------------------------------------------------------------------------


def test_to_decimal_rejects_bool():
    # bool is an int subclass but is never a valid monetary value; reject it
    # explicitly so True/False can never be coerced to Decimal(1)/Decimal(0).
    with pytest.raises(TypeError, match="bool is not a valid"):
        ToDecimal(True)


def test_to_decimal_rejects_unparseable_string():
    # A string that is not a decimal number surfaces a specific ValueError,
    # never a leaked decimal.InvalidOperation.
    with pytest.raises(ValueError, match="Cannot parse Decimal"):
        ToDecimal("not-a-number")


# ---------------------------------------------------------------------------
# Phase K -- Quantize InvalidOperation guard
# ---------------------------------------------------------------------------


def test_quantize_rejects_value_exceeding_precision():
    # Quantizing a value so large it would exceed the Decimal context precision
    # raises InvalidOperation, which is translated to a clean ValueError.
    with pytest.raises(ValueError, match="Cannot quantize"):
        Quantize(Decimal("1E30"))

