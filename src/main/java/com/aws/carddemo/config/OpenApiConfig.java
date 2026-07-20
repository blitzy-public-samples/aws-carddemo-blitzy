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
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
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
 * the full operation catalog automatically; this configuration therefore contributes the
 * <em>top-level document metadata</em> (title, description, version, license), the API's
 * security scheme, a shared {@code ProblemDetail} error schema, and a global customizer that
 * documents the reachable error responses on every operation (see below). The authoritative
 * transaction&harr;program&harr;mapset&harr;file registry that
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
 * <p><strong>Error responses.</strong> Every controller operation shares one error contract: the
 * {@code GlobalExceptionHandler} and the security entry point/handler render failures as RFC&nbsp;7807
 * {@code application/problem+json} documents. Rather than repeat {@code @ApiResponse} annotations on
 * all 34 operations, a single {@code ProblemDetail} schema is registered once under
 * {@code components.schemas} and a {@link GlobalOpenApiCustomizer} attaches the reachable error
 * statuses to each operation after springdoc's controller introspection: {@code 400}, {@code 404},
 * {@code 405}, and {@code 500} on every operation; {@code 401} on every authenticated path (all but
 * the public {@code /api/v1/auth/**} sign-on); {@code 403} on the role-gated {@code /api/v1/admin/**}
 * screens; {@code 415} on every body-consuming {@code POST}; and {@code 409} on the mutating
 * {@code POST}s (account/card/user update, user add, user delete, transaction add, bill pay) that can
 * hit a uniqueness or optimistic-lock conflict. This keeps the published contract aligned with the
 * behavior wired in {@code SecurityConfig} and {@code GlobalExceptionHandler} without per-controller
 * duplication.
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
     * Component name of the shared RFC 7807 error schema registered under
     * {@code components.schemas} and referenced (by {@code $ref}) from every documented error
     * response, so the {@code application/problem+json} body shape is defined exactly once.
     */
    private static final String PROBLEM_SCHEMA_NAME = "ProblemDetail";

    /** Media type of every error body produced by the application (RFC 7807 problem detail). */
    private static final String PROBLEM_MEDIA_TYPE = "application/problem+json";

    /** JSON-pointer {@code $ref} to {@value #PROBLEM_SCHEMA_NAME} in {@code components.schemas}. */
    private static final String PROBLEM_SCHEMA_REF = "#/components/schemas/" + PROBLEM_SCHEMA_NAME;

    /** Human-readable descriptions for each documented error status (shown in Swagger UI). */
    private static final String DESC_400 =
            "Bad Request: the request was malformed or failed input validation (RFC 7807 problem+json).";
    private static final String DESC_401 =
            "Unauthorized: authentication is required and was missing or invalid (RFC 7807 problem+json).";
    private static final String DESC_403 =
            "Forbidden: the authenticated user lacks the ADMIN role required for this operation "
                    + "(RFC 7807 problem+json).";
    private static final String DESC_404 =
            "Not Found: the requested resource or record does not exist (RFC 7807 problem+json).";
    private static final String DESC_405 =
            "Method Not Allowed: the HTTP method is not supported for this resource (RFC 7807 problem+json).";
    private static final String DESC_409 =
            "Conflict: the change violates a uniqueness constraint or lost an optimistic-lock race "
                    + "(RFC 7807 problem+json).";
    private static final String DESC_415 =
            "Unsupported Media Type: the request body is not application/json (RFC 7807 problem+json).";
    private static final String DESC_500 =
            "Internal Server Error: an unexpected error occurred (RFC 7807 problem+json).";

    /**
     * Builds the {@link OpenAPI} metadata bean consumed by springdoc-openapi.
     *
     * <p>The returned document supplies the API's title, description, version, and Apache-2.0
     * license, registers a single HTTP Basic security scheme under {@value #SECURITY_SCHEME_NAME},
     * and applies that scheme globally so every operation springdoc derives from the controllers is
     * documented as requiring authentication. The shared {@value #PROBLEM_SCHEMA_NAME} error schema
     * and the per-operation error responses that reference it are contributed separately by
     * {@link #problemDetailResponsesCustomizer()}, which runs after springdoc has assembled the
     * document: springdoc rebuilds {@code components.schemas} from controller introspection, so the
     * schema must be added to the finished document rather than to this base bean (which would
     * discard it). The concrete operation catalog itself is produced by springdoc's controller
     * introspection and is intentionally not enumerated here.
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

    /**
     * Registers the global springdoc customizer that documents the reachable error responses on
     * every operation springdoc derives from the controllers.
     *
     * <p>springdoc first introspects the controllers (so each operation already carries its success
     * response and the {@code @Operation}/{@code @Tag} metadata) and rebuilds {@code components.schemas}
     * from that introspection. This customizer then runs over the finished document and (1) registers
     * the shared {@value #PROBLEM_SCHEMA_NAME} schema on the assembled {@code components} &mdash; it
     * must be added here, not on the base OpenAPI bean, because springdoc's schema rebuild discards
     * bean-supplied schemas &mdash; and (2) for each operation, attaches the {@code 4xx}/{@code 5xx}
     * statuses that the {@code GlobalExceptionHandler} and the Spring Security entry point/handler can
     * actually produce for that path and method, each referencing that shared schema as
     * {@code application/problem+json}. The status selection mirrors the runtime behavior exactly (see
     * the class Javadoc), so the published contract never claims an error the code cannot emit and
     * never omits one it can. Existing responses are never overwritten.
     *
     * @return a {@link GlobalOpenApiCustomizer} that registers the shared error schema and adds the
     *         RFC 7807 error responses per operation
     */
    @Bean
    GlobalOpenApiCustomizer problemDetailResponsesCustomizer() {
        return openApi -> {
            // springdoc rebuilds components.schemas from controller introspection, so the shared
            // ProblemDetail schema must be registered here on the assembled document (adding it to the
            // base OpenAPI bean's components would be discarded by that rebuild).
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSchemas(PROBLEM_SCHEMA_NAME, problemDetailSchema());
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, pathItem) ->
                    pathItem.readOperationsMap().forEach((method, operation) ->
                            documentErrorResponses(path, method, operation)));
        };
    }

    /**
     * Attaches the reachable error responses to a single operation, keyed by the path and HTTP method
     * so the documented statuses match what the running application can return.
     *
     * @param path      the request path of the operation (e.g. {@code /api/v1/admin/users/add})
     * @param method    the HTTP method of the operation
     * @param operation the springdoc-derived operation whose responses are augmented in place
     */
    private static void documentErrorResponses(String path, PathItem.HttpMethod method,
                                                Operation operation) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            operation.setResponses(responses);
        }

        boolean post = method == PathItem.HttpMethod.POST;
        // Public sign-on entry point (permitAll) is the only unauthenticated business path.
        boolean requiresAuth = !path.startsWith("/api/v1/auth");
        // Only the admin screens are role-gated (hasRole('ADMIN')) and can yield 403.
        boolean adminGated = path.startsWith("/api/v1/admin");
        // 409 is reachable only where a POST actually writes and can hit a uniqueness/optimistic-lock
        // conflict: the update/add/delete screens and bill payment (posting).
        boolean mutating = post && (path.contains("/update") || path.contains("/add")
                || path.contains("/delete") || path.endsWith("/billpay"));

        // Universally reachable on every operation.
        putErrorResponse(responses, "400", DESC_400);
        if (requiresAuth) {
            putErrorResponse(responses, "401", DESC_401);
        }
        if (adminGated) {
            putErrorResponse(responses, "403", DESC_403);
        }
        putErrorResponse(responses, "404", DESC_404);
        putErrorResponse(responses, "405", DESC_405);
        if (mutating) {
            putErrorResponse(responses, "409", DESC_409);
        }
        if (post) {
            // Only body-consuming POSTs can reject the request Content-Type.
            putErrorResponse(responses, "415", DESC_415);
        }
        putErrorResponse(responses, "500", DESC_500);
    }

    /**
     * Adds one error response referencing the shared {@value #PROBLEM_SCHEMA_NAME} schema, unless the
     * operation already declares that status (so any explicitly authored response wins).
     *
     * @param responses   the operation's response map, augmented in place
     * @param code        the HTTP status code as a string (e.g. {@code "404"})
     * @param description the human-readable description shown in the API documentation
     */
    private static void putErrorResponse(ApiResponses responses, String code, String description) {
        if (responses.get(code) == null) {
            responses.addApiResponse(code, new ApiResponse()
                    .description(description)
                    .content(problemContent()));
        }
    }

    /**
     * Builds an {@code application/problem+json} {@link Content} whose schema is a {@code $ref} to the
     * shared {@value #PROBLEM_SCHEMA_NAME} component.
     *
     * <p>The swagger-models fluent {@link Schema} builder is generic but returns the raw
     * {@code Schema} type from every setter, so raw-type/unchecked warnings are unavoidable here and
     * are suppressed narrowly on this helper (the project builds with {@code -Xlint:all} and
     * {@code failOnWarning}). The suppression is confined to this schema-construction helper and does
     * not leak to the callers, which see only the non-generic {@link Content} return type.
     *
     * @return a fresh {@link Content} carrying the problem-detail media type and schema reference
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Content problemContent() {
        Schema schema = new Schema().$ref(PROBLEM_SCHEMA_REF);
        return new Content().addMediaType(PROBLEM_MEDIA_TYPE, new MediaType().schema(schema));
    }

    /**
     * Builds the shared RFC 7807 {@code ProblemDetail} schema registered once under
     * {@code components.schemas}. Its fields mirror the body written by {@code ProblemDetailHttpWriter}
     * and the {@code GlobalExceptionHandler}: {@code type}, {@code title}, {@code status},
     * {@code detail}, {@code instance}, and the CardDemo {@code correlationId}.
     *
     * <p>The typed {@link ObjectSchema}/{@link StringSchema}/{@link IntegerSchema} builders are used
     * (rather than a bare {@link Schema} with {@code type(...)}) so the {@code type} keyword serializes
     * correctly under the OpenAPI 3.1 dialect springdoc emits. Their inherited fluent setters still
     * return the raw {@code Schema} type, so raw-type/unchecked warnings are suppressed narrowly on
     * this helper only.
     *
     * @return the fully populated problem-detail schema
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ObjectSchema problemDetailSchema() {
        ObjectSchema schema = new ObjectSchema();
        schema.description("RFC 7807 problem detail returned as application/problem+json for every "
                + "error response emitted by the API.");
        schema.addProperty("type", new StringSchema()
                .format("uri").example("about:blank"));
        schema.addProperty("title", new StringSchema()
                .example("Not Found"));
        schema.addProperty("status", new IntegerSchema()
                .format("int32").example(404));
        schema.addProperty("detail", new StringSchema()
                .example("The requested account was not found."));
        schema.addProperty("instance", new StringSchema()
                .format("uri").example("/api/v1/accounts/view"));
        schema.addProperty("correlationId", new StringSchema()
                .description("Request correlation id echoed from the X-Correlation-Id header / MDC."));
        return schema;
    }
}
