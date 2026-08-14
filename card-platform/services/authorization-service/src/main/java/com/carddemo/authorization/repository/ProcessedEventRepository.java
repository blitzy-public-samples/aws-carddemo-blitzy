package com.carddemo.authorization.repository;

import com.carddemo.authorization.entity.ProcessedEventEntity;
import com.carddemo.authorization.entity.ProcessedEventEntity.ProcessedEventId;
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
 * <p>Two listeners write this table. {@code messaging/AccountStateChangedConsumer} reads
 * {@code AccountStateChanged}, which the account service publishes on an account update, on a cycle
 * close and on a posted amount, and {@code messaging/CardUpdatedConsumer} reads {@code CardUpdated}
 * from the card service. The authorization service reads its {@code account_credit_snapshot} and
 * {@code card_xref} rows on every decision, and those projections are only current while something
 * applies those events to them. A consumer that applies one twice would double-count a cycle
 * accumulator, so it records the key here in the same local transaction as the projection row it
 * changes.
 *
 * <p><b>The marker is keyed by the event and the topic together.</b> Those two listeners read two
 * topics, and two producing services assign the event identifiers on them independently, so two
 * different events may carry one identifier without either producer being at fault. The identifier
 * alone therefore does not identify a delivery, and
 * {@code src/main/resources/db/migration/V6__processed_event_topic_key.sql} carries the reasoning at
 * length. Every operation below names both key columns for that reason.
 *
 * <p>The interface extends {@link Repository} rather than a full create-read-update-delete base,
 * because a marker has exactly three operations. A consumer asks whether an identifier is already
 * present, claims one that is not, and writes the marker. Nothing updates a marker and nothing
 * deletes one, so neither operation is exposed.
 *
 * <p><b>A claim is permanent.</b> This interface exposed a purge over a 720-hour horizon until a
 * security review found that horizon expiring claims while the effects they guard outlived them: a
 * decision row kept for audit, and two replica tables kept for as long as the service runs. A
 * restored or deliberately replayed record arriving after the purge was new to the guard and applied
 * twice. Migration {@code V21__processed_event_claims_are_permanent.sql} states the same thing in
 * the catalogue, and there is no method here to remove a row.
 *
 */
public interface ProcessedEventRepository
        extends Repository<ProcessedEventEntity, ProcessedEventId> {

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
     * committed and the marker has not, and a redelivery inside that window applies them twice.
     *
     * @param marker the marker to write
     * @return the written marker
     */
    ProcessedEventEntity save(ProcessedEventEntity marker);
}
