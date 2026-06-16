package com.carddemo.repository;

import com.carddemo.entity.Card;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} slice test for {@link CardRepository} &mdash; the relational replacement for
 * the legacy VSAM {@code CARDDATA} key-sequenced dataset and its {@code CARDAIX} alternate index.
 *
 * <h2>What this test proves (legacy parity)</h2>
 * <ul>
 *   <li><strong>Primary-key (RID) access</strong> &mdash; the inherited
 *       {@code findById(String)} reproduces the VSAM {@code READ CARDDATA} by the 16-byte card
 *       number ({@code KEYLEN 16}, copybook {@code app/cpy/CVACT02Y.cpy}, RECLN 150).</li>
 *   <li><strong>Paginated alternate-index browse</strong> &mdash; the derived query
 *       {@link CardRepository#findByCardAcctId(Long, org.springframework.data.domain.Pageable)}
 *       reproduces the {@code CARDAIX} {@code STARTBR} / {@code READNEXT} browse performed by the
 *       online program {@code app/cbl/COCRDLIC.cbl} (the "List Credit Cards" screen). That program
 *       fixes the screen window at <strong>7 rows</strong> ({@code WS-MAX-SCREEN-LINES VALUE 7});
 *       this binding business rule (AAP &sect;0.6.4 / &sect;0.7.1) is preserved here as
 *       {@code PageRequest.of(n, 7)} and the query is backed by index {@code idx_cards_acct_id}.</li>
 *   <li><strong>Total row count</strong> &mdash; the seed loaded by {@code V3__seed_master.sql}
 *       (ground-truth {@code app/data/ASCII/carddata.txt}) contains exactly 50 cards, one per
 *       account.</li>
 * </ul>
 *
 * <h2>Test infrastructure</h2>
 * <p>The class uses the binding repository-test conventions: {@link DataJpaTest} for the JPA slice,
 * {@link AutoConfigureTestDatabase}{@code (replace = NONE)} so the H2 datasource declared in
 * {@code src/test/resources/application-test.yml} is used verbatim (Flyway applies {@code V1..V4} on
 * context startup; Hibernate only validates), and {@link ActiveProfiles}{@code ("test")} to select
 * that profile. {@code @DataJpaTest} is transactional and rolls back after each test, so the
 * synthetic cards persisted by {@link #findByCardAcctId_paginatesAtPageSizeSeven()} are
 * automatically removed and do not leak into sibling tests.</p>
 *
 * @see CardRepository
 * @see Card
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class CardRepositoryTest {

    /**
     * The legacy fixed screen window from {@code COCRDLIC} ({@code WS-MAX-SCREEN-LINES VALUE 7}),
     * preserved as the Spring Data page size. This is a parity-critical business rule (AAP
     * &sect;0.7.1) and MUST remain {@code 7}.
     */
    private static final int PAGE_SIZE = 7;

    /**
     * The single card seeded for account {@code 1} by {@code V3__seed_master.sql}
     * (ground-truth row in {@code app/data/ASCII/carddata.txt}).
     */
    private static final String SEEDED_CARD_FOR_ACCOUNT_ONE = "9680294154603697";

    /** The owning account used throughout the test; exists in the {@code accounts} seed (FK-safe). */
    private static final long ACCOUNT_ONE = 1L;

    /** Total cards seeded by {@code V3__seed_master.sql} (carddata.txt has 50 rows). */
    private static final long SEEDED_CARD_COUNT = 50L;

    /** Number of synthetic cards added to account 1 to exercise paging beyond a single page. */
    private static final int SYNTHETIC_CARDS = 8;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * Verifies primary-key (RID) access: the card seeded for account 1 is retrievable by its
     * 16-character card number and is linked to account {@code 1}. This mirrors the VSAM
     * {@code READ CARDDATA} by record key.
     */
    @Test
    void findById_returnsSeededCardForAccountOne() {
        Card card = cardRepository.findById(SEEDED_CARD_FOR_ACCOUNT_ONE).orElseThrow();

        assertThat(card.getCardAcctId()).isEqualTo(ACCOUNT_ONE);
    }

    /**
     * Verifies the {@code CARDAIX}-replacement browse paginates at the legacy fixed window of 7 rows.
     *
     * <p>Account 1 is seeded with only a single card, which cannot demonstrate paging, so this test
     * persists {@value #SYNTHETIC_CARDS} additional synthetic cards for account 1 and then pages the
     * derived query at {@link #PAGE_SIZE}. The synthetic primary keys use a {@code "TESTCARD"} letter
     * prefix ({@code "TESTCARD" + %08d}) so the 16-character keys can never collide with the numeric
     * 16-digit seed card numbers, and all reference account {@code 1} (which exists in the
     * {@code accounts} seed, satisfying the {@code fk_cards_acct} foreign key).</p>
     *
     * <p>The baseline count for account 1 is queried <em>before</em> inserting (rather than
     * hard-coded) for robustness; the assertions then confirm exactly 7 rows on the first page, a
     * total of {@code initial + 8} elements, a further page available
     * ({@code hasNext() == true}, mirroring the COBOL {@code CA-NEXT-PAGE-EXISTS} flag), that every
     * returned row belongs to account 1, and that the second page carries the remainder.</p>
     */
    @Test
    void findByCardAcctId_paginatesAtPageSizeSeven() {
        // Baseline: query the existing card count for account 1 (do NOT hard-code the seed count).
        long initial = cardRepository
                .findByCardAcctId(ACCOUNT_ONE, PageRequest.of(0, 1000))
                .getTotalElements();

        // Persist 8 synthetic, FK-safe cards for account 1 so paging can be exercised.
        for (int i = 1; i <= SYNTHETIC_CARDS; i++) {
            Card c = new Card();
            c.setCardNum("TESTCARD" + String.format("%08d", i)); // 8 + 8 = 16 chars, letter-prefixed
            c.setCardAcctId(ACCOUNT_ONE);
            c.setCvvCode(100 + i);
            c.setEmbossedName("Test Card " + i);
            c.setExpirationDate(LocalDate.of(2030, 1, 1));
            c.setActiveStatus("Y");
            entityManager.persist(c);
        }
        // Flush the inserts and clear the persistence context so the paginated query hits the DB.
        entityManager.flush();
        entityManager.clear();

        long expectedTotal = initial + SYNTHETIC_CARDS;

        // First page: exactly PAGE_SIZE (7) rows, correct total, a further page available.
        Page<Card> page0 = cardRepository.findByCardAcctId(ACCOUNT_ONE, PageRequest.of(0, PAGE_SIZE));
        assertThat(page0.getContent()).hasSize(PAGE_SIZE);
        assertThat(page0.getTotalElements()).isEqualTo(expectedTotal);
        assertThat(page0.hasNext()).isTrue();
        assertThat(page0.getContent())
                .allSatisfy(c -> assertThat(c.getCardAcctId()).isEqualTo(ACCOUNT_ONE));

        // Second page: the remainder (with the verified seed, initial == 1 -> remainder == 2).
        Page<Card> page1 = cardRepository.findByCardAcctId(ACCOUNT_ONE, PageRequest.of(1, PAGE_SIZE));
        assertThat(page1.getContent()).hasSize((int) expectedTotal - PAGE_SIZE);
        assertThat(page1.getContent())
                .allSatisfy(c -> assertThat(c.getCardAcctId()).isEqualTo(ACCOUNT_ONE));
    }

    /**
     * Verifies the total seeded row count matches the legacy {@code CARDDATA} dataset: 50 cards,
     * loaded by {@code V3__seed_master.sql} from the 50-row {@code app/data/ASCII/carddata.txt}.
     */
    @Test
    void count_matchesSeedRowCount() {
        assertThat(cardRepository.count()).isEqualTo(SEEDED_CARD_COUNT);
    }
}
