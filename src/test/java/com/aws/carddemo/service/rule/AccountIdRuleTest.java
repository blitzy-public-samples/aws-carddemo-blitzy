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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AccountIdRule}, verifying byte-for-byte parity with the legacy account-id
 * edit paragraphs {@code 1210-EDIT-ACCOUNT} ({@code legacy/cbl/COACTUPC.cbl}) and
 * {@code 2210-EDIT-ACCOUNT} ({@code legacy/cbl/COACTVWC.cbl}).
 *
 * <p>The two paragraphs share identical logic but emit two different screen messages; the filter
 * message additionally carries a legacy typo (a double space in {@code "must  be"}) that must be
 * reproduced exactly. These tests assert both the validation behavior and the exact message
 * literals, including an explicit proof that the filter message length reflects the two-space
 * typo.</p>
 */
class AccountIdRuleTest {

    /** The exact filter-path message (COACTVWC 2210, L672) &mdash; note the double space. */
    private static final String EXPECTED_FILTER_MESSAGE = "Account Filter must  be a non-zero 11 digit number";

    /** The exact update-path message (COACTUPC 1210, L1806-1808). */
    private static final String EXPECTED_UPDATE_MESSAGE = "Account Number if supplied must be a 11 digit Non-Zero Number";

    private final AccountIdRule rule = new AccountIdRule();

    // ------------------------------------------------------------------
    // (a) validate(any, valid) -> valid
    // ------------------------------------------------------------------

    @Test
    @DisplayName("(a) validate() accepts a valid non-zero 11-digit account id")
    void validate_validElevenDigit_isValid() {
        ValidationResult result = rule.validate("Account Filter", "00000000123");

        assertThat(result.isValid()).isTrue();
        assertThat(result.message()).isEmpty();
    }

    // ------------------------------------------------------------------
    // (b) validate(any, all-zeros) -> invalid, EXACT filter message (double space)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("(b) validate() rejects an all-zero id with the exact filter message (double space preserved)")
    void validate_allZeros_isInvalidWithExactFilterMessage() {
        ValidationResult result = rule.validate("Account Filter", "00000000000");

        assertThat(result.isInvalid()).isTrue();
        // Verbatim parity contract, including the legacy double space between "must" and "be".
        assertThat(result.message()).isEqualTo("Account Filter must  be a non-zero 11 digit number");
    }

    @Test
    @DisplayName("filter message length (50) reflects the legacy two-space typo (single-space form is 49)")
    void filterMessage_lengthProvesDoubleSpaceTypo() {
        ValidationResult result = rule.validate("Account Filter", "00000000000");
        String message = result.message();

        // The verbatim message is 50 chars; the region "must  be" contains two spaces.
        assertThat(message).hasSize(50);
        assertThat(message).contains("must  be");
        assertThat(message).doesNotContain("must be a non-zero");
        // Collapsing the double space to a single space removes exactly one character (50 -> 49),
        // proving the typo adds precisely one space beyond a "corrected" single-space form.
        assertThat(message.replaceAll(" {2}", " ")).hasSize(49);
        assertThat(EXPECTED_FILTER_MESSAGE).hasSize(50);
        // Explicit typo lock: the verbatim literal must NOT equal the "corrected" single-space form.
        assertThat(EXPECTED_FILTER_MESSAGE)
                .isNotEqualTo("Account Filter must be a non-zero 11 digit number");
    }

    // ------------------------------------------------------------------
    // (c) validate(any, wrong length) -> invalid filter message
    // ------------------------------------------------------------------

    @Test
    @DisplayName("(c) validate() rejects a too-short id (not 11 chars) with the filter message")
    void validate_wrongLength_isInvalidFilterMessage() {
        ValidationResult result = rule.validate("Account Filter", "1234");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(EXPECTED_FILTER_MESSAGE);
    }

    @Test
    @DisplayName("validate() rejects a too-long id (more than 11 chars) with the filter message")
    void validate_tooLong_isInvalidFilterMessage() {
        ValidationResult result = rule.validate("Account Filter", "000000001234");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(EXPECTED_FILTER_MESSAGE);
    }

    // ------------------------------------------------------------------
    // (d) validate(any, non-digit) -> invalid filter message
    // ------------------------------------------------------------------

    @Test
    @DisplayName("(d) validate() rejects a non-digit id with the filter message")
    void validate_nonDigit_isInvalidFilterMessage() {
        ValidationResult result = rule.validate("Account Filter", "1234abc8901");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(EXPECTED_FILTER_MESSAGE);
    }

    @Test
    @DisplayName("validate() rejects an embedded space (breaks IS NUMERIC) with the filter message")
    void validate_embeddedSpace_isInvalidFilterMessage() {
        ValidationResult result = rule.validate("Account Filter", "12345 78901");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(EXPECTED_FILTER_MESSAGE);
    }

    // ------------------------------------------------------------------
    // (e) blank / null / spaces -> valid (soft prompt state)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("(e) validate() treats null/empty/all-spaces as valid (soft prompt/no-search state)")
    void validate_blankInputs_isValid() {
        assertThat(rule.validate("Account Filter", null).isValid()).isTrue();
        assertThat(rule.validate("Account Filter", "").isValid()).isTrue();
        assertThat(rule.validate("Account Filter", "   ").isValid()).isTrue();
        // An all-spaces 11-char field (COBOL SPACES on a PIC X(11)) is still a blank/soft state.
        assertThat(rule.validate("Account Filter", "           ").isValid()).isTrue();
        // The dedicated filter entry point honors the same soft-blank contract as validate().
        assertThat(rule.validateAccountFilter("").isValid()).isTrue();
        assertThat(rule.validateAccountFilter(null).isValid()).isTrue();
    }

    // ------------------------------------------------------------------
    // (f) validateAccountNumber(non-digit) -> invalid, EXACT update message
    // ------------------------------------------------------------------

    @Test
    @DisplayName("(f) validateAccountNumber() rejects a non-digit id with the exact update message")
    void validateAccountNumber_nonDigit_isInvalidWithExactUpdateMessage() {
        ValidationResult result = rule.validateAccountNumber("0000000000A");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Account Number if supplied must be a 11 digit Non-Zero Number");
        assertThat(result.message()).hasSize(61);
    }

    @Test
    @DisplayName("validateAccountNumber() rejects an all-zero id with the update message")
    void validateAccountNumber_allZeros_isInvalidUpdateMessage() {
        ValidationResult result = rule.validateAccountNumber("00000000000");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(EXPECTED_UPDATE_MESSAGE);
    }

    // ------------------------------------------------------------------
    // (g) validateAccountNumber(valid) -> valid
    // ------------------------------------------------------------------

    @Test
    @DisplayName("(g) validateAccountNumber() accepts a valid non-zero 11-digit account id")
    void validateAccountNumber_validEleven_isValid() {
        ValidationResult result = rule.validateAccountNumber("00000000123");

        assertThat(result.isValid()).isTrue();
        assertThat(result.message()).isEmpty();
    }

    @Test
    @DisplayName("validateAccountNumber() treats blank inputs as valid (soft prompt state)")
    void validateAccountNumber_blankInputs_isValid() {
        assertThat(rule.validateAccountNumber(null).isValid()).isTrue();
        assertThat(rule.validateAccountNumber("").isValid()).isTrue();
        assertThat(rule.validateAccountNumber("   ").isValid()).isTrue();
    }

    // ------------------------------------------------------------------
    // The two paths differ ONLY by message; the update and filter messages are distinct.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("update and filter messages are distinct verbatim literals")
    void updateAndFilterMessages_areDistinct() {
        String filter = rule.validateAccountFilter("00000000000").message();
        String update = rule.validateAccountNumber("00000000000").message();

        assertThat(filter).isEqualTo(EXPECTED_FILTER_MESSAGE);
        assertThat(update).isEqualTo(EXPECTED_UPDATE_MESSAGE);
        assertThat(filter).isNotEqualTo(update);
    }

    // ------------------------------------------------------------------
    // validate() honors the ValidationRule contract: the fieldName label is ignored.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("validate() ignores the fieldName label (filter message is a fixed literal)")
    void validate_ignoresFieldNameLabel() {
        ValidationResult withLabel = rule.validate("Some Label", "00000000000");
        ValidationResult withNull = rule.validate(null, "00000000000");
        ValidationResult withOther = rule.validate("A totally different label", "00000000000");

        assertThat(withLabel.message()).isEqualTo(EXPECTED_FILTER_MESSAGE);
        assertThat(withNull.message()).isEqualTo(EXPECTED_FILTER_MESSAGE);
        assertThat(withOther.message()).isEqualTo(EXPECTED_FILTER_MESSAGE);
    }

    @Test
    @DisplayName("validate() delegates to the filter path (same outcome as validateAccountFilter)")
    void validate_delegatesToFilterPath() {
        assertThat(rule.validate("x", "00000000123")).isEqualTo(rule.validateAccountFilter("00000000123"));
        assertThat(rule.validate("x", "00000000000")).isEqualTo(rule.validateAccountFilter("00000000000"));
        assertThat(rule.validate("x", null)).isEqualTo(rule.validateAccountFilter(null));
    }

    // ------------------------------------------------------------------
    // Core check: isElevenDigitNonZero() edge cases.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isElevenDigitNonZero() accepts only a non-zero, exactly-11-character digit string")
    void isElevenDigitNonZero_edgeCases() {
        assertThat(rule.isElevenDigitNonZero("00000000123")).isTrue();
        assertThat(rule.isElevenDigitNonZero("12345678901")).isTrue();

        assertThat(rule.isElevenDigitNonZero(null)).isFalse();
        assertThat(rule.isElevenDigitNonZero("")).isFalse();
        assertThat(rule.isElevenDigitNonZero("1234")).isFalse();         // 4 chars (too short)
        assertThat(rule.isElevenDigitNonZero("0000000000")).isFalse();   // 10 chars
        assertThat(rule.isElevenDigitNonZero("000000000000")).isFalse(); // 12 chars
        assertThat(rule.isElevenDigitNonZero("00000000000")).isFalse();  // all zeros
        assertThat(rule.isElevenDigitNonZero("0000000000A")).isFalse();  // non-digit
        assertThat(rule.isElevenDigitNonZero("1234567890 ")).isFalse();  // trailing space
    }
}
