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

import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.common.config.CorrelationIdContext;
import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.DailyTransaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * :purpose: Validates each daily-transaction feed record by confirming that its
 *  card cross-reference exists and, when it does, that the referenced account
 *  exists; migrated from the legacy batch program ``CBTRN01C``. This is a
 *  validation-read step only — it performs no posting, no balance update and
 *  writes no reject file (the posting program ``CBTRN02C``, reject codes and the
 *  430-byte reject layout are owned by the ``transaction-service`` module).
 *  Every record is logged as validated, cross-reference-missing or
 *  account-missing and then passed through unchanged; no record is removed or
 *  rejected.
 * :output: A Spring {@link Component} implementing
 *  {@link ItemProcessor}&lt;{@link DailyTransaction}, {@link DailyTransaction}&gt;;
 *  each invocation returns the input record (never {@code null}). The sibling
 *  {@code config} package wires a JPQL {@code JpaPagingItemReader<DailyTransaction>}
 *  ordered by {@code dalytranId} to this processor and forwards the returned
 *  record to a {@code LoggingItemWriter<DailyTransaction>} count-only sink; there
 *  is no {@code DailyTransactionRepository}.
 */
@Component
public class DailyTransactionValidationProcessor
        implements ItemProcessor<DailyTransaction, DailyTransaction> {

    /** SLF4J logger; the {@code correlationId} MDC value is rendered by {@code logback-spring.xml}. */
    private static final Logger log =
            LoggerFactory.getLogger(DailyTransactionValidationProcessor.class);

    /** Cross-reference repository used to resolve a card number to its cross-reference record. */
    private final CardXrefRepository cardXrefRepository;

    /** Account repository used to confirm the existence of the cross-referenced account. */
    private final AccountRepository accountRepository;

    /**
     * :purpose: Create the processor with the repositories required to validate
     *  a daily-transaction record.
     * :param cardXrefRepository: repository resolving a card number to its
     *  {@link CardXref} cross-reference record.
     * :param accountRepository: repository resolving an account identifier to
     *  its {@link Account} master record.
     */
    public DailyTransactionValidationProcessor(CardXrefRepository cardXrefRepository,
                                               AccountRepository accountRepository) {
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * :purpose: Validate a single daily-transaction record by looking up its
     *  card cross-reference by card number and, when present, the referenced
     *  account by its identifier, logging the validation outcome. A missing
     *  cross-reference or account is logged at {@code WARN} and never raises an
     *  exception; the record is always passed through so the downstream
     *  count-only writer observes every input record.
     * :param item: the daily-transaction feed record to validate; must not be
     *  {@code null}.
     * :returns: the same {@code item} instance, unchanged (never {@code null}).
     * :throws Exception: declared to satisfy the {@link ItemProcessor} contract;
     *  this implementation does not throw for a missing cross-reference or
     *  account.
     */
    @Override
    public DailyTransaction process(DailyTransaction item) throws Exception {
        CorrelationIdContext.getOrCreateCorrelationId();

        Optional<CardXref> xref = cardXrefRepository.findById(item.getDalytranCardNum());
        if (xref.isEmpty()) {
            log.warn("Daily transaction {} skipped: card cross-reference not found",
                    item.getDalytranId());
            return item;
        }

        Optional<Account> account = accountRepository.findById(xref.get().getXrefAcctId());
        if (account.isEmpty()) {
            log.warn("Daily transaction {}: account {} not found",
                    item.getDalytranId(), xref.get().getXrefAcctId());
        } else {
            log.debug("Daily transaction {} validated", item.getDalytranId());
        }

        return item;
    }
}
