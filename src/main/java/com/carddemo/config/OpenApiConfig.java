package com.carddemo.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * springdoc-openapi 2.3.0 configuration for the CardDemo REST API.
 *
 * <p>This class declares a single {@link OpenAPI} bean that drives the
 * auto-generated API documentation served at:
 * <ul>
 *   <li>{@code /swagger-ui.html} &mdash; interactive Swagger UI</li>
 *   <li>{@code /v3/api-docs} &mdash; machine-readable OpenAPI 3.0 JSON spec</li>
 * </ul>
 *
 * <p>The bean configures:
 * <ol>
 *   <li><b>API metadata</b> &mdash; title, version, description, license, contact</li>
 *   <li><b>Server URLs</b> &mdash; {@code http://localhost:8080} for local dev</li>
 *   <li><b>Bearer-token security scheme</b> ({@code bearerAuth}) so Swagger UI
 *       displays the "Authorize" button for entering JWTs</li>
 *   <li><b>Global security requirement</b> &mdash; all endpoints require
 *       {@code bearerAuth} by default; public endpoints
 *       ({@code POST /api/auth/login}) opt out via
 *       {@code @SecurityRequirements({})} at the controller method level</li>
 * </ol>
 *
 * <h2>Coordination with Spring Security</h2>
 * <p>The {@code com.carddemo.security.SecurityConfig.filterChain} whitelists
 * the springdoc-openapi endpoints (
 * {@code /v3/api-docs/**}, {@code /swagger-ui/**}, {@code /swagger-ui.html},
 * {@code /swagger-resources/**}, {@code /webjars/**}) as {@code permitAll()},
 * so the Swagger UI is accessible without authentication. When a user enters a
 * JWT via the "Authorize" dialog, Swagger UI sends
 * {@code Authorization: Bearer <token>} on subsequent "Try it out" calls &mdash; this
 * matches the format expected by
 * {@code com.carddemo.security.JwtAuthenticationFilter}.
 *
 * <h2>Profile Behavior</h2>
 * <ul>
 *   <li>{@code dev}: API docs and Swagger UI both enabled (default)</li>
 *   <li>{@code prod}: {@code springdoc.api-docs.enabled=false} and
 *       {@code springdoc.swagger-ui.enabled=false} &mdash; docs disabled to reduce
 *       attack surface (production clients use a pre-published OpenAPI spec)</li>
 * </ul>
 *
 * <h2>Critical Rules (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><b>PR-17</b>: BCrypt for passwords &mdash; Swagger UI uses JWT-bearer flow only</li>
 *   <li><b>PR-18</b>: Admin endpoints enforce {@code @PreAuthorize} &mdash; Swagger UI
 *       can test admin roles by submitting an admin-issued JWT</li>
 *   <li><b>PR-25</b>: Single monolith &mdash; one OpenAPI spec for all 9 controllers</li>
 *   <li><b>PR-28</b>: Jakarta EE namespace consistent with Spring Boot 3.x</li>
 *   <li><b>PR-30</b>: Single-phase delivery</li>
 * </ul>
 *
 * @see com.carddemo.security.SecurityConfig whitelists OpenAPI endpoints
 * @see com.carddemo.security.JwtAuthenticationFilter consumes the JWT
 *      submitted via Swagger UI's "Authorize" button
 */
@Configuration
public class OpenApiConfig {

    /**
     * Constructs the OpenAPI 3.0 specification for the CardDemo REST API.
     *
     * <p>The returned bean is consumed by springdoc-openapi's
     * {@code SpringDocConfiguration} to merge with auto-detected
     * {@code @RestController} endpoints and produce the final OpenAPI document
     * served at {@code /v3/api-docs} and rendered by Swagger UI at
     * {@code /swagger-ui.html}.
     *
     * <p>The bean defines:
     * <ul>
     *   <li>API metadata: title, version, description, license (Apache 2.0),
     *       contact (project maintainers)</li>
     *   <li>Server URL: {@code http://localhost:8080} (override per environment
     *       via {@code springdoc.swagger-ui.server} if needed)</li>
     *   <li>Security scheme {@code bearerAuth}: HTTP Bearer + JWT
     *       (HS256), aligned with {@code JwtAuthenticationFilter}</li>
     *   <li>Global security requirement: all endpoints require {@code bearerAuth}
     *       unless explicitly excluded by the controller method via
     *       {@code @SecurityRequirements({})}</li>
     * </ul>
     *
     * @return the OpenAPI specification bean
     */
    @Bean
    public OpenAPI cardDemoOpenAPI() {
        final String securitySchemeName = "bearerAuth";

        return new OpenAPI()
            .info(new Info()
                .title("CardDemo API")
                .version("1.0.0")
                .description(
                    "Modernized Spring Boot 3.2 implementation of the AWS CardDemo "
                        + "COBOL/CICS/VSAM mainframe credit-card application. "
                        + "All business logic preserved from the original COBOL "
                        + "programs (see AAP \u00a70.7.1 preservation rules PR-01 "
                        + "through PR-12). Online flows expose REST endpoints; "
                        + "batch flows are launched via "
                        + "POST /api/admin/jobs/{jobName}/launch.")
                .contact(new Contact()
                    .name("CardDemo Maintainers")
                    .url("https://github.com/aws-samples/aws-mainframe-modernization-carddemo"))
                .license(new License()
                    .name("Apache 2.0")
                    .url("https://www.apache.org/licenses/LICENSE-2.0")))
            .servers(List.of(
                new Server()
                    .url("http://localhost:8080")
                    .description("Local development server")))
            .components(new Components()
                .addSecuritySchemes(securitySchemeName,
                    new SecurityScheme()
                        .name(securitySchemeName)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description(
                            "JWT bearer token issued by POST /api/auth/login. "
                                + "Format: 'Bearer <jwt-token>'. "
                                + "Claims: sub (userId), userType ('A'=ADMIN, "
                                + "'U'=USER), iat, exp. Algorithm: HS256.")))
            .addSecurityItem(new SecurityRequirement()
                .addList(securitySchemeName));
    }
}
