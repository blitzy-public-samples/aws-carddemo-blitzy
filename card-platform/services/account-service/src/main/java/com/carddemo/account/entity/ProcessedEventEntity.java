package com.carddemo.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Jakarta Persistence entity for the {@code processed_event} table: the identifier of one consumed
 * event and the time a consumer processed it.
 *
 * <p>Additive, with no COBOL ancestor. No copybook and no program declares this record, and the
 * source application detects no duplicate anywhere. {@code app/cbl/CBTRN02C.cbl:L562-L579} writes
 * each posted transaction with no duplicate check and routes every file status other than
 * {@code '00'} to {@code 9999-ABEND-PROGRAM}.</p>
 *
 * <p>The account service registers no listener, so it writes no row here today. Flyway creates the
 * table, which stays empty until this module gains a consumer.</p>
 *
 * <p>Two columns carry the marker: {@code event_id}, a 36-character identifier that is also the
 * primary key, and {@code processed_at}, the moment a consumer's side effects committed. A second
 * insert of one identifier violates that primary key. A consumer inserts the row in the same local
 * transaction as those side effects, and the {@code repository} package owns the existence check.
 * Configuration supplies the schema name, and the table annotation names none.</p>
 *
 * <p>{@code card-platform/docs/decision-log.md} records the idempotent-consumer decision.</p>
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEventEntity {

    /**
     * Identifier of the consumed event, in the canonical 36-character text form of a universally
     * unique identifier. The same value travels in the event envelope and in
     * {@code outbox_event.event_id}, so the two tables compare with no translation.
     */
    @Id
    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    /** Moment a consumer's side effects committed. */
    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /** Creates an empty marker. Jakarta Persistence instantiates the entity through this one. */
    public ProcessedEventEntity() {
    }

    /**
     * Creates a marker for one consumed event.
     *
     * @param eventId     identifier of the consumed event, 36 characters
     * @param processedAt moment the consumer's side effects committed
     */
    public ProcessedEventEntity(String eventId, Instant processedAt) {
        this.eventId = eventId;
        this.processedAt = processedAt;
    }

    /**
     * Reads the event identifier.
     *
     * @return identifier of the consumed event
     */
    public String getEventId() {
        return eventId;
    }

    /**
     * Writes the event identifier.
     *
     * @param eventId identifier of the consumed event, 36 characters
     */
    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    /**
     * Reads the processing time.
     *
     * @return moment the consumer's side effects committed
     */
    public Instant getProcessedAt() {
        return processedAt;
    }

    /**
     * Writes the processing time.
     *
     * @param processedAt moment the consumer's side effects committed
     */
    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    /**
     * Compares two markers on the event identifier alone, which is the primary key.
     *
     * @param other object to compare with this marker
     * @return {@code true} when both markers carry one event identifier
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProcessedEventEntity that)) {
            return false;
        }
        return eventId == null ? that.eventId == null : eventId.equals(that.eventId);
    }

    /**
     * Hashes the event identifier alone, matching {@link #equals(Object)}.
     *
     * @return hash of the event identifier, {@code 0} while the identifier is unset
     */
    @Override
    public int hashCode() {
        return eventId == null ? 0 : eventId.hashCode();
    }

    /**
     * Renders both columns. Neither one carries a card number, a card verification value or a
     * monetary value.
     *
     * @return the event identifier and the processing time
     */
    @Override
    public String toString() {
        return "ProcessedEventEntity[eventId=" + eventId + ", processedAt=" + processedAt + "]";
    }
}
