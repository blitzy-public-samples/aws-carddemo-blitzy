package com.aws.carddemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the migrated AWS CardDemo credit-card management application.
 *
 * <p>AWS CardDemo was originally an IBM z/OS COBOL/CICS/VSAM/JCL/BMS system; this class is the
 * {@code public static void main} bootstrap that launches its Java 25 + Spring Boot replacement.
 * It carries no business logic and holds no state &mdash; its sole responsibility is to start the
 * Spring application context.</p>
 *
 * <p>Traceability: every domain, service, repository, web and batch class produced by this
 * migration cites its originating {@code legacy/**} COBOL source path so that the COBOL-construct
 * to Java-artifact mapping reaches 100% bidirectional coverage. This launcher is the one exception:
 * it is a net-new bootstrap class with no legacy COBOL counterpart and is therefore
 * infrastructure-only. Migration rationale is recorded in {@code docs/decision-log.md}, not in
 * code comments.</p>
 *
 * <p>Because this type lives at the base package {@code com.aws.carddemo}, the
 * {@link SpringBootApplication} component scan discovers every sub-package and wires the layered
 * architecture: web &rarr; service &rarr; repository &rarr; PostgreSQL, the Spring Batch jobs, and
 * the cross-cutting security, observability and exception-handling concerns.</p>
 */
@SpringBootApplication
public class CardDemoApplication {

    /**
     * Launches the Spring application context for AWS CardDemo.
     *
     * @param args command-line arguments forwarded to
     *             {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
    }
}
