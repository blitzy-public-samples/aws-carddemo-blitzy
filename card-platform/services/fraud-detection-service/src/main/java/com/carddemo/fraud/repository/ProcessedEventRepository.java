package com.carddemo.fraud.repository;

import com.carddemo.fraud.entity.ProcessedEventEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * The consumer in the sibling {@code messaging} package records one marker row per handled event in
 * table {@code processed_event}, so a duplicate delivery does nothing twice.
 *
 * <p>No COBOL ancestor, where COBOL expands to Common Business Oriented
 * Language.
 *
 * <p>The generic parameter area at {@code app/cbl/CBSTM03B.CBL:L99-L114} carries an operation code
 * that dispatches every read and write behind one subroutine: shape only, no logic.
 *
 * <p>{@link #claimEvent(UUID, Instant, String)} is the atomic idempotency guard, and the
 * {@code eventId} of the event envelope is the idempotency key.
 */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, UUID> {

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
