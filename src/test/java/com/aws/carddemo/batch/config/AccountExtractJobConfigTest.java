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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
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
 * Spring Batch integration test for {@link com.aws.carddemo.batch.config.AccountExtractJobConfig}.
 *
 * <p>The production configuration is the Java translation of the legacy account-extract batch job
 * {@code legacy/app/jcl/READACCT.jcl} — a single-step job ({@code STEP05 EXEC PGM=CBACT01C}) that
 * runs the COBOL program {@code legacy/app/cbl/CBACT01C.cbl} ("Read and print account data file").
 * That program opens the {@code ACCTFILE} VSAM KSDS, reads every record in ascending key order,
 * prints each one, closes the file and returns with no {@code RETURN-CODE} (a normal, successful
 * run). The migration models the job as a single-step Spring Batch {@code Job} ({@code
 * accountExtractJob}) whose lone tasklet step ({@code accountExtractStep}) delegates to {@code
 * AccountExtractService.run()}.
 *
 * <p>This test proves the JCL&rarr;Spring Batch tasklet translation end to end: launched against a
 * real, Flyway-migrated PostgreSQL database seeded with the legacy account-master fixture, the job
 * must reach {@link BatchStatus#COMPLETED} — the faithful counterpart of the COBOL {@code GOBACK}
 * success path (Agent Action Plan &sect;0.4.1, &sect;0.6.7). It is the integration complement to
 * the pure-unit {@code AccountExtractServiceTest} (which mocks the repository to assert the COBOL
 * read/print control flow); here nothing is mocked and the full wiring is exercised.
 *
 * <p><strong>Testcontainers wiring.</strong> The class owns a single {@link PostgreSQLContainer}
 * declared {@code static} and managed by the JUnit 5 {@link Testcontainers} extension via {@link
 * Container} (started once before the class, stopped after it). {@link ServiceConnection} binds the
 * running container to Spring's data source, so no {@code @DynamicPropertySource} and no hardcoded
 * JDBC URL or credentials are required. The {@code test} profile ({@code application-test.yml})
 * supplies the rest: Hibernate {@code ddl-auto=validate} (the schema is owned by Flyway {@code
 * V1__schema.sql} + {@code V2__seed_reference_data.sql}, never Hibernate), {@code
 * spring.batch.job.enabled=false} (jobs are launched explicitly here, never auto-run on startup),
 * and {@code spring.batch.jdbc.initialize-schema=always} (Boot creates the {@code BATCH_*} metadata
 * tables this test needs).
 *
 * <p><strong>Why utilities are built by hand (not autowired).</strong> The application context
 * defines many {@code Job} beans (one per migrated JCL job), so a Spring-managed {@link
 * JobLauncherTestUtils} — whose {@code @Autowired setJob(Job)} would try to inject a single,
 * unambiguous {@code Job} — would fail context startup with {@code
 * NoUniqueBeanDefinitionException}. Constructing {@link JobLauncherTestUtils} (and {@link
 * JobRepositoryTestUtils}) manually in {@link #setUp()} sidesteps that: a hand-built instance is
 * never post-processed by Spring, so the ambiguous setter never fires, and the specific job under
 * test is supplied explicitly via the {@link Qualifier @Qualifier}-resolved {@code
 * accountExtractJob} bean. This deliberately does <em>not</em> use {@code @SpringBatchTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AccountExtractJobConfigTest {

  /** Bean name of the single tasklet step assembled by {@code AccountExtractJobConfig}. */
  private static final String ACCOUNT_EXTRACT_STEP = "accountExtractStep";

  /** Number of account-master rows seeded from {@code /fixtures/ascii/acctdata.txt} (LRECL 300). */
  private static final long EXPECTED_ACCOUNT_ROWS = 50L;

  /**
   * Own PostgreSQL 16 container, lifecycle-managed by the {@link Testcontainers} extension and
   * bound to the Spring data source by {@link ServiceConnection}. Mirrors the {@code postgres:16}
   * image used by {@code docker-compose.yml} and the production data store (AAP &sect;0.5.1).
   */
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  /** Boot-provided launcher used to run the job under test. */
  @Autowired private JobLauncher jobLauncher;

  /** Boot-provided batch metadata repository, used to build the test utilities below. */
  @Autowired private JobRepository jobRepository;

  /**
   * The specific job under test, resolved by bean name so the many other {@code Job} beans in the
   * context cause no ambiguity.
   */
  @Autowired
  @Qualifier("accountExtractJob")
  private Job accountExtractJob;

  /** Account-master repository (the migrated {@code ACCTFILE} store) seeded before each run. */
  @Autowired private AccountRepository accountRepository;

  /** Hand-built launch helper (intentionally not a Spring bean — see the class javadoc). */
  private JobLauncherTestUtils jobLauncherTestUtils;

  /** Hand-built helper used to purge batch metadata between runs. */
  private JobRepositoryTestUtils jobRepositoryTestUtils;

  /**
   * Resets batch metadata and reseeds the account master before every test so each method runs
   * against a known, isolated state.
   *
   * <p>The two batch utilities are constructed manually (never autowired) to avoid the multi-{@code
   * Job}-bean ambiguity described in the class javadoc; the job under test is wired in explicitly
   * via {@link JobLauncherTestUtils#setJob(Job)}. The account table is then cleared and reloaded
   * from the legacy fixed-width fixture through {@link
   * FixtureSeeder#seedAccounts(AccountRepository)}, which is accessible because this test lives in
   * the same package.
   */
  @BeforeEach
  void setUp() {
    jobRepositoryTestUtils = new JobRepositoryTestUtils(jobRepository);
    jobRepositoryTestUtils.removeJobExecutions();

    jobLauncherTestUtils = new JobLauncherTestUtils();
    jobLauncherTestUtils.setJobLauncher(jobLauncher);
    jobLauncherTestUtils.setJobRepository(jobRepository);
    jobLauncherTestUtils.setJob(accountExtractJob);

    accountRepository.deleteAll();
    FixtureSeeder.seedAccounts(accountRepository);
  }

  /**
   * Launches {@code accountExtractJob} over a fully seeded account master and asserts the job, its
   * single step, and the exit status all report success — the COBOL {@code CBACT01C} normal-return
   * path translated to a {@code COMPLETED} Spring Batch run.
   *
   * <p>The no-argument {@link JobLauncherTestUtils#launchJob()} auto-adds a unique job parameter,
   * so every invocation creates a fresh {@code JobInstance} and never collides with prior runs.
   * Because the tasklet sets no custom exit status, a successful run yields the default {@code
   * "COMPLETED"} exit code. The presence of the 50 seeded rows is asserted to confirm the job
   * actually had data to read.
   *
   * @throws Exception if the job launch fails (propagated from {@link
   *     JobLauncherTestUtils#launchJob()})
   */
  @Test
  void accountExtractJob_completes_after_reading_account_master() throws Exception {
    JobExecution execution = jobLauncherTestUtils.launchJob();

    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");

    StepExecution accountExtractStepExecution =
        execution.getStepExecutions().stream()
            .filter(stepExecution -> ACCOUNT_EXTRACT_STEP.equals(stepExecution.getStepName()))
            .findFirst()
            .orElseThrow();
    assertThat(accountExtractStepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

    assertThat(accountRepository.count()).isEqualTo(EXPECTED_ACCOUNT_ROWS);
  }

  /**
   * Launches {@code accountExtractJob} against an empty account master and asserts it still reaches
   * {@link BatchStatus#COMPLETED}.
   *
   * <p>This reproduces the COBOL end-of-file semantics of {@code CBACT01C}: an immediately
   * exhausted read loop (FILE STATUS {@code '10'}) is normal loop termination, not an error, so the
   * migrated job must complete successfully even with nothing to read (AAP &sect;0.6.4). The seeded
   * rows from {@link #setUp()} are removed first to exercise the zero-row path.
   *
   * @throws Exception if the job launch fails (propagated from {@link
   *     JobLauncherTestUtils#launchJob()})
   */
  @Test
  void accountExtractJob_completes_with_empty_table() throws Exception {
    accountRepository.deleteAll();

    JobExecution execution = jobLauncherTestUtils.launchJob();

    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(accountRepository.count()).isZero();
  }
}
