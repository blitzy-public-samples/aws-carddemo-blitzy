package com.carddemo.events;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The five fields every event in this module carries beside its own payload fields.
 *
 * <p>ADDITIVE IN FULL. No COBOL program and no copybook defines this record. One borrowed width:
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} gives {@code aggregateId}
 * eleven digits.
 *
 * <p>The wire form is flat. A serialized event holds these five fields and its payload fields in
 * one JavaScript Object Notation (JSON) object, so no {@code envelope} key reaches a topic. Every
 * document under {@code card-platform/libs/event-contracts/src/main/resources/schemas} names all
 * five in its {@code required} array, beside the payload names.
 *
 * <p>A consumer routes and deduplicates on these five fields and parses no payload to do it. Each
 * consumer records {@code eventId} in its own {@code processed_event} table before it applies side
 * effects, so a duplicate delivery changes nothing. {@code eventType} tells a consumer which
 * payload arrived, which the {@code fraud.assessed} topic needs: {@code FraudFlagged} and
 * {@code FraudCleared} both travel there.
 *
 * <p>{@code aggregateId} carries the account identifier and is always the Kafka message key. Kafka
 * orders messages within one partition, so every event for one account lands on one partition and
 * arrives in publish order. For the path each event travels from publish to consume, read
 * {@code card-platform/docs/event-flow.md}; for the reasoning behind the choices above, read
 * {@code card-platform/docs/decision-log.md}.
 *
 * @param eventId       the idempotency key, a Universally Unique Identifier (UUID) that serializes
 *                      as thirty-six lower-case characters
 * @param eventType     the routing discriminator, and the simple name of the event type this
 *                      envelope labels, for example {@code TransactionAuthorized}
 * @param schemaVersion the contract version, always {@link #SCHEMA_VERSION}
 * @param occurredAt    the moment the producer wrote the event, serialized in Coordinated
 *                      Universal Time with seconds and up to nine fractional digits
 * @param aggregateId   the eleven-digit account identifier the event belongs to, and the Kafka
 *                      message key. Leading zeros belong to the value
 */
public record EventEnvelope(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
        String aggregateId) {

    /**
     * The contract version every schema document of this module pins with {@code "const": 1}.
     *
     * <p>The same number names the document: version 1 lives in the {@code -v1.json} files.
     * {@link #of(String, String)} stamps this constant, and the canonical constructor accepts no
     * other value.
     */
    public static final int SCHEMA_VERSION = 1;

    /**
     * The form {@code aggregateId} takes: exactly eleven decimal digits.
     *
     * <p>Width from {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. The same
     * pattern constrains {@code aggregateId} in every schema document of this module.
     */
    public static final String AGGREGATE_ID_PATTERN = "^[0-9]{11}$";

    /** {@link #AGGREGATE_ID_PATTERN} compiled, and the check the canonical constructor runs. */
    private static final Pattern AGGREGATE_ID_MATCHER = Pattern.compile(AGGREGATE_ID_PATTERN);

    /**
     * Checks all five components and rejects a value no schema document accepts.
     *
     * <p>Every exception message names the component that failed. The three reference components
     * must be present, {@code eventType} must hold one non-blank character, {@code schemaVersion}
     * must equal {@link #SCHEMA_VERSION}, and {@code aggregateId} must match
     * {@link #AGGREGATE_ID_PATTERN}. A message reports the length of a rejected
     * {@code aggregateId} and never the value, so no account identifier reaches a log through a
     * failure.
     *
     * <p>This constructor changes no value it accepts. A component therefore survives a serialize
     * and deserialize round trip unchanged, down to the fractional digits of {@code occurredAt}.
     *
     * @throws NullPointerException     when {@code eventId}, {@code eventType} or
     *                                  {@code occurredAt} is {@code null}
     * @throws IllegalArgumentException when {@code eventType} is blank, when {@code schemaVersion}
     *                                  is not {@link #SCHEMA_VERSION}, or when
     *                                  {@code aggregateId} is {@code null} or is not eleven
     *                                  decimal digits
     */
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId must be present");
        Objects.requireNonNull(eventType, "eventType must be present");
        Objects.requireNonNull(occurredAt, "occurredAt must be present");

        if (eventType.isBlank()) {
            throw new IllegalArgumentException(
                    "eventType must name an event type and the supplied value is blank");
        }
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION
                    + " and the supplied value is " + schemaVersion);
        }
        if (aggregateId == null || !AGGREGATE_ID_MATCHER.matcher(aggregateId).matches()) {
            throw new IllegalArgumentException("aggregateId must match " + AGGREGATE_ID_PATTERN
                    + " and the supplied value " + (aggregateId == null ? "is null"
                            : "holds " + aggregateId.length() + " characters"));
        }
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
        return new EventEnvelope(UUID.randomUUID(), eventType, SCHEMA_VERSION,
                Instant.now().truncatedTo(ChronoUnit.MILLIS), aggregateId);
    }
}
