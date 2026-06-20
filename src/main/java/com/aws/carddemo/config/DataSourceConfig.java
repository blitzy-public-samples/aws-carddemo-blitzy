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

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Persistence-layer configuration anchor for the migrated AWS CardDemo application.
 *
 * <p>In the legacy z/OS system, CardDemo persisted to VSAM KSDS and sequential files. The CICS
 * resource registry {@code legacy/app/csd/CARDDEMO.CSD} enumerates those VSAM file definitions —
 * {@code ACCTDAT}, {@code CARDDAT}, {@code CCXREF}, {@code CUSTDAT}, {@code TRANSACT}, {@code
 * USRSEC}, and the alternate-index paths — and is the conceptual <em>source</em> for the datasource
 * wiring described here. The migration replaces every one of those VSAM files with a PostgreSQL
 * table reached through Spring Data JPA (Agent Action Plan §0.4.1, §0.6.2).
 *
 * <p>This class is deliberately the single, centralized home for any future datasource, JPA, or
 * transaction customization, yet it intentionally declares <strong>no</strong> {@code DataSource}
 * bean and <strong>no</strong> connection details. Each of the following is a hard project
 * constraint rather than a stylistic choice:
 *
 * <ol>
 *   <li><b>The datasource is auto-configured.</b> Spring Boot builds the {@code DataSource} from
 *       the {@code spring.datasource.*} properties declared in {@code
 *       src/main/resources/application.yml}. Declaring a hand-rolled {@code DataSource} bean here
 *       would override — and silently diverge from — that externalized, environment-driven
 *       configuration, so it is intentionally omitted.
 *   <li><b>No hardcoded secrets.</b> The JDBC URL, username, and password are supplied exclusively
 *       through the {@code SPRING_DATASOURCE_URL}, {@code SPRING_DATASOURCE_USERNAME}, and {@code
 *       SPRING_DATASOURCE_PASSWORD} environment variables (the only committed defaults are the
 *       local-development docker-compose values) and are <em>never</em> embedded in Java source.
 *       Keeping connection details out of this class is what upholds the credential-hygiene and
 *       OWASP dependency-check gates (Agent Action Plan §0.7.2, §0.7.3).
 *   <li><b>Flyway owns the schema.</b> The database structure — the eleven business tables plus the
 *       three VSAM-derived alternate indexes — is created and versioned exclusively by the Flyway
 *       migrations under {@code src/main/resources/db/migration/V*.sql}. The companion {@code
 *       spring.jpa.hibernate.ddl-auto=validate} setting only verifies that the JPA entity mappings
 *       match the Flyway-created tables; Hibernate never creates, alters, or drops DDL.
 * </ol>
 *
 * <p>The class carries {@link EnableTransactionManagement} to make the application's reliance on
 * declarative {@code @Transactional} boundaries explicit. The layered service architecture (web
 * &rarr; service &rarr; repository) demarcates every unit of work — for example the
 * read-modify-write account and category-balance posting flows migrated from the batch COBOL
 * programs — at the service boundary, where Spring's transaction interceptor governs commit and
 * rollback. Enabling transaction management with its defaults exactly matches the behavior Spring
 * Boot already auto-configures, so this annotation documents intent without altering runtime
 * behavior and produces no additional beans.
 *
 * <p>Component, entity, and repository scanning are intentionally <em>not</em> declared here: the
 * {@code @SpringBootApplication} annotation on {@code CardDemoApplication} already scans the entire
 * {@code com.aws.carddemo} package tree, so {@code @EnableJpaRepositories} and {@code @EntityScan}
 * would be redundant and are deliberately omitted.
 */
@Configuration
@EnableTransactionManagement
public class DataSourceConfig {}
