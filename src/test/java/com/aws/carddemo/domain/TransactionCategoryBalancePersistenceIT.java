package com.aws.carddemo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence integration test for the composite-key money entity
 * {@link TransactionCategoryBalance}, exercised against a real, Flyway-migrated
 * PostgreSQL database started by Testcontainers.
 *
 * <p><strong>COBOL oracle / origin.</strong> The entity under test is migrated
 * one-for-one from the AWS CardDemo copybook {@code legacy/cpy/CVTRA01Y.cpy}
 * ({@code TRAN-CAT-BAL-RECORD}, record length 50 bytes; VSAM KSDS
 * {@code TCATBAL}). Its VSAM composite key {@code TRAN-CAT-KEY} is
 * {@code TRANCAT-ACCT-ID PIC 9(11)} + {@code TRANCAT-TYPE-CD PIC X(02)} +
 * {@code TRANCAT-CD PIC 9(04)}, and its money field is
 * {@code TRAN-CAT-BAL PIC S9(09)V99}.</p>
 *
 * <p><strong>What this verifies.</strong></p>
 * <ul>
 *   <li><em>Composite-key round-trip (AAP &sect;0.6.2, &sect;0.6.10).</em> The
 *       three-part JPA {@code @IdClass}
 *       ({@link TransactionCategoryBalance.TransactionCategoryBalanceId})
 *       resolves the legacy {@code TRAN-CAT-KEY} both for reading seeded rows
 *       and for locating a freshly persisted row, proving the primary-key
 *       semantics are preserved exactly.</li>
 *   <li><em>Decimal fidelity (AAP &sect;0.6.1).</em> The {@code NUMERIC(11,2)}
 *       balance column round-trips a {@link java.math.BigDecimal} with its
 *       fixed scale of two preserved, guaranteeing COBOL fixed-scale monetary
 *       semantics and never a floating-point representation.</li>
 * </ul>
 *
 * <p><strong>Infrastructure.</strong> Extends
 * {@link AbstractPostgresIntegrationTest}, which starts a single shared
 * {@code postgres:18-alpine} container, activates the {@code test} profile, and
 * applies the versioned Flyway migrations (schema {@code V1__schema.sql} and
 * reference/seed data {@code V2__reference_data.sql}) so this test runs against
 * exactly the production schema and seed data. The class-level
 * {@link Transactional @Transactional} boundary rolls back every test method, so
 * the row inserted by {@link #persistAndFindNewBalanceWithExactScale()} never
 * survives the test and the shared database stays pristine for sibling tests.
 * Data access uses the JPA {@link EntityManager} directly (no repository), as
 * this test targets the entity mapping itself.</p>
 *
 * @see TransactionCategoryBalance
 * @see AbstractPostgresIntegrationTest
 */
@Transactional
class TransactionCategoryBalancePersistenceIT extends AbstractPostgresIntegrationTest {

    /**
     * Container-managed JPA entity manager, bound to the rollback transaction
     * established by the class-level {@link Transactional @Transactional}
     * annotation.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Verifies that a seeded {@code transaction_category_balance} row is located
     * by its three-part composite key and that its {@code NUMERIC(11,2)} balance
     * loads as the exact seeded value.
     *
     * <p>The seed data in {@code V2__reference_data.sql} inserts the row
     * {@code (trancat_acct_id = 1, trancat_type_cd = '01', trancat_cd = 1)} with
     * {@code tran_cat_bal = 0.00}. Building the matching
     * {@link TransactionCategoryBalance.TransactionCategoryBalanceId} and looking
     * it up through {@link EntityManager#find(Class, Object)} confirms the
     * {@code @IdClass} mapping mirrors the legacy {@code TRAN-CAT-KEY}.</p>
     */
    @Test
    void seededBalanceLoadsByCompositeKey() {
        TransactionCategoryBalance found = entityManager.find(
                TransactionCategoryBalance.class,
                new TransactionCategoryBalance.TransactionCategoryBalanceId(1L, "01", 1));

        assertThat(found)
                .as("seeded transaction_category_balance row (acctId=1, typeCd=01, catCd=1) must exist")
                .isNotNull();
        assertThat(found.getBalance())
                .as("seeded balance must equal 0.00")
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    /**
     * Verifies that a newly persisted balance round-trips through PostgreSQL with
     * its value and its fixed scale of two preserved, proving {@code NUMERIC(11,2)}
     * decimal fidelity (AAP &sect;0.6.1).
     *
     * <p>A non-colliding composite key is used &mdash; {@code acctId = 90000000001}
     * (11 digits, well outside the 1..50 seeded range), {@code typeCd = "99"}
     * ({@code CHAR(2)}), {@code catCd = 9999} &mdash; so the insert can never clash with
     * seeded rows. After {@code persist}/{@code flush}/{@code clear}, the row is
     * re-read from the database (not the first-level cache) and asserted to equal
     * {@code 1234.56} with an exact {@link BigDecimal#scale() scale} of two. The
     * class-level {@link Transactional @Transactional} boundary rolls the insert
     * back once the test completes.</p>
     */
    @Test
    void persistAndFindNewBalanceWithExactScale() {
        TransactionCategoryBalance entity = new TransactionCategoryBalance();
        entity.setAcctId(90000000001L);
        entity.setTypeCd("99");
        entity.setCatCd(9999);
        entity.setBalance(new BigDecimal("1234.56"));

        entityManager.persist(entity);
        entityManager.flush();
        entityManager.clear();

        TransactionCategoryBalance found = entityManager.find(
                TransactionCategoryBalance.class,
                new TransactionCategoryBalance.TransactionCategoryBalanceId(90000000001L, "99", 9999));

        assertThat(found)
                .as("persisted transaction_category_balance row must be retrievable by its composite key")
                .isNotNull();
        assertThat(found.getBalance())
                .as("persisted balance must equal 1234.56")
                .isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(found.getBalance().scale())
                .as("NUMERIC(11,2) column must preserve a fixed scale of two")
                .isEqualTo(2);
    }
}
