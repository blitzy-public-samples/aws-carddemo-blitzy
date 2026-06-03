package com.carddemo.batch;

import com.carddemo.entity.Card;
import com.carddemo.repository.CardRepository;
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
 * Spring Batch configuration for the <strong>{@code cardFileReadJob}</strong> — the
 * Java/PostgreSQL replacement for the legacy batch COBOL program {@code app/cbl/CBACT02C.cbl}
 * (invoked operationally by the JCL job {@code app/jcl/READCARD.jcl}).
 *
 * <h2>Legacy mainframe behavior (CBACT02C.cbl + READCARD.jcl)</h2>
 * {@code CBACT02C} is a pure <em>diagnostic</em> reader. Its {@code PROCEDURE DIVISION} opens the
 * indexed {@code CARDFILE-FILE} ({@code ORGANIZATION IS INDEXED}, {@code ACCESS MODE IS SEQUENTIAL},
 * {@code RECORD KEY IS FD-CARD-NUM} — the CARDDATA VSAM KSDS laid out by {@code app/cpy/CVACT02Y.cpy}
 * {@code CARD-RECORD}), then loops {@code PERFORM UNTIL END-OF-FILE = 'Y'}. Each iteration performs
 * {@code 1000-CARDFILE-GET-NEXT} ({@code READ CARDFILE-FILE INTO CARD-RECORD}; an end-of-file
 * file-status {@code '10'} maps to {@code APPL-EOF} and sets {@code END-OF-FILE = 'Y'}) and
 * {@code DISPLAY CARD-RECORD}s it. Finally it performs {@code 9000-CARDFILE-CLOSE} and
 * {@code GOBACK}s. The program never writes, updates, or deletes — it only dumps the card master
 * file to {@code SYSOUT} for operator verification. {@code app/jcl/READCARD.jcl} is merely the JCL
 * wrapper that executes {@code PGM=CBACT02C} (step {@code STEP05}) with the {@code CARDFILE} DD
 * pointed at {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS}.
 *
 * <h2>Modernized Spring Batch behavior — diagnostic reader</h2>
 * This job is a single chunk-oriented {@link Step} that pages over the {@code cards} table (the
 * relational system-of-record replacing the VSAM CARDDATA KSDS) and logs every row — the faithful
 * Spring equivalent of the COBOL {@code DISPLAY CARD-RECORD} loop. It never writes to the database
 * (read-only diagnostic, PR-25 single monolith).
 *
 * <h2>Step shape</h2>
 * <pre>
 *   RepositoryItemReader&lt;Card&gt;  (pages the cards table via findAll, sorted by cardNum ASC)
 *        -&gt; ItemProcessor (logs each row; identity pass-through)
 *        -&gt; ItemWriter   (no-op; logs the chunk size only)
 * </pre>
 * The reader sorts by the natural primary key {@code cardNum} (the {@code card_num} column,
 * originally COBOL {@code CARD-NUM PIC X(16)}, which was the VSAM {@code RECORD KEY}) ascending.
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
 * <p><strong>Entity accessor note.</strong> The logging processor reads the committed {@link Card}
 * entity getters. Their actual Java types are intentionally heterogeneous (they mirror the committed
 * Flyway DDL and {@code ddl-auto: validate} mappings, not a naive 1:1 string mapping of the COBOL
 * {@code PIC} clauses):</p>
 * <ul>
 *   <li>{@code getCardNum()} &rarr; {@link String} — the {@code card_num} primary key, COBOL
 *       {@code CARD-NUM PIC X(16)}.</li>
 *   <li>{@code getAccountId()} &rarr; {@link Long} — the {@code account_id} BIGINT, COBOL
 *       {@code CARD-ACCT-ID PIC 9(11)}.</li>
 *   <li>{@code getCvvCd()} &rarr; {@link Short} — the {@code cvv_cd} SMALLINT, COBOL
 *       {@code CARD-CVV-CD PIC 9(03)} (an exact small integer, <em>not</em> a String).</li>
 *   <li>{@code getExpirationDate()} &rarr; {@link java.time.LocalDate} — the {@code expiration_date}
 *       DATE, COBOL {@code CARD-EXPIRAION-DATE} [sic] {@code PIC X(10)} normalized to a SQL
 *       {@code DATE} (<em>not</em> a String).</li>
 *   <li>{@code getActiveStatus()} &rarr; {@link String} — the {@code active_status} CHAR(1), COBOL
 *       {@code CARD-ACTIVE-STATUS PIC X(01)} (e.g. {@code "Y"}/{@code "N"}).</li>
 * </ul>
 * <p>All five values are passed to the SLF4J {@code log.info(String, Object...)} varargs slots, so
 * the heterogeneous types ({@code String}/{@code Long}/{@code Short}/{@code LocalDate}) are each
 * rendered through their {@code toString()} at log time; the diagnostic line therefore mirrors the
 * COBOL {@code CARD-RECORD} field layout regardless of the underlying Java type.</p>
 *
 * <p><strong>Scaling note.</strong> For very large card volumes a {@code JdbcCursorItemReader}
 * streaming a forward-only cursor would avoid materializing paged result sets; the
 * {@link RepositoryItemReader} paging approach is used here for consistency with the sibling
 * diagnostic readers ({@link XrefFileReadJobConfig}, {@link TransactionReadJobConfig},
 * {@link DailyTransactionReadJobConfig}) and is more than adequate for the demonstration-grade data
 * volume (the 50 default card rows seeded from {@code app/data/ASCII/carddata.txt}).</p>
 *
 * @see com.carddemo.entity.Card
 * @see com.carddemo.repository.CardRepository
 * @see XrefFileReadJobConfig
 * @see org.springframework.batch.item.data.RepositoryItemReader
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class CardFileReadJobConfig {

    /**
     * Logical name of the diagnostic {@link Job} bean. Used by the Spring Batch {@code JobRegistry}
     * and by {@code BatchAdminController} ({@code POST /api/admin/jobs/{jobName}/launch}) to launch
     * this job by name for operator verification of the {@code cards} table — the faithful
     * equivalent of submitting {@code app/jcl/READCARD.jcl} to run {@code CBACT02C}.
     */
    public static final String JOB_NAME = "cardFileReadJob";

    /** Logical name of the single chunk-oriented diagnostic {@link Step}. */
    private static final String STEP_NAME = "cardFileReadStep";

    /**
     * {@link RepositoryItemReader} name — the key prefix under which the reader saves and restores
     * its paging state in the step {@code ExecutionContext}. Must be unique within the step.
     */
    private static final String READER_NAME = "cardFileReader";

    /**
     * Chunk size, doubling as the reader page size. A value of {@value} matches the codebase-wide
     * default chunk size (AAP &sect;0.3.3 #7) and balances log granularity against database
     * round-trips for the demonstration-grade data volume.
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * Entity property the reader sorts on (mapped to the {@code card_num} primary-key column).
     * Sorting by the fixed-width 16-character {@code cardNum} ascending yields a stable,
     * deterministic ordering that reproduces the VSAM {@code RECORD KEY IS FD-CARD-NUM} sequential
     * read order of {@code CBACT02C} — and is also a hard requirement of the paging
     * {@link RepositoryItemReader} (its sort map must be non-empty).
     */
    private static final String SORT_PROPERTY = "cardNum";

    /** Spring Batch metadata repository (auto-configured by Spring Boot 3.x). */
    private final JobRepository jobRepository;

    /**
     * Transaction manager bracketing each chunk's unit of work — the {@code SYNCPOINT}-equivalent
     * boundary for the read step (PR-24, AAP &sect;0.6.12). Even though this diagnostic step performs
     * no writes, Spring Batch requires a transaction manager to delimit chunk processing.
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * Spring Data JPA repository over the {@code cards} table. Supplied to the
     * {@link RepositoryItemReaderBuilder} to provide a paged full-table read via its inherited
     * {@code findAll(Pageable)} method ({@code CardRepository} extends {@code JpaRepository}, hence
     * {@code PagingAndSortingRepository}).
     */
    private final CardRepository cardRepository;

    /**
     * Paging reader over the {@code cards} table.
     *
     * <p>Built on {@link RepositoryItemReader}, which invokes {@code cardRepository.findAll(Pageable)}
     * one page at a time. The configured {@code methodName} {@code "findAll"} combined with the
     * {@code PageRequest} the reader passes resolves unambiguously to
     * {@code PagingAndSortingRepository.findAll(Pageable)} (a {@code PageRequest} is a
     * {@code Pageable}, not a {@code Sort}). A non-empty sort on {@link #SORT_PROPERTY} ascending is
     * mandatory for the paging reader and reproduces the VSAM record-key dump ordering; the page size
     * is set to {@link #CHUNK_SIZE} so each page aligns with one processing chunk.</p>
     *
     * @return a configured {@link RepositoryItemReader} streaming the {@code cards} table in
     *         ascending {@code cardNum} order
     */
    @Bean
    public RepositoryItemReader<Card> cardFileReader() {
        return new RepositoryItemReaderBuilder<Card>()
                .name(READER_NAME)
                .repository(cardRepository)
                .methodName("findAll")
                .sorts(Collections.singletonMap(SORT_PROPERTY, Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    /**
     * Pass-through {@link ItemProcessor} that logs each card row — the Spring equivalent of the
     * COBOL {@code DISPLAY CARD-RECORD} diagnostic in {@code CBACT02C}.
     *
     * <p>A per-bean {@link AtomicLong} captured by the returned lambda emits a monotonically
     * increasing record number ({@code [#n]}) so an operator can correlate the log against the table
     * row count. The item is returned unchanged so the chunk flows on to the no-op writer. Accessor
     * calls use the actual committed {@link Card} getters — {@code getCardNum()} (String PK),
     * {@code getAccountId()} (Long), {@code getCvvCd()} (Short), {@code getExpirationDate()}
     * (LocalDate) and {@code getActiveStatus()} (String) — while the log labels ({@code cardNum},
     * {@code accountId}, {@code cvv}, {@code expDate}, {@code status}) mirror the COBOL
     * {@code CARD-RECORD} field names for traceability.</p>
     *
     * @return a logging, identity-mapping {@link ItemProcessor}
     */
    @Bean
    public ItemProcessor<Card, Card> cardFileLoggingProcessor() {
        final AtomicLong counter = new AtomicLong(0L);
        return card -> {
            long recordNumber = counter.incrementAndGet();
            log.info("[CBACT02C-DIAG] [#{}] cardNum={} accountId={} cvv={} expDate={} status={}",
                    recordNumber,
                    card.getCardNum(),
                    card.getAccountId(),
                    card.getCvvCd(),
                    card.getExpirationDate(),
                    card.getActiveStatus());
            return card;
        };
    }

    /**
     * No-op {@link ItemWriter} for the diagnostic step. Because this job only verifies table
     * contents (it never persists anything — mirroring the read-only {@code CBACT02C}), the writer
     * merely records the number of items in each processed chunk at {@code DEBUG} level. The lambda
     * parameter is a Spring Batch 5 {@code Chunk<? extends Card>}, whose {@code size()} yields the
     * chunk item count.
     *
     * @return a write-nothing {@link ItemWriter} that logs the chunk size
     */
    @Bean
    public ItemWriter<Card> cardFileNoOpWriter() {
        return items -> log.debug("Chunk of {} Card record(s) read", items.size());
    }

    /**
     * Chunk-oriented diagnostic {@link Step} wiring the reader, logging processor and no-op writer.
     *
     * <p>The {@link #transactionManager} delimits each chunk's transaction boundary (PR-24) and the
     * chunk size is {@link #CHUNK_SIZE}. The step reads {@link Card} items and emits {@link Card}
     * items (identity processing), mirroring the single sequential pass of the COBOL
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop in {@code CBACT02C}.</p>
     *
     * @return the {@code cardFileReadStep} bean
     */
    @Bean
    public Step cardFileReadStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<Card, Card>chunk(CHUNK_SIZE, transactionManager)
                .reader(cardFileReader())
                .processor(cardFileLoggingProcessor())
                .writer(cardFileNoOpWriter())
                .build();
    }

    /**
     * The diagnostic {@link Job} bean ({@link #JOB_NAME}) consisting of the single
     * {@link #cardFileReadStep()}.
     *
     * <p>Auto-registered with the Spring Batch {@code JobRegistry} by Spring Boot auto-configuration,
     * so it is launchable by name through {@code BatchAdminController} — the REST-era equivalent of
     * submitting {@code app/jcl/READCARD.jcl} to run {@code CBACT02C}. The job has a single step and
     * therefore no inter-step flow; it completes as soon as the sequential card-table dump finishes.</p>
     *
     * @return the {@code cardFileReadJob} bean
     */
    @Bean
    public Job cardFileReadJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(cardFileReadStep())
                .build();
    }
}
