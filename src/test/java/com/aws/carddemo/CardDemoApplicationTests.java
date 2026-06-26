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

import com.aws.carddemo.repository.UserSecurityRepository;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Root context-load (integration smoke) test for the modernized AWS CardDemo application.
 *
 * <p>This is the single, foundational {@link SpringBootTest} of the test suite. It boots the
 * <em>full</em> production {@link CardDemoApplication} context against a real <b>Testcontainers
 * PostgreSQL&nbsp;16</b> instance and asserts that every cross-cutting subsystem wires up
 * correctly. It is the smoke test that proves the modernized Java&nbsp;25 / Spring&nbsp;Boot&nbsp;
 * 3.5.x stack is internally consistent before any behavioral-parity test runs. Unlike the rest of
 * the suite it has <b>no 1:1 COBOL ancestor</b>: it verifies the Spring Boot infrastructure that
 * <em>replaces</em> the entire legacy CICS / JCL / VSAM runtime, so it is written from scratch
 * (Agent Action Plan &sect;0.6.7 — local-only validation with JUnit&nbsp;5 + Testcontainers
 * PostgreSQL; no running COBOL / mainframe is required).
 *
 * <h2>What a successful context start proves</h2>
 *
 * <p>The deceptively simple {@link #contextLoads()} method is a powerful end-to-end assertion.
 * Because the {@code test} profile ({@code application-test.yml}) sets Hibernate {@code
 * ddl-auto=validate} over a Flyway-owned schema, the context only starts when the entire entity /
 * schema / migration / configuration graph is mutually consistent. Concretely, a clean start
 * demonstrates that:
 *
 * <ul>
 *   <li>the Flyway migrations {@code V1__schema.sql} (the eleven VSAM-faithful tables and the three
 *       alternate indexes) and {@code V2__seed_reference_data.sql} (the four reference tables)
 *       applied successfully against a fresh database;
 *   <li>Hibernate {@code ddl-auto=validate} passed — every JPA entity mapping matches the
 *       Flyway-created schema, so no entity has drifted from its table (a drift would fail this
 *       test, which is the intended guardrail);
 *   <li>the Spring Batch metadata tables were initialized ({@code
 *       spring.batch.jdbc.initialize-schema=always}) and the auto-configured {@code JobRepository}
 *       is present — proving no {@code @EnableBatchProcessing} regression disabled Boot's batch
 *       auto-configuration;
 *   <li>Spring Security and every {@code @Configuration} bean loaded.
 * </ul>
 *
 * <p>The additional focused assertions ({@link #crossCuttingBeansAreWired()}, {@link
 * #securitySeederCreatesSeedUsers()}, {@link #flywayReferenceSeedLoaded()}) make that implicit
 * checklist explicit and reviewable.
 *
 * <h2>Testcontainers wiring</h2>
 *
 * <p>The class owns a single {@link PostgreSQLContainer} declared {@code static final} and managed
 * by the JUnit&nbsp;5 {@link Testcontainers} extension through {@link Container} (started once
 * before the class and stopped after it). {@link ServiceConnection} (Spring&nbsp;Boot&nbsp;3.1+)
 * registers a {@code JdbcConnectionDetails} bean from the running container that <b>overrides</b>
 * {@code spring.datasource.*}, so the production context connects to <em>this</em> container
 * regardless of any URL declared in {@code application-test.yml}. This is the modern,
 * least-error-prone way to bind a Testcontainers database to the context: it removes all
 * {@code @DynamicPropertySource} datasource plumbing, requires no hardcoded JDBC URL or
 * credentials, and avoids the double-container pitfall of a {@code jdbc:tc:} URL. The test is
 * intentionally <b>self-contained</b> — it introduces no shared base class and no {@code
 * Testcontainers Configuration}; downstream subfolder integration tests manage their own
 * Testcontainers needs.
 *
 * <p>This class deliberately adds none of {@code @EnableBatchProcessing},
 * {@code @EnableJpaRepositories}, {@code @EntityScan}, or {@code @EnableWebSecurity}: the
 * production {@code CardDemoApplication} omits them so that Spring Boot's component scan and
 * auto-configuration wire every layer, and mirroring that here keeps the smoke test faithful to
 * production.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CardDemoApplicationTests {

  /**
   * Ephemeral PostgreSQL&nbsp;16 database for this test class. Declared {@code static final} so a
   * single container is shared by every method, managed by the {@link Testcontainers} extension and
   * bound to Spring's data source by {@link ServiceConnection} (which supplies the JDBC URL,
   * username and password, superseding {@code spring.datasource.*}). The {@code postgres:16-alpine}
   * image satisfies the PostgreSQL&nbsp;16+ requirement (Agent Action Plan &sect;0.5.1) and matches
   * the docker-compose / test-profile database.
   */
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  /** Application data source, auto-configured against the Testcontainers PostgreSQL container. */
  @Autowired private DataSource dataSource;

  /** BCrypt password encoder declared by {@code SecurityConfig} (replaces clear-text passwords). */
  @Autowired private PasswordEncoder passwordEncoder;

  /** Sign-on credential / role lookup declared by {@code SecurityConfig}. */
  @Autowired private UserDetailsService userDetailsService;

  /** Spring Security HTTP filter chain declared by {@code SecurityConfig}. */
  @Autowired private SecurityFilterChain securityFilterChain;

  /** Spring Batch metadata repository, auto-configured by Spring Boot (no manual enablement). */
  @Autowired private JobRepository jobRepository;

  /** Repository backing the {@code user_security} table; used to verify the credential seeder. */
  @Autowired private UserSecurityRepository userSecurityRepository;

  /** Lightweight JDBC access used for the single read-only reference-seed sanity check. */
  @Autowired private JdbcTemplate jdbcTemplate;

  /**
   * The canonical context-load test. Its body is intentionally empty: if the full application
   * context cannot start — for example because Hibernate {@code ddl-auto=validate} finds an entity
   * that no longer matches the Flyway schema, a required bean is missing, Spring Security is
   * misconfigured, or a Flyway migration fails — this method fails. A passing run therefore proves
   * that Flyway {@code V1}+{@code V2} applied, schema validation succeeded, the Spring Batch
   * metadata tables were created, and Spring Security plus all configuration beans loaded.
   */
  @Test
  void contextLoads() {
    // Intentionally empty: a successful context start is the assertion (see class Javadoc).
  }

  /**
   * Asserts that the cross-cutting beans called out by the folder requirement are present in the
   * running context. This makes the implicit guarantees of {@link #contextLoads()} explicit:
   *
   * <ul>
   *   <li>the {@link DataSource} is wired to the Testcontainers database;
   *   <li>the {@link PasswordEncoder} is the {@link BCryptPasswordEncoder} from {@code
   *       SecurityConfig};
   *   <li>the {@link UserDetailsService} (sign-on lookup) and the {@link SecurityFilterChain}
   *       (Spring Security loaded) are present;
   *   <li>the auto-configured {@link JobRepository} is present, proving Spring Batch
   *       auto-configuration remained active.
   * </ul>
   */
  @Test
  void crossCuttingBeansAreWired() {
    assertThat(dataSource).isNotNull();
    assertThat(passwordEncoder).isNotNull().isInstanceOf(BCryptPasswordEncoder.class);
    assertThat(userDetailsService).isNotNull();
    assertThat(securityFilterChain).isNotNull();
    assertThat(jobRepository).isNotNull();
  }

  /**
   * Verifies that the bootstrap credential seeder ran under the {@code test} profile. {@code
   * SecuritySeeder} (a {@code CommandLineRunner}) inserts the two default identities at startup
   * using the externalized, BCrypt-hashed seed passwords from {@code application-test.yml}. The
   * assertions confirm existence by primary key only — no plaintext or hashed password value is
   * ever read, logged, or compared (Agent Action Plan &sect;0.6.6, &sect;0.7.2).
   */
  @Test
  void securitySeederCreatesSeedUsers() {
    assertThat(userSecurityRepository.existsById("ADMIN001")).isTrue();
    assertThat(userSecurityRepository.existsById("USER0001")).isTrue();
  }

  /**
   * Sanity-checks that the Flyway reference-data migration {@code V2__seed_reference_data.sql}
   * loaded. That migration seeds exactly seven transaction-type codes ({@code '01'}–{@code '07'})
   * into {@code tran_type}, so the row count must be seven. This is the only data touch in the
   * smoke test and depends on no {@code src/test/resources} fixtures, keeping it deterministic.
   */
  @Test
  void flywayReferenceSeedLoaded() {
    assertThat(jdbcTemplate.queryForObject("select count(*) from tran_type", Integer.class))
        .isEqualTo(7);
  }
}
