package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.ProcessedEventEntity;
import com.carddemo.ledger.entity.ProcessedEventEntity.ProcessedEventId;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the processed-event marker that lets a duplicate delivery change nothing.
 *
 * <p>Three listeners write this repository. {@code messaging/TransactionAuthorizedConsumer} reads
 * {@code transaction.authorized} and {@code messaging/TransactionDeclinedConsumer} reads
 * {@code transaction.declined}, both taking the read-then-mark order;
 * {@code messaging/AccountStateChangedConsumer} reads {@code account.state-changed} and takes the
 * one-statement claim. Each commits the marker in the same local transaction as its business effect,
 * marker after effects, and acknowledges the delivery only once that transaction commits.</p>
 *
 * <p><b>The marker is keyed by the event and the topic together.</b> Those listeners read separate
 * topics whose event identifiers separate producing services assign independently, so two different
 * events may carry one identifier without either producer being at fault. The identifier
 * alone therefore does not identify a delivery, and
 * {@code src/main/resources/db/migration/V5__processed_event_topic_key.sql} carries the reasoning at
 * length. Every operation below names both key columns for that reason.</p>
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
public interface ProcessedEventRepository
        extends ListCrudRepository<ProcessedEventEntity, ProcessedEventId> {

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
     * <p>The conflict target names the topic as well as the event, which is what keeps this guard
     * from suppressing a DIFFERENT event that happens to share an identifier with one already
     * handled on another topic. Suppressing a redelivery is the purpose of the guard; suppressing a
     * different event dropped its effect in silence, since nothing raised, nothing reached a
     * dead-letter topic, and the marker that caused it stays.
     *
     * <p>{@code messaging/TransactionAuthorizedConsumer} takes the read-then-mark order this
     * platform pins for most of its consumers, so only
     * {@code messaging/AccountStateChangedConsumer} calls this method today. It remains available to
     * any caller that needs the one-statement form.
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
