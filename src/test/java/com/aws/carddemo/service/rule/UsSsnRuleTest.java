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
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UsSsnRule}, the Java reproduction of the COBOL edit paragraph
 * {@code 1265-EDIT-US-SSN} of {@code legacy/cbl/COACTUPC.cbl} (lines 2431&ndash;2491).
 *
 * <p>The tests assert the exact screen messages the legacy program produced &mdash; the numeric-
 * required messages delegated to {@link NumericRequiredRule} ("must be supplied." / "must be all
 * numeric." / "must not be zero.") and the part-1 range message
 * ("SSN: First 3 chars: should not be 000, 666, or between 900 and 999") &mdash; the sequential
 * part1 &rarr; part2 &rarr; part3 first-message-wins ordering, the fixed-width digit-count parity,
 * and the {@code 88 INVALID-SSN-PART1} range boundaries. Because the SSN is sensitive, a dedicated
 * test additionally asserts the class declares no logger field and never echoes the raw input into
 * an outcome message.</p>
 *
 * <p>The rule is exercised through {@code new UsSsnRule(new NumericRequiredRule())} (plain
 * construction, no Spring context) to mirror the legacy {@code PERFORM 1245-EDIT-NUM-REQD} reuse.</p>
 */
class UsSsnRuleTest {

    private final UsSsnRule rule = new UsSsnRule(new NumericRequiredRule());

    @Test
    @DisplayName("(a) valid SSN parts (123-45-6789) -> valid")
    void validSsn_isValid() {
        ValidationResult result = rule.validate("123", "45", "6789");
        assertTrue(result.isValid(), "a well-formed SSN must pass every part edit");
        assertEquals("", result.message(), "a valid result carries the empty message");
    }

    @Test
    @DisplayName("(b) part1 = 666 -> range message (INVALID-SSN-PART1)")
    void part1_is666_rangeMessage() {
        ValidationResult result = rule.validate("666", "45", "6789");
        assertTrue(result.isInvalid());
        assertEquals("SSN: First 3 chars: should not be 000, 666, or between 900 and 999",
                result.message());
    }

    @Test
    @DisplayName("(c) part1 = 900 and 999 -> same range message (900 THRU 999)")
    void part1_inNineHundredsRange_rangeMessage() {
        String expected = "SSN: First 3 chars: should not be 000, 666, or between 900 and 999";

        ValidationResult lower = rule.validate("900", "45", "6789");
        assertTrue(lower.isInvalid());
        assertEquals(expected, lower.message());

        ValidationResult upper = rule.validate("999", "45", "6789");
        assertTrue(upper.isInvalid());
        assertEquals(expected, upper.message());
    }

    @Test
    @DisplayName("(d) part1 = 000 -> 'must not be zero.' (numeric edit catches zero before the range check)")
    void part1_isZero_reportsMustNotBeZero() {
        ValidationResult result = rule.validate("000", "45", "6789");
        assertTrue(result.isInvalid());
        // Verifies evaluation order: the non-zero numeric edit wins over the ": should not be..." range message.
        assertEquals("SSN: First 3 chars must not be zero.", result.message());
    }

    @Test
    @DisplayName("(e) blank part1 -> 'must be supplied.'")
    void part1_blank_reportsMustBeSupplied() {
        assertEquals("SSN: First 3 chars must be supplied.",
                rule.validate("", "45", "6789").message(), "empty string models COBOL SPACES");
        assertEquals("SSN: First 3 chars must be supplied.",
                rule.validate(null, "45", "6789").message(), "null models COBOL LOW-VALUES");
        assertEquals("SSN: First 3 chars must be supplied.",
                rule.validate("   ", "45", "6789").message(), "all-whitespace is blank");
    }

    @Test
    @DisplayName("(f) short part1 = 12 -> 'must be all numeric.' (fixed-width: space-padded short entry)")
    void part1_tooShort_reportsMustBeAllNumeric() {
        ValidationResult result = rule.validate("12", "45", "6789");
        assertTrue(result.isInvalid());
        assertEquals("SSN: First 3 chars must be all numeric.", result.message());
    }

    @Test
    @DisplayName("(g) valid part1, part2 = 00 -> 'SSN 4th & 5th chars must not be zero.'")
    void part2_isZero_reportsMustNotBeZero() {
        ValidationResult result = rule.validate("123", "00", "6789");
        assertTrue(result.isInvalid());
        assertEquals("SSN 4th & 5th chars must not be zero.", result.message());
    }

    @Test
    @DisplayName("(h) valid part1 and part2, part3 = 0000 -> 'SSN Last 4 chars must not be zero.'")
    void part3_isZero_reportsMustNotBeZero() {
        ValidationResult result = rule.validate("123", "45", "0000");
        assertTrue(result.isInvalid());
        assertEquals("SSN Last 4 chars must not be zero.", result.message());
    }

    @Test
    @DisplayName("part1 non-numeric -> 'SSN: First 3 chars must be all numeric.'")
    void part1_nonNumeric_reportsMustBeAllNumeric() {
        ValidationResult result = rule.validate("1x3", "45", "6789");
        assertTrue(result.isInvalid());
        assertEquals("SSN: First 3 chars must be all numeric.", result.message());
    }

    @Test
    @DisplayName("INVALID-SSN-PART1 boundaries: 665, 667, 899 are valid; 900 is invalid")
    void part1_rangeBoundaries() {
        assertTrue(rule.validate("665", "45", "6789").isValid(), "665 is just below 666");
        assertTrue(rule.validate("667", "45", "6789").isValid(), "667 is just above 666");
        assertTrue(rule.validate("899", "45", "6789").isValid(), "899 is just below the 900-999 range");
        assertTrue(rule.validate("900", "45", "6789").isInvalid(), "900 is the low bound of the range");
    }

    @Test
    @DisplayName("part2 blank (part1 valid) -> 'SSN 4th & 5th chars must be supplied.'")
    void part2_blank_reportsMustBeSupplied() {
        ValidationResult result = rule.validate("123", "", "6789");
        assertTrue(result.isInvalid());
        assertEquals("SSN 4th & 5th chars must be supplied.", result.message());
    }

    @Test
    @DisplayName("short part2 = 4 (part1 valid) -> 'SSN 4th & 5th chars must be all numeric.'")
    void part2_tooShort_reportsMustBeAllNumeric() {
        ValidationResult result = rule.validate("123", "4", "6789");
        assertTrue(result.isInvalid());
        assertEquals("SSN 4th & 5th chars must be all numeric.", result.message());
    }

    @Test
    @DisplayName("part3 blank (part1 and part2 valid) -> 'SSN Last 4 chars must be supplied.'")
    void part3_blank_reportsMustBeSupplied() {
        ValidationResult result = rule.validate("123", "45", "");
        assertTrue(result.isInvalid());
        assertEquals("SSN Last 4 chars must be supplied.", result.message());
    }

    @Test
    @DisplayName("short part3 = 678 (part1 and part2 valid) -> 'SSN Last 4 chars must be all numeric.'")
    void part3_tooShort_reportsMustBeAllNumeric() {
        ValidationResult result = rule.validate("123", "45", "678");
        assertTrue(result.isInvalid());
        assertEquals("SSN Last 4 chars must be all numeric.", result.message());
    }

    @Test
    @DisplayName("first-message-wins: a part1 failure masks later part2/part3 failures")
    void firstMessageWins_acrossParts() {
        // part1 '000' (zero), part2 '00' (zero), part3 '0000' (zero) all fail; only the part1 message shows.
        ValidationResult result = rule.validate("000", "00", "0000");
        assertTrue(result.isInvalid());
        assertEquals("SSN: First 3 chars must not be zero.", result.message());
    }

    @Test
    @DisplayName("sensitive data: the class declares no logger field and holds only the injected rule")
    void declaresNoLoggerFieldAndHoldsOnlyInjectedRule() throws ReflectiveOperationException {
        int instanceFieldCount = 0;
        for (Field field : UsSsnRule.class.getDeclaredFields()) {
            // Skip any synthetic field the compiler may add (for example the jacoco coverage array).
            if (field.isSynthetic()) {
                continue;
            }
            String typeName = field.getType().getName().toLowerCase();
            assertFalse(typeName.contains("logger") || typeName.contains("log4j") || typeName.contains("slf4j"),
                    "UsSsnRule must not declare a logger field (the SSN must never be logged); found "
                            + field.getName() + " of type " + field.getType().getName());
            if (!Modifier.isStatic(field.getModifiers())) {
                instanceFieldCount++;
                assertEquals(NumericRequiredRule.class, field.getType(),
                        "the only instance state must be the injected NumericRequiredRule");
            }
        }
        assertEquals(1, instanceFieldCount, "UsSsnRule must hold exactly one instance field (no SSN is stored)");
    }

    @Test
    @DisplayName("sensitive data: an outcome message never echoes the raw SSN part that was entered")
    void messageNeverEchoesRawInput() {
        // A distinctive non-numeric token that shares no substring with any fixed message body.
        ValidationResult result = rule.validate("A7Q", "45", "6789");
        assertTrue(result.isInvalid());
        assertEquals("SSN: First 3 chars must be all numeric.", result.message());
        assertFalse(result.message().contains("A7Q"), "the raw entered value must never appear in the message");
    }
}
