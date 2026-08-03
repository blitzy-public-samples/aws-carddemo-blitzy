package com.carddemo.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Records one event identifier the notification service has already processed.
 *
 * <p>One instance maps to one row of the table {@code processed_event}, whose two columns this
 * module declares at {@code src/main/resources/db/migration/V1__schema.sql:L73-L77}. A second
 * insert of one identifier violates the primary key {@code pk_processed_event}.</p>
 *
 * <p>ADDITIVE: no Common Business Oriented Language (COBOL) copybook and no COBOL program declares
 * an equivalent record. CardDemo detects no duplicate delivery anywhere. The transaction write at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} reaches {@code PERFORM 9999-ABEND-PROGRAM} at L577 on a
 * duplicate key. Each of the eight Customer Information Control System (CICS) file definitions in
 * {@code app/csd/CARDDEMO.CSD} specifies {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}.</p>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.</p>
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEventEntity {

    /**
     * Identifier of the processed event, assigned by the service that published it.
     *
     * <p>Maps to {@code event_id UUID NOT NULL}, which carries the primary key
     * {@code pk_processed_event}.</p>
     */
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    /**
     * Instant at which the notification service finished processing the event.
     *
     * <p>Maps to {@code processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL}.</p>
     */
    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /**
     * No-argument constructor for the Jakarta Persistence provider.
     *
     * <p>The provider calls it while materialising a row, then assigns both fields. Application
     * code calls {@link #ProcessedEventEntity(UUID, Instant)}.</p>
     */
    protected ProcessedEventEntity() {
    }

    /**
     * Builds a marker for one processed event.
     *
     * @param eventId     identifier of the processed event
     * @param processedAt instant at which processing finished
     * @throws NullPointerException when either argument is {@code null}, naming the field
     */
    public ProcessedEventEntity(UUID eventId, Instant processedAt) {
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.processedAt = Objects.requireNonNull(processedAt, "processedAt must not be null");
    }

    /**
     * Returns the identifier of the processed event.
     *
     * @return the event identifier, never {@code null} after the public constructor runs
     */
    public UUID getEventId() {
        return eventId;
    }

    /**
     * Returns the instant at which processing finished.
     *
     * @return the processing instant, never {@code null} after the public constructor runs
     */
    public Instant getProcessedAt() {
        return processedAt;
    }

    /**
     * Compares two markers by {@code eventId} alone, which is the whole primary key.
     *
     * @param other the object to compare with this marker
     * @return {@code true} when {@code other} is a marker whose {@code eventId} equals this one
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProcessedEventEntity that)) {
            return false;
        }
        return Objects.equals(eventId, that.eventId);
    }

    /**
     * Derives the hash code from {@code eventId} alone, matching {@link #equals(Object)}.
     *
     * @return the hash code of the event identifier, and zero while the identifier is unset
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(eventId);
    }

    /**
     * Renders both fields for a log line. Neither field carries a card number.
     *
     * @return the simple class name followed by the event identifier and the processing instant
     */
    @Override
    public String toString() {
        return "ProcessedEventEntity[eventId=" + eventId + ", processedAt=" + processedAt + "]";
    }
}
