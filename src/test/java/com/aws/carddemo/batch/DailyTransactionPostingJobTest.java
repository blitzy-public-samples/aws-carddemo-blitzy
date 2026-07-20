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

import com.aws.carddemo.common.util.FixedWidthCodec;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.aws.carddemo.exception.RejectCode;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.DailyTransactionRepository;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.repository.TransactionRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers integration test asserting <strong>behavioral parity</strong> of
 * {@link DailyTransactionPostingJob} (the Java re-platform of COBOL batch program
 * {@code CBTRN02C.cbl}, driven by JCL {@code POSTTRAN.jcl}) against the frozen mainframe contract.
 *
 * <p>This is the highest parity-risk test in the batch suite. It exercises the exact
 * {@code 1500-VALIDATE-TRAN} evaluation order and short-circuit semantics, the monetary arithmetic
 * of the posting paragraphs ({@code 2700}/{@code 2800}/{@code 2900}), the 430-byte {@code DALYREJS}
 * reject-record layout, and the RC 0 / RC 4 / RC 8 return-code mapping. Every monetary assertion is
 * expressed as a {@link BigDecimal} at scale 2 &mdash; never {@code double}/{@code float} &mdash; so
 * rounding parity cannot silently drift (AAP hotspots H3, H4; &sect;0.9.2).</p>
 *
 * <h2>Reject-code coverage</h2>
 * <ul>
 *   <li><strong>100</strong> {@code INVALID CARD NUMBER FOUND} &mdash; cross-reference miss;
 *       short-circuits before the account lookup.</li>
 *   <li><strong>102</strong> {@code OVERLIMIT TRANSACTION} &mdash;
 *       {@code ACCT-CREDIT-LIMIT < WS-TEMP-BAL}.</li>
 *   <li><strong>103</strong> {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION} &mdash;
 *       {@code ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)}.</li>
 *   <li><strong>102 + 103 last-writer-wins</strong> &mdash; two independent {@code IF} blocks write
 *       the same reason field, so a record that is both overlimit and expired records <strong>103</strong>.</li>
 * </ul>
 *
 * <p>Reject <strong>101</strong> ({@code ACCOUNT RECORD NOT FOUND}) is intentionally
 * <em>not</em> exercised here: in the relational schema {@code card_xref.acct_id} is a
 * {@code NOT NULL} foreign key to {@code account}, so a cross-reference hit always resolves to an
 * account and 101 is unreachable end-to-end. That path is covered by the sibling unit test
 * {@code DailyTransactionPostingProcessorTest} with a mocked repository.</p>
 *
 * <h2>Harness</h2>
 * <p>The parent package has no shared base class, so this test is self-contained. A single
 * PostgreSQL 16 container is wired via {@code @ServiceConnection} (secret-free). Because 11
 * {@link Job} beans exist in the context, a nested {@link TestConfiguration} builds a
 * {@link JobLauncherTestUtils} bound explicitly to the {@code dailyTransactionPostingJob} bean via
 * {@link Qualifier}. Each launch uses a unique {@code run.id} job parameter and job executions are
 * cleared between launches. The externalized reject directory is redirected to a per-JVM temporary
 * directory via {@link DynamicPropertySource} so parallel clones never collide.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@SpringBatchTest
@Testcontainers
@DisplayName("DailyTransactionPostingJob - CBTRN02C posting parity (Testcontainers)")
class DailyTransactionPostingJobTest {

    /**
     * Shared PostgreSQL 16 container. {@code @ServiceConnection} contributes the datasource
     * properties automatically, so no credentials appear in source or configuration.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /** Per-JVM reject output directory; the writer resolves {@code DALYREJS.dat[.tmp]} beneath it. */
    private static final Path REJECT_DIR = createRejectDirectory();

    /** Reject file name, matching {@code POSTTRAN.jcl} {@code DALYREJS} DD ({@code LRECL=430}). */
    private static final String REJECT_FILE_NAME = "DALYREJS.dat";

    /**
     * Redirects the writer's externalized reject-file location to {@link #REJECT_DIR}. Registered
     * before the application context is refreshed so the {@code @Value} constructor bindings on
     * {@code DailyTransactionPostingWriter} resolve to the test directory.
     */
    @DynamicPropertySource
    static void rejectFileProperties(DynamicPropertyRegistry registry) {
        registry.add("carddemo.batch.posting.reject-directory", REJECT_DIR::toString);
        registry.add("carddemo.batch.posting.reject-file", () -> REJECT_FILE_NAME);
    }

    // ------------------------------------------------------------------------
    // Job / step identity and DALYREJS record geometry (see the writer's field
    // descriptors; offsets are 0-based into the 430-byte record).
    // ------------------------------------------------------------------------

    private static final String JOB_NAME = "dailyTransactionPostingJob";
    private static final String STEP_NAME = "dailyTransactionPostingStep";

    /** Exit code emitted by the step when at least one record was rejected (RC 4). */
    private static final String EXIT_COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    /** Reference type/category present in Flyway V2 reference data ({@code transaction_category '01',1}). */
    private static final String TYPE_CD = "01";
    private static final int CAT_CD = 1;

    /** Total 430-byte {@code DALYREJS} record width. */
    private static final int REJECT_RECORD_LENGTH = 430;

    // 0-based DALYTRAN image field offsets/lengths used to build inputs and decode reject records.
    private static final int OFF_ID = 0;
    private static final int LEN_ID = 16;
    private static final int OFF_TYPE_CD = 16;
    private static final int LEN_TYPE_CD = 2;
    private static final int OFF_CAT_CD = 18;
    private static final int LEN_CAT_CD = 4;
    private static final int OFF_SOURCE = 22;
    private static final int LEN_SOURCE = 10;
    private static final int OFF_DESC = 32;
    private static final int LEN_DESC = 100;
    private static final int OFF_AMT = 132;
    private static final int LEN_AMT = 11;
    private static final int AMT_SCALE = 2;
    private static final int OFF_MERCHANT_ID = 143;
    private static final int LEN_MERCHANT_ID = 9;
    private static final int OFF_MERCHANT_NAME = 152;
    private static final int LEN_MERCHANT_NAME = 50;
    private static final int OFF_MERCHANT_CITY = 202;
    private static final int LEN_MERCHANT_CITY = 50;
    private static final int OFF_MERCHANT_ZIP = 252;
    private static final int LEN_MERCHANT_ZIP = 10;
    private static final int OFF_CARD_NUM = 262;
    private static final int LEN_CARD_NUM = 16;
    private static final int OFF_ORIG_TS = 278;
    private static final int LEN_ORIG_TS = 26;
    private static final int OFF_PROC_TS = 304;
    private static final int LEN_PROC_TS = 26;

    // 80-byte validation trailer.
    private static final int OFF_REASON_CODE = 350;
    private static final int LEN_REASON_CODE = 4;
    private static final int OFF_REASON_DESC = 354;
    private static final int LEN_REASON_DESC = 76;

    /** Monotonic source of unique {@code run.id} job parameters across launches. */
    private static final AtomicLong RUN_ID = new AtomicLong(System.currentTimeMillis());

    // ------------------------------------------------------------------------
    // Collaborators (constructor-injected; global constructor-autowire mode is
    // enabled via junit-platform.properties).
    // ------------------------------------------------------------------------

    private final JobLauncherTestUtils jobLauncherTestUtils;
    private final JobRepositoryTestUtils jobRepositoryTestUtils;
    private final DailyTransactionRepository dailyTransactionRepository;
    private final CardXrefRepository cardXrefRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionCategoryBalanceRepository tranCatBalanceRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Job dailyTransactionPostingJob;

    DailyTransactionPostingJobTest(
            JobLauncherTestUtils jobLauncherTestUtils,
            JobRepositoryTestUtils jobRepositoryTestUtils,
            DailyTransactionRepository dailyTransactionRepository,
            CardXrefRepository cardXrefRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            TransactionCategoryBalanceRepository tranCatBalanceRepository,
            JdbcTemplate jdbcTemplate,
            @Qualifier(JOB_NAME) Job dailyTransactionPostingJob) {
        this.jobLauncherTestUtils = jobLauncherTestUtils;
        this.jobRepositoryTestUtils = jobRepositoryTestUtils;
        this.dailyTransactionRepository = dailyTransactionRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.tranCatBalanceRepository = tranCatBalanceRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.dailyTransactionPostingJob = dailyTransactionPostingJob;
    }

    @BeforeEach
    void setUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        removeRejectArtifacts();
        cleanFixtures();
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
        jobRepositoryTestUtils.removeJobExecutions();
        removeRejectArtifacts();
    }

    // ------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("context loads and the dailyTransactionPostingJob bean is registered")
    void contextLoadsAndJobRegistered() {
        assertThat(dailyTransactionPostingJob).isNotNull();
        assertThat(dailyTransactionPostingJob.getName()).isEqualTo(JOB_NAME);
        // The JobLauncherTestUtils is bound to the posting job, not an arbitrary job bean.
        assertThat(jobLauncherTestUtils.getJob()).isSameAs(dailyTransactionPostingJob);
    }

    @Test
    @DisplayName("valid transactions post cleanly (RC0); 2700/2800/2900 side-effects exact to the cent")
    void validTransactionsPostedCleanly_RC0() throws Exception {
        long acctId = 1L;
        long custId = 1L;
        String card = "0000000000000001";
        seedCustomer(custId);
        // currBal 100.00, credit limit 100000.00, cycle credit/debit 0.00, never expires.
        seedAccount(acctId, "100.00", "100000.00", "0.00", "0.00", "9999-12-31");
        seedCard(card, acctId);
        seedXref(card, custId, acctId);
        seedCatBalance(acctId, "5.00");
        // Two transactions on the same account exercise both the credit (+) and debit (-) branches of
        // 2800 and the sequential (chunk-size-1) accumulation.
        stage(
                newDaily("VALIDPOST0000001", card, "30.00", "2024-03-03 09:00:00.000000"),
                newDaily("VALIDPOST0000002", card, "-10.00", "2024-03-03 09:05:00.000000"));

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(execution.getAllFailureExceptions()).isEmpty();
        assertThat(stepExitCode(execution)).isEqualTo(ExitStatus.COMPLETED.getExitCode());

        // 2900 POST-TRANSACTION: two rows keyed by the DALYTRAN id (tran_id == dalytran_id).
        assertThat(transactionRepository.count()).isEqualTo(2L);
        Transaction posted1 = transactionRepository.findById("VALIDPOST0000001").orElseThrow();
        assertThat(posted1.getTranAmt()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(posted1.getCardNum()).isEqualTo(card);
        Transaction posted2 = transactionRepository.findById("VALIDPOST0000002").orElseThrow();
        assertThat(posted2.getTranAmt()).isEqualByComparingTo(new BigDecimal("-10.00"));

        // 2800 ACCOUNT UPDATE: ADD DALYTRAN-AMT TO ACCT-CURR-BAL => 100 + 30 - 10 = 120.00;
        // credit branch adds +30.00, debit branch adds the negative -10.00.
        Account acct = accountRepository.findById(acctId).orElseThrow();
        assertThat(acct.getCurrBal()).isEqualByComparingTo(new BigDecimal("120.00"));
        assertThat(acct.getCurrBal().scale()).isEqualTo(AMT_SCALE);
        assertThat(acct.getCurrCycCredit()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(acct.getCurrCycCredit().scale()).isEqualTo(AMT_SCALE);
        assertThat(acct.getCurrCycDebit()).isEqualByComparingTo(new BigDecimal("-10.00"));
        assertThat(acct.getCurrCycDebit().scale()).isEqualTo(AMT_SCALE);

        // 2700 TRAN-CAT-BALANCE UPDATE: 5.00 + 30.00 - 10.00 = 25.00.
        TransactionCategoryBalance tcb = tranCatBalanceRepository
                .findById(new TransactionCategoryBalanceId(acctId, TYPE_CD, CAT_CD)).orElseThrow();
        assertThat(tcb.getBal()).isEqualByComparingTo(new BigDecimal("25.00"));
        assertThat(tcb.getBal().scale()).isEqualTo(AMT_SCALE);

        // A clean run publishes an EMPTY final reject file (no rejects).
        assertThat(readRejectLines()).isEmpty();
    }

    @Test
    @DisplayName("reject 100 - INVALID CARD NUMBER FOUND (cross-reference miss; not posted)")
    void reject100_invalidCardNumber() throws Exception {
        // No cross-reference seeded for this card => 1500-A-LOOKUP-XREF INVALID KEY => reason 100.
        String card = "1111111111111111";
        stage(newDaily("REJECT100AAA0001", card, "25.00", "2024-03-03 09:00:00.000000"));

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepExitCode(execution)).isEqualTo(EXIT_COMPLETED_WITH_REJECTS);

        List<String> rejects = readRejectLines();
        assertThat(rejects).hasSize(1);
        String record = rejects.get(0);
        assertThat(record).hasSize(REJECT_RECORD_LENGTH);
        assertReject(record, RejectCode.INVALID_CARD_NUMBER, card, new BigDecimal("25.00"));

        // Nothing is posted for a rejected record.
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    @DisplayName("reject 102 - OVERLIMIT TRANSACTION (ACCT-CREDIT-LIMIT < WS-TEMP-BAL)")
    void reject102_overlimit() throws Exception {
        long acctId = 102L;
        long custId = 102L;
        String card = "2222222222222222";
        seedCustomer(custId);
        // Credit limit 100.00; WS-TEMP-BAL = 0 - 0 + 500 = 500 > 100 => overlimit. Never expires.
        seedAccount(acctId, "0.00", "100.00", "0.00", "0.00", "9999-12-31");
        seedXref(card, custId, acctId);
        stage(newDaily("REJECT102AAA0001", card, "500.00", "2024-03-03 09:00:00.000000"));

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepExitCode(execution)).isEqualTo(EXIT_COMPLETED_WITH_REJECTS);

        List<String> rejects = readRejectLines();
        assertThat(rejects).hasSize(1);
        assertReject(rejects.get(0), RejectCode.OVER_CREDIT_LIMIT, card, new BigDecimal("500.00"));

        // A reject leaves the account untouched and posts nothing.
        assertThat(transactionRepository.count()).isZero();
        Account acct = accountRepository.findById(acctId).orElseThrow();
        assertThat(acct.getCurrBal()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(acct.getCurrCycCredit()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(acct.getCurrCycDebit()).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("reject 103 - TRANSACTION RECEIVED AFTER ACCT EXPIRATION (within limit, expired)")
    void reject103_afterExpiration() throws Exception {
        long acctId = 103L;
        long custId = 103L;
        String card = "3333333333333333";
        seedCustomer(custId);
        // Huge limit => within limit; expiration 2020-01-01 < ORIG-TS date 2024-03-03 => expired.
        seedAccount(acctId, "0.00", "100000.00", "0.00", "0.00", "2020-01-01");
        seedXref(card, custId, acctId);
        stage(newDaily("REJECT103AAA0001", card, "10.00", "2024-03-03 09:00:00.000000"));

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepExitCode(execution)).isEqualTo(EXIT_COMPLETED_WITH_REJECTS);

        List<String> rejects = readRejectLines();
        assertThat(rejects).hasSize(1);
        assertReject(rejects.get(0), RejectCode.ACCOUNT_EXPIRED, card, new BigDecimal("10.00"));
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    @DisplayName("reject 102+103 last-writer-wins: overlimit AND expired records reason 103")
    void reject_lastWriterWins_102and103_bothFail_yields103() throws Exception {
        long acctId = 199L;
        long custId = 199L;
        String card = "1990000000000001";
        seedCustomer(custId);
        // Overlimit (limit 100 < WS-TEMP-BAL 500) AND expired (2020-01-01 < 2024-03-03). The two
        // independent IF blocks both fail; because 103 is evaluated after 102 and writes the same
        // reason field, 103 overwrites 102 (LAST-WRITER-WINS).
        seedAccount(acctId, "0.00", "100.00", "0.00", "0.00", "2020-01-01");
        seedXref(card, custId, acctId);
        stage(newDaily("REJECT103WINS001", card, "500.00", "2024-03-03 09:00:00.000000"));

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepExitCode(execution)).isEqualTo(EXIT_COMPLETED_WITH_REJECTS);

        List<String> rejects = readRejectLines();
        assertThat(rejects).hasSize(1);
        String record = rejects.get(0);
        // 103 wins; it is NOT recorded as 102.
        assertThat(reasonCodeOf(record)).isEqualTo("0103");
        assertThat(reasonCodeOf(record)).isNotEqualTo("0102");
        assertReject(record, RejectCode.ACCOUNT_EXPIRED, card, new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("RC4 - a mix of posted and rejected records maps to COMPLETED_WITH_REJECTS")
    void returnCode4_whenAnyRejectsPresent() throws Exception {
        long acctId = 4L;
        long custId = 4L;
        String validCard = "0000000000000004";
        String invalidCard = "4444444444444444"; // no cross-reference => reject 100
        seedCustomer(custId);
        seedAccount(acctId, "0.00", "100000.00", "0.00", "0.00", "9999-12-31");
        seedCard(validCard, acctId);
        seedXref(validCard, custId, acctId);
        seedCatBalance(acctId, "0.00");
        stage(
                newDaily("MIXVALID00000001", validCard, "15.00", "2024-03-03 09:00:00.000000"),
                newDaily("MIXREJECT0000001", invalidCard, "20.00", "2024-03-03 09:05:00.000000"));

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());

        // The batch status is COMPLETED; the RC4 signal is carried on the step exit code.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepExitCode(execution)).isEqualTo(EXIT_COMPLETED_WITH_REJECTS);

        // Exactly the valid record posted; the invalid one was rejected with reason 100.
        assertThat(transactionRepository.count()).isEqualTo(1L);
        assertThat(transactionRepository.findById("MIXVALID00000001")).isPresent();
        List<String> rejects = readRejectLines();
        assertThat(rejects).hasSize(1);
        assertReject(rejects.get(0), RejectCode.INVALID_CARD_NUMBER, invalidCard, new BigDecimal("20.00"));
    }

    @Test
    @DisplayName("RC8 - a reject-file I/O error fails the job with ExitStatus.FAILED")
    void returnCode8_onFileIoError() throws Exception {
        // Pre-create a DIRECTORY where the writer will attempt to open the temporary reject FILE.
        // Opening an OutputStream on a directory fails deterministically (even as root), so
        // beforeStep raises a FileStatusException => sticky I/O error => RC 8.
        Path blocker = REJECT_DIR.resolve(REJECT_FILE_NAME + ".tmp");
        stage(newDaily("RC8TXN0000000001", "1111111111111111", "25.00", "2024-03-03 09:00:00.000000"));
        try {
            Files.createDirectory(blocker);

            JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.FAILED.getExitCode());
            assertThat(execution.getAllFailureExceptions()).isNotEmpty();
        } finally {
            // Remove the blocking directory so subsequent runs (and cleanup) are not impeded.
            Files.deleteIfExists(blocker);
        }
    }

    @Test
    @DisplayName("produced reject records are byte-for-byte equal to the golden fixtures (100/102/103)")
    void rejectFileMatchesGolden() throws Exception {
        assertRejectMatchesGolden("golden/reject/expected-reject-100.dat", RejectCode.INVALID_CARD_NUMBER);
        assertRejectMatchesGolden("golden/reject/expected-reject-102.dat", RejectCode.OVER_CREDIT_LIMIT);
        assertRejectMatchesGolden("golden/reject/expected-reject-103.dat", RejectCode.ACCOUNT_EXPIRED);
    }

    // ------------------------------------------------------------------------
    // Golden-file helpers
    // ------------------------------------------------------------------------

    /**
     * Reproduces a golden reject record end-to-end: decode the 350-byte DALYTRAN image from the
     * fixture, arrange the minimal account state that triggers {@code expected}, run the job, then
     * assert the produced 430-byte record equals the golden fixture byte-for-byte (no trimming). Each
     * invocation runs in its own clean cycle so multiple goldens can be verified within one test.
     */
    private void assertRejectMatchesGolden(String goldenResource, RejectCode expected) throws Exception {
        String golden = readGolden(goldenResource);
        assertThat(golden).as("golden %s must be a 430-byte record", goldenResource)
                .hasSize(REJECT_RECORD_LENGTH);

        // Fresh slate for this golden's own launch cycle.
        cleanFixtures();
        removeRejectArtifacts();
        jobRepositoryTestUtils.removeJobExecutions();

        DailyTransaction tx = decodeGoldenDailyTransaction(golden);
        arrangeForRejectCode(expected, tx.getCardNum());
        stage(tx);

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepExitCode(execution)).isEqualTo(EXIT_COMPLETED_WITH_REJECTS);

        List<String> produced = readRejectLines();
        assertThat(produced).as("exactly one reject record for golden %s", goldenResource).hasSize(1);
        assertThat(produced.get(0))
                .as("reject record must be byte-for-byte equal to golden %s", goldenResource)
                .isEqualTo(golden);
    }

    /** Arranges the minimal seed state that drives the given reject code for {@code card}. */
    private void arrangeForRejectCode(RejectCode code, String card) {
        switch (code) {
            case INVALID_CARD_NUMBER ->
                // No cross-reference / account seeded => 1500-A-LOOKUP-XREF miss => reason 100.
                    doNothing();
            case OVER_CREDIT_LIMIT -> {
                long acctId = 900002L;
                long custId = 900002L;
                seedCustomer(custId);
                // Credit limit 100.00 is below the golden amount (500.00) => overlimit; never expires.
                seedAccount(acctId, "0.00", "100.00", "0.00", "0.00", "9999-12-31");
                seedXref(card, custId, acctId);
            }
            case ACCOUNT_EXPIRED -> {
                long acctId = 900003L;
                long custId = 900003L;
                seedCustomer(custId);
                // Within limit; expiration 2020-01-01 precedes the golden ORIG-TS date => expired.
                seedAccount(acctId, "0.00", "100000.00", "0.00", "0.00", "2020-01-01");
                seedXref(card, custId, acctId);
            }
            default -> throw new IllegalArgumentException(
                    "Reject code " + code + " is not integration-testable in this harness");
        }
    }

    /** No-op used by the reason-100 arrange arm to keep the switch statement explicit. */
    private void doNothing() {
        // Intentionally empty: reason 100 requires the ABSENCE of a cross-reference.
    }

    /** Reads a golden fixture as an ISO-8859-1 string (one byte per character). */
    private String readGolden(String resource) throws IOException {
        byte[] bytes = new ClassPathResource(resource).getContentAsByteArray();
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    /** Decodes the 350-byte DALYTRAN image prefix of a reject record into a staging entity. */
    private DailyTransaction decodeGoldenDailyTransaction(String image) {
        String id = FixedWidthCodec.readAlphanumericTrimmed(image, OFF_ID, LEN_ID);
        String typeCd = FixedWidthCodec.readAlphanumericTrimmed(image, OFF_TYPE_CD, LEN_TYPE_CD);
        int catCd = FixedWidthCodec.readNumericInt(image, OFF_CAT_CD, LEN_CAT_CD);
        String source = FixedWidthCodec.readAlphanumericTrimmed(image, OFF_SOURCE, LEN_SOURCE);
        String desc = FixedWidthCodec.readAlphanumericTrimmed(image, OFF_DESC, LEN_DESC);
        BigDecimal amt = FixedWidthCodec.readSignedDecimal(image, OFF_AMT, LEN_AMT, AMT_SCALE);
        long merchantId = FixedWidthCodec.readNumeric(image, OFF_MERCHANT_ID, LEN_MERCHANT_ID);
        String merchantName = FixedWidthCodec.readAlphanumericTrimmed(image, OFF_MERCHANT_NAME, LEN_MERCHANT_NAME);
        String merchantCity = FixedWidthCodec.readAlphanumericTrimmed(image, OFF_MERCHANT_CITY, LEN_MERCHANT_CITY);
        String merchantZip = FixedWidthCodec.readAlphanumericTrimmed(image, OFF_MERCHANT_ZIP, LEN_MERCHANT_ZIP);
        String cardNum = FixedWidthCodec.readAlphanumericTrimmed(image, OFF_CARD_NUM, LEN_CARD_NUM);
        String origTs = FixedWidthCodec.readAlphanumericTrimmed(image, OFF_ORIG_TS, LEN_ORIG_TS);
        String procTs = FixedWidthCodec.readAlphanumericTrimmed(image, OFF_PROC_TS, LEN_PROC_TS);
        return new DailyTransaction(id, typeCd, catCd, source, desc, amt, merchantId,
                merchantName, merchantCity, merchantZip, cardNum, origTs, procTs);
    }

    // ------------------------------------------------------------------------
    // Assertion + decoding helpers
    // ------------------------------------------------------------------------

    /**
     * Asserts a 430-byte reject record carries the expected reason code (zero-padded {@code PIC 9(04)}
     * at offset 350) and description (left-justified, space-padded {@code PIC X(76)} at offset 354),
     * and that the DALYTRAN image round-trips the card number and amount.
     */
    private void assertReject(String record, RejectCode expected, String card, BigDecimal amt) {
        assertThat(record).hasSize(REJECT_RECORD_LENGTH);
        assertThat(reasonCodeOf(record)).isEqualTo(String.format("%04d", expected.getCode()));
        assertThat(reasonDescOf(record)).isEqualTo(padRight(expected.getDescription(), LEN_REASON_DESC));
        assertThat(reasonDescOf(record).strip()).isEqualTo(expected.getDescription());
        assertThat(cardNumOf(record)).isEqualTo(card);
        assertThat(amtOf(record)).isEqualByComparingTo(amt);
    }

    private String reasonCodeOf(String record) {
        return record.substring(OFF_REASON_CODE, OFF_REASON_CODE + LEN_REASON_CODE);
    }

    private String reasonDescOf(String record) {
        return record.substring(OFF_REASON_DESC, OFF_REASON_DESC + LEN_REASON_DESC);
    }

    private String cardNumOf(String record) {
        return FixedWidthCodec.readAlphanumericTrimmed(record, OFF_CARD_NUM, LEN_CARD_NUM);
    }

    private BigDecimal amtOf(String record) {
        return FixedWidthCodec.readSignedDecimal(record, OFF_AMT, LEN_AMT, AMT_SCALE);
    }

    private static String padRight(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        StringBuilder sb = new StringBuilder(width);
        sb.append(value);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------
    // Launch + fixture helpers
    // ------------------------------------------------------------------------

    private JobParameters uniqueParameters() {
        return new JobParametersBuilder()
                .addLong("run.id", RUN_ID.incrementAndGet())
                .toJobParameters();
    }

    /** Returns the exit code of the posting step (authoritative for the RC 0/4/8 mapping). */
    private String stepExitCode(JobExecution execution) {
        return execution.getStepExecutions().stream()
                .filter(step -> STEP_NAME.equals(step.getStepName()))
                .map(step -> step.getExitStatus().getExitCode())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Step '" + STEP_NAME + "' not found"));
    }

    private void seedCustomer(long custId) {
        jdbcTemplate.update("INSERT INTO customer (cust_id) VALUES (?)", custId);
    }

    private void seedAccount(long acctId, String currBal, String creditLimit,
            String currCycCredit, String currCycDebit, String expirationDate) {
        Account account = new Account(
                acctId,
                "Y",
                new BigDecimal(currBal),
                new BigDecimal(creditLimit),
                new BigDecimal("0.00"),
                "2000-01-01",
                expirationDate,
                "2000-01-01",
                new BigDecimal(currCycCredit),
                new BigDecimal(currCycDebit),
                "98101",
                null);
        accountRepository.save(account);
    }

    private void seedCard(String cardNum, long acctId) {
        jdbcTemplate.update("INSERT INTO card (card_num, acct_id) VALUES (?, ?)", cardNum, acctId);
    }

    private void seedXref(String cardNum, long custId, long acctId) {
        cardXrefRepository.save(new CardXref(cardNum, custId, acctId));
    }

    private void seedCatBalance(long acctId, String bal) {
        tranCatBalanceRepository.save(new TransactionCategoryBalance(
                new TransactionCategoryBalanceId(acctId, TYPE_CD, CAT_CD), new BigDecimal(bal)));
    }

    private DailyTransaction newDaily(String id, String cardNum, String amt, String origTs) {
        return new DailyTransaction(
                id,
                TYPE_CD,
                CAT_CD,
                "POS TERM",
                "Integration posting transaction",
                new BigDecimal(amt),
                800000001L,
                "Test Merchant",
                "Testville",
                "75001",
                cardNum,
                origTs,
                "");
    }

    private void stage(DailyTransaction... transactions) {
        dailyTransactionRepository.saveAll(List.of(transactions));
    }

    private List<String> readRejectLines() {
        Path finalFile = REJECT_DIR.resolve(REJECT_FILE_NAME);
        if (!Files.exists(finalFile)) {
            return List.of();
        }
        try {
            return Files.readAllLines(finalFile, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read reject file " + finalFile, e);
        }
    }

    /** Deletes all seeded rows in child-to-parent foreign-key order. */
    private void cleanFixtures() {
        dailyTransactionRepository.deleteAll();
        transactionRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM card");
        cardXrefRepository.deleteAll();
        tranCatBalanceRepository.deleteAll();
        accountRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM customer");
    }

    private void removeRejectArtifacts() {
        try {
            Files.deleteIfExists(REJECT_DIR.resolve(REJECT_FILE_NAME));
            Files.deleteIfExists(REJECT_DIR.resolve(REJECT_FILE_NAME + ".tmp"));
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to clean reject artifacts under " + REJECT_DIR, e);
        }
    }

    private static Path createRejectDirectory() {
        try {
            return Files.createTempDirectory("carddemo-posting-reject-");
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create a temporary reject directory", e);
        }
    }

    // ------------------------------------------------------------------------
    // Test configuration - bind JobLauncherTestUtils to the posting job because
    // the context contains multiple Job beans.
    // ------------------------------------------------------------------------

    @TestConfiguration
    static class BatchTestConfig {

        @Bean
        @Primary
        JobLauncherTestUtils postingJobLauncherTestUtils(
                @Qualifier(JOB_NAME) Job dailyTransactionPostingJob,
                JobLauncher jobLauncher,
                JobRepository jobRepository) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJob(dailyTransactionPostingJob);
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            return utils;
        }
    }
}
