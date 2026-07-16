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
package com.aws.carddemo.account.controller;

import com.aws.carddemo.account.config.JacksonConfig;
import com.aws.carddemo.account.dto.AccountResponse;
import com.aws.carddemo.account.dto.AccountUpdateRequest;
import com.aws.carddemo.account.exception.AccountNotFoundException;
import com.aws.carddemo.account.exception.ValidationException;
import com.aws.carddemo.account.service.AccountService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC web-slice tests for {@link AccountController}, the REST boundary of the CardDemo
 * Account Management microservice (Feature&nbsp;F-003; migrates CICS transactions {@code CAVW}
 * / {@code CAUP}).
 *
 * <p>This is a pure {@link WebMvcTest @WebMvcTest} slice: it bootstraps ONLY the MVC
 * infrastructure for {@link AccountController} plus the auto-detected
 * {@code @RestControllerAdvice} ({@code GlobalExceptionHandler}), Jackson, and Bean Validation.
 * {@link JacksonConfig} is additionally {@link Import @Import}ed because a {@code @WebMvcTest}
 * slice does not component-scan arbitrary {@code @Configuration} classes; importing it activates
 * the strict numeric-coercion policy so the {@code version}/money token-typing cases below exercise
 * the same {@code ObjectMapper} the running application uses. The {@link AccountService} collaborator
 * is replaced by a Mockito mock via {@link MockitoBean @MockitoBean}, so <strong>no</strong> database,
 * Hibernate, Flyway, or Testcontainers is involved. The tests therefore verify the controller wiring
 * in isolation:</p>
 * <ul>
 *   <li>HTTP method/path routing for {@code GET}/{@code PUT} {@code /api/v1/accounts/{accountId}};</li>
 *   <li>request-body (de)serialization and JSON response shape/formatting (scale-2 money rendered in
 *       plain, non-scientific notation; ISO {@code yyyy-MM-dd} dates; 11-digit zero-padded id);</li>
 *   <li>strict numeric input typing &mdash; a fractional, string, or object {@code version} token and a
 *       string monetary token are rejected rather than coerced (config/JacksonConfig);</li>
 *   <li>structural path-variable validation ({@code @Pattern("\\d{11}")} on the {@code @Validated}
 *       controller);</li>
 *   <li>the framework error contract &mdash; malformed/empty body, unsupported method, unsupported
 *       media type, and an unknown route each return the uniform {@code ApiError};</li>
 *   <li>and the central mapping of domain/framework exceptions to HTTP
 *       <strong>200/400/404/405/409/415</strong> performed by {@code GlobalExceptionHandler}, whose
 *       {@code path} is the sanitized route template {@code /api/v1/accounts/{accountId}} (never the
 *       raw URI carrying the id).</li>
 * </ul>
 *
 * <p>Business logic (validation rules, persistence, optimistic-lock decision) is intentionally
 * <em>not</em> exercised here; it is covered by {@code AccountValidatorTest},
 * {@code AccountServiceTest}, {@code AccountMapperTest}, and the Testcontainers integration test.</p>
 *
 * <h2>Legacy-parity oracles (read-only; never modified)</h2>
 * <ul>
 *   <li>Not-found ({@code 404}) reproduces {@code COACTVWC} {@code 9300-GETACCTDATA-BYACCT}
 *       {@code DFHRESP(NOTFND)} (L786&ndash;L807). The legacy 3270 text
 *       {@code "Account:{id} not found in Acct Master file.Resp:{r} Reas:{r2}"} is deliberately
 *       normalized by the migrated {@code AccountNotFoundException} to the id-free
 *       {@code "Account not found in Acct Master file."} (Technical Specification &sect;0.6.6 &mdash;
 *       the full account number must never leak into logs or error payloads).</li>
 *   <li>Conflict ({@code 409}) reproduces {@code COACTUPC} {@code 9700-CHECK-CHANGE-IN-REC}
 *       (L4109&ndash;L4193). The legacy literal {@code "Record changed by some one else. Please review"}
 *       is deliberately modernized to {@code "Record updated by another user - please retry"}
 *       (&sect;0.6.4 / &sect;4.2.3.2).</li>
 * </ul>
 *
 * <h2>Security (&sect;0.6.6)</h2>
 * <p>These tests never print or log account numbers or monetary values; all evidence stays inside
 * MockMvc matchers. There is no {@code System.out} and no logger in this class.</p>
 */
@WebMvcTest(AccountController.class)
@Import(JacksonConfig.class)
class AccountControllerTest {

    /**
     * Canonical zero-padded 11-digit account key, mirroring seed record&nbsp;#1
     * ({@code 00000000001}) from {@code app/data/ASCII/acctdata.txt}. Leading zeros are
     * significant and are preserved as a {@link String} throughout.
     */
    private static final String ACCOUNT_ID = "00000000001";

    /**
     * A well-formed 11-digit key for which the (mocked) service reports no account, used to drive
     * the not-found ({@code 404}) scenarios.
     */
    private static final String MISSING_ID = "00000000002";

    /**
     * MVC test client wired by the {@code @WebMvcTest} slice; performs simulated HTTP requests
     * against {@link AccountController} without a running servlet container.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Mocked application service. Supplied into the slice context by {@link MockitoBean} so the
     * controller (whose sole constructor argument is an {@link AccountService}) is instantiable,
     * while keeping the test decoupled from persistence and business logic.
     */
    @MockitoBean
    private AccountService accountService;

    // ------------------------------------------------------------------
    // Helpers (private methods only; no separate classes per scope rules)
    // ------------------------------------------------------------------

    /**
     * Builds a fully populated {@link AccountResponse} for {@link #ACCOUNT_ID} using the verified
     * seed record&nbsp;#1 values and the supplied optimistic-lock {@code version}.
     *
     * <p>Every monetary field is constructed with the {@link BigDecimal#BigDecimal(String) String
     * constructor} so that scale&nbsp;2 is preserved exactly (for example {@code "194.00"} keeps its
     * two fraction digits). Using {@code new BigDecimal(194.00)} (the {@code double} constructor) or
     * {@code BigDecimal.valueOf(194.00)} would lose scale and defeat the plain scale-2 JSON assertion,
     * so they are deliberately avoided. Dates are set as {@link LocalDate}; the DTO's
     * {@code @JsonFormat} renders them as ISO {@code yyyy-MM-dd} strings.</p>
     *
     * @param version the optimistic-lock version the mocked service should echo back
     * @return a populated read projection for assertions
     */
    private AccountResponse sampleResponse(long version) {
        AccountResponse response = new AccountResponse();
        response.setAccountId(ACCOUNT_ID);
        response.setActiveStatus("Y");
        response.setCurrentBalance(new BigDecimal("194.00"));
        response.setCreditLimit(new BigDecimal("2020.00"));
        response.setCashCreditLimit(new BigDecimal("1020.00"));
        response.setOpenDate(LocalDate.parse("2014-11-20"));
        response.setExpirationDate(LocalDate.parse("2025-05-20"));
        response.setReissueDate(LocalDate.parse("2025-05-20"));
        response.setCurrentCycleCredit(new BigDecimal("0.00"));
        response.setCurrentCycleDebit(new BigDecimal("0.00"));
        response.setAddressZip("A000000000");
        response.setGroupId("");
        response.setVersion(version);
        return response;
    }

    /**
     * Returns a structurally valid {@code AccountUpdateRequest} JSON body as a raw text block.
     *
     * <p>The body includes every field the DTO requires ({@code @NotNull}) and deliberately omits
     * {@code accountId} and {@code groupId}, which the write model does not define (read-only
     * tightening, &sect;0.7.2). Building the body as raw JSON &mdash; rather than serializing a DTO
     * &mdash; keeps the test decoupled from the DTO setters and makes the "omit a field" and "add
     * extra fields" variants trivial to express.</p>
     *
     * @return a valid PUT request body
     */
    private String validRequestJson() {
        return """
            {
              "activeStatus": "Y",
              "currentBalance": 194.00,
              "creditLimit": 2020.00,
              "cashCreditLimit": 1020.00,
              "openDate": "2014-11-20",
              "expirationDate": "2025-05-20",
              "reissueDate": "2025-05-20",
              "currentCycleCredit": 0.00,
              "currentCycleDebit": 0.00,
              "addressZip": "A000000000",
              "version": 0
            }
            """;
    }

    // ------------------------------------------------------------------
    // GET /api/v1/accounts/{accountId} — migrates COACTVWC (CAVW)
    // ------------------------------------------------------------------

    /**
     * GET happy path: a found account is serialized to HTTP&nbsp;200 with the full JSON read model.
     *
     * <p>Asserts the 11-digit zero-padded id (leading zeros intact), the {@code Y} status, ISO dates,
     * the preserved {@code addressZip}, and the echoed {@code version}. Monetary fields are asserted
     * against the <em>raw</em> response body via {@code containsString}, proving plain scale-2
     * rendering (no scientific notation) &mdash; JsonPath reads numbers back as doubles and cannot
     * prove scale, so a substring check on the serialized text is used instead.</p>
     */
    @Test
    void getAccount_found_returns200AndJsonShape() throws Exception {
        when(accountService.getAccount(ACCOUNT_ID)).thenReturn(sampleResponse(0L));

        mockMvc.perform(get("/api/v1/accounts/{accountId}", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accountId").value("00000000001"))
                .andExpect(jsonPath("$.activeStatus").value("Y"))
                .andExpect(jsonPath("$.openDate").value("2014-11-20"))
                .andExpect(jsonPath("$.expirationDate").value("2025-05-20"))
                .andExpect(jsonPath("$.reissueDate").value("2025-05-20"))
                .andExpect(jsonPath("$.addressZip").value("A000000000"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(content().string(containsString("\"currentBalance\":194.00")))
                .andExpect(content().string(containsString("\"creditLimit\":2020.00")))
                .andExpect(content().string(containsString("\"cashCreditLimit\":1020.00")))
                .andExpect(content().string(containsString("\"currentCycleCredit\":0.00")))
                .andExpect(content().string(containsString("\"currentCycleDebit\":0.00")));
    }

    /**
     * GET not-found: the mocked service raises {@link AccountNotFoundException}, which
     * {@code GlobalExceptionHandler} maps to HTTP&nbsp;404 with the structured {@code ApiError} body.
     *
     * <p>The migrated exception carries a deliberately generic, id-free message
     * ({@code "Account not found in Acct Master file."}) per &sect;0.6.6 &mdash; it does NOT
     * interpolate the account id and carries no {@code Resp:}/{@code Reas:} CICS diagnostics from the
     * legacy 3270 text. The exception exposes only a no-argument constructor, so it is thrown as
     * {@code new AccountNotFoundException()}.</p>
     */
    @Test
    void getAccount_notFound_returns404WithExactMessage() throws Exception {
        when(accountService.getAccount(MISSING_ID)).thenThrow(new AccountNotFoundException());

        mockMvc.perform(get("/api/v1/accounts/{accountId}", MISSING_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Account not found in Acct Master file."))
                // Path is the SANITIZED route template, never the raw URI carrying the account id
                // (AAP 0.6.6 / CWE-209/532) — see GlobalExceptionHandler#resolvePath.
                .andExpect(jsonPath("$.path").value("/api/v1/accounts/{accountId}"));
    }

    /**
     * GET malformed id: a path variable that fails the {@code @Pattern("\\d{11}")} structural check
     * raises {@code jakarta.validation.ConstraintViolationException} (the {@code @Validated}
     * controller is proxied by the {@code MethodValidationPostProcessor} registered by
     * {@code ValidationAutoConfiguration} in the slice), which the handler maps to HTTP&nbsp;400 with
     * the generic {@code "Validation failed"} summary. The service must never be reached.
     */
    @Test
    void getAccount_malformedId_returns400ValidationFailed() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}", "123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"));

        verify(accountService, never()).getAccount(anyString());
    }

    // ------------------------------------------------------------------
    // PUT /api/v1/accounts/{accountId} — migrates COACTUPC (CAUP)
    // ------------------------------------------------------------------

    /**
     * PUT happy path: a structurally valid body is bound and delegated to the service, whose returned
     * projection (with the post-update incremented {@code version}) is serialized to HTTP&nbsp;200.
     * Asserts the path id echoes back, the version is {@code 1}, and a monetary field renders in plain
     * scale-2 form.
     */
    @Test
    void updateAccount_valid_returns200() throws Exception {
        when(accountService.updateAccount(eq(ACCOUNT_ID), any(AccountUpdateRequest.class)))
                .thenReturn(sampleResponse(1L));

        mockMvc.perform(put("/api/v1/accounts/{accountId}", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("00000000001"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.activeStatus").value("Y"))
                .andExpect(content().string(containsString("\"currentBalance\":194.00")));
    }

    /**
     * PUT structurally invalid body: omitting the {@code @NotNull} {@code version} field triggers
     * {@code MethodArgumentNotValidException} during {@code @Valid @RequestBody} resolution, which the
     * handler maps to HTTP&nbsp;400 with {@code "Validation failed"} and a {@code fieldErrors} map that
     * includes the offending {@code version} field. The service must never be reached.
     */
    @Test
    void updateAccount_structurallyInvalidBody_returns400ValidationFailed() throws Exception {
        String bodyMissingVersion = """
            {
              "activeStatus": "Y",
              "currentBalance": 194.00,
              "creditLimit": 2020.00,
              "cashCreditLimit": 1020.00,
              "openDate": "2014-11-20",
              "expirationDate": "2025-05-20",
              "reissueDate": "2025-05-20",
              "currentCycleCredit": 0.00,
              "currentCycleDebit": 0.00,
              "addressZip": "A000000000"
            }
            """;

        mockMvc.perform(put("/api/v1/accounts/{accountId}", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyMissingVersion))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors").exists())
                .andExpect(jsonPath("$.fieldErrors.version").exists());

        verify(accountService, never()).updateAccount(anyString(), any());
    }

    /**
     * PUT malformed path id: with a <em>valid</em> body (so {@code @RequestBody} resolution succeeds
     * first), a non-numeric path id fails the method-level {@code @Pattern} at invocation time, raising
     * {@code ConstraintViolationException} &rarr; HTTP&nbsp;400 {@code "Validation failed"}. The service
     * must never be reached.
     */
    @Test
    void updateAccount_malformedPathId_returns400ValidationFailed() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/{accountId}", "abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));

        verify(accountService, never()).updateAccount(anyString(), any());
    }

    /**
     * PUT service-raised business-rule failure: the (mocked) service rejects a structurally valid body
     * on a domain rule by throwing {@link ValidationException} carrying a {@code fieldErrors} map. The
     * handler maps it to HTTP&nbsp;400 and copies the per-field detail into the body. This reproduces
     * the legacy {@code COACTUPC} {@code 1200-EDIT-*} edit-error path and is distinct from the
     * structural (Bean Validation) failure above.
     */
    @Test
    void updateAccount_serviceValidationException_returns400WithFieldErrors() throws Exception {
        when(accountService.updateAccount(eq(ACCOUNT_ID), any(AccountUpdateRequest.class)))
                .thenThrow(new ValidationException("Validation failed",
                        Map.of("creditLimit", "creditLimit exceeds maximum")));

        mockMvc.perform(put("/api/v1/accounts/{accountId}", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").exists())
                .andExpect(jsonPath("$.fieldErrors.creditLimit").value("creditLimit exceeds maximum"));
    }

    /**
     * PUT optimistic-lock conflict: a stale {@code version} surfaces from the service as
     * {@link ObjectOptimisticLockingFailureException}, which the handler maps to HTTP&nbsp;409 with the
     * modernized constant {@code "Record updated by another user - please retry"} (owned by
     * {@code GlobalExceptionHandler}; deliberately NOT the legacy COBOL {@code 9700} literal). The
     * exception's own message is ignored by the handler.
     */
    @Test
    void updateAccount_versionConflict_returns409WithExactMessage() throws Exception {
        when(accountService.updateAccount(eq(ACCOUNT_ID), any(AccountUpdateRequest.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException("account", ACCOUNT_ID));

        mockMvc.perform(put("/api/v1/accounts/{accountId}", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Record updated by another user - please retry"));
    }

    /**
     * PUT not-found: the (mocked) service raises {@link AccountNotFoundException} for the missing key,
     * mapped to HTTP&nbsp;404 with the id-free message. Reproduces the legacy {@code COACTUPC}
     * {@code 9600} {@code READ ... UPDATE} {@code NOTFND} path. The exception exposes only a no-argument
     * constructor.
     */
    @Test
    void updateAccount_notFound_returns404() throws Exception {
        when(accountService.updateAccount(eq(MISSING_ID), any(AccountUpdateRequest.class)))
                .thenThrow(new AccountNotFoundException());

        mockMvc.perform(put("/api/v1/accounts/{accountId}", MISSING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Account not found in Acct Master file."));
    }

    /**
     * PUT over-posting is impossible by construction: extra read-only keys ({@code accountId},
     * {@code groupId}) that the write model does not define are silently ignored during binding
     * (Spring Boot's default {@code FAIL_ON_UNKNOWN_PROPERTIES=false}). The request therefore
     * deserializes, validates, and succeeds (HTTP&nbsp;200), and the returned {@code accountId} is the
     * authoritative PATH id ({@code 00000000001}) &mdash; never the injected {@code 99999999999}.
     * This proves the &sect;0.7.2 read-only tightening at the HTTP boundary.
     */
    @Test
    void updateAccount_overPostingIgnoresReadOnlyFields_returns200() throws Exception {
        when(accountService.updateAccount(eq(ACCOUNT_ID), any(AccountUpdateRequest.class)))
                .thenReturn(sampleResponse(1L));

        String overPostBody = """
            {
              "accountId": "99999999999",
              "groupId": "HACKER",
              "activeStatus": "Y",
              "currentBalance": 194.00,
              "creditLimit": 2020.00,
              "cashCreditLimit": 1020.00,
              "openDate": "2014-11-20",
              "expirationDate": "2025-05-20",
              "reissueDate": "2025-05-20",
              "currentCycleCredit": 0.00,
              "currentCycleDebit": 0.00,
              "addressZip": "A000000000",
              "version": 0
            }
            """;

        mockMvc.perform(put("/api/v1/accounts/{accountId}", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(overPostBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("00000000001"));
    }

    // ------------------------------------------------------------------
    // Framework error contract — malformed/empty body, strict numeric
    // coercion, unsupported method/media type, unknown route (M1/M2/M8/N1)
    // ------------------------------------------------------------------

    /**
     * PUT malformed JSON body: an unparseable payload raises {@code HttpMessageNotReadableException},
     * which {@code GlobalExceptionHandler} maps to a sanitized HTTP&nbsp;400 carrying the fixed generic
     * summary and the route-template {@code path} (never parser detail, never the raw URI). The service
     * is never invoked.
     */
    @Test
    void updateAccount_malformedJsonBody_returns400Sanitized() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/{accountId}", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not valid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"))
                .andExpect(jsonPath("$.path").value("/api/v1/accounts/{accountId}"));

        verify(accountService, never()).updateAccount(anyString(), any());
    }

    /**
     * PUT empty body: a required-but-absent request body also surfaces as
     * {@code HttpMessageNotReadableException} &rarr; sanitized HTTP&nbsp;400. The service is never invoked.
     */
    @Test
    void updateAccount_emptyBody_returns400Sanitized() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/{accountId}", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"));

        verify(accountService, never()).updateAccount(anyString(), any());
    }

    /**
     * PUT with an OBJECT {@code version} token ({@code {}}): Jackson cannot map a JSON object onto a
     * {@code Long}, so binding fails with {@code HttpMessageNotReadableException} &rarr; sanitized 400.
     * (This holds independently of the strict-coercion policy.) The service is never invoked.
     */
    @Test
    void updateAccount_objectVersionToken_returns400() throws Exception {
        String body = validRequestJson().replace("\"version\": 0", "\"version\": {}");

        mockMvc.perform(put("/api/v1/accounts/{accountId}", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"));

        verify(accountService, never()).updateAccount(anyString(), any());
    }

    /**
     * PUT with a FRACTIONAL {@code version} token ({@code 0.5}): the strict numeric-coercion policy
     * (config/JacksonConfig) refuses to truncate a floating-point literal into the {@code Long version},
     * so binding fails &rarr; sanitized 400. Silent truncation would corrupt the optimistic-lock
     * comparison. The service is never invoked.
     */
    @Test
    void updateAccount_fractionalVersionToken_returns400() throws Exception {
        String body = validRequestJson().replace("\"version\": 0", "\"version\": 0.5");

        mockMvc.perform(put("/api/v1/accounts/{accountId}", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"));

        verify(accountService, never()).updateAccount(anyString(), any());
    }

    /**
     * PUT with a STRING {@code version} token ({@code "0"}): the strict policy refuses to parse a quoted
     * string into the {@code Long version} &rarr; sanitized 400. The service is never invoked.
     */
    @Test
    void updateAccount_stringVersionToken_returns400() throws Exception {
        String body = validRequestJson().replace("\"version\": 0", "\"version\": \"0\"");

        mockMvc.perform(put("/api/v1/accounts/{accountId}", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"));

        verify(accountService, never()).updateAccount(anyString(), any());
    }

    /**
     * PUT with a STRING monetary token ({@code "194.00"}): the strict policy refuses to parse a quoted
     * string into a {@code BigDecimal} money field &rarr; sanitized 400 (genuine JSON numbers remain
     * valid, as the happy-path tests show). The service is never invoked.
     */
    @Test
    void updateAccount_stringMoneyToken_returns400() throws Exception {
        String body = validRequestJson().replace("\"currentBalance\": 194.00", "\"currentBalance\": \"194.00\"");

        mockMvc.perform(put("/api/v1/accounts/{accountId}", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"));

        verify(accountService, never()).updateAccount(anyString(), any());
    }

    /**
     * Unsupported HTTP method: {@code POST} to the account resource (which exposes only {@code GET} and
     * {@code PUT}) raises {@code HttpRequestMethodNotSupportedException} &rarr; HTTP&nbsp;405 in the
     * uniform {@code ApiError} shape. The service is never touched.
     */
    @Test
    void account_unsupportedMethod_returns405Sanitized() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{accountId}", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.message").value("Request method not supported"));

        verifyNoInteractions(accountService);
    }

    /**
     * Unsupported media type: a {@code PUT} with {@code Content-Type: text/plain} cannot be read into
     * the JSON DTO, raising {@code HttpMediaTypeNotSupportedException} &rarr; HTTP&nbsp;415 in the
     * uniform {@code ApiError} shape. The service is never touched.
     */
    @Test
    void updateAccount_unsupportedMediaType_returns415Sanitized() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/{accountId}", ACCOUNT_ID)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain text body"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.message").value("Request content type is not supported"));

        verifyNoInteractions(accountService);
    }

    /**
     * Unacceptable {@code Accept} header: a GET whose {@code Accept} cannot be satisfied by the
     * JSON-only representation this API produces raises the framework
     * {@code HttpMediaTypeNotAcceptableException} &rarr; HTTP&nbsp;406 in the uniform
     * {@code ApiError} shape. The handler pins the response to {@code application/json} so the
     * error body is still serialized as JSON, and {@code $.path} is the sanitized route template
     * ({@code /api/v1/accounts/&#123;accountId&#125;}) so the concrete account id never leaks
     * (Technical Specification &sect;0.6.6).
     */
    @Test
    void getAccount_unacceptableAcceptHeader_returns406Sanitized() throws Exception {
        when(accountService.getAccount(ACCOUNT_ID)).thenReturn(sampleResponse(0L));

        mockMvc.perform(get("/api/v1/accounts/{accountId}", ACCOUNT_ID)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(406))
                .andExpect(jsonPath("$.error").value("Not Acceptable"))
                .andExpect(jsonPath("$.message").value("Not acceptable"))
                .andExpect(jsonPath("$.path").value("/api/v1/accounts/{accountId}"));
    }

    /**
     * Unknown route: a request path that matches no controller mapping raises the framework
     * {@code NoResourceFoundException} &rarr; HTTP&nbsp;404 in the uniform {@code ApiError} shape with
     * the fixed generic summary (distinct from the id-free account-not-found message). The service is
     * never touched.
     */
    @Test
    void unknownRoute_returns404Sanitized() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Requested resource was not found"));

        verifyNoInteractions(accountService);
    }
}
