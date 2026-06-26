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
package com.aws.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Pure unit tests for {@link DataSourceConfig}, the persistence-layer configuration anchor of the
 * migrated AWS CardDemo application.
 *
 * <p>These tests are the regression guard for the credential-hygiene requirement of the migration
 * (Agent Action Plan §0.7.2 — "no hardcoded credentials … secrets externalized" — and §0.7.3 — the
 * OWASP dependency-check and zero-warning quality gates). They fail fast if anyone ever
 * reintroduces an in-code {@code DataSource} bean or configuration fields that could hold embedded
 * connection secrets (a JDBC URL, username, or password). In the legacy z/OS system the
 * corresponding VSAM files were declared in the CICS resource registry rather than in program
 * source; the modern equivalent keeps every connection detail out of Java source and in
 * externalized {@code application.yml}/environment configuration instead.
 *
 * <p>This is intentionally a <strong>pure</strong> unit test: it neither bootstraps a Spring
 * application context ({@code @SpringBootTest}) nor starts a Testcontainers/Docker PostgreSQL
 * instance. It verifies the production class purely through reflection and annotation
 * introspection, which is sufficient because {@link Configuration}, {@link
 * EnableTransactionManagement}, and {@link Bean} all carry {@code RUNTIME} retention and are
 * therefore visible to {@code isAnnotationPresent} and {@code getDeclaredMethods}. Keeping the test
 * context-free makes it fast, deterministic, and free of any external infrastructure dependency.
 *
 * <p>The two security-relevant guarantees asserted here are deliberately scoped narrowly: (a) the
 * class declares no {@code DataSource}-returning {@code @Bean} method (so the datasource is always
 * built by Spring Boot auto-configuration from externalized properties), and (b) the class declares
 * no non-synthetic fields (so it cannot carry hardcoded credential literals). The test does
 * <em>not</em> forbid {@code @Bean} methods in general — only a {@code DataSource}-returning one —
 * so a future non-credential bean would not make these assertions brittle.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DataSourceConfigTest {

  /**
   * The class must be a Spring {@link Configuration} so that it participates in component scanning
   * as the application's single, centralized home for any future datasource, JPA, or transaction
   * customization.
   */
  @Test
  void is_annotated_as_a_spring_configuration() {
    assertThat(DataSourceConfig.class.isAnnotationPresent(Configuration.class)).isTrue();
  }

  /**
   * Declarative transaction management must be enabled so the layered service architecture (web
   * &rarr; service &rarr; repository) can demarcate every unit of work at the service boundary,
   * preserving the commit/rollback semantics of the legacy COBOL VSAM read-modify-write posting
   * flows (e.g. {@code legacy/app/cbl/CBTRN02C.cbl}).
   */
  @Test
  void enables_declarative_transaction_management() {
    assertThat(DataSourceConfig.class.isAnnotationPresent(EnableTransactionManagement.class))
        .as("@EnableTransactionManagement preserves COBOL VSAM commit/rollback parity")
        .isTrue();
  }

  /**
   * §0.7.2: the {@link DataSource} must be auto-configured from {@code application.yml}/environment
   * variables, never hand-built in code with embedded connection details. This iterates every
   * declared method and asserts that none is a {@link Bean} factory whose return type is assignable
   * to {@link DataSource}.
   */
  @Test
  void declares_no_datasource_bean_so_connection_details_are_externalized() {
    // §0.7.2: the DataSource must be auto-configured from application.yml/env vars,
    // never hand-built in code with embedded connection details.
    for (Method method : DataSourceConfig.class.getDeclaredMethods()) {
      boolean buildsDataSource =
          method.isAnnotationPresent(Bean.class)
              && DataSource.class.isAssignableFrom(method.getReturnType());
      assertThat(buildsDataSource)
          .as("DataSourceConfig must not declare a DataSource @Bean: %s", method)
          .isFalse();
    }
  }

  /**
   * A configuration holding no (non-synthetic) fields cannot carry hardcoded url/username/password
   * literals. The {@code isSynthetic()} filter is required because JaCoCo instrumentation (used for
   * the ≥80% coverage gate) injects a synthetic {@code $jacocoData} field at runtime; without the
   * filter this assertion would spuriously fail under coverage runs.
   */
  @Test
  void declares_no_fields_so_no_hardcoded_credentials_exist() {
    // A config holding no (non-synthetic) fields cannot carry hardcoded
    // url/username/password literals. Synthetic fields (e.g. JaCoCo $jacocoData) are ignored.
    long nonSyntheticFields =
        Arrays.stream(DataSourceConfig.class.getDeclaredFields())
            .filter(field -> !field.isSynthetic())
            .count();
    assertThat(nonSyntheticFields).isZero();
  }
}
