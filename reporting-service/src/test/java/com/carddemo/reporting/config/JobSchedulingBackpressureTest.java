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
package com.carddemo.reporting.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.common.batch.BatchOutputPathResolver;
import com.carddemo.common.exception.CardDemoException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;

/**
 * :purpose: Verifies the statement-submission surface applies BACKPRESSURE rather than
 *     blocking the submitting thread, and that runs are serialized so two of them can never
 *     share the one pair of output files the ``CREASTMT`` DD names pin.
 * :note: Exercises the production constructor — the one that builds the bounded executor —
 *     because the executor and the admission bound ARE the behaviour under test. The job is
 *     a stub that parks until released, standing in for a long statement run.
 */
@DisplayName("JobSchedulingConfig — submission backpressure and run serialization")
class JobSchedulingBackpressureTest {

    /** Submissions the surface admits at once: one running plus the bounded backlog. */
    private static final int ADMITTED = 5;

    /** Bound on any wait in this test, so a regression fails fast instead of hanging. */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Output/input root the statement names resolve under, so no test writes outside it. */
    @TempDir
    static Path batchRoot;

    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger concurrentRuns = new AtomicInteger();
    private final AtomicInteger peakConcurrentRuns = new AtomicInteger();
    private final CountDownLatch firstRunStarted = new CountDownLatch(1);

    @AfterEach
    void releaseParkedRuns() {
        release.countDown();
    }

    /**
     * :purpose: A statement job that parks on the release latch, recording how many runs
     *     execute at once.
     * :returns: the stub job.
     */
    private Job parkingJob() {
        return new Job() {
            @Override
            public String getName() {
                return "statementGenerationJob";
            }

            @Override
            public void execute(JobExecution execution) {
                firstRunStarted.countDown();
                peakConcurrentRuns.accumulateAndGet(concurrentRuns.incrementAndGet(), Math::max);
                try {
                    release.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    concurrentRuns.decrementAndGet();
                }
                execution.setStatus(BatchStatus.COMPLETED);
            }
        };
    }

    private static JobRepository jobRepository() {
        return new ResourcelessJobRepository();
    }

    /**
     * :purpose: Confine the statement file names this test submits to the temporary root.
     * :returns: a resolver rooted at the per-class temporary directory.
     */
    private static BatchOutputPathResolver pathResolver() {
        return new BatchOutputPathResolver(batchRoot.toString(), batchRoot.toString());
    }

    @Test
    @DisplayName("submissions return promptly and the over-capacity one is refused with the frozen message")
    void overCapacitySubmissionIsRefusedPromptly() throws Exception {
        Job job = parkingJob();
        JobSchedulingConfig config = new JobSchedulingConfig(
                jobRepository(), job, "statements.txt", "statements.html", pathResolver());

        List<JobExecution> accepted = new ArrayList<>();
        long startedAt = System.nanoTime();
        for (int i = 0; i < ADMITTED; i++) {
            accepted.add(config.launchStatementGeneration());
        }
        long submissionNanos = System.nanoTime() - startedAt;

        assertThat(accepted).hasSize(ADMITTED);
        assertThat(accepted).allSatisfy(execution -> assertThat(execution).isNotNull());
        // The point of the fix: a full executor must not park the submitter. The previous
        // throttle blocked for as long as a run took (measured up to 40.5 s in QA).
        assertThat(Duration.ofNanos(submissionNanos))
                .as("admitted submissions must not block on the running job")
                .isLessThan(TIMEOUT);

        assertThat(firstRunStarted.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(config::launchStatementGeneration)
                .isInstanceOf(CardDemoException.class)
                .hasMessage(JobSchedulingConfig.SUBMIT_FAILURE_MESSAGE);
    }

    @Test
    @DisplayName("runs are serialized so two of them never open the same output files")
    void runsAreSerialized() throws Exception {
        Job job = parkingJob();
        JobSchedulingConfig config = new JobSchedulingConfig(
                jobRepository(), job, "statements.txt", "statements.html", pathResolver());

        for (int i = 0; i < ADMITTED; i++) {
            config.launchStatementGeneration();
        }
        assertThat(firstRunStarted.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        // Give any second worker the chance to start before asserting the peak.
        Thread.sleep(250);

        assertThat(peakConcurrentRuns.get())
                .as("two concurrent runs would open the same statements.txt / statements.html")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a permit is returned when a run ends, so capacity recovers")
    void capacityRecoversAfterRunsComplete() throws Exception {
        Job job = parkingJob();
        JobSchedulingConfig config = new JobSchedulingConfig(
                jobRepository(), job, "statements.txt", "statements.html", pathResolver());

        for (int i = 0; i < ADMITTED; i++) {
            config.launchStatementGeneration();
        }
        assertThatThrownBy(config::launchStatementGeneration)
                .isInstanceOf(CardDemoException.class)
                .hasMessage(JobSchedulingConfig.SUBMIT_FAILURE_MESSAGE);

        release.countDown();

        boolean recovered = false;
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                config.launchStatementGeneration();
                recovered = true;
                break;
            } catch (CardDemoException stillFull) {
                Thread.sleep(50);
            }
        }
        assertThat(recovered)
                .as("permits must be returned as runs end, or the surface dies after one burst")
                .isTrue();
    }

    /**
     * :purpose: The caller-supplied-operator constructor must impose no admission bound of
     *     its own, so a test or caller that owns the executor is never refused.
     */
    @Test
    @DisplayName("a caller-supplied operator is not subject to the built-in admission bound")
    void suppliedOperatorHasNoAdmissionBound() throws Exception {
        JobOperator operator = mock(JobOperator.class);
        JobExecution completed = mock(JobExecution.class);
        when(completed.getStatus()).thenReturn(BatchStatus.COMPLETED);
        when(operator.start(any(Job.class), any(JobParameters.class))).thenReturn(completed);
        JobSchedulingConfig config = new JobSchedulingConfig(
                operator, parkingJob(), "statements.txt", "statements.html", pathResolver());

        for (int i = 0; i < ADMITTED * 3; i++) {
            config.launchStatementGeneration();
        }

        verify(operator, times(ADMITTED * 3)).start(any(Job.class), any(JobParameters.class));
    }
}
