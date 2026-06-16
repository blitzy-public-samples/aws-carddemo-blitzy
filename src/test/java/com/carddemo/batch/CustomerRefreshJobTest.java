package com.carddemo.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.carddemo.repository.CustomerRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @SpringBatchTest} integration test for the Spring Batch <strong>{@code customerRefreshJob}</strong>,
 * the Java reinterpretation of the legacy COBOL VSAM dump/print utility {@code app/cbl/CBCUS01C.cbl}
 * ("Read and print customer data file").
 *
 * <h2>Parity goal (AAP &sect;0.2.1.3, &sect;0.4.1.5, &sect;0.2.2.1)</h2>
 * <p>{@code CBCUS01C} opens the {@code CUSTFILE} VSAM KSDS {@code INPUT}-only and walks every record in
 * primary-key ({@code CUST-ID}) order via a {@code READ ... NEXT}/{@code DISPLAY} loop until end-of-file,
 * then closes the file. The source contains <strong>no</strong> {@code WRITE}, {@code REWRITE}, or
 * {@code DELETE} verb &mdash; it is strictly read-only and side-effect-free. The migrated
 * {@code CustomerRefreshJobConfig} preserves that contract: a single chunk-oriented step streams the
 * {@code customers} table in {@code custId} order and logs only the identifier of each record, mutating
 * nothing. This test pins both halves of that contract:</p>
 * <ol>
 *   <li>the job <em>reads every</em> seeded customer and completes successfully, and</li>
 *   <li>the job mutates <em>no</em> customer row (the read-and-print invariant).</li>
 * </ol>
 *
 * <h2>Canonical multi-job scaffold</h2>
 * <p>This is the simplest of the five batch tests and establishes the canonical
 * {@link JobLauncherTestUtils} scaffold the other four reuse. Because the application context defines
 * several {@link Job} beans, {@link JobLauncherTestUtils} cannot auto-select one; the specific job is
 * bound explicitly in {@link #setUp()} via {@link JobLauncherTestUtils#setJob(Job)} after being injected
 * with a mandatory {@link Qualifier @Qualifier("customerRefreshJob")} to disambiguate among the
 * {@code Job} beans.</p>
 *
 * <h2>Test fixture</h2>
 * <p>The {@code test} profile (see {@code src/test/resources/application-test.yml}) runs against an
 * in-memory H2 database in PostgreSQL-compatibility mode, applying the Flyway migrations
 * {@code V1__schema.sql .. V4__seed_users.sql} on context startup. {@code V3__seed_master.sql} seeds
 * exactly {@value #EXPECTED_CUSTOMER_COUNT} customers (byte-exact from {@code app/data/ASCII/custdata.txt}),
 * so {@link CustomerRepository#count()} is {@value #EXPECTED_CUSTOMER_COUNT} and the step's read count must
 * match. Spring Batch jobs do not auto-run ({@code spring.batch.job.enabled=false}); this test launches the
 * job explicitly.</p>
 *
 * <h2>Cross-cutting rules honored</h2>
 * <ul>
 *   <li><strong>No {@code @Transactional}</strong> on the class or any method &mdash; a surrounding test
 *       transaction would corrupt Spring Batch metadata and the job's own commit semantics.</li>
 *   <li><strong>No {@code @DirtiesContext}</strong> &mdash; the job is read-only and commits no domain
 *       mutation, so the shared context is safe to reuse.</li>
 *   <li><strong>PII suppression</strong> &mdash; the test only counts rows and inspects step metadata; it
 *       never reads or asserts on SSN, government id, or any other personally identifiable field.</li>
 *   <li><strong>AssertJ only</strong> &mdash; all assertions use {@code assertThat(...)}, matching the
 *       sibling root tests.</li>
 * </ul>
 *
 * @see CustomerRefreshJobConfig
 * @see CustomerRepository
 * @see <a href="file:app/cbl/CBCUS01C.cbl">app/cbl/CBCUS01C.cbl</a>
 */
@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
class CustomerRefreshJobTest {

    /**
     * The number of customers seeded by Flyway {@code V3__seed_master.sql}, derived byte-exact from the
     * 50-record fixed-width fixture {@code app/data/ASCII/custdata.txt}. Both the repository count and the
     * step read/write counts are pinned to this value. Declared as a {@code long} so it can be compared
     * directly against {@link CustomerRepository#count()} and the {@code long}-typed
     * {@link StepExecution#getReadCount()} / {@link StepExecution#getWriteCount()}.
     */
    private static final long EXPECTED_CUSTOMER_COUNT = 50L;

    /** The exact step name declared by {@code CustomerRefreshJobConfig#customerRefreshStep}. */
    private static final String CUSTOMER_REFRESH_STEP_NAME = "customerRefreshStep";

    /**
     * Auto-registered by {@link SpringBatchTest @SpringBatchTest}; drives explicit job launches. The
     * specific job to launch is bound in {@link #setUp()} because the context holds multiple {@link Job}
     * beans and {@code setJob} is a plain setter (no autowiring), so a multi-job context loads cleanly.
     */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /** Auto-registered by {@link SpringBatchTest @SpringBatchTest}; clears batch metadata between runs. */
    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    /**
     * The job under test, disambiguated among the context's {@link Job} beans by its exact bean name.
     * The {@link Qualifier @Qualifier} is mandatory: without it Spring raises
     * {@code NoUniqueBeanDefinitionException}.
     */
    @Autowired
    @Qualifier("customerRefreshJob")
    private Job customerRefreshJob;

    /** Repository used to count customers before/after the run, proving the read-only invariant. */
    @Autowired
    private CustomerRepository customerRepository;

    /**
     * Binds the specific job to launch and clears any batch metadata from a prior run so each test starts
     * from a clean {@code JobInstance}/{@code JobExecution} state.
     */
    @BeforeEach
    void setUp() {
        jobLauncherTestUtils.setJob(customerRefreshJob);
        jobRepositoryTestUtils.removeJobExecutions();
    }

    /**
     * Verifies the refresh job reads every seeded customer and completes successfully.
     *
     * <p>Asserts the Flyway seed count, launches the job with unique parameters (so the
     * {@code JobInstance} is re-runnable across tests), and then asserts the overall {@link BatchStatus}
     * and {@link ExitStatus} are {@code COMPLETED}, that exactly one step ran, that it is the
     * {@code customerRefreshStep}, and that it read and "wrote" all {@value #EXPECTED_CUSTOMER_COUNT}
     * records. The write count equals the read count because the persistence-free logging writer still
     * receives every item even though it stores nothing.</p>
     *
     * @throws Exception if launching the job fails (declared by {@link JobLauncherTestUtils#launchJob})
     */
    @Test
    @DisplayName("customerRefreshJob reads all 50 seeded customers and completes")
    void customerRefreshJob_readsAllCustomers_andCompletes() throws Exception {
        long before = customerRepository.count();
        assertThat(before).isEqualTo(EXPECTED_CUSTOMER_COUNT);

        JobExecution execution =
                jobLauncherTestUtils.launchJob(jobLauncherTestUtils.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());

        assertThat(execution.getStepExecutions()).hasSize(1);
        StepExecution step = execution.getStepExecutions().iterator().next();
        assertThat(step.getStepName()).isEqualTo(CUSTOMER_REFRESH_STEP_NAME);
        assertThat(step.getReadCount()).isEqualTo(EXPECTED_CUSTOMER_COUNT);
        // The logging writer receives every chunked item (and so increments the write count) even though
        // it persists nothing — faithful to the COBOL DISPLAY-only behavior.
        assertThat(step.getWriteCount()).isEqualTo(EXPECTED_CUSTOMER_COUNT);
    }

    /**
     * Verifies the refresh job is strictly read-only: it mutates no customer row.
     *
     * <p>This is the parity-critical invariant for a "refresh/dump" job. {@code CBCUS01C} contains no
     * {@code WRITE}/{@code REWRITE}/{@code DELETE}, so the migrated job must leave the {@code customers}
     * table exactly as it found it. The test captures the row count, runs the job to a {@code COMPLETED}
     * status, and asserts the count is unchanged.</p>
     *
     * @throws Exception if launching the job fails (declared by {@link JobLauncherTestUtils#launchJob})
     */
    @Test
    @DisplayName("customerRefreshJob is read-only and does not mutate the customers table")
    void customerRefreshJob_isReadOnly_doesNotMutateCustomers() throws Exception {
        long before = customerRepository.count();

        JobExecution execution =
                jobLauncherTestUtils.launchJob(jobLauncherTestUtils.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(customerRepository.count()).isEqualTo(before);
    }
}
