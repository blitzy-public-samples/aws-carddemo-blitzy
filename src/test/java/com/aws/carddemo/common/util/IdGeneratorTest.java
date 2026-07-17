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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IdGenerator}, asserting behavioral parity with the COBOL
 * transaction-id generation in {@code legacy/cbl/COTRN02C.cbl} (paragraph
 * {@code ADD-TRANSACTION}, lines 444&ndash;451), including the {@code READPREV}
 * {@code ENDFILE}&rarr;{@code ZEROS} empty-table case (lines 688&ndash;689) that yields
 * the first id {@code "0000000000000001"}.
 */
class IdGeneratorTest {

    // ---- Empty-table / ENDFILE semantics ------------------------------------

    @Test
    void emptyTableNumericProducesFirstId() {
        Assertions.assertEquals("0000000000000001", IdGenerator.nextTransactionId(0L));
    }

    @Test
    void nullBlankAndAllZerosAreTreatedAsEmptyTable() {
        String nullId = null;
        Assertions.assertEquals("0000000000000001", IdGenerator.nextTransactionId(nullId));
        Assertions.assertEquals("0000000000000001", IdGenerator.nextTransactionId(""));
        Assertions.assertEquals("0000000000000001", IdGenerator.nextTransactionId("   "));
        Assertions.assertEquals("0000000000000001", IdGenerator.nextTransactionId("0000000000000000"));
    }

    // ---- Increment-from-max parity ------------------------------------------

    @Test
    void incrementsNumericMaximum() {
        Assertions.assertEquals("0000000000683581", IdGenerator.nextTransactionId(683580L));
    }

    @Test
    void incrementsStringMaximumPreservingZeroPadding() {
        Assertions.assertEquals("0000000000000010", IdGenerator.nextTransactionId("0000000000000009"));
        Assertions.assertEquals("0000000000683581", IdGenerator.nextTransactionId("0000000000683580"));
    }

    @Test
    void stringMaximumIsTrimmedBeforeParsing() {
        Assertions.assertEquals("0000000000000010",
                IdGenerator.nextTransactionId("  0000000000000009  "));
    }

    @Test
    void largestValidMaximumYieldsAllNines() {
        Assertions.assertEquals("9999999999999999",
                IdGenerator.nextTransactionId(9_999_999_999_999_998L));
    }

    // ---- Canonical shape: always 16 ASCII digits ----------------------------

    @Test
    void resultIsAlwaysSixteenAsciiDigits() {
        assertCanonical(IdGenerator.nextTransactionId(0L));
        assertCanonical(IdGenerator.nextTransactionId(683580L));
        assertCanonical(IdGenerator.nextTransactionId(9_999_999_999_999_998L));
        assertCanonical(IdGenerator.nextTransactionId("0000000000000009"));
        assertCanonical(IdGenerator.format(0L));
        assertCanonical(IdGenerator.format(683580L));
    }

    @Test
    void tranIdLengthConstantIsSixteen() {
        Assertions.assertEquals(16, IdGenerator.TRAN_ID_LENGTH);
    }

    // ---- format(long) -------------------------------------------------------

    @Test
    void formatZeroPadsToSixteenDigits() {
        Assertions.assertEquals("0000000000000000", IdGenerator.format(0L));
        Assertions.assertEquals("0000000000000001", IdGenerator.format(1L));
        Assertions.assertEquals("0000000000683580", IdGenerator.format(683580L));
        Assertions.assertEquals("9999999999999999", IdGenerator.format(9_999_999_999_999_999L));
    }

    // ---- Input validation ---------------------------------------------------

    @Test
    void negativeMaximumIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> IdGenerator.nextTransactionId(-1L));
    }

    @Test
    void nonNumericStringIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> IdGenerator.nextTransactionId("12X4"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> IdGenerator.nextTransactionId("-5"));
    }

    @Test
    void overlongStringIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> IdGenerator.nextTransactionId("00000000000000001")); // 17 digits
    }

    @Test
    void negativeFormatArgumentIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> IdGenerator.format(-1L));
    }

    @Test
    void tooWideFormatArgumentIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> IdGenerator.format(10_000_000_000_000_000L)); // 17 digits
    }

    // ---- Id-space exhaustion (width overflow) -------------------------------

    @Test
    void exhaustedIdSpaceIsRejected() {
        Assertions.assertThrows(IllegalStateException.class,
                () -> IdGenerator.nextTransactionId(9_999_999_999_999_999L));
        Assertions.assertThrows(IllegalStateException.class,
                () -> IdGenerator.nextTransactionId("9999999999999999"));
    }

    // ---- helpers ------------------------------------------------------------

    private static void assertCanonical(String id) {
        Assertions.assertEquals(IdGenerator.TRAN_ID_LENGTH, id.length(),
                "transaction id must be 16 characters: " + id);
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            Assertions.assertTrue(c >= '0' && c <= '9',
                    "transaction id must be all ASCII digits: " + id);
        }
    }
}
