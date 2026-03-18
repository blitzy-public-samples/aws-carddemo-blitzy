package com.cardemo.batch.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.transaction.PlatformTransactionManager;

import com.cardemo.batch.processor.TransactionPostingProcessor;
import com.cardemo.batch.reader.DailyTransactionReader;
import com.cardemo.batch.writer.RejectFileWriter;
import com.cardemo.entity.DailyTransaction;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CategoryBalanceRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.batch.DailyPostingService;

/**
 * Spring Batch job configuration for the Daily Transaction Posting process.
 *
 * <p><strong>COBOL Origin:</strong> JCL job POSTTRAN executing COBOL program
 * {@code CBTRN02C.cbl} — "Post records from daily transaction file."</p>
 *
 * <p>This configuration translates the complete JCL POSTTRAN batch job into a
 * Spring Batch 5.x {@link Job} with a single chunk-oriented {@link Step}. The
 * original COBOL program processes six VSAM datasets:</p>
 * <ul>
 *   <li>{@code DALYTRAN} (sequential input) — daily transaction feed (~350 bytes/record)</li>
 *   <li>{@code TRANSACT} (indexed output) — transaction master file</li>
 *   <li>{@code XREF} (indexed lookup) — card-to-account cross-reference</li>
 *   <li>{@code DALYREJS} (sequential output) — rejected transaction records</li>
 *   <li>{@code ACCOUNT} (indexed I-O) — account master for balance updates</li>
 *   <li>{@code TCATBAL} (indexed I-O) — transaction category balance aggregation</li>
 * </ul>
 *
 * <h2>COBOL Paragraph-to-Java Component Mapping</h2>
 * <table>
 *   <tr><th>COBOL Paragraph</th><th>Java Component</th><th>Method</th></tr>
 *   <tr><td>MAIN (lines 193-234)</td><td>DailyPostingJobConfig</td>
 *       <td>{@link #dailyPostingJob()} + {@link #dailyPostingStep()}</td></tr>
 *   <tr><td>0000-0500 OPEN/CLOSE</td><td>Spring Batch lifecycle</td>
 *       <td>ItemStream.open() / close()</td></tr>
 *   <tr><td>1000-DALYTRAN-GET-NEXT</td><td>{@link DailyTransactionReader}</td>
 *       <td>read()</td></tr>
 *   <tr><td>1500-VALIDATE-TRAN</td><td>{@link TransactionPostingProcessor}</td>
 *       <td>process()</td></tr>
 *   <tr><td>1500-A-LOOKUP-XREF (reject 100)</td><td>TransactionPostingProcessor</td>
 *       <td>lookupXref()</td></tr>
 *   <tr><td>1500-B-LOOKUP-ACCT (rejects 101-103)</td><td>TransactionPostingProcessor</td>
 *       <td>lookupAccount()</td></tr>
 *   <tr><td>2000-POST-TRANSACTION</td><td>TransactionPostingProcessor + writer</td>
 *       <td>field copy + save</td></tr>
 *   <tr><td>2500-WRITE-REJECT-REC</td><td>{@link RejectFileWriter}</td>
 *       <td>write() — 430-byte records</td></tr>
 *   <tr><td>2700-UPDATE-TCATBAL</td><td>{@link DailyPostingService}</td>
 *       <td>{@link DailyPostingService#updateTcatbal(DailyTransaction,
 *           com.cardemo.entity.Account) updateTcatbal()}</td></tr>
 *   <tr><td>2800-UPDATE-ACCOUNT-REC</td><td>{@link DailyPostingService}</td>
 *       <td>{@link DailyPostingService#updateAccountRecord(
 *           com.cardemo.entity.Account, DailyTransaction) updateAccountRecord()}</td></tr>
 *   <tr><td>2900-WRITE-TRANSACTION-FILE</td><td>{@link TransactionRepository}</td>
 *       <td>saveAll() via {@link #transactionWriter()}</td></tr>
 * </table>
 *
 * <h2>Reject Codes (must match COBOL exactly)</h2>
 * <ul>
 *   <li><strong>100</strong>: XREF not found — "INVALID CARD NUMBER FOUND"</li>
 *   <li><strong>101</strong>: Account not found — "ACCOUNT RECORD NOT FOUND"</li>
 *   <li><strong>102</strong>: Credit limit exceeded — "OVERLIMIT TRANSACTION"</li>
 *   <li><strong>103</strong>: Account expired — "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"</li>
 * </ul>
 *
 * <p>The COBOL RETURN-CODE semantics are preserved: if any rejects occur, the
 * step exit status is set to {@code COMPLETED_WITH_REJECTS} (analogous to
 * {@code MOVE 4 TO RETURN-CODE} in CBTRN02C.cbl line 226).</p>
 *
 * @see DailyTransactionReader
 * @see TransactionPostingProcessor
 * @see RejectFileWriter
 * @see DailyPostingService
 * @see AccountRepository
 * @see CategoryBalanceRepository
 */
@Configuration
public class DailyPostingJobConfig {

    private static final Logger logger = LoggerFactory.getLogger(DailyPostingJobConfig.class);

    /**
     * Custom exit status indicating the job completed but rejected one or more
     * daily transaction records. Maps to COBOL {@code MOVE 4 TO RETURN-CODE}
     * in CBTRN02C.cbl line 226 ({@code IF WS-REJECT-COUNT > 0}).
     */
    private static final String EXIT_STATUS_COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    /**
     * Chunk size for the daily posting step. Each chunk reads, validates, and
     * persists this many daily transaction records in a single database
     * transaction. The value of 10 balances the COBOL record-by-record
     * processing model with Spring Batch's batch efficiency. Larger chunks
     * reduce transaction overhead; smaller chunks provide finer error
     * granularity.
     */
    private static final int CHUNK_SIZE = 10;

    // ========================================================================
    // Spring Batch Infrastructure (injected)
    // ========================================================================

    /** Spring Batch metadata repository for job/step execution persistence. */
    private final JobRepository jobRepository;

    /** Spring transaction manager for chunk-level transactional boundaries. */
    private final PlatformTransactionManager transactionManager;

    // ========================================================================
    // Batch Component Dependencies (injected)
    // ========================================================================

    /**
     * Reader component for DALYTRAN sequential file records.
     * Maps to CBTRN02C.cbl paragraph {@code 1000-DALYTRAN-GET-NEXT}.
     */
    private final DailyTransactionReader dailyTransactionReader;

    /**
     * Processor component implementing the 4-reject-code validation logic.
     * Maps to CBTRN02C.cbl paragraphs {@code 1500-VALIDATE-TRAN},
     * {@code 1500-A-LOOKUP-XREF}, and {@code 1500-B-LOOKUP-ACCT}.
     */
    private final TransactionPostingProcessor transactionPostingProcessor;

    /**
     * Writer for rejected transaction records (430-character fixed-width).
     * Registered as a step listener for {@code ItemStream} lifecycle
     * management (open/close DALYREJS file). Maps to CBTRN02C.cbl
     * paragraph {@code 2500-WRITE-REJECT-REC}.
     */
    private final RejectFileWriter rejectFileWriter;

    /**
     * Service encapsulating the complex daily posting business logic from
     * CBTRN02C.cbl. Provides TCATBAL update (paragraph {@code 2700-UPDATE-TCATBAL}),
     * account balance update (paragraph {@code 2800-UPDATE-ACCOUNT-REC}),
     * and transaction writing (paragraph {@code 2900-WRITE-TRANSACTION-FILE}).
     * Also exposes counters for post-step logging.
     */
    private final DailyPostingService dailyPostingService;

    // ========================================================================
    // Repository Dependencies (injected)
    // ========================================================================

    /**
     * Repository for the TRANSACT VSAM KSDS dataset. Used by
     * {@link #transactionWriter()} to batch-persist validated Transaction
     * entities via {@code saveAll()}.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Repository for the ACCTDATA VSAM KSDS dataset. Used for account
     * balance verification during post-step validation. Provides
     * {@code findById()} and {@code save()} for account read-update cycles
     * (maps to CBTRN02C.cbl paragraph {@code 2800-UPDATE-ACCOUNT-REC}).
     */
    private final AccountRepository accountRepository;

    /**
     * Repository for the TCATBALF VSAM KSDS dataset. Used for category
     * balance verification during post-step validation. Provides
     * composite-key lookup via {@code findByAccountIdAndTypeCodeAndCategoryCode()}
     * and {@code save()} (maps to CBTRN02C.cbl paragraph
     * {@code 2700-UPDATE-TCATBAL}).
     */
    private final CategoryBalanceRepository categoryBalanceRepository;

    // ========================================================================
    // Configuration Properties
    // ========================================================================

    /**
     * Input file resource for the DALYTRAN daily transaction feed.
     * Externalized via the {@code cardemo.batch.daily-transaction-file}
     * application property. Defaults to {@code file:./input/dailytran.txt}
     * to match the COBOL JCL DD statement for DALYTRAN-FILE.
     */
    @Value("${cardemo.batch.daily-transaction-file:file:./input/dailytran.txt}")
    private Resource dailyTransactionFile;

    // ========================================================================
    // Constructor — @Autowired dependency injection
    // ========================================================================

    /**
     * Constructs the {@code DailyPostingJobConfig} with all required dependencies
     * injected by the Spring container. Each parameter maps to a COBOL file or
     * service used by CBTRN02C.cbl:
     *
     * @param jobRepository                Spring Batch metadata repository
     * @param transactionManager           transaction manager for chunk boundaries
     * @param dailyTransactionReader       DALYTRAN sequential file reader
     *                                     (← 1000-DALYTRAN-GET-NEXT)
     * @param transactionPostingProcessor  validation and transformation processor
     *                                     (← 1500-VALIDATE-TRAN)
     * @param rejectFileWriter             DALYREJS reject output writer
     *                                     (← 2500-WRITE-REJECT-REC)
     * @param dailyPostingService          posting business logic service
     *                                     (← 2700/2800/2900 paragraphs)
     * @param transactionRepository        TRANSACT master file repository
     *                                     (← 2900-WRITE-TRANSACTION-FILE)
     * @param accountRepository            ACCTDATA account master repository
     *                                     (← 2800-UPDATE-ACCOUNT-REC)
     * @param categoryBalanceRepository    TCATBALF category balance repository
     *                                     (← 2700-UPDATE-TCATBAL)
     */
    @Autowired
    public DailyPostingJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            DailyTransactionReader dailyTransactionReader,
            TransactionPostingProcessor transactionPostingProcessor,
            RejectFileWriter rejectFileWriter,
            DailyPostingService dailyPostingService,
            TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            CategoryBalanceRepository categoryBalanceRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.dailyTransactionReader = dailyTransactionReader;
        this.transactionPostingProcessor = transactionPostingProcessor;
        this.rejectFileWriter = rejectFileWriter;
        this.dailyPostingService = dailyPostingService;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.categoryBalanceRepository = categoryBalanceRepository;
    }

    // ========================================================================
    // @Bean: dailyPostingJob() — JCL POSTTRAN Job Definition
    // ========================================================================

    /**
     * Defines the Spring Batch {@link Job} for daily transaction posting.
     *
     * <p><strong>COBOL Traceability:</strong> JCL job {@code POSTTRAN} executing
     * program {@code CBTRN02C}. The COBOL MAIN paragraph (lines 193-234)
     * orchestrates file opens, the processing loop, file closes, and the
     * DISPLAY of summary counts.</p>
     *
     * <p>The job consists of a single step ({@link #dailyPostingStep()}) that
     * performs the complete daily posting workflow: read DALYTRAN records,
     * validate each record, post valid transactions, reject invalid
     * transactions, and update account/category balances.</p>
     *
     * <p>Job name {@code "dailyPostingJob"} maps to the JCL job name
     * {@code POSTTRAN}.</p>
     *
     * @return the configured Spring Batch Job
     */
    @Bean
    public Job dailyPostingJob() {
        // COBOL CBTRN02C.cbl line 194:
        // DISPLAY 'START OF DAILY TRANSACTION POSTING'
        logger.info("START OF DAILY TRANSACTION POSTING (← JCL POSTTRAN / CBTRN02C.cbl)");

        return new JobBuilder("dailyPostingJob", jobRepository)
                .start(dailyPostingStep())
                .build();
    }

    // ========================================================================
    // @Bean: dailyPostingStep() — Chunk-Oriented Processing Step
    // ========================================================================

    /**
     * Defines the chunk-oriented processing {@link Step} for daily transaction
     * posting.
     *
     * <p><strong>COBOL Traceability:</strong> Maps to the main PERFORM loop in
     * CBTRN02C.cbl (lines 196-220):</p>
     * <pre>
     *   PERFORM 1000-DALYTRAN-GET-NEXT        → reader
     *   PERFORM UNTIL WS-DALYTRAN-EOF = 'Y'   → chunk loop
     *     PERFORM 1500-VALIDATE-TRAN           → processor (validation)
     *     IF WS-VALIDATION-FAIL-REASON = 0     → processor returns non-null
     *       PERFORM 2000-POST-TRANSACTION      → processor (field mapping) + writer
     *     ELSE
     *       PERFORM 2500-WRITE-REJECT-REC      → reject writer (via listener)
     *     END-IF
     *     PERFORM 1000-DALYTRAN-GET-NEXT       → reader (next chunk)
     *   END-PERFORM
     * </pre>
     *
     * <p>The step processes records in chunks of {@value #CHUNK_SIZE}, balancing
     * the COBOL record-by-record model with Spring Batch's batch efficiency.
     * Each chunk is wrapped in a database transaction via the injected
     * {@link PlatformTransactionManager}.</p>
     *
     * <p>The {@link RejectFileWriter} is registered as a step listener for
     * {@code ItemStream} lifecycle management, ensuring the DALYREJS output
     * file is opened before processing begins and closed after processing
     * completes (maps to COBOL paragraphs {@code 0300-DALYREJS-OPEN} and
     * {@code 9300-DALYREJS-CLOSE}).</p>
     *
     * @return the configured Spring Batch Step
     */
    @Bean
    public Step dailyPostingStep() {
        logger.debug("Configuring dailyPostingStep: chunk size={}, input={}",
                CHUNK_SIZE, dailyTransactionFile);

        return new StepBuilder("dailyPostingStep", jobRepository)
                .<DailyTransaction, Transaction>chunk(CHUNK_SIZE, transactionManager)
                .reader(dailyTransactionReader.createReader(dailyTransactionFile))
                .processor(transactionPostingProcessor)
                .writer(transactionWriter())
                .listener(rejectFileWriter)
                .listener(createPostingStepListener())
                .build();
    }

    // ========================================================================
    // @Bean: transactionWriter() — Transaction Persistence Writer
    // ========================================================================

    /**
     * Creates the {@link ItemWriter} bean for persisting validated
     * {@link Transaction} entities to the TRANSACT master file (PostgreSQL
     * {@code transactions} table).
     *
     * <p><strong>COBOL Traceability:</strong> Paragraph
     * {@code 2900-WRITE-TRANSACTION-FILE} (CBTRN02C.cbl lines 645-660):</p>
     * <pre>
     *   WRITE FD-TRANFILE-REC FROM TRAN-RECORD
     *   IF TRANFILE-STATUS NOT = '00'
     *     PERFORM 9910-DISPLAY-IO-STATUS
     *     PERFORM 9999-ABEND-PROGRAM
     *   END-IF
     * </pre>
     *
     * <p>Uses {@link TransactionRepository#saveAll(Iterable)} for batch-
     * efficient persistence of all validated transactions within a single
     * chunk. The complementary operations — TCATBAL update (paragraph 2700)
     * and account balance update (paragraph 2800) — are performed by
     * {@link DailyPostingService#updateTcatbal(DailyTransaction,
     * com.cardemo.entity.Account)} and
     * {@link DailyPostingService#updateAccountRecord(com.cardemo.entity.Account,
     * DailyTransaction)} during the processor phase, ensuring all three
     * database updates are committed atomically within the same chunk
     * transaction.</p>
     *
     * @return the configured ItemWriter for Transaction entities
     */
    @Bean("dailyPostingTransactionWriter")
    public ItemWriter<Transaction> transactionWriter() {
        return chunk -> {
            if (!chunk.isEmpty()) {
                transactionRepository.saveAll(chunk.getItems());
                logger.debug("Chunk: {} transaction(s) written to TRANSACT file",
                        chunk.size());
            }
        };
    }

    // ========================================================================
    // Step Execution Listener — Post-Processing Summary and Exit Status
    // ========================================================================

    /**
     * Creates a {@link StepExecutionListener} that logs processing summary
     * counts and sets the step exit status based on reject presence.
     *
     * <p><strong>COBOL Traceability:</strong> CBTRN02C.cbl lines 214-232:</p>
     * <pre>
     *   DISPLAY 'TRANSACTIONS PROCESSED = ' WS-TRANSACTION-COUNT
     *   DISPLAY 'TRANSACTIONS REJECTED  = ' WS-REJECT-COUNT
     *   IF WS-REJECT-COUNT &gt; 0
     *     MOVE 4 TO RETURN-CODE
     *   END-IF
     * </pre>
     *
     * <p>The COBOL RETURN-CODE of 4 is translated to a custom
     * {@link ExitStatus} of {@value #EXIT_STATUS_COMPLETED_WITH_REJECTS},
     * allowing downstream job orchestration to detect and handle the
     * partial-success condition.</p>
     *
     * <p>This listener also performs post-step verification by querying
     * the {@link AccountRepository} and {@link CategoryBalanceRepository}
     * to log summary statistics, ensuring data integrity after the
     * posting run.</p>
     *
     * @return the configured StepExecutionListener
     */
    private StepExecutionListener createPostingStepListener() {
        return new StepExecutionListener() {

            @Override
            public void beforeStep(StepExecution stepExecution) {
                // COBOL CBTRN02C.cbl line 194:
                // DISPLAY 'START OF DAILY TRANSACTION POSTING'
                logger.info("Daily posting step starting — processing DALYTRAN records");
            }

            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                // COBOL CBTRN02C.cbl lines 214-232:
                // DISPLAY summary counts and set RETURN-CODE
                int transactionCount = dailyPostingService.getTransactionCount();
                int rejectCount = dailyPostingService.getRejectCount();

                logger.info("TRANSACTIONS PROCESSED = {}", transactionCount);
                logger.info("TRANSACTIONS REJECTED  = {}", rejectCount);

                // Post-step verification: log data integrity summary
                // Uses AccountRepository and CategoryBalanceRepository for
                // verification of paragraph 2700/2800 updates
                long totalAccounts = accountRepository.count();
                long totalCategoryBalances = categoryBalanceRepository.count();
                logger.info("Post-step verification: {} account records, "
                        + "{} category balance records in database",
                        totalAccounts, totalCategoryBalances);

                // COBOL: IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE
                if (rejectCount > 0) {
                    logger.warn("Rejects detected — exit status set to {} "
                            + "(equivalent to COBOL RETURN-CODE=4)",
                            EXIT_STATUS_COMPLETED_WITH_REJECTS);
                    return new ExitStatus(EXIT_STATUS_COMPLETED_WITH_REJECTS);
                }

                // COBOL: default RETURN-CODE = 0 (success, no rejects)
                logger.info("END OF DAILY TRANSACTION POSTING — all records posted successfully");
                return ExitStatus.COMPLETED;
            }
        };
    }
}
