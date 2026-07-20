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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link YesNoRule}, the Java migration of COBOL edit paragraph
 * {@code 1220-EDIT-YESNO} (see {@code legacy/cbl/COACTUPC.cbl:L1856-L1896}, source-branch
 * {@code app/cbl/COACTUPC.cbl}).
 *
 * <p>COBOL evidence exercised (verified against {@code app/cbl/COACTUPC.cbl}):
 * <ul>
 *   <li>Not-supplied branch at L1861-1863 &mdash; {@code WS-EDIT-YES-NO EQUAL LOW-VALUES OR
 *       SPACES OR ZEROS} builds {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) ' must be supplied.'}
 *       (L1867-1872). The {@code ZEROS} clause means a lone {@code "0"} is treated as
 *       <em>not supplied</em>, not as an invalid character &mdash; this parity quirk is asserted
 *       explicitly.</li>
 *   <li>Valid-value branch at L1878 &mdash; the 88-level {@code FLG-YES-NO-ISVALID}
 *       ({@code VALUES 'Y','N'}, L78) is case-exact; a failing value builds
 *       {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) ' must be Y or N.'} (L1884-1889).</li>
 * </ul>
 *
 * <p>The representative field label {@code "Account Active Status"} stands in for the COBOL
 * {@code WS-EDIT-VARIABLE-NAME} the paragraph's callers supply (for example {@code 'Account
 * Status'} at L1472 and {@code 'Primary Card Holder'} at L1657). This is a pure JUnit&nbsp;5 +
 * AssertJ unit test &mdash; no Spring context, no Mockito, and no database.
 */
class YesNoRuleTest {

    /** The representative field label substituted into each outcome message. */
    private static final String FIELD = "Account Active Status";

    /** Exact message emitted by the COBOL not-supplied branch (L1867-1872). */
    private static final String MUST_BE_SUPPLIED = "Account Active Status must be supplied.";

    /** Exact message emitted by the COBOL invalid-value branch (L1884-1889). */
    private static final String MUST_BE_Y_OR_N = "Account Active Status must be Y or N.";

    private final YesNoRule rule = new YesNoRule();

    @Test
    @DisplayName("(a) null value -> not supplied (COBOL LOW-VALUES)")
    void nullValue_isNotSupplied() {
        ValidationResult r = rule.validate(FIELD, null);

        assertThat(r.isInvalid()).isTrue();
        assertThat(r.message()).isEqualTo(MUST_BE_SUPPLIED);
    }

    @ParameterizedTest
    @DisplayName("(a) empty / whitespace value -> not supplied (COBOL SPACES)")
    @ValueSource(strings = {"", " ", "   "})
    void blankValue_isNotSupplied(String value) {
        ValidationResult r = rule.validate(FIELD, value);

        assertThat(r.isInvalid()).isTrue();
        assertThat(r.message()).isEqualTo(MUST_BE_SUPPLIED);
    }

    @ParameterizedTest
    @DisplayName("(b) all-zeros value -> not supplied, NOT the Y/N message (COBOL ZEROS parity)")
    @ValueSource(strings = {"0", "00", "000"})
    void zerosValue_isNotSupplied(String value) {
        ValidationResult r = rule.validate(FIELD, value);

        assertThat(r.isInvalid()).isTrue();
        assertThat(r.message())
                .isEqualTo(MUST_BE_SUPPLIED)
                .isNotEqualTo(MUST_BE_Y_OR_N);
    }

    @Test
    @DisplayName("(c) \"Y\" -> valid")
    void upperY_isValid() {
        ValidationResult r = rule.validate(FIELD, "Y");

        assertThat(r.isValid()).isTrue();
        assertThat(r.message()).isEmpty();
    }

    @Test
    @DisplayName("(d) \"N\" -> valid")
    void upperN_isValid() {
        ValidationResult r = rule.validate(FIELD, "N");

        assertThat(r.isValid()).isTrue();
        assertThat(r.message()).isEmpty();
    }

    @Test
    @DisplayName("(e) lowercase \"y\" -> must be Y or N (88-level is case-exact)")
    void lowerY_isInvalid() {
        ValidationResult r = rule.validate(FIELD, "y");

        assertThat(r.isInvalid()).isTrue();
        assertThat(r.message()).isEqualTo(MUST_BE_Y_OR_N);
    }

    @Test
    @DisplayName("lowercase \"n\" -> must be Y or N (88-level is case-exact)")
    void lowerN_isInvalid() {
        ValidationResult r = rule.validate(FIELD, "n");

        assertThat(r.isInvalid()).isTrue();
        assertThat(r.message()).isEqualTo(MUST_BE_Y_OR_N);
    }

    @Test
    @DisplayName("(f) \"X\" -> must be Y or N")
    void otherChar_isInvalid() {
        ValidationResult r = rule.validate(FIELD, "X");

        assertThat(r.isInvalid()).isTrue();
        assertThat(r.message()).isEqualTo(MUST_BE_Y_OR_N);
    }

    @ParameterizedTest
    @DisplayName("fixed-width trailing/leading padding around Y or N is stripped -> valid")
    @ValueSource(strings = {"Y ", " Y", "  Y  ", "N ", " N", "\tN\t"})
    void paddedYesNo_isValid(String value) {
        ValidationResult r = rule.validate(FIELD, value);

        assertThat(r.isValid()).isTrue();
        assertThat(r.message()).isEmpty();
    }

    @Test
    @DisplayName("multi-character non-flag value (\"YES\") -> must be Y or N")
    void multiCharWord_isInvalid() {
        ValidationResult r = rule.validate(FIELD, "YES");

        assertThat(r.isInvalid()).isTrue();
        assertThat(r.message()).isEqualTo(MUST_BE_Y_OR_N);
    }

    @Test
    @DisplayName("field label is trimmed before substitution (FUNCTION TRIM parity)")
    void fieldLabel_isTrimmed() {
        ValidationResult r = rule.validate("  Account Active Status  ", "X");

        assertThat(r.message()).isEqualTo(MUST_BE_Y_OR_N);
    }

    @ParameterizedTest
    @DisplayName("null or blank field label yields an empty label and never throws")
    @NullSource
    @ValueSource(strings = {"", "   "})
    void nullOrBlankLabel_doesNotThrow(String fieldName) {
        ValidationResult r = rule.validate(fieldName, "X");

        assertThat(r.isInvalid()).isTrue();
        assertThat(r.message()).isEqualTo(" must be Y or N.");
    }
}
