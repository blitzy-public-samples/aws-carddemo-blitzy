package com.carddemo.repository;

import com.carddemo.entity.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} slice test for {@link TransactionTypeRepository}.
 *
 * <p><strong>Parity.</strong> This test pins the relational replacement for the legacy VSAM
 * {@code TRANTYPE} key-sequenced dataset (copybook {@code app/cpy/CVTRA03Y.cpy},
 * {@code TRAN-TYPE-RECORD}, {@code RECLN = 60}; primary key {@code TRAN-TYPE} {@code PIC X(02)},
 * {@code KEYLEN = 2} / {@code RKP = 0} per {@code app/catlg/LISTCAT.txt}). Where the COBOL online
 * and batch programs performed VSAM keyed reads of the two-character transaction-type code, the
 * Java application resolves the same lookup through standard Spring Data JPA CRUD. This test
 * verifies that {@code findById}, {@code existsById}, and {@code count} reproduce those keyed-read
 * semantics over the {@code transaction_type} lookup table (AAP &sect;0.3.2 Repository pattern,
 * &sect;0.4.1.2, &sect;0.6.4 VSAM&rarr;JPA index translation).</p>
 *
 * <p><strong>Ground-truth seed.</strong> The {@code transaction_type} table is materialized by the
 * Flyway migration {@code src/main/resources/db/migration/V2__seed_reference.sql}, which seeds
 * exactly <strong>seven</strong> rows transcribed from the ASCII fixture
 * {@code app/data/ASCII/trantype.txt}:</p>
 * <pre>
 *   type_cd  type_desc
 *   -------  -------------
 *   01       Purchase
 *   02       Payment
 *   03       Credit
 *   04       Authorization
 *   05       Refund
 *   06       Reversal
 *   07       Adjustment
 * </pre>
 * <p>Neither {@code V3__seed_master.sql} nor {@code V4__seed_users.sql} inserts into this table, so
 * the row count is a stable {@code 7L} that {@link #count_matchesSeedRowCount()} asserts.</p>
 *
 * <h2>Binding test conventions (folder-wide rule)</h2>
 * <p>This is the <strong>canonical, simplest</strong> repository slice test in the project and is
 * intended as the template for every other {@code @DataJpaTest} in
 * {@code com.carddemo.repository}. The three class-level annotations are <em>mandatory and must be
 * repeated verbatim</em> on each repository test &mdash; there is deliberately no shared base
 * class:</p>
 * <ul>
 *   <li>{@link DataJpaTest @DataJpaTest} &mdash; bootstraps a JPA-only slice (entities, repositories,
 *       {@code EntityManager}, plus Flyway auto-configuration) and wraps each test method in a
 *       transaction that is rolled back afterward, so the seed rows are never mutated across
 *       tests. Because this class lives in {@code com.carddemo.repository}, the slice locates the
 *       {@code @SpringBootConfiguration} ({@code com.carddemo.CardDemoApplication}) by walking up
 *       the package tree.</li>
 *   <li>{@link AutoConfigureTestDatabase @AutoConfigureTestDatabase}{@code (replace = NONE)} &mdash;
 *       suppresses {@code @DataJpaTest}'s default behavior of swapping in a blank embedded database.
 *       Keeping the configured H2 {@code test} datasource is essential: it lets Flyway apply
 *       {@code V1}&ndash;{@code V4} so the seed-count and lookup assertions below have real data to
 *       read. Without {@code replace = NONE} the schema would be empty and every assertion here
 *       would fail.</li>
 *   <li>{@link ActiveProfiles @ActiveProfiles}{@code ("test")} &mdash; selects
 *       {@code src/test/resources/application-test.yml}, which configures the in-memory H2 datasource
 *       in PostgreSQL-compatibility mode ({@code MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE}) so the
 *       PostgreSQL-dialect Flyway migrations run unchanged, with Hibernate set to
 *       {@code ddl-auto=validate} (Flyway is authoritative for the schema).</li>
 * </ul>
 *
 * <p><strong>Read-only.</strong> Every test method here only reads; none mutate, insert, or delete
 * seed rows. Assertions are therefore tied directly to the verified seven-row reference seed. The
 * seeded {@code type_cd} values are each exactly two characters ({@code "01".."07"}), so even though
 * the column is {@code CHAR(2)} no trailing-space trimming is required when comparing identifiers.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class TransactionTypeRepositoryTest {

    /**
     * The repository under test, injected by Spring's test context. Field injection is acceptable
     * (and idiomatic) in a {@code @DataJpaTest} slice; the proxy is the production Spring Data JPA
     * implementation backing the Flyway-seeded {@code transaction_type} table.
     */
    @Autowired
    private TransactionTypeRepository transactionTypeRepository;

    /**
     * Keyed read by the primary key {@code "01"} returns the seeded {@code Purchase} type, exercising
     * the JPA replacement for a VSAM keyed read of the {@code TRANTYPE} dataset. Both the identifier
     * and the description are asserted to confirm the row is mapped correctly end to end.
     */
    @Test
    void findById_returnsSeededPurchaseType() {
        Optional<TransactionType> result = transactionTypeRepository.findById("01");
        assertThat(result).isPresent();
        assertThat(result.get().getTypeCd()).isEqualTo("01");
        assertThat(result.get().getTypeDesc()).isEqualTo("Purchase");
    }

    /**
     * Keyed read by the last seeded primary key {@code "07"} returns the {@code Adjustment} type,
     * confirming the full seven-row seed is present (not just the first row) and that the highest
     * key resolves correctly.
     */
    @Test
    void findById_returnsAdjustmentType() {
        Optional<TransactionType> result = transactionTypeRepository.findById("07");
        assertThat(result).isPresent();
        assertThat(result.get().getTypeDesc()).isEqualTo("Adjustment");
    }

    /**
     * {@code existsById} returns {@code true} for a seeded key ({@code "02"} &rarr; {@code Payment})
     * and {@code false} for a key that was never seeded ({@code "99"}), mirroring the legacy
     * record-found / record-not-found distinction of a VSAM keyed read.
     */
    @Test
    void existsById_trueForSeeded_falseForUnknown() {
        assertThat(transactionTypeRepository.existsById("02")).isTrue();
        assertThat(transactionTypeRepository.existsById("99")).isFalse();
    }

    /**
     * A keyed read for an unknown identifier ({@code "99"}) yields an empty {@link Optional},
     * the JPA analog of a VSAM "record not found" status on the {@code TRANTYPE} dataset.
     */
    @Test
    void findById_unknownKey_isEmpty() {
        assertThat(transactionTypeRepository.findById("99")).isEmpty();
    }

    /**
     * The total row count matches the reference seed. {@code V2__seed_reference.sql} seeds exactly
     * seven transaction types (the seven rows of {@code app/data/ASCII/trantype.txt}), and no later
     * migration adds to this table, so {@code count()} must equal {@code 7L}.
     */
    @Test
    void count_matchesSeedRowCount() {
        // V2__seed_reference.sql seeds 7 transaction types (trantype.txt has 7 rows)
        assertThat(transactionTypeRepository.count()).isEqualTo(7L);
    }
}
