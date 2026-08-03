package com.carddemo.account.entity;

import com.carddemo.cobol.PicClause;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One event row in the {@code outbox_event} table.
 *
 * <p>ADDITIVE. This entity has no COBOL ancestor. No copybook and no program in the CardDemo
 * source declares an outbox record. The locators below are references, not ancestors.
 *
 * <p>The source carries one asynchronous handoff, {@code EXEC CICS WRITEQ TD} with
 * {@code QUEUE ('JOBS')} at {@code app/cbl/CORPT00C.cbl:L517-L518}. All eight file definitions in
 * {@code app/csd/CARDDEMO.CSD} specify {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}, first at
 * {@code app/csd/CARDDEMO.CSD:L3-L9}. {@code app/cbl/COACTUPC.cbl} rewrites two files in one unit
 * of work, at {@code app/cbl/COACTUPC.cbl:L4066} and {@code app/cbl/COACTUPC.cbl:L4086}, and
 * reaches {@code SYNCPOINT ROLLBACK} at {@code app/cbl/COACTUPC.cbl:L4100}.
 * {@code app/cbl/CBTRN02C.cbl:L440-L442} runs three writes with no rollback.
 *
 * <p>{@code card-platform/docs/decision-log.md} records this addition.
 *
 * <p>{@code outbox/OutboxWriter} inserts one row in the same local transaction as the domain
 * write. {@code outbox/OutboxRelay} publishes the row, then marks it sent in a separate
 * transaction. An account update writes one row, and a cycle-close writes one row. A read writes
 * none, and no account-read event exists. {@code card-platform/docs/event-flow.md} draws the path.
 *
 * <p>The relay reads unpublished rows ordered by {@code occurred_at} ascending, then
 * {@code event_id} ascending. Two rows can hold one timestamp, and the primary key completes the
 * order. {@code V1__schema.sql} declares the index on {@code (published, occurred_at, event_id)}
 * that serves this read. Flyway owns every Data Definition Language (DDL) statement for the table,
 * and this class declares no index.
 *
 * <p>Configuration supplies the sweep delay of 500 milliseconds, the batch size of 100 rows, the
 * schema, and the topic name. {@link #getAggregateId()} holds the account identifier, which is
 * also the message key.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEventEntity {

    /**
     * Width of {@code event_id}, matching {@code VARCHAR(36)} in {@code V1__schema.sql}. A
     * universally unique identifier in canonical text form occupies 36 characters.
     */
    public static final int EVENT_ID_LENGTH = 36;

    /**
     * Widest {@code event_type} this row holds, matching {@code VARCHAR(64)} in
     * {@code V1__schema.sql}.
     */
    public static final int EVENT_TYPE_MAX_LENGTH = 64;

    /**
     * Total digits in {@code aggregate_id}. The value is an account identifier,
     * {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}, whose key width is
     * {@code KEYS(11 0)} at {@code app/jcl/ACCTFILE.jcl:L40}.
     */
    public static final int AGGREGATE_ID_PRECISION = PicClause.ACCT_ID_WIDTH;

    /**
     * Digits after the decimal point in {@code aggregate_id}. An account identifier holds none. The
     * column is {@code NUMERIC(11,0)} and keeps the padded digits of the source key.
     */
    public static final int AGGREGATE_ID_SCALE = 0;

    /**
     * Identifier of this event, supplied by {@code outbox/OutboxWriter}. The same value travels in
     * the {@code EventEnvelope.eventId} field of the payload, and every consumer records it in its
     * own processed-event marker.
     */
    @Id
    @Column(name = "event_id", nullable = false, length = EVENT_ID_LENGTH)
    private String eventId;

    /**
     * Name of this event, mirroring the {@code EventEnvelope.eventType} field. The relay routes on
     * this value and reads no part of the payload.
     */
    @Column(name = "event_type", nullable = false, length = EVENT_TYPE_MAX_LENGTH)
    private String eventType;

    /**
     * One complete event, serialized as JavaScript Object Notation (JSON) before it reaches this
     * row, envelope included. {@code outbox/OutboxWriter} serializes and validates the document
     * against its schema, and this row stores the text it receives. Every monetary amount inside
     * the document is a decimal string.
     */
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    /**
     * Account this event belongs to, and the key the relay publishes the message under. Every
     * event for one account lands on one partition and stays in order.
     */
    @Column(name = "aggregate_id", nullable = false,
            precision = AGGREGATE_ID_PRECISION, scale = AGGREGATE_ID_SCALE)
    private BigDecimal aggregateId;

    /**
     * Whether the relay has published this row. The value is {@code false} on insert and
     * {@code true} after a successful publish.
     */
    @Column(name = "published", nullable = false)
    private boolean published;

    /**
     * Instant this event happened, mirroring the {@code EventEnvelope.occurredAt} field. The relay
     * orders its batch on this column first.
     */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /**
     * Instant the relay published this row, and {@code null} until then.
     */
    @Column(name = "published_at")
    private Instant publishedAt;

    /** Creates an empty row for Jakarta Persistence. */
    public OutboxEventEntity() {
    }

    /**
     * Creates one unpublished row.
     *
     * <p>This constructor sets {@code published} to {@code false} and leaves {@code publishedAt}
     * empty. Every new row describes work the relay still owes, and
     * {@link #markPublished(Instant)} moves both values together once the publish succeeds.</p>
     *
     * <p>No exception message carries an argument value. A payload, an account identifier and a
     * card number stay out of any log a caller writes from a failure.</p>
     *
     * @param eventId     identifier of this event, at most {@value #EVENT_ID_LENGTH} characters
     * @param eventType   name of this event, at most {@value #EVENT_TYPE_MAX_LENGTH} characters
     * @param payload     the serialized event document
     * @param aggregateId the account identifier, a whole number of at most
     *                    {@value #AGGREGATE_ID_PRECISION} digits
     * @param occurredAt  the instant this event happened
     * @throws IllegalArgumentException when an argument is absent, when text exceeds its column
     *                                 width, or when {@code aggregateId} is negative, carries a
     *                                 fraction, or holds too many digits
     */
    public OutboxEventEntity(String eventId, String eventType, String payload,
            BigDecimal aggregateId, Instant occurredAt) {
        this.eventId = requireWithin(eventId, "eventId", EVENT_ID_LENGTH);
        this.eventType = requireWithin(eventType, "eventType", EVENT_TYPE_MAX_LENGTH);
        this.payload = requirePresent(payload, "payload");
        this.aggregateId = requireAccountIdentifier(aggregateId);
        this.occurredAt = requireInstant(occurredAt, "occurredAt");
        this.published = false;
        this.publishedAt = null;
    }

    /**
     * Records a successful publish.
     *
     * <p>This method sets {@code published} to {@code true} and {@code publishedAt} to the supplied
     * instant in one call. The flag and the timestamp always agree.</p>
     *
     * @param publishedAt the instant the publish completed
     * @throws IllegalArgumentException when {@code publishedAt} is absent
     */
    public void markPublished(Instant publishedAt) {
        this.publishedAt = requireInstant(publishedAt, "publishedAt");
        this.published = true;
    }

    /**
     * Returns the identifier of this event.
     *
     * @return the value of {@code event_id}
     */
    public String getEventId() {
        return eventId;
    }

    /**
     * Sets the identifier of this event.
     *
     * @param eventId identifier of this event, at most {@value #EVENT_ID_LENGTH} characters
     * @throws IllegalArgumentException when the argument is absent or exceeds the column width
     */
    public void setEventId(String eventId) {
        this.eventId = requireWithin(eventId, "eventId", EVENT_ID_LENGTH);
    }

    /**
     * Returns the name of this event.
     *
     * @return the value of {@code event_type}
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * Sets the name of this event.
     *
     * @param eventType name of this event, at most {@value #EVENT_TYPE_MAX_LENGTH} characters
     * @throws IllegalArgumentException when the argument is absent or exceeds the column width
     */
    public void setEventType(String eventType) {
        this.eventType = requireWithin(eventType, "eventType", EVENT_TYPE_MAX_LENGTH);
    }

    /**
     * Returns the serialized event document.
     *
     * @return the value of {@code payload}
     */
    public String getPayload() {
        return payload;
    }

    /**
     * Sets the serialized event document.
     *
     * @param payload the serialized event document
     * @throws IllegalArgumentException when the argument is absent
     */
    public void setPayload(String payload) {
        this.payload = requirePresent(payload, "payload");
    }

    /**
     * Returns the account this event belongs to, which is also the message key.
     *
     * @return the value of {@code aggregate_id}
     */
    public BigDecimal getAggregateId() {
        return aggregateId;
    }

    /**
     * Sets the account this event belongs to.
     *
     * @param aggregateId the account identifier, a whole number of at most
     *                    {@value #AGGREGATE_ID_PRECISION} digits
     * @throws IllegalArgumentException when the argument is absent, negative, carries a fraction,
     *                                 or holds too many digits
     */
    public void setAggregateId(BigDecimal aggregateId) {
        this.aggregateId = requireAccountIdentifier(aggregateId);
    }

    /**
     * Returns whether the relay has published this row.
     *
     * @return the value of {@code published}
     */
    public boolean isPublished() {
        return published;
    }

    /**
     * Sets whether the relay has published this row.
     *
     * @param published {@code true} once the publish succeeds
     */
    public void setPublished(boolean published) {
        this.published = published;
    }

    /**
     * Returns the instant this event happened.
     *
     * @return the value of {@code occurred_at}
     */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /**
     * Sets the instant this event happened.
     *
     * @param occurredAt the instant this event happened
     * @throws IllegalArgumentException when the argument is absent
     */
    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = requireInstant(occurredAt, "occurredAt");
    }

    /**
     * Returns the instant the relay published this row.
     *
     * @return the value of {@code published_at}, or {@code null} while the row waits
     */
    public Instant getPublishedAt() {
        return publishedAt;
    }

    /**
     * Sets the instant the relay published this row.
     *
     * @param publishedAt the instant the publish completed, or {@code null} while the row waits
     */
    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    /**
     * Compares two rows on {@code event_id}.
     *
     * <p>A row without an identifier equals itself alone, and two unsaved rows stay apart in a
     * collection. The test accepts any subtype, and a lazy proxy compares equal to the row it
     * stands for.</p>
     *
     * @param other the object to compare against
     * @return {@code true} when both rows carry one identifier
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OutboxEventEntity that)) {
            return false;
        }
        return eventId != null && eventId.equals(that.eventId);
    }

    /**
     * Returns a hash code derived from {@code event_id}.
     *
     * @return the hash code of the identifier, or zero while the row carries none
     */
    @Override
    public int hashCode() {
        return eventId == null ? 0 : eventId.hashCode();
    }

    /**
     * Describes this row for a log line.
     *
     * <p>The payload stays out of the result. No masked card number, monetary amount or other
     * document field reaches the log.</p>
     *
     * @return the routing and state columns of this row
     */
    @Override
    public String toString() {
        return "OutboxEventEntity{eventId=" + eventId
                + ", eventType=" + eventType
                + ", aggregateId=" + aggregateId
                + ", published=" + published
                + ", occurredAt=" + occurredAt
                + ", publishedAt=" + publishedAt
                + "}";
    }

    /**
     * Returns text that is present and within its column width.
     *
     * @param value     the text to check
     * @param field     the field name, used in the failure message
     * @param maxLength the widest text the column holds
     * @return the supplied text
     * @throws IllegalArgumentException when the text is absent or too long
     */
    private static String requireWithin(String value, String field, int maxLength) {
        String present = requirePresent(value, field);
        if (present.length() > maxLength) {
            throw new IllegalArgumentException(field + " holds at most " + maxLength
                    + " characters, received " + present.length());
        }
        return present;
    }

    /**
     * Returns text that is present.
     *
     * @param value the text to check
     * @param field the field name, used in the failure message
     * @return the supplied text
     * @throws IllegalArgumentException when the text is {@code null} or empty
     */
    private static String requirePresent(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    /**
     * Returns an account identifier that fits {@code NUMERIC(11,0)}.
     *
     * @param value the identifier to check
     * @return the supplied identifier
     * @throws IllegalArgumentException when the identifier is absent, negative, carries a
     *                                 fraction, or holds too many digits
     */
    private static BigDecimal requireAccountIdentifier(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("aggregateId is required");
        }
        if (value.scale() != AGGREGATE_ID_SCALE) {
            throw new IllegalArgumentException("aggregateId holds scale " + AGGREGATE_ID_SCALE
                    + ", received scale " + value.scale());
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("aggregateId holds no negative value");
        }
        if (value.precision() > AGGREGATE_ID_PRECISION) {
            throw new IllegalArgumentException("aggregateId holds at most " + AGGREGATE_ID_PRECISION
                    + " digits, received " + value.precision());
        }
        return value;
    }

    /**
     * Returns an instant that is present.
     *
     * @param value the instant to check
     * @param field the field name, used in the failure message
     * @return the supplied instant
     * @throws IllegalArgumentException when the instant is {@code null}
     */
    private static Instant requireInstant(Instant value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
