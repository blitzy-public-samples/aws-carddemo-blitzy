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
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link ReportScreen}, the screen view contract migrated from the legacy
 * CICS/BMS mapset {@code CORPT00} (online transaction {@code CR00}).
 *
 * <p>{@code ReportScreen} is a flat, framework-light selector POJO: it has no monetary fields and
 * no nested rows. These tests therefore stay deliberately simple and exercise only the screen
 * contract itself — they instantiate the DTO directly with {@code new ReportScreen()} and assert
 * against it with AssertJ. There is intentionally <em>no</em> Spring context, database,
 * Testcontainers, or Mockito involvement.
 *
 * <p>The suite pins the two properties that protect behavioral parity with the 24x80 3270 screen:
 *
 * <ul>
 *   <li><b>Accessor round-trip</b> — every one of the 17 fields exposes a working getter/setter
 *       pair so the presentation layer and {@code ReportService} can exchange raw field values
 *       without loss.
 *   <li><b>Field-width fidelity</b> — every {@code @Size(max = n)} constraint equals the
 *       originating BMS {@code LENGTH} / COBOL {@code PIC X(n)} width, so the modern field contract
 *       remains byte-faithful to {@code legacy/app/cpy-bms/CORPT00.CPY} and {@code
 *       legacy/app/bms/CORPT00.bms}.
 * </ul>
 *
 * <p><strong>Authority:</strong> AAP section 0.4.1 ("dto/screen/*.java ... preserving field
 * lengths"), section 0.3.4 (User Interface Design), and the quality gates of section 0.7.3.
 */
class ReportScreenTest {

  /**
   * Round-trips all 17 string fields through their setters and getters, proving the complete screen
   * contract is wired and lossless. Values mirror a realistic report request: the monthly selector
   * chosen, the two custom date-range triplets populated, and the submission confirmed.
   */
  @Test
  void all_field_getters_return_values_set_via_setters() {
    ReportScreen screen = new ReportScreen();
    String errMsg = "E".repeat(78);

    // Header (6 fields).
    screen.setTrnName("CR00");
    screen.setTitle01("CardDemo Transaction Reports");
    screen.setCurDate("07/18/22");
    screen.setPgmName("CORPT00C");
    screen.setTitle02("AWS Mainframe Modernization");
    screen.setCurTime("17:02:43");

    // Report-type selectors (mutually exclusive radio-style flags).
    screen.setMonthly("Y");
    screen.setYearly("N");
    screen.setCustom("N");

    // Custom range start-date and end-date component triplets.
    screen.setSdtMm("01");
    screen.setSdtDd("02");
    screen.setSdtYyyy("2026");
    screen.setEdtMm("12");
    screen.setEdtDd("31");
    screen.setEdtYyyy("2026");

    // Confirmation flag and message line.
    screen.setConfirm("Y");
    screen.setErrMsg(errMsg);

    assertThat(screen.getTrnName()).isEqualTo("CR00");
    assertThat(screen.getTitle01()).isEqualTo("CardDemo Transaction Reports");
    assertThat(screen.getCurDate()).isEqualTo("07/18/22");
    assertThat(screen.getPgmName()).isEqualTo("CORPT00C");
    assertThat(screen.getTitle02()).isEqualTo("AWS Mainframe Modernization");
    assertThat(screen.getCurTime()).isEqualTo("17:02:43");
    assertThat(screen.getMonthly()).isEqualTo("Y");
    assertThat(screen.getYearly()).isEqualTo("N");
    assertThat(screen.getCustom()).isEqualTo("N");
    assertThat(screen.getSdtMm()).isEqualTo("01");
    assertThat(screen.getSdtDd()).isEqualTo("02");
    assertThat(screen.getSdtYyyy()).isEqualTo("2026");
    assertThat(screen.getEdtMm()).isEqualTo("12");
    assertThat(screen.getEdtDd()).isEqualTo("31");
    assertThat(screen.getEdtYyyy()).isEqualTo("2026");
    assertThat(screen.getConfirm()).isEqualTo("Y");
    assertThat(screen.getErrMsg()).isEqualTo(errMsg).hasSize(78);
  }

  /**
   * Round-trips values sized to the exact BMS field widths, documenting the fixed-length contract
   * the DTO must honor: the message line (78), the year components (4), the month/day components
   * (2), and the single-character selector/confirmation flags (1).
   */
  @Test
  void field_widths_round_trip_at_bms_lengths() {
    ReportScreen screen = new ReportScreen();

    String errMsg = "E".repeat(78);
    screen.setErrMsg(errMsg);
    assertThat(screen.getErrMsg()).isEqualTo(errMsg).hasSize(78);

    String year = "2026";
    screen.setSdtYyyy(year);
    screen.setEdtYyyy(year);
    assertThat(screen.getSdtYyyy()).isEqualTo(year).hasSize(4);
    assertThat(screen.getEdtYyyy()).isEqualTo(year).hasSize(4);

    screen.setSdtMm("01");
    screen.setSdtDd("02");
    screen.setEdtMm("12");
    screen.setEdtDd("31");
    assertThat(screen.getSdtMm()).isEqualTo("01").hasSize(2);
    assertThat(screen.getSdtDd()).isEqualTo("02").hasSize(2);
    assertThat(screen.getEdtMm()).isEqualTo("12").hasSize(2);
    assertThat(screen.getEdtDd()).isEqualTo("31").hasSize(2);

    screen.setMonthly("Y");
    screen.setYearly("N");
    screen.setCustom("N");
    screen.setConfirm("Y");
    assertThat(screen.getMonthly()).isEqualTo("Y").hasSize(1);
    assertThat(screen.getYearly()).isEqualTo("N").hasSize(1);
    assertThat(screen.getCustom()).isEqualTo("N").hasSize(1);
    assertThat(screen.getConfirm()).isEqualTo("Y").hasSize(1);
  }

  /**
   * Verifies every {@code @Size(max = n)} constraint matches the originating BMS {@code LENGTH} /
   * COBOL {@code PIC X(n)} width from {@code CORPT00}. This is the field-width fidelity gate: any
   * drift between the Java DTO and the legacy screen layout fails the build.
   */
  @Test
  void size_constraints_match_bms_field_widths() throws NoSuchFieldException {
    assertThat(maxOf("trnName")).isEqualTo(4);
    assertThat(maxOf("title01")).isEqualTo(40);
    assertThat(maxOf("curDate")).isEqualTo(8);
    assertThat(maxOf("pgmName")).isEqualTo(8);
    assertThat(maxOf("title02")).isEqualTo(40);
    assertThat(maxOf("curTime")).isEqualTo(8);
    assertThat(maxOf("monthly")).isEqualTo(1);
    assertThat(maxOf("yearly")).isEqualTo(1);
    assertThat(maxOf("custom")).isEqualTo(1);
    assertThat(maxOf("sdtMm")).isEqualTo(2);
    assertThat(maxOf("sdtDd")).isEqualTo(2);
    assertThat(maxOf("sdtYyyy")).isEqualTo(4);
    assertThat(maxOf("edtMm")).isEqualTo(2);
    assertThat(maxOf("edtDd")).isEqualTo(2);
    assertThat(maxOf("edtYyyy")).isEqualTo(4);
    assertThat(maxOf("confirm")).isEqualTo(1);
    assertThat(maxOf("errMsg")).isEqualTo(78);
  }

  /** A freshly constructed screen has no field values: every string property is {@code null}. */
  @Test
  void fresh_instance_has_null_string_fields() {
    ReportScreen screen = new ReportScreen();

    assertThat(screen.getTrnName()).isNull();
    assertThat(screen.getTitle01()).isNull();
    assertThat(screen.getCurDate()).isNull();
    assertThat(screen.getPgmName()).isNull();
    assertThat(screen.getTitle02()).isNull();
    assertThat(screen.getCurTime()).isNull();
    assertThat(screen.getMonthly()).isNull();
    assertThat(screen.getYearly()).isNull();
    assertThat(screen.getCustom()).isNull();
    assertThat(screen.getSdtMm()).isNull();
    assertThat(screen.getSdtDd()).isNull();
    assertThat(screen.getSdtYyyy()).isNull();
    assertThat(screen.getEdtMm()).isNull();
    assertThat(screen.getEdtDd()).isNull();
    assertThat(screen.getEdtYyyy()).isNull();
    assertThat(screen.getConfirm()).isNull();
    assertThat(screen.getErrMsg()).isNull();
  }

  /**
   * Reads the {@code max} attribute of the {@link Size} constraint declared on the named field via
   * reflection. {@code @Size} is retained at runtime, so the field annotation is readable directly.
   *
   * @param fieldName the {@link ReportScreen} field to inspect
   * @return the declared maximum length
   * @throws NoSuchFieldException if the field does not exist (guards against accidental renames)
   */
  private static int maxOf(String fieldName) throws NoSuchFieldException {
    return ReportScreen.class.getDeclaredField(fieldName).getAnnotation(Size.class).max();
  }
}
