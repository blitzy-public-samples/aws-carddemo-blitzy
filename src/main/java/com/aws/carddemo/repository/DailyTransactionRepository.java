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
package com.aws.carddemo.repository;

import com.aws.carddemo.domain.DailyTransaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link DailyTransaction} staging entity.
 *
 * <p>This repository replaces the COBOL sequential file access to the legacy
 * {@code DALYTRAN} dataset (copybook {@code legacy/cpy/CVTRA06Y.cpy},
 * {@code DALYTRAN-RECORD}, record length 350) with set-based access to the
 * PostgreSQL staging table {@code daily_transaction}. Rows in this table are the
 * <em>input staging area</em> of unposted daily transactions awaiting validation
 * and posting.</p>
 *
 * <p><strong>COBOL origin.</strong> The {@code DALYTRAN} file is opened for input
 * and read in file order by two batch programs:</p>
 * <ul>
 *   <li>{@code legacy/cbl/CBTRN01C.cbl} &mdash; the daily-transaction validate
 *       batch, which reads each record ({@code READ DALYTRAN-FILE} in paragraph
 *       {@code 1000-DALYTRAN-GET-NEXT}) and validates it against the customer,
 *       cross-reference, card, account, and transaction files.</li>
 *   <li>{@code legacy/cbl/CBTRN02C.cbl} &mdash; the daily-transaction posting
 *       batch, which reads each staged record in the same sequential manner and
 *       then posts it to the account or rejects it with a reason code.</li>
 * </ul>
 * <p>Both programs declare the file as {@code ORGANIZATION IS SEQUENTIAL} /
 * {@code ACCESS MODE IS SEQUENTIAL}, processing records in insertion (file)
 * order until end-of-file.</p>
 *
 * <p><strong>Primary key.</strong> The managed entity uses a database-generated
 * surrogate {@code Long} identity ({@code daily_transaction.id}) as its primary
 * key, which is why the identifier generic of this repository is {@code Long}.
 * The business identifier {@code DALYTRAN-ID} is carried on the entity as the
 * ordinary, non-key column {@code dalytran_id}, because staging rows may re-use
 * the same business id across posting runs.</p>
 *
 * <p><strong>Scope.</strong> This interface exposes only data-access operations.
 * Transaction validation and the batch reject-code semantics (100 = cross-reference
 * not found, 101 = account not found, 102 = over credit limit, 103 = transaction
 * after account expiration) are intentionally <em>not</em> implemented here; that
 * behavior belongs to the batch/service layer (the posting service), preserving a
 * clean separation between persistence and business logic.</p>
 *
 * @see DailyTransaction
 */
@Repository
public interface DailyTransactionRepository extends JpaRepository<DailyTransaction, Long> {

    /**
     * Reads every staged daily transaction ordered by the surrogate primary key
     * ascending, i.e. in insertion order.
     *
     * <p>This reproduces the sequential read of the {@code DALYTRAN} dataset by the
     * daily posting batch ({@code legacy/cbl/CBTRN02C.cbl}) and the validate batch
     * ({@code legacy/cbl/CBTRN01C.cbl}), which process each daily transaction record
     * in file order. For chunk-oriented batch processing, callers may instead page
     * through the data with the inherited {@link JpaRepository#findAll(org.springframework.data.domain.Pageable)};
     * this convenience method provides a deterministic, fully materialized
     * insertion-order view that mirrors the legacy sequential file semantics.</p>
     *
     * @return all staged daily transactions ordered by {@code id} ascending; an
     *         empty list when the staging table is empty (never {@code null})
     */
    List<DailyTransaction> findAllByOrderByIdAsc();
}
