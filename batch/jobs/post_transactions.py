# Ported from legacy COBOL batch program CBTRN02C.cbl + JCL app/jcl/POSTTRAN.jcl
# (CardDemo). Function: post daily transactions with validation codes 100-103/109
# and 430-byte reject rows.
"""Daily-transaction posting job -- Python port of COBOL ``CBTRN02C``.

This module reimplements the CardDemo daily-transaction posting driver
(``app/cbl/CBTRN02C.cbl``, wired by ``app/jcl/POSTTRAN.jcl``). It walks every
unposted (``PENDING``) daily transaction, validates it exactly as the legacy
``1500-VALIDATE-TRAN`` paragraph did, and -- when valid -- posts it by updating
the transaction-category running balance (``2700-UPDATE-TCATBAL``), updating the
owning account's balances (``2800-UPDATE-ACCOUNT-REC``) and promoting the daily
row to the posted ledger (``2900-WRITE-TRANSACTION-FILE``). Invalid transactions
are *rejected* -- a fixed-width 430-byte reject record is produced
(``2500-WRITE-REJECT-REC``) and processing continues with the next row; the run
never aborts on a data reject.

Golden-master parity (AAP 0.8.1) is the hard requirement here: every reject
reason code, every verbatim description string, the 430-byte reject-record byte
layout, the validation order and the balance-update arithmetic reproduce the
mainframe behavior field-for-field. The reject reason codes (100, 101, 102, 103,
109) and their UPPERCASE descriptions are sourced exclusively from
:mod:`app.core.exceptions` so there is a single source of truth.

Legacy paragraph -> Python helper map:

* ``1500-VALIDATE-TRAN``        -> :func:`_ValidateTran`
* ``1500-A-LOOKUP-XREF``        -> xref lookup inside :func:`_ValidateTran`
* ``1500-B-LOOKUP-ACCT``        -> account lookup + :func:`_CheckAccountLimits`
* ``2000-POST-TRANSACTION``     -> :func:`_PostTransaction`
* ``2700-UPDATE-TCATBAL``       -> :func:`_UpdateTcatbal`
* ``2800-UPDATE-ACCOUNT-REC``   -> :func:`_UpdateAccount`
* ``2900-WRITE-TRANSACTION-FILE`` -> :func:`_WriteTransaction`
* ``2500-WRITE-REJECT-REC``     -> :func:`_BuildRejectRow` + :func:`_FormatDailyRecord`

Transaction ownership:
    The caller owns the unit of work. :func:`PostTransactions` receives an
    already-open synchronous :class:`~sqlalchemy.orm.Session` (from
    ``batch.db.GetSyncSession``) and NEVER calls ``commit`` or ``close`` -- it
    only ``flush``es. Giving each run its own caller-owned transaction preserves
    idempotency: on re-run, the driving query filters on ``PENDING``, so every
    row from a prior run is skipped -- both rows already promoted to ``POSTED``
    and rows marked terminally ``REJECTED`` (a data reject, reason 100-103 or a
    109 update failure). Marking rejects ``REJECTED`` rather than leaving them
    ``PENDING`` is what makes a re-run never re-attempt a previously rejected row
    (AAP 0.7.6; QA finding F-6).

Numeric fidelity (AAP 0.7.1):
    Every monetary value is an exact :class:`decimal.Decimal`; floating point is
    never used, matching the regulatory numeric-parity requirement. Amounts in
    the reject record are re-encoded to their signed zoned-decimal byte image via
    :func:`app.utils.decimal_utils.EncodeZonedDecimal`.

Schema ownership:
    This module never issues DDL. The database schema is owned exclusively by
    Alembic (``backend/alembic/versions/*``); posting only reads and writes rows
    through the supplied ORM session.
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.exceptions import (
    POSTING_REJECT_DESCRIPTIONS,
    REJECT_RECORD_LENGTH,
    REJECT_TRAN_DATA_LENGTH,
    FormatValidationTrailer,
    PostingRejectCode,
)
from app.models import STATUS_PENDING, STATUS_POSTED, STATUS_REJECTED
from app.models.account import Account
from app.models.card_xref import CardXref
from app.models.tran_category_balance import TranCategoryBalance
from app.models.transaction import Transaction
from app.utils.date_utils import FormatLegacyTimestamp, ParseLegacyDate
from app.utils.decimal_utils import MONEY_SCALE, TRAN_AMOUNT_DIGITS, EncodeZonedDecimal

# Module logger. Only aggregate counts and the legacy start banner are logged;
# full reject rows and card numbers (PANs) are never logged (AAP 0.7.8).
LOGGER = logging.getLogger(__name__)

# Legacy start banner, emitted verbatim (CBTRN02C PROCEDURE DIVISION first
# statement: DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN02C').
START_MESSAGE = "START OF EXECUTION OF PROGRAM CBTRN02C"

# Sentinel meaning "no validation failure" -- WS-VALIDATION-FAIL-REASON = 0 in
# the COBOL driver (a passing record).
NO_FAILURE = 0

# ---------------------------------------------------------------------------
# Fixed-width field widths of the 350-byte DALYTRAN-RECORD (copybook CVTRA06Y,
# byte-identical to the posted TRAN-RECORD CVTRA05Y). One ALL_UPPERCASE constant
# per copybook field, in copybook order; the widths tile REJECT_TRAN_DATA_LENGTH
# (350) exactly. The trailing FILLER PIC X(20) carries no data and is emitted as
# spaces only to preserve the 350-byte record length.
# ---------------------------------------------------------------------------
TRAN_ID_WIDTH = 16          # DALYTRAN-ID            PIC X(16)
TRAN_TYPE_CD_WIDTH = 2      # DALYTRAN-TYPE-CD       PIC X(02)
TRAN_CAT_CD_WIDTH = 4       # DALYTRAN-CAT-CD        PIC 9(04)
TRAN_SOURCE_WIDTH = 10      # DALYTRAN-SOURCE        PIC X(10)
TRAN_DESC_WIDTH = 100       # DALYTRAN-DESC          PIC X(100)
TRAN_AMT_WIDTH = TRAN_AMOUNT_DIGITS  # DALYTRAN-AMT  PIC S9(09)V99 -> 11 digits
MERCHANT_ID_WIDTH = 9       # DALYTRAN-MERCHANT-ID   PIC 9(09)
MERCHANT_NAME_WIDTH = 50    # DALYTRAN-MERCHANT-NAME PIC X(50)
MERCHANT_CITY_WIDTH = 50    # DALYTRAN-MERCHANT-CITY PIC X(50)
MERCHANT_ZIP_WIDTH = 10     # DALYTRAN-MERCHANT-ZIP  PIC X(10)
CARD_NUM_WIDTH = 16         # DALYTRAN-CARD-NUM      PIC X(16)
TIMESTAMP_WIDTH = 26        # DALYTRAN-ORIG-TS / -PROC-TS PIC X(26)
DALYTRAN_FILLER_WIDTH = 20  # FILLER                 PIC X(20)

# Position (0-based) at which to slice a legacy 26-byte timestamp text down to
# its 10-character date portion (YYYY-MM-DD) -- the legacy DALYTRAN-ORIG-TS(1:10)
# reference used by the account-expiration check.
DATE_TEXT_LENGTH = 10

# ---------------------------------------------------------------------------
# Protected reject sink (DALYREJS equivalent). AAP 0.7.3 / QA finding #28.
#
# The legacy CBTRN02C writes each 430-byte reject record to the DALYREJS dataset
# (POSTTRAN.jcl: DISP=(NEW,CATLG,DELETE), RECFM=F, LRECL=430) and DISPLAYs only
# the reject COUNT -- the raw-record DISPLAY at CBTRN02C.cbl:449 is commented out.
# We reproduce that: reject rows (which carry the full, unmasked card number as
# part of the raw record image, for reconciliation) are written ONCE to a
# protected on-disk sink and NEVER returned in memory, echoed to stdout, or
# logged. Callers receive only counters and the opaque sink path.
#
# The sink file is created 0600 (owner read/write only) inside a 0700 directory
# (owner-only), so the reconciliation image with its PANs is not world/group
# readable. The dataset is RECFM=F, LRECL=430 (POSTTRAN.jcl): fixed-length
# records are concatenated with NO delimiter, so the physical artifact is
# exactly N*430 bytes -- never N*431 with a trailing newline per record (QA
# finding M-13).
# ---------------------------------------------------------------------------
REJECT_DIR_MODE = 0o700
REJECT_FILE_MODE = 0o600

# Single-byte encoding for the fixed-width DALYREJS record image. The 430-byte
# record is pure single-byte text (space/zero padding, digits and the
# zoned-decimal overpunch bytes ``{`` ``}`` ``A``-``R``), so ``ascii`` maps one
# character to exactly one byte. Using ``ascii`` (not a permissive 8-bit
# codec) makes any unexpected non-ASCII byte fail loud rather than silently
# corrupt the fixed-record geometry the reconciliation reader depends on.
REJECT_RECORD_ENCODING = "ascii"

# Default reject-sink directory: ``<repo>/out/posting_rejects`` (this file is
# ``<repo>/batch/jobs/post_transactions.py`` -> three parents up is ``<repo>``),
# mirroring the CLI's ``<repo>/out`` output convention. The CLI exposes it as the
# ``--reject-dir`` default; the job falls back to it when ``rejectDir`` is None.
DEFAULT_REJECT_DIR = (
    Path(__file__).resolve().parent.parent.parent / "out" / "posting_rejects"
)

# Filename stem for a reject generation. A run-date (or run timestamp when no
# date is supplied) suffix preserves the legacy GDG generation-relative naming
# (AAP 0.7.5) without clobbering evidence from unrelated runs.
REJECT_FILE_PREFIX = "dalyrejs"


@dataclass
class PostingResult:
    """Aggregate outcome of one :func:`PostTransactions` run.

    Groups the return values into a single object so the public entry point keeps
    a small return surface (Ochs Rule: prefer one object over many loose values).
    The three counters mirror the legacy ``WS-TRANSACTION-COUNT`` /
    ``WS-REJECT-COUNT`` display totals; a run with a non-zero
    :attr:`transactionsRejected` corresponds to the legacy ``RETURN-CODE = 4``
    (the CLI surfaces that as process exit code 4; this module does not exit).

    Security (QA finding #28): this object carries ONLY non-sensitive summary
    data -- three integer counters and an opaque filesystem path. It deliberately
    does NOT hold the raw 430-byte reject records, because those are the
    unmasked daily-transaction record images (they contain the full card number).
    The rows are written once to a protected sink (:func:`_WriteRejectSink`); the
    path in :attr:`rejectFilePath` names that sink but reveals no cardholder data.
    Both :meth:`__str__` and the dataclass ``__repr__`` are therefore safe to
    echo to stdout and to log.

    Attributes:
        transactionsProcessed: Total daily (``PENDING``) transactions read and
            examined this run (legacy ``WS-TRANSACTION-COUNT``).
        transactionsPosted: Count of transactions that validated cleanly and were
            posted to the ledger and balances.
        transactionsRejected: Count of transactions that failed validation (or
            the defensive code-109 account-update guard) and produced a reject
            record (legacy ``WS-REJECT-COUNT``).
        rejectFilePath: Absolute path to the protected ``DALYREJS``-equivalent
            sink file the reject records were written to, or ``None`` when the run
            produced no rejects (no file is created). This is opaque metadata (a
            path only) -- it contains no cardholder data.
    """

    transactionsProcessed: int = 0
    transactionsPosted: int = 0
    transactionsRejected: int = 0
    rejectFilePath: str | None = None

    def __str__(self) -> str:
        """Render a safe one-line summary (counters + opaque sink path only).

        Never includes any reject-record content, so it is safe for CLI output
        and logging (QA finding #28).

        Returns:
            A single line of the form
            ``processed=N posted=N rejected=N reject_file=<path|none>``.
        """
        sinkLabel = self.rejectFilePath if self.rejectFilePath else "(none)"
        return (
            f"processed={self.transactionsProcessed} "
            f"posted={self.transactionsPosted} "
            f"rejected={self.transactionsRejected} "
            f"reject_file={sinkLabel}"
        )


def PostTransactions(
    session: Session,
    runDate: date | None = None,
    rejectDir: Path | None = None,
) -> PostingResult:
    """Post every pending daily transaction (Python port of ``CBTRN02C``).

    Reproduces the legacy driver loop verbatim: read each unposted daily
    transaction in a deterministic order, validate it (:func:`_ValidateTran`),
    then either post it (:func:`_PostTransaction`) or build a 430-byte reject
    record (:func:`_BuildRejectRow`) and continue. Validation failures are data
    *rejects*, not fatal errors -- the run never aborts on one. Infrastructure
    failures (for example :class:`sqlalchemy.exc.SQLAlchemyError`) are allowed to
    propagate to the caller, which owns rollback.

    The caller owns the transaction: this function never commits or closes the
    session, only flushes. Because the driving query filters on ``PENDING`` and
    every processed row reaches a terminal status (``POSTED`` on a successful
    post, ``REJECTED`` on a data reject), a re-run skips every row handled on a
    prior run, making the job idempotent and re-runnable (AAP 0.7.6; QA finding
    F-6). A rejected row is therefore never re-attempted.

    Reject handling (QA finding #28): the 430-byte reject records carry the raw,
    unmasked daily-transaction image (including the full card number), so they
    are written ONCE to a protected on-disk sink (the ``DALYREJS`` equivalent;
    see :func:`_WriteRejectSink`) and are never returned in memory, echoed, or
    logged. Only the reject count and the opaque sink path are surfaced on the
    returned :class:`PostingResult`, faithful to the legacy program, which writes
    rejects to the DALYREJS dataset and DISPLAYs only the count.

    Args:
        session: An already-open synchronous SQLAlchemy session (from
            ``batch.db.GetSyncSession``). Not committed or closed here.
        runDate: Optional business date. When supplied it pins the ``proc_ts``
            date component for reproducible re-runs (and names the reject sink
            generation); when ``None`` the current timestamp is used, matching the
            legacy default.
        rejectDir: Directory that receives the protected reject sink file. When
            ``None`` it defaults to :data:`DEFAULT_REJECT_DIR`. The directory is
            created 0700 and the sink file 0600 (owner-only) on demand, and only
            when at least one transaction is rejected.

    Returns:
        A :class:`PostingResult` with the processed/posted/rejected counters and
        the opaque path to the reject sink (``None`` when there were no rejects).
    """
    LOGGER.info(START_MESSAGE)
    result = PostingResult()
    postingTimestamp = _ResolvePostingTimestamp(runDate)

    # Claim the PENDING daily transactions in a stable key order, mirroring the
    # sequential DALYTRAN read. The set is materialized up front so that the
    # in-loop status flips (PENDING -> POSTED) cannot perturb the iteration, and
    # each row is claimed under a row lock so two concurrent posting runs process
    # DISJOINT rows and never double-post (QA finding M-12).
    pendingTransactions = _ClaimPendingTransactions(session)

    # Reject records are accumulated in a LOCAL list (never on the returned
    # result) and flushed once to the protected sink below, so the PANs they
    # contain never enter the object graph the CLI/orchestration echoes or logs.
    rejectRows: list[str] = []
    for dailyTran in pendingTransactions:
        result.transactionsProcessed += 1
        failReason, failDescription = _ValidateTran(session, dailyTran)
        if failReason == NO_FAILURE:
            failReason, failDescription = _PostTransaction(
                session, dailyTran, postingTimestamp
            )
        if failReason == NO_FAILURE:
            result.transactionsPosted += 1
        else:
            # Build the 430-byte reject record FIRST, from the daily row exactly
            # as it stands, so the 350-byte DALYTRAN image stays byte-identical to
            # the pre-change golden-master output (the reject image never includes
            # the synthetic status column).
            rejectRow = _BuildRejectRow(dailyTran, failReason, failDescription)
            rejectRows.append(rejectRow)
            result.transactionsRejected += 1
            # Then mark the daily row terminally REJECTED. Like POSTED, REJECTED is
            # terminal, so the PENDING driving query excludes it on a re-run and a
            # rejected row is never re-attempted -- keeping the posting layer
            # idempotent (AAP 0.7.6; QA finding F-6). This mirrors the success
            # path's PENDING -> POSTED flip and is flushed here; the caller owns
            # the commit. For a code-109 reject, _PostTransaction posts nothing
            # before returning, so no account or category balance was mutated.
            dailyTran.status = STATUS_REJECTED
            session.flush()

    # Legacy 0300-DALYREJS-OPEN / 2500-WRITE-REJECT-REC / 9300-DALYREJS-CLOSE:
    # write the reject generation exactly once, to a protected sink, only when
    # there are rejects (no empty generation is created).
    if rejectRows:
        result.rejectFilePath = _WriteRejectSink(
            rejectDir if rejectDir is not None else DEFAULT_REJECT_DIR,
            runDate,
            rejectRows,
        )

    LOGGER.info(
        "END OF EXECUTION OF PROGRAM CBTRN02C -- processed=%d posted=%d rejected=%d",
        result.transactionsProcessed,
        result.transactionsPosted,
        result.transactionsRejected,
    )
    return result


def _ClaimPendingTransactions(session: Session) -> list[Transaction]:
    """Atomically claim the PENDING daily transactions for this posting run.

    Selects every ``PENDING`` transaction in ascending ``tran_id`` order (the
    stable key order mirroring the legacy sequential DALYTRAN read) and locks
    each claimed row FOR UPDATE with ``SKIP LOCKED``. Under concurrency this is
    the batch analogue of the CICS/VSAM enclave serialization the mainframe
    relied on: two posting runs executing against the same database each claim a
    DISJOINT set of rows -- a row already locked by the other run is skipped
    rather than waited on -- so no daily transaction is ever posted twice and no
    run blocks the other (QA finding M-12; AAP 0.7.4, 0.7.6). The caller owns the
    unit of work: the locks are held until the caller commits or rolls back.

    Args:
        session: The caller-owned synchronous session for this posting run.

    Returns:
        The claimed, row-locked PENDING transactions, ordered by ``tran_id``.
    """
    statement = (
        select(Transaction)
        .where(Transaction.status == STATUS_PENDING)
        .order_by(Transaction.tran_id)
        .with_for_update(skip_locked=True)
    )
    return list(session.scalars(statement).all())


def _WriteRejectSink(
    rejectDir: Path, runDate: date | None, rejectRows: list[str]
) -> str:
    """Write reject records once to a protected ``DALYREJS``-equivalent sink.

    Reproduces the legacy DALYREJS dataset write (POSTTRAN.jcl: RECFM=F,
    LRECL=430). The directory is created owner-only (0700) and the sink file
    owner-only (0600) so the raw record images -- which carry the unmasked card
    number for reconciliation -- are not readable by other users (QA finding
    #28). The file is a fixed-record dataset: each record is length-normalized to
    exactly ``REJECT_RECORD_LENGTH`` (430) characters, encoded to exactly 430
    single-byte characters, and the records are concatenated with NO delimiter,
    so the physical artifact is exactly ``len(rejectRows) * 430`` bytes (QA
    finding M-13). The sink is opened in binary mode to guarantee no newline
    translation ever perturbs the fixed-record geometry. Nothing is logged here.

    Args:
        rejectDir: Destination directory (created 0700 if missing).
        runDate: Optional business date used to name the generation.
        rejectRows: The 430-character reject records to persist.

    Returns:
        The absolute path of the written sink file, as a string.
    """
    rejectDir.mkdir(parents=True, exist_ok=True)
    os.chmod(rejectDir, REJECT_DIR_MODE)
    sinkPath = rejectDir / _ResolveRejectFilename(runDate)
    fileDescriptor = os.open(
        sinkPath, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, REJECT_FILE_MODE
    )
    with os.fdopen(fileDescriptor, "wb") as sinkFile:
        os.fchmod(fileDescriptor, REJECT_FILE_MODE)
        for rejectRow in rejectRows:
            sinkFile.write(_EncodeFixedRecord(rejectRow))
    return str(sinkPath)


def _EncodeFixedRecord(rejectRow: str) -> bytes:
    """Encode one reject row to exactly ``REJECT_RECORD_LENGTH`` bytes.

    Normalizes the row to exactly 430 characters (right-padding or truncating as
    needed, matching the legacy fixed-record discipline) and encodes it with the
    single-byte :data:`REJECT_RECORD_ENCODING`. Because the record image is pure
    ASCII, one character maps to exactly one byte, so the encoded record is
    exactly 430 bytes and no delimiter is appended. A non-ASCII character (which
    would break the one-byte-per-character invariant and silently corrupt the
    fixed-record layout) raises ``UnicodeEncodeError`` rather than being written.

    Args:
        rejectRow: The reject record image (nominally 430 characters).

    Returns:
        Exactly :data:`REJECT_RECORD_LENGTH` bytes with no trailing delimiter.

    Raises:
        UnicodeEncodeError: If the record contains a non-ASCII character.
        ValueError: If the encoded record is not exactly 430 bytes.
    """
    normalizedRow = rejectRow.ljust(REJECT_RECORD_LENGTH)[:REJECT_RECORD_LENGTH]
    encodedRecord = normalizedRow.encode(REJECT_RECORD_ENCODING)
    if len(encodedRecord) != REJECT_RECORD_LENGTH:
        raise ValueError(
            f"reject record must encode to exactly {REJECT_RECORD_LENGTH} "
            f"bytes, got {len(encodedRecord)}"
        )
    return encodedRecord


def _ResolveRejectFilename(runDate: date | None) -> str:
    """Build the reject-sink filename for this run (generation-relative).

    Uses the business ``runDate`` when supplied (one generation per date, so a
    same-date re-run regenerates the same file), otherwise a full run timestamp
    (a unique generation per run). Mirrors the legacy GDG ``DALYREJS(+1)`` naming
    (AAP 0.7.5).

    Args:
        runDate: Optional business date.

    Returns:
        A filename such as ``dalyrejs_20240115.txt`` (dated) or
        ``dalyrejs_20240115_142530.txt`` (timestamped when no date is supplied).
    """
    if runDate is not None:
        return f"{REJECT_FILE_PREFIX}_{runDate:%Y%m%d}.txt"
    return f"{REJECT_FILE_PREFIX}_{datetime.now():%Y%m%d_%H%M%S}.txt"


def _ResolvePostingTimestamp(runDate: date | None) -> datetime:
    """Resolve the timestamp stamped into ``proc_ts`` for posted rows.

    The legacy ``2000-POST-TRANSACTION`` stamps ``TRAN-PROC-TS`` from the current
    timestamp (``Z-GET-DB2-FORMAT-TIMESTAMP``). When an explicit ``runDate`` is
    supplied, the date component is pinned to it (using the current wall-clock
    time of day) so a re-run for a given business date is reproducible; when it
    is omitted the full current timestamp is used, matching the legacy default.

    Args:
        runDate: Optional business date to pin the processing timestamp to.

    Returns:
        The naive local :class:`~datetime.datetime` to store in ``proc_ts``.
    """
    if runDate is None:
        return datetime.now()
    return datetime.combine(runDate, datetime.now().time())


def _ValidateTran(session: Session, dailyTran: Transaction) -> tuple[int, str]:
    """Validate one daily transaction (port of ``1500-VALIDATE-TRAN``).

    Reproduces the legacy validation ORDER exactly:

    1. ``1500-A-LOOKUP-XREF``: look up the card cross-reference. A miss is reject
       100 ``INVALID CARD NUMBER FOUND`` and validation STOPS here -- the account
       is deliberately NOT checked.
    2. ``1500-B-LOOKUP-ACCT`` (only when the xref was found): look up the owning
       account. A miss is reject 101 ``ACCOUNT RECORD NOT FOUND``; otherwise the
       credit-limit and expiration checks run (see :func:`_CheckAccountLimits`).

    Args:
        session: Open session used for the cross-reference and account lookups.
        dailyTran: The pending daily transaction being validated.

    Returns:
        A ``(failReason, failDescription)`` tuple: ``(0, "")`` when the
        transaction passes, otherwise the reject code and its verbatim UPPERCASE
        description.
    """
    # 1500-A-LOOKUP-XREF: READ XREF-FILE ... INVALID KEY -> 100 (STOP; no acct).
    xref = session.get(CardXref, dailyTran.card_num)
    if xref is None:
        return _RejectPair(PostingRejectCode.INVALID_CARD_NUMBER)

    # 1500-B-LOOKUP-ACCT: READ ACCOUNT-FILE ... INVALID KEY -> 101.
    account = session.get(Account, xref.acct_id)
    if account is None:
        return _RejectPair(PostingRejectCode.ACCOUNT_NOT_FOUND)

    # ADD MORE VALIDATIONS HERE (legacy extension point; no new logic added).
    return _CheckAccountLimits(account, dailyTran)


def _CheckAccountLimits(account: Account, dailyTran: Transaction) -> tuple[int, str]:
    """Apply the credit-limit then expiration checks (part of ``1500-B``).

    Mirrors the two consecutive, INDEPENDENT ``IF`` statements of the legacy
    ``1500-B-LOOKUP-ACCT`` NOT-INVALID-KEY branch. Check A (credit limit) runs
    first; Check B (expiration) runs AFTER it as a SEPARATE ``if`` (never an
    ``elif``) and overwrites the fail reason on failure. Consequently, when BOTH
    checks fail the final reject code is 103 (last-write-wins) -- this ordering
    is preserved exactly for golden-master parity.

    The over-limit temp balance matches ``WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT -
    ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT`` and is computed entirely in
    :class:`~decimal.Decimal`.

    Args:
        account: The owning account resolved during validation.
        dailyTran: The pending daily transaction being validated.

    Returns:
        A ``(failReason, failDescription)`` tuple; ``(0, "")`` when both checks
        pass.
    """
    failReason = NO_FAILURE
    failDescription = ""

    tempBal = account.curr_cyc_credit - account.curr_cyc_debit + dailyTran.tran_amt

    # Check A -- credit limit. Legacy: IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
    # CONTINUE ELSE MOVE 102. The `pass` mirrors the COBOL CONTINUE branch.
    if account.credit_limit >= tempBal:
        pass
    else:
        failReason, failDescription = _RejectPair(
            PostingRejectCode.OVERLIMIT_TRANSACTION
        )

    # Check B -- expiration. SEPARATE `if` (not `elif`); its failure assignment
    # overwrites Check A, so code 103 wins when both A and B fail.
    if _IsWithinExpiration(account, dailyTran):
        pass
    else:
        failReason, failDescription = _RejectPair(PostingRejectCode.ACCOUNT_EXPIRED)

    return failReason, failDescription


def _IsWithinExpiration(account: Account, dailyTran: Transaction) -> bool:
    """Return whether the transaction date is on/before the account expiration.

    Ports the legacy comparison ``ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS(1:10)``
    -- a compare of two ``YYYY-MM-DD`` values, equivalent to a chronological date
    compare. Returns ``True`` when the account has NOT expired relative to the
    transaction's original date. A missing date on either side cannot satisfy the
    ``>=`` relation and yields ``False`` (reject 103), mirroring the legacy
    blank/low-value "compares low" behavior.

    Args:
        account: The owning account (its ``expiration_date`` is a ``date``).
        dailyTran: The pending daily transaction whose ``orig_ts`` supplies the
            original date (via :func:`_OrigDate`).

    Returns:
        ``True`` if within validity, ``False`` if expired or a date is missing.
    """
    expirationDate = account.expiration_date
    origDate = _OrigDate(dailyTran)
    if expirationDate is None or origDate is None:
        return False
    return expirationDate >= origDate


def _OrigDate(dailyTran: Transaction) -> date | None:
    """Derive the transaction original *date* (legacy ``DALYTRAN-ORIG-TS(1:10)``).

    The legacy code compares the account expiration against the first 10
    characters (the ``YYYY-MM-DD`` date portion) of the 26-byte original
    timestamp. The modern ``orig_ts`` column is a timezone-aware ``datetime``, so
    its ``.date()`` is used directly; a text value (defensive) is truncated to 10
    characters and parsed via :func:`app.utils.date_utils.ParseLegacyDate`.

    Args:
        dailyTran: The pending daily transaction.

    Returns:
        The original :class:`~datetime.date`, or ``None`` when ``orig_ts`` is
        absent.
    """
    origTs = dailyTran.orig_ts
    if origTs is None:
        return None
    if isinstance(origTs, datetime):
        return origTs.date()
    return ParseLegacyDate(str(origTs)[:DATE_TEXT_LENGTH])


def _RejectPair(code: PostingRejectCode) -> tuple[int, str]:
    """Return the ``(int code, verbatim description)`` pair for a reject reason.

    Sources the UPPERCASE description from
    :data:`app.core.exceptions.POSTING_REJECT_DESCRIPTIONS`, so this module never
    re-declares any reason string (single source of truth; AAP 0.8.1).

    Args:
        code: The :class:`~app.core.exceptions.PostingRejectCode` member.

    Returns:
        A ``(int, str)`` tuple of the numeric code and its verbatim description.
    """
    return int(code), POSTING_REJECT_DESCRIPTIONS[code]


def _PostTransaction(
    session: Session, dailyTran: Transaction, postingTimestamp: datetime
) -> tuple[int, str]:
    """Post a validated transaction (port of ``2000-POST-TRANSACTION``).

    Runs the three posting steps in the EXACT legacy order: update the
    transaction-category running balance (``2700``), update the owning account's
    balances (``2800``), then promote the daily row to the posted ledger
    (``2900``). The cross-reference and account are re-resolved from the session
    identity map (already loaded during validation, so no new query is issued).

    Code 109 is the defensive equivalent of the legacy ``2800`` ``REWRITE ...
    INVALID KEY``: if the account cannot be resolved at post time (which should
    not happen after a passing validation, but the guard is kept) a reject with
    code 109 ``ACCOUNT RECORD NOT FOUND`` is returned and NOTHING is posted.

    Args:
        session: Open session; flushed but never committed or closed.
        dailyTran: The validated daily transaction to post.
        postingTimestamp: Timestamp stamped into ``proc_ts`` for the posted row.

    Returns:
        ``(0, "")`` on a successful post, or the code-109 reject pair when the
        account-update guard trips.
    """
    xref = session.get(CardXref, dailyTran.card_num)
    account = None if xref is None else session.get(Account, xref.acct_id)
    if account is None:
        # 2800 REWRITE ... INVALID KEY defensive guard -> reject 109, post nothing.
        return _RejectPair(PostingRejectCode.ACCOUNT_UPDATE_FAILED)

    _UpdateTcatbal(session, dailyTran, xref.acct_id)  # 2700-UPDATE-TCATBAL
    _UpdateAccount(session, account, dailyTran)  # 2800-UPDATE-ACCOUNT-REC
    _WriteTransaction(session, dailyTran, postingTimestamp)  # 2900-WRITE-TRANSACTION-FILE
    return NO_FAILURE, ""


def _UpdateTcatbal(session: Session, dailyTran: Transaction, acctId: str) -> None:
    """Upsert the transaction-category running balance (port of ``2700``).

    Key is ``(acctId, DALYTRAN-TYPE-CD, DALYTRAN-CAT-CD)`` -- the composite
    primary key of :class:`~app.models.tran_category_balance.TranCategoryBalance`.
    When no row exists (legacy ``READ ... INVALID KEY`` -> ``2700-A-CREATE``) a new
    row is created with balance ``0 + DALYTRAN-AMT``; otherwise the amount is
    added to the existing balance (``2700-B-UPDATE``). The session is flushed so a
    freshly created row is immediately visible to a later lookup in the same run
    -- mirroring the legacy ``WRITE`` that persisted the record at once (needed
    because the batch session runs with ``autoflush=False``).

    Args:
        session: Open session; flushed here, never committed.
        dailyTran: The transaction supplying the type/category codes and amount.
        acctId: The owning account id (legacy ``XREF-ACCT-ID``), PK part 1.
    """
    typeCd = dailyTran.tran_type_cd
    catCd = dailyTran.tran_cat_cd
    tcatbal = session.get(TranCategoryBalance, (acctId, typeCd, catCd))
    if tcatbal is None:
        newRow = TranCategoryBalance(
            acct_id=acctId,
            tran_type_cd=typeCd,
            tran_cat_cd=catCd,
            balance=Decimal("0") + dailyTran.tran_amt,
        )
        session.add(newRow)
    else:
        tcatbal.balance = tcatbal.balance + dailyTran.tran_amt
    session.flush()


def _UpdateAccount(session: Session, account: Account, dailyTran: Transaction) -> None:
    """Update the account balances for a posted transaction (port of ``2800``).

    Applies the legacy balance-update arithmetic in the EXACT order required for
    parity:

    1. ``ADD DALYTRAN-AMT TO ACCT-CURR-BAL``.
    2. ``IF DALYTRAN-AMT >= 0`` add it to ``ACCT-CURR-CYC-CREDIT`` ELSE add it to
       ``ACCT-CURR-CYC-DEBIT``.
    3. Flush so the UPDATE is emitted (the account was loaded via this session).

    All arithmetic is performed in :class:`~decimal.Decimal`.

    Args:
        session: Open session; flushed here, never committed.
        account: The owning account (mutated in place).
        dailyTran: The transaction supplying the signed amount.
    """
    tranAmt = dailyTran.tran_amt
    account.curr_bal = account.curr_bal + tranAmt
    if tranAmt >= 0:
        account.curr_cyc_credit = account.curr_cyc_credit + tranAmt
    else:
        account.curr_cyc_debit = account.curr_cyc_debit + tranAmt
    session.flush()


def _WriteTransaction(
    session: Session, dailyTran: Transaction, postingTimestamp: datetime
) -> None:
    """Promote the daily row to the posted ledger (port of ``2900``).

    Because the daily and posted transactions share the single ``transactions``
    table (distinguished by ``status``; AAP 0.7.5), "writing" the posted
    transaction is done by flipping the daily row's status to ``POSTED`` and
    stamping ``proc_ts`` (legacy ``TRAN-PROC-TS = current timestamp``). No new
    ``tran_id`` is fabricated -- the daily record already carries every field.
    The session is flushed; it is never committed here (the caller owns commit).

    Args:
        session: Open session; flushed here, never committed.
        dailyTran: The daily transaction being promoted (mutated in place).
        postingTimestamp: The processing timestamp to stamp into ``proc_ts``.
    """
    dailyTran.status = STATUS_POSTED
    dailyTran.proc_ts = postingTimestamp
    session.flush()


def _BuildRejectRow(dailyTran: Transaction, code: int, description: str) -> str:
    """Build one 430-byte reject record (port of ``2500-WRITE-REJECT-REC``).

    Concatenates the 350-byte daily-transaction record image
    (:func:`_FormatDailyRecord`) with the 80-byte validation trailer
    (:func:`app.core.exceptions.FormatValidationTrailer`: a 4-digit zero-padded
    reason code plus the description left-justified/space-padded to 76). The
    result is forced to exactly
    :data:`app.core.exceptions.REJECT_RECORD_LENGTH` (430) characters
    defensively, without using a bare ``assert`` for control flow.

    Args:
        dailyTran: The rejected daily transaction (its raw record image).
        code: The reject reason code (100/101/102/103/109).
        description: The verbatim UPPERCASE reject description.

    Returns:
        The 430-character reject-record string.
    """
    data350 = _FormatDailyRecord(dailyTran)
    trailer80 = FormatValidationTrailer(code, description)
    rejectRow = data350 + trailer80
    if len(rejectRow) != REJECT_RECORD_LENGTH:
        rejectRow = rejectRow.ljust(REJECT_RECORD_LENGTH)[:REJECT_RECORD_LENGTH]
    return rejectRow


def _FormatDailyRecord(dailyTran: Transaction) -> str:
    """Render the 350-byte ``REJECT-TRAN-DATA`` image of a daily transaction.

    Re-encodes the ORM row back into its fixed-width ``DALYTRAN-RECORD`` byte
    image (copybook ``CVTRA06Y``), field-by-field in copybook order and to the
    exact copybook widths, so the reject data reconciles against the mainframe
    record. Text fields are left-justified/space-padded; numeric identifier
    fields are right-justified/zero-padded; the amount is a signed zoned-decimal
    field; the two timestamps use the 26-byte legacy layout; the trailing FILLER
    is spaces. The result is forced to exactly
    :data:`app.core.exceptions.REJECT_TRAN_DATA_LENGTH` (350) characters.

    The card number is deliberately NOT masked here: this is the raw record image
    used for reconciliation (the AAP 0.7.8 masking rule applies to UI and log
    output, not to this reject sink).

    Args:
        dailyTran: The daily transaction to render.

    Returns:
        The 350-character record-image string.
    """
    parts = [
        _PadText(dailyTran.tran_id, TRAN_ID_WIDTH),
        _PadText(dailyTran.tran_type_cd, TRAN_TYPE_CD_WIDTH),
        _PadNumeric(dailyTran.tran_cat_cd, TRAN_CAT_CD_WIDTH),
        _PadText(dailyTran.tran_source, TRAN_SOURCE_WIDTH),
        _PadText(dailyTran.tran_desc, TRAN_DESC_WIDTH),
        _EncodeAmount(dailyTran.tran_amt),
        _PadNumeric(dailyTran.merchant_id, MERCHANT_ID_WIDTH),
        _PadText(dailyTran.merchant_name, MERCHANT_NAME_WIDTH),
        _PadText(dailyTran.merchant_city, MERCHANT_CITY_WIDTH),
        _PadText(dailyTran.merchant_zip, MERCHANT_ZIP_WIDTH),
        _PadText(dailyTran.card_num, CARD_NUM_WIDTH),
        _PadTimestamp(dailyTran.orig_ts),
        _PadTimestamp(dailyTran.proc_ts),
        " " * DALYTRAN_FILLER_WIDTH,
    ]
    record = "".join(parts)
    return record.ljust(REJECT_TRAN_DATA_LENGTH)[:REJECT_TRAN_DATA_LENGTH]


def _PadText(value: str | None, width: int) -> str:
    """Render a COBOL ``PIC X(width)`` text field: left-justified, space-padded.

    ``None`` becomes all spaces; values longer than ``width`` are truncated.

    Args:
        value: The field value (``None`` -> spaces).
        width: The fixed field width in characters.

    Returns:
        A string of exactly ``width`` characters.
    """
    text = "" if value is None else str(value)
    return text.ljust(width)[:width]


def _PadNumeric(value: str | None, width: int) -> str:
    """Render a COBOL ``PIC 9(width)`` field: right-justified, zero-padded.

    Numeric DISPLAY identifier fields (for example ``CAT-CD`` and
    ``MERCHANT-ID``) are stored right-justified with leading zeros. ``None``
    becomes all zeros; a value longer than ``width`` keeps its low-order
    ``width`` digits.

    Args:
        value: The field value (``None`` -> zeros).
        width: The fixed field width in characters.

    Returns:
        A string of exactly ``width`` characters.
    """
    text = "" if value is None else str(value)
    return text.zfill(width)[-width:]


def _EncodeAmount(value: Decimal) -> str:
    """Render the ``DALYTRAN-AMT PIC S9(09)V99`` signed zoned-decimal field.

    Delegates to :func:`app.utils.decimal_utils.EncodeZonedDecimal` so the amount
    is rewritten to the exact 11-character overpunch byte image the mainframe
    would have stored (implied decimal point; sign carried in the final byte).

    Args:
        value: The transaction amount as an exact :class:`~decimal.Decimal`.

    Returns:
        The 11-character signed zoned-decimal string.
    """
    return EncodeZonedDecimal(value, TRAN_AMT_WIDTH, MONEY_SCALE)


def _PadTimestamp(value: datetime | None) -> str:
    """Render a 26-byte legacy timestamp field (``PIC X(26)``).

    A :class:`~datetime.datetime` is formatted via
    :func:`app.utils.date_utils.FormatLegacyTimestamp`; ``None`` (for example an
    unposted daily row's blank ``proc_ts``) becomes 26 spaces. The result is
    forced to exactly :data:`TIMESTAMP_WIDTH` (26) characters.

    Args:
        value: The timestamp to render, or ``None`` for a blank field.

    Returns:
        A string of exactly 26 characters.
    """
    if value is None:
        return " " * TIMESTAMP_WIDTH
    if isinstance(value, datetime):
        return FormatLegacyTimestamp(value).ljust(TIMESTAMP_WIDTH)[:TIMESTAMP_WIDTH]
    return str(value).ljust(TIMESTAMP_WIDTH)[:TIMESTAMP_WIDTH]


# Public API of this module: the posting entry point and its result object.
# Everything else is a module-private helper (leading underscore).
__all__ = ["PostTransactions", "PostingResult"]
