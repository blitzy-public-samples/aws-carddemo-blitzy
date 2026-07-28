# Ported from COBOL copybook CVTRA04Y (TRAN-CAT-RECORD); VSAM TRANCATG KSDS
# (KEYLEN=6 composite, RKP=0). Reference-only origin (never modified):
# app/cpy/CVTRA04Y.cpy. This SQLAlchemy 2.0 ORM model preserves the legacy
# transaction-category reference record layout (RECLN=60) field-for-field.
"""Transaction-category reference model for the CardDemo ORM layer.

This module defines :class:`TransactionCategory`, the modern port of the legacy
COBOL copybook ``CVTRA04Y`` (``TRAN-CAT-RECORD``) that backed the VSAM
``TRANCATG`` KSDS reference file. The record supplied a human-readable
description for each ``(transaction-type, transaction-category)`` pairing and was
read by the online and batch programs (for example the transaction browse/detail
flows and the posting/report jobs) to label transaction categories.

Legacy record layout (``app/cpy/CVTRA04Y.cpy``, RECLN = 60 bytes)::

    01  TRAN-CAT-RECORD.
        05  TRAN-CAT-KEY.
            10  TRAN-TYPE-CD        PIC X(02).   -> tran_type_cd  (PK part 1)
            10  TRAN-CAT-CD         PIC 9(04).   -> tran_cat_cd   (PK part 2)
        05  TRAN-CAT-TYPE-DESC      PIC X(50).   -> tran_cat_type_desc
        05  FILLER                  PIC X(04).   -> dropped (parity only)

Type-mapping decisions (per AAP 0.1.2 / 0.7):

* ``TRAN-TYPE-CD PIC X(02)`` -> ``CHAR(2)``: a fixed-width alphanumeric code.
* ``TRAN-CAT-CD PIC 9(04)`` -> ``VARCHAR(4)``: although the COBOL picture is
  numeric, category codes are identifiers whose *leading zeros are significant*
  (e.g. ``"0001"``). They are therefore stored as text to preserve zero padding,
  following the AAP rule that ``9(n)`` identifier fields map to ``VARCHAR(n)``
  rather than an integer type.
* ``TRAN-CAT-TYPE-DESC PIC X(50)`` -> ``VARCHAR(50)``.

The two key sub-fields form a composite primary key, mirroring the 6-byte
``TRAN-CAT-KEY`` VSAM key (2 + 4). The primary-key constraint name is *not*
hand-authored here: it is derived automatically from the shared
``Base.metadata`` naming convention (``pk_%(table_name)s`` ->
``pk_transaction_category``), keeping Alembic autogenerate output deterministic.

This is a reference (lookup) table, so no foreign keys or relationships are
declared on it; cross-references from fact tables point *to* this table and are
defined on those tables, per the AAP scope discipline for reference data.
"""

from sqlalchemy import CHAR, String
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class TransactionCategory(Base):
    """Transaction-category reference row (one per type/category pairing).

    Modern equivalent of the COBOL ``TRAN-CAT-RECORD`` (copybook ``CVTRA04Y``),
    formerly stored in the VSAM ``TRANCATG`` KSDS. Each row maps a composite
    ``(tran_type_cd, tran_cat_cd)`` key to its descriptive text.

    Attributes:
        tran_type_cd: Two-character transaction-type code (``TRAN-TYPE-CD``);
            first component of the composite primary key.
        tran_cat_cd: Four-character, zero-padded transaction-category code
            (``TRAN-CAT-CD``); second component of the composite primary key.
            Stored as text to preserve significant leading zeros.
        tran_cat_type_desc: Human-readable category description
            (``TRAN-CAT-TYPE-DESC``).
    """

    __tablename__ = "transaction_category"

    # TRAN-TYPE-CD PIC X(02) -> composite primary key part 1 (fixed-width code).
    tran_type_cd: Mapped[str] = mapped_column(CHAR(2), primary_key=True)

    # TRAN-CAT-CD PIC 9(04) -> composite primary key part 2. Numeric identifier
    # stored as VARCHAR(4) so significant leading zeros (e.g. "0001") survive.
    tran_cat_cd: Mapped[str] = mapped_column(String(4), primary_key=True)

    # TRAN-CAT-TYPE-DESC PIC X(50) -> category description. Named
    # ``tran_cat_type_desc`` (never ``description``) so the Pydantic schema's
    # ``from_attributes=True`` mapping resolves against this attribute directly.
    tran_cat_type_desc: Mapped[str] = mapped_column(String(50))

    # FILLER PIC X(04) from the 60-byte legacy record is intentionally dropped;
    # it carried no data and is retained here only as a record-length note
    # (2 + 4 + 50 + 4 = 60 bytes = CVTRA04Y RECLN).

    def __repr__(self) -> str:
        """Return an unambiguous, developer-facing representation."""
        return (
            "TransactionCategory("
            f"tran_type_cd={self.tran_type_cd!r}, "
            f"tran_cat_cd={self.tran_cat_cd!r}, "
            f"tran_cat_type_desc={self.tran_cat_type_desc!r})"
        )


__all__ = ["TransactionCategory"]
