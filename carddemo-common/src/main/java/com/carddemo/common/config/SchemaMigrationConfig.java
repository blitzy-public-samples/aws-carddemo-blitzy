/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.common.config;

import com.carddemo.common.migration.SeededPiiEncryptionMigration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * :purpose: Contribute the code-based members of the shared CardDemo migration set that
 *     lives beside the SQL scripts in ``carddemo-common`` (``classpath:db/migration``).
 *     Spring Boot's Flyway auto-configuration collects every ``JavaMigration`` bean and
 *     hands it to Flyway, so declaring the bean here is what places the seeded-PII
 *     encryption step (version 4) between the ``V3`` fixtures and the ``V5`` batch
 *     metadata.
 * :note: The shared library ships no ``META-INF`` auto-configuration import file, so a
 *     service activates this configuration explicitly with
 *     ``@Import(SchemaMigrationConfig.class)`` — exactly like {@link ObservabilityConfig}.
 *     The migration owner needs it to provision a database; the other services need it so
 *     their Testcontainers integration tests provision the identical production schema.
 * :note: Gated on Flyway being present so a module without the migration library on its
 *     classpath can still import the configuration harmlessly.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.flywaydb.core.api.migration.JavaMigration")
public class SchemaMigrationConfig {

    /**
     * :purpose: Register the seeded-PII encryption migration with Flyway.
     * :returns: the version-4 Java migration that rewrites the seeded customer and card
     *     PII columns as AES-256-GCM tokens.
     */
    @Bean
    SeededPiiEncryptionMigration seededPiiEncryptionMigration() {
        return new SeededPiiEncryptionMigration();
    }
}
