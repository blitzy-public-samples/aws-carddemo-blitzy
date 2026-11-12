# Ported from legacy COBOL batch program CBACT02C.cbl (CardDemo). Function: read and print the card master file.
"""Batch job that reads and prints the CardDemo card master file.

This module is a behavior-preserving Python port of the legacy COBOL batch
program ``app/cbl/CBACT02C.cbl`` (CardDemo), whose sole function is to read the
card master file sequentially and ``DISPLAY`` every record. In the mainframe
original the file was the ``CARDDATA`` VSAM KSDS, opened ``INPUT`` with
``ACCESS MODE SEQUENTIAL`` and ``RECORD KEY`` ``FD-CARD-NUM``; the program looped
``READ``-ing each ``CARD-RECORD`` (copybook ``CVACT02Y``) until end-of-file,
displaying it, then closed the file. That structure is reproduced here 1:1
against the relational ``cards`` table (AAP 0.5.2): the sequential KSDS scan in
primary-key order becomes ``select(Card).order_by(Card.card_num)``.

The job is intentionally structurally identical to ``print_account.py`` (the
port of ``CBACT01C``); both are read-only reporting readers over a single table.

Transaction-ownership contract:
    :func:`PrintCards` receives an already-open, caller-owned
    :class:`~sqlalchemy.orm.Session`. It is strictly read-only: it never calls
    ``commit``, ``rollback`` or ``close``, and it never issues DDL. The caller
    (the orchestration chain ``batch.orchestration.batch_chain`` or the CLI
    ``batch.cli``, via ``batch.db.GetSyncSession``) owns the unit of work. Any
    database error raised by SQLAlchemy propagates unchanged to that caller,
    which performs the rollback -- so no broad ``except`` is needed or wanted
    here (Ochs Rule: catch specific exceptions only).

Security (AAP 0.7.8): the card number is a sensitive PAN and the CVV is
sensitive. This module NEVER emits the full card number -- every occurrence is
masked to its last four digits by :func:`_MaskCardNumber` -- and it NEVER emits
``cvv_cd`` in any form.

Public API:
    * :func:`PrintCards` -- read every card in primary-key order, print each
      (masked) record, and return the number of records printed.
"""

from __future__ import annotations

import logging

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.card import Card


# Module logger. The legacy program's ``DISPLAY`` boundary markers (start/end of
# execution) are emitted through the logger, while the per-record data lines are
# written to standard output via ``print`` -- the faithful equivalent of the
# COBOL ``DISPLAY CARD-RECORD`` writing each record to SYSOUT.
LOGGER = logging.getLogger(__name__)

# --- Output formatting constants (Ochs Rule: no magic values) ----------------
# Every data line is rendered as a left-justified field label padded to a fixed
# width, followed by a colon, a single space, and the value -- so the colons of
# consecutive lines align in the output exactly as the legacy report did.
_LABEL_WIDTH = 24

# Fixed field labels for one card record. The COBOL misspelling "EXPIRAION"
# (from copybook CVACT02Y / the batch report) is preserved verbatim for
# output parity; it is NOT corrected here.
_CARD_NUM_LABEL = "CARD-NUM".ljust(_LABEL_WIDTH) + ":"
_ACCT_ID_LABEL = "CARD-ACCT-ID".ljust(_LABEL_WIDTH) + ":"
_EMBOSSED_NAME_LABEL = "CARD-EMBOSSED-NAME".ljust(_LABEL_WIDTH) + ":"
_EXPIRATION_DATE_LABEL = "CARD-EXPIRAION-DATE".ljust(_LABEL_WIDTH) + ":"
_ACTIVE_STATUS_LABEL = "CARD-ACTIVE-STATUS".ljust(_LABEL_WIDTH) + ":"

# Visual record separator printed after each card, matching the legacy report's
# fixed-width dashed rule.
_SEPARATOR_WIDTH = 49
_SEPARATOR = "-" * _SEPARATOR_WIDTH

# Card-number masking: reveal only the trailing digits, mask the rest.
_VISIBLE_DIGITS = 4
_MASK_CHAR = "*"

# Streaming batch size for the sequential scan. Mirrors the legacy record-at-a-
# time KSDS read while keeping memory bounded for large card files.
_YIELD_PER = 500


def _MaskCardNumber(cardNumber: str) -> str:
    """Mask a card number to its last four digits for safe display.

    Args:
        cardNumber: The full card number (PAN). May be empty when unset.

    Returns:
        The card number with every digit except the last four replaced by the
        mask character (for example ``************1234``). An empty string is
        returned for a falsy input, and inputs of four characters or fewer are
        returned unchanged (there is nothing to hide).
    """
    if not cardNumber:
        return ""
    if len(cardNumber) <= _VISIBLE_DIGITS:
        return cardNumber
    maskedLength = len(cardNumber) - _VISIBLE_DIGITS
    return (_MASK_CHAR * maskedLength) + cardNumber[-_VISIBLE_DIGITS:]


def _PrintCardRecord(card: Card) -> None:
    """Print one card record as labeled lines, masking the PAN.

    Reproduces the legacy ``DISPLAY CARD-RECORD`` output field-for-field, with
    two mandatory security adjustments (AAP 0.7.8): the card number is masked to
    its last four digits, and the sensitive ``cvv_cd`` is never printed.

    Args:
        card: The :class:`~app.models.card.Card` ORM row to print.
    """
    print(f"{_CARD_NUM_LABEL} {_MaskCardNumber(card.card_num)}")
    print(f"{_ACCT_ID_LABEL} {card.acct_id}")
    print(f"{_EMBOSSED_NAME_LABEL} {card.embossed_name}")
    print(f"{_EXPIRATION_DATE_LABEL} {card.expiration_date}")
    print(f"{_ACTIVE_STATUS_LABEL} {card.active_status}")
    print(_SEPARATOR)


def PrintCards(session: Session) -> int:
    """Read and print every card record in primary-key order.

    Port of COBOL ``CBACT02C``: emit the start-of-execution marker, scan the
    ``cards`` table sequentially ordered by the primary key ``card_num`` (the
    relational equivalent of the VSAM KSDS sequential read), print each record
    with its card number masked, then emit the end-of-execution marker.

    The rows are streamed with ``yield_per`` so a large card file is processed in
    bounded memory, mirroring the legacy record-at-a-time read.

    Args:
        session: An already-open, caller-owned synchronous SQLAlchemy session.
            This function is read-only and MUST NOT commit, roll back or close
            it; the caller owns the transaction boundary.

    Returns:
        The number of card records printed.
    """
    LOGGER.info("START OF EXECUTION OF PROGRAM CBACT02C")
    recordCount = 0
    selectStatement = select(Card).order_by(Card.card_num)
    cardRows = session.execute(selectStatement).scalars().yield_per(_YIELD_PER)
    for card in cardRows:
        _PrintCardRecord(card)
        recordCount += 1
    LOGGER.info("END OF EXECUTION OF PROGRAM CBACT02C")
    return recordCount


__all__ = ["PrintCards"]
