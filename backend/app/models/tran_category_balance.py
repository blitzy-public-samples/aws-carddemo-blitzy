# Ported from COBOL copybook CVTRA01Y (TRAN-CAT-BAL-RECORD); VSAM TCATBALF KSDS
# (KEYLEN=17 composite, RKP=0). Used by interest calc CBACT04C.
#
# Reference-only sources (never modified): app/cpy/CVTRA01Y.cpy,
# app/catlg/LISTCAT.txt. This module ports the per-account, per-category running
# balance record into a SQLAlchemy 2.0 ORM model whose table registers into the
# shared app.db.base.Base metadata (consumed by backend/alembic/env.py).
"""SQLAlchemy ORM model for the transaction-category-balance table.

Modern port of the COBOL copybook ``CVTRA01Y`` (``TRAN-CAT-BAL-RECORD``, record
length 50), which described the VSAM ``TCATBALF`` KSDS -- the file of
per-account, per-transaction-category running balances. The legacy interest
calculation batch program ``CBACT04C`` reads this file to obtain each category
balance before computing monthly interest
(``TRAN-CAT-BAL * DIS-INT-RATE / 1200``).

The original 50-byte fixed record layout is::

    01  TRAN-CAT-BAL-RECORD.
        05  TRAN-CAT-KEY.
            10  TRANCAT-ACCT-ID  PIC 9(11).      -> acct_id      (PK part 1)
            10  TRANCAT-TYPE-CD  PIC X(02).      -> tran_type_cd (PK part 2)
            10  TRANCAT-CD       PIC 9(04).      -> tran_cat_cd  (PK part 3)
        05  TRAN-CAT-BAL         PIC S9(09)V99.  -> balance (NUMERIC(11, 2))
        05  FILLER               PIC X(22).      -> dropped

The three-part group ``TRAN-CAT-KEY`` is the 17-byte VSAM primary key
(``KEYLEN=17``, ``RKP=0`` per ``app/catlg/LISTCAT.txt``); it becomes a
three-column composite ``PRIMARY KEY`` here. With the shared naming convention
declared in :mod:`app.db.base`, that primary key is deterministically named
``pk_tran_category_balance``.

Key semantics preserved from the mainframe:
    * Numeric *identifier* fields (``PIC 9(n)``) map to ``VARCHAR(n)`` so that
      leading zeros are preserved exactly as stored on the mainframe. They are
      identifiers, not quantities, so they are never treated as integers.
    * ``TRAN-CAT-BAL`` is a *signed zoned-decimal DISPLAY* field
      (``PIC S9(09)V99``), NOT ``COMP-3`` packed decimal (AAP 0.7.1, Finding
      #1). It maps to ``NUMERIC(11, 2)`` and is surfaced as a Python
      :class:`decimal.Decimal`; ``float`` is never used, guaranteeing exact
      regulatory numeric parity.
    * The trailing ``FILLER PIC X(22)`` carried no data and is intentionally
      dropped; it is retained only as a comment below for 50-byte
      record-length parity documentation.

No foreign keys are declared on this table. Although ``acct_id`` mirrors the
account identifier, the extra reference/balance-table foreign key
(``acct_id -> accounts``) is intentionally omitted for scope discipline and
seed-order safety, per the AAP hard foreign-key list; ``acct_id`` here is purely
a composite-primary-key column. No ORM relationships are defined.

Example:
    Construct a category-balance row using an exact ``Decimal`` (never a
    float) for the running balance::

        >>> from decimal import Decimal
        >>> row = TranCategoryBalance(
        ...     acct_id="00000000011",
        ...     tran_type_cd="01",
        ...     tran_cat_cd="0005",
        ...     balance=Decimal("1234.56"),
        ... )
        >>> row.acct_id, row.tran_type_cd, row.tran_cat_cd
        ('00000000011', '01', '0005')
"""

# Ported from COBOL copybook CVTRA01Y (TRAN-CAT-BAL-RECORD); see tech spec AAP
# section 0.7.1 (Finding #1: the balance field is signed zoned-decimal DISPLAY,
# not COMP-3), which fixes TRAN-CAT-BAL -> NUMERIC(11, 2) mapped to Decimal.

from decimal import Decimal

from sqlalchemy import CHAR, Numeric, String
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class TranCategoryBalance(Base):
    """Per-account, per-category running balance (VSAM ``TCATBALF`` -> table).

    Ports ``CVTRA01Y`` ``TRAN-CAT-BAL-RECORD``. The three key parts form a
    composite primary key in the exact COBOL field order; ``balance`` holds the
    signed running balance as an exact ``NUMERIC(11, 2)`` / :class:`decimal.Decimal`
    (AAP 0.7.1 Finding #1). Read by the interest-calculation job ported from
    ``CBACT04C``.
    """

    __tablename__ = "tran_category_balance"

    # --- TRAN-CAT-KEY: 17-byte VSAM composite primary key (KEYLEN=17, RKP=0) ---

    # Composite PK part 1 <- TRANCAT-ACCT-ID PIC 9(11). Numeric-identifier field
    # stored as VARCHAR(11) to preserve leading zeros (never treated as an int).
    acct_id: Mapped[str] = mapped_column(String(11), primary_key=True)

    # Composite PK part 2 <- TRANCAT-TYPE-CD PIC X(02). Fixed 2-character
    # transaction type code -> CHAR(2).
    tran_type_cd: Mapped[str] = mapped_column(CHAR(2), primary_key=True)

    # Composite PK part 3 <- TRANCAT-CD PIC 9(04). Numeric-identifier field
    # stored as VARCHAR(4) to preserve leading zeros.
    tran_cat_cd: Mapped[str] = mapped_column(String(4), primary_key=True)

    # --- Data field ---

    # TRAN-CAT-BAL PIC S9(09)V99 -- signed zoned-decimal DISPLAY (NOT COMP-3;
    # AAP 0.7.1 Finding #1). Exact NUMERIC(11, 2) surfaced as Python Decimal;
    # float is never used, preserving regulatory numeric parity.
    balance: Mapped[Decimal] = mapped_column(Numeric(11, 2))

    # FILLER PIC X(22) -- unused trailing padding to the 50-byte record length.
    # It carried no data on the mainframe and is intentionally dropped here;
    # documented only for record-length parity (17 + 11 + 22 = 50 bytes).

    def __repr__(self) -> str:
        """Return an unambiguous, secret-free debug representation of the row."""
        return (
            "TranCategoryBalance("
            f"acct_id={self.acct_id!r}, "
            f"tran_type_cd={self.tran_type_cd!r}, "
            f"tran_cat_cd={self.tran_cat_cd!r}, "
            f"balance={self.balance!r})"
        )
