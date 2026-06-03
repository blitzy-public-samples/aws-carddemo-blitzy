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
package com.carddemo.controller;

import com.carddemo.controller.advice.GlobalExceptionHandler;
import com.carddemo.dto.billpayment.BillPaymentRequest;
import com.carddemo.dto.billpayment.BillPaymentResponse;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.exception.OverlimitException;
import com.carddemo.service.BillPaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasLength;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc web-slice tests for {@link BillPaymentController} &mdash; the stateless REST replacement
 * for the legacy CICS online program {@code app/cbl/COBIL00C.cbl} (TRANID {@code 'CB00'},
 * "Bill Payment").
 *
 * <p>COBIL00C pays an account's current balance <em>in full</em>: after reading the account and
 * confirming the request it posts a single payment {@code Transaction} against the account's card
 * and reduces {@code ACCT-CURR-BAL} to zero. The single endpoint under test is
 * {@code POST /api/accounts/{acctId}/payments}; the COBIL00C business logic itself lives in
 * {@link BillPaymentService} (stubbed here as a {@link MockBean}), so this slice verifies only the
 * HTTP boundary &mdash; request binding, response serialization, and exception-to-status mapping.</p>
 *
 * <h2>COBIL00C semantics asserted at the HTTP boundary</h2>
 * <ul>
 *   <li><b>Full-balance pay</b> &mdash; COBIL00C always paid the full balance
 *       ({@code MOVE ACCT-CURR-BAL TO TRAN-AMT}, L224) and then reduced the balance to zero
 *       ({@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}, L234). The happy-path response
 *       therefore carries {@code paymentAmount == previousBalance}, {@code newBalance == 0.00}, and
 *       {@code availableCredit == creditLimit} (the available credit returns to the full limit once
 *       the balance is zero, per AAP &sect;0.4.1.1).</li>
 *   <li><b>Nothing-to-pay guard</b> (L198-201) &mdash; a zero/negative balance surfaces as
 *       {@code IllegalStateException("You have nothing to pay...")} which
 *       {@link GlobalExceptionHandler} maps to {@code 422 Unprocessable Entity}
 *       (code {@code "ILLEGAL_STATE"}); the exact COBOL literal is preserved on the payload.</li>
 *   <li><b>Confirmation guard</b> (L210/L236-238) &mdash; a non-{@code "Y"} confirmation surfaces as
 *       {@code IllegalStateException("Confirm to make a bill payment...")} &rarr; {@code 422}.</li>
 *   <li><b>Account-not-found</b> (L361/L392/L425) &mdash; an absent account/card cross-reference
 *       surfaces as {@link AccountNotFoundException} (COBOL code 101) carrying the verbatim
 *       {@code "Account ID NOT found..."} message &rarr; {@code 404 Not Found}.</li>
 * </ul>
 *
 * <h2>Response shape under test</h2>
 * <p>{@link BillPaymentResponse} is an immutable Java&nbsp;17 {@code record}; its components
 * serialize to the JSON keys {@code tranId}, {@code accountId}, {@code paymentAmount},
 * {@code previousBalance}, {@code newBalance}, {@code availableCredit}, {@code processedAt}, and
 * {@code successMessage}. The legacy {@code TRAN-DESC}/{@code TRAN-MERCHANT-ID}/
 * {@code TRAN-MERCHANT-NAME} constants are written onto the internal {@code Transaction} entity by
 * {@link BillPaymentService} and are <em>not</em> echoed in this response, so they are verified in
 * the service/integration tests rather than this web slice.</p>
 *
 * <h2>Slice configuration (matches the established pattern used by every controller test in this
 * module)</h2>
 * <ul>
 *   <li>{@link WebMvcTest @WebMvcTest(controllers = BillPaymentController.class)} loads only the
 *       {@code BillPaymentController} web layer; its sole collaborator {@link BillPaymentService}
 *       is supplied as a {@link MockBean}.</li>
 *   <li>{@code excludeFilters} drops the entire {@code com.carddemo.security} package from the
 *       slice's component scan. A {@code @WebMvcTest} slice always registers application
 *       {@code jakarta.servlet.Filter} beans, and the production
 *       {@code com.carddemo.security.JwtAuthenticationFilter} is a {@code @Component} extending
 *       {@code OncePerRequestFilter}; left untouched it would be instantiated here and fail the
 *       context with an {@code UnsatisfiedDependencyException} because its
 *       {@code CustomAuthorityMapper} collaborator is not loaded by the slice.
 *       {@code @AutoConfigureMockMvc(addFilters = false)} only removes filters from the MockMvc
 *       dispatch &mdash; it does NOT prevent the bean from being created &mdash; so the
 *       component-scan exclusion is what actually keeps the context minimal.</li>
 *   <li>{@link Import @Import(GlobalExceptionHandler.class)} wires the
 *       {@code @RestControllerAdvice} so HTTP status-code assertions reflect production error
 *       handling ({@code IllegalStateException} &rarr; 422, {@link AccountNotFoundException} &rarr;
 *       404, {@link OverlimitException} &rarr; 422, validation/type-mismatch failures &rarr; 400).
 *       The advice has no injected dependencies (it uses {@code com.carddemo.util.CardNumberMasker}
 *       statically), so importing it requires no additional beans.</li>
 *   <li>{@link AutoConfigureMockMvc @AutoConfigureMockMvc(addFilters = false)} disables the
 *       security filter chain so the controller's HTTP behaviour is asserted in isolation.
 *       {@code BillPaymentController} carries no class-level {@code @PreAuthorize}: per AAP
 *       &sect;0.4.1.1 any <em>authenticated</em> caller (USER or ADMIN) may make a bill payment,
 *       exactly as COBIL00C was reachable from the regular user menu ({@code COMEN01C}). Each test
 *       therefore runs with {@code @WithMockUser} (a default {@code ROLE_USER} principal), which
 *       populates {@code SecurityContextHolder} via the test execution listener independently of the
 *       disabled filter chain. Every POST still carries {@code .with(csrf())} for
 *       forward-compatibility if the filters are re-enabled.</li>
 * </ul>
 *
 * <h2>Why the service call is {@code processBillPayment}</h2>
 * <p>{@code BillPaymentController} delegates to
 * {@link BillPaymentService#processBillPayment(Long, BillPaymentRequest)} (the COBIL00C
 * read-account &rarr; nothing-to-pay guard &rarr; confirmation guard &rarr; resolve-card &rarr;
 * write-transaction &rarr; reduce-balance flow) and returns {@code 200 OK}. Tests stub and verify
 * exactly that method &mdash; not a {@code processPayment}/{@code pay} &mdash; so the slice
 * exercises the real controller-to-service contract.</p>
 *
 * <h2>Refactoring rules exercised</h2>
 * <ul>
 *   <li><b>PR-10</b> &mdash; the generated {@code tranId} is exactly 16 characters
 *       ({@code parmDate(10) + suffix(6)}); the happy path asserts both value and length.</li>
 *   <li><b>PR-11</b> &mdash; {@code processedAt} is the 26-character DB2 timestamp string
 *       ({@code yyyy-MM-dd-HH.mm.ss.SS'0000'}).</li>
 *   <li><b>PR-16</b> &mdash; every monetary field is an exact scale-2 {@link BigDecimal}, asserted
 *       with string-constructed values (never a {@code double} literal fixture) and confirmed
 *       byte-for-byte for the smallest cent ({@code 0.01}).</li>
 *   <li><b>PR-28</b> &mdash; Jakarta EE 10 baseline (no {@code javax.*}).</li>
 *   <li><b>PR-29</b> &mdash; {@code BillPaymentController} uses constructor injection of a single
 *       {@code final BillPaymentService}; the slice supplies it via {@code @MockBean}.</li>
 * </ul>
 *
 * @see BillPaymentController
 * @see BillPaymentService
 * @see BillPaymentRequest
 * @see BillPaymentResponse
 * @see GlobalExceptionHandler
 */
@WebMvcTest(
        controllers = BillPaymentController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.carddemo\\.security\\..*"))
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("BillPaymentController web-slice tests (POST /api/accounts/{acctId}/payments)")
class BillPaymentControllerTest {

    /**
     * Account id used for the happy-path and business-rule scenarios. Mirrors COBOL
     * {@code ACCT-ID PIC 9(11)} ({@code app/cpy/CVACT01Y.cpy}) and lies within the controller's
     * {@code @Min(1)}/{@code @Max(99999999999L)} bounds. The value (100000001) is within
     * {@code int} range, so JSON-number assertions may use a plain integer literal.
     */
    private static final long ACCT_ID = 100000001L;

    /**
     * The canonical 16-character transaction id used across the fixtures (PR-10). The first ten
     * characters ({@code 2024010100}) are the {@code parmDate} prefix; the last six
     * ({@code 000042}) are the sequential suffix &mdash; together exactly 16 characters as mandated
     * by {@code TRAN-ID PIC X(16)} ({@code app/cpy/CVTRA05Y.cpy}).
     */
    private static final String TRAN_ID = "2024010100000042";

    /**
     * A 26-character DB2 external-format timestamp ({@code yyyy-MM-dd-HH.mm.ss.SS'0000'}) used for
     * the {@code processedAt} fixture (PR-11). The trailing {@code 0000} literal and millisecond
     * positions preserve byte-for-byte compatibility with the COBOL timestamp emission.
     */
    private static final String PROCESSED_AT = "2024-01-01-09.30.45.120000";

    /** Auto-configured MockMvc for the {@code BillPaymentController} web slice (filters disabled). */
    @Autowired
    private MockMvc mockMvc;

    /** Jackson mapper used to serialize {@link BillPaymentRequest} fixtures into JSON POST bodies. */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The sole controller collaborator, replaced by a Mockito mock in the slice context. The real
     * {@code BillPaymentService} performs the COBIL00C flow; here it is stubbed so the controller's
     * HTTP behaviour is asserted in isolation.
     */
    @MockBean
    private BillPaymentService billPaymentService;

    /**
     * Builds a fully-valid {@link BillPaymentRequest} for the given account that satisfies every
     * Jakarta Bean Validation constraint on the DTO &mdash; {@code accountId}
     * ({@code @NotNull @Positive}) and {@code confirmation} ({@code @NotBlank @Size(1,1)}
     * {@code @Pattern("[YN]")}) &mdash; so the request passes the controller's {@code @Valid} check
     * and its body/path consistency check, reaching the (stubbed) service. COBIL00C took no amount
     * input (it always paid the full balance), so the request carries only the account id and the
     * {@code "Y"} confirmation flag.
     *
     * @param accountId the body account id, set equal to the path {@code acctId} for consistency
     * @return a valid bill-payment request payload
     */
    private BillPaymentRequest validRequest(long accountId) {
        return BillPaymentRequest.builder()
                .accountId(accountId)
                .confirmation("Y")
                .build();
    }

    /**
     * Tests for {@code POST /api/accounts/{acctId}/payments} &mdash; the online bill-payment flow
     * replacing {@code app/cbl/COBIL00C.cbl} (TRANID {@code 'CB00'}). COBIL00C pays the full
     * balance and reduces it to zero; the response echoes the generated transaction id, the balance
     * state before/after, the resulting available credit, the 26-character DB2 timestamp, and the
     * COBIL00C success message.
     */
    @Nested
    @DisplayName("POST /api/accounts/{acctId}/payments — replaces COBIL00C bill payment")
    class CreateBillPayment {

        @Test
        @DisplayName("Happy path: positive balance -> 200 OK, full-balance pay leaves newBalance 0.00 (COBIL00C)")
        @WithMockUser
        void shouldProcessBillPayment() throws Exception {
            // COBIL00C pays the FULL balance (MOVE ACCT-CURR-BAL TO TRAN-AMT, L224) then reduces the
            // balance to zero (COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT, L234). So the
            // response carries paymentAmount == previousBalance, newBalance == 0.00, and
            // availableCredit == creditLimit. All monetary values are exact scale-2 BigDecimals.
            BillPaymentResponse response = new BillPaymentResponse(
                    TRAN_ID,                       // PR-10: 16-char tranId (parmDate(10) + suffix(6))
                    ACCT_ID,                       // echoed account id
                    new BigDecimal("1234.56"),     // paymentAmount = full pre-payment balance
                    new BigDecimal("1234.56"),     // previousBalance (before payment)
                    new BigDecimal("0.00"),        // newBalance reduced to zero (COBIL00C L234)
                    new BigDecimal("5000.00"),     // availableCredit = creditLimit (balance now 0)
                    PROCESSED_AT,                  // PR-11: 26-char DB2 timestamp
                    "Payment successful.  Your Transaction ID is " + TRAN_ID + "."); // COBIL00C L527

            when(billPaymentService.processBillPayment(eq(ACCT_ID), any(BillPaymentRequest.class)))
                    .thenReturn(response);

            mockMvc.perform(post("/api/accounts/{acctId}/payments", ACCT_ID)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest(ACCT_ID))))
                    // BillPaymentController returns ResponseEntity.ok(...) -> 200 OK (not 201).
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountId").value(100000001))
                    // PR-10: tranId value and exact 16-character length.
                    .andExpect(jsonPath("$.tranId").value(TRAN_ID))
                    .andExpect(jsonPath("$.tranId", hasLength(16)))
                    // PR-16: scale-2 BigDecimal money fields serialized as plain JSON numbers.
                    .andExpect(jsonPath("$.paymentAmount").value(1234.56))
                    .andExpect(jsonPath("$.previousBalance").value(1234.56))
                    // Full-balance pay: the new balance is zero.
                    .andExpect(jsonPath("$.newBalance").value(0.00))
                    // Available credit returns to the full credit limit once the balance is zero.
                    .andExpect(jsonPath("$.availableCredit").value(5000.00))
                    // PR-11: 26-character DB2 timestamp preserved verbatim.
                    .andExpect(jsonPath("$.processedAt").value(PROCESSED_AT))
                    .andExpect(jsonPath("$.processedAt", hasLength(26)))
                    // COBIL00C L527 success message preserved (two spaces after the period).
                    .andExpect(jsonPath("$.successMessage")
                            .value("Payment successful.  Your Transaction ID is " + TRAN_ID + "."));

            verify(billPaymentService).processBillPayment(eq(ACCT_ID), any(BillPaymentRequest.class));
        }

        @Test
        @DisplayName("Zero balance -> 422 with EXACT 'You have nothing to pay...' (COBIL00C L201)")
        @WithMockUser
        void shouldRejectZeroBalancePayment() throws Exception {
            // COBIL00C L198-201: IF ACCT-CURR-BAL <= ZEROS -> 'You have nothing to pay...'.
            // BillPaymentService throws IllegalStateException with that exact literal, which
            // GlobalExceptionHandler maps to 422 Unprocessable Entity (code "ILLEGAL_STATE"),
            // preserving the message (it is PAN-free, so the defensive masking is a no-op).
            when(billPaymentService.processBillPayment(eq(ACCT_ID), any(BillPaymentRequest.class)))
                    .thenThrow(new IllegalStateException("You have nothing to pay..."));

            mockMvc.perform(post("/api/accounts/{acctId}/payments", ACCT_ID)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest(ACCT_ID))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status").value(422))
                    .andExpect(jsonPath("$.code").value("ILLEGAL_STATE"))
                    // Exact COBOL literal preserved on the error payload.
                    .andExpect(jsonPath("$.message").value("You have nothing to pay..."));

            verify(billPaymentService).processBillPayment(eq(ACCT_ID), any(BillPaymentRequest.class));
        }

        @Test
        @DisplayName("Unconfirmed (confirmation=N) -> 422 with EXACT 'Confirm to make a bill payment...' (COBIL00C L237)")
        @WithMockUser
        void shouldRejectUnconfirmedPayment() throws Exception {
            // 'N' is a valid Y/N value (passes @Valid) but is not 'Y', so COBIL00C's confirmation
            // guard (L210/L236-238) rejects it: BillPaymentService throws IllegalStateException ->
            // 422 with the exact COBOL literal. This proves the request reaches the service (a
            // well-formed-but-unconfirmed request is a business-rule 422, not a validation 400).
            when(billPaymentService.processBillPayment(eq(ACCT_ID), any(BillPaymentRequest.class)))
                    .thenThrow(new IllegalStateException("Confirm to make a bill payment..."));

            BillPaymentRequest req = BillPaymentRequest.builder()
                    .accountId(ACCT_ID)
                    .confirmation("N")
                    .build();

            mockMvc.perform(post("/api/accounts/{acctId}/payments", ACCT_ID)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_STATE"))
                    .andExpect(jsonPath("$.message").value("Confirm to make a bill payment..."));

            verify(billPaymentService).processBillPayment(eq(ACCT_ID), any(BillPaymentRequest.class));
        }

        @Test
        @DisplayName("Unknown account -> 404 with EXACT 'Account ID NOT found...' (COBOL code 101)")
        @WithMockUser
        void shouldReturn404ForUnknownAccount() throws Exception {
            // COBIL00C L361/L392/L425: an absent account / card cross-reference yields
            // 'Account ID NOT found...'. BillPaymentService raises
            // AccountNotFoundException.withMessage(...) (verbatim message, COBOL code 101), which
            // GlobalExceptionHandler maps to 404 Not Found.
            when(billPaymentService.processBillPayment(eq(ACCT_ID), any(BillPaymentRequest.class)))
                    .thenThrow(AccountNotFoundException.withMessage("Account ID NOT found..."));

            mockMvc.perform(post("/api/accounts/{acctId}/payments", ACCT_ID)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest(ACCT_ID))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    // PR-03: COBOL reason code 101 preserved on the payload.
                    .andExpect(jsonPath("$.code").value("101"))
                    .andExpect(jsonPath("$.message").value("Account ID NOT found..."));

            verify(billPaymentService).processBillPayment(eq(ACCT_ID), any(BillPaymentRequest.class));
        }

        @Test
        @DisplayName("Service OverlimitException -> 422 with EXACT 'OVERLIMIT TRANSACTION' (code 102) mapping")
        @WithMockUser
        void shouldMapOverlimitExceptionTo422() throws Exception {
            // Contract check for the shared exception handling this slice @Imports: the COBOL
            // code-102 OverlimitException maps to 422 emitting the EXACT COBOL_MESSAGE verbatim
            // (GlobalExceptionHandler always returns OverlimitException.COBOL_MESSAGE, regardless of
            // the constructor used). The no-arg constructor yields the exact literal. This documents
            // the 422 boundary for OverlimitException reaching the bill-payment endpoint.
            when(billPaymentService.processBillPayment(eq(ACCT_ID), any(BillPaymentRequest.class)))
                    .thenThrow(new OverlimitException());

            mockMvc.perform(post("/api/accounts/{acctId}/payments", ACCT_ID)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest(ACCT_ID))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("102"))
                    .andExpect(jsonPath("$.message").value("OVERLIMIT TRANSACTION"));

            verify(billPaymentService).processBillPayment(eq(ACCT_ID), any(BillPaymentRequest.class));
        }

        @Test
        @DisplayName("Non-numeric account id path -> 400 Bad Request (type mismatch; service never called)")
        @WithMockUser
        void shouldReject400OnNonNumericId() throws Exception {
            // "ABC" cannot bind to the Long acctId path variable -> MethodArgumentTypeMismatchException
            // -> 400 during argument resolution, before the controller body (and the service) runs.
            mockMvc.perform(post("/api/accounts/{acctId}/payments", "ABC")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(billPaymentService);
        }

        @Test
        @DisplayName("Empty JSON body -> 400 Bad Request (bean validation; service never called)")
        @WithMockUser
        void shouldReject400OnEmptyBody() throws Exception {
            // {} leaves accountId (@NotNull) and confirmation (@NotBlank) absent, so @Valid fails
            // with MethodArgumentNotValidException -> 400 before the controller body (and the
            // service) is ever reached.
            mockMvc.perform(post("/api/accounts/{acctId}/payments", ACCT_ID)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(billPaymentService);
        }

        @Test
        @DisplayName("Malformed JSON -> 400 Bad Request (service never called)")
        @WithMockUser
        void shouldReject400OnMalformedJson() throws Exception {
            // Non-JSON content raises HttpMessageNotReadableException -> 400 at body deserialization,
            // before the controller body (and the service) is ever reached.
            mockMvc.perform(post("/api/accounts/{acctId}/payments", ACCT_ID)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("not a json"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(billPaymentService);
        }

        @Test
        @DisplayName("Body accountId != path acctId -> 400 Bad Request (controller consistency check)")
        @WithMockUser
        void shouldReject400OnBodyPathAccountMismatch() throws Exception {
            // The path acctId is authoritative. A non-null body accountId that differs is rejected by
            // the controller with IllegalArgumentException -> 400 (ILLEGAL_ARGUMENT) before any
            // service call. The body confirmation is a valid 'Y', so only the id mismatch triggers
            // the 400 (not bean validation).
            BillPaymentRequest mismatched = BillPaymentRequest.builder()
                    .accountId(999999999L)   // differs from the path ACCT_ID (100000001)
                    .confirmation("Y")
                    .build();

            mockMvc.perform(post("/api/accounts/{acctId}/payments", ACCT_ID)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(mismatched)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_ARGUMENT"));

            verifyNoInteractions(billPaymentService);
        }

        @Test
        @DisplayName("PR-16: smallest cent (0.01) survives serialization with scale 2")
        @WithMockUser
        void shouldPreserveBigDecimalScale() throws Exception {
            // Boundary fixture: 0.01 is the smallest representable cent. A 0.01 balance is paid in
            // full, leaving newBalance 0.00. Asserts both the numeric value and the byte-level "0.01".
            BillPaymentResponse response = new BillPaymentResponse(
                    "2024010100000099",
                    ACCT_ID,
                    new BigDecimal("0.01"),     // paymentAmount = full 0.01 balance
                    new BigDecimal("0.01"),     // previousBalance
                    new BigDecimal("0.00"),     // newBalance reduced to zero
                    new BigDecimal("5000.00"),  // availableCredit returns to full limit
                    PROCESSED_AT,
                    "Payment successful.  Your Transaction ID is 2024010100000099.");

            when(billPaymentService.processBillPayment(eq(ACCT_ID), any(BillPaymentRequest.class)))
                    .thenReturn(response);

            mockMvc.perform(post("/api/accounts/{acctId}/payments", ACCT_ID)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest(ACCT_ID))))
                    .andExpect(status().isOk())
                    // PR-16: 0.01 cents preserved both as a JSON number and byte-for-byte.
                    .andExpect(jsonPath("$.paymentAmount").value(0.01))
                    .andExpect(jsonPath("$.previousBalance").value(0.01))
                    .andExpect(content().string(containsString("0.01")));

            verify(billPaymentService).processBillPayment(eq(ACCT_ID), any(BillPaymentRequest.class));
        }
    }
}
