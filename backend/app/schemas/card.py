"""Card DTOs. Source: app/cpy/CVACT02Y.cpy; screens app/cpy-bms/COCRDLI.CPY, COCRDSL.CPY, COCRDUP.CPY.

Pydantic v2 data-transfer objects (DTOs) for the Card entity. This module is a
faithful, behavior-preserving port of the legacy COBOL copybook
``app/cpy/CVACT02Y.cpy`` (``CARD-RECORD``, fixed record length 150) and the
three online screens that operate on it (Minimal Change Clause, AAP 0.8.1):

    * ``COCRDLIC`` (transaction ``CCLI``) -- browse/list, at most seven rows per
      page (F-004); mapped to :class:`CardSummary` page items.
    * ``COCRDSLC`` (transaction ``CCDL``) -- read-only detail view; mapped to
      :class:`CardRead`.
    * ``COCRDUPC`` (transaction ``CCUP``) -- update the editable card fields;
      mapped to :class:`CardUpdate`.

Traceability -- legacy copybook ``CVACT02Y`` field mapping (REFERENCE only)::

    01 CARD-RECORD                       (RECLN 150)
       05 CARD-NUM            PIC X(16)  -> card_num       (str, PK; MASKED)
       05 CARD-ACCT-ID        PIC 9(11)  -> acct_id        (str, FK; zeros kept)
       05 CARD-CVV-CD         PIC 9(03)  -> (NEVER exposed; see below)
       05 CARD-EMBOSSED-NAME  PIC X(50)  -> embossed_name  (str, <= 50)
       05 CARD-EXPIRAION-DATE PIC X(10)  -> expiration_date (date; spelling fixed)
       05 CARD-ACTIVE-STATUS  PIC X(01)  -> active_status  (str, 'Y'/'N')
       05 FILLER              PIC X(59)  -> DROPPED (record-length padding only)

Sensitive-data rules (AAP 0.7.8 -- NON-NEGOTIABLE):

    * ``CARD-CVV-CD`` (the card verification value) is NEVER represented on any
      schema in this module. There is deliberately no ``cvv_cd`` field on any
      read, summary, base, or update DTO -- not even on input, because none of
      the three card screens (``COCRDLI``/``COCRDSL``/``COCRDUP``) exposes it.
      The update DTO additionally inherits ``extra="forbid"`` from
      :class:`~app.schemas.common.RequestBase`, so a client cannot smuggle a
      ``cvv_cd`` value in through the request body.
    * ``card_num`` (the 16-digit PAN) is MASKED in every serialized output to
      its last four digits (for example ``************5740``). Masking is
      implemented with a Pydantic ``field_serializer``; because a serializer
      only affects ``model_dump()`` / ``model_dump_json()`` and never attribute
      access, internal callers can still read the full value via the attribute
      while any API response carries only the masked form.

Numeric note (AAP 0.7.1): this record carries no monetary or rate fields, so no
``Decimal`` handling is needed here and floating point is never introduced. The
numeric identifiers ``card_num`` and ``acct_id`` are represented as fixed-width
``str`` (never ``int``) so their significant leading zeros are preserved.
"""

# Ported from COBOL copybook CVACT02Y (CARD-RECORD) and the BMS symbolic maps
# COCRDLI/COCRDSL/COCRDUP. Per the naming-convention resolution (AAP 0.8.3),
# data field names stay snake_case (the DTO / JSON / ORM-column contract) while
# classes and methods are PascalCase and module-level constants are
# ALL_UPPERCASE (Ochs Rule). Field-level edits are delegated to the shared
# app.utils validators so accept/reject behavior matches the legacy screens.

from datetime import date
from typing import Optional

from pydantic import Field, field_serializer, field_validator

from app.schemas.common import OrmBase, RequestBase
from app.utils import date_utils, validators

__all__ = [
    "CardBase",
    "CardRead",
    "CardSummary",
    "CardUpdate",
]

# ---------------------------------------------------------------------------
# Fixed field widths and labels (Ochs Rule 0.8.2: constants are ALL_UPPERCASE
# with underscores), ported directly from the CVACT02Y picture clauses.
# Numeric identifiers preserve leading zeros and are validated as fixed-length
# digit strings, never converted to int.
# ---------------------------------------------------------------------------
CARD_NUM_LENGTH = 16          # CARD-NUM PIC X(16); a 16-digit PAN.
ACCT_ID_LENGTH = 11           # CARD-ACCT-ID PIC 9(11); zero-padded FK.
EMBOSSED_NAME_MAX_LENGTH = 50  # CARD-EMBOSSED-NAME PIC X(50).
ACTIVE_STATUS_LENGTH = 1      # CARD-ACTIVE-STATUS PIC X(01); 'Y' or 'N'.

# Human-readable field labels fed to the shared validators so the returned
# messages read naturally (for example "Card number must be exactly 16 digits").
CARD_NUM_FIELD_LABEL = "Card number"
ACCT_ID_FIELD_LABEL = "Account id"
ACTIVE_STATUS_FIELD_LABEL = "Active status"

# Masking configuration for card_num (AAP 0.7.8). Only the last four digits are
# ever revealed; the leading digits are replaced with MASK_CHARACTER. For the
# fixed 16-digit PAN this yields exactly twelve mask characters ("*" * 12)
# followed by the last four digits, e.g. "************5740".
MASK_VISIBLE_DIGITS = 4
MASK_PREFIX_LENGTH = CARD_NUM_LENGTH - MASK_VISIBLE_DIGITS
MASK_CHARACTER = "*"

# The X(10) expiration date arrives as "YYYY-MM-DD" (ten characters); the legacy
# edits additionally accept a compact eight-digit "CCYYMMDD" form.
DASHED_DATE_TEXT_LENGTH = 10


# ---------------------------------------------------------------------------
# Private helpers (module-internal; not part of the public API). Each is shared
# by the field validators/serializers below so the rules are stated once (DRY).
# ---------------------------------------------------------------------------


def _MaskCardNumber(value: Optional[str]) -> Optional[str]:
    """Mask a card number so only its last four digits remain visible.

    Implements the AAP 0.7.8 masking rule. Because it is invoked from a
    ``field_serializer`` it runs only during serialization; the underlying model
    attribute is never altered, so internal callers keep access to the full
    value while API responses receive only the masked form.

    Args:
        value: The full card number, or None when the value is unset.

    Returns:
        None when ``value`` is None; a fully masked string when the value is too
        short to safely reveal a four-digit suffix (four characters or fewer);
        otherwise ``MASK_CHARACTER`` repeated ``MASK_PREFIX_LENGTH`` times
        followed by the last ``MASK_VISIBLE_DIGITS`` characters (for the fixed
        16-digit PAN, ``"************" + last four``).
    """
    if value is None:
        return None
    text = str(value)
    if len(text) <= MASK_VISIBLE_DIGITS:
        return MASK_CHARACTER * len(text)
    return (MASK_CHARACTER * MASK_PREFIX_LENGTH) + text[-MASK_VISIBLE_DIGITS:]


def _ParseValidatedLegacyDate(text: str) -> date:
    """Convert an already-validated legacy date string into a ``date``.

    The caller must have confirmed the value with
    :func:`app.utils.date_utils.ValidateDate` first, so ``text`` is guaranteed
    to be either the ten-character ``YYYY-MM-DD`` layout or the eight-digit
    ``CCYYMMDD`` layout. Parsing explicitly (rather than deferring to Pydantic's
    own string handling) guarantees a genuine :class:`datetime.date` regardless
    of input layout.

    Args:
        text: A pre-validated ``YYYY-MM-DD`` or ``CCYYMMDD`` date string.

    Returns:
        The parsed :class:`datetime.date`.
    """
    normalized = text.strip()
    if len(normalized) == DASHED_DATE_TEXT_LENGTH:
        return date_utils.ParseLegacyDate(normalized)
    return date(
        int(normalized[0:4]),
        int(normalized[4:6]),
        int(normalized[6:8]),
    )


def _CoerceExpirationDate(value: object) -> object:
    """Validate and coerce a raw expiration value to a ``date`` (or None).

    Runs in a ``field_validator(..., mode="before")`` so it sees the raw input
    before Pydantic's own date handling. A ``date`` (as returned by the ORM) is
    accepted as-is; a string is routed through
    :func:`app.utils.date_utils.ValidateDate` to reproduce the legacy
    accept/reject decision exactly and, on success, parsed into a genuine
    ``date``; ``None`` passes through so an optional field stays optional and a
    required field still surfaces Pydantic's standard "field required" error.

    Args:
        value: The raw expiration value (``date``, ``str``, ``None``, or other).

    Returns:
        A :class:`datetime.date` for a valid string, the original ``date`` when
        already typed, ``None`` when absent, or the untouched value for any
        other type (which Pydantic then validates or rejects).

    Raises:
        ValueError: If ``value`` is a string that fails the legacy date edit;
            the legacy failure wording is preserved as the message so callers
            observe parity with the mainframe screen.
    """
    if value is None:
        return None
    if isinstance(value, date):
        return value
    if isinstance(value, str):
        result = date_utils.ValidateDate(value)
        if not result.isValid:
            raise ValueError(result.message)
        return _ParseValidatedLegacyDate(value)
    return value


def _ValidateCardNumValue(value: str) -> str:
    """Validate a 16-digit card number, preserving it verbatim as a string.

    Delegates to :func:`app.utils.validators.ValidateNumericId` so the value
    must be exactly :data:`CARD_NUM_LENGTH` ASCII digits (a 16-digit PAN); the
    string is returned unchanged so leading zeros are never dropped.

    Args:
        value: The candidate card number.

    Returns:
        The unchanged ``value`` when it passes the numeric-identifier edit.

    Raises:
        ValueError: Carrying the legacy edit wording when the value is not
            exactly 16 digits.
    """
    result = validators.ValidateNumericId(CARD_NUM_FIELD_LABEL, value, CARD_NUM_LENGTH)
    if not result.isValid:
        raise ValueError(result.message)
    return value


def _ValidateAcctIdValue(value: str) -> str:
    """Validate an 11-digit account identifier, preserving leading zeros.

    Delegates to :func:`app.utils.validators.ValidateNumericId` so the value
    must be exactly :data:`ACCT_ID_LENGTH` ASCII digits; the string is returned
    unchanged (never converted to ``int``) so its zero padding survives.

    Args:
        value: The candidate account identifier.

    Returns:
        The unchanged ``value`` when it passes the numeric-identifier edit.

    Raises:
        ValueError: Carrying the legacy edit wording when the value is not
            exactly 11 digits.
    """
    result = validators.ValidateNumericId(ACCT_ID_FIELD_LABEL, value, ACCT_ID_LENGTH)
    if not result.isValid:
        raise ValueError(result.message)
    return value


def _ValidateActiveStatusValue(value: str) -> str:
    """Validate a card active-status flag as the legacy 'Y'/'N' literal.

    Delegates to :func:`app.utils.validators.ValidateYesNo`, which accepts only
    the uppercase literals ``'Y'`` and ``'N'`` (COBOL 88-level
    ``FLG-YES-NO-ISVALID``).

    Args:
        value: The candidate status flag.

    Returns:
        The unchanged ``value`` when it is exactly ``'Y'`` or ``'N'``.

    Raises:
        ValueError: Carrying the legacy edit wording when the value is neither
            ``'Y'`` nor ``'N'``.
    """
    result = validators.ValidateYesNo(ACTIVE_STATUS_FIELD_LABEL, value)
    if not result.isValid:
        raise ValueError(result.message)
    return value


# ---------------------------------------------------------------------------
# Public API -- schema classes (PascalCase per Ochs Rule 0.8.3).
# ---------------------------------------------------------------------------


class CardBase(OrmBase):
    """Canonical, fully validated representation of a single card.

    Holds the complete non-sensitive card field set (every ``CARD-RECORD``
    field except the CVV) and applies the legacy field edits, so it serves as
    the shared, strictly-validated shape for internal use. It inherits
    :class:`~app.schemas.common.OrmBase`, so it can be populated from a
    SQLAlchemy ``Card`` row via ``CardBase.model_validate(orm_obj)``.

    Security: this class is NOT a bare response envelope. Although the full
    ``card_num`` remains readable through attribute access (needed by internal
    logic), its ``field_serializer`` masks the value in any ``model_dump()`` /
    ``model_dump_json()`` output. API responses should nonetheless use
    :class:`CardRead` (detail) or :class:`CardSummary` (list), which are the
    purpose-built response DTOs.

    Attributes:
        card_num: 16-digit PAN (``CARD-NUM PIC X(16)``), the primary key. Kept
            as a string to preserve leading zeros; masked on serialization.
        acct_id: 11-digit owning-account id (``CARD-ACCT-ID PIC 9(11)``); kept
            as a string so leading zeros survive.
        embossed_name: Name embossed on the card face
            (``CARD-EMBOSSED-NAME PIC X(50)``).
        expiration_date: Card expiry (``CARD-EXPIRAION-DATE PIC X(10)``) as a
            :class:`datetime.date`; optional because the column is nullable.
        active_status: Single-character active flag
            (``CARD-ACTIVE-STATUS PIC X(01)``), ``'Y'`` or ``'N'``.
    """

    card_num: str = Field(
        ...,
        max_length=CARD_NUM_LENGTH,
        description="16-digit card number (CARD-NUM PIC X(16)); masked in output.",
    )
    acct_id: str = Field(
        ...,
        max_length=ACCT_ID_LENGTH,
        description="Owning account id (CARD-ACCT-ID PIC 9(11)); leading zeros kept.",
    )
    embossed_name: str = Field(
        ...,
        min_length=1,
        max_length=EMBOSSED_NAME_MAX_LENGTH,
        description="Name embossed on the card (CARD-EMBOSSED-NAME PIC X(50)).",
    )
    expiration_date: Optional[date] = Field(
        default=None,
        description="Card expiry date (CARD-EXPIRAION-DATE PIC X(10)).",
    )
    active_status: str = Field(
        ...,
        max_length=ACTIVE_STATUS_LENGTH,
        description="Active-status flag (CARD-ACTIVE-STATUS PIC X(01)); 'Y' or 'N'.",
    )

    @field_validator("card_num")
    @classmethod
    def ValidateCardNum(cls, value: str) -> str:
        """Enforce the 16-digit card-number edit (see :func:`_ValidateCardNumValue`)."""
        return _ValidateCardNumValue(value)

    @field_validator("acct_id")
    @classmethod
    def ValidateAcctId(cls, value: str) -> str:
        """Enforce the 11-digit account-id edit (see :func:`_ValidateAcctIdValue`)."""
        return _ValidateAcctIdValue(value)

    @field_validator("active_status")
    @classmethod
    def ValidateActiveStatus(cls, value: str) -> str:
        """Enforce the 'Y'/'N' active-status edit (see :func:`_ValidateActiveStatusValue`)."""
        return _ValidateActiveStatusValue(value)

    @field_validator("expiration_date", mode="before")
    @classmethod
    def CoerceExpirationDate(cls, value: object) -> object:
        """Validate/parse the expiration date (see :func:`_CoerceExpirationDate`)."""
        return _CoerceExpirationDate(value)

    @field_serializer("card_num")
    def SerializeCardNum(self, value: str) -> Optional[str]:
        """Mask ``card_num`` to its last four digits on serialization only."""
        return _MaskCardNumber(value)


class CardRead(OrmBase):
    """Read/response DTO for a single card (``COCRDSLC`` detail view, ``CCDL``).

    Built directly from a SQLAlchemy ``Card`` row via
    ``CardRead.model_validate(orm_obj)`` (``from_attributes`` is enabled on
    :class:`~app.schemas.common.OrmBase`). This is a safe outbound envelope:

        * There is NO ``cvv_cd`` field, so the CVV can never be serialized -- it
          is not read from the ORM row at all (``from_attributes`` only pulls
          declared fields).
        * ``card_num`` is emitted MASKED (last four digits) via
          :meth:`SerializeCardNum`.

    Content edits are intentionally not re-applied here: the values originate
    from the trusted database, and re-validating (for example) a card number
    that has already been masked would be self-defeating. Field length caps are
    retained as lightweight shape guarantees.

    Attributes:
        card_num: 16-digit PAN, serialized masked (e.g. ``************5740``).
        acct_id: Owning-account id (leading zeros preserved).
        embossed_name: Name embossed on the card.
        expiration_date: Card expiry as a :class:`datetime.date` (ISO
            ``YYYY-MM-DD`` in JSON); optional because the column is nullable.
        active_status: Active-status flag, ``'Y'`` or ``'N'``.
    """

    card_num: str = Field(
        ...,
        max_length=CARD_NUM_LENGTH,
        description="16-digit card number (CARD-NUM); returned MASKED (last 4).",
    )
    acct_id: str = Field(
        ...,
        max_length=ACCT_ID_LENGTH,
        description="Owning account id (CARD-ACCT-ID PIC 9(11)); leading zeros kept.",
    )
    embossed_name: str = Field(
        ...,
        max_length=EMBOSSED_NAME_MAX_LENGTH,
        description="Name embossed on the card (CARD-EMBOSSED-NAME PIC X(50)).",
    )
    expiration_date: Optional[date] = Field(
        default=None,
        description="Card expiry date (CARD-EXPIRAION-DATE PIC X(10)).",
    )
    active_status: str = Field(
        ...,
        max_length=ACTIVE_STATUS_LENGTH,
        description="Active-status flag (CARD-ACTIVE-STATUS PIC X(01)); 'Y' or 'N'.",
    )

    @field_serializer("card_num")
    def SerializeCardNum(self, value: str) -> Optional[str]:
        """Mask ``card_num`` to its last four digits on serialization only.

        Enforces the AAP 0.7.8 rule that a full PAN never leaves the service.
        The stored attribute keeps the full value; only the serialized output
        (``model_dump`` / ``model_dump_json``) is masked.
        """
        return _MaskCardNumber(value)


class CardSummary(OrmBase):
    """Thin projection of a card for the browse/list grid (``COCRDLIC``, ``CCLI``).

    Carries only the columns the card-list screen renders per row -- masked card
    number, owning account, status, and the embossed name -- so a page of rows
    stays lightweight. It is designed to be wrapped by the shared
    :class:`app.schemas.common.PaginatedResponse` (``PaginatedResponse``
    parameterized with this type) that the service/router builds; the legacy
    seven-rows-per-page limit (F-004) is enforced by the service, so this schema
    simply carries whatever page items it is given.

    Like :class:`CardRead`, it exposes NO ``cvv_cd`` and masks ``card_num`` on
    serialization.

    Attributes:
        card_num: 16-digit PAN, serialized masked (e.g. ``************5740``).
        acct_id: Owning-account id (leading zeros preserved).
        active_status: Active-status flag, ``'Y'`` or ``'N'``.
        embossed_name: Name embossed on the card (shown in the list row).
    """

    card_num: str = Field(
        ...,
        max_length=CARD_NUM_LENGTH,
        description="16-digit card number (CARD-NUM); returned MASKED (last 4).",
    )
    acct_id: str = Field(
        ...,
        max_length=ACCT_ID_LENGTH,
        description="Owning account id (CARD-ACCT-ID PIC 9(11)); leading zeros kept.",
    )
    active_status: str = Field(
        ...,
        max_length=ACTIVE_STATUS_LENGTH,
        description="Active-status flag (CARD-ACTIVE-STATUS PIC X(01)); 'Y' or 'N'.",
    )
    embossed_name: str = Field(
        ...,
        max_length=EMBOSSED_NAME_MAX_LENGTH,
        description="Name embossed on the card (CARD-EMBOSSED-NAME PIC X(50)).",
    )

    @field_serializer("card_num")
    def SerializeCardNum(self, value: str) -> Optional[str]:
        """Mask ``card_num`` to its last four digits on serialization only."""
        return _MaskCardNumber(value)


class CardUpdate(RequestBase):
    """Request DTO for the editable card fields (``COCRDUPC`` update, ``CCUP``).

    Mirrors exactly the fields the ``COCRDUP`` screen lets an operator change --
    the embossed name, the expiration date, and the active-status flag. The
    identifiers ``card_num`` and ``acct_id`` are record keys supplied on the URL
    path (read-only), so they are intentionally absent from the request body,
    and ``cvv_cd`` is deliberately omitted because the update screen does not
    expose it.

    It inherits :class:`~app.schemas.common.RequestBase` (``extra="forbid"``),
    so any unexpected field -- including an attempt to submit ``card_num``,
    ``acct_id``, or ``cvv_cd`` in the body -- is rejected. This is the input
    sanitization boundary, so the incoming values are strictly edited with the
    same rules the legacy screen applied.

    Attributes:
        embossed_name: New embossed name (``CARD-EMBOSSED-NAME PIC X(50)``);
            required and non-empty.
        expiration_date: New expiry date (``CARD-EXPIRAION-DATE PIC X(10)``) as
            a :class:`datetime.date`; required and validated with the legacy
            date edit.
        active_status: New active-status flag (``CARD-ACTIVE-STATUS PIC X(01)``);
            required and restricted to ``'Y'`` or ``'N'``.
    """

    embossed_name: str = Field(
        ...,
        min_length=1,
        max_length=EMBOSSED_NAME_MAX_LENGTH,
        description="New embossed name (CARD-EMBOSSED-NAME PIC X(50)).",
    )
    expiration_date: date = Field(
        ...,
        description="New expiry date (CARD-EXPIRAION-DATE PIC X(10)).",
    )
    active_status: str = Field(
        ...,
        max_length=ACTIVE_STATUS_LENGTH,
        description="New active-status flag (CARD-ACTIVE-STATUS PIC X(01)); 'Y'/'N'.",
    )

    @field_validator("expiration_date", mode="before")
    @classmethod
    def CoerceExpirationDate(cls, value: object) -> object:
        """Validate/parse the expiration date (see :func:`_CoerceExpirationDate`)."""
        return _CoerceExpirationDate(value)

    @field_validator("active_status")
    @classmethod
    def ValidateActiveStatus(cls, value: str) -> str:
        """Enforce the 'Y'/'N' active-status edit (see :func:`_ValidateActiveStatusValue`)."""
        return _ValidateActiveStatusValue(value)

