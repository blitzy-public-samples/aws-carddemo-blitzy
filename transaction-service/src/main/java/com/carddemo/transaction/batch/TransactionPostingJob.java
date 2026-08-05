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

import com.carddemo.common.batch.BatchOutputPathResolver;
import com.carddemo.common.batch.FailedOutputCleanupListener;
import com.carddemo.common.domain.DailyTransaction;
import com.carddemo.transaction.repository.DailyTransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.classify.Classifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import java.util.function.LongSupplier;

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

    /** SLF4J logger; every line carries the MDC correlation id via ``%X{correlationId}``. */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionPostingJob.class);

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
     *     ``dalytranId`` order, reproducing the ``CBTRN02C`` sequential browse of
     *     the daily-transaction data set. The feed is staged in the
     *     ``daily_transactions`` table before launch, so the reader is step-scoped
     *     and snapshots the staged rows at step-execution time (a job launched
     *     later therefore always reads the feed as it stands at that moment).
     * :param dailyTransactionRepository: the daily-transaction feed store whose
     *     ``findAll`` returns the staged records sorted by ``dalytranId`` ascending.
     * :returns: an {@link IteratorItemReader} over the ordered feed snapshot.
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
     * :param dailyTransactionRepository: feed repository, consulted only for the
     *     staged record count that distinguishes a genuinely empty ``DALYTRAN`` feed
     *     from a restart that has already consumed every record.
     * :param rejectFileCleanupListener: listener removing the reject generation when
     *     the step does not complete successfully.
     * :returns: the ``transactionPostingStep`` {@link Step}.
     * :note: The cleanup listener is registered FIRST and the reject-counting listener
     *     second because Spring Batch runs ``afterStep`` in REVERSE registration order
     *     (``CompositeStepExecutionListener`` iterates its composite in reverse). The
     *     cleanup must therefore be registered first so it runs LAST and observes the
     *     final status — including the ``FAILED``/``FAILED_EMPTY_FEED`` verdict the
     *     reject-counting listener sets for a genuinely empty feed, whose zero-byte
     *     reject file must not survive.
     */
    @Bean
    public Step transactionPostingStep(JobRepository jobRepository,
                                       PlatformTransactionManager transactionManager,
                                       ItemReader<DailyTransaction> dailyTransactionReader,
                                       TransactionValidationProcessor transactionValidationProcessor,
                                       ClassifierCompositeItemWriter<PostingItem> postingClassifierWriter,
                                       RejectFileItemWriter rejectFileItemWriter,
                                       DailyTransactionRepository dailyTransactionRepository,
                                       FailedOutputCleanupListener rejectFileCleanupListener) {
        RejectCountingStepListener rejectCountingStepListener =
                new RejectCountingStepListener(dailyTransactionRepository::count);
        return new StepBuilder("transactionPostingStep", jobRepository)
                .<DailyTransaction, PostingItem>chunk(1, transactionManager)
                .reader(dailyTransactionReader)
                .processor(transactionValidationProcessor)
                .writer(postingClassifierWriter)
                .stream(rejectFileItemWriter)
                .listener((StepExecutionListener) rejectFileCleanupListener)
                .listener((StepExecutionListener) rejectCountingStepListener)
                .listener((ItemWriteListener<PostingItem>) rejectCountingStepListener)
                .build();
    }

    /**
     * :purpose: Remove the ``DALYREJS`` reject generation when the posting step does not
     *     complete successfully, so a failed run leaves no reject file that a downstream
     *     reader could mistake for a completed one — the legacy job stream allocated a new
     *     GDG generation per run and an abending step left none [app/jcl/POSTTRAN.jcl].
     * :param rejectFileName: the configured reject file name, the same property the
     *     {@link RejectFileItemWriter} opens.
     * :param pathResolver: resolver confining the name to the batch output root, so the
     *     listener addresses exactly the file the writer opened.
     * :returns: the cleanup listener for ``transactionPostingStep``.
     * :note: A run that rejected records completes ``COMPLETED_WITH_REJECTS``, a qualified
     *     SUCCESS code, so its reject generation is retained.
     */
    @Bean
    public FailedOutputCleanupListener rejectFileCleanupListener(
            @Value("${carddemo.batch.reject-file:dalyrejs.txt}") String rejectFileName,
            BatchOutputPathResolver pathResolver) {
        return new FailedOutputCleanupListener(pathResolver.resolveOutput(rejectFileName));
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
     * :purpose: Tally the rejected items of the posting step, publish the count
     *     into the step execution context under
     *     {@link PostingJobCompletionListener#REJECT_COUNT_KEY} for the job
     *     completion listener to read back, and render the end-of-step verdict on
     *     an empty ``DALYTRAN`` feed.
     * :output: The running reject count is reset at step start, incremented per
     *     write, and stored on the step execution context at step end; the step
     *     exit status is returned unchanged unless the feed was empty, in which
     *     case the step is failed with
     *     {@link PostingJobCompletionListener#EMPTY_FEED_EXIT_CODE} (return code
     *     12) and Spring Batch carries that status and exit code onto the job.
     * :note: The verdict lives here, not on the job listener, so the job and step
     *     rows an operator queries always agree (QA Issue 6): failing the job while
     *     its only step stayed ``COMPLETED`` left ``BATCH_JOB_EXECUTION`` and
     *     ``BATCH_STEP_EXECUTION`` contradicting each other.
     * :note: Reading nothing is not by itself an empty feed (QA Issue 7). A
     *     restarted execution that resumes past the last consumed record legitimately
     *     reads zero rows, and reporting that as a missing feed dispatched an
     *     operator after a non-existent incident. The staged record count is
     *     therefore consulted as well, so the verdict is reached only when the feed
     *     itself holds no record.
     */
    static final class RejectCountingStepListener
            implements StepExecutionListener, ItemWriteListener<PostingItem> {

        /** Running count of rejected items observed during the step run. */
        private long rejectCount;

        /**
         * Supplies the number of records currently staged in the ``DALYTRAN`` feed,
         * used only to tell an empty feed apart from a fully consumed restart.
         */
        private final LongSupplier feedRecordCount;

        /**
         * :purpose: Construct the listener over the staged-feed record counter.
         * :param feedRecordCount: supplier of the staged ``DALYTRAN`` record count.
         */
        RejectCountingStepListener(LongSupplier feedRecordCount) {
            this.feedRecordCount = feedRecordCount;
        }

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
         *     under {@link PostingJobCompletionListener#REJECT_COUNT_KEY} and, when
         *     the ``DALYTRAN`` feed held no record at all, fail the step with the
         *     legacy return-code-12 exit status.
         * :param stepExecution: the completing step execution.
         * :returns: the return-code-12 exit status when the feed was empty,
         *     otherwise the step's existing exit status, left unchanged.
         */
        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            stepExecution.getExecutionContext()
                    .putLong(PostingJobCompletionListener.REJECT_COUNT_KEY, this.rejectCount);

            // An empty feed is an operational failure, not a clean run: the legacy job
            // step was scheduled because a DALYTRAN feed had been delivered, so a feed
            // holding no record means the input never arrived. Reporting COMPLETED in
            // that case is a silent false success - the operator believes the day's
            // transactions were posted when nothing was.
            if (stepExecution.getStatus() == BatchStatus.COMPLETED
                    && stepExecution.getReadCount() == 0
                    && feedRecordCount.getAsLong() == 0L) {
                LOG.error("Daily transaction feed was empty: no records were read from DALYTRAN");
                stepExecution.setStatus(BatchStatus.FAILED);
                return new ExitStatus(PostingJobCompletionListener.EMPTY_FEED_EXIT_CODE,
                        PostingJobCompletionListener.EMPTY_FEED_EXIT_DESCRIPTION);
            }
            return stepExecution.getExitStatus();
        }
    }
}
