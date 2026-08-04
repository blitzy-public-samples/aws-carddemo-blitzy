package com.carddemo.authorization.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row per event identifier the authorization service has handled, in table
 * {@code processed_event}.
 *
 * <p>ADDITIVE: no CardDemo copybook and no CardDemo program is the ancestor of this table. The
 * source detects no duplicate at all. {@code 2900-WRITE-TRANSACTION-FILE} at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} tests the transaction file status at L566. A duplicate key
 * fails that test and reaches {@code PERFORM 9999-ABEND-PROGRAM} at L577. That abend routine at
 * {@code app/cbl/CBTRN02C.cbl:L707-L711} holds four statements and performs no cleanup.
 *
 * <p>A consumer checks the identifier, then inserts the marker in the same local transaction as the
 * side effects it guards, and acknowledges only after that transaction commits. A duplicate
 * delivery finds the row present and does nothing. Apache Kafka 4.2.1 delivers at least once. A
 * restart, a consumer group rebalance, or a crash between the side effects and the offset commit
 * redelivers a message.
 *
 * <p>The concrete incoming event this marker guards is {@code AccountStateChanged}, which the
 * account service publishes on an account update and on a cycle close. The authorization service
 * reads {@code account_credit_snapshot} on every decision, and that projection is only current while
 * something applies those events to it. Applying one twice would double-count a cycle accumulator,
 * so the consumer records the event identifier here in the same local transaction as the projection
 * row it changes. {@code repository/ProcessedEventRepository} owns the existence check, the insert
 * and the retention purge.
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
 */
@Entity
@Table(name = "processed_event",
        indexes = @Index(name = "ix_processed_event_processed_at",
                columnList = "processed_at"))
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
     * Widest value {@code consumed_topic} holds, from {@code consumed_topic VARCHAR(128)} in
     * {@code src/main/resources/db/migration/V1__schema.sql}.
     */
    public static final int CONSUMED_TOPIC_MAX_LENGTH = 128;

    /**
     * Which topic the delivery that first handled this event arrived on, or null when the
     * marker was written without one.
     *
     * <p>A marker on its own says an event was handled and nothing about where it came from, which
     * is not enough to investigate a replay: the same identifier can be redelivered on the topic it
     * came from or arrive on a dead-letter topic during a recovery, and those are different
     * situations. Recording the topic separates them.
     */
    @Column(name = "consumed_topic", length = CONSUMED_TOPIC_MAX_LENGTH)
    private String consumedTopic;

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

    public UUID getEventId() {
        return eventId;
    }

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

    /**
     * Returns which topic the delivery that first handled this event arrived on.
     *
     * @return the topic name, or null when the marker carries none
     */
    public String getConsumedTopic() {
        return consumedTopic;
    }

    /**
     * Records which topic the delivery that first handled this event arrived on.
     *
     * <p>A value longer than {@value #CONSUMED_TOPIC_MAX_LENGTH} characters is refused rather than
     * truncated, because a truncated topic name names a topic that does not exist and is worse than
     * none.
     *
     * @param consumedTopic the topic name, or null to record none
     * @throws IllegalArgumentException if {@code consumedTopic} is blank or too long
     */
    public void setConsumedTopic(String consumedTopic) {
        if (consumedTopic != null) {
            if (consumedTopic.isBlank()) {
                throw new IllegalArgumentException("consumedTopic is blank");
            }
            if (consumedTopic.length() > CONSUMED_TOPIC_MAX_LENGTH) {
                throw new IllegalArgumentException("consumedTopic is " + consumedTopic.length()
                        + " characters, over the " + CONSUMED_TOPIC_MAX_LENGTH
                        + " its column holds");
            }
        }
        this.consumedTopic = consumedTopic;
    }
}
