package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.ProcessedEventEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the processed-event marker that is to let a duplicate delivery change nothing.
 *
 * <p>The planned consumer in the {@code messaging} package is to check for the event identifier
 * before acting, then write the marker in the same local transaction as the business effect. No
 * consumer is authored yet, so no row exists.</p>
 *
 * <p>The repository pattern's Common Business Oriented Language (COBOL) ancestor is the generic
 * parameter area {@code LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112}, whose operation
 * code dispatches every read and write behind one subroutine.</p>
 *
 * <p>ADDITIVE. No COBOL program checks for a duplicate delivery: a failed transaction write at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} ends the run at L577.</p>
 */
public interface ProcessedEventRepository extends ListCrudRepository<ProcessedEventEntity, UUID> {

    /**
     * Deletes markers written before the given instant, and returns how many it removed.
     *
     * <p>A marker matters only while a redelivery of its event is still possible. Past that horizon
     * it is dead weight on a table that otherwise grows for the life of the service.
     * {@code carddemo.processed-event.marker-retention-hours} in
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
