package com.carddemo.fraud.repository;

import com.carddemo.fraud.entity.ProcessedEventEntity;
import com.carddemo.fraud.entity.ProcessedEventEntity.ProcessedEventId;
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
 * {@code eventId} of the event envelope together with the topic the delivery arrived on is the
 * idempotency key.
 *
 * <p><b>The marker is keyed by the event and the topic together.</b> This service reads one topic
 * today, so no delivery here can currently collide with another topic's. The key names the topic
 * anyway, because event identifiers are assigned by the publishing service and different producers
 * assign them independently: the identifier alone stops identifying a delivery the moment a second
 * listener is added, and it stops silently.
 * {@code src/main/resources/db/migration/V4__processed_event_topic_key.sql} carries the reasoning at
 * length. Every operation below names both key columns for that reason.
 */
@Repository
public interface ProcessedEventRepository
        extends JpaRepository<ProcessedEventEntity, ProcessedEventId> {

    /** Rows {@link #claimEvent} writes when the marker already exists, so the delivery is a repeat. */
    int ALREADY_CLAIMED = 0;

    /**
     * Inserts the marker for one event, and reports whether this delivery took the claim.
     *
     * <p>The insert carries {@code ON CONFLICT (event_id, consumed_topic) DO NOTHING}, so two
     * deliveries of one event on one topic cannot both proceed: the loser reads
     * {@link #ALREADY_CLAIMED} and writes nothing. A read followed by a write cannot give that
     * guarantee, because both readers see no marker before either writes one.
     *
     * <p>The conflict target names the topic as well as the event, which is what keeps this guard
     * from suppressing a DIFFERENT event that happens to share an identifier with one already
     * handled on another topic. Suppressing a redelivery is the purpose of the guard; suppressing a
     * different event dropped its effect in silence, since nothing raised, nothing reached a
     * dead-letter topic, and the marker that caused it stays.
     *

     * <p>The caller runs this inside the transaction that carries its side effects, so the marker
     * and the effects commit together or not at all.
     *
     * @param eventId       the event identifier from the envelope
     * @param processedAt   the instant the marker records
     * @param consumedTopic the topic the delivery arrived on; a caller with no topic header passes
     *                      {@link ProcessedEventEntity#NO_CONSUMED_TOPIC}, since a key column
     *                      holds no null
     * @return 1 when this delivery took the claim, {@link #ALREADY_CLAIMED} when it did not
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_event (event_id, processed_at, consumed_topic)
            VALUES (:eventId, :processedAt, :consumedTopic)
            ON CONFLICT (event_id, consumed_topic) DO NOTHING
            """, nativeQuery = true)
    int claimEvent(@Param("eventId") UUID eventId,
            @Param("processedAt") Instant processedAt,
            @Param("consumedTopic") String consumedTopic);
}
