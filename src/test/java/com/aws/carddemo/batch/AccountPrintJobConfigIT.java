package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.repository.AccountRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
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
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Failsafe integration / parity test for {@link AccountPrintJobConfig}, verifying that the migrated
 * account-print Spring Batch job ({@code accountPrintJob}) reproduces the behavior of the mainframe
 * batch program {@code CBACT01C} orchestrated by the {@code READACCT} JCL job.
 *
 * <p><strong>Origin (read-only oracles).</strong> {@code legacy/cbl/CBACT01C.cbl} opens the VSAM
 * {@code ACCTDAT} KSDS for {@code INPUT} in paragraph {@code 0000-ACCTFILE-OPEN}, then in
 * {@code 1000-ACCTFILE-GET-NEXT} reads every {@code ACCOUNT-RECORD} sequentially by ascending
 * primary key ({@code ACCESS MODE IS SEQUENTIAL} on an {@code INDEXED} file) and, on
 * {@code FILE STATUS '00'}, prints it via {@code 1100-DISPLAY-ACCT-RECORD}; {@code FILE STATUS '10'}
 * is normal end-of-file and {@code 9000-ACCTFILE-CLOSE} closes the file. The program contains
 * <strong>no {@code WRITE}, {@code REWRITE} or {@code DELETE}</strong> &mdash; it is a pure read-only
 * print/dump job &mdash; and {@code legacy/jcl/READACCT.jcl} declares only {@code SYSOUT}/{@code SYSPRINT}
 * (no output data set). The Java job therefore streams every {@link Account} in ascending
 * {@code acctId} order and logs each, mutating nothing. See AAP &sect;0.4.1 (account file
 * read/print) and &sect;0.6.6 (deterministic ordering).</p>
 *
 * <p><strong>Harness.</strong> This test extends {@link AbstractPostgresIntegrationTest}, so it runs
 * against a real Testcontainers PostgreSQL instance materialized by the Flyway migrations (including
 * {@code V2__reference_data.sql}, which seeds the 50 legacy {@code acctdata.txt} accounts) with
 * Hibernate {@code ddl-auto=validate}. It deliberately does <strong>not</strong> use
 * {@code @SpringBatchTest}; instead the nested {@link BatchTestHarnessConfig} supplies exactly one
 * {@link JobLauncherTestUtils}, wired explicitly to the {@code accountPrintJob} bean, the
 * auto-configured {@link JobLauncher} and the {@link JobRepository}. Jobs do not auto-run at startup
 * ({@code spring.batch.job.enabled=false} in the {@code test} profile); this test launches the job
 * on demand with a unique {@code run.id} parameter.</p>
 *
 * <p><strong>Parity focus.</strong> The essence of a print/dump job is that it changes nothing, so
 * the zero-mutation guarantee ({@link #jobPerformsNoDatabaseMutations()}) is the most important
 * assertion here: the account row count and the account balances are byte-identical before and after
 * the run. All monetary comparisons use {@link BigDecimal} value comparison
 * ({@code isEqualByComparingTo}); floating-point types are never used for money.</p>
 *
 * <p><strong>Runtime prerequisite.</strong> A reachable Docker daemon is required so Testcontainers
 * can start {@code postgres:18-alpine} (see {@link AbstractPostgresIntegrationTest}).</p>
 *
 * <p><strong>Context slice (why not the whole application).</strong> The Spring context is pinned via
 * {@code @SpringBootTest(classes = }{@link BatchSliceConfig}{@code .class)} to a focused persistence +
 * batch slice: {@link EnableAutoConfiguration auto-configuration} (data source, JPA, Flyway and Spring
 * Batch), an {@link EntityScan @EntityScan} over the {@code domain} package, an
 * {@link EnableJpaRepositories @EnableJpaRepositories} over the {@code repository} package, and an
 * {@link Import @Import} of the production {@link AccountPrintJobConfig}. This mirrors the slice
 * approach the shared {@link AbstractPostgresIntegrationTest base class} explicitly anticipates for
 * batch tests, and deliberately does <em>not</em> component-scan the {@code service}/{@code web}
 * tiers: a read-only account-print job needs neither controllers nor the online menu services, and
 * pinning the slice keeps this parity test hermetic and independent of unrelated wiring in tiers it
 * does not exercise. The slice still loads <strong>every</strong> {@code domain} entity, so Hibernate
 * {@code validate} continues to assert the whole entity model against the real Flyway-materialised
 * schema. Pinning {@code classes} also makes configuration resolution deterministic (Spring never
 * ascends the package to auto-detect a {@code @SpringBootConfiguration}). Because an explicit
 * {@code classes} list suppresses auto-detection of nested {@code @TestConfiguration} classes, the
 * {@link BatchTestHarnessConfig} harness is named alongside {@link BatchSliceConfig} in the
 * {@code classes} list so its single {@link JobLauncherTestUtils} bean is registered.</p>
 *
 * @see AccountPrintJobConfig
 * @see AbstractPostgresIntegrationTest
 */
@SpringBootTest(classes = {
        AccountPrintJobConfigIT.BatchSliceConfig.class,
        AccountPrintJobConfigIT.BatchTestHarnessConfig.class})
class AccountPrintJobConfigIT extends AbstractPostgresIntegrationTest {

    /**
     * Number of account rows seeded by the Flyway {@code V2__reference_data.sql} migration, which
     * loads the 50 fixed-width records from the legacy {@code legacy/data/ASCII/acctdata.txt}
     * fixture (one row per {@code acctId} 1..50). Declared as {@code int} so it widens cleanly for
     * the {@code long} read/write-count and {@code count()} comparisons and satisfies
     * {@code List.hasSize(int)} without a narrowing cast.
     */
    private static final int EXPECTED_SEEDED_ACCOUNTS = 50;

    /**
     * The single {@link JobLauncherTestUtils} used to launch {@code accountPrintJob}. Supplied by
     * {@link BatchTestHarnessConfig}; there is exactly one such bean because {@code @SpringBatchTest}
     * (which would register its own) is intentionally not used.
     */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /** Repository over the account master, used for count / ordering / mutation assertions. */
    @Autowired
    private AccountRepository accountRepository;

    /**
     * Verifies the job runs to a clean, single-step completion, reading every seeded account.
     *
     * @throws Exception if the job launcher fails to run the job
     */
    @Test
    void jobCompletes_readsAllAccounts() throws Exception {
        JobExecution execution = launchPrintJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);

        StepExecution step = singleStep(execution);
        assertThat(step.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step.getReadCount())
                .as("every seeded account row is read (CBACT01C reads the whole ACCTDAT file)")
                .isEqualTo(EXPECTED_SEEDED_ACCOUNTS);
    }

    /**
     * Verifies the step read count equals the seeded account count, and that the write count equals
     * the read count.
     *
     * <p>The {@code accountPrintStep} has <strong>no processor</strong>, so every item the reader
     * emits is handed to the pure-logging {@code accountPrintWriter}; Spring Batch counts each such
     * item as written even though the writer performs no persistence. Hence
     * {@code writeCount == readCount == } the seeded count.</p>
     *
     * @throws Exception if the job launcher fails to run the job
     */
    @Test
    void readCountEqualsSeededAccountCount() throws Exception {
        // Seed precondition: Flyway V2 has loaded the 50 legacy acctdata.txt rows.
        long seeded = accountRepository.count();
        assertThat(seeded).isEqualTo(EXPECTED_SEEDED_ACCOUNTS);

        StepExecution step = singleStep(launchPrintJob());

        assertThat(step.getReadCount()).isEqualTo(seeded);
        assertThat(step.getReadCount()).isEqualTo(EXPECTED_SEEDED_ACCOUNTS);

        // No processor => no filtering => every read item reaches the logging writer.
        assertThat(step.getWriteCount()).isEqualTo(step.getReadCount());
    }

    /**
     * Core read-only parity guarantee: the print job mutates nothing.
     *
     * <p>{@code CBACT01C} opens {@code ACCTDAT} for {@code INPUT} only and issues no
     * {@code WRITE}/{@code REWRITE}/{@code DELETE}. This test snapshots the total account count and
     * the minimum- and maximum-{@code acctId} accounts before the run, launches the job, and asserts
     * the count is unchanged and both snapshot accounts are byte-identical afterwards &mdash; with all five
     * monetary fields compared by {@link BigDecimal} value ({@code isEqualByComparingTo}).</p>
     *
     * @throws Exception if the job launcher fails to run the job
     */
    @Test
    void jobPerformsNoDatabaseMutations() throws Exception {
        // --- Snapshot BEFORE the run ------------------------------------------------------------
        long countBefore = accountRepository.count();
        assertThat(countBefore).isEqualTo(EXPECTED_SEEDED_ACCOUNTS);

        List<Account> orderedBefore = accountRepository.findAll(Sort.by(Sort.Direction.ASC, "acctId"));
        assertThat(orderedBefore).hasSize(EXPECTED_SEEDED_ACCOUNTS);

        // Capture the min- and max-acctId accounts as immutable snapshots so the post-run comparison
        // reflects freshly re-loaded database state rather than a cached entity instance.
        AccountSnapshot minBefore = AccountSnapshot.of(orderedBefore.get(0));
        AccountSnapshot maxBefore = AccountSnapshot.of(orderedBefore.get(orderedBefore.size() - 1));

        // --- Run the read-only print job --------------------------------------------------------
        JobExecution execution = launchPrintJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // --- Assert ZERO mutations (the parity essence of a print/dump job) ----------------------
        assertThat(accountRepository.count())
                .as("account row count is unchanged by the read-only print job")
                .isEqualTo(countBefore);

        Account minAfter = accountRepository.findById(minBefore.acctId()).orElseThrow();
        Account maxAfter = accountRepository.findById(maxBefore.acctId()).orElseThrow();
        assertAccountUnchanged(minBefore, minAfter);
        assertAccountUnchanged(maxBefore, maxAfter);
    }

    /**
     * Verifies accounts are processed in strictly ascending {@code acctId} order, reproducing the
     * ascending primary-key sequential scan of the VSAM {@code ACCTDAT} KSDS.
     *
     * <p>The job completes (exercising the reader's {@code Sort.by("acctId")} ascending contract end
     * to end); the ordering oracle is {@link AccountRepository#findAll(Sort)} sorted ascending by
     * {@code acctId} &mdash; the exact query the production {@code RepositoryItemReader} pages through &mdash; so
     * its result order is authoritative for the order in which accounts are read and printed.</p>
     *
     * @throws Exception if the job launcher fails to run the job
     */
    @Test
    void accountsProcessedInAscendingAcctIdOrder() throws Exception {
        assertThat(launchPrintJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<Account> ordered = accountRepository.findAll(Sort.by(Sort.Direction.ASC, "acctId"));
        assertThat(ordered).hasSize(EXPECTED_SEEDED_ACCOUNTS);

        for (int i = 1; i < ordered.size(); i++) {
            long previous = ordered.get(i - 1).getAcctId();
            long current = ordered.get(i).getAcctId();
            assertThat(current)
                    .as("acctId is strictly ascending at index %d", i)
                    .isGreaterThan(previous);
        }
    }

    /**
     * Launches {@code accountPrintJob} with only a unique {@code run.id} parameter.
     *
     * <p>The job itself takes no business parameters; {@code run.id} (from {@link System#nanoTime()})
     * makes each launch a distinct {@code JobInstance} so repeated runs across test methods never
     * collide with an already-completed instance.</p>
     *
     * @return the completed {@link JobExecution}
     * @throws Exception if the job launcher fails to run the job
     */
    private JobExecution launchPrintJob() throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(parameters);
    }

    /**
     * Returns the job's single {@link StepExecution}, asserting the job has exactly one step
     * ({@code accountPrintStep}).
     *
     * @param execution the completed job execution
     * @return the single step execution
     */
    private static StepExecution singleStep(JobExecution execution) {
        Collection<StepExecution> steps = execution.getStepExecutions();
        assertThat(steps)
                .as("accountPrintJob defines exactly one step (accountPrintStep)")
                .hasSize(1);
        return steps.iterator().next();
    }

    /**
     * Asserts that a re-loaded {@link Account} matches its pre-run {@link AccountSnapshot}. All five
     * monetary fields are compared by {@link BigDecimal} value ({@code isEqualByComparingTo}, which
     * ignores scale differences); identifier, status, date and group fields are compared by value.
     *
     * @param before the immutable snapshot captured before the job ran
     * @param after  the account re-loaded from the database after the job ran
     */
    private static void assertAccountUnchanged(AccountSnapshot before, Account after) {
        assertThat(after.getAcctId()).isEqualTo(before.acctId());
        assertThat(after.getActiveStatus()).isEqualTo(before.activeStatus());
        assertThat(after.getCurrBal()).isEqualByComparingTo(before.currBal());
        assertThat(after.getCreditLimit()).isEqualByComparingTo(before.creditLimit());
        assertThat(after.getCashCreditLimit()).isEqualByComparingTo(before.cashCreditLimit());
        assertThat(after.getCurrCycCredit()).isEqualByComparingTo(before.currCycCredit());
        assertThat(after.getCurrCycDebit()).isEqualByComparingTo(before.currCycDebit());
        assertThat(after.getOpenDate()).isEqualTo(before.openDate());
        assertThat(after.getExpiraionDate()).isEqualTo(before.expiraionDate());
        assertThat(after.getReissueDate()).isEqualTo(before.reissueDate());
        assertThat(after.getGroupId()).isEqualTo(before.groupId());
    }

    /**
     * Immutable snapshot of an {@link Account}'s business fields, captured before the job runs so the
     * post-run comparison reflects freshly re-loaded database state. Every captured type
     * ({@link Long}, {@link String}, {@link BigDecimal}, {@link LocalDate}) is immutable.
     *
     * @param acctId          the account primary key
     * @param activeStatus    the active-status flag
     * @param currBal         the current balance
     * @param creditLimit     the total credit limit
     * @param cashCreditLimit the cash-advance credit limit
     * @param currCycCredit   the current-cycle credit total
     * @param currCycDebit    the current-cycle debit total
     * @param openDate        the account open date
     * @param expiraionDate   the account expiration date (COBOL misspelling preserved)
     * @param reissueDate     the account reissue date
     * @param groupId         the disclosure-group identifier
     */
    private record AccountSnapshot(
            Long acctId,
            String activeStatus,
            BigDecimal currBal,
            BigDecimal creditLimit,
            BigDecimal cashCreditLimit,
            BigDecimal currCycCredit,
            BigDecimal currCycDebit,
            LocalDate openDate,
            LocalDate expiraionDate,
            LocalDate reissueDate,
            String groupId) {

        /**
         * Captures an immutable snapshot of the given account.
         *
         * @param account the account to snapshot
         * @return a snapshot of the account's business fields
         */
        private static AccountSnapshot of(Account account) {
            return new AccountSnapshot(
                    account.getAcctId(),
                    account.getActiveStatus(),
                    account.getCurrBal(),
                    account.getCreditLimit(),
                    account.getCashCreditLimit(),
                    account.getCurrCycCredit(),
                    account.getCurrCycDebit(),
                    account.getOpenDate(),
                    account.getExpiraionDate(),
                    account.getReissueDate(),
                    account.getGroupId());
        }
    }

    /**
     * Primary context configuration for this parity test: a focused persistence + batch slice.
     *
     * <p>{@link EnableAutoConfiguration Auto-configuration} brings up the data source (bound to the
     * Testcontainers PostgreSQL by {@link AbstractPostgresIntegrationTest}), JPA/Hibernate, Flyway
     * (which applies {@code V0}&hellip;{@code V3}, seeding the 50 accounts) and Spring Batch (the
     * {@link JobRepository}/{@link JobLauncher} infrastructure, since the production configuration
     * deliberately omits {@code @EnableBatchProcessing} to rely on Boot's auto-configuration). The
     * {@link EntityScan @EntityScan} anchors on {@link Account} so the whole {@code domain} package is
     * validated against the Flyway schema, {@link EnableJpaRepositories @EnableJpaRepositories} anchors
     * on {@link AccountRepository} to expose the repository layer, and {@link Import @Import} pulls in
     * the production {@link AccountPrintJobConfig} under test. The {@code service}/{@code web} tiers are
     * intentionally not scanned (see the class-level Javadoc). This is a plain {@code @Configuration}
     * (not {@code @SpringBootConfiguration}) so it never participates in package-level primary-config
     * auto-detection; it is used only because it is named explicitly in {@code @SpringBootTest}.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Account.class)
    @EnableJpaRepositories(basePackageClasses = AccountRepository.class)
    @Import(AccountPrintJobConfig.class)
    static class BatchSliceConfig {
    }

    /**
     * Test-only harness registered <em>in addition</em> to the {@link BatchSliceConfig} slice context.
     * It supplies the single {@link JobLauncherTestUtils} used to launch the job under test.
     * {@code @SpringBatchTest} is deliberately not used; the utility is wired explicitly so exactly
     * one instance exists and it targets the {@code accountPrintJob} bean by qualifier.
     */
    @TestConfiguration
    static class BatchTestHarnessConfig {

        /**
         * Builds the {@link JobLauncherTestUtils} bound to {@code accountPrintJob}.
         *
         * @param jobLauncher     the Spring Boot auto-configured {@link JobLauncher}
         * @param jobRepository   the Spring Boot auto-configured {@link JobRepository}
         * @param accountPrintJob the job under test, selected by bean name
         * @return the configured {@link JobLauncherTestUtils}
         */
        @Bean
        JobLauncherTestUtils accountPrintJobLauncherTestUtils(
                JobLauncher jobLauncher,
                JobRepository jobRepository,
                @Qualifier("accountPrintJob") Job accountPrintJob) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            utils.setJob(accountPrintJob);
            return utils;
        }
    }
}
