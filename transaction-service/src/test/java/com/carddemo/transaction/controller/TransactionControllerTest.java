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
package com.carddemo.transaction.controller;

import org.springframework.batch.core.repository.JobRepository;
import org.springframework.transaction.PlatformTransactionManager;
import static org.mockito.Mockito.never;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.dto.TransactionAddRequestDto;
import com.carddemo.common.dto.TransactionAddResponseDto;
import com.carddemo.common.dto.TransactionListItemDto;
import com.carddemo.common.dto.TransactionListRequestDto;
import com.carddemo.common.dto.TransactionListResponseDto;
import com.carddemo.common.dto.TransactionViewResponseDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.RecordNotFoundException;
import com.carddemo.transaction.repository.AccountRepository;
import com.carddemo.transaction.repository.CardXrefRepository;
import com.carddemo.transaction.repository.DailyTransactionRepository;
import com.carddemo.transaction.repository.TranCatBalRepository;
import com.carddemo.transaction.repository.TranCatgRepository;
import com.carddemo.transaction.repository.TranTypeRepository;
import com.carddemo.transaction.repository.TransactionRepository;
import com.carddemo.transaction.service.TransactionService;

import jakarta.persistence.EntityManagerFactory;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

/**
 * Web-slice tests for :class:`TransactionController`.
 *
 * :purpose: Verify the three REST endpoints that replace the CICS transactions
 *     ``CT00`` / ``CT01`` / ``CT02`` (``COTRN00C`` / ``COTRN01C`` / ``COTRN02C``):
 *     ``GET /transactions`` (query-parameter binding of the list request),
 *     ``GET /transactions/{id}`` and ``POST /transactions`` (HTTP 201 on a
 *     successful add). Also verifies that the controller holds no business logic —
 *     it passes the raw inputs through, bridges the externalized
 *     :class:`SessionContext` to and from the servlet session, and lets the shared
 *     :class:`GlobalExceptionHandler` translate the service's domain exceptions
 *     into 400 / 404 responses.
 * :output: JUnit 5 / AssertJ / MockMvc assertions with the service mocked; no
 *     database, no Redis, and no security on the transaction-service classpath.
 */
@WebMvcTest(TransactionController.class)
@Import(GlobalExceptionHandler.class)
class TransactionControllerTest {

    /** :purpose: Session attribute the controller bridges the COMMAREA state through. */
    private static final String SESSION_CONTEXT_ATTR = "carddemoSessionContext";

    /** :purpose: A 16-character zero-padded transaction id. */
    private static final String TRAN_ID = "0000000000000042";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** :purpose: Mocked online transaction service (the controller's sole collaborator). */
    @MockitoBean
    private TransactionService transactionService;

    // Defensive: the transaction-service main class declares an explicit
    // @EnableJpaRepositories, so the web slice eagerly registers JPA infrastructure the
    // slice itself does not auto-configure. Overriding every repository as a mock replaces
    // its factory bean, and the mock entityManagerFactory below satisfies the
    // shared-EntityManager and metamodel-mapping-context singletons, letting the context
    // start without a database. The controller under test never uses any of them.

    /** :purpose: Mock standing in for the transaction master repository bean. */
    @MockitoBean
    private TransactionRepository transactionRepository;

    /** :purpose: Mock standing in for the card cross-reference repository bean. */
    @MockitoBean
    private CardXrefRepository cardXrefRepository;

    /** :purpose: Mock standing in for the account master repository bean. */
    @MockitoBean
    private AccountRepository accountRepository;

    /** :purpose: Mock standing in for the daily-transaction feed repository bean. */
    @MockitoBean
    private DailyTransactionRepository dailyTransactionRepository;

    /** :purpose: Mock standing in for the transaction-category-balance repository bean. */
    @MockitoBean
    private TranCatBalRepository tranCatBalRepository;

    /** :purpose: Mock standing in for the transaction-category reference repository bean. */
    @MockitoBean
    private TranCatgRepository tranCatgRepository;

    /** :purpose: Mock standing in for the transaction-type reference repository bean. */
    @MockitoBean
    private TranTypeRepository tranTypeRepository;

    /**
     * :purpose: Mock ``entityManagerFactory`` bean satisfying the shared-EntityManager and
     *  metamodel-mapping-context singletons the explicit ``@EnableJpaRepositories``
     *  registers eagerly. ``RETURNS_MOCKS`` makes ``getMetamodel()`` non-null with an empty
     *  managed-type set, so the mapping context initializes without a real persistence
     *  unit. Never exercised by the controller under test.
     */
    @MockitoBean(name = "entityManagerFactory", answers = Answers.RETURNS_MOCKS)
    private EntityManagerFactory entityManagerFactory;

    /**
     * :purpose: Mock ``jobRepository`` bean standing in for the JDBC-backed one the
     *  application imports for the transaction-posting job. The real factory reaches for a
     *  ``DataSource`` and provisions the ``BATCH_*`` schema, which a web slice has no
     *  business doing; overriding the bean keeps that factory method out of the slice.
     *  Never exercised by the controller under test.
     */
    @MockitoBean
    private JobRepository jobRepository;

    /**
     * :purpose: Mock ``PlatformTransactionManager`` bean the inherited batch infrastructure
     *  resolves BY NAME while assembling its ``JobOperator``. Never exercised by the
     *  controller under test.
     */
    @MockitoBean(name = "transactionManager")
    private PlatformTransactionManager transactionManager;

    /**
     * Builds a list response with a single row.
     *
     * :output: a populated list response the mocked service returns.
     */
    private static TransactionListResponseDto listResponse() {
        TransactionListItemDto row = new TransactionListItemDto();
        row.setTranId(TRAN_ID);
        row.setTranDate("06/15/24");
        row.setTranDesc("Point of sale purchase");
        row.setTranAmt(new BigDecimal("250.75"));
        TransactionListResponseDto response = new TransactionListResponseDto();
        response.setTransactions(List.of(row));
        response.setPageNumber(1);
        response.setTranIdFirst(TRAN_ID);
        response.setTranIdLast(TRAN_ID);
        response.setNextPage(true);
        return response;
    }

    /**
     * Builds a view response for the located transaction.
     *
     * :output: a populated view response the mocked service returns.
     */
    private static TransactionViewResponseDto viewResponse() {
        TransactionViewResponseDto response = new TransactionViewResponseDto();
        response.setTranId(TRAN_ID);
        response.setTranCardNum("4111111111111111");
        response.setTranTypeCd("01");
        response.setTranCatCd(5001);
        response.setTranSource("POS TERM");
        response.setTranAmt(new BigDecimal("250.75"));
        response.setTranDesc("Point of sale purchase");
        response.setTranOrigTs("2024-06-15-13.45.30.123456");
        response.setTranProcTs("2024-06-16-01.00.00.000000");
        response.setTranMerchantId(123456789L);
        response.setTranMerchantName("Mercado Central");
        response.setTranMerchantCity("Springfield");
        response.setTranMerchantZip("22770");
        return response;
    }

    /**
     * Builds a fully valid add request body.
     *
     * :output: an add request that passes bean validation.
     */
    private static TransactionAddRequestDto addRequest() {
        TransactionAddRequestDto request = new TransactionAddRequestDto();
        request.setAcctId("12345678901");
        request.setTranTypeCd("01");
        request.setTranCatCd(5001);
        request.setTranSource("POS TERM");
        request.setTranDesc("Point of sale purchase");
        request.setTranAmt(new BigDecimal("250.75"));
        request.setTranOrigTs("2024-06-15");
        request.setTranProcTs("2024-06-16");
        request.setTranMerchantId(123456789L);
        request.setTranMerchantName("Mercado Central");
        request.setTranMerchantCity("Springfield");
        request.setTranMerchantZip("22770");
        request.setConfirm("Y");
        return request;
    }

    @Test
    @DisplayName("GET /transactions returns 200 and the service's page verbatim")
    void listReturns200AndThePage() throws Exception {
        when(transactionService.listTransactions(any(TransactionListRequestDto.class),
                any(SessionContext.class))).thenReturn(listResponse());

        mockMvc.perform(get("/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.transactions[0].tranId").value(TRAN_ID))
                .andExpect(jsonPath("$.transactions[0].tranDate").value("06/15/24"))
                .andExpect(jsonPath("$.transactions[0].tranAmt").value(250.75))
                .andExpect(jsonPath("$.pageNumber").value(1))
                .andExpect(jsonPath("$.nextPage").value(true));
    }

    @Test
    @DisplayName("GET /transactions binds every query parameter onto the list request")
    void listBindsQueryParameters() throws Exception {
        when(transactionService.listTransactions(any(TransactionListRequestDto.class),
                any(SessionContext.class))).thenReturn(listResponse());

        mockMvc.perform(get("/transactions")
                        .param("action", "PF8")
                        .param("tranIdFilter", "42")
                        .param("pageNumber", "3")
                        .param("tranIdFirst", "0000000000000021")
                        .param("tranIdLast", "0000000000000030")
                        .param("nextPage", "true")
                        .param("selectionFlag", "S")
                        .param("selectedTranId", TRAN_ID))
                .andExpect(status().isOk());

        ArgumentCaptor<TransactionListRequestDto> captor =
                ArgumentCaptor.forClass(TransactionListRequestDto.class);
        verify(transactionService).listTransactions(captor.capture(), any(SessionContext.class));
        TransactionListRequestDto bound = captor.getValue();
        assertThat(bound.getAction()).isEqualTo("PF8");
        assertThat(bound.getTranIdFilter()).isEqualTo("42");
        assertThat(bound.getPageNumber()).isEqualTo(3);
        assertThat(bound.getTranIdFirst()).isEqualTo("0000000000000021");
        assertThat(bound.getTranIdLast()).isEqualTo("0000000000000030");
        assertThat(bound.isNextPage()).isTrue();
        assertThat(bound.getSelectionFlag()).isEqualTo("S");
        assertThat(bound.getSelectedTranId()).isEqualTo(TRAN_ID);
    }

    @Test
    @DisplayName("GET /transactions/{id} returns 200 with every mapped field")
    void viewReturns200WithEveryField() throws Exception {
        when(transactionService.viewTransaction(eq(TRAN_ID), any(SessionContext.class)))
                .thenReturn(viewResponse());

        mockMvc.perform(get("/transactions/{id}", TRAN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tranId").value(TRAN_ID))
                .andExpect(jsonPath("$.tranCardNum").value("4111111111111111"))
                .andExpect(jsonPath("$.tranTypeCd").value("01"))
                .andExpect(jsonPath("$.tranCatCd").value(5001))
                .andExpect(jsonPath("$.tranAmt").value(250.75))
                .andExpect(jsonPath("$.tranOrigTs").value("2024-06-15-13.45.30.123456"))
                .andExpect(jsonPath("$.tranMerchantId").value(123456789));
    }

    @Test
    @DisplayName("GET /transactions/{id} passes the raw path variable through without local validation")
    void viewPassesTheRawIdThrough() throws Exception {
        when(transactionService.viewTransaction(eq("42"), any(SessionContext.class)))
                .thenReturn(viewResponse());

        mockMvc.perform(get("/transactions/{id}", "42")).andExpect(status().isOk());

        // The zero-padding and the blank-id guard are service-owned business logic.
        verify(transactionService).viewTransaction(eq("42"), any(SessionContext.class));
    }

    @Test
    @DisplayName("GET /transactions/{id} maps RecordNotFoundException to 404 with the verbatim message")
    void viewMapsNotFoundTo404() throws Exception {
        when(transactionService.viewTransaction(any(), any(SessionContext.class)))
                .thenThrow(new RecordNotFoundException("Transaction ID NOT found..."));

        mockMvc.perform(get("/transactions/{id}", "999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Transaction ID NOT found..."));
    }

    @Test
    @DisplayName("GET /transactions/{id} maps CardDemoException to 400 with the verbatim message")
    void viewMapsValidationFailureTo400() throws Exception {
        when(transactionService.viewTransaction(any(), any(SessionContext.class)))
                .thenThrow(new CardDemoException("Tran ID can NOT be empty..."));

        mockMvc.perform(get("/transactions/{id}", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Tran ID can NOT be empty..."));
    }

    @Test
    @DisplayName("POST /transactions returns 201 with the generated id and confirmation message")
    void addReturns201WithGeneratedId() throws Exception {
        when(transactionService.addTransaction(any(TransactionAddRequestDto.class),
                any(SessionContext.class)))
                .thenReturn(new TransactionAddResponseDto(TRAN_ID,
                        "Transaction added successfully.  Your Tran ID is " + TRAN_ID + "."));

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tranId").value(TRAN_ID))
                .andExpect(jsonPath("$.message")
                        .value("Transaction added successfully.  Your Tran ID is " + TRAN_ID + "."));
    }

    @Test
    @DisplayName("POST /transactions deserializes the body and hands it to the service unchanged")
    void addPassesTheDeserializedBodyThrough() throws Exception {
        when(transactionService.addTransaction(any(TransactionAddRequestDto.class),
                any(SessionContext.class)))
                .thenReturn(new TransactionAddResponseDto(TRAN_ID, "ok"));

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addRequest())))
                .andExpect(status().isCreated());

        ArgumentCaptor<TransactionAddRequestDto> captor =
                ArgumentCaptor.forClass(TransactionAddRequestDto.class);
        verify(transactionService).addTransaction(captor.capture(), any(SessionContext.class));
        TransactionAddRequestDto bound = captor.getValue();
        assertThat(bound.getAcctId()).isEqualTo("12345678901");
        assertThat(bound.getTranTypeCd()).isEqualTo("01");
        assertThat(bound.getTranCatCd()).isEqualTo(5001);
        assertThat(bound.getTranAmt()).isEqualByComparingTo(new BigDecimal("250.75"));
        assertThat(bound.getConfirm()).isEqualTo("Y");
    }

    @Test
    @DisplayName("POST /transactions maps a service validation failure to 400 with the verbatim message")
    void addMapsValidationFailureTo400() throws Exception {
        when(transactionService.addTransaction(any(TransactionAddRequestDto.class),
                any(SessionContext.class)))
                .thenThrow(new CardDemoException("Confirm to add this transaction..."));

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Confirm to add this transaction..."));
    }

    @Test
    @DisplayName("POST /transactions maps a missing cross-reference to 404")
    void addMapsMissingCrossReferenceTo404() throws Exception {
        when(transactionService.addTransaction(any(TransactionAddRequestDto.class),
                any(SessionContext.class)))
                .thenThrow(new RecordNotFoundException("Account ID NOT found..."));

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Account ID NOT found..."));
    }

    @Test
    @DisplayName("POST /transactions rejects a bean-validation violation with 400 before reaching the service")
    void addRejectsBeanValidationViolation() throws Exception {
        TransactionAddRequestDto request = addRequest();
        request.setTranTypeCd("TOO-LONG");

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(transactionService);
    }

    @Test
    @DisplayName("an existing session context is handed to the service and re-stored afterwards")
    void sessionContextRoundTripsThroughTheServletSession() throws Exception {
        SessionContext existing = new SessionContext();
        existing.setUserId("ADMIN001");
        existing.setAcctId(12345678901L);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SESSION_CONTEXT_ATTR, existing);
        when(transactionService.viewTransaction(eq(TRAN_ID), any(SessionContext.class)))
                .thenReturn(viewResponse());

        mockMvc.perform(get("/transactions/{id}", TRAN_ID).session(session))
                .andExpect(status().isOk());

        ArgumentCaptor<SessionContext> captor = ArgumentCaptor.forClass(SessionContext.class);
        verify(transactionService).viewTransaction(eq(TRAN_ID), captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(session.getAttribute(SESSION_CONTEXT_ATTR)).isSameAs(existing);
    }

    /**
     * :purpose: A session carrying no context is an invariant violation, not a recoverable
     *  state: the controller must refuse to serve the request rather than fabricate a blank
     *  identity whose user type would then gate nothing. In production the
     *  ``SecurityFilterChain`` rejects such a request with 401 before it reaches the
     *  controller.
     * :output: the request is refused, the service is never invoked, and no blank context is
     *  stored on the session.
     */
    @Test
    @DisplayName("a request without a session context is refused rather than given a blank one")
    void requestWithoutSessionContextIsRefused() throws Exception {
        MockHttpSession session = new MockHttpSession();

        // This slice imports the shared GlobalExceptionHandler, so the invariant violation
        // is reported as the generic error envelope rather than propagating; either way the
        // request is refused and the transaction is never read.
        mockMvc.perform(get("/transactions/{id}", TRAN_ID).session(session))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));

        verify(transactionService, never()).viewTransaction(any(), any());
        assertThat(session.getAttribute(SESSION_CONTEXT_ATTR)).isNull();
    }
}
