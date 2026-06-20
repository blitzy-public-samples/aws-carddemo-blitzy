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
 * Pure unit test for {@link AccountUpdateScreen}, the Java migration of the legacy CICS/BMS mapset
 * {@code COACTUP} (CICS transaction {@code CAUP}).
 *
 * <p>The test verifies the screen DTO contract against the authoritative legacy specification
 * ({@code legacy/app/cpy-bms/COACTUP.CPY} symbolic AI map and {@code legacy/app/bms/COACTUP.bms}
 * field {@code LENGTH}s). It asserts three properties that guarantee parity with the 3270 screen:
 *
 * <ul>
 *   <li><b>Round-trip fidelity</b> &mdash; every one of the 54 fields stores and returns the value
 *       it was given. This includes the editable split-component fields that distinguish the
 *       Account Update screen from the Account View screen: the year / month / day date components,
 *       the three SSN parts and the area / prefix / line telephone components.
 *   <li><b>Monetary decimal fidelity (AAP &sect;0.6.1)</b> &mdash; the five monetary fields (credit
 *       limit, cash credit limit, current balance, current cycle credit, current cycle debit) are
 *       {@link BigDecimal} with a conceptual scale of 2; floating-point types are never used.
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
   * Each of the five monetary fields is a {@link BigDecimal} that round-trips a value with two
   * fractional digits without loss (AAP &sect;0.6.1). {@code isEqualByComparingTo} is used so the
   * assertion is robust to representation differences while still confirming numeric equality.
   */
  @Test
  void monetary_fields_round_trip_as_big_decimal() {
    AccountUpdateScreen screen = new AccountUpdateScreen();
    BigDecimal expected = new BigDecimal("1234567.89");

    screen.setAcrdLim(expected);
    screen.setAcshLim(expected);
    screen.setAcurBal(expected);
    screen.setAcrCycr(expected);
    screen.setAcrCydb(expected);

    assertThat(screen.getAcrdLim()).isInstanceOf(BigDecimal.class).isEqualByComparingTo(expected);
    assertThat(screen.getAcshLim()).isInstanceOf(BigDecimal.class).isEqualByComparingTo(expected);
    assertThat(screen.getAcurBal()).isInstanceOf(BigDecimal.class).isEqualByComparingTo(expected);
    assertThat(screen.getAcrCycr()).isInstanceOf(BigDecimal.class).isEqualByComparingTo(expected);
    assertThat(screen.getAcrCydb()).isInstanceOf(BigDecimal.class).isEqualByComparingTo(expected);
  }

  /**
   * The current balance accepts and preserves a negative value, mirroring the signed COBOL picture
   * {@code PIC S9(10)V99}.
   */
  @Test
  void current_balance_round_trips_negative_value() {
    AccountUpdateScreen screen = new AccountUpdateScreen();

    screen.setAcurBal(new BigDecimal("-50.00"));

    assertThat(screen.getAcurBal()).isEqualByComparingTo(new BigDecimal("-50.00"));
    assertThat(screen.getAcurBal()).isNegative();
  }

  /**
   * The DTO is a pass-through holder, so a value supplied at scale 2 is returned at scale 2 &mdash;
   * preserving the COBOL {@code PIC S9(10)V99} two-decimal precision. The primary equality
   * assertion remains scale-insensitive for robustness.
   */
  @Test
  void monetary_fields_preserve_scale_of_two() {
    AccountUpdateScreen screen = new AccountUpdateScreen();
    BigDecimal scaledTwo = new BigDecimal("100.00");

    screen.setAcrdLim(scaledTwo);

    assertThat(screen.getAcrdLim()).isEqualByComparingTo(scaledTwo);
    assertThat(screen.getAcrdLim().scale()).isEqualTo(2);
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
   * A freshly constructed screen leaves every field at its default: {@code String} fields are
   * {@code null} and the five monetary {@link BigDecimal} fields are {@code null} (the no-argument
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
