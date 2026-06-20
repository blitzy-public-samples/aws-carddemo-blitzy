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
package com.aws.carddemo.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pure unit tests for {@link DateValidationService}, the Java translation of the legacy COBOL date
 * routines {@code app/cbl/CSUTLDTC.cbl} (the {@code CDV1} {@code CEEDAYS} wrapper) plus the
 * field-edit paragraphs in {@code app/cpy/CSUTLDPY.cpy} / {@code app/cpy/CSUTLDWY.cpy}.
 *
 * <p>These tests pin the behaviors that, if mis-translated, would silently accept or reject dates
 * differently from the mainframe (Agent Action Plan &sect;0.6) and so constitute a behavioral
 * regression:
 *
 * <ul>
 *   <li><b>COBOL leap-year rule</b> — divisor 400 when the two-digit year-within-century is zero,
 *       otherwise 4 (so 1900 is <em>not</em> leap but 2000 is). See {@code EDIT-DAY-MONTH-YEAR}.
 *   <li><b>Century gating</b> — only the 19xx and 20xx centuries are accepted, and only by {@link
 *       DateValidationService#editDateCcyymmdd(String, String)} (the {@code CEEDAYS} approximation
 *       in {@link DateValidationService#validateDate(String, String)} does <em>not</em> apply it).
 *   <li><b>Date-of-birth future rejection</b> — the current date must be <em>strictly</em> greater
 *       than the supplied date, so a date that is today or in the future is rejected ({@code
 *       EDIT-DATE-OF-BIRTH}).
 *   <li><b>Never-throws contract</b> — an invalid date is reported through the returned result
 *       object, never as an exception, preserving the COBOL flag-based (non-abending) edit flow.
 * </ul>
 *
 * <p>The suite is intentionally JDK-only: no Spring context, no database, no Testcontainers, and no
 * mocks. Message text is asserted with {@code contains(keyword)} (COBOL edit messages have
 * idiosyncratic spacing and colons) while the {@code valid} flag and the per-field {@link
 * DateValidationService.DateEditResult.FieldStatus} values are asserted exactly. Every assertion is
 * aligned to the actual production contract in {@code
 * src/main/java/com/aws/carddemo/util/DateValidationService.java}.
 */
@DisplayName("DateValidationService — COBOL CSUTLDTC/CSUTLDPY date-edit parity")
class DateValidationServiceTest {

  // Readable aliases for the nested field-status flags (mirrors DateValidationService L125-L127).
  private static final DateValidationService.DateEditResult.FieldStatus VALID =
      DateValidationService.DateEditResult.FieldStatus.VALID;
  private static final DateValidationService.DateEditResult.FieldStatus NOT_OK =
      DateValidationService.DateEditResult.FieldStatus.NOT_OK;
  private static final DateValidationService.DateEditResult.FieldStatus BLANK =
      DateValidationService.DateEditResult.FieldStatus.BLANK;

  // A fixed "today" so every date-of-birth test is deterministic and reproducible.
  private static final LocalDate TODAY = LocalDate.of(2024, 1, 1);

  // ---------------------------------------------------------------------------
  // isLeapYearCobol — the exact COBOL %400 (century) / %4 (non-century) branch
  // ---------------------------------------------------------------------------

  @ParameterizedTest(name = "isLeapYearCobol({0}) == {1}")
  @CsvSource({
    "2000,true",
    "1900,false",
    "2024,true",
    "2023,false",
    "2100,false",
    "1600,true",
    "2400,true",
    "2200,false",
    "2004,true",
    "1999,false"
  })
  @DisplayName("isLeapYearCobol follows the COBOL %400 (century) / %4 (non-century) rule")
  void isLeapYearCobol_follows_cobol_400_and_4_rule(int ccyy, boolean expected) {
    // Century years (year-within-century == 0: 1900/2000/2100/1600/2400/2200) use the 400 divisor;
    // every other year uses the 4 divisor. So 1900 and 2100 and 2200 are NOT leap, but 2000, 1600,
    // and 2400 are.
    assertThat(DateValidationService.isLeapYearCobol(ccyy)).isEqualTo(expected);
  }

  // ---------------------------------------------------------------------------
  // validateDate — CEEDAYS approximation (strict calendar validation, no century rule)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("validateDate accepts valid dates and rejects impossible calendar dates")
  void validateDate_accepts_valid_and_rejects_invalid_calendar_dates() {
    var valid = DateValidationService.validateDate("20221231", "YYYYMMDD");
    assertThat(valid.valid()).isTrue();
    assertThat(valid.severity()).isEqualTo(DateValidationService.SEVERITY_OK);
    assertThat(valid.resultText()).isEqualTo(DateValidationService.RESULT_VALID);

    // February 30 never exists: strict parsing rejects it.
    var feb30 = DateValidationService.validateDate("20220230", "YYYYMMDD");
    assertThat(feb30.valid()).isFalse();
    assertThat(feb30.resultText()).contains("invalid");

    // 2022 is not a leap year, so February 29 is invalid; 2024 is a leap year, so it is valid.
    assertThat(DateValidationService.validateDate("20220229", "YYYYMMDD").valid()).isFalse();
    assertThat(DateValidationService.validateDate("20240229", "YYYYMMDD").valid()).isTrue();
  }

  @Test
  @DisplayName("validateDate does NOT enforce the 19xx/20xx century rule (CEEDAYS accepts 1899)")
  void validateDate_does_not_enforce_century_rule() {
    // The 19/20 century restriction lives only in editDateCcyymmdd; CEEDAYS treats 1899-12-31 as a
    // real date, so validateDate must accept it.
    assertThat(DateValidationService.validateDate("18991231", "YYYYMMDD").valid()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // editDateCcyymmdd — century gating, leap February, month/day range edits
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("editDateCcyymmdd gates centuries to 19xx/20xx only")
  void editDateCcyymmdd_enforces_19xx_20xx_century_gating() {
    assertThat(DateValidationService.editDateCcyymmdd("20001231", "DOB").valid()).isTrue();
    assertThat(DateValidationService.editDateCcyymmdd("19991231", "DOB").valid()).isTrue();

    var century18 = DateValidationService.editDateCcyymmdd("18991231", "DOB");
    assertThat(century18.valid()).isFalse();
    assertThat(century18.year()).isEqualTo(NOT_OK);
    assertThat(century18.message()).contains("Century");

    var century21 = DateValidationService.editDateCcyymmdd("21001231", "DOB");
    assertThat(century21.valid()).isFalse();
    assertThat(century21.year()).isEqualTo(NOT_OK);
    assertThat(century21.message()).contains("Century");
  }

  @Test
  @DisplayName("editDateCcyymmdd applies the COBOL leap-year rule to February 29")
  void editDateCcyymmdd_applies_cobol_leap_year_for_february() {
    // 2024 is a leap year under the COBOL rule -> February 29 is accepted.
    assertThat(DateValidationService.editDateCcyymmdd("20240229", "DOB").valid()).isTrue();

    // 2023 is not a leap year -> February 29 is rejected; COBOL marks day, month, AND year NOT_OK.
    var feb29NonLeap = DateValidationService.editDateCcyymmdd("20230229", "DOB");
    assertThat(feb29NonLeap.valid()).isFalse();
    assertThat(feb29NonLeap.day()).isEqualTo(NOT_OK);
    assertThat(feb29NonLeap.month()).isEqualTo(NOT_OK);
    assertThat(feb29NonLeap.message()).contains("leap year");
  }

  @Test
  @DisplayName("editDateCcyymmdd rejects out-of-range months and days and accepts a clean date")
  void editDateCcyymmdd_rejects_out_of_range_month_and_day() {
    // Month 13 is out of the 1-12 range.
    var month13 = DateValidationService.editDateCcyymmdd("20221301", "DOB");
    assertThat(month13.valid()).isFalse();
    assertThat(month13.month()).isEqualTo(NOT_OK);
    assertThat(month13.message()).contains("Month");

    // Month 00 is out of the 1-12 range.
    var month00 = DateValidationService.editDateCcyymmdd("20220015", "DOB");
    assertThat(month00.valid()).isFalse();
    assertThat(month00.month()).isEqualTo(NOT_OK);

    // April has only 30 days, so day 31 is rejected.
    var april31 = DateValidationService.editDateCcyymmdd("20220431", "DOB");
    assertThat(april31.valid()).isFalse();
    assertThat(april31.message()).contains("31 days");

    // February never has 30 days.
    var feb30 = DateValidationService.editDateCcyymmdd("20220230", "DOB");
    assertThat(feb30.valid()).isFalse();
    assertThat(feb30.message()).contains("30 days");

    // A clean, in-range date passes every field edit and the Language-Environment backstop.
    var clean = DateValidationService.editDateCcyymmdd("20220115", "DOB");
    assertThat(clean.valid()).isTrue();
    assertThat(clean.year()).isEqualTo(VALID);
    assertThat(clean.month()).isEqualTo(VALID);
    assertThat(clean.day()).isEqualTo(VALID);
  }

  // ---------------------------------------------------------------------------
  // editDateOfBirth — strictly-in-the-past reasonableness check
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("editDateOfBirth rejects today and future dates and accepts past dates")
  void editDateOfBirth_rejects_today_and_future_dates() {
    // A far-future date is rejected, and the future check takes precedence over the century edit.
    var future = DateValidationService.editDateOfBirth("29991231", "DOB", TODAY);
    assertThat(future.valid()).isFalse();
    assertThat(future.message()).contains("future");

    // COBOL requires the current date to be STRICTLY greater than the edit date, so a date equal to
    // today is rejected as well.
    var todayDob = DateValidationService.editDateOfBirth("20240101", "DOB", TODAY);
    assertThat(todayDob.valid()).isFalse();
    assertThat(todayDob.message()).contains("future");

    // A date of birth strictly in the past is accepted.
    assertThat(DateValidationService.editDateOfBirth("19900615", "DOB", TODAY).valid()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // Never-throws contract — bad input yields an invalid result, never an exception
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("date methods never throw on null/garbage/non-numeric input")
  void date_methods_never_throw_on_bad_input() {
    assertThatCode(() -> DateValidationService.editDateCcyymmdd("garbage!", "F"))
        .doesNotThrowAnyException();
    assertThatCode(() -> DateValidationService.editDateCcyymmdd(null, "F"))
        .doesNotThrowAnyException();
    assertThatCode(() -> DateValidationService.validateDate("zzzzzzzz", "YYYYMMDD"))
        .doesNotThrowAnyException();
    assertThatCode(() -> DateValidationService.editDateOfBirth("00000000", "F", TODAY))
        .doesNotThrowAnyException();

    // Each bad input yields an invalid result object rather than an exception.
    assertThat(DateValidationService.editDateCcyymmdd("garbage!", "F").valid()).isFalse();
    assertThat(DateValidationService.editDateCcyymmdd(null, "F").valid()).isFalse();
    assertThat(DateValidationService.validateDate("zzzzzzzz", "YYYYMMDD").valid()).isFalse();
    assertThat(DateValidationService.editDateOfBirth("00000000", "F", TODAY).valid()).isFalse();

    // A non-numeric year fails the "4 digit number" edit.
    var nonNumericYear = DateValidationService.editDateCcyymmdd("20XX1231", "F");
    assertThat(nonNumericYear.valid()).isFalse();
    assertThat(nonNumericYear.year()).isEqualTo(NOT_OK);
    assertThat(nonNumericYear.message()).contains("4 digit");

    // A blank year position is reported as BLANK with the "must be supplied" message.
    var blankYear = DateValidationService.editDateCcyymmdd("    1231", "F");
    assertThat(blankYear.valid()).isFalse();
    assertThat(blankYear.year()).isEqualTo(BLANK);
    assertThat(blankYear.message()).contains("supplied");
  }

  // ---------------------------------------------------------------------------
  // Severity/result tokens, the tolerated-2513 predicate, and the FieldStatus enum
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "severity/result constants, the tolerated-2513 predicate, and FieldStatus match COBOL")
  void severity_and_result_constants_match_cobol_tokens() {
    assertThat(DateValidationService.SEVERITY_OK).isEqualTo("0000");
    assertThat(DateValidationService.MSG_UNSUPPORTED_RANGE).isEqualTo("2513");
    assertThat(DateValidationService.RESULT_VALID).isEqualTo("Date is valid");
    assertThat(DateValidationService.RESULT_INVALID).isEqualTo("Date is invalid");
    assertThat(DateValidationService.DEFAULT_FORMAT).isEqualTo("YYYYMMDD");

    // The CEEDAYS "Unsupp. Range" message (2513) is tolerated even when the date is invalid.
    var tolerated =
        new DateValidationService.DateValidationResult(
            "0012", "2513", "Unsupp. Range  ", "", false);
    assertThat(tolerated.isToleratedUnsupportedRange()).isTrue();

    // Any other message number is not tolerated.
    var notTolerated =
        new DateValidationService.DateValidationResult("0000", "0000", "Date is valid", "", true);
    assertThat(notTolerated.isToleratedUnsupportedRange()).isFalse();

    // The CSUTLDWY FLG-* flags are modeled by exactly three FieldStatus constants.
    assertThat(DateValidationService.DateEditResult.FieldStatus.values())
        .contains(VALID, NOT_OK, BLANK);
  }
}
