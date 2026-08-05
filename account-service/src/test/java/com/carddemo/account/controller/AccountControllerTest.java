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
package com.carddemo.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.account.service.AccountService;
import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.dto.AccountUpdateRequestDto;
import com.carddemo.common.dto.AccountUpdateResponseDto;
import com.carddemo.common.dto.AccountViewResponseDto;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.exception.OptimisticLockConflictException;
import com.carddemo.common.exception.RecordNotFoundException;

import jakarta.persistence.EntityManagerFactory;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

/**
 * :purpose: ``@WebMvcTest`` web-slice verification of {@link AccountController}, the REST
 *  facade migrated from the legacy CICS account programs -- account view
 *  (``CAVW`` / ``COACTVWC``, ``GET /accounts/{id}``) and account update
 *  (``CAUP`` / ``COACTUPC``, ``PUT /accounts/{id}``). Exercises account-id editing,
 *  request-body deserialization and pass-through, externalized session-context bridging,
 *  and the translation of service-layer domain exceptions into HTTP status codes through
 *  the shared {@link GlobalExceptionHandler}. The business-logic service is mocked; there
 *  is no database, no Redis, and no security on the account-service classpath.
 */
// This slice verifies the controller contract only, so the security filter chain is
// not applied here. Authentication and authorization are configured centrally
// (carddemo-common SecurityHardening plus this service's SecurityConfig), unit-tested
// in carddemo-common, asserted for this service by its SecurityContractTest, and
// verified against the running service.
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(AccountController.class)
@Import(GlobalExceptionHandler.class)
class AccountControllerTest {

    /** :purpose: A valid non-zero eleven-digit account id (path form). */
    private static final String VALID_ID = "12345678901";

    /** :purpose: The parsed ``Long`` value of {@link #VALID_ID}. */
    private static final long VALID_ID_LONG = 12345678901L;

    /** :purpose: Verbatim account-id edit message surfaced as the HTTP 400 body message. */
    private static final String MSG_INVALID_ACCT_ID = "Account number must be a non zero 11 digit number";

    /** :purpose: Verbatim cross-reference-miss message (legacy COACTVWC L130) surfaced as the HTTP 404 body message. */
    private static final String MSG_ACCT_NOT_IN_XREF = "Did not find this account in account card xref file";

    /** :purpose: ``HttpSession`` attribute key under which the externalized session context is stored. */
    private static final String SESSION_ATTR = SessionContext.SESSION_ATTRIBUTE_NAME;

    /** :purpose: Servlet MockMvc entry point auto-configured by the web slice. */
    @Autowired
    private MockMvc mockMvc;

    /** :purpose: Slice-configured Jackson mapper used to serialize request bodies. */
    @Autowired
    private ObjectMapper objectMapper;

    /** :purpose: Mocked account view/update business-logic service (the controller's sole collaborator). */
    @MockitoBean
    private AccountService accountService;

    // Defensive: the account-service main class declares an explicit
    // @EnableJpaRepositories, so the web slice eagerly registers JPA infrastructure the
    // slice does not auto-configure. Overriding the three repositories as mocks replaces
    // their factory beans, and the mock entityManagerFactory bean below satisfies the
    // shared-EntityManager and metamodel-mapping-context singletons, letting the context
    // start without a database. The controller under test never uses any of them.

    /** :purpose: Mock standing in for the account master repository bean. */
    @MockitoBean
    private AccountRepository accountRepository;

    /** :purpose: Mock standing in for the customer master repository bean. */
    @MockitoBean
    private CustomerRepository customerRepository;

    /** :purpose: Mock standing in for the card cross-reference repository bean. */
    @MockitoBean
    private CardXrefRepository cardXrefRepository;

    /**
     * :purpose: Mock ``entityManagerFactory`` bean that satisfies the shared-EntityManager
     *  and metamodel-mapping-context singletons registered eagerly by the explicit
     *  ``@EnableJpaRepositories``. ``RETURNS_MOCKS`` makes ``getMetamodel()`` non-null with an
     *  empty managed-type set, so the mapping context initializes without a real persistence
     *  unit. Never exercised by the controller under test.
     */
    @MockitoBean(name = "entityManagerFactory", answers = Answers.RETURNS_MOCKS)
    private EntityManagerFactory entityManagerFactory;

    /**
     * :purpose: Build a populated account view response for stubbing the view service call.
     * :returns: an {@link AccountViewResponseDto} carrying non-sensitive account fields.
     */
    private AccountViewResponseDto stubViewResponse() {
        AccountViewResponseDto dto = new AccountViewResponseDto();
        dto.setAcctId(VALID_ID_LONG);
        dto.setAcctActiveStatus("Y");
        dto.setAcctCurrBal(new BigDecimal("1234.56"));
        dto.setAcctCreditLimit(new BigDecimal("5000.00"));
        return dto;
    }

    /**
     * :purpose: Build a populated account update response for stubbing the update service call.
     * :returns: an {@link AccountUpdateResponseDto} carrying non-sensitive account fields.
     */
    private AccountUpdateResponseDto stubUpdateResponse() {
        AccountUpdateResponseDto dto = new AccountUpdateResponseDto();
        dto.setAcctId(VALID_ID_LONG);
        dto.setAcctActiveStatus("Y");
        dto.setAcctCurrBal(new BigDecimal("1234.56"));
        return dto;
    }

    /**
     * :purpose: Build a well-formed update request carrying only non-PII editable fields.
     * :returns: a fully populated {@link AccountUpdateRequestDto}.
     */
    private AccountUpdateRequestDto validUpdateRequest() {
        AccountUpdateRequestDto dto = new AccountUpdateRequestDto();
        // The optimistic-lock snapshot is a required part of the update contract.
        dto.setVersion(0L);
        dto.setAcctActiveStatus("Y");
        dto.setAcctCurrBal(new BigDecimal("1234.56"));
        dto.setAcctCreditLimit(new BigDecimal("5000.00"));
        dto.setAcctGroupId("PREMGRP");
        return dto;
    }

    /**
     * :purpose: Build a session in the state sign-on leaves it: carrying the
     *  externalized {@link SessionContext}. The ``SecurityFilterChain`` authenticates
     *  the caller from this attribute, so every request that reaches the controller in
     *  production has it.
     * :returns: a ``MockHttpSession`` carrying a {@link SessionContext}.
     */
    private static MockHttpSession signedOnSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SESSION_ATTR, new SessionContext());
        return session;
    }

    /**
     * :purpose: A valid id yields HTTP 200 and a JSON body echoing non-sensitive account fields.
     */
    @Test
    @DisplayName("GET /accounts/{id} with a valid 11-digit id returns 200 and the account view body")
    void viewAccount_withValid11DigitId_returns200() throws Exception {
        when(accountService.viewAccount(eq(VALID_ID_LONG), any(SessionContext.class)))
                .thenReturn(stubViewResponse());

        mockMvc.perform(get("/accounts/{id}", VALID_ID).session(signedOnSession()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.acctId").value(VALID_ID_LONG))
                .andExpect(jsonPath("$.acctActiveStatus").value("Y"))
                .andExpect(jsonPath("$.acctCurrBal").value(1234.56));
    }

    /**
     * :purpose: A non-numeric, all-zero, or over-length id yields HTTP 400 with the verbatim edit
     *  message, and the service is never invoked.
     * :param badId: an account-id path value that violates the eleven-digit non-zero edit rule.
     */
    @ParameterizedTest(name = "invalid id [{0}] -> 400")
    @ValueSource(strings = {"abc", "00000000000", "123456789012"})
    @DisplayName("GET /accounts/{id} with an invalid id returns 400 with the verbatim edit message")
    void viewAccount_withInvalidId_returns400(String badId) throws Exception {
        mockMvc.perform(get("/accounts/{id}", badId).session(signedOnSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(MSG_INVALID_ACCT_ID));

        verifyNoInteractions(accountService);
    }

    /**
     * :purpose: A service {@link RecordNotFoundException} is translated to HTTP 404 with the
     *  verbatim not-found message.
     */
    @Test
    @DisplayName("GET /accounts/{id} maps a service RecordNotFoundException to 404")
    void viewAccount_whenServiceThrowsRecordNotFound_returns404() throws Exception {
        when(accountService.viewAccount(eq(VALID_ID_LONG), any(SessionContext.class)))
                .thenThrow(new RecordNotFoundException(MSG_ACCT_NOT_IN_XREF));

        mockMvc.perform(get("/accounts/{id}", VALID_ID).session(signedOnSession()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(MSG_ACCT_NOT_IN_XREF));
    }

    /**
     * :purpose: A valid update body yields HTTP 200; the parsed id and the deserialized request
     *  are passed through to the service exactly as received.
     */
    @Test
    @DisplayName("PUT /accounts/{id} with a valid body returns 200 and forwards the parsed id and request")
    void updateAccount_withValidBody_returns200AndInvokesService() throws Exception {
        when(accountService.updateAccount(eq(VALID_ID_LONG), any(AccountUpdateRequestDto.class), any(SessionContext.class)))
                .thenReturn(stubUpdateResponse());

        String json = objectMapper.writeValueAsString(validUpdateRequest());

        mockMvc.perform(put("/accounts/{id}", VALID_ID)
                        .session(signedOnSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        ArgumentCaptor<AccountUpdateRequestDto> captor = ArgumentCaptor.forClass(AccountUpdateRequestDto.class);
        verify(accountService).updateAccount(eq(VALID_ID_LONG), captor.capture(), any(SessionContext.class));
        assertThat(captor.getValue().getAcctActiveStatus()).isEqualTo("Y");
        assertThat(captor.getValue().getAcctGroupId()).isEqualTo("PREMGRP");
        assertThat(captor.getValue().getAcctCreditLimit()).isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    /**
     * :purpose: An unparseable request body is rejected with HTTP 400 before the controller body
     *  runs, so the service is never invoked. ``AccountUpdateRequestDto`` declares no Jakarta Bean
     *  Validation constraints, so a malformed body is the reachable invalid-body path.
     */
    @Test
    @DisplayName("PUT /accounts/{id} with an unparseable body returns 400 and never calls the service")
    void updateAccount_withUnparseableBody_returns400() throws Exception {
        String malformedJson = "{ not valid json }";

        mockMvc.perform(put("/accounts/{id}", VALID_ID)
                        .session(signedOnSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedJson))
                .andExpect(status().isBadRequest());

        verify(accountService, never()).updateAccount(any(), any(), any());
    }

    /**
     * :purpose: A service {@link OptimisticLockConflictException} is translated to HTTP 409 with the
     *  verbatim legacy conflict message.
     */
    @Test
    @DisplayName("PUT /accounts/{id} maps a service OptimisticLockConflictException to 409")
    void updateAccount_whenServiceThrowsOptimisticLockConflict_returns409() throws Exception {
        when(accountService.updateAccount(eq(VALID_ID_LONG), any(AccountUpdateRequestDto.class), any(SessionContext.class)))
                .thenThrow(new OptimisticLockConflictException());

        String json = objectMapper.writeValueAsString(validUpdateRequest());

        mockMvc.perform(put("/accounts/{id}", VALID_ID)
                        .session(signedOnSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(OptimisticLockConflictException.MESSAGE));
    }

    /**
     * :purpose: The controller bridges the externalized session context under the frozen
     *  ``"carddemoSessionContext"`` attribute, reusing (never replacing) the context that
     *  sign-on established.
     */
    @Test
    @DisplayName("GET /accounts/{id} reuses the session context established by sign-on")
    void viewAccount_bridgesSessionContextAttribute() throws Exception {
        when(accountService.viewAccount(eq(VALID_ID_LONG), any(SessionContext.class)))
                .thenReturn(stubViewResponse());

        MockHttpSession existingSession = new MockHttpSession();
        SessionContext existing = new SessionContext();
        existingSession.setAttribute(SESSION_ATTR, existing);

        mockMvc.perform(get("/accounts/{id}", VALID_ID).session(existingSession))
                .andExpect(status().isOk());

        ArgumentCaptor<SessionContext> captor = ArgumentCaptor.forClass(SessionContext.class);
        verify(accountService).viewAccount(eq(VALID_ID_LONG), captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(existingSession.getAttribute(SESSION_ATTR)).isSameAs(existing);
    }

    /**
     * :purpose: A session carrying no context is an invariant violation, not a
     *  recoverable state: the controller must refuse to serve the request rather than
     *  fabricate a blank identity. In production the ``SecurityFilterChain`` rejects
     *  such a request with 401 before it reaches the controller.
     */
    @Test
    @DisplayName("GET /accounts/{id} refuses to fabricate a context when the session carries none")
    void viewAccount_withNoSessionContext_doesNotFabricateContext() throws Exception {
        when(accountService.viewAccount(eq(VALID_ID_LONG), any(SessionContext.class)))
                .thenReturn(stubViewResponse());
        MockHttpSession freshSession = new MockHttpSession();

        mockMvc.perform(get("/accounts/{id}", VALID_ID).session(freshSession))
                .andExpect(status().isOk());

        // A session carrying no COMMAREA yields an EMPTY context: no user id and no user
        // type are invented, so nothing downstream can mistake the caller for a signed-on
        // principal. The filter chain refuses such a request before the controller.
        ArgumentCaptor<SessionContext> captor = ArgumentCaptor.forClass(SessionContext.class);
        verify(accountService).viewAccount(eq(VALID_ID_LONG), captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
        assertThat(captor.getValue().getUserType()).isNull();
    }
}
