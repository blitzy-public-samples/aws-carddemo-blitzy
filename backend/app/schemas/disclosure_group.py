"""Disclosure group DTO. Source: app/cpy/CVTRA02Y.cpy. interest_rate = NUMERIC(6,2) per §0.7 Finding #2.

Pydantic v2 response schema (DTO) for the Disclosure Group entity -- the
interest-rate lookup keyed by (account group, transaction type, transaction
category). This module is a faithful 1:1 port of the legacy COBOL copybook
``app/cpy/CVTRA02Y.cpy`` (DIS-GROUP-RECORD, record length 50), preserving the
composite key and the signed decimal interest rate exactly (Minimal Change
Clause, AAP §0.8.1).

Traceability (legacy COBOL copybook, REFERENCE only):
    * ``app/cpy/CVTRA02Y.cpy`` -- DIS-GROUP-RECORD (RECLN 50):
        - DIS-GROUP-KEY (composite primary key):
            DIS-ACCT-GROUP-ID  PIC X(10)     -> ``acct_group_id`` (str, <= 10)
            DIS-TRAN-TYPE-CD   PIC X(02)     -> ``tran_type_cd``  (str, <= 2)
            DIS-TRAN-CAT-CD    PIC 9(04)     -> ``tran_cat_cd``   (str, 4 digits)
        - DIS-INT-RATE         PIC S9(04)V99 -> ``interest_rate`` (Decimal 6,2)
        - FILLER               PIC X(28)     -> dropped (record-length padding)

Numeric fidelity (AAP §0.7 Finding #2): ``DIS-INT-RATE`` is ``PIC S9(04)V99``
-- four integer digits plus two fractional digits = six significant digits --
so the faithful relational mapping is ``NUMERIC(6,2)`` (NOT ``NUMERIC(5,2)``).
The rate feeds the monthly interest computation
``interest = balance * rate / 1200`` (AAP §0.7.2), so it is represented as an
exact :class:`decimal.Decimal` and NEVER as a binary ``float`` -- float
rounding would be a regulatory (currency) correctness failure.

The numeric code ``tran_cat_cd`` is modeled as a fixed-width ``str`` (not an
``int``) to preserve the zero-padded leading digits of ``PIC 9(04)`` (for
example ``"0001"``), consistent with the AAP rule that numeric identifier /
code fields map to strings.
"""

# Ported from COBOL copybook CVTRA02Y (DIS-GROUP-RECORD). See AAP §0.7 Finding
# #2 for the NUMERIC(6,2) interest-rate precision decision and §0.8.3 for the
# naming-convention resolution applied below: data field names stay snake_case
# (the DTO / JSON / ORM-column contract), while classes and methods are
# PascalCase and module-level constants are ALL_UPPERCASE (Ochs Rule).

from decimal import Decimal

from pydantic import Field, field_validator

from app.schemas.common import OrmBase
from app.utils import decimal_utils

__all__ = ["DisclosureGroupRead"]


# ---------------------------------------------------------------------------
# Field constraints (Ochs Rule §0.8.2: constants are ALL_UPPERCASE with
# underscores), ported directly from the CVTRA02Y field pictures.
# ---------------------------------------------------------------------------

# DIS-ACCT-GROUP-ID PIC X(10): up to 10 characters. Values are left-justified
# and space-padded on the mainframe (for example "DEFAULT", "ZEROAPR",
# "A000000000"); OrmBase strips the incidental trailing whitespace.
ACCT_GROUP_ID_MAX_LENGTH = 10

# DIS-TRAN-TYPE-CD PIC X(02): a two-character transaction-type code.
TRAN_TYPE_CD_MAX_LENGTH = 2

# DIS-TRAN-CAT-CD PIC 9(04): a four-digit transaction-category code kept as a
# zero-padded string. The pattern enforces exactly four ASCII digits so the
# leading zeros of values such as "0001" are never lost.
TRAN_CAT_CD_LENGTH = 4
TRAN_CAT_CD_PATTERN = r"^[0-9]{4}$"

# DIS-INT-RATE PIC S9(04)V99 -> NUMERIC(6,2): four integer digits + two
# fractional digits = six significant digits, two decimal places (AAP §0.7
# Finding #2). MONEY_SCALE (2) is reused so the fractional precision stays in
# lock-step with the shared monetary scale defined in decimal_utils.
INTEREST_RATE_MAX_DIGITS = 6
INTEREST_RATE_DECIMAL_PLACES = decimal_utils.MONEY_SCALE


class DisclosureGroupRead(OrmBase):
    """Read/response DTO for a single disclosure-group interest-rate row.

    Built directly from a SQLAlchemy ORM row via ``from_attributes`` (inherited
    from :class:`app.schemas.common.OrmBase`), so a repository can return
    ``DisclosureGroupRead.model_validate(orm_row)``. The three key fields form
    the composite primary key ``DIS-GROUP-KEY``; ``interest_rate`` is the signed
    ``NUMERIC(6,2)`` value consumed by the interest-calculation job.
    """

    acct_group_id: str = Field(
        ...,
        min_length=1,
        max_length=ACCT_GROUP_ID_MAX_LENGTH,
        description=(
            "Account group identifier (DIS-ACCT-GROUP-ID, PIC X(10)); part of "
            "the composite disclosure-group key."
        ),
    )
    tran_type_cd: str = Field(
        ...,
        min_length=1,
        max_length=TRAN_TYPE_CD_MAX_LENGTH,
        description=(
            "Transaction type code (DIS-TRAN-TYPE-CD, PIC X(02)); part of the "
            "composite disclosure-group key."
        ),
    )
    tran_cat_cd: str = Field(
        ...,
        min_length=TRAN_CAT_CD_LENGTH,
        max_length=TRAN_CAT_CD_LENGTH,
        pattern=TRAN_CAT_CD_PATTERN,
        description=(
            "Transaction category code (DIS-TRAN-CAT-CD, PIC 9(04)); a "
            "zero-padded four-digit string that preserves leading zeros."
        ),
    )
    interest_rate: Decimal = Field(
        ...,
        max_digits=INTEREST_RATE_MAX_DIGITS,
        decimal_places=INTEREST_RATE_DECIMAL_PLACES,
        description=(
            "Disclosure interest rate (DIS-INT-RATE, PIC S9(04)V99) as an exact "
            "NUMERIC(6,2) Decimal; feeds interest = balance * rate / 1200."
        ),
    )

    @field_validator("interest_rate", mode="before")
    @classmethod
    def ValidateInterestRate(cls, value: object) -> Decimal:
        """Coerce the raw input to an exact ``Decimal`` and reject ``float``.

        Runs before Pydantic's own numeric parsing so the raw value is routed
        through :func:`app.utils.decimal_utils.ToDecimal`, which accepts
        ``str`` / ``int`` / ``Decimal`` (including the ``Decimal`` the
        PostgreSQL driver returns for a ``NUMERIC`` column) and rejects
        ``float`` so binary floating-point rounding can never contaminate the
        NUMERIC(6,2) interest rate.

        Args:
            value: The raw ``interest_rate`` input (``str``, ``int``, or
                ``Decimal``).

        Returns:
            The value as an exact :class:`decimal.Decimal`.

        Raises:
            ValueError: If ``value`` is a ``float`` or any other type that
                cannot be converted to an exact ``Decimal``. The underlying
                ``TypeError`` / ``ValueError`` from
                :func:`app.utils.decimal_utils.ToDecimal` is re-raised as a
                ``ValueError`` so Pydantic surfaces a clean ``ValidationError``
                (Pydantic does not wrap a raw ``TypeError``).
        """
        try:
            return decimal_utils.ToDecimal(value)
        except (TypeError, ValueError) as exc:
            raise ValueError(
                "interest_rate must be an exact Decimal value (str, int, or "
                "Decimal); float is rejected to preserve NUMERIC(6,2) "
                f"precision ({exc})"
            ) from exc
