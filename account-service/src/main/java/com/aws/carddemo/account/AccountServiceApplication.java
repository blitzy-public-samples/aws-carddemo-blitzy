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
package com.aws.carddemo.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the CardDemo Account Management microservice (Feature F-003).
 *
 * <p>This service migrates the legacy COBOL/CICS/VSAM account view
 * ({@code CAVW} / {@code COACTVWC}) and account update ({@code CAUP} /
 * {@code COACTUPC}) transactions to a stateless Java&nbsp;17 / Spring&nbsp;Boot
 * REST service backed by PostgreSQL. It exposes:</p>
 * <ul>
 *   <li>{@code GET /api/v1/accounts/{accountId}} &mdash; account inquiry</li>
 *   <li>{@code PUT /api/v1/accounts/{accountId}} &mdash; account update</li>
 * </ul>
 *
 * <p>Annotated with {@link SpringBootApplication}, this class is the
 * <strong>component-scan root</strong> for the {@code com.aws.carddemo.account}
 * package and all of its sub-packages ({@code controller}, {@code service},
 * {@code repository}, {@code domain}, {@code dto}, {@code mapper},
 * {@code exception} and {@code config}). Because it resides directly in the
 * base package, Spring Boot's default component scan, JPA repository scan and
 * entity scan automatically discover every layer &mdash; no explicit
 * {@code scanBasePackages}, {@code @EnableJpaRepositories} or {@code @EntityScan}
 * is required (adding any of those would risk narrowing the scan).</p>
 *
 * <p>This class is intentionally bootstrap-only. Datasource, JPA, Flyway,
 * Actuator and logging are configured declaratively in {@code application.yml},
 * and OpenAPI metadata is defined in {@code config.OpenApiConfig}; no beans,
 * runners or business logic are declared here.</p>
 */
@SpringBootApplication
public class AccountServiceApplication {

    /**
     * Boots the Spring application context and starts the embedded web server.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
