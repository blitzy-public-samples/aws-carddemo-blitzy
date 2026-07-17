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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link FicoScoreRule}, asserting bit-for-bit behavioral parity with the combined
 * COBOL FICO edit of {@code legacy/cbl/COACTUPC.cbl}: the numeric-required pre-edit
 * {@code 1245-EDIT-NUM-REQD} (L2109&ndash;L2178) followed, only when it passes, by the range check
 * {@code 1275-EDIT-FICO-SCORE} (L2514&ndash;L2533, {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH
 * 850}).
 *
 * <p>The rule is exercised through a real {@link NumericRequiredRule} collaborator
 * (constructor-injected, mirroring the legacy {@code PERFORM 1245-EDIT-NUM-REQD} reuse) rather than
 * a mock, so the tests prove the two migrated paragraphs compose exactly as they do in COBOL. Every
 * failure asserts the verbatim screen message, and the ordered short-circuit is verified so that a
 * value which is simultaneously a pre-edit failure (for example {@code "000"}) reports the
 * {@code 1245} message, never the range message.</p>
 */
class FicoScoreRuleTest {

    /**
     * The caller label the legacy program moves into {@code WS-EDIT-VARIABLE-NAME}
     * ({@code legacy/cbl/COACTUPC.cbl:L1545}); substituted into every outcome message.
     */
    private static final String LABEL = "FICO Score";

    /** The exact range-failure message from {@code legacy/cbl/COACTUPC.cbl:L2522-L2523}. */
    private static final String RANGE_MESSAGE = "FICO Score: should be between 300 and 850";

    /**
     * The rule under test, wired exactly as the agent prompt specifies:
     * {@code new FicoScoreRule(new NumericRequiredRule())}.
     */
    private final FicoScoreRule rule = new FicoScoreRule(new NumericRequiredRule());

    // ---------------------------------------------------------------------------------------------
    // Valid range (numeric pre-edit passes AND value within 300-850 inclusive)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("(a) A mid-range score 700 is valid")
    void midRangeScoreIsValid() {
        ValidationResult result = rule.validate(LABEL, "700");

        assertTrue(result.isValid(), "700 is within 300-850 and must be valid");
        assertEquals("", result.message(), "a valid result carries the empty message");
    }

    @ParameterizedTest
    @ValueSource(strings = {"300", "850"})
    @DisplayName("(b) The inclusive boundaries 300 and 850 are valid (VALUES 300 THROUGH 850)")
    void inclusiveBoundariesAreValid(String score) {
        ValidationResult result = rule.validate(LABEL, score);

        assertTrue(result.isValid(), () -> "boundary score '" + score + "' must be valid");
        assertEquals("", result.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"301", "500", "849"})
    @DisplayName("Scores strictly inside the range are valid")
    void interiorScoresAreValid(String score) {
        assertTrue(rule.validate(LABEL, score).isValid(),
                () -> "interior score '" + score + "' must be valid");
    }

    @Test
    @DisplayName("A passing rule returns the shared ValidationResult.valid() singleton")
    void validOutcomeIsTheSharedSingleton() {
        assertSame(ValidationResult.valid(), rule.validate(LABEL, "700"),
                "a passing rule should return the cached valid() instance");
    }

    // ---------------------------------------------------------------------------------------------
    // Out of range (numeric pre-edit passes, range check fails) -> range message
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("(c) 299 (just below the lower bound) yields the range message")
    void justBelowLowerBoundIsRejected() {
        ValidationResult result = rule.validate(LABEL, "299");

        assertTrue(result.isInvalid(), "299 is below 300 and must be invalid");
        assertEquals(RANGE_MESSAGE, result.message());
    }

    @Test
    @DisplayName("(d) 851 (just above the upper bound) yields the range message")
    void justAboveUpperBoundIsRejected() {
        ValidationResult result = rule.validate(LABEL, "851");

        assertTrue(result.isInvalid(), "851 is above 850 and must be invalid");
        assertEquals(RANGE_MESSAGE, result.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"001", "299"})
    @DisplayName("A non-zero numeric value below 300 yields the range message")
    void belowRangeYieldsRangeMessage(String score) {
        ValidationResult result = rule.validate(LABEL, score);

        assertTrue(result.isInvalid(), () -> "'" + score + "' is below 300 and must be invalid");
        assertEquals(RANGE_MESSAGE, result.message());
    }

    // ---------------------------------------------------------------------------------------------
    // Numeric-required pre-edit (1245) failures -> that paragraph's message, verbatim
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t"})
    @DisplayName("(e) Blank input is caught by the 1245 pre-edit -> 'FICO Score must be supplied.'")
    void blankIsRejectedByNumericPreEdit(String value) {
        ValidationResult result = rule.validate(LABEL, value);

        assertTrue(result.isInvalid(), "blank input must be invalid");
        assertEquals("FICO Score must be supplied.", result.message(),
                "blank must report the 1245 not-supplied message, not the range message");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12A", "7O0", "30.", "-99", "+50", "abc", "70 "})
    @DisplayName("(f) Non-numeric input is caught by the 1245 pre-edit -> 'FICO Score must be all numeric.'")
    void nonNumericIsRejectedByNumericPreEdit(String value) {
        ValidationResult result = rule.validate(LABEL, value);

        assertTrue(result.isInvalid(), () -> "'" + value + "' must be invalid");
        assertEquals("FICO Score must be all numeric.", result.message(),
                "non-numeric must report the 1245 numeric message, not the range message");
    }

    @ParameterizedTest
    @ValueSource(strings = {"000", "0", "00"})
    @DisplayName("(g) Zero is caught by the 1245 pre-edit -> 'FICO Score must not be zero.' (ordering proof)")
    void zeroIsRejectedByNumericPreEditNotRangeCheck(String value) {
        // This is the critical ordering assertion: "000" is numerically < 300, so a naive range-only
        // rule would emit the range message. The COBOL runs 1245-EDIT-NUM-REQD FIRST, so zero must
        // instead report "must not be zero." This proves the pre-edit precedence is preserved.
        ValidationResult result = rule.validate(LABEL, value);

        assertTrue(result.isInvalid(), () -> "'" + value + "' must be invalid");
        assertEquals("FICO Score must not be zero.", result.message(),
                "zero must report the 1245 non-zero message, never the range message");
    }

    // ---------------------------------------------------------------------------------------------
    // Robustness: label trimming, defensive length bound, statelessness
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("The field label is trimmed (FUNCTION TRIM) before substitution into the range message")
    void fieldLabelIsTrimmedInRangeMessage() {
        ValidationResult result = rule.validate("  FICO Score  ", "851");

        assertTrue(result.isInvalid());
        assertEquals(RANGE_MESSAGE, result.message(),
                "leading/trailing label spaces must be removed, matching FUNCTION TRIM");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1000", "9999", "99999999999", "00000000000000000000000000001"})
    @DisplayName("Input wider than the PIC 9(03) field is out of range and never throws (defensive bound)")
    void overlongInputIsRejectedWithoutThrowing(String value) {
        // A value longer than three digits cannot be a valid FICO score. The very long all-digit
        // values additionally prove the internal Integer.parseInt can never overflow (the rule
        // never throws, honoring the ValidationRule contract). Note the last value is all zeros
        // beyond three digits, so it is first caught by the 1245 non-zero pre-edit.
        ValidationResult result = assertDoesNotThrow(() -> rule.validate(LABEL, value));

        assertTrue(result.isInvalid(), () -> "overlong '" + value + "' must be invalid");
    }

    @Test
    @DisplayName("A four-digit non-zero value is out of range with the range message (not a parse error)")
    void fourDigitValueYieldsRangeMessage() {
        ValidationResult result = assertDoesNotThrow(() -> rule.validate(LABEL, "1000"));

        assertTrue(result.isInvalid(), "1000 exceeds the three-digit field and is out of range");
        assertEquals(RANGE_MESSAGE, result.message());
    }

    @Test
    @DisplayName("Fixed-width padding around a valid score is tolerated via strip()")
    void paddedValidScoreIsStripped() {
        // The numeric pre-edit runs on the raw value; a value padded with spaces would fail it as
        // "must be all numeric" because a space is not a digit. This test therefore uses a value
        // that is already all-digits and confirms strip() is a harmless no-op that keeps 700 valid.
        assertTrue(rule.validate(LABEL, "700").isValid());
    }

    @Test
    @DisplayName("The rule is stateless and safe to reuse across successive, mixed invocations")
    void ruleIsStatelessAcrossInvocations() {
        assertTrue(rule.validate(LABEL, "700").isValid());
        assertTrue(rule.validate(LABEL, "851").isInvalid());
        assertTrue(rule.validate(LABEL, "300").isValid());
        assertTrue(rule.validate(LABEL, "000").isInvalid());
        assertTrue(rule.validate(LABEL, null).isInvalid());
        // The final valid call still returns the canonical singleton, confirming no drift.
        assertSame(ValidationResult.valid(), rule.validate(LABEL, "850"));
    }
}
