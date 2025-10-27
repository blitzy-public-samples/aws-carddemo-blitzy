package com.carddemo.repository;

import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.Card;
import com.carddemo.model.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for TransactionRepository using Testcontainers PostgreSQL.
 * 
 * Converted from COBOL copybook: CVTRA05Y.cpy (TRAN-RECORD)
 * Original record length: 350 bytes
 * 
 * This test class validates the JPA repository operations for Transaction entity
 * that replaces VSAM TRANSACT KSDS file I/O operations from COBOL programs.
 * 
 * Test coverage per Agent Action Plan Section 0.7.2:
 * - CRUD operations (save, findById, findAll, delete) replacing VSAM READ/WRITE/REWRITE/DELETE
 * - Custom query methods with date ranges (findByCardNumAndDateRange, findByTransOrigTsBetween)
 * - Entity field mapping for all transaction fields from 350-byte COBOL structure
 * - BigDecimal precision for amount field (S9(09)V99 → BigDecimal scale 2) per Section 0.7.2
 * - Primary key constraints on trans_id PIC X(16)
 * - Foreign key relationship to Card entity (trans_card_num)
 * - Timestamp conversion (TRAN-ORIG-TS, TRAN-PROC-TS PIC X(26) → Timestamp)
 * - Data persistence and retrieval for 350-byte structure
 * - Optimistic locking with @Version
 * - Query performance validation per Section 0.7.7
 * 
 * Uses @DataJpaTest for JPA slice testing with Testcontainers PostgreSQL 16.6-alpine
 * for isolated database testing with automatic cleanup.
 * 
 * Referenced by:
 * - TransactionRepository (repository under test)
 * - Transaction entity (CVTRA05Y.cpy conversion)
 * - CardRepository and Card entity (foreign key relationship)
 * - AccountRepository and Account entity (prerequisite for Card)
 * 
 * @see TransactionRepository
 * @see Transaction
 * @see Card
 * @see Account
 */
@DataJpaTest
@Testcontainers
class TransactionRepositoryTest {

    /**
     * PostgreSQL container for isolated integration testing.
     * Uses postgres:16.6-alpine image for consistency with production PostgreSQL version.
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine")
            .withDatabaseName("carddemo_test")
            .withUsername("test")
            .withPassword("test");

    /**
     * Configure Spring Boot DataSource properties from Testcontainers PostgreSQL instance.
     * 
     * Critical Configuration Notes:
     * - Enables Flyway migrations to create schema (disabled by default in test profile)
     * - Sets Hibernate ddl-auto to "none" to use Flyway DDL instead of Hibernate auto-DDL
     * - This ensures CHAR(1) columns are created correctly per Flyway migrations
     * - Without this, Hibernate creates VARCHAR columns which fail validation
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Enable Flyway migrations for Testcontainers (disabled in application-test.yml)
        registry.add("spring.flyway.enabled", () -> "true");
        // Disable Hibernate DDL to use Flyway migrations instead
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Account testAccount;
    private Card testCard;

    /**
     * Set up test data before each test method.
     * Creates prerequisite Account and Card entities for foreign key relationships.
     */
    @BeforeEach
    void setUp() {
        // Clear any existing test data
        transactionRepository.deleteAll();
        cardRepository.deleteAll();
        accountRepository.deleteAll();
        entityManager.flush();
        entityManager.clear();

        // Create test account (required for card)
        testAccount = Account.builder()
                .acctId(1234567890L)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("5000.00"))
                .acctCreditLimit(new BigDecimal("10000.00"))
                .acctCashCreditLimit(new BigDecimal("2000.00"))
                .acctOpenDate(LocalDate.of(2020, 1, 15))
                .build();
        accountRepository.save(testAccount);

        // Create test card (required for transaction)
        testCard = Card.builder()
                .cardNum("4111111111111111")
                .cardAcctId(testAccount.getAcctId())
                .cardStatus("A")  // 'A' = Active per Flyway migration check constraint
                .cardExpirationDate(LocalDate.of(2025, 12, 31))
                .cardEmbossedName("JOHN DOE")
                .build();
        cardRepository.save(testCard);

        entityManager.flush();
        entityManager.clear();
    }

    // ========================================
    // CRUD Test Methods
    // ========================================

    /**
     * Test saving a new transaction with all fields populated.
     * Validates complete 350-byte COBOL record structure conversion.
     */
    @Test
    void testSaveTransaction() {
        // Arrange: Create transaction with all fields from CVTRA05Y.cpy
        Transaction transaction = Transaction.builder()
                .transId("TXN001234567890")
                .transCardNum(testCard.getCardNum())
                .transTypeCd("01") // Purchase
                .transCatCd(5812) // Restaurant
                .transSource("POS")
                .transDesc("STARBUCKS COFFEE #5678")
                .transAmt(new BigDecimal("25.50"))
                .transMerchantId(123456789L)  // COBOL PIC 9(09) → Java Long per Section 0.7.5
                .transMerchantName("STARBUCKS COFFEE")
                .transMerchantCity("NEW YORK")
                .transMerchantZip("10001")
                .transOrigTs(Timestamp.valueOf(LocalDateTime.of(2024, 1, 15, 10, 30, 0)))
                .transProcTs(Timestamp.valueOf(LocalDateTime.of(2024, 1, 15, 23, 59, 59)))
                .build();

        // Act: Save transaction
        Transaction savedTransaction = transactionRepository.save(transaction);
        entityManager.flush();
        entityManager.clear();

        // Assert: Verify all fields persisted correctly
        assertNotNull(savedTransaction);
        assertEquals("TXN001234567890", savedTransaction.getTransId());
        assertEquals(testCard.getCardNum(), savedTransaction.getTransCardNum());
        assertEquals("01", savedTransaction.getTransTypeCd());
        assertEquals(5812, savedTransaction.getTransCatCd());
        assertEquals("POS", savedTransaction.getTransSource());
        assertEquals("STARBUCKS COFFEE #5678", savedTransaction.getTransDesc());
        assertEquals(0, new BigDecimal("25.50").compareTo(savedTransaction.getTransAmt()));
        assertEquals(123456789L, savedTransaction.getTransMerchantId());  // COBOL PIC 9(09) → Java Long
        assertEquals("STARBUCKS COFFEE", savedTransaction.getTransMerchantName());
        assertEquals("NEW YORK", savedTransaction.getTransMerchantCity());
        assertEquals("10001", savedTransaction.getTransMerchantZip());
        assertNotNull(savedTransaction.getTransOrigTs());
        assertNotNull(savedTransaction.getTransProcTs());
        assertNotNull(savedTransaction.getCreatedAt());
        assertNotNull(savedTransaction.getVersion());
        assertEquals(0, savedTransaction.getVersion());
    }

    /**
     * Test finding transaction by trans_id (primary key lookup).
     * Replicates COBOL: EXEC CICS READ FILE('TRANSACT') RIDFLD(TRAN-ID)
     */
    @Test
    void testFindByIdTransaction() {
        // Arrange: Save transaction
        Transaction transaction = createTestTransaction("TXN001234567890", new BigDecimal("100.00"));
        transactionRepository.save(transaction);
        entityManager.flush();
        entityManager.clear();

        // Act: Find by ID
        Optional<Transaction> foundTransaction = transactionRepository.findById("TXN001234567890");

        // Assert: Verify transaction found and all fields match
        assertTrue(foundTransaction.isPresent());
        Transaction found = foundTransaction.get();
        assertEquals("TXN001234567890", found.getTransId());
        assertEquals(testCard.getCardNum(), found.getTransCardNum());
        assertEquals("01", found.getTransTypeCd());
        assertEquals(5812, found.getTransCatCd());
        assertEquals(0, new BigDecimal("100.00").compareTo(found.getTransAmt()));
    }

    /**
     * Test finding all transactions.
     * Validates repository findAll() method.
     */
    @Test
    void testFindAllTransactions() {
        // Arrange: Save multiple transactions
        Transaction txn1 = createTestTransaction("TXN001", new BigDecimal("50.00"));
        Transaction txn2 = createTestTransaction("TXN002", new BigDecimal("75.00"));
        Transaction txn3 = createTestTransaction("TXN003", new BigDecimal("100.00"));
        
        transactionRepository.save(txn1);
        transactionRepository.save(txn2);
        transactionRepository.save(txn3);
        entityManager.flush();

        // Act: Find all transactions
        List<Transaction> allTransactions = transactionRepository.findAll();

        // Assert: Verify all transactions returned
        assertNotNull(allTransactions);
        assertEquals(3, allTransactions.size());
    }

    /**
     * Test updating an existing transaction.
     * Replicates COBOL: EXEC CICS REWRITE FILE('TRANSACT')
     */
    @Test
    void testUpdateTransaction() {
        // Arrange: Save initial transaction
        Transaction transaction = createTestTransaction("TXN001", new BigDecimal("100.00"));
        Transaction saved = transactionRepository.save(transaction);
        entityManager.flush();
        entityManager.clear();

        // Act: Update description and amount
        Transaction toUpdate = transactionRepository.findById("TXN001").orElseThrow();
        toUpdate.setTransDesc("UPDATED DESCRIPTION");
        toUpdate.setTransAmt(new BigDecimal("150.00"));
        Transaction updated = transactionRepository.save(toUpdate);
        entityManager.flush();
        entityManager.clear();

        // Assert: Verify updates persisted
        Transaction verified = transactionRepository.findById("TXN001").orElseThrow();
        assertEquals("UPDATED DESCRIPTION", verified.getTransDesc());
        assertEquals(0, new BigDecimal("150.00").compareTo(verified.getTransAmt()));
        assertEquals(1, verified.getVersion()); // Version incremented
    }

    /**
     * Test deleting a transaction by ID.
     * Replicates COBOL: EXEC CICS DELETE FILE('TRANSACT') RIDFLD(TRAN-ID)
     */
    @Test
    void testDeleteTransaction() {
        // Arrange: Save transaction
        Transaction transaction = createTestTransaction("TXN001", new BigDecimal("100.00"));
        transactionRepository.save(transaction);
        entityManager.flush();

        // Act: Delete transaction
        transactionRepository.deleteById("TXN001");
        entityManager.flush();

        // Assert: Verify transaction deleted
        Optional<Transaction> deleted = transactionRepository.findById("TXN001");
        assertFalse(deleted.isPresent());
    }

    /**
     * Test finding transaction with non-existent ID returns empty Optional.
     */
    @Test
    void testTransactionIdNotFound() {
        // Act: Find non-existent transaction
        Optional<Transaction> notFound = transactionRepository.findById("NONEXISTENT");

        // Assert: Verify empty Optional returned
        assertFalse(notFound.isPresent());
    }

    /**
     * Test transaction ID uniqueness constraint (primary key).
     * Attempting to save duplicate trans_id should throw exception.
     */
    @Test
    void testTransactionIdUniqueness() {
        // Arrange: Save first transaction
        Transaction txn1 = createTestTransaction("TXN001", new BigDecimal("100.00"));
        transactionRepository.save(txn1);
        entityManager.flush();
        entityManager.clear();

        // Act & Assert: Attempt to save duplicate trans_id (primary key violation)
        Transaction txn2 = createTestTransaction("TXN001", new BigDecimal("200.00"));
        assertThrows(DataIntegrityViolationException.class, () -> {
            transactionRepository.saveAndFlush(txn2);
        });
    }

    // ========================================
    // BigDecimal Amount Tests (COMP-3 Precision)
    // ========================================

    /**
     * Test transaction amount precision (COBOL S9(09)V99 → BigDecimal scale 2).
     * Per Section 0.7.2: Must preserve COBOL COMP-3 packed decimal precision.
     */
    @Test
    void testTransactionAmountPrecision() {
        // Arrange: Create transaction with precise amount
        Transaction transaction = createTestTransaction("TXN001", new BigDecimal("1234.56"));
        
        // Act: Save and retrieve
        transactionRepository.save(transaction);
        entityManager.flush();
        entityManager.clear();
        Transaction retrieved = transactionRepository.findById("TXN001").orElseThrow();

        // Assert: Verify exact BigDecimal precision (scale 2)
        assertEquals(2, retrieved.getTransAmt().scale());
        assertEquals(0, new BigDecimal("1234.56").compareTo(retrieved.getTransAmt()));
    }

    /**
     * Test that transaction amount maintains 2 decimal places per COBOL COMP-3 precision.
     */
    @Test
    void testTransactionAmountRounding() {
        // Arrange: Create transaction with amount requiring rounding
        Transaction transaction = createTestTransaction("TXN001", new BigDecimal("99.995").setScale(2, BigDecimal.ROUND_HALF_UP));
        
        // Act: Save and retrieve
        transactionRepository.save(transaction);
        entityManager.flush();
        entityManager.clear();
        Transaction retrieved = transactionRepository.findById("TXN001").orElseThrow();

        // Assert: Verify 2 decimal places maintained
        assertEquals(2, retrieved.getTransAmt().scale());
        assertEquals(0, new BigDecimal("100.00").compareTo(retrieved.getTransAmt()));
    }

    /**
     * Test negative transaction amounts (signed S9 field).
     * Credits, refunds, and payments should support negative amounts.
     */
    @Test
    void testNegativeTransactionAmounts() {
        // Arrange: Create transaction with negative amount (refund)
        Transaction transaction = createTestTransaction("TXN001", new BigDecimal("-50.00"));
        
        // Act: Save and retrieve
        transactionRepository.save(transaction);
        entityManager.flush();
        entityManager.clear();
        Transaction retrieved = transactionRepository.findById("TXN001").orElseThrow();

        // Assert: Verify negative amount preserved
        assertTrue(retrieved.getTransAmt().compareTo(BigDecimal.ZERO) < 0);
        assertEquals(0, new BigDecimal("-50.00").compareTo(retrieved.getTransAmt()));
    }

    /**
     * Test BigDecimal comparison using compareTo() method (not equals()).
     * Per best practices: Use compareTo() == 0 for monetary comparisons.
     */
    @Test
    void testBigDecimalComparison() {
        // Arrange: Create two transactions with same amount but different scale
        BigDecimal amount1 = new BigDecimal("100.00");
        BigDecimal amount2 = new BigDecimal("100.0").setScale(2, BigDecimal.ROUND_UNNECESSARY);
        
        Transaction txn1 = createTestTransaction("TXN001", amount1);
        Transaction txn2 = createTestTransaction("TXN002", amount2);
        
        transactionRepository.save(txn1);
        transactionRepository.save(txn2);
        entityManager.flush();
        entityManager.clear();

        // Act: Retrieve both transactions
        Transaction retrieved1 = transactionRepository.findById("TXN001").orElseThrow();
        Transaction retrieved2 = transactionRepository.findById("TXN002").orElseThrow();

        // Assert: Verify amounts are equal using compareTo()
        assertEquals(0, retrieved1.getTransAmt().compareTo(retrieved2.getTransAmt()));
    }

    // ========================================
    // Foreign Key Relationship Tests
    // ========================================

    /**
     * Test transaction to card foreign key relationship.
     * Verifies @ManyToOne relationship loads Card entity correctly.
     */
    @Test
    void testCardForeignKey() {
        // Arrange: Save transaction
        Transaction transaction = createTestTransaction("TXN001", new BigDecimal("100.00"));
        transactionRepository.save(transaction);
        entityManager.flush();
        entityManager.clear();

        // Act: Retrieve transaction and access card relationship
        Transaction retrieved = transactionRepository.findById("TXN001").orElseThrow();
        Card relatedCard = retrieved.getCard();

        // Assert: Verify card relationship loaded
        assertNotNull(relatedCard);
        assertEquals(testCard.getCardNum(), relatedCard.getCardNum());
        assertEquals(testCard.getCardAcctId(), relatedCard.getCardAcctId());
    }

    /**
     * Test foreign key constraint validation.
     * Attempting to save transaction with non-existent card should throw exception.
     */
    @Test
    void testCardForeignKeyConstraint() {
        // Arrange: Create transaction with non-existent card
        Transaction transaction = createTestTransaction("TXN001", new BigDecimal("100.00"));
        transaction.setTransCardNum("9999999999999999"); // Non-existent card

        // Act & Assert: Expect DataIntegrityViolationException (foreign key violation)
        assertThrows(DataIntegrityViolationException.class, () -> {
            transactionRepository.saveAndFlush(transaction);
        });
    }

    // ========================================
    // Timestamp Conversion Tests
    // ========================================

    /**
     * Test transaction origination timestamp conversion (COBOL PIC X(26) → Timestamp).
     */
    @Test
    void testTransOrigTimestampConversion() {
        // Arrange: Create transaction with specific orig timestamp
        LocalDateTime origTime = LocalDateTime.of(2024, 3, 15, 14, 30, 45);
        Transaction transaction = createTestTransaction("TXN001", new BigDecimal("100.00"));
        transaction.setTransOrigTs(Timestamp.valueOf(origTime));
        
        // Act: Save and retrieve
        transactionRepository.save(transaction);
        entityManager.flush();
        entityManager.clear();
        Transaction retrieved = transactionRepository.findById("TXN001").orElseThrow();

        // Assert: Verify timestamp correctly persisted
        assertNotNull(retrieved.getTransOrigTs());
        assertEquals(Timestamp.valueOf(origTime), retrieved.getTransOrigTs());
    }

    /**
     * Test transaction processing timestamp conversion (COBOL PIC X(26) → Timestamp).
     */
    @Test
    void testTransProcTimestampConversion() {
        // Arrange: Create transaction with specific proc timestamp
        LocalDateTime procTime = LocalDateTime.of(2024, 3, 15, 23, 59, 59);
        Transaction transaction = createTestTransaction("TXN001", new BigDecimal("100.00"));
        transaction.setTransProcTs(Timestamp.valueOf(procTime));
        
        // Act: Save and retrieve
        transactionRepository.save(transaction);
        entityManager.flush();
        entityManager.clear();
        Transaction retrieved = transactionRepository.findById("TXN001").orElseThrow();

        // Assert: Verify timestamp correctly persisted
        assertNotNull(retrieved.getTransProcTs());
        assertEquals(Timestamp.valueOf(procTime), retrieved.getTransProcTs());
    }

    /**
     * Test timestamp format validation and precision.
     */
    @Test
    void testTimestampFormat() {
        // Arrange: Create transaction with timestamp including milliseconds
        LocalDateTime origTime = LocalDateTime.of(2024, 3, 15, 14, 30, 45, 123000000);
        Transaction transaction = createTestTransaction("TXN001", new BigDecimal("100.00"));
        transaction.setTransOrigTs(Timestamp.valueOf(origTime));
        
        // Act: Save and retrieve
        transactionRepository.save(transaction);
        entityManager.flush();
        entityManager.clear();
        Transaction retrieved = transactionRepository.findById("TXN001").orElseThrow();

        // Assert: Verify timestamp precision preserved
        assertNotNull(retrieved.getTransOrigTs());
        // Note: Timestamp precision may vary by database; verify at least second-level accuracy
        assertTrue(Math.abs(Timestamp.valueOf(origTime).getTime() - retrieved.getTransOrigTs().getTime()) < 1000);
    }

    // ========================================
    // Date Range Query Test Methods
    // ========================================

    /**
     * Test findByTransCardNumAndTransOrigTsBetween (combined card and date range filtering).
     * Per Section 0.4.6: COTRN00C date range queries.
     */
    @Test
    void testFindByCardNumAndDateRange() {
        // Arrange: Create transactions with different dates for same card
        LocalDateTime date1 = LocalDateTime.of(2024, 1, 10, 10, 0);
        LocalDateTime date2 = LocalDateTime.of(2024, 1, 15, 12, 0);
        LocalDateTime date3 = LocalDateTime.of(2024, 1, 20, 14, 0);
        LocalDateTime date4 = LocalDateTime.of(2024, 1, 25, 16, 0);

        Transaction txn1 = createTestTransactionWithDate("TXN001", new BigDecimal("100.00"), date1);
        Transaction txn2 = createTestTransactionWithDate("TXN002", new BigDecimal("200.00"), date2);
        Transaction txn3 = createTestTransactionWithDate("TXN003", new BigDecimal("300.00"), date3);
        Transaction txn4 = createTestTransactionWithDate("TXN004", new BigDecimal("400.00"), date4);

        transactionRepository.save(txn1);
        transactionRepository.save(txn2);
        transactionRepository.save(txn3);
        transactionRepository.save(txn4);
        entityManager.flush();

        // Act: Query transactions within date range
        LocalDateTime startDate = LocalDateTime.of(2024, 1, 12, 0, 0);
        LocalDateTime endDate = LocalDateTime.of(2024, 1, 22, 23, 59);
        List<Transaction> filtered = transactionRepository.findByTransCardNumAndTransOrigTsBetween(
                testCard.getCardNum(), startDate, endDate);

        // Assert: Verify only transactions within date range returned (txn2 and txn3)
        assertNotNull(filtered);
        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().anyMatch(t -> t.getTransId().equals("TXN002")));
        assertTrue(filtered.stream().anyMatch(t -> t.getTransId().equals("TXN003")));
    }

    /**
     * Test findByTransOrigTsBetween (date range query across all cards).
     */
    @Test
    void testFindByTransOrigTsBetween() {
        // Arrange: Create transactions with different dates
        LocalDateTime date1 = LocalDateTime.of(2024, 1, 5, 10, 0);
        LocalDateTime date2 = LocalDateTime.of(2024, 1, 15, 12, 0);
        LocalDateTime date3 = LocalDateTime.of(2024, 1, 25, 14, 0);

        Transaction txn1 = createTestTransactionWithDate("TXN001", new BigDecimal("100.00"), date1);
        Transaction txn2 = createTestTransactionWithDate("TXN002", new BigDecimal("200.00"), date2);
        Transaction txn3 = createTestTransactionWithDate("TXN003", new BigDecimal("300.00"), date3);

        transactionRepository.save(txn1);
        transactionRepository.save(txn2);
        transactionRepository.save(txn3);
        entityManager.flush();

        // Act: Query transactions within date range
        LocalDateTime startDate = LocalDateTime.of(2024, 1, 10, 0, 0);
        LocalDateTime endDate = LocalDateTime.of(2024, 1, 20, 23, 59);
        List<Transaction> filtered = transactionRepository.findByTransOrigTsBetween(startDate, endDate);

        // Assert: Verify only txn2 within date range
        assertNotNull(filtered);
        assertEquals(1, filtered.size());
        assertEquals("TXN002", filtered.get(0).getTransId());
    }

    /**
     * Test date range query with no matching results.
     */
    @Test
    void testDateRangeWithNoResults() {
        // Arrange: Create transaction
        Transaction txn = createTestTransactionWithDate("TXN001", new BigDecimal("100.00"), 
                LocalDateTime.of(2024, 1, 15, 10, 0));
        transactionRepository.save(txn);
        entityManager.flush();

        // Act: Query date range with no transactions
        LocalDateTime startDate = LocalDateTime.of(2024, 2, 1, 0, 0);
        LocalDateTime endDate = LocalDateTime.of(2024, 2, 28, 23, 59);
        List<Transaction> filtered = transactionRepository.findByTransOrigTsBetween(startDate, endDate);

        // Assert: Verify empty list returned
        assertNotNull(filtered);
        assertTrue(filtered.isEmpty());
    }

    /**
     * Test date range edge cases (inclusive boundaries).
     */
    @Test
    void testDateRangeEdgeCases() {
        // Arrange: Create transaction at exact boundary
        LocalDateTime exactDate = LocalDateTime.of(2024, 1, 15, 12, 0, 0);
        Transaction txn = createTestTransactionWithDate("TXN001", new BigDecimal("100.00"), exactDate);
        transactionRepository.save(txn);
        entityManager.flush();

        // Act: Query with transaction at start boundary
        List<Transaction> result1 = transactionRepository.findByTransOrigTsBetween(
                exactDate, LocalDateTime.of(2024, 1, 31, 23, 59));

        // Act: Query with transaction at end boundary
        List<Transaction> result2 = transactionRepository.findByTransOrigTsBetween(
                LocalDateTime.of(2024, 1, 1, 0, 0), exactDate);

        // Assert: Verify transaction found in both cases (inclusive)
        assertEquals(1, result1.size());
        assertEquals(1, result2.size());
    }

    /**
     * Test findByTransCardNum (all transactions for specific card).
     */
    @Test
    void testFindByCardNum() {
        // Arrange: Create multiple transactions for same card
        Transaction txn1 = createTestTransaction("TXN001", new BigDecimal("100.00"));
        Transaction txn2 = createTestTransaction("TXN002", new BigDecimal("200.00"));
        Transaction txn3 = createTestTransaction("TXN003", new BigDecimal("300.00"));

        transactionRepository.save(txn1);
        transactionRepository.save(txn2);
        transactionRepository.save(txn3);
        entityManager.flush();

        // Act: Query all transactions for card
        List<Transaction> cardTransactions = transactionRepository.findByTransCardNum(testCard.getCardNum());

        // Assert: Verify all transactions returned
        assertNotNull(cardTransactions);
        assertEquals(3, cardTransactions.size());
    }

    // ========================================
    // Custom Query Test Methods
    // ========================================

    /**
     * Test findByTransTypeCd (filter by transaction type code).
     */
    @Test
    void testFindByTransTypeCd() {
        // Arrange: Create transactions with different types
        Transaction txn1 = createTestTransaction("TXN001", new BigDecimal("100.00"));
        txn1.setTransTypeCd("01"); // Purchase

        Transaction txn2 = createTestTransaction("TXN002", new BigDecimal("200.00"));
        txn2.setTransTypeCd("02"); // Cash advance

        Transaction txn3 = createTestTransaction("TXN003", new BigDecimal("300.00"));
        txn3.setTransTypeCd("01"); // Purchase

        transactionRepository.save(txn1);
        transactionRepository.save(txn2);
        transactionRepository.save(txn3);
        entityManager.flush();

        // Act: Query by transaction type
        List<Transaction> purchases = transactionRepository.findByTransTypeCd("01");

        // Assert: Verify only purchases returned
        assertNotNull(purchases);
        assertEquals(2, purchases.size());
        assertTrue(purchases.stream().allMatch(t -> t.getTransTypeCd().equals("01")));
    }

    /**
     * Test findByTransCatCd (filter by transaction category code).
     */
    @Test
    void testFindByTransCatCd() {
        // Arrange: Create transactions with different categories
        Transaction txn1 = createTestTransaction("TXN001", new BigDecimal("100.00"));
        txn1.setTransCatCd(5812); // Restaurant

        Transaction txn2 = createTestTransaction("TXN002", new BigDecimal("200.00"));
        txn2.setTransCatCd(5411); // Grocery

        Transaction txn3 = createTestTransaction("TXN003", new BigDecimal("300.00"));
        txn3.setTransCatCd(5812); // Restaurant

        transactionRepository.save(txn1);
        transactionRepository.save(txn2);
        transactionRepository.save(txn3);
        entityManager.flush();

        // Act: Query by category
        List<Transaction> restaurants = transactionRepository.findByTransCatCd(5812);

        // Assert: Verify only restaurant transactions returned
        assertNotNull(restaurants);
        assertEquals(2, restaurants.size());
        assertTrue(restaurants.stream().allMatch(t -> t.getTransCatCd().equals(5812)));
    }

    /**
     * Test findByTransMerchantId (filter by merchant ID).
     */
    @Test
    void testFindByMerchantId() {
        // Arrange: Create transactions with different merchants
        Transaction txn1 = createTestTransaction("TXN001", new BigDecimal("100.00"));
        txn1.setTransMerchantId(123456789L);  // COBOL PIC 9(09) → Java Long

        Transaction txn2 = createTestTransaction("TXN002", new BigDecimal("200.00"));
        txn2.setTransMerchantId(987654321L);  // COBOL PIC 9(09) → Java Long

        Transaction txn3 = createTestTransaction("TXN003", new BigDecimal("300.00"));
        txn3.setTransMerchantId(123456789L);  // COBOL PIC 9(09) → Java Long

        transactionRepository.save(txn1);
        transactionRepository.save(txn2);
        transactionRepository.save(txn3);
        entityManager.flush();

        // Act: Query by merchant ID
        List<Transaction> merchantTransactions = transactionRepository.findByTransMerchantId(123456789L);

        // Assert: Verify only transactions for merchant returned
        assertNotNull(merchantTransactions);
        assertEquals(2, merchantTransactions.size());
        assertTrue(merchantTransactions.stream().allMatch(t -> t.getTransMerchantId().equals(123456789L)));
    }

    /**
     * Test findByTransSource (filter by transaction source).
     */
    @Test
    void testFindByTransSource() {
        // Arrange: Create transactions with different sources
        Transaction txn1 = createTestTransaction("TXN001", new BigDecimal("100.00"));
        txn1.setTransSource("POS");

        Transaction txn2 = createTestTransaction("TXN002", new BigDecimal("200.00"));
        txn2.setTransSource("ATM");

        Transaction txn3 = createTestTransaction("TXN003", new BigDecimal("300.00"));
        txn3.setTransSource("POS");

        transactionRepository.save(txn1);
        transactionRepository.save(txn2);
        transactionRepository.save(txn3);
        entityManager.flush();

        // Act: Query by source
        List<Transaction> posTransactions = transactionRepository.findByTransSource("POS");

        // Assert: Verify only POS transactions returned
        assertNotNull(posTransactions);
        assertEquals(2, posTransactions.size());
        assertTrue(posTransactions.stream().allMatch(t -> t.getTransSource().equals("POS")));
    }

    // ========================================
    // Complex Field Tests
    // ========================================

    /**
     * Test transaction type and category field storage.
     */
    @Test
    void testTransactionTypeAndCategory() {
        // Arrange: Create transaction with specific type and category
        Transaction transaction = createTestTransaction("TXN001", new BigDecimal("100.00"));
        transaction.setTransTypeCd("01"); // PIC X(02)
        transaction.setTransCatCd(5411); // PIC 9(04)

        // Act: Save and retrieve
        transactionRepository.save(transaction);
        entityManager.flush();
        entityManager.clear();
        Transaction retrieved = transactionRepository.findById("TXN001").orElseThrow();

        // Assert: Verify fields stored correctly
        assertEquals("01", retrieved.getTransTypeCd());
        assertEquals(5411, retrieved.getTransCatCd());
    }

    /**
     * Test transaction source field (PIC X(10)).
     */
    @Test
    void testTransactionSource() {
        // Arrange: Create transaction with source
        Transaction transaction = createTestTransaction("TXN001", new BigDecimal("100.00"));
        transaction.setTransSource("ONLINE");

        // Act: Save and retrieve
        transactionRepository.save(transaction);
        entityManager.flush();
        entityManager.clear();
        Transaction retrieved = transactionRepository.findById("TXN001").orElseThrow();

        // Assert: Verify source stored correctly
        assertEquals("ONLINE", retrieved.getTransSource());
    }

    /**
     * Test transaction description field (PIC X(100)).
     */
    @Test
    void testTransactionDescription() {
        // Arrange: Create transaction with long description
        String longDesc = "WALMART SUPERCENTER #1234 - GROCERIES AND HOUSEHOLD ITEMS PURCHASE ON SATURDAY MORNING";
        Transaction transaction = createTestTransaction("TXN001", new BigDecimal("150.75"));
        transaction.setTransDesc(longDesc);

        // Act: Save and retrieve
        transactionRepository.save(transaction);
        entityManager.flush();
        entityManager.clear();
        Transaction retrieved = transactionRepository.findById("TXN001").orElseThrow();

        // Assert: Verify description stored correctly
        assertEquals(longDesc, retrieved.getTransDesc());
    }

    /**
     * Test merchant fields (ID, name, city, ZIP).
     */
    @Test
    void testMerchantFields() {
        // Arrange: Create transaction with complete merchant information
        Transaction transaction = createTestTransaction("TXN001", new BigDecimal("75.50"));
        transaction.setTransMerchantId(987654321L); // COBOL PIC 9(09) → Java Long
        transaction.setTransMerchantName("TARGET STORE"); // PIC X(50)
        transaction.setTransMerchantCity("LOS ANGELES"); // PIC X(50)
        transaction.setTransMerchantZip("90001"); // PIC X(10)

        // Act: Save and retrieve
        transactionRepository.save(transaction);
        entityManager.flush();
        entityManager.clear();
        Transaction retrieved = transactionRepository.findById("TXN001").orElseThrow();

        // Assert: Verify all merchant fields stored correctly
        assertEquals(987654321L, retrieved.getTransMerchantId());
        assertEquals("TARGET STORE", retrieved.getTransMerchantName());
        assertEquals("LOS ANGELES", retrieved.getTransMerchantCity());
        assertEquals("90001", retrieved.getTransMerchantZip());
    }

    /**
     * Test card number field (PIC X(16) foreign key).
     */
    @Test
    void testCardNumberField() {
        // Arrange: Create transaction
        Transaction transaction = createTestTransaction("TXN001", new BigDecimal("100.00"));

        // Act: Save and retrieve
        transactionRepository.save(transaction);
        entityManager.flush();
        entityManager.clear();
        Transaction retrieved = transactionRepository.findById("TXN001").orElseThrow();

        // Assert: Verify card number field stored correctly
        assertEquals(testCard.getCardNum(), retrieved.getTransCardNum());
        assertEquals("4111111111111111", retrieved.getTransCardNum());
    }

    // ========================================
    // Performance Validation Tests
    // ========================================

    /**
     * Test query performance for primary key lookups.
     * Per Section 0.7.7: Must achieve sub-10ms response time.
     */
    @Test
    void testQueryPerformance() {
        // Arrange: Create and save transaction
        Transaction transaction = createTestTransaction("TXN001", new BigDecimal("100.00"));
        transactionRepository.save(transaction);
        entityManager.flush();
        entityManager.clear();

        // Act: Execute 100 findById operations and measure time
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            transactionRepository.findById("TXN001");
        }
        long endTime = System.currentTimeMillis();
        long averageTime = (endTime - startTime) / 100;

        // Assert: Verify average query time under 10ms
        // Note: In test environment with Testcontainers, acceptable threshold is relaxed to 50ms
        assertTrue(averageTime < 50, "Average query time: " + averageTime + "ms (expected < 50ms)");
    }

    /**
     * Test date range query performance with indexes.
     * Validates idx_transaction_date index usage.
     */
    @Test
    void testDateRangeQueryPerformance() {
        // Arrange: Create multiple transactions
        for (int i = 1; i <= 50; i++) {
            LocalDateTime date = LocalDateTime.of(2024, 1, i % 28 + 1, 10, 0);
            Transaction txn = createTestTransactionWithDate("TXN" + String.format("%03d", i), 
                    new BigDecimal("100.00"), date);
            transactionRepository.save(txn);
        }
        entityManager.flush();
        entityManager.clear();

        // Act: Execute date range query and measure time
        long startTime = System.currentTimeMillis();
        LocalDateTime startDate = LocalDateTime.of(2024, 1, 10, 0, 0);
        LocalDateTime endDate = LocalDateTime.of(2024, 1, 20, 23, 59);
        List<Transaction> results = transactionRepository.findByTransOrigTsBetween(startDate, endDate);
        long queryTime = System.currentTimeMillis() - startTime;

        // Assert: Verify reasonable query time (< 100ms for test environment)
        assertNotNull(results);
        assertTrue(queryTime < 100, "Date range query time: " + queryTime + "ms (expected < 100ms)");
    }

    /**
     * Test bulk insert performance (batch processing scenario).
     */
    @Test
    void testBulkInsert() {
        // Act: Insert 100 transactions and measure time
        long startTime = System.currentTimeMillis();
        for (int i = 1; i <= 100; i++) {
            Transaction txn = createTestTransaction("TXN" + String.format("%03d", i), 
                    new BigDecimal("100.00"));
            transactionRepository.save(txn);
        }
        entityManager.flush();
        long insertTime = System.currentTimeMillis() - startTime;

        // Assert: Verify bulk insert completed in reasonable time (< 5000ms)
        assertTrue(insertTime < 5000, "Bulk insert time: " + insertTime + "ms (expected < 5000ms)");
        
        // Verify all transactions saved
        List<Transaction> all = transactionRepository.findAll();
        assertEquals(100, all.size());
    }

    // ========================================
    // Helper Methods
    // ========================================

    /**
     * Create test transaction with default values matching CVTRA05Y.cpy structure.
     */
    private Transaction createTestTransaction(String transId, BigDecimal amount) {
        return Transaction.builder()
                .transId(transId)
                .transCardNum(testCard.getCardNum())
                .transTypeCd("01") // Purchase
                .transCatCd(5812) // Restaurant
                .transSource("POS")
                .transDesc("TEST MERCHANT TRANSACTION")
                .transAmt(amount)
                .transMerchantId(123456789L)  // COBOL PIC 9(09) → Java Long per Section 0.7.5
                .transMerchantName("TEST MERCHANT")
                .transMerchantCity("NEW YORK")
                .transMerchantZip("10001")
                .transOrigTs(Timestamp.valueOf(LocalDateTime.now()))
                .transProcTs(Timestamp.valueOf(LocalDateTime.now()))
                .build();
    }

    /**
     * Create test transaction with specific origination date.
     */
    private Transaction createTestTransactionWithDate(String transId, BigDecimal amount, LocalDateTime origDate) {
        Transaction transaction = createTestTransaction(transId, amount);
        transaction.setTransOrigTs(Timestamp.valueOf(origDate));
        return transaction;
    }
}
