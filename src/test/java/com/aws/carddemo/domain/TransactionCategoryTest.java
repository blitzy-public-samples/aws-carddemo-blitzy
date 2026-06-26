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

import com.aws.carddemo.domain.id.TransactionCategoryId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link TransactionCategory}, the JPA entity migrated from the legacy z/OS
 * COBOL copybook {@code legacy/app/cpy/CVTRA04Y.cpy} (record {@code 01 TRAN-CAT-RECORD}, table
 * {@code tran_category}, VSAM {@code TRANCATG} composite key {@code TRAN-TYPE-CD + TRAN-CAT-CD},
 * KEYLEN 6).
 *
 * <p>These tests are intentionally framework-light — no Spring context, no database, no
 * Testcontainers, and no {@code @DataJpaTest}. They exercise only the plain Java object model with
 * {@code new TransactionCategory()} and Java reflection, matching the production class's
 * framework-free design and keeping the suite fast and deterministic (AAP &sect;0.6.7).
 *
 * <p>They pin the entity-wiring invariants that must not drift:
 *
 * <ul>
 *   <li>the {@code @EmbeddedId} exposes the correct {@link TransactionCategoryId} type and
 *       round-trips a constructed key (AAP &sect;0.6.2 composite-key semantics);
 *   <li>the Lombok-generated description accessor round-trips its value;
 *   <li>the entity maps to table {@code tran_category};
 *   <li>{@code tranCatTypeDesc} is bound to column {@code tran_cat_type_desc char(50)} (COBOL
 *       {@code TRAN-CAT-TYPE-DESC PIC X(50)}); and
 *   <li>no field uses a binary floating-point type — the decimal-fidelity guard shared by every
 *       entity test in this package (AAP &sect;0.6.1).
 * </ul>
 *
 * <p>The behavioral {@code equals}/{@code hashCode} contract for the composite key is verified
 * separately in {@code com.aws.carddemo.domain.id.TransactionCategoryIdTest}; this entity-level
 * test deliberately stays at the wiring level to avoid duplicating that coverage. The production
 * entity is authoritative: if any accessor, field, or annotation name differs, the test is the side
 * that must change.
 */
class TransactionCategoryTest {

  /**
   * The {@code @EmbeddedId} key set on the entity is the key returned by {@code getId()}, and its
   * two character components round-trip unchanged — the COBOL {@code TRAN-CAT-KEY} group ({@code
   * TRAN-TYPE-CD} + {@code TRAN-CAT-CD}) carried as the composite primary key (AAP &sect;0.6.2).
   * This pins the entity-to-key wiring, not the key's value-equality contract, which is covered by
   * {@code TransactionCategoryIdTest}.
   */
  @Test
  void embeddedIdRoundTrips() {
    TransactionCategoryId id = new TransactionCategoryId("01", "0001");
    TransactionCategory e = new TransactionCategory();
    e.setId(id);

    assertThat(e.getId()).isEqualTo(id);
    assertThat(e.getId().getTranTypeCd()).isEqualTo("01");
    assertThat(e.getId().getTranCatCd()).isEqualTo("0001");
  }

  /**
   * The Lombok-generated description accessor round-trips its value — COBOL {@code
   * TRAN-CAT-TYPE-DESC PIC X(50)}.
   */
  @Test
  void descriptionRoundTrips() {
    TransactionCategory e = new TransactionCategory();
    e.setTranCatTypeDesc("PURCHASE - RETAIL");

    assertThat(e.getTranCatTypeDesc()).isEqualTo("PURCHASE - RETAIL");
  }

  /**
   * The {@code id} field is the JPA {@link EmbeddedId} and is typed as the composite-key class
   * {@link TransactionCategoryId}.
   */
  @Test
  void idFieldIsAnnotatedEmbeddedId() throws NoSuchFieldException {
    Field field = TransactionCategory.class.getDeclaredField("id");

    assertThat(field.isAnnotationPresent(EmbeddedId.class)).isTrue();
    assertThat(field.getType()).isEqualTo(TransactionCategoryId.class);
  }

  /** The entity maps to the {@code tran_category} reference table. */
  @Test
  void tableNameIsTranCategory() {
    assertThat(TransactionCategory.class.getAnnotation(Table.class).name())
        .isEqualTo("tran_category");
  }

  /**
   * {@code tranCatTypeDesc} maps to {@code char(50)} column {@code tran_cat_type_desc} (COBOL
   * {@code TRAN-CAT-TYPE-DESC PIC X(50)}).
   */
  @Test
  void descriptionColumnMappingIsCorrect() throws NoSuchFieldException {
    Field field = TransactionCategory.class.getDeclaredField("tranCatTypeDesc");
    Column column = field.getAnnotation(Column.class);

    assertThat(column.name()).isEqualTo("tran_cat_type_desc");
    assertThat(column.length()).isEqualTo(50);
  }

  /**
   * Decimal-fidelity guard (AAP &sect;0.6.1): no declared field may use a binary floating-point
   * type. Synthetic fields (e.g. the JaCoCo {@code $jacocoData} instrumentation field added during
   * coverage runs) are skipped because they are compiler/agent generated, not part of the modeled
   * record.
   */
  @Test
  void hasNoFloatingPointFields() {
    for (Field field : TransactionCategory.class.getDeclaredFields()) {
      if (field.isSynthetic()) {
        continue;
      }
      assertThat(field.getType()).isNotIn(float.class, double.class, Float.class, Double.class);
    }
  }
}
