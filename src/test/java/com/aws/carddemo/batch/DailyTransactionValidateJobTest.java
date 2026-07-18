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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aws.carddemo.batch.processor.DailyTransactionValidateProcessor;
import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.repository.DailyTransactionRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot + Spring Batch Testcontainers integration test for {@link DailyTransactionValidateJob}
 * (CBTRN01C) focused on the QA finding F2 (decision log D34): the operational anomaly logs emitted by
 * {@link DailyTransactionValidateProcessor} must mask the card PAN (PCI-DSS first-6/last-4) rather than
 * emit the full 16-digit number.
 *
 * <p>The test stages a single daily-transaction row whose card number has <strong>no</strong> card
 * cross-reference (the {@code daily_transaction} staging table carries no foreign keys), which drives
 * the {@code WARN "Card number {} could not be verified"} branch. A Logback {@link ListAppender} is
 * attached to the processor's logger to capture the emitted events, and the test asserts:</p>
 * <ul>
 *   <li>the masked form {@code 999999******9999} appears in a captured log event;</li>
 *   <li>the full PAN {@code 9999999999999999} appears in <em>no</em> captured log event;</li>
 *   <li>the job still completes with {@link ExitStatus#COMPLETED} (RC&nbsp;0), preserving the
 *       read-only, anomalies-are-non-fatal CBTRN01C contract; and</li>
 *   <li>the staged row is left unchanged (the validate job persists nothing).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class DailyTransactionValidateJobTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    private static final AtomicLong RUN_ID = new AtomicLong();

    /** A 16-digit card number with no cross-reference (drives the unverified-card WARN branch). */
    private static final String UNVERIFIED_PAN = "9999999999999999";

    /** The expected PCI-DSS masked rendering of {@link #UNVERIFIED_PAN}. */
    private static final String MASKED_PAN = "999999******9999";

    /** The staged transaction id (16 characters, matching the {@code dalytran_id} column width). */
    private static final String DALYTRAN_ID = "0000000000000001";

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    private Logger processorLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        dailyTransactionRepository.deleteAll();
        dailyTransactionRepository.save(new DailyTransaction(
                DALYTRAN_ID, "01", 1, "POS TERM", "Unverified card purchase",
                new BigDecimal("10.00"), 800000000L, "Test Merchant", "Test City", "75010",
                UNVERIFIED_PAN, "2022-06-10 19:27:53.000000", ""));

        // Capture the processor's log output at WARN (the unverified-card branch logs at WARN).
        processorLogger = (Logger) LoggerFactory.getLogger(DailyTransactionValidateProcessor.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        processorLogger.addAppender(logAppender);
        processorLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        if (processorLogger != null && logAppender != null) {
            processorLogger.detachAppender(logAppender);
            logAppender.stop();
        }
        dailyTransactionRepository.deleteAll();
    }

    @Test
    void masksPanInAnomalyLogsAndPreservesReadOnlyRc0() throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addLong("run.id", RUN_ID.incrementAndGet())
                .toJobParameters();

        JobExecution execution = jobLauncherTestUtils.launchJob(parameters);

        // CBTRN01C contract: anomalies are non-fatal; the job completes with RC 0.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());

        List<String> messages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // The masked PAN is present; the full PAN is absent from every captured log line.
        assertThat(messages)
                .anySatisfy(m -> assertThat(m).contains(MASKED_PAN));
        assertThat(messages)
                .allSatisfy(m -> assertThat(m).doesNotContain(UNVERIFIED_PAN));

        // Read-only: the staged row is untouched (the validate job persists nothing).
        List<DailyTransaction> staged = dailyTransactionRepository.findAll();
        assertThat(staged).hasSize(1);
        assertThat(staged.get(0).getCardNum()).isEqualTo(UNVERIFIED_PAN);
    }

    /** Supplies a {@link JobLauncherTestUtils} bound to {@code dailyTransactionValidateJob}. */
    @TestConfiguration
    static class TestBatchSupportConfig {

        /**
         * Builds a {@link JobLauncherTestUtils} for {@code dailyTransactionValidateJob}.
         *
         * @param job           the validate job, selected by qualifier
         * @param jobLauncher   the auto-configured Spring Batch job launcher
         * @param jobRepository the auto-configured Spring Batch job repository
         * @return the configured launcher utility
         */
        @Bean
        JobLauncherTestUtils jobLauncherTestUtils(@Qualifier("dailyTransactionValidateJob") Job job,
                                                  JobLauncher jobLauncher,
                                                  JobRepository jobRepository) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJob(job);
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            return utils;
        }
    }
}
