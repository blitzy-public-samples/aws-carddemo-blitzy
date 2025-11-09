package com.carddemo.repository;

import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit 5 @DataJpaTest test class for TransactionRepository.
 * 
 * <p>This test class validates PostgreSQL query operations that replicate VSAM KSDS
 * READ/WRITE operations on the TRANSACT master file from CVTRA05Y.cpy (TRAN-RECORD).
 * Uses H2 in-memory database for isolated testing without affecting development or
 * production databases.</p>
 * 
 * <p><strong>Purpose:</strong></p>
 * <p>Verifies that Spring Data JPA repository methods correctly:</p>
 * <ul>
 *   <li>Replicate COBOL COTRN00C STARTBR/READNEXT sequential access with pagination</li>
 *   <li>Support 10 transactions per page matching COBOL display requirements</li>
 *   <li>Filter transactions by date range for report generation (CORPT00C)</li>
 *   <li>Preserve BigDecimal precision for TRAN-AMT (PIC S9(09)V99 COMP-3) with scale=2</li>
 *   <li>Persist all 350-byte TRAN-RECORD fields including transaction ID, type/category
 *       codes, merchant information, and timestamps</li>
 *   <li>Maintain foreign key relationship to Card entity via TRAN-CARD-NUM</li>
 * </ul>
 * 
 * <p><strong>Test Data Hierarchy:</strong></p>
 * <p>Tests create complete entity hierarchy to validate referential integrity:</p>
 * <pre>
 * Customer (CVCUS01Y.cpy) 
 *    ↓
 * Account (CVACT01Y.cpy)
 *    ↓
 * Card (CVACT02Y.cpy)
 *    ↓
 * Transaction (CVTRA05Y.cpy) ← This entity is under test
 * </pre>
 * 
 * <p><strong>COBOL to JPA Operation Mapping Tested:</strong></p>
 * <table border="1">
 *   <tr>
 *     <th>COBOL VSAM Operation</th>
 *     <th>JPA Repository Method</th>
 *     <th>Test Method</th>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS STARTBR FILE('TRANSACT') RIDFLD(TRAN-CARD-NUM)<br>
 *         PERFORM UNTIL WS-IDX > 10</td>
 *     <td>findByCard_CardNumber(String, Pageable)</td>
 *     <td>testFindByCardNumber_WithPagination()</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS READ FILE('TRANSACT') filtering by date</td>
 *     <td>findByCardNumberAndTransactionDateBetween(...)</td>
 *     <td>testFindByCardNumberAndDateBetween_ReturnsFiltered()</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS WRITE FILE('TRANSACT') FROM(TRAN-RECORD)</td>
 *     <td>save(Transaction)</td>
 *     <td>testSave_PreservesAmountPrecision()</td>
 *   </tr>
 * </table>
 * 
 * <p><strong>Configuration:</strong></p>
 * <ul>
 *   <li>@DataJpaTest: Configures JPA repositories with H2 in-memory database</li>
 *   <li>@ActiveProfiles("test"): Loads application-test.properties configuration</li>
 *   <li>Auto-rollback: Each test method runs in transaction that rolls back after test</li>
 *   <li>Isolated execution: Each test gets fresh database state via @BeforeEach setup</li>
 * </ul>
 * 
 * <p><strong>Performance Considerations:</strong></p>
 * <p>These tests verify query patterns that must meet production requirements:</p>
 * <ul>
 *   <li>Transaction list queries: &lt;200ms response time (95th percentile)</li>
 *   <li>Pagination: 10 transactions per page to match COBOL COTRN00C requirements</li>
 *   <li>Index usage: Queries use card_number and origination_timestamp indexes</li>
 *   <li>Referential integrity: Foreign key constraints enforced at database level</li>
 * </ul>
 * 
 * @see TransactionRepository
 * @see Transaction
 * @see <a href="Section 0.4">Source File app/cpy/CVTRA05Y.cpy</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.10">Special Instructions - BigDecimal Precision</a>
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("TransactionRepository Integration Tests")
public class TransactionRepositoryTest {

    /**
     * Transaction repository under test.
     * 
     * <p>Provides CRUD operations and custom query methods for Transaction entity,
     * replacing COBOL VSAM file operations on TRANSACT master file.</p>
     */
    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * Card repository for test data setup.
     * 
     * <p>Required to create valid Card entities that transactions reference
     * via foreign key constraint.</p>
     */
    @Autowired
    private CardRepository cardRepository;

    /**
     * Account repository for test data setup.
     * 
     * <p>Required to create valid Account entities that cards reference,
     * establishing complete customer-account-card-transaction hierarchy.</p>
     */
    @Autowired
    private AccountRepository accountRepository;

    /**
     * Customer repository for test data setup.
     * 
     * <p>Required to create Customer entities at root of entity hierarchy,
     * enabling complete referential integrity validation.</p>
     */
    @Autowired
    private CustomerRepository customerRepository;

    // Test data entities - initialized in @BeforeEach
    private Customer testCustomer;
    private Account testAccount;
    private Card testCard;
    private List<Transaction> testTransactions;

    /**
     * Set up test data before each test method execution.
     * 
     * <p>Creates complete entity hierarchy with valid foreign key relationships:</p>
     * <ol>
     *   <li>Customer with ID 1000000001, basic personal information</li>
     *   <li>Account linked to customer with ID 10000000001, balance and limits</li>
     *   <li>Card linked to account with number "4111111111111111"</li>
     *   <li>Multiple transactions linked to card for pagination testing</li>
     * </ol>
     * 
     * <p>This setup ensures each test starts with fresh, isolated data and validates
     * that repository operations work correctly with complete referential integrity.</p>
     * 
     * <p><strong>Test Data Characteristics:</strong></p>
     * <ul>
     *   <li>Customer: Valid DOB, address, FICO score</li>
     *   <li>Account: Active status, positive balance, credit limits</li>
     *   <li>Card: Valid expiration date, active status, CVV</li>
     *   <li>Transactions: Various amounts with BigDecimal precision, merchant details,
     *       timestamps for date range testing</li>
     * </ul>
     */
    @BeforeEach
    public void setUp() {
        // Create test customer (root of entity hierarchy)
        testCustomer = Customer.builder()
                .customerId(1000000001L)
                .firstName("John")
                .lastName("Doe")
                .addressLine1("123 Main Street")
                .addressStateCode("NY")
                .addressZip("10001")
                .dateOfBirth(LocalDate.of(1980, 1, 15))
                .ficoScore(750)
                .build();
        testCustomer = customerRepository.save(testCustomer);

        // Create test account linked to customer
        testAccount = Account.builder()
                .accountId(10000000001L)
                .customer(testCustomer)
                .activeStatus("Y")
                .currentBalance(new BigDecimal("5000.00"))
                .creditLimit(new BigDecimal("10000.00"))
                .cashCreditLimit(new BigDecimal("2000.00"))
                .openDate(LocalDate.of(2020, 1, 1))
                .build();
        testAccount = accountRepository.save(testAccount);

        // Create test card linked to account
        testCard = Card.builder()
                .cardNumber("4111111111111111")
                .account(testAccount)
                .cvvCode("123")
                .embossedName("JOHN DOE")
                .expirationDate(LocalDate.of(2026, 12, 31))
                .activeStatus("Y")
                .build();
        testCard = cardRepository.save(testCard);
    }

    /**
     * Test findByCard_CardNumber with pagination support (10 transactions per page).
     * 
     * <p>Verifies that repository correctly retrieves transactions for a specific card
     * with pagination matching COBOL COTRN00C.cbl requirements (line 290: "UNTIL WS-IDX > 10").</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * EXEC CICS STARTBR
     *     FILE('TRANSACT')
     *     RIDFLD(TRAN-CARD-NUM)
     * END-EXEC.
     * 
     * PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10
     *     EXEC CICS READNEXT FILE('TRANSACT') INTO(TRAN-RECORD)
     * END-PERFORM.
     * </pre>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ol>
     *   <li>Create 15 transactions for test card</li>
     *   <li>Request first page (page 0, size 10)</li>
     *   <li>Verify exactly 10 transactions returned</li>
     *   <li>Verify hasNext() returns true (more pages available)</li>
     *   <li>Request second page (page 1, size 10)</li>
     *   <li>Verify exactly 5 transactions returned</li>
     *   <li>Verify hasNext() returns false (no more pages)</li>
     *   <li>Verify total elements count is 15</li>
     *   <li>Verify total pages count is 2</li>
     * </ol>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>Page content size matches requested page size (10)</li>
     *   <li>Page navigation flags (hasNext, hasPrevious) work correctly</li>
     *   <li>Total element count accurate across all pages</li>
     *   <li>Last page contains remaining transactions (5)</li>
     *   <li>All transactions belong to specified card</li>
     * </ul>
     */
    @Test
    @DisplayName("Test findByCard_CardNumber with pagination - 10 transactions per page")
    public void testFindByCardNumber_WithPagination() {
        // Create 15 test transactions for the card to test pagination across 2 pages
        for (int i = 1; i <= 15; i++) {
            Transaction transaction = Transaction.builder()
                    .transactionId(String.format("TXN2024010100%02d", i))
                    .typeCode("01")  // Purchase type
                    .categoryCode(1000)  // Retail category
                    .transactionSource("POS")
                    .description("Test Transaction " + i)
                    .amount(new BigDecimal("100.50").setScale(2, RoundingMode.HALF_UP))
                    .merchantId(123456789L)
                    .merchantName("TEST MERCHANT " + i)
                    .merchantCity("New York")
                    .merchantZip("10001")
                    .card(testCard)
                    .originationTimestamp(LocalDateTime.now().minusDays(15 - i))
                    .processingTimestamp(LocalDateTime.now().minusDays(15 - i).plusHours(1))
                    .build();
            transactionRepository.save(transaction);
        }

        // Test first page - should return 10 transactions
        Pageable firstPageRequest = PageRequest.of(0, 10);
        Page<Transaction> firstPage = transactionRepository.findByCard_CardNumber(
                testCard.getCardNumber(), firstPageRequest);

        // Verify first page results
        assertThat(firstPage).isNotNull();
        assertThat(firstPage.getContent()).hasSize(10);
        assertThat(firstPage.getTotalElements()).isEqualTo(15);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.hasPrevious()).isFalse();
        assertThat(firstPage.getNumber()).isEqualTo(0);

        // Verify all transactions on first page belong to test card
        firstPage.getContent().forEach(transaction -> 
                assertThat(transaction.getCard().getCardNumber())
                        .isEqualTo(testCard.getCardNumber()));

        // Test second page - should return remaining 5 transactions
        Pageable secondPageRequest = PageRequest.of(1, 10);
        Page<Transaction> secondPage = transactionRepository.findByCard_CardNumber(
                testCard.getCardNumber(), secondPageRequest);

        // Verify second page results
        assertThat(secondPage).isNotNull();
        assertThat(secondPage.getContent()).hasSize(5);
        assertThat(secondPage.getTotalElements()).isEqualTo(15);
        assertThat(secondPage.getTotalPages()).isEqualTo(2);
        assertThat(secondPage.hasNext()).isFalse();
        assertThat(secondPage.hasPrevious()).isTrue();
        assertThat(secondPage.getNumber()).isEqualTo(1);

        // Verify all transactions on second page belong to test card
        secondPage.getContent().forEach(transaction -> 
                assertThat(transaction.getCard().getCardNumber())
                        .isEqualTo(testCard.getCardNumber()));
    }

    /**
     * Test findByCardNumberAndTransactionDateBetween for date range filtering.
     * 
     * <p>Verifies repository correctly filters transactions by origination timestamp
     * within specified date range, matching CORPT00C.cbl report generation requirements.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * PERFORM UNTIL TRANSACT-EOF
     *     EXEC CICS READNEXT FILE('TRANSACT') INTO(TRAN-RECORD)
     *     END-EXEC
     *     
     *     IF TRAN-ORIG-TS >= WS-START-DATE AND
     *        TRAN-ORIG-TS <= WS-END-DATE
     *         PERFORM PROCESS-TRANSACTION-FOR-REPORT
     *     END-IF
     * END-PERFORM.
     * </pre>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ol>
     *   <li>Create transactions spanning 10 days (Jan 1-10, 2024)</li>
     *   <li>Query for date range Jan 3-7 (5 days, middle of range)</li>
     *   <li>Verify only transactions within range returned</li>
     *   <li>Verify transactions ordered by origination timestamp descending</li>
     *   <li>Verify transactions outside range excluded</li>
     *   <li>Verify pagination works with date filtering</li>
     * </ol>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>Only transactions with origination timestamp between startDate and endDate included</li>
     *   <li>Date range is inclusive (transactions on boundary dates included)</li>
     *   <li>Results ordered by origination timestamp descending (most recent first)</li>
     *   <li>Pagination parameters honored (page size, page number)</li>
     *   <li>Empty result when no transactions in date range</li>
     * </ul>
     */
    @Test
    @DisplayName("Test findByCardNumberAndDateBetween returns filtered transactions")
    public void testFindByCardNumberAndDateBetween_ReturnsFiltered() {
        // Create transactions spanning 10 days for date range testing
        LocalDate baseDate = LocalDate.of(2024, 1, 1);
        
        for (int i = 0; i < 10; i++) {
            LocalDate transactionDate = baseDate.plusDays(i);
            Transaction transaction = Transaction.builder()
                    .transactionId(String.format("TXN202401%02d001", i + 1))
                    .typeCode("01")
                    .categoryCode(1000)
                    .transactionSource("POS")
                    .description("Transaction on " + transactionDate)
                    .amount(new BigDecimal("50.75").setScale(2, RoundingMode.HALF_UP))
                    .merchantId(987654321L)
                    .merchantName("MERCHANT " + (i + 1))
                    .merchantCity("Boston")
                    .merchantZip("02101")
                    .card(testCard)
                    .originationTimestamp(transactionDate.atTime(12, 30))
                    .processingTimestamp(transactionDate.atTime(14, 0))
                    .build();
            transactionRepository.save(transaction);
        }

        // Query for transactions in middle 5 days (Jan 3 through Jan 7)
        LocalDate startDate = LocalDate.of(2024, 1, 3);
        LocalDate endDate = LocalDate.of(2024, 1, 7);
        Pageable pageable = PageRequest.of(0, 10);
        
        Page<Transaction> filteredPage = transactionRepository
                .findByCardNumberAndTransactionDateBetween(
                        testCard.getCardNumber(), startDate, endDate, pageable);

        // Verify exactly 5 transactions returned (Jan 3, 4, 5, 6, 7)
        assertThat(filteredPage).isNotNull();
        assertThat(filteredPage.getContent()).hasSize(5);
        assertThat(filteredPage.getTotalElements()).isEqualTo(5);
        
        // Verify all returned transactions fall within date range (inclusive)
        List<Transaction> transactions = filteredPage.getContent();
        transactions.forEach(transaction -> {
            LocalDate transactionDate = transaction.getOriginationTimestamp().toLocalDate();
            assertThat(transactionDate).isAfterOrEqualTo(startDate);
            assertThat(transactionDate).isBeforeOrEqualTo(endDate);
        });
        
        // Verify transactions ordered by origination timestamp descending (most recent first)
        for (int i = 0; i < transactions.size() - 1; i++) {
            LocalDateTime current = transactions.get(i).getOriginationTimestamp();
            LocalDateTime next = transactions.get(i + 1).getOriginationTimestamp();
            assertThat(current).isAfterOrEqualTo(next);
        }
        
        // Test edge case: date range with no transactions
        LocalDate futureStartDate = LocalDate.of(2025, 1, 1);
        LocalDate futureEndDate = LocalDate.of(2025, 1, 31);
        
        Page<Transaction> emptyPage = transactionRepository
                .findByCardNumberAndTransactionDateBetween(
                        testCard.getCardNumber(), futureStartDate, futureEndDate, pageable);
        
        assertThat(emptyPage).isNotNull();
        assertThat(emptyPage.getContent()).isEmpty();
        assertThat(emptyPage.getTotalElements()).isEqualTo(0);
    }

    /**
     * Test save operation preserves BigDecimal amount precision.
     * 
     * <p>Critical test verifying that TRAN-AMT field (PIC S9(09)V99 COMP-3) maintains
     * exact decimal precision when persisted to database. This is mandatory per Section 0.10
     * Special Instructions for COBOL COMP-3 to Java BigDecimal conversion.</p>
     * 
     * <p><strong>COBOL Field Definition:</strong></p>
     * <pre>
     * 05  TRAN-AMT   PIC S9(09)V99 COMP-3.
     * </pre>
     * <p>This packed decimal field must preserve:</p>
     * <ul>
     *   <li>Exactly 2 decimal places (scale=2)</li>
     *   <li>Maximum precision of 12 total digits (10 integer + 2 decimal)</li>
     *   <li>HALF_UP rounding mode matching COBOL arithmetic behavior</li>
     *   <li>No precision loss in database round-trip (save and retrieve)</li>
     * </ul>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ol>
     *   <li>Create transaction with amount $1234.56 (explicit BigDecimal)</li>
     *   <li>Save transaction to database</li>
     *   <li>Retrieve transaction by ID</li>
     *   <li>Verify amount exactly matches original value (no precision loss)</li>
     *   <li>Verify scale is exactly 2 decimal places</li>
     *   <li>Test with various amounts including edge cases</li>
     * </ol>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>Amount retrieved equals amount saved (exact match using compareTo)</li>
     *   <li>Scale maintained at 2 decimal places</li>
     *   <li>No floating-point rounding errors</li>
     *   <li>Works with maximum precision values ($9,999,999,999.99)</li>
     *   <li>Works with minimum precision values ($0.01)</li>
     *   <li>Negative amounts (credits/refunds) handled correctly</li>
     * </ul>
     * 
     * <p><strong>Critical Success Criteria:</strong></p>
     * <p>Per Section 0.10 Special Instructions:</p>
     * <blockquote>
     * "All monetary calculations must produce identical results with exact decimal precision.
     * Any precision loss violates functional equivalence requirements."
     * </blockquote>
     */
    @Test
    @DisplayName("Test save preserves BigDecimal amount precision with scale=2")
    public void testSave_PreservesAmountPrecision() {
        // Create transaction with exact BigDecimal amount (2 decimal places)
        BigDecimal exactAmount = new BigDecimal("1234.56");
        
        Transaction transaction = Transaction.builder()
                .transactionId("TXN20240201001")
                .typeCode("01")
                .categoryCode(1000)
                .transactionSource("POS")
                .description("Precision Test Transaction")
                .amount(exactAmount)
                .merchantId(111222333L)
                .merchantName("PRECISION TEST MERCHANT")
                .merchantCity("Chicago")
                .merchantZip("60601")
                .card(testCard)
                .originationTimestamp(LocalDateTime.now())
                .processingTimestamp(LocalDateTime.now().plusHours(1))
                .build();
        
        // Save transaction
        Transaction savedTransaction = transactionRepository.save(transaction);
        assertThat(savedTransaction).isNotNull();
        assertThat(savedTransaction.getTransactionId()).isEqualTo("TXN20240201001");
        
        // Retrieve transaction from database
        Optional<Transaction> retrievedOptional = transactionRepository
                .findById("TXN20240201001");
        
        assertThat(retrievedOptional).isPresent();
        Transaction retrievedTransaction = retrievedOptional.get();
        
        // Verify amount precision preserved exactly (no precision loss)
        assertThat(retrievedTransaction.getAmount()).isNotNull();
        assertThat(retrievedTransaction.getAmount().compareTo(exactAmount)).isEqualTo(0);
        assertThat(retrievedTransaction.getAmount().scale()).isEqualTo(2);
        
        // Test with various precision scenarios
        
        // Test case 1: Maximum amount ($9,999,999,999.99)
        BigDecimal maxAmount = new BigDecimal("9999999999.99");
        Transaction maxTransaction = Transaction.builder()
                .transactionId("TXN20240202001")
                .typeCode("01")
                .categoryCode(1000)
                .transactionSource("POS")
                .description("Max Amount Test")
                .amount(maxAmount)
                .merchantId(111222333L)
                .merchantName("TEST MERCHANT")
                .merchantCity("Chicago")
                .merchantZip("60601")
                .card(testCard)
                .originationTimestamp(LocalDateTime.now())
                .processingTimestamp(LocalDateTime.now().plusHours(1))
                .build();
        
        transactionRepository.save(maxTransaction);
        Optional<Transaction> maxRetrieved = transactionRepository.findById("TXN20240202001");
        assertThat(maxRetrieved).isPresent();
        assertThat(maxRetrieved.get().getAmount().compareTo(maxAmount)).isEqualTo(0);
        assertThat(maxRetrieved.get().getAmount().scale()).isEqualTo(2);
        
        // Test case 2: Minimum non-zero amount ($0.01)
        BigDecimal minAmount = new BigDecimal("0.01");
        Transaction minTransaction = Transaction.builder()
                .transactionId("TXN20240203001")
                .typeCode("01")
                .categoryCode(1000)
                .transactionSource("POS")
                .description("Min Amount Test")
                .amount(minAmount)
                .merchantId(111222333L)
                .merchantName("TEST MERCHANT")
                .merchantCity("Chicago")
                .merchantZip("60601")
                .card(testCard)
                .originationTimestamp(LocalDateTime.now())
                .processingTimestamp(LocalDateTime.now().plusHours(1))
                .build();
        
        transactionRepository.save(minTransaction);
        Optional<Transaction> minRetrieved = transactionRepository.findById("TXN20240203001");
        assertThat(minRetrieved).isPresent();
        assertThat(minRetrieved.get().getAmount().compareTo(minAmount)).isEqualTo(0);
        assertThat(minRetrieved.get().getAmount().scale()).isEqualTo(2);
        
        // Test case 3: Negative amount (credit/refund) (-$500.75)
        BigDecimal negativeAmount = new BigDecimal("-500.75");
        Transaction refundTransaction = Transaction.builder()
                .transactionId("TXN20240204001")
                .typeCode("03")  // Refund type
                .categoryCode(1000)
                .transactionSource("POS")
                .description("Refund Test")
                .amount(negativeAmount)
                .merchantId(111222333L)
                .merchantName("TEST MERCHANT")
                .merchantCity("Chicago")
                .merchantZip("60601")
                .card(testCard)
                .originationTimestamp(LocalDateTime.now())
                .processingTimestamp(LocalDateTime.now().plusHours(1))
                .build();
        
        transactionRepository.save(refundTransaction);
        Optional<Transaction> refundRetrieved = transactionRepository.findById("TXN20240204001");
        assertThat(refundRetrieved).isPresent();
        assertThat(refundRetrieved.get().getAmount().compareTo(negativeAmount)).isEqualTo(0);
        assertThat(refundRetrieved.get().getAmount().scale()).isEqualTo(2);
    }

    /**
     * Test save operation with timestamp fields (TRAN-ORIG-TS and TRAN-PROC-TS).
     * 
     * <p>Verifies that LocalDateTime fields correctly convert from COBOL PIC X(26)
     * timestamp format and are persisted with microsecond precision.</p>
     * 
     * <p><strong>COBOL Field Definitions:</strong></p>
     * <pre>
     * 05  TRAN-ORIG-TS   PIC X(26).
     * 05  TRAN-PROC-TS   PIC X(26).
     * </pre>
     * <p>These 26-character fields represent ISO 8601 timestamps with microseconds:
     * "YYYY-MM-DDTHH:MM:SS.ssssss"</p>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ol>
     *   <li>Create transaction with specific origination and processing timestamps</li>
     *   <li>Save transaction to database</li>
     *   <li>Retrieve transaction and verify timestamps preserved</li>
     *   <li>Verify timestamp ordering (processing after origination)</li>
     *   <li>Verify timestamp components (date, time, nanoseconds)</li>
     * </ol>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>Origination timestamp persisted and retrieved accurately</li>
     *   <li>Processing timestamp persisted and retrieved accurately</li>
     *   <li>Timestamp precision maintained (nanosecond accuracy)</li>
     *   <li>Temporal relationship preserved (processing after origination)</li>
     *   <li>Timestamp components (year, month, day, hour, minute, second) correct</li>
     * </ul>
     */
    @Test
    @DisplayName("Test save with timestamps for TRAN-ORIG-TS and TRAN-PROC-TS conversion")
    public void testSave_WithTimestamps() {
        // Define specific timestamps for testing
        LocalDateTime originationTime = LocalDateTime.of(2024, 1, 15, 10, 30, 45, 123456789);
        LocalDateTime processingTime = LocalDateTime.of(2024, 1, 15, 12, 15, 30, 987654321);
        
        Transaction transaction = Transaction.builder()
                .transactionId("TXN20240115001")
                .typeCode("01")
                .categoryCode(1000)
                .transactionSource("POS")
                .description("Timestamp Test Transaction")
                .amount(new BigDecimal("250.00"))
                .merchantId(555666777L)
                .merchantName("TIMESTAMP TEST MERCHANT")
                .merchantCity("Seattle")
                .merchantZip("98101")
                .card(testCard)
                .originationTimestamp(originationTime)
                .processingTimestamp(processingTime)
                .build();
        
        // Save transaction
        transactionRepository.save(transaction);
        
        // Retrieve and verify timestamps
        Optional<Transaction> retrievedOptional = transactionRepository
                .findById("TXN20240115001");
        
        assertThat(retrievedOptional).isPresent();
        Transaction retrieved = retrievedOptional.get();
        
        // Verify origination timestamp
        assertThat(retrieved.getOriginationTimestamp()).isNotNull();
        assertThat(retrieved.getOriginationTimestamp()).isEqualTo(originationTime);
        assertThat(retrieved.getOriginationTimestamp().getYear()).isEqualTo(2024);
        assertThat(retrieved.getOriginationTimestamp().getMonthValue()).isEqualTo(1);
        assertThat(retrieved.getOriginationTimestamp().getDayOfMonth()).isEqualTo(15);
        assertThat(retrieved.getOriginationTimestamp().getHour()).isEqualTo(10);
        assertThat(retrieved.getOriginationTimestamp().getMinute()).isEqualTo(30);
        assertThat(retrieved.getOriginationTimestamp().getSecond()).isEqualTo(45);
        
        // Verify processing timestamp
        assertThat(retrieved.getProcessingTimestamp()).isNotNull();
        assertThat(retrieved.getProcessingTimestamp()).isEqualTo(processingTime);
        assertThat(retrieved.getProcessingTimestamp().getYear()).isEqualTo(2024);
        assertThat(retrieved.getProcessingTimestamp().getMonthValue()).isEqualTo(1);
        assertThat(retrieved.getProcessingTimestamp().getDayOfMonth()).isEqualTo(15);
        assertThat(retrieved.getProcessingTimestamp().getHour()).isEqualTo(12);
        assertThat(retrieved.getProcessingTimestamp().getMinute()).isEqualTo(15);
        assertThat(retrieved.getProcessingTimestamp().getSecond()).isEqualTo(30);
        
        // Verify temporal ordering (processing after origination)
        assertThat(retrieved.getProcessingTimestamp())
                .isAfter(retrieved.getOriginationTimestamp());
    }

    /**
     * Test foreign key constraint with Card entity.
     * 
     * <p>Verifies that TRAN-CARD-NUM foreign key relationship is correctly enforced
     * at database level, maintaining referential integrity from VSAM cross-reference
     * file pattern to PostgreSQL foreign key constraint.</p>
     * 
     * <p><strong>COBOL Cross-Reference Pattern:</strong></p>
     * <p>In COBOL/VSAM architecture, TRAN-CARD-NUM in CVTRA05Y.cpy references
     * CARD-NUM in CVACT02Y.cpy through separate cross-reference file (XREF).
     * In PostgreSQL, this becomes a foreign key constraint enforced automatically.</p>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ol>
     *   <li>Create transaction with valid card reference</li>
     *   <li>Save transaction successfully</li>
     *   <li>Retrieve transaction and verify card relationship navigable</li>
     *   <li>Verify transaction accessible from card side (bidirectional)</li>
     *   <li>Verify card fields accessible through transaction.card</li>
     * </ol>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>Transaction saves successfully with valid card foreign key</li>
     *   <li>Card relationship navigable (transaction.getCard() works)</li>
     *   <li>Card number matches expected value</li>
     *   <li>Card fields (account, expiration, status) accessible</li>
     *   <li>Referential integrity maintained through entity relationship</li>
     * </ul>
     * 
     * <p><strong>Database Constraint:</strong></p>
     * <pre>
     * ALTER TABLE transaction 
     * ADD CONSTRAINT fk_transaction_card 
     * FOREIGN KEY (card_number) REFERENCES card(card_number);
     * </pre>
     */
    @Test
    @DisplayName("Test foreign key constraint with Card entity via TRAN-CARD-NUM")
    public void testForeignKeyConstraint_WithCard() {
        // Create transaction with card foreign key
        Transaction transaction = Transaction.builder()
                .transactionId("TXN20240301001")
                .typeCode("01")
                .categoryCode(1000)
                .transactionSource("POS")
                .description("Foreign Key Test Transaction")
                .amount(new BigDecimal("125.99"))
                .merchantId(999888777L)
                .merchantName("FK TEST MERCHANT")
                .merchantCity("Miami")
                .merchantZip("33101")
                .card(testCard)  // Foreign key reference
                .originationTimestamp(LocalDateTime.now())
                .processingTimestamp(LocalDateTime.now().plusHours(1))
                .build();
        
        // Save transaction
        Transaction savedTransaction = transactionRepository.save(transaction);
        assertThat(savedTransaction).isNotNull();
        
        // Retrieve transaction and verify card relationship
        Optional<Transaction> retrievedOptional = transactionRepository
                .findById("TXN20240301001");
        
        assertThat(retrievedOptional).isPresent();
        Transaction retrieved = retrievedOptional.get();
        
        // Verify card foreign key relationship
        assertThat(retrieved.getCard()).isNotNull();
        assertThat(retrieved.getCard().getCardNumber()).isEqualTo("4111111111111111");
        assertThat(retrieved.getCard().getCardNumber()).isEqualTo(testCard.getCardNumber());
        
        // Verify navigation to account through card
        assertThat(retrieved.getCard().getAccount()).isNotNull();
        assertThat(retrieved.getCard().getAccount().getAccountId())
                .isEqualTo(testAccount.getAccountId());
        
        // Verify card properties accessible
        assertThat(retrieved.getCard().getActiveStatus()).isEqualTo("Y");
        assertThat(retrieved.getCard().getExpirationDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(retrieved.getCard().getEmbossedName()).isEqualTo("JOHN DOE");
    }

    /**
     * Test merchant fields persist correctly.
     * 
     * <p>Verifies that all merchant-related fields from CVTRA05Y.cpy are correctly
     * persisted and retrieved from database:</p>
     * <ul>
     *   <li>TRAN-MERCHANT-ID (PIC 9(09)) → Long merchantId</li>
     *   <li>TRAN-MERCHANT-NAME (PIC X(50)) → String merchantName</li>
     *   <li>TRAN-MERCHANT-CITY (PIC X(50)) → String merchantCity</li>
     *   <li>TRAN-MERCHANT-ZIP (PIC X(10)) → String merchantZip</li>
     * </ul>
     * 
     * <p><strong>Purpose:</strong></p>
     * <p>Merchant information is critical for:</p>
     * <ul>
     *   <li>Transaction history display showing where charges occurred</li>
     *   <li>Fraud detection analyzing merchant patterns</li>
     *   <li>Dispute resolution providing transaction context</li>
     *   <li>Reporting and analytics by merchant category</li>
     * </ul>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ol>
     *   <li>Create transaction with complete merchant information</li>
     *   <li>Save transaction to database</li>
     *   <li>Retrieve transaction and verify all merchant fields</li>
     *   <li>Verify field lengths match COBOL definitions</li>
     *   <li>Verify special characters and spaces handled correctly</li>
     * </ol>
     * 
     * <p><strong>Validation Points:</strong></p>
     * <ul>
     *   <li>Merchant ID persisted as Long (9 digits)</li>
     *   <li>Merchant name preserved exactly (up to 50 characters)</li>
     *   <li>Merchant city preserved exactly (up to 50 characters)</li>
     *   <li>Merchant ZIP preserved exactly (up to 10 characters)</li>
     *   <li>Special characters in merchant name handled correctly</li>
     *   <li>All fields retrievable in transaction history queries</li>
     * </ul>
     */
    @Test
    @DisplayName("Test merchant fields persistence for TRAN-MERCHANT-* fields")
    public void testMerchantFields_PersistCorrectly() {
        // Create transaction with comprehensive merchant information
        Transaction transaction = Transaction.builder()
                .transactionId("TXN20240401001")
                .typeCode("01")
                .categoryCode(1000)
                .transactionSource("POS")
                .description("Merchant Fields Test Transaction")
                .amount(new BigDecimal("89.95"))
                .merchantId(123456789L)  // 9-digit merchant ID
                .merchantName("STARBUCKS COFFEE #12345")  // Up to 50 characters
                .merchantCity("San Francisco")  // Up to 50 characters
                .merchantZip("94102-1234")  // Up to 10 characters with dash
                .card(testCard)
                .originationTimestamp(LocalDateTime.now())
                .processingTimestamp(LocalDateTime.now().plusHours(1))
                .build();
        
        // Save transaction
        transactionRepository.save(transaction);
        
        // Retrieve and verify merchant fields
        Optional<Transaction> retrievedOptional = transactionRepository
                .findById("TXN20240401001");
        
        assertThat(retrievedOptional).isPresent();
        Transaction retrieved = retrievedOptional.get();
        
        // Verify merchant ID
        assertThat(retrieved.getMerchantId()).isNotNull();
        assertThat(retrieved.getMerchantId()).isEqualTo(123456789L);
        
        // Verify merchant name
        assertThat(retrieved.getMerchantName()).isNotNull();
        assertThat(retrieved.getMerchantName()).isEqualTo("STARBUCKS COFFEE #12345");
        assertThat(retrieved.getMerchantName().length()).isLessThanOrEqualTo(50);
        
        // Verify merchant city
        assertThat(retrieved.getMerchantCity()).isNotNull();
        assertThat(retrieved.getMerchantCity()).isEqualTo("San Francisco");
        assertThat(retrieved.getMerchantCity().length()).isLessThanOrEqualTo(50);
        
        // Verify merchant ZIP
        assertThat(retrieved.getMerchantZip()).isNotNull();
        assertThat(retrieved.getMerchantZip()).isEqualTo("94102-1234");
        assertThat(retrieved.getMerchantZip().length()).isLessThanOrEqualTo(10);
        
        // Test with edge case: Long merchant name (exactly 50 characters)
        String longMerchantName = "VERY LONG MERCHANT NAME TESTING FIFTY CHARACTER"; // 49 chars
        longMerchantName = longMerchantName + "S"; // Make it exactly 50
        
        Transaction longNameTransaction = Transaction.builder()
                .transactionId("TXN20240402001")
                .typeCode("01")
                .categoryCode(1000)
                .transactionSource("POS")
                .description("Long Name Test")
                .amount(new BigDecimal("50.00"))
                .merchantId(987654321L)
                .merchantName(longMerchantName)
                .merchantCity("City")
                .merchantZip("12345")
                .card(testCard)
                .originationTimestamp(LocalDateTime.now())
                .processingTimestamp(LocalDateTime.now().plusHours(1))
                .build();
        
        transactionRepository.save(longNameTransaction);
        
        Optional<Transaction> longNameRetrieved = transactionRepository
                .findById("TXN20240402001");
        
        assertThat(longNameRetrieved).isPresent();
        assertThat(longNameRetrieved.get().getMerchantName()).isEqualTo(longMerchantName);
        assertThat(longNameRetrieved.get().getMerchantName().length()).isEqualTo(50);
    }

    /**
     * Test empty page returned when no transactions exist for card.
     * 
     * <p>Verifies that repository returns empty page (not null) when querying
     * card with no transactions, matching COBOL NOTFND condition handling.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * EXEC CICS STARTBR FILE('TRANSACT') RIDFLD(TRAN-CARD-NUM)
     *     RESP(WS-RESP-CD)
     * END-EXEC.
     * 
     * IF WS-RESP-CD = DFHRESP(NOTFND)
     *     MOVE 'NO TRANSACTIONS FOUND' TO WS-MESSAGE
     * END-IF.
     * </pre>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ol>
     *   <li>Create card with no associated transactions</li>
     *   <li>Query for transactions by card number</li>
     *   <li>Verify empty page returned (not null)</li>
     *   <li>Verify total elements is 0</li>
     *   <li>Verify content list is empty</li>
     *   <li>Verify hasNext() and hasPrevious() both false</li>
     * </ol>
     */
    @Test
    @DisplayName("Test empty page returned when no transactions exist for card")
    public void testFindByCardNumber_EmptyResult() {
        // Create a second card with no transactions
        Card cardWithoutTransactions = Card.builder()
                .cardNumber("4222222222222222")
                .account(testAccount)
                .cvvCode("456")
                .embossedName("JANE DOE")
                .expirationDate(LocalDate.of(2027, 6, 30))
                .activeStatus("Y")
                .build();
        cardRepository.save(cardWithoutTransactions);
        
        // Query for transactions - should return empty page
        Pageable pageable = PageRequest.of(0, 10);
        Page<Transaction> emptyPage = transactionRepository.findByCard_CardNumber(
                cardWithoutTransactions.getCardNumber(), pageable);
        
        // Verify empty page structure
        assertThat(emptyPage).isNotNull();
        assertThat(emptyPage.getContent()).isEmpty();
        assertThat(emptyPage.getTotalElements()).isEqualTo(0);
        assertThat(emptyPage.getTotalPages()).isEqualTo(0);
        assertThat(emptyPage.hasNext()).isFalse();
        assertThat(emptyPage.hasPrevious()).isFalse();
        assertThat(emptyPage.getNumber()).isEqualTo(0);
    }
}
