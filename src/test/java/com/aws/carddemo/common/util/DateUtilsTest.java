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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DateUtils}, asserting behavioral parity with the COBOL date copybooks
 * ({@code legacy/cpy/CSDAT01Y.cpy}, {@code legacy/cpy/CSUTLDWY.cpy}, {@code legacy/cpy/CSUTLDPY.cpy}).
 * The boundary cases exercised here were verified against the proleptic-Gregorian calendar used by
 * {@code java.time}.
 */
class DateUtilsTest {

    // Calendar-valid dates that also satisfy the 19/20 century restriction.
    private static final String[] VALID_CENTURY_GATED = {
            "20240229", "20000229", "20240131", "19991231",
            "20000101", "20991231", "19960229"
    };

    // Impossible calendar dates: rejected by the strict parse regardless of century.
    private static final String[] CALENDAR_INVALID = {
            "20230229", // 2023 not a leap year
            "19000229", // 1900 not a leap year (century year not divisible by 400)
            "21000229", // 2100 not a leap year
            "20240230", // February cannot have 30 days
            "20240431", // April cannot have 31 days
            "20241301", // month 13
            "20240001", // month 00
            "20240100"  // day 00
    };

    @Test
    void validCalendarDatesAreAcceptedAndCenturyGated() {
        for (String s : VALID_CENTURY_GATED) {
            Assertions.assertTrue(DateUtils.isValidCcyyMmDd(s), "isValidCcyyMmDd should accept " + s);
            Assertions.assertTrue(DateUtils.isValidCcyyMmDdWithCenturyRule(s),
                    "century-gated validity should accept " + s);
        }
    }

    @Test
    void calendarInvalidDatesAreRejected() {
        for (String s : CALENDAR_INVALID) {
            Assertions.assertFalse(DateUtils.isValidCcyyMmDd(s), "isValidCcyyMmDd should reject " + s);
            Assertions.assertFalse(DateUtils.isValidCcyyMmDdWithCenturyRule(s),
                    "century-gated validity should reject " + s);
        }
    }

    @Test
    void centuryRuleRejectsCenturiesOtherThan19And20() {
        // 1899-12-31 and 2100-01-01 are real calendar dates, so the plain calendar check accepts
        // them, but the COBOL EDIT-YEAR-CCYY century gate (19/20 only) rejects them.
        Assertions.assertTrue(DateUtils.isValidCcyyMmDd("18991231"));
        Assertions.assertFalse(DateUtils.isValidCcyyMmDdWithCenturyRule("18991231"));

        Assertions.assertTrue(DateUtils.isValidCcyyMmDd("21000101"));
        Assertions.assertFalse(DateUtils.isValidCcyyMmDdWithCenturyRule("21000101"));
    }

    @Test
    void leapYearFollowsCobolDivisorRule() {
        Assertions.assertTrue(DateUtils.isLeapYear(2000));  // century year divisible by 400
        Assertions.assertFalse(DateUtils.isLeapYear(1900)); // century year not divisible by 400
        Assertions.assertFalse(DateUtils.isLeapYear(2100)); // century year not divisible by 400
        Assertions.assertTrue(DateUtils.isLeapYear(2024));  // divisible by 4
        Assertions.assertFalse(DateUtils.isLeapYear(2023)); // not divisible by 4
        Assertions.assertTrue(DateUtils.isLeapYear(1996));  // divisible by 4
    }

    @Test
    void componentPrimitivesMatchCobol88Levels() {
        // Century: only 19 and 20.
        Assertions.assertTrue(DateUtils.isValidCentury(19));
        Assertions.assertTrue(DateUtils.isValidCentury(20));
        Assertions.assertFalse(DateUtils.isValidCentury(18));
        Assertions.assertFalse(DateUtils.isValidCentury(21));

        // Month: 1..12 valid, 0 and 13 invalid.
        for (int m = 1; m <= 12; m++) {
            Assertions.assertTrue(DateUtils.isValidMonth(m), "month " + m + " should be valid");
        }
        Assertions.assertFalse(DateUtils.isValidMonth(0));
        Assertions.assertFalse(DateUtils.isValidMonth(13));

        // 31-day months.
        for (int m : new int[] {1, 3, 5, 7, 8, 10, 12}) {
            Assertions.assertTrue(DateUtils.isThirtyOneDayMonth(m), "month " + m + " has 31 days");
        }
        for (int m : new int[] {2, 4, 6, 9, 11}) {
            Assertions.assertFalse(DateUtils.isThirtyOneDayMonth(m), "month " + m + " lacks 31 days");
        }

        // February and day-range primitives.
        Assertions.assertTrue(DateUtils.isFebruary(2));
        Assertions.assertFalse(DateUtils.isFebruary(3));
        Assertions.assertTrue(DateUtils.isValidDay(1));
        Assertions.assertTrue(DateUtils.isValidDay(31));
        Assertions.assertFalse(DateUtils.isValidDay(0));
        Assertions.assertFalse(DateUtils.isValidDay(32));
    }

    @Test
    void dayForMonthCombinationChecks() {
        // Check 1: non-31-day month cannot have day 31.
        Assertions.assertFalse(DateUtils.isValidDayForMonth(2024, 4, 31));
        Assertions.assertFalse(DateUtils.isValidDayForMonth(2024, 2, 31));
        // Check 2: February cannot have day 30.
        Assertions.assertFalse(DateUtils.isValidDayForMonth(2024, 2, 30));
        // Check 3: February 29 only in a leap year.
        Assertions.assertFalse(DateUtils.isValidDayForMonth(2023, 2, 29));
        Assertions.assertTrue(DateUtils.isValidDayForMonth(2024, 2, 29));
        // Admissible combinations.
        Assertions.assertTrue(DateUtils.isValidDayForMonth(2024, 1, 31));
        Assertions.assertTrue(DateUtils.isValidDayForMonth(2023, 4, 30));
        Assertions.assertTrue(DateUtils.isValidDayForMonth(2023, 2, 28));
    }

    @Test
    void dateOfBirthMustBeStrictlyBeforeToday() {
        LocalDate today = LocalDate.now();
        Assertions.assertTrue(DateUtils.isDateOfBirthValid(today.minusDays(1)),
                "a past date of birth is valid");
        Assertions.assertFalse(DateUtils.isDateOfBirthValid(today),
                "a date of birth equal to today must be INVALID (strictly-before-today parity)");
        Assertions.assertFalse(DateUtils.isDateOfBirthValid(today.plusDays(1)),
                "a future date of birth is invalid");
    }

    @Test
    void dateOfBirthStringOverload() {
        LocalDate today = LocalDate.now();
        Assertions.assertTrue(DateUtils.isDateOfBirthValid(ccyymmdd(today.minusDays(1))));
        Assertions.assertFalse(DateUtils.isDateOfBirthValid(ccyymmdd(today)));
        Assertions.assertFalse(DateUtils.isDateOfBirthValid(ccyymmdd(today.plusDays(1))));
        // Null / malformed input is treated as invalid (mirrors the COBOL blank/non-numeric edits).
        Assertions.assertFalse(DateUtils.isDateOfBirthValid((String) null));
        Assertions.assertFalse(DateUtils.isDateOfBirthValid("notadate"));
        Assertions.assertFalse(DateUtils.isDateOfBirthValid("20240230")); // impossible date
    }

    @Test
    void formattingMatchesCobolMasks() {
        Assertions.assertEquals("07/19/22",
                DateUtils.formatDateMmDdYy(LocalDate.of(2022, 7, 19)));
        Assertions.assertEquals("23:15:58",
                DateUtils.formatTimeHhMmSs(LocalTime.of(23, 15, 58)));
        Assertions.assertEquals("2022-07-19 23:15:58.000000",
                DateUtils.formatTimestamp(LocalDateTime.of(2022, 7, 19, 23, 15, 58, 0)));
    }

    @Test
    void parseCcyyMmDdReturnsDateOrThrows() {
        Assertions.assertEquals(LocalDate.of(2024, 2, 29), DateUtils.parseCcyyMmDd("20240229"));
        Assertions.assertEquals(LocalDate.of(1999, 12, 31), DateUtils.parseCcyyMmDd("19991231"));
        // Impossible calendar date -> DateTimeParseException (mapped to the COBOL invalid outcome).
        Assertions.assertThrows(DateTimeParseException.class,
                () -> DateUtils.parseCcyyMmDd("20230229"));
        // Wrong length / non-numeric -> DateTimeParseException (numeric/length edits).
        Assertions.assertThrows(DateTimeParseException.class,
                () -> DateUtils.parseCcyyMmDd("2024"));
        Assertions.assertThrows(DateTimeParseException.class,
                () -> DateUtils.parseCcyyMmDd("2024022x"));
        // Null is a programming error -> NullPointerException.
        Assertions.assertThrows(NullPointerException.class,
                () -> DateUtils.parseCcyyMmDd(null));
    }

    @Test
    void toEpochDayMatchesIntegerOfDateSemantics() {
        Assertions.assertEquals(0L, DateUtils.toEpochDay(LocalDate.of(1970, 1, 1)));
        Assertions.assertEquals(1L, DateUtils.toEpochDay(LocalDate.of(1970, 1, 2)));
        // Only the difference is meaningful, exactly as in the COBOL binary-day comparison.
        long span = DateUtils.toEpochDay(LocalDate.of(2024, 1, 1))
                - DateUtils.toEpochDay(LocalDate.of(2023, 1, 1));
        Assertions.assertEquals(365L, span);
    }

    @Test
    void nullAndMalformedStringInputIsRejected() {
        Assertions.assertFalse(DateUtils.isValidCcyyMmDd(null));
        Assertions.assertFalse(DateUtils.isValidCcyyMmDd(""));
        Assertions.assertFalse(DateUtils.isValidCcyyMmDd("2024022"));   // 7 chars
        Assertions.assertFalse(DateUtils.isValidCcyyMmDd("2024022x"));  // non-numeric
        Assertions.assertFalse(DateUtils.isValidCcyyMmDdWithCenturyRule(null));
        // Formatting helpers reject null with NullPointerException.
        Assertions.assertThrows(NullPointerException.class,
                () -> DateUtils.formatDateMmDdYy(null));
        Assertions.assertThrows(NullPointerException.class,
                () -> DateUtils.formatTimeHhMmSs(null));
        Assertions.assertThrows(NullPointerException.class,
                () -> DateUtils.formatTimestamp(null));
    }

    @Test
    void currentHelpersProduceWellFormedOutput() {
        // The "current" helpers read the system clock; assert the shape rather than a fixed value.
        Assertions.assertTrue(DateUtils.currentDateMmDdYy().matches("\\d{2}/\\d{2}/\\d{2}"));
        Assertions.assertTrue(DateUtils.currentTimeHhMmSs().matches("\\d{2}:\\d{2}:\\d{2}"));
        Assertions.assertTrue(DateUtils.currentTimestamp()
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}"));
    }

    /**
     * Formats a date as an eight-digit {@code CCYYMMDD} string for the string-overload tests.
     *
     * @param date the date to render
     * @return the {@code CCYYMMDD} representation
     */
    private static String ccyymmdd(LocalDate date) {
        return String.format("%04d%02d%02d",
                date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }
}
