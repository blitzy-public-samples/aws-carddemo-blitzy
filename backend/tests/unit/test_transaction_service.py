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
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal

import pytest
from sqlalchemy.exc import SQLAlchemyError

from app.core.exceptions import DomainValidationError, NotFoundError
from app.models import (
    Account,
    Card,
    CardXref,
    Customer,
    TranCategoryBalance,
    Transaction,
)
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
@dataclass(frozen=True)
class PostingGraphSpec:
    """Typed scenario object for :func:`SeedPostingGraph` (Ochs N-02).

    Groups the eight Account/Customer/Card attributes that the CBTRN02C posting
    edits depend on into a single value object, so the seeding helper takes only
    ``(session, spec)`` -- keeping it within the Ochs four-parameter limit
    (0.8.2). Every field defaults to the canonical happy-path scenario; a test
    overrides only the fields its scenario cares about, e.g.
    ``PostingGraphSpec(creditLimit=Decimal("100.00"))``.

    Attributes:
        acctId: 11-character account id (primary key, zeros preserved).
        custId: 9-character customer id.
        cardNum: 16-character card number (cross-reference + posting key).
        creditLimit: Account credit limit used by the over-limit edit (code 102).
        cycCredit: Current-cycle credit total used by the over-limit edit.
        cycDebit: Current-cycle debit total used by the over-limit edit.
        currBal: Current balance the posting update increments.
        expirationDate: Account expiration date used by the expiry edit (code 103).
    """

    acctId: str = "00000000030"
    custId: str = "000000030"
    cardNum: str = "4000000000000030"
    creditLimit: Decimal = Decimal("5000.00")
    cycCredit: Decimal = Decimal("0.00")
    cycDebit: Decimal = Decimal("0.00")
    currBal: Decimal = Decimal("1000.00")
    expirationDate: date = date(2030, 1, 1)


@dataclass(frozen=True)
class TransactionSpec:
    """Typed scenario object for :func:`SeedTransaction` (Ochs N-02).

    Groups the posted-transaction attributes into one value object so the
    seeding helper takes only ``(session, spec)`` (Ochs four-parameter limit,
    0.8.2). ``tranId`` and ``cardNum`` are required; the code/amount fields
    default to the canonical seed values.

    Attributes:
        tranId: The 16-character transaction id (primary key).
        cardNum: The owning 16-character card number (foreign key).
        tranAmt: The exact :class:`~decimal.Decimal` transaction amount.
        tranTypeCd: The 2-character transaction type code (numeric).
        tranCatCd: The 4-digit transaction category code.
    """

    tranId: str
    cardNum: str
    tranAmt: Decimal = Decimal("5.00")
    tranTypeCd: str = "01"
    tranCatCd: str = "0005"


async def SeedPostingGraph(session, spec=None):
    """Seed a fully referential Account/Customer/Card/CardXref posting graph.

    Populates every NOT-NULL column verified against the real ORM models so the
    row set flushes cleanly under PostgreSQL foreign-key enforcement. The
    monetary and cycle fields are exact :class:`~decimal.Decimal` values (taken
    from ``spec``) so the CBTRN02C over-limit arithmetic
    (``curr_cyc_credit - curr_cyc_debit + amt`` versus ``credit_limit``) is
    reproduced precisely by the caller's scenario.

    Args:
        session: The active async database session (``db_session`` fixture).
        spec: The :class:`PostingGraphSpec` scenario; ``None`` uses the
            canonical happy-path defaults.

    Returns:
        A ``(acctId, cardNum)`` tuple for the seeded account and card.
    """
    spec = spec or PostingGraphSpec()
    account = Account(
        acct_id=spec.acctId, active_status="Y", curr_bal=spec.currBal,
        credit_limit=spec.creditLimit, cash_credit_limit=Decimal("1000.00"),
        curr_cyc_credit=spec.cycCredit, curr_cyc_debit=spec.cycDebit,
        open_date=date(2020, 1, 1), expiration_date=spec.expirationDate,
        reissue_date=date(2024, 1, 1), addr_zip="12345",
        # group_id NULL: mirrors the seed quirk and is incidental here; NULL is
        # exempt from the M-16 accounts.group_id foreign key.
        group_id=None,
    )
    customer = Customer(
        cust_id=spec.custId, first_name="JANE", last_name="DOE",
        addr_line_1="1 MAIN ST", addr_state_cd="CA", addr_country_cd="USA",
        addr_zip="12345", phone_num_1="1234567890", ssn="123456789",
        govt_issued_id="DL123", date_of_birth=date(1980, 5, 5),
        pri_card_holder_ind="Y", fico_credit_score=750,
    )
    card = Card(
        card_num=spec.cardNum, acct_id=spec.acctId,
        embossed_name="JANE DOE", expiration_date=spec.expirationDate,
        active_status="Y",
    )
    xref = CardXref(
        xref_card_num=spec.cardNum, cust_id=spec.custId, acct_id=spec.acctId,
    )
    session.add_all([account, customer, card, xref])
    await session.flush()
    return spec.acctId, spec.cardNum


async def SeedTransaction(session, spec):
    """Insert one posted ``Transaction`` row on an already-seeded card.

    The ``card_num`` foreign key must reference a card seeded by
    :func:`SeedPostingGraph`. ``status`` is left to its model default
    (``POSTED``), matching the CVTRA05Y posted ledger the browse screens read.

    Args:
        session: The active async database session.
        spec: The :class:`TransactionSpec` describing the row to insert.

    Returns:
        The inserted (session-attached) :class:`~app.models.transaction.Transaction`.
    """
    transaction = Transaction(
        tran_id=spec.tranId, tran_type_cd=spec.tranTypeCd,
        tran_cat_cd=spec.tranCatCd, tran_source="POS",
        tran_desc="SEED TRANSACTION", tran_amt=spec.tranAmt,
        merchant_id="000000001", merchant_name="SEED MERCHANT",
        merchant_city="SEED CITY", merchant_zip="12345",
        card_num=spec.cardNum, orig_ts=DEFAULT_ORIG_TS, proc_ts=DEFAULT_PROC_TS,
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


# ---------------------------------------------------------------------------
# Fault-seam repositories (M-19). Reject codes 101 and 109 guard runtime
# "record vanished" conditions -- the VSAM ``INVALID KEY`` branches of
# ``1500-B-LOOKUP-ACCT`` (101) and ``2800-UPDATE-ACCOUNT-REC`` (109) -- that
# fire when a cross-reference resolves but its account row is gone, or when the
# account disappears between the validation read and the balance rewrite.
# PostgreSQL foreign keys make those orphan states UNSEEDABLE as data, and
# DISABLING the keys to fake them would reintroduce the C-01 hazard and make the
# whole suite untrustworthy. Instead these stubs inject the exact repository
# return the COBOL ``INVALID KEY`` branch observes -- a missing row (``None``) or
# a database error -- so the REAL production methods (``_LoadAccountForPosting``
# / ``_PostToAccount``) execute their genuine runtime mappings deterministically.
# No database is touched, so the tests using them are pure units.
# ---------------------------------------------------------------------------
class _StubXrefRepository:
    """Cross-ref repository stand-in returning a fixed record for the posting read."""

    def __init__(self, xrefRecord):
        self._xrefRecord = xrefRecord

    async def GetByCardNum(self, session, cardNum):
        """Return the pre-set cross-reference record (or ``None`` -> code 100)."""
        return self._xrefRecord


class _StubAccountRepository:
    """Account repository stand-in driving the 101 and 109 INVALID-KEY branches.

    ``GetByAcctId`` feeds the code-101 validation read (``None`` -> 101);
    ``GetForUpdate`` feeds the code-109 balance-update read (``None`` for a
    vanished account, or ``forUpdateError`` raised to simulate a rewrite
    failure); ``Update`` records that the successful rewrite path ran.
    """

    def __init__(self, getResult=None, forUpdateResult=None, forUpdateError=None):
        self._getResult = getResult
        self._forUpdateResult = forUpdateResult
        self._forUpdateError = forUpdateError
        self.updateCalled = False

    async def GetByAcctId(self, session, acctId):
        """Return the pre-set validation-read result (``None`` drives code 101)."""
        return self._getResult

    async def GetForUpdate(self, session, acctId):
        """Return/raise the pre-set balance-update result (drives code 109)."""
        if self._forUpdateError is not None:
            raise self._forUpdateError
        return self._forUpdateResult

    async def Update(self, session, account):
        """Record that the (successful) balance-rewrite path ran."""
        self.updateCalled = True


def BuildInMemoryAccount(creditLimit, cycCredit, cycDebit, expirationDate):
    """Build a detached in-memory ``Account`` for pure-unit posting-edit tests.

    Only the fields the CBTRN02C over-limit and expiration edits read are set;
    the object is never added to a session, so no database is required. This
    keeps the ``_RunPostingValidation`` precedence tests pure (no PostgreSQL).

    Args:
        creditLimit: Credit limit for the over-limit edit (code 102).
        cycCredit: Current-cycle credit total (over-limit running total).
        cycDebit: Current-cycle debit total (over-limit running total).
        expirationDate: Account expiration date for the expiry edit (code 103).

    Returns:
        A detached :class:`~app.models.account.Account`.
    """
    return Account(
        acct_id="00000000099",
        credit_limit=creditLimit,
        curr_cyc_credit=cycCredit,
        curr_cyc_debit=cycDebit,
        expiration_date=expirationDate,
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
        PostingGraphSpec(
            creditLimit=Decimal("100.00"),
            cycCredit=Decimal("0.00"),
            cycDebit=Decimal("0.00"),
            expirationDate=date(2030, 1, 1),
        ),
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
        PostingGraphSpec(
            creditLimit=Decimal("999999.00"),
            expirationDate=date(2000, 1, 1),
        ),
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


async def test_load_account_for_posting_account_absent_raises_101():
    """Reject 101 executes at runtime when a resolved cross-ref has no account.

    ``1500-B-LOOKUP-ACCT``'s ``READ ACCOUNT-FILE ... INVALID KEY`` (code 101)
    fires when a card cross-reference resolves but its account row is gone (a
    concurrent delete / race). PostgreSQL foreign keys make that orphan state
    UNSEEDABLE as data, and disabling the keys to fake it would reintroduce the
    C-01 hazard. A fault-seam account repository therefore returns ``None`` for
    the account read while the cross-reference resolves, so the REAL
    ``_LoadAccountForPosting`` runs its genuine code-101 branch -- no constraint
    is weakened and no database is touched. Distinct from code 109 despite the
    identical description (AAP 0.7.3).
    """
    service = TransactionService()
    service.xrefRepository = _StubXrefRepository(
        CardXref(xref_card_num=UNKNOWN_CARD_NUM, cust_id="000000099",
                 acct_id="00000000099")
    )
    service.accountRepository = _StubAccountRepository(getResult=None)
    with pytest.raises(PostingErrorClasses()) as raised:
        await service._LoadAccountForPosting(None, UNKNOWN_CARD_NUM)
    AssertPostingCode(raised.value, 101, "ACCOUNT RECORD NOT FOUND")


async def test_load_account_for_posting_unknown_xref_raises_100():
    """Reject 100 executes at runtime when the posting cross-ref read misses.

    ``1500-A-LOOKUP-XREF``'s ``READ XREF-FILE ... INVALID KEY`` (code 100) fires
    when the posting re-read finds no cross-reference for the card. A fault-seam
    cross-ref repository returns ``None`` so the REAL ``_LoadAccountForPosting``
    runs its genuine code-100 branch (a pure-unit companion to the DB-backed
    ``test_add_transaction_unknown_card_raises_100``).
    """
    service = TransactionService()
    service.xrefRepository = _StubXrefRepository(None)
    service.accountRepository = _StubAccountRepository(getResult=None)
    with pytest.raises(PostingErrorClasses()) as raised:
        await service._LoadAccountForPosting(None, UNKNOWN_CARD_NUM)
    AssertPostingCode(raised.value, 100, "INVALID CARD NUMBER FOUND")


async def test_post_to_account_vanished_account_raises_109():
    """Reject 109 executes at runtime when the balance-update read finds nothing.

    ``2800-UPDATE-ACCOUNT-REC``'s ``REWRITE ... INVALID KEY`` (code 109) fires
    when the account read-for-update returns nothing -- the row was deleted after
    validation. A fault-seam repository returns ``None`` from ``GetForUpdate`` so
    the REAL ``_PostToAccount`` raises its genuine code-109 mapping. Distinct
    from code 101 despite the identical description text (AAP 0.7.3).
    """
    service = TransactionService()
    service.accountRepository = _StubAccountRepository(forUpdateResult=None)
    with pytest.raises(PostingErrorClasses()) as raised:
        await service._PostToAccount(None, "00000000099", Decimal("10.00"))
    AssertPostingCode(raised.value, 109, "ACCOUNT RECORD NOT FOUND")


async def test_post_to_account_update_error_raises_109():
    """Reject 109 maps a database error during the balance rewrite, chaining it.

    Any ``SQLAlchemyError`` from the read-for-update / update is the modern
    equivalent of the legacy ``REWRITE ... INVALID KEY`` and must surface as code
    109 with the failing error preserved as ``__cause__`` (no bare re-raise, no
    swallowed error).
    """
    service = TransactionService()
    updateError = SQLAlchemyError("simulated rewrite failure")
    service.accountRepository = _StubAccountRepository(forUpdateError=updateError)
    with pytest.raises(PostingErrorClasses()) as raised:
        await service._PostToAccount(None, "00000000099", Decimal("10.00"))
    AssertPostingCode(raised.value, 109, "ACCOUNT RECORD NOT FOUND")
    assert raised.value.__cause__ is updateError


def test_run_posting_validation_overlimit_only_raises_102():
    """Over-limit but NOT expired -> code 102 (the over-limit edit still fires).

    Confirms the code-102 branch stays reachable after the precedence reorder
    that makes 103 win when both edits fail (see the precedence test below).
    """
    service = TransactionService()
    account = BuildInMemoryAccount(
        creditLimit=Decimal("100.00"), cycCredit=Decimal("0.00"),
        cycDebit=Decimal("0.00"), expirationDate=date(2030, 1, 1),
    )
    with pytest.raises(PostingErrorClasses()) as raised:
        service._RunPostingValidation(account, Decimal("200.00"), DEFAULT_ORIG_TS)
    AssertPostingCode(raised.value, 102, "OVERLIMIT TRANSACTION")


def test_run_posting_validation_expired_only_raises_103():
    """Expired but within the credit limit -> code 103."""
    service = TransactionService()
    account = BuildInMemoryAccount(
        creditLimit=Decimal("999999.00"), cycCredit=Decimal("0.00"),
        cycDebit=Decimal("0.00"), expirationDate=date(2000, 1, 1),
    )
    with pytest.raises(PostingErrorClasses()) as raised:
        service._RunPostingValidation(
            account, Decimal("10.00"), datetime(2020, 1, 1, 0, 0, 0)
        )
    AssertPostingCode(
        raised.value, 103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"
    )


def test_run_posting_validation_overlimit_and_expired_prefers_103():
    """BOTH over-limit AND expired -> code 103 wins (CBTRN02C last-write-wins).

    ``1500-B-LOOKUP-ACCT`` sets reason 102 then, in a SEPARATE sequential ``IF``,
    sets reason 103 -- the second ``MOVE`` overwrites the first, so a posting that
    is simultaneously over-limit and past expiration is rejected as 103, not 102
    (AAP 0.8.1 exact-parity). This pins that precedence on the online add path's
    ``_RunPostingValidation`` and keeps it reconcilable with the batch job's
    ``_CheckAccountLimits``. The scenario is over-limit (limit 100, amount 200)
    AND expired (expired 2000, original date 2020).
    """
    service = TransactionService()
    account = BuildInMemoryAccount(
        creditLimit=Decimal("100.00"), cycCredit=Decimal("0.00"),
        cycDebit=Decimal("0.00"), expirationDate=date(2000, 1, 1),
    )
    with pytest.raises(PostingErrorClasses()) as raised:
        service._RunPostingValidation(
            account, Decimal("200.00"), datetime(2020, 1, 1, 0, 0, 0)
        )
    AssertPostingCode(
        raised.value, 103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"
    )


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
    await SeedTransaction(db_session, TransactionSpec("0000000000000005", cardNum))
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
        db_session,
        PostingGraphSpec(currBal=Decimal("1000.00"), cycCredit=Decimal("0.00")),
    )
    service = TransactionService()
    create = BuildTransactionCreate(cardNum, Decimal("50.00"))
    await service.AddTransaction(db_session, create)
    account = await db_session.get(Account, acctId)
    assert account.curr_bal == Decimal("1050.00")
    assert account.curr_cyc_credit == Decimal("50.00")


async def test_add_transaction_creates_category_balance(db_session):
    """Posting updates BOTH the account balance and a NEW category balance (F-1).

    The online add path must perform CBTRN02C ``2700-UPDATE-TCATBAL`` in addition
    to ``2800-UPDATE-ACCOUNT-REC``. With a fresh posting graph (no seeded category
    balances), a +40.00 type-01/cat-0005 posting must (a) add 40.00 to
    ``accounts.curr_bal`` AND (b) create exactly one ``tran_category_balance`` row
    for ``(acct, 01, 0005)`` seeded at 40.00 (``2700-A-CREATE-TCATBAL-REC``), both
    as exact :class:`~decimal.Decimal` values inside one committed unit-of-work.
    This is the unit-level analog of finding F-1.
    """
    acctId, cardNum = await SeedPostingGraph(
        db_session, PostingGraphSpec(currBal=Decimal("200.00")),
    )
    service = TransactionService()
    create = BuildTransactionCreate(cardNum, Decimal("40.00"))
    await service.AddTransaction(db_session, create)
    account = await db_session.get(Account, acctId)
    assert account.curr_bal == Decimal("240.00")
    balanceRow = await db_session.get(TranCategoryBalance, (acctId, "01", "0005"))
    assert balanceRow is not None
    assert balanceRow.balance == Decimal("40.00")


async def test_add_transaction_accumulates_category_balance(db_session):
    """Posting ADDs the amount to an existing category balance (2700-B-UPDATE).

    Ports CBTRN02C ``2700-B-UPDATE-TCATBAL-REC``: when the
    ``tran_category_balance`` row already exists, posting ADDs ``tran_amt`` to the
    accumulated balance rather than creating a second row. Starting from a seeded
    100.00 balance for ``(acct, 01, 0005)``, a +50.00 posting yields exactly
    150.00 in that SAME single row (the composite primary key admits no duplicate).
    """
    acctId, cardNum = await SeedPostingGraph(db_session)
    db_session.add(
        TranCategoryBalance(
            acct_id=acctId, tran_type_cd="01", tran_cat_cd="0005",
            balance=Decimal("100.00"),
        )
    )
    await db_session.flush()
    service = TransactionService()
    create = BuildTransactionCreate(cardNum, Decimal("50.00"))
    await service.AddTransaction(db_session, create)
    balanceRow = await db_session.get(TranCategoryBalance, (acctId, "01", "0005"))
    assert balanceRow is not None
    assert balanceRow.balance == Decimal("150.00")


async def test_add_transaction_negative_amount_decrements_category_balance(db_session):
    """A negative posting subtracts from the category balance (signed ADD).

    CBTRN02C ``2700`` performs an unconditional ``ADD DALYTRAN-AMT TO
    TRAN-CAT-BAL`` -- the amount is accumulated WITH its sign, so a -30.00 posting
    decrements the running category balance, mirroring the signed account-balance
    arithmetic (a negative amount routes to the cycle-debit total). Starting from
    a seeded 100.00 balance, a -30.00 posting yields exactly 70.00.
    """
    acctId, cardNum = await SeedPostingGraph(db_session)
    db_session.add(
        TranCategoryBalance(
            acct_id=acctId, tran_type_cd="01", tran_cat_cd="0005",
            balance=Decimal("100.00"),
        )
    )
    await db_session.flush()
    service = TransactionService()
    create = BuildTransactionCreate(cardNum, Decimal("-30.00"))
    await service.AddTransaction(db_session, create)
    balanceRow = await db_session.get(TranCategoryBalance, (acctId, "01", "0005"))
    assert balanceRow is not None
    assert balanceRow.balance == Decimal("70.00")


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
    await SeedTransaction(
        db_session, TransactionSpec("0000000000000005", cardNum, tranAmt=Decimal("5.00")),
    )
    await SeedTransaction(
        db_session, TransactionSpec("0000000000000006", cardNum, tranAmt=Decimal("6.50")),
    )
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
    await SeedTransaction(
        db_session, TransactionSpec("0000000000000005", cardNum, tranAmt=Decimal("5.00")),
    )
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
