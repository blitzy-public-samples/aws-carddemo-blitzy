# Ported from legacy COBOL batch program CBTRN01C.cbl (CardDemo). Function: read
# and validate the daily transaction input (diagnostic pass, no writes).
#
# Legacy source artifact (REFERENCE only -- never modified):
#   - app/cbl/CBTRN01C.cbl : batch reader that opens DALYTRAN (SEQUENTIAL) plus
#     CUSTFILE/XREFFILE/CARDFILE/ACCTFILE/TRANFILE (INDEXED, RANDOM) and, for each
#     daily transaction, displays the record, looks up the card cross-reference
#     (2000-LOOKUP-XREF) and -- when found -- the owning account
#     (3000-READ-ACCOUNT), logging diagnostics on any lookup miss. It changes no
#     data.
"""Daily-transaction reader / validator (port of COBOL ``CBTRN01C``).

This module reimplements the CardDemo batch program ``CBTRN01C`` as a single
public entry point, :func:`ReadDailyTransactions`. It is a **diagnostic pass**:
it reads every pending daily transaction, resolves each one against the card
cross-reference and the owning account, and logs the same diagnostics the legacy
program displayed -- but it **writes nothing** and never mutates the database or
the session.

Data-model note (AAP section 0.7.5):
    There is no separate ``daily_transactions`` table. Daily (unposted)
    transactions live in the same ``transactions`` table as the posted ledger and
    are distinguished by the
    :attr:`~app.models.transaction.Transaction.status` column. "The
    daily-transaction input" therefore means the ``Transaction`` rows whose
    ``status`` equals :data:`~app.models.transaction.STATUS_PENDING`. This
    mirrors the legacy ``DALYTRAN`` sequential file, which held exactly the
    unposted daily records.

Legacy paragraph mapping (traceability, Minimal Change Clause):
    * ``MAIN-PARA`` main loop -> :func:`ReadDailyTransactions`
    * ``2000-LOOKUP-XREF``    -> :func:`_LookupXref`
    * ``3000-READ-ACCOUNT``   -> :func:`_LookupAccount`
    * per-record diagnostics  -> :func:`_ProcessDailyTransaction`

Transaction-ownership contract (AAP 0.8.1; matches ``batch.db.GetSyncSession``):
    The caller owns the transaction boundary and passes an already-open
    :class:`~sqlalchemy.orm.Session`. This function performs read-only queries
    only; it must not -- and does not -- ``commit``, ``rollback``, ``close`` or
    otherwise mutate the session. That makes the job inherently idempotent and
    freely re-runnable.

Sensitive-data rule (AAP 0.7.8):
    Card numbers are masked to their last four digits in every log line via
    :func:`_MaskCardNumber`; the CVV is never logged (and is not even present on
    the ``Transaction`` model).
"""

from __future__ import annotations

import logging
from typing import Optional

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import STATUS_PENDING
from app.models.account import Account
from app.models.card_xref import CardXref
from app.models.transaction import Transaction

# Module logger. Mirrors the legacy program's DISPLAY diagnostics; configuration
# (handlers, level, formatting) is owned by the CLI / orchestration entry point,
# never by this job module.
LOGGER = logging.getLogger(__name__)

# Number of trailing card-number digits retained when masking a PAN for logs
# (AAP 0.7.8 -- only the last four digits may be shown). ALL_UPPERCASE per the
# Ochs constant-naming rule.
VISIBLE_CARD_DIGITS = 4

# Masking prefix substituted for the redacted portion of a card number.
CARD_MASK_PREFIX = "****"

__all__ = ["ReadDailyTransactions"]


def _MaskCardNumber(cardNumber: Optional[str]) -> str:
    """Return a PAN-safe rendering of ``cardNumber`` (last four digits only).

    Args:
        cardNumber: The raw card number, or ``None``. Any value shorter than the
            visible-digit window is treated as fully sensitive and redacted.

    Returns:
        The masking prefix followed by at most the last
        :data:`VISIBLE_CARD_DIGITS` characters, e.g. ``"****1234"``. When the
        input is ``None`` or too short to safely reveal a suffix, the bare
        :data:`CARD_MASK_PREFIX` (``"****"``) is returned.
    """
    if cardNumber is None:
        return CARD_MASK_PREFIX
    cardText = str(cardNumber)
    if len(cardText) < VISIBLE_CARD_DIGITS:
        return CARD_MASK_PREFIX
    return f"{CARD_MASK_PREFIX}{cardText[-VISIBLE_CARD_DIGITS:]}"


def _LookupXref(session: Session, cardNumber: str) -> Optional[CardXref]:
    """Resolve the card cross-reference for a card (COBOL ``2000-LOOKUP-XREF``).

    Reproduces the legacy paragraph's read-and-display behavior: on a miss it
    logs ``INVALID CARD NUMBER FOR XREF`` and returns ``None``; on a hit it logs
    the resolved card / account / customer identifiers (card number masked) and
    returns the cross-reference row. Performs no writes.

    Args:
        session: An open, caller-owned session used only for the read.
        cardNumber: The 16-character card number keying the cross-reference.

    Returns:
        The matching :class:`~app.models.card_xref.CardXref`, or ``None`` when no
        cross-reference row exists for the card.
    """
    xref = session.get(CardXref, cardNumber)
    if xref is None:
        LOGGER.info("INVALID CARD NUMBER FOR XREF")
        return None
    LOGGER.info("SUCCESSFUL READ OF XREF")
    LOGGER.info("CARD NUMBER: %s", _MaskCardNumber(cardNumber))
    LOGGER.info("ACCOUNT ID : %s", xref.acct_id)
    LOGGER.info("CUSTOMER ID: %s", xref.cust_id)
    return xref


def _LookupAccount(session: Session, accountId: str) -> Optional[Account]:
    """Resolve the account for an id (COBOL ``3000-READ-ACCOUNT``).

    Reproduces the legacy paragraph: on a miss it logs
    ``INVALID ACCOUNT NUMBER FOUND`` and returns ``None``; on a hit it logs
    ``SUCCESSFUL READ OF ACCOUNT FILE`` and returns the account. Read-only.

    Args:
        session: An open, caller-owned session used only for the read.
        accountId: The 11-character account identifier to read.

    Returns:
        The matching :class:`~app.models.account.Account`, or ``None`` when the
        account does not exist.
    """
    account = session.get(Account, accountId)
    if account is None:
        LOGGER.info("INVALID ACCOUNT NUMBER FOUND")
        return None
    LOGGER.info("SUCCESSFUL READ OF ACCOUNT FILE")
    return account


def _ProcessDailyTransaction(session: Session, tran: Transaction) -> None:
    """Validate a single daily transaction (COBOL ``MAIN-PARA`` loop body).

    Logs the daily record (card number masked), then verifies it against the
    cross-reference and the owning account, emitting the legacy diagnostics for
    each miss. Mirrors the mainframe control flow exactly: an unresolved card is
    skipped (the account read is not attempted), while a missing account is
    reported but does not stop the run. Performs no writes.

    Args:
        session: An open, caller-owned session used only for reads.
        tran: The pending :class:`~app.models.transaction.Transaction` to check.
    """
    maskedCardNum = _MaskCardNumber(tran.card_num)
    LOGGER.info(
        "DALYTRAN-RECORD id=%s type=%s cat=%s amt=%s card=%s orig-ts=%s",
        tran.tran_id,
        tran.tran_type_cd,
        tran.tran_cat_cd,
        tran.tran_amt,
        maskedCardNum,
        tran.orig_ts,
    )
    xref = _LookupXref(session, tran.card_num)
    if xref is None:
        LOGGER.info(
            "CARD NUMBER %s COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-%s",
            maskedCardNum,
            tran.tran_id,
        )
        return
    account = _LookupAccount(session, xref.acct_id)
    if account is None:
        LOGGER.info("ACCOUNT %s NOT FOUND", xref.acct_id)


def ReadDailyTransactions(session: Session) -> int:
    """Read and validate the daily-transaction input (port of COBOL ``CBTRN01C``).

    Iterates every pending daily transaction (``Transaction.status ==
    STATUS_PENDING``) in deterministic ``tran_id`` order and, for each, resolves
    the card cross-reference and owning account, logging the same diagnostics the
    legacy program displayed. This is a pure diagnostic pass: it **writes
    nothing**, and it neither commits, rolls back nor closes the session -- the
    caller owns the transaction boundary (see ``batch.db.GetSyncSession``), which
    keeps the job idempotent and re-runnable.

    Args:
        session: An already-open, caller-owned
            :class:`~sqlalchemy.orm.Session`. Used exclusively for read-only
            queries.

    Returns:
        The number of daily (pending) transactions read. Every pending row is
        counted, including those skipped because their card or account could not
        be verified -- matching the legacy record-read semantics.
    """
    LOGGER.info("START OF EXECUTION OF PROGRAM CBTRN01C")
    statement = (
        select(Transaction)
        .where(Transaction.status == STATUS_PENDING)
        .order_by(Transaction.tran_id)
    )
    recordCount = 0
    for tran in session.execute(statement).scalars():
        recordCount += 1
        _ProcessDailyTransaction(session, tran)
    LOGGER.info("END OF EXECUTION OF PROGRAM CBTRN01C")
    return recordCount
