package com.aws.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.domain.TransactionType;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for {@link TransactionTypeRepository} against the Flyway-seeded
 * Testcontainers PostgreSQL, asserting VSAM {@code TRANTYPE} reference-table parity
 * (lookup by 2-character transaction-type code).
 *
 * <p>The legacy mainframe application read the {@code TRANTYPE} key-sequenced (KSDS)
 * reference file randomly by the two-character type code to resolve and validate
 * transaction-type descriptions. That contract is preserved here: the seeded
 * {@code transaction_type} table must contain exactly the fixture rows, and a keyed
 * {@link TransactionTypeRepository#findById(Object) findById(String)} lookup must return
 * the matching description for each code (and empty for an unknown code).</p>
 *
 * <p>Parity oracles:
 * <ul>
 *   <li>{@code legacy/cpy/CVTRA03Y.cpy} (TRAN-TYPE-RECORD, RECLN 60: {@code TRAN-TYPE X(02)},
 *       {@code TRAN-TYPE-DESC X(50)}, {@code FILLER X(08)})</li>
 *   <li>{@code legacy/data/ASCII/trantype.txt} (7 fixed-width rows, seeded into
 *       {@code transaction_type} by {@code V2__reference_data.sql})</li>
 * </ul>
 *
 * <p>The description column is COBOL {@code PIC X(50)} mapped to a space-padded
 * {@code CHAR(50)}; assertions therefore {@link String#trim() trim} the returned value
 * before comparing against the decoded fixture text.</p>
 *
 * <p>Read-only: this suite shares the single seeded container defined by
 * {@link AbstractPostgresIntegrationTest} and must not mutate the reference table. It
 * exercises only {@link TransactionTypeRepository#count()} and
 * {@link TransactionTypeRepository#findById(Object)}; the framework annotations and the
 * PostgreSQL container are inherited from the base class and are deliberately not
 * re-declared here.</p>
 *
 * <p>Origin: net-new parity/oracle test with no COBOL ancestor, derived from the
 * {@code CVTRA03Y} copybook layout and the {@code trantype.txt} ASCII fixture retained
 * read-only under {@code legacy/**} (AAP &sect;0.6.10).</p>
 */
class TransactionTypeRepositoryIT extends AbstractPostgresIntegrationTest {

    /**
     * Repository under test, supplied by the Spring application context started by the
     * base class. Access is read-only across every test method in this class.
     */
    @Autowired
    private TransactionTypeRepository transactionTypeRepository;

    /**
     * Verifies the {@code transaction_type} reference table is seeded with exactly the
     * seven rows present in {@code trantype.txt}, guarding against missing or extra
     * reference data.
     */
    @Test
    @DisplayName("transaction_type seeds exactly 7 rows (trantype.txt parity)")
    void seededRowCountMatchesFixture() {
        assertThat(transactionTypeRepository.count()).isEqualTo(7L);
    }

    /**
     * Verifies the canonical keyed lookup: type code {@code "01"} resolves to
     * {@code "Purchase"}, mirroring the legacy random read of {@code TRANTYPE} by
     * {@code TRAN-TYPE}. The primary key is a two-character {@code String} matching the
     * {@code CHAR(2)} column, and the description is trimmed to drop the {@code CHAR(50)}
     * space padding.
     */
    @Test
    @DisplayName("findById('01') returns Purchase")
    void findByIdReturnsPurchase() {
        Optional<TransactionType> found = transactionTypeRepository.findById("01");
        assertThat(found).isPresent();
        assertThat(found.get().getTranType().trim()).isEqualTo("01");
        assertThat(found.get().getTranTypeDesc().trim()).isEqualTo("Purchase");
    }

    /**
     * Verifies that all seven seeded transaction-type codes map to their exact decoded
     * descriptions, establishing full-table parity with {@code trantype.txt}.
     */
    @Test
    @DisplayName("all 7 seeded transaction-type codes map to their exact descriptions")
    void allSevenTypesHaveExactDescriptions() {
        assertDescription("01", "Purchase");
        assertDescription("02", "Payment");
        assertDescription("03", "Credit");
        assertDescription("04", "Authorization");
        assertDescription("05", "Refund");
        assertDescription("06", "Reversal");
        assertDescription("07", "Adjustment");
    }

    /**
     * Verifies that a keyed lookup for a code absent from the reference table returns an
     * empty {@link Optional}, preserving the legacy "record not found" outcome for an
     * unknown transaction type.
     */
    @Test
    @DisplayName("findById for an unknown type code returns empty")
    void findByIdMissingReturnsEmpty() {
        assertThat(transactionTypeRepository.findById("99")).isEmpty();
    }

    /**
     * Asserts that the given two-character transaction-type code is present in the seeded
     * reference table and that its (trimmed) description equals the expected fixture text.
     *
     * @param code         the two-character transaction-type code (primary key)
     * @param expectedDesc the exact description decoded from {@code trantype.txt}
     */
    private void assertDescription(String code, String expectedDesc) {
        Optional<TransactionType> found = transactionTypeRepository.findById(code);
        assertThat(found)
                .as("transaction_type row for code %s", code)
                .isPresent();
        assertThat(found.get().getTranTypeDesc().trim()).isEqualTo(expectedDesc);
    }
}
