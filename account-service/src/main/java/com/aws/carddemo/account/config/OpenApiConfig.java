/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.account.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import org.springframework.context.annotation.Configuration;

/**
 * Global springdoc-openapi metadata for the CardDemo Account Service
 * (Feature F-003 &mdash; account inquiry and update).
 *
 * <p>This class is a purely declarative configuration holder. It carries the
 * document-level {@link OpenAPIDefinition} {@link Info} block that springdoc
 * renders as the OpenAPI 3 JSON document at {@code /v3/api-docs} and as the
 * interactive Swagger UI at {@code /swagger-ui.html}. That generated contract
 * replaces the retired 3270 BMS screen definitions
 * ({@code COACTVW.bms} / {@code COACTUP.bms}) with a self-documenting
 * REST/JSON contract.</p>
 *
 * <p>The documented endpoints migrate two legacy CICS transactions off
 * COBOL/CICS/VSAM:</p>
 * <ul>
 *   <li>{@code GET /api/v1/accounts/{accountId}} &mdash; account inquiry,
 *       replacing transaction <strong>CAVW</strong> (program
 *       {@code COACTVWC}).</li>
 *   <li>{@code PUT /api/v1/accounts/{accountId}} &mdash; account update,
 *       replacing transaction <strong>CAUP</strong> (program
 *       {@code COACTUPC}).</li>
 * </ul>
 *
 * <p>The class is annotated {@link Configuration} so that the default
 * {@code @SpringBootApplication} component scan (rooted at the base package
 * {@code com.aws.carddemo.account}) discovers and registers it automatically.
 * It holds no state, performs no I/O, and declares no collaborators; the
 * per-operation documentation ({@code @Operation}, {@code @ApiResponse}, and
 * similar annotations) lives on the controller methods, not here.</p>
 */
@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "CardDemo Account Service API",
        version = "1.0",
        description = "REST API for the CardDemo Account Management slice (Feature F-003). "
            + "Provides account inquiry (GET /api/v1/accounts/{accountId}) and account update "
            + "(PUT /api/v1/accounts/{accountId}), migrating the legacy CICS transactions "
            + "CAVW (COACTVWC) and CAUP (COACTUPC) from COBOL/CICS/VSAM to a stateless "
            + "Spring Boot service.",
        license = @License(
            name = "Apache 2.0",
            url = "http://www.apache.org/licenses/LICENSE-2.0"
        )
    )
)
public class OpenApiConfig {
}
