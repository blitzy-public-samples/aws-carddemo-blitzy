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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobOperator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * :purpose: Pure JUnit 5 + Mockito unit test for {@link JobSchedulingConfig}, the
 *   re-platformed ``CORPT00C`` TDQ ``'JOBS'`` submission mechanism. It verifies that
 *   a statement request launches the ``statementGenerationJob`` with the two output
 *   file names carried as identifying {@link JobParameters} — the ``STMTFILE`` and
 *   ``HTMLFILE`` DD names of ``CREASTMT``, which takes no ``PARM`` — that each
 *   submission records a distinct identifying ``run.id`` so a repeat print request is
 *   never refused as an already-completed instance, and that an operator
 *   failure is surfaced as a {@link CardDemoException} carrying the frozen legacy
 *   operator message.
 */
@ExtendWith(MockitoExtension.class)
class JobSchedulingConfigTest {

    /** Mocked auto-configured Spring Batch operator. */
    @Mock
    private JobOperator jobOperator;

    /** Mocked ``statementGenerationJob`` job bean injected by name. */
    @Mock
    private Job statementGenerationJob;

    /** Writable batch output root the launcher validates output names against. */
    @org.junit.jupiter.api.io.TempDir
    private java.nio.file.Path tempDir;

    /**
     * :purpose: Construct the system under test over the mocked operator and job with
     *   the configured default ``STMTFILE``/``HTMLFILE`` output names.
     * :returns: the configured {@link JobSchedulingConfig}.
     */
    private JobSchedulingConfig newConfig() {
        // A real resolver over a temp root: the launcher now VALIDATES both output names
        // against the batch output root before submitting, so a stub would not exercise the
        // path the production code takes.
        return new JobSchedulingConfig(jobOperator, statementGenerationJob,
                "statements.txt", "statements.html",
                new com.carddemo.common.batch.BatchOutputPathResolver(
                        tempDir.toString(), tempDir.toString()));
    }

    /**
     * :purpose: Build a job execution reporting a successful run.
     * :returns: a mocked {@link JobExecution} whose status is ``COMPLETED``.
     */
    private static JobExecution completedExecution() {
        JobExecution execution = mock(JobExecution.class);
        when(execution.getStatus()).thenReturn(BatchStatus.COMPLETED);
        return execution;
    }

    /**
     * :purpose: Verify the launch entry point submits the ``statementGenerationJob``
     *   with the two output file names as identifying string parameters plus a
     *   non-blank, IDENTIFYING uniqueness ``run.id``, records no invented
     *   report-type or date-window parameter, and returns the launcher's
     *   {@link JobExecution}.
     * :raises Exception: propagated from the mocked launcher signature.
     */
    @Test
    void launchStatementGenerationSubmitsJobWithReportParameters() throws Exception {
        JobExecution execution = completedExecution();
        when(jobOperator.start(eq(statementGenerationJob), any(JobParameters.class)))
                .thenReturn(execution);
        JobSchedulingConfig config = newConfig();

        JobExecution future = config.launchStatementGeneration("cycle.txt", "cycle.html");

        assertThat(future).isSameAs(execution);

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(eq(statementGenerationJob), captor.capture());
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
        JobExecution execution = completedExecution();
        when(jobOperator.start(eq(statementGenerationJob), any(JobParameters.class)))
                .thenReturn(execution);
        JobSchedulingConfig config = newConfig();

        config.launchStatementGeneration();

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(eq(statementGenerationJob), captor.capture());
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
        when(jobOperator.start(eq(statementGenerationJob), any(JobParameters.class)))
                .thenThrow(cause);
        JobSchedulingConfig config = newConfig();

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
        JobExecution execution = completedExecution();
        when(jobOperator.start(eq(statementGenerationJob), any(JobParameters.class)))
                .thenReturn(execution);
        JobSchedulingConfig config = newConfig();

        config.launchStatementGeneration("cycle.txt", "cycle.html");
        config.launchStatementGeneration("cycle.txt", "cycle.html");

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator, times(2)).start(eq(statementGenerationJob), captor.capture());
        List<JobParameters> submissions = captor.getAllValues();
        assertThat(submissions).hasSize(2);
        assertThat(submissions.get(0).getString("run.id"))
                .isNotEqualTo(submissions.get(1).getString("run.id"));
    }

    /**
     * :purpose: Verify an output file name that does not resolve inside the configured
     *   batch output root is refused SYNCHRONOUSLY, as a domain refusal, and that the
     *   operator is never reached — so no job instance and no ``BATCH_JOB_EXECUTION`` row
     *   is created for a run that could never have produced a statement.
     * :param hostileName: an output name that escapes the root or is otherwise unusable.
     * :note: The containment rule was already enforced, but only later, inside the
     *   step-scoped writer factory. The caller therefore received ``202 ACCEPTED`` and the
     *   run then failed with a ``BeanCreationException`` whose stack trace was persisted
     *   into ``BATCH_JOB_EXECUTION.exit_message``.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "../../../../etc/passwd",
        "../outside.txt",
        "/etc/passwd",
        "sub/../../escape.txt"})
    void launchStatementGenerationRefusesAnUnusableStatementFile(String hostileName) {
        JobSchedulingConfig config = newConfig();

        assertThatThrownBy(() -> config.launchStatementGeneration(hostileName, "statements.html"))
                .isInstanceOf(CardDemoException.class);

        verifyNoInteractions(jobOperator);
    }

    /**
     * :purpose: Verify the HTML output name is gated by the same rule as the plain-text
     *   name, so neither of the two ``CREASTMT`` DD names can escape the output root.
     */
    @Test
    void launchStatementGenerationRefusesAnUnusableHtmlFile() {
        JobSchedulingConfig config = newConfig();

        assertThatThrownBy(() ->
                config.launchStatementGeneration("statements.txt", "../../../../etc/passwd"))
                .isInstanceOf(CardDemoException.class);

        verifyNoInteractions(jobOperator);
    }

    /**
     * :purpose: Verify the refusal reads as an operator-facing message naming the rejected
     *   value, and carries none of the Spring plumbing that previously reached the caller
     *   and the batch audit record.
     */
    @Test
    void refusalCarriesNoFrameworkPlumbing() {
        JobSchedulingConfig config = newConfig();

        assertThatThrownBy(() ->
                config.launchStatementGeneration("../../../../etc/passwd", "statements.html"))
                .isInstanceOf(CardDemoException.class)
                .hasMessageNotContaining("BeanCreationException")
                .hasMessageNotContaining("scopedTarget")
                .hasMessageNotContaining("org.springframework")
                .hasMessageNotContaining(JobSchedulingConfig.SUBMIT_FAILURE_MESSAGE);
    }

    /**
     * :purpose: Verify a plain name inside the output root is still accepted, so the new
     *   synchronous gate refuses only what the writer factory would have refused later.
     * :raises Exception: propagated from the mocked launcher signature.
     */
    @Test
    void launchStatementGenerationAcceptsNamesInsideTheOutputRoot() throws Exception {
        JobExecution execution = completedExecution();
        when(jobOperator.start(eq(statementGenerationJob), any(JobParameters.class)))
                .thenReturn(execution);
        JobSchedulingConfig config = newConfig();

        config.launchStatementGeneration("cycle-2026-09.txt", "cycle-2026-09.html");

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(eq(statementGenerationJob), captor.capture());
        assertThat(captor.getValue().getString("stmtFile")).isEqualTo("cycle-2026-09.txt");
        assertThat(captor.getValue().getString("htmlFile")).isEqualTo("cycle-2026-09.html");
    }
}
