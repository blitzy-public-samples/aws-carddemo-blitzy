package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.DisclosureGroup;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.DisclosureGroupRepository;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.repository.TransactionRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-concurrency regression gate for the interest-calculation account write-back
 * (QA finding&nbsp;<strong>F-03</strong>). It proves, against a live Testcontainers PostgreSQL&nbsp;18
 * database, that {@link InterestCalcJobConfig} no longer <em>silently loses</em> a committed
 * concurrent balance change while it performs its read-modify-write of an account row.
 *
 * <p><strong>The defect this locks down.</strong> The COBOL {@code CBACT04C} interest program reads an
 * account ({@code 1100-GET-ACCT-DATA}), accumulates interest across its category rows, then rewrites
 * the account balance on the control break ({@code 1050-UPDATE-ACCOUNT}). The Java migration must hold
 * a record lock across that read-modify-write exactly as the CICS/VSAM {@code READ ... UPDATE}
 * &rarr; {@code REWRITE} did. F-03 found that {@code getAccountData} used the <em>unlocked</em>
 * {@code AccountRepository.findById}; under the job's single {@code READ COMMITTED} tasklet transaction
 * a concurrent committed writer's change was overwritten (a classic lost update) with no error &mdash;
 * the exact silent-corruption class the pessimistic lock exists to prevent. The fix switches
 * {@code getAccountData} to {@link AccountRepository#findByIdForUpdate} ({@code SELECT ... FOR UPDATE}),
 * mirroring {@code PostTransactionJobConfig} (decision-log&nbsp;#52).</p>
 *
 * <p><strong>Why genuine threads and no {@code @Transactional} boundary.</strong> A lost update can
 * only be observed when two transactions run <em>simultaneously</em> on <em>separate</em> connections
 * and both <em>commit</em>. Modelled on {@code ConcurrencyParityIT}, this class extends
 * {@link AbstractPostgresIntegrationTest} (real HikariCP pool + {@link PlatformTransactionManager};
 * database reset to the pristine Flyway seed before each test) and declares <em>no</em>
 * {@code @Transactional} rollback &mdash; committed state is the whole point.</p>
 *
 * <p><strong>Deterministic interleaving (no flaky sleeps deciding correctness).</strong> The race is
 * choreographed with two {@link CountDownLatch latches} so the outcome is decided by the lock, not by
 * timing:</p>
 * <ol>
 *   <li>a background <em>external writer</em> opens its own transaction, issues
 *       {@code UPDATE account SET curr_bal = curr_bal + 100} on the race account (taking and
 *       <em>holding</em> the row lock, change uncommitted), signals {@code externalWriterHoldsLock},
 *       then parks awaiting {@code externalWriterMayCommit};</li>
 *   <li>the {@code interestCalcJob} is launched on a second thread; when it reaches the race account it
 *       must wait for that row &mdash; with the fix, at the {@code SELECT ... FOR UPDATE}; with the bug,
 *       later at its blind write-back &mdash; so the external writer's {@code +100} is <em>always</em>
 *       already in flight before the job's own read-modify-write resolves;</li>
 *   <li>the external writer is released and commits {@code +100}; the job then completes.</li>
 * </ol>
 * With the lock the job reads the post-commit balance and preserves the {@code +100}
 * (final = base&nbsp;+&nbsp;100). Without the lock the job read the stale balance and its blind
 * {@code UPDATE} overwrites the committed {@code +100} (final = base &mdash; the {@code +100} lost). The
 * fixed-vs-buggy assertion therefore genuinely fails without the fix.</p>
 *
 * <p><strong>Parity guardrails preserved.</strong> Both arranged accounts use a disclosure interest
 * rate of {@code 0.00}, so the job posts <em>no</em> interest and accumulates nothing: this isolates
 * the assertion to the concurrent {@code +100} alone and touches none of the decimal-truncation logic
 * that {@code InterestCalcJobConfigIT} gates (AAP&nbsp;&sect;0.6.1). The race account is the
 * <em>non-final</em> account in key order (so it <em>is</em> written back on the control break), while
 * a higher-keyed sentinel account is the final one (preserving the final-account "not written" quirk).
 * The fix adds only a lock; it changes neither the interest arithmetic nor the final-account
 * behaviour.</p>
 *
 * <p><strong>No deadlock.</strong> The race account has the minimum key, so the job reaches it first
 * while holding no other account lock; the external writer holds only that one row and waits solely on
 * the test latch. There is no lock cycle in either the fixed or the buggy code path.</p>
 *
 * @see InterestCalcJobConfig
 * @see InterestCalcJobConfigIT
 * @see AccountRepository#findByIdForUpdate(Long)
 * @see com.aws.carddemo.repository.ConcurrencyParityIT
 */
@SpringBootTest(
        classes = {InterestCalcConcurrencyIT.BatchSliceConfig.class, InterestCalcConcurrencyIT.HarnessConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // Prometheus export disabled (as in InterestCalcJobConfigIT): the slice uses a SimpleMeterRegistry
        // so Spring Batch's duplicate spring.batch.job.active meter never trips a Prometheus collision WARN.
        properties = {
            "spring.jpa.hibernate.ddl-auto=none",
            "management.prometheus.metrics.export.enabled=false"
        })
class InterestCalcConcurrencyIT extends AbstractPostgresIntegrationTest {

    /** COBOL {@code PARM-DATE} job-parameter name consumed by {@code InterestCalcJobConfig}. */
    private static final String PROC_DATE_PARAM = "processingDate";

    /** The {@code INTCALC.jcl} {@code PARM='2022071800'} value; the 10-char {@code TRAN-ID} prefix. */
    private static final String PROC_DATE = "2022071800";

    /** Disclosure/account group whose (type, category) interest rate is {@code 0.00} (no interest posts). */
    private static final String RACE_GROUP = "RACEGRP001";

    /**
     * The contended account. It has the <em>minimum</em> key of the two arranged accounts, so the
     * key-ordered scan reaches it first (it is therefore the <em>non-final</em> account and <em>is</em>
     * written back on the control break &mdash; the write that the race must not lose).
     */
    private static final long RACE_ACCT_ID = 90000000801L;

    /**
     * A higher-keyed sentinel so {@link #RACE_ACCT_ID} is not the last account in scan order. Being the
     * final account, its balance is intentionally never rewritten (the preserved final-account quirk).
     */
    private static final long FINAL_ACCT_ID = 90000000802L;

    private static final String RACE_CARD = "9000000000000801";
    private static final String FINAL_CARD = "9000000000000802";

    /** Seed balance of the race account before the concurrent change. */
    private static final BigDecimal RACE_BASE_BALANCE = new BigDecimal("1000.00");

    /** Seed balance of the sentinel (final) account; never rewritten, asserted unchanged. */
    private static final BigDecimal FINAL_BASE_BALANCE = new BigDecimal("500.00");

    /** The committed concurrent change the job must preserve rather than silently overwrite. */
    private static final BigDecimal CONCURRENT_DELTA = new BigDecimal("100.00");

    /** Zero interest rate so the interest computation is skipped and only the {@code +100} race matters. */
    private static final String ZERO_RATE = "0.00";

    /**
     * Time the main thread lets the launched job run before releasing the external writer. It only has
     * to exceed the few milliseconds the job needs to reach the race account and begin waiting on the
     * row; correctness is enforced by the row lock and the latches, not by this value.
     */
    private static final long JOB_REACHES_ROW_MILLIS = 1_500L;

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Autowired
    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Drives the external writer's standalone transaction on its own pooled connection. */
    private TransactionTemplate txTemplate;

    /**
     * Minimal batch-and-persistence slice (identical in shape to {@code InterestCalcJobConfigIT}):
     * enables Spring Boot auto-configuration (DataSource, JPA/Hibernate, Flyway, the Spring Batch
     * infrastructure, and {@link JdbcTemplate}), scans the domain entities and Spring Data
     * repositories, and imports the production {@link InterestCalcJobConfig} under test. It does not
     * component-scan the online/web tier, keeping this concurrency gate decoupled from unrelated beans.
     */
    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Account.class)
    @EnableJpaRepositories(basePackageClasses = AccountRepository.class)
    @Import(InterestCalcJobConfig.class)
    static class BatchSliceConfig {
    }

    /**
     * Contributes exactly one {@link JobLauncherTestUtils} pre-bound to the {@code interestCalcJob}
     * bean via an explicit {@link Qualifier}, avoiding the ambiguous by-type {@code Job} autowiring.
     */
    @TestConfiguration
    static class HarnessConfig {

        /**
         * @param jobLauncher     the auto-configured Spring Batch {@link JobLauncher} (synchronous)
         * @param jobRepository   the auto-configured Spring Batch {@link JobRepository}
         * @param interestCalcJob the {@code CBACT04C} job under test, selected by bean name
         * @return a {@link JobLauncherTestUtils} bound to {@code interestCalcJob}
         */
        @Bean
        JobLauncherTestUtils jobLauncherTestUtils(JobLauncher jobLauncher, JobRepository jobRepository,
                @Qualifier("interestCalcJob") Job interestCalcJob) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            utils.setJob(interestCalcJob);
            return utils;
        }
    }

    /**
     * Clears the {@code transaction} and {@code transaction_category_balance} tables (which the full
     * Flyway seed otherwise populates) so the job scans only the two accounts this test arranges, and
     * then arranges those two accounts. The superclass reset to the pristine seed runs first.
     */
    @BeforeEach
    void arrangeRaceScenario() {
        transactionRepository.deleteAll();
        categoryBalanceRepository.deleteAll();

        txTemplate = new TransactionTemplate(transactionManager);

        // Both accounts share a group whose (type, category) rate is 0.00 -> no interest is posted, so
        // the only balance change in play is the concurrent +100 the race must preserve.
        saveDisclosure(RACE_GROUP, "01", 5, ZERO_RATE);

        // RACE account has the smaller key (scanned first -> non-final -> written back on control break).
        saveAccount(RACE_ACCT_ID, RACE_GROUP, RACE_BASE_BALANCE);
        // FINAL account has the larger key (scanned last -> final -> intentionally not rewritten).
        saveAccount(FINAL_ACCT_ID, RACE_GROUP, FINAL_BASE_BALANCE);

        saveXref(RACE_CARD, RACE_ACCT_ID);
        saveXref(FINAL_CARD, FINAL_ACCT_ID);

        // One category-balance row per account so the scan yields a control break from RACE to FINAL.
        saveCatBal(RACE_ACCT_ID, "01", 5, "500.00");
        saveCatBal(FINAL_ACCT_ID, "01", 5, "500.00");
    }

    /**
     * Leaves the scanned tables empty after the test so a sibling test sharing the container starts
     * clean (the superclass also resets before the next test).
     */
    @AfterEach
    void cleanUpScannedTables() {
        transactionRepository.deleteAll();
        categoryBalanceRepository.deleteAll();
    }

    /**
     * The F-03 lost-update proof. A committed concurrent {@code +100} on the race account must survive
     * the interest job's read-modify-write of that same account. With the pessimistic write lock in
     * {@code getAccountData} the final balance is {@code base + 100}; without it the job's blind
     * write-back overwrites the {@code +100} and the final balance collapses to {@code base}.
     */
    @Test
    @DisplayName("F-03: interest job account write-back preserves a committed concurrent update (no lost update)")
    void interestCalcWriteBackDoesNotLoseConcurrentUpdate() throws Exception {
        BigDecimal base = accountRepository.findById(RACE_ACCT_ID).orElseThrow().getCurrBal();
        assertThat(base)
                .as("precondition: race account seeded at its base balance")
                .isEqualByComparingTo(RACE_BASE_BALANCE);

        CountDownLatch externalWriterHoldsLock = new CountDownLatch(1);
        CountDownLatch externalWriterMayCommit = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // (1) External writer: lock the race row with an uncommitted +100, then park until released.
            Future<?> externalWriter = pool.submit(() -> {
                try {
                    txTemplate.executeWithoutResult(status -> {
                        jdbcTemplate.update(
                                "UPDATE account SET acct_curr_bal = acct_curr_bal + ? WHERE acct_id = ?",
                                CONCURRENT_DELTA, RACE_ACCT_ID);
                        externalWriterHoldsLock.countDown();
                        awaitLatch(externalWriterMayCommit, 60);
                        // Returning normally commits the +100 (TransactionTemplate commit-on-return).
                    });
                } catch (Throwable ex) {
                    failures.add(ex);
                    // Ensure the main thread is never blocked waiting on a writer that already failed.
                    externalWriterHoldsLock.countDown();
                }
            });

            // Wait until the external writer actually holds the row lock before launching the job.
            assertThat(externalWriterHoldsLock.await(30, TimeUnit.SECONDS))
                    .as("external writer acquired the race-account row lock").isTrue();

            // (2) Launch the interest job on its own thread; the auto-configured launcher is synchronous,
            //     so the job runs on this worker and blocks on the contended row.
            Future<JobExecution> jobFuture = pool.submit(this::launchInterestJob);

            // Give the job time to reach the race account and begin waiting on the locked row. The lock +
            // latches (not this delay) decide correctness; it only ensures the job is genuinely in flight.
            Thread.sleep(JOB_REACHES_ROW_MILLIS);

            // (3) Release the external writer so it commits the +100; the job's read-modify-write resolves
            //     afterwards and must not lose it.
            externalWriterMayCommit.countDown();

            JobExecution execution = jobFuture.get(60, TimeUnit.SECONDS);
            externalWriter.get(30, TimeUnit.SECONDS);

            assertThat(failures).as("neither the external writer nor the job threw").isEmpty();
            assertThat(execution.getStatus())
                    .as("interest job completed normally").isEqualTo(BatchStatus.COMPLETED);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                    .as("worker pool terminated (no hung/deadlocked thread)").isTrue();
        }

        BigDecimal expected = base.add(CONCURRENT_DELTA);
        BigDecimal actual = accountRepository.findById(RACE_ACCT_ID).orElseThrow().getCurrBal();
        assertThat(actual)
                .as("committed concurrent +%s survived the interest job's account write-back "
                        + "(lost update would leave %s)", CONCURRENT_DELTA, base)
                .isEqualByComparingTo(expected);

        // The sentinel/final account is never rewritten (final-account quirk), so it must be unchanged.
        BigDecimal finalAcct = accountRepository.findById(FINAL_ACCT_ID).orElseThrow().getCurrBal();
        assertThat(finalAcct)
                .as("final account balance is untouched (final-account write-back quirk preserved)")
                .isEqualByComparingTo(FINAL_BASE_BALANCE);
    }

    // ---- arrange helpers -----------------------------------------------------------------------

    private void saveAccount(long acctId, String groupId, BigDecimal currBal) {
        Account account = new Account();
        account.setAcctId(acctId);
        account.setActiveStatus("Y");
        account.setGroupId(groupId);
        account.setCurrBal(currBal);
        account.setCurrCycCredit(new BigDecimal("0.00"));
        account.setCurrCycDebit(new BigDecimal("0.00"));
        accountRepository.save(account);
    }

    private void saveXref(String cardNum, long acctId) {
        cardXrefRepository.save(new CardXref(cardNum, 1L, acctId));
    }

    private void saveDisclosure(String group, String type, int cat, String rate) {
        DisclosureGroup disclosure = new DisclosureGroup();
        disclosure.setAcctGroupId(group);
        disclosure.setTranTypeCd(type);
        disclosure.setTranCatCd(cat);
        disclosure.setIntRate(new BigDecimal(rate));
        disclosureGroupRepository.save(disclosure);
    }

    private void saveCatBal(long acctId, String type, int cat, String balance) {
        TransactionCategoryBalance row = new TransactionCategoryBalance();
        row.setAcctId(acctId);
        row.setTypeCd(type);
        row.setCatCd(cat);
        row.setBalance(new BigDecimal(balance));
        categoryBalanceRepository.save(row);
    }

    // ---- act helpers ---------------------------------------------------------------------------

    private JobExecution launchInterestJob() throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addString(PROC_DATE_PARAM, PROC_DATE)
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(parameters);
    }

    /**
     * Awaits {@code latch} up to {@code seconds}, converting the checked {@link InterruptedException}
     * (not permitted inside the {@code TransactionTemplate} callback) into an unchecked failure so the
     * surrounding transaction rolls back and the worker records the error.
     */
    private static void awaitLatch(CountDownLatch latch, long seconds) {
        try {
            if (!latch.await(seconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out awaiting test latch after " + seconds + "s");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting test latch", ex);
        }
    }
}
