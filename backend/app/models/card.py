# Ported from COBOL copybook CVACT02Y (CARD-RECORD); VSAM CARDDATA KSDS
# (KEYLEN=16, RKP=0; AIX on CARD-ACCT-ID, AXRKP=16). Business logic in
# COCRDLIC/COCRDSLC/COCRDUPC/CBACT02C.
"""SQLAlchemy 2.0 ORM model for the CardDemo ``cards`` table.

This module defines :class:`Card`, the ORM mapping for a single plastic-card
record. It is a direct, behavior-preserving port of the legacy COBOL copybook
``CVACT02Y`` (``CARD-RECORD``, fixed record length 150) that described the VSAM
``CARDDATA`` KSDS (AAP 0.5.1). The key structure is preserved verbatim:

* The VSAM primary key ``CARD-NUM`` (``KEYLEN=16``, ``RKP=0`` in
  ``app/catlg/LISTCAT.txt``) becomes the table primary key ``card_num``.
* The alternate index (AIX) on ``CARD-ACCT-ID`` (``AXRKP=16``) becomes a
  secondary index on ``acct_id`` together with a foreign key to
  ``accounts.acct_id``. That index is functionally required by the
  card-list-by-account screen ``COCRDLIC`` (transaction ``CCLI``), which
  enumerates the cards belonging to an account (at most seven rows per page).

Traceability (AAP 0.5.1, 0.8.1): the online programs ``COCRDLIC`` (list),
``COCRDSLC`` (view) and ``COCRDUPC`` (update), plus the batch reader
``CBACT02C``, all operate on this record layout.

Security (AAP 0.7.8; QA finding C-03): ``card_num`` is sensitive and, although
persisted here in full, MUST be masked (last four digits) by the schema layer
before it appears in any API response. The legacy card verification value
(``CARD-CVV-CD``) is DELIBERATELY NOT PERSISTED by this model: the AAP
prohibition on CVV retention is enforced structurally by the absence of any
``cvv``/``cvv_cd`` column, so there is nothing to leak, mask, or migrate away.
No read DTO exposes a CVV field either (see ``frontend/src/types/card.ts``).

Numeric note: this record carries no monetary or rate fields, so no
``Decimal`` columns are required here; CardDemo never uses floating point for
stored values (AAP 0.7.1).
"""

from __future__ import annotations

from datetime import date
from typing import List, Optional

from sqlalchemy import CHAR, Date, ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base


class Card(Base):
    """ORM model for a plastic card (``cards`` table).

    Ports COBOL ``CVACT02Y`` ``CARD-RECORD`` field-for-field, preserving the
    COBOL declaration order. The trailing ``FILLER PIC X(59)`` present in the
    150-byte VSAM record is intentionally dropped: it exists only to pad the
    fixed record length and carries no business meaning in a relational store.

    Columns are typed with SQLAlchemy 2.0 :class:`~sqlalchemy.orm.Mapped`
    annotations: a non-optional ``Mapped[str]`` renders ``NOT NULL`` while the
    ``Mapped[Optional[date]]`` on :attr:`expiration_date` renders a nullable
    ``DATE``. Constraint and index names are derived automatically from the
    shared ``NAMING_CONVENTION`` bound to ``Base.metadata`` (see
    ``app/db/base.py``); they are never hand-named here, which keeps Alembic
    autogenerate output stable and reproducible.
    """

    __tablename__ = "cards"

    # --- Columns (COBOL CVACT02Y order preserved) --------------------------

    # CARD-NUM PIC X(16) -> primary key. SENSITIVE: the full 16-digit card
    # number is stored, but the schema/response layer masks it to the last
    # four digits before returning it in any API response (AAP 0.7.8).
    card_num: Mapped[str] = mapped_column(String(16), primary_key=True)

    # CARD-ACCT-ID PIC 9(11) -> VARCHAR(11) to preserve leading zeros. Hard
    # foreign key to accounts.acct_id plus a secondary index. The index is the
    # relational equivalent of the VSAM alternate index (AXRKP=16) and powers
    # the card-list-by-account screen COCRDLIC (CCLI). The FK target is given
    # as a string so this module never imports the Account class.
    acct_id: Mapped[str] = mapped_column(
        String(11),
        ForeignKey("accounts.acct_id"),
        index=True,
    )

    # CARD-CVV-CD PIC 9(03): DELIBERATELY NOT MAPPED (QA finding C-03, AAP 0.7.8).
    # The card verification value must never be retained, so no column exists for
    # it. Record-length parity is unaffected: the 3-byte CVV slice is skipped by
    # the loaders and the trailing FILLER already absorbs record padding.

    # CARD-EMBOSSED-NAME PIC X(50) -> the name embossed on the card face.
    embossed_name: Mapped[str] = mapped_column(String(50))

    # CARD-EXPIRAION-DATE PIC X(10) -> DATE. The attribute is deliberately
    # named ``expiration_date`` to fix the legacy COBOL misspelling
    # ``EXPIRAION`` and to match the Pydantic schema field.
    expiration_date: Mapped[Optional[date]] = mapped_column(Date, nullable=True)

    # CARD-ACTIVE-STATUS PIC X(01) -> fixed-width CHAR(1) (e.g. 'Y'/'N').
    active_status: Mapped[str] = mapped_column(CHAR(1))

    # FILLER PIC X(59) is intentionally dropped. Record-length parity check
    # against the SOURCE VSAM layout (the 3-byte CVV slice still exists in the
    # fixed-width source record but is NOT persisted -- see the CVV note above):
    # 16 + 11 + 3 + 50 + 10 + 1 + 59 = 150 bytes (RECLN 150 in CVACT02Y).

    # --- Relationships -----------------------------------------------------
    # Sibling classes (Account, CardXref, Transaction) are referenced by string
    # name ONLY. SQLAlchemy resolves these names from the declarative registry
    # at mapper-configuration time (configure_mappers), so this module never
    # imports the sibling model classes -- keeping the model dependency graph
    # acyclic and honoring the file's single declared dependency (app.db.base).
    # A targeted linter suppression (F821) on each relationship line below
    # silences the static-analysis "undefined name" note for exactly these
    # deliberate forward references; the names are intentionally absent from
    # this module's namespace.

    # Many-to-one: each card belongs to exactly one account. Reciprocal of
    # ``Account.cards``; the join is inferred from the acct_id foreign key.
    account: Mapped["Account"] = relationship(back_populates="cards")  # noqa: F821

    # One-to-one: a card has at most one cross-reference row, whose primary/
    # foreign key ``xref_card_num`` references ``cards.card_num``. ``uselist``
    # is False so the attribute is a scalar (or None), not a collection.
    xref: Mapped[Optional["CardXref"]] = relationship(  # noqa: F821
        back_populates="card",
        uselist=False,
    )

    # One-to-many: a card accumulates many transactions. Reciprocal of
    # ``Transaction.card`` (FK ``transactions.card_num`` -> ``cards.card_num``).
    transactions: Mapped[List["Transaction"]] = relationship(  # noqa: F821
        back_populates="card",
    )

    def __repr__(self) -> str:
        """Return a concise, non-sensitive debug representation.

        The card number is masked to its last four digits and the CVV is never
        included, so log lines and REPL output cannot leak either value.
        """
        maskedCardNum = self._MaskCardNum(self.card_num)
        return (
            f"Card(card_num={maskedCardNum!r}, acct_id={self.acct_id!r}, "
            f"active_status={self.active_status!r})"
        )

    @staticmethod
    def _MaskCardNum(cardNum: Optional[str]) -> str:
        """Mask a card number to its last four digits for safe display.

        Args:
            cardNum: The full card number, or None when the value is not set.

        Returns:
            The card number with every digit except the last four replaced by
            '*'. Returns an empty string when ``cardNum`` is falsy, and returns
            the value unchanged when it is four characters or fewer (nothing to
            hide).
        """
        if not cardNum:
            return ""
        if len(cardNum) <= 4:
            return cardNum
        return ("*" * (len(cardNum) - 4)) + cardNum[-4:]


__all__ = ["Card"]
