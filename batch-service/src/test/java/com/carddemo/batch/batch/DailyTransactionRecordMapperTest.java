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
package com.carddemo.batch.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.testsupport.AsciiFixtures;
import com.carddemo.common.domain.DailyTransaction;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for :class:`DailyTransactionRecordMapper`.
 *
 * :purpose: Verify the ``CVTRA06Y`` 350-byte ``DALYTRAN-RECORD`` parse that feeds
 *     the ``CBTRN01C`` validation job: every field is taken from its declared
 *     column range, the ``S9(09)V99`` amount is decoded from its trailing sign
 *     overpunch at exact scale two, short and long lines are normalized, and a
 *     malformed numeric or overpunch fails loudly with the offending line number.
 *     The happy path is driven by the COMMITTED ``app/data/ASCII/dailytran.txt``
 *     records so the parse is proven against the real feed, not an invented line.
 * :output: JUnit 5 / AssertJ assertions only; the mapper is stateless, so no
 *     Spring context, database or file handle is involved.
 */
class DailyTransactionRecordMapperTest {

    private DailyTransactionRecordMapper mapper;

    @BeforeEach
    void createMapper() {
        mapper = new DailyTransactionRecordMapper();
    }

    @Nested
    @DisplayName("Committed dailytran.txt fixtures")
    class CommittedFixtures {

        @Test
        @DisplayName("the first fixture record maps every field to its declared column range")
        void firstFixtureRecordMapsEveryField() {
            String line = AsciiFixtures.lines("dailytran.txt").get(0);
            assertThat(line).hasSize(DailyTransactionRecordMapper.RECORD_LENGTH);

            DailyTransaction record = mapper.mapLine(line, 1);

            assertThat(record.getDalytranId()).isEqualTo("0000000000683580");
            assertThat(record.getDalytranTypeCd()).isEqualTo("01");
            assertThat(record.getDalytranCatCd()).isEqualTo(1);
            assertThat(record.getDalytranSource()).isEqualTo("POS TERM");
            assertThat(record.getDalytranDesc()).isEqualTo("Purchase at Abshire-Lowe");
            // '0000005047G' -> digits 0000005047 + overpunch G (+7) -> 504.77
            assertThat(record.getDalytranAmt()).isEqualByComparingTo("504.77");
            assertThat(record.getDalytranAmt().scale()).isEqualTo(2);
            assertThat(record.getDalytranCardNum()).isEqualTo("4859452612877065");
            assertThat(record.getDalytranOrigTs()).isEqualTo("2022-06-10 19:27:53.000000");
        }

        @Test
        @DisplayName("all 300 committed records parse with a non-null id and a scale-2 amount")
        void everyCommittedRecordParses() {
            List<String> fixtures = AsciiFixtures.lines("dailytran.txt");
            assertThat(fixtures).hasSize(300);

            int lineNumber = 0;
            for (String line : fixtures) {
                lineNumber++;
                assertThat(line).as("record %d width", lineNumber)
                        .hasSize(DailyTransactionRecordMapper.RECORD_LENGTH);

                DailyTransaction record = mapper.mapLine(line, lineNumber);

                assertThat(record.getDalytranId()).as("record %d id", lineNumber).hasSize(16);
                assertThat(record.getDalytranCardNum()).as("record %d card", lineNumber).hasSize(16);
                assertThat(record.getDalytranAmt()).as("record %d amount", lineNumber).isNotNull();
                assertThat(record.getDalytranAmt().scale()).as("record %d scale", lineNumber)
                        .isEqualTo(2);
            }
        }

        @Test
        @DisplayName("at least one committed record carries a negative amount, exercising the negative overpunch")
        void committedFeedExercisesTheNegativeOverpunch() {
            List<DailyTransaction> parsed = AsciiFixtures.lines("dailytran.txt").stream()
                    .map(line -> mapper.mapLine(line, 1))
                    .toList();

            assertThat(parsed).anySatisfy(record ->
                    assertThat(record.getDalytranAmt()).isNegative());
            assertThat(parsed).anySatisfy(record ->
                    assertThat(record.getDalytranAmt()).isPositive());
        }
    }

    @Nested
    @DisplayName("Field-level parse rules")
    class ParseRules {

        /**
         * Builds a synthetic 350-byte record with the given amount field.
         *
         * :param amountField: the 11-character ``DALYTRAN-AMT`` slice.
         * :output: a full-width record line.
         */
        private static String recordWithAmount(String amountField) {
            StringBuilder line = new StringBuilder();
            line.append("0000000000000001");                       // id
            line.append("01");                                     // type
            line.append("0002");                                   // category
            line.append(AsciiFixtures.padRight("POS TERM", 10));   // source
            line.append(AsciiFixtures.padRight("Synthetic record", 100));
            line.append(amountField);                              // amount
            line.append("000000042");                              // merchant id
            line.append(AsciiFixtures.padRight("Merchant", 50));
            line.append(AsciiFixtures.padRight("City", 50));
            line.append(AsciiFixtures.padRight("12345", 10));
            line.append("4111111111111111");                       // card
            line.append("2024-06-15-13.45.30.123456");             // orig ts
            line.append("2024-06-16-01.00.00.000000");             // proc ts
            line.append(" ".repeat(20));                           // filler
            return line.toString();
        }

        @ParameterizedTest
        @DisplayName("the trailing overpunch decodes the sign and the units digit")
        @CsvSource({
                "0000005047G,  504.77",
                "0000005047P,  -504.77",
                "0000000000{,  0.00",
                "0000000000},  0.00",
                "0000000000A,  0.01",
                "0000000000J,  -0.01",
                "0000000099I,  9.99",
                "0000000099R,  -9.99",
                "00000000501,  5.01"
        })
        void overpunchDecodesSignAndUnitsDigit(String amountField, String expected) {
            DailyTransaction record = mapper.mapLine(recordWithAmount(amountField), 7);

            assertThat(record.getDalytranAmt()).isEqualByComparingTo(new BigDecimal(expected));
            assertThat(record.getDalytranAmt().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("a blank amount field decodes to 0.00 at scale two")
        void blankAmountDecodesToZero() {
            assertThat(mapper.mapLine(recordWithAmount(" ".repeat(11)), 1).getDalytranAmt())
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a short line is space-padded to 350 and a long line is truncated")
        void shortAndLongLinesAreNormalized() {
            DailyTransaction fromShort = mapper.mapLine("0000000000000009", 1);
            assertThat(fromShort.getDalytranId()).isEqualTo("0000000000000009");
            assertThat(fromShort.getDalytranTypeCd()).isEmpty();
            assertThat(fromShort.getDalytranCatCd()).isZero();
            assertThat(fromShort.getDalytranAmt()).isEqualByComparingTo("0.00");

            DailyTransaction fromLong =
                    mapper.mapLine(recordWithAmount("0000000000A") + "TRAILING GARBAGE", 1);
            assertThat(fromLong.getDalytranProcTs()).isEqualTo("2024-06-16-01.00.00.000000");
            assertThat(fromLong.getDalytranAmt()).isEqualByComparingTo("0.01");
        }

        @Test
        @DisplayName("a null line yields an all-empty record rather than throwing")
        void nullLineYieldsAnEmptyRecord() {
            DailyTransaction record = mapper.mapLine(null, 1);

            assertThat(record.getDalytranId()).isEmpty();
            assertThat(record.getDalytranCatCd()).isZero();
            assertThat(record.getDalytranMerchantId()).isZero();
            assertThat(record.getDalytranAmt()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("trailing spaces are trimmed from every alphanumeric field")
        void trailingSpacesAreTrimmed() {
            DailyTransaction record = mapper.mapLine(recordWithAmount("0000000000{"), 1);

            assertThat(record.getDalytranSource()).isEqualTo("POS TERM");
            assertThat(record.getDalytranDesc()).isEqualTo("Synthetic record");
            assertThat(record.getDalytranMerchantName()).isEqualTo("Merchant");
            assertThat(record.getDalytranMerchantCity()).isEqualTo("City");
            assertThat(record.getDalytranMerchantZip()).isEqualTo("12345");
        }

        @Test
        @DisplayName("a non-digit inside the amount field fails loudly with the line number")
        void nonDigitInAmountFailsLoudly() {
            assertThatThrownBy(() -> mapper.mapLine(recordWithAmount("00000X5047G"), 42))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid zoned-decimal field")
                    .hasMessageContaining("line 42");
        }

        @Test
        @DisplayName("an unknown sign overpunch fails loudly with the line number")
        void unknownOverpunchFailsLoudly() {
            assertThatThrownBy(() -> mapper.mapLine(recordWithAmount("0000005047$"), 43))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid zoned-decimal sign overpunch")
                    .hasMessageContaining("line 43");
        }

        @Test
        @DisplayName("a non-numeric category code fails loudly with the line number")
        void nonNumericCategoryFailsLoudly() {
            String line = recordWithAmount("0000000000{");
            String corrupted = line.substring(0, 18) + "AB12" + line.substring(22);

            assertThatThrownBy(() -> mapper.mapLine(corrupted, 44))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid numeric field")
                    .hasMessageContaining("line 44");
        }

        @Test
        @DisplayName("decodeSignedZonedDecimal treats an embedded space as a zero digit")
        void embeddedSpaceIsTreatedAsZero() {
            assertThat(DailyTransactionRecordMapper
                    .decodeSignedZonedDecimal("     5047G", 2, 1))
                    .isEqualByComparingTo("504.77");
        }
    }
}
