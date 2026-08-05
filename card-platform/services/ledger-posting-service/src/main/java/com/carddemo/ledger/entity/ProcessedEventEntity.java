package com.carddemo.ledger.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Marks one event identifier as already processed by the ledger posting service.
 *
 * <p>One instance maps to one row of the table {@code processed_event}. Three columns come from
 * {@code src/main/resources/db/migration/V1__schema.sql}, which is authoritative for them:
 * {@code event_id} as the primary key {@code pk_processed_event}, {@code processed_at}, and
 * {@code consumed_topic}. A retention index over {@code processed_at} accompanies them. No Common
 * Business Oriented Language (COBOL) program carries an equivalent record.</p>
 *
 * <p>The contract is fixed for whichever consumer writes the marker. One row commits inside the
 * same local transaction as the side effects of its event, marker after effects, and the delivery
 * is acknowledged only once that transaction commits. A redelivery finds the row present and does
 * nothing.</p>
 */
@Entity
@Table(name = "processed_event",
        indexes = @Index(name = "ix_processed_event_processed_at",
                columnList = "processed_at"))
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
     * No-argument constructor for the Jakarta Persistence API (JPA) provider.
     *
     * <p>The provider calls it while materialising a row, then assigns all three fields.
     * Application code calls {@link #ProcessedEventEntity(UUID, Instant)} and
     * {@link #setConsumedTopic(String)}.</p>
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

    public UUID getEventId() {
        return eventId;
    }

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
     * Renders the event identifier and the instant, and omits {@code consumedTopic}. The
     * identifier is opaque and the instant is operational, so neither names an account, an amount
     * or a cardholder.
     *
     * @return the simple class name followed by the event identifier and the processing instant
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
