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

import com.carddemo.batch.batch.InterestItemProcessor;
import com.carddemo.batch.batch.InterestPostingItem;
import com.carddemo.batch.batch.InterestTransactionWriter;
import com.carddemo.batch.repository.TranCatBalRepository;
import com.carddemo.common.config.CorrelationIdContext;
import com.carddemo.common.domain.TranCatBal;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.data.RepositoryItemReader;
import org.springframework.batch.infrastructure.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.batch.infrastructure.item.support.SingleItemPeekableItemReader;
import org.springframework.batch.infrastructure.repeat.RepeatContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.infrastructure.repeat.context.RepeatContextSupport;
import org.springframework.batch.infrastructure.repeat.policy.CompletionPolicySupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Batch configuration for the monthly interest-calculation job.
 *
 * :purpose: Wire the interest-calculation ``Job`` and its single per-account
 *     control-break chunk ``Step``, re-platforming the legacy JCL job stream
 *     ``app/jcl/INTCALC.jcl`` (which runs ``PGM=CBACT04C`` with
 *     ``PARM='2022071800'``) and the COBOL program ``app/cbl/CBACT04C.cbl``. The
 *     step reads transaction-category-balance rows in
 *     ``(trancatAcctId, trancatTypeCd, trancatCd)`` ascending order and sizes
 *     each chunk to exactly one account's rows so the per-account cycle roll-up
 *     runs once per account inside a single transaction. This class only
 *     assembles beans from the reader built here, the ``@StepScope``
 *     {@link InterestItemProcessor}, and the {@link InterestTransactionWriter} in
 *     ``com.carddemo.batch.batch``; the monthly-interest arithmetic
 *     ``(TRAN-CAT-BAL * DIS-INT-RATE) / 1200`` at scale 2 with ``HALF_UP``, the
 *     disclosure-group ``DEFAULT`` fallback, the one-interest-transaction-per
 *     non-zero-rate-category emission, and the per-account balance update that
 *     adds accumulated interest to ``ACCT-CURR-BAL`` and zeroes
 *     ``ACCT-CURR-CYC-CREDIT`` / ``ACCT-CURR-CYC-DEBIT`` all live in those
 *     components and the service layer; the legacy ``1400-COMPUTE-FEES`` stub is
 *     a no-op and is not implemented.
 * :output: The ``interestTranCatBalReader``, ``interestCalculationStep``, and
 *     ``interestCalculationJob`` beans. ``JobRepository`` and the batch
 *     ``PlatformTransactionManager`` are supplied by Spring Boot batch
 *     auto-configuration and taken as ``@Bean`` method parameters; no batch
 *     infrastructure is self-instantiated and ``@EnableBatchProcessing`` is
 *     intentionally absent so the Boot auto-configuration stays active. The step
 *     runs single-threaded with no skip or retry so the writer's global,
 *     monotonic interest transaction-id suffix stays contiguous.
 */
@Configuration
public class InterestCalculationJobConfig {

    /**
     * :purpose: Database fetch size of the paging delegate reader; bounds each
     *     page read against the ``tran_cat_bal`` table and is independent of the
     *     per-account chunk (transaction) sizing.
     */
    private static final int READER_PAGE_SIZE = 100;

    /**
     * :purpose: Provide the driving reader for the interest step: a paging reader
     *     over transaction-category balances in account/type/category order,
     *     wrapped so the control-break completion policy can peek the next row
     *     without consuming it. The delegate uses the inherited
     *     ``JpaRepository.findAll(Pageable)`` with an explicit multi-key sort so
     *     ordering is defined solely by the sort map; all category-balance rows
     *     for one account therefore arrive contiguously for the per-account
     *     control break, reproducing the ``CBACT04C`` sequential read of
     *     ``TCATBALF`` in ``TRANCAT-ACCT-ID`` / ``TRANCAT-TYPE-CD`` /
     *     ``TRANCAT-CD`` key order.
     * :param tranCatBalRepository: paging repository over the
     *     ``tran_cat_bal`` table supplying category-balance rows.
     * :returns: a ``SingleItemPeekableItemReader`` over {@link TranCatBal} rows in
     *     ascending ``(trancatAcctId, trancatTypeCd, trancatCd)`` order.
     */
    @Bean
    public SingleItemPeekableItemReader<TranCatBal> interestTranCatBalReader(
            TranCatBalRepository tranCatBalRepository) {
        // LinkedHashMap preserves the exact multi-key ORDER BY sequence
        // (account, then type, then category); RepositoryItemReader builds the
        // Sort by iterating this map's entries in insertion order.
        Map<String, Sort.Direction> sorts = new LinkedHashMap<>();
        sorts.put("trancatAcctId", Sort.Direction.ASC);
        sorts.put("trancatTypeCd", Sort.Direction.ASC);
        sorts.put("trancatCd", Sort.Direction.ASC);

        RepositoryItemReader<TranCatBal> delegate = new RepositoryItemReaderBuilder<TranCatBal>()
                .name("interestTranCatBalDelegateReader")
                .repository(tranCatBalRepository)
                .methodName("findAll")
                .sorts(sorts)
                .pageSize(READER_PAGE_SIZE)
                .build();

        // The peekable wrapper is an ItemStreamReader that delegates open/update/
        // close to the RepositoryItemReader, so registering only this reader on
        // the step opens the delegate exactly once.
        return new SingleItemPeekableItemReader<>(delegate);
    }

    /**
     * :purpose: Build the ``JobExecutionListener`` that binds the incoming
     *     ``correlationId`` job parameter to the logging MDC for the duration of a
     *     job run, so every log line emitted by the job's step, reader, processor,
     *     and writer carries a single correlation id even though the async
     *     ``JobLauncher`` runs the job on a different thread than the launcher; a
     *     job launched without a correlation id is assigned a generated one.
     * :returns: a ``JobExecutionListener`` whose ``beforeJob`` seeds the
     *     correlation id from the ``correlationId`` job parameter (or generates one
     *     when the parameter is absent or blank) and whose ``afterJob`` clears it,
     *     using only the static {@link CorrelationIdContext} helpers.
     */
    private JobExecutionListener correlationIdJobListener() {
        return new JobExecutionListener() {
            @Override
            public void beforeJob(JobExecution jobExecution) {
                String correlationId = jobExecution.getJobParameters()
                        .getString(CorrelationIdContext.CORRELATION_ID_KEY);
                if (correlationId != null && !correlationId.isBlank()) {
                    CorrelationIdContext.setCorrelationId(correlationId);
                } else {
                    CorrelationIdContext.getOrCreateCorrelationId();
                }
            }

            @Override
            public void afterJob(JobExecution jobExecution) {
                CorrelationIdContext.clear();
            }
        };
    }

    /**
     * :purpose: Assemble the interest-calculation chunk step. The chunk size is
     *     governed by the per-account control-break completion policy rather than
     *     a fixed integer, so all {@link TranCatBal} rows for one account form
     *     exactly one chunk (one transaction); within that transaction the writer
     *     persists one interest transaction per non-zero-rate category and applies
     *     the account cycle roll-up once, mirroring ``CBACT04C``. The step is
     *     single-threaded with no skip or retry.
     * :param jobRepository: batch job repository (Spring Boot auto-configured).
     * :param transactionManager: batch transaction manager (Spring Boot
     *     auto-configured); defines the per-account chunk transaction boundary.
     * :param interestTranCatBalReader: account-ordered peekable reader whose next
     *     row the completion policy peeks to detect the account control break.
     * :param interestItemProcessor: ``@StepScope`` processor that resolves the
     *     disclosure rate and computes per-row monthly interest.
     * :param interestTransactionWriter: writer that persists interest
     *     transactions and performs the once-per-account balance roll-up.
     * :returns: the ``interestCalculationStep`` ``Step``.
     */
    @Bean
    @SuppressWarnings({"deprecation", "removal"})
    public Step interestCalculationStep(JobRepository jobRepository,
                                        PlatformTransactionManager transactionManager,
                                        SingleItemPeekableItemReader<TranCatBal> interestTranCatBalReader,
                                        InterestItemProcessor interestItemProcessor,
                                        InterestTransactionWriter interestTransactionWriter) {
        PerAccountCompletionPolicy completionPolicy =
                new PerAccountCompletionPolicy(interestTranCatBalReader);
        return new StepBuilder("interestCalculationStep", jobRepository)
                .<TranCatBal, InterestPostingItem>chunk(completionPolicy, transactionManager)
                .reader(interestTranCatBalReader)
                .processor(interestItemProcessor)
                .writer(interestTransactionWriter)
                .build();
    }

    /**
     * :purpose: Assemble the interest-calculation job that runs the single
     *     per-account control-break step, attaching the correlation-id listener
     *     for structured, traceable logging across the async launch boundary.
     * :param jobRepository: batch job repository (Spring Boot auto-configured).
     * :param interestCalculationStep: the single step of this job.
     * :returns: the ``interestCalculationJob`` ``Job``.
     * :note: Required job parameters, supplied at launch by the scheduling
     *     configuration (not defaulted here): ``parmDate`` — a 10-character
     *     ``YYYYMMDDHH`` business date (legacy ``INTCALC.jcl`` ``PARM='2022071800'``)
     *     consumed by the processor and writer to build each interest ``TRAN-ID``
     *     as ``parmDate`` (10) followed by a zero-padded 6-digit global suffix; and
     *     ``correlationId`` — a non-identifying parameter bound into the logging
     *     MDC for the job run.
     */
    @Bean
    public Job interestCalculationJob(JobRepository jobRepository, Step interestCalculationStep) {
        return new JobBuilder("interestCalculationJob", jobRepository)
                .listener(correlationIdJobListener())
                .start(interestCalculationStep)
                .build();
    }

    /**
     * Per-account control-break chunk-completion policy.
     *
     * :purpose: Close the current chunk when the account identifier of the next
     *     (peeked, still-unconsumed) reader row differs from the account
     *     identifier captured when the chunk started, or at end of data, so each
     *     chunk contains exactly one account's contiguous rows. Because the
     *     driving reader is sorted by account, this yields one transaction per
     *     account, reproducing the ``CBACT04C`` control break on
     *     ``TRANCAT-ACCT-ID``.
     * :output: A completion decision for each chunk; the account identifier of the
     *     chunk is held in an {@link AccountChunkContext}.
     */
    static final class PerAccountCompletionPolicy extends CompletionPolicySupport {

        /** Peekable driving reader whose next row is inspected without consuming it. */
        private final SingleItemPeekableItemReader<TranCatBal> reader;

        /**
         * :purpose: Construct the policy over the step's peekable reader.
         * :param reader: the same peekable reader instance the step reads from, so
         *     the policy peeks the identical row stream.
         */
        PerAccountCompletionPolicy(SingleItemPeekableItemReader<TranCatBal> reader) {
            this.reader = reader;
        }

        /**
         * :purpose: Begin a chunk by capturing the account identifier of the first
         *     row the chunk will read (peeked before the first read).
         * :param parent: the enclosing repeat context.
         * :returns: an {@link AccountChunkContext} carrying the chunk's account
         *     identifier, or a ``null`` account identifier at end of data.
         */
        @Override
        public RepeatContext start(RepeatContext parent) {
            TranCatBal firstOfChunk = peekNextRow();
            Long chunkAccountId = (firstOfChunk == null) ? null : firstOfChunk.getTrancatAcctId();
            return new AccountChunkContext(parent, chunkAccountId);
        }

        /**
         * :purpose: Decide whether the current chunk is complete by peeking the
         *     next unconsumed row and comparing its account identifier to the
         *     chunk's captured account identifier.
         * :param context: the {@link AccountChunkContext} created by
         *     {@link #start(RepeatContext)}.
         * :returns: ``true`` when there are no rows for the chunk, at end of data,
         *     or when the next row belongs to a different account; ``false`` while
         *     the next row belongs to the same account.
         */
        @Override
        public boolean isComplete(RepeatContext context) {
            AccountChunkContext ctx = (AccountChunkContext) context;
            if (ctx.chunkAccountId == null) {
                return true;
            }
            TranCatBal next = peekNextRow();
            if (next == null) {
                return true;
            }
            return !ctx.chunkAccountId.equals(next.getTrancatAcctId());
        }

        /**
         * :purpose: Decide chunk completion taking the last iteration result into
         *     account, closing the chunk when the reader has signalled it can no
         *     longer continue and otherwise deferring to the account-change check.
         * :param context: the {@link AccountChunkContext} for the chunk.
         * :param result: the status of the most recent read iteration.
         * :returns: ``true`` when the result is non-continuable or the account has
         *     changed / data is exhausted; ``false`` otherwise.
         */
        @Override
        public boolean isComplete(RepeatContext context, RepeatStatus result) {
            if (result != null && !result.isContinuable()) {
                return true;
            }
            return isComplete(context);
        }

        /**
         * :purpose: Peek the next unconsumed reader row, translating the reader's
         *     checked exception into an unchecked one so it can be raised from the
         *     completion-policy methods, which declare no checked exceptions.
         * :returns: the next {@link TranCatBal} row, or ``null`` at end of data.
         */
        private TranCatBal peekNextRow() {
            try {
                return this.reader.peek();
            } catch (Exception peekFailure) {
                throw new IllegalStateException(
                        "Unable to peek the transaction-category-balance reader while evaluating the "
                                + "per-account control break", peekFailure);
            }
        }

        /**
         * Repeat context that remembers the account identifier of the chunk it
         * describes.
         *
         * :purpose: Carry the account identifier captured at chunk start so the
         *     completion check can compare it against the next peeked row.
         * :output: An immutable per-chunk account identifier, or ``null`` when the
         *     chunk started at end of data.
         */
        static final class AccountChunkContext extends RepeatContextSupport {

            /** Account identifier of the chunk, captured at chunk start; may be ``null`` at end of data. */
            private final Long chunkAccountId;

            /**
             * :purpose: Construct the per-chunk context.
             * :param parent: the enclosing repeat context.
             * :param chunkAccountId: the account identifier of the chunk's first
             *     row, or ``null`` at end of data.
             */
            AccountChunkContext(RepeatContext parent, Long chunkAccountId) {
                super(parent);
                this.chunkAccountId = chunkAccountId;
            }
        }
    }
}
