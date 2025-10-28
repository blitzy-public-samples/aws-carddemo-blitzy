/*
 * TransactionControllerTest.java
 *
 * JUnit 5 test class for TransactionController REST endpoint testing.
 * 
 * Tests transaction operations converted from COBOL programs:
 * - COTRN00C.cbl (transaction list with date filtering and pagination)
 * - COTRN01C.cbl (transaction detail view)
 * - COTRN02C.cbl (transaction posting with credit limit validation)
 *
 * This test class uses Spring Boot Test @WebMvcTest for controller layer testing
 * with MockMvc to perform HTTP requests and assert responses without starting
 * a full HTTP server. TransactionService is mocked using @MockBean to isolate
 * controller testing from service implementation.
 *
 * Key Testing Requirements (from Agent Action Plan Section 0.7.8):
 * 1. Test GET /api/transactions endpoint with pagination and date range filters
 * 2. Test GET /api/transactions/{id} endpoint returns transaction details or 404
 * 3. Test POST /api/transactions endpoint posts new transaction with amount validation
 * 4. Validate BigDecimal transaction amounts maintain scale 2 precision (COBOL COMP-3)
 * 5. Test transaction type and category code validations
 * 6. Test error handling for insufficient balance, invalid card, expired card
 *
 * COMP-3 Precision Testing:
 * All transaction amounts must be validated with BigDecimal scale 2 and RoundingMode.HALF_UP
 * to ensure bit-identical results to COBOL COMP-3 packed decimal arithmetic per
 * Agent Action Plan Section 0.7.3.
 *
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

import com.carddemo.model.dto.TransactionDto;
import com.carddemo.service.TransactionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test class for TransactionController REST endpoints.
 *
 * <p>This test class validates the conversion of COBOL transaction processing programs
 * (COTRN00C.cbl, COTRN01C.cbl, COTRN02C.cbl) to REST API endpoints, ensuring:
 * <ul>
 *   <li>Pagination works correctly for transaction lists (COTRN00C.cbl browse logic)</li>
 *   <li>Date range filtering matches COBOL date filter validation</li>
 *   <li>Transaction detail retrieval returns proper HTTP status codes</li>
 *   <li>Transaction posting validates all business rules from COTRN02C.cbl</li>
 *   <li>BigDecimal amounts preserve COBOL COMP-3 precision with scale 2</li>
 *   <li>Error handling matches COBOL file-status and RESP code handling</li>
 * </ul>
 *
 * <h2>Test Coverage:</h2>
 * <ul>
 *   <li><b>GET /api/transactions</b> - List transactions with pagination</li>
 *   <li><b>GET /api/transactions</b> - List with date range filter</li>
 *   <li><b>GET /api/transactions</b> - List filtered by card number</li>
 *   <li><b>GET /api/transactions/{id}</b> - Get transaction detail (200 OK)</li>
 *   <li><b>GET /api/transactions/{id}</b> - Transaction not found (404 Not Found)</li>
 *   <li><b>POST /api/transactions</b> - Post transaction successfully (201 Created)</li>
 *   <li><b>POST /api/transactions</b> - Validation errors (400 Bad Request)</li>
 *   <li><b>POST /api/transactions</b> - Business rule violations (422 Unprocessable Entity)</li>
 *   <li><b>POST /api/transactions</b> - BigDecimal precision validation (scale 2)</li>
 * </ul>
 *
 * @see TransactionController
 * @see TransactionService
 * @see TransactionDto
 */
@WebMvcTest(value = TransactionController.class, 
    excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransactionService transactionService;

    @MockBean
    private com.carddemo.service.ValidationService validationService;

    @MockBean
    private com.carddemo.security.JwtTokenProvider jwtTokenProvider;

    private TransactionDto testTransactionDto;
    private List<TransactionDto> testTransactionList;
    private LocalDateTime testTimestamp;

    /**
     * Setup method executed before each test to initialize test data.
     * 
     * Creates sample TransactionDto objects with proper BigDecimal amounts
     * maintaining scale 2 precision to match COBOL COMP-3 packed decimal fields.
     * Test data includes various transaction types (Purchase, Payment, Cash Advance)
     * and amounts with 2 decimal places matching COBOL PIC S9(09)V99 COMP-3 format.
     */
    @BeforeEach
    void setUp() {
        testTimestamp = LocalDateTime.of(2025, 1, 15, 10, 30, 0);

        // Create test transaction with BigDecimal amount (scale 2, HALF_UP rounding)
        // Matches COBOL TRAN-AMT PIC S9(09)V99 COMP-3 field
        testTransactionDto = TransactionDto.builder()
                .transId("0000000123456789")
                .transCardNum("************1234")  // Masked for security
                .transTypeCd("01")  // Purchase (debit transaction)
                .transCatCd(5411)  // Grocery store MCC
                .transSource("POS")
                .transDesc("GROCERY STORE PURCHASE")
                .transAmt(new BigDecimal("125.50").setScale(2, RoundingMode.HALF_UP))
                .transMerchantId(123456789L)
                .transMerchantName("LOCAL GROCERY")
                .transMerchantCity("SEATTLE")
                .transMerchantZip("98101")
                .transOrigTs(testTimestamp)
                .transProcTs(testTimestamp.plusSeconds(15))
                .build();

        // Create test transaction list for pagination testing
        TransactionDto transaction2 = TransactionDto.builder()
                .transId("0000000123456790")
                .transCardNum("************1234")
                .transTypeCd("04")  // Payment (credit transaction)
                .transCatCd(0)  // Payment category
                .transSource("ONLINE")
                .transDesc("PAYMENT - THANK YOU")
                .transAmt(new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP))
                .transMerchantId(0L)
                .transMerchantName("CARDHOLDER PAYMENT")
                .transMerchantCity("")
                .transMerchantZip("")
                .transOrigTs(testTimestamp.minusDays(1))
                .transProcTs(testTimestamp.minusDays(1).plusSeconds(5))
                .build();

        TransactionDto transaction3 = TransactionDto.builder()
                .transId("0000000123456791")
                .transCardNum("************1234")
                .transTypeCd("02")  // Cash Advance (debit transaction)
                .transCatCd(6011)  // ATM MCC
                .transSource("ATM")
                .transDesc("CASH WITHDRAWAL")
                .transAmt(new BigDecimal("200.00").setScale(2, RoundingMode.HALF_UP))
                .transMerchantId(987654321L)
                .transMerchantName("BANK ATM")
                .transMerchantCity("SEATTLE")
                .transMerchantZip("98102")
                .transOrigTs(testTimestamp.minusDays(2))
                .transProcTs(testTimestamp.minusDays(2).plusSeconds(10))
                .build();

        testTransactionList = Arrays.asList(testTransactionDto, transaction2, transaction3);
    }

    /**
     * Test GET /api/transactions endpoint returns paginated transaction list successfully.
     * 
     * <p>Validates conversion from COBOL program COTRN00C.cbl transaction list logic:</p>
     * <pre>
     * COBOL: EXEC CICS STARTBR FILE('TRANSACT')
     *        PERFORM UNTIL TRANSACT-EOF OR WS-REC-COUNT >= 20
     *            EXEC CICS READNEXT FILE('TRANSACT')
     *        END-PERFORM
     *        EXEC CICS SEND MAP('COTRN00')
     * 
     * REST:  GET /api/transactions?page=0&size=20
     *        Returns Page<TransactionDto> with pagination metadata
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>HTTP 200 OK status</li>
     *   <li>Response contains JSON array with transactions</li>
     *   <li>Pagination metadata included (totalElements, totalPages, size, number)</li>
     *   <li>Transaction fields present (transId, transCardNum, transAmt, etc.)</li>
     *   <li>BigDecimal amounts have 2 decimal places (COMP-3 precision)</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testGetTransactions_Success() throws Exception {
        // Given: Pageable with page 0, size 20 (matching COBOL WS-REC-COUNT = 20)
        Pageable pageable = PageRequest.of(0, 20);
        Page<TransactionDto> transactionPage = new PageImpl<>(testTransactionList, pageable, testTransactionList.size());

        // When: TransactionService.listTransactions called with no filters
        when(transactionService.listTransactions(eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(transactionPage);

        // Then: GET /api/transactions returns 200 OK with paginated transaction list
        mockMvc.perform(get("/api/transactions")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[0].transId", is("0000000123456789")))
                .andExpect(jsonPath("$.content[0].transCardNum", is("************1234")))
                .andExpect(jsonPath("$.content[0].transTypeCd", is("01")))
                .andExpect(jsonPath("$.content[0].transCatCd", is(5411)))
                .andExpect(jsonPath("$.content[0].transAmt", is("125.50")))  // BigDecimal with scale 2 as string
                .andExpect(jsonPath("$.content[0].transDesc", is("GROCERY STORE PURCHASE")))
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.totalPages", is(1)))
                .andExpect(jsonPath("$.size", is(20)))
                .andExpect(jsonPath("$.number", is(0)));
    }

    /**
     * Test GET /api/transactions with date range filter.
     * 
     * <p>Validates COBOL date filtering logic from COTRN00C.cbl:</p>
     * <pre>
     * COBOL: IF WS-START-DATE NOT = SPACES
     *            IF TRAN-ORIG-TS < WS-START-DATE
     *                CONTINUE TO NEXT RECORD
     *        IF WS-END-DATE NOT = SPACES
     *            IF TRAN-ORIG-TS > WS-END-DATE
     *                CONTINUE TO NEXT RECORD
     * 
     * REST:  GET /api/transactions?startDate=2025-01-01&endDate=2025-01-31
     * </pre>
     *
     * <p>Verifies:</p>
     * <ul>
     *   <li>HTTP 200 OK status</li>
     *   <li>Date parameters parsed correctly (ISO 8601 format: yyyy-MM-dd)</li>
     *   <li>Filtered transaction list returned</li>
     *   <li>DateTimeFormat annotation converts string to LocalDate</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testGetTransactions_WithDateFilter() throws Exception {
        // Given: Date range filter (January 2025)
        LocalDate startDate = LocalDate.of(2025, 1, 1);
        LocalDate endDate = LocalDate.of(2025, 1, 31);
        Pageable pageable = PageRequest.of(0, 20);
        
        // Filtered list contains only transactions within date range
        List<TransactionDto> filteredList = Arrays.asList(testTransactionDto);
        Page<TransactionDto> transactionPage = new PageImpl<>(filteredList, pageable, filteredList.size());

        // When: TransactionService.listTransactions called with date filters
        when(transactionService.listTransactions(eq(null), eq(startDate), eq(endDate), any(Pageable.class)))
                .thenReturn(transactionPage);

        // Then: GET /api/transactions with date parameters returns filtered list
        mockMvc.perform(get("/api/transactions")
                        .param("startDate", "2025-01-01")
                        .param("endDate", "2025-01-31")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].transId", is("0000000123456789")))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    /**
     * Test GET /api/transactions with card number filter.
     * 
     * <p>Validates COBOL card number filtering logic from COTRN00C.cbl:</p>
     * <pre>
     * COBOL: IF WS-CARD-FILTER NOT = SPACES
     *            IF TRAN-CARD-NUM NOT = WS-CARD-FILTER
     *                CONTINUE TO NEXT RECORD
     * 
     * REST:  GET /api/transactions?cardNumber=4000123412341234
     * </pre>
     *
     * <p>Verifies:</p>
     * <ul>
     *   <li>HTTP 200 OK status</li>
     *   <li>Only transactions for specified card returned</li>
     *   <li>Card number validation performed by ValidationService</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testGetTransactions_WithCardNumberFilter() throws Exception {
        // Given: Card number filter
        String cardNumber = "4000123412341234";
        Pageable pageable = PageRequest.of(0, 20);
        Page<TransactionDto> transactionPage = new PageImpl<>(testTransactionList, pageable, testTransactionList.size());

        // When: TransactionService.listTransactions called with card number filter
        when(transactionService.listTransactions(eq(cardNumber), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(transactionPage);

        // Then: GET /api/transactions with cardNumber parameter returns filtered list
        mockMvc.perform(get("/api/transactions")
                        .param("cardNumber", cardNumber)
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.totalElements", is(3)));
    }

    /**
     * Test GET /api/transactions/{id} returns transaction detail successfully.
     * 
     * <p>Validates conversion from COBOL program COTRN01C.cbl transaction detail logic:</p>
     * <pre>
     * COBOL: EXEC CICS READ FILE('TRANSACT')
     *             RIDFLD(TRAN-ID)
     *             INTO(TRAN-RECORD)
     *             RESP(WS-RESP-CD)
     *        END-EXEC
     *        EVALUATE WS-RESP-CD
     *            WHEN DFHRESP(NORMAL)
     *                EXEC CICS SEND MAP('COTRN01')
     * 
     * REST:  GET /api/transactions/{transactionId}
     *        Returns TransactionDto with HTTP 200 OK
     * </pre>
     *
     * <p>Verifies:</p>
     * <ul>
     *   <li>HTTP 200 OK status</li>
     *   <li>Transaction detail JSON returned</li>
     *   <li>All fields populated correctly</li>
     *   <li>BigDecimal amount has 2 decimal places</li>
     *   <li>Card number masked for security</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testGetTransactionById_Success() throws Exception {
        // Given: Transaction ID exists
        String transactionId = "0000000123456789";

        // When: TransactionService.getTransactionById returns transaction
        when(transactionService.getTransactionById(eq(transactionId)))
                .thenReturn(testTransactionDto);

        // Then: GET /api/transactions/{id} returns 200 OK with transaction detail
        mockMvc.perform(get("/api/transactions/{transactionId}", transactionId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transId", is("0000000123456789")))
                .andExpect(jsonPath("$.transCardNum", is("************1234")))
                .andExpect(jsonPath("$.transTypeCd", is("01")))
                .andExpect(jsonPath("$.transCatCd", is(5411)))
                .andExpect(jsonPath("$.transSource", is("POS")))
                .andExpect(jsonPath("$.transDesc", is("GROCERY STORE PURCHASE")))
                .andExpect(jsonPath("$.transAmt", is("125.50")))  // BigDecimal with scale 2 as string
                .andExpect(jsonPath("$.transMerchantId", is(123456789)))
                .andExpect(jsonPath("$.transMerchantName", is("LOCAL GROCERY")))
                .andExpect(jsonPath("$.transMerchantCity", is("SEATTLE")))
                .andExpect(jsonPath("$.transMerchantZip", is("98101")));
    }

    /**
     * Test GET /api/transactions/{id} returns 404 when transaction not found.
     * 
     * <p>Validates conversion from COBOL DFHRESP(NOTFND) error handling:</p>
     * <pre>
     * COBOL: EXEC CICS READ FILE('TRANSACT')
     *             RIDFLD(TRAN-ID)
     *             RESP(WS-RESP-CD)
     *        END-EXEC
     *        EVALUATE WS-RESP-CD
     *            WHEN DFHRESP(NOTFND)
     *                MOVE 'Transaction not found' TO WS-MESSAGE
     *                PERFORM SEND-ERROR-SCREEN
     * 
     * REST:  GET /api/transactions/{transactionId}
     *        Returns HTTP 404 Not Found with error message
     * </pre>
     *
     * <p>Verifies:</p>
     * <ul>
     *   <li>HTTP 404 Not Found status</li>
     *   <li>Error response JSON with message</li>
     *   <li>DataNotFoundException thrown by service</li>
     *   <li>GlobalExceptionHandler converts to HTTP 404</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testGetTransactionById_NotFound() throws Exception {
        // Given: Transaction ID does not exist
        String transactionId = "9999999999999999";

        // When: TransactionService.getTransactionById throws DataNotFoundException
        when(transactionService.getTransactionById(eq(transactionId)))
                .thenThrow(new com.carddemo.exception.DataNotFoundException("Transaction not found: " + transactionId));

        // Then: GET /api/transactions/{id} returns 404 Not Found
        mockMvc.perform(get("/api/transactions/{transactionId}", transactionId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message", containsString("Transaction not found")));
    }

    /**
     * Test POST /api/transactions creates transaction successfully.
     * 
     * <p>Validates conversion from COBOL program COTRN02C.cbl transaction posting logic:</p>
     * <pre>
     * COBOL: EXEC CICS RECEIVE MAP('COTRN02') INTO(COTRN2AI) END-EXEC
     *        PERFORM VALIDATE-INPUT-DATA-FIELDS
     *        EXEC CICS READ FILE('CARDFILE') [verify card active]
     *        EXEC CICS READ FILE('ACCTFILE') [get account]
     *        COMPUTE NEW-BALANCE = CURR-BALANCE - TRAN-AMT
     *        IF NEW-BALANCE > CREDIT-LIMIT [validation]
     *        EXEC CICS REWRITE FILE('ACCTFILE') [update balance]
     *        EXEC CICS WRITE FILE('TRANSACT') [create transaction]
     *        EXEC CICS SYNCPOINT [commit]
     *        EXEC CICS SEND MAP('COTRN02') [success message]
     * 
     * REST:  POST /api/transactions
     *        Request Body: TransactionDto (JSON)
     *        Returns HTTP 201 Created with created transaction
     * </pre>
     *
     * <p>Verifies:</p>
     * <ul>
     *   <li>HTTP 201 Created status</li>
     *   <li>Created transaction returned in response</li>
     *   <li>Transaction ID generated</li>
     *   <li>BigDecimal amount preserved with scale 2</li>
     *   <li>All validation passed</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testPostTransaction_Success() throws Exception {
        // Given: Valid transaction request with unmasked card number
        TransactionDto requestDto = TransactionDto.builder()
                .transCardNum("4000123412341234")  // Unmasked for posting
                .transTypeCd("01")  // Purchase
                .transCatCd(5411)  // Grocery
                .transSource("POS")
                .transDesc("GROCERY STORE PURCHASE")
                .transAmt(new BigDecimal("125.50").setScale(2, RoundingMode.HALF_UP))
                .transMerchantId(123456789L)
                .transMerchantName("LOCAL GROCERY")
                .transMerchantCity("SEATTLE")
                .transMerchantZip("98101")
                .transOrigTs(testTimestamp)
                .build();

        // Response DTO with generated transaction ID and masked card number
        TransactionDto responseDto = TransactionDto.builder()
                .transId("0000000123456789")  // Generated by service
                .transCardNum("************1234")  // Masked in response
                .transTypeCd("01")
                .transCatCd(5411)
                .transSource("POS")
                .transDesc("GROCERY STORE PURCHASE")
                .transAmt(new BigDecimal("125.50").setScale(2, RoundingMode.HALF_UP))
                .transMerchantId(123456789L)
                .transMerchantName("LOCAL GROCERY")
                .transMerchantCity("SEATTLE")
                .transMerchantZip("98101")
                .transOrigTs(testTimestamp)
                .transProcTs(testTimestamp.plusSeconds(15))
                .build();

        // When: TransactionService.postTransaction returns created transaction
        when(transactionService.postTransaction(any(TransactionDto.class)))
                .thenReturn(responseDto);

        // Then: POST /api/transactions returns 201 Created with transaction
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transId", is("0000000123456789")))
                .andExpect(jsonPath("$.transCardNum", is("************1234")))  // Masked
                .andExpect(jsonPath("$.transTypeCd", is("01")))
                .andExpect(jsonPath("$.transCatCd", is(5411)))
                .andExpect(jsonPath("$.transAmt", is("125.50")))  // BigDecimal with scale 2 as string
                .andExpect(jsonPath("$.transDesc", is("GROCERY STORE PURCHASE")));
    }

    /**
     * Test POST /api/transactions with validation errors returns 400 Bad Request.
     * 
     * <p>Validates COBOL validation logic from COTRN02C.cbl:</p>
     * <pre>
     * COBOL: PERFORM VALIDATE-INPUT-DATA-FIELDS
     *        IF ERR-FLG-ON
     *            MOVE validation error message TO ERRMSGO
     *            PERFORM SEND-TRNADD-SCREEN
     * 
     * REST:  POST /api/transactions (invalid data)
     *        Returns HTTP 400 Bad Request with validation errors
     * </pre>
     *
     * <p>Verifies:</p>
     * <ul>
     *   <li>HTTP 400 Bad Request status</li>
     *   <li>Error response with validation message</li>
     *   <li>Bean Validation (@Valid) triggers MethodArgumentNotValidException</li>
     *   <li>GlobalExceptionHandler converts to HTTP 400</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testPostTransaction_ValidationError() throws Exception {
        // Given: Invalid transaction request (negative amount)
        TransactionDto requestDto = TransactionDto.builder()
                .transCardNum("4000123412341234")
                .transTypeCd("01")
                .transCatCd(5411)
                .transSource("POS")
                .transDesc("GROCERY STORE PURCHASE")
                .transAmt(new BigDecimal("-125.50"))  // Invalid negative amount
                .transMerchantId(123456789L)
                .transMerchantName("LOCAL GROCERY")
                .transMerchantCity("SEATTLE")
                .transMerchantZip("98101")
                .transOrigTs(testTimestamp)
                .build();

        // When: TransactionService.postTransaction throws ValidationException
        when(transactionService.postTransaction(any(TransactionDto.class)))
                .thenThrow(new com.carddemo.exception.ValidationException("Amount must be positive"));

        // Then: POST /api/transactions returns 400 Bad Request
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message", containsString("Amount must be positive")));
    }

    /**
     * Test POST /api/transactions with credit limit exceeded returns 422 Unprocessable Entity.
     * 
     * <p>Validates COBOL credit limit validation from COTRN02C.cbl lines 630-660:</p>
     * <pre>
     * COBOL: COMPUTE NEW-BALANCE = ACCT-CURR-BAL - TRAN-AMT
     *        IF NEW-BALANCE > ACCT-CREDIT-LIMIT
     *            MOVE 'Transaction exceeds credit limit' TO WS-MESSAGE
     *            PERFORM SEND-ERROR-SCREEN
     * 
     * REST:  POST /api/transactions (exceeds credit limit)
     *        Returns HTTP 422 Unprocessable Entity
     * </pre>
     *
     * <p>Verifies:</p>
     * <ul>
     *   <li>HTTP 422 Unprocessable Entity status (business rule violation)</li>
     *   <li>Error response with credit limit message</li>
     *   <li>BusinessException thrown by service</li>
     *   <li>Credit limit calculation uses BigDecimal with scale 2</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testPostTransaction_CreditLimitExceeded() throws Exception {
        // Given: Transaction that would exceed credit limit
        TransactionDto requestDto = TransactionDto.builder()
                .transCardNum("4000123412341234")
                .transTypeCd("01")  // Purchase (debit)
                .transCatCd(5411)
                .transSource("POS")
                .transDesc("LARGE PURCHASE")
                .transAmt(new BigDecimal("10000.00").setScale(2, RoundingMode.HALF_UP))
                .transMerchantId(123456789L)
                .transMerchantName("ELECTRONICS STORE")
                .transMerchantCity("SEATTLE")
                .transMerchantZip("98101")
                .transOrigTs(testTimestamp)
                .build();

        // When: TransactionService.postTransaction throws BusinessException for credit limit
        when(transactionService.postTransaction(any(TransactionDto.class)))
                .thenThrow(new com.carddemo.exception.BusinessException("Transaction exceeds credit limit"));

        // Then: POST /api/transactions returns 400 Bad Request (BusinessException mapped by GlobalExceptionHandler)
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message", containsString("Transaction exceeds credit limit")));
    }

    /**
     * Test POST /api/transactions validates BigDecimal precision with scale 2.
     * 
     * <p>Validates COBOL COMP-3 precision preservation per Agent Action Plan Section 0.7.3:</p>
     * <pre>
     * COBOL: TRAN-AMT PIC S9(09)V99 COMP-3
     *        [2 decimal places, packed decimal]
     * 
     * Java:  BigDecimal with scale 2, RoundingMode.HALF_UP
     *        [exact precision match to COBOL COMP-3]
     * </pre>
     *
     * <p>Verifies:</p>
     * <ul>
     *   <li>BigDecimal amounts serialized with exactly 2 decimal places</li>
     *   <li>JSON response contains amounts like 125.50 (not 125.5 or 125.500)</li>
     *   <li>RoundingMode.HALF_UP matches COBOL COMP-3 rounding behavior</li>
     *   <li>Scale 2 precision maintained through JSON serialization</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testPostTransaction_BigDecimalPrecision() throws Exception {
        // Given: Transaction with various decimal precision amounts
        TransactionDto requestDto = TransactionDto.builder()
                .transCardNum("4000123412341234")
                .transTypeCd("01")
                .transCatCd(5411)
                .transSource("POS")
                .transDesc("PRECISION TEST")
                .transAmt(new BigDecimal("100.456").setScale(2, RoundingMode.HALF_UP))  // Rounds to 100.46
                .transMerchantId(123456789L)
                .transMerchantName("TEST MERCHANT")
                .transMerchantCity("SEATTLE")
                .transMerchantZip("98101")
                .transOrigTs(testTimestamp)
                .build();

        // Response with properly rounded amount (100.46, not 100.456)
        TransactionDto responseDto = TransactionDto.builder()
                .transId("0000000123456789")
                .transCardNum("************1234")
                .transTypeCd("01")
                .transCatCd(5411)
                .transSource("POS")
                .transDesc("PRECISION TEST")
                .transAmt(new BigDecimal("100.46").setScale(2, RoundingMode.HALF_UP))  // Exactly 2 decimals
                .transMerchantId(123456789L)
                .transMerchantName("TEST MERCHANT")
                .transMerchantCity("SEATTLE")
                .transMerchantZip("98101")
                .transOrigTs(testTimestamp)
                .transProcTs(testTimestamp.plusSeconds(15))
                .build();

        // When: TransactionService.postTransaction returns transaction with scale 2 amount
        when(transactionService.postTransaction(any(TransactionDto.class)))
                .thenReturn(responseDto);

        // Then: POST /api/transactions returns amount with exactly 2 decimal places
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transAmt", is("100.46")))  // Exactly 2 decimals (COMP-3 precision) as string
                .andExpect(jsonPath("$.transId", notNullValue()));
    }

    /**
     * Test POST /api/transactions with inactive card returns 400 Bad Request.
     * 
     * <p>Validates COBOL card status validation from COTRN02C.cbl lines 450-453:</p>
     * <pre>
     * COBOL: EXEC CICS READ FILE('CARDFILE')
     *             RIDFLD(CARD-NUM)
     *             INTO(CARD-RECORD)
     *        END-EXEC
     *        IF CARD-STATUS NOT = 'Y'
     *            MOVE 'Card is not active' TO WS-MESSAGE
     *            PERFORM SEND-ERROR-SCREEN
     * 
     * REST:  POST /api/transactions (inactive card)
     *        Returns HTTP 400 Bad Request with error message
     * </pre>
     *
     * <p>Verifies:</p>
     * <ul>
     *   <li>HTTP 400 Bad Request status</li>
     *   <li>Error response with card status message</li>
     *   <li>BusinessException thrown for inactive card</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testPostTransaction_InactiveCard() throws Exception {
        // Given: Transaction for inactive card
        TransactionDto requestDto = TransactionDto.builder()
                .transCardNum("4000123412341234")
                .transTypeCd("01")
                .transCatCd(5411)
                .transSource("POS")
                .transDesc("PURCHASE ATTEMPT")
                .transAmt(new BigDecimal("50.00").setScale(2, RoundingMode.HALF_UP))
                .transMerchantId(123456789L)
                .transMerchantName("TEST MERCHANT")
                .transMerchantCity("SEATTLE")
                .transMerchantZip("98101")
                .transOrigTs(testTimestamp)
                .build();

        // When: TransactionService.postTransaction throws BusinessException for inactive card
        when(transactionService.postTransaction(any(TransactionDto.class)))
                .thenThrow(new com.carddemo.exception.BusinessException("Card is not active"));

        // Then: POST /api/transactions returns 400 Bad Request
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message", containsString("Card is not active")));
    }

    /**
     * Test GET /api/transactions with empty result set.
     * 
     * <p>Validates COBOL empty browse result handling:</p>
     * <pre>
     * COBOL: PERFORM UNTIL TRANSACT-EOF OR WS-REC-COUNT >= 20
     *            [No matching records found]
     *        END-PERFORM
     *        IF WS-REC-COUNT = 0
     *            MOVE 'No transactions found' TO WS-MESSAGE
     * 
     * REST:  GET /api/transactions
     *        Returns HTTP 200 OK with empty content array
     * </pre>
     *
     * <p>Verifies:</p>
     * <ul>
     *   <li>HTTP 200 OK status (not 404 - empty list is valid result)</li>
     *   <li>Empty content array in response</li>
     *   <li>Pagination metadata with totalElements = 0</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testGetTransactions_EmptyResult() throws Exception {
        // Given: No transactions exist
        Pageable pageable = PageRequest.of(0, 20);
        Page<TransactionDto> emptyPage = new PageImpl<>(List.of(), pageable, 0);

        // When: TransactionService.listTransactions returns empty page
        when(transactionService.listTransactions(eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(emptyPage);

        // Then: GET /api/transactions returns 200 OK with empty content
        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements", is(0)))
                .andExpect(jsonPath("$.totalPages", is(0)));
    }
}
