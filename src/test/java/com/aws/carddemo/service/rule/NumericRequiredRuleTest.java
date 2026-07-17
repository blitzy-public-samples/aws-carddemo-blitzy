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
 * Unit tests for {@link NumericRequiredRule}, asserting bit-for-bit parity with the COBOL
 * paragraph {@code 1245-EDIT-NUM-REQD} of {@code legacy/cbl/COACTUPC.cbl} (lines 2109&ndash;2178).
 *
 * <p>The tests exercise the three ordered, short-circuiting checks (supplied &rarr; all-numeric
 * &rarr; non-zero), the verbatim period-terminated messages, and the {@code FUNCTION TRIM} field-
 * label normalization, so that any reordering, message drift, or trimming regression is caught.</p>
 */
class NumericRequiredRuleTest {

    private final NumericRequiredRule rule = new NumericRequiredRule();

    @Test
    @DisplayName("Not supplied: null models COBOL LOW-VALUES -> 'must be supplied.'")
    void nullValueIsNotSupplied() {
        ValidationResult result = rule.validate("Account Id", null);
        assertTrue(result.isInvalid(), "a null value must fail the not-supplied check");
        assertEquals("Account Id must be supplied.", result.message());
    }

    @Test
    @DisplayName("Not supplied: empty string models COBOL SPACES / trim length 0 -> 'must be supplied.'")
    void emptyValueIsNotSupplied() {
        ValidationResult result = rule.validate("Account Id", "");
        assertTrue(result.isInvalid(), "an empty value must fail the not-supplied check");
        assertEquals("Account Id must be supplied.", result.message());
    }

    @Test
    @DisplayName("Not supplied: all-whitespace value models COBOL SPACES -> 'must be supplied.'")
    void whitespaceValueIsNotSupplied() {
        ValidationResult result = rule.validate("Account Id", "   ");
        assertTrue(result.isInvalid(), "an all-whitespace value must fail the not-supplied check");
        assertEquals("Account Id must be supplied.", result.message());
    }

    @Test
    @DisplayName("Not numeric: an embedded letter fails IS NUMERIC -> 'must be all numeric.'")
    void letterValueIsNotNumeric() {
        ValidationResult result = rule.validate("Account Id", "12A");
        assertTrue(result.isInvalid(), "a value with a letter must fail the numeric check");
        assertEquals("Account Id must be all numeric.", result.message());
    }

    @Test
    @DisplayName("Not numeric: a trailing space fails IS NUMERIC (buffer slice) -> 'must be all numeric.'")
    void trailingSpaceValueIsNotNumeric() {
        ValidationResult result = rule.validate("Account Id", "12 ");
        assertTrue(result.isInvalid(), "a value with a trailing space must fail the numeric check");
        assertEquals("Account Id must be all numeric.", result.message());
    }

    @Test
    @DisplayName("Zero: an all-zero numeric value fails NUMVAL = 0 -> 'must not be zero.'")
    void zeroValueIsRejected() {
        ValidationResult result = rule.validate("Account Id", "000");
        assertTrue(result.isInvalid(), "an all-zero value must fail the non-zero check");
        assertEquals("Account Id must not be zero.", result.message());
    }

    @Test
    @DisplayName("Valid: a supplied, all-numeric, non-zero value passes all three checks")
    void suppliedNonZeroNumericIsValid() {
        ValidationResult result = rule.validate("Account Id", "007");
        assertTrue(result.isValid(), "a supplied non-zero numeric value must be valid");
        assertEquals("", result.message(), "a valid result carries the empty message");
    }

    @Test
    @DisplayName("Field label is trimmed (FUNCTION TRIM) before substitution into the message")
    void fieldLabelIsTrimmed() {
        ValidationResult result = rule.validate("  FICO Score  ", "abc");
        assertTrue(result.isInvalid(), "a non-numeric value must fail the numeric check");
        assertEquals("FICO Score must be all numeric.", result.message(),
                "the leading/trailing spaces of the label must be removed");
    }

    @Test
    @DisplayName("Check order: a blank value reports 'must be supplied', never 'must be all numeric'")
    void checkOrderShortCircuitsOnNotSupplied() {
        // A blank value is also non-numeric; the ordered short-circuit must report the FIRST
        // failure (not supplied), matching the legacy GO TO ...-EXIT after the first failed check.
        ValidationResult result = rule.validate("Account Id", null);
        assertEquals("Account Id must be supplied.", result.message(),
                "the not-supplied check must take precedence over the numeric check");
    }
}
