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
 * Unit tests for {@link DisclosureGroupId}, the JPA composite key for table {@code
 * disclosure_group} (COBOL copybook {@code CVTRA02Y}, group {@code DIS-GROUP-KEY}; VSAM {@code
 * DISCGRP} KEYLEN 16 = 10 + 2 + 4). Verifies the value-object {@code equals}/{@code hashCode}
 * contract that makes JPA entity identity match VSAM composite-key semantics (AAP 0.6.2),
 * fixed-length CHAR leading-zero preservation, constructor/accessor round-trip, and the
 * decimal-fidelity no-floating-point guard (AAP 0.6.1). Pure POJO test: no Spring, no database.
 */
class DisclosureGroupIdTest {

  private static final String GROUP_ID = "ACCTGROUP1";
  private static final String TYPE_CD = "01";
  private static final String CAT_CD = "0001";

  private static DisclosureGroupId newId() {
    return new DisclosureGroupId(GROUP_ID, TYPE_CD, CAT_CD);
  }

  @Test
  void allArgsConstructorPopulatesAllComponents() {
    DisclosureGroupId id = newId();
    assertThat(id.getDisAcctGroupId()).isEqualTo(GROUP_ID);
    assertThat(id.getDisTranTypeCd()).isEqualTo(TYPE_CD);
    assertThat(id.getDisTranCatCd()).isEqualTo(CAT_CD);
  }

  @Test
  void noArgConstructorThenSettersRoundTrip() {
    DisclosureGroupId id = new DisclosureGroupId();
    assertThat(id.getDisAcctGroupId()).isNull();
    assertThat(id.getDisTranTypeCd()).isNull();
    assertThat(id.getDisTranCatCd()).isNull();
    id.setDisAcctGroupId(GROUP_ID);
    id.setDisTranTypeCd(TYPE_CD);
    id.setDisTranCatCd(CAT_CD);
    assertThat(id.getDisAcctGroupId()).isEqualTo(GROUP_ID);
    assertThat(id.getDisTranTypeCd()).isEqualTo(TYPE_CD);
    assertThat(id.getDisTranCatCd()).isEqualTo(CAT_CD);
  }

  @Test
  void equalsIsReflexive() {
    DisclosureGroupId id = newId();
    assertThat(id.equals(id)).isTrue();
  }

  @Test
  void equalsIsSymmetricForIdenticalComponents() {
    DisclosureGroupId a = newId();
    DisclosureGroupId b = newId();
    assertThat(a.equals(b)).isTrue();
    assertThat(b.equals(a)).isTrue();
  }

  @Test
  void equalsIsTransitiveForIdenticalComponents() {
    DisclosureGroupId a = newId();
    DisclosureGroupId b = newId();
    DisclosureGroupId c = newId();
    assertThat(a.equals(b)).isTrue();
    assertThat(b.equals(c)).isTrue();
    assertThat(a.equals(c)).isTrue();
  }

  @Test
  void equalInstancesHaveEqualHashCode() {
    DisclosureGroupId a = newId();
    DisclosureGroupId b = newId();
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
  void notEqualWhenAcctGroupIdDiffers() {
    DisclosureGroupId a = newId();
    DisclosureGroupId b = new DisclosureGroupId("OTHERGROUP", TYPE_CD, CAT_CD);
    assertThat(a.equals(b)).isFalse();
  }

  @Test
  void notEqualWhenTranTypeCdDiffers() {
    DisclosureGroupId a = newId();
    DisclosureGroupId b = new DisclosureGroupId(GROUP_ID, "99", CAT_CD);
    assertThat(a.equals(b)).isFalse();
  }

  @Test
  void notEqualWhenTranCatCdDiffers() {
    DisclosureGroupId a = newId();
    DisclosureGroupId b = new DisclosureGroupId(GROUP_ID, TYPE_CD, "9999");
    assertThat(a.equals(b)).isFalse();
  }

  @Test
  void fixedLengthFieldsPreserveLeadingZerosAndWidth() {
    DisclosureGroupId id = new DisclosureGroupId("ACCTGROUP1", "01", "0001");
    assertThat(id.getDisTranCatCd()).isEqualTo("0001").hasSize(4);
    assertThat(id.getDisAcctGroupId()).isEqualTo("ACCTGROUP1").hasSize(10);
    assertThat(id.getDisTranTypeCd()).isEqualTo("01").hasSize(2);
  }

  @Test
  void hasNoFloatingPointFields() {
    for (Field field : DisclosureGroupId.class.getDeclaredFields()) {
      if (field.isSynthetic()) {
        continue;
      }
      assertThat(field.getType())
          .as("field %s must not be floating-point", field.getName())
          .isNotIn(float.class, double.class, Float.class, Double.class);
    }
  }
}
