package com.carddemo.notification.repository;

import com.carddemo.notification.entity.ProcessedEventEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the marker that records one processed event identifier, so a second delivery
 * of that event changes nothing.
 *
 * <p>Each of the four listeners checks the event identifier before acting, then writes the marker in
 * the same local transaction as the work it guards: {@code messaging/TransactionAuthorizedConsumer},
 * {@code messaging/TransactionPostedConsumer}, {@code messaging/FraudFlaggedConsumer} and
 * {@code messaging/CustomerContextChangedConsumer}.
 *
 * <p>The repository pattern's Common Business Oriented Language (COBOL) ancestor is the generic
 * parameter area {@code LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112}, whose operation
 * code dispatches every read and write behind one subroutine.
 *
 * <p>No COBOL ancestor: no COBOL program detects a duplicate delivery, and the transaction write at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} ends the run at L577.
 */
public interface ProcessedEventRepository extends ListCrudRepository<ProcessedEventEntity, UUID> {

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
