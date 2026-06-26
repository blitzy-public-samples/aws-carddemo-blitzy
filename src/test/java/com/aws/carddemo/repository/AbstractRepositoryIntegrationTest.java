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
package com.aws.carddemo.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Foundational abstract base class for every Spring Data JPA {@link DataJpaTest} <em>slice</em>
 * integration test in the CardDemo repository layer.
 *
 * <p>The legacy z/OS system persisted its data in VSAM KSDS / sequential files manipulated by the
 * batch posting archetype {@code app/cbl/CBTRN02C.cbl} (a six-file read / write / upsert program).
 * The migration replaces those files with eleven PostgreSQL tables accessed through Spring Data JPA
 * repositories. The repository slice tests that extend this class prove the resulting parity
 * guarantees locally, with no running mainframe (Agent Action Plan &sect;0.6.7):
 *
 * <ul>
 *   <li><b>Key / index parity</b> (AAP &sect;0.6.2) — primary keys, composite keys and the three
 *       non-unique alternate indexes are derived from the VSAM catalog and exercised against a real
 *       database engine;
 *   <li><b>FILE STATUS / upsert parity</b> (AAP &sect;0.6.4) — record-not-found ({@code '23'}) maps
 *       to {@link java.util.Optional#empty()} and the category-balance find-or-create behaviour is
 *       reproduced;
 *   <li><b>Decimal and fixed-width fidelity</b> (AAP &sect;0.6.1) — {@code numeric(p,s)} scale and
 *       {@code char(n)} blank-padding semantics, which an in-memory engine cannot faithfully
 *       reproduce.
 * </ul>
 *
 * <p>Because of those fidelity requirements the tests run against a real <b>Testcontainers
 * PostgreSQL 16</b> instance rather than an embedded H2 database. The wiring below is deliberate
 * and must be preserved to keep the guarantees intact:
 *
 * <ul>
 *   <li>{@link DataJpaTest} configures the JPA slice. It is {@code @Transactional}, so every test
 *       method rolls back, and it auto-configures Flyway, so {@code V1__schema.sql} (the eleven
 *       tables plus the three alternate indexes) and {@code V2__seed_reference_data.sql} (the four
 *       reference tables) are applied automatically on context startup.
 *   <li>{@link AutoConfigureTestDatabase} with {@link AutoConfigureTestDatabase.Replace#NONE}
 *       disables the default embedded-database replacement, so the slice binds to the real
 *       PostgreSQL data source instead of H2.
 *   <li>{@link ActiveProfiles @ActiveProfiles("test")} activates {@code application-test.yml},
 *       which sets Hibernate {@code ddl-auto=validate} (entity mappings are validated against the
 *       Flyway schema, failing fast on any drift), points Flyway at {@code classpath:db/migration},
 *       and disables auto-launch of Spring Batch jobs.
 * </ul>
 *
 * <p><b>Singleton container pattern.</b> {@link #POSTGRES} is started exactly once from a {@code
 * static} initializer and is intentionally never stopped — the Testcontainers Ryuk reaper removes
 * it at JVM exit. This is preferred over the JUnit-managed
 * {@code @Testcontainers}/{@code @Container} lifecycle: all subclasses share this one database, and
 * because they all carry identical slice configuration Spring caches and reuses a single {@code
 * ApplicationContext} across the whole suite (Flyway therefore runs only once). A JUnit-managed
 * static container, by contrast, would be stopped after each test class while the cached context
 * still referenced the now-dead container, causing "connection refused" failures on the next class.
 *
 * <p>{@link ServiceConnection} reads the running container and registers a {@code
 * JdbcConnectionDetails} bean that overrides {@code spring.datasource.*}, so no second container is
 * created and the profile's data-source settings are superseded. Spring detects the annotation on
 * this superclass field by traversing the test class hierarchy, and it only requires the container
 * to be running when connection details are read — guaranteed by the {@code static} start above.
 *
 * <p>The class is declared {@code abstract} and defines no {@code @Test} methods, so Surefire never
 * executes it directly; only the concrete {@code <Repo>IntegrationTest} subclasses run.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
abstract class AbstractRepositoryIntegrationTest {

  /**
   * Shared, JVM-singleton PostgreSQL 16 container reused by every repository slice test. Started
   * once from the {@code static} initializer and reaped at JVM exit; {@link ServiceConnection}
   * binds it to the Spring data source.
   */
  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  /** Slice-provided helper for persisting, flushing and re-reading entities under test. */
  @Autowired protected TestEntityManager entityManager;

  /**
   * Forces a database round-trip: flushes pending INSERT/UPDATE and clears the persistence context
   * so a subsequent {@code findById} re-reads from PostgreSQL. This is required to assert COBOL
   * decimal scale (BigDecimal scale == 2) and {@code char(n)} blank-padding parity (values read
   * back from a {@code char(n)} column are space-padded).
   */
  protected void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }
}
