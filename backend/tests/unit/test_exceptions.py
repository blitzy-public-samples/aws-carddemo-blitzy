# Unit tests for app.core.exceptions
# Traceability: app/cbl/CBTRN02C.cbl posting reject codes 100/101/102/103/109
#   (1500-VALIDATE-TRAN / 2800-UPDATE-ACCOUNT-REC). REJECT-RECORD = 350 + 80 =
#   430 bytes; trailer = reason PIC 9(04) + desc PIC X(76) = 80 bytes
#   (CBTRN02C L176-182, L385-419, L556-558).
"""Pure-function unit tests for the CardDemo domain exception hierarchy.

These tests pin the transaction-posting reject catalog that ``app.core.exceptions``
ports verbatim from the legacy batch posting program ``app/cbl/CBTRN02C.cbl``.
The numeric reject codes (100, 101, 102, 103, 109) and their UPPERCASE
descriptions form a golden-master parity contract: the 430-byte ``DALYREJS``
reject record must reconcile field-for-field against the mainframe output, so
the codes and their descriptions are asserted with exact (never substring)
string equality.

The suite is deliberately independent of the module's internal design. The
services agent was permitted to implement the posting family EITHER as a set of
per-code subclasses (Design A) OR as a single ``TransactionPostingError`` driven
by a ``PostingRejectCode`` enum (Design B). Every assertion therefore reaches
the module through the defensive ``BuildPostingError`` factory and ``getattr``
symbol lookups, so the suite passes unchanged under either design.

No database, no fixtures, no network, and no application/config import are used:
``app.core.exceptions`` depends only on the Python standard library, and both
``app`` and ``app.core`` are side-effect-free package markers, so importing the
module under test has no side effects.
"""

import importlib

import pytest

# Import the module under test defensively. Using ``import_module`` (rather than
# ``from app.core.exceptions import ...``) lets every test tolerate either the
# subclass design (Design A) or the enum design (Design B) without a hard import
# failure when a design-specific symbol is absent.
exceptionsModule = importlib.import_module("app.core.exceptions")


# ---------------------------------------------------------------------------
# Verbatim reject catalog (source of truth for the assertions below).
#
# Character-for-character UPPERCASE, copied from CBTRN02C. Codes 101 and 109
# intentionally share the SAME description text yet remain DISTINCT codes
# (AAP 0.7.3): 101 is the account read-not-found during validation
# (1500-B-LOOKUP-ACCT, L397-399); 109 is the account rewrite-not-found during
# the balance update (2800-UPDATE-ACCOUNT-REC, L556-558).
# ---------------------------------------------------------------------------
EXPECTED_POSTING_DESCRIPTIONS = {
    100: "INVALID CARD NUMBER FOUND",
    101: "ACCOUNT RECORD NOT FOUND",
    102: "OVERLIMIT TRANSACTION",
    103: "TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
    109: "ACCOUNT RECORD NOT FOUND",
}

# Design-A subclass names keyed by reject code. Tried first by the factory; the
# factory transparently falls back to the Design-B enum construction path.
DESIGN_A_CLASS_BY_CODE = {
    100: "InvalidCardNumberError",
    101: "AccountNotFoundError",
    102: "OverlimitTransactionError",
    103: "AccountExpiredError",
    109: "AccountUpdateFailedError",
}

# General (non-posting) domain errors that must all derive from CardDemoError.
GENERAL_DOMAIN_ERROR_NAMES = [
    "NotFoundError",
    "DomainValidationError",
    "AuthenticationError",
    "AuthorizationError",
    "ConflictError",
]


def BuildPostingError(rejectCode):
    """Return a posting-error instance for ``rejectCode`` under either design.

    Args:
        rejectCode: One of the five ported reject reason codes (100, 101, 102,
            103, 109).

    Returns:
        A raised-and-catchable posting exception instance exposing ``.code``
        (int) and ``.description`` (verbatim UPPERCASE str).

    The Design-A per-code subclass is preferred when present; otherwise the
    Design-B ``TransactionPostingError`` is constructed from the matching
    ``PostingRejectCode`` enum member.
    """
    className = DESIGN_A_CLASS_BY_CODE[rejectCode]
    if hasattr(exceptionsModule, className):
        return getattr(exceptionsModule, className)()
    # Design B fallback: build TransactionPostingError from the enum member
    # whose integer value equals the requested reject code.
    postingError = getattr(exceptionsModule, "TransactionPostingError")
    rejectEnum = getattr(exceptionsModule, "PostingRejectCode")
    memberByCode = {int(member): member for member in rejectEnum}
    return postingError(memberByCode[rejectCode])


# ===========================================================================
# Phase A -- each posting code carries its exact int code + verbatim description
# ===========================================================================
@pytest.mark.parametrize(
    "rejectCode, expectedDescription",
    list(EXPECTED_POSTING_DESCRIPTIONS.items()),
    ids=[f"code-{code}" for code in EXPECTED_POSTING_DESCRIPTIONS],
)
def test_posting_code_has_exact_code_and_verbatim_description(
    rejectCode, expectedDescription
):
    """Reject code exposes the exact int code and byte-exact description."""
    postingError = BuildPostingError(rejectCode)
    # The code must be the exact integer value (IntEnum members compare/format
    # as their int; the module stores a plain int).
    assert postingError.code == rejectCode
    assert isinstance(postingError.code, int)
    # Exact UPPERCASE equality -- never substring matching (golden-master parity).
    assert isinstance(postingError.description, str)
    assert postingError.description == expectedDescription


# ===========================================================================
# Phase B -- 101 and 109 are DISTINCT codes with IDENTICAL description text
# ===========================================================================
def test_codes_101_and_109_are_distinct_codes():
    """Reject 101 and reject 109 must remain separate reason codes."""
    accountNotFound = BuildPostingError(101)
    accountUpdateFailed = BuildPostingError(109)
    assert accountNotFound.code == 101
    assert accountUpdateFailed.code == 109
    assert accountNotFound.code != accountUpdateFailed.code
    assert 101 != 109


def test_codes_101_and_109_share_identical_description():
    """101 (read-not-found) and 109 (rewrite-not-found) share the same text."""
    accountNotFound = BuildPostingError(101)
    accountUpdateFailed = BuildPostingError(109)
    assert accountNotFound.description == "ACCOUNT RECORD NOT FOUND"
    assert accountUpdateFailed.description == "ACCOUNT RECORD NOT FOUND"
    assert accountNotFound.description == accountUpdateFailed.description


# ===========================================================================
# Phase C -- posting errors are specific exception types (not a bare Exception)
# ===========================================================================
@pytest.mark.parametrize(
    "rejectCode",
    list(EXPECTED_POSTING_DESCRIPTIONS.keys()),
    ids=[f"code-{code}" for code in EXPECTED_POSTING_DESCRIPTIONS],
)
def test_posting_error_is_a_specific_domain_error(rejectCode):
    """Every posting error is a CardDemoError and a TransactionPostingError."""
    cardDemoError = getattr(exceptionsModule, "CardDemoError")
    transactionPostingError = getattr(exceptionsModule, "TransactionPostingError")
    postingError = BuildPostingError(rejectCode)
    assert isinstance(postingError, cardDemoError)
    assert isinstance(postingError, transactionPostingError)


def test_posting_error_is_catchable_as_specific_type():
    """A raised posting error is caught by a specific type, not bare Exception."""
    # Prefer the most specific Design-A subclass when present; otherwise the
    # Design-B base. Both are strictly narrower than the built-in ``Exception``,
    # proving the reject is catchable specifically (Ochs: catch specific types).
    specificType = getattr(exceptionsModule, "OverlimitTransactionError", None)
    if specificType is None:
        specificType = getattr(exceptionsModule, "TransactionPostingError")
    with pytest.raises(specificType):
        raise BuildPostingError(102)


def test_posting_error_is_not_an_unrelated_domain_error():
    """A posting reject is not conflated with an unrelated NotFoundError."""
    notFoundError = getattr(exceptionsModule, "NotFoundError")
    postingError = BuildPostingError(101)
    # 101 reads "ACCOUNT RECORD NOT FOUND" but is a posting reject, NOT the
    # general NotFoundError (HTTP 404) type -- the hierarchy stays specific.
    assert not isinstance(postingError, notFoundError)


# ===========================================================================
# Phase D -- base exception hierarchy sanity
# ===========================================================================
@pytest.mark.parametrize("errorClassName", GENERAL_DOMAIN_ERROR_NAMES)
def test_general_domain_error_subclasses_carddemoerror(errorClassName):
    """Each general domain error derives from the CardDemoError root."""
    cardDemoError = getattr(exceptionsModule, "CardDemoError")
    errorClass = getattr(exceptionsModule, errorClassName)
    assert issubclass(errorClass, cardDemoError)


def test_optimistic_lock_error_subclasses_carddemoerror():
    """OptimisticLockError derives from CardDemoError (via ConflictError or not)."""
    cardDemoError = getattr(exceptionsModule, "CardDemoError")
    optimisticLockError = getattr(exceptionsModule, "OptimisticLockError")
    # Robust to either parent: implemented as ConflictError today, but the only
    # invariant asserted here is family membership under CardDemoError.
    assert issubclass(optimisticLockError, cardDemoError)


# ===========================================================================
# Phase E -- FormatValidationTrailer geometry (only if the helper exists)
# ===========================================================================
def test_format_validation_trailer_geometry():
    """The 80-byte reject trailer = 4-digit zero-padded reason + 76-char desc."""
    if not hasattr(exceptionsModule, "FormatValidationTrailer"):
        pytest.skip("FormatValidationTrailer not implemented")
    formatTrailer = getattr(exceptionsModule, "FormatValidationTrailer")
    trailer = formatTrailer(102, "OVERLIMIT TRANSACTION")
    # VALIDATION-TRAILER is PIC X(80): reason PIC 9(04) + desc PIC X(76).
    assert len(trailer) == 80
    # Reason field is the zero-padded 4-digit reject code.
    assert trailer.startswith("0102")
    # Description is carried verbatim within the fixed-width field.
    assert "OVERLIMIT TRANSACTION" in trailer
