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
package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.boot.autoconfigure.batch.JobExecutionEvent;

/**
 * Unit tests for {@link BatchReturnCodeExitCodeGenerator}, verifying the mainframe
 * {@code 0 / 4 / 8} return-code mapping and the worst-outcome-wins aggregation across job
 * executions. These are pure POJO tests (no Spring context) so the mapping contract is asserted
 * deterministically and in isolation from any launch wiring.
 */
class BatchReturnCodeExitCodeGeneratorTest {

    private BatchReturnCodeExitCodeGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new BatchReturnCodeExitCodeGenerator();
    }

    /** Feeds a synthetic {@link JobExecution} to the generator via a {@link JobExecutionEvent}. */
    private void publish(BatchStatus status, ExitStatus exitStatus) {
        JobExecution execution = new JobExecution(1L);
        execution.setStatus(status);
        execution.setExitStatus(exitStatus);
        generator.onApplicationEvent(new JobExecutionEvent(execution));
    }

    @Test
    @DisplayName("No job executions -> exit code 0")
    void noExecutionsYieldsZero() {
        assertThat(generator.getExitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("COMPLETED -> RC 0")
    void completedYieldsZero() {
        publish(BatchStatus.COMPLETED, ExitStatus.COMPLETED);
        assertThat(generator.getExitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("COMPLETED_WITH_REJECTS -> RC 4")
    void completedWithRejectsYieldsFour() {
        publish(BatchStatus.COMPLETED, new ExitStatus("COMPLETED_WITH_REJECTS"));
        assertThat(generator.getExitCode()).isEqualTo(4);
    }

    @Test
    @DisplayName("FAILED -> RC 8")
    void failedYieldsEight() {
        publish(BatchStatus.FAILED, ExitStatus.FAILED);
        assertThat(generator.getExitCode()).isEqualTo(8);
    }

    @Test
    @DisplayName("Abnormal BatchStatus escalates to RC 8 even with a benign exit-code string")
    void abnormalStatusEscalatesToEight() {
        // Defensive belt-and-suspenders: status is unsuccessful though the exit code reads COMPLETED.
        publish(BatchStatus.FAILED, ExitStatus.COMPLETED);
        assertThat(generator.getExitCode()).isEqualTo(8);
    }

    @Test
    @DisplayName("STOPPED (not COMPLETED/REJECTS) -> RC 8 via the fail-safe default")
    void stoppedYieldsEight() {
        publish(BatchStatus.STOPPED, ExitStatus.STOPPED);
        assertThat(generator.getExitCode()).isEqualTo(8);
    }

    @Test
    @DisplayName("ABANDONED -> RC 8")
    void abandonedYieldsEight() {
        publish(BatchStatus.ABANDONED, ExitStatus.FAILED);
        assertThat(generator.getExitCode()).isEqualTo(8);
    }

    @Test
    @DisplayName("Worst outcome wins: COMPLETED + COMPLETED_WITH_REJECTS -> RC 4")
    void maxAcrossCompletedAndRejects() {
        publish(BatchStatus.COMPLETED, ExitStatus.COMPLETED);
        publish(BatchStatus.COMPLETED, new ExitStatus("COMPLETED_WITH_REJECTS"));
        assertThat(generator.getExitCode()).isEqualTo(4);
    }

    @Test
    @DisplayName("Worst outcome wins: COMPLETED_WITH_REJECTS + FAILED -> RC 8")
    void maxAcrossRejectsAndFailed() {
        publish(BatchStatus.COMPLETED, new ExitStatus("COMPLETED_WITH_REJECTS"));
        publish(BatchStatus.FAILED, ExitStatus.FAILED);
        assertThat(generator.getExitCode()).isEqualTo(8);
    }

    @Test
    @DisplayName("Worst outcome wins: COMPLETED + FAILED -> RC 8")
    void maxAcrossCompletedAndFailed() {
        publish(BatchStatus.COMPLETED, ExitStatus.COMPLETED);
        publish(BatchStatus.FAILED, ExitStatus.FAILED);
        assertThat(generator.getExitCode()).isEqualTo(8);
    }

    @Test
    @DisplayName("A null JobExecution in the event is ignored")
    void nullJobExecutionIgnored() {
        generator.onApplicationEvent(new JobExecutionEvent(new JobExecution(1L)) {
            @Override
            public JobExecution getJobExecution() {
                return null;
            }
        });
        assertThat(generator.getExitCode()).isEqualTo(0);
    }
}
