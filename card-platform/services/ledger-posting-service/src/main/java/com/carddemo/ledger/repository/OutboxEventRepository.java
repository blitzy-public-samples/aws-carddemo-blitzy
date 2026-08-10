package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.OutboxEventEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
     * Deletes at most {@code limit} published rows older than the horizon, and returns how many it
     * removed.
     *
     * <p>The bound keeps one retention pass from producing a single very large statement on a schema
     * that has been idle for a long time. An unbounded delete holds every row it removes under one
     * lock for the whole statement, which blocks the relay sweeping this same table. A caller repeats
     * this call until it removes fewer rows than the limit, which drains the same backlog in short
     * transactions that each release their locks.
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
     * <p>A correlated absence check admits only the oldest non-terminal row of each account, so one
     * batch never holds two rows of one account. Recording each outcome separately is what makes
     * that necessary: if an older row of an account failed while a newer one succeeded, the retry of
     * the older row would reach the topic behind the newer one, and the account identifier is the
     * message key, so every consumer of that account would see the two events out of order. It is
     * also what lets one unpublishable row pause its own account and leave every other account
     * eligible, and what makes the pass safe to issue several sends at once. Partial index
     * {@code ix_outbox_event_aggregate_head} covers the check.
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
     * @return the claimed account heads, longest-waiting first, at most {@code limit} of them, and
     *         empty when no row is due
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED_TIMEOUT))
    @Query("""
            SELECT row FROM OutboxEventEntity row
            WHERE row.relayState =
                    com.carddemo.ledger.entity.OutboxEventEntity.RelayState.PENDING
              AND row.nextAttemptAt <= :now
              AND NOT EXISTS (
                    SELECT preceding.eventId FROM OutboxEventEntity preceding
                    WHERE preceding.aggregateId = row.aggregateId
                      AND preceding.relayState IN (
                          com.carddemo.ledger.entity.OutboxEventEntity.RelayState.PENDING,
                          com.carddemo.ledger.entity.OutboxEventEntity.RelayState.CLAIMED)
                      AND (
                          preceding.createdAt < row.createdAt
                          OR (preceding.createdAt = row.createdAt
                              AND preceding.eventId < row.eventId)
                      )
              )
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

    /**
     * Counts the rows awaiting an attempt whose attempt is already due.
     *
     * <p>Readiness reported only whether a row had been abandoned, which happens after every attempt
     * of that row is spent. A broker unreachable for minutes therefore left a growing backlog and a
     * readiness document with nothing in it, because no row had run out of attempts yet. This is the
     * number that moves first.
     *
     * <p>The count is restricted to rows that are due, so a row deliberately waiting out its backoff
     * is not reported as a backlog. Both columns it reads are the two of the
     * {@code relay_state, next_attempt_at} index, so the count is answered from that index.
     *
     * @param now the current time, against which {@code next_attempt_at} is compared
     * @return how many rows are due for an attempt, and zero when none is
     */
    @Query("""
            SELECT COUNT(row) FROM OutboxEventEntity row
            WHERE row.relayState =
                    com.carddemo.ledger.entity.OutboxEventEntity.RelayState.PENDING
              AND row.nextAttemptAt <= :now
            """)
    long countDueBefore(@Param("now") Instant now);

    /**
     * Returns when the longest-waiting due row became due, or empty when no row is due.
     *
     * <p>A count alone cannot separate a service that is busy from one that is stuck. Three rows due
     * for forty minutes is a stopped relay, and three hundred due for two seconds is a burst being
     * worked through. The age derived from this value is what tells them apart.
     *
     * @param now the current time, against which {@code next_attempt_at} is compared
     * @return the earliest {@code next_attempt_at} among due rows, or empty when none is due
     */
    @Query("""
            SELECT MIN(row.nextAttemptAt) FROM OutboxEventEntity row
            WHERE row.relayState =
                    com.carddemo.ledger.entity.OutboxEventEntity.RelayState.PENDING
              AND row.nextAttemptAt <= :now
            """)
    Optional<Instant> findEarliestDueBefore(@Param("now") Instant now);

}
