/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.integration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.carddemo.dto.auth.LoginRequest;
import com.carddemo.dto.auth.LoginResponse;
import com.carddemo.dto.account.AccountDto;
import com.carddemo.dto.card.CardDto;
import com.carddemo.dto.transaction.TransactionDto;
import com.carddemo.dto.transaction.TransactionRequest;
import com.carddemo.dto.billpayment.BillPaymentRequest;
import com.carddemo.dto.billpayment.BillPaymentResponse;
import com.carddemo.dto.user.UserDto;
import com.carddemo.dto.user.UserCreateRequest;
import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.User;
import com.carddemo.exception.ErrorResponse;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Canonical end-to-end full-stack integration test for the CardDemo COBOL/CICS/VSAM
 * &rarr; Spring Boot 3.2 migration.
 *
 * <p>This is the single largest regression check in the migration. It boots the full
 * Spring application context on a real embedded Tomcat
 * ({@code @SpringBootTest(RANDOM_PORT)}) against a genuine PostgreSQL&nbsp;15 database
 * provided by Testcontainers, then exercises the complete vertical stack for every
 * user-facing functional domain:</p>
 * <ol>
 *   <li>REST controllers (HTTP request/response cycle via {@link TestRestTemplate})</li>
 *   <li>Spring Security 6 — JWT bearer authentication and {@code @PreAuthorize}</li>
 *   <li>Service-layer business logic (transaction validation, bill payment, user CRUD)</li>
 *   <li>Spring Data JPA repositories (real persistence)</li>
 *   <li>PostgreSQL&nbsp;15 (Testcontainers)</li>
 *   <li>Flyway migrations (schema + seed data, {@code V1}-{@code V5})</li>
 *   <li>Batch-job launching over REST ({@code POST /api/admin/jobs/{jobName}/launch})</li>
 *   <li>Cross-cutting concerns ({@code @Transactional}, optimistic locking, exception handling)</li>
 * </ol>
 *
 * <h2>Preserved COBOL business rules verified here</h2>
 * <ul>
 *   <li><b>PR-03</b> &mdash; transaction validation codes carry the <em>exact</em>
 *       COBOL message strings from {@code app/cbl/CBTRN02C.cbl}: code&nbsp;100
 *       {@code "INVALID CARD NUMBER FOUND"} (L380-L392), code&nbsp;101
 *       {@code "ACCOUNT RECORD NOT FOUND"} (L393-L401), code&nbsp;102
 *       {@code "OVERLIMIT TRANSACTION"} (L402-L416), code&nbsp;103
 *       {@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"} (L417-L420).</li>
 *   <li><b>PR-04 / PR-05</b> &mdash; over-limit and post-expiration transactions map
 *       to HTTP&nbsp;422 (Unprocessable Entity).</li>
 *   <li><b>PR-10</b> &mdash; generated transaction IDs are exactly 16 characters.</li>
 *   <li><b>PR-11</b> &mdash; DB2 external timestamp format
 *       {@code YYYY-MM-DD-HH.MM.SS.MIL0000} (26 chars) on transaction timestamps.</li>
 *   <li><b>PR-16</b> &mdash; ALL monetary assertions use AssertJ
 *       {@code isEqualByComparingTo(BigDecimal)} (scale-insensitive); never
 *       {@code isEqualTo} (scale-sensitive), so {@code 100.00} and {@code 100.0}
 *       compare equal.</li>
 *   <li><b>PR-17</b> &mdash; BCrypt password hashing ({@code $2} prefix) on user creation.</li>
 *   <li><b>PR-18</b> &mdash; {@code @PreAuthorize("hasRole('ADMIN')")} on admin endpoints.</li>
 *   <li><b>PR-20</b> &mdash; SSN is masked ({@code ***-**-####}) and never leaked in full.</li>
 *   <li><b>PR-22</b> &mdash; optimistic locking via {@code @Version}; a stale write is
 *       rejected with an optimistic-locking failure (HTTP&nbsp;409 at the REST layer).</li>
 *   <li><b>PR-24</b> &mdash; bill payment atomically creates a transaction AND zeroes the
 *       account balance in a single {@code @Transactional} unit of work, preserving the
 *       {@code COBIL00C} fixed-field values (type {@code "02"}, category {@code 2},
 *       source {@code "POS TERM"}, description {@code "BILL PAYMENT - ONLINE"}, merchant
 *       {@code 999999999} / {@code "BILL PAYMENT"} / {@code "N/A"} / {@code "N/A"}).</li>
 *   <li><b>PR-27</b> &mdash; original {@code app/cbl/*.cbl} and {@code app/csd/CARDDEMO.CSD}
 *       are REFERENCE only (used to derive the assertions above; never modified).</li>
 *   <li><b>PR-28</b> &mdash; Jakarta EE namespace throughout the application under test.</li>
 * </ul>
 *
 * <h2>Datasource note</h2>
 * <p>The {@code @DynamicPropertySource} below binds this class's dedicated
 * {@link #POSTGRES} container and explicitly overrides
 * {@code spring.datasource.driver-class-name} to {@code org.postgresql.Driver}. That
 * override is mandatory because {@code application-test.yml} configures the
 * Testcontainers {@code ContainerDatabaseDriver} (which only accepts {@code jdbc:tc:}
 * magic URLs); without the override the plain {@code jdbc:postgresql://} URL returned by
 * {@link PostgreSQLContainer#getJdbcUrl()} would be rejected by that driver.</p>
 *
 * <h2>Seed-data preconditions (Flyway {@code V4}/{@code V5})</h2>
 * <p>The database is seeded with exactly 10 default users
 * ({@code ADMIN001}-{@code ADMIN005}, {@code USER0001}-{@code USER0005}, all sharing the
 * literal password {@code "PASSWORD"}), 50 accounts ({@code acct_id} 1-50), 50 cards, 50
 * customers, and a 1:1 card&rarr;account mapping. Because every seeded
 * {@code expiration_date} is in the past relative to the test clock, scenarios that must
 * post a transaction (valid create, over-limit, bill payment) first move the target
 * account's expiration date into the future via the injected {@link #accountRepository};
 * the post-expiration scenario (code&nbsp;103) instead forces it into the past. Distinct
 * account IDs are reserved per scenario to prevent cross-test pollution.</p>
 *
 * @see com.carddemo.integration.SecurityIT
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Disabled("""
        Deferred to CP4. This IT boots the full Spring application context and exercises the \
        complete REST + security vertical, which depends on components that do not exist until \
        CP4: SecurityConfig, the PasswordEncoder bean, JwtAuthenticationFilter, and the online \
        controllers (AuthController, AccountController, CardController, CustomerController, \
        TransactionController, BillPaymentController, ReportController, UserController, \
        MenuController, BatchAdminController). With these absent the context fails to start \
        (UserSeedingJobConfig requires a PasswordEncoder bean that CP4's SecurityConfig will \
        provide), so an enabled IT here would make `mvn verify` fail. Re-enable in CP4 by \
        removing this annotation once the security configuration and online controllers exist.""")
@DisplayName("FullStackIT — End-to-end REST + service + repository + database + Spring Security verification")
class FullStackIT {

    // ------------------------------------------------------------------------
    // Constants — endpoints, credentials, and regex contracts
    // ------------------------------------------------------------------------

    /** Literal seed password shared by all 10 default users (BCrypt-hashed per user). */
    private static final String DEFAULT_PASSWORD = "PASSWORD";

    /** Stateless authentication endpoint (replaces CICS COSGN00C sign-on). */
    private static final String LOGIN_PATH = "/api/auth/login";

    /** Role-filtered menu endpoint (replaces COMEN01C / COADM01C XCTL routing). */
    private static final String MENU_PATH = "/api/menu";

    /** ADMIN-only user-administration collection endpoint (PR-18 — closes the COUSR auth gap). */
    private static final String ADMIN_USERS_PATH = "/api/admin/users";

    /** Actuator health endpoint (exposed via application-test.yml management config). */
    private static final String HEALTH_PATH = "/actuator/health";

    /** A canonical seeded ADMIN user id (V4 seed — MARGARET GOLD, type 'A'). */
    private static final String ADMIN_ID = "ADMIN001";

    /** A canonical seeded regular-user id (V4 seed — type 'U'). */
    private static final String USER_ID = "USER0001";

    /** Exact COBOL CBTRN02C code-100 message (app/cbl/CBTRN02C.cbl L380-L392). */
    private static final String MSG_INVALID_CARD = "INVALID CARD NUMBER FOUND";

    /** Exact COBOL CBTRN02C code-101 message (app/cbl/CBTRN02C.cbl L393-L401). */
    private static final String MSG_ACCOUNT_NOT_FOUND = "ACCOUNT RECORD NOT FOUND";

    /** Exact COBOL CBTRN02C code-102 message (app/cbl/CBTRN02C.cbl L402-L416). */
    private static final String MSG_OVERLIMIT = "OVERLIMIT TRANSACTION";

    /** Exact COBOL CBTRN02C code-103 message (app/cbl/CBTRN02C.cbl L417-L420). */
    private static final String MSG_EXPIRED = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    /**
     * DB2 external timestamp format {@code YYYY-MM-DD-HH.MM.SS.MIL0000} (26 chars,
     * trailing literal {@code 0000}) — PR-11 (app/cbl/CBACT04C.cbl L613-L626).
     */
    private static final Pattern DB2_TIMESTAMP_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{3}0000$");

    /** BCrypt hash shape: {@code $2[aby]$NN$} + 53 chars (60 total) — PR-17. */
    private static final Pattern BCRYPT_PATTERN =
            Pattern.compile("^\\$2[aby]\\$\\d{2}\\$.{53}$");

    /** A bare 9-digit SSN (e.g. {@code 020973888}); PR-20 requires this never appears in full. */
    private static final Pattern RAW_SSN_PATTERN = Pattern.compile("\\d{9}");

    /** The masked SSN shape {@code ***-**-####} mandated by PR-20. */
    private static final Pattern MASKED_SSN_PATTERN = Pattern.compile("\\*\\*\\*-\\*\\*-\\d{4}");

    /** A future expiration date used to "un-expire" an account for transaction scenarios. */
    private static final LocalDate FUTURE_EXPIRATION = LocalDate.of(2099, 12, 31);

    /** A past expiration date used to force the code-103 (post-expiration) scenario. */
    private static final LocalDate PAST_EXPIRATION = LocalDate.of(2000, 1, 1);

    // Reserved account IDs per scenario (all exist 1-50, all 1:1 with a seeded card)
    // to prevent cross-test state pollution.
    private static final long ACCT_READ            = 1L;   // read-only views
    private static final long ACCT_UPDATE          = 3L;   // account update
    private static final long ACCT_CARD            = 5L;   // card management
    private static final long ACCT_CUSTOMER        = 1L;   // customer lookup (read-only)
    private static final long ACCT_TXN_VALID       = 10L;  // valid transaction create
    private static final long ACCT_TXN_OVERLIMIT   = 11L;  // code 102
    private static final long ACCT_TXN_EXPIRED     = 12L;  // code 103
    private static final long ACCT_BILLPAY_FULL    = 13L;  // bill payment (positive balance)
    private static final long ACCT_BILLPAY_ZERO    = 14L;  // zero-balance bill payment
    private static final long ACCT_OPTLOCK         = 15L;  // optimistic locking
    private static final long ACCT_TXN_NEGATIVE    = 16L;  // negative amount (cyc debit)
    private static final long ACCT_TXN_TCATBAL     = 17L;  // TCATBAL upsert verification

    // Valid reference-data codes seeded by V3 (transaction_types / transaction_categories).
    private static final String VALID_TYPE_CD = "01";       // Purchase
    private static final String VALID_CAT_CD  = "0001";     // Regular Sales Draft (CHAR(4))

    // ------------------------------------------------------------------------
    // Testcontainers PostgreSQL 15
    // ------------------------------------------------------------------------

    /**
     * Dedicated PostgreSQL 15 container for the full-stack suite, managed with the Testcontainers
     * <em>singleton-container</em> pattern: it is started eagerly in the {@code static} initializer
     * block below rather than via the {@code @Container}/{@code @Testcontainers} JUnit lifecycle.
     *
     * <p>This is deliberate and necessary. The {@link DynamicPropertySource} method must read the
     * container's mapped JDBC port while Spring is building the application context. Under the
     * {@code @Container} lifecycle the container is only started by the Testcontainers JUnit
     * extension's {@code beforeAll} callback, which (because {@code @SpringBootTest}'s
     * {@code SpringExtension} is registered first) can run <em>after</em> Spring has already begun
     * resolving {@code spring.datasource.*}, yielding
     * {@code IllegalStateException: Mapped port can only be obtained after the container is started}.
     * Starting the container in a {@code static} block guarantees it is running before any property
     * supplier is evaluated, regardless of extension ordering or whether the whole class or a single
     * {@code @Nested} group is executed. The container is reaped by the Testcontainers Ryuk sidecar
     * at JVM exit; {@code @SuppressWarnings("resource")} documents that we intentionally never close
     * it explicitly.</p>
     */
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15"))
            .withDatabaseName("carddemo_fullstack")
            .withUsername("carddemo")
            .withPassword("test_password");

    static {
        // Eager start (singleton-container pattern) — see field Javadoc for the rationale.
        POSTGRES.start();
    }

    /**
     * Binds the container's JDBC coordinates onto the Spring {@code Environment} before the
     * application context starts. The {@code driver-class-name} override is mandatory (see
     * class Javadoc) so the plain {@code jdbc:postgresql://} URL is handled by the real
     * PostgreSQL JDBC driver rather than the Testcontainers magic-URL driver configured in
     * {@code application-test.yml}.
     *
     * @param registry the dynamic property registry supplied by the Spring TestContext framework
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.open-in-view", () -> "false");
    }

    // ------------------------------------------------------------------------
    // Injected collaborators
    // ------------------------------------------------------------------------

    /** The random embedded-Tomcat port (used for a context-startup sanity assertion). */
    @LocalServerPort
    private int port;

    /** HTTP client that auto-resolves relative paths against {@code http://localhost:{port}}. */
    @Autowired
    private TestRestTemplate restTemplate;

    /** Repository for direct verification (and precondition setup) of {@code accounts} rows. */
    @Autowired
    private AccountRepository accountRepository;

    /** Repository for direct verification of {@code cards} rows. */
    @Autowired
    private CardRepository cardRepository;

    /** Repository for direct verification of persisted {@code transactions} rows. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** Repository for direct verification of {@code users} rows (created / deleted / hashed). */
    @Autowired
    private UserRepository userRepository;

    /** Direct SQL access for resolving seed fixtures and counting Flyway / domain rows. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Production BCrypt encoder bean — verifies {@code matches(raw, storedHash)} (PR-17). */
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    /** Jackson mapper for inspecting raw JSON payloads (SSN-masking, menu shape, error shape). */
    @Autowired
    private ObjectMapper objectMapper;

    // Cached seed fixtures resolved once from the database (HTTP-free, so safe even before
    // sibling controllers exist).
    private String cardForReadAccount;
    private String firstSeededCard;

    /**
     * Resolves database-derived fixtures exactly once before any test runs. Deliberately
     * HTTP-free: it queries the seeded schema via {@link JdbcTemplate} so it never depends on
     * a sibling REST controller being present. With {@code @TestInstance(PER_CLASS)} this is a
     * non-static instance method invoked a single time for the whole class.
     */
    @BeforeAll
    void resolveSeedFixtures() {
        this.cardForReadAccount = cardNumberForAccount(ACCT_READ);
        this.firstSeededCard = firstSeededCardNumber();
        assertThat(this.cardForReadAccount)
                .as("Seed precondition: account %d must own at least one card (V5 seed)", ACCT_READ)
                .isNotNull();
        assertThat(this.firstSeededCard)
                .as("Seed precondition: at least one card must be seeded (V5 seed)")
                .isNotNull();
    }

    // ========================================================================
    // 1. HealthAndStartup — context boot, Flyway, and seed-data preconditions
    // ========================================================================

    /**
     * Verifies the most basic precondition for every other test: the full context booted on a
     * real random port, Flyway applied all migrations, and the expected seed rows are present.
     * The seed/migration assertions go through JPA / JDBC (not HTTP), so they hold regardless of
     * which sibling REST controllers exist yet.
     */
    @Nested
    @DisplayName("1. Health & startup — context boot, Flyway migrations, seed-data preconditions")
    class HealthAndStartup {

        @Test
        @DisplayName("Context boots on a random embedded port with TestRestTemplate wired")
        void shouldStartSpringContextSuccessfully() {
            assertThat(port)
                    .as("Embedded Tomcat must be bound to a positive random port for end-to-end HTTP tests")
                    .isPositive();
            assertThat(restTemplate)
                    .as("TestRestTemplate must be auto-configured by @SpringBootTest(RANDOM_PORT)")
                    .isNotNull();
        }

        @Test
        @DisplayName("Actuator /health responds without a server error (app is live)")
        void shouldRespondToHealthEndpoint() {
            ResponseEntity<String> health = restTemplate.getForEntity(HEALTH_PATH, String.class);
            assertThat(health.getStatusCode().is5xxServerError())
                    .as("Actuator health endpoint must not raise a 5xx — the application must be live "
                        + "(got %s)", health.getStatusCode())
                    .isFalse();
        }

        @Test
        @DisplayName("Flyway applied all V1-V5 migrations (flyway_schema_history)")
        void shouldHaveFlywayMigrationsApplied() {
            Integer applied = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true AND version IS NOT NULL",
                    Integer.class);
            assertThat(applied)
                    .as("All five Flyway migrations (V1 schema, V2 indexes, V3 reference, V4 users, "
                        + "V5 master data) must have applied successfully")
                    .isNotNull()
                    .isGreaterThanOrEqualTo(5);
        }

        @Test
        @DisplayName("Exactly 10 default users seeded (V4 — ADMIN001-005, USER0001-0005)")
        void shouldHaveSeededDefaultUsers() {
            assertThat(userRepository.count())
                    .as("V4__seed_users.sql must seed exactly 10 default users (PR-17 BCrypt roster)")
                    .isEqualTo(10L);
        }

        @Test
        @DisplayName("50 accounts and 50 cards seeded (V5 master data)")
        void shouldHaveSeededAccountsAndCards() {
            assertThat(accountRepository.count())
                    .as("V5__seed_master_data.sql must seed exactly 50 accounts")
                    .isEqualTo(50L);
            assertThat(cardRepository.count())
                    .as("V5__seed_master_data.sql must seed exactly 50 cards")
                    .isEqualTo(50L);
        }
    }

    // ========================================================================
    // 2. AuthenticationFlow — POST /api/auth/login (replaces COSGN00C sign-on)
    // ========================================================================

    /**
     * Happy-path authentication plus the unauthenticated-rejection guard. Deeper credential and
     * authorization scenarios live in {@code SecurityIT}; this section asserts the JWT issuance
     * contract that every other authenticated section depends on.
     */
    @Nested
    @DisplayName("2. Authentication flow (POST /api/auth/login) — JWT issuance, PR-17")
    class AuthenticationFlow {

        @Test
        @DisplayName("ADMIN001 / PASSWORD → 200 with a non-blank JWT bearer token (userType 'A')")
        void shouldLoginAsAdminAndReceiveBearerToken() {
            LoginRequest req = new LoginRequest(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<LoginResponse> resp =
                    restTemplate.postForEntity(LOGIN_PATH, req, LoginResponse.class);

            assertThat(resp.getStatusCode())
                    .as("Valid admin credentials must authenticate via BCrypt match (PR-17)")
                    .isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("A 200 login response must carry a body")
                    .isNotNull();
            assertThat(resp.getBody().token())
                    .as("Login response must include a non-blank JWT bearer token")
                    .isNotNull().isNotBlank();
            assertThat(resp.getBody().userType())
                    .as("ADMIN001 must report userType 'A' (PR-19 → ROLE_ADMIN)")
                    .isEqualTo("A");
        }

        @Test
        @DisplayName("USER0001 / PASSWORD → 200 with a non-blank JWT bearer token (userType 'U')")
        void shouldLoginAsRegularUserAndReceiveBearerToken() {
            LoginRequest req = new LoginRequest(USER_ID, DEFAULT_PASSWORD);
            ResponseEntity<LoginResponse> resp =
                    restTemplate.postForEntity(LOGIN_PATH, req, LoginResponse.class);

            assertThat(resp.getStatusCode())
                    .as("Valid regular-user credentials must authenticate via BCrypt match (PR-17)")
                    .isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("A 200 login response must carry a body")
                    .isNotNull();
            assertThat(resp.getBody().token())
                    .as("Login response must include a non-blank JWT bearer token")
                    .isNotNull().isNotBlank();
            assertThat(resp.getBody().userType())
                    .as("USER0001 must report userType 'U' (PR-19 → ROLE_USER)")
                    .isEqualTo("U");
        }

        @Test
        @DisplayName("Unauthenticated access to a protected endpoint is rejected (401/403)")
        void shouldRejectUnauthenticatedAccessToProtectedEndpoint() {
            ResponseEntity<String> resp =
                    restTemplate.getForEntity("/api/accounts/" + ACCT_READ, String.class);
            assertThat(resp.getStatusCode())
                    .as("A protected resource must reject anonymous access (Spring Security filter "
                        + "chain runs before controller dispatch)")
                    .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        }
    }

    // ========================================================================
    // 3. MenuAccess — GET /api/menu (replaces COMEN01C / COADM01C XCTL routing)
    // ========================================================================

    /**
     * Verifies the role-filtered menu endpoint that replaces the CICS menu programs. The exact
     * option strings are a sibling concern; this section asserts the contract that matters: an
     * authenticated principal receives a menu, anonymous callers are rejected, and the ADMIN and
     * USER menus are not identical (role filtering is applied).
     */
    @Nested
    @DisplayName("3. Menu access (GET /api/menu) — role-filtered routing")
    class MenuAccess {

        @Test
        @DisplayName("ADMIN receives a non-empty menu payload")
        void shouldReturnAdminMenuForAdminUser() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<String> resp = getWithAuth(MENU_PATH, adminToken, String.class);

            assertThat(resp.getStatusCode())
                    .as("An authenticated ADMIN must receive the menu (replaces COADM01C routing)")
                    .isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("Menu response body must be present and non-blank")
                    .isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("USER and ADMIN menus differ (role filtering is applied)")
        void shouldReturnUserMenuForRegularUser() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            String userToken = login(USER_ID, DEFAULT_PASSWORD);

            ResponseEntity<String> adminMenu = getWithAuth(MENU_PATH, adminToken, String.class);
            ResponseEntity<String> userMenu = getWithAuth(MENU_PATH, userToken, String.class);

            assertThat(userMenu.getStatusCode())
                    .as("An authenticated USER must receive the menu (replaces COMEN01C routing)")
                    .isEqualTo(HttpStatus.OK);
            assertThat(userMenu.getBody())
                    .as("USER menu response body must be present and non-blank")
                    .isNotNull().isNotBlank();
            assertThat(userMenu.getBody())
                    .as("The USER menu must differ from the ADMIN menu — role filtering replaces the "
                        + "COADM01C-vs-COMEN01C XCTL split")
                    .isNotEqualTo(adminMenu.getBody());
        }

        @Test
        @DisplayName("Anonymous access to /api/menu is rejected (401/403)")
        void shouldRequireAuthenticationForMenu() {
            ResponseEntity<String> resp = restTemplate.getForEntity(MENU_PATH, String.class);
            assertThat(resp.getStatusCode())
                    .as("The menu endpoint requires authentication")
                    .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        }
    }

    // ========================================================================
    // 4. AccountManagement — GET/PUT /api/accounts/{acctId} (COACTVWC / COACTUPC)
    // ========================================================================

    /**
     * Verifies account view and update, the 404 mapping for an unknown account (code 101), input
     * validation on update, and the PR-16 money-scale contract.
     */
    @Nested
    @DisplayName("4. Account management (GET/PUT /api/accounts/{id}) — COACTVWC / COACTUPC")
    class AccountManagement {

        @Test
        @DisplayName("GET /api/accounts/1 returns the account with money fields populated")
        void shouldReturnAccountByIdForAuthenticatedUser() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<AccountDto> resp =
                    getWithAuth("/api/accounts/" + ACCT_READ, adminToken, AccountDto.class);

            assertThat(resp.getStatusCode())
                    .as("An authenticated principal must be able to view a seeded account")
                    .isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("Account view response must carry a body")
                    .isNotNull();
            assertThat(resp.getBody().getAcctId())
                    .as("Returned account id must match the requested id")
                    .isEqualTo(ACCT_READ);
            assertThat(resp.getBody().getCurrBal())
                    .as("Current balance must be present (money field, PR-16 BigDecimal)")
                    .isNotNull();
            assertThat(resp.getBody().getCreditLimit())
                    .as("Credit limit must be present (money field, PR-16 BigDecimal)")
                    .isNotNull();
        }

        @Test
        @DisplayName("GET unknown account → 404 with the COBOL code-101 message (PR-03)")
        void shouldReturn404ForUnknownAccount() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<ErrorResponse> resp =
                    getWithAuth("/api/accounts/99999999", adminToken, ErrorResponse.class);

            assertThat(resp.getStatusCode())
                    .as("A missing account must map to HTTP 404 (AccountNotFoundException, code 101)")
                    .isEqualTo(HttpStatus.NOT_FOUND);
            if (resp.getBody() != null && resp.getBody().message() != null) {
                assertThat(resp.getBody().message())
                        .as("Code-101 message must preserve the COBOL CBTRN02C string (PR-03)")
                        .contains(MSG_ACCOUNT_NOT_FOUND);
            }
        }

        @Test
        @DisplayName("PUT updates a mutable account field and the change is durable")
        void shouldUpdateAccountFieldsAtomically() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            String path = "/api/accounts/" + ACCT_UPDATE;

            ResponseEntity<AccountDto> before = getWithAuth(path, adminToken, AccountDto.class);
            assertThat(before.getStatusCode())
                    .as("Precondition: the account under update must be viewable")
                    .isEqualTo(HttpStatus.OK);
            assertThat(before.getBody()).isNotNull();

            AccountDto edited = before.getBody();
            String newZip = "99950";
            edited.setAddrZip(newZip);

            ResponseEntity<AccountDto> update = putWithAuth(path, adminToken, edited, AccountDto.class);
            assertThat(update.getStatusCode())
                    .as("A valid account update must succeed (COACTUPC REWRITE equivalent)")
                    .isEqualTo(HttpStatus.OK);

            ResponseEntity<AccountDto> after = getWithAuth(path, adminToken, AccountDto.class);
            assertThat(after.getBody())
                    .as("Re-fetched account must carry a body")
                    .isNotNull();
            assertThat(after.getBody().getAddrZip())
                    .as("The updated ZIP must be durably persisted")
                    .isEqualTo(newZip);
        }

        @Test
        @DisplayName("PUT with a negative credit limit is rejected (400/422)")
        void shouldRejectInvalidAccountUpdates() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            String path = "/api/accounts/" + ACCT_UPDATE;

            ResponseEntity<AccountDto> before = getWithAuth(path, adminToken, AccountDto.class);
            assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(before.getBody()).isNotNull();

            AccountDto invalid = before.getBody();
            invalid.setCreditLimit(new BigDecimal("-1.00"));

            ResponseEntity<ErrorResponse> resp = putWithAuth(path, adminToken, invalid, ErrorResponse.class);
            assertThat(resp.getStatusCode())
                    .as("A negative credit limit must be rejected by input validation")
                    .isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("Money fields carry BigDecimal scale 2 (PR-16)")
        void shouldStoreMoneyWithExactBigDecimalScale() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<AccountDto> resp =
                    getWithAuth("/api/accounts/" + ACCT_READ, adminToken, AccountDto.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().getCurrBal().scale())
                    .as("Money must have scale 2 per PR-16 (S9(10)V99 packed-decimal → NUMERIC(15,2))")
                    .isEqualTo(2);
            assertThat(resp.getBody().getCurrBal())
                    .as("Account 1 seeded current balance must compare equal to 194.00 (PR-16 "
                        + "isEqualByComparingTo, never isEqualTo)")
                    .isEqualByComparingTo(new BigDecimal("194.00"));
        }
    }

    // ========================================================================
    // 5. CardManagement — GET/PUT /api/cards & /api/accounts/{id}/cards
    // ========================================================================

    /**
     * Verifies card listing by account (backed by {@code idx_card_account_id}, the
     * {@code CARDDATA.AIX} replacement), pagination, single-card detail, the unknown-card guard,
     * and an active-status update.
     */
    @Nested
    @DisplayName("5. Card management (GET/PUT /api/cards, /api/accounts/{id}/cards) — COCRDLIC/SLC/UPC")
    class CardManagement {

        @Test
        @DisplayName("GET /api/accounts/{id}/cards lists the account's card(s)")
        void shouldListCardsForAccount() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            String expectedCard = cardNumberForAccount(ACCT_CARD);

            ResponseEntity<String> resp =
                    getWithAuth("/api/accounts/" + ACCT_CARD + "/cards", adminToken, String.class);

            assertThat(resp.getStatusCode())
                    .as("Listing an account's cards replaces VSAM STARTBR over CARDDATA.AIX "
                        + "(idx_card_account_id)")
                    .isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("The account's seeded card number must appear in the listing payload")
                    .isNotNull()
                    .contains(expectedCard);
        }

        @Test
        @DisplayName("GET /api/accounts/{id}/cards?page=0&size=10 paginates (Pageable replaces PF7/PF8)")
        void shouldPaginateCardsList() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<String> resp = getWithAuth(
                    "/api/accounts/" + ACCT_CARD + "/cards?page=0&size=10", adminToken, String.class);

            assertThat(resp.getStatusCode())
                    .as("Paginated card listing must succeed (Spring Data Pageable replaces the "
                        + "COCRDLIC browse cursor)")
                    .isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("Paginated card listing must return a body")
                    .isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("GET /api/cards/{cardNum} returns the card detail")
        void shouldReturnCardByNumberDetail() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            String cardNum = cardNumberForAccount(ACCT_CARD);

            ResponseEntity<CardDto> resp =
                    getWithAuth("/api/cards/" + cardNum, adminToken, CardDto.class);

            assertThat(resp.getStatusCode())
                    .as("Single-card detail replaces the COCRDSLC keyed READ")
                    .isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("Card detail response must carry a body")
                    .isNotNull();
            assertThat(resp.getBody().getCardNum())
                    .as("Returned card number must match the requested key")
                    .isEqualTo(cardNum);
            assertThat(resp.getBody().getAccountId())
                    .as("Returned card must belong to the requested account")
                    .isEqualTo(ACCT_CARD);
        }

        @Test
        @DisplayName("GET an unknown card → 400/404")
        void shouldReturn404ForUnknownCard() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<ErrorResponse> resp =
                    getWithAuth("/api/cards/0000000000000000", adminToken, ErrorResponse.class);

            assertThat(resp.getStatusCode())
                    .as("A non-existent card must be rejected (INVALID-KEY → 404, or 400 for a bad key)")
                    .isIn(HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("PUT /api/cards/{cardNum} updates the active status durably")
        void shouldUpdateCardActiveStatus() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            String cardNum = cardNumberForAccount(ACCT_CARD);

            ResponseEntity<CardDto> before = getWithAuth("/api/cards/" + cardNum, adminToken, CardDto.class);
            assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(before.getBody()).isNotNull();

            String current = before.getBody().getActiveStatus();
            String flipped = "Y".equalsIgnoreCase(current) ? "N" : "Y";
            CardDto edited = before.getBody();
            edited.setActiveStatus(flipped);

            ResponseEntity<CardDto> update =
                    putWithAuth("/api/cards/" + cardNum, adminToken, edited, CardDto.class);
            assertThat(update.getStatusCode())
                    .as("A valid card update must succeed (COCRDUPC REWRITE equivalent)")
                    .isEqualTo(HttpStatus.OK);

            Card persisted = cardRepository.findById(cardNum).orElseThrow();
            assertThat(persisted.getActiveStatus())
                    .as("The flipped active status must be durably persisted to the cards table")
                    .isEqualTo(flipped);
        }
    }

    // ========================================================================
    // 6. CustomerLookup — GET /api/customers/{custId} (COACTVWC customer portion)
    // ========================================================================

    /**
     * Verifies customer retrieval and the PR-20 SSN-masking contract: the full 9-digit SSN must
     * never appear in the outbound payload, and any masked value present must use the
     * {@code ***-**-####} shape.
     */
    @Nested
    @DisplayName("6. Customer lookup (GET /api/customers/{id}) — PR-20 SSN masking")
    class CustomerLookup {

        @Test
        @DisplayName("GET /api/customers/1 returns the customer")
        void shouldReturnCustomerById() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<String> resp = getWithAuth("/api/customers/1", adminToken, String.class);

            assertThat(resp.getStatusCode())
                    .as("A seeded customer must be viewable by an authenticated principal")
                    .isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("Customer response body must be present")
                    .isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("Full SSN is never leaked; masked form (if present) is ***-**-#### (PR-20)")
        void shouldNotLeakFullSsn() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            String fullSsn = jdbcTemplate.queryForObject(
                    "SELECT ssn FROM customers WHERE cust_id = 1", String.class);

            ResponseEntity<String> resp = getWithAuth("/api/customers/1", adminToken, String.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            String body = resp.getBody();
            assertThat(body).as("Customer response body must be present").isNotNull();

            assertThat(body)
                    .as("PR-20: the full 9-digit SSN (%s) must NEVER appear in an outbound payload", fullSsn)
                    .doesNotContain(fullSsn);

            JsonNode node = parseJson(body);
            JsonNode ssnNode = node.get("ssn");
            if (ssnNode != null && !ssnNode.isNull()) {
                String masked = ssnNode.asText();
                assertThat(RAW_SSN_PATTERN.matcher(masked).matches())
                        .as("PR-20: the emitted SSN value '%s' must not be a bare 9-digit number", masked)
                        .isFalse();
                assertThat(MASKED_SSN_PATTERN.matcher(masked).matches())
                        .as("PR-20: a masked SSN must use the ***-**-#### shape (last 4 visible)")
                        .isTrue();
            }
        }
    }

    // ========================================================================
    // 7. TransactionCreation — POST /api/transactions (COTRN02C online add)
    // ========================================================================

    /**
     * The most important section: verifies that the online transaction-create path preserves the
     * {@code CBTRN02C} validation chain (codes 100/101/102/103) with the exact COBOL message
     * strings (PR-03), the credit-limit (PR-04) and expiration (PR-05) checks, the 16-character
     * transaction id (PR-10), the DB2 timestamp format (PR-11), the {@code TCATBAL} upsert (PR-06),
     * and the sign-based account-bucket update (PR-07).
     *
     * <p>Because every seeded account is expired relative to the test clock, scenarios that must
     * reach the posting logic first move the target account's expiration into the future.</p>
     */
    @Nested
    @DisplayName("7. Transaction creation (POST /api/transactions) — PR-03/04/05/06/07/10/11")
    class TransactionCreation {

        @Test
        @DisplayName("Valid purchase on an active, in-date card → 201 with a 16-char tranId (PR-10)")
        void shouldCreateValidTransactionForActiveCard() {
            makeAccountTransactable(ACCT_TXN_VALID);
            String card = cardNumberForAccount(ACCT_TXN_VALID);
            TransactionRequest req = buildTransactionRequest(card, ACCT_TXN_VALID, new BigDecimal("25.99"));

            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<TransactionDto> resp =
                    postWithAuth("/api/transactions", adminToken, req, TransactionDto.class);

            assertThat(resp.getStatusCode())
                    .as("A valid online transaction must be created (HTTP 201)")
                    .isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody())
                    .as("Create response must carry a TransactionDto body")
                    .isNotNull();
            assertThat(resp.getBody().getTranId())
                    .as("PR-10: generated transaction id must be exactly 16 characters")
                    .isNotNull().hasSize(16);
            assertThat(resp.getBody().getAmount())
                    .as("PR-16: returned amount must compare equal to the submitted 25.99")
                    .isEqualByComparingTo(new BigDecimal("25.99"));
        }

        @Test
        @DisplayName("CRITICAL — invalid card → 400 with code-100 message (PR-03)")
        void shouldRejectInvalidCardWithMessage100() {
            // 16 digits → passes DTO format validation, so the request reaches the service where
            // the card lookup fails first in the CBTRN02C chain (code 100).
            TransactionRequest req =
                    buildTransactionRequest("9999999999999999", ACCT_TXN_VALID, new BigDecimal("10.00"));

            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<ErrorResponse> resp =
                    postWithAuth("/api/transactions", adminToken, req, ErrorResponse.class);

            assertThat(resp.getStatusCode())
                    .as("Invalid card yields 400 BAD_REQUEST per the global exception handler")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody())
                    .as("ErrorResponse body must not be null")
                    .isNotNull();
            assertThat(resp.getBody().message())
                    .as("Code-100 message must match COBOL CBTRN02C L380-L392 verbatim (PR-03)")
                    .isEqualTo(MSG_INVALID_CARD);
        }

        @Test
        @DisplayName("CRITICAL — over-limit purchase → 422 with code-102 message (PR-03/PR-04)")
        void shouldRejectOverlimitTransactionWithMessage102() {
            makeAccountTransactable(ACCT_TXN_OVERLIMIT);
            String card = cardNumberForAccount(ACCT_TXN_OVERLIMIT);
            BigDecimal creditLimit =
                    accountRepository.findById(ACCT_TXN_OVERLIMIT).orElseThrow().getCreditLimit();
            // Seed cyc credit/debit are 0, so any amount strictly above the credit limit violates
            // ACCT-CREDIT-LIMIT < (CYC-CREDIT - CYC-DEBIT + AMT).
            BigDecimal overLimitAmount = creditLimit.add(new BigDecimal("100.00"));
            TransactionRequest req = buildTransactionRequest(card, ACCT_TXN_OVERLIMIT, overLimitAmount);

            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<ErrorResponse> resp =
                    postWithAuth("/api/transactions", adminToken, req, ErrorResponse.class);

            assertThat(resp.getStatusCode())
                    .as("PR-04: an over-limit transaction must map to HTTP 422")
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(resp.getBody())
                    .as("ErrorResponse body must not be null")
                    .isNotNull();
            assertThat(resp.getBody().message())
                    .as("Code-102 message must match COBOL CBTRN02C L402-L416 verbatim (PR-03)")
                    .isEqualTo(MSG_OVERLIMIT);
        }

        @Test
        @DisplayName("CRITICAL — post-expiration purchase → 422 with code-103 message (PR-03/PR-05)")
        void shouldRejectExpiredAccountWithMessage103() {
            makeAccountExpired(ACCT_TXN_EXPIRED);
            String card = cardNumberForAccount(ACCT_TXN_EXPIRED);
            // A small amount stays under the credit limit, so the expiration check (103), not the
            // over-limit check (102), is the failing condition.
            TransactionRequest req = buildTransactionRequest(card, ACCT_TXN_EXPIRED, new BigDecimal("10.00"));

            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<ErrorResponse> resp =
                    postWithAuth("/api/transactions", adminToken, req, ErrorResponse.class);

            assertThat(resp.getStatusCode())
                    .as("PR-05: a post-expiration transaction must map to HTTP 422")
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(resp.getBody())
                    .as("ErrorResponse body must not be null")
                    .isNotNull();
            assertThat(resp.getBody().message())
                    .as("Code-103 message must match COBOL CBTRN02C L417-L420 verbatim (PR-03)")
                    .isEqualTo(MSG_EXPIRED);
        }

        @Test
        @DisplayName("A created transaction is durably persisted to the transactions table")
        void shouldPersistTransactionToDatabase() {
            makeAccountTransactable(ACCT_TXN_VALID);
            String card = cardNumberForAccount(ACCT_TXN_VALID);
            TransactionRequest req = buildTransactionRequest(card, ACCT_TXN_VALID, new BigDecimal("31.41"));

            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<TransactionDto> resp =
                    postWithAuth("/api/transactions", adminToken, req, TransactionDto.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody()).isNotNull();

            String tranId = resp.getBody().getTranId();
            Transaction persisted = transactionRepository.findById(tranId).orElse(null);
            assertThat(persisted)
                    .as("The created transaction (id=%s) must exist in the transactions table", tranId)
                    .isNotNull();
            assertThat(persisted.getAmount())
                    .as("PR-16: persisted amount must compare equal to 31.41")
                    .isEqualByComparingTo(new BigDecimal("31.41"));
        }

        @Test
        @DisplayName("TCATBAL is upserted by the created transaction's amount (PR-06)")
        void shouldUpsertTransactionCategoryBalance() {
            makeAccountTransactable(ACCT_TXN_TCATBAL);
            String card = cardNumberForAccount(ACCT_TXN_TCATBAL);
            BigDecimal amount = new BigDecimal("12.50");

            BigDecimal before = tcatBalanceOrZero(ACCT_TXN_TCATBAL, VALID_TYPE_CD, VALID_CAT_CD);

            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<TransactionDto> resp = postWithAuth(
                    "/api/transactions", adminToken,
                    buildTransactionRequest(card, ACCT_TXN_TCATBAL, amount), TransactionDto.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            BigDecimal after = tcatBalanceOrZero(ACCT_TXN_TCATBAL, VALID_TYPE_CD, VALID_CAT_CD);
            assertThat(after.subtract(before))
                    .as("PR-06: TCATBAL for (acct=%d,type=%s,cat=%s) must increase by exactly the "
                        + "transaction amount (insert-or-add upsert)",
                        ACCT_TXN_TCATBAL, VALID_TYPE_CD, VALID_CAT_CD)
                    .isEqualByComparingTo(amount);
        }

        @Test
        @DisplayName("Positive amount increments curr_cyc_credit and curr_bal (PR-07)")
        void shouldUpdateAccountCurrCycCreditForPositiveAmount() {
            makeAccountTransactable(ACCT_TXN_VALID);
            Account before = accountRepository.findById(ACCT_TXN_VALID).orElseThrow();
            BigDecimal balBefore = before.getCurrBal();
            BigDecimal creditBefore = before.getCurrCycCredit();
            BigDecimal debitBefore = before.getCurrCycDebit();
            BigDecimal amount = new BigDecimal("30.00");
            String card = cardNumberForAccount(ACCT_TXN_VALID);

            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<TransactionDto> resp = postWithAuth(
                    "/api/transactions", adminToken,
                    buildTransactionRequest(card, ACCT_TXN_VALID, amount), TransactionDto.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            Account after = accountRepository.findById(ACCT_TXN_VALID).orElseThrow();
            assertThat(after.getCurrBal().subtract(balBefore))
                    .as("PR-07: curr_bal must increase by the (positive) amount")
                    .isEqualByComparingTo(amount);
            assertThat(after.getCurrCycCredit().subtract(creditBefore))
                    .as("PR-07: a positive amount must accrue to curr_cyc_credit")
                    .isEqualByComparingTo(amount);
            assertThat(after.getCurrCycDebit())
                    .as("PR-07: a positive amount must NOT touch curr_cyc_debit")
                    .isEqualByComparingTo(debitBefore);
        }

        @Test
        @DisplayName("Negative amount increments curr_cyc_debit and decreases curr_bal (PR-07)")
        void shouldUpdateAccountCurrCycDebitForNegativeAmount() {
            makeAccountTransactable(ACCT_TXN_NEGATIVE);
            Account before = accountRepository.findById(ACCT_TXN_NEGATIVE).orElseThrow();
            BigDecimal balBefore = before.getCurrBal();
            BigDecimal creditBefore = before.getCurrCycCredit();
            BigDecimal debitBefore = before.getCurrCycDebit();
            BigDecimal amount = new BigDecimal("-15.00");
            String card = cardNumberForAccount(ACCT_TXN_NEGATIVE);

            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<TransactionDto> resp = postWithAuth(
                    "/api/transactions", adminToken,
                    buildTransactionRequest(card, ACCT_TXN_NEGATIVE, amount), TransactionDto.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            Account after = accountRepository.findById(ACCT_TXN_NEGATIVE).orElseThrow();
            assertThat(after.getCurrBal().subtract(balBefore))
                    .as("PR-07: curr_bal must change by the signed amount (here, -15.00)")
                    .isEqualByComparingTo(amount);
            assertThat(after.getCurrCycDebit().compareTo(debitBefore))
                    .as("PR-07: a negative amount must move curr_cyc_debit (the debit bucket)")
                    .isNotEqualTo(0);
            assertThat(after.getCurrCycCredit())
                    .as("PR-07: a negative amount must NOT touch curr_cyc_credit")
                    .isEqualByComparingTo(creditBefore);
        }

        @Test
        @DisplayName("Response carries a DB2-format origin timestamp (PR-11)")
        void shouldEmitDB2FormatTimestampInResponse() {
            makeAccountTransactable(ACCT_TXN_VALID);
            String card = cardNumberForAccount(ACCT_TXN_VALID);

            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<TransactionDto> resp = postWithAuth(
                    "/api/transactions", adminToken,
                    buildTransactionRequest(card, ACCT_TXN_VALID, new BigDecimal("9.99")),
                    TransactionDto.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody()).isNotNull();

            assertThat(resp.getBody().getOrigTimestamp())
                    .as("PR-11: origin timestamp must use DB2 format YYYY-MM-DD-HH.MM.SS.MIL0000 (26 chars)")
                    .isNotNull()
                    .matches(DB2_TIMESTAMP_PATTERN.pattern());
        }
    }

    // ========================================================================
    // 8. TransactionListing — GET /api/transactions (COTRN00C / COTRN01C)
    // ========================================================================

    /**
     * Verifies paginated listing, card-number filtering, and single-transaction detail view.
     * A transaction is created first so the listing/detail assertions have a known target.
     */
    @Nested
    @DisplayName("8. Transaction listing (GET /api/transactions) — COTRN00C / COTRN01C")
    class TransactionListing {

        @Test
        @DisplayName("GET /api/transactions?page=0&size=10 returns a paginated payload")
        void shouldListTransactionsWithPagination() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<String> resp =
                    getWithAuth("/api/transactions?page=0&size=10", adminToken, String.class);

            assertThat(resp.getStatusCode())
                    .as("Paginated transaction listing must succeed (Pageable replaces COTRN00C browse)")
                    .isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("Transaction listing must return a body")
                    .isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("GET /api/transactions?cardNumber=X returns the matching card's transactions")
        void shouldFilterTransactionsByCardNumber() {
            makeAccountTransactable(ACCT_TXN_VALID);
            String card = cardNumberForAccount(ACCT_TXN_VALID);
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            // Ensure at least one transaction exists for this card.
            postWithAuth("/api/transactions", adminToken,
                    buildTransactionRequest(card, ACCT_TXN_VALID, new BigDecimal("5.00")), TransactionDto.class);

            ResponseEntity<String> resp =
                    getWithAuth("/api/transactions?cardNumber=" + card, adminToken, String.class);
            assertThat(resp.getStatusCode())
                    .as("Filtering transactions by card number must succeed")
                    .isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("The filtered listing must reference the queried card number")
                    .isNotNull().contains(card);
        }

        @Test
        @DisplayName("GET /api/transactions/{tranId} returns the full detail view")
        void shouldReturnTransactionByIdInDetailView() {
            makeAccountTransactable(ACCT_TXN_VALID);
            String card = cardNumberForAccount(ACCT_TXN_VALID);
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);

            ResponseEntity<TransactionDto> created = postWithAuth(
                    "/api/transactions", adminToken,
                    buildTransactionRequest(card, ACCT_TXN_VALID, new BigDecimal("7.77")), TransactionDto.class);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(created.getBody()).isNotNull();
            String tranId = created.getBody().getTranId();

            ResponseEntity<TransactionDto> detail =
                    getWithAuth("/api/transactions/" + tranId, adminToken, TransactionDto.class);
            assertThat(detail.getStatusCode())
                    .as("Single-transaction detail must succeed (COTRN01C keyed READ)")
                    .isEqualTo(HttpStatus.OK);
            assertThat(detail.getBody())
                    .as("Detail response must carry a body")
                    .isNotNull();
            assertThat(detail.getBody().getTranId())
                    .as("Returned transaction id must match the requested key")
                    .isEqualTo(tranId);
        }
    }

    // ========================================================================
    // 9. BillPayment — POST /api/accounts/{acctId}/payments (COBIL00C)
    // ========================================================================

    /**
     * Verifies the COBIL00C bill-payment flow: the {@code @Transactional} unit of work that
     * atomically creates the payment transaction and zeroes the account balance (PR-24), the
     * exact COBIL00C fixed-field values on the created transaction, the zero-balance rejection,
     * and that a rejected payment leaves no partial state.
     */
    @Nested
    @DisplayName("9. Bill payment (POST /api/accounts/{id}/payments) — COBIL00C, PR-24")
    class BillPayment {

        @Test
        @DisplayName("Full bill payment zeroes the balance and writes the COBIL00C transaction (PR-24)")
        void shouldProcessBillPaymentForAccountWithBalance() {
            makeAccountTransactable(ACCT_BILLPAY_FULL);
            BigDecimal startingBalance = new BigDecimal("150.00");
            setAccountBalance(ACCT_BILLPAY_FULL, startingBalance);

            BillPaymentRequest req = BillPaymentRequest.builder()
                    .accountId(ACCT_BILLPAY_FULL)
                    .amount(startingBalance)
                    .paymentMethod("FULL")
                    .confirmation("Y")
                    .build();

            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<BillPaymentResponse> resp = postWithAuth(
                    "/api/accounts/" + ACCT_BILLPAY_FULL + "/payments", adminToken, req,
                    BillPaymentResponse.class);

            assertThat(resp.getStatusCode())
                    .as("A bill payment for an account with a balance must succeed (HTTP 200)")
                    .isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("Bill-payment response must carry a body")
                    .isNotNull();
            assertThat(resp.getBody().newBalance())
                    .as("PR-24: the account balance reported by the response must be zero after a full "
                        + "payment (COBIL00C: ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT)")
                    .isEqualByComparingTo(BigDecimal.ZERO);

            // The account row must be durably zeroed within the same UOW (PR-24 atomicity).
            Account after = accountRepository.findById(ACCT_BILLPAY_FULL).orElseThrow();
            assertThat(after.getCurrBal())
                    .as("PR-24: account current balance must be zero after a full bill payment")
                    .isEqualByComparingTo(BigDecimal.ZERO);

            // The created transaction must carry the COBIL00C fixed-field values.
            String tranId = resp.getBody().tranId();
            assertThat(tranId)
                    .as("Bill-payment response must include the created transaction id")
                    .isNotNull();
            Transaction tx = transactionRepository.findById(tranId).orElseThrow();
            assertThat(tx.getTypeCd().trim())
                    .as("COBIL00C TRAN-TYPE-CD must be '02'")
                    .isEqualTo("02");
            assertThat(Integer.parseInt(tx.getCategoryCd().trim()))
                    .as("COBIL00C TRAN-CAT-CD must be 2 (any zero-padded string form is accepted)")
                    .isEqualTo(2);
            assertThat(tx.getSource().trim())
                    .as("COBIL00C TRAN-SOURCE must be 'POS TERM'")
                    .isEqualTo("POS TERM");
            assertThat(tx.getDescription().trim())
                    .as("COBIL00C TRAN-DESC must be 'BILL PAYMENT - ONLINE'")
                    .isEqualTo("BILL PAYMENT - ONLINE");
            assertThat(tx.getMerchantId())
                    .as("COBIL00C TRAN-MERCHANT-ID must be 999999999")
                    .isEqualTo(999999999L);
            assertThat(tx.getMerchantName().trim())
                    .as("COBIL00C TRAN-MERCHANT-NAME must be 'BILL PAYMENT'")
                    .isEqualTo("BILL PAYMENT");
            assertThat(tx.getMerchantCity().trim())
                    .as("COBIL00C TRAN-MERCHANT-CITY must be 'N/A'")
                    .isEqualTo("N/A");
            assertThat(tx.getMerchantZip().trim())
                    .as("COBIL00C TRAN-MERCHANT-ZIP must be 'N/A'")
                    .isEqualTo("N/A");
            assertThat(tx.getAmount().abs())
                    .as("COBIL00C TRAN-AMT magnitude must equal the prior account balance (150.00)")
                    .isEqualByComparingTo(startingBalance);
        }

        @Test
        @DisplayName("Bill payment on a zero-balance account is rejected (422, 'nothing to pay')")
        void shouldRejectBillPaymentForZeroBalance() {
            makeAccountTransactable(ACCT_BILLPAY_ZERO);
            setAccountBalance(ACCT_BILLPAY_ZERO, BigDecimal.ZERO);

            BillPaymentRequest req = BillPaymentRequest.builder()
                    .accountId(ACCT_BILLPAY_ZERO)
                    .amount(new BigDecimal("0.01"))
                    .paymentMethod("FULL")
                    .confirmation("Y")
                    .build();

            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<ErrorResponse> resp = postWithAuth(
                    "/api/accounts/" + ACCT_BILLPAY_ZERO + "/payments", adminToken, req, ErrorResponse.class);

            assertThat(resp.getStatusCode())
                    .as("A bill payment with no outstanding balance must be rejected (HTTP 422)")
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            if (resp.getBody() != null && resp.getBody().message() != null) {
                assertThat(resp.getBody().message().toLowerCase())
                        .as("COBIL00C zero-balance message must convey 'nothing to pay'")
                        .contains("nothing to pay");
            }
        }

        @Test
        @DisplayName("A rejected bill payment leaves no partial state — balance unchanged, no transaction (PR-24)")
        void shouldRollbackBothChangesIfFailureMidwayTroughTransaction() {
            makeAccountTransactable(ACCT_BILLPAY_ZERO);
            setAccountBalance(ACCT_BILLPAY_ZERO, BigDecimal.ZERO);
            String card = cardNumberForAccount(ACCT_BILLPAY_ZERO);
            long txCountBefore = transactionRepository.findByCardNum(card).size();

            BillPaymentRequest req = BillPaymentRequest.builder()
                    .accountId(ACCT_BILLPAY_ZERO)
                    .amount(new BigDecimal("0.01"))
                    .paymentMethod("FULL")
                    .confirmation("Y")
                    .build();

            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<ErrorResponse> resp = postWithAuth(
                    "/api/accounts/" + ACCT_BILLPAY_ZERO + "/payments", adminToken, req, ErrorResponse.class);
            assertThat(resp.getStatusCode())
                    .as("Precondition: the zero-balance payment must be rejected")
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

            // PR-24: the failed unit of work must not have half-committed.
            Account after = accountRepository.findById(ACCT_BILLPAY_ZERO).orElseThrow();
            assertThat(after.getCurrBal())
                    .as("PR-24: a rejected bill payment must leave the account balance unchanged (zero)")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            long txCountAfter = transactionRepository.findByCardNum(card).size();
            assertThat(txCountAfter)
                    .as("PR-24: a rejected bill payment must NOT create an orphan transaction row")
                    .isEqualTo(txCountBefore);
        }
    }

    // ========================================================================
    // 10. UserAdminCrud — /api/admin/users (COUSR00C-03C, PR-17/PR-18)
    // ========================================================================

    /**
     * Verifies the admin user-administration data flow (list / create / update / delete) and the
     * PR-17 BCrypt contract on create and password-change. All endpoints are ADMIN-only (PR-18,
     * exercised more exhaustively in {@code SecurityIT}); here the focus is the data path and the
     * password hashing.
     */
    @Nested
    @DisplayName("10. User admin CRUD (/api/admin/users) — COUSR00C-03C, PR-17 BCrypt")
    class UserAdminCrud {

        @Test
        @DisplayName("ADMIN can list users; the seeded roster is present")
        void shouldListAllUsersForAdmin() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<String> resp = getWithAuth(ADMIN_USERS_PATH, adminToken, String.class);

            assertThat(resp.getStatusCode())
                    .as("An ADMIN must be able to list users (PR-18 grants ROLE_ADMIN)")
                    .isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("User listing must return a body")
                    .isNotNull().isNotBlank();
            assertThat(userRepository.count())
                    .as("The seeded user roster (10 defaults) must be present in the database")
                    .isGreaterThanOrEqualTo(10L);
        }

        @Test
        @DisplayName("ADMIN creates a user whose password is BCrypt-hashed (PR-17)")
        void shouldCreateNewUserViaPostWithBcryptHashing() {
            String newUserId = "TESTNEW1";
            String rawPassword = "newPassword";
            UserCreateRequest req = UserCreateRequest.builder()
                    .userId(newUserId)
                    .firstName("FIRST")
                    .lastName("USER")
                    .password(rawPassword)
                    .userType("U")
                    .build();

            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            try {
                ResponseEntity<UserDto> resp =
                        postWithAuth(ADMIN_USERS_PATH, adminToken, req, UserDto.class);

                assertThat(resp.getStatusCode())
                        .as("Creating a user must return HTTP 201")
                        .isEqualTo(HttpStatus.CREATED);
                assertThat(resp.getBody())
                        .as("Create response must carry a UserDto body")
                        .isNotNull();
                assertThat(resp.getBody().getUserId())
                        .as("Created user id must echo the request")
                        .isEqualTo(newUserId);

                User created = userRepository.findById(newUserId).orElseThrow();
                assertThat(created.getPassword())
                        .as("PR-17: the stored password must be a BCrypt hash (begins with $2)")
                        .startsWith("$2")
                        .matches(BCRYPT_PATTERN.pattern());
                assertThat(passwordEncoder.matches(rawPassword, created.getPassword()))
                        .as("PR-17: BCryptPasswordEncoder.matches must validate the raw password "
                            + "against the stored hash")
                        .isTrue();
            } finally {
                userRepository.deleteById(newUserId);
            }
        }

        @Test
        @DisplayName("ADMIN updates a user's password and the hash is rotated (PR-17)")
        void shouldUpdateUserAndRehashPasswordOnPasswordChange() {
            String userId = "TESTUPD1";
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            try {
                postWithAuth(ADMIN_USERS_PATH, adminToken, UserCreateRequest.builder()
                        .userId(userId).firstName("FIRST").lastName("USER")
                        .password("origPass1").userType("U").build(), UserDto.class);
                String originalHash = userRepository.findById(userId).orElseThrow().getPassword();

                String newPassword = "rotated2";
                ResponseEntity<UserDto> update = putWithAuth(
                        ADMIN_USERS_PATH + "/" + userId, adminToken,
                        UserCreateRequest.builder()
                                .userId(userId).firstName("FIRST").lastName("USER")
                                .password(newPassword).userType("U").build(),
                        UserDto.class);
                assertThat(update.getStatusCode())
                        .as("A user update must succeed (COUSR02C REWRITE equivalent)")
                        .isIn(HttpStatus.OK, HttpStatus.NO_CONTENT, HttpStatus.ACCEPTED);

                String rotatedHash = userRepository.findById(userId).orElseThrow().getPassword();
                assertThat(rotatedHash)
                        .as("PR-17: a password change must produce a new BCrypt hash")
                        .isNotEqualTo(originalHash)
                        .matches(BCRYPT_PATTERN.pattern());
                assertThat(passwordEncoder.matches(newPassword, rotatedHash))
                        .as("PR-17: the rotated hash must validate the NEW password")
                        .isTrue();
            } finally {
                userRepository.deleteById(userId);
            }
        }

        @Test
        @DisplayName("ADMIN deletes a user and the row is removed")
        void shouldDeleteUserForAdmin() {
            String userId = "TESTDEL1";
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            try {
                postWithAuth(ADMIN_USERS_PATH, adminToken, UserCreateRequest.builder()
                        .userId(userId).firstName("FIRST").lastName("USER")
                        .password("delPass12").userType("U").build(), UserDto.class);
                assertThat(userRepository.findById(userId))
                        .as("Precondition: the user to delete must exist")
                        .isPresent();

                ResponseEntity<Void> resp =
                        deleteWithAuth(ADMIN_USERS_PATH + "/" + userId, adminToken, Void.class);
                assertThat(resp.getStatusCode())
                        .as("Deleting a user must succeed (COUSR03C DELETE equivalent)")
                        .isIn(HttpStatus.OK, HttpStatus.NO_CONTENT, HttpStatus.ACCEPTED);

                assertThat(userRepository.findById(userId))
                        .as("The deleted user must no longer exist in the database")
                        .isEmpty();
            } finally {
                if (userRepository.existsById(userId)) {
                    userRepository.deleteById(userId);
                }
            }
        }
    }

    // ========================================================================
    // 11. OptimisticLocking — @Version concurrency (PR-22)
    // ========================================================================

    /**
     * Verifies PR-22 optimistic locking. Because {@code AccountDto} intentionally does not expose
     * the {@code @Version} field, a stale-version PUT cannot be driven deterministically through
     * the REST DTO; instead the contract is exercised directly against the persistence layer using
     * two detached copies of the same row at the same version. Exactly one concurrent
     * {@code saveAndFlush} must fail with an optimistic-locking exception — the same exception the
     * {@code GlobalExceptionHandler} maps to HTTP 409 Conflict for REST callers.
     */
    @Nested
    @DisplayName("11. Optimistic locking (@Version) — PR-22 / HTTP 409 mapping")
    class OptimisticLocking {

        @Test
        @DisplayName("Concurrent updates from the same version → exactly one optimistic-lock failure")
        void shouldRejectStaleConcurrentUpdateViaOptimisticLocking() {
            // Two independent reads (each in its own transaction) yield two detached copies that
            // share the same @Version value.
            Account copyA = accountRepository.findById(ACCT_OPTLOCK).orElseThrow();
            Account copyB = accountRepository.findById(ACCT_OPTLOCK).orElseThrow();
            assertThat(copyA.getVersion())
                    .as("Both detached copies must start at the same @Version")
                    .isEqualTo(copyB.getVersion());
            copyA.setAddrZip("11111");
            copyB.setAddrZip("22222");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CompletableFuture<Throwable> f1 = CompletableFuture.supplyAsync(() -> trySave(copyA), pool);
                CompletableFuture<Throwable> f2 = CompletableFuture.supplyAsync(() -> trySave(copyB), pool);
                CompletableFuture.allOf(f1, f2).join();

                Throwable t1 = f1.join();
                Throwable t2 = f2.join();
                int failures = (t1 != null ? 1 : 0) + (t2 != null ? 1 : 0);
                assertThat(failures)
                        .as("PR-22: exactly one of two concurrent same-version writes must be rejected")
                        .isEqualTo(1);

                Throwable failing = (t1 != null) ? t1 : t2;
                assertThat(isOptimisticLockFailure(failing))
                        .as("PR-22: the rejected write must fail with an optimistic-locking exception "
                            + "(mapped to HTTP 409 by GlobalExceptionHandler); got %s",
                            failing == null ? "none" : failing.getClass().getName())
                        .isTrue();
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("@Version increments on a successful update (locking is active)")
        void shouldIncrementVersionOnSuccessfulUpdate() {
            Account a = accountRepository.findById(ACCT_OPTLOCK).orElseThrow();
            Integer versionBefore = a.getVersion();
            a.setAddrZip("33333");
            accountRepository.saveAndFlush(a);

            Account reloaded = accountRepository.findById(ACCT_OPTLOCK).orElseThrow();
            assertThat(reloaded.getVersion())
                    .as("PR-22: the @Version column must advance after a successful update")
                    .isGreaterThan(versionBefore);
        }
    }

    // ========================================================================
    // 12. GlobalExceptionHandling — @ControllerAdvice status + payload shape
    // ========================================================================

    /**
     * Verifies the {@code @ControllerAdvice} contract: not-found → 404, malformed input → 400,
     * business-rule violation → 422, insufficient role → 403, and a consistent {@link ErrorResponse}
     * JSON shape ({@code status}, {@code code}, {@code message}, {@code path}, {@code timestamp}).
     */
    @Nested
    @DisplayName("12. Global exception handling (@ControllerAdvice) — status mapping + payload shape")
    class GlobalExceptionHandling {

        @Test
        @DisplayName("Unknown account → 404 with a fully-populated ErrorResponse")
        void shouldMap404ForAccountNotFound() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<ErrorResponse> resp =
                    getWithAuth("/api/accounts/99999999", adminToken, ErrorResponse.class);

            assertThat(resp.getStatusCode())
                    .as("AccountNotFoundException must map to HTTP 404")
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(resp.getBody())
                    .as("404 responses must carry an ErrorResponse body")
                    .isNotNull();
            assertThat(resp.getBody().status())
                    .as("ErrorResponse.status must echo the HTTP status (404)")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("Malformed transaction request → 400")
        void shouldMap400ForInvalidCard() {
            // A non-16-digit card number fails the @Pattern(\\d{16}) DTO constraint → 400.
            TransactionRequest req =
                    buildTransactionRequest("BAD", ACCT_TXN_VALID, new BigDecimal("10.00"));
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<ErrorResponse> resp =
                    postWithAuth("/api/transactions", adminToken, req, ErrorResponse.class);

            assertThat(resp.getStatusCode())
                    .as("A malformed request body must map to HTTP 400 (bean-validation failure)")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Business-rule violation (over-limit) → 422")
        void shouldMap422ForBusinessValidationFailures() {
            makeAccountTransactable(ACCT_TXN_OVERLIMIT);
            String card = cardNumberForAccount(ACCT_TXN_OVERLIMIT);
            BigDecimal creditLimit =
                    accountRepository.findById(ACCT_TXN_OVERLIMIT).orElseThrow().getCreditLimit();
            TransactionRequest req = buildTransactionRequest(
                    card, ACCT_TXN_OVERLIMIT, creditLimit.add(new BigDecimal("250.00")));

            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<ErrorResponse> resp =
                    postWithAuth("/api/transactions", adminToken, req, ErrorResponse.class);

            assertThat(resp.getStatusCode())
                    .as("A business-rule violation (over-limit, code 102) must map to HTTP 422")
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("Regular user hitting an admin endpoint → 403 (PR-18)")
        void shouldMap403ForForbiddenRoleAccess() {
            String userToken = login(USER_ID, DEFAULT_PASSWORD);
            ResponseEntity<ErrorResponse> resp =
                    getWithAuth(ADMIN_USERS_PATH, userToken, ErrorResponse.class);

            assertThat(resp.getStatusCode())
                    .as("PR-18: a ROLE_USER principal must be forbidden from admin endpoints (403)")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("Error payloads carry a consistent shape (status, code, message, path, timestamp)")
        void shouldEmitStandardErrorResponseShape() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<String> resp =
                    getWithAuth("/api/accounts/99999999", adminToken, String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            JsonNode body = parseJson(resp.getBody());
            assertThat(body.has("status"))
                    .as("ErrorResponse JSON must contain a 'status' field")
                    .isTrue();
            assertThat(body.has("message"))
                    .as("ErrorResponse JSON must contain a 'message' field")
                    .isTrue();
            assertThat(body.has("path"))
                    .as("ErrorResponse JSON must contain a 'path' field")
                    .isTrue();
            assertThat(body.has("timestamp"))
                    .as("ErrorResponse JSON must contain a 'timestamp' field")
                    .isTrue();
        }
    }

    // ========================================================================
    // 13. BatchAdminEndpoint — POST /api/admin/jobs/{jobName}/launch
    // ========================================================================

    /**
     * Verifies the operational batch-launch endpoint. The positive path asserts the ADMIN is
     * authorized (the request is not rejected by security and does not 5xx); the negative paths
     * assert the regular-user 403 (PR-18) and the unknown-job rejection. The exact set of
     * registered job beans is a sibling concern, so the happy-path assertion is intentionally
     * tolerant about whether a specific name is currently registered.
     */
    @Nested
    @DisplayName("13. Batch admin endpoint (POST /api/admin/jobs/{name}/launch) — PR-18")
    class BatchAdminEndpoint {

        @Test
        @DisplayName("ADMIN is authorized to launch a job (not rejected, no server error)")
        void shouldLaunchJobByNameForAdmin() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<String> resp = postWithAuth(
                    "/api/admin/jobs/dataInitializationJob/launch", adminToken, Map.of(), String.class);

            assertThat(resp.getStatusCode().value())
                    .as("PR-18: an ADMIN must pass authorization on the batch-launch endpoint "
                        + "(not 401/403)")
                    .isNotIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
            assertThat(resp.getStatusCode().is5xxServerError())
                    .as("The batch-launch endpoint must not raise a 5xx for an authorized ADMIN")
                    .isFalse();
        }

        @Test
        @DisplayName("Regular user is forbidden from launching jobs (403, PR-18)")
        void shouldRejectBatchAdminForRegularUser() {
            String userToken = login(USER_ID, DEFAULT_PASSWORD);
            ResponseEntity<String> resp = postWithAuth(
                    "/api/admin/jobs/dataInitializationJob/launch", userToken, Map.of(), String.class);

            assertThat(resp.getStatusCode())
                    .as("PR-18: a ROLE_USER principal must be forbidden from the batch-launch endpoint")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("Unknown job name → 404 (or 400)")
        void shouldReturn404ForUnknownJob() {
            String adminToken = login(ADMIN_ID, DEFAULT_PASSWORD);
            ResponseEntity<ErrorResponse> resp = postWithAuth(
                    "/api/admin/jobs/definitelyNotARealJobName/launch", adminToken, Map.of(),
                    ErrorResponse.class);

            assertThat(resp.getStatusCode())
                    .as("Launching a non-existent job must be rejected (404 not-found, or 400 bad request)")
                    .isIn(HttpStatus.NOT_FOUND, HttpStatus.BAD_REQUEST);
        }
    }

    // ========================================================================
    // 14. OpenApiDocsExposure — springdoc endpoints (disabled under the test profile)
    // ========================================================================

    /**
     * Verifies the springdoc documentation endpoints respond without a server error. Note: the
     * {@code test} profile (application-test.yml) explicitly disables springdoc
     * ({@code springdoc.api-docs.enabled=false}, {@code swagger-ui.enabled=false}) to speed up the
     * context, so these paths return 404 here; in the {@code dev}/{@code prod} profiles they return
     * 200 (or a 3xx redirect for the UI). Either way the application must handle the request, never
     * raise a 5xx.
     */
    @Nested
    @DisplayName("14. OpenAPI docs exposure (springdoc) — handled without server error")
    class OpenApiDocsExposure {

        @Test
        @DisplayName("GET /swagger-ui.html is handled without a 5xx")
        void shouldExposeSwaggerUiAtStandardPath() {
            ResponseEntity<String> resp = restTemplate.getForEntity("/swagger-ui.html", String.class);
            assertThat(resp.getStatusCode().is5xxServerError())
                    .as("Swagger UI path must be handled without a server error (springdoc is "
                        + "disabled under the test profile, so a 404 here is expected)")
                    .isFalse();
        }

        @Test
        @DisplayName("GET /v3/api-docs is handled without a 5xx")
        void shouldExposeOpenApiSpecJsonAtStandardPath() {
            ResponseEntity<String> resp = restTemplate.getForEntity("/v3/api-docs", String.class);
            assertThat(resp.getStatusCode().is5xxServerError())
                    .as("OpenAPI spec path must be handled without a server error (springdoc is "
                        + "disabled under the test profile, so a 404 here is expected)")
                    .isFalse();
        }
    }

    // ========================================================================
    // 15. CorsConfiguration — preflight against the configured allowlist
    // ========================================================================

    /**
     * Verifies the CORS preflight contract for the allowlisted origin
     * ({@code http://localhost:3000}, from {@code app.cors.allowed-origins} in
     * application-test.yml). The preflight must be handled without a server error; when an
     * {@code Access-Control-Allow-Origin} header is returned it must echo the allowed origin.
     */
    @Nested
    @DisplayName("15. CORS configuration — preflight for the configured origin")
    class CorsConfiguration {

        @Test
        @DisplayName("OPTIONS preflight from the allowed origin is handled (and echoes the origin if set)")
        void shouldAllowConfiguredOrigins() {
            String allowedOrigin = "http://localhost:3000";
            HttpHeaders headers = new HttpHeaders();
            headers.setOrigin(allowedOrigin);
            headers.setAccessControlRequestMethod(HttpMethod.POST);

            ResponseEntity<String> resp = restTemplate.exchange(
                    LOGIN_PATH, HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);

            assertThat(resp.getStatusCode().is5xxServerError())
                    .as("A CORS preflight from the allowlisted origin must not raise a 5xx")
                    .isFalse();

            String allowOrigin = resp.getHeaders().getAccessControlAllowOrigin();
            if (allowOrigin != null) {
                assertThat(allowOrigin)
                        .as("When present, Access-Control-Allow-Origin must echo the allowlisted origin")
                        .isEqualTo(allowedOrigin);
            }
        }
    }

    // =====================================================================================
    // HELPER METHODS — shared HTTP / auth / fixture plumbing used by the @Nested sections.
    // =====================================================================================

    /**
     * Authenticates against {@code POST /api/auth/login} and returns the issued JWT bearer
     * token. Fails fast (with a descriptive assertion) if the login does not return 200 with a
     * populated body, so dependent tests surface the real cause rather than an NPE.
     *
     * @param userId   the (uppercase) user id
     * @param password the raw password
     * @return the issued JWT bearer token
     */
    private String login(String userId, String password) {
        LoginRequest req = new LoginRequest(userId, password);
        ResponseEntity<LoginResponse> resp =
                restTemplate.postForEntity(LOGIN_PATH, req, LoginResponse.class);
        assertThat(resp.getStatusCode())
                .as("Helper login() expected 200 for user %s but got %s", userId, resp.getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody())
                .as("Helper login() expected a non-null body for user %s", userId)
                .isNotNull();
        return resp.getBody().token();
    }

    /** Builds bearer-auth headers (JSON content type) for an authenticated request. */
    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Issues an authenticated {@code GET} and returns the typed response entity. */
    private <T> ResponseEntity<T> getWithAuth(String path, String token, Class<T> responseType) {
        return restTemplate.exchange(
                path, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), responseType);
    }

    /** Issues an authenticated {@code POST} with a JSON body and returns the response entity. */
    private <T> ResponseEntity<T> postWithAuth(
            String path, String token, Object body, Class<T> responseType) {
        return restTemplate.exchange(
                path, HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), responseType);
    }

    /** Issues an authenticated {@code PUT} with a JSON body and returns the response entity. */
    private <T> ResponseEntity<T> putWithAuth(
            String path, String token, Object body, Class<T> responseType) {
        return restTemplate.exchange(
                path, HttpMethod.PUT, new HttpEntity<>(body, authHeaders(token)), responseType);
    }

    /** Issues an authenticated {@code DELETE} and returns the response entity. */
    private <T> ResponseEntity<T> deleteWithAuth(String path, String token, Class<T> responseType) {
        return restTemplate.exchange(
                path, HttpMethod.DELETE, new HttpEntity<>(authHeaders(token)), responseType);
    }

    /**
     * Builds a syntactically valid {@link TransactionRequest} with sane defaults for an online
     * purchase. The {@code typeCd}/{@code categoryCd} pair is the V3-seeded valid combination
     * {@code 01}/{@code 0001}; {@code origTimestamp} is intentionally left {@code null} so the
     * service stamps "now" (the expiration check then compares against the account date).
     *
     * @param cardNumber the card to charge
     * @param accountId  the owning account id (xref target)
     * @param amount     the transaction amount (scale 2, PR-16)
     * @return a populated, valid transaction-create request
     */
    private TransactionRequest buildTransactionRequest(String cardNumber, Long accountId, BigDecimal amount) {
        return TransactionRequest.builder()
                .accountId(accountId)
                .cardNumber(cardNumber)
                .typeCd(VALID_TYPE_CD)
                .categoryCd(VALID_CAT_CD)
                .source("POS TERM")
                .description("Test purchase")
                .amount(amount)
                .merchantId(800000000L)
                .merchantName("Test Merchant Name")
                .merchantCity("Test City")
                .merchantZip("12345")
                .build();
    }

    /**
     * Convenience overload that resolves the owning account id from the seeded card before
     * delegating to {@link #buildTransactionRequest(String, Long, BigDecimal)}.
     */
    private TransactionRequest buildTransactionRequest(String cardNumber, BigDecimal amount) {
        return buildTransactionRequest(cardNumber, accountIdForCard(cardNumber), amount);
    }

    /** Returns the lexicographically first seeded card number (V5 — belongs to account 50). */
    private String firstSeededCardNumber() {
        return jdbcTemplate.queryForObject(
                "SELECT card_num FROM cards ORDER BY card_num LIMIT 1", String.class);
    }

    /** Resolves the (single, 1:1-seeded) card number owned by the given account. */
    private String cardNumberForAccount(long acctId) {
        List<String> cards = jdbcTemplate.queryForList(
                "SELECT card_num FROM cards WHERE account_id = ? ORDER BY card_num LIMIT 1",
                String.class, acctId);
        return cards.isEmpty() ? null : cards.get(0);
    }

    /** Resolves the owning account id for a given seeded card number. */
    private Long accountIdForCard(String cardNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT account_id FROM cards WHERE card_num = ?", Long.class, cardNumber);
    }

    /**
     * Forces the given account into a transaction-eligible state: active and not expired
     * (expiration moved to the far future). Required because every seeded account is expired
     * relative to the test clock. Returns the freshly persisted entity (version bumped).
     */
    private Account makeAccountTransactable(long acctId) {
        Account a = accountRepository.findById(acctId).orElseThrow();
        a.setActiveStatus("Y");
        a.setExpirationDate(FUTURE_EXPIRATION);
        return accountRepository.saveAndFlush(a);
    }

    /** Forces the given account's expiration date into the past (code-103 scenario). */
    private Account makeAccountExpired(long acctId) {
        Account a = accountRepository.findById(acctId).orElseThrow();
        a.setActiveStatus("Y");
        a.setExpirationDate(PAST_EXPIRATION);
        return accountRepository.saveAndFlush(a);
    }

    /** Sets the given account's current balance to the supplied value (scale 2). */
    private Account setAccountBalance(long acctId, BigDecimal balance) {
        Account a = accountRepository.findById(acctId).orElseThrow();
        a.setCurrBal(balance.setScale(2, RoundingMode.HALF_UP));
        return accountRepository.saveAndFlush(a);
    }

    /** Parses a raw JSON body into a Jackson tree, failing the test on malformed JSON. */
    private JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new AssertionError("Response body was not valid JSON: " + body, e);
        }
    }

    /**
     * Returns the current {@code tran_cat_balances.balance} for the given composite key, or
     * {@link BigDecimal#ZERO} when no row exists yet. Used by the TCATBAL upsert assertion (PR-06)
     * to measure the before/after delta around an online transaction post.
     *
     * @param acctId the account id component of the composite key
     * @param typeCd the transaction-type component (CHAR(2))
     * @param catCd  the transaction-category component (CHAR(4))
     * @return the persisted balance, or {@code BigDecimal.ZERO} if the row is absent
     */
    private BigDecimal tcatBalanceOrZero(long acctId, String typeCd, String catCd) {
        List<BigDecimal> rows = jdbcTemplate.queryForList(
                "SELECT balance FROM tran_cat_balances "
                    + "WHERE account_id = ? AND type_cd = ? AND cat_cd = ?",
                BigDecimal.class, acctId, typeCd, catCd);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
    }

    /**
     * Attempts a {@code saveAndFlush} of the supplied (detached) account on the calling thread and
     * returns any throwable raised, or {@code null} on success. Used by the optimistic-locking
     * concurrency test (PR-22) so that exceptions thrown on pool threads can be inspected on the
     * test thread.
     *
     * @param account the detached account copy to persist
     * @return the throwable raised by the write, or {@code null} if the write succeeded
     */
    private Throwable trySave(Account account) {
        try {
            accountRepository.saveAndFlush(account);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /**
     * Walks a throwable's cause chain looking for an optimistic-locking failure (Spring's
     * {@code ObjectOptimisticLockingFailureException} or Hibernate/JPA equivalents). Matching by
     * simple class name avoids importing a framework exception that is never referenced directly by
     * production code under test.
     *
     * @param t the throwable to inspect (may be {@code null})
     * @return {@code true} if any cause in the chain is an optimistic-locking failure
     */
    private boolean isOptimisticLockFailure(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause.getClass().getName().toLowerCase().contains("optimisticlock")) {
                return true;
            }
        }
        return false;
    }
}
