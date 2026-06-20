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
 * Unit tests for {@link TransactionCategoryId}, the JPA composite key for table {@code
 * tran_category} (COBOL copybook {@code CVTRA04Y}, group {@code TRAN-CAT-KEY}; VSAM {@code
 * TRANCATG} KEYLEN 6 = 2 + 4). Verifies the value-object {@code equals}/{@code hashCode} contract
 * that makes JPA entity identity match VSAM composite-key semantics (AAP 0.6.2), fixed-length CHAR
 * leading-zero preservation, constructor/accessor round-trip, and the decimal-fidelity
 * no-floating-point guard (AAP 0.6.1). Pure POJO test: no Spring, no database.
 */
class TransactionCategoryIdTest {

  private static final String TYPE_CD = "05";
  private static final String CAT_CD = "0001";

  private static TransactionCategoryId newId() {
    return new TransactionCategoryId(TYPE_CD, CAT_CD);
  }

  @Test
  void allArgsConstructorPopulatesAllComponents() {
    TransactionCategoryId id = newId();
    assertThat(id.getTranTypeCd()).isEqualTo(TYPE_CD);
    assertThat(id.getTranCatCd()).isEqualTo(CAT_CD);
  }

  @Test
  void noArgConstructorThenSettersRoundTrip() {
    TransactionCategoryId id = new TransactionCategoryId();
    assertThat(id.getTranTypeCd()).isNull();
    assertThat(id.getTranCatCd()).isNull();
    id.setTranTypeCd(TYPE_CD);
    id.setTranCatCd(CAT_CD);
    assertThat(id.getTranTypeCd()).isEqualTo(TYPE_CD);
    assertThat(id.getTranCatCd()).isEqualTo(CAT_CD);
  }

  @Test
  void equalsIsReflexive() {
    TransactionCategoryId id = newId();
    assertThat(id.equals(id)).isTrue();
  }

  @Test
  void equalsIsSymmetricForIdenticalComponents() {
    TransactionCategoryId a = newId();
    TransactionCategoryId b = newId();
    assertThat(a.equals(b)).isTrue();
    assertThat(b.equals(a)).isTrue();
  }

  @Test
  void equalsIsTransitiveForIdenticalComponents() {
    TransactionCategoryId a = newId();
    TransactionCategoryId b = newId();
    TransactionCategoryId c = newId();
    assertThat(a.equals(b)).isTrue();
    assertThat(b.equals(c)).isTrue();
    assertThat(a.equals(c)).isTrue();
  }

  @Test
  void equalInstancesHaveEqualHashCode() {
    TransactionCategoryId a = newId();
    TransactionCategoryId b = newId();
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
  void notEqualWhenTypeCdDiffers() {
    TransactionCategoryId a = newId();
    TransactionCategoryId b = new TransactionCategoryId("99", CAT_CD);
    assertThat(a.equals(b)).isFalse();
  }

  @Test
  void notEqualWhenCatCdDiffers() {
    TransactionCategoryId a = newId();
    TransactionCategoryId b = new TransactionCategoryId(TYPE_CD, "9999");
    assertThat(a.equals(b)).isFalse();
  }

  @Test
  void fixedLengthFieldsPreserveLeadingZerosAndWidth() {
    TransactionCategoryId id = new TransactionCategoryId("05", "0001");
    assertThat(id.getTranCatCd()).isEqualTo("0001").hasSize(4);
    assertThat(id.getTranTypeCd()).isEqualTo("05").hasSize(2);
  }

  @Test
  void hasNoFloatingPointFields() {
    for (Field field : TransactionCategoryId.class.getDeclaredFields()) {
      if (field.isSynthetic()) {
        continue;
      }
      assertThat(field.getType())
          .as("field %s must not be floating-point", field.getName())
          .isNotIn(float.class, double.class, Float.class, Double.class);
    }
  }
}
