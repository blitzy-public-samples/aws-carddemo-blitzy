"""Transaction category DTO. Source: app/cpy/CVTRA04Y.cpy.

Pydantic v2 data-transfer object for the Transaction Category lookup entity, a
static reference table that names each ``(transaction type, transaction
category)`` pair. It is the modern equivalent of the legacy COBOL copybook
``CVTRA04Y`` (record ``TRAN-CAT-RECORD``, RECLN 60), whose layout is::

    01  TRAN-CAT-RECORD.
        05  TRAN-CAT-KEY.
            10  TRAN-TYPE-CD          PIC X(02).   -> tran_type_cd
            10  TRAN-CAT-CD           PIC 9(04).   -> tran_cat_cd (string)
        05  TRAN-CAT-TYPE-DESC        PIC X(50).   -> tran_cat_type_desc
        05  FILLER                    PIC X(04).   -> dropped

The composite key is ``(tran_type_cd, tran_cat_cd)``. ``tran_cat_cd`` is a
numeric code in the legacy record (``PIC 9(04)``) but is represented here as a
fixed-width 4-character string so that leading zeros are preserved, per the AAP
type rule for numeric identifiers/codes -- it is never modeled as an ``int``.

This module is a leaf of the schema dependency graph: it depends only on
:mod:`app.schemas.common` (for :class:`OrmBase`).
"""

from pydantic import Field

from app.schemas.common import OrmBase

__all__ = ["TransactionCategoryRead"]

# Field-width constants (Ochs Rule 0.8.2: constants are ALL_UPPERCASE), sized
# exactly to the CVTRA04Y copybook PICTURE clauses so the DTO validation edits
# match the legacy fixed-width record byte-for-byte.
TRAN_TYPE_CD_LENGTH = 2
TRAN_CAT_CD_LENGTH = 4
TRAN_CAT_TYPE_DESC_MAX_LENGTH = 50


class TransactionCategoryRead(OrmBase):
    """Read/response schema for a single transaction-category lookup row.

    Serializes a ``TransactionCategory`` ORM instance for API responses.
    Inherits :class:`OrmBase`, so ``model_config.from_attributes`` is enabled
    and an instance can be built directly from an ORM row via
    ``TransactionCategoryRead.model_validate(orm_row)``.

    Traceability: legacy COBOL copybook ``CVTRA04Y`` (``TRAN-CAT-RECORD``).
    """

    tran_type_cd: str = Field(
        ...,
        max_length=TRAN_TYPE_CD_LENGTH,
        description=(
            "Transaction type code. Legacy TRAN-TYPE-CD PIC X(02); the first "
            "component of the composite transaction-category key."
        ),
    )
    tran_cat_cd: str = Field(
        ...,
        min_length=TRAN_CAT_CD_LENGTH,
        max_length=TRAN_CAT_CD_LENGTH,
        pattern=r"^\d{4}$",
        description=(
            "Transaction category code. Legacy TRAN-CAT-CD PIC 9(04); a "
            "4-digit numeric code kept as a fixed-width string to preserve "
            "leading zeros (never an int). Second component of the composite "
            "key."
        ),
    )
    tran_cat_type_desc: str = Field(
        ...,
        max_length=TRAN_CAT_TYPE_DESC_MAX_LENGTH,
        description=("Human-readable category description. Legacy TRAN-CAT-TYPE-DESC PIC X(50)."),
    )
