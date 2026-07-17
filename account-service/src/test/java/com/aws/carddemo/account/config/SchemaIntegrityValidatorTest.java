/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.account.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * Isolated unit tests for {@link SchemaIntegrityValidator} (finding F-04).
 *
 * <p>The validator's single collaborator, {@link JdbcTemplate}, is a Mockito mock whose
 * {@code query(sql, RowCallbackHandler, args)} call is answered by replaying a controlled set of
 * {@code information_schema.columns} rows into the callback. This exercises the precision/scale/
 * nullability decision logic without any database, so both the happy path and each drift scenario
 * are verified deterministically and fast.</p>
 */
class SchemaIntegrityValidatorTest {

    /** Builds a mock metadata row exposing the column accessors the validator reads. */
    private static ResultSet row(String name, String dataType, int precision, int scale,
                                 String isNullable) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("column_name")).thenReturn(name);
        when(rs.getString("data_type")).thenReturn(dataType);
        when(rs.getInt("numeric_precision")).thenReturn(precision);
        when(rs.getInt("numeric_scale")).thenReturn(scale);
        when(rs.getString("is_nullable")).thenReturn(isNullable);
        return rs;
    }

    /** The correct schema: 5 money columns NUMERIC(12,2) NOT NULL and version BIGINT NOT NULL. */
    private static List<ResultSet> correctSchemaRows() throws SQLException {
        List<ResultSet> rows = new ArrayList<>();
        for (String money : new String[] {"current_balance", "credit_limit", "cash_credit_limit",
                "current_cycle_credit", "current_cycle_debit"}) {
            rows.add(row(money, "numeric", 12, 2, "NO"));
        }
        rows.add(row("version", "bigint", 0, 0, "NO"));
        // A couple of unrelated columns to prove they are ignored.
        rows.add(row("account_id", "character varying", 0, 0, "NO"));
        rows.add(row("open_date", "date", 0, 0, "NO"));
        return rows;
    }

    /** Wires the mock JdbcTemplate to replay {@code rows} into the validator's RowCallbackHandler. */
    private static JdbcTemplate templateReturning(List<ResultSet> rows) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            for (ResultSet rs : rows) {
                handler.processRow(rs);
            }
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), eq("accounts"));
        return jdbcTemplate;
    }

    @Test
    void afterPropertiesSet_correctSchema_passes() throws Exception {
        SchemaIntegrityValidator validator =
                new SchemaIntegrityValidator(templateReturning(correctSchemaRows()));
        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void afterPropertiesSet_precisionDrift_failsFast() throws Exception {
        List<ResultSet> rows = correctSchemaRows();
        // Narrow current_balance to NUMERIC(11,2) — the exact drift the finding describes.
        rows.set(0, row("current_balance", "numeric", 11, 2, "NO"));
        SchemaIntegrityValidator validator = new SchemaIntegrityValidator(templateReturning(rows));

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current_balance")
                .hasMessageContaining("NUMERIC(12,2) NOT NULL")
                .hasMessageContaining("NUMERIC(11,2) NOT NULL");
    }

    @Test
    void afterPropertiesSet_scaleDrift_failsFast() throws Exception {
        List<ResultSet> rows = correctSchemaRows();
        rows.set(1, row("credit_limit", "numeric", 12, 0, "NO"));
        SchemaIntegrityValidator validator = new SchemaIntegrityValidator(templateReturning(rows));

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credit_limit")
                .hasMessageContaining("NUMERIC(12,0)");
    }

    @Test
    void afterPropertiesSet_moneyColumnNullable_failsFast() throws Exception {
        List<ResultSet> rows = correctSchemaRows();
        rows.set(2, row("cash_credit_limit", "numeric", 12, 2, "YES"));
        SchemaIntegrityValidator validator = new SchemaIntegrityValidator(templateReturning(rows));

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cash_credit_limit")
                .hasMessageContaining("NUMERIC(12,2) NULL");
    }

    @Test
    void afterPropertiesSet_versionNotBigint_failsFast() throws Exception {
        List<ResultSet> rows = correctSchemaRows();
        rows.set(5, row("version", "integer", 0, 0, "NO"));
        SchemaIntegrityValidator validator = new SchemaIntegrityValidator(templateReturning(rows));

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version")
                .hasMessageContaining("BIGINT NOT NULL");
    }

    @Test
    void afterPropertiesSet_missingMoneyColumn_failsFast() throws Exception {
        List<ResultSet> rows = correctSchemaRows();
        rows.remove(0); // drop current_balance entirely
        SchemaIntegrityValidator validator = new SchemaIntegrityValidator(templateReturning(rows));

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current_balance")
                .hasMessageContaining("missing");
    }
}
