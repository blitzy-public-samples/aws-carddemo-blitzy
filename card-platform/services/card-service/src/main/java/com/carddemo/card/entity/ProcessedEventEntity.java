package com.carddemo.card.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Marks one event identifier the card service has already handled, on one topic, as one row of
 * table {@code processed_event}.
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
 * because every service of this platform declares the same marker with the same columns and the
 * same key, so a consumer added to this service inherits the contract unchanged rather than
 * inventing one.
 *
 * <p>That contract is fixed. A consumer commits one row inside the same local transaction as its
 * side effects, marker after effects, and acknowledges the delivery only once that transaction
 * commits. A redelivery finds the row present and does nothing, and the {@code repository} package
 * owns that lookup by primary key.
 *
 * <p><b>The topic is part of the identity.</b> Keyed on the identifier alone, this marker
 * asserts that one identifier is handled once by the whole service, and that assertion is false the
 * moment a service reads two topics: two producing services assign identifiers independently, so two
 * different events may carry one identifier without either producer being at fault. The second of
 * the two would lose its claim to the first and its listener would write nothing, which is right for
 * a redelivery and silently wrong for a different event. Keyed on the identifier and the topic
 * together, duplicate suppression within one topic is unchanged and the cross-topic collision stops
 * being one. Inheriting the narrow key would have reintroduced that defect on the day a second
 * listener was added here, and reintroduced it in silence.
 *
 * <p>Columns come from {@code src/main/resources/db/migration/V1__schema.sql} and
 * {@code src/main/resources/db/migration/V3__processed_event_topic_key.sql}, which are authoritative
 * for them: {@code event_id} and {@code consumed_topic} together carry the primary key
 * {@code pk_processed_event}, and {@code processed_at} carries the retention index. The
 * {@link Table} annotation names no schema, and {@code src/main/resources/application.yml} supplies
 * one and sets {@code ddl-auto: validate}, so Hibernate checks this mapping against those migrations
 * at start-up.
 */
@Entity
@Table(name = "processed_event",
        indexes = @Index(name = "ix_processed_event_processed_at",
                columnList = "processed_at"))
public class ProcessedEventEntity {

    /**
     * Widest value {@code consumed_topic} holds, from {@code consumed_topic VARCHAR(128)} in
     * {@code src/main/resources/db/migration/V1__schema.sql}.
     */
    public static final int CONSUMED_TOPIC_MAX_LENGTH = 128;

    /**
     * Topic recorded for a delivery that carried no topic header.
     *
     * <p>A key column cannot be null, so a delivery with no topic to record takes this text instead.
     * It holds spaces and parentheses and a Kafka topic name holds only {@code [a-zA-Z0-9._-]}, so
     * it can never collide with a real topic name.
     * {@code src/main/resources/db/migration/V3__processed_event_topic_key.sql} backfills the same
     * text into any row written while {@code consumed_topic} was still nullable.
     */
    public static final String NO_CONSUMED_TOPIC = "(no topic header)";

    /** Holds the composite key: the event identifier and the topic the delivery arrived on. */
    @EmbeddedId
    private ProcessedEventId id;

    /** Instant at which the caller finished handling the event. */
    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /** No-argument constructor the persistence provider calls before assigning both fields. */
    protected ProcessedEventEntity() {
    }

    /**
     * Builds a marker for one handled event on one topic.
     *
     * <p>The topic is required rather than optional because it is half of the key. A caller with no
     * topic header to record passes {@link #NO_CONSUMED_TOPIC}, which states that absence rather
     * than leaving the key half unset.
     *
     * @param eventId       identifier the producing service assigned to the event
     * @param processedAt   instant at which the caller finished handling the event
     * @param consumedTopic the topic the delivery arrived on, or {@link #NO_CONSUMED_TOPIC}
     * @throws NullPointerException     when any argument is {@code null}, naming the argument
     * @throws IllegalArgumentException when {@code consumedTopic} is blank or too long
     */
    public ProcessedEventEntity(UUID eventId, Instant processedAt, String consumedTopic) {
        this.id = new ProcessedEventId(eventId, consumedTopic);
        this.processedAt = Objects.requireNonNull(processedAt, "processedAt");
    }

    /** @return the composite key, never {@code null} for a constructed marker */
    public ProcessedEventId getId() {
        return id;
    }

    /** @return the identifier of the handled event */
    public UUID getEventId() {
        return id == null ? null : id.getEventId();
    }

    /** @return the instant at which handling finished */
    public Instant getProcessedAt() {
        return processedAt;
    }

    /**
     * Returns which topic the delivery that handled this event arrived on.
     *
     * @return the topic name, or {@link #NO_CONSUMED_TOPIC} when the delivery carried no header
     */
    public String getConsumedTopic() {
        return id == null ? null : id.getConsumedTopic();
    }

    /**
     * Compares two markers by their key, which is the event identifier and the topic together.
     *
     * @param other object to compare with this marker
     * @return {@code true} when {@code other} is a marker carrying an equal key
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProcessedEventEntity that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    /**
     * Derives the hash code from the key alone, matching {@link #equals(Object)}.
     *
     * @return the hash code of the key, and zero while the key is unset
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /**
     * Renders the key and the instant. The identifier is opaque, the topic is configuration and the
     * instant is operational, so no field names an account, an amount or a cardholder.
     *
     * @return the simple class name followed by the key parts and the processing instant
     */
    @Override
    public String toString() {
        return "ProcessedEventEntity[eventId=" + getEventId()
                + ", consumedTopic=" + getConsumedTopic()
                + ", processedAt=" + processedAt + "]";
    }

    /**
     * The composite key: the event identifier then the topic the delivery arrived on.
     *
     * <p>The identifier half comes from {@code EventEnvelope.eventId} of the consumed event. The
     * topic half comes from the {@code RECEIVED_TOPIC} header of the delivery itself, so a listener
     * records what it observed rather than what it was configured as; a key built from a consumer
     * group name would change identity whenever a group was renamed.</p>
     *
     * <p>{@code src/main/resources/db/migration/V3__processed_event_topic_key.sql} is authoritative
     * for both columns and carries the reasoning at length.</p>
     */
    @Embeddable
    public static class ProcessedEventId implements Serializable {

        /** Serialization identity of this key. */
        private static final long serialVersionUID = 1L;

        /**
         * Event identifier the producing service assigned, a Universally Unique Identifier (UUID).
         *
         * <p>Maps to {@code event_id UUID NOT NULL}.</p>
         */
        @Column(name = "event_id", nullable = false, updatable = false)
        private UUID eventId;

        /**
         * Which topic the delivery that handled this event arrived on.
         *
         * <p>Maps to {@code consumed_topic VARCHAR(128) NOT NULL}. A marker naming only the event
         * says it was handled and nothing about where it came from, which is not enough to
         * investigate a replay: the same identifier can be redelivered on the topic it came from, or
         * arrive on a dead-letter topic during a recovery, and those are different situations.</p>
         */
        @Column(name = "consumed_topic", nullable = false, updatable = false,
                length = CONSUMED_TOPIC_MAX_LENGTH)
        private String consumedTopic;

        /** Required by the persistence provider. */
        protected ProcessedEventId() {
        }

        /**
         * Takes both key parts.
         *
         * <p>A blank topic is refused rather than accepted, and a topic longer than
         * {@value ProcessedEventEntity#CONSUMED_TOPIC_MAX_LENGTH} characters is refused rather than
         * truncated, because a truncated topic name names a topic that does not exist and would key
         * a row nothing can find again.</p>
         *
         * @param eventId       the event identifier
         * @param consumedTopic the topic the delivery arrived on, or
         *                      {@link ProcessedEventEntity#NO_CONSUMED_TOPIC}
         * @throws NullPointerException     when an argument is {@code null}
         * @throws IllegalArgumentException when {@code consumedTopic} is blank or too long
         */
        public ProcessedEventId(UUID eventId, String consumedTopic) {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(consumedTopic, "consumedTopic");
            if (consumedTopic.isBlank()) {
                throw new IllegalArgumentException("consumedTopic is blank");
            }
            if (consumedTopic.length() > CONSUMED_TOPIC_MAX_LENGTH) {
                throw new IllegalArgumentException("consumedTopic is " + consumedTopic.length()
                        + " characters, over the " + CONSUMED_TOPIC_MAX_LENGTH
                        + " its column holds");
            }
            this.eventId = eventId;
            this.consumedTopic = consumedTopic;
        }

        /** @return the identifier of the handled event */
        public UUID getEventId() {
            return eventId;
        }

        /** @return the topic the delivery arrived on */
        public String getConsumedTopic() {
            return consumedTopic;
        }

        /**
         * Compares both key parts.
         *
         * @param other object to compare with this key
         * @return {@code true} when {@code other} is a key with an equal identifier and topic
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ProcessedEventId that)) {
                return false;
            }
            return Objects.equals(eventId, that.eventId)
                    && Objects.equals(consumedTopic, that.consumedTopic);
        }

        /**
         * Derives the hash code from both key parts, matching {@link #equals(Object)}.
         *
         * @return the combined hash code
         */
        @Override
        public int hashCode() {
            return Objects.hash(eventId, consumedTopic);
        }

        /**
         * Renders both key parts. The identifier is opaque and the topic is configuration, so
         * neither names an account, an amount or a cardholder.
         *
         * @return the simple class name followed by the identifier and the topic
         */
        @Override
        public String toString() {
            return "ProcessedEventId[eventId=" + eventId
                    + ", consumedTopic=" + consumedTopic + "]";
        }
    }
}
