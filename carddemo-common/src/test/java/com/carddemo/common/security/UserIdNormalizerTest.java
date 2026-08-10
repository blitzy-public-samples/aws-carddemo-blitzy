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
package com.carddemo.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Pin the single canonical user-id form shared by every module, reproducing the legacy
 *   3270 ``UCTRAN`` fold and ``COSGN00C``'s ``FUNCTION UPPER-CASE`` of the entered id
 *   [app/cbl/COSGN00C.cbl:L132].
 * :note: This rule was previously re-implemented in six places across four modules and the copies
 *   had drifted -- the sign-on path folded case, the administrative write path did not, and two
 *   read paths folded without trimming -- which is how an administrator could create a user that
 *   no sign-on could match. These tests exist so the rule stays in one place with one meaning.
 */
class UserIdNormalizerTest {

    /**
     * :purpose: Every casing and surrounding-whitespace variation of the same id must collapse to
     *   one stored form, so a user written under any of them is found under all of them.
     * :param raw: the id as supplied by a caller.
     * :param expected: the canonical stored form.
     */
    @ParameterizedTest
    @CsvSource({
            "admin001,ADMIN001",
            "ADMIN001,ADMIN001",
            "AdMiN001,ADMIN001",
            "'  admin001  ',ADMIN001",
            "'admin001 ',ADMIN001",
            "user0001,USER0001",
            "qat0001,QAT0001"
    })
    @DisplayName("casing and surrounding whitespace collapse to one canonical form")
    void casingAndWhitespaceCollapse(String raw, String expected) {
        assertThat(UserIdNormalizer.normalize(raw)).isEqualTo(expected);
    }

    /**
     * :purpose: ``null`` must stay ``null`` rather than becoming an empty string, so an ABSENT id
     *   remains distinguishable from a BLANK one and the legacy presence edits
     *   (``'User ID can NOT be empty...'``) still fire on their own terms.
     */
    @Test
    @DisplayName("null is preserved as null, not collapsed to an empty string")
    void nullIsPreserved() {
        assertThat(UserIdNormalizer.normalize(null)).isNull();
    }

    /**
     * :purpose: A blank id normalizes to an empty string rather than throwing, so validation --
     *   not normalization -- decides what a blank id means.
     */
    @Test
    @DisplayName("a whitespace-only id normalizes to empty rather than throwing")
    void blankNormalizesToEmpty() {
        assertThat(UserIdNormalizer.normalize("   ")).isEmpty();
    }

    /**
     * :purpose: The key variant must never hand a ``null`` to a caller building a lookup or index
     *   key, because a null would either compose a corrupt key or throw at the call site.
     */
    @Test
    @DisplayName("the key variant maps null to an empty string")
    void keyVariantMapsNullToEmpty() {
        assertThat(UserIdNormalizer.normalizeToKey(null)).isEmpty();
        assertThat(UserIdNormalizer.normalizeToKey("  admin001 ")).isEqualTo("ADMIN001");
    }

    /**
     * :purpose: Folding MUST be locale independent. ``toUpperCase()`` without a locale uses the JVM
     *   default, and in a Turkish locale ``"admin001"`` folds to ``"ADMİN001"`` with a dotted
     *   capital I -- an id no keyed read would ever match. That would make authentication depend on
     *   the host's locale, so the guarantee is asserted with the default locale actually switched.
     */
    @Test
    @DisplayName("folding is locale independent (Turkish dotted-I guard)")
    void foldingIsLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(UserIdNormalizer.normalize("admin001")).isEqualTo("ADMIN001");
            assertThat(UserIdNormalizer.normalize("admin001")).doesNotContain("\u0130");
        } finally {
            Locale.setDefault(original);
        }
    }

    /**
     * :purpose: Normalizing an already-canonical id must be a no-op, so repeated application
     *   through layered call paths cannot change the value.
     */
    @Test
    @DisplayName("normalization is idempotent")
    void normalizationIsIdempotent() {
        String once = UserIdNormalizer.normalize("  admin001 ");
        assertThat(UserIdNormalizer.normalize(once)).isEqualTo(once);
    }

    /**
     * :purpose: A Unicode compatibility form must fold to the character it stands for BEFORE the
     *   id is stored or looked up. Two ids a reviewer reads as the same string would otherwise be
     *   two rows and two principals: fullwidth ``ＡDMIN001`` stored beside ``ADMIN001``.
     */
    @Test
    @DisplayName("NFKC folds a fullwidth id to its canonical form")
    void nfkcFoldsFullwidthCharacters() {
        // U+FF21 FULLWIDTH LATIN CAPITAL LETTER A followed by "DMIN001".
        String fullwidth = "\uFF21DMIN001";

        assertThat(UserIdNormalizer.normalize(fullwidth)).isEqualTo("ADMIN001");
    }

    /**
     * :purpose: NFKC deliberately does NOT equate characters from different scripts, so
     *   canonicalization alone cannot be the homoglyph defence. This test pins that boundary:
     *   the Cyrillic id stays distinct here, and is refused instead by the add-user request DTO
     *   pattern and by the ``chk_sec_usr_id_charset`` database constraint. If a future change
     *   moved the charset rule into this class, this expectation is what would flag it.
     */
    @Test
    @DisplayName("NFKC does not fold a Cyrillic homoglyph into its Latin lookalike")
    void nfkcDoesNotFoldCrossScriptHomoglyphs() {
        // U+0410 CYRILLIC CAPITAL LETTER A followed by "DMIN001".
        String cyrillic = "\u0410DMIN001";

        assertThat(UserIdNormalizer.normalize(cyrillic)).isNotEqualTo("ADMIN001");
        assertThat(UserIdNormalizer.normalize(cyrillic)).isEqualTo(cyrillic);
    }
}
