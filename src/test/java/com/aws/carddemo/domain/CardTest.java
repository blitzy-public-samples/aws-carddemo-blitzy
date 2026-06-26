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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Pure POJO unit tests for {@link Card}, the JPA entity translation of the COBOL copybook {@code
 * CVACT02Y.cpy} ({@code 01 CARD-RECORD}, RECLN 150, legacy source {@code
 * legacy/app/cpy/CVACT02Y.cpy}). The entity maps the legacy VSAM {@code CARDDATA} KSDS (primary key
 * {@code CARD-NUM}, KEYLEN 16, alternate index on {@code CARD-ACCT-ID}) onto the {@code card}
 * table.
 *
 * <p>These tests pin the <strong>COBOL-to-JPA parity invariants</strong> that the persistence layer
 * depends on (Agent Action Plan &sect;0.6.1, &sect;0.6.2):
 *
 * <ul>
 *   <li>all six modeled fields round-trip through their Lombok-generated getters and setters;
 *   <li>{@code cardCvvCd} is a fixed-width {@code char(3)} {@link String} — <em>not</em> a numeric
 *       type — even though the copybook PIC is {@code 9(03)}, so the CVV's mandatory leading zeros
 *       (for example {@code "007"}) survive intact; modeling it as {@code int}/{@link Long} would
 *       collapse {@code "007"} to {@code 7} (&sect;0.6.2);
 *   <li>the legacy copybook misspelling {@code cardExpiraionDate} / column {@code
 *       card_expiraion_date} is preserved verbatim and must never be "corrected" to {@code
 *       cardExpirationDate} (&sect;0.6.2);
 *   <li>{@code cardNum} is the {@code char(16)} {@link Id} primary key (VSAM {@code CARDDATA}
 *       KEYLEN 16); and
 *   <li>no field uses a binary floating-point type — the decimal-fidelity guard shared by every
 *       entity test in this package (&sect;0.6.1).
 * </ul>
 *
 * <p>This is intentionally a framework-light test: it constructs the POJO directly with {@code new}
 * and asserts with AssertJ and Java reflection only. There is no Spring context, database,
 * {@code @DataJpaTest}, Testcontainers, or Mockito. The production {@link Card} entity is
 * authoritative: if any accessor, field, or annotation differs, this test is the side that must
 * change.
 */
class CardTest {

  /**
   * Exercises every accessor pair: sets all six modeled fields to synthetic, in-bounds values and
   * confirms each getter returns exactly what was set. The CVV is set to {@code "007"} to show the
   * leading zeros survive the round-trip, and {@code cardAcctId} is set to a value that exceeds the
   * 32-bit {@code int} range to confirm it is a {@link Long}.
   */
  @Test
  void gettersAndSettersRoundTrip() {
    Card card = new Card();

    card.setCardNum("4111111111111111");
    card.setCardAcctId(12345678901L);
    card.setCardCvvCd("007");
    card.setCardEmbossedName("JOHN Q PUBLIC");
    card.setCardExpiraionDate("2026-12-31");
    card.setCardActiveStatus("Y");

    assertThat(card.getCardNum()).isEqualTo("4111111111111111");
    assertThat(card.getCardAcctId()).isEqualTo(12345678901L);
    assertThat(card.getCardCvvCd()).isEqualTo("007");
    assertThat(card.getCardEmbossedName()).isEqualTo("JOHN Q PUBLIC");
    assertThat(card.getCardExpiraionDate()).isEqualTo("2026-12-31");
    assertThat(card.getCardActiveStatus()).isEqualTo("Y");
  }

  /**
   * Pins the CVV as a fixed-width {@code char(3)} {@link String}, never a numeric type. COBOL
   * {@code CARD-CVV-CD PIC 9(03)} is digits-only by picture, but the value is an identifier
   * carrying mandatory leading zeros and is never used in arithmetic, so it must be a {@link
   * String}. The round-trip of {@code "007"} proves the leading zeros are preserved as fixed-width
   * text and not collapsed to the integer {@code 7} (Agent Action Plan &sect;0.6.2).
   */
  @Test
  void cvvIsFixedLengthStringNotNumeric() throws NoSuchFieldException {
    Field cvv = Card.class.getDeclaredField("cardCvvCd");

    assertThat(cvv.getType()).isEqualTo(String.class);
    assertThat(cvv.getAnnotation(Column.class).length()).isEqualTo(3);

    Card card = new Card();
    card.setCardCvvCd("007");
    assertThat(card.getCardCvvCd()).isEqualTo("007");
  }

  /**
   * {@code cardNum} is the {@link Id} primary key and maps to {@code char(16)} column {@code
   * card_num} (COBOL {@code CARD-NUM PIC X(16)}, VSAM {@code CARDDATA} KSDS KEYLEN 16). It is a
   * {@link String} so the fixed-width, blank-padded VSAM ordering/equality semantics are preserved.
   */
  @Test
  void cardNumberIsFixedLengthCharSixteen() throws NoSuchFieldException {
    Field cardNum = Card.class.getDeclaredField("cardNum");
    Column column = cardNum.getAnnotation(Column.class);

    assertThat(cardNum.isAnnotationPresent(Id.class)).isTrue();
    assertThat(column.name()).isEqualTo("card_num");
    assertThat(column.length()).isEqualTo(16);
    assertThat(cardNum.getType()).isEqualTo(String.class);
  }

  /**
   * Locks the preserved legacy misspelling. The copybook field {@code CARD-EXPIRAION-DATE} (missing
   * the second "t") is carried verbatim into the entity field {@code cardExpiraionDate} and column
   * {@code card_expiraion_date}. The correctly-spelled {@code cardExpirationDate} must <em>not</em>
   * exist — "improving" the typo would silently break traceability with the source copybook and the
   * V1 migration DDL (Agent Action Plan &sect;0.6.2).
   */
  @Test
  void preservesLegacyMisspellingCardExpiraionDate() throws NoSuchFieldException {
    Field expiraionDate = Card.class.getDeclaredField("cardExpiraionDate");
    assertThat(expiraionDate.getAnnotation(Column.class).name()).isEqualTo("card_expiraion_date");

    assertThatThrownBy(() -> Card.class.getDeclaredField("cardExpirationDate"))
        .isInstanceOf(NoSuchFieldException.class);
  }

  /** Locks the JPA table mapping to the legacy {@code CARDDATA} store name. */
  @Test
  void tableNameIsCard() {
    assertThat(Card.class.getAnnotation(Table.class).name()).isEqualTo("card");
  }

  /**
   * Spot-checks the {@code @Column} mappings of the remaining modeled fields against the Flyway
   * migration DDL: {@code cardAcctId} ({@code card_acct_id numeric(11)}, a genuine {@link Long}),
   * {@code cardEmbossedName} ({@code card_embossed_name char(50)}), and {@code cardActiveStatus}
   * ({@code card_active_status char(1)}). Any drift in these snake_case names, lengths, or the
   * numeric type makes Hibernate {@code validate} fail at startup.
   */
  @Test
  void columnMappingsMatchSchema() throws NoSuchFieldException {
    Field cardAcctId = Card.class.getDeclaredField("cardAcctId");
    Column cardAcctIdColumn = cardAcctId.getAnnotation(Column.class);
    assertThat(cardAcctIdColumn.name()).isEqualTo("card_acct_id");
    assertThat(cardAcctIdColumn.precision()).isEqualTo(11);
    assertThat(cardAcctId.getType()).isEqualTo(Long.class);

    Field cardEmbossedName = Card.class.getDeclaredField("cardEmbossedName");
    Column cardEmbossedNameColumn = cardEmbossedName.getAnnotation(Column.class);
    assertThat(cardEmbossedNameColumn.name()).isEqualTo("card_embossed_name");
    assertThat(cardEmbossedNameColumn.length()).isEqualTo(50);

    Field cardActiveStatus = Card.class.getDeclaredField("cardActiveStatus");
    Column cardActiveStatusColumn = cardActiveStatus.getAnnotation(Column.class);
    assertThat(cardActiveStatusColumn.name()).isEqualTo("card_active_status");
    assertThat(cardActiveStatusColumn.length()).isEqualTo(1);
  }

  /**
   * Decimal-fidelity guard (Agent Action Plan &sect;0.6.1): no declared field may use a binary
   * floating-point type. Iterates every declared field, skipping compiler/coverage synthetic
   * members (for example the JaCoCo {@code $jacocoData} instrumentation field added during coverage
   * runs), and asserts no field is {@code float}, {@code double}, {@link Float}, or {@link Double}.
   * This entity has no monetary fields, so none should ever appear.
   */
  @Test
  void hasNoFloatingPointFields() {
    for (Field field : Card.class.getDeclaredFields()) {
      if (field.isSynthetic()) {
        continue;
      }
      assertThat(field.getType())
          .as("field %s must not be a floating-point type", field.getName())
          .isNotIn(float.class, double.class, Float.class, Double.class);
    }
  }
}
