# Ported from COBOL copybook CVACT01Y (ACCOUNT-RECORD); VSAM ACCTDATA KSDS
# (KEYLEN=11, RKP=0). Business logic in COACTVWC/COACTUPC/CBACT01C.
#
# Reference-only sources (never modified): app/cpy/CVACT01Y.cpy (the 300-byte
# ACCOUNT-RECORD layout) and app/catlg/LISTCAT.txt (VSAM cluster
# AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS: KEYLEN=11, RKP=0, AVGLRECL=MAXLRECL=300,
# INDEXED, UNIQUE -- i.e. ACCT-ID is the sole 11-byte key at record position 0).
"""SQLAlchemy 2.0 ORM model for the CardDemo ``accounts`` table.

This module defines :class:`Account`, the modern relational port of the legacy
mainframe ``ACCOUNT-RECORD`` (COBOL copybook ``CVACT01Y``) that was stored in
the VSAM KSDS dataset ``ACCTDATA``. Each row is one credit-card account and is
read/updated by the online programs ``COACTVWC`` (view) and ``COACTUPC``
(update) and by the batch program ``CBACT01C`` (sequential print), whose logic
now lives in ``app.services.account_service`` and ``batch.jobs.print_account``.

Field-mapping rules (AAP section 0.1 type-mapping table, verified against
``app/cpy/CVACT01Y.cpy``):

* ``ACCT-ID PIC 9(11)`` -> ``acct_id VARCHAR(11)`` PRIMARY KEY. The identifier
  is modeled as text (not an integer) so leading zeros in the 11-digit account
  number are preserved exactly, matching the VSAM key semantics.
* ``ACCT-ACTIVE-STATUS PIC X(01)`` -> ``active_status CHAR(1)``.
* The five monetary fields ``PIC S9(10)V99`` -> ``NUMERIC(12, 2)`` mapped to
  Python :class:`decimal.Decimal`. Per AAP section 0.7.1 (Finding #1) these are
  signed zoned-decimal DISPLAY fields (there is NO ``COMP-3``/``PACKED-DECIMAL``
  clause anywhere in the copybook); ``float`` is therefore never used, because
  binary floating point would violate the regulatory numeric-parity requirement.
  Zoned-decimal decode/encode happens exclusively in the seed loaders
  (``batch/loaders``) via ``app.utils.decimal_utils`` -- never in this model.
* The three ``PIC X(10)`` date fields -> ``DATE`` and are nullable, because the
  legacy record leaves reissue/expiration/open dates blank (low-values/spaces)
  for accounts that have not reached those lifecycle events.
* ``ACCT-GROUP-ID PIC X(10)`` -> ``group_id VARCHAR(10)``, an indexed logical
  reference to ``disclosure_group.group_id`` (see the column comment below).
* ``FILLER PIC X(178)`` is intentionally dropped; it exists in the copybook only
  to pad the fixed record to its 300-byte length
  (11+1+12+12+12+10+10+10+12+12+10+10+178 = 300) and carries no data.

Concurrency note (AAP section 0.7.4, Minimal Change Clause): the legacy
``COACTUPC`` READ-for-UPDATE -> REWRITE optimistic check is reproduced in the
service layer with a database transaction using ``SELECT ... FOR UPDATE``. No
``version`` or ``updated_at`` optimistic-lock column is added to this table, so
the relational schema stays a faithful 1:1 image of the VSAM record.

The class extends the shared declarative :class:`app.db.base.Base`, so importing
this module registers the ``accounts`` table into ``Base.metadata`` for Alembic
autogenerate and for the golden-master parity tests, which rely on the exact
``NUMERIC(12, 2)`` precision/scale declared here.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import List, Optional

from sqlalchemy import CHAR, Date, Numeric, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base

__all__ = ["Account"]


class Account(Base):
    """Credit-card account entity (VSAM ``ACCTDATA`` KSDS -> ``accounts`` table).

    Ports the ``ACCOUNT-RECORD`` layout from copybook ``CVACT01Y``. Columns are
    declared in the exact order of the COBOL 05-level items for traceability;
    monetary amounts use exact :class:`decimal.Decimal` precision and are never
    floating point. The sole primary key is :attr:`acct_id`, mirroring the
    11-byte VSAM key at relative key position 0.
    """

    __tablename__ = "accounts"

    # ACCT-ID PIC 9(11) -> VARCHAR(11) PRIMARY KEY. Stored as text to preserve
    # leading zeros in the 11-digit account number (the VSAM KSDS primary key).
    acct_id: Mapped[str] = mapped_column(String(11), primary_key=True)

    # ACCT-ACTIVE-STATUS PIC X(01) -> CHAR(1) (e.g. 'Y'/'N' active flag).
    active_status: Mapped[str] = mapped_column(CHAR(1))

    # --- Monetary fields: ACCT-* PIC S9(10)V99 -> NUMERIC(12, 2) (Decimal). ---
    # Signed zoned-decimal DISPLAY in COBOL (AAP 0.7.1 Finding #1; NOT COMP-3).
    # Exact NUMERIC(12, 2) is required for regulatory parity -- never Float.
    # ACCT-CURR-BAL: current outstanding balance on the account.
    curr_bal: Mapped[Decimal] = mapped_column(Numeric(12, 2))
    # ACCT-CREDIT-LIMIT: total credit limit.
    credit_limit: Mapped[Decimal] = mapped_column(Numeric(12, 2))
    # ACCT-CASH-CREDIT-LIMIT: cash-advance sub-limit.
    cash_credit_limit: Mapped[Decimal] = mapped_column(Numeric(12, 2))

    # --- Lifecycle dates: ACCT-*-DATE PIC X(10) -> DATE (nullable). ---
    # ACCT-OPEN-DATE: account open date.
    open_date: Mapped[Optional[date]] = mapped_column(Date, nullable=True)
    # ACCT-EXPIRAION-DATE (sic) -> attribute deliberately spelled
    # `expiration_date`, correcting the copybook typo and matching the Pydantic
    # `account` schema and the `card` naming convention.
    expiration_date: Mapped[Optional[date]] = mapped_column(Date, nullable=True)
    # ACCT-REISSUE-DATE: date the card/account was last reissued.
    reissue_date: Mapped[Optional[date]] = mapped_column(Date, nullable=True)

    # --- Cycle amounts: ACCT-CURR-CYC-* PIC S9(10)V99 -> NUMERIC(12, 2). ---
    # Same signed zoned-decimal DISPLAY semantics as the balance fields above.
    # ACCT-CURR-CYC-CREDIT: current-cycle credits (used by the CBTRN02C
    # over-limit check WS-TEMP-BAL = cyc-credit - cyc-debit + tran-amt).
    curr_cyc_credit: Mapped[Decimal] = mapped_column(Numeric(12, 2))
    # ACCT-CURR-CYC-DEBIT: current-cycle debits.
    curr_cyc_debit: Mapped[Decimal] = mapped_column(Numeric(12, 2))

    # ACCT-ADDR-ZIP PIC X(10) -> VARCHAR(10) (account billing ZIP).
    addr_zip: Mapped[Optional[str]] = mapped_column(String(10), nullable=True)

    # ACCT-GROUP-ID PIC X(10) -> VARCHAR(10), indexed.
    # Logical ref to disclosure_group.group_id (composite PK there; not a hard FK).
    group_id: Mapped[Optional[str]] = mapped_column(String(10), index=True, nullable=True)

    # FILLER PIC X(178) is intentionally omitted (padding to RECLN=300 only).

    # Minimal Change Clause (AAP 0.7.4): NO `version`/`updated_at` optimistic-lock
    # column. COACTUPC concurrency is enforced in account_service via
    # SELECT ... FOR UPDATE, keeping this table a 1:1 image of the VSAM record.

    # --- Relationships (parent side; foreign keys live on the child tables). ---
    # The target classes are referenced purely as strings, so this module adds NO
    # Python import for them (per the model's design and the depends_on_files
    # contract, whose only internal dependency is app.db.base). SQLAlchemy resolves
    # "Card"/"CardXref" from its class registry when app/models/__init__.py imports
    # every model and configure_mappers() runs. A targeted F821 lint suppression is
    # applied on each relationship line because these forward-reference names are
    # never undefined at runtime -- they are registry lookups, not module globals.
    # Reciprocal of Card.account (FK cards.acct_id -> accounts.acct_id).
    cards: Mapped[List["Card"]] = relationship(back_populates="account")  # noqa: F821
    # Reciprocal of CardXref.account (FK card_xref.acct_id -> accounts.acct_id).
    xrefs: Mapped[List["CardXref"]] = relationship(back_populates="account")  # noqa: F821

    def __repr__(self) -> str:
        """Return an unambiguous developer representation keyed by account id."""
        return f"Account(acct_id={self.acct_id!r}, active_status={self.active_status!r})"
