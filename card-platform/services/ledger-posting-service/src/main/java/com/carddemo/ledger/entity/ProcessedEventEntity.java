package com.carddemo.ledger.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Marks one event identifier as already processed by the ledger posting service.
 *
 * <p>One instance maps to one row of the table {@code processed_event}, whose two columns are
 * declared in this module at {@code src/main/resources/db/migration/V1__schema.sql}. ADDITIVE: no
 * Common Business Oriented Language (COBOL) program carries an equivalent record. Rationale is
 * recorded in card-platform/docs/decision-log.md.</p>
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEventEntity {

    /**
     * Identifier of the consumed event, assigned by the producing service.
     *
     * <p>Maps to {@code event_id UUID NOT NULL}, which carries the primary key
     * {@code pk_processed_event}.</p>
     */
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    /**
     * Instant at which the ledger posting service finished processing the event.
     *
     * <p>Maps to {@code processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL}.</p>
     */
    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /**
     * No-argument constructor for the Jakarta Persistence API (JPA) provider.
     *
     * <p>The provider calls it while materialising a row, then assigns both fields. Application
     * code calls {@link #ProcessedEventEntity(UUID, Instant)}.</p>
     */
    protected ProcessedEventEntity() {
    }

    /**
     * Builds a marker for one consumed event.
     *
     * @param eventId     identifier of the consumed event
     * @param processedAt instant at which processing finished
     * @throws NullPointerException when either argument is {@code null}, naming the field
     */
    public ProcessedEventEntity(UUID eventId, Instant processedAt) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.processedAt = Objects.requireNonNull(processedAt, "processedAt");
    }

    /**
     * Returns the identifier of the consumed event.
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
     * Compares two markers by {@code eventId} alone, accepting any subclass.
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
     * Renders both fields for a log line.
     *
     * @return the simple class name followed by the event identifier and the processing instant
     */
    @Override
    public String toString() {
        return "ProcessedEventEntity[eventId=" + eventId + ", processedAt=" + processedAt + "]";
    }
}
