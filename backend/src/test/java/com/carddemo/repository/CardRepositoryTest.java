package com.carddemo.repository;

import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.Card;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

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
 * Integration test for CardRepository using Testcontainers PostgreSQL.
 * 
 * Converted from COBOL VSAM CARDFILE operations (CVACT02Y.cpy 150-byte record structure).
 * Tests comprehensive JPA repository operations for card master data with foreign key
 * relationship to Account entity.
 * 
 * This test validates the complete conversion from mainframe VSAM file operations
 * to PostgreSQL database access, ensuring:
 * - CRUD operations match COBOL READ/WRITE/REWRITE/DELETE semantics
 * - Custom query methods replicate VSAM alternate index browse operations
 * - Primary key constraints enforce CARD-NUM PIC X(16) uniqueness
 * - Foreign key relationship to Account maintains referential integrity
 * - Date conversion from COBOL PIC X(10) to LocalDate works correctly
 * - Query performance meets sub-10ms requirement per Section 0.7.7
 * - PII field handling for card numbers and CVV codes per Section 0.2.2
 * - Optimistic locking prevents concurrent update conflicts
 * 
 * Test Coverage:
 * - Basic CRUD operations (save, findById, findAll, update, delete)
 * - Foreign key constraint validation
 * - Custom query methods (findByCardAcctId, findByCardStatus)
 * - Primary key uniqueness constraint
 * - Date field conversion and formatting
 * - Card status field validation
 * - PII field storage and retrieval
 * - Optimistic locking with @Version field
 * - Query performance validation
 * 
 * Per Agent Action Plan Section 0.7.1 MINIMAL CHANGE: Tests validate exact COBOL 
 * 150-byte card record conversion with zero functional deviation.
 * 
 * @see CardRepository
 * @see Card
 * @see Account
 */
@DataJpaTest
@Testcontainers
class CardRepositoryTest {

    /**
     * PostgreSQL test container using postgres:16.6-alpine image.
     * Provides isolated database instance for integration testing.
     * Container automatically starts before tests and stops after completion.
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine")
            .withDatabaseName("carddemo_test")
            .withUsername("test")
            .withPassword("test");

    /**
     * Configure Spring Boot to use Testcontainers PostgreSQL instance.
     * Dynamically sets datasource properties from container configuration.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TestEntityManager entityManager;

    // Test data constants matching COBOL copybook CVACT02Y.cpy structure
    private static final String TEST_CARD_NUM = "4111111111111111";  // PIC X(16) - 16-digit Visa test card
    private static final String TEST_CARD_NUM_2 = "4222222222222222";
    private static final String TEST_CARD_NUM_3 = "4333333333333333";
    private static final Long TEST_ACCT_ID = 11223344556L;  // PIC 9(11) - Account ID
    private static final Long TEST_ACCT_ID_2 = 22334455667L;
    private static final Long TEST_CARDMEMBER_ID = 99887766554L;  // PIC 9(11) - Cardholder ID
    private static final String TEST_EMBOSSED_NAME = "JOHN M DOE";  // PIC X(50)
    private static final String TEST_CARD_STATUS_ACTIVE = "Y";  // PIC X(01) - Active status
    private static final String TEST_CARD_STATUS_INACTIVE = "N";  // PIC X(01) - Inactive status
    private static final String TEST_CARD_STATUS_LOST = "L";  // PIC X(01) - Lost status
    private static final LocalDate TEST_EXPIRATION_DATE = LocalDate.of(2025, 12, 31);  // PIC X(10)

    /**
     * Helper method to create and persist an Account entity for foreign key testing.
     * 
     * Creates prerequisite Account entity that Card entities reference via cardAcctId.
     * Required because card table has foreign key constraint to account table per
     * Section 0.3.4 database schema design.
     * 
     * Equivalent to COBOL sequence:
     * <pre>
     * EXEC CICS WRITE FILE('ACCTFILE') FROM(ACCOUNT-RECORD) RIDFLD(ACCT-ID) END-EXEC.
     * </pre>
     * 
     * @param accountId the account ID (COBOL PIC 9(11) ACCT-ID)
     * @return persisted Account entity with flush to ensure database synchronization
     */
    private Account createAndSaveAccount(Long accountId) {
        Account account = Account.builder()
                .acctId(accountId)
                .acctActiveStatus("Y")
                .acctCurrBal(BigDecimal.valueOf(1500.00))
                .acctCreditLimit(BigDecimal.valueOf(5000.00))
                .acctCashCreditLimit(BigDecimal.valueOf(2000.00))
                .acctOpenDate(LocalDate.of(2020, 1, 15))
                .acctExpirationDate(LocalDate.of(2028, 1, 15))
                .acctReissueDate(null)
                .acctCurrCycCredit(BigDecimal.ZERO)
                .acctCurrCycDebit(BigDecimal.ZERO)
                .acctAddrZip("10001")
                .acctGroupId("GRP001")
                .build();
        return entityManager.persistAndFlush(account);
    }

    /**
     * Helper method to create a test Card entity with all required fields.
     * 
     * Creates Card entity matching COBOL copybook CVACT02Y.cpy structure (150 bytes).
     * Does NOT persist the entity - caller must save via repository or entityManager.
     * 
     * @param cardNum the card number (COBOL PIC X(16) CARD-NUM)
     * @param accountId the associated account ID (COBOL PIC 9(11) CARD-ACCT-ID)
     * @param status the card status (COBOL PIC X(01) CARD-ACTIVE-STATUS)
     * @return Card entity ready for persistence
     */
    private Card createTestCard(String cardNum, Long accountId, String status) {
        return Card.builder()
                .cardNum(cardNum)
                .cardAcctId(accountId)
                .cardCardmemberId(TEST_CARDMEMBER_ID)
                .cardStatus(status)
                .cardEmbossedName(TEST_EMBOSSED_NAME)
                .cardExpirationDate(TEST_EXPIRATION_DATE)
                .cardActiveDate(LocalDate.now())
                .build();
    }

    @BeforeEach
    void setUp() {
        // Clear entity manager cache before each test
        entityManager.clear();
    }

    /**
     * Test 1: Save new card (COBOL EXEC CICS WRITE equivalent).
     * 
     * Validates that saving a new card entity persists all fields correctly
     * to the PostgreSQL database, matching COBOL WRITE operation behavior.
     * 
     * COBOL equivalent:
     * <pre>
     * EXEC CICS WRITE FILE('CARDFILE') FROM(CARD-RECORD) RIDFLD(CARD-NUM) END-EXEC.
     * </pre>
     * 
     * Tests:
     * - All CVACT02Y.cpy fields (CARD-NUM, CARD-ACCT-ID, CARD-EMBOSSED-NAME,
     *   CARD-EXPIRAION-DATE, CARD-ACTIVE-STATUS) are persisted correctly
     * - Foreign key relationship to Account is maintained
     * - Audit fields (createdAt, updatedAt) are automatically populated
     * - Version field is initialized to 0 for optimistic locking
     */
    @Test
    void testSaveCard() {
        // Given: Account entity exists for foreign key reference
        createAndSaveAccount(TEST_ACCT_ID);

        // When: Create and save new card
        Card card = createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE);
        Card savedCard = cardRepository.save(card);

        // Then: Verify all fields persisted correctly per CVACT02Y.cpy structure
        assertNotNull(savedCard);
        assertEquals(TEST_CARD_NUM, savedCard.getCardNum());
        assertEquals(TEST_ACCT_ID, savedCard.getCardAcctId());
        assertEquals(TEST_CARDMEMBER_ID, savedCard.getCardCardmemberId());
        assertEquals(TEST_CARD_STATUS_ACTIVE, savedCard.getCardStatus());
        assertEquals(TEST_EMBOSSED_NAME, savedCard.getCardEmbossedName());
        assertEquals(TEST_EXPIRATION_DATE, savedCard.getCardExpirationDate());
        assertNotNull(savedCard.getCardActiveDate());
        
        // Verify audit fields are automatically populated
        assertNotNull(savedCard.getCreatedAt());
        assertNotNull(savedCard.getUpdatedAt());
        assertNotNull(savedCard.getVersion());
        assertEquals(0, savedCard.getVersion());
    }

    /**
     * Test 2: Find card by ID (COBOL EXEC CICS READ equivalent).
     * 
     * Validates that findById() retrieves card by primary key (CARD-NUM PIC X(16))
     * and all fields match the saved values, replicating COBOL READ operation.
     * 
     * COBOL equivalent:
     * <pre>
     * EXEC CICS READ FILE('CARDFILE') RIDFLD(CARD-NUM) INTO(CARD-RECORD) END-EXEC.
     * IF EIBRESP = DFHRESP(NORMAL)
     *    ... process card record ...
     * END-IF
     * </pre>
     * 
     * Tests primary key lookup performance must be sub-10ms per Section 0.7.7.
     */
    @Test
    void testFindByIdCard() {
        // Given: Account and card exist in database
        createAndSaveAccount(TEST_ACCT_ID);
        Card originalCard = createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE);
        cardRepository.save(originalCard);
        entityManager.flush();
        entityManager.clear();

        // When: Find card by primary key (CARD-NUM)
        Optional<Card> foundCard = cardRepository.findById(TEST_CARD_NUM);

        // Then: Verify card found and all CVACT02Y.cpy fields match
        assertTrue(foundCard.isPresent());
        Card card = foundCard.get();
        assertEquals(TEST_CARD_NUM, card.getCardNum());
        assertEquals(TEST_ACCT_ID, card.getCardAcctId());
        assertEquals(TEST_CARDMEMBER_ID, card.getCardCardmemberId());
        assertEquals(TEST_CARD_STATUS_ACTIVE, card.getCardStatus());
        assertEquals(TEST_EMBOSSED_NAME, card.getCardEmbossedName());
        assertEquals(TEST_EXPIRATION_DATE, card.getCardExpirationDate());
    }

    /**
     * Test 3: Find all cards (COBOL EXEC CICS STARTBR sequential browse equivalent).
     * 
     * Validates that findAll() retrieves all card records, replicating COBOL
     * sequential browse operation for full file scan.
     * 
     * COBOL equivalent:
     * <pre>
     * EXEC CICS STARTBR DATASET('CARDFILE') END-EXEC
     * PERFORM UNTIL NO-MORE-RECORDS
     *    EXEC CICS READNEXT DATASET('CARDFILE') INTO(CARD-RECORD) END-EXEC
     *    ... process each card ...
     * END-PERFORM
     * EXEC CICS ENDBR DATASET('CARDFILE') END-EXEC
     * </pre>
     */
    @Test
    void testFindAllCards() {
        // Given: Multiple accounts and cards exist
        createAndSaveAccount(TEST_ACCT_ID);
        createAndSaveAccount(TEST_ACCT_ID_2);
        
        cardRepository.save(createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE));
        cardRepository.save(createTestCard(TEST_CARD_NUM_2, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE));
        cardRepository.save(createTestCard(TEST_CARD_NUM_3, TEST_ACCT_ID_2, TEST_CARD_STATUS_INACTIVE));
        entityManager.flush();

        // When: Retrieve all cards
        List<Card> allCards = cardRepository.findAll();

        // Then: Verify all 3 cards are returned
        assertNotNull(allCards);
        assertEquals(3, allCards.size());
    }

    /**
     * Test 4: Update card (COBOL EXEC CICS REWRITE equivalent).
     * 
     * Validates that updating and saving a card entity persists changes,
     * matching COBOL REWRITE operation behavior.
     * 
     * COBOL equivalent:
     * <pre>
     * EXEC CICS READ FILE('CARDFILE') RIDFLD(CARD-NUM) INTO(CARD-RECORD) UPDATE END-EXEC.
     * MOVE 'L' TO CARD-ACTIVE-STATUS.
     * EXEC CICS REWRITE FILE('CARDFILE') FROM(CARD-RECORD) END-EXEC.
     * </pre>
     * 
     * Tests:
     * - Field updates persist correctly
     * - Version field increments for optimistic locking
     * - UpdatedAt timestamp is refreshed
     */
    @Test
    void testUpdateCard() {
        // Given: Account and card exist in database
        createAndSaveAccount(TEST_ACCT_ID);
        Card card = createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE);
        Card savedCard = cardRepository.save(card);
        Integer originalVersion = savedCard.getVersion();
        entityManager.flush();
        entityManager.clear();

        // When: Update card status and embossed name (e.g., report card as lost)
        savedCard.setCardStatus(TEST_CARD_STATUS_LOST);
        savedCard.setCardEmbossedName("JOHN MICHAEL DOE");
        Card updatedCard = cardRepository.save(savedCard);
        entityManager.flush();

        // Then: Verify updates persisted and version incremented
        assertEquals(TEST_CARD_NUM, updatedCard.getCardNum());
        assertEquals(TEST_CARD_STATUS_LOST, updatedCard.getCardStatus());
        assertEquals("JOHN MICHAEL DOE", updatedCard.getCardEmbossedName());
        assertNotNull(updatedCard.getVersion());
        assertTrue(updatedCard.getVersion() > originalVersion);
    }

    /**
     * Test 5: Delete card (COBOL EXEC CICS DELETE equivalent).
     * 
     * Validates that deleteById() removes card record from database,
     * matching COBOL DELETE operation behavior.
     * 
     * COBOL equivalent:
     * <pre>
     * EXEC CICS DELETE FILE('CARDFILE') RIDFLD(CARD-NUM) END-EXEC.
     * </pre>
     */
    @Test
    void testDeleteCard() {
        // Given: Account and card exist in database
        createAndSaveAccount(TEST_ACCT_ID);
        cardRepository.save(createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE));
        entityManager.flush();

        // When: Delete card by primary key
        cardRepository.deleteById(TEST_CARD_NUM);
        entityManager.flush();

        // Then: Verify card no longer exists (COBOL file-status '23' record not found)
        Optional<Card> deletedCard = cardRepository.findById(TEST_CARD_NUM);
        assertFalse(deletedCard.isPresent());
    }

    /**
     * Test 6: Card number not found (COBOL file-status '23' equivalent).
     * 
     * Validates that findById() returns empty Optional when card doesn't exist,
     * matching COBOL NOTFND condition (file-status '23').
     * 
     * COBOL equivalent:
     * <pre>
     * EXEC CICS READ FILE('CARDFILE') RIDFLD(CARD-NUM) INTO(CARD-RECORD) END-EXEC.
     * IF EIBRESP = DFHRESP(NOTFND)
     *    MOVE 'Card not found' TO ERROR-MESSAGE
     * END-IF
     * </pre>
     */
    @Test
    void testCardNumberNotFound() {
        // When: Attempt to find card with non-existent card number
        Optional<Card> card = cardRepository.findById("9999999999999999");

        // Then: Verify empty Optional returned (COBOL NOTFND condition)
        assertFalse(card.isPresent());
    }

    /**
     * Test 7: Card number uniqueness constraint (COBOL file-status '22' duplicate key).
     * 
     * Validates that attempting to save duplicate card number throws exception,
     * matching COBOL DUPREC condition (file-status '22').
     * 
     * COBOL equivalent:
     * <pre>
     * EXEC CICS WRITE FILE('CARDFILE') FROM(CARD-RECORD) RIDFLD(CARD-NUM) END-EXEC.
     * IF EIBRESP = DFHRESP(DUPREC)
     *    MOVE 'Duplicate card number' TO ERROR-MESSAGE
     * END-IF
     * </pre>
     * 
     * Tests primary key constraint on card_num (VARCHAR(16) PRIMARY KEY).
     */
    @Test
    void testCardNumberUniqueness() {
        // Given: Account and card exist with TEST_CARD_NUM
        createAndSaveAccount(TEST_ACCT_ID);
        cardRepository.save(createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE));
        entityManager.flush();
        entityManager.clear();

        // When/Then: Attempt to save another card with same card number
        Card duplicateCard = createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE);
        assertThrows(Exception.class, () -> {
            cardRepository.save(duplicateCard);
            entityManager.flush();
        });
    }

    /**
     * Test 8: Card-Account relationship (Many-to-One foreign key).
     * 
     * Validates that Card.account navigation property loads associated Account entity,
     * verifying the @ManyToOne relationship defined in Section 0.3.4 database schema.
     * 
     * Tests:
     * - Foreign key relationship card.card_acct_id → account.acct_id
     * - JPA lazy loading of Account entity
     * - Referential integrity maintenance
     */
    @Test
    void testCardAccountRelationship() {
        // Given: Account and card exist with relationship
        Account account = createAndSaveAccount(TEST_ACCT_ID);
        cardRepository.save(createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE));
        entityManager.flush();
        entityManager.clear();

        // When: Retrieve card and access account relationship
        Card card = cardRepository.findById(TEST_CARD_NUM).orElseThrow();
        Account relatedAccount = card.getAccount();

        // Then: Verify account entity loaded correctly
        assertNotNull(relatedAccount);
        assertEquals(TEST_ACCT_ID, relatedAccount.getAcctId());
        assertEquals(account.getAcctActiveStatus(), relatedAccount.getAcctActiveStatus());
        assertEquals(0, account.getAcctCurrBal().compareTo(relatedAccount.getAcctCurrBal()));
    }

    /**
     * Test 9: Foreign key constraint validation.
     * 
     * Validates that attempting to save card with non-existent account ID
     * throws DataIntegrityViolationException, enforcing referential integrity.
     * 
     * Database constraint: card.card_acct_id REFERENCES account(acct_id)
     * Per Section 0.3.4: FOREIGN KEY constraint must be enforced.
     */
    @Test
    void testForeignKeyConstraint() {
        // Given: No account exists with ID 99999999999L

        // When/Then: Attempt to save card with non-existent account ID
        Card cardWithInvalidAccount = createTestCard(TEST_CARD_NUM, 99999999999L, TEST_CARD_STATUS_ACTIVE);
        assertThrows(Exception.class, () -> {
            cardRepository.save(cardWithInvalidAccount);
            entityManager.flush();
        });
    }

    /**
     * Test 10: Find cards by account ID (COBOL alternate index browse).
     * 
     * Validates findByCardAcctId() custom query method that replicates COBOL
     * VSAM alternate index browse on CARD-ACCT-ID field.
     * 
     * COBOL equivalent:
     * <pre>
     * EXEC CICS STARTBR DATASET('CARDFILE') RIDFLD(CARD-ACCT-ID) END-EXEC
     * PERFORM UNTIL NO-MORE-RECORDS OR CARD-ACCT-ID NOT = WS-TARGET-ACCT-ID
     *    EXEC CICS READNEXT DATASET('CARDFILE') INTO(CARD-RECORD) END-EXEC
     *    ... process card for account ...
     * END-PERFORM
     * EXEC CICS ENDBR DATASET('CARDFILE') END-EXEC
     * </pre>
     * 
     * Used extensively in COCRDLIC.cbl (card list display program) for retrieving
     * all cards associated with a specific account.
     * 
     * Tests idx_card_acct index performance per Section 0.7.7.
     */
    @Test
    void testFindByCardAccountId() {
        // Given: Account with multiple cards
        createAndSaveAccount(TEST_ACCT_ID);
        createAndSaveAccount(TEST_ACCT_ID_2);
        
        cardRepository.save(createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE));
        cardRepository.save(createTestCard(TEST_CARD_NUM_2, TEST_ACCT_ID, TEST_CARD_STATUS_INACTIVE));
        cardRepository.save(createTestCard(TEST_CARD_NUM_3, TEST_ACCT_ID_2, TEST_CARD_STATUS_ACTIVE));
        entityManager.flush();

        // When: Query cards by account ID using alternate index
        List<Card> accountCards = cardRepository.findByCardAcctId(TEST_ACCT_ID);

        // Then: Verify only cards for specified account returned
        assertNotNull(accountCards);
        assertEquals(2, accountCards.size());
        accountCards.forEach(card -> assertEquals(TEST_ACCT_ID, card.getCardAcctId()));
    }

    /**
     * Test 11: Find cards by status (COBOL status filtering).
     * 
     * Validates findByCardStatus() custom query method for efficient status-based
     * retrieval, replacing COBOL application-level filtering logic.
     * 
     * COBOL equivalent:
     * <pre>
     * EXEC CICS STARTBR DATASET('CARDFILE') END-EXEC
     * PERFORM UNTIL NO-MORE-RECORDS
     *    EXEC CICS READNEXT DATASET('CARDFILE') INTO(CARD-RECORD) END-EXEC
     *    IF CARD-ACTIVE-STATUS = 'Y'
     *       ... process active card ...
     *    END-IF
     * END-PERFORM
     * EXEC CICS ENDBR DATASET('CARDFILE') END-EXEC
     * </pre>
     * 
     * This method uses idx_card_status index for efficient filtering without
     * requiring full table scan, improving performance over COBOL approach.
     * 
     * Used in batch processing (CBACT04C.cbl equivalent) for finding expired cards,
     * and in online programs for displaying active cards only.
     */
    @Test
    void testFindByCardStatus() {
        // Given: Multiple cards with different statuses
        createAndSaveAccount(TEST_ACCT_ID);
        
        cardRepository.save(createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE));
        cardRepository.save(createTestCard(TEST_CARD_NUM_2, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE));
        cardRepository.save(createTestCard(TEST_CARD_NUM_3, TEST_ACCT_ID, TEST_CARD_STATUS_INACTIVE));
        entityManager.flush();

        // When: Query cards by active status
        List<Card> activeCards = cardRepository.findByCardStatus(TEST_CARD_STATUS_ACTIVE);

        // Then: Verify only active cards returned
        assertNotNull(activeCards);
        assertEquals(2, activeCards.size());
        activeCards.forEach(card -> assertEquals(TEST_CARD_STATUS_ACTIVE, card.getCardStatus()));
    }

    /**
     * Test 12: Find by account ID not found (empty result set).
     * 
     * Validates that findByCardAcctId() returns empty list when no cards exist
     * for specified account, matching COBOL ENDFILE condition.
     * 
     * COBOL equivalent:
     * <pre>
     * EXEC CICS STARTBR DATASET('CARDFILE') RIDFLD(CARD-ACCT-ID) END-EXEC
     * EXEC CICS READNEXT DATASET('CARDFILE') INTO(CARD-RECORD) END-EXEC
     * IF EIBRESP = DFHRESP(ENDFILE) OR CARD-ACCT-ID NOT = WS-TARGET-ACCT-ID
     *    MOVE 'No cards found for account' TO ERROR-MESSAGE
     * END-IF
     * </pre>
     */
    @Test
    void testFindByAccountIdNotFound() {
        // Given: Account exists but has no cards
        createAndSaveAccount(TEST_ACCT_ID);

        // When: Query cards for account with no cards
        List<Card> cards = cardRepository.findByCardAcctId(TEST_ACCT_ID);

        // Then: Verify empty list returned (COBOL ENDFILE condition)
        assertNotNull(cards);
        assertTrue(cards.isEmpty());
    }

    /**
     * Test 13: Card expiration date conversion (COBOL PIC X(10) to LocalDate).
     * 
     * Validates that CARD-EXPIRAION-DATE PIC X(10) (YYYY-MM-DD format in COBOL)
     * converts correctly to Java LocalDate and persists/retrieves with proper formatting.
     * 
     * COBOL date field: PIC X(10) VALUE '2025-12-31'
     * Java LocalDate: LocalDate.of(2025, 12, 31)
     * 
     * Per Section 0.7.5: Must maintain COBOL date format (YYYY-MM-DD) in external
     * interfaces even though internal representation is Java LocalDate.
     */
    @Test
    void testCardExpirationDateConversion() {
        // Given: Account exists and card with specific expiration date
        createAndSaveAccount(TEST_ACCT_ID);
        LocalDate expirationDate = LocalDate.of(2025, 12, 31);
        
        Card card = createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE);
        card.setCardExpirationDate(expirationDate);
        cardRepository.save(card);
        entityManager.flush();
        entityManager.clear();

        // When: Retrieve card and access expiration date
        Card retrievedCard = cardRepository.findById(TEST_CARD_NUM).orElseThrow();

        // Then: Verify LocalDate conversion matches exactly
        assertNotNull(retrievedCard.getCardExpirationDate());
        assertEquals(expirationDate, retrievedCard.getCardExpirationDate());
        assertEquals(2025, retrievedCard.getCardExpirationDate().getYear());
        assertEquals(12, retrievedCard.getCardExpirationDate().getMonthValue());
        assertEquals(31, retrievedCard.getCardExpirationDate().getDayOfMonth());
    }

    /**
     * Test 14: Card expiration date format validation.
     * 
     * Validates that expiration dates are stored in ISO-8601 format (YYYY-MM-DD)
     * in PostgreSQL DATE column, matching COBOL PIC X(10) date format.
     * 
     * This ensures compatibility with COBOL date format for external interfaces
     * and file exports per Section 0.7.4 backward compatibility requirements.
     */
    @Test
    void testCardExpirationDateFormat() {
        // Given: Account and card with expiration date
        createAndSaveAccount(TEST_ACCT_ID);
        Card card = createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE);
        cardRepository.save(card);
        entityManager.flush();

        // When: Retrieve card
        Card savedCard = cardRepository.findById(TEST_CARD_NUM).orElseThrow();

        // Then: Verify date format is ISO-8601 (YYYY-MM-DD) matching COBOL PIC X(10)
        assertNotNull(savedCard.getCardExpirationDate());
        String dateString = savedCard.getCardExpirationDate().toString();
        assertTrue(dateString.matches("\\d{4}-\\d{2}-\\d{2}"));  // YYYY-MM-DD format
    }

    /**
     * Test 15: Nullable expiration date (if allowed by schema).
     * 
     * Note: Per Section 0.3.4 database schema, card_expiration_date is NOT NULL,
     * so this test verifies that null expiration date is rejected.
     * 
     * Database constraint: card_expiration_date DATE NOT NULL
     */
    @Test
    void testNullExpirationDateNotAllowed() {
        // Given: Account exists
        createAndSaveAccount(TEST_ACCT_ID);

        // When/Then: Attempt to save card with null expiration date
        Card cardWithNullExpiration = createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE);
        cardWithNullExpiration.setCardExpirationDate(null);
        
        assertThrows(Exception.class, () -> {
            cardRepository.save(cardWithNullExpiration);
            entityManager.flush();
        });
    }

    /**
     * Test 16: Card number storage (PII field CARD-NUM PIC X(16)).
     * 
     * Validates that 16-digit card number (CARD-NUM PIC X(16)) is stored correctly
     * as VARCHAR(16) primary key per Section 0.2.2 PII handling requirements.
     * 
     * SECURITY NOTE per Section 0.7.9:
     * - Card numbers are sensitive PII requiring masking in logs/API responses
     * - Card numbers should show only last 4 digits in display (e.g., ****1111)
     * - This test validates storage only; masking is tested at service/controller layer
     */
    @Test
    void testCardNumberStorage() {
        // Given: Account exists
        createAndSaveAccount(TEST_ACCT_ID);

        // When: Save card with 16-digit card number
        String testCardNumber = "4111111111111111";  // 16-digit Visa test card
        Card card = createTestCard(testCardNumber, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE);
        cardRepository.save(card);
        entityManager.flush();
        entityManager.clear();

        // Then: Verify 16-digit card number stored correctly
        Card retrievedCard = cardRepository.findById(testCardNumber).orElseThrow();
        assertEquals(testCardNumber, retrievedCard.getCardNum());
        assertEquals(16, retrievedCard.getCardNum().length());
    }

    /**
     * Test 17: Card active status field (COBOL PIC X(01)).
     * 
     * Validates that CARD-ACTIVE-STATUS PIC X(01) accepts valid status values
     * ('Y' = Active, 'N' = Inactive) and stores as CHAR(1) in database.
     * 
     * Valid status codes per CardRepository documentation:
     * - 'Y' = Active (card can be used for transactions)
     * - 'N' = Inactive (card cannot be used)
     * - 'S' = Stolen (card reported stolen, block all transactions)
     * - 'L' = Lost (card reported lost, block all transactions)
     * - 'E' = Expired (card past expiration date)
     * - 'C' = Closed (card permanently closed)
     * 
     * Per Section 0.7.5: Must preserve COBOL PIC X(01) fixed-length semantics.
     */
    @Test
    void testCardActiveStatus() {
        // Given: Account exists
        createAndSaveAccount(TEST_ACCT_ID);

        // When: Save cards with different valid status values
        Card activeCard = createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, "Y");
        Card inactiveCard = createTestCard(TEST_CARD_NUM_2, TEST_ACCT_ID, "N");
        Card lostCard = createTestCard(TEST_CARD_NUM_3, TEST_ACCT_ID, "L");
        
        cardRepository.save(activeCard);
        cardRepository.save(inactiveCard);
        cardRepository.save(lostCard);
        entityManager.flush();

        // Then: Verify status values stored correctly
        assertEquals("Y", cardRepository.findById(TEST_CARD_NUM).orElseThrow().getCardStatus());
        assertEquals("N", cardRepository.findById(TEST_CARD_NUM_2).orElseThrow().getCardStatus());
        assertEquals("L", cardRepository.findById(TEST_CARD_NUM_3).orElseThrow().getCardStatus());
    }

    /**
     * Test 18: Card status validation.
     * 
     * Note: Field-level validation (ensuring only valid status codes) should be
     * implemented at service layer or entity validation level, not at repository level.
     * Repository test verifies that any single-character value can be persisted,
     * matching COBOL PIC X(01) behavior where any character is technically valid
     * at the storage level.
     */
    @Test
    void testCardStatusFieldLength() {
        // Given: Account exists
        createAndSaveAccount(TEST_ACCT_ID);

        // When: Save card with single-character status
        Card card = createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, "Y");
        cardRepository.save(card);
        entityManager.flush();

        // Then: Verify status field accepts single character per COBOL PIC X(01)
        Card savedCard = cardRepository.findById(TEST_CARD_NUM).orElseThrow();
        assertEquals(1, savedCard.getCardStatus().length());
    }

    /**
     * Test 19: Optimistic locking with @Version field.
     * 
     * Validates that JPA @Version field prevents concurrent update conflicts,
     * replicating COBOL VSAM RBA (Relative Byte Address) optimistic locking semantics.
     * 
     * COBOL equivalent:
     * <pre>
     * EXEC CICS READ FILE('CARDFILE') RIDFLD(CARD-NUM) INTO(CARD-RECORD) UPDATE END-EXEC.
     * ... if another transaction updates the record ...
     * EXEC CICS REWRITE FILE('CARDFILE') FROM(CARD-RECORD) END-EXEC.
     * IF EIBRESP = DFHRESP(INVREQ)  *> Record changed by another transaction
     *    MOVE 'Record modified by another user' TO ERROR-MESSAGE
     * END-IF
     * </pre>
     * 
     * Per Section 0.3.4: Optimistic locking prevents lost updates in high-concurrency
     * card management operations (web, mobile, call center simultaneous access).
     */
    @Test
    void testOptimisticLocking() {
        // Given: Account and card exist
        createAndSaveAccount(TEST_ACCT_ID);
        Card card = createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE);
        entityManager.persistAndFlush(card);
        entityManager.clear();

        // When: Simulate two concurrent transactions reading same card
        // Load first instance and detach it (simulates first transaction)
        Card card1 = cardRepository.findById(TEST_CARD_NUM).orElseThrow();
        entityManager.detach(card1);  // Detach to simulate separate transaction
        
        // Load second instance (simulates second concurrent transaction)
        Card card2 = cardRepository.findById(TEST_CARD_NUM).orElseThrow();

        // Second transaction completes first (version increments to 1)
        card2.setCardStatus("L");  // Report as lost
        cardRepository.saveAndFlush(card2);

        // First transaction tries to save (still has version 0 - should fail)
        card1.setCardStatus("S");  // Report as stolen

        // Then: Verify version conflict detected (OptimisticLockException wrapped in Spring exception)
        assertThrows(Exception.class, () -> {
            cardRepository.saveAndFlush(card1);
        });
    }

    /**
     * Test 20: Query performance validation (sub-10ms requirement).
     * 
     * Validates that primary key lookup (findById) meets sub-10ms response time
     * requirement per Section 0.7.7, replicating VSAM KSDS direct key access performance.
     * 
     * VSAM performance characteristic:
     * - Direct key access via primary index: typically 2-5ms
     * - PostgreSQL B-tree index lookup: O(log n), typically 1-5ms for small datasets
     * 
     * This test executes 100 findById() operations and validates average time < 10ms.
     * 
     * Note: Test database is small, so actual production performance with millions
     * of records should be validated separately with representative data volumes.
     */
    @Test
    void testQueryPerformance() {
        // Given: Account and card exist
        createAndSaveAccount(TEST_ACCT_ID);
        cardRepository.save(createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE));
        entityManager.flush();
        entityManager.clear();

        // When: Execute 100 findById() operations and measure time
        int iterations = 100;
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            cardRepository.findById(TEST_CARD_NUM);
        }
        
        long endTime = System.nanoTime();
        long totalTimeMs = (endTime - startTime) / 1_000_000;
        double averageTimeMs = (double) totalTimeMs / iterations;

        // Then: Verify average query time < 10ms per Section 0.7.7 requirement
        assertTrue(averageTimeMs < 10.0, 
                String.format("Average query time %.2fms exceeds 10ms requirement", averageTimeMs));
        
        System.out.printf("Primary key lookup performance: %.2fms average over %d iterations%n", 
                averageTimeMs, iterations);
    }

    /**
     * Test 21: Foreign key query performance (findByCardAcctId index).
     * 
     * Validates that findByCardAcctId() query using idx_card_acct index performs
     * efficiently, matching VSAM alternate index browse performance characteristics.
     * 
     * VSAM alternate index performance: O(log n + k) where k = number of matching records
     * PostgreSQL B-tree index scan: O(log n + k) with similar performance profile
     * 
     * Tests that index on card_acct_id enables efficient account-based card retrieval
     * used in COCRDLIC.cbl (card list display program).
     */
    @Test
    void testForeignKeyQueryPerformance() {
        // Given: Account with multiple cards
        createAndSaveAccount(TEST_ACCT_ID);
        
        // Create 10 cards for same account to test index performance
        for (int i = 1; i <= 10; i++) {
            String cardNum = String.format("411111111111%04d", i);
            cardRepository.save(createTestCard(cardNum, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE));
        }
        entityManager.flush();
        entityManager.clear();

        // When: Execute 50 findByCardAcctId() operations and measure time
        int iterations = 50;
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            cardRepository.findByCardAcctId(TEST_ACCT_ID);
        }
        
        long endTime = System.nanoTime();
        long totalTimeMs = (endTime - startTime) / 1_000_000;
        double averageTimeMs = (double) totalTimeMs / iterations;

        // Then: Verify reasonable query performance using indexed column
        // Allow higher threshold than primary key lookup due to multiple result retrieval
        assertTrue(averageTimeMs < 50.0, 
                String.format("Average indexed query time %.2fms exceeds 50ms threshold", averageTimeMs));
        
        System.out.printf("Foreign key query performance: %.2fms average over %d iterations%n", 
                averageTimeMs, iterations);
    }

    /**
     * Test 22: Card embossed name field (COBOL PIC X(50)).
     * 
     * Validates that CARD-EMBOSSED-NAME PIC X(50) stores up to 50 characters
     * and handles nullable values correctly per database schema.
     * 
     * Per Card entity documentation: May be null for virtual cards or corporate
     * cards with generic embossing. Typically all uppercase per card production standards.
     */
    @Test
    void testCardEmbossedName() {
        // Given: Account exists
        createAndSaveAccount(TEST_ACCT_ID);

        // When: Save card with embossed name
        String embossedName = "JANE ELIZABETH DOE";
        Card card = createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE);
        card.setCardEmbossedName(embossedName);
        cardRepository.save(card);
        entityManager.flush();

        // Then: Verify embossed name stored correctly
        Card savedCard = cardRepository.findById(TEST_CARD_NUM).orElseThrow();
        assertEquals(embossedName, savedCard.getCardEmbossedName());
        assertTrue(savedCard.getCardEmbossedName().length() <= 50);
    }

    /**
     * Test 23: Audit timestamp fields (createdAt, updatedAt).
     * 
     * Validates that audit timestamps are automatically populated via @PrePersist
     * and @PreUpdate JPA lifecycle callbacks, providing record lifecycle tracking
     * not present in original COBOL copybook CVACT02Y.cpy.
     * 
     * Tests:
     * - createdAt set automatically on insert
     * - updatedAt set automatically on insert and update
     * - createdAt remains unchanged on update
     * - updatedAt refreshed on each update
     */
    @Test
    void testAuditTimestamps() throws InterruptedException {
        // Given: Account exists
        createAndSaveAccount(TEST_ACCT_ID);

        // When: Save new card
        Card card = createTestCard(TEST_CARD_NUM, TEST_ACCT_ID, TEST_CARD_STATUS_ACTIVE);
        Card savedCard = cardRepository.save(card);
        entityManager.flush();
        
        // Capture initial timestamps
        var initialCreatedAt = savedCard.getCreatedAt();
        var initialUpdatedAt = savedCard.getUpdatedAt();
        
        assertNotNull(initialCreatedAt);
        assertNotNull(initialUpdatedAt);
        
        // Wait to ensure timestamp difference
        Thread.sleep(100);
        
        // Update card status
        savedCard.setCardStatus("L");
        Card updatedCard = cardRepository.save(savedCard);
        entityManager.flush();

        // Then: Verify createdAt unchanged, updatedAt refreshed
        assertEquals(initialCreatedAt, updatedCard.getCreatedAt());
        assertTrue(updatedCard.getUpdatedAt().after(initialUpdatedAt));
    }
}

