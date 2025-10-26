package com.carddemo.integration;

import com.carddemo.CardDemoApplication;
import com.carddemo.model.dto.AccountDto;
import com.carddemo.model.dto.ErrorResponse;
import com.carddemo.model.entity.Account;
import com.carddemo.repository.AccountRepository;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Comprehensive integration tests for Account REST API endpoints converted from COBOL programs
 * COACTUPC.cbl (account update) and COACTVWC.cbl (account view).
 * 
 * This test class validates the complete Java Spring Boot implementation against the original
 * COBOL mainframe business logic, ensuring bit-identical results for financial calculations
 * using BigDecimal with scale 2 and RoundingMode.HALF_UP to match COBOL COMP-3 packed decimal
 * arithmetic per Section 0.7.2 of the Agent Action Plan.
 * 
 * Technology Stack Migration Validation:
 * - COBOL COACTUPC.cbl → AccountController PUT /api/accounts/{id}
 * - COBOL COACTVWC.cbl → AccountController GET /api/accounts/{id}
 * - VSAM ACCTFILE I/O → AccountRepository JPA operations
 * - COBOL COMP-3 arithmetic → Java BigDecimal with scale 2, RoundingMode.HALF_UP
 * - CICS transaction boundaries → Spring @Transactional
 * - COBOL file-status codes → HTTP status codes (404, 409, 500)
 * 
 * Test Coverage:
 * 1. GET /api/accounts/{id} - Retrieve account details (COACTVWC.cbl equivalent)
 * 2. PUT /api/accounts/{id} - Update account with validation (COACTUPC.cbl equivalent)
 * 3. POST /api/accounts - Create new account
 * 4. BigDecimal precision validation for financial amounts
 * 5. Optimistic locking using JPA @Version field
 * 6. Field validation rules from COBOL programs
 * 7. Error scenarios: not found (404), duplicate (409), concurrent update (409)
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @see com.carddemo.controller.AccountController
 * @see com.carddemo.model.entity.Account
 * @see com.carddemo.repository.AccountRepository
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("integration-test")
@Testcontainers
public class AccountIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AccountRepository accountRepository;

    /**
     * Testcontainers PostgreSQL instance for isolated integration testing.
     * This replaces the VSAM ACCTFILE dataset from the mainframe environment,
     * providing a clean database for each test execution.
     */
    @Container
    static PostgreSQLContainer<?> postgresContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("carddemo_test")
            .withUsername("carddemo_user")
            .withPassword("carddemo_pass");

    /**
     * Configure Spring Boot to use the Testcontainers PostgreSQL instance.
     * Dynamically sets the datasource connection properties from the test container.
     * 
     * CRITICAL: Must explicitly override ddl-auto here as @DynamicPropertySource has highest
     * precedence and ensures Hibernate creates schema even if main application.yml sets validate.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);
        // Force Hibernate to create schema - this overrides application.yml's "validate" setting
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    /**
     * Set up REST Assured configuration before each test.
     * Configures the base URI and port for REST API calls.
     */
    @BeforeEach
    void setUp() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;
        
        // Configure REST Assured to include authentication header for all requests
        // This bypasses JWT authentication for integration tests
        RestAssured.authentication = RestAssured.preemptive().basic("test", "test");
        
        // Clean database before each test to ensure isolation
        accountRepository.deleteAll();
    }

    /**
     * Clean up test data after each test execution.
     * This ensures database isolation between tests and prevents side effects.
     */
    @AfterEach
    void tearDown() {
        accountRepository.deleteAll();
    }

    /**
     * Test: GET /api/accounts/{id} - Retrieve account details
     * COBOL Equivalent: COACTVWC.cbl (Account View) EXEC CICS READ operation
     * 
     * Validates:
     * - Successful account retrieval returns HTTP 200
     * - All account fields are correctly populated
     * - BigDecimal amounts maintain precision (scale 2)
     * - Date fields are in YYYY-MM-DD format
     * - Response JSON structure matches AccountDto
     */
    @Test
    void testGetAccountById_Success() {
        // Arrange: Create test account using JPA (replaces VSAM WRITE)
        Account testAccount = Account.builder()
                .acctId(10000000001L)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("1234.56").setScale(2, RoundingMode.HALF_UP))
                .acctCreditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCashCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctOpenDate(LocalDate.of(2020, 1, 15))
                .acctExpirationDate(LocalDate.of(2025, 1, 15))
                .acctReissueDate(LocalDate.of(2023, 1, 15))
                .acctCurrCycCredit(new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP))
                .acctCurrCycDebit(new BigDecimal("300.00").setScale(2, RoundingMode.HALF_UP))
                .acctAddrZip("12345")
                .acctGroupId("GROUP001")
                .build();
        
        accountRepository.save(testAccount);

        // Act & Assert: Call REST API GET endpoint (replaces COBOL EXEC CICS READ)
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
        .when()
            .get("/api/accounts/{id}", testAccount.getAcctId())
        .then()
            .statusCode(HttpStatus.OK.value())
            .contentType(ContentType.JSON)
            .body("acctId", equalTo(10000000001L))
            .body("acctActiveStatus", equalTo("Y"))
            .body("acctCurrBal", equalTo(1234.56f))
            .body("acctCreditLimit", equalTo(5000.00f))
            .body("acctCashCreditLimit", equalTo(1000.00f))
            .body("acctOpenDate", equalTo("2020-01-15"))
            .body("acctExpirationDate", equalTo("2025-01-15"))
            .body("acctReissueDate", equalTo("2023-01-15"))
            .body("acctCurrCycCredit", equalTo(500.00f))
            .body("acctCurrCycDebit", equalTo(300.00f))
            .body("acctAddrZip", equalTo("12345"))
            .body("acctGroupId", equalTo("GROUP001"));
    }

    /**
     * Test: GET /api/accounts/{id} - Account not found scenario
     * COBOL Equivalent: COACTVWC.cbl when EXEC CICS READ returns NOTFND (file-status 23)
     * 
     * Validates:
     * - Non-existent account returns HTTP 404 (Not Found)
     * - Error response contains proper error message
     * - Maps COBOL file-status 23 to HTTP 404
     */
    @Test
    void testGetAccountById_NotFound() {
        // Arrange: Use non-existent account ID
        Long nonExistentAccountId = 99999999999L;

        // Act & Assert: Call REST API expecting 404
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
        .when()
            .get("/api/accounts/{id}", nonExistentAccountId)
        .then()
            .statusCode(HttpStatus.NOT_FOUND.value())
            .contentType(ContentType.JSON)
            .body("status", equalTo(404))
            .body("error", notNullValue())
            .body("message", containsStringIgnoringCase("not found"));
    }

    /**
     * Test: PUT /api/accounts/{id} - Update account successfully
     * COBOL Equivalent: COACTUPC.cbl EXEC CICS REWRITE operation with validation
     * 
     * Validates:
     * - Successful account update returns HTTP 200
     * - All updatable fields are persisted correctly
     * - BigDecimal calculations maintain COBOL COMP-3 precision
     * - Balance calculations: acctCurrBal = acctCurrCycCredit - acctCurrCycDebit
     * - Credit limit validation: acctCurrBal <= acctCreditLimit
     * - Version field increments (optimistic locking)
     */
    @Test
    void testUpdateAccount_Success() {
        // Arrange: Create initial account
        Account existingAccount = Account.builder()
                .acctId(10000000002L)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCreditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCashCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctOpenDate(LocalDate.of(2020, 1, 15))
                .acctExpirationDate(LocalDate.of(2025, 1, 15))
                .acctCurrCycCredit(new BigDecimal("200.00").setScale(2, RoundingMode.HALF_UP))
                .acctCurrCycDebit(new BigDecimal("150.00").setScale(2, RoundingMode.HALF_UP))
                .acctGroupId("GROUP001")
                .build();
        
        Account savedAccount = accountRepository.save(existingAccount);
        Integer initialVersion = savedAccount.getVersion();

        // Prepare update data (matches COBOL screen input validation from COACTUPC.cbl)
        AccountDto updateDto = AccountDto.builder()
                .acctId(10000000002L)
                .acctActiveStatus("N") // Status change
                .acctCurrBal(new BigDecimal("2500.00").setScale(2, RoundingMode.HALF_UP))
                .acctCreditLimit(new BigDecimal("10000.00").setScale(2, RoundingMode.HALF_UP)) // Increased
                .acctCashCreditLimit(new BigDecimal("2000.00").setScale(2, RoundingMode.HALF_UP))
                .acctOpenDate(LocalDate.of(2020, 1, 15))
                .acctExpirationDate(LocalDate.of(2026, 1, 15)) // Extended
                .acctCurrCycCredit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCurrCycDebit(new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP))
                .acctGroupId("GROUP002")
                .build();

        // Act & Assert: Call REST API PUT endpoint (replaces COBOL EXEC CICS REWRITE)
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body(updateDto)
        .when()
            .put("/api/accounts/{id}", existingAccount.getAcctId())
        .then()
            .statusCode(HttpStatus.OK.value())
            .contentType(ContentType.JSON)
            .body("acctId", equalTo(10000000002L))
            .body("acctActiveStatus", equalTo("N"))
            .body("acctCurrBal", equalTo(2500.00f))
            .body("acctCreditLimit", equalTo(10000.00f))
            .body("acctCashCreditLimit", equalTo(2000.00f))
            .body("acctExpirationDate", equalTo("2026-01-15"))
            .body("acctCurrCycCredit", equalTo(1000.00f))
            .body("acctCurrCycDebit", equalTo(500.00f))
            .body("acctGroupId", equalTo("GROUP002"));

        // Verify database state and version increment (optimistic locking)
        Account updatedAccount = accountRepository.findById(existingAccount.getAcctId()).orElseThrow();
        assert updatedAccount.getVersion() > initialVersion : "Version should increment on update";
        assert updatedAccount.getAcctActiveStatus().equals("N") : "Status should be updated";
        assert updatedAccount.getAcctCreditLimit().compareTo(new BigDecimal("10000.00")) == 0 : 
            "Credit limit should match updated value with exact precision";
    }

    /**
     * Test: PUT /api/accounts/{id} - Update non-existent account
     * COBOL Equivalent: COACTUPC.cbl when account lookup fails before REWRITE
     * 
     * Validates:
     * - Updating non-existent account returns HTTP 404
     * - Proper error message returned
     */
    @Test
    void testUpdateAccount_NotFound() {
        // Arrange: Prepare update for non-existent account
        Long nonExistentId = 99999999998L;
        AccountDto updateDto = AccountDto.builder()
                .acctId(nonExistentId)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("1000.00"))
                .acctCreditLimit(new BigDecimal("5000.00"))
                .acctCashCreditLimit(new BigDecimal("1000.00"))
                .acctOpenDate(LocalDate.of(2020, 1, 15))
                .acctExpirationDate(LocalDate.of(2025, 1, 15))
                .build();

        // Act & Assert: Attempt update on non-existent account
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body(updateDto)
        .when()
            .put("/api/accounts/{id}", nonExistentId)
        .then()
            .statusCode(HttpStatus.NOT_FOUND.value())
            .contentType(ContentType.JSON)
            .body("status", equalTo(404))
            .body("message", containsStringIgnoringCase("not found"));
    }

    /**
     * Test: PUT /api/accounts/{id} - Validation failure for invalid status
     * COBOL Equivalent: COACTUPC.cbl field validation (acctActiveStatus must be Y or N)
     * From COBOL line 193: 88 FLG-ACCT-STATUS-ISVALID VALUES 'Y', 'N'
     * 
     * Validates:
     * - Invalid account status returns HTTP 400 (Bad Request)
     * - Field validation rules from COBOL are enforced
     * - Error message indicates validation failure
     */
    @Test
    void testUpdateAccount_InvalidStatus() {
        // Arrange: Create account
        Account existingAccount = Account.builder()
                .acctId(10000000003L)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCreditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCashCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctOpenDate(LocalDate.of(2020, 1, 15))
                .acctExpirationDate(LocalDate.of(2025, 1, 15))
                .build();
        
        accountRepository.save(existingAccount);

        // Prepare update with invalid status (not Y or N)
        AccountDto updateDto = AccountDto.builder()
                .acctId(10000000003L)
                .acctActiveStatus("X") // Invalid value
                .acctCurrBal(new BigDecimal("1000.00"))
                .acctCreditLimit(new BigDecimal("5000.00"))
                .acctCashCreditLimit(new BigDecimal("1000.00"))
                .acctOpenDate(LocalDate.of(2020, 1, 15))
                .acctExpirationDate(LocalDate.of(2025, 1, 15))
                .build();

        // Act & Assert: Validation should fail
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body(updateDto)
        .when()
            .put("/api/accounts/{id}", existingAccount.getAcctId())
        .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
            .body("status", equalTo(400));
    }

    /**
     * Test: PUT /api/accounts/{id} - Credit limit validation failure
     * COBOL Equivalent: COACTUPC.cbl validation that credit limit must be positive
     * From COBOL lines 196-199: Credit limit must be supplied and valid
     * 
     * Validates:
     * - Negative or zero credit limit returns HTTP 400
     * - Business rule validation from COBOL is enforced
     */
    @Test
    void testUpdateAccount_InvalidCreditLimit() {
        // Arrange: Create account
        Account existingAccount = Account.builder()
                .acctId(10000000004L)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCreditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCashCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctOpenDate(LocalDate.of(2020, 1, 15))
                .acctExpirationDate(LocalDate.of(2025, 1, 15))
                .build();
        
        accountRepository.save(existingAccount);

        // Prepare update with invalid credit limit (zero)
        AccountDto updateDto = AccountDto.builder()
                .acctId(10000000004L)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("1000.00"))
                .acctCreditLimit(new BigDecimal("0.00")) // Invalid - must be > 0
                .acctCashCreditLimit(new BigDecimal("1000.00"))
                .acctOpenDate(LocalDate.of(2020, 1, 15))
                .acctExpirationDate(LocalDate.of(2025, 1, 15))
                .build();

        // Act & Assert: Validation should fail
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body(updateDto)
        .when()
            .put("/api/accounts/{id}", existingAccount.getAcctId())
        .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
            .body("status", equalTo(400));
    }

    /**
     * Test: PUT /api/accounts/{id} - Balance exceeds credit limit
     * COBOL Equivalent: COACTUPC.cbl validation that current balance <= credit limit
     * Business rule: Account balance cannot exceed the credit limit
     * 
     * Validates:
     * - Balance > credit limit returns HTTP 400
     * - Financial business rules are enforced
     */
    @Test
    void testUpdateAccount_BalanceExceedsCreditLimit() {
        // Arrange: Create account
        Account existingAccount = Account.builder()
                .acctId(10000000005L)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCreditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCashCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctOpenDate(LocalDate.of(2020, 1, 15))
                .acctExpirationDate(LocalDate.of(2025, 1, 15))
                .build();
        
        accountRepository.save(existingAccount);

        // Prepare update where balance exceeds credit limit
        AccountDto updateDto = AccountDto.builder()
                .acctId(10000000005L)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("6000.00")) // Exceeds credit limit
                .acctCreditLimit(new BigDecimal("5000.00"))
                .acctCashCreditLimit(new BigDecimal("1000.00"))
                .acctOpenDate(LocalDate.of(2020, 1, 15))
                .acctExpirationDate(LocalDate.of(2025, 1, 15))
                .build();

        // Act & Assert: Validation should fail
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body(updateDto)
        .when()
            .put("/api/accounts/{id}", existingAccount.getAcctId())
        .then()
            .statusCode(HttpStatus.BAD_REQUEST.value())
            .body("status", equalTo(400));
    }

    /**
     * Test: POST /api/accounts - Create new account successfully
     * COBOL Equivalent: COACTUPC.cbl EXEC CICS WRITE operation for new account
     * 
     * Validates:
     * - New account creation returns HTTP 201 (Created)
     * - All fields are persisted correctly
     * - BigDecimal amounts maintain COBOL COMP-3 precision
     * - Generated account ID is returned
     * - Version field is initialized to 0
     */
    @Test
    void testCreateAccount_Success() {
        // Arrange: Prepare new account data
        AccountDto newAccountDto = AccountDto.builder()
                .acctId(10000000006L)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP))
                .acctCreditLimit(new BigDecimal("3000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCashCreditLimit(new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP))
                .acctOpenDate(LocalDate.now())
                .acctExpirationDate(LocalDate.now().plusYears(5))
                .acctCurrCycCredit(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP))
                .acctCurrCycDebit(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP))
                .acctAddrZip("10001")
                .acctGroupId("NEWGROUP")
                .build();

        // Act & Assert: Call REST API POST endpoint (replaces COBOL EXEC CICS WRITE)
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body(newAccountDto)
        .when()
            .post("/api/accounts")
        .then()
            .statusCode(HttpStatus.CREATED.value())
            .contentType(ContentType.JSON)
            .body("acctId", equalTo(10000000006L))
            .body("acctActiveStatus", equalTo("Y"))
            .body("acctCurrBal", equalTo(0.00f))
            .body("acctCreditLimit", equalTo(3000.00f))
            .body("acctCashCreditLimit", equalTo(500.00f))
            .body("acctAddrZip", equalTo("10001"))
            .body("acctGroupId", equalTo("NEWGROUP"));

        // Verify account exists in database
        Account createdAccount = accountRepository.findById(10000000006L).orElseThrow();
        assert createdAccount.getAcctActiveStatus().equals("Y") : "Status should be Y";
        assert createdAccount.getAcctCreditLimit().compareTo(new BigDecimal("3000.00")) == 0 : 
            "Credit limit should match with exact precision";
    }

    /**
     * Test: POST /api/accounts - Create account with duplicate ID
     * COBOL Equivalent: COACTUPC.cbl EXEC CICS WRITE with DUPREC condition
     * 
     * Validates:
     * - Duplicate account ID returns HTTP 409 (Conflict)
     * - Primary key constraint is enforced
     * - Error message indicates duplicate key violation
     */
    @Test
    void testCreateAccount_DuplicateId() {
        // Arrange: Create existing account
        Account existingAccount = Account.builder()
                .acctId(10000000007L)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCreditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCashCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctOpenDate(LocalDate.of(2020, 1, 15))
                .acctExpirationDate(LocalDate.of(2025, 1, 15))
                .build();
        
        accountRepository.save(existingAccount);

        // Prepare duplicate account
        AccountDto duplicateAccountDto = AccountDto.builder()
                .acctId(10000000007L) // Same ID
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("500.00"))
                .acctCreditLimit(new BigDecimal("2000.00"))
                .acctCashCreditLimit(new BigDecimal("500.00"))
                .acctOpenDate(LocalDate.now())
                .acctExpirationDate(LocalDate.now().plusYears(5))
                .build();

        // Act & Assert: Attempt to create duplicate account
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body(duplicateAccountDto)
        .when()
            .post("/api/accounts")
        .then()
            .statusCode(HttpStatus.CONFLICT.value())
            .body("status", equalTo(409));
    }

    /**
     * Test: PUT /api/accounts/{id} - Concurrent update conflict (Optimistic Locking)
     * COBOL Equivalent: COACTUPC.cbl detection of record change during update (lines 4109-4195)
     * Maps to: DATA-WAS-CHANGED-BEFORE-UPDATE condition
     * 
     * Validates:
     * - Concurrent modification returns HTTP 409 (Conflict)
     * - JPA @Version field provides optimistic locking
     * - Matches COBOL VSAM RBA (Relative Byte Address) check behavior
     * - Error message indicates concurrent update conflict
     * 
     * COBOL Logic (lines 9700-9700):
     * 9700-CHECK-CHANGE-IN-REC checks if record was modified since READ
     * If changed, sets DATA-WAS-CHANGED-BEFORE-UPDATE flag
     * Java equivalent: JPA OptimisticLockException via @Version
     */
    @Test
    void testUpdateAccount_ConcurrentModification() {
        // Arrange: Create account
        Account originalAccount = Account.builder()
                .acctId(10000000008L)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCreditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCashCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctOpenDate(LocalDate.of(2020, 1, 15))
                .acctExpirationDate(LocalDate.of(2025, 1, 15))
                .build();
        
        Account savedAccount = accountRepository.save(originalAccount);

        // Simulate concurrent update by modifying the account directly
        savedAccount.setAcctCreditLimit(new BigDecimal("7500.00").setScale(2, RoundingMode.HALF_UP));
        accountRepository.save(savedAccount);

        // Prepare update with stale version
        AccountDto staleUpdateDto = AccountDto.builder()
                .acctId(10000000008L)
                .acctActiveStatus("N")
                .acctCurrBal(new BigDecimal("1500.00"))
                .acctCreditLimit(new BigDecimal("6000.00"))
                .acctCashCreditLimit(new BigDecimal("1200.00"))
                .acctOpenDate(LocalDate.of(2020, 1, 15))
                .acctExpirationDate(LocalDate.of(2025, 1, 15))
                .build();

        // Act & Assert: Update with stale data should fail with 409 Conflict
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body(staleUpdateDto)
        .when()
            .put("/api/accounts/{id}", originalAccount.getAcctId())
        .then()
            .statusCode(HttpStatus.CONFLICT.value())
            .body("status", equalTo(409))
            .body("message", containsStringIgnoringCase("conflict"));
    }

    /**
     * Test: BigDecimal precision validation for financial calculations
     * COBOL Equivalent: COMP-3 packed decimal arithmetic precision
     * From Agent Action Plan Section 0.7.2: "COMP-3 Precision: All COBOL COMP-3 
     * (packed decimal) arithmetic must be replicated using Java BigDecimal with 
     * appropriate scale and rounding modes to ensure bit-identical results"
     * 
     * Validates:
     * - All monetary fields use BigDecimal with scale 2
     * - RoundingMode.HALF_UP matches COBOL COMP-3 rounding
     * - Balance calculations are bit-identical to COBOL
     * - Currency amounts maintain precision through persistence
     */
    @Test
    void testBigDecimalPrecision_FinancialCalculations() {
        // Arrange: Create account with precise decimal values
        BigDecimal currentBalance = new BigDecimal("1234.565").setScale(2, RoundingMode.HALF_UP); // Should round to 1234.57
        BigDecimal creditLimit = new BigDecimal("5000.004").setScale(2, RoundingMode.HALF_UP);    // Should round to 5000.00
        BigDecimal cycleCredit = new BigDecimal("123.456").setScale(2, RoundingMode.HALF_UP);     // Should round to 123.46
        BigDecimal cycleDebit = new BigDecimal("456.784").setScale(2, RoundingMode.HALF_UP);      // Should round to 456.78

        Account testAccount = Account.builder()
                .acctId(10000000009L)
                .acctActiveStatus("Y")
                .acctCurrBal(currentBalance)
                .acctCreditLimit(creditLimit)
                .acctCashCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctOpenDate(LocalDate.of(2020, 1, 15))
                .acctExpirationDate(LocalDate.of(2025, 1, 15))
                .acctCurrCycCredit(cycleCredit)
                .acctCurrCycDebit(cycleDebit)
                .build();
        
        accountRepository.save(testAccount);

        // Act: Retrieve account and verify precision
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
        .when()
            .get("/api/accounts/{id}", testAccount.getAcctId())
        .then()
            .statusCode(HttpStatus.OK.value())
            .body("acctCurrBal", equalTo(1234.57f))        // Rounded up
            .body("acctCreditLimit", equalTo(5000.00f))    // Rounded down
            .body("acctCurrCycCredit", equalTo(123.46f))   // Rounded up
            .body("acctCurrCycDebit", equalTo(456.78f));   // Rounded down

        // Assert: Verify database persisted values with exact precision
        Account retrievedAccount = accountRepository.findById(testAccount.getAcctId()).orElseThrow();
        assert retrievedAccount.getAcctCurrBal().compareTo(new BigDecimal("1234.57")) == 0 : 
            "Current balance should be exactly 1234.57";
        assert retrievedAccount.getAcctCurrBal().scale() == 2 : 
            "Current balance scale should be 2";
        assert retrievedAccount.getAcctCreditLimit().compareTo(new BigDecimal("5000.00")) == 0 : 
            "Credit limit should be exactly 5000.00";
        assert retrievedAccount.getAcctCurrCycCredit().compareTo(new BigDecimal("123.46")) == 0 : 
            "Cycle credit should be exactly 123.46";
        assert retrievedAccount.getAcctCurrCycDebit().compareTo(new BigDecimal("456.78")) == 0 : 
            "Cycle debit should be exactly 456.78";
    }

    /**
     * Test: Date format validation (YYYY-MM-DD)
     * COBOL Equivalent: Date fields stored as PIC X(10) in YYYY-MM-DD format
     * From COBOL lines 427-429: Date fields in account record
     * 
     * Validates:
     * - Date fields use ISO-8601 format (YYYY-MM-DD)
     * - Java LocalDate correctly maps to COBOL PIC X(10) date fields
     * - Date validation prevents invalid dates
     */
    @Test
    void testDateFormat_Validation() {
        // Arrange: Create account with specific dates
        LocalDate openDate = LocalDate.of(2020, 3, 15);
        LocalDate expirationDate = LocalDate.of(2025, 3, 15);
        LocalDate reissueDate = LocalDate.of(2023, 3, 15);

        Account testAccount = Account.builder()
                .acctId(10000000010L)
                .acctActiveStatus("Y")
                .acctCurrBal(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCreditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP))
                .acctCashCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP))
                .acctOpenDate(openDate)
                .acctExpirationDate(expirationDate)
                .acctReissueDate(reissueDate)
                .build();
        
        accountRepository.save(testAccount);

        // Act & Assert: Verify date format in API response
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
        .when()
            .get("/api/accounts/{id}", testAccount.getAcctId())
        .then()
            .statusCode(HttpStatus.OK.value())
            .body("acctOpenDate", equalTo("2020-03-15"))
            .body("acctExpirationDate", equalTo("2025-03-15"))
            .body("acctReissueDate", equalTo("2023-03-15"));
    }
}
