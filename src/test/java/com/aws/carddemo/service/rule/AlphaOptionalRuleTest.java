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
 * Unit tests for {@link AlphaOptionalRule}, verifying behavioral parity with COBOL edit
 * paragraph {@code 1235-EDIT-ALPHA-OPT} of {@code legacy/cbl/COACTUPC.cbl:L2012-L2059}.
 *
 * <p>The rule is the <em>optional</em> counterpart of {@code 1225-EDIT-ALPHA-REQD}: a blank
 * (not-supplied) value is accepted with no message, and a supplied value must contain only the
 * 52 ASCII letters {@code A}&ndash;{@code Z}/{@code a}&ndash;{@code z} and spaces
 * (see the {@code LIT-ALL-ALPHA-FROM-X} literal at {@code legacy/cbl/COACTUPC.cbl:L586-L593}).
 * These tests assert the exact COBOL screen message, {@code "<field> can have alphabets only."},
 * with the trailing period preserved.</p>
 */
class AlphaOptionalRuleTest {

    /** The rule under test; stateless, so a single instance is reused across cases. */
    private final AlphaOptionalRule rule = new AlphaOptionalRule();

    @Test
    @DisplayName("blank (null / empty / spaces) is VALID for an optional field and carries NO message")
    void blankIsValidWithNoMessage() {
        // null models COBOL LOW-VALUES (see legacy/cbl/COACTUPC.cbl:L2018).
        ValidationResult fromNull = rule.validate("Middle Name", null);
        assertTrue(fromNull.isValid(), "null (LOW-VALUES) is acceptable for an optional field");
        assertEquals("", fromNull.message(), "an unsupplied optional field carries no message");

        // empty and all-whitespace model COBOL SPACES / FUNCTION TRIM length 0 (L2019-L2022).
        ValidationResult fromEmpty = rule.validate("Middle Name", "");
        assertTrue(fromEmpty.isValid(), "empty (SPACES) is acceptable for an optional field");
        assertEquals("", fromEmpty.message());

        ValidationResult fromSpaces = rule.validate("Middle Name", "     ");
        assertTrue(fromSpaces.isValid(), "all-whitespace (trim length 0) is acceptable");
        assertEquals("", fromSpaces.message());
    }

    @Test
    @DisplayName("a simple alphabetic value \"Doe\" is VALID")
    void alphabeticValueIsValid() {
        ValidationResult result = rule.validate("Middle Name", "Doe");
        assertTrue(result.isValid(), "letters only must pass the alpha-only check");
        assertEquals("", result.message());
    }

    @Test
    @DisplayName("letters with an embedded space \"Mary Jane\" is VALID (space is in the allowed set)")
    void lettersWithSpaceIsValid() {
        ValidationResult result = rule.validate("Middle Name", "Mary Jane");
        assertTrue(result.isValid(), "space is a permitted character alongside the 52 letters");
        assertEquals("", result.message());
    }

    @Test
    @DisplayName("mixed upper- and lower-case letters are VALID (both LIT-UPPER and LIT-LOWER accepted)")
    void mixedCaseLettersAreValid() {
        ValidationResult result = rule.validate("Middle Name", "McArthur");
        assertTrue(result.isValid(), "both A-Z (LIT-UPPER) and a-z (LIT-LOWER) are accepted");
        assertEquals("", result.message());
    }

    @Test
    @DisplayName("a value containing a digit \"Doe2\" is INVALID with the exact COBOL message")
    void valueWithDigitIsInvalid() {
        ValidationResult result = rule.validate("Middle Name", "Doe2");
        assertFalse(result.isValid(), "a digit is outside the alpha-only set");
        assertTrue(result.isInvalid(), "isInvalid mirrors the COBOL INPUT-ERROR flag");
        assertEquals("Middle Name can have alphabets only.", result.message(),
                "must emit the exact screen message with trailing period");
    }

    @Test
    @DisplayName("a value containing punctuation \"Mary-Jane\" is INVALID with the exact COBOL message")
    void valueWithPunctuationIsInvalid() {
        ValidationResult result = rule.validate("Middle Name", "Mary-Jane");
        assertFalse(result.isValid(), "a hyphen is neither a letter nor a space");
        assertEquals("Middle Name can have alphabets only.", result.message());
    }

    @Test
    @DisplayName("the field label is trimmed into the message (FUNCTION TRIM of WS-EDIT-VARIABLE-NAME)")
    void fieldLabelIsTrimmedIntoMessage() {
        ValidationResult result = rule.validate("  Middle Name  ", "Doe2");
        assertFalse(result.isValid());
        assertEquals("Middle Name can have alphabets only.", result.message(),
                "leading/trailing label whitespace is stripped, matching FUNCTION TRIM");
    }
}
