# Ported from COBOL copybook CVCUS01Y (CUSTOMER-RECORD); VSAM CUSTDATA KSDS (KEYLEN=9, RKP=0).
#
# Reference-only sources (never modified):
#   - app/cpy/CVCUS01Y.cpy   (01 CUSTOMER-RECORD, fixed length RECLN=500)
#   - app/catlg/LISTCAT.txt  (CUSTDATA cluster: KEYLEN=9, RKP=0, MAXLRECL=500)
#
# The COBOL CUSTOMER-RECORD is the customer master formerly stored in the VSAM
# CUSTDATA KSDS and read/updated by the online programs (COACTVWC view,
# COACTUPC update) and the batch print program (CBCUS01C). This module ports
# that fixed-width record layout to a SQLAlchemy 2.0 ORM model, preserving the
# COBOL field order, field lengths, and the 9-digit primary key while applying
# the CardDemo VSAM -> PostgreSQL type-mapping rules:
#   * PIC 9(n) identifier fields -> VARCHAR(n)  (leading zeros preserved as text)
#   * PIC X(n) free-text fields  -> VARCHAR(n)
#   * PIC X(n) fixed-width codes -> CHAR(n)
#   * PIC X(10) YYYY-MM-DD date  -> DATE
#   * PIC 9(03) FICO score       -> SMALLINT    (a true integer; no leading-zero
#                                                semantics, so it is not text)
# No monetary or interest-rate fields appear on this record, so no Decimal
# handling is required here; floating point is never used anywhere in the port.
"""Customer master ORM model (the ``customers`` table).

Modern port of the COBOL ``CUSTOMER-RECORD`` copybook (``CVCUS01Y``) that backed
the VSAM ``CUSTDATA`` KSDS. Each :class:`Customer` instance corresponds to one
500-byte customer master record keyed by the 9-digit ``CUST-ID``.

Design notes
------------
* ``cust_id`` and ``ssn`` originate from COBOL ``PIC 9(n)`` (zoned numeric) but
  are mapped to ``VARCHAR`` rather than an integer type so that leading zeros in
  the fixed-width source are preserved byte-for-byte (golden-master parity).
* ``fico_credit_score`` is the only ``PIC 9(n)`` field mapped to a true integer
  (``SMALLINT``): it is a computed score (FICO 300-850), not an identifier, so it
  carries no leading-zero semantics.
* ``date_of_birth`` is deliberately named ``date_of_birth`` (not ``dob``) so the
  attribute matches the Pydantic ``customer`` schema, which enables
  ``from_attributes=True`` ORM-to-DTO conversion without field remapping.
* ``ssn`` stores the full value; masking is enforced in the schema/response
  layer, never at rest here.
* ``Customer`` is the *parent* in the customer <-> card cross-reference
  relationship. The foreign key ``card_xref.cust_id -> customers.cust_id`` is
  declared on the child model (:class:`CardXref`), so this model carries only the
  navigation collection :attr:`xrefs` and never a ``ForeignKey``.

The trailing COBOL ``FILLER PIC X(168)`` is intentionally dropped: it exists only
to pad the record to its fixed 500-byte length and has no business meaning in a
relational schema. The header comment above records the original length for
parity traceability.
"""

from __future__ import annotations

from datetime import date
from typing import List, Optional

from sqlalchemy import CHAR, Date, SmallInteger, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base


class Customer(Base):
    """SQLAlchemy ORM model for a CardDemo customer master record.

    Ports the COBOL ``CUSTOMER-RECORD`` layout (copybook ``CVCUS01Y``) to the
    ``customers`` PostgreSQL table. Columns are declared in the original COBOL
    field order; attribute names are the exact snake_case names consumed by the
    Pydantic ``customer`` schema (``from_attributes=True``).
    """

    __tablename__ = "customers"

    # CUST-ID PIC 9(09): primary VSAM KSDS key (KEYLEN=9, RKP=0). Kept as
    # VARCHAR(9) to preserve any leading zeros in the 9-digit customer number.
    cust_id: Mapped[str] = mapped_column(String(9), primary_key=True)  # <- CUST-ID

    # Name fields. First and last names are required on the master record; the
    # middle name is optional.
    first_name: Mapped[str] = mapped_column(String(25))  # <- CUST-FIRST-NAME
    middle_name: Mapped[Optional[str]] = mapped_column(String(25), nullable=True)  # <- CUST-MIDDLE-NAME
    last_name: Mapped[str] = mapped_column(String(25))  # <- CUST-LAST-NAME

    # Mailing address. Line 1 is required; lines 2 and 3 are optional overflow.
    addr_line_1: Mapped[str] = mapped_column(String(50))  # <- CUST-ADDR-LINE-1
    addr_line_2: Mapped[Optional[str]] = mapped_column(String(50), nullable=True)  # <- CUST-ADDR-LINE-2
    addr_line_3: Mapped[Optional[str]] = mapped_column(String(50), nullable=True)  # <- CUST-ADDR-LINE-3

    # Fixed-width geographic codes map to CHAR(n) (space-padded fixed length).
    addr_state_cd: Mapped[Optional[str]] = mapped_column(CHAR(2), nullable=True)  # <- CUST-ADDR-STATE-CD
    addr_country_cd: Mapped[Optional[str]] = mapped_column(CHAR(3), nullable=True)  # <- CUST-ADDR-COUNTRY-CD
    addr_zip: Mapped[Optional[str]] = mapped_column(String(10), nullable=True)  # <- CUST-ADDR-ZIP

    # Contact phone numbers (free text, may include formatting characters).
    phone_num_1: Mapped[Optional[str]] = mapped_column(String(15), nullable=True)  # <- CUST-PHONE-NUM-1
    phone_num_2: Mapped[Optional[str]] = mapped_column(String(15), nullable=True)  # <- CUST-PHONE-NUM-2

    # CUST-SSN PIC 9(09): 9-digit SSN stored as VARCHAR(9) to preserve leading
    # zeros. The full value is stored here; the API/schema layer masks it.
    ssn: Mapped[Optional[str]] = mapped_column(String(9), nullable=True)  # SENSITIVE: masked in API responses

    govt_issued_id: Mapped[Optional[str]] = mapped_column(String(20), nullable=True)  # <- CUST-GOVT-ISSUED-ID

    # CUST-DOB-YYYY-MM-DD PIC X(10): the COBOL text date "YYYY-MM-DD" becomes a
    # native DATE. Attribute is named date_of_birth for schema compatibility.
    date_of_birth: Mapped[Optional[date]] = mapped_column(Date, nullable=True)  # <- CUST-DOB-YYYY-MM-DD

    eft_account_id: Mapped[Optional[str]] = mapped_column(String(10), nullable=True)  # <- CUST-EFT-ACCOUNT-ID
    pri_card_holder_ind: Mapped[Optional[str]] = mapped_column(CHAR(1), nullable=True)  # <- CUST-PRI-CARD-HOLDER-IND

    # CUST-FICO-CREDIT-SCORE PIC 9(03): the sole numeric field mapped to a true
    # integer. SMALLINT (-32768..32767) comfortably holds the FICO range 300-850.
    fico_credit_score: Mapped[Optional[int]] = mapped_column(SmallInteger, nullable=True)  # <- CUST-FICO-CREDIT-SCORE

    # Navigation to the card cross-reference rows for this customer. The FK
    # (card_xref.cust_id -> customers.cust_id) lives on the CardXref child model,
    # so this side is a plain back-reference collection. The target class is given
    # by string name and is resolved at mapper-configuration time once every model
    # module has been imported (see app/models/__init__.py).
    xrefs: Mapped[List["CardXref"]] = relationship(back_populates="customer")  # noqa: F821
