/*
 * ============================================================================
 * CardDemoApplication.java — Spring Boot Main Entry Point
 * ============================================================================
 * AWS CardDemo Mainframe Application
 * Migrated from COBOL/CICS/VSAM/JCL to Java 25 + Spring Boot 3.5.x
 *
 * This class replaces the CICS Transaction Processing (TP) monitor that
 * previously managed program dispatch for the CardDemo application. In the
 * original COBOL architecture, CICS acted as the runtime container that:
 *   - Dispatched transactions (CC00, CM00, CA00, etc.) to COBOL programs
 *   - Managed pseudo-conversational session state via the 1024-byte COMMAREA
 *     (defined in COCOM01Y.cpy)
 *   - Handled XCTL program-to-program transfers (e.g., COSGN00C → COMEN01C)
 *
 * In the migrated architecture, Spring Boot's auto-configuration replaces
 * CICS by discovering and wiring all annotated beans:
 *   - @Service classes (online services replacing 18 CICS programs)
 *   - @Repository interfaces (JPA repositories replacing 10 VSAM KSDS files)
 *   - @Controller classes (REST endpoints replacing BMS map interactions)
 *   - @Configuration classes (SecurityConfig, BatchConfig, JpaConfig, etc.)
 *   - Spring Batch infrastructure (replacing JCL batch job orchestration)
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 * ============================================================================
 */
package com.cardemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot application entry point for the CardDemo credit card management system.
 *
 * <p>This class serves as the bootstrap entry point for the entire CardDemo application,
 * replacing the CICS TP monitor that previously managed COBOL program dispatch. The
 * {@link SpringBootApplication} annotation enables:</p>
 * <ul>
 *   <li>Auto-configuration of all Spring Boot starters (web, data-jpa, security,
 *       actuator, validation, batch)</li>
 *   <li>Component scanning of all sub-packages (config, service, controller, batch,
 *       entity, repository, common)</li>
 *   <li>Configuration properties binding from application.yml</li>
 * </ul>
 *
 * <p>Spring Boot 3.x auto-configures Spring Batch infrastructure (JobRepository,
 * JobLauncher) without requiring {@code @EnableBatchProcessing}. The custom
 * {@link com.cardemo.config.BatchConfig} overrides specific beans as needed for the
 * 7 batch job configurations migrated from JCL: DailyPostingJob, InterestCalcJob,
 * TransactionSortJob, StatementGenJob, AccountLoadJob, CustomerLoadJob, and
 * TransactionLoadJob.</p>
 *
 * <p>All business logic resides in dedicated service, batch, and controller packages.
 * This entry point class contains no business logic — it exists solely to bootstrap
 * the Spring application context.</p>
 *
 * <p>Authentication is provided by a custom {@link com.cardemo.config.CardDemoUserDetailsService}
 * that loads user credentials from the {@code user_security} table (formerly USRSEC VSAM).
 * No auto-configuration exclusion is needed — the custom {@code UserDetailsService} bean
 * takes priority over Spring Boot's default in-memory user store.</p>
 *
 * @see com.cardemo.config.SecurityConfig
 * @see com.cardemo.config.BatchConfig
 * @see com.cardemo.config.JpaConfig
 * @see com.cardemo.config.CardDemoUserDetailsService
 */
@SpringBootApplication
public class CardDemoApplication {

    /**
     * Application entry point that bootstraps the Spring Boot context.
     *
     * <p>Replaces the CICS TP monitor startup sequence that previously initialized
     * the transaction processing environment, loaded program definitions, and
     * opened VSAM file connections. Spring Boot's {@link SpringApplication#run}
     * performs the equivalent initialization by:</p>
     * <ul>
     *   <li>Creating and refreshing the ApplicationContext</li>
     *   <li>Auto-configuring all registered starters</li>
     *   <li>Starting the embedded Tomcat web server</li>
     *   <li>Executing Flyway database migrations</li>
     *   <li>Initializing Spring Batch job infrastructure</li>
     * </ul>
     *
     * @param args command-line arguments passed to the application
     */
    public static void main(String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
    }
}
