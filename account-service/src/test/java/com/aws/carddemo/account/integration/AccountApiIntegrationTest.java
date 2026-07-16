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
package com.aws.carddemo.account.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import com.aws.carddemo.account.domain.Account;
import com.aws.carddemo.account.dto.AccountUpdateRequest;
import com.aws.carddemo.account.repository.AccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Top-of-pyramid, full-stack end-to-end integration test for the CardDemo Account Management
 * slice (Feature&nbsp;F-003). It boots the entire Spring application context and drives the
 * account REST API end-to-end &mdash;
 * {@code HTTP -> AccountController -> AccountService -> AccountValidator -> AccountRepository ->
 * PostgreSQL} &mdash; against a <strong>real PostgreSQL&nbsp;17 database provisioned by
 * Testcontainers</strong>, with the true migrated schema and seed applied by Flyway
 * ({@code V1} DDL then {@code V2} 50-row seed) and Hibernate running with
 * {@code ddl-auto=validate}.
 *
 * <h2>Why a real database (no H2 / no in-memory)</h2>
 * <p>Behavioral parity with the retired COBOL programs {@code COACTVWC} (view / {@code CAVW})
 * and {@code COACTUPC} (update / {@code CAUP}) depends on genuine PostgreSQL semantics that an
 * in-memory database cannot faithfully reproduce: {@code NUMERIC(12,2)} exact-precision money,
 * native {@code DATE} and {@code CHAR(1)} types, the POSIX-regex
 * {@code CHECK (account_id ~ '^[0-9]{11}$')} constraint, and the real Hibernate {@code @Version}
 * optimistic-locking flush behavior.</p>
 *
 * <h2>Hermetic datasource wiring (CQ-CONFIG-1)</h2>
 * <p>The PostgreSQL container is a JUnit-managed {@link Container @Container} field
 * ({@code postgres:17-alpine}, tag pinned for reproducibility) started once for the class by the
 * {@link Testcontainers @Testcontainers} extension. Its connection coordinates are published to
 * the Spring {@code Environment} through {@link DynamicPropertySource @DynamicPropertySource}.
 * Spring's dynamic test properties have <em>higher</em> precedence than the operating-system
 * environment, so an ambient {@code SPRING_DATASOURCE_URL}/{@code _USERNAME}/{@code _PASSWORD}
 * (for example one exported for the module's {@code docker} profile) can <strong>not</strong>
 * hijack the test onto a different database &mdash; the container URL always wins. This replaces
 * an earlier {@code jdbc:tc:} URL hard-coded in {@code application-test.yml}, which an ambient
 * {@code SPRING_DATASOURCE_URL} <em>did</em> override, silently repointing the test. A
 * {@link #assertHermeticContainerDatasource() &#64;BeforeEach} guard additionally asserts that the
 * live JDBC connection targets the container's mapped port, so the hermeticity invariant fails
 * loudly rather than silently if it is ever broken. A Docker daemon is a runtime prerequisite.</p>
 *
 * <h2>Bootstrap</h2>
 * <ul>
 *   <li>{@link SpringBootTest} boots the full context with a real embedded server on a
 *       {@code RANDOM_PORT}, so the concurrent-writer scenario ({@code 4.9}) can exercise the true
 *       servlet stack over HTTP via {@link TestRestTemplate}.</li>
 *   <li>{@link AutoConfigureMockMvc} supplies a {@link MockMvc} that exercises the real controller,
 *       the {@code @Validated} path checks, the {@code @Valid @RequestBody} binding, and the central
 *       {@code GlobalExceptionHandler} advice for the single-request scenarios.</li>
 *   <li>{@link ActiveProfiles}{@code ("test")} activates {@code application-test.yml}.</li>
 * </ul>
 *
 * <h2>Test isolation (deliberately NOT {@code @Transactional})</h2>
 * <p>The class is intentionally not annotated {@code @Transactional}: a rolled-back transaction
 * would mask the real commit/flush and optimistic-lock behavior this test exists to prove.
 * Order-independence is instead achieved by giving each mutating scenario its own distinct seed
 * account, so no test depends on another's state:</p>
 * <ul>
 *   <li>Read-only assertions and the seed-parity oracle use accounts other than the mutating set.</li>
 *   <li>The happy update mutates account {@code 00000000010} (balance &rarr; 250.00, version &rarr; 1).</li>
 *   <li>The sequential conflict scenario mutates account {@code 00000000020} (version &rarr; 1).</li>
 *   <li>The concurrent repository race mutates account {@code 00000000030} (version &rarr; 1).</li>
 *   <li>The concurrent HTTP race mutates account {@code 00000000040} (version &rarr; 1).</li>
 *   <li>Bad-input {@code PUT}s target account {@code 00000000001} but never persist, because the
 *       service validates <em>before</em> loading/saving; the row is left untouched.</li>
 * </ul>
 * <p>Consequently the seed row count stays exactly 50 regardless of method execution order, and the
 * seed-parity oracle ({@code 4.7}) verifies every immutable column on all 50 rows while skipping only
 * the two genuinely order-dependent columns ({@code version} and, for the balance-mutating accounts,
 * {@code current_balance}) on the four mutating accounts.</p>
 *
 * <h2>Coverage &mdash; AAP &sect;0.6.5 traceability</h2>
 * <p>The scenarios exercise: happy read with an exact scale-2/ISO field check, not-found (404),
 * invalid input (400) via both business-rule and Bean-Validation paths, sequential stale-version
 * conflict (409), a deterministic <em>concurrent</em> two-writer conflict at both the repository
 * flush backstop and the full HTTP stack (409), happy update with version increment and re-read,
 * database {@code CHECK}-constraint enforcement, and a full 50-row migration seed-parity oracle
 * decoded field-by-field from the legacy flat file. Only HTTP&nbsp;200/400/404/409 are ever
 * expected from the API.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DisplayName("Account REST API — full-stack integration (real PostgreSQL via Testcontainers)")
class AccountApiIntegrationTest {

    static {
        // CQ-OBS-1: the Testcontainers @Container extension starts the database — logging
        // "Container is started (JDBC URL: <url>)" at INFO on the "tc.<image>" logger — during JUnit's
        // beforeAll, which runs BEFORE Spring Boot applies application-test.yml's logging levels. Pin
        // the "tc" parent logger to WARN here, at class-initialization time (before the container is
        // started), so that early URL line is suppressed too. The YAML level covers the running Spring
        // context; this static block covers the narrow pre-Spring window, and 4.10 asserts the result.
        ((Logger) LoggerFactory.getLogger("tc")).setLevel(Level.WARN);
    }

    /** Shared base path for the account resource; the 11-digit id is appended per request. */
    private static final String BASE = "/api/v1/accounts/";

    /**
     * Exact HTTP&nbsp;404 body message produced for a missing account.
     *
     * <p><strong>Contract note (legacy parity, QA finding F-01).</strong> The not-found message
     * reproduces the legacy {@code COACTVWC} text and names the requested account:
     * {@code AccountNotFoundException} builds {@code "Account: <id> not found in Acct Master file."}
     * ({@code app/cbl/COACTVWC.cbl:L796-L805}, modernized to drop the CICS {@code Resp:}/{@code Reas:}
     * diagnostics), and {@code GlobalExceptionHandler} passes it through verbatim. The embedded id is
     * the very key the client supplied on the path, echoed only to that caller in the response body.
     * AAP&nbsp;&sect;0.6.6 ("never <em>log</em> full account numbers") is upheld independently: the 404
     * handler does not log, and the {@code path} field remains the digit-masked route template. This
     * constant is the not-found message for the id used by the not-found scenario ({@code 00000000099}).</p>
     */
    private static final String NOT_FOUND_MESSAGE = "Account: 00000000099 not found in Acct Master file.";

    /**
     * Exact HTTP&nbsp;409 body message, owned by {@code GlobalExceptionHandler} and mandated by
     * AAP&nbsp;&sect;0.6.4 / &sect;4.2.3.2 (the modernized phrasing, not the raw COBOL literal).
     */
    private static final String CONFLICT_MESSAGE = "Record updated by another user - please retry";

    /**
     * Accounts whose {@code version} is advanced (0&nbsp;&rarr;&nbsp;1) by a mutating scenario in this
     * class. Because the class is deliberately not {@code @Transactional}, that increment persists for
     * the run, so the seed-parity oracle can not assert {@code version == 0} for these rows in an
     * order-independent way; it verifies every <em>immutable</em> column for them instead.
     */
    private static final Set<String> VERSION_MUTATING_ACCOUNTS =
            Set.of("00000000010", "00000000020", "00000000030", "00000000040");

    /**
     * Accounts whose {@code current_balance} is changed by a mutating scenario (the happy update to
     * a fixed value, and the concurrent repository race to a non-deterministic winner). Their balance
     * is therefore excluded from the order-independent seed-parity comparison; the other 48 rows still
     * verify the exact decoded balance, and every immutable column is verified for all 50 rows.
     */
    private static final Set<String> BALANCE_MUTATING_ACCOUNTS =
            Set.of("00000000010", "00000000030");

    /**
     * Real PostgreSQL provisioned for the whole test class by the {@link Testcontainers} extension.
     * The image tag is pinned ({@code postgres:17-alpine}) for reproducible parity with the documented
     * Amazon RDS PostgreSQL target. Declared {@code static} so a single container is shared across all
     * methods (started once, stopped once).
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("accountsdb")
            .withUsername("carddemo")
            .withPassword("carddemo");

    /**
     * Publishes the container's connection coordinates as Spring properties. Dynamic test properties
     * outrank the OS environment, so this is what makes the test hermetic against ambient
     * {@code SPRING_DATASOURCE_*} variables (CQ-CONFIG-1).
     *
     * @param registry the dynamic property registry supplied by the Spring TestContext framework
     */
    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    /** Drives the single-request HTTP endpoints against the full, running application context. */
    @Autowired
    private MockMvc mockMvc;

    /**
     * The Spring-configured mapper (JavaTimeModule + {@code write-bigdecimal-as-plain}), used to
     * serialize request bodies and to parse response JSON when building round-trip update requests.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /** Direct JDBC access for database-level assertions (seed oracle and the CHECK negative test). */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The live datasource, used only by the hermeticity guard to inspect the real connection URL. */
    @Autowired
    private DataSource dataSource;

    /** Repository handle for the repository-level concurrent-writer race (4.8). */
    @Autowired
    private AccountRepository accountRepository;

    /** Transaction manager backing the {@link TransactionTemplate}s used by the repository race (4.8). */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Real-HTTP client (bound to the RANDOM_PORT server) for the concurrent HTTP race (4.9). */
    @Autowired
    private TestRestTemplate restTemplate;

    /** The startup schema-integrity guard (F-04), re-invoked against the real container schema. */
    @Autowired
    private com.aws.carddemo.account.config.SchemaIntegrityValidator schemaIntegrityValidator;

    /**
     * Hermeticity guard (CQ-CONFIG-1): before every test, assert that the live JDBC connection points
     * at the Testcontainers PostgreSQL by matching the container's mapped port in the connection URL.
     * If an ambient {@code SPRING_DATASOURCE_*} ever managed to repoint the datasource, this fails
     * loudly instead of letting the suite run against the wrong database.
     *
     * @throws Exception if a connection cannot be obtained
     */
    @BeforeEach
    void assertHermeticContainerDatasource() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            Integer mappedPort = POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
            assertThat(url)
                    .as("Integration test MUST connect to the Testcontainers PostgreSQL (mapped port "
                            + "%s), not any ambient SPRING_DATASOURCE_* database", mappedPort)
                    .contains(":" + mappedPort + "/");
        }
    }

    // ------------------------------------------------------------------
    // 4.1 Happy GET -> 200 + seed-parity oracle
    // ------------------------------------------------------------------

    /**
     * A keyed read of seed account {@code 00000000001} returns HTTP&nbsp;200 and the fully mapped
     * read projection, proving the {@code COACTVWC} {@code DFHRESP(NORMAL)} path. Money renders as
     * plain scale-2 decimals (no scientific notation) and dates as ISO {@code yyyy-MM-dd} strings.
     */
    @Test
    @DisplayName("4.1 GET 00000000001 -> 200 with seed-parity fields, scale-2 money, ISO dates")
    void getExistingAccount_returns200WithSeedParity() throws Exception {
        MvcResult result = mockMvc.perform(get(BASE + "00000000001").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("00000000001"))
                .andExpect(jsonPath("$.activeStatus").value("Y"))
                .andExpect(jsonPath("$.openDate").value("2014-11-20"))
                .andExpect(jsonPath("$.expirationDate").value("2025-05-20"))
                .andExpect(jsonPath("$.reissueDate").value("2025-05-20"))
                .andExpect(jsonPath("$.addressZip").value("A000000000"))
                .andExpect(jsonPath("$.groupId").value(""))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();

        // Authoritative scale-2 / plain-rendering assertion: Spring's compact Jackson output has no
        // space after the colon, so these substrings prove BOTH the value AND the exact 2-dp scale.
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains(
                "\"currentBalance\":194.00",
                "\"creditLimit\":2020.00",
                "\"cashCreditLimit\":1020.00",
                "\"currentCycleCredit\":0.00",
                "\"currentCycleDebit\":0.00");
    }

    // ------------------------------------------------------------------
    // 4.2 Not-found GET -> 404 with exact (id-free) message
    // ------------------------------------------------------------------

    /**
     * A syntactically valid but absent id ({@code 00000000099}, 11 digits so it clears the path
     * {@code @Pattern} and reaches the service) reproduces the legacy {@code COACTVWC} {@code 9300}
     * {@code NOTFND} branch and yields HTTP&nbsp;404 with the exact id-free message.
     */
    @Test
    @DisplayName("4.2 GET 00000000099 -> 404 with exact id-free not-found message")
    void getUnknownAccount_returns404WithExactMessage() throws Exception {
        mockMvc.perform(get(BASE + "00000000099").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(NOT_FOUND_MESSAGE));
    }

    // ------------------------------------------------------------------
    // 4.3 Bad input PUT -> 400 (business-rule + Bean-Validation paths), no mutation
    // ------------------------------------------------------------------

    /**
     * Three independent malformed {@code PUT}s against an existing account each yield HTTP&nbsp;400
     * through the correct mechanism, and none mutates the row (the service validates before it loads
     * or saves):
     * <ol>
     *   <li><strong>Bad date</strong> ({@code openDate = "2023-13-40"}) &mdash; the DTO date fields are
     *       {@code String} with only {@code @NotNull}, so Bean Validation passes and the service's
     *       {@code AccountValidator} strict-parses ({@code ResolverStyle.STRICT}, {@code uuuu-MM-dd},
     *       year 1900&ndash;2099), throwing {@code ValidationException}. Lineage: {@code CSUTLDPY}
     *       date edits + {@code COACTUPC} {@code 1210/1220/1250}.</li>
     *   <li><strong>Over-range money</strong> ({@code currentBalance = 10000000000.00}, 11 integer
     *       digits) &mdash; there is no DTO {@code @Digits} guard, so this reaches
     *       {@code AccountValidator#validateAmount}, which rejects magnitudes above
     *       &plusmn;9,999,999,999.99 with {@code ValidationException}. Lineage: {@code 1250-EDIT-SIGNED-9V2}.</li>
     *   <li><strong>Missing version</strong> ({@code version = null}) &mdash; the {@code @NotNull} on the
     *       required optimistic-lock token fails Bean Validation, raising
     *       {@code MethodArgumentNotValidException} before the service is entered.</li>
     * </ol>
     */
    @Test
    @DisplayName("4.3 PUT invalid body -> 400 (bad date / over-range money / missing version), no mutation")
    void putInvalidRequest_returns400AndDoesNotMutate() throws Exception {
        JsonNode current = getAccountJson("00000000001");

        // (1) Business-rule: invalid calendar date -> AccountValidator -> ValidationException -> 400.
        AccountUpdateRequest badDate = validRequestFrom(current);
        badDate.setOpenDate("2023-13-40");
        mockMvc.perform(put(BASE + "00000000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badDate)))
                .andExpect(status().isBadRequest());

        // (2) Business-rule: monetary magnitude over +/-9,999,999,999.99 -> ValidationException -> 400.
        AccountUpdateRequest overRange = validRequestFrom(current);
        overRange.setCurrentBalance(new BigDecimal("10000000000.00"));
        mockMvc.perform(put(BASE + "00000000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(overRange)))
                .andExpect(status().isBadRequest());

        // (3) Structural: missing required version -> Bean Validation -> MethodArgumentNotValidException -> 400.
        AccountUpdateRequest missingVersion = validRequestFrom(current);
        missingVersion.setVersion(null);
        mockMvc.perform(put(BASE + "00000000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(missingVersion)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // Because validation precedes persistence, account 00000000001 remains at its seed version 0.
        assertThat(currentVersion("00000000001")).isEqualTo(0L);
    }

    // ------------------------------------------------------------------
    // 4.4 Sequential stale-version conflict -> 409 (service up-front check, account 00000000020)
    // ------------------------------------------------------------------

    /**
     * Two sequential {@code PUT}s carrying the same (initially fresh, then stale) version reproduce
     * the legacy {@code COACTUPC} {@code 9700-CHECK-CHANGE-IN-REC} before-image conflict: the first
     * write succeeds and advances the persisted {@code @Version} to 1; replaying the now-stale
     * version&nbsp;0 body is rejected by the service's explicit up-front version comparison, which
     * raises {@code ObjectOptimisticLockingFailureException} and maps to HTTP&nbsp;409 with the exact
     * modernized message. This is the deterministic <em>pre-check</em> half of the concurrency
     * coverage; the true race is proven by {@code 4.8} (repository flush backstop) and {@code 4.9}
     * (full HTTP stack).
     */
    @Test
    @DisplayName("4.4 PUT stale version -> 409 with exact conflict message (account 00000000020)")
    void putStaleVersion_returns409WithExactMessage() throws Exception {
        JsonNode current = getAccountJson("00000000020");
        assertThat(current.get("version").asLong()).isEqualTo(0L);

        // Build a fully valid update echoing the read projection; version is 0 (fresh).
        AccountUpdateRequest request = validRequestFrom(current);

        // First PUT commits and increments the version to 1.
        mockMvc.perform(put(BASE + "00000000020")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // Second PUT re-submits the SAME body (still version 0, now stale) -> deterministic 409.
        mockMvc.perform(put(BASE + "00000000020")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(CONFLICT_MESSAGE));
    }

    // ------------------------------------------------------------------
    // 4.5 Happy PUT -> 200 + version increment; re-GET reflects the change (account 00000000010)
    // ------------------------------------------------------------------

    /**
     * A valid {@code PUT} that changes exactly one field on account {@code 00000000010} succeeds
     * with HTTP&nbsp;200, returns the incremented {@code version} (0&nbsp;&rarr;&nbsp;1) and the new
     * value, and a follow-up {@code GET} confirms the change was committed. This reproduces the
     * {@code COACTUPC} {@code 9600-WRITE-PROCESSING} {@code REWRITE} with its unconditional version
     * advance.
     */
    @Test
    @DisplayName("4.5 PUT change -> 200 with version 1; re-GET reflects committed change (account 00000000010)")
    void putHappyPath_updatesFieldAndIncrementsVersion() throws Exception {
        JsonNode before = getAccountJson("00000000010");
        assertThat(before.get("version").asLong()).isEqualTo(0L);

        // Echo the read projection, then change exactly one field to a new scale-2 value.
        AccountUpdateRequest request = validRequestFrom(before);
        request.setCurrentBalance(new BigDecimal("250.00"));

        String putBody = mockMvc.perform(put(BASE + "00000000010")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(putBody).contains("\"currentBalance\":250.00");

        // Re-read: the change and the incremented version are durably committed.
        String afterBody = mockMvc.perform(get(BASE + "00000000010").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(afterBody).contains("\"currentBalance\":250.00");
    }

    // ------------------------------------------------------------------
    // 4.5a JSON input hardening — control chars (F-08) and oversized body (F-10),
    //      exercised end-to-end through the real servlet filter chain + PostgreSQL.
    // ------------------------------------------------------------------

    /**
     * F-08: a {@code PUT} whose {@code addressZip} carries an embedded {@code NUL} (U+0000) &mdash; an
     * ISO control character PostgreSQL cannot store in a {@code text}/{@code varchar} column &mdash; is
     * rejected cleanly by {@code AccountValidator} as HTTP&nbsp;400 <em>before</em> persistence, rather
     * than reaching the driver and surfacing as an ungraceful HTTP&nbsp;500. The sanitized message
     * names the field only (never the raw value; AAP &sect;0.6.6), and the target account is not
     * mutated. The value ({@code "1234\u00005678"}, 9 chars) satisfies the DTO's {@code @Size(max=10)}
     * and {@code @NotNull}, so only the business well-formedness guard can reject it.
     */
    @Test
    @DisplayName("4.5a PUT addressZip with NUL control char -> 400 (not 500), no mutation")
    void putAddressZipWithControlChar_returns400NotServerError() throws Exception {
        final String id = "00000000005";
        JsonNode current = getAccountJson(id);
        long versionBefore = current.get("version").asLong();

        AccountUpdateRequest badZip = validRequestFrom(current);
        badZip.setAddressZip("1234\u00005678");

        mockMvc.perform(put(BASE + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badZip)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("addressZip must not contain control characters."));

        // The rejection precedes persistence: the account is unchanged.
        assertThat(currentVersion(id)).isEqualTo(versionBefore);
    }

    /**
     * F-10: a {@code PUT} carrying an otherwise-valid body plus a large <em>unknown</em> string
     * property is now rejected by {@code RequestBodySizeLimitFilter} once the total body exceeds the
     * 64&nbsp;KiB byte cap &mdash; closing the gap where a skipped/unknown value of 65,536 or 70,000
     * characters previously slipped past the intended limit and returned HTTP&nbsp;200. Both the
     * 65,536- and 70,000-character cases (each pushing the total body over the cap) now return
     * HTTP&nbsp;400 with the uniform sanitized malformed-body envelope, before deserialization and
     * before the service is reached, so the target account is never mutated.
     */
    @ParameterizedTest
    @ValueSource(ints = {65536, 70000})
    @DisplayName("4.5b PUT with large unknown string over the 64 KiB cap -> 400 (filter), no mutation")
    void putOversizedUnknownString_returns400(int junkLength) throws Exception {
        final String id = "00000000006";
        JsonNode current = getAccountJson(id);
        long versionBefore = current.get("version").asLong();

        // Build a valid body, then splice in a large unknown "junk" string so the *total* body
        // exceeds the cap. The filter rejects on raw byte count before any parsing/binding occurs.
        String valid = objectMapper.writeValueAsString(validRequestFrom(current));
        String oversized = valid.substring(0, valid.length() - 1)
                + ",\"junk\":\"" + "x".repeat(junkLength) + "\"}";
        assertThat(oversized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isGreaterThan(64 * 1024);

        mockMvc.perform(put(BASE + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"));

        // The size rejection precedes the service: the account is unchanged.
        assertThat(currentVersion(id)).isEqualTo(versionBefore);
    }

    // ------------------------------------------------------------------
    // 4.6 Account-id DB CHECK constraint enforced (real PostgreSQL POSIX regex)
    // ------------------------------------------------------------------

    /**
     * Proves the {@code CHECK (account_id ~ '^[0-9]{11}$')} constraint is genuinely enforced by
     * PostgreSQL: a direct {@code INSERT} whose id ({@code 'ABCDEFGHIJK'}) is 11 characters but
     * non-numeric is rejected with a {@link DataIntegrityViolationException}. Every {@code NOT NULL}
     * column is supplied so the constraint violation is the sole possible failure cause; the failed
     * statement is auto-rolled-back under JDBC autocommit, leaving the row count unchanged. This
     * preserves the legacy {@code 1210-EDIT-ACCOUNT} 11-digit numeric domain at the database tier.
     */
    @Test
    @DisplayName("4.6 DB CHECK constraint rejects a non-numeric 11-char account id")
    void checkConstraint_rejectsNonNumericAccountId() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO accounts (account_id, active_status, current_balance, credit_limit, "
                        + "cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, "
                        + "current_cycle_debit, address_zip, group_id, version) VALUES "
                        + "('ABCDEFGHIJK','Y',0,0,0,'2020-01-01','2020-01-01','2020-01-01',0,0,'','',0)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // 4.7 Seed parity -> full 50-row field-by-field oracle decoded from the legacy flat file
    // ------------------------------------------------------------------

    /**
     * Full migration seed-parity oracle (CQ-TEST-2): decodes <strong>every one of the 50 records</strong>
     * of the legacy fixed-length flat file {@code app/data/ASCII/acctdata.txt} directly in the test
     * (fixed offsets + zoned-decimal overpunch sign decode + ISO dates) and compares it, row for row
     * and column for column, against the live rows the {@code V2} Flyway migration seeded into
     * PostgreSQL. This proves the migration decode &mdash; not just aggregate counts &mdash; is exact.
     *
     * <p>Verified for all 50 rows: {@code account_id} (zero-padded 11-char key, in ascending order),
     * {@code active_status}, {@code credit_limit}, {@code cash_credit_limit}, {@code open_date},
     * {@code expiration_date}, {@code reissue_date}, {@code current_cycle_credit},
     * {@code current_cycle_debit}, {@code address_zip}, and {@code group_id} (blank 10-space source
     * normalized to {@code ''}). Monetary columns are asserted both by value and by exact scale&nbsp;2.
     * {@code current_balance} is additionally verified on the 48 rows no scenario mutates, and
     * {@code version} on the 46 rows no scenario mutates; the four mutating accounts still have all
     * of their immutable columns verified (see {@link #VERSION_MUTATING_ACCOUNTS} /
     * {@link #BALANCE_MUTATING_ACCOUNTS}), keeping the assertion order-independent.</p>
     */
    @Test
    @DisplayName("4.7 Seed parity: full 50-row field-by-field oracle decoded from the legacy flat file")
    void seedParity_allFiftyRowsMatchDecodedSourceOracle() throws Exception {
        List<Map<String, Object>> oracle = decodeSourceOracle();
        assertThat(oracle).as("legacy flat file must decode to exactly 50 records").hasSize(50);

        List<Map<String, Object>> live = jdbcTemplate.query(
                "SELECT account_id, active_status, current_balance, credit_limit, cash_credit_limit, "
                        + "open_date, expiration_date, reissue_date, current_cycle_credit, "
                        + "current_cycle_debit, address_zip, group_id, version "
                        + "FROM accounts ORDER BY account_id",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("account_id", rs.getString("account_id"));
                    row.put("active_status", rs.getString("active_status"));
                    row.put("current_balance", rs.getBigDecimal("current_balance"));
                    row.put("credit_limit", rs.getBigDecimal("credit_limit"));
                    row.put("cash_credit_limit", rs.getBigDecimal("cash_credit_limit"));
                    row.put("open_date", rs.getObject("open_date", LocalDate.class));
                    row.put("expiration_date", rs.getObject("expiration_date", LocalDate.class));
                    row.put("reissue_date", rs.getObject("reissue_date", LocalDate.class));
                    row.put("current_cycle_credit", rs.getBigDecimal("current_cycle_credit"));
                    row.put("current_cycle_debit", rs.getBigDecimal("current_cycle_debit"));
                    row.put("address_zip", rs.getString("address_zip"));
                    row.put("group_id", rs.getString("group_id"));
                    row.put("version", rs.getLong("version"));
                    return row;
                });
        assertThat(live).as("migration must have seeded exactly 50 rows").hasSize(50);

        for (int i = 0; i < 50; i++) {
            Map<String, Object> expected = oracle.get(i);
            Map<String, Object> actual = live.get(i);
            String id = (String) actual.get("account_id");

            // Zero-padded ascending id sequence 00000000001..00000000050.
            String expectedId = String.format("%011d", i + 1);
            assertThat(id).as("row %d account_id ordering", i).isEqualTo(expectedId);
            assertThat(id).as("row %d account_id parity", i).isEqualTo(expected.get("account_id"));

            assertThat(actual.get("active_status")).as("row %s active_status", id)
                    .isEqualTo(expected.get("active_status"));

            // Immutable monetary columns: exact value AND exact scale 2, every row.
            assertScale2Equal(id, "credit_limit", actual, expected);
            assertScale2Equal(id, "cash_credit_limit", actual, expected);
            assertScale2Equal(id, "current_cycle_credit", actual, expected);
            assertScale2Equal(id, "current_cycle_debit", actual, expected);

            // Dates and text columns, every row.
            assertThat(actual.get("open_date")).as("row %s open_date", id)
                    .isEqualTo(expected.get("open_date"));
            assertThat(actual.get("expiration_date")).as("row %s expiration_date", id)
                    .isEqualTo(expected.get("expiration_date"));
            assertThat(actual.get("reissue_date")).as("row %s reissue_date", id)
                    .isEqualTo(expected.get("reissue_date"));
            assertThat(actual.get("address_zip")).as("row %s address_zip", id)
                    .isEqualTo(expected.get("address_zip"));
            assertThat(actual.get("group_id")).as("row %s group_id (blank -> '')", id)
                    .isEqualTo(expected.get("group_id"));

            // current_balance: exact on every row a scenario does not mutate.
            if (!BALANCE_MUTATING_ACCOUNTS.contains(id)) {
                assertScale2Equal(id, "current_balance", actual, expected);
            }

            // version: pristine seed value 0 on every row a scenario does not mutate.
            if (!VERSION_MUTATING_ACCOUNTS.contains(id)) {
                assertThat(actual.get("version")).as("row %s pristine seed version", id).isEqualTo(0L);
            }
        }
    }

    // ------------------------------------------------------------------
    // 4.8 Concurrent repository writers -> exactly one commits (flush @Version backstop), account 00000000030
    // ------------------------------------------------------------------

    /**
     * Deterministic <em>concurrent</em> two-writer race at the repository/flush tier (CQ-TEST-1),
     * exercising the {@code @Version} optimistic-lock backstop that the sequential pre-check in
     * {@code 4.4} can not reach. Two threads each open their own transaction, load account
     * {@code 00000000030} (both observe {@code version = 0}), and then &mdash; released together by a
     * {@link CyclicBarrier} so both hold the same before-image &mdash; call
     * {@code saveAndFlush}. PostgreSQL row-locking serializes the two {@code UPDATE ... WHERE version=0}
     * statements: exactly one matches a row and commits (advancing the version to 1); the other matches
     * zero rows and Hibernate raises a {@code StaleObjectStateException}, surfaced by Spring as
     * {@link OptimisticLockingFailureException}. The assertion is outcome-based and order-independent:
     * one {@code COMMITTED}, one {@code CONFLICT}, and a final persisted {@code version} of exactly 1.
     * This is the modern equivalent of the legacy {@code 9700-CHECK-CHANGE-IN-REC} before-image guard.
     */
    @Test
    @DisplayName("4.8 Concurrent repository writers -> one commit, one optimistic-lock conflict; version -> 1")
    void concurrentRepositoryWriters_flushBackstop_exactlyOneWins() throws Exception {
        final String id = "00000000030";
        assertThat(currentVersion(id)).as("account %s must start at seed version 0", id).isEqualTo(0L);

        CyclicBarrier readBarrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(repositoryWriter(id, new BigDecimal("111.00"), readBarrier));
            Future<String> second = pool.submit(repositoryWriter(id, new BigDecimal("222.00"), readBarrier));
            List<String> outcomes = List.of(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("COMMITTED", "CONFLICT");
        } finally {
            pool.shutdownNow();
        }

        // The winning write advanced the optimistic-lock version exactly once.
        assertThat(currentVersion(id)).as("exactly one concurrent write must commit").isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // 4.9 Concurrent HTTP PUTs (real server) -> one 200, one 409, account 00000000040
    // ------------------------------------------------------------------

    /**
     * Deterministic <em>concurrent</em> two-writer race across the full HTTP stack (CQ-TEST-1) against
     * the real embedded server on the random port, using {@link TestRestTemplate}. Both threads first
     * read account {@code 00000000040} (version 0), then &mdash; released together by a
     * {@link CyclicBarrier} &mdash; {@code PUT} the identical fresh-version body. Whether the loser is
     * rejected by the service's up-front version comparison or by the flush-time {@code @Version}
     * backstop, the observable outcome is invariant: exactly one HTTP&nbsp;200 and one HTTP&nbsp;409,
     * with the persisted {@code version} advanced to exactly 1. This proves the 409 conflict semantics
     * end-to-end under genuine concurrency, not merely the sequential replay of {@code 4.4}.
     */
    @Test
    @DisplayName("4.9 Concurrent HTTP PUTs -> exactly one 200 and one 409; version -> 1 (account 00000000040)")
    void concurrentHttpPuts_fullStack_oneSucceedsOneConflicts() throws Exception {
        final String id = "00000000040";

        ResponseEntity<String> readResponse = restTemplate.getForEntity(BASE + id, String.class);
        assertThat(readResponse.getStatusCode().value()).isEqualTo(200);
        AccountUpdateRequest request = validRequestFrom(objectMapper.readTree(readResponse.getBody()));
        assertThat(request.getVersion()).as("account %s must start at seed version 0", id).isEqualTo(0L);
        final String payload = objectMapper.writeValueAsString(request);

        CyclicBarrier startBarrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> concurrentPut = () -> {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<String> entity = new HttpEntity<>(payload, headers);
                awaitBarrier(startBarrier);
                return restTemplate.exchange(BASE + id, HttpMethod.PUT, entity, String.class)
                        .getStatusCode().value();
            };
            Future<Integer> first = pool.submit(concurrentPut);
            Future<Integer> second = pool.submit(concurrentPut);
            List<Integer> statuses = List.of(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        // Exactly one of the two concurrent writers committed, advancing the version once.
        assertThat(currentVersion(id)).as("exactly one concurrent PUT must commit").isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // 4.10 Log hygiene -> JDBC-URL-bearing loggers are pinned >= WARN (CQ-OBS-1)
    // ------------------------------------------------------------------

    /**
     * CQ-OBS-1 log-hygiene assertion: the loggers empirically found to print the datasource JDBC URL
     * at INFO on startup &mdash; Flyway's {@code org.flywaydb.core.FlywayExecutor}
     * ("Database: {@code <url>} ...") and the Testcontainers container logger {@code tc}
     * ("Container is started (JDBC URL: {@code <url>})") &mdash; are pinned at {@code WARN} or higher
     * in the active configuration, so the connection URL (and therefore the database host) is never
     * emitted to the logs (AAP&nbsp;&sect;0.6.6 log hygiene). Asserting the <em>effective</em> Logback
     * level in the running context proves the {@code application.yml} / {@code application-test.yml}
     * redaction is genuinely in force rather than merely documented.
     */
    @Test
    @DisplayName("4.10 Log hygiene: JDBC-URL-bearing loggers (Flyway, Testcontainers) are pinned >= WARN")
    void logHygiene_jdbcUrlBearingLoggersArePinnedAtWarn() {
        assertLoggerPinnedAtLeastWarn("org.flywaydb.core.FlywayExecutor");
        assertLoggerPinnedAtLeastWarn("tc");
    }

    // ------------------------------------------------------------------
    // 4.11 OpenAPI contract completeness -> generated /v3/api-docs is fully described (CQ-API-1)
    // ------------------------------------------------------------------

    /**
     * CQ-API-1 contract-completeness assertion: fetches the springdoc-generated OpenAPI 3 document
     * from {@code GET /v3/api-docs} (served by {@code springdoc-openapi-starter-webmvc-ui}) and proves
     * the machine-readable contract is genuinely complete &mdash; both operations, every documented
     * status code, all three component schemas, and the field-level {@code @Schema} metadata
     * (types, formats, patterns, numeric ranges, enums, {@code maxLength}, {@code required} sets, and
     * examples) that the DTOs now carry. Asserting against the <em>generated</em> document (rather than
     * merely eyeballing the annotations) is what makes this a true regression guard: if any
     * {@code @Schema} is dropped or a field is added/removed, this test fails.
     *
     * <p>Run inside the full-context integration test (not a {@code @WebMvcTest} slice) because the
     * springdoc {@code /v3/api-docs} endpoint is only auto-configured when the complete application
     * context is bootstrapped.</p>
     *
     * <p>Structural read-only tightening (AAP&nbsp;&sect;0.7.2) is also verified at the contract level:
     * the write model {@code AccountUpdateRequest} exposes neither {@code accountId} nor {@code groupId},
     * while the read model {@code AccountResponse} exposes both.</p>
     */
    @Test
    @DisplayName("4.11 OpenAPI /v3/api-docs is complete: operations, statuses, schemas, field metadata (CQ-API-1)")
    void openApiDocument_isCompleteAndWellTyped() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode api = objectMapper.readTree(json);

        // --- Document is OpenAPI 3.x ---
        assertThat(api.path("openapi").asText())
                .as("generated document must declare OpenAPI 3.x").startsWith("3.");

        // --- Both operations exist on the single resource path ---
        JsonNode pathItem = api.path("paths").path("/api/v1/accounts/{accountId}");
        assertThat(pathItem.isMissingNode())
                .as("path /api/v1/accounts/{accountId} must be documented").isFalse();
        assertThat(pathItem.has("get")).as("GET operation must be documented").isTrue();
        assertThat(pathItem.has("put")).as("PUT operation must be documented").isTrue();

        // --- The account-id path parameter is documented with an example (masked-safe sample id) ---
        assertThat(operationHasPathParam(pathItem.path("get"), "accountId"))
                .as("GET must document the accountId path parameter").isTrue();
        assertThat(operationHasPathParam(pathItem.path("put"), "accountId"))
                .as("PUT must document the accountId path parameter").isTrue();

        // --- Documented status codes: GET 200/400/404, PUT 200/400/404/409 ---
        JsonNode getResponses = pathItem.path("get").path("responses");
        assertThat(getResponses.has("200")).as("GET 200 must be documented").isTrue();
        assertThat(getResponses.has("400")).as("GET 400 must be documented").isTrue();
        assertThat(getResponses.has("404")).as("GET 404 must be documented").isTrue();

        JsonNode putResponses = pathItem.path("put").path("responses");
        assertThat(putResponses.has("200")).as("PUT 200 must be documented").isTrue();
        assertThat(putResponses.has("400")).as("PUT 400 must be documented").isTrue();
        assertThat(putResponses.has("404")).as("PUT 404 must be documented").isTrue();
        assertThat(putResponses.has("409")).as("PUT 409 (optimistic-lock conflict) must be documented").isTrue();

        // --- All three component schemas are generated ---
        JsonNode schemas = api.path("components").path("schemas");
        assertThat(schemas.has("AccountUpdateRequest")).as("AccountUpdateRequest schema").isTrue();
        assertThat(schemas.has("AccountResponse")).as("AccountResponse schema").isTrue();
        assertThat(schemas.has("ApiError")).as("ApiError schema").isTrue();

        // ================= AccountUpdateRequest (write model) =================
        JsonNode req = schemas.path("AccountUpdateRequest");
        JsonNode reqProps = req.path("properties");

        // Read-only tightening: the write model exposes NEITHER accountId NOR groupId (AAP §0.7.2).
        assertThat(reqProps.has("accountId"))
                .as("AccountUpdateRequest must NOT expose the immutable accountId").isFalse();
        assertThat(reqProps.has("groupId"))
                .as("AccountUpdateRequest must NOT expose the read-only groupId").isFalse();

        // All eleven editable fields are marked required.
        assertThat(requiredSet(req)).as("AccountUpdateRequest required set").contains(
                "activeStatus", "currentBalance", "creditLimit", "cashCreditLimit", "openDate",
                "expirationDate", "reissueDate", "currentCycleCredit", "currentCycleDebit", "addressZip",
                "version");

        // activeStatus: string enum {Y,N}, single character.
        JsonNode activeStatus = reqProps.path("activeStatus");
        assertThat(activeStatus.path("type").asText()).isEqualTo("string");
        assertThat(enumValues(activeStatus)).containsExactlyInAnyOrder("Y", "N");
        assertThat(activeStatus.path("maxLength").asInt()).isEqualTo(1);
        assertThat(activeStatus.path("minLength").asInt()).isEqualTo(1);
        assertThat(activeStatus.has("example")).as("activeStatus example").isTrue();

        // Money fields: number, exact scale-2 (+/-9,999,999,999.99), multipleOf 0.01, with examples.
        for (String money : new String[] {"currentBalance", "creditLimit", "cashCreditLimit",
                "currentCycleCredit", "currentCycleDebit"}) {
            JsonNode m = reqProps.path(money);
            assertThat(m.path("type").asText()).as("%s type", money).isEqualTo("number");
            assertThat(new BigDecimal(m.path("minimum").asText()))
                    .as("%s minimum", money).isEqualByComparingTo("-9999999999.99");
            assertThat(new BigDecimal(m.path("maximum").asText()))
                    .as("%s maximum", money).isEqualByComparingTo("9999999999.99");
            assertThat(new BigDecimal(m.path("multipleOf").asText()))
                    .as("%s multipleOf", money).isEqualByComparingTo("0.01");
            assertThat(m.has("example")).as("%s example", money).isTrue();
        }

        // Date fields (String-typed on the write model): ISO pattern expressing the documented legacy
        // domain (year 1900-2099, month 01-12, day 01-31) + example. Cross-field calendar validity
        // (leap year, days-per-month) is enforced by AccountValidator, not by the pattern.
        for (String date : new String[] {"openDate", "expirationDate", "reissueDate"}) {
            JsonNode d = reqProps.path(date);
            assertThat(d.path("type").asText()).as("%s type", date).isEqualTo("string");
            assertThat(d.path("pattern").asText()).as("%s pattern", date)
                    .isEqualTo("^(19|20)\\d{2}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$");
            assertThat(d.has("example")).as("%s example", date).isTrue();
        }

        // addressZip: string, maxLength 10.
        assertThat(reqProps.path("addressZip").path("type").asText()).isEqualTo("string");
        assertThat(reqProps.path("addressZip").path("maxLength").asInt()).isEqualTo(10);

        // version: integer int64, minimum 0.
        JsonNode reqVersion = reqProps.path("version");
        assertThat(reqVersion.path("type").asText()).isEqualTo("integer");
        assertThat(reqVersion.path("format").asText()).isEqualTo("int64");
        assertThat(new BigDecimal(reqVersion.path("minimum").asText())).isEqualByComparingTo("0");

        // ================= AccountResponse (read model) =================
        JsonNode resp = schemas.path("AccountResponse");
        JsonNode respProps = resp.path("properties");

        // Full read model: all thirteen fields required (always populated on a response).
        assertThat(requiredSet(resp)).as("AccountResponse required set").contains(
                "accountId", "activeStatus", "currentBalance", "creditLimit", "cashCreditLimit", "openDate",
                "expirationDate", "reissueDate", "currentCycleCredit", "currentCycleDebit", "addressZip",
                "groupId", "version");

        // accountId: 11-digit zero-padded numeric string pattern, with example.
        JsonNode accountId = respProps.path("accountId");
        assertThat(accountId.path("type").asText()).isEqualTo("string");
        assertThat(accountId.path("pattern").asText()).isEqualTo("^\\d{11}$");
        assertThat(accountId.path("example").asText()).isEqualTo("00000000001");

        // The read model exposes groupId (unlike the write model).
        assertThat(respProps.has("groupId")).as("AccountResponse must expose groupId").isTrue();

        // Response date fields are LocalDate -> string/format=date.
        for (String date : new String[] {"openDate", "expirationDate", "reissueDate"}) {
            JsonNode d = respProps.path(date);
            assertThat(d.path("type").asText()).as("response %s type", date).isEqualTo("string");
            assertThat(d.path("format").asText()).as("response %s format", date).isEqualTo("date");
        }

        // Response money fields: number, exact scale-2 range.
        JsonNode respBalance = respProps.path("currentBalance");
        assertThat(respBalance.path("type").asText()).isEqualTo("number");
        assertThat(new BigDecimal(respBalance.path("minimum").asText()))
                .isEqualByComparingTo("-9999999999.99");
        assertThat(new BigDecimal(respBalance.path("maximum").asText()))
                .isEqualByComparingTo("9999999999.99");

        // Response version: integer int64, minimum 0.
        JsonNode respVersion = respProps.path("version");
        assertThat(respVersion.path("type").asText()).isEqualTo("integer");
        assertThat(new BigDecimal(respVersion.path("minimum").asText())).isEqualByComparingTo("0");

        // ================= ApiError (error body) =================
        JsonNode apiError = schemas.path("ApiError");
        JsonNode errProps = apiError.path("properties");

        // Always-present fields are required; the optional fieldErrors map is NOT required.
        Set<String> errRequired = requiredSet(apiError);
        assertThat(errRequired).as("ApiError required set")
                .contains("timestamp", "status", "error", "message", "path");
        assertThat(errRequired).as("ApiError optional fieldErrors must not be required")
                .doesNotContain("fieldErrors");

        assertThat(errProps.path("status").path("type").asText()).isEqualTo("integer");
        assertThat(errProps.path("timestamp").path("format").asText()).isEqualTo("date-time");
        assertThat(errProps.path("path").path("type").asText()).isEqualTo("string");
        assertThat(errProps.has("fieldErrors")).as("ApiError must document the fieldErrors property").isTrue();
    }

    // ------------------------------------------------------------------
    // 4.12 Security response headers (finding F-11)
    // ------------------------------------------------------------------

    /**
     * A successful account read carries the baseline security headers and, because it returns
     * sensitive financial data, the full cache-suppression set. Exercised through the real filter
     * chain (the {@code @SpringBootTest} MockMvc runs every registered servlet {@code Filter},
     * including {@code SecurityHeadersFilter}).
     *
     * <p>{@code X-Content-Type-Options: nosniff} and {@code X-Frame-Options: DENY} are the universal
     * pair; {@code Cache-Control: no-store}, {@code Pragma: no-cache}, and {@code Expires: 0} are the
     * account-data cache suppressors that keep balances/limits out of any shared or browser cache
     * (AAP &sect;0.6.6).</p>
     */
    @Test
    @DisplayName("4.12 GET account carries nosniff + frame-deny + no-store cache suppression (F-11)")
    void getAccount_carriesSecurityHeaders() throws Exception {
        mockMvc.perform(get(BASE + "00000000001").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().string(HttpHeaders.EXPIRES, "0"));
    }

    /**
     * A non-account surface (the OpenAPI document) still receives the universal {@code nosniff} /
     * frame-deny headers, but is deliberately left cacheable &mdash; the {@code no-store} directive is
     * scoped to the account-data API only, so documentation assets are not needlessly made
     * non-cacheable.
     */
    @Test
    @DisplayName("4.13 Non-account surface gets nosniff/frame-deny but NOT no-store (F-11 scoping)")
    void nonAccountSurface_getsUniversalHeadersOnly() throws Exception {
        mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL));
    }

    // ------------------------------------------------------------------
    // 4.14 Uniform ApiError envelope for container/servlet error dispatches (finding F-13)
    // ------------------------------------------------------------------

    /**
     * An unmatched route requested with {@code Accept: text/html} previously fell through to Spring
     * Boot's Whitelabel HTML error page (the {@code ApiError} the advice produced cannot be rendered
     * as {@code text/html}, so the container re-dispatches to {@code /error}). With the custom
     * {@code ApiErrorController} owning {@code /error}, the final envelope is the uniform sanitized
     * {@link com.aws.carddemo.account.exception.ApiError} JSON &mdash; a {@code 404} whose body carries
     * a generic message and the digit-masked path, never HTML.
     */
    @Test
    @DisplayName("4.14 Unknown route with Accept: text/html now yields ApiError JSON, not Whitelabel HTML (F-13)")
    void unknownRoute_htmlAccept_yieldsApiErrorJson() throws Exception {
        // Exercised through the REAL embedded Tomcat (TestRestTemplate on the RANDOM_PORT server) so
        // the genuine servlet error dispatch that produced the Whitelabel page is executed.
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_HTML));
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/nonexistent", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getContentType())
                .as("error envelope must be JSON, not Whitelabel HTML")
                .isNotNull()
                .matches(mt -> mt.isCompatibleWith(MediaType.APPLICATION_JSON));
        assertThat(response.getBody()).as("must not be the Whitelabel HTML page")
                .doesNotContain("Whitelabel");
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("status").asInt()).isEqualTo(404);
        assertThat(body.path("error").asText()).isEqualTo("Not Found");
        assertThat(body.path("message").asText()).isEqualTo("Requested resource was not found");
        assertThat(body.path("timestamp").isMissingNode()).isFalse();
    }

    /**
     * A direct hit on {@code /error} (no error attributes present) mirrors Boot's own default of
     * {@code 500} but renders the uniform {@link com.aws.carddemo.account.exception.ApiError} JSON
     * envelope with a generic, sanitized message &mdash; not the Whitelabel HTML page. The path is the
     * masked {@code /error} literal. Verified through the real embedded Tomcat.
     */
    @Test
    @DisplayName("4.15 Direct GET /error yields sanitized ApiError JSON (F-13)")
    void directError_yieldsApiErrorJson() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_HTML));
        ResponseEntity<String> response = restTemplate.exchange(
                "/error", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .matches(mt -> mt.isCompatibleWith(MediaType.APPLICATION_JSON));
        assertThat(response.getBody()).doesNotContain("Whitelabel");
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("status").asInt()).isEqualTo(500);
        assertThat(body.path("message").asText())
                .isEqualTo("An unexpected error occurred while processing the request");
        assertThat(body.path("path").asText()).isEqualTo("/error");
        assertThat(body.path("message").asText())
                .as("sanitized: no exception detail may leak").doesNotContain("Exception");
    }

    /**
     * Regression guard: the JSON-accept unmatched-route path is unchanged &mdash; it is still handled
     * by {@code GlobalExceptionHandler#handleNoResourceFound} (the advice, not {@code /error}) and
     * returns the same {@code 404} {@link com.aws.carddemo.account.exception.ApiError} JSON as before
     * the F-13 fix.
     */
    @Test
    @DisplayName("4.16 Unknown route with Accept: application/json still 404 ApiError via advice (F-13 regression guard)")
    void unknownRoute_jsonAccept_stillAdviceHandled() throws Exception {
        mockMvc.perform(get("/api/v1/nonexistent").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Requested resource was not found"));
    }

    // ------------------------------------------------------------------
    // 4.17 Schema-integrity startup guard passes on the correct schema (finding F-04)
    // ------------------------------------------------------------------

    /**
     * The {@code SchemaIntegrityValidator} runs at context startup; the very fact this
     * {@code @SpringBootTest} context loaded proves it accepted the Flyway-migrated schema. This test
     * re-invokes it against the live Testcontainers database to assert explicitly that the correct
     * {@code NUMERIC(12,2) NOT NULL} money columns and {@code BIGINT NOT NULL} version pass without a
     * fail-fast (the negative precision/scale/nullability drift cases are covered by the isolated
     * {@code SchemaIntegrityValidatorTest}, and end-to-end startup-abort-on-drift is verified at
     * runtime).
     */
    @Test
    @DisplayName("4.17 SchemaIntegrityValidator accepts the correct NUMERIC(12,2)/BIGINT schema (F-04)")
    void schemaIntegrityValidator_passesOnCorrectSchema() {
        assertThat(schemaIntegrityValidator).as("F-04 guard must be a registered bean").isNotNull();
        assertThatCode(schemaIntegrityValidator::afterPropertiesSet)
                .as("correct migrated schema must pass the precision/scale/nullability check")
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // 4.18 Kubernetes-style health probes are exposed and UP (finding F-05)
    // ------------------------------------------------------------------

    /**
     * With {@code management.endpoint.health.probes.enabled=true}, both probe endpoints are exposed
     * in every environment and report UP while the database is reachable. The readiness group now
     * includes the {@code db} indicator (F-05), so a healthy database yields readiness UP; the runtime
     * outage test verifies the DOWN transition. {@code show-details=never} keeps the body free of any
     * datasource internals.
     */
    @Test
    @DisplayName("4.18 /actuator/health/readiness and /liveness are exposed and UP (F-05)")
    void healthProbes_areExposedAndUp() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // ------------------------------------------------------------------
    // Private helpers (single-class scope; no separate helper files per the brief)
    // ------------------------------------------------------------------

    /**
     * Collects a schema's {@code required} array into a set of property names. Returns an empty set
     * when the schema declares no {@code required} array.
     *
     * @param schema an OpenAPI object-schema node
     * @return the set of required property names (never {@code null})
     */
    private static Set<String> requiredSet(JsonNode schema) {
        Set<String> names = new java.util.LinkedHashSet<>();
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            required.forEach(node -> names.add(node.asText()));
        }
        return names;
    }

    /**
     * Collects a property schema's {@code enum} array into a set of string values. Returns an empty
     * set when the property declares no {@code enum} array.
     *
     * @param property an OpenAPI property-schema node
     * @return the set of enum string values (never {@code null})
     */
    private static Set<String> enumValues(JsonNode property) {
        Set<String> values = new java.util.LinkedHashSet<>();
        JsonNode enumNode = property.path("enum");
        if (enumNode.isArray()) {
            enumNode.forEach(node -> values.add(node.asText()));
        }
        return values;
    }

    /**
     * Returns {@code true} when the given operation node documents a path parameter with the given
     * name (springdoc emits path parameters in the operation's {@code parameters} array with
     * {@code "in":"path"}).
     *
     * @param operation the OpenAPI operation node (e.g. the {@code get} or {@code put} node)
     * @param name      the expected parameter name
     * @return whether a matching {@code in:path} parameter is present
     */
    private static boolean operationHasPathParam(JsonNode operation, String name) {
        JsonNode params = operation.path("parameters");
        if (params.isArray()) {
            for (JsonNode p : params) {
                if ("path".equals(p.path("in").asText()) && name.equals(p.path("name").asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Asserts that the given logger's effective Logback level is {@code WARN} or higher, so it can not
     * emit its INFO-level JDBC-URL line (CQ-OBS-1). The SLF4J logger is a Logback logger at runtime
     * (spring-boot-starter-logging), so the cast is safe.
     *
     * @param loggerName the logger category to check
     */
    private static void assertLoggerPinnedAtLeastWarn(String loggerName) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        assertThat(logger.getEffectiveLevel().toInt())
                .as("logger %s must be pinned at WARN or higher so it never emits the JDBC URL", loggerName)
                .isGreaterThanOrEqualTo(Level.WARN.toInt());
    }

    /**
     * Performs a {@code GET} for the given account, asserts HTTP&nbsp;200, and returns the response
     * body parsed as a Jackson {@link JsonNode}. Used as the source of truth for building faithful
     * round-trip update requests in the mutating scenarios.
     *
     * @param accountId the zero-padded 11-digit account key
     * @return the parsed JSON response body
     * @throws Exception if the request cannot be performed
     */
    private JsonNode getAccountJson(String accountId) throws Exception {
        String body = mockMvc.perform(get(BASE + accountId).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /**
     * Reads the current optimistic-lock {@code version} of a row straight from PostgreSQL.
     *
     * @param accountId the zero-padded 11-digit account key
     * @return the persisted {@code version}
     */
    private Long currentVersion(String accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM accounts WHERE account_id = ?", Long.class, accountId);
    }

    /**
     * Builds a fully populated, valid {@link AccountUpdateRequest} from a {@code GET} response node,
     * echoing every editable field plus the optimistic-lock {@code version}. The three date fields
     * are copied as their ISO {@code yyyy-MM-dd} strings (the DTO models them as {@code String} on
     * purpose so invalid values reach {@code AccountValidator}); monetary fields are reconstructed as
     * exact-scale {@link BigDecimal}s from their plain-text rendering. Callers mutate a single field
     * afterward to craft valid or invalid scenarios while keeping every other field correct.
     *
     * @param node a {@code GET} response body parsed as JSON
     * @return a valid, ready-to-serialize update request mirroring {@code node}
     */
    private AccountUpdateRequest validRequestFrom(JsonNode node) {
        AccountUpdateRequest request = new AccountUpdateRequest();
        request.setActiveStatus(node.get("activeStatus").asText());
        request.setCurrentBalance(new BigDecimal(node.get("currentBalance").asText()));
        request.setCreditLimit(new BigDecimal(node.get("creditLimit").asText()));
        request.setCashCreditLimit(new BigDecimal(node.get("cashCreditLimit").asText()));
        request.setOpenDate(node.get("openDate").asText());
        request.setExpirationDate(node.get("expirationDate").asText());
        request.setReissueDate(node.get("reissueDate").asText());
        request.setCurrentCycleCredit(new BigDecimal(node.get("currentCycleCredit").asText()));
        request.setCurrentCycleDebit(new BigDecimal(node.get("currentCycleDebit").asText()));
        request.setAddressZip(node.get("addressZip").asText());
        request.setVersion(node.get("version").asLong());
        return request;
    }

    /**
     * Builds a {@link Callable} that, in its own transaction, loads the given account, changes its
     * balance, waits on the shared barrier (so both racers hold the same before-image), and flushes.
     * Returns {@code "COMMITTED"} if the write wins the race or {@code "CONFLICT"} if the flush hits
     * the {@code @Version} optimistic-lock backstop.
     *
     * @param accountId  the account to contend on
     * @param newBalance the balance this writer attempts to persist
     * @param barrier    the two-party barrier that releases both writers together after each has read
     * @return a callable yielding the writer's race outcome
     */
    private Callable<String> repositoryWriter(String accountId, BigDecimal newBalance, CyclicBarrier barrier) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return () -> {
            try {
                transactionTemplate.execute(status -> {
                    Account account = accountRepository.findById(accountId)
                            .orElseThrow(() -> new IllegalStateException("seed row missing"));
                    account.setCurrentBalance(newBalance);
                    awaitBarrier(barrier);              // both writers now hold before-image version 0
                    accountRepository.saveAndFlush(account); // flush triggers the @Version check
                    return null;
                });
                return "COMMITTED";
            } catch (OptimisticLockingFailureException ex) {
                return "CONFLICT";
            }
        };
    }

    /**
     * Awaits a two-party {@link CyclicBarrier} with a bounded timeout, converting any interruption,
     * breakage, or timeout into an unchecked failure so a stuck racer surfaces as a test failure
     * rather than a hang.
     *
     * @param barrier the barrier to await
     */
    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("concurrent race barrier failed", ex);
        }
    }

    /**
     * Asserts that a live monetary column equals the decoded-oracle value both by numeric value and
     * by exact scale&nbsp;2 (the {@code NUMERIC(12,2)} storage contract, AAP&nbsp;&sect;0.6.2).
     *
     * @param accountId the row's id (for assertion messages)
     * @param column    the monetary column name
     * @param actual    the live row map
     * @param expected  the decoded-oracle row map
     */
    private static void assertScale2Equal(String accountId, String column,
                                          Map<String, Object> actual, Map<String, Object> expected) {
        BigDecimal actualValue = (BigDecimal) actual.get(column);
        BigDecimal expectedValue = (BigDecimal) expected.get(column);
        assertThat(actualValue).as("row %s %s value", accountId, column)
                .isEqualByComparingTo(expectedValue);
        assertThat(actualValue.scale()).as("row %s %s scale", accountId, column).isEqualTo(2);
    }

    /**
     * Decodes the legacy fixed-length flat file {@code app/data/ASCII/acctdata.txt} into a list of
     * per-record field maps &mdash; the independent source-of-truth oracle for the migration
     * seed-parity check ({@code 4.7}). Each 300-byte record is sliced at the copybook offsets from
     * {@code CVACT01Y.cpy}; the three {@code S9(10)V99} monetary fields are zoned-decimal with a
     * trailing overpunched sign byte (USAGE DISPLAY, not COMP-3), and the two 10-char text fields are
     * right-trimmed so a blank {@code group_id} normalizes to {@code ''}, matching the {@code V2} seed.
     *
     * @return the 50 decoded records, in file order
     * @throws Exception if the source file cannot be located or read
     */
    private List<Map<String, Object>> decodeSourceOracle() throws Exception {
        Path sourceFile = locateSourceSeedFile();
        List<String> lines = Files.readAllLines(sourceFile, StandardCharsets.US_ASCII);
        List<Map<String, Object>> records = new ArrayList<>();
        for (String raw : lines) {
            if (raw.isEmpty()) {
                continue;
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("account_id", raw.substring(0, 11));
            record.put("active_status", raw.substring(11, 12));
            record.put("current_balance", decodeOverpunchedMoney(raw.substring(12, 24)));
            record.put("credit_limit", decodeOverpunchedMoney(raw.substring(24, 36)));
            record.put("cash_credit_limit", decodeOverpunchedMoney(raw.substring(36, 48)));
            record.put("open_date", LocalDate.parse(raw.substring(48, 58)));
            record.put("expiration_date", LocalDate.parse(raw.substring(58, 68)));
            record.put("reissue_date", LocalDate.parse(raw.substring(68, 78)));
            record.put("current_cycle_credit", decodeOverpunchedMoney(raw.substring(78, 90)));
            record.put("current_cycle_debit", decodeOverpunchedMoney(raw.substring(90, 102)));
            record.put("address_zip", raw.substring(102, 112).stripTrailing());
            record.put("group_id", raw.substring(112, 122).stripTrailing());
            records.add(record);
        }
        return records;
    }

    /**
     * Decodes a 12-character zoned-decimal {@code S9(10)V99} field with a trailing overpunched sign
     * byte into an exact scale-2 {@link BigDecimal}. The first 11 characters are digits; the final
     * byte encodes both the low-order digit and the sign: {@code '{'}&nbsp;=&nbsp;+0,
     * {@code 'A'..'I'}&nbsp;=&nbsp;+1..9, {@code '}'}&nbsp;=&nbsp;-0, {@code 'J'..'R'}&nbsp;=&nbsp;-1..9.
     * The resulting 12 digits are scaled by 1/100 (the implied {@code V99}).
     *
     * @param field the raw 12-character field
     * @return the decoded exact-precision amount, scale 2
     */
    private static BigDecimal decodeOverpunchedMoney(String field) {
        String leadingDigits = field.substring(0, field.length() - 1);
        char signByte = field.charAt(field.length() - 1);

        int lowDigit;
        boolean negative;
        if (signByte == '{') {
            lowDigit = 0;
            negative = false;
        } else if (signByte == '}') {
            lowDigit = 0;
            negative = true;
        } else if (signByte >= 'A' && signByte <= 'I') {
            lowDigit = signByte - 'A' + 1;
            negative = false;
        } else if (signByte >= 'J' && signByte <= 'R') {
            lowDigit = signByte - 'J' + 1;
            negative = true;
        } else {
            // Defensive: a plain trailing digit (unsigned) — treat as positive.
            lowDigit = Character.digit(signByte, 10);
            negative = false;
            if (lowDigit < 0) {
                throw new IllegalArgumentException("unrecognized overpunch sign byte in monetary field");
            }
        }

        BigDecimal amount = new BigDecimal(leadingDigits + lowDigit).movePointLeft(2);
        if (negative) {
            amount = amount.negate();
        }
        return amount.setScale(2);
    }

    /**
     * Locates the legacy seed file {@code app/data/ASCII/acctdata.txt} by walking up from the working
     * directory (the module runs with its own directory as the CWD, so the repository root &mdash;
     * and therefore the legacy {@code app/} tree &mdash; is an ancestor). Walking up rather than
     * hard-coding a relative depth keeps the oracle robust to where the test is launched from.
     *
     * @return the resolved path to the source seed file
     * @throws IllegalStateException if the file cannot be found within a bounded number of ancestors
     */
    private static Path locateSourceSeedFile() {
        Path directory = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && directory != null; depth++) {
            Path candidate = directory.resolve("app/data/ASCII/acctdata.txt");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "Could not locate app/data/ASCII/acctdata.txt from " + Paths.get("").toAbsolutePath());
    }
}
