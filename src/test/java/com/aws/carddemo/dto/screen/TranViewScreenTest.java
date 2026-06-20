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
package com.aws.carddemo.dto.screen;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.constraints.Size;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link TranViewScreen}, the screen view contract migrated from the legacy
 * CICS/BMS {@code COTRN01} transaction-view screen (CICS transaction {@code CT01}; legacy spec
 * {@code legacy/app/cpy-bms/COTRN01.CPY} + {@code legacy/app/bms/COTRN01.bms}).
 *
 * <p>These tests are framework-light by design: they exercise the plain getters/setters of the DTO
 * with no Spring context, database, Testcontainers, or mocking. They pin three parity guarantees
 * from the Agent Action Plan:
 *
 * <ul>
 *   <li><strong>Decimal fidelity (AAP &sect;0.6.1)</strong> &mdash; the single monetary field
 *       {@code trnAmt} is a {@link BigDecimal} (never {@code float}/{@code double}), preserves a
 *       signed value, and retains a conceptual scale of 2; comparisons use {@code
 *       isEqualByComparingTo} so they are scale-insensitive.
 *   <li><strong>Field-width fidelity (AAP &sect;0.4.1)</strong> &mdash; every {@code PIC X(n)}
 *       field width declared by the copybook/BMS map is reflected by the {@link Size} bound on the
 *       corresponding property and the field round-trips its full BMS length without truncation.
 *   <li><strong>Round-trip integrity</strong> &mdash; every getter returns exactly what the
 *       matching setter stored, and a freshly constructed instance has unset (null) fields.
 * </ul>
 */
class TranViewScreenTest {

  @Test
  void header_fields_round_trip() {
    TranViewScreen screen = new TranViewScreen();

    screen.setTrnName("CT01");
    screen.setTitle01("View Transaction");
    screen.setCurDate("01/02/26");
    screen.setPgmName("COTRN01C");
    screen.setTitle02("AWS CardDemo");
    screen.setCurTime("14:30:00");

    assertThat(screen.getTrnName()).isEqualTo("CT01");
    assertThat(screen.getTitle01()).isEqualTo("View Transaction");
    assertThat(screen.getCurDate()).isEqualTo("01/02/26");
    assertThat(screen.getPgmName()).isEqualTo("COTRN01C");
    assertThat(screen.getTitle02()).isEqualTo("AWS CardDemo");
    assertThat(screen.getCurTime()).isEqualTo("14:30:00");
  }

  @Test
  void lookup_and_detail_string_fields_round_trip() {
    TranViewScreen screen = new TranViewScreen();

    // trnIdIn is the operator's search entry; trnId is the resolved transaction id (both X(16)).
    screen.setTrnIdIn("0000000000000099");
    screen.setTrnId("0000000000000001");
    screen.setCardNum("4111111111111111");
    screen.setTtypCd("01");
    screen.setTcatCd("0001");
    screen.setTrnSrc("POS");
    screen.setTDesc("Grocery purchase");
    screen.setTOrigDt("2026-01-02");
    screen.setTProcDt("2026-01-03");
    screen.setMid("MERCH0001");
    screen.setMName("Acme Corporation");
    screen.setMCity("Seattle");
    screen.setMZip("98101");
    screen.setErrMsg("Transaction located.");

    assertThat(screen.getTrnIdIn()).isEqualTo("0000000000000099");
    assertThat(screen.getTrnId()).isEqualTo("0000000000000001");
    assertThat(screen.getCardNum()).isEqualTo("4111111111111111");
    assertThat(screen.getTtypCd()).isEqualTo("01");
    assertThat(screen.getTcatCd()).isEqualTo("0001");
    assertThat(screen.getTrnSrc()).isEqualTo("POS");
    assertThat(screen.getTDesc()).isEqualTo("Grocery purchase");
    assertThat(screen.getTOrigDt()).isEqualTo("2026-01-02");
    assertThat(screen.getTProcDt()).isEqualTo("2026-01-03");
    assertThat(screen.getMid()).isEqualTo("MERCH0001");
    assertThat(screen.getMName()).isEqualTo("Acme Corporation");
    assertThat(screen.getMCity()).isEqualTo("Seattle");
    assertThat(screen.getMZip()).isEqualTo("98101");
    assertThat(screen.getErrMsg()).isEqualTo("Transaction located.");
  }

  @Test
  void trn_amt_round_trips_as_big_decimal() {
    TranViewScreen screen = new TranViewScreen();

    screen.setTrnAmt(new BigDecimal("1234567.89"));

    // Decimal fidelity (AAP 0.6.1): monetary value held as BigDecimal, never a binary float/double.
    assertThat(screen.getTrnAmt()).isNotNull();
    assertThat(screen.getTrnAmt()).isEqualByComparingTo(new BigDecimal("1234567.89"));
  }

  @Test
  void trn_amt_supports_signed_negative_amount() {
    TranViewScreen screen = new TranViewScreen();

    // COBOL TRAN-AMT is PIC S9(09)V99 (signed); a negative amount must round-trip intact.
    screen.setTrnAmt(new BigDecimal("-99.99"));

    assertThat(screen.getTrnAmt()).isNegative();
    assertThat(screen.getTrnAmt()).isEqualByComparingTo(new BigDecimal("-99.99"));
  }

  @Test
  void trn_amt_preserves_two_decimal_scale() {
    TranViewScreen screen = new TranViewScreen();

    // A scale-2 value is retained verbatim (no normalization) while comparisons stay
    // scale-insensitive.
    screen.setTrnAmt(new BigDecimal("100.00"));

    assertThat(screen.getTrnAmt().scale()).isEqualTo(2);
    assertThat(screen.getTrnAmt()).isEqualByComparingTo(new BigDecimal("100"));
  }

  @Test
  void field_widths_match_bms_lengths() {
    TranViewScreen screen = new TranViewScreen();

    // Exercise the full BMS LENGTH of the widest representative fields with no truncation.
    String trnId16 = "X".repeat(16);
    String tDesc60 = "D".repeat(60);
    String errMsg78 = "E".repeat(78);

    screen.setTrnId(trnId16);
    screen.setTDesc(tDesc60);
    screen.setErrMsg(errMsg78);

    assertThat(screen.getTrnId()).isEqualTo(trnId16).hasSize(16);
    assertThat(screen.getTDesc()).isEqualTo(tDesc60).hasSize(60);
    assertThat(screen.getErrMsg()).isEqualTo(errMsg78).hasSize(78);
  }

  @Test
  void size_annotations_match_bms_widths() throws NoSuchFieldException {
    // Each PIC X(n) width from COTRN01.CPY / COTRN01.bms is declared as @Size(max = n).
    assertSizeMax("trnName", 4);
    assertSizeMax("title01", 40);
    assertSizeMax("curDate", 8);
    assertSizeMax("pgmName", 8);
    assertSizeMax("title02", 40);
    assertSizeMax("curTime", 8);
    assertSizeMax("trnIdIn", 16);
    assertSizeMax("trnId", 16);
    assertSizeMax("cardNum", 16);
    assertSizeMax("ttypCd", 2);
    assertSizeMax("tcatCd", 4);
    assertSizeMax("trnSrc", 10);
    assertSizeMax("tDesc", 60);
    assertSizeMax("tOrigDt", 10);
    assertSizeMax("tProcDt", 10);
    assertSizeMax("mid", 9);
    assertSizeMax("mName", 30);
    assertSizeMax("mCity", 25);
    assertSizeMax("mZip", 10);
    assertSizeMax("errMsg", 78);
  }

  @Test
  void fresh_instance_has_null_defaults() {
    TranViewScreen screen = new TranViewScreen();

    assertThat(screen.getTrnName()).isNull();
    assertThat(screen.getTitle01()).isNull();
    assertThat(screen.getCurDate()).isNull();
    assertThat(screen.getPgmName()).isNull();
    assertThat(screen.getTitle02()).isNull();
    assertThat(screen.getCurTime()).isNull();
    assertThat(screen.getTrnIdIn()).isNull();
    assertThat(screen.getTrnId()).isNull();
    assertThat(screen.getCardNum()).isNull();
    assertThat(screen.getTtypCd()).isNull();
    assertThat(screen.getTcatCd()).isNull();
    assertThat(screen.getTrnSrc()).isNull();
    assertThat(screen.getTDesc()).isNull();
    assertThat(screen.getTrnAmt()).isNull();
    assertThat(screen.getTOrigDt()).isNull();
    assertThat(screen.getTProcDt()).isNull();
    assertThat(screen.getMid()).isNull();
    assertThat(screen.getMName()).isNull();
    assertThat(screen.getMCity()).isNull();
    assertThat(screen.getMZip()).isNull();
    assertThat(screen.getErrMsg()).isNull();
  }

  /**
   * Asserts that the named declared field carries a {@link Size} constraint whose {@code max}
   * equals the field's BMS width.
   *
   * @param fieldName the declared field name on {@link TranViewScreen}
   * @param expected the expected {@code @Size(max)} value (the BMS LENGTH / copybook {@code PIC
   *     X(n)} width)
   * @throws NoSuchFieldException if the field name does not exist on the DTO
   */
  private static void assertSizeMax(String fieldName, int expected) throws NoSuchFieldException {
    Field field = TranViewScreen.class.getDeclaredField(fieldName);
    Size size = field.getAnnotation(Size.class);
    assertThat(size).as("@Size annotation present on field '%s'", fieldName).isNotNull();
    assertThat(size.max()).as("@Size max for field '%s'", fieldName).isEqualTo(expected);
  }
}
