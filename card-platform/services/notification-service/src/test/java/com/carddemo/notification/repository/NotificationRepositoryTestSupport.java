package com.carddemo.notification.repository;

import com.carddemo.notification.NotificationServiceDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Gives every repository test in this package a live PostgreSQL database carrying the migrated
 * schema.
 *
 * <p>{@link NotificationServiceDatabase} supplies the container, which the whole module fork
 * shares, and hands this class a database of its own inside it. The method below points the
 * datasource at that database. Flyway then applies
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
 * <p>No configuration class is nested here and none is named. The bean definitions arrive from the
 * class annotated {@code SpringBootConfiguration}, which is what a slice finds when nothing else is
 * declared. Supplying the container as a Spring bean annotated {@code ServiceConnection} would
 * require naming a configuration class, and Spring Framework 7.0 detects a nested one, ignores it,
 * and warns that 7.1 will stop ignoring it. Naming the datasource through
 * {@code DynamicPropertySource} also keeps the container out of the context lifecycle, and a
 * container Spring does not own is a container Spring cannot close while another class is still
 * reading its own database in it.
 *
 * <p>Agent Action Plan section 0.5.1 pins each version named here: {@code postgres:18.4},
 * Testcontainers 2.0.5 and Java 25.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
abstract class NotificationRepositoryTestSupport {

    /**
     * Points the datasource at the database this class and its subclasses share.
     *
     * <p>Three properties leave here and no fourth: every other datasource, Flyway and persistence
     * setting arrives from {@code src/main/resources/application.yml} on the test classpath. The
     * database belongs to this class rather than to a subclass, so the three subclasses read the
     * one database they read when they shared one container.
     *
     * @param registry the registry the Spring test context supplies
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> NotificationServiceDatabase.urlFor(NotificationRepositoryTestSupport.class));
        registry.add("spring.datasource.username", NotificationServiceDatabase.container()::getUsername);
        registry.add("spring.datasource.password", NotificationServiceDatabase.container()::getPassword);
    }
}
