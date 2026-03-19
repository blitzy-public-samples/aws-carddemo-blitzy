/*
 * InterestCalcJobConfig.java — Spring Batch Job Configuration for Interest Calculation
 *
 * Translates JCL INTCALC batch job into a Spring Batch @Configuration class.
 * Source COBOL program: CBACT04C.cbl — batch interest calculator program.
 *
 * COBOL-to-Java Traceability (CBACT04C.cbl paragraphs → Java components):
 *   MAIN (lines 180-232)                → interestCalcJob() + interestCalcStep()
 *   0000-0400 file OPEN/CLOSE           → Spring Batch lifecycle (ItemStream.open/close)
 *   1000-TCATBALF-GET-NEXT (325-348)    → categoryBalanceReader() — RepositoryItemReader
 *   1050-UPDATE-ACCOUNT (350-370)       → InterestCalculationProcessor.updateAccount()
 *   1100-GET-ACCT-DATA (372-391)        → AccountRepository.findById()
 *   1110-GET-XREF-DATA (393-413)        → CardXrefRepository.findByAccountId() (AIX)
 *   1200-GET-INTEREST-RATE (415-440)    → DiscountGroupRepository lookup
 *   1200-A-GET-DEFAULT-INT-RATE (443-460) → DiscountGroupRepository DEFAULT fallback
 *   1300-COMPUTE-INTEREST (462-470)     → InterestCalculationProcessor.computeInterest()
 *   1300-B-WRITE-TX (473-515)           → InterestCalculationService.writeInterestTransaction()
 *   1400-COMPUTE-FEES (518-520)         → InterestCalculationService.computeFees() [STUB]
 *   9000-9400 file CLOSE                → Spring Batch lifecycle (automatic)
 *
 * Processing Flow:
 *   1. Reader: reads CategoryBalance records ordered by accountId ASC
 *      (← COBOL sequential READ on TCATBAL-FILE keyed by FD-TRANCAT-ACCT-ID)
 *   2. Processor: STATEFUL interest calculation with account boundary detection
 *      (← COBOL grouping by TRANCAT-ACCT-ID with WS-LAST-ACCT-NUM)
 *   3. Writer: delegates to InterestCalculationService for persistence
 *      (← COBOL WRITE/REWRITE file operations and 1400-COMPUTE-FEES call)
 *   4. Listener: afterStep callback flushes the last account's accumulated interest
 *      (← COBOL post-loop PERFORM 1050-UPDATE-ACCOUNT at line 220)
 *
 * CRITICAL: The InterestCalculationProcessor is STATEFUL — it tracks lastAccountId,
 * totalInterest, and firstTime state across process() calls for account boundary
 * detection and interest accumulation. The step MUST be single-threaded (no
 * taskExecutor configured) to ensure correct sequential processing. The processor's
 * @AfterStep callback flushes the last account's accumulated interest.
 *
 * Interest Formula (CBACT04C.cbl line 464-465):
 *   COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
 *   Uses BigDecimal with RoundingMode.HALF_UP, scale=2 (matching COBOL PIC S9(09)V99)
 *   Divisor 1200 = 12 months × 100 (percentage to decimal conversion)
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.batch.job;

import com.cardemo.batch.processor.InterestCalculationProcessor;
import com.cardemo.entity.CategoryBalance;
import com.cardemo.repository.CategoryBalanceRepository;
import com.cardemo.service.batch.InterestCalculationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

/**
 * Spring Batch job configuration for the interest calculation batch process.
 *
 * <p>Translates JCL INTCALC batch job into a Spring Batch {@link Job} with a
 * single chunk-oriented {@link Step}. The original COBOL program is CBACT04C.cbl,
 * which reads transaction category balance records (TCATBALF VSAM KSDS), groups
 * them by account ID, computes monthly interest using discount group rates,
 * writes interest transaction records, and updates account balances.</p>
 *
 * <h3>COBOL Datasets Mapped (← CBACT04C.cbl FILE-CONTROL, lines 27-56):</h3>
 * <ul>
 *   <li>TCATBAL-FILE (TCATBALF) — indexed input, sequential read
 *       → {@link CategoryBalanceRepository} via {@link #categoryBalanceReader()}</li>
 *   <li>XREF-FILE (XREFFILE) — indexed random, AIX on XREF-ACCT-ID
 *       → CardXrefRepository (used by processor)</li>
 *   <li>ACCOUNT-FILE (ACCTFILE) — indexed I-O for balance updates
 *       → AccountRepository (used by processor)</li>
 *   <li>DISCGRP-FILE (DISCGRP) — indexed random for interest rate lookup
 *       → DiscountGroupRepository (used by processor)</li>
 *   <li>TRANSACT-FILE (TRANSACT) — sequential output for interest transactions
 *       → TransactionRepository (used by service via writer)</li>
 * </ul>
 *
 * <h3>External Parameters (← CBACT04C.cbl LINKAGE SECTION, lines 175-178):</h3>
 * <p>The COBOL program accepts a {@code PARM-DATE} (PIC X(10)) via the LINKAGE
 * SECTION. In the Spring Batch translation, this is passed as a job parameter
 * named {@code parmDate} and accessed by the processor and service via
 * {@code @StepScope} late-binding.</p>
 *
 * <h3>Chunk-Oriented Processing:</h3>
 * <p>Each chunk of 10 {@link CategoryBalance} records is processed within a
 * single database transaction, ensuring atomicity of interest computation and
 * account balance updates — matching the COBOL file I/O commit semantics where
 * REWRITE operations on ACCOUNT-FILE are committed sequentially.</p>
 *
 * @see InterestCalculationProcessor
 * @see InterestCalculationService
 * @see CategoryBalance
 * @see CategoryBalanceRepository
 */
@Configuration
public class InterestCalcJobConfig {

    private static final Logger log = LoggerFactory.getLogger(
            InterestCalcJobConfig.class);

    // =========================================================================
    // Injected Dependencies (constructor-injected for consistency with other
    // job configs and Spring best practices — immutable after construction)
    // =========================================================================

    /** Spring Batch metadata repository for job/step execution persistence. */
    private final JobRepository jobRepository;

    /** Spring transaction manager for chunk-oriented step processing. */
    private final PlatformTransactionManager transactionManager;

    /**
     * STATEFUL interest calculation processor (← CBACT04C.cbl business logic).
     * Tracks lastAccountId, totalInterest, and firstTime state across consecutive
     * {@code process()} invocations for account boundary detection.
     */
    private final InterestCalculationProcessor interestCalculationProcessor;

    /**
     * Interest calculation service providing the underlying business logic for
     * account balance updates, interest transaction writes, and fee computation.
     */
    private final InterestCalculationService interestCalculationService;

    /** Spring Data JPA repository for the TCATBALF VSAM dataset. */
    private final CategoryBalanceRepository categoryBalanceRepository;

    /**
     * Constructs the interest calculation job configuration with all required
     * dependencies injected via constructor (Spring-recommended DI pattern).
     *
     * @param jobRepository                   Spring Batch metadata repository
     * @param transactionManager              transaction manager for chunk steps
     * @param interestCalculationProcessor    STATEFUL processor for interest logic
     * @param interestCalculationService      service for persistence operations
     * @param categoryBalanceRepository       TCATBALF dataset repository
     */
    public InterestCalcJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            InterestCalculationProcessor interestCalculationProcessor,
            InterestCalculationService interestCalculationService,
            CategoryBalanceRepository categoryBalanceRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.interestCalculationProcessor = interestCalculationProcessor;
        this.interestCalculationService = interestCalculationService;
        this.categoryBalanceRepository = categoryBalanceRepository;
    }

    // =========================================================================
    // Job Bean — MAIN (← CBACT04C.cbl PROCEDURE DIVISION, lines 180-232)
    // =========================================================================

    /**
     * Creates the interest calculation batch {@link Job}.
     *
     * <p>Translates the COBOL PROCEDURE DIVISION entry point (CBACT04C.cbl
     * lines 180-232) into a Spring Batch Job named {@code "interestCalcJob"},
     * corresponding to the JCL INTCALC job definition.</p>
     *
     * <p>The job consists of a single step ({@link #interestCalcStep()}) that
     * performs the complete interest calculation workflow: read TCATBAL records,
     * group by account, compute interest, write transactions, and update
     * account balances.</p>
     *
     * <p>Replaces COBOL DISPLAY banners:</p>
     * <ul>
     *   <li>Line 181: {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT04C'}
     *       → SLF4J info log at job configuration time</li>
     *   <li>Line 230: {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT04C'}
     *       → SLF4J info log in the afterStep listener callback</li>
     * </ul>
     *
     * @return the configured interest calculation {@link Job}
     */
    @Bean
    public Job interestCalcJob() {
        // ← COBOL line 181: DISPLAY 'START OF EXECUTION OF PROGRAM CBACT04C'
        log.info("Configuring interestCalcJob (← JCL INTCALC / CBACT04C.cbl)");
        return new JobBuilder("interestCalcJob", jobRepository)
                .start(interestCalcStep())
                .build();
    }

    // =========================================================================
    // Step Bean — Processing loop (← CBACT04C.cbl lines 188-222)
    // =========================================================================

    /**
     * Creates the interest calculation {@link Step} with chunk-oriented processing.
     *
     * <p>Translates the COBOL main processing loop (CBACT04C.cbl lines 188-222,
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'}) into a Spring Batch chunk step
     * with the following pipeline:</p>
     * <ol>
     *   <li><strong>Reader:</strong> {@link #categoryBalanceReader()} reads
     *       {@link CategoryBalance} records ordered by accountId ASC, matching
     *       the COBOL sequential READ on TCATBAL-FILE keyed by
     *       FD-TRANCAT-ACCT-ID (paragraph 1000-TCATBALF-GET-NEXT)</li>
     *   <li><strong>Processor:</strong> {@link InterestCalculationProcessor}
     *       performs STATEFUL interest computation with account boundary detection
     *       (paragraphs 1050-1400). Groups records by account, computes monthly
     *       interest using discount group rates with DEFAULT fallback, and updates
     *       account balances when the account ID changes</li>
     *   <li><strong>Writer:</strong> {@link #interestWriter()} delegates to
     *       {@link InterestCalculationService} for persistence operations including
     *       the 1400-COMPUTE-FEES stub call preserving COBOL call semantics</li>
     *   <li><strong>Listener:</strong> {@link StepExecutionListener} wrapper around
     *       {@link InterestCalculationProcessor#afterStep(StepExecution)} to flush
     *       the last account's accumulated interest after all items are processed
     *       (← COBOL post-loop PERFORM 1050-UPDATE-ACCOUNT at line 220)</li>
     * </ol>
     *
     * <p><strong>CRITICAL — Single-threaded execution:</strong> No
     * {@code taskExecutor()} is configured on this step because the
     * {@link InterestCalculationProcessor} is STATEFUL. It tracks
     * {@code lastAccountId}, {@code totalInterest}, and {@code firstTime}
     * across consecutive {@code process()} calls. Multi-threaded execution
     * would produce incorrect results due to race conditions on shared state.</p>
     *
     * <p><strong>Chunk size:</strong> 10 records per chunk, processed within a
     * single database transaction via the injected
     * {@link PlatformTransactionManager}.</p>
     *
     * @return the configured interest calculation {@link Step}
     */
    @Bean
    public Step interestCalcStep() {
        return new StepBuilder("interestCalcStep", jobRepository)
                .<CategoryBalance, CategoryBalance>chunk(10, transactionManager)
                .reader(categoryBalanceReader())
                .processor(interestCalculationProcessor)
                .writer(interestWriter())
                .listener(new StepExecutionListener() {
                    /**
                     * Before-step callback that propagates PARM-DATE from
                     * job parameters into the STATEFUL processor. This is
                     * equivalent to the COBOL LINKAGE SECTION receiving
                     * PARM-DATE (PIC X(10)) from the JCL EXEC PARM.
                     *
                     * @param stepExecution the step execution context
                     */
                    @Override
                    public void beforeStep(StepExecution stepExecution) {
                        String date = stepExecution.getJobParameters()
                                .getString("parmDate", "");
                        interestCalculationProcessor.setParmDate(date);
                    }

                    /**
                     * After-step callback that flushes the last account's
                     * accumulated interest and logs the job completion banner.
                     *
                     * <p>Translates COBOL post-loop logic (CBACT04C.cbl line
                     * 220): after the main PERFORM UNTIL loop exits, a final
                     * {@code PERFORM 1050-UPDATE-ACCOUNT} updates the last
                     * account that was being processed.</p>
                     *
                     * @param stepExecution the step execution context
                     * @return {@link ExitStatus#COMPLETED} from the processor's
                     *         afterStep callback, indicating successful completion
                     */
                    @Override
                    public ExitStatus afterStep(StepExecution stepExecution) {
                        // ← COBOL line 230:
                        // DISPLAY 'END OF EXECUTION OF PROGRAM CBACT04C'
                        log.info("END OF EXECUTION OF PROGRAM CBACT04C "
                                + "(← COBOL DISPLAY line 230). "
                                + "Step exit status: {}",
                                stepExecution.getExitStatus().getExitCode());
                        return interestCalculationProcessor
                                .afterStep(stepExecution);
                    }
                })
                .build();
    }

    // =========================================================================
    // Reader Bean — 1000-TCATBALF-GET-NEXT (← CBACT04C.cbl lines 325-348)
    // =========================================================================

    /**
     * Creates the category balance item reader with step-scope lifecycle.
     *
     * <p>Translates COBOL paragraph 1000-TCATBALF-GET-NEXT (CBACT04C.cbl
     * lines 325-348), which performs a sequential indexed READ on the
     * TCATBAL-FILE (KSDS with primary key FD-TRAN-CAT-KEY composed of
     * FD-TRANCAT-ACCT-ID + FD-TRANCAT-TYPE-CD + FD-TRANCAT-CD).</p>
     *
     * <p>The reader is configured with {@code @StepScope} to ensure:</p>
     * <ul>
     *   <li>A fresh reader instance per step execution (preventing state
     *       leakage from previous job runs)</li>
     *   <li>Late-binding of job parameters (e.g., {@code parmDate}) if
     *       needed by the reader configuration</li>
     * </ul>
     *
     * <p>Records are read ordered by {@code accountId ASC}, which replicates
     * the COBOL VSAM KSDS primary key ordering. This is <strong>critical</strong>
     * for the {@link InterestCalculationProcessor}'s stateful account boundary
     * detection logic — records for the same account must arrive consecutively.</p>
     *
     * <p>The reader uses Spring Data JPA's {@code findAll(Pageable)} method via
     * the {@link RepositoryItemReaderBuilder}, providing automatic pagination
     * for efficient memory usage during large batch runs.</p>
     *
     * @return the step-scoped {@link ItemReader} for {@link CategoryBalance} records
     */
    @Bean
    @StepScope
    public ItemReader<CategoryBalance> categoryBalanceReader() {
        log.debug("Creating categoryBalanceReader — ordered by accountId ASC "
                + "(← COBOL 1000-TCATBALF-GET-NEXT sequential indexed read "
                + "on FD-TRANCAT-ACCT-ID)");
        return new RepositoryItemReaderBuilder<CategoryBalance>()
                .name("categoryBalanceReader")
                .repository(categoryBalanceRepository)
                .methodName("findAll")
                .sorts(Map.of("accountId", Sort.Direction.ASC))
                .build();
    }

    // =========================================================================
    // Writer Bean — Persistence delegation
    // (← CBACT04C.cbl paragraphs 1300-B-WRITE-TX, 1400-COMPUTE-FEES)
    // =========================================================================

    /**
     * Creates the interest calculation item writer.
     *
     * <p>The {@link InterestCalculationProcessor} (stateful) handles the core
     * business logic: interest rate lookup with DEFAULT fallback (paragraphs
     * 1200, 1200-A), interest computation and accumulation (paragraph
     * 1300-COMPUTE-INTEREST), and account balance updates on boundary changes
     * (paragraph 1050-UPDATE-ACCOUNT).</p>
     *
     * <p>This writer completes the Spring Batch chunk lifecycle and delegates
     * to {@link InterestCalculationService} for the 1400-COMPUTE-FEES call,
     * preserving the exact COBOL call sequence where {@code PERFORM
     * 1400-COMPUTE-FEES} (line 216) is invoked for each TCATBAL record with
     * a non-zero interest rate. The fee computation is a documented COBOL stub
     * ({@code "To be implemented"}, CBACT04C.cbl line 519) — no feature
     * expansion is performed.</p>
     *
     * <p>The {@link InterestCalculationService} also provides
     * {@code updateAccount()}, {@code writeInterestTransaction()}, and
     * {@code calculateInterest()} methods that support the overall batch
     * processing pipeline through the processor's delegation chain.</p>
     *
     * @return the configured {@link ItemWriter} for {@link CategoryBalance} records
     */
    @Bean
    public ItemWriter<CategoryBalance> interestWriter() {
        return items -> {
            for (CategoryBalance item : items) {
                // ← COBOL paragraph 1400-COMPUTE-FEES (line 518-520):
                // Stub in original COBOL — "To be implemented" (EXIT only).
                // Called per TCATBAL record in the processing loop (line 216):
                //   IF DIS-INT-RATE NOT = 0
                //       PERFORM 1300-COMPUTE-INTEREST
                //       PERFORM 1400-COMPUTE-FEES
                //   END-IF
                // Preserving exact COBOL call semantics — no feature expansion.
                interestCalculationService.computeFees();

                log.debug("Interest writer: chunk item committed — "
                        + "accountId={}, typeCode={}, categoryCode={}, "
                        + "balance={}",
                        item.getAccountId(), item.getTypeCode(),
                        item.getCategoryCode(), item.getBalance());
            }
        };
    }
}
