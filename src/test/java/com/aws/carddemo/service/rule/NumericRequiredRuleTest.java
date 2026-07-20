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
 * Unit tests for {@link NumericRequiredRule}, the Java migration of the COBOL edit paragraph
 * {@code 1245-EDIT-NUM-REQD} in {@code legacy/cbl/COACTUPC.cbl} (source-branch
 * {@code app/cbl/COACTUPC.cbl:L2109-L2178}).
 *
 * <p>COBOL evidence (verified against {@code legacy/cbl/COACTUPC.cbl}): the paragraph runs three
 * edits <em>in order</em>, latching the first failure's screen message and branching to its exit
 * ({@code GO TO 1245-EDIT-NUM-REQD-EXIT}):</p>
 * <ol>
 *   <li><strong>Not supplied</strong> ({@code LOW-VALUES}/{@code SPACES}/{@code TRIM} length 0,
 *       {@code L2114-L2119}) &rarr; {@code "<field> must be supplied."} ({@code L2124-L2126}).</li>
 *   <li><strong>Not numeric</strong> (fails {@code IS NUMERIC}, {@code L2137-L2138}) &rarr;
 *       {@code "<field> must be all numeric."} ({@code L2144-L2146}).</li>
 *   <li><strong>Zero</strong> ({@code FUNCTION NUMVAL(...) = 0}, {@code L2156-L2157}) &rarr;
 *       {@code "<field> must not be zero."} ({@code L2161-L2163}).</li>
 * </ol>
 * <p>All three passing &rarr; valid ({@code SET FLG-ALPHNANUM-ISVALID TO TRUE}, {@code L2174}).
 * The {@code <field>} token is {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} &mdash; the
 * caller-supplied label with leading/trailing whitespace removed &mdash; and every message ends
 * with a period.</p>
 *
 * <p>These tests lock down that parity: the ordered, short-circuiting supplied &rarr; numeric
 * &rarr; non-zero sequence, the verbatim period-terminated messages (asserted exactly as a
 * preserved parity contract), the buffer-slice semantics under which any embedded/trailing space
 * makes a value non-numeric, and the {@code FUNCTION TRIM} label normalization. This is a pure
 * JUnit&nbsp;5 + AssertJ unit test &mdash; no Spring context, no {@code @SpringBootTest}, no
 * Mockito, and no database (the rule is stateless and has no collaborators, so it is constructed
 * directly with {@code new NumericRequiredRule()}).</p>
 */
class NumericRequiredRuleTest {

    /** The stateless rule under test, freshly constructed before each case. */
    private NumericRequiredRule rule;

    @BeforeEach
    void setUp() {
        // Stateless, no collaborators -> plain construction (no Mockito, no Spring).
        rule = new NumericRequiredRule();
    }

    // ---------------------------------------------------------------------------------------------
    // Check 1 - Not supplied (COBOL L2114-L2119) -> "<field> must be supplied."
    // ---------------------------------------------------------------------------------------------

    @Test
    void nullValueIsNotSupplied() {
        // null models COBOL LOW-VALUES: fails the not-supplied guard first.
        ValidationResult result = rule.validate("Account Id", null);

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.isValid()).isFalse();
        assertThat(result.message()).isEqualTo("Account Id must be supplied.");
    }

    @Test
    void emptyValueIsNotSupplied() {
        // "" models COBOL SPACES / TRIM length 0: fails the not-supplied guard.
        ValidationResult result = rule.validate("Account Id", "");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Account Id must be supplied.");
    }

    @Test
    void whitespaceValueIsNotSupplied() {
        // An all-whitespace value trims to length 0, matching COBOL SPACES.
        ValidationResult result = rule.validate("Account Id", "   ");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Account Id must be supplied.");
    }

    // ---------------------------------------------------------------------------------------------
    // Check 2 - Only all numeric allowed (COBOL L2137-L2138) -> "<field> must be all numeric."
    // ---------------------------------------------------------------------------------------------

    @Test
    void embeddedLetterIsNotAllNumeric() {
        // Supplied but contains a letter: passes check 1, fails the IS NUMERIC class test.
        ValidationResult result = rule.validate("Account Id", "12A");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Account Id must be all numeric.");
    }

    @Test
    void trailingSpaceIsNotAllNumeric() {
        // The value models a fixed-width buffer slice as entered; a trailing space is significant
        // and makes the slice non-numeric, exactly as COBOL IS NUMERIC behaves.
        ValidationResult result = rule.validate("Account Id", "12 ");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Account Id must be all numeric.");
    }

    // ---------------------------------------------------------------------------------------------
    // Check 3 - Must not be zero (COBOL L2156-L2157) -> "<field> must not be zero."
    // ---------------------------------------------------------------------------------------------

    @Test
    void allZeroValueIsRejected() {
        // Supplied and all-numeric, but NUMVAL evaluates to zero: fails the non-zero check.
        ValidationResult result = rule.validate("Account Id", "000");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Account Id must not be zero.");
    }

    // ---------------------------------------------------------------------------------------------
    // All three checks pass (COBOL L2174: SET FLG-ALPHNANUM-ISVALID TO TRUE)
    // ---------------------------------------------------------------------------------------------

    @Test
    void suppliedNonZeroNumericIsValid() {
        // Supplied, all-numeric, and non-zero -> valid; a valid result carries the empty message.
        ValidationResult result = rule.validate("Account Id", "007");

        assertThat(result.isValid()).isTrue();
        assertThat(result.isInvalid()).isFalse();
        assertThat(result.message()).isEqualTo("");
    }

    // ---------------------------------------------------------------------------------------------
    // Field-label normalization: FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
    // ---------------------------------------------------------------------------------------------

    @Test
    void fieldLabelIsTrimmedIntoMessage() {
        // The label is trimmed before substitution; "abc" is non-numeric so the second check fires,
        // proving both the TRIM normalization and that the numeric check is reached.
        ValidationResult result = rule.validate("  FICO Score  ", "abc");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("FICO Score must be all numeric.");
    }

    // ---------------------------------------------------------------------------------------------
    // Ordering / short-circuit contract: supplied -> numeric -> zero
    // ---------------------------------------------------------------------------------------------

    @Test
    void checkOrderIsSuppliedThenNumericThenZero() {
        // For the SAME field, three inputs that each fail a different (successively later) check
        // must yield three DISTINCT messages, proving the ordered short-circuit: a blank value
        // (also non-numeric) reports "must be supplied" and never reaches the numeric check; a
        // non-numeric-but-supplied value reports "must be all numeric" and never reaches the zero
        // check; only a supplied, all-numeric, zero value reaches "must not be zero".
        ValidationResult supplied = rule.validate("Account Id", null);
        ValidationResult numeric = rule.validate("Account Id", "12A");
        ValidationResult zero = rule.validate("Account Id", "000");

        assertThat(supplied.message()).isEqualTo("Account Id must be supplied.");
        assertThat(numeric.message()).isEqualTo("Account Id must be all numeric.");
        assertThat(zero.message()).isEqualTo("Account Id must not be zero.");

        // All three are distinct -> the checks run in a fixed order and short-circuit on the first.
        assertThat(supplied.message()).isNotEqualTo(numeric.message());
        assertThat(supplied.message()).isNotEqualTo(zero.message());
        assertThat(numeric.message()).isNotEqualTo(zero.message());
    }
}
