# CardDemo Testing Strategy and Execution Guide

## Table of Contents

1. [Testing Strategy Overview](#1-testing-strategy-overview)
2. [Unit Testing (Backend)](#2-unit-testing-backend)
3. [Integration Testing (Backend)](#3-integration-testing-backend)
4. [REST API Testing](#4-rest-api-testing)
5. [Batch Processing Testing](#5-batch-processing-testing)
6. [Frontend Testing (React)](#6-frontend-testing-react)
7. [Test Data Management](#7-test-data-management)
8. [Functional Equivalence Testing](#8-functional-equivalence-testing)
9. [Performance Testing](#9-performance-testing)
10. [Security Testing](#10-security-testing)
11. [CI/CD Integration](#11-cicd-integration)
12. [Test Execution Procedures](#12-test-execution-procedures)
13. [Regression Testing](#13-regression-testing)
14. [Documentation and Standards](#14-documentation-and-standards)

---

## 1. Testing Strategy Overview

### 1.1 Testing Pyramid Approach

The CardDemo testing strategy follows the industry-standard testing pyramid, ensuring comprehensive coverage while maintaining fast feedback cycles and efficient resource utilization.

```
                    ╱╲
                   ╱  ╲
                  ╱ E2E ╲         (10% - End-to-End Tests)
                 ╱──────╲
                ╱        ╲
               ╱Integration╲      (30% - Integration Tests)
              ╱────────────╲
             ╱              ╲
            ╱  Unit Tests    ╲   (60% - Unit Tests)
           ╱──────────────────╱
```

**Test Distribution:**
- **Unit Tests (60%)**: 61+ test classes covering individual components, services, and utilities
- **Integration Tests (30%)**: Database integration, REST API integration, batch job integration
- **End-to-End Tests (10%)**: Complete workflow validation from UI to database

### 1.2 Functional Equivalence Validation

The primary objective of the testing strategy is to validate 100% functional equivalence with the original COBOL/CICS mainframe application.

**Equivalence Validation Principles:**
- **Input Parity**: All test inputs match COBOL test data exactly (field lengths, formats, values)
- **Output Verification**: Java application outputs match COBOL outputs character-for-character or byte-for-byte
- **Business Rule Consistency**: All validation rules, calculations, and business logic produce identical results
- **Error Handling Equivalence**: Error codes, messages, and recovery behavior match mainframe implementation

**Test Case Compatibility Requirement:**
All 50+ existing COBOL unit test scenarios must pass without modification to expected outcomes after migration.

### 1.3 Test-Driven Migration Approach

The migration follows a test-driven approach to ensure business logic preservation:

1. **Analyze COBOL Test Case**: Extract inputs, expected outputs, and business rules
2. **Create Equivalent Java Test**: Implement JUnit test with identical inputs and assertions
3. **Implement Java Component**: Develop service/controller/repository to satisfy test
4. **Validate Equivalence**: Compare outputs with COBOL execution results
5. **Refine if Necessary**: Adjust implementation to achieve exact match

### 1.4 CI/CD Integration

All tests are integrated into the CI/CD pipeline with automated execution on every commit:

- **Pre-Commit**: Local unit test execution (developer responsibility)
- **Pull Request**: Full test suite execution (unit + integration)
- **Merge to Main**: Regression test suite + performance tests
- **Pre-Deployment**: Full E2E test suite + security scans
- **Deployment Gate**: Minimum 80% line coverage, 70% branch coverage required

### 1.5 Test Coverage Targets

**Mandatory Coverage Requirements:**
- **Line Coverage**: Minimum 80% across all backend Java code
- **Branch Coverage**: Minimum 70% for conditional logic and business rules
- **Service Layer**: Minimum 90% coverage (critical business logic)
- **Controller Layer**: Minimum 85% coverage (API contracts)
- **Repository Layer**: Minimum 75% coverage (data access patterns)
- **Batch Jobs**: Minimum 85% coverage (batch processing logic)

**Frontend Coverage Requirements:**
- **React Components**: Minimum 75% statement coverage
- **Redux Slices**: Minimum 80% coverage (state management logic)
- **Service Functions**: Minimum 85% coverage (API client logic)

---

## 2. Unit Testing (Backend)

### 2.1 JUnit 5 Framework

All backend unit tests utilize JUnit 5 (Jupiter) with Spring Boot Test annotations for dependency injection and configuration.

**Core Dependencies:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <version>3.2.1</version>
    <scope>test</scope>
</dependency>
```

**Included Testing Libraries:**
- JUnit 5 (Jupiter) - Test framework
- Mockito 5.8.0 - Mocking framework
- AssertJ 3.24.2 - Fluent assertions
- Hamcrest 2.2 - Matcher assertions
- JSONassert 1.5.1 - JSON comparison
- JsonPath 2.9.0 - JSON path expressions

### 2.2 Service Layer Testing

Service layer tests validate business logic extracted from COBOL programs, ensuring functional equivalence.

**Test Structure Pattern:**
```java
@ExtendWith(MockitoExtension.class)
class AccountViewServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private AccountViewService accountViewService;

    @Test
    @DisplayName("View account details - should return complete account with customer info")
    void testViewAccount_ValidAccountId_ReturnsAccountDetails() {
        // Given: Test data matching COBOL test case INPUT-01
        Long accountId = 1000000001L;
        Customer mockCustomer = createMockCustomer(19750101L, "John", "Doe");
        Account mockAccount = createMockAccount(accountId, mockCustomer, 
            new BigDecimal("5000.00"), new BigDecimal("1500.50"));
        
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(mockAccount));
        
        // When: Service method called (equivalent to COBOL COACTVWC PERFORM MAIN-PROCESS)
        AccountViewResponse response = accountViewService.viewAccount(accountId);
        
        // Then: Verify outputs match COBOL EXPECTED-OUTPUT-01
        assertThat(response).isNotNull();
        assertThat(response.getAccountId()).isEqualTo(accountId);
        assertThat(response.getAccountBalance()).isEqualByComparingTo("5000.00");
        assertThat(response.getAvailableCredit()).isEqualByComparingTo("1500.50");
        assertThat(response.getCustomerName()).isEqualTo("John Doe");
        
        verify(accountRepository, times(1)).findById(accountId);
    }

    @Test
    @DisplayName("View account - account not found - should throw exception")
    void testViewAccount_InvalidAccountId_ThrowsException() {
        // Given: Invalid account ID (COBOL test case ERROR-01)
        Long invalidAccountId = 9999999999L;
        when(accountRepository.findById(invalidAccountId)).thenReturn(Optional.empty());
        
        // When/Then: Expect exception matching COBOL file-status 23 (record not found)
        assertThatThrownBy(() -> accountViewService.viewAccount(invalidAccountId))
            .isInstanceOf(AccountNotFoundException.class)
            .hasMessageContaining("Account not found: 9999999999");
    }

    // Additional test methods for pagination, filtering, sorting...
}
```

**Service Testing Best Practices:**
- Use `@ExtendWith(MockitoExtension.class)` for lightweight Mockito integration
- Mock all repository dependencies to isolate business logic
- Test both happy path and error scenarios from COBOL test cases
- Validate BigDecimal precision matches COBOL COMP-3 calculations
- Verify exception handling matches COBOL error codes

### 2.3 Repository Testing

Repository tests validate data access patterns and query correctness using @DataJpaTest with H2 in-memory database.

**Test Structure Pattern:**
```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql(scripts = "/test-data/account-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AccountRepositoryTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("Find by account ID - should return account with customer relationship")
    void testFindById_ValidId_ReturnsAccountWithCustomer() {
        // Given: Account exists in test data (matching VSAM ACCTDAT record key 1000000001)
        Long accountId = 1000000001L;
        
        // When: Repository findById called (equivalent to VSAM READ with key)
        Optional<Account> result = accountRepository.findById(accountId);
        
        // Then: Verify record retrieved matches VSAM record layout
        assertThat(result).isPresent();
        Account account = result.get();
        assertThat(account.getAccountId()).isEqualTo(accountId);
        assertThat(account.getCustomer()).isNotNull();
        assertThat(account.getAccountStatus()).isEqualTo("A"); // Active status
        
        // Verify COMP-3 balance precision preserved
        assertThat(account.getCurrentBalance().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("Find accounts by customer ID - should return all customer accounts")
    void testFindByCustomerId_ValidCustomer_ReturnsAccountList() {
        // Given: Customer with multiple accounts (COBOL test scenario MULTI-ACCT-01)
        Long customerId = 19750101L;
        
        // When: Custom query executed (equivalent to VSAM alternate index read)
        List<Account> accounts = accountRepository.findByCustomer_CustomerId(customerId);
        
        // Then: Verify all accounts returned with correct relationship
        assertThat(accounts).hasSize(3);
        assertThat(accounts).extracting(Account::getCustomer)
            .extracting(Customer::getCustomerId)
            .containsOnly(customerId);
    }

    @Test
    @DisplayName("Update account balance - transaction should commit successfully")
    void testUpdateAccountBalance_ValidUpdate_CommitsSuccessfully() {
        // Given: Existing account with initial balance
        Long accountId = 1000000001L;
        Account account = entityManager.find(Account.class, accountId);
        BigDecimal newBalance = new BigDecimal("7500.00").setScale(2, RoundingMode.HALF_UP);
        
        // When: Balance updated (equivalent to VSAM REWRITE)
        account.setCurrentBalance(newBalance);
        accountRepository.save(account);
        entityManager.flush();
        entityManager.clear();
        
        // Then: Verify persisted value matches with COMP-3 precision
        Account updatedAccount = entityManager.find(Account.class, accountId);
        assertThat(updatedAccount.getCurrentBalance()).isEqualByComparingTo(newBalance);
    }
}
```

**Repository Testing Best Practices:**
- Use @DataJpaTest for optimized JPA testing context
- Load test data with @Sql scripts matching COBOL test data
- Test custom query methods thoroughly
- Verify cascade operations and foreign key constraints
- Validate transaction rollback behavior

### 2.4 Utility Class Testing

Utility classes require thorough testing to ensure COBOL utility function equivalence.

**BigDecimal Precision Validation Example:**
```java
class DecimalUtilsTest {

    @Test
    @DisplayName("COMP-3 conversion - PIC S9(13)V99 - preserves precision")
    void testComp3Conversion_Balance_PreservesPrecision() {
        // Given: COBOL COMP-3 value PIC S9(13)V99 = +00000123456.78
        String cobolValue = "123456.78";
        
        // When: Convert to BigDecimal with COMP-3 equivalent precision
        BigDecimal result = DecimalUtils.toComp3Decimal(cobolValue, 13, 2);
        
        // Then: Verify scale and precision match COBOL
        assertThat(result.scale()).isEqualTo(2);
        assertThat(result.precision()).isLessThanOrEqualTo(15); // 13 + 2
        assertThat(result.toPlainString()).isEqualTo("123456.78");
    }

    @Test
    @DisplayName("Interest calculation - COBOL formula equivalence")
    void testInterestCalculation_DailyInterest_MatchesCobolFormula() {
        // Given: COBOL interest calc from CBACT04C
        BigDecimal principal = new BigDecimal("10000.00");
        BigDecimal annualRate = new BigDecimal("0.15"); // 15% APR
        int days = 30;
        
        // When: Calculate using Java equivalent of COBOL formula
        // COBOL: COMPUTE INTEREST = PRINCIPAL * ANNUAL-RATE / 365 * DAYS
        BigDecimal dailyRate = annualRate.divide(new BigDecimal("365"), 8, RoundingMode.HALF_UP);
        BigDecimal interest = principal.multiply(dailyRate).multiply(new BigDecimal(days))
            .setScale(2, RoundingMode.HALF_UP);
        
        // Then: Verify matches COBOL expected output (from CBACT04C test case)
        assertThat(interest).isEqualByComparingTo("123.29");
    }
}
```

### 2.5 Test Coverage Measurement

Execute test coverage analysis using JaCoCo Maven plugin:

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.11</version>
    <executions>
        <execution>
            <goals>
                <goal>prepare-agent</goal>
            </goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals>
                <goal>report</goal>
            </goals>
        </execution>
        <execution>
            <id>jacoco-check</id>
            <goals>
                <goal>check</goal>
            </goals>
            <configuration>
                <rules>
                    <rule>
                        <element>PACKAGE</element>
                        <limits>
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.80</minimum>
                            </limit>
                            <limit>
                                <counter>BRANCH</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.70</minimum>
                            </limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

**Coverage Report Location:**
- HTML Report: `target/site/jacoco/index.html`
- XML Report: `target/site/jacoco/jacoco.xml` (for CI/CD integration)

---

## 3. Integration Testing (Backend)

### 3.1 Testcontainers for PostgreSQL Integration

Integration tests use Testcontainers to spin up real PostgreSQL instances, ensuring database behavior matches production.

**Test Configuration:**
```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AccountIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.5-alpine")
        .withDatabaseName("carddemo_test")
        .withUsername("test_user")
        .withPassword("test_pass")
        .withInitScript("test-data/init-schema.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    @DisplayName("Account creation - full transaction with customer validation")
    void testCreateAccount_ValidCustomer_PersistsSuccessfully() {
        // Given: Account creation request matching COBOL COACTADD input
        AccountAddRequest request = new AccountAddRequest();
        request.setCustomerId(19750101L);
        request.setAccountType("CC"); // Credit Card
        request.setCreditLimit(new BigDecimal("10000.00"));
        request.setAccountStatus("A");
        
        // When: Service creates account (equivalent to CICS transaction with SYNCPOINT)
        AccountViewResponse response = accountService.createAccount(request);
        
        // Then: Verify account persisted with foreign key to customer
        assertThat(response.getAccountId()).isNotNull();
        Account persistedAccount = accountRepository.findById(response.getAccountId()).orElseThrow();
        assertThat(persistedAccount.getCustomer().getCustomerId()).isEqualTo(19750101L);
        assertThat(persistedAccount.getCreditLimit()).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("Transaction rollback - invalid data should not persist")
    void testUpdateAccount_ValidationFailure_RollsBackTransaction() {
        // Given: Invalid update request (credit limit exceeds maximum)
        Long accountId = 1000000001L;
        AccountUpdateRequest request = new AccountUpdateRequest();
        request.setCreditLimit(new BigDecimal("1000000.00")); // Exceeds $999,999 limit
        
        // When/Then: Update fails and transaction rolls back (CICS SYNCPOINT ROLLBACK equivalent)
        assertThatThrownBy(() -> accountService.updateAccount(accountId, request))
            .isInstanceOf(ValidationException.class);
        
        // Verify original account data unchanged
        Account account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getCreditLimit()).isNotEqualByComparingTo("1000000.00");
    }
}
```

### 3.2 Spring Boot @SpringBootTest Integration

Full application context integration tests validate complete request-to-response flows including transaction management.

**Test Pattern:**
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Sql(scripts = "/test-data/full-dataset.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/test-data/cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class CardManagementIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String generateAuthToken() {
        return jwtTokenProvider.generateToken("testuser", List.of("ROLE_USER"));
    }

    @Test
    @DisplayName("Card list retrieval - pagination and sorting equivalent to COCRDLIC")
    void testGetCardList_WithPagination_ReturnsPagedResults() throws Exception {
        // Given: Authenticated user with valid token
        String token = generateAuthToken();
        
        // When: Request card list with pagination (COBOL 7 cards per page)
        mockMvc.perform(get("/api/cards")
                .header("Authorization", "Bearer " + token)
                .param("page", "0")
                .param("size", "7")
                .param("sort", "cardNumber,asc"))
            // Then: Response matches COBOL COCRDLIC output format
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.content.length()").value(7))
            .andExpect(jsonPath("$.totalElements").exists())
            .andExpect(jsonPath("$.totalPages").exists())
            .andExpect(jsonPath("$.pageable.pageNumber").value(0))
            .andExpect(jsonPath("$.pageable.pageSize").value(7));
    }
}
```

### 3.3 Transaction Testing with @Transactional

Validate Spring @Transactional behavior matches CICS SYNCPOINT semantics:

```java
@SpringBootTest
@Transactional
class TransactionBoundaryTest {

    @Autowired
    private BillPaymentService billPaymentService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    @DisplayName("Bill payment - atomic transaction across account and transaction tables")
    void testBillPayment_Success_UpdatesAccountAndCreatesTransaction() {
        // Given: Account with sufficient balance for payment
        Long accountId = 1000000001L;
        BigDecimal initialBalance = new BigDecimal("5000.00");
        BigDecimal paymentAmount = new BigDecimal("150.00");
        
        BillPaymentRequest request = new BillPaymentRequest();
        request.setAccountId(accountId);
        request.setPaymentAmount(paymentAmount);
        request.setPayeeName("Electric Company");
        
        // When: Payment processed (equivalent to COBIL00C with SYNCPOINT)
        BillPaymentResponse response = billPaymentService.processPayment(request);
        
        // Then: Verify atomic update - both account balance and transaction record created
        Account account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getCurrentBalance())
            .isEqualByComparingTo(initialBalance.subtract(paymentAmount));
        
        Transaction transaction = transactionRepository.findById(response.getTransactionId()).orElseThrow();
        assertThat(transaction.getTransactionAmount()).isEqualByComparingTo(paymentAmount);
        assertThat(transaction.getTransactionType()).isEqualTo("BILLPAY");
    }

    @Test
    @DisplayName("Bill payment - insufficient balance - rolls back entire transaction")
    @Rollback
    void testBillPayment_InsufficientBalance_RollsBackTransaction() {
        // Given: Account with insufficient balance
        Long accountId = 1000000002L;
        BigDecimal paymentAmount = new BigDecimal("10000.00");
        
        BillPaymentRequest request = new BillPaymentRequest();
        request.setAccountId(accountId);
        request.setPaymentAmount(paymentAmount);
        
        // When/Then: Payment fails with exception (CICS SYNCPOINT ROLLBACK)
        assertThatThrownBy(() -> billPaymentService.processPayment(request))
            .isInstanceOf(InsufficientBalanceException.class);
        
        // Verify no transaction record created and balance unchanged
        List<Transaction> transactions = transactionRepository.findByAccount_AccountId(accountId);
        long paymentCount = transactions.stream()
            .filter(t -> t.getTransactionAmount().compareTo(paymentAmount) == 0)
            .count();
        assertThat(paymentCount).isZero();
    }
}
```

### 3.4 Database Migration Testing

Validate Flyway migrations execute correctly and produce expected schema:

```java
@SpringBootTest
@Testcontainers
class FlywayMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.5-alpine");

    @Test
    @DisplayName("Flyway migrations - all versions execute successfully")
    void testFlywayMigrations_AllVersions_ExecuteSuccessfully(@Autowired Flyway flyway) {
        // When: Flyway migrations run on clean database
        MigrationInfo[] migrations = flyway.info().all();
        
        // Then: Verify all migrations successful
        assertThat(migrations).isNotEmpty();
        assertThat(Arrays.stream(migrations).allMatch(m -> m.getState().isSuccess())).isTrue();
        
        // Verify expected schema elements exist (matching VSAM file structure)
        assertThat(migrations).extracting(MigrationInfo::getDescription)
            .contains(
                "create customer table",
                "create account table",
                "create card table",
                "create transaction table",
                "create user security table",
                "create xref tables",
                "create indexes",
                "create foreign keys"
            );
    }

    @Test
    @DisplayName("Schema validation - foreign keys enforce referential integrity")
    void testSchemaValidation_ForeignKeys_EnforceIntegrity(@Autowired DataSource dataSource) throws SQLException {
        // Given: Database with schema applied
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            
            // When: Query foreign key constraints
            ResultSet foreignKeys = metaData.getImportedKeys(null, null, "account");
            
            // Then: Verify foreign key to customer table exists (XREF equivalent)
            boolean hasCustomerFK = false;
            while (foreignKeys.next()) {
                if ("customer".equals(foreignKeys.getString("PKTABLE_NAME"))) {
                    hasCustomerFK = true;
                }
            }
            assertThat(hasCustomerFK).isTrue();
        }
    }
}
```

---

## 4. REST API Testing

### 4.1 RestAssured for Endpoint Testing

REST API tests validate endpoint behavior, request/response formats, and HTTP semantics using RestAssured framework.

**Setup and Configuration:**
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthenticationControllerApiTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @BeforeAll
    void setup() {
        RestAssured.port = port;
        RestAssured.basePath = "/api";
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @Test
    @DisplayName("POST /auth/login - valid credentials - returns JWT token")
    void testLogin_ValidCredentials_ReturnsToken() {
        // Given: Login request matching COBOL COSGN00C input
        LoginRequest loginRequest = new LoginRequest("testuser", "password123");
        
        // When: POST to login endpoint (equivalent to CICS transaction CC00)
        given()
            .contentType(ContentType.JSON)
            .body(loginRequest)
        .when()
            .post("/auth/login")
        .then()
            .statusCode(200)
            .body("token", notNullValue())
            .body("userName", equalTo("Test User"))
            .body("userType", equalTo("R")) // Regular user
            .body("responseCode", equalTo("00")); // Success code matching COBOL
    }

    @Test
    @DisplayName("POST /auth/login - invalid credentials - returns 401 Unauthorized")
    void testLogin_InvalidCredentials_ReturnsUnauthorized() {
        // Given: Invalid login credentials (COBOL COSGN00C error scenario)
        LoginRequest loginRequest = new LoginRequest("invaliduser", "wrongpass");
        
        // When/Then: Login fails with 401 (CICS RESP=70 equivalent)
        given()
            .contentType(ContentType.JSON)
            .body(loginRequest)
        .when()
            .post("/auth/login")
        .then()
            .statusCode(401)
            .body("responseCode", equalTo("01")) // Authentication failure code
            .body("message", containsString("Invalid credentials"));
    }
}
```

### 4.2 JSON Schema Validation

Validate request/response DTOs match expected JSON schemas derived from COBOL COMMAREA structures:

```java
@Test
@DisplayName("GET /accounts/{id} - response matches JSON schema")
void testGetAccount_ValidResponse_MatchesSchema() {
    // Given: Valid account ID and auth token
    String token = jwtTokenProvider.generateToken("testuser", List.of("ROLE_USER"));
    Long accountId = 1000000001L;
    
    // When: GET account endpoint called
    given()
        .header("Authorization", "Bearer " + token)
    .when()
        .get("/accounts/" + accountId)
    .then()
        .statusCode(200)
        .body(matchesJsonSchemaInClasspath("schemas/account-view-response-schema.json"));
}
```

**Example JSON Schema (account-view-response-schema.json):**
```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["accountId", "customerId", "customerName", "accountStatus", "currentBalance"],
  "properties": {
    "accountId": { "type": "integer", "minimum": 1000000000, "maximum": 9999999999 },
    "customerId": { "type": "integer" },
    "customerName": { "type": "string", "maxLength": 50 },
    "accountStatus": { "type": "string", "enum": ["A", "C", "S"] },
    "currentBalance": { "type": "string", "pattern": "^-?\\d+\\.\\d{2}$" },
    "creditLimit": { "type": "string", "pattern": "^\\d+\\.\\d{2}$" },
    "availableCredit": { "type": "string", "pattern": "^\\d+\\.\\d{2}$" }
  }
}
```

### 4.3 Authentication and Authorization Testing

Validate JWT token-based authentication and role-based access control:

```java
@Test
@DisplayName("GET /admin/users - ROLE_ADMIN required - returns 403 for regular user")
void testAdminEndpoint_RegularUser_ReturnsForbidden() {
    // Given: Token with ROLE_USER only (COBOL USER-TYPE='R')
    String userToken = jwtTokenProvider.generateToken("regularuser", List.of("ROLE_USER"));
    
    // When: Access admin endpoint (equivalent to COADM01C program check)
    given()
        .header("Authorization", "Bearer " + userToken)
    .when()
        .get("/admin/users")
    .then()
        .statusCode(403); // Forbidden - insufficient privileges
}

@Test
@DisplayName("GET /admin/users - ROLE_ADMIN - returns user list")
void testAdminEndpoint_AdminUser_ReturnsUserList() {
    // Given: Token with ROLE_ADMIN (COBOL USER-TYPE='A')
    String adminToken = jwtTokenProvider.generateToken("adminuser", List.of("ROLE_ADMIN"));
    
    // When: Access admin endpoint
    given()
        .header("Authorization", "Bearer " + adminToken)
    .when()
        .get("/admin/users")
    .then()
        .statusCode(200)
        .body("$", hasSize(greaterThan(0)));
}
```

### 4.4 Error Handling and Exception Testing

Validate error responses match COBOL error code semantics:

```java
@Test
@DisplayName("PUT /cards/{id} - validation failure - returns 400 with error details")
void testUpdateCard_InvalidData_ReturnsBadRequest() {
    // Given: Invalid card update request (credit limit exceeds maximum)
    String token = jwtTokenProvider.generateToken("testuser", List.of("ROLE_USER"));
    CardUpdateRequest request = new CardUpdateRequest();
    request.setCreditLimit(new BigDecimal("1000000.00")); // Exceeds max
    
    // When: PUT to update card (COBOL COCRDUPC validation error)
    given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(request)
    .when()
        .put("/cards/4000123400001234")
    .then()
        .statusCode(400)
        .body("errorCode", equalTo("VALIDATION_ERROR"))
        .body("message", containsString("Credit limit exceeds maximum"))
        .body("fieldErrors.creditLimit", notNullValue());
}
```

### 4.5 Performance Testing with Response Time Assertions

Ensure REST endpoints meet <200ms response time target at 95th percentile:

```java
@Test
@DisplayName("GET /accounts/{id} - response time under 200ms")
void testGetAccount_ResponseTime_Under200ms() {
    // Given: Valid account ID
    String token = jwtTokenProvider.generateToken("testuser", List.of("ROLE_USER"));
    Long accountId = 1000000001L;
    
    // When: GET account endpoint called multiple times
    long[] responseTimes = new long[100];
    for (int i = 0; i < 100; i++) {
        long startTime = System.currentTimeMillis();
        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/accounts/" + accountId)
        .then()
            .statusCode(200);
        responseTimes[i] = System.currentTimeMillis() - startTime;
    }
    
    // Then: Calculate 95th percentile and verify under 200ms
    Arrays.sort(responseTimes);
    long percentile95 = responseTimes[94]; // 95th element of 100
    assertThat(percentile95).isLessThan(200);
}
```

---

## 5. Batch Processing Testing

### 5.1 Spring Batch JobLauncherTestUtils

Test batch jobs using Spring Batch Test utilities to validate chunk processing, error handling, and job completion.

**Test Configuration:**
```java
@SpringBootTest
@SpringBatchTest
@Testcontainers
class AccountDataLoadJobTest {

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    private AccountRepository accountRepository;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.5-alpine");

    @BeforeEach
    void setUp() {
        jobRepositoryTestUtils.removeJobExecutions();
    }

    @Test
    @DisplayName("Account data load job - successful execution - loads all records")
    void testAccountDataLoadJob_Success_LoadsAllRecords() throws Exception {
        // Given: Test input file matching COBOL CBACT01C input (app/data/ASCII/acctdata.txt)
        JobParameters jobParameters = new JobParametersBuilder()
            .addString("inputFile", "classpath:test-data/acctdata-test.txt")
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters();
        
        // When: Job executed (equivalent to JCL CBACT01C.jcl execution)
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);
        
        // Then: Verify job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jobExecution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        
        // Verify all records loaded (matching COBOL expected count)
        long accountCount = accountRepository.count();
        assertThat(accountCount).isEqualTo(50); // 50 test records in input file
    }

    @Test
    @DisplayName("Account data load job - validation error - skips invalid records")
    void testAccountDataLoadJob_ValidationError_SkipsInvalidRecords() throws Exception {
        // Given: Input file with invalid records (COBOL skip logic test)
        JobParameters jobParameters = new JobParametersBuilder()
            .addString("inputFile", "classpath:test-data/acctdata-invalid.txt")
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters();
        
        // When: Job executed with skip policy
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);
        
        // Then: Job completes with skipped records logged
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        StepExecution stepExecution = jobExecution.getStepExecutions().iterator().next();
        assertThat(stepExecution.getSkipCount()).isEqualTo(5); // 5 invalid records skipped
        assertThat(stepExecution.getWriteCount()).isEqualTo(45); // 45 valid records written
    }
}
```

### 5.2 Chunk-Oriented Processing Validation

Test ItemReader, ItemProcessor, and ItemWriter components independently:

```java
@SpringBootTest
class InterestCalculationProcessorTest {

    @Autowired
    private InterestCalculationProcessor interestCalculationProcessor;

    @Test
    @DisplayName("Interest calculation processor - daily interest - matches COBOL CBACT04C")
    void testProcess_DailyInterest_MatchesCobolFormula() throws Exception {
        // Given: Account with balance requiring interest calculation
        Account account = new Account();
        account.setAccountId(1000000001L);
        account.setCurrentBalance(new BigDecimal("10000.00"));
        account.setInterestRate(new BigDecimal("0.15")); // 15% APR
        account.setLastInterestDate(LocalDate.now().minusDays(30));
        
        // When: Processor calculates interest (COBOL CBACT04C PERFORM CALC-INTEREST)
        Account processedAccount = interestCalculationProcessor.process(account);
        
        // Then: Interest amount matches COBOL calculation
        // COBOL: COMPUTE INTEREST = BALANCE * ANNUAL-RATE / 365 * DAYS
        BigDecimal expectedInterest = new BigDecimal("123.29"); // Pre-calculated from COBOL test
        BigDecimal actualInterest = processedAccount.getCurrentBalance()
            .subtract(account.getCurrentBalance());
        assertThat(actualInterest).isEqualByComparingTo(expectedInterest);
    }
}
```

### 5.3 Job Execution Context and Restart Testing

Validate checkpoint/restart capabilities equivalent to COBOL batch restart logic:

```java
@Test
@DisplayName("Transaction processing job - restart from failure point - resumes correctly")
void testTransactionProcessingJob_RestartAfterFailure_ResumesFromCheckpoint() throws Exception {
    // Given: Job parameters with simulated failure after 500 records
    JobParameters jobParameters = new JobParametersBuilder()
        .addString("inputFile", "classpath:test-data/transactions-large.txt")
        .addLong("timestamp", System.currentTimeMillis())
        .addBoolean("simulateFailure", true)
        .addLong("failAfterCount", 500L)
        .toJobParameters();
    
    // When: Initial job execution fails mid-processing (COBOL abend simulation)
    JobExecution firstExecution = jobLauncherTestUtils.launchJob(jobParameters);
    assertThat(firstExecution.getStatus()).isEqualTo(BatchStatus.FAILED);
    
    // When: Job restarted with same parameters (COBOL JCL RESTART)
    JobParameters restartParameters = new JobParametersBuilder()
        .addString("inputFile", "classpath:test-data/transactions-large.txt")
        .addLong("timestamp", System.currentTimeMillis())
        .addBoolean("simulateFailure", false)
        .toJobParameters();
    JobExecution restartExecution = jobLauncherTestUtils.launchJob(restartParameters);
    
    // Then: Job completes successfully, processing only remaining records
    assertThat(restartExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    long totalProcessed = restartExecution.getStepExecutions().stream()
        .mapToLong(StepExecution::getWriteCount)
        .sum();
    assertThat(totalProcessed).isEqualTo(1000); // Total records in file
}
```

### 5.4 Output File Verification

Validate batch job outputs match COBOL batch job outputs byte-for-byte:

```java
@Test
@DisplayName("Statement generation job - output file matches COBOL format")
void testStatementGenerationJob_OutputFormat_MatchesCobol() throws Exception {
    // Given: Statement generation job parameters (COBOL CBSTM03A)
    JobParameters jobParameters = new JobParametersBuilder()
        .addString("outputFile", "target/test-output/statements.txt")
        .addString("statementDate", "2024-01-31")
        .addLong("timestamp", System.currentTimeMillis())
        .toJobParameters();
    
    // When: Job generates statement file
    JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);
    assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    
    // Then: Output file matches expected COBOL format
    Path outputPath = Paths.get("target/test-output/statements.txt");
    assertThat(outputPath).exists();
    
    List<String> lines = Files.readAllLines(outputPath);
    assertThat(lines).isNotEmpty();
    
    // Verify record layout matches COBOL copybook format
    String firstRecord = lines.get(0);
    assertThat(firstRecord.substring(0, 10)).matches("\\d{10}"); // Account ID
    assertThat(firstRecord.substring(10, 18)).matches("\\d{8}"); // Statement date YYYYMMDD
    assertThat(firstRecord.substring(18, 31)).matches("-?\\d{11}\\.\\d{2}"); // Balance with COMP-3 format
}
```

---

## 6. Frontend Testing (React)

### 6.1 React Testing Library

All React component tests use React Testing Library for user-centric testing approach.

**Test Setup:**
```javascript
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { Provider } from 'react-redux';
import { BrowserRouter } from 'react-router-dom';
import { configureStore } from '@reduxjs/toolkit';
import LoginComponent from '../components/auth/LoginComponent';
import authReducer from '../redux/slices/authSlice';

describe('LoginComponent', () => {
  let store;

  beforeEach(() => {
    store = configureStore({
      reducer: {
        auth: authReducer
      }
    });
  });

  test('renders login form with username and password fields', () => {
    // Given: LoginComponent rendered (equivalent to BMS COSGN00M display)
    render(
      <Provider store={store}>
        <BrowserRouter>
          <LoginComponent />
        </BrowserRouter>
      </Provider>
    );

    // Then: Form fields visible matching COBOL screen layout
    expect(screen.getByLabelText(/username/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /login/i })).toBeInTheDocument();
  });

  test('submits login form with valid credentials', async () => {
    // Given: Mock API response for successful login
    const mockLoginResponse = {
      token: 'mock-jwt-token',
      userName: 'Test User',
      userType: 'R',
      responseCode: '00'
    };
    
    global.fetch = jest.fn(() =>
      Promise.resolve({
        ok: true,
        json: () => Promise.resolve(mockLoginResponse)
      })
    );

    render(
      <Provider store={store}>
        <BrowserRouter>
          <LoginComponent />
        </BrowserRouter>
      </Provider>
    );

    // When: User enters credentials and submits (COBOL COSGN00C transaction)
    fireEvent.change(screen.getByLabelText(/username/i), {
      target: { value: 'testuser' }
    });
    fireEvent.change(screen.getByLabelText(/password/i), {
      target: { value: 'password123' }
    });
    fireEvent.click(screen.getByRole('button', { name: /login/i }));

    // Then: Success message displayed and token stored
    await waitFor(() => {
      expect(screen.getByText(/login successful/i)).toBeInTheDocument();
    });
  });

  test('displays error message on invalid credentials', async () => {
    // Given: Mock API response for failed login (COBOL error response)
    global.fetch = jest.fn(() =>
      Promise.resolve({
        ok: false,
        status: 401,
        json: () => Promise.resolve({
          responseCode: '01',
          message: 'Invalid credentials'
        })
      })
    );

    render(
      <Provider store={store}>
        <BrowserRouter>
          <LoginComponent />
        </BrowserRouter>
      </Provider>
    );

    // When: User submits invalid credentials
    fireEvent.change(screen.getByLabelText(/username/i), {
      target: { value: 'invaliduser' }
    });
    fireEvent.change(screen.getByLabelText(/password/i), {
      target: { value: 'wrongpass' }
    });
    fireEvent.click(screen.getByRole('button', { name: /login/i }));

    // Then: Error message displayed (matching COBOL error screen)
    await waitFor(() => {
      expect(screen.getByText(/invalid credentials/i)).toBeInTheDocument();
    });
  });
});
```

### 6.2 Redux Slice Testing

Test Redux state management logic with pure function testing:

```javascript
import authReducer, { loginSuccess, loginFailure, logout } from '../redux/slices/authSlice';

describe('authSlice', () => {
  const initialState = {
    user: null,
    token: null,
    isAuthenticated: false,
    error: null
  };

  test('loginSuccess - sets user and token', () => {
    // Given: Login success action payload
    const payload = {
      token: 'jwt-token-123',
      userName: 'Test User',
      userType: 'R'
    };

    // When: loginSuccess action dispatched
    const state = authReducer(initialState, loginSuccess(payload));

    // Then: State updated with user info (matching COBOL USRSEC record)
    expect(state.isAuthenticated).toBe(true);
    expect(state.token).toBe('jwt-token-123');
    expect(state.user.userName).toBe('Test User');
    expect(state.user.userType).toBe('R');
    expect(state.error).toBeNull();
  });

  test('logout - clears user state', () => {
    // Given: Authenticated state
    const authenticatedState = {
      user: { userName: 'Test User', userType: 'R' },
      token: 'jwt-token-123',
      isAuthenticated: true,
      error: null
    };

    // When: logout action dispatched
    const state = authReducer(authenticatedState, logout());

    // Then: State reset to initial (COBOL COSGN00C sign-off)
    expect(state.isAuthenticated).toBe(false);
    expect(state.token).toBeNull();
    expect(state.user).toBeNull();
  });
});
```

### 6.3 Service Layer Mocking with Mock Service Worker (MSW)

Use MSW to mock API responses for integration testing:

```javascript
import { rest } from 'msw';
import { setupServer } from 'msw/node';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { Provider } from 'react-redux';
import { store } from '../redux/store';
import AccountViewComponent from '../components/account/AccountViewComponent';

// Setup MSW server with mock handlers
const server = setupServer(
  rest.get('/api/accounts/:id', (req, res, ctx) => {
    return res(
      ctx.json({
        accountId: 1000000001,
        customerId: 19750101,
        customerName: 'John Doe',
        accountStatus: 'A',
        currentBalance: '5000.00',
        creditLimit: '10000.00',
        availableCredit: '5000.00'
      })
    );
  })
);

beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

test('AccountViewComponent - displays account details from API', async () => {
  // Given: Component rendered with account ID parameter
  render(
    <Provider store={store}>
      <AccountViewComponent accountId={1000000001} />
    </Provider>
  );

  // Then: Account details displayed matching COBOL COACTVWC output
  await waitFor(() => {
    expect(screen.getByText(/Account ID: 1000000001/i)).toBeInTheDocument();
    expect(screen.getByText(/John Doe/i)).toBeInTheDocument();
    expect(screen.getByText(/\$5,000\.00/i)).toBeInTheDocument();
  });
});
```

### 6.4 Accessibility Testing

Ensure components meet accessibility standards using jest-axe:

```javascript
import { render } from '@testing-library/react';
import { axe, toHaveNoViolations } from 'jest-axe';
import CardListComponent from '../components/card/CardListComponent';

expect.extend(toHaveNoViolations);

test('CardListComponent - no accessibility violations', async () => {
  // Given: Component rendered with mock data
  const { container } = render(<CardListComponent cards={mockCards} />);

  // When: Accessibility audit performed
  const results = await axe(container);

  // Then: No violations detected
  expect(results).toHaveNoViolations();
});
```

---

## 7. Test Data Management

### 7.1 Test Data Fixtures

Create reusable test data fixtures matching COBOL test data from `app/data/ASCII/`:

**Java Test Data Builder:**
```java
public class TestDataBuilder {

    public static Customer createTestCustomer(Long customerId) {
        Customer customer = new Customer();
        customer.setCustomerId(customerId);
        customer.setFirstName("John");
        customer.setLastName("Doe");
        customer.setDateOfBirth(LocalDate.of(1975, 1, 1));
        customer.setCustomerStatus("A");
        customer.setFicoScore(750);
        return customer;
    }

    public static Account createTestAccount(Long accountId, Customer customer) {
        Account account = new Account();
        account.setAccountId(accountId);
        account.setCustomer(customer);
        account.setAccountType("CC");
        account.setAccountStatus("A");
        account.setCurrentBalance(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP));
        account.setCreditLimit(new BigDecimal("10000.00").setScale(2, RoundingMode.HALF_UP));
        account.setOpenDate(LocalDate.now().minusYears(2));
        return account;
    }

    public static Card createTestCard(String cardNumber, Account account) {
        Card card = new Card();
        card.setCardNumber(cardNumber);
        card.setAccount(account);
        card.setCardStatus("A");
        card.setExpirationDate(LocalDate.now().plusYears(3));
        card.setCardType("VISA");
        return card;
    }

    public static Transaction createTestTransaction(Account account, BigDecimal amount) {
        Transaction transaction = new Transaction();
        transaction.setAccount(account);
        transaction.setTransactionAmount(amount.setScale(2, RoundingMode.HALF_UP));
        transaction.setTransactionType("PURCHASE");
        transaction.setTransactionDate(LocalDate.now());
        transaction.setTransactionTime(LocalTime.now());
        transaction.setMerchantName("Test Merchant");
        transaction.setTransactionStatus("P"); // Posted
        return transaction;
    }
}
```

### 7.2 Database Seeding Scripts

SQL scripts for seeding test databases with data matching COBOL test files:

**test-data/init-schema.sql:**
```sql
-- Seed customer data matching app/data/ASCII/custdata.txt
INSERT INTO customer (customer_id, first_name, last_name, date_of_birth, customer_status, fico_score)
VALUES 
  (19750101, 'John', 'Doe', '1975-01-01', 'A', 750),
  (19800215, 'Jane', 'Smith', '1980-02-15', 'A', 820),
  (19650520, 'Bob', 'Johnson', '1965-05-20', 'A', 680);

-- Seed account data matching app/data/ASCII/acctdata.txt
INSERT INTO account (account_id, customer_id, account_type, account_status, current_balance, credit_limit, open_date, interest_rate)
VALUES 
  (1000000001, 19750101, 'CC', 'A', 5000.00, 10000.00, '2022-01-15', 0.15),
  (1000000002, 19800215, 'CC', 'A', 12000.50, 15000.00, '2021-06-20', 0.18),
  (1000000003, 19650520, 'CC', 'A', 500.00, 5000.00, '2023-03-10', 0.12);

-- Seed card data matching app/data/ASCII/carddata.txt
INSERT INTO card (card_number, account_id, card_status, expiration_date, card_type, cvv)
VALUES 
  ('4000123400001234', 1000000001, 'A', '2027-01-31', 'VISA', '123'),
  ('4000567800005678', 1000000002, 'A', '2026-12-31', 'VISA', '456'),
  ('4000901200009012', 1000000003, 'A', '2028-06-30', 'VISA', '789');

-- Seed transaction data matching app/data/ASCII/transact.txt
INSERT INTO transaction (transaction_id, account_id, card_number, transaction_type, transaction_amount, transaction_date, transaction_time, merchant_name, transaction_status)
VALUES 
  ('TXN000001', 1000000001, '4000123400001234', 'PURCHASE', 150.00, '2024-01-15', '14:30:00', 'Amazon', 'P'),
  ('TXN000002', 1000000001, '4000123400001234', 'PURCHASE', 75.50, '2024-01-16', '09:15:00', 'Walmart', 'P'),
  ('TXN000003', 1000000002, '4000567800005678', 'PURCHASE', 500.00, '2024-01-17', '18:45:00', 'Best Buy', 'P');

-- Seed user security data matching app/data/ASCII/usrsec.txt
INSERT INTO user_security (user_id, username, password_hash, user_type, first_name, last_name, last_login)
VALUES 
  (1, 'testuser', '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYFq7l4fUdq', 'R', 'Test', 'User', '2024-01-01 10:00:00'),
  (2, 'adminuser', '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYFq7l4fUdq', 'A', 'Admin', 'User', '2024-01-01 09:00:00');
```

### 7.3 Data Builder Patterns

Use builder pattern for complex test object construction:

```java
public class AccountBuilder {
    private Long accountId;
    private Customer customer;
    private String accountType = "CC";
    private String accountStatus = "A";
    private BigDecimal currentBalance = new BigDecimal("0.00");
    private BigDecimal creditLimit = new BigDecimal("10000.00");
    private LocalDate openDate = LocalDate.now();
    private BigDecimal interestRate = new BigDecimal("0.15");

    public AccountBuilder withAccountId(Long accountId) {
        this.accountId = accountId;
        return this;
    }

    public AccountBuilder withCustomer(Customer customer) {
        this.customer = customer;
        return this;
    }

    public AccountBuilder withBalance(BigDecimal balance) {
        this.currentBalance = balance.setScale(2, RoundingMode.HALF_UP);
        return this;
    }

    public AccountBuilder withCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit.setScale(2, RoundingMode.HALF_UP);
        return this;
    }

    public Account build() {
        Account account = new Account();
        account.setAccountId(accountId);
        account.setCustomer(customer);
        account.setAccountType(accountType);
        account.setAccountStatus(accountStatus);
        account.setCurrentBalance(currentBalance);
        account.setCreditLimit(creditLimit);
        account.setOpenDate(openDate);
        account.setInterestRate(interestRate);
        return account;
    }
}

// Usage in tests:
Account testAccount = new AccountBuilder()
    .withAccountId(1000000001L)
    .withCustomer(testCustomer)
    .withBalance(new BigDecimal("5000.00"))
    .withCreditLimit(new BigDecimal("10000.00"))
    .build();
```

### 7.4 Anonymized Production Data

For performance and stress testing, use anonymized production data:

```java
@Component
public class DataAnonymizer {

    public Customer anonymizeCustomer(Customer customer) {
        Customer anonymized = new Customer();
        anonymized.setCustomerId(customer.getCustomerId());
        anonymized.setFirstName("FirstName" + customer.getCustomerId());
        anonymized.setLastName("LastName" + customer.getCustomerId());
        anonymized.setDateOfBirth(customer.getDateOfBirth().minusYears(5));
        anonymized.setCustomerStatus(customer.getCustomerStatus());
        anonymized.setFicoScore(customer.getFicoScore());
        // Do NOT copy: SSN, email, phone, address
        return anonymized;
    }

    public void anonymizeDatabase(DataSource dataSource) throws SQLException {
        // Bulk anonymization script for test environments
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE customer SET " +
                "first_name = 'FirstName' || customer_id, " +
                "last_name = 'LastName' || customer_id, " +
                "ssn = NULL, " +
                "email = customer_id || '@example.com', " +
                "phone = '555-' || LPAD(customer_id::text, 7, '0')");
        }
    }
}
```

---

## 8. Functional Equivalence Testing

### 8.1 Side-by-Side Comparison Framework

Framework for comparing COBOL and Java execution results:

```java
@SpringBootTest
public class FunctionalEquivalenceTest {

    @Autowired
    private InterestCalculationService javaService;

    @Test
    @DisplayName("Interest calculation - Java output matches COBOL output exactly")
    void testInterestCalculation_JavaMatchesCobol() {
        // Given: Input data from COBOL test case CBACT04C-TC01
        BigDecimal principal = new BigDecimal("10000.00");
        BigDecimal annualRate = new BigDecimal("0.15");
        int days = 30;

        // Expected output from COBOL execution (pre-calculated)
        BigDecimal cobolExpectedInterest = new BigDecimal("123.29");

        // When: Java service calculates interest
        BigDecimal javaCalculatedInterest = javaService.calculateInterest(principal, annualRate, days);

        // Then: Java result matches COBOL result exactly
        assertThat(javaCalculatedInterest)
            .isEqualByComparingTo(cobolExpectedInterest)
            .describedAs("Java interest calculation must match COBOL CBACT04C output");
    }

    @Test
    @DisplayName("Account balance update - maintains COMP-3 precision")
    void testBalanceUpdate_MaintainsPrecision() {
        // Given: COBOL COMP-3 PIC S9(13)V99 balance operations
        BigDecimal startBalance = new BigDecimal("5000.00");
        BigDecimal purchase1 = new BigDecimal("150.75");
        BigDecimal purchase2 = new BigDecimal("89.99");
        BigDecimal payment = new BigDecimal("500.00");

        // When: Java performs same operations
        BigDecimal javaBalance = startBalance
            .subtract(purchase1)
            .subtract(purchase2)
            .add(payment)
            .setScale(2, RoundingMode.HALF_UP);

        // Then: Result matches COBOL calculation exactly
        BigDecimal cobolExpectedBalance = new BigDecimal("5259.26");
        assertThat(javaBalance).isEqualByComparingTo(cobolExpectedBalance);
    }
}
```

### 8.2 Decimal Precision Validation

Comprehensive testing of BigDecimal precision matching COBOL COMP-3:

```java
@ParameterizedTest
@CsvSource({
    "123456.78, 13, 2, 123456.78",
    "0.15, 3, 5, 0.15000",
    "-9999999999999.99, 13, 2, -9999999999999.99",
    "0.00, 13, 2, 0.00"
})
@DisplayName("COMP-3 decimal precision - various PIC clauses")
void testComp3Precision_VariousPicClauses_MaintainsPrecision(
        String inputValue, int precision, int scale, String expectedOutput) {
    // Given: Input value and COBOL PIC clause specification
    BigDecimal input = new BigDecimal(inputValue);

    // When: Value processed with COMP-3 equivalent precision
    BigDecimal result = input.setScale(scale, RoundingMode.HALF_UP);

    // Then: Precision and scale match COBOL specification
    assertThat(result.scale()).isEqualTo(scale);
    assertThat(result.toPlainString()).isEqualTo(expectedOutput);
}
```

### 8.3 Date Format Conversion Testing

Validate date conversions from COBOL CEEDAYS Lillian format to Java LocalDate:

```java
@Test
@DisplayName("Date conversion - CEEDAYS to LocalDate - maintains correct date")
void testDateConversion_CeedaysToLocalDate() {
    // Given: COBOL CEEDAYS (Lillian date) value
    // Lillian date: Days since Oct 15, 1582
    // Example: 152345 = some specific date
    int lillianDays = 152345;

    // When: Convert to Java LocalDate using equivalent formula
    LocalDate javaDate = DateUtils.lillianToLocalDate(lillianDays);

    // Then: Date matches COBOL date calculation
    // Verify with known COBOL test case date
    assertThat(javaDate).isEqualTo(LocalDate.of(1999, 12, 31));
}

@Test
@DisplayName("Date arithmetic - adding days - matches COBOL ADD operation")
void testDateArithmetic_AddDays_MatchesCobol() {
    // Given: COBOL date and days to add
    LocalDate startDate = LocalDate.of(2024, 1, 15);
    int daysToAdd = 45;

    // When: Java adds days
    LocalDate resultDate = startDate.plusDays(daysToAdd);

    // Then: Result matches COBOL date arithmetic
    assertThat(resultDate).isEqualTo(LocalDate.of(2024, 2, 29)); // Leap year handling
}
```

### 8.4 Transaction Boundary Verification

Verify Spring @Transactional behavior matches CICS SYNCPOINT semantics:

```java
@Test
@DisplayName("Transaction boundary - atomic update - matches CICS SYNCPOINT")
@Transactional
void testTransactionBoundary_AtomicUpdate_MatchesCicsSyncpoint() {
    // Given: Multi-table update scenario (COBOL with SYNCPOINT)
    Account account = accountRepository.findById(1000000001L).orElseThrow();
    BigDecimal paymentAmount = new BigDecimal("150.00");

    // When: Service method with @Transactional (CICS SYNCPOINT equivalent)
    transactionService.postPayment(account.getAccountId(), paymentAmount);

    // Then: Verify both account and transaction updated atomically
    Account updatedAccount = accountRepository.findById(1000000001L).orElseThrow();
    List<Transaction> transactions = transactionRepository.findByAccount_AccountId(account.getAccountId());

    assertThat(updatedAccount.getCurrentBalance())
        .isEqualByComparingTo(account.getCurrentBalance().subtract(paymentAmount));
    assertThat(transactions).anyMatch(t -> 
        t.getTransactionAmount().compareTo(paymentAmount) == 0 &&
        "PAYMENT".equals(t.getTransactionType())
    );
}
```

### 8.5 Error Message and Response Code Mapping

Validate Java error codes match COBOL error codes:

```java
@Test
@DisplayName("Error code mapping - COBOL file-status to Java exception")
void testErrorCodeMapping_FileStatusToException() {
    // Given: Scenario causing COBOL file-status 23 (record not found)
    Long nonExistentAccountId = 9999999999L;

    // When: Java service attempts operation
    assertThatThrownBy(() -> accountService.viewAccount(nonExistentAccountId))
        .isInstanceOf(AccountNotFoundException.class)
        .hasMessageContaining("Account not found")
        .extracting("errorCode")
        .isEqualTo("ACCT_NOT_FOUND"); // Maps to COBOL file-status 23

    // Then: Error code accessible for client applications
    try {
        accountService.viewAccount(nonExistentAccountId);
    } catch (AccountNotFoundException e) {
        assertThat(e.getErrorCode()).isEqualTo("ACCT_NOT_FOUND");
        assertThat(e.getCobolFileStatus()).isEqualTo("23");
    }
}
```

---

## 9. Performance Testing

### 9.1 JMeter Load Testing

JMeter test plans for validating 10,000 TPS requirement:

**Test Plan Structure:**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2" properties="5.0">
  <hashTree>
    <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="CardDemo Performance Test">
      <stringProp name="TestPlan.comments">10,000 TPS Load Test</stringProp>
      <boolProp name="TestPlan.functional_mode">false</boolProp>
    </TestPlan>
    <hashTree>
      <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="Account Lookup Thread Group">
        <stringProp name="ThreadGroup.num_threads">500</stringProp>
        <stringProp name="ThreadGroup.ramp_time">60</stringProp>
        <stringProp name="ThreadGroup.duration">300</stringProp>
        <boolProp name="ThreadGroup.scheduler">true</boolProp>
      </ThreadGroup>
      <hashTree>
        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="GET Account">
          <stringProp name="HTTPSampler.domain">${__P(host,localhost)}</stringProp>
          <stringProp name="HTTPSampler.port">${__P(port,8080)}</stringProp>
          <stringProp name="HTTPSampler.path">/api/accounts/${accountId}</stringProp>
          <stringProp name="HTTPSampler.method">GET</stringProp>
        </HTTPSamplerProxy>
      </hashTree>
    </hashTree>
  </hashTree>
</jmeterTestPlan>
```

**Execution Command:**
```bash
jmeter -n -t performance-test-plan.jmx \
  -Jhost=api.carddemo.com \
  -Jport=443 \
  -Jthreads=500 \
  -Jduration=300 \
  -l results.jtl \
  -e -o performance-report/
```

### 9.2 Response Time Monitoring

Automated response time validation in tests:

```java
@Test
@DisplayName("Response time - 95th percentile under 200ms at 10,000 TPS")
void testResponseTime_95thPercentile_Under200ms() throws Exception {
    // Given: 10,000 concurrent requests
    int totalRequests = 10000;
    ExecutorService executor = Executors.newFixedThreadPool(500);
    List<Future<Long>> futures = new ArrayList<>();

    // When: Execute requests concurrently
    for (int i = 0; i < totalRequests; i++) {
        Future<Long> future = executor.submit(() -> {
            long startTime = System.currentTimeMillis();
            mockMvc.perform(get("/api/accounts/1000000001")
                .header("Authorization", "Bearer " + testToken))
                .andExpect(status().isOk());
            return System.currentTimeMillis() - startTime;
        });
        futures.add(future);
    }

    // Collect response times
    List<Long> responseTimes = new ArrayList<>();
    for (Future<Long> future : futures) {
        responseTimes.add(future.get());
    }
    executor.shutdown();

    // Then: Calculate 95th percentile
    Collections.sort(responseTimes);
    long percentile95 = responseTimes.get((int) (totalRequests * 0.95));
    assertThat(percentile95).isLessThan(200);

    // Log statistics
    long avg = responseTimes.stream().mapToLong(Long::longValue).sum() / totalRequests;
    long max = Collections.max(responseTimes);
    System.out.printf("Response Time Stats - Avg: %dms, 95th: %dms, Max: %dms%n", 
        avg, percentile95, max);
}
```

### 9.3 Database Query Performance Profiling

Profile database queries to ensure performance parity with VSAM access:

```java
@Test
@DisplayName("Database query performance - indexed lookup comparable to VSAM")
void testDatabaseQueryPerformance_IndexedLookup_ComparableToVsam() {
    // Given: 1000 account lookups by primary key (VSAM key access equivalent)
    int iterations = 1000;
    List<Long> queryTimes = new ArrayList<>();

    // When: Execute indexed lookups
    for (int i = 0; i < iterations; i++) {
        long startTime = System.nanoTime();
        accountRepository.findById(1000000001L);
        long endTime = System.nanoTime();
        queryTimes.add((endTime - startTime) / 1_000_000); // Convert to ms
    }

    // Then: Average query time under 10ms (VSAM key access typical time)
    double avgQueryTime = queryTimes.stream()
        .mapToLong(Long::longValue)
        .average()
        .orElse(0.0);
    assertThat(avgQueryTime).isLessThan(10.0);
}

@Test
@DisplayName("Batch query performance - pagination efficient")
void testBatchQueryPerformance_Pagination_Efficient() {
    // Given: Large result set with pagination (COBOL browse pattern)
    int pageSize = 100;
    int totalPages = 10;

    // When: Fetch multiple pages
    long startTime = System.currentTimeMillis();
    for (int page = 0; page < totalPages; page++) {
        PageRequest pageRequest = PageRequest.of(page, pageSize);
        Page<Account> accountPage = accountRepository.findAll(pageRequest);
        assertThat(accountPage.getContent()).hasSize(pageSize);
    }
    long totalTime = System.currentTimeMillis() - startTime;

    // Then: Total time for 1000 records under 2 seconds
    assertThat(totalTime).isLessThan(2000);
}
```

### 9.4 Batch Processing Window Validation

Ensure batch jobs complete within 4-hour window:

```java
@Test
@DisplayName("Batch processing - completes within 4-hour window")
void testBatchProcessing_CompletesWith4Hours() throws Exception {
    // Given: Full daily batch processing suite (matching COBOL batch schedule)
    long startTime = System.currentTimeMillis();

    // When: Execute all daily batch jobs
    jobLauncherTestUtils.launchJob(createJobParameters("AccountDataLoadJob"));
    jobLauncherTestUtils.launchJob(createJobParameters("TransactionDataLoadJob"));
    jobLauncherTestUtils.launchJob(createJobParameters("DailyTransactionProcessingJob"));
    jobLauncherTestUtils.launchJob(createJobParameters("InterestCalculationJob"));
    jobLauncherTestUtils.launchJob(createJobParameters("StatementGenerationJob"));

    long totalTime = System.currentTimeMillis() - startTime;

    // Then: Total processing time under 4 hours (14,400,000 ms)
    assertThat(totalTime).isLessThan(14_400_000);
}
```

---

## 10. Security Testing

### 10.1 Authentication Flow Testing

Test JWT token generation and validation:

```java
@SpringBootTest
class JwtAuthenticationTest {

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("JWT token generation - valid user - creates token")
    void testJwtTokenGeneration_ValidUser_CreatesToken() {
        // Given: User authentication details
        String username = "testuser";
        List<String> roles = List.of("ROLE_USER");

        // When: Generate JWT token (COBOL USRSEC authentication equivalent)
        String token = jwtTokenProvider.generateToken(username, roles);

        // Then: Token is valid and contains correct claims
        assertThat(token).isNotNull();
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        assertThat(jwtTokenProvider.getUsernameFromToken(token)).isEqualTo(username);
        assertThat(jwtTokenProvider.getRolesFromToken(token)).contains("ROLE_USER");
    }

    @Test
    @DisplayName("JWT token validation - expired token - returns false")
    void testJwtTokenValidation_ExpiredToken_ReturnsFalse() {
        // Given: Token with short expiration (1 second)
        String username = "testuser";
        String token = jwtTokenProvider.generateToken(username, List.of("ROLE_USER"), 1);

        // When: Wait for token to expire
        Thread.sleep(2000);

        // Then: Token validation fails
        assertThat(jwtTokenProvider.validateToken(token)).isFalse();
    }
}
```

### 10.2 Authorization Testing with @PreAuthorize

Validate role-based access control:

```java
@SpringBootTest
@WithMockUser(username = "testuser", roles = {"USER"})
class AuthorizationTest {

    @Autowired
    private AdminService adminService;

    @Test
    @DisplayName("Admin operation - regular user - throws AccessDeniedException")
    void testAdminOperation_RegularUser_ThrowsAccessDeniedException() {
        // Given: User with ROLE_USER only (COBOL USER-TYPE='R')

        // When/Then: Admin operation denied
        assertThatThrownBy(() -> adminService.deleteUser(1L))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("Account access - user can access own account")
    @WithMockUser(username = "testuser", roles = {"USER"})
    void testAccountAccess_UserOwnAccount_AllowsAccess() {
        // Given: User accessing their own account

        // When: View own account (COBOL authorization check equivalent)
        AccountViewResponse response = accountService.viewAccountForUser(1000000001L, "testuser");

        // Then: Access granted
        assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("Account access - user cannot access other user account")
    @WithMockUser(username = "testuser", roles = {"USER"})
    void testAccountAccess_OtherUserAccount_DeniesAccess() {
        // Given: User attempting to access another user's account

        // When/Then: Access denied
        assertThatThrownBy(() -> accountService.viewAccountForUser(1000000002L, "testuser"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("User not authorized to access this account");
    }
}
```

### 10.3 Input Validation and SQL Injection Prevention

Test input validation and security against SQL injection:

```java
@Test
@DisplayName("Input validation - malicious input - sanitized")
void testInputValidation_MaliciousInput_Sanitized() {
    // Given: SQL injection attempt in search parameter
    String maliciousInput = "'; DROP TABLE account; --";

    // When: Search with malicious input
    assertThatThrownBy(() -> accountService.searchAccounts(maliciousInput))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Invalid search criteria");

    // Then: Verify table still exists
    long accountCount = accountRepository.count();
    assertThat(accountCount).isGreaterThan(0);
}

@Test
@DisplayName("Bean validation - invalid request - returns validation errors")
void testBeanValidation_InvalidRequest_ReturnsValidationErrors() {
    // Given: Invalid account creation request
    AccountAddRequest request = new AccountAddRequest();
    request.setCreditLimit(new BigDecimal("-1000.00")); // Negative credit limit

    // When: Validate request
    Set<ConstraintViolation<AccountAddRequest>> violations = validator.validate(request);

    // Then: Validation errors detected
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> 
        v.getMessage().contains("Credit limit must be positive")
    );
}
```

### 10.4 CSRF Protection Validation

Test CSRF token validation for state-changing operations:

```java
@Test
@DisplayName("CSRF protection - POST without token - returns 403")
void testCsrfProtection_PostWithoutToken_Returns403() throws Exception {
    // Given: POST request without CSRF token

    // When: Attempt state-changing operation
    mockMvc.perform(post("/api/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountType\":\"CC\"}"))
        // Then: Request rejected with 403 Forbidden
        .andExpect(status().isForbidden());
}
```

### 10.5 Password Encryption Verification

Validate BCrypt password encryption:

```java
@Test
@DisplayName("Password encryption - BCrypt - stores hash not plaintext")
void testPasswordEncryption_BCrypt_StoresHashNotPlaintext() {
    // Given: User registration with plaintext password
    String plainPassword = "SecureP@ssw0rd!";
    UserRegistrationRequest request = new UserRegistrationRequest();
    request.setUsername("newuser");
    request.setPassword(plainPassword);

    // When: User created (COBOL USRSEC record equivalent)
    UserSecurity user = userService.createUser(request);

    // Then: Password stored as BCrypt hash, not plaintext
    assertThat(user.getPasswordHash()).isNotEqualTo(plainPassword);
    assertThat(user.getPasswordHash()).startsWith("$2a$"); // BCrypt prefix
    assertThat(passwordEncoder.matches(plainPassword, user.getPasswordHash())).isTrue();
}
```

---

## 11. CI/CD Integration

### 11.1 GitHub Actions Workflow

Automated test execution on every commit:

**.github/workflows/backend-ci.yml:**
```yaml
name: Backend CI

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main, develop ]

jobs:
  test:
    runs-on: ubuntu-latest
    
    services:
      postgres:
        image: postgres:15.5-alpine
        env:
          POSTGRES_DB: carddemo_test
          POSTGRES_USER: test_user
          POSTGRES_PASSWORD: test_pass
        ports:
          - 5432:5432
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
      
      redis:
        image: redis:7.2-alpine
        ports:
          - 6379:6379
        options: >-
          --health-cmd "redis-cli ping"
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5

    steps:
    - uses: actions/checkout@v4
    
    - name: Set up JDK 21
      uses: actions/setup-java@v4
      with:
        java-version: '21'
        distribution: 'temurin'
        cache: 'maven'
    
    - name: Run unit tests
      run: mvn test
      working-directory: ./backend
    
    - name: Run integration tests
      run: mvn verify -Pintegration-tests
      working-directory: ./backend
    
    - name: Generate coverage report
      run: mvn jacoco:report
      working-directory: ./backend
    
    - name: Upload coverage to Codecov
      uses: codecov/codecov-action@v3
      with:
        files: ./backend/target/site/jacoco/jacoco.xml
        flags: backend
    
    - name: Check coverage thresholds
      run: mvn jacoco:check
      working-directory: ./backend
    
    - name: Publish test results
      uses: EnricoMi/publish-unit-test-result-action@v2
      if: always()
      with:
        files: |
          backend/target/surefire-reports/*.xml
          backend/target/failsafe-reports/*.xml
```

### 11.2 Test Reporting and Coverage Dashboards

SonarQube integration for code quality and coverage tracking:

```yaml
    - name: SonarQube Scan
      env:
        SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
        SONAR_HOST_URL: ${{ secrets.SONAR_HOST_URL }}
      run: |
        mvn sonar:sonar \
          -Dsonar.projectKey=carddemo-java \
          -Dsonar.host.url=$SONAR_HOST_URL \
          -Dsonar.login=$SONAR_TOKEN \
          -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
      working-directory: ./backend
```

### 11.3 Failed Test Notification

Slack notification on test failures:

```yaml
    - name: Notify on failure
      if: failure()
      uses: slackapi/slack-github-action@v1
      with:
        webhook-url: ${{ secrets.SLACK_WEBHOOK_URL }}
        payload: |
          {
            "text": "❌ CardDemo Backend Tests Failed",
            "blocks": [
              {
                "type": "section",
                "text": {
                  "type": "mrkdwn",
                  "text": "*Build Failed:* <${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}|View Details>\n*Branch:* ${{ github.ref }}\n*Commit:* ${{ github.sha }}"
                }
              }
            ]
          }
```

### 11.4 Deployment Gate Enforcement

Prevent deployment if tests fail or coverage is below threshold:

```yaml
  deploy:
    needs: test
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main' && github.event_name == 'push'
    
    steps:
    - name: Check test results
      run: |
        if [ "${{ needs.test.result }}" != "success" ]; then
          echo "Tests failed, deployment blocked"
          exit 1
        fi
    
    - name: Deploy to production
      run: |
        # Deployment commands here
        echo "Deploying to production..."
```

---

## 12. Test Execution Procedures

### 12.1 Local Test Execution

**Backend Unit Tests:**
```bash
# Run all unit tests
cd backend
mvn clean test

# Run specific test class
mvn test -Dtest=AccountServiceTest

# Run tests with coverage
mvn clean test jacoco:report

# View coverage report
open target/site/jacoco/index.html
```

**Backend Integration Tests:**
```bash
# Run integration tests only
mvn clean verify -Pintegration-tests

# Run with Testcontainers (requires Docker)
docker ps # Verify Docker is running
mvn verify -Pintegration-tests
```

**Frontend Tests:**
```bash
# Run all frontend tests
cd frontend
npm test

# Run tests in watch mode
npm test -- --watch

# Run tests with coverage
npm test -- --coverage

# View coverage report
open coverage/lcov-report/index.html
```

### 12.2 Full Test Suite Execution

Execute complete test suite before deployment:

```bash
# Backend full test suite
cd backend
mvn clean verify -Pall-tests

# Frontend full test suite
cd frontend
npm run test:all

# Run both concurrently
./scripts/run-all-tests.sh
```

**scripts/run-all-tests.sh:**
```bash
#!/bin/bash
set -e

echo "=== Running Backend Tests ==="
cd backend
mvn clean verify -Pall-tests
cd ..

echo "=== Running Frontend Tests ==="
cd frontend
npm test -- --coverage --watchAll=false
cd ..

echo "=== All Tests Passed ==="
```

### 12.3 Test Result Interpretation

**Understanding Test Output:**

**Successful Test Run:**
```
[INFO] Tests run: 150, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 150, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

**Failed Test Run:**
```
[ERROR] Failures: 
[ERROR]   AccountServiceTest.testViewAccount_InvalidAccountId_ThrowsException:45 
    Expected: AccountNotFoundException
    But was: NullPointerException
[INFO] 
[INFO] Tests run: 150, Failures: 1, Errors: 0, Skipped: 0
[INFO]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
```

### 12.4 Debugging Test Failures

**Enable Debug Logging:**
```bash
# Maven with debug output
mvn test -X -Dtest=AccountServiceTest

# Spring Boot test logging
mvn test -Dlogging.level.com.carddemo=DEBUG
```

**Run Single Test Method:**
```bash
# JUnit 5 single method
mvn test -Dtest=AccountServiceTest#testViewAccount_ValidAccountId_ReturnsAccountDetails
```

**Inspect Test Database State:**
```java
@Test
void debugTest() {
    // Add breakpoint here and inspect database
    List<Account> accounts = accountRepository.findAll();
    System.out.println("Account count: " + accounts.size());
    accounts.forEach(System.out::println);
}
```

---

## 13. Regression Testing

### 13.1 Automated Regression Test Suite

Maintain comprehensive regression test suite based on COBOL test scenarios:

```java
@Suite
@SelectPackages("com.carddemo.regression")
@IncludeTags("regression")
public class RegressionTestSuite {
    // All regression tests executed together
}

@Tag("regression")
@SpringBootTest
class AccountRegressionTest {

    @Test
    @DisplayName("REGRESSION: Account creation workflow end-to-end")
    void testAccountCreationWorkflow_CompleteFlow_MatchesCobol() {
        // Test scenario from COBOL test case library
        // Ensures no breaking changes in account creation flow
    }
}
```

### 13.2 Continuous Validation of Functional Equivalence

Scheduled daily regression tests against COBOL baseline:

```bash
# Cron job for nightly regression tests
0 2 * * * cd /opt/carddemo && ./scripts/run-regression-tests.sh
```

**scripts/run-regression-tests.sh:**
```bash
#!/bin/bash
# Nightly regression test execution

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="regression_${TIMESTAMP}.log"

echo "Starting regression test suite at $(date)" | tee $LOG_FILE

# Execute all regression tests
mvn clean verify -Pregression-tests >> $LOG_FILE 2>&1

if [ $? -eq 0 ]; then
    echo "✓ Regression tests PASSED" | tee -a $LOG_FILE
    # Send success notification
else
    echo "✗ Regression tests FAILED" | tee -a $LOG_FILE
    # Send failure alert
    mail -s "Regression Test Failure" devteam@company.com < $LOG_FILE
fi
```

### 13.3 Breaking Change Detection

Monitor for breaking changes in API contracts and data formats:

```java
@Test
@DisplayName("API contract - account view response - unchanged schema")
void testApiContract_AccountViewResponse_UnchangedSchema() {
    // Given: Expected schema from API specification
    String expectedSchema = loadSchemaFromFile("schemas/account-view-response-v1.json");

    // When: Generate schema from current DTO
    String actualSchema = generateJsonSchema(AccountViewResponse.class);

    // Then: Schema unchanged (no breaking changes)
    JSONAssert.assertEquals(expectedSchema, actualSchema, JSONCompareMode.STRICT);
}
```

### 13.4 Impact Analysis

Automated impact analysis when code changes:

```java
@Test
@DisplayName("Impact analysis - service method signature unchanged")
void testImpactAnalysis_ServiceMethodSignature_Unchanged() throws Exception {
    // Given: Expected method signature
    Method expectedMethod = AccountService.class.getMethod(
        "viewAccount", Long.class);

    // Then: Verify return type and parameters unchanged
    assertThat(expectedMethod.getReturnType()).isEqualTo(AccountViewResponse.class);
    assertThat(expectedMethod.getParameterTypes()).containsExactly(Long.class);
    assertThat(expectedMethod.getExceptionTypes()).isEmpty();
}
```

---

## 14. Documentation and Standards

### 14.1 Test Naming Conventions

Follow Given-When-Then pattern for test method names:

**Pattern:**
```
test<MethodUnderTest>_<Scenario>_<ExpectedOutcome>
```

**Examples:**
```java
testViewAccount_ValidAccountId_ReturnsAccountDetails()
testCreateAccount_InvalidCreditLimit_ThrowsValidationException()
testProcessPayment_InsufficientBalance_RollsBackTransaction()
```

### 14.2 Test Organization Structure

Organize tests to mirror source code structure:

```
backend/src/test/java/com/carddemo/
├── controller/
│   ├── AccountControllerTest.java
│   ├── CardControllerTest.java
│   └── TransactionControllerTest.java
├── service/
│   ├── AccountServiceTest.java
│   ├── CardServiceTest.java
│   └── TransactionServiceTest.java
├── repository/
│   ├── AccountRepositoryTest.java
│   └── CardRepositoryTest.java
├── batch/
│   └── job/
│       ├── AccountDataLoadJobTest.java
│       └── InterestCalculationJobTest.java
└── integration/
    ├── AccountIntegrationTest.java
    └── TransactionIntegrationTest.java
```

### 14.3 Assertion Best Practices

Use AssertJ fluent assertions for readability:

```java
// ❌ Poor assertion
assertTrue(account.getBalance().compareTo(new BigDecimal("5000.00")) == 0);

// ✅ Good assertion
assertThat(account.getBalance()).isEqualByComparingTo("5000.00");

// ✅ Complex assertion with descriptive message
assertThat(account)
    .describedAs("Account should have correct balance after payment")
    .extracting(Account::getCurrentBalance)
    .isEqualByComparingTo("4850.00");
```

### 14.4 Test Maintenance Procedures

**Regular Test Review:**
- Quarterly review of all test cases for relevance
- Remove obsolete tests for deprecated features
- Update tests when business requirements change
- Refactor tests to improve maintainability

**Test Code Quality:**
- Apply same code quality standards as production code
- Use test builders and fixtures for reusability
- Keep tests independent and isolated
- Avoid test interdependencies

**Documentation:**
- Add JavaDoc to complex test setups
- Reference COBOL test case IDs in test comments
- Document test data sources and expected values
- Maintain test coverage reports

---

## Appendix A: Test Coverage Summary

### Current Test Coverage Targets

| Layer | Target Coverage | Current Status |
|-------|----------------|----------------|
| Service Layer | 90% | To be achieved post-migration |
| Controller Layer | 85% | To be achieved post-migration |
| Repository Layer | 75% | To be achieved post-migration |
| Batch Jobs | 85% | To be achieved post-migration |
| Utility Classes | 80% | To be achieved post-migration |
| **Overall Backend** | **80%** | **Mandatory Requirement** |
| React Components | 75% | To be achieved post-migration |
| Redux Slices | 80% | To be achieved post-migration |
| **Overall Frontend** | **75%** | **Target** |

### Test Class Inventory

**Backend Test Classes: 61+**
- Controller Tests: 9 classes
- Service Tests: 17 classes
- Repository Tests: 8 classes
- Batch Job Tests: 11 classes
- Integration Tests: 8 classes
- Utility Tests: 8+ classes

**Frontend Test Classes: 26+**
- Component Tests: 17 classes
- Redux Tests: 4 classes
- Service Tests: 5 classes

---

## Appendix B: Troubleshooting Guide

### Common Test Issues

**Issue: Testcontainers fails to start PostgreSQL**
```
Solution:
1. Ensure Docker is running: docker ps
2. Check Docker resources (memory > 4GB)
3. Verify network connectivity: docker network ls
```

**Issue: Tests pass locally but fail in CI**
```
Solution:
1. Check environment-specific configuration
2. Verify test data initialization in CI environment
3. Review CI service health checks
4. Check for timezone differences
```

**Issue: Intermittent test failures**
```
Solution:
1. Add @Retry annotation for flaky tests
2. Increase timeouts for async operations
3. Review test isolation and cleanup
4. Check for shared state between tests
```

---

## Appendix C: Reference Links

- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)
- [Spring Boot Testing Documentation](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing)
- [Testcontainers Documentation](https://www.testcontainers.org/)
- [React Testing Library](https://testing-library.com/docs/react-testing-library/intro/)
- [JMeter Performance Testing](https://jmeter.apache.org/usermanual/index.html)
- [AssertJ Assertions](https://assertj.github.io/doc/)

---

**Document Version**: 1.0  
**Last Updated**: 2024-01-20  
**Maintained By**: CardDemo Development Team

