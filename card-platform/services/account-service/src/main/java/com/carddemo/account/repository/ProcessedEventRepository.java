package com.carddemo.account.repository;

import com.carddemo.account.entity.ProcessedEventEntity;
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
 * aggregate. {@link #existsById(UUID)} reproduces the keyed read {@code 'K'} at
 * {@code app/cbl/CBSTM03B.CBL:L106} and {@link #save(ProcessedEventEntity)} reproduces the write
 * {@code 'W'} at {@code L107}.
 *
 * <p>ADDITIVE: no Customer Information Control System (CICS) program and no Job Control Language
 * (JCL) member declares this marker, and every file definition in {@code app/csd/CARDDEMO.CSD}
 * carries {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}.
 *
 * <p>The account service publishes {@code AccountStateChanged} and consumes nothing at this
 * boundary, so no caller writes a marker yet. The table and this interface carry the idempotency
 * contract every consumer of this platform honours, so a consumer added to this service inherits
 * both unchanged. {@code card-platform/docs/decision-log.md} (planned) records that choice.
 *
 * <p>The interface extends {@link Repository} rather than a full create-read-update-delete base,
 * because a marker has exactly three operations. Nothing updates a marker and nothing deletes one
 * by identifier, so neither operation is exposed.
 */
public interface ProcessedEventRepository extends Repository<ProcessedEventEntity, UUID> {

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
     * Deletes markers written before the given instant, and returns how many it removed.
     *
     * <p>{@code carddemo.processed-event.marker-retention-hours} in
     * {@code src/main/resources/application.yml} supplies the horizon, and
     * {@code ix_processed_event_processed_at} serves this delete.
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
     * then inserts has a window between the two in which a second delivery of the same event reads
     * nothing, and both deliveries then apply their side effects; one statement that inserts or
     * declines to has no such window, because the primary key decides the race inside the database.
     *
     * <p>The caller runs this in the same transaction as its side effects and acknowledges the
     * message only after that transaction commits. A crash before the commit rolls back the marker
     * along with the side effects, so the redelivery that follows is the first to claim the event
     * again; a crash after the commit but before the acknowledgement leaves the marker, so the
     * redelivery is refused here and the consumer skips straight to acknowledging. Neither order
     * double-processes and neither loses the event.
     *
     * @param eventId    the event identifier from the envelope
     * @param processedAt when this consumer began handling the event
     * @param consumedTopic which topic the delivery arrived on, at most 128 characters
     * @return 1 when this caller claimed the event, and 0 when it was already claimed
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
}
