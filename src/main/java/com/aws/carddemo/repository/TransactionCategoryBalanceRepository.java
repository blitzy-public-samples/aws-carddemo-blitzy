package com.aws.carddemo.repository;

import com.aws.carddemo.domain.TransactionCategoryBalance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
 *       {@code RECORD KEY IS FD-TRAN-CAT-KEY}). This maps to the inherited
 *       {@code findAll(Sort)} with {@code Sort.by("acctId", "typeCd", "catCd")}
 *       supplied by the batch reader.</li>
 * </ul>
 *
 * <p>Deterministic ordering fidelity for the sequential scan relies on the
 * Flyway-provisioned C/POSIX collation applied to the {@code CHAR} key column
 * (AAP &sect;0.6.6) combined with the caller-supplied {@code Sort} specification;
 * this repository itself declares no ordering method. Consistent with the
 * no-feature-expansion mandate, this interface adds no query methods beyond
 * those inherited from {@link JpaRepository}. It performs no arithmetic; the
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
}
