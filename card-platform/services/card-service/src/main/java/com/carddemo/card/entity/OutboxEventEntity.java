package com.carddemo.card.entity;

import com.carddemo.cobol.PicClause;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * arrival order, publishes each one, then calls {@link #markPublished(Instant)}. The annotation
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
 * event_id     UUID                        NOT NULL, primary key
 * event_type   VARCHAR(50)                 NOT NULL
 * aggregate_id CHAR(11)                    NOT NULL
 * payload      TEXT                        NOT NULL
 * published    BOOLEAN                     NOT NULL DEFAULT FALSE
 * created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL
 * published_at TIMESTAMP(6) WITH TIME ZONE
 * </pre>
 *
 * <p>{@link #getPayload()} holds one event serialized as JavaScript Object Notation (JSON). That
 * document is flat, one object one level deep, carrying five envelope fields beside the payload
 * fields. The schema {@code card-updated-v1.json} that {@code com.carddemo:event-contracts}
 * ships lists all ten of them in one {@code required} array. A caller serializes and validates the
 * document before construction, and this class stores that text unchanged.
 *
 * <p>Monetary fields inside the document are decimal strings. A card number inside it arrives
 * masked, and no card verification value reaches it.
 *
 * <p>A new consumer reads the published event with no change to this class.
 */
@Entity
@Table(name = "outbox_event",
        indexes = {
                @Index(name = "ix_outbox_event_pending",
                        columnList = "created_at, event_id"),
                @Index(name = "ix_outbox_event_claimable",
                        columnList = "relay_state, next_attempt_at"),
                @Index(name = "ix_outbox_event_published_at", columnList = "published_at")})
public class OutboxEventEntity {

    /**
     * Widest {@code eventType} this row holds, from {@code event_type VARCHAR(50)} in
     * {@code src/main/resources/db/migration/V1__schema.sql}. The one value this service writes is
     * {@code CardUpdated} at eleven characters, fixed by the {@code const} that
     * {@code card-updated-v1.json} declares.
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
     * Characters this row accepts in {@code payload}.
     *
     * <p>The column is {@code TEXT} and holds more, so this is a guard rather than a column width.
     * The outbox sits on the write path of every request that produces an event, and a payload past
     * this size means a caller has put something in an event that does not belong there.
     */
    public static final int PAYLOAD_MAX_LENGTH = 4000;

    /**
     * Pattern every {@code aggregateId} matches: exactly eleven decimal digits, compiled once. The
     * text below is the {@code pattern} that the {@code aggregateId} property of
     * {@code card-updated-v1.json} carries, so a leading zero survives the round trip.
     */
    private static final Pattern AGGREGATE_ID_PATTERN = Pattern.compile("^[0-9]{11}$");

    /**
     * The Universally Unique Identifier (UUID) the caller assigned to this event, and the
     * deduplication key for this event.
     */
    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    /** The routing discriminator, which is the simple class name of the event. */
    @Column(name = "event_type", nullable = false, length = EVENT_TYPE_MAX_LENGTH)
    private String eventType;

    /** The account identifier, eleven digits, and the message key the relay publishes under. */
    @Column(name = "aggregate_id", nullable = false, length = AGGREGATE_ID_LENGTH,
            columnDefinition = "bpchar(11)")
    private String aggregateId;

    /** One event, already serialized, flat and one level deep. */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** False on insert, and true once this row has been published. */
    @Column(name = "published", nullable = false)
    private boolean published;

    /** The time this row was written, supplied by the caller. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * The time the relay published this row, and null until it does.
     *
     * <p>{@link #markPublished(Instant)} sets this value and the flag together, so
     * {@code ck_outbox_event_publication} in {@code db/migration/V1__schema.sql} always holds.
     */
    @Column(name = "published_at")
    private Instant publishedAt;

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
     *                    {@value #PAYLOAD_MAX_LENGTH} characters, the guard this row applies
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
        this.publishedAt = null;
        this.nextAttemptAt = createdAt;
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

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getPayload() {
        return payload;
    }

    /**
     * Reports whether the relay has published this row.
     *
     * @return {@code true} after {@link #markPublished(Instant)}
     */
    public boolean isPublished() {
        return published;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Records that the relay published this row, moving it to its terminal successful state.
     *
     * <p>Three values move together, and they have to: {@code published}, {@code relay_state} and
     * {@code published_at}. Two check constraints in
     * {@code src/main/resources/db/migration/V1__schema.sql} tie them, so a flush that set only the
     * boolean would be refused by the database rather than quietly leave a published row looking
     * pending. The claim is released, because a published row needs none.
     *
     * <p>A second call on an already published row is ignored, so a relay that publishes and then
     * fails before its own transaction commits does not corrupt the row on the retry that follows.
     *
     * @param publishedAt when the publish succeeded
     * @throws NullPointerException  if {@code publishedAt} is null
     * @throws IllegalStateException if this row was abandoned, which is the other terminal state
     */
    public void markPublished(Instant publishedAt) {
        Objects.requireNonNull(publishedAt, "publishedAt");
        if (this.relayState == RelayState.ABANDONED) {
            throw new IllegalStateException("an ABANDONED row cannot be marked published");
        }
        if (this.published) {
            return;
        }
        this.published = true;
        this.relayState = RelayState.PUBLISHED;
        this.publishedAt = publishedAt;
        this.claimedBy = null;
        this.claimedAt = null;
    }

    /**
     * Returns the time the relay published this row.
     *
     * @return the value of {@code published_at}, or {@code null} while the row waits
     */
    public Instant getPublishedAt() {
        return publishedAt;
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

    // ------------------------------------------------------------------------------------
    // Relay state. ADDITIVE: the CardDemo source has no relay and therefore no lease. Its one
    // asynchronous handoff, the transient data queue write at app/cbl/CORPT00C.cbl:L517, is
    // picked up by a single scheduled job, so nothing there can claim a row twice or give up on
    // one. The enum, the four constants, the seven columns and the three operations below are
    // one concern and are kept together rather than scattered through the class.
    //
    // Two failures are what these columns exist to prevent. Without a claim, two relay instances
    // read the same unpublished row and publish the same event twice, which a consumer then has
    // to deduplicate. Without an attempt count and a next-attempt time, one undeliverable row is
    // retried forever and every row behind it waits.
    // ------------------------------------------------------------------------------------

    /**
     * How one row stands with the relay.
     *
     * <p>{@link #PENDING} is awaiting a first or a further attempt, {@link #CLAIMED} is held by
     * one relay instance, and {@link #PUBLISHED} and {@link #ABANDONED} are terminal. The names
     * are stored as text in {@code relay_state VARCHAR(16)}, which a check constraint in
     * {@code src/main/resources/db/migration/V1__schema.sql} restricts to exactly these four.
     */
    public enum RelayState {

        /** Written and awaiting an attempt. Every new row starts here. */
        PENDING,

        /** Held by the relay instance named in {@code claimed_by}. */
        CLAIMED,

        /** Published. Terminal, and the only state in which {@code published} is true. */
        PUBLISHED,

        /** Given up on after {@value #MAX_DELIVERY_ATTEMPTS} attempts. Terminal. */
        ABANDONED
    }

    /**
     * How many attempts a row takes before the relay abandons it.
     *
     * <p>The ceiling lives here and not in a check constraint on purpose: abandoning a row is a
     * decision the relay records, and a constraint would instead turn the attempt that crosses
     * the ceiling into a failed statement.
     */
    public static final int MAX_DELIVERY_ATTEMPTS = 10;

    /**
     * Widest value {@code last_error} holds, from {@code last_error VARCHAR(500)} in
     * {@code src/main/resources/db/migration/V1__schema.sql}. The column is bounded so that a
     * stack trace cannot be stored in it by accident, and a longer reason is truncated rather
     * than refused: losing the tail of a diagnostic is better than losing the row.
     */
    public static final int LAST_ERROR_MAX_LENGTH = 500;

    /** Widest value {@code claimed_by} holds, from {@code claimed_by VARCHAR(64)}. */
    public static final int CLAIMED_BY_MAX_LENGTH = 64;

    /** Widest value {@code relay_state} holds, from {@code relay_state VARCHAR(16)}. */
    public static final int RELAY_STATE_MAX_LENGTH = 16;

    /** Where this row stands with the relay. Never null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "relay_state", nullable = false, length = RELAY_STATE_MAX_LENGTH)
    private RelayState relayState = RelayState.PENDING;

    /** How many publish attempts this row has taken. Zero until the first attempt. */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /** When the relay may next attempt this row. Equal to the creation time for a new row. */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    /** When the last attempt ran, or null before the first. */
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    /** A short, redacted reason for the last failure. Never a payload and never an event value. */
    @Column(name = "last_error", length = LAST_ERROR_MAX_LENGTH)
    private String lastError;

    /** Which relay instance holds the claim, or null when none does. */
    @Column(name = "claimed_by", length = CLAIMED_BY_MAX_LENGTH)
    private String claimedBy;

    /** When the claim was taken, or null when no claim is held. */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    /**
     * Returns where this row stands with the relay.
     *
     * @return the relay state, never null
     */
    public RelayState getRelayState() {
        return relayState;
    }

    /**
     * Returns how many publish attempts this row has taken.
     *
     * @return the attempt count, zero before the first attempt
     */
    public int getAttemptCount() {
        return attemptCount;
    }

    /**
     * Returns when the relay may next attempt this row.
     *
     * @return the due time, never null
     */
    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    /**
     * Returns when the last attempt ran.
     *
     * @return the time of the last attempt, or null before the first
     */
    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    /**
     * Returns the short reason the last attempt failed.
     *
     * @return a redacted reason, or null when no attempt has failed
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * Returns which relay instance holds the claim.
     *
     * @return the claiming instance, or null when no claim is held
     */
    public String getClaimedBy() {
        return claimedBy;
    }

    /**
     * Returns when the claim was taken.
     *
     * @return the claim time, or null when no claim is held
     */
    public Instant getClaimedAt() {
        return claimedAt;
    }

    /**
     * Reports whether this row is in a terminal state.
     *
     * @return true once the row is published or abandoned, after which the relay leaves it alone
     */
    public boolean isTerminal() {
        return relayState == RelayState.PUBLISHED || relayState == RelayState.ABANDONED;
    }

    /**
     * Takes the claim for one relay instance.
     *
     * <p>Selecting the row is what excludes a competing instance, through
     * {@code FOR UPDATE SKIP LOCKED} in the repository's claim query; this method records who won.
     * A row already in a terminal state is refused rather than silently re-claimed, because a
     * second publish of a published row is the duplicate this machinery exists to prevent.
     *
     * @param relayId the claiming instance, not blank and at most
     *                {@value #CLAIMED_BY_MAX_LENGTH} characters
     * @param at      when the claim was taken
     * @throws NullPointerException     if either argument is null
     * @throws IllegalArgumentException if {@code relayId} is blank or too long
     * @throws IllegalStateException    if this row is already in a terminal state
     */
    public void claim(String relayId, Instant at) {
        Objects.requireNonNull(relayId, "relayId");
        Objects.requireNonNull(at, "at");
        if (relayId.isBlank()) {
            throw new IllegalArgumentException("relayId is blank");
        }
        if (relayId.length() > CLAIMED_BY_MAX_LENGTH) {
            throw new IllegalArgumentException("relayId is " + relayId.length()
                    + " characters, over the " + CLAIMED_BY_MAX_LENGTH + " its column holds");
        }
        if (isTerminal()) {
            throw new IllegalStateException("a " + relayState + " row cannot be claimed");
        }
        this.claimedBy = relayId;
        this.claimedAt = at;
        this.relayState = RelayState.CLAIMED;
    }

    /**
     * Records one failed attempt and either schedules a retry or abandons the row.
     *
     * <p>The attempt count rises by one. Below {@value #MAX_DELIVERY_ATTEMPTS} the row returns to
     * {@link RelayState#PENDING} and becomes due again at {@code retryAt}; at the ceiling it moves
     * to {@link RelayState#ABANDONED} and the relay leaves it alone. Either way the claim is
     * released, so a relay instance that dies mid-attempt does not strand the row.
     *
     * <p>A reason longer than {@value #LAST_ERROR_MAX_LENGTH} characters is truncated rather than
     * refused. The caller is responsible for passing a reason that names a failure and quotes no
     * event value, which is why this method neither reads nor writes the payload.
     *
     * @param reason  a short, redacted reason, or null when none is available
     * @param at      when the attempt ran
     * @param retryAt when the row becomes due again, ignored once the row is abandoned
     * @throws NullPointerException  if {@code at} or {@code retryAt} is null
     * @throws IllegalStateException if this row is already in a terminal state
     */
    public void recordFailure(String reason, Instant at, Instant retryAt) {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(retryAt, "retryAt");
        if (isTerminal()) {
            throw new IllegalStateException("a " + relayState + " row takes no further attempt");
        }
        this.attemptCount = this.attemptCount + 1;
        this.lastAttemptAt = at;
        this.lastError = reason == null || reason.length() <= LAST_ERROR_MAX_LENGTH
                ? reason
                : reason.substring(0, LAST_ERROR_MAX_LENGTH);
        this.claimedBy = null;
        this.claimedAt = null;
        if (this.attemptCount >= MAX_DELIVERY_ATTEMPTS) {
            this.relayState = RelayState.ABANDONED;
            this.nextAttemptAt = at;
        } else {
            this.relayState = RelayState.PENDING;
            this.nextAttemptAt = retryAt;
        }
    }
}
