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

import com.carddemo.common.domain.DailyTransaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * :purpose: Spring Data JPA repository for the daily-transaction feed
 *     (``DALYTRAN``), the source of the batch posting ``ItemReader`` that the
 *     ``CBTRN02C`` transaction-posting job consumes. The feed is staged in the
 *     ``daily_transactions`` table (loaded by the seed migration or by the
 *     flat-file load job), so keyed reads, the sequential browse, staging writes
 *     and the between-run reset are all served by the shared database, replacing
 *     the legacy sequential ``DALYTRAN`` data set.
 * :output: {@link DailyTransaction} feed records keyed by their sixteen-character
 *     ``dalytranId``; {@link #findAll()} returns them in ascending ``dalytranId``
 *     order, reproducing the legacy sequential browse order of the posting job.
 */
@Repository
public interface DailyTransactionRepository extends JpaRepository<DailyTransaction, String> {

    /**
     * Return every staged feed record in sequential browse order.
     *
     * :purpose: Supplies the ordered record set that the posting ``ItemReader``
     *     iterates, reproducing the ``CBTRN02C`` sequential browse in ascending
     *     ``dalytranId`` order. The explicit ``order by`` is required because
     *     {@link JpaRepository#findAll()} makes no ordering guarantee.
     * :returns: all staged records sorted by ``dalytranId`` ascending; an empty
     *     list when nothing is staged.
     */
    @Override
    @Query("select d from DailyTransaction d order by d.dalytranId asc")
    List<DailyTransaction> findAll();
}
