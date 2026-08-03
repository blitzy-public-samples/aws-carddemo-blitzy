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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * :purpose: Unit tests for {@link SensitiveDataMasker}, which keeps a primary account
 *     number out of the log stream (CWE-532) while leaving shorter business
 *     identifiers, such as the 11-digit account id, readable for support.
 */
@DisplayName("SensitiveDataMasker")
class SensitiveDataMaskerTest {

    /**
     * :purpose: A 16-digit PAN in a request path is reduced to its last four digits.
     */
    @Test
    @DisplayName("PAN in a request path is masked to the last four digits")
    void maskPan_masksPanInRequestPath() {
        assertThat(SensitiveDataMasker.maskPan("/cards/0500024453765740"))
                .isEqualTo("/cards/************5740");
    }

    /**
     * :purpose: Every PAN in a message is masked, not just the first.
     */
    @Test
    @DisplayName("Every PAN-shaped run in the text is masked")
    void maskPan_masksEveryOccurrence() {
        assertThat(SensitiveDataMasker.maskPan("from 0500024453765740 to 9680294154603697"))
                .isEqualTo("from ************5740 to ************3697");
    }

    /**
     * :purpose: Shorter identifiers are preserved: an 11-digit account id and a 3-digit
     *     category code must stay readable for support and correlation.
     */
    @Test
    @DisplayName("Shorter identifiers are left intact")
    void maskPan_leavesShortIdentifiersIntact() {
        assertThat(SensitiveDataMasker.maskPan("/accounts/00000000001")).isEqualTo("/accounts/00000000001");
        assertThat(SensitiveDataMasker.maskPan("category 001")).isEqualTo("category 001");
    }

    /**
     * :purpose: A 19-digit PAN (the ISO/IEC 7812 maximum) is masked too.
     */
    @Test
    @DisplayName("A 19-digit PAN is masked")
    void maskPan_masksNineteenDigitPan() {
        assertThat(SensitiveDataMasker.maskPan("1234567890123456789"))
                .isEqualTo("***************6789");
    }

    /**
     * :purpose: ``null`` and empty input pass through unchanged so callers need no guard.
     */
    @Test
    @DisplayName("Null and empty input pass through unchanged")
    void maskPan_passesThroughNullAndEmpty() {
        assertThat(SensitiveDataMasker.maskPan(null)).isNull();
        assertThat(SensitiveDataMasker.maskPan("")).isEmpty();
    }

    /**
     * :purpose: Text without a PAN is returned unchanged (no allocation surprises).
     */
    @Test
    @DisplayName("Text without a PAN is returned unchanged")
    void maskPan_returnsTextWithoutPanUnchanged() {
        String message = "Card name can only contain alphabets and spaces";
        assertThat(SensitiveDataMasker.maskPan(message)).isSameAs(message);
    }
}
