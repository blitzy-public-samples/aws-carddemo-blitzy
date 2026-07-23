# Ported from COBOL copybooks CVTRA05Y (TRAN-RECORD) + CVTRA06Y
# (DALYTRAN-RECORD); VSAM TRANSACT KSDS (KEYLEN=16, RKP=0). Posting logic
# CBTRN02C; browse COTRN00C/COTRN01C/COTRN02C.
#
# CVTRA05Y (TRAN-RECORD, RECLN=350) backs the posted-transaction ledger stored in
# the VSAM TRANSACT KSDS. CVTRA06Y (DALYTRAN-RECORD) is its byte-identical daily
# staging variant (same 350-byte layout, DALYTRAN- field prefix). Both layouts
# are unified into the single ``transactions`` table and distinguished at runtime
# by the ``status`` column (AAP 0.7.5): daily rows load as PENDING and are
# promoted to POSTED by the batch posting job (CBTRN02C). This avoids a separate
# daily-staging table while faithfully modelling the daily-staging semantics.
"""SQLAlchemy 2.0 ORM model for the CardDemo ``transactions`` table.

This module ports the COBOL ``TRAN-RECORD`` copybook (``CVTRA05Y``) and its
byte-identical daily variant ``DALYTRAN-RECORD`` (``CVTRA06Y``) into a single
:class:`Transaction` ORM entity mapped to the ``transactions`` table.

Fidelity notes (see AAP 0.7):

* ``tran_amt`` originates from ``PIC S9(09)V99`` -- a signed *zoned-decimal*
  DISPLAY field, NOT ``COMP-3`` packed decimal (AAP 0.7.1, Finding #1). It maps
  to ``NUMERIC(11, 2)`` and is exposed as a Python :class:`~decimal.Decimal`.
  Floating point is never used; it would violate the monetary-rounding
  correctness relied upon by the posting and interest-calculation parity tests.
* The 26-byte timestamp text fields ``TRAN-ORIG-TS`` / ``TRAN-PROC-TS`` map to
  timezone-aware ``TIMESTAMPTZ`` columns (``DateTime(timezone=True)``).
* The VSAM primary KSDS key (``TRAN-ID``, LISTCAT KEYLEN=16, RKP=0) becomes the
  primary key ``tran_id``. The cross-reference to the owning card
  (``TRAN-CARD-NUM``) becomes a hard foreign key to ``cards.card_num`` plus a
  secondary index that backs the list-by-card browse screen (COTRN00C / CT00).

The trailing ``FILLER PIC X(20)`` present in both copybooks is intentionally
dropped: it only padded the record to its 350-byte length and carries no data.
"""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Optional

from sqlalchemy import CHAR, DateTime, ForeignKey, Numeric, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base

# Staging-lifecycle values for :attr:`Transaction.status` (Ochs ALL_UPPERCASE
# constants). POSTED rows are the CVTRA05Y ledger; PENDING rows are the CVTRA06Y
# daily transactions awaiting posting by CBTRN02C. REJECTED is a terminal status
# for a daily row that CBTRN02C failed to post (a data reject, reason 100-103 or
# a 109 update failure): like POSTED it is terminal, so a rejected row is never
# re-attempted when the posting job is re-run, which keeps the batch chain's
# posting layer idempotent (AAP 0.7.6; QA finding F-6). REJECTED is 8 characters
# and fits the String(10) status column.
STATUS_PENDING = "PENDING"
STATUS_POSTED = "POSTED"
STATUS_REJECTED = "REJECTED"


class Transaction(Base):
    """A posted or pending financial transaction (``transactions`` table).

    Ports COBOL ``CVTRA05Y`` (``TRAN-RECORD``) unified with the byte-identical
    daily variant ``CVTRA06Y`` (``DALYTRAN-RECORD``). One table serves both the
    posted ledger and the daily staging rows; the :attr:`status` column
    distinguishes them (``PENDING`` -> ``POSTED``).

    The column order mirrors the COBOL record layout for traceability, and each
    column documents its originating copybook field.
    """

    __tablename__ = "transactions"

    # TRAN-ID PIC X(16) -> primary KSDS key (LISTCAT KEYLEN=16, RKP=0).
    tran_id: Mapped[str] = mapped_column(String(16), primary_key=True)

    # TRAN-TYPE-CD PIC X(02) -> fixed-width 2-character transaction type code.
    tran_type_cd: Mapped[str] = mapped_column(CHAR(2))

    # TRAN-CAT-CD PIC 9(04) -> VARCHAR(4) to preserve numeric leading zeros.
    tran_cat_cd: Mapped[str] = mapped_column(String(4))

    tran_source: Mapped[Optional[str]] = mapped_column(String(10), nullable=True)  # TRAN-SOURCE X(10)
    tran_desc: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)  # TRAN-DESC X(100)

    # TRAN-AMT PIC S9(09)V99 -> NUMERIC(11, 2) as Python Decimal (AAP 0.7.1:
    # signed zoned-decimal DISPLAY, never COMP-3, never float).
    tran_amt: Mapped[Decimal] = mapped_column(Numeric(11, 2))

    merchant_id: Mapped[Optional[str]] = mapped_column(String(9), nullable=True)  # TRAN-MERCHANT-ID 9(09)
    merchant_name: Mapped[Optional[str]] = mapped_column(String(50), nullable=True)  # TRAN-MERCHANT-NAME X(50)
    merchant_city: Mapped[Optional[str]] = mapped_column(String(50), nullable=True)  # TRAN-MERCHANT-CITY X(50)
    merchant_zip: Mapped[Optional[str]] = mapped_column(String(10), nullable=True)  # TRAN-MERCHANT-ZIP X(10)

    # TRAN-CARD-NUM PIC X(16) -> hard FK to cards.card_num plus a secondary index
    # backing the list-by-card browse (COTRN00C / CT00).
    card_num: Mapped[str] = mapped_column(String(16), ForeignKey("cards.card_num"), index=True)

    # TRAN-ORIG-TS PIC X(26) -> TIMESTAMPTZ (timezone-aware).
    orig_ts: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)

    # TRAN-PROC-TS PIC X(26) -> TIMESTAMPTZ (timezone-aware). NOTE: the legacy
    # VSAM alternate index (LISTCAT AXRKP=304, KEYLEN=26) was defined on this
    # field, but the modern schema indexes card_num instead (per AAP 0.7.4), so
    # no index is created on proc_ts here.
    proc_ts: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)

    # Staging status (CVTRA06Y daily rows = PENDING until posted by CBTRN02C).
    # Defaults to POSTED because the table is fundamentally the CVTRA05Y posted
    # ledger; loaders and the posting job set PENDING explicitly for unposted
    # daily rows.
    status: Mapped[str] = mapped_column(
        String(10),
        nullable=False,
        default=STATUS_POSTED,
        server_default=STATUS_POSTED,
    )

    # FILLER PIC X(20) at the tail of both copybooks is dropped: it only padded
    # the record to 350 bytes and holds no business data.

    # Reciprocal of Card.transactions (many transactions -> one card). The "Card"
    # target is a string forward reference that SQLAlchemy resolves through its
    # class registry at mapper-configuration time -- no import of app.models.card
    # is created here, keeping this module's dependencies limited to app.db.base.
    # The trailing per-line suppression silences the linter's F821 undefined-name
    # check for that deliberate forward reference.
    card: Mapped["Card"] = relationship(back_populates="transactions")  # noqa: F821


__all__ = ["Transaction", "STATUS_PENDING", "STATUS_POSTED", "STATUS_REJECTED"]
