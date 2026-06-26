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

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pure POJO unit tests for {@link Transaction}, the JPA entity translation of the COBOL copybook
 * {@code CVTRA05Y.cpy} ({@code 01 TRAN-RECORD}, RECLN 350, legacy source {@code
 * legacy/app/cpy/CVTRA05Y.cpy}). The entity maps the legacy VSAM {@code TRANSACT} KSDS (primary key
 * {@code TRAN-ID}, KEYLEN 16, with a non-unique alternate index on the processing timestamp {@code
 * TRAN-PROC-TS}) onto the {@code transaction} table.
 *
 * <p>These tests pin the <strong>COBOL-to-JPA parity invariants</strong> that the transaction
 * posting and reporting layers depend on (Agent Action Plan &sect;0.6.1, &sect;0.6.2):
 *
 * <ul>
 *   <li>all 13 modeled fields round-trip through their Lombok-generated getters and setters;
 *   <li>the posting amount {@code tranAmt} is a {@link BigDecimal} mapped at {@code numeric(11,2)}
 *       and is never a binary floating-point type — COBOL {@code TRAN-AMT PIC S9(09)V99}
 *       (&sect;0.6.1);
 *   <li>{@code tranMerchantId} is a genuine numeric identifier modeled as {@link Long} at {@code
 *       numeric(9)} (COBOL {@code TRAN-MERCHANT-ID PIC 9(09)});
 *   <li>the entity maps to the {@code transaction} table with {@code tranId} as the {@code @Id};
 *       and
 *   <li>the snake_case {@code @Column} names and lengths match the migration DDL exactly so
 *       Hibernate {@code validate} succeeds, including the {@code char(26)} processing timestamp
 *       that backs the alternate-index ordering (&sect;0.6.2).
 * </ul>
 *
 * <p>This is intentionally a framework-light test: it constructs the POJO directly with {@code new}
 * and asserts with AssertJ and Java reflection only. There is no Spring context, database,
 * {@code @DataJpaTest}, Testcontainers, or Mockito. The production entity is authoritative; if any
 * accessor, field, or annotation differs, the test is the side that must change.
 */
class TransactionTest {

  /**
   * Exercises every accessor pair: sets all 13 modeled fields to synthetic, in-bounds values and
   * confirms each getter returns exactly what was set. The two timestamps use the fixed-width 26
   * character ISO-8601 form ({@code yyyy-MM-dd-HH.mm.ss.ffffff}) carried by COBOL {@code X(26)}.
   */
  @Test
  void gettersAndSettersRoundTrip() {
    Transaction transaction = new Transaction();

    transaction.setTranId("0000000000000001");
    transaction.setTranTypeCd("01");
    transaction.setTranCatCd("0001");
    transaction.setTranSource("POS");
    transaction.setTranDesc("PURCHASE - GROCERY STORE");
    transaction.setTranAmt(new BigDecimal("1234567.89"));
    transaction.setTranMerchantId(123456789L);
    transaction.setTranMerchantName("ACME MERCHANT SERVICES");
    transaction.setTranMerchantCity("SEATTLE");
    transaction.setTranMerchantZip("98101");
    transaction.setTranCardNum("4111111111111111");
    transaction.setTranOrigTs("2022-07-18-12.34.56.789000");
    transaction.setTranProcTs("2022-07-19-01.02.03.456000");

    assertThat(transaction.getTranId()).isEqualTo("0000000000000001");
    assertThat(transaction.getTranTypeCd()).isEqualTo("01");
    assertThat(transaction.getTranCatCd()).isEqualTo("0001");
    assertThat(transaction.getTranSource()).isEqualTo("POS");
    assertThat(transaction.getTranDesc()).isEqualTo("PURCHASE - GROCERY STORE");
    assertThat(transaction.getTranAmt()).isEqualTo(new BigDecimal("1234567.89"));
    assertThat(transaction.getTranMerchantId()).isEqualTo(123456789L);
    assertThat(transaction.getTranMerchantName()).isEqualTo("ACME MERCHANT SERVICES");
    assertThat(transaction.getTranMerchantCity()).isEqualTo("SEATTLE");
    assertThat(transaction.getTranMerchantZip()).isEqualTo("98101");
    assertThat(transaction.getTranCardNum()).isEqualTo("4111111111111111");
    assertThat(transaction.getTranOrigTs()).isEqualTo("2022-07-18-12.34.56.789000");
    assertThat(transaction.getTranProcTs()).isEqualTo("2022-07-19-01.02.03.456000");
  }

  /**
   * Pins the posting amount as {@link BigDecimal} on both the field and its Lombok-generated
   * getter. COBOL fixed-point money ({@code TRAN-AMT PIC S9(09)V99}) must never regress to {@code
   * float}, {@code double}, or any other type (Agent Action Plan &sect;0.6.1).
   */
  @Test
  void tranAmtIsBigDecimalNotFloatingPoint() throws NoSuchFieldException, NoSuchMethodException {
    assertThat(Transaction.class.getDeclaredField("tranAmt").getType()).isEqualTo(BigDecimal.class);
    assertThat(Transaction.class.getMethod("getTranAmt").getReturnType())
        .isEqualTo(BigDecimal.class);
  }

  /**
   * The entity stores the amount exactly as supplied, preserving the two-decimal scale required for
   * cent-accurate transaction posting (Agent Action Plan &sect;0.6.1). The round-trip is asserted
   * by {@code compareTo} (numeric equality) and the retained {@code scale()} is confirmed to be 2.
   */
  @Test
  void tranAmtScaleIsPreservedAtScaleTwo() {
    Transaction transaction = new Transaction();
    transaction.setTranAmt(new BigDecimal("100.45"));

    assertThat(transaction.getTranAmt()).isEqualByComparingTo(new BigDecimal("100.45"));
    assertThat(transaction.getTranAmt().scale()).isEqualTo(2);
  }

  /**
   * The {@code tran_amt} column declares {@code precision = 11, scale = 2} — COBOL {@code
   * S9(09)V99} is 9 integer digits plus 2 fraction digits, i.e. PostgreSQL {@code numeric(11,2)}
   * (Agent Action Plan &sect;0.6.1).
   */
  @Test
  void tranAmtColumnDeclaresPrecisionElevenScaleTwo() throws NoSuchFieldException {
    Column column = column("tranAmt");

    assertThat(column.precision()).isEqualTo(11);
    assertThat(column.scale()).isEqualTo(2);
  }

  /**
   * Locks the JPA table mapping to the legacy {@code TRANSACT} store name and confirms {@code
   * tranId} is the primary key: it is a {@link String} carrying {@code @Id} and maps to {@code
   * char(16)} column {@code tran_id} (VSAM {@code TRAN-ID PIC X(16)}, KEYLEN 16).
   */
  @Test
  void tableNameIsTransactionAndPrimaryKeyIsTranId() throws NoSuchFieldException {
    assertThat(Transaction.class.getAnnotation(Table.class).name()).isEqualTo("transaction");

    Field tranId = Transaction.class.getDeclaredField("tranId");
    assertThat(tranId.isAnnotationPresent(Id.class)).isTrue();
    assertThat(tranId.getType()).isEqualTo(String.class);

    Column column = tranId.getAnnotation(Column.class);
    assertThat(column.name()).isEqualTo("tran_id");
    assertThat(column.length()).isEqualTo(16);
  }

  /**
   * Spot-checks a representative set of {@code @Column} mappings to lock the snake_case names and
   * fixed-widths the Flyway migration DDL expects. Covers text/code fields, the {@code char(26)}
   * timestamps backing the alternate index, and the {@link Long} {@code tran_merchant_id} numeric
   * identifier with its {@code precision = 9}.
   */
  @Test
  void representativeColumnMappings() throws NoSuchFieldException {
    assertThat(column("tranTypeCd").name()).isEqualTo("tran_type_cd");
    assertThat(column("tranTypeCd").length()).isEqualTo(2);
    assertThat(column("tranCatCd").name()).isEqualTo("tran_cat_cd");
    assertThat(column("tranCatCd").length()).isEqualTo(4);
    assertThat(column("tranDesc").name()).isEqualTo("tran_desc");
    assertThat(column("tranDesc").length()).isEqualTo(100);
    assertThat(column("tranCardNum").name()).isEqualTo("tran_card_num");
    assertThat(column("tranCardNum").length()).isEqualTo(16);
    assertThat(column("tranOrigTs").name()).isEqualTo("tran_orig_ts");
    assertThat(column("tranOrigTs").length()).isEqualTo(26);
    assertThat(column("tranProcTs").name()).isEqualTo("tran_proc_ts");
    assertThat(column("tranProcTs").length()).isEqualTo(26);

    assertThat(Transaction.class.getDeclaredField("tranMerchantId").getType())
        .isEqualTo(Long.class);
    assertThat(column("tranMerchantId").name()).isEqualTo("tran_merchant_id");
    assertThat(column("tranMerchantId").precision()).isEqualTo(9);
  }

  /**
   * Decimal-fidelity guard (Agent Action Plan &sect;0.6.1): no declared field may use a binary
   * floating-point type. Synthetic members (for example the JaCoCo {@code $jacocoData}
   * instrumentation field added during coverage runs) are skipped because they are compiler/agent
   * generated, not part of the modeled record.
   */
  @Test
  void hasNoFloatingPointFields() {
    for (Field field : Transaction.class.getDeclaredFields()) {
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
    return Transaction.class.getDeclaredField(fieldName).getAnnotation(Column.class);
  }
}
