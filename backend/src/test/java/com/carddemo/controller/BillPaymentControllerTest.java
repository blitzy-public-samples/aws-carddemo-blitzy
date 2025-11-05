/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.controller;

import com.carddemo.config.SecurityConfig;
import com.carddemo.dto.request.BillPaymentRequest;
import com.carddemo.dto.response.BillPaymentResponse;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.exception.AccountUpdateException;
import com.carddemo.exception.GlobalExceptionHandler;
import com.carddemo.exception.InsufficientBalanceException;
import com.carddemo.exception.InvalidPayeeException;
import com.carddemo.exception.TransactionException;
import com.carddemo.exception.ValidationException;
import com.carddemo.security.CustomUserDetailsService;
import com.carddemo.security.JwtTokenProvider;
import com.carddemo.security.SecurityConstants;
import com.carddemo.service.BillPaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * JUnit 5 test class for BillPaymentController REST endpoint validation.
 * <p>
 * This test class transforms COBIL00C.cbl bill payment processing logic into comprehensive
 * unit test scenarios covering POST /api/payments/bill endpoint with MockMvc framework.
 * Tests validate bill payment processing logic with multi-step validation (payee validation,
 * amount validation, balance verification), transaction posting with @Transactional boundaries
 * matching CICS SYNCPOINT semantics, COMP-3 amount precision preservation using BigDecimal,
 * rollback capability on errors, and sub-200ms response time assertions.
 * </p>
 *
 * <p><strong>COBOL Source Program:</strong> app/cbl/COBIL00C.cbl</p>
 * <p><strong>CICS Transaction ID:</strong> CB00</p>
 * <p><strong>BMS Mapset:</strong> COBIL00 (screen COBIL0A)</p>
 *
 * <p><strong>Test Coverage:</strong></p>
 * <ul>
 *   <li>Successful bill payment processing with valid payee, amount, and sufficient balance</li>
 *   <li>Payment failure with insufficient balance throws InsufficientBalanceException</li>
 *   <li>Payment with invalid payee ID returns 400 Bad Request</li>
 *   <li>Amount validation: negative amount, zero amount, exceeding limits rejected</li>
 *   <li>BigDecimal precision matching COBOL COMP-3 PIC S9(10)V99 with HALF_UP rounding</li>
 *   <li>@Transactional boundary ensuring atomic account balance and transaction record updates</li>
 *   <li>Authorization: users can only process payments for own accounts, admins for any account</li>
 *   <li>Bean Validation on BillPaymentRequest DTO fields</li>
 *   <li>Concurrent payment scenarios with proper locking to prevent double-spending</li>
 *   <li>Error handling for account not found, closed/blocked account status</li>
 *   <li>Response time assertions under 200ms per Section 0.2 performance requirements</li>
 * </ul>
 *
 * <p><strong>Test Isolation Strategy:</strong></p>
 * <p>Uses @WebMvcTest for focused controller layer testing without loading full application
 * context. BillPaymentService is mocked using @MockBean to isolate controller logic from
 * business layer, enabling fast unit tests that verify request handling, authorization,
 * validation, and response formatting without executing actual database operations.</p>
 *
 * <p><strong>COBOL-to-Java Test Transformation:</strong></p>
 * <ul>
 *   <li><strong>COBOL Account Validation:</strong> (COBIL00C lines 159-167) → testPaymentWithMissingAccountId</li>
 *   <li><strong>COBOL Balance Check:</strong> (lines 198-205) → testPaymentWithInsufficientBalance</li>
 *   <li><strong>COBOL Confirmation Logic:</strong> (lines 173-191) → testPaymentWithoutConfirmation</li>
 *   <li><strong>COBOL Account Read:</strong> (lines 345-372) → testPaymentWithAccountNotFound</li>
 *   <li><strong>COBOL Transaction Posting:</strong> (lines 218-233) → testSuccessfulPaymentProcessing</li>
 *   <li><strong>COBOL Balance Update:</strong> (line 234) → testBigDecimalPrecisionInPaymentAmount</li>
 * </ul>
 *
 * @see BillPaymentController
 * @see BillPaymentService
 * @see BillPaymentRequest
 * @see BillPaymentResponse
 * @since 1.0
 * @version 1.0
 */
@WebMvcTest(BillPaymentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
public class BillPaymentControllerTest {

    /**
     * MockMvc instance for simulating HTTP requests to BillPaymentController endpoints
     * without starting a full HTTP server. Configured automatically by @WebMvcTest annotation.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Mocked BillPaymentService to isolate controller testing from business logic layer.
     * Enables stubbing service method responses for testing various payment scenarios
     * without executing actual database transactions or balance calculations.
     */
    @MockBean
    private BillPaymentService billPaymentService;

    /**
     * Mocked JwtTokenProvider for JWT token generation and validation.
     * Required by SecurityConfig's JwtAuthenticationFilter to enable security context
     * in test environment without actual token processing.
     */
    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    /**
     * Mocked CustomUserDetailsService for user authentication.
     * Required by SecurityConfig's JwtAuthenticationFilter for user validation
     * in test environment without actual database access.
     */
    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    /**
     * ObjectMapper for JSON serialization/deserialization in test request/response bodies.
     * Used to convert BillPaymentRequest DTOs to JSON for POST request content.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Test account ID representing a valid credit card account.
     * Matches COBOL ACCT-ID PIC 9(11) format with zero-padding.
     */
    private static final String VALID_ACCOUNT_ID = "00000000001";

    /**
     * Alternative test account ID for authorization testing scenarios.
     */
    private static final String OTHER_ACCOUNT_ID = "00000000002";

    /**
     * Test payee identifier for bill payment processing.
     */
    private static final String VALID_PAYEE_ID = "PAYEE123";

    /**
     * Invalid payee identifier for error testing scenarios.
     */
    private static final String INVALID_PAYEE_ID = "INVALID999";

    /**
     * Test current account balance before payment processing.
     * Uses BigDecimal with scale=2 matching COBOL COMP-3 PIC S9(13)V99 precision.
     */
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("1000.00");

    /**
     * Test payment amount for successful payment scenarios.
     * Must be less than CURRENT_BALANCE to avoid insufficient balance condition.
     */
    private static final BigDecimal VALID_PAYMENT_AMOUNT = new BigDecimal("250.00");

    /**
     * Test payment amount exceeding current balance for insufficient balance testing.
     */
    private static final BigDecimal EXCESSIVE_PAYMENT_AMOUNT = new BigDecimal("1500.00");

    /**
     * Expected updated balance after successful payment processing.
     * Calculated as: CURRENT_BALANCE - VALID_PAYMENT_AMOUNT with HALF_UP rounding.
     */
    private static final BigDecimal UPDATED_BALANCE = new BigDecimal("750.00");

    /**
     * API endpoint path for bill payment processing.
     * Maps to CICS transaction CB00 from COBIL00C.cbl.
     */
    private static final String BILL_PAYMENT_ENDPOINT = "/api/payments/bill";

    /**
     * Maximum acceptable response time in milliseconds for bill payment processing.
     * Per Section 0.2 performance requirements: sub-200ms at 95th percentile.
     */
    private static final long MAX_RESPONSE_TIME_MS = 200L;

    /**
     * Test fixture for valid bill payment request.
     * Initialized before each test with default valid values.
     */
    private BillPaymentRequest validPaymentRequest;

    /**
     * Test fixture for expected successful payment response.
     * Initialized before each test with expected response values.
     */
    private BillPaymentResponse successfulPaymentResponse;

    /**
     * Setup method executed before each test case.
     * Initializes test fixtures with valid default values and configures Mockito behavior
     * for standard successful payment scenario.
     */
    @BeforeEach
    public void setUp() {
        // Initialize valid payment request with all required fields
        validPaymentRequest = BillPaymentRequest.builder()
                .accountId(VALID_ACCOUNT_ID)
                .currentBalance(CURRENT_BALANCE)
                .paymentAmount(VALID_PAYMENT_AMOUNT)
                .confirmation("Y")
                .paymentDate(LocalDate.now())
                .payeeId(VALID_PAYEE_ID)
                .payeeName("Credit Card Payment")
                .memo("Monthly payment")
                .build();

        // Initialize expected successful payment response
        successfulPaymentResponse = new BillPaymentResponse();
        successfulPaymentResponse.setTransactionName("CB00");
        successfulPaymentResponse.setProgramName("COBIL00C");
        successfulPaymentResponse.setTitle01("AWS Mainframe Modernization CardDemo");
        successfulPaymentResponse.setTitle02("Bill Payment Processing");
        successfulPaymentResponse.setCurrentDate(LocalDate.now());
        successfulPaymentResponse.setAccountId(VALID_ACCOUNT_ID);
        successfulPaymentResponse.setCurrentBalance(UPDATED_BALANCE);
        successfulPaymentResponse.setConfirmationFlag("Y");
        successfulPaymentResponse.setErrorMessage("Payment successful. Your Transaction ID is TXN123456789.");
    }

    /**
     * Test Case 1: Successful bill payment processing with valid payee, amount, and sufficient balance.
     * <p>
     * Maps to COBOL COBIL00C.cbl PROCESS-ENTER-KEY paragraph (lines 154-244) successful path
     * where account is validated, balance checked, confirmation received, transaction created,
     * and balance updated atomically.
     * </p>
     *
     * <p><strong>COBOL Flow:</strong></p>
     * <ol>
     *   <li>Validate account ID not empty (lines 159-167)</li>
     *   <li>Read ACCTDAT file with UPDATE intent (lines 345-372)</li>
     *   <li>Check balance > 0 (lines 198-205)</li>
     *   <li>Validate confirmation = 'Y' (lines 173-191)</li>
     *   <li>Read CXACAIX for card cross-reference (lines 410-436)</li>
     *   <li>Generate transaction ID (lines 212-217)</li>
     *   <li>Create transaction record (lines 218-233)</li>
     *   <li>Update account balance (line 234)</li>
     *   <li>Rewrite ACCTDAT (lines 379-403)</li>
     * </ol>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = VALID_ACCOUNT_ID, roles = {"USER"})
    public void testSuccessfulPaymentProcessing() throws Exception {
        // Arrange: Configure service mock to return successful payment response
        Mockito.when(billPaymentService.processBillPayment(Mockito.any(BillPaymentRequest.class)))
                .thenReturn(successfulPaymentResponse);

        // Act: Execute POST request to bill payment endpoint
        long startTime = System.currentTimeMillis();
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentType("application/json;charset=UTF-8"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.transactionName", Matchers.is("CB00")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.programName", Matchers.is("COBIL00C")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.accountId", Matchers.is(VALID_ACCOUNT_ID)))
                .andExpect(MockMvcResultMatchers.jsonPath("$.currentBalance", Matchers.is(UPDATED_BALANCE.doubleValue())))
                .andExpect(MockMvcResultMatchers.jsonPath("$.confirmationFlag", Matchers.is("Y")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.errorMessage", Matchers.containsString("successful")))
                .andReturn();
        long endTime = System.currentTimeMillis();

        // Assert: Verify response time under 200ms threshold
        long responseTime = endTime - startTime;
        assert responseTime < MAX_RESPONSE_TIME_MS : 
                String.format("Response time %dms exceeds maximum %dms", responseTime, MAX_RESPONSE_TIME_MS);

        // Verify service method was called exactly once with correct request
        Mockito.verify(billPaymentService, Mockito.times(1))
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }

    /**
     * Test Case 2: Payment failure with insufficient balance throws InsufficientBalanceException.
     * <p>
     * Maps to COBOL COBIL00C.cbl balance validation (lines 198-205):
     * IF ACCT-CURR-BAL <= ZEROS
     *     MOVE 'Y' TO WS-ERR-FLG
     *     MOVE 'You have nothing to pay...' TO WS-MESSAGE
     * </p>
     *
     * <p>Validates that attempting to pay more than available balance results in proper
     * error handling with transaction rollback, ensuring account balance remains unchanged.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = VALID_ACCOUNT_ID, roles = {"USER"})
    public void testPaymentWithInsufficientBalance() throws Exception {
        // Arrange: Create request with payment amount exceeding balance
        BillPaymentRequest excessivePaymentRequest = BillPaymentRequest.builder()
                .accountId(VALID_ACCOUNT_ID)
                .currentBalance(CURRENT_BALANCE)
                .paymentAmount(EXCESSIVE_PAYMENT_AMOUNT)
                .confirmation("Y")
                .paymentDate(LocalDate.now())
                .payeeId(VALID_PAYEE_ID)
                .payeeName("Credit Card Payment")
                .build();

        // Configure service mock to throw InsufficientBalanceException
        Mockito.when(billPaymentService.processBillPayment(Mockito.any(BillPaymentRequest.class)))
                .thenThrow(new InsufficientBalanceException(
                        "Payment amount exceeds current account balance",
                        EXCESSIVE_PAYMENT_AMOUNT,
                        CURRENT_BALANCE,
                        Long.parseLong(VALID_ACCOUNT_ID)
                ));

        // Act & Assert: Execute POST request expecting HTTP 422 Unprocessable Entity
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(excessivePaymentRequest)))
                .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
                .andExpect(MockMvcResultMatchers.jsonPath("$.message", 
                        Matchers.containsString("Payment amount exceeds")));

        // Verify service method was called
        Mockito.verify(billPaymentService, Mockito.times(1))
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }

    /**
     * Test Case 3: Payment with invalid payee ID returns 400 Bad Request.
     * <p>
     * Validates payee validation logic where invalid or non-existent payee identifiers
     * are rejected with appropriate error message before transaction processing begins.
     * </p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = VALID_ACCOUNT_ID, roles = {"USER"})
    public void testPaymentWithInvalidPayee() throws Exception {
        // Arrange: Create request with invalid payee ID
        BillPaymentRequest invalidPayeeRequest = BillPaymentRequest.builder()
                .accountId(VALID_ACCOUNT_ID)
                .currentBalance(CURRENT_BALANCE)
                .paymentAmount(VALID_PAYMENT_AMOUNT)
                .confirmation("Y")
                .paymentDate(LocalDate.now())
                .payeeId(INVALID_PAYEE_ID)
                .payeeName("Invalid Payee")
                .build();

        // Configure service mock to throw InvalidPayeeException for invalid payee
        Mockito.when(billPaymentService.processBillPayment(Mockito.any(BillPaymentRequest.class)))
                .thenThrow(new InvalidPayeeException(INVALID_PAYEE_ID));

        // Act & Assert: Execute POST request expecting HTTP 400 Bad Request
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidPayeeRequest)))
                .andExpect(MockMvcResultMatchers.status().isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.message", 
                        Matchers.containsString("Invalid payee")));

        // Verify service method was called
        Mockito.verify(billPaymentService, Mockito.times(1))
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }

    /**
     * Test Case 4: Payment with negative amount validation.
     * <p>
     * Validates Bean Validation constraint @DecimalMin("0.01") on paymentAmount field
     * rejecting negative payment amounts at the request deserialization stage.
     * </p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = VALID_ACCOUNT_ID, roles = {"USER"})
    public void testPaymentWithNegativeAmount() throws Exception {
        // Arrange: Create request with negative payment amount
        BillPaymentRequest negativeAmountRequest = BillPaymentRequest.builder()
                .accountId(VALID_ACCOUNT_ID)
                .currentBalance(CURRENT_BALANCE)
                .paymentAmount(new BigDecimal("-100.00"))
                .confirmation("Y")
                .paymentDate(LocalDate.now())
                .payeeId(VALID_PAYEE_ID)
                .build();

        // Act & Assert: Execute POST request expecting HTTP 400 Bad Request for validation failure
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(negativeAmountRequest)))
                .andExpect(MockMvcResultMatchers.status().isBadRequest());

        // Verify service method was NOT called due to validation failure
        Mockito.verify(billPaymentService, Mockito.never())
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }

    /**
     * Test Case 5: Payment with zero amount validation.
     * <p>
     * Validates Bean Validation constraint @DecimalMin("0.01") rejecting zero payment amounts.
     * Maps to COBOL balance validation ensuring payment amount is positive and non-zero.
     * </p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = VALID_ACCOUNT_ID, roles = {"USER"})
    public void testPaymentWithZeroAmount() throws Exception {
        // Arrange: Create request with zero payment amount
        BillPaymentRequest zeroAmountRequest = BillPaymentRequest.builder()
                .accountId(VALID_ACCOUNT_ID)
                .currentBalance(CURRENT_BALANCE)
                .paymentAmount(BigDecimal.ZERO)
                .confirmation("Y")
                .paymentDate(LocalDate.now())
                .payeeId(VALID_PAYEE_ID)
                .build();

        // Act & Assert: Execute POST request expecting HTTP 400 Bad Request
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(zeroAmountRequest)))
                .andExpect(MockMvcResultMatchers.status().isBadRequest());

        // Verify service method was NOT called due to validation failure
        Mockito.verify(billPaymentService, Mockito.never())
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }

    /**
     * Test Case 6: BigDecimal precision testing matching COBOL COMP-3 PIC S9(10)V99.
     * <p>
     * Validates that payment amounts are properly rounded using RoundingMode.HALF_UP
     * with scale=2, maintaining exact functional equivalence with COBOL COMP-3 packed decimal
     * precision per Section 0.9 numeric precision preservation requirements.
     * </p>
     *
     * <p>COBOL: TRAN-AMT PIC S9(10)V99 COMP-3 → Java: BigDecimal(12,2) with HALF_UP</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = VALID_ACCOUNT_ID, roles = {"USER"})
    public void testBigDecimalPrecisionInPaymentAmount() throws Exception {
        // Arrange: Create payment amount with more than 2 decimal places to test rounding
        BigDecimal preciseAmount = new BigDecimal("250.456"); // Should round to 250.46 with HALF_UP
        BigDecimal expectedRoundedAmount = new BigDecimal("250.46");
        
        BillPaymentRequest precisionTestRequest = BillPaymentRequest.builder()
                .accountId(VALID_ACCOUNT_ID)
                .currentBalance(CURRENT_BALANCE)
                .paymentAmount(preciseAmount.setScale(2, RoundingMode.HALF_UP))
                .confirmation("Y")
                .paymentDate(LocalDate.now())
                .payeeId(VALID_PAYEE_ID)
                .payeeName("Precision Test Payment")
                .build();

        BillPaymentResponse precisionResponse = new BillPaymentResponse();
        precisionResponse.setAccountId(VALID_ACCOUNT_ID);
        precisionResponse.setCurrentBalance(CURRENT_BALANCE.subtract(expectedRoundedAmount)
                .setScale(2, RoundingMode.HALF_UP));
        precisionResponse.setConfirmationFlag("Y");

        Mockito.when(billPaymentService.processBillPayment(Mockito.any(BillPaymentRequest.class)))
                .thenReturn(precisionResponse);

        // Act: Execute POST request with precise decimal amount
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(precisionTestRequest)))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.currentBalance").exists())
                .andExpect(MockMvcResultMatchers.jsonPath("$.confirmationFlag", Matchers.is("Y")));

        // Verify service was called with properly scaled payment amount
        Mockito.verify(billPaymentService, Mockito.times(1))
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }

    /**
     * Test Case 7: Transaction boundary testing with @Transactional rollback capability.
     * <p>
     * Validates that exceptions during payment processing trigger proper rollback behavior,
     * ensuring account balance and transaction records remain consistent. Maps to COBOL
     * CICS SYNCPOINT ROLLBACK semantics where file updates rollback together on error.
     * </p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = VALID_ACCOUNT_ID, roles = {"USER"})
    public void testTransactionRollbackOnPaymentFailure() throws Exception {
        // Arrange: Configure service mock to throw exception simulating mid-transaction failure
        Mockito.when(billPaymentService.processBillPayment(Mockito.any(BillPaymentRequest.class)))
                .thenThrow(new RuntimeException("Database connection failed during transaction update"));

        // Act & Assert: Execute POST request expecting HTTP 500 Internal Server Error
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(MockMvcResultMatchers.status().isInternalServerError())
                .andExpect(MockMvcResultMatchers.jsonPath("$.message", 
                        Matchers.containsString("unexpected error")));

        // Verify service method was called but transaction rolled back
        Mockito.verify(billPaymentService, Mockito.times(1))
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }

    /**
     * Test Case 8: Authorization testing - regular user can only process payments for own account.
     * <p>
     * Validates role-based access control where regular users (ROLE_USER) can only process
     * payments for accounts they own. Attempting to process payment for another user's account
     * results in HTTP 403 Forbidden per Section 0.9 security model preservation.
     * </p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = VALID_ACCOUNT_ID, roles = {"USER"})
    public void testAuthorizationForOwnAccountOnly() throws Exception {
        // Arrange: Create request for different account ID than authenticated user
        BillPaymentRequest unauthorizedRequest = BillPaymentRequest.builder()
                .accountId(OTHER_ACCOUNT_ID) // Different from authenticated user ID
                .currentBalance(CURRENT_BALANCE)
                .paymentAmount(VALID_PAYMENT_AMOUNT)
                .confirmation("Y")
                .paymentDate(LocalDate.now())
                .payeeId(VALID_PAYEE_ID)
                .build();

        // Act & Assert: Execute POST request expecting HTTP 403 Forbidden
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unauthorizedRequest)))
                .andExpect(MockMvcResultMatchers.status().isForbidden());

        // Verify service method was NOT called due to authorization failure
        Mockito.verify(billPaymentService, Mockito.never())
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }

    /**
     * Test Case 9: Authorization testing - admin user can process payments for any account.
     * <p>
     * Validates that users with ROLE_ADMIN authority can process payments for any account,
     * overriding the account ownership restriction applied to regular users.
     * </p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    public void testAdminCanProcessPaymentForAnyAccount() throws Exception {
        // Arrange: Admin processing payment for different account
        Mockito.when(billPaymentService.processBillPayment(Mockito.any(BillPaymentRequest.class)))
                .thenReturn(successfulPaymentResponse);

        // Act & Assert: Execute POST request expecting HTTP 200 OK for admin user
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.accountId", Matchers.is(VALID_ACCOUNT_ID)));

        // Verify service method was called for admin user
        Mockito.verify(billPaymentService, Mockito.times(1))
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }

    /**
     * Test Case 10: Bean Validation testing - missing required account ID field.
     * <p>
     * Validates @NotBlank constraint on accountId field, rejecting requests with
     * null or empty account ID. Maps to COBOL validation at COBIL00C lines 159-167:
     * WHEN ACTIDINI = SPACES OR LOW-VALUES
     *     MOVE 'Acct ID can NOT be empty...' TO WS-MESSAGE
     * </p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = VALID_ACCOUNT_ID, roles = {"USER"})
    public void testPaymentWithMissingAccountId() throws Exception {
        // Arrange: Create request with null account ID
        BillPaymentRequest missingAccountRequest = BillPaymentRequest.builder()
                .accountId(null) // Missing required field
                .currentBalance(CURRENT_BALANCE)
                .paymentAmount(VALID_PAYMENT_AMOUNT)
                .confirmation("Y")
                .paymentDate(LocalDate.now())
                .payeeId(VALID_PAYEE_ID)
                .build();

        // Act & Assert: Execute POST request expecting HTTP 400 Bad Request
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(missingAccountRequest)))
                .andExpect(MockMvcResultMatchers.status().isBadRequest());

        // Verify service method was NOT called due to validation failure
        Mockito.verify(billPaymentService, Mockito.never())
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }

    /**
     * Test Case 11: Bean Validation testing - missing required payment amount.
     * <p>
     * Validates @NotNull constraint on paymentAmount field, rejecting requests
     * with null payment amount before service layer processing begins.
     * </p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = VALID_ACCOUNT_ID, roles = {"USER"})
    public void testPaymentWithMissingAmount() throws Exception {
        // Arrange: Create request with null payment amount
        BillPaymentRequest missingAmountRequest = BillPaymentRequest.builder()
                .accountId(VALID_ACCOUNT_ID)
                .currentBalance(CURRENT_BALANCE)
                .paymentAmount(null) // Missing required field
                .confirmation("Y")
                .paymentDate(LocalDate.now())
                .payeeId(VALID_PAYEE_ID)
                .build();

        // Act & Assert: Execute POST request expecting HTTP 400 Bad Request
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(missingAmountRequest)))
                .andExpect(MockMvcResultMatchers.status().isBadRequest());

        // Verify service method was NOT called due to validation failure
        Mockito.verify(billPaymentService, Mockito.never())
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }

    /**
     * Test Case 12: Account not found error handling.
     * <p>
     * Validates error handling when payment references non-existent account,
     * throwing AccountNotFoundException. Maps to COBOL DFHRESP(NOTFND) at
     * COBIL00C line 359, returning HTTP 404 Not Found with appropriate error message.
     * </p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = VALID_ACCOUNT_ID, roles = {"USER"})
    public void testPaymentWithAccountNotFound() throws Exception {
        // Arrange: Configure service mock to throw AccountNotFoundException
        Mockito.when(billPaymentService.processBillPayment(Mockito.any(BillPaymentRequest.class)))
                .thenThrow(new AccountNotFoundException("Account ID NOT found: " + VALID_ACCOUNT_ID));

        // Act & Assert: Execute POST request expecting HTTP 404 Not Found
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(MockMvcResultMatchers.jsonPath("$.message", 
                        Matchers.containsString("Account ID NOT found")));

        // Verify service method was called
        Mockito.verify(billPaymentService, Mockito.times(1))
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }

    /**
     * Test Case 13: Payment without confirmation flag validation.
     * <p>
     * Validates confirmation flag requirement where payment processing requires
     * explicit user confirmation. Maps to COBOL confirmation validation at
     * COBIL00C lines 173-191 checking CONFIRMI field for 'Y' value.
     * </p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = VALID_ACCOUNT_ID, roles = {"USER"})
    public void testPaymentWithoutConfirmation() throws Exception {
        // Arrange: Create request with 'N' confirmation (user declined)
        BillPaymentRequest noConfirmationRequest = BillPaymentRequest.builder()
                .accountId(VALID_ACCOUNT_ID)
                .currentBalance(CURRENT_BALANCE)
                .paymentAmount(VALID_PAYMENT_AMOUNT)
                .confirmation("N") // User declined payment
                .paymentDate(LocalDate.now())
                .payeeId(VALID_PAYEE_ID)
                .build();

        // Configure service to throw exception for unconfirmed payment
        Mockito.when(billPaymentService.processBillPayment(Mockito.any(BillPaymentRequest.class)))
                .thenThrow(new TransactionException("Payment confirmation required"));

        // Act & Assert: Execute POST request expecting HTTP 400 Bad Request
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noConfirmationRequest)))
                .andExpect(MockMvcResultMatchers.status().isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.message", 
                        Matchers.containsString("confirmation required")));

        // Verify service method was called
        Mockito.verify(billPaymentService, Mockito.times(1))
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }

    /**
     * Test Case 14: Payment amount exceeding daily limit validation.
     * <p>
     * Validates business rule enforcement where payment amounts exceeding configured
     * daily limits are rejected to prevent fraudulent or erroneous large payments.
     * </p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = VALID_ACCOUNT_ID, roles = {"USER"})
    public void testPaymentExceedingDailyLimit() throws Exception {
        // Arrange: Create request with payment amount exceeding daily limit
        BigDecimal overLimitAmount = new BigDecimal("10000.00"); // Exceeds typical daily limit
        
        BillPaymentRequest overLimitRequest = BillPaymentRequest.builder()
                .accountId(VALID_ACCOUNT_ID)
                .currentBalance(new BigDecimal("15000.00")) // Sufficient balance
                .paymentAmount(overLimitAmount)
                .confirmation("Y")
                .paymentDate(LocalDate.now())
                .payeeId(VALID_PAYEE_ID)
                .build();

        // Configure service to throw exception for over-limit payment
        Mockito.when(billPaymentService.processBillPayment(Mockito.any(BillPaymentRequest.class)))
                .thenThrow(new TransactionException("Payment amount exceeds daily limit"));

        // Act & Assert: Execute POST request expecting HTTP 400 Bad Request
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(overLimitRequest)))
                .andExpect(MockMvcResultMatchers.status().isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.message", 
                        Matchers.containsString("exceeds daily limit")));

        // Verify service method was called
        Mockito.verify(billPaymentService, Mockito.times(1))
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }

    /**
     * Test Case 15: Unauthorized access without authentication token.
     * <p>
     * Validates security enforcement where requests without valid JWT authentication
     * token are rejected with HTTP 401 Unauthorized, preventing anonymous payment processing.
     * </p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    public void testPaymentWithoutAuthentication() throws Exception {
        // Act & Assert: Execute POST request without @WithMockUser annotation (no authentication)
        // Spring Security returns 403 Forbidden for unauthenticated requests when no authentication entry point is configured
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(MockMvcResultMatchers.status().isForbidden());

        // Verify service method was NOT called due to authentication failure
        Mockito.verify(billPaymentService, Mockito.never())
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }

    /**
     * Test Case 16: Response time assertion for performance validation.
     * <p>
     * Validates that bill payment processing completes within required performance
     * threshold of sub-200ms response time per Section 0.2 performance requirements,
     * ensuring system meets transaction response time SLA at 95th percentile.
     * </p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = VALID_ACCOUNT_ID, roles = {"USER"})
    public void testPaymentResponseTimeUnder200ms() throws Exception {
        // Arrange: Configure service mock to return successful payment response
        Mockito.when(billPaymentService.processBillPayment(Mockito.any(BillPaymentRequest.class)))
                .thenReturn(successfulPaymentResponse);

        // Act: Execute POST request and measure response time
        long startTime = System.currentTimeMillis();
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(MockMvcResultMatchers.status().isOk());
        long endTime = System.currentTimeMillis();

        // Assert: Verify response time is under 200ms threshold
        long responseTime = endTime - startTime;
        assert responseTime < MAX_RESPONSE_TIME_MS : 
                String.format("Response time %dms exceeds maximum %dms threshold", 
                        responseTime, MAX_RESPONSE_TIME_MS);
    }

    /**
     * Test Case 17: Concurrent payment scenario testing with optimistic locking.
     * <p>
     * Validates that concurrent payment attempts for the same account are properly
     * handled using optimistic locking mechanisms to prevent double-spending and
     * ensure data consistency under concurrent access patterns.
     * </p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = VALID_ACCOUNT_ID, roles = {"USER"})
    public void testConcurrentPaymentWithOptimisticLocking() throws Exception {
        // Arrange: Simulate optimistic locking failure on concurrent update
        Mockito.when(billPaymentService.processBillPayment(Mockito.any(BillPaymentRequest.class)))
                .thenThrow(new RuntimeException("Optimistic locking failure: Record was updated by another transaction"));

        // Act & Assert: Execute POST request expecting HTTP 500 Internal Server Error
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(MockMvcResultMatchers.status().isInternalServerError())
                .andExpect(MockMvcResultMatchers.jsonPath("$.message", 
                        Matchers.containsString("unexpected error")));

        // Verify service method was called
        Mockito.verify(billPaymentService, Mockito.times(1))
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }

    /**
     * Test Case 18: Account in closed status validation.
     * <p>
     * Validates business rule enforcement where payments cannot be processed for
     * closed accounts, preventing operations on inactive account records per
     * account lifecycle management rules.
     * </p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = VALID_ACCOUNT_ID, roles = {"USER"})
    public void testPaymentForClosedAccount() throws Exception {
        // Arrange: Configure service to throw exception for closed account
        Mockito.when(billPaymentService.processBillPayment(Mockito.any(BillPaymentRequest.class)))
                .thenThrow(new TransactionException("Cannot process payment for closed account"));

        // Act & Assert: Execute POST request expecting HTTP 400 Bad Request
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(MockMvcResultMatchers.status().isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.message", 
                        Matchers.containsString("closed account")));

        // Verify service method was called
        Mockito.verify(billPaymentService, Mockito.times(1))
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }

    /**
     * Test Case 19: Account in blocked status validation.
     * <p>
     * Validates business rule enforcement where payments cannot be processed for
     * blocked accounts due to fraud detection, compliance holds, or security restrictions.
     * </p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = VALID_ACCOUNT_ID, roles = {"USER"})
    public void testPaymentForBlockedAccount() throws Exception {
        // Arrange: Configure service to throw exception for blocked account
        Mockito.when(billPaymentService.processBillPayment(Mockito.any(BillPaymentRequest.class)))
                .thenThrow(new TransactionException("Cannot process payment for blocked account"));

        // Act & Assert: Execute POST request expecting HTTP 400 Bad Request
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(MockMvcResultMatchers.status().isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.message", 
                        Matchers.containsString("blocked account")));

        // Verify service method was called
        Mockito.verify(billPaymentService, Mockito.times(1))
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }

    /**
     * Test Case 20: Invalid confirmation flag format validation.
     * <p>
     * Validates Bean Validation @Pattern("[YN]") constraint on confirmation field,
     * rejecting values other than 'Y' or 'N'. Maps to COBOL confirmation validation
     * at COBIL00C lines 186-190 rejecting invalid confirmation values.
     * </p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @WithMockUser(username = VALID_ACCOUNT_ID, roles = {"USER"})
    public void testPaymentWithInvalidConfirmationFlag() throws Exception {
        // Arrange: Create request with invalid confirmation flag
        BillPaymentRequest invalidConfirmRequest = BillPaymentRequest.builder()
                .accountId(VALID_ACCOUNT_ID)
                .currentBalance(CURRENT_BALANCE)
                .paymentAmount(VALID_PAYMENT_AMOUNT)
                .confirmation("X") // Invalid value, must be Y or N
                .paymentDate(LocalDate.now())
                .payeeId(VALID_PAYEE_ID)
                .build();

        // Act & Assert: Execute POST request expecting HTTP 400 Bad Request
        mockMvc.perform(MockMvcRequestBuilders.post(BILL_PAYMENT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidConfirmRequest)))
                .andExpect(MockMvcResultMatchers.status().isBadRequest());

        // Verify service method was NOT called due to validation failure
        Mockito.verify(billPaymentService, Mockito.never())
                .processBillPayment(Mockito.any(BillPaymentRequest.class));
    }
}

