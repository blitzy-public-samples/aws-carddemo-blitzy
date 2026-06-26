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

import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.domain.id.TransactionCategoryBalanceId;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest @DataJpaTest} slice
 * integration test for {@link TransactionCategoryBalanceRepository}, proving <b>composite-key</b>
 * primary-key lookup parity <em>and</em> the TCATBAL <b>find-or-create (upsert)</b> parity for the
 * {@code tran_cat_balance} table against a real <b>Testcontainers PostgreSQL 16</b> instance.
 *
 * <p><strong>Legacy origin.</strong> The repository replaces VSAM KSDS {@code TCATBALF} access
 * (copybook {@code legacy/app/cpy/CVTRA01Y.cpy}, source-branch {@code app/cpy/CVTRA01Y.cpy}; record
 * {@code 01 TRAN-CAT-BAL-RECORD}, {@code RECLN = 50}). Its three-part {@code 05 TRAN-CAT-KEY} group
 * — {@code TRANCAT-ACCT-ID PIC 9(11)} + {@code TRANCAT-TYPE-CD PIC X(02)} + {@code TRANCAT-CD PIC
 * 9(04)} — becomes the PostgreSQL composite primary key {@code (trancat_acct_id, trancat_type_cd,
 * trancat_cd)} of length {@code KEYLEN 17 = 11 + 2 + 4}, modeled by the {@code @Embeddable} {@link
 * TransactionCategoryBalanceId} (Agent Action Plan &sect;0.6.2). The signed packed field {@code
 * TRAN-CAT-BAL PIC S9(09)V99} becomes {@code tran_cat_bal numeric(11,2)}, held as {@link
 * BigDecimal} so the two-decimal monetary scale is preserved exactly (AAP &sect;0.6.1).
 *
 * <p><strong>Upsert parity under test (AAP &sect;0.6.4).</strong> The batch posting archetype
 * {@code app/cbl/CBTRN02C.cbl} maintains this store in paragraph {@code 2700-UPDATE-TCATBAL}: it
 * READs the category-balance record by key and treats FILE STATUS {@code '00' OR '23'} as success;
 * on record-not-found ({@code '23'}, the {@code INVALID KEY} branch) it does <em>not</em> abend but
 * sets a create flag and WRITEs a brand-new row ({@code 2700-A-CREATE-TCATBAL-REC}), otherwise it
 * REWRITEs the existing one ({@code 2700-B-UPDATE-TCATBAL-REC}). The Spring Data equivalent is
 * {@code findById(...)} (which returns {@link Optional#empty()} for {@code '23'}) followed by
 * {@code save(...)} — exercised end-to-end by {@link #findOrCreateUpsertParity()}.
 *
 * <p><strong>Fixtures under test.</strong> Assertions verify the <em>committed</em> Flyway seed,
 * not data inserted by the test: {@code V1__schema.sql} creates {@code tran_cat_balance} and {@code
 * V2__seed_reference_data.sql} loads exactly the fifty rows transcribed from the golden fixture
 * {@code legacy/app/data/ASCII/tcatbal.txt} (account ids {@code 1..50}, each with {@code
 * trancat_type_cd = '01'}, {@code trancat_cd = '0001'} and {@code tran_cat_bal = 0.00}). Because
 * {@code @DataJpaTest} is {@code @Transactional}, the upsert row created by {@link
 * #findOrCreateUpsertParity()} is rolled back at method end, so the seed cardinality observed by
 * {@link #seedCountMatchesVsam()} stays at fifty regardless of test ordering.
 *
 * <p><strong>Slice wiring.</strong> Inherited verbatim from {@link
 * AbstractRepositoryIntegrationTest}: the {@code @DataJpaTest}, {@code @AutoConfigureTestDatabase}
 * {@code (replace = NONE)} and {@code @ActiveProfiles("test")} annotations, the JVM-singleton
 * {@code @ServiceConnection} PostgreSQL container, the {@code TestEntityManager} helper and the
 * {@link #flushAndClear()} round-trip helper. This subclass deliberately re-declares none of them;
 * it only adds the repository under test and the parity assertions.
 */
class TransactionCategoryBalanceRepositoryIntegrationTest
    extends AbstractRepositoryIntegrationTest {

  /** Repository under test; injected by the {@code @DataJpaTest} slice context. */
  @Autowired private TransactionCategoryBalanceRepository repository;

  /**
   * The {@code V2} seed loads exactly fifty category-balance rows, matching the row count of the
   * legacy VSAM {@code TCATBALF} fixture {@code app/data/ASCII/tcatbal.txt} (one row per account id
   * {@code 1..50}).
   */
  @Test
  @DisplayName("V2 seed loads exactly fifty category balances (TCATBAL VSAM parity)")
  void seedCountMatchesVsam() {
    assertThat(repository.count()).isEqualTo(50L);
  }

  /**
   * {@code findById} resolves a seeded {@link TransactionCategoryBalanceId} composite key — built
   * in the constructor order {@code (Long acctId, String typeCd, String catCd)} — to its row, and
   * the seeded balance round-trips as {@code 0.00} at the {@code numeric(11,2)} scale of two (AAP
   * &sect;0.6.1). {@code isEqualByComparingTo} compares numeric value irrespective of scale, while
   * the explicit {@code scale()} assertion pins the two-decimal monetary fidelity.
   */
  @Test
  @DisplayName("findById resolves a seeded composite key with balance 0.00 at scale 2")
  void findSeededCompositeKey() {
    Optional<TransactionCategoryBalance> seeded =
        repository.findById(new TransactionCategoryBalanceId(1L, "01", "0001"));
    assertThat(seeded).isPresent();
    assertThat(seeded.get().getTranCatBal()).isEqualByComparingTo(new BigDecimal("0.00"));
    assertThat(seeded.get().getTranCatBal().scale()).isEqualTo(2);
  }

  /**
   * A composite-key lookup for a row that is not seeded returns {@link Optional#empty()} rather
   * than throwing — the Spring Data JPA equivalent of COBOL FILE STATUS {@code '23'} (record not
   * found), per AAP &sect;0.6.4. The account id {@code 900000001L} is deliberately OUTSIDE the
   * seeded {@code 1..50} range (and well within {@code numeric(11)}), so there is no collision with
   * the committed seed.
   */
  @Test
  @DisplayName("findById for an absent composite key returns empty (COBOL FILE STATUS '23' parity)")
  void findByIdAbsentReturnsEmpty_fileStatus23Parity() {
    assertThat(repository.findById(new TransactionCategoryBalanceId(900000001L, "01", "0001")))
        .isEmpty();
  }

  /**
   * Reproduces the COBOL {@code 2700-UPDATE-TCATBAL} find-or-create (upsert) path of {@code
   * CBTRN02C.cbl} (AAP &sect;0.6.4): a READ that returns record-not-found ({@code '23'}) is not an
   * error — the missing row is created rather than failing (FILE STATUS {@code '00' OR '23'}
   * accepted). The lookup key uses account id {@code 900000002L}, OUTSIDE the seeded {@code 1..50}
   * range, so the row is genuinely absent before the test creates it; because {@code @DataJpaTest}
   * is transactional, the inserted row is rolled back at method end and the seed count is left
   * untouched.
   */
  @Test
  @DisplayName("find-or-create: absent key is created, not failed (CBTRN02C 2700-UPDATE-TCATBAL)")
  void findOrCreateUpsertParity() {
    TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(900000002L, "05", "0009");

    // READ phase: the key is absent -> record-not-found (FILE STATUS '23'). In COBOL this is the
    // INVALID KEY branch, which is NOT treated as an error; it routes to record creation.
    assertThat(repository.findById(key)).isEmpty();

    // CREATE-when-absent phase: reproduces COBOL find-or-create — the missing row is created rather
    // than failing (FILE STATUS '00' OR '23' accepted). Entity is built with the no-arg constructor
    // plus setters because the production entity exposes no all-args constructor.
    TransactionCategoryBalance entity = new TransactionCategoryBalance();
    entity.setId(key);
    entity.setTranCatBal(new BigDecimal("123.45"));
    repository.save(entity);

    // Force a DB round-trip so the balance is asserted as read back from the numeric(11,2) column
    // rather than from the first-level persistence cache.
    flushAndClear();

    // The row now exists with the persisted balance preserved exactly at scale 2.
    Optional<TransactionCategoryBalance> created = repository.findById(key);
    assertThat(created).isPresent();
    assertThat(created.get().getTranCatBal()).isEqualByComparingTo(new BigDecimal("123.45"));
    assertThat(created.get().getTranCatBal().scale()).isEqualTo(2);
  }
}
