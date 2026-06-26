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
package com.aws.carddemo.domain.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TransactionCategoryBalanceId}, the JPA composite key for table {@code
 * tran_cat_balance} (COBOL copybook {@code CVTRA01Y}, group {@code TRAN-CAT-KEY}; VSAM {@code
 * TCATBALF} KEYLEN 17 = 11 + 2 + 4). Verifies the value-object {@code equals}/{@code hashCode}
 * contract that makes JPA entity identity match VSAM composite-key semantics (AAP 0.6.2),
 * fixed-length CHAR leading-zero preservation, constructor/accessor round-trip, and the
 * decimal-fidelity no-floating-point guard (AAP 0.6.1). Pure POJO test: no Spring, no database.
 */
class TransactionCategoryBalanceIdTest {

  private static final Long ACCT_ID = 12345678901L;
  private static final String TYPE_CD = "01";
  private static final String CAT_CD = "0001";

  private static TransactionCategoryBalanceId newId() {
    return new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD);
  }

  @Test
  void allArgsConstructorPopulatesAllComponents() {
    TransactionCategoryBalanceId id = newId();
    assertThat(id.getTrancatAcctId()).isEqualTo(ACCT_ID);
    assertThat(id.getTrancatTypeCd()).isEqualTo(TYPE_CD);
    assertThat(id.getTrancatCd()).isEqualTo(CAT_CD);
  }

  @Test
  void noArgConstructorThenSettersRoundTrip() {
    TransactionCategoryBalanceId id = new TransactionCategoryBalanceId();
    assertThat(id.getTrancatAcctId()).isNull();
    assertThat(id.getTrancatTypeCd()).isNull();
    assertThat(id.getTrancatCd()).isNull();
    id.setTrancatAcctId(ACCT_ID);
    id.setTrancatTypeCd(TYPE_CD);
    id.setTrancatCd(CAT_CD);
    assertThat(id.getTrancatAcctId()).isEqualTo(ACCT_ID);
    assertThat(id.getTrancatTypeCd()).isEqualTo(TYPE_CD);
    assertThat(id.getTrancatCd()).isEqualTo(CAT_CD);
  }

  @Test
  void equalsIsReflexive() {
    TransactionCategoryBalanceId id = newId();
    assertThat(id.equals(id)).isTrue();
  }

  @Test
  void equalsIsSymmetricForIdenticalComponents() {
    TransactionCategoryBalanceId a = newId();
    TransactionCategoryBalanceId b = newId();
    assertThat(a.equals(b)).isTrue();
    assertThat(b.equals(a)).isTrue();
  }

  @Test
  void equalsIsTransitiveForIdenticalComponents() {
    TransactionCategoryBalanceId a = newId();
    TransactionCategoryBalanceId b = newId();
    TransactionCategoryBalanceId c = newId();
    assertThat(a.equals(b)).isTrue();
    assertThat(b.equals(c)).isTrue();
    assertThat(a.equals(c)).isTrue();
  }

  @Test
  void equalInstancesHaveEqualHashCode() {
    TransactionCategoryBalanceId a = newId();
    TransactionCategoryBalanceId b = newId();
    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  @Test
  void equalsIsNullSafe() {
    assertThat(newId().equals(null)).isFalse();
  }

  @Test
  void equalsIsTypeSafe() {
    assertThat(newId().equals("not-an-id")).isFalse();
  }

  @Test
  void notEqualWhenAcctIdDiffers() {
    TransactionCategoryBalanceId a = newId();
    TransactionCategoryBalanceId b =
        new TransactionCategoryBalanceId(99999999999L, TYPE_CD, CAT_CD);
    assertThat(a.equals(b)).isFalse();
  }

  @Test
  void notEqualWhenTypeCdDiffers() {
    TransactionCategoryBalanceId a = newId();
    TransactionCategoryBalanceId b = new TransactionCategoryBalanceId(ACCT_ID, "99", CAT_CD);
    assertThat(a.equals(b)).isFalse();
  }

  @Test
  void notEqualWhenCatCdDiffers() {
    TransactionCategoryBalanceId a = newId();
    TransactionCategoryBalanceId b = new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, "9999");
    assertThat(a.equals(b)).isFalse();
  }

  @Test
  void fixedLengthCodePreservesLeadingZerosAndWidth() {
    TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(ACCT_ID, "01", "0001");
    assertThat(id.getTrancatCd()).isEqualTo("0001").hasSize(4);
    assertThat(id.getTrancatTypeCd()).isEqualTo("01").hasSize(2);
  }

  @Test
  void hasNoFloatingPointFields() {
    for (Field field : TransactionCategoryBalanceId.class.getDeclaredFields()) {
      if (field.isSynthetic()) {
        continue;
      }
      assertThat(field.getType())
          .as("field %s must not be floating-point", field.getName())
          .isNotIn(float.class, double.class, Float.class, Double.class);
    }
  }
}
