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
package com.aws.carddemo.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 document metadata for the AWS CardDemo REST API.
 *
 * <p>CardDemo's 17 legacy CICS online screens (3270/BMS maps such as CC00/COSGN00C signon,
 * CM00/COMEN01C main menu, the account, card, and transaction management screens, CR00/CORPT00C
 * reports, CB00/COBIL00C bill pay, and the CU00&ndash;CU03 + CA00 administrative user-management
 * screens) are re-expressed as Spring MVC {@code @RestController}s during the COBOL&rarr;Java 25 /
 * Spring Boot 3 migration. springdoc-openapi introspects those controllers at runtime and derives
 * the full operation catalog automatically; this configuration therefore contributes only the
 * <em>top-level document metadata</em> (title, description, version, license) and the API's
 * security scheme. The authoritative transaction&harr;program&harr;mapset&harr;file registry that
 * proves this 17-screen surface is retained for reference in {@code app/csd/CARDDEMO.CSD}
 * (relocated under {@code legacy/}).
 *
 * <p><strong>Authentication.</strong> The application authenticates with stateless HTTP Basic
 * (configured by the sibling {@code config/SecurityConfig}). Accordingly, the single security
 * scheme published here is HTTP Basic ({@link SecurityScheme.Type#HTTP} with scheme
 * {@code "basic"}), registered under the key {@value #SECURITY_SCHEME_NAME} and applied globally
 * via a top-level {@link SecurityRequirement}. This makes the Swagger UI "Authorize" dialog drive
 * the same credentials the real security filter chain expects. No bearer/JWT scheme is documented
 * because the application has none.
 *
 * <p><strong>Separation of concerns.</strong> The springdoc endpoint locations and UI behavior
 * (the {@code /v3/api-docs} and {@code /swagger-ui.html} paths and the operations sorter) are owned
 * by {@code application.yml} under the {@code springdoc.*} keys and are deliberately <em>not</em>
 * restated here; likewise, the {@code permitAll} access that keeps those documentation endpoints
 * publicly reachable is wired in {@code SecurityConfig}. Keeping paths in configuration and the
 * document contract in code preserves independent reviewability.
 *
 * <p>This class holds no mutable state and declares no injected collaborators; Spring discovers it
 * through the component scan rooted at {@code com.aws.carddemo} and consumes the single
 * {@link OpenAPI} bean it exposes.
 */
@Configuration
public class OpenApiConfig {

    /**
     * Name under which the HTTP Basic security scheme is registered in the OpenAPI
     * {@link Components} and referenced by the global {@link SecurityRequirement}.
     *
     * <p>Declared once and used in both places so the scheme definition and the requirement that
     * references it cannot drift apart.
     */
    private static final String SECURITY_SCHEME_NAME = "basicAuth";

    /** Public title of the generated OpenAPI document. */
    private static final String API_TITLE = "AWS CardDemo API";

    /** Document version of the API surface (independent of the application/build version). */
    private static final String API_VERSION = "1.0.0";

    /**
     * Concise description of the migrated API surface. Faithful to the existing COBOL scope
     * (no feature expansion): it enumerates only the 17 re-platformed CICS online screens.
     */
    private static final String API_DESCRIPTION =
            "REST API for the AWS CardDemo credit-card account management system, migrated from "
                    + "COBOL/CICS to Java 25 and Spring Boot. It re-expresses the 17 legacy online "
                    + "(3270/BMS) screens as JSON request/response endpoints: signon, the main and "
                    + "administrative menus, account view/update, card list/view/update, transaction "
                    + "list/view/add, transaction reports, bill payment, and administrative user "
                    + "management. This is a faithful, behavior-preserving migration and not a web UI, "
                    + "terminal emulator, or 3270 renderer; no capabilities beyond the original COBOL "
                    + "application are added.";

    /** SPDX-style license identifier advertised in the document metadata. */
    private static final String LICENSE_NAME = "Apache-2.0";

    /** Canonical URL of the Apache License, Version 2.0. */
    private static final String LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0";

    /**
     * Builds the {@link OpenAPI} metadata bean consumed by springdoc-openapi.
     *
     * <p>The returned document supplies the API's title, description, version, and Apache-2.0
     * license, registers a single HTTP Basic security scheme under {@value #SECURITY_SCHEME_NAME},
     * and applies that scheme globally so every operation springdoc derives from the controllers is
     * documented as requiring authentication. The concrete operation catalog itself is produced by
     * springdoc's controller introspection and is intentionally not enumerated here.
     *
     * @return the fully populated OpenAPI document metadata for the CardDemo REST API
     */
    @Bean
    OpenAPI cardDemoOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title(API_TITLE)
                        .description(API_DESCRIPTION)
                        .version(API_VERSION)
                        .license(new License()
                                .name(LICENSE_NAME)
                                .url(LICENSE_URL)))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }
}
