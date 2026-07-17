/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers-backed integration test for {@link LocalSeedDataLoader}, the
 * {@code @Profile("local")} {@code ApplicationRunner} that loads the bulk
 * demonstration data ({@code src/main/resources/db/seed/*.csv}) into the
 * relational tables on a clean local start.
 *
 * <p><strong>What this verifies (AAP&nbsp;&sect;0.5.5 seed provisioning,
 * &sect;0.9.2).</strong> A clean local start must leave the eight bulk tables
 * populated with the shipped demonstration dataset &mdash; including the
 * documented sign-on identities ({@code ADMIN001}/{@code USER0001}) &mdash; and
 * a restart against an already-seeded database must neither duplicate rows nor
 * error. This test exercises the loader end-to-end against a real
 * PostgreSQL&nbsp;16 database and asserts both properties.
 *
 * <p><strong>Why the loader is driven directly (not through Spring).</strong>
 * The loader is a bean only under the {@code local} profile; the automated suite
 * runs under {@code test}, where it is intentionally inert (integration tests
 * manage their own fixtures). The test therefore instantiates the loader
 * directly with a {@link DataSource} pointed at a disposable
 * {@code postgres:16-alpine} container, which is exactly the collaborator the
 * production {@code local}-profile datasource would supply.
 *
 * <p><strong>Schema ownership.</strong> As in production, Flyway owns the schema:
 * the real migrations ({@code classpath:db/migration}: {@code V1__schema.sql} and
 * {@code V2__reference_data.sql}) are applied to the container so the tables,
 * foreign keys, and the three Flyway-owned reference tables exist before the
 * loader runs. No JDBC URL, username, or password is hardcoded &mdash; every
 * connection value comes from the Testcontainers instance
 * (AAP&nbsp;&sect;0.8.1, &sect;0.9.3).
 *
 * @see LocalSeedDataLoader
 */
@Testcontainers
class LocalSeedDataLoaderTest {

    /**
     * A single PostgreSQL&nbsp;16 container shared by the test (started once).
     * The same image the repository integration tests use, so it is already
     * cached locally.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /**
     * The expected row count of every bulk table after a clean seed load, in the
     * loader's foreign-key load order. These are the shipped fixture sizes under
     * {@code db/seed/} (customer&nbsp;50, users&nbsp;10, account&nbsp;50,
     * card&nbsp;50, cross-reference&nbsp;50, transaction&nbsp;300, category
     * balance&nbsp;50, daily-transaction staging&nbsp;300 &mdash; 860 rows total).
     */
    private static final Map<String, Integer> EXPECTED_COUNTS = new LinkedHashMap<>();

    static {
        EXPECTED_COUNTS.put("customer", 50);
        EXPECTED_COUNTS.put("user_security", 10);
        EXPECTED_COUNTS.put("account", 50);
        EXPECTED_COUNTS.put("card", 50);
        EXPECTED_COUNTS.put("card_xref", 50);
        EXPECTED_COUNTS.put("transaction", 300);
        EXPECTED_COUNTS.put("tran_cat_balance", 50);
        EXPECTED_COUNTS.put("daily_transaction", 300);
    }

    /** The datasource pointed at the container, shared with the loader under test. */
    private static DataSource dataSource;

    /**
     * Builds the container datasource and applies the real Flyway migrations so
     * the schema and Flyway-owned reference data exist before any test runs.
     */
    @BeforeAll
    static void migrateSchema() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        dataSource = ds;

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /**
     * A clean local start seeds every bulk table with its full shipped dataset
     * (including the documented sign-on identities), and a second start against
     * the now-populated database is idempotent: it inserts nothing, does not
     * error, and leaves the row counts unchanged.
     *
     * @throws Exception if the loader or a verification query fails
     */
    @Test
    void seedsAllBulkTablesAndIsIdempotent() throws Exception {
        LocalSeedDataLoader loader = new LocalSeedDataLoader(dataSource);

        // --- First (clean) start: every bulk table is loaded from its CSV. ---
        loader.run(new DefaultApplicationArguments());

        int expectedTotal = 0;
        for (Map.Entry<String, Integer> entry : EXPECTED_COUNTS.entrySet()) {
            assertThat(count(entry.getKey()))
                    .as("row count of '%s' after clean seed load", entry.getKey())
                    .isEqualTo(entry.getValue());
            expectedTotal += entry.getValue();
        }

        // The documented demonstration logins must be present after seeding
        // (onboarding goal: a populated dataset with working sign-on identities).
        assertThat(exists("SELECT 1 FROM user_security WHERE sec_usr_id = 'ADMIN001'"))
                .as("admin demonstration login ADMIN001 is seeded").isTrue();
        assertThat(exists("SELECT 1 FROM user_security WHERE sec_usr_id = 'USER0001'"))
                .as("regular demonstration login USER0001 is seeded").isTrue();

        // --- Second start against the already-seeded database: idempotent. ---
        loader.run(new DefaultApplicationArguments());

        int actualTotal = 0;
        for (Map.Entry<String, Integer> entry : EXPECTED_COUNTS.entrySet()) {
            int c = count(entry.getKey());
            assertThat(c)
                    .as("row count of '%s' is unchanged after a second (idempotent) run",
                            entry.getKey())
                    .isEqualTo(entry.getValue());
            actualTotal += c;
        }
        assertThat(actualTotal)
                .as("total bulk row count is stable across restarts")
                .isEqualTo(expectedTotal);
    }

    /**
     * Returns the number of rows in {@code table}. The table name comes only from
     * this test's fixed {@link #EXPECTED_COUNTS} map, never external input.
     *
     * @param table the table to count
     * @return the current row count
     * @throws SQLException if the query fails
     */
    private int count(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    /**
     * Returns {@code true} if the given single-row existence query returns a row.
     *
     * @param sql a {@code SELECT 1 ... } probe
     * @return whether at least one row matched
     * @throws SQLException if the query fails
     */
    private boolean exists(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next();
        }
    }
}
