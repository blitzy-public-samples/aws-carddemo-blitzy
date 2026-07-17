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
package com.aws.carddemo.batch.processor;

import java.util.Optional;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemProcessor} that reproduces the per-record, <em>read-only</em>
 * validation pass of the legacy COBOL batch program {@code CBTRN01C}
 * (source {@code legacy/cbl/CBTRN01C.cbl}, formerly {@code app/cbl/CBTRN01C.cbl}),
 * the transform side of the daily-transaction <em>validate</em> job.
 *
 * <h2>Legacy lineage (CBTRN01C)</h2>
 * <p>CBTRN01C is a pure verification pass over the daily-transaction file. For each
 * record it looks up the card cross-reference and then, only if that succeeds, the
 * owning account, and it merely <em>reports</em> (COBOL {@code DISPLAY}) any anomaly.
 * It performs <strong>no writes, no mutations, and no reject-file output</strong>, and
 * always ends with return code {@code 0} even when records fail their lookups. The
 * legacy {@code Z-ABEND-PROGRAM} path exists solely for file-system I/O failures
 * (open/read/close status other than {@code '00'}/{@code '10'}), which in the Spring
 * Batch model are surfaced by the reader/framework, never by this item processor; a
 * missing cross-reference or account is a normal, non-fatal {@code INVALID KEY} that
 * the program logs and skips. This processor preserves that behavior exactly.</p>
 *
 * <p>The reproduced control flow (COBOL {@code MAIN-PARA} loop, L164-186) is:</p>
 * <ul>
 *   <li><strong>Card cross-reference lookup</strong> &mdash; paragraph
 *       {@code 2000-LOOKUP-XREF} (L227-239). The card number is the cross-reference
 *       key; if the read returns {@code INVALID KEY} the program displays that the
 *       card could not be verified and skips the record. Here that becomes a
 *       {@code cardXrefRepository.findById(cardNum)} whose empty result is logged at
 *       {@code WARN} and short-circuits the record.</li>
 *   <li><strong>Account lookup</strong> &mdash; paragraph {@code 3000-READ-ACCOUNT}
 *       (L241-250), performed <em>only</em> when the cross-reference read succeeded
 *       (COBOL guard {@code IF WS-XREF-READ-STATUS = 0}). A missing account is a
 *       {@code WARN}; a successful read is a quiet {@code DEBUG} trace so normal runs
 *       stay silent.</li>
 * </ul>
 *
 * <h2>Parity guarantees</h2>
 * <ul>
 *   <li><strong>Read-only:</strong> the entity is never mutated and no repository
 *       write ({@code save}/{@code update}) is ever invoked &mdash; CBTRN01C writes
 *       nothing.</li>
 *   <li><strong>Non-failing:</strong> a missing cross-reference or account is logged,
 *       never thrown and never emitted as a reject. Reject-code semantics belong to the
 *       posting program {@code CBTRN02C}, a different processor. Return-code parity is
 *       therefore {@code RC 0} even in the presence of anomalies.</li>
 *   <li><strong>Evaluation order:</strong> the cross-reference is resolved first and
 *       the account is looked up only if the cross-reference exists, matching the COBOL
 *       status guard.</li>
 *   <li><strong>Pass-through:</strong> the same {@link DailyTransaction} instance is
 *       returned unchanged so the enclosing chunk-oriented step can count and aggregate
 *       records; the write side of the validate job is a no-op / logging writer.</li>
 * </ul>
 *
 * <p>CBTRN01C also opens the customer, card and transaction files but does not use them
 * in the validate loop, so &mdash; faithful to the legacy scope &mdash; this processor
 * intentionally injects and references only the cross-reference and account
 * repositories.</p>
 *
 * <p>The bean's default name is {@code dailyTransactionValidateProcessor}; the parent
 * {@code batch/} job wires it into {@code DailyTransactionValidateJob} by type/name.
 * Collaborators are supplied by constructor injection to keep the processor trivially
 * unit-testable with mocked repositories.</p>
 */
@Component
public class DailyTransactionValidateProcessor
        implements ItemProcessor<DailyTransaction, DailyTransaction> {

    /** SLF4J logger; carries the batch correlation id via the MDC populated upstream. */
    private static final Logger log =
            LoggerFactory.getLogger(DailyTransactionValidateProcessor.class);

    /**
     * Cross-reference repository (COBOL {@code XREF-FILE}); resolves a card number to
     * its cross-reference row, the analog of paragraph {@code 2000-LOOKUP-XREF}.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Account repository (COBOL {@code ACCOUNT-FILE}); resolves an account id to its
     * master record, the analog of paragraph {@code 3000-READ-ACCOUNT}.
     */
    private final AccountRepository accountRepository;

    /**
     * Creates the processor with its two read-only collaborators.
     *
     * <p>Only the fields are assigned so that no overridable instance method is invoked
     * during construction.</p>
     *
     * @param cardXrefRepository the card cross-reference repository (COBOL
     *                           {@code XREF-FILE}); must not be {@code null}
     * @param accountRepository  the master-account repository (COBOL
     *                           {@code ACCOUNT-FILE}); must not be {@code null}
     */
    public DailyTransactionValidateProcessor(CardXrefRepository cardXrefRepository,
                                             AccountRepository accountRepository) {
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Validates a single staged daily-transaction record without mutating it.
     *
     * <p>Reproduces the CBTRN01C main-loop body: the card number is looked up in the
     * cross-reference ({@code 2000-LOOKUP-XREF}); if it is not found the anomaly is
     * logged at {@code WARN} and the record is skipped (no account lookup), mirroring
     * the COBOL {@code INVALID KEY} branch that displays
     * {@code 'CARD NUMBER ... COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-...'}. If
     * the cross-reference is found, its account id is looked up
     * ({@code 3000-READ-ACCOUNT}); a missing account is logged at {@code WARN}
     * (mirroring {@code 'ACCOUNT ... NOT FOUND'}) while a successful read produces a
     * quiet {@code DEBUG} trace. The record is always returned unchanged, and no
     * exception is thrown for a missing cross-reference or account &mdash; preserving
     * the legacy read-only, return-code-{@code 0} behavior.</p>
     *
     * @param item the staged daily-transaction record supplied by the reader; never
     *             {@code null} under the Spring Batch chunk contract
     * @return the same {@code item} instance, unchanged (pass-through)
     */
    @Override
    public DailyTransaction process(DailyTransaction item) {
        // Paragraph 2000-LOOKUP-XREF: read the cross-reference by card number.
        Optional<CardXref> xref = cardXrefRepository.findById(item.getCardNum());
        if (xref.isEmpty()) {
            // COBOL INVALID KEY branch: card could not be verified; skip this record.
            log.warn("Card number {} could not be verified. Skipping transaction ID {}",
                    item.getCardNum(), item.getDalytranId());
            return item;
        }

        // Paragraph 3000-READ-ACCOUNT: performed only when the cross-reference exists
        // (COBOL guard IF WS-XREF-READ-STATUS = 0), using the resolved account id.
        Long acctId = xref.get().getAcctId();
        Optional<Account> account = accountRepository.findById(acctId);
        if (account.isEmpty()) {
            // COBOL INVALID KEY branch: 'ACCOUNT <id> NOT FOUND'.
            log.warn("Account {} not found for card {} (transaction ID {})",
                    acctId, item.getCardNum(), item.getDalytranId());
        } else {
            // COBOL NOT INVALID KEY branch: 'SUCCESSFUL READ OF ACCOUNT FILE'.
            log.debug("Successful read of account {} for card {} (transaction ID {})",
                    acctId, item.getCardNum(), item.getDalytranId());
        }

        // Report-only pass: return the record unchanged (RC 0 even with anomalies).
        return item;
    }
}
