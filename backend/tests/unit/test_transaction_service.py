# Unit tests for app.services.transaction_service.TransactionService
# Traceability: app/cbl/COTRN00C.cbl (CT00), COTRN01C.cbl (CT01), COTRN02C.cbl (CT02),
#   posting validation from CBTRN02C.cbl (codes 100/101/102/103/109)
"""Unit tests for :class:`app.services.transaction_service.TransactionService`.

This suite pins the modern re-expression of three legacy CICS online COBOL
programs plus the batch posting validator, preserving their business rules
field-for-field (AAP 0.8.1, Minimal Change Clause):

* ``COTRN00C`` (CT00) -> :meth:`TransactionService.ListTransactions` -- the
  paginated transaction browse; ``card_num`` is masked in every summary row.
* ``COTRN01C`` (CT01) -> :meth:`TransactionService.GetTransaction` -- view one
  transaction by id; a blank id and an absent id raise the two distinct legacy
  messages.
* ``COTRN02C`` (CT02) -> :meth:`TransactionService.AddTransaction` -- add a
  transaction and post it, reusing the ``CBTRN02C`` ``1500-VALIDATE-TRAN`` /
  ``2800-UPDATE-ACCOUNT-REC`` reject codes 100/101/102/103 and the additional
  code 109 (AAP 0.7.3).

The five posting reject codes are asserted with their EXACT integer ``.code``
and their VERBATIM UPPERCASE ``.description`` (a golden-master parity contract):
the online add path (COTRN02C) and the batch posting job (CBTRN02C) must
reconcile against the 430-byte ``DALYREJS`` reject record. All monetary values
are exact :class:`~decimal.Decimal` (never ``float``); equality is asserted with
``==`` and never :func:`pytest.approx`.

Design independence:
    The posting exceptions were permitted to be implemented EITHER as per-code
    subclasses (Design A) OR as a single ``TransactionPostingError`` driven by a
    ``PostingRejectCode`` enum (Design B). Every posting assertion reaches the
    module defensively -- through :func:`PostingErrorClasses` and
    :func:`BuildPostingError` -- so the suite passes unchanged under either
    design.

Async model:
    ``asyncio_mode = "auto"`` (backend ``pyproject.toml``) runs each ``async
    def test_*`` without a decorator; the shared ``db_session`` fixture
    (``backend/tests/conftest.py``) supplies an isolated PostgreSQL
    ``AsyncSession`` that is truncated after every test.

Ochs naming (AAP 0.8.2 / 0.8.3): snake_case test functions (the pytest
collection contract), PascalCase helper functions (``SeedPostingGraph``,
``BuildTransactionCreate``, ``PostingErrorClasses`` ...), camelCase local
variables, and ALL_UPPERCASE module constants; 4-space indentation throughout.
"""

import importlib
from datetime import date, datetime
from decimal import Decimal

import pytest
from sqlalchemy.exc import IntegrityError

from app.core.exceptions import DomainValidationError, NotFoundError
from app.models import Account, Card, CardXref, Customer, Transaction
from app.schemas.common import PaginationParams
from app.schemas.transaction import TransactionCreate
from app.services.transaction_service import TransactionService

# Import the exceptions module by name (rather than binding every concrete
# posting class) so the defensive helpers below tolerate BOTH the subclass
# design (Design A) and the enum design (Design B) without a hard ImportError
# when a design-specific symbol is absent -- mirroring tests/unit/test_exceptions.py.
exceptionsModule = importlib.import_module("app.core.exceptions")


# ---------------------------------------------------------------------------
# Module constants (Ochs Rule: ALL_UPPERCASE).
# ---------------------------------------------------------------------------

# Default add-screen timestamps for BuildTransactionCreate / SeedTransaction. A
# naive datetime is accepted by the schema (normalized to datetime) and stored
# into the TIMESTAMPTZ columns by asyncpg; only the date portion participates in
# the CBTRN02C expiration edit (code 103), so a fixed instant is sufficient.
DEFAULT_ORIG_TS = datetime(2025, 6, 1, 12, 0, 0)
DEFAULT_PROC_TS = datetime(2025, 6, 1, 12, 0, 0)

# The verbatim UPPERCASE description shared by reject codes 101 and 109. They are
# DISTINCT reason codes with IDENTICAL text (AAP 0.7.3): 101 is the account
# read-not-found during validation (1500-B-LOOKUP-ACCT); 109 is the account
# rewrite-not-found during the balance update (2800-UPDATE-ACCOUNT-REC).
ACCOUNT_RECORD_NOT_FOUND_TEXT = "ACCOUNT RECORD NOT FOUND"

# Design-A concrete subclass names keyed by reject code. BuildPostingError tries
# these first and transparently falls back to the Design-B enum construction.
DESIGN_A_CLASS_BY_CODE = {
    100: "InvalidCardNumberError",
    101: "AccountNotFoundError",
    102: "OverlimitTransactionError",
    103: "AccountExpiredError",
    109: "AccountUpdateFailedError",
}

# A card number with no cross-reference seeded, used to exercise the posting
# XREF lookup miss (reject code 100) and the defensive orphan-xref path (101).
UNKNOWN_CARD_NUM = "9999999999999999"


# ---------------------------------------------------------------------------
# Defensive posting-exception helpers (Design A subclasses OR Design B enum).
# ---------------------------------------------------------------------------
def PostingErrorClasses():
    """Return a tuple of catchable posting-error classes under either design.

    Collects whichever concrete posting-exception symbols the module exposes
    (the Design-A per-code subclasses and/or the Design-B
    ``TransactionPostingError`` base). The result is suitable for
    ``pytest.raises(...)`` and is never a bare :class:`Exception`, satisfying
    the Ochs "catch specific exceptions" rule.

    Returns:
        A non-empty tuple of exception classes. Falls back to
        ``(CardDemoError,)`` only in the impossible case that no posting class
        is exported.
    """
    candidateNames = [
        "InvalidCardNumberError", "AccountNotFoundError",
        "OverlimitTransactionError", "AccountExpiredError",
        "AccountUpdateFailedError", "TransactionPostingError",
    ]
    classes = tuple(
        getattr(exceptionsModule, name)
        for name in candidateNames
        if hasattr(exceptionsModule, name)
    )
    return classes or (exceptionsModule.CardDemoError,)


def AssertPostingCode(raisedError, expectedCode, expectedDescription):
    """Assert a posting error carries the exact code and verbatim description.

    Args:
        raisedError: The caught posting-exception instance.
        expectedCode: The exact integer reject reason code (for example 102).
        expectedDescription: The verbatim UPPERCASE reject description.
    """
    assert int(raisedError.code) == expectedCode
    assert raisedError.description == expectedDescription


def BuildPostingError(rejectCode):
    """Construct a posting-error instance for ``rejectCode`` under either design.

    Prefers the Design-A per-code subclass when the module exposes it; otherwise
    builds the Design-B ``TransactionPostingError`` from the ``PostingRejectCode``
    enum member whose integer value equals ``rejectCode``. Mirrors the defensive
    builder in ``tests/unit/test_exceptions.py`` so the two suites stay aligned.

    Args:
        rejectCode: One of the five ported reject reason codes (100, 101, 102,
            103, 109).

    Returns:
        A raised-and-catchable posting exception exposing ``.code`` (int) and
        ``.description`` (verbatim UPPERCASE str).
    """
    className = DESIGN_A_CLASS_BY_CODE[rejectCode]
    if hasattr(exceptionsModule, className):
        return getattr(exceptionsModule, className)()
    postingError = getattr(exceptionsModule, "TransactionPostingError")
    rejectEnum = getattr(exceptionsModule, "PostingRejectCode")
    memberByCode = {int(member): member for member in rejectEnum}
    return postingError(memberByCode[rejectCode])


def MessageOf(error):
    """Return the human-readable message carried by a domain error.

    ``CardDemoError`` stores its text on ``.message`` and forwards it to
    ``Exception`` (so ``str(error)`` matches); this helper prefers ``.message``
    and falls back to ``str(error)`` so an assertion reads identically under
    either accessor.

    Args:
        error: The caught exception instance.

    Returns:
        The error message string.
    """
    return getattr(error, "message", None) or str(error)


# ---------------------------------------------------------------------------
# Seeding helpers (PascalCase per Ochs; NOT fixtures -- awaited directly). Every
# NOT-NULL column of each model is populated, and rows are added in
# FK-satisfying order Account -> Customer -> Card -> CardXref (SQLAlchemy's unit
# of work topologically orders the INSERTs from a single add_all + flush).
# ---------------------------------------------------------------------------
async def SeedPostingGraph(
    session,
    acctId="00000000030",
    custId="000000030",
    cardNum="4000000000000030",
    creditLimit=Decimal("5000.00"),
    cycCredit=Decimal("0.00"),
    cycDebit=Decimal("0.00"),
    currBal=Decimal("1000.00"),
    expirationDate=date(2030, 1, 1),
):
    """Seed a fully referential Account/Customer/Card/CardXref posting graph.

    Populates every NOT-NULL column verified against the real ORM models so the
    row set flushes cleanly under PostgreSQL foreign-key enforcement. The
    monetary and cycle fields are exact :class:`~decimal.Decimal` values so the
    CBTRN02C over-limit arithmetic (``curr_cyc_credit - curr_cyc_debit + amt``
    versus ``credit_limit``) is reproduced precisely by the caller's scenario.

    Args:
        session: The active async database session (``db_session`` fixture).
        acctId: 11-character account id (primary key, zeros preserved).
        custId: 9-character customer id.
        cardNum: 16-character card number (cross-reference + posting key).
        creditLimit: Account credit limit used by the over-limit edit (code 102).
        cycCredit: Current-cycle credit total used by the over-limit edit.
        cycDebit: Current-cycle debit total used by the over-limit edit.
        currBal: Current balance the posting update increments.
        expirationDate: Account expiration date used by the expiry edit (code 103).

    Returns:
        A ``(acctId, cardNum)`` tuple for the seeded account and card.
    """
    account = Account(
        acct_id=acctId, active_status="Y", curr_bal=currBal,
        credit_limit=creditLimit, cash_credit_limit=Decimal("1000.00"),
        curr_cyc_credit=cycCredit, curr_cyc_debit=cycDebit,
        open_date=date(2020, 1, 1), expiration_date=expirationDate,
        reissue_date=date(2024, 1, 1), addr_zip="12345", group_id="DEFAULT",
    )
    customer = Customer(
        cust_id=custId, first_name="JANE", last_name="DOE",
        addr_line_1="1 MAIN ST", addr_state_cd="CA", addr_country_cd="USA",
        addr_zip="12345", phone_num_1="1234567890", ssn="123456789",
        govt_issued_id="DL123", date_of_birth=date(1980, 5, 5),
        pri_card_holder_ind="Y", fico_credit_score=750,
    )
    card = Card(
        card_num=cardNum, acct_id=acctId, cvv_cd="123",
        embossed_name="JANE DOE", expiration_date=expirationDate,
        active_status="Y",
    )
    xref = CardXref(xref_card_num=cardNum, cust_id=custId, acct_id=acctId)
    session.add_all([account, customer, card, xref])
    await session.flush()
    return acctId, cardNum


async def SeedTransaction(
    session,
    tranId,
    cardNum,
    tranAmt=Decimal("5.00"),
    tranTypeCd="01",
    tranCatCd="0005",
):
    """Insert one posted ``Transaction`` row on an already-seeded card.

    The ``card_num`` foreign key must reference a card seeded by
    :func:`SeedPostingGraph`. ``status`` is left to its model default
    (``POSTED``), matching the CVTRA05Y posted ledger the browse screens read.

    Args:
        session: The active async database session.
        tranId: The 16-character transaction id (primary key).
        cardNum: The owning 16-character card number (foreign key).
        tranAmt: The exact :class:`~decimal.Decimal` transaction amount.
        tranTypeCd: The 2-character transaction type code (numeric).
        tranCatCd: The 4-digit transaction category code.

    Returns:
        The inserted (session-attached) :class:`~app.models.transaction.Transaction`.
    """
    transaction = Transaction(
        tran_id=tranId, tran_type_cd=tranTypeCd, tran_cat_cd=tranCatCd,
        tran_source="POS", tran_desc="SEED TRANSACTION", tran_amt=tranAmt,
        merchant_id="000000001", merchant_name="SEED MERCHANT",
        merchant_city="SEED CITY", merchant_zip="12345",
        card_num=cardNum, orig_ts=DEFAULT_ORIG_TS, proc_ts=DEFAULT_PROC_TS,
    )
    session.add(transaction)
    await session.flush()
    return transaction


def BuildTransactionCreate(cardNum, tranAmt, origTs=DEFAULT_ORIG_TS, procTs=DEFAULT_PROC_TS):
    """Build a valid :class:`TransactionCreate` for the add path (COTRN02C).

    Every field the real schema requires is populated with values that also pass
    the service-layer COTRN02C data-field edits: a numeric ``tran_type_cd``, a
    4-digit ``tran_cat_cd``, a 9-digit ``merchant_id``, an exact ``Decimal``
    amount (float is rejected), and valid ``YYYY-MM-DD`` dates. The account is
    identified by ``card_num`` (``acct_id`` is left unset).

    Args:
        cardNum: The 16-digit card number identifying the owning account.
        tranAmt: The exact :class:`~decimal.Decimal` transaction amount.
        origTs: The original transaction timestamp (drives the expiry edit).
        procTs: The processing timestamp.

    Returns:
        A validated :class:`TransactionCreate` request DTO.
    """
    return TransactionCreate(
        card_num=cardNum,
        tran_type_cd="01",
        tran_cat_cd="0005",
        tran_source="POS",
        tran_desc="TEST TRANSACTION",
        tran_amt=tranAmt,
        merchant_id="000000001",
        merchant_name="TEST MERCHANT",
        merchant_city="TEST CITY",
        merchant_zip="12345",
        orig_ts=origTs,
        proc_ts=procTs,
    )


# ===========================================================================
# Phase A -- posting reject codes (CBTRN02C 1500-VALIDATE-TRAN / 2800-UPDATE).
# ===========================================================================
async def test_add_transaction_unknown_card_raises_100(db_session):
    """An unknown card at posting raises reject code 100 (CBTRN02C 1500-A).

    The online COTRN02C add flow intercepts an entirely-unknown card earlier,
    in VALIDATE-INPUT-KEY-FIELDS (raising the "Card Number NOT found..."
    edit), so the CBTRN02C posting XREF re-read that emits reject code 100 is
    never reached via ``AddTransaction`` for that input. This test therefore
    drives the posting XREF lookup at its own entry point
    (``_LoadAccountForPosting``) with NO cross-reference seeded -- the exact
    ``1500-A-LOOKUP-XREF`` path that raises ``InvalidCardNumberError`` (code
    100), keeping the reject reconcilable with the batch posting job.
    """
    service = TransactionService()
    with pytest.raises(PostingErrorClasses()) as raised:
        await service._LoadAccountForPosting(db_session, UNKNOWN_CARD_NUM)
    AssertPostingCode(raised.value, 100, "INVALID CARD NUMBER FOUND")


async def test_add_transaction_overlimit_raises_102(db_session):
    """Posting over the credit limit raises reject code 102 (CBTRN02C L406-414).

    Over-limit uses the cycle running total plus the amount --
    ``tempBal = curr_cyc_credit - curr_cyc_debit + tranAmt`` -- and rejects when
    ``credit_limit < tempBal``. With limit 100 and cycle totals 0, a 200 amount
    yields ``tempBal = 200 > 100`` and must reject.
    """
    _acctId, cardNum = await SeedPostingGraph(
        db_session,
        creditLimit=Decimal("100.00"),
        cycCredit=Decimal("0.00"),
        cycDebit=Decimal("0.00"),
        expirationDate=date(2030, 1, 1),
    )
    service = TransactionService()
    create = BuildTransactionCreate(cardNum, Decimal("200.00"))
    with pytest.raises(PostingErrorClasses()) as raised:
        await service.AddTransaction(db_session, create)
    AssertPostingCode(raised.value, 102, "OVERLIMIT TRANSACTION")


async def test_add_transaction_after_expiration_raises_103(db_session):
    """A transaction received after expiration raises code 103 (CBTRN02C L415-421).

    Seeded well within the credit limit (so the over-limit edit passes) but with
    an account that expired in 2000; an original date in 2020 is after the
    expiration, so the expiry edit must reject.
    """
    _acctId, cardNum = await SeedPostingGraph(
        db_session,
        creditLimit=Decimal("999999.00"),
        expirationDate=date(2000, 1, 1),
    )
    service = TransactionService()
    create = BuildTransactionCreate(
        cardNum, Decimal("10.00"), origTs=datetime(2020, 1, 1, 0, 0, 0)
    )
    with pytest.raises(PostingErrorClasses()) as raised:
        await service.AddTransaction(db_session, create)
    AssertPostingCode(
        raised.value, 103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"
    )


async def test_add_transaction_account_not_found_raises_101(db_session):
    """An orphan cross-reference raises code 101 -- defensive under FK enforcement.

    Reject 101 requires a card cross-reference that resolves while its account is
    absent (the ``1500-B-LOOKUP-ACCT`` read-not-found). Under PostgreSQL foreign
    keys such an orphan row cannot be seeded, so the flush raises
    ``IntegrityError`` and the scenario is skipped (the code-101 contract is
    still pinned structurally in ``tests/unit/test_exceptions.py``). If foreign
    keys are not enforced, the posting lookup is driven directly and must reject.
    """
    orphanXref = CardXref(
        xref_card_num=UNKNOWN_CARD_NUM, cust_id="999999999", acct_id="99999999999"
    )
    db_session.add(orphanXref)
    try:
        await db_session.flush()
    except IntegrityError:
        await db_session.rollback()
        pytest.skip(
            "FK enforcement prevents orphan xref; code 101 covered in test_exceptions"
        )
    service = TransactionService()
    with pytest.raises(PostingErrorClasses()) as raised:
        await service._LoadAccountForPosting(db_session, UNKNOWN_CARD_NUM)
    AssertPostingCode(raised.value, 101, "ACCOUNT RECORD NOT FOUND")


def test_posting_code_109_distinct_from_101():
    """Reject 109 and 101 are DISTINCT codes with IDENTICAL description text.

    The runtime 109 path (``2800-UPDATE-ACCOUNT-REC`` ``REWRITE ... INVALID
    KEY``) is impractical to trigger in a unit test, so this locks the contract
    instead: 101 (read-not-found during validation) and 109 (rewrite-not-found
    during the balance update) must stay separate reason codes even though both
    read "ACCOUNT RECORD NOT FOUND" (AAP 0.7.3). Built defensively so it holds
    under either exceptions design.
    """
    accountNotFound = BuildPostingError(101)
    accountUpdateFailed = BuildPostingError(109)
    assert int(accountNotFound.code) == 101
    assert int(accountUpdateFailed.code) == 109
    assert accountNotFound.code != accountUpdateFailed.code
    assert accountNotFound.description == ACCOUNT_RECORD_NOT_FOUND_TEXT
    assert accountUpdateFailed.description == ACCOUNT_RECORD_NOT_FOUND_TEXT
    assert accountNotFound.description == accountUpdateFailed.description



# ===========================================================================
# Phase B -- successful posting (COTRN02C ADD-TRANSACTION + balance update).
# ===========================================================================
async def test_add_transaction_success_generates_zero_filled_tran_id(db_session):
    """A successful add assigns ``max(tran_id) + 1`` zero-filled to 16 chars.

    With an existing transaction id ``0000000000000005`` on the card, the next
    id is ``5 + 1 = 6`` right-justified into the 16-character ``TRAN-ID``
    (COTRN02C STARTBR/READPREV + ADD 1), i.e. ``0000000000000006``.
    """
    _acctId, cardNum = await SeedPostingGraph(db_session)
    await SeedTransaction(db_session, "0000000000000005", cardNum)
    service = TransactionService()
    create = BuildTransactionCreate(cardNum, Decimal("50.00"))
    result = await service.AddTransaction(db_session, create)
    assert result.tran_id == "0000000000000006"
    assert len(result.tran_id) == 16


async def test_add_transaction_first_tran_id_is_one_padded(db_session):
    """With no existing transactions the first id is ``1`` zero-filled to 16.

    When ``MAX(tran_id)`` is empty the service seeds the sequence at one, right
    justified into the 16-character key: ``0000000000000001``.
    """
    _acctId, cardNum = await SeedPostingGraph(db_session)
    service = TransactionService()
    create = BuildTransactionCreate(cardNum, Decimal("50.00"))
    result = await service.AddTransaction(db_session, create)
    assert result.tran_id == "0000000000000001"


async def test_add_transaction_updates_balance(db_session):
    """Posting adds the amount to the balance and the positive cycle credit.

    CBTRN02C ``2800-UPDATE-ACCOUNT-REC`` adds ``DALYTRAN-AMT`` to
    ``ACCT-CURR-BAL`` and, because the amount is non-negative, to
    ``ACCT-CURR-CYC-CREDIT`` (L547-553). Starting from a 1000.00 balance and a
    0.00 cycle credit, a +50.00 posting yields exactly 1050.00 and 50.00 --
    asserted as exact :class:`~decimal.Decimal` values.
    """
    acctId, cardNum = await SeedPostingGraph(
        db_session, currBal=Decimal("1000.00"), cycCredit=Decimal("0.00")
    )
    service = TransactionService()
    create = BuildTransactionCreate(cardNum, Decimal("50.00"))
    await service.AddTransaction(db_session, create)
    account = await db_session.get(Account, acctId)
    assert account.curr_bal == Decimal("1050.00")
    assert account.curr_cyc_credit == Decimal("50.00")


async def test_add_transaction_returns_decimal_amount(db_session):
    """The posted transaction's amount round-trips as an exact ``Decimal``.

    A 50.00 amount is stored to ``NUMERIC(11,2)`` and returned as an exact
    :class:`~decimal.Decimal` (never ``float``), asserted with ``==``.
    """
    _acctId, cardNum = await SeedPostingGraph(db_session)
    service = TransactionService()
    create = BuildTransactionCreate(cardNum, Decimal("50.00"))
    result = await service.AddTransaction(db_session, create)
    assert isinstance(result.tran_amt, Decimal)
    assert result.tran_amt == Decimal("50.00")


# ===========================================================================
# Phase C -- list (COTRN00C, CT00) and view (COTRN01C, CT01).
# ===========================================================================
async def test_list_transactions_returns_summaries(db_session):
    """The browse returns masked-card summaries with ``Decimal`` amounts.

    Ports the COTRN00C list: each summary row masks ``card_num`` to its last
    four digits (AAP 0.7.8) and carries an exact :class:`~decimal.Decimal`
    ``tran_amt``.
    """
    _acctId, cardNum = await SeedPostingGraph(db_session)
    await SeedTransaction(db_session, "0000000000000005", cardNum, tranAmt=Decimal("5.00"))
    await SeedTransaction(db_session, "0000000000000006", cardNum, tranAmt=Decimal("6.50"))
    service = TransactionService()
    result = await service.ListTransactions(
        db_session, PaginationParams(page=1, page_size=7)
    )
    assert len(result.items) >= 1
    for summaryItem in result.items:
        assert isinstance(summaryItem.tran_amt, Decimal)
        assert summaryItem.model_dump()["card_num"].startswith("*")


async def test_get_transaction_found(db_session):
    """Viewing a seeded id returns it with an exact ``Decimal`` amount (CT01).

    Ports COTRN01C READ-TRANSACT-FILE: the requested id is returned as a
    ``TransactionRead`` whose ``tran_amt`` is an exact
    :class:`~decimal.Decimal`.
    """
    _acctId, cardNum = await SeedPostingGraph(db_session)
    await SeedTransaction(db_session, "0000000000000005", cardNum, tranAmt=Decimal("5.00"))
    service = TransactionService()
    tran = await service.GetTransaction(db_session, "0000000000000005")
    assert tran.tran_id == "0000000000000005"
    assert isinstance(tran.tran_amt, Decimal)


async def test_get_transaction_empty_raises_validation(db_session):
    """A blank transaction id is rejected before any lookup (COTRN01C L149).

    The verbatim COTRN01C edit "Tran ID can NOT be empty..." is raised as a
    :class:`DomainValidationError` with no database read.
    """
    service = TransactionService()
    with pytest.raises(DomainValidationError) as raised:
        await service.GetTransaction(db_session, "")
    assert MessageOf(raised.value) == "Tran ID can NOT be empty..."


async def test_get_transaction_absent_raises_not_found(db_session):
    """An absent id raises the verbatim not-found message (COTRN01C L285).

    A non-blank id that matches no row surfaces
    :class:`NotFoundError` "Transaction ID NOT found...".
    """
    service = TransactionService()
    with pytest.raises(NotFoundError) as raised:
        await service.GetTransaction(db_session, "0000000000000999")
    assert MessageOf(raised.value) == "Transaction ID NOT found..."

