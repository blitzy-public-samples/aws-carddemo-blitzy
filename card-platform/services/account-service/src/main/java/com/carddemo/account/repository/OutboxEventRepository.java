package com.carddemo.account.repository;

import com.carddemo.account.entity.OutboxEventEntity;
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
 * Access to the transactional-outbox rows of the account service, table {@code outbox_event} in its
 * private schema.
 *
 * <p>{@code app/cbl/CBSTM03B.CBL:L99-L112} declares a generic parameter area whose operation code
 * selects the access path, and this package reproduces that contract as one interface per
 * aggregate. The inherited {@code save} reproduces the write {@code 'W'} at
 * {@code app/cbl/CBSTM03B.CBL:L107} and the rewrite {@code 'Z'} at {@code L108}, and the inherited
 * {@code findById} reproduces the keyed read {@code 'K'} at {@code L106}.
 *
 * <p>The interface has one ancestor: the single asynchronous handoff of the source.
 * The source writes that handoff with the Customer Information Control System (CICS) command
 * {@code EXEC CICS WRITEQ TD} on {@code QUEUE ('JOBS')} at
 * {@code app/cbl/CORPT00C.cbl:L517-L518}, inside the paragraph {@code WIRTE-JOBSUB-TDQ} at
 * {@code L515}.
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
    List<OutboxEventEntity> findByPublishedFalseOrderByCreatedAtAscEventIdAsc(Limit limit);

    /**
     * Claims up to {@code limit} rows that are due for a publish attempt, excluding every row a
     * competing relay instance already holds.
     *
     * <p>{@code FOR NO KEY UPDATE ... SKIP LOCKED} is what makes this safe to run from more than one
     * instance. {@link LockModeType#PESSIMISTIC_WRITE} with a lock timeout of
     * {@value #SKIP_LOCKED_TIMEOUT} renders as exactly that on PostgreSQL: the lock is taken as the
     * rows are read, and a row another transaction has locked is skipped rather than waited for, so
     * two relays working the same table return disjoint batches and neither blocks the other. A plain
     * finder cannot do this: two instances read the same unpublished rows and publish every one of
     * them twice.
     *
     * <p>The query is written in the Jakarta Persistence Query Language rather than as native SQL so
     * that the schema comes from the entity mapping. A native {@code FROM outbox_event} carries no
     * schema, and {@code spring.jpa.properties.hibernate.default_schema} does not apply to a native
     * string, so such a query fails wherever the search path does not already name the right schema.
     *
     * <p>The filter is {@code relayState = PENDING} and {@code nextAttemptAt <= now}, so a row
     * awaiting its backoff is left alone and a row in either terminal state is never returned. A
     * correlated absence check admits only the oldest non-terminal row of each account. A later
     * row therefore waits behind an earlier row even while that earlier row is in backoff, while a
     * failure on one account does not stop another account's head.
     *
     * <p>The caller runs this inside a transaction and calls
     * {@link OutboxEventEntity#claim(String, java.time.Instant)} on each row it takes, which
     * records which instance won. The lock lasts only as long as that transaction, and
     * {@code claimed_by} is what survives it; a row whose claim outlives its usefulness is
     * recovered by
     * {@link #findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(OutboxEventEntity.RelayState,
     * java.time.Instant, org.springframework.data.domain.Limit)}.
     *
     * @param now   the current time, against which {@code nextAttemptAt} is compared
     * @param limit how many rows to claim, at least one
     * @return the claimed account heads in due and write order, at most {@code limit} rows,
     *         and empty when no row is due
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED_TIMEOUT))
    @Query("""
            SELECT row FROM OutboxEventEntity row
            WHERE row.relayState = com.carddemo.account.entity.OutboxEventEntity.RelayState.PENDING
              AND row.nextAttemptAt <= :now
              AND NOT EXISTS (
                    SELECT preceding.eventId FROM OutboxEventEntity preceding
                    WHERE preceding.aggregateId = row.aggregateId
                      AND preceding.relayState IN (
                          com.carddemo.account.entity.OutboxEventEntity.RelayState.PENDING,
                          com.carddemo.account.entity.OutboxEventEntity.RelayState.CLAIMED)
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
     * Returns abandoned rows that still owe a terminal diagnostic, oldest attempt first.
     *
     * <p>An abandoned row is terminal and {@link #claimDueRows(Instant, Limit)} does not return it,
     * so without this query the sweep that gave up on a row is the last sweep that ever sees it.
     * This is the query that makes a refused dead letter a delay rather than a loss: the obligation
     * sits in {@code dead_letter_state} and every later sweep reads it back until the broker
     * acknowledges the diagnostic.
     *
     * <p>The same {@code SKIP LOCKED} lock the claim query takes applies here, so two relay
     * instances sweeping one table divide the owed diagnostics between them instead of publishing
     * the same one twice.
     *
     * <p>The partial index {@code ix_outbox_event_dead_letter_required} from
     * {@code src/main/resources/db/migration/V5__outbox_dead_letter_state.sql} serves this query and
     * holds only the owed rows, so the read costs nothing while the relay is healthy.
     *
     * @param deadLetterState always {@link OutboxEventEntity.DeadLetterState#REQUIRED}; passing it
     *                        keeps the derived query readable rather than hiding the state in a name
     * @param limit           how many owed rows one sweep takes on
     * @return abandoned rows owing a diagnostic, oldest attempt first, and empty when none is owed
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED_TIMEOUT))
    List<OutboxEventEntity> findByDeadLetterStateOrderByLastAttemptAtAsc(
            OutboxEventEntity.DeadLetterState deadLetterState, Limit limit);
}
