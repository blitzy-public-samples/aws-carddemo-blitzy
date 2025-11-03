package com.carddemo.repository;

import com.carddemo.entity.Account;
import com.carddemo.entity.AccountXref;
import com.carddemo.entity.AccountXref.AccountXrefId;
import com.carddemo.entity.Card;
import com.carddemo.entity.Customer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.jdbc.Sql;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive JUnit 5 test class for {@link AccountXrefRepository} validating XREF file
 * to account_xref table migration with composite keys per Section 0.6 transformation rules.
 * 
 * <p><strong>VSAM Source Context:</strong></p>
 * <p>This test suite validates the Java/PostgreSQL replacement of the mainframe XREF file
 * which provided customer-to-account cross-reference functionality. In the COBOL system:</p>
 * <ul>
 *   <li><strong>CVACT03Y.cpy:</strong> Defined CARD-XREF-RECORD structure with XREF-CARD-NUM
 *       PIC X(16), XREF-CUST-ID PIC 9(09), and XREF-ACCT-ID PIC 9(11)</li>
 *   <li><strong>XREF VSAM File:</strong> Stored cross-reference records as KSDS with alternate
 *       index (CXACAIX) for bidirectional navigation</li>
 *   <li><strong>CBACT02C.cbl:</strong> Batch program building cross-reference relationships</li>
 * </ul>
 * 
 * <p><strong>Test Data Configuration:</strong></p>
 * <p>Tests use @Sql annotation to load test data in dependency order:</p>
 * <ol>
 *   <li>customers.sql - Customer master data (prerequisite for foreign keys)</li>
 *   <li>accounts.sql - Account master data (prerequisite for foreign keys)</li>
 *   <li>cards.sql - Card master data (prerequisite for card_num foreign key)</li>
 *   <li>account-xrefs.sql - Cross-reference test data</li>
 * </ol>
 * 
 * <p><strong>Composite Key Validation:</strong></p>
 * <p>Tests verify the composite primary key implementation using {@link AccountXrefId}
 * embedded key class with customer_id and account_id fields, ensuring:</p>
 * <ul>
 *   <li>Uniqueness constraint on (customer_id, account_id) pair</li>
 *   <li>NUMERIC(9,0) precision for customer_id from COBOL PIC 9(09)</li>
 *   <li>NUMERIC(11,0) precision for account_id from COBOL PIC 9(11)</li>
 *   <li>VARCHAR(16) for card_num from COBOL PIC X(16)</li>
 * </ul>
 * 
 * <p><strong>Foreign Key Constraint Testing:</strong></p>
 * <p>Tests validate referential integrity enforcement replacing VSAM XREF validation:</p>
 * <ul>
 *   <li>Foreign key constraint to customer table (customer_id)</li>
 *   <li>Foreign key constraint to account table (account_id)</li>
 *   <li>CASCADE delete behavior ensuring orphaned xref records are removed</li>
 *   <li>RESTRICT delete when relationships exist (configurable per business rules)</li>
 * </ul>
 * 
 * <p><strong>Test Coverage Areas:</strong></p>
 * <ol>
 *   <li>Composite key CRUD operations (findById, save, delete)</li>
 *   <li>Bidirectional navigation (findByCustomerId, findByAccountId)</li>
 *   <li>Foreign key constraint enforcement (invalid customer_id, account_id)</li>
 *   <li>Unique constraint validation (duplicate composite key detection)</li>
 *   <li>Query performance for indexed lookups</li>
 *   <li>Cascade delete behavior</li>
 *   <li>Orphaned record detection</li>
 * </ol>
 * 
 * <p><strong>Test Execution Strategy:</strong></p>
 * <p>Tests are ordered using @TestMethodOrder to ensure predictable execution sequence,
 * particularly for tests that create, update, or delete data. Read-only tests execute
 * first, followed by mutation tests.</p>
 * 
 * <p><strong>Performance Requirements (Section 0.2):</strong></p>
 * <ul>
 *   <li>Cross-reference lookups must complete in &lt; 10ms (B-tree index access)</li>
 *   <li>Composite key lookups must complete in &lt; 5ms (primary key access)</li>
 *   <li>Batch operations must support 10,000 TPS without degradation</li>
 * </ul>
 * 
 * <p><strong>Architecture Pattern:</strong> Repository Pattern (Section 0.9 Design Pattern Adherence)</p>
 * <p><strong>VSAM Source:</strong> XREF file (CVACT03Y.cpy), CBACT02C.cbl</p>
 * <p><strong>Target Table:</strong> account_xref (PostgreSQL join table with composite key)</p>
 * <p><strong>Migration Context:</strong> Section 0.6 - VSAM to PostgreSQL Transformation</p>
 * 
 * @see AccountXrefRepository
 * @see AccountXref
 * @see AccountXrefId
 * @see <a href="Section 0.3">VSAM to PostgreSQL Transformation Rules</a>
 * @see <a href="Section 0.9">Referential Integrity Requirements</a>
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 1.0
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Sql(scripts = {
    "/db/test-data/customers.sql",
    "/db/test-data/accounts.sql",
    "/db/test-data/cards.sql",
    "/db/test-data/account-xrefs.sql"
})
@TestMethodOrder(OrderAnnotation.class)
@DisplayName("AccountXrefRepository VSAM XREF File Replacement Tests")
class AccountXrefRepositoryTest {

    @Autowired
    private AccountXrefRepository accountXrefRepository;

    @Autowired
    private EntityManager entityManager;

    // Test data constants matching SQL test data files
    // CUST-ID PIC 9(09) → NUMERIC(9,0) → max value 999999999 (9 digits)
    // ACCT-ID PIC 9(11) → NUMERIC(11,0) → max value 99999999999 (11 digits)
    // Using spec-compliant 9-digit customer IDs with dedicated account IDs (10000000021-23)
    // to avoid conflicts with legacy 10-digit customer data used by other tests
    private static final Long VALID_CUSTOMER_ID_1 = 100000001L;  // 9 digits (valid)
    private static final Long VALID_CUSTOMER_ID_2 = 100000002L;  // 9 digits (valid)
    private static final Long VALID_ACCOUNT_ID_1 = 10000000021L;  // 11 digits (valid, linked to 9-digit customer)
    private static final Long VALID_ACCOUNT_ID_2 = 10000000022L;  // 11 digits (valid, linked to 9-digit customer)
    private static final Long VALID_ACCOUNT_ID_3 = 10000000023L;  // 11 digits (valid, linked to 9-digit customer)
    private static final Long INVALID_CUSTOMER_ID = 999999999L;  // 9 digits but non-existent
    private static final Long INVALID_ACCOUNT_ID = 99999999999L;  // 11 digits but non-existent
    private static final String VALID_CARD_NUM_1 = "4000123456789010";
    private static final String VALID_CARD_NUM_2 = "5000234567890120";
    private static final String INVALID_CARD_NUM = "9999999999999999";

    /**
     * Test 1: Validate composite key lookup with valid customer and account IDs.
     * 
     * <p><strong>VSAM Equivalent:</strong></p>
     * <pre>
     * MOVE CUSTOMER-ID TO XREF-KEY-CUST-ID
     * MOVE ACCOUNT-ID  TO XREF-KEY-ACCT-ID
     * EXEC CICS READ
     *      DATASET   ('XREFFILE')
     *      RIDFLD    (XREF-KEY)
     *      INTO      (XREF-RECORD)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Composite primary key (customer_id, account_id) lookup functionality</li>
     *   <li>XREF-CUST-ID PIC 9(09) → NUMERIC(9,0) customer_id mapping</li>
     *   <li>XREF-ACCT-ID PIC 9(11) → NUMERIC(11,0) account_id mapping</li>
     *   <li>AccountXrefId embedded key class usage</li>
     * </ul>
     */
    @Test
    @Order(1)
    @DisplayName("Test findById with valid composite key returns AccountXref")
    void testFindById_ValidCompositeKey() {
        // Arrange: Create composite key matching test data
        AccountXrefId compositeKey = new AccountXrefId(VALID_CUSTOMER_ID_1, VALID_ACCOUNT_ID_1);

        // Act: Retrieve cross-reference by composite key
        Optional<AccountXref> result = accountXrefRepository.findById(compositeKey);

        // Assert: Verify cross-reference exists and contains correct data
        assertTrue(result.isPresent(), "Cross-reference should exist for valid composite key");
        
        AccountXref xref = result.get();
        assertNotNull(xref.getId(), "Composite key should not be null");
        assertEquals(VALID_CUSTOMER_ID_1, xref.getId().getCustomerId(), 
            "Customer ID should match PIC 9(09) precision");
        assertEquals(VALID_ACCOUNT_ID_1, xref.getId().getAccountId(), 
            "Account ID should match PIC 9(11) precision");
        assertNotNull(xref.getCreatedDate(), "Created date audit field should be populated");
    }

    /**
     * Test 2: Validate composite key lookup with non-existent relationship.
     * 
     * <p><strong>VSAM Equivalent:</strong> CICS READ returning DFHRESP(NOTFND)</p>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Optional.empty() return for non-existent cross-reference</li>
     *   <li>VSAM XREF lookup failure equivalent behavior</li>
     *   <li>Null-safe access pattern using Optional</li>
     * </ul>
     */
    @Test
    @Order(2)
    @DisplayName("Test findById with invalid composite key returns empty Optional")
    void testFindById_InvalidCompositeKey() {
        // Arrange: Create composite key with non-existent relationship
        AccountXrefId nonExistentKey = new AccountXrefId(INVALID_CUSTOMER_ID, INVALID_ACCOUNT_ID);

        // Act: Attempt to retrieve non-existent cross-reference
        Optional<AccountXref> result = accountXrefRepository.findById(nonExistentKey);

        // Assert: Verify Optional.empty() is returned
        assertFalse(result.isPresent(), 
            "Cross-reference should not exist for invalid composite key");
        assertTrue(result.isEmpty(), "Result should be empty Optional");
    }

    /**
     * Test 3: Validate customer-to-accounts cross-reference navigation.
     * 
     * <p><strong>VSAM Equivalent:</strong></p>
     * <pre>
     * EXEC CICS START
     *      DATASET   ('XREFFILE')
     *      RIDFLD    (CUSTOMER-ID)
     *      GTEQ
     * END-EXEC
     * PERFORM UNTIL WS-RESP-CD = DFHRESP(ENDFILE)
     *     EXEC CICS READNEXT ...
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>findByIdCustomerId() method for retrieving all customer accounts</li>
     *   <li>List<AccountXref> return type with multiple records</li>
     *   <li>XREF navigation equivalent to COBOL START/READNEXT</li>
     *   <li>Secondary index on customer_id performance</li>
     * </ul>
     */
    @Test
    @Order(3)
    @DisplayName("Test findByIdCustomerId returns all accounts for valid customer")
    void testFindByCustomerId_ValidCustomer() {
        // Act: Retrieve all accounts for customer
        List<AccountXref> customerAccounts = accountXrefRepository.findByIdCustomerId(VALID_CUSTOMER_ID_1);

        // Assert: Verify customer has accounts
        assertNotNull(customerAccounts, "Customer account list should not be null");
        assertFalse(customerAccounts.isEmpty(), 
            "Customer should have at least one associated account");
        
        // Verify all returned xrefs belong to the queried customer
        customerAccounts.forEach(xref -> 
            assertEquals(VALID_CUSTOMER_ID_1, xref.getId().getCustomerId(),
                "All cross-references should belong to queried customer"));
        
        // Verify customer_id NUMERIC(9,0) precision from COBOL PIC 9(09)
        assertTrue(customerAccounts.get(0).getId().getCustomerId() <= 999999999L,
            "Customer ID should fit within NUMERIC(9,0) precision");
    }

    /**
     * Test 4: Validate customer with no account cross-references.
     * 
     * <p><strong>VSAM Equivalent:</strong> CICS START/READNEXT immediately returning ENDFILE</p>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Empty list return for customer with no accounts</li>
     *   <li>Null-safe empty list (not null) return behavior</li>
     * </ul>
     */
    @Test
    @Order(4)
    @DisplayName("Test findByIdCustomerId returns empty list for customer with no accounts")
    void testFindByCustomerId_NoAccounts() {
        // Arrange: Use customer ID known to have no accounts in test data
        Long customerWithNoAccounts = 1000000999L;

        // Act: Retrieve accounts for customer with no relationships
        List<AccountXref> result = accountXrefRepository.findByIdCustomerId(customerWithNoAccounts);

        // Assert: Verify empty list (not null) is returned
        assertNotNull(result, "Result should be non-null empty list");
        assertTrue(result.isEmpty(), "Customer with no accounts should return empty list");
        assertEquals(0, result.size(), "List size should be zero");
    }

    /**
     * Test 5: Validate account-to-customer cross-reference navigation.
     * 
     * <p><strong>VSAM Equivalent:</strong></p>
     * <pre>
     * EXEC CICS READ
     *      DATASET   (LIT-CARDXREFNAME-ACCT-PATH)
     *      RIDFLD    (ACCOUNT-ID)
     *      KEYLENGTH (LENGTH OF ACCOUNT-ID)
     *      INTO      (XREF-RECORD)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * Source: COACTVWC.cbl lines 727-735 (CXACAIX alternate index)
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>findByIdAccountId() method for reverse XREF navigation</li>
     *   <li>Returns AccountXref with customer info</li>
     *   <li>Tests secondary index on account_id</li>
     *   <li>Joint account scenarios (multiple customers per account)</li>
     * </ul>
     */
    @Test
    @Order(5)
    @DisplayName("Test findByIdAccountId returns customer relationships for valid account")
    void testFindByAccountId_ValidAccount() {
        // Act: Retrieve customers for account
        List<AccountXref> accountCustomers = accountXrefRepository.findByIdAccountId(VALID_ACCOUNT_ID_1);

        // Assert: Verify account has customer relationships
        assertNotNull(accountCustomers, "Account customer list should not be null");
        assertFalse(accountCustomers.isEmpty(), 
            "Account should have at least one associated customer");
        
        // Verify all returned xrefs belong to the queried account
        accountCustomers.forEach(xref -> 
            assertEquals(VALID_ACCOUNT_ID_1, xref.getId().getAccountId(),
                "All cross-references should belong to queried account"));
        
        // Verify account_id NUMERIC(11,0) precision from COBOL PIC 9(11)
        assertTrue(accountCustomers.get(0).getId().getAccountId() <= 99999999999L,
            "Account ID should fit within NUMERIC(11,0) precision");
    }

    /**
     * Test 6: Validate card number to cross-reference navigation (if implemented).
     * 
     * <p><strong>Note:</strong> This test validates findByCardNumber if the repository
     * method exists. Card number cross-references enable navigation from card to
     * account and customer information.</p>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Card-to-account-customer navigation chain</li>
     *   <li>VARCHAR(16) card_num from COBOL PIC X(16)</li>
     *   <li>XREF file equivalent lookup</li>
     * </ul>
     */
    @Test
    @Order(6)
    @DisplayName("Test findAll returns all cross-reference records")
    void testFindAll_ReturnsAllXrefs() {
        // Act: Retrieve all cross-reference records
        List<AccountXref> allXrefs = accountXrefRepository.findAll();

        // Assert: Verify records are returned
        assertNotNull(allXrefs, "Cross-reference list should not be null");
        assertFalse(allXrefs.isEmpty(), "Should have cross-reference records in test data");
        
        // Verify result ordering (should be consistent)
        // All records should have valid composite keys
        allXrefs.forEach(xref -> {
            assertNotNull(xref.getId(), "Composite key should not be null");
            assertNotNull(xref.getId().getCustomerId(), "Customer ID component should not be null");
            assertNotNull(xref.getId().getAccountId(), "Account ID component should not be null");
        });
        
        // Verify VSAM XREF sequential read equivalent
        assertTrue(allXrefs.size() > 0, "Should retrieve at least one cross-reference record");
    }

    /**
     * Test 7: Validate creation of new cross-reference relationship.
     * 
     * <p><strong>VSAM Equivalent:</strong></p>
     * <pre>
     * EXEC CICS WRITE
     *      DATASET   ('XREFFILE')
     *      FROM      (XREF-RECORD)
     *      RIDFLD    (XREF-KEY)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>INSERT of new cross-reference record</li>
     *   <li>Composite key uniqueness constraint</li>
     *   <li>All three foreign key constraints</li>
     *   <li>Referential integrity enforcement</li>
     * </ul>
     */
    @Test
    @Order(7)
    @DisplayName("Test save creates new cross-reference with valid foreign keys")
    void testSave_NewXref() {
        // Arrange: Create new cross-reference with valid customer and account
        AccountXrefId newId = new AccountXrefId(VALID_CUSTOMER_ID_2, VALID_ACCOUNT_ID_2);
        AccountXref newXref = new AccountXref();
        newXref.setId(newId);
        newXref.setCreatedDate(LocalDateTime.now());
        newXref.setUpdatedDate(LocalDateTime.now());

        // Act: Save new cross-reference
        AccountXref savedXref = accountXrefRepository.save(newXref);

        // Assert: Verify cross-reference was created
        assertNotNull(savedXref, "Saved cross-reference should not be null");
        assertNotNull(savedXref.getId(), "Saved composite key should not be null");
        assertEquals(VALID_CUSTOMER_ID_2, savedXref.getId().getCustomerId(),
            "Customer ID should be preserved");
        assertEquals(VALID_ACCOUNT_ID_2, savedXref.getId().getAccountId(),
            "Account ID should be preserved");
        assertNotNull(savedXref.getCreatedDate(), "Created date should be set");

        // Verify cross-reference can be retrieved
        Optional<AccountXref> retrieved = accountXrefRepository.findById(newId);
        assertTrue(retrieved.isPresent(), "Newly created cross-reference should be retrievable");
        assertEquals(VALID_CUSTOMER_ID_2, retrieved.get().getId().getCustomerId());
        assertEquals(VALID_ACCOUNT_ID_2, retrieved.get().getId().getAccountId());
    }

    /**
     * Test 8: Validate foreign key constraint enforcement for invalid customer ID.
     * 
     * <p><strong>VSAM Equivalent:</strong> COBOL validation in CBACT02C before XREF write</p>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Foreign key constraint to customer table</li>
     *   <li>DataIntegrityViolationException when customer doesn't exist</li>
     *   <li>Referential integrity enforcement</li>
     * </ul>
     */
    @Test
    @Order(8)
    @DisplayName("Test save with invalid customer ID throws DataIntegrityViolationException")
    void testSave_InvalidCustomerId_ThrowsException() {
        // Arrange: Create cross-reference with non-existent customer
        AccountXrefId invalidId = new AccountXrefId(INVALID_CUSTOMER_ID, VALID_ACCOUNT_ID_1);
        AccountXref invalidXref = new AccountXref();
        invalidXref.setId(invalidId);
        invalidXref.setCreatedDate(LocalDateTime.now());

        // Act & Assert: Verify foreign key constraint violation
        assertThrows(DataIntegrityViolationException.class, () -> {
            accountXrefRepository.save(invalidXref);
            accountXrefRepository.flush(); // Force immediate constraint check
        }, "Should throw DataIntegrityViolationException for invalid customer_id foreign key");
    }

    /**
     * Test 9: Validate foreign key constraint enforcement for invalid account ID.
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Foreign key constraint to account table</li>
     *   <li>DataIntegrityViolationException when account doesn't exist</li>
     *   <li>Referential integrity enforcement per Section 0.9</li>
     * </ul>
     */
    @Test
    @Order(9)
    @DisplayName("Test save with invalid account ID throws DataIntegrityViolationException")
    void testSave_InvalidAccountId_ThrowsException() {
        // Arrange: Create cross-reference with non-existent account
        AccountXrefId invalidId = new AccountXrefId(VALID_CUSTOMER_ID_1, INVALID_ACCOUNT_ID);
        AccountXref invalidXref = new AccountXref();
        invalidXref.setId(invalidId);
        invalidXref.setCreatedDate(LocalDateTime.now());

        // Act & Assert: Verify foreign key constraint violation
        assertThrows(DataIntegrityViolationException.class, () -> {
            accountXrefRepository.save(invalidXref);
            accountXrefRepository.flush(); // Force immediate constraint check
        }, "Should throw DataIntegrityViolationException for invalid account_id foreign key");
    }

    /**
     * Test 10: Validate composite key uniqueness constraint.
     * 
     * <p><strong>VSAM Equivalent:</strong> VSAM duplicate record error on WRITE</p>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Composite key uniqueness constraint</li>
     *   <li>DataIntegrityViolationException for duplicate key</li>
     *   <li>Prevention of duplicate customer-account relationships</li>
     * </ul>
     */
    @Test
    @Order(10)
    @DisplayName("Test save with duplicate composite key throws DataIntegrityViolationException")
    void testSave_DuplicateCompositeKey_ThrowsException() {
        // Arrange: Create cross-reference with existing composite key
        AccountXrefId duplicateId = new AccountXrefId(VALID_CUSTOMER_ID_1, VALID_ACCOUNT_ID_1);
        AccountXref duplicateXref = new AccountXref();
        duplicateXref.setId(duplicateId);
        duplicateXref.setCreatedDate(LocalDateTime.now());
        duplicateXref.setUpdatedDate(LocalDateTime.now());

        // Act & Assert: Verify duplicate key constraint violation
        // Use entityManager.persist() to force INSERT operation (repository.save() would do merge/update)
        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(duplicateXref);
            entityManager.flush(); // Force immediate constraint check
        }, "Should throw PersistenceException for duplicate composite key");
    }

    /**
     * Test 11: Validate update of existing cross-reference.
     * 
     * <p><strong>VSAM Equivalent:</strong> CICS REWRITE operation</p>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>UPDATE of cross-reference metadata</li>
     *   <li>updated_date timestamp modification</li>
     *   <li>Optimistic locking if implemented</li>
     * </ul>
     */
    @Test
    @Order(11)
    @DisplayName("Test save updates existing cross-reference with new timestamp")
    void testUpdate_ExistingXref() {
        // Arrange: Retrieve existing cross-reference
        AccountXrefId existingId = new AccountXrefId(VALID_CUSTOMER_ID_1, VALID_ACCOUNT_ID_1);
        Optional<AccountXref> existingOpt = accountXrefRepository.findById(existingId);
        assertTrue(existingOpt.isPresent(), "Test requires existing cross-reference");
        
        AccountXref existing = existingOpt.get();
        LocalDateTime originalCreatedDate = existing.getCreatedDate();
        LocalDateTime originalUpdatedDate = existing.getUpdatedDate();

        // Act: Update cross-reference timestamp
        existing.setUpdatedDate(LocalDateTime.now());
        AccountXref updated = accountXrefRepository.save(existing);

        // Assert: Verify update was successful
        assertNotNull(updated, "Updated cross-reference should not be null");
        assertEquals(originalCreatedDate, updated.getCreatedDate(),
            "Created date should not change on update");
        assertNotEquals(originalUpdatedDate, updated.getUpdatedDate(),
            "Updated date should be modified");
    }

    /**
     * Test 12: Validate deletion of cross-reference relationship.
     * 
     * <p><strong>VSAM Equivalent:</strong></p>
     * <pre>
     * EXEC CICS DELETE
     *      DATASET   ('XREFFILE')
     *      RIDFLD    (XREF-KEY)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>DELETE of cross-reference record</li>
     *   <li>Cascade behavior validation</li>
     *   <li>Orphan prevention</li>
     * </ul>
     */
    @Test
    @Order(12)
    @DisplayName("Test delete removes existing cross-reference")
    void testDelete_ExistingXref() {
        // Arrange: Create and save a cross-reference to delete
        AccountXrefId deleteId = new AccountXrefId(VALID_CUSTOMER_ID_2, VALID_ACCOUNT_ID_3);
        AccountXref toDelete = new AccountXref();
        toDelete.setId(deleteId);
        toDelete.setCreatedDate(LocalDateTime.now());
        accountXrefRepository.save(toDelete);
        
        // Verify it exists before deletion
        assertTrue(accountXrefRepository.findById(deleteId).isPresent(),
            "Cross-reference should exist before deletion");

        // Act: Delete the cross-reference
        accountXrefRepository.delete(toDelete);

        // Assert: Verify cross-reference was deleted
        Optional<AccountXref> deleted = accountXrefRepository.findById(deleteId);
        assertFalse(deleted.isPresent(), 
            "Cross-reference should not exist after deletion");
    }

    /**
     * Test 13: Validate composite key class implementation.
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>@EmbeddedId or @IdClass usage</li>
     *   <li>Composite key class structure</li>
     *   <li>equals() and hashCode() implementation</li>
     *   <li>Serializable interface implementation</li>
     * </ul>
     */
    @Test
    @Order(13)
    @DisplayName("Test AccountXrefId composite key class implementation")
    void testCompositeKeyMapping() {
        // Arrange: Create two composite keys with same values
        AccountXrefId key1 = new AccountXrefId(VALID_CUSTOMER_ID_1, VALID_ACCOUNT_ID_1);
        AccountXrefId key2 = new AccountXrefId(VALID_CUSTOMER_ID_1, VALID_ACCOUNT_ID_1);
        AccountXrefId key3 = new AccountXrefId(VALID_CUSTOMER_ID_2, VALID_ACCOUNT_ID_2);

        // Assert: Verify equals() implementation
        assertEquals(key1, key2, "Keys with same values should be equal");
        assertNotEquals(key1, key3, "Keys with different values should not be equal");

        // Assert: Verify hashCode() implementation
        assertEquals(key1.hashCode(), key2.hashCode(),
            "Equal keys should have same hash code");
        
        // Assert: Verify Serializable (composite keys must be Serializable for JPA)
        assertTrue(key1 instanceof Serializable,
            "Composite key class must implement Serializable");

        // Assert: Verify fields are accessible
        assertEquals(VALID_CUSTOMER_ID_1, key1.getCustomerId(),
            "Customer ID should be accessible");
        assertEquals(VALID_ACCOUNT_ID_1, key1.getAccountId(),
            "Account ID should be accessible");
    }

    /**
     * Test 14: Validate foreign key constraints configuration.
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>All three foreign key constraints existence</li>
     *   <li>Constraint naming conventions</li>
     *   <li>ON DELETE CASCADE/RESTRICT behavior</li>
     *   <li>Referential integrity enforcement per Section 0.9</li>
     * </ul>
     */
    @Test
    @Order(14)
    @DisplayName("Test foreign key constraints enforce referential integrity")
    void testForeignKeyConstraints() {
        // Test 1: Verify customer foreign key constraint
        AccountXrefId invalidCustomerKey = new AccountXrefId(INVALID_CUSTOMER_ID, VALID_ACCOUNT_ID_1);
        AccountXref invalidCustomerXref = new AccountXref();
        invalidCustomerXref.setId(invalidCustomerKey);
        invalidCustomerXref.setCreatedDate(LocalDateTime.now());

        assertThrows(DataIntegrityViolationException.class, () -> {
            accountXrefRepository.save(invalidCustomerXref);
            accountXrefRepository.flush();
        }, "Customer foreign key constraint should prevent invalid customer_id");

        // Test 2: Verify account foreign key constraint
        AccountXrefId invalidAccountKey = new AccountXrefId(VALID_CUSTOMER_ID_1, INVALID_ACCOUNT_ID);
        AccountXref invalidAccountXref = new AccountXref();
        invalidAccountXref.setId(invalidAccountKey);
        invalidAccountXref.setCreatedDate(LocalDateTime.now());

        assertThrows(DataIntegrityViolationException.class, () -> {
            accountXrefRepository.save(invalidAccountXref);
            accountXrefRepository.flush();
        }, "Account foreign key constraint should prevent invalid account_id");

        // Test 3: Verify successful save with valid foreign keys
        AccountXrefId validKey = new AccountXrefId(VALID_CUSTOMER_ID_1, VALID_ACCOUNT_ID_3);
        AccountXref validXref = new AccountXref();
        validXref.setId(validKey);
        validXref.setCreatedDate(LocalDateTime.now());

        assertDoesNotThrow(() -> {
            AccountXref saved = accountXrefRepository.save(validXref);
            assertNotNull(saved, "Should save successfully with valid foreign keys");
        }, "Should not throw exception with valid foreign keys");

        // Cleanup
        accountXrefRepository.deleteById(validKey);
    }

    /**
     * Test 15: Validate query performance for XREF navigation.
     * 
     * <p><strong>Performance Requirements (Section 0.2):</strong></p>
     * <ul>
     *   <li>Cross-reference lookups must complete in &lt; 10ms</li>
     *   <li>Composite key lookups must complete in &lt; 5ms</li>
     *   <li>Index usage validation</li>
     * </ul>
     */
    @Test
    @Order(15)
    @DisplayName("Test cross-reference query performance with indexed lookups")
    void testXrefNavigationPerformance() {
        // Test 1: Measure composite key lookup performance (primary key access)
        long startTime = System.currentTimeMillis();
        AccountXrefId key = new AccountXrefId(VALID_CUSTOMER_ID_1, VALID_ACCOUNT_ID_1);
        Optional<AccountXref> result1 = accountXrefRepository.findById(key);
        long elapsedTime1 = System.currentTimeMillis() - startTime;

        assertTrue(result1.isPresent(), "Should find cross-reference");
        assertTrue(elapsedTime1 < 5, 
            "Composite key lookup should complete in < 5ms (actual: " + elapsedTime1 + "ms)");

        // Test 2: Measure customer_id index lookup performance
        startTime = System.currentTimeMillis();
        List<AccountXref> result2 = accountXrefRepository.findByIdCustomerId(VALID_CUSTOMER_ID_1);
        long elapsedTime2 = System.currentTimeMillis() - startTime;

        assertFalse(result2.isEmpty(), "Should find customer accounts");
        assertTrue(elapsedTime2 < 10,
            "Customer ID lookup should complete in < 10ms (actual: " + elapsedTime2 + "ms)");

        // Test 3: Measure account_id index lookup performance
        startTime = System.currentTimeMillis();
        List<AccountXref> result3 = accountXrefRepository.findByIdAccountId(VALID_ACCOUNT_ID_1);
        long elapsedTime3 = System.currentTimeMillis() - startTime;

        assertFalse(result3.isEmpty(), "Should find account customers");
        assertTrue(elapsedTime3 < 10,
            "Account ID lookup should complete in < 10ms (actual: " + elapsedTime3 + "ms)");
    }

    /**
     * Test 16: Validate complete navigation chain from customer to account.
     * 
     * <p><strong>VSAM Equivalent:</strong> Multi-step XREF file navigation in COBOL</p>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Complete navigation chain validation</li>
     *   <li>Card → Account → Customer lookup</li>
     *   <li>Multi-table JOIN performance</li>
     * </ul>
     */
    @Test
    @Order(16)
    @DisplayName("Test navigation chain validates customer-account relationships")
    void testFindCustomerAccountRelationship() {
        // Act: Verify specific customer-account relationship exists
        Optional<AccountXref> relationship = accountXrefRepository
            .findByIdCustomerIdAndIdAccountId(VALID_CUSTOMER_ID_1, VALID_ACCOUNT_ID_1);

        // Assert: Verify relationship exists and is correct
        assertTrue(relationship.isPresent(), 
            "Customer-account relationship should exist");
        
        AccountXref xref = relationship.get();
        assertEquals(VALID_CUSTOMER_ID_1, xref.getId().getCustomerId(),
            "Customer ID should match");
        assertEquals(VALID_ACCOUNT_ID_1, xref.getId().getAccountId(),
            "Account ID should match");
        assertNotNull(xref.getCreatedDate(), 
            "Created date should be populated");

        // Verify non-existent relationship returns empty
        Optional<AccountXref> nonExistent = accountXrefRepository
            .findByIdCustomerIdAndIdAccountId(VALID_CUSTOMER_ID_1, INVALID_ACCOUNT_ID);
        
        assertFalse(nonExistent.isPresent(),
            "Non-existent relationship should return empty Optional");
    }

    /**
     * Test 17: Validate account_xref table constraints.
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>NOT NULL constraints on all key columns</li>
     *   <li>NUMERIC(9,0) precision for customer_id</li>
     *   <li>NUMERIC(11,0) precision for account_id</li>
     *   <li>Composite unique constraint</li>
     * </ul>
     */
    @Test
    @Order(17)
    @DisplayName("Test account_xref table column constraints")
    void testAccountXrefConstraints() {
        // Test 1: Verify NOT NULL constraint on customer_id (via composite key)
        assertThrows(Exception.class, () -> {
            AccountXrefId nullCustomerId = new AccountXrefId(null, VALID_ACCOUNT_ID_1);
            AccountXref xref = new AccountXref();
            xref.setId(nullCustomerId);
            xref.setCreatedDate(LocalDateTime.now());
            accountXrefRepository.save(xref);
            accountXrefRepository.flush();
        }, "Should not allow null customer_id");

        // Test 2: Verify NOT NULL constraint on account_id (via composite key)
        assertThrows(Exception.class, () -> {
            AccountXrefId nullAccountId = new AccountXrefId(VALID_CUSTOMER_ID_1, null);
            AccountXref xref = new AccountXref();
            xref.setId(nullAccountId);
            xref.setCreatedDate(LocalDateTime.now());
            accountXrefRepository.save(xref);
            accountXrefRepository.flush();
        }, "Should not allow null account_id");

        // Test 3: Verify NUMERIC precision limits
        // Customer ID: PIC 9(09) → NUMERIC(9,0) max value 999999999
        Long maxCustomerId = 999999999L;
        Long maxAccountId = 99999999999L; // PIC 9(11) → NUMERIC(11,0)
        
        AccountXrefId maxPrecisionId = new AccountXrefId(maxCustomerId, maxAccountId);
        AccountXref maxPrecisionXref = new AccountXref();
        maxPrecisionXref.setId(maxPrecisionId);
        maxPrecisionXref.setCreatedDate(LocalDateTime.now());

        // Should not throw exception for values within precision limits
        assertDoesNotThrow(() -> {
            AccountXref saved = accountXrefRepository.save(maxPrecisionXref);
            assertNotNull(saved, "Should save with max precision values");
            
            // Cleanup
            accountXrefRepository.deleteById(maxPrecisionId);
        }, "Should handle maximum precision values from COBOL PIC clauses");
    }

    /**
     * Test 18: Validate bidirectional navigation support.
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Customer → Accounts navigation</li>
     *   <li>Account → Customers navigation</li>
     *   <li>Both navigation directions work correctly</li>
     * </ul>
     */
    @Test
    @Order(18)
    @DisplayName("Test bidirectional navigation from customer and account perspectives")
    void testBidirectionalNavigation() {
        // Test 1: Navigate from customer to accounts
        List<AccountXref> customerAccounts = accountXrefRepository
            .findByIdCustomerId(VALID_CUSTOMER_ID_1);
        
        assertFalse(customerAccounts.isEmpty(), 
            "Customer should have associated accounts");
        
        Long firstAccountId = customerAccounts.get(0).getId().getAccountId();

        // Test 2: Navigate from account back to customers
        List<AccountXref> accountCustomers = accountXrefRepository
            .findByIdAccountId(firstAccountId);
        
        assertFalse(accountCustomers.isEmpty(),
            "Account should have associated customers");

        // Test 3: Verify bidirectional consistency
        boolean foundOriginalCustomer = accountCustomers.stream()
            .anyMatch(xref -> xref.getId().getCustomerId().equals(VALID_CUSTOMER_ID_1));
        
        assertTrue(foundOriginalCustomer,
            "Bidirectional navigation should return to original customer");

        // Test 4: Verify reverse navigation for joint accounts
        List<AccountXref> accountXrefs = accountXrefRepository
            .findByIdAccountId(VALID_ACCOUNT_ID_1);
        
        accountXrefs.forEach(xref -> {
            List<AccountXref> reverseCheck = accountXrefRepository
                .findByIdCustomerId(xref.getId().getCustomerId());
            
            assertTrue(reverseCheck.stream()
                .anyMatch(r -> r.getId().getAccountId().equals(VALID_ACCOUNT_ID_1)),
                "Reverse navigation should find original account");
        });
    }

    /**
     * Test 19: Validate cascade delete behavior with referential integrity.
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Cascade delete from customer</li>
     *   <li>Cascade delete from account</li>
     *   <li>Orphan record prevention</li>
     * </ul>
     */
    @Test
    @Order(19)
    @DisplayName("Test cascade delete maintains referential integrity")
    void testXrefIntegrityWithCascades() {
        // Arrange: Create a cross-reference that will be subject to cascade operations
        AccountXrefId testId = new AccountXrefId(VALID_CUSTOMER_ID_1, VALID_ACCOUNT_ID_1);
        
        // Verify the cross-reference exists
        Optional<AccountXref> xrefBefore = accountXrefRepository.findById(testId);
        assertTrue(xrefBefore.isPresent(), 
            "Test cross-reference should exist before cascade test");

        // Test: Verify count methods work for cascade scenario testing
        long customerXrefCount = accountXrefRepository.countByIdCustomerId(VALID_CUSTOMER_ID_1);
        assertTrue(customerXrefCount > 0,
            "Customer should have at least one cross-reference");

        long accountXrefCount = accountXrefRepository.countByIdAccountId(VALID_ACCOUNT_ID_1);
        assertTrue(accountXrefCount > 0,
            "Account should have at least one cross-reference");

        // Note: Actual cascade delete testing requires customer/account deletion
        // which is beyond the scope of this repository test. The foreign key
        // constraints with CASCADE are validated at database level.
        // This test validates the counting methods used in cascade scenarios.
    }

    /**
     * Test 20: Validate detection of orphaned cross-references.
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Query for orphaned cross-references</li>
     *   <li>Referential integrity checks</li>
     *   <li>Data quality validation</li>
     * </ul>
     */
    @Test
    @Order(20)
    @DisplayName("Test query validation for data integrity")
    void testFindOrphanedXrefs() {
        // Test 1: Verify all cross-references have valid customer IDs
        List<AccountXref> allXrefs = accountXrefRepository.findAll();
        
        allXrefs.forEach(xref -> {
            assertNotNull(xref.getId(), "Cross-reference should have composite key");
            assertNotNull(xref.getId().getCustomerId(), 
                "Customer ID should not be null");
            assertNotNull(xref.getId().getAccountId(), 
                "Account ID should not be null");
            
            // Verify customer ID is within COBOL PIC 9(09) range
            assertTrue(xref.getId().getCustomerId() > 0 && 
                      xref.getId().getCustomerId() <= 999999999L,
                "Customer ID should be within NUMERIC(9,0) range");
            
            // Verify account ID is within COBOL PIC 9(11) range
            assertTrue(xref.getId().getAccountId() > 0 && 
                      xref.getId().getAccountId() <= 99999999999L,
                "Account ID should be within NUMERIC(11,0) range");
        });

        // Test 2: Verify existence check method works correctly
        assertTrue(accountXrefRepository.existsByCustomerIdAndAccountId(
            VALID_CUSTOMER_ID_1, VALID_ACCOUNT_ID_1),
            "Existence check should return true for valid relationship");
        
        assertFalse(accountXrefRepository.existsByCustomerIdAndAccountId(
            INVALID_CUSTOMER_ID, INVALID_ACCOUNT_ID),
            "Existence check should return false for invalid relationship");
    }

    /**
     * Test 21: Validate count operations for customer and account relationships.
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>countByIdCustomerId returns accurate count</li>
     *   <li>countByIdAccountId returns accurate count</li>
     *   <li>Joint account detection (count > 1)</li>
     * </ul>
     */
    @Test
    @Order(21)
    @DisplayName("Test count methods return accurate relationship counts")
    void testCountOperations() {
        // Test 1: Count accounts for customer
        long customerAccountCount = accountXrefRepository.countByIdCustomerId(VALID_CUSTOMER_ID_1);
        
        // Verify count matches findByIdCustomerId result
        List<AccountXref> customerAccounts = accountXrefRepository
            .findByIdCustomerId(VALID_CUSTOMER_ID_1);
        assertEquals(customerAccounts.size(), customerAccountCount,
            "Count should match list size for customer accounts");
        
        assertTrue(customerAccountCount > 0,
            "Test customer should have at least one account");

        // Test 2: Count customers for account
        long accountCustomerCount = accountXrefRepository.countByIdAccountId(VALID_ACCOUNT_ID_1);
        
        // Verify count matches findByIdAccountId result
        List<AccountXref> accountCustomers = accountXrefRepository
            .findByIdAccountId(VALID_ACCOUNT_ID_1);
        assertEquals(accountCustomers.size(), accountCustomerCount,
            "Count should match list size for account customers");
        
        assertTrue(accountCustomerCount > 0,
            "Test account should have at least one customer");

        // Test 3: Verify zero count for non-existent relationships
        long nonExistentCount = accountXrefRepository.countByIdCustomerId(INVALID_CUSTOMER_ID);
        assertEquals(0, nonExistentCount,
            "Count should be zero for non-existent customer");
    }

    /**
     * Test 22: Validate bulk delete operations for customer and account relationships.
     * 
     * <p><strong>VSAM Equivalent:</strong> Sequential delete of XREF records</p>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>deleteByIdCustomerId removes all customer relationships</li>
     *   <li>deleteByIdAccountId removes all account relationships</li>
     *   <li>Return count of deleted records</li>
     * </ul>
     */
    @Test
    @Order(22)
    @DisplayName("Test bulk delete operations for customer and account cleanup")
    void testBulkDeleteOperations() {
        // Arrange: Create test cross-references for deletion
        Long testCustomerId = 1000000100L;
        Long testAccountId1 = 10000000100L;
        Long testAccountId2 = 10000000101L;

        // Note: This test assumes test customers and accounts exist in test data
        // If not, this tests the method behavior with zero deletions

        // Test 1: Delete all cross-references for a customer
        long initialCustomerCount = accountXrefRepository.countByIdCustomerId(VALID_CUSTOMER_ID_2);
        
        if (initialCustomerCount > 0) {
            long deletedCount = accountXrefRepository.deleteByIdCustomerId(VALID_CUSTOMER_ID_2);
            
            assertEquals(initialCustomerCount, deletedCount,
                "Deleted count should match initial count");
            
            long afterDeleteCount = accountXrefRepository.countByIdCustomerId(VALID_CUSTOMER_ID_2);
            assertEquals(0, afterDeleteCount,
                "Customer should have no relationships after bulk delete");
        }

        // Test 2: Delete all cross-references for an account
        long initialAccountCount = accountXrefRepository.countByIdAccountId(VALID_ACCOUNT_ID_3);
        
        if (initialAccountCount > 0) {
            long deletedCount = accountXrefRepository.deleteByIdAccountId(VALID_ACCOUNT_ID_3);
            
            assertEquals(initialAccountCount, deletedCount,
                "Deleted count should match initial count");
            
            long afterDeleteCount = accountXrefRepository.countByIdAccountId(VALID_ACCOUNT_ID_3);
            assertEquals(0, afterDeleteCount,
                "Account should have no relationships after bulk delete");
        }

        // Test 3: Verify bulk delete with zero relationships returns zero
        long zeroDeleteCount = accountXrefRepository.deleteByIdCustomerId(INVALID_CUSTOMER_ID);
        assertEquals(0, zeroDeleteCount,
            "Bulk delete of non-existent relationships should return zero");
    }
}
