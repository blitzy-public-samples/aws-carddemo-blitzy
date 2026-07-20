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
 * Unit tests for {@link AlphaRequiredRule}, asserting to-the-character behavioral parity with the
 * COBOL edit paragraph {@code 1225-EDIT-ALPHA-REQD} of {@code legacy/cbl/COACTUPC.cbl}
 * (source-branch {@code app/cbl/COACTUPC.cbl}, lines L1898&ndash;L1953).
 *
 * <p>{@code AlphaRequiredRule} is the mandatory ("required") alphabetic-field edit: it runs two
 * checks in a fixed order and stops at the first failure, then otherwise reports the field valid.
 * These tests pin down that exact parity contract:</p>
 * <ol>
 *   <li><b>Not supplied (checked first).</b> A {@code null} (COBOL {@code LOW-VALUES}), empty, or
 *       all-whitespace (COBOL {@code SPACES} / {@code FUNCTION TRIM} length&nbsp;0) value is invalid
 *       with the verbatim screen message {@code "<field> must be supplied."} &mdash; trailing period
 *       included.</li>
 *   <li><b>Alphabets and space only.</b> Otherwise, a value containing any character other than an
 *       ASCII letter ({@code A}&ndash;{@code Z}/{@code a}&ndash;{@code z}, the 52-character
 *       {@code LIT-ALL-ALPHA-FROM-X} set at {@code legacy/cbl/COACTUPC.cbl:L586-L593}) or a space is
 *       invalid with the verbatim screen message {@code "<field> can have alphabets only."} &mdash;
 *       trailing period included.</li>
 *   <li><b>Otherwise valid.</b> A value made up solely of letters and spaces (including leading,
 *       interior, and trailing spaces) is valid and carries the empty message.</li>
 * </ol>
 *
 * <p>The rule is stateless and side-effect free, so a single instance is constructed directly with
 * {@code new AlphaRequiredRule()} &mdash; there is no Spring context, no Mockito, and no database.
 * The field label {@code "First Name"} is used throughout; because
 * {@link ValidationRule#label(String)} merely trims the label (COBOL
 * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)}) and {@code "First Name"} has no surrounding
 * whitespace, the substituted messages are exactly {@code "First Name must be supplied."} and
 * {@code "First Name can have alphabets only."}. Every message is asserted verbatim so that any
 * wording, punctuation, or evaluation-order drift is caught.</p>
 */
class AlphaRequiredRuleTest {

    /** The rule under test; stateless, so a single instance is reused across every case. */
    private final AlphaRequiredRule rule = new AlphaRequiredRule();

    /**
     * A {@code null} value models COBOL {@code LOW-VALUES} and must fail the not-supplied check
     * first (before the alpha-only check), yielding the verbatim {@code "must be supplied."} message.
     */
    @Test
    void nullValueIsNotSupplied() {
        ValidationResult result = rule.validate("First Name", null);

        assertThat(result.isValid()).isFalse();
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("First Name must be supplied.");
    }

    /**
     * An empty string models COBOL {@code SPACES} / a {@code FUNCTION TRIM} length of zero and must
     * fail the not-supplied check with the verbatim {@code "must be supplied."} message.
     */
    @Test
    void emptyValueIsNotSupplied() {
        ValidationResult result = rule.validate("First Name", "");

        assertThat(result.isValid()).isFalse();
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("First Name must be supplied.");
    }

    /**
     * An all-whitespace value models COBOL {@code SPACES}; the not-supplied guard strips it to an
     * empty residue and reports {@code "must be supplied."} rather than the alpha-only message,
     * confirming the blank check takes precedence over the character-set check.
     */
    @Test
    void blankSpacesValueIsNotSupplied() {
        ValidationResult result = rule.validate("First Name", "   ");

        assertThat(result.isValid()).isFalse();
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("First Name must be supplied.");
    }

    /**
     * Letters with an embedded space ({@code "John Smith"}) are valid: a space is a permitted
     * character alongside the 52 letters, so the value passes the alpha-only check and carries the
     * empty message.
     */
    @Test
    void lettersWithEmbeddedSpaceAreValid() {
        ValidationResult result = rule.validate("First Name", "John Smith");

        assertThat(result.isValid()).isTrue();
        assertThat(result.isInvalid()).isFalse();
        assertThat(result.message()).isEmpty();
    }

    /**
     * A value containing a digit ({@code "John1"}) is supplied but not alphabetic; the digit is
     * outside the {@code [A-Za-z ]} set, so the rule reports the verbatim
     * {@code "can have alphabets only."} message.
     */
    @Test
    void letterWithDigitIsInvalid() {
        ValidationResult result = rule.validate("First Name", "John1");

        assertThat(result.isValid()).isFalse();
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("First Name can have alphabets only.");
    }

    /**
     * A value containing an apostrophe ({@code "O'Brien"}) is invalid: the apostrophe is neither a
     * letter nor a space, so it fails the alpha-only check with the verbatim
     * {@code "can have alphabets only."} message.
     */
    @Test
    void apostropheIsInvalid() {
        ValidationResult result = rule.validate("First Name", "O'Brien");

        assertThat(result.isValid()).isFalse();
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("First Name can have alphabets only.");
    }

    /**
     * Letters surrounded by leading and trailing spaces ({@code "  Jane  "}) are valid: the regex
     * {@code ^[A-Za-z ]*$} permits spaces anywhere, mirroring the legacy convert-then-{@code TRIM}
     * result where surrounding spaces are removed and only letters remain.
     */
    @Test
    void leadingAndTrailingSpacesAreValid() {
        ValidationResult result = rule.validate("First Name", "  Jane  ");

        assertThat(result.isValid()).isTrue();
        assertThat(result.isInvalid()).isFalse();
        assertThat(result.message()).isEmpty();
    }
}
