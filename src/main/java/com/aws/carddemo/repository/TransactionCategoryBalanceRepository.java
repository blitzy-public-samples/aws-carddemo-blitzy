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

import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.domain.id.TransactionCategoryBalanceId;
import jakarta.persistence.QueryHint;
import java.util.stream.Stream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link TransactionCategoryBalance} (table {@code
 * tran_cat_balance}).
 *
 * <p>Replaces legacy VSAM {@code TCATBALF} KSDS access (copybook {@code CVTRA01Y}), opened {@code
 * I-O} in {@code CBTRN02C}. Composite primary key {@link TransactionCategoryBalanceId} (KEYLEN 17).
 * The legacy {@code 2700-UPDATE-TCATBAL} find-or-create (upsert) — read accepts FILE STATUS {@code
 * '00' OR '23'}, then WRITE if absent / REWRITE if present — is implemented in the SERVICE layer as
 * {@code findById(...)} (returns {@link java.util.Optional}) followed by {@code save(...)}. This
 * repository exposes the standard {@code JpaRepository} operations plus a bounded-memory streaming
 * scan for the batch jobs.
 */
@Repository
public interface TransactionCategoryBalanceRepository
    extends JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId> {

  /**
   * Streams every category-balance row ascending by its full composite key ({@code
   * trancat_acct_id}, then {@code trancat_type_cd}, then {@code trancat_cd}) as a forward-only JDBC
   * cursor, the bounded-memory replacement for the unbounded {@code
   * findAll(Sort.by("id.trancat*"))} used by the {@code InterestCalculation} and {@code
   * CategoryBalanceReport} jobs. Unlike {@code findAll(Sort)} — which materialises the entire
   * {@code tran_cat_balance} table into a {@code List} before iteration (the full-buffer pattern
   * flagged at scale) — this finder carries {@link
   * org.hibernate.jpa.HibernateHints#HINT_FETCH_SIZE} so the JDBC driver fetches a small window of
   * rows at a time and {@link org.hibernate.jpa.HibernateHints#HINT_READ_ONLY} so Hibernate keeps
   * no dirty-checking snapshot. A composite-key ordering cannot be expressed by a derived query
   * method name, so the ascending order is given explicitly by the JPQL {@code ORDER BY}. The
   * caller MUST consume the stream inside the active (tasklet) transaction, {@code detach} each
   * read entity after reading it (the read-only iterated record only — never {@code clear()}, which
   * would also detach pending account/transaction writes in the interest job), and close the stream
   * (try-with-resources or a {@code close} paragraph) to release the cursor. Iteration order is
   * identical to the legacy ascending composite-key scan, preserving output parity.
   *
   * @return a lazily-streamed, ascending composite-key view of the entire category-balance table
   */
  @QueryHints({
    @QueryHint(name = HINT_FETCH_SIZE, value = "200"),
    @QueryHint(name = HINT_READ_ONLY, value = "true")
  })
  @Query(
      "SELECT t FROM TransactionCategoryBalance t"
          + " ORDER BY t.id.trancatAcctId ASC, t.id.trancatTypeCd ASC, t.id.trancatCd ASC")
  Stream<TransactionCategoryBalance> streamAllByOrderByIdAsc();
}
