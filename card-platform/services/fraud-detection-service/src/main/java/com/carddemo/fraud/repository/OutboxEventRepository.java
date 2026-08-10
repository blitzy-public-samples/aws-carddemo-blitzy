package com.carddemo.fraud.repository;

import com.carddemo.fraud.entity.OutboxEventEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Reads rows of the table {@code outbox_event}, one row per {@code FraudFlagged} or
 * {@code FraudCleared} event awaiting publication to the topic {@code fraud.assessed}. A consumer
 * writes the row in the same transaction as its assessment, and the relay publishes it later.
 *
 * <p>No COBOL ancestor. The parameter area at
 * {@code app/cbl/CBSTM03B.CBL:L99-L114} supplies shape only, no logic: its sequential read becomes
 * the finder below.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

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
     * Returns unpublished rows in creation order, at most as many as {@code pageable} allows.
     *
     * <p>This finder is diagnostic only. The relay uses {@link #claimDueRows(Instant, Limit)},
     * because this unlocked read would let two service instances publish the same rows.
     *
     * @param pageable the row bound the caller supplies
     * @return the unpublished rows, empty when the table holds none
     */
    List<OutboxEventEntity> findByPublishedFalseOrderByCreatedAtAsc(Pageable pageable);

    /**
     * Claims the due rows for this caller alone, and returns them.
     *
     * <p>{@code FOR NO KEY UPDATE ... SKIP LOCKED} is what makes this safe to run from more than one
     * instance. {@link LockModeType#PESSIMISTIC_WRITE} with a lock timeout of
     * {@value #SKIP_LOCKED_TIMEOUT} renders as exactly that on PostgreSQL: the lock is taken as the
     * rows are read, and a row another transaction already holds is skipped rather than waited for, so
     * two relays working the same table return disjoint batches and neither blocks the other. The
     * plain finder above cannot do this, which is why one rolling deployment or one manual scale-out
     * had both instances publishing every assessment twice.
     *
     * <p>The query is written in the Jakarta Persistence Query Language rather than as native SQL so
     * that the schema comes from the entity mapping. A native {@code FROM outbox_event} carries no
     * schema, and {@code spring.jpa.properties.hibernate.default_schema} does not apply to a native
     * string, so such a query fails wherever the search path does not already name the right schema.
     *
     * <p>The filter is {@code relayState = PENDING} and {@code nextAttemptAt <= now}, so a row
     * awaiting its backoff is left alone and a row in either terminal state is never returned. Index
     * {@code ix_outbox_event_claimable} covers both columns. The order takes the longest-waiting row
     * first, and the primary key completes it so two instances agree on which row comes next.
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
     * @param now   the current time, against which {@code nextAttemptAt} is compared
     * @param limit greatest number of rows to claim
     * @return the claimed account heads, longest-waiting first, and empty when no row is due
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED_TIMEOUT))
    @Query("""
            SELECT row FROM OutboxEventEntity row
            WHERE row.relayState = com.carddemo.fraud.entity.OutboxEventEntity.RelayState.PENDING
              AND row.nextAttemptAt <= :now
              AND NOT EXISTS (
                    SELECT preceding.eventId FROM OutboxEventEntity preceding
                    WHERE preceding.aggregateId = row.aggregateId
                      AND preceding.relayState IN (
                          com.carddemo.fraud.entity.OutboxEventEntity.RelayState.PENDING,
                          com.carddemo.fraud.entity.OutboxEventEntity.RelayState.CLAIMED)
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
     * <p>Without this, one crash costs one assessment permanently: the row stays {@code CLAIMED}, the
     * claim query filters on {@code PENDING}, and nothing ever looks at it again.
     *
     * @param relayState    always {@link OutboxEventEntity.RelayState#CLAIMED}; the parameter keeps
     *                      the derived query readable rather than hiding the state in a name
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
     * <p>This is the set {@code outbox/OutboxRelay} reads at the head of every pass. An owed
     * diagnostic is the last remaining record of an assessment this service gave up on, and this
     * service has no ancestor to fall back on: nothing in {@code app/cbl/} computes a risk score, so
     * there is no batch job to re-run and no reject dataset holding what was missed. The diagnostic
     * is therefore offered again for as long as it takes, and the ordering names the row that has
     * gone unnamed longest first.
     *
     * <p>The set is empty while the relay is healthy, and the partial index
     * {@code ix_outbox_event_dead_letter_required} of
     * {@code src/main/resources/db/migration/V5__outbox_dead_letter_state.sql} covers exactly it, so
     * the read costs nothing on a service with nothing to report.
     *
     * <p>No lock is taken here. Each row this returns is written in a short transaction of its own
     * once the broker has acknowledged its diagnostic, and a second relay instance offering the same
     * diagnostic publishes one duplicate on a dead-letter topic rather than losing one.
     *
     * @param deadLetterState always {@link OutboxEventEntity.DeadLetterState#REQUIRED}; naming it as
     *                        a parameter keeps the derived query readable rather than hiding the
     *                        state inside a method name
     * @param limit           how many rows to return
     * @return rows owing a diagnostic, oldest attempt first, and empty when none is owed
     */
    List<OutboxEventEntity> findByDeadLetterStateOrderByLastAttemptAtAsc(
            OutboxEventEntity.DeadLetterState deadLetterState, Limit limit);

    /**
     * Lock timeout that asks the database to pass over a locked row instead of waiting for it.
     *
     * <p>Hibernate maps this value to {@code SKIP LOCKED}. A positive value would be a wait in
     * milliseconds, and zero would fail immediately on a locked row.
     */
    String SKIP_LOCKED_TIMEOUT = "-2";

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
                    com.carddemo.fraud.entity.OutboxEventEntity.RelayState.PENDING
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
                    com.carddemo.fraud.entity.OutboxEventEntity.RelayState.PENDING
              AND row.nextAttemptAt <= :now
            """)
    Optional<Instant> findEarliestDueBefore(@Param("now") Instant now);

}
