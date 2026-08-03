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
 * language governing permissions and limitations under the License
 */
package com.carddemo.reporting.config;

import com.carddemo.common.exception.CardDemoException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobLauncher;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * :purpose: Pure JUnit 5 + Mockito unit test for {@link JobSchedulingConfig}, the
 *   re-platformed ``CORPT00C`` TDQ ``'JOBS'`` submission mechanism. It verifies that
 *   a report request launches the ``statementGenerationJob`` with the report type
 *   and date range carried as {@link JobParameters}, that each submission uses a
 *   unique ``run.id`` (mirroring "every submit is a new JES job"), and that a
 *   launcher failure is surfaced as a {@link CardDemoException} carrying the frozen
 *   legacy operator message.
 */
@ExtendWith(MockitoExtension.class)
class JobSchedulingConfigTest {

    /** Mocked auto-configured Spring Batch launcher. */
    @Mock
    private JobLauncher jobLauncher;

    /** Mocked ``statementGenerationJob`` job bean injected by name. */
    @Mock
    private Job statementGenerationJob;

    /**
     * :purpose: Verify the launch entry point submits the ``statementGenerationJob``
     *   with the report type and date range as string parameters plus a non-blank
     *   uniqueness ``run.id``, and completes the returned future with the launcher's
     *   {@link JobExecution}.
     * :raises Exception: propagated from the mocked launcher signature.
     */
    @Test
    void launchStatementGenerationSubmitsJobWithReportParameters() throws Exception {
        JobExecution execution = mock(JobExecution.class);
        when(jobLauncher.run(eq(statementGenerationJob), any(JobParameters.class)))
                .thenReturn(execution);
        JobSchedulingConfig config = new JobSchedulingConfig(jobLauncher, statementGenerationJob, "statements.txt", "statements.html");

        JobExecution future = config.launchStatementGeneration("Custom", "2024-01-01", "2024-01-31");

                assertThat(future).isSameAs(execution);

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(statementGenerationJob), captor.capture());
        JobParameters params = captor.getValue();
        assertThat(params.getString("reportType")).isEqualTo("Custom");
        assertThat(params.getString("startDate")).isEqualTo("2024-01-01");
        assertThat(params.getString("endDate")).isEqualTo("2024-01-31");
        assertThat(params.getString("run.id")).isNotBlank();
    }

    /**
     * :purpose: Verify a checked launch failure is caught and rethrown as a
     *   {@link CardDemoException} whose message equals the frozen legacy literal
     *   ``Unable to Write TDQ (JOBS)...`` byte-for-byte and whose cause is the
     *   originating Spring Batch launch exception.
     * :raises Exception: propagated from the mocked launcher signature.
     */
    @Test
    void launchStatementGenerationSurfacesFrozenMessageOnLaunchFailure() throws Exception {
        JobExecutionAlreadyRunningException cause =
                new JobExecutionAlreadyRunningException("job already running");
        when(jobLauncher.run(eq(statementGenerationJob), any(JobParameters.class)))
                .thenThrow(cause);
        JobSchedulingConfig config = new JobSchedulingConfig(jobLauncher, statementGenerationJob, "statements.txt", "statements.html");

        assertThatThrownBy(() ->
                config.launchStatementGeneration("Monthly", "2024-02-01", "2024-02-29"))
                .isInstanceOf(CardDemoException.class)
                .hasMessage("Unable to Write TDQ (JOBS)...")
                .hasCauseInstanceOf(JobExecutionAlreadyRunningException.class);
    }

    /**
     * :purpose: Verify two submissions with identical report arguments each build a
     *   distinct ``run.id`` so a repeat report request is never rejected as an
     *   already-completed job instance.
     * :raises Exception: propagated from the mocked launcher signature.
     */
    @Test
    void repeatSubmissionsUseDistinctRunId() throws Exception {
        JobExecution execution = mock(JobExecution.class);
        when(jobLauncher.run(eq(statementGenerationJob), any(JobParameters.class)))
                .thenReturn(execution);
        JobSchedulingConfig config = new JobSchedulingConfig(jobLauncher, statementGenerationJob, "statements.txt", "statements.html");

        config.launchStatementGeneration("Custom", "2024-01-01", "2024-01-31");
        config.launchStatementGeneration("Custom", "2024-01-01", "2024-01-31");

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher, times(2)).run(eq(statementGenerationJob), captor.capture());
        List<JobParameters> submissions = captor.getAllValues();
        assertThat(submissions).hasSize(2);
        assertThat(submissions.get(0).getString("run.id"))
                .isNotEqualTo(submissions.get(1).getString("run.id"));
    }
}
