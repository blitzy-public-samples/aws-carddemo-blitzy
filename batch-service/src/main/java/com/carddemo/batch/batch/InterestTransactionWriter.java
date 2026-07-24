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

import com.carddemo.batch.service.InterestCalculationService;
import com.carddemo.common.config.CorrelationIdContext;
import com.carddemo.common.domain.Account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * :purpose: Spring Batch {@link ItemWriter} that completes the monthly interest job by
 *     persisting each computed interest {@link com.carddemo.common.domain.Transaction} and
 *     then applying the per-account balance roll-up with current-cycle zeroing. It is the
 *     Java re-platforming of ``CBACT04C``'s ``1300-B-WRITE-TX`` (write the interest
 *     transaction) together with ``1050-UPDATE-ACCOUNT`` (``ADD WS-TOTAL-INT TO
 *     ACCT-CURR-BAL``; ``MOVE 0 TO ACCT-CURR-CYC-CREDIT``; ``MOVE 0 TO ACCT-CURR-CYC-DEBIT``;
 *     ``REWRITE``).
 * :output: For every delivered chunk, each carried interest transaction is persisted before
 *     any account roll-up, and every distinct account id in the chunk — including accounts
 *     whose category rows all carried a zero interest rate — receives one cycle roll-up that
 *     adds the accumulated interest to the current balance and zeroes the current-cycle
 *     credit and debit figures. All monetary arithmetic, rounding and JPA ``@Version``
 *     optimistic-lock handling are performed by {@link InterestCalculationService}.
 * :note: The writer is not annotated ``@Transactional``; Spring Batch wraps each chunk in its
 *     own transaction, so all transaction writes and account roll-ups for a chunk commit or
 *     roll back together (the ``@Transactional``-per-account unit). The ``config`` package
 *     sizes each chunk to one account's category rows so that a chunk boundary is an account
 *     control break, yielding one transaction per account — the analogue of
 *     ``1050-UPDATE-ACCOUNT`` firing on account change — and configures the interest step
 *     single-threaded with no retry or skip.
 * :note: Because {@link InterestCalculationService#updateAccount} adds to the balance and
 *     zeroes the cycle figures, an account split across two chunks still rolls up correctly.
 */
@Component
public class InterestTransactionWriter implements ItemWriter<InterestPostingItem> {

    /** SLF4J logger; the ``correlationId`` MDC value is rendered by ``logback-spring.xml``. */
    private static final Logger log = LoggerFactory.getLogger(InterestTransactionWriter.class);

    /** Service holding the interest-transaction persistence and per-account cycle roll-up. */
    private final InterestCalculationService interestService;

    /**
     * :purpose: Construct the writer with its interest-calculation service injected by Spring.
     * :param interestService: service that persists interest transactions and applies the
     *     per-account cycle roll-up honoring the JPA ``@Version`` optimistic lock.
     */
    public InterestTransactionWriter(InterestCalculationService interestService) {
        this.interestService = interestService;
    }

    /**
     * :purpose: Persist the interest transactions carried by the chunk and then apply the
     *     per-account cycle roll-up for every account represented in the chunk, reproducing
     *     ``CBACT04C``'s ``1300-B-WRITE-TX`` writes followed by the ``1050-UPDATE-ACCOUNT``
     *     rewrite.
     * :param chunk: the chunk of interest-posting items delivered by the step; each item that
     *     carries a transaction is persisted, and every distinct account id it references
     *     (including a zero-interest account) is rolled up.
     * :output: Every carried transaction is saved before any account roll-up, and each
     *     distinct account receives exactly one roll-up that adds its accumulated interest to
     *     the current balance and zeroes the current-cycle credit and debit figures.
     */
    @Override
    public void write(Chunk<? extends InterestPostingItem> chunk) throws Exception {
        // Stamp an MDC correlation id first so every subsequent log line for this chunk is
        // correlated; the service delegates also ensure one is present.
        CorrelationIdContext.getOrCreateCorrelationId();

        // Persist each interest transaction (1300-B-WRITE-TX) while accumulating the interest
        // total per account in encounter order. merge(..., BigDecimal::add) records an entry
        // for every distinct account id, so the cycle roll-up (1050-UPDATE-ACCOUNT) runs once
        // per account even when all of that account's rows carried a zero interest rate and
        // therefore emitted no transaction (contributing BigDecimal.ZERO).
        Map<Long, BigDecimal> totalInterestByAccount = new LinkedHashMap<>();
        int transactionsSaved = 0;
        for (InterestPostingItem item : chunk) {
            if (item.hasTransaction()) {
                interestService.saveTransaction(item.getTransaction());
                transactionsSaved++;
            }
            totalInterestByAccount.merge(item.getAcctId(), item.getMonthlyInterest(), BigDecimal::add);
        }

        // Apply the per-account roll-up after every transaction in the chunk has been written,
        // matching the COBOL order in which the transaction writes precede the
        // 1050-UPDATE-ACCOUNT REWRITE. The balance arithmetic and @Version optimistic-lock
        // handling are delegated to the service.
        for (Map.Entry<Long, BigDecimal> entry : totalInterestByAccount.entrySet()) {
            Account account = interestService.loadAccount(entry.getKey());
            interestService.updateAccount(account, entry.getValue());
        }

        // Aggregate counts only — never card numbers, PANs, balances or other PII.
        log.info("Interest chunk complete: {} transaction(s) saved, {} account(s) rolled up",
                transactionsSaved, totalInterestByAccount.size());
    }
}
