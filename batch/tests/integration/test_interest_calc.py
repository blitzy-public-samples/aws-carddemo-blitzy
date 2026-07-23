# Golden-master integration tests for the monthly interest-calculation job
# (batch/jobs/interest_calc.py), reconciled 1:1 against the legacy COBOL program
# app/cbl/CBACT04C.cbl (driven by app/jcl/INTCALC.jcl). REFERENCE-only sources
# (never modified): CBACT04C.cbl and the copybooks CVTRA01Y (TRAN-CAT-BAL),
# CVTRA02Y (DIS-INT-RATE) and CVTRA05Y (TRAN-RECORD). AAP 0.5.2 / 0.7.2 / 0.8.1.
"""DB-backed parity tests for ``batch.jobs.interest_calc.CalculateInterest``.

Each test reconciles one behavioral contract of the ported interest calculator
against the legacy ``CBACT04C`` PROCEDURE DIVISION, and every test carries an
inline comment citing the exact ``app/cbl/CBACT04C.cbl`` paragraph / line range
it pins.

The single most important parity property is **truncation, not rounding**: the
legacy ``COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200``
(CBACT04C L462-465) has no ``ROUNDED`` phrase, so the modern port truncates the
product toward zero to two decimals. ``test_interest_truncates_round_down``
proves this with a value whose exact quotient (``1.665833...``) would round up to
``1.67`` but must truncate down to ``1.66``.

These tests are deliberately SYNCHRONOUS -- the batch layer never uses the
FastAPI async engine, an async driver, the async pytest plugin, an ASGI HTTP
client, the standard-library event loop, or SQLAlchemy's async ORM extension.
They consume the rolled-back synchronous ``db_session`` (root
``batch/tests/conftest.py``) and the ``record_builder`` fixture (local
``batch/tests/integration/conftest.py``); the shared session starts EMPTY, so
after a run ``select(Transaction)`` returns exactly the interest transactions.

Every monetary assertion uses an exact :class:`decimal.Decimal` literal;
binary floating point and tolerance-based equality are never used, because
binary rounding would violate the regulatory numeric-parity requirement
(AAP 0.7.1).

Naming follows the Ochs Rule with the documented pytest exception: test function
names are snake_case (pytest discovers them by name), the private scenario helper
is PascalCase, local variables are camelCase, and module constants are
ALL_UPPERCASE.
"""

from contextlib import contextmanager
from datetime import date
from decimal import Decimal

import pytest  # noqa: F401  (conventional pytest test-module import per the file spec)
from sqlalchemy import delete, select
from sqlalchemy.exc import OperationalError

from app.models import (
    Account,
    AccountGroup,
    Card,
    CardXref,
    Customer,
    DisclosureGroup,
    STATUS_POSTED,
    TranCategoryBalance,
    Transaction,
)
from batch import db as batch_db
from batch.jobs.interest_calc import (
    CalculateInterest,
    INTEREST_TRAN_CAT_CD,
    INTEREST_TRAN_TYPE_CD,
)

# --------------------------------------------------------------------------- #
# Module constants (Ochs ALL_UPPERCASE). Identifier constants are text so their
# leading zeros are preserved exactly (AAP 0.1.2 numeric-key rule); every money
# value is an exact Decimal, never a float.
# --------------------------------------------------------------------------- #
# Legacy interest divisor from CBACT04C L465 ((...) / 1200); declared Decimal so
# the discriminating quotient in the truncation test is exact Decimal division.
INTEREST_DIVISOR = Decimal("1200")

# Reserved fallback disclosure-group id used by interest_calc when an account's
# specific group has no matching (type, category) row (CBACT04C 1200-A default).
DEFAULT_GROUP_ID = "DEFAULT"

# Account identifiers (ACCT-ID PIC 9(11) -> 11-char text).
ACCT_ONE = "00000000001"
ACCT_TWO = "00000000002"

# Card numbers (CARD-NUM PIC X(16) -> 16-char text).
CARD_ONE = "0000000000000001"
CARD_TWO = "0000000000000002"

# Customer identifiers (CUST-ID PIC 9(09) -> 9-char text).
CUST_ONE = "000000001"
CUST_TWO = "000000002"

# The staged category-balance key parts read by the job (TCATBALF input):
# TRANCAT-TYPE-CD PIC X(02) and TRANCAT-CD PIC 9(04) (4-char, zero-padded).
INPUT_TYPE_CD = "01"
INPUT_CAT_CD = "0001"

# 'System' -- the exact legacy MOVE 'System' TO TRAN-SOURCE literal (CBACT04C
# L482); interest is a system-generated posting.
INTEREST_SOURCE = "System"

# Fixed run date for the idempotency tests, so BOTH runs share the same 10-char
# tran-id date prefix and the second run's ledger probe finds the first run's
# interest transaction (QA finding M-12). A concrete date keeps the tran ids and
# assertions deterministic regardless of the wall clock.
RUN_DATE = date(2023, 6, 15)


# --------------------------------------------------------------------------- #
# Scenario helper (Ochs PascalCase, module-level). Groups the eight per-scenario
# values into a single ``spec`` dict so the signature stays within the Ochs
# four-parameter maximum (here three parameters).
# --------------------------------------------------------------------------- #
def _BuildInterestScenario(recordBuilder, acctId, spec):
    """Stage one complete, FK-valid interest scenario for a single account.

    Builds the customer -> account -> card -> cross-reference chain, exactly one
    ``TranCategoryBalance`` row (so the account's total interest equals that one
    category's interest, sidestepping any per-category vs per-account ambiguity),
    and a ``DisclosureGroup`` rate row whose ``group_id`` matches the account's
    ``group_id``. All rows are inserted into the shared rolled-back session.

    The builder methods take positional key values (``BuildCard``/``BuildXref``/
    ``BuildTranCategoryBalance``/``BuildDisclosureGroup``) and ``overrides`` dicts
    (``BuildCustomer``/``BuildAccount``) exactly as declared in the integration
    ``conftest.RecordBuilder``.

    Args:
        recordBuilder: The ``RecordBuilder`` bound to the test ``db_session``.
        acctId: The account id to stage (``ACCT_ONE`` / ``ACCT_TWO``).
        spec: A mapping with keys ``groupId``, ``cardNum``, ``custId``,
            ``currBal``, ``cycCredit``, ``cycDebit``, ``balance`` and ``rate``.

    Returns:
        The staged :class:`~app.models.account.Account` ORM instance.
    """
    recordBuilder.BuildCustomer(overrides={"cust_id": spec["custId"]})
    account = recordBuilder.BuildAccount(overrides={
        "acct_id": acctId,
        "group_id": spec["groupId"],
        "curr_bal": spec["currBal"],
        "curr_cyc_credit": spec["cycCredit"],
        "curr_cyc_debit": spec["cycDebit"],
    })
    recordBuilder.BuildCard(spec["cardNum"], acctId)
    recordBuilder.BuildXref(spec["cardNum"], spec["custId"], acctId)
    recordBuilder.BuildTranCategoryBalance(
        acctId, INPUT_TYPE_CD, INPUT_CAT_CD, spec["balance"],
    )
    recordBuilder.BuildDisclosureGroup(
        spec["groupId"], INPUT_TYPE_CD, INPUT_CAT_CD, spec["rate"],
    )
    return account


def test_interest_truncates_round_down(db_session, record_builder):
    """Interest is truncated toward zero, never rounded to nearest."""
    # Reconciles CBACT04C 1300-COMPUTE-INTEREST (app/cbl/CBACT04C.cbl L462-465):
    # COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200 carries NO
    # ROUNDED phrase, so the legacy program truncates toward zero to two cents.
    spec = {
        "groupId": "TESTGRP",
        "cardNum": CARD_ONE,
        "custId": CUST_ONE,
        "currBal": Decimal("200.00"),
        "cycCredit": Decimal("0.00"),
        "cycDebit": Decimal("0.00"),
        "balance": Decimal("100.00"),
        "rate": Decimal("19.99"),
    }
    _BuildInterestScenario(record_builder, ACCT_ONE, spec)

    result = CalculateInterest(db_session)

    # Exact quotient (100.00 * 19.99) / 1200 = 1.665833...; nearest-cent rounding
    # (ties up) would yield 1.67, but the legacy truncation must yield 1.66. The
    # raw Decimal quotient strictly exceeds the truncated total, proving the job
    # dropped -- rather than rounded -- the sub-cent remainder.
    rawQuotient = (spec["balance"] * spec["rate"]) / INTEREST_DIVISOR
    assert rawQuotient > result.totalInterest
    assert result.totalInterest == Decimal("1.66")
    assert result.interestTransactionsWritten == 1
    assert result.accountsProcessed == 1

    db_session.expire_all()
    interestTran = db_session.execute(select(Transaction)).scalars().one()
    assert interestTran.tran_amt == Decimal("1.66")


def test_interest_transaction_classification(db_session, record_builder):
    """The interest posting is type 01, category 05, source 'System'."""
    # Reconciles CBACT04C 1300-B-WRITE-TX (app/cbl/CBACT04C.cbl L473-490): MOVE
    # '01' TO TRAN-TYPE-CD, MOVE '05' TO TRAN-CAT-CD, MOVE 'System' TO
    # TRAN-SOURCE, and STRING 'Int. for a/c ' ACCT-ID INTO TRAN-DESC (AAP 0.7.2).
    spec = {
        "groupId": "TESTGRP",
        "cardNum": CARD_ONE,
        "custId": CUST_ONE,
        "currBal": Decimal("200.00"),
        "cycCredit": Decimal("0.00"),
        "cycDebit": Decimal("0.00"),
        "balance": Decimal("100.00"),
        "rate": Decimal("12.00"),
    }
    _BuildInterestScenario(record_builder, ACCT_ONE, spec)

    CalculateInterest(db_session)

    db_session.expire_all()
    interestTran = db_session.execute(select(Transaction)).scalars().one()
    assert interestTran.tran_type_cd == "01"
    # Normalize the category padding ("05" vs "0005") to the numeric golden value.
    assert int(interestTran.tran_cat_cd) == 5
    assert interestTran.tran_source == INTEREST_SOURCE
    assert interestTran.tran_desc.strip() == f"Int. for a/c {ACCT_ONE}"
    # (100.00 * 12.00) / 1200 = 1.00 exactly.
    assert interestTran.tran_amt == Decimal("1.00")
    # 10-character run-date prefix + 6-digit run-global suffix = 16 characters.
    assert len(interestTran.tran_id) == 16


def test_interest_updates_account_and_resets_cycles(db_session, record_builder):
    """Accrued interest is added to the balance and both cycle counters zero."""
    # Reconciles CBACT04C 1050-UPDATE-ACCOUNT (app/cbl/CBACT04C.cbl L350-370):
    # ADD WS-TOTAL-INT TO ACCT-CURR-BAL, then MOVE 0 TO ACCT-CURR-CYC-CREDIT and
    # MOVE 0 TO ACCT-CURR-CYC-DEBIT before the REWRITE.
    spec = {
        "groupId": "TESTGRP",
        "cardNum": CARD_ONE,
        "custId": CUST_ONE,
        "currBal": Decimal("200.00"),
        "cycCredit": Decimal("50.00"),
        "cycDebit": Decimal("30.00"),
        "balance": Decimal("100.00"),
        "rate": Decimal("12.00"),
    }
    _BuildInterestScenario(record_builder, ACCT_ONE, spec)

    CalculateInterest(db_session)

    db_session.expire_all()
    account = db_session.get(Account, ACCT_ONE)
    # 200.00 current balance + 1.00 interest = 201.00.
    assert account.curr_bal == Decimal("201.00")
    assert account.curr_cyc_credit == Decimal("0.00")
    assert account.curr_cyc_debit == Decimal("0.00")


def test_interest_default_group_fallback(db_session, record_builder):
    """A missing specific group falls back to the reserved DEFAULT group rate."""
    # Reconciles CBACT04C 1200-A-GET-DEFAULT-INT-RATE (app/cbl/CBACT04C.cbl
    # L443-459): when the account's disclosure group is missing (DISCGRP-STATUS
    # '23'), MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID and re-read the rate. Built
    # inline (not via the helper) because the account group and the disclosure
    # group intentionally differ.
    record_builder.BuildCustomer(overrides={"cust_id": CUST_ONE})
    record_builder.BuildAccount(overrides={
        "acct_id": ACCT_ONE,
        "group_id": "MISSINGGRP",
        "curr_bal": Decimal("200.00"),
        "curr_cyc_credit": Decimal("0.00"),
        "curr_cyc_debit": Decimal("0.00"),
    })
    record_builder.BuildCard(CARD_ONE, ACCT_ONE)
    record_builder.BuildXref(CARD_ONE, CUST_ONE, ACCT_ONE)
    record_builder.BuildTranCategoryBalance(
        ACCT_ONE, INPUT_TYPE_CD, INPUT_CAT_CD, Decimal("100.00"),
    )
    # Disclosure exists ONLY for the DEFAULT group; there is none for MISSINGGRP.
    record_builder.BuildDisclosureGroup(
        DEFAULT_GROUP_ID, INPUT_TYPE_CD, INPUT_CAT_CD, Decimal("12.00"),
    )

    result = CalculateInterest(db_session)

    # The MISSINGGRP lookup misses and the DEFAULT-group rate (12.00) is applied:
    # (100.00 * 12.00) / 1200 = 1.00, proving the fallback path was taken.
    assert result.interestTransactionsWritten == 1
    assert result.totalInterest == Decimal("1.00")


def test_interest_zero_rate_skipped(db_session, record_builder):
    """A zero disclosure rate contributes no interest and writes no posting."""
    # Reconciles the CBACT04C main-loop guard (app/cbl/CBACT04C.cbl L214):
    # IF DIS-INT-RATE NOT = 0 gates 1300-COMPUTE-INTEREST, so a zero-rate
    # category is skipped -- no interest accrues and no transaction is written.
    spec = {
        "groupId": "ZEROGRP",
        "cardNum": CARD_ONE,
        "custId": CUST_ONE,
        "currBal": Decimal("200.00"),
        "cycCredit": Decimal("0.00"),
        "cycDebit": Decimal("0.00"),
        "balance": Decimal("100.00"),
        "rate": Decimal("0.00"),
    }
    _BuildInterestScenario(record_builder, ACCT_ONE, spec)

    result = CalculateInterest(db_session)

    assert result.interestTransactionsWritten == 0
    assert result.totalInterest == Decimal("0")
    assert db_session.execute(select(Transaction)).scalars().all() == []

    db_session.expire_all()
    # The balance is unchanged because zero interest was added.
    assert db_session.get(Account, ACCT_ONE).curr_bal == Decimal("200.00")


def test_interest_control_break_processes_all_accounts(db_session, record_builder):
    """Every account is finalised: the broken-on account and the final EOF one."""
    # Reconciles the CBACT04C main control-break loop (app/cbl/CBACT04C.cbl
    # L188-221): the previous account is finalised on a control break (L194 IF
    # TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM -> 1050-UPDATE-ACCOUNT) and the LAST
    # account is finalised again at end of input (L219-220), so no group is lost.
    _BuildInterestScenario(record_builder, ACCT_ONE, {
        "groupId": "GRP1",
        "cardNum": CARD_ONE,
        "custId": CUST_ONE,
        "currBal": Decimal("200.00"),
        "cycCredit": Decimal("0.00"),
        "cycDebit": Decimal("0.00"),
        "balance": Decimal("100.00"),
        "rate": Decimal("12.00"),
    })
    _BuildInterestScenario(record_builder, ACCT_TWO, {
        "groupId": "GRP2",
        "cardNum": CARD_TWO,
        "custId": CUST_TWO,
        "currBal": Decimal("300.00"),
        "cycCredit": Decimal("0.00"),
        "cycDebit": Decimal("0.00"),
        "balance": Decimal("100.00"),
        "rate": Decimal("12.00"),
    })

    result = CalculateInterest(db_session)

    assert result.accountsProcessed == 2
    assert result.interestTransactionsWritten == 2
    # 1.00 (account one) + 1.00 (account two) = 2.00.
    assert result.totalInterest == Decimal("2.00")

    db_session.expire_all()
    # Account one was finalised on the control break; account two at EOF.
    assert db_session.get(Account, ACCT_ONE).curr_bal == Decimal("201.00")
    assert db_session.get(Account, ACCT_TWO).curr_bal == Decimal("301.00")


# --------------------------------------------------------------------------- #
# M-12: interest idempotency (rerun + concurrency).
# --------------------------------------------------------------------------- #
def _CountInterestTransactions(session, acctId):
    """Count POSTED interest transactions (type 01 / category 0005) for an account.

    An interest posting is identified exactly as the idempotency ledger does: a
    POSTED transaction of type :data:`INTEREST_TRAN_TYPE_CD` and category
    :data:`INTEREST_TRAN_CAT_CD` whose description names the account.

    Args:
        session: The session to query.
        acctId: The account id whose interest postings are counted.

    Returns:
        The number of matching interest transactions.
    """
    statement = select(Transaction).where(
        Transaction.tran_type_cd == INTEREST_TRAN_TYPE_CD,
        Transaction.tran_cat_cd == INTEREST_TRAN_CAT_CD,
        Transaction.tran_desc == "Int. for a/c " + acctId,
        Transaction.status == STATUS_POSTED,
    )
    return len(session.execute(statement).scalars().all())


def test_interest_rerun_same_period_is_idempotent(db_session, record_builder):
    # QA finding M-12: re-running interest for the SAME period must neither
    # re-accrue the balance nor write a duplicate interest transaction. On the
    # second run the ledger probe (_InterestAlreadyAccrued) finds the first run's
    # POSTED interest transaction -- visible within the caller's transaction after
    # its flush -- and skips the account entirely.
    spec = {
        "groupId": "TESTGRP",
        "cardNum": CARD_ONE,
        "custId": CUST_ONE,
        "currBal": Decimal("200.00"),
        "cycCredit": Decimal("50.00"),
        "cycDebit": Decimal("30.00"),
        "balance": Decimal("100.00"),
        "rate": Decimal("12.00"),
    }
    _BuildInterestScenario(record_builder, ACCT_ONE, spec)

    firstRun = CalculateInterest(db_session, runDate=RUN_DATE)
    assert firstRun.accountsProcessed == 1
    assert firstRun.interestTransactionsWritten == 1
    assert firstRun.totalInterest == Decimal("1.00")  # 100.00 * 12.00 / 1200
    db_session.expire_all()
    account = db_session.get(Account, ACCT_ONE)
    assert account.curr_bal == Decimal("201.00")       # 200.00 + 1.00
    assert account.curr_cyc_credit == Decimal("0.00")
    assert account.curr_cyc_debit == Decimal("0.00")
    assert _CountInterestTransactions(db_session, ACCT_ONE) == 1

    secondRun = CalculateInterest(db_session, runDate=RUN_DATE)
    # Fully idempotent: no account counted, no interest written, no total.
    assert secondRun.accountsProcessed == 0
    assert secondRun.interestTransactionsWritten == 0
    assert secondRun.totalInterest == Decimal("0")
    db_session.expire_all()
    account = db_session.get(Account, ACCT_ONE)
    assert account.curr_bal == Decimal("201.00")       # NOT 202.00 -- not re-accrued
    assert account.curr_cyc_credit == Decimal("0.00")
    assert account.curr_cyc_debit == Decimal("0.00")
    assert _CountInterestTransactions(db_session, ACCT_ONE) == 1  # no duplicate


def test_interest_new_period_accrues_again(db_session, record_builder):
    # Non-vacuity for the idempotency ledger: it is PERIOD-scoped, so a genuinely
    # NEW run date (a different 10-char tran-id prefix) is NOT suppressed and
    # accrues normally. This proves the second run of the idempotency test skips
    # because of the ledger match, not because a second run is always suppressed.
    spec = {
        "groupId": "TESTGRP",
        "cardNum": CARD_ONE,
        "custId": CUST_ONE,
        "currBal": Decimal("200.00"),
        "cycCredit": Decimal("0.00"),
        "cycDebit": Decimal("0.00"),
        "balance": Decimal("100.00"),
        "rate": Decimal("12.00"),
    }
    _BuildInterestScenario(record_builder, ACCT_ONE, spec)

    CalculateInterest(db_session, runDate=RUN_DATE)          # period A
    db_session.expire_all()
    assert db_session.get(Account, ACCT_ONE).curr_bal == Decimal("201.00")

    laterPeriod = date(2023, 7, 15)                          # period B (new prefix)
    secondPeriodRun = CalculateInterest(db_session, runDate=laterPeriod)
    assert secondPeriodRun.accountsProcessed == 1            # accrues again
    assert secondPeriodRun.interestTransactionsWritten == 1
    db_session.expire_all()
    assert db_session.get(Account, ACCT_ONE).curr_bal == Decimal("202.00")  # 201 + 1
    assert _CountInterestTransactions(db_session, ACCT_ONE) == 2            # one per period


# Distinct COMMITTED-fixture keys for the concurrency test, held well away from
# the rolled-back scenario keys so the surgical cleanup can never touch another
# test's rows.
CONCURRENCY_GROUP_ID = "ZZINTCON1"
CONCURRENCY_ACCT_ID = "98000000001"
CONCURRENCY_CARD_NUM = "4998000000000001"
CONCURRENCY_CUST_ID = "980000001"


@contextmanager
def _CommittedInterestGraph():
    """Yield two INDEPENDENT sessions over a COMMITTED interest scenario.

    Cross-connection row locking (``SELECT ... FOR UPDATE``) can only be
    exercised against committed data, so this helper steps outside the rolled-back
    ``db_session`` recipe: it seeds a complete interest graph
    (``account_group -> customer -> account -> card -> xref -> tran category
    balance -> disclosure rate``) on its own connection and COMMITS it, then
    yields two independent sessions on separate connections. On exit it rolls back
    both sessions and surgically DELETEs exactly the rows it committed (in
    reverse foreign-key order, including any interest transaction a run posted) on
    a dedicated cleanup connection, so nothing leaks into another test.

    Yields:
        A ``(sessionA, sessionB)`` tuple of independent, committed-data sessions.
    """
    seedSession = batch_db.SessionLocal()
    try:
        seedSession.add(AccountGroup(group_id=CONCURRENCY_GROUP_ID))
        seedSession.add(
            Customer(
                cust_id=CONCURRENCY_CUST_ID,
                first_name="TEST",
                last_name="CUSTOMER",
                addr_line_1="123 TEST STREET",
            )
        )
        seedSession.add(
            Account(
                acct_id=CONCURRENCY_ACCT_ID,
                active_status="Y",
                curr_bal=Decimal("200.00"),
                credit_limit=Decimal("5000.00"),
                cash_credit_limit=Decimal("2000.00"),
                curr_cyc_credit=Decimal("0.00"),
                curr_cyc_debit=Decimal("0.00"),
                group_id=CONCURRENCY_GROUP_ID,
            )
        )
        seedSession.add(
            Card(
                card_num=CONCURRENCY_CARD_NUM,
                acct_id=CONCURRENCY_ACCT_ID,
                embossed_name="TEST CARDHOLDER",
                active_status="Y",
            )
        )
        seedSession.add(
            CardXref(
                xref_card_num=CONCURRENCY_CARD_NUM,
                cust_id=CONCURRENCY_CUST_ID,
                acct_id=CONCURRENCY_ACCT_ID,
            )
        )
        seedSession.add(
            TranCategoryBalance(
                acct_id=CONCURRENCY_ACCT_ID,
                tran_type_cd=INPUT_TYPE_CD,
                tran_cat_cd=INPUT_CAT_CD,
                balance=Decimal("100.00"),
            )
        )
        seedSession.add(
            DisclosureGroup(
                group_id=CONCURRENCY_GROUP_ID,
                tran_type_cd=INPUT_TYPE_CD,
                tran_cat_cd=INPUT_CAT_CD,
                interest_rate=Decimal("12.00"),
            )
        )
        seedSession.commit()
    finally:
        seedSession.close()

    sessionA = batch_db.SessionLocal()
    sessionB = batch_db.SessionLocal()
    try:
        yield sessionA, sessionB
    finally:
        sessionA.rollback()
        sessionB.rollback()
        sessionA.close()
        sessionB.close()
        cleanupSession = batch_db.SessionLocal()
        try:
            cleanupSession.execute(
                delete(Transaction).where(
                    Transaction.card_num == CONCURRENCY_CARD_NUM
                )
            )
            cleanupSession.execute(
                delete(DisclosureGroup).where(
                    DisclosureGroup.group_id == CONCURRENCY_GROUP_ID
                )
            )
            cleanupSession.execute(
                delete(TranCategoryBalance).where(
                    TranCategoryBalance.acct_id == CONCURRENCY_ACCT_ID
                )
            )
            cleanupSession.execute(
                delete(CardXref).where(
                    CardXref.xref_card_num == CONCURRENCY_CARD_NUM
                )
            )
            cleanupSession.execute(
                delete(Card).where(Card.card_num == CONCURRENCY_CARD_NUM)
            )
            cleanupSession.execute(
                delete(Account).where(Account.acct_id == CONCURRENCY_ACCT_ID)
            )
            cleanupSession.execute(
                delete(Customer).where(Customer.cust_id == CONCURRENCY_CUST_ID)
            )
            cleanupSession.execute(
                delete(AccountGroup).where(
                    AccountGroup.group_id == CONCURRENCY_GROUP_ID
                )
            )
            cleanupSession.commit()
        finally:
            cleanupSession.close()


def test_concurrent_interest_runs_serialize_and_accrue_once():
    # QA finding M-12: two concurrent interest runs on the same account must
    # accrue interest EXACTLY ONCE. The per-account SELECT ... FOR UPDATE row
    # lock serializes them: while run A holds the lock (having accrued but not yet
    # committed), a second run cannot take the lock -- proven deterministically
    # with a NOWAIT probe that raises rather than blocking the test. After A
    # commits, run B takes the lock, its ledger probe finds A's committed interest
    # and it SKIPS, so the balance is 201.00 (single accrual), never 202.00.
    with _CommittedInterestGraph() as (sessionA, sessionB):
        firstRun = CalculateInterest(sessionA, runDate=RUN_DATE)
        assert firstRun.accountsProcessed == 1
        assert firstRun.interestTransactionsWritten == 1
        # A holds the account row lock (uncommitted). A concurrent lock attempt
        # must fail immediately rather than silently proceeding to double-accrue.
        lockProbe = (
            select(Account)
            .where(Account.acct_id == CONCURRENCY_ACCT_ID)
            .with_for_update(nowait=True)
        )
        with pytest.raises(OperationalError):
            sessionB.execute(lockProbe).first()
        sessionB.rollback()  # clear the aborted transaction from the failed probe

        # A commits: its interest transaction and accrued balance become visible.
        sessionA.commit()

        secondRun = CalculateInterest(sessionB, runDate=RUN_DATE)
        assert secondRun.accountsProcessed == 0            # skipped: already accrued
        assert secondRun.interestTransactionsWritten == 0
        assert secondRun.totalInterest == Decimal("0")
        sessionB.commit()

        # Verify the COMMITTED end state on a fresh connection BEFORE the context
        # manager's teardown surgically deletes the committed fixture rows.
        verifySession = batch_db.SessionLocal()
        try:
            account = verifySession.get(Account, CONCURRENCY_ACCT_ID)
            assert account.curr_bal == Decimal("201.00")   # accrued once, not twice
            assert _CountInterestTransactions(
                verifySession, CONCURRENCY_ACCT_ID
            ) == 1
        finally:
            verifySession.close()
