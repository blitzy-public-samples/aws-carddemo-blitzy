package com.aws.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;

/**
 * Integration tests for {@link TransactionCategoryBalanceRepository} against the Flyway-seeded
 * Testcontainers PostgreSQL, asserting VSAM {@code TCATBAL} parity via the composite key
 * (account + type + category) and the sequential key-order scan {@code findAll(Sort)}.
 *
 * <p>Parity oracles:
 * <ul>
 *   <li>{@code legacy/cpy/CVTRA01Y.cpy} (TRAN-CAT-BAL-RECORD, RECLN 50)</li>
 *   <li>{@code legacy/data/ASCII/tcatbal.txt} (50 rows, seeded by V2__reference_data.sql)</li>
 * </ul>
 * Read-only: the shared seeded table must not be mutated. The repository exposes no account-id
 * derived finder (composite-key random access plus full key-order scan only), matching the COBOL
 * access paths (CBTRN02C random, CBACT04C sequential).
 */
class TransactionCategoryBalanceRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    @Test
    @DisplayName("transaction_category_balance seeds exactly 50 rows (tcatbal.txt parity)")
    void seededRowCountMatchesFixture() {
        assertThat(transactionCategoryBalanceRepository.count()).isEqualTo(50L);
    }

    @Test
    @DisplayName("findById(acct=1, type=01, cat=1) returns balance 0.00")
    void findByCompositeKeyReturnsSeededRowOne() {
        Optional<TransactionCategoryBalance> found = transactionCategoryBalanceRepository.findById(
                new TransactionCategoryBalance.TransactionCategoryBalanceId(1L, "01", 1));
        assertThat(found).isPresent();
        assertThat(found.get().getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("findAll(Sort by acctId,typeCd,catCd) returns all 50 rows in ascending key order")
    void findAllSortedByCompositeKeyMatchesLegacyKeyOrder() {
        List<TransactionCategoryBalance> sorted = transactionCategoryBalanceRepository
                .findAll(Sort.by("acctId", "typeCd", "catCd"));
        assertThat(sorted).hasSize(50);
        Comparator<TransactionCategoryBalance> keyOrder =
                Comparator.comparing(TransactionCategoryBalance::getAcctId)
                        .thenComparing(t -> t.getTypeCd().trim())
                        .thenComparing(TransactionCategoryBalance::getCatCd);
        assertThat(sorted).isSortedAccordingTo(keyOrder);
    }

    @Test
    @DisplayName("findById for an unknown composite key returns empty")
    void findByMissingCompositeKeyReturnsEmpty() {
        assertThat(transactionCategoryBalanceRepository.findById(
                new TransactionCategoryBalance.TransactionCategoryBalanceId(999_999_999L, "99", 9999)))
                .isEmpty();
    }
}
