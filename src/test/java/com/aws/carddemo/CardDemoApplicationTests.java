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
package com.aws.carddemo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Root application context-load smoke test for the AWS CardDemo COBOL&rarr;Java migration.
 *
 * <p>This is the canonical "does the whole application wire together and start?" test &mdash;
 * the test-tree counterpart of the production bootstrap class {@link CardDemoApplication}. It
 * boots the <em>full</em> Spring application context under the {@code test} profile against a
 * <strong>real PostgreSQL 16</strong> database supplied by Testcontainers. A successful startup
 * is the assertion: the {@code contextLoads()} body is intentionally minimal because merely
 * reaching a fully-initialized {@link ApplicationContext} transitively proves several things at
 * once.
 *
 * <ol>
 *   <li><b>Full bean wiring.</b> Every Spring bean across all layers instantiates and injects
 *       correctly &mdash; {@code @Configuration} classes, {@code @Service} components (including
 *       the {@code service.rule} validators), the Spring Data JPA {@code @Repository} beans, the
 *       {@code @RestController} web layer, the Spring Batch {@code Job}/{@code Step} definitions,
 *       Spring Security ({@code UserDetailsService}), and the observability beans
 *       (correlation-id filter, tracing). Any unsatisfiable dependency fails the context load.</li>
 *   <li><b>Flyway migration.</b> Flyway applies the shipped production migrations from
 *       {@code classpath:db/migration} ({@code V1__schema.sql} &rarr; {@code V2__reference_data.sql})
 *       against the container database on startup.</li>
 *   <li><b>Entity&harr;schema parity ({@code ddl-auto: validate}).</b> Hibernate validates every
 *       {@code com.aws.carddemo.domain} {@code @Entity} against the Flyway-authored schema without
 *       creating or mutating it. This is the single most valuable outcome: any drift between a JPA
 *       entity and the {@code V1__schema.sql} DDL (column name, type, nullability, length) fails
 *       the context load immediately, guarding the VSAM&rarr;PostgreSQL data-tier transformation.</li>
 *   <li><b>No batch auto-execution.</b> Because {@code spring.batch.job.enabled: false} is set in
 *       {@code application-test.yml}, the context loads without kicking off the posting, interest,
 *       statement, or report jobs; those are launched explicitly by dedicated {@code batch}
 *       integration tests.</li>
 * </ol>
 *
 * <h2>Testcontainers datasource wiring</h2>
 * <p>The {@code test} profile deliberately declares no {@code spring.datasource.*} values (it is
 * secret-free by design), and the base {@code application.yml} resolves the datasource from
 * environment variables with no literal fallback. The database connection is therefore supplied
 * dynamically at runtime from the {@link #POSTGRES} container via {@link DynamicPropertySource}
 * (see {@link #registerDataSourceProperties(DynamicPropertyRegistry)}). No connection string or
 * credential is ever hardcoded &mdash; the URL, username, and password all originate from the
 * ephemeral container. This mechanism relies only on {@code org.testcontainers:junit-jupiter} and
 * {@code org.testcontainers:postgresql}, the Testcontainers modules declared by the project build,
 * and {@code spring-test} (transitively provided by {@code spring-boot-starter-test}); it is the
 * datasource-wiring strategy explicitly accommodated by {@code application-test.yml}.
 *
 * <p>The container is {@code static} so it is started once and shared across every test method in
 * this class (fast, reused); the {@link Testcontainers} extension together with {@link Container}
 * manages its lifecycle. The image is {@code postgres:16-alpine} to match the PostgreSQL 16
 * production target.
 *
 * <p>The class and its test method are package-private, following modern JUnit 5 convention, and
 * the {@link ApplicationContext} is obtained through constructor injection (never field injection),
 * consistent with the project-wide dependency-injection rule.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CardDemoApplicationTests {

    /**
     * Shared, single-instance PostgreSQL 16 container backing the integration context.
     *
     * <p>Declared {@code static} so Testcontainers starts it once before the test class runs and
     * stops it afterwards, rather than per test method. The {@code postgres:16-alpine} image
     * satisfies the PostgreSQL 16 data-tier target of the migration.
     */
    @Container
    static PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /**
     * Binds the Spring datasource to the running Testcontainers PostgreSQL instance.
     *
     * <p>Registered as a {@link DynamicPropertySource} so the values are resolved lazily from the
     * live container after it has started. These dynamically-registered properties take precedence
     * over the base {@code application.yml} placeholders, so the context connects to the ephemeral
     * container regardless of any environment-variable configuration &mdash; and, crucially, without
     * baking any credential into source or configuration.
     *
     * @param registry the Spring test property registry to populate with the container's
     *                  connection coordinates
     */
    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** The fully-initialized application context under test, provided by the Spring TestContext. */
    private final ApplicationContext applicationContext;

    /**
     * Constructor-injects the application context.
     *
     * <p>Constructor injection (rather than field injection) keeps the dependency explicit and
     * final. The {@link Autowired} annotation instructs the Spring TestContext framework to resolve
     * the parameter from the booted context.
     *
     * @param applicationContext the running Spring application context
     */
    @Autowired
    CardDemoApplicationTests(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Verifies that the full application context starts successfully.
     *
     * <p>The primary assertion is implicit: if any bean fails to wire, Flyway fails to migrate, or
     * Hibernate's {@code validate} detects entity&harr;schema drift, the context never reaches this
     * method and the test fails during startup. The lightweight, non-brittle assertions below make
     * the success explicit without coupling the test to an exact bean inventory: the context is
     * non-null, and the {@code cardDemoApplication} bean (the {@link CardDemoApplication}
     * {@code @SpringBootApplication} itself, registered under its default decapitalized name) is
     * present.
     */
    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.containsBean("cardDemoApplication")).isTrue();
    }
}
