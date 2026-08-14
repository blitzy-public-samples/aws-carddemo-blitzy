package com.carddemo.card.api;

import com.carddemo.card.CardServiceDatabase;
import com.carddemo.card.TestIdentityPasswords;
import com.carddemo.card.config.CrossSiteRequestFilter;
import com.carddemo.cobol.PanMasker;

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
 * <ul><li>{@code GET /cards} requires the account-ownership authority for the {@code accountId}
 *     request parameter. An absent parameter denies.</li>
 * <li>{@code GET /cards/{cardToken}} requires the card authority naming the path value, which is
 *     {@code SCOPE_CARD_} followed by the token of that number. Holding the account the card
 *     belongs to is not the same authority.</li>
 * <li>{@code PUT /cards/{cardToken}} requires {@code ROLE_ADMIN} alone, so neither account
 *     ownership nor card ownership is a way to update a card.</li>
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
                "USER_SCOPES=" + CardRouteSecurityIT.USER_SCOPES,
                "spring.kafka.bootstrap-servers=" + CardRouteSecurityIT.UNREACHABLE_BROKER,
                "spring.kafka.listener.auto-startup=false",
                "spring.jpa.hibernate.ddl-auto=validate",
                "carddemo.outbox.relay.fixed-delay-ms=3600000",
                "carddemo.retention.sweep-interval-ms=3600000"
        })
@DisplayName("Every card business route through the real filter chain")
class CardRouteSecurityIT {

    /** Host and port the broker client is pointed at, where nothing listens. */
    static final String UNREACHABLE_BROKER = "localhost:1";

    /** The administrator login, which every ownership rule admits. */
    private static final String ADMIN_USERNAME = "admin001";

    /** Password of the administrator, synthetic and encoded with the noop prefix. */
    static final String ADMIN_PASSWORD = "not-a-real-admin-password";

    /** The ordinary login, which holds only what {@link #USER_SCOPES} names. */
    private static final String USER_USERNAME = "user0001";

    /** Password of the ordinary identity. */
    static final String USER_PASSWORD = "not-a-real-user-password";

    /** The metrics login, which carries the monitoring role and reaches no business route. */
    private static final String MONITORING_USERNAME = "monitor01";

    /** Password of the metrics identity. */
    static final String MONITORING_PASSWORD = "not-a-real-monitoring-password";

    /** Account the ordinary identity holds, from {@code app/data/ASCII/cardxref.txt}. */
    private static final String OWNED_ACCOUNT_ID = "00000000050";

    /** An account the ordinary identity does not hold. */
    private static final String OTHER_ACCOUNT_ID = "00000000001";

    /**
     * The seeded card of {@link #OWNED_ACCOUNT_ID}, from {@code app/data/ASCII/carddata.txt}.
     *
     * <p>Row 42 of {@code V2__seed.sql} carries this number against account {@code 00000000050},
     * which is why the ordinary identity can hold both authorities for it.
     */
    private static final String OWNED_CARD_NUMBER = "0500024453765740";

    /**
     * A seeded card of another account, which the ordinary identity holds no authority for.
     *
     * <p>{@code V2__seed.sql} carries it against account {@code 00000000027}, and the ordinary
     * identity holds neither that account nor this card, so a refusal on this number is the
     * ownership rule of the route working rather than an absent row.
     */
    private static final String UNHELD_CARD_NUMBER = "0683586198171516";

    /**
     * The statuses the handler itself may answer once the chain has admitted a request.
     *
     * <p>{@code 403} is among them, which is why no assertion below reads a status alone. The detail
     * handler answers {@code 403} with its own body when a search condition matches no card, carrying
     * the message {@value #NO_CARD_FOUND_MESSAGE}. A caller cannot tell that apart from a refusal by
     * status, and neither can a test: the two are told apart by their bodies, which is what
     * {@link #assertNotAChainRefusal} does.
     */
    private static final List<Integer> HANDLER_ANSWERS = List.of(200, 400, 403, 404, 409, 422);

    /** The message the detail handler answers with when its search condition matches no card. */
    private static final String NO_CARD_FOUND_MESSAGE =
            "Did not find cards for this search condition";

    /**
     * Authorities the ordinary identity carries, one per entitlement it holds.
     *
     * <p>The card authority names a token rather than a number, and a token is derived under the
     * configured key, so it cannot be written as a constant here. {@link #identityProperties} adds
     * it while the context starts.
     */
    static final String USER_SCOPES = "SCOPE_ACCOUNT_" + OWNED_ACCOUNT_ID;

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
     * <p>{@link CardServiceDatabase} owns it and hands this class a database of its own inside it.
     * Nothing here starts or stops a container.
     */
    static final PostgreSQLContainer POSTGRES = CardServiceDatabase.container();

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
        registry.add("spring.datasource.url", CardRouteSecurityIT::jdbcUrlOnServiceSchema);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Grants the ordinary identity the account it holds and the one card it holds.
     *
     * <p>{@code config/SecurityConfig.ownsCardNumberPathVariable} derives the card token from the
     * path value and requires {@code SCOPE_CARD_} followed by that token. The token comes from
     * {@link PanMasker#cardToken(String)} under the configured key, so it is not a constant and
     * cannot be written into the annotation above.
     *
     * @param registry the registry the test context reads these values from
     */
    @DynamicPropertySource
    static void identityProperties(DynamicPropertyRegistry registry) {
        registry.add("USER_SCOPES",
                () -> USER_SCOPES + ",SCOPE_CARD_" + PanMasker.cardToken(OWNED_CARD_NUMBER));
    }

    /**
     * Returns the container connection string with the service schema selected.
     *
     * @return the connection string
     */
    private static String jdbcUrlOnServiceSchema() {
        return CardServiceDatabase.urlFor(CardRouteSecurityIT.class);
    }

    @Nested
    @DisplayName("Listing cards, which is scoped by the account parameter")
    class TheListRoute {

        /** Asserts the holder of the account reads its own cards. */
        @Test
        @DisplayName("the holder of the account reads its own cards")
        void theHolderReadsItsOwnCards() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    listRoute(OWNED_ACCOUNT_ID)).GET().build());

            assertEquals(200, response.statusCode(),
                    "the identity holding SCOPE_ACCOUNT_ for this account reaches the handler: "
                            + response.body());
        }

        /** Asserts the administrator lists any account's cards. */
        @Test
        @DisplayName("the administrator lists any account's cards")
        void theAdministratorListsAnyAccount() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    listRoute(OTHER_ACCOUNT_ID)).GET().build());

            assertEquals(200, response.statusCode(),
                    "ROLE_ADMIN passes every ownership check: " + response.body());
        }

        /** Asserts another account's cards are refused, revealing nothing about them. */
        @Test
        @DisplayName("another account's cards are refused, and the body reveals nothing")
        void anotherAccountsCardsAreRefused() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    listRoute(OTHER_ACCOUNT_ID)).GET().build());

            assertAll(
                    () -> assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL),
                    () -> assertFalse(response.body().contains(OTHER_ACCOUNT_ID),
                            "the refusal does not echo the account that was asked for"));
        }

        /** Asserts a caller carrying no credential is challenged. */
        @Test
        @DisplayName("no credential is challenged with 401 and a scheme to retry with")
        void noCredentialIsChallenged() {
            HttpResponse<String> response = send(anonymous(listRoute(OWNED_ACCOUNT_ID))
                    .GET().build());

            assertAll(
                    () -> assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL),
                    () -> assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("")
                                    .startsWith("Basic realm=\"carddemo\""),
                            "a 401 names the scheme and realm a caller should retry with"));
        }

        /**
         * Asserts an absent account parameter is answered as the malformed request it is.
         *
         * <p>A request that names no account names no subject, so the ownership rule has nothing to
         * compare and the route's own required parameter answers it 400 with the text
         * {@code src/main/resources/openapi.yaml} publishes. Refusing it in the chain answered 403,
         * which told an administrator its entitlement was wrong when its request was, and it did so
         * without listing anything: the reply carries one text and no page either way.
         */
        @Test
        @DisplayName("an absent account parameter reads the route's own refusal, not a 403")
        void anAbsentAccountParameterIsABadRequest() {
            HttpResponse<String> response =
                    send(authorized(USER_USERNAME, USER_PASSWORD, "/cards").GET().build());

            assertAll(
                    () -> assertEquals(400, response.statusCode(),
                            "the request is malformed rather than unentitled: " + response.body()),
                    () -> assertTrue(
                            response.body().contains(CardController.ACCOUNT_ID_ABSENT_MESSAGE),
                            "the published text reaches the caller, and the body read: "
                                    + response.body()),
                    () -> assertFalse(response.body().contains("cardNumber"),
                            "no page is listed for a request that named no account"));
        }

        /**
         * Asserts the same request with no credential is still challenged rather than answered.
         *
         * <p>This is the boundary the deferral above must not cross. A required parameter is not a
         * way to reach a route without authenticating, so an anonymous caller reads 401 whether it
         * names an account or not.
         */
        @Test
        @DisplayName("an absent account parameter with no credential is still challenged")
        void anAbsentAccountParameterWithNoCredentialIsChallenged() {
            HttpResponse<String> response = send(anonymous("/cards").GET().build());

            assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL);
        }

        /**
         * Asserts an administrator, whose entitlement covers every account, reads the same 400.
         *
         * <p>This is the case the finding reported: an identity that may list any account was told
         * it may not use the operation, because the rule read a parameter that was never sent.
         */
        @Test
        @DisplayName("the administrator reads the route's refusal rather than a 403")
        void theAdministratorReadsTheRoutesRefusal() {
            HttpResponse<String> response =
                    send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD, "/cards").GET().build());

            assertAll(
                    () -> assertEquals(400, response.statusCode(),
                            "an entitlement that covers every account cannot be the reason: "
                                    + response.body()),
                    () -> assertTrue(
                            response.body().contains(CardController.ACCOUNT_ID_ABSENT_MESSAGE),
                            "the published text reaches the caller, and the body read: "
                                    + response.body()));
        }

        @Test
        @DisplayName("the metrics identity reaches no business route")
        void theMetricsIdentityReachesNoBusinessRoute() {
            HttpResponse<String> response = send(authorized(MONITORING_USERNAME,
                    MONITORING_PASSWORD, listRoute(OWNED_ACCOUNT_ID)).GET().build());

            assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL);
        }
    }

    @Nested
    @DisplayName("Reading one card, which is scoped by the card the path names")
    class TheDetailRoute {

        /** Asserts the holder of the card reads it. */
        @Test
        @DisplayName("the holder of the card reaches the handler")
        void theHolderOfTheCardReachesTheHandler() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    cardRoute(OWNED_CARD_NUMBER)).GET().build());

            assertAll(
                    () -> assertNotEquals(401, response.statusCode(), response.body()),
                    () -> assertNotAChainRefusal(response),
                    () -> assertTrue(HANDLER_ANSWERS.contains(response.statusCode()),
                            "the answer came from the handler: " + response.statusCode()));
        }

        @Test
        @DisplayName("the administrator reads any card")
        void theAdministratorReadsAnyCard() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    cardRoute(UNHELD_CARD_NUMBER)).GET().build());

            assertAll(
                    () -> assertNotEquals(401, response.statusCode(), response.body()),
                    () -> assertNotAChainRefusal(response),
                    () -> assertTrue(HANDLER_ANSWERS.contains(response.statusCode()),
                            "ROLE_ADMIN passes every ownership check: " + response.statusCode()));
        }

        /**
         * Asserts a card the caller does not hold is refused, revealing nothing about it.
         *
         * <p>The row exists and the caller holds no authority for it, so the refusal establishes
         * that the rule reads the card the path names rather than answering on the account
         * authority the same identity does hold. The answer is the same whether or not the row
         * exists.
         */
        @Test
        @DisplayName("a card the caller does not hold is refused, and the body reveals nothing")
        void aCardTheCallerDoesNotHoldIsRefused() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    cardRoute(UNHELD_CARD_NUMBER)).GET().build());

            assertAll(
                    () -> assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL),
                    () -> assertFalse(response.body().contains(UNHELD_CARD_NUMBER),
                            "the refusal does not echo the card number that was asked for"));
        }

        /** Asserts a caller carrying no credential is challenged before the path is read. */
        @Test
        @DisplayName("an unauthenticated caller is challenged before the path is read")
        void anUnauthenticatedCallerIsChallengedBeforeThePathIsRead() {
            HttpResponse<String> response =
                    send(anonymous(cardRoute(OWNED_CARD_NUMBER)).GET().build());

            assertAll(
                    () -> assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL),
                    () -> assertNotEquals(422, response.statusCode(),
                            "the path value was never edited, because the chain answered first"));
        }

        @Test
        @DisplayName("the metrics identity reaches no business route")
        void theMetricsIdentityReachesNoBusinessRoute() {
            HttpResponse<String> response = send(authorized(MONITORING_USERNAME,
                    MONITORING_PASSWORD, cardRoute(OWNED_CARD_NUMBER)).GET().build());

            assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL);
        }
    }

    @Nested
    @DisplayName("Updating one card, which the administrator alone may do")
    class TheUpdateRoute {

        /**
         * Asserts an ordinary identity may not update a card, even one of its own account.
         *
         * <p>This is the rule most easily loosened by accident. The update route is granted by role
         * alone, and the ordinary identity below holds the account the card belongs to, so the refusal
         * establishes that ownership of the account is not a way to update its cards.
         */
        @Test
        @DisplayName("an ordinary identity holding the account and the card is still refused")
        void anOrdinaryIdentityHoldingTheAccountIsStillRefused() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    cardRoute(OWNED_CARD_NUMBER)).PUT(jsonBody(oneUpdateRequest())).build());

            assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL);
        }

        @Test
        @DisplayName("the administrator reaches the handler")
        void theAdministratorReachesTheHandler() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    cardRoute(OWNED_CARD_NUMBER)).PUT(jsonBody(oneUpdateRequest())).build());

            assertAll(
                    () -> assertNotEquals(401, response.statusCode(), response.body()),
                    () -> assertNotAChainRefusal(response),
                    () -> assertTrue(HANDLER_ANSWERS.contains(response.statusCode()),
                            "the answer came from the handler: " + response.statusCode() + " "
                                    + response.body()));
        }

        /** Asserts a caller carrying no credential is challenged. */
        @Test
        @DisplayName("no credential is challenged")
        void noCredentialIsChallenged() {
            HttpResponse<String> response = send(anonymous(cardRoute(OWNED_CARD_NUMBER))
                    .PUT(jsonBody(oneUpdateRequest())).build());

            assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL);
        }
    }

    @Nested
    @DisplayName("What this chain names in no rule")
    class UnnamedRoutes {

        /** Asserts a path this chain names in no rule fails closed rather than answering 404. */
        @Test
        @DisplayName("a path named in no rule fails closed rather than answering 404")
        void anUnnamedPathFailsClosed() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/cross-references").GET().build());

            assertAll(
                    () -> assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL),
                    () -> assertNotEquals(404, response.statusCode(),
                            "adding a route without deciding who may reach it must fail closed"));
        }

        /** Asserts a method this chain names in no rule fails closed. */
        @Test
        @DisplayName("deleting a card is named in no rule and fails closed")
        void deletingACardFailsClosed() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/cards").DELETE().build());

            assertAll(
                    () -> assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL),
                    () -> assertNotEquals(405, response.statusCode(),
                            "the chain answers before the dispatcher reports a method mismatch"));
        }
    }

    /**
     * Asserts one answer came from the handler rather than from the security chain.
     *
     * <p>Reading the status is not enough on this service, because the detail handler answers
     * {@code 403} of its own for a search condition that matches no card. The chain's refusal is the
     * fixed problem document, so the two are told apart by media type and by the fixed detail. A test
     * that read the status alone would call a handler answer a refusal, and would also call a refusal
     * a handler answer once the handler stopped using that status.
     *
     * @param response the answer to read
     */
    private static void assertNotAChainRefusal(HttpResponse<String> response) {
        String mediaType =
                response.headers().firstValue("Content-Type").orElse("").split(";")[0];
        assertNotEquals("application/problem+json", mediaType,
                "the chain refused this request rather than admitting it: " + response.body());
        assertFalse(response.body().contains(FORBIDDEN_DETAIL),
                "the body carries the chain's refusal sentence: " + response.body());
        assertFalse(response.body().contains(UNAUTHORIZED_DETAIL),
                "the body carries the chain's challenge sentence: " + response.body());
    }

    /**
     * Returns the route listing one account's cards.
     *
     * @param accountId the account
     * @return the route
     */
    private static String listRoute(String accountId) {
        return "/cards?accountId=" + accountId;
    }

    /**
     * Builds the route of one card, naming it by the token the mapping declares.
     *
     * <p>The number is tokenized here rather than sent, so no request of this class carries a card
     * number in a request line. The authority granted to the ordinary identity names the same token,
     * which is what lets the ownership rule compare the two directly.
     *
     * @param cardNumber the sixteen digits this method tokenizes
     * @return the request path
     */
    private static String cardRoute(String cardNumber) {
        return "/cards/" + PanMasker.cardToken(cardNumber);
    }

    /**
     * Builds one card-update request body.
     *
     * <p>The chain runs ahead of validation, so the body only has to be readable. Its contents decide
     * which handler status follows, and every assertion above reads the chain's verdict rather than
     * that status. The card number is absent by design: it names the row and travels in the path.
     *
     * @return the request document
     */
    private static String oneUpdateRequest() {
        return """
                {
                  "embossedName": "Aniya Von",
                  "activeStatus": "Y",
                  "expiryYear": "2099",
                  "expiryMonth": "12",
                  "expiryDay": "31"
                }
                """;
    }

    /**
     * Wraps one document as a JavaScript Object Notation request body.
     *
     * @param document the body text
     * @return the publisher
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
