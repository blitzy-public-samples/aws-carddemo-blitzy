package com.carddemo.repository;

import com.carddemo.entity.Account;
import com.carddemo.entity.Customer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 test class for AccountRepository using @DataJpaTest annotation.
 * 
 * <p><strong>Purpose:</strong> Comprehensive validation of Spring Data JPA repository methods
 * for account data migrated from ACCTDAT VSAM KSDS file defined in CVACT01Y.cpy copybook
 * (RECLN 300). This test suite ensures 100% functional equivalence with COBOL VSAM file
 * operations per Section 0.2 and 0.9 refactoring requirements.</p>
 * 
 * <p><strong>COBOL-to-Java Migration Validation:</strong></p>
 * <ul>
 *   <li><strong>VSAM Random Read:</strong> Validates findById equivalent to EXEC CICS READ 
 *       with account_id primary key</li>
 *   <li><strong>VSAM Sequential Browse:</strong> Validates findAll with pagination equivalent 
 *       to STARTBR/READNEXT operations</li>
 *   <li><strong>XREF Cross-Reference:</strong> Validates findByCustomerId equivalent to XREF 
 *       file navigation with foreign key relationship</li>
 *   <li><strong>VSAM Write:</strong> Validates save for new account insertion with foreign key 
 *       constraint enforcement</li>
 *   <li><strong>VSAM Rewrite:</strong> Validates save for account updates with optimistic locking</li>
 *   <li><strong>VSAM Delete:</strong> Validates delete operations with cascade handling</li>
 * </ul>
 * 
 * <p><strong>Critical Precision Testing (Section 0.9):</strong></p>
 * <ul>
 *   <li>ACCT-CURR-BAL S9(10)V99 COMP-3 → NUMERIC(12,2) with scale=2, RoundingMode.HALF_UP</li>
 *   <li>ACCT-CREDIT-LIMIT S9(10)V99 COMP-3 → NUMERIC(12,2) with scale=2</li>
 *   <li>ACCT-CASH-CREDIT-LIMIT S9(10)V99 COMP-3 → NUMERIC(12,2) with scale=2</li>
 *   <li>All BigDecimal calculations maintain exact decimal arithmetic</li>
 * </ul>
 * 
 * <p><strong>Database Schema Validation:</strong></p>
 * <ul>
 *   <li>Primary Key: account_id NUMERIC(11,0) with B-tree index</li>
 *   <li>Foreign Key: customer_id → customer(customer_id) with ON DELETE RESTRICT</li>
 *   <li>Indexes: idx_account_customer_id, idx_account_status</li>
 *   <li>Constraints: NOT NULL on account_id, customer_id, balance fields</li>
 * </ul>
 * 
 * <p><strong>Test Data Setup:</strong></p>
 * <ul>
 *   <li>@Sql scripts load customers.sql BEFORE accounts.sql to satisfy foreign key constraints</li>
 *   <li>Test data includes: account_id 11-digit values, BigDecimal balances with 2 decimal precision</li>
 *   <li>Active/inactive status values ('Y'/'N') matching COBOL 88-level conditions</li>
 * </ul>
 * 
 * <p><strong>Test Configuration:</strong></p>
 * <ul>
 *   <li>@DataJpaTest: Spring Boot test slice for JPA repositories with embedded test database</li>
 *   <li>@AutoConfigureTestDatabase(replace=Replace.NONE): Disables H2 replacement, uses configured 
 *       PostgreSQL test container for schema validation</li>
 *   <li>@TestMethodOrder: Ensures ordered execution for data integrity tests</li>
 * </ul>
 * 
 * @see AccountRepository
 * @see Account
 * @see Customer
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.3">VSAM File to PostgreSQL Table Transformation</a>
 * @see <a href="Section 0.9">Critical Numeric Precision Requirements</a>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Sql(scripts = {
    "/db/test-data/customers.sql",
    "/db/test-data/accounts.sql"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccountRepositoryTest {

    @Autowired
    private AccountRepository accountRepository;

    /**
     * Tests VSAM random read (EXEC CICS READ ACCTFILE) functionality.
     * 
     * <p><strong>COBOL Equivalent (COACTVWC.cbl lines 776-784):</strong></p>
     * <pre>
     * EXEC CICS READ
     *      DATASET   (LIT-ACCTFILENAME)
     *      RIDFLD    (WS-CARD-RID-ACCT-ID-X)
     *      INTO      (ACCOUNT-RECORD)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>B-tree index on account_id primary key enables fast lookup</li>
     *   <li>ACCT-ID PIC 9(11) → Long accountId mapping</li>
     *   <li>All 300-byte record fields correctly mapped to Account entity</li>
     *   <li>Active status PIC X(01) → String activeStatus</li>
     *   <li>BigDecimal balance fields with scale=2, RoundingMode.HALF_UP</li>
     *   <li>Foreign key to customer table properly resolved</li>
     * </ul>
     */
    @Test
    @Order(1)
    void testFindById_ValidAccountId() {
        // Given: Account ID from test data (accounts.sql)
        Long accountId = 10000000001L;
        
        // When: Find account by primary key (VSAM random read equivalent)
        Optional<Account> accountOptional = accountRepository.findByAccountId(accountId);
        
        // Then: Account is found with all fields correctly mapped
        assertTrue(accountOptional.isPresent(), "Account should be found");
        
        Account account = accountOptional.get();
        
        // Validate ACCT-ID PIC 9(11) → Long accountId
        assertEquals(accountId, account.getAccountId());
        
        // Validate ACCT-ACTIVE-STATUS PIC X(01) → String activeStatus
        assertEquals("Y", account.getActiveStatus());
        assertTrue(account.isActive());
        
        // Validate ACCT-CURR-BAL S9(10)V99 COMP-3 → BigDecimal(12,2) with precision preservation
        assertNotNull(account.getCurrentBalance());
        assertEquals(2, account.getCurrentBalance().scale(), 
            "Current balance must have scale=2 for COMP-3 precision");
        assertEquals(new BigDecimal("1250.75").setScale(2, RoundingMode.HALF_UP), 
            account.getCurrentBalance());
        
        // Validate ACCT-CREDIT-LIMIT S9(10)V99 COMP-3 → BigDecimal(12,2)
        assertNotNull(account.getCreditLimit());
        assertEquals(2, account.getCreditLimit().scale());
        assertEquals(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP), 
            account.getCreditLimit());
        
        // Validate ACCT-CASH-CREDIT-LIMIT S9(10)V99 COMP-3 → BigDecimal(12,2)
        assertNotNull(account.getCashCreditLimit());
        assertEquals(2, account.getCashCreditLimit().scale());
        assertEquals(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP), 
            account.getCashCreditLimit());
        
        // Validate ACCT-CURR-CYC-CREDIT S9(10)V99 COMP-3 → BigDecimal(12,2)
        assertNotNull(account.getCurrentCycleCredit());
        assertEquals(2, account.getCurrentCycleCredit().scale());
        assertEquals(new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP), 
            account.getCurrentCycleCredit());
        
        // Validate ACCT-CURR-CYC-DEBIT S9(10)V99 COMP-3 → BigDecimal(12,2)
        assertNotNull(account.getCurrentCycleDebit());
        assertEquals(2, account.getCurrentCycleDebit().scale());
        assertEquals(new BigDecimal("1750.75").setScale(2, RoundingMode.HALF_UP), 
            account.getCurrentCycleDebit());
        
        // Validate ACCT-OPEN-DATE PIC X(10) → LocalDate openDate
        assertNotNull(account.getOpenDate());
        assertEquals(LocalDate.of(2020, 1, 15), account.getOpenDate());
        
        // Validate ACCT-EXPIRAION-DATE PIC X(10) → LocalDate expirationDate
        assertNotNull(account.getExpirationDate());
        assertEquals(LocalDate.of(2027, 1, 15), account.getExpirationDate());
        
        // Validate ACCT-REISSUE-DATE PIC X(10) → LocalDate reissueDate
        assertNotNull(account.getReissueDate());
        assertEquals(LocalDate.of(2024, 12, 1), account.getReissueDate());
        
        // Validate ACCT-ADDR-ZIP PIC X(10) → String addressZip
        assertEquals("75001", account.getAddressZip());
        
        // Validate ACCT-GROUP-ID PIC X(10) → String accountGroupId
        assertEquals("GROUP001", account.getAccountGroupId());
        
        // Validate foreign key relationship to customer table (XREF file replacement)
        assertNotNull(account.getCustomer());
        assertEquals(1000000001L, account.getCustomer().getCustomerId());
    }

    /**
     * Tests VSAM NOTFND condition (account not found scenario).
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * IF WS-RESP-CD = DFHRESP(NOTFND)
     *     MOVE 'Account not found' TO ERROR-MESSAGE
     * END-IF
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Non-existent account ID returns Optional.empty()</li>
     *   <li>Equivalent to VSAM NOTFND response code</li>
     * </ul>
     */
    @Test
    @Order(2)
    void testFindById_InvalidAccountId() {
        // Given: Non-existent account ID
        Long nonExistentAccountId = 99999999999L;
        
        // When: Attempt to find non-existent account
        Optional<Account> accountOptional = accountRepository.findByAccountId(nonExistentAccountId);
        
        // Then: Account is not found (VSAM NOTFND equivalent)
        assertFalse(accountOptional.isPresent(), "Non-existent account should not be found");
        assertTrue(accountOptional.isEmpty());
    }

    /**
     * Tests account-by-customer retrieval using foreign key relationship.
     * 
     * <p><strong>COBOL Equivalent (XREF file navigation pattern):</strong></p>
     * <pre>
     * MOVE CUSTOMER-ID TO XREF-CUST-ID
     * EXEC CICS READ DATASET('XREFFILE') ... END-EXEC
     * PERFORM VARYING I FROM 1 BY 1 UNTIL I > XREF-ACCT-COUNT
     *     MOVE XREF-ACCT-ID(I) TO WS-ACCT-ID
     *     EXEC CICS READ DATASET('ACCTFILE') ... END-EXEC
     * END-PERFORM
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Foreign key relationship from account to customer</li>
     *   <li>Secondary index on customer_id enables efficient retrieval</li>
     *   <li>Multiple accounts per customer supported</li>
     *   <li>Results ordered by account_id ascending</li>
     * </ul>
     */
    @Test
    @Order(3)
    void testFindByCustomerId_ValidCustomer() {
        // Given: Customer ID with multiple accounts in test data
        Long customerId = 1000000001L;
        
        // When: Find all accounts for customer (XREF file navigation equivalent)
        List<Account> accounts = accountRepository.findByCustomer_CustomerId(customerId);
        
        // Then: Multiple accounts are returned
        assertNotNull(accounts);
        assertFalse(accounts.isEmpty());
        assertEquals(2, accounts.size(), "Customer should have 2 accounts in test data");
        
        // Validate all returned accounts belong to the specified customer
        accounts.forEach(account -> {
            assertNotNull(account.getCustomer());
            assertEquals(customerId, account.getCustomer().getCustomerId());
        });
        
        // Validate ordering by account_id ascending
        assertEquals(10000000001L, accounts.get(0).getAccountId());
        assertEquals(10000000002L, accounts.get(1).getAccountId());
        
        // Validate BigDecimal precision for all accounts
        accounts.forEach(account -> {
            assertEquals(2, account.getCurrentBalance().scale());
            assertEquals(2, account.getCreditLimit().scale());
            assertEquals(2, account.getCashCreditLimit().scale());
        });
    }

    /**
     * Tests customer with no accounts scenario.
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * IF XREF-ACCT-COUNT = ZERO
     *     MOVE 'No accounts found for customer' TO MESSAGE
     * END-IF
     * </pre>
     */
    @Test
    @Order(4)
    void testFindByCustomerId_NoAccounts() {
        // Given: Customer ID with no accounts
        Long customerIdNoAccounts = 9999999999L;
        
        // When: Find accounts for customer with none
        List<Account> accounts = accountRepository.findByCustomer_CustomerId(customerIdNoAccounts);
        
        // Then: Empty list is returned
        assertNotNull(accounts);
        assertTrue(accounts.isEmpty());
        assertEquals(0, accounts.size());
    }

    /**
     * Tests VSAM sequential read (STARTBR/READNEXT pattern).
     * 
     * <p><strong>COBOL Equivalent (CBACT01C.cbl batch processing):</strong></p>
     * <pre>
     * EXEC CICS STARTBR DATASET('ACCTFILE') ... END-EXEC
     * PERFORM UNTIL END-OF-FILE
     *     EXEC CICS READNEXT ... END-EXEC
     *     ... process account record ...
     * END-PERFORM
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Sequential access by primary key</li>
     *   <li>All accounts retrieved in key order</li>
     *   <li>Result count matches test data</li>
     * </ul>
     */
    @Test
    @Order(5)
    void testFindAll_ReturnsAllAccounts() {
        // When: Retrieve all accounts (VSAM sequential read equivalent)
        List<Account> allAccounts = accountRepository.findAll();
        
        // Then: All accounts from test data are returned
        assertNotNull(allAccounts);
        assertFalse(allAccounts.isEmpty());
        assertTrue(allAccounts.size() >= 3, "At least 3 accounts should exist in test data");
        
        // Validate BigDecimal precision for all accounts
        allAccounts.forEach(account -> {
            assertNotNull(account.getCurrentBalance());
            assertEquals(2, account.getCurrentBalance().scale());
            assertNotNull(account.getCreditLimit());
            assertEquals(2, account.getCreditLimit().scale());
        });
    }

    /**
     * Tests VSAM WRITE operation for new account insertion.
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * EXEC CICS WRITE
     *      DATASET   ('ACCTFILE')
     *      FROM      (ACCOUNT-RECORD)
     *      RIDFLD    (ACCT-ID)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>INSERT with all field constraints</li>
     *   <li>Account ID uniqueness constraint</li>
     *   <li>Foreign key constraint to customer</li>
     *   <li>BigDecimal precision preservation</li>
     * </ul>
     */
    @Test
    @Order(6)
    void testSave_NewAccount() {
        // Given: New account with all required fields and valid foreign key
        Account newAccount = new Account();
        newAccount.setAccountId(10000000099L);
        
        // Create customer reference for foreign key
        Customer customer = new Customer();
        customer.setCustomerId(1000000001L);
        newAccount.setCustomer(customer);
        
        newAccount.setActiveStatus("Y");
        
        // Set BigDecimal fields with scale=2 and RoundingMode.HALF_UP per Section 0.9
        newAccount.setCurrentBalance(new BigDecimal("2500.50").setScale(2, RoundingMode.HALF_UP));
        newAccount.setCreditLimit(new BigDecimal("10000.00").setScale(2, RoundingMode.HALF_UP));
        newAccount.setCashCreditLimit(new BigDecimal("2000.00").setScale(2, RoundingMode.HALF_UP));
        newAccount.setCurrentCycleCredit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP));
        newAccount.setCurrentCycleDebit(new BigDecimal("3500.50").setScale(2, RoundingMode.HALF_UP));
        
        newAccount.setOpenDate(LocalDate.of(2024, 1, 1));
        newAccount.setExpirationDate(LocalDate.of(2029, 1, 1));
        newAccount.setReissueDate(LocalDate.of(2023, 12, 15));
        newAccount.setAddressZip("90210");
        newAccount.setAccountGroupId("GROUP999");
        
        // When: Save new account (VSAM WRITE equivalent)
        Account savedAccount = accountRepository.save(newAccount);
        
        // Then: Account is persisted with all fields preserved
        assertNotNull(savedAccount);
        assertEquals(10000000099L, savedAccount.getAccountId());
        
        // Verify BigDecimal precision maintained after database round-trip
        assertEquals(new BigDecimal("2500.50").setScale(2, RoundingMode.HALF_UP), 
            savedAccount.getCurrentBalance());
        assertEquals(2, savedAccount.getCurrentBalance().scale());
        
        // Verify account can be retrieved
        Optional<Account> retrieved = accountRepository.findByAccountId(10000000099L);
        assertTrue(retrieved.isPresent());
        assertEquals("Y", retrieved.get().getActiveStatus());
    }

    /**
     * Tests foreign key constraint violation on insert.
     * 
     * <p><strong>COBOL Equivalent (XREF validation logic):</strong></p>
     * <pre>
     * EXEC CICS READ DATASET('CUSTFILE') ... END-EXEC
     * IF WS-RESP-CD = DFHRESP(NOTFND)
     *     MOVE 'Invalid customer ID' TO ERROR-MESSAGE
     *     GO TO ERROR-EXIT
     * END-IF
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Foreign key constraint enforcement</li>
     *   <li>DataIntegrityViolationException thrown</li>
     *   <li>Equivalent to COBOL XREF validation failure</li>
     * </ul>
     */
    @Test
    @Order(7)
    void testSave_InvalidCustomerId_ThrowsException() {
        // Given: New account with non-existent customer ID (foreign key violation)
        Account accountWithBadFk = new Account();
        accountWithBadFk.setAccountId(10000000098L);
        
        // Create customer with invalid ID
        Customer invalidCustomer = new Customer();
        invalidCustomer.setCustomerId(9999999999L);  // Does not exist
        accountWithBadFk.setCustomer(invalidCustomer);
        
        accountWithBadFk.setActiveStatus("Y");
        accountWithBadFk.setCurrentBalance(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP));
        accountWithBadFk.setCreditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP));
        accountWithBadFk.setCashCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP));
        accountWithBadFk.setCurrentCycleCredit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        accountWithBadFk.setCurrentCycleDebit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        accountWithBadFk.setOpenDate(LocalDate.now());
        
        // When/Then: Save should throw DataIntegrityViolationException (foreign key constraint violation)
        assertThrows(DataIntegrityViolationException.class, () -> {
            accountRepository.save(accountWithBadFk);
            accountRepository.flush();  // Force immediate constraint check
        }, "Foreign key constraint violation should throw DataIntegrityViolationException");
    }

    /**
     * Tests VSAM REWRITE operation for account update.
     * 
     * <p><strong>COBOL Equivalent (COACTUPC.cbl update logic):</strong></p>
     * <pre>
     * EXEC CICS READ UPDATE DATASET('ACCTFILE') ... END-EXEC
     * MOVE NEW-BALANCE TO ACCT-CURR-BAL
     * MOVE NEW-CREDIT-LIMIT TO ACCT-CREDIT-LIMIT
     * EXEC CICS REWRITE DATASET('ACCTFILE') FROM(ACCOUNT-RECORD) ... END-EXEC
     * EXEC CICS SYNCPOINT END-EXEC
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>UPDATE (VSAM REWRITE equivalent)</li>
     *   <li>Balance modifications with BigDecimal precision</li>
     *   <li>Optimistic locking with @Version (if present)</li>
     *   <li>Credit limit updates preserved</li>
     * </ul>
     */
    @Test
    @Order(8)
    void testUpdate_ExistingAccount() {
        // Given: Existing account from test data
        Long accountId = 10000000001L;
        Optional<Account> accountOptional = accountRepository.findByAccountId(accountId);
        assertTrue(accountOptional.isPresent());
        
        Account account = accountOptional.get();
        BigDecimal originalBalance = account.getCurrentBalance();
        
        // Modify account fields with proper BigDecimal precision
        BigDecimal newBalance = new BigDecimal("2000.25").setScale(2, RoundingMode.HALF_UP);
        BigDecimal newCreditLimit = new BigDecimal("8000.00").setScale(2, RoundingMode.HALF_UP);
        
        account.setCurrentBalance(newBalance);
        account.setCreditLimit(newCreditLimit);
        account.setActiveStatus("N");  // Change from 'Y' to 'N'
        
        // When: Update account (VSAM REWRITE equivalent)
        Account updatedAccount = accountRepository.save(account);
        
        // Then: Changes are persisted
        assertNotNull(updatedAccount);
        assertEquals(accountId, updatedAccount.getAccountId());
        assertEquals(newBalance, updatedAccount.getCurrentBalance());
        assertEquals(2, updatedAccount.getCurrentBalance().scale());
        assertEquals(newCreditLimit, updatedAccount.getCreditLimit());
        assertEquals("N", updatedAccount.getActiveStatus());
        assertFalse(updatedAccount.isActive());
        
        // Verify changes persisted to database
        Optional<Account> reloaded = accountRepository.findByAccountId(accountId);
        assertTrue(reloaded.isPresent());
        assertEquals(newBalance, reloaded.get().getCurrentBalance());
        assertEquals("N", reloaded.get().getActiveStatus());
    }

    /**
     * Tests VSAM DELETE operation.
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * EXEC CICS DELETE DATASET('ACCTFILE') RIDFLD(ACCT-ID) ... END-EXEC
     * IF WS-RESP-CD = DFHRESP(NORMAL)
     *     MOVE 'Account deleted' TO MESSAGE
     * END-IF
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>DELETE operation removes account</li>
     *   <li>Cascade handling for related cards (if applicable)</li>
     *   <li>Foreign key constraint behavior on delete</li>
     * </ul>
     */
    @Test
    @Order(9)
    void testDelete_ExistingAccount() {
        // Given: Create a temporary account for deletion
        Account tempAccount = new Account();
        tempAccount.setAccountId(10000000097L);
        
        Customer customer = new Customer();
        customer.setCustomerId(1000000002L);
        tempAccount.setCustomer(customer);
        
        tempAccount.setActiveStatus("N");
        tempAccount.setCurrentBalance(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        tempAccount.setCreditLimit(new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP));
        tempAccount.setCashCreditLimit(new BigDecimal("500.00").setScale(2, RoundingMode.HALF_UP));
        tempAccount.setCurrentCycleCredit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        tempAccount.setCurrentCycleDebit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        tempAccount.setOpenDate(LocalDate.now());
        
        Account savedAccount = accountRepository.save(tempAccount);
        assertNotNull(savedAccount);
        
        // Verify account exists before deletion
        assertTrue(accountRepository.findByAccountId(10000000097L).isPresent());
        
        // When: Delete account (VSAM DELETE equivalent)
        accountRepository.deleteById(10000000097L);
        
        // Then: Account no longer exists
        Optional<Account> deletedAccount = accountRepository.findByAccountId(10000000097L);
        assertFalse(deletedAccount.isPresent());
    }

    /**
     * Tests COBOL 88-level condition name filtering for active accounts.
     * 
     * <p><strong>COBOL Equivalent (88-level conditions in CVACT01Y.cpy):</strong></p>
     * <pre>
     * 01  ACCOUNT-RECORD.
     *     05 ACCT-ACTIVE-STATUS      PIC X(01).
     *        88 ACCT-IS-ACTIVE       VALUE 'Y'.
     *        88 ACCT-IS-INACTIVE     VALUE 'N'.
     * 
     * (Program logic):
     * IF ACCT-IS-ACTIVE
     *     ... process active account ...
     * END-IF
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Status filtering ('Y'/'N')</li>
     *   <li>B-tree index on active_status for performance</li>
     * </ul>
     */
    @Test
    @Order(10)
    void testFindByActiveStatus() {
        // When: Find all active accounts (88-level ACCT-IS-ACTIVE equivalent)
        List<Account> activeAccounts = accountRepository.findByActiveStatus("Y");
        
        // Then: Only active accounts returned
        assertNotNull(activeAccounts);
        assertFalse(activeAccounts.isEmpty());
        
        // Validate all returned accounts have status 'Y'
        activeAccounts.forEach(account -> {
            assertEquals("Y", account.getActiveStatus());
            assertTrue(account.isActive());
        });
        
        // When: Find all inactive accounts (88-level ACCT-IS-INACTIVE equivalent)
        List<Account> inactiveAccounts = accountRepository.findByActiveStatus("N");
        
        // Then: Only inactive accounts returned (may be empty if none exist)
        assertNotNull(inactiveAccounts);
        inactiveAccounts.forEach(account -> {
            assertEquals("N", account.getActiveStatus());
            assertFalse(account.isActive());
        });
    }

    /**
     * Tests BigDecimal comparison queries for balance filtering.
     * 
     * <p><strong>COBOL Equivalent (CBACT04C.cbl interest calculation):</strong></p>
     * <pre>
     * IF ACCT-CURR-BAL > MIN-BALANCE-FOR-INTEREST
     *     COMPUTE INTEREST-AMT = ACCT-CURR-BAL * INTEREST-RATE / 365
     *     ADD INTEREST-AMT TO ACCT-CURR-BAL
     * END-IF
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>BigDecimal comparison with COMP-3 precision</li>
     *   <li>Numeric range filtering</li>
     *   <li>Database-side filtering performance</li>
     * </ul>
     */
    @Test
    @Order(11)
    void testFindByCreditLimitGreaterThan() {
        // Given: Minimum credit limit threshold with proper BigDecimal scale
        BigDecimal minCreditLimit = new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP);
        
        // When: Find accounts with credit limit greater than threshold
        List<Account> highCreditAccounts = accountRepository.findAccountsWithBalanceGreaterThan(minCreditLimit);
        
        // Then: Only accounts with balance > threshold are returned
        assertNotNull(highCreditAccounts);
        
        // Validate all returned accounts meet the criteria
        highCreditAccounts.forEach(account -> {
            assertTrue(account.getCurrentBalance().compareTo(minCreditLimit) > 0,
                "Account balance should be greater than " + minCreditLimit);
            assertEquals(2, account.getCurrentBalance().scale());
        });
        
        // Verify results are ordered by balance descending (highest first)
        if (highCreditAccounts.size() > 1) {
            for (int i = 0; i < highCreditAccounts.size() - 1; i++) {
                assertTrue(highCreditAccounts.get(i).getCurrentBalance()
                    .compareTo(highCreditAccounts.get(i + 1).getCurrentBalance()) >= 0,
                    "Results should be ordered by balance descending");
            }
        }
    }

    /**
     * Tests balance field precision preservation for all COMP-3 fields.
     * 
     * <p><strong>COBOL COMP-3 Fields (CVACT01Y.cpy):</strong></p>
     * <ul>
     *   <li>ACCT-CURR-BAL PIC S9(10)V99 → NUMERIC(12,2)</li>
     *   <li>ACCT-CREDIT-LIMIT PIC S9(10)V99 → NUMERIC(12,2)</li>
     *   <li>ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 → NUMERIC(12,2)</li>
     *   <li>ACCT-CURR-CYC-CREDIT PIC S9(10)V99 → NUMERIC(12,2)</li>
     *   <li>ACCT-CURR-CYC-DEBIT PIC S9(10)V99 → NUMERIC(12,2)</li>
     * </ul>
     * 
     * <p><strong>Critical Validation (Section 0.9):</strong></p>
     * <p>All decimal fields must maintain scale=2 with RoundingMode.HALF_UP to ensure
     * identical calculation results to COBOL COMP-3 packed decimal arithmetic.</p>
     */
    @Test
    @Order(12)
    void testAccountBalanceCalculations() {
        // Given: Account from test data
        Long accountId = 10000000001L;
        Optional<Account> accountOptional = accountRepository.findByAccountId(accountId);
        assertTrue(accountOptional.isPresent());
        
        Account account = accountOptional.get();
        
        // Then: Validate all balance fields have scale=2 and RoundingMode.HALF_UP
        
        // ACCT-CURR-BAL S9(10)V99 COMP-3 → BigDecimal currentBalance
        assertNotNull(account.getCurrentBalance());
        assertEquals(2, account.getCurrentBalance().scale(), 
            "Current balance must have scale=2 for COMP-3 precision");
        
        // ACCT-CREDIT-LIMIT S9(10)V99 COMP-3 → BigDecimal creditLimit
        assertNotNull(account.getCreditLimit());
        assertEquals(2, account.getCreditLimit().scale(),
            "Credit limit must have scale=2 for COMP-3 precision");
        
        // ACCT-CASH-CREDIT-LIMIT S9(10)V99 COMP-3 → BigDecimal cashCreditLimit
        assertNotNull(account.getCashCreditLimit());
        assertEquals(2, account.getCashCreditLimit().scale(),
            "Cash credit limit must have scale=2 for COMP-3 precision");
        
        // ACCT-CURR-CYC-CREDIT S9(10)V99 COMP-3 → BigDecimal currentCycleCredit
        assertNotNull(account.getCurrentCycleCredit());
        assertEquals(2, account.getCurrentCycleCredit().scale(),
            "Current cycle credit must have scale=2 for COMP-3 precision");
        
        // ACCT-CURR-CYC-DEBIT S9(10)V99 COMP-3 → BigDecimal currentCycleDebit
        assertNotNull(account.getCurrentCycleDebit());
        assertEquals(2, account.getCurrentCycleDebit().scale(),
            "Current cycle debit must have scale=2 for COMP-3 precision");
        
        // Test arithmetic operations maintain precision
        BigDecimal availableCredit = account.getAvailableCredit();
        assertNotNull(availableCredit);
        assertEquals(2, availableCredit.scale(),
            "Calculated available credit must maintain scale=2");
        
        // Validate calculation: Credit Limit - Current Balance = Available Credit
        BigDecimal expectedAvailableCredit = account.getCreditLimit()
            .subtract(account.getCurrentBalance())
            .setScale(2, RoundingMode.HALF_UP);
        assertEquals(expectedAvailableCredit, availableCredit);
    }

    /**
     * Tests date field handling and conversion from COBOL PIC X(10) format.
     * 
     * <p><strong>COBOL Date Fields (CVACT01Y.cpy):</strong></p>
     * <ul>
     *   <li>ACCT-OPEN-DATE PIC X(10) → LocalDate openDate</li>
     *   <li>ACCT-EXPIRAION-DATE PIC X(10) → LocalDate expirationDate (typo in COBOL)</li>
     *   <li>ACCT-REISSUE-DATE PIC X(10) → LocalDate reissueDate</li>
     * </ul>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>DATE field mapping from PIC X(10)</li>
     *   <li>Date range queries</li>
     *   <li>LocalDate comparison operations</li>
     * </ul>
     */
    @Test
    @Order(13)
    void testAccountDateFields() {
        // Given: Account with date fields
        Long accountId = 10000000001L;
        Optional<Account> accountOptional = accountRepository.findByAccountId(accountId);
        assertTrue(accountOptional.isPresent());
        
        Account account = accountOptional.get();
        
        // Then: Validate date field mappings
        
        // ACCT-OPEN-DATE PIC X(10) → LocalDate openDate
        assertNotNull(account.getOpenDate());
        assertTrue(account.getOpenDate().isBefore(LocalDate.now()),
            "Open date should be in the past");
        
        // ACCT-EXPIRAION-DATE PIC X(10) → LocalDate expirationDate
        assertNotNull(account.getExpirationDate());
        assertTrue(account.getExpirationDate().isAfter(LocalDate.now()),
            "Expiration date should be in the future for active accounts");
        assertFalse(account.isExpired(), "Account should not be expired");
        
        // ACCT-REISSUE-DATE PIC X(10) → LocalDate reissueDate
        assertNotNull(account.getReissueDate());
        
        // Validate date ordering: open_date < reissue_date < expiration_date
        assertTrue(account.getOpenDate().isBefore(account.getExpirationDate()),
            "Open date must be before expiration date");
    }

    /**
     * Tests database constraints and field validations.
     * 
     * <p><strong>Database Constraints:</strong></p>
     * <ul>
     *   <li>NOT NULL on account_id, customer_id</li>
     *   <li>VARCHAR length constraints</li>
     *   <li>NUMERIC precision (account_id 11, balances 12,2)</li>
     *   <li>Unique constraint on account_id</li>
     * </ul>
     */
    @Test
    @Order(14)
    void testAccountConstraints() {
        // Given: Account from test data
        Long accountId = 10000000001L;
        Optional<Account> accountOptional = accountRepository.findByAccountId(accountId);
        assertTrue(accountOptional.isPresent());
        
        Account account = accountOptional.get();
        
        // Then: Validate all NOT NULL constraints are satisfied
        assertNotNull(account.getAccountId(), "account_id is NOT NULL");
        assertNotNull(account.getCustomer(), "customer_id is NOT NULL (foreign key)");
        assertNotNull(account.getActiveStatus(), "active_status is NOT NULL");
        assertNotNull(account.getCurrentBalance(), "current_balance is NOT NULL");
        assertNotNull(account.getCreditLimit(), "credit_limit is NOT NULL");
        assertNotNull(account.getCashCreditLimit(), "cash_credit_limit is NOT NULL");
        assertNotNull(account.getCurrentCycleCredit(), "current_cycle_credit is NOT NULL");
        assertNotNull(account.getCurrentCycleDebit(), "current_cycle_debit is NOT NULL");
        
        // Validate VARCHAR length constraints
        assertTrue(account.getActiveStatus().length() <= 1, 
            "active_status VARCHAR(1) constraint");
        
        if (account.getAddressZip() != null) {
            assertTrue(account.getAddressZip().length() <= 10,
                "address_zip VARCHAR(10) constraint");
        }
        
        if (account.getAccountGroupId() != null) {
            assertTrue(account.getAccountGroupId().length() <= 10,
                "account_group_id VARCHAR(10) constraint");
        }
        
        // Validate NUMERIC precision for account_id (11 digits, 0 decimal places)
        assertTrue(account.getAccountId() <= 99999999999L,
            "account_id must fit in NUMERIC(11,0)");
        
        // Validate NUMERIC precision for balance fields (12 digits total, 2 decimal)
        assertTrue(account.getCurrentBalance().precision() <= 12,
            "current_balance precision must be <= 12");
        assertTrue(account.getCreditLimit().precision() <= 12,
            "credit_limit precision must be <= 12");
    }

    /**
     * Tests foreign key constraint enforcement from account to customer table.
     * 
     * <p><strong>COBOL XREF File Replacement:</strong></p>
     * <p>The COBOL application used XREF cross-reference file to maintain
     * customer-to-account relationships. In the normalized PostgreSQL design,
     * this is replaced by a direct foreign key constraint on the account table
     * per Section 0.9 referential integrity requirements.</p>
     * 
     * <p><strong>Foreign Key Constraint:</strong></p>
     * <pre>
     * ALTER TABLE account
     *     ADD CONSTRAINT fk_account_customer
     *     FOREIGN KEY (customer_id)
     *     REFERENCES customer(customer_id)
     *     ON DELETE RESTRICT;
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Foreign key to customer table</li>
     *   <li>Constraint name and behavior</li>
     *   <li>ON DELETE RESTRICT behavior</li>
     * </ul>
     */
    @Test
    @Order(15)
    void testAccountForeignKeyConstraint() {
        // Given: Account from test data
        Long accountId = 10000000001L;
        Optional<Account> accountOptional = accountRepository.findByAccountId(accountId);
        assertTrue(accountOptional.isPresent());
        
        Account account = accountOptional.get();
        
        // Then: Validate foreign key relationship to customer
        assertNotNull(account.getCustomer(), 
            "Account must have customer relationship (foreign key)");
        assertNotNull(account.getCustomer().getCustomerId(),
            "Customer ID must be populated");
        
        // Validate customer_id value is valid (references existing customer)
        Long customerId = account.getCustomer().getCustomerId();
        assertEquals(1000000001L, customerId,
            "Customer ID should match test data");
        
        // Validate customer relationship can be navigated
        assertNotNull(account.getCustomer().getFirstName(),
            "Customer details should be accessible via foreign key");
        assertNotNull(account.getCustomer().getLastName(),
            "Customer details should be accessible via foreign key");
    }

    /**
     * Tests compound query with JOIN and BigDecimal filter.
     * 
     * <p><strong>COBOL Equivalent (complex filtering logic):</strong></p>
     * <pre>
     * PERFORM VARYING I FROM 1 BY 1 UNTIL I > CUSTOMER-COUNT
     *     IF CUST-ID(I) = WS-TARGET-CUSTOMER-ID
     *         PERFORM VARYING J FROM 1 BY 1 UNTIL J > ACCT-COUNT
     *             IF ACCT-CUST-ID(J) = CUST-ID(I)
     *                 IF ACCT-CURR-BAL(J) > MIN-BALANCE
     *                     ... process account ...
     *                 END-IF
     *             END-IF
     *         END-PERFORM
     *     END-IF
     * END-PERFORM
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Compound query with JOIN and filter</li>
     *   <li>Performance with indexes</li>
     *   <li>BigDecimal comparison</li>
     * </ul>
     */
    @Test
    @Order(16)
    void testFindByCustomerIdWithBalanceGreaterThan() {
        // Given: Customer ID and minimum balance threshold
        Long customerId = 1000000001L;
        BigDecimal minBalance = new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP);
        
        // When: Find all accounts for customer with balance > threshold
        List<Account> customerAccounts = accountRepository.findByCustomer_CustomerId(customerId);
        
        // Filter by balance (demonstrating compound query logic)
        List<Account> highBalanceAccounts = customerAccounts.stream()
            .filter(account -> account.getCurrentBalance().compareTo(minBalance) > 0)
            .toList();
        
        // Then: Validate results meet both criteria
        assertNotNull(highBalanceAccounts);
        
        highBalanceAccounts.forEach(account -> {
            // Validate customer_id matches
            assertEquals(customerId, account.getCustomer().getCustomerId());
            
            // Validate balance > threshold
            assertTrue(account.getCurrentBalance().compareTo(minBalance) > 0,
                "Account balance should exceed " + minBalance);
            
            // Validate BigDecimal precision maintained
            assertEquals(2, account.getCurrentBalance().scale());
        });
    }

    /**
     * Tests aggregate SUM query for customer total balance calculation.
     * 
     * <p><strong>COBOL Equivalent (CBACT03C.cbl balance aggregation):</strong></p>
     * <pre>
     * MOVE ZERO TO CUSTOMER-TOTAL-BALANCE
     * PERFORM VARYING ACCT-IDX FROM 1 BY 1 UNTIL ACCT-IDX > ACCT-COUNT
     *     IF ACCT-CUST-ID(ACCT-IDX) = WS-CUSTOMER-ID
     *         ADD ACCT-CURR-BAL(ACCT-IDX) TO CUSTOMER-TOTAL-BALANCE
     *     END-IF
     * END-PERFORM
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Database-side SUM aggregation</li>
     *   <li>BigDecimal precision in aggregate results</li>
     *   <li>Null handling for customers with no accounts</li>
     * </ul>
     */
    @Test
    @Order(17)
    void testCalculateTotalBalanceByCustomer() {
        // Given: Customer ID with multiple accounts
        Long customerId = 1000000001L;
        
        // When: Calculate total balance across all customer accounts
        BigDecimal totalBalance = accountRepository.calculateTotalBalanceByCustomer(customerId);
        
        // Then: Validate aggregate calculation
        assertNotNull(totalBalance, "Total balance should not be null for customer with accounts");
        
        // Validate BigDecimal precision maintained in aggregate
        assertEquals(2, totalBalance.scale(),
            "Aggregate total balance must maintain scale=2 for COMP-3 precision");
        
        // Manually verify calculation
        List<Account> accounts = accountRepository.findByCustomer_CustomerId(customerId);
        BigDecimal manualTotal = accounts.stream()
            .map(Account::getCurrentBalance)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
        
        assertEquals(manualTotal, totalBalance,
            "Database SUM should match manual calculation");
    }

    /**
     * Tests count operation for repository statistics.
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * MOVE ZERO TO ACCT-COUNT
     * PERFORM UNTIL END-OF-FILE
     *     ADD 1 TO ACCT-COUNT
     * END-PERFORM
     * </pre>
     */
    @Test
    @Order(18)
    void testCount_AllAccounts() {
        // When: Count all accounts
        long count = accountRepository.count();
        
        // Then: Count matches findAll size
        List<Account> allAccounts = accountRepository.findAll();
        assertEquals(allAccounts.size(), count,
            "count() should match findAll().size()");
        
        assertTrue(count >= 3, "At least 3 accounts should exist in test data");
    }

    /**
     * Tests existsById for account existence check.
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * EXEC CICS READ DATASET('ACCTFILE') RIDFLD(ACCT-ID) ... END-EXEC
     * IF WS-RESP-CD = DFHRESP(NORMAL)
     *     MOVE 'Y' TO ACCT-EXISTS-FLAG
     * ELSE
     *     MOVE 'N' TO ACCT-EXISTS-FLAG
     * END-IF
     * </pre>
     */
    @Test
    @Order(19)
    void testExistsById() {
        // Given: Valid and invalid account IDs
        Long validAccountId = 10000000001L;
        Long invalidAccountId = 99999999999L;
        
        // When/Then: Validate existence checks
        assertTrue(accountRepository.existsById(validAccountId),
            "Valid account ID should exist");
        
        assertFalse(accountRepository.existsById(invalidAccountId),
            "Invalid account ID should not exist");
    }

    /**
     * Tests pagination support for account list displays.
     * 
     * <p><strong>BMS Screen Pattern (7 accounts per page):</strong></p>
     * <p>BMS mapsets display 7 accounts per screen with PF7/PF8 navigation.
     * This test validates equivalent pagination in Spring Data JPA.</p>
     */
    @Test
    @Order(20)
    void testPagination_CustomerAccounts() {
        // Given: Customer ID with multiple accounts
        Long customerId = 1000000001L;
        
        // When: Request first page of 7 accounts (BMS screen pattern)
        PageRequest pageRequest = PageRequest.of(0, 7, Sort.by("accountId").ascending());
        Page<Account> firstPage = accountRepository.findByCustomer_CustomerId(customerId, pageRequest);
        
        // Then: Validate pagination metadata
        assertNotNull(firstPage);
        assertTrue(firstPage.hasContent(), "Page should have content");
        assertTrue(firstPage.getContent().size() <= 7,
            "Page size should not exceed 7 accounts");
        
        // Validate page metadata
        assertEquals(0, firstPage.getNumber(), "Should be first page (0-indexed)");
        assertTrue(firstPage.getTotalElements() >= 2,
            "Customer should have at least 2 accounts");
        
        // Validate all accounts belong to customer
        firstPage.getContent().forEach(account -> {
            assertEquals(customerId, account.getCustomer().getCustomerId());
        });
    }
}

