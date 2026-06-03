package com.carddemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CardDemo Spring Boot 3.2 Application Entry Point.
 *
 * <p>This class is the single executable entry point for the modernized CardDemo
 * application — a Java/Spring Boot 3.2 reimplementation of the original
 * COBOL/CICS/VSAM mainframe credit-card management system. The original
 * mainframe sources remain preserved unchanged under {@code app/} (COBOL programs,
 * JCL jobs, copybooks, BMS maps, CICS resource definitions, ASCII fixture data)
 * for downstream regression verification and parity testing.
 *
 * <p>The {@code @SpringBootApplication} annotation is a meta-annotation combining:
 * <ul>
 *   <li>{@code @SpringBootConfiguration} — marks this class as a configuration source</li>
 *   <li>{@code @EnableAutoConfiguration} — activates Spring Boot 3.2 auto-configuration
 *       for all starters declared in {@code pom.xml} (web, data-jpa, security, batch,
 *       validation, actuator)</li>
 *   <li>{@code @ComponentScan} — scans {@code com.carddemo} and all sub-packages for
 *       Spring-managed beans (controllers, services, repositories, configurations,
 *       batch jobs, security components)</li>
 * </ul>
 *
 * <p>Application profiles activate environment-specific configuration:
 * <ul>
 *   <li>{@code dev} — local PostgreSQL with verbose SQL logging (application-dev.yml)</li>
 *   <li>{@code prod} — production PostgreSQL via environment variables, structured
 *       JSON logging (application-prod.yml)</li>
 *   <li>{@code test} — Testcontainers PostgreSQL for integration tests (application-test.yml)</li>
 * </ul>
 *
 * <p>Activation methods (any of):
 * <pre>
 *   java -jar target/carddemo-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev
 *   SPRING_PROFILES_ACTIVE=prod java -jar target/carddemo-1.0.0-SNAPSHOT.jar
 *   mvn spring-boot:run -Dspring-boot.run.profiles=dev
 * </pre>
 *
 * <p>Migration scope:
 * <ul>
 *   <li>17 CICS online programs ({@code app/cbl/CO*.cbl}) → REST controllers + services</li>
 *   <li>11 batch COBOL programs ({@code app/cbl/CB*.cbl} + {@code CSUTLDTC.cbl}) →
 *       Spring Batch Job/Step beans</li>
 *   <li>29 JCL jobs ({@code app/jcl/*.jcl}) → Spring Batch Job beans (the critical
 *       sequence {@code POSTTRAN → INTCALC → COMBTRAN → CREASTMT} is preserved)</li>
 *   <li>VSAM KSDS files → JPA entities backed by PostgreSQL 15</li>
 *   <li>RACF/VSAM USRSEC security → Spring Security 6 + BCrypt password hashing</li>
 * </ul>
 *
 * @see org.springframework.boot.SpringApplication
 * @see org.springframework.boot.autoconfigure.SpringBootApplication
 */
@SpringBootApplication
public class CardDemoApplication {

    /**
     * Spring Boot application entry point.
     *
     * <p>Invokes {@link SpringApplication#run(Class, String...)} which performs:
     * <ol>
     *   <li>Creates the {@code ApplicationContext} (Spring container)</li>
     *   <li>Loads all {@code @Configuration} classes via component scanning</li>
     *   <li>Auto-configures starters declared in {@code pom.xml}</li>
     *   <li>Applies Flyway schema migrations from {@code classpath:db/migration}</li>
     *   <li>Initializes Spring Batch metadata tables (BATCH_*) via
     *       {@code spring.batch.jdbc.initialize-schema=always}</li>
     *   <li>Validates JPA entity mappings against the PostgreSQL schema
     *       (ddl-auto: validate)</li>
     *   <li>Starts embedded Tomcat on the configured port (default 8080)</li>
     * </ol>
     *
     * <p>Command-line arguments are passed through to Spring's environment.
     * Notable arguments:
     * <ul>
     *   <li>{@code --spring.profiles.active=dev|prod} — select active profile</li>
     *   <li>{@code --server.port=NNNN} — override HTTP port</li>
     * </ul>
     *
     * @param args command-line arguments forwarded to Spring's {@code Environment}
     */
    public static void main(String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
    }
}
