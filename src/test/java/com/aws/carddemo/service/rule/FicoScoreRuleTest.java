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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FicoScoreRule}, asserting bit-for-bit behavioral parity with the combined
 * COBOL FICO edit of {@code legacy/cbl/COACTUPC.cbl} (source-branch {@code app/cbl/COACTUPC.cbl}):
 * the numeric-required pre-edit {@code 1245-EDIT-NUM-REQD} performed first
 * ({@code legacy/cbl/COACTUPC.cbl:L1549-L1550}) and, <em>only when it passes</em>
 * ({@code IF FLG-FICO-SCORE-ISVALID} at {@code L1553}), the range check
 * {@code 1275-EDIT-FICO-SCORE} ({@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850}) whose
 * out-of-range message literal {@code ': should be between 300 and 850'} lives at
 * {@code legacy/cbl/COACTUPC.cbl:L2523}.
 *
 * <p>Per the production validation instruction the rule is exercised with a <strong>real</strong>
 * {@link NumericRequiredRule} collaborator &mdash; {@code new FicoScoreRule(new NumericRequiredRule())}
 * &mdash; rather than a Mockito stub. {@code NumericRequiredRule} is stateless, so a genuine instance
 * reproduces the legacy {@code PERFORM 1245-EDIT-NUM-REQD} reuse with higher fidelity than a mock and
 * proves the two migrated paragraphs compose exactly as they do in COBOL. This is a pure JUnit 5 +
 * AssertJ unit test: no Spring context, no {@code @SpringBootTest}, and no database.</p>
 *
 * <p>Every failing case asserts the verbatim screen message (mind the leading colon-space of the
 * range message), and the ordered short-circuit is verified so that a value which is simultaneously a
 * numeric-required failure and out of range &mdash; most importantly {@code "000"} &mdash; reports the
 * {@code 1245} message ("must not be zero.") and <em>never</em> the range message.</p>
 */
class FicoScoreRuleTest {

    /**
     * The caller label the legacy program moves into {@code WS-EDIT-VARIABLE-NAME}
     * ({@code legacy/cbl/COACTUPC.cbl:L1545}); substituted into every outcome message.
     */
    private static final String LABEL = "FICO Score";

    /**
     * The exact range-failure message: the trimmed label followed by the verbatim COBOL literal at
     * {@code legacy/cbl/COACTUPC.cbl:L2523} (including its leading colon-space).
     */
    private static final String RANGE_MESSAGE = "FICO Score: should be between 300 and 850";

    /**
     * The rule under test, re-created before each test with a real numeric-required collaborator
     * exactly as the agent prompt specifies: {@code new FicoScoreRule(new NumericRequiredRule())}.
     * Constructing it through its public constructor verifies the constructor-injection contract.
     */
    private FicoScoreRule rule;

    @BeforeEach
    void setUp() {
        rule = new FicoScoreRule(new NumericRequiredRule());
    }

    // ---------------------------------------------------------------------------------------------
    // Valid range: numeric pre-edit passes AND value within 300-850 inclusive
    // ---------------------------------------------------------------------------------------------

    @Test
    void midRangeScoreIsValid() {
        ValidationResult result = rule.validate(LABEL, "700");

        assertThat(result.isValid()).as("700 is within 300-850 and must be valid").isTrue();
        assertThat(result.message()).as("a valid result carries the empty message").isEmpty();
    }

    @Test
    void lowerBoundaryIsValid() {
        // 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850 -> the lower bound is inclusive.
        ValidationResult result = rule.validate(LABEL, "300");

        assertThat(result.isValid()).as("300 is the inclusive lower bound and must be valid").isTrue();
        assertThat(result.message()).isEmpty();
    }

    @Test
    void upperBoundaryIsValid() {
        // 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850 -> the upper bound is inclusive.
        ValidationResult result = rule.validate(LABEL, "850");

        assertThat(result.isValid()).as("850 is the inclusive upper bound and must be valid").isTrue();
        assertThat(result.message()).isEmpty();
    }

    @Test
    void interiorScoresAreValid() {
        assertThat(rule.validate(LABEL, "301").isValid()).as("301 is just inside the range").isTrue();
        assertThat(rule.validate(LABEL, "500").isValid()).as("500 is mid-range").isTrue();
        assertThat(rule.validate(LABEL, "849").isValid()).as("849 is just inside the range").isTrue();
    }

    @Test
    void validOutcomeIsTheSharedSingleton() {
        // A passing rule returns ValidationResult.valid(), the cached immutable singleton.
        assertThat(rule.validate(LABEL, "700")).isSameAs(ValidationResult.valid());
    }

    // ---------------------------------------------------------------------------------------------
    // Out of range: numeric pre-edit passes, range check (1275) fails -> range message verbatim
    // ---------------------------------------------------------------------------------------------

    @Test
    void justBelowLowerBoundYieldsRangeMessage() {
        // 299 is a non-zero numeric value below 300: the 1245 pre-edit passes, so 1275 reports it.
        ValidationResult result = rule.validate(LABEL, "299");

        assertThat(result.isValid()).as("299 is below 300 and must be invalid").isFalse();
        assertThat(result.message()).isEqualTo(RANGE_MESSAGE);
    }

    @Test
    void justAboveUpperBoundYieldsRangeMessage() {
        // 851 is a non-zero numeric value above 850: the 1245 pre-edit passes, so 1275 reports it.
        ValidationResult result = rule.validate(LABEL, "851");

        assertThat(result.isValid()).as("851 is above 850 and must be invalid").isFalse();
        assertThat(result.message()).isEqualTo(RANGE_MESSAGE);
    }

    // ---------------------------------------------------------------------------------------------
    // Numeric-required pre-edit (1245) failures -> that paragraph's message, verbatim.
    // These prove the 1245 pre-edit runs BEFORE the 1275 range check (evaluation-order parity).
    // ---------------------------------------------------------------------------------------------

    @Test
    void nullInputIsRejectedByNumericPreEditAsNotSupplied() {
        // null models COBOL LOW-VALUES: the not-supplied guard opens 1245-EDIT-NUM-REQD.
        ValidationResult result = rule.validate(LABEL, null);

        assertThat(result.isValid()).as("null (LOW-VALUES) must be invalid").isFalse();
        assertThat(result.message())
                .as("blank must report the 1245 not-supplied message, not the range message")
                .isEqualTo("FICO Score must be supplied.");
    }

    @Test
    void blankInputIsRejectedByNumericPreEditAsNotSupplied() {
        // All-whitespace models COBOL SPACES / a trim length of zero.
        ValidationResult result = rule.validate(LABEL, "   ");

        assertThat(result.isValid()).as("all-whitespace (SPACES) must be invalid").isFalse();
        assertThat(result.message()).isEqualTo("FICO Score must be supplied.");
    }

    @Test
    void nonNumericInputIsRejectedByNumericPreEditAsAllNumeric() {
        // "12A" fails the IS NUMERIC class test in 1245 before the range check is ever reached.
        ValidationResult result = rule.validate(LABEL, "12A");

        assertThat(result.isValid()).as("12A is not all numeric and must be invalid").isFalse();
        assertThat(result.message())
                .as("non-numeric must report the 1245 numeric message, not the range message")
                .isEqualTo("FICO Score must be all numeric.");
    }

    @Test
    void zeroIsRejectedByNumericPreEditNotByRangeCheck() {
        // CRITICAL ORDERING ASSERTION: "000" is numerically < 300, so a naive range-only rule would
        // emit the range message. The COBOL performs 1245-EDIT-NUM-REQD FIRST (L1549) and only runs
        // 1275 when FLG-FICO-SCORE-ISVALID (L1553); zero fails the 1245 non-zero check and must
        // therefore report "must not be zero.", never the range message.
        ValidationResult result = rule.validate(LABEL, "000");

        assertThat(result.isValid()).as("000 must be invalid").isFalse();
        assertThat(result.message())
                .as("zero must report the 1245 non-zero message, never the range message")
                .isEqualTo("FICO Score must not be zero.");
    }

    // ---------------------------------------------------------------------------------------------
    // Robustness: label trimming, PIC 9(03) width bound (never throws), statelessness
    // ---------------------------------------------------------------------------------------------

    @Test
    void fieldLabelIsTrimmedInRangeMessage() {
        // FUNCTION TRIM(WS-EDIT-VARIABLE-NAME): leading/trailing label spaces are removed before the
        // literal is appended, so the message equals the un-padded RANGE_MESSAGE.
        ValidationResult result = rule.validate("  FICO Score  ", "851");

        assertThat(result.isValid()).isFalse();
        assertThat(result.message())
                .as("leading/trailing label spaces must be removed, matching FUNCTION TRIM")
                .isEqualTo(RANGE_MESSAGE);
    }

    @Test
    void overlongNonZeroInputIsOutOfRangeAndNeverThrows() {
        // A value wider than the PIC 9(03) field cannot be a valid FICO score. If the internal parse
        // ran unguarded, a very long all-digit value could overflow int; the rule instead treats
        // length > 3 as out of range and returns a result (a thrown exception would fail this test),
        // honoring the ValidationRule "never throw for a validation failure" contract.
        ValidationResult fourDigits = rule.validate(LABEL, "1000");
        assertThat(fourDigits.isValid()).as("1000 exceeds the three-digit field").isFalse();
        assertThat(fourDigits.message()).isEqualTo(RANGE_MESSAGE);

        ValidationResult veryLong = rule.validate(LABEL, "99999999999");
        assertThat(veryLong.isValid()).as("an oversized all-digit value must not throw").isFalse();
        assertThat(veryLong.message()).isEqualTo(RANGE_MESSAGE);
    }

    @Test
    void ruleIsStatelessAcrossSuccessiveMixedInvocations() {
        // Interleave valid, out-of-range, and pre-edit failures to prove no state leaks between calls.
        assertThat(rule.validate(LABEL, "700").isValid()).isTrue();
        assertThat(rule.validate(LABEL, "851").isValid()).isFalse();
        assertThat(rule.validate(LABEL, "300").isValid()).isTrue();
        assertThat(rule.validate(LABEL, "000").isValid()).isFalse();
        assertThat(rule.validate(LABEL, null).isValid()).isFalse();
        // The final valid call still returns the canonical singleton, confirming no drift.
        assertThat(rule.validate(LABEL, "850")).isSameAs(ValidationResult.valid());
    }
}
