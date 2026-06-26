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
 * Pure POJO unit tests for {@link DailyTransaction}, the JPA entity translation of the COBOL
 * copybook {@code CVTRA06Y.cpy} ({@code 01 DALYTRAN-RECORD}, RECLN 350, legacy source {@code
 * legacy/app/cpy/CVTRA06Y.cpy}). The entity maps the inbound daily-transaction sequential feed onto
 * the {@code daily_transaction} table; it shares the {@code Transaction} layout but uses the {@code
 * dalytran_} column prefix and carries no alternate index (Agent Action Plan &sect;0.4.1).
 *
 * <p>These tests pin the <strong>COBOL-to-JPA parity invariants</strong> the daily-posting and
 * reject-processing batch depend on (Agent Action Plan &sect;0.6.1, &sect;0.6.6):
 *
 * <ul>
 *   <li>all 13 modeled fields round-trip through their Lombok-generated getters and setters;
 *   <li>the monetary amount {@code dalytranAmt} is a {@link BigDecimal} — never a binary
 *       floating-point type — with scale 2 and a {@code numeric(11,2)} column, so posted amounts
 *       reconcile to the cent with {@code Transaction.tranAmt};
 *   <li>the entity is mapped to the {@code daily_transaction} table with {@code dalytranId} as the
 *       {@code @Id}; and
 *   <li>the snake_case {@code @Column} names and fixed-width lengths match the migration DDL
 *       exactly so Hibernate {@code validate} succeeds.
 * </ul>
 *
 * <p>This is intentionally a framework-light test: it constructs the POJO directly with {@code new}
 * and asserts with AssertJ only. There is no Spring context, database, {@code @DataJpaTest},
 * Testcontainers, or Mockito. The production entity is authoritative: if any accessor, field, or
 * annotation differs, the test is the side that must change.
 */
class DailyTransactionTest {

  /**
   * Exercises every accessor pair: sets all 13 modeled fields to synthetic, in-bounds values and
   * confirms each getter returns exactly what was set. The 26-character timestamps mirror the
   * fixed-width {@code X(26)} COBOL fields, and the amount is built from a string literal so its
   * scale is locked.
   */
  @Test
  void gettersAndSettersRoundTrip() {
    DailyTransaction transaction = new DailyTransaction();

    transaction.setDalytranId("0000000000000001");
    transaction.setDalytranTypeCd("01");
    transaction.setDalytranCatCd("0005");
    transaction.setDalytranSource("POS");
    transaction.setDalytranDesc("RETAIL PURCHASE - GROCERY STORE");
    transaction.setDalytranAmt(new BigDecimal("987654.32"));
    transaction.setDalytranMerchantId(123456789L);
    transaction.setDalytranMerchantName("ACME CORPORATION");
    transaction.setDalytranMerchantCity("DALLAS");
    transaction.setDalytranMerchantZip("75001");
    transaction.setDalytranCardNum("4111111111111111");
    transaction.setDalytranOrigTs("2022-07-18 12:34:56.123456");
    transaction.setDalytranProcTs("2022-07-18 23:59:59.654321");

    assertThat(transaction.getDalytranId()).isEqualTo("0000000000000001");
    assertThat(transaction.getDalytranTypeCd()).isEqualTo("01");
    assertThat(transaction.getDalytranCatCd()).isEqualTo("0005");
    assertThat(transaction.getDalytranSource()).isEqualTo("POS");
    assertThat(transaction.getDalytranDesc()).isEqualTo("RETAIL PURCHASE - GROCERY STORE");
    assertThat(transaction.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("987654.32"));
    assertThat(transaction.getDalytranMerchantId()).isEqualTo(123456789L);
    assertThat(transaction.getDalytranMerchantName()).isEqualTo("ACME CORPORATION");
    assertThat(transaction.getDalytranMerchantCity()).isEqualTo("DALLAS");
    assertThat(transaction.getDalytranMerchantZip()).isEqualTo("75001");
    assertThat(transaction.getDalytranCardNum()).isEqualTo("4111111111111111");
    assertThat(transaction.getDalytranOrigTs()).isEqualTo("2022-07-18 12:34:56.123456");
    assertThat(transaction.getDalytranProcTs()).isEqualTo("2022-07-18 23:59:59.654321");
  }

  /**
   * The monetary amount is modeled as {@link BigDecimal} on both the field and its getter — never a
   * binary floating-point type — guarding decimal fidelity (Agent Action Plan &sect;0.6.1).
   */
  @Test
  void dalytranAmtIsBigDecimalNotFloatingPoint()
      throws NoSuchFieldException, NoSuchMethodException {
    assertThat(field("dalytranAmt").getType()).isEqualTo(BigDecimal.class);
    assertThat(DailyTransaction.class.getMethod("getDalytranAmt").getReturnType())
        .isEqualTo(BigDecimal.class);
  }

  /**
   * A {@code S9(09)V99} amount keeps scale 2 after a round-trip, matching the COBOL declared scale
   * so cent-level totals reconcile (Agent Action Plan &sect;0.6.1).
   */
  @Test
  void dalytranAmtScaleIsPreservedAtScaleTwo() {
    DailyTransaction transaction = new DailyTransaction();
    transaction.setDalytranAmt(new BigDecimal("100.45"));

    assertThat(transaction.getDalytranAmt()).isEqualByComparingTo(new BigDecimal("100.45"));
    assertThat(transaction.getDalytranAmt().scale()).isEqualTo(2);
  }

  /**
   * The {@code dalytran_amt} column declares {@code numeric(11,2)} — precision 11, scale 2 —
   * matching COBOL {@code PIC S9(09)V99} (nine integer digits plus two fractional).
   */
  @Test
  void dalytranAmtColumnDeclaresPrecisionElevenScaleTwo() throws NoSuchFieldException {
    Column column = column("dalytranAmt");

    assertThat(column.precision()).isEqualTo(11);
    assertThat(column.scale()).isEqualTo(2);
  }

  /**
   * The entity maps to the {@code daily_transaction} table and {@code dalytranId} is the {@link Id}
   * primary key bound to {@code char(16)} column {@code dalytran_id} (COBOL {@code DALYTRAN-ID PIC
   * X(16)}).
   */
  @Test
  void tableNameIsDailyTransactionAndPrimaryKeyIsDalytranId() throws NoSuchFieldException {
    assertThat(DailyTransaction.class.getAnnotation(Table.class).name())
        .isEqualTo("daily_transaction");

    Field dalytranId = field("dalytranId");
    assertThat(dalytranId.isAnnotationPresent(Id.class)).isTrue();
    assertThat(dalytranId.getType()).isEqualTo(String.class);

    Column column = dalytranId.getAnnotation(Column.class);
    assertThat(column.name()).isEqualTo("dalytran_id");
    assertThat(column.length()).isEqualTo(16);
  }

  /**
   * Spot-checks a representative set of {@code @Column} mappings to lock the snake_case naming and
   * fixed-width lengths the Flyway migration DDL expects, and confirms the merchant identifier is a
   * genuine-numeric {@link Long} with a {@code numeric(9)} column.
   */
  @Test
  void representativeColumnMappings() throws NoSuchFieldException {
    assertThat(column("dalytranTypeCd").name()).isEqualTo("dalytran_type_cd");
    assertThat(column("dalytranTypeCd").length()).isEqualTo(2);

    assertThat(column("dalytranCatCd").name()).isEqualTo("dalytran_cat_cd");
    assertThat(column("dalytranCatCd").length()).isEqualTo(4);

    assertThat(column("dalytranCardNum").name()).isEqualTo("dalytran_card_num");
    assertThat(column("dalytranCardNum").length()).isEqualTo(16);

    assertThat(column("dalytranProcTs").name()).isEqualTo("dalytran_proc_ts");
    assertThat(column("dalytranProcTs").length()).isEqualTo(26);

    Field merchantId = field("dalytranMerchantId");
    assertThat(merchantId.getType()).isEqualTo(Long.class);

    Column merchantIdColumn = merchantId.getAnnotation(Column.class);
    assertThat(merchantIdColumn.name()).isEqualTo("dalytran_merchant_id");
    assertThat(merchantIdColumn.precision()).isEqualTo(9);
  }

  /**
   * Decimal-fidelity guard (Agent Action Plan &sect;0.6.1): no declared field may use a binary
   * floating-point type. Synthetic fields (for example JaCoCo's {@code $jacocoData} instrumentation
   * field added during coverage runs) are skipped because they are compiler/agent generated, not
   * part of the modeled record.
   */
  @Test
  void hasNoFloatingPointFields() {
    for (Field field : DailyTransaction.class.getDeclaredFields()) {
      if (field.isSynthetic()) {
        continue;
      }
      assertThat(field.getType())
          .as("field %s must not be a floating-point type", field.getName())
          .isNotIn(float.class, double.class, Float.class, Double.class);
    }
  }

  /** Resolves the declared entity {@link Field} with the given name. */
  private static Field field(String fieldName) throws NoSuchFieldException {
    return DailyTransaction.class.getDeclaredField(fieldName);
  }

  /** Resolves the {@link Column} annotation declared on the named entity field. */
  private static Column column(String fieldName) throws NoSuchFieldException {
    return field(fieldName).getAnnotation(Column.class);
  }
}
