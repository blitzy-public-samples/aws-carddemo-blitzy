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
package com.carddemo.user;

import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.config.ObservabilityConfig;
import com.carddemo.common.config.SessionRedisConfig;
import com.carddemo.common.config.WebHardeningConfig;
import com.carddemo.common.config.WebObservabilityConfig;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.carddemo.common.config.CardDemoErrorController;
import com.carddemo.common.config.ContainerErrorReportConfig;
import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.config.SecurityExceptionHandler;
import com.carddemo.common.config.ObservabilityConfig;
import com.carddemo.common.config.PersistenceExceptionHandler;
import com.carddemo.common.config.RedisCommandMetricsConfig;
import com.carddemo.common.config.SessionRedisConfig;
import com.carddemo.common.config.WebObservabilityConfig;
import com.carddemo.common.config.SchemaMigrationConfig;
/**
 * Executable entry point for the CardDemo User Management microservice.
 *
 * :purpose: Bootstraps the Spring application context for the ``user-service``
 *     tier, registers the shared ``com.carddemo.common.domain`` JPA entities,
 *     enables the ``com.carddemo.user.repository`` Spring Data repositories, and
 *     imports the shared observability and global-exception-handling
 *     configuration from ``carddemo-common``.
 * :output: A running Spring Boot application context exposing the role-gated
 *     user-management REST endpoints together with Actuator health and metrics
 *     endpoints.
 */
// UserDetailsServiceAutoConfiguration is excluded: user-service verifies no
// credential (auth-service does) and its principal is rebuilt from the shared
// session, so Boot's fallback in-memory user is never used - and its generated
// password must never be printed to the log.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EntityScan("com.carddemo.common.domain")
@EnableJpaRepositories("com.carddemo.user.repository")
@Import({ ObservabilityConfig.class, WebObservabilityConfig.class, GlobalExceptionHandler.class,
        CardDemoErrorController.class, SecurityExceptionHandler.class, ContainerErrorReportConfig.class,
        PersistenceExceptionHandler.class, SessionRedisConfig.class, RedisCommandMetricsConfig.class,
        SchemaMigrationConfig.class, WebHardeningConfig.class })
public class UserServiceApplication {

    /**
     * Application entry point.
     *
     * :param args: command-line arguments forwarded to
     *     :class:`SpringApplication` to bootstrap the Spring context.
     */
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
