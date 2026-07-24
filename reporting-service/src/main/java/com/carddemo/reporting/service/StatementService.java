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
package com.carddemo.reporting.service;

import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.domain.Transaction;
import com.carddemo.common.exception.RecordNotFoundException;
import com.carddemo.reporting.mapper.StatementMapper;
import com.carddemo.reporting.repository.AccountRepository;
import com.carddemo.reporting.repository.CardXrefRepository;
import com.carddemo.reporting.repository.CustomerRepository;
import com.carddemo.reporting.repository.TransactionRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * :purpose: Read-only statement assembly orchestration re-platformed from the legacy batch
 *   statement engine ``CBSTM03A`` (paragraph ``1000-MAINLINE`` with its
 *   ``2000-CUSTFILE-GET`` / ``3000-ACCTFILE-GET`` / ``4000-TRNXFILE-GET`` /
 *   ``5000-CREATE-STATEMENT`` reads). For a single card, or for every card in card-number
 *   order, it reads the card cross-reference, then the owning customer and account, then the
 *   card's transactions, and delegates statement construction to {@link StatementMapper}. The
 *   ordered lookups and the abend-on-missing-referenced-record behavior of the source program
 *   are preserved as ``cross-reference -> customer -> account -> transactions`` reads that raise
 *   {@link RecordNotFoundException} rather than silently skipping.
 * :output: The assembled {@link StatementMapper.StatementModel} for one card, or an ordered
 *   list of models (one per cross-reference) for the all-cards sweep. Bulk plain-text and HTML
 *   file output is handled by the sibling ``batch/StatementGenerationJob``; line-by-line layout,
 *   total accumulation and name/address concatenation live in {@link StatementMapper}. This
 *   service owns no tables and performs no writes, formatting, file I/O or job launching.
 */
@Service
@Transactional(readOnly = true)
public class StatementService {

    private final CardXrefRepository cardXrefRepository;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final StatementMapper statementMapper;

    /**
     * :purpose: Construct the statement service, wiring the cross-reference, customer, account
     *   and transaction repositories together with the statement mapper via constructor
     *   injection.
     * :param cardXrefRepository: repository providing card cross-reference reads (by card number
     *   and in card-number order).
     * :param customerRepository: repository providing keyed customer reads.
     * :param accountRepository: repository providing keyed account reads.
     * :param transactionRepository: repository providing a card's transactions in transaction-id
     *   order.
     * :param statementMapper: assembler that builds the ``StatementModel`` from the read
     *   entities.
     */
    public StatementService(CardXrefRepository cardXrefRepository,
                            CustomerRepository customerRepository,
                            AccountRepository accountRepository,
                            TransactionRepository transactionRepository,
                            StatementMapper statementMapper) {
        this.cardXrefRepository = cardXrefRepository;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.statementMapper = statementMapper;
    }

    /**
     * :purpose: Assemble the statement for a single card, mirroring one iteration of
     *   ``CBSTM03A`` ``1000-MAINLINE``: resolve the card cross-reference, then read the owning
     *   customer and account and the card's transactions, and delegate assembly to the mapper.
     * :param cardNumber: the 16-character card number whose statement is requested.
     * :returns: the assembled {@link StatementMapper.StatementModel} for the card.
     * :raises com.carddemo.common.exception.RecordNotFoundException: when the card cross-reference,
     *   the owning customer, or the owning account cannot be found.
     */
    public StatementMapper.StatementModel generateStatement(String cardNumber) {
        CardXref cardXref = cardXrefRepository.findById(cardNumber)
                .orElseThrow(() -> new RecordNotFoundException(
                        "Card cross-reference not found for card: " + cardNumber));
        return buildStatement(cardXref);
    }

    /**
     * :purpose: Assemble a statement for every card, mirroring the full ``CBSTM03A``
     *   ``1000-MAINLINE`` loop that reads each cross-reference in card-number key order and
     *   produces one statement per record.
     * :returns: the assembled {@link StatementMapper.StatementModel} instances, one per card
     *   cross-reference, in ascending card-number order; an empty list when no cross-references
     *   exist.
     * :raises com.carddemo.common.exception.RecordNotFoundException: when a referenced customer
     *   or account for any cross-reference cannot be found (propagated, never silently skipped).
     */
    public List<StatementMapper.StatementModel> generateAllStatements() {
        List<CardXref> cardXrefs =
                cardXrefRepository.findAll(Sort.by(Sort.Direction.ASC, "xrefCardNum"));
        List<StatementMapper.StatementModel> statements = new ArrayList<>(cardXrefs.size());
        for (CardXref cardXref : cardXrefs) {
            statements.add(buildStatement(cardXref));
        }
        return statements;
    }

    /**
     * :purpose: Read the customer, account and transactions referenced by a single cross-reference
     *   in the fidelity-preserving order ``customer -> account -> transactions`` and delegate to
     *   {@link StatementMapper#toStatement} to build one statement.
     * :param cardXref: the resolved card cross-reference identifying the customer, account and
     *   card to assemble.
     * :returns: the assembled {@link StatementMapper.StatementModel} for the cross-reference.
     * :raises com.carddemo.common.exception.RecordNotFoundException: when the referenced customer
     *   or account cannot be found.
     */
    private StatementMapper.StatementModel buildStatement(CardXref cardXref) {
        Customer customer = customerRepository.findById(cardXref.getXrefCustId())
                .orElseThrow(() -> new RecordNotFoundException(
                        "Customer not found for id: " + cardXref.getXrefCustId()));
        Account account = accountRepository.findById(cardXref.getXrefAcctId())
                .orElseThrow(() -> new RecordNotFoundException(
                        "Account not found for id: " + cardXref.getXrefAcctId()));
        List<Transaction> transactions =
                transactionRepository.findByTranCardNumOrderByTranIdAsc(cardXref.getXrefCardNum());
        return statementMapper.toStatement(account, customer, cardXref, transactions);
    }
}
