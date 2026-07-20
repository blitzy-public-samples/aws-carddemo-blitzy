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
package com.aws.carddemo.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PanMasker}, verifying the PCI-DSS first-6/last-4 masking rule, the null-safe
 * behavior, and the short-input full-mask boundary used for batch operational logging.
 */
class PanMaskerTest {

    @Test
    void masksMiddleOfCanonicalSixteenDigitPan() {
        // 16 chars: 6 visible + 6 masked + 4 visible.
        assertThat(PanMasker.mask("9999999999999999")).isEqualTo("999999******9999");
    }

    @Test
    void preservesFirstSixAndLastFourDigits() {
        String masked = PanMasker.mask("4859452612877065");
        assertThat(masked).startsWith("485945");
        assertThat(masked).endsWith("7065");
        assertThat(masked).hasSize(16);
        // The full PAN must not survive anywhere in the output.
        assertThat(masked).doesNotContain("4859452612877065");
        assertThat(masked).contains("******");
    }

    @Test
    void neverEmitsTheFullPan() {
        String pan = "4111111111111111";
        assertThat(PanMasker.mask(pan)).doesNotContain(pan);
    }

    @Test
    void returnsNullTokenForNullInput() {
        assertThat(PanMasker.mask(null)).isEqualTo("null");
    }

    @Test
    void masksEntireValueBelowRevealThreshold() {
        // 10 chars == VISIBLE_PREFIX + VISIBLE_SUFFIX: revealing both windows would expose everything,
        // so the whole value is masked.
        assertThat(PanMasker.mask("0123456789")).isEqualTo("**********");
        assertThat(PanMasker.mask("0123456789")).hasSize(10);
    }

    @Test
    void revealsWindowsAtExactlyElevenCharacters() {
        // 11 chars is the smallest length that reveals the windows with a single masked character.
        String masked = PanMasker.mask("01234567890");
        assertThat(masked).isEqualTo("012345*7890");
        assertThat(masked).hasSize(11);
    }

    @Test
    void handlesEmptyStringWithoutThrowing() {
        assertThat(PanMasker.mask("")).isEmpty();
    }

    @Test
    void maskedLengthAlwaysEqualsInputLength() {
        String pan = "1234567890123456789"; // 19-digit (max ISO PAN)
        assertThat(PanMasker.mask(pan)).hasSize(pan.length());
    }
}
