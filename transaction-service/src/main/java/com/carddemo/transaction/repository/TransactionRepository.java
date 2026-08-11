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
package com.carddemo.transaction.repository;

import com.carddemo.common.domain.Transaction;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * :purpose: Data-access repository for the transaction master (``TRANSACT``);
 *     replaces the VSAM ``STARTBR``/``READNEXT``/``READPREV`` browse of the
 *     transaction list (``COTRN00C``), the keyed ``READ`` of the transaction
 *     view (``COTRN01C``) served by the inherited ``findById``, and the
 *     posting-master ``WRITE`` (``CBTRN02C``) served by the inherited
 *     ``save``; it also exposes the transaction-id sequence consumed by the
 *     add flow (``COTRN02C``).
 * :output: managed {@link Transaction} aggregates keyed by the sixteen-character
 *     ``tranId`` primary key.
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Fetch the forward page of transactions after a cursor id.
     *
     * :purpose: Reproduces the ``COTRN00C`` forward browse (``STARTBR`` at the
     *     cursor followed by ``READNEXT``) that fills the ten list rows
     *     ``TRNID01``..``TRNID10``.
     * :param tranId: the exclusive lower-bound cursor, the last ``tranId``
     *     shown on the current page; ``GreaterThan`` keeps the bound strict.
     * :param pageable: the caller-supplied window; the list screen passes a
     *     ten-row page (for example ``PageRequest.of(0, 10)``) so the page size
     *     is never hardcoded here.
     * :output: up to one page of transactions whose ``tranId`` is greater than
     *     the cursor, in ascending id order (the ``READNEXT`` order).
     */
    List<Transaction> findByTranIdGreaterThanOrderByTranIdAsc(String tranId, Pageable pageable);

    /**
     * Fetch the backward page of transactions before a cursor id.
     *
     * :purpose: Reproduces the ``COTRN00C`` backward browse (``STARTBR`` at the
     *     cursor followed by ``READPREV``) driving the PF7 page-back action.
     * :param tranId: the exclusive upper-bound cursor, the first ``tranId``
     *     shown on the current page; ``LessThan`` keeps the bound strict.
     * :param pageable: the caller-supplied window; the list screen passes a
     *     ten-row page so the page size is never hardcoded here.
     * :output: up to one page of transactions whose ``tranId`` is less than the
     *     cursor, in descending id order (the ``READPREV`` order); the calling
     *     service re-sorts ascending for display.
     */
    List<Transaction> findByTranIdLessThanOrderByTranIdDesc(String tranId, Pageable pageable);

    /**
     * Test whether a further forward page exists.
     *
     * :purpose: Reproduces the extra ``READNEXT`` that ``COTRN00C`` performs to
     *     set the ``NEXT-PAGE-YES``/``NEXT-PAGE-NO`` flag.
     * :param tranId: the last ``tranId`` shown on the current page.
     * :output: ``true`` when at least one transaction has a greater ``tranId``;
     *     ``false`` otherwise.
     */
    boolean existsByTranIdGreaterThan(String tranId);

    /**
     * Locate the first transaction from the top of the file.
     *
     * :purpose: Reproduces the ``STARTBR`` at ``LOW-VALUES`` first-record read
     *     used on initial list entry and on the PF7-to-top action when no
     *     cursor has been established yet.
     * :output: the transaction with the lowest ``tranId``, or an empty
     *     {@link Optional} when the master holds no rows.
     */
    Optional<Transaction> findFirstByOrderByTranIdAsc();

    /**
     * Locate the last transaction at the bottom of the file.
     *
     * :purpose: Reproduces the ``MOVE HIGH-VALUES`` plus ``READPREV``
     *     browse-to-bottom position used by the PF8-to-end action and by the
     *     add flow's highest-id probe.
     * :output: the transaction with the highest ``tranId``, or an empty
     *     {@link Optional} when the master holds no rows.
     */
    Optional<Transaction> findFirstByOrderByTranIdDesc();

    /**
     * Draw the next raw transaction-id value from the database sequence.
     *
     * :purpose: Supplies a collision-free identifier for the add flow
     *     (``COTRN02C``) in place of the legacy browse-last-then-increment
     *     probe. Backed by the PostgreSQL sequence ``transaction_id_seq``,
     *     created by the transaction-service Flyway migration.
     * :output: the next value of the ``transaction_id_seq`` sequence; the
     *     calling service zero-pads it to the sixteen-character ``tranId`` wire
     *     format via ``String.format("%016d", value)``.
     */
    @Query(value = "SELECT nextval('transaction_id_seq')", nativeQuery = true)
    Long getNextTransactionId();

}
