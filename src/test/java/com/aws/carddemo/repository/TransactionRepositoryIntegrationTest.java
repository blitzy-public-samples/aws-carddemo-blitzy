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

import com.aws.carddemo.domain.Transaction;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest @DataJpaTest} slice
 * integration test for {@link TransactionRepository}, the Spring Data JPA replacement for the
 * legacy VSAM {@code TRANSACT} KSDS (copybook {@code legacy/app/cpy/CVTRA05Y.cpy}, {@code 01
 * TRAN-RECORD}, RECLN 350). It runs against the shared Testcontainers PostgreSQL 16 instance and
 * Flyway-applied schema provided by {@link AbstractRepositoryIntegrationTest}; all annotations, the
 * container, the {@code TestEntityManager} and {@link
 * AbstractRepositoryIntegrationTest#flushAndClear()} are inherited and intentionally not
 * re-declared here.
 *
 * <p>The test pins the COBOL-to-Java parity guarantees the transaction posting, combine and
 * reporting layers depend on (Agent Action Plan &sect;0.6):
 *
 * <ul>
 *   <li><b>Combine-step sort parity</b> (AAP &sect;0.6.3) — {@link
 *       TransactionRepository#findAllByOrderByTranIdAsc()} reproduces the {@code COMBTRAN.jcl} step
 *       {@code SORT FIELDS=(TRAN-ID,A)} with {@code SYMNAMES TRAN-ID,1,16,CH} (legacy source {@code
 *       legacy/app/jcl/COMBTRAN.jcl}). For the fixed-width, zero-padded 16-character numeric
 *       identifiers used here, {@code char(16)} lexical ascending ordering is identical to the
 *       legacy COBOL {@code CH} (character) ascending sort.
 *   <li><b>Alternate-index ordering</b> (AAP &sect;0.6.2) — {@link
 *       TransactionRepository#findAllByOrderByTranProcTsAsc()} is backed by the non-unique
 *       alternate index {@code ix_transaction_proc_ts} (legacy VSAM {@code TRANSACT} AIX, {@code
 *       AXRKP 304}, {@code LISTCAT.txt:L3674-L3676}). The processing timestamp is stored as
 *       fixed-width {@code char(26)} ISO-style text so lexical ordering equals chronological
 *       ordering. The legacy batch reporting reader {@code legacy/app/cbl/CBTRN03C.cbl} consumes
 *       the master in this order.
 *   <li><b>Primary-key lookup and FILE STATUS {@code '23'} parity</b> (AAP &sect;0.6.4) — a hit on
 *       {@code findById} returns the row and a miss returns {@link java.util.Optional#empty()},
 *       mirroring the COBOL record-found / record-not-found ({@code '23'}) branch.
 *   <li><b>Decimal fidelity</b> (AAP &sect;0.6.1) — {@code TRAN-AMT PIC S9(09)V99} round-trips
 *       through {@code numeric(11,2)} preserving an exact scale of two; money is never {@code
 *       float}/{@code double}.
 * </ul>
 *
 * <p>The {@code transaction} table is empty in the slice ({@code V2__seed_reference_data.sql} seeds
 * only the four reference tables), and {@code @DataJpaTest} is {@code @Transactional}, so every
 * test method rolls back. Each test therefore builds and persists its own rows and observes only
 * those rows, which makes the {@code containsExactly} assertions deterministic. Rows are inserted
 * out of order and the finder is asserted to return them sorted; distinct keys are used throughout
 * because the legacy {@code SORT} declares no {@code EQUALS} option, leaving tie-break order
 * unspecified (AAP &sect;0.6.3). {@link AbstractRepositoryIntegrationTest#flushAndClear()} forces a
 * database round-trip so {@code char(n)} blank-padding and {@code numeric} scale reflect true
 * PostgreSQL read-back semantics; values are {@link String#trim() trimmed} on read before
 * comparison.
 */
class TransactionRepositoryIntegrationTest extends AbstractRepositoryIntegrationTest {

  /** Repository under test; wired from the {@code @DataJpaTest} slice context. */
  @Autowired private TransactionRepository repository;

  /**
   * Combine-step sort parity (AAP &sect;0.6.3). Three transactions are persisted with their ids in
   * a deliberately scrambled order ({@code 3, 1, 2}); {@link
   * TransactionRepository#findAllByOrderByTranIdAsc()} must return them ascending by {@code
   * tran_id} ({@code 1, 2, 3}), exactly reproducing {@code SORT FIELDS=(TRAN-ID,A)} from {@code
   * COMBTRAN.jcl}. The keys are distinct so the assertion is not sensitive to the unspecified
   * tie-break order of the legacy character sort.
   */
  @Test
  @DisplayName("findAllByOrderByTranIdAsc returns rows ascending by tran_id (COMBTRAN sort parity)")
  void findAllByOrderByTranIdAsc_sortParity() {
    // Insert out of order: 3, 1, 2.
    entityManager.persist(buildTransaction("0000000000000003", "2022-07-18 00:00:03.000000"));
    entityManager.persist(buildTransaction("0000000000000001", "2022-07-18 00:00:01.000000"));
    entityManager.persist(buildTransaction("0000000000000002", "2022-07-18 00:00:02.000000"));
    flushAndClear();

    List<Transaction> ordered = repository.findAllByOrderByTranIdAsc();

    // char(16) lexical ascending == COBOL CH ascending for these fixed-width numeric ids.
    assertThat(ordered)
        .extracting(transaction -> transaction.getTranId().trim())
        .containsExactly("0000000000000001", "0000000000000002", "0000000000000003");
  }

  /**
   * Alternate-index ordering parity (AAP &sect;0.6.2). The processing timestamp is intentionally
   * paired against the primary key in the <em>opposite</em> direction (the lowest {@code tran_id}
   * carries the latest timestamp) and the rows are inserted out of timestamp order, so a result
   * that is merely primary-key ordered would fail. {@link
   * TransactionRepository#findAllByOrderByTranProcTsAsc()} must return the rows ascending by {@code
   * tran_proc_ts}, the ordering served by the {@code ix_transaction_proc_ts} alternate index.
   */
  @Test
  @DisplayName("findAllByOrderByTranProcTsAsc returns rows ascending by tran_proc_ts (alt index)")
  void findAllByOrderByTranProcTsAsc_altIndexOrdering() {
    // Timestamp order is independent of id order, and rows are inserted out of timestamp order.
    entityManager.persist(buildTransaction("0000000000000001", "2022-07-18 00:00:03.000000"));
    entityManager.persist(buildTransaction("0000000000000002", "2022-07-18 00:00:01.000000"));
    entityManager.persist(buildTransaction("0000000000000003", "2022-07-18 00:00:02.000000"));
    flushAndClear();

    List<Transaction> ordered = repository.findAllByOrderByTranProcTsAsc();

    // char(26) ISO-style text: lexical ascending == chronological ascending.
    assertThat(ordered)
        .extracting(transaction -> transaction.getTranProcTs().trim())
        .containsExactly(
            "2022-07-18 00:00:01.000000",
            "2022-07-18 00:00:02.000000",
            "2022-07-18 00:00:03.000000");
  }

  /**
   * Primary-key lookup parity. After persisting a single row, {@code findById} on its {@code
   * tran_id} ({@code TRAN-ID}, the VSAM {@code TRANSACT} KSDS key) returns the matching
   * transaction.
   */
  @Test
  @DisplayName("findById returns the transaction for an existing tran_id")
  void findByIdPkLookup() {
    entityManager.persist(buildTransaction("0000000000000001", "2022-07-18 00:00:01.000000"));
    flushAndClear();

    assertThat(repository.findById("0000000000000001"))
        .isPresent()
        .get()
        .extracting(transaction -> transaction.getTranId().trim())
        .isEqualTo("0000000000000001");
  }

  /**
   * Record-not-found parity (AAP &sect;0.6.4). A lookup for a {@code tran_id} that was never
   * persisted returns {@link java.util.Optional#empty()}, the Java mapping of COBOL FILE STATUS
   * {@code '23'} (record not found).
   */
  @Test
  @DisplayName("findById returns Optional.empty for an absent tran_id (FILE STATUS '23')")
  void findByIdAbsentReturnsEmpty_fileStatus23Parity() {
    assertThat(repository.findById("9999999999999999")).isEmpty();
  }

  /**
   * Decimal-fidelity parity (AAP &sect;0.6.1). The posting amount {@code TRAN-AMT PIC S9(09)V99} is
   * stored at {@code numeric(11,2)} and must round-trip through the database preserving an exact
   * scale of two cents. Reading back through a real PostgreSQL {@code numeric} column (after {@link
   * AbstractRepositoryIntegrationTest#flushAndClear()}) confirms both the numeric value and the
   * retained scale.
   */
  @Test
  @DisplayName("tran_amt round-trips at numeric(11,2) preserving scale two")
  void tranAmtScaleTwoFidelity() {
    Transaction transaction = buildTransaction("0000000000000001", "2022-07-18 00:00:01.000000");
    transaction.setTranAmt(new BigDecimal("504.77"));
    entityManager.persist(transaction);
    flushAndClear();

    Transaction reread = repository.findById("0000000000000001").orElseThrow();

    assertThat(reread.getTranAmt()).isEqualByComparingTo("504.77");
    assertThat(reread.getTranAmt().scale()).isEqualTo(2);
  }

  /**
   * Builds a fully populated {@link Transaction} for persistence, setting <em>every</em> {@code NOT
   * NULL} column declared by {@code V1__schema.sql} so an {@code INSERT} never violates a
   * constraint. Only the two values the ordering tests vary — the primary key {@code tranId} and
   * the processing timestamp {@code tranProcTs} — are parameters; all other fields take fixed,
   * in-bounds, fixed-width-compatible values. {@code tranAmt} defaults to {@code 100.00} and is
   * overridden by the decimal-fidelity test.
   *
   * @param tranId the 16-character primary key ({@code TRAN-ID PIC X(16)})
   * @param procTs the 26-character processing timestamp ({@code TRAN-PROC-TS PIC X(26)})
   * @return a transaction with all non-nullable fields populated, ready to persist
   */
  private Transaction buildTransaction(String tranId, String procTs) {
    Transaction transaction = new Transaction();
    transaction.setTranId(tranId);
    transaction.setTranTypeCd("01");
    transaction.setTranCatCd("0001");
    transaction.setTranSource("POS");
    transaction.setTranDesc("PURCHASE - GROCERY STORE");
    transaction.setTranAmt(new BigDecimal("100.00"));
    transaction.setTranMerchantId(123456789L);
    transaction.setTranMerchantName("ACME MERCHANT SERVICES");
    transaction.setTranMerchantCity("SEATTLE");
    transaction.setTranMerchantZip("98101");
    transaction.setTranCardNum("4111111111111111");
    transaction.setTranOrigTs("2022-07-18-12.34.56.789000");
    transaction.setTranProcTs(procTs);
    return transaction;
  }
}
