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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.config.ObservabilityConfig;

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
@SpringBootApplication
@EntityScan("com.carddemo.common.domain")
@EnableJpaRepositories("com.carddemo.user.repository")
@Import({ObservabilityConfig.class, GlobalExceptionHandler.class})
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
