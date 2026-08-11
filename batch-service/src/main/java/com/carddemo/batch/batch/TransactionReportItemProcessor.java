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

import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.common.config.CorrelationIdContext;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.TranCatg;
import com.carddemo.common.domain.TranCatgId;
import com.carddemo.common.domain.TranType;
import com.carddemo.common.domain.Transaction;
import com.carddemo.common.exception.RecordNotFoundException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * :purpose: Spring Batch item processor that resolves the descriptive fields of the
 *     transaction-detail report for one posted {@link Transaction}. It is the Java analogue of
 *     ``CBTRN03C``'s per-record lookups ``1500-A-LOOKUP-XREF`` (card cross-reference to
 *     account id), ``1500-B-LOOKUP-TRANTYPE`` (transaction-type description), and
 *     ``1500-C-LOOKUP-TRANCATG`` (transaction-category description), whose results
 *     ``1120-WRITE-DETAIL`` assembles into a detail row. Each COBOL lookup performs
 *     ``9999-ABEND-PROGRAM`` on ``INVALID KEY``; the Java equivalent throws the unchecked
 *     {@link RecordNotFoundException} when any of the three lookups misses, so the step halts
 *     exactly as the COBOL program abends. The {@code config} package builds the reader via
 *     ``TransactionRepository.findByProcTsDateRangeOrderByCardNum(startDate, endDate,
 *     pageable)`` (which filters on ``SUBSTRING(tranProcTs,1,10)`` inclusively and orders by
 *     ``tranCardNum`` ascending) and supplies the ``startDate``/``endDate``/``reportFile`` job
 *     parameters, so this processor assumes records arrive already date-range-filtered and
 *     card-number-ordered.
 * :output: A fully-populated {@link TransactionReportItem} carrier for every input
 *     transaction; the processor never returns ``null`` because the reader has already applied
 *     the date-range filter, so every record that reaches this processor is an in-range detail
 *     row. Not-found messages reference only the transaction id, never the card number/PAN.
 */
@Component
public class TransactionReportItemProcessor implements ItemProcessor<Transaction, TransactionReportItem> {

    /**
     * Cross-reference repository resolving a card number to its owning account id; replaces the
     * legacy VSAM ``XREFFILE`` random read performed by ``1500-A-LOOKUP-XREF``.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Container-managed persistence context used to resolve the transaction-type and
     * transaction-category reference rows by primary key. No dedicated ``TranType``/``TranCatg``
     * repository exists, so the entity manager performs these two reads directly; the ``find`` calls
     * run inside the step's chunk transaction.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * :purpose: Construct the processor with its injected cross-reference repository.
     * :param cardXrefRepository: repository used to resolve the card cross-reference to an account id.
     */
    public TransactionReportItemProcessor(CardXrefRepository cardXrefRepository) {
        this.cardXrefRepository = cardXrefRepository;
    }

    /**
     * :purpose: Resolve the card cross-reference (account id), transaction-type description, and
     *  transaction-category description for a posted transaction, then assemble the transaction-detail
     *  report row, reproducing ``CBTRN03C`` ``1500-A/B/C`` lookups and ``1120-WRITE-DETAIL``.
     * :param transaction: the posted transaction to enrich; supplies the card number, transaction id,
     *  type code, category code, origination source, and amount.
     * :returns: a fully-populated {@link TransactionReportItem} for the transaction; never ``null``.
     */
    @Override
    public TransactionReportItem process(Transaction transaction) throws Exception {
        CorrelationIdContext.getOrCreateCorrelationId();

        // 1500-A-LOOKUP-XREF: card cross-reference -> account id; abend (INVALID KEY) => not found.
        CardXref xref = cardXrefRepository.findById(transaction.getTranCardNum())
                .orElseThrow(() -> new RecordNotFoundException(
                        "Card cross-reference not found for transaction " + transaction.getTranId()));
        String accountId = String.format("%011d", xref.getXrefAcctId());

        // 1500-B-LOOKUP-TRANTYPE: transaction-type description; abend (INVALID KEY) => not found.
        TranType tranType = entityManager.find(TranType.class, transaction.getTranTypeCd());
        if (tranType == null) {
            throw new RecordNotFoundException(
                    "Transaction type not found for transaction " + transaction.getTranId());
        }

        // 1500-C-LOOKUP-TRANCATG: transaction-category description; abend (INVALID KEY) => not found.
        TranCatg tranCatg = entityManager.find(TranCatg.class,
                new TranCatgId(transaction.getTranTypeCd(), transaction.getTranCatCd()));
        if (tranCatg == null) {
            throw new RecordNotFoundException(
                    "Transaction category not found for transaction " + transaction.getTranId());
        }

        // 1120-WRITE-DETAIL: assemble the detail row in the carrier's constructor field order.
        return new TransactionReportItem(
                transaction.getTranId(),
                accountId,
                transaction.getTranTypeCd(),
                tranType.getTranTypeDesc(),
                transaction.getTranCatCd(),
                tranCatg.getTranCatTypeDesc(),
                transaction.getTranSource(),
                transaction.getTranAmt(),
                transaction.getTranCardNum());
    }
}
