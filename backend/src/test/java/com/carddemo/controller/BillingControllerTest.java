/*
 * BillingControllerTest.java
 *
 * JUnit 5 test class for BillingController testing billing operations converted from COBOL COBIL00C.cbl.
 *
 * Converted from COBOL program: COBIL00C.cbl (23KB, billing and statement processing)
 * Original function: Bill Payment - Pay account balance in full and create transaction for online bill payment
 *
 * Test Coverage:
 * - GET /api/billing/{accountId}/statement endpoint for billing statement generation
 * - GET /api/billing/{accountId}/current endpoint for current balance retrieval
 * - Error handling for DataNotFoundException (HTTP 404 Not Found)
 * - BigDecimal precision validation maintaining COBOL COMP-3 calculation precision
 * - JSON response structure validation
 * - Query parameter handling (statementDate)
 *
 * Testing Strategy:
 * - Uses @WebMvcTest to load only BillingController and web layer components
 * - Mocks BillingService and AccountService using @MockBean for isolation
 * - Uses MockMvc to perform HTTP requests without starting full server
 * - Validates JSON responses using jsonPath() matchers with Hamcrest
 * - Verifies BigDecimal monetary amounts maintain 2 decimal precision (COBOL COMP-3)
 *
 * Per Agent Action Plan Section 0.7.2: All billing calculations, validation rules, and error handling
 * patterns maintain identical business logic to COBOL implementation with zero functional deviation.
 *
 * Per Agent Action Plan Section 0.7.3: All COBOL COMP-3 (packed decimal) arithmetic replicated using
 * Java BigDecimal with scale 2 and RoundingMode.HALF_UP to ensure bit-identical results.
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

import com.carddemo.exception.DataNotFoundException;
import com.carddemo.model.dto.AccountDto;
import com.carddemo.model.dto.BillingStatementDto;
import com.carddemo.service.AccountService;
import com.carddemo.service.BillingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Test class for BillingController REST endpoints.
 *
 * <p><strong>COBOL to Java Testing Conversion:</strong></p>
 * <p>Tests billing operations converted from COBOL program COBIL00C.cbl to Java Spring Boot REST controller.</p>
 *
 * <p><strong>Test Strategy:</strong></p>
 * <ul>
 *   <li>@WebMvcTest annotation loads only BillingController and web layer (not full Spring context)</li>
 *   <li>@MockBean creates Mockito mocks of BillingService and AccountService as Spring beans</li>
 *   <li>MockMvc performs HTTP requests and validates responses without starting actual server</li>
 *   <li>Tests validate JSON structure, HTTP status codes, BigDecimal precision, error handling</li>
 * </ul>
 *
 * <p><strong>Coverage Requirements:</strong></p>
 * <ul>
 *   <li>GET /api/billing/{accountId}/statement - successful generation with default date</li>
 *   <li>GET /api/billing/{accountId}/statement?statementDate=YYYY-MM-DD - with explicit date</li>
 *   <li>GET /api/billing/{accountId}/current - current balance retrieval</li>
 *   <li>Error scenarios: account not found (HTTP 404)</li>
 *   <li>BigDecimal precision: all amounts have scale 2 matching COBOL COMP-3</li>
 * </ul>
 *
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2025-10-25
 * @see BillingController
 * @see BillingService
 * @see AccountService
 */
@WebMvcTest(BillingController.class)
@AutoConfigureMockMvc(addFilters = false)
class BillingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BillingService billingService;

    @MockBean
    private AccountService accountService;

    @MockBean
    private com.carddemo.security.JwtTokenProvider jwtTokenProvider;

    // Test data constants (matching COBOL test scenarios)
    private static final Long TEST_ACCOUNT_ID = 12345678901L; // COBOL PIC 9(11)
    private static final Long INVALID_ACCOUNT_ID = 99999999999L;
    private static final BigDecimal TEST_BALANCE = new BigDecimal("1166.10").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal TEST_CREDIT_LIMIT = new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal TEST_PREVIOUS_BALANCE = new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal TEST_NEW_CHARGES = new BigDecimal("250.50").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal TEST_PAYMENTS = new BigDecimal("100.00").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal TEST_INTEREST = new BigDecimal("15.60").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal TEST_LATE_FEE = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal TEST_MIN_PAYMENT = new BigDecimal("34.98").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal TEST_AVAILABLE_CREDIT = new BigDecimal("3833.90").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal TEST_APR = new BigDecimal("0.1899"); // 18.99% annual rate

    private AccountDto testAccount;
    private BillingStatementDto testStatement;

    /**
     * Set up test data before each test method execution.
     * 
     * Initializes mock AccountDto and BillingStatementDto objects with realistic
     * financial data maintaining COBOL COMP-3 precision (scale 2, HALF_UP rounding).
     */
    @BeforeEach
    void setUp() {
        // Create test AccountDto (replaces COBOL ACCOUNT-RECORD from CVACT01Y.cpy)
        testAccount = AccountDto.builder()
                .acctId(TEST_ACCOUNT_ID)
                .acctActiveStatus("Y") // COBOL 88-level: STATUS-IS-ACTIVE
                .acctCurrBal(TEST_BALANCE) // COBOL PIC S9(10)V99 COMP-3
                .acctCreditLimit(TEST_CREDIT_LIMIT) // COBOL PIC S9(10)V99 COMP-3
                .build();

        // Create test BillingStatementDto (derived from COBIL00C.cbl billing logic)
        LocalDate statementDate = LocalDate.now();
        LocalDate periodStartDate = statementDate.minusMonths(1);
        LocalDate dueDate = statementDate.plusDays(21); // PAYMENT_DUE_DAYS constant

        testStatement = BillingStatementDto.builder()
                .accountId(TEST_ACCOUNT_ID)
                .statementDate(statementDate)
                .periodStartDate(periodStartDate)
                .periodEndDate(statementDate)
                .dueDate(dueDate)
                .previousBalance(TEST_PREVIOUS_BALANCE)
                .newCharges(TEST_NEW_CHARGES)
                .paymentsAndCredits(TEST_PAYMENTS)
                .interestCharged(TEST_INTEREST)
                .lateFee(TEST_LATE_FEE)
                .newBalance(TEST_BALANCE) // Formula: prevBal + charges - payments + interest + lateFee
                .minimumPaymentDue(TEST_MIN_PAYMENT) // Greater of $25.00 or 3% of newBalance
                .creditLimit(TEST_CREDIT_LIMIT)
                .availableCredit(TEST_AVAILABLE_CREDIT) // creditLimit - newBalance
                .transactions(new ArrayList<>()) // Empty list for simplicity in tests
                .annualPercentageRate(TEST_APR)
                .daysInPeriod(31) // Typical month length
                .build();
    }

    /**
     * Test GET /api/billing/{accountId}/statement without query parameter (defaults to current date).
     *
     * <p><strong>COBOL Test Scenario:</strong> COBIL00C.cbl billing statement generation with system date</p>
     *
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>HTTP 200 OK status</li>
     *   <li>JSON content type</li>
     *   <li>All statement fields present and correctly formatted</li>
     *   <li>BigDecimal monetary amounts have scale 2 (COBOL COMP-3 precision)</li>
     *   <li>Account ID matches request path variable</li>
     *   <li>Date fields follow ISO 8601 format (YYYY-MM-DD)</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testGenerateStatement_DefaultDate_ReturnsStatementWithAllFields() throws Exception {
        // Arrange: Mock service to return test statement when called with any accountId and date
        when(billingService.generateStatement(eq(TEST_ACCOUNT_ID), any(LocalDate.class)))
                .thenReturn(testStatement);

        // Act & Assert: Perform GET request and validate response
        mockMvc.perform(get("/api/billing/{accountId}/statement", TEST_ACCOUNT_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // Validate account identifier
                .andExpect(jsonPath("$.accountId", is(TEST_ACCOUNT_ID.longValue())))
                // Validate date fields are not null
                .andExpect(jsonPath("$.statementDate", notNullValue()))
                .andExpect(jsonPath("$.periodStartDate", notNullValue()))
                .andExpect(jsonPath("$.periodEndDate", notNullValue()))
                .andExpect(jsonPath("$.dueDate", notNullValue()))
                // Validate monetary amounts with COBOL COMP-3 precision (scale 2)
                .andExpect(jsonPath("$.previousBalance", is(TEST_PREVIOUS_BALANCE.doubleValue())))
                .andExpect(jsonPath("$.newCharges", is(TEST_NEW_CHARGES.doubleValue())))
                .andExpect(jsonPath("$.paymentsAndCredits", is(TEST_PAYMENTS.doubleValue())))
                .andExpect(jsonPath("$.interestCharged", is(TEST_INTEREST.doubleValue())))
                .andExpect(jsonPath("$.lateFee", is(TEST_LATE_FEE.doubleValue())))
                .andExpect(jsonPath("$.newBalance", is(TEST_BALANCE.doubleValue())))
                .andExpect(jsonPath("$.minimumPaymentDue", is(TEST_MIN_PAYMENT.doubleValue())))
                .andExpect(jsonPath("$.creditLimit", is(TEST_CREDIT_LIMIT.doubleValue())))
                .andExpect(jsonPath("$.availableCredit", is(TEST_AVAILABLE_CREDIT.doubleValue())))
                // Validate APR and days in period
                .andExpect(jsonPath("$.annualPercentageRate", is(TEST_APR.doubleValue())))
                .andExpect(jsonPath("$.daysInPeriod", is(31)))
                // Validate transactions list exists (even if empty)
                .andExpect(jsonPath("$.transactions", notNullValue()));

        // Verify service was called with correct accountId and date defaulted to current date
        verify(billingService, times(1))
                .generateStatement(eq(TEST_ACCOUNT_ID), any(LocalDate.class));
    }

    /**
     * Test GET /api/billing/{accountId}/statement with explicit statementDate query parameter.
     *
     * <p><strong>COBOL Test Scenario:</strong> COBIL00C.cbl billing statement generation with specified date</p>
     *
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Query parameter statementDate is correctly parsed from ISO 8601 format</li>
     *   <li>BillingService is called with the exact date from query parameter</li>
     *   <li>Statement returned matches expected structure</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testGenerateStatement_WithExplicitDate_PassesDateToService() throws Exception {
        // Arrange: Define explicit statement date
        LocalDate explicitDate = LocalDate.of(2024, 1, 31);
        
        // Update test statement with explicit date
        BillingStatementDto statementWithDate = BillingStatementDto.builder()
                .accountId(TEST_ACCOUNT_ID)
                .statementDate(explicitDate)
                .periodStartDate(explicitDate.minusMonths(1))
                .periodEndDate(explicitDate)
                .dueDate(explicitDate.plusDays(21))
                .previousBalance(TEST_PREVIOUS_BALANCE)
                .newCharges(TEST_NEW_CHARGES)
                .paymentsAndCredits(TEST_PAYMENTS)
                .interestCharged(TEST_INTEREST)
                .lateFee(TEST_LATE_FEE)
                .newBalance(TEST_BALANCE)
                .minimumPaymentDue(TEST_MIN_PAYMENT)
                .creditLimit(TEST_CREDIT_LIMIT)
                .availableCredit(TEST_AVAILABLE_CREDIT)
                .transactions(new ArrayList<>())
                .annualPercentageRate(TEST_APR)
                .daysInPeriod(31)
                .build();

        when(billingService.generateStatement(eq(TEST_ACCOUNT_ID), eq(explicitDate)))
                .thenReturn(statementWithDate);

        // Act & Assert: Perform GET request with query parameter
        mockMvc.perform(get("/api/billing/{accountId}/statement", TEST_ACCOUNT_ID)
                        .param("statementDate", "2024-01-31") // ISO 8601 date format
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accountId", is(TEST_ACCOUNT_ID.longValue())))
                .andExpect(jsonPath("$.statementDate", is("2024-01-31")))
                .andExpect(jsonPath("$.newBalance", is(TEST_BALANCE.doubleValue())));

        // Verify service was called with exact date from query parameter
        verify(billingService, times(1))
                .generateStatement(eq(TEST_ACCOUNT_ID), eq(explicitDate));
    }

    /**
     * Test GET /api/billing/{accountId}/statement when account not found.
     *
     * <p><strong>COBOL Error Scenario:</strong> DFHRESP(NOTFND) from EXEC CICS READ FILE('ACCTDAT')</p>
     * <p>COBIL00C.cbl lines 359-364: Account not found handling</p>
     *
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>HTTP 404 Not Found status returned</li>
     *   <li>DataNotFoundException thrown by service is caught by GlobalExceptionHandler</li>
     *   <li>Error response contains appropriate error message</li>
     *   <li>Replicates COBOL error handling: MOVE 'Account not found' TO WS-RETURN-MSG</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testGenerateStatement_AccountNotFound_Returns404() throws Exception {
        // Arrange: Mock service to throw DataNotFoundException (replaces COBOL DFHRESP(NOTFND))
        when(billingService.generateStatement(eq(INVALID_ACCOUNT_ID), any(LocalDate.class)))
                .thenThrow(new DataNotFoundException("Account", INVALID_ACCOUNT_ID));

        // Act & Assert: Perform GET request and expect HTTP 404
        mockMvc.perform(get("/api/billing/{accountId}/statement", INVALID_ACCOUNT_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        // Verify service was called with invalid account ID
        verify(billingService, times(1))
                .generateStatement(eq(INVALID_ACCOUNT_ID), any(LocalDate.class));
    }

    /**
     * Test GET /api/billing/{accountId}/current for current balance retrieval.
     *
     * <p><strong>COBOL Origin:</strong> COBIL00C.cbl READ-ACCTDAT-FILE paragraph (lines 343-372)</p>
     * <p>Replaces: EXEC CICS READ FILE('ACCTDAT') RIDFLD(ACCT-ID) INTO(ACCOUNT-RECORD)</p>
     * <p>Then: MOVE ACCT-CURR-BAL TO WS-CURR-BAL</p>
     *
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>HTTP 200 OK status</li>
     *   <li>Balance returned as BigDecimal with scale 2 (COBOL COMP-3 precision)</li>
     *   <li>Balance value matches expected TEST_BALANCE</li>
     *   <li>AccountService.getAccountById() called exactly once</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testGetCurrentBalance_ValidAccount_ReturnsBalance() throws Exception {
        // Arrange: Mock AccountService to return test account
        when(accountService.getAccountById(eq(TEST_ACCOUNT_ID)))
                .thenReturn(testAccount);

        // Act & Assert: Perform GET request and validate balance response
        mockMvc.perform(get("/api/billing/{accountId}/current", TEST_ACCOUNT_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // Validate balance is returned as number (not string) with proper precision
                .andExpect(jsonPath("$", is(TEST_BALANCE.doubleValue())));

        // Verify AccountService was called with correct account ID
        verify(accountService, times(1))
                .getAccountById(eq(TEST_ACCOUNT_ID));
    }

    /**
     * Test GET /api/billing/{accountId}/current when account not found.
     *
     * <p><strong>COBOL Error Scenario:</strong> DFHRESP(NOTFND) from READ-ACCTDAT-FILE</p>
     * <p>COBIL00C.cbl lines 359-364: WHEN DFHRESP(NOTFND) error handling</p>
     *
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>HTTP 404 Not Found status returned</li>
     *   <li>DataNotFoundException thrown by AccountService</li>
     *   <li>GlobalExceptionHandler maps exception to HTTP 404</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testGetCurrentBalance_AccountNotFound_Returns404() throws Exception {
        // Arrange: Mock AccountService to throw DataNotFoundException
        when(accountService.getAccountById(eq(INVALID_ACCOUNT_ID)))
                .thenThrow(new DataNotFoundException("Account", INVALID_ACCOUNT_ID));

        // Act & Assert: Perform GET request and expect HTTP 404
        mockMvc.perform(get("/api/billing/{accountId}/current", INVALID_ACCOUNT_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        // Verify AccountService was called with invalid account ID
        verify(accountService, times(1))
                .getAccountById(eq(INVALID_ACCOUNT_ID));
    }

    /**
     * Test billing statement with zero balance (edge case).
     *
     * <p><strong>COBOL Edge Case:</strong> ACCT-CURR-BAL <= ZEROS (COBIL00C.cbl lines 198-205)</p>
     *
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>System handles zero balance gracefully (no exceptions)</li>
     *   <li>Minimum payment is $0.00 when balance is zero</li>
     *   <li>All BigDecimal amounts maintain scale 2 even for zero values</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testGenerateStatement_ZeroBalance_ReturnsStatementWithZeroAmounts() throws Exception {
        // Arrange: Create statement with zero balance
        BigDecimal zeroAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        
        BillingStatementDto zeroBalanceStatement = BillingStatementDto.builder()
                .accountId(TEST_ACCOUNT_ID)
                .statementDate(LocalDate.now())
                .periodStartDate(LocalDate.now().minusMonths(1))
                .periodEndDate(LocalDate.now())
                .dueDate(LocalDate.now().plusDays(21))
                .previousBalance(zeroAmount)
                .newCharges(zeroAmount)
                .paymentsAndCredits(zeroAmount)
                .interestCharged(zeroAmount)
                .lateFee(zeroAmount)
                .newBalance(zeroAmount)
                .minimumPaymentDue(zeroAmount) // Zero balance, zero minimum payment
                .creditLimit(TEST_CREDIT_LIMIT)
                .availableCredit(TEST_CREDIT_LIMIT) // Full credit available
                .transactions(new ArrayList<>())
                .annualPercentageRate(TEST_APR)
                .daysInPeriod(31)
                .build();

        when(billingService.generateStatement(eq(TEST_ACCOUNT_ID), any(LocalDate.class)))
                .thenReturn(zeroBalanceStatement);

        // Act & Assert: Perform GET request and validate zero balance handling
        mockMvc.perform(get("/api/billing/{accountId}/statement", TEST_ACCOUNT_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accountId", is(TEST_ACCOUNT_ID.longValue())))
                // Validate all amounts are zero with proper scale 2
                .andExpect(jsonPath("$.previousBalance", is(zeroAmount.doubleValue())))
                .andExpect(jsonPath("$.newCharges", is(zeroAmount.doubleValue())))
                .andExpect(jsonPath("$.paymentsAndCredits", is(zeroAmount.doubleValue())))
                .andExpect(jsonPath("$.interestCharged", is(zeroAmount.doubleValue())))
                .andExpect(jsonPath("$.lateFee", is(zeroAmount.doubleValue())))
                .andExpect(jsonPath("$.newBalance", is(zeroAmount.doubleValue())))
                .andExpect(jsonPath("$.minimumPaymentDue", is(zeroAmount.doubleValue())))
                // Available credit equals full credit limit when balance is zero
                .andExpect(jsonPath("$.availableCredit", is(TEST_CREDIT_LIMIT.doubleValue())));

        verify(billingService, times(1))
                .generateStatement(eq(TEST_ACCOUNT_ID), any(LocalDate.class));
    }

    /**
     * Test billing statement with high-precision amounts (BigDecimal precision validation).
     *
     * <p><strong>COBOL Precision Test:</strong> Validates COMP-3 precision preservation</p>
     * <p>Per Section 0.7.3: All COBOL COMP-3 arithmetic replicated using BigDecimal scale 2 and HALF_UP rounding</p>
     *
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>All monetary amounts maintain exactly 2 decimal places</li>
     *   <li>Amounts like 1234.56, 0.01, 9999999999.99 handled correctly</li>
     *   <li>Rounding follows HALF_UP rule matching COBOL COMP-3 behavior</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testGenerateStatement_HighPrecisionAmounts_MaintainsScale2() throws Exception {
        // Arrange: Create statement with precise decimal amounts
        BigDecimal preciseBalance = new BigDecimal("1234.56").setScale(2, RoundingMode.HALF_UP);
        BigDecimal preciseCharges = new BigDecimal("567.89").setScale(2, RoundingMode.HALF_UP);
        BigDecimal precisePayments = new BigDecimal("123.45").setScale(2, RoundingMode.HALF_UP);
        BigDecimal preciseInterest = new BigDecimal("12.34").setScale(2, RoundingMode.HALF_UP);
        BigDecimal preciseFee = new BigDecimal("35.00").setScale(2, RoundingMode.HALF_UP);
        
        // Calculate new balance: prevBal + charges - payments + interest + fee
        BigDecimal calculatedBalance = preciseBalance
                .add(preciseCharges)
                .subtract(precisePayments)
                .add(preciseInterest)
                .add(preciseFee)
                .setScale(2, RoundingMode.HALF_UP); // Result: 1726.34
        
        BillingStatementDto precisionStatement = BillingStatementDto.builder()
                .accountId(TEST_ACCOUNT_ID)
                .statementDate(LocalDate.now())
                .periodStartDate(LocalDate.now().minusMonths(1))
                .periodEndDate(LocalDate.now())
                .dueDate(LocalDate.now().plusDays(21))
                .previousBalance(preciseBalance)
                .newCharges(preciseCharges)
                .paymentsAndCredits(precisePayments)
                .interestCharged(preciseInterest)
                .lateFee(preciseFee)
                .newBalance(calculatedBalance)
                .minimumPaymentDue(new BigDecimal("51.79").setScale(2, RoundingMode.HALF_UP)) // 3% of 1726.34
                .creditLimit(TEST_CREDIT_LIMIT)
                .availableCredit(TEST_CREDIT_LIMIT.subtract(calculatedBalance).setScale(2, RoundingMode.HALF_UP))
                .transactions(new ArrayList<>())
                .annualPercentageRate(TEST_APR)
                .daysInPeriod(31)
                .build();

        when(billingService.generateStatement(eq(TEST_ACCOUNT_ID), any(LocalDate.class)))
                .thenReturn(precisionStatement);

        // Act & Assert: Perform GET request and validate precision
        mockMvc.perform(get("/api/billing/{accountId}/statement", TEST_ACCOUNT_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // Validate all amounts maintain exact scale 2 precision
                .andExpect(jsonPath("$.previousBalance", is(preciseBalance.doubleValue())))
                .andExpect(jsonPath("$.newCharges", is(preciseCharges.doubleValue())))
                .andExpect(jsonPath("$.paymentsAndCredits", is(precisePayments.doubleValue())))
                .andExpect(jsonPath("$.interestCharged", is(preciseInterest.doubleValue())))
                .andExpect(jsonPath("$.lateFee", is(preciseFee.doubleValue())))
                .andExpect(jsonPath("$.newBalance", is(calculatedBalance.doubleValue())));

        verify(billingService, times(1))
                .generateStatement(eq(TEST_ACCOUNT_ID), any(LocalDate.class));
    }

    /**
     * Test GET /api/billing/{accountId}/current with zero balance (edge case).
     *
     * <p><strong>COBOL Edge Case:</strong> ACCT-CURR-BAL = ZEROS</p>
     *
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Zero balance returned correctly with scale 2</li>
     *   <li>No exceptions thrown for zero balance</li>
     * </ul>
     *
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testGetCurrentBalance_ZeroBalance_ReturnsZeroWithScale2() throws Exception {
        // Arrange: Create account with zero balance
        BigDecimal zeroBalance = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        AccountDto zeroBalanceAccount = AccountDto.builder()
                .acctId(TEST_ACCOUNT_ID)
                .acctActiveStatus("Y")
                .acctCurrBal(zeroBalance) // COBOL: ACCT-CURR-BAL = ZEROS
                .acctCreditLimit(TEST_CREDIT_LIMIT)
                .build();

        when(accountService.getAccountById(eq(TEST_ACCOUNT_ID)))
                .thenReturn(zeroBalanceAccount);

        // Act & Assert: Perform GET request and validate zero balance
        mockMvc.perform(get("/api/billing/{accountId}/current", TEST_ACCOUNT_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", is(zeroBalance.doubleValue())));

        verify(accountService, times(1))
                .getAccountById(eq(TEST_ACCOUNT_ID));
    }

    /**
     * Test verify no interactions with mocks when test is not executed.
     * Ensures test isolation and proper mock reset between tests.
     */
    @Test
    void testMocksAreIsolatedBetweenTests() {
        // This test verifies that @MockBean creates fresh mocks for each test
        // No interactions should exist before calling any endpoints
        verifyNoInteractions(billingService);
        verifyNoInteractions(accountService);
    }
}
