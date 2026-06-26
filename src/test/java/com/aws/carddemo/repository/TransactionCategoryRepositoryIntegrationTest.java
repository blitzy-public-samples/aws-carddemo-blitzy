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

import com.aws.carddemo.domain.TransactionCategory;
import com.aws.carddemo.domain.id.TransactionCategoryId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest @DataJpaTest} slice
 * integration test for {@link TransactionCategoryRepository}, proving <strong>composite</strong>
 * primary-key lookup parity for the {@code tran_category} reference table against a real
 * <b>Testcontainers PostgreSQL 16</b> instance.
 *
 * <p><strong>Legacy origin.</strong> The repository replaces random reads of the legacy z/OS VSAM
 * KSDS {@code TRANCATG} (copybook {@code legacy/app/cpy/CVTRA04Y.cpy}, record {@code 01
 * TRAN-CAT-RECORD}, {@code RECLN = 60}). Its {@code 05 TRAN-CAT-KEY} group — {@code TRAN-TYPE-CD
 * PIC X(02)} plus {@code TRAN-CAT-CD PIC 9(04)} — becomes the PostgreSQL composite primary key
 * {@code (tran_type_cd char(2), tran_cat_cd char(4))} with a combined <b>KEYLEN 6</b> (Agent Action
 * Plan &sect;0.6.2), modeled as the {@code @EmbeddedId} {@link TransactionCategoryId}. The lookup
 * table is seeded — not written at runtime — so these tests are strictly read-only and never mutate
 * rows.
 *
 * <p><strong>Fixtures under test.</strong> All assertions verify the <em>committed</em> Flyway seed
 * rather than data inserted by the test: {@code V1__schema.sql} creates {@code tran_category} and
 * {@code V2__seed_reference_data.sql} loads exactly the eighteen rows transcribed from the golden
 * fixture {@code legacy/app/data/ASCII/trancatg.txt} (transaction type {@code 01} alone owns five
 * categories, {@code 0001}–{@code 0005}, including {@code 0005 = Interest Amount}).
 * {@code @DataJpaTest} is {@code @Transactional}; Flyway commits the seed on context startup before
 * any test transaction begins, so the rows are visible to every {@code findById} / {@code count}
 * call here, and each method's surrounding transaction rolls back, leaving the seed untouched.
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
 *   <li><b>Seed cardinality</b> — exactly eighteen transaction categories load (VSAM {@code
 *       TRANCATG} parity);
 *   <li><b>Composite-key lookup</b> — {@code findById(new TransactionCategoryId(type, cat))}
 *       returns the seeded row; the {@code char(50)} description is blank-padded on read, so text
 *       is compared after {@link String#trim()};
 *   <li><b>Record-not-found</b> — an unknown {@code (type, cat)} pair yields {@link
 *       Optional#empty()}, the JPA mapping of COBOL FILE STATUS {@code '23'} (AAP &sect;0.6.4);
 *   <li><b>bpchar key equality</b> — the two-character type code and four-character category code
 *       are matched against the {@code char(2)} / {@code char(4)} key columns with no manual
 *       padding, mirroring VSAM equality semantics (AAP &sect;0.6.2).
 * </ul>
 */
class TransactionCategoryRepositoryIntegrationTest extends AbstractRepositoryIntegrationTest {

  /** Repository under test; injected by the {@code @DataJpaTest} slice context. */
  @Autowired private TransactionCategoryRepository repository;

  /**
   * The {@code V2} seed loads exactly eighteen transaction categories, matching the row count of
   * the legacy VSAM {@code TRANCATG} fixture {@code app/data/ASCII/trancatg.txt}.
   */
  @Test
  @DisplayName("V2 seed loads exactly eighteen transaction categories (TRANCATG VSAM parity)")
  void seedCountMatchesVsam() {
    assertThat(repository.count()).isEqualTo(18L);
  }

  /**
   * {@code findById} resolves a composite {@code (type, category)} key to its seeded row, and the
   * blank-padded {@code char(50)} description equals the expected text once trimmed. Two distinct
   * keys are exercised — {@code ("01", "0001")} and {@code ("07", "0001")} — to confirm the seed is
   * reachable across transaction types by the full composite primary key.
   */
  @Test
  @DisplayName("findById returns the seeded category with its trimmed description")
  void findByCompositeKeyReturnsSeededCategory() {
    Optional<TransactionCategory> regularSalesDraft =
        repository.findById(new TransactionCategoryId("01", "0001"));
    assertThat(regularSalesDraft).isPresent();
    assertThat(regularSalesDraft.get().getTranCatTypeDesc().trim())
        .isEqualTo("Regular Sales Draft");

    Optional<TransactionCategory> salesDraftCreditAdjustment =
        repository.findById(new TransactionCategoryId("07", "0001"));
    assertThat(salesDraftCreditAdjustment).isPresent();
    assertThat(salesDraftCreditAdjustment.get().getTranCatTypeDesc().trim())
        .isEqualTo("Sales draft credit adjustment");
  }

  /**
   * A composite-key lookup for a {@code (type, category)} pair that is not seeded returns {@link
   * Optional#empty()} rather than throwing — the Spring Data JPA equivalent of COBOL FILE STATUS
   * {@code '23'} (record not found), per AAP &sect;0.6.4.
   */
  @Test
  @DisplayName("findById for an unknown key returns empty (FILE STATUS '23' parity)")
  void findByIdAbsentReturnsEmpty_fileStatus23Parity() {
    assertThat(repository.findById(new TransactionCategoryId("09", "9999"))).isEmpty();
  }

  /**
   * Both halves of the composite key are PostgreSQL {@code bpchar} ({@code char(2)} and {@code
   * char(4)}). The lookup is issued with the bare, exactly-width codes — no manual right-padding —
   * and the values read back round-trip to exactly those characters with no trailing-space
   * surprises, preserving VSAM key equality and ordering semantics (AAP &sect;0.6.2).
   */
  @Test
  @DisplayName("composite char(2)/char(4) key matches plain codes without manual padding")
  void compositeCharKeyEqualityIgnoresTrailingSpaces() {
    Optional<TransactionCategory> category =
        repository.findById(new TransactionCategoryId("01", "0001"));
    assertThat(category).isPresent();

    TransactionCategoryId id = category.get().getId();
    assertThat(id.getTranTypeCd()).isEqualTo("01").hasSize(2);
    assertThat(id.getTranCatCd()).isEqualTo("0001").hasSize(4);
  }
}
