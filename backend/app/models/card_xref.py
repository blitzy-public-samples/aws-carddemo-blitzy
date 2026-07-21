# backend/app/models/card_xref.py
# =============================================================================
# Ported from COBOL copybook CVACT03Y (CARD-XREF-RECORD); VSAM CARDXREF KSDS
# (KEYLEN=16, RKP=0; AIX on XREF-ACCT-ID, AXRKP=25). Modeled as a PHYSICAL table.
# =============================================================================
# Legacy source artifacts (REFERENCE only -- never modified):
#   - app/cpy/CVACT03Y.cpy   : 01 CARD-XREF-RECORD, RECLN 50 (field layout).
#   - app/catlg/LISTCAT.txt  : VSAM cluster/AIX key positions and lengths.
#
# The CARDXREF file is the card <-> customer <-> account cross-reference. In the
# modern schema its VSAM referential relationships are made explicit as three
# PostgreSQL FOREIGN KEY constraints (AAP 0.7.4). The primary KSDS key becomes
# the PRIMARY KEY; the non-unique alternate index (NONUNIQKEY, AXRKP=25) on
# XREF-ACCT-ID becomes a secondary, non-unique INDEX. A matching non-unique
# INDEX is placed on the customer foreign key.
#
# Original COBOL record layout (CVACT03Y), field order preserved:
#   01  CARD-XREF-RECORD.
#       05  XREF-CARD-NUM  PIC X(16).  -> xref_card_num (PK + FK -> cards.card_num)
#       05  XREF-CUST-ID   PIC 9(09).  -> cust_id       (FK -> customers.cust_id + INDEX)
#       05  XREF-ACCT-ID   PIC 9(11).  -> acct_id       (FK -> accounts.acct_id  + INDEX)
#       05  FILLER         PIC X(14).  -> DROPPED (record padding to the 50-byte RECLN)
"""SQLAlchemy 2.0 ORM model for the ``card_xref`` cross-reference table.

This module defines :class:`CardXref`, the modern port of the legacy VSAM
``CARDXREF`` KSDS described by COBOL copybook ``CVACT03Y`` (``CARD-XREF-RECORD``,
record length 50). The cross-reference ties a card number to its owning customer
and account; the modernized schema realizes those VSAM relationships as explicit
PostgreSQL foreign keys, satisfying the referential-integrity requirement in AAP
section 0.7.4.

Key mapping decisions (verified against ``app/cpy/CVACT03Y.cpy`` and
``app/catlg/LISTCAT.txt``):

* ``XREF-CARD-NUM PIC X(16)`` is the KSDS primary key (``KEYLEN=16``, ``RKP=0``)
  and becomes the sole PRIMARY KEY column, additionally constrained as a FOREIGN
  KEY to ``cards.card_num``. The real DDL column name is ``xref_card_num``; an
  ORM-only :func:`~sqlalchemy.orm.synonym` named ``card_num`` exposes the field
  name used by the Pydantic schema layer (AAP 0.5.1) without adding any physical
  column.
* ``XREF-CUST-ID PIC 9(09)`` maps to ``VARCHAR(9)`` -- character, not integer, to
  preserve leading zeros -- with a FOREIGN KEY to ``customers.cust_id`` and a
  non-unique secondary INDEX.
* ``XREF-ACCT-ID PIC 9(11)`` maps to ``VARCHAR(11)`` with a FOREIGN KEY to
  ``accounts.acct_id`` and a non-unique secondary INDEX, mirroring the VSAM
  alternate index (``AXRKP=25``, ``NONUNIQKEY``).
* The trailing ``FILLER PIC X(14)`` is intentionally dropped (documented above
  only for 50-byte record-length parity).

The model extends the shared declarative :class:`~app.db.base.Base`, so it is
registered into the single ``Base.metadata`` consumed by Alembic autogenerate.
Constraint and index names follow the shared naming convention, yielding the
deterministic identifiers ``pk_card_xref``, ``fk_card_xref_xref_card_num_cards``,
``fk_card_xref_cust_id_customers``, ``fk_card_xref_acct_id_accounts``,
``ix_card_xref_cust_id`` and ``ix_card_xref_acct_id``.
"""

from __future__ import annotations

from sqlalchemy import ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column, relationship, synonym

from app.db.base import Base


class CardXref(Base):
    """Card-to-customer-and-account cross-reference (legacy VSAM ``CARDXREF``).

    Each row links exactly one card (:attr:`xref_card_num`) to the customer
    (:attr:`cust_id`) and account (:attr:`acct_id`) that own it. The class is a
    faithful, physical-table port of the ``CARD-XREF-RECORD`` copybook, with the
    VSAM relationships expressed as three hard foreign keys.

    Attributes:
        xref_card_num: 16-character card number. This is the primary key (the
            legacy KSDS key) and simultaneously a foreign key to
            ``cards.card_num``. Exposed to the schema layer under the synonym
            :attr:`card_num`.
        cust_id: 9-character customer identifier; foreign key to
            ``customers.cust_id`` with a non-unique index.
        acct_id: 11-character account identifier; foreign key to
            ``accounts.acct_id`` with a non-unique index (the VSAM alternate
            index on ``XREF-ACCT-ID``).
        card_num: ORM-only synonym for :attr:`xref_card_num`; adds no physical
            column and lets Pydantic ``from_attributes`` read ``orm.card_num``.
        card: Related :class:`~app.models.card.Card` (one-to-one).
        customer: Related :class:`~app.models.customer.Customer` (many-to-one).
        account: Related :class:`~app.models.account.Account` (many-to-one).
    """

    __tablename__ = "card_xref"

    # XREF-CARD-NUM PIC X(16): primary KSDS key (KEYLEN=16, RKP=0). The real
    # column name is ``xref_card_num`` (honors copybook + folder requirement);
    # it is BOTH the primary key AND a foreign key to ``cards.card_num``.
    xref_card_num: Mapped[str] = mapped_column(
        String(16),
        ForeignKey("cards.card_num"),
        primary_key=True,
    )

    # XREF-CUST-ID PIC 9(09) -> VARCHAR(9) (character, to preserve leading
    # zeros). Hard foreign key to customers plus a non-unique secondary index.
    cust_id: Mapped[str] = mapped_column(
        String(9),
        ForeignKey("customers.cust_id"),
        index=True,
    )

    # XREF-ACCT-ID PIC 9(11) -> VARCHAR(11). Hard foreign key to accounts plus a
    # non-unique secondary index, mirroring the VSAM alternate index (AIX,
    # AXRKP=25, NONUNIQKEY).
    acct_id: Mapped[str] = mapped_column(
        String(11),
        ForeignKey("accounts.acct_id"),
        index=True,
    )

    # NOTE: FILLER PIC X(14) from CVACT03Y is intentionally dropped; it existed
    # only to pad the legacy record to its 50-byte RECLN and carries no data.

    # ORM-only synonym exposing the schema/DTO field name ``card_num`` for the
    # real column ``xref_card_num``. A synonym is purely a mapper construct: it
    # creates NO phantom DDL column, so Alembic autogenerate sees only
    # ``xref_card_num``, ``cust_id`` and ``acct_id``.
    card_num = synonym("xref_card_num")

    # Relationships to the three parent tables. Target classes are given as
    # string forward references and resolved lazily from the shared declarative
    # registry once every model module is imported. Each relationship traverses
    # a distinct foreign-key column, so the join is unambiguous and no explicit
    # ``foreign_keys=`` argument is required. The trailing F821 suppressions
    # acknowledge that "Card", "Customer" and "Account" are registry-resolved
    # forward references defined in sibling modules -- deliberately not imported
    # here (this module depends only on app.db.base) -- not names bound locally.
    card: Mapped["Card"] = relationship(back_populates="xref")  # noqa: F821
    customer: Mapped["Customer"] = relationship(back_populates="xrefs")  # noqa: F821
    account: Mapped["Account"] = relationship(back_populates="xrefs")  # noqa: F821

    def __repr__(self) -> str:
        """Return an unambiguous, PAN-safe debug representation.

        The card number is sensitive data (a PAN); only its last four
        characters are shown, consistent with the card-number masking rule in
        AAP section 0.7.8. The full card number and the CVV are never emitted.

        Returns:
            A string of the form
            ``CardXref(card_num='****1234', cust_id='000000001', acct_id=...)``.
        """
        maskedCardNum = "****"
        if self.xref_card_num is not None and len(self.xref_card_num) >= 4:
            maskedCardNum = f"****{self.xref_card_num[-4:]}"
        return f"CardXref(card_num={maskedCardNum!r}, cust_id={self.cust_id!r}, acct_id={self.acct_id!r})"
