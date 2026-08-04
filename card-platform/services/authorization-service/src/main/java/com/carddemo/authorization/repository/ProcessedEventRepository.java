package com.carddemo.authorization.repository;

import com.carddemo.authorization.entity.ProcessedEventEntity;
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
 * <p>ADDITIVE. This interface has no COBOL ancestor. No program in {@code app/cbl/} detects a
 * duplicate delivery, and {@code app/cbl/CBTRN02C.cbl:L562-L579} drives a replayed feed into a
 * duplicate key and straight to the abend routine.
 *
 * <p>The incoming event this marker guards is {@code AccountStateChanged}, which the account service
 * publishes on an account update and on a cycle close. The authorization service reads its
 * {@code account_credit_snapshot} rows on every decision, and that projection is only current while
 * something applies those events to it. A consumer that applies one twice would double-count a
 * cycle accumulator, so it records the event identifier here in the same local transaction as the
 * projection row it changes.
 *
 * <p>The interface extends {@link Repository} rather than a full create-read-update-delete base,
 * because a marker has exactly three operations. A consumer asks whether an identifier is already
 * present, writes one that is not, and a purge removes markers past the retention horizon. Nothing
 * updates a marker and nothing deletes one by identifier, so neither operation is exposed.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md} (planned).
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
}
