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

    /**
     * :purpose: Stands in for the durable metadata store the listener consults to find the
     *     OTHER executions of the same job instance. Stubbed empty by default, so a fixture
     *     that registers only the current execution tallies only that execution.
     */
    private org.springframework.batch.core.repository.JobRepository jobRepository;

    private ch.qos.logback.classic.Logger listenerLogger;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        jobRepository =
                org.mockito.Mockito.mock(org.springframework.batch.core.repository.JobRepository.class);
        org.mockito.Mockito.when(jobRepository.getJobExecutions(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        listener = new PostingJobCompletionListener(jobRepository);
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
        StepExecution stepExecution = new StepExecution(1L, STEP_NAME, jobExecution);
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
        @DisplayName("the empty-feed verdict is left to the step, which owns both facts")
        void emptyFeedVerdictIsLeftToTheStep() {
            JobExecution execution = jobExecution(BatchStatus.COMPLETED);

            listener.afterJob(execution);

            // Reading nothing is not by itself an empty feed: a restart that has already
            // consumed every record legitimately reads zero rows. Only the
            // step knows both its read count and whether the feed table holds records, so
            // it renders the verdict and Spring Batch carries the failed status onto the
            // job - which also keeps the job and step rows consistent.
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());
            assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
            assertThat(loggedLines()).containsExactly(
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
        @DisplayName("a step execution that ended abnormally contributes exactly what it committed")
        void abnormalStepExecutionContributesItsCommittedWork() {
            JobExecution execution = jobExecution(BatchStatus.COMPLETED);
            StepExecution failed = new StepExecution(1L, STEP_NAME, execution);
            if (!execution.getStepExecutions().contains(failed)) {
                execution.addStepExecution(failed);
            }
            failed.setStatus(BatchStatus.FAILED);
            // The observed shape of a partially committed posting step: it READ one more
            // record than it committed, because the in-flight chunk rolled back.
            failed.setReadCount(50L);
            failed.setWriteCount(49L);
            failed.getExecutionContext()
                    .putLong(PostingJobCompletionListener.REJECT_COUNT_KEY, 9L);

            listener.afterJob(execution);

            // 49 committed, not the 50 read: counting the read of the rolled-back chunk
            // would report one more record than the feed holds once a restart adds its
            // own reads. The 9 rejects WERE committed - the execution context is persisted
            // in the chunk transaction - and they are already in the instance's reject
            // generation, so dropping them would under-report the delivered file.
            assertThat(loggedLines()).containsExactly(
                    "TRANSACTIONS PROCESSED :49",
                    "TRANSACTIONS REJECTED  :9");
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo("COMPLETED_WITH_REJECTS");
        }

        @Test
        @DisplayName("a run that ended abnormally reports no tally at all")
        void abnormalRunReportsNoTally() {
            JobExecution execution = jobExecution(BatchStatus.FAILED);
            step(execution, BatchStatus.COMPLETED, 2L, 0L);

            listener.afterJob(execution);

            // CBTRN02C reaches its end-of-run DISPLAY statements only on the normal
            // end-of-file path; 9999-ABEND-PROGRAM calls CEE3ABD and no tally is ever
            // printed. Emitting one anyway printed PROCESSED :0 / REJECTED :0 for a run
            // that had committed records - a figure with no legacy analogue that
            // contradicted the committed work.
            assertThat(loggedLines()).noneMatch(line -> line.startsWith("TRANSACTIONS PROCESSED"));
            assertThat(loggedLines()).noneMatch(line -> line.startsWith("TRANSACTIONS REJECTED"));
            assertThat(loggedLines()).anyMatch(line -> line.contains("ended FAILED"));
        }

        @Test
        @DisplayName("tallies sum across every completed step execution of the run")
        void talliesSumAcrossCompletedSteps() {
            JobExecution execution = jobExecution(BatchStatus.COMPLETED);
            StepExecution first = new StepExecution(1L, STEP_NAME, execution);
            StepExecution second = new StepExecution(2L, STEP_NAME + "Restart", execution);
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
            StepExecution stepExecution = new StepExecution(1L, STEP_NAME, execution);
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

    @Nested
    @DisplayName("Tally across every execution of a restarted instance")
    class RestartedInstanceTally {

        /**
         * Builds an EARLIER execution of the same job instance, as the metadata store returns
         * it on a restart.
         *
         * :param executionId: id of the earlier execution, distinct from the current one.
         * :param status: the status that earlier execution ended in.
         * :param readCount: records its step committed.
         * :param rejectCount: rejects its step published.
         * :output: the earlier execution, with one registered step execution.
         */
        private JobExecution earlierExecution(long executionId, BatchStatus status,
                                              long readCount, long rejectCount) {
            JobExecution earlier =
                    new JobExecution(executionId, new JobInstance(1L, JOB_NAME), new JobParameters());
            earlier.setStatus(status);
            StepExecution stepExecution = new StepExecution(executionId, STEP_NAME, earlier);
            if (!earlier.getStepExecutions().contains(stepExecution)) {
                earlier.addStepExecution(stepExecution);
            }
            // The step row of a failed execution carries the FAILED status, and its
            // counters describe what it committed before failing: it read one record more
            // than it committed, because the in-flight chunk rolled back. This is the
            // shape observed in BATCH_STEP_EXECUTION for the reproduced failure
            // (status FAILED, read 5, write 4, commit 4, rollback 1).
            stepExecution.setStatus(status == BatchStatus.COMPLETED
                    ? BatchStatus.COMPLETED : BatchStatus.FAILED);
            stepExecution.setReadCount(status == BatchStatus.COMPLETED ? readCount : readCount + 1);
            stepExecution.setWriteCount(readCount);
            stepExecution.getExecutionContext()
                    .putLong(PostingJobCompletionListener.REJECT_COUNT_KEY, rejectCount);
            return earlier;
        }

        /**
         * :purpose: A restart resumes after the last committed record, so the rejects the
         *     failed execution already committed are NOT reprocessed by the restart. Tallying
         *     only the current execution therefore under-reported the instance: the run
         *     announced fewer rejected transactions than the published reject file contained,
         *     and the difference was silent. The tally must span the whole instance.
         */
        @Test
        @DisplayName("the tally spans the failed execution and the restart that completed it")
        void tallySpansEveryExecutionOfTheInstance() {
            JobExecution restart =
                    new JobExecution(2L, new JobInstance(1L, JOB_NAME), new JobParameters());
            restart.setStatus(BatchStatus.COMPLETED);
            restart.setExitStatus(ExitStatus.COMPLETED);
            step(restart, BatchStatus.COMPLETED, 100L, 3L);
            org.mockito.Mockito.when(jobRepository.getJobExecutions(restart.getJobInstance()))
                    .thenReturn(List.of(earlierExecution(1L, BatchStatus.FAILED, 200L, 5L), restart));

            listener.afterJob(restart);

            // 200 committed by the failed execution (it read 201; the 201st chunk rolled
            // back) plus the restart's 100, and 5 + 3 rejected across the instance. The
            // return code reflects the instance total rather than the restart's own share.
            assertThat(loggedLines()).containsExactly(
                    "TRANSACTIONS PROCESSED :300",
                    "TRANSACTIONS REJECTED  :8");
            assertThat(restart.getExitStatus().getExitCode()).isEqualTo("COMPLETED_WITH_REJECTS");
            assertThat(restart.getExitStatus().getExitDescription())
                    .isEqualTo("Return code 4: 8 transaction(s) rejected");
        }

        /**
         * :purpose: The metadata store returns the CURRENT execution among the instance's
         *     executions, so it must be counted exactly once however it arrives.
         */
        @Test
        @DisplayName("the current execution is counted once even when the store also returns it")
        void currentExecutionIsNeverDoubleCounted() {
            JobExecution execution = jobExecution(BatchStatus.COMPLETED);
            step(execution, BatchStatus.COMPLETED, 40L, 2L);
            org.mockito.Mockito.when(jobRepository.getJobExecutions(execution.getJobInstance()))
                    .thenReturn(List.of(execution));

            listener.afterJob(execution);

            assertThat(loggedLines()).containsExactly(
                    "TRANSACTIONS PROCESSED :40",
                    "TRANSACTIONS REJECTED  :2");
        }

        /**
         * :purpose: The listener must still report when the metadata store cannot be
         *     consulted, because a tally is the only record of the run's outcome.
         */
        @Test
        @DisplayName("a metadata-store failure leaves the current execution's own tally intact")
        void storeFailureStillReportsTheCurrentExecution() {
            JobExecution execution = jobExecution(BatchStatus.COMPLETED);
            step(execution, BatchStatus.COMPLETED, 12L, 1L);
            org.mockito.Mockito.when(jobRepository.getJobExecutions(execution.getJobInstance()))
                    .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));

            listener.afterJob(execution);

            assertThat(loggedLines()).contains(
                    "TRANSACTIONS PROCESSED :12",
                    "TRANSACTIONS REJECTED  :1");
        }
    }

    @Test
    @DisplayName("the execution-context key the posting step publishes under is frozen")
    void rejectCountKeyIsFrozen() {
        assertThat(PostingJobCompletionListener.REJECT_COUNT_KEY).isEqualTo("posting.rejectCount");
    }
}
