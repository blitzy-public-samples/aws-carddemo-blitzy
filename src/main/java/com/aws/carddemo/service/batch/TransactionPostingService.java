/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.service.batch;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.domain.id.TransactionCategoryBalanceId;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.DailyTransactionRepository;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.CobolStringUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Batch service that reproduces &mdash; with 100% behavioral parity (Agent Action Plan &sect;0.1.1,
 * &sect;0.6.7, &sect;0.7.1) &mdash; the legacy COBOL batch program {@code CBTRN02C} (source {@code
 * legacy/app/cbl/CBTRN02C.cbl}), the daily-transaction <em>posting</em> archetype and the single
 * most parity-critical batch program in the migration.
 *
 * <p><strong>What {@code CBTRN02C} does.</strong> It reads every record of the sequential
 * daily-transaction file ({@code DALYTRAN}, copybook {@code CVTRA06Y}) and, for each one, validates
 * it against the card cross-reference and account master, then either <em>posts</em> the
 * transaction (updating the transaction-category-balance store, the account master, and writing the
 * posted transaction to the transaction master) or <em>rejects</em> it (writing a fixed-width
 * reject record to {@code DALYREJS}). At end-of-run it reports the processed and rejected counts
 * and sets {@code RETURN-CODE} to {@code 4} when any record was rejected (otherwise {@code 0}).
 *
 * <p><strong>Files and repositories.</strong> The COBOL program opens six files; this service
 * injects the five repositories whose stores it reads or writes and models the sixth ({@code
 * DALYREJS}, an OUTPUT file) as an injected {@link Consumer} sink:
 *
 * <ul>
 *   <li>{@code DALYTRAN} (sequential INPUT) &rarr; {@link DailyTransactionRepository} (cursor in
 *       primary-key order).
 *   <li>{@code TRANSACT} (indexed OUTPUT, {@code WRITE}) &rarr; {@link TransactionRepository}.
 *   <li>{@code XREFFILE} (indexed INPUT, keyed read) &rarr; {@link CardXrefRepository}.
 *   <li>{@code ACCTFILE} (indexed I-O, keyed read + rewrite) &rarr; {@link AccountRepository}.
 *   <li>{@code TCATBALF} (indexed I-O, keyed read + find-or-create upsert) &rarr; {@link
 *       TransactionCategoryBalanceRepository}.
 *   <li>{@code DALYREJS} (sequential OUTPUT) &rarr; the {@link Consumer Consumer&lt;String&gt;}
 *       {@code rejectSink} passed to {@link #run(Consumer)}; the physical 430-byte writer lives in
 *       {@code com.aws.carddemo.batch} (AAP &sect;0.6.6). This service builds each complete
 *       430-character record and hands it to the sink; it never opens or writes a file directly.
 * </ul>
 *
 * <p><strong>Decimal fidelity (AAP &sect;0.6.1).</strong> Every monetary value &mdash; the
 * transaction amount, the category balance, and all five account balances &mdash; is carried as
 * {@link BigDecimal} at scale {@code 2}, never {@code float}/{@code double}. {@code CBTRN02C}
 * performs only additions and subtractions (no division, hence no rounding phrase), so the additive
 * arithmetic preserves scale {@code 2} exactly.
 *
 * <p><strong>FILE STATUS / abend mapping (AAP &sect;0.6.4, &sect;0.6.6).</strong> Status {@code
 * '00'} is a normal I/O; {@code '10'} is end-of-file on the sequential read (loop termination, not
 * an error); {@code '23'} (record not found) is handled either as a reject reason (the card /
 * account lookups) or as a find-or-create upsert (the category balance) and is <em>never</em> an
 * abend. Any other condition surfaces as a Spring {@link DataAccessException} and is translated,
 * only in the paragraphs that contain an explicit COBOL abend, into an {@link IoStatusException}
 * (the {@code 9910-DISPLAY-IO-STATUS} + {@code 9999-ABEND-PROGRAM} path) so the externally
 * observable status line is preserved and the Spring Batch step fails. A {@code
 * RecordNotFoundException} is never thrown (that type is reserved for the online layer).
 *
 * <p><strong>Execution model.</strong> Mirroring the single-threaded COBOL run unit, the per-run
 * working state (counters, validation flags, the sequential cursor and the current record handles)
 * is held in instance fields that reproduce the program's {@code WORKING-STORAGE}. {@link
 * #run(Consumer)} re-initializes that state on entry so the bean may be reused for successive runs,
 * but a single invocation constitutes one run unit and the bean is not safe for concurrent use. Per
 * AAP &sect;0.4.1 and the file conventions, no Spring Batch type is referenced here; a future
 * {@code TransactionPostingJobConfig} in {@code com.aws.carddemo.batch} wires {@link
 * #run(Consumer)} (or {@link #processOneTransaction(DailyTransaction)}) into a chunk-oriented step
 * and owns the {@code @Transactional} chunk boundary and the physical {@code DALYREJS} writer.
 *
 * <p><strong>COBOL paragraph &rarr; Java method traceability</strong> (AAP &sect;0.6.7). This
 * service is the reference example for {@code docs/traceability-matrix.md}; every numbered
 * paragraph maps to exactly one method, invoked in the original {@code PERFORM} order:
 *
 * <table border="1">
 *   <caption>CBTRN02C paragraph &rarr; Java method</caption>
 *   <tr><th>COBOL paragraph</th><th>Java method</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} main</td><td>{@link #run(Consumer)}</td></tr>
 *   <tr><td>{@code 0000-DALYTRAN-OPEN}</td><td>{@link #openDalytran()}</td></tr>
 *   <tr><td>{@code 0100-TRANFILE-OPEN}</td><td>{@link #openTranfile()}</td></tr>
 *   <tr><td>{@code 0200-XREFFILE-OPEN}</td><td>{@link #openXreffile()}</td></tr>
 *   <tr><td>{@code 0300-DALYREJS-OPEN}</td><td>{@link #openDalyrejs()}</td></tr>
 *   <tr><td>{@code 0400-ACCTFILE-OPEN}</td><td>{@link #openAcctfile()}</td></tr>
 *   <tr><td>{@code 0500-TCATBALF-OPEN}</td><td>{@link #openTcatbalf()}</td></tr>
 *   <tr><td>{@code 1000-DALYTRAN-GET-NEXT}</td><td>{@link #dalytranGetNext()}</td></tr>
 *   <tr><td>{@code 1500-VALIDATE-TRAN}</td><td>{@link #validateTran()}</td></tr>
 *   <tr><td>{@code 1500-A-LOOKUP-XREF}</td><td>{@link #lookupXref()}</td></tr>
 *   <tr><td>{@code 1500-B-LOOKUP-ACCT}</td><td>{@link #lookupAcct()}</td></tr>
 *   <tr><td>{@code 2000-POST-TRANSACTION}</td><td>{@link #postTransaction()}</td></tr>
 *   <tr><td>{@code 2500-WRITE-REJECT-REC}</td><td>{@link #writeRejectRec(Consumer)}</td></tr>
 *   <tr><td>{@code 2700-UPDATE-TCATBAL} (+{@code 2700-A}/{@code 2700-B})</td>
 *       <td>{@link #updateTcatbal()}</td></tr>
 *   <tr><td>{@code 2800-UPDATE-ACCOUNT-REC}</td><td>{@link #updateAccountRec()}</td></tr>
 *   <tr><td>{@code 2900-WRITE-TRANSACTION-FILE}</td><td>{@link #writeTransactionFile()}</td></tr>
 *   <tr><td>{@code Z-GET-DB2-FORMAT-TIMESTAMP}</td><td>{@link #getDb2FormatTimestamp()}</td></tr>
 *   <tr><td>{@code 9000-DALYTRAN-CLOSE}</td><td>{@link #closeDalytran()}</td></tr>
 *   <tr><td>{@code 9100-TRANFILE-CLOSE}</td><td>{@link #closeTranfile()}</td></tr>
 *   <tr><td>{@code 9200-XREFFILE-CLOSE}</td><td>{@link #closeXreffile()}</td></tr>
 *   <tr><td>{@code 9300-DALYREJS-CLOSE}</td><td>{@link #closeDalyrejs()}</td></tr>
 *   <tr><td>{@code 9400-ACCTFILE-CLOSE}</td><td>{@link #closeAcctfile()}</td></tr>
 *   <tr><td>{@code 9500-TCATBALF-CLOSE}</td><td>{@link #closeTcatbalf()}</td></tr>
 *   <tr><td>{@code 9910-DISPLAY-IO-STATUS} + {@code 9999-ABEND-PROGRAM}</td>
 *       <td>{@link #abend(String, String, DataAccessException)}</td></tr>
 * </table>
 */
@Service
public class TransactionPostingService {

  /** SLF4J logger; the COBOL {@code DISPLAY} verb maps to {@code LOG} output. */
  private static final Logger LOG = LoggerFactory.getLogger(TransactionPostingService.class);

  /**
   * Synthetic two-byte FILE STATUS used when translating a Spring {@link DataAccessException} into
   * an {@link IoStatusException}. The JPA model has no native COBOL FILE STATUS byte pair, so the
   * non-{@code '00'}/{@code '10'}/{@code '23'} "other status" abend branch ({@code
   * 9910-DISPLAY-IO-STATUS} + {@code 9999-ABEND-PROGRAM}) is represented with {@code "99"},
   * matching the abend status convention used across the migrated batch services.
   */
  private static final String ABEND_FILE_STATUS = "99";

  /**
   * Positive trailing-overpunch sign characters indexed by units digit: index {@code 0} is {@code
   * '{'} (=0), index {@code 1} is {@code 'A'} (=1) &hellip; index {@code 9} is {@code 'I'} (=9).
   * This is the exact inverse of the seed decoder so the rebuilt {@code DALYTRAN} image is
   * byte-faithful for golden-file parity tests (AAP &sect;0.6.6).
   */
  private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

  /**
   * Negative trailing-overpunch sign characters indexed by units digit: index {@code 0} is {@code
   * '}'} (=0), index {@code 1} is {@code 'J'} (=1) &hellip; index {@code 9} is {@code 'R'} (=9).
   */
  private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

  /** Total digit count of {@code DALYTRAN-AMT PIC S9(09)V99}: 9 integer + 2 fraction = 11. */
  private static final int AMOUNT_DIGITS = 11;

  /** Width of the 350-byte {@code DALYTRAN-RECORD} image ({@code FD-REJECT-RECORD PIC X(350)}). */
  private static final int DALYTRAN_IMAGE_WIDTH = 350;

  /** Width of the complete {@code DALYREJS} record: 350-byte image + 80-byte trailer. */
  private static final int REJECT_RECORD_WIDTH = 430;

  /** Logical DD name of the sequential daily-transaction input file ({@code DALYTRAN}). */
  private static final String DD_DALYTRAN = "DALYTRAN";

  /** Logical DD name of the transaction master output file ({@code TRANSACT}). */
  private static final String DD_TRANSACT = "TRANSACT";

  /** Logical DD name of the transaction-category-balance file ({@code TCATBALF}). */
  private static final String DD_TCATBALF = "TCATBALF";

  /** Logical DD name of the account master file ({@code ACCTFILE}). */
  private static final String DD_ACCTFILE = "ACCTFILE";

  /** Repository backing the sequential {@code DALYTRAN-FILE} (copybook {@code CVTRA06Y}). */
  private final DailyTransactionRepository dailyTransactionRepository;

  /** Repository backing the indexed {@code TRANSACT-FILE} output (copybook {@code CVTRA05Y}). */
  private final TransactionRepository transactionRepository;

  /** Repository backing the keyed {@code XREF-FILE} (copybook {@code CVACT03Y}). */
  private final CardXrefRepository cardXrefRepository;

  /** Repository backing the I-O {@code ACCOUNT-FILE} (copybook {@code CVACT01Y}). */
  private final AccountRepository accountRepository;

  /** Repository backing the I-O {@code TCATBAL-FILE} (copybook {@code CVTRA01Y}). */
  private final TransactionCategoryBalanceRepository tcatbalRepository;

  /**
   * Time source for {@code Z-GET-DB2-FORMAT-TIMESTAMP} ({@code FUNCTION CURRENT-DATE}). Injectable
   * so golden-file parity tests can pin a deterministic processing timestamp; defaults to {@link
   * Clock#systemDefaultZone()} in production.
   */
  private final Clock clock;

  // -----------------------------------------------------------------------------------------------
  // Per-run-unit working state mirroring the COBOL WORKING-STORAGE SECTION. Reset by run(...) on
  // entry; one run() invocation == one COBOL run unit. Not safe for concurrent invocation.
  // -----------------------------------------------------------------------------------------------

  /** Sequential cursor over the daily-transaction store (the {@code DALYTRAN-FILE} read order). */
  private Iterator<DailyTransaction> dalytranCursor;

  /**
   * The {@code END-OF-FILE} switch; set once the sequential daily-transaction read is exhausted.
   */
  private boolean endOfFile;

  /** {@code WS-TRANSACTION-COUNT PIC 9(09)} &mdash; total daily transactions read this run. */
  private long transactionCount;

  /** {@code WS-REJECT-COUNT PIC 9(09)} &mdash; total daily transactions rejected this run. */
  private long rejectCount;

  /**
   * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} &mdash; {@code 0} when the current record is valid.
   */
  private int validationFailReason;

  /**
   * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} &mdash; reason text for the current record.
   */
  private String validationFailReasonDesc;

  /** Current {@code DALYTRAN-RECORD} (CVTRA06Y) under validation/posting. */
  private DailyTransaction dalytran;

  /** Current {@code CARD-XREF-RECORD} (CVACT03Y) resolved by {@link #lookupXref()}. */
  private CardXref currentXref;

  /** Current {@code ACCOUNT-RECORD} (CVACT01Y) resolved by {@link #lookupAcct()}. */
  private Account currentAccount;

  /** Current {@code TRAN-RECORD} (CVTRA05Y) built by {@link #postTransaction()}. */
  private Transaction currentTransaction;

  /**
   * Creates the posting service for Spring dependency injection, using the system default-zone
   * {@link Clock} as the {@code FUNCTION CURRENT-DATE} time source. Spring auto-wires this
   * constructor; it delegates to the {@link #TransactionPostingService(DailyTransactionRepository,
   * TransactionRepository, CardXrefRepository, AccountRepository,
   * TransactionCategoryBalanceRepository, Clock) clock-injecting constructor}.
   *
   * @param dailyTransactionRepository repository for the sequential daily-transaction input store
   * @param transactionRepository repository for the transaction master output store
   * @param cardXrefRepository repository for the card cross-reference store (keyed by card number)
   * @param accountRepository repository for the account master store (keyed by account id)
   * @param tcatbalRepository repository for the transaction-category-balance store (composite key)
   */
  @Autowired
  public TransactionPostingService(
      DailyTransactionRepository dailyTransactionRepository,
      TransactionRepository transactionRepository,
      CardXrefRepository cardXrefRepository,
      AccountRepository accountRepository,
      TransactionCategoryBalanceRepository tcatbalRepository) {
    this(
        dailyTransactionRepository,
        transactionRepository,
        cardXrefRepository,
        accountRepository,
        tcatbalRepository,
        Clock.systemDefaultZone());
  }

  /**
   * Creates the posting service with an explicit {@link Clock} time source. Package-private and
   * intended for golden-file parity tests, which inject a fixed clock so the computed {@code
   * TRAN-PROC-TS} ({@code Z-GET-DB2-FORMAT-TIMESTAMP}) is deterministic.
   *
   * @param dailyTransactionRepository repository for the sequential daily-transaction input store
   * @param transactionRepository repository for the transaction master output store
   * @param cardXrefRepository repository for the card cross-reference store (keyed by card number)
   * @param accountRepository repository for the account master store (keyed by account id)
   * @param tcatbalRepository repository for the transaction-category-balance store (composite key)
   * @param clock the time source backing {@link #getDb2FormatTimestamp()}; must not be {@code null}
   */
  TransactionPostingService(
      DailyTransactionRepository dailyTransactionRepository,
      TransactionRepository transactionRepository,
      CardXrefRepository cardXrefRepository,
      AccountRepository accountRepository,
      TransactionCategoryBalanceRepository tcatbalRepository,
      Clock clock) {
    this.dailyTransactionRepository = dailyTransactionRepository;
    this.transactionRepository = transactionRepository;
    this.cardXrefRepository = cardXrefRepository;
    this.accountRepository = accountRepository;
    this.tcatbalRepository = tcatbalRepository;
    this.clock = clock;
  }

  // -----------------------------------------------------------------------------------------------
  // Orchestration entry points (PROCEDURE DIVISION main, L193-L234)
  // -----------------------------------------------------------------------------------------------

  /**
   * Runs the daily-transaction posting job, reproducing the {@code CBTRN02C} {@code PROCEDURE
   * DIVISION} main flow exactly (open all files; read-validate-post/reject each daily transaction;
   * close all files; report counts; set the return code).
   *
   * <p>The {@code DALYREJS} OUTPUT file is modeled as {@code rejectSink}: each rejected record is
   * built here as a complete 430-character {@code DALYREJS} image and handed to the sink, which (in
   * {@code com.aws.carddemo.batch}) performs the physical write. This method never opens or writes
   * a file directly.
   *
   * @param rejectSink consumer of fully-formatted 430-character reject records (one per rejected
   *     daily transaction); must not be {@code null}
   * @return {@code 4} when at least one daily transaction was rejected, otherwise {@code 0} &mdash;
   *     the value the legacy program moves to {@code RETURN-CODE} and the Spring Batch step exit
   *     code
   */
  public int run(Consumer<String> rejectSink) { // <- CBTRN02C PROCEDURE DIVISION (main)
    LOG.info("START OF EXECUTION OF PROGRAM CBTRN02C");

    resetRunState();

    // PERFORM the six file opens in declared order (0000-0500).
    openDalytran();
    openTranfile();
    openXreffile();
    openDalyrejs();
    openAcctfile();
    openTcatbalf();

    // PERFORM UNTIL END-OF-FILE = 'Y' with the COBOL read-ahead idiom preserved verbatim.
    while (!endOfFile) {
      if (!endOfFile) {
        dalytranGetNext();
        if (!endOfFile) {
          transactionCount++; // ADD 1 TO WS-TRANSACTION-COUNT
          validationFailReason = 0; // MOVE 0 TO WS-VALIDATION-FAIL-REASON
          validationFailReasonDesc = ""; // MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC
          validateTran();
          if (validationFailReason == 0) {
            postTransaction();
          } else {
            rejectCount++; // ADD 1 TO WS-REJECT-COUNT
            writeRejectRec(rejectSink);
          }
        }
      }
    }

    // PERFORM the six file closes in declared order (9000-9500).
    closeDalytran();
    closeTranfile();
    closeXreffile();
    closeDalyrejs();
    closeAcctfile();
    closeTcatbalf();

    LOG.info("TRANSACTIONS PROCESSED :" + transactionCount);
    LOG.info("TRANSACTIONS REJECTED  :" + rejectCount);

    int returnCode = rejectCount > 0 ? 4 : 0; // IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE

    LOG.info("END OF EXECUTION OF PROGRAM CBTRN02C");
    return returnCode;
  }

  /**
   * Convenience overload of {@link #run(Consumer)} that collects every reject record into an
   * in-memory list rather than streaming them to an external sink. Useful for tests and for callers
   * that wish to inspect or persist the rejects after the run completes.
   *
   * @return the list of fully-formatted 430-character reject records produced during the run, in
   *     the order they were rejected (empty when no record was rejected)
   */
  public List<String> run() {
    List<String> rejects = new ArrayList<>();
    run(rejects::add);
    return rejects;
  }

  /**
   * Validates and (if valid) posts a single daily transaction, returning the outcome rather than
   * mutating run-level counters. This is the per-record entry point a chunk-oriented Spring Batch
   * {@code ItemProcessor} (in {@code com.aws.carddemo.batch}) invokes; it reproduces exactly the
   * per-record body of the {@code CBTRN02C} main loop (reset reason, {@code 1500-VALIDATE-TRAN},
   * then {@code 2000-POST-TRANSACTION} on success or build the {@code 2500} reject image on
   * failure) for one record.
   *
   * @param tran the daily transaction to validate and post; must not be {@code null}
   * @return a {@link PostingResult} describing whether the record was posted and, when rejected,
   *     the 430-character reject record and the numeric reason code
   */
  public PostingResult processOneTransaction(DailyTransaction tran) {
    this.dalytran = tran;
    this.currentXref = null;
    this.currentAccount = null;
    this.currentTransaction = null;
    validationFailReason = 0; // MOVE 0 TO WS-VALIDATION-FAIL-REASON
    validationFailReasonDesc = ""; // MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC

    validateTran();
    if (validationFailReason == 0) {
      postTransaction();
      return PostingResult.ofPosted();
    }
    return PostingResult.ofRejected(buildRejectRecord(), validationFailReason);
  }

  /**
   * Immutable outcome of {@link #processOneTransaction(DailyTransaction)}.
   *
   * @param posted {@code true} when the transaction was posted; {@code false} when rejected
   * @param rejectRecord the 430-character {@code DALYREJS} record when {@code posted} is {@code
   *     false}; {@code null} when {@code posted} is {@code true}
   * @param reason the {@code WS-VALIDATION-FAIL-REASON} code ({@code 0} when posted; {@code 100},
   *     {@code 101}, {@code 102}, {@code 103} or {@code 109} when rejected)
   */
  public static record PostingResult(boolean posted, String rejectRecord, int reason) {

    /**
     * Creates a "posted" result (no reject record, reason {@code 0}). Named {@code ofPosted} rather
     * than {@code posted} to avoid colliding with the record's auto-generated {@link #posted()}
     * accessor.
     *
     * @return a result indicating the transaction was posted
     */
    public static PostingResult ofPosted() {
      return new PostingResult(true, null, 0);
    }

    /**
     * Creates a "rejected" result carrying the formatted reject record and its reason code.
     *
     * @param rejectRecord the 430-character {@code DALYREJS} record
     * @param reason the numeric {@code WS-VALIDATION-FAIL-REASON} code
     * @return a result indicating the transaction was rejected
     */
    public static PostingResult ofRejected(String rejectRecord, int reason) {
      return new PostingResult(false, rejectRecord, reason);
    }
  }

  /**
   * Re-initializes all per-run working state to its COBOL {@code WORKING-STORAGE} initial values so
   * the bean can be reused for successive runs. Mirrors the implicit {@code VALUE} clauses (counts
   * {@code 0}, switches {@code 'N'}, reason {@code 0}, description {@code SPACES}).
   */
  private void resetRunState() {
    dalytranCursor = null;
    endOfFile = false;
    transactionCount = 0L;
    rejectCount = 0L;
    validationFailReason = 0;
    validationFailReasonDesc = "";
    dalytran = null;
    currentXref = null;
    currentAccount = null;
    currentTransaction = null;
  }

  // -----------------------------------------------------------------------------------------------
  // File opens (0000-0500). DALYTRAN is a sequential INPUT file and needs a primary-key-ordered
  // cursor; the remaining files are keyed inputs or write-on-demand outputs that need no open-time
  // action under JPA, so their opens are documented no-ops retained for 1:1 paragraph traceability.
  // -----------------------------------------------------------------------------------------------

  /**
   * Opens the sequential daily-transaction input by positioning a primary-key-ordered cursor over
   * the {@code DALYTRAN} store, reproducing {@code OPEN INPUT DALYTRAN-FILE}. The {@code
   * DALYTRAN-RECORD} key is {@code DALYTRAN-ID}, so the cursor ascends by {@code dalytranId} to
   * preserve the legacy sequential read order. An unexpected repository failure maps to the {@code
   * CBTRN02C} OPEN abend path.
   */
  private void openDalytran() { // <- CBTRN02C 0000-DALYTRAN-OPEN
    try {
      dalytranCursor =
          dailyTransactionRepository.findAll(Sort.by(Sort.Direction.ASC, "dalytranId")).iterator();
      endOfFile = false;
    } catch (DataAccessException ex) {
      throw abend(DD_DALYTRAN, "OPEN", ex);
    }
  }

  /**
   * Opens the transaction master output, reproducing {@code OPEN OUTPUT TRANSACT-FILE}. The store
   * is written record-by-record via {@link TransactionRepository#save(Object)} in {@link
   * #writeTransactionFile()}, so no open-time action is required; retained for traceability.
   */
  private void openTranfile() { // <- CBTRN02C 0100-TRANFILE-OPEN
    // No-op: indexed OUTPUT file; rows are persisted on demand by writeTransactionFile().
  }

  /**
   * Opens the card cross-reference input, reproducing {@code OPEN INPUT XREF-FILE}. The store is
   * read by key via {@link CardXrefRepository#findById(Object)} in {@link #lookupXref()}, so no
   * open-time action is required; retained for traceability.
   */
  private void openXreffile() { // <- CBTRN02C 0200-XREFFILE-OPEN
    // No-op: keyed INPUT file; rows are read on demand by lookupXref().
  }

  /**
   * Opens the daily-reject output, reproducing {@code OPEN OUTPUT DALYREJS-FILE}. The physical
   * {@code DALYREJS} writer is owned by {@code com.aws.carddemo.batch} and supplied to {@link
   * #run(Consumer)} as the reject sink, so no open-time action is required here; retained for
   * traceability.
   */
  private void openDalyrejs() { // <- CBTRN02C 0300-DALYREJS-OPEN
    // No-op: OUTPUT file owned by the batch layer; records are emitted via the reject sink.
  }

  /**
   * Opens the account master for update, reproducing {@code OPEN I-O ACCOUNT-FILE}. The store is
   * read and rewritten by key via {@link AccountRepository} in {@link #lookupAcct()} / {@link
   * #updateAccountRec()}, so no open-time action is required; retained for traceability.
   */
  private void openAcctfile() { // <- CBTRN02C 0400-ACCTFILE-OPEN
    // No-op: keyed I-O file; rows are read and rewritten on demand.
  }

  /**
   * Opens the transaction-category-balance store for update, reproducing {@code OPEN I-O
   * TCATBAL-FILE}. The store is read and upserted by composite key via {@link
   * TransactionCategoryBalanceRepository} in {@link #updateTcatbal()}, so no open-time action is
   * required; retained for traceability.
   */
  private void openTcatbalf() { // <- CBTRN02C 0500-TCATBALF-OPEN
    // No-op: keyed I-O file; rows are read and upserted on demand.
  }

  // -----------------------------------------------------------------------------------------------
  // Sequential read (1000-DALYTRAN-GET-NEXT)
  // -----------------------------------------------------------------------------------------------

  /**
   * Reads the next daily-transaction record, reproducing {@code 1000-DALYTRAN-GET-NEXT}. When the
   * cursor is exhausted this is FILE STATUS {@code '10'} (end-of-file): {@code END-OF-FILE} is set
   * to {@code 'Y'} and the current record is left unchanged &mdash; this is loop termination, not
   * an error. Any other repository failure is the {@code CBTRN02C} READ abend path.
   */
  private void dalytranGetNext() { // <- CBTRN02C 1000-DALYTRAN-GET-NEXT
    try {
      if (dalytranCursor != null && dalytranCursor.hasNext()) {
        dalytran = dalytranCursor.next(); // FILE STATUS '00'
      } else {
        endOfFile = true; // FILE STATUS '10' (end-of-file)
      }
    } catch (DataAccessException ex) {
      throw abend(DD_DALYTRAN, "READ", ex);
    }
  }

  // -----------------------------------------------------------------------------------------------
  // File closes (9000-9500). Under JPA there is no handle to release, so each close is a documented
  // no-op retained for 1:1 paragraph traceability with CBTRN02C.
  // -----------------------------------------------------------------------------------------------

  /** Closes the daily-transaction input, reproducing {@code CLOSE DALYTRAN-FILE} (no-op). */
  private void closeDalytran() { // <- CBTRN02C 9000-DALYTRAN-CLOSE
    // No-op: no JPA resource to release.
  }

  /** Closes the transaction master output, reproducing {@code CLOSE TRANSACT-FILE} (no-op). */
  private void closeTranfile() { // <- CBTRN02C 9100-TRANFILE-CLOSE
    // No-op: no JPA resource to release.
  }

  /** Closes the card cross-reference input, reproducing {@code CLOSE XREF-FILE} (no-op). */
  private void closeXreffile() { // <- CBTRN02C 9200-XREFFILE-CLOSE
    // No-op: no JPA resource to release.
  }

  /** Closes the daily-reject output, reproducing {@code CLOSE DALYREJS-FILE} (no-op). */
  private void closeDalyrejs() { // <- CBTRN02C 9300-DALYREJS-CLOSE
    // No-op: the reject sink is owned and closed by the batch layer.
  }

  /** Closes the account master, reproducing {@code CLOSE ACCOUNT-FILE} (no-op). */
  private void closeAcctfile() { // <- CBTRN02C 9400-ACCTFILE-CLOSE
    // No-op: no JPA resource to release.
  }

  /** Closes the category-balance store, reproducing {@code CLOSE TCATBAL-FILE} (no-op). */
  private void closeTcatbalf() { // <- CBTRN02C 9500-TCATBALF-CLOSE
    // No-op: no JPA resource to release.
  }

  // -----------------------------------------------------------------------------------------------
  // Validation (1500-VALIDATE-TRAN, 1500-A-LOOKUP-XREF, 1500-B-LOOKUP-ACCT)
  // -----------------------------------------------------------------------------------------------

  /**
   * Validates the current daily transaction, reproducing {@code 1500-VALIDATE-TRAN}. It looks up
   * the card cross-reference first; only when that succeeds ({@code WS-VALIDATION-FAIL-REASON = 0})
   * does it look up the account. The legacy {@code * ADD MORE VALIDATIONS HERE} comment marks an
   * extension point with no additional validations.
   */
  private void validateTran() { // <- CBTRN02C 1500-VALIDATE-TRAN
    lookupXref();
    if (validationFailReason == 0) {
      lookupAcct();
    }
  }

  /**
   * Resolves the card cross-reference by card number, reproducing {@code 1500-A-LOOKUP-XREF}. A
   * present row is FILE STATUS {@code '00'} and is kept as the current cross-reference (its {@code
   * XREF-ACCT-ID} drives the account lookup and posting). An absent row is the {@code INVALID KEY}
   * (FILE STATUS {@code '23'}) branch, which sets reason {@code 100}; it is not an abend and is not
   * thrown.
   */
  private void lookupXref() { // <- CBTRN02C 1500-A-LOOKUP-XREF
    Optional<CardXref> xref = cardXrefRepository.findById(dalytran.getDalytranCardNum());
    if (xref.isPresent()) {
      currentXref = xref.get(); // FILE STATUS '00'
    } else {
      currentXref = null;
      validationFailReason = 100; // INVALID KEY (FILE STATUS '23')
      validationFailReasonDesc = "INVALID CARD NUMBER FOUND";
    }
  }

  /**
   * Resolves the account master by the cross-reference account id and applies the two business
   * checks, reproducing {@code 1500-B-LOOKUP-ACCT}. An absent account is the {@code INVALID KEY}
   * branch (reason {@code 101}) and the checks are skipped. When the account is present it is kept
   * as the current account and BOTH checks run sequentially, exactly as the two independent COBOL
   * {@code IF}s &mdash; the expiration check can overwrite an overlimit reason:
   *
   * <ol>
   *   <li><strong>Overlimit:</strong> {@code WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT -
   *       ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT}; valid when {@code ACCT-CREDIT-LIMIT >= WS-TEMP-BAL},
   *       otherwise reason {@code 102}.
   *   <li><strong>Expiration:</strong> valid when {@code ACCT-EXPIRAION-DATE >=
   *       DALYTRAN-ORIG-TS(1:10)} (lexicographic compare of the {@code YYYY-MM-DD} prefix),
   *       otherwise reason {@code 103}.
   * </ol>
   *
   * <p>All monetary arithmetic uses {@link BigDecimal} at scale {@code 2}; {@code compareTo} is
   * value-based and scale-independent, matching COBOL's numeric {@code >=}.
   */
  private void lookupAcct() { // <- CBTRN02C 1500-B-LOOKUP-ACCT
    Optional<Account> acct = accountRepository.findById(currentXref.getXrefAcctId());
    if (acct.isEmpty()) {
      currentAccount = null;
      validationFailReason = 101; // INVALID KEY (FILE STATUS '23')
      validationFailReasonDesc = "ACCOUNT RECORD NOT FOUND";
      return;
    }

    currentAccount = acct.get(); // FILE STATUS '00'

    // Overlimit check: WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT.
    BigDecimal wsTempBal =
        currentAccount
            .getAcctCurrCycCredit()
            .subtract(currentAccount.getAcctCurrCycDebit())
            .add(dalytran.getDalytranAmt());
    if (currentAccount.getAcctCreditLimit().compareTo(wsTempBal) >= 0) {
      // ACCT-CREDIT-LIMIT >= WS-TEMP-BAL: within limit (CONTINUE).
    } else {
      validationFailReason = 102;
      validationFailReasonDesc = "OVERLIMIT TRANSACTION";
    }

    // Expiration check (sequential; may overwrite 102): ACCT-EXPIRAION-DATE >=
    // DALYTRAN-ORIG-TS(1:10).
    String expirationDate = CobolStringUtils.padRight(currentAccount.getAcctExpiraionDate(), 10);
    String originationDate = CobolStringUtils.padRight(dalytran.getDalytranOrigTs(), 10);
    if (expirationDate.compareTo(originationDate) >= 0) {
      // Not expired (CONTINUE).
    } else {
      validationFailReason = 103;
      validationFailReasonDesc = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Posting (2000-POST-TRANSACTION and its sub-paragraphs)
  // -----------------------------------------------------------------------------------------------

  /**
   * Posts the current (valid) daily transaction, reproducing {@code 2000-POST-TRANSACTION}. It
   * builds the {@code TRAN-RECORD} by copying all twelve {@code DALYTRAN-RECORD} fields in the
   * legacy {@code MOVE} order, stamps {@code TRAN-PROC-TS} from {@link #getDb2FormatTimestamp()},
   * then performs, in order, the category-balance upsert, the account update, and the transaction
   * write.
   */
  private void postTransaction() { // <- CBTRN02C 2000-POST-TRANSACTION
    Transaction transaction = new Transaction();
    transaction.setTranId(dalytran.getDalytranId());
    transaction.setTranTypeCd(dalytran.getDalytranTypeCd());
    transaction.setTranCatCd(dalytran.getDalytranCatCd());
    transaction.setTranSource(dalytran.getDalytranSource());
    transaction.setTranDesc(dalytran.getDalytranDesc());
    transaction.setTranAmt(dalytran.getDalytranAmt());
    transaction.setTranMerchantId(dalytran.getDalytranMerchantId());
    transaction.setTranMerchantName(dalytran.getDalytranMerchantName());
    transaction.setTranMerchantCity(dalytran.getDalytranMerchantCity());
    transaction.setTranMerchantZip(dalytran.getDalytranMerchantZip());
    transaction.setTranCardNum(dalytran.getDalytranCardNum());
    transaction.setTranOrigTs(dalytran.getDalytranOrigTs());
    transaction.setTranProcTs(getDb2FormatTimestamp());
    this.currentTransaction = transaction;

    updateTcatbal();
    updateAccountRec();
    writeTransactionFile();
  }

  /**
   * Upserts the transaction-category balance for the current transaction, reproducing {@code
   * 2700-UPDATE-TCATBAL} together with {@code 2700-A-CREATE-TCATBAL-REC} and {@code
   * 2700-B-UPDATE-TCATBAL-REC}. The composite key is {@code XREF-ACCT-ID + DALYTRAN-TYPE-CD +
   * DALYTRAN-CAT-CD}. The legacy read accepts FILE STATUS {@code '00'} OR {@code '23'}, so a
   * missing row is <em>not</em> an error: when absent, a new balance record is created starting
   * from zero and the amount is added ({@code WRITE}); when present, the amount is added to the
   * existing balance ({@code REWRITE}). All balances are {@link BigDecimal} at scale {@code 2}. Any
   * other repository failure maps to the {@code CBTRN02C} TCATBAL abend path.
   */
  private void updateTcatbal() { // <- CBTRN02C 2700-UPDATE-TCATBAL (+2700-A / +2700-B)
    TransactionCategoryBalanceId key =
        new TransactionCategoryBalanceId(
            currentXref.getXrefAcctId(), dalytran.getDalytranTypeCd(), dalytran.getDalytranCatCd());

    Optional<TransactionCategoryBalance> existing;
    try {
      existing = tcatbalRepository.findById(key); // FILE STATUS '00' OR '23' both accepted
    } catch (DataAccessException ex) {
      throw abend(DD_TCATBALF, "READ", ex);
    }

    TransactionCategoryBalance balance;
    if (existing.isEmpty()) {
      // 2700-A-CREATE-TCATBAL-REC: INITIALIZE, move key, ADD DALYTRAN-AMT TO TRAN-CAT-BAL, WRITE.
      balance = new TransactionCategoryBalance();
      balance.setId(key);
      balance.setTranCatBal(BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY));
    } else {
      // 2700-B-UPDATE-TCATBAL-REC: ADD DALYTRAN-AMT TO TRAN-CAT-BAL, REWRITE.
      balance = existing.get();
    }
    balance.setTranCatBal(balance.getTranCatBal().add(dalytran.getDalytranAmt()));

    try {
      tcatbalRepository.save(balance); // WRITE (create) / REWRITE (update)
    } catch (DataAccessException ex) {
      throw abend(DD_TCATBALF, "REWRITE", ex);
    }
  }

  /**
   * Applies the transaction amount to the current account, reproducing {@code
   * 2800-UPDATE-ACCOUNT-REC}: the amount is added to {@code ACCT-CURR-BAL}; then, per the literal
   * COBOL {@code ADD}, a non-negative amount is added to {@code ACCT-CURR-CYC-CREDIT} and a
   * negative amount is added (as-is, not negated) to {@code ACCT-CURR-CYC-DEBIT}. The updated
   * account is rewritten. Should the account be absent (the {@code REWRITE INVALID KEY} path, a
   * "cannot happen" case since {@code 1500-B-LOOKUP-ACCT} loaded it), reason {@code 109} is set.
   * All balances are {@link BigDecimal} at scale {@code 2}; the account carries no {@code @Version}
   * so, matching the single-threaded COBOL run unit, no optimistic-lock retry is attempted. Any
   * other repository failure maps to the {@code CBTRN02C} ACCTFILE abend path.
   */
  private void updateAccountRec() { // <- CBTRN02C 2800-UPDATE-ACCOUNT-REC
    if (currentAccount == null) {
      validationFailReason = 109; // REWRITE INVALID KEY
      validationFailReasonDesc = "ACCOUNT RECORD NOT FOUND";
      return;
    }

    BigDecimal amount = dalytran.getDalytranAmt();
    currentAccount.setAcctCurrBal(currentAccount.getAcctCurrBal().add(amount));
    if (amount.signum() >= 0) {
      currentAccount.setAcctCurrCycCredit(currentAccount.getAcctCurrCycCredit().add(amount));
    } else {
      currentAccount.setAcctCurrCycDebit(currentAccount.getAcctCurrCycDebit().add(amount));
    }

    try {
      accountRepository.save(currentAccount); // REWRITE
    } catch (DataAccessException ex) {
      throw abend(DD_ACCTFILE, "REWRITE", ex);
    }
  }

  /**
   * Writes the posted transaction to the transaction master, reproducing {@code
   * 2900-WRITE-TRANSACTION-FILE} ({@code WRITE TRAN-RECORD}). Any repository failure maps to the
   * {@code CBTRN02C} TRANSACT abend path.
   */
  private void writeTransactionFile() { // <- CBTRN02C 2900-WRITE-TRANSACTION-FILE
    try {
      transactionRepository.save(currentTransaction); // WRITE
    } catch (DataAccessException ex) {
      throw abend(DD_TRANSACT, "WRITE", ex);
    }
  }

  // -----------------------------------------------------------------------------------------------
  // DB2-format timestamp (Z-GET-DB2-FORMAT-TIMESTAMP)
  // -----------------------------------------------------------------------------------------------

  /**
   * Builds the 26-character DB2-format processing timestamp, reproducing {@code
   * Z-GET-DB2-FORMAT-TIMESTAMP}. The legacy program takes {@code FUNCTION CURRENT-DATE} (which
   * yields date, time, and hundredths of a second) and assembles {@code
   * 'YYYY-MM-DD-HH.MM.SS.mmmmmm'} where the six-digit fractional tail is the two-digit hundredths
   * ({@code COB-MIL}) followed by a literal {@code '0000'}. The time source is the injected {@link
   * Clock}, so parity tests can pin a deterministic value.
   *
   * @return a 26-character timestamp string with dash date separators and dot time separators
   */
  private String getDb2FormatTimestamp() { // <- CBTRN02C Z-GET-DB2-FORMAT-TIMESTAMP
    LocalDateTime now = LocalDateTime.now(clock);
    long hundredths = now.getNano() / 10_000_000L; // COB-MIL: hundredths of a second (00-99)
    return String.format(
        Locale.ROOT,
        "%04d-%02d-%02d-%02d.%02d.%02d.%02d0000",
        now.getYear(),
        now.getMonthValue(),
        now.getDayOfMonth(),
        now.getHour(),
        now.getMinute(),
        now.getSecond(),
        hundredths);
  }

  // -----------------------------------------------------------------------------------------------
  // Reject record (2500-WRITE-REJECT-REC) — build here, physical write owned by the batch layer.
  // -----------------------------------------------------------------------------------------------

  /**
   * Emits the reject record for the current (invalid) daily transaction, reproducing {@code
   * 2500-WRITE-REJECT-REC}. The complete 430-character {@code DALYREJS} image (350-byte {@code
   * DALYTRAN} image + 80-byte validation trailer) is built here and handed to {@code rejectSink};
   * the physical write is owned by {@code com.aws.carddemo.batch} (AAP &sect;0.6.6). No file is
   * opened or written here.
   *
   * @param rejectSink the consumer that performs the physical {@code DALYREJS} write
   */
  private void writeRejectRec(Consumer<String> rejectSink) { // <- CBTRN02C 2500-WRITE-REJECT-REC
    rejectSink.accept(buildRejectRecord());
  }

  /**
   * Builds the complete 430-character {@code DALYREJS} record: the 350-byte fixed-width image of
   * the current {@code DALYTRAN-RECORD} concatenated with the 80-byte validation trailer.
   *
   * @return the 430-character reject record (no trailing line terminator)
   */
  private String buildRejectRecord() {
    return buildDalytranImage() + buildValidationTrailer();
  }

  /**
   * Renders the 350-byte fixed-width image of the current {@code DALYTRAN-RECORD}, field-for-field
   * in copybook {@code CVTRA06Y} order and width. Text fields are space-padded ({@code PIC X(n)}),
   * the category code and merchant id are zero-padded numerics ({@code PIC 9(n)}), and the signed
   * amount is rendered with a trailing zoned-decimal overpunch sign ({@code PIC S9(09)V99}). The
   * processing-timestamp field images the input record's (blank) value, matching the legacy {@code
   * MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA}. Field widths sum to exactly 350.
   *
   * @return the 350-character {@code DALYTRAN} image
   */
  private String buildDalytranImage() {
    long merchantId =
        dalytran.getDalytranMerchantId() == null ? 0L : dalytran.getDalytranMerchantId();
    StringBuilder image = new StringBuilder(DALYTRAN_IMAGE_WIDTH);
    image.append(CobolStringUtils.padRight(dalytran.getDalytranId(), 16)); // DALYTRAN-ID X(16)
    image.append(CobolStringUtils.padRight(dalytran.getDalytranTypeCd(), 2)); // -TYPE-CD X(02)
    image.append(CobolStringUtils.padLeftZeros(dalytran.getDalytranCatCd(), 4)); // -CAT-CD 9(04)
    image.append(CobolStringUtils.padRight(dalytran.getDalytranSource(), 10)); // -SOURCE X(10)
    image.append(CobolStringUtils.padRight(dalytran.getDalytranDesc(), 100)); // -DESC X(100)
    image.append(encodeOverpunch(dalytran.getDalytranAmt())); // -AMT S9(09)V99
    image.append(CobolStringUtils.padLeftZeros(merchantId, 9)); // -MERCHANT-ID 9(09)
    image.append(CobolStringUtils.padRight(dalytran.getDalytranMerchantName(), 50)); // -NAME X(50)
    image.append(CobolStringUtils.padRight(dalytran.getDalytranMerchantCity(), 50)); // -CITY X(50)
    image.append(CobolStringUtils.padRight(dalytran.getDalytranMerchantZip(), 10)); // -ZIP X(10)
    image.append(CobolStringUtils.padRight(dalytran.getDalytranCardNum(), 16)); // -CARD-NUM X(16)
    image.append(CobolStringUtils.padRight(dalytran.getDalytranOrigTs(), 26)); // -ORIG-TS X(26)
    image.append(CobolStringUtils.padRight(dalytran.getDalytranProcTs(), 26)); // -PROC-TS X(26)
    image.append(CobolStringUtils.spaces(20)); // FILLER X(20)
    return image.toString();
  }

  /**
   * Renders the 80-byte validation trailer: the four-digit zero-padded {@code
   * WS-VALIDATION-FAIL-REASON} ({@code PIC 9(04)}) followed by the 76-byte space-padded {@code
   * WS-VALIDATION-FAIL-REASON-DESC} ({@code PIC X(76)}). Widths sum to exactly 80.
   *
   * @return the 80-character validation trailer
   */
  private String buildValidationTrailer() {
    return CobolStringUtils.padLeftZeros(validationFailReason, 4)
        + CobolStringUtils.padRight(validationFailReasonDesc, 76);
  }

  /**
   * Encodes a signed monetary amount as an 11-character zoned-decimal field with a trailing
   * overpunch sign, matching {@code PIC S9(09)V99} and the inverse of the seed decoder so the
   * rebuilt image is byte-faithful (AAP &sect;0.6.6). The amount is truncated to scale {@code 2}
   * (no rounding, as in COBOL), its unscaled magnitude is right-justified and zero-padded to 11
   * digits (high-order digits truncate if the magnitude overflows the picture), and the final digit
   * is replaced by the overpunch character selected from the positive table ({@code "{ABCDEFGHI"})
   * for non-negative amounts or the negative table ({@code "}JKLMNOPQR"}) for negative amounts.
   *
   * @param amount the signed amount to encode; scale is normalized to {@code 2}
   * @return the 11-character overpunched representation
   */
  private String encodeOverpunch(BigDecimal amount) {
    BigDecimal scaled = amount.abs().setScale(2, RoundingMode.DOWN);
    String digits = CobolStringUtils.padLeftZeros(scaled.unscaledValue().toString(), AMOUNT_DIGITS);
    int lastIndex = digits.length() - 1;
    int unitsDigit = digits.charAt(lastIndex) - '0';
    String overpunchTable = amount.signum() < 0 ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH;
    return digits.substring(0, lastIndex) + overpunchTable.charAt(unitsDigit);
  }

  // -----------------------------------------------------------------------------------------------
  // Abend / FILE STATUS mapping (9910-DISPLAY-IO-STATUS + 9999-ABEND-PROGRAM)
  // -----------------------------------------------------------------------------------------------

  /**
   * Builds the {@link IoStatusException} for an unrecoverable I/O condition, reproducing the {@code
   * 9910-DISPLAY-IO-STATUS} then {@code 9999-ABEND-PROGRAM} path. The displayable FILE STATUS line
   * is logged at error level before the exception is returned to the caller to throw, so the
   * externally observable status message is preserved and the Spring Batch step fails. Only the
   * paragraphs that contain an explicit COBOL abend ({@code OPEN}, sequential {@code READ}, the
   * {@code TCATBALF} read/write, and the {@code ACCTFILE}/{@code TRANSACT} writes) route through
   * here; the keyed {@code INVALID KEY} conditions are handled as reject reasons or upserts and do
   * not abend.
   *
   * @param fileName the logical DD name of the file whose I/O failed
   * @param operation the failed I/O operation (e.g. {@code OPEN}, {@code READ}, {@code WRITE},
   *     {@code REWRITE})
   * @param cause the originating Spring data-access exception
   * @return the {@link IoStatusException} for the caller to throw
   */
  private IoStatusException abend(
      String fileName, String operation, DataAccessException cause) { // <- CBTRN02C 9910/9999
    IoStatusException exception =
        new IoStatusException(fileName, operation, ABEND_FILE_STATUS, cause);
    LOG.error(exception.getDisplayMessage()); // 9910-DISPLAY-IO-STATUS
    LOG.error("ABENDING PROGRAM"); // 9999-ABEND-PROGRAM
    return exception;
  }
}
