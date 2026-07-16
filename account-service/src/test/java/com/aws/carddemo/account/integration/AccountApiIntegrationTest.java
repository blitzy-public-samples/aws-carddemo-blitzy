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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aws.carddemo.account.dto.AccountUpdateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
 * optimistic-locking flush behavior. The datasource is the {@code jdbc:tc:} Testcontainers URL
 * declared in {@code src/test/resources/application-test.yml}
 * ({@code jdbc:tc:postgresql:17-alpine:///accountsdb}), so the {@code postgres:17-alpine}
 * container starts and is torn down automatically &mdash; no {@code @Testcontainers},
 * {@code @Container}, or {@code @DynamicPropertySource} wiring is required here. A Docker daemon
 * is therefore a runtime prerequisite for this test.</p>
 *
 * <h2>Bootstrap</h2>
 * <ul>
 *   <li>{@link SpringBootTest} boots the full context (default {@code webEnvironment = MOCK}),
 *       auto-discovering {@code com.aws.carddemo.account.AccountServiceApplication}.</li>
 *   <li>{@link AutoConfigureMockMvc} supplies a {@link MockMvc} that exercises the real
 *       controller, the {@code @Validated} path checks, the {@code @Valid @RequestBody} binding,
 *       and the central {@code GlobalExceptionHandler} advice.</li>
 *   <li>{@link ActiveProfiles}{@code ("test")} activates {@code application-test.yml}.</li>
 * </ul>
 *
 * <h2>Test isolation (deliberately NOT {@code @Transactional})</h2>
 * <p>The class is intentionally not annotated {@code @Transactional}: a rolled-back transaction
 * would mask the real commit/flush and optimistic-lock behavior this test exists to prove.
 * Order-independence is instead achieved by giving each mutating scenario its own distinct seed
 * account, so no test depends on another's state:</p>
 * <ul>
 *   <li>Read-only assertions use account {@code 00000000001} (and {@code 00000000050}).</li>
 *   <li>The happy update mutates account {@code 00000000010}.</li>
 *   <li>The conflict scenario mutates account {@code 00000000020}.</li>
 *   <li>The bad-input {@code PUT}s target account {@code 00000000001} but never persist, because
 *       the service validates <em>before</em> loading/saving; the row is left untouched.</li>
 *   <li>The {@code CHECK}-constraint negative {@code INSERT} is auto-rolled-back under JDBC
 *       autocommit and never changes the row count.</li>
 * </ul>
 * <p>Consequently the seed row count stays exactly 50 regardless of method execution order.</p>
 *
 * <h2>Coverage &mdash; AAP &sect;0.6.5 traceability</h2>
 * <p>The seven scenarios exercise: happy read with seed-parity oracle, not-found (404), invalid
 * input (400) via both business-rule and Bean-Validation paths, concurrent conflict (409), happy
 * update with version increment and re-read, database {@code CHECK}-constraint enforcement, and
 * migration seed parity (count + id range). Only HTTP&nbsp;200/400/404/409 are ever expected.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Account REST API — full-stack integration (real PostgreSQL via Testcontainers)")
class AccountApiIntegrationTest {

    /** Shared base path for the account resource; the 11-digit id is appended per request. */
    private static final String BASE = "/api/v1/accounts/";

    /**
     * Exact HTTP&nbsp;404 body message produced for a missing account.
     *
     * <p><strong>Contract note (intentional deviation from the file brief's example literal).</strong>
     * The brief illustrated the not-found message as {@code "Account: {id} not found in Acct Master
     * file."}, but the authoritative, committed production contract deliberately omits the account id:
     * {@code AccountNotFoundException} carries the fixed, id-free constant
     * {@code "Account not found in Acct Master file."}, and {@code GlobalExceptionHandler} passes it
     * through verbatim while masking the id out of the {@code path}. That id-free wording is the
     * security-hardened behavior mandated by AAP&nbsp;&sect;0.6.6 ("never log/return full account
     * numbers"). This test asserts the <em>actual</em> API behavior, which is the higher authority.</p>
     */
    private static final String NOT_FOUND_MESSAGE = "Account not found in Acct Master file.";

    /**
     * Exact HTTP&nbsp;409 body message, owned by {@code GlobalExceptionHandler} and mandated by
     * AAP&nbsp;&sect;0.6.4 / &sect;4.2.3.2 (the modernized phrasing, not the raw COBOL literal).
     */
    private static final String CONFLICT_MESSAGE = "Record updated by another user - please retry";

    /** Drives the HTTP endpoints against the full, running application context. */
    @Autowired
    private MockMvc mockMvc;

    /**
     * The Spring-configured mapper (JavaTimeModule + {@code write-bigdecimal-as-plain}), used to
     * serialize request bodies and to parse response JSON when building round-trip update requests.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /** Direct JDBC access for database-level assertions (seed count/range and the CHECK negative test). */
    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM accounts WHERE account_id = '00000000001'", Long.class);
        assertThat(version).isEqualTo(0L);
    }

    // ------------------------------------------------------------------
    // 4.4 Concurrent conflict -> 409 with exact message (account 00000000020)
    // ------------------------------------------------------------------

    /**
     * Two sequential {@code PUT}s carrying the same (initially fresh, then stale) version reproduce
     * the legacy {@code COACTUPC} {@code 9700-CHECK-CHANGE-IN-REC} before-image conflict: the first
     * write succeeds and advances the persisted {@code @Version} to 1; replaying the now-stale
     * version&nbsp;0 body is rejected by the service's explicit up-front version comparison, which
     * raises {@code ObjectOptimisticLockingFailureException} and maps to HTTP&nbsp;409 with the exact
     * modernized message.
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
    // 4.7 Seed parity -> exactly 50 rows and the expected id range
    // ------------------------------------------------------------------

    /**
     * Confirms the {@code V2} migration seeded exactly the 50 source records with the zero-padded id
     * range {@code 00000000001}&nbsp;..&nbsp;{@code 00000000050}, and spot-checks the last row
     * ({@code 00000000050}) through the API to prove the highest-key record migrated with correct
     * scale-2 money and ISO dates. The COUNT/MIN/MAX are read directly from PostgreSQL; the spot
     * check is a read-only {@code GET}.
     */
    @Test
    @DisplayName("4.7 Seed parity: COUNT = 50, id range 00000000001..00000000050, row-50 spot check")
    void seedParity_countIdRangeAndRow50() throws Exception {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class))
                .isEqualTo(50);
        assertThat(jdbcTemplate.queryForObject("SELECT MIN(account_id) FROM accounts", String.class))
                .isEqualTo("00000000001");
        assertThat(jdbcTemplate.queryForObject("SELECT MAX(account_id) FROM accounts", String.class))
                .isEqualTo("00000000050");

        // Row-50 spot check via the API (read-only; does not affect the count or other tests).
        String body = mockMvc.perform(get(BASE + "00000000050").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("00000000050"))
                .andExpect(jsonPath("$.openDate").value("2011-04-22"))
                .andExpect(jsonPath("$.expirationDate").value("2023-03-09"))
                .andExpect(jsonPath("$.reissueDate").value("2023-03-09"))
                .andExpect(jsonPath("$.addressZip").value("A000000000"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains(
                "\"currentBalance\":492.00",
                "\"creditLimit\":6169.00",
                "\"cashCreditLimit\":4587.00");
    }

    // ------------------------------------------------------------------
    // Private helpers (single-class scope; no separate helper files per the brief)
    // ------------------------------------------------------------------

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
}
