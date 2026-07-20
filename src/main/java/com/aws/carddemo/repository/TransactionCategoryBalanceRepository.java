package com.aws.carddemo.repository;

import com.aws.carddemo.domain.TransactionCategoryBalance;

import jakarta.persistence.QueryHint;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

import java.util.stream.Stream;

/**
 * Spring Data JPA repository for the {@link TransactionCategoryBalance} entity,
 * replacing the legacy VSAM {@code TCATBAL} file I/O with relational access over
 * PostgreSQL in the AWS CardDemo COBOL-to-Java migration.
 *
 * <p>The repository is typed on the entity's nested composite-key class
 * {@link TransactionCategoryBalance.TransactionCategoryBalanceId}, which mirrors
 * the three-part legacy VSAM key {@code TRAN-CAT-KEY} (account identifier +
 * transaction-type code + transaction-category code). The VSAM composite-key
 * semantics are therefore preserved exactly, matching the entity's
 * {@code @IdClass} declaration.</p>
 *
 * <p>Origin: legacy/cpy/CVTRA01Y.cpy (TRAN-CAT-BAL-RECORD, RECLN 50); VSAM TCATBAL;
 * composite key TRAN-CAT-KEY (account+type+category).</p>
 *
 * <p>The two legacy jobs that touch {@code TCATBAL} are both served by methods
 * inherited from {@link JpaRepository}, so no bespoke query methods are required:</p>
 * <ul>
 *   <li><strong>Random composite-key read/update</strong> &mdash; the daily
 *       transaction posting job ({@code CBTRN02C}) reads and rewrites a single
 *       balance row addressed by the full VSAM key {@code FD-TRAN-CAT-KEY}. This
 *       maps to the inherited
 *       {@code findById(TransactionCategoryBalance.TransactionCategoryBalanceId)}
 *       followed by {@code save(...)}.</li>
 *   <li><strong>Sequential key-order scan</strong> &mdash; the monthly
 *       interest-calculation job ({@code CBACT04C}) reads the file sequentially
 *       in ascending key order ({@code ACCESS MODE IS SEQUENTIAL},
 *       {@code RECORD KEY IS FD-TRAN-CAT-KEY}). This maps to
 *       {@link #streamAllByAccountKeyOrder()}, a forward-only, cursor-backed
 *       stream ordered by {@code acctId}, {@code typeCd}, then {@code catCd}
 *       ascending. Streaming (rather than a materialized {@code findAll(Sort)}
 *       list) processes one row at a time with bounded memory, preserving the
 *       account control-break scan without loading the whole {@code TCATBAL}
 *       dataset into the heap (review finding&#160;#21).</li>
 * </ul>
 *
 * <p>Deterministic ordering fidelity for the sequential scan relies on the
 * Flyway-provisioned C/POSIX collation applied to the {@code CHAR} key column
 * (AAP &sect;0.6.6) combined with the {@code ORDER BY} baked into
 * {@link #streamAllByAccountKeyOrder()}. Consistent with the
 * no-feature-expansion mandate, this interface adds no business query methods
 * beyond the streaming key-order scan. It performs no arithmetic; the
 * entity's monetary {@code balance} field is a {@link java.math.BigDecimal} for
 * decimal fidelity.</p>
 *
 * <p>Consumed by the {@code PostTransactionJobConfig} (random read/update) and
 * {@code InterestCalcJobConfig} (sequential key-order scan) Spring Batch
 * configurations.</p>
 */
@Repository
public interface TransactionCategoryBalanceRepository
        extends JpaRepository<TransactionCategoryBalance, TransactionCategoryBalance.TransactionCategoryBalanceId> {

    /**
     * Streams every transaction-category-balance row in ascending composite-key order
     * ({@code acctId}, then {@code typeCd}, then {@code catCd}), reproducing the COBOL
     * {@code ACCESS MODE IS SEQUENTIAL} / {@code RECORD KEY IS FD-TRAN-CAT-KEY} scan of
     * {@code CBACT04C} so that all category-balance rows for one account are contiguous and the
     * interest job's account control-break logic sees them in key order.
     *
     * <p><strong>Bounded memory (review finding&#160;#21):</strong> the result is a lazily-populated,
     * forward-only cursor-backed {@link Stream}, not a materialized list, so the interest job never
     * loads the entire {@code TCATBAL} dataset into the heap. The caller <em>must</em> consume the
     * stream inside a try-with-resources block so the underlying JDBC cursor is released. The
     * {@code READ_ONLY} hint avoids dirty-checking snapshots and the fetch-size hint bounds the JDBC
     * row buffer, matching the sibling {@link TransactionRepository#streamAllByCardOrder()} scan.</p>
     *
     * <p><strong>Ordering fidelity (AAP &sect;0.6.6):</strong> the {@code trancat_acct_id},
     * {@code trancat_type_cd}, and {@code trancat_cd} columns use the {@code C} / {@code POSIX}
     * collation, giving the bytewise/ASCII ordering that matches the legacy EBCDIC-sorted key sequence
     * for the numeric and uppercase-alphanumeric key components involved.</p>
     *
     * @return a lazily-populated, cursor-backed stream of all category-balance rows ordered by
     *         {@code acctId}, {@code typeCd}, then {@code catCd} ascending; never {@code null}
     */
    @QueryHints({
            @QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "200"),
            @QueryHint(name = HibernateHints.HINT_READ_ONLY, value = "true")
    })
    @Query("select b from TransactionCategoryBalance b "
            + "order by b.acctId asc, b.typeCd asc, b.catCd asc")
    Stream<TransactionCategoryBalance> streamAllByAccountKeyOrder();
}
