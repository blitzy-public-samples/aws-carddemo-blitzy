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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UsSsnRule}, the Java reproduction of the COBOL edit paragraph
 * {@code 1265-EDIT-US-SSN} of {@code legacy/cbl/COACTUPC.cbl} (source-branch
 * {@code app/cbl/COACTUPC.cbl}, lines L2431&ndash;L2491). The paragraph edits a United States
 * Social Security Number modeled as three fixed-width sub-fields &mdash; part&nbsp;1 (3 digits),
 * part&nbsp;2 (2 digits), part&nbsp;3 (4 digits) &mdash; running the shared numeric-required edit
 * {@code 1245-EDIT-NUM-REQD} on each part with a distinct fixed label, plus the additional
 * {@code 88 INVALID-SSN-PART1} range check on part&nbsp;1 ({@code VALUES 0, 666, 900 THRU 999}).
 *
 * <p><strong>Parity contract asserted here</strong> (verified against the source COBOL):</p>
 * <ul>
 *   <li>the sequential, first-message-wins order part&nbsp;1&nbsp;numeric &rarr; part&nbsp;1&nbsp;range
 *       &rarr; part&nbsp;2&nbsp;numeric &rarr; part&nbsp;3&nbsp;numeric, so the <em>first</em> failing
 *       part's screen message is the caller-visible outcome;</li>
 *   <li>the verbatim, per-part numeric-required messages delegated to {@link NumericRequiredRule}
 *       ("{@code <label> must be supplied.}" / "{@code <label> must be all numeric.}" /
 *       "{@code <label> must not be zero.}") built from the three fixed labels
 *       {@code "SSN: First 3 chars"}, {@code "SSN 4th & 5th chars"}, and
 *       {@code "SSN Last 4 chars"};</li>
 *   <li>the verbatim part&nbsp;1 range message
 *       {@code "SSN: First 3 chars: should not be 000, 666, or between 900 and 999"} (note the
 *       leading colon-space that the COBOL {@code STRING} literal contributes at
 *       {@code legacy/cbl/COACTUPC.cbl:L2457});</li>
 *   <li>the ordering proof that {@code "000"} reports "{@code must not be zero.}" (the numeric edit
 *       rejects zero <em>before</em> the range check runs), so the range message is only reachable
 *       for {@code 666} and {@code 900}&ndash;{@code 999};</li>
 *   <li>the fixed-width digit-count parity under which a short (space-padded) entry fails the COBOL
 *       {@code IS NUMERIC} class test and reports "{@code must be all numeric.}".</li>
 * </ul>
 *
 * <p><strong>Sensitive data (SSN).</strong> The SSN is sensitive, so this test uses only obviously
 * synthetic part tokens, never assembles or asserts a full {@code xxx-xx-xxxx} string, and never
 * prints or logs any part value. The only strings asserted are the fixed parity messages, which are
 * composed solely of a fixed field label plus fixed text and therefore never contain an SSN digit.
 * A dedicated reflection test additionally proves {@link UsSsnRule} declares no logger field (so the
 * SSN can never be logged) and holds no state beyond the injected collaborator, and another test
 * proves an outcome message never echoes the raw value that was entered.</p>
 *
 * <p><strong>Collaborator strategy.</strong> The rule is exercised through
 * {@code new UsSsnRule(new NumericRequiredRule())} with a <em>real</em> collaborator (constructor
 * injection, no Mockito, no Spring context, no database), mirroring the legacy
 * {@code PERFORM 1245-EDIT-NUM-REQD} reuse and proving the two migrated paragraphs compose exactly
 * as they do in COBOL. This is a pure JUnit&nbsp;5 + AssertJ unit test.</p>
 */
class UsSsnRuleTest {

    /**
     * The verbatim part&nbsp;1 range-failure message. Reproduced from the COBOL
     * {@code STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) ': should not be 000, 666, or between 900
     * and 999'} at {@code legacy/cbl/COACTUPC.cbl:L2450-L2457}; the label already ends without a
     * colon, so the literal's leading colon-space produces the doubled "{@code chars:}" seen here.
     */
    private static final String PART1_RANGE_MESSAGE =
            "SSN: First 3 chars: should not be 000, 666, or between 900 and 999";

    /** A well-formed, non-zero part&nbsp;2 value (4th &amp; 5th digits) reused by the part-3 cases. */
    private static final String VALID_PART2 = "45";

    /** A well-formed, non-zero part&nbsp;3 value (last 4 digits) reused by the part-1/part-2 cases. */
    private static final String VALID_PART3 = "6789";

    /** The rule under test, wired with a real numeric-required collaborator before each case. */
    private UsSsnRule rule;

    @BeforeEach
    void setUp() {
        // Real collaborator (no Mockito), plain construction (no Spring), mirroring the legacy
        // PERFORM 1245-EDIT-NUM-REQD reuse via constructor injection.
        rule = new UsSsnRule(new NumericRequiredRule());
    }

    // ---------------------------------------------------------------------------------------------
    // (a) All parts valid -> valid (every part passes its numeric-required edit; part 1 in range)
    // ---------------------------------------------------------------------------------------------

    @Test
    void allValidParts_areValid() {
        ValidationResult result = rule.validate("123", VALID_PART2, VALID_PART3);

        assertThat(result.isValid()).as("a well-formed SSN must pass every part edit").isTrue();
        assertThat(result.isInvalid()).isFalse();
        assertThat(result.message()).as("a valid result carries the empty message").isEqualTo("");
    }

    @Test
    void validResult_isTheSharedValidSingleton() {
        // A passing rule returns the cached ValidationResult.valid() instance (no per-call allocation).
        assertThat(rule.validate("123", VALID_PART2, VALID_PART3))
                .isSameAs(ValidationResult.valid());
    }

    // ---------------------------------------------------------------------------------------------
    // (b/c) Part 1 range check (88 INVALID-SSN-PART1: VALUES 0, 666, 900 THRU 999) -> range message
    // ---------------------------------------------------------------------------------------------

    @Test
    void part1_is666_reportsRangeMessage() {
        ValidationResult result = rule.validate("666", VALID_PART2, VALID_PART3);

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(PART1_RANGE_MESSAGE);
    }

    @Test
    void part1_lowBoundOfNineHundreds_reportsRangeMessage() {
        ValidationResult result = rule.validate("900", VALID_PART2, VALID_PART3);

        assertThat(result.isInvalid()).as("900 is the low bound of the 900-999 range").isTrue();
        assertThat(result.message()).isEqualTo(PART1_RANGE_MESSAGE);
    }

    @Test
    void part1_highBoundOfNineHundreds_reportsRangeMessage() {
        ValidationResult result = rule.validate("999", VALID_PART2, VALID_PART3);

        assertThat(result.isInvalid()).as("999 is the high bound of the 900-999 range").isTrue();
        assertThat(result.message()).isEqualTo(PART1_RANGE_MESSAGE);
    }

    @Test
    void part1_rangeBoundaries_justOutsideAreValid() {
        // Immediately-adjacent values must NOT trip the 88 condition: 665/667 straddle 666, and 899
        // is just below the 900-999 band, so all three are valid; 900 is the inclusive low bound.
        assertThat(rule.validate("665", VALID_PART2, VALID_PART3).isValid())
                .as("665 is just below 666").isTrue();
        assertThat(rule.validate("667", VALID_PART2, VALID_PART3).isValid())
                .as("667 is just above 666").isTrue();
        assertThat(rule.validate("899", VALID_PART2, VALID_PART3).isValid())
                .as("899 is just below the 900-999 range").isTrue();
        assertThat(rule.validate("900", VALID_PART2, VALID_PART3).isInvalid())
                .as("900 is the inclusive low bound of the range").isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // (d) Ordering proof: "000" is caught by the numeric non-zero edit BEFORE the range check
    // ---------------------------------------------------------------------------------------------

    @Test
    void part1_isZero_reportsMustNotBeZero() {
        ValidationResult result = rule.validate("000", VALID_PART2, VALID_PART3);

        assertThat(result.isInvalid()).isTrue();
        // Critical ordering assertion: the non-zero numeric edit wins over the range message, so a
        // zero part 1 reports "must not be zero.", never ": should not be 000, 666, ...".
        assertThat(result.message()).isEqualTo("SSN: First 3 chars must not be zero.");
    }

    // ---------------------------------------------------------------------------------------------
    // (e) Blank part 1 (LOW-VALUES / SPACES) -> "must be supplied."
    // ---------------------------------------------------------------------------------------------

    @Test
    void part1_null_reportsMustBeSupplied() {
        // null models COBOL LOW-VALUES.
        assertThat(rule.validate(null, VALID_PART2, VALID_PART3).message())
                .isEqualTo("SSN: First 3 chars must be supplied.");
    }

    @Test
    void part1_empty_reportsMustBeSupplied() {
        // "" models COBOL SPACES / TRIM length 0.
        assertThat(rule.validate("", VALID_PART2, VALID_PART3).message())
                .isEqualTo("SSN: First 3 chars must be supplied.");
    }

    @Test
    void part1_allWhitespace_reportsMustBeSupplied() {
        // An all-whitespace value trims to length 0, matching COBOL SPACES.
        assertThat(rule.validate("   ", VALID_PART2, VALID_PART3).message())
                .isEqualTo("SSN: First 3 chars must be supplied.");
    }

    // ---------------------------------------------------------------------------------------------
    // (f) Short / non-numeric part 1 -> "must be all numeric." (fixed-width space-padding parity)
    // ---------------------------------------------------------------------------------------------

    @Test
    void part1_tooShort_reportsMustBeAllNumeric() {
        // A short entry is space-padded in the fixed-width field, so COBOL IS NUMERIC fails it.
        ValidationResult result = rule.validate("12", VALID_PART2, VALID_PART3);

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("SSN: First 3 chars must be all numeric.");
    }

    @Test
    void part1_nonNumeric_reportsMustBeAllNumeric() {
        ValidationResult result = rule.validate("1x3", VALID_PART2, VALID_PART3);

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("SSN: First 3 chars must be all numeric.");
    }

    // ---------------------------------------------------------------------------------------------
    // (g) Part 2 edits (reached only when part 1 is valid) -> the part-2 label in the message
    // ---------------------------------------------------------------------------------------------

    @Test
    void part2_isZero_reportsMustNotBeZero() {
        ValidationResult result = rule.validate("123", "00", VALID_PART3);

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("SSN 4th & 5th chars must not be zero.");
    }

    @Test
    void part2_blank_reportsMustBeSupplied() {
        ValidationResult result = rule.validate("123", "", VALID_PART3);

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("SSN 4th & 5th chars must be supplied.");
    }

    @Test
    void part2_tooShort_reportsMustBeAllNumeric() {
        // One digit space-pads to width 2 -> not numeric in the fixed-width field.
        ValidationResult result = rule.validate("123", "4", VALID_PART3);

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("SSN 4th & 5th chars must be all numeric.");
    }

    // ---------------------------------------------------------------------------------------------
    // (h) Part 3 edits (reached only when parts 1 and 2 are valid) -> the part-3 label in the message
    // ---------------------------------------------------------------------------------------------

    @Test
    void part3_isZero_reportsMustNotBeZero() {
        ValidationResult result = rule.validate("123", VALID_PART2, "0000");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("SSN Last 4 chars must not be zero.");
    }

    @Test
    void part3_blank_reportsMustBeSupplied() {
        ValidationResult result = rule.validate("123", VALID_PART2, "");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("SSN Last 4 chars must be supplied.");
    }

    @Test
    void part3_tooShort_reportsMustBeAllNumeric() {
        // Three digits space-pad to width 4 -> not numeric in the fixed-width field.
        ValidationResult result = rule.validate("123", VALID_PART2, "678");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("SSN Last 4 chars must be all numeric.");
    }

    // ---------------------------------------------------------------------------------------------
    // First-message-wins across parts, and statelessness across successive invocations
    // ---------------------------------------------------------------------------------------------

    @Test
    void firstFailingPartWins_part1MasksLaterFailures() {
        // All three parts are independently invalid (each numerically zero); only the part-1 message
        // is returned, proving the sequential first-message-wins latch (WS-RETURN-MSG-OFF).
        ValidationResult result = rule.validate("000", "00", "0000");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("SSN: First 3 chars must not be zero.");
    }

    @Test
    void ruleIsStatelessAcrossInvocations() {
        // The single rule instance safely handles a mix of valid and invalid calls with no drift.
        assertThat(rule.validate("123", VALID_PART2, VALID_PART3).isValid()).isTrue();
        assertThat(rule.validate("666", VALID_PART2, VALID_PART3).isInvalid()).isTrue();
        assertThat(rule.validate("123", "00", VALID_PART3).isInvalid()).isTrue();
        assertThat(rule.validate("123", VALID_PART2, "0000").isInvalid()).isTrue();
        // The final valid call still returns the canonical singleton, confirming no state leaked.
        assertThat(rule.validate("123", VALID_PART2, VALID_PART3)).isSameAs(ValidationResult.valid());
    }

    // ---------------------------------------------------------------------------------------------
    // Sensitive-data guarantees: no logger field, no stored SSN, and no raw input echoed in messages
    // ---------------------------------------------------------------------------------------------

    @Test
    void declaresNoLoggerFieldAndHoldsOnlyTheInjectedRule() {
        int instanceFieldCount = 0;
        for (Field field : UsSsnRule.class.getDeclaredFields()) {
            // Skip any synthetic field the compiler or coverage agent may add (e.g. $jacocoData).
            if (field.isSynthetic()) {
                continue;
            }
            // No field of any kind may be a logger: the SSN must never be logged.
            assertThat(field.getType().getName())
                    .as("UsSsnRule must not declare a logger field (field '%s')", field.getName())
                    .doesNotContain("Logger", "slf4j", "log4j", "logging");
            if (!Modifier.isStatic(field.getModifiers())) {
                instanceFieldCount++;
                assertThat(field.getType())
                        .as("the only instance state must be the injected NumericRequiredRule")
                        .isEqualTo(NumericRequiredRule.class);
            }
        }
        assertThat(instanceFieldCount)
                .as("UsSsnRule must hold exactly one instance field (no SSN value is ever stored)")
                .isEqualTo(1);
    }

    @Test
    void outcomeMessage_neverEchoesTheRawInput() {
        // A distinctive non-numeric token that shares no substring with any fixed message body; the
        // message must report the fixed "must be all numeric." text and must not contain the token.
        ValidationResult result = rule.validate("A7Q", VALID_PART2, VALID_PART3);

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("SSN: First 3 chars must be all numeric.");
        assertThat(result.message())
                .as("the raw entered value must never appear in the outcome message")
                .doesNotContain("A7Q");
    }
}
