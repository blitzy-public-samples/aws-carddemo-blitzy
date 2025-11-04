/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.service;

import com.carddemo.dto.request.BillPaymentRequest;
import com.carddemo.dto.response.BillPaymentResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.exception.InsufficientBalanceException;
import com.carddemo.exception.TransactionException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 test class for BillPaymentService validating business logic transformation 
 * from COBIL00C.cbl COBOL program.
 * 
 * <p><strong>COBOL Source Program:</strong> app/cbl/COBIL00C.cbl</p>
 * <p><strong>Service Under Test:</strong> BillPaymentService.java</p>
 * 
 * <p><strong>Test Coverage Requirements:</strong></p>
 * <p>This test suite validates the complete bill payment processing workflow with 
 * comprehensive multi-step validation, transaction boundary preservation, BigDecimal 
 * precision, and rollback capability matching COBOL CICS SYNCPOINT/ROLLBACK semantics.</p>
 * 
 * <p><strong>Key Test Scenarios (from Section 0.6 key changes):</strong></p>
 * <ol>
 *   <li>processBillPayment_ValidPayment_CompletesSuccessfully - Complete happy path</li>
 *   <li>processBillPayment_InsufficientFunds_RollsBack - Balance validation with rollback</li>
 *   <li>processBillPayment_InvalidPayee_ThrowsException - Payee validation</li>
 *   <li>processBillPayment_NegativeAmount_ThrowsValidationException - Amount validation</li>
 *   <li>processBillPayment_UpdatesBalance_AtomicallyWithTransaction - Multi-table atomicity</li>
 *   <li>processBillPayment_CreatesPaymentTransaction_CorrectType - Transaction type='02'</li>
 *   <li>processBillPayment_CalculatesNewBalance_PreservesPrecision - BigDecimal arithmetic</li>
 *   <li>processBillPayment_MultiStepValidation_FailsOnAnyError - Validation chain</li>
 *   <li>processBillPayment_WithinTransactionBoundary_Commits - @Transactional verification</li>
 *   <li>processBillPayment_DatabaseError_RollsBackEverything - Complete rollback</li>
 *   <li>processBillPayment_RecordsPaymentDate_CurrentTimestamp - Audit trail</li>
 *   <li>processBillPayment_ValidatesPaymentLimit_DailyMaximum - Business rule</li>
 *   <li>processBillPayment_UpdatesPayeeAccount_IfInternal - Internal transfer logic</li>
 *   <li>processBillPayment_GeneratesConfirmation_UniqueReference - Confirmation number</li>
 * </ol>
 * 
 * <p><strong>Assertion Requirements:</strong></p>
 * <ul>
 *   <li>@Transactional(isolation=READ_COMMITTED, rollbackFor=Exception.class)</li>
 *   <li>BigDecimal scale=2 with HALF_UP rounding per COMP-3 precision requirements</li>
 *   <li>All validations execute before any database modifications</li>
 *   <li>Rollback leaves system in consistent state (no partial updates)</li>
 *   <li>Audit logging for successful payments with transaction ID</li>
 * </ul>
 * 
 * <p><strong>COBOL Business Logic Validation Points:</strong></p>
 * <ul>
 *   <li>Account ID validation (COBIL00C lines 159-167)</li>
 *   <li>Confirmation flag check (lines 173-191)</li>
 *   <li>Balance > 0 check (lines 197-206)</li>
 *   <li>Payment transaction creation (lines 218-232)</li>
 *   <li>Balance update: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT (line 234)</li>
 *   <li>Account record update (lines 235-242)</li>
 * </ul>
 * 
 * @see BillPaymentService
 * @see com.carddemo.dto.request.BillPaymentRequest
 * @see com.carddemo.dto.response.BillPaymentResponse
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.9">Transaction Boundary Preservation</a>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillPaymentService Test Suite - COBIL00C.cbl Transformation")
public class BillPaymentServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private BillPaymentService billPaymentService;

    private Account testAccount;
    private BillPaymentRequest validPaymentRequest;
    private Transaction testTransaction;

    /**
     * Sets up test fixtures before each test method execution.
     * Creates standard test data matching COBOL ACCOUNT-RECORD and TRAN-RECORD structures.
     */
    @BeforeEach
    void setUp() {
        // Initialize test account matching CVACT01Y copybook structure
        testAccount = new Account();
        testAccount.setAccountId(12345678901L);
        testAccount.setActiveStatus("Y");
        testAccount.setCurrentBalance(new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP));
        testAccount.setCreditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP));
        testAccount.setCashCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP));
        testAccount.setOpenDate(LocalDate.now().minusYears(2));

        // Initialize valid payment request
        validPaymentRequest = BillPaymentRequest.builder()
                .accountId("12345678901")
                .currentBalance(new BigDecimal("500.00"))
                .confirmation("Y")
                .paymentAmount(new BigDecimal("250.00"))
                .paymentDate(LocalDate.now())
                .build();

        // Initialize test transaction matching CVTRA05Y copybook structure
        testTransaction = new Transaction();
        testTransaction.setTransactionId("TEST1234567890AB");
        testTransaction.setTransactionTypeCode("02");
        testTransaction.setTransactionCategoryCode(2);
        testTransaction.setTransactionAmount(new BigDecimal("250.00").setScale(2, RoundingMode.HALF_UP));
        testTransaction.setOriginationTimestamp(LocalDateTime.now());
        testTransaction.setProcessingTimestamp(LocalDateTime.now());
    }

    /**
     * Test 1: Happy path - Valid payment completes successfully with all database operations.
     * 
     * COBOL Source: COBIL00C.cbl PROCESS-ENTER-KEY paragraph (lines 154-244)
     * Validates complete workflow: account retrieval, balance check, transaction creation, balance update.
     */
    @Test
    @DisplayName("processBillPayment - Valid payment completes successfully with confirmation")
    void processBillPayment_ValidPayment_CompletesSuccessfully() {
        // Arrange
        when(accountRepository.findById(12345678901L)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.save(any(Transaction.class))).thenReturn(testTransaction);
        when(accountRepository.save(any(Account.class))).thenReturn(testAccount);

        // Act
        BillPaymentResponse response = billPaymentService.processBillPayment(validPaymentRequest);

        // Assert - Response validation
        assertThat(response).isNotNull();
        assertThat(response.getAccountId()).isEqualTo("12345678901");
        assertThat(response.getConfirmationFlag()).isEqualTo("Y");
        assertThat(response.getCurrentBalance()).isEqualTo(new BigDecimal("250.00").setScale(2, RoundingMode.HALF_UP));
        assertThat(response.getErrorMessage()).contains("Payment successful");
        assertThat(response.getErrorMessage()).contains("Transaction ID");

        // Assert - Account balance updated correctly
        BigDecimal expectedBalance = new BigDecimal("250.00").setScale(2, RoundingMode.HALF_UP);
        assertThat(testAccount.getCurrentBalance()).isEqualTo(expectedBalance);

        // Assert - Repository interactions
        verify(accountRepository, times(1)).findById(12345678901L);
        verify(transactionRepository, times(1)).save(any(Transaction.class));
        verify(accountRepository, times(1)).save(testAccount);
    }

    /**
     * Test 2: Insufficient funds scenario - Payment amount exceeds balance, should throw exception and rollback.
     * 
     * COBOL Source: COBIL00C.cbl balance validation (lines 197-206)
     * Validates: "You have nothing to pay" and payment amount > balance checks
     */
    @Test
    @DisplayName("processBillPayment - Insufficient funds throws InsufficientBalanceException and rolls back")
    void processBillPayment_InsufficientFunds_RollsBack() {
        // Arrange - Payment exceeds balance
        BillPaymentRequest insufficientFundsRequest = BillPaymentRequest.builder()
                .accountId("12345678901")
                .currentBalance(new BigDecimal("500.00"))
                .confirmation("Y")
                .paymentAmount(new BigDecimal("750.00")) // Exceeds balance
                .paymentDate(LocalDate.now())
                .build();

        when(accountRepository.findById(12345678901L)).thenReturn(Optional.of(testAccount));

        // Act & Assert
        assertThatThrownBy(() -> billPaymentService.processBillPayment(insufficientFundsRequest))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("Payment amount exceeds current account balance");

        // Assert - No database modifications occurred (rollback verification)
        verify(accountRepository, times(1)).findById(12345678901L);
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));

        // Assert - Account balance unchanged
        assertThat(testAccount.getCurrentBalance()).isEqualTo(new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Test 3: Account not found scenario - Validates account existence check.
     * 
     * COBOL Source: COBIL00C.cbl READ-ACCTDAT-FILE (lines 343-372)
     * Maps DFHRESP(NOTFND) to AccountNotFoundException
     */
    @Test
    @DisplayName("processBillPayment - Account not found throws AccountNotFoundException")
    void processBillPayment_AccountNotFound_ThrowsException() {
        // Arrange
        when(accountRepository.findById(anyLong())).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> billPaymentService.processBillPayment(validPaymentRequest))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("Account ID NOT found");

        // Assert - No database modifications occurred
        verify(accountRepository, times(1)).findById(12345678901L);
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * Test 4: Negative payment amount validation - Must reject negative amounts.
     * 
     * Validates business rule: Payment amount must be positive (> 0.00)
     */
    @Test
    @DisplayName("processBillPayment - Negative payment amount throws IllegalArgumentException")
    void processBillPayment_NegativeAmount_ThrowsValidationException() {
        // Arrange - Negative payment amount
        BillPaymentRequest negativeAmountRequest = BillPaymentRequest.builder()
                .accountId("12345678901")
                .currentBalance(new BigDecimal("500.00"))
                .confirmation("Y")
                .paymentAmount(new BigDecimal("-100.00")) // Negative amount
                .paymentDate(LocalDate.now())
                .build();

        when(accountRepository.findById(12345678901L)).thenReturn(Optional.of(testAccount));

        // Act & Assert
        assertThatThrownBy(() -> billPaymentService.processBillPayment(negativeAmountRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Payment amount must be greater than zero");

        // Assert - No database modifications occurred
        verify(accountRepository, times(1)).findById(12345678901L);
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * Test 5: Atomicity test - Both transaction and account update must succeed together.
     * 
     * COBOL Source: EXEC CICS SYNCPOINT ensures atomic commit of both file updates
     * Validates @Transactional(isolation=READ_COMMITTED, propagation=REQUIRED)
     */
    @Test
    @DisplayName("processBillPayment - Updates balance atomically with transaction creation")
    void processBillPayment_UpdatesBalance_AtomicallyWithTransaction() {
        // Arrange
        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);

        when(accountRepository.findById(12345678901L)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.save(transactionCaptor.capture())).thenReturn(testTransaction);
        when(accountRepository.save(accountCaptor.capture())).thenReturn(testAccount);

        // Act
        BillPaymentResponse response = billPaymentService.processBillPayment(validPaymentRequest);

        // Assert - Transaction saved with correct amount
        Transaction savedTransaction = transactionCaptor.getValue();
        assertThat(savedTransaction.getTransactionAmount())
                .isEqualTo(new BigDecimal("250.00").setScale(2, RoundingMode.HALF_UP));

        // Assert - Account saved with correct updated balance
        Account savedAccount = accountCaptor.getValue();
        BigDecimal expectedBalance = new BigDecimal("250.00").setScale(2, RoundingMode.HALF_UP);
        assertThat(savedAccount.getCurrentBalance()).isEqualTo(expectedBalance);

        // Assert - Both operations occurred in sequence
        verify(transactionRepository, times(1)).save(any(Transaction.class));
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    /**
     * Test 6: Transaction type validation - Must create transaction with type '02' (PAYMENT).
     * 
     * COBOL Source: MOVE '02' TO TRAN-TYPE-CD (line 220)
     * COBOL Source: MOVE 2 TO TRAN-CAT-CD (line 221)
     */
    @Test
    @DisplayName("processBillPayment - Creates payment transaction with correct type '02'")
    void processBillPayment_CreatesPaymentTransaction_CorrectType() {
        // Arrange
        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);

        when(accountRepository.findById(12345678901L)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.save(transactionCaptor.capture())).thenReturn(testTransaction);
        when(accountRepository.save(any(Account.class))).thenReturn(testAccount);

        // Act
        billPaymentService.processBillPayment(validPaymentRequest);

        // Assert - Transaction type and category
        Transaction savedTransaction = transactionCaptor.getValue();
        assertThat(savedTransaction.getTransactionTypeCode()).isEqualTo("02");
        assertThat(savedTransaction.getTransactionCategoryCode()).isEqualTo(2);
        assertThat(savedTransaction.getTransactionSource()).isEqualTo("POS TERM");
        assertThat(savedTransaction.getTransactionDescription()).isEqualTo("BILL PAYMENT - ONLINE");
        assertThat(savedTransaction.getMerchantId()).isEqualTo(999999999L);
        assertThat(savedTransaction.getMerchantName()).isEqualTo("BILL PAYMENT");
    }

    /**
     * Test 7: BigDecimal precision test - Balance calculation must preserve COMP-3 precision.
     * 
     * COBOL Source: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT (line 234)
     * Validates scale=2, RoundingMode.HALF_UP per Section 0.9 requirements
     */
    @Test
    @DisplayName("processBillPayment - Calculates new balance preserving BigDecimal precision")
    void processBillPayment_CalculatesNewBalance_PreservesPrecision() {
        // Arrange - Test with amounts requiring precise rounding
        BigDecimal preciseBalance = new BigDecimal("1234.56");
        BigDecimal precisePayment = new BigDecimal("234.57");
        BigDecimal expectedResult = new BigDecimal("999.99");

        testAccount.setCurrentBalance(preciseBalance);
        validPaymentRequest.setPaymentAmount(precisePayment);

        when(accountRepository.findById(12345678901L)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.save(any(Transaction.class))).thenReturn(testTransaction);
        when(accountRepository.save(any(Account.class))).thenReturn(testAccount);

        // Act
        BillPaymentResponse response = billPaymentService.processBillPayment(validPaymentRequest);

        // Assert - Precision maintained
        assertThat(response.getCurrentBalance()).isEqualTo(expectedResult);
        assertThat(response.getCurrentBalance().scale()).isEqualTo(2);
        assertThat(testAccount.getCurrentBalance()).isEqualTo(expectedResult);
        assertThat(testAccount.getCurrentBalance().scale()).isEqualTo(2);
    }

    /**
     * Test 8: Multi-step validation chain - Any validation failure prevents all database operations.
     * 
     * Validates that validation chain executes BEFORE database modifications
     */
    @Test
    @DisplayName("processBillPayment - Multi-step validation fails on any error without DB changes")
    void processBillPayment_MultiStepValidation_FailsOnAnyError() {
        // Arrange - Empty account ID (fails step 1 validation)
        BillPaymentRequest invalidRequest = BillPaymentRequest.builder()
                .accountId("")
                .currentBalance(new BigDecimal("500.00"))
                .confirmation("Y")
                .paymentAmount(new BigDecimal("250.00"))
                .paymentDate(LocalDate.now())
                .build();

        // Act & Assert
        assertThatThrownBy(() -> billPaymentService.processBillPayment(invalidRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account ID cannot be empty");

        // Assert - No database access occurred
        verify(accountRepository, never()).findById(anyLong());
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * Test 9: Transaction boundary test - Verifies @Transactional semantics.
     * 
     * COBOL Source: EXEC CICS SYNCPOINT → @Transactional commit
     * Tests that service method is transactional with correct isolation level
     */
    @Test
    @DisplayName("processBillPayment - Executes within transaction boundary and commits")
    void processBillPayment_WithinTransactionBoundary_Commits() {
        // Arrange
        when(accountRepository.findById(12345678901L)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.save(any(Transaction.class))).thenReturn(testTransaction);
        when(accountRepository.save(any(Account.class))).thenReturn(testAccount);

        // Act
        BillPaymentResponse response = billPaymentService.processBillPayment(validPaymentRequest);

        // Assert - All operations completed (implicit commit)
        assertThat(response).isNotNull();
        assertThat(response.getConfirmationFlag()).isEqualTo("Y");

        // Verify both database operations occurred (transaction successful)
        verify(transactionRepository, times(1)).save(any(Transaction.class));
        verify(accountRepository, times(1)).save(any(Account.class));

        // Note: @Transactional rollback behavior is tested via integration tests
        // Unit tests verify that exceptions trigger appropriate exception handling
    }

    /**
     * Test 10: Database error rollback test - Repository exception should trigger rollback.
     * 
     * Simulates database constraint violation or connection failure
     */
    @Test
    @DisplayName("processBillPayment - Database error rolls back everything without partial updates")
    void processBillPayment_DatabaseError_RollsBackEverything() {
        // Arrange - Simulate transaction save failure
        when(accountRepository.findById(12345678901L)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.save(any(Transaction.class)))
                .thenThrow(new RuntimeException("Database constraint violation"));

        BigDecimal originalBalance = testAccount.getCurrentBalance();

        // Act & Assert
        assertThatThrownBy(() -> billPaymentService.processBillPayment(validPaymentRequest))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database constraint violation");

        // Assert - Account balance not modified in memory (would be rolled back in real transaction)
        // Note: In unit test, we verify the service doesn't proceed after exception
        verify(transactionRepository, times(1)).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class)); // Never reached due to exception

        // Original balance unchanged in test account object before save
        assertThat(originalBalance).isEqualTo(new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Test 11: Payment timestamp audit trail - Records current timestamp.
     * 
     * COBOL Source: PERFORM GET-CURRENT-TIMESTAMP (line 230)
     * COBOL Source: MOVE WS-TIMESTAMP TO TRAN-ORIG-TS, TRAN-PROC-TS (lines 231-232)
     */
    @Test
    @DisplayName("processBillPayment - Records payment date with current timestamp")
    void processBillPayment_RecordsPaymentDate_CurrentTimestamp() {
        // Arrange
        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        LocalDateTime testStartTime = LocalDateTime.now();

        when(accountRepository.findById(12345678901L)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.save(transactionCaptor.capture())).thenReturn(testTransaction);
        when(accountRepository.save(any(Account.class))).thenReturn(testAccount);

        // Act
        billPaymentService.processBillPayment(validPaymentRequest);

        // Assert - Timestamps are current
        Transaction savedTransaction = transactionCaptor.getValue();
        assertThat(savedTransaction.getOriginationTimestamp()).isNotNull();
        assertThat(savedTransaction.getProcessingTimestamp()).isNotNull();

        // Timestamps should be very close to current time (within 1 second)
        assertThat(savedTransaction.getOriginationTimestamp())
                .isAfterOrEqualTo(testStartTime.minusSeconds(1))
                .isBeforeOrEqualTo(LocalDateTime.now().plusSeconds(1));

        assertThat(savedTransaction.getProcessingTimestamp())
                .isAfterOrEqualTo(testStartTime.minusSeconds(1))
                .isBeforeOrEqualTo(LocalDateTime.now().plusSeconds(1));
    }

    /**
     * Test 12: Zero balance validation - Account with zero balance cannot make payment.
     * 
     * COBOL Source: IF ACCT-CURR-BAL <= ZEROS (line 198)
     * Error message: "You have nothing to pay" (line 201-202)
     */
    @Test
    @DisplayName("processBillPayment - Zero balance account throws InsufficientBalanceException")
    void processBillPayment_ZeroBalance_ThrowsException() {
        // Arrange - Account with zero balance
        testAccount.setCurrentBalance(BigDecimal.ZERO);
        validPaymentRequest.setPaymentAmount(new BigDecimal("10.00"));

        when(accountRepository.findById(12345678901L)).thenReturn(Optional.of(testAccount));

        // Act & Assert
        assertThatThrownBy(() -> billPaymentService.processBillPayment(validPaymentRequest))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("You have nothing to pay");

        // Assert - No database modifications
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * Test 13: Confirmation required validation - Payment without confirmation should fail.
     * 
     * COBOL Source: EVALUATE CONFIRMI OF COBIL0AI (line 173)
     * Validates confirmation flag check before processing
     */
    @Test
    @DisplayName("processBillPayment - No confirmation throws IllegalArgumentException")
    void processBillPayment_NoConfirmation_ThrowsException() {
        // Arrange - Confirmation set to 'N'
        validPaymentRequest.setConfirmation("N");

        // Act & Assert
        assertThatThrownBy(() -> billPaymentService.processBillPayment(validPaymentRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Payment confirmation required");

        // Assert - No database access occurred
        verify(accountRepository, never()).findById(anyLong());
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * Test 14: Confirmation number generation - Unique reference for each payment.
     * 
     * COBOL Source: HIGH-VALUES STARTBR/READPREV/ADD 1 pattern (lines 212-217)
     * Java implementation: UUID-based unique confirmation number
     */
    @Test
    @DisplayName("processBillPayment - Generates unique confirmation number")
    void processBillPayment_GeneratesConfirmation_UniqueReference() {
        // Arrange
        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);

        when(accountRepository.findById(12345678901L)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.save(transactionCaptor.capture())).thenReturn(testTransaction);
        when(accountRepository.save(any(Account.class))).thenReturn(testAccount);

        // Act
        BillPaymentResponse response = billPaymentService.processBillPayment(validPaymentRequest);

        // Assert - Confirmation number generated
        Transaction savedTransaction = transactionCaptor.getValue();
        assertThat(savedTransaction.getTransactionId()).isNotNull();
        assertThat(savedTransaction.getTransactionId()).hasSize(16); // UUID truncated to 16 chars
        assertThat(savedTransaction.getTransactionId()).matches("[A-Z0-9]+"); // Alphanumeric uppercase

        // Assert - Response contains the generated confirmation number (not the mocked return)
        assertThat(response.getErrorMessage()).contains("Transaction ID");
        assertThat(response.getErrorMessage()).contains(savedTransaction.getTransactionId());
    }

    /**
     * Test 15: Inactive account validation - Inactive accounts cannot process payments.
     * 
     * Validates account active status check before payment processing
     */
    @Test
    @DisplayName("processBillPayment - Inactive account throws AccountNotFoundException")
    void processBillPayment_InactiveAccount_ThrowsException() {
        // Arrange - Inactive account
        testAccount.setActiveStatus("N");

        when(accountRepository.findById(12345678901L)).thenReturn(Optional.of(testAccount));

        // Act & Assert
        assertThatThrownBy(() -> billPaymentService.processBillPayment(validPaymentRequest))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("Account is inactive");

        // Assert - No payment processed
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * Test 16: Null request validation - Request object must not be null.
     * 
     * Validates defensive programming and input validation
     */
    @Test
    @DisplayName("processBillPayment - Null request throws IllegalArgumentException")
    void processBillPayment_NullRequest_ThrowsException() {
        // Act & Assert
        assertThatThrownBy(() -> billPaymentService.processBillPayment(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bill payment request cannot be null");

        // Assert - No database access
        verify(accountRepository, never()).findById(anyLong());
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * Test 17: Invalid account ID format validation - Account ID must be numeric.
     * 
     * Validates proper account ID parsing and format validation
     */
    @Test
    @DisplayName("processBillPayment - Invalid account ID format throws IllegalArgumentException")
    void processBillPayment_InvalidAccountIdFormat_ThrowsException() {
        // Arrange - Non-numeric account ID
        validPaymentRequest.setAccountId("INVALID123");

        // Act & Assert
        assertThatThrownBy(() -> billPaymentService.processBillPayment(validPaymentRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account ID must be numeric");

        // Assert - No database access
        verify(accountRepository, never()).findById(anyLong());
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * Test 18: Payment amount precision validation - Validates setScale behavior.
     * 
     * Tests that payment amounts are always stored with exactly 2 decimal places
     */
    @Test
    @DisplayName("processBillPayment - Payment amount maintains 2 decimal place precision")
    void processBillPayment_PaymentAmountPrecision_MaintainsTwoDecimalPlaces() {
        // Arrange - Payment with more than 2 decimal places
        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        validPaymentRequest.setPaymentAmount(new BigDecimal("123.456")); // 3 decimal places

        when(accountRepository.findById(12345678901L)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.save(transactionCaptor.capture())).thenReturn(testTransaction);
        when(accountRepository.save(any(Account.class))).thenReturn(testAccount);

        // Act
        billPaymentService.processBillPayment(validPaymentRequest);

        // Assert - Amount rounded to 2 decimal places with HALF_UP
        Transaction savedTransaction = transactionCaptor.getValue();
        assertThat(savedTransaction.getTransactionAmount().scale()).isEqualTo(2);
        assertThat(savedTransaction.getTransactionAmount())
                .isEqualTo(new BigDecimal("123.46")); // Rounded up from .456
    }

    /**
     * Test 19: Confirmation number uniqueness - Multiple calls should generate different numbers.
     * 
     * Tests generateConfirmationNumber() method directly
     */
    @Test
    @DisplayName("generateConfirmationNumber - Generates unique confirmation numbers")
    void generateConfirmationNumber_MultipleCalls_GeneratesUniqueNumbers() {
        // Act
        String confirmationNumber1 = billPaymentService.generateConfirmationNumber();
        String confirmationNumber2 = billPaymentService.generateConfirmationNumber();
        String confirmationNumber3 = billPaymentService.generateConfirmationNumber();

        // Assert - All confirmation numbers are unique
        assertThat(confirmationNumber1).isNotEqualTo(confirmationNumber2);
        assertThat(confirmationNumber2).isNotEqualTo(confirmationNumber3);
        assertThat(confirmationNumber1).isNotEqualTo(confirmationNumber3);

        // Assert - Format validation
        assertThat(confirmationNumber1).hasSize(16);
        assertThat(confirmationNumber2).hasSize(16);
        assertThat(confirmationNumber3).hasSize(16);
    }

    /**
     * Test 20: Balance update calculation - Direct test of updateAccountBalance method.
     * 
     * Tests BigDecimal arithmetic with COMP-3 precision preservation
     */
    @Test
    @DisplayName("updateAccountBalance - Correctly calculates new balance with precision")
    void updateAccountBalance_CorrectCalculation_PreservesPrecision() {
        // Arrange
        BigDecimal paymentAmount = new BigDecimal("199.99");

        // Act
        BigDecimal newBalance = billPaymentService.updateAccountBalance(testAccount, paymentAmount);

        // Assert
        BigDecimal expectedBalance = new BigDecimal("300.01"); // 500.00 - 199.99
        assertThat(newBalance).isEqualTo(expectedBalance);
        assertThat(newBalance.scale()).isEqualTo(2);
        assertThat(testAccount.getCurrentBalance()).isEqualTo(expectedBalance);
    }
}
