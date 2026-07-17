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
package com.aws.carddemo.service.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DateRangeRule}, verifying verbatim parity with the inline report
 * date-part edits of {@code legacy/cbl/CORPT00C.cbl} (lines {@code L329}&ndash;{@code L379}).
 *
 * <p>Each assertion pins the exact COBOL screen message &mdash; including the {@code " - "}
 * separator and the trailing three dots ({@code "..."}) &mdash; and exercises the parity-critical
 * behaviors: the inclusive month ({@code <= 12}) and day ({@code <= 31}) bounds, the "{@code 00}
 * passes the quick range check" rule, the numeric-only year check, and the month&ndash;day&ndash;year
 * "first message wins" ordering of {@link DateRangeRule#validateDateParts(String, String, String, String)}.</p>
 *
 * <p>{@link DateRangeRule} is a stateless plain component, so it is exercised directly (no Spring
 * context is required), matching the style of the sibling rule tests in this package.</p>
 */
class DateRangeRuleTest {

    private final DateRangeRule rule = new DateRangeRule();

    @Test
    @DisplayName("(a) validateMonth(\"Start Date\",\"12\") -> valid (12 is the inclusive upper bound)")
    void validateMonth_upperBoundIsValid() {
        assertTrue(rule.validateMonth("Start Date", "12").isValid());
    }

    @Test
    @DisplayName("(b) validateMonth(\"Start Date\",\"13\") -> \"Start Date - Not a valid Month...\"")
    void validateMonth_aboveBoundIsInvalid() {
        ValidationResult result = rule.validateMonth("Start Date", "13");
        assertTrue(result.isInvalid());
        assertEquals("Start Date - Not a valid Month...", result.message());
    }

    @Test
    @DisplayName("(c) validateMonth(\"Start Date\",\"00\") -> valid (00 passes the range check per COBOL parity)")
    void validateMonth_zeroZeroPassesRange() {
        assertTrue(rule.validateMonth("Start Date", "00").isValid());
    }

    @Test
    @DisplayName("(d) validateMonth(\"Start Date\",\"1A\") -> \"Start Date - Not a valid Month...\" (not numeric)")
    void validateMonth_nonNumericIsInvalid() {
        ValidationResult result = rule.validateMonth("Start Date", "1A");
        assertTrue(result.isInvalid());
        assertEquals("Start Date - Not a valid Month...", result.message());
    }

    @Test
    @DisplayName("(e) validateDay(\"End Date\",\"31\") -> valid; validateDay(\"End Date\",\"32\") -> \"End Date - Not a valid Day...\"")
    void validateDay_boundary() {
        assertTrue(rule.validateDay("End Date", "31").isValid());

        ValidationResult result = rule.validateDay("End Date", "32");
        assertTrue(result.isInvalid());
        assertEquals("End Date - Not a valid Day...", result.message());
    }

    @Test
    @DisplayName("(f) validateYear(\"Start Date\",\"2024\") -> valid; validateYear(\"Start Date\",\"20X4\") -> \"Start Date - Not a valid Year...\"")
    void validateYear_numericOnly() {
        assertTrue(rule.validateYear("Start Date", "2024").isValid());

        ValidationResult result = rule.validateYear("Start Date", "20X4");
        assertTrue(result.isInvalid());
        assertEquals("Start Date - Not a valid Year...", result.message());
    }

    @Test
    @DisplayName("(g) validateDateParts(\"End Date\",\"13\",\"05\",\"2024\") -> month failure surfaces first")
    void validateDateParts_firstFailureWins() {
        ValidationResult result = rule.validateDateParts("End Date", "13", "05", "2024");
        assertTrue(result.isInvalid());
        assertEquals("End Date - Not a valid Month...", result.message());
    }

    @Test
    @DisplayName("validateDay(\"Start Date\",\"00\") -> valid (00 passes the day range per parity)")
    void validateDay_zeroZeroPassesRange() {
        assertTrue(rule.validateDay("Start Date", "00").isValid());
    }

    @Test
    @DisplayName("validateDateParts surfaces the day failure only after a valid month")
    void validateDateParts_dayFailureAfterValidMonth() {
        ValidationResult result = rule.validateDateParts("Start Date", "12", "32", "2024");
        assertTrue(result.isInvalid());
        assertEquals("Start Date - Not a valid Day...", result.message());
    }

    @Test
    @DisplayName("validateDateParts surfaces the year failure only after a valid month and day")
    void validateDateParts_yearFailureAfterValidMonthAndDay() {
        ValidationResult result = rule.validateDateParts("End Date", "12", "31", "20X4");
        assertTrue(result.isInvalid());
        assertEquals("End Date - Not a valid Year...", result.message());
    }

    @Test
    @DisplayName("validateDateParts with all valid parts -> valid")
    void validateDateParts_allValid() {
        assertTrue(rule.validateDateParts("Start Date", "01", "15", "2024").isValid());
    }

    @Test
    @DisplayName("null parts are invalid (COBOL LOW-VALUES / not numeric)")
    void nullParts_areInvalid() {
        assertTrue(rule.validateMonth("Start Date", null).isInvalid());
        assertTrue(rule.validateDay("End Date", null).isInvalid());
        assertTrue(rule.validateYear("Start Date", null).isInvalid());
    }

    @Test
    @DisplayName("a value wider than the two-character month/day field is invalid (PIC X(2) width guard)")
    void overWidthMonthDay_areInvalid() {
        ValidationResult month = rule.validateMonth("Start Date", "013");
        assertTrue(month.isInvalid());
        assertEquals("Start Date - Not a valid Month...", month.message());

        ValidationResult day = rule.validateDay("End Date", "031");
        assertTrue(day.isInvalid());
        assertEquals("End Date - Not a valid Day...", day.message());
    }

    @Test
    @DisplayName("whitespace-padded numeric parts are stripped before evaluation (NUMVAL-C-normalized fields)")
    void paddedNumericParts_areStripped() {
        assertTrue(rule.validateMonth("Start Date", " 12 ").isValid());
        assertTrue(rule.validateDay("End Date", " 31 ").isValid());
        assertTrue(rule.validateYear("Start Date", " 2024 ").isValid());
    }
}
