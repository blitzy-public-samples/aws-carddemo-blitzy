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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;

/**
 * Unit tests for :class:`PostingJobCompletionListener`.
 *
 * :purpose: Verify the ``CBTRN02C`` end-of-run contract that AAP §0.6.4 freezes:
 *     the ``TRANSACTIONS PROCESSED`` and ``TRANSACTIONS REJECTED`` tallies, and the
 *     legacy ``RETURN-CODE`` mapping of ``0`` for a clean run and ``4`` when at
 *     least one record was rejected (``CBTRN02C`` L227-L233). The legacy
 *     ``APPL-RESULT`` values ``0`` / ``8`` / ``12`` are FILE-STATUS classifications
 *     inside the I/O paragraphs, not job return codes, so they surface as
 *     propagated exceptions rather than as an exit status and are deliberately not
 *     asserted here.
 * :output: JUnit 5 / AssertJ assertions over real Spring Batch ``JobExecution`` /
 *     ``StepExecution`` objects plus a Logback ``ListAppender`` capturing the two
 *     tally lines; no Spring context, database or file is involved.
 */
class PostingJobCompletionListenerTest {

    /** :purpose: Job name used by every fixture, matching the production bean name. */
    private static final String JOB_NAME = "transactionPostingJob";

    /** :purpose: Step name used by every fixture, matching the production bean name. */
    private static final String STEP_NAME = "transactionPostingStep";

    private PostingJobCompletionListener listener;

    private ch.qos.logback.classic.Logger listenerLogger;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        listener = new PostingJobCompletionListener();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        listenerLogger = context.getLogger(PostingJobCompletionListener.class);
        logAppender = new ListAppender<>();
        logAppender.setContext(context);
        logAppender.start();
        listenerLogger.addAppender(logAppender);
        listenerLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void detachLogAppender() {
        listenerLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    /**
     * Builds a job execution in the given batch status.
     *
     * :param status: the batch status the run ended in.
     * :output: a job execution with no step executions yet.
     */
    private static JobExecution jobExecution(BatchStatus status) {
        JobExecution execution = new JobExecution(1L, new JobInstance(1L, JOB_NAME), new JobParameters());
        execution.setStatus(status);
        execution.setExitStatus(status == BatchStatus.FAILED ? ExitStatus.FAILED : ExitStatus.COMPLETED);
        return execution;
    }

    /**
     * Registers a step execution carrying a read count and a published reject count.
     *
     * :param jobExecution: the owning job execution.
     * :param status: the status of this step execution.
     * :param readCount: the number of records the step read (processed tally).
     * :param rejectCount: the value the step published under
     *     :data:`PostingJobCompletionListener.REJECT_COUNT_KEY`.
     * :output: the registered step execution.
     */
    private static StepExecution step(JobExecution jobExecution, BatchStatus status,
                                      long readCount, long rejectCount) {
        StepExecution stepExecution = new StepExecution(STEP_NAME, jobExecution);
        if (!jobExecution.getStepExecutions().contains(stepExecution)) {
            jobExecution.addStepExecution(stepExecution);
        }
        stepExecution.setStatus(status);
        stepExecution.setReadCount(readCount);
        stepExecution.getExecutionContext()
                .putLong(PostingJobCompletionListener.REJECT_COUNT_KEY, rejectCount);
        return stepExecution;
    }

    /**
     * Returns the formatted messages the listener logged.
     *
     * :output: the captured log lines in emission order.
     */
    private List<String> loggedLines() {
        return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Nested
    @DisplayName("Return-code mapping for a completed run")
    class ReturnCodeMapping {

        @Test
        @DisplayName("a clean run leaves the COMPLETED exit status untouched (return code 0)")
        void cleanRunKeepsCompletedExitStatus() {
            JobExecution execution = jobExecution(BatchStatus.COMPLETED);
            step(execution, BatchStatus.COMPLETED, 300L, 0L);

            listener.afterJob(execution);

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
            assertThat(execution.getExitStatus().getExitDescription()).isEmpty();
        }

        @ParameterizedTest
        @DisplayName("a run with rejects reports COMPLETED_WITH_REJECTS and the return-code-4 description")
        @CsvSource({
                "1,   Return code 4: 1 transaction(s) rejected",
                "3,   Return code 4: 3 transaction(s) rejected",
                "300, Return code 4: 300 transaction(s) rejected"
        })
        void rejectsMapToReturnCodeFour(long rejectCount, String expectedDescription) {
            JobExecution execution = jobExecution(BatchStatus.COMPLETED);
            step(execution, BatchStatus.COMPLETED, 300L, rejectCount);

            listener.afterJob(execution);

            // The batch status stays COMPLETED: rejects are a business outcome, not a failure.
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED_WITH_REJECTS");
            assertThat(execution.getExitStatus().getExitDescription()).isEqualTo(expectedDescription);
        }

        @Test
        @DisplayName("a FAILED run keeps its FAILED exit status even when records were rejected")
        void failedRunIsNeverReportedAsCompletedWithRejects() {
            JobExecution execution = jobExecution(BatchStatus.FAILED);
            step(execution, BatchStatus.COMPLETED, 120L, 7L);

            listener.afterJob(execution);

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getExitStatus().getExitCode()).isEqualTo("FAILED");
        }

        @Test
        @DisplayName("a run with no step executions is flagged as an empty feed")
        void runWithoutStepExecutionsIsFlaggedAsAnEmptyFeed() {
            JobExecution execution = jobExecution(BatchStatus.COMPLETED);

            listener.afterJob(execution);

            // No step ran, so nothing was posted: the run must not be reported as a
            // clean completion.
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(PostingJobCompletionListener.EMPTY_FEED_EXIT_CODE);
            assertThat(loggedLines()).containsSubsequence(
                    "TRANSACTIONS PROCESSED :0",
                    "TRANSACTIONS REJECTED  :0");
        }
    }

    @Nested
    @DisplayName("Tally reporting")
    class TallyReporting {

        @Test
        @DisplayName("both tallies are logged with the legacy DISPLAY wording")
        void talliesUseTheLegacyDisplayWording() {
            JobExecution execution = jobExecution(BatchStatus.COMPLETED);
            step(execution, BatchStatus.COMPLETED, 300L, 4L);

            listener.afterJob(execution);

            assertThat(loggedLines()).containsExactly(
                    "TRANSACTIONS PROCESSED :300",
                    "TRANSACTIONS REJECTED  :4");
        }

        @Test
        @DisplayName("a non-COMPLETED step execution is excluded from both tallies")
        void incompleteStepExecutionsAreNotTallied() {
            JobExecution execution = jobExecution(BatchStatus.COMPLETED);
            StepExecution failed = new StepExecution(STEP_NAME, execution);
            if (!execution.getStepExecutions().contains(failed)) {
                execution.addStepExecution(failed);
            }
            failed.setStatus(BatchStatus.FAILED);
            failed.setReadCount(50L);
            failed.getExecutionContext()
                    .putLong(PostingJobCompletionListener.REJECT_COUNT_KEY, 9L);

            listener.afterJob(execution);

            // Only the failed partial execution exists, so nothing is counted. Having
            // posted nothing, the run is reported as an empty feed rather than a silent
            // false success.
            assertThat(loggedLines()).containsSubsequence(
                    "TRANSACTIONS PROCESSED :0",
                    "TRANSACTIONS REJECTED  :0");
            assertThat(loggedLines())
                    .contains("Daily transaction feed was empty: no records were read from DALYTRAN");
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(PostingJobCompletionListener.EMPTY_FEED_EXIT_CODE);
        }

        @Test
        @DisplayName("tallies sum across every completed step execution of the run")
        void talliesSumAcrossCompletedSteps() {
            JobExecution execution = jobExecution(BatchStatus.COMPLETED);
            StepExecution first = new StepExecution(STEP_NAME, execution);
            StepExecution second = new StepExecution(STEP_NAME + "Restart", execution);
            for (StepExecution stepExecution : List.of(first, second)) {
                if (!execution.getStepExecutions().contains(stepExecution)) {
                    execution.addStepExecution(stepExecution);
                }
                stepExecution.setStatus(BatchStatus.COMPLETED);
            }
            first.setReadCount(200L);
            first.getExecutionContext()
                    .putLong(PostingJobCompletionListener.REJECT_COUNT_KEY, 2L);
            second.setReadCount(100L);
            second.getExecutionContext()
                    .putLong(PostingJobCompletionListener.REJECT_COUNT_KEY, 3L);

            listener.afterJob(execution);

            assertThat(loggedLines()).containsExactly(
                    "TRANSACTIONS PROCESSED :300",
                    "TRANSACTIONS REJECTED  :5");
            assertThat(execution.getExitStatus().getExitDescription())
                    .isEqualTo("Return code 4: 5 transaction(s) rejected");
        }

        @Test
        @DisplayName("a step that published no reject count contributes zero rejects")
        void missingRejectCountKeyDefaultsToZero() {
            JobExecution execution = jobExecution(BatchStatus.COMPLETED);
            StepExecution stepExecution = new StepExecution(STEP_NAME, execution);
            if (!execution.getStepExecutions().contains(stepExecution)) {
                execution.addStepExecution(stepExecution);
            }
            stepExecution.setStatus(BatchStatus.COMPLETED);
            stepExecution.setReadCount(10L);

            listener.afterJob(execution);

            assertThat(loggedLines()).containsExactly(
                    "TRANSACTIONS PROCESSED :10",
                    "TRANSACTIONS REJECTED  :0");
            assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("the execution-context key the posting step publishes under is frozen")
    void rejectCountKeyIsFrozen() {
        assertThat(PostingJobCompletionListener.REJECT_COUNT_KEY).isEqualTo("posting.rejectCount");
    }
}
