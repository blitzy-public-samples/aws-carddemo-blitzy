package com.carddemo.controller;

import com.carddemo.dto.AccountResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc REST-contract integration test for {@link com.carddemo.controller.AccountController}.
 *
 * <p>This is the mandated {@code AccountControllerTest} of <strong>AAP &sect;0.2.1.3</strong> (the
 * Validation Framework lists it among the per-program test classes) and <strong>&sect;0.4.1.4</strong>
 * ({@code AccountController &larr; COACTVWC, COACTUPC}). It proves that the two legacy CICS online
 * account programs are faithfully re-expressed as a stateless REST/JSON contract whose 3270
 * presentation tier has been retired, while preserving every cross-cutting business rule the
 * migration must keep intact:</p>
 * <ul>
 *   <li>{@code app/cbl/COACTVWC.cbl} (transaction {@code CAVW}, <em>Account View</em>) &rarr;
 *       {@code GET /accounts/{accountId}}. The legacy program reads the {@code ACCTDAT} record,
 *       resolves the owning customer through the card cross-reference ({@code CARDXREF} alternate
 *       index by account), reads {@code CUSTDAT}, and renders the combined account&nbsp;+&nbsp;customer
 *       detail on a single panel. A missing account trips the
 *       <em>"Did not find this account in account master file"</em> branch (COACTVWC&nbsp;L132), which
 *       the migration surfaces as <strong>HTTP&nbsp;404</strong>.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl} (transaction {@code CAUP}, <em>Account Update</em>) &rarr;
 *       {@code PUT /accounts/{accountId}}. The legacy program edits account/customer fields, then
 *       {@code 9700-CHECK-CHANGE-IN-REC} re-reads and compares against the {@code ACUP-OLD-*} snapshot;
 *       if the record changed underneath the editor it sets the
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} 88-level (literal
 *       <em>"Record changed by some one else. Please review"</em>, COACTUPC&nbsp;L521&ndash;L522) and
 *       refuses the {@code REWRITE}. That lost-update guard becomes JPA {@code @Version} optimistic
 *       locking &rarr; <strong>HTTP&nbsp;409</strong>; field-edit failures &rarr;
 *       <strong>HTTP&nbsp;400</strong>.</li>
 * </ul>
 *
 * <h2>Preservation-critical assertions (AAP &sect;0.6.6 / &sect;0.6.8 / &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>SSN last-4 suppression.</strong> {@link AccountResponse} exposes the Social Security
 *       Number only as {@code ssnLastFour} (at most the trailing four digits) and declares <em>no</em>
 *       component capable of holding a full nine-digit SSN. Every account view is asserted to carry a
 *       four-character {@code ssnLastFour}, to omit any full-SSN field, and to never echo the complete
 *       SSN anywhere in the serialized body.</li>
 *   <li><strong>Optimistic-lock &rarr; HTTP&nbsp;409.</strong> A {@code PUT} carrying a stale
 *       {@code version} must be rejected with HTTP&nbsp;409. This is THE end-to-end controller-level
 *       proof of the {@code @Version} conflict path that the sibling root-level
 *       {@code AccountConcurrencyTest} deliberately delegates here (it proves the exceptions are raised
 *       at their source; this class proves the HTTP-status mapping).</li>
 * </ul>
 *
 * <h2>House style &amp; harness</h2>
 * <p>Full {@code @SpringBootTest} integration against the seeded H2 {@code test} profile (Flyway applies
 * {@code V1..V4} on context startup, seeding 50 accounts with {@code version = 0}), mirroring the
 * sibling {@code CardControllerTest} and {@code AccountConcurrencyTest}. The class is
 * {@code @Transactional} so every test &mdash; including the mutating {@code PUT}s &mdash; rolls back,
 * leaving the seed pristine for the next test (no manual cleanup). The class is annotated
 * {@code @WithMockUser} with a plain {@code USER} role because the account endpoints require only
 * authentication ({@code SecurityConfig} restricts {@code /users/**} to {@code ADMIN} but leaves
 * everything else at {@code authenticated()}, and {@code AccountController} declares no
 * {@code @PreAuthorize}); the {@code JwtAuthenticationFilter} skips when the {@code SecurityContext} is
 * already populated, so {@code @WithMockUser} authenticates without a real JWT. CSRF is disabled
 * globally, so no {@code .with(csrf())} is needed on the {@code PUT}s.</p>
 *
 * <h2>Build-the-body strategy &mdash; GET-then-modify</h2>
 * <p>The {@code PUT} body is constructed by first performing the {@code GET}, parsing the returned
 * {@link AccountResponse} JSON into a mutable {@link ObjectNode}, changing only the field(s) under test
 * and the optimistic-lock {@code version}, and re-serializing. This keeps the tests robust to the exact
 * shape of {@link com.carddemo.dto.AccountUpdateRequest}: response-only properties ({@code accountId},
 * {@code customerId}, {@code ssnLastFour}) are simply ignored on bind (Spring Boot's default
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false}), and the input-only {@code ssn} is absent from the response
 * and therefore left {@code null} (the edit fields are null-tolerant; only {@code version} is
 * {@code @NotNull}).</p>
 *
 * <h2>Seed data (Flyway {@code V3__seed_master.sql})</h2>
 * <p>Accounts are seeded with ids {@code 1..50}, all at {@code version = 0}. Account&nbsp;{@code 1} has a
 * {@code card_xref} row ({@code '9680294154603697', 1, 1}) resolving to customer&nbsp;{@code 1}, so the
 * view read-chain completes and the update reaches its version guard. Account&nbsp;{@code 1}'s seeded
 * money values are {@code curr_bal = 194.00}, {@code credit_limit = 2020.00},
 * {@code cash_credit_limit = 1020.00}, and {@code curr_cyc_credit = curr_cyc_debit = 0.00}; its owning
 * customer's SSN is {@code 020973888} (last four {@code 3888}).</p>
 *
 * @see com.carddemo.controller.AccountController
 * @see com.carddemo.service.AccountService
 * @see com.carddemo.dto.AccountResponse
 * @see com.carddemo.dto.AccountUpdateRequest
 * @see com.carddemo.AccountConcurrencyTest
 * @see <a href="file:app/cbl/COACTVWC.cbl">app/cbl/COACTVWC.cbl (Account View, tran CAVW)</a>
 * @see <a href="file:app/cbl/COACTUPC.cbl">app/cbl/COACTUPC.cbl (Account Update, tran CAUP)</a>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "USER0001", roles = {"USER"})
@DisplayName("AccountController REST contract \u2014 COACTVWC view (200/404) / COACTUPC update (200/409/400/404) parity")
class AccountControllerTest {

    /** Performs the HTTP requests against the fully wired application context (including security). */
    @Autowired
    private MockMvc mockMvc;

    /**
     * The application's configured {@link ObjectMapper} (JSR-310 enabled,
     * {@code write-dates-as-timestamps=false}, plain {@code BigDecimal}); used both to parse the
     * {@code GET} response into a mutable tree and to serialize the {@code PUT} body, so the wire form
     * matches exactly what the controller serializes and deserializes.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * A seeded account id. Flyway {@code V3__seed_master.sql} seeds account {@code 1} with a
     * {@code card_xref} row resolving to an existing customer, so {@code GET} returns the combined
     * detail and {@code PUT} reaches the optimistic-lock guard (an account without a cross-reference
     * would surface HTTP&nbsp;404 first).
     */
    private static final long EXISTING_ACCOUNT_ID = 1L;

    /** An 11-digit-range account id that is intentionally NOT present in the 1..50 seed. */
    private static final long MISSING_ACCOUNT_ID = 99999999L;

    /**
     * The full nine-digit SSN of account&nbsp;1's owning customer (customer&nbsp;1) in
     * {@code V3__seed_master.sql}. Used as a negative assertion: this value must NEVER appear in any
     * account-view response body (only its last four digits may be exposed).
     */
    private static final String EXISTING_ACCOUNT_FULL_SSN = "020973888";

    /** The last four digits of {@link #EXISTING_ACCOUNT_FULL_SSN} &mdash; the only SSN-derived value the API may expose. */
    private static final String EXISTING_ACCOUNT_SSN_LAST_FOUR = "3888";

    /**
     * Fetches an account via {@code GET /accounts/{id}} and returns its JSON body as a mutable
     * {@link ObjectNode}, the seed for the GET-then-modify {@code PUT} bodies.
     *
     * <p>The {@code GET} is asserted to return HTTP&nbsp;200 and a JSON object before the node is
     * handed back, so a misbehaving view endpoint fails fast and unambiguously rather than surfacing as
     * a confusing downstream {@code PUT} error.</p>
     *
     * @param accountId the account id to fetch
     * @return the response body parsed into a mutable {@link ObjectNode}
     * @throws Exception if the request or JSON parsing fails
     */
    private ObjectNode fetchAccountAsObjectNode(long accountId) throws Exception {
        String body = mockMvc.perform(get("/accounts/{accountId}", accountId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        assertThat(node.isObject())
                .as("GET /accounts/%d must return a JSON object", accountId)
                .isTrue();
        return (ObjectNode) node;
    }

    // =====================================================================================
    // Phase 2 — GET /accounts/{id} (view): 200 detail, SSN last-4 PII gate, 404, 401
    // =====================================================================================

    /**
     * Viewing an existing account returns HTTP&nbsp;200 with the flattened account&nbsp;+&nbsp;customer
     * detail (COACTVWC). The identifier, active status, the optimistic-lock {@code version}, and the
     * owning {@code customerId} are asserted at the JSON boundary; the monetary fields are asserted
     * scale-insensitively by deserializing the body into an {@link AccountResponse} and comparing
     * {@link BigDecimal} values with {@code isEqualByComparingTo} (so {@code 194.00} matches
     * {@code 194.0} regardless of the rendered trailing-zero scale).
     */
    @Test
    @DisplayName("GET /accounts/{id}: existing account -> 200 with flattened account + customer detail")
    void getAccount_existing_returns200WithAccountAndCustomerDetail() throws Exception {
        String body = mockMvc.perform(get("/accounts/{accountId}", EXISTING_ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value((int) EXISTING_ACCOUNT_ID))
                .andExpect(jsonPath("$.activeStatus").value("Y"))
                .andExpect(jsonPath("$.customerId").exists())
                .andExpect(jsonPath("$.version").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Robust, scale-insensitive money assertions via the typed DTO (avoids 194.00 vs 194.0 brittleness).
        AccountResponse response = objectMapper.readValue(body, AccountResponse.class);
        assertThat(response.accountId()).isEqualTo(EXISTING_ACCOUNT_ID);
        assertThat(response.currentBalance()).isEqualByComparingTo(new BigDecimal("194.00"));
        assertThat(response.creditLimit()).isEqualByComparingTo(new BigDecimal("2020.00"));
        assertThat(response.cashCreditLimit()).isEqualByComparingTo(new BigDecimal("1020.00"));
        assertThat(response.currentCycleCredit()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(response.currentCycleDebit()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(response.version()).as("optimistic-lock version must be echoed").isNotNull();
    }

    /**
     * The SSN PII gate (AAP &sect;0.6.8 / &sect;0.7.1): the account view exposes the SSN as its last four
     * digits ONLY and never leaks the full nine-digit value. The response must carry a four-digit
     * {@code ssnLastFour}, must omit every plausible full-SSN field name, and the complete SSN string
     * must be absent from the entire serialized body.
     */
    @Test
    @DisplayName("GET /accounts/{id}: SSN exposed as last-4 only; full SSN never present (AAP 0.6.8/0.7.1)")
    void getAccount_exposesSsnLastFourOnly_neverFullSsn() throws Exception {
        String body = mockMvc.perform(get("/accounts/{accountId}", EXISTING_ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ssnLastFour").exists())
                .andExpect(jsonPath("$.ssn").doesNotExist())
                .andExpect(jsonPath("$.socialSecurityNumber").doesNotExist())
                .andExpect(jsonPath("$.custSsn").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        AccountResponse response = objectMapper.readValue(body, AccountResponse.class);
        assertThat(response.ssnLastFour())
                .as("ssnLastFour must be present and exactly four digits")
                .isNotNull()
                .hasSize(4)
                .matches("\\d{4}")
                .isEqualTo(EXISTING_ACCOUNT_SSN_LAST_FOUR);

        // The full nine-digit SSN must NEVER appear anywhere in the serialized response body.
        assertThat(body)
                .as("the full 9-digit SSN must never be serialized")
                .doesNotContain(EXISTING_ACCOUNT_FULL_SSN);
    }

    /**
     * An unknown account id raises {@code ResourceNotFoundException} in the service, which
     * {@code GlobalExceptionHandler} maps to HTTP&nbsp;404 &mdash; the migration of COACTVWC's
     * "Did not find this account in account master file" branch.
     */
    @Test
    @DisplayName("GET /accounts/{id}: unknown account -> 404 Not Found")
    void getAccount_missing_returns404() throws Exception {
        mockMvc.perform(get("/accounts/{accountId}", MISSING_ACCOUNT_ID))
                .andExpect(status().isNotFound());
    }

    /**
     * An anonymous caller (overriding the class-level {@code @WithMockUser}) is rejected by the
     * stateless filter chain: {@code anyRequest().authenticated()} denies the anonymous principal and
     * the JWT entry point returns HTTP&nbsp;401 before the controller is reached.
     */
    @Test
    @WithAnonymousUser
    @DisplayName("GET /accounts/{id}: unauthenticated -> 401 Unauthorized")
    void getAccount_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/accounts/{accountId}", EXISTING_ACCOUNT_ID))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================================
    // Phase 3 — PUT /accounts/{id} (update): 200 with current version, 409 stale, 400 invalid, 404 missing
    // =====================================================================================

    /**
     * A valid update carrying the current optimistic-lock {@code version} (the seeded {@code 0}) applies
     * the edit and returns HTTP&nbsp;200 with the new state. The body is built GET-then-modify and a
     * single harmless field is flipped &mdash; {@code activeStatus} {@code 'Y'} &rarr; {@code 'N'} (both
     * permitted by {@code @Pattern("[YN]")}) &mdash; so the response visibly reflects a real change.
     * The optimistic-lock {@code version} is asserted present (its post-update numeric value is left
     * unasserted: within the single test transaction Hibernate need not have flushed the increment by
     * the time the response is built).
     */
    @Test
    @DisplayName("PUT /accounts/{id}: valid body with current version (0) -> 200; activeStatus change reflected")
    void updateAccount_validWithCurrentVersion_returns200() throws Exception {
        ObjectNode body = fetchAccountAsObjectNode(EXISTING_ACCOUNT_ID);
        body.put("activeStatus", "N");  // seed is 'Y'; flip to 'N' to prove the edit is applied
        body.put("version", 0);         // the current (correct) seeded version

        mockMvc.perform(put("/accounts/{accountId}", EXISTING_ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value((int) EXISTING_ACCOUNT_ID))
                .andExpect(jsonPath("$.activeStatus").value("N"))
                .andExpect(jsonPath("$.version").exists());
    }

    /**
     * The optimistic-lock parity (AAP &sect;0.6.6) &mdash; THE controller-level HTTP&nbsp;409 proof that
     * {@code AccountConcurrencyTest} delegates here. A well-formed update whose {@code version}
     * ({@code 999}) disagrees with the persisted account ({@code 0}) means the client edited a stale
     * snapshot; {@code AccountService} throws the CardDemo domain
     * {@code com.carddemo.exception.ConcurrentModificationException}, which {@code GlobalExceptionHandler}
     * maps to HTTP&nbsp;409 &mdash; reproducing {@code COACTUPC}'s
     * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} lost-update guard (COACTUPC&nbsp;L521&ndash;L522).
     *
     * <p>The HTTP status is asserted, not the exception <em>type</em>: the domain exception
     * deliberately shares its simple name with {@code java.util.ConcurrentModificationException}, so
     * this test neither imports nor references either type, eliminating any name-clash ambiguity.</p>
     */
    @Test
    @DisplayName("PUT /accounts/{id}: stale version (999) -> 409 Conflict (COACTUPC 9700 lost-update guard)")
    void updateAccount_staleVersion_returns409() throws Exception {
        ObjectNode body = fetchAccountAsObjectNode(EXISTING_ACCOUNT_ID);
        body.put("activeStatus", "N");  // a genuine edit that MUST be rejected because the version is stale
        body.put("version", 999);       // != seeded version 0 -> ConcurrentModificationException -> 409

        mockMvc.perform(put("/accounts/{accountId}", EXISTING_ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    /**
     * A field-edit failure maps to HTTP&nbsp;400. An {@code activeStatus} of {@code 'Z'} violates the
     * {@code @Pattern("[YN]")} constraint on {@link com.carddemo.dto.AccountUpdateRequest}, raising
     * {@code MethodArgumentNotValidException} &rarr; HTTP&nbsp;400 with the standardized
     * {@code ErrorResponse} ({@code $.status = 400} and per-field {@code $.fieldErrors}). The
     * {@code version} is left valid ({@code 0}) so the constraint violation is the sole cause of the
     * rejection.
     */
    @Test
    @DisplayName("PUT /accounts/{id}: invalid activeStatus 'Z' violates @Pattern -> 400 with fieldErrors")
    void updateAccount_invalidField_returns400() throws Exception {
        ObjectNode body = fetchAccountAsObjectNode(EXISTING_ACCOUNT_ID);
        body.put("activeStatus", "Z");  // violates @Pattern("[YN]")
        body.put("version", 0);         // valid version: the ONLY failure is the field constraint

        mockMvc.perform(put("/accounts/{accountId}", EXISTING_ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").exists());
    }

    /**
     * Validation precedes the service lookup: a structurally <em>valid</em> body (built from the
     * existing account, with a valid {@code version}) targeting an unknown account passes Bean
     * Validation, after which {@code AccountService} loads the account by primary key, misses, and
     * raises {@code ResourceNotFoundException} &rarr; HTTP&nbsp;404 (not 400 and not 409). This pins the
     * service's ordering: existence is checked before the optimistic-lock comparison.
     */
    @Test
    @DisplayName("PUT /accounts/{id}: valid body on unknown account -> 404 (existence checked before version)")
    void updateAccount_missingAccount_returns404() throws Exception {
        ObjectNode body = fetchAccountAsObjectNode(EXISTING_ACCOUNT_ID);
        body.put("version", 0);  // valid version; @Valid passes, then findById misses -> 404

        mockMvc.perform(put("/accounts/{accountId}", MISSING_ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }
}
