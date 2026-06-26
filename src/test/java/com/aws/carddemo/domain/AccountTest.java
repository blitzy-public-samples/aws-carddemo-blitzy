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
package com.aws.carddemo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pure POJO unit tests for {@link Account}, the JPA entity translation of the COBOL copybook {@code
 * CVACT01Y.cpy} ({@code 01 ACCOUNT-RECORD}, RECLN 300, legacy source {@code
 * legacy/app/cpy/CVACT01Y.cpy}). The entity maps the legacy VSAM {@code ACCTDATA} KSDS (primary key
 * {@code ACCT-ID}, KEYLEN 11) onto the {@code account} table.
 *
 * <p>{@code Account} holds the five monetary balance/limit fields, which makes it the most
 * decimal-fidelity-critical entity in the domain package. These tests therefore pin the
 * <strong>COBOL-to-JPA parity invariants</strong> that the persistence and posting/interest logic
 * depend on (Agent Action Plan &sect;0.6.1, &sect;0.6.2, &sect;0.6.7, &sect;0.7.3):
 *
 * <ul>
 *   <li>all 12 modeled fields round-trip through their Lombok-generated getters and setters;
 *   <li>every monetary balance/limit field ({@code acctCurrBal}, {@code acctCreditLimit}, {@code
 *       acctCashCreditLimit}, {@code acctCurrCycCredit}, {@code acctCurrCycDebit}) is a {@link
 *       java.math.BigDecimal} and <em>never</em> {@code float}/{@code double}, and stores its value
 *       at scale 2 without lossy conversion (COBOL {@code PIC S9(10)V99});
 *   <li>each money {@code @Column} declares {@code precision = 12, scale = 2}, matching the {@code
 *       numeric(12,2)} columns of the Flyway migration so Hibernate {@code validate} succeeds;
 *   <li>the entity maps to table {@code account} with {@code acctId} as the {@code @Id} primary
 *       key; and
 *   <li>the legacy misspelling {@code acct_expiraion_date} (copybook {@code ACCT-EXPIRAION-DATE},
 *       CVACT01Y line 11) is preserved verbatim and deliberately <em>not</em> "corrected" to
 *       "expiration".
 * </ul>
 *
 * <p>This is intentionally a framework-light test: it constructs the POJO directly with {@code new}
 * and asserts with AssertJ and Java reflection only. There is no Spring context, database,
 * {@code @DataJpaTest}, Testcontainers, or Mockito, which keeps the suite fast and deterministic
 * (AAP &sect;0.6.7).
 *
 * <p>The production entity is authoritative: if any accessor, field, or annotation drifts from what
 * is asserted here, the entity (and the migration it mirrors) is the side to scrutinize — these
 * parity locks must not be weakened.
 */
class AccountTest {

  /**
   * The five COBOL {@code PIC S9(10)V99} monetary balance/limit fields. Centralized here so the
   * type, getter-return-type, and {@code @Column} precision/scale assertions can be driven
   * uniformly across every money field rather than duplicated field by field.
   */
  private static final String[] MONEY_FIELDS = {
    "acctCurrBal", "acctCreditLimit", "acctCashCreditLimit", "acctCurrCycCredit", "acctCurrCycDebit"
  };

  /**
   * Exercises every accessor pair: sets all 12 modeled fields to synthetic, in-bounds values and
   * confirms each getter returns exactly what was set. Monetary fields are compared by value with
   * {@code isEqualByComparingTo} (the idiomatic {@link java.math.BigDecimal} comparison), while the
   * identifier and character fields use {@code isEqualTo}. This single thorough round-trip drives
   * accessor coverage for the whole entity.
   */
  @Test
  void gettersAndSettersRoundTrip() {
    Account account = new Account();

    account.setAcctId(12345678901L);
    account.setAcctActiveStatus("Y");
    account.setAcctCurrBal(new BigDecimal("1234567890.12"));
    account.setAcctCreditLimit(new BigDecimal("5000.00"));
    account.setAcctCashCreditLimit(new BigDecimal("2500.00"));
    account.setAcctOpenDate("2020-01-15");
    account.setAcctExpiraionDate("2025-12-31");
    account.setAcctReissueDate("2023-06-30");
    account.setAcctCurrCycCredit(new BigDecimal("100.25"));
    account.setAcctCurrCycDebit(new BigDecimal("75.50"));
    account.setAcctAddrZip("75001");
    account.setAcctGroupId("GROUP00001");

    assertThat(account.getAcctId()).isEqualTo(12345678901L);
    assertThat(account.getAcctActiveStatus()).isEqualTo("Y");
    assertThat(account.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("1234567890.12"));
    assertThat(account.getAcctCreditLimit()).isEqualByComparingTo(new BigDecimal("5000.00"));
    assertThat(account.getAcctCashCreditLimit()).isEqualByComparingTo(new BigDecimal("2500.00"));
    assertThat(account.getAcctOpenDate()).isEqualTo("2020-01-15");
    assertThat(account.getAcctExpiraionDate()).isEqualTo("2025-12-31");
    assertThat(account.getAcctReissueDate()).isEqualTo("2023-06-30");
    assertThat(account.getAcctCurrCycCredit()).isEqualByComparingTo(new BigDecimal("100.25"));
    assertThat(account.getAcctCurrCycDebit()).isEqualByComparingTo(new BigDecimal("75.50"));
    assertThat(account.getAcctAddrZip()).isEqualTo("75001");
    assertThat(account.getAcctGroupId()).isEqualTo("GROUP00001");
  }

  /**
   * The core decimal-fidelity assertion (AAP &sect;0.6.1): every monetary balance/limit field is
   * declared as {@link java.math.BigDecimal}, and its Lombok-generated getter returns {@code
   * BigDecimal} as well. Money must never regress to {@code float} or {@code double}, which would
   * silently corrupt cent-level posting and interest arithmetic.
   */
  @Test
  void moneyFieldsAreBigDecimalNotFloatingPoint()
      throws NoSuchFieldException, NoSuchMethodException {
    for (String fieldName : MONEY_FIELDS) {
      assertThat(Account.class.getDeclaredField(fieldName).getType())
          .as("field %s must be declared as BigDecimal", fieldName)
          .isEqualTo(BigDecimal.class);

      String getter = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
      assertThat(Account.class.getMethod(getter).getReturnType())
          .as("getter %s must return BigDecimal", getter)
          .isEqualTo(BigDecimal.class);
    }
  }

  /**
   * Confirms the entity faithfully stores a {@link java.math.BigDecimal} at scale 2 with no lossy
   * double conversion: a value set with {@code new BigDecimal("100.45")} is read back numerically
   * equal and still carries {@code scale() == 2}. Repeated for a second money field set to a
   * trailing-zero value ({@code "5000.00"}) to prove the declared two-decimal scale is preserved
   * rather than normalized away.
   */
  @Test
  void bigDecimalScaleIsPreservedAtScaleTwo() {
    Account account = new Account();

    account.setAcctCurrBal(new BigDecimal("100.45"));
    assertThat(account.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("100.45"));
    assertThat(account.getAcctCurrBal().scale()).isEqualTo(2);

    account.setAcctCreditLimit(new BigDecimal("5000.00"));
    assertThat(account.getAcctCreditLimit()).isEqualByComparingTo(new BigDecimal("5000.00"));
    assertThat(account.getAcctCreditLimit().scale()).isEqualTo(2);
  }

  /**
   * Each monetary {@code @Column} declares {@code precision = 12, scale = 2}, the JPA mapping of
   * COBOL {@code PIC S9(10)V99} (10 integer digits + 2 fraction digits = {@code numeric(12,2)}).
   * This keeps the entity aligned with the {@code numeric(12,2)} columns in the Flyway migration so
   * Hibernate schema {@code validate} succeeds at startup (AAP &sect;0.6.1, &sect;0.6.2).
   */
  @Test
  void moneyColumnsDeclareScaleTwoAndPrecisionTwelve() throws NoSuchFieldException {
    for (String fieldName : MONEY_FIELDS) {
      Column column = column(fieldName);
      assertThat(column.precision()).as("precision of %s", fieldName).isEqualTo(12);
      assertThat(column.scale()).as("scale of %s", fieldName).isEqualTo(2);
    }
  }

  /**
   * Parity lock for the deliberately preserved legacy misspelling (AAP &sect;0.6.2): the field is
   * named {@code acctExpiraionDate} and maps to column {@code acct_expiraion_date}, exactly as in
   * copybook {@code ACCT-EXPIRAION-DATE} (CVACT01Y line 11). The correctly spelled variant {@code
   * acctExpirationDate} must <em>not</em> exist; if anyone "fixes" the typo, the entity diverges
   * from the schema/VSAM contract and this test fails by design.
   */
  @Test
  void preservesLegacyMisspellingAcctExpiraionDate() throws NoSuchFieldException {
    Field misspelled = Account.class.getDeclaredField("acctExpiraionDate");
    assertThat(misspelled.getAnnotation(Column.class).name()).isEqualTo("acct_expiraion_date");

    assertThatThrownBy(() -> Account.class.getDeclaredField("acctExpirationDate"))
        .isInstanceOf(NoSuchFieldException.class);
  }

  /**
   * Locks the JPA table mapping to the legacy {@code ACCTDATA} store and the primary-key contract:
   * {@code @Table} resolves to {@code account}, and {@code acctId} carries {@code @Id} with
   * {@code @Column(name = "acct_id", precision = 11)} (VSAM {@code ACCT-ID} KEYLEN 11) and the
   * genuine numeric type {@link Long} — never a floating-point type.
   */
  @Test
  void tableNameIsAccountAndPrimaryKeyIsAcctId() throws NoSuchFieldException {
    assertThat(Account.class.getAnnotation(Table.class).name()).isEqualTo("account");

    Field acctId = Account.class.getDeclaredField("acctId");
    assertThat(acctId.isAnnotationPresent(Id.class)).isTrue();

    Column column = acctId.getAnnotation(Column.class);
    assertThat(column.name()).isEqualTo("acct_id");
    assertThat(column.precision()).isEqualTo(11);
    assertThat(acctId.getType()).isEqualTo(Long.class);
  }

  /**
   * Hard decimal-fidelity guard across the whole entity (AAP &sect;0.6.1): iterate every declared
   * field, skip compiler/coverage synthetic members (for example JaCoCo's {@code $jacocoData}), and
   * assert that no field is {@code float}, {@code double}, {@link Float}, or {@link Double}. This
   * catches a floating-point regression on <em>any</em> field, including ones not enumerated in
   * {@link #MONEY_FIELDS}.
   */
  @Test
  void hasNoFloatingPointFields() {
    for (Field field : Account.class.getDeclaredFields()) {
      if (field.isSynthetic()) {
        continue;
      }
      assertThat(field.getType())
          .as("field %s must not be a floating-point type", field.getName())
          .isNotIn(float.class, double.class, Float.class, Double.class);
    }
  }

  /** Resolves the {@code @Column} annotation declared on the named entity field. */
  private static Column column(String fieldName) throws NoSuchFieldException {
    return Account.class.getDeclaredField(fieldName).getAnnotation(Column.class);
  }
}
