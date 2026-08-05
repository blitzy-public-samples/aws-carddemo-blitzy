package com.carddemo.fraud.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row per event identifier this service has handled, in table {@code processed_event}.
 *
 * <p>No Common Business Oriented Language (COBOL) ancestor. Searching all 28 COBOL programs in
 * {@code app/cbl/} for {@code fraud}, {@code risk}, {@code velocit}, {@code scoring} and
 * {@code luhn} matches none.
 *
 * <p>The CardDemo source detects no duplicate: {@code app/cbl/CBTRN02C.cbl:L564} writes the
 * transaction file, and a failing status test at L566 reaches {@code 9999-ABEND-PROGRAM} at
 * L707-L711, four statements ending in {@code CALL 'CEE3ABD'}. A replayed feed hits a duplicate key
 * and abends.
 *
 * <p>{@code messaging.TransactionAuthorizedConsumer} reads this table before it acts, then inserts
 * the marker inside the same local transaction as its side effects, marker after effects, and
 * acknowledges the delivery only once that transaction commits. A redelivery finds the row present
 * and does nothing.
 *
 * <p>Three columns come from {@code src/main/resources/db/migration/V1__schema.sql}, which Flyway
 * 12.4.0 applies to PostgreSQL 18.4 and which is authoritative for them: {@code event_id} as the
 * primary key {@code pk_processed_event}, {@code processed_at}, and {@code consumed_topic}. A
 * retention index over {@code processed_at} accompanies them.
 *
 * <p>{@link Table} names no schema; the default schema arrives from configuration. That
 * configuration sets {@code spring.jpa.hibernate.ddl-auto} to {@code validate}, and the Jakarta
 * Persistence API (JPA) provider then checks this mapping against that migration at start-up. A
 * column name or type mismatch stops the service.
 */
@Entity
@Table(name = "processed_event",
        indexes = @Index(name = "ix_processed_event_processed_at",
                columnList = "processed_at"))
public class ProcessedEventEntity {

    /** Identifier of the handled event, assigned by the producing service. */
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    /** Instant at which this service finished handling the event. */
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
     * <p>A marker on its own says an event was handled and nothing about where it came from,
     * which is not enough to investigate a replay. The same identifier can be redelivered on the
     * topic it came from, or arrive on a dead-letter topic during a recovery. Those are different
     * situations, and recording the topic separates them.
     */
    @Column(name = "consumed_topic", length = CONSUMED_TOPIC_MAX_LENGTH)
    private String consumedTopic;

    /**
     * No-argument constructor for the Jakarta Persistence API provider.
     *
     * <p>The provider calls it while materialising a row, then assigns all three fields.
     * Application code calls {@link #ProcessedEventEntity(UUID, Instant)} and
     * {@link #setConsumedTopic(String)}.
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

    public UUID getEventId() {
        return eventId;
    }

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
     * Renders the event identifier and the instant, and omits {@code consumedTopic}. The identifier
     * is opaque and the instant is operational, so neither names an account, an amount or a
     * cardholder.
     *
     * @return the event identifier and the instant handling finished
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
