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
package com.carddemo.transaction.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.common.domain.DailyTransaction;
import com.carddemo.common.exception.CardDemoException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * :purpose: Unit tests for :class:`DailyTransactionRowMapper`, which maps a persisted
 *     ``daily_transactions`` row to the ``CVTRA06Y DALYTRAN-RECORD`` value object the
 *     posting job validates.
 * :note: The mapper is exercised against a mocked ``ResultSet`` so the column-name
 *     contract, the monetary scale and the null handling are asserted without a database.
 */
@DisplayName("DailyTransactionRowMapper")
class DailyTransactionRowMapperTest {

    private final DailyTransactionRowMapper mapper = new DailyTransactionRowMapper();

    /**
     * :purpose: Every ``DALYTRAN`` field is read from its own column, so a reordering or
     *     renaming of the query cannot silently shift values between fields.
     * :raises SQLException: never; declared because the mapper contract declares it.
     */
    @Test
    @DisplayName("every DALYTRAN field is mapped from its own column")
    void everyFieldIsMappedFromItsOwnColumn() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("dalytran_id")).thenReturn("0000000000683580");
        when(rs.getString("dalytran_type_cd")).thenReturn("01");
        when(rs.getObject(eq("dalytran_cat_cd"), eq(Integer.class))).thenReturn(5);
        when(rs.getString("dalytran_source")).thenReturn("POS TERM");
        when(rs.getString("dalytran_desc")).thenReturn("Purchase");
        when(rs.getBigDecimal("dalytran_amt")).thenReturn(new BigDecimal("123.45"));
        when(rs.getObject(eq("dalytran_merchant_id"), eq(Long.class))).thenReturn(987654321L);
        when(rs.getString("dalytran_merchant_name")).thenReturn("Corner Store");
        when(rs.getString("dalytran_merchant_city")).thenReturn("Seattle");
        when(rs.getString("dalytran_merchant_zip")).thenReturn("98101");
        when(rs.getString("dalytran_card_num")).thenReturn("4111111111111111");
        when(rs.getString("dalytran_orig_ts")).thenReturn("2026-08-01-10.00.00.000000");
        when(rs.getString("dalytran_proc_ts")).thenReturn("2026-08-02-10.00.00.000000");

        DailyTransaction record = mapper.mapRow(rs, 0);

        assertThat(record.getDalytranId()).isEqualTo("0000000000683580");
        assertThat(record.getDalytranTypeCd()).isEqualTo("01");
        assertThat(record.getDalytranCatCd()).isEqualTo(5);
        assertThat(record.getDalytranSource()).isEqualTo("POS TERM");
        assertThat(record.getDalytranDesc()).isEqualTo("Purchase");
        assertThat(record.getDalytranAmt()).isEqualByComparingTo("123.45");
        assertThat(record.getDalytranMerchantId()).isEqualTo(987654321L);
        assertThat(record.getDalytranMerchantName()).isEqualTo("Corner Store");
        assertThat(record.getDalytranMerchantCity()).isEqualTo("Seattle");
        assertThat(record.getDalytranMerchantZip()).isEqualTo("98101");
        assertThat(record.getDalytranCardNum()).isEqualTo("4111111111111111");
        assertThat(record.getDalytranOrigTs()).isEqualTo("2026-08-01-10.00.00.000000");
        assertThat(record.getDalytranProcTs()).isEqualTo("2026-08-02-10.00.00.000000");
    }

    /**
     * :purpose: The monetary amount keeps the exact ``NUMERIC(11,2)`` scale of the
     *     ``TRAN-AMT`` COMP-3 field, so no rounding is introduced while reading the feed.
     * :raises SQLException: never; declared because the mapper contract declares it.
     */
    @Test
    @DisplayName("the amount preserves its NUMERIC(11,2) scale exactly")
    void amountPreservesScale() throws SQLException {
        ResultSet rs = usableRow();
        when(rs.getBigDecimal("dalytran_amt")).thenReturn(new BigDecimal("0.05"));

        DailyTransaction record = mapper.mapRow(rs, 0);

        assertThat(record.getDalytranAmt()).isEqualTo(new BigDecimal("0.05"));
        assertThat(record.getDalytranAmt().scale()).isEqualTo(2);
    }

    /**
     * :purpose: A SQL ``NULL`` in an optional numeric column stays ``null`` rather than
     *     becoming zero, because zero is a meaningful value the validation rules would
     *     act on differently. ``DALYTRAN-MERCHANT-ID`` is the optional case; the fields
     *     the posting checks dereference are mandatory and are covered below.
     * :raises SQLException: never; declared because the mapper contract declares it.
     */
    @Test
    @DisplayName("a null merchant id stays null, never zero")
    void nullNumericsStayNull() throws SQLException {
        ResultSet rs = usableRow();
        when(rs.getObject(eq("dalytran_merchant_id"), eq(Long.class))).thenReturn(null);

        DailyTransaction record = mapper.mapRow(rs, 0);

        assertThat(record.getDalytranMerchantId()).isNull();
    }

    /**
     * :purpose: A staged row missing a field the posting checks dereference is reported
     *     as an actionable feed fault naming the record and the column (QA Issue 8),
     *     instead of aborting the step later with a raw ``NullPointerException``. A
     *     fixed-width 350-byte ``DALYTRAN`` record can never be missing one, so such a
     *     row is a staging fault, not a business rejection.
     * :raises SQLException: never; declared because the mapper contract declares it.
     */
    @Test
    @DisplayName("a row with no DALYTRAN-AMT is refused, naming the record and the column")
    void missingAmountIsRefusedWithAnActionableMessage() throws SQLException {
        ResultSet rs = usableRow();
        when(rs.getBigDecimal("dalytran_amt")).thenReturn(null);

        assertThatThrownBy(() -> mapper.mapRow(rs, 0))
                .isInstanceOf(CardDemoException.class)
                .hasMessageContaining("0000000000683580")
                .hasMessageContaining("DALYTRAN-AMT (dalytran_amt)")
                .hasMessageContaining("is absent");
    }

    /**
     * :purpose: A staged row whose ``DALYTRAN-ORIG-TS`` is shorter than the ten date
     *     characters the expiration check compares is refused with the same actionable
     *     message rather than a ``StringIndexOutOfBoundsException``.
     * :raises SQLException: never; declared because the mapper contract declares it.
     */
    @Test
    @DisplayName("a truncated DALYTRAN-ORIG-TS is refused, naming the character shortfall")
    void truncatedOriginationTimestampIsRefused() throws SQLException {
        ResultSet rs = usableRow();
        when(rs.getString("dalytran_orig_ts")).thenReturn("2026-");

        assertThatThrownBy(() -> mapper.mapRow(rs, 0))
                .isInstanceOf(CardDemoException.class)
                .hasMessageContaining("DALYTRAN-ORIG-TS (dalytran_orig_ts)")
                .hasMessageContaining("carries 5 characters")
                .hasMessageContaining("ACCT-EXPIRAION-DATE");
    }

    /**
     * :purpose: Stub a complete, usable ``daily_transactions`` row so a test can vary a
     *     single column without the row becoming structurally invalid for an unrelated
     *     reason.
     * :returns: a mocked ``ResultSet`` positioned on a usable ``CVTRA06Y`` row.
     * :raises SQLException: never; declared because the stubbed getters declare it.
     */
    private static ResultSet usableRow() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("dalytran_id")).thenReturn("0000000000683580");
        when(rs.getString("dalytran_type_cd")).thenReturn("01");
        when(rs.getObject(eq("dalytran_cat_cd"), eq(Integer.class))).thenReturn(5);
        when(rs.getBigDecimal("dalytran_amt")).thenReturn(new BigDecimal("123.45"));
        when(rs.getString("dalytran_card_num")).thenReturn("4111111111111111");
        when(rs.getString("dalytran_orig_ts")).thenReturn("2026-08-01-10.00.00.000000");
        return rs;
    }
}
