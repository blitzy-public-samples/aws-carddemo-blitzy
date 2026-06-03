package com.carddemo.batch;

import com.carddemo.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.CardNumberMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spring Batch configuration for the <strong>{@code transactionReadJob}</strong> — the
 * Java/PostgreSQL replacement for the <em>diagnostic sequential-dump</em> aspect of the legacy
 * batch COBOL program {@code app/cbl/CBTRN03C.cbl}.
 *
 * <h2>Legacy mainframe behavior (CBTRN03C.cbl)</h2>
 * The original COBOL {@code CBTRN03C} is multi-purpose. Its {@code PROCEDURE DIVISION} opens the
 * sequential {@code TRANSACT-FILE} (a KSDS read as {@code ORGANIZATION IS SEQUENTIAL}, laid out by
 * {@code app/cpy/CVTRA05Y.cpy} {@code TRAN-RECORD}) alongside the indexed {@code CARDXREF},
 * {@code TRANTYPE} and {@code TRANCATG} VSAM datasets and a {@code DATEPARM} control file, then
 * loops {@code PERFORM UNTIL END-OF-FILE = 'Y'}. Each iteration reads the next transaction record
 * ({@code 1000-TRANFILE-GET-NEXT}: {@code READ TRANSACT-FILE INTO TRAN-RECORD}; an end-of-file
 * file-status {@code '10'} sets {@code END-OF-FILE = 'Y'}) and {@code DISPLAY TRAN-RECORD}s it. The
 * program then performs <em>two distinct duties</em>:
 * <ol>
 *   <li>a <strong>diagnostic dump</strong> — the {@code DISPLAY TRAN-RECORD} of every record, used
 *       by an operator to verify the contents of the {@code TRANSACT} file (for example after the
 *       {@code POSTTRAN} job); and</li>
 *   <li>a <strong>detail report</strong> — a date-range-filtered, card-grouped report with page
 *       totals, per-account (per-card) subtotals and a grand total
 *       ({@code 1100-WRITE-TRANSACTION-REPORT}, {@code 1110-WRITE-PAGE-TOTALS},
 *       {@code 1120-WRITE-ACCOUNT-TOTALS}, {@code 1110-WRITE-GRAND-TOTALS}), emitted to the
 *       {@code TRANREPT} report file.</li>
 * </ol>
 *
 * <h2>Why this job covers only the diagnostic dump</h2>
 * Per the AAP transformation plan, the single COBOL {@code CBTRN03C} program is split into two
 * cleanly separated Spring Batch jobs:
 * <ul>
 *   <li><strong>{@code TransactionReadJobConfig}</strong> (this class) — the pure diagnostic
 *       sequential dump: page through the {@code transactions} table and log each row, with
 *       <em>no</em> date filter, <em>no</em> sorting by card, and <em>no</em> subtotals. This is
 *       the CBACT01C-style "read &amp; display the whole file" pattern.</li>
 *   <li>{@code TransactionReportJobConfig} — the date-range-filtered detail report sorted by card
 *       number with per-card subtotals and a grand total, written to a formatted text file
 *       (derived from {@code app/jcl/TRANREPT.jcl} + {@code app/proc/TRANREPT.prc}).</li>
 * </ul>
 * Keeping the dump read-only and unfiltered mirrors the {@code DISPLAY TRAN-RECORD} loop faithfully
 * while leaving the report formatting to the dedicated report job; the two jobs remain
 * independently launchable.
 *
 * <h2>Modernized Spring Batch behavior — diagnostic reader</h2>
 * This job is a single chunk-oriented {@link Step} that pages over the {@code transactions} table
 * (the relational system-of-record replacing the VSAM {@code TRANSACT} KSDS) and logs every row —
 * the faithful Spring equivalent of the COBOL {@code DISPLAY TRAN-RECORD} loop. It never writes to
 * the database (PR-25 single monolith, read-only diagnostic).
 *
 * <h2>Step shape</h2>
 * <pre>
 *   RepositoryItemReader&lt;Transaction&gt;  (pages the transactions table via findAll, sorted by tranId ASC)
 *        -&gt; ItemProcessor (logs each row; identity pass-through)
 *        -&gt; ItemWriter   (no-op; logs the chunk size only)
 * </pre>
 * The reader sorts by the natural primary key {@code tranId} (the {@code tran_id} column,
 * originally COBOL {@code TRAN-ID PIC X(16)}) ascending. Because transaction IDs are zero-padded
 * fixed-width 16-character strings ({@code parmDate(10) + suffix(6)}, PR-10), lexicographic
 * ascending order reproduces a stable, deterministic sequence. A deterministic, non-empty sort is
 * also a hard requirement of the paging {@link RepositoryItemReader}.
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><strong>PR-11</strong> (DB2 timestamp format): {@code origTimestamp} / {@code procTimestamp}
 *       are the normalized {@link java.time.LocalDateTime} form of the 26-character DB2 external
 *       timestamp {@code YYYY-MM-DD-HH.MM.SS.MIL0000}; they are rendered here only for diagnostic
 *       logging (a read-only I/O boundary).</li>
 *   <li><strong>PR-25</strong> (single monolith): this batch job runs inside the one Spring Boot
 *       context — no microservice, no external scheduler, no message queue.</li>
 *   <li><strong>PR-28</strong> (Jakarta / Spring 6 baseline): only Spring Framework 6.1 / Spring
 *       Batch 5.1 APIs are used; no {@code javax.*} types.</li>
 *   <li><strong>PR-29</strong> (constructor injection only): the three collaborators are
 *       {@code final} and injected through the Lombok {@code @RequiredArgsConstructor}-generated
 *       constructor — no field injection, no {@code @Autowired}.</li>
 * </ul>
 *
 * <p><strong>Why {@code @EnableBatchProcessing} is intentionally absent:</strong> under Spring
 * Boot 3.x the {@code spring-boot-starter-batch} auto-configuration supplies the
 * {@link JobRepository}, {@code JobLauncher}, {@code JobRegistry} and {@code JobExplorer}; adding
 * {@code @EnableBatchProcessing} would switch off that auto-configuration (see {@code BatchConfig}).
 * Both the {@code @Bean Job} and {@code @Bean Step} defined here are auto-registered with the
 * {@code JobRegistry}, so {@code BatchAdminController} ({@code POST /api/admin/jobs/{jobName}/launch})
 * can launch this job by its {@link #JOB_NAME}.</p>
 *
 * <p><strong>Entity accessor note.</strong> The logging processor reads the committed
 * {@link Transaction} entity getters: {@code getTranId()} / {@code getCardNum()} /
 * {@code getTypeCd()} return {@link String}, {@code getCategoryCd()} returns {@link String} (the
 * fixed-width {@code CHAR(4)} category code, preserving leading zeros), {@code getAmount()} returns
 * {@link java.math.BigDecimal} (the {@code NUMERIC(15,2)} money field, PR-16), and
 * {@code getOrigTimestamp()} / {@code getProcTimestamp()} return {@link java.time.LocalDateTime}.
 * The diagnostic log labels ({@code typeCd}, {@code catCd}, {@code amt}, {@code origTs},
 * {@code procTs}) mirror the COBOL {@code TRAN-*} field names for operator traceability.</p>
 *
 * <p><strong>Scaling note.</strong> For very large transaction volumes a
 * {@code JdbcCursorItemReader} streaming a forward-only cursor would avoid materializing paged
 * result sets; the {@link RepositoryItemReader} paging approach is used here for consistency with
 * the sibling diagnostic readers and is more than adequate for the demonstration-grade data volume
 * (a few hundred transactions).</p>
 *
 * @see com.carddemo.entity.Transaction
 * @see com.carddemo.repository.TransactionRepository
 * @see DailyTransactionReadJobConfig
 * @see org.springframework.batch.item.data.RepositoryItemReader
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class TransactionReadJobConfig {

    /**
     * Logical name of the diagnostic {@link Job} bean. Used by the Spring Batch {@code JobRegistry}
     * and by {@code BatchAdminController} ({@code POST /api/admin/jobs/{jobName}/launch}) to launch
     * this job by name for operator verification of the {@code transactions} table — for example
     * after the {@code POSTTRAN} ({@code CBTRN02C}) posting run.
     */
    public static final String JOB_NAME = "transactionReadJob";

    /** Logical name of the single chunk-oriented diagnostic {@link Step}. */
    private static final String STEP_NAME = "transactionReadStep";

    /**
     * {@link RepositoryItemReader} name — the key prefix under which the reader saves and restores
     * its paging state in the step {@code ExecutionContext}. Must be unique within the step.
     */
    private static final String READER_NAME = "transactionReader";

    /**
     * Chunk size, doubling as the reader page size. A value of {@value} matches the codebase-wide
     * default chunk size (AAP &sect;0.3.3 #7) and balances log granularity against database
     * round-trips for the demonstration-grade data volume.
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * Entity property the reader sorts on (mapped to the {@code tran_id} primary-key column).
     * Sorting by the zero-padded 16-character {@code tranId} ascending yields a stable,
     * deterministic ordering — both a faithful sequential dump order and a hard requirement of the
     * paging {@link RepositoryItemReader} (its sort map must be non-empty).
     */
    private static final String SORT_PROPERTY = "tranId";

    /** Spring Batch metadata repository (auto-configured by Spring Boot 3.x). */
    private final JobRepository jobRepository;

    /**
     * Transaction manager bracketing each chunk's unit of work — the {@code SYNCPOINT}-equivalent
     * boundary for the read step (PR-24, AAP &sect;0.6.12). Even though this diagnostic step performs
     * no writes, Spring Batch requires a transaction manager to delimit chunk processing.
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * Spring Data JPA repository over the {@code transactions} table. Supplied to the
     * {@link RepositoryItemReaderBuilder} to provide a paged full-table read via its inherited
     * {@code findAll(Pageable)} method ({@code TransactionRepository} extends {@code JpaRepository},
     * hence {@code PagingAndSortingRepository}).
     */
    private final TransactionRepository transactionRepository;

    /**
     * Paging reader over the {@code transactions} table.
     *
     * <p>Built on {@link RepositoryItemReader}, which invokes
     * {@code transactionRepository.findAll(Pageable)} one page at a time. The configured
     * {@code methodName} {@code "findAll"} combined with the {@code PageRequest} the reader passes
     * resolves unambiguously to {@code PagingAndSortingRepository.findAll(Pageable)} (a
     * {@code PageRequest} is a {@code Pageable}, not a {@code Sort}). A non-empty sort on
     * {@link #SORT_PROPERTY} ascending is mandatory for the paging reader and yields the
     * deterministic dump ordering; the page size is set to {@link #CHUNK_SIZE} so each page aligns
     * with one processing chunk.</p>
     *
     * @return a configured {@link RepositoryItemReader} streaming the {@code transactions} table in
     *         ascending {@code tranId} order
     */
    @Bean
    public RepositoryItemReader<Transaction> transactionReader() {
        return new RepositoryItemReaderBuilder<Transaction>()
                .name(READER_NAME)
                .repository(transactionRepository)
                .methodName("findAll")
                .sorts(Collections.singletonMap(SORT_PROPERTY, Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    /**
     * Pass-through {@link ItemProcessor} that logs each transaction row — the Spring equivalent of
     * the COBOL {@code DISPLAY TRAN-RECORD} diagnostic.
     *
     * <p>A per-bean {@link AtomicLong} captured by the returned lambda emits a monotonically
     * increasing record number ({@code [#n]}) so an operator can correlate the log against the
     * table row count. The item is returned unchanged so the chunk flows on to the no-op writer.
     * Accessor calls use the actual committed {@link Transaction} getters — {@code getCategoryCd()}
     * for the {@code CHAR(4)} category code and {@code getAmount()} for the {@code BigDecimal}
     * monetary amount — while the log labels ({@code catCd}, {@code amt}, {@code origTs},
     * {@code procTs}) mirror the COBOL {@code TRAN-*} field names for traceability.</p>
     *
     * @return a logging, identity-mapping {@link ItemProcessor}
     */
    @Bean
    public ItemProcessor<Transaction, Transaction> transactionLoggingProcessor() {
        final AtomicLong counter = new AtomicLong(0L);
        return transaction -> {
            long recordNumber = counter.incrementAndGet();
            // SECURITY (QA Issue 4 / PR-20): the diagnostic dump must NEVER emit a full 16-digit PAN.
            // Mask the card number to its last 4 digits (CardNumberMasker.mask) before it can reach
            // any appender; all other TRAN-* fields are non-sensitive and logged verbatim.
            log.info("[CBTRN03C-DIAG] [#{}] tranId={} cardNum={} typeCd={} catCd={} amt={} "
                            + "origTs={} procTs={}",
                    recordNumber,
                    transaction.getTranId(),
                    CardNumberMasker.mask(transaction.getCardNum()),
                    transaction.getTypeCd(),
                    transaction.getCategoryCd(),
                    transaction.getAmount(),
                    transaction.getOrigTimestamp(),
                    transaction.getProcTimestamp());
            return transaction;
        };
    }

    /**
     * No-op {@link ItemWriter} for the diagnostic step. Because this job only verifies table
     * contents (it never persists anything), the writer merely records the number of items in each
     * processed chunk at {@code DEBUG} level. The lambda parameter is a Spring Batch 5
     * {@code Chunk<? extends Transaction>}, whose {@code size()} yields the chunk item count.
     *
     * @return a write-nothing {@link ItemWriter} that logs the chunk size
     */
    @Bean
    public ItemWriter<Transaction> transactionNoOpWriter() {
        return items -> log.debug("Chunk of {} Transaction record(s) read", items.size());
    }

    /**
     * Chunk-oriented diagnostic {@link Step} wiring the reader, logging processor and no-op writer.
     *
     * <p>The {@link #transactionManager} delimits each chunk's transaction boundary (PR-24) and the
     * chunk size is {@link #CHUNK_SIZE}. The step reads {@link Transaction} items and emits
     * {@link Transaction} items (identity processing), mirroring the single sequential pass of the
     * COBOL {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop.</p>
     *
     * @return the {@code transactionReadStep} bean
     */
    @Bean
    public Step transactionReadStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<Transaction, Transaction>chunk(CHUNK_SIZE, transactionManager)
                .reader(transactionReader())
                .processor(transactionLoggingProcessor())
                .writer(transactionNoOpWriter())
                .build();
    }

    /**
     * The diagnostic {@link Job} bean ({@link #JOB_NAME}) consisting of the single
     * {@link #transactionReadStep()}.
     *
     * <p>Auto-registered with the Spring Batch {@code JobRegistry} by Spring Boot
     * auto-configuration, so it is launchable by name through {@code BatchAdminController}. Running
     * the job with the same parameters returns the existing {@code JobExecution} (idempotent);
     * supplying a distinct parameter set creates a new execution.</p>
     *
     * @return the {@code transactionReadJob} bean
     */
    @Bean
    public Job transactionReadJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(transactionReadStep())
                .build();
    }
}
