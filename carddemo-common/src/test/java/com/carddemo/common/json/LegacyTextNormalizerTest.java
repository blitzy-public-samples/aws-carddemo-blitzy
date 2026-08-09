/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.common.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * :purpose: Pin the text normalisation to what a fixed-width ``PIC X(n)`` field can hold:
 *     the direction-altering and zero-width characters are gone, the control characters a
 *     COBOL class test still has to see are untouched, and printable text is returned
 *     unchanged and un-reallocated.
 * :output: JUnit assertions only.
 */
class LegacyTextNormalizerTest {

    /** :purpose: RIGHT-TO-LEFT OVERRIDE — the character that reversed rendered text. */
    private static final String RLO = "\u202E";

    /** :purpose: ZERO WIDTH SPACE — invisible, and it occupied a field position. */
    private static final String ZWSP = "\u200B";

    @Test
    @DisplayName("a bidi override is removed, so the remainder cannot render reversed")
    void removesBidiOverride() {
        assertThat(LegacyTextNormalizer.normalize("test" + RLO + "evil")).isEqualTo("testevil");
    }

    @Test
    @DisplayName("every bidi control of the embedding and isolate families is removed")
    void removesEveryBidiControl() {
        String payload = "A\u202AB\u202BC\u202CD\u202DE" + RLO + "F\u200EG\u200FH"
                + "\u2066I\u2067J\u2068K\u2069L\u2060M\uFEFFN";
        assertThat(LegacyTextNormalizer.normalize(payload)).isEqualTo("ABCDEFGHIJKLMN");
    }

    @Test
    @DisplayName("repeated zero-width spaces are removed rather than occupying field positions")
    void removesZeroWidthSpaces() {
        String padded = "AB" + ZWSP.repeat(10) + "CD";
        assertThat(padded).hasSize(14);
        assertThat(LegacyTextNormalizer.normalize(padded)).isEqualTo("ABCD").hasSize(4);
    }

    @Test
    @DisplayName("a decomposed sequence is composed, which is also what makes it fit its width")
    void composesToNfc() {
        String decomposed = "Jose\u0301";
        assertThat(Normalizer.isNormalized(decomposed, Normalizer.Form.NFC)).isFalse();
        String normalized = LegacyTextNormalizer.normalize(decomposed);
        assertThat(normalized).isEqualTo("Jos\u00E9").hasSize(4);
        assertThat(Normalizer.isNormalized(normalized, Normalizer.Form.NFC)).isTrue();
    }

    @Test
    @DisplayName("a control character is LEFT IN PLACE for the COBOL class tests to refuse")
    void keepsControlCharacters() {
        // ``COTRN00C`` and ``COCRDLIC`` test the RAW map field with ``IS NUMERIC``, and a
        // filter holding a tab must fail that test rather than become an unfiltered browse.
        assertThat(LegacyTextNormalizer.normalize("\t")).isEqualTo("\t");
        assertThat(LegacyTextNormalizer.normalize(" 15 ")).isEqualTo(" 15 ");
        assertThat(LegacyTextNormalizer.normalize("1\n5")).isEqualTo("1\n5");
    }

    @Test
    @DisplayName("printable text is returned as the SAME instance, so a clean value costs nothing")
    void returnsSameInstanceForCleanText() {
        String clean = "Immanuel Kessler";
        assertThat(LegacyTextNormalizer.normalize(clean)).isSameAs(clean);
    }

    @Test
    @DisplayName("a frozen legacy literal passes through untouched")
    void leavesFrozenLiteralsAlone() {
        String literal = "Record changed by some one else. Please review";
        assertThat(LegacyTextNormalizer.normalize(literal)).isSameAs(literal);
        String picture = "+0000000194.00";
        assertThat(LegacyTextNormalizer.normalize(picture)).isSameAs(picture);
    }

    @Test
    @DisplayName("null and empty are returned as they arrived")
    void passesThroughNullAndEmpty() {
        assertThat(LegacyTextNormalizer.normalize(null)).isNull();
        assertThat(LegacyTextNormalizer.normalize("")).isEmpty();
    }

    @Test
    @DisplayName("a value that is nothing but invisible characters becomes empty")
    void reducesInvisibleOnlyValueToEmpty() {
        assertThat(LegacyTextNormalizer.normalize(ZWSP + RLO + "\uFEFF")).isEmpty();
    }

    @Test
    @DisplayName("a supplementary-plane character survives, so the scan is code-point safe")
    void keepsSupplementaryCharacters() {
        String withEmoji = "A\uD83D\uDE00B";
        assertThat(LegacyTextNormalizer.normalize(withEmoji)).isEqualTo(withEmoji);
        assertThat(LegacyTextNormalizer.normalize("A\uD83D\uDE00" + ZWSP + "B"))
                .isEqualTo(withEmoji);
    }
}
