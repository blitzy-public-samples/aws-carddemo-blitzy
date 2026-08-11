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

import com.carddemo.common.domain.TranCatBal;
import com.carddemo.common.domain.TranCatBalId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the transaction-category-balance table.
 *
 * :purpose: Data-access repository for transaction-category balances
 *     (``TCATBAL``); replaces VSAM keyed ``READ``/``WRITE``/``REWRITE`` upsert
 *     used by ``CBTRN02C`` posting. The inherited composite-key ``findById``
 *     reproduces the ``2700-UPDATE-TCATBAL`` keyed read (present -> existing
 *     balance; absent -> new category), and ``save`` reproduces both the insert
 *     of a new category-balance row and the rewrite of an existing one; the
 *     add-and-round arithmetic is performed by the batch posting processor.
 * :output: managed {@link TranCatBal} rows keyed by {@link TranCatBalId}.
 */
@Repository
public interface TranCatBalRepository extends JpaRepository<TranCatBal, TranCatBalId> {
}
