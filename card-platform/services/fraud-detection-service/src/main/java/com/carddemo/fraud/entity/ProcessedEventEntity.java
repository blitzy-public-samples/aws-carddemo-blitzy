package com.carddemo.fraud.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row per event identifier this service has already handled, in table {@code processed_event}.
 *
 * <p>ADDITIVE IN FULL: net new; no COBOL ancestor. Searching all 28 Common Business Oriented
 * Language (COBOL) programs in {@code app/cbl/} for {@code fraud}, {@code risk}, {@code velocit},
 * {@code scoring} and {@code luhn} matches none.
 *
 * <p>The CardDemo source detects no duplicate: {@code app/cbl/CBTRN02C.cbl:L564} writes the
 * transaction file, and a failing status test at L566 reaches {@code 9999-ABEND-PROGRAM} at
 * L707-L711, four statements ending in {@code CALL 'CEE3ABD'}. A replayed feed hits a duplicate key
 * and abends.
 *
 * <p>A consumer in the sibling {@code messaging} package reads this table before acting, then
 * inserts the marker in the same local transaction as its side effects.
 *
 * <p>Flyway 12.4.0 creates both columns on PostgreSQL 18.4 from
 * {@code src/main/resources/db/migration/V1__schema.sql}. The primary key is the only access path,
 * and no other index exists:
 *
 * <ul>
 *   <li>{@code event_id}: Structured Query Language (SQL) type {@code UUID}, {@code NOT NULL},
 *       primary key {@code pk_processed_event}, held as a Universally Unique Identifier
 *       ({@link UUID}).</li>
 *   <li>{@code processed_at}: SQL type {@code TIMESTAMP(6) WITH TIME ZONE}, {@code NOT NULL}, held
 *       as an {@link Instant}.</li>
 * </ul>
 *
 * <p>{@link Table} names no schema; the default schema arrives from configuration. That
 * configuration sets {@code spring.jpa.hibernate.ddl-auto} to {@code validate}, and the Jakarta
 * Persistence API (JPA) provider then checks this mapping against the Data Definition Language
 * (DDL) above at start-up. A column name or type mismatch stops the service.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEventEntity {

    /** Identifier of the handled event, assigned by the producing service. */
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    /** Instant at which this service finished handling the event. */
    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /**
     * No-argument constructor for the Jakarta Persistence API provider.
     *
     * <p>The provider calls it while materialising a row, then assigns both fields. Application
     * code calls {@link #ProcessedEventEntity(UUID, Instant)}.
     */
    protected ProcessedEventEntity() {
        // Both fields stay unassigned until the provider populates them.
    }

    /**
     * Builds a marker for one handled event.
     *
     * @param eventId     identifier the producing service assigned to the event
     * @param processedAt instant at which handling finished
     * @throws NullPointerException when either argument is {@code null}, naming the argument
     */
    public ProcessedEventEntity(UUID eventId, Instant processedAt) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.processedAt = Objects.requireNonNull(processedAt, "processedAt");
    }

    /**
     * Returns the identifier of the handled event.
     *
     * @return value of column {@code event_id}
     */
    public UUID getEventId() {
        return eventId;
    }

    /**
     * Returns the instant at which handling finished.
     *
     * @return value of column {@code processed_at}
     */
    public Instant getProcessedAt() {
        return processedAt;
    }

    /**
     * Compares on the event identifier, which carries the primary key. An instance whose identifier
     * is unassigned equals itself alone.
     *
     * @param other object to compare with this marker
     * @return {@code true} when {@code other} is a {@code ProcessedEventEntity} carrying an equal
     *         event identifier
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

    /**
     * Hashes the event identifier alone.
     *
     * @return hash of column {@code event_id}, and zero while that column is unassigned
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(eventId);
    }

    /**
     * Renders both columns.
     *
     * @return the event identifier and the instant handling finished
     */
    @Override
    public String toString() {
        return "ProcessedEventEntity[eventId=" + eventId + ", processedAt=" + processedAt + "]";
    }
}
