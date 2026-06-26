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

import com.aws.carddemo.domain.DailyTransaction;
import jakarta.persistence.QueryHint;
import java.util.stream.Stream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link DailyTransaction} (table {@code daily_transaction}).
 *
 * <p>Replaces the legacy sequential {@code DALYTRAN} file consumed by the posting batch ({@code
 * CBTRN02C} 1000-DALYTRAN-GET-NEXT). Primary key {@code dalytran_id} ({@code String}); this table
 * has no alternate index. Sequential consumption is performed by the Spring Batch reader; {@code
 * findAll}/{@code findById} (returning {@link java.util.Optional}) cover lookups, with a missing
 * record mapping to {@code Optional.empty()} (FILE STATUS {@code '23'}).
 */
@Repository
public interface DailyTransactionRepository extends JpaRepository<DailyTransaction, String> {

  /**
   * Streams every daily transaction ascending by {@code dalytran_id} as a forward-only JDBC cursor,
   * the bounded-memory replacement for the unbounded {@code findAll(Sort.by("dalytranId"))} used by
   * the {@code DailyTransactionPost} job (legacy {@code CBTRN01C} sequential {@code DALYTRAN}
   * read). Unlike {@code findAll(Sort)} — which materialises the entire {@code daily_transaction}
   * table into a {@code List} before iteration (the full-buffer pattern flagged at scale) — this
   * finder carries {@link org.hibernate.jpa.HibernateHints#HINT_FETCH_SIZE} so the JDBC driver
   * fetches a small window of rows at a time and {@link
   * org.hibernate.jpa.HibernateHints#HINT_READ_ONLY} so Hibernate keeps no dirty-checking snapshot.
   * The caller MUST consume the stream inside the active (tasklet) transaction, {@code detach} each
   * read entity after reading it, and close the stream (try-with-resources or a {@code close}
   * paragraph) to release the cursor. Iteration order is identical to the legacy ascending
   * sequential read, preserving posting-order parity.
   *
   * @return a lazily-streamed, ascending-{@code dalytran_id} view of the entire daily-transaction
   *     file
   */
  @QueryHints({
    @QueryHint(name = HINT_FETCH_SIZE, value = "200"),
    @QueryHint(name = HINT_READ_ONLY, value = "true")
  })
  Stream<DailyTransaction> streamAllByOrderByDalytranIdAsc();
}
