package com.carddemo.batch;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionTypeRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Batch integration test for {@link DataInitializationJobConfig} &mdash; the
 * {@code dataInitializationJob} bean that is the Java/PostgreSQL replacement for the composite
 * mainframe seed-loading sequence of the file-initialization JCL jobs.
 *
 * <h2>What the original JCL data-load jobs did</h2>
 * Each source JCL job ran the same IDCAMS pipeline against one VSAM dataset &mdash; close the CICS
 * file, {@code DELETE} the existing KSDS, {@code DEFINE} a fresh KSDS, {@code REPRO} the fixed-width
 * ASCII PS file into it, then reopen the CICS file. The ten source jobs map to the modernized job's
 * steps and fixtures as follows:
 * <table border="1">
 *   <caption>Source JCL &rarr; ASCII fixture &rarr; entity/table mapping</caption>
 *   <tr><th>Source JCL</th><th>Fixture (read-only, PR-27)</th><th>Records</th><th>Target table / entity</th></tr>
 *   <tr><td>{@code app/jcl/CUSTFILE.jcl}</td><td>{@code app/data/ASCII/custdata.txt}</td><td>50</td><td>{@code customers} / {@code Customer}</td></tr>
 *   <tr><td>{@code app/jcl/ACCTFILE.jcl}</td><td>{@code app/data/ASCII/acctdata.txt}</td><td>50</td><td>{@code accounts} / {@code Account}</td></tr>
 *   <tr><td>{@code app/jcl/CARDFILE.jcl}</td><td>{@code app/data/ASCII/carddata.txt}</td><td>50</td><td>{@code cards} / {@code Card}</td></tr>
 *   <tr><td>{@code app/jcl/XREFFILE.jcl}</td><td>{@code app/data/ASCII/cardxref.txt}</td><td>50</td><td>{@code card_xref} / {@code CardXref}</td></tr>
 *   <tr><td>{@code app/jcl/TRANTYPE.jcl}</td><td>{@code app/data/ASCII/trantype.txt}</td><td>7</td><td>{@code transaction_types} / {@code TransactionType}</td></tr>
 *   <tr><td>{@code app/jcl/TRANCATG.jcl}</td><td>{@code app/data/ASCII/trancatg.txt}</td><td>18</td><td>{@code transaction_categories} / {@code TransactionCategory}</td></tr>
 *   <tr><td>{@code app/jcl/DISCGRP.jcl}</td><td>{@code app/data/ASCII/discgrp.txt}</td><td>51</td><td>{@code disclosure_groups} / {@code DisclosureGroup}</td></tr>
 *   <tr><td>{@code app/jcl/TCATBALF.jcl}</td><td>{@code app/data/ASCII/tcatbal.txt}</td><td>50</td><td>{@code tran_cat_balances} / {@code TransactionCategoryBalance}</td></tr>
 *   <tr><td>{@code app/jcl/TRANFILE.jcl}</td><td>(no seed fixture)</td><td>0</td><td>{@code transactions} (populated by POSTTRAN; step is a no-op)</td></tr>
 *   <tr><td>{@code app/jcl/CBADMCDJ.jcl}</td><td>(n/a &mdash; CICS resource definitions)</td><td>0</td><td>no Java equivalent (REST controllers replace CICS routing)</td></tr>
 * </table>
 *
 * <h2>What the modernized job does (system under test)</h2>
 * The relational schema is created by the committed Flyway migrations
 * ({@code V1__schema.sql} + {@code V2__indexes.sql}); the {@code @Index} declarations replace the VSAM
 * alternate indexes, so the original {@code DEFINE}/{@code BLDINDEX} mechanics need no Java equivalent.
 * The job reuses the unchanged {@code app/data/ASCII/*.txt} fixtures as a programmatic seed path: each
 * of the nine {@code @Bean Step}s reads one fixture, parses every fixed-width line into the matching
 * JPA entity, and batch-persists it through the corresponding repository, in dependency-aware order
 * (PR-23: reference data first, then {@code Customer} &rarr; {@code Account} &rarr; {@code Card} &rarr;
 * {@code CardXref} &rarr; {@code TransactionCategoryBalance}, then the {@code Transaction} no-op).
 *
 * <h2>Idempotency guard &mdash; why this test clears the tables first</h2>
 * <p>The same master/reference data is <em>also</em> seeded declaratively by Flyway
 * ({@code V3__seed_reference_data.sql} + {@code V5__seed_master_data.sql}), which run at context
 * start. To stay safely re-runnable, every loading step in {@link DataInitializationJobConfig} is
 * guarded: if its target table already holds rows the step logs and returns without loading. Because
 * Flyway has already populated those tables when the context starts, this test's {@link #setUp()}
 * truncates each target table (in reverse foreign-key order) <em>before</em> launching the job, so the
 * job performs the fixture seed afresh and the post-run row counts reflect exactly what the job
 * loaded from {@code app/data/ASCII/*.txt} &mdash; not what Flyway seeded.</p>
 *
 * <p><strong>Fixture source resolution.</strong> {@code DataInitializationJobConfig} resolves each
 * fixture by its {@code *.txt} name on the classpath ({@code fixtures/<name>.txt}) first and falls
 * back to the repository-root filesystem path ({@code app/data/ASCII/<name>.txt}). The test classpath
 * carries only {@code *.csv} mirrors (e.g. {@code fixtures/custdata.csv}), so the {@code *.txt}
 * classpath lookup misses and the job loads the canonical {@code app/data/ASCII/*.txt} fixtures &mdash;
 * which is why the asserted counts match those fixtures' line counts (50/50/50/50/7/18/51/50).</p>
 *
 * <h2>Test topology</h2>
 * <p>{@code @SpringBootTest} boots the full application context; {@code @SpringBatchTest} contributes
 * {@link JobLauncherTestUtils} / {@link JobRepositoryTestUtils}. A real PostgreSQL 15 database is
 * supplied by Testcontainers (AAP &sect;0.5.1), Flyway applies every {@code V*.sql} migration, and
 * Hibernate validates the schema against the committed DDL. The job runs against production wiring
 * rather than mocks.</p>
 *
 * <p><strong>Datasource note.</strong> The {@code @DynamicPropertySource} below binds this class's
 * dedicated {@link #POSTGRES} container and explicitly overrides
 * {@code spring.datasource.driver-class-name} to {@code org.postgresql.Driver}. The override is
 * mandatory because {@code application-test.yml} configures the Testcontainers
 * {@code ContainerDatabaseDriver} (which only accepts {@code jdbc:tc:} magic URLs); the plain
 * {@code jdbc:postgresql://} URL returned by {@link PostgreSQLContainer#getJdbcUrl()} must be handled
 * by the real PostgreSQL driver. This mirrors the canonical pattern in
 * {@code com.carddemo.batch.UserSeedingJobIT} and {@code com.carddemo.batch.TransactionConsolidationJobIT}.</p>
 *
 * <p><strong>JobLauncher note.</strong> The context holds two {@link JobLauncher} beans &mdash;
 * Spring Boot's auto-configured synchronous {@code jobLauncher} and {@code BatchConfig}'s non-primary
 * {@code asyncJobLauncher} &mdash; so {@code @SpringBatchTest}'s own {@code @Autowired(required = false)}
 * setter silently skips injecting one into {@link JobLauncherTestUtils}. The synchronous launcher is
 * therefore resolved by bean name into the {@link #jobLauncher} field and installed explicitly in
 * {@link #setUp()} so the job runs inline and its committed results are visible to the post-run
 * assertions.</p>
 *
 * <p><strong>Transactionality.</strong> The class is intentionally <em>not</em> {@code @Transactional}:
 * the seeding job must commit so the synchronous launcher (and the post-run repository queries)
 * observe the inserted rows. {@link #setUp()} clears Spring Batch metadata and truncates every target
 * table before each test to guarantee isolation.</p>
 *
 * @see DataInitializationJobConfig
 * @see com.carddemo.batch.reader.AsciiFixedWidthItemReader
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("Data Initialization composite Job — 9-step ASCII-fixture-to-DB seed")
class DataInitializationJobIT {

    // ------------------------------------------------------------------------
    // Expected invariants (counts derived from the app/data/ASCII/*.txt fixtures)
    // ------------------------------------------------------------------------

    /** Registry/bean name of the job under test (matches {@code DataInitializationJobConfig.JOB_NAME}). */
    private static final String EXPECTED_JOB_NAME = "dataInitializationJob";

    /** Number of {@code @Bean Step}s chained by {@code dataInitializationJob()} (PR-23 load order). */
    private static final int EXPECTED_STEP_COUNT = 9;

    /** Customer rows seeded from {@code custdata.txt} (CUSTFILE.jcl). */
    private static final long EXPECTED_CUSTOMER_COUNT = 50L;

    /** Account rows seeded from {@code acctdata.txt} (ACCTFILE.jcl). */
    private static final long EXPECTED_ACCOUNT_COUNT = 50L;

    /** Card rows seeded from {@code carddata.txt} (CARDFILE.jcl). */
    private static final long EXPECTED_CARD_COUNT = 50L;

    /** Card cross-reference rows seeded from {@code cardxref.txt} (XREFFILE.jcl). */
    private static final long EXPECTED_CARD_XREF_COUNT = 50L;

    /** Transaction-type reference rows seeded from {@code trantype.txt} (TRANTYPE.jcl). */
    private static final long EXPECTED_TRANSACTION_TYPE_COUNT = 7L;

    /** Transaction-category reference rows seeded from {@code trancatg.txt} (TRANCATG.jcl). */
    private static final long EXPECTED_TRANSACTION_CATEGORY_COUNT = 18L;

    /** Disclosure-group reference rows seeded from {@code discgrp.txt} (DISCGRP.jcl, 3 blocks of 17). */
    private static final long EXPECTED_DISCLOSURE_GROUP_COUNT = 51L;

    /**
     * Transaction-category-balance rows seeded from {@code tcatbal.txt} (TCATBALF.jcl). The fixture
     * contains 50 records; although AAP &sect;0.4.1.1 mentions "100 TCATBAL records", the canonical
     * fixture has 50 lines and the loader persists one entity per line, so the verified count is 50.
     */
    private static final long EXPECTED_TCATBAL_COUNT = 50L;

    // ------------------------------------------------------------------------
    // Testcontainers PostgreSQL 15 (AAP §0.5.1 — PostgreSQL 15 baseline)
    // ------------------------------------------------------------------------

    /**
     * Dedicated PostgreSQL 15 container for the seed-loading job. {@code @SuppressWarnings("resource")}
     * is applied because the {@code @Container}/{@code @Testcontainers} lifecycle (not a
     * try-with-resources block) owns container startup and shutdown.
     */
    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15"))
            .withDatabaseName("carddemo_datainit_test")
            .withUsername("carddemo")
            .withPassword("test_password");

    /**
     * Binds the container's JDBC coordinates onto the Spring {@code Environment} before the application
     * context starts. The {@code driver-class-name} override is mandatory (see class Javadoc) so the
     * plain {@code jdbc:postgresql://} URL is handled by the real PostgreSQL JDBC driver rather than the
     * Testcontainers magic-URL driver configured in {@code application-test.yml}.
     *
     * @param registry the dynamic property registry supplied by the Spring TestContext framework
     */
    @DynamicPropertySource
    static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    // ------------------------------------------------------------------------
    // Injected collaborators
    // ------------------------------------------------------------------------

    /** Spring Batch test helper used to launch the job and inspect its {@link JobExecution}. */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /** Cleans Spring Batch metadata ({@code BATCH_*} tables) between tests for isolation. */
    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    /**
     * The <em>synchronous</em> {@link JobLauncher} that drives the job under test. Two
     * {@link JobLauncher} beans exist (Spring Boot's auto-configured synchronous {@code jobLauncher}
     * and {@code BatchConfig}'s non-primary {@code asyncJobLauncher}); naming this field
     * {@code jobLauncher} resolves it by bean name to the synchronous one, which is installed onto
     * {@link JobLauncherTestUtils} in {@link #setUp()} so the job runs inline and its committed results
     * are visible to the assertions.
     */
    @Autowired
    private JobLauncher jobLauncher;

    /** The system under test &mdash; selected by bean name so the correct {@link Job} is exercised. */
    @Autowired
    @Qualifier("dataInitializationJob")
    private Job dataInitializationJob;

    /** Repository used to reset and count the {@code customers} table. */
    @Autowired
    private CustomerRepository customerRepository;

    /** Repository used to reset and count the {@code accounts} table. */
    @Autowired
    private AccountRepository accountRepository;

    /** Repository used to reset and count the {@code cards} table. */
    @Autowired
    private CardRepository cardRepository;

    /** Repository used to reset and count the {@code card_xref} table. */
    @Autowired
    private CardXrefRepository cardXrefRepository;

    /** Repository used to reset and count the {@code transaction_types} table. */
    @Autowired
    private TransactionTypeRepository transactionTypeRepository;

    /** Repository used to reset and count the {@code transaction_categories} table. */
    @Autowired
    private TransactionCategoryRepository transactionCategoryRepository;

    /** Repository used to reset and count the {@code disclosure_groups} table. */
    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    /** Repository used to reset and count the {@code tran_cat_balances} table. */
    @Autowired
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    // ------------------------------------------------------------------------
    // Per-test setup
    // ------------------------------------------------------------------------

    /**
     * Resets all state the job interacts with before each test:
     * <ul>
     *   <li>{@code jobRepositoryTestUtils.removeJobExecutions()} clears prior Spring Batch executions so
     *       each test launches a clean job instance;</li>
     *   <li>every target table is emptied in <em>reverse</em> foreign-key order
     *       ({@code tran_cat_balances} &rarr; {@code card_xref} &rarr; {@code cards} &rarr;
     *       {@code accounts} &rarr; {@code customers} &rarr; {@code transaction_categories} &rarr;
     *       {@code transaction_types} &rarr; {@code disclosure_groups}). This is safe because the
     *       {@code transactions} table stays empty (its load step is a no-op and Flyway does not seed
     *       it), so no surviving row references the {@code cards} being deleted. Clearing the tables
     *       (which Flyway pre-seeds) lets the guarded job perform the fixture seed afresh;</li>
     *   <li>{@code jobLauncherTestUtils.setJobLauncher(jobLauncher)} installs the synchronous launcher
     *       explicitly (see the {@link #jobLauncher} field Javadoc &mdash; {@code @SpringBatchTest} cannot
     *       auto-resolve it because two {@link JobLauncher} beans exist);</li>
     *   <li>{@code jobLauncherTestUtils.setJob(...)} points the launcher at the data-initialization job
     *       (the field is not autowired by {@code @SpringBatchTest} and must be set explicitly).</li>
     * </ul>
     */
    @BeforeEach
    void setUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        transactionCategoryBalanceRepository.deleteAll();
        cardXrefRepository.deleteAll();
        cardRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();
        transactionCategoryRepository.deleteAll();
        transactionTypeRepository.deleteAll();
        disclosureGroupRepository.deleteAll();
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJob(dataInitializationJob);
    }

    // ------------------------------------------------------------------------
    // Helper methods
    // ------------------------------------------------------------------------

    /**
     * Builds a unique {@link JobParameters} set so every launch creates a fresh {@code JobInstance}
     * (avoiding {@link org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException}
     * when the same logical job is exercised by several test methods). The {@code run.id} parameter is
     * the only job parameter; {@code DataInitializationJobConfig} reads none, so it is purely an
     * identity discriminator.
     *
     * <p><strong>Why this returns {@link JobParameters}, not {@link JobExecution}.</strong>
     * {@code @SpringBatchTest} registers a {@code JobScopeTestExecutionListener} that, during
     * {@code prepareTestInstance} (i.e. <em>before</em> {@link #setUp()} runs), scans the test class
     * for <em>any</em> method whose return type is {@link JobExecution} and eagerly invokes it to seed
     * a job-scope context. A launch helper returning {@link JobExecution} would therefore be invoked
     * before {@link #setUp()} installs the launcher, yielding an {@code IllegalArgumentException}
     * wrapping a {@code NullPointerException} ("{@code getJobLauncher() is null}"). Returning
     * {@link JobParameters} keeps this helper invisible to that listener; each test instead launches
     * explicitly via {@code jobLauncherTestUtils.launchJob(uniqueJobParameters())} from within the test
     * body, after {@link #setUp()} has installed both the launcher and the job under test.
     *
     * @return a unique {@link JobParameters} instance
     */
    private JobParameters uniqueJobParameters() {
        return new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
    }

    /**
     * Returns the index of the first step name (case-insensitive) that contains {@code substring}, or
     * {@code -1} if none match. Used by the step-ordering assertion to locate steps robustly without
     * depending on the exact, full step-bean names.
     *
     * @param stepNames the ordered list of executed step names
     * @param substring the case-insensitive fragment to search for (e.g. {@code "customer"})
     * @return the zero-based index of the first matching step name, or {@code -1}
     */
    private static int indexOfStep(List<String> stepNames, String substring) {
        String needle = substring.toLowerCase(Locale.ROOT);
        for (int i = 0; i < stepNames.size(); i++) {
            if (stepNames.get(i).toLowerCase(Locale.ROOT).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------

    /**
     * Test 1 &mdash; the composite job completes successfully and every one of its nine seed steps
     * reports {@link ExitStatus#COMPLETED}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("Should complete all seed steps with ExitStatus.COMPLETED")
    void shouldCompleteAll9StepsSuccessfully() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueJobParameters());

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(execution.getStepExecutions())
                .as("the composite job must execute its nine dependency-ordered seed steps")
                .hasSize(EXPECTED_STEP_COUNT);
        for (StepExecution step : execution.getStepExecutions()) {
            assertThat(step.getExitStatus())
                    .as("Step %s should complete successfully", step.getStepName())
                    .isEqualTo(ExitStatus.COMPLETED);
        }
    }

    /**
     * Test 2 &mdash; the customer step (CUSTFILE.jcl) loads exactly 50 rows from {@code custdata.txt}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("Should load 50 customer records from custdata.txt")
    void shouldLoad50CustomersFromCustdataTxt() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueJobParameters());

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(customerRepository.count())
                .as("customers seeded from custdata.txt")
                .isEqualTo(EXPECTED_CUSTOMER_COUNT);
    }

    /**
     * Test 3 &mdash; the account step (ACCTFILE.jcl) loads exactly 50 rows from {@code acctdata.txt}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("Should load 50 account records from acctdata.txt")
    void shouldLoad50AccountsFromAcctdataTxt() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueJobParameters());

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(accountRepository.count())
                .as("accounts seeded from acctdata.txt")
                .isEqualTo(EXPECTED_ACCOUNT_COUNT);
    }

    /**
     * Test 4 &mdash; the card step (CARDFILE.jcl) loads exactly 50 rows from {@code carddata.txt}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("Should load 50 card records from carddata.txt")
    void shouldLoad50CardsFromCarddataTxt() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueJobParameters());

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(cardRepository.count())
                .as("cards seeded from carddata.txt")
                .isEqualTo(EXPECTED_CARD_COUNT);
    }

    /**
     * Test 5 &mdash; the cross-reference step (XREFFILE.jcl) loads exactly 50 rows from
     * {@code cardxref.txt}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("Should load 50 card cross-references from cardxref.txt")
    void shouldLoad50CardXrefsFromCardxrefTxt() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueJobParameters());

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(cardXrefRepository.count())
                .as("card cross-references seeded from cardxref.txt")
                .isEqualTo(EXPECTED_CARD_XREF_COUNT);
    }

    /**
     * Test 6 &mdash; the transaction-type step (TRANTYPE.jcl) loads exactly 7 reference rows from
     * {@code trantype.txt}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("Should load 7 transaction types from trantype.txt")
    void shouldLoad7TransactionTypesFromTrantypeTxt() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueJobParameters());

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(transactionTypeRepository.count())
                .as("transaction types seeded from trantype.txt")
                .isEqualTo(EXPECTED_TRANSACTION_TYPE_COUNT);
    }

    /**
     * Test 7 &mdash; the transaction-category step (TRANCATG.jcl) loads exactly 18 reference rows from
     * {@code trancatg.txt}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("Should load 18 transaction categories from trancatg.txt")
    void shouldLoad18TransactionCategoriesFromTrancatgTxt() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueJobParameters());

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(transactionCategoryRepository.count())
                .as("transaction categories seeded from trancatg.txt")
                .isEqualTo(EXPECTED_TRANSACTION_CATEGORY_COUNT);
    }

    /**
     * Test 8 &mdash; the disclosure-group step (DISCGRP.jcl) loads exactly 51 reference rows from
     * {@code discgrp.txt} (three blocks of 17 entries, including the {@code DEFAULT} fallback group
     * used by the interest-calculation lookup, PR-02).
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("Should load 51 disclosure groups (3 blocks × 17 entries incl. DEFAULT) from discgrp.txt")
    void shouldLoad51DisclosureGroupsFromDiscgrpTxt() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueJobParameters());

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(disclosureGroupRepository.count())
                .as("disclosure groups seeded from discgrp.txt")
                .isEqualTo(EXPECTED_DISCLOSURE_GROUP_COUNT);
    }

    /**
     * Test 9 &mdash; the category-balance step (TCATBALF.jcl) loads exactly 50 rows from
     * {@code tcatbal.txt}. The canonical fixture has 50 lines and the loader persists one entity per
     * line, so the verified count is 50 (see {@link #EXPECTED_TCATBAL_COUNT}).
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("Should load 50 transaction category balances from tcatbal.txt")
    void shouldLoadTransactionCategoryBalancesFromTcatbalTxt() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueJobParameters());

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(transactionCategoryBalanceRepository.count())
                .as("transaction category balances seeded from tcatbal.txt")
                .isEqualTo(EXPECTED_TCATBAL_COUNT);
    }

    /**
     * Test 10 &mdash; the steps execute in dependency-aware order (PR-23): customers must be seeded
     * before the card cross-references (which carry the {@code cust_id} foreign key) and before the
     * accounts. The executed step names are collected in execution order (sorted by their
     * monotonically increasing {@code StepExecution} id, which the {@code JobRepository} assigns as each
     * step starts) and the relative positions are asserted with case-insensitive name matching.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("Customer step should execute before account and card-xref steps (FK dependency)")
    void shouldEnforceStepOrderingForFKDependencies() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueJobParameters());

        List<String> stepNames = execution.getStepExecutions().stream()
                .sorted(Comparator.comparing(StepExecution::getId))
                .map(StepExecution::getStepName)
                .toList();

        int customersIdx = indexOfStep(stepNames, "customer");
        int accountsIdx = indexOfStep(stepNames, "account");
        int xrefIdx = indexOfStep(stepNames, "xref");

        assertThat(customersIdx)
                .as("a customer-loading step must be present in %s", stepNames)
                .isGreaterThanOrEqualTo(0);
        assertThat(xrefIdx)
                .as("a card-xref-loading step must be present in %s", stepNames)
                .isGreaterThanOrEqualTo(0);
        assertThat(customersIdx)
                .as("customers must be seeded before card cross-references (XREF carries the cust_id FK)")
                .isLessThan(xrefIdx);
        if (accountsIdx >= 0) {
            assertThat(customersIdx)
                    .as("customers must be seeded before accounts per the documented load order")
                    .isLessThan(accountsIdx);
        }
    }

    /**
     * Test 11 &mdash; the injected job is the {@code dataInitializationJob} bean (its
     * {@link Job#getName()} equals the name {@code DataInitializationJobConfig} registers).
     */
    @Test
    @DisplayName("Job under test must be the 'dataInitializationJob' bean")
    void shouldHaveJobNameDataInitializationJob() {
        assertThat(dataInitializationJob.getName()).isEqualTo(EXPECTED_JOB_NAME);
    }
}
