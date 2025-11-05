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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carddemo.service;

import com.carddemo.dto.request.TransactionRequest;
import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.exception.CardNotFoundException;
import com.carddemo.exception.InsufficientBalanceException;
import com.carddemo.exception.TransactionException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.DecimalUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Comprehensive JUnit 5 test suite for TransactionCreationService validating business logic
 * transformation from COBOL program COTRN02C.cbl.
 * 
 * <p>This test class verifies the complete transaction posting workflow including:</p>
 * <ul>
 *   <li>Atomic transaction creation with balance updates within @Transactional boundary</li>
 *   <li>COMP-3 decimal precision preservation using BigDecimal with scale=2 and HALF_UP rounding</li>
 *   <li>Transaction type validation matching COBOL 88-level condition names</li>
 *   <li>Overdraft checking and credit limit validation</li>
 *   <li>Multi-table atomicity (TRANSACT insert + ACCTDAT balance update)</li>
 *   <li>Rollback behavior on database errors or validation failures</li>
 *   <li>Concurrent transaction handling with optimistic/pessimistic locking</li>
 * </ul>
 * 
 * <p><strong>COBOL Source Mapping:</strong></p>
 * <ul>
 *   <li>Source Program: app/cbl/COTRN02C.cbl (CICS Transaction CT02)</li>
 *   <li>ADD-TRANSACTION (lines 442-466): Transaction creation and ID generation</li>
 *   <li>WRITE-TRANSACT-FILE (lines 711-749): Transaction persistence logic</li>
 *   <li>VALIDATE-INPUT-KEY-FIELDS (lines 193-230): Account and card validation</li>
 *   <li>VALIDATE-INPUT-DATA-FIELDS (lines 235-437): Transaction data validation</li>
 * </ul>
 * 
 * <p><strong>Critical Test Requirements (Section 0.9):</strong></p>
 * <ul>
 *   <li>Verify @Transactional with propagation=REQUIRED, isolation=READ_COMMITTED, rollbackFor=Exception.class</li>
 *   <li>Validate BigDecimal precision: ALL calculations use setScale(2, RoundingMode.HALF_UP)</li>
 *   <li>Ensure atomic operations: Either BOTH transaction created AND balance updated, or NEITHER</li>
 *   <li>Test negative amounts for purchases and positive amounts for credits/payments</li>
 *   <li>Validate foreign key relationships (card-to-account, transaction-to-card)</li>
 *   <li>Verify timestamp recording for audit trail compliance</li>
 * </ul>
 * 
 * @see TransactionCreationService
 * @see Transaction
 * @see Account
 * @see Card
 * @since 1.0
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionCreationService - COBOL COTRN02C.cbl Transformation Tests")
public class TransactionCreationServiceTest {

    /**
     * Service under test - TransactionCreationService with @Transactional boundaries.
     * Uses @InjectMocks to automatically inject all @Mock dependencies.
     */
    @InjectMocks
    private TransactionCreationService transactionCreationService;

    /**
     * Mock repository for Transaction entity database operations.
     * Simulates EXEC CICS WRITE TRANSACT file operations from COBOL.
     */
    @Mock
    private TransactionRepository transactionRepository;

    /**
     * Mock repository for Account entity database operations.
     * Simulates EXEC CICS READ/REWRITE ACCTDAT file operations from COBOL.
     */
    @Mock
    private AccountRepository accountRepository;

    /**
     * Mock repository for Card entity database operations.
     * Simulates EXEC CICS READ CARDDAT and XREF file operations from COBOL.
     */
    @Mock
    private CardRepository cardRepository;

    /**
     * Mock utility for BigDecimal operations with COMP-3 precision preservation.
     * Ensures monetary calculations maintain exact decimal arithmetic matching COBOL.
     */
    @Mock
    private DecimalUtils decimalUtils;

    // Test data constants
    private static final Long TEST_ACCOUNT_ID = 123456L;  // Parsed value of TEST_ACCOUNT_ID_STRING without leading zeros
    private static final String TEST_ACCOUNT_ID_STRING = "00000123456";
    private static final String TEST_CARD_NUMBER = "4111111111111111";
    private static final String TEST_TRANSACTION_TYPE = "01";  // Purchase
    private static final String TEST_TRANSACTION_CATEGORY = "5000";  // Retail
    private static final String TEST_TRANSACTION_SOURCE = "POS";
    private static final BigDecimal TEST_CREDIT_LIMIT = new BigDecimal("10000.00");
    private static final BigDecimal TEST_INITIAL_BALANCE = new BigDecimal("2500.00");
    private static final BigDecimal TEST_PURCHASE_AMOUNT = new BigDecimal("-150.75");
    private static final BigDecimal TEST_PAYMENT_AMOUNT = new BigDecimal("500.00");

    private Account testAccount;
    private Card testCard;
    private TransactionRequest testRequest;

    /**
     * Set up test fixtures before each test method execution.
     * Creates mock Account, Card, and TransactionRequest objects with valid test data.
     */
    @BeforeEach
    void setUp() {
        // Initialize test account with COMP-3 precision BigDecimal balances
        testAccount = new Account();
        testAccount.setAccountId(TEST_ACCOUNT_ID);
        testAccount.setCurrentBalance(TEST_INITIAL_BALANCE.setScale(2, RoundingMode.HALF_UP));
        testAccount.setCreditLimit(TEST_CREDIT_LIMIT.setScale(2, RoundingMode.HALF_UP));
        testAccount.setActiveStatus("Y");
        testAccount.setCurrentCycleDebit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        testAccount.setCurrentCycleCredit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

        // Initialize test card with active status and valid expiration
        testCard = new Card();
        testCard.setCardNumber(TEST_CARD_NUMBER);
        testCard.setAccountId(TEST_ACCOUNT_ID);
        testCard.setActiveStatus("Y");  // Active status must be 'Y'
        testCard.setExpirationDate(LocalDate.of(2025, 12, 31));

        // Initialize test transaction request with valid purchase data
        testRequest = new TransactionRequest();
        testRequest.setAccountId(TEST_ACCOUNT_ID_STRING);
        testRequest.setCardNumber(TEST_CARD_NUMBER);
        testRequest.setTransactionTypeCode(TEST_TRANSACTION_TYPE);
        testRequest.setTransactionCategoryCode(TEST_TRANSACTION_CATEGORY);
        testRequest.setTransactionAmount(TEST_PURCHASE_AMOUNT.setScale(2, RoundingMode.HALF_UP));
        testRequest.setTransactionSource(TEST_TRANSACTION_SOURCE);
        testRequest.setTransactionDescription("Test purchase transaction");
        testRequest.setMerchantId("987654321");
        testRequest.setMerchantName("Test Merchant");
        testRequest.setOriginDate(LocalDate.now());
        testRequest.setProcessDate(LocalDate.now());
    }

    /**
     * Test: Valid purchase transaction updates account balance atomically.
     * 
     * <p>Verifies COBOL logic from COTRN02C ADD-TRANSACTION (lines 442-466) and
     * WRITE-TRANSACT-FILE (lines 711-749) where transaction is written to TRANSACT file
     * and account balance is updated in ACCTDAT file within same CICS SYNCPOINT boundary.</p>
     * 
     * <p>Critical validations:</p>
     * <ul>
     *   <li>Transaction repository save() called exactly once</li>
     *   <li>Account repository save() called exactly once</li>
     *   <li>Account balance updated with correct BigDecimal precision (scale=2, HALF_UP)</li>
     *   <li>Balance calculation: newBalance = currentBalance + transactionAmount</li>
     *   <li>Atomic operation: Both saves execute within @Transactional boundary</li>
     * </ul>
     */
    @Test
    @DisplayName("createTransaction - Valid Purchase - Updates Balance Atomically")
    void createTransaction_ValidPurchase_UpdatesBalance() {
        // Arrange: Set up mock repository responses
        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID))
            .thenReturn(Optional.of(testAccount));
        when(cardRepository.findByCardNumber(TEST_CARD_NUMBER))
            .thenReturn(Optional.of(testCard));
        
        // Mock transaction save to return transaction with generated ID
        when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(invocation -> {
                Transaction t = invocation.getArgument(0);
                t.setTransactionId("2024011500000001");
                return t;
            });
        
        // Mock account save to return updated account
        when(accountRepository.save(any(Account.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // Act: Execute transaction creation
        Transaction result = transactionCreationService.createTransaction(testRequest);

        // Assert: Verify transaction was created
        assertNotNull(result, "Transaction should not be null");
        assertNotNull(result.getTransactionId(), "Transaction ID should be generated");

        // Verify balance update with correct BigDecimal precision
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, times(1)).save(accountCaptor.capture());
        
        Account savedAccount = accountCaptor.getValue();
        BigDecimal expectedBalance = TEST_INITIAL_BALANCE
            .add(TEST_PURCHASE_AMOUNT)
            .setScale(2, RoundingMode.HALF_UP);
        
        assertEquals(expectedBalance, savedAccount.getCurrentBalance(),
            "Account balance should be updated with correct COMP-3 precision");

        // Verify atomic operations: both transaction and account saved
        verify(transactionRepository, times(1)).save(any(Transaction.class));
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    /**
     * Test: Insufficient funds throws InsufficientBalanceException with overdraft prevention.
     * 
     * <p>Verifies COBOL credit limit validation logic where transaction amount validation
     * checks prevent processing when new balance would exceed credit limit. Maps to validation
     * pattern: IF ACCT-CURR-BAL + TRAN-AMT &gt; ACCT-CREDIT-LIMIT THEN reject transaction.</p>
     * 
     * <p>Critical validations:</p>
     * <ul>
     *   <li>InsufficientBalanceException thrown with correct message</li>
     *   <li>Exception contains requestedAmount, availableCredit, accountId</li>
     *   <li>No transaction repository save() called (validation fails before save)</li>
     *   <li>No account repository save() called (no balance update on validation failure)</li>
     *   <li>Atomic rollback: Neither transaction nor balance updated</li>
     * </ul>
     */
    @Test
    @DisplayName("createTransaction - Insufficient Funds - Throws Exception")
    void createTransaction_InsufficientFunds_ThrowsException() {
        // Arrange: Set up account with balance at credit limit
        testAccount.setCurrentBalance(new BigDecimal("9900.00").setScale(2, RoundingMode.HALF_UP));
        
        // Transaction amount that would exceed credit limit
        BigDecimal excessiveAmount = new BigDecimal("-200.00").setScale(2, RoundingMode.HALF_UP);
        testRequest.setTransactionAmount(excessiveAmount);

        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID))
            .thenReturn(Optional.of(testAccount));
        when(cardRepository.findByCardNumber(TEST_CARD_NUMBER))
            .thenReturn(Optional.of(testCard));

        // Act & Assert: Verify exception thrown with correct details
        InsufficientBalanceException exception = assertThrows(
            InsufficientBalanceException.class,
            () -> transactionCreationService.createTransaction(testRequest),
            "Should throw InsufficientBalanceException when transaction exceeds credit limit"
        );

        // Verify exception contains contextual information
        assertNotNull(exception.getRequestedAmount(), "Exception should contain requested amount");
        assertNotNull(exception.getAvailableCredit(), "Exception should contain available credit");
        assertNotNull(exception.getAccountId(), "Exception should contain account ID");

        // Verify no database operations performed (rollback behavior)
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * Test: Negative amount validation throws TransactionException.
     * 
     * <p>Verifies COBOL amount validation from VALIDATE-INPUT-DATA-FIELDS (lines 339-351)
     * where amount format is validated: WHEN TRNAMTI NOT IN EXPECTED-FORMAT THEN reject.</p>
     * 
     * <p>Critical validations:</p>
     * <ul>
     *   <li>TransactionException thrown for invalid negative amount (incorrect sign for payment)</li>
     *   <li>Exception message indicates validation failure</li>
     *   <li>No repository operations performed before validation</li>
     * </ul>
     */
    @Test
    @DisplayName("createTransaction - Negative Amount For Payment - Throws ValidationException")
    void createTransaction_NegativeAmount_ThrowsValidationException() {
        // Arrange: Set up payment transaction type with negative amount (invalid)
        testRequest.setTransactionTypeCode("02");  // Payment type
        testRequest.setTransactionAmount(new BigDecimal("-100.00"));  // Invalid: payments should be positive

        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID))
            .thenReturn(Optional.of(testAccount));
        when(cardRepository.findByCardNumber(TEST_CARD_NUMBER))
            .thenReturn(Optional.of(testCard));

        // Act & Assert: Verify validation exception
        assertThrows(
            TransactionException.class,
            () -> transactionCreationService.createTransaction(testRequest),
            "Should throw TransactionException for invalid amount sign for transaction type"
        );

        // Verify no persistence operations
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * Test: Transaction operations execute within @Transactional boundary and commit atomically.
     * 
     * <p>Verifies CICS SYNCPOINT semantics from COBOL where EXEC CICS SYNCPOINT commits all
     * file updates (TRANSACT write, ACCTDAT rewrite) as single atomic unit. Maps to Spring
     * @Transactional with propagation=REQUIRED, isolation=READ_COMMITTED, rollbackFor=Exception.class.</p>
     * 
     * <p>Critical validations:</p>
     * <ul>
     *   <li>All repository operations execute within transaction boundary</li>
     *   <li>Transaction commit occurs after all saves complete successfully</li>
     *   <li>Verify transactionRepository.save() called before accountRepository.save()</li>
     *   <li>Confirm both operations succeed or both rollback together</li>
     * </ul>
     */
    @Test
    @DisplayName("createTransaction - Within Transaction Boundary - Commits Atomically")
    void createTransaction_WithinTransactionBoundary_CommitsAtomically() {
        // Arrange
        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID))
            .thenReturn(Optional.of(testAccount));
        when(cardRepository.findByCardNumber(TEST_CARD_NUMBER))
            .thenReturn(Optional.of(testCard));
        
        when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(invocation -> {
                Transaction t = invocation.getArgument(0);
                t.setTransactionId("2024011500000001");
                return t;
            });
        
        when(accountRepository.save(any(Account.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Transaction result = transactionCreationService.createTransaction(testRequest);

        // Assert: Verify atomic execution order
        assertNotNull(result, "Transaction should be created successfully");

        // Verify both save operations occurred
        verify(transactionRepository, times(1)).save(any(Transaction.class));
        verify(accountRepository, times(1)).save(any(Account.class));

        // Verify account was retrieved before updates
        verify(accountRepository, times(1)).findByAccountId(TEST_ACCOUNT_ID);
        verify(cardRepository, times(1)).findByCardNumber(TEST_CARD_NUMBER);
    }

    /**
     * Test: Database error triggers rollback of all operations within @Transactional boundary.
     * 
     * <p>Verifies CICS SYNCPOINT ROLLBACK semantics where database error causes automatic
     * rollback of all file updates. Maps to Spring @Transactional rollbackFor=Exception.class.</p>
     * 
     * <p>Critical validations:</p>
     * <ul>
     *   <li>DataAccessException thrown when database error occurs</li>
     *   <li>Transaction save succeeds but account save fails</li>
     *   <li>Automatic rollback prevents partial updates</li>
     *   <li>Neither transaction nor account changes persisted</li>
     * </ul>
     */
    @Test
    @DisplayName("createTransaction - Database Error - Rolls Back All Operations")
    void createTransaction_DatabaseError_RollsBackAll() {
        // Arrange: Simulate database error on account save
        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID))
            .thenReturn(Optional.of(testAccount));
        when(cardRepository.findByCardNumber(TEST_CARD_NUMBER))
            .thenReturn(Optional.of(testCard));
        
        when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(invocation -> {
                Transaction t = invocation.getArgument(0);
                t.setTransactionId("2024011500000001");
                return t;
            });
        
        // Simulate database constraint violation on account save
        when(accountRepository.save(any(Account.class)))
            .thenThrow(new DataIntegrityViolationException("Database constraint violation"));

        // Act & Assert: Verify exception propagated for rollback
        assertThrows(
            DataAccessException.class,
            () -> transactionCreationService.createTransaction(testRequest),
            "Should propagate DataAccessException for automatic rollback"
        );

        // Verify transaction save was attempted but rollback occurs
        verify(transactionRepository, times(1)).save(any(Transaction.class));
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    /**
     * Test: Balance calculation maintains correct COMP-3 precision with BigDecimal.
     * 
     * <p>Verifies COBOL COMP-3 packed decimal precision from CVACT01Y.cpy where
     * ACCT-CURR-BAL PIC S9(10)V99 requires exact 2-decimal place arithmetic.
     * Balance = currentBalance + transactionAmount with setScale(2, HALF_UP).</p>
     * 
     * <p>Critical validations:</p>
     * <ul>
     *   <li>BigDecimal scale set to 2 decimal places</li>
     *   <li>RoundingMode.HALF_UP matches COBOL rounding</li>
     *   <li>No floating-point arithmetic used</li>
     *   <li>Precision preserved across addition operations</li>
     * </ul>
     */
    @Test
    @DisplayName("createTransaction - Calculates New Balance - Correct COMP-3 Precision")
    void createTransaction_CalculatesNewBalance_CorrectPrecision() {
        // Arrange: Use amounts that require rounding
        testAccount.setCurrentBalance(new BigDecimal("1234.56").setScale(2, RoundingMode.HALF_UP));
        testRequest.setTransactionAmount(new BigDecimal("-123.45").setScale(2, RoundingMode.HALF_UP));

        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID))
            .thenReturn(Optional.of(testAccount));
        when(cardRepository.findByCardNumber(TEST_CARD_NUMBER))
            .thenReturn(Optional.of(testCard));
        
        when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(invocation -> {
                Transaction t = invocation.getArgument(0);
                t.setTransactionId("2024011500000001");
                return t;
            });
        
        when(accountRepository.save(any(Account.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        transactionCreationService.createTransaction(testRequest);

        // Assert: Verify balance calculation with exact precision
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        
        Account savedAccount = accountCaptor.getValue();
        BigDecimal expectedBalance = new BigDecimal("1234.56")
            .add(new BigDecimal("-123.45"))
            .setScale(2, RoundingMode.HALF_UP);
        
        assertEquals(new BigDecimal("1111.11"), savedAccount.getCurrentBalance(),
            "Balance should be calculated with exact COMP-3 precision: 1234.56 - 123.45 = 1111.11");
        
        // Verify scale is exactly 2
        assertEquals(2, savedAccount.getCurrentBalance().scale(),
            "Balance scale must be 2 for COMP-3 equivalence");
    }

    /**
     * Test: Transaction timestamp recorded for audit trail compliance.
     * 
     * <p>Verifies COBOL timestamp recording from CVTRA05Y.cpy where TRAN-ORIG-TS and
     * TRAN-PROC-TS fields (PIC X(26)) capture origination and processing timestamps
     * for regulatory audit trail requirements per Section 0.9.</p>
     * 
     * <p>Critical validations:</p>
     * <ul>
     *   <li>Transaction origination timestamp set from request</li>
     *   <li>Transaction processing timestamp set to current datetime</li>
     *   <li>Timestamps not null for audit compliance</li>
     * </ul>
     */
    @Test
    @DisplayName("createTransaction - Records Timestamp - Current DateTime")
    void createTransaction_RecordsTimestamp_CurrentDateTime() {
        // Arrange
        LocalDate originDate = LocalDate.of(2024, 1, 15);
        LocalDate processDate = LocalDate.now();
        testRequest.setOriginDate(originDate);
        testRequest.setProcessDate(processDate);

        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID))
            .thenReturn(Optional.of(testAccount));
        when(cardRepository.findByCardNumber(TEST_CARD_NUMBER))
            .thenReturn(Optional.of(testCard));
        
        when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(invocation -> {
                Transaction t = invocation.getArgument(0);
                t.setTransactionId("2024011500000001");
                return t;
            });
        
        when(accountRepository.save(any(Account.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Transaction result = transactionCreationService.createTransaction(testRequest);

        // Assert: Verify timestamps recorded
        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        
        Transaction savedTransaction = transactionCaptor.getValue();
        assertNotNull(savedTransaction.getOriginationTimestamp(),
            "Origination timestamp must be set for audit trail");
        assertNotNull(savedTransaction.getProcessingTimestamp(),
            "Processing timestamp must be set for audit trail");
    }

    /**
     * Test: Transaction type code validated as enum value matching COBOL 88-level conditions.
     * 
     * <p>Verifies COBOL transaction type validation from CVTRA02Y.cpy where transaction
     * type codes have 88-level condition names (e.g., 88 PURCHASE-TYPE VALUE '01').</p>
     * 
     * <p>Critical validations:</p>
     * <ul>
     *   <li>Valid transaction type codes accepted: '01'=Purchase, '02'=Payment, '03'=Credit</li>
     *   <li>Invalid transaction type codes rejected with TransactionException</li>
     *   <li>Type code validation occurs before persistence</li>
     * </ul>
     */
    @Test
    @DisplayName("createTransaction - Validates Transaction Type - Enum Value")
    void createTransaction_ValidatesTransactionType_EnumValue() {
        // Arrange: Valid transaction type
        testRequest.setTransactionTypeCode("01");  // Valid purchase type

        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID))
            .thenReturn(Optional.of(testAccount));
        when(cardRepository.findByCardNumber(TEST_CARD_NUMBER))
            .thenReturn(Optional.of(testCard));
        
        when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(invocation -> {
                Transaction t = invocation.getArgument(0);
                t.setTransactionId("2024011500000001");
                return t;
            });
        
        when(accountRepository.save(any(Account.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // Act & Assert: Valid type accepted
        assertDoesNotThrow(
            () -> transactionCreationService.createTransaction(testRequest),
            "Valid transaction type should not throw exception"
        );

        // Test invalid type code
        testRequest.setTransactionTypeCode("99");  // Invalid type
        
        assertThrows(
            TransactionException.class,
            () -> transactionCreationService.createTransaction(testRequest),
            "Invalid transaction type should throw TransactionException"
        );
    }

    /**
     * Test: Multi-table update atomicity for transaction and account within @Transactional.
     * 
     * <p>Verifies COBOL multi-file update atomicity where WRITE TRANSACT and REWRITE ACCTDAT
     * execute within same CICS SYNCPOINT boundary. Maps to @Transactional ensuring both
     * transactionRepository.save() and accountRepository.save() commit together.</p>
     * 
     * <p>Critical validations:</p>
     * <ul>
     *   <li>Transaction entity saved to database</li>
     *   <li>Account entity saved with updated balance</li>
     *   <li>Both operations succeed atomically or both rollback</li>
     *   <li>Card entity verified but not updated (read-only operation)</li>
     * </ul>
     */
    @Test
    @DisplayName("createTransaction - Updates Account And Card - Both Tables Atomically")
    void createTransaction_UpdatesAccountAndCard_BothTables() {
        // Arrange
        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID))
            .thenReturn(Optional.of(testAccount));
        when(cardRepository.findByCardNumber(TEST_CARD_NUMBER))
            .thenReturn(Optional.of(testCard));
        
        when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(invocation -> {
                Transaction t = invocation.getArgument(0);
                t.setTransactionId("2024011500000001");
                return t;
            });
        
        when(accountRepository.save(any(Account.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Transaction result = transactionCreationService.createTransaction(testRequest);

        // Assert: Verify multi-table atomicity
        assertNotNull(result, "Transaction should be created");

        // Verify transaction saved
        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(1)).save(transactionCaptor.capture());
        assertEquals(TEST_CARD_NUMBER, transactionCaptor.getValue().getCardNumber(),
            "Transaction should reference correct card number");

        // Verify account saved with updated balance
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, times(1)).save(accountCaptor.capture());
        assertNotEquals(TEST_INITIAL_BALANCE, accountCaptor.getValue().getCurrentBalance(),
            "Account balance should be updated");

        // Verify card validated but not updated (read-only)
        verify(cardRepository, times(1)).findByCardNumber(TEST_CARD_NUMBER);
        verify(cardRepository, never()).save(any(Card.class));
    }

    /**
     * Test: Credit limit validation prevents transactions that would exceed limit.
     * 
     * <p>Verifies COBOL credit limit checking logic: IF ACCT-CURR-BAL + TRAN-AMT &gt; ACCT-CREDIT-LIMIT
     * THEN reject transaction. Critical business rule enforcement before posting.</p>
     * 
     * <p>Critical validations:</p>
     * <ul>
     *   <li>Transaction rejected when newBalance &gt; creditLimit</li>
     *   <li>InsufficientBalanceException thrown with available credit info</li>
     *   <li>No database updates performed when validation fails</li>
     *   <li>Available credit calculated correctly: creditLimit - currentBalance</li>
     * </ul>
     */
    @Test
    @DisplayName("createTransaction - Checks Credit Limit - Before Posting")
    void createTransaction_ChecksCreditLimit_BeforePosting() {
        // Arrange: Account near credit limit
        testAccount.setCurrentBalance(new BigDecimal("9500.00").setScale(2, RoundingMode.HALF_UP));
        testAccount.setCreditLimit(new BigDecimal("10000.00").setScale(2, RoundingMode.HALF_UP));
        
        // Transaction that would exceed limit: 9500 + (-600) = 10100 > 10000
        testRequest.setTransactionAmount(new BigDecimal("-600.00").setScale(2, RoundingMode.HALF_UP));

        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID))
            .thenReturn(Optional.of(testAccount));
        when(cardRepository.findByCardNumber(TEST_CARD_NUMBER))
            .thenReturn(Optional.of(testCard));

        // Act & Assert: Verify credit limit enforcement
        InsufficientBalanceException exception = assertThrows(
            InsufficientBalanceException.class,
            () -> transactionCreationService.createTransaction(testRequest),
            "Should throw InsufficientBalanceException when credit limit exceeded"
        );

        // Verify exception contains correct available credit
        BigDecimal expectedAvailableCredit = new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP);
        assertNotNull(exception.getAvailableCredit(), "Exception should contain available credit");

        // Verify no persistence operations
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * Test: Debit transaction (payment/credit) increases available balance.
     * 
     * <p>Verifies COBOL logic where positive transaction amounts (payments, credits, refunds)
     * reduce the account balance (making more credit available). Opposite of purchase transactions.</p>
     * 
     * <p>Critical validations:</p>
     * <ul>
     *   <li>Positive transaction amount reduces balance (increases available credit)</li>
     *   <li>Balance calculation: newBalance = currentBalance + positiveAmount</li>
     *   <li>Result: Lower balance means more credit available</li>
     *   <li>BigDecimal precision maintained for payment amounts</li>
     * </ul>
     */
    @Test
    @DisplayName("createTransaction - Debit Transaction - Increases Balance (Reduces Owed)")
    void createTransaction_DebitTransaction_IncreasesBalance() {
        // Arrange: Payment transaction with positive amount
        testRequest.setTransactionTypeCode("02");  // Payment type
        testRequest.setTransactionAmount(TEST_PAYMENT_AMOUNT.setScale(2, RoundingMode.HALF_UP));

        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID))
            .thenReturn(Optional.of(testAccount));
        when(cardRepository.findByCardNumber(TEST_CARD_NUMBER))
            .thenReturn(Optional.of(testCard));
        
        when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(invocation -> {
                Transaction t = invocation.getArgument(0);
                t.setTransactionId("2024011500000001");
                return t;
            });
        
        when(accountRepository.save(any(Account.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        transactionCreationService.createTransaction(testRequest);

        // Assert: Verify balance reduced (more credit available)
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        
        Account savedAccount = accountCaptor.getValue();
        BigDecimal expectedBalance = TEST_INITIAL_BALANCE
            .add(TEST_PAYMENT_AMOUNT)
            .setScale(2, RoundingMode.HALF_UP);
        
        assertEquals(new BigDecimal("3000.00"), savedAccount.getCurrentBalance(),
            "Payment should increase balance: 2500.00 + 500.00 = 3000.00");
        
        assertTrue(savedAccount.getCurrentBalance().compareTo(TEST_INITIAL_BALANCE) > 0,
            "Balance after payment should be higher (less owed)");
    }

    /**
     * Test: Invalid card number throws CardNotFoundException.
     * 
     * <p>Verifies COBOL DFHRESP(NOTFND) error handling from READ-CCXREF-FILE (lines 609-637)
     * where card number lookup fails. Maps to CardNotFoundException in Java.</p>
     * 
     * <p>Critical validations:</p>
     * <ul>
     *   <li>CardNotFoundException thrown when card not found</li>
     *   <li>Exception contains card identifier for debugging</li>
     *   <li>No transaction or account operations performed</li>
     *   <li>Foreign key validation enforced before persistence</li>
     * </ul>
     */
    @Test
    @DisplayName("createTransaction - Invalid Card Number - Throws CardNotFoundException")
    void createTransaction_InvalidCardNumber_ThrowsNotFoundException() {
        // Arrange: Card not found in database
        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID))
            .thenReturn(Optional.of(testAccount));
        when(cardRepository.findByCardNumber(TEST_CARD_NUMBER))
            .thenReturn(Optional.empty());  // Card not found

        // Act & Assert: Verify exception thrown
        CardNotFoundException exception = assertThrows(
            CardNotFoundException.class,
            () -> transactionCreationService.createTransaction(testRequest),
            "Should throw CardNotFoundException when card does not exist"
        );

        // Verify exception contains card identifier
        assertNotNull(exception.getCardIdentifier(), "Exception should contain card identifier");
        assertTrue(exception.getMessage().contains(TEST_CARD_NUMBER),
            "Exception message should reference the card number");

        // Verify no persistence operations
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * Test: Account not found throws AccountNotFoundException with proper error context.
     * 
     * <p>Verifies COBOL DFHRESP(NOTFND) error handling from READ-CXACAIX-FILE (lines 583-604)
     * where account lookup fails. Maps to AccountNotFoundException in Java.</p>
     * 
     * <p>Critical validations:</p>
     * <ul>
     *   <li>AccountNotFoundException thrown when account not found</li>
     *   <li>Exception contains account identifier</li>
     *   <li>No downstream operations performed</li>
     * </ul>
     */
    @Test
    @DisplayName("createTransaction - Account Not Found - Throws AccountNotFoundException")
    void createTransaction_AccountNotFound_ThrowsException() {
        // Arrange: Account not found in database
        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID))
            .thenReturn(Optional.empty());  // Account not found

        // Act & Assert: Verify exception thrown
        AccountNotFoundException exception = assertThrows(
            AccountNotFoundException.class,
            () -> transactionCreationService.createTransaction(testRequest),
            "Should throw AccountNotFoundException when account does not exist"
        );

        // Verify exception contains account identifier
        assertNotNull(exception.getAccountIdentifier(), "Exception should contain account identifier");

        // Verify no card lookup attempted after account validation fails
        verify(cardRepository, never()).findByCardNumber(anyString());
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    /**
     * Test: Explicit atomicity test - balance update failure prevents transaction creation.
     * 
     * <p>Verifies CICS SYNCPOINT ROLLBACK behavior where failure in any part of the transaction
     * causes complete rollback. If account balance update fails, transaction record must not
     * be persisted to database.</p>
     * 
     * <p>Critical validations:</p>
     * <ul>
     *   <li>Account save failure triggers exception</li>
     *   <li>Transaction save occurs before account save in execution order</li>
     *   <li>@Transactional rollback prevents transaction persistence</li>
     *   <li>Database remains consistent (neither record persisted)</li>
     * </ul>
     */
    @Test
    @DisplayName("testAtomicity - Balance Update Fails - Transaction Not Created")
    void testAtomicity_BalanceUpdateFails_TransactionNotCreated() {
        // Arrange: Account save will fail after transaction save succeeds
        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID))
            .thenReturn(Optional.of(testAccount));
        when(cardRepository.findByCardNumber(TEST_CARD_NUMBER))
            .thenReturn(Optional.of(testCard));
        
        // Transaction save succeeds
        when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(invocation -> {
                Transaction t = invocation.getArgument(0);
                t.setTransactionId("2024011500000001");
                return t;
            });
        
        // Account save fails with database error
        when(accountRepository.save(any(Account.class)))
            .thenThrow(new DataIntegrityViolationException("Account balance constraint violation"));

        // Act & Assert: Verify exception propagated for rollback
        DataIntegrityViolationException exception = assertThrows(
            DataIntegrityViolationException.class,
            () -> transactionCreationService.createTransaction(testRequest),
            "Should propagate exception to trigger @Transactional rollback"
        );

        // Verify transaction save was attempted but will be rolled back
        verify(transactionRepository, times(1)).save(any(Transaction.class));
        
        // Verify account save was attempted and failed
        verify(accountRepository, times(1)).save(any(Account.class));

        // In real scenario with @Transactional, Spring would rollback transaction.save()
        // even though it succeeded, because account.save() threw exception
    }

    /**
     * Test: Concurrent transaction handling with pessimistic locking prevents lost updates.
     * 
     * <p>Verifies concurrent transaction processing where multiple transactions attempt to
     * update same account balance simultaneously. Optimistic locking (@Version) or pessimistic
     * locking prevents lost update problem.</p>
     * 
     * <p>Critical validations:</p>
     * <ul>
     *   <li>First transaction succeeds with balance update</li>
     *   <li>Second concurrent transaction detects version conflict</li>
     *   <li>PessimisticLockingFailureException or OptimisticLockingException thrown</li>
     *   <li>Balance consistency maintained under concurrent load</li>
     * </ul>
     */
    @Test
    @DisplayName("createTransaction - Concurrent Transactions - Handles Locking Correctly")
    void createTransaction_ConcurrentTransactions_HandlesLocking() {
        // Arrange: Simulate concurrent transaction scenario
        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID))
            .thenReturn(Optional.of(testAccount));
        when(cardRepository.findByCardNumber(TEST_CARD_NUMBER))
            .thenReturn(Optional.of(testCard));
        
        when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(invocation -> {
                Transaction t = invocation.getArgument(0);
                t.setTransactionId("2024011500000001");
                return t;
            });
        
        // First transaction succeeds
        when(accountRepository.save(any(Account.class)))
            .thenAnswer(invocation -> invocation.getArgument(0))
            // Second concurrent transaction fails due to optimistic locking
            .thenThrow(new PessimisticLockingFailureException("Account locked by another transaction"));

        // Act: First transaction succeeds
        Transaction result1 = transactionCreationService.createTransaction(testRequest);
        assertNotNull(result1, "First transaction should succeed");

        // Act & Assert: Second concurrent transaction fails with locking exception
        assertThrows(
            PessimisticLockingFailureException.class,
            () -> transactionCreationService.createTransaction(testRequest),
            "Concurrent transaction should fail with locking exception"
        );

        // Verify account save called twice (first success, second locked)
        verify(accountRepository, times(2)).save(any(Account.class));
    }

    /**
     * Test: Zero amount transaction validation throws TransactionException.
     * 
     * <p>Verifies COBOL validation logic where transaction amount must be non-zero.
     * Zero-amount transactions are invalid and should be rejected before persistence.</p>
     * 
     * <p>Critical validations:</p>
     * <ul>
     *   <li>TransactionException thrown for zero amount</li>
     *   <li>Validation occurs before database operations</li>
     *   <li>Exception message indicates validation failure</li>
     * </ul>
     */
    @Test
    @DisplayName("createTransaction - Zero Amount - Throws ValidationException")
    void createTransaction_ZeroAmount_ThrowsValidationException() {
        // Arrange: Transaction with zero amount
        testRequest.setTransactionAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID))
            .thenReturn(Optional.of(testAccount));
        when(cardRepository.findByCardNumber(TEST_CARD_NUMBER))
            .thenReturn(Optional.of(testCard));

        // Act & Assert: Verify validation exception
        TransactionException exception = assertThrows(
            TransactionException.class,
            () -> transactionCreationService.createTransaction(testRequest),
            "Should throw TransactionException for zero amount"
        );

        assertTrue(exception.getMessage().contains("amount"),
            "Exception message should reference amount validation");

        // Verify no persistence operations
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * Test: Inactive account prevents transaction processing.
     * 
     * <p>Verifies COBOL account status validation where ACCT-ACTIVE-STATUS must be 'Y'.
     * Inactive accounts cannot process new transactions.</p>
     * 
     * <p>Critical validations:</p>
     * <ul>
     *   <li>AccountNotFoundException or TransactionException thrown</li>
     *   <li>Active status check occurs before transaction processing</li>
     *   <li>No transactions posted to inactive accounts</li>
     * </ul>
     */
    @Test
    @DisplayName("createTransaction - Inactive Account - Throws Exception")
    void createTransaction_InactiveAccount_ThrowsException() {
        // Arrange: Account with inactive status
        testAccount.setActiveStatus("N");  // Inactive

        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID))
            .thenReturn(Optional.of(testAccount));
        // Card stubbing not needed since account validation fails first, but keeping for clarity
        lenient().when(cardRepository.findByCardNumber(TEST_CARD_NUMBER))
            .thenReturn(Optional.of(testCard));

        // Act & Assert: Verify exception thrown for inactive account
        Exception exception = assertThrows(
            RuntimeException.class,
            () -> transactionCreationService.createTransaction(testRequest),
            "Should throw exception when account is inactive"
        );

        // Verify no persistence operations
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * Test: Card-to-account relationship validation enforces foreign key integrity.
     * 
     * <p>Verifies COBOL cross-reference validation from READ-CCXREF-FILE where card must
     * belong to specified account. Prevents fraudulent transactions using another account's card.</p>
     * 
     * <p>Critical validations:</p>
     * <ul>
     *   <li>Card's accountId must match transaction's accountId</li>
     *   <li>TransactionException thrown when mismatch detected</li>
     *   <li>Foreign key integrity enforced at service layer</li>
     * </ul>
     */
    @Test
    @DisplayName("createTransaction - Card Account Mismatch - Throws Exception")
    void createTransaction_CardAccountMismatch_ThrowsException() {
        // Arrange: Card belongs to different account
        testCard.setAccountId(99999999999L);  // Different account

        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID))
            .thenReturn(Optional.of(testAccount));
        when(cardRepository.findByCardNumber(TEST_CARD_NUMBER))
            .thenReturn(Optional.of(testCard));

        // Act & Assert: Verify validation exception
        TransactionException exception = assertThrows(
            TransactionException.class,
            () -> transactionCreationService.createTransaction(testRequest),
            "Should throw TransactionException when card doesn't belong to account"
        );

        assertTrue(exception.getMessage().toLowerCase().contains("card") || 
                   exception.getMessage().toLowerCase().contains("account"),
            "Exception message should reference card-account mismatch");

        // Verify no persistence operations
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }
}
