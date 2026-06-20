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
import com.aws.carddemo.domain.DisclosureGroup;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.domain.id.DisclosureGroupId;
import com.aws.carddemo.domain.id.TransactionCategoryBalanceId;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.DisclosureGroupRepository;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.CobolStringUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Interest-and-fee calculation batch service, migrated with 100% behavioral parity from the legacy
 * z/OS COBOL program {@code CBACT04C} (behavioral spec {@code legacy/app/cbl/CBACT04C.cbl},
 * "interest calculator program"). This program carries the single most parity-critical calculation
 * in the whole migration: the COBOL {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) /
 * 1200} carries <strong>no {@code ROUNDED} phrase</strong>, so the result is <em>truncated</em> to
 * the receiving field's scale of 2. The Java translation reproduces that truncation exactly with
 * {@link java.math.BigDecimal} and {@link java.math.RoundingMode#DOWN} at scale 2 — never {@code
 * float}/{@code double}, never {@link java.math.RoundingMode#HALF_UP} (Agent Action Plan
 * &sect;0.6.1, &sect;0.7.1).
 *
 * <p>The legacy program reads the transaction-category-balance store ({@code TCATBALF}) in
 * ascending composite-key order, and on every change of account id ("control break") posts the
 * accumulated interest to the previous account, then for each balance row looks up the
 * disclosure-group interest rate (falling back to the {@code DEFAULT} group when the account's
 * group is unknown), computes the monthly interest, accumulates it, and writes an interest {@link
 * Transaction}. Files map to JPA stores as follows: {@code TCATBALF} &rarr; {@link
 * TransactionCategoryBalanceRepository} (sequential cursor), {@code DISCGRP} &rarr; {@link
 * DisclosureGroupRepository} (keyed), {@code ACCTFILE} &rarr; {@link AccountRepository} (read +
 * rewrite), {@code XREFFILE} &rarr; {@link CardXrefRepository} (keyed by the alternate index on
 * account id), and {@code TRANSACT} &rarr; {@link TransactionRepository} (output). Every COBOL
 * {@code DISPLAY} maps to an SLF4J log statement, and the COBOL {@code FILE STATUS}-to-abend
 * handling maps to {@link IoStatusException} (AAP &sect;0.6.4, &sect;0.6.6).
 *
 * <p>Per migration convention this service contains <strong>no Spring Batch types</strong>: {@link
 * #run(String)} is a plain method that the batch tier ({@code InterestCalculationJobConfig} in
 * {@code com.aws.carddemo.batch}) later invokes from a tasklet, supplying {@code parmDate} from a
 * Spring Batch {@code JobParameter} (the legacy {@code EXEC PGM=CBACT04C,PARM='2022071800'} run
 * date in {@code legacy/app/jcl/INTCALC.jcl}). An unhandled {@link IoStatusException} therefore
 * becomes a failed Spring Batch step exit status — the abend-equivalent (AAP &sect;0.6.6).
 *
 * <p><strong>Control-break correctness and the final account update.</strong> The COBOL main loop
 * is {@code PERFORM UNTIL END-OF-FILE = 'Y'} with an {@code IF END-OF-FILE = 'N' ... ELSE PERFORM
 * 1050-UPDATE-ACCOUNT} structure (L188-222). The {@code ELSE} branch is the intended posting of the
 * <em>last</em> account's accumulated interest at end-of-file. This service realizes it faithfully
 * as a single post-loop {@link #updateAccount()} call, guarded by {@code !wsFirstTime} so an empty
 * input file updates no account (AAP &sect;0.4.1, "Final 1050-UPDATE-ACCOUNT at EOF"). The
 * golden-file parity scenario confirms the last account's balance is updated by this call.
 *
 * <p><strong>Statefulness / threading.</strong> Mirroring the single-run COBOL program, this
 * service keeps the in-flight scan state (cursor, control-break and accumulator fields, current
 * records) in instance fields and resets them at the start of every {@link #run(String)}. A single
 * {@code run} is self-contained and repeatable, but the method is <em>not</em> re-entrant: it must
 * be driven by one thread per execution, exactly as the batch tasklet drives it (there is no
 * {@code @Version} column on {@link Account}, so the legacy single-threaded {@code READ}/{@code
 * REWRITE} is preserved without lock retries).
 *
 * <p><strong>COBOL paragraph &rarr; Java method traceability</strong> (AAP &sect;0.6.7):
 *
 * <table border="1">
 *   <caption>CBACT04C paragraph to Java method mapping</caption>
 *   <tr><th>COBOL paragraph</th><th>Java method</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION USING EXTERNAL-PARMS} (main)</td>
 *       <td>{@link #run(String)}</td></tr>
 *   <tr><td>{@code 0000-TCATBALF-OPEN}</td><td>{@link #openTcatbalf()}</td></tr>
 *   <tr><td>{@code 0100-XREFFILE-OPEN}</td><td>{@link #openXreffile()}</td></tr>
 *   <tr><td>{@code 0200-DISCGRP-OPEN}</td><td>{@link #openDiscgrp()}</td></tr>
 *   <tr><td>{@code 0300-ACCTFILE-OPEN}</td><td>{@link #openAcctfile()}</td></tr>
 *   <tr><td>{@code 0400-TRANFILE-OPEN}</td><td>{@link #openTranfile()}</td></tr>
 *   <tr><td>{@code 1000-TCATBALF-GET-NEXT}</td><td>{@link #tcatbalfGetNext()}</td></tr>
 *   <tr><td>{@code 1050-UPDATE-ACCOUNT}</td><td>{@link #updateAccount()}</td></tr>
 *   <tr><td>{@code 1100-GET-ACCT-DATA}</td><td>{@link #getAcctData(Long)}</td></tr>
 *   <tr><td>{@code 1110-GET-XREF-DATA}</td><td>{@link #getXrefData(Long)}</td></tr>
 *   <tr><td>{@code 1200-GET-INTEREST-RATE}</td><td>{@link #getInterestRate()}</td></tr>
 *   <tr><td>{@code 1200-A-GET-DEFAULT-INT-RATE}</td>
 *       <td>{@link #getDefaultInterestRate(String, String)}</td></tr>
 *   <tr><td>{@code 1300-COMPUTE-INTEREST}</td><td>{@link #computeInterest(String)}</td></tr>
 *   <tr><td>{@code 1300-B-WRITE-TX}</td><td>{@link #writeInterestTx(String, BigDecimal)}</td></tr>
 *   <tr><td>{@code 1400-COMPUTE-FEES}</td><td>{@link #computeFees()} (empty stub)</td></tr>
 *   <tr><td>{@code Z-GET-DB2-FORMAT-TIMESTAMP}</td><td>{@link #getDb2FormatTimestamp()}</td></tr>
 *   <tr><td>{@code 9000-TCATBALF-CLOSE}</td><td>{@link #closeTcatbalf()}</td></tr>
 *   <tr><td>{@code 9100-XREFFILE-CLOSE}</td><td>{@link #closeXreffile()}</td></tr>
 *   <tr><td>{@code 9200-DISCGRP-CLOSE}</td><td>{@link #closeDiscgrp()}</td></tr>
 *   <tr><td>{@code 9300-ACCTFILE-CLOSE}</td><td>{@link #closeAcctfile()}</td></tr>
 *   <tr><td>{@code 9400-TRANFILE-CLOSE}</td><td>{@link #closeTranfile()}</td></tr>
 *   <tr><td>{@code 9910-DISPLAY-IO-STATUS} + {@code 9999-ABEND-PROGRAM}</td>
 *       <td>{@link #abend(String, String, String, String, DataAccessException)} &rarr; throw {@link
 *       IoStatusException}</td></tr>
 * </table>
 */
@Service
public class InterestCalculationService {

  /** SLF4J logger; every COBOL {@code DISPLAY} in {@code CBACT04C} routes through this logger. */
  private static final Logger LOG = LoggerFactory.getLogger(InterestCalculationService.class);

  /** {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT04C'} (CBACT04C L181). */
  private static final String START_MESSAGE = "START OF EXECUTION OF PROGRAM CBACT04C";

  /** {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT04C'} (CBACT04C L230). */
  private static final String END_MESSAGE = "END OF EXECUTION OF PROGRAM CBACT04C";

  /** {@code DISPLAY 'ABENDING PROGRAM'} from {@code 9999-ABEND-PROGRAM} (CBACT04C L629). */
  private static final String ABEND_MESSAGE = "ABENDING PROGRAM";

  /** Logical {@code ASSIGN TO} name of the transaction-category-balance file (CBACT04C L28). */
  private static final String TCATBALF_DDNAME = "TCATBALF";

  /** Logical {@code ASSIGN TO} name of the card cross-reference file (CBACT04C L34). */
  private static final String XREFFILE_DDNAME = "XREFFILE";

  /** Logical {@code ASSIGN TO} name of the disclosure-group file (CBACT04C L47). */
  private static final String DISCGRP_DDNAME = "DISCGRP";

  /** Logical {@code ASSIGN TO} name of the account master file (CBACT04C L41). */
  private static final String ACCTFILE_DDNAME = "ACCTFILE";

  /** Logical {@code ASSIGN TO} name of the output transaction file (CBACT04C L53). */
  private static final String TRANSACT_DDNAME = "TRANSACT";

  /** {@code DISPLAY 'ERROR OPENING TRANSACTION CATEGORY BALANCE'} (CBACT04C L245). */
  private static final String TCATBALF_OPEN_ERROR = "ERROR OPENING TRANSACTION CATEGORY BALANCE";

  /** {@code DISPLAY 'ERROR READING TRANSACTION CATEGORY FILE'} (CBACT04C L342). */
  private static final String TCATBALF_READ_ERROR = "ERROR READING TRANSACTION CATEGORY FILE";

  /** {@code DISPLAY 'ERROR READING XREF FILE'} (CBACT04C L408). */
  private static final String XREF_READ_ERROR = "ERROR READING XREF FILE";

  /** {@code DISPLAY 'ERROR READING ACCOUNT FILE'} (CBACT04C L386). */
  private static final String ACCT_READ_ERROR = "ERROR READING ACCOUNT FILE";

  /** {@code DISPLAY 'ERROR RE-WRITING ACCOUNT FILE'} (CBACT04C L365). */
  private static final String ACCT_REWRITE_ERROR = "ERROR RE-WRITING ACCOUNT FILE";

  /** {@code DISPLAY 'ERROR READING DISCLOSURE GROUP FILE'} (CBACT04C L431). */
  private static final String DISCGRP_READ_ERROR = "ERROR READING DISCLOSURE GROUP FILE";

  /** {@code DISPLAY 'ERROR READING DEFAULT DISCLOSURE GROUP'} (CBACT04C L455). */
  private static final String DEFAULT_DISCGRP_READ_ERROR = "ERROR READING DEFAULT DISCLOSURE GROUP";

  /** {@code DISPLAY 'ERROR WRITING TRANSACTION RECORD'} (CBACT04C L510). */
  private static final String TRAN_WRITE_ERROR = "ERROR WRITING TRANSACTION RECORD";

  /**
   * Synthetic two-byte {@code FILE STATUS} used when an unexpected {@link DataAccessException}
   * surfaces from a repository. It corresponds to the COBOL {@code 9910-DISPLAY-IO-STATUS}
   * "9-prefixed / abnormal" branch; {@link IoStatusException#BATCH_ABEND_CODE} (999 / {@code
   * CEE3ABD}) already encodes the run-unit abend.
   */
  private static final String ABEND_FILE_STATUS = "99";

  /**
   * COBOL FILE STATUS {@code '23'} (record not found / {@code INVALID KEY}). In {@code
   * 1100-GET-ACCT-DATA}, {@code 1110-GET-XREF-DATA}, and {@code 1200-A-GET-DEFAULT-INT-RATE},
   * {@code CBACT04C} accepts <strong>only</strong> {@code '00'}, so a missing record there is a
   * hard abend (<em>not</em> an {@code Optional}-skip and <em>never</em> a {@code
   * RecordNotFoundException}).
   */
  private static final String RECORD_NOT_FOUND_STATUS = "23";

  /** {@code MOVE '01' TO TRAN-TYPE-CD} (CBACT04C L482); {@code TRAN-TYPE-CD PIC X(02)}. */
  private static final String TRAN_TYPE_INTEREST = "01";

  /**
   * {@code MOVE '05' TO TRAN-CAT-CD} (CBACT04C L483). {@code TRAN-CAT-CD} is {@code PIC 9(04)}, so
   * the alphanumeric literal {@code '05'} is stored right-justified, zero-filled as {@code "0005"}.
   */
  private static final String TRAN_CAT_INTEREST = "0005";

  /** {@code MOVE 'System' TO TRAN-SOURCE} (CBACT04C L484); {@code TRAN-SOURCE PIC X(10)}. */
  private static final String TRAN_SOURCE_SYSTEM = "System";

  /**
   * Literal prefix of {@code STRING 'Int. for a/c ', ACCT-ID ... INTO TRAN-DESC} (CBACT04C L485);
   * the trailing space is part of the 13-character literal.
   */
  private static final String TRAN_DESC_PREFIX = "Int. for a/c ";

  /**
   * Disclosure account-group id used for the default-rate fallback (CBACT04C L437, {@code MOVE
   * 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID}). {@code FD-DIS-ACCT-GROUP-ID} is {@code PIC X(10)}, so the
   * 7-character literal is space-padded to 10, matching the {@code char(10)} {@code
   * dis_acct_group_id} column.
   */
  private static final String DEFAULT_GROUP_ID = "DEFAULT";

  /** Fixed width (10) of the disclosure account-group id ({@code DIS-ACCT-GROUP-ID PIC X(10)}). */
  private static final int DIS_ACCT_GROUP_ID_WIDTH = 10;

  /**
   * Width (6) of {@code WS-TRANID-SUFFIX PIC 9(06)} appended to {@code PARM-DATE} for {@code
   * TRAN-ID}.
   */
  private static final int TRANID_SUFFIX_WIDTH = 6;

  /** Width (11) of {@code ACCT-ID PIC 9(11)} as rendered into {@code TRAN-DESC}. */
  private static final int ACCT_ID_WIDTH = 11;

  /** Width (100) of {@code TRAN-DESC PIC X(100)}. */
  private static final int TRAN_DESC_WIDTH = 100;

  /** Width (10) of {@code TRAN-SOURCE PIC X(10)}. */
  private static final int TRAN_SOURCE_WIDTH = 10;

  /**
   * Width (50) of {@code TRAN-MERCHANT-NAME PIC X(50)} and {@code TRAN-MERCHANT-CITY PIC X(50)}.
   */
  private static final int MERCHANT_NAME_WIDTH = 50;

  /** Width (10) of {@code TRAN-MERCHANT-ZIP PIC X(10)}. */
  private static final int MERCHANT_ZIP_WIDTH = 10;

  /** Number of nanoseconds in one hundredth of a second (the COBOL {@code COB-MIL} resolution). */
  private static final int NANOS_PER_CENTISECOND = 10_000_000;

  /**
   * Monetary/accumulator zero at scale 2, mirroring {@code MOVE 0} into the {@code S9(...)V99}
   * fields {@code WS-TOTAL-INT}, {@code ACCT-CURR-CYC-CREDIT} and {@code ACCT-CURR-CYC-DEBIT}.
   */
  private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

  /**
   * The literal {@code 1200} monthly divisor of {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL *
   * DIS-INT-RATE) / 1200} (CBACT04C L464-465). Held as {@link BigDecimal} so the division stays in
   * exact decimal arithmetic.
   */
  private static final BigDecimal MONTHLY_DIVISOR = BigDecimal.valueOf(1200);

  /** {@code TCATBALF} store ({@code SELECT TCATBAL-FILE ... ACCESS MODE IS SEQUENTIAL}). */
  private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

  /** {@code DISCGRP} store ({@code SELECT DISCGRP-FILE ... ACCESS MODE IS RANDOM}). */
  private final DisclosureGroupRepository disclosureGroupRepository;

  /**
   * {@code ACCTFILE} store ({@code SELECT ACCOUNT-FILE ...}); opened {@code I-O} (read + rewrite).
   */
  private final AccountRepository accountRepository;

  /** {@code XREFFILE} store; read by the alternate index on {@code FD-XREF-ACCT-ID}. */
  private final CardXrefRepository cardXrefRepository;

  /** {@code TRANSACT} store; output file written with the computed interest transactions. */
  private final TransactionRepository transactionRepository;

  /**
   * Clock backing {@code Z-GET-DB2-FORMAT-TIMESTAMP} (the COBOL {@code FUNCTION CURRENT-DATE}).
   * Defaults to {@link Clock#systemDefaultZone()}; tests inject a fixed clock via {@link
   * #setClock(Clock)} so the {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} timestamps are
   * deterministic.
   */
  private Clock clock = Clock.systemDefaultZone();

  /**
   * Ascending composite-key cursor over {@code TCATBALF}, established by {@link #openTcatbalf()}
   * and released by {@link #closeTcatbalf()}. Models the open VSAM sequential file handle; {@code
   * null} when the file is not open.
   */
  private Iterator<TransactionCategoryBalance> cursor;

  /**
   * End-of-file flag modeling the COBOL {@code END-OF-FILE PIC X(01)} ({@code 'N'}/{@code 'Y'})
   * switch. Set {@code true} when the sequential read reaches exhaustion (FILE STATUS {@code
   * '10'}).
   */
  private boolean endOfFile;

  /**
   * Record most recently read by {@link #tcatbalfGetNext()} (COBOL {@code TRAN-CAT-BAL-RECORD}).
   */
  private TransactionCategoryBalance currentTcb;

  /** Current account being accumulated/posted (COBOL {@code ACCOUNT-RECORD}). */
  private Account currentAccount;

  /** Current card cross-reference for the account (COBOL {@code CARD-XREF-RECORD}). */
  private CardXref currentXref;

  /**
   * Resolved disclosure interest rate for the current row (COBOL {@code DIS-INT-RATE S9(04)V99}).
   */
  private BigDecimal disIntRate;

  /**
   * Accumulated monthly interest for the current account (COBOL {@code WS-TOTAL-INT S9(09)V99}).
   */
  private BigDecimal wsTotalInt;

  /** Last account id seen, for the control break (COBOL {@code WS-LAST-ACCT-NUM PIC X(11)}). */
  private Long wsLastAcctNum;

  /** First-iteration flag (COBOL {@code WS-FIRST-TIME PIC X(01) VALUE 'Y'}). */
  private boolean wsFirstTime;

  /** Monotonic transaction-id suffix (COBOL {@code WS-TRANID-SUFFIX PIC 9(06) VALUE 0}). */
  private long wsTranidSuffix;

  /**
   * Count of category-balance records processed (COBOL {@code WS-RECORD-COUNT PIC 9(09) VALUE 0}).
   */
  private long wsRecordCount;

  /**
   * Creates the service with its five repository collaborators (constructor injection). The Spring
   * context supplies no {@link Clock} bean; {@link #clock} therefore defaults to {@link
   * Clock#systemDefaultZone()} and is overridable for tests via {@link #setClock(Clock)}.
   *
   * @param transactionCategoryBalanceRepository the {@code TCATBALF} store; must not be {@code
   *     null}
   * @param disclosureGroupRepository the {@code DISCGRP} store; must not be {@code null}
   * @param accountRepository the {@code ACCTFILE} store; must not be {@code null}
   * @param cardXrefRepository the {@code XREFFILE} store; must not be {@code null}
   * @param transactionRepository the {@code TRANSACT} output store; must not be {@code null}
   */
  public InterestCalculationService(
      TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
      DisclosureGroupRepository disclosureGroupRepository,
      AccountRepository accountRepository,
      CardXrefRepository cardXrefRepository,
      TransactionRepository transactionRepository) {
    this.transactionCategoryBalanceRepository = transactionCategoryBalanceRepository;
    this.disclosureGroupRepository = disclosureGroupRepository;
    this.accountRepository = accountRepository;
    this.cardXrefRepository = cardXrefRepository;
    this.transactionRepository = transactionRepository;
  }

  /**
   * Overrides the {@link Clock} used by {@link #getDb2FormatTimestamp()}. This is a test seam that
   * lets a fixed clock produce deterministic {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} values for
   * golden-file parity assertions; production never calls it (the default system clock is used).
   *
   * @param clock the clock to use; must not be {@code null}
   */
  void setClock(Clock clock) {
    this.clock = clock;
  }

  /**
   * Runs the interest calculation, reproducing the {@code CBACT04C} {@code PROCEDURE DIVISION USING
   * EXTERNAL-PARMS} main paragraph (L180-232) statement-for-statement.
   *
   * <p>The flow is: announce start &rarr; open the five files ({@code 0000}-{@code 0400}) &rarr;
   * {@code PERFORM UNTIL END-OF-FILE = 'Y'} reading one category-balance row per iteration; on each
   * change of {@code TRANCAT-ACCT-ID} (control break) post the previous account's accumulated
   * interest ({@code 1050}), reset the accumulator, and read the new account ({@code 1100}) and its
   * card cross-reference ({@code 1110}); then, for every row, resolve the disclosure rate ({@code
   * 1200}) and, when the rate is non-zero, compute interest ({@code 1300}) and fees ({@code 1400}).
   * After the loop the last account is posted once ({@code 1050} via the COBOL EOF {@code ELSE}
   * branch). Finally close the files ({@code 9000}-{@code 9400}) and announce end. The legacy
   * {@code GOBACK} sets no {@code RETURN-CODE}, so normal completion is success and the method
   * returns {@code void}; an unexpected I/O condition instead propagates an {@link
   * IoStatusException} (the abend-equivalent).
   *
   * @param parmDate the 10-character run date (COBOL {@code PARM-DATE PIC X(10)}), supplied by the
   *     batch tier from a {@code JobParameter} (legacy {@code PARM='2022071800'}); used verbatim as
   *     the high-order 10 characters of every generated {@code TRAN-ID}
   * @throws IoStatusException if any file operation raises an unexpected {@link
   *     DataAccessException}, or if a required account, cross-reference, or default
   *     disclosure-group record is missing (the COBOL {@code '00'}-only abend paths)
   */
  public void run(String parmDate) {
    // <- CBACT04C PROCEDURE DIVISION USING EXTERNAL-PARMS (main, L180-232)
    resetRunState();
    LOG.info(START_MESSAGE); // DISPLAY 'START OF EXECUTION OF PROGRAM CBACT04C'

    openTcatbalf(); // PERFORM 0000-TCATBALF-OPEN
    openXreffile(); // PERFORM 0100-XREFFILE-OPEN
    openDiscgrp(); // PERFORM 0200-DISCGRP-OPEN
    openAcctfile(); // PERFORM 0300-ACCTFILE-OPEN
    openTranfile(); // PERFORM 0400-TRANFILE-OPEN

    // PERFORM UNTIL END-OF-FILE = 'Y'
    while (!endOfFile) {
      tcatbalfGetNext(); // PERFORM 1000-TCATBALF-GET-NEXT
      if (!endOfFile) { // IF END-OF-FILE = 'N' (a record was returned)
        wsRecordCount++; // ADD 1 TO WS-RECORD-COUNT
        LOG.info("{}", formatTranCatBalRecord(currentTcb)); // DISPLAY TRAN-CAT-BAL-RECORD

        TransactionCategoryBalanceId balKey = currentTcb.getId();
        Long trancatAcctId = balKey.getTrancatAcctId();

        // IF TRANCAT-ACCT-ID NOT = WS-LAST-ACCT-NUM (control break)
        if (!trancatAcctId.equals(wsLastAcctNum)) {
          if (!wsFirstTime) { // IF WS-FIRST-TIME NOT = 'Y'
            updateAccount(); // PERFORM 1050-UPDATE-ACCOUNT (posts the PREVIOUS account)
          } else {
            wsFirstTime = false; // ELSE MOVE 'N' TO WS-FIRST-TIME
          }
          wsTotalInt = ZERO_AMOUNT; // MOVE 0 TO WS-TOTAL-INT
          wsLastAcctNum = trancatAcctId; // MOVE TRANCAT-ACCT-ID TO WS-LAST-ACCT-NUM
          getAcctData(trancatAcctId); // MOVE ... TO FD-ACCT-ID; PERFORM 1100-GET-ACCT-DATA
          getXrefData(trancatAcctId); // MOVE ... TO FD-XREF-ACCT-ID; PERFORM 1110-GET-XREF-DATA
        }

        // MOVE ACCT-GROUP-ID / TRANCAT-CD / TRANCAT-TYPE-CD TO the DISCGRP key; PERFORM 1200
        getInterestRate();
        if (disIntRate.signum() != 0) { // IF DIS-INT-RATE NOT = 0
          computeInterest(parmDate); // PERFORM 1300-COMPUTE-INTEREST
          computeFees(); // PERFORM 1400-COMPUTE-FEES
        }
      }
    }

    // COBOL EOF 'ELSE PERFORM 1050-UPDATE-ACCOUNT' (L219-220): post the LAST account once.
    // Guarded by !wsFirstTime so an empty input file updates no (non-existent) account.
    if (!wsFirstTime) {
      updateAccount();
    }

    closeTcatbalf(); // PERFORM 9000-TCATBALF-CLOSE
    closeXreffile(); // PERFORM 9100-XREFFILE-CLOSE
    closeDiscgrp(); // PERFORM 9200-DISCGRP-CLOSE
    closeAcctfile(); // PERFORM 9300-ACCTFILE-CLOSE
    closeTranfile(); // PERFORM 9400-TRANFILE-CLOSE

    LOG.info(END_MESSAGE); // DISPLAY 'END OF EXECUTION OF PROGRAM CBACT04C'
    // GOBACK -- normal return (no RETURN-CODE set => success).
  }

  /**
   * Resets all per-run scan state to the COBOL working-storage initial values so the bean is
   * reusable across executions. Mirrors {@code WS-LAST-ACCT-NUM VALUE SPACES} (modeled as {@code
   * null} — no account matched yet), {@code WS-FIRST-TIME VALUE 'Y'}, {@code WS-TRANID-SUFFIX VALUE
   * 0}, {@code WS-RECORD-COUNT VALUE 0}, and the {@code END-OF-FILE 'N'} switch.
   */
  private void resetRunState() {
    cursor = null;
    endOfFile = false;
    currentTcb = null;
    currentAccount = null;
    currentXref = null;
    disIntRate = ZERO_AMOUNT;
    wsTotalInt = ZERO_AMOUNT;
    wsLastAcctNum = null;
    wsFirstTime = true;
    wsTranidSuffix = 0L;
    wsRecordCount = 0L;
  }

  /**
   * Opens the transaction-category-balance store, reproducing {@code 0000-TCATBALF-OPEN} (CBACT04C
   * L234-250).
   *
   * <p>The legacy {@code OPEN INPUT} on an {@code INDEXED} file with {@code ACCESS MODE IS
   * SEQUENTIAL} reads in ascending {@code RECORD KEY (FD-TRAN-CAT-KEY)} order. The control break in
   * {@link #run(String)} depends on records being grouped by account, which that key order
   * guarantees, so the Java cursor sorts by the embedded-id property paths with the account id
   * <strong>first</strong>: {@code id.trancatAcctId}, then {@code id.trancatTypeCd}, then {@code
   * id.trancatCd}. A {@link Sort} is used directly rather than adding a bespoke repository method.
   */
  private void openTcatbalf() {
    // <- CBACT04C 0000-TCATBALF-OPEN
    try {
      cursor =
          transactionCategoryBalanceRepository
              .findAll(Sort.by("id.trancatAcctId", "id.trancatTypeCd", "id.trancatCd"))
              .iterator();
      endOfFile = false; // initial END-OF-FILE = 'N'
    } catch (DataAccessException ex) {
      throw abend(TCATBALF_DDNAME, "OPEN", ABEND_FILE_STATUS, TCATBALF_OPEN_ERROR, ex);
    }
  }

  /**
   * Reproduces {@code 0100-XREFFILE-OPEN} (CBACT04C L252-268) as a no-op. The cross-reference is
   * reached by the alternate-index key via {@link CardXrefRepository#findByXrefAcctId(Long)}, which
   * needs no open cursor; retained for paragraph traceability (AAP &sect;0.6.7).
   */
  private void openXreffile() {
    // <- CBACT04C 0100-XREFFILE-OPEN (no-op: keyed access via findByXrefAcctId needs no cursor)
  }

  /**
   * Reproduces {@code 0200-DISCGRP-OPEN} (CBACT04C L270-286) as a no-op. The disclosure group is
   * reached by key via {@link DisclosureGroupRepository#findById(Object)}, which needs no open
   * cursor; retained for paragraph traceability (AAP &sect;0.6.7).
   */
  private void openDiscgrp() {
    // <- CBACT04C 0200-DISCGRP-OPEN (no-op: keyed access via findById needs no cursor)
  }

  /**
   * Reproduces {@code 0300-ACCTFILE-OPEN} (CBACT04C L289-305) as a no-op. The account master is
   * reached by key via {@link AccountRepository#findById(Object)} and rewritten via {@code save};
   * the COBOL {@code OPEN I-O} needs no Java counterpart. Retained for traceability (AAP
   * &sect;0.6.7).
   */
  private void openAcctfile() {
    // <- CBACT04C 0300-ACCTFILE-OPEN (no-op: keyed read/rewrite via findById/save needs no cursor)
  }

  /**
   * Reproduces {@code 0400-TRANFILE-OPEN} (CBACT04C L307-323) as a no-op. The output transaction
   * file is written one row at a time via {@link TransactionRepository#save(Object)}; the COBOL
   * {@code OPEN OUTPUT} needs no Java counterpart. Retained for traceability (AAP &sect;0.6.7).
   */
  private void openTranfile() {
    // <- CBACT04C 0400-TRANFILE-OPEN (no-op: output via save needs no cursor)
  }

  /**
   * Reads the next category-balance record, reproducing {@code 1000-TCATBALF-GET-NEXT} (CBACT04C
   * L325-348).
   *
   * <p>Mirrors the COBOL {@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD} status branching: a
   * record available (FILE STATUS {@code '00'}) becomes {@link #currentTcb}; cursor exhaustion
   * (FILE STATUS {@code '10'}, {@code APPL-EOF}) sets {@link #endOfFile} {@code true} — normal loop
   * termination, not an error; any other condition (an unexpected {@link DataAccessException})
   * triggers the abend path.
   */
  private void tcatbalfGetNext() {
    // <- CBACT04C 1000-TCATBALF-GET-NEXT
    try {
      if (cursor.hasNext()) {
        currentTcb = cursor.next(); // TCATBALF-STATUS = '00'
      } else {
        endOfFile = true; // TCATBALF-STATUS = '10' (APPL-EOF): MOVE 'Y' TO END-OF-FILE
      }
    } catch (DataAccessException ex) {
      throw abend(TCATBALF_DDNAME, "READ", ABEND_FILE_STATUS, TCATBALF_READ_ERROR, ex);
    }
  }

  /**
   * Posts the accumulated interest to the current account, reproducing {@code 1050-UPDATE-ACCOUNT}
   * (CBACT04C L350-370).
   *
   * <p>{@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL} then {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} and
   * {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT}, all at scale 2, then {@code REWRITE} (here {@code
   * save}). No {@code @Version} column exists on {@link Account}, so the legacy single-threaded
   * read/rewrite is preserved without optimistic-lock retries. An unexpected {@link
   * DataAccessException} triggers the abend path.
   */
  private void updateAccount() {
    // <- CBACT04C 1050-UPDATE-ACCOUNT
    currentAccount.setAcctCurrBal(
        currentAccount.getAcctCurrBal().add(wsTotalInt)); // ADD WS-TOTAL-INT TO ACCT-CURR-BAL
    currentAccount.setAcctCurrCycCredit(ZERO_AMOUNT); // MOVE 0 TO ACCT-CURR-CYC-CREDIT
    currentAccount.setAcctCurrCycDebit(ZERO_AMOUNT); // MOVE 0 TO ACCT-CURR-CYC-DEBIT
    try {
      accountRepository.save(currentAccount); // REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
    } catch (DataAccessException ex) {
      throw abend(ACCTFILE_DDNAME, "REWRITE", ABEND_FILE_STATUS, ACCT_REWRITE_ERROR, ex);
    }
  }

  /**
   * Reads the account master record for the current account id, reproducing {@code
   * 1100-GET-ACCT-DATA} (CBACT04C L372-391).
   *
   * <p><strong>Program-specific abend-on-missing.</strong> Unlike the daily-posting archetype, this
   * paragraph accepts <em>only</em> FILE STATUS {@code '00'}: the COBOL {@code INVALID KEY} clause
   * displays {@code 'ACCOUNT NOT FOUND: '} and the subsequent status check ({@code '23'} != {@code
   * '00'}) drives the {@code 9999-ABEND-PROGRAM} path. A missing account is therefore a hard abend
   * — <strong>not</strong> an {@code Optional}-skip and <strong>never</strong> a {@code
   * RecordNotFoundException}. The loaded {@link Account} is kept as {@link #currentAccount} for
   * {@code 1050} and {@code 1300-B}.
   *
   * @param acctId the account id to read (COBOL {@code FD-ACCT-ID})
   */
  private void getAcctData(Long acctId) {
    // <- CBACT04C 1100-GET-ACCT-DATA
    Optional<Account> found;
    try {
      found = accountRepository.findById(acctId); // READ ACCOUNT-FILE INTO ACCOUNT-RECORD
    } catch (DataAccessException ex) {
      throw abend(ACCTFILE_DDNAME, "READ", ABEND_FILE_STATUS, ACCT_READ_ERROR, ex);
    }
    if (found.isEmpty()) {
      // INVALID KEY: DISPLAY 'ACCOUNT NOT FOUND: ' FD-ACCT-ID; status '23' != '00' -> abend.
      LOG.error("ACCOUNT NOT FOUND: {}", acctId);
      throw abend(ACCTFILE_DDNAME, "READ", RECORD_NOT_FOUND_STATUS, ACCT_READ_ERROR, null);
    }
    currentAccount = found.get();
  }

  /**
   * Reads the card cross-reference for the current account id via the alternate index, reproducing
   * {@code 1110-GET-XREF-DATA} (CBACT04C L393-413).
   *
   * <p>The COBOL {@code READ XREF-FILE ... KEY IS FD-XREF-ACCT-ID} returns the first record
   * matching the alternate key, so the Java equivalent calls {@link
   * CardXrefRepository#findByXrefAcctId(Long)} and takes the first element. Like {@code 1100}, this
   * paragraph accepts <em>only</em> FILE STATUS {@code '00'}: no matching cross-reference is a hard
   * abend. The retained {@link CardXref}'s {@code xrefCardNum} becomes {@code TRAN-CARD-NUM} in
   * {@code 1300-B}.
   *
   * @param acctId the account id to look up (COBOL {@code FD-XREF-ACCT-ID})
   */
  private void getXrefData(Long acctId) {
    // <- CBACT04C 1110-GET-XREF-DATA
    List<CardXref> matches;
    try {
      matches =
          cardXrefRepository.findByXrefAcctId(acctId); // READ XREF-FILE KEY IS FD-XREF-ACCT-ID
    } catch (DataAccessException ex) {
      throw abend(XREFFILE_DDNAME, "READ", ABEND_FILE_STATUS, XREF_READ_ERROR, ex);
    }
    if (matches.isEmpty()) {
      // INVALID KEY: DISPLAY 'ACCOUNT NOT FOUND: ' FD-XREF-ACCT-ID; status '23' != '00' -> abend.
      LOG.error("ACCOUNT NOT FOUND: {}", acctId);
      throw abend(XREFFILE_DDNAME, "READ", RECORD_NOT_FOUND_STATUS, XREF_READ_ERROR, null);
    }
    currentXref = matches.get(0); // mirrors VSAM READ returning the first matching record
  }

  /**
   * Resolves the disclosure interest rate for the current row, reproducing {@code
   * 1200-GET-INTEREST-RATE} (CBACT04C L415-440).
   *
   * <p>The lookup key is built (COBOL {@code MOVE ACCT-GROUP-ID / TRANCAT-CD / TRANCAT-TYPE-CD})
   * from the current account's group id and the row's transaction type and category codes. Both
   * FILE STATUS {@code '00'} (found) and {@code '23'} (not found) are accepted (non-abend): when
   * found, {@link #disIntRate} is taken from the record; when not found, the group id is replaced
   * with {@code 'DEFAULT'} and {@link #getDefaultInterestRate(String, String)} is performed. The
   * account group id read back from the {@code char(10)} column is already blank-padded to width
   * 10; {@code DEFAULT} is padded likewise so equality matches the stored key under PostgreSQL
   * {@code char(10)} semantics.
   */
  private void getInterestRate() {
    // <- CBACT04C 1200-GET-INTEREST-RATE
    TransactionCategoryBalanceId balKey = currentTcb.getId();
    String tranTypeCd = balKey.getTrancatTypeCd(); // MOVE TRANCAT-TYPE-CD TO FD-DIS-TRAN-TYPE-CD
    String tranCatCd = balKey.getTrancatCd(); // MOVE TRANCAT-CD TO FD-DIS-TRAN-CAT-CD
    String acctGroupId =
        currentAccount.getAcctGroupId(); // MOVE ACCT-GROUP-ID TO FD-DIS-ACCT-GROUP-ID

    DisclosureGroupId key =
        new DisclosureGroupId(
            CobolStringUtils.padRight(acctGroupId, DIS_ACCT_GROUP_ID_WIDTH), tranTypeCd, tranCatCd);
    Optional<DisclosureGroup> found;
    try {
      found = disclosureGroupRepository.findById(key); // READ DISCGRP-FILE INTO DIS-GROUP-RECORD
    } catch (DataAccessException ex) {
      throw abend(DISCGRP_DDNAME, "READ", ABEND_FILE_STATUS, DISCGRP_READ_ERROR, ex);
    }

    if (found.isPresent()) { // DISCGRP-STATUS = '00'
      disIntRate = found.get().getDisIntRate();
    } else { // DISCGRP-STATUS = '23': MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID; PERFORM 1200-A
      LOG.info("DISCLOSURE GROUP RECORD MISSING");
      LOG.info("TRY WITH DEFAULT GROUP CODE");
      getDefaultInterestRate(tranTypeCd, tranCatCd);
    }
  }

  /**
   * Reads the {@code DEFAULT} disclosure-group rate, reproducing {@code
   * 1200-A-GET-DEFAULT-INT-RATE} (CBACT04C L443-460).
   *
   * <p>Re-reads {@code DISCGRP} with the account-group id replaced by {@code 'DEFAULT'}
   * (space-padded to the {@code char(10)} width). This paragraph accepts <em>only</em> FILE STATUS
   * {@code '00'}, so a missing {@code DEFAULT} row is a hard abend. On success {@link #disIntRate}
   * is taken from the record.
   *
   * @param tranTypeCd the transaction type code component of the key ({@code FD-DIS-TRAN-TYPE-CD})
   * @param tranCatCd the transaction category code component of the key ({@code
   *     FD-DIS-TRAN-CAT-CD})
   */
  private void getDefaultInterestRate(String tranTypeCd, String tranCatCd) {
    // <- CBACT04C 1200-A-GET-DEFAULT-INT-RATE
    DisclosureGroupId key =
        new DisclosureGroupId(
            CobolStringUtils.padRight(DEFAULT_GROUP_ID, DIS_ACCT_GROUP_ID_WIDTH),
            tranTypeCd,
            tranCatCd);
    Optional<DisclosureGroup> found;
    try {
      found = disclosureGroupRepository.findById(key); // READ DISCGRP-FILE INTO DIS-GROUP-RECORD
    } catch (DataAccessException ex) {
      throw abend(DISCGRP_DDNAME, "READ", ABEND_FILE_STATUS, DEFAULT_DISCGRP_READ_ERROR, ex);
    }
    if (found.isEmpty()) { // 1200-A accepts only '00': a missing DEFAULT row abends.
      throw abend(
          DISCGRP_DDNAME, "READ", RECORD_NOT_FOUND_STATUS, DEFAULT_DISCGRP_READ_ERROR, null);
    }
    disIntRate = found.get().getDisIntRate();
  }

  /**
   * Computes the monthly interest for the current row, reproducing {@code 1300-COMPUTE-INTEREST}
   * (CBACT04C L462-470).
   *
   * <p><strong>Truncation parity (the single most important rule in the migration, AAP
   * &sect;0.6.1).</strong> The COBOL {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE)
   * / 1200} carries <em>no {@code ROUNDED} phrase</em>, and the receiving field {@code
   * WS-MONTHLY-INT} is {@code PIC S9(09)V99} (scale 2). The Java translation forms the exact
   * product ({@code multiply}), then divides by the literal {@code 1200} truncating to scale 2 with
   * {@link java.math.RoundingMode#DOWN}. This is <em>never</em> {@code HALF_UP}, <em>never</em>
   * {@code float}/{@code double}, and the {@code /1200} monthly divisor and scale-2 truncation are
   * preserved verbatim. The truncated amount is accumulated into {@link #wsTotalInt} and an
   * interest transaction is written ({@code 1300-B}).
   *
   * @param parmDate the run date forwarded to {@link #writeInterestTx(String, BigDecimal)} for the
   *     {@code TRAN-ID}
   */
  private void computeInterest(String parmDate) {
    // <- CBACT04C 1300-COMPUTE-INTEREST
    BigDecimal tranCatBal = currentTcb.getTranCatBal();
    // COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE ) / 1200  (no ROUNDED -> truncate)
    BigDecimal wsMonthlyInt =
        tranCatBal.multiply(disIntRate).divide(MONTHLY_DIVISOR, 2, RoundingMode.DOWN);
    wsTotalInt = wsTotalInt.add(wsMonthlyInt); // ADD WS-MONTHLY-INT TO WS-TOTAL-INT
    writeInterestTx(parmDate, wsMonthlyInt); // PERFORM 1300-B-WRITE-TX
  }

  /**
   * Builds and writes one interest transaction, reproducing {@code 1300-B-WRITE-TX} (CBACT04C
   * L473-515).
   *
   * <p>The transaction-id suffix is incremented first ({@code ADD 1 TO WS-TRANID-SUFFIX}) — note it
   * advances <em>only</em> when a transaction is actually written, so zero-rate rows leave no gap.
   * The {@code TRAN-ID} is {@code PARM-DATE} (10) concatenated with the 6-digit zero-padded suffix
   * ({@code STRING ... DELIMITED BY SIZE}). The remaining fields reproduce the COBOL {@code MOVE}s
   * exactly into their fixed-width pictures: {@code TRAN-TYPE-CD '01'}, {@code TRAN-CAT-CD '0005'}
   * ({@code '05'} into {@code PIC 9(04)}), {@code TRAN-SOURCE 'System'} ({@code X(10)}), {@code
   * TRAN-DESC 'Int. for a/c ' + ACCT-ID} ({@code X(100)}), {@code TRAN-AMT} = the truncated monthly
   * interest, {@code TRAN-MERCHANT-ID 0}, the three merchant fields {@code SPACES}, {@code
   * TRAN-CARD-NUM} from the cross-reference, and both timestamps from {@link
   * #getDb2FormatTimestamp()}. The {@code WRITE} maps to {@code save}; a failure abends.
   *
   * @param parmDate the run date (high-order 10 characters of {@code TRAN-ID})
   * @param wsMonthlyInt the truncated monthly interest amount ({@code WS-MONTHLY-INT}, scale 2)
   */
  private void writeInterestTx(String parmDate, BigDecimal wsMonthlyInt) {
    // <- CBACT04C 1300-B-WRITE-TX
    wsTranidSuffix++; // ADD 1 TO WS-TRANID-SUFFIX

    Transaction tx = new Transaction();
    // STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID
    tx.setTranId(parmDate + CobolStringUtils.padLeftZeros(wsTranidSuffix, TRANID_SUFFIX_WIDTH));
    tx.setTranTypeCd(TRAN_TYPE_INTEREST); // MOVE '01' TO TRAN-TYPE-CD
    tx.setTranCatCd(TRAN_CAT_INTEREST); // MOVE '05' TO TRAN-CAT-CD (PIC 9(04) -> "0005")
    tx.setTranSource(CobolStringUtils.padRight(TRAN_SOURCE_SYSTEM, TRAN_SOURCE_WIDTH)); // 'System'
    // STRING 'Int. for a/c ', ACCT-ID DELIMITED BY SIZE INTO TRAN-DESC (PIC X(100))
    tx.setTranDesc(
        CobolStringUtils.padRight(
            TRAN_DESC_PREFIX
                + CobolStringUtils.padLeftZeros(currentAccount.getAcctId(), ACCT_ID_WIDTH),
            TRAN_DESC_WIDTH));
    tx.setTranAmt(wsMonthlyInt); // MOVE WS-MONTHLY-INT TO TRAN-AMT
    tx.setTranMerchantId(0L); // MOVE 0 TO TRAN-MERCHANT-ID
    tx.setTranMerchantName(CobolStringUtils.spaces(MERCHANT_NAME_WIDTH)); // MOVE SPACES
    tx.setTranMerchantCity(CobolStringUtils.spaces(MERCHANT_NAME_WIDTH)); // MOVE SPACES
    tx.setTranMerchantZip(CobolStringUtils.spaces(MERCHANT_ZIP_WIDTH)); // MOVE SPACES
    tx.setTranCardNum(currentXref.getXrefCardNum()); // MOVE XREF-CARD-NUM TO TRAN-CARD-NUM

    String timestamp = getDb2FormatTimestamp(); // PERFORM Z-GET-DB2-FORMAT-TIMESTAMP
    tx.setTranOrigTs(timestamp); // MOVE DB2-FORMAT-TS TO TRAN-ORIG-TS
    tx.setTranProcTs(timestamp); // MOVE DB2-FORMAT-TS TO TRAN-PROC-TS

    try {
      transactionRepository.save(tx); // WRITE FD-TRANFILE-REC FROM TRAN-RECORD
    } catch (DataAccessException ex) {
      throw abend(TRANSACT_DDNAME, "WRITE", ABEND_FILE_STATUS, TRAN_WRITE_ERROR, ex);
    }
  }

  /**
   * Reproduces {@code 1400-COMPUTE-FEES} (CBACT04C L518-520).
   *
   * <p>The legacy paragraph body is just {@code * To be implemented} followed by {@code EXIT}: it
   * performs no work. This empty method is retained, and invoked in {@link #run(String)} exactly
   * where the COBOL performs it, solely for 100% paragraph traceability (AAP &sect;0.6.7). It is a
   * faithful translation of an intentionally-empty source paragraph, not deferred work.
   */
  private void computeFees() {
    // <- CBACT04C 1400-COMPUTE-FEES
    // To be implemented (no-op stub in CBACT04C: legacy body is '* To be implemented' + EXIT).
  }

  /**
   * Builds the 26-character DB2-format timestamp, reproducing {@code Z-GET-DB2-FORMAT-TIMESTAMP}
   * (CBACT04C L613-626).
   *
   * <p>The COBOL moves {@code FUNCTION CURRENT-DATE} into a work area and reassembles it as {@code
   * 'YYYY-MM-DD-HH.MM.SS.<cc>0000'}, where {@code <cc>} is the two-digit hundredths-of-a-second
   * field ({@code COB-MIL}) and the trailing {@code DB2-REST} is the literal {@code '0000'}. The
   * Java translation reads the wall clock through the injectable {@link #clock}, derives the
   * hundredths from the nanosecond-of-second, and formats with {@link Locale#ROOT} so the digits
   * are always ASCII. With a fixed clock at {@code 2022-07-18T00:00:00} the result is the
   * deterministic sentinel {@code 2022-07-18-00.00.00.000000}.
   *
   * @return the 26-character DB2-format timestamp string
   */
  private String getDb2FormatTimestamp() {
    // <- CBACT04C Z-GET-DB2-FORMAT-TIMESTAMP
    LocalDateTime now = LocalDateTime.now(clock); // MOVE FUNCTION CURRENT-DATE TO COBOL-TS
    int hundredths = now.getNano() / NANOS_PER_CENTISECOND; // COB-MIL (hundredths of a second)
    // YYYY-MM-DD-HH.MM.SS.<2-digit hundredths>0000  (DB2-MIL = hundredths, DB2-REST = '0000')
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

  /**
   * Releases the category-balance cursor, reproducing {@code 9000-TCATBALF-CLOSE} (CBACT04C
   * L522-538). Dropping the in-memory cursor is the Java counterpart of the VSAM {@code CLOSE}.
   */
  private void closeTcatbalf() {
    // <- CBACT04C 9000-TCATBALF-CLOSE
    cursor = null; // CLOSE TCATBAL-FILE: release the file handle
  }

  /**
   * Reproduces {@code 9100-XREFFILE-CLOSE} (CBACT04C L541-557) as a no-op (keyed access held no
   * cursor). Retained for paragraph traceability (AAP &sect;0.6.7).
   */
  private void closeXreffile() {
    // <- CBACT04C 9100-XREFFILE-CLOSE (no-op)
  }

  /**
   * Reproduces {@code 9200-DISCGRP-CLOSE} (CBACT04C L559-575) as a no-op (keyed access held no
   * cursor). Retained for paragraph traceability (AAP &sect;0.6.7).
   */
  private void closeDiscgrp() {
    // <- CBACT04C 9200-DISCGRP-CLOSE (no-op)
  }

  /**
   * Reproduces {@code 9300-ACCTFILE-CLOSE} (CBACT04C L577-593) as a no-op (keyed read/rewrite held
   * no cursor). Retained for paragraph traceability (AAP &sect;0.6.7).
   */
  private void closeAcctfile() {
    // <- CBACT04C 9300-ACCTFILE-CLOSE (no-op)
  }

  /**
   * Reproduces {@code 9400-TRANFILE-CLOSE} (CBACT04C L595-611) as a no-op (output via {@code save}
   * held no cursor). Retained for paragraph traceability (AAP &sect;0.6.7).
   */
  private void closeTranfile() {
    // <- CBACT04C 9400-TRANFILE-CLOSE (no-op)
  }

  /**
   * Renders the whole-record image emitted by the main-loop {@code DISPLAY TRAN-CAT-BAL-RECORD}
   * (CBACT04C L193).
   *
   * <p>Concatenates the copybook {@code CVTRA01Y} business fields in declaration order: the
   * 11-digit account id, the two-character type code, the four-character category code, and the
   * {@link BigDecimal} balance (the trailing {@code FILLER PIC X(22)} carries no business data and
   * is omitted). The image is informational only — it is logged, never asserted by golden-file
   * parity.
   *
   * @param record the category-balance record to render; never {@code null} on the {@code '00'}
   *     path
   * @return the concatenated record image in copybook field order
   */
  private String formatTranCatBalRecord(TransactionCategoryBalance record) {
    // <- CBACT04C main-loop DISPLAY TRAN-CAT-BAL-RECORD (CVTRA01Y field order)
    TransactionCategoryBalanceId key = record.getId();
    return new StringBuilder()
        .append(CobolStringUtils.padLeftZeros(key.getTrancatAcctId(), ACCT_ID_WIDTH))
        .append(key.getTrancatTypeCd())
        .append(key.getTrancatCd())
        .append(record.getTranCatBal())
        .toString();
  }

  /**
   * Builds the unrecoverable-I/O abend, reproducing {@code 9910-DISPLAY-IO-STATUS} (CBACT04C
   * L635-648) together with {@code 9999-ABEND-PROGRAM} (CBACT04C L628-632).
   *
   * <p>The three externally observable {@code DISPLAY}s are emitted in the legacy order: the verb's
   * {@code 'ERROR ...'} line, then the byte-faithful {@code 'FILE STATUS IS: NNNN....'} line (via
   * {@link IoStatusException#getDisplayMessage()}), then {@code 'ABENDING PROGRAM'}. The returned
   * {@link IoStatusException} carries the logical file name, the failing verb, the supplied {@code
   * FILE STATUS}, and (when present) the originating cause; its {@link
   * IoStatusException#BATCH_ABEND_CODE} (999 / {@code CEE3ABD}) is the migrated batch abend code.
   * Callers {@code throw} the result so control transfers immediately, mirroring {@code CALL
   * 'CEE3ABD'}.
   *
   * @param fileName the logical file name that failed (for example {@code "ACCTFILE"})
   * @param operation the failing COBOL I/O verb ({@code "OPEN"}, {@code "READ"}, {@code "REWRITE"},
   *     or {@code "WRITE"})
   * @param fileStatus the two-byte COBOL {@code FILE STATUS} to render ({@code "23"} for a missing
   *     record, otherwise {@code "99"} for an unexpected data-access failure)
   * @param errorDisplay the verb-specific {@code DISPLAY 'ERROR ...'} literal emitted first
   * @param cause the originating {@link DataAccessException}, or {@code null} for a
   *     record-not-found abend
   * @return the {@link IoStatusException} for the caller to throw
   */
  private IoStatusException abend(
      String fileName,
      String operation,
      String fileStatus,
      String errorDisplay,
      DataAccessException cause) {
    // <- CBACT04C 9910-DISPLAY-IO-STATUS + 9999-ABEND-PROGRAM
    LOG.error("{}", errorDisplay); // DISPLAY 'ERROR ...'
    IoStatusException ex =
        (cause == null)
            ? new IoStatusException(fileName, operation, fileStatus)
            : new IoStatusException(fileName, operation, fileStatus, cause);
    LOG.error("{}", ex.getDisplayMessage()); // 9910-DISPLAY-IO-STATUS: 'FILE STATUS IS: NNNN'...
    LOG.error(ABEND_MESSAGE); // 9999-ABEND-PROGRAM: DISPLAY 'ABENDING PROGRAM'
    return ex;
  }
}
