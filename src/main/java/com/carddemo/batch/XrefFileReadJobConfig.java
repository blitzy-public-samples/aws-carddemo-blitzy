package com.carddemo.batch;

import com.carddemo.entity.CardXref;
import com.carddemo.repository.CardXrefRepository;
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
 * Spring Batch configuration for the <strong>{@code xrefFileReadJob}</strong> — the
 * Java/PostgreSQL replacement for the legacy batch COBOL program {@code app/cbl/CBACT03C.cbl}
 * (invoked operationally by the JCL job {@code app/jcl/READXREF.jcl}).
 *
 * <h2>Legacy mainframe behavior (CBACT03C.cbl + READXREF.jcl)</h2>
 * {@code CBACT03C} is a pure <em>diagnostic</em> reader. Its {@code PROCEDURE DIVISION} opens the
 * indexed {@code XREFFILE-FILE} ({@code ORGANIZATION IS INDEXED}, {@code ACCESS MODE IS SEQUENTIAL},
 * {@code RECORD KEY IS FD-XREF-CARD-NUM} — the CARDXREF VSAM KSDS laid out by
 * {@code app/cpy/CVACT03Y.cpy} {@code CARD-XREF-RECORD}), then loops
 * {@code PERFORM UNTIL END-OF-FILE = 'Y'}. Each iteration performs {@code 1000-XREFFILE-GET-NEXT}
 * ({@code READ XREFFILE-FILE INTO CARD-XREF-RECORD}; an end-of-file file-status {@code '10'} maps to
 * {@code APPL-EOF} and sets {@code END-OF-FILE = 'Y'}) and {@code DISPLAY CARD-XREF-RECORD}s it.
 * Finally it performs {@code 9000-XREFFILE-CLOSE} and {@code GOBACK}s. The program never writes,
 * updates, or deletes — it only dumps the cross-reference file to {@code SYSOUT} for operator
 * verification. {@code app/jcl/READXREF.jcl} is merely the JCL wrapper that executes
 * {@code PGM=CBACT03C} with the {@code XREFFILE} DD pointed at
 * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}.
 *
 * <h2>Modernized Spring Batch behavior — diagnostic reader</h2>
 * This job is a single chunk-oriented {@link Step} that pages over the {@code card_xref} table (the
 * relational system-of-record replacing the VSAM CARDXREF KSDS) and logs every row — the faithful
 * Spring equivalent of the COBOL {@code DISPLAY CARD-XREF-RECORD} loop. It never writes to the
 * database (read-only diagnostic, PR-25 single monolith).
 *
 * <h2>Step shape</h2>
 * <pre>
 *   RepositoryItemReader&lt;CardXref&gt;  (pages the card_xref table via findAll, sorted by xrefCardNum ASC)
 *        -&gt; ItemProcessor (logs each row; identity pass-through)
 *        -&gt; ItemWriter   (no-op; logs the chunk size only)
 * </pre>
 * The reader sorts by the natural primary key {@code xrefCardNum} (the {@code xref_card_num} column,
 * originally COBOL {@code XREF-CARD-NUM PIC X(16)}, which was the VSAM {@code RECORD KEY}) ascending.
 * Because card numbers are fixed-width 16-character strings, lexicographic ascending order
 * reproduces the sequential record-key ordering of the original VSAM read. A deterministic,
 * non-empty sort is also a hard requirement of the paging {@link RepositoryItemReader}.
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
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
 * {@link CardXref} entity getters: {@code getXrefCardNum()} returns {@link String} (the
 * {@code xref_card_num} primary key, COBOL {@code XREF-CARD-NUM PIC X(16)}), while
 * {@code getCustId()} and {@code getAccountId()} return {@link Long} (the {@code xref_cust_id} /
 * {@code xref_acct_id} BIGINT columns, COBOL {@code XREF-CUST-ID PIC 9(09)} /
 * {@code XREF-ACCT-ID PIC 9(11)}). {@code CardXref} has only these three business fields — there is
 * no separate {@code cardNum} property distinct from {@code xrefCardNum} — so the diagnostic log
 * line mirrors the COBOL {@code CARD-XREF-RECORD} layout exactly.</p>
 *
 * <p><strong>Scaling note.</strong> For very large cross-reference volumes a
 * {@code JdbcCursorItemReader} streaming a forward-only cursor would avoid materializing paged
 * result sets; the {@link RepositoryItemReader} paging approach is used here for consistency with
 * the sibling diagnostic readers ({@link TransactionReadJobConfig},
 * {@link DailyTransactionReadJobConfig}) and is more than adequate for the demonstration-grade data
 * volume (the 50 default cross-reference rows seeded from {@code app/data/ASCII/cardxref.txt}).</p>
 *
 * @see com.carddemo.entity.CardXref
 * @see com.carddemo.repository.CardXrefRepository
 * @see TransactionReadJobConfig
 * @see org.springframework.batch.item.data.RepositoryItemReader
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class XrefFileReadJobConfig {

    /**
     * Logical name of the diagnostic {@link Job} bean. Used by the Spring Batch {@code JobRegistry}
     * and by {@code BatchAdminController} ({@code POST /api/admin/jobs/{jobName}/launch}) to launch
     * this job by name for operator verification of the {@code card_xref} table — the faithful
     * equivalent of submitting {@code app/jcl/READXREF.jcl} to run {@code CBACT03C}.
     */
    public static final String JOB_NAME = "xrefFileReadJob";

    /** Logical name of the single chunk-oriented diagnostic {@link Step}. */
    private static final String STEP_NAME = "xrefFileReadStep";

    /**
     * {@link RepositoryItemReader} name — the key prefix under which the reader saves and restores
     * its paging state in the step {@code ExecutionContext}. Must be unique within the step.
     */
    private static final String READER_NAME = "xrefFileReader";

    /**
     * Chunk size, doubling as the reader page size. A value of {@value} matches the codebase-wide
     * default chunk size (AAP &sect;0.3.3 #7) and balances log granularity against database
     * round-trips for the demonstration-grade data volume.
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * Entity property the reader sorts on (mapped to the {@code xref_card_num} primary-key column).
     * Sorting by the fixed-width 16-character {@code xrefCardNum} ascending yields a stable,
     * deterministic ordering that reproduces the VSAM {@code RECORD KEY IS FD-XREF-CARD-NUM}
     * sequential read order of {@code CBACT03C} — and is also a hard requirement of the paging
     * {@link RepositoryItemReader} (its sort map must be non-empty).
     */
    private static final String SORT_PROPERTY = "xrefCardNum";

    /** Spring Batch metadata repository (auto-configured by Spring Boot 3.x). */
    private final JobRepository jobRepository;

    /**
     * Transaction manager bracketing each chunk's unit of work — the {@code SYNCPOINT}-equivalent
     * boundary for the read step (PR-24, AAP &sect;0.6.12). Even though this diagnostic step performs
     * no writes, Spring Batch requires a transaction manager to delimit chunk processing.
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * Spring Data JPA repository over the {@code card_xref} table. Supplied to the
     * {@link RepositoryItemReaderBuilder} to provide a paged full-table read via its inherited
     * {@code findAll(Pageable)} method ({@code CardXrefRepository} extends {@code JpaRepository},
     * hence {@code PagingAndSortingRepository}).
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Paging reader over the {@code card_xref} table.
     *
     * <p>Built on {@link RepositoryItemReader}, which invokes
     * {@code cardXrefRepository.findAll(Pageable)} one page at a time. The configured
     * {@code methodName} {@code "findAll"} combined with the {@code PageRequest} the reader passes
     * resolves unambiguously to {@code PagingAndSortingRepository.findAll(Pageable)} (a
     * {@code PageRequest} is a {@code Pageable}, not a {@code Sort}). A non-empty sort on
     * {@link #SORT_PROPERTY} ascending is mandatory for the paging reader and reproduces the VSAM
     * record-key dump ordering; the page size is set to {@link #CHUNK_SIZE} so each page aligns with
     * one processing chunk.</p>
     *
     * @return a configured {@link RepositoryItemReader} streaming the {@code card_xref} table in
     *         ascending {@code xrefCardNum} order
     */
    @Bean
    public RepositoryItemReader<CardXref> xrefFileReader() {
        return new RepositoryItemReaderBuilder<CardXref>()
                .name(READER_NAME)
                .repository(cardXrefRepository)
                .methodName("findAll")
                .sorts(Collections.singletonMap(SORT_PROPERTY, Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    /**
     * Pass-through {@link ItemProcessor} that logs each cross-reference row — the Spring equivalent
     * of the COBOL {@code DISPLAY CARD-XREF-RECORD} diagnostic in {@code CBACT03C}.
     *
     * <p>A per-bean {@link AtomicLong} captured by the returned lambda emits a monotonically
     * increasing record number ({@code [#n]}) so an operator can correlate the log against the table
     * row count. The item is returned unchanged so the chunk flows on to the no-op writer. Accessor
     * calls use the actual committed {@link CardXref} getters — {@code getXrefCardNum()} (String PK),
     * {@code getCustId()} (Long) and {@code getAccountId()} (Long) — while the log labels
     * ({@code xrefCardNum}, {@code custId}, {@code accountId}) mirror the COBOL
     * {@code CARD-XREF-RECORD} field names for traceability.</p>
     *
     * @return a logging, identity-mapping {@link ItemProcessor}
     */
    @Bean
    public ItemProcessor<CardXref, CardXref> xrefFileLoggingProcessor() {
        final AtomicLong counter = new AtomicLong(0L);
        return cardXref -> {
            long recordNumber = counter.incrementAndGet();
            log.info("[CBACT03C-DIAG] [#{}] xrefCardNum={} custId={} accountId={}",
                    recordNumber,
                    cardXref.getXrefCardNum(),
                    cardXref.getCustId(),
                    cardXref.getAccountId());
            return cardXref;
        };
    }

    /**
     * No-op {@link ItemWriter} for the diagnostic step. Because this job only verifies table
     * contents (it never persists anything — mirroring the read-only {@code CBACT03C}), the writer
     * merely records the number of items in each processed chunk at {@code DEBUG} level. The lambda
     * parameter is a Spring Batch 5 {@code Chunk<? extends CardXref>}, whose {@code size()} yields
     * the chunk item count.
     *
     * @return a write-nothing {@link ItemWriter} that logs the chunk size
     */
    @Bean
    public ItemWriter<CardXref> xrefFileNoOpWriter() {
        return items -> log.debug("Chunk of {} CardXref record(s) read", items.size());
    }

    /**
     * Chunk-oriented diagnostic {@link Step} wiring the reader, logging processor and no-op writer.
     *
     * <p>The {@link #transactionManager} delimits each chunk's transaction boundary (PR-24) and the
     * chunk size is {@link #CHUNK_SIZE}. The step reads {@link CardXref} items and emits
     * {@link CardXref} items (identity processing), mirroring the single sequential pass of the
     * COBOL {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop in {@code CBACT03C}.</p>
     *
     * @return the {@code xrefFileReadStep} bean
     */
    @Bean
    public Step xrefFileReadStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<CardXref, CardXref>chunk(CHUNK_SIZE, transactionManager)
                .reader(xrefFileReader())
                .processor(xrefFileLoggingProcessor())
                .writer(xrefFileNoOpWriter())
                .build();
    }

    /**
     * The diagnostic {@link Job} bean ({@link #JOB_NAME}) consisting of the single
     * {@link #xrefFileReadStep()}.
     *
     * <p>Auto-registered with the Spring Batch {@code JobRegistry} by Spring Boot auto-configuration,
     * so it is launchable by name through {@code BatchAdminController} — the REST-era equivalent of
     * submitting {@code app/jcl/READXREF.jcl}. Running the job with the same parameters returns the
     * existing {@code JobExecution} (idempotent); supplying a distinct parameter set creates a new
     * execution.</p>
     *
     * @return the {@code xrefFileReadJob} bean
     */
    @Bean
    public Job xrefFileReadJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(xrefFileReadStep())
                .build();
    }
}
