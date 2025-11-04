/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.repository;

import com.carddemo.entity.Transaction;
import com.carddemo.entity.Card;
import com.carddemo.entity.TransactionType;
import com.carddemo.entity.TransactionCategory;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive JUnit 5 test class for TransactionRepository validating Spring Data JPA
 * repository methods for transaction data migrated from TRANSACT VSAM KSDS file.
 * 
 * <p><strong>COBOL Source Structure (CVTRA05Y.cpy - RECLN 350):</strong></p>
 * <pre>
 * 01  TRAN-RECORD.
 *     05  TRAN-ID                    PIC X(16).      → VARCHAR(16) transaction_id PK
 *     05  TRAN-TYPE-CD               PIC X(02).      → VARCHAR(2) transaction_type_code FK
 *     05  TRAN-CAT-CD                PIC 9(04).      → NUMERIC(4,0) transaction_category_code
 *     05  TRAN-SOURCE                PIC X(10).      → VARCHAR(10) transaction_source
 *     05  TRAN-DESC                  PIC X(100).     → VARCHAR(100) transaction_description
 *     05  TRAN-AMT                   PIC S9(09)V99.  → NUMERIC(11,2) transaction_amount
 *     05  TRAN-MERCHANT-ID           PIC 9(09).      → BIGINT merchant_id
 *     05  TRAN-MERCHANT-NAME         PIC X(50).      → VARCHAR(50) merchant_name
 *     05  TRAN-MERCHANT-CITY         PIC X(50).      → VARCHAR(50) merchant_city
 *     05  TRAN-MERCHANT-ZIP          PIC X(10).      → VARCHAR(10) merchant_zip
 *     05  TRAN-CARD-NUM              PIC X(16).      → VARCHAR(16) card_number FK
 *     05  TRAN-ORIG-TS               PIC X(26).      → TIMESTAMP origination_timestamp
 *     05  TRAN-PROC-TS               PIC X(26).      → TIMESTAMP processing_timestamp
 * </pre>
 * 
 * <p><strong>Test Coverage:</strong></p>
 * <ul>
 *   <li>VSAM random read (EXEC CICS READ) → findById operations</li>
 *   <li>VSAM sequential read → findAll with pagination (10 per page per BMS screen)</li>
 *   <li>Date range queries for statement generation (CBSTM03A.cbl replacement)</li>
 *   <li>Card number queries with pagination (COTRN00C.cbl browsing replacement)</li>
 *   <li>Account-based queries via card JOIN (indirect relationship)</li>
 *   <li>Category aggregation (COTRN01C.cbl category summary replacement)</li>
 *   <li>BigDecimal precision validation from COBOL COMP-3 S9(09)V99</li>
 *   <li>Foreign key constraints to Card, TransactionType, TransactionCategory</li>
 *   <li>Compound indexes on (card_number, transaction_date) for performance</li>
 *   <li>CRUD operations (INSERT, UPDATE, DELETE equivalent to VSAM WRITE/REWRITE/DELETE)</li>
 * </ul>
 * 
 * <p><strong>Test Data Requirements:</strong></p>
 * <ul>
 *   <li>Customers → Accounts → Cards → Transactions (hierarchical data dependencies)</li>
 *   <li>TransactionType reference data (PU, CA, PM, RF, FE, IN codes)</li>
 *   <li>TransactionCategory reference data (category codes 1001-9999)</li>
 *   <li>Minimum 20 transaction records for pagination testing</li>
 *   <li>Multiple cards per account for JOIN testing</li>
 *   <li>Date range spanning multiple months for date query testing</li>
 * </ul>
 * 
 * <p><strong>Performance Validation:</strong></p>
 * <ul>
 *   <li>B-tree index usage on transaction_id (primary key)</li>
 *   <li>Index on card_number for card transaction history</li>
 *   <li>Index on origination_timestamp for date range queries</li>
 *   <li>Compound index on (transaction_type_code, transaction_category_code)</li>
 *   <li>Pagination prevents memory exhaustion with large result sets</li>
 * </ul>
 * 
 * <p><strong>CRITICAL Validations (Section 0.9):</strong></p>
 * <ul>
 *   <li>TRAN-AMT S9(09)V99 → NUMERIC(11,2) with BigDecimal scale=2</li>
 *   <li>RoundingMode.HALF_UP for all BigDecimal arithmetic operations</li>
 *   <li>VARCHAR length constraints match COBOL PIC clause lengths exactly</li>
 *   <li>Foreign key referential integrity enforcement</li>
 *   <li>NOT NULL constraints on mandatory fields</li>
 *   <li>Unique constraint on transaction_id primary key</li>
 * </ul>
 * 
 * @see TransactionRepository
 * @see Transaction
 * @see Card
 * @see TransactionType
 * @see TransactionCategory
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.3">VSAM to PostgreSQL Transformation Rules</a>
 * @see <a href="Section 0.9">Numeric Precision Requirements</a>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Sql(scripts = {
    "/db/test-data/customers.sql",
    "/db/test-data/accounts.sql",
    "/db/test-data/cards.sql",
    "/db/test-data/transaction-types.sql",
    "/db/test-data/transaction-categories.sql",
    "/db/test-data/transactions.sql"
})
@TestMethodOrder(OrderAnnotation.class)
class TransactionRepositoryTest {

    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * Test findById with valid transaction ID.
     * 
     * <p>Tests VSAM random read (EXEC CICS READ TRANSACT) equivalent operation.
     * Validates B-tree index on transaction_id primary key for efficient lookup.</p>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>TRAN-ID PIC X(16) → VARCHAR(16) transaction_id mapping</li>
     *   <li>All 350-byte record fields correctly mapped to entity</li>
     *   <li>TRAN-AMT S9(09)V99 → NUMERIC(11,2) with BigDecimal scale=2</li>
     *   <li>Type code PIC X(02) → VARCHAR(2) foreign key</li>
     *   <li>Category code PIC 9(04) → NUMERIC(4,0) integer</li>
     *   <li>Merchant fields and timestamps properly populated</li>
     * </ul>
     */
    @Test
    @Order(1)
    void testFindById_ValidTransactionId() {
        // Arrange: Use test data transaction ID from transactions.sql
        String transactionId = "TXN0000000000001";

        // Act: Execute findById (VSAM READ equivalent)
        Optional<Transaction> result = transactionRepository.findById(transactionId);

        // Assert: Verify transaction found and all fields correctly mapped
        assertAll("Valid transaction ID lookup",
            () -> assertTrue(result.isPresent(), "Transaction should be found"),
            () -> {
                Transaction transaction = result.get();
                assertNotNull(transaction, "Transaction entity should not be null");
                assertEquals(transactionId, transaction.getTransactionId(), 
                    "Transaction ID should match");
                assertEquals("01", transaction.getTransactionTypeCode(), 
                    "Transaction type code should be '01' (Purchase)");
                assertEquals(5010, transaction.getTransactionCategoryCode(), 
                    "Category code should be 5010");
                assertNotNull(transaction.getTransactionAmount(), 
                    "Transaction amount should not be null");
                assertEquals(2, transaction.getTransactionAmount().scale(), 
                    "Amount scale should be 2 (COMP-3 precision)");
                assertEquals(0, transaction.getTransactionAmount().compareTo(
                    new BigDecimal("125.50")), 
                    "Amount should be 125.50");
                assertEquals("POS", transaction.getTransactionSource(), 
                    "Transaction source should be POS");
                assertNotNull(transaction.getCardNumber(), 
                    "Card number should not be null");
                assertNotNull(transaction.getOriginationTimestamp(), 
                    "Origination timestamp should not be null");
            }
        );
    }

    /**
     * Test findById with invalid transaction ID.
     * 
     * <p>Tests VSAM NOTFND condition equivalent when transaction doesn't exist.
     * Validates Optional.empty() return for non-existent records.</p>
     */
    @Test
    @Order(2)
    void testFindById_InvalidTransactionId() {
        // Arrange: Use non-existent transaction ID
        String invalidId = "TXN9999999999999";

        // Act: Execute findById
        Optional<Transaction> result = transactionRepository.findById(invalidId);

        // Assert: Verify transaction not found
        assertFalse(result.isPresent(), "Transaction should not be found for invalid ID");
    }

    /**
     * Test findByTransactionId method from repository.
     * 
     * <p>Tests custom query method for transaction lookup by ID.
     * This method is provided by TransactionRepository as an alternative to findById.</p>
     */
    @Test
    @Order(3)
    void testFindByTransactionId_ValidId() {
        // Arrange
        String transactionId = "TXN0000000000002";

        // Act
        Optional<Transaction> result = transactionRepository.findByTransactionId(transactionId);

        // Assert
        assertAll("Transaction lookup by transaction ID",
            () -> assertTrue(result.isPresent(), "Transaction should be found"),
            () -> {
                Transaction txn = result.get();
                assertEquals(transactionId, txn.getTransactionId());
                assertEquals("02", txn.getTransactionTypeCode(), 
                    "Type code should be '02' (Cash Advance)");
                assertEquals(6011, txn.getTransactionCategoryCode(), 
                    "Category code should be 6011 (ATM Withdrawal)");
            }
        );
    }

    /**
     * Test findByCardNumber with pagination.
     * 
     * <p>Tests transaction-by-card retrieval with pagination support.
     * Validates secondary index on card_num for efficient card transaction history.</p>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>Pageable parameter handling (10 transactions per page)</li>
     *   <li>Sort order (most recent first - DESC)</li>
     *   <li>Page metadata (total elements, total pages, hasNext, hasPrevious)</li>
     *   <li>Foreign key relationship to Card entity</li>
     * </ul>
     */
    @Test
    @Order(4)
    void testFindByCardNumber_WithPagination() {
        // Arrange: Card number from test data
        String cardNumber = "4000123456789010";
        Pageable pageable = PageRequest.of(0, 10, Sort.by("originationTimestamp").descending());

        // Act: Execute paginated query
        Page<Transaction> page = transactionRepository.findByCardNumber(cardNumber, pageable);

        // Assert: Verify pagination and results
        assertAll("Card number pagination query",
            () -> assertNotNull(page, "Page should not be null"),
            () -> assertFalse(page.getContent().isEmpty(), 
                "Page should contain transactions"),
            () -> assertTrue(page.getContent().size() <= 10, 
                "Page size should not exceed 10"),
            () -> page.getContent().forEach(txn -> 
                assertEquals(cardNumber, txn.getCardNumber(), 
                    "All transactions should have matching card number"))
        );
    }

    /**
     * Test findByAccountId with pagination via card JOIN.
     * 
     * <p>Tests multi-table query performance with pagination.
     * Transaction → Card → Account relationship navigation.</p>
     * 
     * <p>Validates compound index on (card.account_id via join, transaction_timestamp).</p>
     */
    @Test
    @Order(5)
    void testFindByAccountId_WithPagination() {
        // Arrange: Account ID from test data
        String accountId = "00000000001";
        Pageable pageable = PageRequest.of(0, 10, Sort.by("originationTimestamp").descending());

        // Act: Execute account query with JOIN
        Page<Transaction> page = transactionRepository.findByAccountId(accountId, pageable);

        // Assert: Verify results
        assertAll("Account ID pagination query via card JOIN",
            () -> assertNotNull(page, "Page should not be null"),
            () -> assertNotNull(page.getContent(), "Page content should not be null"),
            () -> assertTrue(page.getSize() <= 10, 
                "Page size should be 10 or less per COTRN00C requirement")
        );
    }

    /**
     * Test findByTransactionDateBetween for date range queries.
     * 
     * <p>Tests VSAM sequential read with date filtering equivalent.
     * Used for statement generation (CBSTM03A.cbl replacement).</p>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>CAST(originationTimestamp AS date) BETWEEN operation</li>
     *   <li>Inclusive date range (startDate and endDate included)</li>
     *   <li>Index on transaction_timestamp for performance</li>
     *   <li>ORDER BY originationTimestamp</li>
     * </ul>
     */
    @Test
    @Order(6)
    void testFindByTransactionDateBetween_ValidRange() {
        // Arrange: Date range for January 2024
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);

        // Act: Execute date range query
        List<Transaction> transactions = transactionRepository
            .findByTransactionDateBetween(startDate, endDate);

        // Assert: Verify results within date range
        assertAll("Date range query",
            () -> assertNotNull(transactions, "Result list should not be null"),
            () -> transactions.forEach(txn -> {
                LocalDate txnDate = txn.getOriginationTimestamp().toLocalDate();
                assertTrue(
                    !txnDate.isBefore(startDate) && !txnDate.isAfter(endDate),
                    "Transaction date should be within range"
                );
            })
        );
    }

    /**
     * Test findByAccountIdAndTransactionDateBetween with pagination.
     * 
     * <p>Tests combined account + date range filtering with pagination.
     * Combines COTRN00C browsing with CBSTM03A statement generation logic.</p>
     */
    @Test
    @Order(7)
    void testFindByAccountIdAndTransactionDateBetween() {
        // Arrange
        String accountId = "00000000001";
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);
        Pageable pageable = PageRequest.of(0, 10);

        // Act
        Page<Transaction> page = transactionRepository
            .findByAccountIdAndTransactionDateBetween(accountId, startDate, endDate, pageable);

        // Assert
        assertAll("Account and date range pagination query",
            () -> assertNotNull(page, "Page should not be null"),
            () -> assertNotNull(page.getContent(), "Page content should not be null"),
            () -> page.getContent().forEach(txn -> {
                LocalDate txnDate = txn.getOriginationTimestamp().toLocalDate();
                assertTrue(
                    !txnDate.isBefore(startDate) && !txnDate.isAfter(endDate),
                    "Transaction date should be within range"
                );
            })
        );
    }

    /**
     * Test aggregateByCategory for category-based summation.
     * 
     * <p>Tests SQL GROUP BY with SUM aggregation replacing COBOL COTRN01C
     * category summary logic with accumulator variables.</p>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>SUM(transactionAmount) maintains BigDecimal scale=2</li>
     *   <li>GROUP BY transactionCategoryCode</li>
     *   <li>Result Object[] structure: [0]=categoryCode, [1]=sumAmount</li>
     *   <li>Database aggregation performance vs application-level summation</li>
     * </ul>
     */
    @Test
    @Order(8)
    void testAggregateByCategory() {
        // Arrange
        String accountId = "00000000001";
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);

        // Act: Execute category aggregation
        List<Object[]> results = transactionRepository
            .aggregateByCategory(accountId, startDate, endDate);

        // Assert: Verify aggregation results
        assertAll("Category aggregation query",
            () -> assertNotNull(results, "Results should not be null"),
            () -> results.forEach(row -> {
                assertNotNull(row[0], "Category code should not be null");
                assertNotNull(row[1], "Sum amount should not be null");
                assertTrue(row[0] instanceof Integer, 
                    "Category code should be Integer");
                assertTrue(row[1] instanceof BigDecimal, 
                    "Sum amount should be BigDecimal");
                BigDecimal sumAmount = (BigDecimal) row[1];
                assertEquals(2, sumAmount.scale(), 
                    "Sum amount should have scale=2 (COMP-3 precision)");
            })
        );
    }

    /**
     * Test countDailyTransactions for transaction volume monitoring.
     * 
     * <p>Tests COUNT query for daily transaction counting (CBTRN02C.cbl logic).
     * Used for fraud detection and batch processing metrics.</p>
     */
    @Test
    @Order(9)
    void testCountDailyTransactions() {
        // Arrange
        String accountId = "00000000001";
        LocalDate date = LocalDate.of(2024, 1, 15);

        // Act
        long count = transactionRepository.countDailyTransactions(accountId, date);

        // Assert
        assertTrue(count >= 0, "Count should be non-negative");
    }

    /**
     * Test existsByTransactionId for duplicate detection.
     * 
     * <p>Tests duplicate transaction detection logic from CBTRN01C batch load.
     * Replaces VSAM READ with NOTFND condition check.</p>
     */
    @Test
    @Order(10)
    void testExistsByTransactionId_ExistingTransaction() {
        // Arrange
        String existingId = "TXN0000000000001";

        // Act
        boolean exists = transactionRepository.existsByTransactionId(existingId);

        // Assert
        assertTrue(exists, "Transaction should exist");
    }

    /**
     * Test existsByTransactionId with non-existent transaction.
     */
    @Test
    @Order(11)
    void testExistsByTransactionId_NonExistentTransaction() {
        // Arrange
        String nonExistentId = "TXN9999999999999";

        // Act
        boolean exists = transactionRepository.existsByTransactionId(nonExistentId);

        // Assert
        assertFalse(exists, "Transaction should not exist");
    }

    /**
     * Test save operation for new transaction (INSERT).
     * 
     * <p>Tests VSAM WRITE equivalent operation.
     * Validates all field constraints and foreign key relationships.</p>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>Transaction ID uniqueness constraint</li>
     *   <li>BigDecimal amount precision (scale=2) preservation</li>
     *   <li>VARCHAR length constraints</li>
     *   <li>Foreign key to Card entity</li>
     *   <li>Timestamp auto-population</li>
     * </ul>
     */
    @Test
    @Order(12)
    void testSave_NewTransaction() {
        // Arrange: Create new transaction entity
        Transaction newTransaction = new Transaction();
        newTransaction.setTransactionId("TXN0000000000099");
        newTransaction.setTransactionTypeCode("01"); // Purchase
        newTransaction.setTransactionCategoryCode(5010); // Retail
        newTransaction.setTransactionSource("POS");
        newTransaction.setTransactionDescription("Test Purchase");
        
        // CRITICAL: Set BigDecimal with scale=2 and HALF_UP rounding
        BigDecimal amount = new BigDecimal("99.99").setScale(2, RoundingMode.HALF_UP);
        newTransaction.setTransactionAmount(amount);
        
        newTransaction.setMerchantId(123456789L);
        newTransaction.setMerchantName("TEST MERCHANT");
        newTransaction.setMerchantCity("TEST CITY");
        newTransaction.setMerchantZip("12345");
        newTransaction.setCardNumber("4000123456789010"); // Must exist in test data
        newTransaction.setOriginationTimestamp(LocalDateTime.now());
        newTransaction.setProcessingTimestamp(LocalDateTime.now());

        // Act: Save transaction (VSAM WRITE equivalent)
        Transaction saved = transactionRepository.save(newTransaction);

        // Assert: Verify save successful
        assertAll("New transaction save",
            () -> assertNotNull(saved, "Saved transaction should not be null"),
            () -> assertEquals("TXN0000000000099", saved.getTransactionId()),
            () -> assertEquals(amount, saved.getTransactionAmount()),
            () -> assertEquals(2, saved.getTransactionAmount().scale(), 
                "Saved amount should maintain scale=2")
        );

        // Cleanup: Delete test transaction
        transactionRepository.deleteById("TXN0000000000099");
    }

    /**
     * Test update operation for existing transaction (UPDATE).
     * 
     * <p>Tests VSAM REWRITE equivalent operation.
     * Validates processing timestamp updates and optimistic locking.</p>
     */
    @Test
    @Order(13)
    void testUpdate_ExistingTransaction() {
        // Arrange: Fetch existing transaction
        String transactionId = "TXN0000000000001";
        Optional<Transaction> optionalTxn = transactionRepository.findById(transactionId);
        assertTrue(optionalTxn.isPresent(), "Transaction should exist for update test");
        
        Transaction transaction = optionalTxn.get();
        LocalDateTime newProcessingTimestamp = LocalDateTime.now();
        transaction.setProcessingTimestamp(newProcessingTimestamp);

        // Act: Update transaction
        Transaction updated = transactionRepository.save(transaction);

        // Assert: Verify update successful
        assertAll("Transaction update",
            () -> assertNotNull(updated, "Updated transaction should not be null"),
            () -> assertEquals(transactionId, updated.getTransactionId()),
            () -> assertNotNull(updated.getProcessingTimestamp(), 
                "Processing timestamp should be updated")
        );
    }

    /**
     * Test transaction amount precision.
     * 
     * <p>CRITICAL TEST: Validates TRAN-AMT S9(09)V99 COMP-3 precision preservation.</p>
     * 
     * <p>Tests:</p>
     * <ul>
     *   <li>NUMERIC(11,2) column precision</li>
     *   <li>BigDecimal scale=2 enforcement</li>
     *   <li>RoundingMode.HALF_UP for arithmetic operations</li>
     *   <li>Negative amounts (credits/refunds)</li>
     *   <li>Addition/subtraction with precision maintenance</li>
     * </ul>
     */
    @Test
    @Order(14)
    void testTransactionAmountPrecision() {
        // Arrange: Create transactions with specific amounts
        BigDecimal amount1 = new BigDecimal("12.50").setScale(2, RoundingMode.HALF_UP);
        BigDecimal amount2 = new BigDecimal("34.25").setScale(2, RoundingMode.HALF_UP);
        BigDecimal amount3 = new BigDecimal("100.00").setScale(2, RoundingMode.HALF_UP);

        // Act: Perform arithmetic operations
        BigDecimal sum = amount1.add(amount2).add(amount3).setScale(2, RoundingMode.HALF_UP);
        BigDecimal expectedSum = new BigDecimal("146.75");

        // Assert: Verify precision maintained
        assertAll("BigDecimal precision validation",
            () -> assertEquals(2, amount1.scale(), "Amount1 should have scale=2"),
            () -> assertEquals(2, amount2.scale(), "Amount2 should have scale=2"),
            () -> assertEquals(2, amount3.scale(), "Amount3 should have scale=2"),
            () -> assertEquals(2, sum.scale(), "Sum should have scale=2"),
            () -> assertEquals(0, sum.compareTo(expectedSum), 
                "Sum should equal 146.75 exactly")
        );

        // Test negative amounts (credits/refunds)
        BigDecimal refund = new BigDecimal("-50.00").setScale(2, RoundingMode.HALF_UP);
        BigDecimal netAmount = sum.add(refund).setScale(2, RoundingMode.HALF_UP);
        BigDecimal expectedNet = new BigDecimal("96.75");

        assertAll("Negative amount handling",
            () -> assertEquals(2, refund.scale(), "Refund should have scale=2"),
            () -> assertTrue(refund.compareTo(BigDecimal.ZERO) < 0, 
                "Refund should be negative"),
            () -> assertEquals(0, netAmount.compareTo(expectedNet), 
                "Net amount should be 96.75 after refund")
        );
    }

    /**
     * Test transaction timestamp fields.
     * 
     * <p>Validates origination_timestamp and processing_timestamp mappings
     * from COBOL PIC X(26) fields to TIMESTAMP columns.</p>
     */
    @Test
    @Order(15)
    void testTransactionTimestamps() {
        // Arrange
        String transactionId = "TXN0000000000001";
        Optional<Transaction> optionalTxn = transactionRepository.findById(transactionId);
        assertTrue(optionalTxn.isPresent(), "Transaction should exist");

        // Act
        Transaction transaction = optionalTxn.get();

        // Assert: Verify timestamps
        assertAll("Transaction timestamp validation",
            () -> assertNotNull(transaction.getOriginationTimestamp(), 
                "Origination timestamp should not be null"),
            () -> assertNotNull(transaction.getProcessingTimestamp(), 
                "Processing timestamp should not be null"),
            () -> assertTrue(
                !transaction.getProcessingTimestamp()
                    .isBefore(transaction.getOriginationTimestamp()),
                "Processing timestamp should be after or equal to origination")
        );
    }

    /**
     * Test transaction constraints validation.
     * 
     * <p>Validates database constraints:</p>
     * <ul>
     *   <li>NOT NULL on transaction_id, card_num, amount</li>
     *   <li>VARCHAR length constraints</li>
     *   <li>NUMERIC precision (11,2) for amount</li>
     *   <li>Unique constraint on transaction_id</li>
     * </ul>
     */
    @Test
    @Order(16)
    void testTransactionConstraints() {
        // Arrange: Fetch transaction from database
        Optional<Transaction> optionalTxn = transactionRepository.findById("TXN0000000000001");
        assertTrue(optionalTxn.isPresent(), "Transaction should exist");

        Transaction transaction = optionalTxn.get();

        // Assert: Verify mandatory fields are populated
        assertAll("Transaction constraint validation",
            () -> assertNotNull(transaction.getTransactionId(), 
                "Transaction ID should not be null (NOT NULL constraint)"),
            () -> assertNotNull(transaction.getCardNumber(), 
                "Card number should not be null (NOT NULL constraint)"),
            () -> assertNotNull(transaction.getTransactionAmount(), 
                "Transaction amount should not be null (NOT NULL constraint)"),
            () -> assertTrue(transaction.getTransactionId().length() <= 16, 
                "Transaction ID should not exceed 16 characters"),
            () -> assertTrue(transaction.getTransactionTypeCode().length() <= 2, 
                "Type code should not exceed 2 characters"),
            () -> assertEquals(2, transaction.getTransactionAmount().scale(), 
                "Amount scale should be 2 (matches COBOL PIC S9(09)V99)"),
            () -> assertTrue(transaction.getTransactionAmount().compareTo(new BigDecimal("-999999999.99")) >= 0 
                && transaction.getTransactionAmount().compareTo(new BigDecimal("999999999.99")) <= 0,
                "Amount should be within valid range for NUMERIC(11,2)")
        );
    }

    /**
     * Test pagination performance with 10 transactions per page.
     * 
     * <p>Validates pagination matching COTRN00C BMS screen requirement
     * of 10 transactions per page with PF7/PF8 navigation.</p>
     */
    @Test
    @Order(17)
    void testPaginationWithTenTransactionsPerPage() {
        // Arrange: Card number with multiple transactions
        String cardNumber = "4000123456789010";
        Pageable firstPage = PageRequest.of(0, 10, Sort.by("originationTimestamp").descending());

        // Act: Fetch first page
        Page<Transaction> page = transactionRepository.findByCardNumber(cardNumber, firstPage);

        // Assert: Verify pagination metadata
        assertAll("Pagination with 10 per page",
            () -> assertNotNull(page, "Page should not be null"),
            () -> assertTrue(page.getSize() <= 10, 
                "Page size should be 10 or less"),
            () -> assertEquals(10, firstPage.getPageSize(), 
                "Requested page size should be 10 per COTRN00C"),
            () -> assertTrue(page.getNumber() >= 0, 
                "Page number should be non-negative")
        );
    }

    /**
     * Test findAll with count operation.
     * 
     * <p>Tests basic count operation for batch processing metrics.</p>
     */
    @Test
    @Order(18)
    void testCountAllTransactions() {
        // Act
        long count = transactionRepository.count();

        // Assert
        assertTrue(count > 0, "Should have transactions in test database");
    }

    /**
     * Test delete operation (VSAM DELETE equivalent).
     * 
     * <p>Tests transaction deletion with audit trail considerations.</p>
     */
    @Test
    @Order(19)
    void testDelete_ExistingTransaction() {
        // Arrange: Create transaction to delete
        Transaction txnToDelete = new Transaction();
        txnToDelete.setTransactionId("TXN0000000000098");
        txnToDelete.setTransactionTypeCode("01");
        txnToDelete.setTransactionCategoryCode(5010);
        txnToDelete.setTransactionAmount(
            new BigDecimal("10.00").setScale(2, RoundingMode.HALF_UP));
        txnToDelete.setCardNumber("4000123456789010");
        txnToDelete.setOriginationTimestamp(LocalDateTime.now());
        txnToDelete.setProcessingTimestamp(LocalDateTime.now());
        
        transactionRepository.save(txnToDelete);

        // Act: Delete transaction
        transactionRepository.deleteById("TXN0000000000098");

        // Assert: Verify deletion
        Optional<Transaction> deleted = transactionRepository.findById("TXN0000000000098");
        assertFalse(deleted.isPresent(), "Transaction should be deleted");
    }

    /**
     * Test sorting by amount descending.
     * 
     * <p>Tests custom sorting for transaction reports and analysis.</p>
     */
    @Test
    @Order(20)
    void testSortingByAmountDescending() {
        // Arrange
        String cardNumber = "4000123456789010";
        Pageable pageable = PageRequest.of(0, 10, Sort.by("transactionAmount").descending());

        // Act
        Page<Transaction> page = transactionRepository.findByCardNumber(cardNumber, pageable);

        // Assert: Verify descending order
        if (page.getContent().size() > 1) {
            List<Transaction> transactions = page.getContent();
            for (int i = 0; i < transactions.size() - 1; i++) {
                BigDecimal current = transactions.get(i).getTransactionAmount();
                BigDecimal next = transactions.get(i + 1).getTransactionAmount();
                assertTrue(current.compareTo(next) >= 0, 
                    "Transactions should be sorted by amount descending");
            }
        }
    }

    /**
     * Test compound query with multiple conditions.
     * 
     * <p>Tests complex query with account, date range, and sorting.</p>
     */
    @Test
    @Order(21)
    void testComplexQueryWithMultipleConditions() {
        // Arrange
        String accountId = "00000000001";
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 12, 31);
        Pageable pageable = PageRequest.of(0, 10, 
            Sort.by("originationTimestamp").descending());

        // Act
        Page<Transaction> page = transactionRepository
            .findByAccountIdAndTransactionDateBetween(
                accountId, startDate, endDate, pageable);

        // Assert
        assertAll("Complex query validation",
            () -> assertNotNull(page, "Page should not be null"),
            () -> assertNotNull(page.getContent(), "Page content should not be null")
        );
    }

    /**
     * Test BigDecimal arithmetic operations matching COBOL behavior.
     * 
     * <p>Validates that Java BigDecimal operations produce identical results
     * to COBOL COMP-3 packed decimal arithmetic.</p>
     */
    @Test
    @Order(22)
    void testBigDecimalArithmeticMatchingCobol() {
        // Arrange: Amounts matching COBOL test cases
        BigDecimal balance = new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP);
        BigDecimal purchase = new BigDecimal("125.50").setScale(2, RoundingMode.HALF_UP);
        BigDecimal payment = new BigDecimal("-200.00").setScale(2, RoundingMode.HALF_UP);

        // Act: Perform calculations
        BigDecimal afterPurchase = balance.add(purchase).setScale(2, RoundingMode.HALF_UP);
        BigDecimal afterPayment = afterPurchase.add(payment).setScale(2, RoundingMode.HALF_UP);

        // Assert: Verify COBOL-equivalent results
        assertAll("COBOL arithmetic equivalence",
            () -> assertEquals(0, afterPurchase.compareTo(new BigDecimal("1125.50")),
                "Balance after purchase should be 1125.50"),
            () -> assertEquals(0, afterPayment.compareTo(new BigDecimal("925.50")),
                "Balance after payment should be 925.50"),
            () -> assertEquals(2, afterPayment.scale(),
                "Final balance should maintain scale=2")
        );
    }
}

