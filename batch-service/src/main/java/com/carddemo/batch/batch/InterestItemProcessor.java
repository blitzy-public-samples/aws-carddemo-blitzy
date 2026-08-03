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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemStream;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.carddemo.batch.service.InterestCalculationService;
import com.carddemo.common.domain.TranCatBal;
import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.DiscGroup;
import com.carddemo.common.domain.Transaction;
import com.carddemo.common.config.CorrelationIdContext;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * :purpose: Per-transaction-category-balance-row interest processor migrated from the
 *     legacy batch program ``CBACT04C`` (paragraphs ``1200-GET-INTEREST-RATE``,
 *     ``1300-COMPUTE-INTEREST`` and ``1300-B-WRITE-TX``). For each {@link TranCatBal}
 *     row it resolves the disclosure-group interest rate, and only when that rate is
 *     non-zero computes the monthly interest ``(TRAN-CAT-BAL * DIS-INT-RATE) / 1200`` and
 *     assembles the interest {@link Transaction} to be persisted, emitting an
 *     {@link InterestPostingItem} that carries the per-row result to the downstream
 *     {@code InterestTransactionWriter}.
 * :output: A non-{@code null} {@link InterestPostingItem} for every input row — either
 *     {@link InterestPostingItem#withInterest} (rate non-zero: computed interest plus a
 *     built interest transaction) or {@link InterestPostingItem#zeroInterest} (rate zero:
 *     no transaction) — so that every owning account still reaches the writer and receives
 *     its once-per-account cycle roll-up (``1050-UPDATE-ACCOUNT``), which runs in
 *     ``CBACT04C`` for every account regardless of interest. The account-level running
 *     total accumulation and the cycle credit/debit zeroing are performed downstream by the
 *     writer, not here.
 * :note: The tran-id suffix ({@code tranIdSuffix}, the equivalent of ``WS-TRANID-SUFFIX``)
 *     is a step-global monotonic counter that starts at 0 and is incremented only when a
 *     transaction is actually written (non-zero rate), so the first written transaction is
 *     rendered ``000001`` and zero-rate rows consume no suffix number; it is never reset per
 *     account. The interest arithmetic and the disclosure-group ``DEFAULT`` fallback are
 *     owned by {@link InterestCalculationService}; this processor performs no inline math and
 *     no inline fallback.
 * :note: Coordination contract with the ``config/`` package (described here for reference;
 *     the wiring itself is built by that package): the interest step's reader supplies
 *     {@link TranCatBal} rows ordered by
 *     ``(trancatAcctId, trancatTypeCd, trancatCd)`` ascending (via
 *     ``TranCatBalRepository.findAllByOrderByTrancatAcctIdAscTrancatTypeCdAscTrancatCdAsc``),
 *     the ``parmDate`` job parameter is passed to this ``@StepScope`` bean, and the step runs
 *     single-threaded with no retry/skip so the tran-id suffix stays contiguous and no row is
 *     processed twice.
 */
@Component
@StepScope
public class InterestItemProcessor
        implements ItemProcessor<TranCatBal, InterestPostingItem>, ItemStream {

    /**
     * :purpose: Step ``ExecutionContext`` key under which the highest suffix this run has
     *     allocated is persisted, so a restart resumes past every COMMITTED value instead
     *     of re-issuing ``000001``.
     */
    static final String TRAN_ID_SUFFIX_KEY = "interest.tranIdSuffix";

    /** SLF4J logger; every line carries the MDC correlation id via ``%X{correlationId}``. */
    private static final Logger log = LoggerFactory.getLogger(InterestItemProcessor.class);

    /**
     * Interest-domain service that owns the exact monthly-interest arithmetic, the
     * disclosure-group resolution with ``DEFAULT`` fallback, the account and card
     * cross-reference reads, and the interest-transaction assembly (migrated from the
     * ``CBACT04C`` helper paragraphs).
     */
    private final InterestCalculationService interestService;

    /**
     * The 10-character business date (``PARM-DATE``) supplied as the ``parmDate`` job
     * parameter, matching ``INTCALC.jcl`` ``PARM='2022071800'``; it forms the leading
     * portion of every generated ``TRAN-ID``.
     */
    private final String parmDate;

    /**
     * Run-global monotonic transaction-id suffix, the Java equivalent of
     * ``WS-TRANID-SUFFIX``. It starts at 0 and is incremented only when a transaction is
     * written (non-zero rate), so the first written transaction receives suffix 1 (rendered
     * ``000001``); it is never reset per account.
     *
     * <p>The value is seeded from, and republished to, the step ``ExecutionContext``
     * ({@link #TRAN_ID_SUFFIX_KEY}), which Spring Batch persists with every chunk commit.
     * A restarted execution therefore continues past the last suffix whose transaction was
     * COMMITTED, and the suffixes of a rolled-back chunk are re-issued because those rows
     * were never written.
     */
    private final AtomicLong tranIdSuffix = new AtomicLong(0);

    /**
     * :purpose: Construct the processor with its interest-domain service and the
     *     step-scoped business date job parameter.
     * :param interestService: the interest-domain service that owns the arithmetic,
     *     disclosure-group resolution, reads and transaction assembly.
     * :param parmDate: the 10-character business date job parameter (``PARM-DATE``),
     *     late-bound at step start from ``#{jobParameters['parmDate']}``.
     */
    public InterestItemProcessor(InterestCalculationService interestService,
                                 @Value("#{jobParameters['parmDate']}") String parmDate) {
        this.interestService = interestService;
        this.parmDate = parmDate;
    }

    /**
     * :purpose: Process one transaction-category-balance row, reproducing the per-row body
     *     of the ``CBACT04C`` main loop: resolve the disclosure-group interest rate for the
     *     row and, only when it is non-zero, compute the monthly interest, allocate the next
     *     tran-id suffix and assemble the interest {@link Transaction}.
     * :param item: the {@link TranCatBal} row supplied by the account-ordered reader.
     * :returns: a non-{@code null} {@link InterestPostingItem}; carrying the computed
     *     interest and its built transaction when the rate is non-zero, otherwise a
     *     zero-interest carrier so the owning account still reaches the writer for its
     *     cycle roll-up.
     */
    @Override
    public InterestPostingItem process(TranCatBal item) throws Exception {
        CorrelationIdContext.getOrCreateCorrelationId();

        Long acctId = item.getTrancatAcctId();
        Account account = interestService.loadAccount(acctId);
        DiscGroup discGroup = interestService.resolveDiscGroup(
                account.getAcctGroupId(), item.getTrancatTypeCd(), item.getTrancatCd());

        // COBOL ``IF DIS-INT-RATE NOT = 0`` is a numeric comparison; use compareTo (not
        // equals, which is scale-sensitive for BigDecimal) so a rate stored as e.g. 0.00
        // still evaluates as zero and writes no transaction.
        if (discGroup.getDisIntRate().compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal monthlyInterest =
                    interestService.computeMonthlyInterest(item.getTranCatBal(), discGroup.getDisIntRate());
            String cardNumber = interestService.resolveCardNumber(acctId);
            // Mirrors ``ADD 1 TO WS-TRANID-SUFFIX`` inside 1300-B-WRITE-TX: increment only on
            // the write path, exactly once per emitted transaction.
            long suffix = tranIdSuffix.incrementAndGet();
            Transaction interestTransaction =
                    interestService.buildInterestTransaction(account, cardNumber, monthlyInterest, parmDate, suffix);
            return InterestPostingItem.withInterest(acctId, monthlyInterest, interestTransaction);
        }

        // Zero rate: no transaction is written and the suffix is NOT incremented, yet the
        // account id is still carried so the writer performs the cycle-zeroing roll-up.
        return InterestPostingItem.zeroInterest(acctId);
    }

    /**
     * :purpose: Seed the tran-id suffix from the step ``ExecutionContext`` so a restarted
     *     execution continues past every suffix whose transaction was already committed.
     * :param executionContext: the step execution context; on a restart it is the context
     *     Spring Batch persisted at the last successful chunk commit of the previous
     *     execution.
     * :note: This is what makes the interest run restartable (QA Issue 10). With the
     *     counter starting at 0 on every execution, a run that had committed
     *     ``<PARM-DATE>000001..000005`` re-claimed ``000001`` when restarted and died on
     *     ``duplicate key value violates unique constraint "transactions_pkey"``, so a
     *     partially posted portfolio could never be completed - the remaining accounts
     *     could not be posted at all without manually deleting the committed rows.
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        long resumeFrom = executionContext.getLong(TRAN_ID_SUFFIX_KEY, 0L);
        tranIdSuffix.set(resumeFrom);
        if (resumeFrom > 0L) {
            log.info("Resuming interest transaction-id suffix after {} previously committed suffix(es)",
                    resumeFrom);
        }
    }

    /**
     * :purpose: Publish the highest suffix allocated so far into the step
     *     ``ExecutionContext``, which Spring Batch persists in the same transaction as the
     *     chunk, so the durable high-water mark always covers exactly the committed
     *     transactions.
     * :param executionContext: the step execution context to update.
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putLong(TRAN_ID_SUFFIX_KEY, tranIdSuffix.get());
    }

    /**
     * :purpose: Release the processor at the end of the step; no resource is held, and the
     *     durable suffix high-water mark has already been published by {@link #update}.
     */
    @Override
    public void close() throws ItemStreamException {
        // No resource to release: the counter's durable state lives in the ExecutionContext.
    }
}
