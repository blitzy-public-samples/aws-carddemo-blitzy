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

import com.aws.carddemo.repository.CustomerRepository;
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
 * Spring Batch <strong>integration test</strong> for {@link CustomerExtractJobConfig}, the Spring
 * Batch wiring that reproduces the legacy customer-master extract job {@code
 * legacy/app/jcl/READCUST.jcl} ({@code STEP05 EXEC PGM=CBCUS01C}). The configuration exposes a
 * single-step {@link Job} named {@code customerExtractJob} whose lone tasklet step {@code
 * customerExtractStep} delegates to {@code CustomerExtractService.run()} &mdash; the Java
 * translation of batch COBOL program {@code CBCUS01C} ("Read and print customer data file").
 *
 * <p><strong>What this test proves (and what it deliberately does not).</strong> {@code CBCUS01C}
 * is a self-contained read/print loop: it opens the customer master, scans it forward in ascending
 * {@code FD-CUST-ID} order, and &mdash; the parity quirk of the program &mdash; emits two {@code
 * DISPLAY CUSTOMER-RECORD} log lines per record (one inside {@code 1000-CUSTFILE-GET-NEXT} on
 * {@code FILE STATUS '00'}, a second back in the main {@code PERFORM UNTIL END-OF-FILE} loop), then
 * closes the file and returns via {@code GOBACK} with no {@code RETURN-CODE}. Its only observable
 * side effect is logging, so the meaningful integration-level guarantee is simply that the job
 * <em>runs to {@link BatchStatus#COMPLETED} end-to-end</em> against a real, seeded database: the
 * Spring context boots, the Boot-provided {@link JobRepository}/{@code PlatformTransactionManager}
 * wire the no-{@code @EnableBatchProcessing} configuration, the tasklet invokes the service once,
 * and the service's sequential scan over all 50 seeded customers terminates normally on
 * end-of-file. The byte-exact <em>double-display</em> behaviour itself is asserted by the service's
 * own focused unit test, not here; this test intentionally makes <em>no</em> assertion on log
 * output (the double emission changes neither row counts nor persistence &mdash; {@code CBCUS01C}
 * never writes).
 *
 * <p><strong>Context &amp; Testcontainers wiring (Agent Action Plan &sect;0.6.7).</strong> The test
 * is a full-context {@link SpringBootTest} under the {@link ActiveProfiles &#64;ActiveProfiles}
 * {@code "test"} profile (Flyway {@code V1__schema.sql} + {@code V2__seed_reference_data.sql} are
 * authoritative; Hibernate {@code ddl-auto=validate}; auto-launch of batch jobs is disabled so this
 * test launches the job explicitly). A single class-scoped PostgreSQL 16 container is managed by
 * the JUnit 5 {@link Testcontainers} extension ({@link Container &#64;Container}); {@link
 * ServiceConnection &#64;ServiceConnection} registers a {@code JdbcConnectionDetails} bean that
 * <em>overrides</em> the profile's {@code spring.datasource.*} settings, so the application binds
 * to this throwaway container rather than any external database &mdash; keeping the run hermetic
 * and reproducible with no mainframe in the loop.
 *
 * <p><strong>Batch-test harness convention (no {@code @SpringBatchTest}).</strong> Following the
 * project-wide convention this test does not use {@code @SpringBatchTest}; instead it constructs
 * {@link JobLauncherTestUtils} and {@link JobRepositoryTestUtils} by hand in {@link #setUp()},
 * binding the autowired {@link JobLauncher}, {@link JobRepository}, and the {@code
 * customerExtractJob} {@link Job} (resolved by {@link Qualifier &#64;Qualifier} bean name). Each
 * test starts from a clean slate: prior Spring Batch executions are removed, the {@code customer}
 * table is truncated, and exactly the 50 fixture rows from {@code /fixtures/ascii/custdata.txt} are
 * re-seeded via {@link FixtureSeeder#seedCustomers}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CustomerExtractJobConfigTest {

  /**
   * Number of customer-master fixture rows in {@code /fixtures/ascii/custdata.txt} (copybook {@code
   * CVCUS01Y}). {@link FixtureSeeder#seedCustomers(CustomerRepository)} loads exactly this many
   * rows, and {@code CBCUS01C} is a read-only extract, so the post-run {@code customer} row count
   * is unchanged at this value.
   */
  private static final long EXPECTED_CUSTOMER_ROWS = 50L;

  /**
   * Bean name of the single tasklet step under test, as declared by {@link
   * CustomerExtractJobConfig}.
   */
  private static final String EXTRACT_STEP_NAME = "customerExtractStep";

  /**
   * Class-scoped, JUnit-managed PostgreSQL 16 container. {@link Container &#64;Container} on a
   * {@code static} field starts it once for the class and stops it afterwards; {@link
   * ServiceConnection &#64;ServiceConnection} binds it to the Spring data source, superseding the
   * {@code application-test.yml} datasource settings so no second database is used.
   */
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  /** Boot-provided launcher used to run {@code customerExtractJob} from the test harness. */
  @Autowired private JobLauncher jobLauncher;

  /** Boot-provided Spring Batch metadata repository (also reset between tests). */
  @Autowired private JobRepository jobRepository;

  /** The job under test, injected by bean name to disambiguate it from sibling extract jobs. */
  @Autowired
  @Qualifier("customerExtractJob")
  private Job customerExtractJob;

  /**
   * Repository backing the migrated {@code CUSTFILE} KSDS; used to seed fixtures and assert counts.
   */
  @Autowired private CustomerRepository customerRepository;

  /**
   * Hand-built launcher harness (no {@code @SpringBatchTest}); recreated per test in {@link
   * #setUp()}.
   */
  private JobLauncherTestUtils jobLauncherTestUtils;

  /** Hand-built metadata harness used to purge prior executions before each test. */
  private JobRepositoryTestUtils jobRepositoryTestUtils;

  /**
   * Resets the batch metadata and the customer master before each test so every run is independent
   * and deterministic.
   *
   * <p>Prior Spring Batch executions are removed first, then a fresh {@link JobLauncherTestUtils}
   * is wired to the autowired {@link JobLauncher}, {@link JobRepository}, and {@code
   * customerExtractJob} {@link Job}. Finally the {@code customer} table is truncated and re-seeded
   * with exactly the 50 fixture rows, mirroring the populated VSAM customer master that {@code
   * CBCUS01C} would scan on the mainframe.
   */
  @BeforeEach
  void setUp() {
    jobRepositoryTestUtils = new JobRepositoryTestUtils(jobRepository);
    jobRepositoryTestUtils.removeJobExecutions();

    jobLauncherTestUtils = new JobLauncherTestUtils();
    jobLauncherTestUtils.setJobLauncher(jobLauncher);
    jobLauncherTestUtils.setJobRepository(jobRepository);
    jobLauncherTestUtils.setJob(customerExtractJob);

    customerRepository.deleteAll();
    FixtureSeeder.seedCustomers(customerRepository);
  }

  /**
   * Launches {@code customerExtractJob} end-to-end and asserts it completes after reading the
   * customer master.
   *
   * <p>The job is launched with unique parameters (via {@link JobLauncherTestUtils#launchJob()}).
   * The assertions verify the externally observable batch outcome that mirrors {@code CBCUS01C}'s
   * {@code GOBACK} (clean termination): the {@link JobExecution} reaches {@link
   * BatchStatus#COMPLETED} with exit code {@code "COMPLETED"}, and the lone {@code
   * customerExtractStep} also completes. The final row-count sanity check confirms the extract is
   * read-only &mdash; the 50 seeded customers are all still present, the double {@code DISPLAY}
   * having affected logging only.
   *
   * @throws Exception if the job launcher fails to run the job (declared by {@link
   *     JobLauncherTestUtils#launchJob()})
   */
  @Test
  void customerExtractJob_completes_after_reading_customer_master() throws Exception {
    JobExecution execution = jobLauncherTestUtils.launchJob();

    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");

    StepExecution extractStep =
        execution.getStepExecutions().stream()
            .filter(step -> EXTRACT_STEP_NAME.equals(step.getStepName()))
            .findFirst()
            .orElseThrow();
    assertThat(extractStep.getStatus()).isEqualTo(BatchStatus.COMPLETED);

    assertThat(customerRepository.count()).isEqualTo(EXPECTED_CUSTOMER_ROWS);
  }
}
