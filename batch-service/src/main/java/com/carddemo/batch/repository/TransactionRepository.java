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

package com.carddemo.batch.repository;

import com.carddemo.common.domain.Transaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link Transaction} entity.
 *
 * :purpose: Persistence access over the shared ``Transaction`` domain type (copybook
 *     ``CVTRA05Y``), replacing legacy VSAM TRANSACT (KSDS) access. It writes the
 *     interest transactions produced by the interest job (``CBACT04C``) through the
 *     inherited {@code save}, backs the COMBTRAN combined print in transaction-id key
 *     order, and backs the ``CBTRN03C`` transaction-detail report reader with a
 *     process-timestamp date-range filter ordered by card number.
 * :output: Managed {@link Transaction} instances keyed by the 16-character ``tranId``
 *     primary key.
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Read all transactions ordered by transaction id ascending.
     *
     * :param pageable: paging and sort window applied to the result set.
     * :return: transactions in ``tranId`` key order, backing the COMBTRAN combined print.
     */
    List<Transaction> findAllByOrderByTranIdAsc(Pageable pageable);

    /**
     * Read transactions whose processing-timestamp date prefix falls within an inclusive range.
     *
     * :param startDate: inclusive lower bound in ``YYYY-MM-DD`` form.
     * :param endDate: inclusive upper bound in ``YYYY-MM-DD`` form.
     * :param pageable: paging and sort window applied to the result set.
     * :return: matching transactions ordered by ``tranCardNum`` ascending and, within a
     *     card group, by ``tranId`` ascending, comparing the first ten characters (the
     *     ``YYYY-MM-DD`` prefix) of the 26-character ``tranProcTs``. The primary key
     *     completes the ordering so that a paged read cannot repeat or drop a tied row,
     *     and so the rows of one card arrive in the order the legacy sequential
     *     ``TRANSACT`` KSDS read delivers them.
     */
    @Query("SELECT t FROM Transaction t "
         + "WHERE SUBSTRING(t.tranProcTs, 1, 10) >= :startDate "
         + "AND SUBSTRING(t.tranProcTs, 1, 10) <= :endDate "
         + "ORDER BY t.tranCardNum ASC, t.tranId ASC")
    List<Transaction> findByProcTsDateRangeOrderByCardNum(@Param("startDate") String startDate,
                                                          @Param("endDate") String endDate,
                                                          Pageable pageable);

}
