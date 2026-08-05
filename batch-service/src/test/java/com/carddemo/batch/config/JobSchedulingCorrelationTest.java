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
package com.carddemo.batch.config;

import java.nio.file.Path;
import com.carddemo.common.batch.BatchOutputPathResolver;
import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.config.CorrelationIdContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.MDC;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.repository.JobRepository;

/**
 * :purpose: Tests the correlation-id hygiene of the batch launch path. A launch obtains a
 *     correlation id for the job, but the launching thread is a pooled container thread:
 *     if the launcher seeds that thread's MDC and never clears it, the id leaks into the
 *     next unrelated request handled by the same thread and mislabels its log lines.
 * :note: The launcher's collaborators are mocked, so no Spring context, database or job
 *     execution is required; only the MDC contract of the launching thread is asserted.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JobSchedulingConfig correlation-id hygiene")
class JobSchedulingCorrelationTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private Job job;

    /**
     * :purpose: Start every case from a clean MDC so none inherits an id.
     */
    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    /**
     * :purpose: Leave no id behind for the next case or a pooled thread.
     */
    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    /**
     * :purpose: Build the launcher under test with every job slot bound to the same mock;
     *     the identity of the individual jobs is irrelevant to the MDC contract.
     * :returns: a :class:`JobSchedulingConfig` whose launches operate on mocks.
     */
    private JobSchedulingConfig newConfig() {
        // A real resolver rooted at the JVM temporary directory: the launcher resolves each
        // file parameter before accepting a submission, and the default report names used
        // below resolve inside that root, so the MDC contract is observed on the same path a
        // deployment takes rather than on a stubbed one.
        BatchOutputPathResolver resolver = new BatchOutputPathResolver(
                Path.of(System.getProperty("java.io.tmpdir"), "carddemo-batch-mdc", "output")
                        .toString(),
                Path.of(System.getProperty("java.io.tmpdir"), "carddemo-batch-mdc", "input")
                        .toString());
        return new JobSchedulingConfig(jobRepository, job, job, job, job, job, job, job, job, job,
                resolver,
                JobSchedulingConfig.DEFAULT_ACCOUNT_REPORT_FILE,
                JobSchedulingConfig.DEFAULT_CARD_REPORT_FILE,
                JobSchedulingConfig.DEFAULT_CARD_XREF_REPORT_FILE,
                JobSchedulingConfig.DEFAULT_CUSTOMER_REPORT_FILE,
                JobSchedulingConfig.DEFAULT_CATEGORY_BALANCE_REPORT_FILE,
                JobSchedulingConfig.DEFAULT_TRANSACTION_DETAIL_REPORT_FILE,
                JobSchedulingConfig.DEFAULT_COMBINED_TRANSACTION_FILE,
                JobSchedulingConfig.DEFAULT_DAILY_TRANSACTION_FEED_FILE);
    }

    /**
     * :purpose: When no correlation id is in scope, the launcher seeds one for the job but
     *     must clear it from the launching thread afterwards, so a pooled container thread
     *     never carries a batch launch's id into a later, unrelated request.
     */
    @Test
    @DisplayName("a self-seeded correlation id is cleared from the launching thread")
    void selfSeededCorrelationIdIsClearedFromLaunchingThread() {
        JobSchedulingConfig config = newConfig();
        assertThat(CorrelationIdContext.getCorrelationId()).isNull();

        // The mocked job never runs, so the launch may fail; the MDC contract must hold
        // regardless of the launch outcome, which is exactly why the reset is in a
        // finally block.
        try {
            config.launchAccountRead();
        } catch (Exception expectedFromMocks) {
            // The launch outcome is not under test here.
        }

        assertThat(CorrelationIdContext.getCorrelationId())
                .as("a self-seeded correlation id must not remain on the launching thread")
                .isNull();
    }

    /**
     * :purpose: A correlation id already in scope belongs to the request's
     *     ``CorrelationIdFilter``, which clears it when the request completes. The launcher
     *     must leave it untouched so the remainder of the request keeps logging under it.
     */
    @Test
    @DisplayName("a caller-owned correlation id survives the launch")
    void callerOwnedCorrelationIdSurvivesLaunch() {
        JobSchedulingConfig config = newConfig();
        CorrelationIdContext.setCorrelationId("request-owned-id");

        try {
            config.launchAccountRead();
        } catch (Exception expectedFromMocks) {
            // The launch outcome is not under test here.
        }

        assertThat(CorrelationIdContext.getCorrelationId())
                .as("the request's own correlation id must be preserved for the rest of the request")
                .isEqualTo("request-owned-id");
    }
}
