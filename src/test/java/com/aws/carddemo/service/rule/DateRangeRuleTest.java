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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DateRangeRule}, verifying verbatim behavioral parity with the inline
 * report date-part "range" edits of the transaction-report request program
 * {@code legacy/cbl/CORPT00C.cbl} (source-branch {@code app/cbl/CORPT00C.cbl}, lines
 * {@code L329}&ndash;{@code L379}).
 *
 * <p>The legacy program collects a Start Date and an End Date, each split into a month field
 * ({@code PIC X(2)}), a day field ({@code PIC X(2)}) and a year field ({@code PIC X(4)}), and
 * performs a quick per-part sanity check before the calendar-aware validation runs:</p>
 * <pre>
 *     IF SDTMMI   IS NOT NUMERIC OR SDTMMI   &gt; '12'  -&gt; 'Start Date - Not a valid Month...'
 *     IF SDTDDI   IS NOT NUMERIC OR SDTDDI   &gt; '31'  -&gt; 'Start Date - Not a valid Day...'
 *     IF SDTYYYYI IS NOT NUMERIC                     -&gt; 'Start Date - Not a valid Year...'
 *     IF EDTMMI   IS NOT NUMERIC OR EDTMMI   &gt; '12'  -&gt; 'End Date - Not a valid Month...'
 *     IF EDTDDI   IS NOT NUMERIC OR EDTDDI   &gt; '31'  -&gt; 'End Date - Not a valid Day...'
 *     IF EDTYYYYI IS NOT NUMERIC                     -&gt; 'End Date - Not a valid Year...'
 * </pre>
 *
 * <p>Each assertion pins the exact COBOL screen message &mdash; including the {@code " - "}
 * (space-hyphen-space) separator and the trailing three dots ({@code "..."}) &mdash; and exercises
 * the parity-critical behaviors: the inclusive month ({@code <= 12}) and day ({@code <= 31})
 * bounds, the "{@code 00} passes the quick range check" rule, the numeric-only year check (no
 * range component), and the month&ndash;day&ndash;year "first message wins" ordering of
 * {@link DateRangeRule#validateDateParts(String, String, String, String)}.</p>
 *
 * <p>{@link DateRangeRule} is a stateless, side-effect-free component, so it is exercised directly
 * via {@code new DateRangeRule()} &mdash; no Spring context, no Mockito, and no database &mdash;
 * matching the style of the sibling rule tests in this package. This is a pure JUnit&nbsp;5 +
 * AssertJ unit test.</p>
 */
class DateRangeRuleTest {

    /** The stateless rule under test; safe to share across every test method. */
    private final DateRangeRule rule = new DateRangeRule();

    /**
     * (a) A month equal to the inclusive upper bound {@code "12"} passes the quick range check
     * ({@code SDTMMI > '12'} is false), reproducing {@code legacy/cbl/CORPT00C.cbl:L329}.
     */
    @Test
    void validateMonth_upperBound12_isValid() {
        assertThat(rule.validateMonth("Start Date", "12").isValid()).isTrue();
    }

    /**
     * (b) A month of {@code "13"} is greater than {@code '12'} and therefore fails with the
     * verbatim {@code 'Start Date - Not a valid Month...'} message
     * ({@code legacy/cbl/CORPT00C.cbl:L330}&ndash;{@code L332}).
     */
    @Test
    void validateMonth_above12_isInvalidWithMonthMessage() {
        ValidationResult result = rule.validateMonth("Start Date", "13");
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Start Date - Not a valid Month...");
    }

    /**
     * (c) A month of {@code "00"} <em>passes</em> the quick range check because the COBOL test is
     * only "not numeric OR greater than 12"; {@code "00"} is numeric and {@code <= 12}. It is the
     * later full-date validation (a separate concern) that rejects a zero month, so passing it
     * here is the faithful, parity-preserving behavior.
     */
    @Test
    void validateMonth_zeroZero_passesRange() {
        assertThat(rule.validateMonth("Start Date", "00").isValid()).isTrue();
    }

    /**
     * (d) A non-numeric month {@code "1A"} fails the {@code IS NOT NUMERIC} class test and yields
     * the same verbatim {@code 'Start Date - Not a valid Month...'} message.
     */
    @Test
    void validateMonth_nonNumeric_isInvalidWithMonthMessage() {
        ValidationResult result = rule.validateMonth("Start Date", "1A");
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Start Date - Not a valid Month...");
    }

    /**
     * (e) The day boundary: {@code "31"} passes ({@code EDTDDI > '31'} is false) while {@code "32"}
     * fails with the verbatim {@code 'End Date - Not a valid Day...'} message
     * ({@code legacy/cbl/CORPT00C.cbl:L364}&ndash;{@code L367}).
     */
    @Test
    void validateDay_boundary31Valid_32Invalid() {
        assertThat(rule.validateDay("End Date", "31").isValid()).isTrue();

        ValidationResult result = rule.validateDay("End Date", "32");
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("End Date - Not a valid Day...");
    }

    /**
     * (f) The year check is numeric-only (no range component): {@code "2024"} passes while the
     * non-numeric {@code "20X4"} fails with the verbatim {@code 'Start Date - Not a valid Year...'}
     * message ({@code legacy/cbl/CORPT00C.cbl:L347}&ndash;{@code L349}).
     */
    @Test
    void validateYear_numericValid_nonNumericInvalid() {
        assertThat(rule.validateYear("Start Date", "2024").isValid()).isTrue();

        ValidationResult result = rule.validateYear("Start Date", "20X4");
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Start Date - Not a valid Year...");
    }

    /**
     * (g) {@link DateRangeRule#validateDateParts(String, String, String, String)} evaluates the
     * parts in month&ndash;day&ndash;year order and returns the first failure. With an invalid
     * month ({@code "13"}) but a valid day and year, the month failure surfaces first &mdash; the
     * day and year are never reached &mdash; so the message is the verbatim
     * {@code 'End Date - Not a valid Month...'}.
     */
    @Test
    void validateDateParts_monthFailsFirst() {
        ValidationResult result = rule.validateDateParts("End Date", "13", "05", "2024");
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("End Date - Not a valid Month...");
    }

    /**
     * (h) Trailing-dots / separator lock: every failure message ends with the three-dot ellipsis
     * ({@code "..."}) and contains the {@code " - Not a valid "} fragment, pinning the exact
     * spacing and punctuation of the COBOL literals so that an accidental edit (a missing dot or a
     * changed separator) is caught.
     */
    @Test
    void messages_endWithEllipsisAndContainSeparator() {
        assertThat(rule.validateMonth("Start Date", "13").message())
                .endsWith("...")
                .contains(" - Not a valid ");
        assertThat(rule.validateDay("End Date", "32").message())
                .endsWith("...")
                .contains(" - Not a valid ");
        assertThat(rule.validateYear("Start Date", "20X4").message())
                .endsWith("...")
                .contains(" - Not a valid ");
    }

    /**
     * A day of {@code "00"} passes the quick day range check for the same reason a zero month does:
     * the COBOL test is only "not numeric OR greater than 31", and {@code "00"} is numeric and
     * {@code <= 31}. The later full-date validation rejects it.
     */
    @Test
    void validateDay_zeroZero_passesRange() {
        assertThat(rule.validateDay("Start Date", "00").isValid()).isTrue();
    }

    /**
     * When the month is valid but the day is invalid, {@code validateDateParts} surfaces the day
     * failure (the month having passed), returning the verbatim
     * {@code 'Start Date - Not a valid Day...'} message.
     */
    @Test
    void validateDateParts_dayFailsAfterValidMonth() {
        ValidationResult result = rule.validateDateParts("Start Date", "12", "32", "2024");
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Start Date - Not a valid Day...");
    }

    /**
     * When the month and day are both valid but the year is non-numeric, {@code validateDateParts}
     * surfaces the year failure last, returning the verbatim
     * {@code 'End Date - Not a valid Year...'} message.
     */
    @Test
    void validateDateParts_yearFailsAfterValidMonthAndDay() {
        ValidationResult result = rule.validateDateParts("End Date", "12", "31", "20X4");
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("End Date - Not a valid Year...");
    }

    /**
     * When every part is within range and numeric, {@code validateDateParts} reports a valid
     * outcome (the quick range check raises no error and control passes to the full-date
     * validation).
     */
    @Test
    void validateDateParts_allPartsValid_isValid() {
        assertThat(rule.validateDateParts("Start Date", "01", "15", "2024").isValid()).isTrue();
    }

    /**
     * A {@code null} part (the COBOL {@code LOW-VALUES} / uninitialized state) is not numeric and
     * therefore fails every part check, for both the month/day range guard and the numeric-only
     * year check.
     */
    @Test
    void nullParts_areInvalid() {
        assertThat(rule.validateMonth("Start Date", null).isInvalid()).isTrue();
        assertThat(rule.validateDay("End Date", null).isInvalid()).isTrue();
        assertThat(rule.validateYear("Start Date", null).isInvalid()).isTrue();
    }

    /**
     * A value wider than the two-character {@code PIC X(2)} month/day field (for example
     * {@code "013"} or {@code "031"}) is rejected by the field-width guard, so it never reaches the
     * numeric parse and fails with the corresponding verbatim Month / Day message.
     */
    @Test
    void overWidthMonthDay_areInvalid() {
        ValidationResult month = rule.validateMonth("Start Date", "013");
        assertThat(month.isInvalid()).isTrue();
        assertThat(month.message()).isEqualTo("Start Date - Not a valid Month...");

        ValidationResult day = rule.validateDay("End Date", "031");
        assertThat(day.isInvalid()).isTrue();
        assertThat(day.message()).isEqualTo("End Date - Not a valid Day...");
    }

    /**
     * Whitespace-padded numeric parts are stripped before evaluation, matching the fixed-width,
     * {@code FUNCTION NUMVAL-C}-normalized fields the legacy program feeds these checks; the
     * stripped values {@code "12"}, {@code "31"} and {@code "2024"} all pass.
     */
    @Test
    void whitespacePaddedParts_areStrippedAndValid() {
        assertThat(rule.validateMonth("Start Date", " 12 ").isValid()).isTrue();
        assertThat(rule.validateDay("End Date", " 31 ").isValid()).isTrue();
        assertThat(rule.validateYear("Start Date", " 2024 ").isValid()).isTrue();
    }
}
