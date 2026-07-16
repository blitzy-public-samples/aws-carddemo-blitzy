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

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fail-fast startup guard that verifies the <em>exact numeric precision, scale, and nullability</em>
 * of the money and version columns of the {@code accounts} table (finding F-04).
 *
 * <h2>Why Hibernate validation is not enough</h2>
 * <p>The service runs Hibernate with {@code spring.jpa.hibernate.ddl-auto=validate}, which confirms
 * that every mapped column exists and that its broad SQL type category matches the entity. That check
 * is deliberately coarse: for a {@code NUMERIC} column Hibernate verifies the JDBC type is numeric but
 * does <strong>not</strong> assert the declared {@code precision} and {@code scale}. A silent drift
 * from {@code NUMERIC(12,2)} to, say, {@code NUMERIC(11,2)} therefore passes Hibernate validation even
 * though it narrows the representable monetary range below the legacy {@code S9(10)V99} domain
 * (&plusmn;9,999,999,999.99) that the service and its exact-precision {@code BigDecimal} contract
 * depend on (AAP &sect;0.6.2). Rounding or overflow on a large balance would then be a latent data
 * defect rather than a startup failure.</p>
 *
 * <h2>What this guard asserts</h2>
 * <p>Immediately after the JPA {@code entityManagerFactory} is initialised (so Flyway has migrated the
 * schema and Hibernate's own validation has already run &mdash; see {@link DependsOn}), this component
 * queries {@code information_schema.columns} for the {@code accounts} table and asserts, for each
 * monetary column, {@code numeric_precision = 12}, {@code numeric_scale = 2}, and {@code NOT NULL};
 * and for {@code version}, SQL type {@code bigint} and {@code NOT NULL}. Any deviation aborts context
 * refresh with an {@link IllegalStateException}, so the application refuses to start against a schema
 * that cannot faithfully store the monetary domain &mdash; exactly the behaviour the finding requires
 * when a money column is narrowed.</p>
 *
 * <h2>Normal operation</h2>
 * <p>The Flyway {@code V1__create_accounts_table.sql} migration defines all five money columns as
 * {@code NUMERIC(12,2) NOT NULL} and {@code version} as {@code BIGINT NOT NULL}, so this guard is a
 * no-op on a correctly-migrated database (including the Testcontainers integration database). It adds
 * value only when the live schema has drifted from the migration.</p>
 *
 * <h2>Security</h2>
 * <p>The guard reads schema <em>metadata</em> only (column names and type descriptors); it never reads
 * or logs any row data. A failure message names the offending column and its expected-vs-actual type
 * descriptor &mdash; no account identifier or monetary value can appear (AAP &sect;0.6.6; CWE-532).</p>
 */
@Component
@DependsOn("entityManagerFactory")
public class SchemaIntegrityValidator implements InitializingBean {

    /** Table whose money/version columns are verified. */
    private static final String TABLE = "accounts";

    /** Required precision for every monetary column (10 integer + 2 fraction digits). */
    private static final int MONEY_PRECISION = 12;

    /** Required scale for every monetary column (exact cents). */
    private static final int MONEY_SCALE = 2;

    /** The five monetary columns that map to {@code PIC S9(10)V99} / {@code BigDecimal} scale 2. */
    private static final List<String> MONEY_COLUMNS = List.of(
            "current_balance",
            "credit_limit",
            "cash_credit_limit",
            "current_cycle_credit",
            "current_cycle_debit");

    /** The optimistic-locking control column. */
    private static final String VERSION_COLUMN = "version";

    /** {@code information_schema} SQL type name PostgreSQL reports for a {@code NUMERIC} column. */
    private static final String TYPE_NUMERIC = "numeric";

    /** {@code information_schema} SQL type name PostgreSQL reports for a {@code BIGINT} column. */
    private static final String TYPE_BIGINT = "bigint";

    /** Datastore access for the metadata query (auto-configured from the primary datasource). */
    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates the validator.
     *
     * @param jdbcTemplate the datastore access used to read {@code information_schema}
     */
    public SchemaIntegrityValidator(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Runs the precision/scale/nullability assertions once the JPA layer is up. Aggregates every
     * deviation so a single restart reports all problems at once.
     *
     * @throws IllegalStateException if any money column is not {@code NUMERIC(12,2) NOT NULL} or the
     *                               version column is not {@code BIGINT NOT NULL}, or if an expected
     *                               column is missing from the live schema
     */
    @Override
    public void afterPropertiesSet() {
        final Map<String, ColumnMeta> columns = loadColumns();
        final List<String> problems = new ArrayList<>();

        for (final String money : MONEY_COLUMNS) {
            final ColumnMeta meta = columns.get(money);
            if (meta == null) {
                problems.add("column '" + money + "' is missing (expected NUMERIC(12,2) NOT NULL)");
                continue;
            }
            if (!TYPE_NUMERIC.equalsIgnoreCase(meta.dataType())
                    || meta.numericPrecision() != MONEY_PRECISION
                    || meta.numericScale() != MONEY_SCALE
                    || meta.nullable()) {
                problems.add("column '" + money + "' expected NUMERIC(12,2) NOT NULL but found "
                        + describe(meta));
            }
        }

        final ColumnMeta version = columns.get(VERSION_COLUMN);
        if (version == null) {
            problems.add("column '" + VERSION_COLUMN + "' is missing (expected BIGINT NOT NULL)");
        } else if (!TYPE_BIGINT.equalsIgnoreCase(version.dataType()) || version.nullable()) {
            problems.add("column '" + VERSION_COLUMN + "' expected BIGINT NOT NULL but found "
                    + describe(version));
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Schema integrity check failed for table '" + TABLE + "': "
                            + String.join("; ", problems)
                            + ". The database schema cannot faithfully store the account monetary "
                            + "domain (S9(10)V99, scale 2); refusing to start.");
        }
    }

    /**
     * Loads the {@code accounts} column metadata from {@code information_schema.columns} into a
     * case-normalised map keyed by column name.
     *
     * @return a map of lower-cased column name to its {@link ColumnMeta}
     */
    private Map<String, ColumnMeta> loadColumns() {
        final Map<String, ColumnMeta> byName = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT column_name, data_type, numeric_precision, numeric_scale, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = ? AND table_schema = current_schema()",
                rs -> {
                    final String name = rs.getString("column_name").toLowerCase(Locale.ROOT);
                    final String dataType = rs.getString("data_type");
                    // numeric_precision/scale are NULL for non-numeric columns; getInt maps NULL->0,
                    // which is fine because those columns are never checked for precision/scale.
                    final int precision = rs.getInt("numeric_precision");
                    final int scale = rs.getInt("numeric_scale");
                    final boolean nullable = "YES".equalsIgnoreCase(rs.getString("is_nullable"));
                    byName.put(name, new ColumnMeta(dataType, precision, scale, nullable));
                },
                TABLE);
        return byName;
    }

    /**
     * Renders a column's live type descriptor for a failure message (metadata only, no row data).
     *
     * @param meta the observed column metadata
     * @return a human-readable descriptor such as {@code NUMERIC(11,2) NOT NULL} or {@code bigint NULL}
     */
    private static String describe(final ColumnMeta meta) {
        final String nullability = meta.nullable() ? "NULL" : "NOT NULL";
        if (TYPE_NUMERIC.equalsIgnoreCase(meta.dataType())) {
            return "NUMERIC(" + meta.numericPrecision() + "," + meta.numericScale() + ") " + nullability;
        }
        return meta.dataType() + " " + nullability;
    }

    /**
     * Immutable snapshot of a single column's relevant {@code information_schema} metadata.
     *
     * @param dataType         the {@code information_schema} SQL type name (for example {@code numeric})
     * @param numericPrecision the declared precision ({@code 0} when not applicable)
     * @param numericScale     the declared scale ({@code 0} when not applicable)
     * @param nullable         whether the column permits NULL
     */
    private record ColumnMeta(String dataType, int numericPrecision, int numericScale, boolean nullable) {
    }
}
