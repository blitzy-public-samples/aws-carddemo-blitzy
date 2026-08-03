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

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.ItemWriteListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader;
import org.springframework.batch.infrastructure.item.support.ClassifierCompositeItemWriter;
import org.springframework.classify.Classifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * :purpose: Configure the daily transaction-posting batch job that re-platforms
 *     ``CBTRN02C`` driven by ``app/jcl/POSTTRAN.jcl``. It assembles the pipeline
 *     from the collaborators in this package: an ``ItemReader`` over the
 *     ``DALYTRAN`` feed, the {@link TransactionValidationProcessor}, a
 *     classifier-composite writer that routes each item to the
 *     {@link TransactionPostingItemWriter} or the {@link RejectFileItemWriter}, a
 *     single chunk-size-one {@link Step}, and the {@link Job} carrying the
 *     {@link PostingJobCompletionListener}.
 * :output: Registers the ``transactionPostingJob`` {@link Job} bean (and its
 *     reader, classifier writer, and step beans) launched on demand through the
 *     Spring Boot auto-configured ``JobLauncher``; the batch infrastructure
 *     ({@link JobRepository}, {@link PlatformTransactionManager}) is injected
 *     from Boot auto-configuration and never self-instantiated.
 */
@Configuration("transactionPostingJobConfig")
public class TransactionPostingJob {

    /**
     * :purpose: Sequential read of the persisted ``DALYTRAN`` feed in ascending key
     *     order, matching the ``CBTRN02C`` browse of the daily-transaction data set.
     */
    private static final String DALYTRAN_FEED_SQL =
            "SELECT dalytran_id, dalytran_type_cd, dalytran_cat_cd, dalytran_source, "
                    + "dalytran_desc, dalytran_amt, dalytran_merchant_id, dalytran_merchant_name, "
                    + "dalytran_merchant_city, dalytran_merchant_zip, dalytran_card_num, "
                    + "dalytran_orig_ts, dalytran_proc_ts "
                    + "FROM daily_transactions ORDER BY dalytran_id";

    /** :purpose: JDBC fetch size for the feed cursor, keeping memory bounded. */
    private static final int FEED_FETCH_SIZE = 100;

    /**
     * :purpose: Read the ``DALYTRAN`` feed one record at a time in ascending
     *     ``dalytran_id`` order, reproducing the ``CBTRN02C`` sequential browse of the
     *     daily-transaction data set. The reader streams the real
     *     ``daily_transactions`` table over a JDBC cursor: the job previously read a
     *     hand-written in-memory ``ConcurrentHashMap`` that nothing ever populated, so
     *     every run reported ``read=0 write=0`` and finished COMPLETED while the
     *     persisted feed sat unread - a silent false success in which no transaction was
     *     ever posted and no record was ever rejected.
     * :param dataSource: the application datasource holding the persisted feed.
     * :returns: a step-scoped {@link JdbcCursorItemReader} over the ordered feed.
     */
    @Bean
    @StepScope
    public JdbcCursorItemReader<DailyTransaction> dailyTransactionReader(DataSource dataSource) {
        JdbcCursorItemReader<DailyTransaction> reader = new JdbcCursorItemReader<>(
                dataSource, DALYTRAN_FEED_SQL, new DailyTransactionRowMapper());
        reader.setName("dailyTransactionReader");
        // Stream the feed instead of materializing it: the legacy program processed the
        // data set sequentially and the table can be arbitrarily large.
        reader.setFetchSize(FEED_FETCH_SIZE);
        return reader;
    }

    /**
     * :purpose: Route each validated item to the correct writer, reproducing the
     *     ``CBTRN02C`` post-validation branch that sends a valid record to
     *     ``2000-POST-TRANSACTION`` and a rejected record to
     *     ``2500-WRITE-REJECT-REC``.
     * :param transactionPostingItemWriter: writer that posts valid items.
     * :param rejectFileItemWriter: writer that records rejected items.
     * :returns: a {@link ClassifierCompositeItemWriter} directing rejected items to
     *     the reject writer and all other items to the posting writer.
     */
    @Bean
    public ClassifierCompositeItemWriter<PostingItem> postingClassifierWriter(
            TransactionPostingItemWriter transactionPostingItemWriter,
            RejectFileItemWriter rejectFileItemWriter) {
        ClassifierCompositeItemWriter<PostingItem> writer = new ClassifierCompositeItemWriter<>();
        writer.setClassifier((Classifier<PostingItem, ItemWriter<? super PostingItem>>) item ->
                item.isRejected() ? rejectFileItemWriter : transactionPostingItemWriter);
        return writer;
    }

    /**
     * :purpose: Define the single chunk-size-one posting step: read, validate,
     *     then write each daily-transaction record within its own chunk
     *     transaction so a record is posted and committed before the next record
     *     is validated against the running balances.
     * :param jobRepository: the Boot auto-configured Spring Batch job repository.
     * :param transactionManager: the Boot auto-configured platform transaction
     *     manager bounding each single-record chunk.
     * :param dailyTransactionReader: the step-scoped ``DALYTRAN`` feed reader.
     * :param transactionValidationProcessor: the validation processor producing a
     *     valid or rejected {@link PostingItem} for each record.
     * :param postingClassifierWriter: the classifier writer routing valid and
     *     rejected items.
     * :param rejectFileItemWriter: the reject writer, registered as a stream so its
     *     file lifecycle is managed by the step.
     * :returns: the ``transactionPostingStep`` {@link Step}.
     */
    @Bean
    public Step transactionPostingStep(JobRepository jobRepository,
                                       PlatformTransactionManager transactionManager,
                                       ItemReader<DailyTransaction> dailyTransactionReader,
                                       TransactionValidationProcessor transactionValidationProcessor,
                                       ClassifierCompositeItemWriter<PostingItem> postingClassifierWriter,
                                       RejectFileItemWriter rejectFileItemWriter) {
        RejectCountingStepListener rejectCountingStepListener = new RejectCountingStepListener();
        return new StepBuilder("transactionPostingStep", jobRepository)
                .<DailyTransaction, PostingItem>chunk(1, transactionManager)
                .reader(dailyTransactionReader)
                .processor(transactionValidationProcessor)
                .writer(postingClassifierWriter)
                .stream(rejectFileItemWriter)
                .listener((StepExecutionListener) rejectCountingStepListener)
                .listener((ItemWriteListener<PostingItem>) rejectCountingStepListener)
                .build();
    }

    /**
     * :purpose: Define the on-demand daily transaction-posting job as a single
     *     posting step, with the completion listener reporting the processed and
     *     rejected tallies and mapping the legacy return code.
     * :param jobRepository: the Boot auto-configured Spring Batch job repository.
     * :param transactionPostingStep: the single step of this job.
     * :param postingJobCompletionListener: the end-of-run tally and return-code
     *     listener.
     * :returns: the ``transactionPostingJob`` {@link Job}.
     */
    @Bean
    public Job transactionPostingJob(JobRepository jobRepository,
                                     Step transactionPostingStep,
                                     PostingJobCompletionListener postingJobCompletionListener) {
        return new JobBuilder("transactionPostingJob", jobRepository)
                .listener(postingJobCompletionListener)
                .start(transactionPostingStep)
                .build();
    }

    /**
     * :purpose: Tally the rejected items of the posting step and publish the count
     *     into the step execution context under
     *     {@link PostingJobCompletionListener#REJECT_COUNT_KEY} for the job
     *     completion listener to read back.
     * :output: The running reject count is reset at step start, incremented per
     *     write, and stored on the step execution context at step end; the step
     *     exit status is returned unchanged.
     */
    private static final class RejectCountingStepListener
            implements StepExecutionListener, ItemWriteListener<PostingItem> {

        /** Running count of rejected items observed during the step run. */
        private long rejectCount;

        /**
         * :purpose: Reset the running reject count so a re-run of the step starts
         *     from zero.
         * :param stepExecution: the starting step execution.
         */
        @Override
        public void beforeStep(StepExecution stepExecution) {
            this.rejectCount = 0L;
        }

        /**
         * :purpose: Add the rejected items of the written chunk to the running
         *     reject count.
         * :param items: the chunk of items delivered to the classifier writer.
         */
        @Override
        public void afterWrite(Chunk<? extends PostingItem> items) {
            this.rejectCount += items.getItems().stream()
                    .filter(PostingItem::isRejected)
                    .count();
        }

        /**
         * :purpose: Publish the final reject count onto the step execution context
         *     under {@link PostingJobCompletionListener#REJECT_COUNT_KEY}.
         * :param stepExecution: the completing step execution.
         * :returns: the step's existing exit status, left unchanged.
         */
        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            stepExecution.getExecutionContext()
                    .putLong(PostingJobCompletionListener.REJECT_COUNT_KEY, this.rejectCount);
            return stepExecution.getExitStatus();
        }
    }
}
