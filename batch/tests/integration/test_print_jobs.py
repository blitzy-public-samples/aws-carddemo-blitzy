# DB-backed integration tests for the five read-only CardDemo batch reader jobs,
# each reconciled against the legacy COBOL program it ports (Ochs Rule: every
# ported unit references its origin). These close QA finding F-03 (six in-scope
# batch jobs at 0.0% coverage) for five of the six programs; the sixth
# (CBTRN03C transaction detail report) is covered by test_tran_detail_report.py.
#
#   * batch.jobs.print_account.PrintAccounts       <- app/cbl/CBACT01C.cbl
#   * batch.jobs.print_card.PrintCards             <- app/cbl/CBACT02C.cbl
#   * batch.jobs.print_xref.PrintCardXref          <- app/cbl/CBACT03C.cbl
#   * batch.jobs.print_customer.PrintCustomers     <- app/cbl/CBCUS01C.cbl
#   * batch.jobs.read_daily_tran.ReadDailyTransactions <- app/cbl/CBTRN01C.cbl
#
# Every test runs the job against a real PostgreSQL 17 test database using the
# shared, rolled-back synchronous ``db_session`` / ``seeded_db`` / ``record_builder``
# fixtures provided by the batch conftests. Assertions cover the golden-master
# row counts, the START/END execution banners, the sensitive-data masking rules
# (AAP 0.7.8: PAN masked to its last four digits, SSN masked to its last four,
# CVV never emitted), and the diagnostic control flow of CBTRN01C's cross-
# reference / account lookups.
#
# The legacy app/ tree is REFERENCE-only and is never modified. Synchronous
# only: the batch layer is blocking (psycopg2); this module imports no async
# machinery.
"""Integration tests for the CardDemo read-only batch reader jobs (F-03).

These exercise the five reader/report-reader jobs end to end against seeded
PostgreSQL so that a regression in any of them -- a wrong record count, a
dropped banner, a broken decode, or (critically) an unmasked card number or
Social Security Number -- is caught by an executing test rather than shipping
undetected.

Coding conventions (AAP 0.8.2/0.8.3 Ochs Rule): helper callables use PascalCase,
local variables use camelCase, module constants use ALL_UPPERCASE, and test
function names plus pytest fixture names stay snake_case (the documented pytest
framework-contract exception).
"""

import logging
from decimal import Decimal

import pytest
from sqlalchemy import func, select
from sqlalchemy.exc import OperationalError, ProgrammingError

from app.models import Account, Transaction
from batch.jobs.print_account import PrintAccounts
from batch.jobs.print_card import PrintCards
from batch.jobs.print_customer import PrintCustomers
from batch.jobs.print_xref import PrintCardXref
from batch.jobs.read_daily_tran import ReadDailyTransactions

# --------------------------------------------------------------------------- #
# Golden-master row counts (the ``wc -l`` of each app/data/ASCII seed file).
# Mirrors batch/tests/integration/test_loaders.py so the reader counts reconcile
# against the loader counts.
# --------------------------------------------------------------------------- #
EXPECTED_ACCOUNT_COUNT = 50
EXPECTED_CARD_COUNT = 50
EXPECTED_CARD_XREF_COUNT = 50
EXPECTED_CUSTOMER_COUNT = 50
EXPECTED_TRANSACTION_COUNT = 300

# --------------------------------------------------------------------------- #
# Concrete seed values verified from app/data/ASCII (used to assert masking on
# real seed rows -- the full sensitive value must never appear in output while
# its masked form must).
# --------------------------------------------------------------------------- #
SEED_CARD_NUM = "0500024453765740"          # carddata.txt / cardxref.txt PK
SEED_CARD_LAST4 = "5740"
SEED_CARD_MASKED_PRINT = "************5740"  # 12 mask chars + last four (print jobs)
SEED_XREF_CUST_ID = "000000050"
SEED_XREF_ACCT_ID = "00000000050"
SEED_CUST_ID = "000000001"
SEED_CUST_FULL_SSN = "020973888"
SEED_CUST_SSN_MASKED = "***-**-3888"
SEED_CUST_SSN_MASK_PREFIX = "***-**-"  # emitted only when an SSN is on file

# Obviously-fake, self-contained values for the controlled record_builder
# scenarios (never real card numbers or SSNs).
FAKE_CARD_NUM = "4111111111111111"
FAKE_CARD_LAST4 = "1111"
FAKE_CARD_MASKED_PRINT = "************1111"
FAKE_CARD_MASKED_DALY = "****1111"
FAKE_ACCT_ID = "00000000009"
FAKE_CUST_ID = "000000009"
FAKE_SSN = "123456789"
FAKE_SSN_MASKED = "***-**-6789"
MISSING_ACCT_ID = "00000000077"
FAKE_TRAN_ID = "0000000000000001"

# Execution banners emitted by each ported program (verbatim for log parity).
BANNER_START_ACCOUNT = "START OF EXECUTION OF PROGRAM CBACT01C"
BANNER_END_ACCOUNT = "END OF EXECUTION OF PROGRAM CBACT01C"
BANNER_START_CARD = "START OF EXECUTION OF PROGRAM CBACT02C"
BANNER_START_XREF = "START OF EXECUTION OF PROGRAM CBACT03C"
BANNER_END_XREF = "END OF EXECUTION OF PROGRAM CBACT03C"
BANNER_START_CUSTOMER = "START OF EXECUTION OF PROGRAM CBCUS01C"
BANNER_END_CUSTOMER = "END OF EXECUTION OF PROGRAM CBCUS01C"
BANNER_START_DALY = "START OF EXECUTION OF PROGRAM CBTRN01C"
BANNER_END_DALY = "END OF EXECUTION OF PROGRAM CBTRN01C"

# The description prefix CBACT01C prints before each account id (verbatim label).
ACCT_ID_LABEL_PREFIX = "ACCT-ID"


# --------------------------------------------------------------------------- #
# Module-level helpers (Ochs PascalCase; <= 4 parameters each).
# --------------------------------------------------------------------------- #
def _CountRows(session, model):
    """Return the number of rows of ``model`` visible in the test transaction.

    Args:
        session: The open, rolled-back synchronous test session.
        model: A mapped ORM class whose table rows are counted.

    Returns:
        The current row count as an ``int``.
    """
    return session.execute(select(func.count()).select_from(model)).scalar_one()


def _BuildLinkedCard(recordBuilder, cardNum, acctId=FAKE_ACCT_ID):
    """Create one account and one card on it, returning the card row.

    Args:
        recordBuilder: The ``RecordBuilder`` bound to the rolled-back session.
        cardNum: The 16-character card number to create.
        acctId: The account id to own the card (created if needed).

    Returns:
        The created :class:`~app.models.card.Card` row.
    """
    recordBuilder.BuildAccount(acctId)
    return recordBuilder.BuildCard(cardNum, acctId)


# =========================================================================== #
# CBACT01C -> batch.jobs.print_account.PrintAccounts
# =========================================================================== #
def test_print_accounts_returns_seeded_count_with_banners(seeded_db, caplog):
    # CBACT01C: sequential ACCTFILE read; count == golden-master account count,
    # and the START/END DISPLAY banners bracket the run.
    with caplog.at_level(logging.INFO):
        printedCount = PrintAccounts(seeded_db)
    assert printedCount == EXPECTED_ACCOUNT_COUNT
    assert _CountRows(seeded_db, Account) == EXPECTED_ACCOUNT_COUNT
    assert BANNER_START_ACCOUNT in caplog.text
    assert BANNER_END_ACCOUNT in caplog.text
    # A known seeded account id is printed with the verbatim ACCT-ID label.
    assert ACCT_ID_LABEL_PREFIX in caplog.text
    assert "00000000001" in caplog.text


def test_print_accounts_empty_table_returns_zero(db_session, caplog):
    # CBACT01C on an empty ACCTFILE: zero records, banners still emitted.
    with caplog.at_level(logging.INFO):
        printedCount = PrintAccounts(db_session)
    assert printedCount == 0
    assert BANNER_START_ACCOUNT in caplog.text
    assert BANNER_END_ACCOUNT in caplog.text


def test_print_accounts_orders_by_ascending_acct_id(record_builder, db_session, caplog):
    # CBACT01C reproduces VSAM KSDS ascending-key order: build out of order and
    # assert the first ACCT-ID line emitted is the numerically-lowest account.
    record_builder.BuildAccount("00000000030")
    record_builder.BuildAccount("00000000010")
    record_builder.BuildAccount("00000000020")
    with caplog.at_level(logging.INFO):
        printedCount = PrintAccounts(db_session)
    assert printedCount == 3
    acctLines = [rec.getMessage() for rec in caplog.records if ACCT_ID_LABEL_PREFIX in rec.getMessage()]
    assert acctLines, "expected at least one ACCT-ID line"
    assert acctLines[0].strip().endswith("00000000010")


# =========================================================================== #
# CBACT02C -> batch.jobs.print_card.PrintCards
# =========================================================================== #
def test_print_cards_returns_seeded_count(seeded_db, capsys):
    # CBACT02C: sequential CARDFILE read; count == golden-master card count.
    printedCount = PrintCards(seeded_db)
    assert printedCount == EXPECTED_CARD_COUNT
    captured = capsys.readouterr()
    # The masked form of a known seed card is present; the full PAN never is.
    assert SEED_CARD_NUM not in captured.out
    assert SEED_CARD_MASKED_PRINT in captured.out


def test_print_cards_masks_pan_and_never_emits_cvv(record_builder, db_session, capsys):
    # AAP 0.7.8: the PAN is masked to its last four digits and the CVV is never
    # emitted in any form (the ORM row does not even expose a cvv attribute).
    card = _BuildLinkedCard(record_builder, FAKE_CARD_NUM)
    assert not hasattr(card, "cvv_cd")
    printedCount = PrintCards(db_session)
    assert printedCount == 1
    captured = capsys.readouterr()
    assert FAKE_CARD_NUM not in captured.out
    assert FAKE_CARD_MASKED_PRINT in captured.out
    assert "CVV" not in captured.out.upper()


def test_print_cards_empty_table_returns_zero(db_session, capsys):
    # CBACT02C on an empty CARDFILE prints no data rows and returns zero.
    printedCount = PrintCards(db_session)
    assert printedCount == 0
    captured = capsys.readouterr()
    assert SEED_CARD_MASKED_PRINT not in captured.out


# =========================================================================== #
# CBACT03C -> batch.jobs.print_xref.PrintCardXref
# =========================================================================== #
def test_print_xref_returns_seeded_count_with_banners(seeded_db, capsys, caplog):
    # CBACT03C: sequential XREFFILE read; count == golden-master xref count; the
    # START/END banners bracket the run.
    with caplog.at_level(logging.INFO):
        printedCount = PrintCardXref(seeded_db)
    assert printedCount == EXPECTED_CARD_XREF_COUNT
    assert BANNER_START_XREF in caplog.text
    assert BANNER_END_XREF in caplog.text
    captured = capsys.readouterr()
    # Masked card present, full PAN absent; the (non-sensitive) ids show in full.
    assert SEED_CARD_NUM not in captured.out
    assert SEED_CARD_MASKED_PRINT in captured.out
    assert SEED_XREF_CUST_ID in captured.out
    assert SEED_XREF_ACCT_ID in captured.out


def test_print_xref_masks_pan_shows_ids(record_builder, db_session, capsys):
    # A controlled single xref row: PAN masked, customer + account ids in full.
    record_builder.BuildCustomer(FAKE_CUST_ID)
    _BuildLinkedCard(record_builder, FAKE_CARD_NUM)
    record_builder.BuildXref(FAKE_CARD_NUM, FAKE_CUST_ID, FAKE_ACCT_ID)
    printedCount = PrintCardXref(db_session)
    assert printedCount == 1
    captured = capsys.readouterr()
    assert FAKE_CARD_NUM not in captured.out
    assert FAKE_CARD_MASKED_PRINT in captured.out
    assert FAKE_CUST_ID in captured.out
    assert FAKE_ACCT_ID in captured.out


# =========================================================================== #
# CBCUS01C -> batch.jobs.print_customer.PrintCustomers
# =========================================================================== #
def test_print_customers_returns_seeded_count_and_masks_ssn(seeded_db, caplog):
    # CBCUS01C: sequential CUSTFILE read; count == golden-master customer count;
    # a known seed SSN is masked to its last four digits and never shown in full.
    with caplog.at_level(logging.INFO):
        printedCount = PrintCustomers(seeded_db)
    assert printedCount == EXPECTED_CUSTOMER_COUNT
    assert BANNER_START_CUSTOMER in caplog.text
    assert BANNER_END_CUSTOMER in caplog.text
    assert SEED_CUST_ID in caplog.text
    assert SEED_CUST_FULL_SSN not in caplog.text
    assert SEED_CUST_SSN_MASKED in caplog.text


def test_print_customers_masks_controlled_ssn(record_builder, db_session, caplog):
    # A controlled customer with a fabricated SSN: only the last four digits are
    # ever surfaced, in the ***-**-#### form.
    record_builder.BuildCustomer(FAKE_CUST_ID, overrides={"ssn": FAKE_SSN})
    with caplog.at_level(logging.INFO):
        printedCount = PrintCustomers(db_session)
    assert printedCount == 1
    assert FAKE_SSN not in caplog.text
    assert FAKE_SSN_MASKED in caplog.text


def test_print_customers_none_ssn_masks_to_empty(record_builder, db_session, caplog):
    # CBCUS01C for a customer with no SSN on file (the nullable ssn column is
    # None): the masked SSN is the empty string -- no mask prefix and no digits
    # are ever emitted -- while the record is still printed and counted.
    record_builder.BuildCustomer(FAKE_CUST_ID, overrides={"ssn": None})
    with caplog.at_level(logging.INFO):
        printedCount = PrintCustomers(db_session)
    assert printedCount == 1
    assert FAKE_CUST_ID in caplog.text
    assert SEED_CUST_SSN_MASK_PREFIX not in caplog.text


def test_print_customers_empty_table_returns_zero(db_session, caplog):
    # CBCUS01C on an empty CUSTFILE returns zero with banners intact.
    with caplog.at_level(logging.INFO):
        printedCount = PrintCustomers(db_session)
    assert printedCount == 0
    assert BANNER_START_CUSTOMER in caplog.text
    assert BANNER_END_CUSTOMER in caplog.text


# =========================================================================== #
# CBTRN01C -> batch.jobs.read_daily_tran.ReadDailyTransactions
# =========================================================================== #
def test_read_daily_tran_counts_pending_seeded(seeded_db, caplog):
    # CBTRN01C: reads every PENDING daily transaction; on the golden-master seed
    # all 300 daily rows load PENDING, so the count is exactly 300.
    seededPending = seeded_db.execute(
        select(func.count()).select_from(Transaction).where(Transaction.status == "PENDING")
    ).scalar_one()
    assert seededPending == EXPECTED_TRANSACTION_COUNT
    with caplog.at_level(logging.INFO):
        readCount = ReadDailyTransactions(seeded_db)
    assert readCount == EXPECTED_TRANSACTION_COUNT
    assert BANNER_START_DALY in caplog.text
    assert BANNER_END_DALY in caplog.text


def test_read_daily_tran_reports_successful_lookups(record_builder, db_session, caplog):
    # CBTRN01C happy path: a PENDING transaction whose card resolves to an xref
    # and an existing account logs both successful reads; the PAN is masked.
    record_builder.BuildCustomer(FAKE_CUST_ID)
    _BuildLinkedCard(record_builder, FAKE_CARD_NUM)
    record_builder.BuildXref(FAKE_CARD_NUM, FAKE_CUST_ID, FAKE_ACCT_ID)
    record_builder.BuildPendingTransaction(FAKE_TRAN_ID, FAKE_CARD_NUM, Decimal("12.34"))
    with caplog.at_level(logging.INFO):
        readCount = ReadDailyTransactions(db_session)
    assert readCount == 1
    assert "SUCCESSFUL READ OF XREF" in caplog.text
    assert "SUCCESSFUL READ OF ACCOUNT FILE" in caplog.text
    # The card number is masked in the diagnostics; the full PAN never appears.
    assert FAKE_CARD_NUM not in caplog.text
    assert FAKE_CARD_MASKED_DALY in caplog.text


def test_read_daily_tran_missing_xref_is_skipped(record_builder, db_session, caplog):
    # CBTRN01C: a PENDING transaction on a card with NO cross-reference row logs
    # the invalid-card diagnostic and is skipped, but is still counted as read.
    _BuildLinkedCard(record_builder, FAKE_CARD_NUM)
    record_builder.BuildPendingTransaction(FAKE_TRAN_ID, FAKE_CARD_NUM, Decimal("5.00"))
    with caplog.at_level(logging.INFO):
        readCount = ReadDailyTransactions(db_session)
    assert readCount == 1
    assert "INVALID CARD NUMBER FOR XREF" in caplog.text
    assert "SKIPPING TRANSACTION" in caplog.text
    assert FAKE_CARD_NUM not in caplog.text


def test_read_daily_tran_xref_hit_but_account_missing(
    record_builder, db_session, caplog, relax_foreign_keys
):
    # CBTRN01C: a cross-reference that resolves but points at a MISSING account
    # logs the invalid-account diagnostic (the account read fails). A dangling
    # xref is inserted with the card_xref FK triggers temporarily relaxed on the
    # disposable test schema so this referential-integrity branch can be
    # exercised, mirroring the proven pattern in test_post_transactions.py.
    record_builder.BuildCustomer(FAKE_CUST_ID)
    _BuildLinkedCard(record_builder, FAKE_CARD_NUM)
    record_builder.BuildPendingTransaction(FAKE_TRAN_ID, FAKE_CARD_NUM, Decimal("7.00"))

    # Insert the dangling xref inside a SAVEPOINT so that, if the test-DB role
    # lacks the privilege to DISABLE TRIGGER, the SAVEPOINT is rolled back and
    # the test skips cleanly rather than poisoning the shared session.
    savepoint = db_session.begin_nested()
    try:
        with relax_foreign_keys(db_session, "card_xref"):
            # Resolved-but-dangling xref: it exists (so the xref lookup
            # succeeds) yet points at an account that does not exist (so the
            # subsequent account lookup misses and the diagnostic fires).
            record_builder.BuildXref(FAKE_CARD_NUM, FAKE_CUST_ID, MISSING_ACCT_ID)
    except (ProgrammingError, OperationalError):
        savepoint.rollback()
        pytest.skip("DISABLE TRIGGER requires table ownership/superuser")

    with caplog.at_level(logging.INFO):
        readCount = ReadDailyTransactions(db_session)
    assert readCount == 1
    assert "SUCCESSFUL READ OF XREF" in caplog.text
    assert "INVALID ACCOUNT NUMBER FOUND" in caplog.text
    assert MISSING_ACCT_ID in caplog.text
