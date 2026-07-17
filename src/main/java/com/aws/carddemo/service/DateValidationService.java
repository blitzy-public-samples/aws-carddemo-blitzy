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
package com.aws.carddemo.service;

import com.aws.carddemo.common.util.DateUtils;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Date-validation service &mdash; the Java re-platform of the CardDemo COBOL date
 * utility program {@code CSUTLDTC} (source {@code legacy/cbl/CSUTLDTC.cbl}, formerly
 * {@code app/cbl/CSUTLDTC.cbl}). It reproduces, with no feature expansion, the
 * caller-visible outcome of the legacy {@code CALL 'CSUTLDTC' USING LS-DATE,
 * LS-DATE-FORMAT, LS-RESULT} that every online program used to validate an entered
 * date.
 *
 * <h2>Legacy contract reproduced</h2>
 * {@code CSUTLDTC} takes three linkage items and returns a severity in
 * {@code RETURN-CODE}:
 * <ul>
 *   <li>{@code LS-DATE} &mdash; {@code PIC X(10)} &mdash; the candidate date string
 *       (for example {@code "2024-01-31"}); modeled by the {@code date} parameter.</li>
 *   <li>{@code LS-DATE-FORMAT} &mdash; {@code PIC X(10)} &mdash; the expected mask
 *       (for example {@code "YYYY-MM-DD"}); modeled by the {@code format} parameter.</li>
 *   <li>{@code LS-RESULT} &mdash; {@code PIC X(80)} &mdash; the human-readable result
 *       message; modeled by {@link DateValidationResult#message()}.</li>
 * </ul>
 * Internally the COBOL calls the Language Environment intrinsic {@code CEEDAYS}
 * ({@code legacy/cbl/CSUTLDTC.cbl:L116}) to convert the date to a Lilian day number
 * and inspects the LE {@code FEEDBACK-CODE}: it moves {@code SEVERITY OF FEEDBACK-CODE}
 * to {@code RETURN-CODE} and sets a 15-character {@code WS-RESULT} message from the
 * feedback condition (its {@code EVALUATE} at {@code L128-L149}).
 *
 * <h2>Acceptance rule (from the callers)</h2>
 * The legacy caller convention observed in {@code COTRN02C} and {@code CORPT00C} treats
 * a date as valid only when the returned <strong>severity is {@code '0000'}</strong>
 * <strong>and</strong> the LE <strong>message number is not {@code '2513'}</strong>
 * (message&nbsp;2513 is the {@code FC-INSUFFICIENT-DATA} case &mdash; a syntactically
 * incomplete value). This service reproduces that exact rule: a fully specified, real
 * calendar date yields {@link DateValidationResult#valid()} (severity {@code "0000"});
 * anything else &mdash; including the insufficient-data case, which here manifests as a
 * value that does not fill the mask &mdash; yields {@link DateValidationResult#invalid(String)}
 * with a non-zero severity, matching {@code severity != '0000'}.
 *
 * <h2>Delegation to {@link DateUtils}</h2>
 * The actual calendar arithmetic ({@code CEEDAYS} &rarr; {@code java.time}, per the
 * migration mapping) lives in the dependency-free helper
 * {@link com.aws.carddemo.common.util.DateUtils}. This service is a thin, well-documented
 * wrapper that composes {@link DateUtils#isValidCcyyMmDd(String)},
 * {@link DateUtils#isValidMonth(int)}, and {@link DateUtils#parseCcyyMmDd(String)}; it
 * does <em>not</em> re-implement leap-year or day-in-month logic. {@code DateUtils}
 * operates on the compact {@code CCYYMMDD} (eight-digit) form, so this service first
 * normalizes the hyphenated {@code YYYY-MM-DD} value its callers supply into that form
 * before delegating.
 *
 * <h2>Pseudo-conversational translation</h2>
 * In the legacy system every program that needed a date performed a static
 * {@code CALL 'CSUTLDTC'}. Per AAP &sect;0.5.3 and &sect;0.5.7 that static call becomes
 * this injected Spring bean, consumed via constructor injection by
 * {@code TransactionService}, {@code ReportService}, {@code AccountService}, and
 * {@code CardService}.
 *
 * <h2>Design constraints</h2>
 * The service is stateless and has <strong>no injected dependencies</strong>:
 * {@code DateUtils} is a pure {@code static} utility and is therefore called directly
 * rather than injected, so no explicit constructor is declared. All handling here is
 * calendar-only (no monetary values, hence no {@code BigDecimal}); input date strings
 * are never logged. The {@code DateValidationResult} nested type is a hand-written
 * {@code final} class rather than a {@code record} because a record component named
 * {@code valid} would generate an accessor {@code valid()} that collides with the
 * required static {@link DateValidationResult#valid()} factory (an illegal duplicate) &mdash;
 * the same reason the sibling {@code service.rule.ValidationResult} is a class.
 */
@Service
public class DateValidationService {

    /**
     * The only date mask exercised by the callers ({@code CSUTLDTC} via
     * {@code COTRN02C}/{@code CORPT00C}): {@code YYYY-MM-DD}, that is a four-digit year,
     * a two-digit month, and a two-digit day separated by hyphens (a {@code CCYYMMDD}
     * value with hyphens). Any other mask is rejected defensively rather than throwing.
     */
    private static final String FORMAT_YYYY_MM_DD = "YYYY-MM-DD";

    /**
     * Result message for a month outside {@code 1..12}. Exact COBOL {@code WS-RESULT}
     * literal from {@code legacy/cbl/CSUTLDTC.cbl:L140} ({@code FC-INVALID-MONTH}):
     * {@code 'Invalid month  '}. {@code WS-RESULT} is {@code PIC X(15)}, so the two
     * trailing spaces are significant and are preserved verbatim for behavioral parity.
     */
    private static final String MSG_INVALID_MONTH = "Invalid month  ";

    /**
     * Result message for any other rejected value. Exact COBOL {@code WS-RESULT} literal
     * from {@code legacy/cbl/CSUTLDTC.cbl:L148} (the {@code WHEN OTHER} branch):
     * {@code 'Date is invalid'} (15 characters). Also used for {@code null}/blank input,
     * mirroring the legacy "not a valid date" outcome.
     */
    private static final String MSG_INVALID_DATE = "Date is invalid";

    /** Fixed length of a {@code YYYY-MM-DD} value ({@code LS-DATE} {@code PIC X(10)}). */
    private static final int HYPHENATED_LENGTH = 10;

    /** Index of the hyphen between the year and the month in {@code YYYY-MM-DD}. */
    private static final int HYPHEN_INDEX_YEAR_MONTH = 4;

    /** Index of the hyphen between the month and the day in {@code YYYY-MM-DD}. */
    private static final int HYPHEN_INDEX_MONTH_DAY = 7;

    /** Number of digits in the compact {@code CCYYMMDD} form consumed by {@link DateUtils}. */
    private static final int COMPACT_LENGTH = 8;

    /** Offset of the two month digits within the compact {@code CCYYMMDD} form. */
    private static final int COMPACT_MONTH_OFFSET = 4;

    /** Radix used to combine the two month digits of a {@code CCYYMMDD} value. */
    private static final int TENS = 10;

    /**
     * Immutable outcome of a date validation &mdash; the transport-neutral analog of the
     * COBOL {@code CSUTLDTC} outputs {@code RETURN-CODE} (severity) and {@code LS-RESULT}
     * (message). Exactly one of two shapes is produced: {@link #valid()} carries
     * {@code valid == true} with severity {@code "0000"}, and {@link #invalid(String)}
     * carries {@code valid == false} with a non-zero severity and a caller-visible message.
     *
     * <p>This is a hand-written {@code final} class rather than a {@code record} to keep
     * the required static {@link #valid()} factory from clashing with the record accessor
     * a {@code valid} component would generate (see the class-level note). Instances are
     * immutable and therefore safe to share across threads. Validation messages describe
     * the value only in generic terms and never echo sensitive data, so {@link #toString()}
     * is safe to log.</p>
     */
    public static final class DateValidationResult {

        /**
         * Severity for a valid date: the string {@code "0000"}, matching the COBOL
         * {@code RETURN-CODE 0} produced from {@code SEVERITY OF FEEDBACK-CODE} when
         * {@code CEEDAYS} accepts the date ({@code FC-INVALID-DATE}, an all-zero token).
         */
        private static final String SEVERITY_VALID = "0000";

        /**
         * Severity for a rejected date: the string {@code "0003"}, the LE severity
         * (halfword {@code 0x0003}) carried by the {@code CSUTLDTC} feedback condition
         * tokens for bad-date, invalid-month, and insufficient-data outcomes
         * ({@code legacy/cbl/CSUTLDTC.cbl:L63-L70}). Any non-{@code "0000"} severity means
         * invalid under the caller acceptance rule.
         */
        private static final String SEVERITY_INVALID = "0003";

        /**
         * Result message for a valid date. Exact COBOL {@code WS-RESULT} literal from
         * {@code legacy/cbl/CSUTLDTC.cbl:L130} ({@code FC-INVALID-DATE}): {@code 'Date is valid'}.
         */
        private static final String MESSAGE_VALID = "Date is valid";

        /** Whether the candidate value is a valid date (COBOL severity {@code '0000'}). */
        private final boolean valid;

        /** The four-character severity string ({@code "0000"} when valid). Never {@code null}. */
        private final String severity;

        /** The human-readable result message (the COBOL {@code LS-RESULT}). Never {@code null}. */
        private final String message;

        /**
         * Sole constructor, kept {@code private} so instances are obtained only through the
         * {@link #valid()} and {@link #invalid(String)} factories.
         *
         * @param valid    {@code true} for a valid date, {@code false} otherwise
         * @param severity the four-character severity string
         * @param message  the result message
         */
        private DateValidationResult(boolean valid, String severity, String message) {
            this.valid = valid;
            this.severity = severity;
            this.message = message;
        }

        /**
         * Builds the valid outcome: {@code valid == true}, severity {@code "0000"}, message
         * {@code "Date is valid"}. This is the Java analog of {@code CSUTLDTC} returning
         * {@code RETURN-CODE 0} with {@code WS-RESULT = 'Date is valid'}.
         *
         * @return a valid {@code DateValidationResult}
         */
        public static DateValidationResult valid() {
            return new DateValidationResult(true, SEVERITY_VALID, MESSAGE_VALID);
        }

        /**
         * Builds an invalid outcome carrying the supplied message and a non-zero severity
         * ({@code "0003"}), the Java analog of {@code CSUTLDTC} returning a non-zero
         * {@code RETURN-CODE} with a {@code WS-RESULT} error message.
         *
         * @param message the caller-visible result message; expected non-{@code null}
         *                (all call sites pass a fixed message constant)
         * @return an invalid {@code DateValidationResult}
         */
        public static DateValidationResult invalid(String message) {
            return new DateValidationResult(false, SEVERITY_INVALID, message);
        }

        /**
         * Reports whether the candidate value is a valid date. Named {@code isValid} rather
         * than {@code valid} so it does not collide with the static {@link #valid()} factory.
         *
         * @return {@code true} if the date is valid (severity {@code "0000"}), else {@code false}
         */
        public boolean isValid() {
            return valid;
        }

        /**
         * Returns the four-character severity string: {@code "0000"} when valid, otherwise a
         * non-zero severity. Mirrors the COBOL {@code RETURN-CODE}/{@code WS-SEVERITY}.
         *
         * @return the non-{@code null} severity string
         */
        public String severity() {
            return severity;
        }

        /**
         * Returns the human-readable result message (the COBOL {@code LS-RESULT}/{@code WS-RESULT}).
         *
         * @return the non-{@code null} result message
         */
        public String message() {
            return message;
        }

        /**
         * Returns a diagnostic representation. Messages are generic and never carry sensitive
         * data, so this string is safe to log.
         *
         * @return a string of the form {@code DateValidationResult{valid=..., severity='...', message='...'}}
         */
        @Override
        public String toString() {
            return "DateValidationResult{valid=" + valid
                    + ", severity='" + severity + "'"
                    + ", message='" + message + "'}";
        }
    }

    /**
     * Validates a date string against a format mask &mdash; the primary analog of
     * {@code CALL 'CSUTLDTC' USING LS-DATE, LS-DATE-FORMAT, LS-RESULT}. The outcome
     * reproduces the caller acceptance rule (valid only when severity {@code '0000'} and
     * message&nbsp;{@code != 2513}) as follows:
     * <ol>
     *   <li>a {@code null} or blank value is invalid ({@code "Date is invalid"}), mirroring
     *       the legacy rejection of a spaces-only {@code LS-DATE};</li>
     *   <li>only the {@code YYYY-MM-DD} mask is supported; any other {@code format}, or a
     *       value that does not structurally fill that mask (wrong length, misplaced
     *       hyphens, or non-numeric digits &mdash; the {@code FC-INSUFFICIENT-DATA}/{@code 2513}
     *       and non-numeric cases), is invalid, returned defensively without throwing;</li>
     *   <li>a structurally sound value is checked against the strict proleptic-Gregorian
     *       calendar via {@link DateUtils#isValidCcyyMmDd(String)} (the {@code CEEDAYS}
     *       equivalent): a real date is {@link DateValidationResult#valid()};</li>
     *   <li>otherwise the failing branch is reported &mdash; a month outside {@code 1..12}
     *       yields {@code "Invalid month  "} ({@code FC-INVALID-MONTH}); every other rejected
     *       value yields {@code "Date is invalid"} (the {@code WHEN OTHER} branch).</li>
     * </ol>
     *
     * @param date   the candidate date string (a {@code YYYY-MM-DD} value); may be {@code null}
     * @param format the expected format mask; only {@code "YYYY-MM-DD"} is supported
     * @return a {@link DateValidationResult} describing the outcome; never {@code null}
     */
    public DateValidationResult validateDate(String date, String format) {
        // Step 1: reject a spaces-only / absent LS-DATE (COBOL treats blanks as not a date).
        if (date == null || date.isBlank()) {
            return DateValidationResult.invalid(MSG_INVALID_DATE);
        }
        // Step 2: normalize YYYY-MM-DD -> compact CCYYMMDD; null means an unsupported mask or a
        // value that does not fill the mask (the insufficient-data / non-numeric rejections).
        final String compact = toCompactCcyymmdd(date, format);
        if (compact == null) {
            return DateValidationResult.invalid(MSG_INVALID_DATE);
        }
        // Step 3: strict calendar check (CEEDAYS -> java.time). A real date is valid (severity 0000).
        if (DateUtils.isValidCcyyMmDd(compact)) {
            return DateValidationResult.valid();
        }
        // Step 4: classify the rejection to reproduce the CSUTLDTC per-field message.
        if (!DateUtils.isValidMonth(monthOf(compact))) {
            return DateValidationResult.invalid(MSG_INVALID_MONTH);
        }
        return DateValidationResult.invalid(MSG_INVALID_DATE);
    }

    /**
     * Convenience boolean wrapper over {@link #validateDate(String, String)}, matching the
     * common caller pattern that treats a date as valid when the returned severity is
     * {@code '0000'}. Never throws for {@code null}, blank, or malformed input &mdash; such
     * values simply report {@code false}.
     *
     * @param date   the candidate date string; may be {@code null}
     * @param format the expected format mask; only {@code "YYYY-MM-DD"} is supported
     * @return {@code true} if {@code date} is a valid calendar date under {@code format}
     */
    public boolean isValid(String date, String format) {
        return validateDate(date, format).isValid();
    }

    /**
     * Strictly parses a {@code YYYY-MM-DD} value into a {@link LocalDate}, delegating the
     * calendar rules to {@link DateUtils#parseCcyyMmDd(String)}. Intended for callers that
     * need the parsed value after a successful {@link #isValid(String, String)} check.
     *
     * <p><strong>Error behavior (documented, propagate):</strong> a {@code null}, wrong-mask,
     * or non-calendar value causes a {@link DateTimeParseException} to propagate to the caller
     * (rather than being wrapped in another exception type). Callers guard with
     * {@link #isValid(String, String)} first, mirroring the legacy flow that validated via
     * {@code CSUTLDTC} before using the date.</p>
     *
     * @param date the date string in {@code YYYY-MM-DD} form
     * @return the parsed {@link LocalDate}
     * @throws DateTimeParseException if {@code date} is {@code null}, does not fill the
     *                                {@code YYYY-MM-DD} mask, or is not a real calendar date
     */
    public LocalDate parse(String date) {
        final String compact = toCompactCcyymmdd(date, FORMAT_YYYY_MM_DD);
        if (compact == null) {
            throw new DateTimeParseException(
                    "Date must match the " + FORMAT_YYYY_MM_DD + " mask",
                    String.valueOf(date), 0);
        }
        return DateUtils.parseCcyyMmDd(compact);
    }

    /**
     * Normalizes a hyphenated {@code YYYY-MM-DD} value into the compact eight-digit
     * {@code CCYYMMDD} form that {@link DateUtils} consumes. Returns {@code null} (rather than
     * throwing) for an unsupported mask, a wrong-length value, misplaced hyphens, or any
     * non-numeric digit, so callers can map those to the invalid outcome. This reproduces the
     * COBOL length/format guards that precede the {@code CEEDAYS} call.
     *
     * @param date   the candidate value; may be {@code null}
     * @param format the mask to validate against; only {@link #FORMAT_YYYY_MM_DD} is supported
     * @return the eight-digit {@code CCYYMMDD} string, or {@code null} if the value does not
     *         structurally fill the {@code YYYY-MM-DD} mask
     */
    private static String toCompactCcyymmdd(String date, String format) {
        if (!FORMAT_YYYY_MM_DD.equals(format)) {
            return null;
        }
        if (date == null || date.length() != HYPHENATED_LENGTH) {
            return null;
        }
        if (date.charAt(HYPHEN_INDEX_YEAR_MONTH) != '-'
                || date.charAt(HYPHEN_INDEX_MONTH_DAY) != '-') {
            return null;
        }
        final char[] compact = new char[COMPACT_LENGTH];
        int j = 0;
        for (int i = 0; i < HYPHENATED_LENGTH; i++) {
            if (i == HYPHEN_INDEX_YEAR_MONTH || i == HYPHEN_INDEX_MONTH_DAY) {
                continue;
            }
            final char c = date.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
            compact[j] = c;
            j++;
        }
        return new String(compact);
    }

    /**
     * Extracts the two-digit month from a compact {@code CCYYMMDD} value. The argument is
     * guaranteed to be eight digits (it comes from {@link #toCompactCcyymmdd(String, String)}),
     * so the two month characters are always numeric.
     *
     * @param compactCcyymmdd an eight-digit {@code CCYYMMDD} string
     * @return the month component as an integer (for example {@code 13} for {@code "20241301"})
     */
    private static int monthOf(String compactCcyymmdd) {
        final int tensDigit = compactCcyymmdd.charAt(COMPACT_MONTH_OFFSET) - '0';
        final int unitsDigit = compactCcyymmdd.charAt(COMPACT_MONTH_OFFSET + 1) - '0';
        return tensDigit * TENS + unitsDigit;
    }
}
