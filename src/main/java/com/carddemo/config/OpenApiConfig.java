package com.carddemo.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc OpenAPI&nbsp;3 configuration for the CardDemo REST API.
 *
 * <p><strong>Migration role.</strong> The legacy AWS CardDemo mainframe application presented its
 * functionality through 17&nbsp;BMS 3270 character screens ({@code app/bms/*.bms}) driven by CICS
 * pseudo-conversational programs. In this Java&nbsp;17 / Spring&nbsp;Boot&nbsp;3.2.x modernization
 * that terminal presentation tier is <strong>retired</strong> (AAP&nbsp;&sect;0.3.4): the system now
 * exposes a stateless JSON REST API. This class supplies the single, machine-readable
 * {@link io.swagger.v3.oas.models.OpenAPI OpenAPI} contract that documents that API and powers the
 * Swagger UI &mdash; it is, in effect, the public specification that stands where the 3270 screens
 * once did. Authority: AAP&nbsp;&sect;0.3.1 ({@code OpenApiConfig.java (springdoc schema docs)}),
 * &sect;0.4.1.5.</p>
 *
 * <p><strong>No COBOL predecessor.</strong> Unlike the entity, service, and batch classes, this
 * configuration has <em>no</em> legacy source program to port ({@code source_files=[]}); there was
 * never an API-documentation artifact in the mainframe app. It is pure framework infrastructure.</p>
 *
 * <p><strong>What this bean declares.</strong> The {@link #cardDemoOpenAPI()} bean carries only API
 * <em>metadata</em> and the authentication scheme:</p>
 * <ul>
 *   <li>{@link io.swagger.v3.oas.models.info.Info Info} &mdash; the API title, version, a concise
 *       description of the modernization, and the repository's Apache&nbsp;License&nbsp;2.0.</li>
 *   <li>A {@code bearerAuth} HTTP bearer {@link io.swagger.v3.oas.models.security.SecurityScheme
 *       SecurityScheme} ({@code scheme=bearer}, {@code bearerFormat=JWT}) plus a global
 *       {@link io.swagger.v3.oas.models.security.SecurityRequirement SecurityRequirement}. This
 *       mirrors the application's stateless HS256 JWT authentication (wired by the sibling
 *       {@code security/} package, see AAP&nbsp;&sect;0.6.7) and makes the Swagger UI
 *       <strong>"Authorize"</strong> button available so a tester can paste a bearer token obtained
 *       from {@code POST /auth/signon} and exercise protected endpoints.</li>
 * </ul>
 *
 * <p><strong>Separation of concerns (intentional non-responsibilities).</strong></p>
 * <ul>
 *   <li>The springdoc serving <em>paths</em> ({@code springdoc.api-docs.path=/v3/api-docs},
 *       {@code springdoc.swagger-ui.path=/swagger-ui.html}, {@code operations-sorter=method}) are
 *       owned by {@code src/main/resources/application.yml} and are deliberately <strong>not</strong>
 *       duplicated or overridden here.</li>
 *   <li>This class declares <strong>no</strong> controllers, endpoints, CORS, or security-filter
 *       rules; the {@link org.springframework.security.web.SecurityFilterChain} and JWT filter live
 *       in the sibling {@code security/SecurityConfig}.</li>
 * </ul>
 *
 * <p><strong>Coordination note for {@code security/SecurityConfig} (reviewers / security agent).</strong>
 * For the generated documentation to be reachable on an otherwise authenticated API,
 * {@code security/SecurityConfig} must {@code permitAll()} the springdoc / Swagger paths:
 * {@code /v3/api-docs/**}, {@code /swagger-ui/**}, and {@code /swagger-ui.html}. That allow-listing is
 * the security configuration's responsibility and is intentionally <em>not</em> performed in this
 * class.</p>
 *
 * <p>This bean is discovered by Spring component scanning of the {@code com.carddemo} base package
 * (from {@code CardDemoApplication}); springdoc then merges this {@code OpenAPI} instance with the
 * operations and schemas it derives from the {@code @RestController} classes at runtime.</p>
 */
@Configuration
public class OpenApiConfig {

    /**
     * Logical name of the JWT bearer security scheme.
     *
     * <p>The same identifier is used both as the key under which the scheme is registered in
     * {@link io.swagger.v3.oas.models.Components#addSecuritySchemes(String,
     * io.swagger.v3.oas.models.security.SecurityScheme)} and as the entry added to the global
     * {@link io.swagger.v3.oas.models.security.SecurityRequirement}, so that the two references
     * resolve to one another and the Swagger UI renders a single "Authorize" affordance.</p>
     */
    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    /**
     * API title shown in the Swagger UI header and the generated OpenAPI document.
     */
    private static final String API_TITLE = "CardDemo REST API";

    /**
     * API version. Tracks the Maven artifact version ({@code com.carddemo:carddemo:1.0.0}).
     */
    private static final String API_VERSION = "1.0.0";

    /**
     * Human-readable summary of the modernized API surface, rendered in the Swagger UI and the
     * {@code GET /v3/api-docs} JSON. It states the technology migration and enumerates the feature
     * domains now exposed as JSON REST endpoints, and records that the legacy 3270 UI is retired.
     */
    private static final String API_DESCRIPTION =
            "Java 17 / Spring Boot 3.2.x modernization of the legacy AWS CardDemo mainframe "
            + "application (originally COBOL / CICS / VSAM on z/OS). The application's feature "
            + "domains (catalogued F-001 through F-009) are re-implemented as stateless JSON REST "
            + "endpoints: authentication, menu navigation, account management, card management, "
            + "transaction processing, bill payment, reporting, and user administration. The "
            + "original BMS 3270 screen user interface is retired and replaced by this REST "
            + "contract. Protected operations require an HS256 JWT bearer token obtained from "
            + "POST /auth/signon; use the Authorize action to supply it.";

    /**
     * Name of the license under which the CardDemo project is distributed (repository {@code LICENSE}).
     */
    private static final String LICENSE_NAME = "Apache License 2.0";

    /**
     * Canonical URL of the Apache License, Version 2.0.
     */
    private static final String LICENSE_URL = "http://www.apache.org/licenses/LICENSE-2.0";

    /**
     * Builds the application-wide {@link io.swagger.v3.oas.models.OpenAPI OpenAPI} definition that
     * springdoc serves at {@code /v3/api-docs} and renders through the Swagger UI.
     *
     * <p>The returned definition supplies the API {@link io.swagger.v3.oas.models.info.Info Info}
     * (title, version, description, Apache&nbsp;2.0 license) and registers a {@code bearerAuth} HTTP
     * bearer {@link io.swagger.v3.oas.models.security.SecurityScheme SecurityScheme} with
     * {@code bearerFormat = "JWT"}. A global
     * {@link io.swagger.v3.oas.models.security.SecurityRequirement SecurityRequirement} referencing
     * that scheme applies it to the documented operations, enabling the Swagger UI "Authorize"
     * button. No secrets or example tokens are embedded.</p>
     *
     * @return the fully populated {@code OpenAPI} metadata bean for the CardDemo REST API
     */
    @Bean
    public OpenAPI cardDemoOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title(API_TITLE)
                        .version(API_VERSION)
                        .description(API_DESCRIPTION)
                        .license(new License()
                                .name(LICENSE_NAME)
                                .url(LICENSE_URL)))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .name(SECURITY_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
