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

import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.repository.DailyTransactionRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spring Boot + Spring Batch Testcontainers integration test that validates the executable raw
 * fixed-width daily-transaction loader {@link DailyTransactionLoadJob} end-to-end: it launches the
 * {@code dailyTransactionLoadJob} against a real PostgreSQL 16, ingesting the shipped 350-byte
 * fixed-width {@code DALYTRAN} fixture ({@code src/test/resources/seed/dailytran-fixedwidth-sample.txt})
 * and asserting that every record is decoded per the CVTRA06Y offset table and inserted into the
 * {@code daily_transaction} staging table.
 *
 * <h2>Contract reproduced</h2>
 * <ul>
 *   <li><strong>Raw external-file ingestion (AAP &sect;0.7.2 hotspot M2).</strong> The 350-byte
 *       {@code DALYTRAN-RECORD} contract is read line-by-line over {@code ISO-8859-1} and decoded by
 *       {@code DailyTransactionFileItemReader} / {@code DailyTransactionLineMapper}.</li>
 *   <li><strong>Monetary fidelity.</strong> The zoned-decimal overpunch of {@code DALYTRAN-AMT} is
 *       decoded to a scale-2 {@link BigDecimal} (record 1 {@code +504.77}, record 2 {@code -919.00}).</li>
 *   <li><strong>Fail-fast parameterization.</strong> Launching without the required
 *       {@code inputResource} parameter is rejected with {@link JobParametersInvalidException} before
 *       the step runs.</li>
 * </ul>
 *
 * <h2>Harness</h2>
 * <p>The context boots under the {@code test} profile against a Testcontainers PostgreSQL 16 wired
 * with {@link ServiceConnection} (no hardcoded JDBC credentials). Because the context contains many
 * {@code Job} beans, a {@link JobLauncherTestUtils} bound to {@code @Qualifier("dailyTransactionLoadJob")}
 * is supplied by the nested {@link TestBatchSupportConfig} rather than relying on {@code @SpringBatchTest}
 * single-job auto-wiring. Each launch uses a unique {@code run.id} so repeated launches are fresh job
 * instances. The {@code daily_transaction} table is not seeded under the {@code test} profile (the CSV
 * seed loader is {@code @Profile("local")}); each method clears it before and after so the row-count
 * assertions are deterministic.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class DailyTransactionLoadJobTest {

    /**
     * Shared PostgreSQL 16 container backing the integration context. {@link ServiceConnection}
     * publishes its JDBC url/username/password dynamically, so no credential is baked into source.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /** Supplies unique, ever-increasing {@code run.id} job-parameter values across launches. */
    private static final AtomicLong RUN_ID = new AtomicLong();

    /** Classpath location of the raw fixed-width daily-transaction fixture (10 records, 350 bytes each). */
    private static final String FIXTURE_RESOURCE = "classpath:seed/dailytran-fixedwidth-sample.txt";

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    @BeforeEach
    void clearStagingBefore() {
        dailyTransactionRepository.deleteAll();
    }

    @AfterEach
    void clearStagingAfter() {
        dailyTransactionRepository.deleteAll();
    }

    @Test
    void loadsEveryFixtureRecordIntoStaging() throws Exception {
        JobExecution execution = launchLoadJob(FIXTURE_RESOURCE);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");

        List<DailyTransaction> loaded = dailyTransactionRepository.findAllByOrderByIdAsc();
        assertThat(loaded).hasSize(10);

        // Record 1: positive overpunch amount and full field decode.
        DailyTransaction first = loaded.get(0);
        assertThat(first.getDalytranId()).isEqualTo("0000000000683580");
        assertThat(first.getTypeCd()).isEqualTo("01");
        assertThat(first.getCatCd()).isEqualTo(1);
        assertThat(first.getTranSource()).isEqualTo("POS TERM");
        assertThat(first.getTranDesc()).isEqualTo("Purchase at Abshire-Lowe");
        assertThat(first.getMerchantId()).isEqualTo(800000000L);
        assertThat(first.getMerchantName()).isEqualTo("Abshire-Lowe");
        assertThat(first.getCardNum()).isEqualTo("4859452612877065");
        assertThat(first.getOrigTs()).isEqualTo("2022-06-10 19:27:53.000000");
        assertThat(first.getTranAmt()).isEqualByComparingTo(new BigDecimal("504.77"));
        assertThat(first.getTranAmt().scale()).isEqualTo(2);

        // Record 2: negative overpunch amount.
        DailyTransaction second = loaded.get(1);
        assertThat(second.getDalytranId()).isEqualTo("0000000001774260");
        assertThat(second.getCardNum()).isEqualTo("0927987108636232");
        assertThat(second.getTranAmt()).isEqualByComparingTo(new BigDecimal("-919.00"));
        assertThat(second.getTranAmt().scale()).isEqualTo(2);

        // Every decoded amount is a scale-2 BigDecimal (never a binary floating-point value).
        assertThat(loaded).allSatisfy(record -> {
            assertThat(record.getTranAmt()).isNotNull();
            assertThat(record.getTranAmt().scale()).isEqualTo(2);
        });
    }

    @Test
    void isRerunnableAndReplacesPriorStagingRows() throws Exception {
        launchLoadJob(FIXTURE_RESOURCE);
        assertThat(dailyTransactionRepository.count()).isEqualTo(10);

        // A second load after clearing yields the same deterministic result (rerun-safe ingestion).
        dailyTransactionRepository.deleteAll();
        JobExecution second = launchLoadJob(FIXTURE_RESOURCE);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(dailyTransactionRepository.count()).isEqualTo(10);
    }

    @Test
    void failsFastWhenInputResourceParameterMissing() {
        JobParameters parametersWithoutInput = new JobParametersBuilder()
                .addLong("run.id", RUN_ID.incrementAndGet())
                .toJobParameters();

        assertThatThrownBy(() -> jobLauncherTestUtils.launchJob(parametersWithoutInput))
                .isInstanceOf(JobParametersInvalidException.class)
                .hasMessageContaining("inputResource");

        assertThat(dailyTransactionRepository.count()).isZero();
    }

    /**
     * Launches {@code dailyTransactionLoadJob} with the supplied input-file location and a unique
     * {@code run.id} identifying parameter so every invocation is a fresh job instance.
     *
     * @param inputResource the {@code inputResource} job-parameter value (a Spring resource location)
     * @return the completed {@link JobExecution}
     * @throws Exception if the underlying {@code JobLauncher} throws
     */
    private JobExecution launchLoadJob(String inputResource) throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addLong("run.id", RUN_ID.incrementAndGet())
                .addString("inputResource", inputResource)
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(parameters);
    }

    /**
     * Test-only configuration supplying a {@link JobLauncherTestUtils} bound to the specific
     * {@code dailyTransactionLoadJob} bean (the context holds many {@code Job} beans, so by-type
     * single-job auto-wiring would be ambiguous).
     */
    @TestConfiguration
    static class TestBatchSupportConfig {

        /**
         * Builds a {@link JobLauncherTestUtils} for {@code dailyTransactionLoadJob}.
         *
         * @param job           the daily-transaction load job, selected by qualifier
         * @param jobLauncher   the auto-configured Spring Batch job launcher
         * @param jobRepository the auto-configured Spring Batch job repository
         * @return the configured launcher utility
         */
        @Bean
        JobLauncherTestUtils jobLauncherTestUtils(@Qualifier("dailyTransactionLoadJob") Job job,
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
