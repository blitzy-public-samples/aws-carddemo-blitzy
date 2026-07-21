# Unit tests for app.utils.validators
# Traceability: field edits from app/bms/*.bms symbolic maps and
#   app/cbl/COACTUPC.cbl (WS-GENERIC-EDITS field-edit paragraphs). The SSN
#   part-1 rule is the 88-level condition INVALID-SSN-PART1 VALUES 0, 666,
#   900 THRU 999 (COACTUPC.cbl L121-123), enforced by paragraph
#   1265-EDIT-US-SSN with the legacy message
#   ": should not be 000, 666, or between 900 and 999" (COACTUPC.cbl L2457).
#
# These are pure-function tests: NO database, NO fixtures, NO network, NO
# conftest imports. They are synchronous plain ``def test_*`` functions, so the
# project-wide asyncio_mode="auto" never applies (none are coroutines). Ochs
# naming (AAP 0.8.2 / 0.8.3) is honored throughout: snake_case test-function
# names (the pytest discovery contract) and file name, camelCase local
# variables, ALL_UPPERCASE module-level constants, 4-space indentation, and one
# asserted behavior per test. Every ``.isValid`` assertion uses the identity
# operators ``is True`` / ``is False`` so a non-bool return would fail loudly,
# and at least one failure case additionally asserts a non-empty ``.message``.

from decimal import Decimal

from app.utils.validators import (
    ACCT_ID_LENGTH,
    CARD_NUM_LENGTH,
    CUST_ID_LENGTH,
    INVALID_SSN_PART1,
    TRAN_ID_LENGTH,
    USER_ID_LENGTH,
    ValidateAlpha,
    ValidateAlphanumeric,
    ValidateDateField,
    ValidateDateOfBirthField,
    ValidateLength,
    ValidateNonNegative,
    ValidateNumericId,
    ValidateNumericRange,
    ValidateRequired,
    ValidateSignedNumber,
    ValidateUsPhone,
    ValidateUsSsn,
    ValidateYesNo,
    ValidationResult,
)

# A valid nine-digit SSN whose area number (part 1 = 123) is outside the
# reserved INVALID-SSN-PART1 set; reused as the "happy path" SSN fixture value.
VALID_SSN_VALUE = "123456789"

# An account identifier at the exact ACCT-ID width (11) that keeps its
# significant leading zeros; proves ValidateNumericId never coerces to int.
ZERO_PADDED_ACCT_ID = "00000000001"

# Inclusive ``(min, max)`` bounds passed to ValidateNumericRange, expressed as
# exact Decimals (never float). Each mirrors a verified legacy 88-level range:
#   FICO  300 THROUGH 850   -> COACTUPC.cbl:L848-849 (FICO-RANGE-IS-VALID).
#   YEAR 1950 THRU   2099   -> COCRDUPC.cbl:L99       (VALID-YEAR).
#   MONTH   1 through   12  -> COCRDUPC.cbl:L197-198  (card expiry month 1-12).
FICO_SCORE_BOUNDS = (Decimal(300), Decimal(850))
CARD_YEAR_BOUNDS = (Decimal(1950), Decimal(2099))
CARD_MONTH_BOUNDS = (Decimal(1), Decimal(12))

# Unbounded range used to prove that a non-finite value is rejected by the
# finiteness guard itself, independent of any numeric bound.
UNBOUNDED_RANGE = (None, None)


# ---------------------------------------------------------------------------
# ValidationResult contract -- every validator returns this frozen dataclass.
# ---------------------------------------------------------------------------


def test_validation_result_exposes_isvalid_bool_and_message_str():
    # Lock the shared return contract consumed by the Pydantic/service layer:
    # each validator yields a ValidationResult with a bool ``isValid`` and a
    # str ``message`` (empty on success).
    resultValue = ValidateRequired("userId", "ADMIN001")
    assert isinstance(resultValue, ValidationResult)
    assert isinstance(resultValue.isValid, bool)
    assert isinstance(resultValue.message, str)


# ---------------------------------------------------------------------------
# Phase A -- Length constants (lock the copybook-derived contract).
# ---------------------------------------------------------------------------


def test_user_id_length_is_eight():
    # SEC-USR-ID / COSGN00 user id is X(08).
    assert USER_ID_LENGTH == 8


def test_acct_id_length_is_eleven():
    # ACCT-ID is 9(11).
    assert ACCT_ID_LENGTH == 11


def test_card_num_length_is_sixteen():
    # CARD-NUM is X(16).
    assert CARD_NUM_LENGTH == 16


def test_cust_id_length_is_nine():
    # CUST-ID is 9(09).
    assert CUST_ID_LENGTH == 9


def test_tran_id_length_is_sixteen():
    # TRAN-ID is X(16).
    assert TRAN_ID_LENGTH == 16


# ---------------------------------------------------------------------------
# Phase B -- ValidateUsSsn (COACTUPC INVALID-SSN-PART1 rule, L121-123).
# ---------------------------------------------------------------------------


def test_ssn_part1_123_is_valid():
    # Part 1 = 123 is a normal area number, so the SSN passes.
    ssnResult = ValidateUsSsn("ssn", VALID_SSN_VALUE)
    assert ssnResult.isValid is True


def test_ssn_part1_000_is_invalid_with_message():
    # Part 1 = 000 is reserved (INVALID-SSN-PART1 VALUE 0); this failure case
    # also asserts a non-empty message per the module's parity wording.
    ssnResult = ValidateUsSsn("ssn", "000123456")
    assert ssnResult.isValid is False
    assert ssnResult.message != ""


def test_ssn_part1_666_is_invalid():
    # Part 1 = 666 is reserved (INVALID-SSN-PART1 VALUE 666).
    ssnResult = ValidateUsSsn("ssn", "666123456")
    assert ssnResult.isValid is False


def test_ssn_part1_900_is_invalid():
    # Part 1 = 900 falls inside the reserved 900 THRU 999 range.
    ssnResult = ValidateUsSsn("ssn", "900123456")
    assert ssnResult.isValid is False


def test_ssn_part1_899_boundary_is_valid():
    # Part 1 = 899 is the boundary just below the 900 THRU 999 range and must
    # remain valid -- guards against an off-by-one in the reserved range.
    ssnResult = ValidateUsSsn("ssn", "899999999")
    assert ssnResult.isValid is True


def test_invalid_ssn_part1_set_membership():
    # The reserved set is exactly {0, 666} | range(900, 1000): it contains the
    # endpoints 0/666/900/999 but excludes 1, 665, and the 899 boundary.
    assert 0 in INVALID_SSN_PART1
    assert 666 in INVALID_SSN_PART1
    assert 900 in INVALID_SSN_PART1
    assert 999 in INVALID_SSN_PART1
    assert 1 not in INVALID_SSN_PART1
    assert 665 not in INVALID_SSN_PART1
    assert 899 not in INVALID_SSN_PART1


# ---------------------------------------------------------------------------
# Phase C -- ValidateNumericId (fixed-length digits; leading zeros preserved).
# ---------------------------------------------------------------------------


def test_numeric_id_preserves_leading_zeros():
    # A fully zero-padded 11-digit account id is accepted as-is; the validator
    # measures the string and never converts to int (which would drop zeros).
    numericResult = ValidateNumericId("acctId", ZERO_PADDED_ACCT_ID, ACCT_ID_LENGTH)
    assert numericResult.isValid is True


def test_numeric_id_wrong_length_is_invalid():
    # An all-digit value of the wrong width (3 vs the required 11) is rejected.
    numericResult = ValidateNumericId("acctId", "123", ACCT_ID_LENGTH)
    assert numericResult.isValid is False


def test_numeric_id_non_numeric_is_invalid():
    # A correct-width value containing a non-digit ('A') is rejected: the
    # allow-list is ASCII digits only.
    numericResult = ValidateNumericId("acctId", "0000000000A", ACCT_ID_LENGTH)
    assert numericResult.isValid is False


# ---------------------------------------------------------------------------
# Phase D -- ValidateRequired (1215-EDIT-MANDATORY).
# ---------------------------------------------------------------------------


def test_required_present_is_valid():
    # Any non-blank content satisfies the mandatory-field edit.
    requiredResult = ValidateRequired("userId", "ADMIN001")
    assert requiredResult.isValid is True


def test_required_empty_is_invalid_with_message():
    # An empty string is "not supplied"; also assert a non-empty message.
    requiredResult = ValidateRequired("userId", "")
    assert requiredResult.isValid is False
    assert requiredResult.message != ""


def test_required_whitespace_only_is_invalid():
    # Whitespace-only trims to empty and is treated as not supplied
    # (LOW-VALUES / SPACES parity).
    requiredResult = ValidateRequired("userId", "   ")
    assert requiredResult.isValid is False


# ---------------------------------------------------------------------------
# Phase E -- ValidateUsPhone / ValidateSignedNumber (light coverage).
# ---------------------------------------------------------------------------


def test_phone_ten_digits_is_valid():
    # The compact 3-3-4 form is exactly ten ASCII digits.
    phoneResult = ValidateUsPhone("phone", "1234567890")
    assert phoneResult.isValid is True


def test_phone_nine_digits_is_invalid():
    # Nine digits is one short of the required ten and is rejected.
    phoneResult = ValidateUsPhone("phone", "123456789")
    assert phoneResult.isValid is False


def test_signed_number_negative_is_valid():
    # A signed two-decimal monetary literal parses as an exact Decimal.
    signedResult = ValidateSignedNumber("amt", "-919.00")
    assert signedResult.isValid is True


def test_signed_number_non_numeric_is_invalid():
    # Non-numeric text cannot parse to a Decimal and is rejected.
    signedResult = ValidateSignedNumber("amt", "abc")
    assert signedResult.isValid is False


# ---------------------------------------------------------------------------
# Phase F -- Non-finite rejection across the numeric validators.
#
# The legacy signed-number edit (COACTUPC 1250-EDIT-SIGNED-9V2 operating on
# WS-EDIT-SIGNED-NUMBER-9V2-X PIC X(15), COACTUPC.cbl:L55-58) edits a
# fixed-format signed decimal; a COBOL zoned-decimal DISPLAY field can never
# hold NaN or Infinity. The modernized numeric validators must therefore reject
# non-finite Decimals so a non-finite value can never contaminate downstream
# monetary arithmetic (AAP 0.7.1). These cases previously regressed:
# ValidateSignedNumber ACCEPTED "NaN"/"Infinity" as valid, and both
# ValidateNonNegative and ValidateNumericRange raised an uncaught
# decimal.InvalidOperation when comparing a NaN. Each test calls the validator
# directly with no try/except, so any escaped exception would fail it loudly.
# ---------------------------------------------------------------------------


def test_signed_number_nan_is_invalid_with_message():
    # "NaN" parses as a Decimal but is non-finite: it is not a representable
    # signed amount, so it is rejected -- and the failure carries a message.
    signedResult = ValidateSignedNumber("amt", "NaN")
    assert signedResult.isValid is False
    assert signedResult.message != ""


def test_signed_number_infinity_is_invalid():
    # Positive infinity is non-finite and rejected.
    signedResult = ValidateSignedNumber("amt", "Infinity")
    assert signedResult.isValid is False


def test_signed_number_negative_infinity_is_invalid():
    # Negative infinity is non-finite and rejected.
    signedResult = ValidateSignedNumber("amt", "-Infinity")
    assert signedResult.isValid is False


def test_non_negative_nan_is_invalid_without_raising():
    # NaN must be rejected up front so it never reaches the ``< 0`` comparison
    # (which would raise decimal.InvalidOperation). A returned result -- rather
    # than a raised exception -- proves the guard runs first.
    nonNegativeResult = ValidateNonNegative("bal", "NaN")
    assert nonNegativeResult.isValid is False


def test_non_negative_infinity_is_invalid():
    # Infinity is not a valid non-negative amount; it is rejected as non-finite
    # rather than accepted merely because it compares greater than zero.
    nonNegativeResult = ValidateNonNegative("bal", "Infinity")
    assert nonNegativeResult.isValid is False


def test_numeric_range_nan_is_invalid_without_raising():
    # NaN is rejected before the range comparison, so no InvalidOperation
    # escapes even when explicit FICO bounds are supplied.
    rangeResult = ValidateNumericRange("fico", "NaN", FICO_SCORE_BOUNDS)
    assert rangeResult.isValid is False


def test_numeric_range_infinity_unbounded_is_invalid():
    # With NO bounds, only the finiteness guard can reject Infinity -- a
    # discriminating check that the guard runs before the (here absent) bound
    # comparisons. Before the fix this returned valid.
    rangeResult = ValidateNumericRange("amt", "Infinity", UNBOUNDED_RANGE)
    assert rangeResult.isValid is False


# ---------------------------------------------------------------------------
# Phase G -- FICO credit-score range.
#
# COACTUPC 88-level FICO-RANGE-IS-VALID VALUES 300 THROUGH 850
# (COACTUPC.cbl:L848-849) over CUST-FICO-CREDIT-SCORE PIC 9(03). The reusable
# ValidateNumericRange enforces the exact inclusive boundary the customer edit
# depends on; pinning 299/851 as rejects and the 300/850 endpoints as accepts
# means a widened range (e.g. the regressed 0-999) can no longer pass silently.
# ---------------------------------------------------------------------------


def test_fico_below_minimum_is_invalid_with_message():
    # 299 is one below the 300 floor and is rejected with a message.
    ficoResult = ValidateNumericRange("fico", "299", FICO_SCORE_BOUNDS)
    assert ficoResult.isValid is False
    assert ficoResult.message != ""


def test_fico_minimum_boundary_is_valid():
    # 300 is the inclusive lower bound and must be accepted.
    ficoResult = ValidateNumericRange("fico", "300", FICO_SCORE_BOUNDS)
    assert ficoResult.isValid is True


def test_fico_maximum_boundary_is_valid():
    # 850 is the inclusive upper bound and must be accepted.
    ficoResult = ValidateNumericRange("fico", "850", FICO_SCORE_BOUNDS)
    assert ficoResult.isValid is True


def test_fico_above_maximum_is_invalid():
    # 851 is one above the 850 ceiling and is rejected.
    ficoResult = ValidateNumericRange("fico", "851", FICO_SCORE_BOUNDS)
    assert ficoResult.isValid is False


def test_fico_non_finite_is_invalid():
    # A FICO score can never be non-finite; the finiteness guard rejects it.
    ficoResult = ValidateNumericRange("fico", "NaN", FICO_SCORE_BOUNDS)
    assert ficoResult.isValid is False


# ---------------------------------------------------------------------------
# Phase H -- Full customer-edit field rules (COACTUPC WS-GENERIC-EDITS over the
# CVCUS01Y record). The customer maintenance screen edits first/last name
# (alpha, X(25)), state (X(02)), country (X(03)), ZIP (digits), the customer id
# (9(09)) and phone (3-3-4 = 10 digits, COACTUPC.cbl:L82-99). Each reusable
# validator is pinned at its accept/reject boundary.
# ---------------------------------------------------------------------------


def test_customer_first_name_alpha_rejects_digit_with_message():
    # 1225-EDIT-ALPHA-REQD: a name containing a digit is rejected (+ message).
    nameResult = ValidateAlpha("firstName", "John3")
    assert nameResult.isValid is False
    assert nameResult.message != ""


def test_customer_first_name_alpha_accepts_letters_and_space():
    # Letters plus the embedded space are accepted (legacy allows the space).
    nameResult = ValidateAlpha("firstName", "Mary Jane")
    assert nameResult.isValid is True


def test_customer_state_exact_two_characters_is_valid():
    # CUST-ADDR-STATE-CD is a fixed 2-character code.
    stateResult = ValidateLength("state", "TX", 2)
    assert stateResult.isValid is True


def test_customer_state_wrong_length_is_invalid():
    # A single character is not the fixed 2-character state code.
    stateResult = ValidateLength("state", "T", 2)
    assert stateResult.isValid is False


def test_customer_country_exact_three_characters_is_valid():
    # CUST-ADDR-COUNTRY-CD is a fixed 3-character code.
    countryResult = ValidateLength("country", "USA", 3)
    assert countryResult.isValid is True


def test_customer_zip_five_digits_is_valid():
    # A 5-digit ZIP satisfies the digits-only edit used for the ZIP field.
    zipResult = ValidateNumericId("zip", "12345", 5)
    assert zipResult.isValid is True


def test_customer_zip_non_numeric_is_invalid():
    # A ZIP containing a letter is rejected by the digits-only allow-list.
    zipResult = ValidateNumericId("zip", "1234A", 5)
    assert zipResult.isValid is False


def test_customer_id_nine_digits_preserves_leading_zeros():
    # CUST-ID is 9(09); leading zeros are significant and preserved as a string.
    custResult = ValidateNumericId("custId", "000000009", CUST_ID_LENGTH)
    assert custResult.isValid is True


def test_customer_id_wrong_length_is_invalid():
    # A 3-digit value is not the fixed 9-digit customer id.
    custResult = ValidateNumericId("custId", "123", CUST_ID_LENGTH)
    assert custResult.isValid is False


def test_customer_phone_contains_letter_is_invalid():
    # 1260-EDIT-US-PHONE-NUM: a non-digit in the 10-char phone is rejected.
    phoneResult = ValidateUsPhone("phone", "12345678AB")
    assert phoneResult.isValid is False


def test_customer_phone_eleven_digits_is_invalid():
    # Eleven digits exceeds the 3-3-4 = 10-digit form and is rejected.
    phoneResult = ValidateUsPhone("phone", "12345678901")
    assert phoneResult.isValid is False


# ---------------------------------------------------------------------------
# Phase I -- Card-update field rules (COCRDUPC / CVACT02Y). The card update
# screen edits the 16-digit card number (X(16)), the embossed name (alpha), and
# the expiry year (88 VALID-YEAR VALUES 1950 THRU 2099, COCRDUPC.cbl:L99) and
# month (1-12, COCRDUPC.cbl:L197-198). Each boundary is pinned so a widened
# year window or a bad month can no longer pass silently.
# ---------------------------------------------------------------------------


def test_card_number_sixteen_digits_is_valid():
    # CARD-NUM is a fixed 16-digit identifier.
    cardResult = ValidateNumericId("cardNum", "4111111111111111", CARD_NUM_LENGTH)
    assert cardResult.isValid is True


def test_card_number_fifteen_digits_is_invalid_with_message():
    # Fifteen digits is one short of the required 16 and is rejected.
    cardResult = ValidateNumericId("cardNum", "411111111111111", CARD_NUM_LENGTH)
    assert cardResult.isValid is False
    assert cardResult.message != ""


def test_card_embossed_name_alpha_rejects_digit():
    # CARD-EMBOSSED-NAME is alphabetic; an embedded digit is rejected.
    embossedResult = ValidateAlpha("embossedName", "JOHN2DOE")
    assert embossedResult.isValid is False


def test_card_embossed_name_alpha_accepts_letters_and_space():
    # A normal embossed name of letters and spaces is accepted.
    embossedResult = ValidateAlpha("embossedName", "JOHN Q DOE")
    assert embossedResult.isValid is True


def test_card_expiry_year_below_1950_is_invalid():
    # 1949 is one below the 1950 floor of VALID-YEAR and is rejected.
    yearResult = ValidateNumericRange("expiryYear", "1949", CARD_YEAR_BOUNDS)
    assert yearResult.isValid is False


def test_card_expiry_year_1950_boundary_is_valid():
    # 1950 is the inclusive lower bound and must be accepted.
    yearResult = ValidateNumericRange("expiryYear", "1950", CARD_YEAR_BOUNDS)
    assert yearResult.isValid is True


def test_card_expiry_year_2099_boundary_is_valid():
    # 2099 is the inclusive upper bound and must be accepted.
    yearResult = ValidateNumericRange("expiryYear", "2099", CARD_YEAR_BOUNDS)
    assert yearResult.isValid is True


def test_card_expiry_year_above_2099_is_invalid():
    # 2100 is one above the 2099 ceiling and is rejected.
    yearResult = ValidateNumericRange("expiryYear", "2100", CARD_YEAR_BOUNDS)
    assert yearResult.isValid is False


def test_card_expiry_month_zero_is_invalid():
    # Month 0 is below the 1-12 range and is rejected.
    monthResult = ValidateNumericRange("expiryMonth", "0", CARD_MONTH_BOUNDS)
    assert monthResult.isValid is False


def test_card_expiry_month_one_is_valid():
    # Month 1 (January) is the inclusive lower bound.
    monthResult = ValidateNumericRange("expiryMonth", "1", CARD_MONTH_BOUNDS)
    assert monthResult.isValid is True


def test_card_expiry_month_twelve_is_valid():
    # Month 12 (December) is the inclusive upper bound.
    monthResult = ValidateNumericRange("expiryMonth", "12", CARD_MONTH_BOUNDS)
    assert monthResult.isValid is True


def test_card_expiry_month_thirteen_is_invalid():
    # Month 13 is above the 1-12 range and is rejected.
    monthResult = ValidateNumericRange("expiryMonth", "13", CARD_MONTH_BOUNDS)
    assert monthResult.isValid is False


def test_card_expiry_date_valid_calendar_date_is_valid():
    # A well-formed expiry date passes the delegated CCYYMMDD calendar edits.
    expiryResult = ValidateDateField("expiryDate", "2025-06-15")
    assert expiryResult.isValid is True


def test_card_expiry_date_invalid_month_is_invalid():
    # Month 13 fails the delegated month edit (EDIT-MONTH 1-12).
    expiryResult = ValidateDateField("expiryDate", "2025-13-01")
    assert expiryResult.isValid is False


# ---------------------------------------------------------------------------
# Phase J -- Transaction-add field rules (COTRN02C). The add-transaction screen
# requires a non-empty type code (L254), amount (L278) and merchant id (L296);
# validates the account id and card number as numeric (L197, L211); the type
# code as a 2-char numeric (TRAN-TYPE-CD X(02); "Type CD must be Numeric" L323);
# the merchant id as 9-digit numeric (TRAN-MERCHANT-ID 9(09); L430); the amount
# in -99999999.99 form (TRAN-AMT S9(09)V99; L341-345); and the origin date
# (L354). Each reusable validator is pinned at its accept/reject boundary.
# ---------------------------------------------------------------------------


def test_transaction_type_code_empty_is_invalid_with_message():
    # "Type CD can NOT be empty..." (COTRN02C.cbl:L254).
    typeResult = ValidateRequired("typeCd", "")
    assert typeResult.isValid is False
    assert typeResult.message != ""


def test_transaction_amount_empty_is_invalid():
    # "Amount can NOT be empty..." (COTRN02C.cbl:L278).
    amountResult = ValidateRequired("amount", "")
    assert amountResult.isValid is False


def test_transaction_merchant_id_empty_is_invalid():
    # "Merchant ID can NOT be empty..." (COTRN02C.cbl:L296).
    merchantResult = ValidateRequired("merchantId", "")
    assert merchantResult.isValid is False


def test_transaction_account_id_numeric_is_valid():
    # "Account ID must be Numeric..." (L197): an 11-digit id is accepted.
    acctResult = ValidateNumericId("acctId", ZERO_PADDED_ACCT_ID, ACCT_ID_LENGTH)
    assert acctResult.isValid is True


def test_transaction_account_id_non_numeric_is_invalid():
    # A non-digit in the account id is rejected by the numeric edit.
    acctResult = ValidateNumericId("acctId", "1234567890A", ACCT_ID_LENGTH)
    assert acctResult.isValid is False


def test_transaction_card_number_non_numeric_is_invalid():
    # "Card Number must be Numeric..." (L211): a lettered card num is rejected.
    cardResult = ValidateNumericId("cardNum", "411111111111111A", CARD_NUM_LENGTH)
    assert cardResult.isValid is False


def test_transaction_type_code_two_digits_is_valid():
    # TRAN-TYPE-CD is X(02); a 2-digit code satisfies the numeric edit.
    typeResult = ValidateNumericId("typeCd", "01", 2)
    assert typeResult.isValid is True


def test_transaction_type_code_non_numeric_is_invalid():
    # "Type CD must be Numeric..." (L323): "0A" is rejected.
    typeResult = ValidateNumericId("typeCd", "0A", 2)
    assert typeResult.isValid is False


def test_transaction_type_code_exact_length_two_is_valid():
    # The fixed 2-character width is enforced independently of the numeric edit.
    typeResult = ValidateLength("typeCd", "01", 2)
    assert typeResult.isValid is True


def test_transaction_merchant_id_nine_digits_is_valid():
    # TRAN-MERCHANT-ID is 9(09); a 9-digit id is accepted.
    merchantResult = ValidateNumericId("merchantId", "000000009", 9)
    assert merchantResult.isValid is True


def test_transaction_merchant_id_non_numeric_is_invalid():
    # "Merchant ID must be Numeric..." (L430): a lettered id is rejected.
    merchantResult = ValidateNumericId("merchantId", "12345678X", 9)
    assert merchantResult.isValid is False


def test_transaction_amount_signed_two_decimal_is_valid():
    # "Amount should be in format -99999999.99" (L341-345): the maximum-width
    # signed S9(09)V99 literal parses as an exact Decimal.
    amountResult = ValidateSignedNumber("amount", "-99999999.99")
    assert amountResult.isValid is True


def test_transaction_amount_non_finite_is_invalid():
    # A transaction amount can never be non-finite; Infinity is rejected.
    amountResult = ValidateSignedNumber("amount", "Infinity")
    assert amountResult.isValid is False


def test_transaction_origin_date_valid_calendar_date_is_valid():
    # The origin date's CCYYMMDD portion passes the delegated calendar edits.
    originResult = ValidateDateField("originDate", "2024-01-15")
    assert originResult.isValid is True


def test_transaction_origin_date_impossible_day_is_invalid():
    # 2024-02-30 is not a real calendar date and is rejected.
    originResult = ValidateDateField("originDate", "2024-02-30")
    assert originResult.isValid is False


# ---------------------------------------------------------------------------
# Phase Z -- wrong-type robustness (VALIDATORS-1).
#
# The string-oriented validators are reusable building blocks that a service or
# schema layer may hand an unexpected non-str value (e.g. a JSON number, list,
# or object that slipped past an upstream coercion). A raw ``str``/regex/``len``
# operation on such a value would raise (TypeError/AttributeError) and surface
# as an opaque 500 instead of a clean field-level rejection. Each validator must
# therefore treat a non-str (non-None) value as a *failed edit* -- returning a
# ValidationResult(isValid=False) with its own field-specific message -- never
# raising. These tests feed every string validator each representative wrong
# type and assert a clean invalid result; a crash fails the test loudly. The
# object-typed validators (which legitimately accept str/int/Decimal) are
# separately locked to prove they too never raise on an unexpected type.
# ---------------------------------------------------------------------------

# Representative non-str, non-None values a caller might erroneously supply.
# ``True`` is included deliberately: bool is an int subclass (never a str), so
# it must be caught by the same isinstance(value, str) guard. Unhashable types
# (list, dict) also prove the Y/N membership test can never raise.
WRONG_TYPE_VALUES = (123, 3.14, True, [1, 2], {"a": 1}, b"abc")


def test_validate_alpha_rejects_non_str_without_raising():
    # A non-str name is a failed alphabetic edit, not an exception.
    for wrongValue in WRONG_TYPE_VALUES:
        alphaResult = ValidateAlpha("firstName", wrongValue)
        assert alphaResult.isValid is False
        assert alphaResult.message != ""


def test_validate_alphanumeric_rejects_non_str_without_raising():
    # A non-str code is a failed alphanumeric edit, not an exception.
    for wrongValue in WRONG_TYPE_VALUES:
        alphanumericResult = ValidateAlphanumeric("addressLine", wrongValue)
        assert alphanumericResult.isValid is False
        assert alphanumericResult.message != ""


def test_validate_length_rejects_non_str_without_raising():
    # A non-str value has no character length to measure: reject, never let
    # len() raise a TypeError.
    for wrongValue in WRONG_TYPE_VALUES:
        lengthResult = ValidateLength("userId", wrongValue, USER_ID_LENGTH)
        assert lengthResult.isValid is False
        assert lengthResult.message != ""


def test_validate_numeric_id_rejects_non_str_without_raising():
    # A non-str id (e.g. a JSON integer) is a failed numeric-id edit; the
    # significant-leading-zero contract means it must not be coerced to int.
    for wrongValue in WRONG_TYPE_VALUES:
        numericIdResult = ValidateNumericId("acctId", wrongValue, ACCT_ID_LENGTH)
        assert numericIdResult.isValid is False
        assert numericIdResult.message != ""


def test_validate_yes_no_rejects_non_str_without_raising():
    # A non-str flag can never equal "Y"/"N"; an unhashable list/dict would
    # otherwise raise on the membership test.
    for wrongValue in WRONG_TYPE_VALUES:
        yesNoResult = ValidateYesNo("activeStatus", wrongValue)
        assert yesNoResult.isValid is False
        assert yesNoResult.message != ""


def test_validate_us_phone_rejects_non_str_without_raising():
    # A non-str phone has no text to normalize: reject, never let str.strip()
    # raise an AttributeError.
    for wrongValue in WRONG_TYPE_VALUES:
        phoneResult = ValidateUsPhone("phoneNumber", wrongValue)
        assert phoneResult.isValid is False
        assert phoneResult.message != ""


def test_validate_us_ssn_rejects_non_str_without_raising():
    # A non-str SSN has no text to normalize: reject, never let str.strip()
    # raise an AttributeError.
    for wrongValue in WRONG_TYPE_VALUES:
        ssnResult = ValidateUsSsn("ssn", wrongValue)
        assert ssnResult.isValid is False
        assert ssnResult.message != ""


def test_validate_date_field_rejects_non_str_without_raising():
    # A non-str date has no text to parse: reject as malformed, never let the
    # delegate's str.strip() raise.
    for wrongValue in WRONG_TYPE_VALUES:
        dateResult = ValidateDateField("originDate", wrongValue)
        assert dateResult.isValid is False
        assert dateResult.message != ""


def test_validate_date_of_birth_field_rejects_non_str_without_raising():
    # A non-str date of birth has no text to parse: reject as malformed, never
    # let the delegate's str.strip() raise.
    for wrongValue in WRONG_TYPE_VALUES:
        dobResult = ValidateDateOfBirthField("dateOfBirth", wrongValue)
        assert dobResult.isValid is False
        assert dobResult.message != ""


def test_string_validators_preserve_valid_str_after_guard():
    # The wrong-type guard must not disturb the happy path: a well-formed str
    # still validates for the three validators added to this suite's imports.
    assert ValidateAlphanumeric("addressLine", "123 Main St").isValid is True
    assert ValidateYesNo("activeStatus", "Y").isValid is True
    assert ValidateDateOfBirthField("dateOfBirth", "1990-06-30").isValid is True


def test_string_validators_preserve_none_required_message_after_guard():
    # ``None`` bypasses the wrong-type guard so the pre-existing required/
    # malformed handling is preserved: blank-style validators still say
    # "must be supplied", and the date delegate still owns the None message.
    assert ValidateAlpha("firstName", None).message == "firstName must be supplied."
    assert ValidateYesNo("activeStatus", None).message == "activeStatus must be supplied."
    assert ValidateDateField("originDate", None).isValid is False


def test_object_typed_validators_never_raise_on_wrong_type():
    # The object-accepting validators (str/int/Decimal by contract) are left
    # unchanged by VALIDATORS-1, but must likewise degrade to a bool result --
    # never an exception -- for any unexpected type.
    for wrongValue in WRONG_TYPE_VALUES:
        assert isinstance(ValidateRequired("field", wrongValue).isValid, bool)
        assert isinstance(ValidateSignedNumber("field", wrongValue).isValid, bool)
        assert isinstance(ValidateNonNegative("field", wrongValue).isValid, bool)
        assert isinstance(
            ValidateNumericRange("field", wrongValue, UNBOUNDED_RANGE).isValid,
            bool,
        )
