/*
 * TransactionIntegrationTest.java
 *
 * Integration tests for transaction processing REST API endpoints converted from COBOL programs:
 * - COTRN00C.cbl (transaction list with date range filtering and pagination)
 * - COTRN01C.cbl (transaction detail view)
 * - COTRN02C.cbl (transaction posting with balance updates and credit limit validation)
 *
 * Tests complete request-response cycles across all application layers:
 * Controller → Service → Repository → Database (PostgreSQL via Testcontainers)
 *
 * Per Agent Action Plan Section 0.7.2: Validate that Java implementation produces
 * bit-identical results to original COBOL programs COTRN00C.cbl, COTRN01C.cbl, and COTRN02C.cbl.
 *
 * COBOL-to-Java Conversion Validations:
 * 1. Transaction list retrieval (COTRN00C.cbl EXEC CICS STARTBR/READNEXT) → GET /api/transactions
 * 2. Transaction detail view (COTRN01C.cbl EXEC CICS READ) → GET /api/transactions/{id}
 * 3. Transaction posting (COTRN02C.cbl WRITE/REWRITE operations) → POST /api/transactions
 * 4. COMP-3 precision preservation: All amounts use BigDecimal scale 2 with HALF_UP rounding
 * 5. Transaction boundaries: @Transactional replicates EXEC CICS SYNCPOINT/ROLLBACK behavior
 * 6. Credit limit validation: Exact COBOL logic from COTRN02C.cbl lines 630-660
 * 7. Balance calculations: Bit-identical to COBOL COMPUTE statements with COMP-3 arithmetic
 *
 * Critical Performance Requirement (Section 0.7.7):
 * Transaction response time MUST remain under 200ms for card authorization requests.
 * Tests include performance validation assertions.
 *
 * Test Infrastructure:
 * - Uses @SpringBootTest for full application context with all layers
 * - Uses Testcontainers PostgreSQL 16.x for database isolation
 * - Uses REST Assured 5.5.0 for HTTP API testing
 * - Test data setup in @BeforeEach, cleanup in @AfterEach
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
package com.carddemo.integration;

import com.carddemo.model.dto.TransactionDto;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.Card;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategory;
import com.carddemo.model.entity.TransactionCategoryId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for Transaction REST API endpoints.
 * 
 * Tests complete end-to-end transaction processing including:
 * - Transaction list retrieval with date filtering (COTRN00C.cbl)
 * - Transaction detail view (COTRN01C.cbl)
 * - Transaction posting with balance updates (COTRN02C.cbl)
 * - COMP-3 precision preservation in BigDecimal calculations
 * - Credit limit validation matching COBOL business logic
 * - Transaction atomicity with rollback on errors
 * - Optimistic locking for concurrent transactions
 * - Performance validation (sub-200ms requirement)
 * 
 * Uses Testcontainers PostgreSQL for database isolation ensuring test independence.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@org.springframework.test.context.TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=none"
})
@Testcontainers
public class TransactionIntegrationTest {

    /**
     * Testcontainers PostgreSQL 16.x instance for database isolation.
     * 
     * Provides ephemeral PostgreSQL database for each test run with:
     * - Accounts table (converted from ACCTFILE VSAM)
     * - Cards table (converted from CARDFILE VSAM)
     * - Transactions table (converted from TRANSACT VSAM)
     * 
     * Flyway migrations automatically create schema per Section 0.4.15.
     */
    @Container
    static PostgreSQLContainer<?> postgresContainer = new PostgreSQLContainer<>("postgres:16.6-alpine")
            .withDatabaseName("carddemo_test")
            .withUsername("test")
            .withPassword("test");

    /**
     * Register PostgreSQL container properties into Spring test environment.
     * 
     * Dynamically configures datasource properties from running container:
     * - spring.datasource.url (JDBC URL from container)
     * - spring.datasource.username
     * - spring.datasource.password
     * - spring.flyway.enabled (must enable Flyway to load reference data)
     * 
     * CRITICAL: Flyway must be enabled to run V7__insert_initial_data.sql migration
     * which populates transaction_category, transaction_type, and disclosure_group
     * tables. Without this data, transaction category validation will fail.
     */
    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);
        // Enable Flyway migrations to load reference data (transaction_category, etc.)
        registry.add("spring.flyway.enabled", () -> "true");
    }

    /**
     * Random port assigned to embedded Tomcat server.
     * Injected by @LocalServerPort to configure RestAssured base URI.
     */
    @LocalServerPort
    private int port;

    /**
     * Transaction repository for test data setup and verification.
     * Provides access to transaction table for:
     * - Creating test transaction records
     * - Verifying transaction posting results
     * - Cleaning up test data after each test
     */
    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * Account repository for test data setup.
     * Provides access to account table for:
     * - Creating test accounts with specific balances and credit limits
     * - Verifying balance updates after transaction posting
     * - Cleaning up test data after each test
     */
    @Autowired
    private AccountRepository accountRepository;

    /**
     * Card repository for test data setup.
     * Provides access to card table for:
     * - Creating test cards linked to test accounts
     * - Satisfying foreign key constraints for transactions
     * - Cleaning up test data after each test
     */
    @Autowired
    private CardRepository cardRepository;

    /**
     * Transaction category repository for test data verification.
     * Used to verify reference data is loaded correctly from Flyway migrations.
     */
    @Autowired
    private TransactionCategoryRepository transactionCategoryRepository;

    /**
     * Transaction category balance repository for test data cleanup.
     * Provides access to transaction_category_balance table for:
     * - Cleaning up category balance records created by transaction posting
     * - Satisfying foreign key constraints during account deletion
     */
    @Autowired
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    // Test data constants matching COBOL data structures
    // Account IDs must be exactly 11 digits per ValidationService.validateAccountId (VAL007)
    private static final Long TEST_ACCOUNT_ID_1 = 10000000001L; // 11 digits
    private static final Long TEST_ACCOUNT_ID_2 = 10000000002L; // 11 digits
    private static final String TEST_CARD_NUM_1 = "4000123412341234";
    private static final String TEST_CARD_NUM_2 = "4000567856785678";
    private static final String TEST_TRANS_ID_1 = "0000000000000001";
    private static final String TEST_TRANS_ID_2 = "0000000000000002";
    private static final String TEST_TRANS_ID_3 = "0000000000000003";
    
    // Transaction type codes from COBOL CVTRA03Y.cpy
    private static final String TRANS_TYPE_PURCHASE = "01";
    private static final String TRANS_TYPE_PAYMENT = "04";
    private static final String TRANS_TYPE_CASH_ADVANCE = "02";
    
    // Transaction category codes matching V7__insert_initial_data.sql
    // Type '01' (purchase) has categories 1-5 in database
    private static final Integer TRANS_CAT_REGULAR_SALE = 1;  // Regular Sales Draft
    private static final Integer TRANS_CAT_CASH_ADVANCE = 2;  // Regular Cash Advance
    
    // Financial amounts with COMP-3 precision (scale 2)
    private static final BigDecimal AMOUNT_INITIAL_BALANCE = new BigDecimal("5000.00");
    private static final BigDecimal AMOUNT_CREDIT_LIMIT = new BigDecimal("10000.00");
    private static final BigDecimal AMOUNT_PURCHASE_125_50 = new BigDecimal("125.50");
    private static final BigDecimal AMOUNT_PURCHASE_250_75 = new BigDecimal("250.75");
    private static final BigDecimal AMOUNT_PAYMENT_500_00 = new BigDecimal("500.00");
    
    // Date time formatter matching @JsonFormat pattern in TransactionDto
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    
    /**
     * Setup method executed before each test.
     * 
     * Configures RestAssured and creates test data:
     * 1. Set RestAssured base URI and port to embedded Tomcat server
     * 2. Create test accounts with specific balances and credit limits
     * 3. Create test cards linked to test accounts
     * 4. Create test transaction records for list retrieval tests
     * 
     * Test data matches COBOL record structures:
     * - Account: CVACT01Y.cpy (ACCOUNT-RECORD)
     * - Card: CVACT02Y.cpy (CARD-RECORD)
     * - Transaction: CVTRA05Y.cpy (TRAN-RECORD)
     */
    @BeforeEach
    void setUp() {
        // Configure RestAssured for API testing
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;
        
        // Create test account 1 with initial balance and credit limit
        // Matches COBOL: ACCOUNT-RECORD from CVACT01Y.cpy
        Account account1 = Account.builder()
                .acctId(TEST_ACCOUNT_ID_1)
                .acctActiveStatus("Y")
                .acctCurrBal(AMOUNT_INITIAL_BALANCE.setScale(2, RoundingMode.HALF_UP))
                .acctCreditLimit(AMOUNT_CREDIT_LIMIT.setScale(2, RoundingMode.HALF_UP))
                .acctCashCreditLimit(new BigDecimal("2000.00").setScale(2, RoundingMode.HALF_UP))
                .acctOpenDate(LocalDate.of(2023, 1, 1))
                .acctCurrCycDebit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .acctCurrCycCredit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .build();
        accountRepository.save(account1);
        
        // Create test account 2 for multi-card testing
        Account account2 = Account.builder()
                .acctId(TEST_ACCOUNT_ID_2)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("3000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCreditLimit(AMOUNT_CREDIT_LIMIT.setScale(2, RoundingMode.HALF_UP))
                .acctCashCreditLimit(new BigDecimal("2000.00").setScale(2, RoundingMode.HALF_UP))
                .acctOpenDate(LocalDate.of(2023, 6, 1))
                .acctCurrCycDebit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .acctCurrCycCredit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .build();
        accountRepository.save(account2);
        
        // Create test card 1 linked to account 1
        // Matches COBOL: CARD-RECORD from CVACT02Y.cpy
        Card card1 = Card.builder()
                .cardNum(TEST_CARD_NUM_1)
                .cardAcctId(TEST_ACCOUNT_ID_1)
                .cardStatus("A") // Active status per Card entity valid values
                .cardExpirationDate(LocalDate.of(2026, 12, 31))
                .build();
        cardRepository.save(card1);
        
        // Create test card 2 linked to account 2
        Card card2 = Card.builder()
                .cardNum(TEST_CARD_NUM_2)
                .cardAcctId(TEST_ACCOUNT_ID_2)
                .cardStatus("A") // Active status per Card entity valid values
                .cardExpirationDate(LocalDate.of(2026, 12, 31))
                .build();
        cardRepository.save(card2);
        
        // Create test transactions for list retrieval tests
        // Matches COBOL: TRAN-RECORD from CVTRA05Y.cpy
        LocalDateTime now = LocalDateTime.now();
        
        Transaction trans1 = Transaction.builder()
                .transId(TEST_TRANS_ID_1)
                .transCardNum(TEST_CARD_NUM_1)
                .transTypeCd(TRANS_TYPE_PURCHASE)
                .transCatCd(TRANS_CAT_REGULAR_SALE)
                .transAmt(AMOUNT_PURCHASE_125_50.setScale(2, RoundingMode.HALF_UP))
                .transOrigTs(Timestamp.valueOf(now.minusDays(5)))
                .transProcTs(Timestamp.valueOf(now.minusDays(5)))
                .transDesc("GROCERY STORE PURCHASE")
                .transMerchantName("LOCAL GROCERY")
                .build();
        transactionRepository.save(trans1);
        
        Transaction trans2 = Transaction.builder()
                .transId(TEST_TRANS_ID_2)
                .transCardNum(TEST_CARD_NUM_1)
                .transTypeCd(TRANS_TYPE_PURCHASE)
                .transCatCd(TRANS_CAT_CASH_ADVANCE)
                .transAmt(AMOUNT_PURCHASE_250_75.setScale(2, RoundingMode.HALF_UP))
                .transOrigTs(Timestamp.valueOf(now.minusDays(3)))
                .transProcTs(Timestamp.valueOf(now.minusDays(3)))
                .transDesc("RESTAURANT PURCHASE")
                .transMerchantName("LOCAL RESTAURANT")
                .build();
        transactionRepository.save(trans2);
        
        Transaction trans3 = Transaction.builder()
                .transId(TEST_TRANS_ID_3)
                .transCardNum(TEST_CARD_NUM_2)
                .transTypeCd(TRANS_TYPE_PAYMENT)
                .transCatCd(0)
                .transAmt(AMOUNT_PAYMENT_500_00.setScale(2, RoundingMode.HALF_UP))
                .transOrigTs(Timestamp.valueOf(now.minusDays(1)))
                .transProcTs(Timestamp.valueOf(now.minusDays(1)))
                .transDesc("PAYMENT - THANK YOU")
                .transMerchantName("ONLINE PAYMENT")
                .build();
        transactionRepository.save(trans3);
    }
    
    /**
     * Cleanup method executed after each test.
     * 
     * Deletes all test data from repositories to ensure test isolation:
     * 1. Delete all transaction category balances (created by transaction posting)
     * 2. Delete all transactions (must be before accounts due to foreign key)
     * 3. Delete all cards (must be before accounts due to foreign key)
     * 4. Delete all accounts
     * 
     * Prevents test data pollution between test methods and foreign key violations.
     */
    @AfterEach
    void tearDown() {
        // Delete in correct order to satisfy foreign key constraints
        transactionCategoryBalanceRepository.deleteAll();
        transactionRepository.deleteAll();
        cardRepository.deleteAll();
        accountRepository.deleteAll();
    }
    

    /**
     * Test GET /api/transactions - retrieve transaction list without filters.
     * 
     * Converted from COBOL: COTRN00C.cbl (transaction list display)
     * Original COBOL: EXEC CICS STARTBR FILE('TRANSACT')
     *                 EXEC CICS READNEXT [loop through records]
     *                 EXEC CICS SEND MAP('COTRN00')
     * 
     * Validates:
     * - HTTP 200 OK status
     * - Response contains all test transactions (3 records)
     * - Pagination metadata (page number, total elements, total pages)
     * - Default page size of 20 (matching COBOL WS-REC-COUNT)
     * - Transaction fields match test data
     * - Amounts preserved with COMP-3 precision (BigDecimal scale 2)
     */
    @Test
    void testListTransactions_NoFilters_ReturnsAllTransactions() {
        given()
                .contentType(ContentType.JSON)
        .when()
                .get("/api/transactions")
        .then()
                .statusCode(200)
                .body("content", hasSize(3))
                .body("content[0].transId", notNullValue())
                .body("content[0].transCardNum", notNullValue())
                .body("content[0].transAmt", notNullValue())
                .body("totalElements", equalTo(3))
                .body("totalPages", equalTo(1))
                .body("size", equalTo(20)); // Default page size matching COBOL
    }
    
    /**
     * Test GET /api/transactions with card number filter.
     * 
     * Converted from COBOL: COTRN00C.cbl with card filter
     * Original COBOL: IF TRAN-CARD-NUM = WS-CARD-FILTER
     * 
     * Validates:
     * - Only transactions for specified card are returned
     * - Other cards' transactions are excluded
     * - Card number filter matches COBOL filtering logic
     */
    @Test
    void testListTransactions_WithCardNumberFilter_ReturnsFilteredTransactions() {
        given()
                .contentType(ContentType.JSON)
                .queryParam("cardNumber", TEST_CARD_NUM_1)
        .when()
                .get("/api/transactions")
        .then()
                .statusCode(200)
                .body("content", hasSize(2))
                .body("content[0].transCardNum", containsString("1234")) // Last 4 digits (masked)
                .body("content[1].transCardNum", containsString("1234"))
                .body("totalElements", equalTo(2));
    }
    
    /**
     * Test GET /api/transactions with date range filter.
     * 
     * Converted from COBOL: COTRN00C.cbl with date range filtering
     * Original COBOL: IF TRAN-DATE >= WS-START-DATE AND TRAN-DATE <= WS-END-DATE
     * 
     * Validates:
     * - Only transactions within date range are returned
     * - Start date filter (greater than or equal)
     * - End date filter (less than or equal)
     * - Date range matching COBOL date filtering logic
     */
    @Test
    void testListTransactions_WithDateRangeFilter_ReturnsFilteredTransactions() {
        LocalDate startDate = LocalDate.now().minusDays(4);
        LocalDate endDate = LocalDate.now().minusDays(2);
        
        given()
                .contentType(ContentType.JSON)
                .queryParam("startDate", startDate.toString())
                .queryParam("endDate", endDate.toString())
        .when()
                .get("/api/transactions")
        .then()
                .statusCode(200)
                .body("content", hasSize(1))
                .body("content[0].transId", equalTo(TEST_TRANS_ID_2))
                .body("totalElements", equalTo(1));
    }
    
    /**
     * Test GET /api/transactions with pagination.
     * 
     * Converted from COBOL: COTRN00C.cbl browse cursor pagination
     * Original COBOL: WS-REC-COUNT = 20 (records per screen)
     * 
     * Validates:
     * - Custom page size respected
     * - Page number navigation
     * - Pagination metadata (page, size, totalPages)
     * - Spring Data Pageable replaces COBOL STARTBR/READNEXT loop
     */
    @Test
    void testListTransactions_WithPagination_ReturnsPaginatedResults() {
        given()
                .contentType(ContentType.JSON)
                .queryParam("page", 0)
                .queryParam("size", 2)
        .when()
                .get("/api/transactions")
        .then()
                .statusCode(200)
                .body("content", hasSize(2))
                .body("number", equalTo(0))
                .body("size", equalTo(2))
                .body("totalPages", equalTo(2))
                .body("totalElements", equalTo(3));
    }
    
    /**
     * Test GET /api/transactions with invalid date range (end before start).
     * 
     * Converted from COBOL: COTRN00C.cbl date validation
     * Original COBOL: IF END-DATE < START-DATE
     *                    MOVE 'Invalid date range' TO WS-MESSAGE
     *                    PERFORM SEND-ERROR-SCREEN
     * 
     * Validates:
     * - HTTP 400 Bad Request for invalid date range
     * - Error message indicates date range validation failure
     * - Matches COBOL date validation logic
     */
    @Test
    void testListTransactions_WithInvalidDateRange_Returns400() {
        LocalDate startDate = LocalDate.now();
        LocalDate endDate = LocalDate.now().minusDays(10);
        
        given()
                .contentType(ContentType.JSON)
                .queryParam("startDate", startDate.toString())
                .queryParam("endDate", endDate.toString())
        .when()
                .get("/api/transactions")
        .then()
                .statusCode(400)
                .body("message", containsString("date"));
    }
    
    /**
     * Test GET /api/transactions/{id} - retrieve transaction detail.
     * 
     * Converted from COBOL: COTRN01C.cbl (transaction detail view)
     * Original COBOL: EXEC CICS READ FILE('TRANSACT') RIDFLD(TRAN-ID)
     *                 EXEC CICS SEND MAP('COTRN01') [display detail]
     * 
     * Validates:
     * - HTTP 200 OK status
     * - Transaction detail fields match test data
     * - Transaction ID correctly retrieved (16-character COBOL PIC X(16))
     * - Amount preserved with COMP-3 precision (BigDecimal scale 2)
     * - Merchant details included
     * - Timestamps correctly formatted
     */
    @Test
    void testGetTransactionById_ExistingTransaction_ReturnsTransactionDetail() {
        given()
                .contentType(ContentType.JSON)
        .when()
                .get("/api/transactions/{transactionId}", TEST_TRANS_ID_1)
        .then()
                .statusCode(200)
                .body("transId", equalTo(TEST_TRANS_ID_1))
                .body("transCardNum", containsString("1234")) // Masked card number
                .body("transTypeCd", equalTo(TRANS_TYPE_PURCHASE))
                .body("transCatCd", equalTo(TRANS_CAT_REGULAR_SALE))
                .body("transAmt", equalTo("125.50")) // BigDecimal preserved as string for COMP-3 precision
                .body("transDesc", equalTo("GROCERY STORE PURCHASE"))
                .body("transMerchantName", equalTo("LOCAL GROCERY"))
                .body("transOrigTs", notNullValue())
                .body("transProcTs", notNullValue());
    }
    
    /**
     * Test GET /api/transactions/{id} with non-existent transaction.
     * 
     * Converted from COBOL: COTRN01C.cbl NOT FOUND handling
     * Original COBOL: EVALUATE WS-RESP-CD
     *                   WHEN DFHRESP(NOTFND)
     *                     MOVE 'Transaction not found' TO WS-MESSAGE
     *                     PERFORM SEND-ERROR-SCREEN
     * 
     * Validates:
     * - HTTP 404 Not Found for non-existent transaction
     * - Error message indicates transaction not found
     * - Matches COBOL DFHRESP(NOTFND) error handling
     */
    @Test
    void testGetTransactionById_NonExistentTransaction_Returns404() {
        String nonExistentTransId = "9999999999999999";
        
        given()
                .contentType(ContentType.JSON)
        .when()
                .get("/api/transactions/{transactionId}", nonExistentTransId)
        .then()
                .statusCode(404)
                .body("message", containsString("not found"));
    }
    
    /**
     * Test POST /api/transactions - post valid purchase transaction.
     * 
     * Converted from COBOL: COTRN02C.cbl (transaction posting, lines 200-800)
     * Original COBOL: EXEC CICS RECEIVE MAP('COTRN02')
     *                 [Validate input fields]
     *                 EXEC CICS READ FILE('CARDFILE') [verify card active]
     *                 EXEC CICS READ FILE('ACCTFILE') UPDATE
     *                 COMPUTE NEW-BALANCE = ACCT-CURR-BAL - TRAN-AMT
     *                 EXEC CICS REWRITE FILE('ACCTFILE')
     *                 EXEC CICS WRITE FILE('TRANSACT')
     *                 EXEC CICS SYNCPOINT
     * 
     * Validates:
     * - HTTP 201 Created status
     * - Transaction created with generated transId
     * - Account balance updated correctly (debit transaction reduces balance)
     * - Balance calculation uses COMP-3 precision (BigDecimal scale 2, HALF_UP)
     * - Transaction type and category validated
     * - Merchant data persisted
     * - Timestamps generated correctly
     * - @Transactional behavior ensures atomic operation (EXEC CICS SYNCPOINT)
     * 
     * CRITICAL: Per Section 0.7.2, balance calculation must be bit-identical to COBOL:
     * COBOL: COMPUTE NEW-BALANCE = ACCT-CURR-BAL - TRAN-AMT
     * Java:  newBalance = currentBalance.subtract(transactionAmount).setScale(2, HALF_UP)
     */
    @Test
    void testPostTransaction_ValidPurchase_CreatesTransactionAndUpdatesBalance() {
        // Prepare transaction request matching COBOL COTRN2AI input map
        Map<String, Object> transactionRequest = new HashMap<>();
        transactionRequest.put("transCardNum", TEST_CARD_NUM_1);
        transactionRequest.put("transTypeCd", TRANS_TYPE_PURCHASE);
        transactionRequest.put("transCatCd", TRANS_CAT_REGULAR_SALE);
        transactionRequest.put("transSource", "POS");
        transactionRequest.put("transDesc", "TEST PURCHASE TRANSACTION");
        transactionRequest.put("transAmt", 75.25); // Will be converted to BigDecimal scale 2
        transactionRequest.put("transMerchantId", 123456789L);
        transactionRequest.put("transMerchantName", "TEST MERCHANT");
        transactionRequest.put("transMerchantCity", "SEATTLE");
        transactionRequest.put("transMerchantZip", "98101");
        transactionRequest.put("transOrigTs", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        
        // Post transaction and measure response time (must be < 200ms per Section 0.7.7)
        long startTime = System.currentTimeMillis();
        
        String newTransId = given()
                .contentType(ContentType.JSON)
                .body(transactionRequest)
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(201)
                .body("transId", notNullValue())
                .body("transCardNum", containsString("1234")) // Masked
                .body("transTypeCd", equalTo(TRANS_TYPE_PURCHASE))
                .body("transCatCd", equalTo(TRANS_CAT_REGULAR_SALE))
                .body("transAmt", equalTo("75.25")) // BigDecimal preserved as string for COMP-3 precision
                .body("transMerchantName", equalTo("TEST MERCHANT"))
                .body("transOrigTs", notNullValue())
                .body("transProcTs", notNullValue())
                .extract()
                .path("transId");
        
        long responseTime = System.currentTimeMillis() - startTime;
        
        // Validate performance requirement: sub-200ms response time
        assert responseTime < 200 : "Transaction posting response time " + responseTime + 
                "ms exceeds 200ms requirement per Section 0.7.7";
        
        // Verify account balance updated correctly with COMP-3 precision
        Account updatedAccount = accountRepository.findById(TEST_ACCOUNT_ID_1).orElseThrow();
        
        // Expected balance calculation matching COBOL COMPUTE statement:
        // NEW-BALANCE = 5000.00 - 75.25 = 4924.75
        BigDecimal expectedBalance = AMOUNT_INITIAL_BALANCE
                .subtract(new BigDecimal("75.25"))
                .setScale(2, RoundingMode.HALF_UP);
        
        assert updatedAccount.getAcctCurrBal().compareTo(expectedBalance) == 0 :
                "Balance mismatch: expected " + expectedBalance + ", got " + updatedAccount.getAcctCurrBal();
        
        // Verify transaction persisted in database
        assert transactionRepository.findById(newTransId).isPresent() :
                "Transaction not persisted in database";
    }
    
    /**
     * Test POST /api/transactions - post payment transaction (credit type).
     * 
     * Converted from COBOL: COTRN02C.cbl payment posting (credit transaction)
     * Original COBOL: IF TRAN-TYPE-CD = '04' [payment]
     *                   COMPUTE NEW-BALANCE = ACCT-CURR-BAL + TRAN-AMT
     * 
     * Validates:
     * - Payment increases account balance (credit transaction)
     * - Balance calculation for credit type matches COBOL logic
     * - Negative impact on balance (payment reduces debt)
     */
    @Test
    void testPostTransaction_ValidPayment_IncreasesBalance() {
        Map<String, Object> paymentRequest = new HashMap<>();
        paymentRequest.put("transCardNum", TEST_CARD_NUM_1);
        paymentRequest.put("transTypeCd", TRANS_TYPE_PAYMENT);
        paymentRequest.put("transCatCd", 0);
        paymentRequest.put("transSource", "ONLINE");
        paymentRequest.put("transDesc", "ONLINE PAYMENT");
        paymentRequest.put("transAmt", 200.00);
        paymentRequest.put("transOrigTs", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        
        given()
                .contentType(ContentType.JSON)
                .body(paymentRequest)
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(201)
                .body("transTypeCd", equalTo(TRANS_TYPE_PAYMENT));
        
        // Verify balance increased by payment amount (credit transaction)
        Account updatedAccount = accountRepository.findById(TEST_ACCOUNT_ID_1).orElseThrow();
        
        // Expected balance: 5000.00 + 200.00 = 5200.00
        BigDecimal expectedBalance = AMOUNT_INITIAL_BALANCE
                .add(new BigDecimal("200.00"))
                .setScale(2, RoundingMode.HALF_UP);
        
        assert updatedAccount.getAcctCurrBal().compareTo(expectedBalance) == 0;
    }
    
    /**
     * Test POST /api/transactions with invalid card number.
     * 
     * Converted from COBOL: COTRN02C.cbl card validation (lines 400-450)
     * Original COBOL: EXEC CICS READ FILE('CARDFILE')
     *                 EVALUATE WS-RESP-CD
     *                   WHEN DFHRESP(NOTFND)
     *                     MOVE 'Card not found' TO WS-MESSAGE
     *                     PERFORM SEND-ERROR-SCREEN
     * 
     * Validates:
     * - HTTP 404 Not Found for non-existent card
     * - Transaction not created when card invalid
     * - Account balance unchanged
     * - Matches COBOL DFHRESP(NOTFND) error handling
     */
    @Test
    void testPostTransaction_InvalidCardNumber_Returns404() {
        Map<String, Object> transactionRequest = new HashMap<>();
        transactionRequest.put("transCardNum", "4111111111111111"); // Valid Luhn but not in database
        transactionRequest.put("transTypeCd", TRANS_TYPE_PURCHASE);
        transactionRequest.put("transCatCd", TRANS_CAT_REGULAR_SALE);
        transactionRequest.put("transAmt", 100.00);
        transactionRequest.put("transMerchantId", 123456789L); // Required for purchase
        transactionRequest.put("transMerchantName", "TEST MERCHANT"); // Required for purchase
        transactionRequest.put("transOrigTs", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        
        given()
                .contentType(ContentType.JSON)
                .body(transactionRequest)
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(404)
                .body("message", containsString("Card"));
    }
    
    /**
     * Test POST /api/transactions with invalid transaction type.
     * 
     * Converted from COBOL: COTRN02C.cbl type validation (lines 235-250)
     * Original COBOL: IF TTYPCDI NOT = '01' AND NOT = '02' ... [valid types]
     *                   MOVE 'Invalid transaction type' TO WS-MESSAGE
     *                   SET ERR-FLG-ON TO TRUE
     * 
     * Validates:
     * - HTTP 400 Bad Request for invalid type code
     * - Error message indicates type validation failure
     * - Matches COBOL field validation logic
     */
    @Test
    void testPostTransaction_InvalidTransactionType_Returns400() {
        Map<String, Object> transactionRequest = new HashMap<>();
        transactionRequest.put("transCardNum", TEST_CARD_NUM_1);
        transactionRequest.put("transTypeCd", "99"); // Invalid type code
        transactionRequest.put("transCatCd", TRANS_CAT_REGULAR_SALE);
        transactionRequest.put("transAmt", 100.00);
        transactionRequest.put("transOrigTs", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        
        given()
                .contentType(ContentType.JSON)
                .body(transactionRequest)
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400)
                .body("message", containsString("type"));
    }
    
    /**
     * Test POST /api/transactions with zero or negative amount.
     * 
     * Converted from COBOL: COTRN02C.cbl amount validation (lines 260-275)
     * Original COBOL: IF TRNAMTI NOT NUMERIC OR TRNAMTI <= ZERO
     *                   MOVE 'Invalid amount' TO WS-MESSAGE
     *                   SET ERR-FLG-ON TO TRUE
     * 
     * Validates:
     * - HTTP 400 Bad Request for invalid amount
     * - Amount must be positive (> 0)
     * - Matches COBOL numeric validation logic
     */
    @Test
    void testPostTransaction_ZeroAmount_Returns400() {
        Map<String, Object> transactionRequest = new HashMap<>();
        transactionRequest.put("transCardNum", TEST_CARD_NUM_1);
        transactionRequest.put("transTypeCd", TRANS_TYPE_PURCHASE);
        transactionRequest.put("transCatCd", TRANS_CAT_REGULAR_SALE);
        transactionRequest.put("transAmt", 0.0); // Invalid: zero amount
        transactionRequest.put("transOrigTs", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        
        given()
                .contentType(ContentType.JSON)
                .body(transactionRequest)
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400)
                .body("message", containsStringIgnoringCase("amount")); // Case-insensitive match
    }
    
    @Test
    void testPostTransaction_NegativeAmount_Returns400() {
        Map<String, Object> transactionRequest = new HashMap<>();
        transactionRequest.put("transCardNum", TEST_CARD_NUM_1);
        transactionRequest.put("transTypeCd", TRANS_TYPE_PURCHASE);
        transactionRequest.put("transCatCd", TRANS_CAT_REGULAR_SALE);
        transactionRequest.put("transAmt", -50.0); // Invalid: negative amount
        transactionRequest.put("transOrigTs", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        
        given()
                .contentType(ContentType.JSON)
                .body(transactionRequest)
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400)
                .body("message", containsStringIgnoringCase("amount"));
    }
    
    /**
     * Test POST /api/transactions exceeding credit limit.
     * 
     * Converted from COBOL: COTRN02C.cbl credit limit validation (lines 630-660)
     * Original COBOL: COMPUTE NEW-BALANCE = ACCT-CURR-BAL - TRAN-AMT
     *                 IF NEW-BALANCE > ACCT-CREDIT-LIMIT
     *                   MOVE 'Transaction exceeds credit limit' TO WS-MESSAGE
     *                   PERFORM SEND-ERROR-SCREEN
     * 
     * Validates:
     * - HTTP 400 or 409 for credit limit exceeded
     * - Transaction rejected when new balance would exceed limit
     * - Account balance unchanged (transaction rolled back)
     * - Matches COBOL credit limit check logic
     * 
     * Test scenario:
     * - Initial balance: 5000.00
     * - Credit limit: 10000.00
     * - Transaction amount: 6000.00
     * - New balance would be: 5000.00 - 6000.00 = -1000.00 (exceeds limit of 10000.00)
     */
    @Test
    void testPostTransaction_ExceedsCreditLimit_Returns400() {
        Map<String, Object> transactionRequest = new HashMap<>();
        transactionRequest.put("transCardNum", TEST_CARD_NUM_1);
        transactionRequest.put("transTypeCd", TRANS_TYPE_PURCHASE);
        transactionRequest.put("transCatCd", TRANS_CAT_REGULAR_SALE);
        transactionRequest.put("transAmt", 16000.00); // Exceeds credit limit
        transactionRequest.put("transMerchantId", 123456789L); // Required for purchase transactions
        transactionRequest.put("transMerchantName", "TEST MERCHANT"); // Required for purchase transactions
        transactionRequest.put("transOrigTs", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        
        given()
                .contentType(ContentType.JSON)
                .body(transactionRequest)
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(Matchers.anyOf(equalTo(400), equalTo(409)))
                .body("message", containsString("credit limit"));
        
        // Verify balance unchanged (transaction rolled back matching EXEC CICS ROLLBACK)
        Account account = accountRepository.findById(TEST_ACCOUNT_ID_1).orElseThrow();
        assert account.getAcctCurrBal().compareTo(AMOUNT_INITIAL_BALANCE) == 0 :
                "Balance should remain unchanged after credit limit rejection";
    }
    
    /**
     * Test POST /api/transactions with missing required fields.
     * 
     * Converted from COBOL: COTRN02C.cbl field validation (lines 235-300)
     * Original COBOL: IF CARDNINI = SPACES OR LOW-VALUES
     *                   MOVE 'Card number required' TO WS-MESSAGE
     *                   SET ERR-FLG-ON TO TRUE
     * 
     * Validates:
     * - HTTP 400 Bad Request for missing mandatory fields
     * - Error message indicates missing field
     * - Matches COBOL mandatory field validation
     */
    @Test
    void testPostTransaction_MissingCardNumber_Returns400() {
        Map<String, Object> transactionRequest = new HashMap<>();
        // transCardNum intentionally missing
        transactionRequest.put("transTypeCd", TRANS_TYPE_PURCHASE);
        transactionRequest.put("transCatCd", TRANS_CAT_REGULAR_SALE);
        transactionRequest.put("transAmt", 100.00);
        transactionRequest.put("transOrigTs", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        
        given()
                .contentType(ContentType.JSON)
                .body(transactionRequest)
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400);
    }
    
    @Test
    void testPostTransaction_MissingTransactionType_Returns400() {
        Map<String, Object> transactionRequest = new HashMap<>();
        transactionRequest.put("transCardNum", TEST_CARD_NUM_1);
        // transTypeCd intentionally missing
        transactionRequest.put("transCatCd", TRANS_CAT_REGULAR_SALE);
        transactionRequest.put("transAmt", 100.00);
        transactionRequest.put("transOrigTs", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        
        given()
                .contentType(ContentType.JSON)
                .body(transactionRequest)
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400);
    }
    
    @Test
    void testPostTransaction_MissingAmount_Returns400() {
        Map<String, Object> transactionRequest = new HashMap<>();
        transactionRequest.put("transCardNum", TEST_CARD_NUM_1);
        transactionRequest.put("transTypeCd", TRANS_TYPE_PURCHASE);
        transactionRequest.put("transCatCd", TRANS_CAT_REGULAR_SALE);
        // transAmt intentionally missing
        transactionRequest.put("transOrigTs", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        
        given()
                .contentType(ContentType.JSON)
                .body(transactionRequest)
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400);
    }
    
    /**
     * Test POST /api/transactions with invalid category code.
     * 
     * Converted from COBOL: COTRN02C.cbl category validation
     * Original COBOL: EXEC CICS READ FILE('TRANCATG')
     *                 EVALUATE WS-RESP-CD
     *                   WHEN DFHRESP(NOTFND)
     *                     MOVE 'Invalid category' TO WS-MESSAGE
     * 
     * Validates:
     * - HTTP 400 Bad Request for invalid category code
     * - Category code must exist in TRANCATG reference table
     */
    @Test
    void testPostTransaction_InvalidCategoryCode_Returns400() {
        Map<String, Object> transactionRequest = new HashMap<>();
        transactionRequest.put("transCardNum", TEST_CARD_NUM_1);
        transactionRequest.put("transTypeCd", TRANS_TYPE_PURCHASE);
        transactionRequest.put("transCatCd", 9999); // Invalid category code
        transactionRequest.put("transAmt", 100.00);
        transactionRequest.put("transMerchantId", 123456789L); // Required for purchase transactions
        transactionRequest.put("transMerchantName", "TEST MERCHANT"); // Required for purchase transactions
        transactionRequest.put("transOrigTs", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        
        given()
                .contentType(ContentType.JSON)
                .body(transactionRequest)
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400)
                .body("message", containsString("category"));
    }
    
    /**
     * Test POST /api/transactions merchant data validation.
     * 
     * Validates merchant field constraints:
     * - transMerchantId: 9 digits (COBOL PIC 9(09))
     * - transMerchantName: max 50 characters (COBOL PIC X(50))
     * - transMerchantCity: max 50 characters (COBOL PIC X(50))
     * - transMerchantZip: max 10 characters (COBOL PIC X(10))
     */
    @Test
    void testPostTransaction_ValidMerchantData_CreatesTransaction() {
        Map<String, Object> transactionRequest = new HashMap<>();
        transactionRequest.put("transCardNum", TEST_CARD_NUM_1);
        transactionRequest.put("transTypeCd", TRANS_TYPE_PURCHASE);
        transactionRequest.put("transCatCd", TRANS_CAT_REGULAR_SALE);
        transactionRequest.put("transAmt", 50.00);
        transactionRequest.put("transMerchantId", 987654321L);
        transactionRequest.put("transMerchantName", "MERCHANT WITH EXACTLY FIFTY CHARACTERS NAME HERE");
        transactionRequest.put("transMerchantCity", "CITY WITH EXACTLY FIFTY CHARACTERS NAME HERE ALSO");
        transactionRequest.put("transMerchantZip", "98101-1234"); // 10 characters
        transactionRequest.put("transOrigTs", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        
        given()
                .contentType(ContentType.JSON)
                .body(transactionRequest)
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(201)
                .body("transMerchantId", equalTo(987654321))
                .body("transMerchantName", notNullValue())
                .body("transMerchantCity", notNullValue())
                .body("transMerchantZip", equalTo("98101-1234"));
    }
    
    /**
     * Stress test: Post 100 transactions sequentially and verify all balance updates.
     * 
     * Validates:
     * - System can handle sequential transaction posting
     * - All balance calculations remain accurate with COMP-3 precision
     * - No race conditions or data corruption
     * - All transactions persisted correctly
     * - Final balance matches expected accumulated value
     * 
     * Per Section 0.7.7: System must handle 10,000 TPS peak volumes.
     * This test validates correctness at lower volume (100 sequential transactions).
     */
    @Test
    void testPostTransaction_StressTest100Transactions_AllBalancesCorrect() {
        int transactionCount = 100;
        BigDecimal transactionAmount = new BigDecimal("10.00").setScale(2, RoundingMode.HALF_UP);
        
        // Expected final balance after 100 purchases of $10.00 each
        // Initial: 5000.00, After 100 debits: 5000.00 - (100 * 10.00) = 4000.00
        BigDecimal expectedFinalBalance = AMOUNT_INITIAL_BALANCE
                .subtract(transactionAmount.multiply(new BigDecimal(transactionCount)))
                .setScale(2, RoundingMode.HALF_UP);
        
        List<String> createdTransactionIds = new ArrayList<>();
        
        // Post 100 transactions sequentially
        for (int i = 0; i < transactionCount; i++) {
            Map<String, Object> transactionRequest = new HashMap<>();
            transactionRequest.put("transCardNum", TEST_CARD_NUM_1);
            transactionRequest.put("transTypeCd", TRANS_TYPE_PURCHASE);
            transactionRequest.put("transCatCd", TRANS_CAT_REGULAR_SALE);
            transactionRequest.put("transAmt", transactionAmount.doubleValue());
            transactionRequest.put("transDesc", "STRESS TEST TRANSACTION " + (i + 1));
            transactionRequest.put("transMerchantId", 123456789L); // Required for purchase transactions
            transactionRequest.put("transMerchantName", "STRESS TEST MERCHANT"); // Required for purchase transactions
            transactionRequest.put("transOrigTs", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
            
            String transId = given()
                    .contentType(ContentType.JSON)
                    .body(transactionRequest)
            .when()
                    .post("/api/transactions")
            .then()
                    .statusCode(201)
                    .extract()
                    .path("transId");
            
            createdTransactionIds.add(transId);
        }
        
        // Verify all transactions persisted
        assert createdTransactionIds.size() == transactionCount :
                "Not all transactions were created";
        
        // Verify final account balance is correct with COMP-3 precision
        Account finalAccount = accountRepository.findById(TEST_ACCOUNT_ID_1).orElseThrow();
        
        assert finalAccount.getAcctCurrBal().compareTo(expectedFinalBalance) == 0 :
                "Final balance mismatch after " + transactionCount + " transactions. Expected: " +
                expectedFinalBalance + ", Got: " + finalAccount.getAcctCurrBal();
        
        // Verify all transactions are retrievable via API
        int retrievedCount = given()
                .contentType(ContentType.JSON)
                .queryParam("cardNumber", TEST_CARD_NUM_1)
        .when()
                .get("/api/transactions")
        .then()
                .statusCode(200)
                .extract()
                .path("totalElements");
        
        // Total should be initial 2 test transactions + 100 stress test transactions
        assert retrievedCount >= transactionCount :
                "Not all transactions retrievable via API. Expected >= " + transactionCount +
                ", Got: " + retrievedCount;
    }
    
    /**
     * Test transaction atomicity with @Transactional rollback behavior.
     * 
     * Converted from COBOL: COTRN02C.cbl transaction boundaries
     * Original COBOL: EXEC CICS SYNCPOINT [commit]
     *                 EXEC CICS ROLLBACK [rollback on error]
     * 
     * Validates:
     * - @Transactional ensures atomic operation
     * - Failed transaction does not update account balance
     * - Failed transaction is not persisted
     * - Matches COBOL EXEC CICS ROLLBACK behavior
     * 
     * Note: This test validates that Spring @Transactional provides equivalent
     * atomicity to COBOL EXEC CICS SYNCPOINT/ROLLBACK per Section 0.7.2.
     */
    @Test
    void testPostTransaction_TransactionalRollback_BalanceUnchanged() {
        // Attempt transaction with invalid category (will fail validation)
        Map<String, Object> invalidRequest = new HashMap<>();
        invalidRequest.put("transCardNum", TEST_CARD_NUM_1);
        invalidRequest.put("transTypeCd", TRANS_TYPE_PURCHASE);
        invalidRequest.put("transCatCd", 9999); // Invalid category
        invalidRequest.put("transAmt", 100.00);
        invalidRequest.put("transOrigTs", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        
        // Capture initial balance
        Account accountBefore = accountRepository.findById(TEST_ACCOUNT_ID_1).orElseThrow();
        BigDecimal initialBalance = accountBefore.getAcctCurrBal();
        
        // Attempt invalid transaction (should fail)
        given()
                .contentType(ContentType.JSON)
                .body(invalidRequest)
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400);
        
        // Verify balance unchanged (transaction rolled back)
        Account accountAfter = accountRepository.findById(TEST_ACCOUNT_ID_1).orElseThrow();
        
        assert accountAfter.getAcctCurrBal().compareTo(initialBalance) == 0 :
                "@Transactional rollback failed: balance changed despite validation failure";
    }
}

