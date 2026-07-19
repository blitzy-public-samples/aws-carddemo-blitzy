package com.aws.carddemo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence integration test for the composite-key JPA entity
 * {@link TransactionCategory}, exercised against a real, Flyway-migrated
 * PostgreSQL database started with Testcontainers.
 *
 * <p><strong>Origin.</strong> The entity under test is the Java translation of
 * the COBOL copybook {@code legacy/cpy/CVTRA04Y.cpy} record
 * {@code TRAN-CAT-RECORD} (RECLN 60), which describes the legacy VSAM
 * {@code TRANCATG} reference/lookup file. That copybook keys each row by the
 * composite {@code TRAN-CAT-KEY} = {@code TRAN-TYPE-CD PIC X(02)} +
 * {@code TRAN-CAT-CD PIC 9(04)} and carries a {@code TRAN-CAT-TYPE-DESC PIC
 * X(50)} description (the trailing {@code FILLER PIC X(04)} is not persisted).</p>
 *
 * <p><strong>What this validates (AAP &sect;0.6.2, &sect;0.6.10).</strong> This
 * test proves the composite-key persistence contract mandated by the VSAM
 * KSDS &rarr; PostgreSQL schema translation: that the {@link jakarta.persistence.IdClass}
 * identifier {@link TransactionCategory.TransactionCategoryId} round-trips
 * through {@link EntityManager#find(Class, Object)} for both a Flyway-seeded row
 * (read path) and a freshly-persisted row (write-then-read path). It thereby
 * verifies the entity &harr; {@code V1__schema.sql} {@code transaction_category}
 * mapping end-to-end (the {@code @IdClass} components map to the composite
 * primary key {@code (tran_type_cd, tran_cat_cd)}), contributing the runtime
 * traceability evidence required by the migration's explainability rule.</p>
 *
 * <p><strong>Fixed-width parity (AAP &sect;0.6.2).</strong> The description
 * column is {@code CHAR(50)}, chosen deliberately to preserve the legacy
 * {@code PIC X(50)} fixed-width, trailing-space semantics. PostgreSQL blank-pads
 * {@code CHAR(n)} values and the JDBC driver returns them padded to the full
 * column width, so {@link TransactionCategory#getDescription()} returns the
 * business text right-padded with spaces to 50 characters. The assertions below
 * therefore compare the logical value via {@link String#stripTrailing()} &mdash;
 * the established trailing-space parity idiom used elsewhere in this codebase
 * &mdash; rather than a raw exact match against the unpadded literal.</p>
 *
 * <p><strong>Infrastructure.</strong> Extends
 * {@link AbstractPostgresIntegrationTest}, which starts the shared
 * {@code postgres:18-alpine} container once, activates the {@code test} profile
 * (Flyway applies {@code V0 -> V1 -> V2 -> V3}; Hibernate
 * {@code ddl-auto=validate}), and binds the container's JDBC coordinates
 * dynamically. This class adds class-level
 * {@link org.springframework.transaction.annotation.Transactional} so every
 * {@code @Test} runs inside a transaction that is rolled back on completion,
 * keeping the newly-persisted row from leaking between tests or into the shared
 * seed data. Persistence is driven through a container-managed
 * {@link EntityManager} (no repository), matching the test's charter of
 * validating the raw JPA composite-key mapping.</p>
 *
 * @see TransactionCategory
 * @see TransactionCategory.TransactionCategoryId
 * @see AbstractPostgresIntegrationTest
 */
@Transactional
@DisplayName("TransactionCategory composite-key JPA persistence (PostgreSQL / Testcontainers)")
class TransactionCategoryPersistenceIT extends AbstractPostgresIntegrationTest {

    /** Transaction type code of the seeded oracle row ({@code TRAN-TYPE-CD}, CHAR(2)). */
    private static final String SEEDED_TYPE_CD = "01";

    /** Transaction category code of the seeded oracle row ({@code TRAN-CAT-CD}). */
    private static final int SEEDED_CAT_CD = 1;

    /**
     * Expected (unpadded) description of the seeded row, verified against
     * {@code V2__reference_data.sql}: {@code ('01', 1, 'Regular Sales Draft')}.
     */
    private static final String SEEDED_DESCRIPTION = "Regular Sales Draft";

    /**
     * Transaction type code for the newly-persisted row. {@code "99"} is exactly
     * two characters ({@code CHAR(2)}) and does not collide with any of the 18
     * seeded rows (whose type codes span {@code "01".."07"}).
     */
    private static final String NEW_TYPE_CD = "99";

    /**
     * Transaction category code for the newly-persisted row. {@code 9999} is the
     * maximum value that fits the {@code NUMERIC(4)} column and does not collide
     * with any seeded key.
     */
    private static final int NEW_CAT_CD = 9999;

    /** Description written and then read back for the new-row round-trip. */
    private static final String NEW_DESCRIPTION = "Integration Test Category";

    /**
     * Container-managed, transaction-scoped {@link EntityManager}. Injected with
     * {@link PersistenceContext} so it enrolls in the class-level
     * {@link Transactional} boundary of each test method.
     */
    @PersistenceContext
    private EntityManager em;

    /**
     * Verifies that a Flyway-seeded {@code transaction_category} row loads by its
     * {@link jakarta.persistence.IdClass} composite key. This exercises the
     * read path of the composite-key contract: an identifier object built from
     * the {@code (TRAN-TYPE-CD, TRAN-CAT-CD)} pair locates the exact seeded row,
     * and the {@code CHAR(50)} description matches the expected business value
     * once trailing padding is stripped.
     */
    @Test
    @DisplayName("seeded (\"01\", 1) loads by @IdClass composite key with expected description")
    void seededCategoryLoadsByCompositeKey() {
        var id = new TransactionCategory.TransactionCategoryId(SEEDED_TYPE_CD, SEEDED_CAT_CD);

        TransactionCategory category = em.find(TransactionCategory.class, id);

        assertThat(category)
                .as("seeded transaction_category row for composite key (%s, %d)",
                        SEEDED_TYPE_CD, SEEDED_CAT_CD)
                .isNotNull();
        assertThat(category.getTypeCd()).isEqualTo(SEEDED_TYPE_CD);
        assertThat(category.getCatCd()).isEqualTo(SEEDED_CAT_CD);

        // The description column is CHAR(50): PostgreSQL/JDBC returns it right-padded
        // with spaces to the full width (fixed-width parity, AAP 0.6.2), so compare
        // the logical value after stripping the trailing padding.
        assertThat(category.getDescription())
                .as("CHAR(50) description is present before trimming trailing padding")
                .isNotNull();
        assertThat(category.getDescription().stripTrailing()).isEqualTo(SEEDED_DESCRIPTION);
    }

    /**
     * Verifies the write-then-read path of the composite-key contract: a new
     * {@link TransactionCategory} with a non-colliding composite key is persisted,
     * flushed to the database, detached (via {@link EntityManager#clear()}), and
     * then re-loaded by an independently constructed
     * {@link TransactionCategory.TransactionCategoryId}. The reloaded instance is
     * a distinct managed object (proving a genuine database round-trip rather than
     * a first-level-cache hit) and carries the same composite key and description.
     * The surrounding class-level transaction is rolled back afterwards, so the
     * inserted row never escapes the test.
     */
    @Test
    @DisplayName("new (\"99\", 9999) persists and reloads by @IdClass composite key")
    void persistAndFindNewCategory() {
        TransactionCategory category = new TransactionCategory();
        category.setTypeCd(NEW_TYPE_CD);
        category.setCatCd(NEW_CAT_CD);
        category.setDescription(NEW_DESCRIPTION);

        em.persist(category);
        em.flush();
        // Detach all managed entities so the subsequent find() must hit the database
        // rather than returning the just-persisted instance from the persistence context.
        em.clear();

        TransactionCategory reloaded = em.find(TransactionCategory.class,
                new TransactionCategory.TransactionCategoryId(NEW_TYPE_CD, NEW_CAT_CD));

        assertThat(reloaded)
                .as("newly persisted transaction_category row reloaded by composite key (%s, %d)",
                        NEW_TYPE_CD, NEW_CAT_CD)
                .isNotNull()
                .isNotSameAs(category);
        assertThat(reloaded.getTypeCd()).isEqualTo(NEW_TYPE_CD);
        assertThat(reloaded.getCatCd()).isEqualTo(NEW_CAT_CD);
        // CHAR(50) blank-padding parity (AAP 0.6.2): strip the trailing padding
        // before comparing the round-tripped description to the written value.
        assertThat(reloaded.getDescription()).isNotNull();
        assertThat(reloaded.getDescription().stripTrailing()).isEqualTo(NEW_DESCRIPTION);
    }
}
