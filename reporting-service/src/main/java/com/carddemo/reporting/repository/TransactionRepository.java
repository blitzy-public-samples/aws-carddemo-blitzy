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
package com.carddemo.reporting.repository;

import com.carddemo.common.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * :purpose: Spring Data JPA repository for the {@link Transaction} store, replacing the legacy
 *  VSAM ``TRNXFILE`` read that accumulates a card's transactions during statement assembly
 *  (``CBSTM03A`` ``4000-TRNXFILE-GET``). The statement engine reads every transaction belonging
 *  to a card in transaction-id key order.
 * :output: Managed {@link Transaction} instances keyed by the 16-character ``tranId`` primary
 *  key, plus the card-scoped, transaction-id-ordered finder declared below.
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * :purpose: Read every transaction posted to a card, ordered by transaction id ascending,
     *  reproducing the sequential ``TRNXFILE`` read for one card in ``CBSTM03A``
     *  ``4000-TRNXFILE-GET``.
     * :param tranCardNum: the 16-character card number whose transactions are requested.
     * :returns: the card's transactions ordered by ``tranId`` ascending; an empty list when the
     *  card has no transactions.
     */
    List<Transaction> findByTranCardNumOrderByTranIdAsc(String tranCardNum);
}
