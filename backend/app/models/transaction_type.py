# Ported from COBOL copybook CVTRA03Y (TRAN-TYPE-RECORD); VSAM TRANTYPE KSDS
# (KEYLEN=2, RKP=0). Reference-only source (never modified): app/cpy/CVTRA03Y.cpy.
# Modern SQLAlchemy 2.0 ORM port of the legacy transaction-type reference file:
# it replaces the VSAM KSDS with the PostgreSQL "transaction_type" table,
# registered on the shared app.db.base.Base metadata (AAP 0.5.1).
"""Transaction-type reference model for the CardDemo ORM layer.

Modern port of the legacy COBOL copybook ``CVTRA03Y`` (``TRAN-TYPE-RECORD``,
record length 60), which described the VSAM ``TRANTYPE`` KSDS -- a small,
standalone lookup file keyed on a two-character transaction-type code. The
copybook layout (verified against ``app/cpy/CVTRA03Y.cpy``) is::

    01  TRAN-TYPE-RECORD.
        05  TRAN-TYPE          PIC X(02).   -> primary key (RKP=0, KEYLEN=2)
        05  TRAN-TYPE-DESC     PIC X(50).
        05  FILLER             PIC X(08).   -> dropped (record-length padding)

The VSAM primary key ``TRAN-TYPE`` (relative key position 0, length 2) becomes
the PostgreSQL ``PRIMARY KEY``. The trailing ``FILLER`` is intentionally not
mapped: on the mainframe it only padded the record to its 60-byte length and
carries no business meaning. This is a reference/lookup table, so -- per the
AAP scope discipline -- it declares no foreign keys or relationships even
though other tables (for example ``transactions``) reference the type codes.

The description column is deliberately named ``tran_type_desc`` (not
``description``) so that the transaction-type Pydantic schema, which reads ORM
attributes by name through ``ConfigDict(from_attributes=True)``, maps cleanly
onto this model.
"""

from sqlalchemy import CHAR, String
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class TransactionType(Base):
    """ORM model for the ``transaction_type`` reference table.

    One row per transaction-type code, ported one-to-one from the legacy VSAM
    ``TRANTYPE`` file (copybook ``CVTRA03Y``). Column order and fixed widths are
    preserved from the COBOL record so that seed data loaded from
    ``app/data/ASCII`` and the golden-master parity checks reconcile
    field-for-field with the mainframe original.
    """

    __tablename__ = "transaction_type"

    # TRAN-TYPE PIC X(02): two-character transaction-type code and the VSAM
    # primary key (RKP=0, KEYLEN=2). Fixed-width code -> CHAR(2). The
    # primary-key constraint is auto-named ``pk_transaction_type`` by the shared
    # MetaData naming convention in app.db.base (never hand-named here).
    tran_type: Mapped[str] = mapped_column(CHAR(2), primary_key=True)

    # TRAN-TYPE-DESC PIC X(50): human-readable description of the transaction
    # type. Named ``tran_type_desc`` (never ``description``) to match the
    # transaction-type Pydantic DTO field consumed via from_attributes.
    tran_type_desc: Mapped[str] = mapped_column(String(50))

    # NOTE: the legacy copybook's trailing FILLER PIC X(08) is intentionally
    # dropped. It held no data and existed only to pad TRAN-TYPE-RECORD to its
    # 60-byte VSAM record length (2 + 50 + 8 = 60); it has no relational meaning.


__all__ = ["TransactionType"]
