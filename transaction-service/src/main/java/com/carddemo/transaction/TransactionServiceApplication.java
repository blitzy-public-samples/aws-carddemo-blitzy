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
package com.carddemo.transaction;

import com.carddemo.common.config.CardDemoErrorController;
import com.carddemo.common.config.ContainerErrorReportConfig;
import com.carddemo.common.batch.BatchPathConfig;
import com.carddemo.common.batch.JdbcBatchConfiguration;
import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.config.ObservabilityConfig;
import com.carddemo.common.config.PersistenceExceptionHandler;
import com.carddemo.common.config.RedisCommandMetricsConfig;
import com.carddemo.common.config.SessionRedisConfig;
import com.carddemo.common.config.WebObservabilityConfig;
import com.carddemo.common.config.SchemaMigrationConfig;
import com.carddemo.common.config.WebHardeningConfig;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Executable entry point for the CardDemo transaction-service microservice.
 *
 * :purpose: Bootstraps the Spring Boot application context for online
 *     transaction list (CICS CT00), view (CICS CT01), and add (CICS CT02)
 *     operations, and for the on-demand Spring Batch transaction-posting
 *     job, wiring shared carddemo-common entities, observability, and
 *     exception handling.
 * :output: A running stateless REST service exposing transaction endpoints
 *     plus a JobLauncher-driven posting job.
 * :note: {@link BatchPathConfig} is imported from ``carddemo-common`` to supply the
 *     shared ``BatchOutputPathResolver``, which confines the ``DALYREJS`` reject
 *     file the posting job writes to the configured, writable
 *     ``carddemo.batch.output-dir`` root and proves that root writable while the
 *     context refreshes. The reject writer previously opened a path relative to the
 *     process working directory, which is on the read-only container root
 *     filesystem (QA Issue 21).
 * :note: {@link JdbcBatchConfiguration} is imported so the posting job's executions
 *     are persisted in the ``BATCH_*`` metadata tables instead of the in-memory
 *     ``ResourcelessJobRepository`` that Spring Batch 6 makes the default.
 */
// UserDetailsServiceAutoConfiguration is excluded: this service authenticates from the
// shared CardDemo session, never from an in-memory user. Left enabled, Spring Boot
// generates a random development credential and logs it at WARN on every start, which
// both advertises a usable in-memory account and is an unrequested secret in the log.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EntityScan("com.carddemo.common.domain")
@EnableJpaRepositories("com.carddemo.transaction.repository")
@Import({ ObservabilityConfig.class, GlobalExceptionHandler.class, CardDemoErrorController.class,
        ContainerErrorReportConfig.class, PersistenceExceptionHandler.class, SessionRedisConfig.class,
        RedisCommandMetricsConfig.class, SchemaMigrationConfig.class, WebObservabilityConfig.class,
        BatchPathConfig.class, JdbcBatchConfiguration.class,
        WebHardeningConfig.class })
public class TransactionServiceApplication {

    /**
     * Application entry point.
     *
     * :param args: standard Java command-line arguments forwarded to
     *     :class:`SpringApplication` to bootstrap the Spring context.
     */
    public static void main(String[] args) {
        SpringApplication.run(TransactionServiceApplication.class, args);
    }
}
