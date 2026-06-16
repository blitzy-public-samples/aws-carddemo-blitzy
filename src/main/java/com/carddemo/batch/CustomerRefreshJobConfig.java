package com.carddemo.batch;

import com.carddemo.entity.Customer;
import com.carddemo.repository.CustomerRepository;
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
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Collections;
import java.util.Map;

/**
 * Spring Batch&nbsp;5 configuration for the <strong>customer master refresh</strong> job &mdash; the
 * Java reinterpretation of the legacy COBOL VSAM dump/print utility {@code app/cbl/CBCUS01C.cbl}.
 *
 * <h2>Legacy behaviour (source of truth)</h2>
 * <p>{@code CBCUS01C} is a batch program whose entire purpose is to <em>read and print the customer
 * data file</em>. It opens the {@code CUSTFILE} VSAM KSDS {@code INPUT}-only, walks every record in
 * primary-key ({@code CUST-ID}) order via a {@code READ ... NEXT}/{@code DISPLAY} loop until
 * end-of-file, then closes the file (see {@code CBCUS01C.cbl} paragraphs {@code 0000-CUSTFILE-OPEN},
 * {@code 1000-CUSTFILE-GET-NEXT}, {@code 9000-CUSTFILE-CLOSE}). It performs <strong>no</strong>
 * {@code WRITE}, {@code REWRITE}, or {@code DELETE} &mdash; it is strictly <em>read-only and
 * side-effect-free</em>. This class preserves that contract exactly: it streams the {@code customers}
 * table in key order and counts/logs the records it reads, mutating nothing.</p>
 *
 * <h2>Migration mapping (AAP &sect;0.2.2.1, &sect;0.4.1.5, &sect;0.3.2)</h2>
 * <p>The Agent Action Plan reinterprets the VSAM dump/print utilities as Spring Batch "refresh" jobs.
 * {@code CBCUS01C} becomes this single-step {@code customerRefreshJob}, the customer-master analogue
 * of {@code AccountRefreshJobConfig}. The translation is the standard chunk-oriented
 * reader&nbsp;&rarr;&nbsp;writer triplet:</p>
 * <ul>
 *   <li>The COBOL {@code OPEN INPUT}/{@code READ NEXT} sequential browse becomes a
 *       {@link RepositoryItemReader} over {@link CustomerRepository#findAll(org.springframework.data.domain.Pageable)
 *       findAll(Pageable)}, paging in {@link #CHUNK_SIZE}-sized blocks and ordering by the entity key
 *       {@code custId} (replacing the VSAM {@code CUST-ID} record key).</li>
 *   <li>The COBOL {@code DISPLAY CUSTOMER-RECORD} becomes a deliberately persistence-free
 *       {@linkplain #loggingWriter() logging writer} that emits only the customer identifier at
 *       {@code TRACE} level &mdash; never the personally identifiable fields the record also carries.</li>
 *   <li>The implicit COBOL end-of-run record count is surfaced by a
 *       {@linkplain #readCountListener() step-execution listener} that logs
 *       {@link StepExecution#getReadCount()} when the step completes.</li>
 * </ul>
 *
 * <h2>Batch infrastructure (OPTION&nbsp;A &mdash; Spring Boot auto-configuration)</h2>
 * <p>Consistent with {@code CardDemoApplication} and {@code config/BatchConfig}, this class does
 * <strong>not</strong> declare {@code @EnableBatchProcessing}. The shared {@link JobRepository} and
 * {@link PlatformTransactionManager} are supplied by Spring Boot's {@code BatchAutoConfiguration} and
 * are simply constructor-injected here. Jobs do not auto-run at startup
 * ({@code spring.batch.job.enabled=false}); {@code customerRefreshJob} is launched on demand through
 * the auto-configured {@code JobLauncher}.</p>
 *
 * <h2>Dependency boundary</h2>
 * <p>Per the layered architecture (AAP &sect;0.3.2) this batch configuration depends only on the
 * repository and entity layers ({@link CustomerRepository}, {@link Customer}); it imports no service
 * classes, performs no database writes, and uses no transaction-id generation. There is a single
 * constructor, so {@code @Autowired} is unnecessary.</p>
 *
 * <h2>Parity rules enforced (AAP &sect;0.6.8, &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>Read-only:</strong> no inserts/updates/deletes &mdash; faithful to the dump utility.</li>
 *   <li><strong>Key order:</strong> records are processed in {@code custId} ascending order.</li>
 *   <li><strong>Count-agnostic:</strong> the reader pages to exhaustion; no record count is hard-coded.</li>
 *   <li><strong>PII suppression:</strong> only {@code custId} is ever logged; the SSN, government id,
 *       and date of birth on {@link Customer} are never emitted.</li>
 * </ul>
 *
 * @see CustomerRepository
 * @see Customer
 * @see <a href="file:app/cbl/CBCUS01C.cbl">app/cbl/CBCUS01C.cbl</a>
 */
@Configuration
public class CustomerRefreshJobConfig {

    /**
     * Logger for the customer-refresh job. Used only to emit the read-count summary at {@code INFO}
     * and, when {@code TRACE} is enabled, the per-record {@code custId}. Never logs PII.
     */
    private static final Logger log = LoggerFactory.getLogger(CustomerRefreshJobConfig.class);

    /**
     * Reader page size and step commit-interval (chunk size) for the refresh.
     *
     * <p>Because this job neither writes nor needs the legacy 7-row online screen pagination, the
     * value is tuned purely for efficient sequential streaming of the customer master; {@code 100}
     * mirrors the sibling account-refresh job. The same constant is used for the
     * {@link RepositoryItemReader} page size and the chunk commit interval so that one database page
     * maps to exactly one (empty) commit.</p>
     */
    private static final int CHUNK_SIZE = 100;

    /** Auto-configured Spring Batch metadata repository used to build the step and job. */
    private final JobRepository jobRepository;

    /** Auto-configured transaction manager bounding each chunk's (read-only) transaction. */
    private final PlatformTransactionManager transactionManager;

    /** Repository providing key-ordered, paged read access to the {@code customers} table. */
    private final CustomerRepository customerRepository;

    /**
     * Creates the customer-refresh job configuration with its collaborators injected by Spring.
     *
     * <p>This is the only constructor, so Spring resolves and injects all three arguments without an
     * explicit {@code @Autowired} annotation. All fields are {@code final}, making the configuration
     * immutable after construction.</p>
     *
     * @param jobRepository      the auto-configured Spring Batch {@link JobRepository}
     * @param transactionManager the auto-configured {@link PlatformTransactionManager}
     * @param customerRepository the Spring Data repository for the {@link Customer} aggregate
     */
    public CustomerRefreshJobConfig(JobRepository jobRepository,
                                    PlatformTransactionManager transactionManager,
                                    CustomerRepository customerRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.customerRepository = customerRepository;
    }

    /**
     * Builds the side-effect-free chunk writer that replaces the COBOL {@code DISPLAY CUSTOMER-RECORD}.
     *
     * <p>The writer persists <strong>nothing</strong>: it merely logs, at {@code TRACE} level and only
     * when {@code TRACE} logging is enabled, the {@code custId} of each customer in the chunk. No other
     * field is logged, in particular never the SSN, government-issued id, or date of birth carried by
     * {@link Customer} (AAP &sect;0.6.8 PII suppression). Keeping the body inside the
     * {@code isTraceEnabled()} guard avoids iterating the chunk at all when trace logging is off.</p>
     *
     * @return an {@link ItemWriter} that logs only {@code custId} and writes no data
     */
    private ItemWriter<Customer> loggingWriter() {
        return chunk -> {
            if (log.isTraceEnabled()) {
                for (Customer customer : chunk) {
                    log.trace("Refresh read CUSTOMER: custId={}", customer.getCustId());
                }
            }
        };
    }

    /**
     * Builds the step listener that logs the total number of customer records read once the step ends.
     *
     * <p>This reproduces the implicit end-of-run accounting of {@code CBCUS01C} without hard-coding any
     * expected count. {@link StepExecutionListener} declares {@code beforeStep} and {@code afterStep} as
     * {@code default} methods in Spring Batch&nbsp;5, so the anonymous implementation overrides only
     * {@code afterStep}; it returns the step's existing {@link ExitStatus} unchanged, leaving the
     * outcome entirely to the framework.</p>
     *
     * @return a {@link StepExecutionListener} that logs {@link StepExecution#getReadCount()} after the step
     */
    private StepExecutionListener readCountListener() {
        return new StepExecutionListener() {
            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                log.info("CUSTOMER refresh complete: {} record(s) read", stepExecution.getReadCount());
                return stepExecution.getExitStatus();
            }
        };
    }

    /**
     * Reader bean that streams the {@code customers} table in primary-key order.
     *
     * <p>Backed by {@link CustomerRepository#findAll(org.springframework.data.domain.Pageable)
     * findAll(Pageable)} (resolved by {@code methodName="findAll"} with an empty fixed-argument list, so
     * the reader appends only the page request), it pages through the entire table in
     * {@link #CHUNK_SIZE}-sized blocks until exhaustion. The {@code sorts} map MUST be non-empty &mdash;
     * {@link RepositoryItemReader} requires an explicit ordering to make paging deterministic &mdash; so
     * the records are ordered by the entity key {@code custId} ascending, faithfully replacing the
     * legacy VSAM {@code CUST-ID} key sequence.</p>
     *
     * <p>The bean is a plain singleton: the job takes no parameters, so neither {@code @StepScope} nor
     * late binding is needed.</p>
     *
     * @return a configured {@link RepositoryItemReader} over {@link Customer}, ordered by {@code custId}
     */
    @Bean
    public RepositoryItemReader<Customer> customerRefreshReader() {
        return new RepositoryItemReaderBuilder<Customer>()
                .name("customerRefreshReader")
                .repository(customerRepository)
                .methodName("findAll")
                .arguments(Collections.emptyList())
                .sorts(Map.of("custId", Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    /**
     * Single chunk-oriented step of the customer-refresh job.
     *
     * <p>The chunk input and output types are both {@link Customer} ({@code <Customer, Customer>}): the
     * job reads customers and "writes" them through the persistence-free {@link #loggingWriter()}, so no
     * type transformation occurs. The commit interval equals {@link #CHUNK_SIZE}. The
     * {@link #readCountListener()} reports the number of records read when the step finishes.</p>
     *
     * @param customerRefreshReader the reader bean injected by Spring (see {@link #customerRefreshReader()})
     * @return the configured {@link Step}
     */
    @Bean
    public Step customerRefreshStep(RepositoryItemReader<Customer> customerRefreshReader) {
        return new StepBuilder("customerRefreshStep", jobRepository)
                .<Customer, Customer>chunk(CHUNK_SIZE, transactionManager)
                .reader(customerRefreshReader)
                .writer(loggingWriter())
                .listener(readCountListener())
                .build();
    }

    /**
     * The customer-master refresh {@link Job}, composed of the single {@link #customerRefreshStep(RepositoryItemReader)}.
     *
     * <p>The bean is named exactly {@code customerRefreshJob}; this is the identifier under which the
     * job is launched on demand via the auto-configured {@code JobLauncher}. The job runs the single
     * read-only step to completion, mirroring the linear top-to-bottom flow of {@code CBCUS01C}.</p>
     *
     * @param customerRefreshStep the step bean injected by Spring (see {@link #customerRefreshStep(RepositoryItemReader)})
     * @return the configured {@link Job} named {@code customerRefreshJob}
     */
    @Bean
    public Job customerRefreshJob(Step customerRefreshStep) {
        return new JobBuilder("customerRefreshJob", jobRepository)
                .start(customerRefreshStep)
                .build();
    }
}
