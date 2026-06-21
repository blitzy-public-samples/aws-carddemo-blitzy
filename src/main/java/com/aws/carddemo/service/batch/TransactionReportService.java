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

import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategory;
import com.aws.carddemo.domain.TransactionType;
import com.aws.carddemo.domain.id.TransactionCategoryId;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionCategoryRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.repository.TransactionTypeRepository;
import com.aws.carddemo.util.CobolStringUtils;
import com.aws.carddemo.util.NumberFormatter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Batch "transaction detail report" service, migrated with 100% behavioral parity from the legacy
 * z/OS COBOL program {@code CBTRN03C} (source {@code legacy/app/cbl/CBTRN03C.cbl}, "Transaction
 * detail report"). This is the <em>report archetype</em> for CardDemo: it produces a fixed-width
 * 133-column report with page totals, account totals and a grand total, enriched by three indexed
 * lookups (card-xref, transaction-type, transaction-category). Its control flow is reproduced
 * paragraph-for-paragraph in the original {@code PERFORM} order (Agent Action Plan &sect;0.1.2
 * control-flow preservation, &sect;0.6.7 paragraph&rarr;method traceability).
 *
 * <p>The legacy job ({@code TRANREPT.jcl} + {@code TRANREPT.prc}) first runs a {@code SORT
 * FIELDS=(TRAN-CARD-NUM,A)} with {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,
 * TRAN-PROC-DT,LE,PARM-END-DATE)}, producing a card-number-ordered, date-filtered file that {@code
 * CBTRN03C} then reads. Here that sorted/filtered working set is rebuilt from the {@link
 * TransactionRepository} via a bounded-memory streaming cursor {@code
 * streamAllByOrderByTranCardNumAsc()} with a lazy in-memory date filter, so records stream in
 * ascending {@code tranCardNum} order without materialising the whole table; the three lookups
 * become {@code findById} calls on their JPA repositories (AAP &sect;0.6.2). The run dates replace
 * the COBOL {@code DATEPARM} file read and, in production, originate as Spring Batch {@code
 * JobParameters} (AAP &sect;0.1.1: JCL {@code PARM} &rarr; {@code JobParameter}).
 *
 * <p>Per migration convention this service contains <strong>no Spring Batch types</strong>: {@link
 * #run(String, String, Consumer)} is a plain method that the batch tier ({@code
 * TransactionReportJobConfig} in {@code com.aws.carddemo.batch}) later invokes from a tasklet, so
 * an unhandled {@link IoStatusException} naturally becomes a failed Spring Batch step exit status
 * (the abend-equivalent, AAP &sect;0.6.6). Every fully-formatted 133-character line is handed to
 * the {@link Consumer} {@code reportSink}; the physical writer to the {@code TRANREPT} dataset
 * lives in the batch tier.
 *
 * <p><strong>Decimal fidelity (AAP &sect;0.6.1).</strong> The page, account and grand totals are
 * {@link BigDecimal} at scale 2 ({@code S9(09)V99}); {@code float}/{@code double} are never used.
 * Edited numeric fields are rendered exclusively through {@link NumberFormatter} and fixed-width
 * text through {@link CobolStringUtils} so the output matches the COBOL truncation/justification
 * byte-for-byte.
 *
 * <p><strong>COBOL quirks preserved bug-for-bug (golden-file parity, AAP &sect;0.7.1).</strong>
 *
 * <ul>
 *   <li><b>EOF double-count</b> &mdash; at end-of-file the COBOL {@code READ ... INTO} leaves the
 *       record area holding the <em>last</em> record, and the EOF branch adds its amount to the
 *       page and account totals <em>again</em>, double-counting the final record in the printed
 *       page and grand totals (CBTRN03C L200-201).
 *   <li><b>No final account total</b> &mdash; the EOF branch emits only the page total and grand
 *       total, so the last card's account-total line is never written.
 *   <li><b>Control break on card number</b> &mdash; the account break is keyed on {@code
 *       TRAN-CARD-NUM}, not the account id.
 *   <li><b>Page/grand totals at EOF</b> &mdash; only page and grand totals close out the report.
 * </ul>
 *
 * <p><strong>COBOL paragraph &rarr; Java method traceability</strong> (AAP &sect;0.6.7). The source
 * reuses paragraph numbers {@code 1110-} (twice) and {@code 1120-} (three times); they carry
 * distinct full names and therefore map to distinct Java methods.
 *
 * <table border="1">
 *   <caption>CBTRN03C paragraph to Java method mapping</caption>
 *   <tr><th>COBOL paragraph (CBTRN03C)</th><th>Java method</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} (L159-217)</td>
 *       <td>{@link #run(String, String, Consumer)}</td></tr>
 *   <tr><td>{@code 0000-TRANFILE-OPEN} (L376)</td><td>{@link #openTranfile()}</td></tr>
 *   <tr><td>{@code 0100-REPTFILE-OPEN} (L394)</td><td>{@link #openReptfile()}</td></tr>
 *   <tr><td>{@code 0200-CARDXREF-OPEN} (L412)</td><td>{@link #openCardxref()}</td></tr>
 *   <tr><td>{@code 0300-TRANTYPE-OPEN} (L430)</td><td>{@link #openTrantype()}</td></tr>
 *   <tr><td>{@code 0400-TRANCATG-OPEN} (L448)</td><td>{@link #openTrancatg()}</td></tr>
 *   <tr><td>{@code 0500-DATEPARM-OPEN} (L466)</td><td>{@link #openDateparm()}</td></tr>
 *   <tr><td>{@code 0550-DATEPARM-READ} (L220)</td><td>{@link #dateparmRead()}</td></tr>
 *   <tr><td>{@code 1000-TRANFILE-GET-NEXT} (L248)</td><td>{@link #tranfileGetNext()}</td></tr>
 *   <tr><td>{@code 1100-WRITE-TRANSACTION-REPORT} (L274)</td>
 *       <td>{@link #writeTransactionReport()}</td></tr>
 *   <tr><td>{@code 1110-WRITE-PAGE-TOTALS} (L293)</td><td>{@link #writePageTotals()}</td></tr>
 *   <tr><td>{@code 1120-WRITE-ACCOUNT-TOTALS} (L306)</td><td>{@link #writeAccountTotals()}</td></tr>
 *   <tr><td>{@code 1110-WRITE-GRAND-TOTALS} (L318)</td><td>{@link #writeGrandTotals()}</td></tr>
 *   <tr><td>{@code 1120-WRITE-HEADERS} (L324)</td><td>{@link #writeHeaders()}</td></tr>
 *   <tr><td>{@code 1111-WRITE-REPORT-REC} (L343)</td><td>{@link #writeReportRec(String)}</td></tr>
 *   <tr><td>{@code 1120-WRITE-DETAIL} (L361)</td><td>{@link #writeDetail()}</td></tr>
 *   <tr><td>{@code 1500-A-LOOKUP-XREF} (L484)</td><td>{@link #lookupXref()}</td></tr>
 *   <tr><td>{@code 1500-B-LOOKUP-TRANTYPE} (L494)</td><td>{@link #lookupTrantype()}</td></tr>
 *   <tr><td>{@code 1500-C-LOOKUP-TRANCATG} (L504)</td><td>{@link #lookupTrancatg()}</td></tr>
 *   <tr><td>{@code 9000-TRANFILE-CLOSE} (L514)</td><td>{@link #closeTranfile()}</td></tr>
 *   <tr><td>{@code 9100-REPTFILE-CLOSE} (L532)</td><td>{@link #closeReptfile()}</td></tr>
 *   <tr><td>{@code 9200-CARDXREF-CLOSE} (L551)</td><td>{@link #closeCardxref()}</td></tr>
 *   <tr><td>{@code 9300-TRANTYPE-CLOSE} (L569)</td><td>{@link #closeTrantype()}</td></tr>
 *   <tr><td>{@code 9400-TRANCATG-CLOSE} (L587)</td><td>{@link #closeTrancatg()}</td></tr>
 *   <tr><td>{@code 9500-DATEPARM-CLOSE} (L605)</td><td>{@link #closeDateparm()}</td></tr>
 *   <tr><td>{@code 9999-ABEND-PROGRAM} (L626) + {@code 9910-DISPLAY-IO-STATUS} (L633)</td>
 *       <td>{@link #abend(String, String, String, String, Throwable)} &rarr; throw {@link
 *       IoStatusException}</td></tr>
 * </table>
 *
 * <p><strong>Statefulness / threading.</strong> Mirroring the single-run COBOL program, this
 * service keeps the in-flight report state (cursor, end-of-file switch, current record, running
 * totals, carried lookup results, line counter and report sink) in instance fields and resets it at
 * the start of every {@link #run(String, String, Consumer)}. A single {@code run(...)} is therefore
 * self-contained and repeatable, but the method is <em>not</em> re-entrant: it is for
 * single-threaded batch use only (one job execution at a time), exactly as the batch tasklet drives
 * it.
 */
@Service
public class TransactionReportService {

  /** SLF4J logger; every COBOL {@code DISPLAY} in {@code CBTRN03C} routes through this logger. */
  private static final Logger LOG = LoggerFactory.getLogger(TransactionReportService.class);

  /** {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} &mdash; report body lines per page. */
  private static final int WS_PAGE_SIZE = 20;

  /** {@code FD-REPTFILE-REC PIC X(133)} &mdash; the report record length (LRECL=133). */
  private static final int REPORT_RECORD_LENGTH = 133;

  /**
   * Shared zero total at scale 2, modeling {@code S9(09)V99 VALUE 0}. {@link BigDecimal} is
   * immutable, so a single shared instance is safe to reuse for every reset.
   */
  private static final BigDecimal ZERO_AMOUNT = BigDecimal.ZERO.setScale(2);

  /** {@code WS-CURR-CARD-NUM PIC X(16) VALUE SPACES} &mdash; the initial control-break key. */
  private static final String SIXTEEN_SPACES = " ".repeat(16);

  /** Logical file name reported on the abend path for the sorted transaction working set. */
  private static final String TRANFILE_DDNAME = "TRANFILE";

  /** Logical file name reported on the abend path for the report output dataset. */
  private static final String REPTFILE_DDNAME = "TRANREPT";

  /** Logical file name reported on the abend path for the card cross-reference lookup. */
  private static final String CARDXREF_DDNAME = "CARDXREF";

  /** Logical file name reported on the abend path for the transaction-type lookup. */
  private static final String TRANTYPE_DDNAME = "TRANTYPE";

  /** Logical file name reported on the abend path for the transaction-category lookup. */
  private static final String TRANCATG_DDNAME = "TRANCATG";

  /** Logical file name reported on the abend path for the date-parameter input. */
  private static final String DATEPARM_DDNAME = "DATEPARM";

  /** {@code MOVE 23 TO IO-STATUS} &mdash; record-not-found, which is FATAL in this program. */
  private static final String FILE_STATUS_NOT_FOUND = "23";

  /** Sentinel {@code FILE STATUS} for an unexpected {@link DataAccessException} (abnormal I/O). */
  private static final String FILE_STATUS_ABEND = "99";

  /** Sentinel {@code FILE STATUS} for a write/read failure on the report or date-parm files. */
  private static final String FILE_STATUS_IO_ERROR = "12";

  /** {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN03C'} (CBTRN03C L160). */
  private static final String START_MESSAGE = "START OF EXECUTION OF PROGRAM CBTRN03C";

  /** {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN03C'} (CBTRN03C L215). */
  private static final String END_MESSAGE = "END OF EXECUTION OF PROGRAM CBTRN03C";

  /** {@code DISPLAY 'ABENDING PROGRAM'} from {@code 9999-ABEND-PROGRAM} (CBTRN03C L626). */
  private static final String ABEND_MESSAGE = "ABENDING PROGRAM";

  /**
   * Verb-specific {@code DISPLAY 'ERROR ...'} literal emitted when opening the transaction file.
   */
  private static final String TRANFILE_OPEN_ERROR_MESSAGE = "ERROR OPENING TRANSACTION FILE";

  /** Verb-specific {@code DISPLAY 'ERROR ...'} literal emitted when the date parm is unusable. */
  private static final String DATEPARM_READ_ERROR_MESSAGE = "ERROR READING DATE PARM FILE";

  /** Verb-specific {@code DISPLAY 'ERROR ...'} literal emitted when a report write fails. */
  private static final String REPTFILE_WRITE_ERROR_MESSAGE = "ERROR WRITING REPORT FILE";

  /** Verb-specific {@code DISPLAY 'ERROR ...'} literal emitted on an unexpected xref read error. */
  private static final String XREF_READ_ERROR_MESSAGE = "ERROR READING CARD XREF FILE";

  /** Verb-specific {@code DISPLAY 'ERROR ...'} literal emitted on an unexpected trantype error. */
  private static final String TRANTYPE_READ_ERROR_MESSAGE = "ERROR READING TRANSACTION TYPE FILE";

  /** Verb-specific {@code DISPLAY 'ERROR ...'} literal emitted on an unexpected trancatg error. */
  private static final String TRANCATG_READ_ERROR_MESSAGE = "ERROR READING TRANSACTION CATG FILE";

  /** {@code REPT-SHORT-NAME PIC X(38) VALUE 'DALYREPT'} (CVTRA07Y). */
  private static final String REPT_SHORT_NAME = "DALYREPT";

  /** {@code REPT-LONG-NAME PIC X(41) VALUE 'Daily Transaction Report'} (CVTRA07Y). */
  private static final String REPT_LONG_NAME = "Daily Transaction Report";

  /**
   * {@code REPT-DATE-HEADER PIC X(12) VALUE 'Date Range: '} (CVTRA07Y) &mdash; exactly 12 chars.
   */
  private static final String REPT_DATE_HEADER = "Date Range: ";

  /** {@code FILLER PIC X(04) VALUE ' to '} between the start and end dates (CVTRA07Y). */
  private static final String DATE_RANGE_SEPARATOR = " to ";

  /** {@code FILLER PIC X(11) VALUE 'Page Total'} (CVTRA07Y L50). */
  private static final String PAGE_TOTAL_LABEL = "Page Total";

  /** {@code FILLER PIC X(13) VALUE 'Account Total'} (CVTRA07Y L56). */
  private static final String ACCOUNT_TOTAL_LABEL = "Account Total";

  /** {@code FILLER PIC X(11) VALUE 'Grand Total'} (CVTRA07Y L62). */
  private static final String GRAND_TOTAL_LABEL = "Grand Total";

  /**
   * {@code TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'} (CVTRA07Y L48) &mdash; full-width rule.
   */
  private static final String DASH_133 = "-".repeat(133);

  /** {@code FILLER PIC X(86) VALUE ALL '.'} used by the page and grand total lines (CVTRA07Y). */
  private static final String PAGE_TOTAL_DOTS = ".".repeat(86);

  /** {@code FILLER PIC X(84) VALUE ALL '.'} used by the account total line (CVTRA07Y). */
  private static final String ACCOUNT_TOTAL_DOTS = ".".repeat(84);

  /**
   * {@code TRANSACTION-HEADER-1} (CVTRA07Y L33-46), content width 114, byte-for-byte from the
   * copybook {@code VALUE} clauses. The amount caption is eight leading spaces followed by {@code
   * "Amount"}.
   */
  private static final String TRANSACTION_HEADER_1 =
      CobolStringUtils.fixedWidth("Transaction ID", 17)
          + CobolStringUtils.fixedWidth("Account ID", 12)
          + CobolStringUtils.fixedWidth("Transaction Type", 19)
          + CobolStringUtils.fixedWidth("Tran Category", 35)
          + CobolStringUtils.fixedWidth("Tran Source", 14)
          + " "
          + CobolStringUtils.fixedWidth("        Amount", 16);

  /** {@code SELECT TRANSACT-FILE} &rarr; transaction master repository (the report working set). */
  private final TransactionRepository transactionRepository;

  /** {@code SELECT XREF-FILE} &rarr; card cross-reference repository ({@code 1500-A} lookup). */
  private final CardXrefRepository cardXrefRepository;

  /** {@code SELECT TRANTYPE-FILE} &rarr; transaction-type repository ({@code 1500-B} lookup). */
  private final TransactionTypeRepository transactionTypeRepository;

  /**
   * {@code SELECT TRANCATG-FILE} &rarr; transaction-category repository ({@code 1500-C} lookup).
   */
  private final TransactionCategoryRepository transactionCategoryRepository;

  /** {@code WS-START-DATE PIC X(10)} &mdash; inclusive lower bound of the report date range. */
  private String wsStartDate;

  /** {@code WS-END-DATE PIC X(10)} &mdash; inclusive upper bound of the report date range. */
  private String wsEndDate;

  /** {@code WS-FIRST-TIME PIC X(01) VALUE 'Y'} &mdash; {@code true} until the first detail line. */
  private boolean wsFirstTime;

  /** {@code WS-LINE-COUNTER PIC 9(09) COMP-3 VALUE 0} &mdash; drives the page-break {@code MOD}. */
  private long wsLineCounter;

  /** {@code WS-PAGE-TOTAL PIC S9(09)V99 VALUE 0} &mdash; running page total (scale 2). */
  private BigDecimal wsPageTotal;

  /** {@code WS-ACCOUNT-TOTAL PIC S9(09)V99 VALUE 0} &mdash; running account total (scale 2). */
  private BigDecimal wsAccountTotal;

  /** {@code WS-GRAND-TOTAL PIC S9(09)V99 VALUE 0} &mdash; running grand total (scale 2). */
  private BigDecimal wsGrandTotal;

  /** {@code WS-CURR-CARD-NUM PIC X(16)} &mdash; current control-break key (the card number). */
  private String wsCurrCardNum;

  /** {@code END-OF-FILE PIC X(01)} switch ({@code 'N'}/{@code 'Y'}); {@code true} at exhaustion. */
  private boolean endOfFile;

  /**
   * Transaction-scoped entity manager used to {@code detach} each streamed transaction immediately
   * after the cursor fetches it (via a {@code peek} ahead of the date filter), so the persistence
   * context does not accumulate the whole 200k-row table — the bounded-memory complement to the
   * streaming cursor. Injected by the container; bound to the tasklet transaction that brackets
   * {@link #run(String, String, Consumer)}.
   */
  @PersistenceContext private EntityManager entityManager;

  /**
   * Open streaming cursor over the transaction table (ascending {@code tranCardNum}), held so it
   * can be closed in {@link #closeTranfile()} to release the underlying JDBC cursor. {@code null}
   * when no scan is in progress.
   */
  private Stream<Transaction> transactionStream;

  /**
   * Ascending-card-number cursor over the sorted, date-filtered working set; {@code null} when not
   * open. Backed by the streaming cursor with a {@code peek}-detach stage and a lazy date-range
   * {@code filter}, so it yields only in-range records (mirroring the JCL {@code SORT INCLUDE})
   * while every fetched row is detached to bound memory.
   */
  private Iterator<Transaction> cursor;

  /**
   * The COBOL {@code TRAN-RECORD} work area: the record most recently read by {@link
   * #tranfileGetNext()}. It is deliberately <em>not</em> cleared at end-of-file so it still holds
   * the last record (required for the EOF double-count), exactly as {@code READ ... INTO} behaves.
   */
  private Transaction currentTransaction;

  /**
   * {@code XREF-ACCT-ID} carried from {@code 1500-A}; retained across all detail lines of a card.
   */
  private String xrefAcctId;

  /** {@code TRAN-TYPE-DESC} carried from {@code 1500-B} for the current detail line. */
  private String tranTypeDesc;

  /** {@code TRAN-CAT-TYPE-DESC} carried from {@code 1500-C} for the current detail line. */
  private String tranCatTypeDesc;

  /** Physical report writer: each fully-formatted 133-character line is handed to this sink. */
  private Consumer<String> reportSink;

  /**
   * Creates the service with its four lookup/data collaborators (constructor injection so the
   * service is trivially unit-testable with mock repositories).
   *
   * @param transactionRepository the transaction master ({@code TRANSACT}); must not be {@code
   *     null}
   * @param cardXrefRepository the card cross-reference store ({@code CARDXREF}); must not be {@code
   *     null}
   * @param transactionTypeRepository the transaction-type store ({@code TRANTYPE}); must not be
   *     {@code null}
   * @param transactionCategoryRepository the transaction-category store ({@code TRANCATG}); must
   *     not be {@code null}
   */
  public TransactionReportService(
      TransactionRepository transactionRepository,
      CardXrefRepository cardXrefRepository,
      TransactionTypeRepository transactionTypeRepository,
      TransactionCategoryRepository transactionCategoryRepository) {
    this.transactionRepository = transactionRepository;
    this.cardXrefRepository = cardXrefRepository;
    this.transactionTypeRepository = transactionTypeRepository;
    this.transactionCategoryRepository = transactionCategoryRepository;
  }

  /**
   * Produces the transaction detail report, reproducing the {@code CBTRN03C} {@code PROCEDURE
   * DIVISION} main paragraph (L159-217) statement-for-statement.
   *
   * <p>The order is: reset run state &rarr; announce start &rarr; open the six files ({@code
   * 0000}-{@code 0500}) &rarr; read the date parm ({@code 0550}) &rarr; the read/report loop &rarr;
   * close the six files ({@code 9000}-{@code 9500}) &rarr; announce end &rarr; {@code GOBACK}. The
   * COBOL sets no {@code RETURN-CODE}, so a normal completion simply returns; an unrecoverable I/O
   * condition instead propagates an {@link IoStatusException} (the abend-equivalent).
   *
   * @param startDate the inclusive lower bound ({@code YYYY-MM-DD}); replaces {@code WS-START-DATE}
   *     read from {@code DATEPARM}
   * @param endDate the inclusive upper bound ({@code YYYY-MM-DD}); replaces {@code WS-END-DATE}
   * @param reportSink receives every fully-formatted 133-character report line; must not be {@code
   *     null}
   * @throws IoStatusException if the date parm is unusable, a lookup record is missing, a report
   *     write fails, or an unexpected {@link DataAccessException} surfaces
   */
  public void run(String startDate, String endDate, Consumer<String> reportSink) {
    // <- CBTRN03C PROCEDURE DIVISION (main)
    // Reset all run-state to the COBOL WORKING-STORAGE initial VALUEs (single-threaded batch use).
    this.wsStartDate = startDate;
    this.wsEndDate = endDate;
    this.reportSink = reportSink;
    this.wsFirstTime = true;
    this.wsLineCounter = 0L;
    this.wsPageTotal = ZERO_AMOUNT;
    this.wsAccountTotal = ZERO_AMOUNT;
    this.wsGrandTotal = ZERO_AMOUNT;
    this.wsCurrCardNum = SIXTEEN_SPACES;
    this.endOfFile = false;
    this.currentTransaction = null;
    this.xrefAcctId = null;
    this.tranTypeDesc = null;
    this.tranCatTypeDesc = null;

    LOG.info(START_MESSAGE); // DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN03C'
    openTranfile(); // PERFORM 0000-TRANFILE-OPEN
    openReptfile(); // PERFORM 0100-REPTFILE-OPEN
    openCardxref(); // PERFORM 0200-CARDXREF-OPEN
    openTrantype(); // PERFORM 0300-TRANTYPE-OPEN
    openTrancatg(); // PERFORM 0400-TRANCATG-OPEN
    openDateparm(); // PERFORM 0500-DATEPARM-OPEN

    dateparmRead(); // PERFORM 0550-DATEPARM-READ

    // PERFORM UNTIL END-OF-FILE = 'Y'
    while (!endOfFile) {
      // IF END-OF-FILE = 'N' (guaranteed true at the loop top by the while condition)
      tranfileGetNext(); // PERFORM 1000-TRANFILE-GET-NEXT

      // IF TRAN-PROC-TS (1:10) >= WS-START-DATE AND <= WS-END-DATE ... ELSE NEXT SENTENCE.
      // NEXT SENTENCE transfers control past END-PERFORM, i.e. it exits the loop. Because the
      // working set is pre-filtered to the date range (mirroring the JCL SORT INCLUDE), an in-range
      // record always passes and, at EOF, the stale last record (still in range) also passes -
      // letting control reach the EOF-totals branch below. Only an empty working set yields a null
      // current record, failing the guard so the loop exits with no output. (COBOL parity.)
      if (!inDateRange(currentTransaction)) {
        break; // ELSE NEXT SENTENCE -> terminate the PERFORM
      }

      if (!endOfFile) { // IF END-OF-FILE = 'N'
        // COBOL parity (CBTRN03C L180): DISPLAY TRAN-RECORD, reproduced ONLY as a
        // REDACTED, DEBUG-level (off by default) diagnostic. The card number is masked
        // to its last four digits and the merchant/location fields are redacted, so no
        // cardholder/merchant PII reaches application or test logs. Byte-faithful report
        // output (from the report sink) is unaffected; see formatRedactedTranRecord.
        if (LOG.isDebugEnabled()) {
          LOG.debug("{}", formatRedactedTranRecord(currentTransaction)); // DISPLAY TRAN-RECORD
        }
        // COBOL parity: the control break is on TRAN-CARD-NUM (card number), not the account id.
        if (!currentTransaction.getTranCardNum().equals(wsCurrCardNum)) {
          if (!wsFirstTime) { // IF WS-FIRST-TIME = 'N'
            writeAccountTotals(); // PERFORM 1120-WRITE-ACCOUNT-TOTALS
          }
          this.wsCurrCardNum = currentTransaction.getTranCardNum(); // MOVE TRAN-CARD-NUM
          lookupXref(); // PERFORM 1500-A-LOOKUP-XREF (only on card break -> xrefAcctId retained)
        }
        lookupTrantype(); // PERFORM 1500-B-LOOKUP-TRANTYPE
        lookupTrancatg(); // PERFORM 1500-C-LOOKUP-TRANCATG
        writeTransactionReport(); // PERFORM 1100-WRITE-TRANSACTION-REPORT
      } else { // EOF totals branch (L197-204)
        LOG.info("TRAN-AMT {}", currentTransaction.getTranAmt()); // DISPLAY 'TRAN-AMT ' TRAN-AMT
        LOG.info("WS-PAGE-TOTAL{}", wsPageTotal); // DISPLAY 'WS-PAGE-TOTAL' WS-PAGE-TOTAL
        // COBOL parity (CBTRN03C L200-201): the stale last record's amount is ADDED AGAIN to the
        // page and account totals (it was already added during that record's
        // writeTransactionReport),
        // double-counting the final record in the printed page and grand totals. Do NOT "fix".
        this.wsPageTotal = wsPageTotal.add(currentTransaction.getTranAmt());
        this.wsAccountTotal = wsAccountTotal.add(currentTransaction.getTranAmt());
        writePageTotals(); // PERFORM 1110-WRITE-PAGE-TOTALS
        writeGrandTotals(); // PERFORM 1110-WRITE-GRAND-TOTALS
      }
    }

    closeTranfile(); // PERFORM 9000-TRANFILE-CLOSE
    closeReptfile(); // PERFORM 9100-REPTFILE-CLOSE
    closeCardxref(); // PERFORM 9200-CARDXREF-CLOSE
    closeTrantype(); // PERFORM 9300-TRANTYPE-CLOSE
    closeTrancatg(); // PERFORM 9400-TRANCATG-CLOSE
    closeDateparm(); // PERFORM 9500-DATEPARM-CLOSE

    LOG.info(END_MESSAGE); // DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN03C'
    // GOBACK -- normal return (CBTRN03C sets no RETURN-CODE).
  }

  /**
   * Builds the sorted, date-filtered working set, reproducing {@code 0000-TRANFILE-OPEN} (CBTRN03C
   * L376-392) together with the {@code TRANREPT.prc} {@code SORT}/{@code INCLUDE} step that feeds
   * it.
   *
   * <p>The legacy report never reads the live {@code TRANSACT} KSDS directly; the PROC first runs
   * {@code SORT FIELDS=(TRAN-CARD-NUM,A)} with {@code INCLUDE COND=(TRAN-PROC-DT,GE,
   * PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)}. This method reproduces both: (a) ascending
   * {@code TRAN-CARD-NUM} order via the bounded-memory streaming cursor {@code
   * streamAllByOrderByTranCardNumAsc()} (stable order; the legacy {@code SORT} declares no {@code
   * EQUALS}, AAP &sect;0.6.3, so tie order is the database's stable order), and (b) the date-range
   * filter on {@code TRAN-PROC-TS(1:10)} ({@code = TRAN-PROC-DT}) applied lazily as a stream {@code
   * filter} so the cursor yields only in-range records, exactly as iterating the pre-filtered SORT
   * output did. A {@code peek} stage detaches <em>every</em> fetched row (in-range or not) before
   * the filter, so the persistence context never accumulates the whole table. An unexpected {@link
   * DataAccessException} maps to the abend path.
   */
  private void openTranfile() {
    // <- CBTRN03C 0000-TRANFILE-OPEN (+ TRANREPT.prc SORT/INCLUDE)
    try {
      // Bounded-memory streaming cursor (HINT_FETCH_SIZE / HINT_READ_ONLY) ascending by
      // tranCardNum, replacing the findAll(Sort) full-table materialization (~513 MB at 200k). The
      // peek detaches each fetched row so memory stays O(fetch window); the filter reproduces the
      // JCL SORT INCLUDE date range (inDateRange returns false for an out-of-range or null record),
      // so the cursor yields exactly the pre-filtered in-range working set. Consumed inside the
      // tasklet transaction and closed in closeTranfile() to release the JDBC cursor.
      this.transactionStream = transactionRepository.streamAllByOrderByTranCardNumAsc();
      this.cursor =
          transactionStream.peek(entityManager::detach).filter(this::inDateRange).iterator();
      this.endOfFile = false; // MOVE 'N' is the initial END-OF-FILE state.
    } catch (DataAccessException ex) {
      throw abend(TRANFILE_DDNAME, "OPEN", FILE_STATUS_ABEND, TRANFILE_OPEN_ERROR_MESSAGE, ex);
    }
  }

  /**
   * Initializes the report output, reproducing {@code 0100-REPTFILE-OPEN} (CBTRN03C L394-410).
   *
   * <p>Under JPA/Spring the report sink is supplied to {@link #run(String, String, Consumer)} and
   * needs no physical open; the line counter is (re)armed to zero in {@code run(...)} from the
   * COBOL {@code WS-LINE-COUNTER ... VALUE 0}. This method is therefore a structural no-op kept for
   * 1:1 paragraph traceability.
   */
  private void openReptfile() {
    // <- CBTRN03C 0100-REPTFILE-OPEN: report sink is injected; nothing to open.
  }

  /**
   * Opens the card cross-reference store, reproducing {@code 0200-CARDXREF-OPEN} (CBTRN03C
   * L412-428). A JPA repository requires no explicit open, so this is a structural no-op retained
   * for traceability.
   */
  private void openCardxref() {
    // <- CBTRN03C 0200-CARDXREF-OPEN: JPA repository needs no explicit open.
  }

  /**
   * Opens the transaction-type store, reproducing {@code 0300-TRANTYPE-OPEN} (CBTRN03C L430-446). A
   * JPA repository requires no explicit open, so this is a structural no-op retained for
   * traceability.
   */
  private void openTrantype() {
    // <- CBTRN03C 0300-TRANTYPE-OPEN: JPA repository needs no explicit open.
  }

  /**
   * Opens the transaction-category store, reproducing {@code 0400-TRANCATG-OPEN} (CBTRN03C
   * L448-464). A JPA repository requires no explicit open, so this is a structural no-op retained
   * for traceability.
   */
  private void openTrancatg() {
    // <- CBTRN03C 0400-TRANCATG-OPEN: JPA repository needs no explicit open.
  }

  /**
   * Opens the date-parameter input, reproducing {@code 0500-DATEPARM-OPEN} (CBTRN03C L466-482). The
   * dates arrive as {@code run(...)} parameters, so there is no file to open; this is a structural
   * no-op retained for traceability.
   */
  private void openDateparm() {
    // <- CBTRN03C 0500-DATEPARM-OPEN: dates arrive as run(...) parameters; nothing to open.
  }

  /**
   * Validates and announces the reporting date range, reproducing {@code 0550-DATEPARM-READ}
   * (CBTRN03C L220-243).
   *
   * <p>The COBOL reads {@code WS-START-DATE}/{@code WS-END-DATE} from the {@code DATEPARM} record;
   * here they are supplied to {@link #run(String, String, Consumer)}. A successful read ({@code
   * FILE STATUS '00'}) emits {@code DISPLAY 'Reporting from ' WS-START-DATE ' to ' WS-END-DATE}
   * (L232-233). A missing/blank date mirrors the read-error path ({@code FILE STATUS} other than
   * {@code '00'}/{@code '10'}) and abends.
   */
  private void dateparmRead() {
    // <- CBTRN03C 0550-DATEPARM-READ
    if (wsStartDate == null || wsStartDate.isBlank() || wsEndDate == null || wsEndDate.isBlank()) {
      throw abend(DATEPARM_DDNAME, "READ", FILE_STATUS_IO_ERROR, DATEPARM_READ_ERROR_MESSAGE, null);
    }
    LOG.info("Reporting from {} to {}", wsStartDate, wsEndDate);
  }

  /**
   * Reads the next transaction from the working set, reproducing {@code 1000-TRANFILE-GET-NEXT}
   * (CBTRN03C L248-272).
   *
   * <p>Mirrors the COBOL {@code READ TRANFILE-FILE INTO TRAN-RECORD} status branching: a record
   * available ({@code FILE STATUS '00'}) becomes {@link #currentTransaction}; exhaustion ({@code
   * FILE STATUS '10'}) sets {@link #endOfFile} (normal loop termination, not an error). <b>COBOL
   * parity:</b> {@code READ ... INTO} does not overwrite the record area at EOF, so {@link
   * #currentTransaction} is left holding the last record (required for the EOF double-count) - it
   * is never cleared here. A non-{@code '00'}/{@code '10'} status is impossible for an in-memory
   * cursor; a real VSAM I/O error would map to {@link IoStatusException} (the unreachable {@code
   * 1000} abend branch, L266-269).
   */
  private void tranfileGetNext() {
    // <- CBTRN03C 1000-TRANFILE-GET-NEXT
    if (cursor.hasNext()) {
      this.currentTransaction = cursor.next(); // FILE STATUS '00': READ ... INTO TRAN-RECORD
    } else {
      this.endOfFile = true; // FILE STATUS '10' (APPL-EOF): MOVE 'Y' TO END-OF-FILE (record kept)
    }
  }

  /**
   * Writes one transaction detail line with first-time header emission and pagination, reproducing
   * {@code 1100-WRITE-TRANSACTION-REPORT} (CBTRN03C L274-290).
   *
   * <p>On the first detail it flips {@code WS-FIRST-TIME} to {@code 'N'} and emits the report
   * headers. On every {@code WS-LINE-COUNTER} that is an exact multiple of {@link #WS_PAGE_SIZE}
   * ({@code FUNCTION MOD(...) = 0}) it emits the page totals and a fresh header block. It then adds
   * the amount to the page and account totals and emits the detail line.
   */
  private void writeTransactionReport() {
    // <- CBTRN03C 1100-WRITE-TRANSACTION-REPORT
    if (wsFirstTime) { // IF WS-FIRST-TIME = 'Y'
      this.wsFirstTime = false; // MOVE 'N' TO WS-FIRST-TIME
      writeHeaders(); // (header start/end dates are taken from wsStartDate/wsEndDate) -> 1120
    }
    if (wsLineCounter % WS_PAGE_SIZE == 0) { // IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0
      writePageTotals(); // PERFORM 1110-WRITE-PAGE-TOTALS
      writeHeaders(); // PERFORM 1120-WRITE-HEADERS
    }
    // ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL.
    this.wsPageTotal = wsPageTotal.add(currentTransaction.getTranAmt());
    this.wsAccountTotal = wsAccountTotal.add(currentTransaction.getTranAmt());
    writeDetail(); // PERFORM 1120-WRITE-DETAIL
  }

  /**
   * Emits the page-total line and trailing rule, reproducing {@code 1110-WRITE-PAGE-TOTALS}
   * (CBTRN03C L293-304). Layout {@code REPORT-PAGE-TOTALS} (CVTRA07Y L50-54), content width 112.
   *
   * <p>After writing the line it rolls the page total into the grand total ({@code ADD
   * WS-PAGE-TOTAL TO WS-GRAND-TOTAL}), resets the page total to zero, bumps the line counter, emits
   * the 133-dash separator and bumps the counter again.
   */
  private void writePageTotals() {
    // <- CBTRN03C 1110-WRITE-PAGE-TOTALS
    String line =
        CobolStringUtils.fixedWidth(PAGE_TOTAL_LABEL, 11) // FILLER X(11) 'Page Total'
            + PAGE_TOTAL_DOTS // FILLER X(86) ALL '.'
            + NumberFormatter.formatTotalAmount(
                wsPageTotal); // REPT-PAGE-TOTAL +ZZZ,ZZZ,ZZZ.ZZ (15)
    writeReportRec(line);
    this.wsGrandTotal = wsGrandTotal.add(wsPageTotal); // ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL
    this.wsPageTotal = ZERO_AMOUNT; // MOVE 0 TO WS-PAGE-TOTAL
    this.wsLineCounter++;
    writeReportRec(DASH_133); // WRITE TRANSACTION-HEADER-2 (dashes)
    this.wsLineCounter++;
  }

  /**
   * Emits the account-total line and trailing rule, reproducing {@code 1120-WRITE-ACCOUNT-TOTALS}
   * (CBTRN03C L306-316). Layout {@code REPORT-ACCOUNT-TOTALS} (CVTRA07Y L56-60), content width 112.
   *
   * <p>Performed only on a card control break (and never at end-of-file), so the final card's
   * account-total line is never written (COBOL parity).
   */
  private void writeAccountTotals() {
    // <- CBTRN03C 1120-WRITE-ACCOUNT-TOTALS
    String line =
        CobolStringUtils.fixedWidth(ACCOUNT_TOTAL_LABEL, 13) // FILLER X(13) 'Account Total'
            + ACCOUNT_TOTAL_DOTS // FILLER X(84) ALL '.'
            + NumberFormatter.formatTotalAmount(wsAccountTotal); // REPT-ACCOUNT-TOTAL (15)
    writeReportRec(line);
    this.wsAccountTotal = ZERO_AMOUNT; // MOVE 0 TO WS-ACCOUNT-TOTAL
    this.wsLineCounter++;
    writeReportRec(DASH_133); // WRITE TRANSACTION-HEADER-2 (dashes)
    this.wsLineCounter++;
  }

  /**
   * Emits the grand-total line, reproducing {@code 1110-WRITE-GRAND-TOTALS} (CBTRN03C L318-322).
   * Layout {@code REPORT-GRAND-TOTALS} (CVTRA07Y L62-66), content width 112. <b>COBOL parity:</b>
   * this paragraph performs no line-counter increment.
   */
  private void writeGrandTotals() {
    // <- CBTRN03C 1110-WRITE-GRAND-TOTALS
    String line =
        CobolStringUtils.fixedWidth(GRAND_TOTAL_LABEL, 11) // FILLER X(11) 'Grand Total'
            + PAGE_TOTAL_DOTS // FILLER X(86) ALL '.'
            + NumberFormatter.formatTotalAmount(wsGrandTotal); // REPT-GRAND-TOTAL (15)
    writeReportRec(line);
  }

  /**
   * Emits the four-line report header block, reproducing {@code 1120-WRITE-HEADERS} (CBTRN03C
   * L324-341): the name header, a blank line, column header 1, and the full-width dash rule. The
   * line counter is incremented once after each line (net {@code +4}).
   *
   * <p>The name header ({@code REPORT-NAME-HEADER}, CVTRA07Y L4-13, content width 115) is assembled
   * from the copybook {@code VALUE} fragments plus the current start/end dates ({@code
   * REPT-START-DATE}/{@code REPT-END-DATE}).
   */
  private void writeHeaders() {
    // <- CBTRN03C 1120-WRITE-HEADERS
    String nameHeader =
        CobolStringUtils.fixedWidth(REPT_SHORT_NAME, 38) // REPT-SHORT-NAME X(38) 'DALYREPT'
            + CobolStringUtils.fixedWidth(REPT_LONG_NAME, 41) // REPT-LONG-NAME X(41)
            + REPT_DATE_HEADER // REPT-DATE-HEADER X(12) 'Date Range: '
            + CobolStringUtils.fixedWidth(wsStartDate, 10) // REPT-START-DATE X(10)
            + DATE_RANGE_SEPARATOR // FILLER X(04) ' to '
            + CobolStringUtils.fixedWidth(wsEndDate, 10); // REPT-END-DATE X(10)
    writeReportRec(nameHeader);
    this.wsLineCounter++;
    writeReportRec(""); // WS-BLANK-LINE (133 spaces) -- writeReportRec pads "" to 133.
    this.wsLineCounter++;
    writeReportRec(TRANSACTION_HEADER_1); // TRANSACTION-HEADER-1 (content 114)
    this.wsLineCounter++;
    writeReportRec(DASH_133); // TRANSACTION-HEADER-2 (X(133) ALL '-')
    this.wsLineCounter++;
  }

  /**
   * Writes one physical report record, reproducing {@code 1111-WRITE-REPORT-REC} (CBTRN03C
   * L343-359).
   *
   * <p>The content is right-padded with spaces (or truncated) to exactly {@link
   * #REPORT_RECORD_LENGTH} characters, modeling the {@code WRITE FD-REPTFILE-REC PIC X(133)}, then
   * handed to the report sink. The COBOL checks {@code TRANREPT-STATUS} after the {@code WRITE} and
   * abends on a non-{@code '00'} status (L346-358); if the sink throws, that failure is translated
   * into the same abend ({@link IoStatusException}).
   *
   * @param content the report line content (&le; 133 chars); padded to the record length
   */
  private void writeReportRec(String content) {
    // <- CBTRN03C 1111-WRITE-REPORT-REC
    String line133 = CobolStringUtils.fixedWidth(content, REPORT_RECORD_LENGTH);
    try {
      reportSink.accept(line133);
    } catch (RuntimeException ex) {
      throw abend(REPTFILE_DDNAME, "WRITE", FILE_STATUS_IO_ERROR, REPTFILE_WRITE_ERROR_MESSAGE, ex);
    }
  }

  /**
   * Formats and emits one transaction detail line, reproducing {@code 1120-WRITE-DETAIL} (CBTRN03C
   * L361-374).
   *
   * <p>The line is assembled per {@code TRANSACTION-DETAIL-REPORT} (CVTRA07Y L15-31), content width
   * 114 (then padded to 133 by {@link #writeReportRec(String)}), in this exact field order:
   *
   * <pre>
   *   TRAN-REPORT-TRANS-ID   X(16)  tranId, left-justified, space-padded
   *   FILLER                 X(01)  one space
   *   TRAN-REPORT-ACCOUNT-ID X(11)  xrefAcctId as an 11-digit zero-padded number
   *   FILLER                 X(01)  one space
   *   TRAN-REPORT-TYPE-CD    X(02)  tranTypeCd
   *   FILLER                 X(01)  '-'
   *   TRAN-REPORT-TYPE-DESC  X(15)  tranTypeDesc (source X(50) truncated to 15)
   *   FILLER                 X(01)  one space
   *   TRAN-REPORT-CAT-CD     9(04)  tranCatCd as a 4-digit zero-padded number
   *   FILLER                 X(01)  '-'
   *   TRAN-REPORT-CAT-DESC   X(29)  tranCatTypeDesc (source X(50) truncated to 29)
   *   FILLER                 X(01)  one space
   *   TRAN-REPORT-SOURCE     X(10)  tranSource
   *   FILLER                 X(04)  four spaces
   *   TRAN-REPORT-AMT        PIC -ZZZ,ZZZ,ZZZ.ZZ (15)  tranAmt
   *   FILLER                 X(02)  two spaces
   * </pre>
   *
   * <p>The COBOL {@code INITIALIZE TRANSACTION-DETAIL-REPORT} (L362) does not reset the {@code
   * FILLER} literals ({@code '-'} and spaces); they are constants in this concatenation, so that is
   * honored automatically.
   */
  private void writeDetail() {
    // <- CBTRN03C 1120-WRITE-DETAIL
    String line =
        CobolStringUtils.fixedWidth(currentTransaction.getTranId(), 16)
            + " "
            + CobolStringUtils.padLeftZeros(xrefAcctId, 11)
            + " "
            + CobolStringUtils.fixedWidth(currentTransaction.getTranTypeCd(), 2)
            + "-"
            + CobolStringUtils.fixedWidth(tranTypeDesc, 15)
            + " "
            + NumberFormatter.formatCategoryCode(
                parseCategoryCode(currentTransaction.getTranCatCd()))
            + "-"
            + CobolStringUtils.fixedWidth(tranCatTypeDesc, 29)
            + " "
            + CobolStringUtils.fixedWidth(currentTransaction.getTranSource(), 10)
            + "    "
            + NumberFormatter.formatReportAmount(currentTransaction.getTranAmt())
            + "  ";
    writeReportRec(line);
    this.wsLineCounter++; // ADD 1 TO WS-LINE-COUNTER
  }

  /**
   * Looks up the card cross-reference, reproducing {@code 1500-A-LOOKUP-XREF} (CBTRN03C L484-492).
   *
   * <p>The COBOL {@code READ XREF-FILE ... INVALID KEY} treats a missing record as <b>FATAL</b>
   * (the {@code INVALID KEY} path {@code MOVE 23 TO IO-STATUS} then abends). Accordingly {@link
   * Optional#empty()} maps to {@link IoStatusException} with {@code FILE STATUS '23'} - never a
   * {@code RecordNotFoundException} (that is online-only). The found {@code XREF-ACCT-ID} ({@code
   * 9(11)}) is retained across every detail line of the same card, because this paragraph runs only
   * on a card control break (COBOL parity).
   */
  private void lookupXref() {
    // <- CBTRN03C 1500-A-LOOKUP-XREF
    Optional<CardXref> xref;
    try {
      xref = cardXrefRepository.findById(wsCurrCardNum);
    } catch (DataAccessException ex) {
      throw abend(CARDXREF_DDNAME, "READ", FILE_STATUS_ABEND, XREF_READ_ERROR_MESSAGE, ex);
    }
    if (xref.isEmpty()) {
      // INVALID KEY: DISPLAY 'INVALID CARD NUMBER : ' + MOVE 23 + 9910 + 9999 (FATAL).
      throw abend(
          CARDXREF_DDNAME,
          "READ",
          FILE_STATUS_NOT_FOUND,
          "INVALID CARD NUMBER : " + wsCurrCardNum,
          null);
    }
    this.xrefAcctId = String.valueOf(xref.get().getXrefAcctId());
  }

  /**
   * Looks up the transaction type, reproducing {@code 1500-B-LOOKUP-TRANTYPE} (CBTRN03C L494-502).
   * A missing record is <b>FATAL</b> (the {@code INVALID KEY} path {@code MOVE 23} then abends), so
   * {@link Optional#empty()} maps to {@link IoStatusException} with {@code FILE STATUS '23'}; the
   * found {@code TRAN-TYPE-DESC} is carried into the next detail line.
   */
  private void lookupTrantype() {
    // <- CBTRN03C 1500-B-LOOKUP-TRANTYPE
    Optional<TransactionType> type;
    try {
      type = transactionTypeRepository.findById(currentTransaction.getTranTypeCd());
    } catch (DataAccessException ex) {
      throw abend(TRANTYPE_DDNAME, "READ", FILE_STATUS_ABEND, TRANTYPE_READ_ERROR_MESSAGE, ex);
    }
    if (type.isEmpty()) {
      // INVALID KEY: DISPLAY 'INVALID TRANSACTION TYPE : ' + MOVE 23 + 9910 + 9999 (FATAL).
      throw abend(
          TRANTYPE_DDNAME,
          "READ",
          FILE_STATUS_NOT_FOUND,
          "INVALID TRANSACTION TYPE : " + currentTransaction.getTranTypeCd(),
          null);
    }
    this.tranTypeDesc = type.get().getTranTypeDesc();
  }

  /**
   * Looks up the transaction category, reproducing {@code 1500-C-LOOKUP-TRANCATG} (CBTRN03C
   * L504-512). The key is the composite {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} modeled by {@link
   * TransactionCategoryId}. A missing record is <b>FATAL</b> (the {@code INVALID KEY} path {@code
   * MOVE 23} then abends), so {@link Optional#empty()} maps to {@link IoStatusException} with
   * {@code FILE STATUS '23'}; the found {@code TRAN-CAT-TYPE-DESC} is carried into the next detail
   * line.
   */
  private void lookupTrancatg() {
    // <- CBTRN03C 1500-C-LOOKUP-TRANCATG
    TransactionCategoryId key =
        new TransactionCategoryId(
            currentTransaction.getTranTypeCd(), currentTransaction.getTranCatCd());
    Optional<TransactionCategory> category;
    try {
      category = transactionCategoryRepository.findById(key);
    } catch (DataAccessException ex) {
      throw abend(TRANCATG_DDNAME, "READ", FILE_STATUS_ABEND, TRANCATG_READ_ERROR_MESSAGE, ex);
    }
    if (category.isEmpty()) {
      // INVALID KEY: DISPLAY 'INVALID TRAN CATG KEY : ' + MOVE 23 + 9910 + 9999 (FATAL).
      throw abend(
          TRANCATG_DDNAME,
          "READ",
          FILE_STATUS_NOT_FOUND,
          "INVALID TRAN CATG KEY : "
              + currentTransaction.getTranTypeCd()
              + currentTransaction.getTranCatCd(),
          null);
    }
    this.tranCatTypeDesc = category.get().getTranCatTypeDesc();
  }

  /**
   * Closes the transaction working set, reproducing {@code 9000-TRANFILE-CLOSE} (CBTRN03C
   * L514-530). Closes the streaming cursor (releasing the underlying JDBC cursor) and then clears
   * the in-memory references — the Java counterpart of the VSAM {@code CLOSE}. The stream close is
   * null-safe and idempotent.
   */
  private void closeTranfile() {
    // <- CBTRN03C 9000-TRANFILE-CLOSE
    if (this.transactionStream != null) {
      this.transactionStream.close();
      this.transactionStream = null;
    }
    this.cursor = null;
  }

  /**
   * Closes the report output, reproducing {@code 9100-REPTFILE-CLOSE} (CBTRN03C L532-549). The sink
   * is owned by the caller, so this is a structural no-op retained for traceability.
   */
  private void closeReptfile() {
    // <- CBTRN03C 9100-REPTFILE-CLOSE: report sink is owned by the caller; nothing to close.
  }

  /**
   * Closes the card cross-reference store, reproducing {@code 9200-CARDXREF-CLOSE} (CBTRN03C
   * L551-567). A JPA repository requires no explicit close, so this is a structural no-op.
   */
  private void closeCardxref() {
    // <- CBTRN03C 9200-CARDXREF-CLOSE: JPA repository needs no explicit close.
  }

  /**
   * Closes the transaction-type store, reproducing {@code 9300-TRANTYPE-CLOSE} (CBTRN03C L569-585).
   * A JPA repository requires no explicit close, so this is a structural no-op.
   */
  private void closeTrantype() {
    // <- CBTRN03C 9300-TRANTYPE-CLOSE: JPA repository needs no explicit close.
  }

  /**
   * Closes the transaction-category store, reproducing {@code 9400-TRANCATG-CLOSE} (CBTRN03C
   * L587-603). A JPA repository requires no explicit close, so this is a structural no-op.
   */
  private void closeTrancatg() {
    // <- CBTRN03C 9400-TRANCATG-CLOSE: JPA repository needs no explicit close.
  }

  /**
   * Closes the date-parameter input, reproducing {@code 9500-DATEPARM-CLOSE} (CBTRN03C L605-621).
   * The dates were supplied as parameters, so this is a structural no-op retained for traceability.
   */
  private void closeDateparm() {
    // <- CBTRN03C 9500-DATEPARM-CLOSE: dates came from run(...) parameters; nothing to close.
  }

  /**
   * Builds the abend, reproducing {@code 9999-ABEND-PROGRAM} together with the {@code
   * 9910-DISPLAY-IO-STATUS} operator display (CBTRN03C L626-646).
   *
   * <p>The externally observable {@code DISPLAY}s are emitted in the legacy order: the
   * verb-specific {@code 'INVALID ...'}/{@code 'ERROR ...'} line, then the {@code 'FILE STATUS IS:
   * NNNN'} line (via {@link IoStatusException#getDisplayMessage()}, i.e. {@code
   * 9910-DISPLAY-IO-STATUS}), then {@code 'ABENDING PROGRAM'}. The returned {@link
   * IoStatusException} carries the file name, the failing operation, the two-byte {@code FILE
   * STATUS} and the originating cause; its {@link IoStatusException#BATCH_ABEND_CODE} (999 / {@code
   * CEE3ABD}) is the migrated abend code. Callers {@code throw} the result so control transfers
   * immediately, mirroring {@code CALL 'CEE3ABD'}.
   *
   * @param fileName the logical file name of the failing store
   * @param operation the failing COBOL I/O verb ({@code "OPEN"}, {@code "READ"}, or {@code
   *     "WRITE"})
   * @param fileStatus the two-byte {@code FILE STATUS} to report ({@code "23"}, {@code "99"} or
   *     {@code "12"})
   * @param errorDisplay the verb-specific {@code DISPLAY} literal to emit first
   * @param cause the originating throwable, or {@code null} for an {@code INVALID KEY} / validation
   *     abend
   * @return the {@link IoStatusException} for the caller to {@code throw}
   */
  private IoStatusException abend(
      String fileName, String operation, String fileStatus, String errorDisplay, Throwable cause) {
    // <- CBTRN03C 9999-ABEND-PROGRAM + 9910-DISPLAY-IO-STATUS
    LOG.error("{}", errorDisplay); // DISPLAY 'INVALID ...' / 'ERROR ...'
    IoStatusException ioStatusException =
        (cause == null)
            ? new IoStatusException(fileName, operation, fileStatus)
            : new IoStatusException(fileName, operation, fileStatus, cause);
    LOG.error("{}", ioStatusException.getDisplayMessage()); // 9910-DISPLAY-IO-STATUS
    LOG.error(ABEND_MESSAGE); // DISPLAY 'ABENDING PROGRAM'
    return ioStatusException;
  }

  /**
   * Evaluates the COBOL in-loop date guard {@code IF TRAN-PROC-TS (1:10) >= WS-START-DATE AND <=
   * WS-END-DATE} (CBTRN03C L173-174).
   *
   * <p>Because the working set is pre-filtered to the date range in {@link #openTranfile()}
   * (mirroring the JCL {@code SORT INCLUDE}), this guard is always {@code true} for a real in-range
   * record, and at end-of-file the stale last record (still in range) also passes - allowing the
   * caller to reach the EOF-totals branch. A {@code null} record (an empty working set) is treated
   * as out of range so the caller exits with no output, reproducing the COBOL {@code NEXT SENTENCE}
   * over the uninitialized record (COBOL parity).
   *
   * @param tx the current record, or {@code null} when no record has been read
   * @return {@code true} when the record's processing date is within {@code [wsStartDate,
   *     wsEndDate]}
   */
  private boolean inDateRange(Transaction tx) {
    if (tx == null) {
      return false;
    }
    String procDate = tx.getTranProcTs().substring(0, 10); // TRAN-PROC-TS (1:10) = TRAN-PROC-DT
    return procDate.compareTo(wsStartDate) >= 0 && procDate.compareTo(wsEndDate) <= 0;
  }

  /**
   * Parses a {@code TRAN-CAT-CD} display value into the {@code int} consumed by {@link
   * NumberFormatter#formatCategoryCode(int)}.
   *
   * <p>{@code TRAN-CAT-CD} is a {@code PIC 9(04)} numeric-display field stored as a {@code String}
   * (for example {@code "0001"}); this returns its numeric value so it can be re-rendered as a
   * four-digit zero-padded field. A blank value is treated as zero, mirroring a COBOL numeric move
   * of an unset field.
   *
   * @param catCd the category code string, possibly {@code null} or blank
   * @return the numeric category code ({@code 0} when blank)
   */
  private static int parseCategoryCode(String catCd) {
    String trimmed = (catCd == null) ? "" : catCd.trim();
    return trimmed.isEmpty() ? 0 : Integer.parseInt(trimmed);
  }

  /**
   * Renders a <strong>redacted</strong> diagnostic image of the record processed by the main loop,
   * mirroring the intent of the COBOL {@code DISPLAY TRAN-RECORD} (CBTRN03C L180) without leaking
   * sensitive data.
   *
   * <p>The legacy COBOL displayed the contiguous {@code TRAN-RECORD} group (copybook {@code
   * CVTRA05Y}) verbatim to SYSOUT. Reproducing that image faithfully would emit the full card
   * number and the merchant/location fields into application and test logs, violating the project's
   * security and PII-hygiene posture (AAP &sect;0.1.1 security hardening, &sect;0.7.3). This method
   * therefore masks the card number to its last four digits (matching the project-wide convention
   * used by {@code CardDemoCommarea}, AAP &sect;0.6.6) and redacts the merchant identifier, name,
   * city, and ZIP. The non-sensitive reference fields (transaction id, type/category codes, source,
   * description, amount, and timestamps) are retained for diagnostic value. The {@link BigDecimal}
   * amount is rendered verbatim and never converted to {@code float}/{@code double} (AAP
   * &sect;0.6.1). This output is a redacted diagnostic only; the byte-faithful report image is
   * produced solely by the report sink and is unaffected.
   *
   * @param tx the record to render; never {@code null} on the normal {@code '00'} path
   * @return a redacted, single-line diagnostic image of the record
   */
  private String formatRedactedTranRecord(Transaction tx) {
    // <- CBTRN03C main-loop DISPLAY TRAN-RECORD, redacted for PII hygiene (CVTRA05Y field order)
    return new StringBuilder()
        .append("tranId=")
        .append(tx.getTranId())
        .append(" typeCd=")
        .append(tx.getTranTypeCd())
        .append(" catCd=")
        .append(tx.getTranCatCd())
        .append(" source=")
        .append(tx.getTranSource())
        .append(" desc=")
        .append(tx.getTranDesc())
        .append(" amt=")
        .append(tx.getTranAmt())
        .append(" merchant=[REDACTED]")
        .append(" cardNum=")
        .append(maskCardNumber(tx.getTranCardNum()))
        .append(" origTs=")
        .append(tx.getTranOrigTs())
        .append(" procTs=")
        .append(tx.getTranProcTs())
        .toString();
  }

  /**
   * Masks a card number for safe inclusion in diagnostic logs, revealing at most the last four
   * digits and replacing every preceding character with {@code '*'} (matching the {@code
   * CardDemoCommarea} masking convention, AAP &sect;0.6.6).
   *
   * <p>A {@code null} value renders as {@code "null"}; a value of four characters or fewer (after
   * trimming the fixed-width {@code PIC X(16)} padding) is fully masked so that no digits are
   * exposed.
   *
   * @param cardNum the raw card number ({@code TRAN-CARD-NUM}, {@code PIC X(16)}); may be {@code
   *     null}
   * @return the masked card number, never exposing more than the final four digits
   */
  private static String maskCardNumber(String cardNum) {
    if (cardNum == null) {
      return "null";
    }
    String trimmed = cardNum.trim();
    if (trimmed.length() <= 4) {
      return "*".repeat(trimmed.length());
    }
    int maskLength = trimmed.length() - 4;
    return "*".repeat(maskLength) + trimmed.substring(maskLength);
  }
}
