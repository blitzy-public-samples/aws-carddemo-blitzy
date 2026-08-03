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
 * One row of the table {@code outbox_event} in this service's private schema, holding one event
 * awaiting publication to the topic {@code fraud.assessed}.
 *
 * <p>ADDITIVE IN FULL: net new; no COBOL ancestor. Searching {@code app/cbl/} for {@code fraud},
 * {@code velocit}, {@code risk}, {@code scoring} and {@code luhn} matches zero of its 28 programs.
 * The width of {@code aggregate_id} comes from {@code XREF-ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT03Y.cpy:L7}, shape only; no other column here has a source counterpart.
 *
 * <p>Flyway creates the table from {@code src/main/resources/db/migration/V1__schema.sql}.
 * Hibernate validates this mapping against that schema and writes no schema object, so a column
 * name or type differing from the contract below stops start-up.
 *
 * <pre>
 * event_id     UUID                        NOT NULL, primary key pk_outbox_event
 * event_type   VARCHAR(32)                 NOT NULL
 * aggregate_id CHAR(11)                    NOT NULL
 * payload      TEXT                        NOT NULL
 * published    BOOLEAN                     NOT NULL DEFAULT FALSE
 * created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL
 * published_at TIMESTAMP(6) WITH TIME ZONE
 * index        ix_outbox_event_unpublished (published, created_at)
 * </pre>
 *
 * <p>The relay reads unpublished rows in {@code created_at} order, publishes each one, then calls
 * {@link #setPublished(boolean)} and {@link #setPublishedAt(Instant)}.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Entity
@Table(name = "outbox_event",
        indexes = @Index(name = "ix_outbox_event_unpublished",
                columnList = "published, created_at"))
public class OutboxEventEntity {

    /**
     * The event identifier the writer assigned, a Universally Unique Identifier (UUID), and the
     * value each consumer deduplicates on.
     */
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    /**
     * The routing discriminator, exactly {@code FraudFlagged} or {@code FraudCleared}. Both event
     * types travel on the topic {@code fraud.assessed}.
     */
    @Column(name = "event_type", length = 32, nullable = false, updatable = false)
    private String eventType;

    /**
     * The account identifier, eleven decimal digits, and always the Kafka message key. PostgreSQL
     * reports the fixed-width type of this column through Java Database Connectivity (JDBC)
     * metadata as {@code bpchar}, and {@link Column#columnDefinition()} names that type verbatim.
     */
    @Column(name = "aggregate_id", length = 11, nullable = false, updatable = false,
            columnDefinition = "bpchar(11)")
    private String aggregateId;

    /**
     * One event serialized as one flat JavaScript Object Notation (JSON) object. The five envelope
     * properties {@code eventId}, {@code eventType}, {@code schemaVersion}, {@code occurredAt} and
     * {@code aggregateId} sit at the top level beside the payload properties. The writer serializes
     * the document and this row stores that text unchanged.
     */
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    /** False on insert, and true once the relay has published this row. */
    @Column(name = "published", nullable = false)
    private boolean published;

    /** The time the writer inserted this row, and the column the relay orders its batch by. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The time the relay published this row, and null until it does. */
    @Column(name = "published_at")
    private Instant publishedAt;

    /**
     * Required by the persistence provider. Application code calls
     * {@link #OutboxEventEntity(UUID, String, String, String, Instant)}.
     */
    protected OutboxEventEntity() {
        // The provider assigns every field from the row it read.
    }

    /**
     * Builds one unpublished row.
     *
     * @param eventId     the event identifier the writer assigned
     * @param eventType   the event type, not blank and at most thirty-two characters
     * @param aggregateId the account identifier, exactly eleven decimal digits with leading zeros
     *                    kept
     * @param payload     one serialized event, not blank
     * @param createdAt   the time this row was written
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code eventType} or {@code payload} is blank, if
     *                                  {@code eventType} is longer than its column, or if
     *                                  {@code aggregateId} is not eleven decimal digits
     */
    public OutboxEventEntity(UUID eventId, String eventType, String aggregateId, String payload,
            Instant createdAt) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.eventType = requireEventType(eventType);
        this.aggregateId = requireAggregateId(aggregateId);
        this.payload = requireText(payload, "payload");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.published = false;
        this.publishedAt = null;
    }

    /**
     * Checks one text argument and returns it. No failure message carries the argument value.
     *
     * @param value     the argument to check
     * @param fieldName the field name, which names the field in any failure message
     * @return {@code value}
     */
    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        return value;
    }

    /**
     * Checks the event type against the width of {@code event_type} and returns it.
     *
     * @param value the argument to check
     * @return {@code value}
     */
    private static String requireEventType(String value) {
        requireText(value, "eventType");
        if (value.length() > 32) {
            throw new IllegalArgumentException("eventType is " + value.length()
                    + " characters, over the 32 its column holds");
        }
        return value;
    }

    /**
     * Checks the account identifier against the width and digits of {@code aggregate_id} and
     * returns it. The failure message names the field and omits the value.
     *
     * @param value the argument to check
     * @return {@code value}
     */
    private static String requireAggregateId(String value) {
        Objects.requireNonNull(value, "aggregateId");
        boolean elevenDigits = value.length() == 11
                && value.chars().allMatch(digit -> digit >= '0' && digit <= '9');
        if (!elevenDigits) {
            throw new IllegalArgumentException("aggregateId is not 11 decimal digits");
        }
        return value;
    }

    /**
     * Returns the event identifier the writer assigned.
     *
     * @return the value each consumer deduplicates on
     */
    public UUID getEventId() {
        return eventId;
    }

    /**
     * Returns the routing discriminator.
     *
     * @return {@code FraudFlagged} or {@code FraudCleared}
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * Returns the account identifier, eleven digits with leading zeros kept.
     *
     * @return the Kafka message key the relay publishes this row under
     */
    public String getAggregateId() {
        return aggregateId;
    }

    /**
     * Returns the serialized event this row carries.
     *
     * @return one flat JSON object, envelope properties beside payload properties
     */
    public String getPayload() {
        return payload;
    }

    /**
     * Reports whether the relay has published this row.
     *
     * @return {@code false} until the relay publishes it
     */
    public boolean isPublished() {
        return published;
    }

    /**
     * Returns the time the writer inserted this row.
     *
     * @return the creation time the caller supplied
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Returns the time the relay published this row.
     *
     * @return the publication time, or {@code null} while the row is unpublished
     */
    public Instant getPublishedAt() {
        return publishedAt;
    }

    /**
     * Records whether the relay has published this row.
     *
     * @param published {@code true} once one publish has succeeded
     */
    public void setPublished(boolean published) {
        this.published = published;
    }

    /**
     * Records the time the relay published this row.
     *
     * @param publishedAt the publication time, or {@code null} while the row is unpublished
     */
    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    /**
     * Compares on {@link #getEventId()} alone.
     *
     * @param other the object to compare with
     * @return {@code true} if {@code other} is an {@code OutboxEventEntity} carrying an equal event
     *         identifier
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OutboxEventEntity that)) {
            return false;
        }
        return Objects.equals(this.eventId, that.eventId);
    }

    /**
     * Hashes {@link #getEventId()} alone.
     *
     * @return the hash of the event identifier, and zero while that identifier is unset
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(eventId);
    }

    /**
     * Describes this row by its identifier, its type, its key and its publication flag. The text
     * omits {@link #getPayload()}.
     *
     * @return one line naming four fields
     */
    @Override
    public String toString() {
        return "OutboxEventEntity{eventId=" + eventId
                + ", eventType=" + eventType
                + ", aggregateId=" + aggregateId
                + ", published=" + published
                + "}";
    }
}
