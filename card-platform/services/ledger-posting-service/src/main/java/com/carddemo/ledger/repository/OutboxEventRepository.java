package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.OutboxEventEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Data access for the pending domain events of the ledger posting service, held in the table
 * {@code outbox_event}.
 *
 * <p>{@code outbox/OutboxWriter} inserts one row in the same local transaction as the domain write
 * that produced the event. {@code outbox/OutboxRelay} reads the unpublished rows and marks each one
 * with {@link OutboxEventEntity#markPublished(java.time.Instant)}.</p>
 *
 * <p>The one-interface-per-aggregate shape comes from the generic parameter area
 * {@code 01 LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112}, whose operation code
 * {@code LK-M03B-OPER} dispatches every read and write behind one called subroutine. The queued
 * handoff comes from {@code EXEC CICS WRITEQ TD QUEUE ('JOBS')} at
 * {@code app/cbl/CORPT00C.cbl:L517-L518}. That Customer Information Control System (CICS) write is
 * the only transient-data queue write in the 28 programs of {@code app/cbl/}, and the one
 * asynchronous handoff the CardDemo source performs.</p>
 */
public interface OutboxEventRepository extends ListCrudRepository<OutboxEventEntity, UUID> {

    /**
     * Lock timeout that asks the database to pass over a locked row instead of waiting for it.
     *
     * <p>Hibernate maps this value to {@code SKIP LOCKED}. A positive value would be a wait in
     * milliseconds, and zero would fail immediately on a locked row.
     */
    String SKIP_LOCKED_TIMEOUT = "-2";

    /**
     * Claims the oldest pending rows for this caller alone, and returns them.
     *
     * <p>Two properties matter here, and the plain derived finder this method replaces had neither.
     *
     * <p>The order is total. Rows come back by {@code createdAt} ascending and then by
     * {@code eventId} ascending. The writer sets {@code createdAt}, so two rows can carry one value,
     * and the primary key completes the order. Two relay instances therefore agree on which row
     * comes next.
     *
     * <p>The claim is exclusive. {@link LockModeType#PESSIMISTIC_WRITE} with a lock timeout of
     * {@value #SKIP_LOCKED_TIMEOUT} renders on PostgreSQL as {@code FOR NO KEY UPDATE ... SKIP
     * LOCKED}: the query takes a row lock on each row it returns and passes over any row another
     * transaction already holds. A
     * second relay running at the same moment receives the next unlocked rows instead of the same
     * ones, so no row is published twice. Producer idempotence does not give this, because it
     * deduplicates one producer's retries of one send rather than two producers sending one payload.
     *
     * <p>The query is written in the Jakarta Persistence Query Language rather than in Structured
     * Query Language (SQL), because {@code hibernate.default_schema} in
     * {@code src/main/resources/application.yml} qualifies a mapped query and leaves a native one
     * unqualified. A native statement would look for the table on the connection search path and
     * not find it.
     *
     * <p>The caller must run inside a transaction that stays open until it has marked each returned
     * row published, because a row lock lasts as long as the transaction that took it.
     * {@code carddemo.outbox.relay.batch-size} supplies the row cap.
     *
     * <p>The partial index {@code ix_outbox_event_pending} in
     * {@code src/main/resources/db/migration/V1__schema.sql} covers exactly the rows this query
     * reads, in exactly the order it reads them.
     *
     * @param limit greatest number of rows to claim
     * @return the claimed rows, oldest first, and empty when no row awaits publication
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED_TIMEOUT))
    @Query("""
            SELECT row FROM OutboxEventEntity row
            WHERE row.published = FALSE
            ORDER BY row.createdAt ASC, row.eventId ASC
            """)
    List<OutboxEventEntity> claimPendingBatch(Limit limit);

    /**
     * Deletes published rows whose publication is older than the given instant, and returns how
     * many it removed.
     *
     * <p>A published row has done its work and stays only for diagnosis. Nothing reads it again, so
     * past the retention horizon it is dead weight on a table every event passes through.
     * {@code carddemo.outbox.published-retention-hours} in
     * {@code src/main/resources/application.yml} supplies the horizon, and the partial index
     * {@code ix_outbox_event_published_at} serves this delete.
     *
     * <p>The condition names {@code published} as well as {@code published_at} so the delete matches
     * the partial index exactly and can never touch a row the relay has not published.
     *
     * @param horizon the instant before which a published row is removed
     * @return the number of rows removed
     */
    @Modifying
    @Query("""
            DELETE FROM OutboxEventEntity row
            WHERE row.published = TRUE AND row.publishedAt < :horizon
            """)
    int deletePublishedBefore(@Param("horizon") Instant horizon);

    /**
     * Returns unpublished rows in write order, taking no lock.
     *
     * <p>This is the reading counterpart of {@link #claimPendingBatch(Limit)}: a diagnostic view of
     * the backlog, and the finder a test uses to assert what a write left behind. A relay never uses
     * it, because two relay instances reading the same unlocked rows would publish every one twice.
     *
     * @param limit greatest number of rows to return
     * @return unpublished rows, oldest first, and empty when none awaits publication
     */
    List<OutboxEventEntity> findByPublishedFalseOrderByCreatedAtAsc(Limit limit);

    /**
     * Claims up to {@code limit} rows that are due for a publish attempt, excluding every row a
     * competing relay instance already holds.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes this safe to run from more than one instance.
     * The lock is taken as the rows are read, and a row another transaction has locked is skipped
     * rather than waited for, so two relays working the same table return disjoint batches and
     * neither blocks the other. A plain finder cannot do this: two instances read the same
     * unpublished rows and publish every one of them twice.
     *
     * <p>The filter is {@code relay_state = 'PENDING'} and {@code next_attempt_at <= now}, so a row
     * awaiting its backoff is left alone and a row in either terminal state is never returned.
     * Index {@code ix_outbox_event_claimable} covers both columns. Ordering by
     * {@code next_attempt_at} takes the longest-waiting row first.
     *
     * <p>The caller runs this inside a transaction and calls
     * {@link OutboxEventEntity#claim(String, java.time.Instant)} on each row it takes, which
     * records which instance won. The lock lasts only as long as that transaction, and
     * {@code claimed_by} is what survives it; a row whose claim outlives its usefulness is
     * recovered by
     * {@link #findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(OutboxEventEntity.RelayState,
     * java.time.Instant, org.springframework.data.domain.Limit)}.
     *
     * @param now   the current time, against which {@code next_attempt_at} is compared
     * @param limit how many rows to claim, at least one
     * @return the claimed rows, longest-waiting first, at most {@code limit} of them, and empty
     *         when no row is due
     */
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE relay_state = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEventEntity> claimDueRows(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Returns rows left in {@link OutboxEventEntity.RelayState#CLAIMED} since before
     * {@code claimedBefore}, so a relay instance that died holding a claim does not strand them.
     *
     * <p>Without this, one crash costs one event permanently: the row stays {@code CLAIMED}, the
     * claim query filters on {@code PENDING}, and nothing ever looks at it again.
     *
     * @param relayState    always {@link OutboxEventEntity.RelayState#CLAIMED}; the parameter
     *                      keeps the derived query readable rather than hiding the state in a name
     * @param claimedBefore the cutoff; a row claimed at or after it is still considered live
     * @param limit         how many rows to return
     * @return stranded rows, longest-claimed first, and empty when none is stranded
     */
    List<OutboxEventEntity> findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
            OutboxEventEntity.RelayState relayState, Instant claimedBefore, Limit limit);
}
