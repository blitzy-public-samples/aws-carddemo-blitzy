package com.carddemo.fraud.api;

import com.carddemo.fraud.FraudServiceDatabase;
import com.carddemo.fraud.TestIdentityPasswords;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives every business route of this service through the real filter chain, and reads back what an
 * unauthenticated caller, an unentitled caller and the administrator each receive.
 *
 * <p><b>What this class measures that the unit tests of the same routes cannot.</b> The controller tests here
 * stand the handler up without a filter, so they reach it without authenticating and can observe
 * neither the {@code 401} of a missing credential nor the {@code 403} of an identity reaching for
 * something it does not hold. The module's {@code SecurityConfigTest} exercises the ownership
 * {@code AuthorizationManager} as a unit rather than through a chain. Both answers are produced by
 * {@code config/SecurityConfig} ahead of every handler, so only a request that traverses that chain
 * can establish them.
 *
 * <p><b>The chain under test is the production one.</b> Every request below is dispatched through the
 * {@code springSecurityFilterChain} bean this service builds, installed ahead of the dispatcher by
 * {@link MockMvcBuilders#webAppContextSetup}. No security is stubbed, replaced or bypassed: the
 * refusals read back here are written by the entry point and the access-denied handler of
 * {@code config/SecurityConfig} itself.
 *
 * <p><b>This transport rather than a socket client.</b> This module may hold no outbound client
 * type at all. {@code FraudApiContractTest.noSourceHoldsAnOutboundClientType} scans the production
 * <em>and</em> test sources of this service and fails on one, because a fraud consumer that can call
 * out is a fraud consumer that can couple itself to a sibling service — the coupling the platform
 * forbids. A harness holding a Hypertext Transfer Protocol (HTTP) client would defeat that guard for
 * the sake of testing it, so the chain is driven in the transport this module already uses for the
 * same purpose in {@code ConfigurationInvariantsIT}. The filter chain, the entry point, the
 * access-denied handler and the rendered body are identical either way; only the socket is absent.
 *
 * <p><b>What is asserted.</b> For each route: no credential is challenged with {@code 401} and a
 * {@code WWW-Authenticate} header; an authenticated identity that does not hold the route is refused
 * with {@code 403}; the administrator reaches the handler; and every refusal body is the fixed
 * four-member problem document, carrying no identifier, no route and no timestamp. The metrics
 * identity is asserted to reach no business route, and a path the chain names in no rule is asserted
 * to fail closed rather than answer {@code 404}.
 *
 * <p><b>Redaction.</b> Each refusal detail is compared literally. A reworded detail that helpfully
 * named the route or the subject would copy that wording into every caller's log, so the two
 * sentences are pinned here as well as in the interface description.
 *
 * <p>The rules under test are declared at {@code config/SecurityConfig}:
 * <ul><li>{@code GET /fraud-assessments/**} requires {@code ROLE_ADMIN}. An ownership authority is
 *     not read here and is not a way past the rule.</li>
 * <li>Every other path and method is denied.</li></ul>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                "USER_SCOPES=" + FraudRouteSecurityIT.USER_SCOPES,
                "spring.kafka.bootstrap-servers=" + FraudRouteSecurityIT.UNREACHABLE_BROKER,
                "spring.kafka.listener.auto-startup=false",
                "spring.jpa.hibernate.ddl-auto=validate",
                "management.server.port=${server.port}",
                "carddemo.outbox.relay.fixed-delay-ms=3600000",
                "carddemo.retention.sweep-interval-ms=3600000"
        })
@DisplayName("Every fraud business route through the real filter chain")
class FraudRouteSecurityIT {

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

    /** A transaction identifier no assessment row carries, at DALYTRAN-ID PIC X(16) width. */
    private static final String ABSENT_TRANSACTION_ID = "0000000000000001";

    /** Authorities the ordinary identity carries, one per entitlement it holds. */
    static final String USER_SCOPES = "SCOPE_ACCOUNT_00000000001";

    /** The four members {@code config/SecurityConfig} writes into a refusal, and no fifth. */
    private static final List<String> PROBLEM_MEMBERS =
            List.of("type", "title", "status", "detail");

    /** The problem type a refusal carries, since no type is minted for these. */
    private static final String ABOUT_BLANK = "about:blank";

    /** The sentence a 401 carries, fixed at {@code config/SecurityConfig}. */
    private static final String UNAUTHORIZED_DETAIL = "This request carried no usable credential.";

    /** The sentence a 403 carries, fixed at {@code config/SecurityConfig}. */
    private static final String FORBIDDEN_DETAIL = "This identity may not use this operation.";

    /** Name of the bean holding the production chain, installed ahead of the dispatcher. */
    private static final String CHAIN_BEAN = "springSecurityFilterChain";

    /** Reads one response body. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * The one container the module fork runs, which this class reads a login from.
     *
     * <p>{@link FraudServiceDatabase} owns it and hands this class a database of its own inside
     * it. Nothing here starts or stops a container.
     */
    static final PostgreSQLContainer POSTGRES = FraudServiceDatabase.container();

    /** The context the dispatcher and the chain are both taken from. */
    @Autowired
    private WebApplicationContext webContext;

    /**
     * Points the datasource at the container, on the schema the migrations own.
     *
     * @param registry the registry the test context reads these values from
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", FraudRouteSecurityIT::jdbcUrlOnServiceSchema);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Returns the container connection string with the service schema selected.
     *
     * @return the connection string
     */
    private static String jdbcUrlOnServiceSchema() {
        return FraudServiceDatabase.urlFor(FraudRouteSecurityIT.class);
    }

    @Nested
    @DisplayName("The administrator-only assessment routes")
    class TheAssessmentRoutes {

        /**
         * Asserts the administrator reaches the handler.
         *
         * <p>No assessment row is seeded, so the handler answers 404. That is the point: the status
         * comes from the handler rather than from the chain, which is what proves the chain admitted
         * the request.
         *
         * @throws Exception if the dispatch fails
         */
        @Test
        @DisplayName("the administrator reaches the handler, which answers for itself")
        void theAdministratorReachesTheHandler() throws Exception {
            MockHttpServletResponse response = perform(
                    authorized(get(assessmentRoute(ABSENT_TRANSACTION_ID)),
                            ADMIN_USERNAME, ADMIN_PASSWORD));

            assertAll(
                    () -> assertEquals(404, response.getStatus(),
                            "the handler answered for a transaction it holds no assessment of: "
                                    + response.getContentAsString()),
                    () -> assertNotEquals(403, response.getStatus(),
                            "the chain admitted the administrator"));
        }

        /**
         * Asserts an ordinary identity is refused even while holding an ownership authority.
         *
         * <p>This route is granted by role alone. The ordinary identity below carries an account
         * authority, so the refusal establishes that holding a scope is not a way past a rule that
         * asks for a role.
         *
         * @throws Exception if the dispatch fails
         */
        @Test
        @DisplayName("an ordinary identity holding a scope is still refused")
        void anOrdinaryIdentityHoldingAScopeIsStillRefused() throws Exception {
            MockHttpServletResponse response = perform(
                    authorized(get(assessmentRoute(ABSENT_TRANSACTION_ID)),
                            USER_USERNAME, USER_PASSWORD));

            assertAll(
                    () -> assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL),
                    () -> assertNotEquals(404, response.getStatus(),
                            "a 404 would say whether that assessment exists"));
        }

        /**
         * Asserts the account-scoped collection route is administrator-only as well.
         *
         * @throws Exception if the dispatch fails
         */
        @Test
        @DisplayName("the collection route is administrator-only too")
        void theCollectionRouteIsAdministratorOnly() throws Exception {
            MockHttpServletResponse refused = perform(authorized(
                    get(COLLECTION_ROUTE).param(ACCOUNT_PARAMETER, OWNED_ACCOUNT_ID),
                    USER_USERNAME, USER_PASSWORD));
            MockHttpServletResponse admitted = perform(authorized(
                    get(COLLECTION_ROUTE).param(ACCOUNT_PARAMETER, OWNED_ACCOUNT_ID),
                    ADMIN_USERNAME, ADMIN_PASSWORD));

            assertAll(
                    () -> assertProblem(refused, 403, "Forbidden", FORBIDDEN_DETAIL),
                    () -> assertNotEquals(403, admitted.getStatus(),
                            "the administrator reaches it: " + admitted.getContentAsString()),
                    () -> assertNotEquals(401, admitted.getStatus(),
                            "and is not challenged: " + admitted.getContentAsString()));
        }

        /**
         * Asserts a caller carrying no credential is challenged.
         *
         * @throws Exception if the dispatch fails
         */
        @Test
        @DisplayName("no credential is challenged with 401 and a scheme to retry with")
        void noCredentialIsChallenged() throws Exception {
            MockHttpServletResponse response =
                    perform(get(assessmentRoute(ABSENT_TRANSACTION_ID)));

            assertAll(
                    () -> assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL),
                    () -> assertTrue(String.valueOf(response.getHeader("WWW-Authenticate"))
                                    .startsWith("Basic realm=\"carddemo\""),
                            "a 401 names the scheme and realm a caller should retry with"));
        }

        /**
         * Asserts a wrong password is challenged rather than refused.
         *
         * @throws Exception if the dispatch fails
         */
        @Test
        @DisplayName("a wrong password is challenged rather than refused")
        void aWrongPasswordIsChallenged() throws Exception {
            MockHttpServletResponse response = perform(
                    authorized(get(assessmentRoute(ABSENT_TRANSACTION_ID)),
                            ADMIN_USERNAME, "not-the-password"));

            assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL);
        }

        /**
         * Asserts the metrics identity reaches no business route.
         *
         * @throws Exception if the dispatch fails
         */
        @Test
        @DisplayName("the metrics identity reaches no business route")
        void theMetricsIdentityReachesNoBusinessRoute() throws Exception {
            MockHttpServletResponse response = perform(
                    authorized(get(assessmentRoute(ABSENT_TRANSACTION_ID)),
                            MONITORING_USERNAME, MONITORING_PASSWORD));

            assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL);
        }

        /**
         * Asserts a method this chain names in no rule fails closed.
         *
         * @throws Exception if the dispatch fails
         */
        @Test
        @DisplayName("a method named in no rule fails closed for the administrator")
        void anUnnamedMethodFailsClosed() throws Exception {
            MockHttpServletResponse response = perform(
                    authorized(delete(assessmentRoute(ABSENT_TRANSACTION_ID)),
                            ADMIN_USERNAME, ADMIN_PASSWORD));

            assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL);
        }

        /**
         * Asserts a path this chain names in no rule fails closed rather than answering 404.
         *
         * @throws Exception if the dispatch fails
         */
        @Test
        @DisplayName("a path named in no rule fails closed rather than answering 404")
        void anUnnamedPathFailsClosed() throws Exception {
            MockHttpServletResponse response = perform(
                    authorized(get(UNMAPPED_ROUTE), ADMIN_USERNAME, ADMIN_PASSWORD));

            assertAll(
                    () -> assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL),
                    () -> assertNotEquals(404, response.getStatus(),
                            "adding a route without deciding who may reach it must fail closed"));
        }
    }

    /** The collection route the controller publishes below its base path. */
    private static final String COLLECTION_ROUTE = "/fraud-assessments";

    /** The query parameter the collection route reads. */
    private static final String ACCOUNT_PARAMETER = "accountId";

    /** The account the ordinary identity holds an authority for, per {@link #USER_SCOPES}. */
    private static final String OWNED_ACCOUNT_ID = "00000000001";

    /** A path this service maps in no rule and no handler. */
    private static final String UNMAPPED_ROUTE = "/velocity-windows";

    /**
     * Returns the route of one transaction's assessment.
     *
     * @param transactionId the transaction
     * @return the route
     */
    private static String assessmentRoute(String transactionId) {
        return COLLECTION_ROUTE + "/" + transactionId;
    }

    /**
     * Adds one identity's credential to a request.
     *
     * @param request  the request being built
     * @param username the login name
     * @param password the password, synthetic in every case
     * @return the same builder, carrying the credential
     */
    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request,
            String username, String password) {
        String credential = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return request.header("Authorization", "Basic " + credential);
    }

    /**
     * Dispatches one request through the production security chain and the dispatcher behind it.
     *
     * @param request the request to dispatch
     * @return the response the chain or the handler wrote
     * @throws Exception if the dispatch fails
     */
    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request)
            throws Exception {
        MockMvc chain = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(webContext.getBean(CHAIN_BEAN, Filter.class))
                .build();
        return chain.perform(request.header("Accept", "application/json"))
                .andReturn().getResponse();
    }

    /**
     * Asserts one refusal is the fixed problem document the security chain writes.
     *
     * @param response the response to read
     * @param status   the status the answer carries
     * @param title    the title the body carries
     * @param detail   the fixed detail the body carries
     * @throws Exception if the body cannot be read
     */
    private static void assertProblem(MockHttpServletResponse response, int status, String title,
            String detail) throws Exception {
        String rendered = response.getContentAsString();
        assertEquals(status, response.getStatus(), "the status: " + rendered);
        assertEquals("application/problem+json",
                String.valueOf(response.getContentType()).split(";")[0],
                "the media type RFC 9457 names");
        assertEquals("no-store", response.getHeader("Cache-Control"),
                "a refusal is kept out of every cache");
        JsonNode body = JSON.readTree(rendered);
        assertEquals(PROBLEM_MEMBERS.size(), body.size(),
                "the body carries the four declared members and no fifth: " + rendered);
        for (String member : PROBLEM_MEMBERS) {
            assertTrue(body.has(member), "the body carries " + member + ": " + rendered);
        }
        assertEquals(ABOUT_BLANK, body.get("type").asString(), "the problem type");
        assertEquals(title, body.get("title").asString(), "the title of this class of failure");
        assertEquals(status, body.get("status").asInt(), "the status repeated in the body");
        assertEquals(detail, body.get("detail").asString(), "the fixed detail");
        assertFalse(body.has("path"), "no member echoes the resolved request path");
        assertFalse(body.has("timestamp"), "the framework body carried a timestamp");
        assertFalse(rendered.contains(USER_USERNAME), "no refusal names the caller");
    }
}
