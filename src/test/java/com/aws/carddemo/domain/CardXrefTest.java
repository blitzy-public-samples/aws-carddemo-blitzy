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
import org.junit.jupiter.api.Test;

/**
 * Pure POJO unit tests for {@link CardXref}, the JPA entity translation of the legacy z/OS COBOL
 * copybook {@code legacy/app/cpy/CVACT03Y.cpy} ({@code 01 CARD-XREF-RECORD}, record length 50). The
 * entity maps the legacy VSAM {@code CARDXREF} KSDS — primary key {@code XREF-CARD-NUM} (KEYLEN
 * 16), non-unique alternate index on {@code XREF-ACCT-ID} — onto the {@code card_xref} table.
 *
 * <p>These tests are intentionally framework-light: there is no Spring context, no database, no
 * Testcontainers, and no {@code @DataJpaTest}. They construct the POJO directly with {@code new
 * CardXref()} and use Java reflection plus AssertJ only, mirroring the production class's
 * framework-free design and keeping the suite fast and deterministic (Agent Action Plan
 * &sect;0.6.7).
 *
 * <p>They pin the <strong>COBOL-to-JPA parity invariants</strong> that the persistence layer
 * depends on and that must not silently drift (Agent Action Plan &sect;0.6.1, &sect;0.6.2):
 *
 * <ul>
 *   <li>the three modeled fields round-trip through their Lombok-generated accessors, and a freshly
 *       constructed instance leaves every field unset ({@code null});
 *   <li>the 16-character card number is a fixed-width {@link String} (COBOL {@code XREF-CARD-NUM
 *       PIC X(16)}), preserving VSAM blank-padding/ordering semantics, while the customer and
 *       account identifiers are genuine-numeric {@link Long} values (COBOL {@code 9(09)} / {@code
 *       9(11)}) — never {@code String} and never a binary floating-point type;
 *   <li>the entity is mapped to the {@code card_xref} table with {@code xrefCardNum} as the
 *       {@code @Id}; and
 *   <li>the snake_case {@code @Column} names, the {@code length}, and the {@code precision} values
 *       match the Flyway migration DDL exactly so Hibernate {@code validate} succeeds at startup.
 * </ul>
 *
 * <p>The production entity is authoritative: if any accessor, field, or annotation differs, the
 * test is the side that must change.
 */
class CardXrefTest {

  /**
   * Every accessor pair round-trips its value, and a freshly constructed entity leaves all three
   * fields unset ({@code null}) — mirroring the COBOL record before any {@code MOVE} populates it.
   * The account identifier deliberately uses an 11-digit value that exceeds the 32-bit {@code int}
   * range to demonstrate the {@link Long} mapping (COBOL {@code XREF-ACCT-ID PIC 9(11)}).
   */
  @Test
  void gettersAndSettersRoundTrip() {
    CardXref x = new CardXref();
    x.setXrefCardNum("4111111111111111");
    x.setXrefCustId(123456789L);
    x.setXrefAcctId(12345678901L);

    assertThat(x.getXrefCardNum()).isEqualTo("4111111111111111");
    assertThat(x.getXrefCustId()).isEqualTo(123456789L);
    assertThat(x.getXrefAcctId()).isEqualTo(12345678901L);

    CardXref fresh = new CardXref();
    assertThat(fresh.getXrefCardNum()).isNull();
    assertThat(fresh.getXrefCustId()).isNull();
    assertThat(fresh.getXrefAcctId()).isNull();
  }

  /**
   * Pins the field Java types: the two identifiers are {@link Long} (genuine numerics, COBOL {@code
   * 9(09)} / {@code 9(11)}), while the card number is a {@link String} (fixed-width COBOL {@code
   * X(16)} key). This guards against both an "everything-is-String" regression and a
   * "card-number-as-long" regression that would break VSAM key parity (Agent Action Plan
   * &sect;0.6.2).
   */
  @Test
  void numericIdsAreLongNotString() throws NoSuchFieldException {
    assertThat(CardXref.class.getDeclaredField("xrefCustId").getType()).isEqualTo(Long.class);
    assertThat(CardXref.class.getDeclaredField("xrefAcctId").getType()).isEqualTo(Long.class);
    assertThat(CardXref.class.getDeclaredField("xrefCardNum").getType()).isEqualTo(String.class);
  }

  /** Locks the JPA table mapping to the legacy {@code CARDXREF} store name. */
  @Test
  void tableNameIsCardXref() {
    assertThat(CardXref.class.getAnnotation(Table.class).name()).isEqualTo("card_xref");
  }

  /**
   * Confirms {@code xrefCardNum} is the primary key: {@code @Id} is present and its {@code @Column}
   * maps to fixed-width {@code char(16)} column {@code xref_card_num}, matching VSAM {@code
   * XREF-CARD-NUM} KEYLEN 16 (COBOL {@code PIC X(16)}).
   */
  @Test
  void primaryKeyIsXrefCardNum() throws NoSuchFieldException {
    Field field = CardXref.class.getDeclaredField("xrefCardNum");
    Column column = field.getAnnotation(Column.class);

    assertThat(field.isAnnotationPresent(Id.class)).isTrue();
    assertThat(column.name()).isEqualTo("xref_card_num");
    assertThat(column.length()).isEqualTo(16);
  }

  /**
   * Spot-checks the {@code @Column} mappings for the two numeric identifiers so the snake_case
   * names and numeric precisions match the Flyway migration DDL: {@code xref_cust_id numeric(9)}
   * (COBOL {@code 9(09)}) and {@code xref_acct_id numeric(11)} (COBOL {@code 9(11)}; backs the
   * non-unique alternate index {@code ix_card_xref_acct_id}).
   */
  @Test
  void columnMappingsMatchSchema() throws NoSuchFieldException {
    Column custId = CardXref.class.getDeclaredField("xrefCustId").getAnnotation(Column.class);
    assertThat(custId.name()).isEqualTo("xref_cust_id");
    assertThat(custId.precision()).isEqualTo(9);

    Column acctId = CardXref.class.getDeclaredField("xrefAcctId").getAnnotation(Column.class);
    assertThat(acctId.name()).isEqualTo("xref_acct_id");
    assertThat(acctId.precision()).isEqualTo(11);
  }

  /**
   * Decimal-fidelity guard (Agent Action Plan &sect;0.6.1): no declared field may use a binary
   * floating-point type. Synthetic fields (for example the JaCoCo {@code $jacocoData}
   * instrumentation field added during coverage runs) are skipped because they are compiler/agent
   * generated and are not part of the modeled record.
   */
  @Test
  void hasNoFloatingPointFields() {
    for (Field field : CardXref.class.getDeclaredFields()) {
      if (field.isSynthetic()) {
        continue;
      }
      assertThat(field.getType())
          .as("field %s must not be a floating-point type", field.getName())
          .isNotIn(float.class, double.class, Float.class, Double.class);
    }
  }
}
