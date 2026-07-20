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
package com.aws.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.boot.autoconfigure.batch.JobExecutionEvent;

/**
 * Unit tests for {@link BatchExitCodeGenerator}, the component that maps a batch job's Spring Batch
 * {@link ExitStatus} to the mainframe {@code 0}/{@code 4}/{@code 8} return code exposed as the JVM
 * process exit code.
 *
 * <p>Each test builds a {@link JobExecution} with a chosen {@link BatchStatus} and {@link ExitStatus},
 * delivers it to the generator as the {@link JobExecutionEvent} the launcher would publish, and
 * asserts the resolved exit code. The mapping under test gates success on the {@link BatchStatus}:
 * any non-{@code COMPLETED} status &rarr; {@code 8}; a {@code COMPLETED} run whose {@link ExitStatus}
 * code is {@code "COMPLETED_WITH_REJECTS"} &rarr; {@code 4}; and any other {@code COMPLETED} run
 * &mdash; including one whose exit code is {@code "NOOP"} (a restart with no remaining work) &rarr;
 * {@code 0}. Across multiple jobs in one JVM, the worst (highest) code wins (the {@code MAXCC}
 * analog).</p>
 */
class BatchExitCodeGeneratorTest {

    /** Builds a {@link JobExecution} carrying the given batch status and exit status. */
    private static JobExecution executionWith(BatchStatus status, ExitStatus exitStatus) {
        JobExecution execution = MetaDataInstanceFactory.createJobExecution();
        execution.setStatus(status);
        execution.setExitStatus(exitStatus);
        return execution;
    }

    private static void publish(BatchExitCodeGenerator generator, BatchStatus status, ExitStatus exitStatus) {
        generator.onApplicationEvent(new JobExecutionEvent(executionWith(status, exitStatus)));
    }

    @Test
    @DisplayName("no job ran → exit code 0")
    void noExecutionsYieldsZero() {
        assertThat(new BatchExitCodeGenerator().getExitCode()).isZero();
    }

    @Test
    @DisplayName("COMPLETED → exit code 0")
    void completedYieldsZero() {
        BatchExitCodeGenerator generator = new BatchExitCodeGenerator();
        publish(generator, BatchStatus.COMPLETED, ExitStatus.COMPLETED);
        assertThat(generator.getExitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("COMPLETED with NOOP exit code (restart no-op) → exit code 0")
    void completedNoopYieldsZero() {
        // A restart with no remaining work completes with BatchStatus.COMPLETED but ExitStatus
        // code "NOOP"; it is a success and must map to 0, not be mistaken for a failure. This
        // case was observed at runtime when re-launching an already-completed job instance.
        BatchExitCodeGenerator generator = new BatchExitCodeGenerator();
        publish(generator, BatchStatus.COMPLETED, new ExitStatus("NOOP"));
        assertThat(generator.getExitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("COMPLETED_WITH_REJECTS → exit code 4")
    void completedWithRejectsYieldsFour() {
        BatchExitCodeGenerator generator = new BatchExitCodeGenerator();
        publish(generator, BatchStatus.COMPLETED, new ExitStatus("COMPLETED_WITH_REJECTS"));
        assertThat(generator.getExitCode()).isEqualTo(4);
    }

    @Test
    @DisplayName("FAILED → exit code 8")
    void failedYieldsEight() {
        BatchExitCodeGenerator generator = new BatchExitCodeGenerator();
        publish(generator, BatchStatus.FAILED, ExitStatus.FAILED);
        assertThat(generator.getExitCode()).isEqualTo(8);
    }

    @Test
    @DisplayName("STOPPED (and other non-completed statuses) → exit code 8")
    void stoppedYieldsEight() {
        BatchExitCodeGenerator generator = new BatchExitCodeGenerator();
        publish(generator, BatchStatus.STOPPED, ExitStatus.STOPPED);
        assertThat(generator.getExitCode()).isEqualTo(8);
    }

    @Test
    @DisplayName("multiple jobs: COMPLETED then COMPLETED_WITH_REJECTS → worst code 4 (MAXCC)")
    void worstOfCompletedAndRejectsIsFour() {
        BatchExitCodeGenerator generator = new BatchExitCodeGenerator();
        publish(generator, BatchStatus.COMPLETED, ExitStatus.COMPLETED);
        publish(generator, BatchStatus.COMPLETED, new ExitStatus("COMPLETED_WITH_REJECTS"));
        assertThat(generator.getExitCode()).isEqualTo(4);
    }

    @Test
    @DisplayName("multiple jobs: COMPLETED_WITH_REJECTS then FAILED → worst code 8 (MAXCC)")
    void worstOfRejectsAndFailedIsEight() {
        BatchExitCodeGenerator generator = new BatchExitCodeGenerator();
        publish(generator, BatchStatus.COMPLETED, new ExitStatus("COMPLETED_WITH_REJECTS"));
        publish(generator, BatchStatus.FAILED, ExitStatus.FAILED);
        assertThat(generator.getExitCode()).isEqualTo(8);
    }
}
