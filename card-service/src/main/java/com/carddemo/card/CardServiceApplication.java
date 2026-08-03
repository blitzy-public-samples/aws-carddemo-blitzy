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
package com.carddemo.card;

import com.carddemo.common.config.CardDemoErrorController;
import com.carddemo.common.config.ContainerErrorReportConfig;
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
 * Executable entry point for the CardDemo card-service microservice.
 *
 * :purpose: Bootstraps the Spring Boot application context for card list
 *     (CICS CCLI), card detail (CICS CCDL), and card update (CICS CCUP)
 *     operations, wiring the shared carddemo-common JPA entities,
 *     observability configuration, and global exception handling.
 * :output: A running stateless REST service exposing the card endpoints.
 */
// UserDetailsServiceAutoConfiguration is excluded: this service authenticates from the
// shared CardDemo session, never from an in-memory user. Left enabled, Spring Boot
// generates a random development credential and logs it at WARN on every start, which
// both advertises a usable in-memory account and is an unrequested secret in the log.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EntityScan("com.carddemo.common.domain")
@EnableJpaRepositories("com.carddemo.card.repository")
@Import({ ObservabilityConfig.class, GlobalExceptionHandler.class, CardDemoErrorController.class,
        ContainerErrorReportConfig.class, PersistenceExceptionHandler.class, SessionRedisConfig.class,
        RedisCommandMetricsConfig.class, SchemaMigrationConfig.class, WebObservabilityConfig.class,
        WebHardeningConfig.class, PiiEncryptionConfig.class })
public class CardServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CardServiceApplication.class, args);
    }
}
