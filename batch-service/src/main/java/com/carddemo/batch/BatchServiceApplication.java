/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.batch;

import com.carddemo.common.config.RedisCommandMetricsConfig;
import com.carddemo.common.config.SessionRedisConfig;
import com.carddemo.common.config.CardDemoErrorController;
import com.carddemo.common.config.ContainerErrorReportConfig;
import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.batch.BatchPathConfig;
import com.carddemo.common.batch.JdbcBatchConfiguration;
import com.carddemo.common.config.ObservabilityConfig;
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
 * Spring Boot entry point for the ``batch-service`` microservice.
 *
 * :purpose: Bootstraps the CardDemo batch tier (interest calculation and
 *     data-management jobs) as a resident Spring Boot application that also
 *     exposes Actuator HTTP endpoints for health and metrics.
 * :output: A running application context with Spring Batch auto-configuration,
 *     JPA repositories under ``com.carddemo.batch.repository`` and shared
 *     entities under ``com.carddemo.common.domain``.
 * :note: {@link BatchPathConfig} is imported from ``carddemo-common`` to supply
 *     the shared ``BatchOutputPathResolver``, which confines every file a job
 *     opens to the allowlisted ``carddemo.batch`` roots and proves the output
 *     root writable while the context refreshes.
 * :note: {@link JdbcBatchConfiguration} is imported so job and step executions are
 *     persisted in the ``BATCH_*`` metadata tables instead of the in-memory
 *     ``ResourcelessJobRepository`` that Spring Batch 6 makes the default; without
 *     it the job-execution status endpoint had nothing to read and no job history
 *     existed at all.
 * :note: {@link GlobalExceptionHandler} is imported so the on-demand job-launch
 *     endpoints of ``BatchController`` answer with the same error envelope as
 *     every other CardDemo service (unknown job to HTTP 404, missing job
 *     parameter or rejected submission to HTTP 400) instead of a bare Boot
 *     ``/error`` body.
 * :note: {@link CardDemoErrorController} and {@link ContainerErrorReportConfig} cover the
 *     failures ``GlobalExceptionHandler`` cannot reach — a container-level error dispatch
 *     such as a request that matched no handler, or a refusal decided before the dispatcher
 *     servlet — so those answer in the same envelope here as in every other service instead
 *     of Boot's abbreviated ``{timestamp,status,error,path}`` document.
 */
// UserDetailsServiceAutoConfiguration is excluded: this service authenticates from the
// shared CardDemo session, never from an in-memory user. Left enabled, Spring Boot
// generates a random development credential and logs it at WARN on every start, which
// both advertises a usable in-memory account and is an unrequested secret in the log.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EntityScan("com.carddemo.common.domain")
@EnableJpaRepositories("com.carddemo.batch.repository")
@Import({ ObservabilityConfig.class, SchemaMigrationConfig.class, WebObservabilityConfig.class,
        GlobalExceptionHandler.class, CardDemoErrorController.class,
        ContainerErrorReportConfig.class, SessionRedisConfig.class, RedisCommandMetricsConfig.class,
        BatchPathConfig.class, JdbcBatchConfiguration.class, WebHardeningConfig.class,
        PiiEncryptionConfig.class })
public class BatchServiceApplication {

    /**
     * Application entry point.
     *
     * :param args: standard Java command-line arguments forwarded to
     *     :class:`SpringApplication` to bootstrap the Spring context.
     */
    public static void main(String[] args) {
        SpringApplication.run(BatchServiceApplication.class, args);
    }
}
