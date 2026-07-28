# Ported from COBOL copybook CVTRA02Y (DIS-GROUP-RECORD); VSAM DISCGRP KSDS
# (KEYLEN=16 composite, RKP=0). Feeds interest calc CBACT04C.
"""SQLAlchemy 2.0 ORM model for the CardDemo *disclosure group* table.

Modern port of the legacy z/OS copybook ``CVTRA02Y`` (``DIS-GROUP-RECORD``,
record length 50) that described the VSAM KSDS ``DISCGRP`` dataset. Each row
associates an *account group* + *transaction type* + *transaction category*
triple with the monthly interest rate that the interest-calculation batch
program ``CBACT04C`` -- re-expressed in Python as
``batch/jobs/interest_calc.py`` -- applies when accruing finance charges.

Legacy record layout (``app/cpy/CVTRA02Y.cpy``, verified)::

    01  DIS-GROUP-RECORD.                       *> RECLN = 50
        05  DIS-GROUP-KEY.                      *> 16-byte composite key, RKP=0
            10  DIS-ACCT-GROUP-ID  PIC X(10).   *> -> group_id     (PK part 1)
            10  DIS-TRAN-TYPE-CD   PIC X(02).   *> -> tran_type_cd (PK part 2)
            10  DIS-TRAN-CAT-CD    PIC 9(04).   *> -> tran_cat_cd  (PK part 3)
        05  DIS-INT-RATE           PIC S9(04)V99. *> -> interest_rate NUMERIC(6,2)
        05  FILLER                 PIC X(28).   *> dropped (record padding only)

Mapping rules (AAP sections 0.1.2, 0.7):

* The three key fields form a **composite primary key**, mirroring the VSAM
  KSDS primary key (``app/catlg/LISTCAT.txt``: ``KEYLEN=16``, ``RKP=0``).
* ``DIS-TRAN-CAT-CD PIC 9(04)`` is numeric on the mainframe but is stored as
  ``VARCHAR(4)`` to **preserve leading zeros** (AAP 0.1.2 numeric-key rule),
  matching how the seed loaders and cross-references treat category codes.
* ``DIS-INT-RATE PIC S9(04)V99`` is signed zoned-decimal DISPLAY in the source
  (there is no ``COMP-3`` anywhere in the copybook). It maps to
  ``NUMERIC(6, 2)`` / :class:`decimal.Decimal` and **never** to a binary float,
  because floating-point rounding would corrupt regulated interest
  computations (AAP 0.7.1). **Precision is (6, 2), not (5, 2)** -- four integer
  plus two fractional digits give six significant digits (AAP 0.7.2 Finding
  #2). The Alembic initial migration and golden-master parity tests depend on
  this exact precision. Zoned-decimal *decoding* happens only in the seed
  loaders; the column type here is a plain ``NUMERIC(6, 2)``.
* ``FILLER PIC X(28)`` is intentionally dropped (AAP type-mapping rule); it is
  documented above solely for 50-byte record-length parity.
* This is a read-mostly reference table, so it declares no foreign keys or ORM
  relationships; consuming modules join to it by the composite key.
"""

from decimal import Decimal

from sqlalchemy import CHAR, Numeric, String
from sqlalchemy.orm import Mapped, mapped_column, synonym

from app.db.base import Base


class DisclosureGroup(Base):
    """Interest-rate disclosure group (legacy VSAM ``DISCGRP`` / ``CVTRA02Y``).

    A disclosure-group row is keyed by the ``(group_id, tran_type_cd,
    tran_cat_cd)`` triple and carries the monthly ``interest_rate`` consumed by
    the interest-calculation job. The class name follows the Ochs PascalCase
    rule for classes; the column attributes use snake_case to match the
    PostgreSQL schema and the sibling ``accounts.group_id`` column.
    """

    __tablename__ = "disclosure_group"

    # --- Composite primary key (VSAM DIS-GROUP-KEY, 16 bytes, RKP=0) ---------
    # DIS-ACCT-GROUP-ID PIC X(10). The real column name is ``group_id`` so that
    # it lines up with ``accounts.group_id`` for logical-relationship clarity;
    # the ``acct_group_id`` synonym below preserves the copybook/DTO field name.
    group_id: Mapped[str] = mapped_column(String(10), primary_key=True)

    # DIS-TRAN-TYPE-CD PIC X(02) -> fixed-width two-character type code.
    tran_type_cd: Mapped[str] = mapped_column(CHAR(2), primary_key=True)

    # DIS-TRAN-CAT-CD PIC 9(04) -> VARCHAR(4), preserving leading zeros
    # (numeric-key rule, AAP 0.1.2).
    tran_cat_cd: Mapped[str] = mapped_column(String(4), primary_key=True)

    # --- Attributes ----------------------------------------------------------
    # DIS-INT-RATE PIC S9(04)V99 -> NUMERIC(6, 2) / Decimal (NEVER float).
    # Precision is (6, 2) per AAP 0.7.2 Finding #2 (a deliberate correction of
    # the draft schema's (5, 2)); the Alembic migration and parity tests rely
    # on this exact precision.
    interest_rate: Mapped[Decimal] = mapped_column(Numeric(6, 2))

    # FILLER PIC X(28) from the copybook is intentionally omitted (record
    # padding only, documented in the module docstring for 50-byte parity).

    # ORM-only synonym exposing the primary key under its copybook/DTO name
    # ``acct_group_id`` so Pydantic schemas using ``from_attributes=True`` can
    # read it. ``synonym`` maps onto the existing ``group_id`` column and does
    # NOT emit a second DDL column, so Alembic autogenerate sees only
    # ``group_id`` (verified: no phantom ``acct_group_id`` column).
    acct_group_id = synonym("group_id")

    def __repr__(self) -> str:
        """Return a concise, unambiguous representation for logs and tests."""
        return (
            "DisclosureGroup("
            f"group_id={self.group_id!r}, "
            f"tran_type_cd={self.tran_type_cd!r}, "
            f"tran_cat_cd={self.tran_cat_cd!r}, "
            f"interest_rate={self.interest_rate!r})"
        )
