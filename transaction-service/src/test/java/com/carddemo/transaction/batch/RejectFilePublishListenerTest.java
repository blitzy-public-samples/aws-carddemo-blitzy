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

import com.carddemo.common.batch.BatchOutputPathResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;

/**
 * Unit tests for :class:`RejectFilePublishListener`.
 *
 * :purpose: Verify that the ``DALYREJS`` reject file is a DELIVERABLE rather than a side
 *     effect of a run. Two properties are asserted, neither of which held before: the
 *     published generation name appears only when the instance has completed, so a
 *     downstream reader scanning for it can never read a partially written file; and a
 *     restart of a failed instance publishes the rejects of BOTH the failed execution and
 *     the restart, because the staging file is instance-scoped and appended rather than
 *     deleted on failure.
 * :output: JUnit 5 / AssertJ assertions against real files in a JUnit temporary directory;
 *     no Spring context, database or job launch is involved.
 */
class RejectFilePublishListenerTest {

    /** :purpose: Length of one reject record: 350-byte image + 80-byte trailer. */
    private static final int RECORD_LENGTH = 430;

    /** :purpose: Job instance under test, which supplies the generation number. */
    private static final long INSTANCE_ID = 33L;

    /** :purpose: Configured base name of the reject sink. */
    private static final String REJECT_FILE = "dalyrejs.txt";

    /** :purpose: The published generation name for {@link #INSTANCE_ID}. */
    private static final String GENERATION_FILE = "dalyrejs.G0033V00.txt";

    /** :purpose: The staging name the writer appends to before publication. */
    private static final String STAGING_FILE = GENERATION_FILE + ".part";

    @TempDir
    private Path tempDir;

    /**
     * Builds the listener under test over the temp dir standing in for the writable mount.
     *
     * :output: a listener resolving both names inside the temporary output root.
     */
    private RejectFilePublishListener listener() {
        return new RejectFilePublishListener(REJECT_FILE,
                new BatchOutputPathResolver(tempDir.toString(), tempDir.toString()));
    }

    /**
     * Builds a finished execution of the instance under test.
     *
     * :param executionId: the execution id.
     * :param status: the status the run ended in.
     * :output: the execution the listener is handed.
     */
    private static JobExecution execution(long executionId, BatchStatus status) {
        JobExecution execution = new JobExecution(executionId,
                new JobInstance(INSTANCE_ID, "transactionPostingJob"), new JobParameters());
        execution.setStatus(status);
        execution.setExitStatus(status == BatchStatus.COMPLETED
                ? ExitStatus.COMPLETED : ExitStatus.FAILED);
        return execution;
    }

    /**
     * Writes fixed-width reject records into the staging file, as the writer does.
     *
     * :param count: how many 430-byte records to stage.
     * :output: the staging file path.
     */
    private Path stage(int count) throws Exception {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < count; i++) {
            content.append(String.valueOf((char) ('A' + i)).repeat(RECORD_LENGTH)).append('\n');
        }
        Path staging = tempDir.resolve(STAGING_FILE);
        Files.write(staging, content.toString().getBytes(StandardCharsets.ISO_8859_1));
        return staging;
    }

    @Nested
    @DisplayName("Publication on completion")
    class PublicationOnCompletion {

        /**
         * :purpose: A completed instance publishes its rejects under the generation name a
         *     downstream reader scans for, and the staging file is gone afterwards so the
         *     next cycle cannot inherit it.
         */
        @Test
        @DisplayName("a completed run publishes the staging file under the generation name")
        void completedRunPublishesTheGeneration() throws Exception {
            stage(2);

            listener().afterJob(execution(1L, BatchStatus.COMPLETED));

            Path published = tempDir.resolve(GENERATION_FILE);
            assertThat(published).exists();
            assertThat(tempDir.resolve(STAGING_FILE)).doesNotExist();
            assertThat(Files.size(published)).isEqualTo(2L * (RECORD_LENGTH + 1));
        }

        /**
         * :purpose: Publication is a rename, not a copy, so the bytes the run committed are
         *     the bytes delivered -- byte-for-byte, with no re-encoding step that could
         *     widen a record.
         */
        @Test
        @DisplayName("the published bytes are exactly the bytes the run staged")
        void publishedBytesAreUnchanged() throws Exception {
            byte[] staged = Files.readAllBytes(stage(3));

            listener().afterJob(execution(1L, BatchStatus.COMPLETED));

            assertThat(Files.readAllBytes(tempDir.resolve(GENERATION_FILE))).isEqualTo(staged);
        }

        /**
         * :purpose: Publishing over a generation left behind by an earlier, superseded run
         *     must succeed rather than fail the job: the instance's own rejects are the
         *     authoritative set for that generation.
         */
        @Test
        @DisplayName("an existing generation file is replaced rather than failing the run")
        void anExistingGenerationIsReplaced() throws Exception {
            Files.write(tempDir.resolve(GENERATION_FILE), "stale".getBytes(StandardCharsets.ISO_8859_1));
            stage(1);
            JobExecution execution = execution(1L, BatchStatus.COMPLETED);

            listener().afterJob(execution);

            assertThat(Files.size(tempDir.resolve(GENERATION_FILE)))
                    .isEqualTo(RECORD_LENGTH + 1L);
            assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        }

        /**
         * :purpose: A run that rejected nothing must not leave an EMPTY published
         *     generation. An empty file under the generation name tells a downstream reader
         *     that a complete reject set exists and is empty, which is a different statement
         *     from "this cycle produced no reject file at all".
         */
        @Test
        @DisplayName("a run with no rejects publishes no file at all")
        void aRunWithNoRejectsPublishesNothing() {
            listener().afterJob(execution(1L, BatchStatus.COMPLETED));

            assertThat(tempDir.resolve(GENERATION_FILE)).doesNotExist();
            assertThat(tempDir.resolve(STAGING_FILE)).doesNotExist();
        }
    }

    @Nested
    @DisplayName("Retention on failure")
    class RetentionOnFailure {

        /**
         * :purpose: This is the QA finding's core defect. A failed execution's rejects were
         *     DELETED, and a restart resumes after the last committed record, so those
         *     rejects were never reprocessed and never delivered: the operator was told the
         *     re-driven job completed while the reject file was silently short. The staging
         *     file must survive the failure, and the published name must stay absent because
         *     the set is not yet complete.
         */
        @Test
        @DisplayName("a failed run retains its staging file and publishes nothing")
        void failedRunRetainsTheStagingFile() throws Exception {
            stage(2);

            listener().afterJob(execution(1L, BatchStatus.FAILED));

            assertThat(tempDir.resolve(STAGING_FILE)).exists();
            assertThat(Files.size(tempDir.resolve(STAGING_FILE)))
                    .isEqualTo(2L * (RECORD_LENGTH + 1));
            assertThat(tempDir.resolve(GENERATION_FILE)).doesNotExist();
        }

        /**
         * :purpose: The end-to-end guarantee: the restart of a failed instance publishes the
         *     rejects of BOTH executions, because the retained staging file is the same
         *     instance-scoped file the restart appends to.
         */
        @Test
        @DisplayName("the restart of a failed instance publishes both executions' rejects")
        void restartPublishesTheRejectsOfBothExecutions() throws Exception {
            // First execution commits two rejects and fails.
            stage(2);
            listener().afterJob(execution(1L, BatchStatus.FAILED));

            // The restart appends its own reject to the retained staging file, exactly as
            // the append-mode writer does, and completes.
            Files.write(tempDir.resolve(STAGING_FILE),
                    ("Z".repeat(RECORD_LENGTH) + "\n").getBytes(StandardCharsets.ISO_8859_1),
                    java.nio.file.StandardOpenOption.APPEND);
            listener().afterJob(execution(2L, BatchStatus.COMPLETED));

            assertThat(Files.readAllLines(tempDir.resolve(GENERATION_FILE),
                    StandardCharsets.ISO_8859_1))
                    .as("nothing committed by the failed execution is lost")
                    .hasSize(3);
            assertThat(tempDir.resolve(STAGING_FILE)).doesNotExist();
        }

        /**
         * :purpose: A stopped run is not a completed one, so it is treated as a failure for
         *     publication: the set is incomplete either way.
         */
        @Test
        @DisplayName("a stopped run also retains rather than publishes")
        void stoppedRunRetains() throws Exception {
            stage(1);

            listener().afterJob(execution(1L, BatchStatus.STOPPED));

            assertThat(tempDir.resolve(STAGING_FILE)).exists();
            assertThat(tempDir.resolve(GENERATION_FILE)).doesNotExist();
        }
    }

    @Nested
    @DisplayName("Record counting and defensive behaviour")
    class RecordCounting {

        /**
         * :purpose: The tally is measured from the FILE rather than counted from writes, so
         *     the number reported for a generation always agrees with the artefact
         *     delivered. Each record is 430 payload bytes plus exactly one LF.
         */
        @Test
        @DisplayName("the record count is the byte length divided by 431")
        void recordCountIsMeasuredFromTheFile() throws Exception {
            Path staging = stage(7);

            assertThat(RejectFilePublishListener.countRecords(staging)).isEqualTo(7L);
        }

        /**
         * :purpose: A file that cannot be measured must not throw out of an end-of-run
         *     listener and turn a successful job into a failed one.
         */
        @Test
        @DisplayName("an unmeasurable file counts zero rather than throwing")
        void anUnmeasurableFileCountsZero() {
            assertThat(RejectFilePublishListener.countRecords(tempDir.resolve("absent.txt")))
                    .isZero();
        }

        /**
         * :purpose: An execution with no resolvable instance carries no generation number, so
         *     the listener has nothing to publish and must return quietly rather than fail.
         */
        @Test
        @DisplayName("an execution without a job instance is a quiet no-op")
        void anExecutionWithoutAnInstanceIsANoOp() throws Exception {
            stage(1);
            JobExecution orphan = new JobExecution(1L, null, new JobParameters());
            orphan.setStatus(BatchStatus.COMPLETED);

            listener().afterJob(orphan);

            assertThat(tempDir.resolve(STAGING_FILE)).exists();
            assertThat(tempDir.resolve(GENERATION_FILE)).doesNotExist();
        }
    }
}
