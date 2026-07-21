"""Transaction DTOs. Source: app/cpy/CVTRA05Y.cpy (+ CVTRA06Y daily); screens COTRN00/01/02.CPY.

Pydantic v2 request/response schemas for the Transaction entity of the
modernized CardDemo backend. The canonical field set is ported verbatim from
the posted-transaction record ``TRAN-RECORD`` (``CVTRA05Y``, RECLN 350) and its
byte-identical daily-staging variant ``DALYTRAN-RECORD`` (``CVTRA06Y``, same
350-byte layout with a ``DALYTRAN-`` field prefix). The per-class field-length
differences originate from the three online screens ``COTRN00`` (list, CT00),
``COTRN01`` (view, CT01), and ``COTRN02`` (add, CT02).

Fidelity rules preserved here (see tech-spec AAP section 0.7):

* ``tran_amt`` originates from ``PIC S9(09)V99`` -- a signed zoned-decimal
  DISPLAY field mapped to ``NUMERIC(11, 2)`` and exposed as a Python
  :class:`~decimal.Decimal`. Floating point is rejected outright so that binary
  rounding can never contaminate a monetary value (Finding 0.7.1).
* ``card_num`` (``TRAN-CARD-NUM PIC X(16)``) is masked to its last four digits
  in every response schema (:class:`TransactionRead`,
  :class:`TransactionSummary`) per the sensitive-data rule (0.7.8); the raw
  value survives only on the internal :class:`TransactionBase`.
* ``tran_desc`` is 100 characters on the posted record (Base/Read/Summary) but
  the add screen (``COTRN02`` ``TDESCI``) caps operator input at 60 characters,
  so :class:`TransactionCreate` deliberately narrows ``tran_desc`` to 60.

This module imports only from :mod:`app.schemas.common` and :mod:`app.utils`;
it deliberately imports no sibling schema module so that
:mod:`app.schemas.report` (which reuses :class:`TransactionSummary`) can never
create an import cycle. In-code identifiers follow the Ochs Rule (PascalCase
classes/methods, camelCase locals, ALL_UPPERCASE constants); field and module
names remain snake_case (AAP 0.8.3).
"""

# Ported from COBOL copybooks CVTRA05Y (TRAN-RECORD) and CVTRA06Y
# (DALYTRAN-RECORD), plus the BMS symbolic maps COTRN00/COTRN01/COTRN02.CPY.
# Numeric semantics (signed zoned-decimal DISPLAY, never float) come from
# app.utils.decimal_utils; field edits from app.utils.validators; timestamp
# parsing from app.utils.date_utils. No business logic lives here -- these are
# pure data-transfer objects that reproduce the legacy field lengths, numeric
# precision, and accept/reject validation of the online transaction screens.

from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from typing import Optional

from pydantic import Field, field_serializer, field_validator, model_validator

from app.schemas.common import OrmBase, RequestBase
from app.utils import date_utils, decimal_utils, validators

__all__ = [
    "TransactionBase",
    "TransactionRead",
    "TransactionSummary",
    "TransactionCreate",
]

# ---------------------------------------------------------------------------
# Field-width constants (Ochs Rule: ALL_UPPERCASE). Record widths come from
# CVTRA05Y; the shared identifier widths (TRAN_ID_LENGTH, CARD_NUM_LENGTH,
# ACCT_ID_LENGTH) are reused from app.utils.validators so a single definition
# governs every screen and schema.
# ---------------------------------------------------------------------------
TRAN_TYPE_CD_MAX_LENGTH = 2          # TRAN-TYPE-CD PIC X(02).
TRAN_CAT_CD_LENGTH = 4               # TRAN-CAT-CD PIC 9(04) (digits, zero-padded).
TRAN_SOURCE_MAX_LENGTH = 10          # TRAN-SOURCE PIC X(10).
TRAN_DESC_RECORD_MAX_LENGTH = 100    # TRAN-DESC PIC X(100) (posted record).
TRAN_DESC_SCREEN_MAX_LENGTH = 60     # COTRN02 TDESCI PIC X(60) (add screen).
MERCHANT_ID_LENGTH = 9               # TRAN-MERCHANT-ID PIC 9(09) (digits).
MERCHANT_NAME_MAX_LENGTH = 50        # TRAN-MERCHANT-NAME PIC X(50).
MERCHANT_CITY_MAX_LENGTH = 50        # TRAN-MERCHANT-CITY PIC X(50).
MERCHANT_ZIP_MAX_LENGTH = 10         # TRAN-MERCHANT-ZIP PIC X(10).

# Number of trailing card-number digits left visible when masking (0.7.8) and
# the character used to obscure every preceding digit.
CARD_MASK_VISIBLE_DIGITS = 4
CARD_MASK_CHARACTER = "*"

# ---------------------------------------------------------------------------
# Field labels used to build human-readable validation messages. Defined once
# so the wording stays consistent across every schema class.
# ---------------------------------------------------------------------------
LABEL_TRAN_ID = "Transaction ID"
LABEL_CARD_NUM = "Card Number"
LABEL_TRAN_CAT_CD = "Transaction Category Code"
LABEL_MERCHANT_ID = "Merchant ID"
LABEL_ACCT_ID = "Account ID"

# Failure messages raised directly by this module (validators supply the rest).
MSG_TRAN_AMT_NOT_DECIMAL = (
    "Transaction amount must be an exact decimal value (floating point is not "
    "accepted)."
)
MSG_TIMESTAMP_INVALID = "Timestamp must be a valid date or timestamp value."
MSG_ACCOUNT_IDENTIFIER_REQUIRED = (
    "Either acct_id or card_num must be supplied to identify the account."
)


# ---------------------------------------------------------------------------
# Private helpers (module-internal; not part of the public API). They follow
# the codebase convention of _PascalCase private functions and delegate the
# actual edit logic to app.utils so the legacy accept/reject behavior is shared
# rather than re-implemented here.
# ---------------------------------------------------------------------------


def _MaskCardNumber(value: Optional[str]) -> Optional[str]:
    """Mask all but the final :data:`CARD_MASK_VISIBLE_DIGITS` of a card number.

    Reproduces the sensitive-data rule (AAP 0.7.8): a 16-digit card number is
    returned as twelve mask characters followed by its last four digits. Values
    at or below the visible-digit threshold are fully masked so nothing
    sensitive can leak, and ``None`` passes through untouched.

    Args:
        value: The raw card number to obscure, or ``None``.

    Returns:
        The masked card number, or ``None`` when ``value`` is ``None``.
    """
    if value is None:
        return None
    cardText = str(value).strip()
    if len(cardText) <= CARD_MASK_VISIBLE_DIGITS:
        return CARD_MASK_CHARACTER * len(cardText)
    visibleDigits = cardText[-CARD_MASK_VISIBLE_DIGITS:]
    maskedPortion = CARD_MASK_CHARACTER * (len(cardText) - CARD_MASK_VISIBLE_DIGITS)
    return maskedPortion + visibleDigits


def _EnsureValid(result: validators.ValidationResult, value: str) -> str:
    """Return ``value`` when a validation result passed; raise otherwise.

    Centralizes the ``if not result.isValid: raise ValueError(result.message)``
    pattern shared by every field edit, keeping the individual validators to a
    single line.

    Args:
        result: The :class:`~app.utils.validators.ValidationResult` to inspect.
        value: The value to return when the result is valid.

    Returns:
        The unchanged ``value`` when ``result.isValid`` is True.

    Raises:
        ValueError: Carrying ``result.message`` when the edit failed.
    """
    if not result.isValid:
        raise ValueError(result.message)
    return value


def _CheckExactLength(fieldLabel: str, value: Optional[str], length: int) -> Optional[str]:
    """Validate a fixed-width field to an exact character length.

    ``None`` passes through so optional columns can reuse this helper; required
    fields never receive ``None`` because Pydantic rejects a missing required
    value before this validator runs.

    Args:
        fieldLabel: Human-readable field name used in the failure message.
        value: The candidate text (or ``None`` for an absent optional field).
        length: The exact required character count.

    Returns:
        The unchanged ``value``.

    Raises:
        ValueError: When the value is present but not exactly ``length`` chars.
    """
    if value is None:
        return value
    return _EnsureValid(validators.ValidateLength(fieldLabel, value, length), value)


def _CheckNumericId(fieldLabel: str, value: Optional[str], length: int) -> Optional[str]:
    """Validate a fixed-length numeric identifier (digits only, zeros kept).

    Numeric identifier and code fields (``tran_cat_cd``, ``merchant_id``,
    ``card_num``, ``acct_id``) are stored as zero-padded digit strings and must
    never be coerced to ``int`` (that would drop significant leading zeros), so
    the value must be all ASCII digits AND exactly ``length`` characters. An
    absent optional value (``None``) passes through unchanged.

    Args:
        fieldLabel: Human-readable field name used in the failure message.
        value: The candidate identifier (or ``None`` for an absent field).
        length: The exact required digit count.

    Returns:
        The unchanged ``value``.

    Raises:
        ValueError: When the value is present but not exactly ``length`` digits.
    """
    if value is None:
        return value
    return _EnsureValid(validators.ValidateNumericId(fieldLabel, value, length), value)


def _CoerceAmount(value: object) -> Decimal:
    """Coerce a monetary input to :class:`~decimal.Decimal`, rejecting float.

    Routes the raw value through :func:`app.utils.decimal_utils.ToDecimal`,
    which accepts ``str``/``int``/``Decimal`` and rejects ``float`` and ``bool``
    with :class:`TypeError`. This is the guarantee that ``tran_amt`` never
    silently absorbs binary floating-point rounding (AAP 0.7.1). Used as a
    ``mode="before"`` validator so the check runs before Pydantic's own numeric
    coercion.

    Args:
        value: The raw amount input (``str``, ``int``, or ``Decimal``).

    Returns:
        The value as an exact :class:`~decimal.Decimal`.

    Raises:
        ValueError: When the value is a ``float``/``bool`` or is otherwise not a
            parseable decimal number.
    """
    try:
        return decimal_utils.ToDecimal(value)
    except (TypeError, ValueError, InvalidOperation) as conversionError:
        raise ValueError(MSG_TRAN_AMT_NOT_DECIMAL) from conversionError


def _ParseTimestampInput(value: object) -> object:
    """Normalize a timestamp input to a :class:`~datetime.datetime` when legacy.

    Accepts the values the transaction screens and datastore can supply:

    * ``None`` -> ``None`` (optional columns).
    * :class:`~datetime.datetime` -> returned unchanged (the ORM/TIMESTAMPTZ
      path and modern callers).
    * :class:`~datetime.date` -> promoted to midnight ``datetime`` (the 10-char
      ``TORIGDTI`` / ``TPROCDTI`` add-screen date inputs).
    * ``str`` -> parsed with the legacy X(26) timestamp layout first, then the
      legacy X(10) date layout; if neither matches, the raw string is returned
      so Pydantic can apply its native ISO-8601 parsing for modern JSON clients.

    ``datetime`` is checked before ``date`` because ``datetime`` subclasses
    ``date``. Only :class:`ValueError` from the legacy parsers is caught; no
    broad exception handler is used.

    Args:
        value: The raw timestamp input.

    Returns:
        A :class:`~datetime.datetime` for legacy inputs, ``None`` for blanks, or
        the original value for Pydantic to finish validating.
    """
    if value is None:
        return None
    if isinstance(value, datetime):
        return value
    if isinstance(value, date):
        return datetime(value.year, value.month, value.day)
    if isinstance(value, str):
        timestampText = value.strip()
        if not timestampText:
            return None
        try:
            return date_utils.ParseLegacyTimestamp(timestampText)
        except ValueError:
            pass
        try:
            parsedDate = date_utils.ParseLegacyDate(timestampText)
            return datetime(parsedDate.year, parsedDate.month, parsedDate.day)
        except ValueError:
            # Not a legacy layout; defer to Pydantic's native datetime parsing
            # (for example, ISO-8601 payloads from the modern JSON frontend).
            return value
    # Unsupported type: hand back to Pydantic so it raises a precise error.
    return value


# ---------------------------------------------------------------------------
# Schema classes. In-code identifiers use PascalCase (Ochs Rule); field names
# remain snake_case to mirror the canonical copybook layout.
# ---------------------------------------------------------------------------


class TransactionBase(OrmBase):
    """Full posted-transaction record shape (internal/base representation).

    Mirrors the complete ``CVTRA05Y`` ``TRAN-RECORD`` layout with every field at
    its posted-record width (for example, ``tran_desc`` up to 100 characters).
    This is the internal base representation: ``card_num`` is held **raw** here
    (no masking) for service-layer and golden-master use. Response schemas that
    leave the service boundary (:class:`TransactionRead`,
    :class:`TransactionSummary`) mask ``card_num`` instead.
    """

    # TRAN-ID PIC X(16) -> primary key; validated to exactly 16 characters.
    tran_id: str = Field(
        ...,
        max_length=validators.TRAN_ID_LENGTH,
        description="Transaction identifier (TRAN-ID X(16)); primary key.",
    )
    # TRAN-TYPE-CD PIC X(02).
    tran_type_cd: str = Field(
        ...,
        max_length=TRAN_TYPE_CD_MAX_LENGTH,
        description="Transaction type code (TRAN-TYPE-CD X(02)).",
    )
    # TRAN-CAT-CD PIC 9(04) -> kept as a 4-digit string (leading zeros preserved).
    tran_cat_cd: str = Field(
        ...,
        max_length=TRAN_CAT_CD_LENGTH,
        description="Transaction category code (TRAN-CAT-CD 9(04)); 4 digits.",
    )
    # TRAN-SOURCE PIC X(10).
    tran_source: Optional[str] = Field(
        default=None,
        max_length=TRAN_SOURCE_MAX_LENGTH,
        description="Transaction source channel (TRAN-SOURCE X(10)).",
    )
    # TRAN-DESC PIC X(100) -- full posted-record description width.
    tran_desc: Optional[str] = Field(
        default=None,
        max_length=TRAN_DESC_RECORD_MAX_LENGTH,
        description="Transaction description (TRAN-DESC X(100)).",
    )
    # TRAN-AMT PIC S9(09)V99 -> NUMERIC(11,2), exact Decimal, never float.
    tran_amt: Decimal = Field(
        ...,
        description=(
            "Signed transaction amount (TRAN-AMT S9(09)V99) as an exact "
            "NUMERIC(11,2) Decimal; floating point is rejected."
        ),
    )
    # TRAN-MERCHANT-ID PIC 9(09) -> 9-digit string (leading zeros preserved).
    merchant_id: Optional[str] = Field(
        default=None,
        max_length=MERCHANT_ID_LENGTH,
        description="Merchant identifier (TRAN-MERCHANT-ID 9(09)); 9 digits.",
    )
    # TRAN-MERCHANT-NAME PIC X(50).
    merchant_name: Optional[str] = Field(
        default=None,
        max_length=MERCHANT_NAME_MAX_LENGTH,
        description="Merchant name (TRAN-MERCHANT-NAME X(50)).",
    )
    # TRAN-MERCHANT-CITY PIC X(50).
    merchant_city: Optional[str] = Field(
        default=None,
        max_length=MERCHANT_CITY_MAX_LENGTH,
        description="Merchant city (TRAN-MERCHANT-CITY X(50)).",
    )
    # TRAN-MERCHANT-ZIP PIC X(10).
    merchant_zip: Optional[str] = Field(
        default=None,
        max_length=MERCHANT_ZIP_MAX_LENGTH,
        description="Merchant postal code (TRAN-MERCHANT-ZIP X(10)).",
    )
    # TRAN-CARD-NUM PIC X(16) -> validated as 16 digits; RAW (unmasked) here.
    card_num: str = Field(
        ...,
        max_length=validators.CARD_NUM_LENGTH,
        description="Owning card number (TRAN-CARD-NUM X(16)); raw on the base.",
    )
    # TRAN-ORIG-TS PIC X(26) -> timezone-aware datetime.
    orig_ts: Optional[datetime] = Field(
        default=None,
        description="Original transaction timestamp (TRAN-ORIG-TS X(26)).",
    )
    # TRAN-PROC-TS PIC X(26) -> timezone-aware datetime.
    proc_ts: Optional[datetime] = Field(
        default=None,
        description="Processing timestamp (TRAN-PROC-TS X(26)).",
    )

    @field_validator("tran_id")
    @classmethod
    def CheckTranId(cls, value: str) -> str:
        """Validate ``tran_id`` is exactly ``TRAN_ID_LENGTH`` characters."""
        return _CheckExactLength(LABEL_TRAN_ID, value, validators.TRAN_ID_LENGTH)

    @field_validator("card_num")
    @classmethod
    def CheckCardNum(cls, value: str) -> str:
        """Validate ``card_num`` is exactly ``CARD_NUM_LENGTH`` digits (raw)."""
        return _CheckNumericId(LABEL_CARD_NUM, value, validators.CARD_NUM_LENGTH)

    @field_validator("tran_cat_cd")
    @classmethod
    def CheckTranCatCd(cls, value: str) -> str:
        """Validate ``tran_cat_cd`` is exactly ``TRAN_CAT_CD_LENGTH`` digits."""
        return _CheckNumericId(LABEL_TRAN_CAT_CD, value, TRAN_CAT_CD_LENGTH)

    @field_validator("merchant_id")
    @classmethod
    def CheckMerchantId(cls, value: Optional[str]) -> Optional[str]:
        """Validate ``merchant_id`` is exactly ``MERCHANT_ID_LENGTH`` digits."""
        return _CheckNumericId(LABEL_MERCHANT_ID, value, MERCHANT_ID_LENGTH)

    @field_validator("tran_amt", mode="before")
    @classmethod
    def CoerceTranAmount(cls, value: object) -> Decimal:
        """Coerce ``tran_amt`` to an exact ``Decimal`` and reject float input."""
        return _CoerceAmount(value)

    @field_validator("orig_ts", "proc_ts", mode="before")
    @classmethod
    def ParseTimestamps(cls, value: object) -> object:
        """Normalize legacy date/timestamp text (or ``date``) to ``datetime``."""
        return _ParseTimestampInput(value)


class TransactionRead(OrmBase):
    """Response DTO for a single transaction, built from the ORM ``Transaction``.

    Exposes every display field at posted-record width (``tran_desc`` up to 100)
    with ``tran_amt`` as an exact :class:`~decimal.Decimal` and the timestamps as
    timezone-aware :class:`~datetime.datetime` values. The sensitive
    ``card_num`` is **masked to its last four digits** during serialization
    (AAP 0.7.8); the in-memory attribute keeps the raw value so service code can
    still read it, but ``model_dump`` / JSON output only ever emit the mask.

    ``card_num`` intentionally has no strict digit validator here: this schema
    is populated from trusted ORM rows, and skipping re-validation keeps the
    masked serialized form from being rejected on any accidental round trip.
    """

    tran_id: str = Field(
        ...,
        max_length=validators.TRAN_ID_LENGTH,
        description="Transaction identifier (TRAN-ID X(16)); primary key.",
    )
    tran_type_cd: str = Field(
        ...,
        max_length=TRAN_TYPE_CD_MAX_LENGTH,
        description="Transaction type code (TRAN-TYPE-CD X(02)).",
    )
    tran_cat_cd: str = Field(
        ...,
        max_length=TRAN_CAT_CD_LENGTH,
        description="Transaction category code (TRAN-CAT-CD 9(04)); 4 digits.",
    )
    tran_source: Optional[str] = Field(
        default=None,
        max_length=TRAN_SOURCE_MAX_LENGTH,
        description="Transaction source channel (TRAN-SOURCE X(10)).",
    )
    tran_desc: Optional[str] = Field(
        default=None,
        max_length=TRAN_DESC_RECORD_MAX_LENGTH,
        description="Transaction description (TRAN-DESC X(100)).",
    )
    tran_amt: Decimal = Field(
        ...,
        description=(
            "Signed transaction amount (TRAN-AMT S9(09)V99) as an exact "
            "NUMERIC(11,2) Decimal; floating point is rejected."
        ),
    )
    merchant_id: Optional[str] = Field(
        default=None,
        max_length=MERCHANT_ID_LENGTH,
        description="Merchant identifier (TRAN-MERCHANT-ID 9(09)); 9 digits.",
    )
    merchant_name: Optional[str] = Field(
        default=None,
        max_length=MERCHANT_NAME_MAX_LENGTH,
        description="Merchant name (TRAN-MERCHANT-NAME X(50)).",
    )
    merchant_city: Optional[str] = Field(
        default=None,
        max_length=MERCHANT_CITY_MAX_LENGTH,
        description="Merchant city (TRAN-MERCHANT-CITY X(50)).",
    )
    merchant_zip: Optional[str] = Field(
        default=None,
        max_length=MERCHANT_ZIP_MAX_LENGTH,
        description="Merchant postal code (TRAN-MERCHANT-ZIP X(10)).",
    )
    # Masked to its last four digits on serialization (see MaskCardNum below).
    card_num: str = Field(
        ...,
        max_length=validators.CARD_NUM_LENGTH,
        description="Owning card number (TRAN-CARD-NUM X(16)); masked in output.",
    )
    orig_ts: Optional[datetime] = Field(
        default=None,
        description="Original transaction timestamp (TRAN-ORIG-TS X(26)).",
    )
    proc_ts: Optional[datetime] = Field(
        default=None,
        description="Processing timestamp (TRAN-PROC-TS X(26)).",
    )

    @field_validator("tran_id")
    @classmethod
    def CheckTranId(cls, value: str) -> str:
        """Validate ``tran_id`` is exactly ``TRAN_ID_LENGTH`` characters."""
        return _CheckExactLength(LABEL_TRAN_ID, value, validators.TRAN_ID_LENGTH)

    @field_validator("tran_cat_cd")
    @classmethod
    def CheckTranCatCd(cls, value: str) -> str:
        """Validate ``tran_cat_cd`` is exactly ``TRAN_CAT_CD_LENGTH`` digits."""
        return _CheckNumericId(LABEL_TRAN_CAT_CD, value, TRAN_CAT_CD_LENGTH)

    @field_validator("merchant_id")
    @classmethod
    def CheckMerchantId(cls, value: Optional[str]) -> Optional[str]:
        """Validate ``merchant_id`` is exactly ``MERCHANT_ID_LENGTH`` digits."""
        return _CheckNumericId(LABEL_MERCHANT_ID, value, MERCHANT_ID_LENGTH)

    @field_validator("tran_amt", mode="before")
    @classmethod
    def CoerceTranAmount(cls, value: object) -> Decimal:
        """Coerce ``tran_amt`` to an exact ``Decimal`` and reject float input."""
        return _CoerceAmount(value)

    @field_validator("orig_ts", "proc_ts", mode="before")
    @classmethod
    def ParseTimestamps(cls, value: object) -> object:
        """Normalize legacy date/timestamp text (or ``date``) to ``datetime``."""
        return _ParseTimestampInput(value)

    @field_serializer("card_num")
    def MaskCardNum(self, value: str) -> str:
        """Serialize ``card_num`` as a masked value exposing only the last 4."""
        return _MaskCardNumber(value)


class TransactionSummary(OrmBase):
    """Thin transaction projection for the CT00 browse grid (``COTRN00``).

    Carries only the columns the transaction-list screen renders per row, so it
    can be wrapped in ``PaginatedResponse[TransactionSummary]`` for the paged
    browse endpoint (and reused by the report screen). Like
    :class:`TransactionRead`, it **masks** ``card_num`` to the last four digits
    on serialization (AAP 0.7.8) and exposes ``tran_amt`` as an exact
    :class:`~decimal.Decimal`.
    """

    tran_id: str = Field(
        ...,
        max_length=validators.TRAN_ID_LENGTH,
        description="Transaction identifier (TRAN-ID X(16)); primary key.",
    )
    # Masked to its last four digits on serialization (see MaskCardNum below).
    card_num: str = Field(
        ...,
        max_length=validators.CARD_NUM_LENGTH,
        description="Owning card number (TRAN-CARD-NUM X(16)); masked in output.",
    )
    tran_type_cd: str = Field(
        ...,
        max_length=TRAN_TYPE_CD_MAX_LENGTH,
        description="Transaction type code (TRAN-TYPE-CD X(02)).",
    )
    tran_cat_cd: str = Field(
        ...,
        max_length=TRAN_CAT_CD_LENGTH,
        description="Transaction category code (TRAN-CAT-CD 9(04)); 4 digits.",
    )
    tran_amt: Decimal = Field(
        ...,
        description=(
            "Signed transaction amount (TRAN-AMT S9(09)V99) as an exact "
            "NUMERIC(11,2) Decimal; floating point is rejected."
        ),
    )
    orig_ts: Optional[datetime] = Field(
        default=None,
        description="Original transaction timestamp (TRAN-ORIG-TS X(26)).",
    )
    tran_source: Optional[str] = Field(
        default=None,
        max_length=TRAN_SOURCE_MAX_LENGTH,
        description="Transaction source channel (TRAN-SOURCE X(10)).",
    )
    # Short description surfaced in the grid row (TDESC01I on COTRN00 is X(26));
    # the record width (100) is retained here so the projection never truncates.
    tran_desc: Optional[str] = Field(
        default=None,
        max_length=TRAN_DESC_RECORD_MAX_LENGTH,
        description="Transaction description (TRAN-DESC X(100)).",
    )

    @field_validator("tran_id")
    @classmethod
    def CheckTranId(cls, value: str) -> str:
        """Validate ``tran_id`` is exactly ``TRAN_ID_LENGTH`` characters."""
        return _CheckExactLength(LABEL_TRAN_ID, value, validators.TRAN_ID_LENGTH)

    @field_validator("tran_cat_cd")
    @classmethod
    def CheckTranCatCd(cls, value: str) -> str:
        """Validate ``tran_cat_cd`` is exactly ``TRAN_CAT_CD_LENGTH`` digits."""
        return _CheckNumericId(LABEL_TRAN_CAT_CD, value, TRAN_CAT_CD_LENGTH)

    @field_validator("tran_amt", mode="before")
    @classmethod
    def CoerceTranAmount(cls, value: object) -> Decimal:
        """Coerce ``tran_amt`` to an exact ``Decimal`` and reject float input."""
        return _CoerceAmount(value)

    @field_validator("orig_ts", mode="before")
    @classmethod
    def ParseOrigTs(cls, value: object) -> object:
        """Normalize legacy date/timestamp text (or ``date``) to ``datetime``."""
        return _ParseTimestampInput(value)

    @field_serializer("card_num")
    def MaskCardNum(self, value: str) -> str:
        """Serialize ``card_num`` as a masked value exposing only the last 4."""
        return _MaskCardNumber(value)


class TransactionCreate(RequestBase):
    """Add-transaction input DTO for the CT02 screen (``COTRN02``).

    Reproduces the operator-entered fields of the add-transaction screen. The
    account is identified by ``acct_id`` and/or ``card_num`` (at least one is
    required; the service resolves the owning card), and ``tran_id`` is **not**
    accepted here because the posting logic assigns the next sequential
    identifier.

    ``tran_desc`` is deliberately capped at 60 characters (``COTRN02`` ``TDESCI``
    is ``X(60)``) even though the posted record and the read schemas allow 100:
    the add screen truncates the operator's description to 60. ``merchant_name``
    and ``merchant_city`` accept up to their full record widths (50) even though
    the screen inputs are narrower (30 and 25), so no legitimate value entered
    elsewhere is rejected. ``tran_amt`` is an exact :class:`~decimal.Decimal`
    (floating point rejected). Unknown fields are rejected (``RequestBase``
    forbids extras) as an input-sanitization measure.
    """

    # ACTIDINI X(11) -> account identifier (optional; digits, zeros preserved).
    acct_id: Optional[str] = Field(
        default=None,
        max_length=validators.ACCT_ID_LENGTH,
        description="Account identifier (ACTIDINI 9(11)); 11 digits when given.",
    )
    # CARDNINI X(16) -> card number (optional; digits, zeros preserved).
    card_num: Optional[str] = Field(
        default=None,
        max_length=validators.CARD_NUM_LENGTH,
        description="Card number (CARDNINI X(16)); 16 digits when given.",
    )
    # TTYPCDI X(02) -> transaction type code (required to post a transaction).
    tran_type_cd: str = Field(
        ...,
        max_length=TRAN_TYPE_CD_MAX_LENGTH,
        description="Transaction type code (TTYPCDI X(02)).",
    )
    # TCATCDI X(04) -> transaction category code (required; 4 digits).
    tran_cat_cd: str = Field(
        ...,
        max_length=TRAN_CAT_CD_LENGTH,
        description="Transaction category code (TCATCDI 9(04)); 4 digits.",
    )
    # TRNSRCI X(10) -> transaction source channel (optional).
    tran_source: Optional[str] = Field(
        default=None,
        max_length=TRAN_SOURCE_MAX_LENGTH,
        description="Transaction source channel (TRNSRCI X(10)).",
    )
    # TDESCI X(60) -> add-screen description width (narrower than the record).
    tran_desc: Optional[str] = Field(
        default=None,
        max_length=TRAN_DESC_SCREEN_MAX_LENGTH,
        description=(
            "Transaction description (TDESCI X(60)); capped at 60 on the add "
            "screen even though the posted record allows 100."
        ),
    )
    # TRNAMTI X(12) -> signed amount; exact NUMERIC(11,2) Decimal, never float.
    tran_amt: Decimal = Field(
        ...,
        description=(
            "Signed transaction amount (TRNAMTI, TRAN-AMT S9(09)V99) as an exact "
            "NUMERIC(11,2) Decimal; floating point is rejected."
        ),
    )
    # MIDI X(09) -> merchant identifier (optional; 9 digits).
    merchant_id: Optional[str] = Field(
        default=None,
        max_length=MERCHANT_ID_LENGTH,
        description="Merchant identifier (MIDI 9(09)); 9 digits when given.",
    )
    # MNAMEI X(30) on screen; accept up to the record width (50).
    merchant_name: Optional[str] = Field(
        default=None,
        max_length=MERCHANT_NAME_MAX_LENGTH,
        description="Merchant name (MNAMEI; accepted up to record width 50).",
    )
    # MCITYI X(25) on screen; accept up to the record width (50).
    merchant_city: Optional[str] = Field(
        default=None,
        max_length=MERCHANT_CITY_MAX_LENGTH,
        description="Merchant city (MCITYI; accepted up to record width 50).",
    )
    # MZIPI X(10) -> merchant postal code (optional).
    merchant_zip: Optional[str] = Field(
        default=None,
        max_length=MERCHANT_ZIP_MAX_LENGTH,
        description="Merchant postal code (MZIPI X(10)).",
    )
    # TORIGDTI X(10) date -> normalized to a datetime (accepts date/datetime).
    orig_ts: Optional[datetime] = Field(
        default=None,
        description="Original transaction date/timestamp (TORIGDTI X(10) date).",
    )
    # TPROCDTI X(10) date -> normalized to a datetime (accepts date/datetime).
    proc_ts: Optional[datetime] = Field(
        default=None,
        description="Processing date/timestamp (TPROCDTI X(10) date).",
    )

    @field_validator("acct_id")
    @classmethod
    def CheckAcctId(cls, value: Optional[str]) -> Optional[str]:
        """Validate ``acct_id`` is exactly ``ACCT_ID_LENGTH`` digits when given."""
        return _CheckNumericId(LABEL_ACCT_ID, value, validators.ACCT_ID_LENGTH)

    @field_validator("card_num")
    @classmethod
    def CheckCardNum(cls, value: Optional[str]) -> Optional[str]:
        """Validate ``card_num`` is exactly ``CARD_NUM_LENGTH`` digits when given."""
        return _CheckNumericId(LABEL_CARD_NUM, value, validators.CARD_NUM_LENGTH)

    @field_validator("tran_cat_cd")
    @classmethod
    def CheckTranCatCd(cls, value: str) -> str:
        """Validate ``tran_cat_cd`` is exactly ``TRAN_CAT_CD_LENGTH`` digits."""
        return _CheckNumericId(LABEL_TRAN_CAT_CD, value, TRAN_CAT_CD_LENGTH)

    @field_validator("merchant_id")
    @classmethod
    def CheckMerchantId(cls, value: Optional[str]) -> Optional[str]:
        """Validate ``merchant_id`` is exactly ``MERCHANT_ID_LENGTH`` digits."""
        return _CheckNumericId(LABEL_MERCHANT_ID, value, MERCHANT_ID_LENGTH)

    @field_validator("tran_amt", mode="before")
    @classmethod
    def CoerceTranAmount(cls, value: object) -> Decimal:
        """Coerce ``tran_amt`` to an exact ``Decimal`` and reject float input."""
        return _CoerceAmount(value)

    @field_validator("orig_ts", "proc_ts", mode="before")
    @classmethod
    def ParseTimestamps(cls, value: object) -> object:
        """Normalize legacy date/timestamp text (or ``date``) to ``datetime``."""
        return _ParseTimestampInput(value)

    @model_validator(mode="after")
    def CheckAccountIdentifier(self) -> "TransactionCreate":
        """Require at least one of ``acct_id`` / ``card_num`` to be supplied.

        The COTRN02 add screen accepts an account id and/or a card number to
        locate the account; a request that supplies neither cannot identify a
        target and is rejected.
        """
        if not self.acct_id and not self.card_num:
            raise ValueError(MSG_ACCOUNT_IDENTIFIER_REQUIRED)
        return self
