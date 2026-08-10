package com.carddemo.authorization.entity;

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
 * One row per event identifier the authorization service has handled, on one topic, in table
 * {@code processed_event}.
 *
 * <p>No CardDemo copybook and no CardDemo program is the ancestor of this table. The
 * source detects no duplicate at all. {@code 2900-WRITE-TRANSACTION-FILE} at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} tests the transaction file status at L566. A duplicate key
 * fails that test and reaches {@code PERFORM 9999-ABEND-PROGRAM} at L577. That abend routine at
 * {@code app/cbl/CBTRN02C.cbl:L707-L711} holds four statements and performs no cleanup.
 *
 * <p>A consumer checks the key, then inserts the marker in the same local transaction as the
 * side effects it guards, and acknowledges only after that transaction commits. A duplicate
 * delivery finds the row present and does nothing. Apache Kafka 4.2.1 delivers at least once. A
 * restart, a consumer group rebalance, or a crash between the side effects and the offset commit
 * redelivers a message.
 *
 * <p>Two listeners write this table. {@code messaging/CardUpdatedConsumer} reads
 * {@code card.updated}, published by the card service, and
 * {@code messaging/AccountStateChangedConsumer} reads {@code account.state-changed}, published by
 * the account service on an account update, on a cycle close and on a posted amount. This service
 * reads {@code card_xref} and {@code account_credit_snapshot} on every decision, so applying one of
 * those events twice would double-count a cycle accumulator and applying one none would authorize
 * against a value its owner has already changed. {@code repository/ProcessedEventRepository} owns
 * the existence check, the insert and the retention purge.
 *
 * <p><b>The topic is part of the identity.</b> Those two listeners read two topics, and the
 * event identifiers on them are assigned independently by two different producing services. Two
 * events on two topics may therefore carry the same identifier without either producer being at
 * fault. Keyed on the identifier alone, the second of the two read a marker its own topic never
 * wrote, concluded the event was already handled, and applied nothing: the right outcome for a
 * redelivery, and a silently dropped replica row for a different event. Keyed on the identifier and
 * the topic, duplicate suppression within one topic is unchanged and the cross-topic collision stops
 * being one.
 *
 * <p>Three columns and one index, created on PostgreSQL 18.4 by
 * {@code src/main/resources/db/migration/V1__schema.sql} and re-keyed by
 * {@code src/main/resources/db/migration/V6__processed_event_topic_key.sql}, which are authoritative
 * for their definitions: {@code event_id} and {@code consumed_topic} as the primary key,
 * {@code processed_at}, and {@code ix_processed_event_processed_at} over {@code processed_at} for the
 * retention purge.
 *
 * <p>The producer assigns the event identifier while building the event envelope, the delivery
 * carries the topic in its own header, and the writer supplies the timestamp. Each of the other five
 * services owns a {@code processed_event} table in its own private schema, and no table is shared.
 *
 * <p>The {@link Table} annotation carries no {@code schema} attribute. The default schema arrives
 * from {@code spring.jpa.properties.hibernate.default_schema} in
 * {@code src/main/resources/application.yml}. That file sets
 * {@code spring.jpa.hibernate.ddl-auto} to {@code validate}. Hibernate checks this mapping against
 * the migrations above at start-up.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
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
     * {@code src/main/resources/db/migration/V6__processed_event_topic_key.sql}
     * backfills the same text into any row written while {@code consumed_topic} was still
     * nullable.
     */
    public static final String NO_CONSUMED_TOPIC = "(no topic header)";

    /** Holds the composite key: the event identifier and the topic the delivery arrived on. */
    @EmbeddedId
    private ProcessedEventId id;

    /**
     * Instant at which the authorization service finished processing the event.
     *
     * <p>Maps to {@code processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL}.</p>
     */
    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /**
     * No-argument constructor for the Jakarta Persistence provider.
     *
     * <p>The provider calls it while materialising a row, then assigns both fields. Application
     * code calls {@link #ProcessedEventEntity(UUID, Instant, String)}.</p>
     */
    protected ProcessedEventEntity() {
    }

    /**
     * Builds a marker for one processed event on one topic.
     *
     * <p>The topic is required rather than optional because it is half of the key. A caller with no
     * topic header to record passes {@link #NO_CONSUMED_TOPIC}, which states that absence rather
     * than leaving the key half unset.</p>
     *
     * @param eventId       identifier of the processed event
     * @param processedAt   instant at which processing finished
     * @param consumedTopic the topic the delivery arrived on, or {@link #NO_CONSUMED_TOPIC}
     * @throws NullPointerException     when any argument is {@code null}, naming the field
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

    /** @return the identifier of the processed event */
    public UUID getEventId() {
        return id == null ? null : id.getEventId();
    }

    /** @return the instant at which processing finished */
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
     * @param other the object to compare with this marker
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
     * Renders the key and the instant. The event identifier is opaque, the topic is configuration
     * and the instant is operational, so no field names an account, an amount or a cardholder.
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
     * <p>{@code src/main/resources/db/migration/V6__processed_event_topic_key.sql}
     * is authoritative for both columns and carries the reasoning at length.</p>
     */
    @Embeddable
    public static class ProcessedEventId implements Serializable {

        /** Serialization identity of this key. */
        private static final long serialVersionUID = 1L;

        /**
         * Identifier of the processed event, assigned by the service that published it.
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

        /** @return the identifier of the processed event */
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
         * @param other the object to compare with this key
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
