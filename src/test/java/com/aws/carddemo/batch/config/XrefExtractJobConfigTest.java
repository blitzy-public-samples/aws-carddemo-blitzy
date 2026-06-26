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

import com.aws.carddemo.repository.CardXrefRepository;
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
 * Spring Batch integration test for {@link XrefExtractJobConfig} &mdash; the Java/Spring
 * configuration that reproduces the legacy card cross-reference extract job {@code
 * legacy/app/jcl/READXREF.jcl} (single step {@code STEP05 EXEC PGM=CBACT03C}). The production job
 * {@code xrefExtractJob} is a one-step tasklet ({@code xrefExtractStep}) that delegates to {@code
 * XrefExtractService#run()}, the migration of COBOL program {@code legacy/app/cbl/CBACT03C.cbl}
 * ("read and print the card-xref data file") per the Agent Action Plan transformation mapping
 * (&sect;0.4.1).
 *
 * <p><strong>What is asserted (and why).</strong> {@code CBACT03C} emits every cross-reference
 * record <em>twice</em> (the well-known double-display parity quirk reproduced verbatim in {@code
 * XrefExtractService}). That duplication is a pure logging side-effect: it changes neither the job
 * outcome nor the database. This test therefore deliberately does <em>not</em> assert on log
 * output; it pins the externally observable batch contract instead &mdash; the job and its single
 * step both reach {@link BatchStatus#COMPLETED}, the job exit code is {@code "COMPLETED"}, and the
 * read-only extract leaves all fifty seeded rows intact (AAP &sect;0.6.7 local-only validation).
 *
 * <p><strong>Context &amp; data wiring (AAP &sect;0.6.2, &sect;0.6.7).</strong> A full {@link
 * SpringBootTest} context runs against a real, throwaway <strong>Testcontainers PostgreSQL
 * 16</strong> instance (the {@code postgres:16-alpine} image used across the suite). {@link
 * ServiceConnection} reads the running container and registers a {@code JdbcConnectionDetails} bean
 * that supersedes the {@code spring.datasource.*} settings of the {@code test} profile, so no
 * second database is created. The {@code test} profile ({@code application-test.yml}) keeps
 * Hibernate {@code ddl-auto=validate} (the schema is owned by the Flyway {@code V1__schema.sql} /
 * {@code V2__seed_reference_data.sql} migrations), disables Spring Batch auto-launch ({@code
 * spring.batch.job.enabled=false} &mdash; jobs are started explicitly here), and creates the Spring
 * Batch {@code BATCH_*} metadata tables.
 *
 * <p>Because {@code @SpringBootTest} runs <em>non-transactionally</em>, the per-test reseed in
 * {@link #setUp()} commits, so the rows are visible to the batch job's own transaction. The fixture
 * {@code /fixtures/ascii/cardxref.txt} is the byte-exact legacy {@code CARDXREF} extract (50
 * records, LRECL 36; copybook {@code CVACT03Y} with its trailing {@code FILLER} omitted), seeded
 * through the shared {@link FixtureSeeder}. The {@code card_xref} table has no foreign keys, so
 * seeding the cross-references alone is sufficient.
 *
 * <p><strong>Spring Batch test harness (no {@code @SpringBatchTest}).</strong> The canonical
 * {@code @SpringBatchTest} annotation is intentionally avoided; the harness is assembled by hand
 * for full control and to bind the one specific job under test. {@link JobLauncherTestUtils} is
 * wired with the Boot-provided {@link JobLauncher}, {@link JobRepository} and the
 * {@code @Qualifier}-selected {@code xrefExtractJob} {@link Job}; {@link
 * JobRepositoryTestUtils#removeJobExecutions()} clears prior batch metadata before each run so
 * every launch starts from a clean slate. {@code launchJob()} supplies unique job parameters on
 * each call, so repeated runs never collide on a completed {@code JobInstance}.
 *
 * @see XrefExtractJobConfig
 * @see FixtureSeeder
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class XrefExtractJobConfigTest {

  /**
   * Expected number of card cross-reference rows seeded from {@code /fixtures/ascii/cardxref.txt}.
   */
  private static final long EXPECTED_XREF_ROWS = 50L;

  /** Bean name of the production single tasklet step defined by {@link XrefExtractJobConfig}. */
  private static final String XREF_EXTRACT_STEP = "xrefExtractStep";

  /**
   * Throwaway PostgreSQL 16 container backing the test. JUnit's Testcontainers extension starts it
   * before the tests and stops it afterwards; {@link ServiceConnection} binds it to the Spring data
   * source, overriding the {@code test} profile's datasource settings so the context talks to this
   * container instead of any externally configured database.
   */
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  /** Boot-provided launcher used to start the job under test. */
  @Autowired private JobLauncher jobLauncher;

  /** Boot-provided Spring Batch metadata repository (drives {@link JobRepositoryTestUtils}). */
  @Autowired private JobRepository jobRepository;

  /** The cross-reference extract job under test, selected by bean name. */
  @Autowired
  @Qualifier("xrefExtractJob")
  private Job xrefExtractJob;

  /**
   * Repository over {@code card_xref}; used to reseed fixtures and to assert the post-run count.
   */
  @Autowired private CardXrefRepository cardXrefRepository;

  /** Hand-assembled Spring Batch test harness bound to {@link #xrefExtractJob}. */
  private JobLauncherTestUtils jobLauncherTestUtils;

  /**
   * Prepares a clean, deterministic starting state before every test: purges Spring Batch metadata,
   * (re)builds the {@link JobLauncherTestUtils} harness around the job under test, and reseeds the
   * {@code card_xref} table from the legacy fixture.
   *
   * <p>The delete-and-seed commits (the enclosing test is non-transactional), so the fifty rows are
   * visible to the batch job's own transaction when it runs.
   */
  @BeforeEach
  void setUp() {
    // Clear any batch executions left by a previous test so each run starts from a clean slate.
    JobRepositoryTestUtils jobRepositoryTestUtils = new JobRepositoryTestUtils(jobRepository);
    jobRepositoryTestUtils.removeJobExecutions();

    // Assemble the harness by hand (no @SpringBatchTest) and bind the specific job under test.
    jobLauncherTestUtils = new JobLauncherTestUtils();
    jobLauncherTestUtils.setJobLauncher(jobLauncher);
    jobLauncherTestUtils.setJobRepository(jobRepository);
    jobLauncherTestUtils.setJob(xrefExtractJob);

    // Reseed the cross-reference store: 50 rows (LRECL 36) from the byte-exact legacy fixture.
    cardXrefRepository.deleteAll();
    FixtureSeeder.seedCardXrefs(cardXrefRepository);
  }

  /**
   * Launching {@code xrefExtractJob} reads the seeded cross-reference store and completes normally.
   *
   * <p>Asserts the externally observable batch contract only: the job and its single {@code
   * xrefExtractStep} both finish {@link BatchStatus#COMPLETED} with exit code {@code "COMPLETED"},
   * and the read-only extract leaves all fifty seeded rows in place. The {@code CBACT03C}
   * double-display is a logging side-effect and is intentionally not asserted.
   *
   * @throws Exception if the job launcher fails to run the job
   */
  @Test
  void xrefExtractJob_completes_after_reading_card_xref() throws Exception {
    JobExecution exec = jobLauncherTestUtils.launchJob();

    assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(exec.getExitStatus().getExitCode()).isEqualTo("COMPLETED");

    StepExecution stepExecution =
        exec.getStepExecutions().stream()
            .filter(step -> XREF_EXTRACT_STEP.equals(step.getStepName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("xrefExtractStep did not execute"));
    assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

    assertThat(cardXrefRepository.count()).isEqualTo(EXPECTED_XREF_ROWS);
  }
}
