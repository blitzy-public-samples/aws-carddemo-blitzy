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
package com.carddemo.common.crypto;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * :purpose: Register the {@link SeededPiiEncryptionMigrator} for a service that reads
 *     the regulated columns protected by {@link CryptoConverter}, so those columns are
 *     encrypted at rest before the service serves its first request. Import it from
 *     every service whose domain reads ``customers`` or ``cards``.
 * :output: One {@code SeededPiiEncryptionMigrator} bean that runs during context
 *     initialization.
 * :note: Deliberately opt-in per service and switchable with
 *     ``carddemo.pii.encrypt-existing-rows=false`` for a deployment whose data at rest
 *     is normalized by another controlled process.
 */
@Configuration
@ConditionalOnProperty(name = "carddemo.pii.encrypt-existing-rows", matchIfMissing = true)
public class PiiEncryptionConfig {

    /**
     * :purpose: Build the migrator once the persistence layer is ready.
     * :param jdbcTemplates: data access used to read and rewrite the protected columns.
     * :param transactionManagers: supplies the transaction the rewrite runs in.
     * :param entityManagerFactories: not used for data access - resolving it here forces
     *     the persistence unit to be created first, and Spring Boot makes the persistence
     *     unit depend on the Flyway initializer, so the schema and its seed rows exist
     *     before the pass runs.
     * :returns: the configured migrator, or a disabled one in a context that has no
     *     datasource (for example a web-layer test slice).
     */
    @Bean
    public SeededPiiEncryptionMigrator seededPiiEncryptionMigrator(
            ObjectProvider<JdbcTemplate> jdbcTemplates,
            ObjectProvider<PlatformTransactionManager> transactionManagers,
            ObjectProvider<EntityManagerFactory> entityManagerFactories) {
        JdbcTemplate jdbcTemplate = jdbcTemplates.getIfAvailable();
        PlatformTransactionManager transactionManager = transactionManagers.getIfAvailable();
        entityManagerFactories.getIfAvailable();
        if (jdbcTemplate == null || transactionManager == null) {
            return SeededPiiEncryptionMigrator.disabled();
        }
        return new SeededPiiEncryptionMigrator(jdbcTemplate, new TransactionTemplate(transactionManager));
    }
}
