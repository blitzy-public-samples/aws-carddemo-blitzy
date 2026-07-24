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
package com.carddemo.billpay.repository;

import com.carddemo.common.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository over the ``transactions`` table for the
 * bill-payment service.
 *
 * :purpose: Provides persistence access to {@link Transaction} for bill
 *     payment, replacing the CICS VSAM ``TRANSACT`` access of the legacy
 *     program ``COBIL00C`` — the inherited ``save`` operation supersedes the
 *     ``WRITE-TRANSACT-FILE`` paragraph, and {@link #getNextTransactionId()}
 *     supersedes the ``STARTBR``/``READPREV``/``ENDBR`` browse-last identifier
 *     generation.
 * :output: A Spring-managed repository proxy keyed by the 16-character
 *     ``tran_id`` string.
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Reserve the next transaction identifier from the shared PostgreSQL
     * sequence ``transaction_id_seq``.
     *
     * :output: The next raw sequence value as a {@link Long}, which the service
     *     layer zero-pads to the 16-digit ``tran_id`` wire form.
     */
    @Query(value = "SELECT nextval('transaction_id_seq')", nativeQuery = true)
    Long getNextTransactionId();
}
