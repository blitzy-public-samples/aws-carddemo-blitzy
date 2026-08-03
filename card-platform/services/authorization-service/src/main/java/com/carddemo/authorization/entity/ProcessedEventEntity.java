package com.carddemo.authorization.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row per event identifier the authorization service has already handled, in table
 * {@code processed_event}.
 *
 * <p>ADDITIVE: no CardDemo copybook and no CardDemo program is the ancestor of this table. The
 * source detects no duplicate at all. {@code 2900-WRITE-TRANSACTION-FILE} at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} tests the transaction file status at L566. A duplicate key
 * fails that test and reaches {@code PERFORM 9999-ABEND-PROGRAM} at L577. That abend routine at
 * {@code app/cbl/CBTRN02C.cbl:L707-L711} holds four statements and performs no cleanup.
 *
 * <p>A writer inserts the marker in the same local transaction as the effect it guards. A duplicate
 * delivery finds the row present and does nothing. Apache Kafka 4.2.1 delivers at least once. A
 * restart, a consumer group rebalance, or a crash between the side effects and the offset commit
 * redelivers a message.
 *
 * <p>This service registers no listener and publishes two topics. The marker covers a replayed
 * authorization request and the outbox relay's restart path.
 *
 * <p>Two columns, both {@code NOT NULL}, created on PostgreSQL 18.4 by
 * {@code src/main/resources/db/migration/V1__schema.sql:L80-L84}:
 *
 * <pre>
 *   event_id      UUID                        PRIMARY KEY
 *   processed_at  TIMESTAMP(6) WITH TIME ZONE
 * </pre>
 *
 * <p>The producer assigns the event identifier while building the event envelope, and the writer
 * supplies the timestamp. Each of the other five services owns a {@code processed_event} table in
 * its own private schema, and no table is shared.
 *
 * <p>The {@link Table} annotation carries no {@code schema} attribute. The default schema arrives
 * from {@code spring.jpa.properties.hibernate.default_schema} in
 * {@code src/main/resources/application.yml}. That file sets
 * {@code spring.jpa.hibernate.ddl-auto} to {@code validate}. Hibernate checks this mapping against
 * the migration above at start-up.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEventEntity {

    /** Event identifier and primary key, from column {@code event_id UUID NOT NULL}. */
    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    /**
     * Instant this marker was written, from column
     * {@code processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL}.
     */
    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /**
     * No-argument constructor the persistence provider calls. Hibernate assigns both fields through
     * reflection after calling it.
     */
    protected ProcessedEventEntity() {
        // Both fields stay unassigned until the persistence provider populates them.
    }

    /**
     * Builds a marker for one handled event.
     *
     * @param eventId     identifier the producer assigned to the event; never {@code null}
     * @param processedAt instant the caller finished handling the event; never {@code null}
     * @throws NullPointerException when either argument is {@code null}
     */
    public ProcessedEventEntity(UUID eventId, Instant processedAt) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.processedAt = Objects.requireNonNull(processedAt, "processedAt");
    }

    /**
     * Returns the event identifier this marker records.
     *
     * @return value of column {@code event_id}
     */
    public UUID getEventId() {
        return eventId;
    }

    /**
     * Returns the instant this marker was written.
     *
     * @return value of column {@code processed_at}
     */
    public Instant getProcessedAt() {
        return processedAt;
    }

    /**
     * Compares on the event identifier, which is the primary key. An instance whose identifier the
     * persistence provider has not yet assigned equals itself alone.
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
     * Renders both columns. Neither one carries a Primary Account Number (PAN).
     *
     * @return the event identifier and the timestamp
     */
    @Override
    public String toString() {
        return "ProcessedEventEntity[eventId=" + eventId + ", processedAt=" + processedAt + "]";
    }
}
