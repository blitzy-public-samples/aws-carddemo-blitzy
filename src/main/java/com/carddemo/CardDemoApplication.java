package com.carddemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot bootstrap entry point for the AWS CardDemo modernization.
 *
 * <p>This is the single executable {@code main} class of the layered Spring Boot 3.2.x
 * monolith (Java 17 LTS) that re-implements the legacy COBOL / CICS / VSAM AWS CardDemo
 * sample application with 100% functional parity. It is the <em>only</em> Java type that
 * lives directly in the base package {@code com.carddemo}; every other component resides in
 * one of the eleven sub-packages: {@code config}, {@code security}, {@code controller},
 * {@code service}, {@code repository}, {@code entity}, {@code dto}, {@code mapper},
 * {@code batch}, {@code exception}, and {@code util}.</p>
 *
 * <h2>Component scanning</h2>
 * <p>Because this class is annotated with {@link SpringBootApplication} and is declared in
 * the base package {@code com.carddemo}, the default component scan, entity scan, and
 * Spring Data repository scan all root themselves here and therefore auto-discover every
 * component in the eleven sub-packages with no further configuration. No narrowing
 * {@code @ComponentScan}, {@code @EntityScan}, or {@code @EnableJpaRepositories} annotation
 * is declared, since relocating or narrowing the base package would risk missing packages.
 * Keeping the bootstrap class minimal (annotation + {@code main} only) preserves the strict
 * layered architecture: all {@code @Bean} definitions, security configuration, datasource
 * configuration, and batch job definitions live in {@code config}, {@code security}, and
 * {@code batch} respectively, never here.</p>
 *
 * <h2>Spring Batch enablement decision (Spring Boot 3.2 / Spring Batch 5)</h2>
 * <p>The application defines five Spring Batch jobs (transaction posting, interest
 * calculation, statement creation, account refresh, and customer refresh) that replace the
 * legacy JCL-scheduled COBOL batch programs. This class intentionally relies on Spring
 * Boot's batch <strong>auto-configuration</strong> and deliberately does <strong>not</strong>
 * declare {@code @EnableBatchProcessing}.</p>
 *
 * <p>Rationale: under Spring Boot 3.2 / Spring Batch 5, {@code BatchAutoConfiguration} is
 * gated by {@code @ConditionalOnMissingBean(annotation = EnableBatchProcessing.class)}.
 * Adding {@code @EnableBatchProcessing} would cause that auto-configuration to back off,
 * which in turn would disable the two batch settings that the sibling
 * {@code src/main/resources/application.yml} depends on:</p>
 * <ul>
 *   <li>{@code spring.batch.job.enabled=false} &mdash; suppresses the
 *       {@code JobLauncherApplicationRunner} so that <strong>no</strong> job runs
 *       automatically at startup (jobs are launched on demand via {@code JobLauncher});
 *       and</li>
 *   <li>{@code spring.batch.jdbc.initialize-schema=always} &mdash; creates the
 *       {@code BATCH_*} metadata tables ({@code BATCH_JOB_INSTANCE},
 *       {@code BATCH_JOB_EXECUTION}, {@code BATCH_STEP_EXECUTION}, etc.) that the
 *       {@code JobRepository} requires before any job can be launched.</li>
 * </ul>
 *
 * <p>By keeping {@code @EnableBatchProcessing} off, Spring Boot auto-provides the
 * {@code JobRepository}, {@code JobLauncher}, and {@code JobExplorer} beans (backed by the
 * application {@code DataSource} and transaction manager), honors
 * {@code spring.batch.job.enabled=false} so nothing auto-runs, and honors
 * {@code spring.batch.jdbc.initialize-schema=always} so the metadata schema exists for
 * on-demand launches. The Agent Action Plan's intent &mdash; "enable Spring Batch" &mdash;
 * is fully satisfied by the {@code spring-boot-starter-batch} dependency plus this
 * auto-configuration.</p>
 *
 * <h2>Profiles, schema migration, and datasource awareness</h2>
 * <p>This class is profile-agnostic and hardcodes neither a datasource nor an active
 * profile. The active profile (default {@code dev} &rarr; H2 in-memory in
 * {@code MODE=PostgreSQL}; {@code prod} &rarr; PostgreSQL 15.x) is selected via
 * {@code application*.yml} and the {@code SPRING_PROFILES_ACTIVE} environment variable. On
 * startup, Flyway applies migrations {@code V1__schema.sql} through {@code V4__seed_users.sql}
 * before Hibernate runs with {@code spring.jpa.hibernate.ddl-auto=validate}, so Hibernate
 * validates (never creates) against the Flyway-managed schema.</p>
 *
 * @see SpringBootApplication
 * @see SpringApplication
 */
@SpringBootApplication
public class CardDemoApplication {

    /**
     * Launches the CardDemo Spring Boot application.
     *
     * <p>Delegates to {@link SpringApplication#run(Class, String...)}, which creates and
     * refreshes the {@code ApplicationContext}, drives auto-configuration (web, JPA,
     * security, batch, Flyway, actuator), and starts the embedded servlet container. No
     * Spring Batch job is executed here; jobs run on demand via {@code JobLauncher}.</p>
     *
     * @param args the command-line arguments forwarded to {@link SpringApplication}
     */
    public static void main(String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
    }
}
