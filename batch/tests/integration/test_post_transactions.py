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
from contextlib import contextmanager
from decimal import Decimal
from pathlib import Path

import pytest
from sqlalchemy import delete
from sqlalchemy.exc import OperationalError, ProgrammingError

from app.models import (
    Account,
    AccountGroup,
    Card,
    STATUS_PENDING,
    STATUS_POSTED,
    STATUS_REJECTED,
    TranCategoryBalance,
    Transaction,
)
from batch import db as batch_db
from batch.jobs.post_transactions import (
    PostTransactions,
    _ClaimPendingTransactions,
    _PostTransaction,
)

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
# A second stable tran id used by multi-reject scenarios (for example the M-13
# fixed-record byte-exactness test), so a run can stage more than one reject.
SECOND_TRAN_ID = "TRAN000000000002"

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


def _ReadRejectSinkBytes(postingResult):
    """Return the RAW bytes of the protected reject sink (or ``b""`` if none).

    Reads the sink verbatim -- no text decoding, no newline handling -- so a test
    can assert the exact physical geometry of the fixed-record dataset (RECFM=F,
    LRECL=430). This is the byte-level ground truth behind :func:`_ReadRejectRows`
    and is what makes QA finding M-13 observable: a stray per-record delimiter
    would make the file length ``N*431`` instead of ``N*430``.

    Args:
        postingResult: The :class:`PostingResult` returned by the job.

    Returns:
        The full sink contents as ``bytes`` (``b""`` when no sink was written).
    """
    sinkPath = postingResult.rejectFilePath
    if sinkPath is None:
        return b""
    return Path(sinkPath).read_bytes()


def _ReadRejectRows(postingResult):
    """Read the protected reject sink and return its fixed-width 430-byte rows.

    :func:`PostTransactions` writes the reject generation once to a protected
    owner-only sink (the ``DALYREJS`` equivalent; QA finding #28) and exposes only
    the path on ``postingResult.rejectFilePath`` -- it never returns the raw,
    unmasked reject images in memory. This golden-master helper reads that sink
    back as RAW BYTES and slices it into exact :data:`REJECT_ROW_LENGTH`-byte
    records -- deliberately NOT via ``str.splitlines()``, which would silently
    absorb a stray per-record delimiter and hide the M-13 physical defect. The
    sink is a fixed-record (RECFM=F, LRECL=430) dataset with NO delimiter, so its
    total length must be an exact multiple of 430; this helper asserts that
    invariant, then decodes each 430-byte slice from the single-byte record
    encoding to a 430-character string for field-level golden-master assertions.

    Args:
        postingResult: The :class:`PostingResult` returned by the job.

    Returns:
        The ``list`` of 430-character reject records (empty when the run produced
        no rejects, in which case no sink file is created).
    """
    sinkBytes = _ReadRejectSinkBytes(postingResult)
    if not sinkBytes:
        return []
    assert len(sinkBytes) % REJECT_ROW_LENGTH == 0, (
        f"reject sink must be a whole number of {REJECT_ROW_LENGTH}-byte records "
        f"(RECFM=F, LRECL=430), got {len(sinkBytes)} bytes"
    )
    rows = []
    for offset in range(0, len(sinkBytes), REJECT_ROW_LENGTH):
        recordBytes = sinkBytes[offset:offset + REJECT_ROW_LENGTH]
        rows.append(recordBytes.decode("ascii"))
    return rows


# Distinct committed-fixture keys for the concurrency test (kept well away from
# the rolled-back per-test graph's keys so the surgical cleanup below can never
# touch another test's data).
CONCURRENCY_GROUP_ID = "ZZCONCUR01"
CONCURRENCY_ACCT_ID = "99000000001"
CONCURRENCY_CARD_NUM = "4999000000000001"
CONCURRENCY_TRAN_IDS = ("CONCURRENT000001", "CONCURRENT000002")


@contextmanager
def _CommittedPendingGraph():
    """Yield two INDEPENDENT sessions over a COMMITTED two-row PENDING graph.

    Cross-connection row locking (``SELECT ... FOR UPDATE SKIP LOCKED``) can only
    be exercised against data that is visible to more than one transaction, so
    this helper deliberately steps OUTSIDE the rolled-back ``db_session`` recipe:
    it seeds a minimal ``account_group -> account -> card -> 2 PENDING
    transactions`` graph on its OWN connection and COMMITS it, then yields two
    independent :class:`~sqlalchemy.orm.Session` objects (``sessionA``,
    ``sessionB``) on two separate connections. On exit it rolls back both
    sessions and surgically DELETEs exactly the rows it committed (in reverse
    foreign-key order) on a dedicated cleanup connection, so the committed
    fixture never leaks into another test. The keys are unique constants held far
    from any rolled-back graph's keys.

    Yields:
        A ``(sessionA, sessionB)`` tuple of independent, committed-data sessions.
    """
    seedSession = batch_db.SessionLocal()
    try:
        seedSession.add(AccountGroup(group_id=CONCURRENCY_GROUP_ID))
        seedSession.add(
            Account(
                acct_id=CONCURRENCY_ACCT_ID,
                active_status="Y",
                curr_bal=Decimal("0.00"),
                credit_limit=Decimal("5000.00"),
                cash_credit_limit=Decimal("2000.00"),
                curr_cyc_credit=Decimal("0.00"),
                curr_cyc_debit=Decimal("0.00"),
                group_id=CONCURRENCY_GROUP_ID,
            )
        )
        seedSession.add(
            Card(
                card_num=CONCURRENCY_CARD_NUM,
                acct_id=CONCURRENCY_ACCT_ID,
                embossed_name="TEST CARDHOLDER",
                active_status="Y",
            )
        )
        for tranId in CONCURRENCY_TRAN_IDS:
            seedSession.add(
                Transaction(
                    tran_id=tranId,
                    tran_type_cd="01",
                    tran_cat_cd="0001",
                    tran_amt=Decimal("100.00"),
                    card_num=CONCURRENCY_CARD_NUM,
                    status=STATUS_PENDING,
                )
            )
        seedSession.commit()
    finally:
        seedSession.close()

    sessionA = batch_db.SessionLocal()
    sessionB = batch_db.SessionLocal()
    try:
        yield sessionA, sessionB
    finally:
        sessionA.rollback()
        sessionB.rollback()
        sessionA.close()
        sessionB.close()
        cleanupSession = batch_db.SessionLocal()
        try:
            cleanupSession.execute(
                delete(Transaction).where(
                    Transaction.tran_id.in_(CONCURRENCY_TRAN_IDS)
                )
            )
            cleanupSession.execute(
                delete(Card).where(Card.card_num == CONCURRENCY_CARD_NUM)
            )
            cleanupSession.execute(
                delete(Account).where(Account.acct_id == CONCURRENCY_ACCT_ID)
            )
            cleanupSession.execute(
                delete(AccountGroup).where(
                    AccountGroup.group_id == CONCURRENCY_GROUP_ID
                )
            )
            cleanupSession.commit()
        finally:
            cleanupSession.close()


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


def test_reject_sink_is_fixed_430_byte_records_no_delimiter(
    db_session, record_builder, tmp_path
):
    # QA finding M-13: the DALYREJS sink is a fixed-record dataset (RECFM=F,
    # LRECL=430) whose records are concatenated with NO delimiter. The physical
    # artifact must therefore be EXACTLY N*430 bytes; the pre-fix defect appended
    # a newline per record, making it N*431 -- a defect that ``str.splitlines()``
    # silently hid. This asserts the RAW byte geometry directly. Two invalid-card
    # (code 100) rejects are staged (a card with NO CardXref), so the sink holds
    # exactly two fixed records.
    account = record_builder.BuildAccount()
    record_builder.BuildCard(GRAPH_CARD_NUM, account.acct_id)
    record_builder.BuildPendingTransaction(
        GRAPH_TRAN_ID, GRAPH_CARD_NUM, Decimal("100.00")
    )
    record_builder.BuildPendingTransaction(
        SECOND_TRAN_ID, GRAPH_CARD_NUM, Decimal("200.00")
    )

    result = PostTransactions(db_session, rejectDir=tmp_path)
    assert result.transactionsRejected == 2

    sinkBytes = _ReadRejectSinkBytes(result)
    # Exactly two 430-byte records = 860 bytes: no trailing or interstitial
    # delimiter of any kind.
    assert len(sinkBytes) == 2 * REJECT_ROW_LENGTH
    assert b"\n" not in sinkBytes
    assert b"\r" not in sinkBytes
    # And the raw stream slices cleanly into two full 430-character records.
    rejectRows = _ReadRejectRows(result)
    assert len(rejectRows) == 2
    assert all(len(rejectRow) == REJECT_ROW_LENGTH for rejectRow in rejectRows)


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


def test_reject_code_109_account_update_failure(
    db_session, record_builder, relax_foreign_keys
):
    # Reconciles CBTRN02C 2800-UPDATE-ACCOUNT-REC (app/cbl/CBTRN02C.cbl L556-558):
    # the defensive REWRITE ... INVALID KEY guard that rejects with code 109
    # "ACCOUNT RECORD NOT FOUND" when the owning account cannot be resolved at
    # POST time and posts NOTHING.
    #
    # Code 109 is reached in production ONLY through the post-time account
    # re-resolution in _PostTransaction; it is structurally UNREACHABLE through
    # the public PostTransactions entry point, because _ValidateTran's
    # 1500-B-LOOKUP-ACCT would first catch a missing account as reject 101 and
    # short-circuit, so the row never reaches the post step. This test therefore
    # drives the REAL production _PostTransaction DIRECTLY on the genuine
    # resolved-xref / missing-account seam -- a lower-level integration test the
    # M-19 finding explicitly permits -- exercising the true 109 mapping without
    # weakening any constraint or disabling FK enforcement on the posting path.
    #
    # Notably, the SAME dangling-xref seed drives reject 101 through the
    # validation path (test_reject_code_101_account_not_found) and reject 109
    # through the post path (here), proving 101 and 109 are DISTINCT reason codes
    # reached by DISTINCT production paths even though both carry the verbatim
    # text "ACCOUNT RECORD NOT FOUND" (AAP 0.7.3 + code-109 discovery).
    #
    # The dangling xref is seeded exactly as the 101 test does: card_xref.acct_id
    # carries a hard FK, so its triggers are relaxed inside a SAVEPOINT; a
    # privilege failure (DISABLE TRIGGER needs table ownership / superuser)
    # rolls the SAVEPOINT back and skips rather than poisoning the session.
    customer = record_builder.BuildCustomer()
    account = record_builder.BuildAccount()
    record_builder.BuildCard(GRAPH_CARD_NUM, account.acct_id)
    transaction = record_builder.BuildPendingTransaction(
        GRAPH_TRAN_ID, GRAPH_CARD_NUM, DEFAULT_TRAN_AMT
    )
    # Capture the transaction's type/category codes now (while loaded) so the
    # "nothing posted" tcatbal probe below is self-consistent with whatever the
    # builder staged, without hardcoding conftest defaults that could drift.
    tranTypeCd = transaction.tran_type_cd
    tranCatCd = transaction.tran_cat_cd

    savepoint = db_session.begin_nested()
    try:
        with relax_foreign_keys(db_session, "card_xref"):
            # Resolved-but-dangling xref: it exists (so _PostTransaction's xref
            # lookup succeeds) yet points at an account that does not exist (so
            # the subsequent account lookup misses -> the code-109 guard trips).
            record_builder.BuildXref(GRAPH_CARD_NUM, customer.cust_id, DANGLING_ACCT_ID)
    except (ProgrammingError, OperationalError):
        savepoint.rollback()
        pytest.skip("DISABLE TRIGGER requires table ownership/superuser")

    # The posting timestamp is only stamped by 2900-WRITE-TRANSACTION-FILE, which
    # is never reached on the 109 path, so its value is immaterial here; a fixed
    # timestamp keeps the call deterministic.
    postingTimestamp = datetime.datetime(2023, 6, 1, 12, 0, 0)
    reasonCode, description = _PostTransaction(
        db_session, transaction, postingTimestamp
    )

    # The genuine 109 mapping: reject code 109 with the verbatim COBOL text.
    assert reasonCode == 109
    assert description.strip() == EXPECTED_POSTING_CODES[109]

    # Nothing is posted: the guard returns before 2700/2800/2900, so no
    # category-balance row was created under the dangling account and the daily
    # row stays PENDING (the orchestrator -- not _PostTransaction -- flips status).
    assert (
        _GetCategoryBalance(db_session, DANGLING_ACCT_ID, tranTypeCd, tranCatCd)
        is None
    )
    db_session.expire_all()
    assert transaction.status == STATUS_PENDING


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


def test_concurrent_posting_runs_claim_disjoint_rows():
    # QA finding M-12: two concurrent posting runs must claim DISJOINT PENDING
    # rows and never double-process one. This is a DETERMINISTIC lock-barrier
    # proof (no threads): SELECT ... FOR UPDATE SKIP LOCKED is non-blocking, so
    # once run A claims and locks every PENDING row inside its still-open
    # transaction, run B's IDENTICAL production claim (_ClaimPendingTransactions)
    # skips those locked rows and gets nothing. The final step releases A's locks
    # and re-claims on B to prove B's empty claim was caused by A's locks (not an
    # empty or mis-seeded table) -- i.e. the assertion is non-vacuous.
    with _CommittedPendingGraph() as (sessionA, sessionB):
        claimedA = _ClaimPendingTransactions(sessionA)
        idsA = {tran.tran_id for tran in claimedA}
        assert idsA == set(CONCURRENCY_TRAN_IDS)  # A claims and locks both rows

        # B runs concurrently while A still holds its locks: SKIP LOCKED yields
        # the DISJOINT (here empty) remainder -- no row is claimed twice.
        claimedB = _ClaimPendingTransactions(sessionB)
        idsB = {tran.tran_id for tran in claimedB}
        assert idsB == set()
        assert idsA.isdisjoint(idsB)

        # Non-vacuity: release A's locks, then B can now claim the very same rows,
        # proving B's earlier empty claim was due to A's locks, not missing data.
        sessionA.rollback()
        reclaimedB = _ClaimPendingTransactions(sessionB)
        assert {tran.tran_id for tran in reclaimedB} == set(CONCURRENCY_TRAN_IDS)
