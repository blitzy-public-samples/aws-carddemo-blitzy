package com.aws.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.domain.Card;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for {@link CardRepository} against the Flyway-seeded Testcontainers PostgreSQL
 * database, asserting the exact seeded row count, primary-key ({@code CARD-NUM}) field parity, and
 * that the derived query {@link CardRepository#findByCardAcctId(Long)} faithfully reproduces the
 * legacy VSAM {@code CARDAIX} alternate index (materialised at the database level by the V3 index
 * {@code idx_card_card_acct_id} on {@code card(card_acct_id)}).
 *
 * <p>This is a net-new, from-scratch persistence test with no COBOL ancestor; it validates the
 * COBOL/CICS/VSAM &rarr; Java 25 / Spring Boot 3.5.16 migration of the card master file. It runs in
 * the Maven Failsafe ({@code verify}) phase because its class name ends in {@code IT}.
 *
 * <p>Parity oracles (per AAP &sect;0.6.10):
 * <ul>
 *   <li>{@code legacy/cpy/CVACT02Y.cpy} &mdash; {@code CARD-RECORD}, RECLN 150 ({@code CARD-NUM
 *       X(16)} primary key, {@code CARD-ACCT-ID 9(11)}, {@code CARD-CVV-CD 9(03)},
 *       {@code CARD-EMBOSSED-NAME X(50)}, {@code CARD-EXPIRAION-DATE X(10)},
 *       {@code CARD-ACTIVE-STATUS X(01)}, {@code FILLER X(59)}).</li>
 *   <li>{@code legacy/data/ASCII/carddata.txt} &mdash; 50 fixed-width rows, seeded into the
 *       {@code card} table by {@code V2__reference_data.sql}.</li>
 * </ul>
 *
 * <p>The {@code card_num} primary key fills {@code CHAR(16)} exactly, so {@code findById} performs an
 * exact key comparison. The {@code CHAR(n)} character columns ({@code card_embossed_name},
 * {@code card_active_status}) are returned space-padded by the JDBC driver, so their assertions
 * apply {@link String#trim()}. The card fixture is strictly 1:1 (50 cards map to 50 distinct
 * accounts), so the by-account access path returns exactly one card per account.
 *
 * <p>Read-only contract: this test extends {@link AbstractPostgresIntegrationTest}, which starts a
 * single shared container without a rollback {@code @Transactional} boundary. The shared seeded
 * {@code card} table therefore must never be mutated, so only the read operations
 * {@code count()}, {@code findById(String)}, {@link CardRepository#findByCardAcctId(Long)} and
 * {@link CardRepository#findByCardAcctIdOrderByCardNumAsc(Long)} are exercised.
 */
class CardRepositoryIT extends AbstractPostgresIntegrationTest {

    /**
     * Card number of the first fixture row in {@code legacy/data/ASCII/carddata.txt}. It is exactly
     * 16 characters, filling the {@code card_num CHAR(16)} primary key so that {@code findById}
     * comparisons are exact.
     */
    private static final String SEEDED_CARD = "0500024453765740";

    @Autowired
    private CardRepository cardRepository;

    @Test
    @DisplayName("card seeds exactly 50 rows (carddata.txt parity)")
    void seededRowCountMatchesFixture() {
        assertThat(cardRepository.count()).isEqualTo(50L);
    }

    @Test
    @DisplayName("findById(0500024453765740) returns the seeded card fields exactly")
    void findByIdReturnsSeededCard() {
        Optional<Card> found = cardRepository.findById(SEEDED_CARD);
        assertThat(found).isPresent();
        Card card = found.get();
        assertThat(card.getCardAcctId()).isEqualTo(50L);
        assertThat(card.getCardCvvCd()).isEqualTo(747);
        assertThat(card.getCardEmbossedName().trim()).isEqualTo("Aniya Von");
        assertThat(card.getCardExpiraionDate()).isEqualTo(LocalDate.of(2023, 3, 9));
        assertThat(card.getCardActiveStatus().trim()).isEqualTo("Y");
    }

    @Test
    @DisplayName("findByCardAcctId(50) reproduces CARDAIX -> exactly one card")
    void findByCardAcctIdReplacesCardAix() {
        List<Card> byAcct = cardRepository.findByCardAcctId(50L);
        assertThat(byAcct).hasSize(1);
        assertThat(byAcct.get(0).getCardNum()).isEqualTo(SEEDED_CARD);

        List<Card> ordered = cardRepository.findByCardAcctIdOrderByCardNumAsc(50L);
        assertThat(ordered).hasSize(1);
        assertThat(ordered.get(0).getCardNum()).isEqualTo(SEEDED_CARD);
    }

    @Test
    @DisplayName("findByCardAcctId for an unknown account returns an empty list")
    void findByCardAcctIdUnknownReturnsEmpty() {
        assertThat(cardRepository.findByCardAcctId(999_999_999L)).isEmpty();
    }

    @Test
    @DisplayName("findById for an unknown card number returns empty")
    void findByIdMissingReturnsEmpty() {
        assertThat(cardRepository.findById("9999999999999999")).isEmpty();
    }
}
