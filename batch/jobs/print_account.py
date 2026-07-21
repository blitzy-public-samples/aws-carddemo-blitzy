# Ported from legacy COBOL batch program CBACT01C.cbl (CardDemo). Function: read and print the account master file.
#
# Traceability (AAP section 0.8.1, Minimal Change Clause): this module is the 1:1
# Python port of the batch COBOL program ``CBACT01C`` ("Read and print account
# data file"). The legacy program opened the ACCTFILE VSAM KSDS with SEQUENTIAL
# access (RECORD KEY = FD-ACCT-ID), looped reading each record, ``DISPLAY``ed
# every field with a fixed label in paragraph ``1100-DISPLAY-ACCT-RECORD``, then
# closed the file. VSAM KSDS sequential access returns records in ascending
# primary-key order, reproduced here with ``ORDER BY acct_id``.
"""Batch job: read and print the CardDemo account master (port of ``CBACT01C``).

This module exposes a single public entry point, :func:`PrintAccounts`, invoked by
``batch.cli`` / ``batch.orchestration``. It reads every row of the ``accounts``
table (the relational replacement for the legacy ``ACCTDATA`` VSAM KSDS) in
ascending account-id order and logs each record's fields using the exact labels
emitted by the legacy ``1100-DISPLAY-ACCT-RECORD`` paragraph. The start/end banner
text is preserved verbatim for golden-master log parity, the legacy
``ACCT-EXPIRAION-DATE`` label misspelling is retained in the label text, and
``ACCT-ADDR-ZIP`` is intentionally not printed because ``CBACT01C`` never
displays it.

The job is strictly read-only: the caller (``batch.db.GetSyncSession``) owns the
SQLAlchemy :class:`~sqlalchemy.orm.Session` and its transaction lifecycle, so this
module never commits, closes, or otherwise mutates the session. Monetary fields
are :class:`decimal.Decimal` values (``NUMERIC(12, 2)``) and are logged as-is;
they are never coerced to floating point, honoring the regulatory numeric-parity
requirement (AAP section 0.7.1).
"""

from __future__ import annotations

import logging

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.account import Account

# Module-level logger. ``CBACT01C`` used COBOL ``DISPLAY`` for all output; the
# modern port routes the equivalent lines through the standard logging framework
# so the batch runner controls formatting and destinations.
LOGGER = logging.getLogger(__name__)

# Record separator emitted after each account, mirroring the legacy
# ``DISPLAY '-------------------------------------------------'`` line in
# ``1100-DISPLAY-ACCT-RECORD`` (exactly 49 dashes).
SEPARATOR_LINE = "-" * 49


def _PrintAccountRecord(account: Account) -> None:
    """Log one account using the verbatim ``1100-DISPLAY-ACCT-RECORD`` labels."""
    fieldLines = (
        ("ACCT-ID                 :", account.acct_id),
        ("ACCT-ACTIVE-STATUS      :", account.active_status),
        ("ACCT-CURR-BAL           :", account.curr_bal),
        ("ACCT-CREDIT-LIMIT       :", account.credit_limit),
        ("ACCT-CASH-CREDIT-LIMIT  :", account.cash_credit_limit),
        ("ACCT-OPEN-DATE          :", account.open_date),
        ("ACCT-EXPIRAION-DATE     :", account.expiration_date),
        ("ACCT-REISSUE-DATE       :", account.reissue_date),
        ("ACCT-CURR-CYC-CREDIT    :", account.curr_cyc_credit),
        ("ACCT-CURR-CYC-DEBIT     :", account.curr_cyc_debit),
        ("ACCT-GROUP-ID           :", account.group_id),
    )
    for fieldLabel, fieldValue in fieldLines:
        LOGGER.info("%s%s", fieldLabel, fieldValue)
    LOGGER.info(SEPARATOR_LINE)


def PrintAccounts(session: Session) -> int:
    """Read and print every account, returning the number of records printed.

    Ports the ``CBACT01C`` PROCEDURE DIVISION. Records are selected in ascending
    ``acct_id`` order to reproduce VSAM KSDS sequential access, streamed in
    batches, and printed via :func:`_PrintAccountRecord`. The start/end banner
    text matches the legacy ``DISPLAY`` statements exactly for log parity.

    Args:
        session: An already-open SQLAlchemy session owned by the caller. This
            function performs only reads; it never commits or closes the session.

    Returns:
        The count of account records printed.
    """
    LOGGER.info("START OF EXECUTION OF PROGRAM CBACT01C")
    recordCount = 0
    statement = select(Account).order_by(Account.acct_id)
    result = session.execute(statement)
    for account in result.scalars().yield_per(500):
        _PrintAccountRecord(account)
        recordCount += 1
    LOGGER.info("END OF EXECUTION OF PROGRAM CBACT01C")
    return recordCount
