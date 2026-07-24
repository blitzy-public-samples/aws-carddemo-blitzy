# Integration tests for the cards API.
# Ported from legacy CICS online programs COCRDLIC (card list, CCLI; <=7 rows/
# page per F-004), COCRDSLC (card view, CCDL), COCRDUPC (card update, CCUP).
# Endpoints: GET /api/v1/cards, GET/PUT /api/v1/cards/{cardNum} (AAP §0.5.5).
# Verifies pagination <=7 rows/page (F-004), card_num masking ("*"*12+last4),
# and that cvv_cd is NEVER present in any response. Record layout CVACT02Y.
"""Integration tests for the cards REST API.

These tests exercise the three card endpoints end to end through the in-process
httpx ASGI client wired to ``app.main:app`` (via the shared fixtures in
``backend/tests/conftest.py`` -- there is deliberately no local ``conftest``):

    * ``GET /api/v1/cards`` -- the COCRDLIC browse (transaction ``CCLI``), which
      never returns more than seven rows per page (F-004).
    * ``GET /api/v1/cards/{cardNum}`` -- the COCRDSLC detail view (``CCDL``).
    * ``PUT /api/v1/cards/{cardNum}`` -- the COCRDUPC update (``CCUP``), including
      the optimistic before-image concurrency check (AAP 0.7.4).

Two sensitive-data guarantees are asserted on every response shape (AAP 0.7.8):
``card_num`` is always masked to its last four digits, and the card verification
value (``cvv_cd``) is never present in any payload. The pagination guarantee
(no page ever exceeds seven rows) is asserted for both the default and an
oversized page-size request, since the service clamps the effective page size.

Ochs conventions (AAP 0.8.2 / 0.8.3): the module file name is snake_case and the
pytest test functions keep snake_case names (the framework contract); helper
functions are PascalCase (``AssertCardMaskedNoCvv``, ``BuildUpdatePayload``),
local variables are camelCase (``responseBody``, ``beforeImage``,
``updatePayload``), and module constants are ALL_UPPERCASE
(``MAX_ROWS_PER_PAGE``). Every assertion pins a precise HTTP status code.
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
from app.repositories.card_repo import CardRepository
from app.schemas.card import CardBeforeImage, CardUpdate
from app.services.card_service import CardService

# ---------------------------------------------------------------------------
# Module constants (ALL_UPPERCASE per the Ochs Rule 0.8.2).
#
# The ``/api/v1`` version prefix is read from ``settings`` rather than hardcoded
# (Ochs Rule #3: no hardcoded values), mirroring the sibling integration tests.
# ---------------------------------------------------------------------------

# Fully-qualified cards resource path: the version-1 API prefix (mounted once by
# ``app.main``) plus the cards sub-router's own ``/cards`` path.
CARDS_URL = f"{settings.API_V1_PREFIX}/cards"

# Golden-master seed card (carddata.txt row 1; values VERIFIED by direct byte
# slicing of app/data/ASCII/carddata.txt -- CVACT02Y fixed-width layout).
SEED_CARD_NUM = "0500024453765740"
SEED_ACCT_ID = "00000000050"
SEED_CVV = "747"
SEED_EMBOSSED_NAME = "Aniya Von"
SEED_EXPIRATION_DATE = "2023-03-09"
SEED_ACTIVE_STATUS = "Y"

# Total rows in carddata.txt (50) -- used to assert the browse envelope's
# authoritative total for an administrator (who lists every card).
SEED_CARD_COUNT = 50

# Card-number masking (AAP 0.7.8): only the last four digits are ever revealed,
# so the fixed 16-digit PAN yields twelve mask characters followed by the last
# four digits (for example "************5740").
MASK_CHARACTER = "*"
MASK_PREFIX_LENGTH = 12
MASKED_SEED_CARD = (MASK_CHARACTER * MASK_PREFIX_LENGTH) + SEED_CARD_NUM[-4:]

# Legacy seven-line browse window (F-004, COCRDLIC WS-MAX-SCREEN-LINES).
MAX_ROWS_PER_PAGE = 7

# A valid 16-digit card number that is deliberately absent from the seed set, so
# a keyed read returns the legacy "not found" condition.
MISSING_CARD_NUM = "9999999999999999"

# Update-payload values. Names are alphabetic + spaces so they pass the legacy
# 1230-EDIT-NAME edit; each differs from the seed embossed name so the update is
# never a no-op (which the service rejects as "No change detected").
NEW_EMBOSSED_NAME = "NEW CARDHOLDER"
VALID_EMBOSSED_NAME = "VALID CARDHOLDER"

# ---------------------------------------------------------------------------
# Independent-session lost-update fixture (QA findings M-10 + M-11).
#
# The shared ``seed_data`` fixture only FLUSHES rows into the per-test
# ``db_session`` (it never commits), so those rows are invisible to any OTHER
# database connection. Proving a genuine lost-update therefore requires a card
# that is COMMITTED on its own connection so two independent sessions can both
# see and contend for it. A ``cards`` row's only foreign key is
# ``cards.acct_id -> accounts.acct_id``, so the minimal committed graph is one
# account (``group_id`` is a plain free-text column with no parent table)
# plus one card. These identifiers are deliberately outside the golden-master
# seed set so they never collide with it.
# ---------------------------------------------------------------------------
CONFLICT_ACCT_ID = "00000000099"
CONFLICT_CARD_NUM = "4111111111110099"
CONFLICT_ORIGINAL_NAME = "ORIGINAL HOLDER"
CONFLICT_WINNER_NAME = "WINNER HOLDER"
CONFLICT_LOSER_NAME = "LOSER HOLDER"
CONFLICT_CARD_EXPIRY = date(2027, 8, 31)
CONFLICT_ACCT_CURR_BAL = Decimal("100.00")
CONFLICT_ACCT_CREDIT_LIMIT = Decimal("5000.00")
CONFLICT_ACCT_CASH_LIMIT = Decimal("1000.00")
CONFLICT_ACCT_ZERO = Decimal("0.00")

# Grace period allowing the blocked FOR UPDATE task to reach the database and
# park on the row lock before the test asserts it has not completed. The task is
# genuinely blocked at the database, so it stays pending regardless of this
# value; the wait only lets the event loop schedule it up to the lock.
LOCK_WAIT_SECONDS = 0.25

# Query-string and body edge values exercised by the tests.
OVERSIZED_PAGE_SIZE = 50
SECOND_PAGE = 2
INVALID_ACTIVE_STATUS = "X"

# Precise HTTP status codes asserted by the tests (never a broad "not 2xx").
HTTP_OK = 200
HTTP_UNAUTHORIZED = 401
HTTP_NOT_FOUND = 404
HTTP_CONFLICT = 409
HTTP_UNPROCESSABLE_CONTENT = 422


# ---------------------------------------------------------------------------
# Shared assertion / builder helpers (PascalCase per Ochs; NOT fixtures).
# ---------------------------------------------------------------------------


def AssertCardMaskedNoCvv(cardPayload: dict) -> None:
    """Assert a serialized card masks its number and never exposes the CVV.

    Enforces the two non-negotiable sensitive-data guarantees (AAP 0.7.8) on a
    single card object -- whether a browse list item or a detail body: the
    ``card_num`` is masked (its first twelve characters are the mask character)
    and neither ``cvv_cd`` nor a bare ``cvv`` key is present.

    Args:
        cardPayload: One serialized card object (a list item or detail body).
    """
    maskedPrefix = MASK_CHARACTER * MASK_PREFIX_LENGTH
    assert cardPayload["card_num"].startswith(maskedPrefix)
    assert "cvv_cd" not in cardPayload
    assert "cvv" not in cardPayload


def BuildUpdatePayload(
    embossedName: str,
    expirationDate: str,
    activeStatus: str,
    beforeImage: dict | None = None,
) -> dict:
    """Build a ``CardUpdate`` request body from the three editable fields.

    Groups the editable card fields into one JSON-serializable object (the
    request body accepts only the embossed name, expiration date, and active
    status; the identifiers and the CVV are never in the body). Every update
    also carries the client-echoed ``before_image`` optimistic-lock token
    (COACTUPC-style READ-before-image; QA finding C-06) so the service can
    detect a concurrent modification under ``SELECT ... FOR UPDATE``.

    Args:
        embossedName: New embossed name (alphabetic + spaces).
        expirationDate: Expiry date as ISO ``YYYY-MM-DD`` text.
        activeStatus: Active-status flag, ``'Y'`` or ``'N'``.
        beforeImage: The card's editable values as last read by the client
            (an ``AssertCardMaskedNoCvv``-shaped GET response, or any mapping
            exposing ``embossed_name``/``active_status``/``expiration_date``).
            When ``None`` the golden-master seed card's committed values are
            echoed, which matches an unmodified ``SEED_CARD_NUM`` row.

    Returns:
        A dict matching the ``CardUpdate`` schema, ready to pass as ``json=``.
    """
    if beforeImage is None:
        beforeImage = {
            "embossed_name": SEED_EMBOSSED_NAME,
            "active_status": SEED_ACTIVE_STATUS,
            "expiration_date": SEED_EXPIRATION_DATE,
        }
    return {
        "before_image": {
            "embossed_name": beforeImage["embossed_name"],
            "active_status": beforeImage["active_status"],
            "expiration_date": beforeImage["expiration_date"],
        },
        "embossed_name": embossedName,
        "expiration_date": expirationDate,
        "active_status": activeStatus,
    }


async def SeedConflictCardCommitted(sessionMaker: async_sessionmaker) -> None:
    """Commit the minimal account+card graph on an INDEPENDENT session.

    Inserts one account (``CONFLICT_ACCT_ID``; ``group_id`` left NULL so no
    disclosure-group parent is required) and one card (``CONFLICT_CARD_NUM``,
    embossed ``CONFLICT_ORIGINAL_NAME``) and COMMITS them on their own session.
    Committing (rather than the flush-only ``seed_data`` fixture) is what makes
    the row visible to the two independent sessions that then contend for it,
    which is the crux of proving a genuine lost-update (QA finding M-11). The
    ``db_session`` fixture's ``TRUNCATE ... CASCADE`` teardown removes these
    committed rows after the test.

    Args:
        sessionMaker: An ``async_sessionmaker`` bound to the test engine.
    """
    async with sessionMaker() as seedSession:
        seedSession.add(
            Account(
                acct_id=CONFLICT_ACCT_ID,
                active_status="Y",
                curr_bal=CONFLICT_ACCT_CURR_BAL,
                credit_limit=CONFLICT_ACCT_CREDIT_LIMIT,
                cash_credit_limit=CONFLICT_ACCT_CASH_LIMIT,
                curr_cyc_credit=CONFLICT_ACCT_ZERO,
                curr_cyc_debit=CONFLICT_ACCT_ZERO,
                group_id=None,
            )
        )
        await seedSession.flush()
        seedSession.add(
            Card(
                card_num=CONFLICT_CARD_NUM,
                acct_id=CONFLICT_ACCT_ID,
                embossed_name=CONFLICT_ORIGINAL_NAME,
                expiration_date=CONFLICT_CARD_EXPIRY,
                active_status="Y",
            )
        )
        await seedSession.commit()


# ===========================================================================
# Phase 2 -- list pagination, never more than seven rows per page (F-004).
# ===========================================================================


@pytest.mark.asyncio
async def test_cards_list_default_page_size_is_seven(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """The default browse page never exceeds the seven-row F-004 limit.

    Args:
        admin_client: Authenticated administrator client (lists all cards).
        seed_data: Golden-master seed fixture (50 cards + accounts + xref).
    """
    resp = await admin_client.get(CARDS_URL)

    assert resp.status_code == HTTP_OK
    responseBody = resp.json()
    assert len(responseBody["items"]) <= MAX_ROWS_PER_PAGE
    assert responseBody["page_size"] <= MAX_ROWS_PER_PAGE


@pytest.mark.asyncio
async def test_cards_list_clamps_oversized_page_size(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """An oversized ``page_size`` is clamped so the page stays within F-004.

    ``page_size=50`` is within the schema bound (<= 100) so the request
    succeeds with HTTP 200, but the service caps the effective window to seven
    rows, so neither the returned items nor the echoed ``page_size`` may exceed
    the F-004 limit.

    Args:
        admin_client: Authenticated administrator client.
        seed_data: Golden-master seed fixture.
    """
    resp = await admin_client.get(f"{CARDS_URL}?page_size={OVERSIZED_PAGE_SIZE}")

    assert resp.status_code == HTTP_OK
    responseBody = resp.json()
    assert len(responseBody["items"]) <= MAX_ROWS_PER_PAGE
    assert responseBody["page_size"] <= MAX_ROWS_PER_PAGE


@pytest.mark.asyncio
async def test_cards_list_second_page(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """The second browse page reports its ordinal and a previous page.

    With 50 seed cards paged at seven rows there are eight pages, so page two
    carries ``has_previous`` True and ``has_next`` True, still bounded to seven
    rows. The administrator lists every card, so the authoritative total equals
    the full seed count.

    Args:
        admin_client: Authenticated administrator client.
        seed_data: Golden-master seed fixture.
    """
    resp = await admin_client.get(
        f"{CARDS_URL}?page={SECOND_PAGE}&page_size={MAX_ROWS_PER_PAGE}"
    )

    assert resp.status_code == HTTP_OK
    responseBody = resp.json()
    assert responseBody["page"] == SECOND_PAGE
    assert responseBody["has_previous"] is True
    assert responseBody["has_next"] is True
    assert responseBody["total_items"] == SEED_CARD_COUNT
    assert len(responseBody["items"]) <= MAX_ROWS_PER_PAGE


@pytest.mark.asyncio
async def test_cards_list_masks_and_omits_cvv(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """Every browse row masks its card number and omits the CVV (AAP 0.7.8).

    Args:
        admin_client: Authenticated administrator client.
        seed_data: Golden-master seed fixture.
    """
    resp = await admin_client.get(CARDS_URL)

    assert resp.status_code == HTTP_OK
    responseBody = resp.json()
    assert responseBody["items"], "expected at least one seeded card row"
    for item in responseBody["items"]:
        AssertCardMaskedNoCvv(item)


# ===========================================================================
# Phase 3 -- card detail view (COCRDSLC, tx CCDL).
# ===========================================================================


@pytest.mark.asyncio
async def test_get_card_detail_masked_no_cvv(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """The detail view returns the masked card with its account and no CVV.

    Confirms the COCRDSLC read: the masked ``card_num`` and owning ``acct_id``
    match the verified seed values, the CVV is absent from the payload, and the
    real CVV digits never appear anywhere in the serialized response text.

    Args:
        admin_client: Authenticated administrator client.
        seed_data: Golden-master seed fixture.
    """
    resp = await admin_client.get(f"{CARDS_URL}/{SEED_CARD_NUM}")

    assert resp.status_code == HTTP_OK
    responseBody = resp.json()
    assert responseBody["card_num"] == MASKED_SEED_CARD
    assert responseBody["acct_id"] == SEED_ACCT_ID
    AssertCardMaskedNoCvv(responseBody)
    # The real CVV must never be serialized into any part of the response.
    assert SEED_CVV not in resp.text


@pytest.mark.asyncio
async def test_get_card_not_found(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """A keyed read for an absent card returns the legacy not-found (HTTP 404).

    Args:
        admin_client: Authenticated administrator client.
        seed_data: Golden-master seed fixture.
    """
    resp = await admin_client.get(f"{CARDS_URL}/{MISSING_CARD_NUM}")

    assert resp.status_code == HTTP_NOT_FOUND
    responseText = str(resp.json())
    assert "Did not find cards" in responseText or "not found" in responseText.lower()


@pytest.mark.asyncio
async def test_get_card_requires_auth(client: AsyncClient) -> None:
    """The detail view rejects an unauthenticated caller with HTTP 401.

    Args:
        client: The unauthenticated httpx ASGI client (carries no identity).
    """
    resp = await client.get(f"{CARDS_URL}/{SEED_CARD_NUM}")

    assert resp.status_code == HTTP_UNAUTHORIZED


# ===========================================================================
# Phase 4 -- card update (COCRDUPC, tx CCUP), incl. optimistic before-image.
# ===========================================================================


@pytest.mark.asyncio
async def test_update_card_success(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """A valid update changes the embossed name and returns the masked card.

    Reads the card first (capturing the before-image), then submits a new
    embossed name while re-sending the unchanged expiry and status. The update
    succeeds (HTTP 200), the response reflects the new name, the card number is
    still masked, and no CVV is present.

    Args:
        admin_client: Authenticated administrator client.
        seed_data: Golden-master seed fixture.
    """
    getResp = await admin_client.get(f"{CARDS_URL}/{SEED_CARD_NUM}")
    assert getResp.status_code == HTTP_OK
    beforeImage = getResp.json()

    updatePayload = BuildUpdatePayload(
        NEW_EMBOSSED_NAME,
        beforeImage["expiration_date"],
        beforeImage["active_status"],
        beforeImage,
    )
    putResp = await admin_client.put(f"{CARDS_URL}/{SEED_CARD_NUM}", json=updatePayload)

    assert putResp.status_code == HTTP_OK
    responseBody = putResp.json()
    assert responseBody["embossed_name"] == NEW_EMBOSSED_NAME
    assert responseBody["card_num"] == MASKED_SEED_CARD
    AssertCardMaskedNoCvv(responseBody)


@pytest.mark.asyncio
async def test_update_card_invalid_status(
    admin_client: AsyncClient,
    seed_data: None,
) -> None:
    """An active status outside {Y, N} is rejected as a field-edit failure.

    The active-status flag is edited at the schema layer (COCRDUPC
    1240-EDIT-CARDSTATUS), so an ``'X'`` fails the Pydantic field edit and the
    API responds HTTP 422, naming the offending field.

    Args:
        admin_client: Authenticated administrator client.
        seed_data: Golden-master seed fixture.
    """
    updatePayload = BuildUpdatePayload(
        VALID_EMBOSSED_NAME,
        SEED_EXPIRATION_DATE,
        INVALID_ACTIVE_STATUS,
    )
    resp = await admin_client.put(f"{CARDS_URL}/{SEED_CARD_NUM}", json=updatePayload)

    assert resp.status_code == HTTP_UNPROCESSABLE_CONTENT
    responseText = resp.text.lower()
    assert "active_status" in responseText or "active status" in responseText


@pytest.mark.asyncio
async def test_update_card_lost_update_prevented_independent_sessions(
    test_engine,
    db_session: AsyncSession,
) -> None:
    """Two INDEPENDENT transactions race; the row lock yields one winner + one conflict.

    Genuine lost-update reproduction (QA findings M-10 + M-11). It replaces the
    prior test, which mutated a single SHARED session's identity map with
    ``synchronize_session=False`` and so did not prove independent behavior.
    Here two GENUINELY independent ``AsyncSession``s (each its own connection and
    transaction) contend for a COMMITTED card:

    * The LOSER transaction reads the card FIRST on its own connection, so its
      before-image is the original name -- exactly as a user who opened the
      update screen before anyone else committed.
    * The WINNER transaction independently runs the full ``CardService.UpdateCard``
      and COMMITS first. Its locking re-read (``CardRepository.GetForUpdate`` ->
      ``SELECT ... FOR UPDATE``, QA finding M-10) sees the unchanged original,
      so its before-image check passes and it writes ``CONFLICT_WINNER_NAME``.
    * The LOSER then runs the full update. Its before-image is the stale
      original it read earlier; the locking re-read now observes the WINNER's
      committed name (``populate_existing`` refreshes the identity-mapped row to
      the true current state), so the before-image check raises
      ``OptimisticLockError`` (COCRDUPC 9300). Its write is rejected, so the
      WINNER's change is NOT lost.

    Asserts one winner, one explicit conflict, and the final committed state
    (exactly the winner's value), directly at the service + repository layer
    against real PostgreSQL row locks.

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
    await SeedConflictCardCommitted(sessionMaker)
    service = CardService()
    repository = CardRepository()

    async with sessionMaker() as sessionWinner, sessionMaker() as sessionLoser:
        # The LOSER reads the row FIRST on its own connection (before-image =
        # ORIGINAL). Keeping this instance in the loser session's identity map
        # is what makes its later UpdateCard reuse the now-stale before-image.
        loserCard = await repository.GetByCardNum(sessionLoser, CONFLICT_CARD_NUM)
        assert loserCard is not None
        assert loserCard.embossed_name == CONFLICT_ORIGINAL_NAME

        # The WINNER independently performs the full update and COMMITS first
        # under the SELECT ... FOR UPDATE lock (ORIGINAL -> WINNER).
        winnerResult = await service.UpdateCard(
            sessionWinner,
            CONFLICT_CARD_NUM,
            CardUpdate(
                before_image=CardBeforeImage(
                    embossed_name=CONFLICT_ORIGINAL_NAME,
                    active_status="Y",
                    expiration_date=CONFLICT_CARD_EXPIRY,
                ),
                embossed_name=CONFLICT_WINNER_NAME,
                expiration_date=CONFLICT_CARD_EXPIRY,
                active_status="Y",
            ),
        )
        assert winnerResult.embossed_name == CONFLICT_WINNER_NAME

        # The LOSER now runs the full update. Its before-image is the stale
        # ORIGINAL; the locking re-read observes the WINNER's committed name, so
        # the before-image check raises the explicit conflict (no lost update).
        with pytest.raises(OptimisticLockError):
            await service.UpdateCard(
                sessionLoser,
                CONFLICT_CARD_NUM,
                CardUpdate(
                    before_image=CardBeforeImage(
                        embossed_name=CONFLICT_ORIGINAL_NAME,
                        active_status="Y",
                        expiration_date=CONFLICT_CARD_EXPIRY,
                    ),
                    embossed_name=CONFLICT_LOSER_NAME,
                    expiration_date=CONFLICT_CARD_EXPIRY,
                    active_status="Y",
                ),
            )

    # Final committed state: exactly the WINNER's change survived; the LOSER's
    # write was rejected (one winner, one conflict, no lost update).
    async with sessionMaker() as verifySession:
        finalCard = await CardRepository().GetByCardNum(verifySession, CONFLICT_CARD_NUM)
        assert finalCard is not None
        assert finalCard.embossed_name == CONFLICT_WINNER_NAME
        assert finalCard.embossed_name != CONFLICT_LOSER_NAME


@pytest.mark.asyncio
async def test_card_get_for_update_serializes_concurrent_writers(
    test_engine,
    db_session: AsyncSession,
) -> None:
    """SELECT ... FOR UPDATE blocks a second writer until the first commits (M-10).

    The deterministic proof that the row lock added in M-10
    (``CardRepository.GetForUpdate`` -> ``SELECT ... FOR UPDATE``) genuinely
    serializes two independent transactions, orchestrated with an explicit lock
    barrier (a bare ``asyncio.gather`` cannot prove this: the first writer may
    commit before the second even reads, so no contention is forced). Two
    GENUINELY independent sessions contend for one COMMITTED card:

    1. Transaction TWO reads the row first, capturing the ORIGINAL before-image.
    2. Transaction ONE acquires the row lock (``GetForUpdate``) and HOLDS it.
    3. Transaction TWO's own ``GetForUpdate`` is launched as a task and is
       observed to BLOCK while ONE holds the lock -- the direct evidence that
       the lock serializes writers (a non-locking read would not block).
    4. Transaction ONE commits its change (ORIGINAL -> WINNER) and releases the
       lock; TWO then unblocks and its ``populate_existing`` re-read observes the
       committed WINNER, NOT the stale ORIGINAL it first read -- so the before
       -image check would reject its write, preventing the lost update.

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
    await SeedConflictCardCommitted(sessionMaker)
    repository = CardRepository()

    async with sessionMaker() as sessionOne, sessionMaker() as sessionTwo:
        # (1) TWO captures the ORIGINAL before-image on its own connection.
        beforeCard = await repository.GetByCardNum(sessionTwo, CONFLICT_CARD_NUM)
        assert beforeCard is not None
        beforeName = beforeCard.embossed_name
        assert beforeName == CONFLICT_ORIGINAL_NAME

        # (2) ONE acquires the row lock FOR UPDATE and holds it (no commit yet).
        lockedOne = await repository.GetForUpdate(sessionOne, CONFLICT_CARD_NUM)
        assert lockedOne is not None

        # (3) TWO's locking read MUST block while ONE holds the lock.
        blockedTask = asyncio.create_task(
            repository.GetForUpdate(sessionTwo, CONFLICT_CARD_NUM)
        )
        await asyncio.sleep(LOCK_WAIT_SECONDS)
        assert not blockedTask.done()

        # (4) ONE commits ORIGINAL -> WINNER, releasing the lock.
        lockedOne.embossed_name = CONFLICT_WINNER_NAME
        await repository.Update(sessionOne, lockedOne)
        await sessionOne.commit()

        # TWO now unblocks; its locked, populate_existing re-read observes the
        # committed WINNER -- not the stale ORIGINAL it first read.
        lockedTwo = await blockedTask
        assert lockedTwo is not None
        assert lockedTwo.embossed_name == CONFLICT_WINNER_NAME
        assert lockedTwo.embossed_name != beforeName


# ===========================================================================
# Phase 5 -- role scoping (regular vs admin).
# ===========================================================================


@pytest.mark.asyncio
async def test_regular_client_cards_scoped(
    regular_client: AsyncClient,
    seed_data: None,
) -> None:
    """A regular user's browse succeeds and still respects the F-004 limit.

    Regular users are scoped to their own account's cards on the service side;
    this test asserts only that the endpoint succeeds, never returns more than
    seven rows, and masks every row (no specific count is assumed).

    Args:
        regular_client: Authenticated regular-user client (USER0001, 'U').
        seed_data: Golden-master seed fixture.
    """
    resp = await regular_client.get(CARDS_URL)

    assert resp.status_code == HTTP_OK
    responseBody = resp.json()
    assert len(responseBody["items"]) <= MAX_ROWS_PER_PAGE
    for item in responseBody["items"]:
        AssertCardMaskedNoCvv(item)
