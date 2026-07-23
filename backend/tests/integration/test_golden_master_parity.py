# Golden-master parity tests for the CardDemo migration.
# Seeds the app/data/ASCII sample via the `seed_data` fixture and reconciles the
# migrated PostgreSQL state field-for-field against independently-known expected
# values (AAP §0.5.1, §0.8.1 golden-master parity).
# Sources: app/data/ASCII/*.txt (seed), CBTRN02C (posting), CBACT04C (interest
# = TRAN-CAT-BAL * DIS-INT-RATE / 1200, TRUNCATED — no ROUNDED), COBIL00C (billpay
# available_credit = credit_limit - curr_bal). All money is exact Decimal — NEVER
# float, NEVER tolerance. Zoned-decimal semantics per §0.7.1.
"""Golden-master field-for-field parity tests for the CardDemo migration.

This is the single most important correctness module in the backend test suite.
It proves that the ETL/loaders + SQLAlchemy ORM + numeric mapping reproduce the
legacy mainframe values EXACTLY, so the modern PostgreSQL image of the CardDemo
data is byte-faithful to the original VSAM/COBOL system.

The suite is deliberately *database-level* (it does not use the httpx client):
each test seeds the golden-master ASCII sample through the shared ``seed_data``
fixture published by the parent ``backend/tests/conftest.py`` (there is
deliberately NO local ``conftest.py``) and then reads rows back through the very
same isolated ``db_session``. It asserts:

* exact ``Decimal`` monetary/rate values (signed zoned-decimal decode, AAP
  §0.7.1) -- never ``float``, never ``pytest.approx``, never a tolerance;
* the account lifecycle status and dates map to their native ``CHAR``/``DATE``
  columns;
* the disclosure-group interest rate keeps ``NUMERIC(6, 2)`` precision (AAP
  §0.7.2 Finding #2, the ``S9(4)V99 -> NUMERIC(6, 2)`` correction);
* the referential-integrity chain (card -> account + customer) resolves, and
  the VSAM cross-reference relationships became *enforced* foreign-key
  constraints -- a bogus parent reference raises ``IntegrityError`` (AAP §0.8.1);
* the seeded row counts reconcile with the ASCII source files;
* the two pure numeric identities port exactly: the CBACT04C monthly-interest
  COMPUTE truncates (``ROUND_DOWN``, no ``ROUNDED``) rather than rounding, and
  the COBIL00C available-credit identity ``credit_limit - curr_bal`` is exact;
* the CBTRN02C posting reject catalog (codes 100/101/102/103/109) carries the
  verbatim UPPERCASE descriptions, with 101 and 109 kept DISTINCT even though
  their text is identical.

Fixtures consumed (parent conftest): ``seed_data`` (full ASCII master + reference
seed), ``db_session`` (the same isolated ``AsyncSession`` the seeders write to).
Every ``db_session`` call is awaited sequentially; the shared session is not safe
for concurrent use.

Naming conventions (Ochs Rule, AAP §0.8.2 / §0.8.3): the file/module name is
snake_case; the module-level helper is PascalCase (:func:`MonthlyInterest`);
local and parameter identifiers are camelCase (``goldenAccount``,
``availableCredit``, ``rowCount``); module constants are ALL_UPPERCASE
(``GOLDEN_*``, ``RI_*``, ``EXPECTED_*``). The pytest fixture parameter names
(``seed_data``, ``db_session``) keep their snake_case contract names because
pytest resolves fixtures by name.
"""

from __future__ import annotations

import codecs
from decimal import ROUND_DOWN, Decimal
from pathlib import Path

import pytest
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError

from app.core.exceptions import (
    AccountExpiredError,
    AccountNotFoundError,
    AccountUpdateFailedError,
    InvalidCardNumberError,
    OverlimitTransactionError,
)
from app.models.account import Account
from app.models.account_group import AccountGroup
from app.models.card import Card
from app.models.card_xref import CardXref
from app.models.customer import Customer
from app.models.disclosure_group import DisclosureGroup
from app.models.transaction import Transaction
from app.utils.decimal_utils import DecodeZonedDecimal, TruncateToCents

# ===========================================================================
# Golden-master constants -- VERIFIED by direct inspection of the legacy
# copybooks (app/cpy/*.cpy) and the ASCII seed files (app/data/ASCII/*.txt).
# Every monetary/rate expectation is constructed from a Decimal STRING literal
# so no binary floating-point value can ever enter a comparison.
# ===========================================================================

# Account acctdata.txt row 1 (app/cpy/CVACT01Y.cpy, 300-byte ACCOUNT-RECORD).
GOLDEN_ACCT_ID = "00000000001"
GOLDEN_CURR_BAL = Decimal("194.00")               # ACCT-CURR-BAL      S9(10)V99
GOLDEN_CREDIT_LIMIT = Decimal("2020.00")          # ACCT-CREDIT-LIMIT  S9(10)V99
GOLDEN_CASH_CREDIT_LIMIT = Decimal("1020.00")     # ACCT-CASH-CREDIT-LIMIT
GOLDEN_CYC_CREDIT = Decimal("0.00")               # ACCT-CURR-CYC-CREDIT
GOLDEN_CYC_DEBIT = Decimal("0.00")                # ACCT-CURR-CYC-DEBIT
GOLDEN_ACTIVE_STATUS = "Y"                         # ACCT-ACTIVE-STATUS X(01)
GOLDEN_OPEN_DATE = "2014-11-20"                    # ACCT-OPEN-DATE     X(10)
GOLDEN_EXPIRATION_DATE = "2025-05-20"              # ACCT-EXPIRAION-DATE (sic)
GOLDEN_REISSUE_DATE = "2025-05-20"                 # ACCT-REISSUE-DATE  X(10)

# Referential-integrity chain: cardxref.txt row 1 (app/cpy/CVACT03Y.cpy) links a
# card number to its owning customer and account; carddata.txt row 1 carries the
# matching CVV. These three identifiers span the cards, customers and accounts
# tables and prove the RI chain resolves after seeding.
RI_CARD_NUM = "0500024453765740"                   # XREF-CARD-NUM      X(16)
RI_CUST_ID = "000000050"                           # XREF-CUST-ID       9(09)
RI_ACCT_ID = "00000000050"                         # XREF-ACCT-ID       9(11)
GOLDEN_CVV = "747"                                  # CARD-CVV-CD        9(03)

# Disclosure-group key + rate: discgrp.txt row 1 (app/cpy/CVTRA02Y.cpy). The key
# tuple is (acct_group_id, tran_type_cd, tran_cat_cd); the rate proves the
# S9(4)V99 -> NUMERIC(6, 2) precision correction (AAP §0.7.2 Finding #2).
GOLDEN_DISC_KEY = ("A000000000", "01", "0001")
GOLDEN_INT_RATE = Decimal("15.00")                 # DIS-INT-RATE       S9(04)V99

# COBIL00C available-credit identity for the golden account (F-006):
# credit_limit - curr_bal = 2020.00 - 194.00 = 1826.00 (exact Decimal).
GOLDEN_AVAILABLE_CREDIT = Decimal("1826.00")

# Expected seed row counts. VERIFIED via ``wc -l app/data/ASCII/*.txt``; the
# conftest loaders are 1:1 with those files. IMPORTANT: ``seed_data`` intentionally
# does NOT seed daily transactions (see conftest ``LoadDailyTranRows`` -- posting
# tests build their own), so the ``transactions`` table is empty after seeding.
EXPECTED_ACCOUNT_COUNT = 50                         # acctdata.txt   (50 rows)
EXPECTED_CARD_COUNT = 50                            # carddata.txt   (50 rows)
EXPECTED_CARD_XREF_COUNT = 50                       # cardxref.txt   (50 rows)
EXPECTED_CUSTOMER_COUNT = 50                        # custdata.txt   (50 rows)
EXPECTED_DISCLOSURE_GROUP_COUNT = 51                # discgrp.txt    (51 rows)
EXPECTED_TRANSACTION_COUNT = 0                      # daily-tran NOT seeded

# Bogus keys with no seeded parent row, used to prove foreign-key enforcement.
NONEXISTENT_ACCT_ID = "99999999999"
NONEXISTENT_CARD_NUM = "9999999999999999"
NONEXISTENT_TRAN_ID = "9999999999999999"
# Account-group FK (M-16) fixtures: a bogus group with no account_groups parent,
# and a valid group to prove the constraint accepts a real reference.
NONEXISTENT_GROUP_ID = "NOSUCHGRP"
FK_TEST_GROUP_ID = "FKTESTGRP"
FK_TEST_ACCT_ID = "88888888888"


def _BuildFkTestAccount(acctId: str, groupId: str | None) -> Account:
    """Build an FK-test account with every NOT NULL column populated.

    A minimal but complete :class:`~app.models.account.Account` (all required
    monetary columns set to exact ``Decimal`` zeros) used purely to exercise the
    M-16 ``accounts.group_id`` foreign key with a chosen ``groupId``.

    Args:
        acctId: The account id (primary key) to assign.
        groupId: The group id to place on ``group_id`` (may be None).

    Returns:
        An unsaved ``Account`` instance ready to add to a session.
    """
    return Account(
        acct_id=acctId,
        active_status="Y",
        curr_bal=Decimal("0.00"),
        credit_limit=Decimal("0.00"),
        cash_credit_limit=Decimal("0.00"),
        curr_cyc_credit=Decimal("0.00"),
        curr_cyc_debit=Decimal("0.00"),
        group_id=groupId,
    )


# ===========================================================================
# Pure-computation helper (PascalCase per Ochs). Mirrors the legacy interest
# COMPUTE so the truncation semantics are asserted independently of the app.
# ===========================================================================


def MonthlyInterest(balance: Decimal, rate: Decimal) -> Decimal:
    """Reproduce the CBACT04C monthly-interest COMPUTE (truncated, no ROUNDED).

    Mirrors ``COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200`` at
    ``app/cbl/CBACT04C.cbl`` L464-465, whose receiving field ``WS-MONTHLY-INT``
    is ``PIC S9(09)V99`` (two fractional digits). A COBOL ``COMPUTE`` WITHOUT the
    ``ROUNDED`` phrase TRUNCATES toward zero to the receiving scale -- it does not
    round-half-up -- so ``ROUND_DOWN`` is used here. The production helper
    :func:`app.utils.decimal_utils.TruncateToCents` is asserted to agree, proving
    the shipped code matches this legacy identity.

    Args:
        balance: The category balance (``TRAN-CAT-BAL``) as an exact ``Decimal``.
        rate: The monthly disclosure interest rate (``DIS-INT-RATE``) as an exact
            ``Decimal``.

    Returns:
        The monthly interest truncated to cents (two decimal places) as an exact
        :class:`decimal.Decimal`.
    """
    rawInterest = balance * rate / Decimal(1200)
    return rawInterest.quantize(Decimal("0.01"), rounding=ROUND_DOWN)


# ===========================================================================
# INDEPENDENT legacy-layout oracle (QA finding M-17). This block deliberately
# does NOT reuse app.utils.decimal_utils: it re-derives the signed zoned-decimal
# overpunch from ORDERED code-point strings and plain integer arithmetic -- a
# structurally different implementation from production's lookup dicts + string
# splicing. Comparing the two therefore catches a defect in EITHER direction
# instead of validating the production decoder against a copy of itself, and it
# exercises the negative authoritative overpunch (}/J-R) that the ASCII account
# rows (all-positive) never reach. The USRSEC EBCDIC seed is likewise decoded
# independently with the standard-library ``cp037`` codec so the golden suite
# covers the EBCDIC users it previously omitted.
# ===========================================================================

# Repository-root-relative golden-master data locations. This test file lives at
# ``backend/tests/integration/`` so ``parents[3]`` is the repository root under
# which the legacy (REFERENCE-only) ``app/data`` tree resides.
_REPO_ROOT = Path(__file__).resolve().parents[3]
_ASCII_DATA_DIR = _REPO_ROOT / "app" / "data" / "ASCII"
_EBCDIC_USRSEC_PATH = (
    _REPO_ROOT / "app" / "data" / "EBCDIC" / "AWS.M2.CARDDEMO.USRSEC.PS"
)
_EBCDIC_CODEC = "cp037"

# USRSEC record layout (app/cpy/CSUSR01Y.cpy, SEC-USER-DATA, RECLN 80).
_USER_RECORD_LENGTH = 80
_USER_ID_SLICE = slice(0, 8)
_USER_TYPE_SLICE = slice(56, 57)

# dailytran.txt (app/cpy/CVTRA06Y.cpy, 350-byte DALYTRAN-RECORD): the signed
# ``DALYTRAN-AMT PIC S9(09)V99`` field occupies bytes [132:143]. This is the one
# ASCII seed file that carries NEGATIVE authoritative overpunch terminators, so
# it is the independent oracle's negative-overpunch source.
_DAILYTRAN_ROW_LENGTH = 350
_DAILYTRAN_AMT_SLICE = slice(132, 143)

# acctdata.txt (app/cpy/CVACT01Y.cpy, 300-byte ACCOUNT-RECORD): the five
# ``PIC S9(10)V99`` money fields and their byte offsets (verified against the
# conftest loader map). Used to prove the SEEDED (production-decoded) DB values
# equal an INDEPENDENT decode of the same raw bytes.
_ACCTDATA_ROW_LENGTH = 300
_ACCT_ID_SLICE = slice(0, 11)
_ACCT_MONEY_SLICES = {
    "curr_bal": slice(12, 24),
    "credit_limit": slice(24, 36),
    "cash_credit_limit": slice(36, 48),
    "curr_cyc_credit": slice(78, 90),
    "curr_cyc_debit": slice(90, 102),
}

# Implied fractional digits for every CardDemo money / rate field (COBOL ``V99``).
_MONEY_SCALE = 2

# Independent overpunch alphabets as ORDERED strings (index == encoded digit),
# NOT dict literals -- so they cannot be a copy-paste of production's maps.
_POSITIVE_OVERPUNCH = "{ABCDEFGHI"   # index 0..9 -> '{', 'A'..'I' (sign +)
_NEGATIVE_OVERPUNCH = "}JKLMNOPQR"   # index 0..9 -> '}', 'J'..'R' (sign -)

# Expected USRSEC seed identities, decoded INDEPENDENTLY from the EBCDIC dataset
# (5 admins user_type 'A', 5 regular user_type 'U'); SEED-ONLY per the README,
# never real credentials.
_EXPECTED_USER_TYPES = {
    "ADMIN001": "A", "ADMIN002": "A", "ADMIN003": "A", "ADMIN004": "A",
    "ADMIN005": "A", "USER0001": "U", "USER0002": "U", "USER0003": "U",
    "USER0004": "U", "USER0005": "U",
}


def IndependentDecodeZoned(rawValue: str, scale: int) -> Decimal:
    """Independently decode a signed zoned-decimal DISPLAY field to ``Decimal``.

    Re-implements the legacy overpunch convention from first principles: the
    final byte both carries the low-order digit and encodes the sign (``{``/``A``
    -``I`` positive; ``}``/``J``-``R`` negative). The digit is recovered by its
    INDEX into an ordered alphabet and the value assembled with plain integer
    arithmetic and :meth:`decimal.Decimal.scaleb`, so this oracle shares no code
    with :func:`app.utils.decimal_utils.DecodeZonedDecimal`.

    Args:
        rawValue: The raw fixed-width field exactly as stored, e.g. ``"0000009190}"``.
        scale: The number of implied fractional digits (``2`` for money).

    Returns:
        The decoded value as an exact :class:`decimal.Decimal`.

    Raises:
        ValueError: If the final byte is not a legal overpunch/digit, or the
            remaining payload is non-numeric.
    """
    lastByte = rawValue[-1]
    if lastByte in _POSITIVE_OVERPUNCH:
        signIsNegative = False
        finalDigit = _POSITIVE_OVERPUNCH.index(lastByte)
    elif lastByte in _NEGATIVE_OVERPUNCH:
        signIsNegative = True
        finalDigit = _NEGATIVE_OVERPUNCH.index(lastByte)
    elif lastByte.isdigit():
        signIsNegative = False
        finalDigit = int(lastByte)
    else:
        raise ValueError(f"Illegal zoned-decimal overpunch byte: {lastByte!r}")
    allDigits = rawValue[:-1] + str(finalDigit)
    if not allDigits.isdigit():
        raise ValueError(f"Non-numeric zoned-decimal payload: {rawValue!r}")
    unsignedValue = int(allDigits)
    signedValue = -unsignedValue if signIsNegative else unsignedValue
    if scale == 0:
        return Decimal(signedValue)
    return Decimal(signedValue).scaleb(-scale)


def _ReadRawRows(fileName: str, rowLength: int) -> list[str]:
    """Read a golden-master ASCII file into its fixed-width rows (no decoding).

    Args:
        fileName: Base name inside ``app/data/ASCII`` (e.g. ``"acctdata.txt"``).
        rowLength: The fixed record length used to slice rows, so a stray
            trailing newline can never shift a field offset.

    Returns:
        The list of ``rowLength``-character rows, in file order.
    """
    rawText = (_ASCII_DATA_DIR / fileName).read_text(encoding="latin-1")
    rows = []
    for line in rawText.splitlines():
        if line.strip() == "":
            continue
        rows.append(line[:rowLength])
    return rows


def _ReadUsrsecRecords() -> list[str]:
    """Independently decode the EBCDIC USRSEC seed into fixed 80-char records.

    Uses the standard-library single-byte IBM codec ``cp037`` (independent of the
    production ``init_users`` loader) and slices on the copybook record length.

    Returns:
        The list of 80-character decoded user records, in file order.
    """
    rawBytes = _EBCDIC_USRSEC_PATH.read_bytes()
    decodedText = codecs.decode(rawBytes, _EBCDIC_CODEC)
    return [
        decodedText[offset:offset + _USER_RECORD_LENGTH]
        for offset in range(0, len(decodedText), _USER_RECORD_LENGTH)
    ]


# ===========================================================================
# Phase 2 -- zoned-decimal money parity (exact Decimal, never float).
# ===========================================================================


async def test_account_monetary_fields_exact_decimal(seed_data, db_session):
    """Golden account monetary fields decode to the exact expected ``Decimal``s.

    Proves the signed zoned-decimal DISPLAY money fields (AAP §0.7.1) load into
    ``NUMERIC(12, 2)`` columns and are returned by the ORM as :class:`Decimal`
    values equal, to the cent, to the independently decoded golden values.
    """
    goldenAccount = await db_session.scalar(
        select(Account).where(Account.acct_id == GOLDEN_ACCT_ID)
    )
    assert goldenAccount is not None

    # Each field must be an exact Decimal (proves NUMERIC, not float) AND equal
    # to its golden literal. Comparison is ``==`` against a Decimal string
    # literal -- never float, never pytest.approx, never a tolerance.
    assert isinstance(goldenAccount.curr_bal, Decimal)
    assert goldenAccount.curr_bal == GOLDEN_CURR_BAL

    assert isinstance(goldenAccount.credit_limit, Decimal)
    assert goldenAccount.credit_limit == GOLDEN_CREDIT_LIMIT

    assert isinstance(goldenAccount.cash_credit_limit, Decimal)
    assert goldenAccount.cash_credit_limit == GOLDEN_CASH_CREDIT_LIMIT

    assert isinstance(goldenAccount.curr_cyc_credit, Decimal)
    assert goldenAccount.curr_cyc_credit == GOLDEN_CYC_CREDIT

    assert isinstance(goldenAccount.curr_cyc_debit, Decimal)
    assert goldenAccount.curr_cyc_debit == GOLDEN_CYC_DEBIT


async def test_account_status_and_dates(seed_data, db_session):
    """Golden account status maps to CHAR and the three dates map to DATE.

    The ``PIC X(01)`` active-status flag round-trips as ``"Y"`` and the three
    ``PIC X(10)`` ``YYYY-MM-DD`` fields land in native ``DATE`` columns whose ISO
    string form matches the golden values exactly.
    """
    goldenAccount = await db_session.scalar(
        select(Account).where(Account.acct_id == GOLDEN_ACCT_ID)
    )
    assert goldenAccount is not None

    assert goldenAccount.active_status == GOLDEN_ACTIVE_STATUS
    # ``str(date(...))`` renders ISO ``YYYY-MM-DD``; equality proves the text
    # date parsed into a real DATE column rather than being stored as text.
    assert str(goldenAccount.open_date) == GOLDEN_OPEN_DATE
    assert str(goldenAccount.expiration_date) == GOLDEN_EXPIRATION_DATE
    assert str(goldenAccount.reissue_date) == GOLDEN_REISSUE_DATE


async def test_disclosure_interest_rate_precision(seed_data, db_session):
    """Disclosure-group interest rate keeps ``NUMERIC(6, 2)`` precision.

    Selects the golden disclosure-group row by its composite key and asserts the
    rate equals ``Decimal("15.00")`` as an exact :class:`Decimal`, proving the
    ``S9(4)V99 -> NUMERIC(6, 2)`` mapping (AAP §0.7.2 Finding #2, correcting the
    draft schema's ``NUMERIC(5, 2)``).
    """
    goldenGroupId, goldenTranTypeCd, goldenTranCatCd = GOLDEN_DISC_KEY
    disclosureRow = await db_session.scalar(
        select(DisclosureGroup).where(
            DisclosureGroup.group_id == goldenGroupId,
            DisclosureGroup.tran_type_cd == goldenTranTypeCd,
            DisclosureGroup.tran_cat_cd == goldenTranCatCd,
        )
    )
    assert disclosureRow is not None

    assert isinstance(disclosureRow.interest_rate, Decimal)
    assert disclosureRow.interest_rate == GOLDEN_INT_RATE


# ===========================================================================
# Phase 3 -- referential integrity: the RI chain resolves and the VSAM
# cross-reference relationships became ENFORCED foreign-key constraints.
# ===========================================================================


async def test_ri_chain_card_to_acct_to_cust(seed_data, db_session):
    """The card cross-reference resolves to a real account and customer.

    Selects the golden ``CardXref`` row (by the ``card_num`` synonym that maps to
    the physical ``xref_card_num`` column), asserts it points at the expected
    customer and account, and confirms all three parent rows -- account, customer
    and card -- exist, so the VSAM ``CARDXREF`` chain is intact after seeding.
    """
    cardXref = await db_session.scalar(
        select(CardXref).where(CardXref.card_num == RI_CARD_NUM)
    )
    assert cardXref is not None
    # The ``card_num`` synonym reads back the physical ``xref_card_num`` value.
    assert cardXref.card_num == RI_CARD_NUM
    assert cardXref.cust_id == RI_CUST_ID
    assert cardXref.acct_id == RI_ACCT_ID

    # The chain resolves: every referenced parent row exists.
    parentAccount = await db_session.scalar(
        select(Account).where(Account.acct_id == RI_ACCT_ID)
    )
    parentCustomer = await db_session.scalar(
        select(Customer).where(Customer.cust_id == RI_CUST_ID)
    )
    parentCard = await db_session.scalar(
        select(Card).where(Card.card_num == RI_CARD_NUM)
    )
    assert parentAccount is not None
    assert parentCustomer is not None
    assert parentCard is not None
    # The card's own foreign key agrees with the cross-reference account.
    assert parentCard.acct_id == RI_ACCT_ID


async def test_fk_enforced_missing_parent_card(seed_data, db_session):
    """Inserting a card with a non-existent parent account raises ``IntegrityError``.

    Proves the ``cards.acct_id -> accounts.acct_id`` relationship became an
    ENFORCED foreign-key constraint (AAP §0.8.1): flushing a card whose account
    does not exist is rejected by PostgreSQL. The session is rolled back
    afterwards so its transaction is clean for teardown.
    """
    orphanCard = Card(
        card_num=NONEXISTENT_CARD_NUM,
        acct_id=NONEXISTENT_ACCT_ID,
        embossed_name="FK ENFORCEMENT TEST",
        active_status="N",
    )
    db_session.add(orphanCard)
    with pytest.raises(IntegrityError):
        await db_session.flush()
    await db_session.rollback()


async def test_fk_enforced_missing_parent_transaction(seed_data, db_session):
    """Inserting a transaction with a non-existent parent card raises ``IntegrityError``.

    The ``transactions.card_num -> cards.card_num`` relationship is an enforced
    foreign key, so flushing a transaction that references a card which was never
    seeded is rejected. The session is rolled back afterwards to leave its
    transaction clean for teardown.
    """
    orphanTransaction = Transaction(
        tran_id=NONEXISTENT_TRAN_ID,
        tran_type_cd="01",
        tran_cat_cd="0001",
        tran_amt=Decimal("1.00"),
        card_num=NONEXISTENT_CARD_NUM,
    )
    db_session.add(orphanTransaction)
    with pytest.raises(IntegrityError):
        await db_session.flush()
    await db_session.rollback()


async def test_fk_enforced_missing_account_group(db_session):
    """Inserting an account with an unknown ``group_id`` raises ``IntegrityError``.

    Proves the AAP-mandated ``accounts.group_id -> account_groups.group_id``
    relationship (AAP §0.5.1, §0.8.1; QA finding M-16) is an ENFORCED foreign
    key: flushing an account whose group has no ``account_groups`` parent row is
    rejected by PostgreSQL. No ``account_groups`` row is seeded for
    ``NONEXISTENT_GROUP_ID``, so the reference is dangling. The session is rolled
    back afterwards so its transaction is clean for teardown.
    """
    orphanAccount = _BuildFkTestAccount(FK_TEST_ACCT_ID, NONEXISTENT_GROUP_ID)
    db_session.add(orphanAccount)
    with pytest.raises(IntegrityError):
        await db_session.flush()
    await db_session.rollback()


async def test_account_group_fk_accepts_valid_group(db_session):
    """An account referencing an existing ``account_groups`` row is accepted.

    The complement of :func:`test_fk_enforced_missing_account_group`: once the
    parent ``account_groups`` row exists, an account referencing it flushes
    cleanly. This proves the M-16 foreign key accepts valid references (it is not
    merely rejecting everything). The session is rolled back for teardown.
    """
    db_session.add(AccountGroup(group_id=FK_TEST_GROUP_ID))
    await db_session.flush()

    validAccount = _BuildFkTestAccount(FK_TEST_ACCT_ID, FK_TEST_GROUP_ID)
    db_session.add(validAccount)
    await db_session.flush()

    persisted = await db_session.get(Account, FK_TEST_ACCT_ID)
    assert persisted is not None
    assert persisted.group_id == FK_TEST_GROUP_ID
    await db_session.rollback()


async def test_account_group_fk_allows_null(db_session):
    """An account with a NULL ``group_id`` is accepted (mirrors the seed quirk).

    Every golden-master account carries a blank ``ACCT-GROUP-ID`` (loaded as
    NULL), and NULLs are exempt from the foreign-key check. This confirms the
    M-16 constraint does not force a group on the byte-faithful seed data. The
    session is rolled back for teardown.
    """
    nullGroupAccount = _BuildFkTestAccount(FK_TEST_ACCT_ID, None)
    db_session.add(nullGroupAccount)
    await db_session.flush()

    persisted = await db_session.get(Account, FK_TEST_ACCT_ID)
    assert persisted is not None
    assert persisted.group_id is None
    await db_session.rollback()


# ===========================================================================
# Phase 4 -- row-count parity (seed reconciliation against the ASCII sources).
# ===========================================================================


async def test_seed_row_counts(seed_data, db_session):
    """Every seeded table reconciles with its ASCII golden-master source.

    Counts are compared against constants verified from the ASCII line counts
    (the loaders are 1:1 with the files). ``transactions`` is expected to be 0
    because ``seed_data`` intentionally does not seed the daily-transaction file.
    """
    accountCount = await db_session.scalar(
        select(func.count()).select_from(Account)
    )
    assert accountCount == EXPECTED_ACCOUNT_COUNT

    cardCount = await db_session.scalar(
        select(func.count()).select_from(Card)
    )
    assert cardCount == EXPECTED_CARD_COUNT

    cardXrefCount = await db_session.scalar(
        select(func.count()).select_from(CardXref)
    )
    assert cardXrefCount == EXPECTED_CARD_XREF_COUNT

    customerCount = await db_session.scalar(
        select(func.count()).select_from(Customer)
    )
    assert customerCount == EXPECTED_CUSTOMER_COUNT

    disclosureGroupCount = await db_session.scalar(
        select(func.count()).select_from(DisclosureGroup)
    )
    assert disclosureGroupCount == EXPECTED_DISCLOSURE_GROUP_COUNT

    # Daily transactions are intentionally NOT seeded by ``seed_data``.
    transactionCount = await db_session.scalar(
        select(func.count()).select_from(Transaction)
    )
    assert transactionCount == EXPECTED_TRANSACTION_COUNT


# ===========================================================================
# Phase 5 -- computation parity (pure exact-Decimal identities; no float).
# ===========================================================================


def test_interest_formula_truncates_round_down():
    """Monthly interest truncates (``ROUND_DOWN``) instead of rounding-half-up.

    Locks in the CBACT04C ``COMPUTE ... WITHOUT ROUNDED`` truncation semantics.
    ``194.00 * 15.00 / 1200 = 2.425`` must truncate DOWN to ``2.42`` (never
    round to ``2.43``); ``100.00 * 19.99 / 1200 = 1.6658...`` must truncate DOWN
    to ``1.66`` (never ``1.67``). The production helper ``TruncateToCents`` must
    reproduce the identical truncated values.
    """
    # Primary case: the classic 2.425 half-cent that distinguishes truncation
    # from rounding.
    firstCase = MonthlyInterest(GOLDEN_CURR_BAL, GOLDEN_INT_RATE)
    assert isinstance(firstCase, Decimal)
    assert firstCase == Decimal("2.42")
    assert firstCase != Decimal("2.43")

    # Secondary case whose rounded and truncated results also differ.
    secondCase = MonthlyInterest(Decimal("100.00"), Decimal("19.99"))
    assert secondCase == Decimal("1.66")
    assert secondCase != Decimal("1.67")

    # The shipped production helper must agree with the legacy identity exactly.
    appFirstCase = TruncateToCents(
        GOLDEN_CURR_BAL * GOLDEN_INT_RATE / Decimal(1200)
    )
    assert isinstance(appFirstCase, Decimal)
    assert appFirstCase == Decimal("2.42")

    appSecondCase = TruncateToCents(
        Decimal("100.00") * Decimal("19.99") / Decimal(1200)
    )
    assert appSecondCase == Decimal("1.66")


async def test_available_credit_identity(seed_data, db_session):
    """The COBIL00C available-credit identity is exact: limit minus balance.

    For the golden account, ``credit_limit - curr_bal`` must equal
    ``Decimal("1826.00")`` (``2020.00 - 194.00``, F-006). The subtraction stays
    in exact ``Decimal`` arithmetic -- no float ever participates.
    """
    goldenAccount = await db_session.scalar(
        select(Account).where(Account.acct_id == GOLDEN_ACCT_ID)
    )
    assert goldenAccount is not None

    availableCredit = goldenAccount.credit_limit - goldenAccount.curr_bal
    assert isinstance(availableCredit, Decimal)
    assert availableCredit == GOLDEN_AVAILABLE_CREDIT


# ===========================================================================
# Phase 6 -- posting reason-code parity (static/domain guard, CBTRN02C).
# ===========================================================================


def test_posting_reason_codes_and_texts():
    """The CBTRN02C posting reject catalog carries exact codes and verbatim text.

    Each posting exception must expose the exact numeric ``code`` and the verbatim
    UPPERCASE ``description`` from CBTRN02C's ``1500-VALIDATE-TRAN`` /
    ``2800-UPDATE-ACCOUNT-REC``. Codes 101 and 109 share the identical description
    text ("ACCOUNT RECORD NOT FOUND") but MUST remain DISTINCT codes.
    """
    invalidCard = InvalidCardNumberError()
    assert invalidCard.code == 100
    assert invalidCard.description == "INVALID CARD NUMBER FOUND"

    accountNotFound = AccountNotFoundError()
    assert accountNotFound.code == 101
    assert accountNotFound.description == "ACCOUNT RECORD NOT FOUND"

    overlimitTransaction = OverlimitTransactionError()
    assert overlimitTransaction.code == 102
    assert overlimitTransaction.description == "OVERLIMIT TRANSACTION"

    accountExpired = AccountExpiredError()
    assert accountExpired.code == 103
    assert accountExpired.description == "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"

    accountUpdateFailed = AccountUpdateFailedError()
    assert accountUpdateFailed.code == 109
    assert accountUpdateFailed.description == "ACCOUNT RECORD NOT FOUND"

    # 101 vs 109: identical description text, but DISTINCT reason codes.
    assert accountNotFound.code != accountUpdateFailed.code
    assert accountNotFound.description == accountUpdateFailed.description


# ===========================================================================
# Phase 7 -- INDEPENDENT-ORACLE de-circularization (QA finding M-17).
# ===========================================================================


def test_independent_oracle_agrees_with_production_zoned_decimal():
    # QA M-17: prove the production DecodeZonedDecimal agrees with a genuinely
    # INDEPENDENT decoder (code-point arithmetic, no shared code) across positive
    # AND negative authoritative overpunch. A curated set spans zero, the positive
    # digit alphabet, and the negative digit alphabet -- so the two implementations
    # cross-validate instead of the oracle validating production against itself.
    curatedFields = [
        "00000001940{",   # +194.00
        "0000009190}",    # -919.00 (negative overpunch, all digits present)
        "0000000000{",    # +0.00
        "0000000123A",    # +12.31 (A == +1 low digit)
        "0000000129I",    # +12.99 (I == +9 low digit)
        "0000000121J",    # -12.11 (J == -1 low digit)
        "0000000129R",    # -12.99 (R == -9 low digit)
    ]
    for rawField in curatedFields:
        productionValue = DecodeZonedDecimal(rawField, _MONEY_SCALE)
        independentValue = IndependentDecodeZoned(rawField, _MONEY_SCALE)
        assert isinstance(productionValue, Decimal)
        assert isinstance(independentValue, Decimal)
        assert productionValue == independentValue, rawField


def test_independent_oracle_negative_overpunch_exhaustive():
    # QA M-17: the previously-omitted negative authoritative overpunch. Every
    # negative terminator '}' and 'J'..'R' must decode to the exact negative
    # low-order digit, and production must agree byte-for-byte. Scale 0 isolates
    # the sign+digit mapping from the implied decimal point.
    for expectedDigit, terminator in enumerate(_NEGATIVE_OVERPUNCH):
        rawField = "000" + terminator
        independentValue = IndependentDecodeZoned(rawField, 0)
        # '}' encodes -0, which equals 0; 'J'..'R' encode -1..-9.
        assert independentValue == Decimal(-expectedDigit)
        assert DecodeZonedDecimal(rawField, 0) == independentValue
    # And the positive alphabet stays positive (guards against a sign flip).
    for expectedDigit, terminator in enumerate(_POSITIVE_OVERPUNCH):
        rawField = "000" + terminator
        assert IndependentDecodeZoned(rawField, 0) == Decimal(expectedDigit)


async def test_seeded_accounts_match_independent_oracle(seed_data, db_session):
    # QA M-17: de-circularize the money-field pipeline end to end. The DB was
    # seeded through the PRODUCTION decoder; here every seeded account's five
    # monetary columns are compared against an INDEPENDENT decode of the very same
    # raw acctdata.txt bytes. Agreement across all 50 rows proves the production
    # load path is correct against a reference that shares none of its code.
    rawRows = _ReadRawRows("acctdata.txt", _ACCTDATA_ROW_LENGTH)
    assert len(rawRows) == EXPECTED_ACCOUNT_COUNT
    for rawRow in rawRows:
        acctId = rawRow[_ACCT_ID_SLICE].strip()
        seededAccount = await db_session.scalar(
            select(Account).where(Account.acct_id == acctId)
        )
        assert seededAccount is not None, acctId
        for columnName, fieldSlice in _ACCT_MONEY_SLICES.items():
            independentValue = IndependentDecodeZoned(
                rawRow[fieldSlice], _MONEY_SCALE
            )
            seededValue = getattr(seededAccount, columnName)
            assert isinstance(seededValue, Decimal)
            assert seededValue == independentValue, (acctId, columnName)


def test_dailytran_negative_overpunch_independent_vs_production():
    # QA M-17: the ASCII account rows are all-positive, so negative overpunch is
    # exercised HERE against dailytran.txt, whose signed DALYTRAN-AMT carries the
    # full '}'/'J'..'R' negative alphabet. Every row's amount is decoded by BOTH
    # implementations and asserted equal; the test also PROVES it is non-vacuous
    # by requiring that a meaningful number of the decoded amounts are negative.
    rawRows = _ReadRawRows("dailytran.txt", _DAILYTRAN_ROW_LENGTH)
    assert len(rawRows) > 0
    negativeCount = 0
    for rawRow in rawRows:
        rawAmount = rawRow[_DAILYTRAN_AMT_SLICE]
        productionValue = DecodeZonedDecimal(rawAmount, _MONEY_SCALE)
        independentValue = IndependentDecodeZoned(rawAmount, _MONEY_SCALE)
        assert productionValue == independentValue, rawAmount
        if independentValue < 0:
            negativeCount += 1
    # dailytran.txt carries 50 negative-overpunch amounts (}=6, J..R=44); require
    # a healthy floor so a future data/loader change cannot silently drop them.
    assert negativeCount >= 40


def test_ebcdic_users_independent_layout_and_identities():
    # QA M-17: the golden suite previously OMITTED the EBCDIC users entirely. The
    # USRSEC seed is EBCDIC-only, so it is decoded INDEPENDENTLY here with the
    # stdlib cp037 codec and sliced per CSUSR01Y. All ten seed operators and their
    # user_type roles ('A' admin / 'U' regular) must be present exactly.
    userRecords = _ReadUsrsecRecords()
    assert len(userRecords) == len(_EXPECTED_USER_TYPES)
    decodedTypes = {}
    for userRecord in userRecords:
        assert len(userRecord) == _USER_RECORD_LENGTH
        userId = userRecord[_USER_ID_SLICE].strip()
        userType = userRecord[_USER_TYPE_SLICE]
        decodedTypes[userId] = userType
    assert decodedTypes == _EXPECTED_USER_TYPES
