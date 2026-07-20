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
 * Unit tests for {@link AlphanumRequiredRule}, the Java migration of COBOL edit paragraph
 * {@code 1230-EDIT-ALPHANUM-REQD} in {@code legacy/cbl/COACTUPC.cbl}
 * (source {@code app/cbl/COACTUPC.cbl:L1955-L2009}).
 *
 * <p>The rule reproduces, with verbatim behavioral parity, the two ordered, short-circuiting
 * checks the legacy online account-update program applies to a mandatory free-text field such as
 * an address line:</p>
 * <ol>
 *   <li><em>Not-supplied (blank) test &mdash; first.</em> A {@code null} value (COBOL
 *       {@code LOW-VALUES}), an empty string, or an all-whitespace string (COBOL {@code SPACES} /
 *       a {@code FUNCTION TRIM} length of zero) fails with the message
 *       {@code "<field> must be supplied."} ({@code legacy/cbl/COACTUPC.cbl:L1960-L1978}).</li>
 *   <li><em>Alphanumeric-only test &mdash; second.</em> A supplied value that contains any
 *       character outside the ASCII set of letters, digits, and spaces fails with the message
 *       {@code "<field> can have numbers or alphabets only."}
 *       ({@code legacy/cbl/COACTUPC.cbl:L1981-L2005}).</li>
 * </ol>
 * <p>A supplied value composed solely of letters, digits, and spaces is valid and carries the
 * empty message (mirroring the COBOL {@code WS-RETURN-MSG-OFF} state on the
 * {@link ValidationResult} returned by {@link AlphanumRequiredRule#validate(String, String)}).</p>
 *
 * <p>The rule is stateless, so it is exercised through a directly constructed instance
 * ({@code new AlphanumRequiredRule()}) with no Spring context, no mock, and no database. The
 * failure messages are asserted <em>verbatim</em> because they are a caller-visible parity
 * contract, and the field label {@code "Address Line 1"} (which contains no leading or trailing
 * whitespace) is echoed unchanged by the {@link ValidationRule#label(String)} trim.</p>
 */
class AlphanumRequiredRuleTest {

    /**
     * The rule under test. It is stateless and immutable, so a single directly constructed
     * instance is reused across every test method.
     */
    private final AlphanumRequiredRule rule = new AlphanumRequiredRule();

    /**
     * Verifies the not-supplied (blank) check, which runs first and short-circuits: a
     * {@code null} value ({@code LOW-VALUES}), an empty string, and an all-whitespace string
     * ({@code SPACES} / a trim length of zero) each fail with the verbatim message
     * {@code "Address Line 1 must be supplied."} ({@code legacy/cbl/COACTUPC.cbl:L1960-L1978}).
     */
    @Test
    void blankValueIsNotSupplied() {
        for (String value : new String[] {null, "", "   "}) {
            ValidationResult result = rule.validate("Address Line 1", value);
            assertThat(result.isValid())
                    .as("blank value [" + value + "] must be invalid")
                    .isFalse();
            assertThat(result.isInvalid())
                    .as("blank value [" + value + "] must report invalid")
                    .isTrue();
            assertThat(result.message())
                    .as("blank value [" + value + "] message")
                    .isEqualTo("Address Line 1 must be supplied.");
        }
    }

    /**
     * Verifies that a supplied value containing only letters, digits, and spaces
     * ({@code "123 Main St"}) passes both checks and is valid.
     */
    @Test
    void lettersDigitsAndSpacesAreValid() {
        ValidationResult result = rule.validate("Address Line 1", "123 Main St");
        assertThat(result.isValid())
                .as("letters, digits and spaces are all inside the allowed set")
                .isTrue();
        assertThat(result.message())
                .as("a valid result carries the empty message")
                .isEmpty();
    }

    /**
     * Verifies the alphanumeric-only check rejects a value containing a hash character
     * ({@code "Apt#4"}) with the verbatim message
     * {@code "Address Line 1 can have numbers or alphabets only."}
     * ({@code legacy/cbl/COACTUPC.cbl:L1981-L2005}).
     */
    @Test
    void hashCharacterIsInvalid() {
        ValidationResult result = rule.validate("Address Line 1", "Apt#4");
        assertThat(result.isValid())
                .as("'#' is neither an ASCII alphanumeric character nor a space")
                .isFalse();
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message())
                .as("message must match COBOL WS-RETURN-MSG verbatim")
                .isEqualTo("Address Line 1 can have numbers or alphabets only.");
    }

    /**
     * Verifies that a supplied value composed of adjacent letters and digits ({@code "ABC123"})
     * passes both checks and is valid.
     */
    @Test
    void lettersAndDigitsAreValid() {
        ValidationResult result = rule.validate("Address Line 1", "ABC123");
        assertThat(result.isValid())
                .as("adjacent letters and digits are all inside the allowed set")
                .isTrue();
        assertThat(result.message())
                .as("a valid result carries the empty message")
                .isEmpty();
    }

    /**
     * Verifies the alphanumeric-only check rejects a value containing a hyphen ({@code "12-34"})
     * with the verbatim message {@code "Address Line 1 can have numbers or alphabets only."}
     * ({@code legacy/cbl/COACTUPC.cbl:L1981-L2005}).
     */
    @Test
    void hyphenIsInvalid() {
        ValidationResult result = rule.validate("Address Line 1", "12-34");
        assertThat(result.isValid())
                .as("'-' is neither an ASCII alphanumeric character nor a space")
                .isFalse();
        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message())
                .as("message must match COBOL WS-RETURN-MSG verbatim")
                .isEqualTo("Address Line 1 can have numbers or alphabets only.");
    }
}
