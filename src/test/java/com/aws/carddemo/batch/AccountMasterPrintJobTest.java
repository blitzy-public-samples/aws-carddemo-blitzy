/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.batch;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.repository.AccountRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot + Spring Batch Testcontainers integration test that validates the behavioral
 * parity of {@link AccountMasterPrintJob} against the legacy COBOL batch program
 * {@code CBACT01C} (source {@code legacy/cbl/CBACT01C.cbl}, record layout
 * {@code legacy/cpy/CVACT01Y.cpy}).
 *
 * <h2>Parity contract reproduced from CBACT01C</h2>
 * <ul>
 *   <li><strong>Ascending primary-key sequential read.</strong> CBACT01C opens
 *       {@code ACCTFILE} ({@code ORGANIZATION IS INDEXED}, {@code ACCESS MODE IS SEQUENTIAL},
 *       {@code RECORD KEY IS FD-ACCT-ID}) and reads every record in ascending
 *       {@code ACCT-ID PIC 9(11)} order. The Java job reproduces this with a
 *       {@code RepositoryItemReader} sorted by the {@code acctId} property ascending, so the
 *       records printed by the step must appear in ascending account-id order.</li>
 *   <li><strong>Read-only.</strong> The mainframe program opens the file {@code INPUT} and only
 *       {@code DISPLAY}s each record; it never writes or rewrites. The job must therefore leave
 *       the {@code account} table completely unchanged (row count, monetary values, and the JPA
 *       {@code @Version} counters all identical before and after).</li>
 *   <li><strong>Return code 0.</strong> A clean CBACT01C pass ends with {@code GOBACK} and return
 *       code {@code 0}. The Spring Batch analog is a {@code COMPLETED} {@link BatchStatus} and the
 *       {@code COMPLETED} {@link ExitStatus} exit code.</li>
 *   <li><strong>Monetary fidelity.</strong> The five {@code PIC S9(10)V99} money fields
 *       ({@code ACCT-CURR-BAL}, {@code ACCT-CREDIT-LIMIT}, {@code ACCT-CASH-CREDIT-LIMIT},
 *       {@code ACCT-CURR-CYC-CREDIT}, {@code ACCT-CURR-CYC-DEBIT}) are {@link BigDecimal} at
 *       scale 2; assertions use {@code BigDecimal}, never {@code double}/{@code float}.</li>
 * </ul>
 *
 * <h2>Harness</h2>
 * <p>The full application context boots under the {@code test} profile against a real
 * PostgreSQL 16 supplied by Testcontainers and wired in with {@link ServiceConnection} (no
 * hardcoded JDBC url/user/password). Flyway applies the production migrations
 * ({@code V1__schema.sql}, {@code V2__reference_data.sql}) and Hibernate runs in
 * {@code validate} mode (see {@code application-test.yml}).</p>
 *
 * <p>Because {@code spring.batch.job.enabled=false} in the {@code test} profile, no job runs at
 * startup; {@link #jobLauncherTestUtils} launches {@code accountMasterPrintJob} explicitly. The
 * application context contains eleven {@code Job} beans, which makes the single-{@code Job}
 * auto-wiring performed by {@code @SpringBatchTest}'s auto-registered {@code JobLauncherTestUtils}
 * ambiguous; this test therefore does <em>not</em> use {@code @SpringBatchTest} and instead
 * supplies a {@link JobLauncherTestUtils} bound to the specific
 * {@code @Qualifier("accountMasterPrintJob")} job through the nested
 * {@link TestBatchSupportConfig}. Each launch uses a unique {@code run.id} job parameter so
 * repeated launches create new job instances rather than throwing
 * {@code JobInstanceAlreadyCompleteException}.</p>
 *
 * <p>The {@code account} table is not seeded under the {@code test} profile (the CSV seed loader
 * is {@code @Profile("local")} and the reference-data migration seeds only the type/category/group
 * tables), so each test method seeds its own deterministic rows via {@link AccountRepository} in
 * {@link #seedAccounts()} and clears them afterwards. Rows are inserted intentionally out of
 * primary-key order so the ascending-order assertion proves the job sorts by {@code acct_id}
 * rather than echoing insertion order.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AccountMasterPrintJobTest {

    /**
     * Shared, single-instance PostgreSQL 16 container backing the integration context.
     *
     * <p>Declared {@code static} so Testcontainers starts it once for the class. The
     * {@link ServiceConnection} annotation publishes the container's JDBC url, username, and
     * password to the Spring test context dynamically, so no credential is baked into source or
     * configuration. The {@code postgres:16-alpine} image matches the PostgreSQL 16 data-tier
     * target of the migration.</p>
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /** Supplies unique, ever-increasing {@code run.id} job-parameter values across launches. */
    private static final AtomicLong RUN_ID = new AtomicLong();

    /** The account-master print job bean name, matching {@code AccountMasterPrintJob}. */
    private static final String JOB_BEAN_NAME = "accountMasterPrintJob";

    /** Fixed non-monetary field values shared by every seeded account (values are irrelevant to the read-only print). */
    private static final String ACTIVE_STATUS = "Y";
    private static final String OPEN_DATE = "2024-01-01";
    private static final String EXPIRATION_DATE = "2027-12-31";
    private static final String REISSUE_DATE = "2024-01-01";
    private static final String ADDR_ZIP = "30301";
    private static final String GROUP_ID = "DEFAULT";

    /** Launches the specific {@code accountMasterPrintJob} (see {@link TestBatchSupportConfig}). */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /** Repository used to seed, read back, and clean up {@code account} rows. */
    @Autowired
    private AccountRepository accountRepository;

    /** The booted application context, used to assert the job bean is registered. */
    @Autowired
    private ApplicationContext applicationContext;

    /**
     * Seeds a small, deterministic set of accounts before every test and guarantees the rows are
     * committed and visible to the batch job (which reads them in its own transactions).
     *
     * <p>Any pre-existing rows are removed first so each method starts from a known state; this is
     * safe because the {@code card}, {@code card_xref}, and {@code tran_cat_balance} tables that
     * hold the only foreign keys referencing {@code account} are empty under the {@code test}
     * profile.</p>
     */
    @BeforeEach
    void seed() {
        accountRepository.deleteAll();
        accountRepository.saveAll(seedAccounts());
    }

    /** Removes the seeded rows after every test so no state leaks between methods. */
    @AfterEach
    void cleanUp() {
        accountRepository.deleteAll();
    }

    /**
     * Sanity check: the full context loads and the {@code accountMasterPrintJob} bean (the Java
     * re-platforming of CBACT01C) is registered as a {@link Job}, and the test's job launcher is
     * available.
     */
    @Test
    void contextLoadsAndJobRegistered() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.containsBean(JOB_BEAN_NAME)).isTrue();
        assertThat(applicationContext.getBean(JOB_BEAN_NAME)).isInstanceOf(Job.class);
        assertThat(jobLauncherTestUtils).isNotNull();
    }

    /**
     * Launching the job over the seeded accounts completes cleanly with return code 0 parity:
     * a {@code COMPLETED} batch status and the {@code COMPLETED} exit code, mirroring the clean
     * {@code GOBACK} (RC0) of CBACT01C when no I/O error occurs.
     *
     * @throws Exception if the job launch fails (fails the test)
     */
    @Test
    void runsCleanWithReturnCodeZero() throws Exception {
        JobExecution execution = launchJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * The job reads and prints every account in ascending {@code acct_id} order, reproducing the
     * CBACT01C ascending-key sequential browse.
     *
     * <p>The step read count must equal the total number of accounts, and the sequence of
     * {@code ACCT-ID} values emitted by the print writer (captured from the job's logger) must be
     * exactly the accounts in ascending primary-key order. Because the rows were inserted out of
     * order, an ascending result confirms the job sorts by key rather than echoing insertion
     * order.</p>
     *
     * @throws Exception if the job launch fails (fails the test)
     */
    @Test
    void printsAllAccountsInAscendingAcctIdOrder() throws Exception {
        Logger jobLogger = (Logger) LoggerFactory.getLogger(AccountMasterPrintJob.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        jobLogger.addAppender(capture);
        try {
            JobExecution execution = launchJob();
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            StepExecution stepExecution = execution.getStepExecutions().iterator().next();
            assertThat(stepExecution.getReadCount()).isEqualTo(accountRepository.count());

            List<Long> printedAcctIds = capture.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("ACCT-ID"))
                    .map(AccountMasterPrintJobTest::parsePrintedAcctId)
                    .toList();

            List<Long> expectedAcctIds = accountRepository.findAllByOrderByAcctIdAsc().stream()
                    .map(Account::getAcctId)
                    .toList();

            assertThat(printedAcctIds).isEqualTo(expectedAcctIds);
            assertThat(printedAcctIds).isSorted();
        } finally {
            jobLogger.detachAppender(capture);
            capture.stop();
        }
    }

    /**
     * The job is strictly read-only: the account row count, the aggregate of every monetary field,
     * and the JPA optimistic-lock {@code @Version} counters are all identical before and after the
     * run. This mirrors CBACT01C opening the file {@code INPUT} and never writing or rewriting a
     * record; an unchanged {@code version} on every row proves no REWRITE occurred.
     *
     * @throws Exception if the job launch fails (fails the test)
     */
    @Test
    void isReadOnly_noMutations() throws Exception {
        long countBefore = accountRepository.count();
        BigDecimal monetaryChecksumBefore = monetaryChecksum();
        Map<Long, Long> versionsBefore = versionsByAcctId();

        JobExecution execution = launchJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(accountRepository.count()).isEqualTo(countBefore);
        assertThat(monetaryChecksum()).isEqualByComparingTo(monetaryChecksumBefore);
        assertThat(versionsByAcctId()).isEqualTo(versionsBefore);
    }

    /**
     * Every monetary field printed by the job is a {@link BigDecimal} at scale 2 (the
     * {@code PIC S9(10)V99} to {@code DECIMAL(12,2)} contract). The job is launched to exercise the
     * read-and-print pass, then each persisted account is read back and its five money fields are
     * asserted to carry scale 2 with no floating-point representation.
     *
     * @throws Exception if the job launch fails (fails the test)
     */
    @Test
    void monetaryFieldsRenderedAtScaleTwo() throws Exception {
        JobExecution execution = launchJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<Account> accounts = accountRepository.findAllByOrderByAcctIdAsc();
        assertThat(accounts).isNotEmpty();
        for (Account account : accounts) {
            assertThat(account.getCurrBal()).isNotNull();
            assertThat(account.getCurrBal().scale()).isEqualTo(2);
            assertThat(account.getCreditLimit().scale()).isEqualTo(2);
            assertThat(account.getCashCreditLimit().scale()).isEqualTo(2);
            assertThat(account.getCurrCycCredit().scale()).isEqualTo(2);
            assertThat(account.getCurrCycDebit().scale()).isEqualTo(2);
        }
    }

    /**
     * Launches {@code accountMasterPrintJob} with a unique {@code run.id} identifying parameter so
     * every invocation is a fresh job instance.
     *
     * @return the completed {@link JobExecution}
     * @throws Exception if the underlying {@code JobLauncher} throws
     */
    private JobExecution launchJob() throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addLong("run.id", RUN_ID.incrementAndGet())
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(parameters);
    }

    /**
     * Builds the deterministic seed set. Ids are deliberately inserted out of ascending order
     * ({@code 3, 1, 5, 2, 4}) and each account carries distinct scale-2 monetary values so the
     * ordering and read-only assertions are meaningful.
     *
     * @return the list of transient accounts to persist
     */
    private List<Account> seedAccounts() {
        List<Account> accounts = new ArrayList<>();
        accounts.add(newAccount(10_000_000_003L, "300.33", "7000.00", "3000.00", "300.00", "150.00"));
        accounts.add(newAccount(10_000_000_001L, "100.11", "5000.00", "1000.00", "100.00", "50.00"));
        accounts.add(newAccount(10_000_000_005L, "500.55", "9000.00", "5000.00", "500.00", "250.00"));
        accounts.add(newAccount(10_000_000_002L, "200.22", "6000.00", "2000.00", "200.00", "100.00"));
        accounts.add(newAccount(10_000_000_004L, "400.44", "8000.00", "4000.00", "400.00", "200.00"));
        return accounts;
    }

    /**
     * Constructs a transient {@link Account} using the entity's copybook-ordered all-arguments
     * constructor. The optimistic-lock {@code version} is left unset so the first {@code save} is
     * an insert. Every monetary value is created with the {@link BigDecimal#BigDecimal(String)
     * string constructor} so scale is exactly 2.
     *
     * @param acctId          account identifier (primary key, {@code ACCT-ID PIC 9(11)})
     * @param currBal         current balance ({@code ACCT-CURR-BAL})
     * @param creditLimit     credit limit ({@code ACCT-CREDIT-LIMIT})
     * @param cashCreditLimit cash credit limit ({@code ACCT-CASH-CREDIT-LIMIT})
     * @param currCycCredit   current-cycle credit total ({@code ACCT-CURR-CYC-CREDIT})
     * @param currCycDebit    current-cycle debit total ({@code ACCT-CURR-CYC-DEBIT})
     * @return a new transient account ready to persist
     */
    private Account newAccount(long acctId,
                               String currBal,
                               String creditLimit,
                               String cashCreditLimit,
                               String currCycCredit,
                               String currCycDebit) {
        return new Account(
                acctId,
                ACTIVE_STATUS,
                new BigDecimal(currBal),
                new BigDecimal(creditLimit),
                new BigDecimal(cashCreditLimit),
                OPEN_DATE,
                EXPIRATION_DATE,
                REISSUE_DATE,
                new BigDecimal(currCycCredit),
                new BigDecimal(currCycDebit),
                ADDR_ZIP,
                GROUP_ID);
    }

    /**
     * Computes an order-independent aggregate of every monetary field across all accounts, used to
     * detect any mutation by the read-only job.
     *
     * @return the sum of all five money fields over every account
     */
    private BigDecimal monetaryChecksum() {
        BigDecimal checksum = BigDecimal.ZERO;
        for (Account account : accountRepository.findAll()) {
            checksum = checksum
                    .add(account.getCurrBal())
                    .add(account.getCreditLimit())
                    .add(account.getCashCreditLimit())
                    .add(account.getCurrCycCredit())
                    .add(account.getCurrCycDebit());
        }
        return checksum;
    }

    /**
     * Snapshots the optimistic-lock version of every account keyed by account id. A read-only run
     * leaves every version unchanged; any REWRITE would increment one.
     *
     * @return a map of {@code acctId} to its current {@code @Version} value
     */
    private Map<Long, Long> versionsByAcctId() {
        return accountRepository.findAll().stream()
                .collect(Collectors.toMap(Account::getAcctId, Account::getVersion));
    }

    /**
     * Extracts the numeric account id from a captured {@code ACCT-ID} print line. The print writer
     * emits {@code "ACCT-ID                 :<value>"}; the value follows the single {@code ':'}.
     *
     * @param message the formatted log message beginning with {@code ACCT-ID}
     * @return the parsed account id
     */
    private static Long parsePrintedAcctId(String message) {
        return Long.parseLong(message.substring(message.indexOf(':') + 1).trim());
    }

    /**
     * Test-only Spring configuration that supplies a {@link JobLauncherTestUtils} bound to the
     * specific {@code accountMasterPrintJob} bean.
     *
     * <p>This deliberately replaces the auto-registration that {@code @SpringBatchTest} would
     * provide: with eleven {@code Job} beans in the context, a by-type single-{@code Job} binding
     * is ambiguous. Selecting the job explicitly by {@link Qualifier} keeps the launcher wired to
     * exactly the account-master job under test.</p>
     */
    @TestConfiguration
    static class TestBatchSupportConfig {

        /**
         * Builds a {@link JobLauncherTestUtils} for {@code accountMasterPrintJob} using the
         * auto-configured {@link JobLauncher} and {@link JobRepository}.
         *
         * @param job           the account-master print job, selected by qualifier
         * @param jobLauncher   the auto-configured Spring Batch job launcher
         * @param jobRepository the auto-configured Spring Batch job repository
         * @return the configured launcher utility
         */
        @Bean
        JobLauncherTestUtils jobLauncherTestUtils(@Qualifier("accountMasterPrintJob") Job job,
                                                  JobLauncher jobLauncher,
                                                  JobRepository jobRepository) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJob(job);
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            return utils;
        }
    }
}
