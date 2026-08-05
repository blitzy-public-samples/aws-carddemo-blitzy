package com.carddemo.notification.repository;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Gives every repository test in this package a live PostgreSQL database carrying the migrated
 * schema.
 *
 * <p>The nested configuration below supplies one container as a Spring bean, and Spring Boot reads
 * the datasource host, port, database, login and password from it. Flyway then applies
 * {@code src/main/resources/db/migration/V1__schema.sql} into the schema
 * {@code src/main/resources/application.yml} names, and Hibernate validates every Jakarta
 * Persistence (JPA) mapping against the migrated catalogue. So {@code mvn test} asks for a running
 * Docker daemon and nothing else: no hand-made database, no schema step, no environment variable.
 *
 * <p>Every subclass shares one Spring context, so one container serves the whole package. The
 * cardholder projection starts with fixture-backed rows; the read model, attempt log and marker
 * table start empty. Each test method runs in a transaction the slice rolls back, so one subclass
 * never reads a row another subclass wrote.</p>
 *
 * <p>The repository contract those subclasses exercise descends from
 * {@code app/cbl/CBSTM03B.CBL}. Its parameter area {@code 01 LK-M03B-AREA.} at line 100 routes
 * every read and write, carrying an operation code at line 102 and a key at line 110.
 *
 * <p>Agent Action Plan section 0.5.1 pins each version named here: {@code postgres:18.4},
 * Testcontainers 2.0.5 and Java 25.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(NotificationRepositoryTestSupport.PostgresContainerConfiguration.class)
abstract class NotificationRepositoryTestSupport {

    /** Database image the container runs, pinned by Agent Action Plan section 0.5.1. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /** Database name, login and password of the disposable container, all one demo value. */
    private static final String DATABASE_CREDENTIAL = "carddemo";

    /** Supplies the single database container every test in this package shares. */
    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresContainerConfiguration {

        /**
         * Declares the database container as a Spring bean.
         *
         * <p>{@code @ServiceConnection} hands the running container's coordinates to the datasource
         * under test. No test then reaches the compose hostname that
         * {@code src/main/resources/application.yml} defaults to.
         *
         * @return the container Spring starts before the first test and stops with the context
         */
        @Bean
        @ServiceConnection
        PostgreSQLContainer notificationPostgres() {
            return new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName(DATABASE_CREDENTIAL)
                    .withUsername(DATABASE_CREDENTIAL)
                    .withPassword(DATABASE_CREDENTIAL);
        }
    }
}
