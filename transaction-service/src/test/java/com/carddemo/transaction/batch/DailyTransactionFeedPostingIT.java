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

import com.carddemo.common.domain.DailyTransaction;
import com.carddemo.transaction.repository.DailyTransactionRepository;
import com.carddemo.transaction.repository.TransactionRepository;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Testcontainers integration test proving the staged ``DALYTRAN`` feed is
 *  reachable end to end: the rows loaded by the shared seed migration into
 *  ``daily_transactions`` are read back through the Spring Data JPA
 *  {@link DailyTransactionRepository} in ascending ``DALYTRAN-ID`` order and are
 *  consumed by the ``CBTRN02C`` posting job (``transactionPostingJob``, driven by
 *  ``app/jcl/POSTTRAN.jcl``) through its step-scoped reader. The context boots on
 *  the production settings - Hibernate ``ddl-auto: validate`` over the consolidated
 *  migration set in ``carddemo-common`` - so the entity mapping is validated against
 *  the real table as well.
 * :output: JUnit 5 / AssertJ assertions over the repository read order and over the
 *  launched job: the step reads every staged record, each record is either posted or
 *  rejected (reject codes 100-103), the posted rows land in ``transactions``, and the
 *  job completes.
 */
@SpringBootTest
@ActiveProfiles("test")
// Keep the DALYREJS reject sink inside the build directory instead of the module root.
@TestPropertySource(properties = "carddemo.batch.reject-file=target/dalyrejs-posting-it.txt")
class DailyTransactionFeedPostingIT {

    /** Number of ``DALYTRAN`` records loaded by the seed migration from app/data/ASCII/dailytran.txt. */
    private static final int SEEDED_FEED_ROWS = 300;

    /** :purpose: Repository under test - the JPA re-platforming of the sequential DALYTRAN read. */
    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    /** :purpose: Posted-transaction repository, used to observe what the posting writer wrote. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** :purpose: The on-demand posting job (never launched at startup). */
    @Autowired
    @Qualifier("transactionPostingJob")
    private Job transactionPostingJob;

    /** :purpose: Batch metadata repository backing the synchronous launcher built below. */
    @Autowired
    private JobRepository jobRepository;

    /**
     * :purpose: Verify the seeded feed is loadable from the database through the JPA
     *  repository and is returned in the legacy sequential browse order (ascending
     *  ``DALYTRAN-ID``), which is the order the posting reader consumes.
     */
    @Test
    void seededFeedIsReadableThroughJpaRepositoryInAscendingIdOrder() {
        assertThat(dailyTransactionRepository.count())
                .as("the seed migration stages the whole DALYTRAN feed")
                .isEqualTo(SEEDED_FEED_ROWS);

        List<DailyTransaction> feed = dailyTransactionRepository.findAll();
        assertThat(feed).hasSize(SEEDED_FEED_ROWS);
        assertThat(feed)
                .extracting(DailyTransaction::getDalytranId)
                .isSorted();

        DailyTransaction first = feed.get(0);
        assertThat(first.getDalytranId()).isNotBlank();
        assertThat(first.getDalytranCardNum()).isNotBlank();
        assertThat(first.getDalytranAmt())
                .as("DALYTRAN-AMT is materialized as an exact NUMERIC(11,2) value")
                .isNotNull();
        assertThat(first.getDalytranAmt().scale()).isEqualTo(2);
        assertThat(first.getDalytranOrigTs())
                .as("DALYTRAN-ORIG-TS keeps its full 26-character width")
                .hasSize(26);

        assertThat(dailyTransactionRepository.findById(first.getDalytranId()))
                .as("keyed read of a staged feed record")
                .isPresent();
    }

    /**
     * :purpose: Verify the posting job consumes the staged feed from the database:
     *  every staged record is read, classified as posted or rejected, and the posted
     *  records are written to ``transactions``.
     * :note: The seed also loads the posted image of the feed, so the already-posted
     *  history is cleared first - the legacy job likewise posts a feed exactly once.
     */
    @Test
    void postingJobReadsTheStagedFeedFromTheDatabase() throws Exception {
        transactionRepository.deleteAllInBatch();
        assertThat(transactionRepository.count()).isZero();

        JobParameters parameters = new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .toJobParameters();

        JobExecution execution = synchronousJobOperator().start(transactionPostingJob, parameters);

        assertThat(execution.getStatus().isUnsuccessful())
                .as("posting run must not fail: %s", execution.getAllFailureExceptions())
                .isFalse();

        StepExecution step = execution.getStepExecutions().iterator().next();
        assertThat(step.getReadCount())
                .as("the reader must consume every staged DALYTRAN row from the database")
                .isEqualTo(SEEDED_FEED_ROWS);
        assertThat(step.getWriteCount() + step.getFilterCount())
                .as("every read record is either posted or rejected")
                .isEqualTo(SEEDED_FEED_ROWS);

        assertThat(transactionRepository.count())
                .as("posted records are written to the transactions table")
                .isPositive();
        assertThat(dailyTransactionRepository.count())
                .as("the staged feed itself is retained, exactly as the legacy input data set is")
                .isEqualTo(SEEDED_FEED_ROWS);
    }

    /**
     * :purpose: Build a synchronous {@link JobOperator} over the context
     *  {@link JobRepository}, backed by a {@link SyncTaskExecutor} so a launched job
     *  runs on the calling thread and the launch call blocks until completion, keeping
     *  the assertions race-free.
     * :returns: an initialized synchronous {@link JobOperator}.
     */
    private JobOperator synchronousJobOperator() throws Exception {
        TaskExecutorJobOperator operator = new TaskExecutorJobOperator();
        operator.setJobRepository(jobRepository);
        operator.setTaskExecutor(new SyncTaskExecutor());
        MapJobRegistry jobRegistry = new MapJobRegistry();
        jobRegistry.register(transactionPostingJob);
        operator.setJobRegistry(jobRegistry);
        operator.afterPropertiesSet();
        return operator;
    }
}
