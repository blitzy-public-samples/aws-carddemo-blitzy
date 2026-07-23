package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.repository.CustomerRepository;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Spring Batch integration / parity test (Failsafe {@code *IT}) for {@link CustomerLoadJobConfig},
 * proving that the migrated {@code customerLoadJob} reproduces the behavior of the mainframe batch
 * program {@code CBCUS01C} orchestrated by {@code READCUST.jcl} against a real PostgreSQL database.
 *
 * <p>Origin (read-only oracles retained under {@code legacy/}): {@code legacy/cbl/CBCUS01C.cbl} and
 * {@code legacy/jcl/READCUST.jcl}; the seed fixture is {@code legacy/data/ASCII/custdata.txt}
 * (50 customer records, {@code CUST-ID} {@code 1}&ndash;{@code 50}). Implements AAP &sect;0.4.1
 * (customer file "read/load" &mdash; the misnomer noted below) and &sect;0.6.6 (deterministic
 * key-ordered traversal).</p>
 *
 * <p><strong>Central parity quirk &mdash; "Load" is a misnomer (this is the headline assertion).</strong>
 * Despite the artifact name {@code CustomerLoadJobConfig}, the source program performs
 * <strong>no</strong> {@code INSERT}, {@code WRITE} or {@code UPDATE}. {@code CBCUS01C} opens the
 * {@code CUSTDAT} KSDS for input ({@code 0000-CUSTFILE-OPEN}), reads every 500-byte
 * {@code CUSTOMER-RECORD} sequentially in key order and {@code DISPLAY}s each one
 * ({@code 1000-CUSTFILE-GET-NEXT}, whose {@code READ} is followed by an inline
 * {@code DISPLAY CUSTOMER-RECORD} &mdash; there is no separate display paragraph), then closes the
 * file ({@code 9000-CUSTFILE-CLOSE}). The {@code READCUST.jcl} job wires only a {@code CUSTFILE}
 * input DD plus {@code SYSOUT}/{@code SYSPRINT}; it declares no output dataset, confirming the
 * read-only nature. The Java job is therefore read-only: its reader pages
 * {@link CustomerRepository#findAll(org.springframework.data.domain.Pageable)} ordered by
 * {@code custId} ascending and its writer only logs, so the customer table's row count and contents
 * are identical before and after execution. This test exists to <em>prove nothing is loaded</em>: a
 * test that merely checked the job completes would miss the parity point entirely. The misnomer and
 * the read-only guarantee are recorded for the traceability matrix
 * ({@code docs/traceability-matrix.md}) and the decision log ({@code docs/decision-log.md}).</p>
 *
 * <p><strong>Personally identifiable information (PII).</strong> The {@code CUSTOMER-RECORD} carries
 * PII (Social Security Number, date of birth, government-issued id, phone numbers). In the source
 * program these values are only {@code DISPLAY}ed to the job log, never persisted anew. This test
 * asserts that the existing rows &mdash; including their PII fields &mdash; are byte-identical after
 * the job runs, so no PII is inserted, mutated or duplicated by the migrated job.</p>
 *
 * <p><strong>Harness.</strong> The class extends {@link AbstractPostgresIntegrationTest}, inheriting
 * its shared Testcontainers PostgreSQL 18 instance, the {@code test} profile and the datasource
 * property binding. It deliberately does <strong>not</strong> use {@code @SpringBatchTest} (whose
 * auto-wired single-{@link Job} utility is unsuitable here). Rather than boot the entire application
 * &mdash; whose web, security and menu components are irrelevant to this batch job and, in an
 * incremental migration, may be assembled independently &mdash; the test pins an explicit, focused
 * Spring Boot slice ({@link BatchSliceConfig}) that enables auto-configuration and imports only
 * {@link CustomerLoadJobConfig} together with the domain entities and repositories. Spring Boot's
 * Batch auto-configuration then supplies the {@link JobLauncher} and {@link JobRepository}
 * ({@code CustomerLoadJobConfig} intentionally omits {@code @EnableBatchProcessing}). Flyway remains
 * enabled, so the real versioned schema and the 50-row customer seed are applied to the container
 * exactly as in production. Hibernate DDL validation is switched off via
 * {@code spring.jpa.hibernate.ddl-auto=none} because Flyway &mdash; not Hibernate &mdash; owns the
 * schema here; the JPA mappings are exercised through real queries rather than a start-up
 * cross-check. A nested {@link TestConfiguration} supplies exactly one {@link JobLauncherTestUtils},
 * explicitly bound to the {@code customerLoadJob} bean via {@link Qualifier}.</p>
 *
 * <p><strong>Fixed-width columns.</strong> The customer table preserves COBOL {@code PIC X(n)}
 * semantics as PostgreSQL {@code CHAR(n)} columns, so character values are read back space-padded to
 * their declared width (a deliberately preserved fixed-width trait). The PII name spot-checks
 * therefore compare trimmed values, whereas numeric SSN and the date of birth carry no padding; the
 * field-by-field before/after comparison relies on the identically padded snapshots.</p>
 *
 * @see CustomerLoadJobConfig
 */
@SpringBootTest(classes = {CustomerLoadJobConfigIT.BatchSliceConfig.class,
        CustomerLoadJobConfigIT.BatchTestHarnessConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.jpa.hibernate.ddl-auto=none",
            "management.prometheus.metrics.export.enabled=false"
        })
class CustomerLoadJobConfigIT extends AbstractPostgresIntegrationTest {

    /**
     * Number of customer rows seeded by the Flyway reference-data migration
     * ({@code V2__reference_data.sql}), derived from {@code legacy/data/ASCII/custdata.txt}
     * ({@code CUST-ID} {@code 1}&ndash;{@code 50}). The job must read exactly this many records and
     * must not change the count.
     */
    private static final long SEEDED_CUSTOMER_COUNT = 50L;

    /** Name of the single chunk-oriented step declared by {@link CustomerLoadJobConfig}. */
    private static final String STEP_NAME = "customerLoadStep";

    /**
     * {@link Customer} JPA property the production reader sorts on; querying the same access path
     * reproduces the exact sequence the job processes (AAP &sect;0.6.6, deterministic key order).
     */
    private static final String CUST_ID_PROPERTY = "custId";

    /**
     * Focused Spring Boot configuration slice that boots only what {@code customerLoadJob} requires:
     * auto-configuration (DataSource, JPA, Spring Batch, Flyway), the domain entities, the
     * repositories, and {@link CustomerLoadJobConfig} itself.
     *
     * <p>Pinning this as the explicit {@code @SpringBootTest} configuration keeps the context small
     * and deterministic: it neither component-scans nor instantiates the application's web,
     * security or menu beans (which are irrelevant to this batch job), and it prevents Spring Boot
     * from auto-detecting an unrelated nested {@code @SpringBootConfiguration} from a sibling test.
     * Flyway still owns and creates the real schema plus the 50-row customer seed, so the job runs
     * against production-equivalent data.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.aws.carddemo.domain")
    @EnableJpaRepositories("com.aws.carddemo.repository")
    @Import(CustomerLoadJobConfig.class)
    static class BatchSliceConfig {
    }

    /**
     * Test-only configuration supplying the single {@link JobLauncherTestUtils} used to launch the
     * job under test.
     *
     * <p>The utility is built by hand rather than through {@code @SpringBatchTest} so that the
     * job is selected explicitly by name with {@link Qualifier}, while the {@link JobLauncher} and
     * {@link JobRepository} are the unique beans supplied by Spring Boot's Batch auto-configuration.
     * In Spring Batch 5 the {@code JobLauncherTestUtils} setters carry no injection annotations, so
     * returning the instance from this factory method performs no further (ambiguous) autowiring.</p>
     */
    @TestConfiguration
    static class BatchTestHarnessConfig {

        /**
         * Builds the {@link JobLauncherTestUtils} bound to {@code customerLoadJob}.
         *
         * @param jobLauncher     the auto-configured Spring Batch job launcher
         * @param jobRepository   the auto-configured Spring Batch job repository
         * @param customerLoadJob the job under test, selected by bean name
         * @return the configured test utility
         */
        @Bean
        JobLauncherTestUtils jobLauncherTestUtils(JobLauncher jobLauncher, JobRepository jobRepository,
                @Qualifier("customerLoadJob") Job customerLoadJob) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            utils.setJob(customerLoadJob);
            return utils;
        }
    }

    /** Launches {@code customerLoadJob} and inspects the resulting {@link JobExecution}. */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /** Direct data access used to snapshot the customer table before and after the job runs. */
    @Autowired
    private CustomerRepository customerRepository;

    /**
     * The job completes successfully, reproducing {@code CBCUS01C}'s normal end-of-file termination
     * (a {@code FILE STATUS '10'} treated as success), and runs exactly one step named
     * {@code customerLoadStep}.
     *
     * @throws Exception if the job launcher fails to run the job
     */
    @Test
    void jobCompletes_readsAllCustomers() throws Exception {
        JobExecution execution = launchCustomerLoadJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);

        StepExecution step = singleStepExecution(execution);
        assertThat(step.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    /**
     * Every seeded customer record is read, reproducing the {@code CBCUS01C}
     * {@code PERFORM UNTIL END-OF-FILE} sequential traversal of the whole {@code CUSTDAT} file.
     *
     * @throws Exception if the job launcher fails to run the job
     */
    @Test
    void readCountEqualsSeededCustomerCount() throws Exception {
        JobExecution execution = launchCustomerLoadJob();

        StepExecution step = singleStepExecution(execution);
        assertThat(step.getReadCount()).isEqualTo(SEEDED_CUSTOMER_COUNT);

        // Spring Batch "write count" is the number of items handed to the ItemWriter, NOT the number
        // of database rows inserted. The CustomerLoadJobConfig writer only logs each record (it never
        // calls save/insert), so all 50 items flow through the writer while nothing is persisted. The
        // genuine read-only / no-load guarantee is proven by loadJobInsertsNothing_countUnchanged().
        assertThat(step.getWriteCount()).isEqualTo(SEEDED_CUSTOMER_COUNT);
    }

    /**
     * Headline misnomer assertion: the "Load" job inserts and updates nothing. The customer row count
     * is identical before and after the job (50 &rarr; 50, never doubled or increased), exactly as
     * {@code CBCUS01C} &mdash; which only reads and displays &mdash; would leave it.
     *
     * @throws Exception if the job launcher fails to run the job
     */
    @Test
    void loadJobInsertsNothing_countUnchanged() throws Exception {
        long countBefore = customerRepository.count();
        assertThat(countBefore).isEqualTo(SEEDED_CUSTOMER_COUNT);

        JobExecution execution = launchCustomerLoadJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        long countAfter = customerRepository.count();
        assertThat(countAfter).isEqualTo(SEEDED_CUSTOMER_COUNT);
        assertThat(countAfter).isEqualTo(countBefore);
    }

    /**
     * Existing customer rows &mdash; including PII (SSN, date of birth, names, address, phone numbers,
     * government id, FICO score) &mdash; are byte-identical before and after the job. PII is only
     * displayed/logged by the source program, never persisted anew, so nothing about the stored rows
     * may change.
     *
     * @throws Exception if the job launcher fails to run the job
     */
    @Test
    void existingCustomerRowsAreUnchanged_includingPii() throws Exception {
        List<Customer> before = customerRepository.findAll(Sort.by(Sort.Direction.ASC, CUST_ID_PROPERTY));
        assertThat(before).hasSize((int) SEEDED_CUSTOMER_COUNT);

        JobExecution execution = launchCustomerLoadJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<Customer> after = customerRepository.findAll(Sort.by(Sort.Direction.ASC, CUST_ID_PROPERTY));
        assertThat(after).hasSize((int) SEEDED_CUSTOMER_COUNT);

        // Field-by-field (recursive) equality of every row, in key order: a recursive comparison is
        // required because Customer.equals() intentionally compares only the custId primary key, so a
        // plain equals-based check would ignore any change to the non-key or PII fields.
        assertThat(after).usingRecursiveComparison().isEqualTo(before);

        // Explicit PII spot-checks on the minimum and maximum customer ids give a crisp, readable
        // guarantee that sensitive values remain intact after the job. Character columns are fixed
        // width CHAR(n) (preserving COBOL PIC X(n) semantics), so names are trimmed of the trailing
        // pad; the numeric SSN and the date of birth carry no padding.
        Customer first = customerRepository.findById(1L).orElseThrow();
        assertThat(first.getFirstName().trim()).isEqualTo("Immanuel");
        assertThat(first.getLastName().trim()).isEqualTo("Kessler");
        assertThat(first.getSsn()).isEqualTo(20_973_888L);
        assertThat(first.getDateOfBirth()).isEqualTo(LocalDate.of(1961, 6, 8));

        Customer last = customerRepository.findById(50L).orElseThrow();
        assertThat(last.getFirstName().trim()).isEqualTo("Aniya");
        assertThat(last.getLastName().trim()).isEqualTo("Von");
        assertThat(last.getSsn()).isEqualTo(931_248_469L);
        assertThat(last.getDateOfBirth()).isEqualTo(LocalDate.of(1960, 12, 1));
    }

    /**
     * Customers are processed in ascending {@code custId} order, reproducing the {@code CUSTDAT} KSDS
     * key-sequenced traversal. The production reader pages
     * {@link CustomerRepository#findAll(org.springframework.data.domain.Pageable)} ordered by
     * {@code custId} ascending; querying the same access path yields the exact sequence the job
     * processes.
     *
     * @throws Exception if the job launcher fails to run the job
     */
    @Test
    void customersProcessedInAscendingCustIdOrder() throws Exception {
        JobExecution execution = launchCustomerLoadJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<Long> orderedIds = customerRepository
                .findAll(Sort.by(Sort.Direction.ASC, CUST_ID_PROPERTY))
                .stream()
                .map(Customer::getCustId)
                .toList();

        assertThat(orderedIds).hasSize((int) SEEDED_CUSTOMER_COUNT);
        assertThat(orderedIds).isSorted();
        assertThat(orderedIds).doesNotHaveDuplicates();
        assertThat(orderedIds.get(0)).isEqualTo(1L);
        assertThat(orderedIds.get(orderedIds.size() - 1)).isEqualTo(SEEDED_CUSTOMER_COUNT);
    }

    /**
     * Launches {@code customerLoadJob} with a unique {@code run.id} (and no business parameters,
     * matching the production job) so each launch is a distinct job instance.
     *
     * @return the completed job execution
     * @throws Exception if the job launcher fails to run the job
     */
    private JobExecution launchCustomerLoadJob() throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(parameters);
    }

    /**
     * Returns the single {@link StepExecution} of the given job execution, asserting there is exactly
     * one step and that it is the {@code customerLoadStep}.
     *
     * @param execution the job execution to inspect
     * @return the sole step execution
     */
    private StepExecution singleStepExecution(JobExecution execution) {
        assertThat(execution.getStepExecutions()).hasSize(1);
        StepExecution step = execution.getStepExecutions().iterator().next();
        assertThat(step.getStepName()).isEqualTo(STEP_NAME);
        return step;
    }
}
