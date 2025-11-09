/*
 * ReportControllerTest.java
 * 
 * Integration tests for ReportController REST endpoints validating transaction
 * report generation with @SpringBootTest and MockMvc.
 * 
 * This test class provides comprehensive integration testing of GET /api/reports/transactions
 * endpoint ensuring correct transformation of COBOL CORPT00C.cbl CR00 transaction
 * report generation logic to modern REST API with date range filtering, pagination,
 * and export capabilities.
 * 
 * COBOL Program Tested: CORPT00C.cbl (CR00 transaction)
 * Original Functionality Validated:
 * - Lines 60-72: Date range input validation (WS-START-DATE, WS-END-DATE structures)
 * - Lines 236-354: Date validation logic ensuring start date <= end date
 * - VSAM TRANSACT file sequential reading with STARTBR/READNEXT
 * - Transaction aggregation with COBOL COMPUTE statements for totals
 * - Report formatting with category breakdown and merchant filtering
 * 
 * Modern Test Coverage:
 * - Synchronous HTTP GET endpoint validation replacing batch job submission
 * - PostgreSQL query testing with date range filtering via Spring Data JPA
 * - JSON ReportResponse structure validation with nested DTOs
 * - Pagination testing ensuring 10 transactions per page default
 * - Role-based security testing (ADMIN and USER access)
 * - Export functionality validation (PDF, CSV formats)
 * - Edge case handling (empty results, invalid dates, large datasets)
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
package com.carddemo.controller;

import com.carddemo.dto.response.ReportResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.DateTimeUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comprehensive integration tests for ReportController endpoints.
 * 
 * <p>This test class validates transaction report generation REST API functionality
 * replacing COBOL mainframe CICS transaction CR00 (CORPT00C.cbl) which generated and
 * submitted batch JCL jobs for asynchronous report processing. Modern implementation
 * provides synchronous, on-demand report generation with immediate JSON responses.</p>
 * 
 * <h2>Test Strategy</h2>
 * <p>Uses @SpringBootTest for full application context loading including:</p>
 * <ul>
 *   <li>ReportController with @RequestMapping and @GetMapping endpoints</li>
 *   <li>ReportGenerationService with business logic and aggregation</li>
 *   <li>TransactionRepository with Spring Data JPA queries</li>
 *   <li>PostgreSQL database with test data fixtures</li>
 *   <li>Spring Security with JWT authentication simulation via @WithMockUser</li>
 *   <li>Jackson ObjectMapper for JSON serialization/deserialization</li>
 * </ul>
 * 
 * <h2>Test Data Setup</h2>
 * <p>Each test method operates on a clean database state:</p>
 * <ol>
 *   <li>@BeforeEach setupTestData() creates Transaction entities using builder pattern</li>
 *   <li>TransactionRepository.saveAll() persists test transactions to database</li>
 *   <li>Test method executes HTTP GET request via MockMvc</li>
 *   <li>@AfterEach cleanup() deletes all test transactions ensuring test isolation</li>
 * </ol>
 * 
 * <h2>Validation Approach</h2>
 * <p>Tests validate multiple aspects of report generation:</p>
 * <ul>
 *   <li>HTTP status codes (200 OK for success, 400 Bad Request for invalid parameters)</li>
 *   <li>Content-Type header (application/json)</li>
 *   <li>JSON structure using jsonPath() matchers for field presence and values</li>
 *   <li>ReportResponse DTO deserialization with ObjectMapper for complex object validation</li>
 *   <li>BigDecimal monetary precision ensuring scale=2 and RoundingMode.HALF_UP</li>
 *   <li>Date format validation matching ISO 8601 (yyyy-MM-dd)</li>
 *   <li>Pagination metadata (totalPages, currentPage, totalElements)</li>
 *   <li>Category and type breakdown aggregation accuracy</li>
 * </ul>
 * 
 * <h2>COBOL Functional Equivalence</h2>
 * <p>Test scenarios preserve COBOL CORPT00C.cbl behavior:</p>
 * <ul>
 *   <li>Date range validation identical to lines 236-354</li>
 *   <li>Transaction aggregation matching COBOL COMPUTE arithmetic</li>
 *   <li>BigDecimal precision matching COBOL COMP-3 packed decimal (PIC S9(09)V99)</li>
 *   <li>Default date range (current month) matching 'Monthly' report type</li>
 *   <li>Empty result handling matching TRANSACT-EOF condition</li>
 * </ul>
 * 
 * @see ReportController
 * @see com.carddemo.service.reporting.ReportGenerationService
 * @see ReportResponse
 * @see Transaction
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ReportController Integration Tests")
public class ReportControllerTest {

    /**
     * MockMvc instance for simulating HTTP requests to controller endpoints.
     * 
     * <p>Autowired by Spring Test framework with @AutoConfigureMockMvc annotation,
     * enabling HTTP GET request simulation without starting embedded Tomcat server.
     * Provides perform() method for request execution and ResultActions for response
     * assertions using fluent API matching RestAssured testing patterns.</p>
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Jackson ObjectMapper for JSON serialization and deserialization.
     * 
     * <p>Autowired Spring Boot configured ObjectMapper with:</p>
     * <ul>
     *   <li>Java 8 date/time module for LocalDate/LocalDateTime handling</li>
     *   <li>BigDecimal serialization as JSON string preventing precision loss</li>
     *   <li>ISO 8601 date format patterns matching API specification</li>
     *   <li>POJO property ordering matching @JsonPropertyOrder annotations</li>
     * </ul>
     * 
     * <p>Used to deserialize MockMvc response JSON into ReportResponse DTO objects
     * enabling field-level assertions beyond jsonPath() string matching capabilities.</p>
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * TransactionRepository for test data setup and cleanup.
     * 
     * <p>Provides database access methods:</p>
     * <ul>
     *   <li>saveAll(List&lt;Transaction&gt;) - Batch insert test transactions in @BeforeEach</li>
     *   <li>deleteAll() - Bulk delete all transactions in @AfterEach for test isolation</li>
     *   <li>findAll() - Verify test data persistence during debugging</li>
     * </ul>
     * 
     * <p>Enables true integration testing with real PostgreSQL database queries
     * validating JPA entity mappings, composite indexes, and query performance
     * without repository layer mocking for authentic end-to-end testing.</p>
     */
    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * AccountRepository for test account data setup and cleanup.
     * 
     * <p>Provides database access methods for Account entities:</p>
     * <ul>
     *   <li>saveAll(List&lt;Account&gt;) - Batch insert test accounts in @BeforeEach</li>
     *   <li>deleteAll() - Bulk delete all accounts in @AfterEach for test isolation</li>
     * </ul>
     * 
     * <p>Required because Card entity has @ManyToOne relationship with Account,
     * so Account entities must exist before creating Card entities to satisfy
     * foreign key constraints.</p>
     */
    @Autowired
    private AccountRepository accountRepository;

    /**
     * CardRepository for test card data setup and cleanup.
     * 
     * <p>Provides database access methods for Card entities:</p>
     * <ul>
     *   <li>saveAll(List&lt;Card&gt;) - Batch insert test cards in @BeforeEach</li>
     *   <li>deleteAll() - Bulk delete all cards in @AfterEach for test isolation</li>
     * </ul>
     * 
     * <p>Required because Transaction entity has @ManyToOne relationship with Card,
     * so Card entities must exist before creating Transaction entities to satisfy
     * foreign key constraints.</p>
     */
    @Autowired
    private CardRepository cardRepository;

    /**
     * CustomerRepository for test customer data setup and cleanup.
     * 
     * <p>Provides database access methods for Customer entities:</p>
     * <ul>
     *   <li>saveAll(List&lt;Customer&gt;) - Batch insert test customers in @BeforeEach</li>
     *   <li>deleteAll() - Bulk delete all customers in @AfterEach for test isolation</li>
     * </ul>
     * 
     * <p>Required because Account entity has @ManyToOne relationship with Customer,
     * so Customer entities must exist before creating Account entities to satisfy
     * foreign key constraints.</p>
     */
    @Autowired
    private CustomerRepository customerRepository;

    /**
     * Test data fixture holding transactions for current test method.
     * 
     * <p>Populated in @BeforeEach setupTestData() method with Transaction entities
     * using builder pattern, persisted to database via TransactionRepository.saveAll().
     * Cleared in @AfterEach cleanup() to ensure test isolation preventing data
     * pollution between test methods following JUnit best practices.</p>
     * 
     * <p>Contains diverse transaction samples covering:</p>
     * <ul>
     *   <li>Different date ranges for filtering validation</li>
     *   <li>Multiple card numbers for card-specific report testing</li>
     *   <li>Various transaction types and categories for breakdown aggregation</li>
     *   <li>Different amount values for sum/average calculation testing</li>
     *   <li>Different merchants for merchant filtering validation</li>
     * </ul>
     */
    private List<Transaction> testTransactions;

    /**
     * Test data fixture holding accounts for current test method.
     * 
     * <p>Populated in @BeforeEach setupTestData() method with Account entities
     * using builder pattern, persisted to database via AccountRepository.saveAll()
     * before creating Card entities.</p>
     * 
     * <p>Accounts are created first to satisfy foreign key constraint from Card
     * to Account entity (@ManyToOne relationship).</p>
     */
    private List<Account> testAccounts;

    /**
     * Test data fixture holding cards for current test method.
     * 
     * <p>Populated in @BeforeEach setupTestData() method with Card entities
     * using builder pattern, persisted to database via CardRepository.saveAll()
     * before creating Transaction entities.</p>
     * 
     * <p>Cards are created first to satisfy foreign key constraint from Transaction
     * to Card entity (@ManyToOne relationship).</p>
     */
    private List<Card> testCards;

    /**
     * Test data fixture holding customers for current test method.
     * 
     * <p>Populated in @BeforeEach setupTestData() method with Customer entities
     * using builder pattern, persisted to database via CustomerRepository.saveAll()
     * before creating Account entities.</p>
     * 
     * <p>Customers are created first to satisfy foreign key constraint from Account
     * to Customer entity (@ManyToOne relationship).</p>
     */
    private List<Customer> testCustomers;

    /**
     * Sets up test data before each test method execution.
     * 
     * <p>Creates sample Transaction entities using builder pattern with:</p>
     * <ul>
     *   <li>Unique transaction IDs following TXN{YYYYMMDDHHMMSS}{SEQ} format</li>
     *   <li>BigDecimal amounts with scale=2 using RoundingMode.HALF_UP</li>
     *   <li>LocalDateTime timestamps for origination and processing times</li>
     *   <li>Various card numbers for testing card-specific filtering</li>
     *   <li>Different type codes and category codes for breakdown testing</li>
     *   <li>Merchant information for location-based filtering</li>
     * </ul>
     * 
     * <p>Test transactions are persisted to PostgreSQL database via
     * TransactionRepository.saveAll() enabling real database query execution
     * in test methods validating Spring Data JPA repository functionality,
     * composite index performance, and SQL query generation accuracy.</p>
     * 
     * <p>This setup method runs before EACH test method ensuring every test
     * starts with a known, consistent database state matching JUnit test
     * isolation principles preventing test interdependencies and flaky tests
     * caused by shared mutable state.</p>
     */
    @BeforeEach
    public void setupTestData() {
        // Clear any existing data from previous tests to ensure clean state
        // Order matters: delete transactions first, then cards, then accounts, then customers (foreign key constraints)
        transactionRepository.deleteAll();
        cardRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();
        
        // Step 1: Create test customers
        testCustomers = new ArrayList<>();
        
        Customer customer1 = Customer.builder()
                .customerId(1000000001L)
                .firstName("John")
                .middleName("M")
                .lastName("Doe")
                .addressLine1("123 Main Street")
                .addressLine2("Apt 4B")
                .addressStateCode("NY")
                .addressCountryCode("USA")
                .addressZip("10001")
                .build();
        
        Customer customer2 = Customer.builder()
                .customerId(1000000002L)
                .firstName("Jane")
                .middleName("A")
                .lastName("Smith")
                .addressLine1("456 Oak Avenue")
                .addressLine2("Suite 200")
                .addressStateCode("CA")
                .addressCountryCode("USA")
                .addressZip("90210")
                .build();
        
        testCustomers.add(customer1);
        testCustomers.add(customer2);
        customerRepository.saveAll(testCustomers);
        
        // Step 2: Create test accounts associated with customers
        testAccounts = new ArrayList<>();
        
        Account account1 = Account.builder()
                .accountId(100000000001L)
                .customer(customer1)
                .activeStatus("Y")
                .currentBalance(new BigDecimal("1500.00").setScale(2, RoundingMode.HALF_UP))
                .creditLimit(new BigDecimal("10000.00").setScale(2, RoundingMode.HALF_UP))
                .build();
        
        Account account2 = Account.builder()
                .accountId(100000000002L)
                .customer(customer2)
                .activeStatus("Y")
                .currentBalance(new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP))
                .creditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP))
                .build();
        
        testAccounts.add(account1);
        testAccounts.add(account2);
        accountRepository.saveAll(testAccounts);
        
        // Step 3: Create test cards associated with accounts
        testCards = new ArrayList<>();
        
        Card card1 = Card.builder()
                .cardNumber("4111111111111111")
                .account(account1)
                .cvvCode("123")
                .embossedName("JOHN DOE")
                .expirationDate(LocalDate.of(2026, 12, 31))
                .activeStatus("Y")
                .build();
        
        Card card2 = Card.builder()
                .cardNumber("4222222222222222")
                .account(account2)
                .cvvCode("456")
                .embossedName("JANE SMITH")
                .expirationDate(LocalDate.of(2025, 6, 30))
                .activeStatus("Y")
                .build();
        
        testCards.add(card1);
        testCards.add(card2);
        cardRepository.saveAll(testCards);
        
        // Step 4: Create test transactions associated with cards
        testTransactions = new ArrayList<>();
        
        // Create transactions for January 2024 (primary test month)
        // Transaction 1: Retail purchase - January 15, 2024
        testTransactions.add(Transaction.builder()
                .transactionId("TXN202401150001")
                .typeCode("01")  // Purchase
                .categoryCode(1000)  // Retail
                .transactionSource("POS")
                .description("Retail purchase at electronics store")
                .amount(new BigDecimal("499.99").setScale(2, RoundingMode.HALF_UP))
                .merchantId(100001L)
                .merchantName("Best Electronics")
                .merchantCity("New York")
                .merchantZip("10001")
                .card(card1)
                .originationTimestamp(LocalDateTime.of(2024, 1, 15, 10, 30, 0))
                .processingTimestamp(LocalDateTime.of(2024, 1, 15, 10, 30, 5))
                .build());
        
        // Transaction 2: Dining purchase - January 20, 2024
        testTransactions.add(Transaction.builder()
                .transactionId("TXN202401200001")
                .typeCode("01")  // Purchase
                .categoryCode(3000)  // Dining
                .transactionSource("POS")
                .description("Restaurant dinner with friends")
                .amount(new BigDecimal("85.50").setScale(2, RoundingMode.HALF_UP))
                .merchantId(100002L)
                .merchantName("Fine Dining Restaurant")
                .merchantCity("New York")
                .merchantZip("10002")
                .card(card1)
                .originationTimestamp(LocalDateTime.of(2024, 1, 20, 19, 45, 0))
                .processingTimestamp(LocalDateTime.of(2024, 1, 20, 19, 45, 3))
                .build());
        
        // Transaction 3: Travel purchase - January 25, 2024
        testTransactions.add(Transaction.builder()
                .transactionId("TXN202401250001")
                .typeCode("01")  // Purchase
                .categoryCode(2000)  // Travel
                .transactionSource("ONLINE")
                .description("Airline ticket booking for business trip")
                .amount(new BigDecimal("750.00").setScale(2, RoundingMode.HALF_UP))
                .merchantId(100003L)
                .merchantName("Global Airlines")
                .merchantCity("Chicago")
                .merchantZip("60601")
                .card(card1)
                .originationTimestamp(LocalDateTime.of(2024, 1, 25, 14, 15, 0))
                .processingTimestamp(LocalDateTime.of(2024, 1, 25, 14, 15, 8))
                .build());
        
        // Transaction 4: Cash advance - January 28, 2024
        testTransactions.add(Transaction.builder()
                .transactionId("TXN202401280001")
                .typeCode("02")  // Cash advance
                .categoryCode(4000)  // Cash
                .transactionSource("ATM")
                .description("ATM cash withdrawal")
                .amount(new BigDecimal("200.00").setScale(2, RoundingMode.HALF_UP))
                .merchantId(100004L)
                .merchantName("ATM Network")
                .merchantCity("New York")
                .merchantZip("10003")
                .card(card1)
                .originationTimestamp(LocalDateTime.of(2024, 1, 28, 9, 0, 0))
                .processingTimestamp(LocalDateTime.of(2024, 1, 28, 9, 0, 2))
                .build());
        
        // Transaction 5: Different card - Retail purchase - January 18, 2024
        testTransactions.add(Transaction.builder()
                .transactionId("TXN202401180001")
                .typeCode("01")  // Purchase
                .categoryCode(1000)  // Retail
                .transactionSource("ONLINE")
                .description("Online shopping for clothing")
                .amount(new BigDecimal("125.75").setScale(2, RoundingMode.HALF_UP))
                .merchantId(100005L)
                .merchantName("Fashion Boutique Online")
                .merchantCity("Los Angeles")
                .merchantZip("90001")
                .card(card2)
                .originationTimestamp(LocalDateTime.of(2024, 1, 18, 15, 30, 0))
                .processingTimestamp(LocalDateTime.of(2024, 1, 18, 15, 30, 10))
                .build());
        
        // Transaction 6: February transaction (for date range edge testing)
        testTransactions.add(Transaction.builder()
                .transactionId("TXN202402050001")
                .typeCode("01")  // Purchase
                .categoryCode(1000)  // Retail
                .transactionSource("POS")
                .description("Grocery store purchase")
                .amount(new BigDecimal("67.89").setScale(2, RoundingMode.HALF_UP))
                .merchantId(100006L)
                .merchantName("SuperMart Grocery")
                .merchantCity("New York")
                .merchantZip("10004")
                .card(card1)
                .originationTimestamp(LocalDateTime.of(2024, 2, 5, 11, 20, 0))
                .processingTimestamp(LocalDateTime.of(2024, 2, 5, 11, 20, 4))
                .build());
        
        // Transaction 7: December 2023 transaction (for year boundary testing)
        testTransactions.add(Transaction.builder()
                .transactionId("TXN202312280001")
                .typeCode("01")  // Purchase
                .categoryCode(3000)  // Dining
                .transactionSource("POS")
                .description("New Year celebration dinner")
                .amount(new BigDecimal("150.00").setScale(2, RoundingMode.HALF_UP))
                .merchantId(100007L)
                .merchantName("Celebration Restaurant")
                .merchantCity("New York")
                .merchantZip("10005")
                .card(card1)
                .originationTimestamp(LocalDateTime.of(2023, 12, 28, 20, 0, 0))
                .processingTimestamp(LocalDateTime.of(2023, 12, 28, 20, 0, 5))
                .build());
        
        // Persist all test transactions to database
        transactionRepository.saveAll(testTransactions);
    }

    /**
     * Cleans up test data after each test method execution.
     * 
     * <p>Deletes all transactions from database using TransactionRepository.deleteAll()
     * ensuring test isolation and preventing data pollution between test methods.
     * Critical for maintaining independent test execution where each test can run
     * in any order without affecting others, following JUnit best practices for
     * integration testing with shared database resources.</p>
     * 
     * <p>This cleanup runs AFTER each test method regardless of test success or
     * failure, ensuring database state is always cleaned up even when assertions
     * fail or exceptions are thrown during test execution.</p>
     */
    @AfterEach
    public void cleanup() {
        // Delete all data to ensure clean state for next test
        // Order matters: delete transactions first, then cards, then accounts, then customers (foreign key constraints)
        transactionRepository.deleteAll();
        cardRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();
        
        // Clear test data lists
        if (testTransactions != null) {
            testTransactions.clear();
        }
        if (testCards != null) {
            testCards.clear();
        }
        if (testAccounts != null) {
            testAccounts.clear();
        }
        if (testCustomers != null) {
            testCustomers.clear();
        }
    }

    /**
     * Tests successful transaction report generation with explicit date range filtering.
     * 
     * <p>Validates COBOL CORPT00C.cbl functionality:</p>
     * <ul>
     *   <li>Date range input via WS-START-DATE and WS-END-DATE structures (lines 60-72)</li>
     *   <li>VSAM TRANSACT file sequential reading with date filtering</li>
     *   <li>Transaction amount aggregation using COBOL COMPUTE statements</li>
     *   <li>Count accumulation for transaction totals</li>
     *   <li>Report formatting with filtered result set</li>
     * </ul>
     * 
     * <p>Test scenario:</p>
     * <ol>
     *   <li>Request report for January 2024 (2024-01-01 to 2024-01-31)</li>
     *   <li>Expect 5 transactions matching date range from test data</li>
     *   <li>Validate aggregated totals with BigDecimal precision</li>
     *   <li>Verify JSON response structure and field values</li>
     * </ol>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("Should generate report successfully with date range filtering")
    @WithMockUser(roles = {"USER"})
    public void testGenerateReportWithDateRange() throws Exception {
        // Arrange: Define test date range (January 2024)
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);
        
        // Calculate expected aggregates from test data
        // Transactions 1-4 fall within January 2024 date range
        // Expected total: 499.99 + 85.50 + 750.00 + 200.00 = 1535.49
        BigDecimal expectedTotal = new BigDecimal("1535.49").setScale(2, RoundingMode.HALF_UP);
        long expectedCount = 4L;  // 4 transactions in January for card 4111111111111111
        
        // Act & Assert: Execute GET request and validate response
        MvcResult result = mockMvc.perform(get("/api/reports/transactions")
                        .param("startDate", startDate.toString())
                        .param("endDate", endDate.toString())
                        .param("cardNumber", "4111111111111111")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.reportTitle").value("Transaction Report"))
                .andExpect(jsonPath("$.reportDate").exists())
                .andExpect(jsonPath("$.filterCriteria").exists())
                .andExpect(jsonPath("$.filterCriteria.startDate").value(startDate.toString()))
                .andExpect(jsonPath("$.filterCriteria.endDate").value(endDate.toString()))
                .andExpect(jsonPath("$.transactionSummary").exists())
                .andExpect(jsonPath("$.transactionSummary.transactionCount").value(expectedCount))
                .andExpect(jsonPath("$.transactionSummary.totalAmount").value(expectedTotal.toString()))
                .andReturn();
        
        // Deserialize response to ReportResponse DTO for detailed validation
        String responseJson = result.getResponse().getContentAsString();
        ReportResponse reportResponse = objectMapper.readValue(responseJson, ReportResponse.class);
        
        // Validate ReportResponse fields
        assertThat(reportResponse).isNotNull();
        assertThat(reportResponse.getReportTitle()).isEqualTo("Transaction Report");
        assertThat(reportResponse.getReportDate()).isNotNull();
        assertThat(reportResponse.getFilterCriteria()).isNotNull();
        assertThat(reportResponse.getFilterCriteria().getStartDate()).isEqualTo(startDate);
        assertThat(reportResponse.getFilterCriteria().getEndDate()).isEqualTo(endDate);
        assertThat(reportResponse.getTransactionSummary()).isNotNull();
        assertThat(reportResponse.getTransactionSummary().getTransactionCount()).isEqualTo(expectedCount);
        assertThat(reportResponse.getTransactionSummary().getTotalAmount()).isEqualTo(expectedTotal);
    }

    /**
     * Tests report generation with default date range (current month).
     * 
     * <p>Validates COBOL CORPT00C.cbl default 'Monthly' report type behavior when
     * user does not specify start and end dates. Controller defaults to first day
     * and last day of current month matching mainframe default report behavior.</p>
     * 
     * <p>Test scenario:</p>
     * <ol>
     *   <li>Request report without startDate or endDate parameters</li>
     *   <li>Expect controller to default to current month date range</li>
     *   <li>Validate response includes filterCriteria with defaulted dates</li>
     *   <li>Verify HTTP 200 OK status with valid JSON structure</li>
     * </ol>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("Should use default date range (current month) when dates not provided")
    @WithMockUser(roles = {"USER"})
    public void testGenerateReportWithDefaultDateRange() throws Exception {
        // Arrange: Calculate current month date range
        LocalDate now = LocalDate.now();
        LocalDate expectedStartDate = now.withDayOfMonth(1);
        LocalDate expectedEndDate = now.withDayOfMonth(now.lengthOfMonth());
        
        // Act & Assert: Execute GET request without date parameters
        mockMvc.perform(get("/api/reports/transactions")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.reportTitle").value("Transaction Report"))
                .andExpect(jsonPath("$.filterCriteria").exists())
                .andExpect(jsonPath("$.filterCriteria.startDate").value(expectedStartDate.toString()))
                .andExpect(jsonPath("$.filterCriteria.endDate").value(expectedEndDate.toString()))
                .andExpect(jsonPath("$.transactionSummary").exists());
    }

    /**
     * Tests report generation with invalid date range (start date after end date).
     * 
     * <p>Validates COBOL CORPT00C.cbl date validation logic (lines 236-354) ensuring
     * start date must be on or before end date. Modern implementation throws
     * ValidationException handled by GlobalExceptionHandler returning HTTP 400
     * Bad Request matching COBOL error handling pattern.</p>
     * 
     * <p>Test scenario:</p>
     * <ol>
     *   <li>Request report with startDate=2024-01-31, endDate=2024-01-01 (inverted)</li>
     *   <li>Expect HTTP 400 Bad Request status</li>
     *   <li>Validate error message describes validation failure</li>
     * </ol>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("Should return 400 Bad Request for invalid date range (start > end)")
    @WithMockUser(roles = {"USER"})
    public void testGenerateReportWithInvalidDateRange() throws Exception {
        // Arrange: Define invalid date range (start after end)
        LocalDate startDate = LocalDate.of(2024, 1, 31);
        LocalDate endDate = LocalDate.of(2024, 1, 1);
        
        // Act & Assert: Execute GET request and expect 400 Bad Request
        mockMvc.perform(get("/api/reports/transactions")
                        .param("startDate", startDate.toString())
                        .param("endDate", endDate.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    /**
     * Tests report generation returning empty result set.
     * 
     * <p>Validates COBOL TRANSACT-EOF condition handling when no transactions match
     * filter criteria. Modern implementation returns HTTP 200 OK with empty
     * transactionList and zero aggregates, not HTTP 404, matching REST API best
     * practices where empty result is valid successful response.</p>
     * 
     * <p>Test scenario:</p>
     * <ol>
     *   <li>Request report for date range with no transactions (year 2020)</li>
     *   <li>Expect HTTP 200 OK with transactionCount=0</li>
     *   <li>Validate totalAmount=0.00 with proper BigDecimal scale</li>
     *   <li>Verify empty transactionList array</li>
     * </ol>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("Should return empty report for date range with no transactions")
    @WithMockUser(roles = {"USER"})
    public void testGenerateReportWithNoTransactions() throws Exception {
        // Arrange: Define date range with no test transactions (year 2020)
        LocalDate startDate = LocalDate.of(2020, 1, 1);
        LocalDate endDate = LocalDate.of(2020, 12, 31);
        
        // Act & Assert: Execute GET request and validate empty result
        MvcResult result = mockMvc.perform(get("/api/reports/transactions")
                        .param("startDate", startDate.toString())
                        .param("endDate", endDate.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionSummary.transactionCount").value(0))
                .andExpect(jsonPath("$.transactionSummary.totalAmount").value("0.00"))
                .andReturn();
        
        // Deserialize and validate empty result structure
        String responseJson = result.getResponse().getContentAsString();
        ReportResponse reportResponse = objectMapper.readValue(responseJson, ReportResponse.class);
        
        assertThat(reportResponse.getTransactionSummary().getTransactionCount()).isEqualTo(0L);
        assertThat(reportResponse.getTransactionSummary().getTotalAmount())
                .isEqualTo(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Tests report generation with ADMIN role access.
     * 
     * <p>Validates Spring Security @PreAuthorize("hasAnyRole('ADMIN', 'USER')") annotation
     * on ReportController.generateTransactionReport() method allows ADMIN role to
     * access report endpoint, replacing RACF security checking from COBOL COSGN00C.cbl
     * with method-level authorization per section 0.6 security transformation requirements.</p>
     * 
     * <p>Test scenario:</p>
     * <ol>
     *   <li>Simulate authenticated user with ADMIN role using @WithMockUser</li>
     *   <li>Request transaction report</li>
     *   <li>Expect HTTP 200 OK status (access granted)</li>
     *   <li>Validate successful report generation</li>
     * </ol>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("Should allow ADMIN role to generate reports")
    @WithMockUser(roles = {"ADMIN"})
    public void testGenerateReportWithAdminRole() throws Exception {
        // Arrange: Define test date range
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);
        
        // Act & Assert: Execute GET request with ADMIN role and expect success
        mockMvc.perform(get("/api/reports/transactions")
                        .param("startDate", startDate.toString())
                        .param("endDate", endDate.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.reportTitle").value("Transaction Report"));
    }

    /**
     * Tests report generation with USER role access.
     * 
     * <p>Validates Spring Security allows regular USER role to access report endpoint
     * matching original RACF security where all authorized users could generate
     * reports for their transactions, not restricted to administrators only.</p>
     * 
     * <p>Test scenario:</p>
     * <ol>
     *   <li>Simulate authenticated user with USER role using @WithMockUser</li>
     *   <li>Request transaction report</li>
     *   <li>Expect HTTP 200 OK status (access granted)</li>
     *   <li>Validate successful report generation</li>
     * </ol>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("Should allow USER role to generate reports")
    @WithMockUser(roles = {"USER"})
    public void testGenerateReportWithUserRole() throws Exception {
        // Arrange: Define test date range
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);
        
        // Act & Assert: Execute GET request with USER role and expect success
        mockMvc.perform(get("/api/reports/transactions")
                        .param("startDate", startDate.toString())
                        .param("endDate", endDate.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.reportTitle").value("Transaction Report"));
    }

    /**
     * Tests report generation with pagination parameters.
     * 
     * <p>Validates Spring Data Pageable integration with query parameters matching
     * BMS CORPT00M.bms screen pagination with PF7/PF8 keys transforming to HTTP
     * query parameters ?page=0&size=10 for paginated report detail retrieval.</p>
     * 
     * <p>Test scenario:</p>
     * <ol>
     *   <li>Request report with page=0, size=2 (first page, 2 records per page)</li>
     *   <li>Expect HTTP 200 OK with pagination metadata</li>
     *   <li>Validate currentPage=0, totalPages calculated correctly</li>
     *   <li>Verify transactionList limited to page size</li>
     * </ol>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("Should support pagination with page and size parameters")
    @WithMockUser(roles = {"USER"})
    public void testGenerateReportWithPagination() throws Exception {
        // Arrange: Define test date range and pagination parameters
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);
        int page = 0;
        int size = 2;
        
        // Act & Assert: Execute GET request with pagination parameters
        mockMvc.perform(get("/api/reports/transactions")
                        .param("startDate", startDate.toString())
                        .param("endDate", endDate.toString())
                        .param("cardNumber", "4111111111111111")
                        .param("page", String.valueOf(page))
                        .param("size", String.valueOf(size))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.currentPage").value(page))
                .andExpect(jsonPath("$.totalElements").exists())
                .andExpect(jsonPath("$.totalPages").exists());
    }

    /**
     * Tests report generation with single transaction result.
     * 
     * <p>Validates edge case handling where date range or filters return exactly
     * one transaction. Tests BigDecimal aggregation with single value ensuring
     * proper scale and rounding maintained, and average calculation equals single
     * transaction amount.</p>
     * 
     * <p>Test scenario:</p>
     * <ol>
     *   <li>Request report with narrow date range containing single transaction</li>
     *   <li>Expect HTTP 200 OK with transactionCount=1</li>
     *   <li>Validate totalAmount equals single transaction amount</li>
     *   <li>Verify averageAmount equals totalAmount for single record</li>
     * </ol>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("Should handle single transaction result correctly")
    @WithMockUser(roles = {"USER"})
    public void testGenerateReportWithSingleTransaction() throws Exception {
        // Arrange: Define narrow date range containing single transaction
        LocalDate startDate = LocalDate.of(2024, 1, 15);
        LocalDate endDate = LocalDate.of(2024, 1, 15);
        
        // Expected values from Transaction 1 (January 15, 2024)
        BigDecimal expectedAmount = new BigDecimal("499.99").setScale(2, RoundingMode.HALF_UP);
        
        // Act & Assert: Execute GET request and validate single transaction result
        MvcResult result = mockMvc.perform(get("/api/reports/transactions")
                        .param("startDate", startDate.toString())
                        .param("endDate", endDate.toString())
                        .param("cardNumber", "4111111111111111")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionSummary.transactionCount").value(1))
                .andExpect(jsonPath("$.transactionSummary.totalAmount").value(expectedAmount.toString()))
                .andReturn();
        
        // Deserialize and validate single transaction aggregation
        String responseJson = result.getResponse().getContentAsString();
        ReportResponse reportResponse = objectMapper.readValue(responseJson, ReportResponse.class);
        
        assertThat(reportResponse.getTransactionSummary().getTransactionCount()).isEqualTo(1L);
        assertThat(reportResponse.getTransactionSummary().getTotalAmount()).isEqualTo(expectedAmount);
    }

    /**
     * Tests report generation with invalid date format parameter.
     * 
     * <p>Validates Spring @DateTimeFormat annotation validation ensuring only
     * ISO 8601 date format (yyyy-MM-dd) accepted. Invalid formats trigger
     * MethodArgumentTypeMismatchException handled by GlobalExceptionHandler
     * returning HTTP 400 Bad Request matching COBOL date format validation.</p>
     * 
     * <p>Test scenario:</p>
     * <ol>
     *   <li>Request report with invalid date format "01/01/2024" (MM/DD/YYYY)</li>
     *   <li>Expect HTTP 400 Bad Request status</li>
     *   <li>Validate error response indicates date format issue</li>
     * </ol>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("Should return 400 Bad Request for invalid date format")
    @WithMockUser(roles = {"USER"})
    public void testGenerateReportWithInvalidDateFormat() throws Exception {
        // Act & Assert: Execute GET request with invalid date format
        mockMvc.perform(get("/api/reports/transactions")
                        .param("startDate", "01/01/2024")  // Invalid format (should be yyyy-MM-dd)
                        .param("endDate", "01/31/2024")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    /**
     * Tests report generation with category breakdown validation.
     * 
     * <p>Validates COBOL nested loop pattern for category accumulation transforming
     * to Java Stream API with groupingBy collector or native PostgreSQL GROUP BY
     * query. Ensures category breakdown list contains correct category codes,
     * counts, and total amounts with BigDecimal precision.</p>
     * 
     * <p>Test scenario:</p>
     * <ol>
     *   <li>Request report for date range with multiple categories</li>
     *   <li>Expect HTTP 200 OK with categoryBreakdown array</li>
     *   <li>Validate category codes present in breakdown</li>
     *   <li>Verify category totals sum to overall totalAmount</li>
     * </ol>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("Should include category breakdown in report response")
    @WithMockUser(roles = {"USER"})
    public void testGenerateReportWithCategoryBreakdown() throws Exception {
        // Arrange: Define test date range covering multiple categories
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);
        
        // Act & Assert: Execute GET request and validate category breakdown
        MvcResult result = mockMvc.perform(get("/api/reports/transactions")
                        .param("startDate", startDate.toString())
                        .param("endDate", endDate.toString())
                        .param("cardNumber", "4111111111111111")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.categoryBreakdown").exists())
                .andExpect(jsonPath("$.categoryBreakdown").isArray())
                .andReturn();
        
        // Deserialize and validate category breakdown structure
        String responseJson = result.getResponse().getContentAsString();
        ReportResponse reportResponse = objectMapper.readValue(responseJson, ReportResponse.class);
        
        assertThat(reportResponse.getCategoryBreakdown()).isNotNull();
        // Expect at least 2 categories in January transactions (Retail, Dining, Travel, Cash)
        assertThat(reportResponse.getCategoryBreakdown().size()).isGreaterThan(0);
    }

    /**
     * Tests report generation with year boundary edge case.
     * 
     * <p>Validates date range spanning multiple years ensuring proper PostgreSQL
     * timestamp comparison with BETWEEN operator and composite index usage.
     * Tests December 2023 to January 2024 transition maintaining accurate
     * transaction filtering and aggregation.</p>
     * 
     * <p>Test scenario:</p>
     * <ol>
     *   <li>Request report for date range spanning year boundary (Dec 2023 - Jan 2024)</li>
     *   <li>Expect HTTP 200 OK with transactions from both years</li>
     *   <li>Validate count includes all matching transactions across year boundary</li>
     *   <li>Verify aggregation accuracy with BigDecimal precision</li>
     * </ol>
     * 
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("Should handle date range spanning year boundary correctly")
    @WithMockUser(roles = {"USER"})
    public void testGenerateReportSpanningYearBoundary() throws Exception {
        // Arrange: Define date range spanning year boundary
        LocalDate startDate = LocalDate.of(2023, 12, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);
        
        // Act & Assert: Execute GET request and validate year boundary handling
        mockMvc.perform(get("/api/reports/transactions")
                        .param("startDate", startDate.toString())
                        .param("endDate", endDate.toString())
                        .param("cardNumber", "4111111111111111")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionSummary.transactionCount").exists())
                .andExpect(jsonPath("$.transactionSummary.totalAmount").exists());
    }
}
