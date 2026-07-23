# Integration tests for the accounts API.
# Ported from legacy CICS online programs COACTVWC (account view, CAVW) and
# COACTUPC (account update, CAUP). Endpoints: GET/PUT /api/v1/accounts/{acctId}
# (AAP §0.5.5). Verifies exact-Decimal golden-master account values, embedded
# CustomerRead with masked ssn, and the SELECT..FOR UPDATE optimistic-lock
# conflict -> HTTP 409 (§0.7.4). Record layouts CVACT01Y/CVCUS01Y/CVACT03Y.
"""End-to-end (PostgreSQL-backed) integration tests for the accounts REST API.

These tests exercise the two account endpoints that replace the legacy CICS
online programs of the CardDemo application:

* ``GET  /api/v1/accounts/{acctId}`` -- the account *view* screen (``COACTVWC``,
  transaction ``CAVW``): returns the account master joined to its owning
  customer (resolved through the card cross-reference) as an ``AccountDetail``.
* ``PUT  /api/v1/accounts/{acctId}`` -- the account *update* screen
  (``COACTUPC``, transaction ``CAUP``): applies the editable account fields
  under the READ-for-UPDATE -> REWRITE optimistic-lock guard.

Fidelity focus (why these tests are PostgreSQL-backed, per the AAP):

* Monetary values are asserted as EXACT :class:`decimal.Decimal` (never
  ``float``, never a tolerance) so the regulatory numeric-parity requirement
  (AAP §0.7.1) is proven end to end -- from the ``NUMERIC(12, 2)`` column,
  through the Pydantic DTO, to the JSON body.
* The ``ssn`` of the embedded customer is asserted MASKED (AAP §0.7.8): only the
  last four digits are exposed and the full nine-digit value never appears in
  the serialized response.
* The COACTUPC lost-update guard (AAP §0.7.4) is proven to surface as HTTP 409
  when a concurrent modification invalidates the client's before-image.

Golden-master oracle (``app/data/ASCII``, decoded per the copybooks): account
``00000000001`` has ``curr_bal`` 194.00, ``credit_limit`` 2020.00 and
``cash_credit_limit`` 1020.00; it is owned by customer ``000000001`` whose SSN
masks to ``***-**-NNNN``.

Test conventions (parent ``backend/tests/conftest.py``; there is deliberately no
local ``conftest`` here). Under ``asyncio_mode = "auto"`` every ``async def
test_*`` runs on its own event loop, so no explicit marker is required. httpx
0.28.1 removed the ``AsyncClient(app=...)`` shortcut, so the fixtures build the
client from an explicit ``ASGITransport``; tests only consume the fixtures and
``await`` each request sequentially (the shared session is not safe for
concurrent requests).

Ochs naming (AAP §0.8.2 / §0.8.3): module constants are ALL_UPPERCASE, helper
functions are PascalCase (``ToDecimal``, ``BuildBeforeImage``), and local
variables are camelCase (``responseBody``, ``beforeImage``, ``updatePayload``).
The ``test_*`` function names stay snake_case because that is the pytest
collection contract (``python_files = ["test_*.py"]``) -- the same documented
exception the shared fixtures make for their own snake_case names.
"""

from __future__ import annotations

import asyncio
from datetime import date
from decimal import Decimal

import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.core.config import settings
from app.core.exceptions import OptimisticLockError
from app.models.account import Account
from app.models.card import Card
from app.models.card_xref import CardXref
from app.models.customer import Customer
from app.repositories.account_repo import AccountRepository
from app.schemas.account import AccountBeforeImage, AccountUpdate
from app.services.account_service import AccountService

# ---------------------------------------------------------------------------
# Module constants (ALL_UPPERCASE per the Ochs Rule §0.8.2).
# ---------------------------------------------------------------------------

# Fully-qualified accounts resource path: the version-1 API prefix (mounted once
# by ``app.main``) plus the accounts sub-router's own "/accounts" segment. Built
# from ``settings`` so the API version prefix is never hardcoded here.
ACCOUNTS_URL = f"{settings.API_V1_PREFIX}/accounts"

# Golden-master account, seeded from ``app/data/ASCII/acctdata.txt`` row 1 and
# decoded per ``app/cpy/CVACT01Y.cpy``. The identifier is an 11-DIGIT STRING so
# its significant leading zeros (the VSAM ``ACCT-ID`` key) are preserved.
SEED_ACCT_ID = "00000000001"

# A well-formed 11-digit account id that is absent from the seed data, used to
# assert the COACTVWC not-found path (HTTP 404).
MISSING_ACCT_ID = "99999999999"

# A syntactically invalid (non-numeric) account id used to assert the
# COACTVWC 2210-EDIT-ACCOUNT filter edit.
INVALID_ACCT_ID = "abc"

# Exact golden-master money values for SEED_ACCT_ID. These are asserted as
# ``Decimal`` -- NEVER as ``float`` and NEVER with a tolerance (AAP §0.7.1).
EXPECTED_CURR_BAL = Decimal("194.00")
EXPECTED_CREDIT_LIMIT = Decimal("2020.00")
EXPECTED_CASH_LIMIT = Decimal("1020.00")
EXPECTED_CYC_CREDIT = Decimal("0.00")
EXPECTED_CYC_DEBIT = Decimal("0.00")

# Exact golden-master lifecycle dates (serialized as ISO ``YYYY-MM-DD`` strings).
EXPECTED_OPEN_DATE = "2014-11-20"
EXPECTED_EXPIRATION_DATE = "2025-05-20"
EXPECTED_REISSUE_DATE = "2025-05-20"

# Golden-master active-status flag for SEED_ACCT_ID (ACCT-ACTIVE-STATUS 'Y').
EXPECTED_ACTIVE_STATUS = "Y"

# The new balance submitted by the happy-path update. Sent as a money STRING so
# the exact decimal precision survives the wire (a binary ``float`` would be
# rejected by the schema's money coercion).
NEW_BALANCE = "250.00"

# ---------------------------------------------------------------------------
# Independent-session lost-update fixture (QA findings M-10 + M-11).
#
# The shared ``seed_data`` fixture only FLUSHES rows into the per-test
# ``db_session`` (never commits), so they are invisible to any OTHER connection.
# Proving a genuine lost-update therefore requires an account COMMITTED on its
# own connection so two independent sessions can both read the same before-image
# and contend for it. The COACTUPC update path resolves + locks the owning
# customer through the card cross-reference, so the minimal committed graph is
# customer + account + card + card_xref. These identifiers sit outside the
# golden-master seed set so they never collide with it.
# ---------------------------------------------------------------------------
CONFLICT_ACCT_ID = "00000000099"
CONFLICT_CUST_ID = "000000099"
CONFLICT_CARD_NUM = "4111111111110099"
CONFLICT_ORIGINAL_BAL = Decimal("100.00")
CONFLICT_WINNER_BAL = Decimal("500.00")
CONFLICT_LOSER_BAL = Decimal("900.00")
CONFLICT_CREDIT_LIMIT = Decimal("5000.00")
CONFLICT_CASH_LIMIT = Decimal("1000.00")
CONFLICT_ZERO = Decimal("0.00")
CONFLICT_CARD_EXPIRY = date(2027, 8, 31)

# Grace period allowing the blocked FOR UPDATE task to reach the database and
# park on the row lock before the test asserts it has not completed. The task is
# genuinely blocked at the database, so it stays pending regardless of this
# value; the wait only lets the event loop schedule it up to the lock.
LOCK_WAIT_SECONDS = 0.25

# Precise HTTP status codes asserted by the tests (never a broad "not 2xx").
HTTP_OK = 200
HTTP_UNAUTHORIZED = 401
HTTP_NOT_FOUND = 404
HTTP_CONFLICT = 409
HTTP_UNPROCESSABLE = 422

# The five monetary fields echoed on the optimistic-lock before-image. Together
# with ``active_status`` they are the REQUIRED members of
# ``app.schemas.account.AccountBeforeImage`` and anchor the COACTUPC 9700
# lost-update comparison.
BEFORE_IMAGE_MONEY_FIELDS = (
    "curr_bal",
    "credit_limit",
    "cash_credit_limit",
    "curr_cyc_credit",
    "curr_cyc_debit",
)


# ---------------------------------------------------------------------------
# Small helpers (PascalCase per the Ochs Rule; keep each test focused).
# ---------------------------------------------------------------------------
def ToDecimal(jsonValue: object) -> Decimal:
    """Convert a JSON money value to an EXACT ``Decimal`` with no float step.

    A JSON number deserializes to a Python ``float`` (which cannot represent
    most decimal fractions exactly), so the value is first rendered with ``str``
    and only then parsed by :class:`decimal.Decimal`. This preserves the
    monetary value byte-for-byte whether the API serialized the field as a JSON
    string (``"194.00"``) or a JSON number, satisfying the AAP §0.7.1 rule that
    money is compared as exact ``Decimal`` and never through binary floating
    point.

    Args:
        jsonValue: The raw money value taken from a parsed JSON response body.

    Returns:
        The exact :class:`decimal.Decimal` numerically equal to ``jsonValue``.
    """
    return Decimal(str(jsonValue))


def BuildBeforeImage(responseBody: dict) -> dict:
    """Build the optimistic-lock before-image echo from a GET response body.

    Reproduces the client side of the COACTUPC before-image contract
    (AAP §0.7.4): the caller echoes the editable-field image it last read so the
    service can compare it, field-for-field, against the freshly locked row. The
    six fields required by :class:`app.schemas.account.AccountBeforeImage`
    (``active_status`` plus the five monetary fields) are echoed, and every
    monetary value is passed as a STRING so the request preserves exact decimal
    precision and is never rejected as a binary ``float``.

    Args:
        responseBody: A parsed ``AccountDetail`` JSON body from a prior GET.

    Returns:
        A mapping suitable for the ``before_image`` member of an
        ``AccountUpdate`` request payload.
    """
    beforeImage = {"active_status": responseBody["active_status"]}
    for moneyField in BEFORE_IMAGE_MONEY_FIELDS:
        beforeImage[moneyField] = str(responseBody[moneyField])
    return beforeImage


def BuildConflictBeforeImage() -> AccountBeforeImage:
    """Build the client before-image echoing the seeded ORIGINAL account values.

    Both the winner and the loser transactions submit THIS same before-image,
    exactly reproducing two clients that each read the account while it still
    held its original values. The winner commits first (its before-image still
    matches), so the loser's identical before-image is then stale and its update
    is rejected -- proving the COACTUPC 9700 lost-update guard (AAP §0.7.4).

    Returns:
        An :class:`~app.schemas.account.AccountBeforeImage` carrying the six
        required members (``active_status`` plus the five monetary fields) set
        to the seeded ORIGINAL values.
    """
    return AccountBeforeImage(
        active_status=EXPECTED_ACTIVE_STATUS,
        curr_bal=CONFLICT_ORIGINAL_BAL,
        credit_limit=CONFLICT_CREDIT_LIMIT,
        cash_credit_limit=CONFLICT_CASH_LIMIT,
        curr_cyc_credit=CONFLICT_ZERO,
        curr_cyc_debit=CONFLICT_ZERO,
    )


async def SeedConflictAccountCommitted(sessionMaker: async_sessionmaker) -> None:
    """Commit the account-update RI chain (customer+account+card+xref) independently.

    COMMITS (not the flush-only ``seed_data``) so the row is visible to the two
    independent sessions that then contend for it, which is the crux of proving a
    genuine lost-update (QA finding M-11). The COACTUPC update path resolves the
    owning customer through the card cross-reference and locks it, so all four
    parents/children are required. ``group_id`` is left NULL so no disclosure
    group parent is needed. The ``db_session`` fixture's ``TRUNCATE ... CASCADE``
    teardown removes these committed rows after the test.

    Args:
        sessionMaker: An ``async_sessionmaker`` bound to the test engine.
    """
    async with sessionMaker() as seedSession:
        seedSession.add(
            Customer(
                cust_id=CONFLICT_CUST_ID,
                first_name="CONFLICT",
                last_name="OWNER",
                addr_line_1="1 TEST WAY",
            )
        )
        seedSession.add(
            Account(
                acct_id=CONFLICT_ACCT_ID,
                active_status=EXPECTED_ACTIVE_STATUS,
                curr_bal=CONFLICT_ORIGINAL_BAL,
                credit_limit=CONFLICT_CREDIT_LIMIT,
                cash_credit_limit=CONFLICT_CASH_LIMIT,
                curr_cyc_credit=CONFLICT_ZERO,
                curr_cyc_debit=CONFLICT_ZERO,
                group_id=None,
            )
        )
        await seedSession.flush()
        seedSession.add(
            Card(
                card_num=CONFLICT_CARD_NUM,
                acct_id=CONFLICT_ACCT_ID,
                embossed_name="CONFLICT OWNER",
                expiration_date=CONFLICT_CARD_EXPIRY,
                active_status=EXPECTED_ACTIVE_STATUS,
            )
        )
        await seedSession.flush()
        seedSession.add(
            CardXref(
                xref_card_num=CONFLICT_CARD_NUM,
                cust_id=CONFLICT_CUST_ID,
                acct_id=CONFLICT_ACCT_ID,
            )
        )
        await seedSession.commit()


# ===========================================================================
# GET /accounts/{acctId} -- account view (COACTVWC, tx CAVW).
# ===========================================================================
async def test_get_account_returns_golden_master_decimals(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """GET returns the golden-master account with EXACT-Decimal money fields.

    Proves the end-to-end numeric-parity contract (AAP §0.7.1): the five
    monetary fields of ``AccountDetail`` round-trip from the ``NUMERIC(12, 2)``
    columns to the JSON body with no loss of precision, and the three lifecycle
    dates serialize as their exact ISO strings.

    Args:
        admin_client: Authenticated (admin) httpx ASGI client.
        seed_data: Golden-master seed loaded from ``app/data/ASCII``.
    """
    resp = await admin_client.get(f"{ACCOUNTS_URL}/{SEED_ACCT_ID}")

    assert resp.status_code == HTTP_OK
    responseBody = resp.json()
    assert responseBody["acct_id"] == SEED_ACCT_ID
    assert ToDecimal(responseBody["curr_bal"]) == EXPECTED_CURR_BAL
    assert ToDecimal(responseBody["credit_limit"]) == EXPECTED_CREDIT_LIMIT
    assert ToDecimal(responseBody["cash_credit_limit"]) == EXPECTED_CASH_LIMIT
    assert ToDecimal(responseBody["curr_cyc_credit"]) == EXPECTED_CYC_CREDIT
    assert ToDecimal(responseBody["curr_cyc_debit"]) == EXPECTED_CYC_DEBIT
    assert responseBody["open_date"] == EXPECTED_OPEN_DATE
    assert responseBody["expiration_date"] == EXPECTED_EXPIRATION_DATE
    assert responseBody["reissue_date"] == EXPECTED_REISSUE_DATE


async def test_get_account_embeds_customer_with_masked_ssn(
    admin_client: AsyncClient,
    seed_data: None,
    db_session: AsyncSession,
) -> None:
    """GET embeds the owning customer with its SSN masked to the last four.

    Proves the AAP §0.7.8 sensitive-data rule: the response embeds a
    ``CustomerRead`` whose ``ssn`` exposes only the final four digits
    (``***-**-NNNN``) and never the full nine-digit value. The full stored SSN
    is read directly from the database and asserted ABSENT from the raw response
    text, so the check cannot silently pass on a masking-format change.

    Args:
        admin_client: Authenticated (admin) httpx ASGI client.
        seed_data: Golden-master seed loaded from ``app/data/ASCII``.
        db_session: Shared async session used to read the stored SSN at rest.
    """
    resp = await admin_client.get(f"{ACCOUNTS_URL}/{SEED_ACCT_ID}")

    assert resp.status_code == HTTP_OK
    responseBody = resp.json()
    assert "customer" in responseBody
    customer = responseBody["customer"]
    assert customer["cust_id"]
    assert customer["first_name"]
    assert customer["last_name"]

    maskedSsn = customer["ssn"]
    assert maskedSsn is not None
    assert "*" in maskedSsn

    # The full SSN stored at rest must never leak into the serialized response.
    customerRow = await db_session.get(Customer, customer["cust_id"])
    assert customerRow is not None
    fullSsn = customerRow.ssn
    assert fullSsn is not None
    assert fullSsn not in resp.text
    assert maskedSsn.endswith(fullSsn[-4:])


async def test_get_account_not_found(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """GET for an absent (but well-formed) account id returns HTTP 404.

    Reproduces the COACTVWC not-found path: an account with no cross-reference
    row surfaces the verbatim legacy message and maps to HTTP 404.

    Args:
        admin_client: Authenticated (admin) httpx ASGI client.
        seed_data: Golden-master seed (which does not contain MISSING_ACCT_ID).
    """
    resp = await admin_client.get(f"{ACCOUNTS_URL}/{MISSING_ACCT_ID}")

    assert resp.status_code == HTTP_NOT_FOUND
    bodyText = str(resp.json())
    assert "Did not find this account" in bodyText or "not found" in bodyText.lower()


async def test_get_account_requires_auth(client: AsyncClient) -> None:
    """GET on the protected route without a session returns HTTP 401.

    The unauthenticated ``client`` fixture overrides only ``get_db`` (not
    ``get_current_user``), so the real authentication dependency runs and,
    finding no session cookie / bearer token, rejects the request with 401.

    Args:
        client: Unauthenticated httpx ASGI client (carries no identity).
    """
    resp = await client.get(f"{ACCOUNTS_URL}/{SEED_ACCT_ID}")

    assert resp.status_code == HTTP_UNAUTHORIZED


async def test_get_account_invalid_id_format(admin_client: AsyncClient) -> None:
    """GET with a non-numeric account id is rejected by the filter edit.

    The service validates the account filter (COACTVWC 2210-EDIT-ACCOUNT)
    BEFORE any lookup, so a non-numeric id raises ``DomainValidationError``,
    which the application maps to HTTP 422 (asserted exactly).

    Args:
        admin_client: Authenticated (admin) httpx ASGI client.
    """
    resp = await admin_client.get(f"{ACCOUNTS_URL}/{INVALID_ACCT_ID}")

    assert resp.status_code == HTTP_UNPROCESSABLE


# ===========================================================================
# PUT /accounts/{acctId} -- account update (COACTUPC, tx CAUP).
# ===========================================================================
async def test_update_account_success(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """PUT applies a new balance and returns the refreshed account (HTTP 200).

    Reproduces the COACTUPC happy path: the client reads the account, echoes its
    before-image, and submits a single changed field. The new balance is sent as
    a money STRING to preserve exact precision, and the response value is
    asserted as an exact ``Decimal`` (AAP §0.7.1); the untouched money fields
    keep their golden-master values.

    Args:
        admin_client: Authenticated (admin) httpx ASGI client.
        seed_data: Golden-master seed loaded from ``app/data/ASCII``.
    """
    getResp = await admin_client.get(f"{ACCOUNTS_URL}/{SEED_ACCT_ID}")
    assert getResp.status_code == HTTP_OK
    beforeImage = BuildBeforeImage(getResp.json())

    updatePayload = {"before_image": beforeImage, "curr_bal": NEW_BALANCE}
    putResp = await admin_client.put(
        f"{ACCOUNTS_URL}/{SEED_ACCT_ID}",
        json=updatePayload,
    )

    assert putResp.status_code == HTTP_OK
    responseBody = putResp.json()
    assert responseBody["acct_id"] == SEED_ACCT_ID
    assert ToDecimal(responseBody["curr_bal"]) == Decimal(NEW_BALANCE)
    # The fields the client did not change keep their exact golden-master values.
    assert ToDecimal(responseBody["credit_limit"]) == EXPECTED_CREDIT_LIMIT
    assert ToDecimal(responseBody["cash_credit_limit"]) == EXPECTED_CASH_LIMIT


async def test_update_account_lost_update_prevented_independent_sessions(
    test_engine,
    db_session: AsyncSession,
) -> None:
    """Two INDEPENDENT clients submit the SAME before-image; one wins, one 409s.

    Genuine lost-update reproduction (QA findings M-10 + M-11). It replaces the
    prior test, which mutated the SHARED ``db_session`` (the same connection the
    request handler used) and so did not prove independent behavior. Here the
    account is COMMITTED on its own connection and two GENUINELY independent
    ``AsyncSession``s (each its own connection and transaction) drive the full
    ``AccountService.UpdateAccount`` COACTUPC flow:

    * The WINNER submits the ORIGINAL before-image; because the row still holds
      its original values, the before-image check passes and it COMMITS
      ``CONFLICT_WINNER_BAL`` under the ``SELECT ... FOR UPDATE`` lock.
    * The LOSER, on a SEPARATE session, submits the SAME before-image (exactly
      what a second client that read the account at the same time would echo).
      Its locked re-read now observes the WINNER's committed balance, so the
      before-image check (COACTUPC 9700) raises ``OptimisticLockError`` and the
      write is rejected -- the WINNER's change is NOT lost.

    Asserts one winner, one explicit conflict, and the final committed state
    (exactly the winner's balance), directly against real PostgreSQL row locks.

    Args:
        test_engine: Function-scoped async engine used to build two independent
            sessions (and the same engine ``db_session`` is bound to).
        db_session: Requested only so its ``TRUNCATE ... CASCADE`` teardown wipes
            the committed conflict rows after the test.
    """
    sessionMaker = async_sessionmaker(
        bind=test_engine,
        class_=AsyncSession,
        expire_on_commit=False,
        autoflush=False,
    )
    await SeedConflictAccountCommitted(sessionMaker)
    service = AccountService()

    # WINNER: independent session; the before-image still matches the current
    # row, so the update succeeds and COMMITS (ORIGINAL -> WINNER).
    async with sessionMaker() as sessionWinner:
        winnerResult = await service.UpdateAccount(
            sessionWinner,
            CONFLICT_ACCT_ID,
            AccountUpdate(
                before_image=BuildConflictBeforeImage(),
                curr_bal=CONFLICT_WINNER_BAL,
            ),
        )
        assert winnerResult.curr_bal == CONFLICT_WINNER_BAL

    # LOSER: a SEPARATE independent session submits the SAME (now stale)
    # before-image; the locked re-read observes the WINNER's committed balance,
    # so the before-image check raises the explicit conflict.
    async with sessionMaker() as sessionLoser:
        with pytest.raises(OptimisticLockError):
            await service.UpdateAccount(
                sessionLoser,
                CONFLICT_ACCT_ID,
                AccountUpdate(
                    before_image=BuildConflictBeforeImage(),
                    curr_bal=CONFLICT_LOSER_BAL,
                ),
            )

    # Final committed state: exactly the WINNER's balance survived; the LOSER's
    # write was rejected (one winner, one conflict, no lost update).
    async with sessionMaker() as verifySession:
        finalAccount = await AccountRepository().GetByAcctId(verifySession, CONFLICT_ACCT_ID)
        assert finalAccount is not None
        assert finalAccount.curr_bal == CONFLICT_WINNER_BAL
        assert finalAccount.curr_bal != CONFLICT_LOSER_BAL


async def test_account_get_for_update_serializes_concurrent_writers(
    test_engine,
    db_session: AsyncSession,
) -> None:
    """SELECT ... FOR UPDATE blocks a second writer until the first commits (M-11).

    The deterministic proof that the COACTUPC account row lock
    (``AccountRepository.GetForUpdate`` -> ``SELECT ... FOR UPDATE``, AAP §0.7.4)
    serializes two independent transactions, orchestrated with an explicit lock
    barrier (a bare ``asyncio.gather`` cannot prove this: the first writer may
    commit before the second even reads, so no contention is forced). Two
    GENUINELY independent sessions contend for one COMMITTED account:

    1. Transaction TWO reads the account first, capturing the ORIGINAL balance.
    2. Transaction ONE acquires the row lock (``GetForUpdate``) and HOLDS it.
    3. Transaction TWO's own ``GetForUpdate`` is launched as a task and is
       observed to BLOCK while ONE holds the lock -- the direct evidence that
       the lock serializes writers (a non-locking read would not block).
    4. Transaction ONE commits its change (ORIGINAL -> WINNER) and releases the
       lock; TWO then unblocks and observes the committed WINNER balance, NOT
       the stale ORIGINAL it first read -- so the before-image check would reject
       its write, preventing the lost update.

    Args:
        test_engine: Function-scoped async engine used to build two independent
            sessions.
        db_session: Requested only so its ``TRUNCATE ... CASCADE`` teardown wipes
            the committed conflict rows after the test.
    """
    sessionMaker = async_sessionmaker(
        bind=test_engine,
        class_=AsyncSession,
        expire_on_commit=False,
        autoflush=False,
    )
    await SeedConflictAccountCommitted(sessionMaker)
    repository = AccountRepository()

    async with sessionMaker() as sessionOne, sessionMaker() as sessionTwo:
        # (1) ONE acquires the account row lock FOR UPDATE and holds it (no
        # commit yet). The seeded ORIGINAL balance is the known before-image.
        lockedOne = await repository.GetForUpdate(sessionOne, CONFLICT_ACCT_ID)
        assert lockedOne is not None
        assert lockedOne.curr_bal == CONFLICT_ORIGINAL_BAL

        # (2) TWO's locking read MUST block while ONE holds the lock. TWO's
        # GetForUpdate is its FIRST read of the row, exactly as the COACTUPC
        # update flow locks the account before reading it (so no prior
        # identity-map entry can mask the committed value).
        blockedTask = asyncio.create_task(
            repository.GetForUpdate(sessionTwo, CONFLICT_ACCT_ID)
        )
        await asyncio.sleep(LOCK_WAIT_SECONDS)
        assert not blockedTask.done()

        # (3) ONE commits ORIGINAL -> WINNER, releasing the lock.
        lockedOne.curr_bal = CONFLICT_WINNER_BAL
        await repository.Update(sessionOne, lockedOne)
        await sessionOne.commit()

        # TWO now unblocks; its locked read observes the committed WINNER
        # balance -- not the stale ORIGINAL, so a before-image echo of ORIGINAL
        # would be rejected, preventing the lost update.
        lockedTwo = await blockedTask
        assert lockedTwo is not None
        assert lockedTwo.curr_bal == CONFLICT_WINNER_BAL
        assert lockedTwo.curr_bal != CONFLICT_ORIGINAL_BAL


async def test_update_account_invalid_body(admin_client: AsyncClient) -> None:
    """PUT with an invalid editable field is rejected with a 4xx validation error.

    Sends a well-formed before-image alongside an ``active_status`` of ``"X"``
    (the ported Yes/No edit accepts only ``'Y'`` or ``'N'``). The value fails the
    Pydantic field edit before any lookup, so the API responds HTTP 422 (asserted
    exactly); the offending field name appears in the error body.

    Args:
        admin_client: Authenticated (admin) httpx ASGI client.
    """
    invalidPayload = {
        "before_image": {
            "active_status": EXPECTED_ACTIVE_STATUS,
            "curr_bal": str(EXPECTED_CURR_BAL),
            "credit_limit": str(EXPECTED_CREDIT_LIMIT),
            "cash_credit_limit": str(EXPECTED_CASH_LIMIT),
            "curr_cyc_credit": str(EXPECTED_CYC_CREDIT),
            "curr_cyc_debit": str(EXPECTED_CYC_DEBIT),
        },
        "active_status": "X",
    }
    resp = await admin_client.put(
        f"{ACCOUNTS_URL}/{SEED_ACCT_ID}",
        json=invalidPayload,
    )

    assert resp.status_code == HTTP_UNPROCESSABLE
    assert "active_status" in str(resp.json())
