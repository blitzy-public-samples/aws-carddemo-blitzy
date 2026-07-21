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

from app.utils.validators import (
    ACCT_ID_LENGTH,
    CARD_NUM_LENGTH,
    CUST_ID_LENGTH,
    INVALID_SSN_PART1,
    TRAN_ID_LENGTH,
    USER_ID_LENGTH,
    ValidateNumericId,
    ValidateRequired,
    ValidateSignedNumber,
    ValidateUsPhone,
    ValidateUsSsn,
    ValidationResult,
)

# A valid nine-digit SSN whose area number (part 1 = 123) is outside the
# reserved INVALID-SSN-PART1 set; reused as the "happy path" SSN fixture value.
VALID_SSN_VALUE = "123456789"

# An account identifier at the exact ACCT-ID width (11) that keeps its
# significant leading zeros; proves ValidateNumericId never coerces to int.
ZERO_PADDED_ACCT_ID = "00000000001"


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
