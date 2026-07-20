package com.aws.carddemo.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.DisclosureGroup;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.DisclosureGroupRepository;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.CobolDecimal;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Spring Batch job configuration translating the mainframe interest-calculation program
 * {@code CBACT04C}, orchestrated on z/OS by JCL job {@code INTCALC.jcl} (step {@code STEP15},
 * {@code PARM='2022071800'}).
 *
 * <p><strong>Origin (traceability, AAP &sect;0.6.10):</strong> {@code legacy/cbl/CBACT04C.cbl}
 * (source branch {@code app/cbl/CBACT04C.cbl}) and {@code legacy/jcl/INTCALC.jcl}
 * ({@code app/jcl/INTCALC.jcl}). Implements AAP &sect;0.4.1 (batch program &rarr; Spring Batch
 * {@link Job}), &sect;0.6.1 (decimal fidelity &mdash; this program is <em>the</em> canonical
 * truncation example), and &sect;0.6.3 (the {@code PARM} processing date becomes a
 * {@code JobParameter}).</p>
 *
 * <p><strong>What it does.</strong> It scans the transaction-category-balance file
 * ({@code TCATBAL}) sequentially in account-key order, and for each category-balance row it
 * looks up the disclosure-group interest rate for the {@code (account-group, type, category)}
 * tuple, computes one month's interest on that category balance, writes a system-generated
 * interest {@link Transaction}, and accumulates the interest per account. When the scan crosses to
 * a new account (a "control break") the <em>previous</em> account's accumulated interest is written
 * back to its balance and its cycle credit/debit totals are reset.</p>
 *
 * <p><strong>Paragraph &rarr; method map</strong> (COBOL {@code PROCEDURE DIVISION} preserved as
 * calls, per the migration rule that each paragraph becomes a method):</p>
 * <ul>
 *   <li>{@code PROCEDURE DIVISION} main loop (L180-222) &rarr; {@link #calculateInterest(String)}</li>
 *   <li>{@code 1000-TCATBALF-GET-NEXT} (L325) &rarr; the cursor-backed key-order stream scan +
 *       for-each iteration in {@link #calculateInterest(String)}</li>
 *   <li>{@code 1050-UPDATE-ACCOUNT} (L350) &rarr; {@link #updateAccount(Account, BigDecimal)}</li>
 *   <li>{@code 1100-GET-ACCT-DATA} (L372) &rarr; {@link #getAccountData(Long)}</li>
 *   <li>{@code 1110-GET-XREF-DATA} (L393) &rarr; {@link #getCardNumber(Long)}</li>
 *   <li>{@code 1200-GET-INTEREST-RATE} + {@code 1200-A-GET-DEFAULT-INT-RATE} (L415, L443)
 *       &rarr; {@link #resolveInterestRate(String, String, Integer)}</li>
 *   <li>{@code 1300-COMPUTE-INTEREST} (L462) &rarr; {@link #computeMonthlyInterest(BigDecimal, BigDecimal)}</li>
 *   <li>{@code 1300-B-WRITE-TX} (L473) &rarr;
 *       {@link #writeInterestTransaction(String, long, Account, String, BigDecimal)}</li>
 *   <li>{@code 1400-COMPUTE-FEES} (L518, "To be implemented") &rarr; {@link #computeFees()} (no-op)</li>
 * </ul>
 *
 * <p><strong>Decimal fidelity (AAP &sect;0.6.1).</strong> {@code 1300-COMPUTE-INTEREST} evaluates
 * {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} where {@code WS-MONTHLY-INT}
 * is {@code PIC S9(09)V99} with <em>no</em> {@code ROUNDED} phrase, so the quotient is
 * <em>truncated</em> to two decimals. This is reproduced exactly by
 * {@link CobolDecimal#divide(BigDecimal, BigDecimal, int)} (which applies
 * {@link java.math.RoundingMode#DOWN}); the intermediate multiplication stays at full precision and
 * only the final divide fixes the scale. Monetary values never touch {@code float}/{@code double}.</p>
 *
 * <p><strong>Parity-critical quirk &mdash; the FINAL account is intentionally not updated.</strong>
 * The COBOL driver is {@code PERFORM UNTIL END-OF-FILE = 'Y'} (a test-before loop) and end-of-file
 * is detected <em>inside</em> the body by {@code 1000-TCATBALF-GET-NEXT}; the {@code ELSE PERFORM
 * 1050-UPDATE-ACCOUNT} branch at L219-220 is therefore unreachable dead code, so
 * {@code 1050-UPDATE-ACCOUNT} only ever runs on an account <em>change</em> (L196). Consequently the
 * last account group in the scan never has its accumulated interest written back to
 * {@code ACCT-CURR-BAL} and its cycle credit/debit are not reset &mdash; even though the interest
 * <em>transactions</em> for that final account are still written. This implementation preserves that
 * behavior exactly: {@link #updateAccount(Account, BigDecimal)} is invoked only on an account-id
 * change and there is deliberately no post-loop flush. Silently "fixing" it would be a behavioral
 * regression against the 100%-parity mandate; the retained-legacy-defect decision is recorded in
 * {@code docs/decision-log.md} (Explainability rule).</p>
 *
 * <p><strong>Design (AAP recommended approach).</strong> A single {@link Tasklet} step whose body
 * mirrors the {@code PROCEDURE DIVISION} loop line-for-line, chosen over a chunk step because this
 * is a control-break job with cross-record accumulation and a final-group edge case that does not
 * map cleanly onto a stateless chunk. The alternative (an ordered chunk reader plus a stateful
 * account-change component) is recorded in {@code docs/decision-log.md}.</p>
 *
 * <p><strong>Wiring note (AAP binding constraint).</strong> This class deliberately does
 * <strong>not</strong> use {@code @EnableBatchProcessing} and does not depend on
 * {@code config/BatchConfig}. It relies on Spring Boot's Batch auto-configuration, which supplies
 * the persistent, restartable {@link JobRepository} and the {@link PlatformTransactionManager}
 * injected through the constructor &mdash; the same convention used by the sibling batch
 * configurations (for example {@code AdminBatchJobConfig}).</p>
 *
 * <p>The configuration holds only immutable collaborators; the {@code @StepScope} tasklet reads its
 * processing date exclusively from the per-execution job parameters, so the bean is thread-safe.</p>
 */
@Configuration
public class InterestCalcJobConfig {

    /** Logger for job-level progress; never emits account/financial record content. */
    private static final Logger LOGGER = LoggerFactory.getLogger(InterestCalcJobConfig.class);

    /** Registry name of the Spring Batch {@link Job} translating {@code CBACT04C}. */
    static final String JOB_NAME = "interestCalcJob";

    /** Name of the single step within {@link #JOB_NAME}. */
    static final String STEP_NAME = "interestCalcStep";

    /**
     * Job-parameter name carrying the COBOL {@code PARM-DATE} ({@code PIC X(10)}, e.g.
     * {@code 2022071800}). Used verbatim as the 10-character prefix of every generated interest
     * {@code TRAN-ID}; it is never reformatted.
     */
    static final String PROCESSING_DATE_PARAM = "processingDate";

    /** {@code MOVE '01' TO TRAN-TYPE-CD} (L482): interest transactions are transaction type {@code 01}. */
    private static final String INTEREST_TRAN_TYPE_CD = "01";

    /** {@code MOVE '05' TO TRAN-CAT-CD} (L483): category {@code 0005} (COBOL {@code PIC 9(04)}). */
    private static final int INTEREST_TRAN_CAT_CD = 5;

    /** {@code MOVE 'System' TO TRAN-SOURCE} (L484): note the exact capitalization. */
    private static final String INTEREST_TRAN_SOURCE = "System";

    /**
     * Literal prefix of {@code TRAN-DESC} (L485): {@code 'Int. for a/c '} &mdash; thirteen
     * characters including the trailing space, followed by the zero-padded account id.
     */
    private static final String INTEREST_TRAN_DESC_PREFIX = "Int. for a/c ";

    /** {@code MOVE 0 TO TRAN-MERCHANT-ID} (L491): interest transactions carry no merchant. */
    private static final long INTEREST_MERCHANT_ID = 0L;

    /**
     * Blank value for the merchant name/city/zip fields, reproducing {@code MOVE SPACES}
     * (L492-494) for these non-applicable columns on a system-generated interest transaction.
     */
    private static final String BLANK = "";

    /**
     * Divisor {@code 1200} from {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} (L465): twelve months
     * times the {@code S9(4)V99} rate's implied hundredths, converting an annual percentage rate to
     * a monthly fraction.
     */
    private static final BigDecimal MONTHLY_INTEREST_DIVISOR = BigDecimal.valueOf(1200);

    /**
     * The {@code 'DEFAULT'} account-group id used by {@code 1200-A-GET-DEFAULT-INT-RATE} (L437, L443)
     * when a {@code (group, type, category)} disclosure row is missing.
     */
    private static final String DEFAULT_DISCLOSURE_GROUP_ID = "DEFAULT";

    /**
     * Scale-2 zero used to reset the cycle credit/debit totals, reproducing
     * {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} / {@code ACCT-CURR-CYC-DEBIT} (L353-354) into the
     * {@code S9(10)V99} fields.
     */
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(CobolDecimal.MONEY_SCALE);

    /**
     * Modulus {@code 1_000_000} that reproduces the wraparound of {@code WS-TRANID-SUFFIX}
     * ({@code PIC 9(06)}): once the running counter passes {@code 999999} the six-digit field rolls
     * back through {@code 000000}, exactly as the fixed-width COBOL field would.
     */
    private static final long TRANID_SUFFIX_MODULUS = 1_000_000L;

    /** Auto-configured Spring Batch job repository used to build the step and the job. */
    private final JobRepository jobRepository;

    /** Auto-configured transaction manager bounding the tasklet's execution. */
    private final PlatformTransactionManager transactionManager;

    /** {@code TCATBAL} KSDS &rarr; sequential category-balance scan in account-key order. */
    private final TransactionCategoryBalanceRepository categoryBalanceRepository;

    /** {@code ACCTFILE} KSDS (I-O) &rarr; per-account read and balance write-back. */
    private final AccountRepository accountRepository;

    /** {@code XREF} KSDS (alt-index by account) &rarr; card-number lookup for the interest transaction. */
    private final CardXrefRepository cardXrefRepository;

    /** {@code DISCGRP} KSDS &rarr; disclosure-group interest-rate lookup (with DEFAULT fallback). */
    private final DisclosureGroupRepository disclosureGroupRepository;

    /** {@code TRANSACT} output &rarr; persistence of the generated interest transactions. */
    private final TransactionRepository transactionRepository;

    /**
     * Creates the configuration with the collaborators supplied by Spring Boot's Batch
     * auto-configuration and component scanning.
     *
     * @param jobRepository             the auto-configured Spring Batch {@link JobRepository}
     * @param transactionManager        the auto-configured {@link PlatformTransactionManager}
     * @param categoryBalanceRepository the {@code TCATBAL} repository (sequential scan)
     * @param accountRepository         the {@code ACCTDAT} repository (read + write-back)
     * @param cardXrefRepository        the {@code CCXREF} repository (card lookup by account)
     * @param disclosureGroupRepository the {@code DISCGRP} repository (interest-rate lookup)
     * @param transactionRepository     the {@code TRANSACT} repository (interest transaction output)
     */
    public InterestCalcJobConfig(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            TransactionCategoryBalanceRepository categoryBalanceRepository,
            AccountRepository accountRepository,
            CardXrefRepository cardXrefRepository,
            DisclosureGroupRepository disclosureGroupRepository,
            TransactionRepository transactionRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.categoryBalanceRepository = categoryBalanceRepository;
        this.accountRepository = accountRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.disclosureGroupRepository = disclosureGroupRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * The single {@code @StepScope} tasklet that reproduces the whole {@code CBACT04C}
     * {@code PROCEDURE DIVISION}. It reads the {@code processingDate} job parameter (the COBOL
     * {@code PARM-DATE}) and delegates to {@link #calculateInterest(String)}, then logs the number of
     * category-balance rows processed and signals completion.
     *
     * <p>Because the bean is {@code @StepScope}, the {@code @Value} job-parameter expression is
     * resolved lazily per step execution; {@link #interestCalcStep()} passes a {@code null}
     * placeholder that the scoped proxy replaces with the real parameter value at run time. Any
     * fatal condition ({@code CBACT04C}'s {@code 9999-ABEND-PROGRAM}: a missing account, a missing
     * cross-reference, or a missing DEFAULT disclosure group) surfaces as a thrown exception, which
     * fails the step &mdash; the batch equivalent of the COBOL abend &mdash; rather than silently
     * completing.</p>
     *
     * @param processingDate the processing date bound from job parameter {@code processingDate}
     *                       (COBOL {@code PARM-DATE}); resolved per step execution by the scoped proxy
     * @return a {@link Tasklet} that performs the interest calculation and returns
     *         {@link RepeatStatus#FINISHED}
     */
    @Bean
    @StepScope
    public Tasklet interestCalcTasklet(
            @Value("#{jobParameters['processingDate']}") String processingDate) {
        return (contribution, chunkContext) -> {
            int processed = calculateInterest(processingDate);
            LOGGER.info("CBACT04C interest calculation complete: processingDate=[{}], "
                    + "categoryBalanceRowsProcessed={}", processingDate, processed);
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * The single step of {@link #interestCalcJob()}, wrapping the {@code @StepScope}
     * {@link #interestCalcTasklet(String)} within the auto-configured transaction boundary. The
     * {@code null} argument is a placeholder: the scoped proxy resolves the actual
     * {@code processingDate} job parameter at run time.
     *
     * <p>Running the whole account-key scan inside the single tasklet transaction reproduces the
     * mainframe job's all-or-nothing commit shape: every interest transaction and every non-final
     * account balance write-back commit together when the step completes, and any abend-equivalent
     * exception rolls the step back.</p>
     *
     * @return the {@code interestCalcStep} {@link Step}
     */
    @Bean
    public Step interestCalcStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(interestCalcTasklet(null), transactionManager)
                .build();
    }

    /**
     * The Spring Batch {@link Job} corresponding to the mainframe interest calculator
     * {@code CBACT04C} (JCL {@code INTCALC.jcl}). It consists of the single
     * {@link #interestCalcStep()}.
     *
     * @return the {@code interestCalcJob} {@link Job}
     */
    @Bean
    public Job interestCalcJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(interestCalcStep())
                .build();
    }

    /**
     * Reproduces the {@code CBACT04C} {@code PROCEDURE DIVISION} main loop (L180-222): a single
     * account-key-ordered pass over the transaction-category-balance file that computes and posts
     * interest and writes back each account's accumulated interest on a control break.
     *
     * <p>The category balances are read in ascending composite-key order through
     * {@link TransactionCategoryBalanceRepository#streamAllByAccountKeyOrder()}, a forward-only
     * cursor-backed stream ({@code order by acctId, typeCd, catCd}), matching the COBOL
     * {@code ACCESS MODE IS SEQUENTIAL} / {@code RECORD KEY IS FD-TRAN-CAT-KEY} scan so that rows for
     * the same account are contiguous. Streaming (rather than a materialized {@code findAll} list)
     * processes one row at a time with bounded memory, never loading the whole {@code TCATBAL} dataset
     * into the heap (review finding&#160;#21); the stream is consumed inside a try-with-resources block
     * so the JDBC cursor is released. For each row:</p>
     * <ol>
     *   <li><strong>Account control break</strong> ({@code IF TRANCAT-ACCT-ID NOT = WS-LAST-ACCT-NUM},
     *       L194): on a change, write the <em>previous</em> account back via
     *       {@link #updateAccount(Account, BigDecimal)} unless this is the first row (L195-198), reset
     *       the per-account interest accumulator (L200), remember the new account id (L201), and load
     *       the account ({@link #getAccountData(Long)}, L203) and its card cross-reference
     *       ({@link #getCardNumber(Long)}, L205).</li>
     *   <li><strong>Disclosure key</strong> (L210-212): the account-group id comes from the loaded
     *       <em>account</em>; the type and category codes come from the <em>category-balance row</em>.</li>
     *   <li><strong>Interest rate</strong> ({@link #resolveInterestRate(String, String, Integer)},
     *       L213) with the DEFAULT fallback.</li>
     *   <li><strong>Compute + post</strong> ({@code IF DIS-INT-RATE NOT = 0}, L214-216): when the rate
     *       is non-zero, compute the month's interest, add it to the per-account accumulator, write one
     *       interest transaction, and invoke the {@code 1400-COMPUTE-FEES} no-op. A zero rate is
     *       skipped entirely &mdash; no transaction and no accumulation for that row.</li>
     * </ol>
     *
     * <p><strong>Final-account quirk preserved:</strong> there is intentionally no write-back after
     * the loop, so the last account group's balance and cycle totals are left untouched (see the
     * class Javadoc and {@code docs/decision-log.md}). The per-transaction id suffix
     * ({@code WS-TRANID-SUFFIX}) is a single monotonic counter across the whole run and is never
     * reset per account.</p>
     *
     * <p>This method is package-private (rather than {@code private}) purely so unit tests can drive
     * the control-break logic directly with mocked repositories; it is not part of any public API.</p>
     *
     * @param processingDate the COBOL {@code PARM-DATE}; the verbatim 10-character prefix of every
     *                       generated {@code TRAN-ID}. Must be non-{@code null} and non-blank.
     * @return the number of category-balance rows processed (the COBOL {@code WS-RECORD-COUNT})
     * @throws IllegalArgumentException if {@code processingDate} is {@code null} or blank
     * @throws IllegalStateException    on an abend-equivalent condition (missing account, missing
     *                                  cross-reference, or missing DEFAULT disclosure group)
     */
    int calculateInterest(String processingDate) {
        if (processingDate == null || processingDate.isBlank()) {
            throw new IllegalArgumentException(
                    "Job parameter '" + PROCESSING_DATE_PARAM + "' is required (COBOL PARM-DATE)");
        }

        Long lastAcctNum = null;                 // WS-LAST-ACCT-NUM (VALUE SPACES) -> null sentinel
        boolean firstTime = true;                // WS-FIRST-TIME = 'Y'
        BigDecimal totalInterest = BigDecimal.ZERO;  // WS-TOTAL-INT (reset per account)
        long tranIdSuffix = 0L;                  // WS-TRANID-SUFFIX (global; never reset per account)
        int recordCount = 0;                     // WS-RECORD-COUNT

        Account currentAccount = null;
        String currentCardNum = null;

        // 1000-TCATBALF-GET-NEXT: forward-only cursor-backed key-order scan (review finding #21). The
        // stream is consumed inside try-with-resources so the JDBC cursor is released; the enclosing
        // Spring Batch tasklet transaction keeps the persistence context open for the whole scan. An
        // explicit iterator (not forEach) is used so the control-break state below is mutated as
        // ordinary locals rather than captured by a lambda.
        try (Stream<TransactionCategoryBalance> categoryBalances =
                categoryBalanceRepository.streamAllByAccountKeyOrder()) {
            Iterator<TransactionCategoryBalance> iterator = categoryBalances.iterator();
            while (iterator.hasNext()) {
                TransactionCategoryBalance row = iterator.next();
                recordCount++;                       // ADD 1 TO WS-RECORD-COUNT (L192)
                Long acctId = row.getAcctId();

                // 1) Account control break (L194): TRANCAT-ACCT-ID NOT = WS-LAST-ACCT-NUM.
                if (!acctId.equals(lastAcctNum)) {
                    if (!firstTime) {
                        // Write the PREVIOUS account's accumulated interest (1050) -- L195-196.
                        updateAccount(currentAccount, totalInterest);
                    } else {
                        firstTime = false;           // MOVE 'N' TO WS-FIRST-TIME (L197-198)
                    }
                    totalInterest = BigDecimal.ZERO; // MOVE 0 TO WS-TOTAL-INT (L200)
                    lastAcctNum = acctId;            // MOVE TRANCAT-ACCT-ID TO WS-LAST-ACCT-NUM (L201)
                    currentAccount = getAccountData(acctId);   // 1100-GET-ACCT-DATA (L203)
                    currentCardNum = getCardNumber(acctId);    // 1110-GET-XREF-DATA (L205)
                }

                // 2) Disclosure key mixes the account's group id with the row's type + category
                //    (L210-212): group from ACCT-GROUP-ID, type from TRANCAT-TYPE-CD, cat from TRANCAT-CD.
                BigDecimal interestRate = resolveInterestRate(
                        currentAccount.getGroupId(), row.getTypeCd(), row.getCatCd());  // 1200 (+1200-A)

                // 4) IF DIS-INT-RATE NOT = 0 (L214): compute + post; a zero rate is skipped entirely.
                if (CobolDecimal.nullToZero(interestRate).compareTo(BigDecimal.ZERO) != 0) {
                    BigDecimal monthlyInterest =
                            computeMonthlyInterest(row.getBalance(), interestRate);     // 1300-COMPUTE-INTEREST
                    totalInterest = totalInterest.add(monthlyInterest);                 // ADD ... TO WS-TOTAL-INT (L467)
                    tranIdSuffix = writeInterestTransaction(
                            processingDate, tranIdSuffix, currentAccount, currentCardNum, monthlyInterest); // 1300-B
                    computeFees();                                                      // 1400-COMPUTE-FEES (no-op)
                }
            }
        }

        // PARITY-CRITICAL QUIRK (L188-222): the driver is PERFORM UNTIL END-OF-FILE = 'Y' (test-before)
        // and 1050-UPDATE-ACCOUNT runs only on an account change, so the FINAL account group is never
        // written back. There is intentionally NO post-loop flush here. See class Javadoc +
        // docs/decision-log.md. Do NOT "fix" this by updating currentAccount after the loop.

        return recordCount;
    }

    /**
     * {@code 1100-GET-ACCT-DATA} (L372): loads the account addressed by the current
     * category-balance row's account id (COBOL random {@code READ ACCOUNT-FILE} by
     * {@code FD-ACCT-ID}).
     *
     * <p>In COBOL a failed read &mdash; including {@code INVALID KEY} (file status {@code '23'})
     * &mdash; sets {@code APPL-RESULT} to {@code 12} and performs {@code 9999-ABEND-PROGRAM}. A
     * missing account is therefore fatal, reproduced here by throwing {@link IllegalStateException}
     * so the step fails rather than continuing with no account context.</p>
     *
     * @param acctId the account id to load (COBOL {@code TRANCAT-ACCT-ID} moved to {@code FD-ACCT-ID})
     * @return the managed {@link Account}
     * @throws IllegalStateException if no account exists for {@code acctId} (COBOL abend)
     */
    private Account getAccountData(Long acctId) {
        return accountRepository.findById(acctId)
                .orElseThrow(() -> new IllegalStateException(
                        "Account not found for interest calculation: acctId=" + acctId));
    }

    /**
     * {@code 1110-GET-XREF-DATA} (L393): resolves the card number for the account via the card
     * cross-reference, reproducing the COBOL alternate-index read
     * {@code READ XREF-FILE ... KEY IS FD-XREF-ACCT-ID}, which returns the first cross-reference for
     * the account. The card number is used only to tag the generated interest transaction
     * ({@code TRAN-CARD-NUM}).
     *
     * <p>As in {@code CBACT04C}, a failed read (file status {@code != '00'}, including a missing
     * cross-reference) sets {@code APPL-RESULT} to {@code 12} and performs
     * {@code 9999-ABEND-PROGRAM}; a missing cross-reference is therefore fatal, reproduced here by
     * throwing {@link IllegalStateException}.</p>
     *
     * @param acctId the account id (COBOL {@code TRANCAT-ACCT-ID} moved to {@code FD-XREF-ACCT-ID})
     * @return the 16-character card number ({@code XREF-CARD-NUM}) of the first cross-reference
     * @throws IllegalStateException if the account has no card cross-reference (COBOL abend)
     */
    private String getCardNumber(Long acctId) {
        List<CardXref> xrefs = cardXrefRepository.findByXrefAcctId(acctId);
        if (xrefs.isEmpty()) {
            throw new IllegalStateException(
                    "Card cross-reference not found for interest calculation: acctId=" + acctId);
        }
        return xrefs.get(0).getXrefCardNum();
    }

    /**
     * {@code 1200-GET-INTEREST-RATE} (L415) plus the {@code 1200-A-GET-DEFAULT-INT-RATE} fallback
     * (L443): looks up the disclosure interest rate for the {@code (account-group, type, category)}
     * tuple.
     *
     * <p>The COBOL reads {@code DISCGRP} by the composite key and treats file status {@code '00'}
     * (found) and {@code '23'} (not found) as acceptable, abending on any other status. On
     * {@code '23'} it moves {@code 'DEFAULT'} into the group id and re-reads; if that DEFAULT row is
     * also absent it abends. This method mirrors that exactly: it first looks up the specific key and,
     * when absent, retries with the {@code 'DEFAULT'} group, throwing {@link IllegalStateException}
     * (the abend equivalent) only when neither row exists. Any underlying data-access failure
     * propagates as-is, matching the COBOL abend on an unexpected file status.</p>
     *
     * @param acctGroupId the account-group id (COBOL {@code ACCT-GROUP-ID} from the loaded account)
     * @param tranTypeCd  the transaction-type code (COBOL {@code TRANCAT-TYPE-CD} from the row)
     * @param tranCatCd   the transaction-category code (COBOL {@code TRANCAT-CD} from the row)
     * @return the disclosure interest rate ({@code DIS-INT-RATE}) for the specific or DEFAULT group
     * @throws IllegalStateException if neither the specific nor the DEFAULT disclosure row exists
     */
    private BigDecimal resolveInterestRate(String acctGroupId, String tranTypeCd, Integer tranCatCd) {
        Optional<DisclosureGroup> specific = disclosureGroupRepository.findById(
                new DisclosureGroup.DisclosureGroupId(acctGroupId, tranTypeCd, tranCatCd));
        if (specific.isPresent()) {
            return specific.get().getIntRate();
        }
        // File status '23' (not found): MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID and re-read (1200-A).
        return disclosureGroupRepository.findById(
                        new DisclosureGroup.DisclosureGroupId(
                                DEFAULT_DISCLOSURE_GROUP_ID, tranTypeCd, tranCatCd))
                .orElseThrow(() -> new IllegalStateException(
                        "Disclosure group interest rate not found (neither specific group nor DEFAULT): "
                                + "group=" + acctGroupId + ", type=" + tranTypeCd
                                + ", category=" + tranCatCd))
                .getIntRate();
    }

    /**
     * {@code 1300-COMPUTE-INTEREST} (L462-465), the canonical decimal-fidelity computation
     * (AAP &sect;0.6.1): {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}.
     *
     * <p>Because {@code WS-MONTHLY-INT} ({@code PIC S9(09)V99}) has <em>no</em> {@code ROUNDED}
     * phrase, the quotient is <em>truncated</em> to two decimals. The intermediate product is kept at
     * full precision and only the final divide fixes the scale using
     * {@link java.math.RoundingMode#DOWN} via {@link CobolDecimal#divide(BigDecimal, BigDecimal, int)}.
     * For example {@code (1000.00 * 19.99) / 1200 = 16.6583...} truncates to {@code 16.65} (never
     * rounded up to {@code 16.66}). Both operands are defaulted to zero when {@code null}, matching
     * COBOL numeric fields, which are never null.</p>
     *
     * <p>Declared {@code static} because it is a pure function of its arguments; this also lets unit
     * tests exercise the truncation rule directly.</p>
     *
     * @param categoryBalance the category running balance ({@code TRAN-CAT-BAL}); {@code null} is zero
     * @param interestRate    the disclosure interest rate ({@code DIS-INT-RATE}); {@code null} is zero
     * @return the month's interest, truncated to two decimals
     */
    private static BigDecimal computeMonthlyInterest(BigDecimal categoryBalance, BigDecimal interestRate) {
        BigDecimal catBal = CobolDecimal.nullToZero(categoryBalance);
        BigDecimal rate = CobolDecimal.nullToZero(interestRate);
        return CobolDecimal.divide(catBal.multiply(rate), MONTHLY_INTEREST_DIVISOR, CobolDecimal.MONEY_SCALE);
    }

    /**
     * {@code 1300-B-WRITE-TX} (L473-500): builds and persists one system-generated interest
     * {@link Transaction} for the current category-balance row.
     *
     * <p>Field-by-field parity with the COBOL:</p>
     * <ul>
     *   <li><strong>{@code TRAN-ID}</strong> (L474-480): the suffix counter is incremented first
     *       ({@code ADD 1 TO WS-TRANID-SUFFIX}) and the id is {@code PARM-DATE} (verbatim, 10 chars)
     *       concatenated with the six-digit zero-padded suffix ({@code PIC 9(06)}), giving a 16-char
     *       id; the first id ends {@code 000001}. The suffix wraps at
     *       {@value #TRANID_SUFFIX_MODULUS} to mirror the fixed six-digit field, and the counter is
     *       never reset between accounts.</li>
     *   <li><strong>{@code TRAN-TYPE-CD}</strong> {@code "01"}; <strong>{@code TRAN-CAT-CD}</strong>
     *       {@code 5} (COBOL {@code '05'} into {@code PIC 9(04)}); <strong>{@code TRAN-SOURCE}</strong>
     *       {@code "System"} (L482-484).</li>
     *   <li><strong>{@code TRAN-DESC}</strong> (L485-489): {@code "Int. for a/c "} followed by the
     *       account id rendered as 11 zero-padded digits, reproducing the {@code STRING} of the
     *       {@code ACCT-ID PIC 9(11)} display field.</li>
     *   <li><strong>{@code TRAN-AMT}</strong> the computed monthly interest (L490);
     *       <strong>{@code TRAN-MERCHANT-ID}</strong> {@code 0} and merchant name/city/zip blank
     *       (L491-494); <strong>{@code TRAN-CARD-NUM}</strong> the cross-reference card number
     *       (L495).</li>
     *   <li><strong>{@code TRAN-ORIG-TS}</strong> and <strong>{@code TRAN-PROC-TS}</strong> both the
     *       current timestamp captured once at write time (L496-498), reproducing
     *       {@code Z-GET-DB2-FORMAT-TIMESTAMP} writing the same value to both fields.</li>
     * </ul>
     *
     * <p>The transaction is persisted explicitly via {@link TransactionRepository#save(Object)}
     * (the {@code WRITE FD-TRANFILE-REC} equivalent, L500) rather than relying on JPA dirty
     * checking.</p>
     *
     * @param processingDate  the verbatim {@code PARM-DATE} prefix
     * @param tranIdSuffix    the current value of the global suffix counter before this transaction
     * @param account         the account the interest is for (source of {@code ACCT-ID} in the desc)
     * @param cardNum         the card number from the cross-reference ({@code XREF-CARD-NUM})
     * @param monthlyInterest the computed interest amount ({@code WS-MONTHLY-INT})
     * @return the incremented suffix counter, to be threaded into the next call
     */
    private long writeInterestTransaction(String processingDate, long tranIdSuffix,
            Account account, String cardNum, BigDecimal monthlyInterest) {
        long nextSuffix = tranIdSuffix + 1;  // ADD 1 TO WS-TRANID-SUFFIX (L474)

        Transaction interestTx = new Transaction();
        interestTx.setTranId(processingDate
                + String.format("%06d", nextSuffix % TRANID_SUFFIX_MODULUS));  // L476-480
        interestTx.setTranTypeCd(INTEREST_TRAN_TYPE_CD);                        // '01' (L482)
        interestTx.setTranCatCd(INTEREST_TRAN_CAT_CD);                         // '05' -> 0005 (L483)
        interestTx.setTranSource(INTEREST_TRAN_SOURCE);                        // 'System' (L484)
        interestTx.setTranDesc(INTEREST_TRAN_DESC_PREFIX
                + String.format("%011d", account.getAcctId()));               // L485-489
        interestTx.setTranAmt(monthlyInterest);                               // TRAN-AMT (L490)
        interestTx.setMerchantId(INTEREST_MERCHANT_ID);                       // 0 (L491)
        interestTx.setMerchantName(BLANK);                                    // SPACES (L492)
        interestTx.setMerchantCity(BLANK);                                    // SPACES (L493)
        interestTx.setMerchantZip(BLANK);                                     // SPACES (L494)
        interestTx.setCardNum(cardNum);                                       // XREF-CARD-NUM (L495)
        LocalDateTime now = LocalDateTime.now();                              // Z-GET-DB2-FORMAT-TIMESTAMP (L496)
        interestTx.setOrigTs(now);                                            // TRAN-ORIG-TS (L497)
        interestTx.setProcTs(now);                                            // TRAN-PROC-TS (L498)

        transactionRepository.save(interestTx);                              // WRITE FD-TRANFILE-REC (L500)
        return nextSuffix;
    }

    /**
     * {@code 1050-UPDATE-ACCOUNT} (L350-356): writes an account's accumulated interest back to its
     * balance on a control break.
     *
     * <p>It adds the per-account interest total to {@code ACCT-CURR-BAL}
     * ({@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL}, L352) &mdash; both fixed-scale two-decimal values,
     * kept at scale two via {@link CobolDecimal#money(BigDecimal)} &mdash; and resets the cycle credit
     * and debit totals to zero ({@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} / {@code ACCT-CURR-CYC-DEBIT},
     * L353-354), then persists the change explicitly via {@link AccountRepository#save(Object)} (the
     * {@code REWRITE FD-ACCTFILE-REC} equivalent, L356).</p>
     *
     * <p>Called <em>only</em> on an account-id change (for the <em>previous</em> account), never for
     * the final account &mdash; the parity-critical quirk documented on the class. Because it is the
     * sole mutator of an account and always saves explicitly, the final account (never passed here) is
     * left entirely untouched.</p>
     *
     * @param account       the account to write back (the previous account at a control break)
     * @param totalInterest the interest accumulated for that account ({@code WS-TOTAL-INT})
     */
    private void updateAccount(Account account, BigDecimal totalInterest) {
        account.setCurrBal(CobolDecimal.money(
                CobolDecimal.nullToZero(account.getCurrBal()).add(totalInterest)));  // L352
        account.setCurrCycCredit(ZERO_MONEY);   // MOVE 0 TO ACCT-CURR-CYC-CREDIT (L353)
        account.setCurrCycDebit(ZERO_MONEY);    // MOVE 0 TO ACCT-CURR-CYC-DEBIT (L354)
        accountRepository.save(account);        // REWRITE FD-ACCTFILE-REC (L356)
    }

    /**
     * {@code 1400-COMPUTE-FEES} (L518-520): a documented no-op in the legacy program (its body is the
     * comment "To be implemented").
     *
     * <p>It is reproduced here as an intentionally empty method so the {@code CBACT04C} paragraph
     * &rarr; Java method mapping stays 1:1 in the traceability matrix (Explainability rule). No fee
     * logic is invented, honoring the no-feature-expansion mandate; the rationale is recorded in
     * {@code docs/decision-log.md}.</p>
     */
    private void computeFees() {
        // Intentionally empty: legacy 1400-COMPUTE-FEES is "To be implemented" (no-op). Retained for
        // 1:1 paragraph-to-method traceability; introducing fee logic would be feature expansion.
    }
}
