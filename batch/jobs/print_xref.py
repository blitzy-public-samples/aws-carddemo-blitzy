# batch/jobs/print_xref.py
# Ported from legacy COBOL batch program CBACT03C.cbl (CardDemo). Function: read
# and print the card cross-reference file.
"""Read-and-print job for the ``card_xref`` cross-reference table.

This module is the modern Python port of the mainframe batch program
``app/cbl/CBACT03C.cbl``. The legacy program opened the ``XREFFILE`` VSAM KSDS
for sequential input, looped reading each ``CARD-XREF-RECORD`` (copybook
``CVACT03Y``), ``DISPLAY``-ed the record to SYSOUT, then closed the file and
returned. It is structurally identical to ``CBACT01C`` (account print).

In the modern three-tier stack the ``XREFFILE`` VSAM cluster is the PostgreSQL
``card_xref`` table (:class:`app.models.card_xref.CardXref`), so the sequential
KSDS scan becomes an ORM ``SELECT ... ORDER BY`` over the primary key and the
COBOL ``DISPLAY`` becomes labeled, human-readable output written to standard
output. The program's lifecycle banners (``START``/``END OF EXECUTION``) are
emitted through the standard library :mod:`logging` module.

Legacy behavior mapping (``CBACT03C`` PROCEDURE DIVISION -> this module):

* ``DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'`` -> ``LOGGER.info`` banner.
* ``OPEN INPUT`` + ``PERFORM UNTIL END-OF-FILE`` sequential ``READ`` -> a single
  ``select(CardXref).order_by(CardXref.xref_card_num)`` iterated via
  ``.scalars()`` (the KSDS is keyed on the 16-byte card number, so ordering by
  the primary key reproduces the sequential read order).
* ``DISPLAY CARD-XREF-RECORD`` -> :func:`_PrintXrefRecord`, one labeled block per
  row terminated by a dashed separator.
* ``CLOSE`` + ``DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'`` + ``GOBACK`` ->
  ``LOGGER.info`` banner and ``return`` of the processed record count.

Sensitive-data handling (AAP 0.7.8): unlike the legacy program, which displayed
the full 16-digit card number, this port MASKS the card number to its last four
digits (for example ``************1234``) via :func:`_MaskCardNumber`. The full
Primary Account Number (PAN) is never written to standard output and never
logged. The customer and account identifiers are not sensitive and are shown in
full.

Transaction ownership:
    This job is strictly READ-ONLY. :func:`PrintCardXref` operates on an
    already-open :class:`~sqlalchemy.orm.Session` handed in by the caller (the
    orchestration chain ``batch.orchestration.batch_chain`` or the CLI
    ``batch.cli``, which obtain the session from ``batch.db.GetSyncSession``). It
    never commits, rolls back, or closes the session, and it never issues DDL or
    creates schema; the caller owns the transaction boundary. Any database error
    raised while reading propagates unchanged to the caller, whose unit-of-work
    guard performs the rollback.

Public API:
    * :func:`PrintCardXref` -- print every cross-reference row (card number
      masked) in card-number order and return the number of rows printed.

Example:
    Run the print job inside a caller-owned unit of work::

        from batch.db import GetSyncSession
        from batch.jobs.print_xref import PrintCardXref

        with GetSyncSession() as session:
            printedCount = PrintCardXref(session)   # job does NOT commit
        # the read-only transaction is released here on clean exit
"""

from __future__ import annotations

import logging

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.card_xref import CardXref

__all__ = ["PrintCardXref"]


# Module logger. Configuration (handlers, level, formatting) is owned by the CLI
# / orchestration entrypoint, not by this job module -- consistent with the Ochs
# "no hardcoded configuration" rule.
LOGGER = logging.getLogger(__name__)


# Legacy program identifier, preserved verbatim for traceability. It is woven
# into the START/END banners so operational logs remain reconcilable with the
# mainframe job output.
PROGRAM_NAME = "CBACT03C"

# Lifecycle banners emitted at program start and end. These reproduce the two
# COBOL ``DISPLAY`` statements that bracket CBACT03C's PROCEDURE DIVISION.
START_OF_EXECUTION_MESSAGE = f"START OF EXECUTION OF PROGRAM {PROGRAM_NAME}"
END_OF_EXECUTION_MESSAGE = f"END OF EXECUTION OF PROGRAM {PROGRAM_NAME}"

# Card-number masking policy (AAP 0.7.8). Only the trailing ``VISIBLE_CARD_DIGITS``
# characters of the PAN are ever shown; every leading character is replaced with
# ``MASK_CHARACTER``.
VISIBLE_CARD_DIGITS = 4
MASK_CHARACTER = "*"

# Presentation constants for the labeled record block. Field labels are the
# original COBOL field names from copybook CVACT03Y; each is left-justified to
# ``LABEL_WIDTH`` so the value colons align into a single column.
LABEL_WIDTH = 24
CARD_NUM_LABEL = "XREF-CARD-NUM"
CUST_ID_LABEL = "XREF-CUST-ID"
ACCT_ID_LABEL = "XREF-ACCT-ID"

# Fixed-width dashed rule printed after each record to visually separate rows in
# the listing, matching the fixed-column feel of the original 3270/SYSOUT output.
SEPARATOR_CHARACTER = "-"
SEPARATOR_WIDTH = 49
RECORD_SEPARATOR = SEPARATOR_CHARACTER * SEPARATOR_WIDTH


def _MaskCardNumber(cardNumber: str) -> str:
    """Mask a card number, revealing only its last four digits.

    Implements the sensitive-data rule from AAP section 0.7.8: the Primary
    Account Number (PAN) must never be emitted in full. Every character except
    the trailing :data:`VISIBLE_CARD_DIGITS` is replaced with
    :data:`MASK_CHARACTER`, so a 16-character number renders as
    ``************1234``.

    Args:
        cardNumber: The raw card number read from the cross-reference row.
            Surrounding whitespace is ignored. An empty or ``None`` value yields
            an empty string.

    Returns:
        The masked card number. Values no longer than
        :data:`VISIBLE_CARD_DIGITS` are fully masked (no digits are revealed) to
        avoid disclosing a short or malformed value.
    """
    if not cardNumber:
        return ""
    strippedNumber = cardNumber.strip()
    if len(strippedNumber) <= VISIBLE_CARD_DIGITS:
        return MASK_CHARACTER * len(strippedNumber)
    maskedLength = len(strippedNumber) - VISIBLE_CARD_DIGITS
    visiblePortion = strippedNumber[-VISIBLE_CARD_DIGITS:]
    return f"{MASK_CHARACTER * maskedLength}{visiblePortion}"


def _PrintXrefRecord(xref: CardXref) -> None:
    """Print one cross-reference row as a labeled, PAN-safe block.

    Reproduces the COBOL ``DISPLAY CARD-XREF-RECORD`` for a single record,
    writing three labeled lines (card number, customer id, account id) followed
    by a dashed separator to standard output. The card number is masked; the
    customer and account identifiers are shown in full.

    Args:
        xref: The cross-reference ORM row to print. Only its
            :attr:`~app.models.card_xref.CardXref.xref_card_num`,
            :attr:`~app.models.card_xref.CardXref.cust_id` and
            :attr:`~app.models.card_xref.CardXref.acct_id` attributes are read.
    """
    maskedCardNumber = _MaskCardNumber(xref.xref_card_num)
    print(f"{CARD_NUM_LABEL:<{LABEL_WIDTH}}: {maskedCardNumber}")
    print(f"{CUST_ID_LABEL:<{LABEL_WIDTH}}: {xref.cust_id}")
    print(f"{ACCT_ID_LABEL:<{LABEL_WIDTH}}: {xref.acct_id}")
    print(RECORD_SEPARATOR)


def PrintCardXref(session: Session) -> int:
    """Read and print every card cross-reference row, in card-number order.

    Modern port of COBOL batch program ``CBACT03C``. Emits a start banner,
    selects all :class:`~app.models.card_xref.CardXref` rows ordered by the
    primary key (the card number, reproducing the legacy sequential KSDS read
    order), prints each row with its card number masked, emits an end banner, and
    returns the number of rows printed.

    The job is READ-ONLY and does not own the transaction: it neither commits,
    rolls back, nor closes the ``session``, and it issues no DDL. The caller (the
    orchestration chain or CLI, via ``batch.db.GetSyncSession``) owns the
    transaction boundary. Any :class:`~sqlalchemy.exc.SQLAlchemyError` raised
    during the read propagates unchanged to the caller for rollback.

    Args:
        session: An open, caller-owned synchronous SQLAlchemy
            :class:`~sqlalchemy.orm.Session`.

    Returns:
        The number of cross-reference rows read and printed.
    """
    LOGGER.info(START_OF_EXECUTION_MESSAGE)
    recordCount = 0
    xrefStatement = select(CardXref).order_by(CardXref.xref_card_num)
    for xrefRecord in session.execute(xrefStatement).scalars():
        _PrintXrefRecord(xrefRecord)
        recordCount += 1
    LOGGER.info(END_OF_EXECUTION_MESSAGE)
    return recordCount
