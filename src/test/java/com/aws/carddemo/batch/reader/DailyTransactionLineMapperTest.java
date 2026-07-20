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
package com.aws.carddemo.batch.reader;

import com.aws.carddemo.domain.DailyTransaction;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test (no Spring context, no database) for {@link DailyTransactionLineMapper}. It asserts
 * that the mapper decodes the real 350-byte fixed-width {@code DALYTRAN-RECORD} fixture
 * ({@code src/test/resources/seed/dailytran-fixedwidth-sample.txt}) into {@link DailyTransaction}
 * staging entities exactly per the CVTRA06Y offset table, including the parity-critical zoned-decimal
 * overpunch of {@code DALYTRAN-AMT} (positive {@code G} = +7, negative right-brace = -0).
 *
 * <p>The fixture is read with {@code ISO-8859-1}, matching the encoding contract of
 * {@link DailyTransactionFileItemReader}, so the overpunch bytes survive intact.</p>
 */
class DailyTransactionLineMapperTest {

    /** Classpath location of the raw fixed-width daily-transaction fixture (10 records, 350 bytes each). */
    private static final String FIXTURE = "/seed/dailytran-fixedwidth-sample.txt";

    /** The mapper under test; stateless, so a single shared instance suffices. */
    private static final DailyTransactionLineMapper MAPPER = new DailyTransactionLineMapper();

    /** The decoded fixture records, in file order. */
    private static List<DailyTransaction> records;

    /**
     * Reads and decodes every fixture line once before the test methods run.
     */
    @BeforeAll
    static void decodeFixture() {
        records = new ArrayList<>();
        try (InputStream in = DailyTransactionLineMapperTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(in).as("fixture %s must be on the test classpath", FIXTURE).isNotNull();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1))) {
                String line;
                int n = 0;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    records.add(MAPPER.mapLine(line, ++n));
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read fixture " + FIXTURE, ex);
        }
    }

    @Test
    void decodesExactlyTenRecords() {
        assertThat(records).hasSize(10);
    }

    @Test
    void decodesEveryFieldOfFirstRecord() {
        DailyTransaction first = records.get(0);
        assertThat(first.getDalytranId()).isEqualTo("0000000000683580");
        assertThat(first.getTypeCd()).isEqualTo("01");
        assertThat(first.getCatCd()).isEqualTo(1);
        assertThat(first.getTranSource()).isEqualTo("POS TERM");
        assertThat(first.getTranDesc()).isEqualTo("Purchase at Abshire-Lowe");
        assertThat(first.getMerchantId()).isEqualTo(800000000L);
        assertThat(first.getMerchantName()).isEqualTo("Abshire-Lowe");
        assertThat(first.getMerchantCity()).isEqualTo("North Enoshaven");
        assertThat(first.getMerchantZip()).isEqualTo("72112");
        assertThat(first.getCardNum()).isEqualTo("4859452612877065");
        assertThat(first.getOrigTs()).isEqualTo("2022-06-10 19:27:53.000000");
        assertThat(first.getProcTs()).isEmpty();
        // Surrogate id is assigned by the database on insert; a decoded record is transient.
        assertThat(first.getId()).isNull();
    }

    @Test
    void decodesPositiveOverpunchAmountOfFirstRecord() {
        // Amount region "0000005047G": trailing G = positive last digit 7 -> +504.77.
        assertThat(records.get(0).getTranAmt()).isEqualByComparingTo(new BigDecimal("504.77"));
        assertThat(records.get(0).getTranAmt().scale()).isEqualTo(2);
    }

    @Test
    void decodesNegativeOverpunchAmountOfSecondRecord() {
        // Amount region "0000009190}": trailing '}' = negative last digit 0 -> -919.00.
        DailyTransaction second = records.get(1);
        assertThat(second.getDalytranId()).isEqualTo("0000000001774260");
        assertThat(second.getTypeCd()).isEqualTo("03");
        assertThat(second.getCardNum()).isEqualTo("0927987108636232");
        assertThat(second.getTranAmt()).isEqualByComparingTo(new BigDecimal("-919.00"));
        assertThat(second.getTranAmt().scale()).isEqualTo(2);
    }

    @Test
    void everyDecodedAmountIsScaleTwoBigDecimal() {
        for (DailyTransaction record : records) {
            assertThat(record.getTranAmt()).isNotNull();
            assertThat(record.getTranAmt().scale()).isEqualTo(2);
        }
    }

    @Test
    void rejectsRecordShorterThanMappedSpan() {
        String tooShort = "x".repeat(DailyTransactionLineMapper.MAPPED_SPAN_LENGTH - 1);
        assertThatThrownBy(() -> MAPPER.mapLine(tooShort, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void rejectsNullLine() {
        assertThatThrownBy(() -> MAPPER.mapLine(null, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 7");
    }
}
