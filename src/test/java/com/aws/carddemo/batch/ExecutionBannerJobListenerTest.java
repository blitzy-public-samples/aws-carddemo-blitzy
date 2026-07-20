/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.batch;

import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Fast, container-free unit test for {@link ExecutionBannerJobListener}.
 *
 * <p>Verifies the exact legacy parity contract of the master-print execution banners:</p>
 * <ul>
 *   <li>{@link ExecutionBannerJobListener#beforeJob(JobExecution)} always emits
 *       {@code START OF EXECUTION OF PROGRAM <name>} (the START {@code DISPLAY} is the first
 *       {@code PROCEDURE DIVISION} statement, so it is unconditional);</li>
 *   <li>{@link ExecutionBannerJobListener#afterJob(JobExecution)} emits
 *       {@code END OF EXECUTION OF PROGRAM <name>} <em>only</em> when the job finished
 *       {@link BatchStatus#COMPLETED} &mdash; a {@link BatchStatus#FAILED} job (the analog of a
 *       {@code CEE3ABD} abend) prints no END banner;</li>
 *   <li>the message text is byte-identical to the legacy literal and uses the supplied program
 *       name;</li>
 *   <li>the constructor rejects a {@code null} or blank program name.</li>
 * </ul>
 *
 * <p>Banner output is captured with a logback {@link ListAppender} attached to the listener's own
 * logger, the same capture technique the master-print job integration tests use.</p>
 */
class ExecutionBannerJobListenerTest {

    private static final String PROGRAM = "CBACT01C";
    private static final String EXPECTED_START = "START OF EXECUTION OF PROGRAM CBACT01C";
    private static final String EXPECTED_END = "END OF EXECUTION OF PROGRAM CBACT01C";

    private Logger listenerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        listenerLogger = (Logger) LoggerFactory.getLogger(ExecutionBannerJobListener.class);
        appender = new ListAppender<>();
        appender.start();
        listenerLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        listenerLogger.detachAppender(appender);
        appender.stop();
    }

    private List<String> capturedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static JobExecution jobExecutionWithStatus(BatchStatus status) {
        JobExecution jobExecution = new JobExecution(1L, new JobParameters());
        jobExecution.setStatus(status);
        return jobExecution;
    }

    @Test
    void beforeJobEmitsStartBannerWithProgramName() {
        new ExecutionBannerJobListener(PROGRAM).beforeJob(jobExecutionWithStatus(BatchStatus.STARTING));

        assertThat(capturedMessages()).containsExactly(EXPECTED_START);
    }

    @Test
    void afterJobOnCompletedEmitsEndBanner() {
        new ExecutionBannerJobListener(PROGRAM).afterJob(jobExecutionWithStatus(BatchStatus.COMPLETED));

        assertThat(capturedMessages()).containsExactly(EXPECTED_END);
    }

    @Test
    void afterJobOnFailedEmitsNoEndBanner() {
        new ExecutionBannerJobListener(PROGRAM).afterJob(jobExecutionWithStatus(BatchStatus.FAILED));

        assertThat(capturedMessages()).isEmpty();
    }

    @Test
    void fullLifecycleOnCompletedEmitsBothBannersInOrder() {
        ExecutionBannerJobListener listener = new ExecutionBannerJobListener(PROGRAM);
        JobExecution jobExecution = jobExecutionWithStatus(BatchStatus.STARTED);

        listener.beforeJob(jobExecution);
        jobExecution.setStatus(BatchStatus.COMPLETED);
        listener.afterJob(jobExecution);

        assertThat(capturedMessages()).containsExactly(EXPECTED_START, EXPECTED_END);
    }

    @Test
    void fullLifecycleOnFailureEmitsOnlyStartBanner() {
        ExecutionBannerJobListener listener = new ExecutionBannerJobListener(PROGRAM);
        JobExecution jobExecution = jobExecutionWithStatus(BatchStatus.STARTED);

        listener.beforeJob(jobExecution);
        jobExecution.setStatus(BatchStatus.FAILED);
        listener.afterJob(jobExecution);

        assertThat(capturedMessages()).containsExactly(EXPECTED_START);
    }

    @Test
    void bannersUseTheSuppliedProgramName() {
        ExecutionBannerJobListener listener = new ExecutionBannerJobListener("CBCUS01C");

        listener.beforeJob(jobExecutionWithStatus(BatchStatus.STARTED));
        listener.afterJob(jobExecutionWithStatus(BatchStatus.COMPLETED));

        assertThat(capturedMessages()).containsExactly(
                "START OF EXECUTION OF PROGRAM CBCUS01C",
                "END OF EXECUTION OF PROGRAM CBCUS01C");
    }

    @Test
    void constructorRejectsNullProgramName() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ExecutionBannerJobListener(null));
    }

    @Test
    void constructorRejectsBlankProgramName() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ExecutionBannerJobListener("   "));
    }
}
