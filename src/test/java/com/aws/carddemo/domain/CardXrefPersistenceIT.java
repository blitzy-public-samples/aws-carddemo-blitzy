package com.aws.carddemo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence integration test for the three-part composite-key entity
 * {@link CardXref}, exercised against a real, Flyway-migrated PostgreSQL 18
 * database provided by Testcontainers.
 *
 * <p><strong>Origin (COBOL oracle).</strong> {@link CardXref} is migrated
 * one-for-one from the COBOL copybook {@code CARD-XREF-RECORD} (record length
 * 50) retained read-only at {@code legacy/cpy/CVACT03Y.cpy}. That layout is
 * {@code XREF-CARD-NUM PIC X(16)}, {@code XREF-CUST-ID PIC 9(09)},
 * {@code XREF-ACCT-ID PIC 9(11)} and {@code FILLER PIC X(14)}, modelling the
 * legacy VSAM KSDS {@code CCXREF} that cross-references a card number to its
 * owning customer and account. This test is net-new; it has no COBOL ancestor
 * of its own and supports the AWS CardDemo COBOL &rarr; Java 25 / Spring Boot
 * 3.5.16 migration.</p>
 *
 * <p><strong>What this validates.</strong> The purpose of this test is to prove
 * the three-part composite-key persistence contract end-to-end: that the JPA
 * {@link jakarta.persistence.IdClass} mapping declared on {@link CardXref}
 * (card number + customer id + account id, realised by
 * {@link CardXref.CardXrefId}) round-trips correctly against the relational
 * {@code card_xref} schema authored in {@code V1__schema.sql}. Per AAP
 * &sect;0.6.2 (VSAM KSDS/AIX &rarr; PostgreSQL schema translation) the VSAM
 * primary-key semantics are preserved as a relational composite primary key,
 * and per AAP &sect;0.6.10 (traceability) this test is the executable evidence
 * that the frozen composite {@code IdClass} mapping behaves as specified.</p>
 *
 * <p><strong>Infrastructure.</strong> The class extends
 * {@link AbstractPostgresIntegrationTest}, inheriting the single shared
 * {@code postgres:18-alpine} Testcontainers instance, the {@code test} Spring
 * profile, and the dynamic datasource wiring; it therefore adds no Spring or
 * container configuration of its own. The class is annotated
 * {@link Transactional} so that every test method runs inside a transaction
 * that is rolled back on completion, leaving the seeded reference data pristine
 * for sibling tests. A container-managed {@link EntityManager} (injected with
 * {@link PersistenceContext}) is used directly &mdash; no Spring Data repository
 * &mdash; so the assertions exercise the raw JPA identifier contract.</p>
 *
 * <p><strong>Runtime prerequisite.</strong> As an {@code *IT} test this is run
 * by the Maven Failsafe plugin and requires a Testcontainers-capable
 * environment (a reachable Docker daemon able to start
 * {@code postgres:18-alpine}). Flyway applies the versioned migrations in order
 * ({@code V0} &rarr; {@code V1} &rarr; {@code V2} &rarr; {@code V3}) into the
 * container, and Hibernate ({@code ddl-auto=validate}) verifies the entity
 * mappings against that materialised schema before any assertion runs.</p>
 *
 * @see CardXref
 * @see CardXref.CardXrefId
 * @see AbstractPostgresIntegrationTest
 */
@Transactional
class CardXrefPersistenceIT extends AbstractPostgresIntegrationTest {

    /**
     * Card number of a row that is guaranteed to exist after {@code V2}
     * reference data is applied. Verified against
     * {@code src/main/resources/db/migration/V2__reference_data.sql} and the
     * legacy fixture {@code legacy/data/ASCII/cardxref.txt}. Exactly sixteen
     * characters, matching the {@code CHAR(16)} column {@code xref_card_num}.
     */
    private static final String SEEDED_CARD_NUM = "0500024453765740";

    /** Customer id of the verified seeded {@code card_xref} row. */
    private static final long SEEDED_CUST_ID = 50L;

    /** Account id of the verified seeded {@code card_xref} row. */
    private static final long SEEDED_ACCT_ID = 50L;

    /**
     * Card number for the persist-and-find round-trip. Sixteen {@code '9'}
     * characters &mdash; absent from the {@code V2} seed set, so it collides
     * with neither the {@code card_xref} composite primary key nor the card
     * number.
     */
    private static final String NEW_CARD_NUM = "9999999999999999";

    /**
     * Customer id for the persist-and-find round-trip: {@code 999_999_999}, the
     * maximum value representable by the {@code xref_cust_id NUMERIC(9)} column
     * defined in {@code V1__schema.sql}.
     *
     * <p>The file specification illustrates this key with {@code 90000000001L};
     * that value has eleven digits and overflows {@code NUMERIC(9)} (PostgreSQL
     * rejects it with a numeric field overflow, so the round-trip could not
     * pass). This constant preserves the specification's intent &mdash; a key
     * far outside the seeded customer range (1&ndash;50) that cannot collide
     * &mdash; while respecting the column's declared precision, keeping the
     * test green against the real schema.</p>
     */
    private static final long NEW_CUST_ID = 999_999_999L;

    /**
     * Account id for the persist-and-find round-trip: {@code 99_999_999_999},
     * the maximum value representable by the {@code xref_acct_id NUMERIC(11)}
     * column defined in {@code V1__schema.sql}. Far outside the seeded account
     * range (1&ndash;50); cannot collide.
     */
    private static final long NEW_ACCT_ID = 99_999_999_999L;

    /**
     * Container-managed JPA entity manager bound to the current test
     * transaction. Injected via {@link PersistenceContext} so that
     * {@code persist}/{@code flush}/{@code clear}/{@code find} all participate
     * in the single transaction opened by the class-level {@link Transactional}
     * annotation and are rolled back when the test method returns.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Loads a pre-seeded cross-reference row by its full three-part
     * {@link CardXref.CardXrefId} composite key and confirms every key
     * component round-trips.
     *
     * <p>This proves the {@link jakarta.persistence.IdClass} lookup path:
     * {@link EntityManager#find(Class, Object)} resolves the entity using the
     * composite identifier built from the card number, customer id and account
     * id. The card number is asserted with a trailing
     * {@link String#strip()} because {@code xref_card_num} is a fixed-width
     * {@code CHAR(16)} column whose reads are blank-padded to sixteen
     * characters.</p>
     */
    @Test
    void seededXrefLoadsByCompositeKey() {
        CardXref.CardXrefId key =
                new CardXref.CardXrefId(SEEDED_CARD_NUM, SEEDED_CUST_ID, SEEDED_ACCT_ID);

        CardXref found = entityManager.find(CardXref.class, key);

        assertThat(found)
                .as("seeded card_xref row must be locatable by its composite @IdClass key")
                .isNotNull();
        assertThat(found.getXrefCustId())
                .as("customer id component of the composite key")
                .isEqualTo(SEEDED_CUST_ID);
        assertThat(found.getXrefAcctId())
                .as("account id component of the composite key")
                .isEqualTo(SEEDED_ACCT_ID);
        assertThat(found.getXrefCardNum().strip())
                .as("card number component of the composite key (CHAR(16), trailing padding stripped)")
                .isEqualTo(SEEDED_CARD_NUM);
    }

    /**
     * Persists a brand-new cross-reference row with a non-colliding three-part
     * composite key, then re-reads it by that key to confirm the write and the
     * {@link jakarta.persistence.IdClass} identity survive a full
     * detach/reload cycle.
     *
     * <p>After {@code persist}, the persistence context is flushed to force the
     * {@code INSERT} and then cleared so the subsequent
     * {@link EntityManager#find(Class, Object)} genuinely reloads the row from
     * the database rather than returning the still-managed instance. The whole
     * method runs inside the class-level {@link Transactional} boundary and is
     * rolled back afterwards, so no data leaks into sibling tests.</p>
     */
    @Test
    void persistAndFindNewXref() {
        CardXref candidate = new CardXref();
        candidate.setXrefCardNum(NEW_CARD_NUM);
        candidate.setXrefCustId(NEW_CUST_ID);
        candidate.setXrefAcctId(NEW_ACCT_ID);

        entityManager.persist(candidate);
        entityManager.flush();
        entityManager.clear();

        CardXref.CardXrefId key =
                new CardXref.CardXrefId(NEW_CARD_NUM, NEW_CUST_ID, NEW_ACCT_ID);

        CardXref reloaded = entityManager.find(CardXref.class, key);

        assertThat(reloaded)
                .as("newly persisted card_xref row must be reloadable by its composite @IdClass key")
                .isNotNull();
        assertThat(reloaded.getXrefCustId())
                .as("customer id component round-trips")
                .isEqualTo(NEW_CUST_ID);
        assertThat(reloaded.getXrefAcctId())
                .as("account id component round-trips")
                .isEqualTo(NEW_ACCT_ID);
        assertThat(reloaded.getXrefCardNum().strip())
                .as("card number component round-trips (CHAR(16), trailing padding stripped)")
                .isEqualTo(NEW_CARD_NUM);
    }

    /**
     * Card number used by {@link #duplicateCardNumberIsRejected()} to prove the
     * single-key uniqueness invariant. Exactly sixteen characters (matching the
     * {@code CHAR(16)} column {@code xref_card_num}) and absent from the
     * {@code V2} seed set, so the first insert is always accepted and only the
     * uniqueness rule — not a seed collision — governs the second insert.
     */
    private static final String DUP_CARD_NUM = "DUPXREFCARD00001";

    /**
     * Regression test for the legacy VSAM KSDS single-key uniqueness of the
     * card cross-reference (finding F-001). The legacy base cluster
     * {@code CCXREF} keys on the 16-byte card number alone
     * ({@code legacy/jcl/XREFFILE.jcl} defines {@code KEYS(16 0)}), so it can
     * never hold two records that share a card number; the account id is only a
     * <em>nonunique</em> alternate index ({@code CXACAIX}). The Java schema
     * preserves the frozen three-column composite {@link CardXref.CardXrefId}
     * (card + customer + account) for structural traceability, which alone would
     * admit two rows sharing a card number but differing in customer/account —
     * silently breaking parity. The {@code UNIQUE} constraint
     * {@code uk_card_xref_card_num} (authored by Flyway
     * {@code V4__card_xref_unique_card_num.sql}, matching the entity's declared
     * {@code @UniqueConstraint}) restores the KSDS invariant (AAP &sect;0.6.2,
     * {@code docs/decision-log.md} F6).
     *
     * <p>The test persists one row for {@link #DUP_CARD_NUM}, flushes it
     * successfully, then persists a second row with the <em>same</em> card
     * number but a different customer and account (so the composite primary key
     * differs and does <strong>not</strong> catch the duplicate). Flushing the
     * second row must be rejected by {@code uk_card_xref_card_num} with a
     * PostgreSQL unique-violation (SQLState {@code 23505}), reproducing the
     * duplicate-{@code card_num} rejection the VSAM base cluster would enforce.
     * Without the constraint (the F-001 defect) both inserts would be accepted
     * and this assertion would fail. The whole method runs inside the
     * class-level {@link Transactional} boundary and is rolled back afterwards,
     * so nothing leaks into sibling tests.</p>
     */
    @Test
    void duplicateCardNumberIsRejected() {
        CardXref first = new CardXref();
        first.setXrefCardNum(DUP_CARD_NUM);
        first.setXrefCustId(111_111_111L);
        first.setXrefAcctId(11_111_111_111L);
        entityManager.persist(first);
        entityManager.flush();

        CardXref duplicate = new CardXref();
        duplicate.setXrefCardNum(DUP_CARD_NUM);
        duplicate.setXrefCustId(222_222_222L);
        duplicate.setXrefAcctId(22_222_222_222L);
        entityManager.persist(duplicate);

        assertThatThrownBy(entityManager::flush)
                .as("a second card_xref row sharing xref_card_num='%s' (differing only in "
                        + "customer/account) must be rejected by uk_card_xref_card_num, "
                        + "preserving VSAM KSDS single-key uniqueness (F-001)", DUP_CARD_NUM)
                .isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("uk_card_xref_card_num");
    }
}
