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

import pytest
from httpx import AsyncClient
from sqlalchemy import update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.models.card import Card

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
STALE_EMBOSSED_NAME = "STALE CARDHOLDER"
OUT_OF_BAND_NAME = "OTHER CARDHOLDER"
VALID_EMBOSSED_NAME = "VALID CARDHOLDER"

# Query-string and body edge values exercised by the tests.
OVERSIZED_PAGE_SIZE = 50
SECOND_PAGE = 2
INVALID_ACTIVE_STATUS = "X"

# Precise HTTP status codes asserted by the tests (never a broad "not 2xx").
HTTP_OK = 200
HTTP_BAD_REQUEST = 400
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
) -> dict:
    """Build a ``CardUpdate`` request body from the three editable fields.

    Groups the editable card fields into one JSON-serializable object (the
    request body accepts only the embossed name, expiration date, and active
    status; the identifiers and the CVV are never in the body).

    Args:
        embossedName: New embossed name (alphabetic + spaces).
        expirationDate: Expiry date as ISO ``YYYY-MM-DD`` text.
        activeStatus: Active-status flag, ``'Y'`` or ``'N'``.

    Returns:
        A dict matching the ``CardUpdate`` schema, ready to pass as ``json=``.
    """
    return {
        "embossed_name": embossedName,
        "expiration_date": expirationDate,
        "active_status": activeStatus,
    }


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
    1240-EDIT-CARDSTATUS), so an ``'X'`` fails validation and the API responds
    with a client-error status (400 or 422) that names the offending field.

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

    assert resp.status_code in (HTTP_BAD_REQUEST, HTTP_UNPROCESSABLE_CONTENT)
    responseText = resp.text.lower()
    assert "active_status" in responseText or "active status" in responseText


@pytest.mark.asyncio
async def test_update_card_optimistic_conflict(
    admin_client: AsyncClient,
    seed_data: None,
    db_session: AsyncSession,
) -> None:
    """A concurrent out-of-band change makes the update fail with HTTP 409.

    Mirrors the COCRDUPC ``9300-CHECK-CHANGE-IN-REC`` before-image mismatch.
    ``CardService.UpdateCard`` loads the row, snapshots that as the before-image,
    then re-reads (``session.refresh``) and compares; a divergence raises the
    legacy "Record changed by some one else" conflict (AAP 0.7.4). To reproduce
    it deterministically the test must recreate the exact state the legacy
    READ-UPDATE window held:

    * Pin the stale row in the SHARED session's identity map by loading it with
      ``db_session.get`` and keeping a strong reference (``staleRow``). Without a
      live reference SQLAlchemy's weakly-referenced identity map would drop the
      row, and the service's ``GetByCardNum`` would then load a FRESH row that
      already reflects the out-of-band change -- no before-image, no conflict.
    * Mutate the row out-of-band on the SAME session with an ORM-Core ``update``
      and ``synchronize_session=False`` so the pinned in-memory copy stays stale.
      The mutation runs on ``db_session`` (not a separate connection) because the
      seed rows are flushed-not-committed and are therefore invisible to any
      other connection.

    When the PUT then runs, the handler's ``GetByCardNum`` returns the pinned
    stale row (before-image = original name), the internal re-read observes the
    changed name, and the service raises HTTP 409.

    Args:
        admin_client: Authenticated administrator client (shares ``db_session``).
        seed_data: Golden-master seed fixture.
        db_session: The shared async session the request handlers also use.
    """
    getResp = await admin_client.get(f"{CARDS_URL}/{SEED_CARD_NUM}")
    assert getResp.status_code == HTTP_OK
    beforeImage = getResp.json()

    # Pin the stale before-image row in the shared identity map so the PUT
    # handler's re-read observes THIS object (COCRDUPC 9300 before-image).
    staleRow = await db_session.get(Card, SEED_CARD_NUM)
    assert staleRow is not None

    # Out-of-band change on the SAME session (seed rows are flush-only and thus
    # invisible to any other connection); synchronize_session=False leaves the
    # pinned in-memory copy stale so the service still holds the old image.
    await db_session.execute(
        update(Card)
        .where(Card.card_num == SEED_CARD_NUM)
        .values(embossed_name=OUT_OF_BAND_NAME)
        .execution_options(synchronize_session=False)
    )

    # Submit a genuine change (a new name) so the update is not a no-op; the
    # stale before-image vs the re-read current row triggers the 409 conflict.
    updatePayload = BuildUpdatePayload(
        STALE_EMBOSSED_NAME,
        beforeImage["expiration_date"],
        beforeImage["active_status"],
    )
    putResp = await admin_client.put(f"{CARDS_URL}/{SEED_CARD_NUM}", json=updatePayload)

    assert putResp.status_code == HTTP_CONFLICT
    assert "Record changed by some one else" in str(putResp.json())

    # Retain the pinned reference through the request so the stale identity-map
    # row cannot be garbage-collected before the handler re-reads it.
    assert staleRow is not None


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

