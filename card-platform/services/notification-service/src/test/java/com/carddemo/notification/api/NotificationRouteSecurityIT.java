package com.carddemo.notification.api;

import com.carddemo.cobol.PanMasker;
import com.carddemo.notification.TestIdentityPasswords;

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
 * <ul><li>{@code GET /notifications/{cardNumber}} carries a sixteen-digit card number and requires
 *     the card-ownership authority for the token that number derives, which {@code ROLE_ADMIN}
 *     satisfies for every card.</li>
 * <li>Every other path and method is denied.</li></ul>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                "spring.kafka.bootstrap-servers=" + NotificationRouteSecurityIT.UNREACHABLE_BROKER,
                "spring.kafka.listener.auto-startup=false",
                "spring.jpa.hibernate.ddl-auto=validate",
                "carddemo.retention.sweep-interval-ms=3600000"
        })
@Testcontainers
@DisplayName("Every notification business route through the real filter chain")
class NotificationRouteSecurityIT {

    /** The image tag {@code card-platform/docker-compose.yml} also names. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /** Database name, login name and password of the container, one value for all three. */
    private static final String CONTAINER_CREDENTIAL = "carddemo";

    /** Schema Flyway migrates, and the one the connection search path names. */
    private static final String SERVICE_SCHEMA = "notification_service";

    /** Host and port the broker client is pointed at, where nothing listens. */
    static final String UNREACHABLE_BROKER = "localhost:1";

    /** The administrator login, which every ownership rule admits. */
    private static final String ADMIN_USERNAME = "admin001";

    /** Password of the administrator, synthetic and encoded with the noop prefix. */
    static final String ADMIN_PASSWORD = "not-a-real-admin-password";

    /** The ordinary login, which holds one card scope and nothing else. */
    private static final String USER_USERNAME = "user0001";

    /** Password of the ordinary identity. */
    static final String USER_PASSWORD = "not-a-real-user-password";

    /** The metrics login, which carries the monitoring role and reaches no business route. */
    private static final String MONITORING_USERNAME = "monitor01";

    /** Password of the metrics identity. */
    static final String MONITORING_PASSWORD = "not-a-real-monitoring-password";

    /**
     * Card number the ordinary identity holds, sixteen digits.
     *
     * <p>Record one of {@code app/data/ASCII/cardxref.txt}, which names account
     * {@code 00000000050}. The route reads a card number and
     * {@code api/NotificationHistoryController} derives the token from it, so the path variable is
     * the number and the authority granted below is the token that number derives.
     */
    private static final String OWNED_CARD_NUMBER = "0500024453765740";

    /** Record two of the same fixture, a well-formed number the ordinary identity does not hold. */
    private static final String UNHELD_CARD_NUMBER = "0683586198171516";

    /** Prefix of the authority that reaches one card, followed by that card's token. */
    private static final String CARD_SCOPE_PREFIX = "SCOPE_CARD_";

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
        registry.add("spring.datasource.url", NotificationRouteSecurityIT::jdbcUrlOnServiceSchema);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Grants the ordinary identity the one card it owns.
     *
     * <p>The authority names a card token, and a token is a keyed value over a card number, so it
     * cannot be a compile-time constant in the annotation above: a build supplying a different
     * card-token key would grant an authority no request could match. Deriving it here uses the same
     * key the running service derives with.
     *
     * @param registry the registry the test context reads these values from
     */
    @DynamicPropertySource
    static void identityProperties(DynamicPropertyRegistry registry) {
        registry.add("USER_SCOPES",
                () -> CARD_SCOPE_PREFIX + PanMasker.cardToken(OWNED_CARD_NUMBER));
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
    @DisplayName("The one card-keyed route")
    class TheCardKeyedRoute {

        /** Asserts the holder of the card reaches its own history. */
        @Test
        @DisplayName("the holder of the card reads its own history")
        void theHolderReadsItsOwnHistory() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    historyRoute(OWNED_CARD_NUMBER)).GET().build());

            assertEquals(200, response.statusCode(),
                    "the identity holding the card scope for this number reaches the handler: "
                            + response.body());
        }

        /** Asserts the administrator reaches any card's history. */
        @Test
        @DisplayName("the administrator reads any card's history")
        void theAdministratorReadsAnyCardsHistory() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    historyRoute(UNHELD_CARD_NUMBER)).GET().build());

            assertEquals(200, response.statusCode(),
                    "ROLE_ADMIN passes every ownership check: " + response.body());
        }

        /** Asserts a caller carrying no credential is challenged. */
        @Test
        @DisplayName("no credential is challenged with 401 and a scheme to retry with")
        void noCredentialIsChallenged() {
            HttpResponse<String> response =
                    send(anonymous(historyRoute(OWNED_CARD_NUMBER)).GET().build());

            assertAll(
                    () -> assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL),
                    () -> assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("")
                                    .startsWith("Basic realm=\"carddemo\""),
                            "a 401 names the scheme and realm a caller should retry with: "
                                    + response.headers().firstValue("WWW-Authenticate")));
        }

        /** Asserts a wrong password is challenged rather than refused. */
        @Test
        @DisplayName("a wrong password is challenged rather than refused")
        void aWrongPasswordIsChallenged() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, "not-the-password",
                    historyRoute(OWNED_CARD_NUMBER)).GET().build());

            assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL);
        }

        /** Asserts another card's history is refused, revealing nothing about it. */
        @Test
        @DisplayName("another card's history is refused, and the body reveals nothing about it")
        void anotherCardsHistoryIsRefused() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    historyRoute(UNHELD_CARD_NUMBER)).GET().build());

            assertAll(
                    () -> assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL),
                    () -> assertFalse(response.body().contains(UNHELD_CARD_NUMBER),
                            "the refusal does not echo the card number that was asked for"),
                    () -> assertNotEquals(404, response.statusCode(),
                            "a 404 would say whether that card has any history at all"));
        }

        /** Asserts the metrics identity reaches no business route. */
        @Test
        @DisplayName("the metrics identity reaches no business route")
        void theMetricsIdentityReachesNoBusinessRoute() {
            HttpResponse<String> response = send(authorized(MONITORING_USERNAME,
                    MONITORING_PASSWORD, historyRoute(OWNED_CARD_NUMBER)).GET().build());

            assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL);
        }

        /** Asserts a method this chain names in no rule fails closed. */
        @Test
        @DisplayName("a method named in no rule fails closed for the administrator")
        void anUnnamedMethodFailsClosed() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    historyRoute(OWNED_CARD_NUMBER)).DELETE().build());

            assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL);
        }

        /** Asserts a path this chain names in no rule fails closed rather than answering 404. */
        @Test
        @DisplayName("a path named in no rule fails closed rather than answering 404")
        void anUnnamedPathFailsClosed() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/statements").GET().build());

            assertAll(
                    () -> assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL),
                    () -> assertNotEquals(404, response.statusCode(),
                            "adding a route without deciding who may reach it must fail closed"));
        }
    }

    /**
     * Returns the route of one card's notification history.
     *
     * @param cardNumber the card number, sixteen digits
     * @return the route
     */
    private static String historyRoute(String cardNumber) {
        return "/notifications/" + cardNumber;
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
                .header("Accept", "application/json");
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
                .header("Accept", "application/json");
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
