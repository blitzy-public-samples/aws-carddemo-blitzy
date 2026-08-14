package com.carddemo.notification.repository;

import com.carddemo.notification.entity.ProcessedEventEntity;
import com.carddemo.notification.entity.ProcessedEventEntity.ProcessedEventId;
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
 * <p><b>The marker is keyed by the event and the topic together.</b> The four listeners read four
 * topics, and three producing services assign event identifiers independently, so the same
 * identifier can arrive on two topics without either producer being at fault. The identifier alone
 * therefore does not identify a delivery, and
 * {@code src/main/resources/db/migration/V3__processed_event_topic_key.sql} carries the reasoning at
 * length. Every operation below names both key columns for that reason.
 *
 * <p>No COBOL ancestor: no COBOL program detects a duplicate delivery, and the transaction write at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} ends the run at L577.
 */
public interface ProcessedEventRepository
        extends ListCrudRepository<ProcessedEventEntity, ProcessedEventId> {

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
     * different event dropped a read-model row in silence, since nothing raised, nothing reached a
     * dead-letter topic, and the marker that caused it stays.
     *
     * <p>The caller runs this inside the transaction that carries its side effects, so the marker
     * and the effects commit together or not at all.
     *
     * @param eventId       the event identifier from the envelope
     * @param processedAt   the instant the marker records
     * @param consumedTopic the topic the delivery arrived on; a caller with no topic header passes
     *                      {@link ProcessedEventEntity#NO_CONSUMED_TOPIC}, since a key column holds
     *                      no null
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
