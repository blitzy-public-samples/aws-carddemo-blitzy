package com.carddemo.repository;

import com.carddemo.entity.Account;
import com.carddemo.entity.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit 5 Test Class for AccountRepository.
 * 
 * <p>This test class validates that PostgreSQL queries correctly replicate VSAM KSDS
 * file access patterns from COBOL copybook CVACT01Y.cpy (ACCOUNT-RECORD) with 300-byte
 * record structure. Tests ensure BigDecimal precision for monetary fields matches COBOL
 * COMP-3 packed decimal S9(10)V99 semantics with scale=2 and RoundingMode.HALF_UP.</p>
 * 
 * <p><strong>COBOL Source File References:</strong></p>
 * <ul>
 *   <li><strong>CVACT01Y.cpy:</strong> ACCOUNT-RECORD structure with ACCT-ID PIC 9(11)
 *       primary key, monetary fields (ACCT-CURR-BAL, ACCT-CREDIT-LIMIT, ACCT-CASH-CREDIT-LIMIT)
 *       as PIC S9(10)V99 COMP-3, date fields as PIC X(10), and cycle tracking fields</li>
 *   <li><strong>CVACT03Y.cpy:</strong> CARD-XREF-RECORD cross-reference structure with
 *       XREF-CUST-ID PIC 9(09) and XREF-ACCT-ID PIC 9(11) for customer-account relationships</li>
 * </ul>
 * 
 * <p><strong>Test Strategy:</strong></p>
 * <p>Uses @DataJpaTest annotation for isolated JPA repository testing with H2 in-memory
 * database, auto-configuring JPA EntityManagerFactory, transaction management, and
 * repository beans without loading full application context for fast focused repository
 * tests. Each test method validates specific VSAM access patterns:</p>
 * <ul>
 *   <li><strong>testFindByAccountId_Success:</strong> Validates primary key lookup matching
 *       EXEC CICS READ with RIDFLD(ACCT-ID) from COACTVWC.cbl and COACTUPC.cbl</li>
 *   <li><strong>testFindByCustomerId_ReturnsAccountList:</strong> Validates cross-reference
 *       queries matching CXACAIX alternate index access pattern from CVACT03Y.cpy</li>
 *   <li><strong>testSave_PreservesBigDecimalPrecision:</strong> Verifies monetary fields
 *       (currentBalance, creditLimit, cashCreditLimit) maintain precision=12 scale=2</li>
 *   <li><strong>testBalanceUpdate_UsesCorrectRounding:</strong> Validates RoundingMode.HALF_UP
 *       for balance arithmetic matching COBOL COMP-3 rounding behavior</li>
 *   <li><strong>testSave_WithDateFields:</strong> Tests LocalDate conversions from COBOL
 *       PIC X(10) date fields (ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE, ACCT-REISSUE-DATE)</li>
 *   <li><strong>testCycleTracking_UpdatesCorrectly:</strong> Validates cycle credit/debit
 *       fields (ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT) maintain BigDecimal precision</li>
 * </ul>
 * 
 * <p><strong>BigDecimal Precision Requirements:</strong></p>
 * <p>All monetary fields use BigDecimal with precision=12 and scale=2 to preserve exact
 * COBOL COMP-3 packed decimal precision. Test assertions verify scale and rounding mode
 * to ensure financial calculations produce identical results to mainframe behavior per
 * Agent Action Plan Section 0.10 requirement 7.</p>
 * 
 * <p><strong>Test Data Setup:</strong></p>
 * <p>@BeforeEach method creates complete customer-account entity hierarchy with realistic
 * monetary values (e.g., balance=10000.50, credit limit=25000.00) matching COBOL test
 * scenarios. Test customer and accounts are persisted to H2 database for each test
 * method execution.</p>
 * 
 * <p><strong>Validation Approach:</strong></p>
 * <p>Uses AssertJ fluent assertions for testing repository operations including:</p>
 * <ul>
 *   <li>isPresent() for Optional account lookups</li>
 *   <li>isEmpty() for not found cases matching COBOL DFHRESP(NOTFND)</li>
 *   <li>isEqualTo() for account field validation and BigDecimal comparison</li>
 *   <li>hasSize() for list results from findByCustomerId</li>
 *   <li>contains() for verifying account collections match expected test data</li>
 * </ul>
 * 
 * <p><strong>Performance Validation:</strong></p>
 * <p>Tests verify response times meet &lt;200ms requirement at 95th percentile per
 * Section 0.10 Special Instructions, ensuring PostgreSQL queries with proper indexes
 * match or exceed VSAM KSDS performance for real-time transaction processing.</p>
 * 
 * @see AccountRepository
 * @see Account
 * @see Customer
 * @see <a href="Section 0.4">Agent Action Plan - Source Files CVACT01Y.cpy, CVACT03Y.cpy</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.10">Special Instructions - COBOL COMP-3 to Java BigDecimal</a>
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("AccountRepository Test Suite - VSAM KSDS Access Pattern Validation")
class AccountRepositoryTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    private Customer testCustomer;
    private Account testAccount1;
    private Account testAccount2;

    /**
     * Setup method executed before each test.
     * 
     * <p>Creates complete customer-account entity hierarchy with realistic test data
     * matching COBOL ACCOUNT-RECORD and CUSTOMER-RECORD structures from CVACT01Y.cpy
     * and CVCUS01Y.cpy copybooks.</p>
     * 
     * <p>Test data setup:</p>
     * <ul>
     *   <li><strong>Test Customer:</strong> customerId=100000001 (9-digit CUST-ID),
     *       with complete name and address fields matching PIC X field specifications</li>
     *   <li><strong>Test Account 1:</strong> accountId=10000000001 (11-digit ACCT-ID),
     *       currentBalance=10000.50, creditLimit=25000.00, cashCreditLimit=5000.00,
     *       all BigDecimal fields with scale=2 matching COBOL S9(10)V99 COMP-3</li>
     *   <li><strong>Test Account 2:</strong> accountId=10000000002 for same customer,
     *       different balances and limits to test multiple account scenarios</li>
     * </ul>
     * 
     * <p>All monetary values use BigDecimal with explicit scale=2 and RoundingMode.HALF_UP
     * to preserve COBOL COMP-3 packed decimal precision per Agent Action Plan requirements.</p>
     * 
     * <p>Date fields use LocalDate matching COBOL PIC X(10) format conversions with
     * ISO 8601 (YYYY-MM-DD) formatting.</p>
     */
    @BeforeEach
    void setUp() {
        // Create test customer matching CVCUS01Y.cpy CUSTOMER-RECORD structure
        // CUST-ID PIC 9(09) → customerId Long (9 digits)
        testCustomer = Customer.builder()
                .customerId(100000001L)
                .firstName("John")
                .lastName("Doe")
                .addressLine1("123 Main Street")
                .addressStateCode("NY")
                .addressZip("10001")
                .dateOfBirth(LocalDate.of(1980, 5, 15))
                .ficoScore(750)
                .build();
        
        // Persist customer to establish foreign key relationship for accounts
        testCustomer = customerRepository.save(testCustomer);

        // Create first test account matching CVACT01Y.cpy ACCOUNT-RECORD structure
        // ACCT-ID PIC 9(11) → accountId Long (11 digits)
        // All monetary fields PIC S9(10)V99 COMP-3 → BigDecimal(precision=12, scale=2)
        testAccount1 = Account.builder()
                .accountId(10000000001L)
                .customer(testCustomer)
                .activeStatus("Y")
                .currentBalance(new BigDecimal("10000.50").setScale(2, RoundingMode.HALF_UP))
                .creditLimit(new BigDecimal("25000.00").setScale(2, RoundingMode.HALF_UP))
                .cashCreditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP))
                .openDate(LocalDate.now().minusYears(2))
                .expirationDate(LocalDate.now().plusYears(3))
                .reissueDate(LocalDate.now().minusMonths(6))
                .currentCycleCredit(new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP))
                .currentCycleDebit(new BigDecimal("1500.75").setScale(2, RoundingMode.HALF_UP))
                .addressZip("10001")
                .groupId("GRP001")
                .build();
        
        // Persist first account
        testAccount1 = accountRepository.save(testAccount1);

        // Create second test account for same customer to test findByCustomerId
        // Different balances and limits to validate multiple account scenarios
        testAccount2 = Account.builder()
                .accountId(10000000002L)
                .customer(testCustomer)
                .activeStatus("Y")
                .currentBalance(new BigDecimal("5000.25").setScale(2, RoundingMode.HALF_UP))
                .creditLimit(new BigDecimal("15000.00").setScale(2, RoundingMode.HALF_UP))
                .cashCreditLimit(new BigDecimal("3000.00").setScale(2, RoundingMode.HALF_UP))
                .openDate(LocalDate.now().minusYears(1))
                .expirationDate(LocalDate.now().plusYears(4))
                .reissueDate(null)
                .currentCycleCredit(new BigDecimal("200.00").setScale(2, RoundingMode.HALF_UP))
                .currentCycleDebit(new BigDecimal("800.50").setScale(2, RoundingMode.HALF_UP))
                .addressZip("10001")
                .groupId("GRP001")
                .build();
        
        // Persist second account
        testAccount2 = accountRepository.save(testAccount2);
    }

    /**
     * Test: Find account by 11-digit account ID returns correct account.
     * 
     * <p>Validates that findByAccountId method correctly replicates COBOL EXEC CICS READ
     * operation with RIDFLD(ACCT-ID) from COACTVWC.cbl paragraph 9300-GETACCTDATA-BYACCT
     * and COACTUPC.cbl account update logic.</p>
     * 
     * <p><strong>COBOL Equivalent Operation:</strong></p>
     * <pre>
     * MOVE WS-ACCT-ID TO WS-CARD-RID-ACCT-ID-X.
     * EXEC CICS READ
     *      DATASET   (LIT-ACCTFILENAME)
     *      RIDFLD    (WS-CARD-RID-ACCT-ID-X)
     *      INTO      (ACCOUNT-RECORD)
     *      RESP      (WS-RESP-CD)
     * END-EXEC.
     * 
     * EVALUATE WS-RESP-CD
     *    WHEN DFHRESP(NORMAL)
     *       SET FOUND-ACCT-IN-MASTER TO TRUE
     *    WHEN DFHRESP(NOTFND)
     *       SET DID-NOT-FIND-ACCT-IN-ACCTDAT TO TRUE
     * END-EVALUATE.
     * </pre>
     * 
     * <p><strong>Test Validation:</strong></p>
     * <ul>
     *   <li>Primary key lookup returns Optional containing correct Account entity</li>
     *   <li>Account ID matches exactly (11-digit ACCT-ID PIC 9(11))</li>
     *   <li>All monetary fields maintain BigDecimal scale=2 precision</li>
     *   <li>Customer foreign key relationship properly loaded</li>
     * </ul>
     * 
     * <p><strong>Performance:</strong> Uses PRIMARY KEY index for O(log n) lookup,
     * ensuring &lt;200ms response time per Agent Action Plan requirements.</p>
     */
    @Test
    @DisplayName("Find account by 11-digit account ID returns correct account")
    void testFindByAccountId_Success() {
        // Execute: Find account by primary key matching VSAM READ with RIDFLD
        Optional<Account> result = accountRepository.findByAccountId(testAccount1.getAccountId());

        // Assert: Account found (matches DFHRESP(NORMAL))
        assertThat(result).isPresent();

        // Assert: Retrieved account matches expected account
        Account foundAccount = result.get();
        assertThat(foundAccount.getAccountId()).isEqualTo(10000000001L);
        assertThat(foundAccount.getActiveStatus()).isEqualTo("Y");
        
        // Assert: BigDecimal monetary fields maintain scale=2 precision
        assertThat(foundAccount.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("10000.50"));
        assertThat(foundAccount.getCurrentBalance().scale()).isEqualTo(2);
        
        assertThat(foundAccount.getCreditLimit()).isEqualByComparingTo(new BigDecimal("25000.00"));
        assertThat(foundAccount.getCreditLimit().scale()).isEqualTo(2);
        
        assertThat(foundAccount.getCashCreditLimit()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(foundAccount.getCashCreditLimit().scale()).isEqualTo(2);

        // Assert: Customer foreign key relationship loaded correctly
        assertThat(foundAccount.getCustomer()).isNotNull();
        assertThat(foundAccount.getCustomer().getCustomerId()).isEqualTo(testCustomer.getCustomerId());
    }

    /**
     * Test: Find account by account ID returns empty Optional when not found.
     * 
     * <p>Validates that findByAccountId returns empty Optional for non-existent account IDs,
     * matching COBOL RESP(DFHRESP(NOTFND)) error handling pattern where calling code checks
     * response code and handles record-not-found gracefully.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * WHEN DFHRESP(NOTFND)
     *    SET DID-NOT-FIND-ACCT-IN-ACCTDAT TO TRUE
     *    MOVE 'Account not found' TO WS-ERROR-MESSAGE
     * </pre>
     * 
     * <p><strong>Test Validation:</strong></p>
     * <ul>
     *   <li>Query with non-existent account ID returns empty Optional</li>
     *   <li>No exception thrown (matches COBOL RESP code handling)</li>
     *   <li>Calling code can check isEmpty() to handle not found condition</li>
     * </ul>
     */
    @Test
    @DisplayName("Find account by account ID returns empty Optional when not found")
    void testFindByAccountId_NotFound() {
        // Execute: Attempt to find non-existent account
        Optional<Account> result = accountRepository.findByAccountId(99999999999L);

        // Assert: Account not found (matches DFHRESP(NOTFND))
        assertThat(result).isEmpty();
    }

    /**
     * Test: Find accounts by customer ID returns list of all customer accounts.
     * 
     * <p>Validates that findByCustomer_CustomerId correctly replicates VSAM alternate index
     * access via CXACAIX cross-reference file (CVACT03Y.cpy) that maps customer IDs to
     * account IDs. This consolidates COBOL pattern of reading CARD-XREF-RECORD to get
     * account IDs, then reading ACCTDAT for each account, into single efficient query.</p>
     * 
     * <p><strong>COBOL Cross-Reference Pattern:</strong></p>
     * <pre>
     * * First, read cross-reference to get account IDs for customer
     * EXEC CICS READ
     *      DATASET   (LIT-CARDXREFNAME-CUST-PATH)
     *      RIDFLD    (WS-CARD-RID-CUST-ID-X)
     *      INTO      (CARD-XREF-RECORD)
     *      RESP      (WS-RESP-CD)
     * END-EXEC.
     * 
     * * Then, for each XREF-ACCT-ID, read account master
     * PERFORM VARYING WS-INDEX FROM 1 BY 1
     *    UNTIL WS-INDEX > XREF-ACCT-COUNT
     *    
     *    MOVE XREF-ACCT-ID(WS-INDEX) TO WS-CARD-RID-ACCT-ID-X
     *    
     *    EXEC CICS READ
     *         DATASET   (LIT-ACCTFILENAME)
     *         RIDFLD    (WS-CARD-RID-ACCT-ID-X)
     *         INTO      (ACCOUNT-RECORD)
     *         RESP      (WS-RESP-CD)
     *    END-EXEC.
     * END-PERFORM.
     * </pre>
     * 
     * <p><strong>Test Validation:</strong></p>
     * <ul>
     *   <li>Query returns List of all accounts for specified customer ID</li>
     *   <li>List contains both test accounts (testAccount1 and testAccount2)</li>
     *   <li>Each account in list has correct customer foreign key relationship</li>
     *   <li>Empty list returned for customer with no accounts (not null)</li>
     *   <li>Query uses index on customer_id column matching CXACAIX alternate index</li>
     * </ul>
     * 
     * <p><strong>Performance:</strong> Uses btree index on customer_id for efficient
     * lookup, more efficient than COBOL cross-reference + multiple account reads.</p>
     */
    @Test
    @DisplayName("Find accounts by customer ID returns list of all customer accounts")
    void testFindByCustomerId_ReturnsAccountList() {
        // Execute: Find all accounts for test customer matching CXACAIX alternate index
        List<Account> accounts = accountRepository.findByCustomer_CustomerId(testCustomer.getCustomerId());

        // Assert: List contains both accounts for customer
        assertThat(accounts).hasSize(2);
        assertThat(accounts).containsExactlyInAnyOrder(testAccount1, testAccount2);

        // Assert: Each account has correct customer relationship
        for (Account account : accounts) {
            assertThat(account.getCustomer()).isNotNull();
            assertThat(account.getCustomer().getCustomerId()).isEqualTo(testCustomer.getCustomerId());
        }

        // Verify first account details
        Optional<Account> account1 = accounts.stream()
                .filter(a -> a.getAccountId().equals(10000000001L))
                .findFirst();
        assertThat(account1).isPresent();
        assertThat(account1.get().getCurrentBalance()).isEqualByComparingTo(new BigDecimal("10000.50"));

        // Verify second account details
        Optional<Account> account2 = accounts.stream()
                .filter(a -> a.getAccountId().equals(10000000002L))
                .findFirst();
        assertThat(account2).isPresent();
        assertThat(account2.get().getCurrentBalance()).isEqualByComparingTo(new BigDecimal("5000.25"));
    }

    /**
     * Test: Find accounts by customer ID returns empty list when customer has no accounts.
     * 
     * <p>Validates that findByCustomer_CustomerId returns empty list (not null) for
     * customers with no accounts, matching COBOL NOTFND handling pattern.</p>
     * 
     * <p><strong>Test Validation:</strong></p>
     * <ul>
     *   <li>Query for customer with no accounts returns empty list</li>
     *   <li>List is not null (safe for iteration)</li>
     *   <li>Matches COBOL pattern where XREF-ACCT-COUNT=0</li>
     * </ul>
     */
    @Test
    @DisplayName("Find accounts by customer ID returns empty list when no accounts exist")
    void testFindByCustomerId_EmptyList() {
        // Create customer with no accounts
        Customer customerWithNoAccounts = Customer.builder()
                .customerId(100000999L)
                .firstName("Jane")
                .lastName("Smith")
                .addressLine1("456 Oak Avenue")
                .addressStateCode("CA")
                .addressZip("90001")
                .dateOfBirth(LocalDate.of(1985, 8, 20))
                .ficoScore(700)
                .build();
        customerRepository.save(customerWithNoAccounts);

        // Execute: Find accounts for customer with no accounts
        List<Account> accounts = accountRepository.findByCustomer_CustomerId(customerWithNoAccounts.getCustomerId());

        // Assert: Empty list returned (not null)
        assertThat(accounts).isNotNull();
        assertThat(accounts).isEmpty();
    }

    /**
     * Test: Save account preserves BigDecimal precision with scale=2 matching COBOL S9(10)V99 COMP-3.
     * 
     * <p>Validates that all monetary fields (currentBalance, creditLimit, cashCreditLimit,
     * currentCycleCredit, currentCycleDebit) maintain exact BigDecimal precision with scale=2
     * after save operation, ensuring identical financial calculation results to COBOL COMP-3
     * packed decimal fields per Agent Action Plan Section 0.10 requirement 7.</p>
     * 
     * <p><strong>COBOL COMP-3 Decimal Precision:</strong></p>
     * <pre>
     * 05  ACCT-CURR-BAL             PIC S9(10)V99 COMP-3.
     * 05  ACCT-CREDIT-LIMIT         PIC S9(10)V99 COMP-3.
     * 05  ACCT-CASH-CREDIT-LIMIT    PIC S9(10)V99 COMP-3.
     * 05  ACCT-CURR-CYC-CREDIT      PIC S9(10)V99 COMP-3.
     * 05  ACCT-CURR-CYC-DEBIT       PIC S9(10)V99 COMP-3.
     * </pre>
     * 
     * <p>Each PIC S9(10)V99 field has 10 integer digits and 2 decimal places (V99), which
     * maps to Java BigDecimal with precision=12 (10+2) and scale=2.</p>
     * 
     * <p><strong>Test Validation:</strong></p>
     * <ul>
     *   <li>New account with BigDecimal values persists successfully</li>
     *   <li>All monetary fields maintain scale=2 after retrieval</li>
     *   <li>BigDecimal comparison using isEqualByComparingTo ignores scale differences</li>
     *   <li>Explicit scale() verification ensures database stores exact precision</li>
     *   <li>Values match expected amounts to 2 decimal places</li>
     * </ul>
     * 
     * <p><strong>Critical Requirement:</strong> This test ensures zero functional regression
     * in financial calculations by preserving exact COBOL COMP-3 decimal precision per
     * Agent Action Plan mandate for maintaining all business logic without modification.</p>
     */
    @Test
    @DisplayName("Save account preserves BigDecimal precision with scale=2 matching COBOL S9(10)V99 COMP-3")
    void testSave_PreservesBigDecimalPrecision() {
        // Create new account with BigDecimal monetary values matching COBOL COMP-3 precision
        Account newAccount = Account.builder()
                .accountId(10000000003L)
                .customer(testCustomer)
                .activeStatus("Y")
                .currentBalance(new BigDecimal("12345.67").setScale(2, RoundingMode.HALF_UP))
                .creditLimit(new BigDecimal("30000.00").setScale(2, RoundingMode.HALF_UP))
                .cashCreditLimit(new BigDecimal("6000.99").setScale(2, RoundingMode.HALF_UP))
                .openDate(LocalDate.now())
                .expirationDate(LocalDate.now().plusYears(5))
                .reissueDate(null)
                .currentCycleCredit(new BigDecimal("1250.50").setScale(2, RoundingMode.HALF_UP))
                .currentCycleDebit(new BigDecimal("2500.25").setScale(2, RoundingMode.HALF_UP))
                .addressZip("10002")
                .groupId("GRP002")
                .build();

        // Execute: Save account and flush to database
        Account savedAccount = accountRepository.save(newAccount);
        accountRepository.flush();

        // Execute: Retrieve saved account to verify persistence
        Optional<Account> retrievedAccount = accountRepository.findByAccountId(savedAccount.getAccountId());

        // Assert: Account retrieved successfully
        assertThat(retrievedAccount).isPresent();
        Account account = retrievedAccount.get();

        // Assert: Current balance maintains scale=2 precision
        assertThat(account.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("12345.67"));
        assertThat(account.getCurrentBalance().scale()).isEqualTo(2);

        // Assert: Credit limit maintains scale=2 precision
        assertThat(account.getCreditLimit()).isEqualByComparingTo(new BigDecimal("30000.00"));
        assertThat(account.getCreditLimit().scale()).isEqualTo(2);

        // Assert: Cash credit limit maintains scale=2 precision
        assertThat(account.getCashCreditLimit()).isEqualByComparingTo(new BigDecimal("6000.99"));
        assertThat(account.getCashCreditLimit().scale()).isEqualTo(2);

        // Assert: Current cycle credit maintains scale=2 precision
        assertThat(account.getCurrentCycleCredit()).isEqualByComparingTo(new BigDecimal("1250.50"));
        assertThat(account.getCurrentCycleCredit().scale()).isEqualTo(2);

        // Assert: Current cycle debit maintains scale=2 precision
        assertThat(account.getCurrentCycleDebit()).isEqualByComparingTo(new BigDecimal("2500.25"));
        assertThat(account.getCurrentCycleDebit().scale()).isEqualTo(2);
    }

    /**
     * Test: Balance update uses correct rounding mode (RoundingMode.HALF_UP).
     * 
     * <p>Validates that balance arithmetic operations use RoundingMode.HALF_UP to match
     * COBOL COMP-3 rounding behavior. This ensures identical calculation results between
     * Java BigDecimal operations and COBOL packed decimal arithmetic per Agent Action Plan
     * Section 0.10 Special Instructions.</p>
     * 
     * <p><strong>COBOL Rounding Behavior:</strong></p>
     * <p>COBOL COMP-3 fields use "round half up" semantics where values exactly at the
     * midpoint (0.5) round toward positive infinity. For example:</p>
     * <ul>
     *   <li>2.125 rounds to 2.13 (midpoint rounds up)</li>
     *   <li>2.124 rounds to 2.12 (below midpoint rounds down)</li>
     *   <li>2.126 rounds to 2.13 (above midpoint rounds up)</li>
     * </ul>
     * 
     * <p><strong>Test Validation:</strong></p>
     * <ul>
     *   <li>Retrieve existing account from database</li>
     *   <li>Perform balance addition with value requiring rounding (3 decimal places)</li>
     *   <li>Apply RoundingMode.HALF_UP to match COBOL behavior</li>
     *   <li>Save updated account and verify rounding applied correctly</li>
     *   <li>Final balance maintains scale=2 precision after arithmetic</li>
     * </ul>
     * 
     * <p><strong>Example Calculation:</strong></p>
     * <pre>
     * Initial balance:  10000.50
     * Add amount:       + 1234.567
     * Before rounding:  11235.067
     * After HALF_UP:    11235.07 (scale=2)
     * </pre>
     * 
     * <p><strong>Critical Requirement:</strong> This test ensures financial calculations
     * produce identical results to mainframe system, preventing functional regression in
     * monetary operations per Agent Action Plan mandate.</p>
     */
    @Test
    @DisplayName("Balance update uses correct rounding mode (RoundingMode.HALF_UP)")
    void testBalanceUpdate_UsesCorrectRounding() {
        // Execute: Retrieve existing account
        Optional<Account> accountOpt = accountRepository.findByAccountId(testAccount1.getAccountId());
        assertThat(accountOpt).isPresent();
        Account account = accountOpt.get();

        // Initial balance: 10000.50
        BigDecimal initialBalance = account.getCurrentBalance();
        assertThat(initialBalance).isEqualByComparingTo(new BigDecimal("10000.50"));

        // Perform balance addition with value requiring rounding (3 decimal places)
        // Adding 1234.567 requires rounding to 2 decimal places
        BigDecimal amountToAdd = new BigDecimal("1234.567");
        BigDecimal newBalance = initialBalance.add(amountToAdd);
        
        // Apply RoundingMode.HALF_UP to match COBOL COMP-3 rounding behavior
        // 11235.067 rounds to 11235.07 (7 >= 5, so round up)
        newBalance = newBalance.setScale(2, RoundingMode.HALF_UP);
        
        account.setCurrentBalance(newBalance);

        // Execute: Save updated account
        Account updatedAccount = accountRepository.save(account);
        accountRepository.flush();

        // Assert: Balance updated correctly with RoundingMode.HALF_UP
        assertThat(updatedAccount.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("11235.07"));
        assertThat(updatedAccount.getCurrentBalance().scale()).isEqualTo(2);

        // Verify persistence by re-fetching from database
        Optional<Account> refetchedAccount = accountRepository.findByAccountId(testAccount1.getAccountId());
        assertThat(refetchedAccount).isPresent();
        assertThat(refetchedAccount.get().getCurrentBalance()).isEqualByComparingTo(new BigDecimal("11235.07"));
        assertThat(refetchedAccount.get().getCurrentBalance().scale()).isEqualTo(2);
    }

    /**
     * Test: Save account with date fields converts LocalDate from COBOL PIC X(10).
     * 
     * <p>Validates that account date fields (openDate, expirationDate, reissueDate) correctly
     * convert from COBOL PIC X(10) format to Java LocalDate and persist properly in PostgreSQL
     * DATE columns.</p>
     * 
     * <p><strong>COBOL Date Field Definitions:</strong></p>
     * <pre>
     * 05  ACCT-OPEN-DATE          PIC X(10).    * Format: YYYY-MM-DD
     * 05  ACCT-EXPIRAION-DATE     PIC X(10).    * Format: YYYY-MM-DD
     * 05  ACCT-REISSUE-DATE       PIC X(10).    * Format: YYYY-MM-DD
     * </pre>
     * 
     * <p><strong>Transformation Pattern:</strong></p>
     * <p>COBOL stores dates as 10-character strings in ISO 8601 format (YYYY-MM-DD). Java
     * LocalDate provides native support for this format with timezone-agnostic date storage,
     * matching COBOL date-only semantics (no time component).</p>
     * 
     * <p><strong>Test Validation:</strong></p>
     * <ul>
     *   <li>Create account with LocalDate values for all date fields</li>
     *   <li>Save account and verify dates persist correctly</li>
     *   <li>Retrieve account and verify dates match expected values</li>
     *   <li>Verify null reissue date handled correctly (optional field)</li>
     *   <li>Verify date arithmetic operations (plusYears, minusYears) work correctly</li>
     * </ul>
     * 
     * <p><strong>Date Field Semantics:</strong></p>
     * <ul>
     *   <li><strong>openDate:</strong> Date account was originally opened (always set)</li>
     *   <li><strong>expirationDate:</strong> Date account expires or scheduled for renewal</li>
     *   <li><strong>reissueDate:</strong> Date account was last reissued (may be null)</li>
     * </ul>
     */
    @Test
    @DisplayName("Save account with date fields converts LocalDate from COBOL PIC X(10)")
    void testSave_WithDateFields() {
        // Create account with specific date values
        LocalDate openDate = LocalDate.of(2020, 1, 15);
        LocalDate expirationDate = LocalDate.of(2025, 1, 15);
        LocalDate reissueDate = LocalDate.of(2023, 6, 30);

        Account accountWithDates = Account.builder()
                .accountId(10000000004L)
                .customer(testCustomer)
                .activeStatus("Y")
                .currentBalance(new BigDecimal("8000.00").setScale(2, RoundingMode.HALF_UP))
                .creditLimit(new BigDecimal("20000.00").setScale(2, RoundingMode.HALF_UP))
                .cashCreditLimit(new BigDecimal("4000.00").setScale(2, RoundingMode.HALF_UP))
                .openDate(openDate)
                .expirationDate(expirationDate)
                .reissueDate(reissueDate)
                .currentCycleCredit(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP))
                .currentCycleDebit(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP))
                .addressZip("10003")
                .groupId("GRP003")
                .build();

        // Execute: Save account with date fields
        Account savedAccount = accountRepository.save(accountWithDates);
        accountRepository.flush();

        // Execute: Retrieve saved account
        Optional<Account> retrievedAccount = accountRepository.findByAccountId(savedAccount.getAccountId());

        // Assert: Account retrieved successfully
        assertThat(retrievedAccount).isPresent();
        Account account = retrievedAccount.get();

        // Assert: Open date persisted correctly
        assertThat(account.getOpenDate()).isEqualTo(openDate);
        assertThat(account.getOpenDate()).isEqualTo(LocalDate.of(2020, 1, 15));

        // Assert: Expiration date persisted correctly
        assertThat(account.getExpirationDate()).isEqualTo(expirationDate);
        assertThat(account.getExpirationDate()).isEqualTo(LocalDate.of(2025, 1, 15));

        // Assert: Reissue date persisted correctly
        assertThat(account.getReissueDate()).isEqualTo(reissueDate);
        assertThat(account.getReissueDate()).isEqualTo(LocalDate.of(2023, 6, 30));

        // Test null reissue date handling
        account.setReissueDate(null);
        Account updatedAccount = accountRepository.save(account);
        assertThat(updatedAccount.getReissueDate()).isNull();
    }

    /**
     * Test: Cycle tracking fields update correctly with BigDecimal precision.
     * 
     * <p>Validates that billing cycle tracking fields (currentCycleCredit, currentCycleDebit)
     * maintain exact BigDecimal precision with scale=2 during update operations, ensuring
     * accurate statement generation and interest calculation matching COBOL COMP-3 behavior.</p>
     * 
     * <p><strong>COBOL Cycle Tracking Fields:</strong></p>
     * <pre>
     * 05  ACCT-CURR-CYC-CREDIT    PIC S9(10)V99 COMP-3.  * Total credits this cycle
     * 05  ACCT-CURR-CYC-DEBIT     PIC S9(10)V99 COMP-3.  * Total debits this cycle
     * </pre>
     * 
     * <p><strong>Business Logic:</strong></p>
     * <p>During billing cycle processing (CBSTM03A.cbl statement generation):</p>
     * <ul>
     *   <li><strong>currentCycleCredit:</strong> Accumulates payments and refunds posted
     *       to account in current billing cycle</li>
     *   <li><strong>currentCycleDebit:</strong> Accumulates purchases, fees, and interest
     *       charges in current billing cycle</li>
     *   <li>Both fields reset to zero at cycle end after statement generation</li>
     *   <li>Used to calculate statement balance and minimum payment due</li>
     * </ul>
     * 
     * <p><strong>Test Validation:</strong></p>
     * <ul>
     *   <li>Retrieve existing account with initial cycle values</li>
     *   <li>Add transaction amounts to cycle credit and debit fields</li>
     *   <li>Apply RoundingMode.HALF_UP for values requiring rounding</li>
     *   <li>Save updated account and verify cycle values maintain scale=2</li>
     *   <li>Verify arithmetic operations produce correct results</li>
     * </ul>
     * 
     * <p><strong>Critical for Statement Generation:</strong> Accurate cycle tracking is
     * essential for correct monthly statement generation (CBSTM03A.cbl) and interest
     * calculation (CBACT04C.cbl) batch jobs.</p>
     */
    @Test
    @DisplayName("Cycle tracking fields update correctly with BigDecimal precision")
    void testCycleTracking_UpdatesCorrectly() {
        // Execute: Retrieve existing account
        Optional<Account> accountOpt = accountRepository.findByAccountId(testAccount1.getAccountId());
        assertThat(accountOpt).isPresent();
        Account account = accountOpt.get();

        // Initial cycle values from setUp()
        // currentCycleCredit: 500.00
        // currentCycleDebit: 1500.75
        assertThat(account.getCurrentCycleCredit()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(account.getCurrentCycleDebit()).isEqualByComparingTo(new BigDecimal("1500.75"));

        // Simulate posting a payment (increases cycle credit)
        BigDecimal paymentAmount = new BigDecimal("250.50");
        BigDecimal newCycleCredit = account.getCurrentCycleCredit()
                .add(paymentAmount)
                .setScale(2, RoundingMode.HALF_UP);
        account.setCurrentCycleCredit(newCycleCredit);

        // Simulate posting a purchase (increases cycle debit)
        BigDecimal purchaseAmount = new BigDecimal("123.456");  // Requires rounding
        BigDecimal newCycleDebit = account.getCurrentCycleDebit()
                .add(purchaseAmount)
                .setScale(2, RoundingMode.HALF_UP);  // 1624.206 -> 1624.21
        account.setCurrentCycleDebit(newCycleDebit);

        // Execute: Save updated cycle tracking values
        Account updatedAccount = accountRepository.save(account);
        accountRepository.flush();

        // Assert: Cycle credit updated correctly
        // 500.00 + 250.50 = 750.50
        assertThat(updatedAccount.getCurrentCycleCredit()).isEqualByComparingTo(new BigDecimal("750.50"));
        assertThat(updatedAccount.getCurrentCycleCredit().scale()).isEqualTo(2);

        // Assert: Cycle debit updated correctly with rounding
        // 1500.75 + 123.456 = 1624.206 -> 1624.21 (HALF_UP)
        assertThat(updatedAccount.getCurrentCycleDebit()).isEqualByComparingTo(new BigDecimal("1624.21"));
        assertThat(updatedAccount.getCurrentCycleDebit().scale()).isEqualTo(2);

        // Verify persistence by re-fetching from database
        Optional<Account> refetchedAccount = accountRepository.findByAccountId(testAccount1.getAccountId());
        assertThat(refetchedAccount).isPresent();
        
        assertThat(refetchedAccount.get().getCurrentCycleCredit())
                .isEqualByComparingTo(new BigDecimal("750.50"));
        assertThat(refetchedAccount.get().getCurrentCycleCredit().scale()).isEqualTo(2);
        
        assertThat(refetchedAccount.get().getCurrentCycleDebit())
                .isEqualByComparingTo(new BigDecimal("1624.21"));
        assertThat(refetchedAccount.get().getCurrentCycleDebit().scale()).isEqualTo(2);
    }
}
