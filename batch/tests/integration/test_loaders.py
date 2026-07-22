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

from datetime import date
from decimal import Decimal

from sqlalchemy import func, select

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
)
from batch.loaders.load_accounts import LoadAccounts
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
    assert card.cvv_cd == "747"
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
