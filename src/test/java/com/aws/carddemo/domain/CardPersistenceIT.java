package com.aws.carddemo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence integration test for the JPA entity {@link Card} against a real, Flyway-migrated
 * PostgreSQL database provisioned by Testcontainers.
 *
 * <p>Provenance and oracle: {@code Card} is migrated one-for-one from the legacy COBOL copybook
 * {@code CARD-RECORD} defined in {@code legacy/cpy/CVACT02Y.cpy} (record length 150), which
 * described the layout of the VSAM {@code CARDDAT} KSDS. Whereas the sibling {@code CardTest}
 * locks the pure object-relational mapping via reflection, this test asserts the <em>runtime</em>
 * contract end-to-end: that the {@code @Entity} mapping agrees with the {@code card} table created
 * by {@code src/main/resources/db/migration/V1__schema.sql}, that the reference/seed rows loaded by
 * {@code V2__reference_data.sql} round-trip through Hibernate, and that a freshly persisted row is
 * retrievable by its primary key (AAP sections 0.4.1, 0.6.2, and 0.6.10).</p>
 *
 * <p><strong>Preserved misspelling.</strong> The COBOL field {@code CARD-EXPIRAION-DATE} is
 * misspelled in the source copybook ("EXPIRAION"). That spelling is preserved verbatim in the Java
 * accessor {@link Card#getCardExpiraionDate()} and the column {@code card_expiraion_date} to keep a
 * 1:1 mapping with the copybook, honoring the "no changes beyond the technology substitution"
 * mandate (AAP section 0.4.1). Both test methods assert against {@code getCardExpiraionDate()} so a
 * well-meaning "correction" of that name would fail the build.</p>
 *
 * <p><strong>Fixed-width {@code CHAR(n)} handling.</strong> Per the type-mapping rule
 * {@code PIC X(n)} &rarr; {@code CHAR(n)} (AAP section 0.6.2), the text columns
 * {@code card_embossed_name} ({@code CHAR(50)}) and {@code card_active_status} ({@code CHAR(1)}) are
 * blank-padded on read, so their values carry trailing spaces. Assertions on these columns therefore
 * always {@link String#strip()} the retrieved value before comparing. The {@code card_num}
 * {@code CHAR(16)} primary key is exercised only with exactly-16-character values so that key
 * comparison is unambiguous and no padding mismatch can occur.</p>
 *
 * <p><strong>Infrastructure.</strong> This class extends {@link AbstractPostgresIntegrationTest},
 * inheriting the shared {@code postgres:18-alpine} Testcontainers database, the {@code test} profile
 * (Flyway applies {@code V0} &rarr; {@code V1} &rarr; {@code V2} &rarr; {@code V3} and Hibernate runs
 * {@code ddl-auto=validate}), and the dynamic, non-hardcoded datasource wiring. A class-level
 * {@link Transactional @Transactional} boundary rolls back every test method, so the
 * {@link #persistAndFindNewCard()} insert never pollutes the shared seed data. The JPA
 * {@link EntityManager} is injected directly (no repository) to assert the entity&harr;schema
 * contract as closely as possible. Because the class name ends in {@code IT}, it runs in the Maven
 * Failsafe (integration) phase and requires a reachable Docker daemon.</p>
 *
 * @see Card
 * @see AbstractPostgresIntegrationTest
 */
@Transactional
@DisplayName("Card persistence contract (legacy/cpy/CVACT02Y.cpy -> card table)")
class CardPersistenceIT extends AbstractPostgresIntegrationTest {

    /**
     * Primary key of the deterministic seed row asserted by {@link #seededCardMatchesSpotCheck()}.
     * Loaded by {@code V2__reference_data.sql} and cross-checked against the fixture
     * {@code legacy/data/ASCII/carddata.txt}. Exactly 16 characters to match the {@code CHAR(16)}
     * key without padding ambiguity.
     */
    private static final String SEEDED_CARD_NUM = "0500024453765740";

    /**
     * Non-colliding 16-character primary key for the transient row created by
     * {@link #persistAndFindNewCard()}. Verified absent from {@code V2__reference_data.sql} so the
     * insert cannot clash with seed data; the surrounding transaction is rolled back regardless.
     */
    private static final String NEW_CARD_NUM = "9999999999999999";

    /**
     * The container-managed, transaction-aware JPA entity manager. Injected via the JPA-standard
     * {@link PersistenceContext @PersistenceContext} so that {@code persist}/{@code find} join the
     * rollback transaction established by the class-level {@link Transactional @Transactional}.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Verifies that the deterministic seed card loaded by {@code V2__reference_data.sql} is present
     * and that every persisted column round-trips through the {@link Card} entity mapping with the
     * expected values. Fixed-width {@code CHAR} text columns are {@link String#strip() stripped}
     * before comparison to discard trailing blank padding.
     */
    @Test
    @DisplayName("seeded card 0500024453765740 round-trips with the expected column values")
    void seededCardMatchesSpotCheck() {
        Card card = entityManager.find(Card.class, SEEDED_CARD_NUM);

        assertThat(card)
                .as("seed row %s must exist (V2__reference_data.sql)", SEEDED_CARD_NUM)
                .isNotNull();
        assertThat(card.getCardAcctId()).isEqualTo(50L);
        assertThat(card.getCardCvvCd()).isEqualTo(747);
        assertThat(card.getCardExpiraionDate()).isEqualTo(LocalDate.of(2023, 3, 9));
        assertThat(card.getCardActiveStatus().strip()).isEqualTo("Y");
        assertThat(card.getCardEmbossedName().strip()).isEqualTo("Aniya Von");
    }

    /**
     * Verifies that a newly constructed {@link Card} persists and is retrievable by its primary key.
     * After {@code persist}/{@code flush}/{@code clear}, the entity manager is forced to reload the
     * row from the database, proving the insert reached PostgreSQL and that the round-tripped column
     * values (including the preserved-misspelling {@code cardExpiraionDate}) match what was written.
     * The class-level transaction rolls the insert back when the test completes.
     */
    @Test
    @DisplayName("new card persists and is re-read by primary key after clear")
    void persistAndFindNewCard() {
        Card newCard = new Card(
                NEW_CARD_NUM,
                90000000001L,
                123,
                "Test Holder",
                LocalDate.of(2031, 12, 31),
                "Y");

        entityManager.persist(newCard);
        entityManager.flush();
        entityManager.clear();

        Card found = entityManager.find(Card.class, NEW_CARD_NUM);

        assertThat(found)
                .as("card %s must be retrievable after flush/clear", NEW_CARD_NUM)
                .isNotNull();
        assertThat(found.getCardCvvCd()).isEqualTo(123);
        assertThat(found.getCardAcctId()).isEqualTo(90000000001L);
        assertThat(found.getCardExpiraionDate()).isEqualTo(LocalDate.of(2031, 12, 31));
        assertThat(found.getCardEmbossedName().strip()).isEqualTo("Test Holder");
    }
}
