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

import com.aws.carddemo.domain.Account;
import jakarta.persistence.QueryHint;
import java.util.stream.Stream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link Account} (table {@code account}).
 *
 * <p>Replaces legacy VSAM {@code ACCTDATA} KSDS access (copybook {@code CVACT01Y}; opened {@code
 * I-O} and {@code REWRITE}n in {@code CBTRN02C} 2800-UPDATE-ACCOUNT-REC). Primary key {@code
 * acct_id} ({@code Long}). A missing record returns {@link java.util.Optional#empty()} from {@code
 * findById} (COBOL FILE STATUS {@code '23'}). The READ-for-update/REWRITE optimistic-locking parity
 * is handled by the service layer (the entity intentionally has no {@code @Version} column).
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

  /**
   * Streams every account ascending by {@code acct_id} as a forward-only JDBC cursor, the
   * bounded-memory replacement for the legacy {@code CBACT01C} sequential {@code READ ACCTFILE}
   * loop. Unlike {@code findAll(Sort)} — which materialises the entire {@code account} table into a
   * {@code List} before iteration (the full-buffer pattern flagged at scale) — this finder carries
   * {@link org.hibernate.jpa.HibernateHints#HINT_FETCH_SIZE} so the JDBC driver fetches a small
   * window of rows at a time and {@link org.hibernate.jpa.HibernateHints#HINT_READ_ONLY} so
   * Hibernate keeps no dirty-checking snapshot. The caller MUST consume the stream inside the
   * active (tasklet) transaction, {@code detach} each entity after reading it, and close the stream
   * (try-with-resources or a {@code close} paragraph) to release the cursor. Iteration order is
   * identical to the legacy ascending-key scan, preserving {@code CBACT01C} output parity.
   *
   * @return a lazily-streamed, ascending-{@code acct_id} view of the entire account master
   */
  @QueryHints({
    @QueryHint(name = HINT_FETCH_SIZE, value = "200"),
    @QueryHint(name = HINT_READ_ONLY, value = "true")
  })
  Stream<Account> streamAllByOrderByAcctIdAsc();
}
