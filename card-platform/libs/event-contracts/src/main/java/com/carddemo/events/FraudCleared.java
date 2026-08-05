package com.carddemo.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The event the fraud detection service publishes when its risk rules clear an authorized
 * transaction.
 *
 * <p>No COBOL program and no copybook defines this event: the CardDemo source assesses no risk,
 * so nothing here is a translation. Two widths are borrowed.
 * {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5} gives {@code transactionId} sixteen
 * characters, and {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} gives
 * {@code accountId} eleven digits. Both locators are width borrowings, and neither is provenance.
 *
 * <p>Three payload components sit beside the five components of {@link EventEnvelope}, and the wire
 * form is flat. A serialized event holds all eight fields in one JavaScript Object Notation (JSON)
 * object, so no {@code envelope} key reaches a topic. The document
 * {@code schemas/fraud-cleared-v1.json} names the same eight in its {@code required} array and is
 * the contract this record matches. Evolution is additive: a later version may add a component, and
 * no component of this version changes shape.
 *
 * <p>{@code FraudFlagged} and {@code FraudCleared} share the {@code fraud.assessed} topic. A
 * consumer reads {@code eventType} to learn which payload arrived, so the canonical constructor
 * accepts {@link #EVENT_TYPE} and rejects every other value. The notification service consumes that
 * topic under the {@code notification-fraud} consumer group, in
 * {@code com.carddemo.notification.messaging.FraudFlaggedConsumer}, and routes on
 * {@code eventType}: a flagged assessment renders a cardholder alert and a cleared one records the
 * outcome without one.
 *
 * <p>The fraud detection service reads the authorization event and publishes this one. That
 * service never sits in the authorization response path and calls no other consumer.
 *
 * @param eventId       the idempotency key, as {@link EventEnvelope} defines it. A consumer records
 *                      it in its own marker table inside the same local transaction as its side
 *                      effects, marker after effects
 * @param eventType     the routing discriminator, always {@link #EVENT_TYPE}
 * @param schemaVersion the contract version, always {@link EventEnvelope#SCHEMA_VERSION}
 * @param occurredAt    the moment the fraud detection service wrote the event, in Coordinated
 *                      Universal Time
 * @param aggregateId   the eleven-digit account identifier, and the Kafka message key
 * @param transactionId the identifier of the transaction the assessment cleared, holding
 *                      {@link #TRANSACTION_ID_LENGTH} characters
 * @param accountId     the eleven-digit account identifier the transaction belongs to. Leading
 *                      zeros belong to the value
 * @param assessedAt    the moment the assessment finished, in Coordinated Universal Time. No
 *                      source field carries it
 */
public record FraudCleared(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
        String aggregateId, String transactionId, String accountId, Instant assessedAt) {

    /**
     * The value {@code eventType} carries on every instance, and the routing discriminator on the
     * shared {@code fraud.assessed} topic.
     *
     * <p>The schema document pins the same value with {@code "const"}, and the canonical
     * constructor accepts no other. {@link #of(String, String, Instant)} stamps it.
     */
    public static final String EVENT_TYPE = "FraudCleared";

    /**
     * The exact character count of {@code transactionId}: sixteen.
     *
     * <p>Width borrowed from {@code TRAN-ID PIC X(16)}. The same count bounds
     * {@code transactionId} in the schema document, as both {@code minLength} and
     * {@code maxLength}.
     */
    public static final int TRANSACTION_ID_LENGTH = 16;

    /**
     * The form {@code accountId} takes: exactly eleven decimal digits.
     *
     * <p>Width borrowed from {@code XREF-ACCT-ID PIC 9(11)}. The value comes from
     * {@link EventEnvelope#AGGREGATE_ID_PATTERN}, so one pattern checks the payload identifier and
     * the Kafka message key.
     */
    public static final String ACCOUNT_ID_PATTERN = EventEnvelope.AGGREGATE_ID_PATTERN;

    /** {@link #ACCOUNT_ID_PATTERN} compiled once, and the check {@link #requireAccountId} runs. */
    private static final Pattern ACCOUNT_ID_MATCHER = Pattern.compile(ACCOUNT_ID_PATTERN);

    /**
     * Checks all eight components and rejects a value the schema document rejects.
     *
     * <p>The five envelope components pass through the checks of {@link EventEnvelope}, so this
     * record repeats none of them. Beyond those, {@code eventType} must equal {@link #EVENT_TYPE},
     * {@code transactionId} must hold {@link #TRANSACTION_ID_LENGTH} characters, {@code accountId}
     * must match {@link #ACCOUNT_ID_PATTERN} and must equal {@code aggregateId}, and
     * {@code assessedAt} must be present. Holding the two account identifiers equal keeps the Kafka
     * message key and the payload naming one account, as {@code TransactionDeclined} and
     * {@code TransactionPosted} do. Every failure message names the component that failed, and a
     * message about an identifier reports its length rather than its value.
     *
     * <p>This constructor changes no value it accepts. Every component therefore survives a
     * serialize and deserialize round trip unchanged, down to the fractional digits of the two
     * timestamps.
     *
     * @throws NullPointerException     when {@code eventId}, {@code eventType}, {@code occurredAt}
     *                                  or {@code assessedAt} is {@code null}
     * @throws IllegalArgumentException when {@code eventType} is not {@link #EVENT_TYPE}, when
     *                                  {@code schemaVersion} is not
     *                                  {@link EventEnvelope#SCHEMA_VERSION}, when
     *                                  {@code aggregateId} or {@code accountId} is not eleven
     *                                  decimal digits, when the two account identifiers differ, or
     *                                  when {@code transactionId} does not hold
     *                                  {@link #TRANSACTION_ID_LENGTH} characters
     */
    public FraudCleared {
        new EventEnvelope(eventId, eventType, schemaVersion, occurredAt, aggregateId);

        if (!EVENT_TYPE.equals(eventType)) {
            throw new IllegalArgumentException("eventType must be \"" + EVENT_TYPE
                    + "\" and the supplied value is \"" + eventType + "\"");
        }
        requireTransactionId(transactionId);
        requireAccountId(accountId);
        if (!accountId.equals(aggregateId)) {
            throw new IllegalArgumentException("accountId must equal aggregateId, which is the "
                    + "Kafka message key, and the two supplied values differ");
        }
        Objects.requireNonNull(assessedAt, "assessedAt must be present");
    }

    /**
     * Builds an event from a carrier envelope and the three payload components.
     *
     * <p>The envelope supplies the five components the flat wire form carries beside the payload.
     * A producer that already holds an envelope uses this constructor;
     * {@link #of(String, String, Instant)} builds one instead.
     *
     * @param envelope      the five envelope components, carrying {@link #EVENT_TYPE} as its event
     *                      type
     * @param transactionId the identifier of the transaction the assessment cleared, holding
     *                      {@link #TRANSACTION_ID_LENGTH} characters
     * @param accountId     the eleven-digit account identifier the transaction belongs to
     * @param assessedAt    the moment the assessment finished, in Coordinated Universal Time
     * @throws NullPointerException     when {@code envelope} or {@code assessedAt} is {@code null},
     *                                  or when the envelope carries a {@code null} component
     * @throws IllegalArgumentException when a component fails a check the canonical constructor
     *                                  runs
     */
    public FraudCleared(EventEnvelope envelope, String transactionId, String accountId,
            Instant assessedAt) {
        this(Objects.requireNonNull(envelope, "envelope must be present").eventId(),
                envelope.eventType(), envelope.schemaVersion(), envelope.occurredAt(),
                envelope.aggregateId(), transactionId, accountId, assessedAt);
    }

    /**
     * Builds an event, stamping its envelope through {@link EventEnvelope#of(String, String)}.
     *
     * <p>The stamped envelope takes {@link #EVENT_TYPE} as its event type and {@code accountId} as
     * its aggregate identifier. The Kafka message key and the payload identifier therefore carry
     * one account.
     *
     * @param transactionId the identifier of the transaction the assessment cleared, holding
     *                      {@link #TRANSACTION_ID_LENGTH} characters
     * @param accountId     the eleven-digit account identifier the transaction belongs to
     * @param assessedAt    the moment the assessment finished, in Coordinated Universal Time
     * @return an event carrying the three supplied components and a stamped envelope
     * @throws NullPointerException     when {@code assessedAt} is {@code null}
     * @throws IllegalArgumentException when {@code transactionId} does not hold
     *                                  {@link #TRANSACTION_ID_LENGTH} characters, or when
     *                                  {@code accountId} is {@code null} or is not eleven decimal
     *                                  digits
     */
    public static FraudCleared of(String transactionId, String accountId, Instant assessedAt) {
        return new FraudCleared(EventEnvelope.of(EVENT_TYPE, requireAccountId(accountId)),
                transactionId, accountId, assessedAt);
    }

    /**
     * The five envelope components of this event, gathered into an {@link EventEnvelope}.
     *
     * <p>Serialization ignores this method. The wire form stays flat and carries no
     * {@code envelope} key.
     *
     * @return an envelope carrying this event's identifier, type, contract version, publish time
     *         and account identifier
     */
    public EventEnvelope envelope() {
        return new EventEnvelope(eventId, eventType, schemaVersion, occurredAt, aggregateId);
    }

    /**
     * Checks that a transaction identifier holds {@link #TRANSACTION_ID_LENGTH} characters.
     *
     * <p>The borrowed source field is alphanumeric, so the check counts characters and reads none
     * of them. The failure text reports the length received.
     *
     * @param transactionId the value to check
     * @return {@code transactionId}, unchanged
     * @throws IllegalArgumentException when {@code transactionId} is {@code null} or holds another
     *                                  number of characters
     */
    private static String requireTransactionId(String transactionId) {
        if (transactionId == null || transactionId.length() != TRANSACTION_ID_LENGTH) {
            throw new IllegalArgumentException("transactionId must hold " + TRANSACTION_ID_LENGTH
                    + " characters and the supplied value " + (transactionId == null ? "is null"
                            : "holds " + transactionId.length() + " characters"));
        }
        return transactionId;
    }

    /**
     * Checks that an account identifier matches {@link #ACCOUNT_ID_PATTERN}.
     *
     * <p>{@link #of(String, String, Instant)} calls this method before it stamps an envelope, so a
     * rejected value names {@code accountId} rather than the envelope component built from it. The
     * failure text reports the length received and never the value, so no account identifier
     * reaches a log through a failure.
     *
     * @param accountId the value to check
     * @return {@code accountId}, unchanged
     * @throws IllegalArgumentException when {@code accountId} is {@code null} or is not eleven
     *                                  decimal digits
     */
    private static String requireAccountId(String accountId) {
        if (accountId == null || !ACCOUNT_ID_MATCHER.matcher(accountId).matches()) {
            throw new IllegalArgumentException("accountId must match " + ACCOUNT_ID_PATTERN
                    + " and the supplied value " + (accountId == null ? "is null"
                            : "holds " + accountId.length() + " characters"));
        }
        return accountId;
    }
    /**
     * Renders the technical identifiers and withholds the account identifier.
     *
     * <p>This override replaces the representation the compiler generates for a record. That
     * generated form prints the account identifier twice, once as {@code aggregateId} and once as
     * {@code accountId}.
     *
     * @return the identifiers of this event with the account identifier withheld, never
     *         {@code null}
     */
    @Override
    public String toString() {
        return "FraudCleared[eventId=" + eventId + ", eventType=" + eventType + ", schemaVersion="
                + schemaVersion + ", occurredAt=" + occurredAt + ", aggregateId="
                + EventEnvelope.WITHHELD + ", transactionId=" + transactionId + ", accountId="
                + EventEnvelope.WITHHELD + ", assessedAt=" + assessedAt + "]";
    }
}
