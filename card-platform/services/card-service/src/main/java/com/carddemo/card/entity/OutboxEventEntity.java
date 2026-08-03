package com.carddemo.card.entity;

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
 * One row of the table {@code outbox_event} in the card service's private schema.
 *
 * <p>ADDITIVE. This table has no COBOL (Common Business Oriented Language) ancestor. No program,
 * copybook or job in the CardDemo source stores an event row.
 *
 * <p>The card update path writes one row in the same local database transaction as the card change
 * that row describes. The relay under {@code com.carddemo.card.outbox} reads unpublished rows in
 * arrival order, publishes each one, then calls {@link #markPublished()}. The annotation
 * {@code @EnableScheduling} on {@code com.carddemo.card.CardApplication} starts that sweep. A card
 * list and a card read write nothing here.
 *
 * <p>The three writes at {@code app/cbl/CBTRN02C.cbl:L440-L442} run under no condition, and no
 * rollback follows them. All eight file definitions in {@code app/csd/CARDDEMO.CSD} carry
 * {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}, eight occurrences of each.
 *
 * <p>The source holds one asynchronous handoff, the queue write at
 * {@code app/cbl/CORPT00C.cbl:L517-L520}. Paragraph {@code WIRTE-JOBSUB-TDQ} writes the Customer
 * Information Control System (CICS) transient data queue {@code QUEUE ('JOBS')}, and a separate job
 * reads the Job Control Language (JCL) record back. That handoff supplies the pattern and no data.
 *
 * <p>Flyway creates this table from {@code src/main/resources/db/migration/V1__schema.sql}, and
 * Hibernate runs under {@code ddl-auto: validate}. Every mapping below matches that migration, which
 * declares six columns and no seventh. The schema name arrives at run time from the environment
 * variable {@code SPRING_JPA_PROPERTIES_HIBERNATE_DEFAULT_SCHEMA}, so the {@link Table} annotation
 * names no schema.
 *
 * <pre>
 * event_id      UUID                        NOT NULL, primary key
 * event_type    VARCHAR(50)                 NOT NULL
 * aggregate_id  VARCHAR(11)                 NOT NULL
 * payload       VARCHAR(4000)               NOT NULL
 * published     BOOLEAN                     NOT NULL DEFAULT FALSE
 * created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL
 * </pre>
 *
 * <p>{@link #getPayload()} holds one event serialized as JavaScript Object Notation (JSON). That
 * document is flat, one object one level deep, carrying five envelope fields beside the payload
 * fields. The schema {@code card-state-changed-v1.json} that {@code com.carddemo:event-contracts}
 * ships lists all eleven of them in one {@code required} array. A caller serializes and validates
 * the document before construction, and this class stores that text unchanged.
 *
 * <p>Monetary fields inside the document are decimal strings. A card number inside it arrives
 * masked, and no card verification value reaches it.
 *
 * <p>A new consumer reads the published event with no change to this class.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Entity
@Table(name = "outbox_event",
        indexes = @Index(name = "idx_outbox_event_unpublished",
                columnList = "published, created_at"))
public class OutboxEventEntity {

    /**
     * Widest {@code eventType} this row holds, from {@code event_type VARCHAR(50)} in
     * {@code src/main/resources/db/migration/V1__schema.sql}. The one value this service writes is
     * {@code CardStateChanged} at sixteen characters, fixed by the {@code const} that
     * {@code card-state-changed-v1.json} declares.
     */
    public static final int EVENT_TYPE_MAX_LENGTH = 50;

    /**
     * Length of every {@code aggregateId}, from {@code aggregate_id VARCHAR(11)} in
     * {@code src/main/resources/db/migration/V1__schema.sql}. The width is
     * {@link PicClause#XREF_ACCT_ID_WIDTH}, which carries {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7}.
     */
    public static final int AGGREGATE_ID_LENGTH = PicClause.XREF_ACCT_ID_WIDTH;

    /**
     * Widest {@code payload} this row holds, from {@code payload VARCHAR(4000)} in
     * {@code src/main/resources/db/migration/V1__schema.sql}. The widest single value in the card
     * event is the embossed name, {@code CARD-EMBOSSED-NAME PIC X(50)} at
     * {@code app/cpy/CVACT02Y.cpy:L8}.
     */
    public static final int PAYLOAD_MAX_LENGTH = 4000;

    /**
     * Pattern every {@code aggregateId} matches: exactly eleven decimal digits, compiled once. The
     * text below is the {@code pattern} that the {@code aggregateId} property of
     * {@code card-state-changed-v1.json} carries, so a leading zero survives the round trip.
     */
    private static final Pattern AGGREGATE_ID_PATTERN = Pattern.compile("^[0-9]{11}$");

    /**
     * The Universally Unique Identifier (UUID) the caller assigned to this event, and the key each
     * consumer deduplicates on.
     */
    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    /** The routing discriminator, which is the simple class name of the event. */
    @Column(name = "event_type", nullable = false, length = EVENT_TYPE_MAX_LENGTH)
    private String eventType;

    /** The account identifier, eleven digits, and the message key the relay publishes under. */
    @Column(name = "aggregate_id", nullable = false, length = AGGREGATE_ID_LENGTH)
    private String aggregateId;

    /** One event, already serialized, flat and one level deep. */
    @Column(name = "payload", nullable = false, length = PAYLOAD_MAX_LENGTH)
    private String payload;

    /** False until the relay publishes this row. */
    @Column(name = "published", nullable = false)
    private boolean published;

    /** The time this row was written, supplied by the caller. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Required by the persistence provider. Application code calls
     * {@link #OutboxEventEntity(UUID, String, String, String, Instant)}.
     */
    protected OutboxEventEntity() {
        // The provider assigns every field from the row it read, so no check below runs on it.
    }

    /**
     * Builds one unpublished row.
     *
     * @param eventId     the event identifier the caller assigned
     * @param eventType   the simple class name of the event, not blank and at most
     *                    {@value #EVENT_TYPE_MAX_LENGTH} characters
     * @param aggregateId the account identifier, exactly {@value #AGGREGATE_ID_LENGTH} decimal
     *                    digits, leading zeros kept
     * @param payload     one serialized event, not blank and at most
     *                    {@value #PAYLOAD_MAX_LENGTH} characters
     * @param createdAt   the time this row was written
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code eventType} or {@code payload} is blank or longer
     *                                  than its column, or if {@code aggregateId} is not
     *                                  {@value #AGGREGATE_ID_LENGTH} decimal digits
     */
    public OutboxEventEntity(UUID eventId, String eventType, String aggregateId, String payload,
            Instant createdAt) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.eventType = requireText(eventType, "eventType", EVENT_TYPE_MAX_LENGTH);
        this.aggregateId = requireAggregateId(aggregateId);
        this.payload = requireText(payload, "payload", PAYLOAD_MAX_LENGTH);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.published = false;
    }

    /**
     * Checks one text argument and returns it. No failure message carries the argument value.
     *
     * @param value     the argument to check
     * @param fieldName the field name, which names the field in any failure message
     * @param maxLength the widest value the matching column holds
     * @return {@code value}
     */
    private static String requireText(String value, String fieldName, int maxLength) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " is " + value.length()
                    + " characters, over the " + maxLength + " its column holds");
        }
        return value;
    }

    /**
     * Checks the account identifier against {@link #AGGREGATE_ID_PATTERN} and returns it. The
     * failure message names the field and omits the value.
     *
     * @param value the argument to check
     * @return {@code value}
     */
    private static String requireAggregateId(String value) {
        Objects.requireNonNull(value, "aggregateId");
        if (!AGGREGATE_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "aggregateId is not " + AGGREGATE_ID_LENGTH + " decimal digits");
        }
        return value;
    }

    /**
     * Returns the event identifier the caller assigned.
     *
     * @return the key each consumer deduplicates on
     */
    public UUID getEventId() {
        return eventId;
    }

    /**
     * Returns the routing discriminator.
     *
     * @return the simple class name of the event
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * Returns the account identifier, eleven digits with leading zeros kept.
     *
     * @return the message key the relay publishes this row under
     */
    public String getAggregateId() {
        return aggregateId;
    }

    /**
     * Returns the serialized event this row carries.
     *
     * @return one JSON document, flat and one level deep
     */
    public String getPayload() {
        return payload;
    }

    /**
     * Reports whether the relay has published this row.
     *
     * @return {@code true} after {@link #markPublished()}
     */
    public boolean isPublished() {
        return published;
    }

    /**
     * Returns the time this row was written.
     *
     * @return the creation time the caller supplied
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Sets {@link #isPublished()} to {@code true}. The relay calls this after one successful
     * publish, and a second call leaves the flag set.
     */
    public void markPublished() {
        this.published = true;
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
}
