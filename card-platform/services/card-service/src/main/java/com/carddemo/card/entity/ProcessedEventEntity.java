package com.carddemo.card.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Marks one event identifier the card service has already handled, as one row of table
 * {@code processed_event}.
 *
 * <p>ADDITIVE: no Common Business Oriented Language (COBOL) copybook and no COBOL program declares
 * this record, and the source detects no duplicate anywhere. Paragraph
 * {@code 2900-WRITE-TRANSACTION-FILE} at {@code app/cbl/CBTRN02C.cbl:L562-L579} writes each posted
 * transaction and tests {@code IF TRANFILE-STATUS = '00'} at L566. A duplicate key fails that test
 * and reaches {@code PERFORM 9999-ABEND-PROGRAM} at L577, whose routine at L707-L711 holds four
 * statements and cleans nothing up. Each of the eight file definitions in
 * {@code app/csd/CARDDEMO.CSD} carries {@code RECOVERY(NONE)}.
 *
 * <p>A card-service consumer inserts one row inside the same local transaction as its side effects,
 * once those side effects succeed, and supplies both values. A redelivery finds the row present,
 * and the {@code repository} package owns that lookup by primary key.
 *
 * <p>Both columns, and the primary key that is their only access path, come from
 * {@code src/main/resources/db/migration/V1__schema.sql:L80-L84}. The {@link Table} annotation
 * names no schema, and {@code src/main/resources/application.yml} supplies one and sets
 * {@code ddl-auto: validate}, so Hibernate checks this mapping against that migration at start-up.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEventEntity {

    /** Event identifier the producing service assigned, a Universally Unique Identifier (UUID). */
    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    /** Instant at which the caller finished handling the event. */
    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /** No-argument constructor the persistence provider calls before assigning both fields. */
    protected ProcessedEventEntity() {
    }

    /**
     * Builds a marker for one handled event.
     *
     * @param eventId     identifier the producing service assigned to the event
     * @param processedAt instant at which the caller finished handling the event
     * @throws NullPointerException when either argument is {@code null}, naming the argument
     */
    public ProcessedEventEntity(UUID eventId, Instant processedAt) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.processedAt = Objects.requireNonNull(processedAt, "processedAt");
    }

    /** {@return the event identifier this marker records, from column {@code event_id}} */
    public UUID getEventId() {
        return eventId;
    }

    /** {@return the handling instant, from column {@code processed_at}} */
    public Instant getProcessedAt() {
        return processedAt;
    }

    /**
     * Compares markers on {@code eventId} alone, the primary key. An identifier the persistence
     * provider has not yet assigned equals itself alone.
     *
     * @param other object to compare with this marker
     * @return {@code true} when {@code other} is a marker carrying an equal event identifier
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProcessedEventEntity that)) {
            return false;
        }
        return eventId != null && eventId.equals(that.eventId);
    }

    /** Hashes {@code eventId} alone, matching {@link #equals(Object)}. Zero while unassigned. */
    @Override
    public int hashCode() {
        return Objects.hashCode(eventId);
    }

    /** Renders both columns for a log line. Neither carries a Primary Account Number (PAN). */
    @Override
    public String toString() {
        return "ProcessedEventEntity[eventId=" + eventId + ", processedAt=" + processedAt + "]";
    }
}
