package com.aws.carddemo.batch;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.repository.CustomerRepository;

/**
 * Spring Batch job configuration translating the mainframe batch program {@code CBCUS01C}
 * &mdash; "Read and print customer data file" &mdash; orchestrated by {@code READCUST.jcl}.
 *
 * <p>Origin: {@code legacy/cbl/CBCUS01C.cbl} (source branch {@code app/cbl/CBCUS01C.cbl}) and
 * {@code legacy/jcl/READCUST.jcl} (source branch {@code app/jcl/READCUST.jcl}). Implements AAP
 * &sect;0.4.1 (JCL job + batch COBOL program &rarr; Spring Batch {@link Job}) and &sect;0.6.3
 * (JES2/JCL &rarr; chunk-oriented Spring Batch topology).</p>
 *
 * <p><strong>Name-vs-behavior note (Explainability rule).</strong> The frozen AAP artifact name is
 * {@code CustomerLoadJobConfig}, but {@code CBCUS01C} does <strong>not</strong> load or insert data:
 * it is a <em>read-and-print</em> utility. On z/OS it opens the {@code CUSTDAT} VSAM KSDS
 * ({@code OPEN INPUT CUSTFILE-FILE}), reads each 500-byte {@code CUSTOMER-RECORD} sequentially in
 * key order, writes it to the job log via {@code DISPLAY CUSTOMER-RECORD}, and closes the file
 * (procedure paragraphs {@code 0000-CUSTFILE-OPEN}, {@code 1000-CUSTFILE-GET-NEXT},
 * {@code 9000-CUSTFILE-CLOSE}). This configuration therefore preserves <strong>read-only</strong>
 * behavior exactly &mdash; no write, update or load path is introduced that the source does not
 * have. The naming discrepancy and the read-only guarantee are recorded in
 * {@code docs/decision-log.md} and the {@code CBCUS01C} entry of {@code docs/traceability-matrix.md};
 * per the Explainability rule an unexplained deviation would be a defect, so it is explained rather
 * than silently renamed.</p>
 *
 * <p><strong>Control-flow mapping (COBOL paragraph &rarr; Spring Batch component).</strong> The
 * {@code PERFORM UNTIL END-OF-FILE = 'Y'} driver that repeatedly calls
 * {@code 1000-CUSTFILE-GET-NEXT} becomes a chunk-oriented step:</p>
 * <ul>
 *   <li><strong>Reader</strong> &mdash; a {@link RepositoryItemReader} over
 *       {@link CustomerRepository#findAll(org.springframework.data.domain.Pageable)}, ordered by the
 *       {@code custId} primary key ascending. This reproduces the sequential {@code READ CUSTFILE
 *       INTO CUSTOMER-RECORD} in VSAM key order (KSDS primary-key traversal; AAP &sect;0.6.6 requires
 *       a deterministic key ordering). The reader returning {@code null} when the result set is
 *       exhausted is the equivalent of {@code FILE STATUS '10'} (end-of-file), which
 *       {@code CBCUS01C} treats as a normal, non-error termination (it moves {@code 16} to
 *       {@code APPL-RESULT} and sets {@code END-OF-FILE = 'Y'}). Spring Batch's own I/O-error
 *       translation stands in for the {@code ELSE} branch that displays the file status and
 *       {@code CALL 'CEE3ABD'} (abends): a genuine data-access failure surfaces as an exception that
 *       fails the step rather than being swallowed.</li>
 *   <li><strong>Writer</strong> &mdash; an {@link ItemWriter} that emits one structured SLF4J line
 *       per record, reproducing the {@code DISPLAY CUSTOMER-RECORD} print action (see the PII note
 *       below).</li>
 * </ul>
 *
 * <p><strong>PII / operational-log redaction (parity with quirk #12).</strong> The
 * {@code CUSTOMER-RECORD} copybook ({@code CVCUS01Y}) carries personally identifiable information
 * (Social Security Number, government-issued id, date of birth, phone numbers, EFT account data,
 * FICO score). {@code CBCUS01C} {@code DISPLAY}s the full record to the job log. The migration
 * resolves the resulting tension exactly as prescribed by
 * {@code docs/traceability-matrix.md} &sect;11 and quirk&nbsp;#12, which mandate <em>two distinct
 * sinks</em>: (a) a protected, byte-for-byte parity-report output (reproduced separately by the
 * fixed-width record codec and the parity-test harness for operator/report files &mdash; out of this
 * job's scope, as {@code READCUST.jcl} defines no report DD and the fixed-width codec is not a
 * dependency of this job), and (b) <em>redacted operational logs</em> that must never contain
 * PAN/CVV/SSN/password. This job produces sink (b): the writer logs only the <strong>non-sensitive
 * {@code custId} primary key</strong> for each record processed &mdash; it does not emit the SSN,
 * government id, date of birth, phone numbers, EFT data or FICO score, consistent with the
 * field-level logging controls in &sect;11, the "no sensitive values written to logs" decision in
 * {@code docs/decision-log.md}, and the {@link Customer} entity's own PII-safe contract (its
 * {@link Customer#toString()} deliberately emits no PII). The read-and-print business behavior
 * (sequential, key-ordered, read-only traversal of every customer with a per-record log line) is
 * preserved; only the sensitive <em>content</em> of the per-record log line is redacted. Byte-for-byte
 * DISPLAY parity that includes PII, and any configurable field-level log masking, are recorded as
 * suggested next tasks in {@code docs/decision-log.md} (the "preserve behavior, defer hardening"
 * pattern of AAP &sect;0.6.7) rather than being applied here.</p>
 *
 * <p><strong>Wiring note (AAP binding constraint).</strong> This class deliberately does
 * <strong>not</strong> use {@code @EnableBatchProcessing} and does not import or depend on
 * {@code config/BatchConfig}. It relies on Spring Boot's Batch auto-configuration, which supplies
 * the persistent, restartable, JDBC-backed {@link JobRepository} and the
 * {@link PlatformTransactionManager} injected through the constructor &mdash; the same convention
 * used by the sibling batch configurations (for example {@code AdminBatchJobConfig}). Adding
 * {@code @EnableBatchProcessing} would make Boot's batch
 * auto-configuration back off and substitute a non-persistent in-memory job repository, a
 * functional regression versus the mainframe's JES2 checkpoint/restart behavior. The chunk/commit
 * interval {@link #CHUNK_SIZE} intentionally matches the shared read-only print-job commit interval
 * documented on {@code config.BatchConfig.DEFAULT_CHUNK_SIZE} (value {@code 100}); it is declared
 * locally, by value, rather than imported, to honor the binding constraint and the strict dependency
 * whitelist for this file. The rationale for all of the above lives in {@code docs/decision-log.md}
 * rather than in code comments.</p>
 *
 * <p>This configuration is stateless and thread-safe: it holds only immutable collaborators, and the
 * beans it declares carry no mutable shared state.</p>
 */
@Configuration
public class CustomerLoadJobConfig {

    /** Logger used by the writer to emit the redacted, one-line-per-record operational log. */
    private static final Logger LOGGER = LoggerFactory.getLogger(CustomerLoadJobConfig.class);

    /** Bean/registry name of the Spring Batch {@link Job} translating {@code CBCUS01C}. */
    static final String JOB_NAME = "customerLoadJob";

    /** Name of the single chunk-oriented step within {@link #JOB_NAME}. */
    static final String STEP_NAME = "customerLoadStep";

    /**
     * Name given to the {@link RepositoryItemReader}. Spring Batch uses it to namespace the reader's
     * keys in the step {@code ExecutionContext} (supporting restartability), mirroring the persistent
     * position tracking of the mainframe's checkpoint/restart model.
     */
    private static final String READER_NAME = "customerItemReader";

    /**
     * {@link Customer} JPA property used to order the sequential read. Ordering by the {@code custId}
     * primary key ascending reproduces the {@code CUSTDAT} KSDS key-sequenced traversal of
     * {@code CBCUS01C} (AAP &sect;0.6.6, deterministic key ordering).
     */
    private static final String CUST_ID_PROPERTY = "custId";

    /**
     * Chunk size (commit interval) for this read-only print step. The value {@code 100} matches the
     * shared print-job commit interval documented on {@code config.BatchConfig.DEFAULT_CHUNK_SIZE};
     * it is declared locally by value (not imported) to honor the binding constraint that this file
     * must not depend on {@code config/BatchConfig}. Because the job never mutates data, the commit
     * interval affects only reader paging and transaction-boundary granularity, never observable
     * results.
     */
    private static final int CHUNK_SIZE = 100;

    /** Auto-configured Spring Batch job repository used to build the step and the job. */
    private final JobRepository jobRepository;

    /** Auto-configured transaction manager bounding each chunk's (read-only) transaction. */
    private final PlatformTransactionManager transactionManager;

    /** Spring Data access to the {@code CUSTDAT} customer master, replacing the VSAM KSDS reads. */
    private final CustomerRepository customerRepository;

    /**
     * Creates the configuration with the collaborators supplied by Spring Boot's Batch
     * auto-configuration and component scanning.
     *
     * @param jobRepository      the auto-configured Spring Batch {@link JobRepository}
     * @param transactionManager the auto-configured {@link PlatformTransactionManager}
     * @param customerRepository the {@code CUSTDAT} repository read sequentially by the reader
     */
    public CustomerLoadJobConfig(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            CustomerRepository customerRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.customerRepository = customerRepository;
    }

    /**
     * Reader reproducing {@code CBCUS01C}'s {@code 1000-CUSTFILE-GET-NEXT} sequential read of the
     * {@code CUSTDAT} KSDS.
     *
     * <p>It pages through {@link CustomerRepository#findAll(org.springframework.data.domain.Pageable)}
     * ordered by {@link #CUST_ID_PROPERTY} ascending, so records are returned in customer-id key
     * order exactly as the VSAM key-sequenced traversal would. When the result set is exhausted the
     * reader returns {@code null}, which is the {@code FILE STATUS '10'} end-of-file condition that
     * {@code CBCUS01C} treats as a normal termination. This is a read-only access path: no repository
     * mutation method is ever invoked.</p>
     *
     * @return a configured, key-ordered {@link RepositoryItemReader} over the customer master
     */
    @Bean
    public RepositoryItemReader<Customer> customerItemReader() {
        return new RepositoryItemReaderBuilder<Customer>()
                .name(READER_NAME)
                .repository(customerRepository)
                .methodName("findAll")
                .sorts(Map.of(CUST_ID_PROPERTY, Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    /**
     * Writer reproducing {@code CBCUS01C}'s {@code DISPLAY CUSTOMER-RECORD} print action as a
     * redacted operational log line, one per record.
     *
     * <p>For each customer in the chunk it emits a single INFO line containing only the non-sensitive
     * {@code custId} primary key. The record's PII (SSN, government id, date of birth, phone numbers,
     * EFT account data, FICO score) is intentionally excluded from the operational log per
     * {@code docs/traceability-matrix.md} &sect;11 / quirk&nbsp;#12 and the "no sensitive values
     * written to logs" decision in {@code docs/decision-log.md}. The writer performs no persistence:
     * it neither writes to the repository nor mutates any entity, preserving the read-only nature of
     * the source program.</p>
     *
     * @return an {@link ItemWriter} that logs the redacted, per-record customer print line
     */
    @Bean
    public ItemWriter<Customer> customerRecordWriter() {
        return customers -> {
            for (Customer customer : customers) {
                LOGGER.info("CBCUS01C read and printed customer record (custId={})",
                        customer.getCustId());
            }
        };
    }

    /**
     * The single chunk-oriented step of {@link #customerLoadJob()}, wiring the key-ordered
     * {@link #customerItemReader()} to the redacting {@link #customerRecordWriter()} within the
     * auto-configured transaction boundary. The {@code <Customer, Customer>} chunk shape reflects a
     * pass-through print step: items are read and logged without transformation.
     *
     * @return the {@code customerLoadStep} {@link Step}
     */
    @Bean
    public Step customerLoadStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<Customer, Customer>chunk(CHUNK_SIZE, transactionManager)
                .reader(customerItemReader())
                .writer(customerRecordWriter())
                .build();
    }

    /**
     * The Spring Batch {@link Job} corresponding to legacy program {@code CBCUS01C} / job
     * {@code READCUST.jcl}. It consists of the single {@link #customerLoadStep()} and completes with
     * batch status {@code COMPLETED} once every customer record has been read and printed, including
     * the degenerate case of an empty customer master (which completes immediately, mirroring an
     * immediate {@code FILE STATUS '10'} at first read).
     *
     * @return the {@code customerLoadJob} {@link Job}
     */
    @Bean
    public Job customerLoadJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(customerLoadStep())
                .build();
    }
}
