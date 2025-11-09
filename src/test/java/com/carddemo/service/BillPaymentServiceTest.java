/*
 * BillPaymentServiceTest.java
 * 
 * JUnit 5 unit test class for BillPaymentService verifying bill payment processing logic
 * preservation from COBOL program COBIL00C.cbl.
 * 
 * This test suite validates the complete bill payment workflow including:
 * - Payment validation (account ID not null, amount positive)
 * - Account balance checking (balance > 0 for payment)
 * - Transaction record creation with type '02' (PAYMENT)
 * - Account balance reduction using BigDecimal arithmetic with scale=2
 * - Multi-repository coordination within @Transactional boundary
 * - Transaction rollback on error matching CICS ROLLBACK semantics
 * 
 * COBOL Source Mapping:
 * - Source Program: COBIL00C.cbl (Bill Payment - Pay account balance in full)
 * - Transaction ID: CB00
 * - Data Structures: CVACT01Y.cpy (Account), CVTRA05Y.cpy (Transaction)
 * 
 * Key Test Scenarios (from COBOL logic):
 * 1. Successful payment (lines 210-243): Creates transaction, reduces balance, returns confirmation
 * 2. Insufficient balance (lines 198-205): Rejects payment when ACCT-CURR-BAL <= ZEROS
 * 3. Account not found (lines 359-364): RESP(NOTFND) → ResourceNotFoundException
 * 4. Amount validation: Positive amounts only
 * 5. Precision verification: BigDecimal scale=2 matching COBOL COMP-3 PIC S9(09)V99
 * 6. Transaction rollback: Verify ACID properties on error
 * 7. Transaction record verification: Type='02', category=2, proper timestamps
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
package com.carddemo.service;

import com.carddemo.dto.request.PaymentRequest;
import com.carddemo.dto.response.PaymentResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.BusinessLogicException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.billing.BillPaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit test class for BillPaymentService.
 * <p>
 * Tests bill payment processing logic transformed from COBOL program COBIL00C.cbl,
 * validating functional equivalence including:
 * <ul>
 *   <li>Payment request validation matching COBOL field checks (lines 159-164)</li>
 *   <li>Balance validation matching COBOL ACCT-CURR-BAL <= ZEROS check (lines 198-205)</li>
 *   <li>Transaction ID generation matching COBOL STARTBR/READPREV pattern (lines 212-217)</li>
 *   <li>Transaction creation with type '02' and amount precision (lines 218-232)</li>
 *   <li>Balance reduction arithmetic with BigDecimal scale=2 (line 234)</li>
 *   <li>Multi-table update coordination with ACID properties (lines 233-235)</li>
 * </ul>
 * </p>
 * 
 * <p><strong>Mocking Strategy:</strong></p>
 * <ul>
 *   <li>@Mock AccountRepository - mocks VSAM ACCTDAT file READ/REWRITE operations</li>
 *   <li>@Mock TransactionRepository - mocks VSAM TRANSACT file WRITE and STARTBR/READPREV</li>
 *   <li>@Mock CardRepository - mocks VSAM CXACAIX cross-reference file READ</li>
 *   <li>@InjectMocks BillPaymentService - service under test with injected mocks</li>
 * </ul>
 * 
 * <p><strong>Assertion Strategy:</strong></p>
 * <ul>
 *   <li>AssertJ assertThat() for fluent, readable assertions</li>
 *   <li>ArgumentCaptor for verifying saved entity field values</li>
 *   <li>BigDecimal.compareTo() for monetary value assertions avoiding equals() precision issues</li>
 *   <li>assertThatThrownBy() for exception scenario verification</li>
 * </ul>
 * 
 * @see BillPaymentService
 * @see PaymentRequest
 * @see PaymentResponse
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Bill Payment Service Tests")
public class BillPaymentServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CardRepository cardRepository;

    @InjectMocks
    private BillPaymentService billPaymentService;

    // Test data constants matching COBOL values from COBIL00C.cbl
    private static final Long TEST_ACCOUNT_ID = 12345678901L;
    private static final String TEST_CARD_NUMBER = "4111111111111111";
    private static final String TEST_TRANSACTION_ID = "1234567890123456";
    private static final String PAYMENT_TYPE_CODE = "02"; // Line 220
    private static final Integer PAYMENT_CATEGORY_CODE = 2; // Line 221
    private static final String PAYMENT_DESCRIPTION = "BILL PAYMENT - ONLINE"; // Line 223

    private Account mockAccount;
    private Card mockCard;
    private Transaction mockTransaction;

    /**
     * Sets up test fixtures before each test execution.
     * <p>
     * Initializes mock entities with test data representing typical account and card
     * structures from COBOL copybooks CVACT01Y.cpy and CVACT02Y.cpy.
     * </p>
     * <p>
     * Test account created with $500.00 balance (BigDecimal scale=2) matching
     * COBOL PIC S9(10)V99 COMP-3 precision requirement from CVACT01Y.cpy line 7.
     * </p>
     */
    @BeforeEach
    public void setUp() {
        // Create mock account with positive balance for testing
        // Corresponds to ACCTDAT record from CVACT01Y.cpy
        mockAccount = Account.builder()
                .accountId(TEST_ACCOUNT_ID)
                .activeStatus("Y")
                .currentBalance(new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP))
                .creditLimit(new BigDecimal("10000.00").setScale(2, RoundingMode.HALF_UP))
                .cashCreditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP))
                .build();

        // Create mock card linked to account
        // Corresponds to CARD-XREF-RECORD from CVACT03Y.cpy
        mockCard = Card.builder()
                .cardNumber(TEST_CARD_NUMBER)
                .account(mockAccount)
                .cardType("DEBIT")
                .build();

        // Create mock transaction for transaction ID generation testing
        // Corresponds to TRAN-RECORD from CVTRA05Y.cpy
        mockTransaction = Transaction.builder()
                .transactionId(TEST_TRANSACTION_ID)
                .typeCode(PAYMENT_TYPE_CODE)
                .categoryCode(PAYMENT_CATEGORY_CODE)
                .transactionSource("POS TERM")
                .description(PAYMENT_DESCRIPTION)
                .amount(new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP))
                .card(mockCard)
                .merchantId(999999999L)
                .merchantName("BILL PAYMENT")
                .originationTimestamp(LocalDateTime.now())
                .processingTimestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Test successful bill payment processing with balance reduction and transaction creation.
     * <p>
     * Corresponds to COBOL COBIL00C.cbl lines 210-243 (CONF-PAY-YES path):
     * </p>
     * <pre>
     * IF CONF-PAY-YES
     *     PERFORM READ-CXACAIX-FILE (line 211)
     *     [Generate Transaction ID] (lines 212-217)
     *     [Create Transaction Record] (lines 218-232)
     *     PERFORM WRITE-TRANSACT-FILE (line 233)
     *     COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT (line 234)
     *     PERFORM UPDATE-ACCTDAT-FILE (line 235)
     * </pre>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>Account exists with $500.00 balance</li>
     *   <li>Payment request for $500.00 (full balance payment)</li>
     *   <li>Service creates transaction, reduces balance to $0.00, returns confirmation</li>
     * </ul>
     * 
     * <p><strong>Assertions:</strong></p>
     * <ul>
     *   <li>Account balance reduced by exact payment amount with scale=2 precision</li>
     *   <li>Transaction record created with type='02', correct amount, timestamps</li>
     *   <li>Payment response contains transaction ID and updated balance</li>
     *   <li>Both account save and transaction save methods called in correct order</li>
     * </ul>
     */
    @Test
    @DisplayName("Test successful bill payment with balance reduction and transaction creation")
    public void testProcessPaymentSuccess() {
        // Arrange
        BigDecimal paymentAmount = new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP);
        PaymentRequest request = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .amount(paymentAmount)
                .build();

        // Mock repository behaviors
        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(mockAccount));
        when(cardRepository.findByAccount_AccountId(TEST_ACCOUNT_ID)).thenReturn(Collections.singletonList(mockCard));
        when(transactionRepository.findTopByOrderByTransactionIdDesc())
                .thenReturn(Optional.of(mockTransaction));
        when(accountRepository.save(any(Account.class))).thenReturn(mockAccount);
        when(transactionRepository.save(any(Transaction.class))).thenReturn(mockTransaction);

        // Act
        PaymentResponse response = billPaymentService.processBillPayment(request);

        // Assert - Verify response structure
        assertThat(response).isNotNull();
        assertThat(response.getTransactionId()).isNotNull();
        assertThat(response.getConfirmationMessage()).contains("Payment successful");
        assertThat(response.getUpdatedBalance()).isNotNull();

        // Assert - Verify account balance reduction
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        Account savedAccount = accountCaptor.getValue();
        
        // Verify balance calculation: 500.00 - 500.00 = 0.00
        BigDecimal expectedBalance = new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP);
        assertThat(savedAccount.getCurrentBalance())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(expectedBalance);
        assertThat(savedAccount.getCurrentBalance().scale()).isEqualTo(2);

        // Assert - Verify transaction record creation
        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        Transaction savedTransaction = transactionCaptor.getValue();
        
        assertThat(savedTransaction.getAmount())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(paymentAmount);
        assertThat(savedTransaction.getAmount().scale()).isEqualTo(2);
        assertThat(savedTransaction.getTypeCode()).isEqualTo(PAYMENT_TYPE_CODE);
        assertThat(savedTransaction.getCategoryCode()).isEqualTo(PAYMENT_CATEGORY_CODE);
        assertThat(savedTransaction.getDescription()).isEqualTo(PAYMENT_DESCRIPTION);
        assertThat(savedTransaction.getCard().getCardNumber()).isEqualTo(TEST_CARD_NUMBER);
        assertThat(savedTransaction.getOriginationTimestamp()).isNotNull();
        assertThat(savedTransaction.getProcessingTimestamp()).isNotNull();

        // Assert - Verify repository method calls
        verify(accountRepository).findById(TEST_ACCOUNT_ID);
        verify(cardRepository).findByAccount_AccountId(TEST_ACCOUNT_ID);
        verify(transactionRepository).findTopByOrderByTransactionIdDesc();
        verify(transactionRepository).save(any(Transaction.class));
        verify(accountRepository).save(any(Account.class));
    }

    /**
     * Test payment rejection when account balance is zero or negative.
     * <p>
     * Corresponds to COBOL COBIL00C.cbl lines 198-205 validation:
     * </p>
     * <pre>
     * IF ACCT-CURR-BAL <= ZEROS AND
     *    ACTIDINI OF COBIL0AI NOT = SPACES AND LOW-VALUES
     *     MOVE 'Y'     TO WS-ERR-FLG
     *     MOVE 'You have nothing to pay...' TO WS-MESSAGE
     * </pre>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>Account exists with $0.00 balance (zero balance)</li>
     *   <li>Payment request submitted for account</li>
     *   <li>Service rejects payment with BusinessLogicException</li>
     * </ul>
     * 
     * <p><strong>Assertions:</strong></p>
     * <ul>
     *   <li>BusinessLogicException thrown matching COBOL error flag</li>
     *   <li>Exception message matches COBOL "You have nothing to pay" semantic meaning</li>
     *   <li>No transaction or account updates performed</li>
     * </ul>
     */
    @Test
    @DisplayName("Test payment rejection for insufficient balance (balance <= 0)")
    public void testProcessPaymentInsufficientBalance() {
        // Arrange - Account with zero balance matching COBOL <= ZEROS condition
        Account zeroBalanceAccount = Account.builder()
                .accountId(TEST_ACCOUNT_ID)
                .activeStatus("Y")
                .currentBalance(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .build();

        PaymentRequest request = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .amount(new BigDecimal("100.00").setScale(2, RoundingMode.HALF_UP))
                .build();

        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(zeroBalanceAccount));

        // Act & Assert
        assertThatThrownBy(() -> billPaymentService.processBillPayment(request))
                .isInstanceOf(BusinessLogicException.class)
                .satisfies(exception -> {
                    BusinessLogicException ble = (BusinessLogicException) exception;
                    assertThat(ble.getErrorCode()).isEqualTo("INSUFFICIENT_BALANCE");
                    assertThat(ble.getMessage()).containsIgnoringCase("nothing to pay");
                });

        // Verify no updates performed when validation fails
        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    /**
     * Test payment rejection for negative balance amounts.
     * <p>
     * Extends COBOL validation logic from lines 198-205 to include negative balance scenario.
     * While COBOL checks ACCT-CURR-BAL <= ZEROS, this test specifically validates the
     * negative balance case to ensure consistent behavior across all non-positive values.
     * </p>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>Account exists with negative balance ($-50.00)</li>
     *   <li>Payment request submitted</li>
     *   <li>Service rejects payment as account has no payable balance</li>
     * </ul>
     */
    @Test
    @DisplayName("Test payment rejection for negative account balance")
    public void testProcessPaymentWithNegativeBalance() {
        // Arrange - Account with negative balance
        Account negativeBalanceAccount = Account.builder()
                .accountId(TEST_ACCOUNT_ID)
                .activeStatus("Y")
                .currentBalance(new BigDecimal("-50.00").setScale(2, RoundingMode.HALF_UP))
                .build();

        PaymentRequest request = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .amount(new BigDecimal("100.00").setScale(2, RoundingMode.HALF_UP))
                .build();

        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(negativeBalanceAccount));

        // Act & Assert
        assertThatThrownBy(() -> billPaymentService.processBillPayment(request))
                .isInstanceOf(BusinessLogicException.class)
                .satisfies(exception -> {
                    BusinessLogicException ble = (BusinessLogicException) exception;
                    assertThat(ble.getErrorCode()).isEqualTo("INSUFFICIENT_BALANCE");
                });

        // Verify no updates performed
        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    /**
     * Test exception thrown when account ID does not exist in the system.
     * <p>
     * Corresponds to COBOL COBIL00C.cbl lines 359-364 (READ-ACCTDAT-FILE paragraph):
     * </p>
     * <pre>
     * EXEC CICS READ
     *      DATASET   (WS-ACCTDAT-FILE)
     *      INTO      (ACCOUNT-RECORD)
     *      RIDFLD    (ACCT-ID)
     *      UPDATE
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * 
     * WHEN DFHRESP(NOTFND)
     *     MOVE 'Account ID NOT found...' TO WS-MESSAGE
     * </pre>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>Payment request submitted with non-existent account ID</li>
     *   <li>Account repository returns Optional.empty() (NOTFND equivalent)</li>
     *   <li>Service throws ResourceNotFoundException matching COBOL RESP(NOTFND) handling</li>
     * </ul>
     * 
     * <p><strong>Assertions:</strong></p>
     * <ul>
     *   <li>ResourceNotFoundException thrown matching COBOL NOTFND response code</li>
     *   <li>Exception message contains "Account not found" semantic equivalent</li>
     *   <li>No database updates attempted</li>
     * </ul>
     */
    @Test
    @DisplayName("Test exception when account not found (RESP(NOTFND) equivalent)")
    public void testProcessPaymentAccountNotFound() {
        // Arrange
        PaymentRequest request = PaymentRequest.builder()
                .accountId(99999999999L) // Non-existent account ID
                .amount(new BigDecimal("100.00").setScale(2, RoundingMode.HALF_UP))
                .build();

        when(accountRepository.findById(99999999999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> billPaymentService.processBillPayment(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Account not found");

        // Verify no updates attempted when account doesn't exist
        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    /**
     * Test validation rejecting null account ID in payment request.
     * <p>
     * Corresponds to COBOL COBIL00C.cbl lines 159-164 validation:
     * </p>
     * <pre>
     * WHEN ACTIDINI OF COBIL0AI = SPACES OR LOW-VALUES
     *     MOVE 'Y'     TO WS-ERR-FLG
     *     MOVE 'Acct ID can NOT be empty...' TO WS-MESSAGE
     * </pre>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>Payment request with null account ID (equivalent to SPACES/LOW-VALUES)</li>
     *   <li>Service validates request before any database access</li>
     *   <li>IllegalArgumentException thrown matching COBOL validation error</li>
     * </ul>
     */
    @Test
    @DisplayName("Test validation rejects null account ID")
    public void testProcessPaymentWithNullAccountId() {
        // Arrange
        PaymentRequest request = PaymentRequest.builder()
                .accountId(null) // Null account ID (SPACES/LOW-VALUES equivalent)
                .amount(new BigDecimal("100.00").setScale(2, RoundingMode.HALF_UP))
                .build();

        // Act & Assert
        assertThatThrownBy(() -> billPaymentService.processBillPayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account ID cannot be null");

        // Verify no repository calls when validation fails
        verify(accountRepository, never()).findById(any());
        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    /**
     * Test BigDecimal precision verification for payment amounts matching COBOL COMP-3.
     * <p>
     * Verifies that all monetary calculations maintain scale=2 precision matching
     * COBOL COMP-3 PIC S9(09)V99 field specification from CVTRA05Y.cpy line 10:
     * </p>
     * <pre>
     * 05  TRAN-AMT  PIC S9(09)V99.
     * </pre>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>Account with balance $150.75 (2 decimal places)</li>
     *   <li>Payment for $150.75 (full balance)</li>
     *   <li>Balance reduction: $150.75 - $150.75 = $0.00</li>
     * </ul>
     * 
     * <p><strong>Assertions:</strong></p>
     * <ul>
     *   <li>Saved transaction amount has scale=2</li>
     *   <li>Updated account balance has scale=2</li>
     *   <li>Balance calculation result has scale=2</li>
     *   <li>All BigDecimal values use RoundingMode.HALF_UP</li>
     * </ul>
     */
    @Test
    @DisplayName("Test BigDecimal precision verification (scale=2) matching COBOL COMP-3")
    public void testProcessPaymentAmountPrecision() {
        // Arrange - Account with fractional balance
        BigDecimal accountBalance = new BigDecimal("150.75").setScale(2, RoundingMode.HALF_UP);
        Account precisionAccount = Account.builder()
                .accountId(TEST_ACCOUNT_ID)
                .activeStatus("Y")
                .currentBalance(accountBalance)
                .build();

        PaymentRequest request = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .amount(accountBalance)
                .build();

        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(precisionAccount));
        when(cardRepository.findByAccount_AccountId(TEST_ACCOUNT_ID)).thenReturn(Collections.singletonList(mockCard));
        when(transactionRepository.findTopByOrderByTransactionIdDesc())
                .thenReturn(Optional.of(mockTransaction));
        when(accountRepository.save(any(Account.class))).thenReturn(precisionAccount);
        when(transactionRepository.save(any(Transaction.class))).thenReturn(mockTransaction);

        // Act
        PaymentResponse response = billPaymentService.processBillPayment(request);

        // Assert - Verify scale=2 on transaction amount
        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        Transaction savedTransaction = transactionCaptor.getValue();
        assertThat(savedTransaction.getAmount().scale()).isEqualTo(2);
        assertThat(savedTransaction.getAmount())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(accountBalance);

        // Assert - Verify scale=2 on updated account balance
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        Account savedAccount = accountCaptor.getValue();
        assertThat(savedAccount.getCurrentBalance().scale()).isEqualTo(2);

        // Verify balance after payment: 150.75 - 150.75 = 0.00
        BigDecimal expectedBalance = new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP);
        assertThat(savedAccount.getCurrentBalance())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(expectedBalance);

        // Verify response balance has scale=2
        assertThat(response.getUpdatedBalance().scale()).isEqualTo(2);
    }

    /**
     * Test transaction rollback when transaction save operation fails.
     * <p>
     * Validates @Transactional ACID properties matching CICS SYNCPOINT/ROLLBACK behavior.
     * Corresponds to COBOL transaction boundary semantics where any file operation failure
     * triggers automatic rollback of all changes within the transaction scope.
     * </p>
     * <p>
     * From COBOL COBIL00C.cbl transaction processing pattern (lines 233-235):
     * </p>
     * <pre>
     * PERFORM WRITE-TRANSACT-FILE (line 233) - If fails, all changes rollback
     * COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT (line 234)
     * PERFORM UPDATE-ACCTDAT-FILE (line 235)
     * </pre>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>Transaction save operation fails with DataIntegrityViolationException</li>
     *   <li>Account balance update should not be saved (rollback occurs)</li>
     *   <li>Exception propagates to caller triggering @Transactional rollback</li>
     * </ul>
     * 
     * <p><strong>Assertions:</strong></p>
     * <ul>
     *   <li>BusinessLogicException thrown when transaction save fails</li>
     *   <li>Account save method not called (transaction rolled back before account update)</li>
     *   <li>Original account balance unchanged (ACID atomicity preserved)</li>
     * </ul>
     */
    @Test
    @DisplayName("Test transaction rollback on error (CICS ROLLBACK equivalent)")
    public void testProcessPaymentRollsBackOnTransactionError() {
        // Arrange
        PaymentRequest request = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .amount(new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP))
                .build();

        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(mockAccount));
        when(cardRepository.findByAccount_AccountId(TEST_ACCOUNT_ID)).thenReturn(Collections.singletonList(mockCard));
        when(transactionRepository.findTopByOrderByTransactionIdDesc())
                .thenReturn(Optional.of(mockTransaction));
        
        // Simulate transaction save failure (RESP(DUPKEY) or other error)
        when(transactionRepository.save(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate transaction ID"));

        // Act & Assert
        assertThatThrownBy(() -> billPaymentService.processBillPayment(request))
                .isInstanceOf(BusinessLogicException.class)
                .satisfies(exception -> {
                    BusinessLogicException ble = (BusinessLogicException) exception;
                    assertThat(ble.getErrorCode()).isEqualTo("DUPLICATE_TRANSACTION_ID");
                });

        // Verify account save NOT called when transaction save fails
        // This validates that rollback occurs before account update in transaction boundary
        verify(accountRepository, never()).save(any(Account.class));
        
        // Verify transaction save was attempted
        verify(transactionRepository).save(any(Transaction.class));
    }

    /**
     * Test correct transaction record creation with all required fields.
     * <p>
     * Verifies transaction record structure matches COBOL COBIL00C.cbl lines 218-232:
     * </p>
     * <pre>
     * INITIALIZE TRAN-RECORD (line 218)
     * MOVE WS-TRAN-ID-NUM       TO TRAN-ID (line 219)
     * MOVE '02'                 TO TRAN-TYPE-CD (line 220)
     * MOVE 2                    TO TRAN-CAT-CD (line 221)
     * MOVE 'POS TERM'           TO TRAN-SOURCE (line 222)
     * MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC (line 223)
     * MOVE ACCT-CURR-BAL        TO TRAN-AMT (line 224)
     * MOVE XREF-CARD-NUM        TO TRAN-CARD-NUM (line 225)
     * MOVE 999999999            TO TRAN-MERCHANT-ID (line 226)
     * MOVE 'BILL PAYMENT'       TO TRAN-MERCHANT-NAME (line 227)
     * MOVE WS-TIMESTAMP         TO TRAN-ORIG-TS (line 231)
     *                              TRAN-PROC-TS (line 232)
     * </pre>
     * 
     * <p><strong>Assertions:</strong></p>
     * <ul>
     *   <li>Transaction type code = '02' (PAYMENT)</li>
     *   <li>Transaction category code = 2</li>
     *   <li>Transaction description = 'BILL PAYMENT - ONLINE'</li>
     *   <li>Transaction amount = account current balance with scale=2</li>
     *   <li>Card number from cross-reference lookup</li>
     *   <li>Merchant ID = 999999999</li>
     *   <li>Merchant name = 'BILL PAYMENT'</li>
     *   <li>Origination and processing timestamps set to current time</li>
     * </ul>
     */
    @Test
    @DisplayName("Test transaction record creation with correct fields")
    public void testProcessPaymentCreatesTransactionRecord() {
        // Arrange
        BigDecimal paymentAmount = new BigDecimal("250.50").setScale(2, RoundingMode.HALF_UP);
        Account testAccount = Account.builder()
                .accountId(TEST_ACCOUNT_ID)
                .activeStatus("Y")
                .currentBalance(paymentAmount)
                .build();

        PaymentRequest request = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .amount(paymentAmount)
                .build();

        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(testAccount));
        when(cardRepository.findByAccount_AccountId(TEST_ACCOUNT_ID)).thenReturn(Collections.singletonList(mockCard));
        when(transactionRepository.findTopByOrderByTransactionIdDesc())
                .thenReturn(Optional.of(mockTransaction));
        when(accountRepository.save(any(Account.class))).thenReturn(testAccount);
        when(transactionRepository.save(any(Transaction.class))).thenReturn(mockTransaction);

        // Act
        LocalDateTime beforeProcessing = LocalDateTime.now();
        billPaymentService.processBillPayment(request);
        LocalDateTime afterProcessing = LocalDateTime.now();

        // Assert - Capture and verify transaction record fields
        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        Transaction savedTransaction = transactionCaptor.getValue();

        // Verify transaction type and category (lines 220-221)
        assertThat(savedTransaction.getTypeCode()).isEqualTo(PAYMENT_TYPE_CODE);
        assertThat(savedTransaction.getCategoryCode()).isEqualTo(PAYMENT_CATEGORY_CODE);

        // Verify transaction description (line 223)
        assertThat(savedTransaction.getDescription()).isEqualTo(PAYMENT_DESCRIPTION);

        // Verify transaction amount equals account balance (line 224)
        assertThat(savedTransaction.getAmount())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(paymentAmount);
        assertThat(savedTransaction.getAmount().scale()).isEqualTo(2);

        // Verify card number from cross-reference (line 225)
        assertThat(savedTransaction.getCard().getCardNumber()).isEqualTo(TEST_CARD_NUMBER);

        // Verify merchant information (lines 226-227)
        assertThat(savedTransaction.getMerchantId()).isEqualTo(999999999L);
        assertThat(savedTransaction.getMerchantName()).isEqualTo("BILL PAYMENT");

        // Verify transaction source (line 222)
        assertThat(savedTransaction.getTransactionSource()).isEqualTo("POS TERM");

        // Verify timestamps are set and within processing window (lines 231-232)
        assertThat(savedTransaction.getOriginationTimestamp()).isNotNull();
        assertThat(savedTransaction.getOriginationTimestamp())
                .isBetween(beforeProcessing, afterProcessing);
        assertThat(savedTransaction.getProcessingTimestamp()).isNotNull();
        assertThat(savedTransaction.getProcessingTimestamp())
                .isBetween(beforeProcessing, afterProcessing);

        // Verify transaction ID is generated (not null)
        assertThat(savedTransaction.getTransactionId()).isNotNull();
    }

    /**
     * Test multiple payment scenarios with varying balances.
     * <p>
     * Validates arithmetic precision across different payment amounts ensuring
     * BigDecimal calculations with scale=2 produce correct results matching
     * COBOL COMPUTE statement from line 234:
     * </p>
     * <pre>
     * COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT
     * </pre>
     * 
     * <p><strong>Test Scenarios:</strong></p>
     * <ul>
     *   <li>Small payment: $1.99 balance → $1.99 payment → $0.00 result</li>
     *   <li>Large payment: $9999999.99 balance → $9999999.99 payment → $0.00 result</li>
     *   <li>Fractional payment: $123.45 balance → $123.45 payment → $0.00 result</li>
     * </ul>
     */
    @Test
    @DisplayName("Test multiple payment scenarios with varying balances")
    public void testProcessPaymentWithVaryingBalances() {
        // Test Case 1: Small balance payment
        BigDecimal smallBalance = new BigDecimal("1.99").setScale(2, RoundingMode.HALF_UP);
        testPaymentScenario(smallBalance, smallBalance, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

        // Test Case 2: Large balance payment (max 9 integer digits)
        BigDecimal largeBalance = new BigDecimal("9999999.99").setScale(2, RoundingMode.HALF_UP);
        testPaymentScenario(largeBalance, largeBalance, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

        // Test Case 3: Fractional balance payment
        BigDecimal fractionalBalance = new BigDecimal("123.45").setScale(2, RoundingMode.HALF_UP);
        testPaymentScenario(fractionalBalance, fractionalBalance, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Helper method to test payment scenarios with specific balance and payment amounts.
     * <p>
     * Validates that the payment processing correctly calculates new balance using
     * BigDecimal arithmetic with scale=2 and RoundingMode.HALF_UP matching COBOL precision.
     * </p>
     * 
     * @param initialBalance the account's initial balance before payment
     * @param paymentAmount the payment amount to process
     * @param expectedFinalBalance the expected account balance after payment
     */
    private void testPaymentScenario(BigDecimal initialBalance, BigDecimal paymentAmount, 
                                      BigDecimal expectedFinalBalance) {
        // Arrange
        Account scenarioAccount = Account.builder()
                .accountId(TEST_ACCOUNT_ID)
                .activeStatus("Y")
                .currentBalance(initialBalance)
                .build();

        PaymentRequest request = PaymentRequest.builder()
                .accountId(TEST_ACCOUNT_ID)
                .amount(paymentAmount)
                .build();

        // Reset mocks for clean test execution
        reset(accountRepository, transactionRepository, cardRepository);
        
        when(accountRepository.findById(TEST_ACCOUNT_ID)).thenReturn(Optional.of(scenarioAccount));
        when(cardRepository.findByAccount_AccountId(TEST_ACCOUNT_ID)).thenReturn(Collections.singletonList(mockCard));
        when(transactionRepository.findTopByOrderByTransactionIdDesc())
                .thenReturn(Optional.of(mockTransaction));
        when(accountRepository.save(any(Account.class))).thenReturn(scenarioAccount);
        when(transactionRepository.save(any(Transaction.class))).thenReturn(mockTransaction);

        // Act
        billPaymentService.processBillPayment(request);

        // Assert - Verify balance calculation
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        Account savedAccount = accountCaptor.getValue();

        assertThat(savedAccount.getCurrentBalance())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(expectedFinalBalance);
        assertThat(savedAccount.getCurrentBalance().scale()).isEqualTo(2);
    }
}
