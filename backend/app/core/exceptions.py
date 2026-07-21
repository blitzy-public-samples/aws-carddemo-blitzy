# Domain exceptions. Transaction-posting validation codes ported verbatim from
# app/cbl/CBTRN02C.cbl (1500-VALIDATE-TRAN, 2800-UPDATE-ACCOUNT-REC).
# Reject record = 430 bytes (CBTRN02C L176-182).
"""Domain exception hierarchy for the CardDemo backend.

This module centralizes every application-level (domain) error raised by the
CardDemo service, repository, and FastAPI dependency layers. It has two
distinct concerns:

1.  A small hierarchy of **general** domain errors -- not-found, validation,
    authentication, authorization, and conflict/optimistic-lock -- that the
    service and repository layers raise and that ``app/main.py`` maps to HTTP
    status codes through registered exception handlers.

2.  The **transaction-posting** exception family that reproduces, verbatim, the
    reject reason codes and descriptions of the legacy batch posting program
    ``app/cbl/CBTRN02C.cbl``. These codes and descriptions form a golden-master
    parity contract: the 430-byte ``DALYREJS`` reject record must reconcile
    field-for-field against the mainframe output, so the numeric codes
    (100, 101, 102, 103, 109) and their UPPERCASE descriptions are never
    reworded, re-cased, or re-punctuated.

Traceability (AAP 0.8.1): each posting member references its originating COBOL
paragraph. Codes 101 and 109 deliberately share the same description text
("ACCOUNT RECORD NOT FOUND") yet remain distinct codes -- 101 is the account
read-not-found during validation (``1500-B-LOOKUP-ACCT``) while 109 is the
account rewrite-not-found during the balance update (``2800-UPDATE-ACCOUNT-REC``).

The module depends only on the Python standard library. It performs no I/O and
imports no database, engine, or settings modules, so importing it has no side
effects.
"""

from __future__ import annotations

from enum import IntEnum

# ---------------------------------------------------------------------------
# Reject-record geometry (CBTRN02C L176-182).
#
# Exposed as constants so the batch posting job
# (batch/jobs/post_transactions.py) and the online transaction service can
# render the fixed-width DALYREJS record without re-deriving field widths.
# This module is the single authoritative source for these widths.
# ---------------------------------------------------------------------------
REJECT_TRAN_DATA_LENGTH = 350            # REJECT-TRAN-DATA               PIC X(350)
VALIDATION_FAIL_REASON_LENGTH = 4        # WS-VALIDATION-FAIL-REASON      PIC 9(04)
VALIDATION_FAIL_REASON_DESC_LENGTH = 76  # WS-VALIDATION-FAIL-REASON-DESC PIC X(76)
VALIDATION_TRAILER_LENGTH = (
    VALIDATION_FAIL_REASON_LENGTH + VALIDATION_FAIL_REASON_DESC_LENGTH
)                                        # VALIDATION-TRAILER             PIC X(80)
REJECT_RECORD_LENGTH = REJECT_TRAN_DATA_LENGTH + VALIDATION_TRAILER_LENGTH  # 430


# ---------------------------------------------------------------------------
# Base domain exception
# ---------------------------------------------------------------------------
class CardDemoError(Exception):
    """Root of the CardDemo domain-exception hierarchy.

    Every application-level error derives from this class so that ``app/main.py``
    can register one family-wide exception handler when convenient, while each
    concrete subclass stays individually catchable (Ochs Rule: catch specific
    exceptions, never a single catch-all).

    Args:
        message: Human-readable description of what went wrong. Stored on the
            instance as ``message`` and forwarded to ``Exception`` so that
            ``str(error)`` yields the same text.
    """

    def __init__(self, message: str) -> None:
        self.message = message
        super().__init__(message)


# ---------------------------------------------------------------------------
# General domain exceptions (mapped to HTTP statuses in app/main.py)
# ---------------------------------------------------------------------------
class NotFoundError(CardDemoError):
    """A requested record does not exist (maps to HTTP 404).

    Raised by repositories and services when an account, card, customer,
    transaction, or user lookup returns no row.
    """


class DomainValidationError(CardDemoError):
    """Input failed a ported field or business validation edit (HTTP 400/422).

    Named ``DomainValidationError`` (not ``ValidationError``) to avoid shadowing
    :class:`pydantic.ValidationError`. Use it for the PROCEDURE DIVISION and BMS
    field edits reproduced in the service and schema layers.
    """


class AuthenticationError(CardDemoError):
    """Sign-on failed or no valid session/token is present (HTTP 401).

    Corresponds to the COSGN00C "User not found" / "Wrong Password" outcomes
    surfaced by ``core/security.py`` and ``services/auth_service.py``.
    """


class AuthorizationError(CardDemoError):
    """Authenticated but not permitted to perform the action (HTTP 403).

    Raised by the ``require_admin`` dependency when a non-admin user
    (COCOM01Y ``CDEMO-USRTYP-ADMIN`` is false) hits an admin-gated route.
    """


class ConflictError(CardDemoError):
    """A concurrent-modification / optimistic-lock conflict (HTTP 409).

    Reproduces the COACTUPC READ-UPDATE -> REWRITE optimistic check (AAP 0.7.4):
    when the row changed between the read and the write, the update is rejected
    instead of silently overwriting a concurrent change.
    """


class OptimisticLockError(ConflictError):
    """Specialization of :class:`ConflictError` for optimistic-lock failures.

    Provided as a named, more specific alternative so callers may catch the
    lost-update case explicitly; it remains a :class:`ConflictError` (and so
    maps to HTTP 409) for family-level handling.
    """


# ---------------------------------------------------------------------------
# Transaction-posting reject catalog (VERBATIM from CBTRN02C).
#
# Single source of truth for the code <-> description pairing shared by
# services/transaction_service.py (COTRN02C online add) and
# batch/jobs/post_transactions.py (CBTRN02C batch posting). No posting code or
# description literal is duplicated elsewhere in the codebase.
# ---------------------------------------------------------------------------
class PostingRejectCode(IntEnum):
    """Numeric reject reason codes moved into ``WS-VALIDATION-FAIL-REASON``.

    Values are the exact ``PIC 9(04)`` reason codes emitted by CBTRN02C's
    ``1500-VALIDATE-TRAN`` and ``2800-UPDATE-ACCOUNT-REC`` paragraphs. As an
    :class:`~enum.IntEnum`, each member compares and formats as its ``int``.

    Members:
        INVALID_CARD_NUMBER: 100 -- card cross-reference not found
            (``1500-A-LOOKUP-XREF``, CBTRN02C L385).
        ACCOUNT_NOT_FOUND: 101 -- account read-not-found during validation
            (``1500-B-LOOKUP-ACCT``, CBTRN02C L397).
        OVERLIMIT_TRANSACTION: 102 -- posting would exceed the credit limit
            (``1500-B-LOOKUP-ACCT``, CBTRN02C L410).
        ACCOUNT_EXPIRED: 103 -- transaction received after account expiration
            (``1500-B-LOOKUP-ACCT``, CBTRN02C L417).
        ACCOUNT_UPDATE_FAILED: 109 -- account rewrite-not-found during the
            balance update (``2800-UPDATE-ACCOUNT-REC``, CBTRN02C L556). Distinct
            from 101 despite the identical description text.
    """

    INVALID_CARD_NUMBER = 100
    ACCOUNT_NOT_FOUND = 101
    OVERLIMIT_TRANSACTION = 102
    ACCOUNT_EXPIRED = 103
    ACCOUNT_UPDATE_FAILED = 109


# Verbatim descriptions moved into WS-VALIDATION-FAIL-REASON-DESC (PIC X(76)).
# Character-for-character UPPERCASE; never reword, re-case, or add punctuation.
# NOTE: codes 101 and 109 intentionally share the SAME text but are DISTINCT.
POSTING_REJECT_DESCRIPTIONS: dict[PostingRejectCode, str] = {
    PostingRejectCode.INVALID_CARD_NUMBER: "INVALID CARD NUMBER FOUND",
    PostingRejectCode.ACCOUNT_NOT_FOUND: "ACCOUNT RECORD NOT FOUND",
    PostingRejectCode.OVERLIMIT_TRANSACTION: "OVERLIMIT TRANSACTION",
    PostingRejectCode.ACCOUNT_EXPIRED: "TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
    PostingRejectCode.ACCOUNT_UPDATE_FAILED: "ACCOUNT RECORD NOT FOUND",
}


# ---------------------------------------------------------------------------
# Transaction-posting exception family
# ---------------------------------------------------------------------------
class TransactionPostingError(CardDemoError):
    """Base for every daily-transaction posting reject (CBTRN02C validation).

    Carries the exact legacy reject ``code`` and ``description`` so callers can
    reconcile them against the 430-byte ``DALYREJS`` reject record. Both the
    online add-transaction path (COTRN02C) and the batch posting job (CBTRN02C)
    raise the concrete subclasses below.

    Args:
        code: The reject reason code -- a :class:`PostingRejectCode` member (or
            the equivalent ``int``). Validated against the catalog; an unknown
            value raises :class:`ValueError`.
        description: Optional explicit reject description. When omitted, the
            verbatim description is looked up from
            :data:`POSTING_REJECT_DESCRIPTIONS`, keeping one source of truth.

    Attributes:
        code: The integer reject reason code (for example ``102``).
        description: The verbatim UPPERCASE reject description.
    """

    def __init__(
        self,
        code: "PostingRejectCode | int",
        description: "str | None" = None,
    ) -> None:
        rejectCode = PostingRejectCode(code)
        resolvedDescription = (
            description
            if description is not None
            else POSTING_REJECT_DESCRIPTIONS[rejectCode]
        )
        self.code = int(rejectCode)
        self.description = resolvedDescription
        super().__init__(f"Posting reject {self.code:04d}: {resolvedDescription}")


class InvalidCardNumberError(TransactionPostingError):
    """Reject 100 -- card cross-reference not found.

    Legacy origin: ``1500-A-LOOKUP-XREF`` -- ``READ XREF-FILE ... INVALID KEY``
    (CBTRN02C L385-387).
    """

    def __init__(self) -> None:
        super().__init__(PostingRejectCode.INVALID_CARD_NUMBER)


class AccountNotFoundError(TransactionPostingError):
    """Reject 101 -- account record not found during validation.

    Legacy origin: ``1500-B-LOOKUP-ACCT`` -- ``READ ACCOUNT-FILE ... INVALID
    KEY`` (CBTRN02C L397-399). Distinct from code 109 despite identical text.
    """

    def __init__(self) -> None:
        super().__init__(PostingRejectCode.ACCOUNT_NOT_FOUND)


class OverlimitTransactionError(TransactionPostingError):
    """Reject 102 -- posting would exceed the account credit limit.

    Legacy origin: ``1500-B-LOOKUP-ACCT`` -- fails when ``ACCT-CREDIT-LIMIT <
    (ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT)``
    (CBTRN02C L410-412).
    """

    def __init__(self) -> None:
        super().__init__(PostingRejectCode.OVERLIMIT_TRANSACTION)


class AccountExpiredError(TransactionPostingError):
    """Reject 103 -- transaction received after account expiration.

    Legacy origin: ``1500-B-LOOKUP-ACCT`` -- fails when ``ACCT-EXPIRAION-DATE <
    DALYTRAN-ORIG-TS(1:10)`` (CBTRN02C L417-419).
    """

    def __init__(self) -> None:
        super().__init__(PostingRejectCode.ACCOUNT_EXPIRED)


class AccountUpdateFailedError(TransactionPostingError):
    """Reject 109 -- account rewrite failed during the balance update.

    Legacy origin: ``2800-UPDATE-ACCOUNT-REC`` -- ``REWRITE ... INVALID KEY`` on
    the posted-balance update (CBTRN02C L556-558). Distinct from code 101 even
    though both read "ACCOUNT RECORD NOT FOUND".
    """

    def __init__(self) -> None:
        super().__init__(PostingRejectCode.ACCOUNT_UPDATE_FAILED)


# ---------------------------------------------------------------------------
# Reject-trailer formatting helper
# ---------------------------------------------------------------------------
def FormatValidationTrailer(code: int, description: str) -> str:
    """Render the 80-byte ``VALIDATION-TRAILER`` of a ``DALYREJS`` reject row.

    Reproduces the fixed-width layout of ``WS-VALIDATION-TRAILER`` (CBTRN02C
    L180-182): a zero-padded 4-digit reason code (``PIC 9(04)``) followed by the
    description left-justified and space-padded to 76 bytes (``PIC X(76)``), for
    a total width of exactly 80 characters. The full 350-byte
    ``REJECT-TRAN-DATA`` portion is owned by the caller (the batch job or the
    transaction service that holds the daily-transaction record) and is not
    built here.

    Args:
        code: The reject reason code. Rendered as the low-order 4 digits, zero
            padded, matching the fixed-width ``PIC 9(04)`` field.
        description: The reject description; truncated to 76 characters when
            longer and right-padded with spaces when shorter.

    Returns:
        An 80-character string: the 4-digit reason field concatenated with the
        76-character description field.
    """
    reasonField = f"{int(code):0{VALIDATION_FAIL_REASON_LENGTH}d}"
    reasonField = reasonField[-VALIDATION_FAIL_REASON_LENGTH:]
    descriptionField = description[:VALIDATION_FAIL_REASON_DESC_LENGTH]
    descriptionField = descriptionField.ljust(VALIDATION_FAIL_REASON_DESC_LENGTH)
    return reasonField + descriptionField


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------
__all__ = [
    # Base
    "CardDemoError",
    # General domain errors
    "NotFoundError",
    "DomainValidationError",
    "AuthenticationError",
    "AuthorizationError",
    "ConflictError",
    "OptimisticLockError",
    # Posting reject catalog
    "PostingRejectCode",
    "POSTING_REJECT_DESCRIPTIONS",
    # Posting exception family
    "TransactionPostingError",
    "InvalidCardNumberError",
    "AccountNotFoundError",
    "OverlimitTransactionError",
    "AccountExpiredError",
    "AccountUpdateFailedError",
    # Reject-record geometry + trailer helper
    "REJECT_TRAN_DATA_LENGTH",
    "VALIDATION_FAIL_REASON_LENGTH",
    "VALIDATION_FAIL_REASON_DESC_LENGTH",
    "VALIDATION_TRAILER_LENGTH",
    "REJECT_RECORD_LENGTH",
    "FormatValidationTrailer",
]
