package com.aws.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.domain.CardXref;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for {@link CardXrefRepository} against the Flyway-seeded Testcontainers
 * PostgreSQL, asserting the exact seeded row count and that the derived queries reproduce the legacy
 * VSAM cross-reference access paths: {@code findByXrefCardNum} (the card-number keyed read of the
 * {@code CCXREF} base cluster) and {@code findByXrefAcctId} (the {@code CXACAIX} alternate index,
 * backed by the V3 secondary index {@code idx_card_xref_xref_acct_id}).
 *
 * <p>The cross-reference maps a 16-character card number to its owning customer id and account id.
 * Because the {@code card_xref} table is seeded once (by {@code V2__reference_data.sql}) and shared
 * across the whole {@code *IT} suite, and the {@link AbstractPostgresIntegrationTest} base declares
 * no {@code @Transactional} rollback boundary, these tests are strictly <strong>read-only</strong>:
 * they exercise only {@code count()}, {@code findByXrefCardNum(String)} and
 * {@code findByXrefAcctId(Long)}, and never mutate the seeded data.</p>
 *
 * <p>Parity oracles (AAP &sect;0.6.10):</p>
 * <ul>
 *   <li>{@code legacy/cpy/CVACT03Y.cpy} &mdash; {@code CARD-XREF-RECORD}, RECLN 50
 *       ({@code XREF-CARD-NUM PIC X(16)}, {@code XREF-CUST-ID PIC 9(09)},
 *       {@code XREF-ACCT-ID PIC 9(11)}, {@code FILLER PIC X(14)}).</li>
 *   <li>{@code legacy/data/ASCII/cardxref.txt} &mdash; 50 fixed-width rows, seeded into
 *       {@code card_xref} by {@code V2__reference_data.sql}.</li>
 * </ul>
 *
 * <p>Verified oracle values (row 1 of {@code cardxref.txt}, decoded against {@code CVACT03Y.cpy}):
 * card number {@code 0500024453765740} cross-references customer {@code 50} and account {@code 50};
 * the account id {@code 50} appears in exactly one row (all 50 card numbers are distinct, a 1:1
 * card-to-account mapping), which is the {@code CXACAIX} parity assertion. Because
 * {@code xref_card_num} is {@code CHAR(16)} and the seeded card number is exactly 16 characters wide,
 * the {@code findByXrefCardNum} comparison is exact with no trailing-space ambiguity.</p>
 *
 * <p>Origin: net-new test infrastructure with no COBOL ancestor; it validates the Spring Data JPA
 * migration of the VSAM {@code CCXREF} KSDS for the AWS CardDemo COBOL &rarr; Java 25 / Spring Boot
 * modernization, whose legacy sources are retained read-only under {@code legacy/**}.</p>
 */
class CardXrefRepositoryIT extends AbstractPostgresIntegrationTest {

    /**
     * Card number of row 1 of {@code legacy/data/ASCII/cardxref.txt}; cross-references customer 50
     * and account 50, and is the single card mapped to account 50 ({@code CXACAIX} 1:1 oracle).
     */
    private static final String SEEDED_CARD = "0500024453765740";

    /** Repository under test, resolved from the Spring context started by the base class. */
    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Test
    @DisplayName("card_xref seeds exactly 50 rows (cardxref.txt parity)")
    void seededRowCountMatchesFixture() {
        assertThat(cardXrefRepository.count()).isEqualTo(50L);
    }

    @Test
    @DisplayName("findByXrefCardNum(0500024453765740) returns customer 50 / account 50")
    void findByXrefCardNumReturnsCrossReference() {
        Optional<CardXref> found = cardXrefRepository.findByXrefCardNum(SEEDED_CARD);
        assertThat(found).isPresent();
        CardXref xref = found.get();
        assertThat(xref.getXrefCustId()).isEqualTo(50L);
        assertThat(xref.getXrefAcctId()).isEqualTo(50L);
    }

    @Test
    @DisplayName("findByXrefAcctId(50) reproduces CXACAIX -> exactly one cross-reference")
    void findByXrefAcctIdReplacesCxacaix() {
        List<CardXref> byAcct = cardXrefRepository.findByXrefAcctId(50L);
        assertThat(byAcct).hasSize(1);
        assertThat(byAcct.get(0).getXrefCardNum()).isEqualTo(SEEDED_CARD);
    }

    @Test
    @DisplayName("findByXrefCardNum for an unknown card returns empty Optional")
    void findByXrefCardNumMissingReturnsEmpty() {
        assertThat(cardXrefRepository.findByXrefCardNum("9999999999999999")).isEmpty();
    }

    @Test
    @DisplayName("findByXrefAcctId for an unknown account returns an empty list")
    void findByXrefAcctIdUnknownReturnsEmpty() {
        assertThat(cardXrefRepository.findByXrefAcctId(999_999_999L)).isEmpty();
    }
}
