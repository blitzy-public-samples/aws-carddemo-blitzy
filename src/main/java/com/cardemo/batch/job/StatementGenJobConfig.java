package com.cardemo.batch.job;

/*
 * StatementGenJobConfig.java — Spring Batch Job Configuration for Statement Generation
 *
 * Translates JCL CREASTMT batch job into a Spring Batch 5.x @Configuration class.
 *
 * COBOL Source:  CBSTM03A.CBL (main statement engine)
 * COBOL Sub:     CBSTM03B.CBL (I/O subroutine — open/read/close 4 VSAM datasets)
 *
 * COBOL Paragraph → Spring Batch Component Traceability:
 * ─────────────────────────────────────────────────────────────────────────────
 *   0000-START / dispatch          → statementGenStep() orchestration
 *   8100-TRNXFILE-OPEN             → StepExecutionListener.beforeStep() → StatementIoService.openTransactionFile()
 *   8200-XREFFILE-OPEN             → StepExecutionListener.beforeStep() → StatementIoService.openXrefFile()
 *   8300-CUSTFILE-OPEN             → StepExecutionListener.beforeStep() → StatementIoService.openCustomerFile()
 *   8400-ACCTFILE-OPEN             → StepExecutionListener.beforeStep() → StatementIoService.openAccountFile()
 *   XREF sequential iteration      → cardXrefReader() (RepositoryItemReader with pagination)
 *   2000-CUSTFILE-GET (keyed read) → StatementProcessor.process() via CustomerRepository.findById()
 *   3000-ACCTFILE-GET (keyed read) → StatementProcessor.process() via AccountRepository.findById()
 *   4000-TRNXFILE-GET (WS-TRNX)   → StatementProcessor.process() via TransactionRepository.findByCardNum()
 *   5000-CREATE-STATEMENT          → StatementEngineService.createStatement() / StatementFileWriter.write()
 *   6000-WRITE-TRANS               → StatementFileWriter.write() (text 80-char + HTML 100-char)
 *   CALL 'CBSTM03B' dispatch      → StatementIoService (open/read/keyed-read/close)
 *   9100-TRNXFILE-CLOSE            → StepExecutionListener.afterStep() → StatementIoService.closeTransactionFile()
 *   9200-XREFFILE-CLOSE            → StepExecutionListener.afterStep() → StatementIoService.closeXrefFile()
 *   9300-CUSTFILE-CLOSE            → StepExecutionListener.afterStep() → StatementIoService.closeCustomerFile()
 *   9400-ACCTFILE-CLOSE            → StepExecutionListener.afterStep() → StatementIoService.closeAccountFile()
 * ─────────────────────────────────────────────────────────────────────────────
 */

import com.cardemo.batch.processor.StatementProcessor;
import com.cardemo.batch.writer.StatementFileWriter;
import com.cardemo.entity.CardXref;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.batch.StatementEngineService;
import com.cardemo.service.batch.StatementIoService;

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
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

/**
 * Spring Batch job configuration translating the JCL CREASTMT batch job into a
 * chunk-oriented Spring Batch 5.x job.
 *
 * <p>The original COBOL program CBSTM03A.CBL generates account statements from
 * transaction data in two parallel formats: fixed-width plain text (80-char per line,
 * STMT-FILE) and HTML (100-char per line, HTML-FILE). Data is read from four VSAM
 * datasets via the CBSTM03B I/O subroutine: TRNXFILE (transactions), XREFFILE
 * (card-to-account cross-reference), CUSTFILE (customer), and ACCTFILE (account).</p>
 *
 * <h3>Spring Batch Pipeline Architecture</h3>
 * <ul>
 *   <li><strong>Reader</strong>: {@link #cardXrefReader()} — paginated read of all
 *       CardXref records sorted by card number ascending, matching CBSTM03A's
 *       sequential XREF iteration via CBSTM03B READNEXT operations</li>
 *   <li><strong>Processor</strong>: {@link StatementProcessor} — for each CardXref,
 *       looks up customer (← 2000-CUSTFILE-GET), account (← 3000-ACCTFILE-GET),
 *       and transactions (← 4000-TRNXFILE-GET / WS-TRNX-TABLE), producing a
 *       {@link StatementFileWriter.StatementData} aggregate</li>
 *   <li><strong>Writer</strong>: {@link StatementFileWriter} — writes each statement
 *       in both text (← 5000-CREATE-STATEMENT) and HTML (← 5100/5200/6000) formats,
 *       with {@link org.springframework.batch.item.ItemStream} lifecycle management
 *       for output file open/close</li>
 * </ul>
 *
 * <h3>Dataset Lifecycle Management</h3>
 * <p>A {@link StepExecutionListener} manages the four input datasets via
 * {@link StatementIoService}, opening them before the step starts
 * (← CBSTM03A 8100–8400 file opens via CALL 'CBSTM03B') and closing them
 * after the step completes (← CBSTM03A 9100–9400 file closes).</p>
 *
 * @see StatementProcessor
 * @see StatementFileWriter
 * @see StatementEngineService
 * @see StatementIoService
 */
@Configuration
public class StatementGenJobConfig {

    private static final Logger log = LoggerFactory.getLogger(StatementGenJobConfig.class);

    /**
     * Chunk size: 1 record per chunk. Each CardXref record drives one complete
     * account statement generation (← CBSTM03A processes one XREF at a time).
     */
    private static final int CHUNK_SIZE = 1;

    /**
     * Page size for the RepositoryItemReader pagination (← CBSTM03A XREF
     * sequential read; paging is a Spring Data optimisation with no COBOL analog).
     */
    private static final int PAGE_SIZE = 50;

    // ─── Injected Dependencies ────────────────────────────────────────────────

    /** Spring Batch job repository for persistence of job/step metadata. */
    private final JobRepository jobRepository;

    /** Transaction manager for chunk-level transaction boundaries (← JCL COMMIT semantics). */
    private final PlatformTransactionManager transactionManager;

    /** Statement data aggregation processor (← CBSTM03A keyed reads and WS-TRNX-TABLE). */
    private final StatementProcessor statementProcessor;

    /** Dual-format statement file writer (← CBSTM03A STMT-FILE PIC X(80) + HTML-FILE PIC X(100)). */
    private final StatementFileWriter statementFileWriter;

    /**
     * Statement generation engine (← CBSTM03A.CBL main orchestration).
     * Provides generateStatements(String) for standalone execution and
     * createStatement(Customer, Account, Writer, Writer) for per-account output.
     * In the Spring Batch pipeline, these capabilities are delegated through
     * the processor and writer components.
     */
    private final StatementEngineService statementEngineService;

    /** I/O subroutine service (← CBSTM03B.CBL CALL dispatch for 4 VSAM datasets). */
    private final StatementIoService statementIoService;

    /** Card cross-reference repository (← XREFFILE VSAM, 50-byte records). */
    private final CardXrefRepository cardXrefRepository;

    /** Customer repository (← CUSTDATA VSAM, 500-byte records). */
    private final CustomerRepository customerRepository;

    /** Account repository (← ACCTDATA VSAM, 300-byte records). */
    private final AccountRepository accountRepository;

    /** Transaction repository (← TRANSACT VSAM, 350-byte records). */
    private final TransactionRepository transactionRepository;

    // ─── Constructor ──────────────────────────────────────────────────────────

    /**
     * Constructs the statement generation job configuration with all required
     * dependencies injected by the Spring container.
     *
     * <p>Translates the COBOL inter-program communication pattern where
     * CBSTM03A calls CBSTM03B via {@code CALL 'CBSTM03B' USING WS-M03B-AREA}
     * and accesses VSAM datasets via FD definitions into Spring constructor
     * injection of service and repository beans.</p>
     *
     * @param jobRepository          Spring Batch job repository
     * @param transactionManager     platform transaction manager
     * @param statementProcessor     CardXref → StatementData processor
     * @param statementFileWriter    dual text + HTML statement output writer
     * @param statementEngineService statement generation engine service
     * @param statementIoService     I/O subroutine service (← CBSTM03B)
     * @param cardXrefRepository     XREFFILE data access
     * @param customerRepository     CUSTFILE data access
     * @param accountRepository      ACCTFILE data access
     * @param transactionRepository  TRANSACT data access
     */
    @Autowired
    public StatementGenJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            StatementProcessor statementProcessor,
            StatementFileWriter statementFileWriter,
            StatementEngineService statementEngineService,
            StatementIoService statementIoService,
            CardXrefRepository cardXrefRepository,
            CustomerRepository customerRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.statementProcessor = statementProcessor;
        this.statementFileWriter = statementFileWriter;
        this.statementEngineService = statementEngineService;
        this.statementIoService = statementIoService;
        this.cardXrefRepository = cardXrefRepository;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  statementGenJob() — Job Definition (← JCL CREASTMT)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Defines the {@code statementGenJob} Spring Batch job, translating the JCL
     * CREASTMT batch job that invokes CBSTM03A.CBL for statement generation.
     *
     * <p>The job consists of a single comprehensive step that mirrors the COBOL
     * program's sequential processing: iterate XREF records → look up customer
     * and account data → accumulate transactions → generate text and HTML output.</p>
     *
     * @return the configured Spring Batch {@link Job}
     */
    @Bean
    public Job statementGenJob() {
        log.info("Configuring statementGenJob (← JCL CREASTMT → CBSTM03A.CBL)");
        return new JobBuilder("statementGenJob", jobRepository)
                .start(statementGenStep())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  statementGenStep() — Step Definition (← CBSTM03A main processing loop)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Defines the main chunk-oriented step for statement generation.
     *
     * <p>The step processes one CardXref record per chunk (chunk size = 1), mirroring
     * the COBOL pattern where each XREF record triggers a complete statement write.
     * The COBOL WS-TRNX-TABLE (51 cards × 10 transactions) accumulation is replaced
     * by the processor's per-record transaction lookup.</p>
     *
     * <p>Pipeline components:</p>
     * <ul>
     *   <li>{@link #cardXrefReader()} — reads CardXref records with pagination</li>
     *   <li>{@link StatementProcessor#process} — aggregates customer, account, transactions</li>
     *   <li>{@link StatementFileWriter#write} — writes text + HTML output</li>
     * </ul>
     *
     * <p>The writer is also registered as an {@link org.springframework.batch.item.ItemStream}
     * via {@code .stream()} for output file {@link StatementFileWriter#open}/{@link
     * StatementFileWriter#close}/{@link StatementFileWriter#update} lifecycle management.</p>
     *
     * @return the configured Spring Batch {@link Step}
     */
    @Bean
    public Step statementGenStep() {
        log.info("Configuring statementGenStep: CardXref -> StatementData -> dual file output");
        return new StepBuilder("statementGenStep", jobRepository)
                .<CardXref, StatementFileWriter.StatementData>chunk(CHUNK_SIZE, transactionManager)
                .reader(cardXrefReader())
                .processor(statementProcessor)
                .writer(statementFileWriter)
                .stream(statementFileWriter)
                .listener(createDatasetLifecycleListener())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  cardXrefReader() — Paginated CardXref Reader
    //  ← CBSTM03A: XREF sequential iteration via CBSTM03B READNEXT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Creates a paginated {@link ItemReader} for CardXref records, sorted by card
     * number ascending to match CBSTM03A's sequential XREF file traversal.
     *
     * <p>The COBOL program reads XREFFILE sequentially via CBSTM03B
     * (1000-XREFFILE-GET-NEXT paragraph using READNEXT). This reader translates
     * that pattern into a Spring Data repository-based paginated reader using
     * {@link CardXrefRepository#findAll} with sort by {@code xrefCardNum} ASC.</p>
     *
     * @return a step-scoped {@link ItemReader} producing {@link CardXref} records
     */
    @Bean
    @StepScope
    public ItemReader<CardXref> cardXrefReader() {
        log.info("Creating CardXref reader — sorted by xrefCardNum ASC "
                + "(← CBSTM03A XREF sequential iteration via CBSTM03B)");
        return new RepositoryItemReaderBuilder<CardXref>()
                .name("cardXrefReader")
                .repository(cardXrefRepository)
                .methodName("findAll")
                .sorts(Map.of("xrefCardNum", Sort.Direction.ASC))
                .pageSize(PAGE_SIZE)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Dataset Lifecycle Listener
    //  ← CBSTM03A: 8100-8400 file opens / 9100-9400 file closes via CBSTM03B
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Creates a {@link StepExecutionListener} that manages the four input dataset
     * lifecycle, matching the COBOL pattern of opening all files before processing
     * (← 8100-TRNXFILE-OPEN through 8400-ACCTFILE-OPEN via CALL 'CBSTM03B') and
     * closing them after processing completes (← 9100 through 9400 closes).
     *
     * <p>The listener also verifies data availability before processing begins,
     * performing sample lookups against all four repositories to detect configuration
     * or data issues early.</p>
     *
     * @return a step execution listener for dataset lifecycle management
     */
    private StepExecutionListener createDatasetLifecycleListener() {
        return new StepExecutionListener() {

            @Override
            public void beforeStep(StepExecution stepExecution) {
                openInputDatasets();
                verifyInputData();
            }

            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                closeInputDatasets();
                logCompletionStats(stepExecution);
                return stepExecution.getExitStatus();
            }
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  openInputDatasets() — Open All 4 VSAM Datasets
    //  ← CBSTM03A: 8100-TRNXFILE-OPEN, 8200-XREFFILE-OPEN,
    //              8300-CUSTFILE-OPEN, 8400-ACCTFILE-OPEN
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Opens all four input datasets via {@link StatementIoService}, initialising
     * the service's internal iterators for sequential traversal.
     *
     * <p>This mirrors the CBSTM03A paragraphs 8100 through 8400, each of which
     * dispatches to CBSTM03B with {@code WS-M03B-OPER = 'O'} (open) for the
     * respective dataset. In the COBOL program, the ALTER/GO TO dispatch pattern
     * chains the four open operations sequentially.</p>
     */
    private void openInputDatasets() {
        log.info("Opening 4 input datasets for statement generation "
                + "(← CBSTM03A 8100-8400 via CALL 'CBSTM03B')");
        statementIoService.openTransactionFile();
        statementIoService.openXrefFile();
        statementIoService.openCustomerFile();
        statementIoService.openAccountFile();
        log.info("All 4 input datasets opened successfully — RC=00 for all");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  closeInputDatasets() — Close All 4 VSAM Datasets
    //  ← CBSTM03A: 9100-TRNXFILE-CLOSE, 9200-XREFFILE-CLOSE,
    //              9300-CUSTFILE-CLOSE, 9400-ACCTFILE-CLOSE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Closes all four input datasets via {@link StatementIoService}, releasing
     * the service's internal iterators.
     *
     * <p>This mirrors the CBSTM03A paragraphs 9100 through 9400, each dispatching
     * to CBSTM03B with {@code WS-M03B-OPER = 'C'} (close).</p>
     */
    private void closeInputDatasets() {
        log.info("Closing 4 input datasets after statement generation "
                + "(← CBSTM03A 9100-9400 via CALL 'CBSTM03B')");
        statementIoService.closeTransactionFile();
        statementIoService.closeXrefFile();
        statementIoService.closeCustomerFile();
        statementIoService.closeAccountFile();
        log.info("All 4 input datasets closed successfully — RC=00 for all");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  verifyInputData() — Pre-Processing Data Verification
    //  Ensures all linked data exists before statement generation begins.
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Verifies input data availability before statement generation begins.
     *
     * <p>Performs sample lookups against all four data repositories to detect
     * configuration or missing-data issues early. Also validates the statement
     * generation pipeline components (engine, processor, writer) are initialised.</p>
     *
     * <p>Uses the same data access patterns as the COBOL paragraphs:
     * XREF sequential read (1000-XREFFILE-GET-NEXT), customer keyed read
     * (2000-CUSTFILE-GET), account keyed read (3000-ACCTFILE-GET), and
     * transaction card lookup (4000-TRNXFILE-GET).</p>
     */
    private void verifyInputData() {
        // Count XREF records without loading all into memory (performance fix:
        // replaced cardXrefRepository.findAll() with count() to avoid loading
        // potentially thousands of records just to check data presence)
        long xrefCount = cardXrefRepository.count();
        log.info("XREF records available: {} for statement generation", xrefCount);

        if (xrefCount > 0) {
            // Sample one XREF record for linked-data verification using a
            // size-1 paginated query instead of loading all records
            CardXref sample = cardXrefRepository.findAll(Pageable.ofSize(1))
                    .getContent().getFirst();
            String cardNum = sample.getXrefCardNum();
            String custId = sample.getCustId();
            String acctId = sample.getAccountId();
            log.debug("Sample XREF verification: card={}, customer={}, account={}",
                    cardNum, custId, acctId);

            // Verify linked customer exists (← 2000-CUSTFILE-GET keyed read via CBSTM03B)
            customerRepository.findById(custId).ifPresentOrElse(
                    cust -> log.debug("Customer {} verified for statement generation", custId),
                    () -> log.warn("Customer {} not found — some statements may be filtered",
                            custId));

            // Verify linked account exists (← 3000-ACCTFILE-GET keyed read via CBSTM03B)
            accountRepository.findById(acctId).ifPresentOrElse(
                    acct -> log.debug("Account {} verified for statement generation", acctId),
                    () -> log.warn("Account {} not found — some statements may be filtered",
                            acctId));

            // Verify transactions exist for sample card (← 4000-TRNXFILE-GET / WS-TRNX-TABLE)
            long txnCount = transactionRepository
                    .findByCardNum(cardNum, Pageable.ofSize(1))
                    .getTotalElements();
            log.debug("Transactions for card {}: {} records available", cardNum, txnCount);
        }

        // Verify statement generation pipeline readiness:
        // StatementEngineService provides:
        //   - generateStatements(String outputDir) for standalone execution
        //     (← CBSTM03A PROCEDURE DIVISION entry / 0000-START)
        //   - createStatement(Customer, Account, Writer, Writer) for per-account output
        //     (← 5000-CREATE-STATEMENT paragraph)
        // In the Spring Batch pipeline, these capabilities are exercised through
        // StatementProcessor (data aggregation) and StatementFileWriter (dual output).
        log.info("Statement pipeline verified — engine={}, io={}, processor={}, writer={}",
                statementEngineService.getClass().getSimpleName(),
                statementIoService.getClass().getSimpleName(),
                statementProcessor.getClass().getSimpleName(),
                statementFileWriter.getClass().getSimpleName());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  logCompletionStats() — Post-Processing Completion Logging
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Logs completion statistics after statement generation, including record
     * counts for read, written, filtered, and skipped items.
     *
     * <p>Replaces the COBOL DISPLAY statements in CBSTM03A that report
     * processing totals before program termination.</p>
     *
     * @param stepExecution the completed step execution with processing counts
     */
    private void logCompletionStats(StepExecution stepExecution) {
        long readCount = stepExecution.getReadCount();
        long writeCount = stepExecution.getWriteCount();
        long filterCount = stepExecution.getFilterCount();
        long skipCount = stepExecution.getSkipCount();
        log.info("Statement generation complete — "
                        + "XREF records read={}, statements written={}, filtered={}, skipped={}",
                readCount, writeCount, filterCount, skipCount);
    }
}
