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
package com.carddemo.reporting;

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
import com.carddemo.common.crypto.PiiEncryptionConfig;
import com.carddemo.common.config.WebHardeningConfig;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Executable entry point for the CardDemo reporting-service microservice.
 *
 * :purpose: Bootstraps the Spring Boot application context for online
 *     report-request handling (CICS CR00) and for the on-demand Spring
 *     Batch statement-generation job (text + HTML), wiring the shared
 *     carddemo-common entities, repositories, observability, and
 *     exception handling.
 * :output: A running stateless REST service exposing report endpoints
 *     plus a JobLauncher-driven statement-generation job.
 * :note: {@link BatchPathConfig} is imported from ``carddemo-common`` to supply
 *     the shared ``BatchOutputPathResolver``. The statement writer previously
 *     opened a path relative to the process working directory, which is inside
 *     the read-only container root, so every run failed with
 *     ``java.io.IOException: No such file or directory``; the
 *     resolver confines the statement files to the configured, writable
 *     ``carddemo.batch.output-dir`` and proves that root writable at startup.
 * :note: {@link JdbcBatchConfiguration} is imported so the statement job's
 *     executions are persisted in the ``BATCH_*`` metadata tables instead of the
 *     in-memory ``ResourcelessJobRepository`` that Spring Batch 6 makes the
 *     default.
 */
// UserDetailsServiceAutoConfiguration is excluded: this service authenticates from the
// shared CardDemo session, never from an in-memory user. Left enabled, Spring Boot
// generates a random development credential and logs it at WARN on every start, which
// both advertises a usable in-memory account and is an unrequested secret in the log.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EntityScan("com.carddemo.common.domain")
@EnableJpaRepositories("com.carddemo.reporting.repository")
@Import({ ObservabilityConfig.class, WebObservabilityConfig.class, GlobalExceptionHandler.class,
        CardDemoErrorController.class, ContainerErrorReportConfig.class, PersistenceExceptionHandler.class,
        SessionRedisConfig.class, RedisCommandMetricsConfig.class, SchemaMigrationConfig.class,
        BatchPathConfig.class, JdbcBatchConfiguration.class,
        WebHardeningConfig.class, PiiEncryptionConfig.class })
public class ReportingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportingServiceApplication.class, args);
    }
}
