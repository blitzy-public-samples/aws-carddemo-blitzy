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
package com.carddemo.reporting;

import com.carddemo.common.exception.CardDemoException;
import com.carddemo.reporting.config.JobSchedulingConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobRestartException;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * :purpose: Pure JUnit 5 + Mockito unit test for
 *  {@link com.carddemo.reporting.config.JobSchedulingConfig}, the component that
 *  re-platforms ``CORPT00C``'s ``WIRTE-JOBSUB-TDQ`` submission
 *  (``EXEC CICS WRITEQ TD QUEUE('JOBS')``) into a Spring Batch
 *  {@link org.springframework.batch.core.launch.JobLauncher} invocation of the
 *  ``statementGenerationJob``.
 * :output: Verifies that a report request builds {@link JobParameters} carrying
 *  ``reportType``/``startDate``/``endDate`` plus a unique ``run.id``, launches the
 *  injected ``statementGenerationJob`` and returns a completed
 *  {@link CompletableFuture}, and that each of the four Spring Batch launch
 *  exceptions is translated into a {@link CardDemoException} bearing the frozen
 *  operator message. The system under test is exercised as a plain object with no
 *  Spring AOP proxy, so its ``@Async`` boundary is inert: the method body runs
 *  synchronously on the calling thread, the returned future is already complete,
 *  and any {@link CardDemoException} propagates directly to the caller.
 */
class JobSchedulingConfigTest {

    /** :purpose: Mocked auto-configured Spring Batch launcher. */
    private JobLauncher jobLauncher;

    /** :purpose: Mocked ``statementGenerationJob`` batch job bean. */
    private Job statementGenerationJob;

    /** :purpose: System under test constructed over the two mocked collaborators. */
    private JobSchedulingConfig config;

    /**
     * :purpose: Build fresh mocks and the system under test before each scenario,
     *  binding the constructor argument order to the production signature
     *  ``JobSchedulingConfig(JobLauncher, Job)``.
     */
    @BeforeEach
    void setUp() {
        jobLauncher = mock(JobLauncher.class);
        statementGenerationJob = mock(Job.class);
        config = new JobSchedulingConfig(jobLauncher, statementGenerationJob);
    }

    /**
     * :purpose: A successful submission launches the ``statementGenerationJob`` and
     *  returns a future already completed with the launcher's job execution.
     */
    @Test
    void launchStatementGenerationReturnsCompletedFutureWithJobExecution() throws Exception {
        JobExecution jobExecution = mock(JobExecution.class);
        when(jobLauncher.run(eq(statementGenerationJob), any(JobParameters.class)))
                .thenReturn(jobExecution);

        CompletableFuture<JobExecution> future =
                config.launchStatementGeneration("Monthly", "2024-01-01", "2024-01-31");

        assertThat(future).isCompletedWithValue(jobExecution);
    }

    /**
     * :purpose: The submission passes the report type and date range to the launcher
     *  as ``reportType``/``startDate``/``endDate`` job parameters alongside a
     *  non-null uniqueness ``run.id``.
     */
    @Test
    void launchStatementGenerationBuildsJobParametersWithReportArgumentsAndRunId() throws Exception {
        JobExecution jobExecution = mock(JobExecution.class);
        when(jobLauncher.run(eq(statementGenerationJob), any(JobParameters.class)))
                .thenReturn(jobExecution);

        config.launchStatementGeneration("Monthly", "2024-01-01", "2024-01-31");

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(statementGenerationJob), captor.capture());
        JobParameters params = captor.getValue();
        assertThat(params.getString("reportType")).isEqualTo("Monthly");
        assertThat(params.getString("startDate")).isEqualTo("2024-01-01");
        assertThat(params.getString("endDate")).isEqualTo("2024-01-31");
        assertThat(params.getString("run.id")).isNotNull();
    }

    /**
     * :purpose: Supply one instance of each Spring Batch launch exception declared by
     *  {@link JobLauncher#run} so the failure-translation path is exercised for all
     *  four.
     * :returns: a stream of the four launch exceptions.
     */
    static Stream<Throwable> batchLaunchExceptions() {
        return Stream.of(
                new JobExecutionAlreadyRunningException("x"),
                new JobRestartException("x"),
                new JobInstanceAlreadyCompleteException("x"),
                new InvalidJobParametersException("x"));
    }

    /**
     * :purpose: Any of the four Spring Batch launch exceptions is translated into a
     *  {@link CardDemoException} carrying the frozen operator message and preserving
     *  the original launch exception as its cause.
     * :param thrown: the launch exception raised by the stubbed launcher.
     */
    @ParameterizedTest
    @MethodSource("batchLaunchExceptions")
    void launchStatementGenerationTranslatesLaunchExceptionsToCardDemoException(Throwable thrown)
            throws Exception {
        when(jobLauncher.run(any(), any())).thenThrow(thrown);

        assertThatThrownBy(() -> config.launchStatementGeneration("Yearly", "2024-01-01", "2024-12-31"))
                .isInstanceOf(CardDemoException.class)
                .hasMessage("Unable to Write TDQ (JOBS)...")
                .hasCause(thrown);
    }

    /**
     * :purpose: The translated failure message equals the frozen legacy literal
     *  ``Unable to Write TDQ (JOBS)...`` byte-for-byte for the invalid-parameters
     *  case, and the launch exception is preserved as the cause.
     */
    @Test
    void launchStatementGenerationFailureMessageIsByteExact() throws Exception {
        InvalidJobParametersException thrown = new InvalidJobParametersException("x");
        when(jobLauncher.run(any(), any())).thenThrow(thrown);

        assertThatThrownBy(() -> config.launchStatementGeneration("Yearly", "2024-01-01", "2024-12-31"))
                .isInstanceOf(CardDemoException.class)
                .hasMessage("Unable to Write TDQ (JOBS)...")
                .hasCause(thrown);
    }

    /**
     * :purpose: Two successive submissions each launch the job with a ``run.id``
     *  present; presence rather than inequality is asserted because a uniqueness
     *  token can in principle repeat, so a strict inequality check would be flaky.
     */
    @Test
    void repeatedSubmissionsEachLaunchWithRunIdPresent() throws Exception {
        JobExecution jobExecution = mock(JobExecution.class);
        when(jobLauncher.run(eq(statementGenerationJob), any(JobParameters.class)))
                .thenReturn(jobExecution);

        config.launchStatementGeneration("Monthly", "2024-01-01", "2024-01-31");
        config.launchStatementGeneration("Monthly", "2024-01-01", "2024-01-31");

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher, times(2)).run(eq(statementGenerationJob), captor.capture());
        assertThat(captor.getAllValues()).hasSize(2);
        assertThat(captor.getAllValues().get(0).getString("run.id")).isNotNull();
        assertThat(captor.getAllValues().get(1).getString("run.id")).isNotNull();
    }
}
