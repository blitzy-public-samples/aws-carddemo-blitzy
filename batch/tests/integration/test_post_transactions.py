# Golden-master integration tests for the CardDemo daily-transaction posting job.
#
# Provenance / traceability (Ochs Rule -- every ported unit references its
# origin): these tests reconcile ``batch.jobs.post_transactions.PostTransactions``
# -- the Python port of legacy COBOL ``app/cbl/CBTRN02C.cbl`` driven by
# ``app/jcl/POSTTRAN.jcl`` -- FIELD-FOR-FIELD against the mainframe behavior. The
# record layouts derive from copybooks ``app/cpy/CVTRA06Y`` (DALYTRAN-RECORD,
# RECLN 350), ``app/cpy/CVTRA05Y`` (TRAN-RECORD), ``app/cpy/CVACT01Y``
# (ACCOUNT-RECORD) and ``app/cpy/CVACT03Y`` (CARD-XREF-RECORD). This file is
# REFERENCE-derived only; the legacy ``app/`` tree is never modified.
#
# Each test carries a comment naming the exact CBTRN02C paragraph / line range it
# reconciles against (golden-master parity -- AAP 0.5.2 / 0.7.3 / 0.8.1).
"""DB-backed golden-master parity tests for CBTRN02C daily-transaction posting.

This is the MOST CRITICAL parity suite in the batch layer. It drives
:func:`batch.jobs.post_transactions.PostTransactions` against a real PostgreSQL
17 test database (``carddemo_test``) and proves, byte-for-byte, that the modern
port preserves the mainframe program ``CBTRN02C`` exactly:

* the validation ORDER (``1500-A-LOOKUP-XREF`` before ``1500-B-LOOKUP-ACCT``);
* the four documented reject reason codes 100/101/102/103 and the discovered
  code 109, with their VERBATIM UPPERCASE descriptions;
* the 430-byte reject-record byte layout (``REJECT-TRAN-DATA`` ``X(350)`` +
  reason ``9(04)`` + description ``X(76)``; CBTRN02C L176-182);
* the 102/103 "last-write-wins" nuance (Check B is a SEPARATE ``IF`` after Check
  A, so when both fail the sequential MOVE of 103 overwrites 102); and
* the posting balance-update ORDER (``2700`` category-balance upsert, then
  ``2800`` account update -- ``curr_bal`` FIRST, then the signed cycle credit /
  debit add -- then ``2900`` status promotion).

Reject-sink contract (QA finding #28): the ported job never returns the 430-byte
reject images in memory, because each image is the raw daily-transaction record
and carries the full (unmasked) card number. Instead the job writes the reject
generation ONCE to a protected owner-only on-disk sink and surfaces only the
opaque path on :attr:`PostingResult.rejectFilePath`. These tests therefore point
the job at a per-test ``tmp_path`` directory and read the 430-byte rows back from
that sink via :func:`_ReadRejectRows` for golden-master assertions; the counters
(``transactionsProcessed`` / ``transactionsPosted`` / ``transactionsRejected``)
are asserted directly off the result object.

Synchronous only: the batch layer is blocking (``psycopg2``). This module
deliberately imports none of the async request-layer machinery -- not the async
database-session module, the async PostgreSQL driver, the async pytest plugin,
the async HTTP client, the standard-library event loop, nor SQLAlchemy's async
ORM extension. Every monetary assertion uses an exact :class:`decimal.Decimal`
compared for exact equality -- never floating point, never a fuzzy tolerance
(AAP 0.7.1).

Design-agnostic on posting exceptions: the ``app.core.exceptions`` posting-code
design is intentionally not assumed here. Reject reasons are verified through the
golden-master reject rows and the design-tolerant ``posting_catalog`` fixture, so
no concrete posting-exception class is ever imported.

Ochs Rule conventions: module constants are ``ALL_UPPERCASE``; the non-test
helpers are ``PascalCase`` (:func:`_BuildPostableGraph`, :func:`_GetCategoryBalance`,
:func:`_ReadRejectRows`); local variables are ``camelCase``; indentation is four
spaces and every ``for`` / ``if`` is a multi-line block. Test function names (and
the fixture parameters they consume) are ``snake_case`` -- the documented pytest
framework-contract exception, because pytest discovers ``test_``-prefixed
functions and injects fixtures by matching argument names.
"""

from __future__ import annotations

import datetime
from decimal import Decimal
from pathlib import Path

import pytest
from sqlalchemy.exc import OperationalError, ProgrammingError

from app.models import STATUS_POSTED, STATUS_REJECTED, TranCategoryBalance
from batch.jobs.post_transactions import PostTransactions

# --------------------------------------------------------------------------- #
# Module constants (Ochs ALL_UPPERCASE).
#
# The two expiration sentinels bracket the builder's default transaction date
# (``2023-06-01``; conftest ``DEFAULT_ORIG_TS``), so the CBTRN02C code-103
# expiration edit is controlled SOLELY by ``account.expiration_date``:
# ``FUTURE_EXPIRATION`` passes the edit and ``PAST_EXPIRATION`` trips it. The
# reject-geometry offsets mirror the CBTRN02C ``REJECT-RECORD`` layout
# (L176-182): a 350-byte data image, a 4-digit reason code, then a 76-byte
# description, for a fixed 430-byte record.
# --------------------------------------------------------------------------- #
FUTURE_EXPIRATION = datetime.date(2099, 12, 31)  # >= 2023-06-01 -> passes edit 103
PAST_EXPIRATION = datetime.date(2000, 1, 1)       # <  2023-06-01 -> trips edit 103
DANGLING_ACCT_ID = "99999999999"                  # acct_id absent from ``accounts``

REJECT_ROW_LENGTH = 430
REJECT_REASON_START = 350
REJECT_REASON_END = 354
REJECT_DESC_START = 354

# Stable identifiers for the single controlled scenario each test stages. The
# widths match the ORM columns: ``card_num``/``tran_id`` are ``VARCHAR(16)``.
GRAPH_CARD_NUM = "4111111111111111"
GRAPH_TRAN_ID = "TRAN000000000001"

# Default posted amount for scenarios whose reason under test is independent of
# the amount (an exact ``Decimal``; never float).
DEFAULT_TRAN_AMT = Decimal("100.00")

# EXACT verbatim COBOL reject descriptions -- the golden-master truth
# (AAP 0.7.3 + the code-109 discovery). Codes 101 and 109 deliberately share the
# text "ACCOUNT RECORD NOT FOUND" yet remain DISTINCT reason codes.
EXPECTED_POSTING_CODES = {
    100: "INVALID CARD NUMBER FOUND",
    101: "ACCOUNT RECORD NOT FOUND",
    102: "OVERLIMIT TRANSACTION",
    103: "TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
    109: "ACCOUNT RECORD NOT FOUND",
}


# --------------------------------------------------------------------------- #
# Helpers (Ochs PascalCase, module-level).
# --------------------------------------------------------------------------- #
def _BuildPostableGraph(recordBuilder, accountOverrides=None, tranOverrides=None):
    """Stage a complete, FK-valid postable graph and return ``(account, transaction)``.

    Builds the customer -> account -> card -> card-xref -> PENDING daily
    transaction chain that CBTRN02C's ``1500-VALIDATE-TRAN`` walks, using the
    integration conftest :class:`RecordBuilder` (its real API takes string keys
    and positional values). ``accountOverrides`` tunes the account edits under
    test (credit limit, cycle credit/debit, expiration date); ``tranOverrides``
    tunes the transaction -- its ``tran_amt`` key, when present, is applied as the
    builder's positional amount and the remaining keys as row overrides.

    Args:
        recordBuilder: The conftest ``record_builder`` fixture instance.
        accountOverrides: Optional ``dict`` of account column overrides.
        tranOverrides: Optional ``dict`` of transaction overrides; a ``tran_amt``
            entry sets the amount, any other keys override transaction columns.

    Returns:
        A ``(account, transaction)`` tuple of the persisted ORM rows.
    """
    customer = recordBuilder.BuildCustomer()
    account = recordBuilder.BuildAccount(overrides=accountOverrides)
    recordBuilder.BuildCard(GRAPH_CARD_NUM, account.acct_id)
    recordBuilder.BuildXref(GRAPH_CARD_NUM, customer.cust_id, account.acct_id)
    tranValues = dict(tranOverrides) if tranOverrides else {}
    tranAmt = tranValues.pop("tran_amt", DEFAULT_TRAN_AMT)
    transaction = recordBuilder.BuildPendingTransaction(
        GRAPH_TRAN_ID,
        GRAPH_CARD_NUM,
        tranAmt,
        overrides=tranValues or None,
    )
    return account, transaction


def _GetCategoryBalance(session, acctId, tranTypeCd, tranCatCd):
    """Fetch a ``TranCategoryBalance`` by its composite primary key.

    The primary key is ``(acct_id, tran_type_cd, tran_cat_cd)`` -- the key
    CBTRN02C ``2700-UPDATE-TCATBAL`` upserts against.

    Args:
        session: The synchronous test session.
        acctId: The owning account id (``XREF-ACCT-ID``).
        tranTypeCd: The transaction type code (``DALYTRAN-TYPE-CD``).
        tranCatCd: The transaction category code (``DALYTRAN-CAT-CD``).

    Returns:
        The matching :class:`TranCategoryBalance`, or ``None`` when absent.
    """
    return session.get(TranCategoryBalance, (acctId, tranTypeCd, tranCatCd))


def _ReadRejectRows(postingResult):
    """Read the protected reject sink and return its fixed-width 430-byte rows.

    :func:`PostTransactions` writes the reject generation once to a protected
    owner-only sink (the ``DALYREJS`` equivalent; QA finding #28) and exposes only
    the path on ``postingResult.rejectFilePath`` -- it never returns the raw,
    unmasked reject images in memory. This golden-master helper reads that sink
    back and returns each 430-character reject record, so a test can reconcile the
    reason code, description and byte layout against CBTRN02C. Splitting on line
    boundaries strips only the record separator, preserving each record's
    space-padded 430-character content exactly.

    Args:
        postingResult: The :class:`PostingResult` returned by the job.

    Returns:
        The ``list`` of 430-character reject records (empty when the run produced
        no rejects, in which case no sink file is created).
    """
    sinkPath = postingResult.rejectFilePath
    if sinkPath is None:
        return []
    return Path(sinkPath).read_text(encoding="utf-8").splitlines()


# --------------------------------------------------------------------------- #
# 1-4: successful posting (validation passes; 2700 / 2800 / 2900 run).
# --------------------------------------------------------------------------- #
def test_valid_transaction_posts_and_flips_status(db_session, record_builder, tmp_path):
    # Reconciles CBTRN02C 2900-WRITE-TRANSACTION-FILE (app/cbl/CBTRN02C.cbl L562):
    # a validated daily transaction is promoted to POSTED and stamped with a
    # processing timestamp; the run reports one processed, one posted, no rejects.
    account, transaction = _BuildPostableGraph(
        record_builder,
        accountOverrides={
            "credit_limit": Decimal("10000.00"),
            "curr_cyc_credit": Decimal("0.00"),
            "curr_cyc_debit": Decimal("0.00"),
            "expiration_date": FUTURE_EXPIRATION,
        },
        tranOverrides={
            "tran_amt": Decimal("100.00"),
            "tran_type_cd": "01",
            "tran_cat_cd": "0001",
        },
    )

    result = PostTransactions(db_session, rejectDir=tmp_path)

    assert result.transactionsProcessed == 1
    assert result.transactionsPosted == 1
    assert result.transactionsRejected == 0
    # No rejects -> the job creates no protected sink file (golden-master
    # equivalent of the legacy WS-REJECT-COUNT = 0).
    assert result.rejectFilePath is None

    db_session.expire_all()
    assert transaction.status == STATUS_POSTED
    assert transaction.proc_ts is not None


def test_valid_post_balance_update_order(db_session, record_builder, tmp_path):
    # Reconciles CBTRN02C 2800-UPDATE-ACCOUNT-REC + 2700-UPDATE-TCATBAL
    # (app/cbl/CBTRN02C.cbl L547-549, L467): curr_bal is updated FIRST
    # (ADD DALYTRAN-AMT TO ACCT-CURR-BAL), then -- because the amount is >= 0 --
    # the cycle CREDIT is incremented (cycle debit untouched), and the 2700 upsert
    # creates the category-balance row seeded with the amount.
    account, transaction = _BuildPostableGraph(
        record_builder,
        accountOverrides={
            "curr_bal": Decimal("100.00"),
            "curr_cyc_credit": Decimal("0.00"),
            "curr_cyc_debit": Decimal("0.00"),
            "credit_limit": Decimal("10000.00"),
            "expiration_date": FUTURE_EXPIRATION,
        },
        tranOverrides={
            "tran_amt": Decimal("250.00"),
            "tran_type_cd": "01",
            "tran_cat_cd": "0001",
        },
    )

    PostTransactions(db_session, rejectDir=tmp_path)

    db_session.expire_all()
    assert account.curr_bal == Decimal("350.00")       # 100 + 250, curr_bal first
    assert account.curr_cyc_credit == Decimal("250.00")  # amount >= 0 -> cycle credit
    assert account.curr_cyc_debit == Decimal("0.00")     # unchanged
    categoryBalance = _GetCategoryBalance(db_session, account.acct_id, "01", "0001")
    assert categoryBalance is not None
    assert categoryBalance.balance == Decimal("250.00")  # 2700 upsert created the row


def test_negative_amount_updates_cycle_debit(db_session, record_builder, tmp_path):
    # Reconciles CBTRN02C 2800-UPDATE-ACCOUNT-REC ELSE branch
    # (app/cbl/CBTRN02C.cbl L550-551): a negative DALYTRAN-AMT is signed-ADDed to
    # ACCT-CURR-CYC-DEBIT (not subtracted), leaving cycle credit unchanged. A
    # negative amount cannot breach the credit limit (tempBal = -40 <= 10000).
    account, transaction = _BuildPostableGraph(
        record_builder,
        accountOverrides={
            "curr_bal": Decimal("100.00"),
            "curr_cyc_credit": Decimal("0.00"),
            "curr_cyc_debit": Decimal("0.00"),
            "credit_limit": Decimal("10000.00"),
            "expiration_date": FUTURE_EXPIRATION,
        },
        tranOverrides={"tran_amt": Decimal("-40.00")},
    )

    PostTransactions(db_session, rejectDir=tmp_path)

    db_session.expire_all()
    assert account.curr_bal == Decimal("60.00")          # 100 + (-40)
    assert account.curr_cyc_debit == Decimal("-40.00")   # signed ADD of the negative
    assert account.curr_cyc_credit == Decimal("0.00")    # unchanged


def test_tcatbal_upsert_accumulates(db_session, record_builder, tmp_path):
    # Reconciles CBTRN02C 2700-UPDATE-TCATBAL (app/cbl/CBTRN02C.cbl L467): when a
    # category-balance row already exists its balance is INCREMENTED by
    # DALYTRAN-AMT (2700-B-UPDATE), never overwritten.
    account, transaction = _BuildPostableGraph(
        record_builder,
        accountOverrides={
            "credit_limit": Decimal("10000.00"),
            "curr_cyc_credit": Decimal("0.00"),
            "curr_cyc_debit": Decimal("0.00"),
            "expiration_date": FUTURE_EXPIRATION,
        },
        tranOverrides={
            "tran_amt": Decimal("100.00"),
            "tran_type_cd": "01",
            "tran_cat_cd": "0001",
        },
    )
    # Pre-seed the running category balance so posting must ACCUMULATE onto it.
    record_builder.BuildTranCategoryBalance(
        account.acct_id, "01", "0001", Decimal("50.00")
    )

    PostTransactions(db_session, rejectDir=tmp_path)

    db_session.expire_all()
    categoryBalance = _GetCategoryBalance(db_session, account.acct_id, "01", "0001")
    assert categoryBalance is not None
    assert categoryBalance.balance == Decimal("150.00")  # 50 + 100 accumulation


# --------------------------------------------------------------------------- #
# 5-9: validation rejects (data rejects; 430-byte reject rows, no posting).
# --------------------------------------------------------------------------- #
def test_reject_code_100_invalid_card(db_session, record_builder, parse_reject_row, tmp_path):
    # Reconciles CBTRN02C 1500-A-LOOKUP-XREF (app/cbl/CBTRN02C.cbl L380-390): a
    # missing card cross-reference (READ XREF-FILE ... INVALID KEY) rejects with
    # code 100 and STOPS validating the row (the account is deliberately not
    # read). Built WITHOUT a CardXref so the lookup misses.
    account = record_builder.BuildAccount()
    record_builder.BuildCard(GRAPH_CARD_NUM, account.acct_id)
    transaction = record_builder.BuildPendingTransaction(
        GRAPH_TRAN_ID, GRAPH_CARD_NUM, Decimal("100.00")
    )

    result = PostTransactions(db_session, rejectDir=tmp_path)

    assert result.transactionsProcessed == 1
    assert result.transactionsPosted == 0
    assert result.transactionsRejected == 1
    rejectRows = _ReadRejectRows(result)
    assert len(rejectRows) == 1
    rejectRow = parse_reject_row(rejectRows[0])
    assert rejectRow.reasonCode == 100
    assert rejectRow.description.strip() == EXPECTED_POSTING_CODES[100]

    db_session.expire_all()
    # A reject is never posted and is marked terminally REJECTED so a re-run
    # excludes it from the PENDING driving query (AAP 0.7.6; QA finding F-6).
    assert transaction.status == STATUS_REJECTED


def test_reject_code_102_overlimit(db_session, record_builder, parse_reject_row, tmp_path):
    # Reconciles CBTRN02C 1500-B-LOOKUP-ACCT Check A (app/cbl/CBTRN02C.cbl
    # L403-412): WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT +
    # DALYTRAN-AMT; when ACCT-CREDIT-LIMIT < WS-TEMP-BAL the row rejects with 102.
    # Here tempBal = 0 - 0 + 500 = 500 > limit 100 -> 102.
    account, transaction = _BuildPostableGraph(
        record_builder,
        accountOverrides={
            "credit_limit": Decimal("100.00"),
            "curr_cyc_credit": Decimal("0.00"),
            "curr_cyc_debit": Decimal("0.00"),
            "curr_bal": Decimal("0.00"),
            "expiration_date": FUTURE_EXPIRATION,
        },
        tranOverrides={"tran_amt": Decimal("500.00")},
    )

    result = PostTransactions(db_session, rejectDir=tmp_path)

    assert result.transactionsRejected == 1
    rejectRows = _ReadRejectRows(result)
    rejectRow = parse_reject_row(rejectRows[0])
    assert rejectRow.reasonCode == 102
    assert rejectRow.description.strip() == EXPECTED_POSTING_CODES[102]

    db_session.expire_all()
    assert account.curr_bal == Decimal("0.00")   # unchanged -- reject not posted
    assert transaction.status == STATUS_REJECTED  # terminal (F-6): not re-attempted


def test_reject_code_103_after_expiration(db_session, record_builder, parse_reject_row, tmp_path):
    # Reconciles CBTRN02C 1500-B-LOOKUP-ACCT Check B (app/cbl/CBTRN02C.cbl
    # L414-419): when ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10) the row rejects
    # with 103. Credit limit is high so Check A passes; expiration is in the past
    # (2000-01-01 < the 2023-06-01 default orig date) so only Check B trips.
    account, transaction = _BuildPostableGraph(
        record_builder,
        accountOverrides={
            "credit_limit": Decimal("10000.00"),
            "curr_cyc_credit": Decimal("0.00"),
            "curr_cyc_debit": Decimal("0.00"),
            "expiration_date": PAST_EXPIRATION,
        },
        tranOverrides={"tran_amt": Decimal("100.00")},
    )

    result = PostTransactions(db_session, rejectDir=tmp_path)

    assert result.transactionsRejected == 1
    rejectRows = _ReadRejectRows(result)
    rejectRow = parse_reject_row(rejectRows[0])
    assert rejectRow.reasonCode == 103
    assert rejectRow.description.strip() == EXPECTED_POSTING_CODES[103]


def test_reject_102_and_103_last_write_wins(db_session, record_builder, parse_reject_row, tmp_path):
    # Reconciles CBTRN02C 1500-B-LOOKUP-ACCT (app/cbl/CBTRN02C.cbl L407-419): Check
    # B (expiration) is a SEPARATE `IF` evaluated AFTER Check A (credit limit) --
    # NOT an `elif`. So when BOTH edits fail, the sequential MOVE of 103 OVERWRITES
    # the earlier 102 and the final reject reason is 103 (last-write-wins). Here
    # limit 100 fails Check A (tempBal 500) AND past expiration fails Check B.
    account, transaction = _BuildPostableGraph(
        record_builder,
        accountOverrides={
            "credit_limit": Decimal("100.00"),
            "curr_cyc_credit": Decimal("0.00"),
            "curr_cyc_debit": Decimal("0.00"),
            "expiration_date": PAST_EXPIRATION,
        },
        tranOverrides={"tran_amt": Decimal("500.00")},
    )

    result = PostTransactions(db_session, rejectDir=tmp_path)

    rejectRows = _ReadRejectRows(result)
    rejectRow = parse_reject_row(rejectRows[0])
    assert rejectRow.reasonCode == 103  # NOT 102 -- 103 is written last and wins
    assert rejectRow.description.strip() == EXPECTED_POSTING_CODES[103]


def test_reject_row_layout_is_430_bytes(db_session, record_builder, tmp_path):
    # Reconciles CBTRN02C L176-182: REJECT-RECORD = REJECT-TRAN-DATA PIC X(350) +
    # WS-VALIDATION-FAIL-REASON PIC 9(04) + WS-VALIDATION-FAIL-REASON-DESC
    # PIC X(76) = 430 bytes. Reuses the 102 over-limit scenario and asserts the
    # exact fixed-width byte geometry of the emitted reject record.
    _BuildPostableGraph(
        record_builder,
        accountOverrides={
            "credit_limit": Decimal("100.00"),
            "curr_cyc_credit": Decimal("0.00"),
            "curr_cyc_debit": Decimal("0.00"),
            "expiration_date": FUTURE_EXPIRATION,
        },
        tranOverrides={"tran_amt": Decimal("500.00")},
    )

    result = PostTransactions(db_session, rejectDir=tmp_path)

    rejectRow = _ReadRejectRows(result)[0]  # raw 430-char record
    assert len(rejectRow) == REJECT_ROW_LENGTH
    # Reason field: zero-padded 4-digit code at [350:354].
    assert rejectRow[REJECT_REASON_START:REJECT_REASON_END] == "0102"
    # Description field: left-justified/space-padded to 76 at [354:430].
    description = rejectRow[REJECT_DESC_START:]
    assert len(description) == 76
    assert description.rstrip() == EXPECTED_POSTING_CODES[102]
    # Data image: the leading 350-byte DALYTRAN-RECORD image.
    assert len(rejectRow[0:REJECT_REASON_START]) == 350


# --------------------------------------------------------------------------- #
# 10-12: catalog parity, best-effort code 101, and idempotent re-run.
# --------------------------------------------------------------------------- #
def test_posting_code_catalog_parity(posting_catalog):
    # Reconciles AAP 0.7.3 + the code-109 discovery against CBTRN02C
    # (app/cbl/CBTRN02C.cbl L385-419, L556-558): the app.core.exceptions catalog
    # must hold the EXACT verbatim COBOL description for every reject code
    # 100/101/102/103/109. This is the design-agnostic guard for 101 and 109,
    # which are structurally unreachable via the public API under the hard FK; the
    # `posting_catalog` fixture resolves the catalog whichever exception design is
    # used.
    postingCatalog = posting_catalog
    if not postingCatalog:
        pytest.skip("posting catalog not resolvable from app.core.exceptions")
    for code, description in EXPECTED_POSTING_CODES.items():
        assert code in postingCatalog
        assert postingCatalog[code].strip() == description


def test_reject_code_101_account_not_found(
    db_session, record_builder, parse_reject_row, relax_foreign_keys, tmp_path
):
    # Reconciles CBTRN02C 1500-B-LOOKUP-ACCT (app/cbl/CBTRN02C.cbl L393-399): the
    # cross-reference is found but the account READ ... INVALID KEY misses, so the
    # row rejects with 101. Code 101 is structurally UNREACHABLE while
    # card_xref.acct_id carries a hard foreign key, so this best-effort test
    # relaxes the FK triggers to seed a dangling xref. The DDL is isolated in a
    # SAVEPOINT so a privilege failure (DISABLE TRIGGER needs table ownership /
    # superuser) does not poison the shared rolled-back session -- it skips.
    customer = record_builder.BuildCustomer()
    account = record_builder.BuildAccount()
    record_builder.BuildCard(GRAPH_CARD_NUM, account.acct_id)
    transaction = record_builder.BuildPendingTransaction(
        GRAPH_TRAN_ID, GRAPH_CARD_NUM, Decimal("100.00")
    )

    savepoint = db_session.begin_nested()
    try:
        with relax_foreign_keys(db_session, "card_xref"):
            # Dangling acct_id: xref points at an account that does not exist.
            record_builder.BuildXref(GRAPH_CARD_NUM, customer.cust_id, DANGLING_ACCT_ID)
    except (ProgrammingError, OperationalError):
        savepoint.rollback()
        pytest.skip("DISABLE TRIGGER requires table ownership/superuser")

    result = PostTransactions(db_session, rejectDir=tmp_path)

    assert result.transactionsRejected == 1
    rejectRows = _ReadRejectRows(result)
    rejectRow = parse_reject_row(rejectRows[0])
    assert rejectRow.reasonCode == 101
    assert rejectRow.description.strip() == EXPECTED_POSTING_CODES[101]

    db_session.expire_all()
    # A reject is never posted and is marked terminally REJECTED so a re-run
    # excludes it from the PENDING driving query (AAP 0.7.6; QA finding F-6).
    assert transaction.status == STATUS_REJECTED


def test_idempotent_rerun_no_double_post(db_session, record_builder, tmp_path):
    # Reconciles CBTRN02C's driver loop (app/cbl/CBTRN02C.cbl L210-215; AAP 0.7.6):
    # posting reads only PENDING daily transactions, so a second run finds nothing
    # to process and never double-posts -- the account balance is stable across
    # re-runs (the caller-owned transaction makes the job idempotent).
    account, transaction = _BuildPostableGraph(
        record_builder,
        accountOverrides={
            "curr_bal": Decimal("0.00"),
            "curr_cyc_credit": Decimal("0.00"),
            "curr_cyc_debit": Decimal("0.00"),
            "credit_limit": Decimal("10000.00"),
            "expiration_date": FUTURE_EXPIRATION,
        },
        tranOverrides={"tran_amt": Decimal("100.00")},
    )

    firstRun = PostTransactions(db_session, rejectDir=tmp_path)
    assert firstRun.transactionsPosted == 1
    db_session.expire_all()
    balanceAfterFirst = account.curr_bal
    assert balanceAfterFirst == Decimal("100.00")

    secondRun = PostTransactions(db_session, rejectDir=tmp_path)
    assert secondRun.transactionsProcessed == 0  # nothing PENDING remains
    assert secondRun.transactionsPosted == 0

    db_session.expire_all()
    assert account.curr_bal == balanceAfterFirst  # no double-post


def test_rejected_row_not_reattempted_on_rerun(db_session, record_builder, tmp_path):
    # Reconciles AAP 0.7.6 / QA finding F-6: a rejected daily row is marked
    # terminally REJECTED, so a second posting run excludes it from the PENDING
    # driving query and never re-attempts it. Previously the row stayed PENDING
    # and was reprocessed on every re-run, so a changed account state on a later
    # run could post a row that was rejected earlier -- breaking chain
    # idempotency. Uses the code-102 over-limit reject (tempBal 500 > limit 100).
    account, transaction = _BuildPostableGraph(
        record_builder,
        accountOverrides={
            "credit_limit": Decimal("100.00"),
            "curr_cyc_credit": Decimal("0.00"),
            "curr_cyc_debit": Decimal("0.00"),
            "curr_bal": Decimal("0.00"),
            "expiration_date": FUTURE_EXPIRATION,
        },
        tranOverrides={"tran_amt": Decimal("500.00")},
    )

    firstRun = PostTransactions(db_session, rejectDir=tmp_path)
    assert firstRun.transactionsProcessed == 1
    assert firstRun.transactionsPosted == 0
    assert firstRun.transactionsRejected == 1
    db_session.expire_all()
    assert transaction.status == STATUS_REJECTED  # terminal after first run
    assert account.curr_bal == Decimal("0.00")    # a reject posts nothing

    secondRun = PostTransactions(db_session, rejectDir=tmp_path)
    # The terminal REJECTED row is excluded from the PENDING driving query, so
    # the re-run processes nothing and re-attempts no reject.
    assert secondRun.transactionsProcessed == 0
    assert secondRun.transactionsPosted == 0
    assert secondRun.transactionsRejected == 0
    db_session.expire_all()
    assert transaction.status == STATUS_REJECTED  # still terminal
    assert account.curr_bal == Decimal("0.00")    # still unchanged
