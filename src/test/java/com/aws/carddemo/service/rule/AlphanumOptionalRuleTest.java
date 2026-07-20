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
 * Unit tests for {@link AlphanumOptionalRule}, the Java migration of the COBOL edit paragraph
 * {@code 1240-EDIT-ALPHANUM-OPT} in {@code legacy/cbl/COACTUPC.cbl} (source-branch
 * {@code app/cbl/COACTUPC.cbl:L2061-L2107}).
 *
 * <p>COBOL evidence (verified against {@code app/cbl/COACTUPC.cbl}): the paragraph is the
 * <em>optional</em> counterpart of {@code 1230-EDIT-ALPHANUM-REQD}. It first accepts a
 * not-supplied field &mdash; {@code LOW-VALUES}, {@code SPACES}, or a {@code FUNCTION TRIM}
 * length of zero (L2066-L2073) &mdash; as <strong>valid</strong> with no message, because the
 * field is optional; it therefore never emits the "must be supplied." text. When a value is
 * present the paragraph performs {@code INSPECT ... CONVERTING} of the 62 ASCII alphanumeric
 * characters to spaces and tests whether the trimmed residue length is zero (L2078-L2087); a
 * non-empty residue sets {@code INPUT-ERROR} and builds the screen message
 * {@code STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) ' can have numbers or alphabets only.'
 * ... INTO WS-RETURN-MSG} (L2090-L2098).</p>
 *
 * <p>These tests lock down that parity: a {@code null}, empty, or all-whitespace value is valid
 * and carries the empty (never-{@code null}) message; a value made only of ASCII letters, ASCII
 * digits, and spaces is valid; any other character is invalid with the exact, period-terminated
 * message {@code "<field> can have numbers or alphabets only."}, which is a preserved parity
 * contract and is asserted verbatim. The ASCII-only character set
 * ({@code app/cbl/COACTUPC.cbl:L586-L593}) is confirmed by a non-ASCII Unicode counter-example
 * that {@link Character#isLetterOrDigit(char)} would wrongly accept. This is a pure JUnit&nbsp;5 +
 * AssertJ unit test &mdash; no Spring context, no Mockito, and no database (the rule is stateless).
 */
class AlphanumOptionalRuleTest {

    /** Field label under test (the COBOL {@code WS-EDIT-VARIABLE-NAME}). */
    private static final String FIELD = "Address Line 2";

    /** The exact, verbatim COBOL screen message ({@code WS-RETURN-MSG}) for a residue failure. */
    private static final String EXPECTED_MESSAGE = "Address Line 2 can have numbers or alphabets only.";

    /** The stateless rule under test; safe to reuse across cases (constructed directly, no Spring). */
    private final AlphanumOptionalRule rule = new AlphanumOptionalRule();

    @Test
    void blankValuesAreValidWithNoMessage() {
        // null models COBOL LOW-VALUES; "" and "   " model SPACES / FUNCTION TRIM length 0
        // (legacy/cbl/COACTUPC.cbl:L2066-L2073). All are valid for an OPTIONAL field, and the
        // optional paragraph never builds a message (no "must be supplied.").
        ValidationResult fromNull = rule.validate(FIELD, null);
        assertThat(fromNull.isValid()).as("null (LOW-VALUES) is valid for an optional field").isTrue();
        assertThat(fromNull.message()).as("an unsupplied optional field carries no message").isEmpty();

        ValidationResult fromEmpty = rule.validate(FIELD, "");
        assertThat(fromEmpty.isValid()).as("empty (SPACES / trim length 0) is valid").isTrue();
        assertThat(fromEmpty.message()).isEmpty();

        ValidationResult fromSpaces = rule.validate(FIELD, "   ");
        assertThat(fromSpaces.isValid()).as("all-whitespace (trim length 0) is valid").isTrue();
        assertThat(fromSpaces.message()).isEmpty();
    }

    @Test
    void alphanumericWithSpaceIsValid() {
        // "Suite 200" -> letters, an embedded space, and digits are all in the allowed set
        // {A-Z, a-z, 0-9, space}, so the INSPECT leaves a zero-length trimmed residue -> valid.
        ValidationResult result = rule.validate(FIELD, "Suite 200");
        assertThat(result.isValid()).as("letters, a space, and digits are all allowed").isTrue();
        assertThat(result.message()).isEmpty();
    }

    @Test
    void slashCharacterIsInvalidWithExactMessage() {
        // "C/O Jane" contains '/', which is neither alphanumeric nor a space; the residue is
        // non-empty, so INPUT-ERROR is set and WS-RETURN-MSG is built verbatim (L2090-L2098).
        ValidationResult result = rule.validate(FIELD, "C/O Jane");
        assertThat(result.isInvalid()).as("'/' is neither alphanumeric nor a space").isTrue();
        assertThat(result.isValid()).isFalse();
        assertThat(result.message()).as("message must match COBOL WS-RETURN-MSG verbatim")
                .isEqualTo(EXPECTED_MESSAGE);
    }

    @Test
    void lettersImmediatelyFollowedByDigitIsValid() {
        // "Bldg7" -> adjacent letters and a digit are all in the allowed set -> valid.
        ValidationResult result = rule.validate(FIELD, "Bldg7");
        assertThat(result.isValid()).as("adjacent letters and digits are allowed").isTrue();
        assertThat(result.message()).isEmpty();
    }

    @Test
    void pureLettersDigitsAndInteriorSpacesAreValid() {
        // Each of these consists solely of the allowed set {A-Z, a-z, 0-9, space}.
        assertThat(rule.validate(FIELD, "MainStreet").isValid()).as("all letters -> valid").isTrue();
        assertThat(rule.validate(FIELD, "1234567890").isValid()).as("all digits -> valid").isTrue();
        assertThat(rule.validate(FIELD, "Main Street West").isValid())
                .as("letters with interior spaces -> valid").isTrue();
    }

    @Test
    void punctuationAndSymbolsAreInvalidWithExactMessage() {
        // Every value below contains exactly one disallowed character; because FIELD is fixed,
        // each failure must produce the identical verbatim message.
        String[] disallowed = {
                "12-34",     // hyphen
                "St. Paul",  // period
                "A,B",       // comma
                "user@host", // at-sign
                "50%",       // percent
                "#7",        // hash
                "(200)",     // parentheses
                "a_b"        // underscore
        };
        for (String value : disallowed) {
            ValidationResult result = rule.validate(FIELD, value);
            assertThat(result.isInvalid()).as("value '%s' contains a disallowed character", value).isTrue();
            assertThat(result.message()).as("message for '%s'", value).isEqualTo(EXPECTED_MESSAGE);
        }
    }

    @Test
    void paddedFieldLabelIsTrimmedIntoMessage() {
        // The label is normalized with FUNCTION TRIM semantics (ValidationRule.label -> String.strip),
        // so leading/trailing whitespace on the label does not leak into WS-RETURN-MSG.
        ValidationResult result = rule.validate("  Address Line 2  ", "C/O Jane");
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).as("label is normalized with FUNCTION TRIM semantics")
                .isEqualTo(EXPECTED_MESSAGE);
    }

    @Test
    void nullFieldLabelYieldsBareMessage() {
        // A null label normalizes to the empty string (ValidationRule.label), so the message is the
        // bare literal ' can have numbers or alphabets only.' with its leading space preserved.
        ValidationResult result = rule.validate(null, "C/O Jane");
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).as("null label normalizes to the empty prefix")
                .isEqualTo(" can have numbers or alphabets only.");
    }

    @Test
    void nonAsciiUnicodeLetterIsInvalid() {
        // "café" contains U+00E9; Character.isLetterOrDigit would accept it, but the COBOL A-Za-z0-9
        // class test (app/cbl/COACTUPC.cbl:L586-L593) does not, so the rule must reject it to keep
        // strict behavioral parity with the fixed 62-character ASCII conversion set.
        ValidationResult result = rule.validate(FIELD, "caf\u00e9");
        assertThat(result.isInvalid()).as("a non-ASCII letter must be rejected for COBOL parity").isTrue();
        assertThat(result.message()).isEqualTo(EXPECTED_MESSAGE);
    }
}
