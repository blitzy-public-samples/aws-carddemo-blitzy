"""Transaction category balance DTO. Source: app/cpy/CVTRA01Y.cpy.

Pydantic v2 response schema for the Transaction Category Balance entity, a
faithful port of the legacy COBOL record ``TRAN-CAT-BAL-RECORD`` (RECLN = 50)
defined in ``app/cpy/CVTRA01Y.cpy``. The record carries a running balance for a
single account / transaction-type / transaction-category combination and is
read by the interest-calculation and reporting flows.

Traceability -- legacy copybook ``CVTRA01Y`` field mapping (REFERENCE only)::

    01 TRAN-CAT-BAL-RECORD
       05 TRAN-CAT-KEY                       (composite key)
          10 TRANCAT-ACCT-ID  PIC 9(11)   -> acct_id       (str, width 11)
          10 TRANCAT-TYPE-CD  PIC X(02)   -> tran_type_cd  (str, width 2)
          10 TRANCAT-CD       PIC 9(04)   -> tran_cat_cd   (str, width 4)
       05 TRAN-CAT-BAL        PIC S9(09)V99 -> balance      (Decimal, NUMERIC(11,2))
       05 FILLER              PIC X(22)   -> DROPPED (record-length parity only)

Numeric fidelity (tech spec AAP section 0.7.1): ``TRAN-CAT-BAL`` is a signed
zoned-decimal ``V99`` money field, so ``balance`` is modeled as
:class:`decimal.Decimal` and NEVER as ``float``. The ``balance`` field validator
routes every incoming value through :func:`app.utils.decimal_utils.ToDecimal`,
which rejects ``float`` (and ``bool``) inputs outright; this blocks Pydantic's
default lax ``float`` -> ``Decimal`` coercion that would otherwise inject binary
floating-point rounding error into a monetary value.

Identifier and code fields (``acct_id``, ``tran_cat_cd``) originate from numeric
COBOL ``PIC 9`` pictures but are represented as ``str`` so that fixed-width
leading zeros are preserved exactly (tech spec type-mapping rule).
"""

# Ported from copybook CVTRA01Y (TRAN-CAT-BAL-RECORD). Field names, widths, and
# the signed-decimal balance semantics are preserved verbatim per the Minimal
# Change Clause; the trailing FILLER is intentionally dropped because it carries
# no business meaning (it existed only to pad the record to 50 bytes).

from decimal import Decimal

from pydantic import Field, field_validator

from app.schemas.common import OrmBase
from app.utils import decimal_utils

__all__ = ["TranCategoryBalanceRead"]

# Fixed field widths carried over from the CVTRA01Y picture clauses. Declared as
# ALL_UPPERCASE module constants (Ochs Rule 0.8.2) so the sizes are stated once
# and reused by the field constraints below.
ACCT_ID_LENGTH = 11
TRAN_TYPE_CD_LENGTH = 2
TRAN_CAT_CD_LENGTH = 4

# NUMERIC(11,2) precision/scale for the signed money balance. TRAN-CAT-BAL is
# PIC S9(09)V99 -> 9 integer digits + 2 fractional digits = 11 total digits.
BALANCE_MAX_DIGITS = 11
BALANCE_DECIMAL_PLACES = 2


class TranCategoryBalanceRead(OrmBase):
    """Read/response DTO for a transaction category balance row.

    Populated directly from the ``TranCategoryBalance`` SQLAlchemy ORM instance
    via ``TranCategoryBalanceRead.model_validate(orm_obj)`` (``from_attributes``
    is enabled on :class:`~app.schemas.common.OrmBase`). Mirrors the legacy
    ``TRAN-CAT-BAL-RECORD`` composite key plus its signed money balance.

    Attributes:
        acct_id: Account identifier (legacy ``TRANCAT-ACCT-ID PIC 9(11)``). Held
            as a string to preserve fixed-width leading zeros.
        tran_type_cd: Transaction type code (legacy
            ``TRANCAT-TYPE-CD PIC X(02)``).
        tran_cat_cd: Transaction category code (legacy ``TRANCAT-CD PIC 9(04)``).
            Held as a string to preserve fixed-width leading zeros.
        balance: Running category balance (legacy ``TRAN-CAT-BAL PIC S9(09)V99``)
            as an exact :class:`decimal.Decimal` mapped to ``NUMERIC(11,2)``.
    """

    acct_id: str = Field(
        ...,
        max_length=ACCT_ID_LENGTH,
        description="Account id (TRANCAT-ACCT-ID PIC 9(11)); leading zeros kept.",
    )
    tran_type_cd: str = Field(
        ...,
        max_length=TRAN_TYPE_CD_LENGTH,
        description="Transaction type code (TRANCAT-TYPE-CD PIC X(02)).",
    )
    tran_cat_cd: str = Field(
        ...,
        max_length=TRAN_CAT_CD_LENGTH,
        description="Transaction category code (TRANCAT-CD PIC 9(04)); zeros kept.",
    )
    balance: Decimal = Field(
        ...,
        max_digits=BALANCE_MAX_DIGITS,
        decimal_places=BALANCE_DECIMAL_PLACES,
        description="Signed category balance (TRAN-CAT-BAL PIC S9(09)V99).",
    )

    @field_validator("balance", mode="before")
    @classmethod
    def CoerceBalance(cls, value: object) -> object:
        """Coerce the balance to an exact ``Decimal`` and reject ``float`` input.

        Runs before Pydantic's own type handling so that a ``str``/``int``/
        ``Decimal`` is converted through
        :func:`app.utils.decimal_utils.ToDecimal`, while a ``float`` (or
        ``bool``) is rejected -- preventing the binary floating-point rounding
        that lax ``float`` -> ``Decimal`` coercion would introduce into a
        monetary value (tech spec AAP section 0.7.1).

        A ``None`` value is passed through untouched so that Pydantic reports the
        standard "field required" error for this non-optional field. No rounding
        or truncation is applied here: stored balances already carry scale 2, and
        the ``NUMERIC(11,2)`` field constraint enforces the precision.

        Args:
            value: The raw balance input from the ORM row or request payload.

        Returns:
            A :class:`decimal.Decimal` for numeric input, or the original
            ``None`` when the value is absent.

        Raises:
            ValueError: If the value is a ``float``/``bool`` or an unparseable
                string (surfaced by Pydantic as a validation error).
        """
        if value is None:
            return value
        try:
            return decimal_utils.ToDecimal(value)
        except (TypeError, ValueError) as exc:
            raise ValueError(
                "balance must be an exact decimal (str, int, or Decimal); "
                "float is not accepted"
            ) from exc
