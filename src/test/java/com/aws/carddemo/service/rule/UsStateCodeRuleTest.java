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

import java.lang.reflect.Field;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UsStateCodeRule}, verifying byte-for-byte parity with the COBOL edit
 * paragraph {@code 1270-EDIT-US-STATE-CD} of {@code legacy/cbl/COACTUPC.cbl} and the
 * {@code 88 VALID-US-STATE-CODE} lookup of {@code legacy/cpy/CSLKPCDY.cpy}: a member code passes,
 * any other value (unknown, lower-case, blank, or {@code null}) fails with the exact screen
 * message {@code "<field>: is not a valid state code"}, and the recognized set contains exactly
 * the 56 codes (50 states + DC + 5 territories) defined by the legacy copybook.
 */
class UsStateCodeRuleTest {

    /** The COBOL screen message for the field label {@code "State"} used across the failure tests. */
    private static final String STATE_MESSAGE = "State: is not a valid state code";

    private final UsStateCodeRule rule = new UsStateCodeRule();

    @Test
    @DisplayName("A recognized state code (CA) is valid with no message")
    void validate_recognizedState_isValid() {
        ValidationResult result = rule.validate("State", "CA");
        assertTrue(result.isValid(), "CA is a recognized state code");
        assertEquals("", result.message(), "a valid result carries the empty message");
    }

    @Test
    @DisplayName("A recognized territory code (PR) is valid")
    void validate_recognizedTerritory_isValid() {
        assertTrue(rule.validate("State", "PR").isValid(), "PR (Puerto Rico) is a recognized territory");
    }

    @Test
    @DisplayName("An unknown code (XX) is invalid with the COBOL state-code message")
    void validate_unknownCode_isInvalid() {
        ValidationResult result = rule.validate("State", "XX");
        assertTrue(result.isInvalid(), "XX is not a member of VALID-US-STATE-CODE");
        assertEquals(STATE_MESSAGE, result.message());
    }

    @Test
    @DisplayName("A lower-case code (ca) is invalid: the COBOL 88-level values are upper-case (case parity)")
    void validate_lowerCase_isInvalid() {
        ValidationResult result = rule.validate("State", "ca");
        assertTrue(result.isInvalid(), "lower-case 'ca' is not a member; input is never upper-cased");
        assertEquals(STATE_MESSAGE, result.message());
    }

    @Test
    @DisplayName("A blank value is invalid with the same message (88-level on SPACES is false)")
    void validate_blank_isInvalid() {
        ValidationResult result = rule.validate("State", "   ");
        assertTrue(result.isInvalid(), "a spaces value is simply not a member");
        assertEquals(STATE_MESSAGE, result.message());
    }

    @Test
    @DisplayName("A null value is invalid with the same message (LOW-VALUES is not a member)")
    void validate_null_isInvalid() {
        ValidationResult result = rule.validate("State", null);
        assertTrue(result.isInvalid(), "null models COBOL LOW-VALUES and is not a member");
        assertEquals(STATE_MESSAGE, result.message());
    }

    @Test
    @DisplayName("A padded value is stripped before the comparison, so ' CA ' is valid")
    void validate_paddedValue_isStrippedThenValid() {
        assertTrue(rule.validate("State", " CA ").isValid(), "surrounding whitespace is stripped");
    }

    @Test
    @DisplayName("The field label is trimmed into the message (FUNCTION TRIM(WS-EDIT-VARIABLE-NAME))")
    void validate_labelIsTrimmedIntoMessage() {
        assertEquals(STATE_MESSAGE, rule.validate("  State  ", "XX").message());
    }

    @Test
    @DisplayName("DC and all five territories (AS, GU, MP, PR, VI) are recognized")
    void isValidStateCode_dcAndTerritories() {
        assertTrue(rule.isValidStateCode("DC"), "District of Columbia is recognized");
        assertTrue(rule.isValidStateCode("AS"), "American Samoa is recognized");
        assertTrue(rule.isValidStateCode("GU"), "Guam is recognized");
        assertTrue(rule.isValidStateCode("MP"), "Northern Mariana Islands is recognized");
        assertTrue(rule.isValidStateCode("PR"), "Puerto Rico is recognized");
        assertTrue(rule.isValidStateCode("VI"), "US Virgin Islands is recognized");
    }

    @Test
    @DisplayName("isValidStateCode rejects unknown, lower-case, blank, and null values")
    void isValidStateCode_rejectsNonMembers() {
        assertTrue(rule.isValidStateCode("AL"), "first legacy code AL is a member");
        assertFalse(rule.isValidStateCode("XX"), "XX is not a member");
        assertFalse(rule.isValidStateCode("ca"), "lower-case is not a member (case-sensitive)");
        assertFalse(rule.isValidStateCode("   "), "blank is not a member");
        assertFalse(rule.isValidStateCode(null), "null is not a member");
    }

    @Test
    @DisplayName("The recognized set contains exactly 56 codes (50 states + DC + 5 territories)")
    void validStateCodes_hasExactly56Entries() throws ReflectiveOperationException {
        Field field = UsStateCodeRule.class.getDeclaredField("VALID_STATE_CODES");
        field.setAccessible(true);
        Set<?> codes = (Set<?>) field.get(null);
        assertEquals(56, codes.size(),
                "VALID-US-STATE-CODE defines exactly 56 members in legacy/cpy/CSLKPCDY.cpy");
    }
}
