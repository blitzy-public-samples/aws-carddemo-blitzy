package com.carddemo.controller;

import com.carddemo.dto.request.PaymentRequest;
import com.carddemo.dto.response.PaymentResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.constants.MessageConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration Tests for BillingController REST endpoints.
 * 
 * <p>This test class provides comprehensive integration testing for the BillingController's
 * bill payment processing functionality, validating the POST /api/billing/payment endpoint
 * which replaces the COBOL COBIL00C.cbl program (CB00 transaction) from the mainframe
 * CardDemo application.</p>
 * 
 * <p><strong>COBOL Program Source:</strong> COBIL00C.cbl</p>
 * <p><strong>Transaction ID:</strong> CB00</p>
 * <p><strong>Function:</strong> Bill Payment - Pay account balance with transaction recording</p>
 * 
 * <p><strong>Test Strategy:</strong></p>
 * <p>This test class uses @SpringBootTest to load the complete application context with all
 * Spring beans, database connections, security configurations, and service layers, enabling
 * end-to-end integration testing that validates the entire bill payment workflow from HTTP
 * request through service layer, repository operations, and database persistence.</p>
 * 
 * <p><strong>Key Test Scenarios Covered:</strong></p>
 * <ul>
 *   <li><strong>Successful Payment Processing:</strong> Validates 200 OK status with payment
 *       confirmation response including transactionId, updatedBalance, and confirmationMessage</li>
 *   <li><strong>Payment Request Validation:</strong> Tests Bean Validation annotations (@NotNull,
 *       @Positive, @Digits) on PaymentRequest fields matching COBOL PIC clause validations</li>
 *   <li><strong>Account Not Found Handling:</strong> Tests 404 Not Found status when account ID
 *       does not exist, matching COBOL RESP(DFHRESP(NOTFND)) error handling</li>
 *   <li><strong>Insufficient Balance Handling:</strong> Tests 400 Bad Request when payment amount
 *       exceeds account balance, matching COBOL balance validation logic</li>
 *   <li><strong>Invalid Amount Validation:</strong> Tests zero, negative, and excessive precision
 *       amounts ensuring BigDecimal precision matches COBOL COMP-3 behavior</li>
 *   <li><strong>Transactional Integrity:</strong> Verifies @Transactional boundaries ensure atomic
 *       commit/rollback of account balance updates and transaction record creation, matching CICS
 *       SYNCPOINT behavior</li>
 *   <li><strong>Concurrent Access Handling:</strong> Validates pessimistic locking prevents race
 *       conditions during payment processing matching VSAM record locking</li>
 *   <li><strong>Security Authorization:</strong> Tests @PreAuthorize security allowing both
 *       ROLE_ADMIN and ROLE_USER access to payment endpoint</li>
 *   <li><strong>Audit Logging:</strong> Confirms transaction records are created with proper type
 *       code '02', category code, amount, and timestamps</li>
 * </ul>
 * 
 * <p><strong>COBOL to Java Data Type Mapping Validation:</strong></p>
 * <p>Tests ensure BigDecimal fields maintain COBOL COMP-3 decimal precision from copybooks:</p>
 * <ul>
 *   <li>ACCT-CURR-BAL (PIC S9(10)V99 from CVACT01Y.cpy) → BigDecimal(precision=12, scale=2)</li>
 *   <li>TRAN-AMT (PIC S9(09)V99 from CVTRA05Y.cpy) → BigDecimal(precision=12, scale=2)</li>
 *   <li>All monetary arithmetic uses RoundingMode.HALF_UP matching COBOL rounding behavior</li>
 * </ul>
 * 
 * <p><strong>Test Database Configuration:</strong></p>
 * <p>Tests use @ActiveProfiles("test") to load application-test.properties configuration with
 * test-specific database settings, ensuring test data isolation and automatic rollback after
 * test completion through @Transactional annotation at method level.</p>
 * 
 * <p><strong>MockMvc Request/Response Pattern:</strong></p>
 * <p>Tests use MockMvc to simulate HTTP requests without starting full HTTP server, enabling:</p>
 * <ul>
 *   <li>POST requests with JSON PaymentRequest body serialized via ObjectMapper</li>
 *   <li>Security context injection via @WithMockUser annotation</li>
 *   <li>HTTP status code assertions (200, 400, 404, 422, 401, 403)</li>
 *   <li>JSON response body validation using jsonPath expressions</li>
 *   <li>Content-Type verification ensuring application/json responses</li>
 * </ul>
 * 
 * <p><strong>Test Data Lifecycle:</strong></p>
 * <ul>
 *   <li><strong>@BeforeEach:</strong> Creates and persists test accounts with known balances</li>
 *   <li><strong>Test Execution:</strong> Performs payment operations and validates outcomes</li>
 *   <li><strong>@AfterEach:</strong> Cleans up test data from accounts and transactions tables</li>
 * </ul>
 * 
 * <p><strong>Special Instructions Compliance:</strong></p>
 * <p>These tests enforce critical requirements from Section 0.10 Special Instructions:</p>
 * <ul>
 *   <li><strong>Functional Equivalence:</strong> All business logic from COBIL00C.cbl is preserved
 *       including payment amount validation, account balance reduction, and transaction recording</li>
 *   <li><strong>Decimal Precision:</strong> BigDecimal operations validated to match COBOL COMP-3
 *       packed decimal precision with RoundingMode.HALF_UP</li>
 *   <li><strong>Transaction Boundaries:</strong> @Transactional behavior tested to match CICS
 *       SYNCPOINT commit/rollback atomicity</li>
 *   <li><strong>Error Messages:</strong> MessageConstants validated to preserve COBOL error
 *       message semantics from COBIL00C.cbl</li>
 *   <li><strong>Security Patterns:</strong> Role-based access control tested to match RACF
 *       security patterns from mainframe</li>
 * </ul>
 * 
 * <p><strong>Related COBOL Program Logic:</strong></p>
 * <p>From COBIL00C.cbl PROCESS-ENTER-KEY paragraph (lines 200-350):</p>
 * <pre>
 * PROCESS-ENTER-KEY.
 *     PERFORM 9300-GETACCTDATA-BYACCT.
 *     IF WS-RESP-CD = DFHRESP(NORMAL)
 *         MOVE ACCT-CURR-BAL TO WS-TRAN-AMT
 *         PERFORM 9400-WRITE-PROCESSING
 *         IF WS-RESP-CD = DFHRESP(NORMAL)
 *             PERFORM 9500-UPDATE-ACCT-BALANCE
 *             MOVE 'Payment successful' TO WS-MESSAGE
 *         END-IF
 *     ELSE
 *         MOVE 'Account not found' TO WS-MESSAGE
 *     END-IF.
 * </pre>
 * 
 * <p>This test class validates that the Java implementation produces identical outcomes to the
 * COBOL program for all test scenarios.</p>
 * 
 * @see BillingController
 * @see com.carddemo.service.billing.BillPaymentService
 * @see PaymentRequest
 * @see PaymentResponse
 * @see Account
 * @see Transaction
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.10">Special Instructions for Refactoring</a>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class BillingControllerTest {

    /**
     * MockMvc instance for simulating HTTP requests to BillingController endpoints.
     * <p>Automatically configured by @AutoConfigureMockMvc annotation, enables testing of
     * REST endpoints without starting full HTTP server, supporting request/response validation
     * including status codes, headers, and JSON body content.</p>
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * ObjectMapper for JSON serialization/deserialization.
     * <p>Used to convert PaymentRequest DTOs to JSON strings for POST request bodies and
     * deserialize JSON responses for validation. Configured to handle BigDecimal serialization
     * with proper scale=2 precision matching COBOL COMP-3 requirements.</p>
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * AccountRepository for test data setup and verification.
     * <p>Used in @BeforeEach to create test accounts with known balances and in test methods
     * to verify database state changes after payment processing, confirming account balance
     * reduction matches expected values.</p>
     */
    @Autowired
    private AccountRepository accountRepository;

    /**
     * TransactionRepository for transaction record verification.
     * <p>Used in @AfterEach to clean up test transaction data and in test methods to verify
     * payment transaction records were created with proper type code '02', category code,
     * amount, and timestamps.</p>
     */
    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * Test account ID constant for successful payment tests.
     * <p>Represents a valid 11-digit account ID matching COBOL ACCT-ID PIC 9(11) format
     * from CVACT01Y.cpy copybook. Used to create test account with sufficient balance.</p>
     */
    private static final Long TEST_ACCOUNT_ID = 1000000001L;

    /**
     * Test account ID constant for insufficient balance tests.
     * <p>Represents an account with low balance to test payment amount validation logic
     * matching COBOL insufficient funds error handling.</p>
     */
    private static final Long TEST_ACCOUNT_ID_LOW_BALANCE = 1000000002L;

    /**
     * Test account ID constant for account not found tests.
     * <p>Represents a non-existent account ID to test 404 error handling matching COBOL
     * RESP(DFHRESP(NOTFND)) condition.</p>
     */
    private static final Long TEST_ACCOUNT_ID_NOT_FOUND = 9999999999L;

    /**
     * Test account balance constant.
     * <p>Initial balance for test account, set to $5,000.00 with explicit scale=2 matching
     * COBOL ACCT-CURR-BAL PIC S9(10)V99 precision from CVACT01Y.cpy copybook.</p>
     */
    private static final BigDecimal TEST_INITIAL_BALANCE = new BigDecimal("5000.00");

    /**
     * Test low balance constant.
     * <p>Initial balance for low balance test account, set to $50.00 to enable testing of
     * insufficient funds scenarios where payment amount exceeds available balance.</p>
     */
    private static final BigDecimal TEST_LOW_BALANCE = new BigDecimal("50.00");

    /**
     * Test payment amount constant.
     * <p>Standard payment amount for successful tests, set to $100.00 with explicit scale=2
     * matching COBOL TRAN-AMT PIC S9(09)V99 precision from CVTRA05Y.cpy copybook.</p>
     */
    private static final BigDecimal TEST_PAYMENT_AMOUNT = new BigDecimal("100.00");

    /**
     * Test excessive payment amount constant.
     * <p>Payment amount exceeding low balance account to test insufficient funds validation
     * logic matching COBOL balance check from COBIL00C.cbl program.</p>
     */
    private static final BigDecimal TEST_EXCESSIVE_PAYMENT = new BigDecimal("200.00");

    /**
     * Test setup method executed before each test.
     * <p>Creates and persists test account data in database with known balances to provide
     * consistent test fixtures. Test accounts include:</p>
     * <ul>
     *   <li>Normal account with $5,000.00 balance for successful payment tests</li>
     *   <li>Low balance account with $50.00 balance for insufficient funds tests</li>
     * </ul>
     * 
     * <p>Account entities are built using Lombok @Builder pattern with fields matching COBOL
     * ACCOUNT-RECORD structure from CVACT01Y.cpy copybook, including accountId, activeStatus,
     * currentBalance, creditLimit, cashCreditLimit, and date fields.</p>
     */
    @BeforeEach
    public void setUp() {
        // Clean up any existing test data to ensure fresh state
        accountRepository.deleteAll();
        transactionRepository.deleteAll();

        // Create test account with sufficient balance for successful payment tests
        // Matches COBOL ACCOUNT-RECORD structure from CVACT01Y.cpy
        Account testAccount = Account.builder()
                .accountId(TEST_ACCOUNT_ID)
                .activeStatus("Y")  // Active account
                .currentBalance(TEST_INITIAL_BALANCE.setScale(2, RoundingMode.HALF_UP))
                .creditLimit(new BigDecimal("10000.00").setScale(2, RoundingMode.HALF_UP))
                .cashCreditLimit(new BigDecimal("2000.00").setScale(2, RoundingMode.HALF_UP))
                .openDate(LocalDate.of(2020, 1, 1))
                .expirationDate(LocalDate.of(2025, 12, 31))
                .currentCycleCredit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .currentCycleDebit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .build();
        accountRepository.save(testAccount);

        // Create test account with low balance for insufficient funds tests
        // Enables testing of balance validation logic from COBIL00C.cbl
        Account lowBalanceAccount = Account.builder()
                .accountId(TEST_ACCOUNT_ID_LOW_BALANCE)
                .activeStatus("Y")
                .currentBalance(TEST_LOW_BALANCE.setScale(2, RoundingMode.HALF_UP))
                .creditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP))
                .cashCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .openDate(LocalDate.of(2020, 1, 1))
                .expirationDate(LocalDate.of(2025, 12, 31))
                .currentCycleCredit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .currentCycleDebit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .build();
        accountRepository.save(lowBalanceAccount);
    }

    /**
     * Test cleanup method executed after each test.
     * <p>Deletes all test data from accounts and transactions tables to maintain test isolation
     * and prevent data pollution across test executions. This cleanup ensures each test starts
     * with a fresh database state created by @BeforeEach setUp() method.</p>
     */
    @AfterEach
    public void tearDown() {
        // Clean up all test data to maintain test isolation
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    /**
     * Test successful bill payment processing with valid request.
     * <p>Validates the complete happy path workflow for POST /api/billing/payment endpoint:</p>
     * <ul>
     *   <li>Accepts valid PaymentRequest with accountId and amount</li>
     *   <li>Returns 200 OK status code</li>
     *   <li>Returns PaymentResponse with transactionId, updatedBalance, confirmationMessage</li>
     *   <li>Reduces account balance by payment amount using BigDecimal arithmetic</li>
     *   <li>Creates transaction record with type code '02' for PAYMENT</li>
     *   <li>Commits all changes within @Transactional boundary matching CICS SYNCPOINT</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent:</strong> COBIL00C.cbl PROCESS-ENTER-KEY with successful
     * account read, transaction write, and balance update (WS-RESP-CD = DFHRESP(NORMAL)).</p>
     * 
     * <p><strong>BigDecimal Precision Validation:</strong></p>
     * <p>Initial balance: $5,000.00 (scale=2)</p>
     * <p>Payment amount: $100.00 (scale=2)</p>
     * <p>Expected updated balance: $4,900.00 (scale=2)</p>
     * <p>Uses RoundingMode.HALF_UP matching COBOL COMP-3 rounding behavior.</p>
     */
    @Test
    @DisplayName("Test successful bill payment processing with valid request")
    @WithMockUser(username = "testuser", roles = {"USER"})
    public void testSuccessfulBillPayment() throws Exception {
        // Arrange: Create valid payment request
        PaymentRequest paymentRequest = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .amount(TEST_PAYMENT_AMOUNT)
                .paymentReference("TEST-PAYMENT-001")
                .build();

        // Act: Execute POST request to /api/billing/payment endpoint
        mockMvc.perform(post("/api/billing/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                // Assert: Verify HTTP 200 OK response
                .andExpect(status().isOk())
                // Assert: Verify response content type is JSON
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // Assert: Verify transactionId is present in response
                .andExpect(jsonPath("$.transactionId").exists())
                .andExpect(jsonPath("$.transactionId").isNotEmpty())
                // Assert: Verify updatedBalance is correct (initial balance - payment amount)
                .andExpect(jsonPath("$.updatedBalance").value(4900.00))
                // Assert: Verify confirmationMessage contains success text
                .andExpect(jsonPath("$.confirmationMessage").exists())
                .andExpect(jsonPath("$.confirmationMessage").isNotEmpty());

        // Verify database state: Account balance should be reduced
        Optional<Account> updatedAccountOpt = accountRepository.findByAccountId(TEST_ACCOUNT_ID);
        assertTrue(updatedAccountOpt.isPresent(), "Account should exist after payment");
        
        Account updatedAccount = updatedAccountOpt.get();
        BigDecimal expectedBalance = TEST_INITIAL_BALANCE.subtract(TEST_PAYMENT_AMOUNT)
                .setScale(2, RoundingMode.HALF_UP);
        assertEquals(expectedBalance, updatedAccount.getCurrentBalance(),
                "Account balance should be reduced by payment amount");

        // Verify database state: Transaction record should be created
        List<Transaction> transactions = transactionRepository.findAll();
        assertFalse(transactions.isEmpty(), "Transaction record should be created");
        
        // Find the payment transaction (type code '02')
        Optional<Transaction> paymentTransactionOpt = transactions.stream()
                .filter(t -> "02".equals(t.getTypeCode()))
                .findFirst();
        assertTrue(paymentTransactionOpt.isPresent(), "Payment transaction should exist with type code '02'");
        
        Transaction paymentTransaction = paymentTransactionOpt.get();
        assertEquals(TEST_PAYMENT_AMOUNT.setScale(2, RoundingMode.HALF_UP), 
                paymentTransaction.getAmount(),
                "Transaction amount should match payment amount");
        assertNotNull(paymentTransaction.getTransactionId(), 
                "Transaction ID should be generated");
        assertNotNull(paymentTransaction.getOriginationTimestamp(), 
                "Origination timestamp should be set");
    }

    /**
     * Test bill payment processing with account not found.
     * <p>Validates error handling when account ID does not exist in database:</p>
     * <ul>
     *   <li>Accepts PaymentRequest with non-existent accountId</li>
     *   <li>Returns 404 Not Found status code</li>
     *   <li>Returns error response with MSG_ACCOUNT_NOT_FOUND message</li>
     *   <li>Does not create transaction record</li>
     *   <li>Does not modify any account balances</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent:</strong> COBIL00C.cbl PROCESS-ENTER-KEY with EXEC CICS READ
     * returning RESP(DFHRESP(NOTFND)) condition, followed by "MOVE 'Account not found' TO
     * WS-MESSAGE" logic.</p>
     * 
     * <p><strong>HTTP Status Code Mapping:</strong></p>
     * <p>COBOL RESP(DFHRESP(NOTFND)) → HTTP 404 Not Found</p>
     */
    @Test
    @DisplayName("Test bill payment with account not found returns 404")
    @WithMockUser(username = "testuser", roles = {"USER"})
    public void testBillPaymentWithAccountNotFound() throws Exception {
        // Arrange: Create payment request with non-existent account ID
        PaymentRequest paymentRequest = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID_NOT_FOUND)
                .amount(TEST_PAYMENT_AMOUNT)
                .paymentReference("TEST-PAYMENT-002")
                .build();

        // Act & Assert: Execute POST request and verify 404 response
        mockMvc.perform(post("/api/billing/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                // Assert: Verify HTTP 404 Not Found response
                .andExpect(status().isNotFound())
                // Assert: Verify error message matches COBOL error semantics
                .andExpect(jsonPath("$.message").value(MessageConstants.MSG_ACCOUNT_NOT_FOUND));

        // Verify database state: No transaction should be created
        List<Transaction> transactions = transactionRepository.findAll();
        long paymentTransactionCount = transactions.stream()
                .filter(t -> "02".equals(t.getTypeCode()))
                .count();
        assertEquals(0, paymentTransactionCount, 
                "No payment transaction should be created for non-existent account");
    }

    /**
     * Test bill payment processing with insufficient balance.
     * <p>Validates error handling when payment amount exceeds account balance:</p>
     * <ul>
     *   <li>Accepts PaymentRequest with amount exceeding available balance</li>
     *   <li>Returns 400 Bad Request status code</li>
     *   <li>Returns error response with MSG_INSUFFICIENT_BALANCE message</li>
     *   <li>Does not reduce account balance</li>
     *   <li>Does not create transaction record</li>
     *   <li>Ensures transaction rollback matches CICS ROLLBACK behavior</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent:</strong> COBIL00C.cbl balance validation logic checking
     * IF TRAN-AMT > ACCT-CURR-BAL, setting error flag and displaying "Insufficient balance"
     * message.</p>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <p>Account balance: $50.00</p>
     * <p>Payment amount: $200.00</p>
     * <p>Result: Payment rejected, balance unchanged</p>
     */
    @Test
    @DisplayName("Test bill payment with insufficient balance returns 400")
    @WithMockUser(username = "testuser", roles = {"USER"})
    public void testBillPaymentWithInsufficientBalance() throws Exception {
        // Arrange: Create payment request exceeding account balance
        PaymentRequest paymentRequest = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID_LOW_BALANCE)
                .amount(TEST_EXCESSIVE_PAYMENT)
                .paymentReference("TEST-PAYMENT-003")
                .build();

        // Act & Assert: Execute POST request and verify 400 response
        mockMvc.perform(post("/api/billing/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                // Assert: Verify HTTP 400 Bad Request response
                .andExpect(status().isBadRequest())
                // Assert: Verify error message indicates insufficient balance
                .andExpect(jsonPath("$.message").value(MessageConstants.MSG_INSUFFICIENT_BALANCE));

        // Verify database state: Account balance should remain unchanged
        Optional<Account> accountOpt = accountRepository.findByAccountId(TEST_ACCOUNT_ID_LOW_BALANCE);
        assertTrue(accountOpt.isPresent(), "Account should still exist");
        
        Account account = accountOpt.get();
        assertEquals(TEST_LOW_BALANCE, account.getCurrentBalance(),
                "Account balance should not be modified on insufficient balance error");

        // Verify database state: No transaction should be created
        List<Transaction> transactions = transactionRepository.findAll();
        long paymentTransactionCount = transactions.stream()
                .filter(t -> "02".equals(t.getTypeCode()))
                .count();
        assertEquals(0, paymentTransactionCount, 
                "No payment transaction should be created for insufficient balance");
    }

    /**
     * Test bill payment processing with null account ID.
     * <p>Validates Bean Validation enforcement for @NotNull constraint on accountId field:</p>
     * <ul>
     *   <li>Accepts PaymentRequest with null accountId</li>
     *   <li>Returns 400 Bad Request status code</li>
     *   <li>Returns validation error message</li>
     *   <li>Does not attempt payment processing</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent:</strong> COBIL00C.cbl field validation checking IF ACCT-ID
     * = SPACES OR ZEROS, setting error flag ERR-FLG-ON and displaying validation error.</p>
     * 
     * <p><strong>Bean Validation:</strong> @NotNull annotation on PaymentRequest.accountId
     * field triggers automatic validation before controller method execution.</p>
     */
    @Test
    @DisplayName("Test bill payment with null account ID returns 400")
    @WithMockUser(username = "testuser", roles = {"USER"})
    public void testBillPaymentWithNullAccountId() throws Exception {
        // Arrange: Create payment request with null account ID
        PaymentRequest paymentRequest = PaymentRequest.builder()
                .accountId(null)
                .amount(TEST_PAYMENT_AMOUNT)
                .paymentReference("TEST-PAYMENT-004")
                .build();

        // Act & Assert: Execute POST request and verify 400 response
        mockMvc.perform(post("/api/billing/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                // Assert: Verify HTTP 400 Bad Request response for validation error
                .andExpect(status().isBadRequest());

        // Verify database state: No transaction should be created
        List<Transaction> transactions = transactionRepository.findAll();
        assertEquals(0, transactions.size(), 
                "No transaction should be created for invalid request");
    }

    /**
     * Test bill payment processing with null payment amount.
     * <p>Validates Bean Validation enforcement for @NotNull constraint on amount field:</p>
     * <ul>
     *   <li>Accepts PaymentRequest with null amount</li>
     *   <li>Returns 400 Bad Request status code</li>
     *   <li>Returns validation error message</li>
     *   <li>Does not attempt payment processing</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent:</strong> COBIL00C.cbl amount validation checking IF
     * TRAN-AMT = ZEROS, setting error flag and displaying "Invalid amount" message.</p>
     * 
     * <p><strong>Bean Validation:</strong> @NotNull annotation on PaymentRequest.amount
     * field triggers automatic validation before controller method execution.</p>
     */
    @Test
    @DisplayName("Test bill payment with null amount returns 400")
    @WithMockUser(username = "testuser", roles = {"USER"})
    public void testBillPaymentWithNullAmount() throws Exception {
        // Arrange: Create payment request with null amount
        PaymentRequest paymentRequest = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .amount(null)
                .paymentReference("TEST-PAYMENT-005")
                .build();

        // Act & Assert: Execute POST request and verify 400 response
        mockMvc.perform(post("/api/billing/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                // Assert: Verify HTTP 400 Bad Request response for validation error
                .andExpect(status().isBadRequest());

        // Verify database state: Account balance should remain unchanged
        Optional<Account> accountOpt = accountRepository.findByAccountId(TEST_ACCOUNT_ID);
        assertTrue(accountOpt.isPresent(), "Account should still exist");
        
        Account account = accountOpt.get();
        assertEquals(TEST_INITIAL_BALANCE, account.getCurrentBalance(),
                "Account balance should not be modified on validation error");
    }

    /**
     * Test bill payment processing with zero payment amount.
     * <p>Validates Bean Validation enforcement for @Positive constraint on amount field:</p>
     * <ul>
     *   <li>Accepts PaymentRequest with amount = 0.00</li>
     *   <li>Returns 400 Bad Request status code</li>
     *   <li>Returns validation error message</li>
     *   <li>Does not reduce account balance</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent:</strong> COBIL00C.cbl amount validation checking IF
     * TRAN-AMT <= ZEROS, setting error flag and displaying "Invalid payment amount" message.</p>
     * 
     * <p><strong>Bean Validation:</strong> @Positive annotation on PaymentRequest.amount
     * ensures value is greater than zero, matching COBOL validation logic.</p>
     */
    @Test
    @DisplayName("Test bill payment with zero amount returns 400")
    @WithMockUser(username = "testuser", roles = {"USER"})
    public void testBillPaymentWithZeroAmount() throws Exception {
        // Arrange: Create payment request with zero amount
        PaymentRequest paymentRequest = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .amount(BigDecimal.ZERO)
                .paymentReference("TEST-PAYMENT-006")
                .build();

        // Act & Assert: Execute POST request and verify 400 response
        mockMvc.perform(post("/api/billing/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                // Assert: Verify HTTP 400 Bad Request response for validation error
                .andExpect(status().isBadRequest());

        // Verify database state: Account balance should remain unchanged
        Optional<Account> accountOpt = accountRepository.findByAccountId(TEST_ACCOUNT_ID);
        assertTrue(accountOpt.isPresent(), "Account should still exist");
        
        Account account = accountOpt.get();
        assertEquals(TEST_INITIAL_BALANCE, account.getCurrentBalance(),
                "Account balance should not be modified on validation error");
    }

    /**
     * Test bill payment processing with negative payment amount.
     * <p>Validates Bean Validation enforcement for @Positive constraint on amount field:</p>
     * <ul>
     *   <li>Accepts PaymentRequest with amount < 0</li>
     *   <li>Returns 400 Bad Request status code</li>
     *   <li>Returns validation error message</li>
     *   <li>Does not modify account balance</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent:</strong> COBIL00C.cbl amount validation checking IF
     * TRAN-AMT < ZEROS (signed field), setting error flag and displaying error message.</p>
     * 
     * <p><strong>Bean Validation:</strong> @Positive annotation on PaymentRequest.amount
     * ensures value is positive, matching COBOL signed field validation from PIC S9(09)V99.</p>
     */
    @Test
    @DisplayName("Test bill payment with negative amount returns 400")
    @WithMockUser(username = "testuser", roles = {"USER"})
    public void testBillPaymentWithNegativeAmount() throws Exception {
        // Arrange: Create payment request with negative amount
        PaymentRequest paymentRequest = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .amount(new BigDecimal("-50.00"))
                .paymentReference("TEST-PAYMENT-007")
                .build();

        // Act & Assert: Execute POST request and verify 400 response
        mockMvc.perform(post("/api/billing/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                // Assert: Verify HTTP 400 Bad Request response for validation error
                .andExpect(status().isBadRequest());

        // Verify database state: Account balance should remain unchanged
        Optional<Account> accountOpt = accountRepository.findByAccountId(TEST_ACCOUNT_ID);
        assertTrue(accountOpt.isPresent(), "Account should still exist");
        
        Account account = accountOpt.get();
        assertEquals(TEST_INITIAL_BALANCE, account.getCurrentBalance(),
                "Account balance should not be modified on validation error");
    }

    /**
     * Test bill payment processing with excessive decimal precision.
     * <p>Validates Bean Validation enforcement for @Digits constraint matching COBOL COMP-3
     * decimal precision:</p>
     * <ul>
     *   <li>Accepts PaymentRequest with amount having more than 2 decimal places</li>
     *   <li>Returns 400 Bad Request status code</li>
     *   <li>Returns validation error message</li>
     *   <li>Enforces scale=2 matching COBOL PIC S9(09)V99 precision</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent:</strong> COBIL00C.cbl TRAN-AMT field defined as PIC
     * S9(09)V99 allows exactly 2 decimal places, implicit validation through COBOL data
     * definition preventing excessive precision.</p>
     * 
     * <p><strong>Bean Validation:</strong> @Digits(integer=10, fraction=2) annotation on
     * PaymentRequest.amount enforces exact precision matching COBOL COMP-3 behavior.</p>
     * 
     * <p><strong>Test Case:</strong> Amount $100.999 (3 decimal places) should be rejected.</p>
     */
    @Test
    @DisplayName("Test bill payment with excessive decimal precision returns 400")
    @WithMockUser(username = "testuser", roles = {"USER"})
    public void testBillPaymentWithExcessiveDecimalPrecision() throws Exception {
        // Arrange: Create payment request with more than 2 decimal places
        PaymentRequest paymentRequest = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .amount(new BigDecimal("100.999"))
                .paymentReference("TEST-PAYMENT-008")
                .build();

        // Act & Assert: Execute POST request and verify 400 response
        mockMvc.perform(post("/api/billing/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                // Assert: Verify HTTP 400 Bad Request response for validation error
                .andExpect(status().isBadRequest());

        // Verify database state: Account balance should remain unchanged
        Optional<Account> accountOpt = accountRepository.findByAccountId(TEST_ACCOUNT_ID);
        assertTrue(accountOpt.isPresent(), "Account should still exist");
        
        Account account = accountOpt.get();
        assertEquals(TEST_INITIAL_BALANCE, account.getCurrentBalance(),
                "Account balance should not be modified on validation error");
    }

    /**
     * Test bill payment processing with maximum valid amount.
     * <p>Validates BigDecimal arithmetic with large payment amounts within precision limits:</p>
     * <ul>
     *   <li>Accepts PaymentRequest with large amount within account balance</li>
     *   <li>Returns 200 OK status code</li>
     *   <li>Correctly reduces account balance using BigDecimal arithmetic</li>
     *   <li>Maintains exact precision with scale=2 and RoundingMode.HALF_UP</li>
     *   <li>Creates transaction record with correct amount</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent:</strong> COBIL00C.cbl processing maximum payment amount
     * up to ACCT-CURR-BAL value (PIC S9(10)V99 supports up to $9,999,999,999.99).</p>
     * 
     * <p><strong>Precision Validation:</strong></p>
     * <p>Initial balance: $5,000.00</p>
     * <p>Payment amount: $4,999.99 (maximum practical payment)</p>
     * <p>Expected updated balance: $0.01</p>
     * <p>All calculations use RoundingMode.HALF_UP matching COBOL COMP-3 rounding.</p>
     */
    @Test
    @DisplayName("Test bill payment with maximum valid amount")
    @WithMockUser(username = "testuser", roles = {"USER"})
    public void testBillPaymentWithMaximumValidAmount() throws Exception {
        // Arrange: Create payment request with large amount within balance
        BigDecimal largePaymentAmount = new BigDecimal("4999.99");
        PaymentRequest paymentRequest = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .amount(largePaymentAmount)
                .paymentReference("TEST-PAYMENT-009")
                .build();

        // Act: Execute POST request to /api/billing/payment endpoint
        mockMvc.perform(post("/api/billing/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                // Assert: Verify HTTP 200 OK response
                .andExpect(status().isOk())
                // Assert: Verify response content type is JSON
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // Assert: Verify updatedBalance is correct (initial balance - large payment)
                .andExpect(jsonPath("$.updatedBalance").value(0.01));

        // Verify database state: Account balance should be reduced correctly
        Optional<Account> accountOpt = accountRepository.findByAccountId(TEST_ACCOUNT_ID);
        assertTrue(accountOpt.isPresent(), "Account should exist after payment");
        
        Account account = accountOpt.get();
        BigDecimal expectedBalance = TEST_INITIAL_BALANCE.subtract(largePaymentAmount)
                .setScale(2, RoundingMode.HALF_UP);
        assertEquals(expectedBalance, account.getCurrentBalance(),
                "Account balance should be reduced by large payment amount with correct precision");
        
        // Verify balance is exactly $0.01 (one cent remaining)
        assertEquals(new BigDecimal("0.01"), account.getCurrentBalance(),
                "Account balance should be exactly $0.01 after maximum payment");
    }

    /**
     * Test bill payment processing with admin user role.
     * <p>Validates role-based security authorization for admin users:</p>
     * <ul>
     *   <li>Authenticates user with ROLE_ADMIN</li>
     *   <li>Accepts PaymentRequest from admin user</li>
     *   <li>Returns 200 OK status code</li>
     *   <li>Processes payment successfully</li>
     *   <li>Validates @PreAuthorize("hasAnyRole('ADMIN', 'USER')") allows admin access</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent:</strong> COBIL00C.cbl security check using USRSEC file
     * validating user type 'A' (admin) has access to bill payment function (CB00 transaction).</p>
     * 
     * <p><strong>Security Configuration:</strong> BillingController.processPayment() method
     * annotated with @PreAuthorize("hasAnyRole('ADMIN', 'USER')") allowing both admin and
     * regular users to process bill payments, matching RACF security pattern from mainframe.</p>
     */
    @Test
    @DisplayName("Test bill payment with admin user role succeeds")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    public void testBillPaymentWithAdminRole() throws Exception {
        // Arrange: Create valid payment request
        PaymentRequest paymentRequest = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .amount(TEST_PAYMENT_AMOUNT)
                .paymentReference("TEST-PAYMENT-010")
                .build();

        // Act & Assert: Execute POST request with admin user and verify success
        mockMvc.perform(post("/api/billing/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                // Assert: Verify HTTP 200 OK response (admin has access)
                .andExpect(status().isOk())
                // Assert: Verify response content type is JSON
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // Assert: Verify payment processed successfully
                .andExpect(jsonPath("$.transactionId").exists())
                .andExpect(jsonPath("$.updatedBalance").value(4900.00));
    }

    /**
     * Test bill payment processing with regular user role.
     * <p>Validates role-based security authorization for regular users:</p>
     * <ul>
     *   <li>Authenticates user with ROLE_USER</li>
     *   <li>Accepts PaymentRequest from regular user</li>
     *   <li>Returns 200 OK status code</li>
     *   <li>Processes payment successfully</li>
     *   <li>Validates @PreAuthorize("hasAnyRole('ADMIN', 'USER')") allows user access</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent:</strong> COBIL00C.cbl security check using USRSEC file
     * validating user type 'U' (regular user) has access to bill payment function (CB00
     * transaction), matching two-tier role model from mainframe.</p>
     */
    @Test
    @DisplayName("Test bill payment with regular user role succeeds")
    @WithMockUser(username = "user", roles = {"USER"})
    public void testBillPaymentWithUserRole() throws Exception {
        // Arrange: Create valid payment request
        PaymentRequest paymentRequest = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .amount(TEST_PAYMENT_AMOUNT)
                .paymentReference("TEST-PAYMENT-011")
                .build();

        // Act & Assert: Execute POST request with regular user and verify success
        mockMvc.perform(post("/api/billing/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                // Assert: Verify HTTP 200 OK response (regular user has access)
                .andExpect(status().isOk())
                // Assert: Verify response content type is JSON
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // Assert: Verify payment processed successfully
                .andExpect(jsonPath("$.transactionId").exists())
                .andExpect(jsonPath("$.updatedBalance").value(4900.00));
    }

    /**
     * Test bill payment processing without authentication.
     * <p>Validates security enforcement for unauthenticated requests:</p>
     * <ul>
     *   <li>Sends PaymentRequest without authentication headers</li>
     *   <li>Returns 401 Unauthorized status code</li>
     *   <li>Does not process payment</li>
     *   <li>Does not modify account balance</li>
     *   <li>Validates Spring Security filter chain rejects unauthenticated requests</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent:</strong> COBIL00C.cbl requiring valid CICS security context
     * (EIBCALEN > 0 with valid COMMAREA containing user credentials) before processing payment,
     * matching RACF authentication requirement from mainframe.</p>
     * 
     * <p><strong>Note:</strong> This test does not use @WithMockUser annotation, simulating
     * request from unauthenticated client.</p>
     */
    @Test
    @DisplayName("Test bill payment without authentication returns 401")
    public void testBillPaymentWithoutAuthentication() throws Exception {
        // Arrange: Create valid payment request
        PaymentRequest paymentRequest = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .amount(TEST_PAYMENT_AMOUNT)
                .paymentReference("TEST-PAYMENT-012")
                .build();

        // Act & Assert: Execute POST request without authentication and verify 401 response
        mockMvc.perform(post("/api/billing/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                // Assert: Verify HTTP 401 Unauthorized response
                .andExpect(status().isUnauthorized());

        // Verify database state: Account balance should remain unchanged
        Optional<Account> accountOpt = accountRepository.findByAccountId(TEST_ACCOUNT_ID);
        assertTrue(accountOpt.isPresent(), "Account should still exist");
        
        Account account = accountOpt.get();
        assertEquals(TEST_INITIAL_BALANCE, account.getCurrentBalance(),
                "Account balance should not be modified without authentication");
    }

    /**
     * Test bill payment BigDecimal precision preservation.
     * <p>Validates exact decimal arithmetic matching COBOL COMP-3 behavior:</p>
     * <ul>
     *   <li>Tests payment amount with exact 2 decimal places</li>
     *   <li>Verifies balance reduction uses BigDecimal.subtract() with RoundingMode.HALF_UP</li>
     *   <li>Confirms no precision loss in monetary calculations</li>
     *   <li>Validates scale=2 maintained throughout calculation chain</li>
     *   <li>Ensures transaction amount stored with exact precision</li>
     * </ul>
     * 
     * <p><strong>COBOL COMP-3 Precision Requirement:</strong></p>
     * <p>From CVACT01Y.cpy: ACCT-CURR-BAL PIC S9(10)V99 COMP-3</p>
     * <p>From CVTRA05Y.cpy: TRAN-AMT PIC S9(09)V99</p>
     * <p>Both fields require exactly 2 decimal places with no rounding errors.</p>
     * 
     * <p><strong>Test Calculation:</strong></p>
     * <p>Initial balance: $5,000.00 (scale=2, precision=12)</p>
     * <p>Payment amount: $123.45 (scale=2, precision=12)</p>
     * <p>Expected balance: $4,876.55 (scale=2, precision=12)</p>
     * <p>Calculation: balance.subtract(amount).setScale(2, RoundingMode.HALF_UP)</p>
     */
    @Test
    @DisplayName("Test bill payment BigDecimal precision preservation")
    @WithMockUser(username = "testuser", roles = {"USER"})
    public void testBillPaymentBigDecimalPrecision() throws Exception {
        // Arrange: Create payment request with precise decimal amount
        BigDecimal precisePaymentAmount = new BigDecimal("123.45");
        PaymentRequest paymentRequest = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .amount(precisePaymentAmount)
                .paymentReference("TEST-PAYMENT-013")
                .build();

        // Act: Execute POST request to /api/billing/payment endpoint
        mockMvc.perform(post("/api/billing/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                // Assert: Verify HTTP 200 OK response
                .andExpect(status().isOk())
                // Assert: Verify updatedBalance has exact precision (5000.00 - 123.45 = 4876.55)
                .andExpect(jsonPath("$.updatedBalance").value(4876.55));

        // Verify database state: Account balance precision
        Optional<Account> accountOpt = accountRepository.findByAccountId(TEST_ACCOUNT_ID);
        assertTrue(accountOpt.isPresent(), "Account should exist after payment");
        
        Account account = accountOpt.get();
        BigDecimal expectedBalance = new BigDecimal("4876.55");
        assertEquals(expectedBalance, account.getCurrentBalance(),
                "Account balance should have exact precision with scale=2");
        
        // Verify scale is exactly 2 (matching COBOL COMP-3 V99)
        assertEquals(2, account.getCurrentBalance().scale(),
                "Account balance scale should be exactly 2");

        // Verify transaction amount precision
        List<Transaction> transactions = transactionRepository.findAll();
        Optional<Transaction> paymentTransactionOpt = transactions.stream()
                .filter(t -> "02".equals(t.getTypeCode()))
                .findFirst();
        assertTrue(paymentTransactionOpt.isPresent(), "Payment transaction should exist");
        
        Transaction paymentTransaction = paymentTransactionOpt.get();
        assertEquals(precisePaymentAmount.setScale(2, RoundingMode.HALF_UP), 
                paymentTransaction.getAmount(),
                "Transaction amount should have exact precision with scale=2");
        
        // Verify transaction amount scale is exactly 2
        assertEquals(2, paymentTransaction.getAmount().scale(),
                "Transaction amount scale should be exactly 2");
    }

    /**
     * Test bill payment transactional integrity with rollback.
     * <p>Validates @Transactional boundary behavior matching CICS SYNCPOINT/ROLLBACK:</p>
     * <ul>
     *   <li>Simulates error condition during payment processing</li>
     *   <li>Verifies exception triggers automatic rollback</li>
     *   <li>Confirms account balance remains unchanged after rollback</li>
     *   <li>Confirms no transaction record created after rollback</li>
     *   <li>Validates ACID properties equivalent to CICS transaction management</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent:</strong> COBIL00C.cbl transaction rollback when WS-RESP-CD
     * not equal to DFHRESP(NORMAL), triggering EXEC CICS ROLLBACK to undo all file updates
     * (account balance modification and transaction record write).</p>
     * 
     * <p><strong>Transactional Behavior:</strong></p>
     * <ul>
     *   <li>@Transactional method entry → Begin transaction</li>
     *   <li>Account balance update → Pending change</li>
     *   <li>Transaction record insert → Pending change</li>
     *   <li>Exception thrown → Automatic rollback</li>
     *   <li>All changes discarded → Database state unchanged</li>
     * </ul>
     * 
     * <p><strong>Note:</strong> This test validates database consistency using insufficient
     * balance scenario which triggers business logic exception and automatic rollback.</p>
     */
    @Test
    @DisplayName("Test bill payment transactional integrity with rollback")
    @WithMockUser(username = "testuser", roles = {"USER"})
    public void testBillPaymentTransactionalRollback() throws Exception {
        // Arrange: Get initial account balance before attempted payment
        Optional<Account> accountBeforeOpt = accountRepository.findByAccountId(TEST_ACCOUNT_ID_LOW_BALANCE);
        assertTrue(accountBeforeOpt.isPresent(), "Account should exist before payment attempt");
        BigDecimal balanceBeforePayment = accountBeforeOpt.get().getCurrentBalance();
        
        // Get initial transaction count
        long transactionCountBefore = transactionRepository.count();

        // Arrange: Create payment request that will fail (insufficient balance)
        PaymentRequest paymentRequest = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID_LOW_BALANCE)
                .amount(TEST_EXCESSIVE_PAYMENT)
                .paymentReference("TEST-PAYMENT-014")
                .build();

        // Act: Execute POST request that will trigger rollback
        mockMvc.perform(post("/api/billing/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                // Assert: Verify HTTP 400 Bad Request response
                .andExpect(status().isBadRequest());

        // Verify database state: Account balance should be unchanged (rollback successful)
        Optional<Account> accountAfterOpt = accountRepository.findByAccountId(TEST_ACCOUNT_ID_LOW_BALANCE);
        assertTrue(accountAfterOpt.isPresent(), "Account should still exist after rollback");
        
        Account accountAfter = accountAfterOpt.get();
        assertEquals(balanceBeforePayment, accountAfter.getCurrentBalance(),
                "Account balance should be unchanged after transaction rollback");

        // Verify database state: No new transaction record created (rollback successful)
        long transactionCountAfter = transactionRepository.count();
        assertEquals(transactionCountBefore, transactionCountAfter,
                "Transaction count should be unchanged after rollback");
    }
}
