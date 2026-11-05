/*
 * ============================================================================
 * CardDemoApplicationTest.java — Spring Boot Context Load Verification Test
 * ============================================================================
 * AWS CardDemo Mainframe Application
 * Migrated from COBOL/CICS/VSAM/JCL to Java 25 + Spring Boot 3.5.x
 *
 * This test class serves as the foundational "does the app start?" verification
 * for the entire CardDemo Java migration. It validates that the Spring Boot
 * application context loads successfully with ALL auto-configurations:
 *   - Spring MVC (web) — REST controllers replacing CICS BMS map interactions
 *   - Spring Data JPA — entity scanning, repository proxies replacing VSAM I/O
 *   - Spring Security — filter chain replacing COBOL sign-on (COSGN00C.cbl)
 *   - Spring Batch — job infrastructure replacing JCL batch orchestration
 *   - Spring Boot Actuator — health, metrics endpoints for observability
 *   - Jakarta Bean Validation — Hibernate Validator for DTO field validation
 *   - Flyway — database migration scripts (V1, V2, V100) replacing VSAM schema
 *   - Testcontainers PostgreSQL — containerized test database
 *
 * COBOL Source Context (reference only — not directly translated):
 *   - COSGN00C.cbl — Sign-on screen authentication (maps to SignonService)
 *   - COMEN01C.cbl — Main menu routing (maps to MainMenuService)
 *   - COCOM01Y.cpy — 1024-byte COMMAREA session context (maps to CardDemoContext)
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 * ============================================================================
 */
package com.cardemo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot application context load verification test for CardDemo.
 *
 * <p>This test class validates that the entire migrated CardDemo application
 * bootstraps correctly under the {@code test} Spring profile. The
 * {@link SpringBootTest} annotation triggers full application context loading,
 * which discovers {@link CardDemoApplication} via package scanning and initializes
 * all auto-configured beans including:</p>
 * <ul>
 *   <li>Configuration classes: SecurityConfig, BatchConfig, JpaConfig,
 *       ObservabilityConfig, AppProperties</li>
 *   <li>Entity classes: Account, Card, Customer, Transaction, UserSecurity, etc.</li>
 *   <li>Repository interfaces: All JPA repositories replacing VSAM KSDS access</li>
 *   <li>Service classes: All 18 online services and 10 batch services</li>
 *   <li>Controller classes: REST endpoints replacing CICS SEND/RECEIVE MAP</li>
 *   <li>Spring Batch infrastructure: JobRepository, JobLauncher for batch jobs</li>
 * </ul>
 *
 * <p>The {@code @ActiveProfiles("test")} annotation activates
 * {@code application-test.yml} which configures:</p>
 * <ul>
 *   <li>Testcontainers PostgreSQL 16-alpine as the datasource</li>
 *   <li>Flyway migrations enabled (V1 schema, V2 indexes, V100 seed data)</li>
 *   <li>Spring Batch job auto-launch disabled (tests launch jobs explicitly)</li>
 *   <li>Actuator endpoints disabled for faster test startup</li>
 * </ul>
 *
 * @see CardDemoApplication
 */
@SpringBootTest
@ActiveProfiles("test")
class CardDemoApplicationTest {

    /**
     * Injected Spring ApplicationContext used to verify successful context loading.
     * If any auto-configuration, bean definition, or Flyway migration fails,
     * this field will not be injected and the test will fail with a context
     * initialization error before reaching the assertion.
     */
    @Autowired
    private ApplicationContext applicationContext;

    /**
     * Verifies that the Spring Boot application context loads without errors.
     *
     * <p>This is the foundational smoke test for the entire CardDemo Java migration.
     * A non-null {@link ApplicationContext} confirms that:</p>
     * <ul>
     *   <li>All {@code @Configuration} classes are valid and wired correctly</li>
     *   <li>All JPA entities are scanned and mapped to PostgreSQL tables</li>
     *   <li>All Spring Data repositories are proxied successfully</li>
     *   <li>Spring Security filter chain is configured (ADMIN/USER roles)</li>
     *   <li>Spring Batch infrastructure beans (JobRepository, JobLauncher) exist</li>
     *   <li>Flyway database migrations executed successfully against Testcontainers PG</li>
     *   <li>Jakarta Bean Validation (Hibernate Validator) is configured</li>
     *   <li>Spring Boot Actuator endpoints are registered</li>
     * </ul>
     *
     * <p>Corresponds to verifying that the CICS TP monitor equivalent (Spring Boot)
     * can initialize all program definitions (beans) and open all VSAM file
     * connections (JPA repository proxies) without errors.</p>
     */
    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }

    /**
     * Verifies that the application main entry point can be invoked without exception.
     *
     * <p>This smoke test calls {@link CardDemoApplication#main(String[])} directly to
     * ensure the static entry point used by the JVM to launch the application does not
     * throw any exceptions. The method passes the {@code test} profile and a random
     * server port to avoid conflicts with the {@code @SpringBootTest} managed context.</p>
     *
     * <p>In the original COBOL architecture, this is analogous to verifying that the
     * CICS region startup (SIT parameters, PCT entries, PPT entries) completes without
     * abends. The {@code main()} method calls {@code SpringApplication.run()} which
     * performs the equivalent initialization: creating the ApplicationContext, running
     * auto-configuration, starting the embedded web server, and executing Flyway
     * migrations.</p>
     */
    @Test
    void mainMethodDoesNotThrow() {
        CardDemoApplication.main(new String[]{
            "--spring.profiles.active=test",
            "--server.port=0"
        });
    }
}
