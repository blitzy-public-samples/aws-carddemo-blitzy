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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ValidationRule}, verifying the four shared COBOL-edit primitives
 * ({@link ValidationRule#isBlank(String)}, {@link ValidationRule#isAllDigits(String)},
 * {@link ValidationRule#isZeroValue(String)}, {@link ValidationRule#label(String)}) and the
 * Strategy {@link ValidationRule#validate(String, String)} contract itself, driven by a small
 * rule that reproduces {@code 1245-EDIT-NUM-REQD} of {@code legacy/cbl/COACTUPC.cbl}.
 */
class ValidationRuleTest {

    @Test
    @DisplayName("isBlank is true for null, empty, and all-whitespace values (LOW-VALUES/SPACES)")
    void isBlank_detectsNotSupplied() {
        assertTrue(ValidationRule.isBlank(null), "null models COBOL LOW-VALUES");
        assertTrue(ValidationRule.isBlank(""), "empty models SPACES / trim length 0");
        assertTrue(ValidationRule.isBlank("   "), "all spaces models SPACES / trim length 0");
    }

    @Test
    @DisplayName("isBlank is false when any non-whitespace character is present")
    void isBlank_falseWhenSupplied() {
        assertFalse(ValidationRule.isBlank(" x "), "a padded value still carries content");
        assertFalse(ValidationRule.isBlank("x"));
    }

    @Test
    @DisplayName("isAllDigits is true only for a non-empty ASCII-digit string (IS NUMERIC)")
    void isAllDigits_trueForAsciiDigits() {
        assertTrue(ValidationRule.isAllDigits("007"), "leading zeros are digits");
        assertTrue(ValidationRule.isAllDigits("0"));
        assertTrue(ValidationRule.isAllDigits("1234567890"));
    }

    @Test
    @DisplayName("isAllDigits is false for null, empty, spaces, letters, and signed input")
    void isAllDigits_falseForNonDigits() {
        assertFalse(ValidationRule.isAllDigits(null));
        assertFalse(ValidationRule.isAllDigits(""));
        assertFalse(ValidationRule.isAllDigits("12 "), "trailing space breaks IS NUMERIC");
        assertFalse(ValidationRule.isAllDigits(" 12"), "leading space breaks IS NUMERIC");
        assertFalse(ValidationRule.isAllDigits("1a"), "a letter is not a digit");
        assertFalse(ValidationRule.isAllDigits("-1"), "an explicit sign is not a digit");
        assertFalse(ValidationRule.isAllDigits("12.3"), "a decimal point is not a digit");
    }

    @Test
    @DisplayName("isZeroValue is true only when every (already-numeric) character is '0' (NUMVAL = 0)")
    void isZeroValue_matchesNumvalZero() {
        assertTrue(ValidationRule.isZeroValue("000"));
        assertTrue(ValidationRule.isZeroValue("0"));

        assertFalse(ValidationRule.isZeroValue("001"), "a trailing non-zero digit is non-zero");
        assertFalse(ValidationRule.isZeroValue("100"), "a leading non-zero digit is non-zero");
        assertFalse(ValidationRule.isZeroValue("007"));
    }

    @Test
    @DisplayName("label trims leading/trailing whitespace and maps null to the empty string (FUNCTION TRIM)")
    void label_trimsAndNullSafe() {
        assertEquals("State", ValidationRule.label("  State  "));
        assertEquals("", ValidationRule.label(null));
        assertEquals("State", ValidationRule.label("State"));
        assertEquals("", ValidationRule.label("   "), "all-whitespace trims to empty");
    }

    @Test
    @DisplayName("validate() Strategy contract: a rule composed of the primitives reproduces 1245-EDIT-NUM-REQD")
    void validate_strategyContractUsesPrimitives() {
        // A minimal numeric-required rule mirroring legacy/cbl/COACTUPC.cbl 1245-EDIT-NUM-REQD:
        // blank -> "must be supplied", non-numeric -> "must be all numeric", zero -> "must not be zero".
        ValidationRule numericRequired = (fieldName, value) -> {
            String label = ValidationRule.label(fieldName);
            if (ValidationRule.isBlank(value)) {
                return ValidationResult.invalid(label + " must be supplied.");
            }
            if (!ValidationRule.isAllDigits(value)) {
                return ValidationResult.invalid(label + " must be all numeric.");
            }
            if (ValidationRule.isZeroValue(value)) {
                return ValidationResult.invalid(label + " must not be zero.");
            }
            return ValidationResult.valid();
        };

        // Blank path (label is trimmed into the message).
        ValidationResult blank = numericRequired.validate("  Account ID  ", null);
        assertTrue(blank.isInvalid());
        assertEquals("Account ID must be supplied.", blank.message());

        // Non-numeric path.
        ValidationResult nonNumeric = numericRequired.validate("Account ID", "12a");
        assertTrue(nonNumeric.isInvalid());
        assertEquals("Account ID must be all numeric.", nonNumeric.message());

        // Zero path.
        ValidationResult zero = numericRequired.validate("Account ID", "000");
        assertTrue(zero.isInvalid());
        assertEquals("Account ID must not be zero.", zero.message());

        // Passing path.
        ValidationResult ok = numericRequired.validate("Account ID", "007");
        assertTrue(ok.isValid());
        assertEquals("", ok.message());
    }
}
