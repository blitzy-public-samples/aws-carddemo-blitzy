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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UsPhoneRule}, the Java reproduction of the COBOL edit paragraph
 * {@code 1260-EDIT-US-PHONE-NUM} of {@code legacy/cbl/COACTUPC.cbl}. The tests assert the exact
 * screen messages the legacy program produced (mind the capital {@code "A"} in
 * "must be A N digit number.", the trailing period on the "supplied." and "digit number." messages,
 * and the absence of a period on the "cannot be zero" and "Not valid ..." messages), the
 * optional-when-all-blank behaviour, the first-message-wins ordering across the three sub-edits,
 * and the size of the embedded {@code VALID-GENERAL-PURP-CODE} area-code set.
 */
class UsPhoneRuleTest {

    private final UsPhoneRule rule = new UsPhoneRule();

    @Test
    @DisplayName("(a) all three parts blank -> valid (phone number is optional)")
    void allBlank_isOptionalAndValid() {
        assertTrue(rule.validate("Phone", null, null, null).isValid(), "null parts model LOW-VALUES");
        assertTrue(rule.validate("Phone", "", "", "").isValid(), "empty parts model SPACES");
        assertTrue(rule.validate("Phone", "   ", " ", "  ").isValid(), "all-whitespace parts are blank");
    }

    @Test
    @DisplayName("(b) valid area/prefix/line -> valid (201 is in the general-purpose set)")
    void fullyValidNumber_isValid() {
        assertTrue(rule.validate("Phone", "201", "555", "0123").isValid());
    }

    @Test
    @DisplayName("(c) area code not in the general-purpose set -> 'Not valid ...' (no trailing period)")
    void areaCode_notInLookupSet() {
        ValidationResult result = rule.validate("Phone", "211", "555", "0123");
        assertTrue(result.isInvalid());
        assertEquals("Phone: Not valid North America general purpose area code", result.message());
    }

    @Test
    @DisplayName("(d) area code '000' -> 'Area code cannot be zero' (no trailing period)")
    void areaCode_zero() {
        ValidationResult result = rule.validate("Phone", "000", "555", "0123");
        assertTrue(result.isInvalid());
        assertEquals("Phone: Area code cannot be zero", result.message());
    }

    @Test
    @DisplayName("(e) short area code '20' -> 'Area code must be A 3 digit number.'")
    void areaCode_tooShort() {
        ValidationResult result = rule.validate("Phone", "20", "555", "0123");
        assertTrue(result.isInvalid());
        assertEquals("Phone: Area code must be A 3 digit number.", result.message());
    }

    @Test
    @DisplayName("(f) blank area but present prefix/line -> 'Area code must be supplied.'")
    void areaCode_blankWhenNumberPresent() {
        ValidationResult result = rule.validate("Phone", "", "555", "0123");
        assertTrue(result.isInvalid());
        assertEquals("Phone: Area code must be supplied.", result.message());
    }

    @Test
    @DisplayName("(g) valid area, prefix '000' -> 'Prefix code cannot be zero' (no trailing period)")
    void prefix_zero() {
        ValidationResult result = rule.validate("Phone", "201", "000", "0123");
        assertTrue(result.isInvalid());
        assertEquals("Phone: Prefix code cannot be zero", result.message());
    }

    @Test
    @DisplayName("(h) valid area+prefix, short line '12' -> 'Line number code must be A 4 digit number.'")
    void lineNumber_tooShort() {
        ValidationResult result = rule.validate("Phone", "201", "555", "12");
        assertTrue(result.isInvalid());
        assertEquals("Phone: Line number code must be A 4 digit number.", result.message());
    }

    @Test
    @DisplayName("(i) valid area+prefix, line '0000' -> 'Line number code cannot be zero' (no trailing period)")
    void lineNumber_zero() {
        ValidationResult result = rule.validate("Phone", "201", "555", "0000");
        assertTrue(result.isInvalid());
        assertEquals("Phone: Line number code cannot be zero", result.message());
    }

    @Test
    @DisplayName("prefix blank (area valid) -> 'Prefix code must be supplied.'")
    void prefix_blank() {
        ValidationResult result = rule.validate("Phone", "201", "", "0123");
        assertTrue(result.isInvalid());
        assertEquals("Phone: Prefix code must be supplied.", result.message());
    }

    @Test
    @DisplayName("prefix non-numeric (area valid) -> 'Prefix code must be A 3 digit number.'")
    void prefix_nonNumeric() {
        ValidationResult result = rule.validate("Phone", "201", "5x5", "0123");
        assertTrue(result.isInvalid());
        assertEquals("Phone: Prefix code must be A 3 digit number.", result.message());
    }

    @Test
    @DisplayName("line number blank (area+prefix valid) -> 'Line number code must be supplied.'")
    void lineNumber_blank() {
        ValidationResult result = rule.validate("Phone", "201", "555", "");
        assertTrue(result.isInvalid());
        assertEquals("Phone: Line number code must be supplied.", result.message());
    }

    @Test
    @DisplayName("area code non-numeric -> 'Area code must be A 3 digit number.'")
    void areaCode_nonNumeric() {
        ValidationResult result = rule.validate("Phone", "2a1", "555", "0123");
        assertTrue(result.isInvalid());
        assertEquals("Phone: Area code must be A 3 digit number.", result.message());
    }

    @Test
    @DisplayName("first-message-wins: an area-code failure masks a later prefix failure")
    void firstMessageWins_acrossSubEdits() {
        // Both area ('000' -> zero) and prefix ('000' -> zero) would fail; only the area message shows.
        ValidationResult result = rule.validate("Phone", "000", "000", "0000");
        assertTrue(result.isInvalid());
        assertEquals("Phone: Area code cannot be zero", result.message());
    }

    @Test
    @DisplayName("field label is trimmed into the message (FUNCTION TRIM(WS-EDIT-VARIABLE-NAME))")
    void fieldLabelIsTrimmed() {
        ValidationResult result = rule.validate("  Phone  ", "20", "555", "0123");
        assertTrue(result.isInvalid());
        assertEquals("Phone: Area code must be A 3 digit number.", result.message());
    }

    @Test
    @DisplayName("VALID_AREA_CODES contains exactly 410 general-purpose area codes")
    void areaCodeSet_hasExactly410Entries() throws ReflectiveOperationException {
        Field field = UsPhoneRule.class.getDeclaredField("VALID_AREA_CODES");
        field.setAccessible(true);
        Set<?> codes = (Set<?>) field.get(null);
        assertEquals(410, codes.size());
    }
}
