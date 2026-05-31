package com.carddemo.batch;

import com.carddemo.entity.DailyTransaction;
import com.carddemo.repository.DailyTransactionRepository;
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
 * Spring Batch configuration for the <strong>{@code dailyTransactionReadJob}</strong> — the
 * Java/PostgreSQL replacement for the legacy batch COBOL program {@code app/cbl/CBTRN01C.cbl}
 * ("Post the records from daily transaction file").
 *
 * <h2>Legacy mainframe behavior (CBTRN01C.cbl)</h2>
 * The original COBOL program opens the sequential {@code DALYTRAN-FILE} (a PS file laid out by
 * {@code app/cpy/CVTRA06Y.cpy} {@code DALYTRAN-RECORD}) together with the indexed
 * {@code CUSTFILE}, {@code XREFFILE}, {@code CARDFILE}, {@code ACCTFILE} and {@code TRANFILE}
 * VSAM datasets. Its {@code MAIN-PARA} loops {@code PERFORM UNTIL END-OF-DAILY-TRANS-FILE = 'Y'}:
 * each iteration reads the next daily-transaction record ({@code 1000-DALYTRAN-GET-NEXT}),
 * {@code DISPLAY}s it, then performs a cross-reference lookup by card number
 * ({@code 2000-LOOKUP-XREF}) and, on success, an account read ({@code 3000-READ-ACCOUNT}),
 * emitting diagnostic {@code DISPLAY} lines for every record and every lookup outcome. It does
 * <em>not</em> mutate any file — its sole purpose is to surface (verify) the contents of the
 * daily-transaction feed for an operator.
 *
 * <h2>Modernized Spring Batch behavior — diagnostic reader</h2>
 * Per the AAP transformation plan (&sect;0.4.1.2), the substantive validation/posting logic of the
 * daily feed (validation codes 100/101/102/103, the {@code TCATBAL} upsert, and the sign-based
 * account-balance bucket) is owned by the {@code POSTTRAN} job
 * ({@code TransactionPostingJobConfig} / {@code TransactionPostingProcessor}). This job is
 * therefore kept deliberately as a <strong>read-only diagnostic</strong>: a chunk-oriented step
 * that pages over the {@code daily_transactions} staging table (the relational stand-in for the
 * sequential {@code DALYTRAN} PS file, AAP &sect;0.6.6) and logs each staged row — the faithful
 * Spring equivalent of the COBOL {@code DISPLAY DALYTRAN-RECORD} loop. It is intended for
 * operator verification of staging-table contents and never writes back to the database.
 *
 * <p>The CICS/VSAM verification reads ({@code 2000-LOOKUP-XREF}, {@code 3000-READ-ACCOUNT}) are
 * intentionally omitted here because their business intent is fully realized by the posting
 * pipeline; reproducing them in this diagnostic job would duplicate that logic without adding
 * verification value.</p>
 *
 * <h2>Step shape</h2>
 * <pre>
 *   RepositoryItemReader&lt;DailyTransaction&gt;  (pages daily_transactions via findAll, sorted by dalytranId ASC)
 *        -&gt; ItemProcessor (logs each row, pass-through)
 *        -&gt; ItemWriter   (no-op; logs the chunk size only)
 * </pre>
 * The reader sorts by the business feed identifier {@code dalytranId} (the {@code tran_id}
 * column, originally COBOL {@code DALYTRAN-ID PIC X(16)}) in ascending order. Because the feed
 * identifiers are zero-padded fixed-width strings, lexicographic ascending order reproduces the
 * natural numeric sequence in which {@code CBTRN01C} read the sequential file. A deterministic,
 * non-empty sort is also a hard requirement of the paging {@link RepositoryItemReader}.
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><strong>PR-11</strong> (DB2 timestamp format): {@code origTimestamp} is the normalized
 *       form of the 26-character DB2 external timestamp {@code YYYY-MM-DD-HH.MM.SS.MIL0000}; it
 *       is rendered here only for diagnostic logging (a read-only I/O boundary).</li>
 *   <li><strong>PR-25</strong> (single monolith): this batch job runs inside the one Spring Boot
 *       context — no microservice or external scheduler.</li>
 *   <li><strong>PR-28</strong> (Jakarta / Spring 6 baseline): only Spring Framework 6.1 / Spring
 *       Batch 5.1 APIs are used; no {@code javax.*} types.</li>
 *   <li><strong>PR-29</strong> (constructor injection only): the three collaborators are
 *       {@code final} and injected through the Lombok {@code @RequiredArgsConstructor}-generated
 *       constructor — no field injection.</li>
 * </ul>
 *
 * <p><strong>Why {@code @EnableBatchProcessing} is intentionally absent:</strong> under Spring
 * Boot 3.x the {@code spring-boot-starter-batch} auto-configuration supplies the
 * {@link JobRepository}, {@code JobLauncher}, {@code JobRegistry} and {@code JobExplorer}; adding
 * {@code @EnableBatchProcessing} would disable that auto-configuration (see {@code BatchConfig}).
 * Both {@code @Bean Job} and {@code @Bean Step} defined here are auto-registered with the
 * {@code JobRegistry}, so {@code BatchAdminController} can launch this job by its
 * {@link #JOB_NAME}.</p>
 *
 * <p>Entity field accessors used by the logging processor are taken from the committed
 * {@link DailyTransaction} entity: {@code getDalytranId()} / {@code getCardNum()} /
 * {@code getTypeCd()} return {@code String}, {@code getCategoryCd()} returns {@code String},
 * {@code getAmount()} returns {@code java.math.BigDecimal}, {@code getOrigTimestamp()} returns
 * {@code java.time.LocalDateTime}, and {@code getProcessed()} returns {@code Boolean}.</p>
 *
 * @see com.carddemo.entity.DailyTransaction
 * @see com.carddemo.repository.DailyTransactionRepository
 * @see TransactionPostingJobConfig
 * @see org.springframework.batch.item.data.RepositoryItemReader
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class DailyTransactionReadJobConfig {

    /**
     * Logical name of the diagnostic {@link Job} bean. Used by the {@code JobRegistry} and the
     * {@code BatchAdminController} ({@code POST /api/admin/jobs/{jobName}/launch}) to launch this
     * job by name for operator verification of the {@code daily_transactions} staging table.
     */
    public static final String JOB_NAME = "dailyTransactionReadJob";

    /** Logical name of the single chunk-oriented diagnostic {@link Step}. */
    private static final String STEP_NAME = "dailyTransactionReadStep";

    /**
     * {@link RepositoryItemReader} name — used as the key prefix under which the reader saves and
     * restores its paging state in the step {@code ExecutionContext}. Must be unique within the
     * step.
     */
    private static final String READER_NAME = "dailyTransactionReader";

    /**
     * Chunk size, doubling as the reader page size. A value of {@value} balances log granularity
     * against round-trips for the demonstration-grade data volume; it matches the codebase-wide
     * default chunk size (AAP &sect;0.3.3 #7).
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * Entity property the reader sorts on (mapped to the {@code tran_id} column). Sorting by the
     * zero-padded feed identifier ascending reproduces the sequential read order of the original
     * {@code DALYTRAN} PS file and provides the deterministic ordering the paging reader requires.
     */
    private static final String SORT_PROPERTY = "dalytranId";

    /** Spring Batch metadata repository (auto-configured by Spring Boot 3.x). */
    private final JobRepository jobRepository;

    /**
     * Transaction manager bracketing each chunk's unit of work — the {@code SYNCPOINT}-equivalent
     * boundary for the read step (PR-24). Even though this diagnostic step performs no writes,
     * Spring Batch requires a transaction manager to delimit chunk processing.
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * Spring Data JPA repository over the {@code daily_transactions} staging table. Supplied to the
     * {@link RepositoryItemReaderBuilder} to provide a paged full-table read via its inherited
     * {@code findAll(Pageable)} method.
     */
    private final DailyTransactionRepository dailyTransactionRepository;

    /**
     * Paging reader over the {@code daily_transactions} staging table.
     *
     * <p>Built on {@link RepositoryItemReader}, which invokes
     * {@code dailyTransactionRepository.findAll(Pageable)} ({@code DailyTransactionRepository}
     * extends {@code JpaRepository}, hence {@code PagingAndSortingRepository}) one page at a time.
     * A non-empty sort on {@link #SORT_PROPERTY} ascending is mandatory for the paging reader and
     * yields the deterministic, COBOL-equivalent sequential ordering; the page size is set to
     * {@link #CHUNK_SIZE} so each page aligns with one processing chunk.</p>
     *
     * @return a configured {@link RepositoryItemReader} streaming the staging table in ascending
     *         {@code dalytranId} order
     */
    @Bean
    public RepositoryItemReader<DailyTransaction> dailyTransactionReader() {
        return new RepositoryItemReaderBuilder<DailyTransaction>()
                .name(READER_NAME)
                .repository(dailyTransactionRepository)
                .methodName("findAll")
                .sorts(Collections.singletonMap(SORT_PROPERTY, Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    /**
     * Pass-through {@link ItemProcessor} that logs each staged daily-transaction row — the Spring
     * equivalent of the COBOL {@code DISPLAY DALYTRAN-RECORD} diagnostic.
     *
     * <p>A per-bean {@link AtomicLong} captured by the returned lambda emits a monotonically
     * increasing record number ({@code [#n]}) so operators can correlate the log against the
     * staging-table row count. The item is returned unchanged so the chunk flows on to the no-op
     * writer. Field labels mirror the COBOL {@code DALYTRAN-*} names for traceability, while the
     * accessor calls use the actual committed {@link DailyTransaction} getters
     * ({@code getCategoryCd()} for the category code and {@code getAmount()} for the monetary
     * amount).</p>
     *
     * @return a logging, identity-mapping {@link ItemProcessor}
     */
    @Bean
    public ItemProcessor<DailyTransaction, DailyTransaction> dailyTransactionLoggingProcessor() {
        final AtomicLong counter = new AtomicLong(0L);
        return dt -> {
            long recordNumber = counter.incrementAndGet();
            log.info("[CBTRN01C-DIAG] [#{}] dalytranId={} cardNum={} typeCd={} catCd={} amt={} "
                            + "origTs={} processed={}",
                    recordNumber,
                    dt.getDalytranId(),
                    dt.getCardNum(),
                    dt.getTypeCd(),
                    dt.getCategoryCd(),
                    dt.getAmount(),
                    dt.getOrigTimestamp(),
                    dt.getProcessed());
            return dt;
        };
    }

    /**
     * No-op {@link ItemWriter} for the diagnostic step. Because this job only verifies staging-table
     * contents (it never persists anything), the writer merely records the number of items in each
     * processed chunk at {@code DEBUG} level. The lambda parameter is a Spring Batch 5
     * {@code Chunk<? extends DailyTransaction>}, whose {@code size()} yields the chunk item count.
     *
     * @return a write-nothing {@link ItemWriter} that logs the chunk size
     */
    @Bean
    public ItemWriter<DailyTransaction> dailyTransactionNoOpWriter() {
        return items -> log.debug("Chunk of {} DailyTransaction record(s) read", items.size());
    }

    /**
     * Chunk-oriented diagnostic {@link Step} wiring the reader, logging processor and no-op writer.
     *
     * <p>The {@link #transactionManager} delimits each chunk's transaction boundary (PR-24) and the
     * chunk size is {@link #CHUNK_SIZE}. The step reads {@link DailyTransaction} items and emits
     * {@link DailyTransaction} items (identity processing), mirroring the single sequential pass of
     * the COBOL {@code MAIN-PARA} loop.</p>
     *
     * @return the {@code dailyTransactionReadStep} bean
     */
    @Bean
    public Step dailyTransactionReadStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<DailyTransaction, DailyTransaction>chunk(CHUNK_SIZE, transactionManager)
                .reader(dailyTransactionReader())
                .processor(dailyTransactionLoggingProcessor())
                .writer(dailyTransactionNoOpWriter())
                .build();
    }

    /**
     * The diagnostic {@link Job} bean ({@link #JOB_NAME}) consisting of the single
     * {@link #dailyTransactionReadStep()}.
     *
     * <p>Auto-registered with the Spring Batch {@code JobRegistry} by Spring Boot
     * auto-configuration, so it is launchable by name through {@code BatchAdminController}. Running
     * the job with the same parameters returns the existing {@code JobExecution} (idempotent);
     * supplying a distinct parameter set creates a new execution.</p>
     *
     * @return the {@code dailyTransactionReadJob} bean
     */
    @Bean
    public Job dailyTransactionReadJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(dailyTransactionReadStep())
                .build();
    }
}
