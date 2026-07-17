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
 * Unit tests for the four shared COBOL-edit "primitives" declared as {@code static} helpers on
 * {@link ValidationRule}: {@link ValidationRule#isBlank(String)},
 * {@link ValidationRule#isAllDigits(String)}, {@link ValidationRule#isZeroValue(String)}, and
 * {@link ValidationRule#label(String)}.
 *
 * <p>These primitives are the Java centralization of the not-supplied test, the {@code IS NUMERIC}
 * class test, the {@code FUNCTION NUMVAL(...) = 0} zero test, and the
 * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} label trim that the {@code 12xx-EDIT-*} paragraphs of
 * the legacy online-edit program {@code legacy/cbl/COACTUPC.cbl} (source-branch
 * {@code app/cbl/COACTUPC.cbl}) repeat verbatim &mdash; most representatively in
 * {@code 1245-EDIT-NUM-REQD}, which chains blank &rarr; {@code "must be supplied."}, non-numeric
 * &rarr; {@code "must be all numeric."}, and zero &rarr; {@code "must not be zero."}.</p>
 *
 * <p>Only the {@code static} primitives are exercised here; the single abstract
 * {@link ValidationRule#validate(String, String)} Strategy method is exercised via each concrete
 * rule's own test, not in this class. This is a pure JUnit&nbsp;5 + AssertJ unit test &mdash; no
 * Spring context, no Mockito, and no database.</p>
 */
class ValidationRuleTest {

    /**
     * {@link ValidationRule#isBlank(String)} reports "not supplied" for {@code null} (COBOL
     * {@code LOW-VALUES}), the empty string, and an all-whitespace string ({@code SPACES} / a
     * {@code FUNCTION TRIM} length of zero).
     */
    @Test
    void isBlank_trueForNullEmptyAndSpaces() {
        assertThat(ValidationRule.isBlank(null)).isTrue();
        assertThat(ValidationRule.isBlank("")).isTrue();
        assertThat(ValidationRule.isBlank("   ")).isTrue();
    }

    /**
     * {@link ValidationRule#isBlank(String)} is {@code false} when any non-whitespace content is
     * present, even when the value is surrounded by padding spaces.
     */
    @Test
    void isBlank_falseWhenContentPresent() {
        assertThat(ValidationRule.isBlank(" x ")).isFalse();
    }

    /**
     * {@link ValidationRule#isAllDigits(String)} is {@code true} for a non-empty string of ASCII
     * digits (leading zeros are digits), reproducing COBOL {@code IS NUMERIC} for an unsigned
     * {@code PIC 9} display item.
     */
    @Test
    void isAllDigits_trueForAsciiDigitsOnly() {
        assertThat(ValidationRule.isAllDigits("007")).isTrue();
    }

    /**
     * {@link ValidationRule#isAllDigits(String)} is {@code false} for the empty string, {@code null},
     * a trailing space, an embedded letter, and an explicit sign character &mdash; documenting the
     * ASCII-only, no-embedded-space parity with COBOL {@code IS NUMERIC} (a fixed-width buffer slice
     * where any non-digit byte makes the item non-numeric).
     */
    @Test
    void isAllDigits_falseForEmptyNullSpaceLetterAndSign() {
        assertThat(ValidationRule.isAllDigits("")).isFalse();
        assertThat(ValidationRule.isAllDigits(null)).isFalse();
        assertThat(ValidationRule.isAllDigits("12 ")).isFalse();
        assertThat(ValidationRule.isAllDigits("1a")).isFalse();
        assertThat(ValidationRule.isAllDigits("-1")).isFalse();
    }

    /**
     * {@link ValidationRule#isZeroValue(String)} is {@code true} only when every character of an
     * already-numeric string is {@code '0'}, reproducing {@code FUNCTION NUMVAL(...) = 0}; a value
     * containing any non-zero digit is not zero.
     */
    @Test
    void isZeroValue_trueForAllZerosFalseForNonZero() {
        assertThat(ValidationRule.isZeroValue("000")).isTrue();
        assertThat(ValidationRule.isZeroValue("001")).isFalse();
    }

    /**
     * {@link ValidationRule#label(String)} strips leading and trailing whitespace, reproducing
     * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)}, and null-guards a {@code null} label to the empty
     * string so callers can build a message without a null check.
     */
    @Test
    void label_trimsAndNullSafe() {
        assertThat(ValidationRule.label("  State  ")).isEqualTo("State");
        assertThat(ValidationRule.label(null)).isEqualTo("");
    }
}
