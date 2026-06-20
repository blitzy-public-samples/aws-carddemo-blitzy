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

import com.aws.carddemo.domain.CardXref;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest @DataJpaTest} slice
 * integration test for {@link CardXrefRepository}, the Spring Data JPA replacement for the legacy
 * VSAM {@code CARDXREF} KSDS (copybook {@code legacy/app/cpy/CVACT03Y.cpy} — {@code 01
 * CARD-XREF-RECORD}, record length 50; batch reader {@code legacy/app/cbl/CBACT03C.cbl}).
 *
 * <p>This class extends {@link AbstractRepositoryIntegrationTest} and therefore inherits the entire
 * slice configuration — the {@code @DataJpaTest} / {@code @AutoConfigureTestDatabase(NONE)} /
 * {@code @ActiveProfiles("test")} annotations, the shared JVM-singleton Testcontainers PostgreSQL
 * 16 database, the {@link org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager}
 * and the {@code flushAndClear()} round-trip helper. None of that wiring is re-declared here.
 *
 * <p>The {@code card_xref} table is created (empty) by the {@code V1__schema.sql} Flyway migration;
 * it holds no reference data, so {@code V2__seed_reference_data.sql} seeds no rows. Because
 * {@code @DataJpaTest} is {@code @Transactional} every test method rolls back, so each test builds
 * and persists — through the production repository under test — exactly the rows it needs in
 * isolation, then forces a database round-trip with {@code flushAndClear()} so the subsequent query
 * re-reads from PostgreSQL rather than the first-level cache.
 *
 * <p>These tests pin two behavioral-parity guarantees from the Agent Action Plan:
 *
 * <ul>
 *   <li><b>Non-unique alternate-index parity</b> (AAP &sect;0.6.2) — {@link
 *       CardXrefRepository#findByXrefAcctId(Long)} is backed by the non-unique index {@code
 *       ix_card_xref_acct_id} (legacy VSAM {@code CARDXREF} AIX on {@code XREF-ACCT-ID}: KEYLEN 11,
 *       AXRKP 25, NONUNIQKEY), so one account id may resolve to many cross-reference rows and the
 *       finder returns every match (or an empty list, never {@code null});
 *   <li><b>Primary-key / FILE STATUS parity</b> (AAP &sect;0.6.4) — {@code findById} resolves a row
 *       by its fixed-width {@code char(16)} primary key {@code xref_card_num} ({@code XREF-CARD-NUM
 *       PIC X(16)}), and a key that matches no row yields {@link Optional#empty()}, mirroring the
 *       legacy {@code '23'} record-not-found FILE STATUS.
 * </ul>
 */
class CardXrefRepositoryIntegrationTest extends AbstractRepositoryIntegrationTest {

  /** Repository under test — the production {@link CardXrefRepository} bean, wired by the slice. */
  @Autowired private CardXrefRepository repository;

  /**
   * Exercises the non-unique alternate index {@code ix_card_xref_acct_id}: two cross-references for
   * the same account, plus one for a different account, are persisted; querying by the shared
   * account id must return exactly the two matching rows (legacy VSAM {@code CARDXREF} AIX on
   * {@code XREF-ACCT-ID}, AAP &sect;0.6.2).
   */
  @Test
  @DisplayName("findByXrefAcctId returns only matching rows (non-unique alt-index parity)")
  void findByXrefAcctIdReturnsOnlyMatchingAccount_altIndexParity() {
    repository.save(buildXref("0000000000000001", 900000001L, 90000000001L));
    repository.save(buildXref("0000000000000002", 900000002L, 90000000001L));
    repository.save(buildXref("0000000000000003", 900000003L, 90000000002L));
    flushAndClear();

    // Validates the non-unique ix_card_xref_acct_id (legacy VSAM CARDXREF AIX on XREF-ACCT-ID, AAP
    // section 0.6.2): a single account id maps to many xref rows, so the finder returns them all.
    List<CardXref> acct1 = repository.findByXrefAcctId(90000000001L);

    assertThat(acct1).hasSize(2);
    assertThat(acct1).allSatisfy(x -> assertThat(x.getXrefAcctId()).isEqualTo(90000000001L));
    assertThat(acct1)
        .extracting(x -> x.getXrefCardNum().trim())
        .containsExactlyInAnyOrder("0000000000000001", "0000000000000002");
  }

  /**
   * Confirms the alternate-index finder returns an empty list (never {@code null}) when no row
   * matches. Rows for other accounts are present, so the empty result proves the query filters by
   * account id rather than merely observing an empty table.
   */
  @Test
  @DisplayName("findByXrefAcctId returns an empty list (never null) when no row matches")
  void findByXrefAcctIdNoMatchReturnsEmptyList() {
    repository.save(buildXref("0000000000000001", 900000001L, 90000000001L));
    repository.save(buildXref("0000000000000002", 900000002L, 90000000002L));
    flushAndClear();

    assertThat(repository.findByXrefAcctId(90000000009L)).isEmpty();
  }

  /**
   * Verifies a primary-key read: a row saved under {@code char(16)} key {@code 0000000000000001} is
   * resolved by {@code findById}, and its account id round-trips intact.
   */
  @Test
  @DisplayName("findById resolves a card xref by its char(16) primary key")
  void findByIdPkLookup() {
    repository.save(buildXref("0000000000000001", 900000001L, 90000000001L));
    flushAndClear();

    Optional<CardXref> found = repository.findById("0000000000000001");

    assertThat(found).isPresent();
    assertThat(found.get().getXrefAcctId()).isEqualTo(90000000001L);
  }

  /**
   * Pins FILE STATUS {@code '23'} (record-not-found) parity (AAP &sect;0.6.4): a known row is
   * present, yet a lookup of a genuinely absent primary key returns {@link Optional#empty()} rather
   * than throwing or matching the existing row.
   */
  @Test
  @DisplayName("findById returns empty for an absent key (FILE STATUS '23' parity)")
  void findByIdAbsentReturnsEmpty_fileStatus23Parity() {
    repository.save(buildXref("0000000000000001", 900000001L, 90000000001L));
    flushAndClear();

    assertThat(repository.findById("9999999999999999")).isEmpty();
  }

  /**
   * Builds a fully-populated, transient {@link CardXref}. Every column of {@code card_xref} is
   * {@code NOT NULL} ({@code xref_card_num}, {@code xref_cust_id}, {@code xref_acct_id}), so all
   * three are set; the trailing COBOL {@code FILLER PIC X(14)} carries no business meaning and is
   * intentionally not modeled by the entity.
   *
   * @param cardNum the 16-character primary key ({@code XREF-CARD-NUM PIC X(16)})
   * @param custId the customer id ({@code XREF-CUST-ID PIC 9(09)}; fits {@code numeric(9)})
   * @param acctId the account id ({@code XREF-ACCT-ID PIC 9(11)}; alt-indexed, fits {@code
   *     numeric(11)})
   * @return a new, unsaved entity ready to persist
   */
  private CardXref buildXref(String cardNum, long custId, long acctId) {
    CardXref xref = new CardXref();
    xref.setXrefCardNum(cardNum);
    xref.setXrefCustId(custId);
    xref.setXrefAcctId(acctId);
    return xref;
  }
}
