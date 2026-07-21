"""Card cross-reference DTO. Source: app/cpy/CVACT03Y.cpy (CARDXREF/CXACAIX).

Pydantic v2 response schema (DTO) for the Card cross-reference entity -- the
linkage that ties a card number to the customer and account that own it. This
module is a faithful 1:1 port of the legacy COBOL copybook
``app/cpy/CVACT03Y.cpy`` (``CARD-XREF-RECORD``, record length 50), the record
behind the VSAM ``CARDXREF`` KSDS and its alternate index ``CXACAIX``. In the
modernized schema those VSAM relationships become explicit PostgreSQL foreign
keys (referential-integrity requirement, AAP section 0.7.4).

Traceability (legacy COBOL copybook ``CVACT03Y``, REFERENCE only):

    01 CARD-XREF-RECORD                         (RECLN 50)
       05 XREF-CARD-NUM  PIC X(16)  -> card_num (str, width 16; PK/FK; MASKED)
       05 XREF-CUST-ID   PIC 9(09)  -> cust_id  (str, width 9; FK -> customers)
       05 XREF-ACCT-ID   PIC 9(11)  -> acct_id  (str, width 11; FK -> accounts)
       05 FILLER         PIC X(14)  -> dropped  (record-length padding only)

Security (AAP sections 0.7.8 and 0.8.2 -- Ochs "sanitize/validate all data"):
``card_num`` is a Primary Account Number and is therefore SENSITIVE. Every
response-facing representation masks it to its last four characters via a
``field_serializer``; the full number is never emitted in ``model_dump`` or
``model_dump_json`` output. Consistent with the ORM model's PAN-safe ``__repr__``
(``app/models/card_xref.py``), only an unmasked *internal* representation would
ever expose the raw value, and none is defined here on purpose.

Identifier fidelity (AAP type-mapping rule): ``cust_id`` and ``acct_id`` come
from numeric COBOL ``PIC 9`` pictures but are represented as fixed-width ``str``
so that zero-padded leading digits (for example ``"000000050"``) are preserved
exactly. They are NEVER converted to ``int`` (which would drop the leading
zeros) and no floating-point type is ever involved.
"""

# Ported from COBOL copybook CVACT03Y (CARD-XREF-RECORD). The trailing FILLER is
# intentionally dropped because it carried no business meaning (it existed only
# to pad the legacy record to its 50-byte RECLN). Naming follows the Ochs Rule
# resolution (AAP section 0.8.3): the data field names stay snake_case (the DTO
# / JSON / ORM-column contract), while the class and its methods are PascalCase
# and the module-level constants are ALL_UPPERCASE with underscores.

from pydantic import Field, field_serializer, field_validator

from app.schemas.common import OrmBase
from app.utils import validators

__all__ = ["CardXrefRead"]


# ---------------------------------------------------------------------------
# Fixed field widths, ported verbatim from the CVACT03Y picture clauses (Ochs
# Rule section 0.8.2: constants are ALL_UPPERCASE). Declaring them once keeps
# the Field constraints and the reused-validator calls in lock-step.
# ---------------------------------------------------------------------------
CARD_NUM_LENGTH = 16    # XREF-CARD-NUM PIC X(16).
CUST_ID_LENGTH = 9      # XREF-CUST-ID  PIC 9(09).
ACCT_ID_LENGTH = 11     # XREF-ACCT-ID  PIC 9(11).

# Card-number masking policy: reveal only the trailing digits, hide the rest
# behind a fixed mask character. For the 16-character CardDemo PAN this yields
# twelve mask characters followed by the last four digits (for example
# "************5740"), matching the sensitive-data rule shared with card.py.
CARD_NUM_VISIBLE_DIGITS = 4
CARD_NUM_MASK_CHAR = "*"


class CardXrefRead(OrmBase):
    """Read/response DTO for a single card cross-reference row.

    Built directly from a SQLAlchemy ``CardXref`` ORM row via ``from_attributes``
    (inherited from :class:`~app.schemas.common.OrmBase`), so a repository can
    return ``CardXrefRead.model_validate(orm_row)``. The ORM model exposes a
    ``card_num`` synonym for its physical ``xref_card_num`` column, so the three
    attributes below populate cleanly from the ORM instance.

    The ``card_num`` value is validated as a full 16-character number on input
    but is always emitted MASKED (last four characters only) by
    :meth:`MaskCardNum`, so no complete Primary Account Number ever leaves the
    service in a serialized response.

    Attributes:
        card_num: 16-character card number (legacy ``XREF-CARD-NUM PIC X(16)``);
            the cross-reference primary key and a foreign key to ``cards``.
            MASKED to the last four characters in every serialized response.
        cust_id: 9-digit customer identifier (legacy ``XREF-CUST-ID PIC 9(09)``);
            foreign key to ``customers``. Held as a string to preserve leading
            zeros.
        acct_id: 11-digit account identifier (legacy
            ``XREF-ACCT-ID PIC 9(11)``); foreign key to ``accounts``. Held as a
            string to preserve leading zeros.
    """

    card_num: str = Field(
        ...,
        max_length=CARD_NUM_LENGTH,
        description=(
            "Card number (XREF-CARD-NUM PIC X(16)); cross-reference primary key "
            "and foreign key. MASKED to the last four characters in responses."
        ),
    )
    cust_id: str = Field(
        ...,
        max_length=CUST_ID_LENGTH,
        description=(
            "Customer identifier (XREF-CUST-ID PIC 9(09)); foreign key to "
            "customers. Nine-digit string; leading zeros preserved."
        ),
    )
    acct_id: str = Field(
        ...,
        max_length=ACCT_ID_LENGTH,
        description=(
            "Account identifier (XREF-ACCT-ID PIC 9(11)); foreign key to "
            "accounts. Eleven-digit string; leading zeros preserved."
        ),
    )

    @field_validator("card_num")
    @classmethod
    def ValidateCardNum(cls, value: str) -> str:
        """Validate the card number as an exactly 16-character field.

        ``XREF-CARD-NUM`` is ``PIC X(16)`` (fixed-width alphanumeric), so the
        edit reuses :func:`app.utils.validators.ValidateLength` to require an
        exact 16-character value -- the same accept/reject behavior the legacy
        program applied to the fixed-width screen/record field.

        Args:
            value: The raw card-number string (already stripped of incidental
                surrounding whitespace by :class:`~app.schemas.common.OrmBase`).

        Returns:
            The unmodified, validated 16-character card number (masking happens
            only at serialization time, in :meth:`MaskCardNum`).

        Raises:
            ValueError: If the value is not exactly :data:`CARD_NUM_LENGTH`
                characters long; Pydantic surfaces this as a ``ValidationError``.
        """
        result = validators.ValidateLength("card_num", value, CARD_NUM_LENGTH)
        if not result.isValid:
            raise ValueError(result.message)
        return value

    @field_validator("cust_id")
    @classmethod
    def ValidateCustId(cls, value: str) -> str:
        """Validate the customer identifier as a 9-digit numeric id string.

        ``XREF-CUST-ID`` is ``PIC 9(09)``. The edit reuses
        :func:`app.utils.validators.ValidateNumericId`, which requires exactly
        nine ASCII digits and keeps the value as a string so the zero-padded
        leading digits are never lost to an ``int`` conversion.

        Args:
            value: The raw customer-identifier string.

        Returns:
            The unmodified, validated 9-digit customer identifier.

        Raises:
            ValueError: If the value is not exactly :data:`CUST_ID_LENGTH` ASCII
                digits; Pydantic surfaces this as a ``ValidationError``.
        """
        result = validators.ValidateNumericId("cust_id", value, CUST_ID_LENGTH)
        if not result.isValid:
            raise ValueError(result.message)
        return value

    @field_validator("acct_id")
    @classmethod
    def ValidateAcctId(cls, value: str) -> str:
        """Validate the account identifier as an 11-digit numeric id string.

        ``XREF-ACCT-ID`` is ``PIC 9(11)``. The edit reuses
        :func:`app.utils.validators.ValidateNumericId`, which requires exactly
        eleven ASCII digits and keeps the value as a string so the zero-padded
        leading digits are preserved (never converted to ``int``).

        Args:
            value: The raw account-identifier string.

        Returns:
            The unmodified, validated 11-digit account identifier.

        Raises:
            ValueError: If the value is not exactly :data:`ACCT_ID_LENGTH` ASCII
                digits; Pydantic surfaces this as a ``ValidationError``.
        """
        result = validators.ValidateNumericId("acct_id", value, ACCT_ID_LENGTH)
        if not result.isValid:
            raise ValueError(result.message)
        return value

    @field_serializer("card_num")
    def MaskCardNum(self, value: str) -> str:
        """Mask the card number to its trailing digits for every response.

        Applied during both ``model_dump`` and ``model_dump_json`` (Pydantic's
        default ``when_used="always"``), so the full Primary Account Number is
        never serialized. All but the last :data:`CARD_NUM_VISIBLE_DIGITS`
        characters are replaced with :data:`CARD_NUM_MASK_CHAR`; for the
        16-character CardDemo PAN this produces twelve mask characters followed
        by the last four digits.

        The guard returns a value that is missing or too short to mask
        unchanged, so a blank or unexpectedly short value can never raise here.

        Args:
            value: The validated card number to mask.

        Returns:
            The masked card number (for example ``"************5740"``), or the
            original value when it is empty or has at most
            :data:`CARD_NUM_VISIBLE_DIGITS` characters.
        """
        if not value or len(value) <= CARD_NUM_VISIBLE_DIGITS:
            return value
        maskedSegment = CARD_NUM_MASK_CHAR * (len(value) - CARD_NUM_VISIBLE_DIGITS)
        return f"{maskedSegment}{value[-CARD_NUM_VISIBLE_DIGITS:]}"
