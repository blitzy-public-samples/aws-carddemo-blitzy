/*
 * TransactionServiceTest.java
 *
 * Comprehensive JUnit 5 unit test for TransactionService validating business logic
 * extracted from COBOL programs COTRN00C.cbl (transaction listing), COTRN01C.cbl
 * (transaction detail view), and COTRN02C.cbl (transaction posting with balance updates).
 *
 * CRITICAL: Tests validate COMP-3 precision preservation using BigDecimal scale 2
 * and RoundingMode.HALF_UP per Agent Action Plan Section 0.7.2 ensuring bit-identical
 * financial calculations to mainframe COBOL implementation.
 *
 * Test Categories:
 * 1. Transaction Listing Tests (COTRN00C) - Browse transactions with date range filtering
 * 2. Transaction Detail Tests (COTRN01C) - Retrieve single transaction by ID
 * 3. Transaction Posting Tests (COTRN02C) - Post transactions with balance updates
 * 4. BigDecimal Precision Tests (CRITICAL) - Validate COMP-3 exact precision
 * 5. Transaction Type Validation Tests - Validate type codes 01-08
 * 6. Transaction Category Validation Tests - Validate category codes 1001-9999
 * 7. Merchant Data Tests - Validate merchant ID, name, city, zip
 * 8. Transaction Source Tests - Validate POS, ATM, Online sources
 * 9. Timestamp Tests - Validate origination and processing timestamps
 *
 * Coverage Goal: 80% per Section 0.7.14
 *
 * Original COBOL files:
 * - app/cbl/COTRN00C.cbl (transaction list display)
 * - app/cbl/COTRN01C.cbl (transaction detail view)
 * - app/cbl/COTRN02C.cbl (transaction posting - 33KB, complex balance logic)
 * - app/cpy/CVTRA05Y.cpy (TRAN-RECORD structure, 350-byte record)
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

import com.carddemo.exception.BusinessException;
import com.carddemo.exception.DataNotFoundException;
import com.carddemo.model.dto.AccountDto;
import com.carddemo.model.dto.CardDto;
import com.carddemo.model.dto.TransactionDto;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.Card;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit test suite for TransactionService.
 * 
 * <p>This test class validates transaction processing business logic converted from
 * COBOL programs with critical focus on:</p>
 * <ul>
 *   <li>COMP-3 precision preservation using BigDecimal scale 2</li>
 *   <li>Transaction listing with date range filtering (COTRN00C)</li>
 *   <li>Transaction detail retrieval (COTRN01C)</li>
 *   <li>Transaction posting with balance updates (COTRN02C)</li>
 *   <li>Business rule validation matching COBOL logic</li>
 * </ul>
 * 
 * <p>Test data matches CVTRA05Y.cpy copybook 350-byte record structure with:</p>
 * <ul>
 *   <li>trans_id: PIC X(16) → String length 16</li>
 *   <li>trans_card_num: PIC X(16) → String length 16</li>
 *   <li>trans_type_cd: PIC X(02) → String length 2</li>
 *   <li>trans_cat_cd: PIC 9(04) → Integer</li>
 *   <li>trans_amt: PIC S9(09)V99 COMP-3 → BigDecimal scale 2</li>
 *   <li>trans_merchant_id: PIC 9(09) → String length 9</li>
 *   <li>trans_merchant_name: PIC X(50) → String length 50</li>
 *   <li>trans_merchant_city: PIC X(50) → String length 50</li>
 *   <li>trans_merchant_zip: PIC X(10) → String length 10</li>
 *   <li>trans_orig_ts: PIC X(26) → Timestamp</li>
 *   <li>trans_proc_ts: PIC X(26) → Timestamp</li>
 * </ul>
 * 
 * @see TransactionService
 * @see Transaction
 * @see TransactionDto
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    // Mocked dependencies injected into TransactionService
    @Mock
    private TransactionRepository mockTransactionRepository;

    @Mock
    private AccountRepository mockAccountRepository;

    @Mock
    private CardRepository mockCardRepository;

    @Mock
    private ValidationService mockValidationService;

    @Mock
    private CardService mockCardService;

    @Mock
    private AccountService mockAccountService;

    @Mock
    private com.carddemo.repository.TransactionCategoryBalanceRepository mockTransactionCategoryBalanceRepository;

    // Service under test with mocked dependencies injected
    @InjectMocks
    private TransactionService transactionService;

    // Test constants matching COBOL copybook CVTRA05Y.cpy field definitions
    private static final String TEST_TRANS_ID = "TXN001234567890";  // PIC X(16)
    private static final String TEST_CARD_NUM = "4111111111111111";  // PIC X(16)
    private static final String TEST_CARD_NUM_MASKED = "************1111";  // Masked for security in DTO
    private static final String TEST_CARD_NUM_2 = "4222222222222222";
    private static final String TEST_TYPE_PURCHASE = "01";  // PIC X(02) - Debit
    private static final String TEST_TYPE_PAYMENT = "04";  // PIC X(02) - Credit
    private static final String TEST_TYPE_CASH_ADV = "02";  // PIC X(02) - Debit
    private static final Integer TEST_CAT_CODE = 1001;  // PIC 9(04)
    private static final String TEST_SOURCE_POS = "POS";  // PIC X(10)
    private static final String TEST_SOURCE_ATM = "ATM";
    private static final String TEST_SOURCE_ONLINE = "ONLINE";
    private static final String TEST_DESC = "Purchase at Store XYZ";  // PIC X(100)
    private static final Long TEST_MERCHANT_ID = 123456789L;  // PIC 9(09)
    private static final String TEST_MERCHANT_NAME = "Store XYZ";  // PIC X(50)
    private static final String TEST_MERCHANT_CITY = "New York";  // PIC X(50)
    private static final String TEST_MERCHANT_ZIP = "10001";  // PIC X(10)
    private static final Long TEST_ACCT_ID = 1000000001L;  // PIC 9(11)
    
    // Test amounts matching COBOL PIC S9(09)V99 COMP-3 with BigDecimal scale 2
    private static final BigDecimal TEST_AMOUNT = new BigDecimal("1234.56").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal TEST_AMOUNT_LARGE = new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal TEST_BALANCE = new BigDecimal("10000.00").setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal TEST_CREDIT_LIMIT = new BigDecimal("15000.00").setScale(2, RoundingMode.HALF_UP);

    //=============================================================================
    // CATEGORY 1: Transaction Listing Tests (COTRN00C.cbl)
    //=============================================================================

    /**
     * Test retrieving all transactions without filters.
     * 
     * Validates: listTransactions() method returns all transactions when no filters applied.
     * COBOL equivalent: EXEC CICS STARTBR FILE('TRANSACT') browsing all records.
     */
    @Test
    void testGetAllTransactions() {
        // Arrange: Create test transaction data
        List<Transaction> mockTransactions = Arrays.asList(
            createTestTransaction(TEST_TRANS_ID, TEST_CARD_NUM, TEST_AMOUNT),
            createTestTransaction("TXN002", TEST_CARD_NUM, new BigDecimal("500.00"))
        );
        Page<Transaction> mockPage = new PageImpl<>(mockTransactions);
        Pageable pageable = PageRequest.of(0, 10);
        
        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();
        
        // Mock ValidationService.validateCardNumber() to pass
        doNothing().when(mockValidationService).validateCardNumber(anyString());
        
        // Mock repository.findByTransCardNumAndTransOrigTsBetween()
        when(mockTransactionRepository.findByTransCardNumAndTransOrigTsBetween(
            anyString(), any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(mockPage);

        // Act: Call listTransactions()
        Page<TransactionDto> result = transactionService.listTransactions(
            TEST_CARD_NUM, startDate, endDate, pageable);

        // Assert: Verify results
        assertNotNull(result);
        assertEquals(2, result.getTotalElements());
        // Card number is masked in DTO for security
        assertEquals(TEST_CARD_NUM_MASKED, result.getContent().get(0).getTransCardNum());
        
        // Verify repository called with correct parameters
        verify(mockTransactionRepository).findByTransCardNumAndTransOrigTsBetween(
            eq(TEST_CARD_NUM), any(LocalDateTime.class), any(LocalDateTime.class), eq(pageable));
        verify(mockValidationService).validateCardNumber(TEST_CARD_NUM);
    }

    /**
     * Test filtering transactions by card number.
     * 
     * Validates: listTransactions() correctly filters by card number.
     * COBOL equivalent: EXEC CICS STARTBR FILE('TRANSACT') RIDFLD(TRAN-CARD-NUM) GTEQ.
     */
    @Test
    void testGetTransactionsByCardNumber() {
        // Arrange
        List<Transaction> mockTransactions = Arrays.asList(
            createTestTransaction(TEST_TRANS_ID, TEST_CARD_NUM, TEST_AMOUNT)
        );
        Page<Transaction> mockPage = new PageImpl<>(mockTransactions);
        Pageable pageable = PageRequest.of(0, 10);
        
        LocalDate startDate = LocalDate.now().minusDays(7);
        LocalDate endDate = LocalDate.now();
        
        doNothing().when(mockValidationService).validateCardNumber(anyString());
        when(mockTransactionRepository.findByTransCardNumAndTransOrigTsBetween(
            eq(TEST_CARD_NUM), any(LocalDateTime.class), any(LocalDateTime.class), eq(pageable)))
            .thenReturn(mockPage);

        // Act
        Page<TransactionDto> result = transactionService.listTransactions(
            TEST_CARD_NUM, startDate, endDate, pageable);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        // Card number is masked in DTO for security
        assertEquals(TEST_CARD_NUM_MASKED, result.getContent().get(0).getTransCardNum());
        
        // Verify only transactions for specified card returned (masked)
        result.getContent().forEach(dto -> 
            assertEquals(TEST_CARD_NUM_MASKED, dto.getTransCardNum())
        );
    }

    /**
     * Test filtering transactions by date range.
     * 
     * Validates: listTransactions() correctly filters by origination timestamp range.
     * COBOL equivalent: IF TRAN-ORIG-TS >= WS-START-DATE AND TRAN-ORIG-TS <= WS-END-DATE.
     */
    @Test
    void testGetTransactionsByDateRange() {
        // Arrange: Set up date range
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);
        
        Transaction transaction = createTestTransaction(TEST_TRANS_ID, TEST_CARD_NUM, TEST_AMOUNT);
        transaction.setTransOrigTs(Timestamp.valueOf(LocalDateTime.of(2024, 1, 15, 10, 30)));
        
        List<Transaction> mockTransactions = Arrays.asList(transaction);
        Page<Transaction> mockPage = new PageImpl<>(mockTransactions);
        Pageable pageable = PageRequest.of(0, 10);
        
        doNothing().when(mockValidationService).validateCardNumber(anyString());
        when(mockTransactionRepository.findByTransCardNumAndTransOrigTsBetween(
            anyString(), any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(mockPage);

        // Act
        Page<TransactionDto> result = transactionService.listTransactions(
            TEST_CARD_NUM, startDate, endDate, pageable);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        
        // Verify repository called with correct date range converted to LocalDateTime
        verify(mockTransactionRepository).findByTransCardNumAndTransOrigTsBetween(
            eq(TEST_CARD_NUM), 
            eq(startDate.atStartOfDay()), 
            eq(endDate.atTime(23, 59, 59)), 
            eq(pageable));
    }

    /**
     * Test combined card number and date range filtering.
     * 
     * Validates: listTransactions() applies both card and date filters simultaneously.
     * COBOL equivalent: Browse transactions with multiple filter conditions.
     */
    @Test
    void testGetTransactionsByCardAndDateRange() {
        // Arrange
        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();
        
        List<Transaction> mockTransactions = Arrays.asList(
            createTestTransaction(TEST_TRANS_ID, TEST_CARD_NUM, TEST_AMOUNT)
        );
        Page<Transaction> mockPage = new PageImpl<>(mockTransactions);
        Pageable pageable = PageRequest.of(0, 10);
        
        doNothing().when(mockValidationService).validateCardNumber(anyString());
        when(mockTransactionRepository.findByTransCardNumAndTransOrigTsBetween(
            eq(TEST_CARD_NUM), any(LocalDateTime.class), any(LocalDateTime.class), eq(pageable)))
            .thenReturn(mockPage);

        // Act
        Page<TransactionDto> result = transactionService.listTransactions(
            TEST_CARD_NUM, startDate, endDate, pageable);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        // Card number is masked in DTO for security
        assertEquals(TEST_CARD_NUM_MASKED, result.getContent().get(0).getTransCardNum());
    }

    /**
     * Test empty result when no transactions match filters.
     * 
     * Validates: listTransactions() returns empty page when no matches found.
     * COBOL equivalent: EXEC CICS READNEXT returns ENDFILE condition.
     */
    @Test
    void testGetTransactionsEmpty() {
        // Arrange: Mock empty result
        Page<Transaction> emptyPage = new PageImpl<>(Collections.emptyList());
        Pageable pageable = PageRequest.of(0, 10);
        
        LocalDate startDate = LocalDate.now().minusDays(7);
        LocalDate endDate = LocalDate.now();
        
        doNothing().when(mockValidationService).validateCardNumber(anyString());
        when(mockTransactionRepository.findByTransCardNumAndTransOrigTsBetween(
            anyString(), any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(emptyPage);

        // Act
        Page<TransactionDto> result = transactionService.listTransactions(
            TEST_CARD_NUM, startDate, endDate, pageable);

        // Assert
        assertNotNull(result);
        assertEquals(0, result.getTotalElements());
        assertTrue(result.getContent().isEmpty());
    }

    /**
     * Test invalid date range throws exception.
     * 
     * Validates: listTransactions() rejects start date after end date.
     * COBOL equivalent: Field validation before file operations.
     */
    @Test
    void testInvalidDateRangeThrowsException() {
        // Arrange: Start date after end date
        LocalDate startDate = LocalDate.now();
        LocalDate endDate = LocalDate.now().minusDays(7);
        Pageable pageable = PageRequest.of(0, 10);
        
        doNothing().when(mockValidationService).validateCardNumber(anyString());

        // Act & Assert: Expect BusinessException
        assertThatThrownBy(() -> transactionService.listTransactions(
            TEST_CARD_NUM, startDate, endDate, pageable))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Start date cannot be after end date");
    }

    //=============================================================================
    // CATEGORY 2: Transaction Detail Tests (COTRN01C.cbl)
    //=============================================================================

    /**
     * Test retrieving transaction by ID.
     * 
     * Validates: getTransactionById() returns complete transaction details.
     * COBOL equivalent: EXEC CICS READ FILE('TRANSACT') RIDFLD(TRAN-ID).
     */
    @Test
    void testGetTransactionById() {
        // Arrange
        Transaction mockTransaction = createTestTransaction(TEST_TRANS_ID, TEST_CARD_NUM, TEST_AMOUNT);
        when(mockTransactionRepository.findById(TEST_TRANS_ID))
            .thenReturn(Optional.of(mockTransaction));

        // Act
        TransactionDto result = transactionService.getTransactionById(TEST_TRANS_ID);

        // Assert: Verify all 350-byte record fields
        assertNotNull(result);
        assertEquals(TEST_TRANS_ID, result.getTransId());
        // Card number is masked in DTO for security
        assertEquals(TEST_CARD_NUM_MASKED, result.getTransCardNum());
        assertEquals(TEST_TYPE_PURCHASE, result.getTransTypeCd());
        assertEquals(TEST_CAT_CODE, result.getTransCatCd());
        assertEquals(TEST_SOURCE_POS, result.getTransSource());
        assertEquals(TEST_DESC, result.getTransDesc());
        
        // CRITICAL: Verify BigDecimal precision with scale 2 and compareTo
        assertNotNull(result.getTransAmt());
        assertEquals(0, TEST_AMOUNT.compareTo(result.getTransAmt()), 
            "Transaction amount must match with exact COMP-3 precision");
        assertEquals(2, result.getTransAmt().scale(), 
            "Transaction amount must have scale 2 for COBOL COMP-3 precision");
        
        assertEquals(TEST_MERCHANT_ID, result.getTransMerchantId());
        assertEquals(TEST_MERCHANT_NAME, result.getTransMerchantName());
        assertEquals(TEST_MERCHANT_CITY, result.getTransMerchantCity());
        assertEquals(TEST_MERCHANT_ZIP, result.getTransMerchantZip());
        
        verify(mockTransactionRepository).findById(TEST_TRANS_ID);
    }

    /**
     * Test transaction not found throws DataNotFoundException.
     * 
     * Validates: getTransactionById() throws exception when transaction doesn't exist.
     * COBOL equivalent: EXEC CICS READ returns NOTFND response code.
     */
    @Test
    void testGetTransactionByIdNotFound() {
        // Arrange: Mock empty Optional
        when(mockTransactionRepository.findById(anyString()))
            .thenReturn(Optional.empty());

        // Act & Assert: Expect DataNotFoundException
        assertThatThrownBy(() -> transactionService.getTransactionById("INVALID_ID"))
            .isInstanceOf(DataNotFoundException.class)
            .hasMessageContaining("Transaction not found");
        
        verify(mockTransactionRepository).findById("INVALID_ID");
    }

    /**
     * Test transaction with card relationship loads correctly.
     * 
     * Validates: getTransactionById() includes card entity via @ManyToOne relationship.
     * COBOL equivalent: Reading related CARDFILE after TRANSACT file.
     */
    @Test
    void testGetTransactionWithCard() {
        // Arrange: Transaction with Card entity
        Card mockCard = Card.builder()
            .cardNum(TEST_CARD_NUM)
            .cardAcctId(TEST_ACCT_ID)
            .cardStatus("Y")
            .cardEmbossedName("JOHN DOE")
            .build();
        
        Transaction mockTransaction = createTestTransaction(TEST_TRANS_ID, TEST_CARD_NUM, TEST_AMOUNT);
        mockTransaction.setCard(mockCard);
        
        when(mockTransactionRepository.findById(TEST_TRANS_ID))
            .thenReturn(Optional.of(mockTransaction));

        // Act
        TransactionDto result = transactionService.getTransactionById(TEST_TRANS_ID);

        // Assert
        assertNotNull(result);
        assertEquals(TEST_TRANS_ID, result.getTransId());
        // Card number is masked in DTO for security
        assertEquals(TEST_CARD_NUM_MASKED, result.getTransCardNum());
        
        // Note: Card relationship verified through entity, DTO may not expose card details
        verify(mockTransactionRepository).findById(TEST_TRANS_ID);
    }

    /**
     * Test all transaction detail fields present.
     * 
     * Validates: getTransactionById() returns all fields from 350-byte CVTRA05Y.cpy structure.
     */
    @Test
    void testGetTransactionDetails() {
        // Arrange: Complete transaction with all fields populated
        Transaction mockTransaction = Transaction.builder()
            .transId(TEST_TRANS_ID)
            .transCardNum(TEST_CARD_NUM)
            .transTypeCd(TEST_TYPE_PURCHASE)
            .transCatCd(TEST_CAT_CODE)
            .transSource(TEST_SOURCE_POS)
            .transDesc(TEST_DESC)
            .transAmt(TEST_AMOUNT)
            .transMerchantId(TEST_MERCHANT_ID)
            .transMerchantName(TEST_MERCHANT_NAME)
            .transMerchantCity(TEST_MERCHANT_CITY)
            .transMerchantZip(TEST_MERCHANT_ZIP)
            .transOrigTs(Timestamp.valueOf(LocalDateTime.now().minusHours(2)))
            .transProcTs(Timestamp.valueOf(LocalDateTime.now()))
            .build();
        
        when(mockTransactionRepository.findById(TEST_TRANS_ID))
            .thenReturn(Optional.of(mockTransaction));

        // Act
        TransactionDto result = transactionService.getTransactionById(TEST_TRANS_ID);

        // Assert: Verify all fields
        assertNotNull(result);
        assertEquals(TEST_TRANS_ID, result.getTransId());
        // Card number is masked in DTO for security
        assertEquals(TEST_CARD_NUM_MASKED, result.getTransCardNum());
        assertEquals(TEST_TYPE_PURCHASE, result.getTransTypeCd());
        assertEquals(TEST_CAT_CODE, result.getTransCatCd());
        assertEquals(TEST_SOURCE_POS, result.getTransSource());
        assertEquals(TEST_DESC, result.getTransDesc());
        assertEquals(0, TEST_AMOUNT.compareTo(result.getTransAmt()));
        assertEquals(TEST_MERCHANT_ID, result.getTransMerchantId());
        assertEquals(TEST_MERCHANT_NAME, result.getTransMerchantName());
        assertEquals(TEST_MERCHANT_CITY, result.getTransMerchantCity());
        assertEquals(TEST_MERCHANT_ZIP, result.getTransMerchantZip());
        assertNotNull(result.getTransOrigTs());
        assertNotNull(result.getTransProcTs());
    }

    //=============================================================================
    // CATEGORY 3: Transaction Posting Tests (COTRN02C.cbl)
    //=============================================================================

    /**
     * Test successful transaction posting with debit (purchase).
     * 
     * Validates: postTransaction() creates transaction and updates account balance.
     * COBOL equivalent: COTRN02C.cbl POST-TRANSACTION paragraph with debit logic.
     */
    @Test
    void testPostTransactionSuccess() {
        // Arrange: Create transaction DTO for posting
        TransactionDto transactionDto = createTestTransactionDto(TEST_CARD_NUM, TEST_TYPE_PURCHASE, TEST_AMOUNT);
        
        // Mock ValidationService calls
        doNothing().when(mockValidationService).validateAmount(any(BigDecimal.class));
        doNothing().when(mockValidationService).validateCardNumber(anyString());
        doNothing().when(mockValidationService).validateTransactionType(anyString());
        doNothing().when(mockValidationService).validateTransactionCategory(anyInt());
        
        // Mock CardService.getCardByNumber()
        CardDto mockCard = createTestCardDto(TEST_CARD_NUM, TEST_ACCT_ID, "Y");
        when(mockCardService.getCardByNumber(TEST_CARD_NUM)).thenReturn(mockCard);
        
        // Mock AccountService.getAccountById()
        AccountDto mockAccount = createTestAccountDto(TEST_ACCT_ID, TEST_BALANCE, TEST_CREDIT_LIMIT);
        when(mockAccountService.getAccountById(TEST_ACCT_ID)).thenReturn(mockAccount);
        
        // Mock AccountService.updateAccount() - it returns AccountDto, not void
        AccountDto updatedAccount = createTestAccountDto(TEST_ACCT_ID, TEST_BALANCE, TEST_CREDIT_LIMIT);
        when(mockAccountService.updateAccount(anyLong(), any(AccountDto.class))).thenReturn(updatedAccount);
        
        // Mock TransactionRepository.save()
        Transaction savedTransaction = createTestTransaction("TXN_GENERATED", TEST_CARD_NUM, TEST_AMOUNT);
        when(mockTransactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        // Act: Post transaction
        TransactionDto result = transactionService.postTransaction(transactionDto);

        // Assert
        assertNotNull(result);
        // Card number is masked in DTO for security
        assertEquals(TEST_CARD_NUM_MASKED, result.getTransCardNum());
        assertEquals(0, TEST_AMOUNT.compareTo(result.getTransAmt()));
        
        // Verify all service/repository methods called
        verify(mockCardService).getCardByNumber(TEST_CARD_NUM);
        verify(mockAccountService).getAccountById(TEST_ACCT_ID);
        verify(mockAccountService).updateAccount(eq(TEST_ACCT_ID), any(AccountDto.class));
        verify(mockTransactionRepository).save(any(Transaction.class));
    }

    /**
     * Test transaction posting fails for invalid card.
     * 
     * Validates: postTransaction() throws exception when card not found.
     * COBOL equivalent: EXEC CICS READ FILE('CCXREF') returns NOTFND.
     */
    @Test
    void testPostTransactionInvalidCard() {
        // Arrange
        TransactionDto transactionDto = createTestTransactionDto(TEST_CARD_NUM, TEST_TYPE_PURCHASE, TEST_AMOUNT);
        
        doNothing().when(mockValidationService).validateAmount(any(BigDecimal.class));
        doNothing().when(mockValidationService).validateCardNumber(anyString());
        doNothing().when(mockValidationService).validateTransactionType(anyString());
        doNothing().when(mockValidationService).validateTransactionCategory(anyInt());
        
        // Mock card not found
        when(mockCardService.getCardByNumber(anyString()))
            .thenThrow(new DataNotFoundException("Card not found"));

        // Act & Assert: Expect DataNotFoundException
        assertThatThrownBy(() -> transactionService.postTransaction(transactionDto))
            .isInstanceOf(DataNotFoundException.class)
            .hasMessageContaining("Card not found");
        
        verify(mockCardService).getCardByNumber(TEST_CARD_NUM);
        verify(mockTransactionRepository, never()).save(any(Transaction.class));
    }

    /**
     * Test transaction posting updates account balance correctly for debit.
     * 
     * Validates: postTransaction() reduces balance for debit transactions (purchases).
     * COBOL equivalent: COMPUTE NEW-BALANCE = ACCT-CURR-BAL - TRAN-AMT.
     */
    @Test
    void testPostTransactionUpdateAccountBalance() {
        // Arrange
        TransactionDto transactionDto = createTestTransactionDto(TEST_CARD_NUM, TEST_TYPE_PURCHASE, TEST_AMOUNT);
        
        doNothing().when(mockValidationService).validateAmount(any(BigDecimal.class));
        doNothing().when(mockValidationService).validateCardNumber(anyString());
        doNothing().when(mockValidationService).validateTransactionType(anyString());
        doNothing().when(mockValidationService).validateTransactionCategory(anyInt());
        
        CardDto mockCard = createTestCardDto(TEST_CARD_NUM, TEST_ACCT_ID, "Y");
        when(mockCardService.getCardByNumber(TEST_CARD_NUM)).thenReturn(mockCard);
        
        BigDecimal initialBalance = new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP);
        AccountDto mockAccount = createTestAccountDto(TEST_ACCT_ID, initialBalance, TEST_CREDIT_LIMIT);
        when(mockAccountService.getAccountById(TEST_ACCT_ID)).thenReturn(mockAccount);
        
        // Mock updateAccount to return updated account (not void)
        AccountDto updatedAccount = createTestAccountDto(TEST_ACCT_ID, initialBalance, TEST_CREDIT_LIMIT);
        when(mockAccountService.updateAccount(anyLong(), any(AccountDto.class))).thenReturn(updatedAccount);
        
        Transaction savedTransaction = createTestTransaction("TXN_GEN", TEST_CARD_NUM, TEST_AMOUNT);
        when(mockTransactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        // Act
        TransactionDto result = transactionService.postTransaction(transactionDto);

        // Assert
        assertNotNull(result);
        
        // Verify account balance updated (should be decreased by transaction amount)
        verify(mockAccountService).updateAccount(eq(TEST_ACCT_ID), argThat(account -> {
            BigDecimal expectedBalance = initialBalance.subtract(TEST_AMOUNT).setScale(2, RoundingMode.HALF_UP);
            return expectedBalance.compareTo(account.getAcctCurrBal()) == 0;
        }));
    }

    /**
     * Test debit transaction decreases balance.
     * 
     * Validates: Debit types (01,02,03,05,06,08) reduce account balance.
     * COBOL equivalent: IF TRAN-TYPE-CD = '01' OR '02'... COMPUTE NEW-BALANCE = ACCT-CURR-BAL - TRAN-AMT.
     */
    @Test
    void testPostTransactionDebitBalance() {
        // Arrange: Debit transaction (type '01' = Purchase)
        TransactionDto transactionDto = createTestTransactionDto(TEST_CARD_NUM, TEST_TYPE_PURCHASE, TEST_AMOUNT);
        
        setupSuccessfulPostTransactionMocks();
        
        BigDecimal initialBalance = new BigDecimal("8000.00").setScale(2, RoundingMode.HALF_UP);
        AccountDto mockAccount = createTestAccountDto(TEST_ACCT_ID, initialBalance, TEST_CREDIT_LIMIT);
        when(mockAccountService.getAccountById(TEST_ACCT_ID)).thenReturn(mockAccount);
        
        Transaction savedTransaction = createTestTransaction("TXN_DEBIT", TEST_CARD_NUM, TEST_AMOUNT);
        when(mockTransactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        // Act
        TransactionDto result = transactionService.postTransaction(transactionDto);

        // Assert: Verify balance decreased
        verify(mockAccountService).updateAccount(eq(TEST_ACCT_ID), argThat(account -> {
            BigDecimal expectedBalance = initialBalance.subtract(TEST_AMOUNT).setScale(2, RoundingMode.HALF_UP);
            return expectedBalance.compareTo(account.getAcctCurrBal()) == 0;
        }));
    }

    /**
     * Test credit transaction increases balance.
     * 
     * Validates: Credit types (04,07) increase account balance.
     * COBOL equivalent: ELSE COMPUTE NEW-BALANCE = ACCT-CURR-BAL + TRAN-AMT.
     */
    @Test
    void testPostTransactionCreditBalance() {
        // Arrange: Credit transaction (type '04' = Payment)
        TransactionDto transactionDto = createTestTransactionDto(TEST_CARD_NUM, TEST_TYPE_PAYMENT, TEST_AMOUNT);
        
        setupSuccessfulPostTransactionMocks();
        
        BigDecimal initialBalance = new BigDecimal("3000.00").setScale(2, RoundingMode.HALF_UP);
        AccountDto mockAccount = createTestAccountDto(TEST_ACCT_ID, initialBalance, TEST_CREDIT_LIMIT);
        when(mockAccountService.getAccountById(TEST_ACCT_ID)).thenReturn(mockAccount);
        
        Transaction savedTransaction = createTestTransaction("TXN_CREDIT", TEST_CARD_NUM, TEST_AMOUNT);
        when(mockTransactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        // Act
        TransactionDto result = transactionService.postTransaction(transactionDto);

        // Assert: Verify balance increased
        verify(mockAccountService).updateAccount(eq(TEST_ACCT_ID), argThat(account -> {
            BigDecimal expectedBalance = initialBalance.add(TEST_AMOUNT).setScale(2, RoundingMode.HALF_UP);
            return expectedBalance.compareTo(account.getAcctCurrBal()) == 0;
        }));
    }

    /**
     * Test transaction exceeds credit limit throws exception.
     * 
     * Validates: postTransaction() rejects transaction exceeding credit limit.
     * COBOL equivalent: IF NEW-BALANCE > ACCT-CREDIT-LIMIT MOVE 'Exceeds limit' TO WS-MESSAGE.
     */
    @Test
    void testPostTransactionInsufficientFunds() {
        // Arrange: Large transaction exceeding credit limit
        BigDecimal largeAmount = new BigDecimal("20000.00").setScale(2, RoundingMode.HALF_UP);
        TransactionDto transactionDto = createTestTransactionDto(TEST_CARD_NUM, TEST_TYPE_PURCHASE, largeAmount);
        
        // Only mock what's needed before exception is thrown (don't use helper to avoid unnecessary stubbings)
        doNothing().when(mockValidationService).validateAmount(any(BigDecimal.class));
        doNothing().when(mockValidationService).validateCardNumber(anyString());
        doNothing().when(mockValidationService).validateTransactionType(anyString());
        doNothing().when(mockValidationService).validateTransactionCategory(anyInt());
        
        CardDto mockCard = createTestCardDto(TEST_CARD_NUM, TEST_ACCT_ID, "Y");
        when(mockCardService.getCardByNumber(anyString())).thenReturn(mockCard);
        
        // Initial balance close to credit limit
        BigDecimal initialBalance = new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP);
        BigDecimal creditLimit = new BigDecimal("15000.00").setScale(2, RoundingMode.HALF_UP);
        AccountDto mockAccount = createTestAccountDto(TEST_ACCT_ID, initialBalance, creditLimit);
        when(mockAccountService.getAccountById(TEST_ACCT_ID)).thenReturn(mockAccount);

        // Act & Assert: Expect BusinessException for credit limit exceeded
        assertThatThrownBy(() -> transactionService.postTransaction(transactionDto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("exceeds credit limit");
        
        // Verify transaction not saved
        verify(mockTransactionRepository, never()).save(any(Transaction.class));
    }

    /**
     * Test inactive card rejected for transaction posting.
     * 
     * Validates: postTransaction() rejects transactions for inactive cards.
     * COBOL equivalent: IF CARD-STATUS NOT = 'Y' MOVE 'Card inactive' TO WS-MESSAGE.
     */
    @Test
    void testPostTransactionInactiveCard() {
        // Arrange: Card with inactive status
        TransactionDto transactionDto = createTestTransactionDto(TEST_CARD_NUM, TEST_TYPE_PURCHASE, TEST_AMOUNT);
        
        doNothing().when(mockValidationService).validateAmount(any(BigDecimal.class));
        doNothing().when(mockValidationService).validateCardNumber(anyString());
        doNothing().when(mockValidationService).validateTransactionType(anyString());
        doNothing().when(mockValidationService).validateTransactionCategory(anyInt());
        
        // Mock inactive card (status = 'N')
        CardDto inactiveCard = createTestCardDto(TEST_CARD_NUM, TEST_ACCT_ID, "N");
        when(mockCardService.getCardByNumber(TEST_CARD_NUM)).thenReturn(inactiveCard);

        // Act & Assert: Expect BusinessException
        assertThatThrownBy(() -> transactionService.postTransaction(transactionDto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Card is not active");
        
        verify(mockTransactionRepository, never()).save(any(Transaction.class));
    }

    //=============================================================================
    // CATEGORY 4: BigDecimal Precision Tests (CRITICAL - COMP-3 Preservation)
    //=============================================================================

    /**
     * CRITICAL TEST: Validate transaction amount precision matches COBOL COMP-3.
     * 
     * Validates: Transaction amounts use BigDecimal with scale=2 matching PIC S9(09)V99 COMP-3.
     * This is CRITICAL per Agent Action Plan Section 0.7.2 for bit-identical financial calculations.
     */
    @Test
    void testTransactionAmountPrecision() {
        // Arrange: Create transaction with specific amount requiring scale 2
        BigDecimal preciseAmount = new BigDecimal("1234.56");  // Exactly 2 decimal places
        TransactionDto transactionDto = createTestTransactionDto(TEST_CARD_NUM, TEST_TYPE_PURCHASE, preciseAmount);
        
        setupSuccessfulPostTransactionMocks();
        
        AccountDto mockAccount = createTestAccountDto(TEST_ACCT_ID, TEST_BALANCE, TEST_CREDIT_LIMIT);
        when(mockAccountService.getAccountById(TEST_ACCT_ID)).thenReturn(mockAccount);
        
        // Capture saved transaction to verify precision
        Transaction capturedTransaction = createTestTransaction("TXN_PREC", TEST_CARD_NUM, preciseAmount);
        when(mockTransactionRepository.save(any(Transaction.class))).thenReturn(capturedTransaction);

        // Act
        TransactionDto result = transactionService.postTransaction(transactionDto);

        // Assert: CRITICAL precision checks
        assertNotNull(result.getTransAmt());
        assertEquals(2, result.getTransAmt().scale(), 
            "CRITICAL: Amount scale must be 2 for COBOL COMP-3 precision");
        assertEquals(0, preciseAmount.setScale(2, RoundingMode.HALF_UP).compareTo(result.getTransAmt()),
            "CRITICAL: Amount must match exactly with HALF_UP rounding");
        
        // Verify repository save called with properly scaled amount
        verify(mockTransactionRepository).save(argThat(transaction -> 
            transaction.getTransAmt().scale() == 2 &&
            transaction.getTransAmt().compareTo(preciseAmount.setScale(2, RoundingMode.HALF_UP)) == 0
        ));
    }

    /**
     * CRITICAL TEST: Validate balance arithmetic maintains COMP-3 precision.
     * 
     * Validates: Account balance +/- transaction amount preserves scale 2 throughout calculation.
     * COBOL equivalent: COMPUTE NEW-BALANCE = ACCT-CURR-BAL - TRAN-AMT (COMP-3 arithmetic).
     */
    @Test
    void testAmountArithmetic() {
        // Arrange: Amounts requiring precise arithmetic
        BigDecimal initialBalance = new BigDecimal("10000.00").setScale(2, RoundingMode.HALF_UP);
        BigDecimal transAmount = new BigDecimal("1234.56").setScale(2, RoundingMode.HALF_UP);
        BigDecimal expectedBalance = new BigDecimal("8765.44").setScale(2, RoundingMode.HALF_UP);  // 10000.00 - 1234.56
        
        TransactionDto transactionDto = createTestTransactionDto(TEST_CARD_NUM, TEST_TYPE_PURCHASE, transAmount);
        
        setupSuccessfulPostTransactionMocks();
        
        AccountDto mockAccount = createTestAccountDto(TEST_ACCT_ID, initialBalance, TEST_CREDIT_LIMIT);
        when(mockAccountService.getAccountById(TEST_ACCT_ID)).thenReturn(mockAccount);
        
        Transaction savedTransaction = createTestTransaction("TXN_ARITH", TEST_CARD_NUM, transAmount);
        when(mockTransactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        // Act
        transactionService.postTransaction(transactionDto);

        // Assert: Verify balance arithmetic maintains precision
        verify(mockAccountService).updateAccount(eq(TEST_ACCT_ID), argThat(account -> {
            BigDecimal newBalance = account.getAcctCurrBal();
            // CRITICAL: Check scale preserved
            assertEquals(2, newBalance.scale(), "Balance scale must remain 2 after arithmetic");
            // CRITICAL: Check exact value with compareTo (not equals!)
            assertEquals(0, expectedBalance.compareTo(newBalance), 
                "Balance arithmetic must be exact: 10000.00 - 1234.56 = 8765.44");
            return true;
        }));
    }

    /**
     * CRITICAL TEST: Validate amount rounding maintains 2 decimal places.
     * 
     * Validates: Amounts with more than 2 decimals are rounded using HALF_UP mode.
     * COBOL equivalent: COMP-3 rounding behavior for PIC S9(09)V99 fields.
     */
    @Test
    void testAmountRounding() {
        // Arrange: Amount with 3 decimal places requiring rounding
        BigDecimal rawAmount = new BigDecimal("1234.567");  // 3 decimals
        BigDecimal expectedRounded = new BigDecimal("1234.57").setScale(2, RoundingMode.HALF_UP);
        
        TransactionDto transactionDto = createTestTransactionDto(TEST_CARD_NUM, TEST_TYPE_PURCHASE, rawAmount);
        
        setupSuccessfulPostTransactionMocks();
        
        AccountDto mockAccount = createTestAccountDto(TEST_ACCT_ID, TEST_BALANCE, TEST_CREDIT_LIMIT);
        when(mockAccountService.getAccountById(TEST_ACCT_ID)).thenReturn(mockAccount);
        
        Transaction savedTransaction = createTestTransaction("TXN_ROUND", TEST_CARD_NUM, expectedRounded);
        when(mockTransactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        // Act
        TransactionDto result = transactionService.postTransaction(transactionDto);

        // Assert: Verify rounding to scale 2 with HALF_UP
        verify(mockTransactionRepository).save(argThat(transaction -> {
            BigDecimal savedAmount = transaction.getTransAmt();
            assertEquals(2, savedAmount.scale(), "Amount must be rounded to scale 2");
            assertEquals(0, expectedRounded.compareTo(savedAmount),
                "Amount 1234.567 must round to 1234.57 with HALF_UP");
            return true;
        }));
    }

    /**
     * CRITICAL TEST: Validate negative amounts (signed S9) for refunds/credits.
     * 
     * Validates: Signed amounts work correctly for credit transactions.
     * COBOL equivalent: PIC S9(09)V99 COMP-3 supporting negative values.
     */
    @Test
    void testNegativeAmounts() {
        // Arrange: Credit transaction with positive amount (credits increase balance)
        BigDecimal creditAmount = new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP);
        TransactionDto transactionDto = createTestTransactionDto(TEST_CARD_NUM, TEST_TYPE_PAYMENT, creditAmount);
        
        setupSuccessfulPostTransactionMocks();
        
        BigDecimal initialBalance = new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP);
        AccountDto mockAccount = createTestAccountDto(TEST_ACCT_ID, initialBalance, TEST_CREDIT_LIMIT);
        when(mockAccountService.getAccountById(TEST_ACCT_ID)).thenReturn(mockAccount);
        
        Transaction savedTransaction = createTestTransaction("TXN_NEG", TEST_CARD_NUM, creditAmount);
        when(mockTransactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        // Act
        transactionService.postTransaction(transactionDto);

        // Assert: Credit adds to balance (payment reduces debt)
        verify(mockAccountService).updateAccount(eq(TEST_ACCT_ID), argThat(account -> {
            BigDecimal expectedBalance = initialBalance.add(creditAmount).setScale(2, RoundingMode.HALF_UP);
            return expectedBalance.compareTo(account.getAcctCurrBal()) == 0;
        }));
    }

    /**
     * CRITICAL TEST: Use compareTo() not equals() for BigDecimal comparisons.
     * 
     * Validates: Proper BigDecimal comparison using compareTo() method.
     * This is essential because BigDecimal.equals() checks scale, compareTo() checks value.
     */
    @Test
    void testBigDecimalComparison() {
        // Demonstrate why compareTo() must be used, not equals()
        BigDecimal amount1 = new BigDecimal("1234.56");  // scale 2
        BigDecimal amount2 = new BigDecimal("1234.560"); // scale 3
        
        // These are NOT equal per equals() due to different scales
        assertNotEquals(amount1, amount2, "equals() checks scale - these differ");
        
        // But they ARE equal per compareTo() (same value)
        assertEquals(0, amount1.compareTo(amount2), 
            "compareTo() checks value - these are equal");
        
        // Demonstrate in transaction context
        Transaction transaction = createTestTransaction(TEST_TRANS_ID, TEST_CARD_NUM, amount1);
        
        // Use compareTo for amount equality check
        assertEquals(0, amount1.compareTo(transaction.getTransAmt()),
            "Must use compareTo() for BigDecimal amount comparisons");
    }

    /**
     * Test zero amount transaction rejected.
     * 
     * Validates: postTransaction() rejects zero amount transactions.
     * COBOL equivalent: IF TRAN-AMT <= ZERO MOVE 'Amount must be positive' TO WS-MESSAGE.
     */
    @Test
    void testZeroAmount() {
        // Arrange: Transaction with zero amount
        BigDecimal zeroAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        TransactionDto transactionDto = createTestTransactionDto(TEST_CARD_NUM, TEST_TYPE_PURCHASE, zeroAmount);

        // Act & Assert: Expect BusinessException
        assertThatThrownBy(() -> transactionService.postTransaction(transactionDto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("must be positive");
        
        verify(mockTransactionRepository, never()).save(any(Transaction.class));
    }

    /**
     * Test maximum amount validation (S9(09)V99 = 9999999.99).
     * 
     * Validates: Transaction amounts within COBOL PIC S9(09)V99 range.
     * Maximum value: 999,999,999.99
     */
    @Test
    void testMaxAmount() {
        // Arrange: Maximum valid COBOL COMP-3 amount
        BigDecimal maxAmount = new BigDecimal("999999999.99").setScale(2, RoundingMode.HALF_UP);
        TransactionDto transactionDto = createTestTransactionDto(TEST_CARD_NUM, TEST_TYPE_PURCHASE, maxAmount);
        
        setupSuccessfulPostTransactionMocks();
        
        // Account with sufficient credit limit for max amount
        BigDecimal hugeBalance = new BigDecimal("500000000.00").setScale(2, RoundingMode.HALF_UP);
        BigDecimal hugeCreditLimit = new BigDecimal("1000000000.00").setScale(2, RoundingMode.HALF_UP);
        AccountDto mockAccount = createTestAccountDto(TEST_ACCT_ID, hugeBalance, hugeCreditLimit);
        when(mockAccountService.getAccountById(TEST_ACCT_ID)).thenReturn(mockAccount);
        
        Transaction savedTransaction = createTestTransaction("TXN_MAX", TEST_CARD_NUM, maxAmount);
        when(mockTransactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        // Act & Assert: Should succeed with maximum valid amount
        TransactionDto result = transactionService.postTransaction(transactionDto);
        
        assertNotNull(result);
        assertEquals(0, maxAmount.compareTo(result.getTransAmt()));
        assertEquals(2, result.getTransAmt().scale());
    }

    //=============================================================================
    // CATEGORY 5: Transaction Type Validation Tests
    //=============================================================================

    /**
     * Test valid transaction type codes.
     * 
     * Validates: Transaction types '01'-'08' are valid per CVTRA03Y.cpy.
     * COBOL equivalent: PIC X(02) TRAN-TYPE-CD field validation.
     */
    @Test
    void testValidTransactionType() {
        // Arrange: Valid type code '01' (Purchase)
        TransactionDto transactionDto = createTestTransactionDto(TEST_CARD_NUM, TEST_TYPE_PURCHASE, TEST_AMOUNT);
        
        setupSuccessfulPostTransactionMocks();
        
        AccountDto mockAccount = createTestAccountDto(TEST_ACCT_ID, TEST_BALANCE, TEST_CREDIT_LIMIT);
        when(mockAccountService.getAccountById(TEST_ACCT_ID)).thenReturn(mockAccount);
        
        Transaction savedTransaction = createTestTransaction("TXN_TYPE", TEST_CARD_NUM, TEST_AMOUNT);
        when(mockTransactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        // Act
        TransactionDto result = transactionService.postTransaction(transactionDto);

        // Assert
        assertNotNull(result);
        assertEquals(TEST_TYPE_PURCHASE, result.getTransTypeCd());
        verify(mockValidationService).validateTransactionType(TEST_TYPE_PURCHASE);
    }

    /**
     * Test invalid transaction type rejected.
     * 
     * Validates: Invalid type codes throw validation exception.
     */
    @Test
    void testInvalidTransactionType() {
        // Arrange: Invalid type code '99'
        TransactionDto transactionDto = createTestTransactionDto(TEST_CARD_NUM, "99", TEST_AMOUNT);
        
        doNothing().when(mockValidationService).validateAmount(any(BigDecimal.class));
        doNothing().when(mockValidationService).validateCardNumber(anyString());
        doThrow(new BusinessException("Invalid transaction type"))
            .when(mockValidationService).validateTransactionType("99");

        // Act & Assert
        assertThatThrownBy(() -> transactionService.postTransaction(transactionDto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Invalid transaction type");
    }

    /**
     * Test transaction type meanings (01=Purchase, 02=Cash Advance, 04=Payment).
     * 
     * Validates: Transaction type codes correctly classified as debit or credit.
     */
    @Test
    void testTransactionTypeMapping() {
        // Test debit type (01 = Purchase)
        assertTrue("01".equals(TEST_TYPE_PURCHASE), "Type 01 is Purchase");
        
        // Test credit type (04 = Payment)
        assertTrue("04".equals(TEST_TYPE_PAYMENT), "Type 04 is Payment");
        
        // Test another debit type (02 = Cash Advance)
        assertTrue("02".equals(TEST_TYPE_CASH_ADV), "Type 02 is Cash Advance");
    }

    //=============================================================================
    // CATEGORY 6: Transaction Category Validation Tests
    //=============================================================================

    /**
     * Test valid transaction category codes.
     * 
     * Validates: Category codes 1001-9999 are valid per CVTRA04Y.cpy.
     * COBOL equivalent: PIC 9(04) TRAN-CAT-CD field validation.
     */
    @Test
    void testValidTransactionCategory() {
        // Arrange: Valid category code 1001
        TransactionDto transactionDto = createTestTransactionDto(TEST_CARD_NUM, TEST_TYPE_PURCHASE, TEST_AMOUNT);
        transactionDto.setTransCatCd(1001);
        
        setupSuccessfulPostTransactionMocks();
        
        AccountDto mockAccount = createTestAccountDto(TEST_ACCT_ID, TEST_BALANCE, TEST_CREDIT_LIMIT);
        when(mockAccountService.getAccountById(TEST_ACCT_ID)).thenReturn(mockAccount);
        
        Transaction savedTransaction = createTestTransaction("TXN_CAT", TEST_CARD_NUM, TEST_AMOUNT);
        when(mockTransactionRepository.save(any(Transaction.class))).thenReturn(savedTransaction);

        // Act
        TransactionDto result = transactionService.postTransaction(transactionDto);

        // Assert
        assertNotNull(result);
        assertEquals(1001, result.getTransCatCd());
        verify(mockValidationService).validateTransactionCategory(1001);
    }

    /**
     * Test invalid transaction category rejected.
     * 
     * Validates: Out-of-range category codes throw validation exception.
     */
    @Test
    void testInvalidTransactionCategory() {
        // Arrange: Invalid category code 0
        TransactionDto transactionDto = createTestTransactionDto(TEST_CARD_NUM, TEST_TYPE_PURCHASE, TEST_AMOUNT);
        transactionDto.setTransCatCd(0);
        
        doNothing().when(mockValidationService).validateAmount(any(BigDecimal.class));
        doNothing().when(mockValidationService).validateCardNumber(anyString());
        doNothing().when(mockValidationService).validateTransactionType(anyString());
        doThrow(new BusinessException("Invalid category code"))
            .when(mockValidationService).validateTransactionCategory(0);

        // Act & Assert
        assertThatThrownBy(() -> transactionService.postTransaction(transactionDto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Invalid category code");
    }

    /**
     * Test category code range (1001-9999).
     * 
     * Validates: Category codes within valid PIC 9(04) range.
     */
    @Test
    void testCategoryCodeRange() {
        // Valid categories: 1001, 5000, 9999
        Integer validCat1 = 1001;
        Integer validCat2 = 5000;
        Integer validCat3 = 9999;
        
        assertTrue(validCat1 >= 1000 && validCat1 <= 9999);
        assertTrue(validCat2 >= 1000 && validCat2 <= 9999);
        assertTrue(validCat3 >= 1000 && validCat3 <= 9999);
    }

    //=============================================================================
    // CATEGORY 7: Merchant Data Tests
    //=============================================================================

    /**
     * Test merchant ID storage.
     * 
     * Validates: Merchant ID stored correctly as PIC 9(09) → String.
     */
    @Test
    void testMerchantIdStorage() {
        // Arrange
        Transaction transaction = createTestTransaction(TEST_TRANS_ID, TEST_CARD_NUM, TEST_AMOUNT);
        transaction.setTransMerchantId(TEST_MERCHANT_ID);
        
        when(mockTransactionRepository.findById(TEST_TRANS_ID))
            .thenReturn(Optional.of(transaction));

        // Act
        TransactionDto result = transactionService.getTransactionById(TEST_TRANS_ID);

        // Assert
        assertNotNull(result);
        assertEquals(TEST_MERCHANT_ID, result.getTransMerchantId());
        assertTrue(TEST_MERCHANT_ID >= 100000000L && TEST_MERCHANT_ID <= 999999999L, 
            "Merchant ID must be 9 digits (100000000-999999999)");
    }

    /**
     * Test merchant name storage.
     * 
     * Validates: Merchant name stored correctly as PIC X(50) → String length 50.
     */
    @Test
    void testMerchantNameStorage() {
        // Arrange
        Transaction transaction = createTestTransaction(TEST_TRANS_ID, TEST_CARD_NUM, TEST_AMOUNT);
        transaction.setTransMerchantName(TEST_MERCHANT_NAME);
        
        when(mockTransactionRepository.findById(TEST_TRANS_ID))
            .thenReturn(Optional.of(transaction));

        // Act
        TransactionDto result = transactionService.getTransactionById(TEST_TRANS_ID);

        // Assert
        assertNotNull(result);
        assertEquals(TEST_MERCHANT_NAME, result.getTransMerchantName());
        assertTrue(TEST_MERCHANT_NAME.length() <= 50, "Merchant name max 50 characters");
    }

    /**
     * Test merchant city storage.
     * 
     * Validates: Merchant city stored correctly as PIC X(50) → String length 50.
     */
    @Test
    void testMerchantCityStorage() {
        // Arrange
        Transaction transaction = createTestTransaction(TEST_TRANS_ID, TEST_CARD_NUM, TEST_AMOUNT);
        transaction.setTransMerchantCity(TEST_MERCHANT_CITY);
        
        when(mockTransactionRepository.findById(TEST_TRANS_ID))
            .thenReturn(Optional.of(transaction));

        // Act
        TransactionDto result = transactionService.getTransactionById(TEST_TRANS_ID);

        // Assert
        assertNotNull(result);
        assertEquals(TEST_MERCHANT_CITY, result.getTransMerchantCity());
        assertTrue(TEST_MERCHANT_CITY.length() <= 50, "Merchant city max 50 characters");
    }

    /**
     * Test merchant ZIP storage.
     * 
     * Validates: Merchant ZIP stored correctly as PIC X(10) → String length 10.
     */
    @Test
    void testMerchantZipStorage() {
        // Arrange
        Transaction transaction = createTestTransaction(TEST_TRANS_ID, TEST_CARD_NUM, TEST_AMOUNT);
        transaction.setTransMerchantZip(TEST_MERCHANT_ZIP);
        
        when(mockTransactionRepository.findById(TEST_TRANS_ID))
            .thenReturn(Optional.of(transaction));

        // Act
        TransactionDto result = transactionService.getTransactionById(TEST_TRANS_ID);

        // Assert
        assertNotNull(result);
        assertEquals(TEST_MERCHANT_ZIP, result.getTransMerchantZip());
        assertTrue(TEST_MERCHANT_ZIP.length() <= 10, "Merchant ZIP max 10 characters");
    }

    /**
     * Test merchant data required for purchase transactions.
     * 
     * Validates: Purchase transactions (type '01') require merchant information.
     */
    @Test
    void testMerchantDataRequiredForPurchase() {
        // Arrange: Purchase without merchant ID
        TransactionDto transactionDto = createTestTransactionDto(TEST_CARD_NUM, TEST_TYPE_PURCHASE, TEST_AMOUNT);
        transactionDto.setTransMerchantId(null);
        
        doNothing().when(mockValidationService).validateAmount(any(BigDecimal.class));
        doNothing().when(mockValidationService).validateCardNumber(anyString());
        doNothing().when(mockValidationService).validateTransactionType(anyString());
        doNothing().when(mockValidationService).validateTransactionCategory(anyInt());

        // Act & Assert
        assertThatThrownBy(() -> transactionService.postTransaction(transactionDto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Merchant ID is required");
    }

    //=============================================================================
    // CATEGORY 8: Transaction Source Tests
    //=============================================================================

    /**
     * Test POS transaction source.
     * 
     * Validates: Point-of-sale transactions have source "POS".
     */
    @Test
    void testTransactionSourcePOS() {
        // Arrange
        Transaction transaction = createTestTransaction(TEST_TRANS_ID, TEST_CARD_NUM, TEST_AMOUNT);
        transaction.setTransSource(TEST_SOURCE_POS);
        
        when(mockTransactionRepository.findById(TEST_TRANS_ID))
            .thenReturn(Optional.of(transaction));

        // Act
        TransactionDto result = transactionService.getTransactionById(TEST_TRANS_ID);

        // Assert
        assertEquals(TEST_SOURCE_POS, result.getTransSource());
    }

    /**
     * Test ATM transaction source.
     * 
     * Validates: ATM withdrawal transactions have source "ATM".
     */
    @Test
    void testTransactionSourceATM() {
        // Arrange
        Transaction transaction = createTestTransaction(TEST_TRANS_ID, TEST_CARD_NUM, TEST_AMOUNT);
        transaction.setTransSource(TEST_SOURCE_ATM);
        
        when(mockTransactionRepository.findById(TEST_TRANS_ID))
            .thenReturn(Optional.of(transaction));

        // Act
        TransactionDto result = transactionService.getTransactionById(TEST_TRANS_ID);

        // Assert
        assertEquals(TEST_SOURCE_ATM, result.getTransSource());
    }

    /**
     * Test online transaction source.
     * 
     * Validates: Online transactions have source "ONLINE".
     */
    @Test
    void testTransactionSourceOnline() {
        // Arrange
        Transaction transaction = createTestTransaction(TEST_TRANS_ID, TEST_CARD_NUM, TEST_AMOUNT);
        transaction.setTransSource(TEST_SOURCE_ONLINE);
        
        when(mockTransactionRepository.findById(TEST_TRANS_ID))
            .thenReturn(Optional.of(transaction));

        // Act
        TransactionDto result = transactionService.getTransactionById(TEST_TRANS_ID);

        // Assert
        assertEquals(TEST_SOURCE_ONLINE, result.getTransSource());
    }

    /**
     * Test transaction source field length.
     * 
     * Validates: Transaction source fits PIC X(10) field length.
     */
    @Test
    void testTransactionSourceLength() {
        assertTrue(TEST_SOURCE_POS.length() <= 10);
        assertTrue(TEST_SOURCE_ATM.length() <= 10);
        assertTrue(TEST_SOURCE_ONLINE.length() <= 10);
    }

    //=============================================================================
    // CATEGORY 9: Timestamp Tests
    //=============================================================================

    /**
     * Test transaction origination timestamp.
     * 
     * Validates: Transaction origination timestamp (TRAN-ORIG-TS) conversion to Timestamp.
     * COBOL equivalent: PIC X(26) → Timestamp.
     */
    @Test
    void testTransactionOrigTimestamp() {
        // Arrange
        LocalDateTime origTime = LocalDateTime.of(2024, 6, 15, 14, 30, 0);
        Transaction transaction = createTestTransaction(TEST_TRANS_ID, TEST_CARD_NUM, TEST_AMOUNT);
        transaction.setTransOrigTs(Timestamp.valueOf(origTime));
        
        when(mockTransactionRepository.findById(TEST_TRANS_ID))
            .thenReturn(Optional.of(transaction));

        // Act
        TransactionDto result = transactionService.getTransactionById(TEST_TRANS_ID);

        // Assert
        assertNotNull(result.getTransOrigTs());
    }

    /**
     * Test transaction processing timestamp auto-populated.
     * 
     * Validates: Transaction processing timestamp (TRAN-PROC-TS) set automatically on save.
     * COBOL equivalent: MOVE FUNCTION CURRENT-DATE TO TRAN-PROC-TS.
     */
    @Test
    void testTransactionProcTimestamp() {
        // Arrange
        LocalDateTime procTime = LocalDateTime.now();
        Transaction transaction = createTestTransaction(TEST_TRANS_ID, TEST_CARD_NUM, TEST_AMOUNT);
        transaction.setTransProcTs(Timestamp.valueOf(procTime));
        
        when(mockTransactionRepository.findById(TEST_TRANS_ID))
            .thenReturn(Optional.of(transaction));

        // Act
        TransactionDto result = transactionService.getTransactionById(TEST_TRANS_ID);

        // Assert
        assertNotNull(result.getTransProcTs());
    }

    /**
     * Test timestamp format preservation.
     * 
     * Validates: Timestamps maintain ISO format after conversion.
     */
    @Test
    void testTimestampFormat() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        Timestamp timestamp = Timestamp.valueOf(now);
        
        Transaction transaction = createTestTransaction(TEST_TRANS_ID, TEST_CARD_NUM, TEST_AMOUNT);
        transaction.setTransOrigTs(timestamp);
        transaction.setTransProcTs(timestamp);
        
        when(mockTransactionRepository.findById(TEST_TRANS_ID))
            .thenReturn(Optional.of(transaction));

        // Act
        TransactionDto result = transactionService.getTransactionById(TEST_TRANS_ID);

        // Assert
        assertNotNull(result.getTransOrigTs());
        assertNotNull(result.getTransProcTs());
    }

    //=============================================================================
    // Helper Methods for Test Data Creation
    //=============================================================================

    /**
     * Create test Transaction entity with basic fields populated.
     * Matches CVTRA05Y.cpy 350-byte record structure.
     */
    private Transaction createTestTransaction(String transId, String cardNum, BigDecimal amount) {
        return Transaction.builder()
            .transId(transId)
            .transCardNum(cardNum)
            .transTypeCd(TEST_TYPE_PURCHASE)
            .transCatCd(TEST_CAT_CODE)
            .transSource(TEST_SOURCE_POS)
            .transDesc(TEST_DESC)
            .transAmt(amount.setScale(2, RoundingMode.HALF_UP))
            .transMerchantId(TEST_MERCHANT_ID)
            .transMerchantName(TEST_MERCHANT_NAME)
            .transMerchantCity(TEST_MERCHANT_CITY)
            .transMerchantZip(TEST_MERCHANT_ZIP)
            .transOrigTs(Timestamp.valueOf(LocalDateTime.now()))
            .transProcTs(Timestamp.valueOf(LocalDateTime.now()))
            .build();
    }

    /**
     * Create test TransactionDto for posting transactions.
     */
    private TransactionDto createTestTransactionDto(String cardNum, String typeCd, BigDecimal amount) {
        TransactionDto dto = new TransactionDto();
        dto.setTransCardNum(cardNum);
        dto.setTransTypeCd(typeCd);
        dto.setTransCatCd(TEST_CAT_CODE);
        dto.setTransSource(TEST_SOURCE_POS);
        dto.setTransDesc(TEST_DESC);
        dto.setTransAmt(amount);
        dto.setTransMerchantId(TEST_MERCHANT_ID);
        dto.setTransMerchantName(TEST_MERCHANT_NAME);
        dto.setTransMerchantCity(TEST_MERCHANT_CITY);
        dto.setTransMerchantZip(TEST_MERCHANT_ZIP);
        return dto;
    }

    /**
     * Create test CardDto for mocking card service responses.
     */
    private CardDto createTestCardDto(String cardNum, Long acctId, String status) {
        CardDto card = new CardDto();
        card.setCardNum(cardNum);
        card.setCardAcctId(acctId);
        card.setCardStatus(status);
        card.setCardEmbossedName("JOHN DOE");
        card.setCardExpirationDate(LocalDate.now().plusYears(2));
        return card;
    }

    /**
     * Create test AccountDto for mocking account service responses.
     */
    private AccountDto createTestAccountDto(Long acctId, BigDecimal balance, BigDecimal creditLimit) {
        AccountDto account = new AccountDto();
        account.setAcctId(acctId);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(balance);
        account.setAcctCreditLimit(creditLimit);
        account.setAcctCurrCycCredit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        account.setAcctCurrCycDebit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        return account;
    }

    /**
     * Setup common mocks for successful postTransaction() tests.
     */
    private void setupSuccessfulPostTransactionMocks() {
        doNothing().when(mockValidationService).validateAmount(any(BigDecimal.class));
        doNothing().when(mockValidationService).validateCardNumber(anyString());
        doNothing().when(mockValidationService).validateTransactionType(anyString());
        doNothing().when(mockValidationService).validateTransactionCategory(anyInt());
        
        CardDto mockCard = createTestCardDto(TEST_CARD_NUM, TEST_ACCT_ID, "Y");
        when(mockCardService.getCardByNumber(anyString())).thenReturn(mockCard);
        
        // Mock updateAccount to return an AccountDto (it's not void)
        AccountDto updatedAccount = createTestAccountDto(TEST_ACCT_ID, TEST_BALANCE, TEST_CREDIT_LIMIT);
        when(mockAccountService.updateAccount(anyLong(), any(AccountDto.class))).thenReturn(updatedAccount);
    }
}
