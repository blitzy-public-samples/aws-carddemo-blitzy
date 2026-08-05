package com.carddemo.ledger.entity;

import com.carddemo.cobol.PicClause;
import com.carddemo.events.EventEnvelope;
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
 * One pending domain event of the ledger posting service, held in the table {@code outbox_event}.
 *
 * <p>A caller writes one row in the same local transaction as the domain write that produced the
 * event. {@link #getPayload()} returns that event as one JavaScript Object Notation (JSON)
 * document, and {@link #getAggregateId()} returns the account identifier that keys it.</p>
 *
 * <p>No COBOL (Common Business Oriented Language) record corresponds to this table.</p>
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

    /** Widest {@code event_type} this table holds. */
    private static final int EVENT_TYPE_MAX_LENGTH = 50;

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
    @Column(name = "aggregate_id", nullable = false, length = PicClause.ACCT_ID_WIDTH,
            columnDefinition = "bpchar(11)")
    private String aggregateId;

    /** The whole event document. */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** Whether this row has been published. */
    @Column(name = "published", nullable = false)
    private boolean published;

    /** Time the caller recorded the event. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** When the relay published this row, or null until it has. */
    @Column(name = "published_at")
    private Instant publishedAt;

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
        this.publishedAt = null;
        this.nextAttemptAt = createdAt;
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
     * Reports whether the relay has already published this row.
     *
     * @return {@code true} once {@link #markPublished(Instant)} has run
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
     * <p>A second call on an already published row is ignored. A relay that publishes and then
     * fails before its own transaction commits therefore leaves the row intact for the retry.
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
     * Describes this row. The payload stays out of the text, and so does the aggregate identifier.
     *
     * <p>The aggregate identifier is an account identifier, so it appears as
     * {@link EventEnvelope#WITHHELD}, the platform-wide redaction marker.
     *
     * @return the event identifier, the type, the withheld aggregate identifier, the published flag
     *         and the creation time
     */
    @Override
    public String toString() {
        return "OutboxEventEntity{eventId=" + eventId
                + ", eventType=" + eventType
                + ", aggregateId=" + EventEnvelope.WITHHELD
                + ", published=" + published + "}";
    }

    // ------------------------------------------------------------------------------------
    // Relay state. No COBOL ancestor: the source's one asynchronous handoff is the transient data
    // queue write at app/cbl/CORPT00C.cbl:L517, which carries no lease. The enum, the four
    // constants, the seven columns and the three operations below hold two invariants. A row is
    // claimed by at most one relay instance, so one event is published once. A row carries an
    // attempt count and a next-attempt time, so an undeliverable row is abandoned rather than
    // retried forever ahead of the rows behind it.
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
     * <p>The ceiling lives here and not in a check constraint. Abandoning a row is a decision
     * the relay records, whereas a constraint would turn the attempt that crosses the ceiling
     * into a failed statement.
     */
    public static final int MAX_DELIVERY_ATTEMPTS = 10;

    /**
     * Widest value {@code last_error} holds, from {@code last_error VARCHAR(500)} in
     * {@code src/main/resources/db/migration/V1__schema.sql}. The bound keeps a stack trace out
     * of the column by accident. A longer reason is truncated rather than refused, so the row
     * survives and only the tail of the diagnostic is lost.
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
