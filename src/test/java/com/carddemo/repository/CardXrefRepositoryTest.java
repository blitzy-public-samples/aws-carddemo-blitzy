package com.carddemo.repository;

import com.carddemo.entity.Card;
import com.carddemo.entity.CardXref;
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
 * {@code @DataJpaTest} slice test for {@link CardXrefRepository} &mdash; the canonical
 * <strong>card&nbsp;&rarr;&nbsp;customer&nbsp;&rarr;&nbsp;account</strong> cross-reference
 * (relational table {@code card_xref}, legacy VSAM {@code CARDXREF} / copybook
 * {@code app/cpy/CVACT03Y.cpy}, {@code CARD-XREF-RECORD} record length 50).
 *
 * <h2>Behavioral parity verified</h2>
 * <ul>
 *   <li><strong>Keyed read by card number.</strong> {@link CardXrefRepository#findByXrefCardNum(String)}
 *       replaces the keyed {@code READ XREF-FILE} of {@code app/cbl/CBTRN02C.cbl}
 *       (paragraph {@code 1500-A-LOOKUP-XREF}) that resolves the owning customer and account
 *       during daily transaction posting. A cross-reference miss corresponds to the legacy
 *       {@code INVALID KEY} branch, which raises validation <em>reject code 100</em>
 *       ({@code 'INVALID CARD NUMBER FOUND'}); the empty-{@code Optional} test below models that
 *       outcome (AAP &sect;0.6.1).</li>
 *   <li><strong>Alternate-index browse by account id.</strong>
 *       {@link CardXrefRepository#findByXrefAcctId(Long, org.springframework.data.domain.Pageable)}
 *       replaces the {@code CARDXREF.AIX} alternate-index browse, backed by the secondary index
 *       {@code idx_cardxref_acct_id} ({@code V1__schema.sql}). The legacy 3270 browse displayed a
 *       fixed page of <strong>7</strong> rows; this test exercises that exact page size
 *       (AAP &sect;0.6.4, &sect;0.7.1).</li>
 * </ul>
 *
 * <h2>Seed ground-truth</h2>
 * <p>The H2 (PostgreSQL-mode) database is migrated by Flyway {@code V1__schema.sql ..
 * V4__seed_users.sql} on context startup. {@code V3__seed_master.sql} loads exactly
 * <strong>50</strong> {@code card_xref} rows (one per card; ground-truth
 * {@code app/data/ASCII/cardxref.txt}). The verified anchor row is card
 * {@code "9680294154603697"} &rarr; {@code xref_cust_id = 1}, {@code xref_acct_id = 1}.</p>
 *
 * <h2>Test conventions</h2>
 * <p>This class follows the project-wide repository-slice test conventions: {@code @DataJpaTest}
 * (JPA slice only), {@code @AutoConfigureTestDatabase(replace = NONE)} (use the configured
 * Flyway-migrated H2 datasource rather than a throwaway embedded one so the seed data is present),
 * and {@code @ActiveProfiles("test")} (selects {@code application-test.yml}). There is no shared
 * base class; the annotations are repeated on every repository test. The slice is transactional and
 * rolls back automatically after each test, so the synthetic rows inserted by the pagination test
 * never leak into other tests.</p>
 *
 * @see CardXrefRepository
 * @see CardXref
 * @see <a href="file:app/cpy/CVACT03Y.cpy">app/cpy/CVACT03Y.cpy (CARD-XREF-RECORD)</a>
 * @see <a href="file:app/cbl/CBTRN02C.cbl">app/cbl/CBTRN02C.cbl (XREF keyed READ / reject 100)</a>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class CardXrefRepositoryTest {

    /**
     * The fixed legacy 3270 browse page size (rows per screen). Preserved verbatim from the
     * mainframe presentation behavior per AAP &sect;0.7.1; supplied to the repository through
     * {@link PageRequest#of(int, int)} (the repository itself imposes no page size).
     */
    private static final int PAGE_SIZE = 7;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * Verifies the keyed read by card number returns the seeded cross-reference and resolves the
     * correct owning customer and account &mdash; the relational replacement for the
     * {@code CBTRN02C} {@code 1500-A-LOOKUP-XREF} keyed {@code READ}.
     */
    @Test
    void findByXrefCardNum_returnsSeededCrossReference() {
        CardXref xref = cardXrefRepository.findByXrefCardNum("9680294154603697").orElseThrow();
        assertThat(xref.getXrefCustId()).isEqualTo(1L);
        assertThat(xref.getXrefAcctId()).isEqualTo(1L);
    }

    /**
     * Verifies that an unknown card number yields an empty {@link java.util.Optional}. In the
     * legacy poster this {@code INVALID KEY} miss is what the batch maps to <em>reject code 100</em>
     * ({@code 'INVALID CARD NUMBER FOUND'}); the reject-code decision is owned by the batch/service
     * layer, so here we assert only the data-access contract (empty result).
     */
    @Test
    void findByXrefCardNum_unknownCard_isEmpty() {
        // miss -> the posting batch maps this to reject code 100 (INVALID CARD NUMBER FOUND)
        assertThat(cardXrefRepository.findByXrefCardNum("ZZZZZZZZZZZZZZZZ")).isEmpty();
    }

    /**
     * Verifies the account-scoped browse (the {@code CARDXREF.AIX} replacement) paginates at the
     * fixed legacy page size of 7.
     *
     * <p><strong>Foreign-key ordering.</strong> {@code card_xref.xref_card_num} is BOTH the primary
     * key AND a foreign key onto {@code cards.card_num} (constraint {@code fk_xref_card}). Therefore
     * each synthetic {@link Card} row MUST be persisted (and flushed) <em>before</em> the matching
     * {@link CardXref} row, otherwise the FK insert fails. The synthetic customer/account ids reuse
     * the seeded customer&nbsp;1 / account&nbsp;1 so the {@code fk_xref_cust} / {@code fk_xref_acct}
     * (and {@code fk_cards_acct}) constraints are satisfied.</p>
     *
     * <p>The pre-existing seed count for account&nbsp;1 is queried as {@code initial} rather than
     * hard-coded, so the assertion remains correct regardless of the seed's exact composition. With
     * {@code initial + 8} total rows and {@code initial >= 1}, the first page of size 7 is full and a
     * next page exists.</p>
     */
    @Test
    void findByXrefAcctId_paginatesAtPageSizeSeven() {
        long initial = cardXrefRepository.findByXrefAcctId(1L, PageRequest.of(0, 1000)).getTotalElements();

        // FK: xref_card_num -> cards. Persist the Card rows FIRST, then the CardXref rows.
        for (int i = 1; i <= 8; i++) {
            Card c = new Card();
            c.setCardNum("TESTCARD" + String.format("%08d", i)); // 16 chars
            c.setCardAcctId(1L);
            c.setCvvCode(100 + i);
            c.setEmbossedName("Test Card " + i);
            c.setExpirationDate(LocalDate.of(2030, 1, 1));
            c.setActiveStatus("Y");
            entityManager.persist(c);
        }
        entityManager.flush();

        for (int i = 1; i <= 8; i++) {
            CardXref x = new CardXref();
            x.setXrefCardNum("TESTCARD" + String.format("%08d", i));
            x.setXrefCustId(1L);
            x.setXrefAcctId(1L);
            entityManager.persist(x);
        }
        entityManager.flush();
        entityManager.clear();

        Page<CardXref> page0 = cardXrefRepository.findByXrefAcctId(1L, PageRequest.of(0, PAGE_SIZE));
        assertThat(page0.getContent()).hasSize(PAGE_SIZE);             // exactly 7 per page
        assertThat(page0.getTotalElements()).isEqualTo(initial + 8);   // 1 seed + 8 synthetic = 9
        assertThat(page0.hasNext()).isTrue();
        assertThat(page0.getContent()).allSatisfy(
                x -> assertThat(x.getXrefAcctId()).isEqualTo(1L));
    }

    /**
     * Verifies the total seeded row count matches the ground-truth fixture: {@code V3__seed_master.sql}
     * loads exactly 50 {@code card_xref} rows (one per card; {@code app/data/ASCII/cardxref.txt} has
     * 50 rows).
     */
    @Test
    void count_matchesSeedRowCount() {
        // V3__seed_master.sql seeds 50 card-xref rows (cardxref.txt has 50 rows)
        assertThat(cardXrefRepository.count()).isEqualTo(50L);
    }
}
