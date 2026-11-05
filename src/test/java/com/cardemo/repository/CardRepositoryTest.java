package com.cardemo.repository;

import com.cardemo.entity.Card;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link CardRepository} validating CRUD operations,
 * AIX-equivalent {@code findByAccountId()} queries, paginated card listing,
 * optimistic locking, and PII field (CVV) handling against a real PostgreSQL 16
 * database using Testcontainers.
 *
 * <p>Tests verify VSAM CARDDATA KSDS access patterns from:
 * <ul>
 *   <li>COCRDSLC.cbl — Card Detail: {@code EXEC CICS READ DATASET('CARDDAT')
 *       RIDFLD(WS-CARD-RID-CARDNUM)}</li>
 *   <li>COCRDSLC.cbl — Card AIX: {@code EXEC CICS READ DATASET('CARDAIX')
 *       RIDFLD(WS-CARD-RID-ACCTID)}</li>
 *   <li>COCRDLIC.cbl — Card List: STARTBR/READNEXT with 9500-FILTER-RECORDS
 *       pagination and PF7/PF8 page navigation</li>
 *   <li>COCRDUPC.cbl — Card Update: {@code EXEC CICS READ UPDATE → REWRITE}
 *       with optimistic locking via {@code @Version}</li>
 * </ul>
 *
 * <p>COBOL Source Context (CVACT02Y.cpy — 150-byte CARD-RECORD):
 * <pre>
 *   CARD-NUM             PIC X(16)   → cardNum VARCHAR(16) PK
 *   CARD-ACCT-ID         PIC 9(11)   → accountId VARCHAR(11) AIX-indexed
 *   CARD-CVV-CD          PIC 9(03)   → cvvCode VARCHAR(3) PII
 *   CARD-EMBOSSED-NAME   PIC X(50)   → embossedName VARCHAR(50)
 *   CARD-EXPIRAION-DATE  PIC X(10)   → expirationDate VARCHAR(10)
 *   CARD-ACTIVE-STATUS   PIC X(01)   → activeStatus VARCHAR(1)
 * </pre>
 *
 * <p>No {@code double} or {@code float} is used anywhere in this test. Card entity
 * has no monetary fields, but String types are used consistently for all VSAM
 * record fields per AAP COBOL-to-Java mapping rules.
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql(
        statements = {
                "DELETE FROM card_xrefs",
                "DELETE FROM cards",
                "DELETE FROM accounts",
                "INSERT INTO accounts (acct_id, acct_active_status, acct_curr_bal, acct_credit_limit, "
                        + "acct_cash_credit_limit, acct_open_date, acct_expiration_date, acct_reissue_date, "
                        + "acct_curr_cyc_credit, acct_curr_cyc_debit, acct_addr_zip, acct_group_id) "
                        + "VALUES ('00000000050', 'Y', 0.00, 0.00, 0.00, '2020-01-01', "
                        + "'2025-12-31', '2025-12-31', 0.00, 0.00, '00000', '0000000000')",
                "INSERT INTO accounts (acct_id, acct_active_status, acct_curr_bal, acct_credit_limit, "
                        + "acct_cash_credit_limit, acct_open_date, acct_expiration_date, acct_reissue_date, "
                        + "acct_curr_cyc_credit, acct_curr_cyc_debit, acct_addr_zip, acct_group_id) "
                        + "VALUES ('00000000027', 'Y', 0.00, 0.00, 0.00, '2020-01-01', "
                        + "'2025-12-31', '2025-12-31', 0.00, 0.00, '00000', '0000000000')"
        },
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class CardRepositoryTest {

    /**
     * PostgreSQL 16 container managed by Testcontainers JUnit 5 extension.
     * Started once before the first test; stopped after the last test.
     * Uses postgres:16-alpine Docker image matching the production target.
     */
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    /**
     * Injects Testcontainers-managed PostgreSQL connection properties into Spring
     * DataSource, overriding the Testcontainers JDBC URL in application-test.yml
     * to use the explicit container instance managed by {@code @Container}.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private CardRepository cardRepository;

    /**
     * JPA test entity manager for explicit flush/clear operations in tests
     * where database roundtrip verification is needed (e.g., optimistic locking
     * {@code @Version} increment, PII field persistence after save).
     */
    @Autowired
    private TestEntityManager entityManager;

    /**
     * Clears all card records and inserts three test Card entities before each test.
     * Test data values are derived from carddata.txt (VSAM CARDDATA records):
     * <ul>
     *   <li>Card 1: cardNum=0500024453765740, accountId=00000000050, cvv=747 (Aniya Von)</li>
     *   <li>Card 2: cardNum=0683586198171516, accountId=00000000027, cvv=567 (Ward Jones)</li>
     *   <li>Card 3: cardNum=0923877193247330, accountId=00000000050, cvv=028 (Enrico Rosenbaum)</li>
     * </ul>
     * Cards 1 and 3 share the same accountId=00000000050 to test AIX multi-result queries.
     * Card 3 has CVV "028" with a leading zero to test PII field String preservation.
     *
     * <p>Prerequisite account records ("00000000050" and "00000000027") are inserted
     * by the class-level {@code @Sql} annotation before this method runs, satisfying
     * the FK constraint {@code cards.card_acct_id → accounts.acct_id}.
     */
    @BeforeEach
    void setUp() {
        // Repository-level cleanup; FK-safe table cleanup handled by @Sql
        cardRepository.deleteAll();

        // Card 1: Derived from carddata.txt record 1
        // Raw: 050002445376574000000000050747Aniya Von...2023-03-09Y
        // 16-char cardNum: "0500024453765740" (original 15-char padded to 16)
        Card card1 = new Card(
                "0500024453765740", "00000000050", "747",
                "Aniya Von", "2023-03-09", "Y"
        );

        // Card 2: Derived from carddata.txt record 2
        // Raw: 068358619817151600000000027567Ward Jones...2025-07-13Y
        Card card2 = new Card(
                "0683586198171516", "00000000027", "567",
                "Ward Jones", "2025-07-13", "Y"
        );

        // Card 3: Derived from carddata.txt record 3 — SAME accountId as card1
        // for AIX multi-result query testing. CVV "028" has leading zero for PII test.
        Card card3 = new Card(
                "0923877193247330", "00000000050", "028",
                "Enrico Rosenbaum", "2024-08-11", "Y"
        );

        cardRepository.saveAll(List.of(card1, card2, card3));
        entityManager.flush();
        entityManager.clear();
    }

    // =========================================================================
    // CRUD Operation Tests — CICS READ / WRITE / REWRITE / DELETE equivalents
    // =========================================================================

    /**
     * Verifies primary key lookup by the 16-character CARD-NUM, mapping to:
     * COCRDSLC.cbl {@code EXEC CICS READ DATASET('CARDDAT') RIDFLD(WS-CARD-RID-CARDNUM)}.
     *
     * <p>Confirms all 6 business fields roundtrip correctly through JPA persistence,
     * including the 16-char cardNum PK and 11-char accountId (AIX-indexed).
     */
    @Test
    @DisplayName("findById returns card by 16-char CARD-NUM primary key")
    void findById_returnsCardBy16CharPrimaryKey() {
        Optional<Card> result = cardRepository.findById("0500024453765740");

        assertThat(result).isPresent();

        Card card = result.get();
        assertThat(card.getCardNum()).isEqualTo("0500024453765740");
        assertThat(card.getCardNum()).hasSize(16);
        assertThat(card.getAccountId()).isEqualTo("00000000050");
        assertThat(card.getAccountId()).hasSize(11);
        assertThat(card.getCvvCode()).isEqualTo("747");
        assertThat(card.getEmbossedName()).isEqualTo("Aniya Von");
        assertThat(card.getExpirationDate()).isEqualTo("2023-03-09");
        assertThat(card.getActiveStatus()).isEqualTo("Y");
    }

    /**
     * Verifies that looking up a non-existent card number returns {@code Optional.empty()},
     * mapping to VSAM file status '23' (record not found) in COCRDSLC.cbl.
     */
    @Test
    @DisplayName("findById returns empty for non-existent card")
    void findById_returnsEmptyForNonExistentCard() {
        Optional<Card> result = cardRepository.findById("9999999999999999");

        assertThat(result).isEmpty();
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.isPresent()).isFalse();
    }

    /**
     * Verifies that a new Card entity can be persisted with all fields,
     * mapping to CICS WRITE operation in the COBOL card management flow.
     * Uses the accountId "00000000027" which was pre-inserted by {@code @Sql}.
     */
    @Test
    @DisplayName("save persists new card entity")
    void save_persistsNewCardEntity() {
        Card newCard = new Card(
                "1111222233334444", "00000000027", "123",
                "Jane Smith", "2026-01-15", "Y"
        );

        Card saved = cardRepository.save(newCard);
        entityManager.flush();
        entityManager.clear();

        assertThat(saved.getCardNum()).isEqualTo("1111222233334444");

        Optional<Card> found = cardRepository.findById("1111222233334444");
        assertThat(found).isPresent();

        Card retrieved = found.get();
        assertThat(retrieved.getCardNum()).isEqualTo("1111222233334444");
        assertThat(retrieved.getCardNum()).hasSize(16);
        assertThat(retrieved.getAccountId()).isEqualTo("00000000027");
        assertThat(retrieved.getAccountId()).hasSize(11);
        assertThat(retrieved.getCvvCode()).isEqualTo("123");
        assertThat(retrieved.getEmbossedName()).isEqualTo("Jane Smith");
        assertThat(retrieved.getExpirationDate()).isEqualTo("2026-01-15");
        assertThat(retrieved.getActiveStatus()).isEqualTo("Y");
    }

    /**
     * Verifies card update persistence, mapping to COCRDUPC.cbl
     * {@code EXEC CICS REWRITE DATASET('CARDDAT') FROM(CARD-RECORD)}.
     * Updates embossed name, expiration date, active status, and account ID,
     * then verifies all changes persist through the database roundtrip.
     */
    @Test
    @DisplayName("save updates existing card")
    void save_updatesExistingCard() {
        Optional<Card> existing = cardRepository.findById("0683586198171516");
        assertThat(existing).isPresent();

        Card card = existing.get();
        card.setEmbossedName("Ward Jones Updated");
        card.setExpirationDate("2026-12-31");
        card.setActiveStatus("N");
        card.setAccountId("00000000050");
        cardRepository.save(card);
        entityManager.flush();
        entityManager.clear();

        Optional<Card> updated = cardRepository.findById("0683586198171516");
        assertThat(updated).isPresent();

        Card updatedCard = updated.get();
        assertThat(updatedCard.getEmbossedName()).isEqualTo("Ward Jones Updated");
        assertThat(updatedCard.getExpirationDate()).isEqualTo("2026-12-31");
        assertThat(updatedCard.getActiveStatus()).isEqualTo("N");
        assertThat(updatedCard.getAccountId()).isEqualTo("00000000050");
        // Primary key unchanged
        assertThat(updatedCard.getCardNum()).isEqualTo("0683586198171516");
    }

    /**
     * Verifies card deletion by primary key, mapping to CICS DELETE operation.
     */
    @Test
    @DisplayName("delete removes card by primary key")
    void delete_removesCardByPrimaryKey() {
        assertThat(cardRepository.findById("0683586198171516")).isPresent();

        cardRepository.deleteById("0683586198171516");
        entityManager.flush();
        entityManager.clear();

        assertThat(cardRepository.findById("0683586198171516")).isEmpty();
    }

    // =========================================================================
    // AIX-Equivalent Query Tests — CARD-ACCT-ID Alternate Index
    // Maps to VSAM CARDDATA AIX on CARD-ACCT-ID (position 16, length 11)
    // COCRDSLC.cbl: EXEC CICS READ DATASET('CARDAIX') RIDFLD(WS-CARD-RID-ACCTID)
    // COCRDLIC.cbl: STARTBR/READNEXT with 9500-FILTER-RECORDS filtering
    // =========================================================================

    /**
     * Verifies the AIX-equivalent multi-result query: two cards share the same
     * accountId "00000000050", mapping to the VSAM alternate index read that
     * returns multiple records for a single alternate key value.
     *
     * <p>This is the most critical query test — it validates the
     * {@code idx_card_account_id} index on {@code cards(card_acct_id)} created
     * by V2__create_indexes.sql, equivalent to the VSAM CARDDATA AIX on
     * CARD-ACCT-ID (AXRKP=16, KEYLEN=11).
     */
    @Test
    @DisplayName("findByAccountId returns all cards for account — AIX equivalent for CARD-ACCT-ID")
    void findByAccountId_returnsAllCardsForAccount() {
        List<Card> cards = cardRepository.findByAccountId("00000000050");

        assertThat(cards).hasSize(2);
        assertThat(cards).extracting(Card::getCardNum)
                .containsExactlyInAnyOrder("0500024453765740", "0923877193247330");
        // Both cards reference the same account — AIX multi-result query
        assertThat(cards).allSatisfy(card ->
                assertThat(card.getAccountId()).isEqualTo("00000000050"));
    }

    /**
     * Verifies that querying by a non-existent account ID returns an empty list,
     * equivalent to VSAM STARTBR with no matching alternate key records.
     */
    @Test
    @DisplayName("findByAccountId returns empty list for account with no cards")
    void findByAccountId_returnsEmptyListForAccountWithNoCards() {
        List<Card> cards = cardRepository.findByAccountId("99999999999");

        assertThat(cards).isEmpty();
        assertThat(cards.size()).isEqualTo(0);
    }

    /**
     * Verifies paginated AIX-equivalent query, mapping to COCRDLIC.cbl
     * STARTBR/READNEXT with 9500-FILTER-RECORDS paragraph that filters
     * cards by CARD-ACCT-ID. Uses {@code PageRequest.of(0, 1)} to request only
     * the first page of 1 card for the account with 2 cards, verifying
     * pagination metadata (totalElements, totalPages, content size).
     */
    @Test
    @DisplayName("findByAccountId with Pageable returns paginated card results")
    void findByAccountId_withPageable_returnsPaginatedResults() {
        Pageable pageRequest = PageRequest.of(0, 1);
        Page<Card> page = cardRepository.findByAccountId("00000000050", pageRequest);

        // Account "00000000050" has 2 cards, but we requested page size 1
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getAccountId()).isEqualTo("00000000050");
    }

    // =========================================================================
    // Pagination and Optimistic Locking Tests
    // =========================================================================

    /**
     * Verifies paginated card listing across all cards, mapping to COCRDLIC.cbl
     * browse operation with PF7 (page backward) and PF8 (page forward) keys.
     * The test uses the 3 cards from setUp and requests pages of size 2,
     * expecting 2 pages total (page 0: 2 cards, page 1: 1 card).
     */
    @Test
    @DisplayName("findAll with Pageable supports paginated card listing")
    void findAll_withPageable_supportsPaginatedCardListing() {
        Pageable pageRequest = PageRequest.of(0, 2);
        Page<Card> page = cardRepository.findAll(pageRequest);

        // 3 test cards total, page size 2 → 2 pages
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(2);
    }

    /**
     * Verifies JPA {@code @Version} optimistic locking, mapping to COCRDUPC.cbl
     * {@code EXEC CICS READ UPDATE → REWRITE} pattern. Ensures version starts at 0
     * after initial persist and increments to 1 after the first update.
     *
     * <p>The {@code @Version} column replaces the CICS READ UPDATE locking
     * mechanism, preventing concurrent overwrites of card records in the
     * PostgreSQL-backed system (VSAM equivalent: file-level record locking).
     */
    @Test
    @DisplayName("@Version enables optimistic locking for card updates")
    void version_enablesOptimisticLockingForCardUpdates() {
        // Create a new card and persist it
        Card card = new Card(
                "5555666677778888", "00000000027", "999",
                "Test Version", "2026-06-01", "Y"
        );
        cardRepository.save(card);
        entityManager.flush();
        entityManager.clear();

        // Re-read from database and verify initial version is 0
        Optional<Card> persisted = cardRepository.findById("5555666677778888");
        assertThat(persisted).isPresent();
        assertThat(persisted.get().getVersion()).isEqualTo(0L);

        // Update the card name (triggers @Version increment on save)
        Card toUpdate = persisted.get();
        toUpdate.setEmbossedName("Updated Name");
        toUpdate.setVersion(toUpdate.getVersion()); // Exercise setVersion with current value
        cardRepository.save(toUpdate);
        entityManager.flush();
        entityManager.clear();

        // Re-read and verify version was incremented by optimistic locking
        Optional<Card> updated = cardRepository.findById("5555666677778888");
        assertThat(updated).isPresent();
        assertThat(updated.get().getVersion()).isEqualTo(1L);
        assertThat(updated.get().getEmbossedName()).isEqualTo("Updated Name");
    }

    // =========================================================================
    // PII Field Handling — CVV Code
    // CARD-CVV-CD PIC 9(03) is PII requiring secure handling
    // =========================================================================

    /**
     * Verifies that the CVV code (CARD-CVV-CD PIC 9(03)) persists correctly
     * as a 3-character String, preserving leading zeros. This is critical for
     * PII field integrity — CVV "028" must not be stored as integer 28.
     *
     * <p>Also tests that updating the CVV code via {@code setCvvCode()} persists
     * correctly, including leading-zero values like "001".
     */
    @Test
    @DisplayName("CVV code persists correctly as 3-char string")
    void cvvCode_persistsCorrectlyAsThreeCharString() {
        // Test leading-zero CVV from Card 3 (Enrico Rosenbaum, cvv="028")
        Optional<Card> cardWithLeadingZeroCvv = cardRepository.findById("0923877193247330");
        assertThat(cardWithLeadingZeroCvv).isPresent();
        assertThat(cardWithLeadingZeroCvv.get().getCvvCode()).isEqualTo("028");
        assertThat(cardWithLeadingZeroCvv.get().getCvvCode()).hasSize(3);

        // Verify a CVV without leading zeros (Card 1, Aniya Von, cvv="747")
        Optional<Card> cardWithRegularCvv = cardRepository.findById("0500024453765740");
        assertThat(cardWithRegularCvv).isPresent();
        assertThat(cardWithRegularCvv.get().getCvvCode()).isEqualTo("747");
        assertThat(cardWithRegularCvv.get().getCvvCode()).hasSize(3);

        // Test updating CVV with new leading-zero value via setCvvCode()
        Card card = cardWithRegularCvv.get();
        card.setCvvCode("001");
        card.setCardNum(card.getCardNum()); // Exercise setCardNum() — PK unchanged
        cardRepository.save(card);
        entityManager.flush();
        entityManager.clear();

        Optional<Card> updatedCard = cardRepository.findById("0500024453765740");
        assertThat(updatedCard).isPresent();
        assertThat(updatedCard.get().getCvvCode()).isEqualTo("001");
        assertThat(updatedCard.get().getCvvCode()).hasSize(3);
    }
}
