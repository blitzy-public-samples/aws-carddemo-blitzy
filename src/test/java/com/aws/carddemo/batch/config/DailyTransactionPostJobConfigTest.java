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

import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.DailyTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
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
 * Spring Batch integration test for {@link DailyTransactionPostJobConfig} — the migration of the
 * legacy COBOL batch program {@code CBTRN01C} (source {@code legacy/app/cbl/CBTRN01C.cbl}) to the
 * single-step Spring Batch job {@code dailyTransactionPostJob} (step {@code
 * dailyTransactionPostStep}) that delegates to {@code DailyTransactionPostService.run()}.
 *
 * <p><strong>What parity guarantee is under test (AAP &sect;0.6.6).</strong> Despite its "post"
 * name, {@code CBTRN01C} is a <em>validation-only</em> pass: it reads every record of the
 * sequential daily-transaction file and, for each one, resolves the card through the card
 * cross-reference and verifies the referenced account exists. A card or account that cannot be
 * resolved is <em>skipped</em> (the legacy VSAM {@code INVALID KEY} / FILE STATUS {@code '23'}
 * path) and is <em>not</em> an error — the program merely emits a diagnostic and moves on. The
 * program performs no posting, no writes/rewrites, and — critically — sets <em>no</em> {@code
 * RETURN-CODE}: its {@code MAIN-PARA} ends with a bare {@code GOBACK}, leaving the return code at
 * its default {@code 0}. (The {@code MOVE 4 TO WS-XREF-READ-STATUS} / {@code WS-ACCT-READ-STATUS}
 * statements set <em>internal working-storage flags</em>, never the program return code.)
 *
 * <p>Faithfully translated, the tasklet in {@link DailyTransactionPostJobConfig} simply invokes the
 * service once and reports {@code FINISHED} without applying any custom exit-status logic, so a
 * clean run leaves the step at the framework default {@link ExitStatus#COMPLETED} (exit code {@code
 * "COMPLETED"}). This test therefore asserts that the job and its step both reach {@link
 * BatchStatus#COMPLETED} and that the step's exit code is exactly {@code
 * ExitStatus.COMPLETED.getExitCode()} — explicitly <em>not</em> the {@code "4"} custom exit status
 * (nor any numeric custom code). That distinguishes this validation-only job from the separate
 * chunk-oriented posting archetype {@code TransactionPostingJobConfig} (migrated from {@code
 * CBTRN02C}), whose dedicated test covers the {@code "4"} reject-driven exit status.
 *
 * <p><strong>Local-only, VSAM-faithful wiring (AAP &sect;0.6.7).</strong> All verification runs
 * locally against a real <b>Testcontainers PostgreSQL 16</b> instance — never an embedded engine —
 * so the schema is the exact VSAM-faithful Flyway schema ({@code numeric(p,s)} decimal scale,
 * {@code char(n)} fixed-width keys, the three alternate indexes) and the daily-transaction lookups
 * behave identically to the mainframe. No running COBOL/mainframe environment is required. The
 * {@code postgres:16-alpine} container is declared with JUnit's
 * {@code @Testcontainers}/{@code @Container} lifecycle and bound to the Spring data source by
 * {@link ServiceConnection}, which registers a {@code JdbcConnectionDetails} bean that supersedes
 * the {@code spring.datasource.*} properties of the {@code test} profile — the same proven pattern
 * used by the repository-slice integration tests.
 *
 * <p><strong>Why a manually wired {@link JobLauncherTestUtils} (no
 * {@code @SpringBatchTest}).</strong> The project deliberately omits {@code @EnableBatchProcessing}
 * so that Spring Boot's {@code DefaultBatchConfiguration} auto-configuration supplies the {@link
 * JobRepository} and {@link JobLauncher}. Rather than relying on {@code @SpringBatchTest} (which
 * expects a single primary {@code Job} bean and autowires its own helpers), this test constructs
 * {@link JobLauncherTestUtils} by hand in {@link #setUp()} and injects the autowired launcher,
 * repository and the {@code @Qualifier}-selected {@code dailyTransactionPostJob} bean — keeping the
 * wiring explicit and unambiguous even though several {@code Job} beans exist in the context. A
 * {@link JobRepositoryTestUtils} clears prior batch execution metadata before each test so every
 * run is independent and deterministic ({@code junit-platform.properties} keeps the suite strictly
 * sequential).
 *
 * <p>The class and its {@code @Test} methods are package-private by project convention, and {@link
 * DisplayNameGeneration} renders the underscore-separated method names as readable phrases in the
 * test report.
 *
 * @see DailyTransactionPostJobConfig
 * @see com.aws.carddemo.service.batch.DailyTransactionPostService
 * @see FixtureSeeder
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DailyTransactionPostJobConfigTest {

  /**
   * Number of daily-transaction rows in the {@code /fixtures/ascii/dailytran.txt} fixture (copybook
   * {@code CVTRA06Y}). The validation-only pass never deletes or moves its input, so the row count
   * is invariant across a run and is asserted as a sanity check.
   */
  private static final long EXPECTED_DAILY_TRANSACTION_ROWS = 300L;

  /** Bean name of the single step of {@code dailyTransactionPostJob}, located within a run. */
  private static final String STEP_NAME = "dailyTransactionPostStep";

  /**
   * Custom numeric exit code the validation-only job must <em>never</em> produce. The reject-driven
   * {@code "4"} exit status belongs to the separate posting archetype ({@code CBTRN02C} / {@code
   * TransactionPostingJobConfig}); {@code CBTRN01C} sets no {@code RETURN-CODE} at all.
   */
  private static final String FORBIDDEN_CUSTOM_EXIT_CODE = "4";

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
  @Qualifier("dailyTransactionPostJob")
  private Job dailyTransactionPostJob;

  /** Repository for the sequential daily-transaction store seeded from the fixture. */
  @Autowired private DailyTransactionRepository dailyTransactionRepository;

  /** Repository for the card cross-reference store the validation pass reads by card number. */
  @Autowired private CardXrefRepository cardXrefRepository;

  /** Repository for the account master store the validation pass reads by account id. */
  @Autowired private AccountRepository accountRepository;

  /** Manually wired Spring Batch launcher helper (constructed per test in {@link #setUp()}). */
  private JobLauncherTestUtils jobLauncherTestUtils;

  /** Helper that clears prior batch execution metadata so each test runs in isolation. */
  private JobRepositoryTestUtils jobRepositoryTestUtils;

  /**
   * Prepares a clean, deterministic state before every test: clears any prior Spring Batch
   * execution metadata, wires a fresh {@link JobLauncherTestUtils} to the {@code
   * dailyTransactionPostJob}, then resets and re-seeds the three stores the validation pass reads.
   *
   * <p>Seeding loads the full master/transaction graph relevant to {@code CBTRN01C}: the account
   * master, the card cross-reference, and the 300-row daily-transaction file. Because the legacy
   * VSAM design enforced no referential integrity, the PostgreSQL schema declares no foreign keys,
   * so the delete/seed order is unconstrained. Some daily-transaction card numbers may not resolve
   * against the seeded cross-reference — those records are skipped by the validation pass (not
   * errors), exercising the {@code INVALID KEY} path within an otherwise successful run.
   */
  @BeforeEach
  void setUp() {
    jobRepositoryTestUtils = new JobRepositoryTestUtils(jobRepository);
    jobRepositoryTestUtils.removeJobExecutions();

    jobLauncherTestUtils = new JobLauncherTestUtils();
    jobLauncherTestUtils.setJobLauncher(jobLauncher);
    jobLauncherTestUtils.setJobRepository(jobRepository);
    jobLauncherTestUtils.setJob(dailyTransactionPostJob);

    dailyTransactionRepository.deleteAll();
    cardXrefRepository.deleteAll();
    accountRepository.deleteAll();

    FixtureSeeder.seedAccounts(accountRepository);
    FixtureSeeder.seedCardXrefs(cardXrefRepository);
    FixtureSeeder.seedDailyTransactions(dailyTransactionRepository);
  }

  /**
   * Verifies the happy path: with the account, cross-reference and daily-transaction stores seeded,
   * the validation pass runs to completion and produces the framework-default exit status — never a
   * custom {@code "4"} — faithfully translating the COBOL {@code GOBACK} that leaves {@code
   * RETURN-CODE = 0}.
   *
   * <p>Assertions: the job reaches {@link BatchStatus#COMPLETED}; the single {@code
   * dailyTransactionPostStep} also reaches {@link BatchStatus#COMPLETED} with an exit code equal to
   * {@link ExitStatus#COMPLETED}'s ({@code "COMPLETED"}) and not the forbidden custom {@code "4"};
   * and, as a sanity check that the validation-only pass mutates nothing, the daily-transaction row
   * count is unchanged at {@value #EXPECTED_DAILY_TRANSACTION_ROWS}.
   *
   * @throws Exception if launching the job fails (propagated to fail the test)
   */
  @Test
  void dailyTransactionPostJob_validates_all_and_completes_without_custom_exit_status()
      throws Exception {
    JobExecution execution = jobLauncherTestUtils.launchJob();

    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

    StepExecution stepExecution = stepExecution(execution);
    assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(stepExecution.getExitStatus().getExitCode())
        .isEqualTo(ExitStatus.COMPLETED.getExitCode())
        .isNotEqualTo(FORBIDDEN_CUSTOM_EXIT_CODE);

    assertThat(dailyTransactionRepository.count()).isEqualTo(EXPECTED_DAILY_TRANSACTION_ROWS);
  }

  /**
   * Verifies that a complete absence of matching master data is still a successful run: when only
   * the daily-transaction file is seeded (no cross-reference rows and no accounts), every record
   * fails its card lookup and is skipped via the {@code INVALID KEY} path. Skips are not errors, so
   * the job and step still reach {@link BatchStatus#COMPLETED} with the default exit status,
   * exactly as the legacy program completes after logging the "could not be verified" diagnostics.
   *
   * <p>This test deliberately re-establishes its own minimal state (clearing all three stores and
   * re-seeding only the daily transactions) so its intent is self-contained and independent of the
   * shared {@link #setUp()} seeding.
   *
   * @throws Exception if launching the job fails (propagated to fail the test)
   */
  @Test
  void dailyTransactionPostJob_completes_when_no_matching_master_data() throws Exception {
    dailyTransactionRepository.deleteAll();
    cardXrefRepository.deleteAll();
    accountRepository.deleteAll();
    FixtureSeeder.seedDailyTransactions(dailyTransactionRepository);

    JobExecution execution = jobLauncherTestUtils.launchJob();

    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

    StepExecution stepExecution = stepExecution(execution);
    assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(stepExecution.getExitStatus().getExitCode())
        .isEqualTo(ExitStatus.COMPLETED.getExitCode())
        .isNotEqualTo(FORBIDDEN_CUSTOM_EXIT_CODE);

    assertThat(dailyTransactionRepository.count()).isEqualTo(EXPECTED_DAILY_TRANSACTION_ROWS);
  }

  /**
   * Locates the single {@code dailyTransactionPostStep} within a completed job execution.
   *
   * @param execution the finished job execution to inspect
   * @return the step execution named {@link #STEP_NAME}
   * @throws AssertionError if the expected step is not present (a structural regression in the job
   *     configuration)
   */
  private static StepExecution stepExecution(JobExecution execution) {
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
