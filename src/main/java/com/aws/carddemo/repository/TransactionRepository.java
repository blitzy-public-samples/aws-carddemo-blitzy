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

import static org.hibernate.jpa.HibernateHints.HINT_FETCH_SIZE;
import static org.hibernate.jpa.HibernateHints.HINT_READ_ONLY;

import com.aws.carddemo.domain.Transaction;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;
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
 *
 * <p><strong>Keyset (range) browse methods.</strong> The {@code COTRN00C} transaction-list screen
 * is a pseudo-conversational STARTBR / READNEXT / READPREV browse of {@code TRANSACT} ascending by
 * {@code tran_id}. To preserve that PF7/PF8 paging semantics <em>without</em> reading the entire
 * 200k-row table on every page turn (the unbounded {@code findAllByOrderByTranIdAsc()} read that
 * exhausted the heap under concurrency), the service issues bounded keyset queries that fetch only
 * {@code page_size + 1} rows (the page plus a single read-ahead peek) anchored at the round-tripped
 * boundary key. {@code GreaterThanEqual} reproduces {@code STARTBR} GTEQ (refresh / browse from the
 * top when the key is the empty string); {@code GreaterThan} reproduces the {@code READNEXT} that
 * steps past the previous page's last id (PF8); {@code LessThan ... Desc} reproduces the {@code
 * READPREV} walk (PF7), the caller reversing the descending slice to ascending. Result memory is
 * O(page) rather than O(table).
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

  /**
   * Returns all transactions ordered ascending by {@code tran_id}, reproducing the legacy combine
   * step {@code SORT FIELDS=(TRAN-ID,A)} (COMBTRAN.jcl). The order is stable on {@code tranId}.
   *
   * <p><strong>Caution — unbounded.</strong> This materialises the entire {@code transaction} table
   * and must only be used by callers that genuinely require a full snapshot at a bounded volume
   * (e.g. the {@code COTRN02C}/{@code COBIL00C} maximum-id derivation). The {@code COTRN00C}
   * transaction-list browse uses the bounded keyset methods below; the {@code TransactionCombine}
   * and statement batch jobs use the streaming reader.
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

  // ===============================================================================================
  // Keyset browse (COTRN00C STARTBR / READNEXT / READPREV) — bounded to page_size + 1 rows
  // ===============================================================================================

  /**
   * Forward keyset page anchored inclusively at {@code startKey} (STARTBR GTEQ; also the
   * from-the-top browse when {@code startKey} is the empty string). Returns up to the {@code
   * Pageable} limit of transactions with {@code tran_id >= startKey} ascending.
   *
   * @param startKey the inclusive lower-bound transaction id (empty string browses from the top)
   * @param pageable the row limit (typically {@code PageRequest.of(0, rowsPerPage + 1)})
   * @return the bounded ascending page (possibly empty)
   */
  List<Transaction> findByTranIdGreaterThanEqualOrderByTranIdAsc(
      String startKey, Pageable pageable);

  /**
   * Backward keyset page strictly below {@code startKey}, descending (READPREV; PF7). The caller
   * reverses the returned descending slice to present it ascending. Returns up to the {@code
   * Pageable} limit of transactions with {@code tran_id < startKey} descending.
   *
   * @param startKey the exclusive upper-bound transaction id (the current page's first id)
   * @param pageable the row limit (typically {@code PageRequest.of(0, rowsPerPage + 1)})
   * @return the bounded descending slice (possibly empty); reverse for ascending display
   */
  List<Transaction> findByTranIdLessThanOrderByTranIdDesc(String startKey, Pageable pageable);

  // ===============================================================================================
  // Streaming full scans: bounded-memory cursors over the whole table for the batch jobs
  // ===============================================================================================

  /**
   * Streams every transaction ascending by {@code tran_id} as a forward-only JDBC cursor, the
   * bounded-memory replacement for the unbounded {@link #findAllByOrderByTranIdAsc()} used by the
   * {@code TransactionCombine} step (legacy {@code COMBTRAN.jcl} {@code SORT FIELDS=(TRAN-ID,A)}).
   * Unlike {@code findAllByOrderByTranIdAsc()} — which materialises the entire 200k-row {@code
   * transaction} table into a {@code List} before iteration (the full-buffer pattern flagged at
   * scale) — this finder carries {@link org.hibernate.jpa.HibernateHints#HINT_FETCH_SIZE} so the
   * JDBC driver fetches a small window of rows at a time and {@link
   * org.hibernate.jpa.HibernateHints#HINT_READ_ONLY} so Hibernate keeps no dirty-checking snapshot.
   * The caller MUST consume the stream inside the active (tasklet) transaction, {@code detach} each
   * entity after reading it, and close the stream (try-with-resources or a {@code close} paragraph)
   * to release the cursor. Iteration order is identical to the legacy ascending {@code TRAN-ID}
   * sort, preserving combine-step output parity.
   *
   * @return a lazily-streamed, ascending-{@code tran_id} view of the entire transaction table
   */
  @QueryHints({
    @QueryHint(name = HINT_FETCH_SIZE, value = "200"),
    @QueryHint(name = HINT_READ_ONLY, value = "true")
  })
  Stream<Transaction> streamAllByOrderByTranIdAsc();

  /**
   * Streams every transaction ascending by {@code tran_card_num} as a forward-only JDBC cursor, the
   * bounded-memory replacement for the unbounded {@code findAll(Sort.by("tranCardNum"))} read used
   * by the {@code TransactionReport} job (legacy {@code CBTRN03C}, detail report grouped by card).
   * Unlike {@code findAll(Sort)} — which materialised the entire 200k-row {@code transaction} table
   * into a {@code List} (the ~513 MB full-buffer pattern flagged at scale) — this finder carries
   * {@link org.hibernate.jpa.HibernateHints#HINT_FETCH_SIZE} so the JDBC driver fetches a small
   * window of rows at a time and {@link org.hibernate.jpa.HibernateHints#HINT_READ_ONLY} so
   * Hibernate keeps no dirty-checking snapshot. The caller MUST consume the stream inside the
   * active (tasklet) transaction, {@code detach} each entity after reading it, and close the stream
   * to release the cursor. Iteration order is identical to the legacy ascending {@code
   * TRAN-CARD-NUM} read, preserving the report's card-grouping and page/account/grand-total parity.
   *
   * @return a lazily-streamed, ascending-{@code tran_card_num} view of the entire transaction table
   */
  @QueryHints({
    @QueryHint(name = HINT_FETCH_SIZE, value = "200"),
    @QueryHint(name = HINT_READ_ONLY, value = "true")
  })
  Stream<Transaction> streamAllByOrderByTranCardNumAsc();
}
