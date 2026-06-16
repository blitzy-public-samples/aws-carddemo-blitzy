package com.carddemo.repository;

import com.carddemo.entity.TransactionCategoryBalance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link TransactionCategoryBalance} &mdash; the per-category
 * running balance of a single account.
 *
 * <p>This interface is the Java/Spring re-expression of access to the legacy VSAM
 * {@code TCATBALF} key-sequenced dataset (copybook {@code app/cpy/CVTRA01Y.cpy},
 * {@code TRAN-CAT-BAL-RECORD}). It abstracts the relational table
 * {@code transaction_category_balance}, whose composite primary key
 * {@code (acct_id, type_cd, cat_cd)} mirrors the COBOL {@code TRAN-CAT-KEY} group.</p>
 *
 * <h2>Composite identifier</h2>
 * The entity uses an {@code @EmbeddedId} of type
 * {@link TransactionCategoryBalance.TransactionCategoryBalanceId} (fields {@code acctId},
 * {@code typeCd}, {@code catCd}); that nested {@code @Embeddable} class is therefore the
 * second type argument to {@link JpaRepository}.
 *
 * <h2>Business consumers</h2>
 * <ul>
 *   <li><strong>Interest calculation</strong> &mdash; {@code app/cbl/CBACT04C.cbl}
 *       (ported by {@code InterestCalculationService}; AAP &sect;0.6.3). The interest job
 *       iterates <em>every</em> category-balance row belonging to one account and computes
 *       {@code monthlyInterest = tranCatBal * rate / 1200} (with {@code RoundingMode.HALF_UP}
 *       in the service/batch layer). The per-account set is obtained through
 *       {@link #findByIdAcctId(Long)}.</li>
 *   <li><strong>Daily transaction posting</strong> &mdash; {@code app/cbl/CBTRN02C.cbl}
 *       (AAP &sect;0.6.1). Posting performs an upsert on a single
 *       {@code (acct_id, type_cd, cat_cd)} row: read it and, when absent, insert it; otherwise
 *       add the transaction amount. That orchestration belongs to the service/batch layer and
 *       uses the inherited
 *       {@link JpaRepository#findById(Object) findById(new TransactionCategoryBalanceId(acctId, typeCd, catCd))}
 *       followed by {@link JpaRepository#save(Object) save(...)}. No dedicated upsert method is
 *       declared here because this repository contains data access only &mdash; never balance
 *       arithmetic.</li>
 * </ul>
 *
 * <p>The interface is auto-detected as a Spring bean; it is intentionally <em>not</em> annotated
 * with {@code @Repository}. It declares no business logic, in keeping with the strict
 * Controller &rarr; Service &rarr; Repository &rarr; Entity layering of AAP &sect;0.3.2.</p>
 *
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalance.TransactionCategoryBalanceId
 */
public interface TransactionCategoryBalanceRepository
        extends JpaRepository<TransactionCategoryBalance, TransactionCategoryBalance.TransactionCategoryBalanceId> {

    /**
     * Returns every category-balance row for the supplied account, across all
     * {@code (type_cd, cat_cd)} combinations.
     *
     * <p>This is the per-account set that the interest-calculation job iterates over when
     * porting {@code app/cbl/CBACT04C.cbl} (AAP &sect;0.6.3). The method name traverses the
     * embedded-identifier path {@code id.acctId}: Spring Data resolves {@code findByIdAcctId}
     * to the {@code @EmbeddedId} property {@code id} of {@link TransactionCategoryBalance} and
     * then to its {@code acctId} field &mdash; <em>not</em> a (non-existent) top-level
     * {@code acctId} property.</p>
     *
     * <p>If the derived method name ever fails to resolve, the equivalent and unambiguous JPQL
     * form may be substituted (imports {@code org.springframework.data.jpa.repository.Query}
     * and {@code org.springframework.data.repository.query.Param}):</p>
     * <pre>{@code
     * @Query("SELECT t FROM TransactionCategoryBalance t WHERE t.id.acctId = :acctId")
     * List<TransactionCategoryBalance> findByIdAcctId(@Param("acctId") Long acctId);
     * }</pre>
     * The plain derived method is preferred and is sufficient for the embedded-id path above.
     *
     * @param acctId the account identifier ({@code TRANCAT-ACCT-ID}); must not be {@code null}
     * @return all category-balance rows for the account, or an empty list if none exist
     */
    List<TransactionCategoryBalance> findByIdAcctId(Long acctId);
}
