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

import com.aws.carddemo.domain.Account;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest} slice integration test
 * for {@link AccountRepository}, the Spring Data JPA replacement for the legacy VSAM {@code
 * ACCTDATA} KSDS account master (copybook {@code app/cpy/CVACT01Y.cpy}, {@code 01 ACCOUNT-RECORD};
 * batch reader {@code app/cbl/CBACT01C.cbl}). The repository declares no custom finders — it simply
 * {@code extends JpaRepository<Account, Long>} — so this suite exercises the inherited {@code save}
 * / {@code findById} contract against a real PostgreSQL engine.
 *
 * <p>The behaviour these tests lock in (Agent Action Plan &sect;0.6.1, &sect;0.6.2, &sect;0.6.4)
 * cannot be proven by a pure-POJO test or an in-memory database; it requires a faithful {@code
 * numeric(12,2)} column, which is why the suite extends {@link AbstractRepositoryIntegrationTest}
 * and runs against Testcontainers PostgreSQL 16:
 *
 * <ul>
 *   <li><b>Single-PK lookup parity</b> (&sect;0.6.2) — {@code ACCT-ID PIC 9(11)} (VSAM KEYLEN 11)
 *       becomes the {@code numeric(11)} primary key {@code acct_id}, queried via {@code findById}.
 *   <li><b>Decimal fidelity</b> (&sect;0.6.1, the headline parity guarantee) — the COBOL
 *       fixed-point balance field {@code ACCT-CURR-BAL PIC S9(10)V99} round-trips through the
 *       {@code numeric(12,2)} column with its value intact <em>and</em> {@code scale() == 2}.
 *       Monetary data is {@link java.math.BigDecimal} only; {@code float}/{@code double} are never
 *       used.
 *   <li><b>FILE STATUS {@code '23'} parity</b> (&sect;0.6.4) — a lookup of an absent key returns
 *       {@link java.util.Optional#empty()}, the Java mapping of the COBOL record-not-found status.
 * </ul>
 *
 * <p>The {@code account} table is not part of the {@code V2__seed_reference_data.sql} seed (which
 * loads only the four reference tables), so the slice starts with an empty {@code account} table
 * and each test builds and persists its own row through {@link #buildAccount(long)}.
 * {@code @DataJpaTest} is transactional, so every method rolls back and the table is empty again
 * for the next test.
 *
 * <p>The {@link #flushAndClear()} call inherited from the base class is essential to the
 * decimal-fidelity assertions: it flushes the pending INSERT and evicts the entity from the
 * persistence context, forcing {@code findById} to re-read the value from the {@code numeric(12,2)}
 * column rather than returning the still-cached in-memory instance. Without that round-trip the
 * column's scale normalization (proven by {@link #nonScaleTwoInputNormalizesToScaleTwo()}) would
 * not be observable.
 */
@DisplayName("AccountRepository — single-PK lookup and S9(10)V99 decimal-fidelity parity")
class AccountRepositoryIntegrationTest extends AbstractRepositoryIntegrationTest {

  /**
   * A primary key that fits {@code numeric(11)} (11 digits, &le; 99,999,999,999). Used for the
   * present-row save/round-trip tests.
   */
  private static final long PRESENT_ACCT_ID = 90000000001L;

  /**
   * The maximum {@code numeric(11)} value (eleven nines); deliberately never inserted, so a lookup
   * proves the empty-Optional (FILE STATUS {@code '23'}) path.
   */
  private static final long ABSENT_ACCT_ID = 99999999999L;

  @Autowired private AccountRepository repository;

  /**
   * Headline decimal-fidelity test (AAP &sect;0.6.1): a balance saved as {@code new
   * BigDecimal("1234.56")} is re-read from the {@code numeric(12,2)} column — after a forced DB
   * round-trip — numerically equal and still carrying {@code scale() == 2}, the COBOL {@code PIC
   * S9(10)V99} contract. A second monetary field ({@code acctCreditLimit}) is checked the same way
   * to prove the guarantee holds across the balance/limit group, not just one column.
   */
  @Test
  @DisplayName("save/findById round-trip preserves BigDecimal value and scale 2")
  void saveThenFindByIdPreservesDecimalScale() {
    Account acct = buildAccount(PRESENT_ACCT_ID);
    acct.setAcctCurrBal(new BigDecimal("1234.56"));

    repository.save(acct);
    flushAndClear();

    Account found = repository.findById(PRESENT_ACCT_ID).orElseThrow();

    assertThat(found.getAcctId()).isEqualTo(PRESENT_ACCT_ID);

    assertThat(found.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("1234.56"));
    assertThat(found.getAcctCurrBal().scale()).isEqualTo(2);

    assertThat(found.getAcctCreditLimit()).isEqualByComparingTo(new BigDecimal("5000.00"));
    assertThat(found.getAcctCreditLimit().scale()).isEqualTo(2);
  }

  /**
   * FILE STATUS {@code '23'} parity (AAP &sect;0.6.4): looking up a key that was never inserted
   * returns {@link java.util.Optional#empty()} rather than throwing, mirroring the COBOL
   * record-not-found branch that the service layer treats as a non-error condition.
   */
  @Test
  @DisplayName("findById of an absent account returns empty (FILE STATUS '23' parity)")
  void findByIdAbsentReturnsEmpty_fileStatus23Parity() {
    assertThat(repository.findById(ABSENT_ACCT_ID)).isEmpty();
  }

  /**
   * Proves the {@code numeric(12,2)} column itself enforces COBOL scale (AAP &sect;0.6.1): a value
   * saved with scale 1 ({@code new BigDecimal("100.5")}) is stored and read back as {@code 100.50}
   * with {@code scale() == 2}. This is the assertion the pure-POJO {@code AccountTest} cannot make,
   * because scale normalization happens in the database, not in the entity.
   */
  @Test
  @DisplayName("numeric(12,2) normalizes a scale-1 input to scale 2 on read-back")
  void nonScaleTwoInputNormalizesToScaleTwo() {
    Account acct = buildAccount(PRESENT_ACCT_ID);
    acct.setAcctCurrBal(new BigDecimal("100.5"));

    repository.save(acct);
    flushAndClear();

    Account found = repository.findById(PRESENT_ACCT_ID).orElseThrow();

    assertThat(found.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("100.50"));
    assertThat(found.getAcctCurrBal().scale()).isEqualTo(2);
  }

  /**
   * Builds a fully populated, persistable {@link Account} for the given primary key. Every {@code
   * NOT NULL} column of the {@code account} table (per {@code V1__schema.sql}) is set: the five
   * monetary {@code numeric(12,2)} fields are given explicit scale-2 {@link java.math.BigDecimal}
   * values, the {@code char(1)} active-status flag is {@code "Y"}, and the {@code char(10)}
   * date/zip/group fields use exactly ten characters so PostgreSQL stores them without ambiguous
   * blank padding. The legacy misspelled accessor {@link Account#setAcctExpiraionDate(String)}
   * (copybook {@code ACCT-EXPIRAION-DATE}) is used verbatim, never a "corrected" spelling.
   *
   * @param id the {@code acct_id} primary key to assign
   * @return a valid {@code Account} ready to be saved by the repository
   */
  private Account buildAccount(long id) {
    Account account = new Account();
    account.setAcctId(id);
    account.setAcctActiveStatus("Y");
    account.setAcctCurrBal(new BigDecimal("1234.56"));
    account.setAcctCreditLimit(new BigDecimal("5000.00"));
    account.setAcctCashCreditLimit(new BigDecimal("2500.00"));
    account.setAcctOpenDate("2020-01-15");
    account.setAcctExpiraionDate("2025-12-31");
    account.setAcctReissueDate("2023-06-30");
    account.setAcctCurrCycCredit(new BigDecimal("100.25"));
    account.setAcctCurrCycDebit(new BigDecimal("75.50"));
    account.setAcctAddrZip("0000075001");
    account.setAcctGroupId("GROUP00001");
    return account;
  }
}
