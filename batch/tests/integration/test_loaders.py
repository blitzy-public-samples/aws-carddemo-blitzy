# DB-backed golden-master integration tests for the CardDemo seed loaders.
#
# Provenance: greenfield batch test tree (AAP 0.5.2 ``batch/tests/**``). These
# tests reconcile the nine synchronous seed loaders in ``batch/loaders/*``
# against the legacy ASCII sample data in ``app/data/ASCII`` -- the golden-master
# parity requirement (AAP 0.5.2 / 0.8.1). Each loader ports a legacy IDCAMS
# DELETE/DEFINE/REPRO load job, and each ASCII file is the display-readable image
# of a VSAM dataset described by an ``app/cpy`` copybook, so every test names the
# loader + fixture (+ copybook) it reconciles against. Monetary and rate fields
# are decoded from signed zoned decimal (AAP 0.7.1) and asserted as exact
# :class:`decimal.Decimal` values compared for exact equality -- never as
# floating point and never with a fuzzy/tolerance comparison.
"""Golden-master integration tests reconciling batch.loaders.* to app/data/ASCII.

The batch layer is SYNCHRONOUS: these tests use plain ``pytest`` with the
blocking synchronous SQLAlchemy :class:`~sqlalchemy.orm.Session` provided by the
root ``batch/tests/conftest.py``. They deliberately avoid every asynchronous
database driver, async engine, and async HTTP test client that is reserved for
the FastAPI request layer. Two conftest fixtures are consumed:

* ``db_session`` -- an isolated, transaction-rolled-back synchronous ``Session``
  that starts EMPTY for every test and discards all writes on teardown, so the
  row counts asserted below are deterministic per test.
* ``data_dir`` -- the :class:`~pathlib.Path` to ``<repo>/app/data/ASCII`` (the
  nine golden-master seed files). It is passed explicitly to every loader so the
  seed source is deterministic and never depends on a loader's default.

Both fixture names are snake_case: that is the documented pytest framework
exception to the Ochs camelCase-variable rule (a fixture name is referenced by
the test's argument name). Every loader performs a SQLAlchemy Core
``INSERT ... ON CONFLICT`` upsert whose written rows are visible to a subsequent
``select`` / ``get`` in the same transaction, so no ``db_session.commit()`` is
ever issued here.
"""

from __future__ import annotations

import codecs
from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy import func, select

from app.core.security import VerifyPassword
from app.models import (
    STATUS_PENDING,
    Account,
    Card,
    CardXref,
    Customer,
    DisclosureGroup,
    TranCategoryBalance,
    Transaction,
    TransactionCategory,
    TransactionType,
    User,
)
from batch.loaders.init_users import InitializeUsers
from batch.loaders.load_accounts import (
    ACCT_ID_LENGTH,
    RECORD_LENGTH,
    SEED_FILE_NAME,
    LoadAccounts,
    _ValidateAccountId,
)
from batch.loaders.load_cards import LoadCards
from batch.loaders.load_customers import LoadCustomers
from batch.loaders.load_disclosure_groups import LoadDisclosureGroups
from batch.loaders.load_tcatbal import LoadTranCategoryBalances
from batch.loaders.load_tran_categories import LoadTranCategories
from batch.loaders.load_tran_types import LoadTranTypes
from batch.loaders.load_transactions import LoadTransactions
from batch.loaders.load_xref import LoadCardXref

# --------------------------------------------------------------------------- #
# Module constants (ALL_UPPERCASE per the Ochs Rule).
# --------------------------------------------------------------------------- #
# Golden-master row counts -- the ``wc -l`` of each ``app/data/ASCII`` seed file.
# Each loader must persist exactly this many rows (and re-persist the same count
# idempotently), so both the loader return value and the table row count are
# asserted against these named expectations.
EXPECTED_TRAN_TYPE_COUNT = 7          # trantype.txt  (CVTRA03Y)
EXPECTED_TRAN_CATEGORY_COUNT = 18     # trancatg.txt  (CVTRA04Y)
EXPECTED_DISCLOSURE_GROUP_COUNT = 51  # discgrp.txt   (CVTRA02Y)
EXPECTED_CUSTOMER_COUNT = 50          # custdata.txt  (CVCUS01Y)
EXPECTED_ACCOUNT_COUNT = 50           # acctdata.txt  (CVACT01Y)
EXPECTED_CARD_COUNT = 50              # carddata.txt  (CVACT02Y)
EXPECTED_CARD_XREF_COUNT = 50         # cardxref.txt  (CVACT03Y)
EXPECTED_TCATBAL_COUNT = 50           # tcatbal.txt   (CVTRA01Y)
EXPECTED_TRANSACTION_COUNT = 300      # dailytran.txt (CVTRA06Y)

# USRSEC (EBCDIC-only) user-security seed: 10 operators = 800 bytes / 80-byte
# CSUSR01Y SEC-USER-DATA record (init_users <- DUSRSECJ.jcl). Independent EBCDIC
# decode constants (stdlib cp037; sliced per the copybook) so the loader is
# checked against a reference that shares none of its parsing code (QA M-17).
EXPECTED_USER_COUNT = 10              # AWS.M2.CARDDEMO.USRSEC.PS (10 x 80 bytes)
USRSEC_CODEC = "cp037"
USRSEC_RECORD_LENGTH = 80
USRSEC_ID_SLICE = slice(0, 8)
USRSEC_TYPE_SLICE = slice(56, 57)
# SEED-ONLY plaintext (README); every user shares it and it must be HASHED, never
# stored or returned in cleartext (Ochs "no hardcoded secrets" + AAP 0.7.8).
SEED_PLAINTEXT_PASSWORD = "PASSWORD"
# Expected operator roles decoded independently from USRSEC: 5 admins / 5 regular.
EXPECTED_USER_TYPES = {
    "ADMIN001": "A", "ADMIN002": "A", "ADMIN003": "A", "ADMIN004": "A",
    "ADMIN005": "A", "USER0001": "U", "USER0002": "U", "USER0003": "U",
    "USER0004": "U", "USER0005": "U",
}


# --------------------------------------------------------------------------- #
# Module-private helpers (PascalCase per the Ochs Rule; the leading underscore
# marks them as internal to this test module, not a shared public API).
# --------------------------------------------------------------------------- #
def _CountRows(session, model) -> int:
    """Return the row count for a mapped model in the current transaction.

    Args:
        session: The active synchronous SQLAlchemy session.
        model: A mapped ORM class whose table rows are counted.

    Returns:
        The number of rows currently visible for ``model`` in this transaction.
    """
    return session.execute(select(func.count()).select_from(model)).scalar_one()


def _GetDisclosureGroup(session, groupId, tranTypeCd, tranCatCd):
    """Fetch one disclosure_group row by its composite primary key.

    A filtered ``select`` is used rather than ``session.get`` so the lookup never
    depends on composite-key tuple ordering.
    """
    return session.execute(
        select(DisclosureGroup).where(
            DisclosureGroup.group_id == groupId,
            DisclosureGroup.tran_type_cd == tranTypeCd,
            DisclosureGroup.tran_cat_cd == tranCatCd,
        )
    ).scalar_one()


def _GetTranCategory(session, tranTypeCd, tranCatCd):
    """Fetch one transaction_category row by its composite primary key."""
    return session.execute(
        select(TransactionCategory).where(
            TransactionCategory.tran_type_cd == tranTypeCd,
            TransactionCategory.tran_cat_cd == tranCatCd,
        )
    ).scalar_one()


def _GetTranCategoryBalance(session, acctId, tranTypeCd, tranCatCd):
    """Fetch one tran_category_balance row by its composite primary key."""
    return session.execute(
        select(TranCategoryBalance).where(
            TranCategoryBalance.acct_id == acctId,
            TranCategoryBalance.tran_type_cd == tranTypeCd,
            TranCategoryBalance.tran_cat_cd == tranCatCd,
        )
    ).scalar_one()


def _DecodeUsrsecTypes(usrsecPath) -> dict[str, str]:
    """Independently decode the EBCDIC USRSEC seed into ``{user_id: user_type}``.

    Uses the standard-library single-byte ``cp037`` codec and slices on the
    ``CSUSR01Y`` record layout, deliberately sharing no code with the production
    :func:`batch.loaders.init_users.InitializeUsers` loader so the loader is
    verified against a genuinely independent reference (QA finding M-17).

    Args:
        usrsecPath: Path to the ``AWS.M2.CARDDEMO.USRSEC.PS`` EBCDIC dataset.

    Returns:
        A mapping of each decoded ``user_id`` to its one-character ``user_type``.
    """
    decodedText = codecs.decode(usrsecPath.read_bytes(), USRSEC_CODEC)
    decodedTypes = {}
    for offset in range(0, len(decodedText), USRSEC_RECORD_LENGTH):
        record = decodedText[offset:offset + USRSEC_RECORD_LENGTH]
        decodedTypes[record[USRSEC_ID_SLICE].strip()] = record[USRSEC_TYPE_SLICE]
    return decodedTypes


# --------------------------------------------------------------------------- #
# Tests (snake_case function names -- the documented pytest discovery exception
# to the Ochs Rule). Each test is small, focused, and carries a comment naming
# the loader + ASCII fixture (+ copybook) it reconciles against.
# --------------------------------------------------------------------------- #
def test_load_tran_types_count_and_values(db_session, data_dir):
    # Reconciles LoadTranTypes <-> app/data/ASCII/trantype.txt (CVTRA03Y).
    rowsProcessed = LoadTranTypes(db_session, data_dir)
    assert rowsProcessed == EXPECTED_TRAN_TYPE_COUNT
    assert _CountRows(db_session, TransactionType) == EXPECTED_TRAN_TYPE_COUNT

    tranType = db_session.get(TransactionType, "01")
    assert tranType is not None
    # Description attribute is ``tran_type_desc`` (NOT ``description``); the "01"
    # transaction type is "Purchase".
    assert tranType.tran_type_desc.startswith("Purchase")


def test_load_tran_categories_count_and_values(db_session, data_dir):
    # Reconciles LoadTranCategories <-> app/data/ASCII/trancatg.txt (CVTRA04Y).
    rowsProcessed = LoadTranCategories(db_session, data_dir)
    assert rowsProcessed == EXPECTED_TRAN_CATEGORY_COUNT
    assert _CountRows(db_session, TransactionCategory) == EXPECTED_TRAN_CATEGORY_COUNT

    # Composite key (tran_type_cd="01", tran_cat_cd="0001") -> Regular Sales Draft.
    category = _GetTranCategory(db_session, "01", "0001")
    assert category.tran_cat_type_desc.startswith("Regular Sales Draft")


def test_load_disclosure_groups_count_rates_and_groups(db_session, data_dir):
    # Reconciles LoadDisclosureGroups <-> app/data/ASCII/discgrp.txt (CVTRA02Y).
    # DIS-INT-RATE is S9(04)V99 -> NUMERIC(6,2) (AAP 0.7 Finding #2).
    rowsProcessed = LoadDisclosureGroups(db_session, data_dir)
    assert rowsProcessed == EXPECTED_DISCLOSURE_GROUP_COUNT
    assert _CountRows(db_session, DisclosureGroup) == EXPECTED_DISCLOSURE_GROUP_COUNT

    # Exact rate: zoned-decimal "00150{" decodes to +15.00.
    discRow = _GetDisclosureGroup(db_session, "A000000000", "01", "0001")
    assert discRow.interest_rate == Decimal("15.00")
    assert isinstance(discRow.interest_rate, Decimal)

    # The seed defines exactly three (space-stripped) disclosure group ids.
    groupIds = set(
        db_session.execute(select(DisclosureGroup.group_id).distinct()).scalars().all()
    )
    assert groupIds == {"A000000000", "DEFAULT", "ZEROAPR"}

    # The ZEROAPR group carries a zero rate ("00000{" -> 0.00).
    zeroAprRow = _GetDisclosureGroup(db_session, "ZEROAPR", "01", "0001")
    assert zeroAprRow.interest_rate == Decimal("0.00")


def test_load_customers_count_and_leading_zeros(db_session, data_dir):
    # Reconciles LoadCustomers <-> app/data/ASCII/custdata.txt (CVCUS01Y).
    rowsProcessed = LoadCustomers(db_session, data_dir)
    assert rowsProcessed == EXPECTED_CUSTOMER_COUNT
    assert _CountRows(db_session, Customer) == EXPECTED_CUSTOMER_COUNT

    # The 9-char CUST-ID must survive as a leading-zero string, not an integer.
    customer = db_session.get(Customer, "000000001")
    assert customer is not None
    assert customer.first_name.startswith("Immanuel")
    assert customer.last_name.startswith("Kessler")


def test_load_accounts_count_values_and_group_quirk(db_session, data_dir):
    # Reconciles LoadAccounts <-> app/data/ASCII/acctdata.txt (CVACT01Y). Money
    # fields are signed zoned decimal (AAP 0.7.1), decoded to exact Decimal.
    rowsProcessed = LoadAccounts(db_session, data_dir)
    assert rowsProcessed == EXPECTED_ACCOUNT_COUNT
    assert _CountRows(db_session, Account) == EXPECTED_ACCOUNT_COUNT

    # The 11-char ACCT-ID primary key is preserved with its leading zeros.
    account = db_session.get(Account, "00000000001")
    assert account is not None

    # Exact Decimal money values compared for exact equality (never floating
    # point, never a fuzzy/tolerance comparison).
    assert account.curr_bal == Decimal("194.00")
    assert account.credit_limit == Decimal("2020.00")
    assert account.cash_credit_limit == Decimal("1020.00")
    assert account.curr_cyc_credit == Decimal("0.00")
    assert account.curr_cyc_debit == Decimal("0.00")
    assert isinstance(account.curr_bal, Decimal)

    assert account.active_status == "Y"

    assert account.open_date == date(2014, 11, 20)
    assert account.expiration_date == date(2025, 5, 20)
    assert account.reissue_date == date(2025, 5, 20)

    # LEGACY QUIRK -- do NOT "fix" this. In every seed row the literal
    # "A000000000" occupies the copybook byte range that the loader maps to
    # ACCT-ADDR-ZIP, while the ACCT-GROUP-ID field is blank and is therefore
    # stored as NULL. So ``addr_zip`` carries the "A000000000" text and
    # ``group_id`` is None (asserting group_id == "A000000000" is the
    # pre-verification misconception the loader spec explicitly corrects).
    assert account.addr_zip == "A000000000"
    assert account.group_id is None


def test_load_cards_count_and_values(db_session, data_dir):
    # Reconciles LoadCards <-> app/data/ASCII/carddata.txt (CVACT02Y). Card has a
    # FK acct_id -> accounts, so accounts are loaded first (no assertion needed).
    LoadAccounts(db_session, data_dir)

    rowsProcessed = LoadCards(db_session, data_dir)
    assert rowsProcessed == EXPECTED_CARD_COUNT
    assert _CountRows(db_session, Card) == EXPECTED_CARD_COUNT

    # The leading-zero 16-char card_num ("05...") is preserved as a string.
    card = db_session.get(Card, "0500024453765740")
    assert card is not None
    assert card.acct_id == "00000000050"
    # CVV is never persisted (C-03, AAP 0.7.8): the loader must not populate a
    # cvv column and the ORM model must not expose one, even though the source
    # seed record carries a CVV field at columns 27-30.
    assert not hasattr(card, "cvv_cd")
    assert card.active_status == "Y"


def test_load_card_xref_count_and_values(db_session, data_dir):
    # Reconciles LoadCardXref <-> app/data/ASCII/cardxref.txt (CVACT03Y, 36-byte
    # records). FKs -> cards/customers/accounts, so those are loaded first.
    LoadCustomers(db_session, data_dir)
    LoadAccounts(db_session, data_dir)
    LoadCards(db_session, data_dir)

    rowsProcessed = LoadCardXref(db_session, data_dir)
    assert rowsProcessed == EXPECTED_CARD_XREF_COUNT
    assert _CountRows(db_session, CardXref) == EXPECTED_CARD_XREF_COUNT

    # The single-column PK is ``xref_card_num`` (exposed via the ``card_num``
    # synonym); ``session.get`` looks it up by that primary key.
    xref = db_session.get(CardXref, "0500024453765740")
    assert xref is not None
    assert xref.cust_id == "000000050"
    assert xref.acct_id == "00000000050"


def test_load_tran_category_balances_count_and_value(db_session, data_dir):
    # Reconciles LoadTranCategoryBalances <-> app/data/ASCII/tcatbal.txt
    # (CVTRA01Y).
    rowsProcessed = LoadTranCategoryBalances(db_session, data_dir)
    assert rowsProcessed == EXPECTED_TCATBAL_COUNT
    assert _CountRows(db_session, TranCategoryBalance) == EXPECTED_TCATBAL_COUNT

    # Composite key (acct_id, tran_type_cd, tran_cat_cd); the seed balance is
    # zero ("0000000000{" -> 0.00).
    balanceRow = _GetTranCategoryBalance(db_session, "00000000001", "01", "0001")
    assert balanceRow.balance == Decimal("0.00")
    assert isinstance(balanceRow.balance, Decimal)


def test_load_transactions_count_and_pending_status(db_session, data_dir):
    # Reconciles LoadTransactions <-> app/data/ASCII/dailytran.txt (CVTRA06Y).
    # Transaction has a FK card_num -> cards, so accounts+cards load first.
    LoadAccounts(db_session, data_dir)
    LoadCards(db_session, data_dir)

    rowsProcessed = LoadTransactions(db_session, data_dir)
    assert rowsProcessed == EXPECTED_TRANSACTION_COUNT
    assert _CountRows(db_session, Transaction) == EXPECTED_TRANSACTION_COUNT

    # Every loaded daily-transaction row starts in the PENDING staging state.
    pendingCount = db_session.execute(
        select(func.count())
        .select_from(Transaction)
        .where(Transaction.status == STATUS_PENDING)
    ).scalar_one()
    assert pendingCount == EXPECTED_TRANSACTION_COUNT

    tran = db_session.get(Transaction, "0000000000683580")
    assert tran is not None
    # TRAN-AMT S9(09)V99 zoned decimal "0000005047G" -> +504.77 (G = +7 overpunch).
    assert tran.tran_amt == Decimal("504.77")
    assert isinstance(tran.tran_amt, Decimal)
    assert tran.card_num == "4859452612877065"
    # The merchant id attribute is ``merchant_id`` (no ``tran_`` prefix).
    assert tran.merchant_id == "800000000"
    assert tran.tran_type_cd == "01"
    # The daily seed carries no processing timestamp yet, so proc_ts is NULL.
    assert tran.proc_ts is None
    assert tran.status == STATUS_PENDING


def test_loader_idempotency(db_session, data_dir):
    # Reconciles the legacy DELETE/DEFINE/REPRO re-runnable load semantics via the
    # ON CONFLICT upsert, using the no-FK LoadTranTypes (trantype.txt / CVTRA03Y).
    firstCount = LoadTranTypes(db_session, data_dir)
    assert firstCount == EXPECTED_TRAN_TYPE_COUNT

    secondCount = LoadTranTypes(db_session, data_dir)
    assert secondCount == EXPECTED_TRAN_TYPE_COUNT

    # The second run upserts the same primary keys, so no duplicate rows are
    # appended -- the table still holds exactly the seed count.
    assert _CountRows(db_session, TransactionType) == EXPECTED_TRAN_TYPE_COUNT


def test_load_accounts_preserves_ledger_balances_on_rerun(db_session, data_dir):
    # F-6 (idempotent chain rerun, AAP 0.7.6): a second LoadAccounts must NOT
    # reset the ledger-owned running balances that the posting and interest jobs
    # mutate after the seed load. The loader excludes LEDGER_OWNED_COLUMNS
    # (curr_bal, curr_cyc_credit, curr_cyc_debit) from its ON CONFLICT update set
    # while still refreshing every STATIC column from the seed. Reconciles
    # LoadAccounts <-> app/data/ASCII/acctdata.txt (CVACT01Y).
    MUTATED_CURR_BAL = Decimal("99999.99")     # sentinel != seed 194.00
    MUTATED_CYC_CREDIT = Decimal("1234.56")    # sentinel != seed 0.00
    MUTATED_CYC_DEBIT = Decimal("789.01")      # sentinel != seed 0.00
    DRIFTED_ACTIVE_STATUS = "N"                # static col: seed value is "Y"

    firstCount = LoadAccounts(db_session, data_dir)
    assert firstCount == EXPECTED_ACCOUNT_COUNT

    # Simulate posting/interest having moved the ledger since the seed load, and
    # drift a STATIC column so the refresh half of the contract is also proven.
    account = db_session.get(Account, "00000000001")
    assert account.curr_bal == Decimal("194.00")     # seed baseline
    assert account.active_status == "Y"              # seed baseline
    account.curr_bal = MUTATED_CURR_BAL
    account.curr_cyc_credit = MUTATED_CYC_CREDIT
    account.curr_cyc_debit = MUTATED_CYC_DEBIT
    account.active_status = DRIFTED_ACTIVE_STATUS
    db_session.flush()

    secondCount = LoadAccounts(db_session, data_dir)
    assert secondCount == EXPECTED_ACCOUNT_COUNT
    # No duplicate rows: the re-run upserts the same 50 primary keys.
    assert _CountRows(db_session, Account) == EXPECTED_ACCOUNT_COUNT

    # The loader's Core upsert bypasses the ORM identity map, so force a fresh
    # read from the database before asserting the post-rerun state.
    db_session.expire_all()
    reloaded = db_session.get(Account, "00000000001")

    # Ledger-owned columns are PRESERVED (never reset to the seed values) ...
    assert reloaded.curr_bal == MUTATED_CURR_BAL
    assert reloaded.curr_cyc_credit == MUTATED_CYC_CREDIT
    assert reloaded.curr_cyc_debit == MUTATED_CYC_DEBIT
    assert isinstance(reloaded.curr_bal, Decimal)
    # ... while every STATIC column is still refreshed back to the seed value.
    assert reloaded.active_status == "Y"
    assert reloaded.credit_limit == Decimal("2020.00")


def test_load_tran_category_balances_preserve_balance_on_rerun(db_session, data_dir):
    # F-6 (idempotent chain rerun, AAP 0.7.6): a second
    # LoadTranCategoryBalances must NOT reset the ledger-owned ``balance`` that
    # the posting job accrues after the seed load. ``balance`` is the ONLY
    # non-key column, so excluding it (LEDGER_OWNED_COLUMNS) makes the upsert
    # fall through to ON CONFLICT DO NOTHING, leaving the existing row untouched.
    # Reconciles LoadTranCategoryBalances <-> app/data/ASCII/tcatbal.txt
    # (CVTRA01Y).
    MUTATED_BALANCE = Decimal("4242.42")       # sentinel != seed 0.00

    firstCount = LoadTranCategoryBalances(db_session, data_dir)
    assert firstCount == EXPECTED_TCATBAL_COUNT

    # Simulate posting having accrued a category balance since the seed load.
    balanceRow = _GetTranCategoryBalance(db_session, "00000000001", "01", "0001")
    assert balanceRow.balance == Decimal("0.00")     # seed baseline
    balanceRow.balance = MUTATED_BALANCE
    db_session.flush()

    secondCount = LoadTranCategoryBalances(db_session, data_dir)
    assert secondCount == EXPECTED_TCATBAL_COUNT
    # No duplicate rows: the re-run upserts the same 50 composite keys.
    assert _CountRows(db_session, TranCategoryBalance) == EXPECTED_TCATBAL_COUNT

    # Force a fresh read past the ORM identity map, then confirm the accrued
    # balance survived the re-load unchanged (never reset to the seed 0.00).
    db_session.expire_all()
    reloaded = _GetTranCategoryBalance(db_session, "00000000001", "01", "0001")
    assert reloaded.balance == MUTATED_BALANCE
    assert isinstance(reloaded.balance, Decimal)


def test_init_users_ebcdic_count_types_and_password_hashing(db_session, usrsec_path):
    # QA M-17: the golden suites previously OMITTED the EBCDIC USRSEC users
    # entirely. This reconciles InitializeUsers <-> AWS.M2.CARDDEMO.USRSEC.PS
    # (CSUSR01Y / DUSRSECJ.jcl): all ten operators load with the correct
    # user_type roles decoded INDEPENDENTLY (stdlib cp037, own slicing), and the
    # plaintext SEC-USR-PWD is HASHED (bcrypt round-trips) and never stored in
    # cleartext (Ochs no-hardcoded-secrets + AAP 0.7.8).
    rowsProcessed = InitializeUsers(db_session, usrsec_path)
    assert rowsProcessed == EXPECTED_USER_COUNT
    assert _CountRows(db_session, User) == EXPECTED_USER_COUNT

    # Independent EBCDIC decode is the reference the loader is checked against.
    independentTypes = _DecodeUsrsecTypes(usrsec_path)
    assert independentTypes == EXPECTED_USER_TYPES

    for userId, expectedType in independentTypes.items():
        loadedUser = db_session.get(User, userId)
        assert loadedUser is not None, userId
        # Role parity with the independent decode (5 admins 'A', 5 regular 'U').
        assert loadedUser.user_type == expectedType, userId
        # Password is stored HASHED: bcrypt verifies the seed plaintext, but the
        # stored value is never the plaintext itself (no cleartext leakage).
        assert loadedUser.password_hash != SEED_PLAINTEXT_PASSWORD, userId
        assert SEED_PLAINTEXT_PASSWORD not in loadedUser.password_hash, userId
        assert VerifyPassword(SEED_PLAINTEXT_PASSWORD, loadedUser.password_hash), userId


def test_validate_account_id_rejects_non_numeric_and_wrong_length():
    # QA finding F-5 (defense-in-depth): the ACCT-ID key is PIC 9(11) -- exactly
    # 11 ASCII digits. _ValidateAccountId must ACCEPT a canonical 11-digit key
    # (leading zeros preserved) and REJECT everything else: a path-traversal
    # string, an embedded non-digit, a too-short and a too-long value, and an
    # 11-character non-ASCII Unicode-digit string (which ``str.isdigit`` alone
    # would wrongly accept, hence the paired ``str.isascii`` guard).
    _ValidateAccountId("0" * ACCT_ID_LENGTH, 1)     # canonical zeros -> no raise
    _ValidateAccountId("00000000001", 1)            # leading zeros    -> no raise

    rejectedAcctIds = (
        "../../../XY",       # 11 chars, path-traversal payload (non-numeric)
        "0000000000A",       # 11 chars, embedded non-digit
        "1234567890",        # 10 chars, too short
        "123456789012",      # 12 chars, too long
        "\u0660\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669\u0660",
    )                        # 11 Arabic-Indic digits: isdigit True, isascii False
    for badAcctId in rejectedAcctIds:
        with pytest.raises(ValueError, match="ACCT-ID must be exactly"):
            _ValidateAccountId(badAcctId, 7)


def test_load_accounts_rejects_crafted_non_numeric_acct_id(
    db_session, data_dir, tmp_path
):
    # QA finding F-5: a crafted seed record whose ACCT-ID carries path separators
    # ("../../../XY") must be REJECTED at load with a specific ValueError and
    # never stored verbatim, so a malformed key can never reach a data-derived
    # consumer (e.g. a statement file name). Reconciles the ACCTFILE load path
    # (LoadAccounts <-> a crafted 300-byte CVACT01Y record spliced from real seed
    # bytes so the ONLY defect is the account id).
    realRecord = (
        (data_dir / SEED_FILE_NAME).read_text(encoding="latin-1").splitlines()[0]
    )
    assert len(realRecord) == RECORD_LENGTH          # sanity: real 300-byte image
    craftedAcctId = "../../../XY"                     # 11 chars keeps length at 300
    assert len(craftedAcctId) == ACCT_ID_LENGTH
    craftedRecord = craftedAcctId + realRecord[ACCT_ID_LENGTH:]
    assert len(craftedRecord) == RECORD_LENGTH
    (tmp_path / SEED_FILE_NAME).write_text(craftedRecord + "\n", encoding="latin-1")

    with pytest.raises(ValueError, match="ACCT-ID must be exactly"):
        LoadAccounts(db_session, tmp_path)

    # The malformed key was rejected BEFORE the upsert -- nothing was persisted.
    assert _CountRows(db_session, Account) == 0
