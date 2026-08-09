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
package com.carddemo.transaction.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;

/**
 * Unit tests for the posting step's own listener
 * (:class:`TransactionPostingJob.RejectCountingStepListener`).
 *
 * :purpose: Verify the end-of-step contract the posting job relies on: the reject
 *     count is published for the job listener to read back, and the empty-``DALYTRAN``
 *     verdict — legacy application result code 12 — is rendered here so the job and
 *     step rows an operator queries always agree, and only when the feed
 *     itself holds no record rather than whenever an execution happens to read nothing
 *.
 * :output: JUnit 5 / AssertJ assertions over real Spring Batch ``StepExecution``
 *     objects; no Spring context, database or file is involved.
 */
@DisplayName("TransactionPostingJob step listener")
class TransactionPostingStepListenerTest {

    /** :purpose: Job name used by every fixture, matching the production bean name. */
    private static final String JOB_NAME = "transactionPostingJob";

    /** :purpose: Step name used by every fixture, matching the production bean name. */
    private static final String STEP_NAME = "transactionPostingStep";

    /**
     * :purpose: The running reject count is published under the frozen execution-context
     *     key so the job completion listener can report the legacy return code 4.
     */
    @Test
    @DisplayName("the reject count is published on the step execution context")
    void rejectCountIsPublished() {
        TransactionPostingJob.RejectCountingStepListener listener =
                new TransactionPostingJob.RejectCountingStepListener(() -> 5L);
        StepExecution stepExecution = stepExecution(BatchStatus.COMPLETED, 5L);
        listener.beforeStep(stepExecution);

        listener.afterStep(stepExecution);

        assertThat(stepExecution.getExecutionContext()
                .getLong(PostingJobCompletionListener.REJECT_COUNT_KEY, -1L)).isZero();
    }

    /**
     * :purpose: A run over a feed that holds no record COMPLETES with return code 0 and a
     *     zero reject tally, exactly as ``CBTRN02C`` does: its read loop ends on the first
     *     read, both DISPLAY lines report zero, and ``RETURN-CODE`` is raised to 4 only when
     *     records were rejected — return code 12 belongs to the OPEN/READ failure paths that
     *     ABEND the program [app/cbl/CBTRN02C.cbl L202-L234]. Failing the step instead paged
     *     an operator for a no-transaction business day.
     */
    @Test
    @DisplayName("an empty feed completes with return code 0 and a zero reject tally")
    void emptyFeedCompletesWithReturnCodeZero() {
        TransactionPostingJob.RejectCountingStepListener listener =
                new TransactionPostingJob.RejectCountingStepListener(() -> 0L);
        StepExecution stepExecution = stepExecution(BatchStatus.COMPLETED, 0L);
        stepExecution.setExitStatus(ExitStatus.COMPLETED);
        listener.beforeStep(stepExecution);

        ExitStatus exitStatus = listener.afterStep(stepExecution);

        assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(exitStatus.getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(stepExecution.getExecutionContext()
                .getLong(PostingJobCompletionListener.REJECT_COUNT_KEY, -1L)).isZero();
    }

    /**
     * :purpose: A restart that resumes past the last consumed record reads nothing yet is
     *     a correct, complete run, and is reported exactly like any other clean run.
     */
    @Test
    @DisplayName("a fully consumed restart completes cleanly")
    void fullyConsumedRestartCompletesCleanly() {
        TransactionPostingJob.RejectCountingStepListener listener =
                new TransactionPostingJob.RejectCountingStepListener(() -> 2L);
        StepExecution stepExecution = stepExecution(BatchStatus.COMPLETED, 0L);
        stepExecution.setExitStatus(ExitStatus.COMPLETED);
        listener.beforeStep(stepExecution);

        ExitStatus exitStatus = listener.afterStep(stepExecution);

        assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(exitStatus.getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * :purpose: A step that already failed keeps its own failure: the empty-feed verdict
     *     must never overwrite the real cause of an abend.
     */
    @Test
    @DisplayName("an already failed step keeps its own failure cause")
    void failedStepKeepsItsOwnFailure() {
        TransactionPostingJob.RejectCountingStepListener listener =
                new TransactionPostingJob.RejectCountingStepListener(() -> 0L);
        StepExecution stepExecution = stepExecution(BatchStatus.FAILED, 0L);
        stepExecution.setExitStatus(ExitStatus.FAILED);
        listener.beforeStep(stepExecution);

        ExitStatus exitStatus = listener.afterStep(stepExecution);

        assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(exitStatus.getExitCode()).isEqualTo(ExitStatus.FAILED.getExitCode());
    }

    /**
     * :purpose: Build a step execution in a given status with a given read count.
     * :param status: the status the step ended in.
     * :param readCount: the number of feed records the step read.
     * :returns: the step execution, attached to a job execution as Spring Batch does.
     */
    private static StepExecution stepExecution(BatchStatus status, long readCount) {
        JobExecution jobExecution =
                new JobExecution(1L, new JobInstance(1L, JOB_NAME), new JobParameters());
        StepExecution stepExecution = new StepExecution(1L, STEP_NAME, jobExecution);
        jobExecution.addStepExecution(stepExecution);
        stepExecution.setStatus(status);
        stepExecution.setReadCount(readCount);
        return stepExecution;
    }
}
