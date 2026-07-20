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
 * Unit tests for {@link AlphaOptionalRule}, verifying behavioral parity with the COBOL edit
 * paragraph {@code 1235-EDIT-ALPHA-OPT} of {@code legacy/cbl/COACTUPC.cbl} (source
 * {@code app/cbl/COACTUPC.cbl:L2012-L2059}).
 *
 * <p>{@code AlphaOptionalRule} is the <em>optional</em> counterpart of {@code 1225-EDIT-ALPHA-REQD}.
 * Its single behavioral difference is that a blank (not-supplied) value is <strong>valid</strong>
 * and carries <em>no</em> message &mdash; there is deliberately no {@code "must be supplied."} error
 * for an optional field (see {@code legacy/cbl/COACTUPC.cbl:L2016-L2028}). When a value <em>is</em>
 * supplied it must consist solely of the 52 ASCII letters {@code A}&ndash;{@code Z} /
 * {@code a}&ndash;{@code z} and the space character (the {@code LIT-ALL-ALPHA-FROM-X} literal at
 * {@code legacy/cbl/COACTUPC.cbl:L586-L593}); any other character yields the verbatim screen message
 * {@code "<field> can have alphabets only."}, with its trailing period preserved.</p>
 *
 * <p>The rule is stateless and side-effect free, so it is exercised through a single
 * {@code new AlphaOptionalRule()} instance with no Spring context, no mocks, and no database
 * &mdash; a pure JUnit&nbsp;5 + AssertJ unit test. Every assertion pins the exact
 * {@link ValidationResult} contract ({@link ValidationResult#isValid()},
 * {@link ValidationResult#isInvalid()}, and the never-{@code null} {@link ValidationResult#message()}).</p>
 */
class AlphaOptionalRuleTest {

    /** Field label under test (COBOL {@code WS-EDIT-VARIABLE-NAME}); the agent-prompt fixture value. */
    private static final String FIELD = "Middle Name";

    /**
     * The exact COBOL screen message ({@code WS-RETURN-MSG}) emitted when a supplied value contains a
     * non-alphabetic, non-space character. Asserted verbatim, trailing period included.
     */
    private static final String EXPECTED_MESSAGE = "Middle Name can have alphabets only.";

    /** The rule under test; stateless, so a single shared instance is reused across every case. */
    private final AlphaOptionalRule rule = new AlphaOptionalRule();

    // ---------------------------------------------------------------------------------------------
    // Not-supplied (blank) path -> VALID with NO message. This is the defining optional-variant
    // behavior (legacy/cbl/COACTUPC.cbl:L2016-L2028) and the key difference from the required rule:
    // there is NO "must be supplied." message for an unsupplied optional field.
    // ---------------------------------------------------------------------------------------------

    @Test
    void nullValueIsValidWithNoMessage() {
        // null models COBOL LOW-VALUES: an optional field left unset is acceptable.
        ValidationResult result = rule.validate(FIELD, null);
        assertThat(result.isValid()).as("null (LOW-VALUES) is valid for an optional field").isTrue();
        assertThat(result.message()).as("an unsupplied optional field carries no message").isEmpty();
    }

    @Test
    void emptyValueIsValidWithNoMessage() {
        // "" models COBOL SPACES / a FUNCTION TRIM length of zero.
        ValidationResult result = rule.validate(FIELD, "");
        assertThat(result.isValid()).as("empty (SPACES / trim length 0) is valid").isTrue();
        assertThat(result.message()).isEmpty();
    }

    @Test
    void allWhitespaceValueIsValidWithNoMessage() {
        // "   " models COBOL SPACES: strip() reduces it to a zero-length string -> not supplied.
        ValidationResult result = rule.validate(FIELD, "   ");
        assertThat(result.isValid()).as("all-whitespace (trim length 0) is valid").isTrue();
        assertThat(result.message()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Supplied and alpha-only (letters and/or spaces) -> VALID.
    // ---------------------------------------------------------------------------------------------

    @Test
    void alphabeticValueIsValid() {
        ValidationResult result = rule.validate(FIELD, "Doe");
        assertThat(result.isValid()).as("letters only must pass the alpha-only check").isTrue();
        assertThat(result.message()).isEmpty();
    }

    @Test
    void lettersWithEmbeddedSpaceAreValid() {
        // The space character is part of the permitted set alongside the 52 letters.
        ValidationResult result = rule.validate(FIELD, "Mary Jane");
        assertThat(result.isValid()).as("space is permitted alongside the 52 letters").isTrue();
        assertThat(result.message()).isEmpty();
    }

    @Test
    void mixedCaseLettersAreValid() {
        // Exercises both LIT-UPPER (A-Z) and LIT-LOWER (a-z) halves of LIT-ALL-ALPHA-FROM-X.
        ValidationResult result = rule.validate(FIELD, "McArthur");
        assertThat(result.isValid()).as("both A-Z and a-z are accepted").isTrue();
        assertThat(result.message()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Supplied but containing a non-alphabetic, non-space character -> INVALID with exact message.
    // ---------------------------------------------------------------------------------------------

    @Test
    void valueWithDigitIsInvalidWithExactMessage() {
        ValidationResult result = rule.validate(FIELD, "Doe2");
        assertThat(result.isValid()).as("a digit is outside the alpha-only set").isFalse();
        assertThat(result.isInvalid()).as("isInvalid mirrors the COBOL INPUT-ERROR flag").isTrue();
        assertThat(result.message())
                .as("must emit the exact COBOL screen message with trailing period")
                .isEqualTo(EXPECTED_MESSAGE);
    }

    @Test
    void valueWithPunctuationIsInvalidWithExactMessage() {
        // A hyphen is neither a letter nor a space, so the alpha-only full match fails.
        ValidationResult result = rule.validate(FIELD, "Mary-Jane");
        assertThat(result.isInvalid()).as("a hyphen is a disallowed character").isTrue();
        assertThat(result.message()).isEqualTo(EXPECTED_MESSAGE);
    }

    // ---------------------------------------------------------------------------------------------
    // Field-label normalization (COBOL FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)) into the message.
    // ---------------------------------------------------------------------------------------------

    @Test
    void fieldLabelIsTrimmedIntoMessage() {
        // A padded label is stripped before substitution, matching FUNCTION TRIM.
        ValidationResult result = rule.validate("  Middle Name  ", "Doe2");
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message())
                .as("leading/trailing label whitespace is removed (FUNCTION TRIM)")
                .isEqualTo(EXPECTED_MESSAGE);
    }

    @Test
    void nullFieldLabelProducesEmptyPrefix() {
        // A null label normalizes to the empty string (ValidationRule.label), so the message has no
        // field prefix but still ends with the verbatim clause and its trailing period.
        ValidationResult result = rule.validate(null, "Doe2");
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(" can have alphabets only.");
    }
}
