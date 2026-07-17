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

import java.lang.reflect.Field;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UsStateZipRule}, the cross-field state/zip-code edit that re-platforms
 * COBOL paragraph {@code 1280-EDIT-US-STATE-ZIP-CD} ({@code legacy/cbl/COACTUPC.cbl},
 * source-branch {@code app/cbl/COACTUPC.cbl}, at {@code L2536-L2558}) and its
 * {@code VALID-US-STATE-ZIP-CD2-COMBO} lookup ({@code legacy/cpy/CSLKPCDY.cpy}, source-branch
 * {@code app/cpy/CSLKPCDY.cpy}, at {@code L1073-L1313}).
 *
 * <p>COBOL evidence reproduced (verified against {@code app/cbl/COACTUPC.cbl}):
 * <ul>
 *   <li>The paragraph {@code STRING}s the two-character state code with the first two zip
 *       characters ({@code ACUP-NEW-CUST-ADDR-ZIP(1:2)}) into the four-character key
 *       {@code US-STATE-AND-FIRST-ZIP2} (L2537-L2540).</li>
 *   <li>When the key is not a {@code VALID-US-STATE-ZIP-CD2-COMBO} member it latches the fixed
 *       screen message {@code 'Invalid zip code for state'} (L2550) &mdash; a literal with no
 *       field-label prefix and no trailing period.</li>
 * </ul>
 *
 * <p>These tests assert behavioral parity (AAP &sect;0.9.2 field-contract parity; &sect;0.7.1 hotspot
 * H2): a recognized combination passes; an unrecognized one fails with the <em>exact</em> fixed
 * message; and the defensive guard treats {@code null}/short input as invalid rather than throwing.
 * A dedicated test also pins the embedded lookup to exactly 240 entries, matching the copybook, so
 * the {@link java.util.Set#of(Object...)} view can never silently lose or gain a combination. This
 * is a pure JUnit&nbsp;5 + AssertJ unit test &mdash; no Spring context, no Mockito, no database
 * (mirroring {@code ValidationResultTest} / {@code ValidationRuleTest}).
 */
class UsStateZipRuleTest {

    /**
     * The exact fixed screen message the legacy paragraph latches on failure ({@code L2550}); no
     * field-label prefix and no trailing period. Every failing assertion below checks against this
     * literal to lock the parity contract.
     */
    private static final String INVALID_MESSAGE = "Invalid zip code for state";

    /** The rule under test; stateless, so a single instance is reused across all cases. */
    private final UsStateZipRule rule = new UsStateZipRule();

    @Test
    @DisplayName("Valid TX combination (TX75) passes with an empty message")
    void validTexasCombinationPasses() {
        ValidationResult result = rule.validate("TX", "75001");

        assertThat(result.isValid()).isTrue();
        assertThat(result.isInvalid()).isFalse();
        assertThat(result.message()).isEmpty();
    }

    @Test
    @DisplayName("Unrecognized TX combination (TX99) fails with the exact fixed message")
    void invalidTexasCombinationFails() {
        ValidationResult result = rule.validate("TX", "99999");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(INVALID_MESSAGE);
    }

    @Test
    @DisplayName("Valid CA combination (CA90) passes")
    void validCaliforniaCombinationPasses() {
        ValidationResult result = rule.validate("CA", "90210");

        assertThat(result.isValid()).isTrue();
        assertThat(result.message()).isEmpty();
    }

    @Test
    @DisplayName("Unrecognized NY combination (NY00) fails with the exact fixed message")
    void invalidNewYorkCombinationFails() {
        ValidationResult result = rule.validate("NY", "00000");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(INVALID_MESSAGE);
    }

    @Test
    @DisplayName("Only the first two zip characters form the key, so a longer zip still matches (TX75)")
    void onlyFirstTwoZipCharactersFormTheKey() {
        ValidationResult result = rule.validate("TX", "7500199999");

        assertThat(result.isValid()).isTrue();
        assertThat(result.message()).isEmpty();
    }

    @Test
    @DisplayName("Leading/trailing whitespace is stripped before the key is built (' TX ',' 75001 ' -> TX75)")
    void surroundingWhitespaceIsStripped() {
        ValidationResult result = rule.validate(" TX ", " 75001 ");

        assertThat(result.isValid()).isTrue();
        assertThat(result.message()).isEmpty();
    }

    @Test
    @DisplayName("A null state code is treated as invalid with the same fixed message (no throw)")
    void nullStateCodeIsInvalid() {
        ValidationResult result = rule.validate(null, "75001");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(INVALID_MESSAGE);
    }

    @Test
    @DisplayName("A null zip code is treated as invalid with the same fixed message (no throw)")
    void nullZipCodeIsInvalid() {
        ValidationResult result = rule.validate("TX", null);

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(INVALID_MESSAGE);
    }

    @Test
    @DisplayName("A state code shorter than two characters is invalid (no substring exception)")
    void shortStateCodeIsInvalid() {
        ValidationResult result = rule.validate("T", "75001");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(INVALID_MESSAGE);
    }

    @Test
    @DisplayName("A zip code shorter than two characters is invalid (no substring exception)")
    void shortZipCodeIsInvalid() {
        ValidationResult result = rule.validate("TX", "7");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(INVALID_MESSAGE);
    }

    @Test
    @DisplayName("Both inputs blank are treated as invalid with the same fixed message (no throw)")
    void blankInputsAreInvalid() {
        ValidationResult result = rule.validate("  ", "     ");

        assertThat(result.isInvalid()).isTrue();
        assertThat(result.message()).isEqualTo(INVALID_MESSAGE);
    }

    @Test
    @DisplayName("The embedded VALID-US-STATE-ZIP-CD2-COMBO set has exactly 240 entries (copybook parity)")
    void comboSetHasExactly240Entries() throws ReflectiveOperationException {
        Field field = UsStateZipRule.class.getDeclaredField("VALID_STATE_ZIP2_COMBOS");
        field.setAccessible(true);
        // Cast to Set<?> (wildcard) to keep the reflective read free of unchecked-cast warnings.
        Set<?> combos = (Set<?>) field.get(null);

        assertThat(combos).hasSize(240);
        // Spot-check representative members/non-members that back the behavioral cases above.
        // Set#contains(Object) is used (rather than AssertJ's element varargs) so the reflective
        // read stays a wildcard Set<?> with no unchecked-cast warning under -Xlint:all.
        assertThat(combos.contains("TX75")).isTrue();
        assertThat(combos.contains("CA90")).isTrue();
        assertThat(combos.contains("TX99")).isFalse();
        assertThat(combos.contains("NY00")).isFalse();
    }
}
