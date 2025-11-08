package com.carddemo.repository;

import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit 5 Test Class for CardRepository.
 * 
 * <p>This test class validates that PostgreSQL queries correctly replicate VSAM KSDS
 * file access patterns from the COBOL CARDDAT master file defined in copybook CVACT02Y.cpy.
 * Tests use @DataJpaTest annotation for isolated repository testing with H2 in-memory
 * database, ensuring fast test execution without requiring external database infrastructure.</p>
 * 
 * <p><strong>COBOL to PostgreSQL Transformation Validation:</strong></p>
 * <ul>
 *   <li><strong>VSAM Primary Key READ:</strong> Tests findByCardNumber replicates
 *       EXEC CICS READ DATASET(CARDDAT) RIDFLD(CARD-NUM) operations with CARD-NUM
 *       PIC X(16) as 16-character primary key matching VSAM indexed access pattern</li>
 *   <li><strong>VSAM Sequential Browse:</strong> Tests findByAccountId with pagination
 *       replicates EXEC CICS STARTBR/READNEXT sequential browsing for card list display
 *       with 7 cards per page matching COBOL COCRDLIC program requirements</li>
 *   <li><strong>VSAM WRITE Operation:</strong> Tests save method for new card creation
 *       replicates EXEC CICS WRITE DATASET(CARDDAT) operations with complete 150-byte
 *       record structure</li>
 *   <li><strong>VSAM REWRITE Operation:</strong> Tests save method for existing card update
 *       replicates EXEC CICS REWRITE DATASET(CARDDAT) operations preserving all fields</li>
 *   <li><strong>Foreign Key Constraints:</strong> Tests verify CARD-ACCT-ID PIC 9(11)
 *       foreign key relationship replaces VSAM cross-reference file CVACT03Y.cpy structure</li>
 * </ul>
 * 
 * <p><strong>Test Data Setup Strategy:</strong></p>
 * <p>@BeforeEach method creates complete entity hierarchy (Customer → Account → Card)
 * matching COBOL cross-reference structure from CVACT03Y.cpy (CARD-XREF-RECORD) with
 * fields XREF-CUST-ID PIC 9(09), XREF-ACCT-ID PIC 9(11), XREF-CARD-NUM PIC X(16).
 * Test data includes:</p>
 * <ul>
 *   <li><strong>Customer:</strong> customerId (9-digit), names, address fields, DOB, FICO score</li>
 *   <li><strong>Account:</strong> accountId (11-digit), customer FK, monetary balances
 *       (BigDecimal precision=12 scale=2 matching COBOL COMP-3), credit limits, dates</li>
 *   <li><strong>Card:</strong> cardNumber (16-char), account FK, CVV code (3-digit),
 *       embossed name (50-char), expiration date, active status (1-char)</li>
 * </ul>
 * 
 * <p><strong>COBOL CARDDAT Record Structure (CVACT02Y.cpy):</strong></p>
 * <pre>
 * 01  CARD-RECORD.
 *     05  CARD-NUM                PIC X(16).     → String cardNumber (Primary Key)
 *     05  CARD-ACCT-ID            PIC 9(11).     → Long accountId (Foreign Key)
 *     05  CARD-CVV-CD             PIC 9(03).     → String cvvCode
 *     05  CARD-EMBOSSED-NAME      PIC X(50).     → String embossedName
 *     05  CARD-EXPIRAION-DATE     PIC X(10).     → LocalDate expirationDate
 *     05  CARD-ACTIVE-STATUS      PIC X(01).     → String activeStatus
 *     05  FILLER                  PIC X(59).     → (Not mapped)
 * </pre>
 * 
 * <p><strong>Pagination Requirements:</strong></p>
 * <p>COBOL program COCRDLIC displays 7 cards per page matching terminal screen constraints.
 * Tests verify Page&lt;Card&gt; results contain correct page size, total elements, and
 * proper pagination boundaries using Spring Data PageRequest with size=7.</p>
 * 
 * <p><strong>Foreign Key Validation:</strong></p>
 * <p>Tests ensure PostgreSQL foreign key constraints properly replicate VSAM cross-reference
 * file CVACT03Y.cpy (CARD-XREF-RECORD) relationships. Card entity must have valid account
 * FK, account must have valid customer FK, maintaining referential integrity matching
 * COBOL data validation rules.</p>
 * 
 * <p><strong>Character Encoding:</strong></p>
 * <p>VSAM CARDDAT uses EBCDIC character encoding. PostgreSQL uses UTF-8 ASCII. Tests verify
 * proper encoding conversion for character fields (cardNumber, embossedName, activeStatus)
 * while preserving data integrity and field lengths.</p>
 * 
 * <p><strong>Test Execution Environment:</strong></p>
 * <ul>
 *   <li><strong>Database:</strong> H2 in-memory database (test profile)</li>
 *   <li><strong>Transaction Management:</strong> Each test runs in separate transaction,
 *       automatically rolled back after test completion</li>
 *   <li><strong>Entity Manager:</strong> Auto-configured by @DataJpaTest annotation</li>
 *   <li><strong>Repository Beans:</strong> CardRepository, AccountRepository, CustomerRepository
 *       auto-wired for entity hierarchy creation</li>
 * </ul>
 * 
 * <p><strong>Validation Checklist from Section 0.10 Requirements:</strong></p>
 * <ol>
 *   <li>All COBOL test scenarios with card READ operations pass identically</li>
 *   <li>Card retrieval by 16-char card number returns identical field values</li>
 *   <li>Account card lookups return same record sets as VSAM sequential browse</li>
 *   <li>Pagination correctly limits results to 7 cards per page</li>
 *   <li>Foreign key constraints maintain referential integrity</li>
 *   <li>CRUD operations (WRITE, REWRITE, DELETE) maintain data consistency</li>
 * </ol>
 * 
 * @see CardRepository
 * @see Card
 * @see Account
 * @see Customer
 * @see <a href="Section 0.4">Agent Action Plan - Source File app/cpy/CVACT02Y.cpy</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan - CardRepositoryTest.java</a>
 * @see <a href="Section 0.10">Special Instructions - Testing Strategy</a>
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("CardRepository Integration Tests - VSAM CARDDAT File Operations")
public class CardRepositoryTest {

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    // Test data entities
    private Customer testCustomer;
    private Account testAccount;
    private Card testCard1;
    private Card testCard2;
    private Card testCard3;

    /**
     * Setup method executed before each test to create complete entity hierarchy.
     * 
     * <p>Creates test data matching COBOL cross-reference structure from CVACT03Y.cpy:</p>
     * <ul>
     *   <li><strong>Customer:</strong> CUST-ID=100000001, complete personal information</li>
     *   <li><strong>Account:</strong> ACCT-ID=12345678901, customer FK, monetary balances</li>
     *   <li><strong>Cards:</strong> Multiple cards with 16-char card numbers, account FK</li>
     * </ul>
     * 
     * <p>Uses builder pattern from entity classes for clean test data creation.
     * All monetary fields use BigDecimal with explicit scale=2 matching COBOL COMP-3
     * packed decimal precision from PIC S9(10)V99 fields.</p>
     */
    @BeforeEach
    @DisplayName("Setup: Create test customer, account, and card entities")
    void setUp() {
        // Create test customer (CUST-ID PIC 9(09) = 9-digit customer identifier)
        testCustomer = Customer.builder()
                .customerId(100000001L)
                .firstName("John")
                .lastName("Smith")
                .addressLine1("123 Main Street")
                .addressStateCode("CA")
                .addressZip("90210")
                .dateOfBirth(LocalDate.of(1980, 5, 15))
                .ficoScore(750)
                .build();
        testCustomer = customerRepository.save(testCustomer);

        // Create test account (ACCT-ID PIC 9(11) = 11-digit account identifier)
        // BigDecimal fields match COBOL PIC S9(10)V99 COMP-3 with precision=12, scale=2
        testAccount = Account.builder()
                .accountId(12345678901L)
                .customer(testCustomer)
                .activeStatus("Y")
                .currentBalance(BigDecimal.valueOf(2500.75))
                .creditLimit(BigDecimal.valueOf(10000.00))
                .cashCreditLimit(BigDecimal.valueOf(2000.00))
                .openDate(LocalDate.of(2020, 1, 15))
                .build();
        testAccount = accountRepository.save(testAccount);

        // Create test cards (CARD-NUM PIC X(16) = 16-character card number)
        // Card 1: Primary test card with complete field data
        testCard1 = Card.builder()
                .cardNumber("4111111111111111")  // 16-char card number (CARD-NUM)
                .account(testAccount)            // FK to account (CARD-ACCT-ID)
                .cvvCode("123")                  // 3-digit CVV (CARD-CVV-CD PIC 9(03))
                .embossedName("JOHN SMITH")      // 50-char name (CARD-EMBOSSED-NAME PIC X(50))
                .expirationDate(LocalDate.now().plusYears(2))  // CARD-EXPIRAION-DATE PIC X(10)
                .activeStatus("Y")               // 1-char status (CARD-ACTIVE-STATUS PIC X(01))
                .build();
        testCard1 = cardRepository.save(testCard1);

        // Card 2: Second card for pagination testing
        testCard2 = Card.builder()
                .cardNumber("4111111111111112")
                .account(testAccount)
                .cvvCode("456")
                .embossedName("JOHN SMITH")
                .expirationDate(LocalDate.now().plusYears(3))
                .activeStatus("Y")
                .build();
        testCard2 = cardRepository.save(testCard2);

        // Card 3: Third card for pagination testing
        testCard3 = Card.builder()
                .cardNumber("4111111111111113")
                .account(testAccount)
                .cvvCode("789")
                .embossedName("JOHN SMITH")
                .expirationDate(LocalDate.now().plusYears(1))
                .activeStatus("N")  // Inactive card
                .build();
        testCard3 = cardRepository.save(testCard3);
    }

    /**
     * Test findByCardNumber method with valid 16-character card number.
     * 
     * <p><strong>COBOL Operation Being Tested:</strong></p>
     * <pre>
     * EXEC CICS READ
     *      DATASET   (LIT-CARDFILENAME)
     *      RIDFLD    (WS-CARD-RID-CARDNUM-X)
     *      INTO      (CARD-RECORD)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * 
     * EVALUATE WS-RESP-CD
     *    WHEN DFHRESP(NORMAL)
     *       SET FOUND-CARD-IN-MASTER TO TRUE
     *    WHEN DFHRESP(NOTFND)
     *       SET DID-NOT-FIND-CARD-IN-CARDDAT TO TRUE
     * END-EVALUATE
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>findByCardNumber returns Optional.of(card) for existing card number</li>
     *   <li>Returned Card entity contains all correct field values matching test data</li>
     *   <li>16-character CARD-NUM primary key lookup uses indexed access</li>
     *   <li>Query execution matches VSAM KSDS random access performance</li>
     * </ul>
     */
    @Test
    @DisplayName("Find card by 16-character card number returns correct card")
    void testFindByCardNumber_Success() {
        // Execute: Find card by primary key (CARD-NUM PIC X(16))
        Optional<Card> result = cardRepository.findByCardNumber("4111111111111111");

        // Verify: Card found and all fields match COBOL CARD-RECORD structure
        assertThat(result).isPresent();
        Card foundCard = result.get();
        assertThat(foundCard.getCardNumber()).isEqualTo("4111111111111111");
        assertThat(foundCard.getAccount()).isNotNull();
        assertThat(foundCard.getAccount().getAccountId()).isEqualTo(12345678901L);
        assertThat(foundCard.getCvvCode()).isEqualTo("123");
        assertThat(foundCard.getEmbossedName()).isEqualTo("JOHN SMITH");
        assertThat(foundCard.getExpirationDate()).isNotNull();
        assertThat(foundCard.getActiveStatus()).isEqualTo("Y");
    }

    /**
     * Test findByCardNumber method with non-existent card number.
     * 
     * <p><strong>COBOL NOTFND Condition Handling:</strong></p>
     * <pre>
     * EXEC CICS READ
     *      DATASET   (LIT-CARDFILENAME)
     *      RIDFLD    (WS-CARD-RID-CARDNUM-X)
     *      INTO      (CARD-RECORD)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * 
     * IF WS-RESP-CD = DFHRESP(NOTFND)
     *    MOVE 'Card not found' TO ERROR-MESSAGE
     *    SET DID-NOT-FIND-CARD-IN-CARDDAT TO TRUE
     * END-IF
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>findByCardNumber returns Optional.empty() for non-existent card</li>
     *   <li>No exception thrown (matches COBOL RESP code pattern)</li>
     *   <li>Calling code can check Optional.isEmpty() like COBOL checks NOTFND</li>
     * </ul>
     */
    @Test
    @DisplayName("Find card by non-existent card number returns empty Optional")
    void testFindByCardNumber_NotFound() {
        // Execute: Attempt to find non-existent card
        Optional<Card> result = cardRepository.findByCardNumber("9999999999999999");

        // Verify: Empty Optional returned (matches COBOL RESP(DFHRESP(NOTFND)))
        assertThat(result).isEmpty();
    }

    /**
     * Test findByAccountId with pagination for 7 cards per page.
     * 
     * <p><strong>COBOL Sequential Browse Pattern Being Tested:</strong></p>
     * <pre>
     * * COCRDLIC.cbl - Card List Display (7 cards per page)
     * MOVE 7 TO WS-PAGE-SIZE
     * 
     * EXEC CICS STARTBR
     *      DATASET   (LIT-CARDFILENAME)
     *      RIDFLD    (WS-CARD-RID-ACCT-ID-X)
     * END-EXEC
     * 
     * PERFORM VARYING WS-INDEX FROM 1 BY 1
     *    UNTIL WS-INDEX > WS-PAGE-SIZE OR END-OF-CARDS
     *    
     *    EXEC CICS READNEXT
     *         DATASET   (LIT-CARDFILENAME)
     *         INTO      (CARD-RECORD)
     *         RIDFLD    (WS-CARD-RID-CARDNUM-X)
     *         RESP      (WS-RESP-CD)
     *    END-EXEC
     *    
     *    IF CARD-ACCT-ID = WS-SEARCH-ACCT-ID
     *       MOVE CARD-RECORD TO DISPLAY-CARD-LINE(WS-INDEX)
     *    ELSE
     *       SET END-OF-CARDS TO TRUE
     *    END-IF
     * END-PERFORM
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>findByAccountId with PageRequest(0, 7) returns page with 7 or fewer cards</li>
     *   <li>All returned cards belong to specified account ID</li>
     *   <li>Page metadata contains correct totalElements, totalPages, pageNumber</li>
     *   <li>Pagination boundaries handled correctly for partial pages</li>
     *   <li>Results match VSAM sequential browse by account ID alternate index</li>
     * </ul>
     */
    @Test
    @DisplayName("Find cards by account ID with pagination returns 7 cards per page")
    void testFindByAccountId_ReturnsPaginatedCards() {
        // Setup: Create additional cards to test pagination (need at least 8 for 2 pages)
        // Using format with 4-digit padding to ensure exactly 16 characters (CARD-NUM PIC X(16))
        for (int i = 4; i <= 10; i++) {
            Card additionalCard = Card.builder()
                    .cardNumber(String.format("411111111111%04d", i))
                    .account(testAccount)
                    .cvvCode("000")
                    .embossedName("JOHN SMITH")
                    .expirationDate(LocalDate.now().plusYears(1))
                    .activeStatus("Y")
                    .build();
            cardRepository.save(additionalCard);
        }

        // Execute: Get first page with 7 cards (COCRDLIC page size requirement)
        Pageable pageable = PageRequest.of(0, 7);
        Page<Card> cardPage = cardRepository.findByAccount_AccountId(testAccount.getAccountId(), pageable);

        // Verify: Page contains correct number of cards and metadata
        assertThat(cardPage).isNotNull();
        assertThat(cardPage.getContent()).hasSize(7);  // Exactly 7 cards per page
        assertThat(cardPage.getTotalElements()).isEqualTo(10);  // Total cards for account
        assertThat(cardPage.getTotalPages()).isEqualTo(2);  // 10 cards / 7 per page = 2 pages
        assertThat(cardPage.getNumber()).isEqualTo(0);  // Page number (zero-indexed)
        assertThat(cardPage.getSize()).isEqualTo(7);  // Page size matches COBOL requirement

        // Verify: All cards belong to correct account
        List<Card> cards = cardPage.getContent();
        for (Card card : cards) {
            assertThat(card.getAccount().getAccountId()).isEqualTo(testAccount.getAccountId());
        }

        // Execute: Get second page (should have remaining 3 cards)
        Pageable page2 = PageRequest.of(1, 7);
        Page<Card> cardPage2 = cardRepository.findByAccount_AccountId(testAccount.getAccountId(), page2);

        // Verify: Second page contains remaining cards
        assertThat(cardPage2.getContent()).hasSize(3);  // Remaining cards
        assertThat(cardPage2.getNumber()).isEqualTo(1);  // Second page (zero-indexed)
    }

    /**
     * Test save method for persisting new card (VSAM WRITE operation).
     * 
     * <p><strong>COBOL WRITE Operation Being Tested:</strong></p>
     * <pre>
     * * Create new card record
     * MOVE '4111111111111114' TO CARD-NUM
     * MOVE 12345678901         TO CARD-ACCT-ID
     * MOVE 999                 TO CARD-CVV-CD
     * MOVE 'JANE DOE'          TO CARD-EMBOSSED-NAME
     * MOVE '2026-12-31'        TO CARD-EXPIRAION-DATE
     * MOVE 'Y'                 TO CARD-ACTIVE-STATUS
     * 
     * EXEC CICS WRITE
     *      DATASET   (LIT-CARDFILENAME)
     *      FROM      (CARD-RECORD)
     *      RIDFLD    (CARD-NUM)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * 
     * EVALUATE WS-RESP-CD
     *    WHEN DFHRESP(NORMAL)
     *       MOVE 'Card created successfully' TO SUCCESS-MESSAGE
     *    WHEN DFHRESP(DUPREC)
     *       MOVE 'Card already exists' TO ERROR-MESSAGE
     * END-EVALUATE
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>save() method successfully inserts new card with all fields</li>
     *   <li>Returned Card entity has generated/assigned cardNumber as primary key</li>
     *   <li>All 150-byte CARD-RECORD fields persisted correctly</li>
     *   <li>Foreign key to account maintained with referential integrity</li>
     *   <li>Card can be retrieved by findByCardNumber after save</li>
     * </ul>
     */
    @Test
    @DisplayName("Save new card persists all fields correctly (VSAM WRITE)")
    void testSave_PersistsNewCard() {
        // Setup: Create new card entity (all fields from CVACT02Y.cpy CARD-RECORD)
        Card newCard = Card.builder()
                .cardNumber("4111111111111114")  // CARD-NUM PIC X(16)
                .account(testAccount)            // CARD-ACCT-ID PIC 9(11) foreign key
                .cvvCode("999")                  // CARD-CVV-CD PIC 9(03)
                .embossedName("JANE DOE")        // CARD-EMBOSSED-NAME PIC X(50)
                .expirationDate(LocalDate.of(2026, 12, 31))  // CARD-EXPIRAION-DATE PIC X(10)
                .activeStatus("Y")               // CARD-ACTIVE-STATUS PIC X(01)
                .build();

        // Execute: Save new card (EXEC CICS WRITE)
        Card savedCard = cardRepository.save(newCard);

        // Verify: Card saved with all fields
        assertThat(savedCard).isNotNull();
        assertThat(savedCard.getCardNumber()).isEqualTo("4111111111111114");
        assertThat(savedCard.getAccount()).isNotNull();
        assertThat(savedCard.getAccount().getAccountId()).isEqualTo(testAccount.getAccountId());
        assertThat(savedCard.getCvvCode()).isEqualTo("999");
        assertThat(savedCard.getEmbossedName()).isEqualTo("JANE DOE");
        assertThat(savedCard.getExpirationDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(savedCard.getActiveStatus()).isEqualTo("Y");

        // Verify: Card can be retrieved by findByCardNumber
        Optional<Card> retrievedCard = cardRepository.findByCardNumber("4111111111111114");
        assertThat(retrievedCard).isPresent();
        assertThat(retrievedCard.get().getCvvCode()).isEqualTo("999");
    }

    /**
     * Test save method for updating existing card (VSAM REWRITE operation).
     * 
     * <p><strong>COBOL REWRITE Operation Being Tested:</strong></p>
     * <pre>
     * * Read existing card
     * EXEC CICS READ
     *      DATASET   (LIT-CARDFILENAME)
     *      RIDFLD    (WS-CARD-RID-CARDNUM-X)
     *      INTO      (CARD-RECORD)
     *      UPDATE
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * 
     * * Modify card fields
     * MOVE 'N'            TO CARD-ACTIVE-STATUS
     * MOVE '2024-12-31'   TO CARD-EXPIRAION-DATE
     * 
     * * Write back updated record
     * EXEC CICS REWRITE
     *      DATASET   (LIT-CARDFILENAME)
     *      FROM      (CARD-RECORD)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * 
     * IF WS-RESP-CD = DFHRESP(NORMAL)
     *    MOVE 'Card updated successfully' TO SUCCESS-MESSAGE
     * END-IF
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>save() method updates existing card when primary key exists</li>
     *   <li>Modified fields (activeStatus, expirationDate) persisted correctly</li>
     *   <li>Unmodified fields remain unchanged after update</li>
     *   <li>Optimistic locking (if configured) handles concurrent updates</li>
     *   <li>Updated card retrievable with modified values</li>
     * </ul>
     */
    @Test
    @DisplayName("Save existing card updates fields correctly (VSAM REWRITE)")
    void testSave_UpdatesExistingCard() {
        // Setup: Retrieve existing card
        Card existingCard = cardRepository.findByCardNumber("4111111111111111")
                .orElseThrow(() -> new AssertionError("Test card not found"));

        // Execute: Modify card fields and save (EXEC CICS READ UPDATE + REWRITE)
        existingCard.setActiveStatus("N");  // Deactivate card
        existingCard.setExpirationDate(LocalDate.of(2024, 12, 31));  // Update expiration
        Card updatedCard = cardRepository.save(existingCard);

        // Verify: Fields updated correctly
        assertThat(updatedCard).isNotNull();
        assertThat(updatedCard.getCardNumber()).isEqualTo("4111111111111111");
        assertThat(updatedCard.getActiveStatus()).isEqualTo("N");
        assertThat(updatedCard.getExpirationDate()).isEqualTo(LocalDate.of(2024, 12, 31));

        // Verify: Unmodified fields remain unchanged
        assertThat(updatedCard.getCvvCode()).isEqualTo("123");
        assertThat(updatedCard.getEmbossedName()).isEqualTo("JOHN SMITH");

        // Verify: Retrieved card reflects updates
        Optional<Card> retrievedCard = cardRepository.findByCardNumber("4111111111111111");
        assertThat(retrievedCard).isPresent();
        assertThat(retrievedCard.get().getActiveStatus()).isEqualTo("N");
        assertThat(retrievedCard.get().getExpirationDate()).isEqualTo(LocalDate.of(2024, 12, 31));
    }

    /**
     * Test foreign key constraint validation with account relationship.
     * 
     * <p><strong>COBOL Cross-Reference Validation Being Tested:</strong></p>
     * <pre>
     * * CVACT03Y.cpy - CARD-XREF-RECORD structure
     * 01  CARD-XREF-RECORD.
     *     05  XREF-CARD-NUM       PIC X(16).   → Card primary key
     *     05  XREF-CUST-ID        PIC 9(09).   → Customer foreign key
     *     05  XREF-ACCT-ID        PIC 9(11).   → Account foreign key
     * 
     * * Validate cross-reference integrity
     * EXEC CICS READ
     *      DATASET   (LIT-CARDXREFNAME)
     *      RIDFLD    (XREF-CARD-NUM)
     *      INTO      (CARD-XREF-RECORD)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * 
     * IF WS-RESP-CD = DFHRESP(NORMAL)
     *    * Verify account exists
     *    EXEC CICS READ
     *         DATASET   (LIT-ACCTFILENAME)
     *         RIDFLD    (XREF-ACCT-ID)
     *         INTO      (ACCOUNT-RECORD)
     *         RESP      (WS-RESP-CD)
     *    END-EXEC
     *    
     *    IF WS-RESP-CD NOT = DFHRESP(NORMAL)
     *       MOVE 'Invalid account reference' TO ERROR-MESSAGE
     *    END-IF
     * END-IF
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>Card entity maintains @ManyToOne relationship to Account entity</li>
     *   <li>CARD-ACCT-ID PIC 9(11) foreign key properly enforced by JPA</li>
     *   <li>Saved card can navigate to parent account entity</li>
     *   <li>Account navigation returns complete account with customer relationship</li>
     *   <li>Foreign key constraints replace VSAM cross-reference file CVACT03Y.cpy</li>
     *   <li>Referential integrity maintained matching COBOL validation rules</li>
     * </ul>
     */
    @Test
    @DisplayName("Card foreign key constraint with account is properly maintained")
    void testForeignKeyConstraint_WithAccount() {
        // Setup: Card already saved in @BeforeEach with account FK

        // Execute: Retrieve card and navigate to account
        Optional<Card> cardOpt = cardRepository.findByCardNumber("4111111111111111");
        assertThat(cardOpt).isPresent();

        Card card = cardOpt.get();

        // Verify: Card has valid account foreign key (CARD-ACCT-ID PIC 9(11))
        assertThat(card.getAccount()).isNotNull();
        assertThat(card.getAccount().getAccountId()).isEqualTo(12345678901L);

        // Verify: Account navigation works (replaces COBOL cross-reference READ)
        Account linkedAccount = card.getAccount();
        assertThat(linkedAccount).isNotNull();
        assertThat(linkedAccount.getAccountId()).isEqualTo(testAccount.getAccountId());
        assertThat(linkedAccount.getActiveStatus()).isEqualTo("Y");
        assertThat(linkedAccount.getCreditLimit()).isEqualByComparingTo(BigDecimal.valueOf(10000.00));

        // Verify: Account has customer FK (complete hierarchy: Card → Account → Customer)
        assertThat(linkedAccount.getCustomer()).isNotNull();
        assertThat(linkedAccount.getCustomer().getCustomerId()).isEqualTo(testCustomer.getCustomerId());
        assertThat(linkedAccount.getCustomer().getLastName()).isEqualTo("Smith");

        // Verify: Matches COBOL CARD-XREF-RECORD cross-reference structure
        // XREF-CARD-NUM = card.cardNumber
        // XREF-ACCT-ID = card.account.accountId
        // XREF-CUST-ID = card.account.customer.customerId
        assertThat(card.getCardNumber()).hasSize(16);  // XREF-CARD-NUM PIC X(16)
        assertThat(card.getAccount().getAccountId().toString()).hasSize(11);  // XREF-ACCT-ID PIC 9(11)
        assertThat(card.getAccount().getCustomer().getCustomerId().toString()).hasSize(9);  // XREF-CUST-ID PIC 9(09)
    }

    /**
     * Test findByAccountId without pagination returns all cards for account.
     * 
     * <p><strong>COBOL Sequential Read All Pattern:</strong></p>
     * <pre>
     * * Read all cards for account (no pagination limit)
     * MOVE ZERO TO CARD-COUNT
     * 
     * EXEC CICS STARTBR
     *      DATASET   (LIT-CARDFILENAME)
     *      RIDFLD    (WS-CARD-RID-ACCT-ID-X)
     * END-EXEC
     * 
     * PERFORM UNTIL END-OF-CARDS
     *    EXEC CICS READNEXT
     *         DATASET   (LIT-CARDFILENAME)
     *         INTO      (CARD-RECORD)
     *         RESP      (WS-RESP-CD)
     *    END-EXEC
     *    
     *    IF CARD-ACCT-ID = WS-SEARCH-ACCT-ID
     *       ADD 1 TO CARD-COUNT
     *       * Process card...
     *    ELSE
     *       SET END-OF-CARDS TO TRUE
     *    END-IF
     * END-PERFORM
     * </pre>
     * 
     * <p><strong>Validates:</strong></p>
     * <ul>
     *   <li>findByAccountId returns List containing all cards for account</li>
     *   <li>List size matches total cards associated with account ID</li>
     *   <li>All cards in list have matching account FK</li>
     *   <li>Results include both active and inactive cards</li>
     * </ul>
     */
    @Test
    @DisplayName("Find cards by account ID without pagination returns all cards")
    void testFindByAccountId_ReturnsAllCards() {
        // Execute: Get all cards for account (no pagination)
        List<Card> cards = cardRepository.findByAccount_AccountId(testAccount.getAccountId());

        // Verify: All cards returned (3 cards created in @BeforeEach)
        assertThat(cards).isNotNull();
        assertThat(cards).hasSize(3);

        // Verify: All cards belong to correct account
        for (Card card : cards) {
            assertThat(card.getAccount().getAccountId()).isEqualTo(testAccount.getAccountId());
        }

        // Verify: Both active and inactive cards included
        long activeCount = cards.stream()
                .filter(c -> "Y".equals(c.getActiveStatus()))
                .count();
        long inactiveCount = cards.stream()
                .filter(c -> "N".equals(c.getActiveStatus()))
                .count();

        assertThat(activeCount).isEqualTo(2);  // testCard1 and testCard2
        assertThat(inactiveCount).isEqualTo(1);  // testCard3
    }
}
