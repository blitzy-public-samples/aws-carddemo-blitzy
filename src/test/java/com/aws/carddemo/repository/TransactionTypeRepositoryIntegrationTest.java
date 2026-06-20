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

import com.aws.carddemo.domain.TransactionType;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest @DataJpaTest} slice
 * integration test for {@link TransactionTypeRepository}, proving primary-key lookup parity for the
 * {@code tran_type} reference table against a real <b>Testcontainers PostgreSQL 16</b> instance.
 *
 * <p><strong>Legacy origin.</strong> The repository replaces random reads of the legacy z/OS VSAM
 * KSDS {@code TRANTYPE} (copybook {@code legacy/app/cpy/CVTRA03Y.cpy}, record {@code 01
 * TRAN-TYPE-RECORD}, {@code RECLN = 60}), whose two-character {@code TRAN-TYPE PIC X(02)} key
 * becomes the PostgreSQL primary key {@code tran_type char(2)} (Agent Action Plan &sect;0.6.2). The
 * lookup table is seeded — not written at runtime — so these tests are strictly read-only and never
 * mutate rows.
 *
 * <p><strong>Fixtures under test.</strong> All assertions verify the <em>committed</em> Flyway seed
 * rather than data inserted by the test: {@code V1__schema.sql} creates {@code tran_type} and
 * {@code V2__seed_reference_data.sql} loads exactly the seven rows transcribed from the golden
 * fixture {@code legacy/app/data/ASCII/trantype.txt} ({@code 01 = Purchase} … {@code 07 =
 * Adjustment}). {@code @DataJpaTest} is {@code @Transactional}; Flyway commits the seed on context
 * startup before any test transaction begins, so the rows are visible to every {@code findById} /
 * {@code count} call here, and each method's surrounding transaction rolls back, leaving the seed
 * untouched.
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
 *   <li><b>Seed cardinality</b> — exactly seven transaction types load (VSAM {@code TRANTYPE}
 *       parity);
 *   <li><b>Key lookup</b> — {@code findById} returns the seeded row; the {@code char(50)}
 *       description is blank-padded on read, so text is compared after {@link String#trim()};
 *   <li><b>Record-not-found</b> — an unknown code yields {@link Optional#empty()}, the JPA mapping
 *       of COBOL FILE STATUS {@code '23'} (AAP &sect;0.6.4);
 *   <li><b>bpchar key equality</b> — a two-character code is matched against the {@code char(2)}
 *       primary key with no manual padding, mirroring VSAM equality semantics (AAP &sect;0.6.2).
 * </ul>
 */
class TransactionTypeRepositoryIntegrationTest extends AbstractRepositoryIntegrationTest {

  /** Repository under test; injected by the {@code @DataJpaTest} slice context. */
  @Autowired private TransactionTypeRepository repository;

  /**
   * The {@code V2} seed loads exactly seven transaction types, matching the row count of the legacy
   * VSAM {@code TRANTYPE} fixture {@code app/data/ASCII/trantype.txt}.
   */
  @Test
  @DisplayName("V2 seed loads exactly seven transaction types (TRANTYPE VSAM parity)")
  void seedCountMatchesVsam() {
    assertThat(repository.count()).isEqualTo(7L);
  }

  /**
   * {@code findById} resolves a seeded code to its row, and the blank-padded {@code char(50)}
   * description equals the expected text once trimmed. Both ends of the key range ({@code "01"} and
   * {@code "07"}) are exercised to confirm the full seed is reachable by primary key.
   */
  @Test
  @DisplayName("findById returns the seeded transaction type with its trimmed description")
  void findByIdReturnsSeededType() {
    Optional<TransactionType> purchase = repository.findById("01");
    assertThat(purchase).isPresent();
    assertThat(purchase.get().getTranTypeDesc().trim()).isEqualTo("Purchase");

    Optional<TransactionType> adjustment = repository.findById("07");
    assertThat(adjustment).isPresent();
    assertThat(adjustment.get().getTranTypeDesc().trim()).isEqualTo("Adjustment");
  }

  /**
   * A primary-key lookup for a code that is not seeded returns {@link Optional#empty()} rather than
   * throwing — the Spring Data JPA equivalent of COBOL FILE STATUS {@code '23'} (record not found),
   * per AAP &sect;0.6.4.
   */
  @Test
  @DisplayName("findById for an unknown code returns empty (COBOL FILE STATUS '23' parity)")
  void findByIdAbsentReturnsEmpty_fileStatus23Parity() {
    assertThat(repository.findById("99")).isEmpty();
  }

  /**
   * The {@code char(2)} primary key (PostgreSQL {@code bpchar}) is matched against the bare
   * two-character code with no manual right-padding, and the value read back round-trips to exactly
   * those two characters with no trailing-space surprises — preserving VSAM key equality and
   * ordering semantics (AAP &sect;0.6.2).
   */
  @Test
  @DisplayName("char(2) primary key matches a plain two-character code without manual padding")
  void charKeyEqualityIgnoresTrailingSpaces() {
    Optional<TransactionType> purchase = repository.findById("01");
    assertThat(purchase).isPresent();
    assertThat(purchase.get().getTranType()).isEqualTo("01").hasSize(2);
  }
}
