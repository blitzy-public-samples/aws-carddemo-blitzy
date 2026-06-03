package com.carddemo.batch;

import com.carddemo.entity.Customer;
import com.carddemo.repository.CustomerRepository;
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
 * Spring Batch configuration for the <strong>{@code customerFileReadJob}</strong> — the
 * Java/PostgreSQL replacement for the legacy batch COBOL program {@code app/cbl/CBCUS01C.cbl}
 * (invoked operationally by the JCL job {@code app/jcl/READCUST.jcl}).
 *
 * <h2>Legacy mainframe behavior (CBCUS01C.cbl + READCUST.jcl)</h2>
 * {@code CBCUS01C} is a pure <em>diagnostic</em> reader. Its {@code PROCEDURE DIVISION} opens the
 * indexed {@code CUSTFILE-FILE} ({@code ORGANIZATION IS INDEXED}, {@code ACCESS MODE IS SEQUENTIAL},
 * {@code RECORD KEY IS FD-CUST-ID} — the CUSTDATA VSAM KSDS laid out by {@code app/cpy/CVCUS01Y.cpy}
 * {@code CUSTOMER-RECORD}), then loops {@code PERFORM UNTIL END-OF-FILE = 'Y'}. Each iteration
 * performs {@code 1000-CUSTFILE-GET-NEXT} ({@code READ CUSTFILE-FILE INTO CUSTOMER-RECORD}; an
 * end-of-file file-status {@code '10'} maps to {@code APPL-EOF} and sets {@code END-OF-FILE = 'Y'})
 * and {@code DISPLAY CUSTOMER-RECORD}s it. Finally it performs {@code 9000-CUSTFILE-CLOSE} and
 * {@code GOBACK}s. The program never writes, updates, or deletes — it only dumps the customer master
 * file to {@code SYSOUT} for operator verification. {@code app/jcl/READCUST.jcl} is merely the JCL
 * wrapper that executes {@code PGM=CBCUS01C} (step {@code STEP05}) with the {@code CUSTFILE} DD
 * pointed at {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS}.
 *
 * <h2>Modernized Spring Batch behavior — diagnostic reader</h2>
 * This job is a single chunk-oriented {@link Step} that pages over the {@code customers} table (the
 * relational system-of-record replacing the VSAM CUSTDATA KSDS) and logs every row — the faithful
 * Spring equivalent of the COBOL {@code DISPLAY CUSTOMER-RECORD} loop. It never writes to the
 * database (read-only diagnostic, PR-25 single monolith).
 *
 * <h2>Step shape</h2>
 * <pre>
 *   RepositoryItemReader&lt;Customer&gt;  (pages the customers table via findAll, sorted by custId ASC)
 *        -&gt; ItemProcessor (logs each row, masking SSN per PR-20; identity pass-through)
 *        -&gt; ItemWriter   (no-op; logs the chunk size only)
 * </pre>
 * The reader sorts by the natural primary key {@code custId} (the {@code cust_id} {@code BIGINT}
 * column, originally COBOL {@code CUST-ID PIC 9(09)}, which was the VSAM {@code RECORD KEY IS
 * FD-CUST-ID}) ascending. Because {@code custId} is a numeric {@link Long} primary key, ascending
 * order is a numeric ordering that reproduces the sequential record-key ordering of the original
 * VSAM read. A deterministic, non-empty sort is also a hard requirement of the paging
 * {@link RepositoryItemReader}.
 *
 * <h2>SSN masking (PR-20)</h2>
 * The {@link Customer} record is PII-rich. Even in this purely diagnostic log output the Social
 * Security Number is <strong>never</strong> emitted in the clear: {@link #maskSsn(String)} renders it
 * as {@code ***-**-####} (only the last four digits visible), enforcing PR-20 (SSN masking on
 * outbound) at the logging boundary. Production systems must never log a full SSN; this diagnostic
 * reader honours that rule exactly as the REST {@code CustomerMapper} does for outbound DTOs.
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><strong>PR-20</strong> (SSN masking on outbound): the SSN is masked to {@code ***-**-####}
 *       in the diagnostic log line via {@link #maskSsn(String)}.</li>
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
 * can launch this job by its {@link #JOB_NAME} — the REST-era equivalent of submitting
 * {@code app/jcl/READCUST.jcl} to run {@code CBCUS01C}.</p>
 *
 * <p><strong>Entity accessor note.</strong> The logging processor reads the committed
 * {@link Customer} entity getters. The COBOL {@code CUST-ADDR-STATE-CD} and {@code CUST-ADDR-ZIP}
 * fields normalize to the Java properties {@code stateCd} / {@code zipCd} (DDL columns
 * {@code addr_state_cd} / {@code addr_zip}), so the corresponding accessors are
 * {@code getStateCd()} and {@code getZipCd()}. All logged values
 * ({@code getCustId()} &rarr; {@link Long}; {@code getFirstName()}, {@code getLastName()},
 * {@code getStateCd()}, {@code getZipCd()}, {@code getSsn()} &rarr; {@link String}) are passed to the
 * SLF4J {@code log.info(String, Object...)} varargs slots and rendered through their
 * {@code toString()} at log time.</p>
 *
 * <p><strong>Scaling note.</strong> For very large customer volumes a {@code JdbcCursorItemReader}
 * streaming a forward-only cursor would avoid materializing paged result sets; the
 * {@link RepositoryItemReader} paging approach is used here for consistency with the sibling
 * diagnostic readers ({@link AccountFileReadJobConfig}, {@link CardFileReadJobConfig},
 * {@link XrefFileReadJobConfig}) and is more than adequate for the demonstration-grade data volume
 * (the 50 default customer rows seeded from {@code app/data/ASCII/custdata.txt}).</p>
 *
 * @see com.carddemo.entity.Customer
 * @see com.carddemo.repository.CustomerRepository
 * @see CardFileReadJobConfig
 * @see XrefFileReadJobConfig
 * @see org.springframework.batch.item.data.RepositoryItemReader
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class CustomerFileReadJobConfig {

    /**
     * Logical name of the diagnostic {@link Job} bean. Used by the Spring Batch {@code JobRegistry}
     * and by {@code BatchAdminController} ({@code POST /api/admin/jobs/{jobName}/launch}) to launch
     * this job by name for operator verification of the {@code customers} table — the faithful
     * equivalent of submitting {@code app/jcl/READCUST.jcl} to run {@code CBCUS01C}.
     */
    public static final String JOB_NAME = "customerFileReadJob";

    /** Logical name of the single chunk-oriented diagnostic {@link Step}. */
    private static final String STEP_NAME = "customerFileReadStep";

    /**
     * {@link RepositoryItemReader} name — the key prefix under which the reader saves and restores
     * its paging state in the step {@code ExecutionContext}. Must be unique within the step.
     */
    private static final String READER_NAME = "customerFileReader";

    /**
     * Chunk size, doubling as the reader page size. A value of {@value} matches the codebase-wide
     * default chunk size (AAP &sect;0.3.3 #7) and balances log granularity against database
     * round-trips for the demonstration-grade data volume.
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * Entity property the reader sorts on (mapped to the {@code cust_id} primary-key column).
     * Sorting by the numeric {@link Long} {@code custId} ascending yields a stable, deterministic
     * ordering that reproduces the VSAM {@code RECORD KEY IS FD-CUST-ID} sequential read order of
     * {@code CBCUS01C} — and is also a hard requirement of the paging {@link RepositoryItemReader}
     * (its sort map must be non-empty).
     */
    private static final String SORT_PROPERTY = "custId";

    /** Spring Batch metadata repository (auto-configured by Spring Boot 3.x). */
    private final JobRepository jobRepository;

    /**
     * Transaction manager bracketing each chunk's unit of work — the {@code SYNCPOINT}-equivalent
     * boundary for the read step (PR-24, AAP &sect;0.6.12). Even though this diagnostic step performs
     * no writes, Spring Batch requires a transaction manager to delimit chunk processing.
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * Spring Data JPA repository over the {@code customers} table. Supplied to the
     * {@link RepositoryItemReaderBuilder} to provide a paged full-table read via its inherited
     * {@code findAll(Pageable)} method ({@code CustomerRepository} extends {@code JpaRepository},
     * hence {@code PagingAndSortingRepository}).
     */
    private final CustomerRepository customerRepository;

    /**
     * Paging reader over the {@code customers} table.
     *
     * <p>Built on {@link RepositoryItemReader}, which invokes
     * {@code customerRepository.findAll(Pageable)} one page at a time. The configured
     * {@code methodName} {@code "findAll"} combined with the {@code PageRequest} the reader passes
     * resolves unambiguously to {@code PagingAndSortingRepository.findAll(Pageable)} (a
     * {@code PageRequest} is a {@code Pageable}, not a {@code Sort}). A non-empty sort on
     * {@link #SORT_PROPERTY} ascending is mandatory for the paging reader and reproduces the VSAM
     * record-key dump ordering; the page size is set to {@link #CHUNK_SIZE} so each page aligns with
     * one processing chunk.</p>
     *
     * @return a configured {@link RepositoryItemReader} streaming the {@code customers} table in
     *         ascending {@code custId} order
     */
    @Bean
    public RepositoryItemReader<Customer> customerFileReader() {
        return new RepositoryItemReaderBuilder<Customer>()
                .name(READER_NAME)
                .repository(customerRepository)
                .methodName("findAll")
                .sorts(Collections.singletonMap(SORT_PROPERTY, Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    /**
     * Pass-through {@link ItemProcessor} that logs each customer row — the Spring equivalent of the
     * COBOL {@code DISPLAY CUSTOMER-RECORD} diagnostic in {@code CBCUS01C}.
     *
     * <p>A per-bean {@link AtomicLong} captured by the returned lambda emits a monotonically
     * increasing record number ({@code [#n]}) so an operator can correlate the log against the table
     * row count. The item is returned unchanged so the chunk flows on to the no-op writer.</p>
     *
     * <p><strong>PR-20 (SSN masking):</strong> the Social Security Number is masked to
     * {@code ***-**-####} via {@link #maskSsn(String)} before it reaches the log; the full value is
     * never written to {@code SYSOUT}/the log. Free-text fields are guarded by {@link #nullSafe(String)}
     * so a {@code null} renders as an empty string rather than the literal {@code "null"}. Accessor
     * calls use the actual committed {@link Customer} getters — {@code getCustId()} ({@link Long} PK),
     * {@code getFirstName()}, {@code getLastName()}, {@code getStateCd()} (DDL {@code addr_state_cd}),
     * {@code getZipCd()} (DDL {@code addr_zip}) and {@code getSsn()} (all {@link String}) — while the
     * log labels ({@code custId}, {@code firstName}, {@code lastName}, {@code state}, {@code zip},
     * {@code ssn}) mirror the COBOL {@code CUSTOMER-RECORD} field names for traceability. Only a
     * representative subset of the 500-byte {@code CVCUS01Y} layout is logged for brevity.</p>
     *
     * @return a logging, identity-mapping {@link ItemProcessor} that masks SSN per PR-20
     */
    @Bean
    public ItemProcessor<Customer, Customer> customerFileLoggingProcessor() {
        final AtomicLong counter = new AtomicLong(0L);
        return customer -> {
            long recordNumber = counter.incrementAndGet();
            log.info("[CBCUS01C-DIAG] [#{}] custId={} firstName={} lastName={} state={} zip={} ssn={}",
                    recordNumber,
                    customer.getCustId(),
                    nullSafe(customer.getFirstName()),
                    nullSafe(customer.getLastName()),
                    nullSafe(customer.getStateCd()),
                    nullSafe(customer.getZipCd()),
                    maskSsn(customer.getSsn()));
            return customer;
        };
    }

    /**
     * No-op {@link ItemWriter} for the diagnostic step. Because this job only verifies table
     * contents (it never persists anything — mirroring the read-only {@code CBCUS01C}), the writer
     * merely records the number of items in each processed chunk at {@code DEBUG} level. The lambda
     * parameter is a Spring Batch 5 {@code Chunk<? extends Customer>}, whose {@code size()} yields the
     * chunk item count.
     *
     * @return a write-nothing {@link ItemWriter} that logs the chunk size
     */
    @Bean
    public ItemWriter<Customer> customerFileNoOpWriter() {
        return items -> log.debug("Chunk of {} Customer record(s) read", items.size());
    }

    /**
     * Chunk-oriented diagnostic {@link Step} wiring the reader, logging processor and no-op writer.
     *
     * <p>The {@link #transactionManager} delimits each chunk's transaction boundary (PR-24) and the
     * chunk size is {@link #CHUNK_SIZE}. The step reads {@link Customer} items and emits
     * {@link Customer} items (identity processing), mirroring the single sequential pass of the COBOL
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop in {@code CBCUS01C}.</p>
     *
     * @return the {@code customerFileReadStep} bean
     */
    @Bean
    public Step customerFileReadStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<Customer, Customer>chunk(CHUNK_SIZE, transactionManager)
                .reader(customerFileReader())
                .processor(customerFileLoggingProcessor())
                .writer(customerFileNoOpWriter())
                .build();
    }

    /**
     * The diagnostic {@link Job} bean ({@link #JOB_NAME}) consisting of the single
     * {@link #customerFileReadStep()}.
     *
     * <p>Auto-registered with the Spring Batch {@code JobRegistry} by Spring Boot auto-configuration,
     * so it is launchable by name through {@code BatchAdminController} — the REST-era equivalent of
     * submitting {@code app/jcl/READCUST.jcl} to run {@code CBCUS01C}. The job has a single step and
     * therefore no inter-step flow; it completes as soon as the sequential customer-table dump
     * finishes.</p>
     *
     * @return the {@code customerFileReadJob} bean
     */
    @Bean
    public Job customerFileReadJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(customerFileReadStep())
                .build();
    }

    /**
     * Masks a Social Security Number for safe diagnostic logging (PR-20). Renders the value as
     * {@code ***-**-####} exposing only the last four digits; a {@code null} value or any value
     * shorter than four characters collapses to {@code ***} so no partial PII can leak. The customer
     * SSN maps the COBOL {@code CUST-SSN PIC 9(09)} stored as a 9-character {@link String} (leading
     * zeros preserved per PR-13).
     *
     * @param ssn the raw, unmasked SSN (may be {@code null})
     * @return the masked representation, never {@code null}
     */
    private String maskSsn(String ssn) {
        if (ssn == null || ssn.length() < 4) {
            return "***";
        }
        return "***-**-" + ssn.substring(ssn.length() - 4);
    }

    /**
     * Null-safe rendering helper for free-text log fields: returns the empty string for a
     * {@code null} input so the diagnostic line shows an empty value rather than the literal
     * {@code "null"}.
     *
     * @param value the value to render (may be {@code null})
     * @return {@code value} when non-null, otherwise the empty string; never {@code null}
     */
    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
