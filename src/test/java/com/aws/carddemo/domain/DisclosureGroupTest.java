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

import com.aws.carddemo.domain.id.DisclosureGroupId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pure POJO unit tests for {@link DisclosureGroup}, the JPA entity migrated from the legacy z/OS
 * COBOL copybook {@code legacy/app/cpy/CVTRA02Y.cpy} (record {@code 01 DIS-GROUP-RECORD}, RECLN 50,
 * table {@code disclosure_group}). The entity maps the legacy VSAM {@code DISCGRP} KSDS, whose
 * composite key has a {@code KEYLEN} of 16 bytes ({@code DIS-ACCT-GROUP-ID X(10)} + {@code
 * DIS-TRAN-TYPE-CD X(02)} + {@code DIS-TRAN-CAT-CD 9(04)}).
 *
 * <p>These tests are intentionally framework-light — no Spring context, no database, no
 * Testcontainers, and no {@code @DataJpaTest}. They construct the POJO directly with {@code new
 * DisclosureGroup()} and assert with AssertJ and Java reflection only, matching the production
 * class's framework-free design and keeping the suite fast and deterministic (AAP &sect;0.6.7).
 *
 * <p>They pin the COBOL-to-Java parity invariants that must not drift:
 *
 * <ul>
 *   <li>the {@link EmbeddedId} composite key round-trips through {@code getId()}/{@code setId(...)}
 *       and exposes its three component values (AAP &sect;0.6.2 — VSAM composite key);
 *   <li>{@code disIntRate} is a {@link java.math.BigDecimal} and <em>never</em> {@code
 *       float}/{@code double}, and stores its value at scale 2 without lossy conversion (COBOL
 *       {@code DIS-INT-RATE PIC S9(04)V99});
 *   <li>the {@code dis_int_rate} {@code @Column} declares {@code precision = 6, scale = 2},
 *       matching the {@code numeric(6,2)} column of the Flyway migration so Hibernate {@code
 *       validate} succeeds (AAP &sect;0.6.1);
 *   <li>the {@code id} field carries {@link EmbeddedId} and is typed {@link DisclosureGroupId}; and
 *   <li>the entity maps to table {@code disclosure_group} and uses no binary floating-point type
 *       anywhere — the decimal-fidelity guard shared by every entity test in this package (AAP
 *       &sect;0.6.1).
 * </ul>
 *
 * <p>The disclosure interest rate guarded here is the rate input to the unrounded COBOL interest
 * computation {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} (AAP &sect;0.6.1); modeling it as a
 * scale-2 {@link java.math.BigDecimal} is a precondition for the truncation parity that the
 * interest-calculation service must achieve. The full value-based {@code equals}/{@code hashCode}
 * contract for the composite key is verified downstream in {@code
 * com.aws.carddemo.domain.id.DisclosureGroupIdTest}, not here.
 *
 * <p>The production entity is authoritative: if any accessor, field, or annotation drifts from what
 * is asserted here, the entity (and the migration it mirrors) is the side to scrutinize — these
 * parity locks must not be weakened.
 */
class DisclosureGroupTest {

  /**
   * The {@link EmbeddedId} composite key round-trips: a fully populated {@link DisclosureGroupId}
   * set via {@code setId(...)} is returned intact by {@code getId()}, and each of its three
   * component getters reads back the value supplied to the all-args constructor (argument order:
   * account group id, transaction type code, transaction category code). This locks the
   * composite-key wiring (AAP &sect;0.6.2); the key's value-equality contract is exercised
   * separately in the {@code id/} subpackage.
   */
  @Test
  void embeddedIdRoundTrips() {
    DisclosureGroupId id = new DisclosureGroupId("ZEROAPR  ", "01", "0001");

    DisclosureGroup e = new DisclosureGroup();
    e.setId(id);

    assertThat(e.getId()).isEqualTo(id);
    assertThat(e.getId().getDisAcctGroupId()).isEqualTo("ZEROAPR  ");
    assertThat(e.getId().getDisTranTypeCd()).isEqualTo("01");
    assertThat(e.getId().getDisTranCatCd()).isEqualTo("0001");
  }

  /**
   * The core decimal-fidelity assertion (AAP &sect;0.6.1): the disclosure interest rate field is
   * declared as {@link java.math.BigDecimal}, and its Lombok-generated getter returns {@code
   * BigDecimal} as well. The rate must never regress to {@code float} or {@code double}, which
   * would silently corrupt the cent-level interest arithmetic that consumes it.
   */
  @Test
  void disIntRateIsBigDecimalNotFloatingPoint() throws NoSuchFieldException, NoSuchMethodException {
    assertThat(DisclosureGroup.class.getDeclaredField("disIntRate").getType())
        .isEqualTo(BigDecimal.class);
    assertThat(DisclosureGroup.class.getMethod("getDisIntRate").getReturnType())
        .isEqualTo(BigDecimal.class);
  }

  /**
   * Confirms the entity faithfully stores a {@link java.math.BigDecimal} at scale 2 with no lossy
   * double conversion: a value set with {@code new BigDecimal("19.99")} is read back numerically
   * equal and still carries {@code scale() == 2}, mirroring the COBOL {@code PIC S9(04)V99}
   * two-decimal scale.
   */
  @Test
  void disIntRateScaleIsPreservedAtScaleTwo() {
    DisclosureGroup e = new DisclosureGroup();

    e.setDisIntRate(new BigDecimal("19.99"));

    assertThat(e.getDisIntRate()).isEqualByComparingTo(new BigDecimal("19.99"));
    assertThat(e.getDisIntRate().scale()).isEqualTo(2);
  }

  /**
   * The {@code dis_int_rate} {@code @Column} declares {@code precision = 6, scale = 2}, the JPA
   * mapping of COBOL {@code PIC S9(04)V99} (4 integer digits + 2 fraction digits = {@code
   * numeric(6,2)}). This keeps the entity aligned with the {@code numeric(6,2)} column in the
   * Flyway migration so Hibernate schema {@code validate} succeeds at startup (AAP &sect;0.6.1).
   */
  @Test
  void disIntRateColumnDeclaresPrecisionSixScaleTwo() throws NoSuchFieldException {
    Column column =
        DisclosureGroup.class.getDeclaredField("disIntRate").getAnnotation(Column.class);

    assertThat(column.precision()).isEqualTo(6);
    assertThat(column.scale()).isEqualTo(2);
  }

  /**
   * The {@code id} field is the composite primary key: it carries {@link EmbeddedId} and is typed
   * {@link DisclosureGroupId} (COBOL {@code 05 DIS-GROUP-KEY} group; VSAM {@code DISCGRP} KEYLEN
   * 16, AAP &sect;0.6.2).
   */
  @Test
  void idFieldIsAnnotatedEmbeddedId() throws NoSuchFieldException {
    Field idField = DisclosureGroup.class.getDeclaredField("id");

    assertThat(idField.isAnnotationPresent(EmbeddedId.class)).isTrue();
    assertThat(idField.getType()).isEqualTo(DisclosureGroupId.class);
  }

  /** The entity maps to the {@code disclosure_group} reference table. */
  @Test
  void tableNameIsDisclosureGroup() {
    assertThat(DisclosureGroup.class.getAnnotation(Table.class).name())
        .isEqualTo("disclosure_group");
  }

  /**
   * Decimal-fidelity guard (AAP &sect;0.6.1): no declared field may use a binary floating-point
   * type. Synthetic fields (e.g. the JaCoCo {@code $jacocoData} instrumentation field added during
   * coverage runs) are skipped because they are compiler/agent generated, not part of the modeled
   * record.
   */
  @Test
  void hasNoFloatingPointFields() {
    for (Field field : DisclosureGroup.class.getDeclaredFields()) {
      if (field.isSynthetic()) {
        continue;
      }
      assertThat(field.getType())
          .as("field %s must not be a floating-point type", field.getName())
          .isNotIn(float.class, double.class, Float.class, Double.class);
    }
  }
}
