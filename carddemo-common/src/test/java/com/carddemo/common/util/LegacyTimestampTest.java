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
package com.carddemo.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * :purpose: Verify the canonical 26-character timestamp contract: the ``COTRN02`` date map
 *     field is accepted only as the WHOLE field, it is stored space-filled to twenty-six
 *     characters, and readers see the same date portion whichever legacy producer wrote the
 *     row.
 */
@DisplayName("LegacyTimestamp: the stored PIC X(26) timestamp contract")
class LegacyTimestampTest {

    /** :purpose: The stored form of a date-only CT02 row: the date plus sixteen blanks. */
    private static final String STORED_DATE_ONLY = "2026-08-01" + "                ";

    @Nested
    @DisplayName("The COTRN02 date map field (LENGTH=10)")
    class MapDateField {

        @Test
        @DisplayName("the bare ten-character date is the field")
        void bareDateIsAccepted() {
            assertThat(LegacyTimestamp.isMapDateField("2026-08-01")).isTrue();
        }

        @Test
        @DisplayName("the date space-filled to the stored width is the same field redisplayed")
        void spaceFilledDateIsAccepted() {
            // COTRN02C L487-L488 moves the 26-character stored value back into the
            // 10-character map field, so a resubmitted screen carries the date space-filled.
            assertThat(LegacyTimestamp.isMapDateField(STORED_DATE_ONLY)).isTrue();
        }

        @ParameterizedTest
        @DisplayName("anything other than the whole ten-character field is refused")
        @ValueSource(strings = {
            "2026-08-01GARBAGE-SUFFIX!!",
            "2026-08-01-10.00.00.000000",
            "2026-08-01 10:00:00.000000",
            "2026-08-0",
            "2026-08-011",
            "2026/08/01",
            "20260801  ",
            "  2026-08-01",
            "xxxx-xx-xx",
            "",
        })
        void everythingElseIsRefused(String value) {
            assertThat(LegacyTimestamp.isMapDateField(value)).isFalse();
        }

        @Test
        @DisplayName("a null value is refused rather than throwing")
        void nullIsRefused() {
            assertThat(LegacyTimestamp.isMapDateField(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("Rendering the stored form")
    class StoredForm {

        @Test
        @DisplayName("a bare date is left-justified and space-filled to twenty-six")
        void bareDateIsPadded() {
            assertThat(LegacyTimestamp.fromMapDateField("2026-08-01"))
                    .isEqualTo(STORED_DATE_ONLY)
                    .hasSize(LegacyTimestamp.STORED_WIDTH);
        }

        @Test
        @DisplayName("an already space-filled date is byte-identical to the bare one")
        void spaceFilledDateIsIdempotent() {
            assertThat(LegacyTimestamp.fromMapDateField(STORED_DATE_ONLY))
                    .isEqualTo(LegacyTimestamp.fromMapDateField("2026-08-01"));
        }

        @Test
        @DisplayName("a value that is not the map field is a programming error, never stored")
        void nonFieldValueThrows() {
            assertThatThrownBy(() -> LegacyTimestamp.fromMapDateField("2026-08-01GARBAGE!!"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("The date portion every producer shares")
    class DatePortion {

        @ParameterizedTest
        @DisplayName("all three legacy producers yield the same date positions")
        @ValueSource(strings = {
            "2026-08-01                ",
            "2026-08-01 10:00:00.000000",
            "2026-08-01-10.00.00.120000",
        })
        void producersAgreeOnPositionsOneToTen(String stored) {
            assertThat(stored).hasSize(LegacyTimestamp.STORED_WIDTH);
            assertThat(LegacyTimestamp.storedDatePortion(stored)).isEqualTo("2026-08-01");
        }

        @Test
        @DisplayName("a null or too-short value yields null rather than throwing")
        void shortValueYieldsNull() {
            assertThat(LegacyTimestamp.storedDatePortion(null)).isNull();
            assertThat(LegacyTimestamp.storedDatePortion("2026-08-0")).isNull();
        }
    }

    @Test
    @DisplayName("Trailing blanks are removed, embedded ones are not")
    void stripTrailingBlanksOnlyTouchesTheTail() {
        assertThat(LegacyTimestamp.stripTrailingBlanks("2026-08-01   ")).isEqualTo("2026-08-01");
        assertThat(LegacyTimestamp.stripTrailingBlanks("2026-08-01 10:00:00.000000"))
                .isEqualTo("2026-08-01 10:00:00.000000");
        assertThat(LegacyTimestamp.stripTrailingBlanks("   ")).isEmpty();
    }
}
