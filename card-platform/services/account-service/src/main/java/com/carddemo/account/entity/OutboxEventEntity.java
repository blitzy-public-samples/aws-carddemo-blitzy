package com.carddemo.account.entity;

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
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.Objects;

/**
 * One event row in the {@code outbox_event} table.
 *
 * <p>No COBOL ancestor. This entity has no COBOL ancestor. No copybook and no program in the
 * CardDemo source declares an outbox record. The locators below are references, not ancestors.
 *
 * <p>The source carries one asynchronous handoff, {@code EXEC CICS WRITEQ TD} with
 * {@code QUEUE ('JOBS')} at {@code app/cbl/CORPT00C.cbl:L517-L518}. All eight file definitions in
 * {@code app/csd/CARDDEMO.CSD} specify {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}, first at
 * {@code app/csd/CARDDEMO.CSD:L3-L9}. {@code app/cbl/COACTUPC.cbl} rewrites two files in one unit
 * of work, at {@code app/cbl/COACTUPC.cbl:L4066} and {@code app/cbl/COACTUPC.cbl:L4086}, and
 * reaches {@code SYNCPOINT ROLLBACK} at {@code app/cbl/COACTUPC.cbl:L4100}.
 * {@code app/cbl/CBTRN02C.cbl:L440-L442} runs three writes with no rollback.
 *
 * <p>{@code outbox/OutboxWriter} inserts one row in the same local transaction as the domain write.
 * {@code outbox/OutboxRelay} publishes the row, then marks it sent in a separate transaction. An
 * account update writes one row, and a cycle-close writes one row. A read writes none, and no
 * account-read event exists.
 *
 * <p>The relay reads unpublished rows through {@code
 * findByPublishedFalseOrderByCreatedAtAscEventIdAsc}, which takes no lock, ordered by {@code
 * created_at} ascending then {@code event_id} ascending. Two rows can hold one timestamp, and the
 * primary key completes the order. The sweep delay of 500 milliseconds and the limit of 100 rows
 * are hard-coded in that class rather than read from configuration.
 *
 * <p>{@code V1__schema.sql} owns every Data Definition Language (DDL) statement for the table and
 * is authoritative for its columns, indexes and constraints. The {@link Table} annotation below
 * repeats three of those indexes so Hibernate can validate the mapping: {@code
 * ix_outbox_event_pending} over {@code (created_at, event_id)}, {@code ix_outbox_event_claimable}
 * over {@code (relay_state, next_attempt_at)}, and {@code ix_outbox_event_published_at} over {@code
 * published_at}.
 *
 * <p>{@link #getAggregateId()} holds the account identifier, which is also the message key.
 */
@Entity
@Table(name = "outbox_event",
        indexes = {
                @Index(name = "ix_outbox_event_pending",
                        columnList = "created_at, event_id"),
                @Index(name = "ix_outbox_event_claimable",
                        columnList = "relay_state, next_attempt_at"),
                @Index(name = "ix_outbox_event_aggregate_head",
                        columnList = "aggregate_id, created_at, event_id"),
                @Index(name = "ix_outbox_event_published_at", columnList = "published_at"),
                @Index(name = "ix_outbox_event_dead_letter_required",
                        columnList = "last_attempt_at")})
public class OutboxEventEntity {

    /**
     * Widest {@code event_type} this row holds, matching {@code VARCHAR(50)} in
     * {@code V1__schema.sql} and the width every service of this platform declares.
     */
    public static final int EVENT_TYPE_MAX_LENGTH = 50;

    /**
     * Characters in {@code aggregate_id}. The value is an account identifier, from
     * {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}, whose key width is
     * {@code KEYS(11 0)} at {@code app/jcl/ACCTFILE.jcl:L40}.
     *
     * <p>The column is {@code CHAR(11)} and holds text, so a leading zero survives. All fifty
     * account identifiers in {@code app/data/ASCII/acctdata.txt} open with a zero.
     */
    public static final int AGGREGATE_ID_LENGTH = PicClause.ACCT_ID_WIDTH;

    /** The form {@code aggregate_id} takes: exactly {@value #AGGREGATE_ID_LENGTH} decimal digits. */
    private static final Pattern AGGREGATE_ID_PATTERN = Pattern.compile("^[0-9]{11}$");

    /**
     * Identifier of this event, supplied by the writer. The same value travels in the
     * {@code EventEnvelope.eventId} field of the payload, and a consumer is to record it in its own
     * processed-event marker.
     */
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    /**
     * Name of this event, mirroring the {@code EventEnvelope.eventType} field. The relay is to
     * route on this value and to read no part of the payload.
     */
    @Column(name = "event_type", nullable = false, length = EVENT_TYPE_MAX_LENGTH)
    private String eventType;

    /**
     * One complete event, serialized as JavaScript Object Notation (JSON) before it reaches this
     * row, envelope included. {@code outbox/OutboxWriter} serializes the event and validates the
     * document against its schema, and this row stores the text it receives. Every monetary amount
     * inside the document is a decimal string.
     */
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    /**
     * Account this event belongs to, and the Kafka message key. Every event for one account lands
     * on one partition and stays in order.
     */
    @Column(name = "aggregate_id", nullable = false, length = AGGREGATE_ID_LENGTH,
            columnDefinition = "bpchar(11)")
    private String aggregateId;

    /**
     * Whether this row is published. The value is {@code false} on insert and {@code true} after a
     * successful publish.
     */
    @Column(name = "published", nullable = false)
    private boolean published;

    /**
     * Instant this event was written, mirroring the {@code EventEnvelope.occurredAt} field. The
     * relay orders its batch on this column first.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Instant this row was published, and {@code null} until then.
     */
    @Column(name = "published_at")
    private Instant publishedAt;

    /**
     * Root correlation identifier of the unit of work that wrote this row.
     *
     * <p>ADDITIVE, from {@code src/main/resources/db/migration/V9__outbox_correlation.sql}. Published as the
     * {@code carddemo-correlation-id} record header, so a reader joins every record of one
     * authorization on one field. It repeats across a fan-out by design and is never the
     * duplicate-delivery key: {@link #getEventId()} keeps that role.
     */
    @Column(name = "correlation_id")
    private UUID correlationId;

    /**
     * The {@code eventId} of the event whose handling wrote this row.
     *
     * <p>ADDITIVE, from {@code src/main/resources/db/migration/V9__outbox_correlation.sql}. Published as the
     * {@code carddemo-causation-id} record header. Null where a caller rather than an event asked
     * for the change.
     */
    @Column(name = "causation_id")
    private UUID causationId;

    public OutboxEventEntity() {
    }

    /**
     * Creates one unpublished row.
     *
     * <p>This constructor sets {@code published} to {@code false} and leaves {@code publishedAt}
     * empty. {@link #markPublished(Instant)} moves both values together once a publish
     * succeeds.</p>
     *
     * <p>No exception message carries an argument value. A payload, an account identifier and a
     * card number stay out of any log a caller writes from a failure.</p>
     *
     * @param eventId     identifier of this event, and the value every consumer deduplicates on
     * @param eventType   name of this event, at most {@value #EVENT_TYPE_MAX_LENGTH} characters
     * @param payload     the serialized event document
     * @param aggregateId the account identifier, exactly {@value #AGGREGATE_ID_LENGTH} decimal
     *                    digits with a leading zero kept
     * @param createdAt   the instant this event was written
     * @throws IllegalArgumentException when an argument is absent, when text exceeds its column
     *                                 width, or when {@code aggregateId} is the wrong width or
     *                                 holds a character that is not a digit
     */
    public OutboxEventEntity(UUID eventId, String eventType, String payload,
            String aggregateId, Instant createdAt) {
        this.eventId = requireEventId(eventId);
        this.eventType = requireWithin(eventType, "eventType", EVENT_TYPE_MAX_LENGTH);
        this.payload = requirePresent(payload, "payload");
        this.aggregateId = requireAccountIdentifier(aggregateId);
        this.createdAt = requireInstant(createdAt, "createdAt");
        this.published = false;
        this.publishedAt = null;
        this.nextAttemptAt = createdAt;
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
        requireInstant(publishedAt, "publishedAt");
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
     * Returns the identifier of this event.
     *
     * @return the value of {@code event_id}
     */
    public UUID getEventId() {
        return eventId;
    }

    /**
     * Sets the identifier of this event.
     *
     * @param eventId identifier of this event, and the value every consumer deduplicates on
     * @throws IllegalArgumentException when the argument is absent or exceeds the column width
     */
    public void setEventId(UUID eventId) {
        this.eventId = requireEventId(eventId);
    }

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
    public String getAggregateId() {
        return aggregateId;
    }

    /**
     * Sets the account this event belongs to.
     *
     * @param aggregateId the account identifier, exactly {@value #AGGREGATE_ID_LENGTH} decimal
     *                    digits with a leading zero kept
     * @throws IllegalArgumentException when the argument is absent or is not
     *                                 {@value #AGGREGATE_ID_LENGTH} decimal digits
     */
    public void setAggregateId(String aggregateId) {
        this.aggregateId = requireAccountIdentifier(aggregateId);
    }

    public boolean isPublished() {
        return published;
    }

    /**
     * Returns the instant this event was written.
     *
     * @return the value of {@code created_at}
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Sets the instant this event was written.
     *
     * @param createdAt the instant this event was written
     * @throws IllegalArgumentException when the argument is absent
     */
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = requireInstant(createdAt, "createdAt");
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    /**
     * Returns the root correlation identifier this row travels under.
     *
     * @return the value of {@code correlation_id}, or {@code null} for a row written outside a
     *         request and outside a delivery
     */
    public UUID getCorrelationId() {
        return correlationId;
    }

    /**
     * Returns the identifier of the event whose handling wrote this row.
     *
     * @return the value of {@code causation_id}, or {@code null} where a caller rather than an event
     *         asked for the change
     */
    public UUID getCausationId() {
        return causationId;
    }

    /**
     * Records the two correlation identifiers of the unit of work that wrote this row.
     *
     * <p>Called by {@code outbox/OutboxWriter} straight after construction, which is the one place
     * that knows them. Either may be {@code null}, and an absent identifier contributes no record
     * header.
     *
     * @param correlationId the root correlation identifier, or {@code null}
     * @param causationId   the identifier of the causing event, or {@code null}
     */
    public void recordCorrelation(UUID correlationId, UUID causationId) {
        this.correlationId = correlationId;
        this.causationId = causationId;
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
     * Describes this row by its routing and state columns.
     *
     * <p>A rendering reaches a log line, an exception message or a debugger view without a caller
     * intending it. The payload, the account identifier and both timestamps therefore stay out of
     * the text. The event identifier locates the row, and it names no account and no person.</p>
     *
     * <p>The aggregate identifier is an account identifier, so it appears as
     * {@link EventEnvelope#WITHHELD} rather than as its value. That marker is the platform-wide
     * redaction marker. The event identifier, the event type and the two publication columns stay,
     * because a reader tracing one row through the relay needs them.</p>
     *
     * @return the routing and state columns of this row, with the aggregate identifier withheld
     */
    @Override
    public String toString() {
        return "OutboxEventEntity{eventId=" + eventId
                + ", eventType=" + eventType
                + ", aggregateId=" + EventEnvelope.WITHHELD
                + ", published=" + published
                + ", createdAt=" + createdAt
                + ", publishedAt=" + publishedAt + "}";
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
     * Returns an event identifier that is present.
     *
     * @param value the identifier to check
     * @return the supplied identifier
     * @throws IllegalArgumentException when the identifier is absent
     */
    private static UUID requireEventId(UUID value) {
        if (value == null) {
            throw new IllegalArgumentException("eventId is required");
        }
        return value;
    }

    /**
     * Returns an account identifier that fits {@code CHAR(11)}.
     *
     * <p>The check is the pattern rather than a numeric range, because the column stores text and
     * a leading zero belongs to the value.
     *
     * @param value the identifier to check
     * @return the supplied identifier
     * @throws IllegalArgumentException when the identifier is absent or is not
     *                                 {@value #AGGREGATE_ID_LENGTH} decimal digits
     */
    private static String requireAccountIdentifier(String value) {
        if (value == null) {
            throw new IllegalArgumentException("aggregateId is required");
        }
        if (!AGGREGATE_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("aggregateId holds " + AGGREGATE_ID_LENGTH
                    + " decimal digits, received a value of " + value.length() + " characters");
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

    // ------------------------------------------------------------------------------------
    // Relay state. No COBOL ancestor: the source's one asynchronous handoff is the transient data
    // queue write at app/cbl/CORPT00C.cbl:L517, which carries no lease. The two enums, the five
    // constants, the nine columns and the four operations below hold three invariants. A row is
    // claimed by at most one relay instance, so one event is published once. A row carries an
    // attempt count and a next-attempt time, so an undeliverable row is abandoned rather than
    // retried forever ahead of the rows behind it. An abandoned row carries a durable obligation to
    // name itself on the dead-letter topic, so giving up on an event is not the same as losing it.
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
     * How one abandoned row stands with its terminal diagnostic.
     *
     * <p>{@link RelayState#ABANDONED} is terminal and the claim query does not return an abandoned
     * row, so the moment the relay gives up is the last moment it looks at the row. Publishing the
     * diagnostic inside that moment and hoping it lands is what loses it: a broker that refuses one
     * dead letter leaves the row terminal, unpublished, and named nowhere.
     *
     * <p>These three values are the obligation, recorded in the same transaction as the
     * abandonment. {@link #REQUIRED} is durable and outlives a broker outage, a process restart and
     * a redeployment, and the relay retries it on later sweeps until the broker acknowledges it.
     * That is the one thing separating an abandoned row from a lost one.
     *
     * <p>The names are stored as text in {@code dead_letter_state VARCHAR(16)}, which a check
     * constraint in {@code src/main/resources/db/migration/V5__outbox_dead_letter_state.sql}
     * restricts to exactly these three.
     */
    public enum DeadLetterState {

        /** No diagnostic is owed. Every row starts here and a published row stays here. */
        NOT_REQUIRED,

        /** A diagnostic is owed and the broker has not acknowledged one. Retried every sweep. */
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
     * to {@link RelayState#ABANDONED} and the relay attempts it no more. Either way the claim is
     * released, so a relay instance that dies mid-attempt does not strand the row.
     *
     * <p>The attempt that abandons a row also records the obligation to name it on the dead-letter
     * topic, as {@link DeadLetterState#REQUIRED}. Both facts are written here, in one call, so they
     * commit in one transaction: an abandoned row that owed nothing would be a lost event, and a
     * row owing a diagnostic that was never abandoned would be published twice.
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
     * Answers whether this row owes a terminal diagnostic no broker has acknowledged.
     *
     * <p>The relay reads this on every sweep for every abandoned row, and republishes while it
     * answers true. A row answers true only after {@link #recordFailure(String, Instant, Instant)}
     * abandoned it, and stops answering true once
     * {@link #markDeadLetterPublished(Instant)} records the acknowledgement.
     *
     * @return true while a diagnostic is owed
     */
    public boolean owesDeadLetter() {
        return deadLetterState == DeadLetterState.REQUIRED;
    }

    /**
     * Records that the broker acknowledged this row's terminal diagnostic.
     *
     * <p>Called after the acknowledgement and never before it. The obligation is what survives a
     * broker outage, so clearing it on the attempt rather than on the acknowledgement would lose
     * exactly the diagnostic this state exists to keep.
     *
     * @param publishedAt when the broker acknowledged the diagnostic
     * @throws NullPointerException  when {@code publishedAt} is null
     * @throws IllegalStateException when this row owes no diagnostic, which means either that no
     *                               diagnostic was ever owed or that one was already acknowledged
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

    /**
     * Returns a claim this relay took and never attempted to {@link RelayState#PENDING}.
     *
     * <p>A pass claims a batch and can reach its deadline with rows of that batch still unattempted.
     * Those rows carry a live claim and no failure, so the claim query passes over them and the only
     * thing that frees them is the claim timeout: one whole aggregate then waits for a lease to
     * expire rather than for a retry. This method is what a pass calls instead, so the wait is
     * bounded by the backoff of a real failure and by nothing else.
     *
     * <p>Nothing here records an attempt. {@code attemptCount}, {@code lastAttemptAt} and
     * {@code lastError} are left exactly as the claim found them, because no send was issued, and
     * {@code nextAttemptAt} is left alone as well: the row was due when it was claimed, so it is due
     * again the moment the claim is released. That is the difference from
     * {@link #recordFailure(String, Instant, Instant)}, which exists for a row that was attempted
     * and answers by spending one of its attempts.
     *
     * @throws IllegalStateException if this row holds no claim
     */
    public void releaseUnattemptedClaim() {
        if (relayState != RelayState.CLAIMED) {
            throw new IllegalStateException("a " + relayState + " row holds no claim to release");
        }
        this.claimedBy = null;
        this.claimedAt = null;
        this.relayState = RelayState.PENDING;
    }
}
