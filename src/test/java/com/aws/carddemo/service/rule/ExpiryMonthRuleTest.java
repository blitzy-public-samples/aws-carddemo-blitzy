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
 * Unit tests for {@link ExpiryMonthRule}, the Java migration of the COBOL edit paragraph
 * {@code 1250-EDIT-EXPIRY-MON} in {@code legacy/cbl/COCRDUPC.cbl} (L877&ndash;L912).
 *
 * <p>These tests assert verbatim behavioral parity: the two-digit range {@code 01}&ndash;{@code 12}
 * is accepted; a blank field ({@code null}/{@code SPACES}), the COBOL {@code ZEROS} value
 * ({@code "00"}), a non-numeric value, and an out-of-range value are each rejected with the
 * single fixed message {@code "Card expiry month must be between 1 and 12"} taken from the
 * COBOL 88-level {@code CARD-EXPIRY-MONTH-NOT-VALID} at {@code legacy/cbl/COCRDUPC.cbl:L197-L198}.
 * The rule never throws (a defensive length bound keeps the internal parse within {@code int}
 * range even for pathologically long numeric input).</p>
 */
class ExpiryMonthRuleTest {

    /**
     * The exact COBOL screen message; every failure path must reproduce it verbatim.
     */
    private static final String MESSAGE = "Card expiry month must be between 1 and 12";

    /**
     * An arbitrary field label; the rule must ignore it because the COBOL message is a fixed
     * literal that never substitutes a field name.
     */
    private static final String LABEL = "Card expiry month";

    private final ExpiryMonthRule rule = new ExpiryMonthRule();

    @ParameterizedTest
    @ValueSource(strings = {"01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12"})
    @DisplayName("Two-digit months 01-12 are valid (VALID-MONTH: VALUES 1 THRU 12)")
    void twoDigitMonthsInRangeAreValid(String month) {
        ValidationResult result = rule.validate(LABEL, month);

        assertTrue(result.isValid(), () -> "expected month '" + month + "' to be valid");
        assertEquals("", result.message(), "a valid result carries the empty message");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "5", "9"})
    @DisplayName("A bare single digit 1-9 is valid (strip + numeric parse treat it as month 1-9)")
    void singleDigitMonthsInRangeAreValid(String month) {
        ValidationResult result = rule.validate(LABEL, month);

        assertTrue(result.isValid(), () -> "expected month '" + month + "' to be valid");
        assertEquals("", result.message());
    }

    @Test
    @DisplayName("The canonical valid outcome is the shared ValidationResult.valid() singleton")
    void validOutcomeIsTheSharedSingleton() {
        assertSame(ValidationResult.valid(), rule.validate(LABEL, "01"),
                "a passing rule should return the cached valid() instance");
    }

    @ParameterizedTest
    @ValueSource(strings = {"00", "0", "000"})
    @DisplayName("Zero (COBOL ZEROS) is rejected with the fixed message")
    void zeroIsRejected(String value) {
        ValidationResult result = rule.validate(LABEL, value);

        assertTrue(result.isInvalid(), () -> "expected '" + value + "' to be invalid");
        assertEquals(MESSAGE, result.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"13", "20", "99"})
    @DisplayName("A numeric value above 12 is rejected with the fixed message")
    void aboveRangeIsRejected(String value) {
        ValidationResult result = rule.validate(LABEL, value);

        assertTrue(result.isInvalid(), () -> "expected '" + value + "' to be invalid");
        assertEquals(MESSAGE, result.message());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  ", "\t"})
    @DisplayName("Blank input (null/LOW-VALUES, empty, and all-whitespace/SPACES) is rejected")
    void blankIsRejected(String value) {
        ValidationResult result = rule.validate(LABEL, value);

        assertTrue(result.isInvalid(), "expected blank input to be invalid");
        assertEquals(MESSAGE, result.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1A", "A1", "1a", "AB", "1.", "-1", "+1", "1/", "O1"})
    @DisplayName("Non-numeric input fails the PIC 9(2) numeric class test with the fixed message")
    void nonNumericIsRejected(String value) {
        ValidationResult result = rule.validate(LABEL, value);

        assertTrue(result.isInvalid(), () -> "expected '" + value + "' to be invalid");
        assertEquals(MESSAGE, result.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"001", "012", "013", "099", "99999999999", "00000000000000000000000000"})
    @DisplayName("Input longer than the two-digit field is rejected and never throws (defensive bound)")
    void overlongInputIsRejectedWithoutThrowing(String value) {
        // "001" and "012" would parse to 1 and 12 respectively if the length were not bounded;
        // the field is PIC X(2), so anything wider than two digits is out of contract. The very
        // long values additionally prove that the parse can never overflow int (the rule never
        // throws, honoring the ValidationRule contract).
        ValidationResult result = assertDoesNotThrow(() -> rule.validate(LABEL, value));

        assertTrue(result.isInvalid(), () -> "expected overlong '" + value + "' to be invalid");
        assertEquals(MESSAGE, result.message());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"IGNORED", "Expiry Month", "   "})
    @DisplayName("The field-name argument is ignored: the failure message is always the fixed literal")
    void fieldNameArgumentIsIgnored(String fieldName) {
        // Regardless of the label supplied, an invalid value yields the identical fixed message,
        // proving the COBOL 88-level constant is emitted verbatim with no name substitution.
        ValidationResult result = rule.validate(fieldName, "00");

        assertTrue(result.isInvalid());
        assertEquals(MESSAGE, result.message());
    }

    @Test
    @DisplayName("The rule is stateless and safe to reuse across successive, mixed invocations")
    void ruleIsStatelessAcrossInvocations() {
        assertTrue(rule.validate(LABEL, "07").isValid());
        assertTrue(rule.validate(LABEL, "13").isInvalid());
        assertTrue(rule.validate(LABEL, "12").isValid());
        assertTrue(rule.validate(LABEL, null).isInvalid());
        // The final valid call still returns the canonical singleton, confirming no drift.
        assertSame(ValidationResult.valid(), rule.validate(LABEL, "01"));
    }
}
