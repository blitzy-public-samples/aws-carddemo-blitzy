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
package com.carddemo.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for :class:`PiiMasker`.
 *
 * :purpose: Verify the single masking rule applied to regulated identifiers that
 *     leave the service boundary - all characters except the trailing four are
 *     asterisked, the length is preserved so the fixed-width screen semantics of
 *     the legacy 3270 maps survive, and an echoed mask is distinguishable from a
 *     genuine edit so an update never overwrites stored PII with asterisks.
 * :output: JUnit 5 / AssertJ assertions only; pure JDK logic with no external
 *     resource.
 */
final class PiiMaskerTest {

    @Nested
    @DisplayName("mask")
    class Mask {

        @ParameterizedTest
        @DisplayName("conceals every character except the trailing four and preserves the length")
        @CsvSource({
                "020973888,*****3888",          // CUST-SSN PIC X(9)
                "GOVT-ID-01234567890,***************7890", // CUST-GOVT-ISSUED-ID PIC X(20)
                "12345,*2345",
                "1234567890,******7890"
        })
        void masksAllButTheTrailingFour(String raw, String expected) {
            assertThat(PiiMasker.maskIdentifier(raw)).isEqualTo(expected);
            assertThat(PiiMasker.maskIdentifier(raw)).hasSameSizeAs(raw);
        }

        @ParameterizedTest
        @DisplayName("a value of four characters or fewer is fully asterisked")
        @CsvSource({
                "1,*",
                "12,**",
                "123,***",
                "1234,****",
                "747,***"                        // CARD-CVV-CD PIC X(3)
        })
        void shortValuesAreFullyMasked(String raw, String expected) {
            assertThat(PiiMasker.maskIdentifier(raw)).isEqualTo(expected);
        }

        @Test
        @DisplayName("null and empty values pass through so absent stays distinguishable from blank")
        void nullAndEmptyPassThrough() {
            assertThat(PiiMasker.maskIdentifier(null)).isNull();
            assertThat(PiiMasker.maskIdentifier("")).isEmpty();
        }

        @Test
        @DisplayName("masking is idempotent, so a masked value re-masked is unchanged")
        void maskingIsIdempotent() {
            String once = PiiMasker.maskIdentifier("020973888");
            assertThat(PiiMasker.maskIdentifier(once)).isEqualTo(once);
        }
    }

    @Nested
    @DisplayName("isMaskOf")
    class IsMaskOf {

        @Test
        @DisplayName("recognizes a client echoing back the masked display value")
        void recognizesAnEchoedMask() {
            assertThat(PiiMasker.isMaskOf("*****3888", "020973888")).isTrue();
            assertThat(PiiMasker.isMaskOf("***************7890", "GOVT-ID-01234567890")).isTrue();
        }

        @Test
        @DisplayName("a genuine edit is not treated as a mask")
        void genuineEditIsNotAMask() {
            assertThat(PiiMasker.isMaskOf("111223333", "020973888")).isFalse();
            assertThat(PiiMasker.isMaskOf("*****9999", "020973888")).isFalse();
        }

        @Test
        @DisplayName("an already-masked stored value submitted unchanged is a genuine value, not a mask")
        void alreadyMaskedStoredValueIsNotAMask() {
            assertThat(PiiMasker.isMaskOf("*****3888", "*****3888")).isFalse();
        }

        @Test
        @DisplayName("null or empty operands are never a mask")
        void nullAndEmptyOperandsAreNotAMask() {
            assertThat(PiiMasker.isMaskOf(null, "020973888")).isFalse();
            assertThat(PiiMasker.isMaskOf("*****3888", null)).isFalse();
            assertThat(PiiMasker.isMaskOf("", "")).isFalse();
        }
    }

    @Test
    @DisplayName("the utility cannot be instantiated")
    void utilityCannotBeInstantiated() throws Exception {
        Constructor<PiiMasker> constructor = PiiMasker.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThatThrownBy(constructor::newInstance)
                .cause()
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("static utility");
    }
}
