package com.carddemo.notification.api;

import com.carddemo.cobol.PanMasker;
import com.carddemo.notification.NotificationServiceDatabase;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives every business route of this service through the real filter chain over a real port, and
 * reads back what an unauthenticated caller, an unentitled caller, the entitled caller and the
 * administrator each receive.
 *
 * <p><b>What this class measures that the unit tests of the same routes cannot.</b> The controller tests here
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
 * <ul><li>{@code GET /notifications/{cardToken}} carries a card token and requires the
 *     card-ownership authority naming that same token, which {@code ROLE_ADMIN} satisfies for every
 *     card. The path value and the authority value are the same kind of value, so the rule compares
 *     them directly and derives nothing.</li>
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
@DisplayName("Every notification business route through the real filter chain")
class NotificationRouteSecurityIT {

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
     * {@code 00000000050}. No request below sends it: the route reads a card token, so this value is
     * tokenized before it reaches a path and the authority granted below names the same token.
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

    /**
     * The one container the module fork runs, which this class reads a login from.
     *
     * <p>{@link NotificationServiceDatabase} owns it and hands this class a database of its own inside it.
     * Nothing here starts or stops a container.
     */
    static final PostgreSQLContainer POSTGRES = NotificationServiceDatabase.container();

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
        return NotificationServiceDatabase.urlFor(NotificationRouteSecurityIT.class);
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

            assertReachedTheHandler(response,
                    "the identity holding the card scope for this token reaches the handler");
        }

        /** Asserts the administrator reaches any card's history. */
        @Test
        @DisplayName("the administrator reads any card's history")
        void theAdministratorReadsAnyCardsHistory() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    historyRoute(UNHELD_CARD_NUMBER)).GET().build());

            assertReachedTheHandler(response, "ROLE_ADMIN passes every ownership check");
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
                    () -> assertFalse(
                            response.body().contains(PanMasker.cardToken(UNHELD_CARD_NUMBER)),
                            "nor the token it was asked for"),
                    () -> assertNotEquals(404, response.statusCode(),
                            "the chain refuses before the read, so a caller holding no authority "
                                    + "cannot tell an unheld card from one with no history"));
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
     * Returns the route of one card's notification history, naming the card by its token.
     *
     * <p>The number is tokenized here rather than sent. A path is written to an access log, a proxy
     * log, a trace and a browser history, and none of the four is reachable by this application's
     * redaction, so no test of this service sends a card number in a request line either.
     *
     * @param cardNumber the card number, sixteen digits, which this method tokenizes
     * @return the route
     */
    private static String historyRoute(String cardNumber) {
        return "/notifications/" + PanMasker.cardToken(cardNumber);
    }


    /**
     * Asserts one request passed the filter chain and was answered by the handler.
     *
     * <p>What this class measures is who reaches the handler, not what the handler then returns. No
     * read-model row is seeded here, so the handler answers {@code 404} with its own three-member
     * failure shape, which is the answer {@code api/NotificationHistoryController} gives a token it
     * holds no statement entry for. That is enough to distinguish it from the chain's own refusals:
     * the chain writes {@code 401} or {@code 403} carrying a four-member problem document under
     * {@code application/problem+json} and never reaches the handler at all.
     * {@code api/NotificationHistoryControllerTest} is where the successful body is asserted.
     *
     * @param response the response to read
     * @param because  what reaching the handler proves
     */
    private static void assertReachedTheHandler(HttpResponse<String> response, String because) {
        assertAll(because + ": " + response.body(),
                () -> assertNotEquals(401, response.statusCode(),
                        "the chain challenged the credential rather than admitting it"),
                () -> assertNotEquals(403, response.statusCode(),
                        "the chain refused the request rather than admitting it"),
                () -> assertEquals(404, response.statusCode(),
                        "the handler answered, and no statement entry is seeded for this card"),
                () -> assertTrue(response.body().contains("\"route\""),
                        "the body is the endpoint's own failure shape, which names the route "
                                + "template, rather than the problem document the chain writes"));
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
