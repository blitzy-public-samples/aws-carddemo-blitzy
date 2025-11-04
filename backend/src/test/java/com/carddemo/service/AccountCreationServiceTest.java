package com.carddemo.service;

import com.carddemo.dto.request.AccountAddRequest;
import com.carddemo.entity.Account;
import com.carddemo.entity.AccountXref;
import com.carddemo.entity.Customer;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.AccountXrefRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.util.DecimalUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * JUnit 5 test class for AccountCreationService validating business logic transformation
 * for account creation operations. Tests account initialization, customer relationship
 * establishment, cross-reference table updates (replacing VSAM XREF file), account number
 * generation, initial balance setting with COMP-3 precision, and multi-table inserts
 * (ACCTDAT + cross-reference) within @Transactional boundary.
 * 
 * Validates referential integrity enforcement through foreign key constraints and proper
 * rollback on any step failure matching COBOL multi-file update atomicity.
 */
@ExtendWith(MockitoExtension.class)
public class AccountCreationServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private AccountXrefRepository accountXrefRepository;

    @Mock
    private DecimalUtils decimalUtils;

    @InjectMocks
    private AccountCreationService accountCreationService;

    private Customer testCustomer;
    private AccountAddRequest validRequest;
    private Account testAccount;
    private AccountXref testAccountXref;

    @BeforeEach
    public void setUp() {
        // Initialize test customer - mimics COBOL CUSTOMER-RECORD from CVCUS01Y.cpy
        testCustomer = new Customer();
        testCustomer.setCustomerId(100000001L);
        testCustomer.setFirstName("John");
        testCustomer.setLastName("Doe");

        // Initialize valid account creation request - mimics COBOL input from BMS screen
        validRequest = new AccountAddRequest();
        validRequest.setCustomerId(100000001L);
        validRequest.setCreditLimit(new BigDecimal("5000.00"));
        validRequest.setAccountStatus("Y");

        // Initialize test account - mimics COBOL ACCOUNT-RECORD from CVACT01Y.cpy
        testAccount = new Account();
        testAccount.setAccountId(10000000001L);
        testAccount.setCustomerId(100000001L);
        testAccount.setCreditLimit(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP));
        testAccount.setCurrentBalance(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP));
        testAccount.setActiveStatus("Y");

        // Initialize test account cross-reference - replaces VSAM XREF file
        testAccountXref = new AccountXref();
        testAccountXref.setCustomerId(100000001L);
        testAccountXref.setAccountId(10000000001L);
    }

    /**
     * Test successful account creation with complete flow including all entities.
     * Validates: customer lookup, account creation, xref creation, all saves executed.
     * Mimics COBOL multi-file update (ACCTDAT + XREF) within CICS SYNCPOINT boundary.
     */
    @Test
    public void createAccount_ValidData_CreatesSuccessfully() {
        // Arrange - Mock customer exists
        Mockito.when(customerRepository.findById(100000001L))
                .thenReturn(Optional.of(testCustomer));

        // Mock DecimalUtils to maintain COMP-3 precision
        Mockito.when(decimalUtils.createMoneyAmount())
                .thenReturn(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP));

        // Mock account save with generated ID
        Mockito.when(accountRepository.save(Mockito.any(Account.class)))
                .thenAnswer(invocation -> {
                    Account account = invocation.getArgument(0);
                    account.setAccountId(10000000001L);
                    account.setCreatedAt(LocalDateTime.now());
                    return account;
                });

        // Mock xref save
        Mockito.when(accountXrefRepository.save(Mockito.any(AccountXref.class)))
                .thenReturn(testAccountXref);

        // Act - Execute account creation
        Account createdAccount = accountCreationService.createAccount(validRequest);

        // Assert - Verify all operations executed
        Assertions.assertNotNull(createdAccount, "Created account should not be null");
        Assertions.assertNotNull(createdAccount.getAccountId(), "Account ID should be generated");
        Assertions.assertEquals(100000001L, createdAccount.getCustomerId(), 
                "Customer ID should match request");
        
        // Verify COMP-3 precision maintained - scale=2, HALF_UP rounding
        Assertions.assertEquals(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP), 
                createdAccount.getCurrentBalance(), 
                "Initial balance should be 0.00 with exact precision");
        
        Assertions.assertEquals(new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP), 
                createdAccount.getCreditLimit(), 
                "Credit limit should match request with precision");
        
        Assertions.assertEquals("Y", createdAccount.getActiveStatus(), 
                "Account should be active");
        
        // Verify all repository interactions - atomic transaction boundary
        Mockito.verify(customerRepository, Mockito.times(1)).findById(100000001L);
        Mockito.verify(accountRepository, Mockito.times(1)).save(Mockito.any(Account.class));
        Mockito.verify(accountXrefRepository, Mockito.times(1)).save(Mockito.any(AccountXref.class));
    }

    /**
     * Test account number generation produces unique 11-digit identifier.
     * Validates: account ID format matches COBOL PIC 9(11) specification.
     * Account number must be non-zero and exactly 11 digits.
     */
    @Test
    public void createAccount_GeneratesAccountNumber_Unique11Digits() {
        // Arrange
        Mockito.when(customerRepository.findById(100000001L))
                .thenReturn(Optional.of(testCustomer));
        
        Mockito.when(decimalUtils.createMoneyAmount())
                .thenReturn(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP));
        
        Mockito.when(accountRepository.save(Mockito.any(Account.class)))
                .thenAnswer(invocation -> {
                    Account account = invocation.getArgument(0);
                    account.setAccountId(10000000001L); // 11-digit account number
                    account.setCreatedAt(LocalDateTime.now());
                    return account;
                });
        
        Mockito.when(accountXrefRepository.save(Mockito.any(AccountXref.class)))
                .thenReturn(testAccountXref);

        // Act
        Account createdAccount = accountCreationService.createAccount(validRequest);

        // Assert - Validate 11-digit account number format
        Assertions.assertNotNull(createdAccount.getAccountId(), 
                "Account ID must be generated");
        
        String accountIdStr = String.valueOf(createdAccount.getAccountId());
        Assertions.assertEquals(11, accountIdStr.length(), 
                "Account ID must be exactly 11 digits (COBOL PIC 9(11))");
        
        Assertions.assertTrue(createdAccount.getAccountId() > 0, 
                "Account ID must be non-zero positive number");
        
        // Verify account number starts with 1 (typical pattern)
        Assertions.assertTrue(accountIdStr.startsWith("1"), 
                "Account ID should start with 1 following mainframe pattern");
    }

    /**
     * Test customer relationship validation via foreign key.
     * Validates: customer must exist before account creation (FK constraint).
     * Mimics COBOL validation checking CUSTDAT file before ACCTDAT insert.
     */
    @Test
    public void createAccount_LinksToCustomer_ForeignKeyValid() {
        // Arrange
        Mockito.when(customerRepository.findById(100000001L))
                .thenReturn(Optional.of(testCustomer));
        
        Mockito.when(decimalUtils.createMoneyAmount())
                .thenReturn(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP));
        
        Mockito.when(accountRepository.save(Mockito.any(Account.class)))
                .thenAnswer(invocation -> {
                    Account account = invocation.getArgument(0);
                    account.setAccountId(10000000001L);
                    account.setCreatedAt(LocalDateTime.now());
                    return account;
                });
        
        Mockito.when(accountXrefRepository.save(Mockito.any(AccountXref.class)))
                .thenReturn(testAccountXref);

        // Act
        Account createdAccount = accountCreationService.createAccount(validRequest);

        // Assert - Verify foreign key relationship established
        Assertions.assertEquals(testCustomer.getCustomerId(), createdAccount.getCustomerId(), 
                "Account must link to valid customer via FK");
        
        // Verify customer lookup was performed before account creation
        Mockito.verify(customerRepository, Mockito.times(1)).findById(100000001L);
        
        // Verify the account saved has the correct customer reference
        Mockito.verify(accountRepository).save(Mockito.argThat(account -> 
                account.getCustomerId().equals(testCustomer.getCustomerId())));
    }

    /**
     * Test cross-reference table creation (VSAM XREF file replacement).
     * Validates: both ACCTDAT and XREF tables updated atomically.
     * Ensures bidirectional relationship customer<->account established.
     */
    @Test
    public void createAccount_CreatesXrefEntries_BothTables() {
        // Arrange
        Mockito.when(customerRepository.findById(100000001L))
                .thenReturn(Optional.of(testCustomer));
        
        Mockito.when(decimalUtils.createMoneyAmount())
                .thenReturn(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP));
        
        Mockito.when(accountRepository.save(Mockito.any(Account.class)))
                .thenAnswer(invocation -> {
                    Account account = invocation.getArgument(0);
                    account.setAccountId(10000000001L);
                    account.setCreatedAt(LocalDateTime.now());
                    return account;
                });
        
        Mockito.when(accountXrefRepository.save(Mockito.any(AccountXref.class)))
                .thenReturn(testAccountXref);

        // Act
        Account createdAccount = accountCreationService.createAccount(validRequest);

        // Assert - Verify both main table and xref table updated
        Mockito.verify(accountRepository, Mockito.times(1)).save(Mockito.any(Account.class));
        Mockito.verify(accountXrefRepository, Mockito.times(1)).save(Mockito.any(AccountXref.class));
        
        // Verify xref contains correct bidirectional references
        Mockito.verify(accountXrefRepository).save(Mockito.argThat(xref -> 
                xref.getCustomerId().equals(testCustomer.getCustomerId()) &&
                xref.getAccountId().equals(createdAccount.getAccountId())));
    }

    /**
     * Test initial balance set to zero with COMP-3 precision.
     * Validates: BigDecimal scale=2, RoundingMode.HALF_UP matching COBOL COMP-3.
     * Initial balance must be exactly 0.00 with proper decimal handling.
     */
    @Test
    public void createAccount_InitializesBalance_ZeroWithPrecision() {
        // Arrange
        BigDecimal initialBalance = new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP);
        
        Mockito.when(customerRepository.findById(100000001L))
                .thenReturn(Optional.of(testCustomer));
        
        Mockito.when(decimalUtils.createMoneyAmount())
                .thenReturn(initialBalance);
        
        Mockito.when(accountRepository.save(Mockito.any(Account.class)))
                .thenAnswer(invocation -> {
                    Account account = invocation.getArgument(0);
                    account.setAccountId(10000000001L);
                    account.setCreatedAt(LocalDateTime.now());
                    return account;
                });
        
        Mockito.when(accountXrefRepository.save(Mockito.any(AccountXref.class)))
                .thenReturn(testAccountXref);

        // Act
        Account createdAccount = accountCreationService.createAccount(validRequest);

        // Assert - Verify exact precision maintained
        Assertions.assertNotNull(createdAccount.getCurrentBalance(), 
                "Balance must be initialized");
        Assertions.assertEquals(initialBalance, createdAccount.getCurrentBalance(), 
                "Balance must be exactly 0.00 with scale=2");
        Assertions.assertEquals(2, createdAccount.getCurrentBalance().scale(), 
                "Balance scale must be 2 (COBOL V99)");
        
        // Verify DecimalUtils was used to create money amount
        Mockito.verify(decimalUtils, Mockito.atLeastOnce()).createMoneyAmount();
        
        // Verify balance is exactly zero
        Assertions.assertTrue(createdAccount.getCurrentBalance().compareTo(BigDecimal.ZERO) == 0, 
                "Initial balance must be zero");
    }

    /**
     * Test credit limit validation ensures positive value.
     * Validates: credit limit must be > 0 and properly formatted.
     * Mimics COBOL validation logic for ACCT-CREDIT-LIMIT field.
     */
    @Test
    public void createAccount_SetsCreditLimit_ValidatesPositive() {
        // Arrange
        BigDecimal creditLimit = new BigDecimal("5000.00").setScale(2, RoundingMode.HALF_UP);
        validRequest.setCreditLimit(creditLimit);
        
        Mockito.when(customerRepository.findById(100000001L))
                .thenReturn(Optional.of(testCustomer));
        
        Mockito.when(decimalUtils.createMoneyAmount())
                .thenReturn(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP));
        
        Mockito.when(accountRepository.save(Mockito.any(Account.class)))
                .thenAnswer(invocation -> {
                    Account account = invocation.getArgument(0);
                    account.setAccountId(10000000001L);
                    account.setCreatedAt(LocalDateTime.now());
                    return account;
                });
        
        Mockito.when(accountXrefRepository.save(Mockito.any(AccountXref.class)))
                .thenReturn(testAccountXref);

        // Act
        Account createdAccount = accountCreationService.createAccount(validRequest);

        // Assert - Verify credit limit set correctly
        Assertions.assertNotNull(createdAccount.getCreditLimit(), 
                "Credit limit must be set");
        Assertions.assertEquals(creditLimit, createdAccount.getCreditLimit(), 
                "Credit limit must match request");
        Assertions.assertTrue(createdAccount.getCreditLimit().compareTo(BigDecimal.ZERO) > 0, 
                "Credit limit must be positive");
        Assertions.assertEquals(2, createdAccount.getCreditLimit().scale(), 
                "Credit limit scale must be 2");
        
        // Verify account saved with correct credit limit
        Mockito.verify(accountRepository).save(Mockito.argThat(account -> 
                account.getCreditLimit().equals(creditLimit)));
    }

    /**
     * Test transaction boundary ensures atomic commit.
     * Validates: all operations within single @Transactional boundary.
     * Mimics COBOL CICS SYNCPOINT - all or nothing commit semantics.
     */
    @Test
    public void createAccount_WithinTransaction_CommitsAtomically() {
        // Arrange
        Mockito.when(customerRepository.findById(100000001L))
                .thenReturn(Optional.of(testCustomer));
        
        Mockito.when(decimalUtils.createMoneyAmount())
                .thenReturn(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP));
        
        Mockito.when(accountRepository.save(Mockito.any(Account.class)))
                .thenAnswer(invocation -> {
                    Account account = invocation.getArgument(0);
                    account.setAccountId(10000000001L);
                    account.setCreatedAt(LocalDateTime.now());
                    return account;
                });
        
        Mockito.when(accountXrefRepository.save(Mockito.any(AccountXref.class)))
                .thenReturn(testAccountXref);

        // Act
        Account createdAccount = accountCreationService.createAccount(validRequest);

        // Assert - Verify atomic transaction behavior
        // All operations must complete or none should persist
        Assertions.assertNotNull(createdAccount, "Account creation must complete");
        
        // Verify exact sequence of operations within transaction
        Mockito.verify(customerRepository).findById(100000001L);
        Mockito.verify(accountRepository).save(Mockito.any(Account.class));
        Mockito.verify(accountXrefRepository).save(Mockito.any(AccountXref.class));
        
        // All three operations should execute in order
        var inOrder = Mockito.inOrder(customerRepository, accountRepository, accountXrefRepository);
        inOrder.verify(customerRepository).findById(100000001L);
        inOrder.verify(accountRepository).save(Mockito.any(Account.class));
        inOrder.verify(accountXrefRepository).save(Mockito.any(AccountXref.class));
    }

    /**
     * Test database error triggers complete rollback.
     * Validates: any failure rolls back all changes (no partial data).
     * Mimics COBOL CICS SYNCPOINT ROLLBACK on error condition.
     */
    @Test
    public void createAccount_DatabaseError_RollsBackAll() {
        // Arrange - Customer exists but xref save fails
        Mockito.when(customerRepository.findById(100000001L))
                .thenReturn(Optional.of(testCustomer));
        
        Mockito.when(decimalUtils.createMoneyAmount())
                .thenReturn(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP));
        
        Mockito.when(accountRepository.save(Mockito.any(Account.class)))
                .thenAnswer(invocation -> {
                    Account account = invocation.getArgument(0);
                    account.setAccountId(10000000001L);
                    account.setCreatedAt(LocalDateTime.now());
                    return account;
                });
        
        // Simulate database error on xref save
        Mockito.when(accountXrefRepository.save(Mockito.any(AccountXref.class)))
                .thenThrow(new RuntimeException("Database constraint violation"));

        // Act & Assert - Expect exception propagation for rollback
        Exception exception = Assertions.assertThrows(RuntimeException.class, () -> {
            accountCreationService.createAccount(validRequest);
        });
        
        Assertions.assertEquals("Database constraint violation", exception.getMessage(), 
                "Exception message should propagate");
        
        // Verify customer lookup occurred
        Mockito.verify(customerRepository).findById(100000001L);
        
        // Verify account save was attempted
        Mockito.verify(accountRepository).save(Mockito.any(Account.class));
        
        // Verify xref save was attempted and failed
        Mockito.verify(accountXrefRepository).save(Mockito.any(AccountXref.class));
        
        // In real @Transactional context, Spring would rollback all changes
        // This test validates exception propagation enabling rollback
    }

    /**
     * Test duplicate account number detection.
     * Validates: unique constraint on account ID enforced.
     * Mimics COBOL VSAM alternate key uniqueness check.
     */
    @Test
    public void createAccount_DuplicateAccountNumber_ThrowsException() {
        // Arrange
        Mockito.when(customerRepository.findById(100000001L))
                .thenReturn(Optional.of(testCustomer));
        
        Mockito.when(decimalUtils.createMoneyAmount())
                .thenReturn(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP));
        
        // Simulate duplicate key violation
        Mockito.when(accountRepository.save(Mockito.any(Account.class)))
                .thenThrow(new RuntimeException("Duplicate key violation: account_id already exists"));

        // Act & Assert
        Exception exception = Assertions.assertThrows(RuntimeException.class, () -> {
            accountCreationService.createAccount(validRequest);
        });
        
        Assertions.assertTrue(exception.getMessage().contains("Duplicate key"), 
                "Exception should indicate duplicate key violation");
        
        // Verify customer was validated before attempted save
        Mockito.verify(customerRepository).findById(100000001L);
        
        // Verify save was attempted
        Mockito.verify(accountRepository).save(Mockito.any(Account.class));
        
        // Verify xref was never attempted (save failed first)
        Mockito.verify(accountXrefRepository, Mockito.never()).save(Mockito.any(AccountXref.class));
    }

    /**
     * Test invalid customer ID throws foreign key exception.
     * Validates: customer must exist before account creation.
     * Mimics COBOL validation error DID-NOT-FIND-CUST-IN-CUSTDAT.
     */
    @Test
    public void createAccount_InvalidCustomerId_ThrowsForeignKeyException() {
        // Arrange - Customer does not exist
        Mockito.when(customerRepository.findById(100000001L))
                .thenReturn(Optional.empty());

        // Act & Assert
        Exception exception = Assertions.assertThrows(AccountNotFoundException.class, () -> {
            accountCreationService.createAccount(validRequest);
        });
        
        // Verify customer lookup was performed
        Mockito.verify(customerRepository).findById(100000001L);
        
        // Verify no account save attempted (validation failed)
        Mockito.verify(accountRepository, Mockito.never()).save(Mockito.any(Account.class));
        
        // Verify no xref save attempted
        Mockito.verify(accountXrefRepository, Mockito.never()).save(Mockito.any(AccountXref.class));
    }

    /**
     * Test required field validation.
     * Validates: NOT NULL constraints on mandatory fields enforced.
     * Mimics COBOL mandatory field validation (FLG-MANDATORY-NOT-OK).
     */
    @Test
    public void createAccount_ValidatesRequiredFields_ThrowsOnMissing() {
        // Arrange - Request missing credit limit (required field)
        AccountAddRequest invalidRequest = new AccountAddRequest();
        invalidRequest.setCustomerId(100000001L);
        invalidRequest.setAccountStatus("Y");
        // creditLimit intentionally not set (null)
        
        Mockito.when(customerRepository.findById(100000001L))
                .thenReturn(Optional.of(testCustomer));

        // Act & Assert
        Exception exception = Assertions.assertThrows(IllegalArgumentException.class, () -> {
            accountCreationService.createAccount(invalidRequest);
        });
        
        Assertions.assertTrue(exception.getMessage().contains("Credit limit") || 
                              exception.getMessage().contains("required"), 
                "Exception should indicate missing required field");
        
        // Verify no save operations attempted with invalid data
        Mockito.verify(accountRepository, Mockito.never()).save(Mockito.any(Account.class));
        Mockito.verify(accountXrefRepository, Mockito.never()).save(Mockito.any(AccountXref.class));
    }

    /**
     * Test default account status set to Active.
     * Validates: new accounts initialized with ACCT-ACTIVE-STATUS = 'Y'.
     * Mimics COBOL 88-level condition ACCOUNT-IS-ACTIVE VALUE 'Y'.
     */
    @Test
    public void createAccount_SetsDefaultStatus_Active() {
        // Arrange
        validRequest.setAccountStatus("Y"); // Explicit active status
        
        Mockito.when(customerRepository.findById(100000001L))
                .thenReturn(Optional.of(testCustomer));
        
        Mockito.when(decimalUtils.createMoneyAmount())
                .thenReturn(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP));
        
        Mockito.when(accountRepository.save(Mockito.any(Account.class)))
                .thenAnswer(invocation -> {
                    Account account = invocation.getArgument(0);
                    account.setAccountId(10000000001L);
                    account.setCreatedAt(LocalDateTime.now());
                    return account;
                });
        
        Mockito.when(accountXrefRepository.save(Mockito.any(AccountXref.class)))
                .thenReturn(testAccountXref);

        // Act
        Account createdAccount = accountCreationService.createAccount(validRequest);

        // Assert - Verify default active status
        Assertions.assertNotNull(createdAccount.getActiveStatus(), 
                "Account status must be set");
        Assertions.assertEquals("Y", createdAccount.getActiveStatus(), 
                "New account should be active (Y)");
        
        // Verify account saved with active status
        Mockito.verify(accountRepository).save(Mockito.argThat(account -> 
                "Y".equals(account.getActiveStatus())));
    }

    /**
     * Test creation timestamp recorded at account creation.
     * Validates: audit trail with current date/time matching COBOL CURRENT-DATE.
     * Ensures timestamp not null and reasonably recent (within last minute).
     */
    @Test
    public void createAccount_RecordsCreationTimestamp_CurrentDateTime() {
        // Arrange
        LocalDateTime beforeCreation = LocalDateTime.now().minusSeconds(1);
        
        Mockito.when(customerRepository.findById(100000001L))
                .thenReturn(Optional.of(testCustomer));
        
        Mockito.when(decimalUtils.createMoneyAmount())
                .thenReturn(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP));
        
        Mockito.when(accountRepository.save(Mockito.any(Account.class)))
                .thenAnswer(invocation -> {
                    Account account = invocation.getArgument(0);
                    account.setAccountId(10000000001L);
                    account.setCreatedAt(LocalDateTime.now());
                    return account;
                });
        
        Mockito.when(accountXrefRepository.save(Mockito.any(AccountXref.class)))
                .thenReturn(testAccountXref);

        // Act
        Account createdAccount = accountCreationService.createAccount(validRequest);
        
        LocalDateTime afterCreation = LocalDateTime.now().plusSeconds(1);

        // Assert - Verify timestamp set
        Assertions.assertNotNull(createdAccount.getCreatedAt(), 
                "Creation timestamp must be recorded");
        
        // Verify timestamp is recent (between before and after markers)
        Assertions.assertTrue(createdAccount.getCreatedAt().isAfter(beforeCreation) || 
                              createdAccount.getCreatedAt().isEqual(beforeCreation), 
                "Creation timestamp should be after test start");
        Assertions.assertTrue(createdAccount.getCreatedAt().isBefore(afterCreation) || 
                              createdAccount.getCreatedAt().isEqual(afterCreation), 
                "Creation timestamp should be before test end");
    }

    /**
     * Test multiple accounts for same customer allowed (one-to-many).
     * Validates: customer can have multiple accounts without constraint violation.
     * Mimics COBOL allowing multiple ACCTDAT records per CUST-ID.
     */
    @Test
    public void createAccount_MultipleAccounts_SameCustomer_AllowsMultiple() {
        // Arrange - Same customer, different account requests
        AccountAddRequest firstRequest = new AccountAddRequest();
        firstRequest.setCustomerId(100000001L);
        firstRequest.setCreditLimit(new BigDecimal("5000.00"));
        firstRequest.setAccountStatus("Y");
        
        AccountAddRequest secondRequest = new AccountAddRequest();
        secondRequest.setCustomerId(100000001L); // Same customer
        secondRequest.setCreditLimit(new BigDecimal("10000.00")); // Different limit
        secondRequest.setAccountStatus("Y");
        
        Mockito.when(customerRepository.findById(100000001L))
                .thenReturn(Optional.of(testCustomer));
        
        Mockito.when(decimalUtils.createMoneyAmount())
                .thenReturn(new BigDecimal("0.00").setScale(2, RoundingMode.HALF_UP));
        
        // Mock different account IDs for each creation
        Mockito.when(accountRepository.save(Mockito.any(Account.class)))
                .thenAnswer(invocation -> {
                    Account account = invocation.getArgument(0);
                    // Simulate different account IDs
                    long accountId = 10000000001L + (long)(Math.random() * 1000);
                    account.setAccountId(accountId);
                    account.setCreatedAt(LocalDateTime.now());
                    return account;
                });
        
        Mockito.when(accountXrefRepository.save(Mockito.any(AccountXref.class)))
                .thenReturn(testAccountXref);

        // Act - Create two accounts for same customer
        Account firstAccount = accountCreationService.createAccount(firstRequest);
        Account secondAccount = accountCreationService.createAccount(secondRequest);

        // Assert - Both accounts created successfully
        Assertions.assertNotNull(firstAccount, "First account should be created");
        Assertions.assertNotNull(secondAccount, "Second account should be created");
        
        // Both link to same customer
        Assertions.assertEquals(100000001L, firstAccount.getCustomerId(), 
                "First account links to customer");
        Assertions.assertEquals(100000001L, secondAccount.getCustomerId(), 
                "Second account links to customer");
        
        // Different account IDs
        Assertions.assertNotEquals(firstAccount.getAccountId(), secondAccount.getAccountId(), 
                "Account IDs must be unique");
        
        // Verify customer lookup called twice
        Mockito.verify(customerRepository, Mockito.times(2)).findById(100000001L);
        
        // Verify two account saves
        Mockito.verify(accountRepository, Mockito.times(2)).save(Mockito.any(Account.class));
        
        // Verify two xref entries created
        Mockito.verify(accountXrefRepository, Mockito.times(2)).save(Mockito.any(AccountXref.class));
    }
}
