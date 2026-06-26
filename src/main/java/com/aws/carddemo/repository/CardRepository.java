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

import com.aws.carddemo.domain.Card;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link Card} (table {@code card}).
 *
 * <p>Replaces legacy VSAM {@code CARDDATA} KSDS access (copybook {@code CVACT02Y}). Primary key
 * {@code card_num} ({@code String}). The legacy non-unique alternate index on {@code CARD-ACCT-ID}
 * (LISTCAT AXRKP 16, DDL index {@code ix_card_acct_id}) backs {@link #findByCardAcctId(Long)},
 * which mirrors the {@code COCRDLIC} (CCLI) browse of all cards for an account. A missing
 * primary-key record returns {@link java.util.Optional#empty()} from {@code findById} (FILE STATUS
 * {@code '23'}).
 *
 * <p><strong>Keyset (range) browse methods.</strong> The {@code COCRDLIC} card-list screen is a
 * pseudo-conversational STARTBR / READNEXT / READPREV browse of {@code CARDDATA} ascending by
 * {@code card_num}. To preserve that PF7/PF8 paging semantics <em>without</em> materialising the
 * whole table (the unbounded {@code findAll()} read that exhausted the heap under concurrency), the
 * service issues bounded keyset queries: each fetches only {@code page_size + 1} rows (the page
 * plus a single read-ahead "more pages" peek) anchored at the round-tripped boundary key. The
 * {@code GreaterThanEqual} variant reproduces a {@code STARTBR} at the page's first key (refresh /
 * re-anchor); {@code GreaterThan} reproduces the {@code READNEXT} that steps past the previous
 * page's last key (PF8 page-down); {@code LessThan ... OrderBy ... Desc} reproduces the {@code
 * READPREV} walk (PF7 page-up), the caller reversing the descending slice back to ascending. The
 * account-scoped variants additionally constrain on {@code card_acct_id} (alternate index {@code
 * ix_card_acct_id}) for the account-filtered browse. The {@code existsBy ... GreaterThan} probes
 * reproduce the read-ahead that sets the legacy {@code CA-NEXT-PAGE-EXISTS} latch. A {@code
 * Pageable} of {@code PageRequest.of(0, n + 1)} bounds every result to one screen page plus the
 * peek, so memory is O(page) rather than O(table).
 */
@Repository
public interface CardRepository extends JpaRepository<Card, String> {

  /**
   * Returns all cards for the given account id, mirroring the legacy non-unique alternate-index
   * browse on {@code CARD-ACCT-ID}. Returns an empty list when none match (never {@code null}).
   *
   * @param cardAcctId the owning account id ({@code card_acct_id})
   * @return matching cards (possibly empty)
   */
  List<Card> findByCardAcctId(Long cardAcctId);

  // ===============================================================================================
  // Keyset browse — unfiltered (all cards ascending by card_num): COCRDLIC
  // STARTBR/READNEXT/READPREV
  // ===============================================================================================

  /**
   * Forward keyset page anchored inclusively at {@code startKey} (STARTBR GTEQ at the page's first
   * card; also the from-the-top browse when {@code startKey} is the empty string). Returns up to
   * the {@code Pageable} limit of cards with {@code card_num >= startKey} ascending.
   *
   * @param startKey the inclusive lower-bound card number (empty string browses from the top)
   * @param pageable the row limit (typically {@code PageRequest.of(0, pageSize + 1)})
   * @return the bounded ascending page (possibly empty)
   */
  List<Card> findByCardNumGreaterThanEqualOrderByCardNumAsc(String startKey, Pageable pageable);

  /**
   * Forward keyset page anchored exclusively after {@code startKey} (READNEXT past the previous
   * page's last card; PF8 page-down). Returns up to the {@code Pageable} limit of cards with {@code
   * card_num > startKey} ascending.
   *
   * @param startKey the exclusive lower-bound card number (the previous page's last card)
   * @param pageable the row limit (typically {@code PageRequest.of(0, pageSize + 1)})
   * @return the bounded ascending page (possibly empty)
   */
  List<Card> findByCardNumGreaterThanOrderByCardNumAsc(String startKey, Pageable pageable);

  /**
   * Backward keyset page strictly below {@code startKey}, in descending order (READPREV; PF7
   * page-up). The caller reverses the returned descending slice to present it ascending. Returns up
   * to the {@code Pageable} limit of cards with {@code card_num < startKey} descending.
   *
   * @param startKey the exclusive upper-bound card number (the current page's first card)
   * @param pageable the row limit (typically {@code PageRequest.of(0, pageSize + 1)})
   * @return the bounded descending slice (possibly empty); reverse for ascending display
   */
  List<Card> findByCardNumLessThanOrderByCardNumDesc(String startKey, Pageable pageable);

  // ===============================================================================================
  // Keyset browse — account-scoped (cards of one account ascending): ix_card_acct_id + card_num
  // ===============================================================================================

  /**
   * Account-scoped forward keyset page anchored inclusively at {@code startKey}.
   *
   * @param cardAcctId the owning account id
   * @param startKey the inclusive lower-bound card number (empty string browses from the account's
   *     first card)
   * @param pageable the row limit
   * @return the bounded ascending page for the account (possibly empty)
   */
  List<Card> findByCardAcctIdAndCardNumGreaterThanEqualOrderByCardNumAsc(
      Long cardAcctId, String startKey, Pageable pageable);

  /**
   * Account-scoped forward keyset page anchored exclusively after {@code startKey} (PF8).
   *
   * @param cardAcctId the owning account id
   * @param startKey the exclusive lower-bound card number
   * @param pageable the row limit
   * @return the bounded ascending page for the account (possibly empty)
   */
  List<Card> findByCardAcctIdAndCardNumGreaterThanOrderByCardNumAsc(
      Long cardAcctId, String startKey, Pageable pageable);

  /**
   * Account-scoped backward keyset page strictly below {@code startKey}, descending (PF7); reverse
   * for ascending display.
   *
   * @param cardAcctId the owning account id
   * @param startKey the exclusive upper-bound card number
   * @param pageable the row limit
   * @return the bounded descending slice for the account (possibly empty)
   */
  List<Card> findByCardAcctIdAndCardNumLessThanOrderByCardNumDesc(
      Long cardAcctId, String startKey, Pageable pageable);

  // ===============================================================================================
  // Read-ahead "more pages" probes (CA-NEXT-PAGE-EXISTS latch)
  // ===============================================================================================

  /**
   * Whether any card sorts strictly after {@code key} in the unfiltered browse (read-ahead peek for
   * the PF8 next-page latch).
   *
   * @param key the current page's last card number
   * @return {@code true} when a further (unfiltered) page exists
   */
  boolean existsByCardNumGreaterThan(String key);

  /**
   * Whether any card of {@code cardAcctId} sorts strictly after {@code key} (account-scoped
   * read-ahead peek for the PF8 next-page latch).
   *
   * @param cardAcctId the owning account id
   * @param key the current page's last card number
   * @return {@code true} when a further page exists within the account
   */
  boolean existsByCardAcctIdAndCardNumGreaterThan(Long cardAcctId, String key);

  // ===============================================================================================
  // Streaming full scan (CBACT02C sequential read): bounded-memory cursor over the whole table
  // ===============================================================================================

  /**
   * Streams every card ascending by {@code card_num} as a forward-only JDBC cursor, the
   * bounded-memory replacement for the legacy {@code CBACT02C} sequential {@code READ CARDFILE}
   * loop. Unlike {@code findAll(Sort)} — which materialises the entire {@code card} table into a
   * {@code List} before iteration (the full-buffer pattern flagged at scale) — this finder carries
   * {@link org.hibernate.jpa.HibernateHints#HINT_FETCH_SIZE} so the JDBC driver fetches a small
   * window of rows at a time and {@link org.hibernate.jpa.HibernateHints#HINT_READ_ONLY} so
   * Hibernate keeps no dirty-checking snapshot. The caller MUST consume the stream inside the
   * active (tasklet) transaction, {@code detach} each entity after reading it, and close the stream
   * (try-with-resources or a {@code close} paragraph) to release the cursor. Iteration order is
   * identical to the legacy ascending-key scan, preserving {@code CBACT02C} output parity.
   *
   * @return a lazily-streamed, ascending-{@code card_num} view of the entire card master
   */
  @QueryHints({
    @QueryHint(name = HINT_FETCH_SIZE, value = "200"),
    @QueryHint(name = HINT_READ_ONLY, value = "true")
  })
  Stream<Card> streamAllByOrderByCardNumAsc();
}
