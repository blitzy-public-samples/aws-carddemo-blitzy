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

import com.aws.carddemo.domain.id.TransactionCategoryBalanceId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pure POJO unit tests for {@link TransactionCategoryBalance}, the JPA entity mapping the legacy
 * VSAM {@code TCATBALF} store (COBOL copybook {@code CVTRA01Y}, record {@code 01
 * TRAN-CAT-BAL-RECORD}, table {@code tran_cat_balance}).
 *
 * <p>No Spring, no database, no {@code @DataJpaTest}: these tests exercise the entity purely as a
 * data holder and pin its persistence-mapping metadata by reflection. The intent is to lock the two
 * parity-critical contracts the Agent Action Plan calls out for this row:
 *
 * <ul>
 *   <li><strong>Composite key wiring (AAP &sect;0.6.2).</strong> The 17-byte VSAM key {@code
 *       TRAN-CAT-KEY} (= {@code TRANCAT-ACCT-ID 9(11)} + {@code TRANCAT-TYPE-CD X(02)} + {@code
 *       TRANCAT-CD 9(04)}) is modeled as the {@link EmbeddedId}-annotated {@link
 *       TransactionCategoryBalanceId}. This test confirms the entity stores and returns that key;
 *       the full value-based {@code equals}/{@code hashCode} contract for the key itself is
 *       verified downstream in {@code com.aws.carddemo.domain.id.TransactionCategoryBalanceIdTest}
 *       and is deliberately not re-asserted here.
 *   <li><strong>Decimal fidelity (AAP &sect;0.6.1).</strong> {@code TRAN-CAT-BAL PIC S9(09)V99} is
 *       money and must be held as {@link BigDecimal} at scale 2 with a {@code numeric(11,2)} column
 *       ({@code precision = 11, scale = 2}) — never {@code float}/{@code double}. {@code
 *       tranCatBal} accumulates per-category posted amounts and is the upsert target of transaction
 *       posting (AAP &sect;0.6.4), so its cent-level scale must be preserved exactly.
 * </ul>
 */
class TransactionCategoryBalanceTest {

  /**
   * Composite-key round-trip (AAP &sect;0.6.2): an {@link TransactionCategoryBalanceId} built with
   * the all-args constructor (argument order {@code acctId, typeCd, catCd}) is stored through
   * {@code setId} and read back intact, and each key component is reachable through the entity.
   * This pins the {@code @EmbeddedId} wiring; the key's own equality contract is covered downstream
   * in the {@code domain/id} sub-package.
   */
  @Test
  void embeddedIdRoundTrips() {
    TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(12345678901L, "01", "0001");
    TransactionCategoryBalance entity = new TransactionCategoryBalance();
    entity.setId(id);

    assertThat(entity.getId()).isEqualTo(id);
    assertThat(entity.getId().getTrancatAcctId()).isEqualTo(12345678901L);
    assertThat(entity.getId().getTrancatTypeCd()).isEqualTo("01");
    assertThat(entity.getId().getTrancatCd()).isEqualTo("0001");
  }

  /**
   * Decimal-fidelity declaration check (AAP &sect;0.6.1): the {@code tranCatBal} field is declared
   * as {@link BigDecimal} and its Lombok-generated getter returns {@code BigDecimal}. Money must
   * never regress to {@code float}/{@code double}, which would silently corrupt cent-level
   * category-balance accumulation.
   */
  @Test
  void tranCatBalIsBigDecimalNotFloatingPoint() throws NoSuchFieldException, NoSuchMethodException {
    assertThat(TransactionCategoryBalance.class.getDeclaredField("tranCatBal").getType())
        .isEqualTo(BigDecimal.class);
    assertThat(TransactionCategoryBalance.class.getMethod("getTranCatBal").getReturnType())
        .isEqualTo(BigDecimal.class);
  }

  /**
   * Confirms the entity faithfully stores a {@link BigDecimal} at scale 2 with no lossy {@code
   * double} conversion: a value set with {@code new BigDecimal("100.45")} reads back numerically
   * equal and still carries {@code scale() == 2} (AAP &sect;0.6.1).
   */
  @Test
  void tranCatBalScaleIsPreservedAtScaleTwo() {
    TransactionCategoryBalance entity = new TransactionCategoryBalance();
    entity.setTranCatBal(new BigDecimal("100.45"));

    assertThat(entity.getTranCatBal()).isEqualByComparingTo(new BigDecimal("100.45"));
    assertThat(entity.getTranCatBal().scale()).isEqualTo(2);
  }

  /**
   * The monetary {@code @Column} declares {@code precision = 11, scale = 2}, the JPA mapping of
   * COBOL {@code TRAN-CAT-BAL PIC S9(09)V99} (9 integer digits + 2 fraction digits = {@code
   * numeric(11,2)}). This keeps the entity aligned with the {@code numeric(11,2)} column in the
   * Flyway migration so Hibernate schema {@code validate} succeeds at startup (AAP &sect;0.6.1).
   */
  @Test
  void tranCatBalColumnDeclaresPrecisionElevenScaleTwo() throws NoSuchFieldException {
    Column column =
        TransactionCategoryBalance.class.getDeclaredField("tranCatBal").getAnnotation(Column.class);

    assertThat(column.precision()).isEqualTo(11);
    assertThat(column.scale()).isEqualTo(2);
  }

  /**
   * Composite primary-key mapping (AAP &sect;0.6.2): the {@code id} field carries {@link
   * EmbeddedId} and is typed as the embeddable {@link TransactionCategoryBalanceId}, mirroring the
   * 17-byte VSAM {@code TRAN-CAT-KEY}.
   */
  @Test
  void idFieldIsAnnotatedEmbeddedId() throws NoSuchFieldException {
    Field idField = TransactionCategoryBalance.class.getDeclaredField("id");

    assertThat(idField.isAnnotationPresent(EmbeddedId.class)).isTrue();
    assertThat(idField.getType()).isEqualTo(TransactionCategoryBalanceId.class);
  }

  /**
   * Locks the JPA table mapping to the legacy {@code TCATBALF} store: {@code @Table} resolves to
   * {@code tran_cat_balance}.
   */
  @Test
  void tableNameIsTranCatBalance() {
    assertThat(TransactionCategoryBalance.class.getAnnotation(Table.class).name())
        .isEqualTo("tran_cat_balance");
  }

  /**
   * Hard decimal-fidelity guard across the whole entity (AAP &sect;0.6.1): iterate every declared
   * field, skip compiler/coverage synthetic members (for example JaCoCo's {@code $jacocoData}), and
   * assert that no field is {@code float}, {@code double}, {@link Float}, or {@link Double}. This
   * catches a floating-point regression on <em>any</em> field of the entity.
   */
  @Test
  void hasNoFloatingPointFields() {
    for (Field field : TransactionCategoryBalance.class.getDeclaredFields()) {
      if (field.isSynthetic()) {
        continue;
      }
      assertThat(field.getType())
          .as("field %s must not be a floating-point type", field.getName())
          .isNotIn(float.class, double.class, Float.class, Double.class);
    }
  }
}
