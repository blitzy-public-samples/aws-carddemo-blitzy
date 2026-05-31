package com.carddemo.repository;

import com.carddemo.entity.RejectedTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link RejectedTransaction} rejection-sink records.
 *
 * <p>Replaces the VSAM/PS {@code DALYREJS} GDG output file defined by
 * {@code app/jcl/DALYREJS.jcl} (generation data group
 * {@code AWS.M2.CARDDEMO.DALYREJS}, {@code LIMIT(5)}). The original COBOL pattern in
 * {@code app/cbl/CBTRN02C.cbl} would {@code WRITE} a 430-byte combined record
 * &mdash; the 350-byte {@code DALYTRAN-RECORD} ({@code app/cpy/CVTRA06Y.cpy}) plus an
 * 80-byte reject-metadata tail &mdash; to the rejection dataset whenever a daily
 * transaction failed validation in paragraph {@code 1500-VALIDATE-TRAN}
 * [app/cbl/CBTRN02C.cbl:L370-L422]. Each failed transaction therefore lands in the
 * relational {@code rejected_transactions} table instead of the sequential GDG file.</p>
 *
 * <p><strong>Primary consumer &mdash; {@code TransactionPostingProcessor}
 * (AAP &sect;0.4.1.2):</strong> the POSTTRAN/{@code CBTRN02C} batch chunk pipeline routes
 * any transaction that trips a PR-03 validation code to the reject sink. The processor
 * produces a {@link RejectedTransaction}, and the {@code CompositeItemWriter}
 * (AAP &sect;0.6.6) persists it via this repository's inherited {@code save(...)} /
 * {@code saveAll(...)}. This is the sole writer; operational/reporting consumers use the
 * inherited read methods only.</p>
 *
 * <p><strong>PR-03 validation codes (preserved exactly)</strong>
 * [app/cbl/CBTRN02C.cbl paragraph {@code 1500-VALIDATE-TRAN}]:</p>
 * <ul>
 *   <li><strong>100</strong> &mdash; "INVALID CARD NUMBER FOUND" &mdash; cross-reference
 *       (XREF) lookup miss in {@code 1500-A-LOOKUP-XREF}
 *       [app/cbl/CBTRN02C.cbl:L380-L392].</li>
 *   <li><strong>101</strong> &mdash; account-not-found ("ACCOUNT RECORD NOT FOUND")
 *       &mdash; {@code ACCOUNT-FILE} lookup miss after XREF resolution in
 *       {@code 1500-B-LOOKUP-ACCT}.</li>
 *   <li><strong>102</strong> &mdash; "OVERLIMIT TRANSACTION" &mdash; credit-limit exceeded
 *       ({@code ACCT-CREDIT-LIMIT < ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT
 *       + DALYTRAN-AMT}) [app/cbl/CBTRN02C.cbl:L393-L422] (PR-04).</li>
 *   <li><strong>103</strong> &mdash; "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"
 *       &mdash; expiration-date check failure
 *       ({@code ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)})
 *       [app/cbl/CBTRN02C.cbl:L417-L420] (PR-05).</li>
 * </ul>
 *
 * <p>Enforcement of these codes lives in {@code TransactionPostingProcessor} (and the
 * shared {@code TransactionValidator}); this interface is a pure data-access component
 * carrying no business logic &mdash; it merely persists and retrieves the resulting
 * rows.</p>
 *
 * <p>The primary key is a synthetic {@code Long id} &mdash; the {@link RejectedTransaction}
 * entity declares {@code @GeneratedValue(strategy = GenerationType.IDENTITY)} over the
 * {@code rejected_id} column. The original COBOL {@code DALYREJS} dataset had no key; its
 * records were strictly sequential, and the same logical feed transaction could be
 * re-submitted and re-rejected on a later processing cycle. The database-generated
 * identity gives every rejection event its own row and supports straightforward retrieval
 * and pagination for operational dashboards. Hence the {@code <RejectedTransaction, Long>}
 * type parameters (a {@code String}/{@code Integer} ID would be incorrect). Rejection rows
 * are write-once audit records, so the entity carries no {@code @Version} optimistic-lock
 * field (per AAP &sect;0.3.3, optimistic locking is mandated only for {@code Account},
 * {@code Card}, {@code Customer}, and {@code Transaction}).</p>
 *
 * <p>No custom finder methods are declared. Per the Repository Pattern (AAP &sect;0.3.3 #1)
 * this interface extends {@code JpaRepository} (not {@code CrudRepository}) to inherit the
 * full CRUD and paging API. The inherited {@code save}/{@code saveAll} satisfy the batch
 * writer, while {@code findAll}/{@code findById(Long)}/{@code count}/{@code existsById}
 * satisfy operational queries and any future reporting endpoints &mdash; no name-derived
 * query is required.</p>
 *
 * <p>The explicit {@code @Repository} stereotype marks the interface as a persistence
 * component for component scanning and activates Spring's
 * {@code PersistenceExceptionTranslationPostProcessor}, translating provider-specific
 * exceptions into the {@code org.springframework.dao.DataAccessException} hierarchy.</p>
 *
 * @see com.carddemo.entity.RejectedTransaction
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface RejectedTransactionRepository extends JpaRepository<RejectedTransaction, Long> {
}
