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
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link BillPayScreen}, the screen view contract migrated from the legacy
 * CICS/BMS bill-payment map {@code COBIL00} (CICS transaction {@code CB00}).
 *
 * <p>The DTO is exercised in complete isolation — no Spring context, database, Testcontainers, or
 * mocking — so the suite is a fast, deterministic guard over three behavioral-parity invariants
 * drawn from the Agent Action Plan:
 *
 * <ul>
 *   <li>getter/setter round-trip fidelity for every screen field;
 *   <li>monetary fidelity of {@code curBal} as a {@link BigDecimal} (scale 2, signed, and never a
 *       {@code float}/{@code double}) per AAP §0.6.1;
 *   <li>fixed-width parity of the {@code PIC X(n)} fields, asserted through the {@link Size} upper
 *       bounds that mirror the BMS {@code LENGTH=} attributes of {@code app/bms/COBIL00.bms}.
 * </ul>
 *
 * <p>Authoritative field contract: legacy {@code app/cpy-bms/COBIL00.CPY} (input map {@code
 * COBIL0AI}) corroborated by {@code app/bms/COBIL00.bms}.
 */
class BillPayScreenTest {

  /**
   * Verifies that all nine character (non-monetary) screen fields round-trip exactly through their
   * setters and getters, and documents the BMS widths of the three screen-specific character fields
   * ({@code ACTIDIN} X(11), {@code CONFIRM} X(1), {@code ERRMSG} X(78)).
   */
  @Test
  void all_string_fields_round_trip() {
    BillPayScreen screen = new BillPayScreen();

    String errMsg = "X".repeat(78);
    screen.setTrnName("CB00");
    screen.setTitle01("AWS CardDemo");
    screen.setCurDate("01/31/22");
    screen.setPgmName("COBIL00C");
    screen.setTitle02("Bill Payment");
    screen.setCurTime("12:34:56");
    screen.setActIdIn("00000000011");
    screen.setConfirm("Y");
    screen.setErrMsg(errMsg);

    // Header fields (TRNNAME/TITLE01/CURDATE/PGMNAME/TITLE02/CURTIME) round-trip unchanged.
    assertThat(screen.getTrnName()).isEqualTo("CB00");
    assertThat(screen.getTitle01()).isEqualTo("AWS CardDemo");
    assertThat(screen.getCurDate()).isEqualTo("01/31/22");
    assertThat(screen.getPgmName()).isEqualTo("COBIL00C");
    assertThat(screen.getTitle02()).isEqualTo("Bill Payment");
    assertThat(screen.getCurTime()).isEqualTo("12:34:56");

    // Screen-specific character fields, asserted at their exact BMS widths.
    assertThat(screen.getActIdIn()).isEqualTo("00000000011").hasSize(11);
    assertThat(screen.getConfirm()).isEqualTo("Y").hasSize(1);
    assertThat(screen.getErrMsg()).isEqualTo(errMsg).hasSize(78);
  }

  /**
   * Verifies the core monetary invariant (AAP §0.6.1): {@code curBal} is carried as a {@link
   * BigDecimal} and round-trips by value via {@code isEqualByComparingTo}.
   */
  @Test
  void curbal_round_trips_as_bigdecimal() {
    BillPayScreen screen = new BillPayScreen();

    screen.setCurBal(new BigDecimal("1234567.89"));

    assertThat(screen.getCurBal())
        .isInstanceOf(BigDecimal.class)
        .isEqualByComparingTo(new BigDecimal("1234567.89"));
  }

  /**
   * Verifies that a signed (negative) balance round-trips, mirroring the COBOL signed picture
   * {@code S9(10)V99} of {@code CURBAL}.
   */
  @Test
  void curbal_supports_signed_negative_value() {
    BillPayScreen screen = new BillPayScreen();

    screen.setCurBal(new BigDecimal("-50.00"));

    assertThat(screen.getCurBal()).isEqualByComparingTo(new BigDecimal("-50.00"));
    assertThat(screen.getCurBal().signum()).isEqualTo(-1);
  }

  /**
   * Verifies that {@code curBal} preserves the declared monetary scale of 2 exactly as supplied
   * (the DTO stores the value as-is and applies no rounding or rescaling), per AAP §0.6.1.
   */
  @Test
  void curbal_preserves_scale_two() {
    BillPayScreen screen = new BillPayScreen();

    screen.setCurBal(new BigDecimal("100.00"));

    assertThat(screen.getCurBal().scale()).isEqualTo(2);
  }

  /**
   * Verifies fixed-width parity: every character field carries a {@link Size} upper bound equal to
   * its BMS {@code LENGTH=} (and {@code PIC X(n)}) width, while the monetary {@code curBal} carries
   * no {@link Size} constraint because it is a {@link BigDecimal}, not a width-bounded string.
   *
   * @throws Exception if a declared field cannot be reflected (never expected for this DTO)
   */
  @Test
  void size_annotations_match_bms_field_widths() throws Exception {
    assertThat(sizeMax("trnName")).isEqualTo(4);
    assertThat(sizeMax("title01")).isEqualTo(40);
    assertThat(sizeMax("curDate")).isEqualTo(8);
    assertThat(sizeMax("pgmName")).isEqualTo(8);
    assertThat(sizeMax("title02")).isEqualTo(40);
    assertThat(sizeMax("curTime")).isEqualTo(8);
    assertThat(sizeMax("actIdIn")).isEqualTo(11);
    assertThat(sizeMax("confirm")).isEqualTo(1);
    assertThat(sizeMax("errMsg")).isEqualTo(78);

    assertThat(BillPayScreen.class.getDeclaredField("curBal").getAnnotation(Size.class)).isNull();
  }

  /**
   * Verifies that a freshly constructed {@link BillPayScreen} leaves every field unset ({@code
   * null}), including the monetary {@code curBal}.
   */
  @Test
  void fresh_instance_fields_default_to_null() {
    BillPayScreen screen = new BillPayScreen();

    assertThat(screen.getTrnName()).isNull();
    assertThat(screen.getTitle01()).isNull();
    assertThat(screen.getCurDate()).isNull();
    assertThat(screen.getPgmName()).isNull();
    assertThat(screen.getTitle02()).isNull();
    assertThat(screen.getCurTime()).isNull();
    assertThat(screen.getActIdIn()).isNull();
    assertThat(screen.getCurBal()).isNull();
    assertThat(screen.getConfirm()).isNull();
    assertThat(screen.getErrMsg()).isNull();
  }

  /**
   * Reads the {@link Size#max()} upper bound declared on the named field of {@link BillPayScreen}.
   *
   * @param fieldName the declared field name
   * @return the configured {@link Size} maximum width
   * @throws NoSuchFieldException if the field does not exist on {@link BillPayScreen}
   */
  private static int sizeMax(String fieldName) throws NoSuchFieldException {
    return BillPayScreen.class.getDeclaredField(fieldName).getAnnotation(Size.class).max();
  }
}
