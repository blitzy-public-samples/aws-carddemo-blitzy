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

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.aws.carddemo.batch.processor.DailyTransactionValidateProcessor;
import com.aws.carddemo.common.util.PanMasker;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.DailyTransactionRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * Spring Boot + Spring Batch Testcontainers integration test asserting the behavioral parity of
 * {@link DailyTransactionValidateJob} &mdash; the Java re-platform of the legacy COBOL batch
 * program {@code CBTRN01C.cbl} (daily-transaction <em>read + validate</em>; DALYTRAN record layout
 * copybook {@code CVTRA06Y.cpy}, {@code RECLN = 350}).
 *
 * <h2>Parity contract under test</h2>
 * <p>{@code CBTRN01C} opens the sequential {@code DALYTRAN} file and walks it front-to-back to
 * end-of-file ({@code MAIN-PARA}, L164-186). For each record it looks up the card cross-reference
 * by card number ({@code 2000-LOOKUP-XREF}) and, <em>only when that read succeeds</em>, reads the
 * owning account ({@code 3000-READ-ACCOUNT}); a missing cross-reference or account is a normal,
 * non-fatal {@code INVALID KEY} that is merely {@code DISPLAY}ed and skipped. The program issues
 * <strong>no {@code WRITE}, {@code REWRITE}, or {@code DELETE}</strong> &mdash; it is strictly
 * read-only &mdash; and always falls through to {@code GOBACK} with return code {@code 0}, even
 * when records reference a missing card or account. (The {@code Z-ABEND-PROGRAM} path, return code
 * {@code 8}, exists solely for genuine file-system I/O failures, which the Spring Batch
 * reader/framework surfaces, never the validation logic.)</p>
 *
 * <p>The Java target re-expresses this as the single-step {@code dailyTransactionValidateJob}: the
 * {@code dailyTransactionValidateStep} streams the {@code daily_transaction} staging table in
 * ascending surrogate-key order (the analog of the sequential file read), the
 * {@link DailyTransactionValidateProcessor} performs the read-only cross-reference and account
 * lookups (logging any anomaly, never throwing), and a no-op / logging writer persists nothing.
 * This test locks in the five observable guarantees that define parity:</p>
 * <ol>
 *   <li>the job bean {@code dailyTransactionValidateJob} is wired and registered;</li>
 *   <li>a clean run finishes {@link BatchStatus#COMPLETED} with the {@link ExitStatus#COMPLETED}
 *       exit code &mdash; the framework analog of the COBOL return code {@code 0} &mdash; even when
 *       some daily transactions reference a missing cross-reference;</li>
 *   <li>the step reads every input daily transaction (read count equals the staged row count);</li>
 *   <li>the run is strictly read-only: the {@code account}, {@code transaction}, and
 *       {@code tran_cat_balance} master tables are unchanged (row counts and balance checksums
 *       identical before and after) &mdash; this job neither posts nor rejects; and</li>
 *   <li>a daily transaction whose card has no cross-reference is logged and skipped (mirroring the
 *       COBOL {@code 'CARD NUMBER ... COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-'}
 *       {@code DISPLAY}) without failing the job or propagating an exception.</li>
 * </ol>
 *
 * <h2>Harness</h2>
 * <p>The test boots the full application context under the {@code test} profile against a real
 * PostgreSQL&nbsp;16 provisioned by Testcontainers and wired through {@link ServiceConnection}, so
 * the datasource carries no hardcoded URL or credentials (AAP 0.8.1 / 0.9.3). Flyway applies the
 * production migrations and Hibernate validates the entity mappings against that schema (per
 * {@code application-test.yml}); {@code spring.batch.job.enabled=false} keeps the job from running
 * at startup, so it is launched explicitly through {@link JobLauncherTestUtils}.</p>
 *
 * <p>Because the application declares many {@link Job} beans, the {@link JobLauncherTestUtils}
 * auto-registered by {@link SpringBatchTest} cannot resolve a unique job. The nested
 * {@link BatchTestConfig} therefore contributes a {@link Primary @Primary}
 * {@link JobLauncherTestUtils} whose job is bound explicitly by {@link Qualifier} to
 * {@code dailyTransactionValidateJob}, so this test always launches the correct job.</p>
 *
 * <p>The application data tables are empty under the {@code test} profile (the bulk seed loader is
 * {@code local}-only), so each test seeds its own small, deterministic fixture: foreign-key parents
 * (customer, account, card, and a couple of posted transactions and category balances) via
 * {@link JdbcTemplate}; cross-reference rows via the in-scope {@link CardXrefRepository}; and staged
 * daily transactions via the in-scope {@link DailyTransactionRepository}. Reference tables
 * ({@code transaction_type}, {@code transaction_category}) are supplied by the Flyway migration and
 * are never mutated here.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@SpringBatchTest
@Testcontainers
class DailyTransactionValidateJobTest {

    /**
     * Shared, single-instance PostgreSQL&nbsp;16 container for the whole test class.
     *
     * <p>{@link Container} on a {@code static} field starts it once for all methods, and
     * {@link ServiceConnection} publishes its JDBC coordinates to Spring Boot's auto-configured
     * datasource with no hardcoded credentials. The {@code postgres:16-alpine} image matches the
     * PostgreSQL&nbsp;16 production target and is resolved locally, so no network pull is required.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /** Name of the {@link Job} bean under test, as declared by {@link DailyTransactionValidateJob}. */
    private static final String JOB_NAME = "dailyTransactionValidateJob";

    /** Name of the single chunk-oriented {@code Step} of {@link #JOB_NAME}. */
    private static final String STEP_NAME = "dailyTransactionValidateStep";

    /** Transaction type code present in the Flyway reference data ({@code transaction_type}). */
    private static final String TYPE_CD = "01";

    /** Transaction category code present in the Flyway reference data ({@code transaction_category}). */
    private static final int CAT_CD = 1;

    /**
     * 16-character, zero-padded card numbers that DO have a cross-reference (and therefore a valid
     * account) seeded per test. Each maps 1:1 to the customer/account with the same ordinal id.
     */
    private static final List<String> VALID_CARD_NUMBERS = List.of(
            "0000000000000001",
            "0000000000000002",
            "0000000000000003",
            "0000000000000004",
            "0000000000000005");

    /**
     * A 16-character card number deliberately absent from {@code card_xref}. A staged daily
     * transaction carrying it exercises the COBOL {@code 2000-LOOKUP-XREF} {@code INVALID KEY}
     * branch: the record is logged and skipped, and the job still completes with return code 0.
     */
    private static final String MISSING_CARD_NUMBER = "9999999999999999";

    /**
     * Number of staged daily transactions seeded per test: one per {@link #VALID_CARD_NUMBERS}
     * entry plus one carrying {@link #MISSING_CARD_NUMBER}. The step must read exactly this many.
     */
    private static final int EXPECTED_DAILY_TRANSACTION_COUNT = VALID_CARD_NUMBERS.size() + 1;

    /** Per-account current balances seeded so the read-only balance checksum is non-trivial. */
    private static final List<BigDecimal> ACCOUNT_BALANCES = List.of(
            new BigDecimal("100.00"),
            new BigDecimal("200.00"),
            new BigDecimal("300.00"),
            new BigDecimal("400.00"),
            new BigDecimal("500.00"));

    /** Credit limit applied to every seeded account (value is irrelevant to the read-only pass). */
    private static final BigDecimal ACCOUNT_CREDIT_LIMIT = new BigDecimal("5000.00");

    /** Category balances seeded into {@code tran_cat_balance} for the read-only checksum. */
    private static final BigDecimal CAT_BALANCE_ACCT_1 = new BigDecimal("500.00");
    private static final BigDecimal CAT_BALANCE_ACCT_2 = new BigDecimal("750.00");

    /** Amounts of the two posted {@code transaction} rows seeded for the read-only checksum. */
    private static final BigDecimal POSTED_TRAN_AMT_1 = new BigDecimal("12.34");
    private static final BigDecimal POSTED_TRAN_AMT_2 = new BigDecimal("56.78");

    /** Marker substring emitted by the processor when a card cross-reference cannot be verified. */
    private static final String COULD_NOT_VERIFY_MARKER = "could not be verified";

    /** Global source of unique {@code run.id} job-parameter values across all launches. */
    private static final AtomicLong RUN_ID = new AtomicLong();

    /** Explicitly-bound test launcher for {@code dailyTransactionValidateJob} (see {@link BatchTestConfig}). */
    private final JobLauncherTestUtils jobLauncherTestUtils;

    /** Utility used to clear Spring Batch metadata between launches. */
    private final JobRepositoryTestUtils jobRepositoryTestUtils;

    /** In-scope repository used to seed and count staged daily transactions. */
    private final DailyTransactionRepository dailyTransactionRepository;

    /** In-scope repository used to seed the card cross-reference rows the processor looks up. */
    private final CardXrefRepository cardXrefRepository;

    /** In-scope repository used to count {@code account} rows for the read-only snapshot. */
    private final AccountRepository accountRepository;

    /** Plain JDBC used to seed and clean foreign-key parents and to compute balance checksums. */
    private final JdbcTemplate jdbcTemplate;

    /** The job bean under test, injected by qualifier for the registration assertion. */
    private final Job dailyTransactionValidateJob;

    /** Logback logger of the validation processor; the target the {@link #logAppender} attaches to. */
    private Logger processorLogger;

    /** In-memory appender capturing the processor's log lines for one test method. */
    private ListAppender<ILoggingEvent> logAppender;

    /**
     * Constructor injection of all collaborators (the project-wide convention; JUnit's
     * {@code spring.test.constructor.autowire.mode=all} autowires every parameter). The
     * {@link JobLauncherTestUtils} resolves to the {@link Primary @Primary} bean from
     * {@link BatchTestConfig}; the {@link Job} is disambiguated by {@link Qualifier}.
     *
     * @param jobLauncherTestUtils       the explicitly-bound launcher for {@code dailyTransactionValidateJob}
     * @param jobRepositoryTestUtils     the batch-metadata cleanup helper
     * @param dailyTransactionRepository the staging-table repository under exercise
     * @param cardXrefRepository         the cross-reference repository used to seed lookups
     * @param accountRepository          the account repository used for the read-only row count
     * @param jdbcTemplate               plain JDBC used for foreign-key parents and balance checksums
     * @param dailyTransactionValidateJob the job bean under test, bound by qualifier
     */
    DailyTransactionValidateJobTest(JobLauncherTestUtils jobLauncherTestUtils,
                                    JobRepositoryTestUtils jobRepositoryTestUtils,
                                    DailyTransactionRepository dailyTransactionRepository,
                                    CardXrefRepository cardXrefRepository,
                                    AccountRepository accountRepository,
                                    JdbcTemplate jdbcTemplate,
                                    @Qualifier(JOB_NAME) Job dailyTransactionValidateJob) {
        this.jobLauncherTestUtils = jobLauncherTestUtils;
        this.jobRepositoryTestUtils = jobRepositoryTestUtils;
        this.dailyTransactionRepository = dailyTransactionRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.dailyTransactionValidateJob = dailyTransactionValidateJob;
    }

    /**
     * Resets batch metadata, clears any leftover fixture, seeds a fresh deterministic fixture, and
     * attaches a capturing appender to the processor's logger so each test method is fully
     * independent.
     */
    @BeforeEach
    void setUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        cleanFixtures();
        seedFixtures();
        attachLogCapture();
    }

    /**
     * Detaches the capturing appender, removes the seeded fixture, and clears batch metadata so no
     * state leaks into the next test method.
     */
    @AfterEach
    void tearDown() {
        detachLogCapture();
        cleanFixtures();
        jobRepositoryTestUtils.removeJobExecutions();
    }

    // ------------------------------------------------------------------------
    // Test methods
    // ------------------------------------------------------------------------

    /**
     * The context wires together and the {@code dailyTransactionValidateJob} bean is registered and
     * bound to the launcher. Reaching this assertion transitively proves the full context (all
     * layers, the Flyway migration, and Hibernate schema validation) started successfully.
     */
    @Test
    @DisplayName("context loads and the dailyTransactionValidateJob bean is registered")
    void contextLoadsAndJobRegistered() {
        assertThat(jobLauncherTestUtils).isNotNull();
        assertThat(dailyTransactionValidateJob).isNotNull();
        assertThat(dailyTransactionValidateJob.getName()).isEqualTo(JOB_NAME);
        assertThat(jobLauncherTestUtils.getJob()).isSameAs(dailyTransactionValidateJob);
    }

    /**
     * A clean validation pass finishes {@link BatchStatus#COMPLETED} with the
     * {@link ExitStatus#COMPLETED} exit code &mdash; the framework analog of the COBOL
     * {@code GOBACK} with return code {@code 0} &mdash; and propagates no failure exception, even
     * though the staged input includes a record whose card has no cross-reference. This reproduces
     * CBTRN01C's guarantee that a missing reference is a non-fatal, logged anomaly.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("runs clean and finishes COMPLETED (return code 0) despite a missing cross-reference")
    void runsCleanWithReturnCodeZero() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(execution.getAllFailureExceptions()).isEmpty();
    }

    /**
     * The step reads every staged daily transaction exactly once &mdash; its read count equals the
     * {@code daily_transaction} row count &mdash; reproducing the CBTRN01C sequential walk of the
     * {@code DALYTRAN} file to end-of-file. The staged amount is additionally asserted to round-trip
     * as a {@link BigDecimal} at scale&nbsp;2, honoring the decimal-fidelity rule (never
     * {@code double}/{@code float}) for the {@code DALYTRAN-AMT} {@code PIC S9(09)V99} field.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("reads every staged daily transaction (step read count equals the staged row count)")
    void readsAllDailyTransactions() throws Exception {
        long stagedRows = dailyTransactionRepository.count();
        assertThat(stagedRows).isEqualTo(EXPECTED_DAILY_TRANSACTION_COUNT);

        // Monetary fidelity: DALYTRAN-AMT round-trips as BigDecimal at scale 2 (never double/float).
        BigDecimal firstAmount =
                dailyTransactionRepository.findAllByOrderByIdAsc().get(0).getTranAmt();
        assertThat(firstAmount).isNotNull();
        assertThat(firstAmount.scale()).isEqualTo(2);

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(stepReadCount(execution, STEP_NAME)).isEqualTo(stagedRows);
    }

    /**
     * The validate job is strictly read-only: the {@code account}, {@code transaction}, and
     * {@code tran_cat_balance} master tables are unchanged after the run &mdash; identical row
     * counts and identical balance checksums before and after. This is the parity guard that
     * distinguishes CBTRN01C (validate) from CBTRN02C (posting), which <em>would</em> update
     * account and category balances and insert posted transactions. The fixture is deliberately
     * non-trivial (non-empty tables with non-zero balances) so the assertion would catch any
     * accidental posting, rejection, or balance mutation.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("is read-only: leaves account, transaction, and tran_cat_balance unchanged")
    void isReadOnly_noMutationsToMasterData() throws Exception {
        MasterDataSnapshot before = captureMasterData();

        // Preconditions: the snapshot is meaningful (a posting run would change every one of these).
        assertThat(before.accountCount()).isEqualTo(VALID_CARD_NUMBERS.size());
        assertThat(before.accountBalanceSum()).isEqualByComparingTo(expectedAccountBalanceSum());
        assertThat(before.transactionCount()).isEqualTo(2L);
        assertThat(before.transactionAmountSum())
                .isEqualByComparingTo(POSTED_TRAN_AMT_1.add(POSTED_TRAN_AMT_2));
        assertThat(before.tranCatBalanceCount()).isEqualTo(2L);
        assertThat(before.tranCatBalanceSum())
                .isEqualByComparingTo(CAT_BALANCE_ACCT_1.add(CAT_BALANCE_ACCT_2));

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        MasterDataSnapshot after = captureMasterData();
        assertThat(after.accountCount()).isEqualTo(before.accountCount());
        assertThat(after.accountBalanceSum()).isEqualByComparingTo(before.accountBalanceSum());
        assertThat(after.transactionCount()).isEqualTo(before.transactionCount());
        assertThat(after.transactionAmountSum()).isEqualByComparingTo(before.transactionAmountSum());
        assertThat(after.tranCatBalanceCount()).isEqualTo(before.tranCatBalanceCount());
        assertThat(after.tranCatBalanceSum()).isEqualByComparingTo(before.tranCatBalanceSum());
    }

    /**
     * A staged daily transaction whose card number has no cross-reference is logged and skipped,
     * not failed: the job still completes with {@link BatchStatus#COMPLETED} / return code&nbsp;0,
     * no exception propagates, the record is still read by the step, and no master data is mutated.
     * The processor emits the anomaly (mirroring the COBOL
     * {@code 'CARD NUMBER ... COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-'} {@code DISPLAY}),
     * which is captured from its logger and asserted.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("a daily transaction with a missing cross-reference is logged and skipped, not failed")
    void missingReferenceIsLoggedNotFailed() throws Exception {
        // Precondition: the missing-reference record truly has no cross-reference to resolve.
        assertThat(cardXrefRepository.findById(MISSING_CARD_NUMBER)).isEmpty();

        MasterDataSnapshot before = captureMasterData();

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());

        // Validation-only semantics: COMPLETED / RC0 and no propagated exception despite the anomaly.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(execution.getAllFailureExceptions()).isEmpty();

        // The anomalous record was still read (the reader emits it; the processor skips it),
        // and nothing was posted, rejected, or mutated.
        assertThat(stepReadCount(execution, STEP_NAME)).isEqualTo(EXPECTED_DAILY_TRANSACTION_COUNT);
        MasterDataSnapshot after = captureMasterData();
        assertThat(after.transactionCount()).isEqualTo(before.transactionCount());
        assertThat(after.accountBalanceSum()).isEqualByComparingTo(before.accountBalanceSum());
        assertThat(after.tranCatBalanceSum()).isEqualByComparingTo(before.tranCatBalanceSum());

        // The processor logged the missing cross-reference (COBOL 2000-LOOKUP-XREF INVALID KEY).
        // The PAN is masked in the operational log (PCI-DSS first-6/last-4; decision log D34), so the
        // captured line carries PanMasker.mask(...) of the card number rather than the raw PAN.
        assertThat(messagesMentioning(PanMasker.mask(MISSING_CARD_NUMBER), COULD_NOT_VERIFY_MARKER))
                .isNotEmpty();
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    /**
     * Seeds a small, deterministic fixture: foreign-key parents and read-only master data via plain
     * {@link JdbcTemplate}, cross-reference rows via the in-scope {@link CardXrefRepository}, and
     * staged daily transactions via the in-scope {@link DailyTransactionRepository}.
     *
     * <p>Five daily transactions carry a card number that resolves to a cross-reference (and hence a
     * valid account); one carries {@link #MISSING_CARD_NUMBER}, which does not. The
     * {@code transaction_type} and {@code transaction_category} reference rows required by the
     * {@code transaction}/{@code tran_cat_balance} foreign keys are supplied by the Flyway migration
     * and are never seeded or removed here.</p>
     */
    private void seedFixtures() {
        // Foreign-key parents and read-only master data (customer, account) with non-zero balances.
        for (int i = 0; i < VALID_CARD_NUMBERS.size(); i++) {
            long id = i + 1L;
            jdbcTemplate.update("INSERT INTO customer (cust_id) VALUES (?)", id);
            jdbcTemplate.update(
                    "INSERT INTO account (acct_id, curr_bal, credit_limit, curr_cyc_credit, "
                            + "curr_cyc_debit) VALUES (?, ?, ?, ?, ?)",
                    id, ACCOUNT_BALANCES.get(i), ACCOUNT_CREDIT_LIMIT, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        // A card row so the posted transactions below satisfy their foreign key to card(card_num).
        jdbcTemplate.update("INSERT INTO card (card_num, acct_id) VALUES (?, ?)",
                VALID_CARD_NUMBERS.get(0), 1L);

        // Two posted transactions: read-only master data a posting run (CBTRN02C) would ADD to.
        jdbcTemplate.update(
                "INSERT INTO transaction (tran_id, type_cd, cat_cd, card_num, tran_amt) "
                        + "VALUES (?, ?, ?, ?, ?)",
                "TRAN000000000001", TYPE_CD, CAT_CD, VALID_CARD_NUMBERS.get(0), POSTED_TRAN_AMT_1);
        jdbcTemplate.update(
                "INSERT INTO transaction (tran_id, type_cd, cat_cd, card_num, tran_amt) "
                        + "VALUES (?, ?, ?, ?, ?)",
                "TRAN000000000002", TYPE_CD, CAT_CD, VALID_CARD_NUMBERS.get(0), POSTED_TRAN_AMT_2);

        // Two category balances: read-only master data a posting/interest run would UPDATE.
        jdbcTemplate.update(
                "INSERT INTO tran_cat_balance (acct_id, type_cd, cat_cd, bal) VALUES (?, ?, ?, ?)",
                1L, TYPE_CD, CAT_CD, CAT_BALANCE_ACCT_1);
        jdbcTemplate.update(
                "INSERT INTO tran_cat_balance (acct_id, type_cd, cat_cd, bal) VALUES (?, ?, ?, ?)",
                2L, TYPE_CD, CAT_CD, CAT_BALANCE_ACCT_2);

        // Cross-reference rows via the in-scope repository: each valid card -> same-ordinal account.
        List<CardXref> xrefs = List.of(
                new CardXref(VALID_CARD_NUMBERS.get(0), 1L, 1L),
                new CardXref(VALID_CARD_NUMBERS.get(1), 2L, 2L),
                new CardXref(VALID_CARD_NUMBERS.get(2), 3L, 3L),
                new CardXref(VALID_CARD_NUMBERS.get(3), 4L, 4L),
                new CardXref(VALID_CARD_NUMBERS.get(4), 5L, 5L));
        cardXrefRepository.saveAll(xrefs);

        // Staged daily transactions via the in-scope repository: five that resolve, one that does not.
        dailyTransactionRepository.saveAll(buildDailyTransactions());
    }

    /**
     * Removes the seeded fixture in foreign-key dependency order (children before parents) so no
     * state leaks between test methods. The Flyway-supplied reference tables
     * ({@code transaction_type}, {@code transaction_category}) are intentionally left intact. Safe
     * to call before any data exists.
     */
    private void cleanFixtures() {
        dailyTransactionRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM transaction");
        jdbcTemplate.update("DELETE FROM card");
        cardXrefRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM tran_cat_balance");
        jdbcTemplate.update("DELETE FROM account");
        jdbcTemplate.update("DELETE FROM customer");
    }

    /**
     * Builds the six staged {@link DailyTransaction} records seeded per test: one per
     * {@link #VALID_CARD_NUMBERS} entry (each resolving to a cross-reference and account) followed
     * by one carrying {@link #MISSING_CARD_NUMBER} (which resolves to neither).
     *
     * @return the staged daily transactions in seed order
     */
    private List<DailyTransaction> buildDailyTransactions() {
        return List.of(
                newDailyTransaction(1L, VALID_CARD_NUMBERS.get(0)),
                newDailyTransaction(2L, VALID_CARD_NUMBERS.get(1)),
                newDailyTransaction(3L, VALID_CARD_NUMBERS.get(2)),
                newDailyTransaction(4L, VALID_CARD_NUMBERS.get(3)),
                newDailyTransaction(5L, VALID_CARD_NUMBERS.get(4)),
                newDailyTransaction(6L, MISSING_CARD_NUMBER));
    }

    /**
     * Constructs a single staged daily-transaction fixture following the {@code CVTRA06Y.cpy}
     * layout. The 16-character business id is {@code "DTRN"} plus a zero-padded sequence, and the
     * amount is a {@link BigDecimal} at scale&nbsp;2 (the {@code DALYTRAN-AMT} contract).
     *
     * @param seq     the 1-based sequence used to derive a unique {@code DALYTRAN-ID} and amount
     * @param cardNum the {@code DALYTRAN-CARD-NUM} the processor will look up
     * @return a fully populated, transient {@link DailyTransaction}
     */
    private DailyTransaction newDailyTransaction(long seq, String cardNum) {
        String dalytranId = String.format("DTRN%012d", seq);
        BigDecimal amount = BigDecimal.valueOf(seq).setScale(2);
        return new DailyTransaction(
                dalytranId,
                TYPE_CD,
                CAT_CD,
                "POS",
                "Integration fixture record",
                amount,
                123456789L,
                "Fixture Merchant",
                "Seattle",
                "98101",
                cardNum,
                "2024-01-01 00:00:00",
                "2024-01-01 00:00:00");
    }

    // ------------------------------------------------------------------------
    // Log capture
    // ------------------------------------------------------------------------

    /**
     * Attaches a fresh {@link ListAppender} to the {@link DailyTransactionValidateProcessor} logger
     * so the processor's anomaly lines can be inspected. The launcher runs the job synchronously on
     * the calling thread, so all lines are captured by the time {@code launchJob} returns.
     */
    private void attachLogCapture() {
        processorLogger = (Logger) LoggerFactory.getLogger(DailyTransactionValidateProcessor.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        processorLogger.addAppender(logAppender);
    }

    /** Detaches and stops the capturing appender attached by {@link #attachLogCapture()}. */
    private void detachLogCapture() {
        if (processorLogger != null && logAppender != null) {
            processorLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    /**
     * Returns the captured log lines whose formatted message contains both supplied fragments, in
     * emission order. Used to assert the processor reported a missing cross-reference.
     *
     * @param firstFragment  the first substring the message must contain (e.g. the card number)
     * @param secondFragment the second substring the message must contain (the anomaly marker)
     * @return the matching formatted messages, possibly empty (never {@code null})
     */
    private List<String> messagesMentioning(String firstFragment, String secondFragment) {
        return logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message != null
                        && message.contains(firstFragment)
                        && message.contains(secondFragment))
                .toList();
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * Builds a unique {@link JobParameters} for each launch so every run is a distinct job instance
     * (the modern analog of one scheduled JCL execution of CBTRN01C).
     *
     * @return job parameters carrying a unique {@code run.id}
     */
    private JobParameters uniqueParameters() {
        return new JobParametersBuilder()
                .addLong("run.id", RUN_ID.incrementAndGet())
                .toJobParameters();
    }

    /**
     * Returns the read count of the named step within a completed {@link JobExecution}.
     *
     * <p>Deliberately returns a primitive {@code long} rather than a {@link StepExecution}: a
     * test-class method whose return type is {@link StepExecution} (or {@link JobExecution}) would
     * be picked up by {@code @SpringBatchTest}'s step/job-scope test listeners as a scope factory
     * and invoked with no arguments, which fails for a parameterized helper. Returning the count
     * keeps the lookup encapsulated without exposing such a factory method.</p>
     *
     * @param execution the completed job execution
     * @param stepName  the step name to locate
     * @return the number of items the step read
     */
    private long stepReadCount(JobExecution execution, String stepName) {
        return execution.getStepExecutions().stream()
                .filter(step -> stepName.equals(step.getStepName()))
                .findFirst()
                .map(StepExecution::getReadCount)
                .orElseThrow(() -> new AssertionError("step not found: " + stepName));
    }

    /**
     * Captures the read-only snapshot of the master tables the posting/interest jobs would mutate:
     * row counts and monetary checksums for {@code account}, {@code transaction}, and
     * {@code tran_cat_balance}. The account row count uses the in-scope {@link AccountRepository};
     * the checksums use aggregate SQL over fixed table/column names.
     *
     * @return an immutable snapshot of the master-data counts and balance sums
     */
    private MasterDataSnapshot captureMasterData() {
        long accountCount = accountRepository.count();
        BigDecimal accountBalanceSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(curr_bal), 0) FROM account", BigDecimal.class);
        long transactionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transaction", Long.class);
        BigDecimal transactionAmountSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(tran_amt), 0) FROM transaction", BigDecimal.class);
        long tranCatBalanceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tran_cat_balance", Long.class);
        BigDecimal tranCatBalanceSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(bal), 0) FROM tran_cat_balance", BigDecimal.class);
        return new MasterDataSnapshot(accountCount, accountBalanceSum, transactionCount,
                transactionAmountSum, tranCatBalanceCount, tranCatBalanceSum);
    }

    /**
     * Computes the expected sum of the seeded account balances ({@link #ACCOUNT_BALANCES}) as a
     * scale-2 {@link BigDecimal}, used to prove the read-only checksum is anchored to real data.
     *
     * @return the total of the seeded account current balances
     */
    private BigDecimal expectedAccountBalanceSum() {
        return ACCOUNT_BALANCES.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ------------------------------------------------------------------------
    // Nested types
    // ------------------------------------------------------------------------

    /**
     * Immutable snapshot of the master tables asserted to be unchanged by the read-only validate
     * job: the {@code account}, {@code transaction}, and {@code tran_cat_balance} row counts and
     * their monetary checksums.
     *
     * @param accountCount         number of {@code account} rows
     * @param accountBalanceSum    sum of {@code account.curr_bal}
     * @param transactionCount     number of {@code transaction} rows
     * @param transactionAmountSum sum of {@code transaction.tran_amt}
     * @param tranCatBalanceCount  number of {@code tran_cat_balance} rows
     * @param tranCatBalanceSum    sum of {@code tran_cat_balance.bal}
     */
    private record MasterDataSnapshot(long accountCount,
                                      BigDecimal accountBalanceSum,
                                      long transactionCount,
                                      BigDecimal transactionAmountSum,
                                      long tranCatBalanceCount,
                                      BigDecimal tranCatBalanceSum) {
    }

    /**
     * Test-scoped configuration that supplies an explicitly-bound {@link JobLauncherTestUtils}.
     *
     * <p>With many {@link Job} beans on the context, the {@link SpringBatchTest}-provided
     * {@link JobLauncherTestUtils} cannot bind a unique job. This {@link Primary @Primary} bean
     * (registered under a distinct name to avoid clashing with the auto-registered one) binds the
     * job explicitly by qualifier and reuses the auto-configured {@link JobLauncher} and
     * {@link JobRepository}, so this test always launches {@code dailyTransactionValidateJob}.</p>
     */
    @TestConfiguration
    static class BatchTestConfig {

        /**
         * Builds the {@link Primary @Primary} launcher-test utility bound to
         * {@code dailyTransactionValidateJob}.
         *
         * @param dailyTransactionValidateJob the job under test, bound by qualifier
         * @param jobLauncher                 the auto-configured, unique batch job launcher
         * @param jobRepository               the auto-configured, unique batch job repository
         * @return a {@link JobLauncherTestUtils} that launches only {@code dailyTransactionValidateJob}
         */
        @Bean
        @Primary
        JobLauncherTestUtils dailyTransactionValidateJobLauncherTestUtils(
                @Qualifier(JOB_NAME) Job dailyTransactionValidateJob,
                JobLauncher jobLauncher,
                JobRepository jobRepository) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJob(dailyTransactionValidateJob);
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            return utils;
        }
    }
}
