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
from decimal import Decimal

import pytest
from pydantic import ValidationError
from sqlalchemy import select

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
        cvv_cd="123",
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
    request = BillPayRequest.model_construct(
        acct_id="", confirm="Y", payment_amount=None
    )
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
