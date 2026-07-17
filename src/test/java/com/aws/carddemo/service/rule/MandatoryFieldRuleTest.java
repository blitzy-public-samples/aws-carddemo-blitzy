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
 * Unit tests for {@link MandatoryFieldRule}, the Java migration of the COBOL edit paragraph
 * {@code 1215-EDIT-MANDATORY} in {@code legacy/cbl/COACTUPC.cbl} (source-branch
 * {@code app/cbl/COACTUPC.cbl:L1824-L1854}).
 *
 * <p>COBOL evidence (verified against {@code app/cbl/COACTUPC.cbl}): the paragraph flags a field
 * as an input error when its buffer slice is {@code LOW-VALUES}, {@code SPACES}, or trims to a
 * length of zero, and then builds the screen message
 * {@code STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) ' must be supplied.' ... INTO WS-RETURN-MSG}
 * (L1839-1843); otherwise it sets {@code FLG-MANDATORY-ISVALID}. These tests lock down that
 * parity: a {@code null}, all-whitespace, or empty value is invalid with the exact message
 * {@code "<field> must be supplied."}, any other value is valid, and the field label is trimmed
 * into the message. The {@code " must be supplied."} literal is a preserved parity contract and is
 * asserted verbatim. This is a pure JUnit&nbsp;5 + AssertJ unit test &mdash; no Spring context, no
 * Mockito, and no database (the rule is stateless).
 */
class MandatoryFieldRuleTest {

    /** The stateless rule under test; safe to reuse across cases. */
    private final MandatoryFieldRule rule = new MandatoryFieldRule();

    @Test
    @DisplayName("null value is invalid and yields '<field> must be supplied.' (COBOL LOW-VALUES)")
    void nullValue_isInvalidWithMessage() {
        ValidationResult result = rule.validate("Foo", null);

        assertThat(result.isValid()).isFalse();
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Foo must be supplied.");
    }

    @Test
    @DisplayName("all-spaces value is invalid and yields '<field> must be supplied.' (COBOL SPACES)")
    void allSpacesValue_isInvalidWithMessage() {
        ValidationResult result = rule.validate("Foo", "     ");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Foo must be supplied.");
    }

    @Test
    @DisplayName("empty value is invalid and yields '<field> must be supplied.' (TRIM length 0)")
    void emptyValue_isInvalidWithMessage() {
        ValidationResult result = rule.validate("Foo", "");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Foo must be supplied.");
    }

    @Test
    @DisplayName("a supplied value passes and carries the empty (never-null) message")
    void suppliedValue_isValidWithEmptyMessage() {
        ValidationResult result = rule.validate("Foo", "X");

        assertThat(result.isValid()).isTrue();
        assertThat(result.isInvalid()).isFalse();
        assertThat(result.message()).isEqualTo("");
    }

    @Test
    @DisplayName("a padded-but-non-blank value passes (only fully blank input is 'not supplied')")
    void paddedNonBlankValue_isValid() {
        ValidationResult result = rule.validate("Foo", "  x  ");

        assertThat(result.isValid()).isTrue();
        assertThat(result.message()).isEqualTo("");
    }

    @Test
    @DisplayName("field label is trimmed into the message (FUNCTION TRIM(WS-EDIT-VARIABLE-NAME))")
    void fieldLabel_isTrimmedIntoMessage() {
        ValidationResult result = rule.validate("  Foo  ", null);

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo("Foo must be supplied.");
    }

    @Test
    @DisplayName("a null field label degrades to the empty label, preserving ' must be supplied.'")
    void nullFieldLabel_yieldsBareMessage() {
        ValidationResult result = rule.validate(null, null);

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(" must be supplied.");
    }
}
