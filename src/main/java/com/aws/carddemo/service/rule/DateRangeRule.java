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

import org.springframework.stereotype.Component;

/**
 * Reproduces the inline report date-part "range" edits of the transaction-report request
 * program {@code legacy/cbl/CORPT00C.cbl} (lines {@code L329}&ndash;{@code L379}). The legacy
 * program collects a Start Date and an End Date, each split into a month field
 * ({@code PIC X(2)}), a day field ({@code PIC X(2)}) and a year field ({@code PIC X(4)}), and
 * performs a quick per-part sanity check before any calendar-aware validation runs:
 *
 * <pre>
 *     IF SDTMMI   IS NOT NUMERIC OR SDTMMI   &gt; '12'  -&gt; 'Start Date - Not a valid Month...'
 *     IF SDTDDI   IS NOT NUMERIC OR SDTDDI   &gt; '31'  -&gt; 'Start Date - Not a valid Day...'
 *     IF SDTYYYYI IS NOT NUMERIC                     -&gt; 'Start Date - Not a valid Year...'
 *     IF EDTMMI   IS NOT NUMERIC OR EDTMMI   &gt; '12'  -&gt; 'End Date - Not a valid Month...'
 *     IF EDTDDI   IS NOT NUMERIC OR EDTDDI   &gt; '31'  -&gt; 'End Date - Not a valid Day...'
 *     IF EDTYYYYI IS NOT NUMERIC                     -&gt; 'End Date - Not a valid Year...'
 * </pre>
 *
 * <p><strong>Scope.</strong> This component intentionally reproduces <em>only</em> those quick
 * per-part range checks (month {@code <= 12}, day {@code <= 31}, year numeric). The full
 * calendar-date validation &mdash; leap years, days-per-month, the {@code CEEDAYS} intrinsic
 * &mdash; is a separate concern handled by {@code DateValidationService} (parent
 * {@code service/} package), which the legacy program performs afterwards by calling
 * {@code CSUTLDTC} (see {@code legacy/cbl/CORPT00C.cbl:L392}). This rule is therefore
 * deliberately <em>self-contained</em>: it does not depend on {@code common/util/DateUtils}.</p>
 *
 * <p><strong>Parity note on {@code "00"}.</strong> Because the legacy month test is only
 * "not numeric OR greater than 12", a month of {@code "00"} <em>passes</em> this rule (it is
 * {@code <= 12}); the same holds for a day of {@code "00"}. That value is subsequently rejected
 * by the full-date validation, so passing it here is the correct, faithful behavior and must be
 * preserved exactly. Likewise the year test has no range component at all &mdash; only a
 * numeric-class test &mdash; so any all-digit year passes this rule.</p>
 *
 * <p><strong>Upstream normalization.</strong> Before these checks run, {@code CORPT00C}
 * normalizes each part with {@code FUNCTION NUMVAL-C} and moves the numeric result back into the
 * fixed-width display field (see {@code legacy/cbl/CORPT00C.cbl:L305}&ndash;{@code L327}), which
 * zero-pads it. For a two-character, zero-padded numeric field the COBOL alphanumeric comparison
 * {@code > '12'} is exactly equivalent to the numeric comparison {@code > 12}; this class
 * reproduces that equivalence with {@link Integer#parseInt(String)} guarded by an all-digit
 * class test and the two-character field width.</p>
 *
 * <p>The methods differ from the single-field {@link ValidationRule} contract because a date is
 * a composite of several sub-fields with their own messages; consequently this class does not
 * implement {@link ValidationRule} but reuses its shared {@link ValidationRule#isAllDigits(String)}
 * primitive (the {@code IS NUMERIC} class test). Every method returns a {@link ValidationResult}
 * and never throws for a validation failure. The typical caller ({@code ReportService}) invokes
 * these in the legacy order &mdash; Start month, day, year, then End month, day, year &mdash; and
 * latches the first failure ("first message wins"), then delegates to the full-date validation.</p>
 *
 * <p>Instances are stateless (no fields), side-effect free, and therefore safe to share as a
 * singleton Spring bean.</p>
 */
@Component
public final class DateRangeRule {

    /** Fixed width, in characters, of the report month and day fields ({@code PIC X(2)}). */
    private static final int MONTH_DAY_FIELD_WIDTH = 2;

    /** Inclusive upper bound for the month part, matching the COBOL {@code > '12'} test. */
    private static final int MAX_MONTH = 12;

    /** Inclusive upper bound for the day part, matching the COBOL {@code > '31'} test. */
    private static final int MAX_DAY = 31;

    /**
     * Validates a report month part, reproducing
     * {@code IF SDTMMI/EDTMMI IS NOT NUMERIC OR ... > '12'} at
     * {@code legacy/cbl/CORPT00C.cbl:L329,L355}. The month passes when it is a numeric value
     * from {@code 00} through {@code 12} inclusive; a non-numeric value or a value greater than
     * {@code 12} fails. A month of {@code "00"} passes here by design (the full-date validation
     * rejects it later).
     *
     * @param dateLabel the date label supplied by the caller, either {@code "Start Date"} or
     *                  {@code "End Date"}, substituted verbatim into the failure message
     * @param month     the month part as entered (expected two-character, zero-padded, numeric);
     *                  may be {@code null}
     * @return {@link ValidationResult#valid()} when the month is numeric and {@code <= 12};
     *         otherwise {@link ValidationResult#invalid(String)} carrying
     *         {@code dateLabel + " - Not a valid Month..."}
     */
    public ValidationResult validateMonth(String dateLabel, String month) {
        if (!isNumericInRange(month, MAX_MONTH)) {
            return ValidationResult.invalid(dateLabel + " - Not a valid Month...");
        }
        return ValidationResult.valid();
    }

    /**
     * Validates a report day part, reproducing
     * {@code IF SDTDDI/EDTDDI IS NOT NUMERIC OR ... > '31'} at
     * {@code legacy/cbl/CORPT00C.cbl:L338,L364}. The day passes when it is a numeric value from
     * {@code 00} through {@code 31} inclusive; a non-numeric value or a value greater than
     * {@code 31} fails. A day of {@code "00"} passes here by design (the full-date validation
     * rejects it later).
     *
     * @param dateLabel the date label supplied by the caller, either {@code "Start Date"} or
     *                  {@code "End Date"}, substituted verbatim into the failure message
     * @param day       the day part as entered (expected two-character, zero-padded, numeric);
     *                  may be {@code null}
     * @return {@link ValidationResult#valid()} when the day is numeric and {@code <= 31};
     *         otherwise {@link ValidationResult#invalid(String)} carrying
     *         {@code dateLabel + " - Not a valid Day..."}
     */
    public ValidationResult validateDay(String dateLabel, String day) {
        if (!isNumericInRange(day, MAX_DAY)) {
            return ValidationResult.invalid(dateLabel + " - Not a valid Day...");
        }
        return ValidationResult.valid();
    }

    /**
     * Validates a report year part, reproducing
     * {@code IF SDTYYYYI/EDTYYYYI IS NOT NUMERIC} at
     * {@code legacy/cbl/CORPT00C.cbl:L347,L373}. Unlike the month and day checks the year check
     * has <em>no</em> range component &mdash; it is purely the COBOL {@code IS NUMERIC} class
     * test &mdash; so any all-digit year passes and only a non-numeric (or {@code null}) year
     * fails. The value is {@link String#strip() stripped} first so that a zero-padded,
     * space-surrounded fixed-width slice is evaluated on its significant characters, matching the
     * shared {@link ValidationRule#isAllDigits(String)} primitive.
     *
     * @param dateLabel the date label supplied by the caller, either {@code "Start Date"} or
     *                  {@code "End Date"}, substituted verbatim into the failure message
     * @param year      the year part as entered (expected four-character, zero-padded, numeric);
     *                  may be {@code null}
     * @return {@link ValidationResult#valid()} when the year consists solely of ASCII digits;
     *         otherwise {@link ValidationResult#invalid(String)} carrying
     *         {@code dateLabel + " - Not a valid Year..."}
     */
    public ValidationResult validateYear(String dateLabel, String year) {
        if (year == null || !ValidationRule.isAllDigits(year.strip())) {
            return ValidationResult.invalid(dateLabel + " - Not a valid Year...");
        }
        return ValidationResult.valid();
    }

    /**
     * Convenience method that runs the three part checks in the exact order the legacy program
     * uses for a single date &mdash; month, then day, then year (see
     * {@code legacy/cbl/CORPT00C.cbl:L329}&ndash;{@code L353} for Start Date and
     * {@code L355}&ndash;{@code L379} for End Date) &mdash; and returns the first failure it
     * encounters, mirroring the "first message wins" latching performed by the caller.
     *
     * <p>Note that the legacy code evaluates each part in its own {@code IF} and re-sends the
     * screen on each failure; the caller-visible outcome, however, is the first message latched.
     * This method returns that first failing {@link ValidationResult}, or {@link ValidationResult#valid()}
     * when all three parts pass.</p>
     *
     * @param dateLabel the date label supplied by the caller, either {@code "Start Date"} or
     *                  {@code "End Date"}, substituted verbatim into every failure message
     * @param month     the month part as entered; may be {@code null}
     * @param day       the day part as entered; may be {@code null}
     * @param year      the year part as entered; may be {@code null}
     * @return the first failing {@link ValidationResult} in month&ndash;day&ndash;year order, or
     *         {@link ValidationResult#valid()} when every part passes
     */
    public ValidationResult validateDateParts(String dateLabel, String month, String day, String year) {
        ValidationResult monthResult = validateMonth(dateLabel, month);
        if (monthResult.isInvalid()) {
            return monthResult;
        }
        ValidationResult dayResult = validateDay(dateLabel, day);
        if (dayResult.isInvalid()) {
            return dayResult;
        }
        return validateYear(dateLabel, year);
    }

    /**
     * Reproduces the COBOL "{@code IS NUMERIC ... OR > 'nn'}" range guard used by the month and
     * day checks. The value passes only when, after stripping surrounding whitespace, it consists
     * solely of ASCII digits (the {@link ValidationRule#isAllDigits(String)} class test), fits the
     * two-character report field width, and is numerically less than or equal to
     * {@code inclusiveMax}.
     *
     * <p>The {@code length() > MONTH_DAY_FIELD_WIDTH} guard reflects the fixed two-character width
     * of the {@code PIC X(2)} month/day fields and prevents any value wider than the field from
     * being parsed. Because a passing value is therefore at most two digits, {@link Integer#parseInt(String)}
     * cannot overflow. A value of {@code "00"} yields {@code 0}, which is {@code <= inclusiveMax},
     * so it passes &mdash; the intended parity behavior.</p>
     *
     * @param value        the field content to test; may be {@code null}
     * @param inclusiveMax the inclusive numeric upper bound ({@code 12} for month, {@code 31} for
     *                     day)
     * @return {@code true} when {@code value} is a one- or two-character all-ASCII-digit string
     *         whose numeric value is {@code <= inclusiveMax}; {@code false} otherwise
     */
    private boolean isNumericInRange(String value, int inclusiveMax) {
        if (value == null) {
            return false;
        }
        String v = value.strip();
        if (!ValidationRule.isAllDigits(v) || v.length() > MONTH_DAY_FIELD_WIDTH) {
            return false;
        }
        return Integer.parseInt(v) <= inclusiveMax;
    }
}
