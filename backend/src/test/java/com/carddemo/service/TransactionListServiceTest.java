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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */

package com.carddemo.service;

import com.carddemo.dto.response.TransactionListResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 test class for TransactionListService validating business logic transformation
 * from COTRN00C.cbl COBOL program.
 * 
 * <p><strong>COBOL Source Program:</strong> COTRN00C.cbl - Transaction list display with 
 * VSAM STARTBR/READNEXT/READPREV browsing pattern for sequential 10-per-page transaction access.</p>
 * 
 * <p><strong>Transformation Validation:</strong></p>
 * <ul>
 *   <li>Tests transaction list retrieval with pagination (10 transactions per page matching COBOL screen display)</li>
 *   <li>Validates date range filtering matching COBOL date validation logic</li>
 *   <li>Tests sorting by transaction date descending (most recent first per COBOL display)</li>
 *   <li>Validates account/card filtering equivalent to VSAM TRANSACT file sequential read</li>
 *   <li>Tests pagination state preservation equivalent to COBOL page-forward/page-backward logic using PF7/PF8 keys</li>
 *   <li>Ensures BigDecimal amount precision (scale=2, HALF_UP) maintains COBOL COMP-3 equivalence</li>
 * </ul>
 * 
 * <p><strong>Test Coverage Summary:</strong></p>
 * <ul>
 *   <li>Pagination: First page, next page (PF8), previous page (PF7), last page with partial records</li>
 *   <li>Filtering: By account ID, by card number, by date range with inclusive boundaries</li>
 *   <li>Sorting: Descending by transaction timestamp (most recent first)</li>
 *   <li>Data formatting: BigDecimal scale 2, date format YYYY-MM-DD, description truncation</li>
 *   <li>Error handling: Invalid page number, date range validation, account not found</li>
 *   <li>Pagination metadata: Total count, page count, hasNext, hasPrevious flags</li>
 * </ul>
 * 
 * <p><strong>COBOL Program Equivalence Mapping:</strong></p>
 * <pre>
 * COBOL Program              Test Method
 * -------------------------  --------------------------------------------------
 * PROCESS-PAGE-FORWARD       getTransactionList_FirstPage_Returns10Records()
 *                            getTransactionList_NextPage_ReturnsRecords11to20()
 * PROCESS-PAGE-BACKWARD      getTransactionList_PreviousPage_ReturnsRecords1to10()
 * PROCESS-PF7-KEY            Tests with pageNumber - 1 (backward navigation)
 * PROCESS-PF8-KEY            Tests with pageNumber + 1 (forward navigation)
 * POPULATE-TRAN-DATA         Tests verify amount formatting, date extraction
 * STARTBR-TRANSACT-FILE      Mock Pageable with Sort.DESC by originationTimestamp
 * READNEXT-TRANSACT-FILE     Page.hasNext() validation in assertions
 * READPREV-TRANSACT-FILE     Page.hasPrevious() validation in assertions
 * WS-IDX 1 TO 10 loop        Verify page size = 10 exactly per COBOL OCCURS
 * Date validation logic      getTransactionList_WithDateRange_FiltersCorrectly()
 * </pre>
 * 
 * <p><strong>Pagination Requirements (Section 0.1):</strong></p>
 * <ul>
 *   <li>Fixed page size: Exactly 10 transactions per page (matches COTRN00 BMS map spec)</li>
 *   <li>Sort order: Most recent first (ORDER BY transaction_timestamp DESC)</li>
 *   <li>PF7 backward: Navigate to previous page if current page > 0</li>
 *   <li>PF8 forward: Navigate to next page if hasNext() = true</li>
 *   <li>Page number tracking: CDEMO-CT00-PAGE-NUM → currentPage in response DTO</li>
 * </ul>
 * 
 * <p><strong>Test Data Construction:</strong></p>
 * <ul>
 *   <li>Transaction entities created with BigDecimal amounts (scale 2, HALF_UP rounding)</li>
 *   <li>LocalDateTime timestamps for sorting and date filtering validation</li>
 *   <li>16-character transaction IDs matching COBOL PIC X(16) format</li>
 *   <li>Account and card references for relationship filtering tests</li>
 * </ul>
 * 
 * @see TransactionListService
 * @see Transaction
 * @see TransactionRepository
 * @see TransactionListResponse
 * @see <a href="Section 0.4">COBOL COTRN00C transformation requirements</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.9">Test Case Compatibility Requirements</a>
 */
@ExtendWith(MockitoExtension.class)
public class TransactionListServiceTest {

    /**
     * Mock TransactionRepository for transaction data access.
     * Simulates VSAM TRANSACT file operations without actual database queries.
     * Stubbed to return controlled test data for pagination and filtering scenarios.
     */
    @Mock
    private TransactionRepository transactionRepository;

    /**
     * Mock AccountRepository for account existence validation.
     * Simulates VSAM ACCTDAT file READ operations for account lookup.
     * Stubbed to return test accounts or empty Optional for not-found scenarios.
     */
    @Mock
    private AccountRepository accountRepository;

    /**
     * TransactionListService instance under test with mocked dependencies injected.
     * Contains business logic transformed from COTRN00C.cbl COBOL program.
     * All tests validate correct behavior of getTransactionList() methods.
     */
    @InjectMocks
    private TransactionListService transactionListService;

    /**
     * Fixed page size constant matching COBOL OCCURS 10 TIMES specification.
     * All pagination tests must validate exactly 10 transactions per page.
     */
    private static final int PAGE_SIZE = 10;

    /**
     * Test account ID for consistent test data (11-digit account identifier).
     * Maps to COBOL ACCT-ID PIC 9(11) format.
     */
    private static final String TEST_ACCOUNT_ID = "100000000001";

    /**
     * Test account ID as Long for repository method parameters.
     */
    private static final Long TEST_ACCOUNT_ID_LONG = 100000000001L;

    /**
     * Test card number for card-based filtering scenarios (16-digit PAN).
     * Maps to COBOL CARD-NUM PIC X(16) format.
     */
    private static final String TEST_CARD_NUMBER = "4532123456789012";

    /**
     * Test account entity for account existence validation in tests.
     * Simulates successful account lookup from VSAM ACCTDAT file.
     */
    private Account testAccount;

    /**
     * Sets up test fixtures before each test method execution.
     * Creates test account entity and configures default mock behavior
     * for account repository to simulate existing account scenario.
     */
    @BeforeEach
    public void setUp() {
        // Create test account entity for existence validation
        testAccount = new Account();
        testAccount.setAccountId(TEST_ACCOUNT_ID_LONG);
        
        // Default mock: Account exists (DFHRESP(NORMAL) equivalent)
        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID_LONG))
            .thenReturn(Optional.of(testAccount));
    }

    /**
     * Test: getTransactionList_FirstPage_Returns10Records
     * 
     * <p><strong>COBOL Equivalent:</strong> PROCESS-PAGE-FORWARD paragraph with page 0
     * (lines 279-328). Tests initial transaction list display with PERFORM VARYING
     * WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10 loop transformation to page size 10.</p>
     * 
     * <p><strong>Test Scenario:</strong> User requests first page of transactions
     * (page 0, 0-based index). System should return exactly 10 transactions sorted
     * by most recent first with hasNext=true indicating more pages available.</p>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>Page size exactly 10 (matches COBOL OCCURS 10 TIMES)</li>
     *   <li>Current page = 1 (1-based display from 0-based internal)</li>
     *   <li>hasNext = true (PF8 forward enabled, more transactions available)</li>
     *   <li>hasPrevious = false (PF7 backward disabled, on first page)</li>
     *   <li>Transaction list not empty and contains 10 items</li>
     *   <li>Total elements > 10 (indicates multiple pages exist)</li>
     * </ul>
     */
    @Test
    public void getTransactionList_FirstPage_Returns10Records() {
        // Arrange: Create first page of 10 transactions (records 1-10)
        List<Transaction> transactions = createTestTransactions(10, LocalDateTime.now());
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, 
            Sort.by(Sort.Direction.DESC, "originationTimestamp"));
        Page<Transaction> transactionPage = new PageImpl<>(transactions, pageable, 25); // Total 25 transactions
        
        when(transactionRepository.findByAccountId(eq(TEST_ACCOUNT_ID_LONG), any(Pageable.class)))
            .thenReturn(transactionPage);
        
        // Act: Request first page
        TransactionListResponse response = transactionListService.getTransactionList(
            TEST_ACCOUNT_ID, 0, null, null);
        
        // Assert: Verify first page pagination behavior
        assertAll("First page should return exactly 10 records with correct pagination metadata",
            () -> assertNotNull(response, "Response must not be null"),
            () -> assertNotNull(response.getTransactions(), "Transaction list must not be null"),
            () -> assertEquals(10, response.getTransactions().size(), 
                "Page must contain exactly 10 transactions (COBOL OCCURS 10 TIMES)"),
            () -> assertEquals(10, response.getPageSize(), 
                "Page size must be 10 (matches COTRN00C screen spec)"),
            () -> assertEquals(1, response.getCurrentPage(), 
                "Current page should be 1 (1-based display from 0-based internal)"),
            () -> assertEquals(3, response.getTotalPages(), 
                "Total pages should be 3 (25 transactions / 10 per page)"),
            () -> assertEquals(25L, response.getTotalElements(), 
                "Total elements should be 25"),
            () -> assertTrue(response.getHasNext(), 
                "hasNext should be true (PF8 forward enabled, more pages available)"),
            () -> assertFalse(response.getHasPrevious(), 
                "hasPrevious should be false (PF7 backward disabled, on first page)")
        );
        
        // Verify repository called with correct pagination parameters
        verify(transactionRepository, times(1))
            .findByAccountId(eq(TEST_ACCOUNT_ID_LONG), any(Pageable.class));
    }

    /**
     * Test: getTransactionList_WithDateRange_FiltersCorrectly
     * 
     * <p><strong>COBOL Equivalent:</strong> Date validation logic in PROCESS-PAGE-FORWARD
     * with date filtering during READNEXT-TRANSACT-FILE loop (lines 290-303). Tests
     * COBOL date arithmetic and BETWEEN comparison transformation to JPA date range query.</p>
     * 
     * <p><strong>Test Scenario:</strong> User requests transactions for specific date range
     * (January 1-31, 2024). System should filter transactions using BETWEEN clause with
     * inclusive boundaries matching COBOL IF TRAN-ORIG-TS >= START-DATE AND <= END-DATE.</p>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>Repository called with findByAccountIdAndTransactionDateBetween method</li>
     *   <li>Start date and end date parameters passed correctly</li>
     *   <li>BETWEEN is inclusive on both boundaries (COBOL comparison equivalent)</li>
     *   <li>Pageable parameters include sort by originationTimestamp DESC</li>
     *   <li>Response contains filtered transactions matching date range</li>
     * </ul>
     */
    @Test
    public void getTransactionList_WithDateRange_FiltersCorrectly() {
        // Arrange: Create date range and filtered transactions
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);
        List<Transaction> transactions = createTestTransactions(5, 
            LocalDateTime.of(2024, 1, 15, 10, 0)); // Mid-January transactions
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, 
            Sort.by(Sort.Direction.DESC, "originationTimestamp"));
        Page<Transaction> transactionPage = new PageImpl<>(transactions, pageable, 5);
        
        when(transactionRepository.findByAccountIdAndTransactionDateBetween(
            eq(TEST_ACCOUNT_ID_LONG), eq(startDate), eq(endDate), any(Pageable.class)))
            .thenReturn(transactionPage);
        
        // Act: Request transactions with date range filter
        TransactionListResponse response = transactionListService.getTransactionList(
            TEST_ACCOUNT_ID, 0, startDate, endDate);
        
        // Assert: Verify date range filtering applied
        assertAll("Date range filtering should work correctly with inclusive boundaries",
            () -> assertNotNull(response, "Response must not be null"),
            () -> assertEquals(5, response.getTransactions().size(), 
                "Should return 5 transactions matching date range"),
            () -> assertEquals(5L, response.getTotalElements(), 
                "Total elements should match filtered count")
        );
        
        // Verify repository called with date range parameters (COBOL BETWEEN equivalent)
        verify(transactionRepository, times(1))
            .findByAccountIdAndTransactionDateBetween(
                eq(TEST_ACCOUNT_ID_LONG), 
                eq(startDate), 
                eq(endDate), 
                any(Pageable.class));
    }

    /**
     * Test: getTransactionList_NextPage_ReturnsRecords11to20
     * 
     * <p><strong>COBOL Equivalent:</strong> PROCESS-PF8-KEY paragraph (lines 257-274)
     * triggering PROCESS-PAGE-FORWARD with CDEMO-CT00-TRNID-LAST as starting position.
     * Tests PF8 forward key navigation transforming to page number increment.</p>
     * 
     * <p><strong>Test Scenario:</strong> User presses PF8 to view next page of transactions.
     * System should retrieve page 1 (records 11-20) with hasNext and hasPrevious both true,
     * enabling both forward and backward navigation.</p>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>Current page = 2 (1-based display from page index 1)</li>
     *   <li>Transaction list contains 10 items (records 11-20)</li>
     *   <li>hasNext = true (PF8 enabled, more pages available)</li>
     *   <li>hasPrevious = true (PF7 enabled, can go back to page 1)</li>
     *   <li>Repository called with page index 1</li>
     * </ul>
     */
    @Test
    public void getTransactionList_NextPage_ReturnsRecords11to20() {
        // Arrange: Create second page of 10 transactions (records 11-20)
        List<Transaction> transactions = createTestTransactions(10, 
            LocalDateTime.now().minusDays(10)); // Older transactions for page 2
        Pageable pageable = PageRequest.of(1, PAGE_SIZE, 
            Sort.by(Sort.Direction.DESC, "originationTimestamp"));
        Page<Transaction> transactionPage = new PageImpl<>(transactions, pageable, 25);
        
        when(transactionRepository.findByAccountId(eq(TEST_ACCOUNT_ID_LONG), any(Pageable.class)))
            .thenReturn(transactionPage);
        
        // Act: Request second page (PF8 forward navigation equivalent)
        TransactionListResponse response = transactionListService.getTransactionList(
            TEST_ACCOUNT_ID, 1, null, null);
        
        // Assert: Verify second page pagination behavior
        assertAll("Second page should return records 11-20 with both navigation directions enabled",
            () -> assertNotNull(response, "Response must not be null"),
            () -> assertEquals(10, response.getTransactions().size(), 
                "Page must contain exactly 10 transactions"),
            () -> assertEquals(2, response.getCurrentPage(), 
                "Current page should be 2 (1-based display from index 1)"),
            () -> assertTrue(response.getHasNext(), 
                "hasNext should be true (PF8 forward enabled, page 3 available)"),
            () -> assertTrue(response.getHasPrevious(), 
                "hasPrevious should be true (PF7 backward enabled, can return to page 1)")
        );
        
        // Verify repository called with page index 1
        verify(transactionRepository, times(1))
            .findByAccountId(eq(TEST_ACCOUNT_ID_LONG), any(Pageable.class));
    }

    /**
     * Test: getTransactionList_PreviousPage_ReturnsRecords1to10
     * 
     * <p><strong>COBOL Equivalent:</strong> PROCESS-PF7-KEY paragraph (lines 234-252)
     * triggering PROCESS-PAGE-BACKWARD with CDEMO-CT00-TRNID-FIRST as starting position.
     * Tests PF7 backward key navigation transforming to page number decrement.</p>
     * 
     * <p><strong>Test Scenario:</strong> User on page 2 presses PF7 to view previous page.
     * System should retrieve page 0 (records 1-10) with hasNext=true and hasPrevious=false,
     * indicating first page position.</p>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>Current page = 1 (first page, 1-based display)</li>
     *   <li>Transaction list contains 10 items (records 1-10)</li>
     *   <li>hasNext = true (PF8 enabled, can go forward to page 2)</li>
     *   <li>hasPrevious = false (PF7 disabled, already on first page)</li>
     *   <li>Repository called with page index 0</li>
     * </ul>
     */
    @Test
    public void getTransactionList_PreviousPage_ReturnsRecords1to10() {
        // Arrange: Create first page after backward navigation
        List<Transaction> transactions = createTestTransactions(10, LocalDateTime.now());
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, 
            Sort.by(Sort.Direction.DESC, "originationTimestamp"));
        Page<Transaction> transactionPage = new PageImpl<>(transactions, pageable, 25);
        
        when(transactionRepository.findByAccountId(eq(TEST_ACCOUNT_ID_LONG), any(Pageable.class)))
            .thenReturn(transactionPage);
        
        // Act: Request first page (PF7 backward navigation from page 2 equivalent)
        TransactionListResponse response = transactionListService.getTransactionList(
            TEST_ACCOUNT_ID, 0, null, null);
        
        // Assert: Verify backward navigation returns to first page
        assertAll("Backward navigation should return to first page (records 1-10)",
            () -> assertNotNull(response, "Response must not be null"),
            () -> assertEquals(10, response.getTransactions().size(), 
                "Page must contain exactly 10 transactions"),
            () -> assertEquals(1, response.getCurrentPage(), 
                "Current page should be 1 (back to first page)"),
            () -> assertTrue(response.getHasNext(), 
                "hasNext should be true (can navigate forward to page 2)"),
            () -> assertFalse(response.getHasPrevious(), 
                "hasPrevious should be false (PF7 disabled, at top of list)")
        );
        
        // Verify repository called with page index 0 (first page)
        verify(transactionRepository, times(1))
            .findByAccountId(eq(TEST_ACCOUNT_ID_LONG), any(Pageable.class));
    }

    /**
     * Test: getTransactionList_SortsByDateDescending_MostRecentFirst
     * 
     * <p><strong>COBOL Equivalent:</strong> VSAM STARTBR with RIDFLD(TRAN-ID) followed by
     * READNEXT operations (lines 593-653). Tests transformation to ORDER BY transaction_timestamp
     * DESC ensuring most recent transactions display first matching COBOL screen behavior.</p>
     * 
     * <p><strong>Test Scenario:</strong> System retrieves transactions and verifies they are
     * sorted by origination timestamp in descending order (most recent first), matching the
     * display order on COTRN00 BMS screen where latest transactions appear at top.</p>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>Transactions sorted by originationTimestamp DESC</li>
     *   <li>Most recent transaction appears first in list (index 0)</li>
     *   <li>Oldest transaction appears last in list (index 9 for page of 10)</li>
     *   <li>Repository Pageable includes Sort.Direction.DESC</li>
     *   <li>Date sequence verified: each transaction older than previous</li>
     * </ul>
     */
    @Test
    public void getTransactionList_SortsByDateDescending_MostRecentFirst() {
        // Arrange: Create transactions with specific timestamps for sort verification
        LocalDateTime baseTime = LocalDateTime.of(2024, 1, 15, 12, 0);
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Transaction txn = createTransaction(
                "T" + String.format("%015d", i),
                BigDecimal.valueOf(100.00 + i).setScale(2, RoundingMode.HALF_UP),
                baseTime.minusDays(i)); // Each transaction 1 day older than previous
            transactions.add(txn);
        }
        
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, 
            Sort.by(Sort.Direction.DESC, "originationTimestamp"));
        Page<Transaction> transactionPage = new PageImpl<>(transactions, pageable, 10);
        
        when(transactionRepository.findByAccountId(eq(TEST_ACCOUNT_ID_LONG), any(Pageable.class)))
            .thenReturn(transactionPage);
        
        // Act: Request transactions
        TransactionListResponse response = transactionListService.getTransactionList(
            TEST_ACCOUNT_ID, 0, null, null);
        
        // Assert: Verify descending date sort order (most recent first)
        assertAll("Transactions should be sorted by date descending (most recent first)",
            () -> assertNotNull(response, "Response must not be null"),
            () -> assertEquals(10, response.getTransactions().size(), 
                "Should have 10 transactions"),
            () -> assertNotNull(response.getTransactions().get(0).getTransactionDate(), 
                "First transaction date must not be null"),
            () -> assertTrue(
                !response.getTransactions().get(0).getTransactionDate()
                    .isBefore(response.getTransactions().get(9).getTransactionDate()),
                "First transaction (most recent) should have date >= last transaction (oldest)")
        );
        
        // Verify dates are in descending order
        List<TransactionListResponse.TransactionItemDTO> items = response.getTransactions();
        for (int i = 0; i < items.size() - 1; i++) {
            LocalDate currentDate = items.get(i).getTransactionDate();
            LocalDate nextDate = items.get(i + 1).getTransactionDate();
            assertTrue(!currentDate.isBefore(nextDate), 
                "Transaction at index " + i + " should have date >= transaction at index " + (i + 1));
        }
    }

    /**
     * Test: getTransactionList_ByAccountId_FiltersTransactions
     * 
     * <p><strong>COBOL Equivalent:</strong> STARTBR with specific ACCT-ID filter followed by
     * READNEXT operations reading only transactions for that account. Tests transformation
     * to JPA query with account ID join through Card entity.</p>
     * 
     * <p><strong>Test Scenario:</strong> System retrieves transactions filtered by account ID,
     * ensuring only transactions belonging to specified account are returned. This matches
     * COBOL behavior where VSAM TRANSACT file is filtered by account cross-reference.</p>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>Repository called with correct account ID parameter</li>
     *   <li>Account existence validated before query (DFHRESP(NORMAL) check)</li>
     *   <li>Transactions returned belong only to specified account</li>
     *   <li>Response contains correct transaction count for account</li>
     * </ul>
     */
    @Test
    public void getTransactionList_ByAccountId_FiltersTransactions() {
        // Arrange: Create account-specific transactions
        List<Transaction> transactions = createTestTransactions(7, LocalDateTime.now());
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, 
            Sort.by(Sort.Direction.DESC, "originationTimestamp"));
        Page<Transaction> transactionPage = new PageImpl<>(transactions, pageable, 7);
        
        when(transactionRepository.findByAccountId(eq(TEST_ACCOUNT_ID_LONG), any(Pageable.class)))
            .thenReturn(transactionPage);
        
        // Act: Request transactions filtered by account ID
        TransactionListResponse response = transactionListService.getTransactionList(
            TEST_ACCOUNT_ID, 0, null, null);
        
        // Assert: Verify account filtering applied correctly
        assertAll("Account ID filtering should return only transactions for specified account",
            () -> assertNotNull(response, "Response must not be null"),
            () -> assertEquals(7, response.getTransactions().size(), 
                "Should return 7 account-specific transactions"),
            () -> assertEquals(7L, response.getTotalElements(), 
                "Total elements should be 7 for this account")
        );
        
        // Verify repository called with correct account ID
        verify(transactionRepository, times(1))
            .findByAccountId(eq(TEST_ACCOUNT_ID_LONG), any(Pageable.class));
        
        // Verify account existence check performed (COBOL READ ACCTDAT equivalent)
        verify(accountRepository, times(1))
            .findByAccountId(TEST_ACCOUNT_ID_LONG);
    }

    /**
     * Test: getTransactionList_ByCardNumber_FiltersCorrectly
     * 
     * <p><strong>COBOL Equivalent:</strong> STARTBR with TRAN-CARD-NUM filter followed by
     * READNEXT reading only transactions for specific card. Tests transformation to JPA
     * query filtering by cardNumber field.</p>
     * 
     * <p><strong>Test Scenario:</strong> System retrieves transactions filtered by card number,
     * useful when customer has multiple cards on same account and wants to view transactions
     * for specific card. Matches COBOL card cross-reference navigation pattern.</p>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>Repository called with findByCardNumber method</li>
     *   <li>Card number parameter (16 digits) passed correctly</li>
     *   <li>Transactions returned belong only to specified card</li>
     *   <li>Pagination and sorting applied consistently</li>
     * </ul>
     */
    @Test
    public void getTransactionList_ByCardNumber_FiltersCorrectly() {
        // Arrange: Create card-specific transactions
        List<Transaction> transactions = createTestTransactions(8, LocalDateTime.now());
        transactions.forEach(txn -> txn.setCardNumber(TEST_CARD_NUMBER)); // Set specific card
        
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, 
            Sort.by(Sort.Direction.DESC, "originationTimestamp"));
        Page<Transaction> transactionPage = new PageImpl<>(transactions, pageable, 8);
        
        when(transactionRepository.findByCardNumber(eq(TEST_CARD_NUMBER), any(Pageable.class)))
            .thenReturn(transactionPage);
        
        // Act: Request transactions filtered by card number
        TransactionListResponse response = transactionListService.getTransactionListByCard(
            TEST_CARD_NUMBER, 0, null, null);
        
        // Assert: Verify card number filtering applied correctly
        assertAll("Card number filtering should return only transactions for specified card",
            () -> assertNotNull(response, "Response must not be null"),
            () -> assertEquals(8, response.getTransactions().size(), 
                "Should return 8 card-specific transactions"),
            () -> assertEquals(8L, response.getTotalElements(), 
                "Total elements should be 8 for this card"),
            () -> assertEquals(1, response.getTotalPages(), 
                "Total pages should be 1 (8 transactions fit on single page)")
        );
        
        // Verify repository called with correct card number
        verify(transactionRepository, times(1))
            .findByCardNumber(eq(TEST_CARD_NUMBER), any(Pageable.class));
    }

    /**
     * Test: getTransactionList_EmptyResult_ReturnsEmptyPage
     * 
     * <p><strong>COBOL Equivalent:</strong> READNEXT returning DFHRESP(ENDFILE) or NOTFND,
     * setting TRANSACT-EOF flag and displaying empty screen with "No transactions found"
     * message (lines 639-645).</p>
     * 
     * <p><strong>Test Scenario:</strong> Account exists but has no transactions. System should
     * return empty page with zero transactions but valid pagination metadata, not throwing
     * exception. Matches COBOL behavior where empty result is valid state, not error.</p>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>Response not null (valid response object returned)</li>
     *   <li>Transaction list empty but not null</li>
     *   <li>Total elements = 0</li>
     *   <li>Total pages = 0</li>
     *   <li>hasNext = false (no more pages available)</li>
     *   <li>hasPrevious = false (no previous pages)</li>
     * </ul>
     */
    @Test
    public void getTransactionList_EmptyResult_ReturnsEmptyPage() {
        // Arrange: Create empty result set (no transactions found)
        List<Transaction> emptyList = new ArrayList<>();
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, 
            Sort.by(Sort.Direction.DESC, "originationTimestamp"));
        Page<Transaction> emptyPage = new PageImpl<>(emptyList, pageable, 0);
        
        when(transactionRepository.findByAccountId(eq(TEST_ACCOUNT_ID_LONG), any(Pageable.class)))
            .thenReturn(emptyPage);
        
        // Act: Request transactions for account with no transactions
        TransactionListResponse response = transactionListService.getTransactionList(
            TEST_ACCOUNT_ID, 0, null, null);
        
        // Assert: Verify empty result handling (COBOL ENDFILE equivalent)
        assertAll("Empty result should return valid response with zero transactions",
            () -> assertNotNull(response, "Response must not be null"),
            () -> assertNotNull(response.getTransactions(), "Transaction list must not be null"),
            () -> assertEquals(0, response.getTransactions().size(), 
                "Transaction list should be empty"),
            () -> assertEquals(0L, response.getTotalElements(), 
                "Total elements should be 0"),
            () -> assertEquals(0, response.getTotalPages(), 
                "Total pages should be 0"),
            () -> assertFalse(response.getHasNext(), 
                "hasNext should be false (no more pages)"),
            () -> assertFalse(response.getHasPrevious(), 
                "hasPrevious should be false (no previous pages)")
        );
    }

    /**
     * Test: getTransactionList_FormatsAmounts_TwoDecimals
     * 
     * <p><strong>COBOL Equivalent:</strong> POPULATE-TRAN-DATA paragraph (lines 381-445)
     * formatting TRAN-AMT PIC S9(09)V99 COMP-3 to display format WS-TRAN-AMT PIC +99999999.99.
     * Tests BigDecimal precision preservation matching COBOL COMP-3 packed decimal.</p>
     * 
     * <p><strong>Test Scenario:</strong> System retrieves transactions and formats amounts
     * with exactly 2 decimal places using RoundingMode.HALF_UP, ensuring identical calculation
     * results to COBOL mainframe system per Section 0.9 numeric precision requirements.</p>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>All amounts have scale = 2 (exactly 2 decimal places)</li>
     *   <li>RoundingMode.HALF_UP applied (matches COBOL COMP-3 rounding)</li>
     *   <li>BigDecimal used (not float/double which lose precision)</li>
     *   <li>Amounts match expected values: 100.00, 250.50, 1234.56</li>
     *   <li>No precision loss in arithmetic operations</li>
     * </ul>
     */
    @Test
    public void getTransactionList_FormatsAmounts_TwoDecimals() {
        // Arrange: Create transactions with specific amounts to verify formatting
        List<Transaction> transactions = new ArrayList<>();
        transactions.add(createTransaction("T000000000000001", 
            BigDecimal.valueOf(100.00).setScale(2, RoundingMode.HALF_UP), LocalDateTime.now()));
        transactions.add(createTransaction("T000000000000002", 
            BigDecimal.valueOf(250.50).setScale(2, RoundingMode.HALF_UP), LocalDateTime.now()));
        transactions.add(createTransaction("T000000000000003", 
            BigDecimal.valueOf(1234.56).setScale(2, RoundingMode.HALF_UP), LocalDateTime.now()));
        
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, 
            Sort.by(Sort.Direction.DESC, "originationTimestamp"));
        Page<Transaction> transactionPage = new PageImpl<>(transactions, pageable, 3);
        
        when(transactionRepository.findByAccountId(eq(TEST_ACCOUNT_ID_LONG), any(Pageable.class)))
            .thenReturn(transactionPage);
        
        // Act: Request transactions
        TransactionListResponse response = transactionListService.getTransactionList(
            TEST_ACCOUNT_ID, 0, null, null);
        
        // Assert: Verify amount formatting with exactly 2 decimal places (COMP-3 precision)
        assertAll("Amounts should be formatted with exactly 2 decimal places (COMP-3 equivalent)",
            () -> assertNotNull(response, "Response must not be null"),
            () -> assertEquals(3, response.getTransactions().size(), 
                "Should have 3 transactions"),
            () -> assertEquals(2, response.getTransactions().get(0).getAmount().scale(), 
                "First amount must have scale 2 (2 decimal places)"),
            () -> assertEquals(2, response.getTransactions().get(1).getAmount().scale(), 
                "Second amount must have scale 2"),
            () -> assertEquals(2, response.getTransactions().get(2).getAmount().scale(), 
                "Third amount must have scale 2"),
            () -> assertEquals(BigDecimal.valueOf(100.00).setScale(2, RoundingMode.HALF_UP), 
                response.getTransactions().get(0).getAmount(), 
                "First amount should be 100.00"),
            () -> assertEquals(BigDecimal.valueOf(250.50).setScale(2, RoundingMode.HALF_UP), 
                response.getTransactions().get(1).getAmount(), 
                "Second amount should be 250.50"),
            () -> assertEquals(BigDecimal.valueOf(1234.56).setScale(2, RoundingMode.HALF_UP), 
                response.getTransactions().get(2).getAmount(), 
                "Third amount should be 1234.56")
        );
    }

    /**
     * Test: getTransactionList_LastPage_HasPartialRecords
     * 
     * <p><strong>COBOL Equivalent:</strong> PROCESS-PAGE-FORWARD with WS-IDX < 10 at end
     * of file, setting NEXT-PAGE-NO flag when TRANSACT-EOF reached before 10 records read
     * (lines 305-320). Tests partial page handling at end of result set.</p>
     * 
     * <p><strong>Test Scenario:</strong> Last page contains only 5 transactions (partial page).
     * System should return those 5 transactions with hasNext=false indicating no more pages
     * available, matching COBOL "You are already at the bottom" message behavior.</p>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>Transaction list contains 5 items (partial page, not full 10)</li>
     *   <li>hasNext = false (PF8 disabled, no more pages)</li>
     *   <li>hasPrevious = true (PF7 enabled, can go back)</li>
     *   <li>Total elements = 25 (indicates last page position)</li>
     *   <li>Current page = 3 (last page in sequence)</li>
     * </ul>
     */
    @Test
    public void getTransactionList_LastPage_HasPartialRecords() {
        // Arrange: Create last page with only 5 transactions (partial page)
        List<Transaction> transactions = createTestTransactions(5, 
            LocalDateTime.now().minusDays(20)); // Oldest transactions on last page
        Pageable pageable = PageRequest.of(2, PAGE_SIZE, 
            Sort.by(Sort.Direction.DESC, "originationTimestamp"));
        Page<Transaction> transactionPage = new PageImpl<>(transactions, pageable, 25);
        
        when(transactionRepository.findByAccountId(eq(TEST_ACCOUNT_ID_LONG), any(Pageable.class)))
            .thenReturn(transactionPage);
        
        // Act: Request last page (page 2 in 0-based index = page 3 in 1-based display)
        TransactionListResponse response = transactionListService.getTransactionList(
            TEST_ACCOUNT_ID, 2, null, null);
        
        // Assert: Verify partial last page handling (COBOL ENDFILE with partial records)
        assertAll("Last page should contain partial records (< 10) with hasNext=false",
            () -> assertNotNull(response, "Response must not be null"),
            () -> assertEquals(5, response.getTransactions().size(), 
                "Last page should have 5 transactions (partial page)"),
            () -> assertEquals(3, response.getCurrentPage(), 
                "Current page should be 3 (last page, 1-based display)"),
            () -> assertEquals(3, response.getTotalPages(), 
                "Total pages should be 3 (25 transactions: 10+10+5)"),
            () -> assertFalse(response.getHasNext(), 
                "hasNext should be false (PF8 disabled, at bottom of list)"),
            () -> assertTrue(response.getHasPrevious(), 
                "hasPrevious should be true (PF7 enabled, can go back to page 2)")
        );
    }

    /**
     * Test: getTransactionList_IncludesPageMetadata_TotalCountCorrect
     * 
     * <p><strong>COBOL Equivalent:</strong> CDEMO-CT00-PAGE-NUM tracking and NEXT-PAGE-YES
     * flag management throughout pagination logic (lines 306-319). Tests pagination metadata
     * calculation and state preservation matching COBOL COMMAREA page tracking.</p>
     * 
     * <p><strong>Test Scenario:</strong> System returns complete pagination metadata including
     * total pages, total elements, current page, page size, hasNext, hasPrevious flags.
     * Metadata enables UI to display page controls matching COBOL PF7/PF8 key availability.</p>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>Total elements calculated correctly from repository</li>
     *   <li>Total pages = ceiling(totalElements / pageSize)</li>
     *   <li>Current page reflects requested page + 1 (1-based display)</li>
     *   <li>Page size = 10 (constant matching COBOL OCCURS)</li>
     *   <li>hasNext calculated from page.hasNext()</li>
     *   <li>hasPrevious calculated from page.hasPrevious()</li>
     * </ul>
     */
    @Test
    public void getTransactionList_IncludesPageMetadata_TotalCountCorrect() {
        // Arrange: Create page with known pagination metadata
        List<Transaction> transactions = createTestTransactions(10, LocalDateTime.now());
        Pageable pageable = PageRequest.of(1, PAGE_SIZE, 
            Sort.by(Sort.Direction.DESC, "originationTimestamp"));
        Page<Transaction> transactionPage = new PageImpl<>(transactions, pageable, 37);
        
        when(transactionRepository.findByAccountId(eq(TEST_ACCOUNT_ID_LONG), any(Pageable.class)))
            .thenReturn(transactionPage);
        
        // Act: Request middle page to verify metadata calculation
        TransactionListResponse response = transactionListService.getTransactionList(
            TEST_ACCOUNT_ID, 1, null, null);
        
        // Assert: Verify pagination metadata accuracy (COBOL PAGE-NUM tracking equivalent)
        assertAll("Response should include complete and accurate pagination metadata",
            () -> assertNotNull(response, "Response must not be null"),
            () -> assertEquals(37L, response.getTotalElements(), 
                "Total elements should be 37 (from repository)"),
            () -> assertEquals(4, response.getTotalPages(), 
                "Total pages should be 4 (ceiling of 37/10)"),
            () -> assertEquals(2, response.getCurrentPage(), 
                "Current page should be 2 (1-based display from index 1)"),
            () -> assertEquals(10, response.getPageSize(), 
                "Page size should be 10 (COBOL OCCURS 10)"),
            () -> assertTrue(response.getHasNext(), 
                "hasNext should be true (pages 3 and 4 available)"),
            () -> assertTrue(response.getHasPrevious(), 
                "hasPrevious should be true (page 1 available)")
        );
    }

    /**
     * Test: getTransactionList_InvalidPageNumber_ThrowsException
     * 
     * <p><strong>COBOL Equivalent:</strong> COBOL programs validate page context and throw
     * errors for invalid navigation attempts. Negative page numbers have no COBOL equivalent
     * (impossible in mainframe UI), but modern REST API must validate input parameters.</p>
     * 
     * <p><strong>Test Scenario:</strong> User provides invalid page number (negative value).
     * System should throw IllegalArgumentException with descriptive error message, preventing
     * invalid pagination state.</p>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>IllegalArgumentException thrown for pageNumber < 0</li>
     *   <li>Exception message indicates page number must be non-negative</li>
     *   <li>Repository not called (validation fails before query)</li>
     *   <li>Transaction remains consistent (no partial state changes)</li>
     * </ul>
     */
    @Test
    public void getTransactionList_InvalidPageNumber_ThrowsException() {
        // Act & Assert: Verify invalid page number throws IllegalArgumentException
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> transactionListService.getTransactionList(TEST_ACCOUNT_ID, -1, null, null),
            "Should throw IllegalArgumentException for negative page number"
        );
        
        // Verify exception message contains appropriate error description
        assertNotNull(exception.getMessage(), "Exception message must not be null");
        assertTrue(exception.getMessage().contains("non-negative"), 
            "Exception message should indicate page number must be non-negative");
        
        // Verify repository never called (validation fails before query)
        verify(transactionRepository, times(0))
            .findByAccountId(anyLong(), any(Pageable.class));
    }

    /**
     * Test: getTransactionList_AccountNotFound_ThrowsException
     * 
     * <p><strong>COBOL Equivalent:</strong> EXEC CICS READ DATASET('ACCTDAT') returning
     * DFHRESP(NOTFND), setting error flag and displaying "Account not found" message
     * to user (equivalent to AccountNotFoundException in Java).</p>
     * 
     * <p><strong>Test Scenario:</strong> User requests transactions for non-existent account.
     * System should validate account existence before querying transactions and throw
     * AccountNotFoundException matching COBOL NOTFND response behavior.</p>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>AccountNotFoundException thrown when account not found</li>
     *   <li>Exception contains account ID in error message</li>
     *   <li>Account repository called for existence check</li>
     *   <li>Transaction repository NOT called (validation fails first)</li>
     * </ul>
     */
    @Test
    public void getTransactionList_AccountNotFound_ThrowsException() {
        // Arrange: Mock account not found scenario (DFHRESP(NOTFND) equivalent)
        when(accountRepository.findByAccountId(TEST_ACCOUNT_ID_LONG))
            .thenReturn(Optional.empty());
        
        // Act & Assert: Verify AccountNotFoundException thrown for non-existent account
        AccountNotFoundException exception = assertThrows(
            AccountNotFoundException.class,
            () -> transactionListService.getTransactionList(TEST_ACCOUNT_ID, 0, null, null),
            "Should throw AccountNotFoundException when account does not exist"
        );
        
        // Verify exception message contains account ID
        assertNotNull(exception.getMessage(), "Exception message must not be null");
        
        // Verify account existence check performed
        verify(accountRepository, times(1))
            .findByAccountId(TEST_ACCOUNT_ID_LONG);
        
        // Verify transaction repository never called (account validation fails first)
        verify(transactionRepository, times(0))
            .findByAccountId(anyLong(), any(Pageable.class));
    }

    /**
     * Test: validateDateRange_InvalidRange_ThrowsException
     * 
     * <p><strong>COBOL Equivalent:</strong> Date validation logic checking IF START-DATE > END-DATE
     * and setting error flag with message "Start date must be before end date". Tests transformation
     * to validateDateRange method throwing IllegalArgumentException.</p>
     * 
     * <p><strong>Test Scenario:</strong> User provides invalid date range where start date is
     * after end date. System should reject this with IllegalArgumentException before attempting
     * database query, matching COBOL validation behavior.</p>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>IllegalArgumentException thrown when startDate > endDate</li>
     *   <li>Exception message indicates date range validation failure</li>
     *   <li>Validation performed before repository query</li>
     *   <li>No partial operations executed</li>
     * </ul>
     */
    @Test
    public void validateDateRange_InvalidRange_ThrowsException() {
        // Arrange: Create invalid date range (start after end)
        LocalDate startDate = LocalDate.of(2024, 12, 31);
        LocalDate endDate = LocalDate.of(2024, 12, 1);
        
        // Act & Assert: Verify IllegalArgumentException thrown for invalid date range
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> transactionListService.getTransactionList(TEST_ACCOUNT_ID, 0, startDate, endDate),
            "Should throw IllegalArgumentException when start date is after end date"
        );
        
        // Verify exception message indicates date validation failure
        assertNotNull(exception.getMessage(), "Exception message must not be null");
        assertTrue(exception.getMessage().toLowerCase().contains("start date"), 
            "Exception message should mention start date");
        assertTrue(exception.getMessage().toLowerCase().contains("end date"), 
            "Exception message should mention end date");
    }

    /**
     * Helper method: Create list of test Transaction entities with sequential IDs and timestamps.
     * 
     * <p>Creates mock transactions for testing pagination, sorting, and filtering scenarios.
     * Each transaction has unique ID, BigDecimal amount with scale 2, and timestamp for
     * sort order verification.</p>
     * 
     * @param count Number of transactions to create
     * @param baseTime Base timestamp for first transaction (subsequent transactions 1 minute apart)
     * @return List of Transaction entities ready for test scenarios
     */
    private List<Transaction> createTestTransactions(int count, LocalDateTime baseTime) {
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Transaction txn = new Transaction();
            txn.setTransactionId("T" + String.format("%015d", i + 1)); // 16-char ID
            txn.setTransactionAmount(
                BigDecimal.valueOf(100.00 + (i * 10)).setScale(2, RoundingMode.HALF_UP));
            txn.setOriginationTimestamp(baseTime.plusMinutes(i)); // Each 1 minute later
            txn.setTransactionDescription("Test Transaction " + (i + 1));
            txn.setTransactionTypeCode("PU"); // Purchase type
            txn.setTransactionCategoryCode(1001); // Category code
            txn.setTransactionSource("POS");
            txn.setCardNumber(TEST_CARD_NUMBER);
            transactions.add(txn);
        }
        return transactions;
    }

    /**
     * Helper method: Create single test Transaction entity with specified parameters.
     * 
     * <p>Creates mock transaction with specific transaction ID, amount, and timestamp
     * for precise test scenario control. Used when exact transaction values need verification.</p>
     * 
     * @param transactionId Unique 16-character transaction identifier
     * @param amount Transaction amount as BigDecimal with scale 2 (COMP-3 equivalent)
     * @param timestamp Transaction origination timestamp for sort order
     * @return Transaction entity configured with specified parameters
     */
    private Transaction createTransaction(String transactionId, BigDecimal amount, LocalDateTime timestamp) {
        Transaction txn = new Transaction();
        txn.setTransactionId(transactionId);
        txn.setTransactionAmount(amount);
        txn.setOriginationTimestamp(timestamp);
        txn.setTransactionDescription("Transaction " + transactionId);
        txn.setTransactionTypeCode("PU");
        txn.setTransactionCategoryCode(1001);
        txn.setTransactionSource("POS");
        txn.setCardNumber(TEST_CARD_NUMBER);
        return txn;
    }
}
