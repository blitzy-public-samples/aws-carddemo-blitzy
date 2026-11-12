"""Unit tests for app.utils.decimal_utils (signed zoned-decimal + Decimal truncation).

Reconciles the ported numeric helpers against the legacy COBOL numeric semantics:
signed zoned-decimal DISPLAY decode (AAP 0.7.1 Finding #1, copybooks CVACT01Y /
CVTRA0*Y, seed files under app/data/ASCII) and interest truncation without ROUNDED
(AAP 0.7.2, program CBACT04C). Golden-master parity rule: currency assertions use
EXACT Decimal equality, never float tolerance. DB-free, synchronous, stdlib only.
"""

from decimal import Decimal

import pytest

import app.utils.decimal_utils as decimal_utils
from app.utils.decimal_utils import DecodeZonedDecimal, TruncateToCents, QUANTUM_CENTS

# Overpunch nibble tables (AAP 0.7.1): positive {=0,A..I=1..9 ; negative }=0,J..R=1..9.
POSITIVE_OVERPUNCH_CHARS = "{ABCDEFGHI"
NEGATIVE_OVERPUNCH_CHARS = "}JKLMNOPQR"


def test_decode_account_current_balance_row1():
    # CVACT01Y ACCT-CURR-BAL S9(10)V99; app/data/ASCII/acctdata.txt row1 (offset 12:24); '{' = +0.
    decodedValue = DecodeZonedDecimal("00000001940{", 2)
    assert decodedValue == Decimal("194.00")


def test_decode_daily_tran_amount_positive():
    # CVTRA06Y DALYTRAN-AMT S9(09)V99; dailytran.txt row1 amount; 'G' = +7 => 504.77.
    assert DecodeZonedDecimal("0000005047G", 2) == Decimal("504.77")


def test_decode_daily_tran_amount_negative_return():
    # dailytran.txt row2 amount; '}' = -0 => NEGATIVE (a return/credit); posting input for CBTRN02C.
    decodedValue = DecodeZonedDecimal("0000009190}", 2)
    assert decodedValue == Decimal("-919.00")
    assert decodedValue < Decimal("0")


def test_decode_daily_tran_amount_row3():
    # dailytran.txt row3 amount; 'H' = +8 => 67.88.
    assert DecodeZonedDecimal("0000000678H", 2) == Decimal("67.88")


def test_decode_disclosure_group_interest_rate():
    # CVTRA02Y DIS-INT-RATE S9(04)V99; discgrp.txt row1 rate (offset 16:22); '{' = +0 => 15.00.
    assert DecodeZonedDecimal("00150{", 2) == Decimal("15.00")


def test_decode_tran_category_balance_zero():
    # CVTRA01Y TRAN-CAT-BAL S9(09)V99; tcatbal.txt row1 balance (offset 17:28); '{' => 0.00.
    assert DecodeZonedDecimal("0000000000{", 2) == Decimal("0.00")


def test_decode_unsigned_trailing_digit():
    # Unsigned trailing plain digit path (no overpunch char) => positive 0.00.
    assert DecodeZonedDecimal("00000000000", 2) == Decimal("0.00")


def test_positive_overpunch_map_coverage():
    # AAP 0.7.1 positive overpunch: {=0, A..I=1..9 carried in the last-digit zone nibble.
    for expectedDigit, overpunchChar in enumerate(POSITIVE_OVERPUNCH_CHARS):
        decodedValue = DecodeZonedDecimal("0" + overpunchChar, 0)
        assert decodedValue == Decimal(expectedDigit)


def test_negative_overpunch_map_coverage():
    # AAP 0.7.1 negative overpunch: }=-0, J..R=-1..-9 carried in the last-digit zone nibble.
    for expectedDigit, overpunchChar in enumerate(NEGATIVE_OVERPUNCH_CHARS):
        decodedValue = DecodeZonedDecimal("0" + overpunchChar, 0)
        assert decodedValue == Decimal(-expectedDigit)
        if expectedDigit > 0:
            assert decodedValue < Decimal("0")


def test_truncate_to_cents_rounds_down_not_half_up():
    # ROUND_DOWN (drop), never ROUND_HALF_UP: 1.999 -> 1.99 (half-up would be 2.00).
    truncatedValue = TruncateToCents(Decimal("1.999"))
    assert truncatedValue == Decimal("1.99")
    assert truncatedValue != Decimal("2.00")


def test_truncate_to_cents_half_boundary():
    # 2.425 -> 2.42 under ROUND_DOWN (half-up would be 2.43).
    assert TruncateToCents(Decimal("2.425")) == Decimal("2.42")


def test_truncate_to_cents_negative_toward_zero():
    # Negative values truncate toward zero: -1.999 -> -1.99.
    assert TruncateToCents(Decimal("-1.999")) == Decimal("-1.99")


def test_quantum_cents_constant():
    # Guaranteed module constant: the 2-decimal quantization step.
    assert QUANTUM_CENTS == Decimal("0.01")


def test_interest_truncation_matches_cbact04c():
    # CBACT04C 1300-COMPUTE-INTEREST: COMPUTE WS-MONTHLY-INT =
    #   (TRAN-CAT-BAL * DIS-INT-RATE) / 1200  -- NO ROUNDED => truncate to 2dp.
    # 194.00 * 15.00 / 1200 = 2.4250 raw; ROUND_DOWN => 2.42 (half-up would be 2.43).
    rawInterest = (Decimal("194.00") * Decimal("15.00")) / Decimal("1200")
    assert TruncateToCents(rawInterest) == Decimal("2.42")
    assert TruncateToCents(rawInterest) != Decimal("2.43")


def test_interest_truncation_repeating_quotient():
    # CBACT04C interest truncation with a repeating quotient:
    # 1000.00 * 13.00 / 1200 = 10.8333... -> ROUND_DOWN => 10.83.
    rawInterest = (Decimal("1000.00") * Decimal("13.00")) / Decimal("1200")
    assert TruncateToCents(rawInterest) == Decimal("10.83")


def test_decode_rejects_invalid_sign_nibble():
    # Sanitize input (Ochs security): an illegal zone nibble must raise ValueError.
    with pytest.raises(ValueError):
        DecodeZonedDecimal("12A4Z", 2)


def test_encode_zoned_decimal_round_trip_negative():
    # Round-trip parity: encode -919.00 (11 digits, scale 2) -> '0000009190}' (dailytran row2).
    encodeFn = getattr(decimal_utils, "EncodeZonedDecimal", None)
    if encodeFn is None:
        pytest.skip("EncodeZonedDecimal is not part of the implemented public surface")
    encodedValue = encodeFn(Decimal("-919.00"), 11, 2)
    assert encodedValue == "0000009190}"
    assert DecodeZonedDecimal(encodedValue, 2) == Decimal("-919.00")


def test_encode_zoned_decimal_positive_overpunch():
    # 194.00 (12 digits, scale 2) -> trailing '{' (positive zero nibble); matches acctdata row1.
    encodeFn = getattr(decimal_utils, "EncodeZonedDecimal", None)
    if encodeFn is None:
        pytest.skip("EncodeZonedDecimal is not part of the implemented public surface")
    encodedValue = encodeFn(Decimal("194.00"), 12, 2)
    assert encodedValue.endswith("{")
    assert DecodeZonedDecimal(encodedValue, 2) == Decimal("194.00")


def test_to_decimal_rejects_float():
    # ToDecimal must never accept float (regulatory: no float in currency; AAP 0.7.1).
    toDecimalFn = getattr(decimal_utils, "ToDecimal", None)
    if toDecimalFn is None:
        pytest.skip("ToDecimal is not part of the implemented public surface")
    with pytest.raises(TypeError):
        toDecimalFn(1.23)


def test_to_decimal_accepts_str_int_decimal():
    # ToDecimal accepts str/int/Decimal and preserves exact value.
    toDecimalFn = getattr(decimal_utils, "ToDecimal", None)
    if toDecimalFn is None:
        pytest.skip("ToDecimal is not part of the implemented public surface")
    assert toDecimalFn("504.77") == Decimal("504.77")
    assert toDecimalFn(7) == Decimal("7")
    assert toDecimalFn(Decimal("67.88")) == Decimal("67.88")


def test_to_decimal_rejects_malformed_string():
    # Malformed numeric text raises a SPECIFIC ValueError (no bare except in impl).
    toDecimalFn = getattr(decimal_utils, "ToDecimal", None)
    if toDecimalFn is None:
        pytest.skip("ToDecimal is not part of the implemented public surface")
    with pytest.raises(ValueError):
        toDecimalFn("12.3x")


def test_decode_signed_amount_wrapper():
    # Optional convenience wrapper == DecodeZonedDecimal(raw, MONEY_SCALE=2); dailytran row1.
    decodeAmountFn = getattr(decimal_utils, "DecodeSignedAmount", None)
    if decodeAmountFn is None:
        pytest.skip("DecodeSignedAmount is not part of the implemented public surface")
    assert decodeAmountFn("0000005047G") == Decimal("504.77")
