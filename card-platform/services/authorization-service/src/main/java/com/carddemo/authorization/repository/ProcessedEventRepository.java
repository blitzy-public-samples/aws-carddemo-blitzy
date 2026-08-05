package com.carddemo.authorization.repository;

import com.carddemo.authorization.entity.ProcessedEventEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Access path to the idempotency marker of the authorization service, table
 * {@code processed_event}.
 *
 * <p>This interface has no COBOL ancestor. No program in {@code app/cbl/} detects a
 * duplicate delivery, and {@code app/cbl/CBTRN02C.cbl:L562-L579} drives a replayed feed into a
 * duplicate key and straight to the abend routine.
 *
 * <p>The incoming event this marker guards is {@code AccountStateChanged}, which the account service
 * publishes on an account update and on a cycle close. The authorization service reads its
 * {@code account_credit_snapshot} rows on every decision, and that projection is only current while
 * something applies those events to it. A consumer that applies one twice would double-count a
 * cycle accumulator, so it records the event identifier here in the same local transaction as the
 * projection row it changes.
 *
 * <p>The interface extends {@link Repository} rather than a full create-read-update-delete base,
 * because a marker has exactly three operations. A consumer asks whether an identifier is already
 * present, writes one that is not, and a purge removes markers past the retention horizon. Nothing
 * updates a marker and nothing deletes one by identifier, so neither operation is exposed.
 *
 */
public interface ProcessedEventRepository extends Repository<ProcessedEventEntity, UUID> {

    /** Rows {@link #claimEvent} writes when the marker already exists, so the delivery is a repeat. */
    int ALREADY_CLAIMED = 0;

    /**
     * Inserts the marker for one event, and reports whether this delivery took the claim.
     *
     * <p>The insert carries {@code ON CONFLICT (event_id) DO NOTHING}, so two deliveries racing on
     * the same event cannot both proceed: the loser reads {@link #ALREADY_CLAIMED} and writes
     * nothing. A read followed by a write cannot give that guarantee, because both readers see no
     * marker before either writes one.
     *
     * <p>The caller runs this inside the transaction that carries its side effects, so the marker
     * and the effects commit together or not at all.
     *
     * @param eventId       the event identifier from the envelope
     * @param processedAt   the instant the marker records
     * @param consumedTopic the topic the delivery arrived on, or {@code null}
     * @return 1 when this delivery took the claim, {@link #ALREADY_CLAIMED} when it did not
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_event (event_id, processed_at, consumed_topic)
            VALUES (:eventId, :processedAt, :consumedTopic)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int claimEvent(@Param("eventId") UUID eventId,
            @Param("processedAt") Instant processedAt,
            @Param("consumedTopic") String consumedTopic);

    /**
     * Reports whether this service has already handled the event with the given identifier.
     *
     * @param eventId the event identifier from the envelope
     * @return {@code true} when a marker already exists
     */
    boolean existsById(UUID eventId);

    /**
     * Writes one marker.
     *
     * <p>The caller writes the marker in the same local transaction as the side effects it guards.
     * A marker written in its own transaction leaves a window in which the side effects have
     * committed and the marker has not, and a redelivery inside that window applies them twice.
     *
     * @param marker the marker to write
     * @return the written marker
     */
    ProcessedEventEntity save(ProcessedEventEntity marker);

    /**
     * Deletes at most {@code limit} markers older than the horizon, and returns how many it removed.
     *
     * <p>The bound keeps one retention pass from producing a single very large statement on a schema
     * that has been idle for a long time. A caller repeats the call until it returns zero.
     *
     * @param horizon the instant before which a marker is removed
     * @param limit   the largest number of rows one statement removes
     * @return the number of rows removed
     */
    @Modifying
    @Query(value = """
            DELETE FROM processed_event
            WHERE event_id IN (SELECT event_id
                               FROM processed_event
                               WHERE processed_at < :horizon
                               ORDER BY processed_at
                               LIMIT :limit)
            """, nativeQuery = true)
    int deleteMarkersProcessedBefore(@Param("horizon") Instant horizon, @Param("limit") int limit);
}
