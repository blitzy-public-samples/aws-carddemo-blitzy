package com.carddemo.account.repository;

import com.carddemo.account.entity.ProcessedEventEntity;
import com.carddemo.account.entity.ProcessedEventEntity.ProcessedEventId;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Access path to the processed-event idempotency marker of the account service private schema,
 * table {@code processed_event}.
 *
 * <p>{@code app/cbl/CBSTM03B.CBL:L99-L112} declares a generic parameter area whose operation code
 * selects the access path, and this package reproduces that contract as one interface per
 * aggregate. {@link #existsById(ProcessedEventId)} reproduces the keyed read {@code 'K'} at
 * {@code app/cbl/CBSTM03B.CBL:L106} and {@link #save(ProcessedEventEntity)} reproduces the write
 * {@code 'W'} at {@code L107}.
 *
 * <p>No COBOL ancestor: no Customer Information Control System (CICS) program and no Job Control
 * Language (JCL) member declares this marker, and every file definition in {@code
 * app/csd/CARDDEMO.CSD} carries {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}.
 *
 * <p>{@code messaging/TransactionPostedConsumer} claims a key here on every delivery of
 * {@code transaction.posted}, in the same local transaction as the posted amount it applies to the
 * account row and the {@code AccountStateChanged} event it publishes. That is the one caller today,
 * and the table and this interface carry the idempotency contract every consumer of this platform
 * honours, so a second consumer added to this service inherits both unchanged.
 *
 * <p><b>The marker is keyed by the event and the topic together.</b> This service reads one topic
 * today, so no delivery here can currently collide with another topic's. The key names the topic
 * anyway, because event identifiers are assigned by the publishing service and different producers
 * assign them independently: the identifier alone stops identifying a delivery the moment a second
 * listener is added, and it stops silently. This schema is the evidence that a second listener does
 * get added, because it declared this table with no listener at all and then acquired its first one.
 * {@code src/main/resources/db/migration/V6__processed_event_topic_key.sql} carries the reasoning at
 * length. Every operation below names both key columns for that reason.
 *
 * <p>The interface extends {@link Repository} rather than a full create-read-update-delete base,
 * because a marker has exactly three operations. Nothing updates a marker and nothing deletes one
 * by identifier, so neither operation is exposed.
 */
public interface ProcessedEventRepository
        extends Repository<ProcessedEventEntity, ProcessedEventId> {

    /**
     * Reports whether this service has already handled one event on one topic.
     *
     * <p>The whole key is required, not the identifier alone. A delivery is identified by its event
     * and the stream it arrived on: two producing services assign identifiers independently, so an
     * answer given on the identifier alone would refuse a different event that happens to share one
     * with an event already handled elsewhere. That refusal reads exactly like a redelivery, and the
     * effect the second event carried is lost with no trace.
     *
     * @param id the event identifier from the envelope, with the topic the delivery arrived on
     * @return {@code true} when a marker already exists for that event on that topic
     */
    boolean existsById(ProcessedEventId id);

    /**
     * Writes one marker.
     *
     * <p>The caller writes the marker in the same local transaction as the side effects it guards.
     * A marker written in its own transaction leaves a window in which the side effects have
     * committed and the marker has not. A redelivery inside that window applies them twice.
     *
     * @param marker the marker to write
     * @return the written marker
     */
    ProcessedEventEntity save(ProcessedEventEntity marker);

    /**
     * Deletes markers written before the given instant, and returns how many it removed.
     *
     * <p>{@code carddemo.processed-event.marker-retention-hours} in
     * {@code src/main/resources/application.yml} supplies the horizon, and
     * {@code ix_processed_event_processed_at} serves this delete.
     *
     * <p>The delete matches on {@code processed_at} rather than on either key column, so it removes
     * a whole row whichever topic keyed it.
     *
     * @param horizon the instant before which a marker is removed
     * @return the number of markers removed
     */
    @Modifying
    @Query("DELETE FROM ProcessedEventEntity marker WHERE marker.processedAt < :horizon")
    int deleteMarkersProcessedBefore(@Param("horizon") Instant horizon);

    /**
     * Claims one event identifier for processing, and reports whether this caller is the first to
     * do so.
     *
     * <p>{@code ON CONFLICT DO NOTHING} is the whole guarantee. A consumer that reads first and
     * then inserts has a window between the two. A second delivery of the same event reads
     * nothing inside that window, and both deliveries apply their side effects. One statement
     * that inserts or
     * declines to has no such window: the primary key decides the race inside the database.
     *
     * <p>The conflict target names the topic as well as the event, which is what keeps this guard
     * from suppressing a DIFFERENT event that happens to share an identifier with one already
     * handled on another topic. Suppressing a redelivery is the purpose of the guard; suppressing a
     * different event dropped its effect in silence, since nothing raised, nothing reached a
     * dead-letter topic, and the marker that caused it stays.
     *

     * <p>The caller runs this in the same transaction as its side effects and acknowledges the
     * message only after that transaction commits. A crash before the commit rolls back the marker
     * along with the side effects, so the redelivery that follows is the first to claim the event
     * again. A crash after the commit but before the acknowledgement leaves the marker, so the
     * redelivery is refused here and the consumer skips straight to acknowledging. Neither order
     * double-processes and neither loses the event.
     *
     * @param eventId    the event identifier from the envelope
     * @param processedAt when this consumer began handling the event
     * @param consumedTopic which topic the delivery arrived on, at most 128 characters; a caller
     *                      with no topic header passes
     *                      {@link ProcessedEventEntity#NO_CONSUMED_TOPIC}, since a key column
     *                      holds no null
     * @return 1 when this caller claimed the event, and 0 when it was already claimed
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
