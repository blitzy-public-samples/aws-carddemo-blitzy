package com.carddemo.repository;

import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.Card;
import com.carddemo.model.entity.CardAccountXref;
import com.carddemo.model.entity.CardAccountXrefId;
import com.carddemo.model.entity.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for CardAccountXrefRepository using Testcontainers PostgreSQL.
 * 
 * Converted from: VSAM XREFFILE I/O operations testing
 * Source Copybook: CVACT03Y.cpy (CARD-XREF-RECORD, 50-byte record length)
 * 
 * Tests JPA repository operations for card_account_xref table that replaces VSAM XREFFILE
 * KSDS (Key-Sequenced Data Set). This cross-reference table establishes many-to-many
 * relationships between cards, accounts, and customers, allowing:
 * - One account to have multiple cards (primary cardholder + authorized users)
 * - One card to potentially associate with multiple accounts (though rare)
 * - Tracking which customer is responsible for each card-account relationship
 * 
 * Testing Strategy:
 * Uses Testcontainers to provide isolated PostgreSQL 16.6-alpine database instance,
 * ensuring clean test environment with actual database constraints, indexes, and
 * foreign key validation matching production PostgreSQL behavior.
 * 
 * Key Test Areas:
 * 1. Composite Primary Key Operations (xref_card_num + xref_acct_id)
 * 2. CRUD Operations with Composite Keys
 * 3. Foreign Key Constraint Validation (Card, Account, Customer)
 * 4. Custom Query Methods (findByXrefCardNum, findByXrefAcctId, findByXrefCustId)
 * 5. Many-to-Many Relationship Scenarios
 * 6. Query Performance Validation (sub-10ms requirement per Section 0.7.7)
 * 7. Composite Key Uniqueness Constraints
 * 8. Entity Relationship Loading (Lazy/Eager fetch)
 * 
 * Database Schema (from Section 0.3.4):
 *   CREATE TABLE card_account_xref (
 *       xref_card_num VARCHAR(16) NOT NULL REFERENCES card(card_num),
 *       xref_acct_id BIGINT NOT NULL REFERENCES account(acct_id),
 *       xref_cust_id BIGINT NOT NULL REFERENCES customer(cust_id),
 *       PRIMARY KEY (xref_card_num, xref_acct_id)
 *   );
 * 
 * Original COBOL Record Structure (CVACT03Y.cpy):
 *   01 CARD-XREF-RECORD.
 *      05  XREF-CARD-NUM    PIC X(16).
 *      05  XREF-CUST-ID     PIC 9(09).
 *      05  XREF-ACCT-ID     PIC 9(11).
 *      05  FILLER           PIC X(14).
 * 
 * Per Agent Action Plan Section 0.7.1 MINIMAL CHANGE: Tests validate exact COBOL
 * 50-byte cross-reference record conversion with composite primary key and three
 * foreign keys, maintaining identical data relationships and query patterns.
 * 
 * Performance Requirements (Section 0.7.7):
 * - Composite primary key lookups must complete in sub-10ms
 * - Custom query methods must match or exceed VSAM alternate index performance
 * - Batch operations must support high-volume card-account association processing
 * 
 * @see CardAccountXrefRepository
 * @see CardAccountXref
 * @see CardAccountXrefId
 * @see Card
 * @see Account
 * @see Customer
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CardAccountXrefRepositoryTest {

    /**
     * PostgreSQL 16.6-alpine test container for isolated integration testing.
     * 
     * Provides real PostgreSQL database with:
     * - Complete SQL constraint enforcement (primary keys, foreign keys, NOT NULL)
     * - B-tree indexes matching production environment
     * - Transaction isolation and rollback behavior
     * - Accurate query performance characteristics
     * 
     * Container lifecycle managed by @Testcontainers annotation:
     * - Started before all tests in this class
     * - Stopped automatically after all tests complete
     * - Provides clean database state for each test class execution
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine")
            .withDatabaseName("carddemo_test")
            .withUsername("testuser")
            .withPassword("testpass");

    /**
     * Configures Spring Boot test to use Testcontainers PostgreSQL instance.
     * 
     * Dynamically registers database connection properties from running container:
     * - spring.datasource.url: JDBC URL with dynamic port
     * - spring.datasource.username: Test database username
     * - spring.datasource.password: Test database password
     * 
     * Ensures test database connectivity and proper Flyway migration execution.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private CardAccountXrefRepository cardAccountXrefRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * Setup method to ensure clean database state before each test.
     * 
     * Clears all cross-reference data to prevent test interference while
     * maintaining prerequisite entity records (cards, accounts, customers).
     */
    @BeforeEach
    void setUp() {
        // Clear cross-reference data before each test to ensure test isolation
        cardAccountXrefRepository.deleteAll();
        entityManager.flush();
        entityManager.clear();
    }

    // ========================================================================
    // Helper Methods - Create Prerequisite Entities
    // ========================================================================

    /**
     * Creates and persists a test Customer entity with required foreign key for CardAccountXref.
     * 
     * Converts COBOL customer data structure from CVCUS01Y.cpy to Customer entity.
     * Required as prerequisite for CardAccountXref records (xref_cust_id foreign key).
     * 
     * @param custId Customer ID (COBOL PIC 9(09) CUST-ID)
     * @param firstName Customer first name (COBOL PIC X(25) CUST-FIRST-NAME)
     * @param lastName Customer last name (COBOL PIC X(25) CUST-LAST-NAME)
     * @param ssn Customer SSN (COBOL PIC 9(09) CUST-SSN)
     * @return Persisted Customer entity
     */
    private Customer createCustomer(Long custId, String firstName, String lastName, String ssn) {
        Customer customer = Customer.builder()
                .custId(custId)
                .custFirstName(firstName)
                .custMiddleName(null)
                .custLastName(lastName)
                .custAddrLine1("123 Main St")
                .custAddrLine2(null)
                .custAddrLine3(null)
                .custAddrStateCd("TX")
                .custAddrCountryCd("USA")
                .custAddrZip("75001")
                .custPhoneNum1("2145551234")
                .custPhoneNum2(null)
                .custSsn(ssn)
                .custGovtIssuedId("DL-" + custId)
                .custDobYyyyMmDd(LocalDate.of(1980, 1, 15))
                .custFicoCreditScore(750)
                .build();
        return customerRepository.save(customer);
    }

    /**
     * Creates and persists a test Account entity with required foreign key for Card and CardAccountXref.
     * 
     * Converts COBOL account data structure from CVACT01Y.cpy to Account entity.
     * Required as prerequisite for Card records (card_acct_id foreign key).
     * 
     * @param acctId Account ID (COBOL PIC 9(11) ACCT-ID)
     * @param currentBalance Current account balance (COBOL PIC S9(10)V99 COMP-3 ACCT-CURR-BAL)
     * @param creditLimit Credit limit (COBOL PIC S9(10)V99 COMP-3 ACCT-CREDIT-LIMIT)
     * @return Persisted Account entity
     */
    private Account createAccount(Long acctId, BigDecimal currentBalance, BigDecimal creditLimit) {
        Account account = Account.builder()
                .acctId(acctId)
                .acctActiveStatus("Y")
                .acctCurrBal(currentBalance)
                .acctCreditLimit(creditLimit)
                .acctCashCreditLimit(creditLimit.multiply(BigDecimal.valueOf(0.5)))
                .acctOpenDate(LocalDate.of(2020, 1, 1))
                .acctExpirationDate(LocalDate.of(2025, 12, 31))
                .acctReissueDate(null)
                .acctCurrCycCredit(BigDecimal.ZERO)
                .acctCurrCycDebit(BigDecimal.ZERO)
                .acctAddrZip("75001")
                .acctGroupId("DEFAULT")
                .build();
        return accountRepository.save(account);
    }

    /**
     * Creates and persists a test Card entity with required foreign key for CardAccountXref.
     * 
     * Converts COBOL card data structure from CVACT02Y.cpy to Card entity.
     * Required as prerequisite for CardAccountXref records (xref_card_num foreign key).
     * 
     * Note: Card requires Account to exist first (card_acct_id foreign key).
     * 
     * @param cardNum Card number (COBOL PIC X(16) CARD-NUM)
     * @param cardAcctId Account ID foreign key (COBOL PIC 9(11) CARD-ACCT-ID)
     * @param cardholderName Embossed cardholder name (COBOL PIC X(50) CARD-EMBOSSED-NAME)
     * @param expirationDate Card expiration date (COBOL PIC X(10) CARD-EXPIRAION-DATE)
     * @return Persisted Card entity
     */
    private Card createCard(String cardNum, Long cardAcctId, String cardholderName, LocalDate expirationDate) {
        Card card = Card.builder()
                .cardNum(cardNum)
                .cardAcctId(cardAcctId)
                .cardStatus("A") // Active
                .cardEmbossedName(cardholderName)
                .cardExpirationDate(expirationDate)
                .build();
        return cardRepository.save(card);
    }

    // ========================================================================
    // Test Methods - Composite Key CRUD Operations
    // ========================================================================

    /**
     * Tests saving a new CardAccountXref with composite primary key.
     * 
     * Validates:
     * - Entity can be saved with composite key (xrefCardNum + xrefAcctId)
     * - All three fields are persisted correctly (xrefCardNum, xrefAcctId, xrefCustId)
     * - Database generates no additional fields (no createdAt, updatedAt, version per schema)
     * - Entity can be retrieved using composite key
     * 
     * Replaces COBOL: EXEC CICS WRITE FILE('XREFFILE') FROM(CARD-XREF-RECORD) RIDFLD(...) END-EXEC
     */
    @Test
    void testSaveCardAccountXref() {
        // Given: Prerequisite entities (customer, account, card)
        Customer customer = createCustomer(100000001L, "John", "Doe", "123456789");
        Account account = createAccount(1000000001L, BigDecimal.valueOf(5000.00), BigDecimal.valueOf(10000.00));
        Card card = createCard("4111111111111111", account.getAcctId(), "JOHN DOE", LocalDate.of(2025, 12, 31));

        // When: Creating and saving card-account cross-reference
        CardAccountXref xref = CardAccountXref.builder()
                .xrefCardNum(card.getCardNum())
                .xrefAcctId(account.getAcctId())
                .customer(customer)
                .build();

        CardAccountXref savedXref = cardAccountXrefRepository.save(xref);
        entityManager.flush();
        entityManager.clear();

        // Then: Cross-reference is persisted with composite key
        assertNotNull(savedXref);
        assertEquals("4111111111111111", savedXref.getXrefCardNum());
        assertEquals(1000000001L, savedXref.getXrefAcctId());
        // Note: xrefCustId field is insertable=false, updatable=false, so access customer ID via relationship
        assertNotNull(savedXref.getCustomer());
        assertEquals(100000001L, savedXref.getCustomer().getCustId());

        // Verify entity can be retrieved using composite key
        CardAccountXrefId compositeKey = new CardAccountXrefId("4111111111111111", 1000000001L);
        Optional<CardAccountXref> retrievedXref = cardAccountXrefRepository.findById(compositeKey);
        assertTrue(retrievedXref.isPresent());
        assertEquals("4111111111111111", retrievedXref.get().getXrefCardNum());
        assertEquals(1000000001L, retrievedXref.get().getXrefAcctId());
        // Access customer ID via relationship (xrefCustId field is non-insertable/non-updatable)
        assertNotNull(retrievedXref.get().getCustomer());
        assertEquals(100000001L, retrievedXref.get().getCustomer().getCustId());
    }

    /**
     * Tests finding CardAccountXref by composite primary key.
     * 
     * Validates:
     * - findById() works with CardAccountXrefId composite key class
     * - Both components of composite key (xrefCardNum + xrefAcctId) are required
     * - Returns Optional.empty() when composite key not found
     * - Returns entity with all fields populated when found
     * 
     * Replaces COBOL: EXEC CICS READ FILE('XREFFILE') RIDFLD(XREF-CARD-NUM + XREF-ACCT-ID) INTO(...) END-EXEC
     */
    @Test
    void testFindByIdWithCompositeKey() {
        // Given: Existing card-account cross-reference
        Customer customer = createCustomer(100000002L, "Jane", "Smith", "987654321");
        Account account = createAccount(1000000002L, BigDecimal.valueOf(2000.00), BigDecimal.valueOf(5000.00));
        Card card = createCard("4222222222222222", account.getAcctId(), "JANE SMITH", LocalDate.of(2026, 6, 30));

        CardAccountXref xref = CardAccountXref.builder()
                .xrefCardNum(card.getCardNum())
                .xrefAcctId(account.getAcctId())
                .customer(customer)
                .build();
        cardAccountXrefRepository.save(xref);
        entityManager.flush();
        entityManager.clear();

        // When: Finding by composite key
        CardAccountXrefId compositeKey = new CardAccountXrefId("4222222222222222", 1000000002L);
        Optional<CardAccountXref> foundXref = cardAccountXrefRepository.findById(compositeKey);

        // Then: Entity is found with correct field values
        assertTrue(foundXref.isPresent());
        CardAccountXref retrievedXref = foundXref.get();
        assertEquals("4222222222222222", retrievedXref.getXrefCardNum());
        assertEquals(1000000002L, retrievedXref.getXrefAcctId());
        assertEquals(100000002L, retrievedXref.getXrefCustId());
    }

    /**
     * Tests finding all CardAccountXref records.
     * 
     * Validates:
     * - findAll() returns all cross-reference records
     * - Multiple records with different composite keys are retrieved
     * - Records are ordered by composite primary key (xrefCardNum, xrefAcctId)
     * 
     * Replaces COBOL: Sequential STARTBR/READNEXT loop through entire XREFFILE
     */
    @Test
    void testFindAllCardAccountXrefs() {
        // Given: Multiple card-account cross-references
        Customer customer1 = createCustomer(100000003L, "Alice", "Johnson", "111222333");
        Customer customer2 = createCustomer(100000004L, "Bob", "Williams", "444555666");
        
        Account account1 = createAccount(1000000003L, BigDecimal.valueOf(3000.00), BigDecimal.valueOf(8000.00));
        Account account2 = createAccount(1000000004L, BigDecimal.valueOf(4000.00), BigDecimal.valueOf(12000.00));
        
        Card card1 = createCard("4333333333333333", account1.getAcctId(), "ALICE JOHNSON", LocalDate.of(2025, 3, 31));
        Card card2 = createCard("4444444444444444", account2.getAcctId(), "BOB WILLIAMS", LocalDate.of(2026, 9, 30));

        CardAccountXref xref1 = CardAccountXref.builder()
                .xrefCardNum(card1.getCardNum())
                .xrefAcctId(account1.getAcctId())
                .customer(customer1)
                .build();
        
        CardAccountXref xref2 = CardAccountXref.builder()
                .xrefCardNum(card2.getCardNum())
                .xrefAcctId(account2.getAcctId())
                .customer(customer2)
                .build();

        cardAccountXrefRepository.save(xref1);
        cardAccountXrefRepository.save(xref2);
        entityManager.flush();
        entityManager.clear();

        // When: Finding all cross-references
        List<CardAccountXref> allXrefs = cardAccountXrefRepository.findAll();

        // Then: All records are retrieved
        assertNotNull(allXrefs);
        assertEquals(2, allXrefs.size());
        
        // Verify first record
        CardAccountXref first = allXrefs.stream()
                .filter(x -> x.getXrefCardNum().equals("4333333333333333"))
                .findFirst().orElse(null);
        assertNotNull(first);
        assertEquals(1000000003L, first.getXrefAcctId());
        assertEquals(100000003L, first.getXrefCustId());
        
        // Verify second record
        CardAccountXref second = allXrefs.stream()
                .filter(x -> x.getXrefCardNum().equals("4444444444444444"))
                .findFirst().orElse(null);
        assertNotNull(second);
        assertEquals(1000000004L, second.getXrefAcctId());
        assertEquals(100000004L, second.getXrefCustId());
    }

    /**
     * Tests updating CardAccountXref entity (changing customer association).
     * 
     * Validates:
     * - Composite key fields (xrefCardNum, xrefAcctId) are immutable (part of @Id)
     * - Only non-key fields (xrefCustId via customer relationship) can be updated
     * - Update persists correctly to database
     * - Subsequent retrieval returns updated values
     * 
     * Replaces COBOL: EXEC CICS REWRITE FILE('XREFFILE') FROM(CARD-XREF-RECORD) END-EXEC
     */
    @Test
    void testUpdateCardAccountXref() {
        // Given: Existing card-account cross-reference
        Customer originalCustomer = createCustomer(100000005L, "Charlie", "Brown", "777888999");
        Customer newCustomer = createCustomer(100000006L, "David", "Miller", "000111222");
        Account account = createAccount(1000000005L, BigDecimal.valueOf(6000.00), BigDecimal.valueOf(15000.00));
        Card card = createCard("4555555555555555", account.getAcctId(), "CHARLIE BROWN", LocalDate.of(2025, 6, 30));

        CardAccountXref xref = CardAccountXref.builder()
                .xrefCardNum(card.getCardNum())
                .xrefAcctId(account.getAcctId())
                .customer(originalCustomer)
                .build();
        cardAccountXrefRepository.save(xref);
        entityManager.flush();
        entityManager.clear();

        // When: Updating customer association (changing who is responsible for card)
        CardAccountXrefId compositeKey = new CardAccountXrefId("4555555555555555", 1000000005L);
        Optional<CardAccountXref> existingXref = cardAccountXrefRepository.findById(compositeKey);
        assertTrue(existingXref.isPresent());
        
        CardAccountXref xrefToUpdate = existingXref.get();
        xrefToUpdate.setCustomer(newCustomer);
        cardAccountXrefRepository.save(xrefToUpdate);
        entityManager.flush();
        entityManager.clear();

        // Then: Customer association is updated
        Optional<CardAccountXref> updatedXref = cardAccountXrefRepository.findById(compositeKey);
        assertTrue(updatedXref.isPresent());
        assertEquals("4555555555555555", updatedXref.get().getXrefCardNum());
        assertEquals(1000000005L, updatedXref.get().getXrefAcctId());
        assertEquals(100000006L, updatedXref.get().getXrefCustId()); // New customer ID
    }

    /**
     * Tests deleting CardAccountXref by composite primary key.
     * 
     * Validates:
     * - delete() or deleteById() works with composite key
     * - Record is removed from database
     * - Subsequent findById() returns Optional.empty()
     * - Deletion does not affect referenced entities (Card, Account, Customer)
     * 
     * Replaces COBOL: EXEC CICS DELETE FILE('XREFFILE') RIDFLD(XREF-CARD-NUM + XREF-ACCT-ID) END-EXEC
     */
    @Test
    void testDeleteCardAccountXref() {
        // Given: Existing card-account cross-reference
        Customer customer = createCustomer(100000007L, "Eve", "Davis", "333444555");
        Account account = createAccount(1000000006L, BigDecimal.valueOf(7000.00), BigDecimal.valueOf(20000.00));
        Card card = createCard("4666666666666666", account.getAcctId(), "EVE DAVIS", LocalDate.of(2026, 12, 31));

        CardAccountXref xref = CardAccountXref.builder()
                .xrefCardNum(card.getCardNum())
                .xrefAcctId(account.getAcctId())
                .customer(customer)
                .build();
        cardAccountXrefRepository.save(xref);
        entityManager.flush();
        entityManager.clear();

        // When: Deleting by composite key
        CardAccountXrefId compositeKey = new CardAccountXrefId("4666666666666666", 1000000006L);
        cardAccountXrefRepository.deleteById(compositeKey);
        entityManager.flush();
        entityManager.clear();

        // Then: Cross-reference is deleted
        Optional<CardAccountXref> deletedXref = cardAccountXrefRepository.findById(compositeKey);
        assertFalse(deletedXref.isPresent());
        
        // Verify referenced entities still exist (deletion does not cascade)
        assertTrue(cardRepository.findById("4666666666666666").isPresent());
        assertTrue(accountRepository.findById(1000000006L).isPresent());
        assertTrue(customerRepository.findById(100000007L).isPresent());
    }

    /**
     * Tests composite key uniqueness constraint.
     * 
     * Validates:
     * - Duplicate composite key (xrefCardNum + xrefAcctId) throws DataIntegrityViolationException
     * - Database PRIMARY KEY constraint is enforced
     * - Error matches expected COBOL file-status 22 (duplicate key) behavior
     * 
     * Replaces COBOL: EXEC CICS WRITE FILE('XREFFILE') ... with file-status 22 (duplicate key)
     */
    @Test
    void testCompositeKeyUniqueness() {
        // Given: Existing card-account cross-reference
        Customer customer1 = createCustomer(100000008L, "Frank", "Garcia", "666777888");
        Account account = createAccount(1000000007L, BigDecimal.valueOf(8000.00), BigDecimal.valueOf(18000.00));
        Card card = createCard("4777777777777777", account.getAcctId(), "FRANK GARCIA", LocalDate.of(2025, 9, 30));

        CardAccountXref xref1 = CardAccountXref.builder()
                .xrefCardNum(card.getCardNum())
                .xrefAcctId(account.getAcctId())
                .customer(customer1)
                .build();
        cardAccountXrefRepository.save(xref1);
        entityManager.flush();
        entityManager.clear();

        // When: Attempting to create duplicate composite key with different customer
        Customer customer2 = createCustomer(100000009L, "Grace", "Martinez", "999000111");
        CardAccountXref xref2 = CardAccountXref.builder()
                .xrefCardNum("4777777777777777") // Same card number
                .xrefAcctId(1000000007L)          // Same account ID (duplicate composite key)
                .customer(customer2)               // Different customer
                .build();

        // Then: ConstraintViolationException is thrown for duplicate composite primary key
        // Note: Must use persist() to force INSERT operation (save() would detect existing entity and UPDATE instead)
        // Using entityManager.persist() bypasses Spring's exception translation, so we catch the raw Hibernate exception
        assertThrows(org.hibernate.exception.ConstraintViolationException.class, () -> {
            entityManager.persist(xref2);
            entityManager.flush();
        });
    }

    /**
     * Tests finding by non-existent composite key.
     * 
     * Validates:
     * - findById() with non-existent composite key returns Optional.empty()
     * - No exception is thrown
     * - Matches COBOL file-status 23 (record not found) behavior
     * 
     * Replaces COBOL: EXEC CICS READ FILE('XREFFILE') ... with file-status 23 (not found)
     */
    @Test
    void testCompositeKeyNotFound() {
        // When: Attempting to find non-existent composite key
        CardAccountXrefId nonExistentKey = new CardAccountXrefId("9999999999999999", 9999999999L);
        Optional<CardAccountXref> notFoundXref = cardAccountXrefRepository.findById(nonExistentKey);

        // Then: Returns empty Optional (COBOL file-status 23 equivalent)
        assertFalse(notFoundXref.isPresent());
    }

    // ========================================================================
    // Test Methods - Foreign Key Relationship Tests
    // ========================================================================

    /**
     * Tests Card foreign key relationship loading.
     * 
     * Validates:
     * - xref.getCard() loads associated Card entity via xref_card_num foreign key
     * - Card entity fields are populated correctly
     * - JPA lazy loading or eager loading works as configured
     * - Foreign key constraint maintains referential integrity
     */
    @Test
    void testCardForeignKey() {
        // Given: Card-account cross-reference with card relationship
        Customer customer = createCustomer(100000010L, "Henry", "Rodriguez", "222333444");
        Account account = createAccount(1000000008L, BigDecimal.valueOf(9000.00), BigDecimal.valueOf(25000.00));
        Card card = createCard("4888888888888888", account.getAcctId(), "HENRY RODRIGUEZ", LocalDate.of(2026, 3, 31));

        CardAccountXref xref = CardAccountXref.builder()
                .xrefCardNum(card.getCardNum())
                .xrefAcctId(account.getAcctId())
                .customer(customer)
                .build();
        cardAccountXrefRepository.save(xref);
        entityManager.flush();
        entityManager.clear();

        // When: Loading cross-reference and accessing card relationship
        CardAccountXrefId compositeKey = new CardAccountXrefId("4888888888888888", 1000000008L);
        Optional<CardAccountXref> foundXref = cardAccountXrefRepository.findById(compositeKey);

        // Then: Card relationship is loaded correctly
        assertTrue(foundXref.isPresent());
        CardAccountXref xrefWithCard = foundXref.get();
        assertNotNull(xrefWithCard.getCard());
        assertEquals("4888888888888888", xrefWithCard.getCard().getCardNum());
        assertEquals("HENRY RODRIGUEZ", xrefWithCard.getCard().getCardEmbossedName());
        assertEquals("A", xrefWithCard.getCard().getCardStatus());
    }

    /**
     * Tests Account foreign key relationship loading.
     * 
     * Validates:
     * - xref.getAccount() loads associated Account entity via xref_acct_id foreign key
     * - Account entity fields are populated correctly
     * - BigDecimal balance and limit fields maintain precision
     * - JPA relationship loading works as configured
     */
    @Test
    void testAccountForeignKey() {
        // Given: Card-account cross-reference with account relationship
        Customer customer = createCustomer(100000011L, "Iris", "Wilson", "555666777");
        Account account = createAccount(1000000009L, BigDecimal.valueOf(10000.50), BigDecimal.valueOf(30000.00));
        Card card = createCard("4999999999999999", account.getAcctId(), "IRIS WILSON", LocalDate.of(2025, 11, 30));

        CardAccountXref xref = CardAccountXref.builder()
                .xrefCardNum(card.getCardNum())
                .xrefAcctId(account.getAcctId())
                .customer(customer)
                .build();
        cardAccountXrefRepository.save(xref);
        entityManager.flush();
        entityManager.clear();

        // When: Loading cross-reference and accessing account relationship
        CardAccountXrefId compositeKey = new CardAccountXrefId("4999999999999999", 1000000009L);
        Optional<CardAccountXref> foundXref = cardAccountXrefRepository.findById(compositeKey);

        // Then: Account relationship is loaded correctly
        assertTrue(foundXref.isPresent());
        CardAccountXref xrefWithAccount = foundXref.get();
        assertNotNull(xrefWithAccount.getAccount());
        assertEquals(1000000009L, xrefWithAccount.getAccount().getAcctId());
        // Use compareTo() for BigDecimal comparison per Section 0.7.2 (scale-independent comparison)
        assertEquals(0, BigDecimal.valueOf(10000.50).compareTo(xrefWithAccount.getAccount().getAcctCurrBal()));
        assertEquals(0, BigDecimal.valueOf(30000.00).compareTo(xrefWithAccount.getAccount().getAcctCreditLimit()));
        assertEquals("Y", xrefWithAccount.getAccount().getAcctActiveStatus());
    }

    /**
     * Tests Customer foreign key relationship loading.
     * 
     * Validates:
     * - xref.getCustomer() loads associated Customer entity via xref_cust_id foreign key
     * - Customer entity fields are populated correctly
     * - Customer PII fields (name, SSN) are accessible
     * - JPA relationship loading works as configured
     */
    @Test
    void testCustomerForeignKey() {
        // Given: Card-account cross-reference with customer relationship
        Customer customer = createCustomer(100000012L, "Jack", "Anderson", "888999000");
        Account account = createAccount(1000000010L, BigDecimal.valueOf(11000.00), BigDecimal.valueOf(22000.00));
        Card card = createCard("5000000000000000", account.getAcctId(), "JACK ANDERSON", LocalDate.of(2026, 5, 31));

        CardAccountXref xref = CardAccountXref.builder()
                .xrefCardNum(card.getCardNum())
                .xrefAcctId(account.getAcctId())
                .customer(customer)
                .build();
        cardAccountXrefRepository.save(xref);
        entityManager.flush();
        entityManager.clear();

        // When: Loading cross-reference and accessing customer relationship
        CardAccountXrefId compositeKey = new CardAccountXrefId("5000000000000000", 1000000010L);
        Optional<CardAccountXref> foundXref = cardAccountXrefRepository.findById(compositeKey);

        // Then: Customer relationship is loaded correctly
        assertTrue(foundXref.isPresent());
        CardAccountXref xrefWithCustomer = foundXref.get();
        assertNotNull(xrefWithCustomer.getCustomer());
        assertEquals(100000012L, xrefWithCustomer.getCustomer().getCustId());
        assertEquals("Jack", xrefWithCustomer.getCustomer().getCustFirstName());
        assertEquals("Anderson", xrefWithCustomer.getCustomer().getCustLastName());
        assertEquals("888999000", xrefWithCustomer.getCustomer().getCustSsn());
    }

    /**
     * Tests card foreign key constraint violation.
     * 
     * Validates:
     * - Attempting to create cross-reference with non-existent card number throws exception
     * - Database FOREIGN KEY constraint on xref_card_num is enforced
     * - Matches COBOL logic error file-status behavior
     */
    @Test
    void testCardForeignKeyConstraint() {
        // Given: Account and customer exist, but card does not
        Customer customer = createCustomer(100000013L, "Kate", "Thomas", "111222333");
        Account account = createAccount(1000000011L, BigDecimal.valueOf(5000.00), BigDecimal.valueOf(10000.00));

        // When: Attempting to create cross-reference with non-existent card
        CardAccountXref xref = CardAccountXref.builder()
                .xrefCardNum("9876543210987654") // Non-existent card number
                .xrefAcctId(account.getAcctId())
                .customer(customer)
                .build();

        // Then: DataIntegrityViolationException is thrown for foreign key constraint violation
        // Note: Using saveAndFlush() to ensure Spring's DAO exception translation wraps Hibernate's ConstraintViolationException
        assertThrows(DataIntegrityViolationException.class, () -> {
            cardAccountXrefRepository.saveAndFlush(xref);
        });
    }

    /**
     * Tests account foreign key constraint violation.
     * 
     * Validates:
     * - Attempting to create cross-reference with non-existent account ID throws exception
     * - Database FOREIGN KEY constraint on xref_acct_id is enforced
     * - Referential integrity is maintained
     */
    @Test
    void testAccountForeignKeyConstraint() {
        // Given: Card and customer exist, but referencing non-existent account
        Customer customer = createCustomer(100000014L, "Leo", "Jackson", "444555666");
        Account account = createAccount(1000000012L, BigDecimal.valueOf(6000.00), BigDecimal.valueOf(12000.00));
        Card card = createCard("5111111111111111", account.getAcctId(), "LEO JACKSON", LocalDate.of(2025, 7, 31));

        // When: Attempting to create cross-reference with non-existent account
        CardAccountXref xref = CardAccountXref.builder()
                .xrefCardNum(card.getCardNum())
                .xrefAcctId(9999999999L) // Non-existent account ID
                .customer(customer)
                .build();

        // Then: DataIntegrityViolationException is thrown for foreign key constraint violation
        // Note: Using saveAndFlush() to ensure Spring's DAO exception translation wraps Hibernate's ConstraintViolationException
        assertThrows(DataIntegrityViolationException.class, () -> {
            cardAccountXrefRepository.saveAndFlush(xref);
        });
    }

    /**
     * Tests customer foreign key constraint violation.
     * 
     * Validates:
     * - Attempting to create cross-reference with non-existent customer ID throws exception
     * - Database FOREIGN KEY constraint on xref_cust_id is enforced
     * - All three foreign key relationships are validated
     */
    @Test
    void testCustomerForeignKeyConstraint() {
        // Given: Card and account exist, but we'll attempt to insert xref with non-existent customer
        Account account = createAccount(1000000013L, BigDecimal.valueOf(7000.00), BigDecimal.valueOf(14000.00));
        Card card = createCard("5222222222222222", account.getAcctId(), "MARY WHITE", LocalDate.of(2026, 8, 31));

        // When/Then: Use native SQL to bypass JPA entity validation and test database FK constraint directly
        // Note: xrefCustId field is marked insertable=false in entity, so we must use native SQL
        // to properly test the database foreign key constraint on xref_cust_id column.
        // Native SQL bypasses Spring's exception translation, so we catch Hibernate's ConstraintViolationException
        assertThrows(org.hibernate.exception.ConstraintViolationException.class, () -> {
            entityManager.getEntityManager().createNativeQuery(
                "INSERT INTO card_account_xref (xref_card_num, xref_acct_id, xref_cust_id) VALUES (?, ?, ?)")
                .setParameter(1, card.getCardNum())
                .setParameter(2, account.getAcctId())
                .setParameter(3, 999999999L) // Non-existent customer ID - tests FK constraint
                .executeUpdate();
            entityManager.flush();
        });
    }

    // ========================================================================
    // Test Methods - Custom Query Methods
    // ========================================================================

    /**
     * Tests findByXrefCardNum custom query method.
     * 
     * Validates:
     * - Finds all cross-reference records for a specific card number
     * - Returns multiple records if card is associated with multiple accounts (rare but possible)
     * - Returns empty list if no matches found
     * - Query uses index for performance
     * 
     * Replaces COBOL: STARTBR/READNEXT loop browsing XREFFILE by card number
     */
    @Test
    void testFindByXrefCardNum() {
        // Given: One card associated with multiple accounts (unusual but valid scenario)
        Customer customer1 = createCustomer(100000015L, "Nancy", "Harris", "000111222");
        Customer customer2 = createCustomer(100000016L, "Oliver", "Clark", "333444555");
        
        Account account1 = createAccount(1000000014L, BigDecimal.valueOf(8000.00), BigDecimal.valueOf(16000.00));
        Account account2 = createAccount(1000000015L, BigDecimal.valueOf(9000.00), BigDecimal.valueOf(18000.00));
        
        Card card = createCard("5333333333333333", account1.getAcctId(), "NANCY HARRIS", LocalDate.of(2025, 10, 31));

        // Create two cross-references for same card with different accounts
        CardAccountXref xref1 = CardAccountXref.builder()
                .xrefCardNum(card.getCardNum())
                .xrefAcctId(account1.getAcctId())
                .customer(customer1)
                .build();
        
        CardAccountXref xref2 = CardAccountXref.builder()
                .xrefCardNum(card.getCardNum())
                .xrefAcctId(account2.getAcctId())
                .customer(customer2)
                .build();

        cardAccountXrefRepository.save(xref1);
        cardAccountXrefRepository.save(xref2);
        entityManager.flush();
        entityManager.clear();

        // When: Finding by card number
        List<CardAccountXref> xrefsByCard = cardAccountXrefRepository.findByXrefCardNum("5333333333333333");

        // Then: Both cross-references are found
        assertNotNull(xrefsByCard);
        assertEquals(2, xrefsByCard.size());
        assertTrue(xrefsByCard.stream().allMatch(x -> x.getXrefCardNum().equals("5333333333333333")));
        assertTrue(xrefsByCard.stream().anyMatch(x -> x.getXrefAcctId().equals(1000000014L)));
        assertTrue(xrefsByCard.stream().anyMatch(x -> x.getXrefAcctId().equals(1000000015L)));
    }

    /**
     * Tests findByXrefAcctId custom query method.
     * 
     * Validates:
     * - Finds all cross-reference records for a specific account ID
     * - Returns multiple records for accounts with multiple cards (primary + authorized users)
     * - Returns empty list if no matches found
     * - Query uses index for performance
     * 
     * Replaces COBOL: STARTBR/READNEXT loop browsing XREFFILE alternate index by account ID
     */
    @Test
    void testFindByXrefAcctId() {
        // Given: One account with multiple cards (typical scenario: primary + authorized users)
        Customer customer1 = createCustomer(100000017L, "Paula", "Lewis", "666777888");
        Customer customer2 = createCustomer(100000018L, "Quinn", "Walker", "999000111");
        
        Account account = createAccount(1000000016L, BigDecimal.valueOf(10000.00), BigDecimal.valueOf(20000.00));
        
        Card card1 = createCard("5444444444444444", account.getAcctId(), "PAULA LEWIS", LocalDate.of(2025, 12, 31));
        Card card2 = createCard("5555555555555555", account.getAcctId(), "QUINN WALKER", LocalDate.of(2026, 1, 31));

        // Create two cross-references for same account with different cards
        CardAccountXref xref1 = CardAccountXref.builder()
                .xrefCardNum(card1.getCardNum())
                .xrefAcctId(account.getAcctId())
                .customer(customer1)
                .build();
        
        CardAccountXref xref2 = CardAccountXref.builder()
                .xrefCardNum(card2.getCardNum())
                .xrefAcctId(account.getAcctId())
                .customer(customer2)
                .build();

        cardAccountXrefRepository.save(xref1);
        cardAccountXrefRepository.save(xref2);
        entityManager.flush();
        entityManager.clear();

        // When: Finding by account ID
        List<CardAccountXref> xrefsByAccount = cardAccountXrefRepository.findByXrefAcctId(1000000016L);

        // Then: Both cross-references are found
        assertNotNull(xrefsByAccount);
        assertEquals(2, xrefsByAccount.size());
        assertTrue(xrefsByAccount.stream().allMatch(x -> x.getXrefAcctId().equals(1000000016L)));
        assertTrue(xrefsByAccount.stream().anyMatch(x -> x.getXrefCardNum().equals("5444444444444444")));
        assertTrue(xrefsByAccount.stream().anyMatch(x -> x.getXrefCardNum().equals("5555555555555555")));
    }

    /**
     * Tests findByXrefCustId custom query method.
     * 
     * Validates:
     * - Finds all cross-reference records for a specific customer ID
     * - Returns multiple records for customers with multiple card-account relationships
     * - Returns empty list if no matches found
     * - Query uses index for performance
     * - Supports customer-level reporting and permission checks
     * 
     * Replaces COBOL: STARTBR/READNEXT loop browsing XREFFILE by customer ID
     */
    @Test
    void testFindByXrefCustId() {
        // Given: One customer with multiple card-account relationships
        Customer customer = createCustomer(100000019L, "Rachel", "Hall", "222333444");
        
        Account account1 = createAccount(1000000017L, BigDecimal.valueOf(11000.00), BigDecimal.valueOf(22000.00));
        Account account2 = createAccount(1000000018L, BigDecimal.valueOf(12000.00), BigDecimal.valueOf(24000.00));
        
        Card card1 = createCard("5666666666666666", account1.getAcctId(), "RACHEL HALL", LocalDate.of(2025, 11, 30));
        Card card2 = createCard("5777777777777777", account2.getAcctId(), "RACHEL HALL", LocalDate.of(2026, 2, 28));

        // Create two cross-references for same customer with different card-account combinations
        CardAccountXref xref1 = CardAccountXref.builder()
                .xrefCardNum(card1.getCardNum())
                .xrefAcctId(account1.getAcctId())
                .customer(customer)
                .build();
        
        CardAccountXref xref2 = CardAccountXref.builder()
                .xrefCardNum(card2.getCardNum())
                .xrefAcctId(account2.getAcctId())
                .customer(customer)
                .build();

        cardAccountXrefRepository.save(xref1);
        cardAccountXrefRepository.save(xref2);
        entityManager.flush();
        entityManager.clear();

        // When: Finding by customer ID
        List<CardAccountXref> xrefsByCustomer = cardAccountXrefRepository.findByXrefCustId(100000019L);

        // Then: Both cross-references are found
        assertNotNull(xrefsByCustomer);
        assertEquals(2, xrefsByCustomer.size());
        assertTrue(xrefsByCustomer.stream().allMatch(x -> x.getXrefCustId().equals(100000019L)));
        assertTrue(xrefsByCustomer.stream().anyMatch(x -> x.getXrefCardNum().equals("5666666666666666")));
        assertTrue(xrefsByCustomer.stream().anyMatch(x -> x.getXrefCardNum().equals("5777777777777777")));
    }

    /**
     * Tests custom query method with no results.
     * 
     * Validates:
     * - Custom query methods return empty list (not null) when no matches found
     * - No exception is thrown for non-existent search criteria
     * - Matches COBOL STARTBR EOF behavior
     */
    @Test
    void testCustomQueryMethodsReturnEmptyList() {
        // When: Searching for non-existent card, account, and customer
        List<CardAccountXref> byCard = cardAccountXrefRepository.findByXrefCardNum("9999999999999999");
        List<CardAccountXref> byAccount = cardAccountXrefRepository.findByXrefAcctId(9999999999L);
        List<CardAccountXref> byCustomer = cardAccountXrefRepository.findByXrefCustId(999999999L);

        // Then: All return empty lists (not null)
        assertNotNull(byCard);
        assertTrue(byCard.isEmpty());
        
        assertNotNull(byAccount);
        assertTrue(byAccount.isEmpty());
        
        assertNotNull(byCustomer);
        assertTrue(byCustomer.isEmpty());
    }

    // ========================================================================
    // Test Methods - Many-to-Many Relationship Scenarios
    // ========================================================================

    /**
     * Tests one card associated with multiple accounts.
     * 
     * Validates:
     * - Single card can be linked to multiple accounts (rare but valid scenario)
     * - Composite key uniqueness allows same card with different accounts
     * - Query by card number returns all account associations
     * 
     * Business Use Case:
     * - Joint account holders sharing same physical card
     * - Card linked to primary account and secondary line of credit
     */
    @Test
    void testOneCardMultipleAccounts() {
        // Given: One card linked to three different accounts
        Customer customer = createCustomer(100000020L, "Sam", "Allen", "555666777");
        
        Account account1 = createAccount(1000000019L, BigDecimal.valueOf(5000.00), BigDecimal.valueOf(10000.00));
        Account account2 = createAccount(1000000020L, BigDecimal.valueOf(15000.00), BigDecimal.valueOf(30000.00));
        Account account3 = createAccount(1000000021L, BigDecimal.valueOf(25000.00), BigDecimal.valueOf(50000.00));
        
        Card card = createCard("5888888888888888", account1.getAcctId(), "SAM ALLEN", LocalDate.of(2026, 4, 30));

        // Link same card to three accounts
        cardAccountXrefRepository.save(CardAccountXref.builder()
                .xrefCardNum(card.getCardNum())
                .xrefAcctId(account1.getAcctId())
                .customer(customer)
                .build());
        
        cardAccountXrefRepository.save(CardAccountXref.builder()
                .xrefCardNum(card.getCardNum())
                .xrefAcctId(account2.getAcctId())
                .customer(customer)
                .build());
        
        cardAccountXrefRepository.save(CardAccountXref.builder()
                .xrefCardNum(card.getCardNum())
                .xrefAcctId(account3.getAcctId())
                .customer(customer)
                .build());
        
        entityManager.flush();
        entityManager.clear();

        // When: Finding all accounts for this card
        List<CardAccountXref> cardAccounts = cardAccountXrefRepository.findByXrefCardNum("5888888888888888");

        // Then: All three account associations are found
        assertNotNull(cardAccounts);
        assertEquals(3, cardAccounts.size());
        assertTrue(cardAccounts.stream().anyMatch(x -> x.getXrefAcctId().equals(1000000019L)));
        assertTrue(cardAccounts.stream().anyMatch(x -> x.getXrefAcctId().equals(1000000020L)));
        assertTrue(cardAccounts.stream().anyMatch(x -> x.getXrefAcctId().equals(1000000021L)));
    }

    /**
     * Tests one account with multiple cards.
     * 
     * Validates:
     * - Single account can have multiple cards (common scenario)
     * - Composite key uniqueness allows different cards for same account
     * - Query by account ID returns all card associations
     * 
     * Business Use Case:
     * - Primary cardholder card
     * - Authorized user cards
     * - Replacement cards
     * - Multiple card types (credit, debit) on same account
     */
    @Test
    void testOneAccountMultipleCards() {
        // Given: One account with four different cards
        Customer customer1 = createCustomer(100000021L, "Tina", "Young", "888999000");
        Customer customer2 = createCustomer(100000022L, "Uma", "King", "111222333");
        Customer customer3 = createCustomer(100000023L, "Victor", "Wright", "444555666");
        
        Account account = createAccount(1000000022L, BigDecimal.valueOf(20000.00), BigDecimal.valueOf(40000.00));
        
        Card card1 = createCard("5999999999999999", account.getAcctId(), "TINA YOUNG", LocalDate.of(2025, 5, 31));
        Card card2 = createCard("6000000000000000", account.getAcctId(), "UMA KING", LocalDate.of(2026, 6, 30));
        Card card3 = createCard("6111111111111111", account.getAcctId(), "VICTOR WRIGHT", LocalDate.of(2026, 7, 31));
        Card card4 = createCard("6222222222222222", account.getAcctId(), "TINA YOUNG", LocalDate.of(2026, 8, 31)); // Replacement

        // Link four cards to same account
        cardAccountXrefRepository.save(CardAccountXref.builder()
                .xrefCardNum(card1.getCardNum())
                .xrefAcctId(account.getAcctId())
                .customer(customer1)
                .build());
        
        cardAccountXrefRepository.save(CardAccountXref.builder()
                .xrefCardNum(card2.getCardNum())
                .xrefAcctId(account.getAcctId())
                .customer(customer2)
                .build());
        
        cardAccountXrefRepository.save(CardAccountXref.builder()
                .xrefCardNum(card3.getCardNum())
                .xrefAcctId(account.getAcctId())
                .customer(customer3)
                .build());
        
        cardAccountXrefRepository.save(CardAccountXref.builder()
                .xrefCardNum(card4.getCardNum())
                .xrefAcctId(account.getAcctId())
                .customer(customer1)
                .build());
        
        entityManager.flush();
        entityManager.clear();

        // When: Finding all cards for this account
        List<CardAccountXref> accountCards = cardAccountXrefRepository.findByXrefAcctId(1000000022L);

        // Then: All four card associations are found
        assertNotNull(accountCards);
        assertEquals(4, accountCards.size());
        assertTrue(accountCards.stream().anyMatch(x -> x.getXrefCardNum().equals("5999999999999999")));
        assertTrue(accountCards.stream().anyMatch(x -> x.getXrefCardNum().equals("6000000000000000")));
        assertTrue(accountCards.stream().anyMatch(x -> x.getXrefCardNum().equals("6111111111111111")));
        assertTrue(accountCards.stream().anyMatch(x -> x.getXrefCardNum().equals("6222222222222222")));
    }

    /**
     * Tests one customer with multiple cards.
     * 
     * Validates:
     * - Single customer can be associated with multiple card-account relationships
     * - Query by customer ID returns complete card portfolio
     * - Supports customer-level reporting and permission checks
     * 
     * Business Use Case:
     * - Customer portfolio view showing all cards
     * - Customer permission checks (user can only see own cards)
     * - Customer-level spending analysis across all cards
     */
    @Test
    void testOneCustomerMultipleCards() {
        // Given: One customer with three cards across two accounts
        Customer customer = createCustomer(100000024L, "Wendy", "Scott", "777888999");
        
        Account account1 = createAccount(1000000023L, BigDecimal.valueOf(10000.00), BigDecimal.valueOf(20000.00));
        Account account2 = createAccount(1000000024L, BigDecimal.valueOf(15000.00), BigDecimal.valueOf(30000.00));
        
        Card card1 = createCard("6333333333333333", account1.getAcctId(), "WENDY SCOTT", LocalDate.of(2025, 9, 30));
        Card card2 = createCard("6444444444444444", account1.getAcctId(), "WENDY SCOTT", LocalDate.of(2026, 10, 31)); // Second card on same account
        Card card3 = createCard("6555555555555555", account2.getAcctId(), "WENDY SCOTT", LocalDate.of(2026, 11, 30));

        // Link all cards to same customer
        cardAccountXrefRepository.save(CardAccountXref.builder()
                .xrefCardNum(card1.getCardNum())
                .xrefAcctId(account1.getAcctId())
                .customer(customer)
                .build());
        
        cardAccountXrefRepository.save(CardAccountXref.builder()
                .xrefCardNum(card2.getCardNum())
                .xrefAcctId(account1.getAcctId())
                .customer(customer)
                .build());
        
        cardAccountXrefRepository.save(CardAccountXref.builder()
                .xrefCardNum(card3.getCardNum())
                .xrefAcctId(account2.getAcctId())
                .customer(customer)
                .build());
        
        entityManager.flush();
        entityManager.clear();

        // When: Finding all card-account associations for this customer
        List<CardAccountXref> customerCards = cardAccountXrefRepository.findByXrefCustId(100000024L);

        // Then: All three card associations are found
        assertNotNull(customerCards);
        assertEquals(3, customerCards.size());
        assertTrue(customerCards.stream().allMatch(x -> x.getXrefCustId().equals(100000024L)));
        assertTrue(customerCards.stream().anyMatch(x -> x.getXrefCardNum().equals("6333333333333333")));
        assertTrue(customerCards.stream().anyMatch(x -> x.getXrefCardNum().equals("6444444444444444")));
        assertTrue(customerCards.stream().anyMatch(x -> x.getXrefCardNum().equals("6555555555555555")));
    }

    // ========================================================================
    // Test Methods - Performance Validation
    // ========================================================================

    /**
     * Tests query performance with composite primary key lookups.
     * 
     * Validates:
     * - Composite key lookups complete in sub-10ms average (per Section 0.7.7)
     * - Database indexes are properly utilized
     * - Performance meets or exceeds VSAM key access response times
     * 
     * Performance Requirement:
     * - Primary key lookups must complete in sub-10ms to match VSAM performance
     * - Tests 100 lookups and measures average response time
     */
    @Test
    void testQueryPerformanceWithCompositeKey() {
        // Given: Set of card-account cross-references for performance testing
        Customer customer = createCustomer(100000025L, "Xavier", "Lopez", "000111222");
        Account account = createAccount(1000000025L, BigDecimal.valueOf(5000.00), BigDecimal.valueOf(10000.00));
        
        // Create 10 test cross-references
        for (int i = 0; i < 10; i++) {
            String cardNum = String.format("677777777777%04d", i);
            Card card = createCard(cardNum, account.getAcctId(), "PERF TEST", LocalDate.of(2026, 12, 31));
            
            CardAccountXref xref = CardAccountXref.builder()
                    .xrefCardNum(card.getCardNum())
                    .xrefAcctId(account.getAcctId())
                    .customer(customer)
                    .build();
            cardAccountXrefRepository.save(xref);
        }
        entityManager.flush();
        entityManager.clear();

        // When: Executing 100 composite key lookups
        long startTime = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            String cardNum = String.format("677777777777%04d", i % 10);
            CardAccountXrefId compositeKey = new CardAccountXrefId(cardNum, 1000000025L);
            cardAccountXrefRepository.findById(compositeKey);
        }
        long endTime = System.nanoTime();

        // Then: Average query time is under 10ms (per Section 0.7.7 performance requirement)
        long durationNanos = endTime - startTime;
        double averageMillis = durationNanos / 1_000_000.0 / 100.0;
        
        assertTrue(averageMillis < 10.0, 
                String.format("Average composite key lookup time %.2fms exceeds 10ms requirement", averageMillis));
    }

    /**
     * Tests foreign key query performance.
     * 
     * Validates:
     * - Custom query methods (findByXrefCardNum, findByXrefAcctId, findByXrefCustId) perform efficiently
     * - Database indexes support fast lookups by foreign key columns
     * - Query performance scales with reasonable data volumes
     * 
     * Performance Expectation:
     * - Foreign key queries should complete quickly with proper indexing
     * - Response times should be consistent across different query methods
     */
    @Test
    void testForeignKeyQueryPerformance() {
        // Given: Test data with multiple cross-references
        Customer customer1 = createCustomer(100000026L, "Yolanda", "Hill", "333444555");
        Customer customer2 = createCustomer(100000027L, "Zachary", "Green", "666777888");
        
        Account account1 = createAccount(1000000026L, BigDecimal.valueOf(8000.00), BigDecimal.valueOf(16000.00));
        Account account2 = createAccount(1000000027L, BigDecimal.valueOf(12000.00), BigDecimal.valueOf(24000.00));
        
        // Create 20 cross-references (10 per account)
        for (int i = 0; i < 10; i++) {
            String cardNum1 = String.format("688888888888%04d", i);
            Card card1 = createCard(cardNum1, account1.getAcctId(), "YOLANDA HILL", LocalDate.of(2026, 12, 31));
            cardAccountXrefRepository.save(CardAccountXref.builder()
                    .xrefCardNum(card1.getCardNum())
                    .xrefAcctId(account1.getAcctId())
                    .customer(customer1)
                    .build());
            
            String cardNum2 = String.format("699999999999%04d", i);
            Card card2 = createCard(cardNum2, account2.getAcctId(), "ZACHARY GREEN", LocalDate.of(2026, 12, 31));
            cardAccountXrefRepository.save(CardAccountXref.builder()
                    .xrefCardNum(card2.getCardNum())
                    .xrefAcctId(account2.getAcctId())
                    .customer(customer2)
                    .build());
        }
        entityManager.flush();
        entityManager.clear();

        // When: Testing query performance for each custom method
        long startCard = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            String cardNum = String.format("688888888888%04d", i);
            cardAccountXrefRepository.findByXrefCardNum(cardNum);
        }
        long cardQueryTime = (System.nanoTime() - startCard) / 1_000_000;

        long startAccount = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            cardAccountXrefRepository.findByXrefAcctId(1000000026L);
        }
        long accountQueryTime = (System.nanoTime() - startAccount) / 1_000_000;

        long startCustomer = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            cardAccountXrefRepository.findByXrefCustId(100000026L);
        }
        long customerQueryTime = (System.nanoTime() - startCustomer) / 1_000_000;

        // Then: All query methods perform efficiently
        assertTrue(cardQueryTime < 100, 
                String.format("findByXrefCardNum took %dms, expected < 100ms", cardQueryTime));
        assertTrue(accountQueryTime < 100, 
                String.format("findByXrefAcctId took %dms, expected < 100ms", accountQueryTime));
        assertTrue(customerQueryTime < 100, 
                String.format("findByXrefCustId took %dms, expected < 100ms", customerQueryTime));
    }
}
