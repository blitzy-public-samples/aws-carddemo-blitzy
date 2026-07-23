"""Reusable field-level validation rules ported from the legacy COBOL edits.

This module is the reusable *field-edit* layer for the modernized CardDemo
backend. It ports the generic input-validation rules that the legacy online
program applied to every screen field into small, composable Python functions
that the Pydantic schemas (``backend/app/schemas``) and the service layer call
to reproduce the mainframe's **identical accept/reject behavior**.

Each validator takes a human-readable ``fieldName`` (used only to build the
returned message) and the ``value`` to check, and returns an immutable
:class:`ValidationResult` carrying ``isValid`` and ``message``. The result
shape is intentionally identical to
:class:`app.utils.date_utils.DateValidationResult`, so a Pydantic field
validator can wrap any of them with one code path::

    from pydantic import field_validator
    from app.utils import validators

    class CustomerCreate(BaseModel):
        ssn: str

        @field_validator("ssn")
        @classmethod
        def _CheckSsn(cls, rawValue: str) -> str:
            result = validators.ValidateUsSsn("SSN", rawValue)
            if not result.isValid:
                raise ValueError(result.message)
            return rawValue

Design constraints honored here:

* **Pure and import-safe.** Only the Python standard library and the two
  sibling ``app.utils`` helpers are imported; nothing runs at import time
  beyond compiling constant, anchored regular expressions. No I/O,
  configuration, network, or wall-clock access occurs at import.
* **Exact decimal, never floating point.** Numeric and monetary checks route
  through :func:`app.utils.decimal_utils.ToDecimal` so values remain
  :class:`decimal.Decimal`; binary floating-point rounding can never
  contaminate a monetary edit. The numeric validators additionally reject
  non-finite Decimals (``NaN``, ``Infinity``, ``-Infinity``): a legacy COBOL
  signed zoned-decimal DISPLAY field (``WS-EDIT-SIGNED-NUMBER-9V2-X PIC X(15)``,
  COACTUPC.cbl:L55-58) can only hold a fixed-format signed number, so a
  non-finite value is not a representable monetary amount and is treated as a
  failed edit rather than being allowed to propagate into later arithmetic.
* **Specific exceptions only.** Numeric parsing catches exactly
  :class:`ValueError`, :class:`TypeError`, and
  :class:`decimal.InvalidOperation`; there is no bare ``except``.
* **These validators are the sanitization layer.** Every check is a strict
  allow-list (digits-only, alphabetic-only, alphanumeric-only, fixed length,
  or an anchored regex), so user-derived input cannot smuggle unexpected
  characters downstream. Input is never passed to ``eval`` or ``exec``.
"""

# Ported from COBOL PROCEDURE DIVISION edits (COACTUPC WS-GENERIC-EDITS: the
# 1215-EDIT-MANDATORY, 1220-EDIT-YESNO, 1225-EDIT-ALPHA-REQD,
# 1230-EDIT-ALPHANUM-REQD, 1245-EDIT-NUM-REQD, 1250-EDIT-SIGNED-9V2,
# 1260-EDIT-US-PHONE-NUM, and 1265-EDIT-US-SSN paragraphs) and the BMS
# symbolic-map copybooks (app/cpy-bms/*.CPY, which fix the screen field names
# and lengths). Date rules are delegated to date_utils (CSUTLDPY.cpy).

from __future__ import annotations

import re
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Final

from app.utils import date_utils, decimal_utils

__all__ = [
    "ValidationResult",
    "ValidateRequired",
    "ValidateNoControlChars",
    "ValidateAlpha",
    "ValidateAlphanumeric",
    "ValidateLength",
    "ValidateNumericId",
    "ValidateYesNo",
    "ValidateSignedNumber",
    "ValidateNumericRange",
    "ValidateNonNegative",
    "ValidateUsPhone",
    "ValidateUsSsn",
    "ValidateDateField",
    "ValidateDateOfBirthField",
]


@dataclass(frozen=True)
class ValidationResult:
    """Immutable outcome of a single field edit.

    Bundling the outcome into one value keeps every validator at a single
    return object and honors the Ochs "<=4 parameters" rule. The attribute
    names deliberately match
    :class:`app.utils.date_utils.DateValidationResult`, so callers can handle
    both result types with one branch.

    Attributes:
        isValid: True when the edited value passed the check.
        message: Empty string on success; otherwise the legacy-style failure
            wording explaining the rejection.
    """

    isValid: bool
    message: str


# ---------------------------------------------------------------------------
# Field lengths (from the BMS symbolic maps app/cpy-bms/*.CPY and the record
# copybooks app/cpy/*.cpy). Numeric identifiers preserve leading zeros and are
# validated as fixed-length digit strings, never converted to int.
# ---------------------------------------------------------------------------
USER_ID_LENGTH: Final = 8      # SEC-USR-ID / COSGN00 user id, X(08).
PASSWORD_LENGTH: Final = 8     # SEC-USR-PWD / COSGN00 password, X(08).
ACCT_ID_LENGTH: Final = 11     # ACCT-ID, 9(11).
CARD_NUM_LENGTH: Final = 16    # CARD-NUM, X(16).
CUST_ID_LENGTH: Final = 9      # CUST-ID, 9(09).
SSN_LENGTH: Final = 9          # CUST-SSN, 9(09), split 3-2-4.
TRAN_ID_LENGTH: Final = 16     # TRAN-ID, X(16).
PHONE_DIGITS: Final = 10       # US phone, 9(3)+9(3)+9(4) = 10 digits.

# Area-number width for the SSN split (COACTUPC WS-EDIT-US-SSN PART1 9(3)).
SSN_PART1_LENGTH: Final = 3

# Invalid SSN area numbers (part 1). COACTUPC 88-level INVALID-SSN-PART1
# VALUES 0, 666, 900 THRU 999 (COACTUPC.cbl:L121-123).
INVALID_SSN_PART1: Final = frozenset({0, 666}) | frozenset(range(900, 1000))

# Accepted Yes/No tokens. COACTUPC 88-level FLG-YES-NO-ISVALID VALUES 'Y', 'N'
# (uppercase only); lenient callers upper-case the value before validating.
YES_NO_VALUES: Final = frozenset({"Y", "N"})

# ---------------------------------------------------------------------------
# Result messages. Wording mirrors the COACTUPC edit paragraphs so the text can
# surface to callers with parity to the legacy program. Each template is filled
# with the caller-supplied field name (and, where relevant, a length).
# ---------------------------------------------------------------------------
MSG_VALID: Final = ""
MSG_REQUIRED: Final = "{field} must be supplied."
MSG_ALPHA: Final = "{field} can have alphabets only."
MSG_ALPHANUMERIC: Final = "{field} can have numbers or alphabets only."
MSG_YES_NO: Final = "{field} must be Y or N."
MSG_LENGTH: Final = "{field} must be exactly {length} characters."
MSG_NUMERIC_ID: Final = "{field} must be exactly {length} digits."
MSG_SIGNED_NUMBER: Final = "{field} is not a valid signed number."
MSG_RANGE: Final = "{field} is outside the allowed range."
MSG_NON_NEGATIVE: Final = "{field} must not be negative."
MSG_PHONE: Final = "{field} must be a 10-digit US phone number."
MSG_SSN: Final = "{field} must be a valid 9-digit SSN."
MSG_SSN_PART1: Final = "{field}: should not be 000, 666, or between 900 and 999"
# QA finding F4 (Online Security Gate): a free-text field must not carry ASCII
# control characters. Names only the field, never the offending value, so the
# message is safe to surface in a 422 body.
MSG_CONTROL_CHARS: Final = "{field} must not contain control characters."

# ---------------------------------------------------------------------------
# Anchored, non-backtracking patterns compiled once at import (deterministic
# and side-effect-free). They use character classes only, so there is no
# catastrophic-backtracking risk. Alpha and alphanumeric permit the space,
# matching the legacy INSPECT ... CONVERTING edits that treated the space as an
# allowed filler character.
# ---------------------------------------------------------------------------
ALPHA_PATTERN: Final = re.compile(r"[A-Za-z ]+")
ALPHANUMERIC_PATTERN: Final = re.compile(r"[A-Za-z0-9 ]+")
DIGITS_PATTERN: Final = re.compile(r"[0-9]+")
# Disallowed control characters (QA finding F4, Online Security Gate): the C0
# control range U+0000-U+001F plus DEL U+007F. These have no legitimate place in
# the single-line identifier/description fields ported from the fixed-width BMS
# screens; a NUL (U+0000) in particular is rejected downstream by the PostgreSQL
# text type (asyncpg) and by bcrypt, surfacing as an unhandled 500 rather than a
# bounded validation error. ``search`` (not ``fullmatch``) detects an offending
# character anywhere in the value.
CONTROL_CHAR_PATTERN: Final = re.compile(r"[\x00-\x1f\x7f]")


# ---------------------------------------------------------------------------
# Private helpers (module-internal; not part of the public API).
# ---------------------------------------------------------------------------


def _Valid() -> ValidationResult:
    """Return the shared "valid" result with an empty message.

    Returns:
        A :class:`ValidationResult` whose ``isValid`` is True.
    """
    return ValidationResult(True, MSG_VALID)


def _Invalid(message: str) -> ValidationResult:
    """Build a failing result carrying an already-formatted message.

    Args:
        message: The human-readable failure text to surface.

    Returns:
        A :class:`ValidationResult` whose ``isValid`` is False.
    """
    return ValidationResult(False, message)


def _IsBlank(value: object) -> bool:
    """Report whether ``value`` is absent or whitespace-only.

    Mirrors the COBOL "not supplied" guard (LOW-VALUES / SPACES / trimmed
    length zero) shared by every ``WS-EDIT-MANDATORY-*`` paragraph.

    Args:
        value: The candidate value (any type; only text can be non-blank).

    Returns:
        True when ``value`` is None or, rendered as text, trims to empty.
    """
    if value is None:
        return True
    return str(value).strip() == ""


def _IsAllDigits(text: str) -> bool:
    """Report whether ``text`` is a non-empty run of ASCII digits 0-9.

    Combining ``str.isascii`` with ``str.isdigit`` rejects Unicode digit
    look-alikes (superscripts, other scripts) that ``str.isdigit`` alone would
    accept, keeping any downstream ``int(...)`` call safe. Mirrors the
    ``_IsNumericText`` helper in :mod:`app.utils.date_utils`.

    Args:
        text: Candidate text, expected already trimmed of surrounding spaces.

    Returns:
        True when ``text`` is non-empty and every character is an ASCII decimal
        digit; False otherwise.
    """
    return bool(text) and text.isascii() and text.isdigit()


def _ToFiniteDecimal(value: object) -> Decimal:
    """Convert ``value`` to a finite :class:`decimal.Decimal` or raise.

    Wraps :func:`app.utils.decimal_utils.ToDecimal` (which already forbids
    ``float`` inputs) and adds the finiteness guard the numeric field edits
    require: a legacy COBOL signed zoned-decimal field cannot represent
    ``NaN``, ``Infinity``, or ``-Infinity``, so a non-finite value is not a
    valid monetary amount. Rejecting it here (before any comparison) also
    prevents an uncaught :class:`decimal.InvalidOperation` from a later
    ``NaN`` comparison in the range/non-negative checks.

    Args:
        value: The candidate value (``str``, ``int``, or ``Decimal``).

    Returns:
        The value as a finite :class:`decimal.Decimal`.

    Raises:
        ValueError: If the parsed Decimal is non-finite (``NaN``/``Infinity``).
            (:func:`decimal_utils.ToDecimal` raises ``ValueError``/``TypeError``
            for unparseable or unsupported inputs.)
    """
    decimalValue = decimal_utils.ToDecimal(value)
    if not decimalValue.is_finite():
        raise ValueError(f"Non-finite decimal is not a valid amount: {value!r}")
    return decimalValue


# ---------------------------------------------------------------------------
# Public API -- core generic validators (COACTUPC WS-GENERIC-EDITS).
# ---------------------------------------------------------------------------


def ValidateRequired(fieldName: str, value: object) -> ValidationResult:
    """Validate that a mandatory field was supplied (1215-EDIT-MANDATORY).

    A field is invalid when it is None, spaces, or low-values (an empty trim);
    any other content passes.

    Args:
        fieldName: Field label used to build the failure message.
        value: The candidate value to check.

    Returns:
        A :class:`ValidationResult`; invalid with :data:`MSG_REQUIRED` when the
        value is blank.
    """
    if _IsBlank(value):
        return _Invalid(MSG_REQUIRED.format(field=fieldName))
    return _Valid()


def ValidateNoControlChars(fieldName: str, value: object) -> ValidationResult:
    """Reject ASCII control characters in a free-text field (QA finding F4).

    Guards against malformed input that would otherwise bypass Pydantic's
    bounded validation and reach a driver that rejects it with an unhandled
    500: PostgreSQL's text type (asyncpg) rejects an embedded NUL (U+0000), and
    bcrypt rejects a NUL in a password. Rejecting the C0 control range
    (U+0000-U+001F) and DEL (U+007F) at the field edge turns that 500 into a
    bounded 422 and satisfies the Ochs rule to sanitize all user-supplied data.

    Only text can carry control characters; a non-``str`` value (for example an
    unset ``Optional`` field that is ``None``, or an ``int`` id) is outside this
    check's scope and passes here -- type and format are enforced by the other
    validators and by the Pydantic field types.

    Args:
        fieldName: Field label used to build the failure message.
        value: The candidate value to check.

    Returns:
        A :class:`ValidationResult`; invalid with :data:`MSG_CONTROL_CHARS` when
        a control character is present, valid otherwise.
    """
    if not isinstance(value, str):
        return _Valid()
    if CONTROL_CHAR_PATTERN.search(value) is not None:
        return _Invalid(MSG_CONTROL_CHARS.format(field=fieldName))
    return _Valid()


def ValidateAlpha(fieldName: str, value: str) -> ValidationResult:
    """Validate an alphabetic-only field (1225-EDIT-ALPHA-REQD).

    Reproduces the legacy required-then-alpha order: a blank value is rejected
    as "must be supplied"; otherwise only letters and spaces are accepted (the
    space is allowed, matching the legacy INSPECT ... CONVERTING edit).

    Args:
        fieldName: Field label used to build the failure message.
        value: The candidate text to check.

    Returns:
        A :class:`ValidationResult`; invalid with :data:`MSG_REQUIRED` when
        blank or :data:`MSG_ALPHA` when a non-alphabetic character is present.
    """
    # A non-str (non-None) value is not a representable alphabetic field: reject
    # it as a failed edit rather than letting the regex raise a TypeError.
    if value is not None and not isinstance(value, str):
        return _Invalid(MSG_ALPHA.format(field=fieldName))
    if _IsBlank(value):
        return _Invalid(MSG_REQUIRED.format(field=fieldName))
    if ALPHA_PATTERN.fullmatch(value) is None:
        return _Invalid(MSG_ALPHA.format(field=fieldName))
    return _Valid()


def ValidateAlphanumeric(fieldName: str, value: str) -> ValidationResult:
    """Validate an alphanumeric-only field (1230-EDIT-ALPHANUM-REQD).

    Reproduces the legacy required-then-alphanumeric order: a blank value is
    rejected as "must be supplied"; otherwise only letters, digits, and spaces
    are accepted.

    Args:
        fieldName: Field label used to build the failure message.
        value: The candidate text to check.

    Returns:
        A :class:`ValidationResult`; invalid with :data:`MSG_REQUIRED` when
        blank or :data:`MSG_ALPHANUMERIC` when a disallowed character appears.
    """
    # A non-str (non-None) value is not a representable alphanumeric field:
    # reject it as a failed edit rather than letting the regex raise TypeError.
    if value is not None and not isinstance(value, str):
        return _Invalid(MSG_ALPHANUMERIC.format(field=fieldName))
    if _IsBlank(value):
        return _Invalid(MSG_REQUIRED.format(field=fieldName))
    if ALPHANUMERIC_PATTERN.fullmatch(value) is None:
        return _Invalid(MSG_ALPHANUMERIC.format(field=fieldName))
    return _Valid()


def ValidateLength(fieldName: str, value: str, expectedLength: int) -> ValidationResult:
    """Validate an exact-length fixed-width field.

    Fixed-width screen and record fields (for example, an 8-character user id)
    must match their copybook length exactly; padding is significant, so the
    raw value is measured without trimming.

    Args:
        fieldName: Field label used to build the failure message.
        value: The candidate text to measure.
        expectedLength: The exact required character count.

    Returns:
        A :class:`ValidationResult`; invalid with :data:`MSG_REQUIRED` when the
        value is None or :data:`MSG_LENGTH` when the length differs.
    """
    if value is None:
        return _Invalid(MSG_REQUIRED.format(field=fieldName))
    # A non-str value has no character length to measure: reject it as a failed
    # length edit rather than letting len() raise a TypeError.
    if not isinstance(value, str):
        return _Invalid(MSG_LENGTH.format(field=fieldName, length=expectedLength))
    if len(value) != expectedLength:
        return _Invalid(MSG_LENGTH.format(field=fieldName, length=expectedLength))
    return _Valid()


def ValidateNumericId(fieldName: str, value: str, expectedLength: int) -> ValidationResult:
    """Validate a fixed-length numeric identifier (digits only, zeros kept).

    Numeric identifiers such as ``acct_id`` (11) and ``cust_id`` (9) are stored
    as zero-padded digit strings and must never be converted to ``int`` (that
    would drop the significant leading zeros). The value must therefore be all
    ASCII digits AND exactly ``expectedLength`` characters long.

    Args:
        fieldName: Field label used to build the failure message.
        value: The candidate identifier string.
        expectedLength: The exact required digit count.

    Returns:
        A :class:`ValidationResult`; invalid with :data:`MSG_REQUIRED` when
        blank or :data:`MSG_NUMERIC_ID` when the value is not exactly
        ``expectedLength`` digits.
    """
    # A non-str (non-None) value is not a representable digit string: reject it
    # as a failed numeric-id edit rather than letting len()/scan raise TypeError.
    if value is not None and not isinstance(value, str):
        return _Invalid(MSG_NUMERIC_ID.format(field=fieldName, length=expectedLength))
    if _IsBlank(value):
        return _Invalid(MSG_REQUIRED.format(field=fieldName))
    if not _IsAllDigits(value) or len(value) != expectedLength:
        return _Invalid(MSG_NUMERIC_ID.format(field=fieldName, length=expectedLength))
    return _Valid()


def ValidateYesNo(fieldName: str, value: str) -> ValidationResult:
    """Validate a Yes/No flag (1220-EDIT-YESNO).

    The legacy 88-level ``FLG-YES-NO-ISVALID`` accepts the uppercase literals
    ``'Y'`` and ``'N'`` only, so this validator is intentionally
    case-sensitive; callers that wish to be lenient should upper-case the value
    before calling. A blank value is rejected as "must be supplied".

    Args:
        fieldName: Field label used to build the failure message.
        value: The candidate flag text.

    Returns:
        A :class:`ValidationResult`; invalid with :data:`MSG_REQUIRED` when
        blank or :data:`MSG_YES_NO` when the value is not exactly ``Y`` or
        ``N``.
    """
    # A non-str (non-None) value can never equal "Y"/"N" and an unhashable type
    # (list/dict) would raise on the membership test: reject it as a failed edit.
    if value is not None and not isinstance(value, str):
        return _Invalid(MSG_YES_NO.format(field=fieldName))
    if _IsBlank(value):
        return _Invalid(MSG_REQUIRED.format(field=fieldName))
    if value not in YES_NO_VALUES:
        return _Invalid(MSG_YES_NO.format(field=fieldName))
    return _Valid()


# ---------------------------------------------------------------------------
# Public API -- numeric / monetary validators (use decimal_utils; never float).
# ---------------------------------------------------------------------------


def ValidateSignedNumber(fieldName: str, value: object) -> ValidationResult:
    """Validate a signed number with two implied decimals (1250-EDIT-SIGNED-9V2).

    Represents the ``WS-EDIT-SIGNED-NUMBER-9V2`` edit used for monetary inputs
    (balances, credit limits, transaction amounts). A blank value is rejected
    as "must be supplied"; otherwise the value must parse as an exact, finite
    :class:`decimal.Decimal` via :func:`_ToFiniteDecimal`, which rejects binary
    floating-point inputs and non-finite values (``NaN``/``Infinity``) outright.

    Args:
        fieldName: Field label used to build the failure message.
        value: The candidate value (``str``, ``int``, or ``Decimal``).

    Returns:
        A :class:`ValidationResult`; invalid with :data:`MSG_REQUIRED` when
        blank or :data:`MSG_SIGNED_NUMBER` when the value is not a finite
        number.
    """
    if _IsBlank(value):
        return _Invalid(MSG_REQUIRED.format(field=fieldName))
    try:
        _ToFiniteDecimal(value)
    except (InvalidOperation, ValueError, TypeError):
        return _Invalid(MSG_SIGNED_NUMBER.format(field=fieldName))
    return _Valid()


def ValidateNumericRange(
    fieldName: str,
    value: object,
    bounds: tuple[Decimal | None, Decimal | None],
) -> ValidationResult:
    """Validate that a numeric value falls within an inclusive range.

    The ``bounds`` are grouped into a single ``(minValue, maxValue)`` tuple to
    respect the Ochs "<=4 parameters" rule; either bound may be None to leave
    that side unbounded. The value is parsed with :func:`_ToFiniteDecimal` and
    compared as an exact, finite :class:`decimal.Decimal` (used for credit-limit
    and amount range checks). A non-finite value (``NaN``/``Infinity``) is
    rejected up front, so it can never reach — and raise from — the range
    comparison.

    Args:
        fieldName: Field label used to build the failure message.
        value: The candidate value (``str``, ``int``, or ``Decimal``).
        bounds: Inclusive ``(minValue, maxValue)``; either side may be None.

    Returns:
        A :class:`ValidationResult`; invalid with :data:`MSG_SIGNED_NUMBER`
        when non-numeric or non-finite, or :data:`MSG_RANGE` when out of range.
    """
    minValue, maxValue = bounds
    try:
        decimalValue = _ToFiniteDecimal(value)
    except (InvalidOperation, ValueError, TypeError):
        return _Invalid(MSG_SIGNED_NUMBER.format(field=fieldName))
    if minValue is not None and decimalValue < minValue:
        return _Invalid(MSG_RANGE.format(field=fieldName))
    if maxValue is not None and decimalValue > maxValue:
        return _Invalid(MSG_RANGE.format(field=fieldName))
    return _Valid()


def ValidateNonNegative(fieldName: str, value: object) -> ValidationResult:
    """Validate that a numeric value is zero or positive.

    A common credit-limit building block: the value is parsed with
    :func:`_ToFiniteDecimal` and compared as an exact, finite
    :class:`decimal.Decimal`. A non-finite value (``NaN``/``Infinity``) is
    rejected before the comparison, so ``NaN`` cannot raise from ``<`` nor can
    ``Infinity`` be accepted as non-negative. The specific business formulas
    (available credit = limit - balance, over-limit) live in the service layer,
    not here.

    Args:
        fieldName: Field label used to build the failure message.
        value: The candidate value (``str``, ``int``, or ``Decimal``).

    Returns:
        A :class:`ValidationResult`; invalid with :data:`MSG_SIGNED_NUMBER`
        when non-numeric or non-finite, or :data:`MSG_NON_NEGATIVE` when
        negative.
    """
    try:
        decimalValue = _ToFiniteDecimal(value)
    except (InvalidOperation, ValueError, TypeError):
        return _Invalid(MSG_SIGNED_NUMBER.format(field=fieldName))
    if decimalValue < Decimal(0):
        return _Invalid(MSG_NON_NEGATIVE.format(field=fieldName))
    return _Valid()


# ---------------------------------------------------------------------------
# Public API -- composite validators (phone, SSN, and date delegation).
# ---------------------------------------------------------------------------


def ValidateUsPhone(fieldName: str, value: str) -> ValidationResult:
    """Validate a US phone number (1260-EDIT-US-PHONE-NUM).

    The copybook stores the number as three numeric parts -- area ``9(3)``,
    prefix ``9(3)``, and line ``9(4)`` -- so a well-formed value is exactly ten
    ASCII digits (the compact ``3-3-4`` form), with each part therefore all
    digits. Surrounding whitespace is ignored. The legacy program additionally
    consulted a North-American area-code lookup and a per-part not-zero rule;
    those refinements depend on an out-of-scope lookup copybook and are layered
    on by the service tier, so this reusable check validates the ten-digit
    numeric form. Optionality (whether a phone is required) is the caller's
    concern.

    Args:
        fieldName: Field label used to build the failure message.
        value: The candidate phone text (expected ten digits).

    Returns:
        A :class:`ValidationResult`; invalid with :data:`MSG_PHONE` unless the
        value is exactly ten ASCII digits.
    """
    # A non-str (non-None) value has no phone text to normalize: reject it as a
    # failed edit rather than letting str.strip() raise an AttributeError.
    if value is not None and not isinstance(value, str):
        return _Invalid(MSG_PHONE.format(field=fieldName))
    normalizedValue = value.strip() if value is not None else ""
    if len(normalizedValue) != PHONE_DIGITS or not _IsAllDigits(normalizedValue):
        return _Invalid(MSG_PHONE.format(field=fieldName))
    return _Valid()


def ValidateUsSsn(fieldName: str, value: str) -> ValidationResult:
    """Validate a US Social Security Number (1265-EDIT-US-SSN).

    The number is the compact nine-digit ``3-2-4`` form (COACTUPC
    ``WS-EDIT-US-SSN``: PART1 ``9(3)``, PART2 ``9(2)``, PART3 ``9(4)``).
    Surrounding whitespace is ignored. Beyond requiring nine ASCII digits, the
    legacy 88-level ``INVALID-SSN-PART1`` rejects an area number (part 1) equal
    to ``000``, ``666``, or between ``900`` and ``999``
    (COACTUPC.cbl:L121-123); parts 2 and 3 must be numeric, which the
    nine-digit guard already guarantees.

    Args:
        fieldName: Field label used to build the failure message.
        value: The candidate SSN text (expected nine digits).

    Returns:
        A :class:`ValidationResult`; invalid with :data:`MSG_SSN` when the value
        is not nine digits, or :data:`MSG_SSN_PART1` when the area number is a
        reserved/invalid value.
    """
    # A non-str (non-None) value has no SSN text to normalize: reject it as a
    # failed edit rather than letting str.strip() raise an AttributeError.
    if value is not None and not isinstance(value, str):
        return _Invalid(MSG_SSN.format(field=fieldName))
    normalizedValue = value.strip() if value is not None else ""
    if len(normalizedValue) != SSN_LENGTH or not _IsAllDigits(normalizedValue):
        return _Invalid(MSG_SSN.format(field=fieldName))
    part1Number = int(normalizedValue[0:SSN_PART1_LENGTH])
    if part1Number in INVALID_SSN_PART1:
        return _Invalid(MSG_SSN_PART1.format(field=fieldName))
    return _Valid()


def ValidateDateField(fieldName: str, value: str) -> ValidationResult:
    """Validate a calendar date by delegating to :mod:`app.utils.date_utils`.

    The full CCYYMMDD edit chain (century 19/20, month 1-12, day 1-31 with the
    month-length and leap-year rules, plus the calendar backstop) is owned by
    :func:`app.utils.date_utils.ValidateDate`; this wrapper adapts that result
    into this module's :class:`ValidationResult` so schema and service callers
    observe a single result type. ``fieldName`` is accepted for a uniform
    signature; the delegated legacy wording is preserved as-is.

    Args:
        fieldName: Field label (kept for signature uniformity across
            validators).
        value: The candidate date text (``YYYY-MM-DD`` or ``CCYYMMDD``).

    Returns:
        A :class:`ValidationResult` mirroring the delegated date result.
    """
    # A non-str (non-None) value has no date text to parse: reject it as a
    # malformed date rather than letting date_utils' str.strip() raise. ``None``
    # is passed through so the delegate's own required-field message is kept.
    if value is not None and not isinstance(value, str):
        return ValidationResult(False, date_utils.MSG_DATE_MALFORMED)
    dateResult = date_utils.ValidateDate(value)
    return ValidationResult(dateResult.isValid, dateResult.message)


def ValidateDateOfBirthField(fieldName: str, value: str) -> ValidationResult:
    """Validate a date of birth by delegating to :mod:`app.utils.date_utils`.

    Adds the "not in the future" rule on top of the calendar edits, exactly as
    :func:`app.utils.date_utils.ValidateDateOfBirth` reproduces the legacy
    ``EDIT-DATE-OF-BIRTH`` paragraph (a birth date equal to today is rejected).

    Args:
        fieldName: Field label (kept for signature uniformity across
            validators).
        value: The candidate birth-date text (``YYYY-MM-DD`` or ``CCYYMMDD``).

    Returns:
        A :class:`ValidationResult` mirroring the delegated date-of-birth
        result.
    """
    # A non-str (non-None) value has no date text to parse: reject it as a
    # malformed date rather than letting date_utils' str.strip() raise. ``None``
    # is passed through so the delegate's own required-field message is kept.
    if value is not None and not isinstance(value, str):
        return ValidationResult(False, date_utils.MSG_DATE_MALFORMED)
    dobResult = date_utils.ValidateDateOfBirth(value)
    return ValidationResult(dobResult.isValid, dobResult.message)
