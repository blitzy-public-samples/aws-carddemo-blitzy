package com.carddemo.events;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The five fields every event in this module carries beside its own payload fields.
 *
 * <p>No COBOL program and no copybook defines this record. One borrowed width:
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} gives {@code aggregateId}
 * eleven digits.
 *
 * <p>The wire form is flat. A serialized event holds these five fields and its payload fields in
 * one JavaScript Object Notation (JSON) object, so no {@code envelope} key reaches a topic. Every
 * document under {@code card-platform/libs/event-contracts/src/main/resources/schemas} names all
 * five in its {@code required} array, beside the payload names.
 *
 * <p>A consumer routes and deduplicates on these five fields and parses no payload to do it. Each
 * consumer claims a row in its own {@code processed_event} table before it acts, keyed by
 * {@code (event_id, consumed_topic)} rather than by {@code eventId} alone: one topic's delivery of
 * an identifier is a different delivery from another topic's, and several groups share one table. It
 * then writes its side effects and the marker row in one local transaction. It acknowledges the
 * message only after that transaction commits, so a duplicate delivery changes nothing and a crash
 * part-way through loses no work. {@code eventType} tells a consumer which payload arrived, which
 * the {@code fraud.assessed} topic needs: {@code FraudFlagged} and {@code FraudCleared} both
 * travel there.
 *
 * <p>{@code aggregateId} is always the Kafka message key. Kafka orders messages within one
 * partition, so every event for one account lands on one partition and arrives in publish order.
 * The key carries the account identifier on every contract but one. A declined event whose account
 * identity the card cross-reference could not resolve is keyed on its transaction identifier
 * instead. Reject reason {@code 0100} at {@code app/cbl/CBTRN02C.cbl:L385-L387} fires exactly when
 * that lookup misses, so no authoritative account identifier exists to key on.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param eventId       the idempotency key, a Universally Unique Identifier (UUID) that serializes
 *                      as thirty-six lower-case characters
 * @param eventType     the routing discriminator, and the simple name of the event type this
 *                      envelope labels, for example {@code TransactionAuthorized}
 * @param schemaVersion the contract version, from {@link #SCHEMA_VERSION} through
 *                      {@link #MAX_SCHEMA_VERSION}
 * @param occurredAt    the moment the producer wrote the event, serialized in Coordinated
 *                      Universal Time with seconds and up to nine fractional digits
 * @param aggregateId   the Kafka message key, in one of two forms: the eleven-digit account
 *                      identifier the event belongs to, or, where no account was resolved, the
 *                      sixteen-character transaction identifier. Leading zeros belong to the value
 */
public record EventEnvelope(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
        String aggregateId) {

    /**
     * The contract version a producer stamps unless it names another, and the version every
     * {@code -v1.json} document of this module pins with {@code "const": 1}.
     *
     * <p>{@link #of(String, String)} stamps this constant. A producer whose current contract is a
     * later version calls {@link #of(String, String, int)} with the version its record declares.
     */
    public static final int SCHEMA_VERSION = 1;

    /**
     * The highest contract version this module ships a schema document for.
     *
     * <p>The canonical constructor accepts any version from {@link #SCHEMA_VERSION} through this
     * one. {@code com.carddemo.events.serde.EventSchemas} decides which pairs of event type and
     * version exist. A pair with no document is refused at the serialize and deserialize gates, so
     * widening the range here admits nothing on its own.
     *
     * <p>Version 3 exists for one contract, {@code schemas/transaction-declined-v3.json}. Each event
     * type numbers its own contracts, so this constant is the widest number any of them uses rather
     * than a version every type ships.
     */
    public static final int MAX_SCHEMA_VERSION = 3;

    /**
     * The form {@code aggregateId} takes: exactly eleven decimal digits, the account identifier.
     *
     * <p>Width from {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. Every
     * account-keyed schema constrains {@code aggregateId} to this form.
     */
    public static final String AGGREGATE_ID_PATTERN = "^[0-9]{11}$";

    /**
     * The form {@code aggregateId} takes when no account identifier exists to key on: sixteen
     * printable characters, none of them a space.
     *
     * <p>Width from {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5} and
     * {@code DALYTRAN-ID} at {@code app/cpy/CVTRA06Y.cpy:L5}. A transaction identifier is the one
     * key available before the cross-reference resolves, and it is deterministic. It names no
     * cardholder, no account and no card, so it discloses nothing a masked event does not already
     * carry.
     *
     * <p>The two forms cannot be confused: eleven characters against sixteen, and this one admits
     * no value the account form admits.
     */
    public static final String UNRESOLVED_AGGREGATE_KEY_PATTERN = "^[!-~]{16}$";

    /**
     * The two forms {@code aggregateId} may take, and the check the canonical constructor runs.
     *
     * <p>A schema document constrains the one form its own contract allows, which is narrower than
     * this union in every case. This constant exists so one record type can carry both an account
     * key and an unresolved key without a second envelope type.
     */
    public static final String AGGREGATE_KEY_PATTERN =
            "^(?:[0-9]{11}|[!-~]{16})$";

    /** {@link #AGGREGATE_ID_PATTERN} compiled, and the check for the account form. */
    private static final Pattern AGGREGATE_ID_MATCHER = Pattern.compile(AGGREGATE_ID_PATTERN);

    /** {@link #AGGREGATE_KEY_PATTERN} compiled, and the check the canonical constructor runs. */
    private static final Pattern AGGREGATE_KEY_MATCHER = Pattern.compile(AGGREGATE_KEY_PATTERN);

    /** {@link #UNRESOLVED_AGGREGATE_KEY_PATTERN} compiled, and the check for the transaction form. */
    private static final Pattern UNRESOLVED_AGGREGATE_KEY_MATCHER =
            Pattern.compile(UNRESOLVED_AGGREGATE_KEY_PATTERN);

    /**
     * Checks all five components and rejects a value no schema document accepts.
     *
     * <p>Every exception message names the component that failed. The three reference components
     * must be present, {@code eventType} must hold one non-blank character, {@code schemaVersion}
     * must fall between {@link #SCHEMA_VERSION} and {@link #MAX_SCHEMA_VERSION}, and
     * {@code aggregateId} must match {@link #AGGREGATE_ID_PATTERN}. A message reports the length
     * of a rejected {@code aggregateId} and never the value, so no account identifier reaches a log
     * through a failure.
     *
     * <p>Accepting either key form here does not let an event type choose one. A record checks the
     * form its own contract allows, and its schema document constrains the same form on the wire.
     * Only the declined event of version 2 reaches a topic keyed on a transaction identifier.
     *
     * <p>This constructor changes no value it accepts. A component therefore survives a serialize
     * and deserialize round trip unchanged, down to the fractional digits of {@code occurredAt}.
     *
     * @throws NullPointerException     when {@code eventId}, {@code eventType} or
     *                                  {@code occurredAt} is {@code null}
     * @throws IllegalArgumentException when {@code eventType} is blank, when {@code schemaVersion}
     *                                  falls outside {@link #SCHEMA_VERSION} through
     *                                  {@link #MAX_SCHEMA_VERSION}, or when {@code aggregateId} is
     *                                  {@code null} or is not eleven decimal digits
     */
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId must be present");
        Objects.requireNonNull(eventType, "eventType must be present");
        Objects.requireNonNull(occurredAt, "occurredAt must be present");

        if (eventType.isBlank()) {
            throw new IllegalArgumentException(
                    "eventType must name an event type and the supplied value is blank");
        }
        if (schemaVersion < SCHEMA_VERSION || schemaVersion > MAX_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be between " + SCHEMA_VERSION
                    + " and " + MAX_SCHEMA_VERSION + " and the supplied value is " + schemaVersion);
        }
        if (aggregateId == null || !AGGREGATE_KEY_MATCHER.matcher(aggregateId).matches()) {
            throw new IllegalArgumentException("aggregateId must match " + AGGREGATE_KEY_PATTERN
                    + " and the supplied value " + (aggregateId == null ? "is null"
                            : "holds " + aggregateId.length() + " characters"));
        }
    }

    /**
     * Reports whether {@code aggregateId} carries an account identifier rather than an unresolved
     * key.
     *
     * <p>A record uses this to check that the key form matches the contract it publishes under.
     *
     * @return {@code true} when {@code aggregateId} is eleven decimal digits
     */
    public boolean carriesAccountKey() {
        return AGGREGATE_ID_MATCHER.matcher(aggregateId).matches();
    }

    /**
     * Reports whether {@code aggregateId} carries a transaction identifier because no account
     * identifier existed to key on.
     *
     * <p>This is the companion of {@link #carriesAccountKey()}, and exactly one of the two answers
     * {@code true} for any envelope the canonical constructor accepted: the two patterns admit
     * different widths and share no value. A producer uses this to check that the key form matches
     * the contract it publishes under, and a store uses it to check the key against its column.
     *
     * @return {@code true} when {@code aggregateId} is sixteen printable characters
     */
    public boolean carriesTransactionKey() {
        return UNRESOLVED_AGGREGATE_KEY_MATCHER.matcher(aggregateId).matches();
    }

    /**
     * Builds an envelope, stamping the three components a producer never chooses by hand.
     *
     * <p>{@code eventId} takes a fresh {@link UUID#randomUUID()} value, {@code schemaVersion} takes
     * {@link #SCHEMA_VERSION}, and {@code occurredAt} takes the current moment truncated to
     * milliseconds. The serialized timestamp then carries three fractional digits.
     *
     * @param eventType   the simple name of the event type this envelope labels, for example
     *                    {@code TransactionPosted}
     * @param aggregateId the eleven-digit account identifier the event belongs to, and the Kafka
     *                    message key
     * @return an envelope carrying the two supplied components and three stamped ones
     * @throws NullPointerException     when {@code eventType} is {@code null}
     * @throws IllegalArgumentException when {@code eventType} is blank, or when
     *                                  {@code aggregateId} is {@code null} or is not eleven
     *                                  decimal digits
     */
    public static EventEnvelope of(String eventType, String aggregateId) {
        return of(eventType, aggregateId, SCHEMA_VERSION);
    }

    /**
     * Builds an envelope under a named contract version, and the target
     * {@link #of(String, String)} delegates to.
     *
     * <p>{@code eventId} and {@code occurredAt} are stamped exactly as
     * {@link #of(String, String)} stamps them. A version outside {@link #SCHEMA_VERSION} through
     * {@link #MAX_SCHEMA_VERSION} is refused by the canonical constructor.
     *
     * @param eventType     the simple name of the event type this envelope labels
     * @param aggregateId   the Kafka message key: the eleven-digit account identifier the event
     *                      belongs to
     * @param schemaVersion the contract version, between {@link #SCHEMA_VERSION} and
     *                      {@link #MAX_SCHEMA_VERSION}
     * @return an envelope carrying the three supplied components and two stamped ones
     * @throws NullPointerException     when {@code eventType} is {@code null}
     * @throws IllegalArgumentException when a component fails a check of the canonical constructor
     */
    public static EventEnvelope of(String eventType, String aggregateId, int schemaVersion) {
        return new EventEnvelope(UUID.randomUUID(), eventType, schemaVersion,
                Instant.now().truncatedTo(ChronoUnit.MILLIS), aggregateId);
    }

    /**
     * Stands in for a value a renderer withholds, and the one marker every event record uses.
     *
     * <p>Every record of this module renders its technical identifiers and replaces every account
     * identifier, monetary amount, balance, merchant value and rule list with this marker. A reader
     * of a log line can therefore correlate an event without reading the data it carries. The
     * records of the six services use the same marker for the same purpose.
     */
    public static final String WITHHELD = "<withheld>";

    /**
     * Renders the four technical components and withholds the account identifier.
     *
     * <p>This override replaces the representation the compiler generates for a record. That
     * generated form prints {@code aggregateId}, which is an account identifier. A log line, an
     * assertion failure, a debugger view and the message of an exception that interpolates an
     * envelope would each persist it.
     *
     * @return the identifiers of this envelope with the account identifier withheld, never
     *         {@code null}
     */
    @Override
    public String toString() {
        return "EventEnvelope[eventId=" + eventId + ", eventType=" + eventType + ", schemaVersion="
                + schemaVersion + ", occurredAt=" + occurredAt + ", aggregateId=" + WITHHELD + "]";
    }
}
