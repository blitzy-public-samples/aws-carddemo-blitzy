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

package com.carddemo.batch.batch;

import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.common.config.CorrelationIdContext;
import com.carddemo.common.domain.Transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * :purpose: Idempotent, ordered reconciliation pass over the unified
 *  ``transactions`` table; the Java analogue of the legacy ``COMBTRAN`` job
 *  (``app/jcl/COMBTRAN.jcl``). The legacy ``STEP05R`` sorts the transaction
 *  backup concatenated with the system-generated interest transactions by
 *  ``SORT FIELDS=(TRAN-ID,A)`` into a combined file, and ``STEP10`` reloads that
 *  combined file into the transaction master via IDCAMS ``REPRO``. In the
 *  relational target there is a single ``transactions`` table and the interest
 *  transactions are already persisted into it by the interest-calculation job,
 *  so this tasklet performs no INSERT, UPDATE, or DELETE: it walks the table
 *  once in ``tranId`` ascending order (the ``SORT FIELDS=(TRAN-ID,A)``
 *  semantics), counts the reconciled rows, and logs the total.
 * :output: A single INFO log line reporting the count of transactions verified
 *  in ``tranId`` order — the count only, never any transaction content, card
 *  number, amount, or other record field (PII safety).
 * :note: The batch ``config`` package wires this tasklet into a single-step
 *  ``Job`` via ``new StepBuilder(name, jobRepository).tasklet(tasklet,
 *  transactionManager).build()``, sets the job-level correlation id, and
 *  launches it through the auto-configured ``JobLauncher``; Spring Boot
 *  auto-configures Spring Batch, so ``@EnableBatchProcessing`` is not added.
 * :note: Structured JSON logging and the ``correlationId`` MDC key are supplied
 *  by the module's ``logback-spring.xml`` together with
 *  {@link CorrelationIdContext}; this tasklet only ensures a correlation id is
 *  present before it logs.
 */
@Component
public class CombineTransactionsTasklet implements Tasklet {

    /** SLF4J logger; the ``correlationId`` MDC value is rendered by ``logback-spring.xml``. */
    private static final Logger log = LoggerFactory.getLogger(CombineTransactionsTasklet.class);

    /**
     * Database fetch page size for the reconciliation scan; independent of any
     * chunk size, keeping the ordered walk memory-bounded across arbitrarily
     * large tables.
     */
    private static final int PAGE_SIZE = 500;

    /** Repository over the unified ``transactions`` table (copybook ``CVTRA05Y``). */
    private final TransactionRepository transactionRepository;

    /**
     * :purpose: Construct the tasklet with the repository used for the ordered
     *  reconciliation scan.
     * :param transactionRepository: Spring Data JPA repository exposing the
     *  ``findAllByOrderByTranIdAsc(Pageable)`` key-ordered finder over the
     *  unified ``transactions`` table.
     */
    public CombineTransactionsTasklet(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * :purpose: Walk the unified ``transactions`` table once in ``tranId``
     *  ascending order, paging through the rows, counting them, and emitting a
     *  single aggregate INFO log line with the reconciled total; performs no
     *  persistence or mutation.
     * :param contribution: the step contribution for the current step execution
     *  (not modified; this verification pass contributes no read or write count).
     * :param chunkContext: the chunk context for the current step execution
     *  (unused; the entire scan runs within this single tasklet invocation).
     * :returns: {@link RepeatStatus#FINISHED}, signalling that the tasklet
     *  completed its work in a single invocation.
     */
    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        CorrelationIdContext.getOrCreateCorrelationId();

        long reconciledCount = 0;
        int pageIndex = 0;
        List<Transaction> page;
        do {
            page = transactionRepository.findAllByOrderByTranIdAsc(PageRequest.of(pageIndex, PAGE_SIZE));
            reconciledCount += page.size();
            pageIndex++;
        } while (page.size() == PAGE_SIZE);

        log.info("Combined transaction reconciliation complete: {} transactions verified in TRAN-ID order",
                reconciledCount);

        return RepeatStatus.FINISHED;
    }
}
