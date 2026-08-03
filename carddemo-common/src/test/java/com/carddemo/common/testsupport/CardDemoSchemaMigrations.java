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
package com.carddemo.common.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.carddemo.common.migration.SeededPiiEncryptionMigration;

import org.flywaydb.core.Flyway;

/**
 * Applies the whole CardDemo business schema to a test database using the real,
 * committed Flyway migrations.
 *
 * :purpose: Give integration tests the exact schema a deployment gets, so that
 *     Hibernate ``ddl-auto: validate`` verifies the entity-to-migration contract
 *     instead of validating against a schema the test itself manufactured. Every
 *     service entity-scans ``com.carddemo.common.domain``, so validation needs the
 *     complete set of tables, not the subset one service happens to read.
 * :output: The tables, indexes, constraints and seed rows produced by the committed
 *     ``V*.sql`` files of the shared migration set, recorded in the single
 *     ``flyway_schema_history`` table a deployment uses. No DDL is declared here: the
 *     SQL files in the repository are the single source of truth.
 *
 * The migrations live in ``carddemo-common`` and are owned by exactly one set, so a
 * test applies them in one run against the same history table the services use and the
 * migration state a test produces is indistinguishable from the state the services
 * produce themselves. Runs are idempotent: applying the schema twice to the same
 * database is a no-op.
 *
 * The Java migration that brings the seeded PII columns to the at-rest format is applied
 * as part of the same run, because the services register it as a bean and their own Flyway
 * validation would otherwise report it as resolved-but-unapplied.
 */
public final class CardDemoSchemaMigrations {

    /**
     * :purpose: Module directory holding the single committed migration set, relative to
     *     the repository root.
     */
    private static final String MIGRATION_MODULE = "carddemo-common";

    /** :purpose: Relative path of the migration directory inside that module. */
    private static final String MIGRATION_PATH = "src/main/resources/db/migration";

    /** :purpose: Schema-history table the services configure. */
    private static final String HISTORY_TABLE = "flyway_schema_history";

    /**
     * :purpose: Prevent instantiation of this static utility.
     */
    private CardDemoSchemaMigrations() {
        throw new AssertionError("CardDemoSchemaMigrations is a static utility");
    }

    /**
     * Applies the committed migrations to the target database.
     *
     * :param jdbcUrl: JDBC url of the (throwaway) test database.
     * :param username: database user with DDL rights.
     * :param password: password for that user.
     * :output: The database carries the complete business schema and seed data.
     * :raises IllegalStateException: when the repository layout cannot be located
     *     or the migration directory is missing.
     */
    public static void migrateAll(String jdbcUrl, String username, String password) {
        Path location = repositoryRoot().resolve(MIGRATION_MODULE).resolve(MIGRATION_PATH);
        if (!Files.isDirectory(location)) {
            throw new IllegalStateException(
                    "Missing committed migrations for module " + MIGRATION_MODULE
                            + " at " + location);
        }
        Flyway.configure()
                // The committed SQL files are read straight from the repository so a
                // test can never diverge from what a deployment applies.
                .locations("filesystem:" + location)
                // The Java migration a deployment registers as a bean is applied here too,
                // so the history a test produces carries the same versions the services
                // resolve and their own Flyway run validates cleanly instead of reporting
                // a resolved-but-unapplied migration.
                .javaMigrations(new SeededPiiEncryptionMigration())
                .dataSource(jdbcUrl, username, password)
                .table(HISTORY_TABLE)
                // A container that already carries objects is baselined at version 0,
                // which records the baseline and still applies every V1+ script.
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    /**
     * Locates the repository root by walking up from the current working directory.
     *
     * :output: The directory containing the aggregator ``pom.xml`` and the module
     *     directories, whatever module the test was launched from.
     * :raises IllegalStateException: when no ancestor directory looks like the
     *     CardDemo repository root.
     */
    private static Path repositoryRoot() {
        Path candidate = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("carddemo-common"))
                    && Files.isDirectory(candidate.resolve("account-service"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Unable to locate the CardDemo repository root from working directory "
                        + System.getProperty("user.dir"));
    }
}
