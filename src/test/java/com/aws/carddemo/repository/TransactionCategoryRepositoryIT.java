package com.aws.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.domain.TransactionCategory;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for {@link TransactionCategoryRepository} against the Flyway-seeded
 * Testcontainers PostgreSQL, asserting VSAM {@code TRANCATG} reference-table parity via the
 * composite key ({@code TRAN-TYPE-CD} + {@code TRAN-CAT-CD}).
 *
 * <p>These tests run in the Failsafe / {@code verify} phase (class name ends in {@code IT}) and
 * exercise the full Spring context and Hibernate mapping wired by the shared base class, rather
 * than a JPA slice, so the entity's {@code @IdClass} composite-key mapping is validated against the
 * real relational schema created by the Flyway migrations. The backing PostgreSQL container, the
 * {@code test} Spring profile, and the dynamic datasource wiring are all supplied by
 * {@link AbstractPostgresIntegrationTest}; this subclass therefore declares no framework
 * annotations of its own.</p>
 *
 * <p>Parity oracles:
 * <ul>
 *   <li>{@code legacy/cpy/CVTRA04Y.cpy} (TRAN-CAT-RECORD, RECLN 60)</li>
 *   <li>{@code legacy/data/ASCII/trancatg.txt} (18 rows, seeded by V2__reference_data.sql)</li>
 * </ul>
 *
 * <p>Read-only: the shared seeded {@code transaction_category} table is asserted against but never
 * mutated (the base class opens no rollback transaction), so only {@code count()} and
 * {@code findById(...)} are used. Composite-key lookups pass a {@code typeCd} that fills the
 * {@code CHAR(2)} width exactly (for example {@code "01"}) to keep the PostgreSQL {@code bpchar}
 * key comparison deterministic; the space-padded {@code CHAR(50)} description is compared after
 * {@link String#trim()}.</p>
 */
class TransactionCategoryRepositoryIT extends AbstractPostgresIntegrationTest {

    /**
     * Repository under test, injected from the Spring context started by the base class.
     */
    @Autowired
    private TransactionCategoryRepository transactionCategoryRepository;

    /**
     * Verifies that the Flyway reference-data migration seeds exactly the 18 rows present in the
     * legacy {@code trancatg.txt} fixture, confirming full reference-table load parity.
     */
    @Test
    @DisplayName("transaction_category seeds exactly 18 rows (trancatg.txt parity)")
    void seededRowCountMatchesFixture() {
        assertThat(transactionCategoryRepository.count()).isEqualTo(18L);
    }

    /**
     * Verifies composite-key retrieval: the first fixture row keyed on transaction type
     * {@code "01"} and category {@code 1} resolves to the description {@code "Regular Sales Draft"},
     * decoded from {@code trancatg.txt} against the {@code CVTRA04Y.cpy} layout.
     */
    @Test
    @DisplayName("findById(type=01, cat=1) returns 'Regular Sales Draft'")
    void findByCompositeKeyReturnsSeededRowOne() {
        Optional<TransactionCategory> found = transactionCategoryRepository.findById(
                new TransactionCategory.TransactionCategoryId("01", 1));
        assertThat(found).isPresent();
        TransactionCategory tc = found.get();
        assertThat(tc.getTypeCd().trim()).isEqualTo("01");
        assertThat(tc.getCatCd()).isEqualTo(1);
        assertThat(tc.getDescription().trim()).isEqualTo("Regular Sales Draft");
    }

    /**
     * Verifies that a composite key absent from the seeded reference data yields an empty
     * {@link Optional}, mirroring the legacy {@code TRANCATG} "record not found" read outcome.
     */
    @Test
    @DisplayName("findById for an unknown (type, category) returns empty")
    void findByMissingCompositeKeyReturnsEmpty() {
        assertThat(transactionCategoryRepository.findById(
                new TransactionCategory.TransactionCategoryId("ZZ", 9999))).isEmpty();
    }
}
