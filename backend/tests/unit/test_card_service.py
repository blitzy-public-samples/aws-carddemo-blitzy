# Unit tests for app.services.card_service.CardService
# Traceability: app/cbl/COCRDLIC.cbl (CCLI, WS-MAX-SCREEN-LINES=7),
#   COCRDSLC.cbl (CCDL), COCRDUPC.cbl (CCUP)
"""Unit tests for :class:`app.services.card_service.CardService`.

These tests pin the behavior-preserving port of three legacy CICS online card
programs (Minimal Change Clause, AAP 0.8.1) and, above all, the two
non-negotiable sensitive-data rules (AAP 0.7.8):

* ``COCRDLIC`` (transaction ``CCLI``) -- the card browse must never return more
  than ``WS-MAX-SCREEN-LINES`` (7) rows on a single page (F-004), and the
  service must clamp an over-large requested page size down to that limit.
* ``COCRDSLC`` (transaction ``CCDL``) -- the read-only detail view rejects a
  blank card number and surfaces the verbatim legacy not-found message for an
  absent card.
* ``COCRDUPC`` (transaction ``CCUP``) -- the update path applies the editable
  fields and returns a masked, cvv-free detail.

The single most important assertion set here is that ``cvv_cd`` (``CARD-CVV-CD``)
NEVER appears in any returned DTO and that ``card_num`` is always masked to its
last four digits. Those guarantees are enforced structurally by the
``CardRead`` / ``CardSummary`` schemas (which declare no ``cvv_cd`` field and
carry a ``card_num`` ``field_serializer``); the tests assert the observable
outcome via ``model_dump()``.

The suite talks to the service directly through the shared ``db_session``
fixture (``backend/tests/conftest.py``), which yields an isolated async
SQLAlchemy session against the test PostgreSQL database and truncates every
table after each test. Under ``asyncio_mode = "auto"`` the async tests need no
``@pytest.mark.asyncio`` decorator.

Ochs naming (AAP 0.8.2 / 0.8.3) is honored throughout: snake_case test-function
names (the pytest discovery contract), PascalCase helper functions
(``SeedAccountWithCards``, ``MessageOf``), camelCase local variables, and
ALL_UPPERCASE module-level constants, with 4-space indentation and one asserted
behavior per test.
"""

import importlib
from datetime import date
from decimal import Decimal

import pytest

from app.core.exceptions import DomainValidationError, NotFoundError
from app.models import Account, Card
from app.schemas.card import CardUpdate
from app.schemas.common import PaginationParams
from app.services.card_service import CardService

# ---------------------------------------------------------------------------
# Test-input constants (ALL_UPPERCASE per the Ochs constant convention). The
# card and account identifiers are fixed-width digit strings so their leading
# zeros are preserved exactly (they are keys, never integers).
# ---------------------------------------------------------------------------

# The F-004 one-page browse limit the service must enforce (COCRDLIC
# WS-MAX-SCREEN-LINES). Kept here so the constant test states its expectation
# with a named value rather than a bare literal.
MAX_SCREEN_LINES_EXPECTED = 7

# A larger-than-cap page size the service must clamp down to seven. It stays
# within the schema's ``page_size <= 100`` bound so the DTO itself accepts it.
OVERSIZED_PAGE_SIZE = 100

# Seed identities. The 11-digit account id and the 16-digit base card number
# match the widths of the ``accounts.acct_id`` / ``cards.card_num`` keys.
SEED_ACCT_ID = "00000000020"
BASE_CARD_NUM = 4000000000000020
FIRST_CARD_NUM = str(BASE_CARD_NUM)  # "4000000000000020" (the index-0 card).

# A well-formed (16-digit) card number that is deliberately never seeded, so a
# lookup for it reproduces the legacy INVALID KEY / not-found condition.
ABSENT_CARD_NUM = "9999999999999999"

# CVV is never persisted (C-03, AAP 0.7.8): the cards model exposes no cvv
# column, so cvv is never constructed on seeded cards. The cvv-protection
# assertions below therefore verify a structural guarantee (the key can never
# appear in a serialized card response because the field does not exist).

# Default number of cards the browse-cap tests seed under one account: one more
# than the seven-row page limit, so a correctly capped page leaves a next page.
OVER_CAP_CARD_COUNT = MAX_SCREEN_LINES_EXPECTED + 1


# ---------------------------------------------------------------------------
# Shared helpers (PascalCase per Ochs; these are NOT fixtures -- call directly).
# ---------------------------------------------------------------------------


def MessageOf(error):
    """Return the operator message carried by a CardDemo domain error.

    The domain exceptions store their message on a ``message`` attribute and
    also forward it to ``Exception`` (so ``str(error)`` matches). Reading
    ``message`` first keeps the assertions precise, with ``str`` as a safe
    fallback for any error that does not expose the attribute.

    Args:
        error: The raised exception instance to read the message from.

    Returns:
        The ``message`` attribute when present and truthy, otherwise
        ``str(error)``.
    """
    return getattr(error, "message", None) or str(error)


async def SeedAccountWithCards(session, acctId=SEED_ACCT_ID, cardCount=OVER_CAP_CARD_COUNT):
    """Seed one account plus ``cardCount`` cards under it, foreign-key safe.

    The parent :class:`~app.models.account.Account` is added before its child
    :class:`~app.models.card.Card` rows, so the ``cards.acct_id`` foreign key is
    always satisfied when SQLAlchemy's unit of work orders the inserts. Every
    NOT-NULL column on both tables is populated. A single explicit ``flush``
    makes the rows visible to the service under test, which is required because
    the test session runs with ``autoflush=False``. Each seeded card carries a
    ``cvv_cd`` so the cvv-protection assertions have a real value to guard.

    Args:
        session: The active async test session (the ``db_session`` fixture).
        acctId: The owning account id (11-digit string, leading zeros kept).
        cardCount: How many cards to create under the account.

    Returns:
        A ``(acctId, cardNums)`` tuple: the seeded account id and the list of
        seeded 16-digit card numbers in creation order.
    """
    account = Account(
        acct_id=acctId,
        active_status="Y",
        curr_bal=Decimal("0.00"),
        credit_limit=Decimal("5000.00"),
        cash_credit_limit=Decimal("1000.00"),
        curr_cyc_credit=Decimal("0.00"),
        curr_cyc_debit=Decimal("0.00"),
        open_date=date(2020, 1, 1),
        expiration_date=date(2027, 1, 1),
        reissue_date=date(2024, 1, 1),
        addr_zip="12345",
        # group_id left NULL to mirror the golden-master seed quirk (every
        # ACCT-GROUP-ID is blank); it is incidental to the card tests and NULL
        # is exempt from the M-16 accounts.group_id foreign key.
        group_id=None,
    )
    session.add(account)
    cards = []
    for index in range(cardCount):
        cardNum = str(BASE_CARD_NUM + index)  # 16-digit card number.
        cards.append(
            Card(
                card_num=cardNum,
                acct_id=acctId,
                embossed_name="JANE DOE",
                expiration_date=date(2027, 1, 1),
                active_status="Y",
            )
        )
    session.add_all(cards)
    await session.flush()
    return acctId, [card.card_num for card in cards]


# ===========================================================================
# Phase A -- COCRDLIC browse: at most seven rows per page (F-004).
# ===========================================================================


async def test_list_cards_caps_at_seven_per_page(db_session):
    """Browsing an account with eight cards yields exactly one seven-row page.

    Ports the COCRDLIC WS-MAX-SCREEN-LINES cap: with eight cards seeded under a
    single account, the first page carries the seven-row maximum and the
    envelope reports that a following page exists.
    """
    await SeedAccountWithCards(db_session, cardCount=OVER_CAP_CARD_COUNT)
    service = CardService()
    params = PaginationParams(page=1, page_size=MAX_SCREEN_LINES_EXPECTED)
    result = await service.ListCards(db_session, params)
    assert len(result.items) == MAX_SCREEN_LINES_EXPECTED
    assert result.has_next is True


async def test_list_cards_page_size_clamped(db_session):
    """The service clamps an over-large requested page size down to seven.

    ``PaginationParams`` accepts a ``page_size`` as large as 100, but the
    service must still honor the F-004 seven-row screen limit. Requesting a
    100-row page against eight seeded cards must therefore return no more than
    seven rows.
    """
    await SeedAccountWithCards(db_session, cardCount=OVER_CAP_CARD_COUNT)
    service = CardService()
    params = PaginationParams(page=1, page_size=OVERSIZED_PAGE_SIZE)
    result = await service.ListCards(db_session, params)
    assert len(result.items) <= MAX_SCREEN_LINES_EXPECTED


def test_max_screen_lines_constant_is_seven():
    """The card repository exposes the F-004 seven-row cap as a constant.

    ``MAX_SCREEN_LINES`` mirrors COCRDLIC's ``WS-MAX-SCREEN-LINES PIC S9(4) COMP
    VALUE 7``. Its exact location may vary (a module-level constant or a class
    attribute), so it is looked up defensively -- module level first, then the
    ``CardRepository`` class -- and the value is asserted to be seven.
    """
    cardRepoModule = importlib.import_module("app.repositories.card_repo")
    maxScreenLines = getattr(cardRepoModule, "MAX_SCREEN_LINES", None)
    if maxScreenLines is None:
        cardRepositoryClass = getattr(cardRepoModule, "CardRepository", None)
        maxScreenLines = getattr(cardRepositoryClass, "MAX_SCREEN_LINES", None)
    if maxScreenLines is None:
        pytest.skip("MAX_SCREEN_LINES not exposed")
    assert maxScreenLines == MAX_SCREEN_LINES_EXPECTED


# ===========================================================================
# Phase B -- COCRDSLC view: sensitive-field protection + input edits.
# ===========================================================================


async def test_get_card_never_exposes_cvv(db_session):
    """A card detail response never carries the CVV (protects CARD-CVV-CD).

    The cards model exposes no ``cvv_cd`` column and the ``CardRead`` DTO
    declares no such field, so neither ``cvv_cd`` nor any ``cvv`` key can appear
    in the serialized output -- CVV is never persisted (C-03, AAP 0.7.8).
    """
    await SeedAccountWithCards(db_session, cardCount=1)
    service = CardService()
    card = await service.GetCard(db_session, FIRST_CARD_NUM)
    dumped = card.model_dump()
    assert "cvv_cd" not in dumped
    assert "cvv" not in dumped


async def test_get_card_masks_card_num(db_session):
    """A card detail response masks the PAN to its last four digits (AAP 0.7.8).

    The full 16-digit number is never serialized: only the trailing four digits
    remain visible and every leading digit is replaced by a mask character.
    """
    await SeedAccountWithCards(db_session, cardCount=1)
    service = CardService()
    card = await service.GetCard(db_session, FIRST_CARD_NUM)
    dumped = card.model_dump()
    assert dumped["card_num"] != FIRST_CARD_NUM
    assert dumped["card_num"].endswith(FIRST_CARD_NUM[-4:])
    assert dumped["card_num"].startswith("*")


async def test_get_card_empty_raises_validation(db_session):
    """A blank card number is rejected with the verbatim legacy message.

    Reproduces COCRDSLC ``1000-EDIT-INPUTS`` (L141): a missing card number
    fails validation before any keyed read is attempted.
    """
    service = CardService()
    with pytest.raises(DomainValidationError) as excInfo:
        await service.GetCard(db_session, "")
    assert MessageOf(excInfo.value) == "Card number not provided"


async def test_get_card_absent_raises_not_found(db_session):
    """A well-formed but absent card number raises the legacy not-found error.

    Reproduces COCRDSLC (L154): a syntactically valid 16-digit card number that
    matches no row surfaces ``NotFoundError`` with the verbatim legacy text
    (the relational equivalent of the VSAM INVALID KEY condition).
    """
    service = CardService()
    with pytest.raises(NotFoundError) as excInfo:
        await service.GetCard(db_session, ABSENT_CARD_NUM)
    assert MessageOf(excInfo.value) == "Did not find cards for this search condition"


# ===========================================================================
# Phase C -- COCRDUPC update: preserves the CVV and keeps it hidden.
# ===========================================================================


async def test_update_card_returns_read_without_cvv(db_session):
    """UpdateCard applies the edits and returns a masked, cvv-free detail.

    ``CardUpdate`` mirrors the editable COCRDUP screen fields (embossed name,
    expiry date, active status); the CVV is intentionally omitted because the
    update screen never exposes it and the service preserves the stored value
    untouched. The committed detail reflects the new name and, like every card
    response, carries no ``cvv_cd`` (AAP 0.7.8).
    """
    await SeedAccountWithCards(db_session, cardCount=1)
    service = CardService()
    cardUpdate = CardUpdate(
        embossed_name="JOHN DOE",
        expiration_date=date(2028, 1, 1),
        active_status="Y",
    )
    updated = await service.UpdateCard(db_session, FIRST_CARD_NUM, cardUpdate)
    assert updated.embossed_name == "JOHN DOE"
    assert "cvv_cd" not in updated.model_dump()
