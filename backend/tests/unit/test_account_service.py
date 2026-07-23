# Unit tests for app.services.account_service.AccountService
# Traceability: app/cbl/COACTVWC.cbl (CAVW), app/cbl/COACTUPC.cbl (CAUP optimistic lock)
"""Unit tests for :class:`app.services.account_service.AccountService`.

Exercises the modern port of the two legacy CICS online programs that read and
maintain an account:

* ``COACTVWC`` (transaction ``CAVW``) -- the read-only *view* screen, verified by
  the ``GetAccount`` tests below (happy path with exact ``Decimal`` money, the
  embedded masked customer, the two account-filter edits, and the not-found
  path).
* ``COACTUPC`` (transaction ``CAUP``) -- the *update* screen whose READ-UPDATE ->
  REWRITE cycle carried an optimistic lock (paragraph ``9700-CHECK-CHANGE-IN-REC``
  compared the freshly re-read record against the before-image the operator had
  fetched, rejecting the write when another unit-of-work had changed the record
  in the interim). The ``UpdateAccount`` tests verify both the happy path and
  that stale-read conflict (AAP section 0.7.4).

Design notes:

* Money is asserted as exact :class:`decimal.Decimal` with ``==`` against
  ``Decimal("...")`` string literals -- never ``float`` and never
  ``pytest.approx`` -- because binary floating point is a regulatory
  numeric-parity failure for currency (AAP section 0.7.1).
* Each test seeds its own self-contained, foreign-key-safe object graph through
  the shared ``db_session`` fixture (defined in ``backend/tests/conftest.py``),
  so the service reads back exactly what the test wrote and no test depends on
  another test's state.
* ``asyncio_mode = "auto"`` is configured in ``backend/pyproject.toml``, so the
  ``async def test_*`` coroutines run without an explicit marker.

Ochs naming (AAP section 0.8.2 / 0.8.3): the module and test-function names stay
snake_case (the pytest framework contract), non-test helper functions use
PascalCase (:func:`MessageOf`, :func:`SeedAccountGraph`, :func:`SeededBeforeImage`),
local variables use camelCase, and module constants are ALL_UPPERCASE.
"""

import pytest
from decimal import Decimal
from datetime import date

from sqlalchemy import select

from app.services.account_service import AccountService
from app.schemas.account import AccountUpdate, AccountBeforeImage
from app.core.exceptions import NotFoundError, DomainValidationError
import app.core.exceptions as exceptionsModule  # for defensive ConflictError/OptimisticLockError
from app.models import Account, Customer, Card, CardXref

# ---------------------------------------------------------------------------
# Golden test identifiers (ALL_UPPERCASE constants per the Ochs Rule). The
# account id is a non-zero 11-digit string, the customer id a 9-digit string and
# the card number a 16-digit string, mirroring the fixed-length VSAM key widths
# so leading zeros are preserved exactly.
# ---------------------------------------------------------------------------
SEED_ACCT_ID = "00000000010"
SEED_CUST_ID = "000000010"
SEED_CARD_NUM = "4000000000000010"
SEED_SSN = "123456789"

# Verbatim operator messages copied from the originating COBOL 88-level VALUE
# clauses (COACTVWC / COACTUPC); asserted character-for-character so a behavior
# change in the service is caught immediately.
MSG_ACCT_NOT_PROVIDED = "Account number not provided"
MSG_ACCT_NON_ZERO_11 = "Account number must be a non zero 11 digit number"
MSG_RECORD_CHANGED = "Record changed by some one else. Please review"


def MessageOf(error):
    """Return the human-readable message carried by a domain exception.

    Every CardDemo domain exception derives from ``CardDemoError``, which stores
    the text on a ``message`` attribute (and forwards it to ``Exception`` so
    ``str(error)`` matches). This helper reads ``message`` when present and falls
    back to ``str(error)`` otherwise, so a test can assert the verbatim legacy
    text regardless of which concrete subclass was raised.

    Args:
        error: The caught exception instance.

    Returns:
        The exception's message text.
    """
    return getattr(error, "message", None) or str(error)


async def SeedAccountGraph(session, acctId=SEED_ACCT_ID, custId=SEED_CUST_ID,
                           cardNum=SEED_CARD_NUM):
    """Seed a complete, foreign-key-safe account graph for one test.

    Inserts, in foreign-key-safe order, an account, its owning customer, a card
    for that account, and the card cross-reference that links card -> customer ->
    account (the modern equivalent of the ``CXACAIX`` account alternate index the
    view program reads). Every NOT-NULL column of each model is populated; the
    rows are written through the caller's ``db_session`` and flushed (not
    committed) so the service under test reads back exactly what was seeded.

    Insertion is staged with an explicit ``flush`` after each foreign-key tier
    (account + customer, then card, then cross-reference), mirroring the proven
    ``conftest.seed_cards_customers_xref`` ordering so no insert can precede the
    row it references.

    Args:
        session: The active async session (the ``db_session`` fixture).
        acctId: The 11-digit account id to seed.
        custId: The 9-digit customer id to seed.
        cardNum: The 16-digit card number to seed.

    Returns:
        The seeded :class:`app.models.account.Account` instance.
    """
    account = Account(
        acct_id=acctId,
        active_status="Y",
        curr_bal=Decimal("1000.00"),
        credit_limit=Decimal("5000.00"),
        cash_credit_limit=Decimal("2000.00"),
        curr_cyc_credit=Decimal("0.00"),
        curr_cyc_debit=Decimal("0.00"),
        open_date=date(2020, 1, 1),
        expiration_date=date(2027, 1, 1),
        reissue_date=date(2024, 1, 1),
        addr_zip="12345",
        group_id="DEFAULT",
    )
    customer = Customer(
        cust_id=custId,
        first_name="JANE",
        last_name="DOE",
        addr_line_1="1 MAIN ST",
        addr_state_cd="CA",
        addr_country_cd="USA",
        addr_zip="12345",
        phone_num_1="1234567890",
        ssn=SEED_SSN,
        govt_issued_id="DL123",
        date_of_birth=date(1980, 5, 5),
        pri_card_holder_ind="Y",
        fico_credit_score=750,
    )
    session.add_all([account, customer])
    await session.flush()
    card = Card(
        card_num=cardNum,
        acct_id=acctId,
        cvv_cd="123",
        embossed_name="JANE DOE",
        expiration_date=date(2027, 1, 1),
        active_status="Y",
    )
    session.add(card)
    await session.flush()
    xref = CardXref(xref_card_num=cardNum, cust_id=custId, acct_id=acctId)
    session.add(xref)
    await session.flush()
    return account


def SeededBeforeImage(**overrides):
    """Build the client before-image echoing the seeded account's fields.

    Reproduces the ``COACTUPC`` optimistic-lock token: the values the operator
    last read for the editable account fields. By default it mirrors the six
    required fields of :func:`SeedAccountGraph` exactly, so the service's
    ``9700`` change-check finds no divergence and the update proceeds. Passing a
    keyword override (for example ``curr_bal=Decimal("2500.00")``) simulates a
    stale client read whose value no longer matches the locked row, which the
    service must reject as a concurrent-modification conflict.

    Args:
        **overrides: Field values to diverge from the seeded baseline.

    Returns:
        A validated :class:`app.schemas.account.AccountBeforeImage`.
    """
    baseline = {
        "active_status": "Y",
        "curr_bal": Decimal("1000.00"),
        "credit_limit": Decimal("5000.00"),
        "cash_credit_limit": Decimal("2000.00"),
        "curr_cyc_credit": Decimal("0.00"),
        "curr_cyc_debit": Decimal("0.00"),
    }
    baseline.update(overrides)
    return AccountBeforeImage(**baseline)


# ===========================================================================
# Phase A -- GetAccount happy path & exact Decimal money (COACTVWC, tx CAVW).
# ===========================================================================
async def test_get_account_returns_detail_with_decimal_money(db_session):
    """GetAccount returns the account detail with exact ``Decimal`` money."""
    await SeedAccountGraph(db_session)
    service = AccountService()
    detail = await service.GetAccount(db_session, SEED_ACCT_ID)
    assert detail.acct_id == SEED_ACCT_ID
    assert isinstance(detail.curr_bal, Decimal)
    assert detail.curr_bal == Decimal("1000.00")
    assert isinstance(detail.credit_limit, Decimal)
    assert detail.credit_limit == Decimal("5000.00")


async def test_get_account_embeds_customer_with_masked_ssn(db_session):
    """GetAccount embeds the owning customer with its SSN masked."""
    await SeedAccountGraph(db_session)
    service = AccountService()
    detail = await service.GetAccount(db_session, SEED_ACCT_ID)
    assert detail.customer is not None
    assert detail.customer.cust_id == SEED_CUST_ID
    dumped = detail.customer.model_dump()
    assert dumped["ssn"] != SEED_SSN
    assert dumped["ssn"].endswith("6789")


# ===========================================================================
# Phase B -- GetAccount filter edits & not-found (COACTVWC 2210 / 9200-9400).
# ===========================================================================
async def test_get_account_empty_id_raises_validation(db_session):
    """A blank account filter is rejected as 'not provided' (2210-EDIT-ACCOUNT)."""
    service = AccountService()
    with pytest.raises(DomainValidationError) as excInfo:
        await service.GetAccount(db_session, "")
    assert MessageOf(excInfo.value) == MSG_ACCT_NOT_PROVIDED


async def test_get_account_bad_format_raises_validation(db_session):
    """A non-eleven-digit / zero filter fails the non-zero-11-digit edit."""
    service = AccountService()
    with pytest.raises(DomainValidationError) as excInfo:
        await service.GetAccount(db_session, "0")
    assert MessageOf(excInfo.value) == MSG_ACCT_NON_ZERO_11


async def test_get_account_absent_raises_not_found(db_session):
    """A well-formed but absent account id raises ``NotFoundError``."""
    service = AccountService()
    with pytest.raises(NotFoundError):
        await service.GetAccount(db_session, "99999999999")


# ===========================================================================
# Phase C -- UpdateAccount happy path (COACTUPC 9600 REWRITE + commit).
# ===========================================================================
async def test_update_account_applies_change(db_session):
    """UpdateAccount applies and persists an editable-field change as ``Decimal``."""
    await SeedAccountGraph(db_session)
    service = AccountService()
    accountUpdate = AccountUpdate(
        before_image=SeededBeforeImage(),
        credit_limit=Decimal("6000.00"),
    )
    updated = await service.UpdateAccount(db_session, SEED_ACCT_ID, accountUpdate)
    assert isinstance(updated.credit_limit, Decimal)
    assert updated.credit_limit == Decimal("6000.00")
    # Confirm the REWRITE + commit actually persisted to the datastore: re-read
    # the row and assert the new limit is present (not just on the returned DTO).
    persisted = (
        await db_session.execute(
            select(Account).where(Account.acct_id == SEED_ACCT_ID)
        )
    ).scalar_one()
    assert persisted.credit_limit == Decimal("6000.00")


# ===========================================================================
# Phase D -- UpdateAccount optimistic-lock conflict (COACTUPC 9700, AAP 0.7.4).
# ===========================================================================
async def test_update_account_concurrent_change_raises_conflict(db_session):
    """A stale before-image (record changed underneath) is rejected as a conflict."""
    await SeedAccountGraph(db_session)
    service = AccountService()
    # The client's before-image believes curr_bal is 2500.00, but the locked
    # row holds the seeded 1000.00 -> someone else changed it -> reject (9700).
    staleUpdate = AccountUpdate(
        before_image=SeededBeforeImage(curr_bal=Decimal("2500.00")),
        credit_limit=Decimal("6000.00"),
    )
    conflictTypes = (
        getattr(exceptionsModule, "OptimisticLockError", exceptionsModule.ConflictError),
        exceptionsModule.ConflictError,
    )
    with pytest.raises(conflictTypes) as excInfo:
        await service.UpdateAccount(db_session, SEED_ACCT_ID, staleUpdate)
    assert MessageOf(excInfo.value) == MSG_RECORD_CHANGED
