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
package com.carddemo.common.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for :class:`FixedWidthText`.
 *
 * :purpose: Verify the invariant every fixed-width CardDemo artefact depends on: one
 *     character encodes to exactly one ISO-8859-1 byte, so padding a value to a declared
 *     character width produces exactly that many BYTES. The legacy layouts are declared in
 *     bytes (``DALYREJS`` ``LRECL=430``, ``FD-STMTFILE-REC PIC X(80)``,
 *     ``HTML-FIXED-LN PIC X(100)``, ``FD-REPTFILE-REC PIC X(133)``), so a character count
 *     that diverges from the byte count breaks every offset-based downstream reader
 *.
 * :output: JUnit 5 / AssertJ assertions over the byte length of the encoded result.
 */
@DisplayName("FixedWidthText")
class FixedWidthTextTest {

    /**
     * :purpose: ASCII content is returned unchanged, so no byte of existing output moves.
     */
    @Test
    @DisplayName("ASCII text is returned unchanged")
    void asciiIsUnchanged() {
        String value = "Purchase at Corner Store 12345";

        assertThat(FixedWidthText.toSingleByteText(value)).isSameAs(value);
    }

    /**
     * :purpose: A ``null`` is passed through so each caller keeps its own COBOL ``MOVE``
     *     semantics (spaces for alphanumeric, zeros for numeric).
     */
    @Test
    @DisplayName("null is passed through")
    void nullIsPassedThrough() {
        assertThat(FixedWidthText.toSingleByteText(null)).isNull();
    }

    /**
     * :purpose: A Latin-1 character stays itself and occupies exactly one byte, so an
     *     accented merchant name no longer lengthens the record as it did under UTF-8.
     */
    @Test
    @DisplayName("a Latin-1 character survives and occupies exactly one byte")
    void latin1CharacterIsOneByte() {
        String sanitized = FixedWidthText.toSingleByteText("Café Müller");

        assertThat(sanitized).isEqualTo("Café Müller");
        assertThat(sanitized).hasSize(11);
        assertThat(sanitized.getBytes(StandardCharsets.ISO_8859_1)).hasSize(11);
    }

    /**
     * :purpose: A code point outside Latin-1 becomes a single substitute character, so the
     *     character count still equals the byte count.
     */
    @Test
    @DisplayName("a non-Latin-1 basic-plane character becomes one substitute character")
    void basicPlaneCharacterOutsideLatin1IsSubstituted() {
        String sanitized = FixedWidthText.toSingleByteText("\u4E2D\u6587");

        assertThat(sanitized).isEqualTo("??");
        assertThat(sanitized.getBytes(StandardCharsets.ISO_8859_1)).hasSize(2);
    }

    /**
     * :purpose: A supplementary code point — two Java characters, one ISO-8859-1 byte —
     *     collapses to ONE substitute character. Without this the padded record came out
     *     SHORTER than its declared byte length even with the encoding pinned.
     */
    @Test
    @DisplayName("a supplementary code point (emoji) collapses to one substitute character")
    void supplementaryCodePointCollapsesToOneCharacter() {
        String emoji = "\uD83D\uDE00";
        assertThat(emoji).hasSize(2);

        String sanitized = FixedWidthText.toSingleByteText(emoji);

        assertThat(sanitized).isEqualTo("?");
        assertThat(sanitized.getBytes(StandardCharsets.ISO_8859_1)).hasSize(1);
    }

    /**
     * :purpose: The invariant holds for mixed content: after padding to a declared width
     *     the encoded form carries exactly that many bytes.
     */
    @Test
    @DisplayName("a padded mixed-content value encodes to exactly the declared byte width")
    void paddedMixedContentMatchesTheDeclaredByteWidth() {
        String sanitized = FixedWidthText.toSingleByteText("José \uD83D\uDE00 \u4E2D Müller");
        String padded = sanitized + " ".repeat(50 - sanitized.length());

        assertThat(padded.getBytes(StandardCharsets.ISO_8859_1)).hasSize(50);
    }
}
