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
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link TransactionType}, the JPA entity migrated from the legacy z/OS COBOL
 * copybook {@code legacy/app/cpy/CVTRA03Y.cpy} (record {@code 01 TRAN-TYPE-RECORD}, table {@code
 * tran_type}).
 *
 * <p>These tests are intentionally framework-light — no Spring context, no database, no
 * Testcontainers, and no {@code @DataJpaTest}. They exercise only the plain Java object model with
 * {@code new TransactionType()} and Java reflection, matching the production class's framework-free
 * design and keeping the suite fast and deterministic (AAP &sect;0.6.7).
 *
 * <p>They pin the COBOL-to-Java parity invariants that must not drift:
 *
 * <ul>
 *   <li>the Lombok-generated accessors round-trip the two character fields, and a freshly
 *       constructed instance leaves both unset ({@code null});
 *   <li>the entity maps to table {@code tran_type} and is annotated {@link Entity};
 *   <li>{@code tranType} is the {@link Id} primary key bound to column {@code tran_type char(2)}
 *       (COBOL {@code TRAN-TYPE PIC X(02)});
 *   <li>{@code tranTypeDesc} is bound to column {@code tran_type_desc char(50)} (COBOL {@code
 *       TRAN-TYPE-DESC PIC X(50)}); and
 *   <li>no field uses a binary floating-point type — the decimal-fidelity guard shared by every
 *       entity test in this package (AAP &sect;0.6.1).
 * </ul>
 *
 * <p>The production entity is authoritative: if any accessor, field, or annotation name differs,
 * the test is the side that must change.
 */
class TransactionTypeTest {

  /**
   * The two character accessors round-trip their values, and a freshly constructed entity leaves
   * both fields unset ({@code null}) — mirroring the COBOL record before any {@code MOVE} populates
   * it.
   */
  @Test
  void gettersAndSettersRoundTrip() {
    TransactionType t = new TransactionType();
    t.setTranType("01");
    t.setTranTypeDesc("PURCHASE");

    assertThat(t.getTranType()).isEqualTo("01");
    assertThat(t.getTranTypeDesc()).isEqualTo("PURCHASE");

    assertThat(new TransactionType().getTranType()).isNull();
    assertThat(new TransactionType().getTranTypeDesc()).isNull();
  }

  /** The entity is an {@link Entity} mapped to the {@code tran_type} reference table. */
  @Test
  void tableNameIsTranType() {
    assertThat(TransactionType.class.getAnnotation(Table.class).name()).isEqualTo("tran_type");
    assertThat(TransactionType.class.isAnnotationPresent(Entity.class)).isTrue();
  }

  /**
   * {@code tranType} is the {@link Id} primary key and maps to {@code char(2)} column {@code
   * tran_type} (COBOL {@code TRAN-TYPE PIC X(02)}).
   */
  @Test
  void primaryKeyFieldIsTranType() throws NoSuchFieldException {
    Field field = TransactionType.class.getDeclaredField("tranType");
    Column column = field.getAnnotation(Column.class);

    assertThat(field.isAnnotationPresent(Id.class)).isTrue();
    assertThat(column.name()).isEqualTo("tran_type");
    assertThat(column.length()).isEqualTo(2);
  }

  /**
   * {@code tranTypeDesc} maps to {@code char(50)} column {@code tran_type_desc} (COBOL {@code
   * TRAN-TYPE-DESC PIC X(50)}).
   */
  @Test
  void descriptionColumnMappingIsCorrect() throws NoSuchFieldException {
    Field field = TransactionType.class.getDeclaredField("tranTypeDesc");
    Column column = field.getAnnotation(Column.class);

    assertThat(column.name()).isEqualTo("tran_type_desc");
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
    for (Field field : TransactionType.class.getDeclaredFields()) {
      if (field.isSynthetic()) {
        continue;
      }
      assertThat(field.getType()).isNotIn(float.class, double.class, Float.class, Double.class);
    }
  }
}
