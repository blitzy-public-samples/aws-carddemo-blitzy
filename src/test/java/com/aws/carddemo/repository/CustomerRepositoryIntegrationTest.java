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

import com.aws.carddemo.domain.Customer;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code @DataJpaTest} slice integration test for {@link CustomerRepository}, the Spring Data JPA
 * replacement for the legacy VSAM {@code CUSTDATA} KSDS. The store is described by copybook {@code
 * CVCUS01Y.cpy} ({@code 01 CUSTOMER-RECORD}, RECLN 500) and was sequentially read and printed by
 * the batch archetype {@code app/cbl/CBCUS01C.cbl} (an {@code ORGANIZATION INDEXED} file keyed on
 * {@code FD-CUST-ID PIC 9(09)}). This test proves the COBOL-to-JPA parity guarantees of the
 * single-key lookup locally, against a real database, with no running mainframe (Agent Action Plan
 * &sect;0.6.7).
 *
 * <p>The class extends {@link AbstractRepositoryIntegrationTest}, inheriting the entire slice
 * configuration — {@code @DataJpaTest} (transactional, rolled back per method; Flyway applies
 * {@code V1__schema.sql} and {@code V2__seed_reference_data.sql} automatically),
 * {@code @AutoConfigureTestDatabase(replace = NONE)}, {@code @ActiveProfiles("test")}, the
 * JVM-singleton Testcontainers PostgreSQL 16 instance wired through {@code @ServiceConnection}, the
 * {@code TestEntityManager}, and the {@link AbstractRepositoryIntegrationTest#flushAndClear()}
 * helper. Those members are intentionally <em>not</em> re-declared here.
 *
 * <p>The {@code customer} table is empty in the slice: the {@code V2} migration seeds only the four
 * reference tables ({@code trantype}, {@code trancatg}, {@code discgrp}, {@code tcatbal}), not
 * {@code customer}, and the surrounding transaction rolls back after each test. Each test therefore
 * builds and persists its own row via {@link #buildCustomer(long)} when a present row is required.
 *
 * <p>The parity invariants exercised here are:
 *
 * <ul>
 *   <li><b>Single-PK lookup parity</b> — a persisted row is retrievable by its {@code cust_id}
 *       primary key ({@code CUST-ID PIC 9(09)} &rarr; {@code numeric(9)}, KEYLEN 9; AAP
 *       &sect;0.6.2), mirroring a keyed VSAM read.
 *   <li><b>FILE STATUS {@code '23'} parity</b> — a lookup for an id that was never written returns
 *       {@link Optional#empty()} rather than throwing, matching the COBOL record-not-found contract
 *       (AAP &sect;0.6.4).
 *   <li><b>Fixed-width {@code char(n)} parity</b> — text shorter than its column width is read back
 *       blank-padded to the full width (AAP &sect;0.6.2); a database round-trip (forced by {@code
 *       flushAndClear()}) is required to observe this because the persistence-context first-level
 *       cache would otherwise return the un-padded value as set.
 * </ul>
 *
 * <p>Primary keys are kept within {@code numeric(9)} ({@code <= 999,999,999}): {@link
 * #PRESENT_CUST_ID} for the persisted row and {@link #ABSENT_CUST_ID} for the missing lookup.
 */
class CustomerRepositoryIntegrationTest extends AbstractRepositoryIntegrationTest {

  /** Primary key of the persisted row; 9 digits, in range for {@code numeric(9)}. */
  private static final long PRESENT_CUST_ID = 900000001L;

  /**
   * Primary key that is never persisted; used to assert record-not-found ({@code '23'}) parity. 9
   * digits, in range for {@code numeric(9)}.
   */
  private static final long ABSENT_CUST_ID = 899999999L;

  /**
   * Logical first-name value. Deliberately only four characters so that, when stored in the {@code
   * char(25)} {@code cust_first_name} column and read back, the blank-padding to the full width is
   * observable (AAP &sect;0.6.2).
   */
  private static final String FIRST_NAME = "JANE";

  /** Logical last-name value, asserted trimmed to demonstrate {@code char(25)} text round-trip. */
  private static final String LAST_NAME = "DOE";

  /**
   * Social-security number; {@code CUST-SSN PIC 9(09)} &rarr; {@code numeric(9)} (genuine Long).
   */
  private static final long SSN = 123456789L;

  /**
   * FICO score; {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} &rarr; {@code numeric(3)} (genuine Long).
   */
  private static final long FICO_SCORE = 700L;

  @Autowired private CustomerRepository repository;

  /**
   * Persists a fully populated customer and confirms it is retrievable by its primary key after a
   * real database round-trip. This is the Java equivalent of a keyed VSAM {@code READ} on {@code
   * CUSTDATA} succeeding (FILE STATUS {@code '00'}). Text columns are compared after {@link
   * String#trim()} because {@code char(n)} values come back blank-padded; the genuine-numeric
   * identifiers round-trip exactly as {@link Long}.
   */
  @Test
  @DisplayName("save then findById returns the persisted row (single-PK lookup parity)")
  void saveThenFindByIdReturnsRow() {
    repository.save(buildCustomer(PRESENT_CUST_ID));
    flushAndClear();

    Optional<Customer> found = repository.findById(PRESENT_CUST_ID);

    assertThat(found).isPresent();
    Customer reread = found.orElseThrow();
    assertThat(reread.getCustId()).isEqualTo(PRESENT_CUST_ID);
    assertThat(reread.getCustFirstName().trim()).isEqualTo(FIRST_NAME);
    assertThat(reread.getCustLastName().trim()).isEqualTo(LAST_NAME);
    assertThat(reread.getCustSsn()).isEqualTo(SSN);
    assertThat(reread.getCustFicoCreditScore()).isEqualTo(FICO_SCORE);
  }

  /**
   * Confirms that a lookup for an id that was never written returns {@link Optional#empty()} (the
   * {@code customer} table is empty in the slice). This pins the COBOL FILE STATUS {@code '23'}
   * (record-not-found) contract to {@code Optional.empty()} rather than an exception, so the
   * service layer can implement find-or-create semantics on top of the repository (AAP
   * &sect;0.6.4).
   */
  @Test
  @DisplayName("findById on an absent id returns Optional.empty (FILE STATUS '23' parity)")
  void findByIdAbsentReturnsEmpty_fileStatus23Parity() {
    assertThat(repository.findById(ABSENT_CUST_ID)).isEmpty();
  }

  /**
   * Demonstrates fixed-width {@code char(n)} parity (AAP &sect;0.6.2): a value shorter than its
   * column is stored and read back blank-padded to the full column width, while {@link
   * String#trim()} recovers the logical value. The {@code flushAndClear()} round-trip is essential
   * — without it {@code findById} would return the cached, un-padded instance from the persistence
   * context and the padding would not be visible.
   */
  @Test
  @DisplayName("char(n) text reads back blank-padded; trim() yields the logical value")
  void charFieldsBlankPaddedOnReadback() {
    repository.save(buildCustomer(PRESENT_CUST_ID));
    flushAndClear();

    Customer reread = repository.findById(PRESENT_CUST_ID).orElseThrow();

    String firstName = reread.getCustFirstName();
    assertThat(firstName).hasSize(25);
    assertThat(firstName.trim()).isEqualTo(FIRST_NAME);
  }

  /**
   * Builds a fully populated, in-bounds {@link Customer}. Every column of the {@code customer}
   * table is {@code NOT NULL} in {@code V1__schema.sql}, so every modeled field is set; because
   * Hibernate runs with {@code ddl-auto=validate} and PostgreSQL enforces the constraints, a
   * partially populated entity would fail to insert. All text values stay within their {@code
   * char(n)} widths and the three genuine-numeric identifiers stay within {@code numeric(9)} /
   * {@code numeric(3)}.
   *
   * @param id the primary key ({@code cust_id}) to assign; must be in range for {@code numeric(9)}
   * @return a valid, ready-to-persist {@link Customer}
   */
  private static Customer buildCustomer(long id) {
    Customer customer = new Customer();
    customer.setCustId(id);
    customer.setCustFirstName(FIRST_NAME);
    customer.setCustMiddleName("Q");
    customer.setCustLastName(LAST_NAME);
    customer.setCustAddrLine1("123 MAIN STREET");
    customer.setCustAddrLine2("SUITE 100");
    customer.setCustAddrLine3("BUILDING A");
    customer.setCustAddrStateCd("TX");
    customer.setCustAddrCountryCd("USA");
    customer.setCustAddrZip("75001");
    customer.setCustPhoneNum1("5551234567");
    customer.setCustPhoneNum2("5559876543");
    customer.setCustSsn(SSN);
    customer.setCustGovtIssuedId("DL12345678");
    customer.setCustDobYyyyMmDd("1990-01-01");
    customer.setCustEftAccountId("EFT0001234");
    customer.setCustPriCardHolderInd("Y");
    customer.setCustFicoCreditScore(FICO_SCORE);
    return customer;
  }
}
