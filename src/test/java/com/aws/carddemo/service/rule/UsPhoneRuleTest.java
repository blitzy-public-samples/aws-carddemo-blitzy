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
 * Unit tests for {@link UsPhoneRule}, the Java reproduction of the COBOL edit paragraph
 * {@code 1260-EDIT-US-PHONE-NUM} of {@code legacy/cbl/COACTUPC.cbl} (lines L2225&ndash;L2429),
 * including the {@code VALID-GENERAL-PURP-CODE} area-code lookup embedded from
 * {@code legacy/cpy/CSLKPCDY.cpy}.
 *
 * <p>Each test asserts the exact screen message the legacy program produced, so any message drift
 * is caught. Pay particular attention to the verbatim literals: the capital {@code "A"} in
 * "must be A N digit number.", the trailing period on the "supplied." and "digit number." messages,
 * and the deliberate <em>absence</em> of a trailing period on the "cannot be zero" and
 * "Not valid North America ..." messages.</p>
 *
 * <p>The suite covers the optional-when-all-blank behaviour, the ordered
 * area&nbsp;&rarr;&nbsp;prefix&nbsp;&rarr;&nbsp;line first-message-wins short-circuit, the
 * fixed-width digit-count edits, the zero-value edits, the general-purpose area-code membership
 * lookup (in-set {@code 201} versus out-of-set {@code 211}), and the {@code FUNCTION TRIM} field-
 * label normalization. {@link UsPhoneRule} is stateless, so it is exercised through a plain
 * {@code new UsPhoneRule()} instance with no Spring context, Mockito, or database.</p>
 */
class UsPhoneRuleTest {

    /** The field label substituted into every outcome message throughout this suite. */
    private static final String FIELD = "Phone";

    /** The stateless rule under test, constructed directly (no Spring, no mocks). */
    private final UsPhoneRule rule = new UsPhoneRule();

    // ---------------------------------------------------------------------------------------------
    // Optional-when-all-blank (legacy/cbl/COACTUPC.cbl:L2234-L2244, intent-preserving).
    // ---------------------------------------------------------------------------------------------

    @Test
    void allPartsNull_isOptionalAndValid() {
        // null parts model COBOL LOW-VALUES; an entirely absent phone number is not mandatory.
        assertThat(rule.validate(FIELD, null, null, null).isValid())
                .as("null area/prefix/line (LOW-VALUES) -> phone number is optional")
                .isTrue();
    }

    @Test
    void allPartsEmpty_isOptionalAndValid() {
        // empty parts model COBOL SPACES.
        assertThat(rule.validate(FIELD, "", "", "").isValid())
                .as("empty area/prefix/line (SPACES) -> phone number is optional")
                .isTrue();
    }

    @Test
    void allPartsWhitespace_isOptionalAndValid() {
        // all-whitespace parts are blank once stripped (ValidationRule.isBlank uses String.strip()).
        assertThat(rule.validate(FIELD, "   ", " ", "  ").isValid())
                .as("all-whitespace area/prefix/line -> blank -> phone number is optional")
                .isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // Fully valid number.
    // ---------------------------------------------------------------------------------------------

    @Test
    void fullyValidNumber_isValid() {
        // 201 is a member of the general-purpose set; 555 and 0123 are non-zero fixed-width digits.
        ValidationResult result = rule.validate(FIELD, "201", "555", "0123");
        assertThat(result.isValid())
                .as("201/555/0123 must pass every sub-edit")
                .isTrue();
        assertThat(result.message())
                .as("a valid result carries the empty message")
                .isEqualTo("");
    }

    // ---------------------------------------------------------------------------------------------
    // Area-code sub-edit (EDIT-AREA-CODE, legacy/cbl/COACTUPC.cbl:L2246-L2315).
    // ---------------------------------------------------------------------------------------------

    @Test
    void areaCode_notInGeneralPurposeSet_isInvalid() {
        // 211 is a 3-digit, non-zero code that is NOT in VALID-GENERAL-PURP-CODE.
        ValidationResult result = rule.validate(FIELD, "211", "555", "0123");
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message())
                .isEqualTo("Phone: Not valid North America general purpose area code");
    }

    @Test
    void areaCode_zero_isInvalid() {
        ValidationResult result = rule.validate(FIELD, "000", "555", "0123");
        assertThat(result.isInvalid()).isTrue();
        // NO trailing period on the "cannot be zero" message.
        assertThat(result.message()).isEqualTo("Phone: Area code cannot be zero");
    }

    @Test
    void areaCode_tooShort_isInvalid() {
        // A short entry is space-padded in the fixed-width field and therefore fails IS NUMERIC.
        ValidationResult result = rule.validate(FIELD, "20", "555", "0123");
        assertThat(result.isInvalid()).isTrue();
        // Capital "A" and a trailing period.
        assertThat(result.message()).isEqualTo("Phone: Area code must be A 3 digit number.");
    }

    @Test
    void areaCode_nonNumeric_isInvalid() {
        ValidationResult result = rule.validate(FIELD, "2a1", "555", "0123");
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Phone: Area code must be A 3 digit number.");
    }

    @Test
    void areaCode_blankWhenOtherPartsPresent_reportsMustBeSupplied() {
        // An empty area code with a present prefix/line is no longer "optional"; it must be supplied.
        ValidationResult empty = rule.validate(FIELD, "", "555", "0123");
        assertThat(empty.isInvalid()).isTrue();
        assertThat(empty.message()).isEqualTo("Phone: Area code must be supplied.");

        // A null area code (LOW-VALUES) with present other parts behaves identically.
        ValidationResult nullArea = rule.validate(FIELD, null, "555", "0123");
        assertThat(nullArea.isInvalid()).isTrue();
        assertThat(nullArea.message()).isEqualTo("Phone: Area code must be supplied.");
    }

    // ---------------------------------------------------------------------------------------------
    // Prefix sub-edit (EDIT-US-PHONE-PREFIX, legacy/cbl/COACTUPC.cbl:L2316-L2368).
    // ---------------------------------------------------------------------------------------------

    @Test
    void prefix_zero_isInvalid() {
        ValidationResult result = rule.validate(FIELD, "201", "000", "0123");
        assertThat(result.isInvalid()).isTrue();
        // NO trailing period.
        assertThat(result.message()).isEqualTo("Phone: Prefix code cannot be zero");
    }

    @Test
    void prefix_blankWhenAreaValid_reportsMustBeSupplied() {
        ValidationResult result = rule.validate(FIELD, "201", "", "0123");
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Phone: Prefix code must be supplied.");
    }

    @Test
    void prefix_nonNumericWhenAreaValid_isInvalid() {
        ValidationResult result = rule.validate(FIELD, "201", "5x5", "0123");
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Phone: Prefix code must be A 3 digit number.");
    }

    // ---------------------------------------------------------------------------------------------
    // Line-number sub-edit (EDIT-US-PHONE-LINENUM, legacy/cbl/COACTUPC.cbl:L2370-L2422).
    // ---------------------------------------------------------------------------------------------

    @Test
    void lineNumber_tooShortWhenAreaAndPrefixValid_isInvalid() {
        ValidationResult result = rule.validate(FIELD, "201", "555", "12");
        assertThat(result.isInvalid()).isTrue();
        // Capital "A" and a trailing period.
        assertThat(result.message()).isEqualTo("Phone: Line number code must be A 4 digit number.");
    }

    @Test
    void lineNumber_zeroWhenAreaAndPrefixValid_isInvalid() {
        ValidationResult result = rule.validate(FIELD, "201", "555", "0000");
        assertThat(result.isInvalid()).isTrue();
        // NO trailing period.
        assertThat(result.message()).isEqualTo("Phone: Line number code cannot be zero");
    }

    @Test
    void lineNumber_blankWhenAreaAndPrefixValid_reportsMustBeSupplied() {
        ValidationResult result = rule.validate(FIELD, "201", "555", "");
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Phone: Line number code must be supplied.");
    }

    // ---------------------------------------------------------------------------------------------
    // Cross-cutting: first-message-wins ordering and field-label trimming.
    // ---------------------------------------------------------------------------------------------

    @Test
    void firstMessageWins_areaFailureMasksLaterPrefixFailure() {
        // Both area ('000' -> zero) and prefix ('000' -> zero) would fail; only the AREA message shows,
        // reproducing the legacy first-message-wins latch (WS-RETURN-MSG-OFF) across the sub-edits.
        ValidationResult result = rule.validate(FIELD, "000", "000", "0123");
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Phone: Area code cannot be zero");
    }

    @Test
    void fieldLabelIsTrimmedIntoMessage() {
        // FUNCTION TRIM(WS-EDIT-VARIABLE-NAME): a padded label is stripped before substitution.
        ValidationResult result = rule.validate("  Phone  ", "20", "555", "0123");
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Phone: Area code must be A 3 digit number.");
    }
}
