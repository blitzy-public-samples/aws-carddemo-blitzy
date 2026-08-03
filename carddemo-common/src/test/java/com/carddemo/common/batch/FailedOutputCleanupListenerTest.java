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
package com.carddemo.common.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;

/**
 * :purpose: Unit tests for the failed-output cleanup listener, which stops a failed batch
 *     step from leaving behind a file that reads as a completed run.
 * :output: Assertions that an unsuccessful step's output is removed, that a successful
 *     step's output is preserved, that several outputs are handled together, and that a
 *     missing file is tolerated.
 */
@DisplayName("FailedOutputCleanupListener")
class FailedOutputCleanupListenerTest {

    @TempDir
    Path workDir;

    /**
     * :purpose: A step that ends FAILED has its partial output removed, so no artefact
     *     survives that could be mistaken for a completed run.
     * :raises IOException: if the fixture file cannot be written.
     */
    @Test
    @DisplayName("removes the output of a failed step")
    void removesOutputOfFailedStep() throws IOException {
        Path output = Files.writeString(workDir.resolve("cardReadJob-qa.txt"),
                "START OF EXECUTION OF PROGRAM CBACT02C\nEND OF EXECUTION OF PROGRAM CBACT02C\n");

        ExitStatus returned = new FailedOutputCleanupListener(output)
                .afterStep(stepExecution(BatchStatus.FAILED, ExitStatus.FAILED));

        assertThat(output).doesNotExist();
        // null leaves the step's own exit status in place.
        assertThat(returned).isNull();
    }

    /**
     * :purpose: A step that completes keeps its output untouched.
     * :raises IOException: if the fixture file cannot be written.
     */
    @Test
    @DisplayName("preserves the output of a completed step")
    void preservesOutputOfCompletedStep() throws IOException {
        Path output = Files.writeString(workDir.resolve("acctrept.txt"), "report body");

        new FailedOutputCleanupListener(output)
                .afterStep(stepExecution(BatchStatus.COMPLETED, ExitStatus.COMPLETED));

        assertThat(output).exists();
        assertThat(Files.readString(output)).isEqualTo("report body");
    }

    /**
     * :purpose: Both statement outputs are removed together when the step stops.
     * :raises IOException: if the fixture files cannot be written.
     */
    @Test
    @DisplayName("removes every registered output of a stopped step")
    void removesAllRegisteredOutputs() throws IOException {
        Path text = Files.writeString(workDir.resolve("statements.txt"), "");
        Path html = Files.writeString(workDir.resolve("statements.html"), "");

        new FailedOutputCleanupListener(text, html)
                .afterStep(stepExecution(BatchStatus.STOPPED, ExitStatus.STOPPED));

        assertThat(text).doesNotExist();
        assertThat(html).doesNotExist();
    }

    /**
     * :purpose: A step that failed before creating its file is handled without error.
     */
    @Test
    @DisplayName("tolerates an output that was never created")
    void toleratesMissingOutput() {
        Path missing = workDir.resolve("never-created.txt");

        new FailedOutputCleanupListener(missing)
                .afterStep(stepExecution(BatchStatus.FAILED, ExitStatus.FAILED));

        assertThat(missing).doesNotExist();
    }

    /**
     * :purpose: A step that reports COMPLETED with a non-COMPLETED exit code - the shape
     *     a job uses to signal a business-level failure - is still cleaned up.
     * :raises IOException: if the fixture file cannot be written.
     */
    @Test
    @DisplayName("removes the output when the exit code is not COMPLETED")
    void removesOutputWhenExitCodeIsNotCompleted() throws IOException {
        Path output = Files.writeString(workDir.resolve("trandet.txt"), "partial");

        new FailedOutputCleanupListener(output).afterStep(
                stepExecution(BatchStatus.COMPLETED, new ExitStatus("FAILED_EMPTY_FEED")));

        assertThat(output).doesNotExist();
    }

    /**
     * :purpose: Build a finished step execution with the given outcome.
     * :param status: the batch status to report.
     * :param exitStatus: the exit status to report.
     * :returns: the populated {@link StepExecution}.
     */
    private static StepExecution stepExecution(BatchStatus status, ExitStatus exitStatus) {
        JobExecution jobExecution =
                new JobExecution(1L, new JobInstance(1L, "testJob"), new JobParameters());
        StepExecution stepExecution = new StepExecution(1L, "testStep", jobExecution);
        stepExecution.setStatus(status);
        stepExecution.setExitStatus(exitStatus);
        return stepExecution;
    }

}
