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

import com.carddemo.common.domain.DailyTransaction;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

/**
 * :purpose: Map one ``daily_transactions`` row to a {@link DailyTransaction}, the
 *     ``CVTRA06Y DALYTRAN-RECORD`` value object the posting job validates. The feed is read
 *     through JDBC rather than JPA because ``DailyTransaction`` is deliberately a non-entity
 *     value object shared by every service, so it carries no ``@Entity``/``@Table``/``@Id``
 *     mapping.
 * :output: A fully populated {@link DailyTransaction} for the current result-set row.
 * :note: ``dalytran_amt`` is read as a ``BigDecimal`` so the ``NUMERIC(11,2)`` scale of
 *     the ``TRAN-AMT`` COMP-3 field survives unchanged, and ``dalytran_merchant_id`` is read
 *     through ``getObject`` so a SQL ``NULL`` stays ``null`` rather than becoming zero, which
 *     would alter the value the validation rules see.
 * :note: Every mapped row is checked by {@link DailyTransactionFeedValidator} before it
 *     leaves the mapper, so a staged row that is not a usable fixed-width ``DALYTRAN`` record
 *     fails with an actionable message naming the record and the offending column instead of
 *     aborting the step later with a raw ``NullPointerException`` or
 *     ``StringIndexOutOfBoundsException``.
 */
public class DailyTransactionRowMapper implements RowMapper<DailyTransaction> {

    /**
     * :purpose: Build the feed record for the current row.
     * :param rs: the result set positioned on the row to map.
     * :param rowNum: the zero-based index of the current row.
     * :returns: the mapped {@link DailyTransaction}.
     * :raises SQLException: if any column cannot be read.
     * :raises com.carddemo.common.exception.CardDemoException: if the row is not a
     *     usable ``CVTRA06Y`` record.
     */
    @Override
    public DailyTransaction mapRow(ResultSet rs, int rowNum) throws SQLException {
        DailyTransaction record = new DailyTransaction();
        record.setDalytranId(rs.getString("dalytran_id"));
        record.setDalytranTypeCd(rs.getString("dalytran_type_cd"));
        record.setDalytranCatCd(rs.getObject("dalytran_cat_cd", Integer.class));
        record.setDalytranSource(rs.getString("dalytran_source"));
        record.setDalytranDesc(rs.getString("dalytran_desc"));
        record.setDalytranAmt(rs.getBigDecimal("dalytran_amt"));
        record.setDalytranMerchantId(rs.getObject("dalytran_merchant_id", Long.class));
        record.setDalytranMerchantName(rs.getString("dalytran_merchant_name"));
        record.setDalytranMerchantCity(rs.getString("dalytran_merchant_city"));
        record.setDalytranMerchantZip(rs.getString("dalytran_merchant_zip"));
        record.setDalytranCardNum(rs.getString("dalytran_card_num"));
        record.setDalytranOrigTs(rs.getString("dalytran_orig_ts"));
        record.setDalytranProcTs(rs.getString("dalytran_proc_ts"));
        DailyTransactionFeedValidator.requireUsableRecord(record);
        return record;
    }
}
