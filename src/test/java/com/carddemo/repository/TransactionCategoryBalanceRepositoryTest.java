package com.carddemo.repository;

import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalance.TransactionCategoryBalanceId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} slice test for {@link TransactionCategoryBalanceRepository}.
 *
 * <p>Parity: replaces VSAM {@code TCATBALF} composite-key reads (copybook
 * {@code app/cpy/CVTRA01Y.cpy}; {@code TRAN-CAT-KEY} = {@code TRANCAT-ACCT-ID PIC 9(11)} +
 * {@code TRANCAT-TYPE-CD PIC X(02)} + {@code TRANCAT-CD PIC 9(04)}, i.e. VSAM key length 17 per
 * {@code app/catlg/LISTCAT.txt}). This test pins two access paths down to byte-compatible
 * behaviour:</p>
 * <ul>
 *   <li>the inherited {@code findById} on the 3-part {@code @EmbeddedId}
 *       {@code (acct_id, type_cd, cat_cd)}; and</li>
 *   <li>the custom derived query {@link TransactionCategoryBalanceRepository#findByIdAcctId(Long)}
 *       which traverses the embedded-id path {@code id.acctId} and supplies the per-account
 *       category-balance set iterated by the interest-calculation port of
 *       {@code app/cbl/CBACT04C.cbl} (AAP &sect;0.6.3).</li>
 * </ul>
 *
 * <p><strong>Seed ground-truth</strong> ({@code app/data/ASCII/tcatbal.txt} &rarr;
 * {@code V3__seed_master.sql}): the {@code transaction_category_balance} table holds exactly
 * <em>50</em> rows, one per account, every row {@code (acct_id, '01', 1, 0.00)}. So account 1's
 * seeded set has size 1: key {@code (1, "01", 1)} &rarr; balance {@code 0.00}.</p>
 *
 * <p><strong>Test conventions (binding, AAP-mandated)</strong>: the three class annotations below
 * appear verbatim on every repository slice test (no shared base class). Monetary balances are
 * compared with {@code isEqualByComparingTo} (never {@code isEqualTo}) so the {@code NUMERIC(12,2)}
 * scale of the persisted value does not cause a spurious mismatch. No {@code @Transactional} is
 * declared manually &mdash; {@code @DataJpaTest} already wraps each test method in a transaction
 * that is rolled back afterwards, restoring the Flyway-applied seed for the next method.</p>
 *
 * @see TransactionCategoryBalanceRepository
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalance.TransactionCategoryBalanceId
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class TransactionCategoryBalanceRepositoryTest {

    @Autowired
    private TransactionCategoryBalanceRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * Verifies composite-key {@code findById} against the seeded row {@code (1, "01", 1)} and
     * asserts the running balance equals {@code 0.00} (every seeded row starts at zero, per
     * {@code tcatbal.txt}). Uses {@code isEqualByComparingTo} to ignore {@code NUMERIC(12,2)}
     * scale differences.
     */
    @Test
    void findById_returnsSeededZeroBalanceRow() {
        TransactionCategoryBalance row =
                repository.findById(new TransactionCategoryBalanceId(1L, "01", 1)).orElseThrow();
        assertThat(row.getTranCatBal()).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    /**
     * Verifies that {@link TransactionCategoryBalanceRepository#findByIdAcctId(Long)} returns
     * <em>all</em> category-balance rows for a single account &mdash; the per-account iteration
     * the interest job ({@code CBACT04C}) relies on.
     *
     * <p>The seed has exactly one {@code ('01', 1)} row for account 1, so two additional rows are
     * persisted using <strong>valid foreign-key</strong> category codes ({@code transaction_category}
     * rows {@code ('02', 1)} "Cash payment" and {@code ('07', 1)} "Sales draft credit adjustment"
     * both exist via {@code V2__seed_reference.sql}) against the existing account {@code 1}. After a
     * {@code persist}/{@code flush}/{@code clear} (so the query hits the database rather than the
     * persistence-context cache), the per-account set must contain {@code initial + 2} rows spanning
     * type codes {@code {01, 02, 07}}, every row keyed to account 1.</p>
     */
    @Test
    void findByIdAcctId_returnsAllCategoryRowsForAccount() {
        // Seed has exactly one ('01',1) row per account.
        long initial = repository.findByIdAcctId(1L).size();

        TransactionCategoryBalance extra1 = new TransactionCategoryBalance();
        extra1.setId(new TransactionCategoryBalanceId(1L, "02", 1));   // category "Cash payment" exists
        extra1.setTranCatBal(new BigDecimal("100.00"));

        TransactionCategoryBalance extra2 = new TransactionCategoryBalance();
        extra2.setId(new TransactionCategoryBalanceId(1L, "07", 1));   // category "Sales draft credit adjustment" exists
        extra2.setTranCatBal(new BigDecimal("200.00"));

        entityManager.persist(extra1);
        entityManager.persist(extra2);
        entityManager.flush();
        entityManager.clear();

        List<TransactionCategoryBalance> rows = repository.findByIdAcctId(1L);
        assertThat(rows).hasSize((int) initial + 2);
        assertThat(rows).allSatisfy(r -> assertThat(r.getId().getAcctId()).isEqualTo(1L));
        assertThat(rows)
                .extracting(r -> r.getId().getTypeCd())
                .contains("01", "02", "07");
    }

    /**
     * Verifies that the embedded-id derived query returns an empty list for an account that has no
     * category-balance rows, rather than {@code null} or an error.
     */
    @Test
    void findByIdAcctId_unknownAccount_isEmpty() {
        assertThat(repository.findByIdAcctId(99999L)).isEmpty();
    }

    /**
     * Verifies the total seeded row count. {@code V3__seed_master.sql} inserts 50
     * category-balance rows (exactly one per account).
     */
    @Test
    void count_matchesSeedRowCount() {
        // V3__seed_master.sql seeds 50 category-balance rows (one per account)
        assertThat(repository.count()).isEqualTo(50L);
    }
}
