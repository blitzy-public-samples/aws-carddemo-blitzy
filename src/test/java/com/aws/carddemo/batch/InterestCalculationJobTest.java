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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.DisclosureGroup;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.DisclosureGroupRepository;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.repository.TransactionRepository;

/**
 * Spring Boot + Spring Batch + Testcontainers integration test that validates the <strong>exact
 * monetary parity</strong> of {@code InterestCalculationJob} &mdash; the Java target of the legacy
 * COBOL batch program {@code app/cbl/CBACT04C.cbl} (triggered by {@code app/jcl/INTCALC.jcl}
 * {@code EXEC PGM=CBACT04C,PARM='2022071800'}). This is the highest-risk monetary-fidelity test in
 * the migration (AAP hotspot <strong>H3</strong>: {@code COMP-3} packed-decimal arithmetic).
 *
 * <h2>Parity contract under test</h2>
 * <ul>
 *   <li><strong>{@code 1300-COMPUTE-INTEREST} (CBACT04C L462-465):</strong>
 *       {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} &mdash; the COBOL has
 *       <em>no {@code ROUNDED} clause</em> (it truncates the intermediate to the receiving field).
 *       The Java target intentionally computes
 *       {@code balance.multiply(rate).divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP)};
 *       the {@code HALF_UP} scale-2 result is <strong>authoritative</strong> for the golden fixtures
 *       (documented in {@code docs/decision-log.md}). Every assertion is to the cent using
 *       {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo(BigDecimal)}.</li>
 *   <li><strong>Disclosure-group rate resolution:</strong> the rate is keyed by
 *       ({@code account-group-id}, {@code type-cd}, {@code cat-cd}); when the specific group is
 *       absent the program falls back to the {@code DEFAULT} disclosure group
 *       ({@code 1200-A-GET-DEFAULT-INT-RATE}). Both paths are exercised.</li>
 *   <li><strong>Per-account control break:</strong> interest accrues per category balance and is
 *       totaled per account ({@code WS-TOTAL-INT}); on the account break the total is applied to
 *       {@code ACCT-CURR-BAL} and the cycle credit/debit are zeroed ({@code 1050-UPDATE-ACCOUNT}).</li>
 *   <li><strong>{@code 1300-B-WRITE-TX}:</strong> each interest transaction id is
 *       {@code PARM-DATE} (10) + an incrementing {@code WS-TRANID-SUFFIX} (6) = 16 characters, and
 *       the interest transactions are written to a 350-byte fixed-width {@code SYSTRAN} file (the
 *       {@code InterestTransactionWriter} output), <em>not</em> the {@code transaction} table.</li>
 * </ul>
 *
 * <h2>How parity is measured</h2>
 * <p>The writer applies the per-account interest total to {@code account.curr_bal} in the database
 * and streams the interest transactions to the {@code SYSTRAN} file. Consequently the
 * <strong>account balance delta</strong> (post-run {@code curr_bal} minus the seeded starting
 * balance) is the authoritative measure of the interest applied, and the SYSTRAN file is parsed to
 * verify the generated transaction ids. Every account is seeded with a starting balance of
 * {@code 0.00} so the post-run {@code curr_bal} equals the applied interest exactly.</p>
 *
 * <h2>Harness</h2>
 * <p>The application registers eleven {@link Job} beans, so the job under test is bound explicitly
 * via a nested {@link TestConfiguration} that builds a {@link JobLauncherTestUtils} around the
 * {@code @Qualifier("interestCalculationJob")} job. {@code spring.batch.job.enabled=false} (see
 * {@code application-test.yml}) means jobs never run at startup; each test launches explicitly with
 * the required {@code parmDate} parameter plus a unique {@code run.id}. The {@code SYSTRAN} output
 * location is redirected to a per-class temporary directory via {@link DynamicPropertySource} so the
 * file can be read back deterministically without colliding with other batch tests.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@SpringBatchTest
@Testcontainers
class InterestCalculationJobTest {

    /**
     * Real PostgreSQL 16 backing the JPA repositories and Flyway migrations (V1 schema + V2
     * reference data, including the 51 {@code disclosure_group} rows). {@link ServiceConnection}
     * wires the datasource with no hardcoded credentials.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /** CBACT04C {@code PARM} value from {@code INTCALC.jcl}; seeds the high-order 10 chars of TRAN-ID. */
    private static final String PARM_DATE = "2022071800";

    /** Fixed length of the {@code SYSTRAN} interest records ({@code DCB=(RECFM=F,LRECL=350)}). */
    private static final int RECORD_LENGTH = 350;

    /** Test-only disclosure group id (matches {@code LIKE 'TST%'} cleanup; never clashes with V2 rows). */
    private static final String TEST_GROUP = "TSTGRP";

    /** Golden-fixture account (see {@code src/test/resources/golden/interest/expected-account.csv}). */
    private static final long GOLDEN_ACCT = 90000000010L;

    /** Divisor from the COBOL {@code / 1200} (12 months * scale factor); never a floating divisor. */
    private static final BigDecimal MONTHS_SCALE = BigDecimal.valueOf(1200);

    /** Monotonic {@code run.id} making each launch a distinct {@link JobExecution}. */
    private static final AtomicLong RUN_ID = new AtomicLong();

    /** SYSTRAN output file name registered into the writer's configuration for this test class. */
    private static final String OUTPUT_FILE = "SYSTRAN.dat";

    /** Per-class temporary directory the writer streams the SYSTRAN file into. */
    private static final Path OUTPUT_DIR;

    static {
        try {
            OUTPUT_DIR = Files.createTempDirectory("carddemo-interest-test-");
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private final JobLauncherTestUtils jobLauncherTestUtils;
    private final JobRepositoryTestUtils jobRepositoryTestUtils;
    private final JdbcTemplate jdbcTemplate;
    private final AccountRepository accountRepository;
    private final DisclosureGroupRepository disclosureGroupRepository;
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;
    private final TransactionRepository transactionRepository;
    private final ApplicationContext applicationContext;

    /**
     * All collaborators are injected by constructor (never field injection); constructor autowiring
     * is enabled globally by {@code spring.test.constructor.autowire.mode=all} in
     * {@code junit-platform.properties}.
     *
     * @param jobLauncherTestUtils                  utility bound to the interest job (nested config)
     * @param jobRepositoryTestUtils                utility to purge job executions between tests
     * @param jdbcTemplate                          used to seed foreign-key parents not in scope for
     *                                              this file ({@code customer}, {@code account},
     *                                              {@code card_xref}) and to clean fixtures
     * @param accountRepository                     in-scope repository for balance verification
     * @param disclosureGroupRepository             in-scope repository for seeding/reading rates
     * @param transactionCategoryBalanceRepository  in-scope repository for seeding category balances
     * @param transactionRepository                 in-scope repository to prove interest txns are not
     *                                              written to the {@code transaction} table
     * @param applicationContext                    used to assert the job bean is registered
     */
    InterestCalculationJobTest(
            JobLauncherTestUtils jobLauncherTestUtils,
            JobRepositoryTestUtils jobRepositoryTestUtils,
            JdbcTemplate jdbcTemplate,
            AccountRepository accountRepository,
            DisclosureGroupRepository disclosureGroupRepository,
            TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            TransactionRepository transactionRepository,
            ApplicationContext applicationContext) {
        this.jobLauncherTestUtils = jobLauncherTestUtils;
        this.jobRepositoryTestUtils = jobRepositoryTestUtils;
        this.jdbcTemplate = jdbcTemplate;
        this.accountRepository = accountRepository;
        this.disclosureGroupRepository = disclosureGroupRepository;
        this.transactionCategoryBalanceRepository = transactionCategoryBalanceRepository;
        this.transactionRepository = transactionRepository;
        this.applicationContext = applicationContext;
    }

    /**
     * Redirects the {@code InterestTransactionWriter} SYSTRAN output to a per-class temporary
     * directory. Registered here (before the context starts) because the writer binds these values
     * via {@code @Value} at construction time.
     *
     * @param registry the dynamic property registry supplied by the Spring TestContext framework
     */
    @DynamicPropertySource
    static void batchOutputProperties(DynamicPropertyRegistry registry) {
        registry.add("carddemo.batch.interest.output-directory", OUTPUT_DIR::toString);
        registry.add("carddemo.batch.interest.output-file", () -> OUTPUT_FILE);
    }

    /**
     * Explicitly binds the multi-job application's {@code interestCalculationJob} to a
     * {@link JobLauncherTestUtils}. Marked {@link Primary} so it wins over the no-arg utility that
     * {@link SpringBatchTest} would otherwise try to autowire against eleven ambiguous jobs.
     */
    @TestConfiguration
    static class BatchTestConfig {

        @Bean
        @Primary
        JobLauncherTestUtils interestJobLauncherTestUtils(
                @Qualifier("interestCalculationJob") Job interestCalculationJob,
                JobLauncher jobLauncher,
                JobRepository jobRepository) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJob(interestCalculationJob);
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            return utils;
        }
    }

    @BeforeEach
    void setUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        cleanFixtures();
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
        jobRepositoryTestUtils.removeJobExecutions();
    }

    // ------------------------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("context loads and the interestCalculationJob bean is registered")
    void contextLoadsAndJobRegistered() {
        assertThat(jobLauncherTestUtils).isNotNull();
        assertThat(applicationContext.containsBean("interestCalculationJob")).isTrue();
        assertThat(jobLauncherTestUtils.getJob().getName()).isEqualTo("interestCalculationJob");
    }

    @Test
    @DisplayName("job runs to COMPLETED with batch return code 0")
    void runsCleanWithReturnCodeZero() throws Exception {
        seedGoldenScenario();

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters(PARM_DATE));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    @Test
    @DisplayName("monthly interest is exact to the cent, including HALF_UP on a half-cent (H3 guard)")
    void monthlyInterestExactToTheCent() throws Exception {
        // A controlled rate under a test-only disclosure group (disclosure_group has no DB FK).
        final BigDecimal rate = new BigDecimal("12.00");
        seedDisclosure(TEST_GROUP, "01", 1, rate);

        // Clean case: 1000.00 * 12.00 / 1200 = 10.00 exactly.
        final long cleanAcct = 90100000001L;
        final BigDecimal cleanBal = new BigDecimal("1000.00");
        seedAccountGraph(cleanAcct, TEST_GROUP, zero(), zero(), zero());
        seedCatBalance(cleanAcct, "01", 1, cleanBal);

        // Half-cent case: 0.50 * 12.00 / 1200 = 0.005 -> HALF_UP -> 0.01
        // (the COBOL truncation would yield 0.00; this is the documented H3 deviation).
        final long halfAcct = 90100000002L;
        final BigDecimal halfBal = new BigDecimal("0.50");
        seedAccountGraph(halfAcct, TEST_GROUP, zero(), zero(), zero());
        seedCatBalance(halfAcct, "01", 1, halfBal);

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters(PARM_DATE));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        final BigDecimal expectedClean = monthlyInterest(cleanBal, rate);
        final BigDecimal expectedHalf = monthlyInterest(halfBal, rate);

        // Sanity-check the fixtures encode the intended values before comparing to the run output.
        assertThat(expectedClean).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(expectedHalf).isEqualByComparingTo(new BigDecimal("0.01"));

        assertThat(interestAppliedFromZero(cleanAcct)).isEqualByComparingTo(expectedClean);

        // Headline H3 assertion: the half-cent rounds UP to 0.01 and is NOT truncated to 0.00.
        assertThat(interestAppliedFromZero(halfAcct)).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(interestAppliedFromZero(halfAcct)).isNotEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("interest falls back to the DEFAULT disclosure group when the account's group has no rate")
    void defaultDisclosureGroupFallback() throws Exception {
        final long acct = 90200000001L;
        final BigDecimal bal = new BigDecimal("1000.00");

        // "NOGRP" has no disclosure rows at all, forcing the 1200-A DEFAULT fallback in the processor.
        seedAccountGraph(acct, "NOGRP", zero(), zero(), zero());
        seedCatBalance(acct, "01", 1, bal);

        // Preconditions: the specific group row is absent; the DEFAULT row exists.
        assertThat(disclosureGroupRepository.findById(
                new DisclosureGroup.DisclosureGroupId("NOGRP", "01", 1))).isEmpty();
        final BigDecimal defaultRate = disclosureGroupRepository.findById(
                        new DisclosureGroup.DisclosureGroupId("DEFAULT", "01", 1))
                .orElseThrow(() -> new AssertionError("DEFAULT disclosure rate row is missing"))
                .getIntRate();

        final BigDecimal expected = monthlyInterest(bal, defaultRate);
        // Ensure the fallback rate is meaningful (non-zero), otherwise the assertion is vacuous.
        assertThat(expected).isGreaterThan(zero());

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters(PARM_DATE));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(interestAppliedFromZero(acct)).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("interest transaction ids are parmDate + an ascending 6-digit suffix in the SYSTRAN file")
    void interestTransactionIdsFromParmDatePlusSuffix() throws Exception {
        seedDisclosure(TEST_GROUP, "01", 1, new BigDecimal("12.00"));
        seedDisclosure(TEST_GROUP, "01", 2, new BigDecimal("12.00"));
        seedDisclosure(TEST_GROUP, "01", 3, new BigDecimal("12.00"));

        final long acct = 90300000001L;
        seedAccountGraph(acct, TEST_GROUP, zero(), zero(), zero());
        seedCatBalance(acct, "01", 1, new BigDecimal("1000.00"));
        seedCatBalance(acct, "01", 2, new BigDecimal("1200.00"));
        seedCatBalance(acct, "01", 3, new BigDecimal("600.00"));

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters(PARM_DATE));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> records = readSystranRecords();
        // Three non-zero category balances -> three interest transactions.
        assertThat(records).hasSize(3);

        for (int i = 0; i < records.size(); i++) {
            String record = records.get(i);
            assertThat(record).hasSize(RECORD_LENGTH);

            // CVTRA05Y fixed-width layout: TRAN-ID @0(16), TRAN-TYPE-CD @16(2), TRAN-CAT-CD @18(4).
            String tranId = record.substring(0, 16);
            assertThat(tranId).startsWith(PARM_DATE);
            assertThat(tranId.substring(10)).isEqualTo(String.format("%06d", i + 1));
            assertThat(record.substring(16, 18)).isEqualTo("01");
            assertThat(Integer.parseInt(record.substring(18, 22).trim())).isEqualTo(5);
        }

        List<String> ids = records.stream().map(record -> record.substring(0, 16)).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).isSorted();

        // Interest transactions are written to the SYSTRAN file, never to the transaction table.
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    @DisplayName("per-account interest total accumulates across category balances (WS-TOTAL-INT)")
    void perAccountTotalsAccumulated() throws Exception {
        // Three rates chosen so each category yields exactly 10.00 -> a 30.00 per-account total.
        final BigDecimal rate1 = new BigDecimal("12.00");
        final BigDecimal rate2 = new BigDecimal("10.00");
        final BigDecimal rate3 = new BigDecimal("20.00");
        seedDisclosure(TEST_GROUP, "01", 1, rate1);
        seedDisclosure(TEST_GROUP, "01", 2, rate2);
        seedDisclosure(TEST_GROUP, "01", 3, rate3);

        final long acct = 90400000001L;
        final BigDecimal bal1 = new BigDecimal("1000.00");
        final BigDecimal bal2 = new BigDecimal("1200.00");
        final BigDecimal bal3 = new BigDecimal("600.00");
        seedAccountGraph(acct, TEST_GROUP, zero(), zero(), zero());
        seedCatBalance(acct, "01", 1, bal1);
        seedCatBalance(acct, "01", 2, bal2);
        seedCatBalance(acct, "01", 3, bal3);

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters(PARM_DATE));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        BigDecimal expectedTotal = monthlyInterest(bal1, rate1)
                .add(monthlyInterest(bal2, rate2))
                .add(monthlyInterest(bal3, rate3));
        assertThat(expectedTotal).isEqualByComparingTo(new BigDecimal("30.00"));

        assertThat(interestAppliedFromZero(acct)).isEqualByComparingTo(expectedTotal);
    }

    @Test
    @DisplayName("golden parity: account 90000000010 ends at curr_bal 37.51 with cycle credit/debit zeroed")
    void goldenInterestFileMatches() throws Exception {
        seedGoldenScenario();

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters(PARM_DATE));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Account account = accountRepository.findById(GOLDEN_ACCT)
                .orElseThrow(() -> new AssertionError("golden account is missing after the run"));

        // golden/interest/expected-account.csv: curr_bal 37.51 (12.50 + 25.00 + 0.01 HALF_UP + 0.00
        // zero-rate skip). The (01,3) row 0.24 * 25.00 / 1200 = 0.005 rounds HALF_UP to 0.01, so the
        // total is 37.51 (the COBOL truncation would give 37.50): this is the H3 golden guard.
        assertThat(account.getCurrBal()).isEqualByComparingTo(new BigDecimal("37.51"));
        // 1050-UPDATE-ACCOUNT zeroes the cycle credit/debit; they were seeded non-zero to prove it.
        assertThat(account.getCurrCycCredit()).isEqualByComparingTo(zero());
        assertThat(account.getCurrCycDebit()).isEqualByComparingTo(zero());
    }

    @Test
    @DisplayName("F-P5-A: an account whose only balance has a zero rate is still finalized (cycle zeroed, no SYSTRAN line)")
    void zeroRateOnlyAccountIsStillFinalized() throws Exception {
        // The DEFAULT disclosure group's (02,1) rate is 0.00, so this account's single category
        // balance yields zero interest. Before the fix the processor filtered the zero-interest row
        // out entirely, so the account never reached the writer's control-break finalization and its
        // cycle credit/debit were left un-zeroed despite a clean (RC0) run.
        final long acct = 90500000001L;
        seedAccountGraph(acct, "DEFAULT", zero(),
                new BigDecimal("111.11"), new BigDecimal("222.22"));
        seedCatBalance(acct, "02", 1, new BigDecimal("500.00"));

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters(PARM_DATE));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Account account = accountRepository.findById(acct)
                .orElseThrow(() -> new AssertionError("account missing after the run"));
        // Zero interest was applied, yet the account was STILL finalized by 1050-UPDATE-ACCOUNT:
        // the balance is unchanged, the cycle credit/debit are zeroed, and the version was bumped ...
        assertThat(account.getCurrBal()).isEqualByComparingTo(zero());
        assertThat(account.getCurrCycCredit()).isEqualByComparingTo(zero());
        assertThat(account.getCurrCycDebit()).isEqualByComparingTo(zero());
        assertThat(account.getVersion()).isEqualTo(1L);
        assertThat(account.getLastInterestCycle()).isEqualTo(PARM_DATE);
        // ... and no interest transaction line was written for a zero-interest account.
        assertThat(readSystranRecords()).isEmpty();
    }

    @Test
    @DisplayName("F-P6-A/F-P5-D: re-running the same cycle applies interest exactly once (idempotent, no double-apply)")
    void rerunningTheSameCycleIsIdempotent() throws Exception {
        seedGoldenScenario();

        // First run applies interest for the cycle and stamps last_interest_cycle = parmDate.
        JobExecution first = jobLauncherTestUtils.launchJob(uniqueParameters(PARM_DATE));
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Account afterFirst = accountRepository.findById(GOLDEN_ACCT)
                .orElseThrow(() -> new AssertionError("golden account missing after the first run"));
        assertThat(afterFirst.getCurrBal()).isEqualByComparingTo(new BigDecimal("37.51"));
        assertThat(afterFirst.getVersion()).isEqualTo(1L);
        assertThat(afterFirst.getLastInterestCycle()).isEqualTo(PARM_DATE);

        // A second launch for the SAME parmDate (the deterministic proxy for a restart or a
        // concurrent duplicate run) must NOT re-apply interest: the conditional cycle update matches
        // zero rows because last_interest_cycle already equals parmDate.
        JobExecution second = jobLauncherTestUtils.launchJob(uniqueParameters(PARM_DATE));
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Account afterSecond = accountRepository.findById(GOLDEN_ACCT)
                .orElseThrow(() -> new AssertionError("golden account missing after the second run"));
        // Balance and version are unchanged: interest was applied exactly once, not doubled to
        // 75.02 / version 2 (the pre-fix behavior).
        assertThat(afterSecond.getCurrBal()).isEqualByComparingTo(new BigDecimal("37.51"));
        assertThat(afterSecond.getVersion()).isEqualTo(1L);
        assertThat(afterSecond.getCurrCycCredit()).isEqualByComparingTo(zero());
        assertThat(afterSecond.getCurrCycDebit()).isEqualByComparingTo(zero());
        assertThat(afterSecond.getLastInterestCycle()).isEqualTo(PARM_DATE);
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures and helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Seeds the golden fixture: account {@code 90000000010} (group {@code DEFAULT}, starting balance
     * {@code 0.00}, non-zero cycle credit/debit to prove they get zeroed) and the four category
     * balances from {@code seed/interest/tran_cat_balance.csv}.
     */
    private void seedGoldenScenario() {
        seedAccountGraph(GOLDEN_ACCT, "DEFAULT", zero(),
                new BigDecimal("111.11"), new BigDecimal("222.22"));
        seedCatBalance(GOLDEN_ACCT, "01", 1, new BigDecimal("1000.00")); // DEFAULT 15.00 -> 12.50
        seedCatBalance(GOLDEN_ACCT, "01", 2, new BigDecimal("1200.00")); // DEFAULT 25.00 -> 25.00
        seedCatBalance(GOLDEN_ACCT, "01", 3, new BigDecimal("0.24"));     // DEFAULT 25.00 -> 0.005 -> 0.01
        seedCatBalance(GOLDEN_ACCT, "02", 1, new BigDecimal("500.00"));   // DEFAULT 0.00  -> skipped
    }

    /**
     * Seeds a complete foreign-key parent graph for one account: a {@code customer}, the
     * {@code account} (with its disclosure {@code group_id}), and a {@code card_xref} row (required
     * by the processor's {@code 1110-GET-XREF-DATA} lookup). The {@code customer}, {@code account},
     * and {@code card_xref} tables are seeded through {@link JdbcTemplate} because their entities are
     * outside this file's dependency scope.
     *
     * @param acctId      the account id, reused as the customer id and to derive the 16-digit card number
     * @param groupId     the disclosure group id stored on the account
     * @param startingBal the seeded {@code curr_bal}; always {@code 0.00} so the post-run balance is
     *                    exactly the applied interest
     * @param cycCredit   the seeded {@code curr_cyc_credit} (zeroed by {@code 1050-UPDATE-ACCOUNT})
     * @param cycDebit    the seeded {@code curr_cyc_debit} (zeroed by {@code 1050-UPDATE-ACCOUNT})
     */
    private void seedAccountGraph(long acctId, String groupId, BigDecimal startingBal,
            BigDecimal cycCredit, BigDecimal cycDebit) {
        jdbcTemplate.update("INSERT INTO customer (cust_id) VALUES (?)", acctId);
        jdbcTemplate.update(
                "INSERT INTO account (acct_id, acct_active_status, curr_bal, credit_limit, "
                        + "cash_credit_limit, curr_cyc_credit, curr_cyc_debit, acct_addr_zip, group_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                acctId, "Y", startingBal, new BigDecimal("5000.00"), new BigDecimal("2000.00"),
                cycCredit, cycDebit, "75010", groupId);
        jdbcTemplate.update("INSERT INTO card_xref (xref_card_num, cust_id, acct_id) VALUES (?, ?, ?)",
                String.format("%016d", acctId), acctId, acctId);
    }

    /**
     * Seeds a category balance via the in-scope repository. The composite key
     * {@code (acct_id, type_cd, cat_cd)} must reference an existing {@code account} (seeded first) and
     * an existing {@code transaction_category} (supplied by Flyway V2).
     */
    private void seedCatBalance(long acctId, String typeCd, int catCd, BigDecimal bal) {
        transactionCategoryBalanceRepository.save(new TransactionCategoryBalance(
                new TransactionCategoryBalance.TransactionCategoryBalanceId(acctId, typeCd, catCd), bal));
    }

    /**
     * Seeds a test-only disclosure-group rate via the in-scope repository. {@code disclosure_group}
     * has no database foreign keys, so any {@code (type, cat)} may be used; cleanup removes only
     * {@code group_id LIKE 'TST%'} rows so the Flyway V2 rate table is never disturbed.
     */
    private void seedDisclosure(String groupId, String typeCd, int catCd, BigDecimal rate) {
        disclosureGroupRepository.save(new DisclosureGroup(groupId, typeCd, catCd, rate));
    }

    /**
     * Builds the launch parameters: the CBACT04C {@code parmDate} (required by the job's validator)
     * plus a unique {@code run.id} so re-launches within the class are distinct executions.
     *
     * <p>This deliberately returns {@link JobParameters} rather than {@link JobExecution}. A
     * test-instance method returning {@code JobExecution} is mistaken by {@code @SpringBatchTest}'s
     * {@code JobScopeTestExecutionListener} for the job-scope factory method and invoked with no
     * arguments during test-instance preparation, which fails. The job is therefore launched inline
     * in each test via {@code jobLauncherTestUtils.launchJob(uniqueParameters(PARM_DATE))}.</p>
     *
     * @param parmDate the 10-character CBACT04C parameter date
     * @return the job parameters to launch with
     */
    private JobParameters uniqueParameters(String parmDate) {
        return new JobParametersBuilder()
                .addString("parmDate", parmDate)
                .addLong("run.id", RUN_ID.incrementAndGet())
                .toJobParameters();
    }

    /**
     * Returns the interest applied to an account, which equals its post-run {@code curr_bal} because
     * every account in this suite is seeded with a {@code 0.00} starting balance.
     *
     * @param acctId the account id
     * @return the post-run current balance (scale 2)
     */
    private BigDecimal interestAppliedFromZero(long acctId) {
        return accountRepository.findById(acctId)
                .orElseThrow(() -> new AssertionError("account not found after the run: " + acctId))
                .getCurrBal();
    }

    /**
     * Reproduces the authoritative interest computation:
     * {@code balance * rate / 1200} at scale 2 with {@link RoundingMode#HALF_UP}.
     *
     * @param balance the category balance
     * @param rate    the annual disclosure interest rate
     * @return the monthly interest at scale 2
     */
    private static BigDecimal monthlyInterest(BigDecimal balance, BigDecimal rate) {
        return balance.multiply(rate).divide(MONTHS_SCALE, 2, RoundingMode.HALF_UP);
    }

    /** A fresh scale-2 zero; monetary math and assertions never use {@code double}/{@code float}. */
    private static BigDecimal zero() {
        return new BigDecimal("0.00");
    }

    /**
     * Reads the fixed-width {@code SYSTRAN} interest file produced by the run, one 350-byte record
     * per line. Returns an empty list when the run wrote no records (the writer still creates the
     * file, but a no-interest run yields no lines).
     *
     * @return the SYSTRAN records as fixed-width strings
     * @throws IOException if the file cannot be read
     */
    private List<String> readSystranRecords() throws IOException {
        Path file = OUTPUT_DIR.resolve(OUTPUT_FILE);
        if (!Files.exists(file)) {
            return List.of();
        }
        return Files.readAllLines(file, StandardCharsets.ISO_8859_1);
    }

    /**
     * Removes all seeded rows in foreign-key dependency order (children before parents) and deletes
     * the SYSTRAN file. The Flyway reference tables ({@code transaction_type},
     * {@code transaction_category}) and the V2 {@code disclosure_group} rows are preserved; only the
     * test-only {@code TST%} disclosure rows are removed. Safe to call before any data exists.
     */
    private void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM transaction");
        jdbcTemplate.update("DELETE FROM tran_cat_balance");
        jdbcTemplate.update("DELETE FROM card_xref");
        jdbcTemplate.update("DELETE FROM account");
        jdbcTemplate.update("DELETE FROM customer");
        jdbcTemplate.update("DELETE FROM disclosure_group WHERE group_id LIKE 'TST%'");
        deleteSystranFile();
    }

    private void deleteSystranFile() {
        try {
            Files.deleteIfExists(OUTPUT_DIR.resolve(OUTPUT_FILE));
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to delete SYSTRAN fixture file", ex);
        }
    }
}
