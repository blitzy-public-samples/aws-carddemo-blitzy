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

import com.aws.carddemo.domain.CardXref;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link CardXref} (table {@code card_xref}).
 *
 * <p>Replaces legacy VSAM {@code CARDXREF} KSDS access (copybook {@code CVACT03Y}). Primary key
 * {@code xref_card_num} ({@code String}) — random read by card number (e.g. {@code CBTRN02C}). The
 * non-unique alternate index on {@code XREF-ACCT-ID} (LISTCAT AXRKP 25, DDL index {@code
 * ix_card_xref_acct_id}) backs {@link #findByXrefAcctId(Long)}, mirroring the {@code COACTVWC}
 * (CAVW) read-by-account path. A missing primary-key record returns {@link
 * java.util.Optional#empty()} from {@code findById} (FILE STATUS {@code '23'}).
 */
@Repository
public interface CardXrefRepository extends JpaRepository<CardXref, String> {

  /**
   * Returns all card cross-reference rows for the given account id, mirroring the legacy non-unique
   * alternate-index path on {@code XREF-ACCT-ID}. Returns an empty list when none match (never
   * {@code null}); callers needing a single row take the first element.
   *
   * @param xrefAcctId the account id ({@code xref_acct_id})
   * @return matching cross-reference rows (possibly empty)
   */
  List<CardXref> findByXrefAcctId(Long xrefAcctId);

  /**
   * Streams every cross-reference row ascending by {@code xref_card_num} as a forward-only JDBC
   * cursor, the bounded-memory replacement for the legacy {@code CBACT03C} sequential {@code READ
   * XREFFILE} loop (and the {@code FileIoService} cross-reference cursor). Unlike {@code
   * findAll(Sort)} — which materialises the entire {@code card_xref} table into a {@code List}
   * before iteration (the full-buffer pattern flagged at scale) — this finder carries {@link
   * org.hibernate.jpa.HibernateHints#HINT_FETCH_SIZE} so the JDBC driver fetches a small window of
   * rows at a time and {@link org.hibernate.jpa.HibernateHints#HINT_READ_ONLY} so Hibernate keeps
   * no dirty-checking snapshot. The caller MUST consume the stream inside the active (tasklet)
   * transaction, {@code detach} each entity after reading it, and close the stream
   * (try-with-resources or a {@code close} paragraph) to release the cursor. Iteration order is
   * identical to the legacy ascending-key scan, preserving {@code CBACT03C} output parity.
   *
   * @return a lazily-streamed, ascending-{@code xref_card_num} view of the entire cross-reference
   *     store
   */
  @QueryHints({
    @QueryHint(name = HINT_FETCH_SIZE, value = "200"),
    @QueryHint(name = HINT_READ_ONLY, value = "true")
  })
  Stream<CardXref> streamAllByOrderByXrefCardNumAsc();
}
