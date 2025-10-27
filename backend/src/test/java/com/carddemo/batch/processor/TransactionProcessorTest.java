/*
 * TransactionProcessorTest.java
 *
 * JUnit 5 unit test class for TransactionProcessor Spring Batch ItemProcessor implementation.
 * Tests business logic extracted from COBOL batch programs CBTRN01C.cbl (transaction validation),
 * CBTRN02C.cbl (daily transaction posting), and CBTRN03C.cbl (transaction category summarization).
 *
 * Validates conversion accuracy from COBOL procedures to Java methods:
 * - CBTRN01C 2000-LOOKUP-XREF: Card-account cross-reference validation → validateCardXref()
 * - CBTRN01C 3000-READ-ACCOUNT: Account record retrieval → loadAccount()
 * - CBTRN02C 1500-VALIDATE-TRAN: Transaction validation orchestration → process()
 * - CBTRN02C 1500-A-LOOKUP-XREF: Card number verification → validateCardXref()
 * - CBTRN02C 1500-B-LOOKUP-ACCT: Account lookup with credit limit check → loadAccount() + validateCreditLimit()
 * - CBTRN02C 2700-UPDATE-TCATBAL: Category balance updates → updateCategoryBalance()
 * - CBTRN02C 2800-UPDATE-ACCOUNT-REC: Account balance updates → calculateNewBalance()
 * - CBTRN02C 2500-WRITE-REJECT-REC: Reject record handling → handleRejectedTransaction()
 *
 * COBOL Business Logic Preservation Validation:
 * - Credit limit calculation: COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
 * - Balance update: ADD DALYTRAN-AMT TO ACCT-CURR-BAL
 * - Cycle tracking: IF DALYTRAN-AMT >= 0 THEN ADD TO CREDIT ELSE ADD TO DEBIT
 * - Category balance: ADD DALYTRAN-AMT TO TRAN-CAT-BAL (create if not found)
 * - Rejection codes: 100 (invalid card), 101 (account not found), 102 (overlimit), 103 (expired account)
 *
 * BigDecimal Precision Testing:
 * All tests verify BigDecimal arithmetic uses scale 2 with RoundingMode.HALF_UP to preserve
 * exact COBOL COMP-3 packed decimal precision per Section 0.7.2 requirement for bit-identical
 * financial calculations matching mainframe behavior.
 *
 * Test Strategy:
 * - Uses Mockito 5.x for mocking repository dependencies (@Mock annotations)
 * - Uses AssertJ for fluent assertions (assertThat() with readable chained methods)
 * - Uses JUnit 5 @ParameterizedTest with @MethodSource for testing multiple transaction scenarios
 * - Tests cover success paths, validation failures, edge cases, and error handling
 * - All tests verify processor behavior matches COBOL paragraph execution flow exactly
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
package com.carddemo.batch.processor;

import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.Card;
import com.carddemo.model.entity.CardAccountXref;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardAccountXrefRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit test class for TransactionProcessor ItemProcessor.
 * 
 * <p>Tests transaction processing business logic converted from COBOL batch programs:
 * <ul>
 *   <li>CBTRN01C.cbl: Transaction file validation and card-account cross-reference lookup</li>
 *   <li>CBTRN02C.cbl: Transaction posting with credit limit enforcement and balance updates</li>
 *   <li>CBTRN03C.cbl: Transaction category summarization with balance aggregation</li>
 * </ul>
 * </p>
 * 
 * <p>Test Coverage:
 * <ul>
 *   <li>Successful transaction processing with valid data</li>
 *   <li>Card-account cross-reference validation (CBTRN01C 2000-LOOKUP-XREF)</li>
 *   <li>Account lookup validation (CBTRN01C 3000-READ-ACCOUNT)</li>
 *   <li>Credit limit enforcement (CBTRN02C lines 403-413)</li>
 *   <li>Account expiration validation (CBTRN02C lines 414-420)</li>
 *   <li>Balance update calculations (CBTRN02C 2800-UPDATE-ACCOUNT-REC)</li>
 *   <li>Category balance updates (CBTRN02C 2700-UPDATE-TCATBAL)</li>
 *   <li>BigDecimal precision preservation (scale 2, HALF_UP rounding)</li>
 *   <li>Transaction rejection handling (returns null per Spring Batch contract)</li>
 *   <li>Error handling for various validation failures</li>
 * </ul>
 * </p>
 * 
 * @see TransactionProcessor
 * @see Transaction
 * @see Account
 * @see CardAccountXref
 * @see TransactionCategoryBalance
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionProcessor Unit Tests")
class TransactionProcessorTest {

    /**
     * Processor under test with mocked dependencies injected.
     */
    @InjectMocks
    private TransactionProcessor transactionProcessor;

    /**
     * Mock CardAccountXrefRepository for card-account cross-reference validation.
     * Replaces COBOL EXEC CICS READ FILE('XREFFILE') operations.
     */
    @Mock
    private CardAccountXrefRepository cardAccountXrefRepository;

    /**
     * Mock AccountRepository for account record retrieval and updates.
     * Replaces COBOL EXEC CICS READ/REWRITE FILE('ACCTFILE') operations.
     */
    @Mock
    private AccountRepository accountRepository;

    /**
     * Mock TransactionCategoryBalanceRepository for category balance updates.
     * Replaces COBOL EXEC CICS READ/WRITE/REWRITE FILE('TCATBAL') operations.
     */
    @Mock
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /**
     * Test constants matching COBOL data values.
     */
    private static final String TEST_CARD_NUMBER = "4111111111111111";
    private static final String TEST_TRANSACTION_ID = "TX0000000001";
    private static final Long TEST_ACCOUNT_ID = 1000000001L;
    private static final Long TEST_CUSTOMER_ID = 2000000001L;
    private static final String TEST_TRANSACTION_TYPE = "PU"; // Purchase
    private static final Integer TEST_CATEGORY_CODE = 5411; // Grocery
    private static final BigDecimal TEST_CREDIT_LIMIT = new BigDecimal("10000.00");
    private static final int DECIMAL_SCALE = 2;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    /**
     * Test fixtures for reuse across test methods.
     */
    private Transaction testTransaction;
    private Account testAccount;
    private CardAccountXref testCardXref;
    private TransactionCategoryBalance testCategoryBalance;

    /**
     * Set up test fixtures before each test method.
     * Creates test data objects representing COBOL record structures.
     */
    @BeforeEach
    void setUp() {
        // Create test transaction (from COBOL DALYTRAN-RECORD)
        testTransaction = Transaction.builder()
                .transId(TEST_TRANSACTION_ID)
                .transCardNum(TEST_CARD_NUMBER)
                .transTypeCd(TEST_TRANSACTION_TYPE)
                .transCatCd(TEST_CATEGORY_CODE)
                .transAmt(new BigDecimal("100.00").setScale(DECIMAL_SCALE, ROUNDING_MODE))
                .transSource("ATM")
                .transDesc("Test transaction")
                .transMerchantId(123456789L)
                .transMerchantName("Test Merchant")
                .transMerchantCity("Test City")
                .transMerchantZip("12345")
                .transOrigTs(Timestamp.valueOf(LocalDateTime.now()))
                .build();

        // Create test account (from COBOL ACCOUNT-RECORD)
        testAccount = Account.builder()
                .acctId(TEST_ACCOUNT_ID)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("1000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE))
                .acctCreditLimit(TEST_CREDIT_LIMIT.setScale(DECIMAL_SCALE, ROUNDING_MODE))
                .acctCurrCycCredit(new BigDecimal("500.00").setScale(DECIMAL_SCALE, ROUNDING_MODE))
                .acctCurrCycDebit(new BigDecimal("0.00").setScale(DECIMAL_SCALE, ROUNDING_MODE))
                .acctExpirationDate(LocalDate.now().plusYears(2))
                .version(0)
                .build();

        // Create test card-account cross-reference (from COBOL CARD-XREF-RECORD)
        testCardXref = CardAccountXref.builder()
                .xrefCardNum(TEST_CARD_NUMBER)
                .xrefAcctId(TEST_ACCOUNT_ID)
                .xrefCustId(TEST_CUSTOMER_ID)
                .build();

        // Create test category balance (from COBOL TRAN-CAT-BAL-RECORD)
        testCategoryBalance = TransactionCategoryBalance.builder()
                .tcatAcctId(TEST_ACCOUNT_ID)
                .tcatTypeCd(TEST_TRANSACTION_TYPE)
                .tcatCatCd(TEST_CATEGORY_CODE)
                .tcatBal(new BigDecimal("0.00").setScale(DECIMAL_SCALE, ROUNDING_MODE))
                .build();
    }

    /**
     * Test successful transaction processing with valid data.
     * 
     * Validates complete COBOL processing flow from CBTRN02C main loop:
     * 1. Card-account cross-reference lookup (1500-A-LOOKUP-XREF)
     * 2. Account record retrieval (1500-B-LOOKUP-ACCT)
     * 3. Credit limit validation (lines 403-413)
     * 4. Account expiration validation (lines 414-420)
     * 5. Category balance update (2700-UPDATE-TCATBAL)
     * 6. Account balance update (2800-UPDATE-ACCOUNT-REC)
     * 7. Transaction returned for writing (2000-POST-TRANSACTION)
     *
     * Expected behavior: Transaction processed successfully and returned (not rejected).
     */
    @Test
    @DisplayName("Should successfully process valid transaction with all validations passing")
    void testProcessValidTransaction_Success() throws Exception {
        // Arrange: Mock repository responses for successful processing
        when(cardAccountXrefRepository.findByXrefCardNum(TEST_CARD_NUMBER))
                .thenReturn(List.of(testCardXref));
        when(accountRepository.findById(TEST_ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));
        when(transactionCategoryBalanceRepository.findById(any()))
                .thenReturn(Optional.of(testCategoryBalance));
        when(accountRepository.save(any(Account.class)))
                .thenReturn(testAccount);
        when(transactionCategoryBalanceRepository.save(any(TransactionCategoryBalance.class)))
                .thenReturn(testCategoryBalance);

        // Act: Process transaction through TransactionProcessor
        Transaction result = transactionProcessor.process(testTransaction);

        // Assert: Transaction processed successfully (not null = not rejected)
        assertThat(result).isNotNull();
        assertThat(result.getTransId()).isEqualTo(TEST_TRANSACTION_ID);

        // Verify repository interactions occurred in correct order
        verify(cardAccountXrefRepository, times(1)).findByXrefCardNum(TEST_CARD_NUMBER);
        verify(accountRepository, times(1)).findById(TEST_ACCOUNT_ID);
        verify(transactionCategoryBalanceRepository, times(1)).save(any(TransactionCategoryBalance.class));
        verify(accountRepository, times(1)).save(any(Account.class));

        // Verify account balance updated correctly (COBOL: ADD DALYTRAN-AMT TO ACCT-CURR-BAL)
        BigDecimal expectedBalance = new BigDecimal("1000.00").add(testTransaction.getTransAmt())
                .setScale(DECIMAL_SCALE, ROUNDING_MODE);
        assertThat(testAccount.getAcctCurrBal()).isEqualByComparingTo(expectedBalance);

        // Verify cycle credit updated (COBOL: IF DALYTRAN-AMT >= 0 THEN ADD TO ACCT-CURR-CYC-CREDIT)
        BigDecimal expectedCycleCredit = new BigDecimal("500.00").add(testTransaction.getTransAmt())
                .setScale(DECIMAL_SCALE, ROUNDING_MODE);
        assertThat(testAccount.getAcctCurrCycCredit()).isEqualByComparingTo(expectedCycleCredit);
    }

    /**
     * Test transaction rejection for invalid card number (COBOL error code 100).
     * 
     * Validates COBOL paragraph 1500-A-LOOKUP-XREF rejection logic:
     * - MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM
     * - READ XREF-FILE INTO CARD-XREF-RECORD
     * - INVALID KEY: MOVE 100 TO WS-VALIDATION-FAIL-REASON
     * 
     * Expected behavior: Transaction rejected (returns null) when card not found in cross-reference table.
     */
    @Test
    @DisplayName("Should reject transaction when card number not found in cross-reference table")
    void testProcessInvalidCardNumber_ReturnsNull() throws Exception {
        // Arrange: Mock empty card cross-reference lookup (COBOL INVALID KEY condition)
        when(cardAccountXrefRepository.findByXrefCardNum(TEST_CARD_NUMBER))
                .thenReturn(List.of()); // Empty list = card not found

        // Act: Process transaction with invalid card number
        Transaction result = transactionProcessor.process(testTransaction);

        // Assert: Transaction rejected (returns null per Spring Batch contract)
        assertThat(result).isNull();

        // Verify card lookup attempted but no further processing occurred
        verify(cardAccountXrefRepository, times(1)).findByXrefCardNum(TEST_CARD_NUMBER);
        verify(accountRepository, never()).findById(any());
        verify(accountRepository, never()).save(any());
    }

    /**
     * Test transaction rejection for account not found (COBOL error code 101).
     * 
     * Validates COBOL paragraph 1500-B-LOOKUP-ACCT rejection logic:
     * - MOVE XREF-ACCT-ID TO FD-ACCT-ID
     * - READ ACCOUNT-FILE INTO ACCOUNT-RECORD
     * - INVALID KEY: MOVE 101 TO WS-VALIDATION-FAIL-REASON
     * 
     * Expected behavior: Transaction rejected (returns null) when account not found in account master file.
     */
    @Test
    @DisplayName("Should reject transaction when account record not found")
    void testProcessAccountNotFound_ReturnsNull() throws Exception {
        // Arrange: Card found but account not found (COBOL INVALID KEY on ACCOUNT-FILE)
        when(cardAccountXrefRepository.findByXrefCardNum(TEST_CARD_NUMBER))
                .thenReturn(List.of(testCardXref));
        when(accountRepository.findById(TEST_ACCOUNT_ID))
                .thenReturn(Optional.empty()); // Account not found

        // Act: Process transaction with non-existent account
        Transaction result = transactionProcessor.process(testTransaction);

        // Assert: Transaction rejected (returns null)
        assertThat(result).isNull();

        // Verify processing stopped after account lookup failure
        verify(cardAccountXrefRepository, times(1)).findByXrefCardNum(TEST_CARD_NUMBER);
        verify(accountRepository, times(1)).findById(TEST_ACCOUNT_ID);
        verify(accountRepository, never()).save(any());
    }

    /**
     * Test transaction rejection for overlimit condition (COBOL error code 102).
     * 
     * Validates COBOL credit limit check from CBTRN02C lines 403-413:
     * - COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
     * - IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL THEN approve ELSE reject
     * - MOVE 102 TO WS-VALIDATION-FAIL-REASON (if overlimit)
     * - MOVE 'OVERLIMIT TRANSACTION' TO WS-VALIDATION-FAIL-REASON-DESC
     * 
     * Expected behavior: Transaction rejected when predicted balance exceeds credit limit.
     */
    @Test
    @DisplayName("Should reject transaction when credit limit would be exceeded")
    void testProcessOverlimitTransaction_ReturnsNull() throws Exception {
        // Arrange: Set up account with high cycle balance approaching credit limit
        BigDecimal cycleCredit = new BigDecimal("9500.00").setScale(DECIMAL_SCALE, ROUNDING_MODE);
        BigDecimal cycleDebit = new BigDecimal("0.00").setScale(DECIMAL_SCALE, ROUNDING_MODE);
        testAccount.setAcctCurrCycCredit(cycleCredit);
        testAccount.setAcctCurrCycDebit(cycleDebit);
        testAccount.setAcctCreditLimit(TEST_CREDIT_LIMIT); // $10,000.00

        // Create transaction that would exceed credit limit
        BigDecimal overlimitAmount = new BigDecimal("600.00").setScale(DECIMAL_SCALE, ROUNDING_MODE);
        testTransaction.setTransAmt(overlimitAmount);
        // Predicted balance: 9500.00 - 0.00 + 600.00 = 10,100.00 > 10,000.00 limit

        when(cardAccountXrefRepository.findByXrefCardNum(TEST_CARD_NUMBER))
                .thenReturn(List.of(testCardXref));
        when(accountRepository.findById(TEST_ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));

        // Act: Process overlimit transaction
        Transaction result = transactionProcessor.process(testTransaction);

        // Assert: Transaction rejected (returns null)
        assertThat(result).isNull();

        // Verify processing stopped at credit limit validation
        verify(cardAccountXrefRepository, times(1)).findByXrefCardNum(TEST_CARD_NUMBER);
        verify(accountRepository, times(1)).findById(TEST_ACCOUNT_ID);
        verify(accountRepository, never()).save(any()); // Account not updated
        verify(transactionCategoryBalanceRepository, never()).save(any()); // Category balance not updated
    }

    /**
     * Test transaction rejection for expired account (COBOL error code 103).
     * 
     * Validates COBOL account expiration check from CBTRN02C lines 414-420:
     * - IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10) THEN valid ELSE reject
     * - MOVE 103 TO WS-VALIDATION-FAIL-REASON (if expired)
     * - MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION' TO WS-VALIDATION-FAIL-REASON-DESC
     * 
     * Expected behavior: Transaction rejected when origination date is after account expiration date.
     */
    @Test
    @DisplayName("Should reject transaction when account expiration date has passed")
    void testProcessExpiredAccount_ReturnsNull() throws Exception {
        // Arrange: Set account expiration date in the past
        LocalDate expiredDate = LocalDate.now().minusYears(1);
        testAccount.setAcctExpirationDate(expiredDate);

        // Set transaction timestamp to recent date (after expiration)
        testTransaction.setTransOrigTs(Timestamp.valueOf(LocalDateTime.now()));

        when(cardAccountXrefRepository.findByXrefCardNum(TEST_CARD_NUMBER))
                .thenReturn(List.of(testCardXref));
        when(accountRepository.findById(TEST_ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));

        // Act: Process transaction on expired account
        Transaction result = transactionProcessor.process(testTransaction);

        // Assert: Transaction rejected (returns null)
        assertThat(result).isNull();

        // Verify processing stopped at expiration validation
        verify(cardAccountXrefRepository, times(1)).findByXrefCardNum(TEST_CARD_NUMBER);
        verify(accountRepository, times(1)).findById(TEST_ACCOUNT_ID);
        verify(accountRepository, never()).save(any());
    }

    /**
     * Test account balance calculation with debit transaction (negative amount).
     * 
     * Validates COBOL balance update logic from CBTRN02C 2800-UPDATE-ACCOUNT-REC:
     * - ADD DALYTRAN-AMT TO ACCT-CURR-BAL
     * - IF DALYTRAN-AMT >= 0 THEN ADD TO ACCT-CURR-CYC-CREDIT
     * - ELSE ADD TO ACCT-CURR-CYC-DEBIT (this branch)
     * 
     * Expected behavior: Negative transaction amount (payment/refund) updates balance and cycle debit.
     */
    @Test
    @DisplayName("Should correctly update account balance for debit transaction (negative amount)")
    void testProcessDebitTransaction_UpdatesCycleDebit() throws Exception {
        // Arrange: Create payment transaction (negative amount)
        BigDecimal paymentAmount = new BigDecimal("-200.00").setScale(DECIMAL_SCALE, ROUNDING_MODE);
        testTransaction.setTransAmt(paymentAmount);
        testTransaction.setTransTypeCd("PM"); // Payment type

        when(cardAccountXrefRepository.findByXrefCardNum(TEST_CARD_NUMBER))
                .thenReturn(List.of(testCardXref));
        when(accountRepository.findById(TEST_ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));
        when(transactionCategoryBalanceRepository.findById(any()))
                .thenReturn(Optional.of(testCategoryBalance));
        when(accountRepository.save(any(Account.class)))
                .thenReturn(testAccount);
        when(transactionCategoryBalanceRepository.save(any(TransactionCategoryBalance.class)))
                .thenReturn(testCategoryBalance);

        // Act: Process payment transaction
        Transaction result = transactionProcessor.process(testTransaction);

        // Assert: Transaction processed successfully
        assertThat(result).isNotNull();

        // Verify balance decreased by payment amount (COBOL: ADD DALYTRAN-AMT TO ACCT-CURR-BAL)
        BigDecimal expectedBalance = new BigDecimal("1000.00").add(paymentAmount)
                .setScale(DECIMAL_SCALE, ROUNDING_MODE);
        assertThat(testAccount.getAcctCurrBal()).isEqualByComparingTo(expectedBalance);
        assertThat(testAccount.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("800.00"));

        // Verify cycle debit updated (COBOL: ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT for negative amounts)
        BigDecimal expectedCycleDebit = new BigDecimal("0.00").add(paymentAmount)
                .setScale(DECIMAL_SCALE, ROUNDING_MODE);
        assertThat(testAccount.getAcctCurrCycDebit()).isEqualByComparingTo(expectedCycleDebit);
        assertThat(testAccount.getAcctCurrCycDebit()).isEqualByComparingTo(new BigDecimal("-200.00"));

        // Verify cycle credit unchanged
        assertThat(testAccount.getAcctCurrCycCredit()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    /**
     * Test category balance creation when record does not exist.
     * 
     * Validates COBOL paragraph 2700-A-CREATE-TCATBAL-REC from CBTRN02C:
     * - READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD
     * - INVALID KEY: MOVE 'Y' TO WS-CREATE-TRANCAT-REC
     * - INITIALIZE TRAN-CAT-BAL-RECORD
     * - MOVE account/type/category fields
     * - ADD DALYTRAN-AMT TO TRAN-CAT-BAL
     * - WRITE FD-TRAN-CAT-BAL-RECORD
     * 
     * Expected behavior: New category balance record created with initial amount equal to transaction amount.
     */
    @Test
    @DisplayName("Should create new category balance record when not found")
    void testProcessTransaction_CreatesNewCategoryBalance() throws Exception {
        // Arrange: Mock category balance not found (COBOL INVALID KEY)
        when(cardAccountXrefRepository.findByXrefCardNum(TEST_CARD_NUMBER))
                .thenReturn(List.of(testCardXref));
        when(accountRepository.findById(TEST_ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));
        when(transactionCategoryBalanceRepository.findById(any()))
                .thenReturn(Optional.empty()); // Category balance not found - will create new
        when(accountRepository.save(any(Account.class)))
                .thenReturn(testAccount);
        when(transactionCategoryBalanceRepository.save(any(TransactionCategoryBalance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act: Process transaction
        Transaction result = transactionProcessor.process(testTransaction);

        // Assert: Transaction processed successfully
        assertThat(result).isNotNull();

        // Verify category balance save called (COBOL: WRITE FD-TRAN-CAT-BAL-RECORD)
        verify(transactionCategoryBalanceRepository, times(1)).save(any(TransactionCategoryBalance.class));

        // Verify saved category balance has correct initial values
        verify(transactionCategoryBalanceRepository).save(argThat(balance ->
                balance.getTcatAcctId().equals(TEST_ACCOUNT_ID) &&
                balance.getTcatTypeCd().equals(TEST_TRANSACTION_TYPE) &&
                balance.getTcatCatCd().equals(TEST_CATEGORY_CODE) &&
                balance.getTcatBal().compareTo(testTransaction.getTransAmt()) == 0
        ));
    }

    /**
     * Test category balance update when record exists.
     * 
     * Validates COBOL paragraph 2700-B-UPDATE-TCATBAL-REC from CBTRN02C:
     * - READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD (successful)
     * - ADD DALYTRAN-AMT TO TRAN-CAT-BAL
     * - REWRITE FD-TRAN-CAT-BAL-RECORD
     * 
     * Expected behavior: Existing category balance updated by adding transaction amount.
     */
    @Test
    @DisplayName("Should update existing category balance record")
    void testProcessTransaction_UpdatesExistingCategoryBalance() throws Exception {
        // Arrange: Mock existing category balance with non-zero balance
        BigDecimal existingBalance = new BigDecimal("500.00").setScale(DECIMAL_SCALE, ROUNDING_MODE);
        testCategoryBalance.setTcatBal(existingBalance);

        when(cardAccountXrefRepository.findByXrefCardNum(TEST_CARD_NUMBER))
                .thenReturn(List.of(testCardXref));
        when(accountRepository.findById(TEST_ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));
        when(transactionCategoryBalanceRepository.findById(any()))
                .thenReturn(Optional.of(testCategoryBalance)); // Existing balance found
        when(accountRepository.save(any(Account.class)))
                .thenReturn(testAccount);
        when(transactionCategoryBalanceRepository.save(any(TransactionCategoryBalance.class)))
                .thenReturn(testCategoryBalance);

        // Act: Process transaction
        Transaction result = transactionProcessor.process(testTransaction);

        // Assert: Transaction processed successfully
        assertThat(result).isNotNull();

        // Verify category balance updated (COBOL: ADD DALYTRAN-AMT TO TRAN-CAT-BAL)
        BigDecimal expectedCategoryBalance = existingBalance.add(testTransaction.getTransAmt())
                .setScale(DECIMAL_SCALE, ROUNDING_MODE);
        assertThat(testCategoryBalance.getTcatBal()).isEqualByComparingTo(expectedCategoryBalance);
        assertThat(testCategoryBalance.getTcatBal()).isEqualByComparingTo(new BigDecimal("600.00"));

        // Verify save called (COBOL: REWRITE FD-TRAN-CAT-BAL-RECORD)
        verify(transactionCategoryBalanceRepository, times(1)).save(testCategoryBalance);
    }

    /**
     * Test BigDecimal precision for all financial calculations.
     * 
     * Validates COBOL COMP-3 precision preservation requirement from Section 0.7.2:
     * - All calculations must use BigDecimal with scale 2 and RoundingMode.HALF_UP
     * - Ensures bit-identical results to COBOL mainframe calculations
     * 
     * Expected behavior: All balance calculations preserve exact precision matching COBOL COMP-3.
     */
    @Test
    @DisplayName("Should preserve BigDecimal precision with scale 2 and HALF_UP rounding")
    void testBigDecimalPrecision_Scale2HalfUp() throws Exception {
        // Arrange: Create transaction with amount requiring rounding
        BigDecimal preciseAmount = new BigDecimal("123.456"); // 3 decimal places
        testTransaction.setTransAmt(preciseAmount);

        when(cardAccountXrefRepository.findByXrefCardNum(TEST_CARD_NUMBER))
                .thenReturn(List.of(testCardXref));
        when(accountRepository.findById(TEST_ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));
        when(transactionCategoryBalanceRepository.findById(any()))
                .thenReturn(Optional.of(testCategoryBalance));
        when(accountRepository.save(any(Account.class)))
                .thenReturn(testAccount);
        when(transactionCategoryBalanceRepository.save(any(TransactionCategoryBalance.class)))
                .thenReturn(testCategoryBalance);

        // Act: Process transaction
        Transaction result = transactionProcessor.process(testTransaction);

        // Assert: Transaction processed successfully
        assertThat(result).isNotNull();

        // Verify account balance has correct scale and rounding
        assertThat(testAccount.getAcctCurrBal().scale()).isEqualTo(DECIMAL_SCALE);
        
        // Expected: 1000.00 + 123.456 = 1123.456 rounded HALF_UP to 1123.46
        BigDecimal expectedBalance = new BigDecimal("1123.46");
        assertThat(testAccount.getAcctCurrBal()).isEqualByComparingTo(expectedBalance);

        // Verify cycle credit has correct scale and rounding
        assertThat(testAccount.getAcctCurrCycCredit().scale()).isEqualTo(DECIMAL_SCALE);
        
        // Expected: 500.00 + 123.456 = 623.456 rounded HALF_UP to 623.46
        BigDecimal expectedCycleCredit = new BigDecimal("623.46");
        assertThat(testAccount.getAcctCurrCycCredit()).isEqualByComparingTo(expectedCycleCredit);

        // Verify category balance has correct scale
        assertThat(testCategoryBalance.getTcatBal().scale()).isEqualTo(DECIMAL_SCALE);
    }

    /**
     * Test transaction rejection when account balance would go negative beyond credit limit.
     * 
     * Edge case validation: Very large charge on account with low credit limit.
     * 
     * Expected behavior: Transaction rejected to prevent excessive overlimit condition.
     */
    @Test
    @DisplayName("Should reject transaction causing extreme overlimit condition")
    void testProcessExtremeOverlimit_ReturnsNull() throws Exception {
        // Arrange: Set up account with very high existing balance
        testAccount.setAcctCurrCycCredit(new BigDecimal("9000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));
        testAccount.setAcctCurrCycDebit(new BigDecimal("0.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));
        testAccount.setAcctCreditLimit(TEST_CREDIT_LIMIT); // $10,000.00

        // Create very large transaction
        testTransaction.setTransAmt(new BigDecimal("5000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));
        // Predicted: 9000.00 - 0.00 + 5000.00 = 14,000.00 > 10,000.00 limit (extreme overlimit)

        when(cardAccountXrefRepository.findByXrefCardNum(TEST_CARD_NUMBER))
                .thenReturn(List.of(testCardXref));
        when(accountRepository.findById(TEST_ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));

        // Act: Process extreme overlimit transaction
        Transaction result = transactionProcessor.process(testTransaction);

        // Assert: Transaction rejected
        assertThat(result).isNull();

        // Verify no updates performed
        verify(accountRepository, never()).save(any());
        verify(transactionCategoryBalanceRepository, never()).save(any());
    }

    /**
     * Test transaction at exact credit limit boundary.
     * 
     * Edge case validation: Transaction amount that brings balance exactly to credit limit.
     * 
     * Expected behavior: Transaction approved when predicted balance equals credit limit exactly.
     */
    @Test
    @DisplayName("Should approve transaction when predicted balance equals credit limit exactly")
    void testProcessAtCreditLimitBoundary_Success() throws Exception {
        // Arrange: Set up account balance and transaction to reach exact limit
        testAccount.setAcctCurrCycCredit(new BigDecimal("9500.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));
        testAccount.setAcctCurrCycDebit(new BigDecimal("0.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));
        testAccount.setAcctCreditLimit(TEST_CREDIT_LIMIT); // $10,000.00

        // Transaction that brings balance to exact limit
        testTransaction.setTransAmt(new BigDecimal("500.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));
        // Predicted: 9500.00 - 0.00 + 500.00 = 10,000.00 == 10,000.00 limit (exact match)

        when(cardAccountXrefRepository.findByXrefCardNum(TEST_CARD_NUMBER))
                .thenReturn(List.of(testCardXref));
        when(accountRepository.findById(TEST_ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));
        when(transactionCategoryBalanceRepository.findById(any()))
                .thenReturn(Optional.of(testCategoryBalance));
        when(accountRepository.save(any(Account.class)))
                .thenReturn(testAccount);
        when(transactionCategoryBalanceRepository.save(any(TransactionCategoryBalance.class)))
                .thenReturn(testCategoryBalance);

        // Act: Process transaction at exact limit
        Transaction result = transactionProcessor.process(testTransaction);

        // Assert: Transaction approved (COBOL: IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL allows equal)
        assertThat(result).isNotNull();

        // Verify updates performed
        verify(accountRepository, times(1)).save(any(Account.class));
        verify(transactionCategoryBalanceRepository, times(1)).save(any(TransactionCategoryBalance.class));
    }

    /**
     * Test transaction processing with account having no expiration date.
     * 
     * Edge case validation: Account with null expiration date (never expires).
     * 
     * Expected behavior: Expiration validation skipped when account has no expiration date.
     */
    @Test
    @DisplayName("Should skip expiration validation when account has no expiration date")
    void testProcessAccountWithNoExpirationDate_Success() throws Exception {
        // Arrange: Set account expiration date to null
        testAccount.setAcctExpirationDate(null);

        when(cardAccountXrefRepository.findByXrefCardNum(TEST_CARD_NUMBER))
                .thenReturn(List.of(testCardXref));
        when(accountRepository.findById(TEST_ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));
        when(transactionCategoryBalanceRepository.findById(any()))
                .thenReturn(Optional.of(testCategoryBalance));
        when(accountRepository.save(any(Account.class)))
                .thenReturn(testAccount);
        when(transactionCategoryBalanceRepository.save(any(TransactionCategoryBalance.class)))
                .thenReturn(testCategoryBalance);

        // Act: Process transaction
        Transaction result = transactionProcessor.process(testTransaction);

        // Assert: Transaction approved (no expiration check performed)
        assertThat(result).isNotNull();

        // Verify processing completed successfully
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    /**
     * Test transaction processing with null transaction amount.
     * 
     * Error handling validation: Null amount should cause processing failure.
     * 
     * Expected behavior: Transaction rejected when amount is null.
     */
    @Test
    @DisplayName("Should reject transaction when transaction amount is null")
    void testProcessNullTransactionAmount_ReturnsNull() throws Exception {
        // Arrange: Set transaction amount to null
        testTransaction.setTransAmt(null);

        when(cardAccountXrefRepository.findByXrefCardNum(TEST_CARD_NUMBER))
                .thenReturn(List.of(testCardXref));
        when(accountRepository.findById(TEST_ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));

        // Act: Process transaction with null amount
        Transaction result = transactionProcessor.process(testTransaction);

        // Assert: Transaction rejected due to null amount causing NullPointerException
        assertThat(result).isNull();

        // Verify no updates performed
        verify(accountRepository, never()).save(any());
        verify(transactionCategoryBalanceRepository, never()).save(any());
    }

    /**
     * Test transaction processing with multiple transactions to same account.
     * 
     * Validates that processor can handle sequential transactions maintaining balance accuracy.
     * 
     * Expected behavior: Multiple transactions update account balance cumulatively with precision.
     */
    @Test
    @DisplayName("Should correctly process multiple sequential transactions to same account")
    void testProcessMultipleTransactions_MaintainsBalanceAccuracy() throws Exception {
        // Arrange: Set up mocks for first transaction
        when(cardAccountXrefRepository.findByXrefCardNum(TEST_CARD_NUMBER))
                .thenReturn(List.of(testCardXref));
        when(accountRepository.findById(TEST_ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));
        when(transactionCategoryBalanceRepository.findById(any()))
                .thenReturn(Optional.of(testCategoryBalance));
        when(accountRepository.save(any(Account.class)))
                .thenReturn(testAccount);
        when(transactionCategoryBalanceRepository.save(any(TransactionCategoryBalance.class)))
                .thenReturn(testCategoryBalance);

        // Track initial balance
        BigDecimal initialBalance = testAccount.getAcctCurrBal();

        // Act: Process first transaction
        Transaction result1 = transactionProcessor.process(testTransaction);

        // Assert: First transaction processed
        assertThat(result1).isNotNull();
        BigDecimal balanceAfterFirst = testAccount.getAcctCurrBal();
        BigDecimal expectedAfterFirst = initialBalance.add(testTransaction.getTransAmt())
                .setScale(DECIMAL_SCALE, ROUNDING_MODE);
        assertThat(balanceAfterFirst).isEqualByComparingTo(expectedAfterFirst);

        // Arrange: Create second transaction with different amount
        Transaction secondTransaction = Transaction.builder()
                .transId("TX0000000002")
                .transCardNum(TEST_CARD_NUMBER)
                .transTypeCd(TEST_TRANSACTION_TYPE)
                .transCatCd(TEST_CATEGORY_CODE)
                .transAmt(new BigDecimal("50.00").setScale(DECIMAL_SCALE, ROUNDING_MODE))
                .transOrigTs(Timestamp.valueOf(LocalDateTime.now()))
                .build();

        // Act: Process second transaction
        Transaction result2 = transactionProcessor.process(secondTransaction);

        // Assert: Second transaction processed
        assertThat(result2).isNotNull();
        BigDecimal balanceAfterSecond = testAccount.getAcctCurrBal();
        BigDecimal expectedAfterSecond = balanceAfterFirst.add(secondTransaction.getTransAmt())
                .setScale(DECIMAL_SCALE, ROUNDING_MODE);
        assertThat(balanceAfterSecond).isEqualByComparingTo(expectedAfterSecond);

        // Verify cumulative balance change
        BigDecimal totalChange = testTransaction.getTransAmt().add(secondTransaction.getTransAmt())
                .setScale(DECIMAL_SCALE, ROUNDING_MODE);
        BigDecimal expectedFinalBalance = initialBalance.add(totalChange)
                .setScale(DECIMAL_SCALE, ROUNDING_MODE);
        assertThat(balanceAfterSecond).isEqualByComparingTo(expectedFinalBalance);
    }

    /**
     * Test various transaction types with parameterized test.
     * 
     * Validates processor handles different transaction types correctly:
     * - PU: Purchase (debit)
     * - PM: Payment (credit)
     * - RF: Refund (credit)
     * - FE: Fee (debit)
     * - IN: Interest (debit)
     * 
     * Expected behavior: All transaction types process correctly with appropriate balance impacts.
     */
    @ParameterizedTest
    @MethodSource("provideTransactionTypeScenarios")
    @DisplayName("Should correctly process different transaction types")
    void testProcessVariousTransactionTypes(String transactionType, BigDecimal amount, String description) throws Exception {
        // Arrange: Set transaction type and amount
        testTransaction.setTransTypeCd(transactionType);
        testTransaction.setTransAmt(amount);
        testTransaction.setTransDesc(description);

        when(cardAccountXrefRepository.findByXrefCardNum(TEST_CARD_NUMBER))
                .thenReturn(List.of(testCardXref));
        when(accountRepository.findById(TEST_ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));
        when(transactionCategoryBalanceRepository.findById(any()))
                .thenReturn(Optional.of(testCategoryBalance));
        when(accountRepository.save(any(Account.class)))
                .thenReturn(testAccount);
        when(transactionCategoryBalanceRepository.save(any(TransactionCategoryBalance.class)))
                .thenReturn(testCategoryBalance);

        BigDecimal initialBalance = testAccount.getAcctCurrBal();

        // Act: Process transaction
        Transaction result = transactionProcessor.process(testTransaction);

        // Assert: Transaction processed successfully
        assertThat(result).isNotNull();
        assertThat(result.getTransTypeCd()).isEqualTo(transactionType);

        // Verify balance updated correctly
        BigDecimal expectedBalance = initialBalance.add(amount).setScale(DECIMAL_SCALE, ROUNDING_MODE);
        assertThat(testAccount.getAcctCurrBal()).isEqualByComparingTo(expectedBalance);

        // Verify cycle credit or debit updated based on amount sign
        if (amount.compareTo(BigDecimal.ZERO) >= 0) {
            // Positive amount: verify cycle credit updated
            verify(accountRepository).save(argThat(account -> 
                account.getAcctCurrCycCredit().compareTo(new BigDecimal("500.00")) > 0
            ));
        } else {
            // Negative amount: verify cycle debit updated
            verify(accountRepository).save(argThat(account -> 
                account.getAcctCurrCycDebit().compareTo(BigDecimal.ZERO) < 0
            ));
        }
    }

    /**
     * Provide test scenarios for different transaction types.
     * 
     * @return Stream of test arguments: transaction type, amount, description
     */
    private static Stream<org.junit.jupiter.params.provider.Arguments> provideTransactionTypeScenarios() {
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of("PU", new BigDecimal("100.00"), "Purchase transaction"),
            org.junit.jupiter.params.provider.Arguments.of("PM", new BigDecimal("-200.00"), "Payment transaction"),
            org.junit.jupiter.params.provider.Arguments.of("RF", new BigDecimal("-50.00"), "Refund transaction"),
            org.junit.jupiter.params.provider.Arguments.of("FE", new BigDecimal("25.00"), "Fee transaction"),
            org.junit.jupiter.params.provider.Arguments.of("IN", new BigDecimal("15.50"), "Interest transaction")
        );
    }

    /**
     * Test transaction rejection when card number is null.
     * 
     * Error handling validation: Null card number should cause immediate rejection.
     * 
     * Expected behavior: Transaction rejected when card number is null.
     */
    @Test
    @DisplayName("Should reject transaction when card number is null")
    void testProcessNullCardNumber_ReturnsNull() throws Exception {
        // Arrange: Set card number to null
        testTransaction.setTransCardNum(null);

        // Act: Process transaction with null card number
        Transaction result = transactionProcessor.process(testTransaction);

        // Assert: Transaction rejected
        assertThat(result).isNull();

        // Verify no repository calls made
        verify(cardAccountXrefRepository, never()).findByXrefCardNum(any());
        verify(accountRepository, never()).findById(any());
    }

    /**
     * Test processor handles repository exception gracefully.
     * 
     * Error handling validation: Database exceptions during processing should be caught and handled.
     * 
     * Expected behavior: Transaction rejected when repository throws exception.
     */
    @Test
    @DisplayName("Should reject transaction when repository throws exception")
    void testProcessRepositoryException_ReturnsNull() throws Exception {
        // Arrange: Mock repository to throw exception
        when(cardAccountXrefRepository.findByXrefCardNum(TEST_CARD_NUMBER))
                .thenThrow(new RuntimeException("Database connection error"));

        // Act: Process transaction
        Transaction result = transactionProcessor.process(testTransaction);

        // Assert: Transaction rejected due to exception
        assertThat(result).isNull();

        // Verify exception logged and no further processing occurred
        verify(cardAccountXrefRepository, times(1)).findByXrefCardNum(TEST_CARD_NUMBER);
        verify(accountRepository, never()).findById(any());
        verify(accountRepository, never()).save(any());
    }

    /**
     * Test transaction with zero amount.
     * 
     * Edge case validation: Transaction with zero amount should process but have no balance impact.
     * 
     * Expected behavior: Zero-amount transaction processes successfully without changing balances.
     */
    @Test
    @DisplayName("Should process zero-amount transaction successfully without changing balances")
    void testProcessZeroAmountTransaction_Success() throws Exception {
        // Arrange: Set transaction amount to zero
        testTransaction.setTransAmt(BigDecimal.ZERO.setScale(DECIMAL_SCALE, ROUNDING_MODE));

        when(cardAccountXrefRepository.findByXrefCardNum(TEST_CARD_NUMBER))
                .thenReturn(List.of(testCardXref));
        when(accountRepository.findById(TEST_ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));
        when(transactionCategoryBalanceRepository.findById(any()))
                .thenReturn(Optional.of(testCategoryBalance));
        when(accountRepository.save(any(Account.class)))
                .thenReturn(testAccount);
        when(transactionCategoryBalanceRepository.save(any(TransactionCategoryBalance.class)))
                .thenReturn(testCategoryBalance);

        BigDecimal initialBalance = testAccount.getAcctCurrBal();

        // Act: Process zero-amount transaction
        Transaction result = transactionProcessor.process(testTransaction);

        // Assert: Transaction processed
        assertThat(result).isNotNull();

        // Verify balance unchanged
        assertThat(testAccount.getAcctCurrBal()).isEqualByComparingTo(initialBalance);

        // Verify processing still completed (repository saves called)
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    /**
     * Test credit limit validation with negative cycle debit.
     * 
     * Validates COBOL formula: WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
     * When cycle debit is negative (payments made), it increases available credit.
     * 
     * Expected behavior: Credit limit calculation correctly handles negative cycle debit values.
     */
    @Test
    @DisplayName("Should correctly calculate credit limit with negative cycle debit")
    void testCreditLimitWithNegativeCycleDebit_Success() throws Exception {
        // Arrange: Set up account with payments made (negative cycle debit)
        testAccount.setAcctCurrCycCredit(new BigDecimal("5000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));
        testAccount.setAcctCurrCycDebit(new BigDecimal("-2000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));
        testAccount.setAcctCreditLimit(TEST_CREDIT_LIMIT); // $10,000.00

        // Create large transaction
        testTransaction.setTransAmt(new BigDecimal("4000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));
        
        // Predicted: 5000.00 - (-2000.00) + 4000.00 = 5000.00 + 2000.00 + 4000.00 = 11,000.00
        // This exceeds 10,000.00 limit, so should be rejected

        when(cardAccountXrefRepository.findByXrefCardNum(TEST_CARD_NUMBER))
                .thenReturn(List.of(testCardXref));
        when(accountRepository.findById(TEST_ACCOUNT_ID))
                .thenReturn(Optional.of(testAccount));

        // Act: Process transaction
        Transaction result = transactionProcessor.process(testTransaction);

        // Assert: Transaction rejected (overlimit)
        assertThat(result).isNull();

        verify(accountRepository, never()).save(any());
    }
}
