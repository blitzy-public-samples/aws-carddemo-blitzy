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
package com.aws.carddemo.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.domain.id.TransactionCategoryBalanceId;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.DailyTransactionRepository;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.repository.TransactionRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Parity-archetype Spring Batch integration test for {@link TransactionPostingJobConfig} — the
 * single chunk-oriented step in the migration and the Java translation of the legacy COBOL batch
 * program {@code CBTRN02C} (source {@code legacy/app/cbl/CBTRN02C.cbl}) driven by JCL {@code
 * legacy/app/jcl/POSTTRAN.jcl} (single step {@code STEP15 EXEC PGM=CBTRN02C}).
 *
 * <p><strong>What parity guarantee is under test (AAP &sect;0.6.4, &sect;0.6.6).</strong> For every
 * inbound daily transaction the job validates the record against the card cross-reference and
 * account master and then either <em>posts</em> it (a transaction-category-balance upsert, an
 * account-balance update and an insert into the transaction master) or <em>rejects</em> it (writing
 * a fixed 430-byte record to the {@code DALYREJS} reject file). The headline parity rule is the
 * exit status: COBOL {@code CBTRN02C} executes {@code MOVE 4 TO RETURN-CODE} when {@code
 * WS-REJECT-COUNT > 0} — it is the only batch program that sets {@code RC=4}. The Spring Batch step
 * therefore reports exit code {@link TransactionPostingJobConfig#REJECT_EXIT_CODE} ({@code "4"})
 * while its {@link BatchStatus} stays {@link BatchStatus#COMPLETED} when at least one record was
 * rejected: rejects are a normal business outcome, never an abend. A clean run leaves the
 * framework-default success exit status, and the posting writes-through to the database.
 *
 * <p><strong>Reject-record layout (parity-critical).</strong> Each {@code DALYREJS} line is exactly
 * {@value #REJECT_RECORD_LENGTH} characters: the 350-byte verbatim {@code DALYTRAN} image followed
 * by an 80-byte validation trailer (a {@code PIC 9(04)} zero-padded reason code in columns 351–354
 * and a {@code PIC X(76)} space-padded description in columns 355–430), matching {@code
 * DCB=(RECFM=F,LRECL=430,...)} on the {@code DALYREJS} DD. This test asserts that structural layout
 * for the invalid-card scenario (reason {@code 0100 INVALID CARD NUMBER FOUND}).
 *
 * <p><strong>Local-only, VSAM-faithful wiring (AAP &sect;0.6.7).</strong> Verification runs against
 * a real <b>Testcontainers PostgreSQL 16</b> instance bound to the Spring data source by {@link
 * ServiceConnection}, which registers a {@code JdbcConnectionDetails} bean that supersedes the
 * {@code spring.datasource.*} properties of the {@code test} profile — the same proven pattern used
 * by the sibling batch-config integration tests. The Flyway schema is the exact VSAM-faithful one
 * ({@code numeric(p,s)} decimal scale, {@code char(n)} fixed-width keys), so lookups and decimal
 * arithmetic behave identically to the mainframe; no running COBOL/mainframe environment is
 * required.
 *
 * <p><strong>Why a manually wired {@link JobLauncherTestUtils} (no
 * {@code @SpringBatchTest}).</strong> The project deliberately omits {@code @EnableBatchProcessing}
 * so Spring Boot's batch auto-configuration supplies the {@link JobRepository} and {@link
 * JobLauncher}. Rather than relying on {@code @SpringBatchTest} (which expects a single primary
 * {@code Job} bean), this test constructs {@link JobLauncherTestUtils} by hand in {@link #setUp()}
 * and injects the autowired launcher, repository and the {@code @Qualifier}-selected {@code
 * transactionPostingJob} bean — keeping the wiring explicit even though several {@code Job} beans
 * exist in the context. A {@link JobRepositoryTestUtils} clears prior batch metadata before each
 * test so every run is an independent {@code JobInstance} ({@code junit-platform.properties} keeps
 * the suite sequential).
 *
 * <p><strong>Scope constraint.</strong> The production processor returns a {@code
 * TransactionPostingService.PostingResult} whose accessor names are intentionally not finalized;
 * this test therefore operates strictly at the job / step / output-file / database level and never
 * references any {@code PostingResult} accessor. All monetary values use {@link BigDecimal} only.
 *
 * <p>The class and its {@code @Test} methods are package-private by project convention, and {@link
 * DisplayNameGeneration} renders the underscore-separated method names as readable phrases.
 *
 * @see TransactionPostingJobConfig
 * @see com.aws.carddemo.service.batch.TransactionPostingService
 * @see DailyTransactionPostJobConfigTest
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TransactionPostingJobConfigTest {

  /** Bean name of the single chunk-oriented step of {@code transactionPostingJob}. */
  private static final String STEP_NAME = "transactionPostingStep";

  /** Job-parameter key that selects the per-run output directory for the {@code DALYREJS} file. */
  private static final String OUTPUT_DIR_PARAM = "outputDir";

  /** File name of the reject dataset written under the {@code outputDir}, matching the JCL DD. */
  private static final String REJECT_FILE_NAME = "DALYREJS";

  /**
   * Fixed logical record length of every {@code DALYREJS} line: 350-byte {@code DALYTRAN} image +
   * 80-byte validation trailer, matching {@code DCB=(RECFM=F,LRECL=430,...)}.
   */
  private static final int REJECT_RECORD_LENGTH = 430;

  /** Controlled, synthetic account id seeded as the single valid account for the posting run. */
  private static final long CONTROLLED_ACCT_ID = 99999999999L;

  /** Controlled, synthetic card number cross-referenced to {@link #CONTROLLED_ACCT_ID}. */
  private static final String CONTROLLED_CARD_NUM = "9999999999999999";

  /** Transaction type code seeded by the V2 reference migration ({@code tran_type '01'}). */
  private static final String TYPE_CD = "01";

  /** Transaction category code seeded by the V2 reference migration ({@code ('01','0001')}). */
  private static final String CAT_CD = "0001";

  /**
   * Shared, class-scoped PostgreSQL 16 container. Started by the {@code @Testcontainers} extension
   * and bound to the Spring data source by {@link ServiceConnection}; the {@code alpine} tag
   * matches the cached image used across the test suite.
   */
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  /** Boot-provided Spring Batch launcher used to run the job under test. */
  @Autowired private JobLauncher jobLauncher;

  /** Boot-provided Spring Batch job repository (metadata store) shared by the test utilities. */
  @Autowired private JobRepository jobRepository;

  /** The job under test, selected by bean name to disambiguate it from the other batch jobs. */
  @Autowired
  @Qualifier("transactionPostingJob")
  private Job transactionPostingJob;

  /** Repository for the account master the posting run reads and rewrites. */
  @Autowired private AccountRepository accountRepository;

  /** Repository for the card cross-reference the posting run reads by card number. */
  @Autowired private CardXrefRepository cardXrefRepository;

  /** Repository for the sequential daily-transaction input the chunk reader pages through. */
  @Autowired private DailyTransactionRepository dailyTransactionRepository;

  /** Repository for the transaction master the posting run writes posted transactions into. */
  @Autowired private TransactionRepository transactionRepository;

  /** Repository for the transaction-category-balance store the posting run upserts. */
  @Autowired private TransactionCategoryBalanceRepository tcatBalRepository;

  /** Fresh per-test output directory; the {@code DALYREJS} reject file is written here. */
  @TempDir Path tempDir;

  /** Manually wired Spring Batch launcher helper (constructed per test in {@link #setUp()}). */
  private JobLauncherTestUtils jobLauncherTestUtils;

  /**
   * Prepares a clean, deterministic state before every test: clears any prior Spring Batch
   * execution metadata, wires a fresh {@link JobLauncherTestUtils} to the {@code
   * transactionPostingJob}, resets the working and category-balance tables (leaving the V2 {@code
   * tran_type} / {@code tran_category} reference rows intact), and seeds exactly one valid account
   * and its cross-reference.
   *
   * <p>Clearing {@code tran_cat_balance} guarantees the find-or-create upsert in the clean-path
   * test starts from an empty store, so the balance the run creates is the only row for the
   * controlled key (AAP &sect;0.6.4). The seeded account is well within its credit limit and has a
   * far-future expiration date, so a valid transaction posts without tripping the overlimit (reason
   * {@code 102}) or expiration (reason {@code 103}) checks.
   */
  @BeforeEach
  void setUp() {
    new JobRepositoryTestUtils(jobRepository).removeJobExecutions();

    jobLauncherTestUtils = new JobLauncherTestUtils();
    jobLauncherTestUtils.setJobLauncher(jobLauncher);
    jobLauncherTestUtils.setJobRepository(jobRepository);
    jobLauncherTestUtils.setJob(transactionPostingJob);

    dailyTransactionRepository.deleteAll();
    transactionRepository.deleteAll();
    accountRepository.deleteAll();
    cardXrefRepository.deleteAll();
    tcatBalRepository.deleteAll();

    Account account = new Account();
    account.setAcctId(CONTROLLED_ACCT_ID);
    account.setAcctActiveStatus("Y");
    account.setAcctCurrBal(new BigDecimal("0.00"));
    account.setAcctCreditLimit(new BigDecimal("99999.99"));
    account.setAcctCashCreditLimit(new BigDecimal("99999.99"));
    account.setAcctOpenDate("2020-01-01");
    account.setAcctExpiraionDate("2099-12-31");
    account.setAcctReissueDate("2020-01-01");
    account.setAcctCurrCycCredit(new BigDecimal("0.00"));
    account.setAcctCurrCycDebit(new BigDecimal("0.00"));
    account.setAcctAddrZip("00000");
    account.setAcctGroupId("DEFAULT");
    accountRepository.save(account);

    CardXref xref = new CardXref();
    xref.setXrefCardNum(CONTROLLED_CARD_NUM);
    xref.setXrefCustId(999999999L);
    xref.setXrefAcctId(CONTROLLED_ACCT_ID);
    cardXrefRepository.save(xref);
  }

  /**
   * Reject path (the headline parity case): a daily transaction whose card is absent from the card
   * cross-reference fails the {@code 1500-A-LOOKUP-XREF} lookup ({@code INVALID KEY} / FILE STATUS
   * {@code '23'}), so the run rejects it with reason {@code 100}. The step and job both COMPLETE —
   * <em>not</em> FAIL — with exit code {@link TransactionPostingJobConfig#REJECT_EXIT_CODE} ({@code
   * "4"}), reproducing COBOL {@code MOVE 4 TO RETURN-CODE}. The single {@code DALYREJS} record is
   * verified to be exactly {@value #REJECT_RECORD_LENGTH} characters wide with the rejected
   * transaction id in columns 1–16 and the {@code 0100 INVALID CARD NUMBER FOUND} trailer in
   * columns 351–430.
   *
   * @throws Exception if launching the job fails (propagated to fail the test)
   */
  @Test
  void posting_with_invalid_card_completes_with_exit_code_4_and_writes_DALYREJS() throws Exception {
    // Card "0000000000000000" has no card_xref row -> reason 100 (INVALID CARD NUMBER FOUND).
    dailyTransactionRepository.save(
        newDaily("0000000000000002", "0000000000000000", new BigDecimal("50.00")));

    JobExecution execution =
        jobLauncherTestUtils.launchJob(
            new JobParametersBuilder()
                .addString(OUTPUT_DIR_PARAM, tempDir.toString())
                .toJobParameters());

    StepExecution postingStep = postingStep(execution);

    // Exit-status parity: a reject is a business outcome, so BatchStatus stays COMPLETED while the
    // exit code is the reject-driven "4" (COBOL RETURN-CODE 4), both at the step and the job level.
    assertThat(postingStep.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(postingStep.getExitStatus().getExitCode())
        .isEqualTo(TransactionPostingJobConfig.REJECT_EXIT_CODE);
    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(execution.getExitStatus().getExitCode())
        .isEqualTo(TransactionPostingJobConfig.REJECT_EXIT_CODE);

    // DALYREJS structural parity: exactly one fixed 430-char record with the 0100 reason trailer.
    Path rejectFile = tempDir.resolve(REJECT_FILE_NAME);
    assertThat(rejectFile).exists();

    List<String> rejectLines = Files.readAllLines(rejectFile, StandardCharsets.UTF_8);
    assertThat(rejectLines).hasSize(1);

    String rejectRecord = rejectLines.get(0);
    assertThat(rejectRecord).hasSize(REJECT_RECORD_LENGTH);
    // Columns 1-16: the verbatim DALYTRAN-ID image of the rejected transaction.
    assertThat(rejectRecord.substring(0, 16)).isEqualTo("0000000000000002");
    // Columns 351-354: the PIC 9(04) zero-padded reason code.
    assertThat(rejectRecord.substring(350, 354)).isEqualTo("0100");
    // Columns 355-430: the PIC X(76) space-padded reason description.
    assertThat(rejectRecord.substring(354, 430).trim()).isEqualTo("INVALID CARD NUMBER FOUND");
  }

  /**
   * Clean path: a single valid daily transaction (matching the seeded account and card
   * cross-reference, within the credit limit and before expiration) is posted. The step and job
   * COMPLETE with the framework-default success exit status — explicitly not the {@code "4"} reject
   * code — and no {@code DALYREJS} content is produced. Posting writes through to all three stores:
   * the transaction master receives the posted transaction, the category balance is created by the
   * find-or-create upsert with the posted amount, and the account balance is increased by that
   * amount (COBOL {@code ADD DALYTRAN-AMT TO ACCT-CURR-BAL} in {@code 2800-UPDATE-ACCOUNT-REC}).
   *
   * @throws Exception if launching the job fails (propagated to fail the test)
   */
  @Test
  void posting_with_valid_transaction_completes_and_persists() throws Exception {
    BigDecimal amount = new BigDecimal("100.00");
    dailyTransactionRepository.save(newDaily("0000000000000001", CONTROLLED_CARD_NUM, amount));

    BigDecimal originalBalance =
        accountRepository.findById(CONTROLLED_ACCT_ID).orElseThrow().getAcctCurrBal();

    JobExecution execution =
        jobLauncherTestUtils.launchJob(
            new JobParametersBuilder()
                .addString(OUTPUT_DIR_PARAM, tempDir.toString())
                .toJobParameters());

    StepExecution postingStep = postingStep(execution);

    // No rejects: COMPLETED with the default success exit status, never the reject-driven "4".
    assertThat(postingStep.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(postingStep.getExitStatus().getExitCode())
        .isIn("0", "COMPLETED")
        .isNotEqualTo(TransactionPostingJobConfig.REJECT_EXIT_CODE);
    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

    // The reject file, if the writer created it at step start, is empty for a clean run.
    Path rejectFile = tempDir.resolve(REJECT_FILE_NAME);
    if (Files.exists(rejectFile)) {
      assertThat(Files.readAllLines(rejectFile, StandardCharsets.UTF_8)).isEmpty();
    }

    // Persistence parity: the valid daily was posted to the transaction master.
    assertThat(transactionRepository.count()).isGreaterThanOrEqualTo(1L);

    // TCATBAL find-or-create upsert (AAP §0.6.4): created from zero, then the amount was added.
    TransactionCategoryBalanceId tcatBalId =
        new TransactionCategoryBalanceId(CONTROLLED_ACCT_ID, TYPE_CD, CAT_CD);
    assertThat(tcatBalRepository.findById(tcatBalId)).isPresent();
    assertThat(tcatBalRepository.findById(tcatBalId).orElseThrow().getTranCatBal())
        .isEqualByComparingTo(amount);

    // Account balance updated by exactly the posted amount (the magnitude and the sign).
    BigDecimal updatedBalance =
        accountRepository.findById(CONTROLLED_ACCT_ID).orElseThrow().getAcctCurrBal();
    assertThat(updatedBalance).isNotEqualByComparingTo(originalBalance);
    assertThat(updatedBalance).isEqualByComparingTo(originalBalance.add(amount));
  }

  /**
   * Builds a fully populated {@link DailyTransaction} with all {@code NOT NULL} columns of copybook
   * {@code CVTRA06Y} set. The type and category codes are the V2-seeded {@code ('01','0001')}
   * lookup pair; the origination timestamp's {@code YYYY-MM-DD} prefix predates the controlled
   * account's far-future expiration date so the expiration check passes; the processing timestamp
   * is blank (the posting run stamps it), matching the inbound daily-transaction record.
   *
   * @param id the {@code DALYTRAN-ID} primary key (16 characters)
   * @param cardNum the {@code DALYTRAN-CARD-NUM} used for the cross-reference lookup (16
   *     characters)
   * @param amount the signed {@code DALYTRAN-AMT} ({@link BigDecimal}, scale 2)
   * @return a persistable daily transaction
   */
  private DailyTransaction newDaily(String id, String cardNum, BigDecimal amount) {
    DailyTransaction daily = new DailyTransaction();
    daily.setDalytranId(id);
    daily.setDalytranTypeCd(TYPE_CD);
    daily.setDalytranCatCd(CAT_CD);
    daily.setDalytranSource("POS");
    daily.setDalytranDesc("PARITY TEST TRANSACTION");
    daily.setDalytranAmt(amount);
    daily.setDalytranMerchantId(999999999L);
    daily.setDalytranMerchantName("TEST MERCHANT");
    daily.setDalytranMerchantCity("TEST CITY");
    daily.setDalytranMerchantZip("00000");
    daily.setDalytranCardNum(cardNum);
    daily.setDalytranOrigTs("2022-06-10 19:27:53.000000");
    daily.setDalytranProcTs("");
    return daily;
  }

  /**
   * Locates the single {@code transactionPostingStep} within a completed job execution.
   *
   * @param execution the finished job execution to inspect
   * @return the step execution named {@link #STEP_NAME}
   * @throws AssertionError if the expected step is not present (a structural regression in the job
   *     configuration)
   */
  private static StepExecution postingStep(JobExecution execution) {
    return execution.getStepExecutions().stream()
        .filter(step -> STEP_NAME.equals(step.getStepName()))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "Expected step '"
                        + STEP_NAME
                        + "' was not present in the job execution; steps found: "
                        + execution.getStepExecutions()));
  }
}
