# Ported from legacy COBOL batch program CBACT04C.cbl + JCL app/jcl/INTCALC.jcl
# (CardDemo). Function: compute monthly interest per account (truncated), post
# interest transactions type 01 / category 05.
#
# Reference-only sources (never modified): app/cbl/CBACT04C.cbl (the interest
# calculator PROCEDURE DIVISION) and app/jcl/INTCALC.jcl (STEP15 EXEC
# PGM=CBACT04C,PARM='2022071800'). The legacy program reads the
# transaction-category-balance VSAM KSDS (TCATBALF) sequentially by account,
# control-breaks on the account id, looks up the disclosure-group interest rate
# (with a DEFAULT fallback), computes monthly interest with truncation, writes a
# system interest transaction to SYSTRAN, and rewrites the account balance while
# zeroing both cycle counters. This module reproduces that logic 1:1 against the
# modern PostgreSQL schema (AAP 0.5.2, 0.7.2, Minimal Change Clause 0.8.1).
"""Monthly interest-calculation batch job (port of COBOL ``CBACT04C``).

This module re-expresses the legacy CardDemo interest calculator as an
idempotent, synchronous Python job that operates over the modern SQLAlchemy ORM
instead of VSAM files. It preserves the legacy business rules exactly:

* **Verbatim interest formula** -- ``(TRAN-CAT-BAL * DIS-INT-RATE) / 1200`` with
  **truncation, not rounding** (the legacy ``COMPUTE`` has no ``ROUNDED``
  phrase). Truncation is delegated to
  :func:`app.utils.decimal_utils.TruncateToCents` (``ROUND_DOWN``). Every value
  flows through :class:`decimal.Decimal`; floating point is never used, which is
  a regulatory numeric-parity requirement (AAP 0.7.1, 0.7.2).
* **Control-break processing** -- the category-balance rows are streamed in the
  legacy VSAM key order (account, then transaction type, then category) and the
  running per-account interest is applied to the account (and both cycle
  counters zeroed) when the account id changes and again at end of input.
* **Disclosure-group lookup with DEFAULT fallback** -- a missing specific group
  retries under the reserved ``DEFAULT`` group; a still-missing rate is treated
  as zero and skipped, mirroring ``IF DIS-INT-RATE NOT = 0`` (AAP 0.7).
* **System interest transactions** -- one transaction per non-zero-rate
  category balance, id = 10-character run-date prefix + 6-digit run-global
  suffix (16 chars), type ``01``, category ``0005``, source ``System``.

Transaction-ownership contract (AAP 0.5.2): the caller supplies an already-open
synchronous :class:`~sqlalchemy.orm.Session` (typically from
``batch.db.GetSyncSession``) and owns the unit of work. :func:`CalculateInterest`
performs no ``commit`` or ``rollback`` and issues no DDL -- it only reads and
writes rows and ``flush``es to surface constraint errors early. It is therefore
re-runnable within the caller's transaction; the batch chain runs it once per
cycle so interest is not double-posted.

Naming follows the Ochs Rule: the public entry point and result type use
PascalCase (:func:`CalculateInterest`, :class:`InterestResult`); private helper
functions use a leading-underscore PascalCase name; local variables are
camelCase; module constants are ALL_UPPERCASE.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.tran_category_balance import TranCategoryBalance
from app.models.disclosure_group import DisclosureGroup
from app.models.account import Account
from app.models.card_xref import CardXref
from app.models.transaction import Transaction
from app.utils.decimal_utils import TruncateToCents

__all__ = ["CalculateInterest", "InterestResult"]

# Module logger. The two INFO banners reproduce the legacy program's
# 'START/END OF EXECUTION OF PROGRAM CBACT04C' DISPLAY statements.
LOGGER = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Module constants (ALL_UPPERCASE -- Ochs Rule; no magic values)
# ---------------------------------------------------------------------------

# MOVE '01' TO TRAN-TYPE-CD. Interest postings are transaction type 01. The
# transactions.tran_type_cd column is CHAR(2), so the stored value is "01".
INTEREST_TRAN_TYPE_CD = "01"

# MOVE '05' TO TRAN-CAT-CD. In the copybook TRAN-CAT-CD is PIC 9(04), so the
# legacy MOVE stores the 4-byte value "0005". The transactions.tran_cat_cd
# column is VARCHAR(4) and every other category in the seed data is stored
# zero-padded to four characters (e.g. "0001"); "0005" keeps this row consistent
# with the model column type and with golden-master parity against the legacy
# 4-byte field (AAP 0.1.2 numeric-key rule, verified against app/data/ASCII).
INTEREST_TRAN_CAT_CD = "0005"

# MOVE 'System' TO TRAN-SOURCE. Interest is a system-generated posting.
INTEREST_TRAN_SOURCE = "System"

# 'Int. for a/c ' -- the exact legacy STRING literal (note the trailing space);
# the account id is appended to build TRAN-DESC.
INTEREST_DESC_PREFIX = "Int. for a/c "

# MOVE 0 TO TRAN-MERCHANT-ID, whose copybook field is PIC 9(09); the legacy MOVE
# yields the 9-byte value "000000000". The transactions.merchant_id column is
# VARCHAR(9), so the value is stored as a 9-character string (never an int, which
# would not match the VARCHAR column type).
INTEREST_MERCHANT_ID = "000000000"

# MOVE SPACES TO TRAN-MERCHANT-NAME/CITY/ZIP. The seed loader strips fixed-width
# space padding to the empty string, so a spaces-filled field is stored as "".
EMPTY_MERCHANT_TEXT = ""

# Divisor from the legacy formula (TRAN-CAT-BAL * DIS-INT-RATE) / 1200. Declared
# as Decimal so the division is exact Decimal arithmetic -- never float division.
INTEREST_DIVISOR = Decimal("1200")

# Reserved disclosure-group id used as the fallback when an account's specific
# group has no row for a (type, category) pair (legacy 1200-A default lookup).
DEFAULT_GROUP_ID = "DEFAULT"


# ---------------------------------------------------------------------------
# Public result type
# ---------------------------------------------------------------------------


@dataclass
class InterestResult:
    """Summary of one interest-calculation run.

    Groups the run's return values into a single object (Ochs Rule: group extra
    values into one object rather than returning several). All monetary totals
    are exact :class:`~decimal.Decimal` values.

    Attributes:
        accountsProcessed: Number of accounts whose interest was applied (one per
            control-break group, including the final account at end of input).
        interestTransactionsWritten: Number of interest transactions inserted.
        totalInterest: Exact grand total of interest accrued across the run.
    """

    accountsProcessed: int = 0
    interestTransactionsWritten: int = 0
    totalInterest: Decimal = Decimal("0")


# ---------------------------------------------------------------------------
# Private run state (threads cross-cutting values through the small helpers)
# ---------------------------------------------------------------------------


@dataclass
class _InterestRunState:
    """Mutable state carried across the control-break helpers for one run.

    The running per-account interest accumulator and the run-global transaction
    -id suffix cross several helper calls. Rather than widen helper signatures
    past four parameters, they are grouped here (Ochs Rule).

    Attributes:
        result: The :class:`InterestResult` being populated (shared object).
        datePrefix: The 10-character run-date prefix for generated tran ids.
        tranIdSuffix: Run-global 6-digit suffix counter (``WS-TRANID-SUFFIX``);
            incremented for every interest transaction and never reset.
        totalInterest: Per-account interest accumulator (``WS-TOTAL-INT``); reset
            when a new account's control-break group begins.
        account: The account currently being processed, or ``None`` before the
            first group begins.
        cardNum: The current account's cross-reference card number
            (``XREF-CARD-NUM``), or ``None`` when no cross-reference exists.
    """

    result: InterestResult
    datePrefix: str
    tranIdSuffix: int = 0
    totalInterest: Decimal = Decimal("0")
    account: Account | None = None
    cardNum: str | None = None


# ---------------------------------------------------------------------------
# Private helpers (each small and single-purpose -- Ochs Rule)
# ---------------------------------------------------------------------------


def _ResolveDatePrefix(runDate: date | None) -> str:
    """Return the 10-character legacy tran-id date prefix.

    Reproduces the legacy ``PARM-DATE`` (``PIC X(10)`` = ``'2022071800'``): the
    eight-digit ``YYYYMMDD`` calendar date followed by the two-character ``'00'``
    suffix. This prefix forms the first 10 characters of every interest
    transaction id (see :func:`_BuildInterestTransaction`).

    Args:
        runDate: The interest run date, or ``None`` to use today's date.

    Returns:
        The 10-character date prefix, for example ``'2022071800'``.
    """
    effectiveDate = runDate if runDate is not None else date.today()
    return effectiveDate.strftime("%Y%m%d") + "00"


def _LookupRate(
    session: Session,
    account: Account,
    row: TranCategoryBalance,
) -> Decimal:
    """Resolve the disclosure-group interest rate for one category balance.

    Reproduces CBACT04C ``1200-GET-INTEREST-RATE`` and its
    ``1200-A-GET-DEFAULT-INT-RATE`` fallback. The rate is looked up by the
    ``(account group, transaction type, transaction category)`` key. When that
    specific group is missing (legacy VSAM status 23) the lookup retries under
    the reserved ``DEFAULT`` group. A still-missing rate yields ``Decimal('0')``,
    which the caller treats as "skip" (legacy ``IF DIS-INT-RATE NOT = 0``).

    Args:
        session: The caller-owned SQLAlchemy session.
        account: The account whose ``group_id`` selects the disclosure group.
        row: The category-balance row supplying the type and category codes.

    Returns:
        The monthly interest rate as an exact :class:`~decimal.Decimal`.
    """
    disc = session.get(
        DisclosureGroup,
        (account.group_id, row.tran_type_cd, row.tran_cat_cd),
    )
    if disc is None:
        disc = session.get(
            DisclosureGroup,
            (DEFAULT_GROUP_ID, row.tran_type_cd, row.tran_cat_cd),
        )
    if disc is None:
        return Decimal("0")
    return disc.interest_rate


def _ComputeFees() -> None:
    """Reproduce CBACT04C ``1400-COMPUTE-FEES``.

    The legacy paragraph body is the single comment ``* To be implemented`` and
    performs no work. This is retained as an intentional no-op for structural
    parity and traceability; fee logic must NOT be invented here.
    """
    # 1400-COMPUTE-FEES: 'To be implemented' in legacy CBACT04C -- intentional
    # no-op for parity.
    return None


def _BeginAccount(
    session: Session,
    state: _InterestRunState,
    row: TranCategoryBalance,
) -> None:
    """Enter a new control-break group: load its account and card cross-reference.

    Reproduces the per-account setup CBACT04C performs on a control break: it
    resets the per-account interest accumulator (``MOVE 0 TO WS-TOTAL-INT``),
    loads the account record (``1100-GET-ACCT-DATA``), and reads the card
    cross-reference to capture the card number (``1110-GET-XREF-DATA``,
    ``XREF-CARD-NUM``).

    Args:
        session: The caller-owned SQLAlchemy session.
        state: The mutable run state updated in place with the new account.
        row: The first category-balance row of the new account.

    Raises:
        LookupError: If no account exists for ``row.acct_id``. This mirrors the
            legacy ``1100-GET-ACCT-DATA`` abend on a missing account
            ('ACCOUNT NOT FOUND'); it never fires for well-formed seed data.
    """
    state.totalInterest = Decimal("0")  # MOVE 0 TO WS-TOTAL-INT
    account = session.get(Account, row.acct_id)  # 1100-GET-ACCT-DATA
    if account is None:
        raise LookupError(f"Account not found for interest calc: {row.acct_id}")
    state.account = account
    # 1110-GET-XREF-DATA: read the cross-reference by account id (VSAM AIX path)
    # and capture XREF-CARD-NUM. The prompt tolerates a missing cross-reference
    # by leaving the card number unset.
    xref = (
        session.execute(select(CardXref).where(CardXref.acct_id == row.acct_id))
        .scalars()
        .first()
    )
    state.cardNum = xref.xref_card_num if xref else None


def _BuildInterestTransaction(
    state: _InterestRunState,
    monthlyInterest: Decimal,
) -> Transaction:
    """Construct the interest ``Transaction`` ORM row (no persistence).

    Assembles the system interest posting exactly as CBACT04C's
    ``1300-B-WRITE-TX`` populated ``TRAN-RECORD``. This is a flat,
    one-line-per-field record map: it intentionally favours field-by-field
    visibility over the small-method guideline (matching the seed loaders'
    convention) so every column stays aligned with its legacy ``MOVE`` for
    traceability.

    The transaction id is the 10-character run-date prefix concatenated with the
    6-digit, zero-padded, run-global suffix, giving a 16-character id. Both
    timestamps are set to the current time (the legacy program moved the same
    ``DB2-FORMAT-TS`` into ``TRAN-ORIG-TS`` and ``TRAN-PROC-TS``).

    Args:
        state: The mutable run state supplying the suffix, date prefix, account,
            and card number.
        monthlyInterest: The truncated monthly interest amount for this row.

    Returns:
        A populated, not-yet-added
        :class:`~app.models.transaction.Transaction`.
    """
    tranId = f"{state.datePrefix}{state.tranIdSuffix:06d}"  # 10-char date + 6-digit suffix = 16
    postingTimestamp = datetime.now()  # both timestamps take the same value
    return Transaction(
        tran_id=tranId,
        tran_type_cd=INTEREST_TRAN_TYPE_CD,  # MOVE '01' TO TRAN-TYPE-CD
        tran_cat_cd=INTEREST_TRAN_CAT_CD,  # MOVE '05' TO TRAN-CAT-CD (stored "0005")
        tran_source=INTEREST_TRAN_SOURCE,  # MOVE 'System' TO TRAN-SOURCE
        tran_desc=INTEREST_DESC_PREFIX + str(state.account.acct_id),  # 'Int. for a/c ' || ACCT-ID
        tran_amt=monthlyInterest,  # MOVE WS-MONTHLY-INT TO TRAN-AMT
        merchant_id=INTEREST_MERCHANT_ID,  # MOVE 0 TO TRAN-MERCHANT-ID (9(09) -> "000000000")
        merchant_name=EMPTY_MERCHANT_TEXT,  # MOVE SPACES TO TRAN-MERCHANT-NAME
        merchant_city=EMPTY_MERCHANT_TEXT,  # MOVE SPACES TO TRAN-MERCHANT-CITY
        merchant_zip=EMPTY_MERCHANT_TEXT,  # MOVE SPACES TO TRAN-MERCHANT-ZIP
        card_num=state.cardNum,  # MOVE XREF-CARD-NUM TO TRAN-CARD-NUM
        orig_ts=postingTimestamp,  # MOVE DB2-FORMAT-TS TO TRAN-ORIG-TS
        proc_ts=postingTimestamp,  # MOVE DB2-FORMAT-TS TO TRAN-PROC-TS
        # status: left to the model default 'POSTED' (a posted/system
        # transaction). It is deliberately NOT set to PENDING, so the posting
        # job never re-picks this interest row.
    )


def _WriteInterestTransaction(
    session: Session,
    state: _InterestRunState,
    monthlyInterest: Decimal,
) -> None:
    """Persist one interest transaction and advance the run counters.

    Reproduces CBACT04C ``1300-B-WRITE-TX``: it increments the run-global
    six-digit transaction-id suffix (``ADD 1 TO WS-TRANID-SUFFIX``), builds the
    interest ``Transaction``, and writes it (``WRITE FD-TRANFILE-REC``). The
    caller owns the transaction boundary, so this flushes -- to surface any
    constraint error immediately -- but never commits.

    Args:
        session: The caller-owned SQLAlchemy session (never committed here).
        state: The mutable run state (supplies the suffix, date prefix, account,
            and card number; its result counters are updated in place).
        monthlyInterest: The truncated monthly interest amount for this row.
    """
    state.tranIdSuffix += 1  # ADD 1 TO WS-TRANID-SUFFIX (run-global; never reset per account)
    interestTran = _BuildInterestTransaction(state, monthlyInterest)
    session.add(interestTran)  # WRITE FD-TRANFILE-REC FROM TRAN-RECORD
    session.flush()  # surface constraint errors now; caller owns commit/rollback
    state.result.interestTransactionsWritten += 1
    state.result.totalInterest += monthlyInterest


def _ComputeInterest(
    session: Session,
    state: _InterestRunState,
    row: TranCategoryBalance,
    interestRate: Decimal,
) -> None:
    """Compute and post one category's monthly interest (CBACT04C ``1300``).

    Reproduces ``1300-COMPUTE-INTEREST`` VERBATIM, including its truncation
    semantics. The legacy ``COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL *
    DIS-INT-RATE) / 1200`` carries NO ``ROUNDED`` phrase, so the product is
    truncated toward zero to two decimals by
    :func:`~app.utils.decimal_utils.TruncateToCents` (``ROUND_DOWN``) -- never
    rounded and never floating point. The amount is added to the per-account
    accumulator (``WS-TOTAL-INT``) and an interest transaction is written even
    when the truncated amount is ``0.00`` (the legacy guard is on the rate, not
    on the computed interest).

    Args:
        session: The caller-owned SQLAlchemy session.
        state: The mutable run state (per-account accumulator and counters).
        row: The category-balance row supplying ``balance`` (``TRAN-CAT-BAL``).
        interestRate: The resolved disclosure-group rate (``DIS-INT-RATE``).
    """
    # FORMULA VERBATIM (CBACT04C.cbl L464-465), truncated to cents (ROUND_DOWN):
    monthlyInterest = TruncateToCents((row.balance * interestRate) / INTEREST_DIVISOR)
    state.totalInterest += monthlyInterest  # ADD WS-MONTHLY-INT TO WS-TOTAL-INT
    _WriteInterestTransaction(session, state, monthlyInterest)  # PERFORM 1300-B-WRITE-TX


def _UpdateAccount(
    session: Session,
    account: Account,
    totalInterest: Decimal,
) -> None:
    """Apply accrued interest to an account and zero its cycle counters (``1050``).

    Reproduces CBACT04C ``1050-UPDATE-ACCOUNT``: it adds the account's
    accumulated interest to the current balance (``ADD WS-TOTAL-INT TO
    ACCT-CURR-BAL``) and zeroes both current-cycle counters (``MOVE 0 TO
    ACCT-CURR-CYC-CREDIT`` / ``ACCT-CURR-CYC-DEBIT``). All arithmetic is exact
    :class:`~decimal.Decimal`. The caller owns the transaction, so this flushes
    (the legacy ``REWRITE``) but never commits.

    Args:
        session: The caller-owned SQLAlchemy session (never committed here).
        account: The account whose control-break group has just ended.
        totalInterest: The interest accumulated for that account this run.
    """
    account.curr_bal = account.curr_bal + totalInterest  # ADD WS-TOTAL-INT TO ACCT-CURR-BAL
    account.curr_cyc_credit = Decimal("0")  # MOVE 0 TO ACCT-CURR-CYC-CREDIT
    account.curr_cyc_debit = Decimal("0")  # MOVE 0 TO ACCT-CURR-CYC-DEBIT
    session.flush()  # REWRITE FD-ACCTFILE-REC (caller owns commit/rollback)


# ---------------------------------------------------------------------------
# Public entry point
# ---------------------------------------------------------------------------


def CalculateInterest(session: Session, runDate: date | None = None) -> InterestResult:
    """Compute monthly interest for every account (port of COBOL ``CBACT04C``).

    Streams the transaction-category-balance table ordered by account, then by
    transaction type and category code -- the exact VSAM ``TCATBALF`` key order
    the legacy program relied on -- and control-breaks on the account id. For
    each category balance it resolves the disclosure-group interest rate (with a
    ``DEFAULT`` fallback), computes the truncated monthly interest, writes a
    system interest transaction, and accumulates the per-account total. When an
    account's group ends (a control break, and again at end of input) the
    accumulated interest is applied to the account and both cycle counters are
    zeroed.

    The caller owns the unit of work: an already-open synchronous
    :class:`~sqlalchemy.orm.Session` is supplied (typically from
    ``batch.db.GetSyncSession``) and this function performs no ``commit`` or
    ``rollback`` and issues no DDL. It is re-runnable within the caller's
    transaction; because a re-run would post interest a second time, the batch
    chain runs it exactly once per cycle (its transaction boundary and chain
    ordering guarantee run-level idempotency).

    Args:
        session: An open, caller-owned SQLAlchemy session (never committed here).
        runDate: The interest run date whose ``YYYYMMDD`` value (plus the legacy
            ``'00'`` suffix) prefixes every generated transaction id. Defaults to
            ``None``, which uses today's date.

    Returns:
        An :class:`InterestResult` summarising the accounts processed, interest
        transactions written, and the exact total interest accrued this run.

    Raises:
        LookupError: If a category balance references an account that does not
            exist (mirrors the legacy ``1100-GET-ACCT-DATA`` abend).
    """
    LOGGER.info("START OF EXECUTION OF PROGRAM CBACT04C")
    result = InterestResult()
    state = _InterestRunState(result=result, datePrefix=_ResolveDatePrefix(runDate))

    # Read every category balance in the legacy VSAM key order: account id, then
    # transaction type, then transaction category. This groups the rows by
    # account so the control break below sees each account's rows contiguously.
    statement = select(TranCategoryBalance).order_by(
        TranCategoryBalance.acct_id,
        TranCategoryBalance.tran_type_cd,
        TranCategoryBalance.tran_cat_cd,
    )
    rows = session.execute(statement).scalars().all()

    previousAcctId: str | None = None
    for row in rows:
        if row.acct_id != previousAcctId:
            if previousAcctId is not None:
                # Control break: finalise the PREVIOUS account (1050-UPDATE-ACCOUNT).
                _UpdateAccount(session, state.account, state.totalInterest)
                result.accountsProcessed += 1
            _BeginAccount(session, state, row)
            previousAcctId = row.acct_id
        interestRate = _LookupRate(session, state.account, row)
        if interestRate != 0:
            _ComputeInterest(session, state, row, interestRate)
            _ComputeFees()

    if previousAcctId is not None:
        # End of input: finalise the LAST account so its group is not lost.
        _UpdateAccount(session, state.account, state.totalInterest)
        result.accountsProcessed += 1

    LOGGER.info("END OF EXECUTION OF PROGRAM CBACT04C")
    return result
