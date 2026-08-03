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

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A single ``postgres:18`` Testcontainer per test JVM, carrying the complete
 * business schema produced by the committed Flyway migrations.
 *
 * :purpose: Let every integration test in a module share one real database whose
 *     schema comes exclusively from :java:class:`CardDemoSchemaMigrations`, so
 *     Hibernate ``ddl-auto: validate`` verifies the entity-to-migration contract
 *     and no test has to fabricate DDL for tables another module owns.
 * :output: A started container plus a helper that binds its coordinates to the
 *     Spring ``Environment``.
 *
 * The container is created and migrated once, lazily, on first class use and is
 * deliberately never stopped by a JUnit callback: the Testcontainers Ryuk reaper
 * removes it after the fork exits, strictly after every Spring context and its
 * connection pool have closed. Tests that mutate seeded rows must restore them, as
 * they already must within a class.
 */
public final class MigratedSchemaContainer {

    /** :purpose: Production database major version, pinned to match deployments. */
    private static final DockerImageName IMAGE = DockerImageName.parse("postgres:18");

    /** :purpose: The shared, already-migrated container for this JVM. */
    private static final PostgreSQLContainer<?> CONTAINER;

    static {
        CONTAINER = new PostgreSQLContainer<>(IMAGE).withDatabaseName("carddemo");
        CONTAINER.start();
        CardDemoSchemaMigrations.migrateAll(
                CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    /**
     * :purpose: Prevent instantiation of this static holder.
     */
    private MigratedSchemaContainer() {
        throw new AssertionError("MigratedSchemaContainer is a static holder");
    }

    /**
     * Returns the shared container, starting and migrating it on first access.
     *
     * :output: the running :java:class:`PostgreSQLContainer`.
     */
    public static PostgreSQLContainer<?> container() {
        return CONTAINER;
    }

    /**
     * Returns the JDBC url of the shared container.
     *
     * :output: a ``jdbc:postgresql://...`` url bound to an ephemeral host port.
     */
    public static String jdbcUrl() {
        return CONTAINER.getJdbcUrl();
    }

    /**
     * Returns the database user of the shared container.
     *
     * :output: the container's user name.
     */
    public static String username() {
        return CONTAINER.getUsername();
    }

    /**
     * Returns the database password of the shared container.
     *
     * :output: the container's password.
     */
    public static String password() {
        return CONTAINER.getPassword();
    }

    /**
     * Binds the shared container's coordinates to a Spring test context.
     *
     * :param registry: the registry supplied by a ``@DynamicPropertySource`` method.
     * :output: ``spring.datasource.*`` resolved to the migrated container, with the
     *     plain PostgreSQL driver replacing any Testcontainers JDBC-URL driver
     *     configured by a profile, so exactly one container backs the tests.
     */
    public static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MigratedSchemaContainer::jdbcUrl);
        registry.add("spring.datasource.username", MigratedSchemaContainer::username);
        registry.add("spring.datasource.password", MigratedSchemaContainer::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
}
