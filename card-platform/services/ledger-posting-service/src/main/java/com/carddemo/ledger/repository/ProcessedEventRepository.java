package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.ProcessedEventEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the processed-event marker that lets a duplicate delivery change nothing.
 *
 * <p>No ledger listener currently writes this repository. A consumer added to this module must
 * claim the event identifier and commit the marker in the same local transaction as its business
 * effect, marker after effects, and acknowledge the delivery only once that transaction commits.
 * {@link #claimEvent(UUID, Instant, String)} is that one statement.</p>
 *
 * <p>The repository pattern's Common Business Oriented Language (COBOL) ancestor is the generic
 * parameter area {@code LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112}, whose operation
 * code dispatches every read and write behind one subroutine.</p>
 *
 * <p>No COBOL program checks for a duplicate delivery: a failed transaction write at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} ends the run at L577.</p>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
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
     * then inserts has a window between the two. A second delivery of the same event reads
     * nothing inside that window, and both deliveries apply their side effects. One statement
     * that inserts or declines to has no such window: the primary key decides the race inside
     * the database.
     *
     * <p>A caller runs this in the same transaction as its side effects and acknowledges the
     * message only after that transaction commits. A crash before the commit rolls back the marker
     * along with the side effects, so the redelivery that follows is the first to claim the event
     * again. A crash after the commit but before the acknowledgement leaves the marker, so the
     * redelivery is refused here and the consumer skips straight to acknowledging. Neither order
     * double-processes and neither loses the event.
     *
     * <p>{@code messaging/TransactionAuthorizedConsumer} takes the read-then-mark order this
     * platform pins for all three of its consumers, so no listener in this service calls this
     * method. It remains available to a caller that needs the one-statement form.
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
