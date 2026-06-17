package com.carddemo.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.carddemo.entity.Account;

import jakarta.persistence.LockModeType;

/**
 * Spring Data JPA repository for the {@link Account} aggregate &mdash; the central financial
 * <em>account master</em> of the CardDemo domain.
 *
 * <h2>Lineage</h2>
 * This repository is the Spring Boot / Hibernate re-expression of primary-key access to the legacy
 * mainframe VSAM KSDS dataset {@code AWS.M2.CARDDEMO.ACCTDATA} (record layout
 * {@code app/cpy/CVACT01Y.cpy}, {@code 01 ACCOUNT-RECORD}, key {@code ACCT-ID PIC 9(11)}). The VSAM
 * keyed {@code READ}/{@code REWRITE} verbs of the online and batch programs become Spring Data
 * derived/CRUD operations against the {@code accounts} table:
 * <ul>
 *   <li>{@code READ ACCTFILE KEY(acct-id)} &rarr; {@link #findById(Object) findById(Long)}.</li>
 *   <li>{@code REWRITE ACCOUNT-RECORD} &rarr; {@link #save(Object) save(Account)} (guarded by the
 *       entity {@code @Version}; see below).</li>
 *   <li>Sequential dataset scan &rarr; {@link #findAll()} / {@code findAll(Pageable)} as inherited.</li>
 * </ul>
 *
 * <h2>Concurrency model (AAP &sect;0.6.6)</h2>
 *
 * <h3>Primary mechanism &mdash; optimistic locking via the entity {@code @Version}</h3>
 * The {@link Account} entity already carries a JPA {@code @Version} column
 * ({@code version BIGINT NOT NULL}). Consequently the inherited {@link #save(Object)} performs
 * optimistic-lock conflict detection automatically: a write against a stale snapshot raises
 * {@link org.springframework.orm.ObjectOptimisticLockingFailureException} (wrapping
 * {@link jakarta.persistence.OptimisticLockException}), which the {@code service} layer together
 * with {@code exception/GlobalExceptionHandler} translate into an <strong>HTTP&nbsp;409 Conflict</strong>.
 * This reproduces the lost-update guard of the online account-update program {@code COACTUPC}, which
 * issued a CICS {@code READ ... UPDATE} followed by a {@code REWRITE} and inspected the VSAM
 * file-status / RESP code to detect a record that had changed underneath it. <strong>No dedicated
 * repository method is required for this path</strong> &mdash; {@link #findById(Object)} followed by
 * {@link #save(Object)} is sufficient because the {@code @Version} field does the work. The optimistic
 * path is the default for all <em>online</em> updates (for example {@code AccountService.update}).
 *
 * <h3>Secondary mechanism &mdash; pessimistic write lock for batch posting</h3>
 * The daily transaction-posting program {@code CBTRN02C} performs, for every account it touches within
 * a chunk, a strictly serialized {@code READ}&nbsp;&rarr;&nbsp;balance-update&nbsp;&rarr;&nbsp;{@code REWRITE}
 * (paragraph {@code 2800-UPDATE-ACCOUNT-REC}; a failed {@code REWRITE} yields reject code&nbsp;109). For
 * that posting path the {@link #findByIdForUpdate(Long)} method below acquires a database-level
 * {@link LockModeType#PESSIMISTIC_WRITE} lock so concurrent account mutations are serialized more
 * strictly than optimistic retries would allow.
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><strong>No {@code @Repository} annotation.</strong> Spring Data auto-detects repository
 *       interfaces and creates the proxy; an explicit stereotype is unnecessary and intentionally
 *       omitted.</li>
 *   <li><strong>No business logic.</strong> Balance arithmetic, overlimit/expiration validation and
 *       reject-code control flow live in the {@code service} and {@code batch} layers (for example
 *       {@code TransactionService} and {@code TransactionPostingJobConfig}); this interface only
 *       abstracts persistence access.</li>
 *   <li><strong>Identifier type.</strong> The repository is parameterized as
 *       {@code JpaRepository<Account, Long>} because {@link Account} declares {@code @Id private Long
 *       acctId} (column {@code acct_id BIGINT}); the 11-digit {@code ACCT-ID} fits a 64-bit integer.</li>
 * </ul>
 *
 * @see Account
 * @see JpaRepository
 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Loads an {@link Account} by its primary key while holding a database
     * {@link LockModeType#PESSIMISTIC_WRITE} ({@code SELECT ... FOR UPDATE}) lock for the remainder of
     * the surrounding transaction.
     *
     * <p>This is the <strong>batch-posting</strong> counterpart to the optimistic default described in
     * the interface Javadoc. It reproduces the serialized
     * {@code READ}&nbsp;&rarr;&nbsp;update&nbsp;&rarr;&nbsp;{@code REWRITE} per-account semantics of
     * {@code CBTRN02C} (paragraph {@code 2800-UPDATE-ACCOUNT-REC}): once a posting transaction has
     * read an account with this method, no other transaction can read-for-update or write that row
     * until the current transaction commits or rolls back, preventing interleaved balance updates
     * across concurrent posting workers.</p>
     *
     * <h3>Usage contract</h3>
     * <ul>
     *   <li>The caller <strong>MUST</strong> invoke this method inside an active transaction
     *       (a {@code @Transactional} service or Spring Batch step scope). Outside a transaction the
     *       lock is released immediately and provides no serialization guarantee.</li>
     *   <li>Intended for the posting / batch write path
     *       ({@code TransactionPostingJobConfig} / {@code TransactionService}). It is <strong>not</strong>
     *       for read-only views &mdash; those use {@link #findById(Object)} so they neither block nor
     *       are blocked.</li>
     *   <li>An empty {@link Optional} means the account does not exist; the posting flow maps that
     *       condition to reject code&nbsp;101 ({@code ACCOUNT RECORD NOT FOUND}). This method itself
     *       performs no validation.</li>
     * </ul>
     *
     * <p>The lock mode is honored by PostgreSQL (production) and by H2 (dev/test), so the behavior is
     * consistent across the active Spring profiles.</p>
     *
     * @param acctId the account identifier ({@code ACCT-ID}); must not be {@code null}
     * @return an {@link Optional} containing the locked {@link Account} if present, otherwise an empty
     *         {@link Optional}
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.acctId = :acctId")
    Optional<Account> findByIdForUpdate(@Param("acctId") Long acctId);
}
