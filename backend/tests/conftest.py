"""Shared pytest fixtures for the CardDemo backend test suite.

Provides: an async SQLAlchemy engine/session bound to a disposable TEST
database, per-test isolation, an httpx ASGI client wired to ``app.main:app``
with dependency overrides for ``get_db`` / ``get_current_user`` /
``require_admin``, and golden-master loader helpers that decode the fixed-width,
zoned-decimal sample data in ``app/data/ASCII/*.txt`` (AAP 0.8.1 golden-master
parity).

Traceability:
    * Golden-master oracle = ``app/data/ASCII`` (the ASCII seed datasets are the
      display-readable copies of the legacy VSAM files; the business rules under
      test are ported from ``app/cbl/*`` -- see the ``unit/`` and
      ``integration/`` modules).
    * There is no legacy COBOL source for this infrastructure module itself; the
      fixed-width record layouts it decodes come from the copybooks
      ``app/cpy/CVACT01Y.cpy`` (account), ``app/cpy/CVACT02Y.cpy`` (card),
      ``app/cpy/CVACT03Y.cpy`` (card cross-reference), ``app/cpy/CVCUS01Y.cpy``
      (customer), ``app/cpy/CVTRA01Y.cpy`` (category balance),
      ``app/cpy/CVTRA02Y.cpy`` (disclosure group), ``app/cpy/CVTRA03Y.cpy``
      (transaction type), ``app/cpy/CVTRA04Y.cpy`` (transaction category) and
      ``app/cpy/CVTRA06Y.cpy`` (daily transaction).

Public fixture inventory (CROSS-AGENT CONTRACT relied upon by ``tests/unit`` and
``tests/integration``; the ``unit``/``integration`` folders MAY add their own
nested ``conftest.py`` for sub-scoped fixtures but MUST NOT redefine these):

    test_engine       (function) -- a disposable async SQLAlchemy engine bound to
                       the TEST database.
    db_session        (function) -- an isolated ``AsyncSession`` for calling
                       service/repository methods directly; every table is wiped
                       after each test so tests never leak state into one another.
    client            (function) -- an UNAUTHENTICATED httpx ASGI client with
                       ``get_db`` overridden to share ``db_session`` (use it to
                       assert 401 behavior on protected routes).
    admin_client      (function) -- ``client`` additionally authenticated as the
                       seed administrator ``ADMIN001`` (``user_type == 'A'``).
    regular_client    (function) -- ``client`` additionally authenticated as the
                       seed regular user ``USER0001`` (``user_type == 'U'``);
                       admin-gated routes correctly return 403 for it.
    admin_user        (function) -- the seeded administrator ``User`` ORM row.
    regular_user      (function) -- the seeded regular ``User`` ORM row.
    seed_data         (function) -- populate the golden-master master + reference
                       rows from ``app/data/ASCII`` (customers, accounts, cards,
                       card cross-references, disclosure groups, category
                       balances, transaction types and categories) in
                       foreign-key-safe order.
    seed_reference_data       (function) -- only the standalone reference tables
                       (transaction types/categories, disclosure groups, category
                       balances).
    seed_cards_customers_xref (function) -- customers + accounts + cards + card
                       cross-references (the referential-integrity chain).

    Loader helper FUNCTIONS (PascalCase; NOT fixtures -- call them directly):
    ``LoadAccountsRows``, ``LoadCardRows``, ``LoadCardXrefRows``,
    ``LoadCustomerRows``, ``LoadDisclosureGroupRows``, ``LoadTcatBalRows``,
    ``LoadTranTypeRows``, ``LoadTranCategoryRows`` and ``LoadDailyTranRows``.

Design notes (event-loop scope -- IMPORTANT):
    The schema is created exactly once per test session through a *synchronous*
    (psycopg2) engine, which runs outside any asyncio event loop and therefore
    cannot collide with a per-test loop. The per-test ``test_engine`` /
    ``db_session`` fixtures are *function-scoped* async fixtures (no
    ``loop_scope`` tuning): because pytest-asyncio runs each async test in its
    own function-scoped event loop under ``asyncio_mode = "auto"``, keeping the
    engine function-scoped guarantees the asyncpg connection pool is created in,
    and torn down from, the same loop that runs the test -- avoiding the
    "attached to a different loop" error that a session-scoped async engine would
    otherwise raise. Per-test isolation is achieved by truncating every table
    after each test (Postgres ``TRUNCATE ... RESTART IDENTITY CASCADE``), which
    is robust even when the code under test calls ``commit()``.

Ochs naming (AAP 0.8.2 / 0.8.3): the file name is snake_case (``conftest.py``)
and the fixture functions keep snake_case names (``db_session``, ``client``,
``admin_client`` ...) as the pytest framework contract -- an intentional,
documented exception mirroring the ``get_db`` / ``get_current_user`` provider
names. Non-fixture helper functions use PascalCase (``LoadCardXrefRows``), local
variables camelCase (``rawLine``, ``filePath``, ``sessionMaker``) and module
constants ALL_UPPERCASE (``ASCII_DATA_DIR``, ``TEST_DATABASE_URL``).
"""

import os
from collections.abc import AsyncGenerator, Iterator
from datetime import date
from pathlib import Path
from urllib.parse import urlsplit

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy import create_engine, text
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

# ---------------------------------------------------------------------------
# Environment bootstrap -- MANDATORY and MUST run before any ``app.*`` import.
#
# ``app.core.config.Settings`` declares ``SECRET_KEY`` with no default (Ochs
# Rule #3, no hardcoded secret), and ``app.db.session`` binds its engine to
# ``DATABASE_URL`` at import time. Seeding a throwaway ``SECRET_KEY`` here makes
# the ``app.*`` modules importable during test collection without depending on a
# real secret; ``setdefault`` is used so any value already exported by the
# environment (or a parent process) is never clobbered. The literal below is an
# obvious non-production placeholder that satisfies the >= 32-character minimum
# enforced by ``app.core.config`` -- it is not, and must never be treated as, a
# real credential.
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "testing-secret-key-not-for-production")
os.environ.setdefault("ENVIRONMENT", "test")

# ---------------------------------------------------------------------------
# TEST-DATABASE SAFETY (QA finding C-01 -- CRITICAL). The whole suite runs
# DESTRUCTIVE DDL/DML against its target database: it DROPs and CREATEs the
# schema (``_create_schema``), ``TRUNCATE ... CASCADE``s every table after each
# test, and (in the batch integration conftest) can toggle table triggers.
# Pointing that at a shared or production database would irrecoverably destroy
# data, so the target MUST be an unmistakably disposable test database and the
# resolution MUST fail closed.
#
# The mandatory rules enforced by ``_AssertDisposableTestDatabase`` below:
#   1. ``ENVIRONMENT`` must be exactly ``test`` (case-insensitive).
#   2. The test URL is resolved from ``TEST_DATABASE_URL`` only, with a local
#      ``carddemo_test`` default that matches the docker-compose ``postgres:17``
#      service. It is NEVER derived from the application's ``DATABASE_URL`` --
#      the destructive fallback that made C-01 dangerous is removed entirely.
#   3. The resolved database NAME must be unmistakably a test database (it must
#      contain the token ``test``); an arbitrary name is rejected.
#   4. If the application ``DATABASE_URL`` is also set, the test target must not
#      be the SAME (host, port, database) as the application database, so a
#      misconfigured environment can never truncate the app's own database.
# Any violation raises ``RuntimeError`` before a single connection is opened.
# Parallel CI clones override ``TEST_DATABASE_URL`` to point at an isolated
# ``*_test*`` database so concurrent sessions never share schema/state.
# ---------------------------------------------------------------------------

# Token that a resolved database name MUST contain to be accepted as disposable.
TEST_DATABASE_NAME_TOKEN = "test"

# Safe, unmistakably-disposable local default (matches docker-compose). This is a
# test database name, never the application's ``carddemo`` database, and it does
# NOT read the application ``DATABASE_URL``.
DEFAULT_TEST_DATABASE_URL = (
    "postgresql+asyncpg://carddemo:carddemo@localhost:5432/carddemo_test"
)


def _ExtractDatabaseIdentity(databaseUrl: str) -> tuple[str, str, str]:
    """Return the ``(host, port, dbname)`` identity of a SQLAlchemy URL.

    Only the non-secret connection-identity parts are extracted; the username
    and password are deliberately ignored so they never appear in any error
    message this module raises.

    Args:
        databaseUrl: The SQLAlchemy database URL to inspect.

    Returns:
        A ``(host, port, dbname)`` tuple; missing parts are empty strings.
    """
    parsed = urlsplit(databaseUrl)
    host = parsed.hostname or ""
    port = str(parsed.port) if parsed.port is not None else ""
    dbName = parsed.path.lstrip("/")
    return (host, port, dbName)


def _AssertDisposableTestDatabase(testDatabaseUrl: str) -> None:
    """Fail closed unless ``testDatabaseUrl`` is an unmistakably disposable test DB.

    Enforces the four C-01 safety rules documented above. The check runs at
    import time, before any engine is created, so an unsafe configuration aborts
    collection instead of destroying data. Error messages name only the
    non-secret database identity (never credentials).

    Args:
        testDatabaseUrl: The resolved TEST database URL to validate.

    Raises:
        RuntimeError: If ``ENVIRONMENT`` is not ``test``, the database name does
            not look like a disposable test database, or the target coincides
            with the application ``DATABASE_URL`` database.
    """
    environment = os.environ.get("ENVIRONMENT", "").strip().lower()
    if environment != "test":
        raise RuntimeError(
            "Refusing to run the destructive test suite: ENVIRONMENT must be "
            f"'test' (got {environment!r}). Set ENVIRONMENT=test explicitly."
        )
    host, port, dbName = _ExtractDatabaseIdentity(testDatabaseUrl)
    if TEST_DATABASE_NAME_TOKEN not in dbName.lower():
        raise RuntimeError(
            "Refusing to run the destructive test suite against database "
            f"{dbName!r}: the target database name must contain "
            f"{TEST_DATABASE_NAME_TOKEN!r} to prove it is disposable. Set "
            "TEST_DATABASE_URL to an isolated *_test* database."
        )
    applicationDatabaseUrl = os.environ.get("DATABASE_URL", "").strip()
    if applicationDatabaseUrl:
        if (host, port, dbName) == _ExtractDatabaseIdentity(applicationDatabaseUrl):
            raise RuntimeError(
                "Refusing to run the destructive test suite: TEST_DATABASE_URL "
                f"targets the same database ({dbName!r} on {host}:{port}) as the "
                "application DATABASE_URL. Point TEST_DATABASE_URL at a separate "
                "disposable test database."
            )


# Resolve the TEST database URL from TEST_DATABASE_URL only (never DATABASE_URL),
# then fail closed unless it is provably a disposable test database.
TEST_DATABASE_URL = os.environ.get("TEST_DATABASE_URL", DEFAULT_TEST_DATABASE_URL)
_AssertDisposableTestDatabase(TEST_DATABASE_URL)

# Synchronous (psycopg2) counterpart of the same TEST database, used only by the
# session-scoped schema fixture. Deriving it from TEST_DATABASE_URL keeps the two
# URLs pointing at the identical database while swapping the driver; a bare
# ``postgresql://`` URL (no ``+asyncpg``) already defaults to psycopg2, so the
# replacement is a safe no-op in that case.
TEST_SYNC_DATABASE_URL = TEST_DATABASE_URL.replace("+asyncpg", "+psycopg2")

# App-under-test imports. These intentionally follow the environment bootstrap
# above (hence the E402 suppressions). ``import app.models`` is performed for its
# load-bearing side effect -- it registers ALL ten ORM tables on
# ``Base.metadata`` -- so it must precede any ``Base.metadata.create_all`` call
# (hence the additional F401 suppression: the bare package import is deliberate,
# not dead code).
import app.models  # noqa: E402,F401  (side effect: registers all 11 tables)
from app.core.config import settings  # noqa: E402
from app.core.dependencies import (  # noqa: E402
    ADMIN_USER_TYPE,
    REGULAR_USER_TYPE,
    get_current_user,
    get_db,
)
from app.core.rate_limiter import (  # noqa: E402
    DefaultClock,
    loginRateLimiter,
)
from app.core.security import HashPassword  # noqa: E402
from app.db.base import Base  # noqa: E402
from app.main import app  # noqa: E402
from app.models import (  # noqa: E402
    STATUS_PENDING,
    Account,
    AccountGroup,
    Card,
    CardXref,
    Customer,
    DisclosureGroup,
    TranCategoryBalance,
    TransactionCategory,
    TransactionType,
    User,
)
from app.utils.decimal_utils import DecodeZonedDecimal  # noqa: E402

# ---------------------------------------------------------------------------
# Fixture-data location and golden-master constants.
# ---------------------------------------------------------------------------

# Directory holding the ASCII golden-master seed files. ``conftest.py`` lives at
# ``backend/tests/conftest.py``; ``parents[2]`` is the repository root, under
# which the legacy (REFERENCE-only) ``app/data/ASCII`` tree resides.
ASCII_DATA_DIR = Path(__file__).resolve().parents[2] / "app" / "data" / "ASCII"

# Fail fast with a specific, actionable error if the golden-master data cannot be
# found (for example, when tests are launched from an unexpected working
# directory). A missing oracle would otherwise surface as a confusing
# per-loader FileNotFoundError.
if not ASCII_DATA_DIR.is_dir():
    raise FileNotFoundError(
        f"Golden-master ASCII data directory not found at {ASCII_DATA_DIR!r}. "
        "Expected the legacy app/data/ASCII tree at the repository root."
    )

# Implied fractional-digit count for every CardDemo signed zoned-decimal money /
# rate field (the COBOL ``V99`` picture). Passed to ``DecodeZonedDecimal``.
MONEY_SCALE = 2

# Seed identities (SEED-ONLY golden-master values from the README -- never real
# credentials, never hardcoded production secrets). The plaintext password is
# always stored HASHED (via ``HashPassword``) and is never asserted in cleartext.
SEED_ADMIN_USER_ID = "ADMIN001"
SEED_REGULAR_USER_ID = "USER0001"
SEED_PASSWORD = "PASSWORD"

# ---------------------------------------------------------------------------
# Verified fixed-width slice maps (byte offsets confirmed by direct inspection of
# both the copybooks and the ASCII data; the widths are load-bearing). Signed
# numeric fields are zoned decimal -- the sign is an overpunch on the LAST byte
# ({=+0..I=+9, }=-0..R=-9) and the decimal point is implied -- so they are
# decoded with ``DecodeZonedDecimal(raw, MONEY_SCALE)`` rather than sliced as
# plain text. Rows are newline-terminated and are read with only the trailing EOL
# removed (never ``.strip()``-ed, which would eat a leading field's significant
# zeros/spaces).
#
#   acctdata.txt  (300 bytes/row, 50 rows) -- app/cpy/CVACT01Y.cpy:
#     acct_id[0:11] active_status[11:12] curr_bal[12:24] credit_limit[24:36]
#     cash_credit_limit[36:48] open_date[48:58] expiration_date[58:68]
#     reissue_date[68:78] curr_cyc_credit[78:90] curr_cyc_debit[90:102]
#     addr_zip[102:112] group_id[112:122]  (FILLER[122:300] dropped)
#   carddata.txt  (150 bytes/row, 50 rows) -- app/cpy/CVACT02Y.cpy:
#     card_num[0:16] acct_id[16:27] (cvv[27:30] NOT loaded, C-03) embossed_name[30:80]
#     expiration_date[80:90] active_status[90:91]  (FILLER[91:150] dropped)
#   cardxref.txt  (36 bytes/row, 50 rows) -- app/cpy/CVACT03Y.cpy (the ASCII file
#     OMITS the trailing FILLER X(14), so rows are 36 not 50 bytes):
#     card_num[0:16] cust_id[16:25] acct_id[25:36]
#   custdata.txt  (500 bytes/row, 50 rows) -- app/cpy/CVCUS01Y.cpy:
#     cust_id[0:9] first_name[9:34] middle_name[34:59] last_name[59:84]
#     addr_line_1[84:134] addr_line_2[134:184] addr_line_3[184:234]
#     addr_state_cd[234:236] addr_country_cd[236:239] addr_zip[239:249]
#     phone_num_1[249:264] phone_num_2[264:279] ssn[279:288]
#     govt_issued_id[288:308] date_of_birth[308:318] eft_account_id[318:328]
#     pri_card_holder_ind[328:329] fico_credit_score[329:332]
#   discgrp.txt   (50 bytes/row, 51 rows) -- app/cpy/CVTRA02Y.cpy:
#     group_id[0:10] tran_type_cd[10:12] tran_cat_cd[12:16] interest_rate[16:22]
#   tcatbal.txt   (50 bytes/row, 50 rows) -- app/cpy/CVTRA01Y.cpy:
#     acct_id[0:11] tran_type_cd[11:13] tran_cat_cd[13:17] balance[17:28]
#   trancatg.txt  (60 bytes/row, 18 rows) -- app/cpy/CVTRA04Y.cpy:
#     tran_type_cd[0:2] tran_cat_cd[2:6] tran_cat_type_desc[6:56]
#   trantype.txt  (60 bytes/row, 7 rows) -- app/cpy/CVTRA03Y.cpy:
#     tran_type[0:2] tran_type_desc[2:52]
#   dailytran.txt (350 bytes/row, 300 rows) -- app/cpy/CVTRA06Y.cpy:
#     tran_id[0:16] tran_type_cd[16:18] tran_cat_cd[18:22] tran_source[22:32]
#     tran_desc[32:132] tran_amt[132:143] merchant_id[143:152]
#     merchant_name[152:202] merchant_city[202:252] merchant_zip[252:262]
#     card_num[262:278] orig_ts[278:304] proc_ts[304:330]
# ---------------------------------------------------------------------------


# ===========================================================================
# Golden-master loader helpers (PascalCase per Ochs; these are NOT fixtures --
# call them directly). Each loader reads one ASCII golden-master file, slices it
# per the verified fixed-width maps above, decodes signed zoned-decimal money /
# rate fields through the trusted, independently unit-tested
# ``app.utils.decimal_utils.DecodeZonedDecimal`` primitive, and returns a list of
# plain dicts whose keys are the exact ORM column names -- so a caller may seed
# via ``Model(**row)``. Monetary values are always ``Decimal`` (never ``float``);
# fixed-width text fields are right-trimmed of their padding, and blank optional
# fields become ``None`` (SQL NULL).
# ===========================================================================


def ReadFixtureLines(fileName: str) -> list[str]:
    """Read a golden-master ASCII file into a list of raw fixed-width rows.

    Only the trailing end-of-line is removed from each row -- never a full
    ``strip()``, which would eat the significant leading zeros or spaces of the
    first field. Fully blank lines (a possible trailing newline) are skipped.

    Args:
        fileName: Base name of the file inside ``ASCII_DATA_DIR`` (for example
            ``"cardxref.txt"``).

    Returns:
        The file's rows, each with only its end-of-line removed.

    Raises:
        FileNotFoundError: If the named file does not exist under
            ``ASCII_DATA_DIR`` (surfaced with the specific missing path).
    """
    filePath = ASCII_DATA_DIR / fileName
    fixtureLines = []
    with open(filePath, "r", encoding="ascii") as handle:
        for rawLine in handle:
            trimmedLine = rawLine.rstrip("\r\n")
            if trimmedLine:
                fixtureLines.append(trimmedLine)
    return fixtureLines


def CleanOptionalText(rawField: str) -> str | None:
    """Right-trim a fixed-width text field; return ``None`` when it is all blanks.

    The fixed-width source pads short values with trailing spaces. The padding is
    removed so the stored value matches the logical content, and an entirely
    blank field maps to SQL NULL (``None``) for nullable columns.

    Args:
        rawField: The raw fixed-width slice.

    Returns:
        The right-trimmed value, or ``None`` when the slice is blank.
    """
    trimmedValue = rawField.rstrip()
    if not trimmedValue:
        return None
    return trimmedValue


def ParseOptionalDate(rawField: str) -> date | None:
    """Parse a ``PIC X(10)`` ``YYYY-MM-DD`` field into a ``date`` (or ``None``).

    A blank field (spaces or low-values in the legacy record) yields ``None`` so
    the nullable ``DATE`` column is left unset. A non-blank but malformed value
    raises ``ValueError``, surfacing corrupt golden-master data rather than
    silently hiding it.

    Args:
        rawField: The raw 10-character date slice.

    Returns:
        The parsed :class:`datetime.date`, or ``None`` when the slice is blank.

    Raises:
        ValueError: If a non-blank value is not a valid ISO ``YYYY-MM-DD`` date.
    """
    trimmedValue = rawField.strip()
    if not trimmedValue:
        return None
    return date.fromisoformat(trimmedValue)


def ParseOptionalInt(rawField: str) -> int | None:
    """Parse a fixed-width numeric field into an ``int`` (``None`` when blank).

    Used for genuine integer quantities (for example the FICO score), never for
    identifier fields whose leading zeros must be preserved as text.

    Args:
        rawField: The raw fixed-width numeric slice.

    Returns:
        The integer value, or ``None`` when the slice is blank.

    Raises:
        ValueError: If a non-blank value is not a valid integer.
    """
    trimmedValue = rawField.strip()
    if not trimmedValue:
        return None
    return int(trimmedValue)


def LoadAccountsRows() -> list[dict]:
    """Parse ``app/data/ASCII/acctdata.txt`` (300-byte rows) into account dicts.

    The five monetary fields (``PIC S9(10)V99``) are decoded as signed zoned
    decimal via ``DecodeZonedDecimal(raw, MONEY_SCALE)`` -> ``Decimal``; the three
    date fields are parsed to nullable ``date``. Dict keys match the
    :class:`app.models.account.Account` columns.
    """
    accountRows = []
    for line in ReadFixtureLines("acctdata.txt"):
        accountRows.append(
            {
                "acct_id": line[0:11],
                "active_status": line[11:12],
                "curr_bal": DecodeZonedDecimal(line[12:24], MONEY_SCALE),
                "credit_limit": DecodeZonedDecimal(line[24:36], MONEY_SCALE),
                "cash_credit_limit": DecodeZonedDecimal(line[36:48], MONEY_SCALE),
                "open_date": ParseOptionalDate(line[48:58]),
                "expiration_date": ParseOptionalDate(line[58:68]),
                "reissue_date": ParseOptionalDate(line[68:78]),
                "curr_cyc_credit": DecodeZonedDecimal(line[78:90], MONEY_SCALE),
                "curr_cyc_debit": DecodeZonedDecimal(line[90:102], MONEY_SCALE),
                "addr_zip": CleanOptionalText(line[102:112]),
                "group_id": CleanOptionalText(line[112:122]),
            }
        )
    return accountRows


def LoadCardRows() -> list[dict]:
    """Parse ``app/data/ASCII/carddata.txt`` (150-byte rows) into card dicts.

    Dict keys match the :class:`app.models.card.Card` columns. ``expiration_date``
    is nullable; the embossed name is right-trimmed of its fixed-width padding.
    The source CVV slice ``line[27:30]`` is DELIBERATELY SKIPPED (QA finding
    C-03, AAP 0.7.8): the ``cards`` table has no CVV column, so no CVV is
    produced by this loader.
    """
    cardRows = []
    for line in ReadFixtureLines("carddata.txt"):
        cardRows.append(
            {
                "card_num": line[0:16],
                "acct_id": line[16:27],
                # line[27:30] is the source CVV slice -- intentionally not loaded.
                "embossed_name": CleanOptionalText(line[30:80]),
                "expiration_date": ParseOptionalDate(line[80:90]),
                "active_status": line[90:91],
            }
        )
    return cardRows


def LoadCardXrefRows() -> list[dict]:
    """Parse ``app/data/ASCII/cardxref.txt`` (36-byte rows) into xref dicts.

    CRITICAL: the ASCII file OMITS the trailing ``FILLER X(14)`` present in
    ``CVACT03Y``, so each row is 36 bytes and is sliced exactly ``[0:16]`` /
    ``[16:25]`` / ``[25:36]``. The dict key is ``card_num`` (the schema/DTO field
    name); the seeders map it onto the physical ``xref_card_num`` column to avoid
    synonym ambiguity.
    """
    xrefRows = []
    for line in ReadFixtureLines("cardxref.txt"):
        xrefRows.append(
            {
                "card_num": line[0:16],
                "cust_id": line[16:25],
                "acct_id": line[25:36],
            }
        )
    return xrefRows


def LoadCustomerRows() -> list[dict]:
    """Parse ``app/data/ASCII/custdata.txt`` (500-byte rows) into customer dicts.

    Dict keys match the :class:`app.models.customer.Customer` columns. Optional
    text fields become ``None`` when blank; ``fico_credit_score`` (``PIC 9(03)``)
    is a true integer (``SMALLINT``); ``date_of_birth`` is a nullable ``DATE``.
    """
    customerRows = []
    for line in ReadFixtureLines("custdata.txt"):
        customerRows.append(
            {
                "cust_id": line[0:9],
                "first_name": CleanOptionalText(line[9:34]),
                "middle_name": CleanOptionalText(line[34:59]),
                "last_name": CleanOptionalText(line[59:84]),
                "addr_line_1": CleanOptionalText(line[84:134]),
                "addr_line_2": CleanOptionalText(line[134:184]),
                "addr_line_3": CleanOptionalText(line[184:234]),
                "addr_state_cd": CleanOptionalText(line[234:236]),
                "addr_country_cd": CleanOptionalText(line[236:239]),
                "addr_zip": CleanOptionalText(line[239:249]),
                "phone_num_1": CleanOptionalText(line[249:264]),
                "phone_num_2": CleanOptionalText(line[264:279]),
                "ssn": CleanOptionalText(line[279:288]),
                "govt_issued_id": CleanOptionalText(line[288:308]),
                "date_of_birth": ParseOptionalDate(line[308:318]),
                "eft_account_id": CleanOptionalText(line[318:328]),
                "pri_card_holder_ind": CleanOptionalText(line[328:329]),
                "fico_credit_score": ParseOptionalInt(line[329:332]),
            }
        )
    return customerRows


def LoadAccountGroupRows() -> list[dict]:
    """Distinct account-group identifiers for the ``account_groups`` registry.

    Derived from the disclosure-group seed so the registry is exactly the set of
    groups the golden-master dataset defines (``A000000000``, ``DEFAULT``,
    ``ZEROAPR``). The value is stripped to match how ``accounts.group_id`` stores
    a group reference, so the M-16 foreign key resolves for any account assigned
    a disclosure-known group. Dict keys match
    :class:`app.models.account_group.AccountGroup`.
    """
    seenGroupIds = []
    for disclosureRow in LoadDisclosureGroupRows():
        groupId = disclosureRow["group_id"].strip()
        if groupId and groupId not in seenGroupIds:
            seenGroupIds.append(groupId)
    return [{"group_id": groupId} for groupId in seenGroupIds]


def LoadDisclosureGroupRows() -> list[dict]:
    """Parse ``app/data/ASCII/discgrp.txt`` (50-byte rows) into disclosure dicts.

    ``interest_rate`` (``PIC S9(04)V99``) is decoded as signed zoned decimal ->
    ``Decimal`` (``NUMERIC(6, 2)``). Dict keys match the
    :class:`app.models.disclosure_group.DisclosureGroup` columns.
    """
    disclosureRows = []
    for line in ReadFixtureLines("discgrp.txt"):
        disclosureRows.append(
            {
                "group_id": line[0:10],
                "tran_type_cd": line[10:12],
                "tran_cat_cd": line[12:16],
                "interest_rate": DecodeZonedDecimal(line[16:22], MONEY_SCALE),
            }
        )
    return disclosureRows


def LoadTcatBalRows() -> list[dict]:
    """Parse ``app/data/ASCII/tcatbal.txt`` (50-byte rows) into balance dicts.

    ``balance`` (``PIC S9(09)V99``) is decoded as signed zoned decimal ->
    ``Decimal`` (``NUMERIC(11, 2)``). Dict keys match the
    :class:`app.models.tran_category_balance.TranCategoryBalance` columns.
    """
    tcatBalRows = []
    for line in ReadFixtureLines("tcatbal.txt"):
        tcatBalRows.append(
            {
                "acct_id": line[0:11],
                "tran_type_cd": line[11:13],
                "tran_cat_cd": line[13:17],
                "balance": DecodeZonedDecimal(line[17:28], MONEY_SCALE),
            }
        )
    return tcatBalRows


def LoadTranTypeRows() -> list[dict]:
    """Parse ``app/data/ASCII/trantype.txt`` (60-byte rows) into tran-type dicts.

    Dict keys match the :class:`app.models.transaction_type.TransactionType`
    columns (``tran_type``, ``tran_type_desc``).
    """
    tranTypeRows = []
    for line in ReadFixtureLines("trantype.txt"):
        tranTypeRows.append(
            {
                "tran_type": line[0:2],
                "tran_type_desc": CleanOptionalText(line[2:52]),
            }
        )
    return tranTypeRows


def LoadTranCategoryRows() -> list[dict]:
    """Parse ``app/data/ASCII/trancatg.txt`` (60-byte rows) into category dicts.

    Dict keys match the
    :class:`app.models.transaction_category.TransactionCategory` columns
    (``tran_type_cd``, ``tran_cat_cd``, ``tran_cat_type_desc``).
    """
    tranCategoryRows = []
    for line in ReadFixtureLines("trancatg.txt"):
        tranCategoryRows.append(
            {
                "tran_type_cd": line[0:2],
                "tran_cat_cd": line[2:6],
                "tran_cat_type_desc": CleanOptionalText(line[6:56]),
            }
        )
    return tranCategoryRows


def LoadDailyTranRows() -> list[dict]:
    """Parse ``app/data/ASCII/dailytran.txt`` (350-byte rows) into daily-tran dicts.

    These rows are the posting INPUT (the ``CVTRA06Y`` DALYTRAN layout).
    ``tran_amt`` (``PIC S9(09)V99``) is decoded to ``Decimal``; ``status``
    defaults to ``STATUS_PENDING``; the two 26-character timestamp fields are kept
    as raw text (the posting job parses them). These rows are intentionally NOT
    auto-seeded -- a daily transaction may reference a card absent from the
    ``cards`` fixture -- so golden-master posting tests consume them directly.
    """
    dailyTranRows = []
    for line in ReadFixtureLines("dailytran.txt"):
        dailyTranRows.append(
            {
                "tran_id": line[0:16],
                "tran_type_cd": line[16:18],
                "tran_cat_cd": line[18:22],
                "tran_source": CleanOptionalText(line[22:32]),
                "tran_desc": CleanOptionalText(line[32:132]),
                "tran_amt": DecodeZonedDecimal(line[132:143], MONEY_SCALE),
                "merchant_id": CleanOptionalText(line[143:152]),
                "merchant_name": CleanOptionalText(line[152:202]),
                "merchant_city": CleanOptionalText(line[202:252]),
                "merchant_zip": CleanOptionalText(line[252:262]),
                "card_num": line[262:278],
                "orig_ts": CleanOptionalText(line[278:304]),
                "proc_ts": CleanOptionalText(line[304:330]),
                "status": STATUS_PENDING,
            }
        )
    return dailyTranRows



# ===========================================================================
# Schema lifecycle + engine fixtures.
#
# The schema is created exactly ONCE per test session through a SYNCHRONOUS
# engine (psycopg2), which runs outside any asyncio event loop and therefore
# cannot collide with a per-test loop. The per-test ``test_engine`` is a
# FUNCTION-SCOPED async engine (deliberately no ``loop_scope`` tuning): under
# ``asyncio_mode = "auto"`` pytest-asyncio runs every async test in its own
# function-scoped event loop, so keeping the async engine function-scoped
# guarantees its asyncpg pool is created in -- and disposed from -- the SAME loop
# that runs the test. A session-scoped async engine would instead raise
# "got Future attached to a different loop" at teardown (empirically confirmed
# against this asyncpg/pytest-asyncio combination), which is why the AAP-permitted
# function-scoped-engine alternative is used here.
# ===========================================================================


@pytest.fixture(scope="session")
def _create_schema() -> Iterator[None]:
    """Create the full CardDemo schema once per session, then drop it.

    Runs synchronously (psycopg2) once per session. It is NO LONGER ``autouse``
    (QA finding M-21): it is now pulled in ONLY by :func:`test_engine`, so a pure
    unit test that never requests a database-backed fixture never triggers schema
    DDL and therefore does not require a running PostgreSQL. Any DB-backed test
    reaches this fixture transitively (``client``/``admin_client``/seed fixtures
    -> ``db_session`` -> ``test_engine`` -> ``_create_schema``), so the eleven
    tables are still created exactly once before the first DB use.

    Because ``import app.models`` at module import time registered all mapped
    classes on ``Base.metadata``, a single ``create_all`` builds every table with
    the deterministic constraint names from the shared naming convention. The
    teardown ``drop_all`` leaves the test database pristine for the next session.

    Yields:
        None -- this fixture is a pure session-level setup/teardown barrier.
    """
    syncEngine = create_engine(TEST_SYNC_DATABASE_URL, future=True)
    try:
        Base.metadata.drop_all(syncEngine)
        Base.metadata.create_all(syncEngine)
        yield
        Base.metadata.drop_all(syncEngine)
    finally:
        syncEngine.dispose()


@pytest_asyncio.fixture
async def test_engine(_create_schema) -> AsyncGenerator[AsyncEngine, None]:
    """Yield a disposable async SQLAlchemy engine bound to the TEST database.

    Function-scoped by design (see the section header): each test receives a
    fresh asyncpg engine that is created and disposed within that test's own
    event loop, avoiding cross-loop errors. It depends on ``_create_schema`` so
    the eleven tables exist before any connection is opened; because that
    dependency is declared here (rather than being ``autouse``), requesting a
    database-backed fixture is what triggers schema creation -- pure unit tests
    that never reach this engine never touch PostgreSQL (QA finding M-21). This
    engine only opens connections; it never issues DDL itself.

    Args:
        _create_schema: The session-scoped schema setup/teardown barrier, pulled
            in here so DB-backed tests always observe the created schema.

    Yields:
        The async engine bound to ``TEST_DATABASE_URL``.
    """
    engine = create_async_engine(TEST_DATABASE_URL, echo=False, future=True)
    try:
        yield engine
    finally:
        await engine.dispose()



# ===========================================================================
# Per-test isolation: login rate limiter reset (autouse).
#
# The login throttle (app/core/rate_limiter.py, QA finding M-01) is a process-
# wide singleton that counts consecutive failed sign-ons per (user id, client
# IP). Because the httpx ASGITransport gives every test the same sentinel client
# host, failures from one test would otherwise accumulate against the next and
# could trip a spurious 429. This autouse fixture resets the limiter -- and
# restores its default (monotonic) clock in case a test injected a fake one --
# BEFORE every test so throttle state never leaks across tests.
# ===========================================================================


@pytest.fixture(autouse=True)
def reset_login_rate_limiter():
    """Reset the process-wide login throttle before each test (M-01 isolation).

    Yields control to the test after clearing all accumulated attempt state and
    restoring the default clock, guaranteeing each test observes a pristine
    limiter regardless of failed-login activity in earlier tests.

    Yields:
        None. The fixture performs setup only; no value is needed by tests.
    """
    loginRateLimiter.Reset()
    loginRateLimiter.SetClock(DefaultClock)
    yield


# ===========================================================================
# Per-test isolation: db_session (function-scoped) + client (function-scoped).
#
# ``db_session`` yields an ``AsyncSession`` for tests that call service /
# repository methods directly. Isolation is achieved by TRUNCATING every table
# AFTER each test (Postgres ``TRUNCATE ... RESTART IDENTITY CASCADE``), which is
# robust even when the code under test calls ``commit()`` -- the committed rows
# are wiped during teardown so the next test starts from a clean, known state.
# ``expire_on_commit=False`` / ``autoflush=False`` mirror the app's
# ``AsyncSessionLocal`` contract so behavior under test matches production.
# ===========================================================================


@pytest_asyncio.fixture
async def db_session(test_engine) -> AsyncGenerator[AsyncSession, None]:
    """Yield an isolated ``AsyncSession`` bound to the test engine.

    The session mirrors the app's ``AsyncSessionLocal`` configuration
    (``expire_on_commit=False``, ``autoflush=False``). After the test finishes,
    every table is truncated so no state leaks into the next test -- this is the
    single source of per-test determinism and is resilient to service code that
    commits mid-test.

    Args:
        test_engine: The function-scoped async engine fixture.

    Yields:
        The isolated async session for the duration of one test.
    """
    sessionMaker = async_sessionmaker(
        bind=test_engine,
        class_=AsyncSession,
        expire_on_commit=False,
        autoflush=False,
    )
    async with sessionMaker() as session:
        yield session
    # Cleanup AFTER the test: wipe every table (children first via CASCADE) so the
    # next test starts from an empty database. RESTART IDENTITY resets any
    # sequences. This runs in the same event loop as the test (function-scoped
    # engine), so no cross-loop teardown error can occur.
    async with test_engine.begin() as conn:
        tableNames = ", ".join(
            f'"{table.name}"' for table in reversed(Base.metadata.sorted_tables)
        )
        await conn.execute(
            text(f"TRUNCATE {tableNames} RESTART IDENTITY CASCADE")
        )


@pytest_asyncio.fixture
async def client(db_session) -> AsyncGenerator[AsyncClient, None]:
    """Yield an UNAUTHENTICATED httpx ASGI client wired to ``app.main:app``.

    ``get_db`` is overridden so the in-process ASGI request handlers share the
    very same ``db_session`` the test uses -- the standard FastAPI override
    pattern -- which means rows a test inserts through the session are visible to
    the request handlers and vice versa. The client carries NO identity override,
    so protected routes correctly return 401, letting tests assert
    authentication behavior. Overrides are cleared on teardown so fixtures never
    leak into other tests.

    httpx 0.28.1 removed the ``AsyncClient(app=...)`` shortcut, so the explicit
    ``ASGITransport(app=app)`` transport is mandatory here.

    Args:
        db_session: The isolated async session shared with the request handlers.

    Yields:
        The configured async HTTP client. Tests MUST ``await`` its calls
        sequentially -- the shared session is not safe for concurrent requests.
    """
    async def _override_get_db() -> AsyncGenerator[AsyncSession, None]:
        yield db_session

    app.dependency_overrides[get_db] = _override_get_db
    transport = ASGITransport(app=app)
    try:
        async with AsyncClient(
            transport=transport,
            base_url="http://test",
        ) as testClient:
            yield testClient
    finally:
        app.dependency_overrides.pop(get_db, None)



# ===========================================================================
# Identity fixtures: seeded users + authenticated clients.
#
# The seed identities (ADMIN001 / USER0001, password "PASSWORD") are SEED-ONLY
# golden-master values from the README -- never real credentials. The password is
# always stored HASHED via ``HashPassword`` and is never asserted in cleartext.
# Because ``require_admin`` depends on ``get_current_user``, overriding
# ``get_current_user`` alone drives the admin/regular gate: an admin identity
# passes admin routes, a regular identity makes them return HTTP 403.
# ===========================================================================


@pytest_asyncio.fixture
async def admin_user(db_session) -> User:
    """Seed and return the administrator user (``ADMIN001``, ``user_type='A'``).

    The password is stored HASHED via ``HashPassword`` (never plaintext). The row
    is flushed (not committed) into the shared ``db_session`` so request handlers
    that reuse that session -- and services that reload the user -- can see it.

    Args:
        db_session: The isolated async session the row is added to.

    Returns:
        The persisted administrator :class:`app.models.user.User` instance.
    """
    user = User(
        user_id=SEED_ADMIN_USER_ID,
        first_name="Admin",
        last_name="User",
        password_hash=HashPassword(SEED_PASSWORD),
        user_type=ADMIN_USER_TYPE,
    )
    db_session.add(user)
    await db_session.flush()
    return user


@pytest_asyncio.fixture
async def regular_user(db_session) -> User:
    """Seed and return the regular user (``USER0001``, ``user_type='U'``).

    Mirror of :func:`admin_user` but with the regular-user role, so tests can
    prove that admin-gated routes reject a non-admin identity. The password is
    stored HASHED via ``HashPassword`` (never plaintext).

    Args:
        db_session: The isolated async session the row is added to.

    Returns:
        The persisted regular :class:`app.models.user.User` instance.
    """
    user = User(
        user_id=SEED_REGULAR_USER_ID,
        first_name="Regular",
        last_name="User",
        password_hash=HashPassword(SEED_PASSWORD),
        user_type=REGULAR_USER_TYPE,
    )
    db_session.add(user)
    await db_session.flush()
    return user


@pytest_asyncio.fixture
async def admin_client(client, admin_user) -> AsyncGenerator[AsyncClient, None]:
    """Yield ``client`` authenticated as the seeded administrator (``ADMIN001``).

    Overrides ``get_current_user`` to return ``admin_user``; since
    ``require_admin`` depends on ``get_current_user``, this single override lets
    admin-only routes succeed. The override is removed on teardown so it never
    leaks into another test.

    Args:
        client: The base (unauthenticated) ASGI client fixture.
        admin_user: The seeded administrator identity to inject.

    Yields:
        The ASGI client that authenticates as the administrator.
    """
    async def _override_current_user() -> User:
        return admin_user

    app.dependency_overrides[get_current_user] = _override_current_user
    try:
        yield client
    finally:
        app.dependency_overrides.pop(get_current_user, None)


@pytest_asyncio.fixture
async def regular_client(
    client,
    regular_user,
) -> AsyncGenerator[AsyncClient, None]:
    """Yield ``client`` authenticated as the seeded regular user (``USER0001``).

    Overrides ``get_current_user`` to return ``regular_user``. Because
    ``require_admin`` re-checks ``user_type``, admin-gated routes correctly return
    HTTP 403 for this client -- which is exactly how tests verify the admin gate.
    The override is removed on teardown.

    Args:
        client: The base (unauthenticated) ASGI client fixture.
        regular_user: The seeded regular identity to inject.

    Yields:
        The ASGI client that authenticates as a regular (non-admin) user.
    """
    async def _override_current_user() -> User:
        return regular_user

    app.dependency_overrides[get_current_user] = _override_current_user
    try:
        yield client
    finally:
        app.dependency_overrides.pop(get_current_user, None)



# ===========================================================================
# Real-login (end-to-end authentication) helpers + fixtures (QA finding M-20).
#
# The admin_client / regular_client fixtures above inject identity through a
# get_current_user dependency OVERRIDE, which is convenient for narrow
# authorization checks but BYPASSES the production auth chain -- cookie/bearer
# parsing (ResolveRequestToken), token decode + claims extraction
# (DecodeAccessToken), and the M-02 session-version (sver) revocation check.
# The helpers below perform a REAL ``POST /auth/login`` and chain the ISSUED
# credential into subsequent protected resource requests, so tests can prove the
# genuine token/claims path works end to end (M-20). Dependency overrides remain
# reserved for narrow unit-style checks.
#
# Cookie transport note: under the session baseline (AAP 0.8.4) login sets an
# HTTP-only cookie that is ``Secure`` whenever ENVIRONMENT != "development"
# (tests run as "test"), and httpx will NOT resend a Secure cookie from its jar
# over the in-process ``http://test`` transport. The issued token is therefore
# read from the login RESPONSE (``response.cookies`` exposes Set-Cookie even for
# a Secure cookie) and replayed as an explicit ``Cookie`` request header, which
# Starlette parses into ``request.cookies`` exactly as a browser on HTTPS would
# present it -- so the real get_current_user / CSRF middleware see it unchanged.
# ===========================================================================

# Login endpoint path, assembled from settings so the /api/v1 prefix is never
# hardcoded. Reuses the seed identities/password already defined above.
REAL_LOGIN_PATH = f"{settings.API_V1_PREFIX}/auth/login"


async def _PerformRealLogin(
    httpClient: AsyncClient, userId: str, password: str
) -> str:
    """Sign on through the REAL login endpoint and return the issued token.

    Posts the credentials to ``POST /auth/login``, asserts an HTTP 200, and
    extracts the signed session token from the response ``Set-Cookie`` (exposed
    on ``response.cookies`` even when the cookie is ``Secure``). This is the
    genuine credential the server issued -- no token is minted in the test.

    Args:
        httpClient: The ASGI client whose ``get_db`` override shares the session
            the seeded user was flushed into (so the login handler can find it).
        userId: The user id to authenticate.
        password: The plaintext password to authenticate with (seed-only).

    Returns:
        The signed session-token string carried by the session cookie.
    """
    response = await httpClient.post(
        REAL_LOGIN_PATH, json={"user_id": userId, "password": password}
    )
    assert response.status_code == 200, (
        f"real login for {userId!r} failed: {response.status_code} {response.text}"
    )
    issuedToken = response.cookies.get(settings.SESSION_COOKIE_NAME)
    assert issuedToken is not None, "login did not set the session cookie"
    return issuedToken


def _CookieAuthHeaders(token: str, origin: str | None = None) -> dict[str, str]:
    """Build request headers that replay an ISSUED session token as a cookie.

    The token is presented in a raw ``Cookie`` header (not httpx's per-request
    ``cookies=`` argument) so it round-trips over the in-process ``http://test``
    transport despite being a ``Secure`` cookie. An optional ``origin`` adds the
    ``Origin`` header required by the CSRF middleware on cookie-authenticated
    state-changing (unsafe) requests.

    Args:
        token: The issued session token to present.
        origin: Optional allow-listed origin for CSRF-guarded mutations.

    Returns:
        The headers dict to pass as ``headers=`` on a protected request.
    """
    headers = {"Cookie": f"{settings.SESSION_COOKIE_NAME}={token}"}
    if origin is not None:
        headers["Origin"] = origin
    return headers


@pytest_asyncio.fixture
async def real_login(client):
    """Return an async callable that performs a REAL login and returns headers.

    The callable ``login(userId, password, origin=None)`` signs on through the
    live ``POST /auth/login`` endpoint on the shared ``client`` and returns
    cookie-auth headers carrying the ISSUED token, ready to pass as ``headers=``
    on a protected resource request. Because it exercises the true credential
    path, requests made with these headers run the real get_current_user chain
    (token parse + decode + claims + M-02 sver check) rather than an override
    (M-20).

    Args:
        client: The base (unauthenticated) ASGI client whose get_db override is
            wired to the per-test session.

    Returns:
        An async callable ``(userId, password, origin=None) -> dict[str, str]``.
    """
    async def _login(
        userId: str, password: str, origin: str | None = None
    ) -> dict[str, str]:
        token = await _PerformRealLogin(client, userId, password)
        return _CookieAuthHeaders(token, origin)

    return _login


@pytest_asyncio.fixture
async def admin_auth_headers(client, admin_user) -> dict[str, str]:
    """Cookie-auth headers carrying a REAL token issued to the seeded admin.

    Signs ``ADMIN001`` on through the live login endpoint and returns headers
    that authenticate subsequent protected requests as that administrator via
    the genuine credential path (M-20).

    Args:
        client: The base ASGI client (shared per-test session).
        admin_user: The seeded administrator identity (``ADMIN001``).

    Returns:
        Cookie-auth headers for the seeded administrator.
    """
    token = await _PerformRealLogin(client, SEED_ADMIN_USER_ID, SEED_PASSWORD)
    return _CookieAuthHeaders(token)


@pytest_asyncio.fixture
async def regular_auth_headers(client, regular_user) -> dict[str, str]:
    """Cookie-auth headers carrying a REAL token issued to the seeded user.

    Signs ``USER0001`` on through the live login endpoint and returns headers
    that authenticate subsequent protected requests as that regular user via the
    genuine credential path (M-20).

    Args:
        client: The base ASGI client (shared per-test session).
        regular_user: The seeded regular identity (``USER0001``).

    Returns:
        Cookie-auth headers for the seeded regular user.
    """
    token = await _PerformRealLogin(client, SEED_REGULAR_USER_ID, SEED_PASSWORD)
    return _CookieAuthHeaders(token)



# ===========================================================================
# Golden-master seed fixtures.
#
# These populate ``db_session`` with rows parsed from ``app/data/ASCII`` in
# foreign-key-safe order. All decoded via the loader helpers above (money always
# Decimal). A test requests the granular fixture it needs, or ``seed_data`` for
# the full master + reference dataset. Because pytest caches fixtures per test,
# requesting ``seed_data`` (which composes the two granular seeders) never
# double-inserts. Every seeded row is wiped by the ``db_session`` truncate
# teardown, preserving per-test isolation.
# ===========================================================================


@pytest_asyncio.fixture
async def seed_reference_data(db_session) -> AsyncGenerator[None, None]:
    """Populate the standalone reference tables from the golden-master data.

    Loads the account-group registry, transaction types, transaction
    categories, disclosure groups and per-category balances. The
    ``account_groups`` registry (M-16 parent of ``accounts.group_id``) is seeded
    first so any account subsequently assigned a disclosure-known group resolves
    its foreign key; the remaining reference tables carry no foreign keys, so
    their order is unconstrained. Rows are flushed into ``db_session`` and are
    wiped by that fixture's truncate teardown.

    Args:
        db_session: The isolated async session the rows are added to.

    Yields:
        None -- a setup barrier; tests read the data back through ``db_session``.
    """
    db_session.add_all([AccountGroup(**row) for row in LoadAccountGroupRows()])
    db_session.add_all([TransactionType(**row) for row in LoadTranTypeRows()])
    db_session.add_all(
        [TransactionCategory(**row) for row in LoadTranCategoryRows()]
    )
    db_session.add_all(
        [DisclosureGroup(**row) for row in LoadDisclosureGroupRows()]
    )
    db_session.add_all(
        [TranCategoryBalance(**row) for row in LoadTcatBalRows()]
    )
    await db_session.flush()
    yield


@pytest_asyncio.fixture
async def seed_cards_customers_xref(db_session) -> AsyncGenerator[None, None]:
    """Populate the referential-integrity chain from the golden-master data.

    Insertion order respects the real PostgreSQL foreign keys: customers and
    accounts first, then cards (``cards.acct_id -> accounts.acct_id``), then the
    card cross-reference (``card_xref`` -> cards + customers + accounts). The
    cross-reference rows are built with the physical ``xref_card_num`` column
    (not the ``card_num`` synonym) to avoid any construction ambiguity. Rows are
    flushed into ``db_session`` and wiped by that fixture's truncate teardown.

    Args:
        db_session: The isolated async session the rows are added to.

    Yields:
        None -- a setup barrier; tests read the data back through ``db_session``.
    """
    db_session.add_all([Customer(**row) for row in LoadCustomerRows()])
    db_session.add_all([Account(**row) for row in LoadAccountsRows()])
    await db_session.flush()
    db_session.add_all([Card(**row) for row in LoadCardRows()])
    await db_session.flush()
    xrefObjects = [
        CardXref(
            xref_card_num=row["card_num"],
            cust_id=row["cust_id"],
            acct_id=row["acct_id"],
        )
        for row in LoadCardXrefRows()
    ]
    db_session.add_all(xrefObjects)
    await db_session.flush()
    yield


@pytest_asyncio.fixture
async def seed_data(
    seed_reference_data,
    seed_cards_customers_xref,
) -> AsyncGenerator[None, None]:
    """Populate the FULL golden-master master + reference dataset.

    A convenience aggregate composing :func:`seed_reference_data` and
    :func:`seed_cards_customers_xref`; requesting it yields a database populated
    with customers, accounts, cards, card cross-references and every reference
    table (transaction types/categories, disclosure groups, category balances).
    Daily transactions are intentionally NOT seeded (see :func:`LoadDailyTranRows`
    for why -- posting tests build their own transactions).

    Args:
        seed_reference_data: Reference-table seeder (composed, runs first).
        seed_cards_customers_xref: RI-chain seeder (composed).

    Yields:
        None -- a setup barrier; tests read the data back through ``db_session``.
    """
    yield

