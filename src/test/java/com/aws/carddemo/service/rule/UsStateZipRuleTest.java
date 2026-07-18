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
 * Unit tests for {@link UsStateZipRule}, the cross-field state/zip-code edit that re-platforms
 * COBOL paragraph {@code 1280-EDIT-US-STATE-ZIP-CD} ({@code legacy/cbl/COACTUPC.cbl}, source-branch
 * {@code app/cbl/COACTUPC.cbl}) together with its {@code VALID-US-STATE-ZIP-CD2-COMBO} lookup
 * ({@code legacy/cpy/CSLKPCDY.cpy}, source-branch {@code app/cpy/CSLKPCDY.cpy}).
 *
 * <p>The legacy paragraph assembles a four-character key by concatenating the two-character state
 * code with the first two characters of the zip code
 * ({@code STRING state ZIP(1:2) INTO US-STATE-AND-FIRST-ZIP2}); when that key is not a member of
 * the 240-entry combination set it latches the fixed screen message
 * {@code 'Invalid zip code for state'} at {@code COACTUPC.cbl:L2550} &mdash; a literal that carries
 * no field-label prefix and no trailing period.</p>
 *
 * <p>These tests assert behavioral parity (AAP &sect;0.9.2 field-contract parity; &sect;0.7.1
 * hotspot H2): a recognized combination passes with an empty message; an unrecognized one fails
 * with the <em>exact</em> fixed message; and the defensive guard treats {@code null} or too-short
 * input as invalid rather than throwing. The four representative keys exercise both membership
 * outcomes ({@code TX75}/{@code CA90} are in the set; {@code TX99}/{@code NY00} are not), and the
 * null/short cases exercise every branch of the rule's guard. This is a pure JUnit&nbsp;5 + AssertJ
 * unit test &mdash; the rule is stateless, so it is exercised via {@code new UsStateZipRule()} with
 * no Spring context, no Mockito, and no database.</p>
 */
class UsStateZipRuleTest {

    /**
     * The exact fixed screen message the legacy paragraph latches on failure
     * ({@code COACTUPC.cbl:L2550}); no field-label prefix and no trailing period. Every failing
     * assertion below checks against this literal to lock the parity contract.
     */
    private static final String INVALID_MESSAGE = "Invalid zip code for state";

    /** The rule under test; stateless, so a single instance is reused across all cases. */
    private final UsStateZipRule rule = new UsStateZipRule();

    @Test
    void validTexasCombinationTx75Passes() {
        // Key "TX" + "75" = "TX75" is a member of VALID-US-STATE-ZIP-CD2-COMBO.
        ValidationResult result = rule.validate("TX", "75001");

        assertThat(result.isValid()).isTrue();
        assertThat(result.isInvalid()).isFalse();
        assertThat(result.message()).isEmpty();
    }

    @Test
    void unrecognizedTexasCombinationTx99FailsWithFixedMessage() {
        // Key "TX" + "99" = "TX99" is NOT a member of the combination set.
        ValidationResult result = rule.validate("TX", "99999");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.isValid()).isFalse();
        assertThat(result.message()).isEqualTo(INVALID_MESSAGE);
    }

    @Test
    void validCaliforniaCombinationCa90Passes() {
        // Key "CA" + "90" = "CA90" is a member of the combination set.
        ValidationResult result = rule.validate("CA", "90210");

        assertThat(result.isValid()).isTrue();
        assertThat(result.message()).isEmpty();
    }

    @Test
    void unrecognizedNewYorkCombinationNy00FailsWithFixedMessage() {
        // Key "NY" + "00" = "NY00" is NOT a member of the combination set.
        ValidationResult result = rule.validate("NY", "00000");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(INVALID_MESSAGE);
    }

    @Test
    void nullStateCodeIsInvalidWithFixedMessage() {
        // Defensive guard: a null state code yields the fixed message and never throws.
        ValidationResult result = rule.validate(null, "75001");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(INVALID_MESSAGE);
    }

    @Test
    void nullZipCodeIsInvalidWithFixedMessage() {
        // Defensive guard: a null zip code yields the fixed message and never throws.
        ValidationResult result = rule.validate("TX", null);

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(INVALID_MESSAGE);
    }

    @Test
    void tooShortInputsAreInvalidWithFixedMessage() {
        // Defensive guard: inputs shorter than two characters cannot form a key, so the rule
        // returns the fixed message rather than raising a substring exception.
        ValidationResult result = rule.validate("T", "7");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(INVALID_MESSAGE);
    }

    @Test
    void shortZipCodeWithValidStateIsInvalidWithFixedMessage() {
        // Guard branch where the state is long enough but the zip is too short to form the key.
        ValidationResult result = rule.validate("TX", "7");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(INVALID_MESSAGE);
    }

    @Test
    void invalidMessageHasNoFieldPrefixAndNoTrailingPeriod() {
        // Parity lock: the failure message is the verbatim COBOL literal - no "<field>:" prefix
        // (hence no colon) and no trailing period.
        String message = rule.validate("TX", "99999").message();

        assertThat(message).isEqualTo(INVALID_MESSAGE);
        assertThat(message).doesNotContain(":");
        assertThat(message).doesNotEndWith(".");
    }
}
