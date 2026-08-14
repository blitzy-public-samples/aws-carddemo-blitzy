package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.AccountBalanceProjectionEntity;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.Repository;

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
 * <h2>Who moves which column</h2>
 *
 * <p>This service owns all three value columns, because all three are produced by
 * {@code 2800-UPDATE-ACCOUNT-REC} at {@code app/cbl/CBTRN02C.cbl:L545-L560}. Two writes reach them
 * from outside the posting path, and each is narrow on purpose.
 * {@link #insertMissingProjection} opens a row for an account this projection does not hold, and
 * changes no row it does hold. {@link #closeBillingCycle} zeroes the two accumulators of a row it
 * does hold, reproducing {@code app/cbl/CBACT04C.cbl:L353-L354}, and leaves the balance alone.
 * Nothing replaces a value column of an existing row, which is what keeps the balance the detail
 * rows of {@code transaction} imply the balance this table reports.
 *
 * <h2>Where the rows come from</h2>
 *
 * <p>{@code src/main/resources/db/migration/V2__seed.sql} writes one row for each of the fifty
 * accounts in {@code app/data/ASCII/acctdata.txt}, and a {@code DO} block in that migration fails
 * the deployment when the table holds any other number. Every business column is
 * {@code NOT NULL}, so a row has to exist before the posting path can add an amount to it.
 *
 * <h2>No create-on-miss</h2>
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
 * <p>Design decisions, source mapping and flagged findings: {@code card-platform/docs/decision-log.md},
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

    /**
     * Inserts the first row for an account this projection does not hold yet, and leaves a row it
     * already holds exactly as it stands.
     *
     * <p>This is the bootstrap, and it is the whole of what an account change writes to a row this
     * service does not yet have. {@code src/main/resources/db/migration/V2__seed.sql} loads the
     * fifty accounts of {@code app/data/ASCII/acctdata.txt} and nothing added a fifty-first, so an
     * account opened after deployment had no row at all: every column is {@code NOT NULL}, so
     * {@code domain/AccountBalanceUpdater} raised for every transaction on that account and no later
     * delivery could have repaired it. The three values come from the account service because this
     * service has posted nothing for that account yet, so it has no derived value of its own to
     * keep.
     *
     * <p>{@code ON CONFLICT (account_id) DO NOTHING} is the whole guarantee, and it is why this
     * statement never discards a posted balance. The three value columns of a row that exists are
     * produced by {@code 2800-UPDATE-ACCOUNT-REC} at {@code app/cbl/CBTRN02C.cbl:L545-L560}: the
     * amount is added to the balance at {@code :L547} and to one of the two accumulators at
     * {@code :L548-L551}. An account change carries the account service's own copy of those three
     * fields. That service applies the same arithmetic to its own record — its
     * {@code messaging/TransactionPostedConsumer} consumes {@code TransactionPosted} and calls
     * {@code domain/PostedTransactionService.applyPostedAmount} — so what arrives is not a copy
     * without the postings. It is a copy that trails them: it holds every posting whose event it has
     * already consumed and none of the rest, and nothing bounds that gap, because a relay backlog, a
     * paused listener or a retry each widen it. Writing a trailing copy over this row moved the
     * balance backwards, the next posting then added to a figure that had lost movements, and nothing
     * reported either step: the detail rows still summed to the movement the balance no longer
     * showed.
     *
     * <p>One statement rather than read-then-write, so two deliveries racing on one new account
     * cannot both insert. Replaying the topic converges on the same rows.
     *
     * @param accountId        the eleven-digit account identifier, the primary key
     * @param currentBalance   ACCT-CURR-BAL, scale 2, the opening value for a new row
     * @param cycleCredit      ACCT-CURR-CYC-CREDIT, scale 2, the opening value for a new row
     * @param cycleDebit       ACCT-CURR-CYC-DEBIT, scale 2, signed, the opening value for a new row
     * @param sourceEventId    the event that carried the change
     * @param sourceOccurredAt when that event occurred, from its envelope
     * @return 1 when this delivery inserted the row, and 0 when the projection already held one
     */
    @Modifying
    @Query(value = """
            INSERT INTO account_balance_projection (account_id, current_balance,
                                                    cycle_credit, cycle_debit,
                                                    source_event_id, source_occurred_at)
            VALUES (:accountId, :currentBalance, :cycleCredit, :cycleDebit,
                    :sourceEventId, :sourceOccurredAt)
            ON CONFLICT (account_id) DO NOTHING
            """, nativeQuery = true)
    int insertMissingProjection(@Param("accountId") String accountId,
            @Param("currentBalance") BigDecimal currentBalance,
            @Param("cycleCredit") BigDecimal cycleCredit,
            @Param("cycleDebit") BigDecimal cycleDebit,
            @Param("sourceEventId") UUID sourceEventId,
            @Param("sourceOccurredAt") Instant sourceOccurredAt);

    /**
     * Moves zero into both billing-cycle accumulators of one account and leaves the balance alone.
     *
     * <p>This reproduces {@code app/cbl/CBACT04C.cbl:L353-L354}, which moves zero into
     * {@code ACCT-CURR-CYC-CREDIT} and {@code ACCT-CURR-CYC-DEBIT} and touches
     * {@code ACCT-CURR-BAL} nowhere. The two literal zeroes are what the source moves, so the value
     * the event carried is not read here: a cycle close is a command, not a reading, and this
     * statement carries it out.
     *
     * <p>The balance stays because it is not part of a cycle close.
     * {@code app/cbl/CBTRN02C.cbl:L547} is the only statement that moves it, and this service owns
     * that statement.
     *
     * <p>Nothing else zeroes these two columns, and the posting path only ever adds to them at
     * {@code app/cbl/CBTRN02C.cbl:L548-L551}. Without this write they grow without bound while the
     * account service zeroes its own copies each cycle, which is the drift the credit-limit rule at
     * {@code app/cbl/CBTRN02C.cbl:L403-L413} reads as an ever-shrinking available credit.
     *
     * <p>The write is ordered, so a redelivery arriving behind a newer change discards itself
     * instead of reopening a cycle the replica has already closed past.
     * {@code source_occurred_at IS NULL} means the row came from {@code V2__seed.sql}, and any
     * change supersedes it.
     *
     * @param accountId        the eleven-digit account identifier, the primary key
     * @param sourceEventId    the event that carried the cycle close
     * @param sourceOccurredAt when that event occurred, from its envelope
     * @return 1 when both accumulators were zeroed, and 0 when no row carries the account or a
     *         change not before this one was already recorded
     */
    @Modifying
    @Query(value = """
            UPDATE account_balance_projection SET
                cycle_credit       = 0.00,
                cycle_debit        = 0.00,
                source_event_id    = :sourceEventId,
                source_occurred_at = :sourceOccurredAt
            WHERE account_id = :accountId
              AND (source_occurred_at IS NULL OR source_occurred_at < :sourceOccurredAt)
            """, nativeQuery = true)
    int closeBillingCycle(@Param("accountId") String accountId,
            @Param("sourceEventId") UUID sourceEventId,
            @Param("sourceOccurredAt") Instant sourceOccurredAt);
}
