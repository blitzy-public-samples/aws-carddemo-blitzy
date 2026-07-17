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
 * Unit tests for {@link AlphanumOptionalRule}, the Java migration of COBOL edit paragraph
 * {@code 1240-EDIT-ALPHANUM-OPT} in {@code legacy/cbl/COACTUPC.cbl} (source
 * {@code app/cbl/COACTUPC.cbl:L2061-L2107}).
 *
 * <p>The tests assert both halves of the legacy behavior in its exact evaluation order: a
 * not-supplied (blank) value is accepted because the field is optional, and a supplied value must
 * consist solely of ASCII letters, ASCII digits, and spaces &mdash; any other character produces
 * the verbatim screen message {@code "<field> can have numbers or alphabets only."}. The
 * ASCII-only character set ({@code legacy/cbl/COACTUPC.cbl:L586-L593}) is confirmed by a Unicode
 * counter-example that {@link Character#isLetterOrDigit(char)} would wrongly accept.</p>
 */
class AlphanumOptionalRuleTest {

    private static final String FIELD = "Address Line 2";
    private static final String EXPECTED_MESSAGE = "Address Line 2 can have numbers or alphabets only.";

    private final AlphanumOptionalRule rule = new AlphanumOptionalRule();

    @Test
    @DisplayName("(a) null / empty / all-spaces are VALID because the field is optional")
    void blankIsValid() {
        assertTrue(rule.validate(FIELD, null).isValid(), "null models COBOL LOW-VALUES -> valid");
        assertTrue(rule.validate(FIELD, "").isValid(), "empty models SPACES / trim length 0 -> valid");
        assertTrue(rule.validate(FIELD, "   ").isValid(), "all spaces models SPACES -> valid");
    }

    @Test
    @DisplayName("a valid (blank) result carries the empty message, never null")
    void blankResultCarriesEmptyMessage() {
        assertEquals("", rule.validate(FIELD, null).message(), "valid result message is the empty string");
    }

    @Test
    @DisplayName("(b) 'Suite 200' (letters + space + digits) is VALID")
    void alphanumericWithSpaceIsValid() {
        ValidationResult result = rule.validate(FIELD, "Suite 200");
        assertTrue(result.isValid(), "letters, a space, and digits are all in the allowed set");
        assertEquals("", result.message());
    }

    @Test
    @DisplayName("(c) 'C/O Jane' (contains '/') is INVALID with the exact COBOL message")
    void slashIsInvalidWithExactMessage() {
        ValidationResult result = rule.validate(FIELD, "C/O Jane");
        assertTrue(result.isInvalid(), "the '/' character is neither alphanumeric nor a space");
        assertFalse(result.isValid());
        assertEquals(EXPECTED_MESSAGE, result.message(), "message must match COBOL WS-RETURN-MSG verbatim");
    }

    @Test
    @DisplayName("(d) 'Bldg7' (letters immediately followed by a digit) is VALID")
    void lettersThenDigitIsValid() {
        assertTrue(rule.validate(FIELD, "Bldg7").isValid(), "adjacent letters and digits are allowed");
    }

    @Test
    @DisplayName("pure letters and pure digits are each VALID")
    void pureLettersAndPureDigitsAreValid() {
        assertTrue(rule.validate(FIELD, "MainStreet").isValid(), "all letters -> valid");
        assertTrue(rule.validate(FIELD, "1234567890").isValid(), "all digits -> valid");
        assertTrue(rule.validate(FIELD, "Main Street West").isValid(), "letters with interior spaces -> valid");
    }

    @Test
    @DisplayName("common punctuation and symbols are each INVALID with the exact message")
    void punctuationIsInvalid() {
        for (String value : new String[] {
                "12-34",   // hyphen
                "St. Paul", // period
                "A,B",     // comma
                "user@host", // at-sign
                "50%",     // percent
                "#7",      // hash
                "(200)",   // parentheses
                "a_b"      // underscore
        }) {
            ValidationResult result = rule.validate(FIELD, value);
            assertTrue(result.isInvalid(), "value '" + value + "' contains a disallowed character");
            assertEquals(EXPECTED_MESSAGE, result.message(), "message for '" + value + "'");
        }
    }

    @Test
    @DisplayName("a leading/trailing padded field label is trimmed in the failure message")
    void fieldLabelIsTrimmedInMessage() {
        ValidationResult result = rule.validate("  Address Line 2  ", "C/O Jane");
        assertEquals(EXPECTED_MESSAGE, result.message(), "label is normalized with FUNCTION TRIM semantics");
    }

    @Test
    @DisplayName("a null field label yields a message with an empty label prefix (no NPE)")
    void nullFieldLabelProducesEmptyPrefix() {
        ValidationResult result = rule.validate(null, "C/O Jane");
        assertTrue(result.isInvalid());
        assertEquals(" can have numbers or alphabets only.", result.message(),
                "null label normalizes to the empty string per ValidationRule.label");
    }

    @Test
    @DisplayName("ASCII-only fidelity: a non-ASCII Unicode letter is INVALID (parity vs isLetterOrDigit)")
    void unicodeLetterIsInvalid() {
        // 'café' contains U+00E9; Character.isLetterOrDigit would accept it, but the COBOL A-Za-z0-9
        // class test (legacy/cbl/COACTUPC.cbl:L586-L593) does not, so the rule must reject it.
        ValidationResult result = rule.validate(FIELD, "caf\u00e9");
        assertTrue(result.isInvalid(), "non-ASCII letter must be rejected to preserve COBOL parity");
        assertEquals(EXPECTED_MESSAGE, result.message());
    }
}
