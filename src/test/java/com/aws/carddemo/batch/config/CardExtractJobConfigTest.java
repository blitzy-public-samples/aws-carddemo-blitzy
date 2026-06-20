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

import com.aws.carddemo.repository.CardRepository;
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
 * Spring Batch integration test for {@link CardExtractJobConfig} — the Spring Batch translation of
 * the legacy card-master extract job {@code legacy/app/jcl/READCARD.jcl} (source-branch {@code
 * app/jcl/READCARD.jcl}), whose single step {@code STEP05 EXEC PGM=CBACT02C} runs the batch COBOL
 * program {@code CBACT02C} ("Read and print card data file", behavioral spec {@code
 * legacy/app/cbl/CBACT02C.cbl}).
 *
 * <p>This test launches the real {@code cardExtractJob} against a live database and proves the
 * batch shape and parity guarantees the configuration promises (Agent Action Plan &sect;0.4.1, "JCL
 * &rarr; Spring Batch jobs"; &sect;0.6.7 local-only validation):
 *
 * <ul>
 *   <li><b>Single-tasklet completion.</b> The job's one step {@code cardExtractStep} delegates to
 *       {@link com.aws.carddemo.service.batch.CardExtractService#run()} and must reach {@link
 *       BatchStatus#COMPLETED}, mirroring the single {@code EXEC PGM=CBACT02C} step that runs to
 *       {@code GOBACK} and ends (CBACT02C {@code PROCEDURE DIVISION}, L70-L87).
 *   <li><b>Read-only parity.</b> {@code CBACT02C} performs a pure sequential read-and-print pass
 *       over the card master and never mutates it, so the row count is unchanged by a successful
 *       run — the suite seeds 50 cards from the legacy ASCII fixture and asserts exactly 50 remain.
 * </ul>
 *
 * <p><b>Wiring rationale.</b> A full {@link SpringBootTest} context is used (not a slice) so the
 * Boot-supplied {@link JobLauncher} / {@link JobRepository} and the production {@link
 * CardExtractJobConfig} beans are exercised exactly as in production — there is no
 * {@code @EnableBatchProcessing} anywhere (AAP &sect;0.7.3, zero-warning gate). Because the full
 * context defines eleven {@code Job} beans, {@code @SpringBatchTest} (which auto-wires a single
 * {@code JobLauncherTestUtils}) would be ambiguous; instead the utility is built manually in {@link
 * #setUp()} and pinned to the {@code @Qualifier("cardExtractJob")} job, eliminating that ambiguity.
 *
 * <p><b>Database.</b> A dedicated Testcontainers PostgreSQL 16 instance is started by the JUnit
 * {@code @Testcontainers} lifecycle and bound to the Spring data source by {@link
 * ServiceConnection} (no {@code @DynamicPropertySource}); the connection-details bean it registers
 * supersedes the {@code application-test.yml} data-source settings so exactly one container backs
 * the context. Flyway applies the VSAM-faithful schema and reference seed on startup, and Hibernate
 * runs with {@code ddl-auto=validate}; the suite then seeds the {@code card} table from the legacy
 * fixture via {@link FixtureSeeder} (same package).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CardExtractJobConfigTest {

  /**
   * Dedicated PostgreSQL 16 container for this test class, managed by the JUnit
   * {@code @Testcontainers} lifecycle (started before, stopped after the class). {@link
   * ServiceConnection} reads the running container and registers a {@code JdbcConnectionDetails}
   * bean that overrides {@code spring.datasource.*}, so the Spring context connects to this
   * container and no second container is created.
   */
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  /** Boot-provided Spring Batch launcher used to run {@code cardExtractJob}. */
  @Autowired private JobLauncher jobLauncher;

  /** Boot-provided Spring Batch metadata repository (no {@code @EnableBatchProcessing}). */
  @Autowired private JobRepository jobRepository;

  /**
   * The production card-master extract job under test, selected by bean name among the eleven
   * {@code Job} beans in the context.
   */
  @Autowired
  @Qualifier("cardExtractJob")
  private Job cardExtractJob;

  /**
   * Repository over the {@code card} table (the JPA replacement for the VSAM {@code CARDDATA}
   * KSDS), used to seed the fixture and assert the read-only row count.
   */
  @Autowired private CardRepository cardRepository;

  /**
   * Manually constructed launch helper pinned to {@link #cardExtractJob}; rebuilt before each test
   * to avoid the multi-{@code Job} ambiguity of {@code @SpringBatchTest}.
   */
  private JobLauncherTestUtils jobLauncherTestUtils;

  /** Helper used to clear Spring Batch execution history between tests for a deterministic run. */
  private JobRepositoryTestUtils jobRepositoryTestUtils;

  /**
   * Prepares a deterministic starting state before each test:
   *
   * <ol>
   *   <li>clears all prior Spring Batch job executions so launches are independent;
   *   <li>builds a fresh {@link JobLauncherTestUtils} wired to the Boot launcher/repository and
   *       pinned to {@code cardExtractJob} (manual construction avoids the eleven-{@code Job}
   *       ambiguity that {@code @SpringBatchTest} auto-wiring would hit);
   *   <li>empties the {@code card} table and reseeds the 50-row legacy fixture via {@link
   *       FixtureSeeder#seedCards(CardRepository)}.
   * </ol>
   *
   * <p>The full {@link SpringBootTest} context is not transactional, so these writes are committed
   * and visible to the batch job's own transaction when it launches.
   */
  @BeforeEach
  void setUp() {
    jobRepositoryTestUtils = new JobRepositoryTestUtils(jobRepository);
    jobRepositoryTestUtils.removeJobExecutions();

    jobLauncherTestUtils = new JobLauncherTestUtils();
    jobLauncherTestUtils.setJobLauncher(jobLauncher);
    jobLauncherTestUtils.setJobRepository(jobRepository);
    jobLauncherTestUtils.setJob(cardExtractJob);

    cardRepository.deleteAll();
    FixtureSeeder.seedCards(cardRepository);
  }

  /**
   * Launches {@code cardExtractJob} over the seeded card master and asserts it completes cleanly.
   * The job and its single {@code cardExtractStep} must both reach {@link BatchStatus#COMPLETED}
   * with a {@code "COMPLETED"} exit code — the batch analog of the COBOL run reaching {@code
   * GOBACK}. Because {@code CBACT02C} only reads and prints (it never writes the card master), the
   * row count is unchanged, so all 50 seeded cards remain.
   *
   * @throws Exception if the job launcher fails to run the job
   */
  @Test
  void cardExtractJob_completes_after_reading_card_master() throws Exception {
    JobExecution exec = jobLauncherTestUtils.launchJob();

    assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(exec.getExitStatus().getExitCode()).isEqualTo("COMPLETED");

    StepExecution cardExtractStep =
        exec.getStepExecutions().stream()
            .filter(step -> "cardExtractStep".equals(step.getStepName()))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("cardExtractStep was not executed by cardExtractJob"));
    assertThat(cardExtractStep.getStatus()).isEqualTo(BatchStatus.COMPLETED);

    // CBACT02C is read-only (PROCEDURE DIVISION L70-L87): the seeded master is unchanged.
    assertThat(cardRepository.count()).isEqualTo(50L);
  }

  /**
   * Launches {@code cardExtractJob} against an empty card master and asserts it still completes.
   * This mirrors {@code CBACT02C} immediately reaching end-of-file (FILE STATUS {@code '10'}) on
   * the first read: the loop performs no emit, the file is closed, and the program ends normally —
   * an empty input is valid control flow, never an error.
   *
   * @throws Exception if the job launcher fails to run the job
   */
  @Test
  void cardExtractJob_completes_when_card_master_is_empty() throws Exception {
    cardRepository.deleteAll();

    JobExecution exec = jobLauncherTestUtils.launchJob();

    assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(exec.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
    assertThat(cardRepository.count()).isZero();
  }
}
