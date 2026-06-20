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

import com.aws.carddemo.domain.id.DisclosureGroupId;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest @DataJpaTest} slice
 * integration test for {@link DisclosureGroupRepository}, proving composite-key lookup parity and
 * COBOL decimal-rate fidelity for the {@code disclosure_group} reference table against a real
 * <b>Testcontainers PostgreSQL 16</b> instance.
 *
 * <p><strong>Legacy origin.</strong> The repository replaces random reads of the legacy z/OS VSAM
 * KSDS {@code DISCGRP} (copybook {@code legacy/app/cpy/CVTRA02Y.cpy}, record {@code 01
 * DIS-GROUP-RECORD}, {@code RECLN = 50}). Its three-part key {@code DIS-ACCT-GROUP-ID X(10)} +
 * {@code DIS-TRAN-TYPE-CD X(02)} + {@code DIS-TRAN-CAT-CD 9(04)} (composite {@code KEYLEN} 16)
 * becomes the embeddable composite primary key {@link DisclosureGroupId} over {@code char(10)} +
 * {@code char(2)} + {@code char(4)} columns (Agent Action Plan &sect;0.6.2). The single non-key
 * field {@code DIS-INT-RATE PIC S9(04)V99} becomes {@code dis_int_rate numeric(6,2)} — the annual
 * disclosure interest rate later consumed by the interest-calculation batch {@code CBACT04C}
 * ({@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}). This is a seeded lookup table, never written at
 * runtime, so these tests are strictly read-only and never mutate rows.
 *
 * <p><strong>Fixtures under test.</strong> Every assertion verifies the <em>committed</em> Flyway
 * seed rather than data inserted by the test: {@code V1__schema.sql} creates {@code
 * disclosure_group} and {@code V2__seed_reference_data.sql} loads exactly 51 rows transcribed from
 * the golden fixture {@code legacy/app/data/ASCII/discgrp.txt} — three account-group blocks of 17
 * ({@code A000000000}, {@code DEFAULT}, {@code ZEROAPR}), each covering the same 17 (type,
 * category) combinations. {@code @DataJpaTest} is {@code @Transactional}; Flyway commits the seed
 * on context startup before any test transaction begins, so the rows are visible to every {@code
 * findById} / {@code count} call here, and each method's surrounding transaction rolls back,
 * leaving the seed untouched.
 *
 * <p><strong>Slice wiring.</strong> Inherited verbatim from {@link
 * AbstractRepositoryIntegrationTest}: the {@code @DataJpaTest}, {@code @AutoConfigureTestDatabase}
 * {@code (replace = NONE)} and {@code @ActiveProfiles("test")} annotations, the JVM-singleton
 * {@code @ServiceConnection} PostgreSQL container, and the {@code TestEntityManager} helper. This
 * subclass deliberately re-declares none of them; it only adds the repository under test and the
 * parity assertions.
 *
 * <p><strong>Parity invariants pinned.</strong>
 *
 * <ul>
 *   <li><b>Seed cardinality</b> — exactly 51 disclosure-group rows load (VSAM {@code DISCGRP}
 *       parity);
 *   <li><b>A-vs-DEFAULT rate distinction</b> — the headline contrast of this table: for the same
 *       (type {@code 07}, category {@code 0001}) combination, account group {@code A000000000}
 *       carries {@code 15.00} while {@code DEFAULT} carries {@code 0.00}; the two seeded rates must
 *       differ;
 *   <li><b>Decimal scale fidelity</b> — every rate read back has {@link BigDecimal#scale()} {@code
 *       == 2}, locking the COBOL {@code S9(04)V99} two-decimal contract so the {@code CBACT04C}
 *       interest math stays cent-exact (AAP &sect;0.6.1);
 *   <li><b>Record-not-found</b> — an unseeded composite key yields {@link
 *       java.util.Optional#empty()}, the JPA mapping of COBOL FILE STATUS {@code '23'} (AAP
 *       &sect;0.6.4);
 *   <li><b>bpchar key equality</b> — a natural-length group id such as {@code "ZEROAPR"} matches
 *       the blank-padded {@code char(10)} stored value {@code 'ZEROAPR '} with no manual padding,
 *       mirroring VSAM key-equality semantics (AAP &sect;0.6.2).
 * </ul>
 *
 * <p>{@code BigDecimal} comparisons use AssertJ {@code isEqualByComparingTo} rather than {@link
 * Object#equals(Object)} so that representational differences (for example {@code 0.00} versus
 * {@code 0}) never cause false failures, while a separate {@link BigDecimal#scale()} assertion
 * independently pins the two-decimal scale.
 */
class DisclosureGroupRepositoryIntegrationTest extends AbstractRepositoryIntegrationTest {

  /** Repository under test; injected by the {@code @DataJpaTest} slice context. */
  @Autowired private DisclosureGroupRepository repository;

  /**
   * The {@code V2} seed loads exactly 51 disclosure-group rows (three account-group blocks of 17),
   * matching the row count of the legacy VSAM {@code DISCGRP} fixture {@code
   * app/data/ASCII/discgrp.txt}.
   */
  @Test
  @DisplayName("V2 seed loads exactly 51 disclosure-group rows (DISCGRP VSAM parity)")
  void seedCountMatchesVsam() {
    assertThat(repository.count()).isEqualTo(51L);
  }

  /**
   * Headline rate-parity test: the {@code A000000000} and {@code DEFAULT} account-group blocks are
   * identical for every (type, category) combination <em>except</em> ({@code 07}, {@code 0001}),
   * where {@code A000000000} discloses {@code 15.00} but {@code DEFAULT} discloses {@code 0.00}.
   * Both rates are read back at scale 2 (COBOL {@code S9(04)V99} fidelity), and the two values are
   * asserted to be numerically unequal to lock the distinction that the interest-calculation batch
   * depends upon.
   */
  @Test
  @DisplayName("Block A vs DEFAULT at (07,0001): 15.00 vs 0.00, the headline rate-parity contrast")
  void blockARateDiffersFromDefault_parity() {
    var a = repository.findById(new DisclosureGroupId("A000000000", "07", "0001"));
    assertThat(a).isPresent();
    BigDecimal aRate = a.get().getDisIntRate();
    assertThat(aRate).isEqualByComparingTo(new BigDecimal("15.00"));
    assertThat(aRate.scale()).isEqualTo(2);

    var d = repository.findById(new DisclosureGroupId("DEFAULT", "07", "0001"));
    assertThat(d).isPresent();
    BigDecimal dRate = d.get().getDisIntRate();
    assertThat(dRate).isEqualByComparingTo(new BigDecimal("0.00"));
    assertThat(dRate.scale()).isEqualTo(2);

    // Lock the A-vs-DEFAULT distinction: the two seeded rates must NOT be numerically equal.
    assertThat(aRate).isNotEqualByComparingTo(dRate);
  }

  /**
   * Two further {@code A000000000} rows confirm that non-zero rates beyond the headline case are
   * seeded faithfully: ({@code 01}, {@code 0001}) discloses {@code 15.00} and ({@code 01}, {@code
   * 0002}) discloses {@code 25.00}, each read back at scale 2.
   */
  @Test
  @DisplayName("Additional Block A rates: (01,0001)=15.00 and (01,0002)=25.00, both scale 2")
  void additionalSeededRates() {
    var first = repository.findById(new DisclosureGroupId("A000000000", "01", "0001"));
    assertThat(first).isPresent();
    assertThat(first.get().getDisIntRate()).isEqualByComparingTo(new BigDecimal("15.00"));
    assertThat(first.get().getDisIntRate().scale()).isEqualTo(2);

    var second = repository.findById(new DisclosureGroupId("A000000000", "01", "0002"));
    assertThat(second).isPresent();
    assertThat(second.get().getDisIntRate()).isEqualByComparingTo(new BigDecimal("25.00"));
    assertThat(second.get().getDisIntRate().scale()).isEqualTo(2);
  }

  /**
   * A primary-key lookup for a composite key that is not seeded returns {@link
   * java.util.Optional#empty()} rather than throwing — the Spring Data JPA equivalent of COBOL FILE
   * STATUS {@code '23'} (record not found), per AAP &sect;0.6.4.
   */
  @Test
  @DisplayName("findById for an absent composite key returns empty (COBOL FILE STATUS '23' parity)")
  void findByIdAbsentReturnsEmpty_fileStatus23Parity() {
    assertThat(repository.findById(new DisclosureGroupId("ZZZZZZZZZZ", "99", "9999"))).isEmpty();
  }

  /**
   * The {@code char(n)} composite-key columns (PostgreSQL {@code bpchar}) match a natural-length
   * group id against the blank-padded stored value with no manual right-padding: the
   * seven-character {@code "ZEROAPR"} resolves the row stored under {@code 'ZEROAPR '}, preserving
   * VSAM key equality semantics (AAP &sect;0.6.2). The resolved rate is {@code 0.00} at scale 2.
   */
  @Test
  @DisplayName(
      "char(n) composite key ignores trailing spaces: 'ZEROAPR' matches stored 'ZEROAPR   '")
  void charKeyTrailingSpaceInsensitive() {
    var z = repository.findById(new DisclosureGroupId("ZEROAPR", "07", "0001"));
    assertThat(z).isPresent();
    assertThat(z.get().getDisIntRate()).isEqualByComparingTo(new BigDecimal("0.00"));
    assertThat(z.get().getDisIntRate().scale()).isEqualTo(2);
  }
}
