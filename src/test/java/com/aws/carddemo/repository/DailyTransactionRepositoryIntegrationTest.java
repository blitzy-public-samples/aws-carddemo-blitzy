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

import com.aws.carddemo.domain.DailyTransaction;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link DailyTransactionRepository} {@code @DataJpaTest} slice integration test.
 *
 * <p>The {@code daily_transaction} table is the modern home of the legacy sequential {@code
 * DALYTRAN} file (copybook {@code legacy/app/cpy/CVTRA06Y.cpy}, {@code 01 DALYTRAN-RECORD}, RECLN
 * 350), the inbound feed consumed by the daily-transaction posting batch ({@code
 * app/cbl/CBTRN01C.cbl} / {@code app/cbl/CBTRN02C.cbl}). The production repository is a bare {@code
 * JpaRepository<DailyTransaction, String>} — single primary key {@code DALYTRAN-ID PIC X(16)} →
 * {@code char(16)}, no custom finder methods and no alternate index — so this test exercises the
 * inherited {@code save}/{@code findById} surface only.
 *
 * <p>Wiring (the {@code @DataJpaTest} annotation, the JVM-singleton Testcontainers PostgreSQL 16
 * instance, the {@link org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager} and
 * the {@code flushAndClear()} helper) is inherited from {@link AbstractRepositoryIntegrationTest}
 * and deliberately not re-declared here. Because {@code @DataJpaTest} is {@code @Transactional}
 * every method rolls back, so the table is empty at the start of each test; the save-based tests
 * therefore build and persist their own row.
 *
 * <p>The assertions prove three behavioural-parity guarantees against a real PostgreSQL engine (an
 * in-memory database cannot reproduce them):
 *
 * <ul>
 *   <li><b>Decimal fidelity</b> (AAP 0.6.1) — {@code DALYTRAN-AMT PIC S9(09)V99} is a {@code
 *       numeric(11,2)} {@link BigDecimal} that round-trips at exactly scale 2, never a binary
 *       floating-point value;
 *   <li><b>FILE STATUS {@code '23'} parity</b> (AAP 0.6.4) — a lookup for an absent key returns
 *       {@link Optional#empty()} rather than raising an error;
 *   <li><b>Fixed-width / blank-padding fidelity</b> (AAP 0.6.2) — values stored in {@code char(26)}
 *       timestamp columns read back right-padded with spaces to the full column width, preserving
 *       VSAM ordering and equality semantics.
 * </ul>
 */
class DailyTransactionRepositoryIntegrationTest extends AbstractRepositoryIntegrationTest {

  /** Sixteen-character primary key ({@code DALYTRAN-ID PIC X(16)} → {@code char(16)}). */
  private static final String EXISTING_ID = "DTRAN00000000001";

  /**
   * Scale-2 monetary amount ({@code DALYTRAN-AMT PIC S9(09)V99} → {@code numeric(11,2)}); mirrors a
   * fixture-decoded value for parity flavour.
   */
  private static final BigDecimal EXPECTED_AMOUNT = new BigDecimal("504.77");

  /**
   * A 19-character timestamp deliberately shorter than the {@code char(26)} column so the
   * blank-padding round-trip is observable on read-back.
   */
  private static final String ORIG_TS = "2022-07-18 12:30:45";

  @Autowired private DailyTransactionRepository repository;

  @Test
  @DisplayName(
      "save() then findById() returns the row with a scale-2 BigDecimal amount"
          + " (decimal fidelity, AAP 0.6.1)")
  void saveThenFindByIdReturnsRowWithScaleTwoAmount() {
    repository.save(buildDailyTransaction(EXISTING_ID));
    flushAndClear();

    Optional<DailyTransaction> found = repository.findById(EXISTING_ID);

    assertThat(found).isPresent();
    BigDecimal amount = found.get().getDalytranAmt();
    assertThat(amount).isEqualByComparingTo(EXPECTED_AMOUNT);
    assertThat(amount.scale()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "findById() for an unsaved id returns Optional.empty() (FILE STATUS '23' parity, AAP 0.6.4)")
  void findByIdAbsentReturnsEmpty_fileStatus23Parity() {
    assertThat(repository.findById("NOSUCHID000000000".substring(0, 16))).isEmpty();
  }

  @Test
  @DisplayName(
      "char(26) timestamp fields are blank-padded on read-back (fixed-width parity, AAP 0.6.2)")
  void charTimestampFieldsBlankPaddedOnReadback() {
    repository.save(buildDailyTransaction(EXISTING_ID));
    flushAndClear();

    DailyTransaction reloaded = repository.findById(EXISTING_ID).orElseThrow();

    // The 19-character origination timestamp is right-padded with spaces to the full char(26)
    // width; trimming recovers the value that was stored.
    String origTs = reloaded.getDalytranOrigTs();
    assertThat(origTs).hasSize(26);
    assertThat(origTs.trim()).isEqualTo(ORIG_TS);

    // Inbound daily transactions are not yet posted, so the processing timestamp is blank (COBOL
    // SPACES); it likewise reads back as a 26-character all-blank string.
    String procTs = reloaded.getDalytranProcTs();
    assertThat(procTs).hasSize(26);
    assertThat(procTs.trim()).isEmpty();
  }

  /**
   * Builds a fully populated {@link DailyTransaction} for the supplied primary key. Every {@code
   * NOT NULL} column declared in {@code V1__schema.sql} for the {@code daily_transaction} table is
   * set so the row inserts cleanly; the values mirror the legacy ASCII fixture {@code
   * fixtures/ascii/dailytran.txt} for parity flavour.
   *
   * @param id the 16-character {@code DALYTRAN-ID} primary key
   * @return a transient, fully populated entity ready to be persisted
   */
  private DailyTransaction buildDailyTransaction(String id) {
    DailyTransaction tran = new DailyTransaction();
    tran.setDalytranId(id);
    tran.setDalytranTypeCd("01");
    tran.setDalytranCatCd("0001");
    tran.setDalytranSource("POS TERM");
    tran.setDalytranDesc("Purchase at Abshire-Lowe");
    tran.setDalytranAmt(EXPECTED_AMOUNT);
    tran.setDalytranMerchantId(123456789L);
    tran.setDalytranMerchantName("Abshire-Lowe");
    tran.setDalytranMerchantCity("Seattle");
    tran.setDalytranMerchantZip("98101");
    tran.setDalytranCardNum("4111111111111111");
    tran.setDalytranOrigTs(ORIG_TS);
    // Blank (SPACES) processing timestamp: the row models an inbound, not-yet-posted transaction.
    tran.setDalytranProcTs(" ");
    return tran;
  }
}
