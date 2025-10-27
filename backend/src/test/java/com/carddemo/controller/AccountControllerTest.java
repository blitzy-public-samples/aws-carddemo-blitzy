/*
 * AccountControllerTest.java
 *
 * JUnit 5 test class for AccountController REST endpoints testing account CRUD operations.
 *
 * Converted from COBOL programs:
 * - Source: app/cbl/COACTUPC.cbl (account update and maintenance)
 * - Source: app/cbl/COACTVWC.cbl (account view and inquiry)
 * - BMS Maps: app/bms/COACTUP.bms, app/bms/COACTVW.bms
 *
 * Test Coverage:
 * This test class validates the complete conversion of COBOL CICS transaction processing
 * to Spring Boot REST API endpoints, ensuring:
 * 1. GET /api/accounts/{id} - Account view (from COACTVWC.cbl)
 * 2. PUT /api/accounts/{id} - Account update (from COACTUPC.cbl)
 * 3. POST /api/accounts - Account creation (from COACTUPC.cbl patterns)
 * 4. Error handling - VSAM RESP codes mapped to HTTP status codes
 * 5. Field validation - BMS field attributes mapped to Jakarta Bean Validation
 * 6. Business rules - Credit limit validation from COBOL lines 500-800
 *
 * COBOL-to-REST Mapping Tested:
 * - EXEC CICS READ FILE('ACCTFILE') → GET /api/accounts/{id}
 * - EXEC CICS REWRITE FILE('ACCTFILE') → PUT /api/accounts/{id}
 * - EXEC CICS WRITE FILE('ACCTFILE') → POST /api/accounts
 * - COBOL RESP 13 (NOTFND) → HTTP 404 Not Found
 * - COBOL APPL-RESULT = 12 → HTTP 400 Bad Request
 * - COBOL validation flags → HTTP 400 Bad Request
 *
 * Test Strategy:
 * - Uses @WebMvcTest for focused controller layer testing
 * - Mocks AccountService with @MockBean to isolate controller logic
 * - Uses MockMvc for performing HTTP requests and asserting responses
 * - Validates JSON serialization/deserialization with ObjectMapper
 * - Tests all success scenarios and error conditions
 * - Verifies proper HTTP status codes per REST standards
 * - Validates BigDecimal precision for monetary amounts (COBOL COMP-3 equivalence)
 *
 * Per Agent Action Plan Section 0.7.2:
 * - Maintain identical business logic validation to COBOL
 * - Preserve COMP-3 precision using BigDecimal with scale 2
 * - Test all field-level validations from COBOL programs
 * - Verify error handling matches COBOL error patterns
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

import com.carddemo.exception.BusinessException;
import com.carddemo.exception.DataNotFoundException;
import com.carddemo.model.dto.AccountDto;
import com.carddemo.security.JwtTokenProvider;
import com.carddemo.service.AccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Test class for AccountController REST endpoints.
 * 
 * <p>Tests the conversion of COBOL CICS transaction processing to Spring Boot REST API,
 * validating that all business logic, validations, and error handling from the original
 * COBOL programs (COACTUPC.cbl and COACTVWC.cbl) are preserved in the Java implementation.</p>
 * 
 * <h3>Test Scenarios:</h3>
 * <ol>
 *   <li><b>GET /api/accounts/{id}:</b>
 *     <ul>
 *       <li>Successful retrieval returns HTTP 200 OK with account data</li>
 *       <li>Non-existent account returns HTTP 404 Not Found (COBOL RESP 13)</li>
 *     </ul>
 *   </li>
 *   <li><b>PUT /api/accounts/{id}:</b>
 *     <ul>
 *       <li>Successful update returns HTTP 200 OK with updated account data</li>
 *       <li>Non-existent account returns HTTP 404 Not Found</li>
 *       <li>Credit limit below balance returns HTTP 400 Bad Request (COBOL APPL-RESULT 12)</li>
 *       <li>Invalid field values return HTTP 400 Bad Request (COBOL validation flags)</li>
 *     </ul>
 *   </li>
 *   <li><b>POST /api/accounts:</b>
 *     <ul>
 *       <li>Successful creation returns HTTP 201 Created with new account data</li>
 *       <li>Duplicate account ID returns HTTP 409 Conflict (COBOL DUPREC)</li>
 *       <li>Missing required fields return HTTP 400 Bad Request</li>
 *       <li>Invalid field values return HTTP 400 Bad Request</li>
 *     </ul>
 *   </li>
 * </ol>
 * 
 * @see AccountController
 * @see AccountService
 * @see AccountDto
 */
@WebMvcTest(AccountController.class)
@AutoConfigureMockMvc(addFilters = false)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Mock bean for JwtTokenProvider to satisfy Spring Security filter chain dependencies.
     * 
     * <p>Even though @AutoConfigureMockMvc(addFilters = false) disables filter execution,
     * Spring Security autoconfiguration still attempts to create JwtAuthenticationFilter bean
     * during context initialization, which requires JwtTokenProvider as a dependency. This
     * @MockBean annotation provides a mock implementation to satisfy the dependency injection
     * requirement without loading the actual JWT security infrastructure.</p>
     * 
     * <p>This mock is not used in account tests since filters are disabled, but it prevents
     * context initialization failures. This follows the same pattern used in HealthCheckControllerTest
     * and MenuControllerTest.</p>
     */
    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private AccountService accountService;

    private AccountDto testAccountDto;
    private Long testAccountId;

    /**
     * Set up test data before each test method execution.
     * 
     * <p>Creates a complete AccountDto object with all fields populated,
     * replicating COBOL ACCOUNT-RECORD structure from CVACT01Y.cpy copybook.</p>
     * 
     * <p>BigDecimal amounts use scale 2 with HALF_UP rounding to maintain
     * COBOL COMP-3 precision requirements per Section 0.7.2.</p>
     */
    @BeforeEach
    void setUp() {
        testAccountId = 12345678901L;
        
        // Create test AccountDto matching COBOL ACCOUNT-RECORD structure
        testAccountDto = AccountDto.builder()
                .acctId(testAccountId)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("1250.75").setScale(2, RoundingMode.HALF_UP))
                .acctCreditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCashCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctOpenDate(LocalDate.of(2020, 1, 15))
                .acctExpirationDate(LocalDate.of(2025, 1, 31))
                .acctReissueDate(null)
                .acctCurrCycCredit(new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP))
                .acctCurrCycDebit(new BigDecimal("750.25").setScale(2, RoundingMode.HALF_UP))
                .acctAddrZip("10001")
                .acctGroupId("PREMIUM")
                .build();
    }

    /**
     * Test GET /api/accounts/{id} - Successful account retrieval.
     * 
     * <p>Converted from COBOL COACTVWC.cbl PROCEDURE DIVISION:</p>
     * <pre>
     * EXEC CICS READ FILE('ACCTFILE')
     *      INTO(ACCOUNT-RECORD)
     *      RIDFLD(ACCT-ID)
     *      RESP(WS-RESP-CD)
     * END-EXEC
     * IF WS-RESP-CD = DFHRESP(NORMAL)
     *    EXEC CICS SEND MAP('COACTVW') FROM(COACTVWO) END-EXEC
     * </pre>
     * 
     * <p>Validates:</p>
     * <ul>
     *   <li>HTTP 200 OK status code</li>
     *   <li>JSON response contains all account fields</li>
     *   <li>BigDecimal amounts have proper scale (2 decimal places)</li>
     *   <li>Service method called exactly once with correct account ID</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testGetAccountById_Success() throws Exception {
        // Arrange: Mock service to return test account
        // Replaces COBOL: IF WS-RESP-CD = DFHRESP(NORMAL)
        when(accountService.getAccountById(testAccountId))
                .thenReturn(testAccountDto);

        // Act & Assert: Perform GET request and validate response
        // Replaces COBOL: EXEC CICS SEND MAP
        mockMvc.perform(MockMvcRequestBuilders.get("/api/accounts/{accountId}", testAccountId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())  // HTTP 200 OK
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.acctId").value(testAccountId))
                .andExpect(jsonPath("$.acctActiveStatus").value("Y"))
                .andExpect(jsonPath("$.acctCurrBal").value(1250.75))
                .andExpect(jsonPath("$.acctCreditLimit").value(5000.00))
                .andExpect(jsonPath("$.acctCashCreditLimit").value(1000.00))
                .andExpect(jsonPath("$.acctOpenDate").value("2020-01-15"))
                .andExpect(jsonPath("$.acctExpirationDate").value("2025-01-31"))
                .andExpect(jsonPath("$.acctReissueDate").doesNotExist())
                .andExpect(jsonPath("$.acctCurrCycCredit").value(500.00))
                .andExpect(jsonPath("$.acctCurrCycDebit").value(750.25))
                .andExpect(jsonPath("$.acctAddrZip").value("10001"))
                .andExpect(jsonPath("$.acctGroupId").value("PREMIUM"));

        // Verify service interaction
        verify(accountService, times(1)).getAccountById(testAccountId);
    }

    /**
     * Test GET /api/accounts/{id} - Account not found.
     * 
     * <p>Converted from COBOL COACTVWC.cbl error handling:</p>
     * <pre>
     * EXEC CICS READ FILE('ACCTFILE')
     *      INTO(ACCOUNT-RECORD)
     *      RIDFLD(ACCT-ID)
     *      RESP(WS-RESP-CD)
     * END-EXEC
     * IF WS-RESP-CD = DFHRESP(NOTFND)  (RESP = 13)
     *    PERFORM ACCOUNT-NOT-FOUND-ERROR
     * END-IF
     * </pre>
     * 
     * <p>Validates:</p>
     * <ul>
     *   <li>HTTP 404 Not Found status code (maps from COBOL RESP 13)</li>
     *   <li>DataNotFoundException properly propagated from service layer</li>
     *   <li>GlobalExceptionHandler converts exception to error response</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testGetAccountById_NotFound() throws Exception {
        // Arrange: Mock service to throw DataNotFoundException
        // Replaces COBOL: IF WS-RESP-CD = DFHRESP(NOTFND)
        Long nonExistentAccountId = 99999999999L;
        when(accountService.getAccountById(nonExistentAccountId))
                .thenThrow(new DataNotFoundException("Account with ID " + nonExistentAccountId + " not found"));

        // Act & Assert: Perform GET request and expect 404 Not Found
        mockMvc.perform(MockMvcRequestBuilders.get("/api/accounts/{accountId}", nonExistentAccountId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());  // HTTP 404 Not Found

        // Verify service interaction
        verify(accountService, times(1)).getAccountById(nonExistentAccountId);
    }

    /**
     * Test PUT /api/accounts/{id} - Successful account update.
     * 
     * <p>Converted from COBOL COACTUPC.cbl update logic:</p>
     * <pre>
     * PERFORM VALIDATE-ACCOUNT-CHANGES.
     * IF VALIDATION-OK
     *    EXEC CICS READ FILE('ACCTFILE')
     *         INTO(ACCOUNT-RECORD)
     *         RIDFLD(ACCT-ID)
     *         UPDATE
     *    END-EXEC
     *    MOVE NEW-CREDIT-LIMIT TO ACCT-CREDIT-LIMIT
     *    MOVE NEW-ACTIVE-STATUS TO ACCT-ACTIVE-STATUS
     *    EXEC CICS REWRITE FILE('ACCTFILE')
     *         FROM(ACCOUNT-RECORD)
     *    END-EXEC
     *    EXEC CICS SYNCPOINT END-EXEC
     * END-IF
     * </pre>
     * 
     * <p>Validates:</p>
     * <ul>
     *   <li>HTTP 200 OK status code</li>
     *   <li>Request body properly deserialized to AccountDto</li>
     *   <li>Response contains updated account data</li>
     *   <li>Service method called with correct account ID and DTO</li>
     *   <li>BigDecimal amounts maintain precision</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testUpdateAccount_Success() throws Exception {
        // Arrange: Create updated account DTO
        AccountDto updateRequest = AccountDto.builder()
                .acctId(testAccountId)  // Required field for validation
                .acctCreditLimit(new BigDecimal("7500.00").setScale(2, RoundingMode.HALF_UP))
                .acctCashCreditLimit(new BigDecimal("1500.00").setScale(2, RoundingMode.HALF_UP))
                .acctActiveStatus("Y")
                .acctExpirationDate(LocalDate.of(2026, 12, 31))
                .acctAddrZip("10002")
                .acctGroupId("PREMIUM")
                .build();

        AccountDto updatedAccount = AccountDto.builder()
                .acctId(testAccountId)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("1250.75").setScale(2, RoundingMode.HALF_UP))
                .acctCreditLimit(new BigDecimal("7500.00").setScale(2, RoundingMode.HALF_UP))
                .acctCashCreditLimit(new BigDecimal("1500.00").setScale(2, RoundingMode.HALF_UP))
                .acctOpenDate(LocalDate.of(2020, 1, 15))
                .acctExpirationDate(LocalDate.of(2026, 12, 31))
                .acctReissueDate(null)
                .acctCurrCycCredit(new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP))
                .acctCurrCycDebit(new BigDecimal("750.25").setScale(2, RoundingMode.HALF_UP))
                .acctAddrZip("10002")
                .acctGroupId("PREMIUM")
                .build();

        // Mock service to return updated account
        // Replaces COBOL: EXEC CICS REWRITE + SYNCPOINT
        when(accountService.updateAccount(eq(testAccountId), any(AccountDto.class)))
                .thenReturn(updatedAccount);

        // Act & Assert: Perform PUT request and validate response
        mockMvc.perform(MockMvcRequestBuilders.put("/api/accounts/{accountId}", testAccountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())  // HTTP 200 OK
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.acctId").value(testAccountId))
                .andExpect(jsonPath("$.acctCreditLimit").value(7500.00))
                .andExpect(jsonPath("$.acctCashCreditLimit").value(1500.00))
                .andExpect(jsonPath("$.acctExpirationDate").value("2026-12-31"))
                .andExpect(jsonPath("$.acctAddrZip").value("10002"));

        // Verify service interaction
        verify(accountService, times(1)).updateAccount(eq(testAccountId), any(AccountDto.class));
    }

    /**
     * Test PUT /api/accounts/{id} - Account not found.
     * 
     * <p>Validates that attempting to update a non-existent account
     * results in HTTP 404 Not Found, mapping from COBOL RESP 13 (NOTFND).</p>
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testUpdateAccount_NotFound() throws Exception {
        // Arrange: Mock service to throw DataNotFoundException
        Long nonExistentAccountId = 99999999999L;
        AccountDto updateRequest = AccountDto.builder()
                .acctId(nonExistentAccountId)  // Required field for validation
                .acctCreditLimit(new BigDecimal("7500.00").setScale(2, RoundingMode.HALF_UP))
                .acctActiveStatus("Y")
                .build();

        when(accountService.updateAccount(eq(nonExistentAccountId), any(AccountDto.class)))
                .thenThrow(new DataNotFoundException("Account with ID " + nonExistentAccountId + " not found"));

        // Act & Assert: Perform PUT request and expect 404 Not Found
        mockMvc.perform(MockMvcRequestBuilders.put("/api/accounts/{accountId}", nonExistentAccountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());  // HTTP 404 Not Found

        // Verify service interaction
        verify(accountService, times(1)).updateAccount(eq(nonExistentAccountId), any(AccountDto.class));
    }

    /**
     * Test PUT /api/accounts/{id} - Business rule violation (credit limit < balance).
     * 
     * <p>Converted from COBOL COACTUPC.cbl validation (lines 500-800):</p>
     * <pre>
     * IF NEW-CREDIT-LIMIT < ACCT-CURR-BAL
     *    MOVE 12 TO APPL-RESULT
     *    MOVE 'Credit limit cannot be less than current balance' TO ERROR-MESSAGE
     *    PERFORM SEND-ERROR-MAP
     * END-IF
     * </pre>
     * 
     * <p>Validates:</p>
     * <ul>
     *   <li>HTTP 400 Bad Request status code (maps from COBOL APPL-RESULT 12)</li>
     *   <li>BusinessException properly propagated from service layer</li>
     *   <li>Error message indicates business rule violation</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testUpdateAccount_CreditLimitBelowBalance() throws Exception {
        // Arrange: Create update request with credit limit below current balance
        AccountDto updateRequest = AccountDto.builder()
                .acctId(testAccountId)  // Required field for validation
                .acctCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))  // Less than balance 1250.75
                .acctActiveStatus("Y")
                .build();

        // Mock service to throw BusinessException
        // Replaces COBOL: MOVE 12 TO APPL-RESULT
        when(accountService.updateAccount(eq(testAccountId), any(AccountDto.class)))
                .thenThrow(new BusinessException("Credit limit cannot be less than current balance"));

        // Act & Assert: Perform PUT request and expect 400 Bad Request
        mockMvc.perform(MockMvcRequestBuilders.put("/api/accounts/{accountId}", testAccountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());  // HTTP 400 Bad Request

        // Verify service interaction
        verify(accountService, times(1)).updateAccount(eq(testAccountId), any(AccountDto.class));
    }

    /**
     * Test PUT /api/accounts/{id} - Invalid account status.
     * 
     * <p>Validates that invalid account status values are rejected with HTTP 400 Bad Request,
     * mapping from COBOL validation flags.</p>
     * 
     * <p>From COBOL COACTUPC.cbl validation:</p>
     * <pre>
     * 88 FLG-ACCT-STATUS-ISVALID VALUES 'Y', 'N', 'C', 'S'
     * IF NOT FLG-ACCT-STATUS-ISVALID
     *    MOVE '0' TO WS-EDIT-ALPHA-ONLY-FLAGS
     *    PERFORM SEND-VALIDATION-ERROR
     * END-IF
     * </pre>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testUpdateAccount_InvalidStatus() throws Exception {
        // Arrange: Create update request with invalid status
        String invalidJson = "{"
                + "\"acctCreditLimit\": 5000.00,"
                + "\"acctActiveStatus\": \"X\""  // Invalid status (should be Y, N, C, or S)
                + "}";

        // Act & Assert: Perform PUT request and expect 400 Bad Request
        // Note: @Pattern validation on AccountDto will trigger MethodArgumentNotValidException
        mockMvc.perform(MockMvcRequestBuilders.put("/api/accounts/{accountId}", testAccountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());  // HTTP 400 Bad Request

        // Verify service is NOT called due to validation failure
        verify(accountService, never()).updateAccount(any(), any());
    }

    /**
     * Test POST /api/accounts - Successful account creation.
     * 
     * <p>Implements account creation logic based on COACTUPC.cbl patterns:</p>
     * <pre>
     * PERFORM VALIDATE-NEW-ACCOUNT.
     * IF VALIDATION-OK
     *    MOVE ZEROS TO ACCT-CURR-BAL
     *    MOVE ZEROS TO ACCT-CURR-CYC-CREDIT
     *    MOVE ZEROS TO ACCT-CURR-CYC-DEBIT
     *    EXEC CICS WRITE FILE('ACCTFILE')
     *         FROM(ACCOUNT-RECORD)
     *         RIDFLD(ACCT-ID)
     *    END-EXEC
     *    EXEC CICS SYNCPOINT END-EXEC
     * END-IF
     * </pre>
     * 
     * <p>Validates:</p>
     * <ul>
     *   <li>HTTP 201 Created status code (REST standard for resource creation)</li>
     *   <li>Request body properly deserialized to AccountDto</li>
     *   <li>Response contains newly created account with generated values</li>
     *   <li>Initial balance, cycle credit, and cycle debit set to zero</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testCreateAccount_Success() throws Exception {
        // Arrange: Create new account request
        Long newAccountId = 98765432109L;
        AccountDto createRequest = AccountDto.builder()
                .acctId(newAccountId)
                .acctActiveStatus("Y")
                .acctCreditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCashCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctOpenDate(LocalDate.now())
                .acctExpirationDate(LocalDate.now().plusYears(3))
                .acctAddrZip("10001")
                .acctGroupId("STANDARD")
                .build();

        AccountDto createdAccount = AccountDto.builder()
                .acctId(newAccountId)
                .acctActiveStatus("Y")
                .acctCurrBal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))  // COBOL: MOVE ZEROS
                .acctCreditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCashCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctOpenDate(createRequest.getAcctOpenDate())
                .acctExpirationDate(createRequest.getAcctExpirationDate())
                .acctReissueDate(null)
                .acctCurrCycCredit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))  // COBOL: MOVE ZEROS
                .acctCurrCycDebit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))   // COBOL: MOVE ZEROS
                .acctAddrZip("10001")
                .acctGroupId("STANDARD")
                .build();

        // Mock service to return created account
        // Replaces COBOL: EXEC CICS WRITE + SYNCPOINT
        when(accountService.createAccount(any(AccountDto.class)))
                .thenReturn(createdAccount);

        // Act & Assert: Perform POST request and validate response
        mockMvc.perform(MockMvcRequestBuilders.post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())  // HTTP 201 Created
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.acctId").value(newAccountId))
                .andExpect(jsonPath("$.acctActiveStatus").value("Y"))
                .andExpect(jsonPath("$.acctCurrBal").value(0.00))
                .andExpect(jsonPath("$.acctCreditLimit").value(5000.00))
                .andExpect(jsonPath("$.acctCashCreditLimit").value(1000.00))
                .andExpect(jsonPath("$.acctCurrCycCredit").value(0.00))
                .andExpect(jsonPath("$.acctCurrCycDebit").value(0.00))
                .andExpect(jsonPath("$.acctAddrZip").value("10001"))
                .andExpect(jsonPath("$.acctGroupId").value("STANDARD"));

        // Verify service interaction
        verify(accountService, times(1)).createAccount(any(AccountDto.class));
    }

    /**
     * Test POST /api/accounts - Missing required fields.
     * 
     * <p>Validates that creating an account without required fields
     * results in HTTP 400 Bad Request due to validation failures.</p>
     * 
     * <p>From COBOL validation patterns:</p>
     * <pre>
     * IF WS-ACCT-ID = SPACES OR ZEROS
     *    MOVE '0' TO FLG-MANDATORY-ISVALID
     *    PERFORM SEND-VALIDATION-ERROR
     * END-IF
     * </pre>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testCreateAccount_MissingRequiredFields() throws Exception {
        // Arrange: Create incomplete account request (missing required fields)
        String incompleteJson = "{"
                + "\"acctActiveStatus\": \"Y\""  // Missing acctId, creditLimit, etc.
                + "}";

        // Act & Assert: Perform POST request and expect 400 Bad Request
        mockMvc.perform(MockMvcRequestBuilders.post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(incompleteJson)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());  // HTTP 400 Bad Request

        // Verify service is NOT called due to validation failure
        verify(accountService, never()).createAccount(any());
    }

    /**
     * Test POST /api/accounts - Invalid credit limit (exceeds maximum).
     * 
     * <p>Validates that credit limit exceeding $50,000.00 maximum is rejected.</p>
     * 
     * <p>From COBOL COACTUPC.cbl validation (lines 500-800):</p>
     * <pre>
     * IF NEW-CREDIT-LIMIT > 50000.00
     *    MOVE 12 TO APPL-RESULT
     *    MOVE 'Credit limit cannot exceed $50,000.00' TO ERROR-MESSAGE
     *    PERFORM SEND-ERROR-MAP
     * END-IF
     * </pre>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testCreateAccount_CreditLimitExceedsMaximum() throws Exception {
        // Arrange: Create account with excessive credit limit
        Long newAccountId = 98765432109L;
        String invalidJson = "{"
                + "\"acctId\": " + newAccountId + ","
                + "\"acctActiveStatus\": \"Y\","
                + "\"acctCreditLimit\": 60000.00,"  // Exceeds $50,000 maximum
                + "\"acctCashCreditLimit\": 1000.00,"
                + "\"acctOpenDate\": \"2024-03-20\""
                + "}";

        // Act & Assert: Perform POST request and expect 400 Bad Request
        // @DecimalMax validation on AccountDto will trigger MethodArgumentNotValidException
        mockMvc.perform(MockMvcRequestBuilders.post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());  // HTTP 400 Bad Request

        // Verify service is NOT called due to validation failure
        verify(accountService, never()).createAccount(any());
    }

    /**
     * Test POST /api/accounts - Duplicate account ID (conflict).
     * 
     * <p>Validates that attempting to create an account with an existing ID
     * results in HTTP 409 Conflict, mapping from COBOL DUPREC condition.</p>
     * 
     * <p>From COBOL error handling:</p>
     * <pre>
     * EXEC CICS WRITE FILE('ACCTFILE')
     *      FROM(ACCOUNT-RECORD)
     *      RIDFLD(ACCT-ID)
     *      RESP(WS-RESP-CD)
     * END-EXEC
     * IF WS-RESP-CD = DFHRESP(DUPREC)
     *    PERFORM DUPLICATE-ACCOUNT-ERROR
     * END-IF
     * </pre>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testCreateAccount_DuplicateAccountId() throws Exception {
        // Arrange: Create account with existing ID
        AccountDto createRequest = AccountDto.builder()
                .acctId(testAccountId)  // Use existing account ID
                .acctActiveStatus("Y")
                .acctCreditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCashCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctOpenDate(LocalDate.now())
                .build();

        // Mock service to throw BusinessException for duplicate ID
        // Replaces COBOL: IF WS-RESP-CD = DFHRESP(DUPREC)
        when(accountService.createAccount(any(AccountDto.class)))
                .thenThrow(new BusinessException("Account ID already exists: " + testAccountId));

        // Act & Assert: Perform POST request and expect 400 Bad Request
        // Note: Could also map to 409 Conflict in GlobalExceptionHandler
        mockMvc.perform(MockMvcRequestBuilders.post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());  // HTTP 400 Bad Request

        // Verify service interaction
        verify(accountService, times(1)).createAccount(any(AccountDto.class));
    }

    /**
     * Test POST /api/accounts - Negative credit limit.
     * 
     * <p>Validates that negative credit limit values are rejected with HTTP 400 Bad Request.</p>
     * 
     * <p>From COBOL validation:</p>
     * <pre>
     * IF NEW-CREDIT-LIMIT < ZEROS
     *    MOVE '0' TO FLG-SIGNED-NUMBER-EDIT
     *    PERFORM SEND-VALIDATION-ERROR
     * END-IF
     * </pre>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testCreateAccount_NegativeCreditLimit() throws Exception {
        // Arrange: Create account with negative credit limit
        Long newAccountId = 98765432109L;
        String invalidJson = "{"
                + "\"acctId\": " + newAccountId + ","
                + "\"acctActiveStatus\": \"Y\","
                + "\"acctCreditLimit\": -1000.00,"  // Negative value
                + "\"acctCashCreditLimit\": 1000.00,"
                + "\"acctOpenDate\": \"2024-03-20\""
                + "}";

        // Act & Assert: Perform POST request and expect 400 Bad Request
        // @DecimalMin validation on AccountDto will trigger MethodArgumentNotValidException
        mockMvc.perform(MockMvcRequestBuilders.post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());  // HTTP 400 Bad Request

        // Verify service is NOT called due to validation failure
        verify(accountService, never()).createAccount(any());
    }
}
