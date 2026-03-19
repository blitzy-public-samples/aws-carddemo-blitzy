/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.service.batch;

import com.cardemo.common.util.DateConversionUtil;
import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.CategoryBalance;
import com.cardemo.entity.DailyTransaction;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CategoryBalanceRepository;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 unit tests for {@link DailyPostingService} — the most complex batch
 * service migrated from CBTRN02C.cbl (PROGRAM-ID: CBTRN02C).
 *
 * <p>Covers ALL 4 validation reject codes (100–103), transaction posting, TCATBAL
 * update/create, account record updates, DB2 timestamp generation, and return
 * code semantics using Mockito mocks (no Spring context loading).</p>
 *
 * <h3>COBOL Paragraph Coverage</h3>
 * <ul>
 *   <li>1500-A-LOOKUP-XREF → reject code 100 tests</li>
 *   <li>1500-B-LOOKUP-ACCT → reject codes 101, 102, 103 tests</li>
 *   <li>2000-POST-TRANSACTION → successful posting test</li>
 *   <li>2700-UPDATE-TCATBAL → TCATBAL update/create tests</li>
 *   <li>2800-UPDATE-ACCOUNT-REC → account cycle credit/debit tests</li>
 *   <li>Z-GET-DB2-FORMAT-TIMESTAMP → timestamp format test</li>
 *   <li>MAIN loop → return code 0/4 tests</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DailyPostingServiceTest {

    // ========================================================================
    // Mock Dependencies (5 repositories — matches DailyPostingService constructor)
    // ========================================================================

    @Mock
    private DailyTransactionRepository dailyTransactionRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CategoryBalanceRepository categoryBalanceRepository;

    /** Service under test — injected with mocked repositories by Mockito. */
    @InjectMocks
    private DailyPostingService dailyPostingService;

    // ========================================================================
    // Constants for Test Data
    // ========================================================================

    /** Valid 16-character card number present in XREF. */
    private static final String VALID_CARD_NUM = "4111111111111111";

    /** Invalid card number NOT in XREF (triggers reject code 100). */
    private static final String INVALID_CARD_NUM = "9999999999999999";

    /** Valid 11-character account ID present in ACCTDATA. */
    private static final String VALID_ACCT_ID = "00000000001";

    /** Account ID that does NOT exist in ACCTDATA (triggers reject code 101). */
    private static final String MISSING_ACCT_ID = "00000000099";

    /** Standard 26-char DB2-format origination timestamp for test transactions. */
    private static final String TEST_ORIG_TIMESTAMP = "2024-01-15-10.30.00.000000";

    /** Deterministic 26-char DB2-format processing timestamp for verify assertions. */
    private static final String TEST_PROC_TIMESTAMP = "2024-01-15-14.00.00.000000";

    // ========================================================================
    // @BeforeEach — Per-test initialisation
    // ========================================================================

    @BeforeEach
    void setUp() {
        // MockitoExtension recreates @Mock and @InjectMocks for each test.
        // Working-storage counters (transactionCount, rejectCount) reset to 0
        // by default in the new DailyPostingService instance. No additional
        // shared setup required — each test creates its own specific test data.
    }

    // ========================================================================
    // Helper Methods — Test Data Factories
    // ========================================================================

    /**
     * Creates a standard test DailyTransaction with valid card number and
     * an amount of 100.00 within normal credit limits.
     *
     * <p>Field values modelled after app/data/ASCII/dailytran.txt records.</p>
     *
     * @return a DailyTransaction suitable for most test paths
     */
    private DailyTransaction createTestDailyTransaction() {
        return new DailyTransaction(
                "0000000000000001",          // dalytranId
                "01",                        // typeCode
                Integer.valueOf(1),          // categoryCode (Integer per entity)
                "ONLINE",                    // source
                "Test transaction",          // description
                new BigDecimal("100.00"),    // amount (BigDecimal — never float)
                "000000001",                 // merchantId
                "Test Merchant",             // merchantName
                "Test City",                 // merchantCity
                "12345",                     // merchantZip
                VALID_CARD_NUM,              // cardNum (16 chars)
                TEST_ORIG_TIMESTAMP,         // origTimestamp (26-char DB2 format)
                ""                           // procTimestamp (filled during posting)
        );
    }

    /**
     * Creates a DailyTransaction with a custom amount (all other fields standard).
     *
     * @param amount the transaction amount (use negative for debits)
     * @return DailyTransaction with the specified amount
     */
    private DailyTransaction createDailyTransactionWithAmount(BigDecimal amount) {
        return new DailyTransaction(
                "0000000000000001", "01", Integer.valueOf(1),
                "ONLINE", "Test transaction", amount,
                "000000001", "Test Merchant", "Test City", "12345",
                VALID_CARD_NUM, TEST_ORIG_TIMESTAMP, ""
        );
    }

    /**
     * Creates a DailyTransaction with a specific card number.
     *
     * @param cardNum the 16-character card number
     * @return DailyTransaction with the specified card
     */
    private DailyTransaction createDailyTransactionWithCard(String cardNum) {
        return new DailyTransaction(
                "0000000000000001", "01", Integer.valueOf(1),
                "ONLINE", "Test transaction", new BigDecimal("100.00"),
                "000000001", "Test Merchant", "Test City", "12345",
                cardNum, TEST_ORIG_TIMESTAMP, ""
        );
    }

    /**
     * Creates a standard test CardXref linking the valid card to the valid account.
     * Mirrors CARDXREF VSAM KSDS 50-byte junction records (CVACT03Y.cpy).
     *
     * @return CardXref with standard valid card → valid account mapping
     */
    private CardXref createTestXref() {
        return new CardXref(VALID_CARD_NUM, "000000001", VALID_ACCT_ID);
    }

    /**
     * Creates a CardXref that maps the valid card to a specific account.
     *
     * @param accountId the target account ID (11 chars)
     * @return CardXref pointing to the specified account
     */
    private CardXref createXrefWithAccount(String accountId) {
        return new CardXref(VALID_CARD_NUM, "000000001", accountId);
    }

    /**
     * Creates a standard test Account within normal limits and not expired.
     * Mirrors ACCTDATA VSAM KSDS 300-byte records (CVACT01Y.cpy).
     *
     * <ul>
     *   <li>currBal = 5000.00</li>
     *   <li>creditLimit = 10000.00</li>
     *   <li>currCycCredit = 500.00, currCycDebit = 200.00</li>
     *   <li>expirationDate = "2025-12-31" (not expired relative to 2024 test dates)</li>
     * </ul>
     *
     * @return Account within limits and not expired
     */
    private Account createTestAccount() {
        return new Account(
                VALID_ACCT_ID,                   // acctId (11 chars)
                "Y",                              // activeStatus
                new BigDecimal("5000.00"),        // currBal
                new BigDecimal("10000.00"),       // creditLimit
                new BigDecimal("3000.00"),        // cashCreditLimit
                "2020-01-01",                     // openDate
                "2025-12-31",                     // expirationDate
                "2023-01-01",                     // reissueDate
                new BigDecimal("500.00"),         // currCycCredit
                new BigDecimal("200.00"),         // currCycDebit
                "12345",                          // addrZip
                "A"                               // groupId
        );
    }

    // ========================================================================
    // Test: Reject Code 100 — XREF Not Found (← 1500-A-LOOKUP-XREF)
    // ========================================================================

    @Test
    @DisplayName("Reject code 100: XREF not found for card number")
    void shouldRejectWithCode100WhenXrefNotFound() {
        // Given: DailyTransaction with card number not in XREF file
        DailyTransaction dailyTran = createDailyTransactionWithCard(INVALID_CARD_NUM);

        when(cardXrefRepository.findByXrefCardNum(eq(INVALID_CARD_NUM)))
                .thenReturn(Optional.empty());

        // When: Validate the transaction (← 1500-VALIDATE-TRAN)
        DailyPostingService.ValidationResult result =
                dailyPostingService.validateTransaction(dailyTran);

        // Then: Rejected with code 100 — "INVALID CARD NUMBER FOUND"
        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailReasonCode()).isEqualTo(100);
        assertThat(result.getFailReasonDescription())
                .isEqualTo("INVALID CARD NUMBER FOUND");

        // No transaction should be posted (COBOL: skip 2000-POST-TRANSACTION)
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    // ========================================================================
    // Test: Reject Code 101 — Account Not Found (← 1500-B-LOOKUP-ACCT)
    // ========================================================================

    @Test
    @DisplayName("Reject code 101: Account not found for XREF account ID")
    void shouldRejectWithCode101WhenAccountNotFound() {
        // Given: Valid card with XREF pointing to non-existent account
        DailyTransaction dailyTran = createTestDailyTransaction();
        CardXref xref = createXrefWithAccount(MISSING_ACCT_ID);

        when(cardXrefRepository.findByXrefCardNum(eq(VALID_CARD_NUM)))
                .thenReturn(Optional.of(xref));
        when(accountRepository.findById(eq(MISSING_ACCT_ID)))
                .thenReturn(Optional.empty());

        // When: Validate the transaction
        DailyPostingService.ValidationResult result =
                dailyPostingService.validateTransaction(dailyTran);

        // Then: Rejected with code 101 — "ACCOUNT RECORD NOT FOUND"
        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailReasonCode()).isEqualTo(101);
        assertThat(result.getFailReasonDescription())
                .isEqualTo("ACCOUNT RECORD NOT FOUND");

        // No transaction should be posted
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    // ========================================================================
    // Test: Reject Code 102 — Credit Limit Exceeded (← 1500-B-LOOKUP-ACCT)
    // COBOL formula: WS-TEMP-BAL = CURR-CYC-CREDIT - CURR-CYC-DEBIT + AMT
    //               IF CREDIT-LIMIT < WS-TEMP-BAL → reject 102
    // ========================================================================

    @Test
    @DisplayName("Reject code 102: Credit limit exceeded (OVERLIMIT)")
    void shouldRejectWithCode102WhenCreditLimitExceeded() {
        // Given: Account near credit limit with transaction pushing over
        // WS-TEMP-BAL = 9000.00 - 200.00 + 1500.00 = 10300.00
        // creditLimit = 10000.00 → 10000 < 10300 → REJECT 102
        DailyTransaction dailyTran = createDailyTransactionWithAmount(
                new BigDecimal("1500.00"));
        CardXref xref = createTestXref();
        Account account = new Account(
                VALID_ACCT_ID, "Y",
                new BigDecimal("5000.00"),        // currBal
                new BigDecimal("10000.00"),       // creditLimit
                new BigDecimal("3000.00"),        // cashCreditLimit
                "2020-01-01",                     // openDate
                "2025-12-31",                     // expirationDate (NOT expired)
                "2023-01-01",                     // reissueDate
                new BigDecimal("9000.00"),        // currCycCredit — high value
                new BigDecimal("200.00"),         // currCycDebit
                "12345", "A"
        );

        when(cardXrefRepository.findByXrefCardNum(eq(VALID_CARD_NUM)))
                .thenReturn(Optional.of(xref));
        when(accountRepository.findById(eq(VALID_ACCT_ID)))
                .thenReturn(Optional.of(account));

        // When: Validate
        DailyPostingService.ValidationResult result =
                dailyPostingService.validateTransaction(dailyTran);

        // Then: Rejected with code 102 — "OVERLIMIT TRANSACTION"
        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailReasonCode()).isEqualTo(102);
        assertThat(result.getFailReasonDescription())
                .isEqualTo("OVERLIMIT TRANSACTION");
    }

    // ========================================================================
    // Test: Balance equals limit exactly — should PASS (not reject)
    // COBOL: IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL → pass (equals = pass)
    // ========================================================================

    @Test
    @DisplayName("Should pass when balance equals credit limit exactly")
    void shouldPassWhenBalanceEqualsLimitExactly() {
        // Given: tempBal exactly equals creditLimit
        // WS-TEMP-BAL = 9000.00 - 200.00 + 1200.00 = 10000.00
        // creditLimit = 10000.00 → 10000 < 10000 → FALSE → PASS
        DailyTransaction dailyTran = createDailyTransactionWithAmount(
                new BigDecimal("1200.00"));
        CardXref xref = createTestXref();
        Account account = new Account(
                VALID_ACCT_ID, "Y",
                new BigDecimal("5000.00"),
                new BigDecimal("10000.00"),       // creditLimit
                new BigDecimal("3000.00"),
                "2020-01-01",
                "2025-12-31",                     // expirationDate (NOT expired)
                "2023-01-01",
                new BigDecimal("9000.00"),        // currCycCredit
                new BigDecimal("200.00"),         // currCycDebit
                "12345", "A"
        );

        when(cardXrefRepository.findByXrefCardNum(eq(VALID_CARD_NUM)))
                .thenReturn(Optional.of(xref));
        when(accountRepository.findById(eq(VALID_ACCT_ID)))
                .thenReturn(Optional.of(account));

        // When: Validate
        DailyPostingService.ValidationResult result =
                dailyPostingService.validateTransaction(dailyTran);

        // Then: Should PASS (creditLimit >= tempBal is true when equal)
        assertThat(result.isValid()).isTrue();
        assertThat(result.getFailReasonCode()).isEqualTo(0);
    }

    // ========================================================================
    // Test: Reject Code 103 — Account Expired (← 1500-B-LOOKUP-ACCT)
    // COBOL: IF ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10) → reject 103
    // ========================================================================

    @Test
    @DisplayName("Reject code 103: Transaction after account expiration")
    void shouldRejectWithCode103WhenAccountExpired() {
        // Given: Account expired before transaction origination date
        // expirationDate = "2023-06-30", origTimestamp(1:10) = "2024-01-15"
        // "2023-06-30" < "2024-01-15" → REJECT 103
        DailyTransaction dailyTran = createTestDailyTransaction();
        CardXref xref = createTestXref();
        Account account = new Account(
                VALID_ACCT_ID, "Y",
                new BigDecimal("5000.00"),
                new BigDecimal("10000.00"),
                new BigDecimal("3000.00"),
                "2020-01-01",
                "2023-06-30",                     // EXPIRED before 2024-01-15
                "2023-01-01",
                new BigDecimal("500.00"),         // currCycCredit (within limits)
                new BigDecimal("200.00"),         // currCycDebit
                "12345", "A"
        );

        when(cardXrefRepository.findByXrefCardNum(eq(VALID_CARD_NUM)))
                .thenReturn(Optional.of(xref));
        when(accountRepository.findById(eq(VALID_ACCT_ID)))
                .thenReturn(Optional.of(account));

        // When: Validate
        DailyPostingService.ValidationResult result =
                dailyPostingService.validateTransaction(dailyTran);

        // Then: Rejected with code 103
        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailReasonCode()).isEqualTo(103);
        assertThat(result.getFailReasonDescription())
                .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
    }

    // ========================================================================
    // Test: Successful Transaction Posting (← 2000-POST-TRANSACTION)
    // ========================================================================

    @Test
    @DisplayName("Should successfully post valid transaction")
    void shouldPostValidTransaction() {
        // Given: Valid transaction data, XREF, and account
        DailyTransaction dailyTran = createTestDailyTransaction();
        CardXref xref = createTestXref();
        Account account = createTestAccount();

        // Mock TCATBAL lookup → empty (new record will be created via 2700-A)
        when(categoryBalanceRepository.findByAccountIdAndTypeCodeAndCategoryCode(
                anyString(), anyString(), any(Integer.class)))
                .thenReturn(Optional.empty());

        // Use MockedStatic for deterministic processing timestamp
        try (MockedStatic<DateConversionUtil> mockedDateUtil =
                     mockStatic(DateConversionUtil.class)) {
            mockedDateUtil.when(DateConversionUtil::getCurrentTimestamp)
                    .thenReturn(TEST_PROC_TIMESTAMP);

            // When: Post the transaction (← 2000-POST-TRANSACTION)
            dailyPostingService.postTransaction(dailyTran, xref, account);
        }

        // Then: Verify Transaction was saved with correct DALYTRAN → TRAN field mapping
        ArgumentCaptor<Transaction> tranCaptor =
                ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(1)).save(tranCaptor.capture());
        Transaction savedTran = tranCaptor.getValue();

        // DALYTRAN-ID → TRAN-ID
        assertThat(savedTran.getTranId()).isEqualTo("0000000000000001");
        // DALYTRAN-TYPE-CD → TRAN-TYPE-CD
        assertThat(savedTran.getTypeCode()).isEqualTo("01");
        // DALYTRAN-CAT-CD → TRAN-CAT-CD
        assertThat(savedTran.getCategoryCode()).isEqualTo(Integer.valueOf(1));
        // DALYTRAN-SOURCE → TRAN-SOURCE
        assertThat(savedTran.getSource()).isEqualTo("ONLINE");
        // DALYTRAN-DESC → TRAN-DESC
        assertThat(savedTran.getDescription()).isEqualTo("Test transaction");
        // DALYTRAN-AMT → TRAN-AMT (BigDecimal precision)
        assertThat(savedTran.getAmount())
                .isEqualByComparingTo(new BigDecimal("100.00"));
        // Merchant fields
        assertThat(savedTran.getMerchantId()).isEqualTo("000000001");
        assertThat(savedTran.getMerchantName()).isEqualTo("Test Merchant");
        assertThat(savedTran.getMerchantCity()).isEqualTo("Test City");
        assertThat(savedTran.getMerchantZip()).isEqualTo("12345");
        // DALYTRAN-CARD-NUM → TRAN-CARD-NUM
        assertThat(savedTran.getCardNum()).isEqualTo(VALID_CARD_NUM);
        // DALYTRAN-ORIG-TS → TRAN-ORIG-TS
        assertThat(savedTran.getOrigTimestamp()).isEqualTo(TEST_ORIG_TIMESTAMP);
        // DB2-FORMAT-TS → TRAN-PROC-TS (26 chars: YYYY-MM-DD-HH.MM.SS.mmmmmm)
        assertThat(savedTran.getProcTimestamp()).isEqualTo(TEST_PROC_TIMESTAMP);
        assertThat(savedTran.getProcTimestamp()).hasSize(26);
    }

    // ========================================================================
    // Test: TCATBAL Update — Existing Record (← 2700-B-UPDATE-TCATBAL-REC)
    // ========================================================================

    @Test
    @DisplayName("Should update existing TCATBAL record with transaction amount")
    void shouldUpdateExistingTcatbalRecord() {
        // Given: Existing CategoryBalance with balance = 500.00 and amount = 100.00
        DailyTransaction dailyTran = createTestDailyTransaction();
        Account account = createTestAccount();
        CategoryBalance existingCatBal = new CategoryBalance(
                VALID_ACCT_ID, "01", Integer.valueOf(1),
                new BigDecimal("500.00")
        );

        when(categoryBalanceRepository.findByAccountIdAndTypeCodeAndCategoryCode(
                eq(VALID_ACCT_ID), eq("01"), eq(Integer.valueOf(1))))
                .thenReturn(Optional.of(existingCatBal));

        // When: Update TCATBAL (← 2700-UPDATE-TCATBAL)
        dailyPostingService.updateTcatbal(dailyTran, account);

        // Then: Balance = 500.00 + 100.00 = 600.00 (COBOL: ADD DALYTRAN-AMT TO TRAN-CAT-BAL)
        ArgumentCaptor<CategoryBalance> catBalCaptor =
                ArgumentCaptor.forClass(CategoryBalance.class);
        verify(categoryBalanceRepository).save(catBalCaptor.capture());
        assertThat(catBalCaptor.getValue().getBalance())
                .isEqualByComparingTo(new BigDecimal("600.00"));
    }

    // ========================================================================
    // Test: TCATBAL Create — Record Not Found (← 2700-A-CREATE-TCATBAL-REC)
    // ========================================================================

    @Test
    @DisplayName("Should create new TCATBAL record when not found (status 23)")
    void shouldCreateNewTcatbalRecordWhenNotFound() {
        // Given: No existing CategoryBalance for the composite key (VSAM status 23)
        DailyTransaction dailyTran = createTestDailyTransaction();
        Account account = createTestAccount();

        when(categoryBalanceRepository.findByAccountIdAndTypeCodeAndCategoryCode(
                eq(VALID_ACCT_ID), eq("01"), eq(Integer.valueOf(1))))
                .thenReturn(Optional.empty());

        // When: Update TCATBAL → triggers create path (← 2700-A)
        dailyPostingService.updateTcatbal(dailyTran, account);

        // Then: New CategoryBalance with balance = DALYTRAN-AMT = 100.00
        ArgumentCaptor<CategoryBalance> catBalCaptor =
                ArgumentCaptor.forClass(CategoryBalance.class);
        verify(categoryBalanceRepository).save(catBalCaptor.capture());
        CategoryBalance savedCatBal = catBalCaptor.getValue();

        assertThat(savedCatBal.getAccountId()).isEqualTo(VALID_ACCT_ID);
        assertThat(savedCatBal.getTypeCode()).isEqualTo("01");
        assertThat(savedCatBal.getCategoryCode()).isEqualTo(Integer.valueOf(1));
        assertThat(savedCatBal.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    // ========================================================================
    // Test: Account Update — Credit (← 2800-UPDATE-ACCOUNT-REC)
    // COBOL: ADD DALYTRAN-AMT TO ACCT-CURR-BAL
    //        IF DALYTRAN-AMT >= 0: ADD TO ACCT-CURR-CYC-CREDIT
    // ========================================================================

    @Test
    @DisplayName("Should add positive amount to ACCT-CURR-CYC-CREDIT")
    void shouldAddPositiveAmountToCycleCredit() {
        // Given: Positive amount (credit transaction), currBal=5000, currCycCredit=500
        Account account = createTestAccount();
        DailyTransaction dailyTran = createTestDailyTransaction(); // amount=100.00

        // When: Update account record (← 2800-UPDATE-ACCOUNT-REC)
        dailyPostingService.updateAccountRecord(account, dailyTran);

        // Then: currBal = 5000 + 100 = 5100, currCycCredit = 500 + 100 = 600
        assertThat(account.getCurrBal())
                .isEqualByComparingTo(new BigDecimal("5100.00"));
        assertThat(account.getCurrCycCredit())
                .isEqualByComparingTo(new BigDecimal("600.00"));
        // currCycDebit should be unchanged
        assertThat(account.getCurrCycDebit())
                .isEqualByComparingTo(new BigDecimal("200.00"));

        // Verify REWRITE (accountRepository.save) was called
        verify(accountRepository).save(account);
    }

    // ========================================================================
    // Test: Account Update — Debit (← 2800-UPDATE-ACCOUNT-REC)
    // COBOL: ADD DALYTRAN-AMT TO ACCT-CURR-BAL
    //        IF DALYTRAN-AMT < 0: ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
    // ========================================================================

    @Test
    @DisplayName("Should add negative amount to ACCT-CURR-CYC-DEBIT")
    void shouldAddNegativeAmountToCycleDebit() {
        // Given: Negative amount (debit transaction)
        // COBOL ADD semantics: adds the signed value directly
        Account account = createTestAccount(); // currBal=5000, currCycDebit=200
        DailyTransaction dailyTran = createDailyTransactionWithAmount(
                new BigDecimal("-50.00"));

        // When: Update account record (← 2800-UPDATE-ACCOUNT-REC)
        dailyPostingService.updateAccountRecord(account, dailyTran);

        // Then: currBal = 5000 + (-50) = 4950
        assertThat(account.getCurrBal())
                .isEqualByComparingTo(new BigDecimal("4950.00"));
        // currCycDebit = 200 + (-50) = 150 (COBOL ADD with signed value)
        assertThat(account.getCurrCycDebit())
                .isEqualByComparingTo(new BigDecimal("150.00"));
        // currCycCredit should be unchanged
        assertThat(account.getCurrCycCredit())
                .isEqualByComparingTo(new BigDecimal("500.00"));

        // Verify REWRITE
        verify(accountRepository).save(account);
    }

    // ========================================================================
    // Test: DB2 Timestamp Generation (← Z-GET-DB2-FORMAT-TIMESTAMP)
    // Format: YYYY-MM-DD-HH.MM.SS.mmmmmm (exactly 26 characters)
    // ========================================================================

    @Test
    @DisplayName("Generated processing timestamp must be 26 chars in DB2 format")
    void shouldGenerateDb2FormatTimestamp() {
        // When: Generate a DB2-format timestamp via the service method
        String timestamp = dailyPostingService.generateDb2Timestamp();

        // Then: Must be exactly 26 characters in YYYY-MM-DD-HH.MM.SS.mmmmmm format
        assertThat(timestamp).isNotNull();
        assertThat(timestamp).hasSize(26);
        // Verify DB2 format pattern: 4 digits, dash, 2 digits, dash, 2 digits, dash,
        // 2 digits, dot, 2 digits, dot, 2 digits, dot, 6 digits
        assertThat(timestamp).matches(
                "\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{6}");
    }

    // ========================================================================
    // Test: Return Code = 4 When Rejects Exist
    // COBOL: RETURN-CODE = 4 when WS-REJECT-COUNT > 0
    // ========================================================================

    @Test
    @DisplayName("Return code should be 4 when rejects exist")
    void shouldReturnCode4WhenRejectsExist() {
        // Given: Mix of valid and invalid transactions
        DailyTransaction validTran = createTestDailyTransaction();
        DailyTransaction invalidTran = createDailyTransactionWithCard(INVALID_CARD_NUM);

        // Valid card → XREF found, account found (posting will succeed)
        when(cardXrefRepository.findByXrefCardNum(eq(VALID_CARD_NUM)))
                .thenReturn(Optional.of(createTestXref()));
        when(accountRepository.findById(eq(VALID_ACCT_ID)))
                .thenReturn(Optional.of(createTestAccount()));
        when(categoryBalanceRepository.findByAccountIdAndTypeCodeAndCategoryCode(
                anyString(), anyString(), any(Integer.class)))
                .thenReturn(Optional.empty());

        // Invalid card → XREF NOT found (reject code 100)
        when(cardXrefRepository.findByXrefCardNum(eq(INVALID_CARD_NUM)))
                .thenReturn(Optional.empty());

        // When: Process both transactions
        List<DailyPostingService.RejectRecord> rejects =
                dailyPostingService.processDailyTransactions(
                        List.of(validTran, invalidTran));

        // Then: RETURN-CODE = 4 (rejectCount > 0)
        assertThat(dailyPostingService.getRejectCount()).isEqualTo(1);
        assertThat(dailyPostingService.getTransactionCount()).isEqualTo(2);
        assertThat(rejects).hasSize(1);
        assertThat(rejects.get(0).getRejectCode()).isEqualTo(100);
        assertThat(rejects.get(0).getRejectDescription())
                .isEqualTo("INVALID CARD NUMBER FOUND");

        // Only the valid transaction was posted
        verify(transactionRepository, times(1)).save(any(Transaction.class));
    }

    // ========================================================================
    // Test: Return Code = 0 When All Posted
    // COBOL: RETURN-CODE = 0 when WS-REJECT-COUNT = 0
    // ========================================================================

    @Test
    @DisplayName("Return code should be 0 when all transactions posted")
    void shouldReturnCode0WhenAllPosted() {
        // Given: Two valid transactions
        DailyTransaction validTran1 = createTestDailyTransaction();
        DailyTransaction validTran2 = createDailyTransactionWithAmount(
                new BigDecimal("200.00"));

        when(cardXrefRepository.findByXrefCardNum(eq(VALID_CARD_NUM)))
                .thenReturn(Optional.of(createTestXref()));
        when(accountRepository.findById(eq(VALID_ACCT_ID)))
                .thenReturn(Optional.of(createTestAccount()));
        when(categoryBalanceRepository.findByAccountIdAndTypeCodeAndCategoryCode(
                anyString(), anyString(), any(Integer.class)))
                .thenReturn(Optional.empty());

        // When: Process all transactions
        List<DailyPostingService.RejectRecord> rejects =
                dailyPostingService.processDailyTransactions(
                        List.of(validTran1, validTran2));

        // Then: RETURN-CODE = 0 (no rejects)
        assertThat(dailyPostingService.getRejectCount()).isEqualTo(0);
        assertThat(dailyPostingService.getTransactionCount()).isEqualTo(2);
        assertThat(rejects).isEmpty();

        // Both transactions should be posted
        verify(transactionRepository, times(2)).save(any(Transaction.class));
    }
}
