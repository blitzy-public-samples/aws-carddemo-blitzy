"""Transaction type DTO. Source: app/cpy/CVTRA03Y.cpy.

The transaction type is a small reference/lookup entity that names each
two-character transaction type code used throughout the CardDemo transaction
and posting flows (for example on the transaction detail/add screens and the
batch interest-posting job, which records interest as type ``01``). This module
is the modern equivalent of the legacy ``TRAN-TYPE-RECORD`` (RECLN 60) declared
in the ``CVTRA03Y`` copybook:

    05  TRAN-TYPE       PIC X(02).   -> tran_type       (primary key)
    05  TRAN-TYPE-DESC  PIC X(50).   -> tran_type_desc
    05  FILLER          PIC X(08).   -> dropped (record padding only)

Only a read/response contract is required by the current REST surface, so a
single :class:`TransactionTypeRead` schema is exposed here. It inherits
:class:`app.schemas.common.OrmBase`, which enables population directly from a
SQLAlchemy ``TransactionType`` ORM instance via ``model_validate`` (Pydantic v2
``from_attributes``) and strips the fixed-width space padding carried over from
the mainframe record.
"""

from pydantic import Field

from app.schemas.common import OrmBase


class TransactionTypeRead(OrmBase):
    """Read/response DTO for a single transaction-type lookup row.

    Mirrors the ``TRAN-TYPE-RECORD`` layout of ``CVTRA03Y``. Instances are
    normally built from an ORM row using
    ``TransactionTypeRead.model_validate(orm_obj)`` -- ``from_attributes`` is
    enabled by :class:`~app.schemas.common.OrmBase`, which also trims the
    trailing blanks inherited from the fixed-width mainframe field.
    """

    # TRAN-TYPE PIC X(02): the fixed two-character type code and primary key of
    # the lookup table. Kept as a string (not an int) so the exact code
    # characters -- including any leading zero, e.g. "01" -- are preserved. The
    # length cap mirrors the copybook width and doubles as input validation.
    tran_type: str = Field(
        ...,
        max_length=2,
        description="Two-character transaction type code (primary key).",
    )

    # TRAN-TYPE-DESC PIC X(50): human-readable description of the type code.
    tran_type_desc: str = Field(
        ...,
        max_length=50,
        description="Human-readable description of the transaction type.",
    )
