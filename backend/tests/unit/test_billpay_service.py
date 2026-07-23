# Unit tests for app.services.billpay_service.BillPayService
# Traceability: app/cbl/COBIL00C.cbl (CB00 bill payment)
"""Unit tests for :class:`app.services.billpay_service.BillPayService`.

These tests pin down the behavioral contract of the legacy CICS online program
``COBIL00C`` (transaction ``CB00``, bill payment) at the service layer. They
verify:

* the F-006 available-credit rule ``available_credit = credit_limit - curr_bal``
  (AAP section 0.8.1) -- which is DELIBERATELY DISTINCT from the batch posting
  over-limit rule (reject code 102: ``curr_cyc_credit - curr_cyc_debit +
  tran_amt`` compared against ``credit_limit``). The two formulas live in
  different modules and are never conflated;
* the ``PayBill`` guard order (empty account id, invalid confirmation flag,
  nothing to pay) with the exact legacy wording;
* the ``confirm='N'`` path, which prompts for confirmation and posts nothing;
* the ``confirm='Y'`` pay-in-full path, which posts a payment transaction with
  the verbatim COBIL00C literals and zeroes the current balance in one commit.

Money is compared as exact :class:`decimal.Decimal` (never ``float`` or
``pytest.approx``), honoring the regulatory numeric-parity requirement (AAP
section 0.7.1). Tests run under ``asyncio_mode = "auto"`` so each coroutine test
is a plain ``async def`` that uses the function-scoped ``db_session`` fixture
from ``backend/tests/conftest.py``; that fixture truncates every table after the
test, so the service is free to ``commit()`` without leaking state.

Naming conventions (Ochs Rule, AAP section 0.8.3): test functions are
snake_case; helper functions are PascalCase; local variables are camelCase; and
module-level constants are ALL_UPPERCASE with underscores.
"""

import importlib
from datetime import date
from decimal import Decimal

import pytest
from pydantic import ValidationError
from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.core.exceptions import DomainValidationError, NotFoundError
from app.models import Account, Card, CardXref, Customer, Transaction
from app.schemas.billpay import BillPayRequest
from app.services.billpay_service import BillPayService

# Import the service module itself so the module-level payment constants can be
# asserted directly (Phase E), exactly as the agent prompt specifies.
billpayModule = importlib.import_module("app.services.billpay_service")


# ---------------------------------------------------------------------------
# Fixed identifiers for the seeded referential-integrity graph (Ochs Rule:
# constants are ALL_UPPERCASE). Each test starts from an empty database (the
# db_session truncate teardown), so a single fixed id triple is reused across
# tests with no risk of cross-test collision. The lengths match the ported
# copybook keys: ACCT-ID PIC 9(11), CUST-ID PIC 9(09), CARD-NUM PIC X(16).
# ---------------------------------------------------------------------------
ACCT_ID = "00000000001"
CUST_ID = "000000001"
CARD_NUM = "4111111111111111"

# A validly formatted (exactly 11 digits) but never-seeded account id, used to
# drive the "account not found" lookup path.
MISSING_ACCT_ID = "99999999999"


def MessageOf(error):
    """Return the human-readable message carried by a raised error.

    CardDemo domain errors expose the wording on a ``message`` attribute;
    anything else (for example a pydantic ``ValidationError``) falls back to its
    ``str()`` form. This keeps the message assertions below independent of the
    concrete exception type.

    Args:
        error: The exception instance whose message text is required.

    Returns:
        The ``message`` attribute when present and truthy, otherwise ``str``.
    """
    return getattr(error, "message", None) or str(error)


async def SeedBillPayGraph(session, currBal, creditLimit):
    """Seed a minimal FK-safe Account -> Customer -> Card -> CardXref graph.

    Populates every NOT-NULL column of each model (values not exercised by the
    bill-payment logic are given deterministic placeholders) and parameterizes
    only the two amounts the tests vary: the account balance and credit limit.
    Rows are inserted in foreign-key-safe order and flushed in stages so each
    child insert sees its parents; the flush is also required because
    ``db_session`` runs with ``autoflush=False`` and the service issues
    ``SELECT ... FOR UPDATE`` (which would not otherwise observe the seed).

    The seeded ``Card.card_num`` equals ``CardXref.xref_card_num`` (``CARD_NUM``)
    so the payment transaction the service posts against the cross-referenced
    card satisfies the ``transactions.card_num -> cards.card_num`` foreign key.

    Args:
        session: The active async session (the ``db_session`` fixture).
        currBal: The account current balance to seed, as an exact ``Decimal``.
        creditLimit: The account credit limit to seed, as an exact ``Decimal``.

    Returns:
        The seeded :class:`app.models.Account` instance.
    """
    account = Account(
        acct_id=ACCT_ID,
        active_status="Y",
        curr_bal=currBal,
        credit_limit=creditLimit,
        cash_credit_limit=Decimal("0.00"),
        curr_cyc_credit=Decimal("0.00"),
        curr_cyc_debit=Decimal("0.00"),
    )
    customer = Customer(
        cust_id=CUST_ID,
        first_name="JOHN",
        last_name="DOE",
        addr_line_1="1 MAIN STREET",
    )
    session.add_all([account, customer])
    await session.flush()

    card = Card(
        card_num=CARD_NUM,
        acct_id=ACCT_ID,
        embossed_name="JOHN DOE",
        active_status="Y",
    )
    session.add(card)
    await session.flush()

    xref = CardXref(xref_card_num=CARD_NUM, cust_id=CUST_ID, acct_id=ACCT_ID)
    session.add(xref)
    await session.flush()

    return account


# ===========================================================================
# Phase A -- GetBillPayInfo (F-006 available credit).
# ===========================================================================


async def test_get_billpay_info_available_credit(db_session):
    """GetBillPayInfo returns available_credit = credit_limit - curr_bal (F-006).

    F-006 is intentionally DISTINCT from the batch posting over-limit rule
    (reject code 102), which uses the cycle credit/debit formula; only the
    limit-minus-balance figure is computed here, as an exact ``Decimal``.
    """
    await SeedBillPayGraph(
        db_session,
        currBal=Decimal("1000.00"),
        creditLimit=Decimal("5000.00"),
    )
    service = BillPayService()

    info = await service.GetBillPayInfo(db_session, ACCT_ID)

    assert isinstance(info.available_credit, Decimal)
    assert info.available_credit == Decimal("4000.00")


async def test_get_billpay_info_missing_account_raises_not_found(db_session):
    """GetBillPayInfo raises NotFoundError when the account does not exist."""
    service = BillPayService()

    with pytest.raises(NotFoundError) as excInfo:
        await service.GetBillPayInfo(db_session, MISSING_ACCT_ID)

    assert MessageOf(excInfo.value) == billpayModule.MSG_ACCOUNT_NOT_FOUND


# ===========================================================================
# Phase B -- PayBill validation guards (exact legacy wording).
# ===========================================================================


async def test_paybill_empty_acct_raises_validation(db_session):
    """An empty account id is rejected with the verbatim empty-field message.

    The ``BillPayRequest`` schema constrains ``acct_id`` to exactly 11 digits,
    so an empty value cannot be built through normal validation;
    ``model_construct`` bypasses the schema to exercise the SERVICE-level empty
    guard (COBIL00C L159-161), which is what this suite targets.
    """
    request = BillPayRequest.model_construct(acct_id="", confirm="Y")
    service = BillPayService()

    with pytest.raises(DomainValidationError) as excInfo:
        await service.PayBill(db_session, request)

    assert MessageOf(excInfo.value) == "Acct ID can NOT be empty..."


def test_paybill_bad_confirm_raises_validation():
    """An invalid confirmation flag is rejected by the schema ('Y'/'N' only).

    The ``confirm`` field delegates to ``validators.ValidateYesNo``
    (case-sensitive 'Y'/'N'), so 'X' is rejected by pydantic at construction
    time, before the service runs -- the schema-level enforcement the agent
    prompt directs us to assert when the field is constrained to Y/N.
    """
    with pytest.raises(ValidationError) as excInfo:
        BillPayRequest(acct_id=ACCT_ID, confirm="X")

    assert "confirm" in str(excInfo.value)


async def test_paybill_nothing_to_pay(db_session):
    """A non-positive balance is rejected with the verbatim nothing-to-pay text."""
    await SeedBillPayGraph(
        db_session,
        currBal=Decimal("0.00"),
        creditLimit=Decimal("5000.00"),
    )
    request = BillPayRequest(acct_id=ACCT_ID, confirm="Y")
    service = BillPayService()

    with pytest.raises(DomainValidationError) as excInfo:
        await service.PayBill(db_session, request)

    assert MessageOf(excInfo.value) == "You have nothing to pay..."


# ===========================================================================
# Phase C -- PayBill confirm='N' (prompt, no posting).
# ===========================================================================


async def test_paybill_confirm_no_does_not_post(db_session):
    """confirm='N' returns the confirm prompt and posts nothing (balance intact)."""
    await SeedBillPayGraph(
        db_session,
        currBal=Decimal("1000.00"),
        creditLimit=Decimal("5000.00"),
    )
    request = BillPayRequest(acct_id=ACCT_ID, confirm="N")
    service = BillPayService()

    response = await service.PayBill(db_session, request)

    assert response.message == "Confirm to make a bill payment..."

    refreshedAccount = await db_session.get(Account, ACCT_ID)
    assert refreshedAccount.curr_bal == Decimal("1000.00")

    postedRows = await db_session.execute(
        select(Transaction).where(Transaction.card_num == CARD_NUM)
    )
    assert postedRows.scalars().first() is None


# ===========================================================================
# Phase D -- PayBill confirm='Y' (pay-in-full + exact posting constants).
# ===========================================================================


async def test_paybill_confirm_yes_zeroes_balance(db_session):
    """confirm='Y' pays the full balance, zeroes curr_bal, and returns a tran id."""
    await SeedBillPayGraph(
        db_session,
        currBal=Decimal("1000.00"),
        creditLimit=Decimal("5000.00"),
    )
    request = BillPayRequest(acct_id=ACCT_ID, confirm="Y")
    service = BillPayService()

    response = await service.PayBill(db_session, request)

    assert response.payment_amount == Decimal("1000.00")

    refreshedAccount = await db_session.get(Account, ACCT_ID)
    assert refreshedAccount.curr_bal == Decimal("0.00")

    assert isinstance(response.tran_id, str)
    assert len(response.tran_id) == 16


async def test_paybill_posts_transaction_with_exact_fields(db_session):
    """The posted payment transaction carries the verbatim COBIL00C literals."""
    await SeedBillPayGraph(
        db_session,
        currBal=Decimal("1000.00"),
        creditLimit=Decimal("5000.00"),
    )
    request = BillPayRequest(acct_id=ACCT_ID, confirm="Y")
    service = BillPayService()

    await service.PayBill(db_session, request)

    postedRows = await db_session.execute(
        select(Transaction).where(Transaction.card_num == CARD_NUM)
    )
    postedTransaction = postedRows.scalars().one()

    assert postedTransaction.tran_type_cd == "02"
    assert postedTransaction.tran_desc == "BILL PAYMENT - ONLINE"
    assert postedTransaction.tran_source == "POS TERM"
    assert postedTransaction.merchant_name == "BILL PAYMENT"
    assert postedTransaction.tran_amt == Decimal("1000.00")


# ===========================================================================
# Phase E -- module-level payment constants.
# ===========================================================================


def test_payment_constants():
    """The module payment constants match the verbatim COBIL00C literals."""
    assert billpayModule.PAYMENT_TRAN_TYPE == "02"
    assert billpayModule.PAYMENT_TRAN_DESC == "BILL PAYMENT - ONLINE"
    assert billpayModule.PAYMENT_MERCHANT_NAME == "BILL PAYMENT"


# ===========================================================================
# Phase F -- M-09 billpay contract: no partial-payment field, inherent
# replay/rollback safety, and the explicit no-expiration-gate policy.
# ===========================================================================


def test_billpay_request_rejects_payment_amount():
    """BillPayRequest forbids a ``payment_amount`` (legacy pay-in-full, M-09).

    COBIL00 has only two unprotected inputs -- the account id and the
    confirmation flag -- and always pays the FULL current balance, so the
    request DTO carries no partial-payment field. Because ``RequestBase`` sets
    ``extra="forbid"``, a client that still submits ``payment_amount`` is
    rejected at the schema boundary (surfacing as HTTP 422) rather than having
    the value silently accepted and ignored -- the accepted-but-ignored contract
    defect QA finding M-09 flagged. The field is also asserted absent from the
    model so a partial-payment feature cannot be reintroduced unnoticed.
    """
    with pytest.raises(ValidationError) as excInfo:
        BillPayRequest(acct_id=ACCT_ID, confirm="Y", payment_amount="50.00")

    errorText = str(excInfo.value).lower()
    assert "payment_amount" in errorText
    assert (
        "extra" in errorText
        or "forbid" in errorText
        or "not permitted" in errorText
    )
    assert "payment_amount" not in BillPayRequest.model_fields


async def test_paybill_replay_two_sessions_rejected(test_engine, db_session):
    """A committed pay-in-full is not double-applied by an independent replay.

    Proves the pay-in-full contract is inherently idempotent WITHOUT any client
    idempotency key (M-09): session A pays the full balance and commits (the
    balance is zeroed); an INDEPENDENT session B -- its own connection, so it
    genuinely re-reads committed state rather than sharing an identity map --
    then replays the identical confirmed request and is rejected by the verbatim
    ``MSG_NOTHING_TO_PAY`` guard. Exactly one payment transaction exists and the
    balance is exactly zero: the duplicate never drives it negative.

    The ``db_session`` fixture is requested only so its truncate teardown cleans
    the tables afterward; the test itself operates entirely through independent
    sessions bound to the shared ``test_engine`` so the two ``PayBill`` calls do
    not share a session. The seed is committed on its own session first so no
    seed-time row lock survives to block session A's ``SELECT ... FOR UPDATE``.

    Args:
        test_engine: The function-scoped async engine fixture (independent
            sessions are opened against it).
        db_session: Requested only for its truncate teardown (never used
            directly, so it holds no locks that could block the FOR UPDATE read).
    """
    sessionMaker = async_sessionmaker(
        bind=test_engine,
        class_=AsyncSession,
        expire_on_commit=False,
        autoflush=False,
    )

    # Seed on an independent session and COMMIT so no seed lock survives.
    async with sessionMaker() as seedSession:
        await SeedBillPayGraph(
            seedSession,
            currBal=Decimal("250.00"),
            creditLimit=Decimal("5000.00"),
        )
        await seedSession.commit()

    request = BillPayRequest(acct_id=ACCT_ID, confirm="Y")
    service = BillPayService()

    # Session A: pay in full (the service owns and performs the commit).
    async with sessionMaker() as sessionA:
        firstResponse = await service.PayBill(sessionA, request)
    assert firstResponse.payment_amount == Decimal("250.00")
    assert firstResponse.tran_id is not None

    # Session B (independent): replay the identical request -> nothing to pay.
    async with sessionMaker() as sessionB:
        with pytest.raises(DomainValidationError) as excInfo:
            await service.PayBill(sessionB, request)
    assert MessageOf(excInfo.value) == billpayModule.MSG_NOTHING_TO_PAY

    # Exactly ONE payment transaction, and the balance is exactly zero.
    async with sessionMaker() as verifySession:
        postedRows = await verifySession.execute(
            select(Transaction).where(Transaction.card_num == CARD_NUM)
        )
        assert len(postedRows.scalars().all()) == 1
        settledAccount = await verifySession.get(Account, ACCT_ID)
        assert settledAccount.curr_bal == Decimal("0.00")


async def test_paybill_rollback_on_insert_failure(db_session, monkeypatch):
    """A failed transaction insert rolls back: no payment persisted, balance intact.

    Forces the ``WRITE-TRANSACT-FILE`` step to fail -- the legacy DUPKEY /
    INVALID KEY write condition -- by patching the transaction repository's
    ``Insert`` to raise a :class:`sqlalchemy.exc.SQLAlchemyError`. ``PayBill``
    must roll the unit of work back, re-raise the verbatim
    ``MSG_UNABLE_ADD_TRAN`` validation error, leave the balance UNCHANGED, and
    persist no transaction, so a partial payment can never be recorded (M-09).
    The seed is committed first so the service's rollback cannot wipe it.

    Args:
        db_session: The async session shared by the seed, the service call, and
            the post-condition verification.
        monkeypatch: pytest fixture used to inject the failing ``Insert``.
    """
    await SeedBillPayGraph(
        db_session,
        currBal=Decimal("300.00"),
        creditLimit=Decimal("5000.00"),
    )
    await db_session.commit()

    service = BillPayService()

    async def _FailingInsert(session, transaction):
        """Simulate the legacy DUPKEY / INVALID KEY write failure."""
        raise SQLAlchemyError("simulated transaction write failure")

    monkeypatch.setattr(service.transactionRepository, "Insert", _FailingInsert)

    request = BillPayRequest(acct_id=ACCT_ID, confirm="Y")
    with pytest.raises(DomainValidationError) as excInfo:
        await service.PayBill(db_session, request)
    assert MessageOf(excInfo.value) == billpayModule.MSG_UNABLE_ADD_TRAN

    # The balance is UNCHANGED and no payment transaction was persisted.
    refreshedAccount = await db_session.get(Account, ACCT_ID)
    assert refreshedAccount.curr_bal == Decimal("300.00")
    postedRows = await db_session.execute(
        select(Transaction).where(Transaction.card_num == CARD_NUM)
    )
    assert postedRows.scalars().first() is None


async def test_paybill_posts_despite_expired_card(db_session):
    """Bill payment posts regardless of card expiration (no 103 gate in COBIL00C).

    The expiration gate (batch posting reject code 103, ``CBTRN02C``) does NOT
    exist in the online bill-pay program, so a confirmed payment on an account
    whose card expired in the past still posts and zeroes the balance (Minimal
    Change Clause, AAP 0.8.1). This pins the explicit "bill-pay does not gate on
    expiration" policy (M-09) so a reviewer does not mistake its absence for an
    omission and add an unfaithful expiry check.

    Args:
        db_session: The async session used to seed, run the payment, and verify.
    """
    await SeedBillPayGraph(
        db_session,
        currBal=Decimal("120.00"),
        creditLimit=Decimal("5000.00"),
    )
    # Force the seeded card's expiration date into the past.
    seededCard = await db_session.get(Card, CARD_NUM)
    seededCard.expiration_date = date(2000, 1, 1)
    await db_session.flush()

    request = BillPayRequest(acct_id=ACCT_ID, confirm="Y")
    service = BillPayService()

    response = await service.PayBill(db_session, request)

    assert response.payment_amount == Decimal("120.00")
    assert response.tran_id is not None
    refreshedAccount = await db_session.get(Account, ACCT_ID)
    assert refreshedAccount.curr_bal == Decimal("0.00")
