package com.aws.carddemo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence integration test for the {@link TransactionType} JPA entity, executed against a real,
 * Flyway-migrated PostgreSQL database provisioned by Testcontainers.
 *
 * <p>This test is what makes Hibernate {@code spring.jpa.hibernate.ddl-auto=validate} meaningful for
 * the transaction-type reference table: it proves that the {@link TransactionType} entity mapping
 * lines up, at runtime, with the {@code transaction_type} table that {@code V1__schema.sql} creates
 * ({@code tran_type CHAR(2)} primary key + {@code tran_type_desc CHAR(50)}) and that the seven
 * reference rows loaded by {@code V2__reference_data.sql} load and round-trip through the JPA layer.
 * It thereby anchors one row of the COBOL-to-Java traceability matrix (AAP &sect;0.6.10) with an
 * executable, self-checking contract rather than a documentation claim.</p>
 *
 * <p>The {@code transaction_type} table and this entity are a one-for-one migration of the legacy
 * VSAM {@code TRANTYPE} reference file described by the COBOL copybook
 * {@code legacy/cpy/CVTRA03Y.cpy} ({@code TRAN-TYPE-RECORD}, record length 60): a two-character
 * {@code TRAN-TYPE} code, a fifty-character {@code TRAN-TYPE-DESC}, and an eight-byte trailing
 * {@code FILLER} that carries no business meaning and is intentionally not persisted.</p>
 *
 * <p><strong>Fixed-width note.</strong> Both mapped columns are fixed-length PostgreSQL
 * {@code CHAR(n)}, so values read back are blank-padded to the declared width (for example the
 * fifty-character {@code tran_type_desc}). Assertions on the description therefore compare on the
 * trimmed value (and additionally use a case-insensitive substring check) so they remain correct and
 * deterministic regardless of the trailing-space padding, while the two-character key values used
 * here ({@code "01"}..{@code "07"} and {@code "99"}) already occupy the full {@code CHAR(2)} width and
 * need no such treatment.</p>
 *
 * <p><strong>Infrastructure.</strong> The shared PostgreSQL container, the {@code test} Spring
 * profile ({@code ddl-auto=validate}, Flyway enabled), and the dynamic datasource wiring are all
 * owned by {@link AbstractPostgresIntegrationTest}; this class only adds its own persistence
 * assertions. It is annotated {@link Transactional} at class level so every {@code @Test} method runs
 * inside a transaction that is rolled back on completion, keeping the {@link #persistAndFindNewType()}
 * insert from leaking into the shared, container-cached schema. Persistence is exercised through a
 * container-managed {@link EntityManager} obtained via {@link PersistenceContext} rather than a Spring
 * Data repository, keeping this domain-level contract decoupled from the repository layer.</p>
 *
 * <p><strong>Runtime prerequisite.</strong> A reachable Docker daemon is required so Testcontainers
 * can start the {@code postgres:18-alpine} image; this is the documented local-validation constraint
 * for the migration. Class name ends in {@code IT}, so it is executed by the Maven Failsafe plugin in
 * the {@code integration-test} phase (never by Surefire).</p>
 *
 * <p>Origin: {@code legacy/cpy/CVTRA03Y.cpy} ({@code TRAN-TYPE-RECORD}). See AAP &sect;0.4.1 and
 * &sect;0.6.10.</p>
 *
 * @see TransactionType
 * @see AbstractPostgresIntegrationTest
 */
@Transactional
class TransactionTypePersistenceIT extends AbstractPostgresIntegrationTest {

    /**
     * The complete, ordered set of seeded transaction-type primary keys as loaded by
     * {@code V2__reference_data.sql}: {@code 01}=Purchase, {@code 02}=Payment, {@code 03}=Credit,
     * {@code 04}=Authorization, {@code 05}=Refund, {@code 06}=Reversal, {@code 07}=Adjustment.
     */
    private static final String[] SEEDED_TYPE_CODES = {"01", "02", "03", "04", "05", "06", "07"};

    /**
     * Container-managed JPA entity manager bound to the Flyway-migrated Testcontainers PostgreSQL
     * schema. Injected via {@link PersistenceContext}; used directly (rather than a repository) so the
     * entity-to-schema contract is verified in isolation from the repository layer.
     */
    @PersistenceContext
    private EntityManager em;

    /**
     * Verifies that the canonical seeded row (transaction type {@code "01"}) loads and that its
     * description is the expected {@code Purchase} value from {@code V2__reference_data.sql}.
     *
     * <p>Because {@code tran_type_desc} is {@code CHAR(50)}, the value read back is blank-padded to
     * fifty characters; the assertions account for this by trimming before the equality check and by
     * additionally asserting a case-insensitive substring match.</p>
     */
    @Test
    @DisplayName("Seeded transaction type 01 loads with description Purchase")
    void seededPurchaseTypeLoads() {
        TransactionType type = em.find(TransactionType.class, "01");

        assertThat(type)
                .as("seeded transaction type 01 must exist after Flyway V2 reference-data load")
                .isNotNull();
        assertThat(type.getTranType())
                .as("primary key of the loaded row")
                .isEqualTo("01");

        String description = type.getTranTypeDesc();
        assertThat(description)
                .as("description of transaction type 01")
                .isNotNull();
        // CHAR(50) is blank-padded on read, so match the meaningful content pad-safely.
        assertThat(description)
                .as("padded description contains the seeded label")
                .containsIgnoringCase("Purchase");
        assertThat(description.trim())
                .as("trimmed description equals the seeded label")
                .isEqualToIgnoringCase("Purchase");
    }

    /**
     * Verifies that every one of the seven reference rows seeded by {@code V2__reference_data.sql}
     * ({@code "01"} through {@code "07"}) is present and loads through the entity mapping with a
     * non-blank description. Iterates over a fixed, ordered set of keys and does not rely on any test
     * execution order.
     */
    @Test
    @DisplayName("All seven seeded transaction types are present")
    void allSevenSeedTypesPresent() {
        for (String code : SEEDED_TYPE_CODES) {
            TransactionType type = em.find(TransactionType.class, code);

            assertThat(type)
                    .as("seeded transaction type %s must exist", code)
                    .isNotNull();
            assertThat(type.getTranType())
                    .as("primary key of transaction type %s", code)
                    .isEqualTo(code);
            assertThat(type.getTranTypeDesc())
                    .as("description of transaction type %s must not be null", code)
                    .isNotNull();
            assertThat(type.getTranTypeDesc().trim())
                    .as("description of transaction type %s must not be blank", code)
                    .isNotEmpty();
        }
    }

    /**
     * Verifies a full persist-then-find round-trip for a new transaction type using a primary key
     * ({@code "99"}) deliberately outside the seeded {@code 01}..{@code 07} range so it can never
     * collide with reference data.
     *
     * <p>The entity is persisted and flushed to the database, the persistence context is cleared to
     * force a fresh {@code SELECT}, and the row is then re-loaded by primary key to confirm the
     * description round-trips exactly (compared on the trimmed value because {@code tran_type_desc}
     * is {@code CHAR(50)}). The class-level {@link Transactional} boundary rolls this insert back so
     * the shared schema is left untouched.</p>
     */
    @Test
    @DisplayName("A new transaction type persists and is found by primary key")
    void persistAndFindNewType() {
        TransactionType type = new TransactionType();
        type.setTranType("99");
        type.setTranTypeDesc("Integration Test Type");

        em.persist(type);
        em.flush();
        em.clear();

        TransactionType found = em.find(TransactionType.class, "99");

        assertThat(found)
                .as("persisted transaction type 99 must be found after flush and clear")
                .isNotNull();
        assertThat(found.getTranType())
                .as("primary key of the round-tripped row")
                .isEqualTo("99");
        assertThat(found.getTranTypeDesc())
                .as("description of the round-tripped row")
                .isNotNull();
        assertThat(found.getTranTypeDesc().trim())
                .as("description round-trips exactly")
                .isEqualTo("Integration Test Type");
    }
}
