package com.carddemo.card.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Marks one event identifier the card service has already handled, as one row of table
 * {@code processed_event}.
 *
 * <p>No COBOL ancestor: no Common Business Oriented Language (COBOL) copybook and no COBOL program
 * declares this record, and the source detects no duplicate anywhere. Paragraph {@code
 * 2900-WRITE-TRANSACTION-FILE} at {@code app/cbl/CBTRN02C.cbl:L562-L579} writes each posted
 * transaction and tests {@code IF TRANFILE-STATUS = '00'} at L566. A duplicate key fails that test
 * and reaches {@code PERFORM 9999-ABEND-PROGRAM} at L577, whose routine at L707-L711 holds four
 * statements and cleans nothing up. Each of the eight file definitions in {@code
 * app/csd/CARDDEMO.CSD} carries {@code RECOVERY(NONE)}.
 *
 * <p>This service consumes no event today, and that is a measured fact rather than an omission:
 * {@code card-platform/.env.example} declares no card-service consumer group. The table is declared
 * because every service of this platform declares the same
 * marker with the same three columns, so a consumer added to this service inherits the contract
 * unchanged rather than inventing one.
 *
 * <p>That contract is fixed. A consumer commits one row inside the same local transaction as its
 * side effects, marker after effects, and acknowledges the delivery only once that transaction
 * commits. A redelivery finds the row present and does nothing, and the {@code repository} package
 * owns that lookup by primary key.
 *
 * <p>Three columns come from {@code src/main/resources/db/migration/V1__schema.sql}, which is
 * authoritative for them: {@code event_id} as the primary key, {@code processed_at}, and
 * {@code consumed_topic}. A retention index over {@code processed_at} accompanies them. The
 * {@link Table} annotation names no schema, and {@code src/main/resources/application.yml} supplies
 * one and sets {@code ddl-auto: validate}, so Hibernate checks this mapping against that migration
 * at start-up.
 */
@Entity
@Table(name = "processed_event",
        indexes = @Index(name = "ix_processed_event_processed_at",
                columnList = "processed_at"))
public class ProcessedEventEntity {

    /** Event identifier the producing service assigned, a Universally Unique Identifier (UUID). */
    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    /** Instant at which the caller finished handling the event. */
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

    /** No-argument constructor the persistence provider calls before assigning all three fields. */
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

    public UUID getEventId() {
        return eventId;
    }

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

    /**
     * Renders the event identifier and the instant, and omits {@code consumedTopic}. The identifier
     * is opaque and the instant is operational, so neither names an account, an amount or a
     * cardholder.
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
