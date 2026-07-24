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
package com.carddemo.billpay.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.billpay.service.BillPaymentService;
import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.dto.BillPaymentRequestDto;
import com.carddemo.common.dto.BillPaymentResponseDto;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.OptimisticLockConflictException;
import com.carddemo.common.exception.RecordNotFoundException;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

/**
 * :purpose: Web-slice (``@WebMvcTest``) tests for {@link BillPaymentController}, the REST
 *  adapter (``POST /billpay``) re-platforming the legacy CICS bill-payment program
 *  ``COBIL00C`` (transaction ``CB00``). Each scenario stubs the mocked
 *  {@link BillPaymentService}, drives the controller with {@link MockMvc}, and asserts the
 *  HTTP status and JSON body produced by the controller together with the shared
 *  {@link GlobalExceptionHandler}, holding the byte-exact ``COBIL00C`` messages and the
 *  exception-to-status contract.
 * :note: {@link GlobalExceptionHandler} lives in ``carddemo-common`` (outside this
 *  controller's package) and is therefore registered explicitly with
 *  ``@Import`` so the domain exceptions map to 400/404/409 rather than surfacing as 500.
 * :note: ``@ContextConfiguration`` pins the slice context to {@link BillPaymentController}
 *  so the bootstrapper does not fall back to the ``@SpringBootApplication`` class, whose
 *  ``@EnableJpaRepositories`` would otherwise require a JPA ``EntityManagerFactory`` absent
 *  from this pure web slice.
 */
@WebMvcTest(BillPaymentController.class)
@ContextConfiguration(classes = BillPaymentController.class)
@Import(GlobalExceptionHandler.class)
class BillPaymentControllerTest {

    /** :purpose: Frozen ``HttpSession`` attribute key shared by every CardDemo service. */
    private static final String SESSION_CONTEXT_ATTRIBUTE = "carddemoSessionContext";

    /** :purpose: Verbatim ``COBIL00C`` confirm-payment prompt (L237). */
    private static final String MSG_CONFIRM_PAYMENT = "Confirm to make a bill payment...";

    /** :purpose: Verbatim ``COBIL00C`` account / cross-reference not-found message (L361/392/425). */
    private static final String MSG_ACCOUNT_NOT_FOUND = "Account ID NOT found...";

    /** :purpose: Verbatim ``COBIL00C`` empty account-id message (L161). */
    private static final String MSG_ACCT_ID_EMPTY = "Acct ID can NOT be empty...";

    /** :purpose: MockMvc entry point auto-configured for the bill-payment controller slice. */
    @Autowired
    private MockMvc mockMvc;

    /** :purpose: Jackson mapper used to serialize typed request DTOs into JSON bodies. */
    @Autowired
    private ObjectMapper objectMapper;

    /** :purpose: Mocked bill-payment collaborator; stubbed per scenario, never really invoked. */
    @MockitoBean
    private BillPaymentService billPaymentService;

    /**
     * :purpose: Confirmed payment (confirm ``Y``) returns HTTP 200 with the post-payment balance,
     *  the 16-digit transaction id and the verbatim two-space success message, and forwards the
     *  session-supplied {@link SessionContext} to the service.
     * :returns: passes when the response is 200, ``$.currentBalance`` is ``0.00``,
     *  ``$.transactionId`` is sixteen digits and ``$.message`` equals the ``COBIL00C`` L527-531
     *  success banner, and the controller forwarded the seeded session context.
     */
    @Test
    @DisplayName("POST /billpay confirmed payment returns 200 with the two-space success message")
    void confirmedPaymentReturns200WithSuccessMessage() throws Exception {
        String tranId = "0000000000000001";
        String successMessage = String.format("Payment successful.  Your Transaction ID is %s.", tranId);
        BillPaymentResponseDto stubbed =
                new BillPaymentResponseDto("12345678901", new BigDecimal("0.00"), tranId, successMessage);
        when(billPaymentService.processBillPayment(any(BillPaymentRequestDto.class), any(SessionContext.class)))
                .thenReturn(stubbed);

        SessionContext seededContext = new SessionContext();
        seededContext.setAcctId(12345678901L);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SESSION_CONTEXT_ATTRIBUTE, seededContext);

        BillPaymentRequestDto request = new BillPaymentRequestDto("12345678901", "Y");

        mockMvc.perform(post("/billpay")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("12345678901"))
                .andExpect(jsonPath("$.currentBalance").value(0.00))
                .andExpect(jsonPath("$.transactionId", matchesPattern("^\\d{16}$")))
                .andExpect(jsonPath("$.transactionId").value(tranId))
                .andExpect(jsonPath("$.message").value(successMessage));

        ArgumentCaptor<SessionContext> sessionCaptor = ArgumentCaptor.forClass(SessionContext.class);
        verify(billPaymentService).processBillPayment(any(BillPaymentRequestDto.class), sessionCaptor.capture());
        assertThat(sessionCaptor.getValue()).isSameAs(seededContext);
        assertThat(sessionCaptor.getValue().getAcctId()).isEqualTo(12345678901L);
    }

    /**
     * :purpose: Blank-confirmation preview returns HTTP 200 carrying the confirm prompt and no
     *  transaction id (``COBIL00C`` L235-238), without a session attribute supplied.
     * :returns: passes when the response is 200 and ``$.message`` equals the confirm prompt.
     */
    @Test
    @DisplayName("POST /billpay blank confirmation returns 200 with the confirm prompt")
    void previewReturns200WithConfirmPrompt() throws Exception {
        BillPaymentResponseDto stubbed =
                new BillPaymentResponseDto("12345678901", new BigDecimal("100.00"), null, MSG_CONFIRM_PAYMENT);
        when(billPaymentService.processBillPayment(any(BillPaymentRequestDto.class), any(SessionContext.class)))
                .thenReturn(stubbed);

        BillPaymentRequestDto request = new BillPaymentRequestDto("12345678901", "");

        mockMvc.perform(post("/billpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("12345678901"))
                .andExpect(jsonPath("$.transactionId").doesNotExist())
                .andExpect(jsonPath("$.message").value(MSG_CONFIRM_PAYMENT));
    }

    /**
     * :purpose: A business-rule violation raised by the service as a base {@link CardDemoException}
     *  maps to HTTP 400 with the verbatim message echoed in the error body.
     * :returns: passes when the response is 400, ``$.status`` is 400 and ``$.message`` equals the
     *  thrown ``COBIL00C`` message.
     */
    @Test
    @DisplayName("POST /billpay CardDemoException maps to 400 with the verbatim message")
    void cardDemoExceptionReturns400() throws Exception {
        when(billPaymentService.processBillPayment(any(BillPaymentRequestDto.class), any(SessionContext.class)))
                .thenThrow(new CardDemoException(MSG_ACCT_ID_EMPTY));

        BillPaymentRequestDto request = new BillPaymentRequestDto("12345678901", "Y");

        mockMvc.perform(post("/billpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(MSG_ACCT_ID_EMPTY));
    }

    /**
     * :purpose: An account (or card cross-reference) miss raised as {@link RecordNotFoundException}
     *  maps to HTTP 404 with the verbatim ``COBIL00C`` not-found message.
     * :returns: passes when the response is 404, ``$.status`` is 404 and ``$.message`` equals
     *  ``Account ID NOT found...``.
     */
    @Test
    @DisplayName("POST /billpay RecordNotFoundException maps to 404")
    void recordNotFoundReturns404() throws Exception {
        when(billPaymentService.processBillPayment(any(BillPaymentRequestDto.class), any(SessionContext.class)))
                .thenThrow(new RecordNotFoundException(MSG_ACCOUNT_NOT_FOUND));

        BillPaymentRequestDto request = new BillPaymentRequestDto("99999999999", "Y");

        mockMvc.perform(post("/billpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(MSG_ACCOUNT_NOT_FOUND));
    }

    /**
     * :purpose: A concurrent-modification conflict raised as {@link OptimisticLockConflictException}
     *  maps to HTTP 409 with the frozen legacy conflict message.
     * :returns: passes when the response is 409, ``$.status`` is 409 and ``$.message`` equals
     *  {@link OptimisticLockConflictException#MESSAGE}.
     */
    @Test
    @DisplayName("POST /billpay OptimisticLockConflictException maps to 409")
    void optimisticLockConflictReturns409() throws Exception {
        when(billPaymentService.processBillPayment(any(BillPaymentRequestDto.class), any(SessionContext.class)))
                .thenThrow(new OptimisticLockConflictException());

        BillPaymentRequestDto request = new BillPaymentRequestDto("12345678901", "Y");

        mockMvc.perform(post("/billpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(OptimisticLockConflictException.MESSAGE));
    }

    /**
     * :purpose: A request body that violates the {@code @Valid} bean-validation constraints on
     *  {@link BillPaymentRequestDto} is rejected with HTTP 400 and per-field messages before the
     *  service is reached; the over-length account id breaches the ``@Size(max = 11)`` bound.
     * :returns: passes when the response is 400, ``$.status`` is 400 and ``$.fieldErrors`` is a
     *  non-empty map carrying the ``accountId`` violation, with the service never invoked.
     */
    @Test
    @DisplayName("POST /billpay invalid request body returns 400 with field errors")
    void validationFailureReturns400WithFieldErrors() throws Exception {
        String invalidBody = "{\"accountId\":\"123456789012\",\"confirm\":\"Y\"}";

        mockMvc.perform(post("/billpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.accountId").exists());

        verifyNoInteractions(billPaymentService);
    }
}
