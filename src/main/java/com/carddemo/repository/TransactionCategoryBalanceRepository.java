package com.carddemo.repository;

import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link TransactionCategoryBalance} (the {@code TCATBAL} file).
 *
 * <p>Replaces VSAM keyed access to the original {@code TCATBAL} file. The underlying
 * {@code tran_cat_balances} table maps the 50-byte {@code TRAN-CAT-BAL-RECORD} layout from
 * {@code app/cpy/CVTRA01Y.cpy} (CardDemo_v1.0-15-g27d6c6f-68), lines 4-10, whose key is the
 * concatenation of the account id, transaction-type code, and transaction-category code:</p>
 * <pre>
 *   05 TRAN-CAT-KEY.
 *     10 TRANCAT-ACCT-ID  PIC 9(11).   --&gt; accountId (account_id BIGINT)
 *     10 TRANCAT-TYPE-CD  PIC X(02).   --&gt; typeCd    (type_cd CHAR(2))
 *     10 TRANCAT-CD       PIC 9(04).   --&gt; categoryCd(cat_cd  CHAR(4))
 *   05 TRAN-CAT-BAL       PIC S9(09)V99. --&gt; balance (NUMERIC(15,2))
 * </pre>
 *
 * <p><strong>Composite primary key (PR-15).</strong> Because the COBOL record is keyed on three
 * fields, the {@link TransactionCategoryBalance} entity declares an {@code @EmbeddedId} of type
 * {@link TransactionCategoryBalanceId} (field order {@code account_id}, {@code type_cd},
 * {@code cat_cd}, mirroring the COBOL key concatenation). This interface therefore extends
 * {@code JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId>}: the inherited
 * {@code findById}, {@code existsById}, and {@code deleteById} signatures all bind to the
 * {@link TransactionCategoryBalanceId} composite-key type.</p>
 *
 * <p><strong>PR-06 &mdash; TCATBAL composite-key upsert (CRITICAL).</strong> This repository is
 * the persistence foundation for the transaction-posting upsert performed by {@code CBTRN02C}
 * ({@code 2700-UPDATE-TCATBAL}, {@code app/cbl/CBTRN02C.cbl} L467-L501) and the interest-posting
 * update in {@code CBACT04C}. The original COBOL attempts a keyed {@code READ}; on
 * {@code INVALID KEY} it creates a new record with the composite key and
 * {@code TRAN-CAT-BAL = DALYTRAN-AMT}, otherwise it adds {@code DALYTRAN-AMT} to the existing
 * {@code TRAN-CAT-BAL} and rewrites. The inherited operations express this upsert directly:</p>
 * <pre>
 *   TransactionCategoryBalanceId key =
 *       new TransactionCategoryBalanceId(acctId, typeCd, catCd);
 *   if (repo.existsById(key)) {            // READ ... NOT INVALID KEY
 *       TransactionCategoryBalance bal = repo.findById(key).orElseThrow();
 *       bal.setTranCatBal(bal.getTranCatBal().add(amount)); // ADD DALYTRAN-AMT TO TRAN-CAT-BAL
 *       repo.save(bal);                    // REWRITE
 *   } else {                               // INVALID KEY -&gt; create
 *       repo.save(new TransactionCategoryBalance(key, amount)); // WRITE
 *   }
 * </pre>
 * <p>The {@code INVALID KEY} branch maps to {@code existsById(...) == false} /
 * {@code Optional.empty()}; both arms terminate in {@code save(...)} (JPA {@code merge}).</p>
 *
 * <p>The {@code JpaRepository} operations satisfy every consumer and no custom finder methods are
 * required (AAP &sect;0.4.1.5). Initial balances are seeded by
 * {@code V5__seed_master_data.sql} from {@code app/data/ASCII/tcatbal.txt}. The CICS/VSAM access
 * verbs this interface supersedes:</p>
 * <ul>
 *   <li>{@code findById(TransactionCategoryBalanceId)} / {@code existsById(...)} &mdash; replace
 *       {@code EXEC CICS READ} (and the {@code INVALID KEY} probe) on the concatenated
 *       {@code (account, type, category)} key.</li>
 *   <li>{@code save(TransactionCategoryBalance)} &mdash; replaces both {@code EXEC CICS WRITE}
 *       (insert on {@code INVALID KEY}) and {@code EXEC CICS REWRITE} (update of an existing
 *       balance) via JPA upsert semantics.</li>
 *   <li>{@code findAll()} &mdash; replaces the sequential full-file scan used by the category
 *       balance report ({@code PRTCATBL}).</li>
 *   <li>{@code count()}, {@code deleteById(TransactionCategoryBalanceId)} &mdash; standard
 *       inherited maintenance operations.</li>
 * </ul>
 *
 * <p><strong>Consumers.</strong> {@code TransactionPostingProcessor} /
 * {@code TransactionCategoryBalanceUpsertWriter} (POSTTRAN/CBTRN02C),
 * {@code InterestCalculationTasklet} (INTCALC/CBACT04C),
 * {@code CategoryBalanceReportJobConfig} (PRTCATBL), and
 * {@code DataInitializationJobConfig} (initial seed load of the standard
 * transaction-category balances from {@code app/data/ASCII/tcatbal.txt}). Access is by
 * composite primary key or full scan; there is no alternate-index (AIX) equivalent for this
 * table.</p>
 *
 * <p><strong>Money (PR-16).</strong> The single monetary field
 * {@code TransactionCategoryBalance.tranCatBal} (COBOL {@code TRAN-CAT-BAL PIC S9(09)V99},
 * physical column {@code balance NUMERIC(15,2)}) is modelled as {@link java.math.BigDecimal}
 * with scale 2 and {@code RoundingMode.HALF_UP}, mirroring the COBOL packed-decimal semantics;
 * {@code float}/{@code double} are forbidden for any monetary value, and balance comparisons
 * use {@link java.math.BigDecimal#compareTo(java.math.BigDecimal)} (never {@code equals}). This
 * is enforced entirely by the entity mapping &mdash; the repository is type-agnostic and
 * performs no arithmetic; the {@code += DALYTRAN-AMT} roll-up (PR-06) and the
 * {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} interest formula (PR-01) live in the consumer
 * writer/tasklet layer.</p>
 *
 * <p><strong>No optimistic locking.</strong> Per AAP &sect;0.3.3 the {@code @Version} optimistic
 * lock is restricted to {@code Account}, {@code Card}, {@code Customer}, and {@code Transaction};
 * the balance roll-ups here are performed inside the single-threaded batch chunk transaction, so
 * this entity carries no {@code @Version} column. Lost-update safety for the concurrent
 * CBTRN02C / CBACT04C balance upserts is provided by the surrounding {@code @Transactional}
 * boundary (PR-24) under the default {@code READ_COMMITTED} isolation.</p>
 *
 * <p><strong>Pattern &amp; scope.</strong> Per the Repository Pattern (AAP &sect;0.3.3 #1)
 * this extends {@code JpaRepository} (not {@code CrudRepository}) to inherit the full CRUD,
 * sort, and paging API. The explicit {@code @Repository} stereotype marks the interface for
 * component scanning and activates Spring's
 * {@code PersistenceExceptionTranslationPostProcessor}, translating provider-specific
 * persistence exceptions into the {@code org.springframework.dao.DataAccessException}
 * hierarchy. This is a pure data-access component and carries no business logic. Spring Data
 * uses standard {@code org.springframework.*} imports here (PR-28 reserves the
 * {@code jakarta.*} namespace for the entity's persistence annotations).</p>
 *
 * @see com.carddemo.entity.TransactionCategoryBalance
 * @see com.carddemo.entity.TransactionCategoryBalanceId
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface TransactionCategoryBalanceRepository
        extends JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId> {
}
