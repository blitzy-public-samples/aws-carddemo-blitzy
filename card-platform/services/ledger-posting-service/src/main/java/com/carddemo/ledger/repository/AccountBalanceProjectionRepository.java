package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.AccountBalanceProjectionEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Finds and stores rows of {@link AccountBalanceProjectionEntity}, the balance and the two
 * billing-cycle accumulators the ledger keeps for one account.
 *
 * <p>The identifier is the eleven-digit account number, from {@code ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT01Y.cpy:L5}, sized by {@code KEYS(11 0)} at {@code app/jcl/ACCTFILE.jcl:L40}.
 *
 * <p>Parameter area {@code LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112} held every read
 * and write behind one operation code. Its keyed read and its rewrite become the {@code findById}
 * and {@code save} this interface declares.
 *
 * <p>{@code domain/AccountBalanceUpdater} reads one row by identifier, then saves it.
 * {@code api/BalanceQueryController} only reads.
 *
 * <h2>Where the rows come from</h2>
 *
 * <p>{@code src/main/resources/db/migration/V2__seed.sql} writes one row for each of the fifty
 * accounts in {@code app/data/ASCII/acctdata.txt}, and a {@code DO} block in that migration fails
 * the deployment when the table holds any other number. Every business column is
 * {@code NOT NULL}, so a row has to exist before the posting path can add an amount to it.
 *
 * <h2>Why no create-on-miss</h2>
 *
 * <p>{@code 2800-UPDATE-ACCOUNT-REC} at {@code app/cbl/CBTRN02C.cbl:L545-L560} adds the amount and
 * issues {@code REWRITE}. On {@code INVALID KEY} it moves 109 and
 * {@code 'ACCOUNT RECORD NOT FOUND'} into the failure fields at {@code :L555-L558}, and it creates
 * nothing. {@code 2700-UPDATE-TCATBAL} at {@code :L473-L499} shows what a create-on-miss looks like
 * in this program: it raises a create flag on {@code INVALID KEY} and branches to
 * {@code 2700-A-CREATE-TCATBAL-REC}. The account record has no such branch, so this interface
 * offers no create path either.
 *
 * <p>A miss reaches the posting path only when something upstream is wrong, because reject reason
 * 101 at {@code app/cbl/CBTRN02C.cbl:L397-L399} already declines a transaction whose account is
 * absent. The consumer treats a miss as the failure reason 109 names, leaves the message
 * unacknowledged, and lets the retries and the dead-letter route carry it. Inventing an account
 * with a zero balance would post a transaction the source would have rejected.
 *
 * <h2>Concurrent updates</h2>
 *
 * <p>The account identifier is the message key, so normal delivery orders one account's events on
 * one partition. The posting path also takes a pessimistic write lock, because a malformed key or a
 * consumer-group transition must not turn two read-modify-write operations into one lost update.
 * The primary key admits one row per account, and the idempotency marker stops a redelivery.
 *
 * <p>Rationale, source mapping and flagged findings: {@code card-platform/docs/decision-log.md},
 * {@code card-platform/docs/traceability-matrix.md}, and
 * {@code card-platform/docs/business-rule-flags.md}.
 */
public interface AccountBalanceProjectionRepository
        extends Repository<AccountBalanceProjectionEntity, String> {

    /**
     * Finds the balance row for one account, taking no lock.
     *
     * <p>{@code api/BalanceQueryController} reads through this method. A posting path reads through
     * {@link #findForUpdateById(String)} instead.
     *
     * @param accountId the eleven-digit account number
     * @return the row, or an empty {@code Optional} when the table holds no such account
     */
    Optional<AccountBalanceProjectionEntity> findById(String accountId);

    /**
     * Reads and locks the balance row that the posting path is about to replace.
     *
     * @param accountId the eleven-digit account number
     * @return the locked row, or an empty value when the projection is absent
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT projection FROM AccountBalanceProjectionEntity projection
            WHERE projection.accountId = :accountId
            """)
    Optional<AccountBalanceProjectionEntity> findForUpdateById(
            @Param("accountId") String accountId);

    /**
     * Saves a balance row the caller has already read and changed.
     *
     * <p>This is the {@code REWRITE} at {@code app/cbl/CBTRN02C.cbl:L554}. The caller saves in the
     * same local transaction as the posted-transaction row and the idempotency marker.
     *
     * @param projection the row to save
     * @return the saved row
     */
    AccountBalanceProjectionEntity save(AccountBalanceProjectionEntity projection);

    /**
     * Counts the projection rows.
     *
     * @return how many accounts the table holds
     */
    long count();
}
