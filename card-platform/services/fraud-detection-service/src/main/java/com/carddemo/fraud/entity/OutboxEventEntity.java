package com.carddemo.fraud.entity;

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

/**
 * One row of the table {@code outbox_event} in this service's private schema, holding one event for
 * the topic {@code fraud.assessed}. A row is pending, claimed by a relay instance, published or
 * abandoned; {@code relay_state} says which.
 *
 * <p>No Common Business Oriented Language (COBOL) ancestor. Searching {@code app/cbl/} for
 * {@code fraud}, {@code velocit}, {@code risk}, {@code scoring} and {@code luhn} matches zero of
 * its 28 programs.
 * The width of {@code aggregate_id} comes from {@code XREF-ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT03Y.cpy:L7}, shape only; no other column here has a source counterpart.
 *
 * <p>Flyway creates the table from {@code src/main/resources/db/migration/V1__schema.sql}, which
 * is authoritative for every column, index and constraint. Hibernate runs under
 * {@code ddl-auto: validate} and writes no schema object, so a column name or type that differs
 * from that migration stops start-up.
 *
 * <p>The mapped columns fall into three groups: the event itself ({@code event_id},
 * {@code event_type}, {@code aggregate_id}, {@code payload}), its publication
 * ({@code published}, {@code created_at}, {@code published_at}), the relay's own bookkeeping
 * ({@code relay_state}, {@code attempt_count}, {@code next_attempt_at}, {@code last_attempt_at},
 * {@code last_error}, {@code claimed_by}, {@code claimed_at}), and the terminal diagnostic it owes
 * once it abandons a row ({@code dead_letter_state}, {@code dead_letter_published_at}). Four
 * indexes serve the pending scan, the claim query, the published purge and the owed diagnostics.
 *
 * <p>{@code messaging.TransactionAuthorizedConsumer} writes one row through
 * {@code outbox.OutboxWriter}, inside the same local transaction as the assessment that row
 * describes. {@code outbox.OutboxRelay} claims due rows in {@code next_attempt_at} then
 * {@code event_id} order, publishes each one, then calls {@link #markPublished(Instant)}. A claim
 * keeps two instances from publishing one row concurrently; it is not exactly-once delivery, because
 * a send the broker acknowledged before the claiming transaction failed to commit leaves the row
 * claimable and the next sweep publishes it again. Each consumer's processed-event marker, keyed by
 * event identifier and consumed topic, is what makes the repeat harmless.
 */
@Entity
@Table(name = "outbox_event",
        indexes = {
                @Index(name = "ix_outbox_event_pending",
                        columnList = "created_at, event_id"),
                @Index(name = "ix_outbox_event_claimable",
                        columnList = "relay_state, next_attempt_at"),
                @Index(name = "ix_outbox_event_published_at", columnList = "published_at"),
                // Declared partial in V5__outbox_dead_letter_state.sql, over the owed diagnostics
                // alone. Flyway owns the schema, so the predicate lives in the migration and this
                // mapping names the index only so the two cannot drift apart unnoticed.
                @Index(name = "ix_outbox_event_dead_letter_required",
                        columnList = "last_attempt_at")})
public class OutboxEventEntity {

    /**
     * Characters the {@code event_type} column holds, and the width every service of this platform
     * declares for it.
     *
     * <p>The longest value any service writes is {@code CustomerContextChanged} at twenty-two
     * characters, so this width leaves room for a longer event type without a migration.
     */
    public static final int EVENT_TYPE_MAX_LENGTH = 50;

    /**
     * Characters the {@code aggregate_id} column holds, from {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7}, whose key width is {@code KEYS(11 0)} at
     * {@code app/jcl/XREFFILE.jcl:L39}.
     */
    public static final int AGGREGATE_ID_LENGTH = 11;

    /**
     * The event identifier the writer assigned, a Universally Unique Identifier (UUID), and the
     * deduplication key for this event.
     */
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    /**
     * The routing discriminator, exactly {@code FraudFlagged} or {@code FraudCleared}. Both event
     * types travel on the topic {@code fraud.assessed}.
     */
    @Column(name = "event_type", length = EVENT_TYPE_MAX_LENGTH, nullable = false,
            updatable = false)
    private String eventType;

    /**
     * The account identifier, eleven decimal digits, and always the Kafka message key. PostgreSQL
     * reports the fixed-width type of this column through Java Database Connectivity (JDBC)
     * metadata as {@code bpchar}, and {@link Column#columnDefinition()} names that type verbatim.
     */
    @Column(name = "aggregate_id", length = AGGREGATE_ID_LENGTH, nullable = false,
            updatable = false, columnDefinition = "bpchar(11)")
    private String aggregateId;

    /**
     * One event serialized as one flat JavaScript Object Notation (JSON) object. The five envelope
     * properties {@code eventId}, {@code eventType}, {@code schemaVersion}, {@code occurredAt} and
     * {@code aggregateId} sit at the top level beside the payload properties. The writer serializes
     * the document and this row stores that text unchanged.
     */
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    /** False on insert, and true once this row has been published. */
    @Column(name = "published", nullable = false)
    private boolean published;

    /** The time the writer inserted this row, and the column a relay batch is ordered by. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The time this row was published, and null until it is. */
    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEventEntity() {
        // The provider assigns every field from the row it read.
    }

    /**
     * Builds one unpublished row.
     *
     * @param eventId     the event identifier the writer assigned
     * @param eventType   the event type, not blank and at most
     *                    {@value #EVENT_TYPE_MAX_LENGTH} characters
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
        this.nextAttemptAt = createdAt;
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
        if (value.length() > EVENT_TYPE_MAX_LENGTH) {
            throw new IllegalArgumentException("eventType is " + value.length()
                    + " characters, over the " + EVENT_TYPE_MAX_LENGTH + " its column holds");
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

    public boolean isPublished() {
        return published;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
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
     * Describes this row by its identifier, its type and its publication flag, and withholds its
     * key. The text omits {@link #getPayload()}.
     *
     * <p>The key is an account identifier, so it appears as {@link EventEnvelope#WITHHELD}, the
     * platform-wide redaction marker.
     *
     * @return one line naming four fields, with the aggregate identifier withheld
     */
    @Override
    public String toString() {
        return "OutboxEventEntity{eventId=" + eventId
                + ", eventType=" + eventType
                + ", aggregateId=" + EventEnvelope.WITHHELD
                + ", published=" + published + "}";
    }

    // ------------------------------------------------------------------------------------
    // Relay state. The enum, the four constants, the seven columns and the three operations
    // below hold two invariants. A row is claimed by at most one relay instance, so one event
    // is published once. A row carries an attempt count and a next-attempt time, so an
    // undeliverable row is abandoned rather than retried forever ahead of the rows behind it.
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

    /**
     * Whether an abandoned row still owes a terminal diagnostic on the dead-letter topic.
     *
     * <p>{@link RelayState#ABANDONED} says the relay stopped attempting a row. It does not say
     * anyone was told, and this service is the one where that distinction bites hardest: fraud
     * detection is ADDITIVE, so there is no batch job to re-run and no reject dataset holding what
     * was missed. The published assessment is the only record that the rules ever ran on a
     * transaction, and an abandoned row that names itself nowhere is an assessment that silently
     * never happened.
     *
     * <p>{@link #REQUIRED} is therefore written in the same transaction as the abandonment, so
     * neither fact can commit without the other, and {@code outbox/OutboxRelay} offers the
     * diagnostic on every later pass until the broker acknowledges one. That is the whole
     * difference between an abandoned row and a lost one.
     *
     * <p>The names are stored as text in {@code dead_letter_state VARCHAR(16)}, which a check
     * constraint in {@code src/main/resources/db/migration/V5__outbox_dead_letter_state.sql}
     * restricts to exactly these three.
     */
    public enum DeadLetterState {

        /** No diagnostic is owed. Every row starts here and a published row stays here. */
        NOT_REQUIRED,

        /** A diagnostic is owed and no broker has acknowledged one. Offered on every pass. */
        REQUIRED,

        /** The broker acknowledged the diagnostic. Terminal, and paired with a timestamp. */
        PUBLISHED
    }

    /** Widest value {@code dead_letter_state} holds, from {@code dead_letter_state VARCHAR(16)}. */
    public static final int DEAD_LETTER_STATE_MAX_LENGTH = 16;

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

    /** Whether this row still owes a terminal diagnostic. Never null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "dead_letter_state", nullable = false, length = DEAD_LETTER_STATE_MAX_LENGTH)
    private DeadLetterState deadLetterState = DeadLetterState.NOT_REQUIRED;

    /** When the broker acknowledged the diagnostic, or null while one is owed or none is. */
    @Column(name = "dead_letter_published_at")
    private Instant deadLetterPublishedAt;

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
     * to {@link RelayState#ABANDONED} and the relay stops attempting the event itself. Either way
     * the claim is released, so a relay instance that dies mid-attempt does not strand the row.
     *
     * <p>Abandonment also raises {@link DeadLetterState#REQUIRED}, in this same write. The relay
     * has stopped attempting the event, so this is the last moment anything looks at the row, and
     * an obligation recorded anywhere else could commit without the abandonment or fail after it.
     * A row that reaches the ceiling therefore leaves this method owing a diagnostic, and
     * {@code outbox/OutboxRelay} discharges it on this pass or on a later one.
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
            // The obligation and the abandonment are one write, so no reader can find a row the
            // relay gave up on that owes nobody an explanation.
            this.deadLetterState = DeadLetterState.REQUIRED;
        } else {
            this.relayState = RelayState.PENDING;
            this.nextAttemptAt = retryAt;
        }
    }

    /**
     * Returns whether this row still owes a terminal diagnostic.
     *
     * @return the dead-letter state, never null
     */
    public DeadLetterState getDeadLetterState() {
        return deadLetterState;
    }

    /**
     * Returns when the broker acknowledged this row's terminal diagnostic.
     *
     * @return the acknowledgement moment, or null while a diagnostic is owed or none is
     */
    public Instant getDeadLetterPublishedAt() {
        return deadLetterPublishedAt;
    }

    /**
     * Answers whether this row owes a diagnostic no broker has acknowledged.
     *
     * <p>{@code outbox/OutboxRelay} offers the diagnostic on every pass while this answers true. A
     * row answers true only after {@link #recordFailure(String, Instant, Instant)} abandoned it,
     * and stops answering true once {@link #markDeadLetterPublished(Instant)} records the
     * acknowledgement.
     *
     * @return true while a diagnostic is owed
     */
    public boolean owesDeadLetter() {
        return deadLetterState == DeadLetterState.REQUIRED;
    }

    /**
     * Records that the broker acknowledged this row's terminal diagnostic.
     *
     * <p>Called after the acknowledgement and never before it. The obligation is the thing that
     * survives a broker outage, so clearing it on the attempt rather than on the acknowledgement
     * would lose exactly the diagnostic it exists to keep.
     *
     * @param publishedAt when the broker acknowledged the diagnostic
     * @throws NullPointerException  when {@code publishedAt} is null
     * @throws IllegalStateException when this row owes no diagnostic, which means either that none
     *                               was ever owed or that one was already acknowledged
     */
    public void markDeadLetterPublished(Instant publishedAt) {
        Objects.requireNonNull(publishedAt, "publishedAt");
        if (deadLetterState != DeadLetterState.REQUIRED) {
            throw new IllegalStateException("a row in dead-letter state " + deadLetterState
                    + " owes no diagnostic to acknowledge");
        }
        this.deadLetterState = DeadLetterState.PUBLISHED;
        this.deadLetterPublishedAt = publishedAt;
    }
}
