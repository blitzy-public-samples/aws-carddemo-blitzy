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
package com.carddemo.common.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.carddemo.common.testsupport.MigratedSchemaContainer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the migration inventory every CardDemo service applies.
 *
 * :purpose: Turn the applied Flyway history into an assertion, so a migration that is
 *     dropped, renumbered, renamed or silently re-described fails the build instead of
 *     being discovered when a service refuses to start or when Hibernate's
 *     ``ddl-auto: validate`` reports a missing column. The set spans SQL files AND the
 *     one Java migration (version ``4``,
 *     :java:class:`com.carddemo.common.migration.SeededPiiEncryptionMigration`), which is
 *     why "``V4__*.sql`` is missing" has repeatedly been misread as a gap in the set.
 * :output: Assertions over ``flyway_schema_history`` in the shared, already-migrated
 *     ``postgres:18`` container - the exact version list in the exact installed order,
 *     each description, every row's ``success`` flag, and the absence of any extra row.
 *
 * The history is read from :java:class:`com.carddemo.common.testsupport.MigratedSchemaContainer`,
 * which applies the committed migrations exactly as a deployment does, so this class
 * asserts against the real applied state and never against a list restated in a fixture.
 */
class SchemaMigrationInventoryIT {

    /**
     * :purpose: The versions the committed set applies, in installed order. A new
     *     migration must be added here deliberately, which is the point of the class.
     */
    private static final List<String> EXPECTED_VERSIONS =
            List.of("1", "2", "3", "4", "5", "6", "7", "8");

    /**
     * :purpose: The description Flyway derives from each migration (the file-name suffix
     *     with underscores turned into spaces; the Java migration supplies its own).
     */
    private static final List<String> EXPECTED_DESCRIPTIONS = List.of(
            "create schema",
            "seed reference data",
            "seed test data",
            "encrypt seeded pii",
            "batch metadata",
            "security users optimistic lock",
            "cards optimistic lock",
            "transactions card fk");

    /** :purpose: One row of the applied history. */
    private record AppliedMigration(String version, String description, boolean success) {
    }

    /** :purpose: The applied history, read once for the whole class. */
    private static List<AppliedMigration> applied;

    /**
     * :purpose: Read ``flyway_schema_history`` from the shared migrated container.
     * :raises SQLException: if the history cannot be read.
     */
    @BeforeAll
    static void readAppliedHistory() throws SQLException {
        List<AppliedMigration> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                        MigratedSchemaContainer.jdbcUrl(),
                        MigratedSchemaContainer.username(),
                        MigratedSchemaContainer.password());
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT version, description, success FROM flyway_schema_history "
                                + "WHERE type <> 'BASELINE' ORDER BY installed_rank")) {
            while (resultSet.next()) {
                rows.add(new AppliedMigration(
                        resultSet.getString("version"),
                        resultSet.getString("description"),
                        resultSet.getBoolean("success")));
            }
        }
        applied = List.copyOf(rows);
    }

    /**
     * :purpose: The applied versions are exactly the committed set, in order. A dropped or
     *     renumbered migration, or an unrecorded extra one, breaks this assertion.
     */
    @Test
    @DisplayName("the applied versions are exactly V1-V8 in installed order")
    void appliedVersionsAreTheCommittedSet() {
        assertThat(applied).extracting(AppliedMigration::version)
                .containsExactlyElementsOf(EXPECTED_VERSIONS);
    }

    /**
     * :purpose: Each version carries the description the repository declares, so renaming a
     *     migration file (which changes its description and its checksum) is a deliberate act
     *     rather than a silent one.
     */
    @Test
    @DisplayName("each applied version carries its committed description")
    void appliedDescriptionsAreTheCommittedOnes() {
        assertThat(applied).extracting(AppliedMigration::description)
                .containsExactlyElementsOf(EXPECTED_DESCRIPTIONS);
    }

    /**
     * :purpose: Version 4 is the JAVA migration, not a missing SQL file. Asserted by name so
     *     the next reader of the history is not misled the way two test docstrings were.
     */
    @Test
    @DisplayName("version 4 is the Java seeded-PII migration, not an absent SQL file")
    void versionFourIsTheJavaMigration() {
        SeededPiiEncryptionMigration javaMigration = new SeededPiiEncryptionMigration();

        assertThat(javaMigration.getVersion().getVersion()).isEqualTo("4");
        assertThat(applied)
                .filteredOn(row -> "4".equals(row.version()))
                .singleElement()
                .satisfies(row -> assertThat(row.description())
                        .isEqualTo(javaMigration.getDescription()));
    }

    /**
     * :purpose: Every migration applied cleanly. A failed row leaves the schema half-built,
     *     and Flyway records it rather than throwing on the next run.
     */
    @Test
    @DisplayName("every applied migration succeeded")
    void everyAppliedMigrationSucceeded() {
        assertThat(applied).isNotEmpty().allSatisfy(row ->
                assertThat(row.success())
                        .as("migration %s (%s) must have succeeded", row.version(), row.description())
                        .isTrue());
    }
}
