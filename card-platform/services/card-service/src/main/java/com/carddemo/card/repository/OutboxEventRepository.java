package com.carddemo.card.repository;

import com.carddemo.card.entity.OutboxEventEntity;
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
 * Reads rows of {@code outbox_event}, the card service's private outbox table.
 *
 * <p>No CardDemo program, copybook or job stores an event row.
 *
 * <p>The source holds one asynchronous handoff. Paragraph {@code WIRTE-JOBSUB-TDQ} at
 * {@code app/cbl/CORPT00C.cbl:L515} writes a Customer Information Control System (CICS) transient
 * data queue, and a separate job reads the Job Control Language (JCL) record back. That handoff
 * supplies the shape of the read below and none of its data.
 *
 * <p>Transformed from the called input and output subroutine {@code app/cbl/CBSTM03B.CBL}, whose
 * parameter area spans {@code app/cbl/CBSTM03B.CBL:L100-L112}. {@code LK-M03B-DD PIC X(08)} at
 * {@code app/cbl/CBSTM03B.CBL:L101} selects a dataset, and one interface serves one dataset.
 * {@code LK-M03B-KEY PIC X(25)} at {@code app/cbl/CBSTM03B.CBL:L110} becomes {@link UUID}, the type
 * of the {@code event_id} primary key. {@code LK-M03B-FLDT PIC X(1000)} at
 * {@code app/cbl/CBSTM03B.CBL:L112} becomes {@link OutboxEventEntity}.
 *
 * <p>The status field {@code LK-M03B-RC PIC X(02)} at {@code app/cbl/CBSTM03B.CBL:L109} becomes an
 * empty result or a thrown exception, and the key width {@code LK-M03B-KEY-LN PIC S9(4)} at
 * {@code app/cbl/CBSTM03B.CBL:L111} maps onto nothing.
 *
 * <p>Four of the six operation codes declared at {@code app/cbl/CBSTM03B.CBL:L103-L108} reach a
 * method, and two reach nothing.
 *
 * <pre>
 * code   condition name   locator   target
 * 'R'    M03B-READ        L105      findByPublishedFalseOrderByCreatedAtAsc, inherited findAll
 * 'K'    M03B-READ-K      L106      the inherited findById
 * 'W'    M03B-WRITE       L107      the inherited save
 * 'Z'    M03B-REWRITE     L108      the inherited save
 * 'O'    M03B-OPEN        L103      none
 * 'C'    M03B-CLOSE       L104      none
 * </pre>
 *
 * <p>{@code app/cbl/CBSTM03B.CBL} declares the write and rewrite codes at
 * {@code app/cbl/CBSTM03B.CBL:L107-L108} and implements neither. All four of its datasets open for
 * input alone, at {@code app/cbl/CBSTM03B.CBL:L136}, {@code :L160}, {@code :L184} and
 * {@code :L209}. An insert and an update therefore reach the table through the inherited
 * {@code save} methods and through no method declared below.
 *
 * <p>The card update path writes a row in the same local transaction as the card change that row
 * describes. The relay under {@code com.carddemo.card.outbox} reads that row through the method
 * below, publishes it, then calls {@code markPublished} and saves it in a transaction of its own.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
public interface OutboxEventRepository extends ListCrudRepository<OutboxEventEntity, UUID> {

    /**
     * Deletes at most {@code limit} published rows whose publication is older than the given
     * instant, and returns how many it removed.
     *
     * <p>A published row has done its work and stays only for diagnosis. Nothing reads it again, so
     * past the retention horizon it is dead weight on a table every event passes through.
     * {@code carddemo.retention.published-retention} in
     * {@code src/main/resources/application.yml} supplies the horizon, and the partial index
     * {@code ix_outbox_event_published_at} serves both the subquery and the delete.
     *
     * <p>The condition names {@code published} as well as {@code published_at} so the delete matches
     * the partial index exactly and can never touch a row the relay has not published.
     *
     * <p>{@code limit} bounds one statement, and {@code domain/RetentionSweep} names the bound. An
     * unbounded delete over a table every event passes through locks every matching row for the
     * length of one transaction.
     *
     * @param horizon the instant before which a published row is removed
     * @param limit   the most rows one statement removes, at least one
     * @return the number of rows removed, and 0 when none is past the horizon
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
     * Returns the rows a writer has committed and the relay has not yet published.
     *
     * <p>Reproduces operation code {@code 'R'}, the condition {@code M03B-READ} at
     * {@code app/cbl/CBSTM03B.CBL:L105}, which reads a dataset forward. The ancestor of that read
     * is {@code app/cbl/CORPT00C.cbl:L517-L523}, where
     * {@code EXEC CICS WRITEQ TD QUEUE ('JOBS')} hands one record to a reader that runs later.
     *
     * <p>Rows arrive by {@code created_at} ascending, which is the order their writers committed
     * them. Partial index {@code ix_outbox_event_pending} in
     * {@code src/main/resources/db/migration/V1__schema.sql} spans
     * {@code (created_at, event_id) WHERE published = FALSE} and covers both the filter and the
     * order.
     *
     * <p>The caller supplies the row cap. Property {@code carddemo.outbox.relay.batch-size} in
     * {@code src/main/resources/application.yml} holds the configured value, which
     * {@code com.carddemo.card.config.CardProperties} binds.
     *
     * <p>An empty result means the relay has nothing to publish.
     *
     * @param limit greatest number of rows to return
     * @return unpublished rows, oldest first, and empty when none awaits publication
     */
    List<OutboxEventEntity> findByPublishedFalseOrderByCreatedAtAsc(Limit limit);

    /**
     * Claims the due rows for this caller alone, and returns them.
     *
     * <p>{@code FOR NO KEY UPDATE ... SKIP LOCKED} is what makes this safe to run from more than one
     * instance. {@link LockModeType#PESSIMISTIC_WRITE} with a lock timeout of
     * {@value #SKIP_LOCKED_TIMEOUT} renders as exactly that on PostgreSQL: the lock is taken as the
     * rows are read, and a row another transaction already holds is skipped rather than waited for, so
     * two relays working the same table return disjoint batches and neither blocks the other. The
     * plain finder above cannot do this, which is why one rolling deployment or one manual scale-out
     * had both instances publishing every row twice.
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
     * <p>The caller runs this inside a transaction and calls
     * {@link OutboxEventEntity#claim(String, Instant)} on each row it takes, which records which
     * instance won. The lock lasts only as long as that transaction and {@code claimed_by} is what
     * survives it, so a claim whose instance died is recovered by
     * {@link #findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc}.
     *
     * @param now   the current time, against which {@code nextAttemptAt} is compared
     * @param limit greatest number of rows to claim
     * @return the claimed account heads, longest-waiting first, and empty when no row is due
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED_TIMEOUT))
    @Query("""
            SELECT row FROM OutboxEventEntity row
            WHERE row.relayState = com.carddemo.card.entity.OutboxEventEntity.RelayState.PENDING
              AND row.nextAttemptAt <= :now
              AND NOT EXISTS (
                    SELECT preceding.eventId FROM OutboxEventEntity preceding
                    WHERE preceding.aggregateId = row.aggregateId
                      AND preceding.relayState IN (
                          com.carddemo.card.entity.OutboxEventEntity.RelayState.PENDING,
                          com.carddemo.card.entity.OutboxEventEntity.RelayState.CLAIMED)
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
     * <p>Without this, one crash costs one event permanently: the row stays {@code CLAIMED}, the claim
     * query filters on {@code PENDING}, and nothing ever looks at it again.
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
                    com.carddemo.card.entity.OutboxEventEntity.RelayState.PENDING
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
                    com.carddemo.card.entity.OutboxEventEntity.RelayState.PENDING
              AND row.nextAttemptAt <= :now
            """)
    Optional<Instant> findEarliestDueBefore(@Param("now") Instant now);

}
