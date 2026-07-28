# Cross-cutting integration tests for sensitive-data protection (AAP 0.7.8).
# Systematically asserts across API responses that:
#   * card_num is MASKED ("*"*12 + last4) in every response (CardRead/CardSummary/TransactionRead)
#   * ssn is MASKED (last 4 only) in CustomerRead (embedded in AccountDetail)
#   * cvv_cd (CARD-CVV-CD, CVACT02Y) is NEVER present in any response body
#   * password / password_hash (SEC-USR-PWD, CSUSR01Y) NEVER appears in any user/auth response
# Not tied to a single COBOL program -- enforces the security uplift across all endpoints.
"""Cross-cutting sensitive-data masking integration tests (AAP 0.7.8).

These tests are deliberately NOT scoped to a single ported COBOL program.
Instead they sweep the CardDemo REST surface and assert -- as a systematic,
security-wide guarantee -- that the four sensitive-data rules of AAP 0.7.8 hold
uniformly on every endpoint that could otherwise leak a secret:

    1. ``card_num`` (``CARD-NUM PIC X(16)``, ``app/cpy/CVACT02Y.cpy``) is always
       serialized MASKED -- the first twelve digits replaced by ``*`` and only
       the final four revealed (for example ``************5740``) -- on card and
       transaction responses (``CardSummary`` / ``CardRead`` / ``TransactionRead``
       / ``TransactionSummary``).
    2. ``ssn`` (``CUST-SSN PIC 9(09)``, ``app/cpy/CVCUS01Y.cpy``) is MASKED to its
       last four digits in the ``CustomerRead`` projection embedded inside the
       ``AccountDetail`` account-view response.
    3. ``cvv_cd`` (``CARD-CVV-CD PIC 9(03)``, ``app/cpy/CVACT02Y.cpy``) is NEVER
       present, under any spelling, anywhere in a response body.
    4. ``password`` / ``password_hash`` (the uplifted ``SEC-USR-PWD PIC X(08)``,
       ``app/cpy/CSUSR01Y.cpy``) NEVER appears in any admin-user or sign-on
       response body.

Fixtures come exclusively from the parent ``backend/tests/conftest.py`` (there is
no local ``conftest`` here): ``admin_client`` (authenticated administrator),
``regular_client`` (authenticated regular user), ``client`` (unauthenticated),
``admin_user`` (seeds the ``ADMIN001`` row so a sign-on can succeed) and
``seed_data`` (loads the golden-master cards/accounts/customers/xref rows from
``app/data/ASCII``). The suite runs under ``asyncio_mode = "auto"`` (plain
``async def test_*``); per httpx 0.28.1 the client fixtures are consumed as-is
and every request is awaited sequentially -- no ``AsyncClient(app=...)`` shortcut
is used.

Naming follows the Ochs resolution (AAP 0.8.3): this module file name is
snake_case, the reusable assertion helpers are PascalCase
(``AssertNoForbiddenKeys`` / ``AssertCardNumMasked``), local variables are
camelCase, and module-level constants are ALL_UPPERCASE. Every assertion pins a
PRECISE HTTP status (200) rather than a loose "not an error" check.
"""

from httpx import AsyncClient
from sqlalchemy import text

from app.core.config import settings

# ---------------------------------------------------------------------------
# Endpoint URL constants (ALL_UPPERCASE per the Ochs Rule 0.8.2). Each is built
# from ``settings.API_V1_PREFIX`` so the version segment is never hardcoded here;
# ``app.main`` owns where the routers are actually mounted.
# ---------------------------------------------------------------------------
CARDS_URL = f"{settings.API_V1_PREFIX}/cards"
ACCOUNTS_URL = f"{settings.API_V1_PREFIX}/accounts"
TRANSACTIONS_URL = f"{settings.API_V1_PREFIX}/transactions"
USERS_URL = f"{settings.API_V1_PREFIX}/admin/users"
LOGIN_URL = f"{settings.API_V1_PREFIX}/auth/login"

# ---------------------------------------------------------------------------
# Golden-master anchors (verified against app/data/ASCII/carddata.txt row 1 and
# the cardxref -> custdata chain for account 00000000001). These are SEED-ONLY
# fixture values, never real credentials or live card data.
# ---------------------------------------------------------------------------
SEED_CARD_NUM = "0500024453765740"        # carddata.txt row 1 (CARD-NUM).
SEED_CVV = "747"                          # carddata.txt row 1 (CARD-CVV-CD); must never leak.
MASKED_SEED_CARD = "************5740"      # "*" * 12 + last four of SEED_CARD_NUM.
SEED_ACCOUNT_ID = "00000000001"           # account whose owning customer's SSN is checked.
SEED_ACCOUNT_SSN = "020973888"            # CUST-SSN of customer 000000001 (xref of account 1).

# Sign-on seed credentials (SEED-ONLY, from the README sample users). Used only to
# drive the login secret-leak check; the plaintext is asserted absent from the
# response body, never stored or echoed by the application.
LOGIN_USER_ID = "ADMIN001"
LOGIN_PASSWORD = "PASSWORD"

# Recursively forbidden JSON keys: no response may ever carry the card
# verification value (any spelling) or a password / password hash.
FORBIDDEN_KEYS = ("cvv_cd", "cvv", "password", "password_hash")

# Card-number masking geometry (mirrors app.schemas.card): a 16-digit PAN is
# rendered as MASK_PREFIX_LENGTH mask characters followed by MASK_VISIBLE_DIGITS
# revealed trailing digits.
CARD_NUM_LENGTH = 16
MASK_VISIBLE_DIGITS = 4
MASK_PREFIX_LENGTH = CARD_NUM_LENGTH - MASK_VISIBLE_DIGITS
MASK_CHARACTER = "*"

# The single precise success status every endpoint under test must return.
HTTP_OK = 200


# ===========================================================================
# Reusable assertion helpers (PascalCase per Ochs; each < 20 lines, <= 4 params).
# ===========================================================================


def AssertNoForbiddenKeys(payload):
    """Assert no forbidden sensitive key appears anywhere in a JSON payload.

    Walks the decoded JSON structure recursively so a forbidden key is caught no
    matter how deeply it is nested (for example ``AccountDetail.customer`` or any
    element of ``PaginatedResponse.items``). Only dictionary KEYS are checked
    against :data:`FORBIDDEN_KEYS`; values are descended into.

    Args:
        payload: A decoded JSON value (dict, list, or scalar) to inspect.
    """
    if isinstance(payload, dict):
        for keyName, itemValue in payload.items():
            assert keyName not in FORBIDDEN_KEYS, (
                f"Forbidden sensitive key {keyName!r} present in response payload"
            )
            AssertNoForbiddenKeys(itemValue)
    elif isinstance(payload, list):
        for element in payload:
            AssertNoForbiddenKeys(element)


def AssertCardNumMasked(cardNumValue):
    """Assert a serialized ``card_num`` is masked to its last four digits.

    A masked PAN must be exactly :data:`CARD_NUM_LENGTH` characters, begin with
    :data:`MASK_PREFIX_LENGTH` ``*`` mask characters, and end with
    :data:`MASK_VISIBLE_DIGITS` decimal digits (for example ``************5740``).

    Args:
        cardNumValue: The serialized ``card_num`` value taken from a response.
    """
    assert isinstance(cardNumValue, str), (
        f"card_num must serialize as a string, got {type(cardNumValue)!r}"
    )
    assert len(cardNumValue) == CARD_NUM_LENGTH, (
        f"masked card_num must be {CARD_NUM_LENGTH} characters, got {cardNumValue!r}"
    )
    assert cardNumValue.startswith(MASK_CHARACTER * MASK_PREFIX_LENGTH), (
        f"card_num must start with {MASK_PREFIX_LENGTH} '*' characters, got {cardNumValue!r}"
    )
    assert cardNumValue[-MASK_VISIBLE_DIGITS:].isdigit(), (
        f"card_num must reveal {MASK_VISIBLE_DIGITS} trailing digits, got {cardNumValue!r}"
    )


# ===========================================================================
# Phase 2 -- card_num masking + CVV never returned.
# ===========================================================================


async def test_cards_list_masks_and_hides_cvv(
    admin_client: AsyncClient,
    seed_data,
) -> None:
    """The card browse masks every ``card_num`` and never exposes the CVV.

    Uses the administrator client so role scoping cannot hide rows: the whole
    (seeded) card file is browsable, so at least one row is returned and each is
    asserted masked with no CVV under any spelling.

    Args:
        admin_client: Authenticated administrator httpx ASGI client.
        seed_data: Golden-master seed fixture (cards/accounts/customers/xref).
    """
    response = await admin_client.get(CARDS_URL)

    assert response.status_code == HTTP_OK
    body = response.json()
    assert body["items"], "admin card browse must return at least one seeded card"
    for cardItem in body["items"]:
        AssertCardNumMasked(cardItem["card_num"])
        assert "cvv_cd" not in cardItem
        assert "cvv" not in cardItem
    AssertNoForbiddenKeys(body)


async def test_card_detail_masks_and_hides_cvv(
    admin_client: AsyncClient,
    seed_data,
) -> None:
    """The card detail view masks ``card_num`` and never leaks the CVV.

    Fetches the golden-master card by its full number and asserts the response
    serializes the masked form, that the real CVV text is absent, and that no
    forbidden key appears anywhere in the body.

    Args:
        admin_client: Authenticated administrator httpx ASGI client.
        seed_data: Golden-master seed fixture (cards/accounts/customers/xref).
    """
    response = await admin_client.get(f"{CARDS_URL}/{SEED_CARD_NUM}")

    assert response.status_code == HTTP_OK
    body = response.json()
    assert body["card_num"] == MASKED_SEED_CARD
    AssertCardNumMasked(body["card_num"])
    assert SEED_CVV not in response.text
    assert "cvv" not in response.text.lower()
    AssertNoForbiddenKeys(body)


# ===========================================================================
# Phase 3 -- SSN masking in the embedded customer of the account detail view.
# ===========================================================================


async def test_account_detail_masks_ssn(
    admin_client: AsyncClient,
    seed_data,
) -> None:
    """The account detail view masks the embedded customer's SSN.

    ``AccountDetail`` embeds a ``CustomerRead``; its ``ssn`` must be serialized
    masked (last four only). The check is robust to either the ``*****NNNN`` or
    ``***-**-NNNN`` presentation: it requires a mask character and asserts the
    raw nine-digit cleartext SSN never appears anywhere in the response body.

    Args:
        admin_client: Authenticated administrator httpx ASGI client.
        seed_data: Golden-master seed fixture (cards/accounts/customers/xref).
    """
    response = await admin_client.get(f"{ACCOUNTS_URL}/{SEED_ACCOUNT_ID}")

    assert response.status_code == HTTP_OK
    body = response.json()
    customerPayload = body["customer"]
    maskedSsn = customerPayload["ssn"]
    assert maskedSsn is not None, "customer ssn must be present (and masked)"
    assert maskedSsn != SEED_ACCOUNT_SSN, "ssn must not be serialized in cleartext"
    assert MASK_CHARACTER in maskedSsn, (
        f"ssn must be masked with a mask character, got {maskedSsn!r}"
    )
    assert SEED_ACCOUNT_SSN not in response.text, (
        "the raw nine-digit SSN must never appear anywhere in the response body"
    )
    AssertNoForbiddenKeys(body)


# ===========================================================================
# Phase 4 -- transaction responses mask card_num.
# ===========================================================================


async def test_transactions_mask_card_num(
    admin_client: AsyncClient,
    seed_data,
) -> None:
    """Transaction list rows mask ``card_num`` and leak no forbidden key.

    Every returned row that carries a ``card_num`` must serialize it masked, and
    the whole envelope must be free of forbidden keys. When a detail row is
    reachable, its ``TransactionRead.card_num`` is additionally asserted masked.

    Args:
        admin_client: Authenticated administrator httpx ASGI client.
        seed_data: Golden-master seed fixture (cards/accounts/customers/xref).
    """
    response = await admin_client.get(TRANSACTIONS_URL)

    assert response.status_code == HTTP_OK
    body = response.json()
    for transactionItem in body["items"]:
        if "card_num" in transactionItem:
            AssertCardNumMasked(transactionItem["card_num"])
    AssertNoForbiddenKeys(body)
    if body["items"]:
        firstTranId = body["items"][0]["tran_id"]
        detailResponse = await admin_client.get(f"{TRANSACTIONS_URL}/{firstTranId}")
        assert detailResponse.status_code == HTTP_OK
        detailBody = detailResponse.json()
        AssertCardNumMasked(detailBody["card_num"])
        AssertNoForbiddenKeys(detailBody)


# ===========================================================================
# Phase 5 -- user and sign-on responses never leak password / password_hash.
# ===========================================================================


async def test_users_never_leak_password(admin_client: AsyncClient) -> None:
    """The admin user browse never returns a password or password hash.

    ``UserSummary`` declares no credential field, so neither ``password`` nor
    ``password_hash`` may appear as a key or as substring text in the response.

    Args:
        admin_client: Authenticated administrator httpx ASGI client (the user
            routes are admin-gated, so a regular client would receive 403).
    """
    response = await admin_client.get(USERS_URL)

    assert response.status_code == HTTP_OK
    body = response.json()
    AssertNoForbiddenKeys(body)
    assert "password" not in response.text.lower()


async def test_login_never_leaks_password(
    client: AsyncClient,
    admin_user,
) -> None:
    """A successful sign-on returns identity only -- never the credential.

    Seeds the ``ADMIN001`` row (via ``admin_user``) so the sign-on succeeds, then
    posts the seed credentials on the unauthenticated ``client`` and asserts the
    200 identity body carries no forbidden key, no submitted password text, and
    no password-hash field.

    Args:
        client: Unauthenticated httpx ASGI client (login is the one open route).
        admin_user: Seeds the administrator ``ADMIN001`` row so login can verify.
    """
    response = await client.post(
        LOGIN_URL,
        json={"user_id": LOGIN_USER_ID, "password": LOGIN_PASSWORD},
    )

    assert response.status_code == HTTP_OK
    AssertNoForbiddenKeys(response.json())
    assert LOGIN_PASSWORD not in response.text
    assert "password_hash" not in response.text.lower()


# ===========================================================================
# Phase 6 -- masking also holds for a regular (non-admin) client.
# ===========================================================================


async def test_regular_client_card_masking(
    regular_client: AsyncClient,
    seed_data,
) -> None:
    """Masking holds for a regular user's card browse as well.

    Role scoping may reduce a regular user's visible rows (a non-admin without an
    account context sees an empty page), so this asserts the precise 200 status,
    masks ``card_num`` on whatever rows are returned, and confirms no CVV or other
    forbidden key leaks regardless of the row count.

    Args:
        regular_client: Authenticated regular-user httpx ASGI client.
        seed_data: Golden-master seed fixture (cards/accounts/customers/xref).
    """
    response = await regular_client.get(CARDS_URL)

    assert response.status_code == HTTP_OK
    body = response.json()
    for cardItem in body["items"]:
        AssertCardNumMasked(cardItem["card_num"])
        assert "cvv_cd" not in cardItem
        assert "cvv" not in cardItem
    AssertNoForbiddenKeys(body)


# ===========================================================================
# Storage-layer guarantee (QA finding C-03, AAP 0.7.8).
#
# The response-body tests above prove the CVV never LEAVES the service. This
# final test proves the stronger, root-cause guarantee: the CVV is never STORED
# in the first place. The test schema is built from ``Base.metadata`` (the ORM
# models) via the session-scoped ``_create_schema`` fixture, so inspecting the
# live ``cards`` table columns is a direct assertion about the ``Card`` model:
# if anyone re-declares a ``cvv_cd`` column on the model, this test fails.
# ===========================================================================

CARDS_TABLE_NAME = "cards"
CVV_COLUMN_NAME = "cvv_cd"
# A minimal set of columns that MUST remain on the cards table, so this test
# fails loudly if the table itself is missing (rather than passing vacuously
# because "cvv_cd" is absent from an empty/nonexistent column set).
REQUIRED_CARD_COLUMNS = ("card_num", "acct_id", "embossed_name", "active_status")

# information_schema query returning every column name of a given table in the
# connection's current database/schema. Parameterized to avoid any injection.
CARD_COLUMNS_QUERY = text(
    "SELECT column_name FROM information_schema.columns "
    "WHERE table_name = :tableName"
)


async def test_cards_table_persists_no_cvv_column(db_session) -> None:
    """The physical ``cards`` table has no ``cvv_cd`` column (never persisted).

    This is the storage-layer counterpart to the response-masking tests: it
    proves CVV protection at its root cause (the data is never written), not
    merely that it is filtered out of responses. Because the test database
    schema is generated from the ORM ``Base.metadata``, the presence/absence of
    the column is a faithful reflection of the ``Card`` model definition.

    Args:
        db_session: Isolated async session bound to the freshly created schema.
    """
    result = await db_session.execute(
        CARD_COLUMNS_QUERY, {"tableName": CARDS_TABLE_NAME}
    )
    columnNames = {row[0] for row in result.all()}

    # The table must actually exist (guards against a vacuous pass).
    for requiredColumn in REQUIRED_CARD_COLUMNS:
        assert requiredColumn in columnNames, (
            f"expected column {requiredColumn!r} missing from {CARDS_TABLE_NAME}; "
            f"present columns: {sorted(columnNames)}"
        )

    # The sensitive CVV column must NOT exist under any spelling.
    assert CVV_COLUMN_NAME not in columnNames, (
        f"sensitive column {CVV_COLUMN_NAME!r} must never be persisted on "
        f"{CARDS_TABLE_NAME} (C-03, AAP 0.7.8); present columns: "
        f"{sorted(columnNames)}"
    )
    assert "cvv" not in columnNames, (
        f"no CVV column may exist on {CARDS_TABLE_NAME} under any spelling; "
        f"present columns: {sorted(columnNames)}"
    )
