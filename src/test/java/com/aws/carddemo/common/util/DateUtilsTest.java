/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.Test;

/**
 * Fast, isolated, pure-logic unit tests for {@link DateUtils}, verifying behavioral parity with
 * the COBOL date copybooks relocated under {@code legacy/**} during the migration:
 * <ul>
 *   <li>{@code legacy/cpy/CSDAT01Y.cpy} &mdash; the display format masks
 *       ({@code MM/DD/YY}, {@code HH:MM:SS}, and the microsecond timestamp);</li>
 *   <li>{@code legacy/cpy/CSUTLDWY.cpy} &mdash; the 88-level ranges (valid century, month, day,
 *       31-day months, February);</li>
 *   <li>{@code legacy/cpy/CSUTLDPY.cpy} &mdash; the {@code EDIT-DATE-CCYYMMDD} validation
 *       procedure, the leap-year rule, and the {@code EDIT-DATE-OF-BIRTH} reasonableness check;</li>
 *   <li>{@code legacy/cbl/CSUTLDTC.cbl} / {@code CEEDAYS} &mdash; the strict proleptic-Gregorian
 *       calendar validation.</li>
 * </ul>
 *
 * <p>These tests use JUnit 5 (Jupiter) with AssertJ and call the {@code static} methods of
 * {@code DateUtils} directly. There is <strong>no</strong> Spring context, database, or
 * Testcontainers dependency, so the suite runs headlessly and reproducibly. Date-of-birth
 * assertions are computed relative to {@link LocalDate#now()} so they remain stable on any run
 * date. {@code DateUtils} lives in the same package and is therefore referenced without an
 * import.</p>
 *
 * <p>The boundary cases exercised here were verified against the proleptic-Gregorian calendar
 * that {@code java.time} uses.</p>
 */
class DateUtilsTest {

    /**
     * Calendar-valid dates that <em>also</em> satisfy the COBOL 19/20 century restriction
     * (centuries 19 and 20 only, per {@code legacy/cpy/CSUTLDWY.cpy}). Each must be accepted by
     * both the pure strict-calendar check and the century-gated composition.
     */
    private static final String[] VALID_CENTURY_GATED = {
            "20240229", // 2024 leap (divisible by 4)
            "20000229", // 2000 leap (century year divisible by 400)
            "20240131", // January 31
            "19991231", // December 31
            "20000101", // first day of the 2000s
            "20991231", // last day of the 2000s
            "19960229"  // 1996 leap (divisible by 4)
    };

    /**
     * Impossible calendar dates: rejected by the strict {@code EDIT-DATE-LE}
     * ({@code CEEDAYS}) parse itself, independent of the century gate.
     */
    private static final String[] CALENDAR_INVALID = {
            "20230229", // 2023 is not a leap year -> no February 29
            "19000229", // 1900 is not a leap year (century year not divisible by 400)
            "21000229", // 2100 is not a leap year (century year not divisible by 400)
            "20240230", // February can never have 30 days
            "20240431", // April has only 30 days
            "20241301", // month 13 is invalid
            "20240001", // month 00 is invalid
            "20240100"  // day 00 is invalid
    };

    // ------------------------------------------------------------------------
    // Phase 2 - Strict calendar validity: VALID dates (century 19/20)
    // ------------------------------------------------------------------------

    @Test
    void validCalendarDatesAreAcceptedAndCenturyGated() {
        for (String s : VALID_CENTURY_GATED) {
            assertThat(DateUtils.isValidCcyyMmDd(s))
                    .as("isValidCcyyMmDd should accept the real calendar date %s", s)
                    .isTrue();
            assertThat(cobolValidDate(s))
                    .as("the COBOL EDIT-DATE-CCYYMMDD contract should accept %s", s)
                    .isTrue();
            assertThat(DateUtils.isValidCcyyMmDdWithCenturyRule(s))
                    .as("century-gated validity should accept %s", s)
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------------
    // Phase 3 - Strict calendar validity: CALENDAR-INVALID dates -> false
    // ------------------------------------------------------------------------

    @Test
    void calendarInvalidDatesAreRejected() {
        for (String s : CALENDAR_INVALID) {
            assertThat(DateUtils.isValidCcyyMmDd(s))
                    .as("isValidCcyyMmDd should reject the impossible date %s", s)
                    .isFalse();
            // Defense in depth: these fail the calendar parse, so the composed contract also fails.
            assertThat(cobolValidDate(s))
                    .as("the COBOL EDIT-DATE-CCYYMMDD contract should reject %s", s)
                    .isFalse();
            assertThat(DateUtils.isValidCcyyMmDdWithCenturyRule(s))
                    .as("century-gated validity should reject %s", s)
                    .isFalse();
        }
    }

    @Test
    void malformedAndNonNumericInputIsRejected() {
        // Reproduces the COBOL length/numeric edits that precede calendar validation.
        assertThat(DateUtils.isValidCcyyMmDd("2024013")).as("7 characters is not CCYYMMDD").isFalse();
        assertThat(DateUtils.isValidCcyyMmDd("2024O131")).as("letter O is not a digit").isFalse();
        assertThat(DateUtils.isValidCcyyMmDd("")).as("empty string").isFalse();
        assertThat(DateUtils.isValidCcyyMmDd(null)).as("null string").isFalse();

        assertThat(cobolValidDate("2024013")).isFalse();
        assertThat(cobolValidDate("2024O131")).isFalse();
        assertThat(cobolValidDate("")).isFalse();
        assertThat(cobolValidDate(null)).isFalse();
    }

    // ------------------------------------------------------------------------
    // Phase 4 - Century gate + full COBOL date validity
    // ------------------------------------------------------------------------

    @Test
    void centuryPrimitiveAcceptsOnly19And20() {
        assertThat(DateUtils.isValidCentury(19)).as("century 19 (LAST-CENTURY)").isTrue();
        assertThat(DateUtils.isValidCentury(20)).as("century 20 (THIS-CENTURY)").isTrue();
        assertThat(DateUtils.isValidCentury(18)).as("century 18 is out of range").isFalse();
        assertThat(DateUtils.isValidCentury(21)).as("century 21 is out of range").isFalse();
    }

    @Test
    void centuryRuleRejectsRealDatesOutside19And20() {
        // 1899-12-31 and 2100-01-01 are REAL Gregorian dates, so the pure calendar parse accepts
        // them; they are invalid ONLY under the COBOL 19/20 century restriction. Asserting the
        // pure-calendar contract stays true here documents that nuance explicitly.
        assertThat(DateUtils.isValidCcyyMmDd("18991231"))
                .as("1899-12-31 is a real calendar date").isTrue();
        assertThat(DateUtils.isValidCcyyMmDd("21000101"))
                .as("2100-01-01 is a real calendar date").isTrue();

        // The century gate (via the composed helper and the production convenience method) rejects
        // both because their centuries (18 and 21) are outside the accepted 19/20 range.
        assertThat(cobolValidDate("18991231")).as("century 18 fails the COBOL gate").isFalse();
        assertThat(cobolValidDate("21000101")).as("century 21 fails the COBOL gate").isFalse();
        assertThat(DateUtils.isValidCcyyMmDdWithCenturyRule("18991231")).isFalse();
        assertThat(DateUtils.isValidCcyyMmDdWithCenturyRule("21000101")).isFalse();
    }

    // ------------------------------------------------------------------------
    // Phase 5 - Leap-year rule (CSUTLDPY.cpy L242-272)
    // ------------------------------------------------------------------------

    @Test
    void leapYearFollowsCobolDivisorRule() {
        assertThat(DateUtils.isLeapYear(2000)).as("2000 divisible by 400 -> leap").isTrue();
        assertThat(DateUtils.isLeapYear(1900)).as("1900 not divisible by 400 -> not leap").isFalse();
        assertThat(DateUtils.isLeapYear(2100)).as("2100 not divisible by 400 -> not leap").isFalse();
        assertThat(DateUtils.isLeapYear(2024)).as("2024 divisible by 4 -> leap").isTrue();
        assertThat(DateUtils.isLeapYear(2023)).as("2023 not divisible by 4 -> not leap").isFalse();
    }

    // ------------------------------------------------------------------------
    // Phase 6 - Component primitives (88-level ranges from CSUTLDWY.cpy)
    // ------------------------------------------------------------------------

    @Test
    void monthPrimitiveMatchesValidMonth88Level() {
        assertThat(DateUtils.isValidMonth(1)).isTrue();
        assertThat(DateUtils.isValidMonth(12)).isTrue();
        assertThat(DateUtils.isValidMonth(0)).isFalse();
        assertThat(DateUtils.isValidMonth(13)).isFalse();
    }

    @Test
    void thirtyOneDayMonthAndFebruaryPrimitives() {
        for (int m : new int[] {1, 3, 5, 7, 8, 10, 12}) {
            assertThat(DateUtils.isThirtyOneDayMonth(m)).as("month %d is a 31-day month", m).isTrue();
        }
        for (int m : new int[] {2, 4, 6, 9, 11}) {
            assertThat(DateUtils.isThirtyOneDayMonth(m)).as("month %d is not a 31-day month", m).isFalse();
        }
        assertThat(DateUtils.isFebruary(2)).as("February is month 2").isTrue();
        assertThat(DateUtils.isFebruary(1)).isFalse();
        assertThat(DateUtils.isFebruary(3)).isFalse();
    }

    @Test
    void dayPrimitiveMatchesValidDay88Level() {
        assertThat(DateUtils.isValidDay(1)).isTrue();
        assertThat(DateUtils.isValidDay(31)).isTrue();
        assertThat(DateUtils.isValidDay(0)).isFalse();
        assertThat(DateUtils.isValidDay(32)).isFalse();
    }

    @Test
    void februaryAndThirtyOneDayCombinationsViaStrictParse() {
        // Feb-day rule (1..28 always valid; 29 only in a leap year; 30 never).
        assertThat(DateUtils.isValidCcyyMmDd("20230228")).as("Feb 28 is valid").isTrue();
        assertThat(DateUtils.isValidCcyyMmDd("20230229")).as("Feb 29 in a non-leap year is invalid").isFalse();
        assertThat(DateUtils.isValidCcyyMmDd("20240229")).as("Feb 29 in a leap year is valid").isTrue();
        assertThat(DateUtils.isValidCcyyMmDd("20230230")).as("Feb 30 never exists").isFalse();
        // 31-day-month rule.
        assertThat(DateUtils.isValidCcyyMmDd("20240131")).as("Jan 31 is valid").isTrue();
        assertThat(DateUtils.isValidCcyyMmDd("20240431")).as("Apr 31 is invalid").isFalse();
        assertThat(DateUtils.isValidCcyyMmDd("20240631")).as("Jun 31 is invalid").isFalse();
    }

    @Test
    void dayForMonthCombinationChecks() {
        // EDIT-DAY-MONTH-YEAR combination checks (CSUTLDPY.cpy L209-272).
        assertThat(DateUtils.isValidDayForMonth(2024, 1, 31)).as("Jan 31 admissible").isTrue();
        assertThat(DateUtils.isValidDayForMonth(2024, 4, 31)).as("Apr cannot have 31 days").isFalse();
        assertThat(DateUtils.isValidDayForMonth(2024, 2, 29)).as("Feb 29 in a leap year").isTrue();
        assertThat(DateUtils.isValidDayForMonth(2023, 2, 29)).as("Feb 29 in a non-leap year").isFalse();
        assertThat(DateUtils.isValidDayForMonth(2024, 2, 30)).as("Feb cannot have 30 days").isFalse();
    }

    // ------------------------------------------------------------------------
    // Phase 7 - Date-of-birth reasonableness (STRICTLY before today)
    // ------------------------------------------------------------------------

    @Test
    void dateOfBirthMustBeStrictlyBeforeToday() {
        LocalDate today = LocalDate.now();
        assertThat(DateUtils.isDateOfBirthValid(today.minusDays(1)))
                .as("a date of birth in the past is valid").isTrue();
        assertThat(DateUtils.isDateOfBirthValid(today))
                .as("a date of birth equal to today must be INVALID (strictly-before-today parity)")
                .isFalse();
        assertThat(DateUtils.isDateOfBirthValid(today.plusDays(1)))
                .as("a date of birth in the future is invalid").isFalse();
    }

    @Test
    void dateOfBirthStringOverloadAndGuards() {
        LocalDate today = LocalDate.now();
        assertThat(DateUtils.isDateOfBirthValid(ccyymmdd(today.minusDays(1)))).isTrue();
        assertThat(DateUtils.isDateOfBirthValid(ccyymmdd(today))).isFalse();
        assertThat(DateUtils.isDateOfBirthValid(ccyymmdd(today.plusDays(1)))).isFalse();
        // Null / malformed input is treated as invalid (mirrors the COBOL blank/non-numeric edits).
        assertThat(DateUtils.isDateOfBirthValid((String) null)).isFalse();
        assertThat(DateUtils.isDateOfBirthValid("notadate")).isFalse();
        assertThat(DateUtils.isDateOfBirthValid("20240230")).as("impossible date is invalid").isFalse();
    }

    // ------------------------------------------------------------------------
    // Phase 8 - Formatting (masks from CSDAT01Y.cpy; fixtures = version stamp 2022-07-19 23:15:58)
    // ------------------------------------------------------------------------

    @Test
    void formattingMatchesCobolMasks() {
        assertThat(DateUtils.formatDateMmDdYy(LocalDate.of(2022, 7, 19))).isEqualTo("07/19/22");
        assertThat(DateUtils.formatTimeHhMmSs(LocalTime.of(23, 15, 58))).isEqualTo("23:15:58");
        assertThat(DateUtils.formatTimestamp(LocalDateTime.of(2022, 7, 19, 23, 15, 58, 0)))
                .as("timestamp mask always renders six fractional digits, even when nanos are zero")
                .isEqualTo("2022-07-19 23:15:58.000000");
    }

    @Test
    void formattingRejectsNull() {
        assertThatThrownBy(() -> DateUtils.formatDateMmDdYy(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DateUtils.formatTimeHhMmSs(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DateUtils.formatTimestamp(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------------
    // Additional public-API coverage: parseCcyyMmDd + toEpochDay
    // (EDIT-DATE-LE parse outcome and FUNCTION INTEGER-OF-DATE semantics)
    // ------------------------------------------------------------------------

    @Test
    void parseCcyyMmDdReturnsDateOrThrows() {
        assertThat(DateUtils.parseCcyyMmDd("20240229")).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(DateUtils.parseCcyyMmDd("19991231")).isEqualTo(LocalDate.of(1999, 12, 31));
        // Impossible calendar date -> DateTimeParseException (mapped to the COBOL invalid outcome).
        assertThatThrownBy(() -> DateUtils.parseCcyyMmDd("20230229"))
                .isInstanceOf(DateTimeParseException.class);
        // Wrong length / non-numeric -> DateTimeParseException (numeric/length edits).
        assertThatThrownBy(() -> DateUtils.parseCcyyMmDd("2024"))
                .isInstanceOf(DateTimeParseException.class);
        assertThatThrownBy(() -> DateUtils.parseCcyyMmDd("2024022x"))
                .isInstanceOf(DateTimeParseException.class);
        // Null is a programming error -> NullPointerException.
        assertThatThrownBy(() -> DateUtils.parseCcyyMmDd(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toEpochDayMatchesIntegerOfDateSemantics() {
        assertThat(DateUtils.toEpochDay(LocalDate.of(1970, 1, 1))).isZero();
        assertThat(DateUtils.toEpochDay(LocalDate.of(1970, 1, 2))).isEqualTo(1L);
        // Only the difference is meaningful, exactly as in the COBOL binary-day comparison.
        long span = DateUtils.toEpochDay(LocalDate.of(2024, 1, 1))
                - DateUtils.toEpochDay(LocalDate.of(2023, 1, 1));
        assertThat(span).as("2023 is a common year (365 days)").isEqualTo(365L);
    }

    // ------------------------------------------------------------------------
    // Test-local helpers
    // ------------------------------------------------------------------------

    /**
     * Composes the COBOL {@code EDIT-DATE-CCYYMMDD} contract (the 19/20 century gate followed by
     * the strict calendar parse) using only the guaranteed primitives, so it compiles regardless
     * of any optional production convenience methods.
     *
     * @param s the candidate {@code CCYYMMDD} string; may be {@code null}
     * @return {@code true} if {@code s} is eight digits, a real calendar date, and a 19/20 century
     */
    private static boolean cobolValidDate(String s) {
        if (s == null || s.length() != 8 || !s.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int cc = Integer.parseInt(s.substring(0, 2));
        return DateUtils.isValidCentury(cc) && DateUtils.isValidCcyyMmDd(s);
    }

    /**
     * Renders a date as an eight-digit {@code CCYYMMDD} string for the string-overload tests.
     *
     * @param date the date to render
     * @return the {@code CCYYMMDD} representation
     */
    private static String ccyymmdd(LocalDate date) {
        return String.format("%04d%02d%02d",
                date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }
}
