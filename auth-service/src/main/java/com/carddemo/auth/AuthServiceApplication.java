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
package com.carddemo.auth;

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
import com.carddemo.common.config.WebHardeningConfig;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Spring Boot entry point for the ``auth-service`` microservice.
 *
 * :purpose: Bootstrap the CardDemo Authentication microservice (re-platforms
 *     COBOL ``COSGN00C`` / CICS transaction ``CC00``); wires the shared
 *     carddemo-common entities and configuration, this service's JPA
 *     repositories, observability, and centralized exception handling.
 * :output: A running application context that registers the
 *     ``com.carddemo.common.domain`` entities and ``com.carddemo.auth.repository``
 *     repositories and exposes the Actuator health and Prometheus endpoints.
 */
@SpringBootApplication
@EntityScan("com.carddemo.common.domain")
@EnableJpaRepositories("com.carddemo.auth.repository")
@Import({ ObservabilityConfig.class, WebObservabilityConfig.class, GlobalExceptionHandler.class,
        CardDemoErrorController.class, SecurityExceptionHandler.class, ContainerErrorReportConfig.class,
        PersistenceExceptionHandler.class, SessionRedisConfig.class, RedisCommandMetricsConfig.class,
        SchemaMigrationConfig.class, WebHardeningConfig.class })
public class AuthServiceApplication {

    /**
     * Application entry point.
     *
     * :param args: standard Java command-line arguments forwarded to
     *     :class:`SpringApplication` to bootstrap the Spring context.
     */
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
