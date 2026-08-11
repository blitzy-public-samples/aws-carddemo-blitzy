package com.carddemo.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Jakarta Persistence entity for the {@code processed_event} table: the identifier of one consumed
 * event, the topic it arrived on, and the time a consumer processed it.
 *
 * <p>No COBOL ancestor. No copybook and no program declares this record, and the
 * source application detects no duplicate anywhere. {@code app/cbl/CBTRN02C.cbl:L562-L579} writes
 * each posted transaction with no duplicate check and routes every file status other than
 * {@code '00'} to {@code 9999-ABEND-PROGRAM}.</p>
 *
 * <p>{@code messaging/TransactionPostedConsumer} claims the key here before it applies a posted
 * amount to the account row, inside the same local transaction as that amount and the
 * {@code AccountStateChanged} event it publishes. It acknowledges the delivery only once that
 * transaction commits, so a redelivery finds the row present and applies nothing. That arithmetic is
 * {@code app/cbl/CBTRN02C.cbl:L545-L560}, which the source runs once per record of a nightly file
 * and this service runs once per event.</p>
 *
 * <p><b>The topic is part of the identity.</b> This service reads one topic today, so no
 * delivery here can currently collide with another topic's. The key names the topic anyway, because
 * an event identifier is assigned by the service that publishes the event and different producing
 * services assign them independently: the identifier alone stops identifying a delivery the moment a
 * second listener is added, and it stops silently. This schema is the clearest evidence that the
 * narrow key is a trap rather than a theoretical one, because it declared this table with no listener
 * at all and then acquired its first one. An amount dropped that way is dropped from the cycle
 * accumulators the credit-limit rule authorizes against.</p>
 *
 * <p>Three columns carry the marker: {@code event_id}, a Universally Unique Identifier (UUID),
 * {@code consumed_topic}, the topic the delivery arrived on, and {@code processed_at}, the moment a
 * consumer's side effects committed. The first two together are the primary key, and the third is
 * evidence of when the claim was made rather than a purge key: a marker guards a balance this service
 * never expires, so {@code V11__processed_event_claims_are_permanent.sql} withdrew the horizon a
 * security review found too short and dropped the index that served it. {@code V1__schema.sql} is
 * authoritative for the columns and {@code V6__processed_event_topic_key.sql} for the key. A second
 * insert of one identifier on one topic violates that key, and
 * {@code repository/ProcessedEventRepository} owns the claim and the existence check. Configuration
 * supplies the schema name, and the table annotation names none.</p>
 */
@Entity
@Table(name = "processed_event")
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
     * Instant at which a consumer finished processing the event.
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
