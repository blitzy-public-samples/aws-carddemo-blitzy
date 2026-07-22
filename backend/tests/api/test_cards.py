# Integration tests for the /cards endpoints (app/api/v1/cards.py).
# Traceability: app/cbl/COCRDLIC.cbl (list, CCLI), COCRDSLC.cbl (view, CCDL),
#   COCRDUPC.cbl (update, CCUP); record layout app/cpy/CVACT02Y.cpy.
#
#   QA issue C1: the card list masks card_num (AAP 0.7.8), so the UI cannot
#   navigate to card detail by the (masked) PAN -- the masked value can never be
#   a valid primary key, which produced an HTTP 422 dead-end and made card
#   view/update entirely unreachable. The fix adds by-owning-account endpoints
#   (GET/PUT /cards/by-account/{acctId}) that resolve an account to its card
#   server-side WITHOUT exposing the full PAN. These tests pin that the
#   by-account view/update work, keep card_num masked, never leak the CVV, and
#   are routed correctly (the literal by-account path is matched before the
#   /{cardNum} catch-all).
#
#   QA issue C3: the card-list search fields were a silent no-op because the
#   handler bound the filter-free PaginationParams, so ?acct_id= / ?card_num=
#   never reached the service. The fix binds CardListParams (exposing both
#   filters) and adds an exact card_num filter. These tests pin that both
#   filters now actually narrow the browse and that a blank filter is ignored.
"""Integration tests for the card list/view/update endpoints (QA C1 and C3).

The tests exercise the live FastAPI request path (routing, dependency
resolution, response-model serialization) through the ``admin_client`` fixture
-- an httpx ASGI client authenticated as the seeded administrator and sharing
the test ``db_session`` -- against the golden-master cards seeded by
``seed_cards_customers_xref``. Administrator identity is used so the COCRDLIC
role gate lists all cards and the by-account lookups are not confined to a
single session account.

Sensitive-data guarantees are asserted alongside behavior: ``card_num`` is
always MASKED to its last four digits and the card verification value
(``cvv_cd``) is NEVER present in any response body (AAP 0.7.8).
"""

from __future__ import annotations

import pytest
from httpx import AsyncClient

from app.core.config import settings
from app.services.card_service import (
    MSG_ACCT_NUM_INVALID,
    MSG_ACCT_NUM_NOT_PROVIDED,
    MSG_CARD_NOT_FOUND,
)

# ---------------------------------------------------------------------------
# Module constants (ALL_UPPERCASE per the Ochs Rule 0.8.2).
# ---------------------------------------------------------------------------

# Card resource path built from settings so the API version prefix is never
# hardcoded here (the /api/v1 prefix is mounted once by app.main).
CARDS_PATH = f"{settings.API_V1_PREFIX}/cards"
BY_ACCOUNT_PATH = f"{CARDS_PATH}/by-account"

# SEED-ONLY golden-master card values (app/data/ASCII/carddata.txt) for the
# account whose card the tests view and update. The seed relationship is 1:1
# (50 accounts, 50 cards), so an account uniquely identifies a card.
SEED_ACCT_ID = "00000000050"
SEED_CARD_NUM = "0500024453765740"
SEED_EMBOSSED_NAME = "Aniya Von"
SEED_EXPIRATION_DATE = "2023-03-09"
SEED_ACTIVE_STATUS = "Y"

# The masked projection the response schema must emit for SEED_CARD_NUM: twelve
# mask characters followed by the last four digits (AAP 0.7.8).
MASKED_SEED_CARD_NUM = "************5740"

# A syntactically valid, non-zero 11-digit account id that owns NO card, used to
# drive the by-account not-found (404) branch.
UNCARDED_ACCT_ID = "00000000099"

# A syntactically valid 16-digit PAN that is NOT seeded, used to drive the
# empty-result card_num filter branch.
UNKNOWN_CARD_NUM = "9999999999999999"

# An account id that fails the non-zero 11-digit edit (too short), used to drive
# the by-account validation (422) branch.
INVALID_ACCT_ID = "0000"

# The total number of seeded cards (matches the golden-master carddata.txt).
TOTAL_SEED_CARDS = 50

# HTTP status codes asserted below.
HTTP_OK = 200
HTTP_NOT_FOUND = 404
HTTP_UNPROCESSABLE = 422

# JSON body keys.
DETAIL_KEY = "detail"


# ===========================================================================
# QA C1 -- by-account card VIEW (GET /cards/by-account/{acctId}).
# ===========================================================================
@pytest.mark.asyncio
async def test_get_card_by_account_returns_masked_card(
    admin_client: AsyncClient,
    seed_cards_customers_xref: None,
) -> None:
    """By-account view returns the account's card with card_num masked, no CVV.

    Pins the core of the C1 fix: navigating by the unmasked account id resolves
    to that account's card, and the response keeps ``card_num`` masked and omits
    the CVV entirely.
    """
    response = await admin_client.get(f"{BY_ACCOUNT_PATH}/{SEED_ACCT_ID}")

    assert response.status_code == HTTP_OK
    body = response.json()
    assert body["acct_id"] == SEED_ACCT_ID
    # card_num is masked -- never the raw PAN.
    assert body["card_num"] == MASKED_SEED_CARD_NUM
    assert body["card_num"] != SEED_CARD_NUM
    assert body["embossed_name"] == SEED_EMBOSSED_NAME
    # The card verification value must never appear in a response (AAP 0.7.8).
    assert "cvv" not in body
    assert "cvv_cd" not in body


@pytest.mark.asyncio
async def test_get_card_by_account_not_found_returns_404(
    admin_client: AsyncClient,
    seed_cards_customers_xref: None,
) -> None:
    """A valid account that owns no card yields the verbatim not-found 404.

    Proves the by-account view correctly distinguishes "no card for this
    account" from a validation error, surfacing the legacy COCRDSLC message.
    """
    response = await admin_client.get(f"{BY_ACCOUNT_PATH}/{UNCARDED_ACCT_ID}")

    assert response.status_code == HTTP_NOT_FOUND
    assert response.json()[DETAIL_KEY] == MSG_CARD_NOT_FOUND


@pytest.mark.asyncio
async def test_get_card_by_account_invalid_id_returns_422(
    admin_client: AsyncClient,
) -> None:
    """A malformed account id fails the non-zero 11-digit edit with a 422.

    The by-account view validates the account id exactly as COACTVWC did before
    touching the datastore.
    """
    response = await admin_client.get(f"{BY_ACCOUNT_PATH}/{INVALID_ACCT_ID}")

    assert response.status_code == HTTP_UNPROCESSABLE
    assert response.json()[DETAIL_KEY] == MSG_ACCT_NUM_INVALID


@pytest.mark.asyncio
async def test_get_card_by_account_all_zeros_id_returns_422(
    admin_client: AsyncClient,
) -> None:
    """An all-zeroes account id is rejected by the non-zero edit (422).

    Mirrors the account_service all-zeroes rejection so account-id validation is
    consistent across the app.
    """
    response = await admin_client.get(f"{BY_ACCOUNT_PATH}/00000000000")

    assert response.status_code == HTTP_UNPROCESSABLE
    assert response.json()[DETAIL_KEY] == MSG_ACCT_NUM_INVALID


@pytest.mark.asyncio
async def test_by_account_path_is_matched_before_card_num_catch_all(
    admin_client: AsyncClient,
    seed_cards_customers_xref: None,
) -> None:
    """The literal /by-account/{acctId} route wins over /{cardNum} (ordering).

    If the by-account routes were declared AFTER the /{cardNum} catch-all,
    "by-account" would be captured as a card number and the request would fail
    the 16-digit card-number edit (a validation 422) instead of returning the
    account's card. A 200 with the account's masked card proves the load-bearing
    route ordering holds.
    """
    response = await admin_client.get(f"{BY_ACCOUNT_PATH}/{SEED_ACCT_ID}")

    assert response.status_code == HTTP_OK
    assert response.json()["card_num"] == MASKED_SEED_CARD_NUM


# ===========================================================================
# QA C1 -- by-account card UPDATE (PUT /cards/by-account/{acctId}).
# ===========================================================================
@pytest.mark.asyncio
async def test_update_card_by_account_persists_change(
    admin_client: AsyncClient,
    seed_cards_customers_xref: None,
) -> None:
    """By-account update applies an editable-field change and persists it.

    Pins the write half of the C1 fix: a PUT addressed by the unmasked account
    id resolves to the real card server-side, applies the change, commits, and
    returns the masked card. A follow-up read confirms the change persisted.
    """
    newName = "Aniya Vonn"
    payload = {
        "embossed_name": newName,
        "expiration_date": SEED_EXPIRATION_DATE,
        "active_status": SEED_ACTIVE_STATUS,
    }

    updateResponse = await admin_client.put(
        f"{BY_ACCOUNT_PATH}/{SEED_ACCT_ID}", json=payload
    )

    assert updateResponse.status_code == HTTP_OK
    updateBody = updateResponse.json()
    assert updateBody["embossed_name"] == newName
    assert updateBody["acct_id"] == SEED_ACCT_ID
    # The write path keeps card_num masked and never returns the CVV.
    assert updateBody["card_num"] == MASKED_SEED_CARD_NUM
    assert "cvv_cd" not in updateBody

    # The change is durable: re-reading by account returns the new name.
    readResponse = await admin_client.get(f"{BY_ACCOUNT_PATH}/{SEED_ACCT_ID}")
    assert readResponse.status_code == HTTP_OK
    assert readResponse.json()["embossed_name"] == newName


@pytest.mark.asyncio
async def test_update_card_by_account_invalid_id_returns_422(
    admin_client: AsyncClient,
) -> None:
    """A blank account id on the update path fails the not-provided edit (422)."""
    payload = {
        "embossed_name": SEED_EMBOSSED_NAME,
        "expiration_date": SEED_EXPIRATION_DATE,
        "active_status": SEED_ACTIVE_STATUS,
    }

    # A whitespace-only path segment normalizes to blank -> "not provided".
    response = await admin_client.put(f"{BY_ACCOUNT_PATH}/%20", json=payload)

    assert response.status_code == HTTP_UNPROCESSABLE
    assert response.json()[DETAIL_KEY] == MSG_ACCT_NUM_NOT_PROVIDED


# ===========================================================================
# QA C3 -- card-list search filters (GET /cards?acct_id=&card_num=).
# ===========================================================================
@pytest.mark.asyncio
async def test_list_cards_filter_by_acct_id(
    admin_client: AsyncClient,
    seed_cards_customers_xref: None,
) -> None:
    """The ?acct_id= filter narrows the browse to that account's card(s).

    Before the fix this parameter was dropped by the router. Now it reaches the
    service: with a 1:1 seed the account matches exactly one card and the
    authoritative total reflects the filter.
    """
    response = await admin_client.get(CARDS_PATH, params={"acct_id": SEED_ACCT_ID})

    assert response.status_code == HTTP_OK
    body = response.json()
    assert body["total_items"] == 1
    assert len(body["items"]) == 1
    assert body["items"][0]["acct_id"] == SEED_ACCT_ID
    assert body["items"][0]["card_num"] == MASKED_SEED_CARD_NUM


@pytest.mark.asyncio
async def test_list_cards_filter_by_card_num_exact(
    admin_client: AsyncClient,
    seed_cards_customers_xref: None,
) -> None:
    """The ?card_num= filter returns exactly the one matching card (masked).

    An exact 16-digit PAN identifies a single card, returned as a one-row page
    with the number still masked in the response.
    """
    response = await admin_client.get(CARDS_PATH, params={"card_num": SEED_CARD_NUM})

    assert response.status_code == HTTP_OK
    body = response.json()
    assert body["total_items"] == 1
    assert len(body["items"]) == 1
    assert body["items"][0]["card_num"] == MASKED_SEED_CARD_NUM
    assert body["items"][0]["acct_id"] == SEED_ACCT_ID


@pytest.mark.asyncio
async def test_list_cards_filter_by_card_num_no_match_is_empty(
    admin_client: AsyncClient,
    seed_cards_customers_xref: None,
) -> None:
    """A card_num that matches nothing yields a truthful empty page.

    A non-matching (but syntactically valid) search term returns zero rows
    rather than an error, so the search box degrades gracefully.
    """
    response = await admin_client.get(CARDS_PATH, params={"card_num": UNKNOWN_CARD_NUM})

    assert response.status_code == HTTP_OK
    body = response.json()
    assert body["total_items"] == 0
    assert body["items"] == []


@pytest.mark.asyncio
async def test_list_cards_blank_filter_is_ignored(
    admin_client: AsyncClient,
    seed_cards_customers_xref: None,
) -> None:
    """A blank filter value is treated as "no filter" (untouched search box).

    An empty ``acct_id`` query value must NOT match the empty string and hide
    every card; it browses normally. As admin the unfiltered total is every
    seeded card.
    """
    response = await admin_client.get(CARDS_PATH, params={"acct_id": ""})

    assert response.status_code == HTTP_OK
    assert response.json()["total_items"] == TOTAL_SEED_CARDS
