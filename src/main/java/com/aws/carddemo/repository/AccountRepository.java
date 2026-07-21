package com.aws.carddemo.repository;

import com.aws.carddemo.domain.Account;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link Account} entity, replacing the legacy
 * VSAM {@code ACCTDAT} key-sequenced data set (KSDS) I/O that the mainframe application
 * performed through {@code EXEC CICS} file commands and COBOL {@code FILE SECTION} access.
 *
 * <p>Origin: legacy/cpy/CVACT01Y.cpy (ACCOUNT-RECORD, RECLN 300); VSAM ACCTDAT; CSD DEFINE FILE(ACCTDAT).</p>
 *
 * <p>All access is keyed by {@code acctId} (COBOL {@code ACCT-ID PIC 9(11)}), the single
 * primary key of the account master. The inherited {@link JpaRepository} operations reproduce
 * the COBOL access paths exactly: random read by account id ({@code findById(Long)}),
 * read-update-rewrite of balances and cycle totals ({@code save(Account)}), and the sequential
 * account-print scan ({@code findAll(org.springframework.data.domain.Sort)} ordered by
 * {@code acctId}). The only additional finder is {@link #findByIdForUpdate(Long)} &mdash; the
 * <em>same</em> primary-key access path as {@link JpaRepository#findById(Object) findById}, but
 * acquiring a pessimistic write lock (SQL {@code SELECT ... FOR UPDATE}). It is the migration of the
 * CICS {@code READ ... UPDATE} record lock and is used by <strong>every</strong> read-update-rewrite
 * path that the COBOL guarded that way: the daily transaction-posting batch step
 * ({@code CBTRN02C 2800-UPDATE-ACCOUNT-REC}), the online account-update write turn
 * ({@code COACTUPC 9600-WRITE-PROCESSING}, which locks the account then the customer), and the
 * bill-payment write turn ({@code COBIL00C UPDATE-ACCTDAT-FILE}). Acquiring the lock inside the
 * caller's transaction, held until commit, closes the lost-update window (CWE-362, review finding
 * #14) that a lock-free {@code findById} + application-level compare-before-rewrite would leave under
 * {@code READ COMMITTED}. No <em>new</em> access path (no query by a different key) is introduced
 * &mdash; a lock is not a new finder &mdash; so this is not feature expansion. The account-view read
 * path ({@code AccountViewService}) keeps the plain lock-free {@code findById}, matching the COBOL
 * {@code READ} (without {@code UPDATE}) it migrates.</p>
 *
 * <p>Consumed by the online account view and update services
 * ({@code AccountViewService}, {@code AccountUpdateService}) and by the account-print,
 * interest-calculation and transaction-posting batch jobs
 * ({@code AccountPrintJobConfig}, {@code InterestCalcJobConfig},
 * {@code PostTransactionJobConfig}).</p>
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Reads the account by its primary key while acquiring a row-level <strong>pessimistic write
     * lock</strong> (JPA {@link LockModeType#PESSIMISTIC_WRITE}; PostgreSQL {@code SELECT ... FOR
     * UPDATE}).
     *
     * <p><strong>Origin / parity (AAP &sect;0.7.1, &sect;0.6.3):</strong> the legacy daily-posting
     * program {@code legacy/cbl/CBTRN02C.cbl} runs as JCL job {@code POSTTRAN}, which allocates the
     * VSAM master data sets (ACCTDAT, TRANSACT, TCATBAL) with {@code DISP=OLD} &mdash; an exclusive,
     * data-set-level ENQ that makes two concurrent {@code POSTTRAN} executions impossible (the second
     * waits for the data set). Under the migrated stack multiple job launches can run concurrently
     * against the shared PostgreSQL database, so the read-modify-write of the account balance and
     * cycle totals ({@code 2800-UPDATE-ACCOUNT-REC}) would, under {@code READ COMMITTED}, lose
     * updates &mdash; a behavioural regression the COBOL never exhibited. Acquiring this pessimistic
     * write lock inside the chunk transaction, held until the chunk commits, serializes concurrent
     * posters on the same account and preserves the legacy invariant that every posted transaction
     * is reflected exactly once (a reconcilable ledger). The account row is the single serialization
     * point: because a {@code TRANSACTION_CATEGORY_BALANCE} key is (account, type, category), the
     * account lock also serializes the dependent {@code 2700-UPDATE-TCATBAL} update, so no second
     * lock &mdash; and therefore no lock-ordering / deadlock concern &mdash; arises.</p>
     *
     * <p><strong>Scope:</strong> used by every read-update-rewrite path that mutates an account
     * balance &mdash; the batch tier's {@code PostTransactionJobConfig} posting processor
     * ({@code CBTRN02C}) and {@code InterestCalcJobConfig} account read
     * ({@code CBACT04C 1100-GET-ACCT-DATA}, whose accumulated interest is rewritten by
     * {@code 1050-UPDATE-ACCOUNT}), and the online tier's {@code AccountUpdateService} write turn
     * ({@code COACTUPC 9600-WRITE-PROCESSING}; account locked first, then customer) and
     * {@code BillPayService} write turn ({@code COBIL00C UPDATE-ACCTDAT-FILE}). Only the read-only
     * account-view path ({@code AccountViewService}) keeps the plain {@code findById}, matching the
     * COBOL {@code READ} (without {@code UPDATE}) it migrates. Must be invoked within an active
     * transaction (the online {@code @Transactional} unit-of-work or the Spring Batch step/chunk
     * transaction); calling it outside a transaction has no lasting lock effect.</p>
     *
     * @param acctId the account primary key ({@code ACCT-ID PIC 9(11)})
     * @return the locked account if present, otherwise empty
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.acctId = :acctId")
    Optional<Account> findByIdForUpdate(@Param("acctId") Long acctId);
}
