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
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for {@link AccountUpdateScreen}, the Java migration of the legacy CICS/BMS mapset
 * {@code COACTUP} (CICS transaction {@code CAUP}).
 *
 * <p>The test verifies the screen DTO contract against the authoritative legacy specification
 * ({@code legacy/app/cpy-bms/COACTUP.CPY} symbolic AI map and {@code legacy/app/bms/COACTUP.bms}
 * field {@code LENGTH}s). It asserts three properties that guarantee parity with the 3270 screen:
 *
 * <ul>
 *   <li><b>Round-trip fidelity</b> &mdash; every field stores and returns the value it was given.
 *       This includes the editable split-component fields that distinguish the Account Update
 *       screen from the Account View screen (the year / month / day date components, the three SSN
 *       parts and the area / prefix / line telephone components) and the hidden {@code old*}
 *       snapshot fields that carry the display-turn values across the pseudo-conversational
 *       boundary for the optimistic-concurrency check (AAP &sect;0.6.5).
 *   <li><b>Monetary text fidelity (AAP &sect;0.6.1)</b> &mdash; the five monetary fields (credit
 *       limit, cash credit limit, current balance, current cycle credit, current cycle debit) are
 *       fixed-width {@code PIC X(15)} {@code String} holders that round-trip the raw characters
 *       verbatim; the service validates them char-by-char and parses to {@code BigDecimal} at scale
 *       2 (floating-point types are never used).
 *   <li><b>Field-width parity (AAP &sect;0.4.1)</b> &mdash; the {@link Size} bound on every {@code
 *       String} field equals the corresponding BMS map {@code LENGTH}.
 * </ul>
 *
 * <p>This is a framework-light test: it constructs the POJO directly and uses AssertJ only &mdash;
 * no Spring context, database, Testcontainers or Mockito are involved.
 */
class AccountUpdateScreenTest {

  /** The six banner / header fields round-trip through their getters and setters. */
  @Test
  void header_string_fields_round_trip() {
    AccountUpdateScreen screen = new AccountUpdateScreen();

    screen.setTrnName("CAUP");
    screen.setTitle01("AWS Mainframe Modernization CardDemo");
    screen.setCurDate("01/15/24");
    screen.setPgmName("COACTUPC");
    screen.setTitle02("Account Update");
    screen.setCurTime("12:30:45");

    assertThat(screen.getTrnName()).isEqualTo("CAUP");
    assertThat(screen.getTitle01()).isEqualTo("AWS Mainframe Modernization CardDemo");
    assertThat(screen.getCurDate()).isEqualTo("01/15/24");
    assertThat(screen.getPgmName()).isEqualTo("COACTUPC");
    assertThat(screen.getTitle02()).isEqualTo("Account Update");
    assertThat(screen.getCurTime()).isEqualTo("12:30:45");
  }

  /**
   * The account identifier, status and the editable open / expiry / reissue date split components
   * round-trip exactly. These year / month / day fields are unprotected BMS input fields and are a
   * defining feature of the editable Account Update screen.
   */
  @Test
  void account_and_limit_string_fields_round_trip() {
    AccountUpdateScreen screen = new AccountUpdateScreen();

    screen.setAcctSid("00000000011");
    screen.setAcstTus("Y");
    screen.setOpnYear("2020");
    screen.setOpnMon("01");
    screen.setOpnDay("15");
    screen.setExpYear("2025");
    screen.setExpMon("12");
    screen.setExpDay("31");
    screen.setRisYear("2023");
    screen.setRisMon("06");
    screen.setRisDay("30");
    screen.setAaddGrp("GROUP00001");

    assertThat(screen.getAcctSid()).isEqualTo("00000000011");
    assertThat(screen.getAcstTus()).isEqualTo("Y");
    assertThat(screen.getOpnYear()).isEqualTo("2020");
    assertThat(screen.getOpnMon()).isEqualTo("01");
    assertThat(screen.getOpnDay()).isEqualTo("15");
    assertThat(screen.getExpYear()).isEqualTo("2025");
    assertThat(screen.getExpMon()).isEqualTo("12");
    assertThat(screen.getExpDay()).isEqualTo("31");
    assertThat(screen.getRisYear()).isEqualTo("2023");
    assertThat(screen.getRisMon()).isEqualTo("06");
    assertThat(screen.getRisDay()).isEqualTo("30");
    assertThat(screen.getAaddGrp()).isEqualTo("GROUP00001");
  }

  /**
   * The customer block round-trips exactly, including the editable SSN parts, the date-of-birth
   * split components and both telephone numbers split into area / prefix / line components.
   */
  @Test
  void customer_string_fields_round_trip() {
    AccountUpdateScreen screen = new AccountUpdateScreen();

    screen.setAcstNum("000000009");
    screen.setActSsn1("123");
    screen.setActSsn2("45");
    screen.setActSsn3("6789");
    screen.setDobYear("1985");
    screen.setDobMon("07");
    screen.setDobDay("04");
    screen.setAcstFco("750");
    screen.setAcsFnam("JONATHAN");
    screen.setAcsMnam("MICHAEL");
    screen.setAcsLnam("SMITH");
    screen.setAcsAdl1("123 MAIN STREET APARTMENT 4B");
    screen.setAcsStte("NY");
    screen.setAcsAdl2("BUILDING C SUITE 200");
    screen.setAcsZipc("10001");
    screen.setAcsCity("NEW YORK");
    screen.setAcsCtry("USA");
    screen.setAcsPh1a("212");
    screen.setAcsPh1b("555");
    screen.setAcsPh1c("0100");
    screen.setAcsGovt("DL-NY-1234567890");
    screen.setAcsPh2a("646");
    screen.setAcsPh2b("555");
    screen.setAcsPh2c("0199");
    screen.setAcsEftc("EFT0000001");
    screen.setAcsPflg("Y");

    assertThat(screen.getAcstNum()).isEqualTo("000000009");
    assertThat(screen.getActSsn1()).isEqualTo("123");
    assertThat(screen.getActSsn2()).isEqualTo("45");
    assertThat(screen.getActSsn3()).isEqualTo("6789");
    assertThat(screen.getDobYear()).isEqualTo("1985");
    assertThat(screen.getDobMon()).isEqualTo("07");
    assertThat(screen.getDobDay()).isEqualTo("04");
    assertThat(screen.getAcstFco()).isEqualTo("750");
    assertThat(screen.getAcsFnam()).isEqualTo("JONATHAN");
    assertThat(screen.getAcsMnam()).isEqualTo("MICHAEL");
    assertThat(screen.getAcsLnam()).isEqualTo("SMITH");
    assertThat(screen.getAcsAdl1()).isEqualTo("123 MAIN STREET APARTMENT 4B");
    assertThat(screen.getAcsStte()).isEqualTo("NY");
    assertThat(screen.getAcsAdl2()).isEqualTo("BUILDING C SUITE 200");
    assertThat(screen.getAcsZipc()).isEqualTo("10001");
    assertThat(screen.getAcsCity()).isEqualTo("NEW YORK");
    assertThat(screen.getAcsCtry()).isEqualTo("USA");
    assertThat(screen.getAcsPh1a()).isEqualTo("212");
    assertThat(screen.getAcsPh1b()).isEqualTo("555");
    assertThat(screen.getAcsPh1c()).isEqualTo("0100");
    assertThat(screen.getAcsGovt()).isEqualTo("DL-NY-1234567890");
    assertThat(screen.getAcsPh2a()).isEqualTo("646");
    assertThat(screen.getAcsPh2b()).isEqualTo("555");
    assertThat(screen.getAcsPh2c()).isEqualTo("0199");
    assertThat(screen.getAcsEftc()).isEqualTo("EFT0000001");
    assertThat(screen.getAcsPflg()).isEqualTo("Y");
  }

  /** The informational, error and PF-key legend buffers round-trip exactly. */
  @Test
  void message_and_pfkey_string_fields_round_trip() {
    AccountUpdateScreen screen = new AccountUpdateScreen();

    screen.setInfoMsg("Account loaded successfully.");
    screen.setErrMsg("Account ID must be numeric.");
    screen.setFkeys("ENTER=Cont PF3=Back");
    screen.setFkey05("PF5=Sav");
    screen.setFkey12("PF12=Exit");

    assertThat(screen.getInfoMsg()).isEqualTo("Account loaded successfully.");
    assertThat(screen.getErrMsg()).isEqualTo("Account ID must be numeric.");
    assertThat(screen.getFkeys()).isEqualTo("ENTER=Cont PF3=Back");
    assertThat(screen.getFkey05()).isEqualTo("PF5=Sav");
    assertThat(screen.getFkey12()).isEqualTo("PF12=Exit");
  }

  /**
   * Each of the five monetary fields is a fixed-width {@code PIC X(15)} text holder (the accepted
   * parity fix, AAP &sect;0.6.1 / COTRN02C / COACTUPC {@code 1250-EDIT-SIGNED-9V2}): the DTO
   * round-trips the raw characters verbatim and the service validates them char-by-char (the legacy
   * {@code TEST-NUMVAL-C} edit) before parsing to {@code BigDecimal}, so a non-numeric typo
   * surfaces a graceful field message instead of a Spring type-mismatch binding abend (HTTP 500).
   */
  @Test
  void monetary_fields_round_trip_as_string() {
    AccountUpdateScreen screen = new AccountUpdateScreen();
    String expected = "1234567.89";

    screen.setAcrdLim(expected);
    screen.setAcshLim(expected);
    screen.setAcurBal(expected);
    screen.setAcrCycr(expected);
    screen.setAcrCydb(expected);

    assertThat(screen.getAcrdLim()).isInstanceOf(String.class).isEqualTo(expected);
    assertThat(screen.getAcshLim()).isEqualTo(expected);
    assertThat(screen.getAcurBal()).isEqualTo(expected);
    assertThat(screen.getAcrCycr()).isEqualTo(expected);
    assertThat(screen.getAcrCydb()).isEqualTo(expected);
  }

  /**
   * The current balance round-trips a signed value verbatim, mirroring the signed COBOL picture
   * {@code PIC S9(10)V99}; the leading minus is preserved as raw text for the service's
   * char-by-char {@code TEST-NUMVAL-C} validation (it is not parsed by the DTO).
   */
  @Test
  void current_balance_round_trips_negative_value() {
    AccountUpdateScreen screen = new AccountUpdateScreen();

    screen.setAcurBal("-50.00");

    assertThat(screen.getAcurBal()).isEqualTo("-50.00");
  }

  /**
   * The DTO is a pass-through fixed-width text holder, so a monetary value supplied with two
   * fractional digits is returned verbatim &mdash; scale enforcement (COBOL {@code PIC S9(10)V99}
   * truncation) is the service's responsibility ({@code scale2} / {@code moneyToString}), not the
   * screen contract's.
   */
  @Test
  void monetary_fields_preserve_raw_text() {
    AccountUpdateScreen screen = new AccountUpdateScreen();

    screen.setAcrdLim("100.00");

    assertThat(screen.getAcrdLim()).isEqualTo("100.00");
  }

  /**
   * Representative {@code String} fields hold a value at their full BMS {@code LENGTH}, documenting
   * the fixed-width parity of the message line, the account id and the editable split components.
   */
  @Test
  void string_fields_round_trip_at_full_bms_width() {
    AccountUpdateScreen screen = new AccountUpdateScreen();

    String errAtWidth = "E".repeat(78);
    String infoAtWidth = "I".repeat(45);
    String acctAtWidth = "9".repeat(11);
    String opnYearAtWidth = "2".repeat(4);
    String ssn1AtWidth = "1".repeat(3);

    screen.setErrMsg(errAtWidth);
    screen.setInfoMsg(infoAtWidth);
    screen.setAcctSid(acctAtWidth);
    screen.setOpnYear(opnYearAtWidth);
    screen.setActSsn1(ssn1AtWidth);

    assertThat(screen.getErrMsg()).isEqualTo(errAtWidth).hasSize(78);
    assertThat(screen.getInfoMsg()).isEqualTo(infoAtWidth).hasSize(45);
    assertThat(screen.getAcctSid()).isEqualTo(acctAtWidth).hasSize(11);
    assertThat(screen.getOpnYear()).isEqualTo(opnYearAtWidth).hasSize(4);
    assertThat(screen.getActSsn1()).isEqualTo(ssn1AtWidth).hasSize(3);
  }

  /**
   * The {@link Size} bound on each {@code String} field equals the corresponding BMS map {@code
   * LENGTH}, guaranteeing field-width parity (AAP &sect;0.4.1). The sample includes the editable
   * split components (date, SSN and telephone parts) that are unique to this screen.
   */
  @Test
  void field_widths_match_bms_length_via_size_annotation() throws NoSuchFieldException {
    assertSizeMax("acctSid", 11);
    assertSizeMax("acsFnam", 25);
    assertSizeMax("acsAdl1", 50);
    assertSizeMax("opnYear", 4);
    assertSizeMax("opnMon", 2);
    assertSizeMax("actSsn1", 3);
    assertSizeMax("actSsn3", 4);
    assertSizeMax("acsPh1a", 3);
    assertSizeMax("acsPh1c", 4);
    assertSizeMax("dobYear", 4);
    assertSizeMax("infoMsg", 45);
    assertSizeMax("errMsg", 78);
    assertSizeMax("fkeys", 21);
  }

  /**
   * The hidden {@code old*} snapshot fields (the Java analogue of {@code ACUP-OLD-*} in the
   * COMMAREA, AAP &sect;0.6.5) round-trip the display-turn values verbatim across the
   * pseudo-conversational boundary, and carry the {@link Size} bound matching their canonical
   * carried form. These fields are the baseline the service's optimistic-concurrency check compares
   * its fresh re-read against; a representative account field, customer field and the two
   * numeric-as-string fields are exercised.
   */
  @Test
  void old_snapshot_fields_round_trip_and_carry_size_bounds() throws NoSuchFieldException {
    AccountUpdateScreen screen = new AccountUpdateScreen();

    screen.setOldAcctId("00000000001");
    screen.setOldActiveStatus("Y");
    screen.setOldCreditLimit("7500.00");
    screen.setOldCurrCycDebit("350.50");
    screen.setOldFirstName("JOHN");
    screen.setOldPhone1("(212)555-0123");
    screen.setOldSsn("123456789");
    screen.setOldFico("700");

    assertThat(screen.getOldAcctId()).isEqualTo("00000000001");
    assertThat(screen.getOldActiveStatus()).isEqualTo("Y");
    assertThat(screen.getOldCreditLimit()).isEqualTo("7500.00");
    assertThat(screen.getOldCurrCycDebit()).isEqualTo("350.50");
    assertThat(screen.getOldFirstName()).isEqualTo("JOHN");
    assertThat(screen.getOldPhone1()).isEqualTo("(212)555-0123");
    assertThat(screen.getOldSsn()).isEqualTo("123456789");
    assertThat(screen.getOldFico()).isEqualTo("700");

    assertSizeMax("oldAcctId", 11);
    assertSizeMax("oldCreditLimit", 15);
    assertSizeMax("oldFirstName", 25);
    assertSizeMax("oldSsn", 9);
    assertSizeMax("oldFico", 3);
  }

  /**
   * A freshly constructed screen leaves every field at its default: all {@code String} fields,
   * including the five monetary {@code PIC X(15)} fields, are {@code null} (the no-argument
   * constructor performs no initialisation).
   */
  @Test
  void fresh_instance_has_null_defaults() {
    AccountUpdateScreen screen = new AccountUpdateScreen();

    assertThat(screen.getTrnName()).isNull();
    assertThat(screen.getAcctSid()).isNull();
    assertThat(screen.getOpnYear()).isNull();
    assertThat(screen.getActSsn1()).isNull();
    assertThat(screen.getAcsPh1a()).isNull();
    assertThat(screen.getErrMsg()).isNull();
    assertThat(screen.getFkey12()).isNull();

    assertThat(screen.getAcrdLim()).isNull();
    assertThat(screen.getAcshLim()).isNull();
    assertThat(screen.getAcurBal()).isNull();
    assertThat(screen.getAcrCycr()).isNull();
    assertThat(screen.getAcrCydb()).isNull();
  }

  /**
   * Asserts that the named declared field of {@link AccountUpdateScreen} carries a {@link Size}
   * annotation whose {@code max} equals the expected BMS field width.
   *
   * @param fieldName the declared field name on {@link AccountUpdateScreen}
   * @param expectedMax the BMS {@code LENGTH} the field must be bounded to
   * @throws NoSuchFieldException if the field does not exist (a contract regression)
   */
  private static void assertSizeMax(String fieldName, int expectedMax) throws NoSuchFieldException {
    Field field = AccountUpdateScreen.class.getDeclaredField(fieldName);
    Size size = field.getAnnotation(Size.class);
    assertThat(size).as("@Size annotation present on field '%s'", fieldName).isNotNull();
    assertThat(size.max()).as("@Size max on field '%s'", fieldName).isEqualTo(expectedMax);
  }
}
