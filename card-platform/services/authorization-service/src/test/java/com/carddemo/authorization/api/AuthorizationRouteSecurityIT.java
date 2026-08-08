package com.carddemo.authorization.api;

import com.carddemo.authorization.TestIdentityPasswords;
import com.carddemo.authorization.config.CrossSiteRequestFilter;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives every business route of this service through the real filter chain over a real port, and
 * reads back what an unauthenticated caller, an unentitled caller, the entitled caller and the
 * administrator each receive.
 *
 * <p><b>Why this class exists beside the unit tests of the same routes.</b> The controller tests here
 * stand the handler up without a filter, so they reach it without authenticating and can observe
 * neither the {@code 401} of a missing credential nor the {@code 403} of an identity reaching for
 * something it does not hold. The module's {@code SecurityConfigTest} exercises the ownership
 * {@code AuthorizationManager} as a unit rather than through a chain. Both answers are produced by
 * {@code config/SecurityConfig} ahead of every handler, so only a request over a real socket can
 * establish them.
 *
 * <p><b>What is asserted.</b> For each route: no credential is challenged with {@code 401} and a
 * {@code WWW-Authenticate} header; an authenticated identity that does not hold the route is refused
 * with {@code 403}; the entitled identity and the administrator reach the handler; and every refusal
 * body is the fixed four-member problem document, carrying no identifier, no route and no timestamp.
 * The metrics identity is asserted to reach no business route, and a path the chain names in no rule
 * is asserted to fail closed rather than answer {@code 404}.
 *
 * <p><b>Redaction.</b> Each refusal detail is compared literally. A reworded detail that helpfully
 * named the route or the subject would copy that wording into every caller's log, so the two
 * sentences are pinned here as well as in the interface description.
 *
 * <p>The rules under test are declared at {@code config/SecurityConfig}:
 * <ul><li>{@code POST /authorizations} requires {@code ROLE_ACQUIRER} or {@code ROLE_ADMIN}. No
 *     ownership authority is read by the chain, because the request names its subject in its
 *     body rather than in its path; {@code domain/CallerEntitlement} compares the caller
 *     against the resolved account inside the decision instead.</li>
 * <li>Every other path and method is denied.</li></ul>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                "ACQUIRER_PASSWORD_HASH=" + TestIdentityPasswords.ACQUIRER_PASSWORD_HASH,
                "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                "USER_SCOPES=" + AuthorizationRouteSecurityIT.USER_SCOPES,
                "spring.kafka.bootstrap-servers=" + AuthorizationRouteSecurityIT.UNREACHABLE_BROKER,
                "spring.kafka.listener.auto-startup=false",
                "spring.jpa.hibernate.ddl-auto=validate",
                "carddemo.outbox.relay.fixed-delay-ms=3600000",
                "carddemo.retention.sweep-interval-ms=3600000"
        })
@Testcontainers
@DisplayName("Every authorization business route through the real filter chain")
class AuthorizationRouteSecurityIT {

    /** The image tag {@code card-platform/docker-compose.yml} also names. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /** Database name, login name and password of the container, one value for all three. */
    private static final String CONTAINER_CREDENTIAL = "carddemo";

    /** Schema Flyway migrates, and the one the connection search path names. */
    private static final String SERVICE_SCHEMA = "authorization_service";

    /** Host and port the broker client is pointed at, where nothing listens. */
    static final String UNREACHABLE_BROKER = "localhost:1";

    /** The administrator login, which every ownership rule admits. */
    private static final String ADMIN_USERNAME = "admin001";

    /** Password of the administrator, paired with the bcrypt hash the properties configure. */
    static final String ADMIN_PASSWORD = TestIdentityPasswords.ADMIN_PASSWORD;

    /** The ordinary login, which holds only what {@link #USER_SCOPES} names. */
    private static final String USER_USERNAME = "user0001";

    /** Password of the ordinary identity. */
    static final String USER_PASSWORD = TestIdentityPasswords.USER_PASSWORD;

    /** The acquirer login, the workload identity a point-of-sale network presents. */
    private static final String ACQUIRER_USERNAME = "acquirer1";

    /** Password of the acquirer identity. */
    static final String ACQUIRER_PASSWORD = TestIdentityPasswords.ACQUIRER_PASSWORD;

    /** The metrics login, which carries the monitoring role and reaches no business route. */
    private static final String MONITORING_USERNAME = "monitor01";

    /** Password of the metrics identity. */
    static final String MONITORING_PASSWORD = TestIdentityPasswords.MONITORING_PASSWORD;

    /** The seeded card the request body names, from {@code app/data/ASCII/cardxref.txt}. */
    private static final String SEEDED_CARD = "0500024453765740";

    /** Format the origin timestamp is supplied in, from {@code ORIGIN_TIMESTAMP_PATTERN}. */
    private static final java.time.format.DateTimeFormatter ORIGIN_TIMESTAMP_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
                    .withZone(java.time.ZoneOffset.UTC);

    /** Format the processing timestamp is supplied in, from {@code PROCESSING_TIMESTAMP_PATTERN}. */
    private static final java.time.format.DateTimeFormatter PROCESSING_TIMESTAMP_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SS")
                    .withZone(java.time.ZoneOffset.UTC);

    /** The statuses the handler itself may answer once the chain has admitted a request. */
    private static final List<Integer> HANDLER_ANSWERS = List.of(200, 400, 422);

    /** Authorities the ordinary identity carries, one per entitlement it holds. */
    static final String USER_SCOPES = "SCOPE_ACCOUNT_00000000050";

    /** The four members {@code config/SecurityConfig} writes into a refusal, and no fifth. */
    private static final List<String> PROBLEM_MEMBERS =
            List.of("type", "title", "status", "detail");

    /** The problem type a refusal carries, since no type is minted for these. */
    private static final String ABOUT_BLANK = "about:blank";

    /** The sentence a 401 carries, fixed at {@code config/SecurityConfig}. */
    private static final String UNAUTHORIZED_DETAIL = "This request carried no usable credential.";

    /** The sentence a 403 carries, fixed at {@code config/SecurityConfig}. */
    private static final String FORBIDDEN_DETAIL = "This identity may not use this operation.";

    /** Longest one request waits. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** Reads one response body. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The container every test in this class shares. */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName(CONTAINER_CREDENTIAL)
            .withUsername(CONTAINER_CREDENTIAL)
            .withPassword(CONTAINER_CREDENTIAL);

    /** The port the embedded container bound. */
    @LocalServerPort
    private int port;

    /** The client every request in this class travels through. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    /**
     * Points the datasource at the container, on the schema the migrations own.
     *
     * @param registry the registry the test context reads these values from
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AuthorizationRouteSecurityIT::jdbcUrlOnServiceSchema);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Returns the container connection string with the service schema selected.
     *
     * @return the connection string
     */
    private static String jdbcUrlOnServiceSchema() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SERVICE_SCHEMA;
    }

    @Nested
    @DisplayName("The one decision route")
    class TheDecisionRoute {

        /**
         * Asserts the acquirer identity reaches the handler.
         *
         * <p>The status the handler answers depends on the decision and is not the subject here. What
         * matters is that it is a handler status and not a chain refusal, which is what establishes
         * that the rule admitted the caller.
         *
         * <p>The acquirer is the workload identity this route is for. It is a separate identity
         * rather than a scope on the cardholder entry because the route names its card in the
         * request body: no path variable carries an identifier an ownership scope could be compared
         * against, so the route reaches every card the platform holds.
         */
        @Test
        @DisplayName("the acquirer identity reaches the handler")
        void theAcquirerIdentityReachesTheHandler() {
            HttpResponse<String> response = send(authorized(ACQUIRER_USERNAME, ACQUIRER_PASSWORD,
                    "/authorizations").POST(jsonBody(oneAuthorization())).build());

            assertAll(
                    () -> assertNotEquals(401, response.statusCode(),
                            "the credential was accepted: " + response.body()),
                    () -> assertNotEquals(403, response.statusCode(),
                            "and the rule admits ROLE_ACQUIRER: " + response.body()),
                    () -> assertTrue(HANDLER_ANSWERS.contains(response.statusCode()),
                            "the answer came from the handler: " + response.statusCode() + " "
                                    + response.body()));
        }

        /**
         * Asserts the ordinary cardholder identity no longer reaches this route.
         *
         * <p>A cardholder credential authorized against every account the platform holds while the
         * rule read {@code hasAnyRole(USER, ADMIN)}, because the request names its own subject.
         * {@code ROLE_USER} is refused by the chain now, before any decision is taken, so no
         * decision row and no event follows the refusal.
         */
        @Test
        @DisplayName("an ordinary cardholder identity is refused before any decision")
        void anOrdinaryCardholderIdentityIsRefused() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    "/authorizations").POST(jsonBody(oneAuthorization())).build());

            assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL);
        }

        /** Asserts the administrator reaches the handler as well. */
        @Test
        @DisplayName("the administrator reaches the handler as well")
        void theAdministratorReachesTheHandlerAsWell() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/authorizations").POST(jsonBody(oneAuthorization())).build());

            assertAll(
                    () -> assertNotEquals(401, response.statusCode(), response.body()),
                    () -> assertNotEquals(403, response.statusCode(), response.body()),
                    () -> assertTrue(HANDLER_ANSWERS.contains(response.statusCode()),
                            "the answer came from the handler: " + response.statusCode()));
        }

        /** Asserts a caller carrying no credential is challenged. */
        @Test
        @DisplayName("no credential is challenged with 401 and a scheme to retry with")
        void noCredentialIsChallenged() {
            HttpResponse<String> response = send(anonymous("/authorizations")
                    .POST(jsonBody(oneAuthorization())).build());

            assertAll(
                    () -> assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL),
                    () -> assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("")
                                    .startsWith("Basic realm=\"carddemo\""),
                            "a 401 names the scheme and realm a caller should retry with"));
        }

        /**
         * Asserts the chain refuses before the body is read.
         *
         * <p>The body below is not an authorization at all. An unauthenticated caller is still
         * answered 401 rather than 400, which is what establishes that the chain runs ahead of
         * validation and that a probe cannot learn the shape of the request from an unauthenticated
         * call.
         */
        @Test
        @DisplayName("an unauthenticated caller is challenged before the body is read")
        void anUnauthenticatedCallerIsChallengedBeforeTheBodyIsRead() {
            HttpResponse<String> response =
                    send(anonymous("/authorizations").POST(jsonBody("{}")).build());

            assertAll(
                    () -> assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL),
                    () -> assertNotEquals(400, response.statusCode(),
                            "an empty body was never validated, because the chain answered first"));
        }

        /** Asserts a wrong password is challenged rather than refused. */
        @Test
        @DisplayName("a wrong password is challenged rather than refused")
        void aWrongPasswordIsChallenged() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, "not-the-password",
                    "/authorizations").POST(jsonBody(oneAuthorization())).build());

            assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL);
        }

        /** Asserts the metrics identity reaches no business route. */
        @Test
        @DisplayName("the metrics identity reaches no business route")
        void theMetricsIdentityReachesNoBusinessRoute() {
            HttpResponse<String> response = send(authorized(MONITORING_USERNAME,
                    MONITORING_PASSWORD, "/authorizations")
                    .POST(jsonBody(oneAuthorization())).build());

            assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL);
        }

        /** Asserts reading the decision route is named by no rule and fails closed. */
        @Test
        @DisplayName("reading the decision route fails closed for the administrator")
        void readingTheDecisionRouteFailsClosed() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/authorizations").GET().build());

            assertAll(
                    () -> assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL),
                    () -> assertNotEquals(405, response.statusCode(),
                            "the chain answers before the dispatcher reports a method mismatch"));
        }

        /** Asserts a path this chain names in no rule fails closed rather than answering 404. */
        @Test
        @DisplayName("a path named in no rule fails closed rather than answering 404")
        void anUnnamedPathFailsClosed() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/decisions").GET().build());

            assertAll(
                    () -> assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL),
                    () -> assertNotEquals(404, response.statusCode(),
                            "adding a route without deciding who may reach it must fail closed"));
        }
    }

    /**
     * Builds one authorization request body, captured now so the origin window admits it.
     *
     * @return the request document
     */
    private static String oneAuthorization() {
        java.time.Instant capturedAt = java.time.Instant.now();
        return """
                {
                  "transactionTypeCode": "01",
                  "transactionCategoryCode": "0001",
                  "source": "POS TERM",
                  "description": "Purchase at Abshire-Lowe",
                  "amount": "10.00",
                  "merchantId": "800000000",
                  "merchantName": "Abshire-Lowe",
                  "merchantCity": "North Enoshaven",
                  "merchantZip": "72112",
                  "cardNumber": "%s",
                  "originTimestamp": "%s",
                  "processingTimestamp": "%s0000"
                }
                """.formatted(SEEDED_CARD, ORIGIN_TIMESTAMP_FORMAT.format(capturedAt),
                PROCESSING_TIMESTAMP_FORMAT.format(capturedAt));
    }

    /**
     * Wraps one document as a JavaScript Object Notation request body.
     *
     * @param document the body text
     * @return the publisher, with the content type set by the caller of this builder
     */
    private static HttpRequest.BodyPublisher jsonBody(String document) {
        return HttpRequest.BodyPublishers.ofString(document, StandardCharsets.UTF_8);
    }


    /**
     * Asserts one refusal is the fixed problem document the security chain writes.
     *
     * @param response the response to read
     * @param status   the status the answer carries
     * @param title    the title the body carries
     * @param detail   the fixed detail the body carries
     */
    private static void assertProblem(HttpResponse<String> response, int status, String title,
            String detail) {
        assertEquals(status, response.statusCode(), "the status: " + response.body());
        assertEquals("application/problem+json",
                response.headers().firstValue("Content-Type").orElse("").split(";")[0],
                "the media type RFC 9457 names");
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""),
                "a refusal is kept out of every cache");
        JsonNode body = JSON.readTree(response.body());
        assertEquals(PROBLEM_MEMBERS.size(), body.size(),
                "the body carries the four declared members and no fifth: " + response.body());
        for (String member : PROBLEM_MEMBERS) {
            assertTrue(body.has(member), "the body carries " + member + ": " + response.body());
        }
        assertEquals(ABOUT_BLANK, body.get("type").asString(), "the problem type");
        assertEquals(title, body.get("title").asString(), "the title of this class of failure");
        assertEquals(status, body.get("status").asInt(), "the status repeated in the body");
        assertEquals(detail, body.get("detail").asString(), "the fixed detail");
        assertFalse(body.has("path"), "no member echoes the resolved request path");
        assertFalse(body.has("timestamp"), "the framework body carried a timestamp");
        assertFalse(response.body().contains(USER_USERNAME), "no refusal names the caller");
    }

    /**
     * Returns the origin the embedded container bound.
     *
     * @return the origin
     */
    private String baseUri() {
        return "http://localhost:" + port;
    }

    /**
     * Builds a request carrying no credential at all.
     *
     * @param path the route to reach
     * @return the builder, ready for a method
     */
    private HttpRequest.Builder anonymous(String path) {
        return HttpRequest.newBuilder(URI.create(baseUri() + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                // config/CrossSiteRequestFilter requires this on every state-changing
                // request. A first-party client sets it on all of them, and an HTML form
                // can set no header at all, which is what separates the two. It is set on
                // every request here, safe ones included, so no assertion below turns on
                // the difference.
                .header(CrossSiteRequestFilter.DEFAULT_REQUIRED_HEADER, "1");
    }

    /**
     * Builds a request carrying one identity's credential.
     *
     * @param username the login name
     * @param password the password, synthetic in every case
     * @param path     the route to reach
     * @return the builder, ready for a method
     */
    private HttpRequest.Builder authorized(String username, String password, String path) {
        String credential = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(baseUri() + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Basic " + credential)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                // config/CrossSiteRequestFilter requires this on every state-changing
                // request. A first-party client sets it on all of them, and an HTML form
                // can set no header at all, which is what separates the two. It is set on
                // every request here, safe ones included, so no assertion below turns on
                // the difference.
                .header(CrossSiteRequestFilter.DEFAULT_REQUIRED_HEADER, "1");
    }

    /**
     * Sends one request over a real socket.
     *
     * @param request the request to send
     * @return the response
     */
    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the request was interrupted", interrupted);
        } catch (java.io.IOException failed) {
            throw new IllegalStateException("the request did not complete", failed);
        }
    }
}
