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

import com.aws.carddemo.account.domain.Account;
import com.aws.carddemo.account.dto.AccountResponse;
import com.aws.carddemo.account.dto.AccountUpdateRequest;
import com.aws.carddemo.account.repository.AccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end integration tests for the account REST API, exercised over real HTTP against a genuine
 * PostgreSQL 17 database provisioned by Testcontainers (via the {@code tc:} JDBC URL in
 * {@code application-test.yml}). Flyway applies the true migrated schema (V1 DDL + V2 50-row seed) at
 * context startup, so these tests prove the migration against real {@code NUMERIC(12,2)}, {@code DATE},
 * {@code CHAR(1)} semantics, the {@code account_id} CHECK constraint, and &mdash; crucially &mdash; the
 * real Hibernate {@code @Version} optimistic-locking behavior that a mocked unit test cannot observe.
 *
 * <h2>Version-semantics coverage (AAP &sect;0.6.5; review findings C1 / M10 / M3)</h2>
 * <ul>
 *   <li><strong>C1</strong> &mdash; a CHANGED {@code PUT} returns the committed, incremented version
 *       (not a stale pre-flush value), and replaying the previously returned token then fails with 409
 *       while the freshly returned token succeeds.</li>
 *   <li><strong>M10</strong> &mdash; an accepted NO-OP {@code PUT} (identical representation) still
 *       advances the token by exactly one, matching the legacy unconditional {@code REWRITE}.</li>
 *   <li><strong>Concurrency</strong> &mdash; two simultaneous {@code PUT}s at the same version yield
 *       exactly one 200 and one 409 (load-to-flush race translation).</li>
 * </ul>
 *
 * <p>Each mutating scenario uses a distinct seed account so the tests are order-independent and do not
 * interfere with one another. Malformed-JSON and strict-token (fractional version / string money) cases
 * are also exercised here against the full application context, proving the auto-discovered strict Jackson
 * coercion ({@code JacksonConfig}) and the framework error handlers in {@code GlobalExceptionHandler} are
 * wired in the real runtime (review findings M1 / M8) &mdash; not only in {@code @WebMvcTest} slices.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AccountApiIntegrationTest {

    /** Exact HTTP 409 body wording owned by {@code GlobalExceptionHandler} (AAP &sect;0.6.4 / &sect;4.2.3.2). */
    private static final String CONFLICT_MESSAGE = "Record updated by another user - please retry";

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AccountRepository repository;

    // ------------------------------------------------------------------
    // GET /api/v1/accounts/{id}
    // ------------------------------------------------------------------

    /** Happy read: seed record #1 is returned with scale-2 money, ISO dates, and version 0. */
    @Test
    void getAccount_existing_returns200WithMappedShape() {
        ResponseEntity<AccountResponse> res =
                rest.getForEntity(url("/api/v1/accounts/00000000001"), AccountResponse.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        AccountResponse body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getAccountId()).isEqualTo("00000000001");
        assertThat(body.getActiveStatus()).isEqualTo("Y");
        assertThat(body.getCurrentBalance()).isEqualByComparingTo("194.00");
        assertThat(body.getCurrentBalance().scale()).isEqualTo(2);
        assertThat(body.getOpenDate().toString()).isEqualTo("2014-11-20");
        assertThat(body.getVersion()).isEqualTo(0L);
    }

    /** Unknown id reproduces the legacy NOTFND branch -> 404 with the sanitized message. */
    @Test
    void getAccount_missing_returns404() {
        ResponseEntity<String> res =
                rest.getForEntity(url("/api/v1/accounts/00000000099"), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode body = readJson(res.getBody());
        assertThat(body.path("status").asInt()).isEqualTo(404);
        assertThat(body.path("error").asText()).isEqualTo("Not Found");
        assertThat(body.path("message").asText()).contains("not found");
    }

    /** A malformed (non 11-digit) path id fails the method-level pattern -> 400. */
    @Test
    void getAccount_malformedId_returns400() {
        ResponseEntity<String> res =
                rest.getForEntity(url("/api/v1/accounts/123"), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------
    // PUT /api/v1/accounts/{id}
    // ------------------------------------------------------------------

    /** Invalid field (bad active status) is rejected by the business validator -> 400, no mutation. */
    @Test
    void updateAccount_invalidField_returns400AndDoesNotMutate() {
        final String id = "00000000010";
        AccountResponse before = getAccount(id);
        AccountUpdateRequest req = toRequest(before);
        req.setActiveStatus("X"); // not Y/N

        ResponseEntity<String> res = put(id, req);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Version unchanged in the database.
        assertThat(repository.findById(id).orElseThrow().getVersion()).isEqualTo(before.getVersion());
    }

    /**
     * C1: a CHANGED PUT returns the committed, incremented version. The response token must equal what
     * PostgreSQL committed; replaying the OLD token then yields 409, and using the NEW token succeeds.
     */
    @Test
    void updateAccount_changed_returnsAndCommitsIncrementedVersion() {
        final String id = "00000000020";
        AccountResponse before = getAccount(id);
        assertThat(before.getVersion()).isEqualTo(0L);

        AccountUpdateRequest req = toRequest(before);
        req.setCurrentBalance(before.getCurrentBalance().add(new BigDecimal("10.00"))); // real change

        ResponseEntity<AccountResponse> res = putForEntity(id, req);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        // Returned token is the COMMITTED token, not the pre-flush 0.
        assertThat(res.getBody().getVersion()).isEqualTo(1L);
        // Database agrees, and the field change persisted.
        Account persisted = repository.findById(id).orElseThrow();
        assertThat(persisted.getVersion()).isEqualTo(1L);
        assertThat(persisted.getCurrentBalance()).isEqualByComparingTo(
                before.getCurrentBalance().add(new BigDecimal("10.00")));

        // Replaying the STALE token (0) is now rejected...
        AccountUpdateRequest stale = toRequest(before);
        stale.setVersion(0L);
        assertThat(put(id, stale).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // ...while the FRESH token (1) succeeds.
        AccountUpdateRequest fresh = toRequest(res.getBody());
        assertThat(putForEntity(id, fresh).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * M10: an accepted NO-OP PUT (identical representation) still advances the token by exactly one,
     * both in the response and in the database, matching the legacy unconditional REWRITE.
     */
    @Test
    void updateAccount_noOp_stillAdvancesVersionByOne() {
        final String id = "00000000021";
        AccountResponse before = getAccount(id);
        assertThat(before.getVersion()).isEqualTo(0L);

        // Send back exactly what was read (no field changes at all).
        AccountUpdateRequest req = toRequest(before);

        ResponseEntity<AccountResponse> res = putForEntity(id, req);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getVersion()).isEqualTo(1L);
        assertThat(repository.findById(id).orElseThrow().getVersion()).isEqualTo(1L);
    }

    /** A stale-version PUT is rejected with 409 and the exact conflict body. */
    @Test
    void updateAccount_staleVersion_returns409WithExactMessage() {
        final String id = "00000000022";
        AccountResponse before = getAccount(id);
        // Advance the row once so the client's version 0 becomes stale.
        putForEntity(id, toRequest(before));

        AccountUpdateRequest stale = toRequest(before);
        stale.setVersion(0L);
        ResponseEntity<String> res = put(id, stale);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = readJson(res.getBody());
        assertThat(body.path("status").asInt()).isEqualTo(409);
        assertThat(body.path("error").asText()).isEqualTo("Conflict");
        assertThat(body.path("message").asText()).isEqualTo(CONFLICT_MESSAGE);
    }

    /**
     * Concurrency: two simultaneous PUTs at the same version produce exactly one 200 and one 409,
     * proving load-to-flush race translation on the real database.
     */
    @Test
    void updateAccount_concurrentSameVersion_yieldsOneSuccessOneConflict() throws Exception {
        final String id = "00000000023";
        AccountResponse before = getAccount(id);
        assertThat(before.getVersion()).isEqualTo(0L);

        final AccountUpdateRequest reqA = toRequest(before);
        reqA.setCurrentBalance(before.getCurrentBalance().add(new BigDecimal("1.00")));
        final AccountUpdateRequest reqB = toRequest(before);
        reqB.setCurrentBalance(before.getCurrentBalance().add(new BigDecimal("2.00")));

        final CyclicBarrier barrier = new CyclicBarrier(2);
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> callA = () -> { barrier.await(); return put(id, reqA).getStatusCode().value(); };
            Callable<Integer> callB = () -> { barrier.await(); return put(id, reqB).getStatusCode().value(); };
            Future<Integer> fA = pool.submit(callA);
            Future<Integer> fB = pool.submit(callB);
            List<Integer> statuses = List.of(fA.get(), fB.get());

            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        // Exactly one write landed: the version advanced by exactly one.
        assertThat(repository.findById(id).orElseThrow().getVersion()).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // Malformed body & strict token coercion (M1 / M8, full-context proof)
    // ------------------------------------------------------------------

    /**
     * M1: a syntactically malformed JSON body is rejected by the framework
     * {@code HttpMessageNotReadableException} handler with a sanitized 400 ApiError -- the route
     * template (never the raw id) as {@code path}, a fixed generic message, and no persistence.
     * Proven in the FULL application context (not a slice), so the advice wiring is real.
     */
    @Test
    void updateAccount_malformedJsonBody_returns400Sanitized() {
        final String id = "00000000030";
        long versionBefore = repository.findById(id).orElseThrow().getVersion();

        ResponseEntity<String> res = putRaw(id, "{ \"activeStatus\": \"Y\", "); // truncated JSON

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = readJson(res.getBody());
        assertThat(body.path("status").asInt()).isEqualTo(400);
        assertThat(body.path("error").asText()).isEqualTo("Bad Request");
        assertThat(body.path("path").asText()).isEqualTo("/api/v1/accounts/{accountId}");
        // Sanitized: the raw id never appears anywhere in the serialized body.
        assertThat(res.getBody()).doesNotContain(id);
        // No mutation occurred.
        assertThat(repository.findById(id).orElseThrow().getVersion()).isEqualTo(versionBefore);
    }

    /**
     * M8: a fractional token for the integer {@code version} field is rejected by strict Jackson
     * coercion (LogicalType.Integer refuses a Float shape) -> 400, proving the auto-discovered
     * {@code JacksonConfig} is active in the real context (not just the {@code @Import}-ed slice).
     */
    @Test
    void updateAccount_fractionalVersionToken_returns400() {
        final String id = "00000000031";
        AccountResponse before = getAccount(id);
        long versionBefore = before.getVersion();

        String raw = rawJsonBody(before, "0.9", before.getCurrentBalance().toPlainString());
        ResponseEntity<String> res = putRaw(id, raw);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(readJson(res.getBody()).path("path").asText())
                .isEqualTo("/api/v1/accounts/{accountId}");
        assertThat(repository.findById(id).orElseThrow().getVersion()).isEqualTo(versionBefore);
    }

    /**
     * M8: a string token for a numeric money field is rejected by strict Jackson coercion
     * (LogicalType.Float refuses a String shape) -> 400, again against the real running app.
     */
    @Test
    void updateAccount_stringMoneyToken_returns400() {
        final String id = "00000000032";
        AccountResponse before = getAccount(id);
        long versionBefore = before.getVersion();

        String raw = rawJsonBody(before, Long.toString(before.getVersion()), "\"not-a-number\"");
        ResponseEntity<String> res = putRaw(id, raw);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.findById(id).orElseThrow().getVersion()).isEqualTo(versionBefore);
    }

    // ------------------------------------------------------------------
    // Seed parity
    // ------------------------------------------------------------------

    /** The V2 migration seeds exactly the 50 source records. */
    @Test
    void seedData_hasExactlyFiftyAccounts() {
        assertThat(repository.count()).isEqualTo(50L);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String url(String path) {
        return path;
    }

    private AccountResponse getAccount(String id) {
        ResponseEntity<AccountResponse> res =
                rest.getForEntity(url("/api/v1/accounts/" + id), AccountResponse.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return res.getBody();
    }

    private ResponseEntity<String> put(String id, AccountUpdateRequest body) {
        return rest.exchange(url("/api/v1/accounts/" + id), HttpMethod.PUT,
                new HttpEntity<>(body, jsonHeaders()), String.class);
    }

    private ResponseEntity<AccountResponse> putForEntity(String id, AccountUpdateRequest body) {
        return rest.exchange(url("/api/v1/accounts/" + id), HttpMethod.PUT,
                new HttpEntity<>(body, jsonHeaders()), AccountResponse.class);
    }

    /** PUT a RAW JSON string body (used for malformed / deliberately mis-typed payloads). */
    private ResponseEntity<String> putRaw(String id, String rawJson) {
        return rest.exchange(url("/api/v1/accounts/" + id), HttpMethod.PUT,
                new HttpEntity<>(rawJson, jsonHeaders()), String.class);
    }

    /**
     * Emits a complete JSON update body echoing {@code r}, but injects {@code versionToken} and
     * {@code currentBalanceToken} as RAW tokens so a test can supply a deliberately wrong JSON shape
     * (e.g. a fractional version {@code 0.9} or a quoted money string) that only strict Jackson
     * coercion would reject. Every other field is well-formed, so the request is rejected for exactly
     * the injected reason.
     */
    private String rawJsonBody(AccountResponse r, String versionToken, String currentBalanceToken) {
        return "{"
                + "\"activeStatus\":\"" + r.getActiveStatus() + "\","
                + "\"currentBalance\":" + currentBalanceToken + ","
                + "\"creditLimit\":" + r.getCreditLimit().toPlainString() + ","
                + "\"cashCreditLimit\":" + r.getCashCreditLimit().toPlainString() + ","
                + "\"openDate\":\"" + r.getOpenDate().format(ISO_DATE) + "\","
                + "\"expirationDate\":\"" + r.getExpirationDate().format(ISO_DATE) + "\","
                + "\"reissueDate\":\"" + r.getReissueDate().format(ISO_DATE) + "\","
                + "\"currentCycleCredit\":" + r.getCurrentCycleCredit().toPlainString() + ","
                + "\"currentCycleDebit\":" + r.getCurrentCycleDebit().toPlainString() + ","
                + "\"addressZip\":\"" + r.getAddressZip() + "\","
                + "\"version\":" + versionToken
                + "}";
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Builds an editable-fields request that faithfully echoes a read projection (for no-op/round-trip). */
    private AccountUpdateRequest toRequest(AccountResponse r) {
        AccountUpdateRequest req = new AccountUpdateRequest();
        req.setActiveStatus(r.getActiveStatus());
        req.setCurrentBalance(r.getCurrentBalance());
        req.setCreditLimit(r.getCreditLimit());
        req.setCashCreditLimit(r.getCashCreditLimit());
        req.setOpenDate(r.getOpenDate().format(ISO_DATE));
        req.setExpirationDate(r.getExpirationDate().format(ISO_DATE));
        req.setReissueDate(r.getReissueDate().format(ISO_DATE));
        req.setCurrentCycleCredit(r.getCurrentCycleCredit());
        req.setCurrentCycleDebit(r.getCurrentCycleDebit());
        req.setAddressZip(r.getAddressZip());
        req.setVersion(r.getVersion());
        return req;
    }

    private JsonNode readJson(String body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Response body was not valid JSON: " + body, e);
        }
    }
}
