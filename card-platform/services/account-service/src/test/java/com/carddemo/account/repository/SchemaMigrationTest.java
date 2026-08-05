package com.carddemo.account.repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that the five Flyway migrations of the account service apply to an empty schema, and that
 * the seed loads the row counts its fixtures carry.
 *
 * <p>Every method here reads, and none writes. Other test classes in this package assert the same
 * seeded counts.</p>
 *
 * <p><b>What a started context already proves.</b> {@link AbstractAccountPostgresTest} boots one
 * Spring context against one PostgreSQL container. Hibernate checks the six Jakarta Persistence
 * (JPA) entity classes against the migrated schema during that start, and a mapped column the
 * migrations never created stops the start. The methods below add what a mapping check cannot see:
 * how many migrations ran, in which order, which tables landed, and how many rows each seeded table
 * holds.</p>
 *
 * <p><b>Nine tables, six entity classes.</b> The migrations declare nine tables, and this module
 * maps six of them. The three validation tables {@code us_phone_area_code},
 * {@code us_state_code} and {@code us_state_zip_prefix} carry no entity class and no repository
 * interface. Every assertion below reads plain Structured Query Language (SQL) over a Java Database
 * Connectivity (JDBC) connection. {@code card-platform/docs/decision-log.md} records that decision,
 * and {@code card-platform/docs/data-model.md} draws the table shapes and the lookup path
 * between them.</p>
 *
 * <p><b>Where the three seeded counts come from.</b> Each count equals the record count of one
 * fixture, and each replaces one dataset copy step. IDCAMS is the z/OS dataset utility, and its
 * {@code REPRO} command copies a flat file into a Virtual Storage Access Method (VSAM) dataset.
 * Each copy step sits in a Job Control Language (JCL) member that also declares the record
 * width.</p>
 *
 * <ul>
 *   <li>{@code account} holds 50 rows. {@code app/data/ASCII/acctdata.txt} carries 50 records of
 *       300 characters, {@code app/jcl/ACCTFILE.jcl:L41} declares {@code RECORDSIZE(300 300)} and
 *       {@code app/jcl/ACCTFILE.jcl:L61} copies the file.</li>
 *   <li>{@code customer} holds 50 rows. {@code app/data/ASCII/custdata.txt} carries 50 records of
 *       500 characters, {@code app/jcl/CUSTFILE.jcl:L51} declares {@code RECORDSIZE(500 500)} and
 *       {@code app/jcl/CUSTFILE.jcl:L71} copies the file.</li>
 *   <li>{@code disclosure_group} holds 51 rows. {@code app/data/ASCII/discgrp.txt} carries 51
 *       records of 50 characters, {@code app/jcl/DISCGRP.jcl:L41} declares
 *       {@code RECORDSIZE(50 50)} and {@code app/jcl/DISCGRP.jcl:L61} copies the file.</li>
 * </ul>
 *
 * <p><b>Which schema every query names.</b> The Flyway bean reports the schema it migrated, and
 * {@link #migratedSchema()} reads the name from there. No schema name appears below as a
 * literal.</p>
 *
 * <p><b>What the sibling classes own.</b> Column types and scales, the reference-data counts, the
 * remaining absence checks and every repository method belong to other classes in this
 * package.</p>
 */
@DisplayName("Flyway migrations and seeded row counts of the account schema")
class SchemaMigrationTest extends AbstractAccountPostgresTest {

    // ------------------------------------------------------------------------------------------
    // Table names. The migrations declare the first nine; Flyway creates the tenth.
    // ------------------------------------------------------------------------------------------

    /** The 300-byte account record of {@code app/cpy/CVACT01Y.cpy}. */
    private static final String ACCOUNT_TABLE = "account";

    /** The 500-byte customer record of {@code app/cpy/CVCUS01Y.cpy}. */
    private static final String CUSTOMER_TABLE = "customer";

    /** The 50-byte disclosure group record of {@code app/cpy/CVTRA02Y.cpy}. */
    private static final String DISCLOSURE_GROUP_TABLE = "disclosure_group";

    /** Telephone area-code validation data, seeded by {@code V3__reference_data.sql}. */
    private static final String PHONE_AREA_CODE_TABLE = "us_phone_area_code";

    /** State-code validation data, seeded by {@code V3__reference_data.sql}. */
    private static final String STATE_CODE_TABLE = "us_state_code";

    /** State and postal-prefix validation data, seeded by {@code V3__reference_data.sql}. */
    private static final String STATE_ZIP_PREFIX_TABLE = "us_state_zip_prefix";

    /** Additive publication table with no source-record ancestor. */
    private static final String OUTBOX_EVENT_TABLE = "outbox_event";

    /** Additive duplicate-delivery marker table with no source-record ancestor. */
    private static final String PROCESSED_EVENT_TABLE = "processed_event";

    /** Service-local replica used to derive an account's customer identifier. */
    private static final String CARD_CROSS_REFERENCE_TABLE = "card_xref";

    /** The nine tables the migrations declare. */
    private static final String[] DECLARED_TABLES = {
            ACCOUNT_TABLE,
            CUSTOMER_TABLE,
            DISCLOSURE_GROUP_TABLE,
            PHONE_AREA_CODE_TABLE,
            STATE_CODE_TABLE,
            STATE_ZIP_PREFIX_TABLE,
            OUTBOX_EVENT_TABLE,
            PROCESSED_EVENT_TABLE,
            CARD_CROSS_REFERENCE_TABLE
    };

    /**
     * Flyway creates and owns this bookkeeping table, and no migration of this module declares it.
     * {@link #migratedSchemaHoldsTheNineDeclaredTables()} filters it out before comparing the
     * table set.
     */
    private static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";

    // ------------------------------------------------------------------------------------------
    // Expected migration history: versions and script names. No checksum appears here, and no
    // assertion below reads one.
    // ------------------------------------------------------------------------------------------

    /** Versions Flyway parses from the five migration file names, in installed order. */
    private static final String[] MIGRATION_VERSIONS = {"1", "2", "3", "4", "5"};

    /**
     * The five files under {@code src/main/resources/db/migration}, in installed order.
     */
    private static final String[] MIGRATION_SCRIPTS = {
            "V1__schema.sql",
            "V2__seed.sql",
            "V3__reference_data.sql",
            "V4__card_cross_reference_replica.sql",
            "V5__outbox_dead_letter_state.sql"
    };

    // ------------------------------------------------------------------------------------------
    // Expected seeded row counts, each measured on the fixture the class Javadoc cites.
    // ------------------------------------------------------------------------------------------

    /** Rows {@code V2__seed.sql} inserts into {@code account}, one per fixture record. */
    private static final long ACCOUNT_SEED_ROWS = 50L;

    /** Rows {@code V2__seed.sql} inserts into {@code customer}, one per fixture record. */
    private static final long CUSTOMER_SEED_ROWS = 50L;

    /** Rows {@code V2__seed.sql} inserts into {@code disclosure_group}, one per fixture record. */
    private static final long DISCLOSURE_GROUP_SEED_ROWS = 51L;

    /** Rows {@code V2__seed.sql} inserts across its three tables. */
    private static final long SEEDED_ROWS_TOTAL = 151L;

    /** Rows the card cross-reference fixture carries. */
    private static final long CARD_CROSS_REFERENCE_SEED_ROWS = 50L;

    /**
     * An unquoted lower-case PostgreSQL identifier. {@link #migratedSchema()} holds the reported
     * schema name to this form before {@link #qualify(String)} writes it into a statement.
     */
    private static final Pattern UNQUOTED_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

    /** Ordinal of the single column each counting query selects. */
    private static final int FIRST_COLUMN = 1;

    /** The component that applied the five migrations, and the source of the schema name. */
    @Autowired
    private Flyway flyway;

    /**
     * One versioned row of the Flyway history table.
     *
     * @param installedRank the position Flyway applied the migration in, counting from one
     * @param version       the version Flyway parsed from the script name
     * @param script        the migration file name
     * @param success       whether the migration ran to completion
     */
    private record AppliedMigration(int installedRank, String version, String script,
            boolean success) {
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Returns the schema the Flyway bean reports, holding it to an unquoted lower-case identifier.
     *
     * @return the schema the five migrations landed in
     */
    private String migratedSchema() {
        String reported = flyway.getConfiguration().getDefaultSchema();
        if (reported == null || reported.isBlank()) {
            String[] configured = flyway.getConfiguration().getSchemas();
            assertThat(configured)
                    .as("schemas the Flyway bean reports when it names no default schema")
                    .isNotEmpty();
            reported = configured[0];
        }
        assertThat(reported)
                .as("schema name the Flyway bean reports, which every statement below qualifies "
                        + "its table with")
                .isNotNull()
                .matches(UNQUOTED_IDENTIFIER);
        return reported;
    }

    /**
     * Qualifies a table name with the migrated schema.
     *
     * @param table one of the table-name constants of this class
     * @return the schema-qualified table name
     */
    private String qualify(String table) {
        return migratedSchema() + "." + table;
    }

    /**
     * Opens a connection to the container {@link AbstractAccountPostgresTest} started, reading its
     * coordinates from the accessor that class exposes.
     *
     * @return a new connection the caller closes
     * @throws SQLException when the driver refuses the connection
     */
    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                postgres().getJdbcUrl(), postgres().getUsername(), postgres().getPassword());
    }

    /**
     * Counts the rows of one table in the migrated schema.
     *
     * @param table one of the table-name constants of this class
     * @return the row count the database reports
     * @throws SQLException when the query fails
     */
    private long countRows(String table) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + qualify(table);
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("row the count query returns for %s", table).isTrue();
            return rows.getLong(FIRST_COLUMN);
        }
    }

    /**
     * Lists the base tables of the migrated schema, in name order. Views and other relation kinds
     * stay out of the result.
     *
     * @return every base-table name the catalogue reports for the migrated schema
     * @throws SQLException when the query fails
     */
    private List<String> baseTableNames() throws SQLException {
        String sql = """
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = ?
                   AND table_type = 'BASE TABLE'
                 ORDER BY table_name
                """;
        List<String> names = new ArrayList<>();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, migratedSchema());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(FIRST_COLUMN));
                }
            }
        }
        return names;
    }

    /**
     * Lists every column name of the migrated schema, in name order and without duplicates.
     *
     * @return each distinct column name the catalogue reports for the migrated schema
     * @throws SQLException when the query fails
     */
    private List<String> columnNames() throws SQLException {
        String sql = """
                SELECT DISTINCT column_name
                  FROM information_schema.columns
                 WHERE table_schema = ?
                 ORDER BY column_name
                """;
        List<String> names = new ArrayList<>();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, migratedSchema());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(FIRST_COLUMN));
                }
            }
        }
        return names;
    }

    /**
     * Reads the versioned rows of the Flyway history table in installed-rank order. A repeatable
     * migration carries no version and would not appear; this module ships none.
     *
     * @return one entry per versioned migration, ordered by the rank Flyway assigned
     * @throws SQLException when the query fails
     */
    private List<AppliedMigration> appliedMigrations() throws SQLException {
        String sql = "SELECT installed_rank, version, script, success FROM "
                + qualify(FLYWAY_HISTORY_TABLE)
                + " WHERE version IS NOT NULL ORDER BY installed_rank";
        List<AppliedMigration> applied = new ArrayList<>();
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                applied.add(new AppliedMigration(
                        rows.getInt("installed_rank"),
                        rows.getString("version"),
                        rows.getString("script"),
                        rows.getBoolean("success")));
            }
        }
        return applied;
    }

    // ------------------------------------------------------------------------------------------
    // The five migrations applied, and applied in order
    // ------------------------------------------------------------------------------------------

    /**
     * Reads the history table in installed-rank order and compares the version sequence. Version 1
     * therefore installed before version 2, and each later version before the one after it.
     *
     * @throws SQLException when the history query fails
     */
    @Test
    @DisplayName("flyway_schema_history holds five versioned rows, versions 1 through 5 in "
            + "installed-rank order")
    void historyHoldsTheFiveVersionsInInstalledRankOrder() throws SQLException {
        List<AppliedMigration> applied = appliedMigrations();
        List<Integer> ranks = applied.stream().map(AppliedMigration::installedRank).toList();

        assertThat(applied).extracting(AppliedMigration::version)
                .as("versions in %s of schema %s, read at installed ranks %s",
                        FLYWAY_HISTORY_TABLE, migratedSchema(), ranks)
                .containsExactly(MIGRATION_VERSIONS);
    }

    /**
     * Asserts the success flag of every versioned row. A migration that failed part way leaves the
     * flag false and the schema half built.
     *
     * @throws SQLException when the history query fails
     */
    @Test
    @DisplayName("every versioned row of flyway_schema_history is marked successful")
    void historyMarksEveryVersionedRowSuccessful() throws SQLException {
        assertThat(appliedMigrations()).extracting(AppliedMigration::success)
                .as("success flag of every versioned row in %s", FLYWAY_HISTORY_TABLE)
                .containsOnly(true);
    }

    /**
     * Asserts the five script names in installed-rank order.
     *
     * @throws SQLException when the history query fails
     */
    @Test
    @DisplayName("the five versioned rows name all account migration scripts in order")
    void historyNamesTheFiveMigrationScripts() throws SQLException {
        assertThat(appliedMigrations()).extracting(AppliedMigration::script)
                .as("scripts under src/main/resources/db/migration, read in installed-rank order")
                .containsExactly(MIGRATION_SCRIPTS);
    }

    // ------------------------------------------------------------------------------------------
    // Nine tables, and exactly nine
    // ------------------------------------------------------------------------------------------

    /**
     * Compares the base-table set of the migrated schema against the nine declared tables.
     *
     * @throws SQLException when the catalogue query fails
     */
    @Test
    @DisplayName("the migrated schema holds the nine declared tables and no tenth")
    void migratedSchemaHoldsTheNineDeclaredTables() throws SQLException {
        List<String> declared = baseTableNames().stream()
                .filter(name -> !FLYWAY_HISTORY_TABLE.equals(name))
                .toList();

        assertThat(declared)
                .as("base tables of schema %s that the account migrations declare, with the "
                        + "Flyway-owned %s filtered out of the catalogue result",
                        migratedSchema(), FLYWAY_HISTORY_TABLE)
                .containsExactlyInAnyOrder(DECLARED_TABLES);
    }

    @Test
    @DisplayName("card_xref holds the 50 account-to-customer relationships of cardxref.txt")
    void cardCrossReferenceHoldsFiftySeededRows() throws SQLException {
        assertThat(countRows(CARD_CROSS_REFERENCE_TABLE))
                .as("rows V4__card_cross_reference_replica.sql inserts from "
                        + "app/data/ASCII/cardxref.txt")
                .isEqualTo(CARD_CROSS_REFERENCE_SEED_ROWS);
    }

    // ------------------------------------------------------------------------------------------
    // The three seeded row counts
    // ------------------------------------------------------------------------------------------

    /**
     * Counts the seeded account rows. {@code app/data/ASCII/acctdata.txt} carries 50 records, and
     * {@code app/jcl/ACCTFILE.jcl:L61} copies that file on the source branch.
     *
     * @throws SQLException when the counting query fails
     */
    @Test
    @DisplayName("account holds the 50 rows acctdata.txt carries")
    void accountHoldsFiftySeededRows() throws SQLException {
        assertThat(countRows(ACCOUNT_TABLE))
                .as("rows V2__seed.sql inserts into %s, one per record of "
                        + "app/data/ASCII/acctdata.txt, copied by REPRO at "
                        + "app/jcl/ACCTFILE.jcl:L61", ACCOUNT_TABLE)
                .isEqualTo(ACCOUNT_SEED_ROWS);
    }

    /**
     * Counts the seeded customer rows. {@code app/data/ASCII/custdata.txt} carries 50 records, and
     * {@code app/jcl/CUSTFILE.jcl:L71} copies that file on the source branch.
     *
     * @throws SQLException when the counting query fails
     */
    @Test
    @DisplayName("customer holds the 50 rows custdata.txt carries")
    void customerHoldsFiftySeededRows() throws SQLException {
        assertThat(countRows(CUSTOMER_TABLE))
                .as("rows V2__seed.sql inserts into %s, one per record of "
                        + "app/data/ASCII/custdata.txt, copied by REPRO at "
                        + "app/jcl/CUSTFILE.jcl:L71", CUSTOMER_TABLE)
                .isEqualTo(CUSTOMER_SEED_ROWS);
    }

    /**
     * Counts the seeded disclosure group rows. {@code app/data/ASCII/discgrp.txt} carries 51
     * records, and {@code app/jcl/DISCGRP.jcl:L61} copies that file on the source branch.
     *
     * @throws SQLException when the counting query fails
     */
    @Test
    @DisplayName("disclosure_group holds the 51 rows discgrp.txt carries")
    void disclosureGroupHoldsFiftyOneSeededRows() throws SQLException {
        assertThat(countRows(DISCLOSURE_GROUP_TABLE))
                .as("rows V2__seed.sql inserts into %s, one per record of "
                        + "app/data/ASCII/discgrp.txt, copied by REPRO at "
                        + "app/jcl/DISCGRP.jcl:L61", DISCLOSURE_GROUP_TABLE)
                .isEqualTo(DISCLOSURE_GROUP_SEED_ROWS);
    }

    /**
     * Adds the three counted totals and compares the sum against one expected value. The sum stands
     * on its own and reads none of the three per-table constants.
     *
     * @throws SQLException when a counting query fails
     */
    @Test
    @DisplayName("the three seeded tables hold 151 rows together")
    void theThreeSeededTablesHoldOneHundredFiftyOneRows() throws SQLException {
        long seeded = countRows(ACCOUNT_TABLE)
                + countRows(CUSTOMER_TABLE)
                + countRows(DISCLOSURE_GROUP_TABLE);

        assertThat(seeded)
                .as("rows V2__seed.sql inserts across %s, %s and %s, one per record of the three "
                        + "fixtures under app/data/ASCII",
                        ACCOUNT_TABLE, CUSTOMER_TABLE, DISCLOSURE_GROUP_TABLE)
                .isEqualTo(SEEDED_ROWS_TOTAL);
    }

    // ------------------------------------------------------------------------------------------
    // The two additive tables start empty
    // ------------------------------------------------------------------------------------------

    /**
     * Asserts the publication table holds no row once the migrations finish.
     * {@code V2__seed.sql} inserts into three tables, and the publication table is not one of them.
     *
     * @throws SQLException when the counting query fails
     */
    @Test
    @DisplayName("outbox_event holds no row after the five migrations")
    void outboxEventHoldsNoRowAfterMigration() throws SQLException {
        assertThat(countRows(OUTBOX_EVENT_TABLE))
                .as("rows in %s, which no migration of this module inserts into",
                        OUTBOX_EVENT_TABLE)
                .isZero();
    }

    /**
     * Asserts the duplicate-delivery marker table holds no row once the migrations finish. A
     * consumer writes every marker row at run time.
     *
     * @throws SQLException when the counting query fails
     */
    @Test
    @DisplayName("processed_event holds no row after the five migrations")
    void processedEventHoldsNoRowAfterMigration() throws SQLException {
        assertThat(countRows(PROCESSED_EVENT_TABLE))
                .as("rows in %s, which no migration of this module inserts into",
                        PROCESSED_EVENT_TABLE)
                .isZero();
    }
}
