package com.carddemo.account.api;

import com.carddemo.account.AccountServiceDatabase;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.account.TestIdentityPasswords;
import com.carddemo.account.config.CrossSiteRequestFilter;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives all four business routes of this service through the real filter chain over a real port,
 * once per identity that can reach them.
 *
 * <h2>What this class covers beside {@code AccountControllerIT}</h2>
 *
 * <p>The controller integration test asserts what each route answers when the caller is entitled. It
 * reads a customer as the administrator and never as the holder, so
 * {@code config/SecurityConfig.CUSTOMER_SCOPE} — one of the two ownership authorities this service
 * declares — was never exercised by any test in the module. Nor did any test present no credential,
 * a wrong password or the metrics identity to a business route, or ask what an ordinary
 * authenticated caller receives from the two administrator-only routes.</p>
 *
 * <p>A gap of that shape does not fail: it passes, and the answer nobody asked for is the one a
 * caller finds. This class asks for all of them.</p>
 *
 * <h2>The matrix</h2>
 *
 * <p>Each of the four routes is driven by the identity that holds it, by the administrator, by an
 * authenticated identity that does not hold it, by the metrics identity, by a caller presenting no
 * credential, and by a caller presenting the right login with the wrong password. The rules under
 * test are declared at {@code config/SecurityConfig}:</p>
 *
 * <ul><li>{@code POST /accounts/{accountId}/cycle-close} requires {@code ROLE_ADMIN} alone, so
 *     holding the account is not a way to close its billing cycle.</li>
 * <li>{@code PUT /accounts/{accountId}} requires {@code ROLE_ADMIN} alone, for the same reason.</li>
 * <li>{@code GET /accounts/{accountId}} requires the account authority naming the path value.</li>
 * <li>{@code GET /customers/{customerId}} requires the customer authority naming the path value,
 *     which is a different authority from the account one even where one subject holds both.</li>
 * <li>Every other path and method is denied.</li></ul>
 *
 * <h2>What a refusal must not carry, and must not do</h2>
 *
 * <p>Every refusal body is compared literally against the fixed four-member problem document, so a
 * reworded detail that helpfully named the route or the subject would fail here rather than reaching
 * every caller's log. And every refused state change is followed by a read of the row and of the
 * outbox: a refusal that answered {@code 403} after writing would be a worse defect than one that
 * answered {@code 200}, because nothing downstream would report it.</p>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}. Route inventory:
 * {@code src/main/resources/openapi.yaml}.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                "USER_SCOPES=" + AccountRouteSecurityIT.USER_SCOPES,
                "spring.kafka.bootstrap-servers=" + AccountRouteSecurityIT.UNREACHABLE_BROKER,
                "spring.kafka.listener.auto-startup=false",
                "carddemo.outbox.relay.fixed-delay-ms=3600000",
                "carddemo.retention.sweep-interval-ms=3600000"
        })
@DisplayName("Every account business route through the real filter chain")
class AccountRouteSecurityIT {

    /** Host and port the broker client is pointed at, where nothing listens. */
    static final String UNREACHABLE_BROKER = "localhost:1";

    /** The administrator login, which every rule of this chain admits. */
    private static final String ADMIN_USERNAME = "admin001";

    /** Password of the administrator, plainly synthetic. */
    private static final String ADMIN_PASSWORD = TestIdentityPasswords.ADMIN_PASSWORD;

    /** The ordinary login, which holds only what {@link #USER_SCOPES} names. */
    private static final String USER_USERNAME = "user0001";

    /** Password of the ordinary identity. */
    private static final String USER_PASSWORD = TestIdentityPasswords.USER_PASSWORD;

    /** The metrics login, which carries the monitoring role and reaches no business route. */
    private static final String MONITORING_USERNAME = "monitor01";

    /** Password of the metrics identity. */
    private static final String MONITORING_PASSWORD = TestIdentityPasswords.MONITORING_PASSWORD;

    /** A password no identity of this service carries. */
    private static final String WRONG_PASSWORD = "not-the-password-either";

    /** Account the ordinary identity holds, row 1 of {@code app/data/ASCII/acctdata.txt}. */
    private static final String OWNED_ACCOUNT_ID = "00000000001";

    /** An account the ordinary identity holds no authority for, row 2 of the same fixture. */
    private static final String OTHER_ACCOUNT_ID = "00000000002";

    /** Customer the ordinary identity holds, resolved from {@code app/data/ASCII/cardxref.txt}. */
    private static final String OWNED_CUSTOMER_ID = "000000001";

    /** A customer the ordinary identity holds no authority for. */
    private static final String OTHER_CUSTOMER_ID = "000000002";

    /**
     * Authorities the ordinary identity carries: the account it holds and the customer it holds.
     *
     * <p>Both are granted deliberately. {@code GET /accounts/{accountId}} and
     * {@code GET /customers/{customerId}} compare different authorities, so one identity holding
     * both is what shows that each route reads its own.</p>
     */
    static final String USER_SCOPES =
            "SCOPE_ACCOUNT_" + OWNED_ACCOUNT_ID + ",SCOPE_CUSTOMER_" + OWNED_CUSTOMER_ID;

    /** The four members {@code config/SecurityConfig} writes into a refusal, and no fifth. */
    private static final List<String> PROBLEM_MEMBERS =
            List.of("type", "title", "status", "detail");

    /** The problem type a refusal carries, since no type is minted for these. */
    private static final String ABOUT_BLANK = "about:blank";

    /** The sentence a 401 carries, fixed at {@code config/SecurityConfig}. */
    private static final String UNAUTHORIZED_DETAIL = "This request carried no usable credential.";

    /** The sentence a 403 carries, fixed at {@code config/SecurityConfig}. */
    private static final String FORBIDDEN_DETAIL = "This identity may not use this operation.";

    /** The only media type a state-changing route of this service accepts. */
    private static final String JSON_MEDIA_TYPE = "application/json";

    /**
     * Statuses the handler itself may answer once the chain has admitted a request.
     *
     * <p>{@code 403} is not among them, and neither is {@code 401}: this service answers those two
     * from the chain alone. Every other outcome is the handler's, which is what
     * {@link #assertAdmittedByTheChain} reads rather than a single expected status. An administrator
     * update carrying a body the edits refuse answers {@code 422}, and that is the chain admitting
     * the request.</p>
     */
    private static final List<Integer> HANDLER_ANSWERS = List.of(200, 400, 404, 409, 415, 422);

    /** Credit limit the refused-update assertions read, so a write would be visible. */
    private static final BigDecimal PRESET_CREDIT_LIMIT = new BigDecimal("4321.00");

    /** Credit accumulator the refused-close assertions read. */
    private static final BigDecimal PRESET_CYCLE_CREDIT = new BigDecimal("111.11");

    /** Debit accumulator the refused-close assertions read. */
    private static final BigDecimal PRESET_CYCLE_DEBIT = new BigDecimal("-22.22");

    /** Longest one request waits. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** Reads one response body. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * The one container this module's test fork runs.
     *
     * <p>{@link AccountServiceDatabase} owns it and hands this class a database of its own inside
     * it. Nothing here starts or stops a container, and nothing here reads the container's own
     * database name: that database carries no migrated schema.
     */
    static final PostgreSQLContainer POSTGRES = AccountServiceDatabase.container();

    /** The port the embedded container bound. */
    @LocalServerPort
    private int port;

    /** Reads and writes rows directly, outside every request this class makes. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The client every request in this class travels through. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    /** The three values of the seeded row this class overwrites, captured before each test. */
    private BigDecimal seededCreditLimit;

    /** The seeded credit accumulator. */
    private BigDecimal seededCycleCredit;

    /** The seeded debit accumulator. */
    private BigDecimal seededCycleDebit;

    /**
     * Points the datasource at the container, on the schema the migrations own.
     *
     * @param registry the registry the test context reads these values from
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AccountRouteSecurityIT::migratedSchemaUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Returns the connection string of this class's own database inside the shared container.
     *
     * <p>The facility creates the database on the first call and selects the schema the migrations
     * own, which is what the shipped URL selects.
     *
     * @return the connection string
     */
    private static String migratedSchemaUrl() {
        return AccountServiceDatabase.urlFor(AccountRouteSecurityIT.class);
    }

    /** Captures the seeded values this class overwrites, and sets the values it reads back. */
    @BeforeEach
    void presetTheRowARefusalMustLeaveAlone() {
        seededCreditLimit = storedAmount("credit_limit");
        seededCycleCredit = storedAmount("current_cycle_credit");
        seededCycleDebit = storedAmount("current_cycle_debit");
        jdbcTemplate.update("UPDATE account SET credit_limit = ?, current_cycle_credit = ?,"
                        + " current_cycle_debit = ? WHERE account_id = ?",
                PRESET_CREDIT_LIMIT, PRESET_CYCLE_CREDIT, PRESET_CYCLE_DEBIT, OWNED_ACCOUNT_ID);
        jdbcTemplate.update("DELETE FROM outbox_event");
    }

    /** Restores the seeded values and empties the outbox, so each test starts from one state. */
    @AfterEach
    void restoreTheSeededRow() {
        jdbcTemplate.update("UPDATE account SET credit_limit = ?, current_cycle_credit = ?,"
                        + " current_cycle_debit = ? WHERE account_id = ?",
                seededCreditLimit, seededCycleCredit, seededCycleDebit, OWNED_ACCOUNT_ID);
        jdbcTemplate.update("DELETE FROM outbox_event");
    }

    @Nested
    @DisplayName("Reading one account, which is scoped by the account the path names")
    class TheAccountReadRoute {

        @Test
        @DisplayName("the holder of the account reads it")
        void theHolderOfTheAccountReadsIt() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    accountRoute(OWNED_ACCOUNT_ID)).GET().build());

            JsonNode body = JSON.readTree(response.body());
            assertAll(
                    () -> assertEquals(200, response.statusCode(),
                            "the identity holding SCOPE_ACCOUNT_ for this account reaches the "
                                    + "handler: " + response.body()),
                    () -> assertEquals(OWNED_ACCOUNT_ID, body.get("accountId").asString(),
                            "and the answer is the account that was asked for"));
        }

        @Test
        @DisplayName("the administrator reads any account")
        void theAdministratorReadsAnyAccount() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    accountRoute(OTHER_ACCOUNT_ID)).GET().build());

            assertEquals(200, response.statusCode(),
                    "ROLE_ADMIN passes every ownership check: " + response.body());
        }

        @Test
        @DisplayName("another account is refused, and the body reveals nothing about it")
        void anotherAccountIsRefused() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    accountRoute(OTHER_ACCOUNT_ID)).GET().build());

            assertAll(
                    () -> assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL),
                    () -> assertFalse(response.body().contains(OTHER_ACCOUNT_ID),
                            "the refusal does not echo the account that was asked for"));
        }

        @Test
        @DisplayName("the customer authority is not a way to read the account")
        void theCustomerAuthorityIsNotAWayToReadTheAccount() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    accountRoute(OWNED_CUSTOMER_ID)).GET().build());

            assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL);
        }

        @Test
        @DisplayName("no credential is challenged with 401 and a scheme to retry with")
        void noCredentialIsChallenged() {
            HttpResponse<String> response =
                    send(anonymous(accountRoute(OWNED_ACCOUNT_ID)).GET().build());

            assertAll(
                    () -> assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL),
                    () -> assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("")
                                    .startsWith("Basic realm=\"carddemo\""),
                            "a 401 names the scheme and realm a caller should retry with"));
        }

        @Test
        @DisplayName("the right login with the wrong password is challenged, not admitted")
        void theWrongPasswordIsChallenged() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, WRONG_PASSWORD,
                    accountRoute(OWNED_ACCOUNT_ID)).GET().build());

            assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL);
        }

        @Test
        @DisplayName("the metrics identity reaches no business route")
        void theMetricsIdentityReachesNoBusinessRoute() {
            HttpResponse<String> response = send(authorized(MONITORING_USERNAME,
                    MONITORING_PASSWORD, accountRoute(OWNED_ACCOUNT_ID)).GET().build());

            assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL);
        }
    }

    @Nested
    @DisplayName("Reading one customer, which is scoped by the customer the path names")
    class TheCustomerReadRoute {

        @Test
        @DisplayName("the holder of the customer reads it, which is the customer authority working")
        void theHolderOfTheCustomerReadsIt() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    customerRoute(OWNED_CUSTOMER_ID)).GET().build());

            JsonNode body = JSON.readTree(response.body());
            assertAll(
                    () -> assertEquals(200, response.statusCode(),
                            "SCOPE_CUSTOMER_ is the authority this route reads, and no test in the "
                                    + "module presented it before: " + response.body()),
                    () -> assertEquals(OWNED_CUSTOMER_ID, body.get("customerId").asString(),
                            "and the answer is the customer that was asked for"));
        }

        @Test
        @DisplayName("the administrator reads any customer")
        void theAdministratorReadsAnyCustomer() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    customerRoute(OTHER_CUSTOMER_ID)).GET().build());

            assertEquals(200, response.statusCode(),
                    "ROLE_ADMIN passes every ownership check: " + response.body());
        }

        @Test
        @DisplayName("another customer is refused, and the body reveals nothing about them")
        void anotherCustomerIsRefused() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    customerRoute(OTHER_CUSTOMER_ID)).GET().build());

            assertAll(
                    () -> assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL),
                    () -> assertFalse(response.body().contains(OTHER_CUSTOMER_ID),
                            "the refusal does not echo the customer that was asked for"));
        }

        @Test
        @DisplayName("the account authority is not a way to read the customer")
        void theAccountAuthorityIsNotAWayToReadTheCustomer() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    customerRoute(OWNED_ACCOUNT_ID)).GET().build());

            assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL);
        }

        @Test
        @DisplayName("no credential is challenged")
        void noCredentialIsChallenged() {
            HttpResponse<String> response =
                    send(anonymous(customerRoute(OWNED_CUSTOMER_ID)).GET().build());

            assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL);
        }

        @Test
        @DisplayName("the right login with the wrong password is challenged")
        void theWrongPasswordIsChallenged() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, WRONG_PASSWORD,
                    customerRoute(OWNED_CUSTOMER_ID)).GET().build());

            assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL);
        }

        @Test
        @DisplayName("the metrics identity reaches no business route")
        void theMetricsIdentityReachesNoBusinessRoute() {
            HttpResponse<String> response = send(authorized(MONITORING_USERNAME,
                    MONITORING_PASSWORD, customerRoute(OWNED_CUSTOMER_ID)).GET().build());

            assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL);
        }
    }

    @Nested
    @DisplayName("Updating one account, which the administrator role alone holds")
    class TheUpdateRoute {

        @Test
        @DisplayName("the administrator reaches the handler")
        void theAdministratorReachesTheHandler() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    accountRoute(OWNED_ACCOUNT_ID))
                    .header("Content-Type", JSON_MEDIA_TYPE)
                    .PUT(jsonBody(oneUpdateRequest()))
                    .build());

            assertAdmittedByTheChain(response);
        }

        @Test
        @DisplayName("the holder of the account is refused, and the row and the outbox stand")
        void theHolderOfTheAccountIsRefused() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    accountRoute(OWNED_ACCOUNT_ID))
                    .header("Content-Type", JSON_MEDIA_TYPE)
                    .PUT(jsonBody(oneUpdateRequest()))
                    .build());

            assertAll(
                    () -> assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL),
                    this::assertTheRowAndTheOutboxStand);
        }

        @Test
        @DisplayName("the metrics identity is refused, and the row and the outbox stand")
        void theMetricsIdentityIsRefused() {
            HttpResponse<String> response = send(authorized(MONITORING_USERNAME,
                    MONITORING_PASSWORD, accountRoute(OWNED_ACCOUNT_ID))
                    .header("Content-Type", JSON_MEDIA_TYPE)
                    .PUT(jsonBody(oneUpdateRequest()))
                    .build());

            assertAll(
                    () -> assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL),
                    this::assertTheRowAndTheOutboxStand);
        }

        @Test
        @DisplayName("no credential is challenged, and the row and the outbox stand")
        void noCredentialIsChallenged() {
            HttpResponse<String> response = send(anonymous(accountRoute(OWNED_ACCOUNT_ID))
                    .header("Content-Type", JSON_MEDIA_TYPE)
                    .PUT(jsonBody(oneUpdateRequest()))
                    .build());

            assertAll(
                    () -> assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL),
                    this::assertTheRowAndTheOutboxStand);
        }

        @Test
        @DisplayName("the right login with the wrong password is challenged, and nothing is written")
        void theWrongPasswordIsChallenged() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, WRONG_PASSWORD,
                    accountRoute(OWNED_ACCOUNT_ID))
                    .header("Content-Type", JSON_MEDIA_TYPE)
                    .PUT(jsonBody(oneUpdateRequest()))
                    .build());

            assertAll(
                    () -> assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL),
                    this::assertTheRowAndTheOutboxStand);
        }

        /** Asserts the preset values are the values the row still holds, and nothing is queued. */
        private void assertTheRowAndTheOutboxStand() {
            assertAll(
                    () -> assertEquals(0, PRESET_CREDIT_LIMIT.compareTo(
                                    storedAmount("credit_limit")),
                            "a refused update writes nothing. A 403 answered after a write would be "
                                    + "worse than a 200, because nothing downstream would report it"),
                    () -> assertEquals(0, outboxRowCount(),
                            "and queues no state change for publication"));
        }
    }

    @Nested
    @DisplayName("Closing one billing cycle, which the administrator role alone holds")
    class TheCycleCloseRoute {

        @Test
        @DisplayName("the administrator reaches the handler and both accumulators are zeroed")
        void theAdministratorReachesTheHandler() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    cycleCloseRoute(OWNED_ACCOUNT_ID))
                    .header("Content-Type", JSON_MEDIA_TYPE)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());

            assertAll(
                    () -> assertEquals(200, response.statusCode(),
                            "ROLE_ADMIN holds this route: " + response.body()),
                    () -> assertEquals(0, BigDecimal.ZERO.compareTo(
                                    storedAmount("current_cycle_credit")),
                            "app/cbl/CBACT04C.cbl:L353 zeroes the credit accumulator"),
                    () -> assertEquals(0, BigDecimal.ZERO.compareTo(
                                    storedAmount("current_cycle_debit")),
                            "and :L354 zeroes the debit accumulator"));
        }

        @Test
        @DisplayName("the holder of the account is refused, and both accumulators stand")
        void theHolderOfTheAccountIsRefused() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    cycleCloseRoute(OWNED_ACCOUNT_ID))
                    .header("Content-Type", JSON_MEDIA_TYPE)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());

            assertAll(
                    () -> assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL),
                    this::assertBothAccumulatorsStand);
        }

        @Test
        @DisplayName("the metrics identity is refused, and both accumulators stand")
        void theMetricsIdentityIsRefused() {
            HttpResponse<String> response = send(authorized(MONITORING_USERNAME,
                    MONITORING_PASSWORD, cycleCloseRoute(OWNED_ACCOUNT_ID))
                    .header("Content-Type", JSON_MEDIA_TYPE)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());

            assertAll(
                    () -> assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL),
                    this::assertBothAccumulatorsStand);
        }

        @Test
        @DisplayName("no credential is challenged, and both accumulators stand")
        void noCredentialIsChallenged() {
            HttpResponse<String> response = send(anonymous(cycleCloseRoute(OWNED_ACCOUNT_ID))
                    .header("Content-Type", JSON_MEDIA_TYPE)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());

            assertAll(
                    () -> assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL),
                    this::assertBothAccumulatorsStand);
        }

        @Test
        @DisplayName("the right login with the wrong password is challenged, and nothing is zeroed")
        void theWrongPasswordIsChallenged() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, WRONG_PASSWORD,
                    cycleCloseRoute(OWNED_ACCOUNT_ID))
                    .header("Content-Type", JSON_MEDIA_TYPE)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());

            assertAll(
                    () -> assertProblem(response, 401, "Unauthorized", UNAUTHORIZED_DETAIL),
                    this::assertBothAccumulatorsStand);
        }

        /** Asserts the preset accumulators are the values the row still holds. */
        private void assertBothAccumulatorsStand() {
            assertAll(
                    () -> assertEquals(0, PRESET_CYCLE_CREDIT.compareTo(
                                    storedAmount("current_cycle_credit")),
                            "a refused close leaves the credit accumulator where it was. Zeroing "
                                    + "it would raise the position of app/cbl/CBTRN02C.cbl:L403 "
                                    + "and approve authorizations that belong declined"),
                    () -> assertEquals(0, PRESET_CYCLE_DEBIT.compareTo(
                                    storedAmount("current_cycle_debit")),
                            "and leaves the debit accumulator where it was"),
                    () -> assertEquals(0, outboxRowCount(),
                            "and queues no state change for publication"));
        }
    }

    @Nested
    @DisplayName("A route the chain names in no rule")
    class TheDefaultDeny {

        @Test
        @DisplayName("is refused rather than answered, even for the administrator")
        void isRefusedRatherThanAnswered() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/accounts").GET().build());

            assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL);
        }

        @Test
        @DisplayName("is refused for a method no rule names on a route that exists")
        void isRefusedForAMethodNoRuleNames() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    accountRoute(OWNED_ACCOUNT_ID))
                    .header("Content-Type", JSON_MEDIA_TYPE)
                    .DELETE()
                    .build());

            assertAll(
                    () -> assertProblem(response, 403, "Forbidden", FORBIDDEN_DETAIL),
                    () -> assertEquals(0, PRESET_CREDIT_LIMIT.compareTo(
                                    storedAmount("credit_limit")),
                            "and the row is untouched"));
        }
    }

    /** Returns the route of one account. */
    private static String accountRoute(String accountId) {
        return "/accounts/" + accountId;
    }

    /** Returns the route of one customer. */
    private static String customerRoute(String customerId) {
        return "/customers/" + customerId;
    }

    /** Returns the cycle-close route of one account. */
    private static String cycleCloseRoute(String accountId) {
        return "/accounts/" + accountId + "/cycle-close";
    }

    /**
     * Builds one update body the chain can read.
     *
     * <p>The chain runs ahead of validation, so the body only has to be readable. Which status the
     * handler answers is the handler's business, and every assertion here reads the chain's verdict
     * instead. {@code AccountControllerIT} owns the update outcomes.</p>
     *
     * @return the request document
     */
    private static String oneUpdateRequest() {
        return """
                {
                  "accountData": {
                    "activeStatus": "Y",
                    "creditLimit": "9999.00"
                  }
                }
                """;
    }

    /** Wraps one document as a JavaScript Object Notation request body. */
    private static HttpRequest.BodyPublisher jsonBody(String document) {
        return HttpRequest.BodyPublishers.ofString(document, StandardCharsets.UTF_8);
    }

    /**
     * Asserts the chain admitted one request, whatever the handler then answered.
     *
     * <p>A status alone cannot establish this. The two sentences the chain writes are what separate
     * its refusal from the handler's own, and they are compared rather than the status because a
     * handler is free to answer {@code 422} on a body the edits refuse.</p>
     *
     * @param response the answer to read
     */
    private static void assertAdmittedByTheChain(HttpResponse<String> response) {
        assertAll(
                () -> assertTrue(HANDLER_ANSWERS.contains(response.statusCode()),
                        "the chain admitted the request, so the status is the handler's: "
                                + response.statusCode() + " " + response.body()),
                () -> assertFalse(response.body().contains(FORBIDDEN_DETAIL),
                        "the body carries the chain's refusal sentence: " + response.body()),
                () -> assertFalse(response.body().contains(UNAUTHORIZED_DETAIL),
                        "the body carries the chain's challenge sentence: " + response.body()));
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
        assertFalse(response.body().contains(ADMIN_USERNAME), "nor the administrator");
    }

    /**
     * Reads one monetary column of the seeded account, outside every request.
     *
     * @param column the column to read
     * @return the stored value
     */
    private BigDecimal storedAmount(String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM account WHERE account_id = ?", BigDecimal.class,
                OWNED_ACCOUNT_ID);
    }

    /** @return the number of rows the outbox holds */
    private int outboxRowCount() {
        Integer count =
                jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_event", Integer.class);
        return count == null ? -1 : count;
    }

    /** @return the origin the embedded container bound */
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
                .header("Content-Type", JSON_MEDIA_TYPE)
                .header("Accept", JSON_MEDIA_TYPE)
                // config/CrossSiteRequestFilter requires this on every state-changing request. It
                // is set on every request here, safe ones included, so no assertion above turns on
                // the difference. AccountControllerIT owns the forged-shape refusal.
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
                .header("Content-Type", JSON_MEDIA_TYPE)
                .header("Accept", JSON_MEDIA_TYPE)
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
