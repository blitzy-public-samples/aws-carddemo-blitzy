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
package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.config.JobSchedulingConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * :purpose: Integration test for the batch launch surface, covering three defects that
 *     together made the launchers unusable. Six of the nine ``launch*`` methods
 *     submitted empty ``JobParameters`` although their jobs require an
 *     ``outputFile``/``inputFile`` parameter, so those jobs always failed with "Batch
 *     file path must not be blank" while the caller received a ``STARTING``
 *     ``JobExecution`` and believed the submission had succeeded. Job metadata was held
 *     in a ``ResourcelessJobRepository``, so nothing was ever recorded in ``BATCH_*``.
 *     And ``run.id`` was an identifying parameter, so every submission created a fresh
 *     ``JobInstance``, making restart impossible and duplicate detection silent.
 * :output: Assertions that every parameter-less launch now completes, that the
 *     execution is recorded in the durable repository, that job identity derives from
 *     the business parameters, and that a completed instance is refused on
 *     re-submission.
 * :note: Runs the real jobs against a throwaway ``postgres:18`` container. The shared
 *     business tables have no batch-service migration, so Hibernate materializes them
 *     from the shared entities via ``ddl-auto=create``; the ``BATCH_*`` schema is
 *     provisioned exactly as in production.
 */
@SpringBootTest(classes = BatchServiceApplication.class)
@DisplayName("Batch launch parameters, durable metadata and job identity")
class JobLaunchParameterIT {

    /**
     * Shared PostgreSQL container for the whole test JVM, started from a static
     * initializer and left for the Testcontainers reaper to remove after the fork JVM
     * exits, so context shutdown never stalls against an already-stopped database.
     */
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

    static {
        POSTGRES.start();
    }

    /**
     * :purpose: Point the datasource at the container and let Hibernate create the
     *     shared business tables this module has no migration for.
     * :param registry: the dynamic property registry supplied by the test context.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    /** :purpose: The launch surface under test. */
    @Autowired
    private JobSchedulingConfig jobScheduling;

    /** :purpose: The asynchronous launcher, inspected to prove its executor is bounded. */
    @Autowired
    @Qualifier("asyncJobLauncher")
    private JobLauncher asyncJobLauncher;

    /** :purpose: Durable job repository, queried to prove executions are recorded. */
    @Autowired
    private JobRepository jobRepository;

    /** :purpose: Used to assert directly against the ``BATCH_*`` metadata tables. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** :purpose: Configured batch output root, used to assert the produced file. */
    @Value("${carddemo.batch.output-dir:}")
    private String outputDir;

    /**
     * :purpose: Block until an asynchronously launched execution reaches a terminal
     *     status, so the assertions observe the job's real outcome rather than the
     *     ``STARTING`` status the launcher returns immediately.
     * :param execution: the execution returned by a ``launch*`` method.
     * :returns: the same execution once it is no longer running.
     * :raises InterruptedException: if the wait is interrupted.
     */
    private JobExecution awaitCompletion(JobExecution execution) throws InterruptedException {
        for (int attempt = 0; attempt < 300 && execution.isRunning(); attempt++) {
            Thread.sleep(100);
        }
        return execution;
    }

    /**
     * :purpose: Every launcher that previously submitted empty ``JobParameters`` now
     *     supplies its configured default path and the job completes. This is the
     *     regression guard for the "Batch file path must not be blank" failure.
     * :raises Exception: if a launch or the wait for completion fails.
     */
    @Test
    @DisplayName("parameter-less launches supply a default path and complete")
    void parameterlessLaunchesCompleteWithDefaultPaths() throws Exception {
        assertThat(awaitCompletion(jobScheduling.launchAccountRead()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(awaitCompletion(jobScheduling.launchCardRead()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(awaitCompletion(jobScheduling.launchCardXrefRead()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(awaitCompletion(jobScheduling.launchCustomerRead()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(awaitCompletion(jobScheduling.launchCategoryBalanceReport()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(awaitCompletion(jobScheduling.launchCombineTransactions()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
    }

    /**
     * :purpose: A launch actually writes its output file, proving the defaulted path is
     *     usable and not merely accepted.
     * :raises Exception: if the launch, the wait, or the file check fails.
     */
    @Test
    @DisplayName("a defaulted output path is written, not just accepted")
    void defaultedOutputPathIsWritten() throws Exception {
        String fileName = "launch-default-" + UUID.randomUUID() + ".txt";

        assertThat(awaitCompletion(jobScheduling.launchAccountRead(fileName)).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        Path root = (outputDir == null || outputDir.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir"), "carddemo-batch", "output")
                : Path.of(outputDir);
        assertThat(Files.exists(root.resolve(fileName)))
                .as("the job must write the file at the resolved output path")
                .isTrue();
    }

    /**
     * :purpose: An unusable path is rejected synchronously, so a caller learns the
     *     submission was not accepted instead of receiving a ``STARTING`` execution for
     *     a job that is certain to fail on a worker thread.
     */
    @Test
    @DisplayName("a path escaping the output root is refused at submission time")
    void escapingPathIsRefusedSynchronously() {
        assertThatThrownBy(() -> jobScheduling.launchAccountRead("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes the allowed directory");
    }

    /**
     * :purpose: Executions are persisted in the durable ``BATCH_*`` metadata tables, so
     *     the audit trail the legacy JES environment provided is preserved.
     * :raises Exception: if the launch or the wait fails.
     */
    @Test
    @DisplayName("executions are recorded in the BATCH_* metadata tables")
    void executionsAreRecordedInBatchMetadata() throws Exception {
        String fileName = "metadata-proof-" + UUID.randomUUID() + ".txt";

        awaitCompletion(jobScheduling.launchCardRead(fileName));

        Integer instances = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch_job_instance WHERE job_name = ?",
                Integer.class, "cardReadJob");
        assertThat(instances).isPositive();
        assertThat(jobRepository.getJobInstances("cardReadJob", 0, 100)).isNotEmpty();
    }

    /**
     * :purpose: A read/print submission is REPEATABLE: re-submitting the same report window
     *     runs the job again, exactly as ``CORPT00C`` writes a TDQ 'JOBS' record on every
     *     request and JES runs the stream each time. While the run id was non-identifying the
     *     report window itself became the instance key, so a monthly report could be printed
     *     once per calendar month and never again, and the whole workflow was unusable after
     *     its first run.
     * :raises Exception: if a launch or the wait fails.
     */
    @Test
    @DisplayName("re-submitting the same read/print window runs the job again")
    void repeatedReadSubmissionIsAccepted() throws Exception {
        String fileName = "repeatable-" + UUID.randomUUID() + ".txt";

        JobExecution first = awaitCompletion(jobScheduling.launchCustomerRead(fileName));
        JobExecution second = awaitCompletion(jobScheduling.launchCustomerRead(fileName));

        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId())
                .as("each print/read submission must be its own JobInstance")
                .isNotEqualTo(first.getJobInstance().getInstanceId());
    }

    /**
     * :purpose: The same repeatability holds for the transaction-detail report submitted by
     *     the online ``CORPT00C`` screen: printing the identical window twice is accepted and
     *     produces two distinct executions.
     * :raises Exception: if a launch or the wait fails.
     */
    @Test
    @DisplayName("the transaction-detail report can be printed twice for one window")
    void repeatedReportSubmissionIsAccepted() throws Exception {
        String fileName = "report-" + UUID.randomUUID() + ".txt";

        JobExecution first = awaitCompletion(
                jobScheduling.launchTransactionDetailReport("2026-08-01", "2026-08-31", fileName));
        JobExecution second = awaitCompletion(
                jobScheduling.launchTransactionDetailReport("2026-08-01", "2026-08-31", fileName));

        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getJobInstance().getInstanceId())
                .isNotEqualTo(first.getJobInstance().getInstanceId());
    }

    /**
     * :purpose: A repeatable read/print run records its run id as IDENTIFYING, which is what
     *     makes every submission its own instance; a state-changing run must NOT, so its
     *     identity still derives from the business parameters alone and a duplicate posting or
     *     interest run is still refused.
     * :raises Exception: if a launch or the wait fails.
     */
    @Test
    @DisplayName("the run id is identifying for a read/print run and not for a state-changing run")
    void runIdIsIdentifyingOnlyForRepeatableRuns() throws Exception {
        JobExecution report = awaitCompletion(jobScheduling.launchCategoryBalanceReport(
                "identifying-" + UUID.randomUUID() + ".txt"));
        JobExecution interest = jobScheduling.launchInterestCalculation("2022071800");

        assertThat(identifyingFlagOfRunId(report.getId()))
                .as("a print/read submission must be its own instance")
                .isEqualTo("Y");
        assertThat(identifyingFlagOfRunId(interest.getId()))
                .as("a state-changing submission must keep instance-level duplicate protection")
                .isEqualTo("N");
        awaitCompletion(interest);
    }

    /**
     * :purpose: Read the ``IDENTIFYING`` flag Spring Batch recorded for an execution's
     *     ``run.id`` parameter.
     * :param jobExecutionId: the execution whose parameter is inspected.
     * :returns: ``"Y"`` when the parameter contributes to job identity, ``"N"`` otherwise.
     */
    private String identifyingFlagOfRunId(Long jobExecutionId) {
        return jdbcTemplate.queryForObject(
                "SELECT identifying FROM batch_job_execution_params "
                        + "WHERE job_execution_id = ? AND parameter_name = 'run.id'",
                String.class, jobExecutionId);
    }

    /**
     * :purpose: The asynchronous launcher's executor is bounded.
     *     ``SimpleAsyncTaskExecutor`` pools no threads, so an unbounded executor starts
     *     a new thread for every submission and a burst of launches can exhaust memory
     *     and saturate the JDBC pool. The legacy environment bounded this through JES
     *     initiator classes.
     */
    @Test
    @DisplayName("the async launcher's executor concurrency is bounded")
    void asyncLauncherConcurrencyIsBounded() {
        Object taskExecutor = ReflectionTestUtils.getField(asyncJobLauncher, "taskExecutor");

        assertThat(taskExecutor).isInstanceOf(SimpleAsyncTaskExecutor.class);
        assertThat(((SimpleAsyncTaskExecutor) taskExecutor).getConcurrencyLimit())
                .as("an unbounded executor would report %d",
                        SimpleAsyncTaskExecutor.UNBOUNDED_CONCURRENCY)
                .isPositive()
                .isNotEqualTo(SimpleAsyncTaskExecutor.UNBOUNDED_CONCURRENCY);
    }

    /**
     * :purpose: Distinct business parameters still start distinct instances, so the
     *     duplicate guard never blocks a genuinely different run.
     * :raises Exception: if a launch or the wait fails.
     */
    @Test
    @DisplayName("distinct business parameters start distinct job instances")
    void distinctParametersStartDistinctInstances() throws Exception {
        String first = "distinct-a-" + UUID.randomUUID() + ".txt";
        String second = "distinct-b-" + UUID.randomUUID() + ".txt";

        awaitCompletion(jobScheduling.launchCardXrefRead(first));
        awaitCompletion(jobScheduling.launchCardXrefRead(second));

        Integer instances = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch_job_instance WHERE job_name = ?",
                Integer.class, "cardXrefReadJob");
        assertThat(instances).isGreaterThanOrEqualTo(2);
    }
}
