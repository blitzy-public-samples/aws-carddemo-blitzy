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

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.domain.Card;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * {@code @DataJpaTest} slice integration test for {@link CardRepository}, the Spring Data JPA
 * replacement for the legacy VSAM {@code CARDDATA} KSDS (copybook {@code app/cpy/CVACT02Y.cpy};
 * batch reader {@code app/cbl/CBACT02C.cbl}). It runs against a real Testcontainers PostgreSQL 16
 * instance and the Flyway-managed {@code card} table, inheriting all slice wiring — the shared
 * container, {@code TestEntityManager}, the {@code "test"} profile and transaction-per-method
 * rollback — from {@link AbstractRepositoryIntegrationTest}.
 *
 * <p>The {@code card} table is empty in the slice (it is not one of the seeded reference tables),
 * so every test builds and persists its own rows through the helper {@link #buildCard(String,
 * long)} and then forces a database round-trip via the inherited {@code flushAndClear()}. Clearing
 * the persistence context guarantees each finder query is resolved by PostgreSQL — and therefore by
 * the real primary key and alternate index — rather than by the first-level persistence-context
 * cache.
 *
 * <p>The behavioural parity guarantees pinned here (Agent Action Plan sections 0.6.2 and 0.6.4)
 * are:
 *
 * <ul>
 *   <li><b>Non-unique alternate-index parity.</b> {@link CardRepository#findByCardAcctId(Long)} is
 *       backed by the non-unique index {@code ix_card_acct_id} on {@code card_acct_id} (LISTCAT
 *       {@code CARDDATA} AIX, KEYLEN 11, AXRKP 16). Multiple cards may share one account id,
 *       exactly as the legacy alternate index allowed, and the finder must return precisely the
 *       matching subset.
 *   <li><b>Empty-result, never-null contract.</b> The finder returns an empty {@link List} (not
 *       {@code null}) when no card matches the requested account.
 *   <li><b>Primary-key lookup parity.</b> {@code findById} resolves a card by its {@code char(16)}
 *       {@code CARD-NUM} primary key (VSAM {@code CARDDATA} KEYLEN 16).
 *   <li><b>FILE STATUS {@code '23'} parity.</b> A primary-key miss yields {@link Optional#empty()},
 *       mirroring the COBOL record-not-found status (Agent Action Plan section 0.6.4).
 * </ul>
 */
@DisplayName("CardRepository slice integration test (CARDDATA parity)")
class CardRepositoryIntegrationTest extends AbstractRepositoryIntegrationTest {

  @Autowired private CardRepository repository;

  /**
   * Builds a fully-populated, persistable {@link Card}. Every column of the {@code card} table is
   * {@code NOT NULL}, so all six modeled fields are set. The CVV is stored as a {@code char(3)}
   * string ({@code "747"}-style values keep any mandatory leading zeros, which an integer mapping
   * would silently drop), the expiration date is an exactly 10-character {@code char(10)} value,
   * and the misspelled-but-authoritative {@code cardExpiraionDate} accessor (copybook field {@code
   * CARD-EXPIRAION-DATE}) is used verbatim for source traceability.
   *
   * @param cardNum the 16-character {@code CARD-NUM} primary key
   * @param acctId the owning account id stored in the alternate-indexed {@code card_acct_id} column
   * @return a valid, transient {@link Card} ready to be saved
   */
  private Card buildCard(String cardNum, long acctId) {
    Card card = new Card();
    card.setCardNum(cardNum);
    card.setCardAcctId(acctId);
    card.setCardCvvCd("747");
    card.setCardEmbossedName("JOHN Q CARDHOLDER");
    card.setCardExpiraionDate("2025-12-31");
    card.setCardActiveStatus("Y");
    return card;
  }

  @Test
  @DisplayName(
      "findByCardAcctId returns only the matching account's cards (non-unique ix_card_acct_id"
          + " alternate-index parity)")
  void findByCardAcctIdReturnsOnlyMatchingAccountCards_altIndexParity() {
    // Two cards share account 90000000001; a third belongs to a different account. The legacy VSAM
    // CARDDATA alternate index on CARD-ACCT-ID is NON-UNIQUE, so multiple cards may map to one
    // account; ix_card_acct_id (V1__schema.sql) reproduces that. The finder must therefore return
    // exactly the two cards for account 90000000001 (AAP section 0.6.2, CARDDATA AIX).
    repository.save(buildCard("0000000000000001", 90000000001L));
    repository.save(buildCard("0000000000000002", 90000000001L));
    repository.save(buildCard("0000000000000003", 90000000002L));
    flushAndClear();

    List<Card> acct1 = repository.findByCardAcctId(90000000001L);

    assertThat(acct1).hasSize(2);
    assertThat(acct1).allSatisfy(c -> assertThat(c.getCardAcctId()).isEqualTo(90000000001L));
    assertThat(acct1)
        .extracting(c -> c.getCardNum().trim())
        .containsExactlyInAnyOrder("0000000000000001", "0000000000000002");
  }

  @Test
  @DisplayName(
      "findByCardAcctId returns an empty list (never null) when no card matches the account")
  void findByCardAcctIdNoMatchReturnsEmptyList() {
    // Persist cards for an unrelated account so the table is non-empty; the finder must still
    // return an empty (never null) list for an account that owns no cards.
    repository.save(buildCard("0000000000000001", 90000000001L));
    repository.save(buildCard("0000000000000002", 90000000001L));
    flushAndClear();

    assertThat(repository.findByCardAcctId(90000000009L)).isEmpty();
  }

  @Test
  @DisplayName("findById resolves a card by its char(16) CARD-NUM primary key")
  void findByIdPkLookup() {
    repository.save(buildCard("0000000000000001", 90000000001L));
    flushAndClear();

    Optional<Card> found = repository.findById("0000000000000001");

    assertThat(found).isPresent();
    assertThat(found.get().getCardAcctId()).isEqualTo(90000000001L);
  }

  @Test
  @DisplayName(
      "findById returns Optional.empty for an absent primary key (FILE STATUS '23' parity)")
  void findByIdAbsentReturnsEmpty_fileStatus23Parity() {
    // A different card exists, proving the empty result is a genuine primary-key miss rather than
    // an empty table (AAP section 0.6.4, FILE STATUS '23').
    repository.save(buildCard("0000000000000001", 90000000001L));
    flushAndClear();

    assertThat(repository.findById("9999999999999999")).isEmpty();
  }

  // ===============================================================================================
  // Keyset browse parity (F-1 fix). The unbounded findAll() browse that materialised the whole
  // card table on every page request is replaced by bounded keyset pages. These tests prove,
  // against a real PostgreSQL char(16) primary key, that: (1) the Pageable limit bounds the read to
  // one page (the F-1 OOM remedy); (2) the bpchar CARD-NUM ordering matches the ascending VSAM-key
  // browse and Java compareTo; (3) the empty start key positions at LOW-VALUES to browse from the
  // top (STARTBR); (4) >= is inclusive and > is exclusive (READNEXT after the boundary record);
  // (5) the < descending query reproduces READPREV; and (6) the exists probe reproduces the PF8
  // "is there a next page" peek (COCRDLIC). Account-filtered variants confine the browse to one
  // account through ix_card_acct_id.
  // ===============================================================================================

  @Test
  @DisplayName("keyset forward from the empty start key browses from the top, bounded by the limit")
  void keysetForwardFromTop_boundedByLimit() {
    for (int i = 1; i <= 5; i++) {
      repository.save(buildCard(String.format("%016d", i), 90000000001L));
    }
    flushAndClear();

    // STARTBR from LOW-VALUES (empty key) with a two-row page: PostgreSQL returns exactly the first
    // two cards ascending, NOT the whole table -- the bounded read that remedies the F-1 OOM.
    List<Card> page =
        repository.findByCardNumGreaterThanEqualOrderByCardNumAsc("", PageRequest.of(0, 2));

    assertThat(page).hasSize(2);
    assertThat(page)
        .extracting(c -> c.getCardNum().trim())
        .containsExactly("0000000000000001", "0000000000000002");
  }

  @Test
  @DisplayName("keyset forward GREATER-THAN-EQUAL includes the start key; GREATER-THAN excludes it")
  void keysetForwardInclusiveVsExclusive() {
    for (int i = 1; i <= 5; i++) {
      repository.save(buildCard(String.format("%016d", i), 90000000001L));
    }
    flushAndClear();
    String key = "0000000000000003";

    List<Card> gteq =
        repository.findByCardNumGreaterThanEqualOrderByCardNumAsc(key, PageRequest.of(0, 10));
    List<Card> gt =
        repository.findByCardNumGreaterThanOrderByCardNumAsc(key, PageRequest.of(0, 10));

    assertThat(gteq)
        .extracting(c -> c.getCardNum().trim())
        .containsExactly("0000000000000003", "0000000000000004", "0000000000000005");
    assertThat(gt)
        .extracting(c -> c.getCardNum().trim())
        .containsExactly("0000000000000004", "0000000000000005");
  }

  @Test
  @DisplayName("keyset backward (LESS-THAN) returns descending rows below the cursor, bounded")
  void keysetBackwardDescending_boundedByLimit() {
    for (int i = 1; i <= 5; i++) {
      repository.save(buildCard(String.format("%016d", i), 90000000001L));
    }
    flushAndClear();

    // READPREV below card 5 with a two-row look-back: the two closest-below rows, descending.
    List<Card> back =
        repository.findByCardNumLessThanOrderByCardNumDesc(
            "0000000000000005", PageRequest.of(0, 2));

    assertThat(back)
        .extracting(c -> c.getCardNum().trim())
        .containsExactly("0000000000000004", "0000000000000003");
  }

  @Test
  @DisplayName("existsByCardNumGreaterThan reproduces the PF8 next-page peek")
  void existsByCardNumGreaterThanPeek() {
    repository.save(buildCard("0000000000000001", 90000000001L));
    repository.save(buildCard("0000000000000002", 90000000001L));
    flushAndClear();

    assertThat(repository.existsByCardNumGreaterThan("0000000000000001")).isTrue();
    assertThat(repository.existsByCardNumGreaterThan("0000000000000002")).isFalse();
  }

  @Test
  @DisplayName("account-filtered keyset pages confine the browse to one account (ix_card_acct_id)")
  void keysetAccountFiltered_confinedToAccount() {
    // Two accounts interleaved by card number; the account-filtered keyset must return only the
    // requested account's cards (ascending, bounded), and the exists probe must respect the filter.
    repository.save(buildCard("0000000000000001", 90000000001L));
    repository.save(buildCard("0000000000000002", 90000000002L));
    repository.save(buildCard("0000000000000003", 90000000001L));
    repository.save(buildCard("0000000000000004", 90000000001L));
    flushAndClear();

    List<Card> firstTwo =
        repository.findByCardAcctIdAndCardNumGreaterThanEqualOrderByCardNumAsc(
            90000000001L, "", PageRequest.of(0, 2));
    assertThat(firstTwo)
        .extracting(c -> c.getCardNum().trim())
        .containsExactly("0000000000000001", "0000000000000003");

    List<Card> afterFirst =
        repository.findByCardAcctIdAndCardNumGreaterThanOrderByCardNumAsc(
            90000000001L, "0000000000000001", PageRequest.of(0, 10));
    assertThat(afterFirst)
        .extracting(c -> c.getCardNum().trim())
        .containsExactly("0000000000000003", "0000000000000004");

    List<Card> back =
        repository.findByCardAcctIdAndCardNumLessThanOrderByCardNumDesc(
            90000000001L, "0000000000000004", PageRequest.of(0, 10));
    assertThat(back)
        .extracting(c -> c.getCardNum().trim())
        .containsExactly("0000000000000003", "0000000000000001");

    assertThat(repository.existsByCardAcctIdAndCardNumGreaterThan(90000000001L, "0000000000000003"))
        .isTrue();
    assertThat(repository.existsByCardAcctIdAndCardNumGreaterThan(90000000001L, "0000000000000004"))
        .isFalse();
  }
}
