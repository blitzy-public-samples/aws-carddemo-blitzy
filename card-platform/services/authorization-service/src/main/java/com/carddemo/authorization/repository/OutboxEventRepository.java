package com.carddemo.authorization.repository;

import com.carddemo.authorization.entity.OutboxEventEntity;
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
 * Reads the authorization service's private {@code outbox_event} table.
 *
 * <p>ADDITIVE. The source ancestor is the transient data queue write in paragraph
 * {@code WIRTE-JOBSUB-TDQ} at {@code app/cbl/CORPT00C.cbl:L515-L523}. The generic repository shape
 * follows the parameter area at {@code app/cbl/CBSTM03B.CBL:L100-L112}.
 *
 * <p>{@code outbox/OutboxWriter.java} inserts through the inherited {@code save} method.
 * {@code outbox/OutboxRelay.java} claims due rows, records retry state, and marks successful rows
 * with {@link OutboxEventEntity#markPublished(java.time.Instant)}.
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
     * <p>The {@code NOT EXISTS} clause narrows the result to the due <em>head</em> row of each
     * aggregate, and it is what makes per-account ordering survive a partial failure. The relay
     * publishes each claimed row and records each outcome separately, so without this clause two
     * rows of one account could be in one batch, the older one could fail while the newer one
     * succeeded, and the retry of the older one would then reach the topic behind the newer one.
     * Kafka orders within a partition and the account identifier is the key, so that reordering
     * would be visible to every consumer of that account.
     *
     * <p>Restricting the batch to account heads is also why one failing row cannot block the table:
     * a refusal pauses that account for the pass while every other account stays eligible. Order
     * inside the clause is {@code created_at} then {@code event_id}, so two rows written in the same
     * instant still have one deterministic head.
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
                    com.carddemo.authorization.entity.OutboxEventEntity.RelayState.PENDING
              AND row.nextAttemptAt <= :now
              AND NOT EXISTS (
                    SELECT preceding.eventId FROM OutboxEventEntity preceding
                    WHERE preceding.aggregateId = row.aggregateId
                      AND preceding.relayState IN (
                          com.carddemo.authorization.entity.OutboxEventEntity.RelayState.PENDING,
                          com.carddemo.authorization.entity.OutboxEventEntity.RelayState.CLAIMED)
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
     * Returns the abandoned rows that still owe the dead-letter topic a diagnostic, oldest attempt
     * first.
     *
     * <p>This is the set {@code outbox/OutboxRelay} reads at the head of every pass. An owed diagnostic
     * is the last remaining record of an event this service gave up on, so it is offered again for as
     * long as it takes, and the ordering names the row that has gone unnamed longest first.
     *
     * <p>The set is empty while the relay is healthy, and the partial index
     * {@code ix_outbox_event_dead_letter_required} of
     * {@code src/main/resources/db/migration/V8__outbox_dead_letter_state.sql} covers exactly it, so the
     * read costs nothing on a service with nothing to report.
     *
     * <p>No lock is taken here. Each row this returns is written in a short transaction of its own once
     * the broker has acknowledged its diagnostic, and a second relay instance offering the same
     * diagnostic publishes one duplicate on a dead-letter topic rather than losing one.
     *
     * @param deadLetterState always {@link OutboxEventEntity.DeadLetterState#REQUIRED}; naming it as a
     *                        parameter keeps the derived query readable rather than hiding the state
     *                        inside a method name
     * @param limit           how many rows to return
     * @return rows owing a diagnostic, oldest attempt first, and empty when none is owed
     */
    List<OutboxEventEntity> findByDeadLetterStateOrderByLastAttemptAtAsc(
            OutboxEventEntity.DeadLetterState deadLetterState, Limit limit);
}
