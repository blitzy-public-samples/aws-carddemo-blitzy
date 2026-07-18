/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.batch;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spring Boot + Spring Batch Testcontainers integration test for {@link InterestCalculationJob}
 * (CBACT04C). It exercises the canonical interest scenario end-to-end against a real PostgreSQL 16 and
 * pins the QA-relevant behaviors addressed in decision log D31/D32/D33:
 *
 * <ul>
 *   <li><strong>D31 (HALF_UP rounding divergence).</strong> DEFAULT-group rates
 *       {@code (01,1)=15.00, (01,2)=25.00, (01,3)=25.00, (02,1)=0.00} applied to category balances
 *       {@code 1000.00 / 1200.00 / 0.24 / 500.00} yield {@code 12.50 + 25.00 + 0.01 + (filtered)} =
 *       <strong>37.51</strong>. The {@code (01,3)} row is the parity anchor: {@code 0.24 * 25 / 1200 =
 *       0.005} rounds to {@code 0.01} under HALF_UP (COBOL truncation would give {@code 0.00} and a
 *       {@code 37.50} total). The golden account balance is therefore {@code 37.51}.</li>
 *   <li><strong>D32 (last-account finalization).</strong> The account's balance is actually updated to
 *       {@code 37.51} by the writer's {@code afterStep} finalization; a run that skipped the last
 *       account (the legacy dead-{@code ELSE}) would leave it at {@code 0.00}.</li>
 *   <li><strong>D33 (parmDate fail-fast validator).</strong> A launch without {@code parmDate} is
 *       rejected with {@link JobParametersInvalidException} before the step runs; a launch with
 *       {@code parmDate} seeds the high-order ten characters of every generated interest
 *       {@code TRAN-ID}.</li>
 * </ul>
 *
 * <p>The {@code account} and {@code tran_cat_balance} tables are not seeded under the {@code test}
 * profile, so each method seeds the scenario deterministically (the DEFAULT disclosure-group rates and
 * the transaction categories are already seeded by the Flyway reference-data migration). The generated
 * interest transactions are written to the {@code SYSTRAN} fixed-width file under the default batch
 * output directory; the test clears it around each run and asserts the record count and id prefix.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class InterestCalculationJobTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /** Supplies unique {@code run.id} values across launches so each is a fresh job instance. */
    private static final AtomicLong RUN_ID = new AtomicLong();

    /** The interest scenario account id (matches the shipped golden fixture). */
    private static final long ACCT_ID = 90000000010L;

    /** The interest scenario customer id (FK target for the card cross-reference). */
    private static final long CUST_ID = 900000010L;

    /** The interest scenario card number resolved via the account's cross-reference. */
    private static final String CARD_NUM = "9000000000000010";

    /** The CBACT04C run date supplied as the {@code parmDate} job parameter (ten characters). */
    private static final String PARM_DATE = "2022071800";

    /** SYSTRAN output written by the interest writer (default batch output directory). */
    private static final Path SYSTRAN = Path.of("./target/batch/SYSTRAN.dat");

    /** Canonical fixed record length of the interest {@code TRAN-RECORD} (CVTRA05Y). */
    private static final int RECORD_LENGTH = 350;

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Autowired
    private TransactionCategoryBalanceRepository tranCatBalanceRepository;

    @BeforeEach
    void seedScenario() throws IOException {
        clearScenarioData();
        Files.deleteIfExists(SYSTRAN);

        // Customer is the FK target for the card cross-reference; seeded first.
        customerRepository.save(new Customer(
                CUST_ID, "Interest", "T", "Scenario",
                "10 Test Way", null, null, "TX", "USA", "75010",
                "2145550010", null, "100000010", "DL100000010", "1980-01-01",
                null, "Y", 750));

        // Account starts with a zero balance; group DEFAULT selects the seeded disclosure rates.
        accountRepository.save(new Account(
                ACCT_ID, "Y",
                new BigDecimal("0.00"), new BigDecimal("5000.00"), new BigDecimal("2000.00"),
                "2020-01-01", "2099-12-31", "2020-01-01",
                new BigDecimal("0.00"), new BigDecimal("0.00"), "75010", "DEFAULT"));

        // Card cross-reference resolves the account to its card number for the interest transaction.
        cardXrefRepository.save(new CardXref(CARD_NUM, CUST_ID, ACCT_ID));

        // Four category balances; (02,1) has a zero DEFAULT rate and is filtered (no transaction).
        tranCatBalanceRepository.save(newBalance("01", 1, "1000.00"));
        tranCatBalanceRepository.save(newBalance("01", 2, "1200.00"));
        tranCatBalanceRepository.save(newBalance("01", 3, "0.24"));
        tranCatBalanceRepository.save(newBalance("02", 1, "500.00"));
    }

    @AfterEach
    void clearScenario() throws IOException {
        clearScenarioData();
        Files.deleteIfExists(SYSTRAN);
    }

    /** Removes all scenario rows in FK-safe order (children before parents). */
    private void clearScenarioData() {
        tranCatBalanceRepository.deleteAll();
        cardXrefRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();
    }

    @Test
    void computesHalfUpTotalAndFinalizesLastAccount() throws Exception {
        JobExecution execution = launchInterestJob(PARM_DATE);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // D31 (HALF_UP) + D32 (last-account finalize): balance updated 0.00 -> 37.51 to the cent.
        Account finalized = accountRepository.findById(ACCT_ID).orElseThrow();
        assertThat(finalized.getCurrBal()).isEqualByComparingTo(new BigDecimal("37.51"));
        assertThat(finalized.getCurrBal().scale()).isEqualTo(2);
    }

    @Test
    void generatesThreeInterestTransactionsPrefixedByParmDate() throws Exception {
        launchInterestJob(PARM_DATE);

        assertThat(SYSTRAN).as("interest writer must produce the SYSTRAN output").exists();
        List<String> records = Files.readAllLines(SYSTRAN, StandardCharsets.ISO_8859_1).stream()
                .filter(line -> !line.isBlank())
                .toList();

        // Zero-rate (02,1) is filtered, so exactly three interest transactions are written.
        assertThat(records).hasSize(3);
        for (String record : records) {
            assertThat(record).hasSize(RECORD_LENGTH);
            // D33: parmDate seeds the high-order ten characters of the 16-char TRAN-ID (offset 0..16).
            assertThat(record.substring(0, 10)).isEqualTo(PARM_DATE);
        }
    }

    @Test
    void failsFastWhenParmDateParameterMissing() {
        JobParameters withoutParmDate = new JobParametersBuilder()
                .addLong("run.id", RUN_ID.incrementAndGet())
                .toJobParameters();

        assertThatThrownBy(() -> jobLauncherTestUtils.launchJob(withoutParmDate))
                .isInstanceOf(JobParametersInvalidException.class)
                .hasMessageContaining("parmDate");

        // The step never ran, so the account balance is untouched.
        assertThat(accountRepository.findById(ACCT_ID).orElseThrow().getCurrBal())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    /**
     * Launches {@code interestCalculationJob} with the supplied {@code parmDate} and a unique
     * {@code run.id}.
     *
     * @param parmDate the CBACT04C run date parameter
     * @return the completed {@link JobExecution}
     * @throws Exception if the underlying launcher throws
     */
    private JobExecution launchInterestJob(String parmDate) throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addLong("run.id", RUN_ID.incrementAndGet())
                .addString("parmDate", parmDate)
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(parameters);
    }

    /**
     * Builds a transient {@link TransactionCategoryBalance} for the scenario account.
     *
     * @param typeCd the transaction type code
     * @param catCd  the transaction category code
     * @param bal    the scale-2 category balance
     * @return a new transient category-balance row
     */
    private TransactionCategoryBalance newBalance(String typeCd, int catCd, String bal) {
        return new TransactionCategoryBalance(
                new TransactionCategoryBalanceId(ACCT_ID, typeCd, catCd), new BigDecimal(bal));
    }

    /**
     * Test-only configuration supplying a {@link JobLauncherTestUtils} bound to the specific
     * {@code interestCalculationJob} bean.
     */
    @TestConfiguration
    static class TestBatchSupportConfig {

        /**
         * Builds a {@link JobLauncherTestUtils} for {@code interestCalculationJob}.
         *
         * @param job           the interest-calculation job, selected by qualifier
         * @param jobLauncher   the auto-configured Spring Batch job launcher
         * @param jobRepository the auto-configured Spring Batch job repository
         * @return the configured launcher utility
         */
        @Bean
        JobLauncherTestUtils jobLauncherTestUtils(@Qualifier("interestCalculationJob") Job job,
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
