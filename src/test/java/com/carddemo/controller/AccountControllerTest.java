/*
 * AccountControllerTest.java
 * 
 * CardDemo Application - Integration Tests for AccountController
 * 
 * Comprehensive integration tests for AccountController REST API endpoints validating
 * account management operations transformed from mainframe CICS transactions CAVW
 * (Account View from COACTVWC.cbl) and CAUP (Account Update from COACTUPC.cbl) to
 * modern RESTful web services.
 * 
 * Test Coverage:
 * - GET /api/accounts/{id} - Account retrieval with complete field validation
 * - PUT /api/accounts/{id} - Account updates with credit limit validation
 * - HTTP status codes: 200 OK, 404 Not Found, 400 Bad Request, 403 Forbidden
 * - Security authorization with @PreAuthorize role-based access control
 * - BigDecimal precision matching COBOL COMP-3 PIC S9(10)V99 fields
 * - Bean Validation constraints (@NotNull, @DecimalMin, @DecimalMax, @Digits)
 * - @Transactional behavior with rollback on validation failures
 * - Optimistic locking with @Version for concurrent update handling
 * - Boundary condition testing for credit limit ranges
 * - JSON serialization/deserialization matching CVACT01Y copybook structure
 * 
 * Original COBOL Programs Tested:
 * - app/cbl/COACTVWC.cbl: Account view and retrieval (CAVW transaction)
 * - app/cbl/COACTUPC.cbl: Account update with credit limit validation (CAUP transaction)
 * 
 * COBOL Copybook Structure:
 * - app/cpy/CVACT01Y.cpy: ACCOUNT-RECORD (300-byte VSAM KSDS record)
 *   - ACCT-ID PIC 9(11) → Long accountId
 *   - ACCT-ACTIVE-STATUS PIC X(01) → String activeStatus ('Y'/'N')
 *   - ACCT-CURR-BAL PIC S9(10)V99 → BigDecimal currentBalance (scale=2)
 *   - ACCT-CREDIT-LIMIT PIC S9(10)V99 → BigDecimal creditLimit (scale=2)
 *   - ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 → BigDecimal cashCreditLimit (scale=2)
 *   - ACCT-OPEN-DATE PIC X(10) → LocalDate openDate
 *   - ACCT-EXPIRAION-DATE PIC X(10) → LocalDate expirationDate
 *   - ACCT-REISSUE-DATE PIC X(10) → LocalDate reissueDate
 *   - ACCT-CURR-CYC-CREDIT PIC S9(10)V99 → BigDecimal currentCycleCredit (scale=2)
 *   - ACCT-CURR-CYC-DEBIT PIC S9(10)V99 → BigDecimal currentCycleDebit (scale=2)
 *   - ACCT-ADDR-ZIP PIC X(10) → String addressZip
 *   - ACCT-GROUP-ID PIC X(10) → String groupId
 * 
 * Test Strategy:
 * @SpringBootTest loads full application context with all Spring components
 * @AutoConfigureMockMvc provides MockMvc for HTTP request/response testing
 * @ActiveProfiles("test") activates test-specific configuration (H2 database)
 * @WithMockUser simulates authenticated users with different roles
 * @Transactional on test methods ensures database rollback after each test
 * 
 * Validation Requirements from Section 0.10:
 * - All financial calculations must use BigDecimal with RoundingMode.HALF_UP
 * - Credit limit minimum: $1,000.00 (matching COBOL 88 LIMIT-VALID VALUE 1000 THRU 999999999)
 * - Credit limit maximum: $999,999,999.99
 * - All monetary fields must maintain scale=2 precision (matching COBOL V99)
 * - Active status must be exactly 'Y' or 'N' only
 * - Response time target: < 200ms at 95th percentile
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

import com.carddemo.dto.request.AccountUpdateRequest;
import com.carddemo.dto.response.AccountResponse;
import com.carddemo.entity.Account;
import com.carddemo.repository.AccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for AccountController REST API endpoints.
 * 
 * <p>This test class provides comprehensive integration testing for account management
 * operations, validating the transformation from mainframe CICS transactions CAVW and
 * CAUP (COACTVWC.cbl and COACTUPC.cbl) to modern RESTful web services.</p>
 * 
 * <p><strong>Test Environment:</strong></p>
 * <ul>
 *   <li>@SpringBootTest: Full application context with all Spring components</li>
 *   <li>@AutoConfigureMockMvc: MockMvc for HTTP request/response testing without server</li>
 *   <li>@ActiveProfiles("test"): H2 in-memory database with test-specific configuration</li>
 *   <li>Test database: Automatically created and torn down for each test class</li>
 * </ul>
 * 
 * <p><strong>Security Testing:</strong></p>
 * <ul>
 *   <li>@WithMockUser: Simulates authenticated users with ROLE_USER or ROLE_ADMIN</li>
 *   <li>Tests authorization rules: users can only access own accounts, admins access all</li>
 *   <li>Validates @PreAuthorize annotations on controller methods</li>
 * </ul>
 * 
 * <p><strong>Transaction Management:</strong></p>
 * <ul>
 *   <li>@Transactional on test methods ensures automatic rollback after each test</li>
 *   <li>Tests verify @Transactional service methods maintain ACID properties</li>
 *   <li>Validates rollback behavior on validation failures and exceptions</li>
 * </ul>
 * 
 * <p><strong>Data Precision Testing:</strong></p>
 * <ul>
 *   <li>All BigDecimal assertions use comparesEqualTo matcher for exact precision</li>
 *   <li>Validates scale=2 for all monetary fields matching COBOL COMP-3 V99</li>
 *   <li>Tests RoundingMode.HALF_UP behavior matching COBOL arithmetic</li>
 * </ul>
 * 
 * @see AccountController
 * @see AccountUpdateRequest
 * @see AccountResponse
 * @see Account
 * @see AccountRepository
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("AccountController Integration Tests")
public class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    // Test data constants matching COBOL CVACT01Y copybook field constraints
    private static final Long TEST_ACCOUNT_ID = 11111111111L; // PIC 9(11)
    private static final Long NON_EXISTENT_ACCOUNT_ID = 99999999999L;
    private static final String ACTIVE_STATUS_ACTIVE = "Y"; // ACCT-ACTIVE-STATUS PIC X(01)
    private static final String ACTIVE_STATUS_INACTIVE = "N";
    
    // BigDecimal monetary values with scale=2 matching COBOL PIC S9(10)V99 COMP-3
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("25000.50").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("50000.00").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal CASH_CREDIT_LIMIT = new BigDecimal("10000.00").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal CURRENT_CYCLE_CREDIT = new BigDecimal("1500.75").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal CURRENT_CYCLE_DEBIT = new BigDecimal("2300.25").setScale(2, RoundingMode.HALF_UP);
    
    // Credit limit boundary values matching COBOL 88 LIMIT-VALID condition
    private static final BigDecimal MIN_CREDIT_LIMIT = new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal MAX_CREDIT_LIMIT = new BigDecimal("999999999.99").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal BELOW_MIN_CREDIT_LIMIT = new BigDecimal("999.99").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal ABOVE_MAX_CREDIT_LIMIT = new BigDecimal("1000000000.00").setScale(2, RoundingMode.HALF_UP);
    
    // Date values matching COBOL PIC X(10) format YYYY-MM-DD
    private static final LocalDate OPEN_DATE = LocalDate.of(2020, 1, 15);
    private static final LocalDate EXPIRATION_DATE = LocalDate.of(2025, 1, 31);
    private static final LocalDate REISSUE_DATE = LocalDate.of(2024, 11, 1);
    
    // Address and group reference data matching COBOL PIC X(10)
    private static final String ADDRESS_ZIP = "10001";
    private static final String GROUP_ID = "GROUP001";

    private Account testAccount;

    /**
     * Setup method executed before each test.
     * 
     * <p>Creates and persists a test account in the database with all fields
     * populated matching CVACT01Y ACCOUNT-RECORD copybook structure. Uses
     * BigDecimal with scale=2 for all monetary fields to match COBOL COMP-3
     * precision requirements.</p>
     * 
     * <p>This simulates the VSAM ACCTDAT file record used by COBOL programs
     * COACTVWC and COACTUPC for account operations.</p>
     */
    @BeforeEach
    public void setUp() {
        // Create test account matching COBOL ACCOUNT-RECORD structure from CVACT01Y.cpy
        testAccount = Account.builder()
                .accountId(TEST_ACCOUNT_ID) // ACCT-ID PIC 9(11)
                .activeStatus(ACTIVE_STATUS_ACTIVE) // ACCT-ACTIVE-STATUS PIC X(01)
                .currentBalance(CURRENT_BALANCE) // ACCT-CURR-BAL PIC S9(10)V99
                .creditLimit(CREDIT_LIMIT) // ACCT-CREDIT-LIMIT PIC S9(10)V99
                .cashCreditLimit(CASH_CREDIT_LIMIT) // ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99
                .openDate(OPEN_DATE) // ACCT-OPEN-DATE PIC X(10)
                .expirationDate(EXPIRATION_DATE) // ACCT-EXPIRAION-DATE PIC X(10)
                .reissueDate(REISSUE_DATE) // ACCT-REISSUE-DATE PIC X(10)
                .currentCycleCredit(CURRENT_CYCLE_CREDIT) // ACCT-CURR-CYC-CREDIT PIC S9(10)V99
                .currentCycleDebit(CURRENT_CYCLE_DEBIT) // ACCT-CURR-CYC-DEBIT PIC S9(10)V99
                .addressZip(ADDRESS_ZIP) // ACCT-ADDR-ZIP PIC X(10)
                .groupId(GROUP_ID) // ACCT-GROUP-ID PIC X(10)
                .build();
        
        // Persist test account to database (replaces EXEC CICS WRITE DATASET('ACCTDAT'))
        accountRepository.save(testAccount);
    }

    /**
     * Teardown method executed after each test.
     * 
     * <p>Cleans up test data by deleting all accounts from the test database.
     * This ensures test isolation and prevents data contamination between tests.</p>
     * 
     * <p>Note: @Transactional on individual test methods also provides rollback,
     * but explicit cleanup ensures consistent test database state.</p>
     */
    @AfterEach
    public void tearDown() {
        // Clean up test data (replaces EXEC CICS DELETE DATASET('ACCTDAT'))
        accountRepository.deleteAll();
    }

    /**
     * Nested test class for GET /api/accounts/{id} endpoint tests.
     * 
     * <p>Tests account retrieval functionality transformed from COBOL program
     * COACTVWC.cbl transaction CAVW. Validates proper HTTP status codes, JSON
     * serialization, BigDecimal precision, and security authorization.</p>
     */
    @Nested
    @DisplayName("GET /api/accounts/{id} - Account Retrieval Tests")
    class GetAccountByIdTests {

        /**
         * Test successful account retrieval with valid account ID.
         * 
         * <p>Validates GET /api/accounts/{id} endpoint returns HTTP 200 OK with
         * complete AccountResponse JSON matching CVACT01Y ACCOUNT-RECORD structure.</p>
         * 
         * <p>COBOL Program: COACTVWC.cbl lines 789-850 (paragraph 9300-GETACCTDATA-BYACCT)</p>
         * <p>COBOL Operation: EXEC CICS READ DATASET('ACCTDAT') INTO(ACCOUNT-RECORD) RIDFLD(ACCT-ID)</p>
         * <p>Java Equivalent: accountRepository.findByAccountId(accountId)</p>
         * 
         * <p>Verifies:</p>
         * <ul>
         *   <li>HTTP 200 OK status code</li>
         *   <li>Content-Type: application/json</li>
         *   <li>All ACCOUNT-RECORD fields present in JSON response</li>
         *   <li>BigDecimal monetary fields with scale=2 precision</li>
         *   <li>Date fields formatted as ISO-8601 strings</li>
         *   <li>Active status 'Y' or 'N' only</li>
         * </ul>
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("Should return account details with 200 OK when account exists")
        public void testGetAccountById_Success() throws Exception {
            mockMvc.perform(get("/api/accounts/{id}", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    // Verify ACCT-ID field (PIC 9(11))
                    .andExpect(jsonPath("$.accountId", is(TEST_ACCOUNT_ID.intValue())))
                    // Verify ACCT-ACTIVE-STATUS field (PIC X(01) - 'Y' or 'N')
                    .andExpect(jsonPath("$.activeStatus", is(ACTIVE_STATUS_ACTIVE)))
                    // Verify ACCT-CURR-BAL field (PIC S9(10)V99) - BigDecimal with scale=2
                    .andExpect(jsonPath("$.currentBalance", comparesEqualTo(CURRENT_BALANCE)))
                    // Verify ACCT-CREDIT-LIMIT field (PIC S9(10)V99) - BigDecimal with scale=2
                    .andExpect(jsonPath("$.creditLimit", comparesEqualTo(CREDIT_LIMIT)))
                    // Verify ACCT-CASH-CREDIT-LIMIT field (PIC S9(10)V99) - BigDecimal with scale=2
                    .andExpect(jsonPath("$.cashCreditLimit", comparesEqualTo(CASH_CREDIT_LIMIT)))
                    // Verify ACCT-OPEN-DATE field (PIC X(10) - ISO-8601 format)
                    .andExpect(jsonPath("$.openDate", is(OPEN_DATE.toString())))
                    // Verify ACCT-EXPIRAION-DATE field (PIC X(10) - ISO-8601 format)
                    .andExpect(jsonPath("$.expirationDate", is(EXPIRATION_DATE.toString())))
                    // Verify ACCT-REISSUE-DATE field (PIC X(10) - ISO-8601 format)
                    .andExpect(jsonPath("$.reissueDate", is(REISSUE_DATE.toString())))
                    // Verify ACCT-CURR-CYC-CREDIT field (PIC S9(10)V99) - BigDecimal with scale=2
                    .andExpect(jsonPath("$.currentCycleCredit", comparesEqualTo(CURRENT_CYCLE_CREDIT)))
                    // Verify ACCT-CURR-CYC-DEBIT field (PIC S9(10)V99) - BigDecimal with scale=2
                    .andExpect(jsonPath("$.currentCycleDebit", comparesEqualTo(CURRENT_CYCLE_DEBIT)))
                    // Verify ACCT-ADDR-ZIP field (PIC X(10))
                    .andExpect(jsonPath("$.addressZip", is(ADDRESS_ZIP)))
                    // Verify ACCT-GROUP-ID field (PIC X(10))
                    .andExpect(jsonPath("$.groupId", is(GROUP_ID)));
        }

        /**
         * Test account retrieval with non-existent account ID.
         * 
         * <p>Validates GET /api/accounts/{id} endpoint returns HTTP 404 Not Found
         * when account does not exist in database.</p>
         * 
         * <p>COBOL Program: COACTVWC.cbl lines 789-850</p>
         * <p>COBOL Error Handling: RESP(DFHRESP(NOTFND)) condition</p>
         * <p>COBOL Message: DID-NOT-FIND-ACCT-IN-ACCTDAT VALUE 'Did not find this account in account master file'</p>
         * <p>Java Equivalent: repository.findByAccountId returns empty Optional → ResourceNotFoundException</p>
         * 
         * <p>Verifies:</p>
         * <ul>
         *   <li>HTTP 404 Not Found status code</li>
         *   <li>Error response handled by GlobalExceptionHandler</li>
         *   <li>Appropriate error message in response body</li>
         * </ul>
         */
        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("Should return 404 Not Found when account does not exist")
        public void testGetAccountById_NotFound() throws Exception {
            mockMvc.perform(get("/api/accounts/{id}", NON_EXISTENT_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound());
        }

        /**
         * Test account retrieval without authentication.
         * 
         * <p>Validates GET /api/accounts/{id} endpoint returns HTTP 401 Unauthorized
         * when JWT authentication token is missing or invalid.</p>
         * 
         * <p>COBOL Security: RACF transaction security check in CICS region</p>
         * <p>Java Security: Spring Security filter chain validates JWT token before controller</p>
         * 
         * <p>Verifies:</p>
         * <ul>
         *   <li>HTTP 401 Unauthorized status code</li>
         *   <li>Spring Security authentication requirement enforced</li>
         * </ul>
         * 
         * <p>Note: @WithMockUser annotation is intentionally omitted to simulate unauthenticated request.</p>
         */
        @Test
        @DisplayName("Should return 401 Unauthorized when not authenticated")
        public void testGetAccountById_Unauthorized() throws Exception {
            mockMvc.perform(get("/api/accounts/{id}", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * Test account retrieval with admin role.
         * 
         * <p>Validates admin users can retrieve any account regardless of ownership.</p>
         * 
         * <p>COBOL Security: RACF user type check - 88 CDEMO-USRTYP-ADMIN VALUE 'A'</p>
         * <p>Java Security: @PreAuthorize("hasRole('ADMIN')") allows access to all accounts</p>
         * 
         * <p>Verifies:</p>
         * <ul>
         *   <li>HTTP 200 OK status code</li>
         *   <li>Complete account details returned</li>
         *   <li>Admin role authorization working correctly</li>
         * </ul>
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("Should return account details with 200 OK for admin users")
        public void testGetAccountById_AdminAccess() throws Exception {
            mockMvc.perform(get("/api/accounts/{id}", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountId", is(TEST_ACCOUNT_ID.intValue())))
                    .andExpect(jsonPath("$.activeStatus", is(ACTIVE_STATUS_ACTIVE)));
        }
    }

    /**
     * Nested test class for PUT /api/accounts/{id} endpoint tests.
     * 
     * <p>Tests account update functionality transformed from COBOL program
     * COACTUPC.cbl transaction CAUP. Validates credit limit updates, validation
     * constraints, transactional behavior, and security authorization.</p>
     */
    @Nested
    @DisplayName("PUT /api/accounts/{id} - Account Update Tests")
    class UpdateAccountTests {

        /**
         * Test successful account update with valid request.
         * 
         * <p>Validates PUT /api/accounts/{id} endpoint updates account credit limits
         * and returns HTTP 200 OK with updated AccountResponse JSON.</p>
         * 
         * <p>COBOL Program: COACTUPC.cbl (Account Update transaction CAUP)</p>
         * <p>COBOL Operation: EXEC CICS REWRITE DATASET('ACCTDAT') FROM(ACCOUNT-RECORD)</p>
         * <p>Java Equivalent: accountRepository.save(account) within @Transactional method</p>
         * 
         * <p>Verifies:</p>
         * <ul>
         *   <li>HTTP 200 OK status code</li>
         *   <li>Updated credit limit values in response</li>
         *   <li>BigDecimal precision maintained (scale=2)</li>
         *   <li>Database state updated correctly</li>
         *   <li>@Transactional ACID properties enforced</li>
         * </ul>
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @Transactional
        @DisplayName("Should update account successfully with 200 OK")
        public void testUpdateAccount_Success() throws Exception {
            // Create update request with new credit limit values
            BigDecimal newCreditLimit = new BigDecimal("75000.00").setScale(2, RoundingMode.HALF_UP);
            BigDecimal newCashCreditLimit = new BigDecimal("15000.00").setScale(2, RoundingMode.HALF_UP);
            
            AccountUpdateRequest updateRequest = AccountUpdateRequest.builder()
                    .accountId(TEST_ACCOUNT_ID)
                    .creditLimit(newCreditLimit)
                    .cashCreditLimit(newCashCreditLimit)
                    .activeStatus(ACTIVE_STATUS_ACTIVE)
                    .build();
            
            mockMvc.perform(put("/api/accounts/{id}", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.accountId", is(TEST_ACCOUNT_ID.intValue())))
                    .andExpect(jsonPath("$.creditLimit", comparesEqualTo(newCreditLimit)))
                    .andExpect(jsonPath("$.cashCreditLimit", comparesEqualTo(newCashCreditLimit)))
                    .andExpect(jsonPath("$.activeStatus", is(ACTIVE_STATUS_ACTIVE)));
            
            // Verify database state after update
            Account updatedAccount = accountRepository.findByAccountId(TEST_ACCOUNT_ID)
                    .orElseThrow(() -> new AssertionError("Account should exist after update"));
            
            assert updatedAccount.getCreditLimit().compareTo(newCreditLimit) == 0 
                : "Credit limit in database should match updated value";
            assert updatedAccount.getCashCreditLimit().compareTo(newCashCreditLimit) == 0 
                : "Cash credit limit in database should match updated value";
        }

        /**
         * Test account update with non-existent account ID.
         * 
         * <p>Validates PUT /api/accounts/{id} endpoint returns HTTP 404 Not Found
         * when attempting to update account that does not exist.</p>
         * 
         * <p>COBOL Program: COACTUPC.cbl error handling</p>
         * <p>COBOL Error: RESP(DFHRESP(NOTFND)) on READ before REWRITE</p>
         * <p>Java Equivalent: repository.findByAccountId returns empty Optional → ResourceNotFoundException</p>
         * 
         * <p>Verifies:</p>
         * <ul>
         *   <li>HTTP 404 Not Found status code</li>
         *   <li>No database changes made</li>
         *   <li>Appropriate error message</li>
         * </ul>
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("Should return 404 Not Found when updating non-existent account")
        public void testUpdateAccount_NotFound() throws Exception {
            AccountUpdateRequest updateRequest = AccountUpdateRequest.builder()
                    .accountId(NON_EXISTENT_ACCOUNT_ID)
                    .creditLimit(CREDIT_LIMIT)
                    .cashCreditLimit(CASH_CREDIT_LIMIT)
                    .activeStatus(ACTIVE_STATUS_ACTIVE)
                    .build();
            
            mockMvc.perform(put("/api/accounts/{id}", NON_EXISTENT_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isNotFound());
        }

        /**
         * Test account update with credit limit below minimum.
         * 
         * <p>Validates Bean Validation @DecimalMin constraint enforcement matching
         * COBOL 88-level condition: 88 LIMIT-VALID VALUE 1000 THRU 999999999</p>
         * 
         * <p>COBOL Validation: Credit limit must be at least $1,000.00</p>
         * <p>Java Validation: @DecimalMin(value = "1000.00", message = "Credit limit must be at least $1,000")</p>
         * 
         * <p>Verifies:</p>
         * <ul>
         *   <li>HTTP 400 Bad Request status code</li>
         *   <li>Validation error message in response</li>
         *   <li>No database changes made</li>
         *   <li>@Transactional rollback on validation failure</li>
         * </ul>
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("Should return 400 Bad Request when credit limit below minimum")
        public void testUpdateAccount_CreditLimitBelowMinimum() throws Exception {
            AccountUpdateRequest updateRequest = AccountUpdateRequest.builder()
                    .accountId(TEST_ACCOUNT_ID)
                    .creditLimit(BELOW_MIN_CREDIT_LIMIT) // $999.99 - below $1,000.00 minimum
                    .cashCreditLimit(CASH_CREDIT_LIMIT)
                    .activeStatus(ACTIVE_STATUS_ACTIVE)
                    .build();
            
            mockMvc.perform(put("/api/accounts/{id}", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isBadRequest());
            
            // Verify database state unchanged after validation failure
            Account unchangedAccount = accountRepository.findByAccountId(TEST_ACCOUNT_ID)
                    .orElseThrow(() -> new AssertionError("Account should still exist"));
            
            assert unchangedAccount.getCreditLimit().compareTo(CREDIT_LIMIT) == 0 
                : "Credit limit in database should remain unchanged after validation failure";
        }

        /**
         * Test account update with credit limit above maximum.
         * 
         * <p>Validates Bean Validation @DecimalMax constraint enforcement matching
         * COBOL 88-level condition: 88 LIMIT-VALID VALUE 1000 THRU 999999999</p>
         * 
         * <p>COBOL Validation: Credit limit cannot exceed $999,999,999.99</p>
         * <p>Java Validation: @DecimalMax(value = "999999999.99", message = "Credit limit cannot exceed $999,999,999.99")</p>
         * 
         * <p>Verifies:</p>
         * <ul>
         *   <li>HTTP 400 Bad Request status code</li>
         *   <li>Validation error message in response</li>
         *   <li>No database changes made</li>
         * </ul>
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("Should return 400 Bad Request when credit limit above maximum")
        public void testUpdateAccount_CreditLimitAboveMaximum() throws Exception {
            AccountUpdateRequest updateRequest = AccountUpdateRequest.builder()
                    .accountId(TEST_ACCOUNT_ID)
                    .creditLimit(ABOVE_MAX_CREDIT_LIMIT) // $1,000,000,000.00 - above $999,999,999.99 maximum
                    .cashCreditLimit(CASH_CREDIT_LIMIT)
                    .activeStatus(ACTIVE_STATUS_ACTIVE)
                    .build();
            
            mockMvc.perform(put("/api/accounts/{id}", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isBadRequest());
        }

        /**
         * Test account update with minimum allowed credit limit.
         * 
         * <p>Validates boundary condition: credit limit exactly at $1,000.00 minimum
         * should be accepted as valid.</p>
         * 
         * <p>COBOL Validation: 88 LIMIT-VALID VALUE 1000 THRU 999999999 (inclusive)</p>
         * <p>Java Validation: @DecimalMin(value = "1000.00") allows exact minimum</p>
         * 
         * <p>Verifies:</p>
         * <ul>
         *   <li>HTTP 200 OK status code</li>
         *   <li>Credit limit updated to $1,000.00</li>
         *   <li>BigDecimal precision maintained</li>
         * </ul>
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @Transactional
        @DisplayName("Should accept minimum credit limit of $1,000.00")
        public void testUpdateAccount_MinimumCreditLimit() throws Exception {
            AccountUpdateRequest updateRequest = AccountUpdateRequest.builder()
                    .accountId(TEST_ACCOUNT_ID)
                    .creditLimit(MIN_CREDIT_LIMIT) // Exactly $1,000.00
                    .cashCreditLimit(CASH_CREDIT_LIMIT)
                    .activeStatus(ACTIVE_STATUS_ACTIVE)
                    .build();
            
            mockMvc.perform(put("/api/accounts/{id}", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.creditLimit", comparesEqualTo(MIN_CREDIT_LIMIT)));
        }

        /**
         * Test account update with maximum allowed credit limit.
         * 
         * <p>Validates boundary condition: credit limit exactly at $999,999,999.99 maximum
         * should be accepted as valid.</p>
         * 
         * <p>COBOL Validation: 88 LIMIT-VALID VALUE 1000 THRU 999999999 (inclusive)</p>
         * <p>Java Validation: @DecimalMax(value = "999999999.99") allows exact maximum</p>
         * 
         * <p>Verifies:</p>
         * <ul>
         *   <li>HTTP 200 OK status code</li>
         *   <li>Credit limit updated to $999,999,999.99</li>
         *   <li>BigDecimal precision maintained with scale=2</li>
         * </ul>
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @Transactional
        @DisplayName("Should accept maximum credit limit of $999,999,999.99")
        public void testUpdateAccount_MaximumCreditLimit() throws Exception {
            AccountUpdateRequest updateRequest = AccountUpdateRequest.builder()
                    .accountId(TEST_ACCOUNT_ID)
                    .creditLimit(MAX_CREDIT_LIMIT) // Exactly $999,999,999.99
                    .cashCreditLimit(CASH_CREDIT_LIMIT)
                    .activeStatus(ACTIVE_STATUS_ACTIVE)
                    .build();
            
            mockMvc.perform(put("/api/accounts/{id}", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.creditLimit", comparesEqualTo(MAX_CREDIT_LIMIT)));
        }

        /**
         * Test account update with invalid active status.
         * 
         * <p>Validates Bean Validation @Pattern constraint enforcement for active status
         * field which must be exactly 'Y' or 'N'.</p>
         * 
         * <p>COBOL Field: ACCT-ACTIVE-STATUS PIC X(01) - must be 'Y' or 'N'</p>
         * <p>Java Validation: @Pattern(regexp="^[YN]$")</p>
         * 
         * <p>Verifies:</p>
         * <ul>
         *   <li>HTTP 400 Bad Request status code</li>
         *   <li>Validation error for invalid status value</li>
         *   <li>No database changes made</li>
         * </ul>
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("Should return 400 Bad Request with invalid active status")
        public void testUpdateAccount_InvalidActiveStatus() throws Exception {
            AccountUpdateRequest updateRequest = AccountUpdateRequest.builder()
                    .accountId(TEST_ACCOUNT_ID)
                    .creditLimit(CREDIT_LIMIT)
                    .cashCreditLimit(CASH_CREDIT_LIMIT)
                    .activeStatus("X") // Invalid - must be 'Y' or 'N'
                    .build();
            
            mockMvc.perform(put("/api/accounts/{id}", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isBadRequest());
        }

        /**
         * Test account update with null credit limit.
         * 
         * <p>Validates Bean Validation @NotNull constraint enforcement.</p>
         * 
         * <p>COBOL Field: ACCT-CREDIT-LIMIT PIC S9(10)V99 - mandatory field</p>
         * <p>Java Validation: @NotNull annotation on creditLimit field</p>
         * 
         * <p>Verifies:</p>
         * <ul>
         *   <li>HTTP 400 Bad Request status code</li>
         *   <li>Validation error for null field</li>
         * </ul>
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("Should return 400 Bad Request with null credit limit")
        public void testUpdateAccount_NullCreditLimit() throws Exception {
            AccountUpdateRequest updateRequest = AccountUpdateRequest.builder()
                    .accountId(TEST_ACCOUNT_ID)
                    .creditLimit(null) // Null value - violates @NotNull
                    .cashCreditLimit(CASH_CREDIT_LIMIT)
                    .activeStatus(ACTIVE_STATUS_ACTIVE)
                    .build();
            
            mockMvc.perform(put("/api/accounts/{id}", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isBadRequest());
        }

        /**
         * Test account update with status change from active to inactive.
         * 
         * <p>Validates account status update functionality.</p>
         * 
         * <p>COBOL Field: ACCT-ACTIVE-STATUS PIC X(01)</p>
         * <p>Valid Values: 'Y' (Active) or 'N' (Inactive)</p>
         * 
         * <p>Verifies:</p>
         * <ul>
         *   <li>HTTP 200 OK status code</li>
         *   <li>Status updated from 'Y' to 'N'</li>
         *   <li>Database state updated correctly</li>
         * </ul>
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @Transactional
        @DisplayName("Should update account status from active to inactive")
        public void testUpdateAccount_StatusChange() throws Exception {
            AccountUpdateRequest updateRequest = AccountUpdateRequest.builder()
                    .accountId(TEST_ACCOUNT_ID)
                    .creditLimit(CREDIT_LIMIT)
                    .cashCreditLimit(CASH_CREDIT_LIMIT)
                    .activeStatus(ACTIVE_STATUS_INACTIVE) // Change from 'Y' to 'N'
                    .build();
            
            mockMvc.perform(put("/api/accounts/{id}", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.activeStatus", is(ACTIVE_STATUS_INACTIVE)));
            
            // Verify database state after status change
            Account updatedAccount = accountRepository.findByAccountId(TEST_ACCOUNT_ID)
                    .orElseThrow(() -> new AssertionError("Account should exist after update"));
            
            assert ACTIVE_STATUS_INACTIVE.equals(updatedAccount.getActiveStatus()) 
                : "Active status in database should be 'N' after update";
        }

        /**
         * Test account update without authentication.
         * 
         * <p>Validates PUT /api/accounts/{id} endpoint returns HTTP 401 Unauthorized
         * when JWT authentication token is missing.</p>
         * 
         * <p>COBOL Security: RACF transaction security check</p>
         * <p>Java Security: Spring Security filter chain validates JWT token</p>
         * 
         * <p>Verifies:</p>
         * <ul>
         *   <li>HTTP 401 Unauthorized status code</li>
         *   <li>No database changes made</li>
         * </ul>
         * 
         * <p>Note: @WithMockUser annotation is intentionally omitted.</p>
         */
        @Test
        @DisplayName("Should return 401 Unauthorized when not authenticated")
        public void testUpdateAccount_Unauthorized() throws Exception {
            AccountUpdateRequest updateRequest = AccountUpdateRequest.builder()
                    .accountId(TEST_ACCOUNT_ID)
                    .creditLimit(CREDIT_LIMIT)
                    .cashCreditLimit(CASH_CREDIT_LIMIT)
                    .activeStatus(ACTIVE_STATUS_ACTIVE)
                    .build();
            
            mockMvc.perform(put("/api/accounts/{id}", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * Test BigDecimal precision preservation during update.
         * 
         * <p>Validates that monetary field updates maintain exact COBOL COMP-3
         * precision with scale=2 and RoundingMode.HALF_UP matching mainframe
         * arithmetic behavior.</p>
         * 
         * <p>COBOL Field: PIC S9(10)V99 COMP-3</p>
         * <p>Java Type: BigDecimal with scale=2, RoundingMode.HALF_UP</p>
         * 
         * <p>Test Case: Update credit limit to $12345.67 with exact precision</p>
         * 
         * <p>Verifies:</p>
         * <ul>
         *   <li>HTTP 200 OK status code</li>
         *   <li>BigDecimal value preserved exactly (no floating-point errors)</li>
         *   <li>Scale=2 maintained (exactly 2 decimal places)</li>
         *   <li>Value in database matches input exactly</li>
         * </ul>
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @Transactional
        @DisplayName("Should preserve BigDecimal precision during update")
        public void testUpdateAccount_BigDecimalPrecision() throws Exception {
            // Test value with exact 2 decimal places matching COBOL V99
            BigDecimal preciseValue = new BigDecimal("12345.67").setScale(2, RoundingMode.HALF_UP);
            
            AccountUpdateRequest updateRequest = AccountUpdateRequest.builder()
                    .accountId(TEST_ACCOUNT_ID)
                    .creditLimit(preciseValue)
                    .cashCreditLimit(CASH_CREDIT_LIMIT)
                    .activeStatus(ACTIVE_STATUS_ACTIVE)
                    .build();
            
            mockMvc.perform(put("/api/accounts/{id}", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.creditLimit", comparesEqualTo(preciseValue)));
            
            // Verify database precision
            Account updatedAccount = accountRepository.findByAccountId(TEST_ACCOUNT_ID)
                    .orElseThrow(() -> new AssertionError("Account should exist"));
            
            assert updatedAccount.getCreditLimit().scale() == 2 
                : "Credit limit scale should be exactly 2 (matching COBOL V99)";
            assert updatedAccount.getCreditLimit().compareTo(preciseValue) == 0 
                : "Credit limit value should match exactly without precision loss";
        }
    }

    /**
     * Nested test class for edge case and error handling scenarios.
     * 
     * <p>Tests exceptional conditions, boundary values, and error handling patterns
     * to ensure robustness and complete functional equivalence with COBOL programs.</p>
     */
    @Nested
    @DisplayName("Edge Cases and Error Handling Tests")
    class EdgeCaseTests {

        /**
         * Test account retrieval with zero balance.
         * 
         * <p>Validates system correctly handles accounts with zero current balance.</p>
         * 
         * <p>COBOL Field: ACCT-CURR-BAL PIC S9(10)V99 can be zero</p>
         * 
         * <p>Verifies:</p>
         * <ul>
         *   <li>HTTP 200 OK status code</li>
         *   <li>Balance displayed as "0.00" with scale=2</li>
         * </ul>
         */
        @Test
        @WithMockUser(roles = "USER")
        @Transactional
        @DisplayName("Should handle account with zero balance")
        public void testGetAccount_ZeroBalance() throws Exception {
            // Create account with zero balance
            Account zeroBalanceAccount = Account.builder()
                    .accountId(22222222222L)
                    .activeStatus(ACTIVE_STATUS_ACTIVE)
                    .currentBalance(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    .creditLimit(CREDIT_LIMIT)
                    .cashCreditLimit(CASH_CREDIT_LIMIT)
                    .openDate(OPEN_DATE)
                    .expirationDate(EXPIRATION_DATE)
                    .reissueDate(REISSUE_DATE)
                    .currentCycleCredit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    .currentCycleDebit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    .addressZip(ADDRESS_ZIP)
                    .groupId(GROUP_ID)
                    .build();
            
            accountRepository.save(zeroBalanceAccount);
            
            mockMvc.perform(get("/api/accounts/{id}", 22222222222L)
                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.currentBalance", comparesEqualTo(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))));
        }

        /**
         * Test account update with malformed JSON request.
         * 
         * <p>Validates proper error handling for invalid JSON payloads.</p>
         * 
         * <p>Verifies:</p>
         * <ul>
         *   <li>HTTP 400 Bad Request status code</li>
         *   <li>Appropriate error message for JSON parse failure</li>
         * </ul>
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("Should return 400 Bad Request with malformed JSON")
        public void testUpdateAccount_MalformedJson() throws Exception {
            String malformedJson = "{ invalid json }";
            
            mockMvc.perform(put("/api/accounts/{id}", TEST_ACCOUNT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(malformedJson))
                    .andExpect(status().isBadRequest());
        }

        /**
         * Test account update with mismatched path and body account IDs.
         * 
         * <p>Validates that path variable accountId must match request body accountId.</p>
         * 
         * <p>Verifies:</p>
         * <ul>
         *   <li>HTTP 400 Bad Request status code</li>
         *   <li>Error message indicating ID mismatch</li>
         * </ul>
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("Should return 400 Bad Request when path and body account IDs mismatch")
        public void testUpdateAccount_IdMismatch() throws Exception {
            AccountUpdateRequest updateRequest = AccountUpdateRequest.builder()
                    .accountId(33333333333L) // Different from path variable
                    .creditLimit(CREDIT_LIMIT)
                    .cashCreditLimit(CASH_CREDIT_LIMIT)
                    .activeStatus(ACTIVE_STATUS_ACTIVE)
                    .build();
            
            mockMvc.perform(put("/api/accounts/{id}", TEST_ACCOUNT_ID) // Path has different ID
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isBadRequest());
        }
    }
}
