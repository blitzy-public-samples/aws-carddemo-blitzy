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
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
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
     * Deletes at most {@code limit} published rows older than the horizon, and returns how many it
     * removed.
     *
     * <p>The bound keeps one retention pass from producing a single very large statement on a schema
     * that has been idle for a long time. A caller repeats the call until it returns zero.
     *
     * @param horizon the instant before which a published row is removed
     * @param limit   the largest number of rows one statement removes
     * @return the number of rows removed
     */
    @Modifying
    @Query(value = """
            DELETE FROM outbox_event
            WHERE event_id IN (SELECT event_id
                               FROM outbox_event
                               WHERE published = TRUE AND published_at < :horizon
                               ORDER BY published_at
                               LIMIT :limit)
            """, nativeQuery = true)
    int deletePublishedBefore(@Param("horizon") Instant horizon, @Param("limit") int limit);

    /** Reports whether any row reached the terminal abandoned state. */
    boolean existsByRelayState(OutboxEventEntity.RelayState relayState);

    /**
     * Returns unpublished rows in write order, taking no lock.
     *
     * <p>This is the reading counterpart of {@link #claimDueRows(Instant, Limit)}: a diagnostic view of
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
     * rather than waited for. Two relays working the same table therefore return disjoint batches,
     * and neither blocks the other. A plain finder cannot do this: two instances read the same
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
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED_TIMEOUT))
    @Query("""
            SELECT row FROM OutboxEventEntity row
            WHERE row.relayState =
                    com.carddemo.ledger.entity.OutboxEventEntity.RelayState.PENDING
              AND row.nextAttemptAt <= :now
            ORDER BY row.nextAttemptAt ASC, row.eventId ASC
            """)
    List<OutboxEventEntity> claimDueRows(@Param("now") Instant now, Limit limit);

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
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED_TIMEOUT))
    List<OutboxEventEntity> findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
            OutboxEventEntity.RelayState relayState, Instant claimedBefore, Limit limit);
}
