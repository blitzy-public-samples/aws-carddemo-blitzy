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
import org.springframework.batch.core.job.parameters.JobParameter;
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
 *   a statement request launches the ``statementGenerationJob`` with the two output
 *   file names carried as identifying {@link JobParameters} — the ``STMTFILE`` and
 *   ``HTMLFILE`` DD names of ``CREASTMT``, which takes no ``PARM`` — that each
 *   submission records a distinct identifying ``run.id`` so a repeat print request is
 *   never refused as an already-completed instance, and that a launcher
 *   failure is surfaced as a {@link CardDemoException} carrying the frozen legacy
 *   operator message.
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
     *   with the two output file names as identifying string parameters plus a
     *   non-blank, IDENTIFYING uniqueness ``run.id``, records no invented
     *   report-type or date-window parameter, and returns the launcher's
     *   {@link JobExecution}.
     * :raises Exception: propagated from the mocked launcher signature.
     */
    @Test
    void launchStatementGenerationSubmitsJobWithTheOutputFileNames() throws Exception {
        JobExecution execution = mock(JobExecution.class);
        when(jobLauncher.run(eq(statementGenerationJob), any(JobParameters.class)))
                .thenReturn(execution);
        JobSchedulingConfig config = new JobSchedulingConfig(jobLauncher, statementGenerationJob, "statements.txt", "statements.html");

        JobExecution future = config.launchStatementGeneration("cycle.txt", "cycle.html");

        assertThat(future).isSameAs(execution);

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(statementGenerationJob), captor.capture());
        JobParameters params = captor.getValue();
        assertThat(params.getString("stmtFile")).isEqualTo("cycle.txt");
        assertThat(params.getString("htmlFile")).isEqualTo("cycle.html");
        assertThat(params.getParameter("stmtFile").identifying()).isTrue();
        assertThat(params.getParameter("htmlFile").identifying()).isTrue();
        assertThat(params.getString("run.id")).isNotBlank();
        // IDENTIFYING: a statement print request only reads business data and rewrites
        // its own two output files, so every submission must yield a fresh JobInstance.
        assertThat(params.getParameter("run.id").identifying()).isTrue();
        // CREASTMT carries no PARM, so no report type and no date window are recorded:
        // an identifying parameter the job cannot read would key duplicate detection on
        // a value that has no effect on the statement produced.
        assertThat(params.parameters()).extracting(JobParameter::name)
                .containsExactlyInAnyOrder("stmtFile", "htmlFile", "run.id");
    }

    /**
     * :purpose: Verify a submission that names no output files falls back to the
     *   configured default ``STMTFILE``/``HTMLFILE`` names, so the job stays runnable
     *   with no arguments at all, exactly as the legacy job stream was.
     * :raises Exception: propagated from the mocked launcher signature.
     */
    @Test
    void launchStatementGenerationFallsBackToTheConfiguredFileNames() throws Exception {
        JobExecution execution = mock(JobExecution.class);
        when(jobLauncher.run(eq(statementGenerationJob), any(JobParameters.class)))
                .thenReturn(execution);
        JobSchedulingConfig config = new JobSchedulingConfig(jobLauncher, statementGenerationJob, "statements.txt", "statements.html");

        config.launchStatementGeneration();

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(statementGenerationJob), captor.capture());
        assertThat(captor.getValue().getString("stmtFile")).isEqualTo("statements.txt");
        assertThat(captor.getValue().getString("htmlFile")).isEqualTo("statements.html");
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
                config.launchStatementGeneration("failing.txt", "failing.html"))
                .isInstanceOf(CardDemoException.class)
                .hasMessage("Unable to Write TDQ (JOBS)...")
                .hasCauseInstanceOf(JobExecutionAlreadyRunningException.class);
    }

    /**
     * :purpose: Verify two submissions with identical arguments each record a distinct
     *   ``run.id`` token, so every submission is traceable and, because the token is
     *   identifying, a repeated print request for the same output files starts a fresh
     *   run instead of being refused as an already-completed instance.
     * :raises Exception: propagated from the mocked launcher signature.
     */
    @Test
    void repeatSubmissionsUseDistinctRunId() throws Exception {
        JobExecution execution = mock(JobExecution.class);
        when(jobLauncher.run(eq(statementGenerationJob), any(JobParameters.class)))
                .thenReturn(execution);
        JobSchedulingConfig config = new JobSchedulingConfig(jobLauncher, statementGenerationJob, "statements.txt", "statements.html");

        config.launchStatementGeneration("cycle.txt", "cycle.html");
        config.launchStatementGeneration("cycle.txt", "cycle.html");

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher, times(2)).run(eq(statementGenerationJob), captor.capture());
        List<JobParameters> submissions = captor.getAllValues();
        assertThat(submissions).hasSize(2);
        assertThat(submissions.get(0).getString("run.id"))
                .isNotEqualTo(submissions.get(1).getString("run.id"));
    }
}
