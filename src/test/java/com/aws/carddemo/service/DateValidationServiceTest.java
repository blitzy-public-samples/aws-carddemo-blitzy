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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aws.carddemo.service.DateValidationService.DateValidationResult;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Fast, isolated, pure-logic unit tests for {@link DateValidationService}, the Java re-platform of
 * the CardDemo COBOL date-validation wrapper {@code CSUTLDTC} (source-branch
 * {@code app/cbl/CSUTLDTC.cbl}, relocated to {@code legacy/cbl/CSUTLDTC.cbl} during migration).
 * The suite locks down the behavioral-parity contract the migration must preserve exactly
 * (AAP &sect;0.9.2 field-contract parity, &sect;0.8.3 "preserve public/observable contracts"):
 * leap-year handling, month/day ranges, invalid-date rejection, and the caller-visible
 * severity/message outcomes that reproduce the legacy {@code CALL 'CSUTLDTC'} result.
 *
 * <h2>Construction (no Spring, no mocks)</h2>
 * {@code DateValidationService} is a {@code @Service} with <em>no</em> injected dependencies: it
 * delegates the calendar arithmetic to the static utility
 * {@link com.aws.carddemo.common.util.DateUtils}. There are therefore no collaborators to mock, so
 * this test uses neither Mockito nor a Spring {@code ApplicationContext} nor Testcontainers &mdash;
 * the service is instantiated directly and its <em>real</em> (deterministic) output is asserted for
 * fixed date strings. {@code DateUtils} is a {@code final} class with a private constructor and only
 * {@code static} methods and consequently cannot be mocked; its own behavior is covered separately by
 * {@code common.util.DateUtilsTest}.
 *
 * <h2>Parity evidence (verified against the relocated COBOL)</h2>
 * The asserted outcomes reproduce {@code CSUTLDTC}'s {@code EVALUATE} on the LE {@code FEEDBACK-CODE}
 * ({@code legacy/cbl/CSUTLDTC.cbl:L128-L149}): {@code FC-INVALID-DATE} &rarr; {@code "Date is valid"}
 * (severity {@code "0000"}), {@code FC-INVALID-MONTH} &rarr; {@code "Invalid month  "} (the
 * {@code PIC X(15)} literal, its two trailing spaces significant), and {@code WHEN OTHER} &rarr;
 * {@code "Date is invalid"}. The leap-year boundaries exercise the {@code EDIT-DAY-MONTH-YEAR}
 * divisor rule of {@code legacy/cpy/CSUTLDPY.cpy} (divisor {@code 400} for century years, else
 * {@code 4}; leap when the remainder is zero) and the {@code CSUTLDWY.cpy} ranges (month
 * {@code 1..12}, day {@code 1..31}).
 *
 * <h2>Acceptance rule</h2>
 * A date is accepted only when the returned severity is {@code "0000"} (the caller convention from
 * {@code COTRN02C}/{@code CORPT00C}). Every rejected value carries a non-{@code "0000"} severity
 * (the authored service uses {@code "0003"}) and a caller-visible message. These tests assert both
 * the boolean {@link DateValidationService#isValid(String, String)} form and the richer
 * {@link DateValidationService.DateValidationResult} it wraps.
 *
 * <p>All inputs are fixed date strings, so {@code validateDate}, {@code isValid}, and {@code parse}
 * are clock-independent; the suite never reads the system clock and runs headlessly and
 * reproducibly.</p>
 */
@DisplayName("DateValidationService — CSUTLDTC date-validation parity")
public class DateValidationServiceTest {

    /**
     * The only date mask the legacy callers exercised through {@code CSUTLDTC}
     * ({@code YYYY-MM-DD}). The production constant {@code DateValidationService.FORMAT_YYYY_MM_DD}
     * is {@code private}, so the literal is repeated here to drive the public API exactly as a
     * caller would.
     */
    private static final String FORMAT = "YYYY-MM-DD";

    /**
     * System under test. {@code DateValidationService} has no dependencies, so it is created
     * directly (per the file contract) rather than through a Spring context. The instance is
     * stateless and safe to share across every test method.
     */
    private final DateValidationService service = new DateValidationService();

    // ------------------------------------------------------------------------
    // Valid dates — accepted with severity "0000"
    // ------------------------------------------------------------------------

    /**
     * Every real calendar date under the {@code YYYY-MM-DD} mask must be accepted: the boolean form
     * reports {@code true}, and the {@link DateValidationResult} reports {@code isValid() == true}
     * with severity {@code "0000"} (the COBOL {@code FC-INVALID-DATE} / {@code RETURN-CODE 0}
     * outcome). Includes the three leap-year shapes: divisible-by-4 ({@code 2024}, {@code 2020}) and
     * the 400-divisible century year ({@code 2000}).
     *
     * @param date a valid {@code YYYY-MM-DD} calendar date
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "2024-02-29", // leap year (divisible by 4) -> February 29 exists
            "2020-02-29", // leap year (divisible by 4)
            "2000-02-29", // leap year (century year divisible by 400)
            "2024-01-31", // January has 31 days
            "1999-12-31", // December 31
            "2000-01-01", // first day of the 2000s
            "2020-12-31"  // December 31
    })
    @DisplayName("valid calendar dates -> isValid true and severity \"0000\"")
    void acceptsRealCalendarDates(String date) {
        assertThat(service.isValid(date, FORMAT)).as("isValid(%s)", date).isTrue();

        DateValidationResult result = service.validateDate(date, FORMAT);
        assertThat(result.isValid()).as("validateDate(%s).isValid()", date).isTrue();
        assertThat(result.severity()).as("validateDate(%s).severity()", date).isEqualTo("0000");
    }

    // ------------------------------------------------------------------------
    // Invalid dates — rejected with a non-"0000" severity
    // ------------------------------------------------------------------------

    /**
     * Every impossible calendar value must be rejected: the boolean form reports {@code false}, and
     * the {@link DateValidationResult} reports {@code isValid() == false} with a severity other than
     * {@code "0000"} (the acceptance rule requires exactly {@code "0000"} to accept). Per-message
     * classification is asserted separately below.
     *
     * @param date an impossible {@code YYYY-MM-DD} value
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "2023-02-29", // 2023 is not a leap year -> no February 29
            "1900-02-29", // 1900 is a century year not divisible by 400 -> not a leap year
            "2024-13-01", // month 13 is out of range
            "2024-00-01", // month 00 is out of range
            "2024-04-31", // April has only 30 days
            "2024-02-30", // February can never have 30 days
            "2024-01-00"  // day 00 is out of range
    })
    @DisplayName("impossible calendar dates -> isValid false and severity not \"0000\"")
    void rejectsImpossibleCalendarDates(String date) {
        assertThat(service.isValid(date, FORMAT)).as("isValid(%s)", date).isFalse();

        DateValidationResult result = service.validateDate(date, FORMAT);
        assertThat(result.isValid()).as("validateDate(%s).isValid()", date).isFalse();
        assertThat(result.severity()).as("validateDate(%s).severity()", date).isNotEqualTo("0000");
    }

    // ------------------------------------------------------------------------
    // Per-message parity — CSUTLDTC EVALUATE branches
    // ------------------------------------------------------------------------

    /**
     * A month outside {@code 1..12} reproduces the {@code CSUTLDTC} {@code FC-INVALID-MONTH} branch
     * ({@code legacy/cbl/CSUTLDTC.cbl:L139-L140}): message {@code "Invalid month  "} &mdash; the
     * {@code WS-RESULT PIC X(15)} literal whose two trailing spaces are significant and preserved
     * verbatim &mdash; with the invalid severity {@code "0003"}.
     */
    @Test
    @DisplayName("month out of 1..12 -> message \"Invalid month  \" (FC-INVALID-MONTH, trailing spaces)")
    void monthOutOfRangeReportsInvalidMonthMessage() {
        DateValidationResult monthThirteen = service.validateDate("2024-13-01", FORMAT);
        assertThat(monthThirteen.isValid()).isFalse();
        assertThat(monthThirteen.severity()).isEqualTo("0003");
        assertThat(monthThirteen.message()).isEqualTo("Invalid month  ");

        DateValidationResult monthZero = service.validateDate("2024-00-01", FORMAT);
        assertThat(monthZero.isValid()).isFalse();
        assertThat(monthZero.severity()).isEqualTo("0003");
        assertThat(monthZero.message()).isEqualTo("Invalid month  ");
    }

    /**
     * A real month with an impossible day reproduces the {@code CSUTLDTC} {@code WHEN OTHER} branch
     * ({@code legacy/cbl/CSUTLDTC.cbl:L147-L148}): message {@code "Date is invalid"} with the invalid
     * severity {@code "0003"}. This distinguishes the day-level rejection from the month-level
     * {@code "Invalid month  "} branch above (the month here is valid, so the month branch is not
     * taken).
     *
     * @param date a value with a valid month but an impossible day
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "2024-04-31", // April (valid month) has only 30 days
            "2024-02-30", // February (valid month) can never have 30 days
            "2024-01-00"  // January (valid month) but day 00
    })
    @DisplayName("valid month, impossible day -> message \"Date is invalid\" (WHEN OTHER)")
    void impossibleDayReportsDateInvalidMessage(String date) {
        DateValidationResult result = service.validateDate(date, FORMAT);
        assertThat(result.isValid()).as("validateDate(%s).isValid()", date).isFalse();
        assertThat(result.severity()).as("validateDate(%s).severity()", date).isEqualTo("0003");
        assertThat(result.message()).as("validateDate(%s).message()", date).isEqualTo("Date is invalid");
    }

    // ------------------------------------------------------------------------
    // Null / blank / malformed — reported as invalid without throwing
    // ------------------------------------------------------------------------

    /**
     * {@code null}, blank, and structurally malformed values must be reported as invalid
     * <em>without</em> throwing from either {@link DateValidationService#isValid(String, String)} or
     * {@link DateValidationService#validateDate(String, String)}, mirroring the legacy rejection of a
     * spaces-only / non-conforming {@code LS-DATE}. The unpadded value {@code "2024-2-9"} is rejected
     * because it does not fill the fixed 10-character {@code YYYY-MM-DD} mask (the service performs a
     * strict length/format guard before delegating).
     *
     * @param candidate a {@code null}, blank, or malformed value
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",           // empty
            "   ",        // blanks only
            "2024/02/29", // wrong separator ('/' instead of '-')
            "abcd-ef-gh", // non-numeric digits
            "2024-2-9"    // unpadded -> does not fill the 10-char YYYY-MM-DD mask
    })
    @DisplayName("null / blank / malformed -> invalid, no exception escapes isValid or validateDate")
    void reportsInvalidForNullBlankOrMalformed(String candidate) {
        assertThat(service.isValid(candidate, FORMAT)).isFalse();
        assertThat(service.validateDate(candidate, FORMAT).isValid()).isFalse();
    }

    /**
     * Explicitly documents the no-throw contract of the two validation entry points for the awkward
     * inputs: neither {@code isValid} nor {@code validateDate} propagates an exception for
     * {@code null}, blank, or malformed values &mdash; they always return a defensively computed
     * result.
     */
    @Test
    @DisplayName("isValid / validateDate never throw for null, blank, or malformed input")
    void validationEntryPointsNeverThrow() {
        assertThatCode(() -> {
            service.isValid(null, FORMAT);
            service.validateDate(null, FORMAT);
            service.isValid("   ", FORMAT);
            service.validateDate("2024/02/29", FORMAT);
            service.isValid("abcd-ef-gh", FORMAT);
            service.validateDate("2024-2-9", FORMAT);
        }).doesNotThrowAnyException();
    }

    /**
     * An otherwise-valid date supplied under an unsupported mask must be rejected: only
     * {@code "YYYY-MM-DD"} is supported, so any other {@code format} is refused defensively (the
     * value never reaches the calendar check). Covers the mask guard the COBOL applied before the
     * {@code CEEDAYS} call.
     */
    @Test
    @DisplayName("unsupported format mask -> invalid even for a real date")
    void rejectsUnsupportedFormatMask() {
        assertThat(service.isValid("2024-02-29", "DD-MM-YYYY")).isFalse();
        assertThat(service.validateDate("2024-02-29", "DD-MM-YYYY").isValid()).isFalse();
    }

    // ------------------------------------------------------------------------
    // parse(String) — strict LocalDate parsing
    // ------------------------------------------------------------------------

    /**
     * {@link DateValidationService#parse(String)} returns the exact {@link LocalDate} for a valid
     * {@code YYYY-MM-DD} value, delegating the strict calendar rules to {@code DateUtils}. This is
     * the accessor callers use after a successful {@link DateValidationService#isValid(String, String)}
     * check.
     */
    @Test
    @DisplayName("parse returns the exact LocalDate for valid input")
    void parseReturnsExactLocalDate() {
        assertThat(service.parse("2024-02-29")).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(service.parse("2000-02-29")).isEqualTo(LocalDate.of(2000, 2, 29));
        assertThat(service.parse("1999-12-31")).isEqualTo(LocalDate.of(1999, 12, 31));
        assertThat(service.parse("2020-01-01")).isEqualTo(LocalDate.of(2020, 1, 1));
    }

    /**
     * {@link DateValidationService#parse(String)} propagates a
     * {@link java.time.format.DateTimeParseException} for {@code null}, a value that does not fill the
     * {@code YYYY-MM-DD} mask, or a non-calendar value &mdash; the documented, intentionally
     * propagated error behavior. {@code parse(null)} throws {@code DateTimeParseException} (not
     * {@code NullPointerException}) because the mask guard rejects the absent value before any
     * calendar work.
     *
     * @param bad a {@code null}, malformed, or impossible value
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "2024-13-01", // month 13
            "2023-02-29", // non-leap February 29
            "1900-02-29", // century year not divisible by 400 -> not a leap year
            "2024-02-30", // impossible February day
            "2024-04-31", // April has only 30 days
            "2100-02-29", // century year not divisible by 400 -> not a leap year
            "2024/02/29", // wrong separator
            "abcd-ef-gh", // non-numeric
            "2024-2-9",   // unpadded -> does not fill the mask
            ""            // empty -> does not fill the mask
    })
    @DisplayName("parse throws DateTimeParseException for null, malformed, or impossible input")
    void parseThrowsForNullMalformedOrImpossible(String bad) {
        assertThatThrownBy(() -> service.parse(bad))
                .isInstanceOf(DateTimeParseException.class);
    }

    // ------------------------------------------------------------------------
    // DateValidationResult — factory / accessor contract
    // ------------------------------------------------------------------------

    /**
     * The {@link DateValidationResult#valid()} factory produces the accepted outcome: {@code isValid()
     * == true}, severity {@code "0000"}, and message {@code "Date is valid"} (the COBOL
     * {@code FC-INVALID-DATE} literal at {@code legacy/cbl/CSUTLDTC.cbl:L130}). Note the instance
     * accessor is {@code isValid()} &mdash; {@code valid()} is the static factory, so the accessor is
     * deliberately named differently to avoid a collision.
     */
    @Test
    @DisplayName("DateValidationResult.valid() -> isValid true, severity \"0000\", message \"Date is valid\"")
    void validResultFactoryExposesAcceptedOutcome() {
        DateValidationResult valid = DateValidationResult.valid();
        assertThat(valid.isValid()).isTrue();
        assertThat(valid.severity()).isEqualTo("0000");
        assertThat(valid.message()).isEqualTo("Date is valid");
    }

    /**
     * The {@link DateValidationResult#invalid(String)} factory produces the rejected outcome carrying
     * the supplied message and a non-zero severity ({@code "0003"}): {@code isValid() == false}, the
     * message echoed back verbatim, and a severity other than {@code "0000"}.
     */
    @Test
    @DisplayName("DateValidationResult.invalid(msg) -> isValid false, message echoed, severity \"0003\"")
    void invalidResultFactoryCarriesMessageAndNonZeroSeverity() {
        DateValidationResult invalid = DateValidationResult.invalid("bad");
        assertThat(invalid.isValid()).isFalse();
        assertThat(invalid.message()).isEqualTo("bad");
        assertThat(invalid.severity()).isEqualTo("0003");
        assertThat(invalid.severity()).isNotEqualTo("0000");
    }

    // ------------------------------------------------------------------------
    // Leap-year boundary emphasis — CSUTLDPY divisor rule
    // ------------------------------------------------------------------------

    /**
     * Parity-critical emphasis on the leap-year divisor rule ({@code legacy/cpy/CSUTLDPY.cpy}
     * {@code EDIT-DAY-MONTH-YEAR}, divisor {@code 400} for century years else {@code 4}):
     * <ul>
     *   <li>{@code 2000-02-29} is <strong>valid</strong> &mdash; 2000 is divisible by 400, so it is a
     *       leap year and February 29 exists;</li>
     *   <li>{@code 1900-02-29} is <strong>invalid</strong> &mdash; 1900 is a century year not
     *       divisible by 400, so it is not a leap year;</li>
     *   <li>{@code 2023-02-29} is <strong>invalid</strong> &mdash; 2023 is not divisible by 4;</li>
     *   <li>{@code 2100-02-29} is <strong>invalid</strong> &mdash; 2100 is a century year not
     *       divisible by 400. Here {@code validateDate} rejects it purely on the non-leap calendar
     *       rule (its path calls {@code DateUtils.isValidCcyyMmDd}, which does not apply the century
     *       gate). Independently, {@code DateUtils.isValidCentury(21)} is {@code false}, so any
     *       century-gated path (the COBOL 19/20 restriction) would also reject it.</li>
     * </ul>
     */
    @Test
    @DisplayName("leap-year boundary parity: 2000 valid; 1900 / 2023 / 2100 Feb-29 invalid")
    void leapYearBoundaryParity() {
        assertThat(service.isValid("2000-02-29", FORMAT)).as("2000 divisible by 400 -> leap").isTrue();
        assertThat(service.isValid("1900-02-29", FORMAT)).as("1900 not divisible by 400 -> not leap").isFalse();
        assertThat(service.isValid("2023-02-29", FORMAT)).as("2023 not divisible by 4 -> not leap").isFalse();
        assertThat(service.isValid("2100-02-29", FORMAT)).as("2100 not divisible by 400 -> not leap").isFalse();
    }
}
