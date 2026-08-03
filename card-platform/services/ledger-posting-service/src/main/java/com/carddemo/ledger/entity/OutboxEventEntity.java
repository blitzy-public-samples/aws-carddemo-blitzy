package com.carddemo.ledger.entity;

import com.carddemo.cobol.PicClause;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One pending domain event of the ledger posting service, held in the table {@code outbox_event}.
 *
 * <p>A caller writes one row in the same local transaction as the domain write that produced the
 * event. {@link #getPayload()} returns that event as one JavaScript Object Notation (JSON)
 * document, and {@link #getAggregateId()} returns the account identifier that keys it.</p>
 *
 * <p>ADDITIVE. No COBOL (Common Business Oriented Language) record corresponds to this table.</p>
 *
 * <p>Rationale for this entity: {@code card-platform/docs/decision-log.md}.</p>
 */
@Entity
@Table(
    name = "outbox_event",
    indexes = @Index(name = "idx_outbox_event_unpublished", columnList = "published, created_at")
)
public class OutboxEventEntity {

    /** Widest {@code event_type} this table holds. */
    private static final int EVENT_TYPE_MAX_LENGTH = 50;

    /** Widest {@code payload} this table holds. */
    private static final int PAYLOAD_MAX_LENGTH = 4000;

    /**
     * Form of every accepted {@code aggregate_id}: eleven digits, leading zeros kept. The width
     * comes from {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}, and record 1 of
     * {@code app/data/ASCII/acctdata.txt} holds {@code 00000000001}.
     */
    private static final Pattern ACCOUNT_ID_PATTERN = Pattern.compile("^[0-9]{11}$");

    /** Identifier of the event, assigned by the caller. */
    @Id
    private UUID eventId;

    /** Name of the event type the payload carries. */
    @Column(name = "event_type", nullable = false, length = EVENT_TYPE_MAX_LENGTH)
    private String eventType;

    /** Account identifier, and the message key the relay publishes this event under. */
    @Column(name = "aggregate_id", nullable = false, length = PicClause.ACCT_ID_WIDTH)
    private String aggregateId;

    /** The whole event document. */
    @Column(name = "payload", nullable = false, length = PAYLOAD_MAX_LENGTH)
    private String payload;

    /** Whether the relay has published this row. */
    @Column(name = "published", nullable = false)
    private boolean published;

    /** Time the caller recorded the event. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by the persistence provider. */
    protected OutboxEventEntity() {
    }

    /**
     * Builds one unpublished row.
     *
     * @param eventId     identifier of the event
     * @param eventType   name of the event type the payload carries
     * @param aggregateId account identifier, eleven digits
     * @param payload     the whole event document
     * @param createdAt   time the caller recorded the event
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code aggregateId} is not eleven digits
     */
    public OutboxEventEntity(UUID eventId, String eventType, String aggregateId, String payload,
            Instant createdAt) {
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.aggregateId = requireAccountIdFormat(
                Objects.requireNonNull(aggregateId, "aggregateId must not be null"));
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.published = false;
    }

    /**
     * Accepts an eleven-digit account identifier and rejects every other value. The failure names
     * the field and omits the value.
     *
     * @param aggregateId the candidate account identifier
     * @return the same value, once it matches {@link #ACCOUNT_ID_PATTERN}
     * @throws IllegalArgumentException if the value is not eleven digits
     */
    private static String requireAccountIdFormat(String aggregateId) {
        if (!ACCOUNT_ID_PATTERN.matcher(aggregateId).matches()) {
            throw new IllegalArgumentException("aggregateId must be eleven digits");
        }
        return aggregateId;
    }

    /**
     * Returns the identifier the caller assigned to this event.
     *
     * @return identifier of the event
     */
    public UUID getEventId() {
        return eventId;
    }

    /**
     * Returns the event type, which a consumer reads to route the payload.
     *
     * @return name of the event type the payload carries
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * Returns the aggregate this event belongs to, eleven digits with leading zeros kept.
     *
     * @return account identifier, and the message key the relay publishes this event under
     */
    public String getAggregateId() {
        return aggregateId;
    }

    /**
     * Returns the event text this row carries, unparsed.
     *
     * @return the whole event document
     */
    public String getPayload() {
        return payload;
    }

    /**
     * Reports whether the relay has already published this row.
     *
     * @return {@code true} once {@link #markPublished()} has run
     */
    public boolean isPublished() {
        return published;
    }

    /**
     * Returns the creation time the caller supplied, which the relay orders its sweep by.
     *
     * @return time the caller recorded the event
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Records that the relay published this row. Calling this method a second time leaves the row
     * as it stands.
     */
    public void markPublished() {
        this.published = true;
    }

    /**
     * Compares two rows on {@link #getEventId()} alone.
     *
     * @param other the object to compare against
     * @return {@code true} when {@code other} is an outbox row carrying the same event identifier
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OutboxEventEntity that)) {
            return false;
        }
        return Objects.equals(eventId, that.eventId);
    }

    /**
     * Hashes this row on {@link #getEventId()} alone, matching {@link #equals(Object)}.
     *
     * @return a hash of the event identifier, and zero while that identifier is unset
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(eventId);
    }

    /**
     * Describes this row. The payload stays out of the text.
     *
     * @return the event identifier, the type, the aggregate identifier, the published flag and the
     *         creation time
     */
    @Override
    public String toString() {
        return "OutboxEventEntity{eventId=" + eventId
                + ", eventType=" + eventType
                + ", aggregateId=" + aggregateId
                + ", published=" + published
                + ", createdAt=" + createdAt
                + '}';
    }
}
