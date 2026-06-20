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
package com.aws.carddemo.repository;

import com.aws.carddemo.domain.Transaction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link Transaction} (table {@code transaction}).
 *
 * <p>Replaces legacy VSAM {@code TRANSACT} KSDS access (copybook {@code CVTRA05Y}). Primary key
 * {@code tran_id} ({@code String}). {@link #findAllByOrderByTranIdAsc()} reproduces the {@code
 * COMBTRAN.jcl} combine step {@code SORT FIELDS=(TRAN-ID,A)} (ascending by transaction id); {@link
 * #findAllByOrderByTranProcTsAsc()} uses the processing-timestamp alternate index ({@code
 * ix_transaction_proc_ts}, LISTCAT AXRKP 304) for chronological ordering. A missing record returns
 * {@link java.util.Optional#empty()} from {@code findById} (FILE STATUS {@code '23'}); the service
 * performs writes via {@code save} (legacy {@code 2900-WRITE-TRANSACTION-FILE}).
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

  /**
   * Returns all transactions ordered ascending by {@code tran_id}, reproducing the legacy combine
   * step {@code SORT FIELDS=(TRAN-ID,A)} (COMBTRAN.jcl). The order is stable on {@code tranId}.
   *
   * @return all transactions in ascending {@code tran_id} order (possibly empty)
   */
  List<Transaction> findAllByOrderByTranIdAsc();

  /**
   * Returns all transactions ordered ascending by the processing timestamp {@code tran_proc_ts},
   * using the chronological alternate index {@code ix_transaction_proc_ts}.
   *
   * @return all transactions in ascending {@code tran_proc_ts} order (possibly empty)
   */
  List<Transaction> findAllByOrderByTranProcTsAsc();
}
