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
package com.carddemo.transaction.mapper;

import com.carddemo.common.domain.Transaction;
import com.carddemo.common.dto.TransactionAddRequestDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Verify the write and read halves of the canonical 26-character timestamp
 *     contract at the mapper: a ``COTRN02`` date map field is stored space-filled to
 *     twenty-six characters, and the list row reads only the date positions, which every
 *     legacy producer writes identically.
 */
@DisplayName("TransactionMapper: the stored timestamp contract")
class TransactionMapperTimestampTest {

    /** :purpose: The stored form of a date-only CT02 row: the date plus sixteen blanks. */
    private static final String STORED_DATE_ONLY = "2026-08-01" + "                ";

    private final TransactionMapper mapper = new TransactionMapper();

    @Test
    @DisplayName("toEntity space-fills the ten-character date to the stored width")
    void toEntityRendersTheStoredForm() {
        Transaction entity = mapper.toEntity(request("2026-08-01", "2026-08-01"));

        assertThat(entity.getTranOrigTs()).isEqualTo(STORED_DATE_ONLY).hasSize(26);
        assertThat(entity.getTranProcTs()).isEqualTo(STORED_DATE_ONLY).hasSize(26);
    }

    @Test
    @DisplayName("a date already space-filled by the redisplay stores byte-identically")
    void toEntityIsIdempotentForTheRedisplayedValue() {
        Transaction entity = mapper.toEntity(request(STORED_DATE_ONLY, STORED_DATE_ONLY));

        assertThat(entity.getTranOrigTs()).isEqualTo(STORED_DATE_ONLY);
        assertThat(entity.getTranProcTs()).isEqualTo(STORED_DATE_ONLY);
    }

    @Test
    @DisplayName("absent dates stay absent rather than becoming twenty-six blanks")
    void toEntityLeavesNullAlone() {
        Transaction entity = mapper.toEntity(request(null, null));

        assertThat(entity.getTranOrigTs()).isNull();
        assertThat(entity.getTranProcTs()).isNull();
    }

    @ParameterizedTest
    @DisplayName("the list row reads the same date from every legacy producer's shape")
    @CsvSource(delimiter = '|', value = {
        "2026-08-01                |08/01/26",
        "2026-08-01 10:00:00.000000|08/01/26",
        "2026-08-01-10.00.00.120000|08/01/26",
    })
    void listRowReadsOnlyTheDatePositions(String stored, String expected) {
        Transaction entity = new Transaction();
        entity.setTranId("0000000000000900");
        entity.setTranOrigTs(stored);

        assertThat(mapper.toListRow(entity).getTranDate()).isEqualTo(expected);
    }

    /**
     * :purpose: Build an add request carrying the supplied date field values.
     * :param origTs: the origination date field value; may be ``null``.
     * :param procTs: the processing date field value; may be ``null``.
     * :returns: the populated request.
     */
    private static TransactionAddRequestDto request(String origTs, String procTs) {
        TransactionAddRequestDto request = new TransactionAddRequestDto();
        request.setTranTypeCd("01");
        request.setTranCatCd(1);
        request.setTranSource("POS TERM");
        request.setTranDesc("MAPPER CONTRACT");
        request.setTranAmt(new BigDecimal("1.00"));
        request.setTranCardNum("9000000000000001");
        request.setTranMerchantId(1L);
        request.setTranOrigTs(origTs);
        request.setTranProcTs(procTs);
        return request;
    }
}
