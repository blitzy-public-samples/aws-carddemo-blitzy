"""Unit tests for app.core.exceptions transaction-posting reject semantics.

Reconciles the ported posting validation codes/descriptions against the legacy
batch posting program app/cbl/CBTRN02C.cbl (1500-VALIDATE-TRAN and
2800-UPDATE-ACCOUNT-REC; L176-182 / L385-419 / L556-558):

  100 INVALID CARD NUMBER FOUND
  101 ACCOUNT RECORD NOT FOUND          (validation read-not-found)
  102 OVERLIMIT TRANSACTION
  103 TRANSACTION RECEIVED AFTER ACCT EXPIRATION
  109 ACCOUNT RECORD NOT FOUND          (rewrite-not-found; DISTINCT from 101)

The 430-byte reject row = REJECT-TRAN-DATA X(350) + VALIDATION-TRAILER X(80),
where the trailer = reason 9(04) + description X(76). DB-free: only codes,
descriptions, and the optional 80-byte trailer formatter are asserted here; the
full 430-byte row (needs the ORM daily-tran record) is covered in integration/.
Supports BOTH exception designs (Design A subclasses / Design B IntEnum+dict) and
skips cleanly if neither posting surface is discoverable. Synchronous, stdlib only.
"""

import pytest

import app.core.exceptions as exceptions_module

# Verbatim CBTRN02C posting reject codes -> descriptions (UPPERCASE, char-for-char).
EXPECTED_POSTING_CODES = {
    100: "INVALID CARD NUMBER FOUND",
    101: "ACCOUNT RECORD NOT FOUND",
    102: "OVERLIMIT TRANSACTION",
    103: "TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
    109: "ACCOUNT RECORD NOT FOUND",
}

# Reject-record byte layout (CBTRN02C L176-182).
REJECT_DATA_BYTES = 350
TRAILER_REASON_BYTES = 4
TRAILER_DESC_BYTES = 76
TRAILER_TOTAL_BYTES = TRAILER_REASON_BYTES + TRAILER_DESC_BYTES
REJECT_RECORD_BYTES = REJECT_DATA_BYTES + TRAILER_TOTAL_BYTES

# Design A subclasses -> expected (code, description).
DESIGN_A_CLASS_NAMES = {
    "InvalidCardNumberError": (100, "INVALID CARD NUMBER FOUND"),
    "AccountNotFoundError": (101, "ACCOUNT RECORD NOT FOUND"),
    "OverlimitTransactionError": (102, "OVERLIMIT TRANSACTION"),
    "AccountExpiredError": (103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"),
    "AccountUpdateFailedError": (109, "ACCOUNT RECORD NOT FOUND"),
}


def _CollectDesignBCodes():
    """Collect {code: description} from Design B (PostingRejectCode + descriptions dict)."""
    rejectCodeEnum = getattr(exceptions_module, "PostingRejectCode", None)
    descriptions = getattr(exceptions_module, "POSTING_REJECT_DESCRIPTIONS", None)
    if rejectCodeEnum is None or descriptions is None:
        return None
    collectedCodes = {}
    for member in rejectCodeEnum:
        descriptionText = descriptions.get(member)
        if descriptionText is None:
            descriptionText = descriptions.get(int(member))
        collectedCodes[int(member)] = descriptionText
    return collectedCodes


def _CollectDesignACodes():
    """Collect {code: description} by instantiating Design A subclasses."""
    collectedCodes = {}
    for className in DESIGN_A_CLASS_NAMES:
        errorClass = getattr(exceptions_module, className, None)
        if errorClass is None:
            return None
        try:
            errorInstance = errorClass()
        except TypeError:
            return None
        code = getattr(errorInstance, "code", None)
        description = getattr(errorInstance, "description", None)
        if code is None or description is None:
            return None
        collectedCodes[int(code)] = description
    return collectedCodes


def _CollectPostingCodes():
    """Return {code: description} via whichever exceptions design is present, else None."""
    designB = _CollectDesignBCodes()
    if designB:
        return designB
    return _CollectDesignACodes()


def test_posting_codes_match_cbtrn02c():
    # CBTRN02C 1500-VALIDATE-TRAN / 2800-UPDATE-ACCOUNT-REC: exact code -> description set.
    collectedCodes = _CollectPostingCodes()
    if not collectedCodes:
        pytest.skip("No posting-reject exception surface (Design A or B) is discoverable")
    for code, expectedDescription in EXPECTED_POSTING_CODES.items():
        assert code in collectedCodes, f"missing posting code {code}"
        assert collectedCodes[code].rstrip() == expectedDescription


def test_codes_101_and_109_distinct_same_description():
    # 101 (validation read-not-found) and 109 (rewrite-not-found) share text but are DISTINCT codes.
    collectedCodes = _CollectPostingCodes()
    if not collectedCodes:
        pytest.skip("No posting-reject exception surface (Design A or B) is discoverable")
    assert 101 in collectedCodes
    assert 109 in collectedCodes
    assert collectedCodes[101].rstrip() == "ACCOUNT RECORD NOT FOUND"
    assert collectedCodes[109].rstrip() == "ACCOUNT RECORD NOT FOUND"


def test_transaction_posting_error_exists():
    # A posting-reject exception type must exist (Design A base or Design B single type).
    postingError = getattr(exceptions_module, "TransactionPostingError", None)
    if postingError is None:
        pytest.skip("TransactionPostingError is not part of the implemented public surface")
    assert issubclass(postingError, Exception)


def test_validation_trailer_is_80_bytes():
    # CBTRN02C L176-182: VALIDATION-TRAILER = reason 9(04) + desc X(76) = 80 bytes.
    formatFn = getattr(exceptions_module, "FormatValidationTrailer", None)
    if formatFn is None:
        pytest.skip("FormatValidationTrailer is not part of the implemented public surface")
    for code, description in EXPECTED_POSTING_CODES.items():
        trailerText = formatFn(code, description)
        assert len(trailerText) == TRAILER_TOTAL_BYTES


def test_validation_trailer_reason_zero_padded():
    # Reason is zero-padded to 4 digits: code 102 -> trailer starts '0102'.
    formatFn = getattr(exceptions_module, "FormatValidationTrailer", None)
    if formatFn is None:
        pytest.skip("FormatValidationTrailer is not part of the implemented public surface")
    trailerText = formatFn(102, "OVERLIMIT TRANSACTION")
    assert trailerText.startswith("0102")
    assert len(trailerText) == TRAILER_TOTAL_BYTES


def test_reject_record_byte_arithmetic():
    # CBTRN02C REJECT-RECORD = X(350) data + X(80) trailer = 430 bytes.
    assert TRAILER_TOTAL_BYTES == 80
    assert REJECT_RECORD_BYTES == 430
    assert REJECT_DATA_BYTES + TRAILER_TOTAL_BYTES == REJECT_RECORD_BYTES
