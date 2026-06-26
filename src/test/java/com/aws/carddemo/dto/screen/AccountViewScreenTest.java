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
 * Pure unit tests for {@link AccountViewScreen}, the screen view-contract DTO migrated from the
 * legacy COBOL BMS mapset {@code COACTVW} (CICS transaction {@code CAVW}; symbolic copybook {@code
 * legacy/app/cpy-bms/COACTVW.CPY} and mapset {@code legacy/app/bms/COACTVW.bms}).
 *
 * <p>The tests exercise the DTO in isolation — no Spring context, database, Testcontainers, or
 * mocking — using only a {@code new AccountViewScreen()} instance and AssertJ. They lock in two
 * behavioral-parity invariants from the Agent Action Plan:
 *
 * <ul>
 *   <li><b>Decimal fidelity (AAP 0.6.1):</b> the five monetary fields ({@code acrdLim}, {@code
 *       acshLim}, {@code acurBal}, {@code acrCycr}, {@code acrCydb}) are {@link BigDecimal} with
 *       COBOL {@code PIC S9(10)V99} (scale 2, signed) semantics; floating-point types are never
 *       used anywhere in this test.
 *   <li><b>Fixed-width fidelity (AAP 0.4.1):</b> string fields round-trip values sized to their BMS
 *       {@code LENGTH} widths (for example {@code ERRMSG} X(78), {@code INFOMSG} X(45), and {@code
 *       ACCTSID} X(11)).
 * </ul>
 */
class AccountViewScreenTest {

  @Test
  void fresh_instance_has_all_null_fields() {
    AccountViewScreen screen = new AccountViewScreen();

    // Header fields default to null (the no-arg constructor performs no initialization).
    assertThat(screen.getTrnName()).isNull();
    assertThat(screen.getTitle01()).isNull();
    assertThat(screen.getCurDate()).isNull();
    assertThat(screen.getPgmName()).isNull();
    assertThat(screen.getTitle02()).isNull();
    assertThat(screen.getCurTime()).isNull();

    // Account string fields default to null.
    assertThat(screen.getAcctSid()).isNull();
    assertThat(screen.getAcstTus()).isNull();
    assertThat(screen.getAdtOpen()).isNull();
    assertThat(screen.getAexpDt()).isNull();
    assertThat(screen.getAreisDt()).isNull();
    assertThat(screen.getAaddGrp()).isNull();

    // Customer string fields default to null.
    assertThat(screen.getAcstNum()).isNull();
    assertThat(screen.getAcstSsn()).isNull();
    assertThat(screen.getAcstDob()).isNull();
    assertThat(screen.getAcstFco()).isNull();
    assertThat(screen.getAcsFnam()).isNull();
    assertThat(screen.getAcsMnam()).isNull();
    assertThat(screen.getAcsLnam()).isNull();
    assertThat(screen.getAcsAdl1()).isNull();
    assertThat(screen.getAcsStte()).isNull();
    assertThat(screen.getAcsAdl2()).isNull();
    assertThat(screen.getAcsZipc()).isNull();
    assertThat(screen.getAcsCity()).isNull();
    assertThat(screen.getAcsCtry()).isNull();
    assertThat(screen.getAcsPhn1()).isNull();
    assertThat(screen.getAcsGovt()).isNull();
    assertThat(screen.getAcsPhn2()).isNull();
    assertThat(screen.getAcsEftc()).isNull();
    assertThat(screen.getAcsPflg()).isNull();

    // Message fields default to null.
    assertThat(screen.getInfoMsg()).isNull();
    assertThat(screen.getErrMsg()).isNull();

    // The five monetary BigDecimal fields default to null (no BigDecimal.ZERO initialization).
    assertThat(screen.getAcrdLim()).isNull();
    assertThat(screen.getAcshLim()).isNull();
    assertThat(screen.getAcurBal()).isNull();
    assertThat(screen.getAcrCycr()).isNull();
    assertThat(screen.getAcrCydb()).isNull();
  }

  @Test
  void header_fields_round_trip() {
    AccountViewScreen screen = new AccountViewScreen();

    screen.setTrnName("CAVW");
    screen.setTitle01("AWS CardDemo");
    screen.setCurDate("01/15/24");
    screen.setPgmName("COACTVWC");
    screen.setTitle02("Account View");
    screen.setCurTime("13:45:07");

    assertThat(screen.getTrnName()).isEqualTo("CAVW");
    assertThat(screen.getTitle01()).isEqualTo("AWS CardDemo");
    assertThat(screen.getCurDate()).isEqualTo("01/15/24");
    assertThat(screen.getPgmName()).isEqualTo("COACTVWC");
    assertThat(screen.getTitle02()).isEqualTo("Account View");
    assertThat(screen.getCurTime()).isEqualTo("13:45:07");
  }

  @Test
  void account_string_fields_round_trip() {
    AccountViewScreen screen = new AccountViewScreen();

    screen.setAcctSid("00000000011");
    screen.setAcstTus("Y");
    screen.setAdtOpen("2020-01-01");
    screen.setAexpDt("2027-12-31");
    screen.setAreisDt("2024-06-15");
    screen.setAaddGrp("GRP0000001");

    assertThat(screen.getAcctSid()).isEqualTo("00000000011");
    assertThat(screen.getAcstTus()).isEqualTo("Y");
    assertThat(screen.getAdtOpen()).isEqualTo("2020-01-01");
    assertThat(screen.getAexpDt()).isEqualTo("2027-12-31");
    assertThat(screen.getAreisDt()).isEqualTo("2024-06-15");
    assertThat(screen.getAaddGrp()).isEqualTo("GRP0000001");
  }

  @Test
  void customer_string_fields_round_trip() {
    AccountViewScreen screen = new AccountViewScreen();

    // Maximum-width values for the wide fixed-width fields, sized to their BMS LENGTH.
    String firstName = "X".repeat(25);
    String middleName = "Y".repeat(25);
    String lastName = "Z".repeat(25);
    String addressLine1 = "A".repeat(50);
    String addressLine2 = "B".repeat(50);
    String city = "C".repeat(50);
    String govtId = "G".repeat(20);

    screen.setAcstNum("123456789");
    screen.setAcstSsn("123-45-6789");
    screen.setAcstDob("1985-07-20");
    screen.setAcstFco("750");
    screen.setAcsFnam(firstName);
    screen.setAcsMnam(middleName);
    screen.setAcsLnam(lastName);
    screen.setAcsAdl1(addressLine1);
    screen.setAcsStte("CA");
    screen.setAcsAdl2(addressLine2);
    screen.setAcsZipc("90210");
    screen.setAcsCity(city);
    screen.setAcsCtry("USA");
    screen.setAcsPhn1("(415)555-0101");
    screen.setAcsGovt(govtId);
    screen.setAcsPhn2("(415)555-0202");
    screen.setAcsEftc("EFT0000001");
    screen.setAcsPflg("Y");

    assertThat(screen.getAcstNum()).isEqualTo("123456789").hasSize(9);
    assertThat(screen.getAcstSsn()).isEqualTo("123-45-6789");
    assertThat(screen.getAcstDob()).isEqualTo("1985-07-20");
    assertThat(screen.getAcstFco()).isEqualTo("750").hasSize(3);
    assertThat(screen.getAcsFnam()).isEqualTo(firstName).hasSize(25);
    assertThat(screen.getAcsMnam()).isEqualTo(middleName).hasSize(25);
    assertThat(screen.getAcsLnam()).isEqualTo(lastName).hasSize(25);
    assertThat(screen.getAcsAdl1()).isEqualTo(addressLine1).hasSize(50);
    assertThat(screen.getAcsStte()).isEqualTo("CA").hasSize(2);
    assertThat(screen.getAcsAdl2()).isEqualTo(addressLine2).hasSize(50);
    assertThat(screen.getAcsZipc()).isEqualTo("90210").hasSize(5);
    assertThat(screen.getAcsCity()).isEqualTo(city).hasSize(50);
    assertThat(screen.getAcsCtry()).isEqualTo("USA").hasSize(3);
    assertThat(screen.getAcsPhn1()).isEqualTo("(415)555-0101").hasSize(13);
    assertThat(screen.getAcsGovt()).isEqualTo(govtId).hasSize(20);
    assertThat(screen.getAcsPhn2()).isEqualTo("(415)555-0202").hasSize(13);
    assertThat(screen.getAcsEftc()).isEqualTo("EFT0000001").hasSize(10);
    assertThat(screen.getAcsPflg()).isEqualTo("Y");
  }

  @Test
  void message_fields_preserve_bms_widths() {
    AccountViewScreen screen = new AccountViewScreen();

    // INFOMSG PIC X(45) and ERRMSG PIC X(78) — exact BMS LENGTH parity.
    String infoMessage = "I".repeat(45);
    String errorMessage = "E".repeat(78);

    screen.setInfoMsg(infoMessage);
    screen.setErrMsg(errorMessage);

    assertThat(screen.getInfoMsg()).isEqualTo(infoMessage).hasSize(45);
    assertThat(screen.getErrMsg()).isEqualTo(errorMessage).hasSize(78);
  }

  @Test
  void account_id_preserves_eleven_character_width() {
    AccountViewScreen screen = new AccountViewScreen();

    // ACCTSID PIC 9(11) is modeled as String to keep the exact 11-character fixed width
    // (including leading zeros) and VSAM key semantics (AAP 0.6.2).
    screen.setAcctSid("00000000011");

    assertThat(screen.getAcctSid()).isEqualTo("00000000011").hasSize(11);
  }

  @Test
  void monetary_fields_round_trip_as_big_decimal() {
    AccountViewScreen screen = new AccountViewScreen();

    // Representative values valid for COBOL PIC S9(10)V99 (scale 2).
    screen.setAcrdLim(new BigDecimal("1234567.89"));
    screen.setAcshLim(new BigDecimal("2345678.90"));
    screen.setAcurBal(new BigDecimal("3456789.01"));
    screen.setAcrCycr(new BigDecimal("4567890.12"));
    screen.setAcrCydb(new BigDecimal("5678901.23"));

    // Every monetary field is java.math.BigDecimal (never float/double) — AAP 0.6.1.
    assertThat(screen.getAcrdLim()).isEqualByComparingTo(new BigDecimal("1234567.89"));
    assertThat(screen.getAcshLim()).isEqualByComparingTo(new BigDecimal("2345678.90"));
    assertThat(screen.getAcurBal()).isEqualByComparingTo(new BigDecimal("3456789.01"));
    assertThat(screen.getAcrCycr()).isEqualByComparingTo(new BigDecimal("4567890.12"));
    assertThat(screen.getAcrCydb()).isEqualByComparingTo(new BigDecimal("5678901.23"));
  }

  @Test
  void monetary_fields_preserve_cobol_scale_two() {
    AccountViewScreen screen = new AccountViewScreen();

    // A value supplied with scale 2 is stored verbatim, preserving the COBOL S9(10)V99
    // two-decimal representation (AAP 0.6.1).
    BigDecimal scaleTwo = new BigDecimal("100.00");

    screen.setAcrdLim(scaleTwo);
    screen.setAcshLim(scaleTwo);
    screen.setAcurBal(scaleTwo);
    screen.setAcrCycr(scaleTwo);
    screen.setAcrCydb(scaleTwo);

    assertThat(screen.getAcrdLim().scale()).isEqualTo(2);
    assertThat(screen.getAcshLim().scale()).isEqualTo(2);
    assertThat(screen.getAcurBal().scale()).isEqualTo(2);
    assertThat(screen.getAcrCycr().scale()).isEqualTo(2);
    assertThat(screen.getAcrCydb().scale()).isEqualTo(2);

    // Value equality is scale-insensitive and remains correct regardless of normalization.
    assertThat(screen.getAcurBal()).isEqualByComparingTo(new BigDecimal("100.00"));
  }

  @Test
  void monetary_fields_support_full_s9_10_v99_precision() {
    AccountViewScreen screen = new AccountViewScreen();

    // Maximum magnitude representable by PIC S9(10)V99: 10 integer digits plus 2 decimals.
    BigDecimal maxValue = new BigDecimal("9999999999.99");

    screen.setAcrdLim(maxValue);
    screen.setAcshLim(maxValue);
    screen.setAcurBal(maxValue);
    screen.setAcrCycr(maxValue);
    screen.setAcrCydb(maxValue);

    assertThat(screen.getAcrdLim()).isEqualByComparingTo(new BigDecimal("9999999999.99"));
    assertThat(screen.getAcshLim()).isEqualByComparingTo(new BigDecimal("9999999999.99"));
    assertThat(screen.getAcurBal()).isEqualByComparingTo(new BigDecimal("9999999999.99"));
    assertThat(screen.getAcrCycr()).isEqualByComparingTo(new BigDecimal("9999999999.99"));
    assertThat(screen.getAcrCydb()).isEqualByComparingTo(new BigDecimal("9999999999.99"));
  }

  @Test
  void current_balance_round_trips_negative_signed_value() {
    AccountViewScreen screen = new AccountViewScreen();

    // COBOL PIC S9(10)V99 is signed; a negative balance must round-trip exactly.
    screen.setAcurBal(new BigDecimal("-50.00"));

    assertThat(screen.getAcurBal()).isEqualByComparingTo(new BigDecimal("-50.00"));
    assertThat(screen.getAcurBal().signum()).isEqualTo(-1);
  }

  @Test
  void acct_sid_size_constraint_matches_bms_width() throws NoSuchFieldException {
    // The account identifier is the only field carrying a Bean Validation @Size constraint in the
    // production DTO; its max must equal the BMS ACCTSID X(11) width.
    Size size = AccountViewScreen.class.getDeclaredField("acctSid").getAnnotation(Size.class);

    assertThat(size).isNotNull();
    assertThat(size.max()).isEqualTo(11);
  }
}
