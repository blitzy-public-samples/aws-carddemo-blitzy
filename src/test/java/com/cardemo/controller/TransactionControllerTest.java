/*
 * ============================================================================
 * TransactionControllerTest.java — MockMvc Controller Test for TransactionController
 * ============================================================================
 * AWS CardDemo Mainframe Application
 * Migrated from COBOL/CICS/VSAM to Java 25 + Spring Boot 3.5.x
 *
 * Source COBOL artifacts covered by this test:
 *   - COTRN00C.cbl  — Transaction List program (CICS transaction CT00)
 *     STARTBR/READNEXT loop fills 10 rows per page (WS-IDX 1..10)
 *   - COTRN01C.cbl  — Transaction View/Detail program (CICS transaction CT01)
 *     READ DATASET(WS-TRANSACT-FILE) INTO(TRAN-RECORD) RIDFLD(TRAN-ID)
 *   - COTRN02C.cbl  — Transaction Add program (CICS transaction CT02)
 *     Browse-last ID generation, field validation, WRITE DATASET
 *   - COTRN00.bms   — Transaction List screen map (10-row repeating display)
 *   - COTRN01.bms   — Transaction View screen map (single detail view)
 *   - COTRN02.bms   — Transaction Add screen map (data entry form)
 *   - CVTRA05Y.cpy  — Transaction master record layout (350 bytes)
 *     TRAN-AMT PIC S9(09)V99 COMP-3 → BigDecimal (CRITICAL)
 *
 * Test Coverage Mapping (3 COBOL programs → TransactionController):
 *   1. listTransactions_returnsOkWithPaginatedResults
 *      ← COTRN00C MAIN-PARA → PROCESS-PAGE-FORWARD (STARTBR/READNEXT, 10 per page)
 *   2. listTransactions_withTransactionIdFilter
 *      ← COTRN00C PROCESS-ENTER-KEY → TRNIDIN browse start position
 *   3. viewTransaction_returnsOkWithFullDetail
 *      ← COTRN01C PROCESS-ENTER-KEY → READ-TRANSACT-FILE (RESP=0)
 *   4. viewTransaction_notFound_returns404
 *      ← COTRN01C READ-TRANSACT-FILE → DFHRESP(NOTFND)
 *   5. addTransaction_returnsCreatedWithGeneratedId
 *      ← COTRN02C PROCESS-ENTER-KEY → ADD-TRANSACTION (browse-last ID gen)
 *   6. addTransaction_validationError_returns400
 *      ← COTRN02C VALIDATE-INPUT-KEY-FIELDS / VALIDATE-INPUT-DATA-FIELDS
 *   7. addTransaction_BigDecimalAmountValidation
 *      ← CVTRA05Y TRAN-AMT PIC S9(09)V99 → BigDecimal precision check
 *   8. addTransaction_accountNotFound_returns404
 *      ← COTRN02C VALIDATE-INPUT-KEY-FIELDS → READ-CXACAIX-FILE fails
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 * ============================================================================
 */
package com.cardemo.controller;

// Internal imports — controller under test and its dependencies
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.config.SecurityConfig;
import com.cardemo.entity.Transaction;
import com.cardemo.service.online.TransactionAddService;
import com.cardemo.service.online.TransactionAddService.TransactionAddRequest;
import com.cardemo.service.online.TransactionListService;
import com.cardemo.service.online.TransactionViewService;

// External imports — JUnit 5 Jupiter (provided by spring-boot-starter-test)
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// External imports — Spring Boot Test and Spring Framework Test
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// External imports — Jackson JSON (provided by spring-boot-starter-web)
import com.fasterxml.jackson.databind.ObjectMapper;

// Static imports — Mockito stubbing and verification
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

// Static imports — Spring MockMvc request builders and result matchers
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Java standard library
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spring {@code @WebMvcTest} slice test for {@link TransactionController} —
 * validates the three REST endpoints that translate CICS transactions CT00
 * (Transaction List, COTRN00C.cbl), CT01 (Transaction View, COTRN01C.cbl),
 * and CT02 (Transaction Add, COTRN02C.cbl) into stateless REST.
 *
 * <p>This test uses {@code @WebMvcTest(TransactionController.class)} to limit
 * the Spring application context to only the web layer (TransactionController),
 * plus the imported {@link SecurityConfig} for the security filter chain. All
 * service dependencies are mocked via {@code @MockitoBean}.</p>
 *
 * <h2>COBOL-to-Java Transaction Field Mapping (CVTRA05Y.cpy):</h2>
 * <table>
 *   <tr><th>COBOL Field</th><th>Java Field</th><th>Type</th></tr>
 *   <tr><td>TRAN-ID PIC X(16)</td><td>tranId</td><td>String</td></tr>
 *   <tr><td>TRAN-TYPE-CD PIC X(02)</td><td>typeCode</td><td>String</td></tr>
 *   <tr><td>TRAN-CAT-CD PIC 9(04)</td><td>categoryCode</td><td>Integer</td></tr>
 *   <tr><td>TRAN-SOURCE PIC X(10)</td><td>source</td><td>String</td></tr>
 *   <tr><td>TRAN-DESC PIC X(100)</td><td>description</td><td>String</td></tr>
 *   <tr><td>TRAN-AMT PIC S9(09)V99</td><td>amount</td><td>BigDecimal</td></tr>
 *   <tr><td>TRAN-MERCHANT-ID PIC 9(09)</td><td>merchantId</td><td>String</td></tr>
 *   <tr><td>TRAN-MERCHANT-NAME PIC X(50)</td><td>merchantName</td><td>String</td></tr>
 *   <tr><td>TRAN-MERCHANT-CITY PIC X(50)</td><td>merchantCity</td><td>String</td></tr>
 *   <tr><td>TRAN-MERCHANT-ZIP PIC X(10)</td><td>merchantZip</td><td>String</td></tr>
 *   <tr><td>TRAN-CARD-NUM PIC X(16)</td><td>cardNum</td><td>String</td></tr>
 *   <tr><td>TRAN-ORIG-TS PIC X(26)</td><td>origTimestamp</td><td>String (ISO-8601)</td></tr>
 *   <tr><td>TRAN-PROC-TS PIC X(26)</td><td>procTimestamp</td><td>String (ISO-8601)</td></tr>
 * </table>
 *
 * <h2>HTTP Status Code Mapping:</h2>
 * <ul>
 *   <li>200 OK — Successful list or view operations</li>
 *   <li>201 Created — Successful transaction creation (POST)</li>
 *   <li>400 Bad Request — Validation failure (invalid amount, date, missing fields)</li>
 *   <li>404 Not Found — Transaction/account/card not found (VSAM STATUS '23')</li>
 * </ul>
 *
 * @see TransactionController
 * @see TransactionListService
 * @see TransactionViewService
 * @see TransactionAddService
 * @see SecurityConfig
 */
@WebMvcTest(TransactionController.class)
@AutoConfigureMockMvc
@Import(SecurityConfig.class)
@WithMockUser
class TransactionControllerTest {

    /**
     * Auto-configured MockMvc instance for performing HTTP requests against
     * the TransactionController without starting a real HTTP server. Provided
     * by {@code @WebMvcTest} and {@code @AutoConfigureMockMvc}.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Jackson ObjectMapper for serializing transaction add request bodies into
     * JSON format. Auto-configured by Spring Boot's Jackson support via
     * {@code spring-boot-starter-web}. Critical for BigDecimal amount
     * serialization preserving exact decimal scale (TRAN-AMT PIC S9(09)V99).
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Mocked TransactionListService — replaces the real paginated list service
     * that translates COTRN00C.cbl CT00 transaction business logic
     * (STARTBR/READNEXT loop filling 10 rows per page matching WS-MAX-SCREEN-LINES).
     *
     * <p>Uses {@code @MockitoBean} (Spring Framework 6.2+) instead of the
     * deprecated {@code @MockBean} to avoid compilation errors under
     * {@code -Xlint:all -Werror}.</p>
     */
    @MockitoBean
    private TransactionListService transactionListService;

    /**
     * Mocked TransactionViewService — replaces the real transaction detail service
     * that translates COTRN01C.cbl CT01 transaction business logic
     * (READ DATASET(WS-TRANSACT-FILE) INTO(TRAN-RECORD) RIDFLD(TRAN-ID)).
     */
    @MockitoBean
    private TransactionViewService transactionViewService;

    /**
     * Mocked TransactionAddService — replaces the real transaction creation service
     * that translates COTRN02C.cbl CT02 transaction business logic including
     * browse-last ID generation, field validation, and WRITE DATASET operations.
     */
    @MockitoBean
    private TransactionAddService transactionAddService;

    // ========================= Helper Methods =========================

    /**
     * Creates a sample {@link Transaction} entity for test stubbing.
     * Populates all CVTRA05Y.cpy fields with realistic test data,
     * using the provided transaction ID and BigDecimal amount.
     *
     * @param tranId the transaction identifier (TRAN-ID PIC X(16))
     * @param amount the transaction amount (TRAN-AMT PIC S9(09)V99 → BigDecimal)
     * @return a fully populated Transaction entity
     */
    private Transaction createSampleTransaction(String tranId, BigDecimal amount) {
        return new Transaction(
                tranId,                                // TRAN-ID PIC X(16)
                "SA",                                  // TRAN-TYPE-CD PIC X(02) — Sale
                5411,                                  // TRAN-CAT-CD PIC 9(04) — Grocery
                "POS",                                 // TRAN-SOURCE PIC X(10)
                "Test purchase at grocery store",      // TRAN-DESC PIC X(100)
                amount,                                // TRAN-AMT PIC S9(09)V99 → BigDecimal
                "123456789",                           // TRAN-MERCHANT-ID PIC 9(09)
                "Test Grocery",                        // TRAN-MERCHANT-NAME PIC X(50)
                "Springfield",                         // TRAN-MERCHANT-CITY PIC X(50)
                "62701",                               // TRAN-MERCHANT-ZIP PIC X(10)
                "4111111111111111",                     // TRAN-CARD-NUM PIC X(16)
                "2025-09-15-10.30.00.000000",          // TRAN-ORIG-TS PIC X(26) ISO-8601
                "2025-09-15-10.30.00.000000"           // TRAN-PROC-TS PIC X(26) ISO-8601
        );
    }

    // ========================= Test Methods =========================

    /**
     * Test 1: GET /api/transactions returns paginated results (200 OK).
     *
     * <p>Maps to COTRN00C.cbl flow:</p>
     * <ol>
     *   <li>MAIN-PARA → PROCESS-PAGE-FORWARD</li>
     *   <li>STARTBR DATASET(WS-TRANSACT-FILE) RIDFLD(WS-TRAN-ID) GTEQ</li>
     *   <li>READNEXT loop with WS-IDX from 1 to 10 (WS-MAX-SCREEN-LINES)</li>
     *   <li>Fills TRNID01I..TRNID10I (10 repeating rows per page)</li>
     * </ol>
     *
     * <p>Verifies paginated response with 10 items per page (matching COBOL
     * WS-MAX-SCREEN-LINES from COTRN00.bms 10-row display), total elements,
     * and total pages.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("GET /api/transactions — returns 200 OK with paginated results (← COTRN00C STARTBR/READNEXT 10 per page)")
    void listTransactions_returnsOkWithPaginatedResults() throws Exception {
        // Arrange: Create 10 transactions matching COBOL 10-row display
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String tranId = String.format("TRN%09d", i);
            transactions.add(createSampleTransaction(tranId, new BigDecimal("100.00")));
        }
        Page<Transaction> page = new PageImpl<>(transactions, PageRequest.of(0, 10), 50);

        given(transactionListService.listTransactions(any(), eq(0))).willReturn(page);

        // Act & Assert
        mockMvc.perform(get("/api/transactions")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(10))
                .andExpect(jsonPath("$.totalElements").value(50))
                .andExpect(jsonPath("$.totalPages").value(5))
                .andExpect(jsonPath("$.content[0].tranId").value("TRN000000001"));

        // Verify: Service called exactly once with null transactionId and page 0
        verify(transactionListService).listTransactions(null, 0);
    }

    /**
     * Test 2: GET /api/transactions with transactionId filter returns filtered results.
     *
     * <p>Maps to COTRN00C.cbl flow:</p>
     * <ol>
     *   <li>PROCESS-ENTER-KEY → TRNIDIN is read from BMS map</li>
     *   <li>TRNIDIN used as STARTBR RIDFLD browse start position</li>
     *   <li>Results filtered from the specified transaction ID onward</li>
     * </ol>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("GET /api/transactions?transactionId=TRN000000001 — returns filtered results (← COTRN00C TRNIDIN browse start)")
    void listTransactions_withTransactionIdFilter() throws Exception {
        // Arrange: Create filtered result page
        Transaction txn = createSampleTransaction("TRN000000001", new BigDecimal("250.75"));
        Page<Transaction> page = new PageImpl<>(List.of(txn), PageRequest.of(0, 10), 1);

        given(transactionListService.listTransactions(eq("TRN000000001"), eq(0)))
                .willReturn(page);

        // Act & Assert
        mockMvc.perform(get("/api/transactions")
                        .param("transactionId", "TRN000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].tranId").value("TRN000000001"));

        // Verify: Service called with correct transactionId filter
        verify(transactionListService).listTransactions("TRN000000001", 0);
    }

    /**
     * Test 3: GET /api/transactions/{id} returns full transaction detail (200 OK).
     *
     * <p>Maps to COTRN01C.cbl flow:</p>
     * <ol>
     *   <li>MAIN-PARA → PROCESS-ENTER-KEY</li>
     *   <li>READ-TRANSACT-FILE: EXEC CICS READ DATASET(WS-TRANSACT-FILE)
     *       INTO(TRAN-RECORD) RIDFLD(TRAN-ID) (RESP=0 — success)</li>
     *   <li>All CVTRA05Y.cpy fields populated and displayed on COTRN01.bms map</li>
     * </ol>
     *
     * <p>Verifies all 13 CVTRA05Y.cpy fields are present in JSON response,
     * especially BigDecimal amount (TRAN-AMT PIC S9(09)V99 COMP-3).</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("GET /api/transactions/{id} — returns 200 OK with full detail (← COTRN01C READ-TRANSACT-FILE RESP=0)")
    void viewTransaction_returnsOkWithFullDetail() throws Exception {
        // Arrange: Create transaction with precise BigDecimal amount
        BigDecimal amount = new BigDecimal("12345.67");
        Transaction txn = createSampleTransaction("TRN000000001", amount);

        given(transactionViewService.viewTransaction("TRN000000001")).willReturn(txn);

        // Act & Assert: Verify all CVTRA05Y.cpy fields are in JSON response
        mockMvc.perform(get("/api/transactions/TRN000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tranId").value("TRN000000001"))
                .andExpect(jsonPath("$.typeCode").value("SA"))
                .andExpect(jsonPath("$.categoryCode").value(5411))
                .andExpect(jsonPath("$.source").value("POS"))
                .andExpect(jsonPath("$.description").value("Test purchase at grocery store"))
                .andExpect(jsonPath("$.amount").value(12345.67))
                .andExpect(jsonPath("$.merchantId").value("123456789"))
                .andExpect(jsonPath("$.merchantName").value("Test Grocery"))
                .andExpect(jsonPath("$.merchantCity").value("Springfield"))
                .andExpect(jsonPath("$.merchantZip").value("62701"))
                .andExpect(jsonPath("$.cardNum").value("4111111111111111"))
                .andExpect(jsonPath("$.origTimestamp").value("2025-09-15-10.30.00.000000"))
                .andExpect(jsonPath("$.procTimestamp").value("2025-09-15-10.30.00.000000"));

        // Verify: Service called with correct transaction ID
        verify(transactionViewService).viewTransaction("TRN000000001");
    }

    /**
     * Test 4: GET /api/transactions/{id} returns 404 when transaction not found.
     *
     * <p>Maps to COTRN01C.cbl flow:</p>
     * <ol>
     *   <li>READ-TRANSACT-FILE → DFHRESP(NOTFND) (RESP=13)</li>
     *   <li>WS-MESSAGE = "Transaction ID NOT found..."</li>
     *   <li>Maps to VSAM file status '23' → RecordNotFoundException</li>
     * </ol>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("GET /api/transactions/{id} — returns 404 when not found (← COTRN01C DFHRESP(NOTFND))")
    void viewTransaction_notFound_returns404() throws Exception {
        // Arrange: Service throws RecordNotFoundException (VSAM STATUS '23')
        given(transactionViewService.viewTransaction("NONEXISTENT0001"))
                .willThrow(new RecordNotFoundException("Transaction ID NOT found..."));

        // Act & Assert
        mockMvc.perform(get("/api/transactions/NONEXISTENT0001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Transaction ID NOT found..."));

        // Verify: Service called with the non-existent ID
        verify(transactionViewService).viewTransaction("NONEXISTENT0001");
    }

    /**
     * Test 5: POST /api/transactions returns 201 Created with generated ID.
     *
     * <p>Maps to COTRN02C.cbl flow:</p>
     * <ol>
     *   <li>MAIN-PARA → PROCESS-ENTER-KEY</li>
     *   <li>VALIDATE-INPUT-KEY-FIELDS → validates card/account references</li>
     *   <li>VALIDATE-INPUT-DATA-FIELDS → validates amount, date, type/category</li>
     *   <li>ADD-TRANSACTION → browse-last ID generation (STARTBR → READPREV →
     *       ENDBR on last TRAN-ID, then increment) per AAP 0.7.4</li>
     *   <li>WRITE DATASET(WS-TRANSACT-FILE) FROM(TRAN-RECORD)</li>
     * </ol>
     *
     * <p>CRITICAL: Amount field must be BigDecimal ("125.50" → BigDecimal, NOT
     * 125.5 or floating point). Maps to TRAN-AMT PIC S9(09)V99 from CVTRA05Y.cpy
     * with RoundingMode.HALF_UP per AAP 0.7.4.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("POST /api/transactions — returns 201 Created with browse-last generated ID (← COTRN02C ADD-TRANSACTION)")
    void addTransaction_returnsCreatedWithGeneratedId() throws Exception {
        // Arrange: Mock service returns created transaction with system-generated ID
        Transaction created = createSampleTransaction("TRN000000051", new BigDecimal("125.50"));

        given(transactionAddService.addTransaction(any(TransactionAddRequest.class)))
                .willReturn(created);

        // Build request body matching TransactionAddRequest record fields
        Map<String, String> requestBody = Map.ofEntries(
                Map.entry("accountId", "00000000001"),
                Map.entry("cardNum", "4111111111111111"),
                Map.entry("typeCode", "SA"),
                Map.entry("categoryCode", "5411"),
                Map.entry("source", "POS"),
                Map.entry("description", "Test purchase at grocery store"),
                Map.entry("amount", "125.50"),
                Map.entry("merchantId", "123456789"),
                Map.entry("merchantName", "Test Grocery"),
                Map.entry("merchantCity", "Springfield"),
                Map.entry("merchantZip", "62701"),
                Map.entry("origDate", "2025-09-15")
        );

        // Act & Assert
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tranId").value("TRN000000051"))
                .andExpect(jsonPath("$.amount").value(125.50));

        // Verify: Service called once with request data
        verify(transactionAddService).addTransaction(any(TransactionAddRequest.class));
    }

    /**
     * Test 6: POST /api/transactions returns 400 Bad Request on validation error.
     *
     * <p>Maps to COTRN02C.cbl flow:</p>
     * <ol>
     *   <li>VALIDATE-INPUT-KEY-FIELDS / VALIDATE-INPUT-DATA-FIELDS</li>
     *   <li>Validation failures: invalid amount range (-99999999.99 to 99999999.99),
     *       date format errors, missing required fields, invalid type/category codes</li>
     *   <li>WS-MESSAGE populated with validation error text</li>
     * </ol>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("POST /api/transactions — returns 400 on validation error (← COTRN02C VALIDATE-INPUT-DATA-FIELDS)")
    void addTransaction_validationError_returns400() throws Exception {
        // Arrange: Service throws ValidationException for out-of-range amount
        given(transactionAddService.addTransaction(any(TransactionAddRequest.class)))
                .willThrow(new ValidationException(
                        "Amount out of range -99999999.99 to 99999999.99"));

        // Build minimal invalid request body
        Map<String, String> requestBody = Map.of(
                "accountId", "00000000001",
                "cardNum", "4111111111111111",
                "amount", "999999999.99"
        );

        // Act & Assert
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Amount out of range -99999999.99 to 99999999.99"));
    }

    /**
     * Test 7: POST /api/transactions preserves BigDecimal amount precision.
     *
     * <p>CRITICAL per AAP 0.7.2 and 0.7.4: "No floating-point for decimal fields.
     * All PIC S9(n)V99 COMP-3 fields become BigDecimal with exact scale matching
     * the COBOL decimal places." This test verifies that the maximum allowable
     * amount (99999999.99) is serialized with exact BigDecimal precision in the
     * JSON response, with no floating-point drift.</p>
     *
     * <p>Amount range per COTRN02.bms hint: -99999999.99 to 99999999.99
     * (TRAN-AMT PIC S9(09)V99 scale=2, RoundingMode.HALF_UP per AAP 0.7.4).</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("POST /api/transactions — BigDecimal amount precision preserved (99999999.99, ← CVTRA05Y TRAN-AMT PIC S9(09)V99)")
    void addTransaction_BigDecimalAmountValidation() throws Exception {
        // Arrange: Create transaction with maximum BigDecimal amount
        BigDecimal maxAmount = new BigDecimal("99999999.99");
        Transaction created = createSampleTransaction("TRN000000052", maxAmount);

        given(transactionAddService.addTransaction(any(TransactionAddRequest.class)))
                .willReturn(created);

        // Build request body with maximum amount value
        Map<String, String> requestBody = Map.ofEntries(
                Map.entry("accountId", "00000000001"),
                Map.entry("cardNum", "4111111111111111"),
                Map.entry("typeCode", "SA"),
                Map.entry("categoryCode", "5411"),
                Map.entry("source", "POS"),
                Map.entry("description", "Max amount transaction"),
                Map.entry("amount", "99999999.99"),
                Map.entry("merchantId", "123456789"),
                Map.entry("merchantName", "Test Merchant"),
                Map.entry("merchantCity", "TestCity"),
                Map.entry("merchantZip", "12345"),
                Map.entry("origDate", "2025-09-15")
        );

        // Act & Assert: Verify BigDecimal precision is preserved (no floating-point drift)
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tranId").value("TRN000000052"))
                .andExpect(jsonPath("$.amount").value(99999999.99));

        // Verify: Service called once with request data
        verify(transactionAddService).addTransaction(any(TransactionAddRequest.class));
    }

    /**
     * Test 8: POST /api/transactions returns 404 when account not found.
     *
     * <p>Maps to COTRN02C.cbl flow:</p>
     * <ol>
     *   <li>VALIDATE-INPUT-KEY-FIELDS → READ-CXACAIX-FILE</li>
     *   <li>Cross-reference lookup fails (RESP=13 NOTFND on CARDXREF AIX)</li>
     *   <li>Or READ-CCXREF-FILE fails (card/account not found)</li>
     *   <li>Maps to RecordNotFoundException (VSAM STATUS '23')</li>
     * </ol>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("POST /api/transactions — returns 404 when account not found (← COTRN02C READ-CXACAIX-FILE NOTFND)")
    void addTransaction_accountNotFound_returns404() throws Exception {
        // Arrange: Service throws RecordNotFoundException for non-existent account
        given(transactionAddService.addTransaction(any(TransactionAddRequest.class)))
                .willThrow(new RecordNotFoundException("Account", "99999999999"));

        // Build request body referencing non-existent account
        Map<String, String> requestBody = Map.of(
                "accountId", "99999999999",
                "cardNum", "4111111111111111",
                "typeCode", "SA",
                "amount", "100.00"
        );

        // Act & Assert
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }
}
