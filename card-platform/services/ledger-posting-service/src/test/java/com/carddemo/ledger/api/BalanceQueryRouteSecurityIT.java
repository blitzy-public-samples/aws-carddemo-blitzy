package com.carddemo.ledger.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.ledger.LedgerServiceDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import com.carddemo.ledger.TestIdentityPasswords;
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
 * Drives {@code GET /balances/{accountId}} through the real filter chain over a real port, and reads
 * back what an unauthenticated caller, an unentitled caller and the owner each receive.
 *
 * <p><b>What this class measures that the two unit tests of the same route cannot.</b>
 * {@link BalanceQueryControllerTest} and {@link LedgerApiExceptionHandlerTest} stand the controller
 * up with {@code standaloneSetup}, which registers no filter. Both therefore reach the handler
 * without authenticating, and neither can observe the two answers a caller most often meets: the
 * {@code 401} of a missing credential and the {@code 403} of an identity asking for another
 * subject's balance. Those answers are produced by
 * {@code com.carddemo.ledger.config.SecurityConfig}, ahead of every handler, and the module's
 * {@code SecurityConfigTest} exercises its ownership {@code AuthorizationManager} as a unit rather
 * than through a chain. This class closes that distance: the context is the application, the port is
 * real, the requests travel over the Java Development Kit client, and the rows come from the Flyway
 * migrations against a PostgreSQL container.
 *
 * <p><b>What the route discloses, and why it is ownership-scoped.</b> The four values are an account
 * holder's financial position: {@code ACCT-CURR-BAL PIC S9(10)V99} at
 * {@code app/cpy/CVACT01Y.cpy:L7} and the two accumulators at {@code :L13} and {@code :L14}. The
 * source reached them through a signed-on 3270 session, and {@code app/cbl/COSGN00C.cbl} is the only
 * place that establishes an identity: it compares the supplied password at
 * {@code app/cbl/COSGN00C.cbl:L223} and forks on {@code SEC-USR-TYPE} at
 * {@code app/cbl/COSGN00C.cbl:L232-L236}. Those two outcomes are the administrator and ordinary roles
 * here. Ownership is ADDITIVE: the source performed no per-account entitlement check, and a REST
 * route reachable by any authenticated caller would disclose every account to every identity.
 *
 * <p><b>No broker takes part.</b> The listener is stopped before start-up and the broker address
 * points where nothing listens, so this class exercises the synchronous surface alone. The relay
 * sweep and the retention sweep are pushed an hour out for the same reason.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                "USER_SCOPES=SCOPE_ACCOUNT_" + BalanceQueryRouteSecurityIT.OWNED_ACCOUNT_ID,
                "spring.kafka.bootstrap-servers=" + BalanceQueryRouteSecurityIT.UNREACHABLE_BROKER,
                "spring.kafka.listener.auto-startup=false",
                "spring.jpa.hibernate.ddl-auto=validate",
                "carddemo.outbox.relay.fixed-delay-ms=3600000",
                "carddemo.retention.sweep-interval-ms=3600000"
        })
@DisplayName("GET /balances/{accountId} through the real filter chain")
class BalanceQueryRouteSecurityIT {

    /** Host and port the broker client is pointed at, where nothing listens. */
    static final String UNREACHABLE_BROKER = "localhost:1";

    /** Login name of the administrator identity, from {@code ADMIN_USERNAME}. */
    private static final String ADMIN_USERNAME = "admin001";

    /** Password of the administrator identity, plainly synthetic and matching no live credential. */
    static final String ADMIN_PASSWORD = "not-a-real-admin-password";

    /** Login name of the ordinary identity, from {@code USER_USERNAME}. */
    private static final String USER_USERNAME = "user0001";

    /** Password of the ordinary identity, equally synthetic. */
    static final String USER_PASSWORD = "not-a-real-user-password";

    /** Login name of the metrics identity, from {@code MONITORING_USERNAME}. */
    private static final String MONITORING_USERNAME = "monitor01";

    /** Password of the metrics identity, equally synthetic. */
    static final String MONITORING_PASSWORD = "not-a-real-monitoring-password";

    /**
     * The account the ordinary identity is entitled to, granted through {@code USER_SCOPES} above.
     * Row 1 of {@code app/data/ASCII/acctdata.txt}, seeded by {@code V2__seed.sql}.
     */
    static final String OWNED_ACCOUNT_ID = "00000000001";

    /** A seeded account the ordinary identity holds no authority for. Row 2 of the same fixture. */
    private static final String OTHER_ACCOUNT_ID = "00000000002";

    /** An eleven-digit identifier no seeded row holds. */
    private static final String ABSENT_ACCOUNT_ID = "00000099999";

    /** A path value outside the eleven-digit shape the route declares. */
    private static final String MALFORMED_ACCOUNT_ID = "0000000000x";

    /** Seeded balance of the owned account, at the two fractional digits the column holds. */
    private static final String OWNED_BALANCE = "194.00";

    /** Seeded balance of the other account. */
    private static final String OTHER_BALANCE = "158.00";

    /** Both accumulators read this in all 50 fixture records. */
    private static final String ZERO_AT_SCALE_TWO = "0.00";

    /** The four members a security refusal carries, and no fifth. */
    private static final List<String> PROBLEM_MEMBERS =
            List.of("type", "title", "status", "detail");

    /** The fixed detail a 401 carries, from {@code SecurityConfig}. */
    private static final String UNAUTHORIZED_DETAIL = "This request carried no usable credential.";

    /** The fixed detail a 403 carries, from the same class. */
    private static final String FORBIDDEN_DETAIL = "This identity may not use this operation.";

    /** The header every state-changing request carries, from CrossSiteRequestFilter. */
    private static final String CROSS_SITE_HEADER = "X-CardDemo-Request";

    /** Longest one request waits for an answer. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** Reads one response body. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * The one container the module fork runs, which this class reads a login from.
     *
     * <p>{@link LedgerServiceDatabase} owns it and hands this class a database of its own inside it.
     * Nothing here starts or stops a container.
     */
    static final PostgreSQLContainer POSTGRES = LedgerServiceDatabase.container();

    /** The port the embedded container bound. */
    @LocalServerPort
    private int port;

    /** The client every request in this class travels through. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    /** Points the datasource at the container, on the schema the migrations own. */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", BalanceQueryRouteSecurityIT::jdbcUrlOnServiceSchema);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** @return the container URL with the service schema selected */
    private static String jdbcUrlOnServiceSchema() {
        return LedgerServiceDatabase.urlFor(BalanceQueryRouteSecurityIT.class);
    }

    @Nested
    @DisplayName("An entitled caller reads a balance")
    class EntitledCaller {

        @Test
        @DisplayName("the owner reads its own account with the seeded values at scale two")
        void theOwnerReadsItsOwnAccount() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    balanceRoute(OWNED_ACCOUNT_ID)).GET().build());

            assertEquals(200, response.statusCode(), response.body());
            JsonNode balance = JSON.readTree(response.body());
            assertAll(
                    () -> assertEquals(OWNED_ACCOUNT_ID, balance.get("accountId").asString(),
                            "the identifier keeps its ten leading zeros"),
                    () -> assertEquals(OWNED_BALANCE, balance.get("currentBalance").asString(),
                            "the seeded balance of row 1"),
                    () -> assertEquals(ZERO_AT_SCALE_TWO, balance.get("cycleCredit").asString(),
                            "the seeded cycle credit"),
                    () -> assertEquals(ZERO_AT_SCALE_TWO, balance.get("cycleDebit").asString(),
                            "the seeded cycle debit"));
        }

        @Test
        @DisplayName("the administrator reads an account it holds no ownership scope for")
        void theAdministratorReadsAnyAccount() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    balanceRoute(OTHER_ACCOUNT_ID)).GET().build());

            assertEquals(200, response.statusCode(),
                    "the administrator role passes every ownership check, which is what "
                            + "app/cbl/COSGN00C.cbl:L232-L236 forks on: " + response.body());
            assertEquals(OTHER_BALANCE,
                    JSON.readTree(response.body()).get("currentBalance").asString(),
                    "the seeded balance of row 2, so the answer is that account's and not row 1's");
        }

        @Test
        @DisplayName("an account no row holds answers 404 to an entitled caller, not 403")
        void anAbsentAccountAnswersNotFound() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    balanceRoute(ABSENT_ACCOUNT_ID)).GET().build());

            assertEquals(404, response.statusCode(),
                    "the chain permits the container's own error dispatch, so a 404 stays a 404 "
                            + "instead of being authorized again into a 403: " + response.body());
            assertEquals("", response.body(), "the answer carries no body");
        }

        @Test
        @DisplayName("a malformed identifier reaches the route's constraint and answers 400")
        void aMalformedIdentifierAnswersBadRequest() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    balanceRoute(MALFORMED_ACCOUNT_ID)).GET().build());

            assertEquals(400, response.statusCode(), response.body());
            assertEquals(ApiProblem.INVALID_ACCOUNT_ID,
                    JSON.readTree(response.body()).get("detail").asString(),
                    "the documented refusal, which names the shape and no submitted value");
            assertFalse(response.body().contains(MALFORMED_ACCOUNT_ID),
                    "no member echoes the value the caller sent: " + response.body());
        }
    }

    @Nested
    @DisplayName("A caller without an entitlement is refused before the controller")
    class RefusedCaller {

        @Test
        @DisplayName("no credential answers 401, naming the realm and carrying the fixed body")
        void noCredentialAnswersUnauthorized() {
            HttpResponse<String> response = send(HttpRequest
                    .newBuilder(URI.create(baseUri() + balanceRoute(OWNED_ACCOUNT_ID)))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build());

            assertEquals(401, response.statusCode(), response.body());
            assertEquals("Basic realm=\"carddemo\", charset=\"UTF-8\"",
                    response.headers().firstValue("WWW-Authenticate").orElse(""),
                    "the challenge names the realm, so a client knows which credential to present");
            assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL);
        }

        @Test
        @DisplayName("a wrong password answers 401 and never reveals which half was wrong")
        void aWrongPasswordAnswersUnauthorized() {
            HttpResponse<String> response = send(authorized(USER_USERNAME,
                    USER_PASSWORD + "-wrong", balanceRoute(OWNED_ACCOUNT_ID)).GET().build());

            assertEquals(401, response.statusCode(), response.body());
            assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL);
            assertFalse(response.body().contains(USER_USERNAME),
                    "the refusal names no login: " + response.body());
        }

        @Test
        @DisplayName("another subject's balance answers 403 and echoes neither identifier nor route")
        void anotherSubjectsBalanceAnswersForbidden() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    balanceRoute(OTHER_ACCOUNT_ID)).GET().build());

            assertEquals(403, response.statusCode(),
                    "the ordinary identity holds SCOPE_ACCOUNT_" + OWNED_ACCOUNT_ID
                            + " and no authority for " + OTHER_ACCOUNT_ID + ": " + response.body());
            assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL);
            assertFalse(response.body().contains(OTHER_ACCOUNT_ID),
                    "a 403 that echoed the identifier would copy it into every access log: "
                            + response.body());
            assertFalse(response.body().contains("/balances"),
                    "the refusal names no route: " + response.body());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""),
                    "the refusal reaches no cache");
        }

        @Test
        @DisplayName("a malformed identifier answers 403 to an unentitled caller, never 400")
        void aMalformedIdentifierIsRefusedBeforeValidation() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    balanceRoute(MALFORMED_ACCOUNT_ID)).GET().build());

            assertEquals(403, response.statusCode(),
                    "authorization runs in the chain, ahead of the handler's constraint, so a "
                            + "caller with no authority for the value cannot learn its shape was "
                            + "wrong: " + response.body());
            assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL);
        }

        @Test
        @DisplayName("the metrics identity reaches no business route")
        void theMetricsIdentityReachesNoBusinessRoute() {
            HttpResponse<String> response = send(authorized(MONITORING_USERNAME,
                    MONITORING_PASSWORD, balanceRoute(OWNED_ACCOUNT_ID)).GET().build());

            assertEquals(403, response.statusCode(),
                    "ROLE_MONITORING is additive and carries no API access: " + response.body());
            assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL);
        }
    }

    @Nested
    @DisplayName("Default deny covers what no rule names")
    class DefaultDeny {

        @Test
        @DisplayName("the administrator is refused a method the one rule does not name")
        void anUnnamedMethodIsRefusedForTheAdministrator() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                            balanceRoute(OWNED_ACCOUNT_ID))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());

            assertEquals(403, response.statusCode(),
                    "the one rule names GET alone, and the chain ends in denyAll, so a POST is "
                            + "refused even for the administrator: " + response.body());
            assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL);
        }

        @Test
        @DisplayName("the administrator is refused a path no rule names")
        void anUnnamedPathIsRefusedForTheAdministrator() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/transactions").GET().build());

            assertEquals(403, response.statusCode(),
                    "a path this chain names in no rule is refused rather than answered 404, so "
                            + "adding a route without deciding who may reach it fails closed: "
                            + response.body());
        }

        @Test
        @DisplayName("an anonymous caller on an unnamed path is challenged rather than refused")
        void anUnnamedPathChallengesAnAnonymousCaller() {
            HttpResponse<String> response = send(HttpRequest
                    .newBuilder(URI.create(baseUri() + "/transactions"))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build());

            assertEquals(401, response.statusCode(),
                    "an unauthenticated caller is asked for a credential first: "
                            + response.body());
        }
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
        assertEquals("application/problem+json",
                response.headers().firstValue("Content-Type").orElse("").split(";")[0],
                "the media type RFC 9457 names");
        JsonNode body = JSON.readTree(response.body());
        assertEquals(PROBLEM_MEMBERS.size(), body.size(),
                "the body carries the four declared members and no fifth: " + response.body());
        for (String member : PROBLEM_MEMBERS) {
            assertTrue(body.has(member), "the body carries " + member + ": " + response.body());
        }
        assertEquals(ApiProblem.ABOUT_BLANK, body.get("type").asString(), "the problem type");
        assertEquals(title, body.get("title").asString(), "the title of this class of failure");
        assertEquals(status, body.get("status").asInt(), "the status repeated in the body");
        assertEquals(detail, body.get("detail").asString(), "the fixed detail");
        assertFalse(body.has("path"), "no member echoes the resolved request path");
        assertFalse(body.has("timestamp"), "the framework body carried a timestamp");
    }

    /** @return the route of one account's balance */
    private static String balanceRoute(String accountId) {
        return "/balances/" + accountId;
    }

    /** @return the origin the embedded container bound */
    private String baseUri() {
        return "http://localhost:" + port;
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
                .header("Accept", "application/json")
                // config/CrossSiteRequestFilter runs ahead of the security chain and refuses a
                // state-changing request that omits this header, so a test measuring a route rule
                // has to present it. Nothing here is testing that filter, and a safe method ignores
                // the header entirely.
                .header(CROSS_SITE_HEADER, "route-security");
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
