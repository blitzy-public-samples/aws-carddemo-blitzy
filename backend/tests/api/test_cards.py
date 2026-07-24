# Integration tests for the /cards endpoints (app/api/v1/cards.py).
# Traceability: app/cbl/COCRDLIC.cbl (list, CCLI), COCRDSLC.cbl (view, CCDL),
#   COCRDUPC.cbl (update, CCUP); record layout app/cpy/CVACT02Y.cpy.
#
#   QA issues C05/C07/C08: card detail/update are keyed by the card number the
#   operator ENTERS (legacy COCRDSL/COCRDUP CARDSID unprotected input), exactly
#   the AAP 0.5.5 frozen contract GET/PUT /cards/{cardNum}. The earlier
#   by-account helper routes were removed: they resolved an account to a single
#   card with .limit(1), which silently selects the WRONG card on the NONUNIQUE
#   account->card relationship (C07), and were two operations beyond the frozen
#   endpoint list (C08). These tests pin the {cardNum}-keyed view/update: they
#   keep card_num masked, never leak the CVV, and surface the verbatim legacy
#   validation/not-found messages.
#
#   QA issue C06: the update path performs a COACTUPC-style optimistic
#   concurrency check. The client echoes the before-image it last read; the
#   service re-reads the row under SELECT ... FOR UPDATE and, if the persisted
#   values have diverged from that before-image, raises OptimisticLockError
#   (HTTP 409) instead of silently overwriting a concurrent change.
#
#   QA issue C3: the card-list search fields were a silent no-op because the
#   handler bound the filter-free PaginationParams, so ?acct_id= / ?card_num=
#   never reached the service. The fix binds CardListParams (exposing both
#   filters) and adds an exact card_num filter. These tests pin that both
#   filters now actually narrow the browse and that a blank filter is ignored.
"""Integration tests for the card view/update/list endpoints (QA C05-C08, C3, C06).

The tests exercise the live FastAPI request path (routing, dependency
resolution, response-model serialization) through the ``admin_client`` fixture
-- an httpx ASGI client authenticated as the seeded administrator and sharing
the test ``db_session`` -- against the golden-master cards seeded by
``seed_cards_customers_xref``. Administrator identity is used so the COCRDLIC
role gate lists all cards and the keyed reads are not confined to a single
session account.

Sensitive-data guarantees are asserted alongside behavior: ``card_num`` is
always MASKED to its last four digits and the card verification value
(``cvv_cd``) is NEVER present in any response body (AAP 0.7.8).
"""

from __future__ import annotations

import pytest
from httpx import AsyncClient

from app.core.config import settings
from app.services.card_service import (
    MSG_CARD_NOT_FOUND,
    MSG_CARD_NUM_INVALID,
    MSG_CARD_NUM_NOT_PROVIDED,
    MSG_RECORD_CHANGED,
)

# ---------------------------------------------------------------------------
# Module constants (ALL_UPPERCASE per the Ochs Rule 0.8.2).
# ---------------------------------------------------------------------------

# Card resource path built from settings so the API version prefix is never
# hardcoded here (the /api/v1 prefix is mounted once by app.main).
CARDS_PATH = f"{settings.API_V1_PREFIX}/cards"

# SEED-ONLY golden-master card values (app/data/ASCII/carddata.txt) for the
# card the tests view and update. Card detail/update are keyed by the ENTERED
# 16-digit card number (legacy COCRDSL/COCRDUP CARDSID input); the account id is
# retained only to assert the response's owning-account field and to drive the
# C3 ?acct_id= browse filter.
SEED_ACCT_ID = "00000000050"
SEED_CARD_NUM = "0500024453765740"
SEED_EMBOSSED_NAME = "Aniya Von"
SEED_EXPIRATION_DATE = "2023-03-09"
SEED_ACTIVE_STATUS = "Y"

# The masked projection the response schema must emit for SEED_CARD_NUM: twelve
# mask characters followed by the last four digits (AAP 0.7.8).
MASKED_SEED_CARD_NUM = "************5740"

# A syntactically valid 16-digit PAN that is NOT seeded, used to drive the keyed
# not-found (404) branch and the empty-result card_num filter branch.
UNKNOWN_CARD_NUM = "9999999999999999"

# A card number that fails the 16-digit edit (too short), used to drive the
# keyed-view validation (422 "must be a 16 digit number") branch.
INVALID_CARD_NUM = "0000"

# New embossed name for the successful update (alphabetic + spaces so it passes
# the COCRDUPC 1230-EDIT-NAME edit; differs from the seed so it is never a
# no-op, which the service rejects as "No change detected").
UPDATED_EMBOSSED_NAME = "Aniya Vonn"

# A deliberately STALE before-image embossed name that does NOT match the
# committed row, used to drive the C06 optimistic-lock conflict (409) branch.
STALE_EMBOSSED_NAME = "Stale Holder"

# The total number of seeded cards (matches the golden-master carddata.txt).
TOTAL_SEED_CARDS = 50

# HTTP status codes asserted below.
HTTP_OK = 200
HTTP_NOT_FOUND = 404
HTTP_CONFLICT = 409
HTTP_UNPROCESSABLE = 422

# JSON body keys.
DETAIL_KEY = "detail"


# ---------------------------------------------------------------------------
# Helper: build a CardUpdate request body carrying the client-echoed
# before-image optimistic-lock token (C06). Grouped so each test states only
# the values it varies (Ochs Rule: <=4 parameters, small helpers).
# ---------------------------------------------------------------------------
def BuildCardUpdateBody(
    embossedName: str,
    beforeName: str = SEED_EMBOSSED_NAME,
) -> dict:
    """Build a ``CardUpdate`` body with an echoed before-image.

    Args:
        embossedName: The new embossed name to submit (the only edited field;
            expiry and status are re-sent unchanged from the seed values).
        beforeName: The embossed name in the before-image the client echoes.
            Defaults to the committed seed name (a coherent, non-stale image);
            pass a diverging value to simulate a lost update (C06).

    Returns:
        A dict matching the ``CardUpdate`` schema, ready to pass as ``json=``.
    """
    return {
        "before_image": {
            "embossed_name": beforeName,
            "active_status": SEED_ACTIVE_STATUS,
            "expiration_date": SEED_EXPIRATION_DATE,
        },
        "embossed_name": embossedName,
        "expiration_date": SEED_EXPIRATION_DATE,
        "active_status": SEED_ACTIVE_STATUS,
    }


# ===========================================================================
# QA C05/C07/C08 -- keyed card VIEW (GET /cards/{cardNum}).
# ===========================================================================
@pytest.mark.asyncio
async def test_get_card_by_card_num_returns_masked_card(
    admin_client: AsyncClient,
    seed_cards_customers_xref: None,
) -> None:
    """The keyed view returns the entered card with card_num masked, no CVV.

    Pins the AAP 0.5.5 contract GET /cards/{cardNum}: the operator-entered
    16-digit card number resolves to that exact card, and the response keeps
    ``card_num`` masked and omits the CVV entirely (AAP 0.7.8).
    """
    response = await admin_client.get(f"{CARDS_PATH}/{SEED_CARD_NUM}")

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
async def test_get_card_by_card_num_not_found_returns_404(
    admin_client: AsyncClient,
    seed_cards_customers_xref: None,
) -> None:
    """A valid but absent 16-digit card number yields the verbatim 404.

    A syntactically valid card number that matches no row surfaces the legacy
    COCRDSLC not-found condition (the relational equivalent of VSAM INVALID
    KEY), distinguishing "no such card" from a validation error.
    """
    response = await admin_client.get(f"{CARDS_PATH}/{UNKNOWN_CARD_NUM}")

    assert response.status_code == HTTP_NOT_FOUND
    assert response.json()[DETAIL_KEY] == MSG_CARD_NOT_FOUND


@pytest.mark.asyncio
async def test_get_card_invalid_card_num_returns_422(
    admin_client: AsyncClient,
) -> None:
    """A card number that is not 16 digits fails the field edit with a 422.

    The keyed view validates the card number exactly as COCRDSLC 1000-EDIT-INPUTS
    did before touching the datastore (a too-short PAN fails the 16-digit edit).
    """
    response = await admin_client.get(f"{CARDS_PATH}/{INVALID_CARD_NUM}")

    assert response.status_code == HTTP_UNPROCESSABLE
    assert response.json()[DETAIL_KEY] == MSG_CARD_NUM_INVALID


@pytest.mark.asyncio
async def test_get_card_blank_card_num_returns_422(
    admin_client: AsyncClient,
) -> None:
    """A whitespace-only card number normalizes to blank -> "not provided".

    A whitespace-only path segment strips to an empty string, which the service
    rejects with the legacy COCRDSLC L141 "not provided" message (422), distinct
    from the "must be a 16 digit number" invalid-format message.
    """
    response = await admin_client.get(f"{CARDS_PATH}/%20")

    assert response.status_code == HTTP_UNPROCESSABLE
    assert response.json()[DETAIL_KEY] == MSG_CARD_NUM_NOT_PROVIDED


# ===========================================================================
# QA C05/C07/C08 + C06 -- keyed card UPDATE (PUT /cards/{cardNum}).
# ===========================================================================
@pytest.mark.asyncio
async def test_update_card_by_card_num_persists_change(
    admin_client: AsyncClient,
    seed_cards_customers_xref: None,
) -> None:
    """A keyed update applies an editable-field change and persists it.

    Pins the write half of the AAP 0.5.5 contract PUT /cards/{cardNum}: the
    client first reads the card (capturing the before-image), then submits a new
    embossed name echoing that before-image. The update applies the change,
    commits, and returns the masked card; a follow-up read confirms it
    persisted.
    """
    getResponse = await admin_client.get(f"{CARDS_PATH}/{SEED_CARD_NUM}")
    assert getResponse.status_code == HTTP_OK
    beforeImage = getResponse.json()

    payload = {
        "before_image": {
            "embossed_name": beforeImage["embossed_name"],
            "active_status": beforeImage["active_status"],
            "expiration_date": beforeImage["expiration_date"],
        },
        "embossed_name": UPDATED_EMBOSSED_NAME,
        "expiration_date": beforeImage["expiration_date"],
        "active_status": beforeImage["active_status"],
    }

    updateResponse = await admin_client.put(
        f"{CARDS_PATH}/{SEED_CARD_NUM}", json=payload
    )

    assert updateResponse.status_code == HTTP_OK
    updateBody = updateResponse.json()
    assert updateBody["embossed_name"] == UPDATED_EMBOSSED_NAME
    assert updateBody["acct_id"] == SEED_ACCT_ID
    # The write path keeps card_num masked and never returns the CVV.
    assert updateBody["card_num"] == MASKED_SEED_CARD_NUM
    assert "cvv_cd" not in updateBody

    # The change is durable: re-reading by card number returns the new name.
    readResponse = await admin_client.get(f"{CARDS_PATH}/{SEED_CARD_NUM}")
    assert readResponse.status_code == HTTP_OK
    assert readResponse.json()["embossed_name"] == UPDATED_EMBOSSED_NAME


@pytest.mark.asyncio
async def test_update_card_stale_before_image_returns_409(
    admin_client: AsyncClient,
    seed_cards_customers_xref: None,
) -> None:
    """A stale before-image is rejected as an optimistic-lock conflict (C06).

    Submitting an otherwise-valid edit whose echoed before-image embossed name
    does NOT match the committed row simulates a lost update: the service
    re-reads the row under SELECT ... FOR UPDATE, observes the divergence from
    the client's before-image, and raises OptimisticLockError -> HTTP 409 with
    the verbatim COCRDUPC L208 message, rather than silently overwriting.
    """
    payload = BuildCardUpdateBody(
        UPDATED_EMBOSSED_NAME,
        beforeName=STALE_EMBOSSED_NAME,
    )

    response = await admin_client.put(f"{CARDS_PATH}/{SEED_CARD_NUM}", json=payload)

    assert response.status_code == HTTP_CONFLICT
    assert response.json()[DETAIL_KEY] == MSG_RECORD_CHANGED


@pytest.mark.asyncio
async def test_update_card_not_found_returns_404(
    admin_client: AsyncClient,
    seed_cards_customers_xref: None,
) -> None:
    """A keyed update against an absent card yields the verbatim 404.

    A well-formed but unseeded card number reaches the datastore and finds no
    row, surfacing the legacy COCRDUPC not-found condition (404) rather than a
    silent create.
    """
    payload = BuildCardUpdateBody(UPDATED_EMBOSSED_NAME)

    response = await admin_client.put(f"{CARDS_PATH}/{UNKNOWN_CARD_NUM}", json=payload)

    assert response.status_code == HTTP_NOT_FOUND
    assert response.json()[DETAIL_KEY] == MSG_CARD_NOT_FOUND


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
