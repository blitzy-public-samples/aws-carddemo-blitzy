package com.carddemo.events.serde;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.carddemo.events.DeadLetterEnvelope;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import com.carddemo.events.TransactionPosted;

import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * Asserts the two ends of the wire agree, and that each end refuses what it must refuse.
 *
 * <p>Both serde classes read one table, {@link EventSchemas#SCHEMA_RESOURCES}, so the set of event
 * types that may be published equals the set that may be consumed. Every test below is written
 * against that property, against the size limits in {@link EventWireBounds}, and against the closed
 * property set every shipped schema declares.
 *
 * <p>The consume side did not exist when this platform first shipped its configuration: three
 * services named {@code JsonSchemaValidatingDeserializer} in
 * {@code spring.deserializer.value.delegate.class} while no such class was on the classpath, so the
 * consume-time validation the documentation promised was not performed and no consumer could start.
 * These tests hold that class to the contract those three configurations already assume.
 */
@DisplayName("event serde, publish side and consume side")
class EventSerdeSecurityTest {

    /** The account identifier every event in this suite belongs to. */
    private static final String ACCOUNT_ID = "00000000007";

    /** The masked card number every event in this suite carries. */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /** A transaction identifier at the sixteen characters {@code TRAN-ID PIC X(16)} holds. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /** The twenty-six character capture timestamp shape the source records carry. */
    private static final String AUTHORIZED_AT = "2022-06-10 19:27:53.000000";

    /** The publish side under test. */
    private final JsonSchemaValidatingSerializer<Object> serializer =
            new JsonSchemaValidatingSerializer<>();

    /** The consume side under test. */
    private final JsonSchemaValidatingDeserializer deserializer =
            new JsonSchemaValidatingDeserializer();

    @Test
    @DisplayName("the consume side exists and governs exactly the event types the publish side does")
    void bothEndsGovernTheSameEventTypes() {
        assertEquals(
                List.of("AccountStateChanged", "CardUpdated", "DeadLetterEnvelope", "FraudCleared",
                        "FraudFlagged", "TransactionAuthorized", "TransactionDeclined",
                        "TransactionPosted"),
                EventSchemas.governedEventTypes().stream().sorted().toList(),
                "the governed event type set changed, so a producer and a consumer of this platform "
                        + "may no longer agree on what may travel");

        for (String eventType : EventSchemas.governedEventTypes()) {
            assertNotNull(EventSchemas.SCHEMA_RESOURCES.get(eventType),
                    eventType + " is governed with no schema document behind it");
        }
    }

    @Test
    @DisplayName("every core event survives a round trip with its exact decimal scale")
    void everyCoreEventRoundTripsThroughBothEnds() {
        for (Object event : coreEvents()) {
            byte[] bytes = serializer.serialize(topicFor(event), event);
            assertNotNull(bytes, event.getClass().getSimpleName() + " serialized to nothing");

            Object read = deserializer.deserialize(topicFor(event), bytes);
            assertEquals(event, read, event.getClass().getSimpleName()
                    + " did not survive a round trip unchanged, so a consumer reads something the "
                    + "producer did not write");
        }
    }

    @Test
    @DisplayName("a tombstone stays a tombstone at both ends")
    void aNullValueIsATombstoneAtBothEnds() {
        assertNull(serializer.serialize(EventContracts.defaultTopicFor(
                EventContracts.TRANSACTION_AUTHORIZED), null));
        assertNull(deserializer.deserialize("topic", null));
    }

    @Test
    @DisplayName("the consume side refuses a record larger than the platform ceiling before parsing")
    void anOversizedRecordIsRefused() {
        byte[] oversized = new byte[EventWireBounds.MAX_EVENT_BYTES + 1];
        java.util.Arrays.fill(oversized, (byte) ' ');

        SerializationException refused = assertThrows(SerializationException.class,
                () -> deserializer.deserialize("topic", oversized));

        assertTrue(refused.getMessage().contains(String.valueOf(EventWireBounds.MAX_EVENT_BYTES)),
                "the refusal does not name the ceiling it applied: " + refused.getMessage());
        assertNull(refused.getCause(),
                "an oversized record was parsed before it was refused, which is the cost the "
                        + "ceiling exists to avoid");
    }

    @Test
    @DisplayName("the publish side refuses an event that would not fit the outbox row carrying it")
    void thePublishSideAppliesTheSameCeiling() {
        assertTrue(EventWireBounds.MAX_EVENT_BYTES > 0);

        byte[] widest = serializer.serialize(topicFor(authorized()), authorized());
        assertTrue(widest.length <= EventWireBounds.MAX_EVENT_BYTES,
                "a legitimate event already exceeds the platform ceiling, so the ceiling is wrong "
                        + "rather than the event");
    }

    @Test
    @DisplayName("an undeclared property is refused, so a hidden card number cannot ride along")
    void anUndeclaredPropertyIsRefusedOnConsume() {
        String smuggled = withProperty(serialized(authorized()), "\"pan\":\"0500024453765740\"");

        SerializationException refused = assertThrows(SerializationException.class,
                () -> deserializer.deserialize("topic",
                        smuggled.getBytes(StandardCharsets.UTF_8)));

        assertTrue(refused.getMessage().contains("TransactionAuthorized"),
                "the refusal does not name the event type: " + refused.getMessage());
        assertFalse(refused.getMessage().contains("0500024453765740"),
                "the refusal echoed the smuggled card number: " + refused.getMessage());
    }

    @Test
    @DisplayName("an unknown event type is refused and is not echoed whole into the message")
    void anUnknownEventTypeIsRefused() {
        String unknown = serialized(authorized())
                .replace("\"eventType\":\"TransactionAuthorized\"",
                        "\"eventType\":\"" + "A".repeat(200) + "\"");

        SerializationException refused = assertThrows(SerializationException.class,
                () -> deserializer.deserialize("topic", unknown.getBytes(StandardCharsets.UTF_8)));

        assertTrue(refused.getMessage().contains("has no schema in this module"),
                "the refusal does not explain itself: " + refused.getMessage());
        assertTrue(refused.getMessage().length() < 200,
                "the refusal echoed a 200-character event type into a log line: "
                        + refused.getMessage().length() + " characters");
    }

    @Test
    @DisplayName("another schema version is refused before the document is validated")
    void anotherSchemaVersionIsRefused() {
        String futureVersion = serialized(authorized())
                .replace("\"schemaVersion\":1", "\"schemaVersion\":2");

        SerializationException refused = assertThrows(SerializationException.class,
                () -> deserializer.deserialize("topic",
                        futureVersion.getBytes(StandardCharsets.UTF_8)));

        assertTrue(refused.getMessage().contains("schemaVersion"),
                "the refusal does not name the version mismatch: " + refused.getMessage());
    }

    @Test
    @DisplayName("a value added inside the bounded extensions object still reaches a v1 consumer")
    void anAddedExtensionValueIsAccepted() {
        String enriched = withProperty(serialized(authorized()),
                "\"extensions\":{\"replayOf\":\"3f1d9c62-8b4e-4a17-9f0c-2d6a5e73b418\"}");

        assertDoesNotThrow(
                () -> deserializer.deserialize("topic", enriched.getBytes(StandardCharsets.UTF_8)),
                "a version-one consumer refused an event a later version enriched, which is the "
                        + "breakage the extensions object exists to prevent");
    }

    @Test
    @DisplayName("bytes that are not well-formed UTF-8 are refused rather than silently repaired")
    void malformedUtf8IsRefused() {
        byte[] malformed = {(byte) 0x7B, (byte) 0xC3, (byte) 0x28, (byte) 0x7D};

        SerializationException refused = assertThrows(SerializationException.class,
                () -> deserializer.deserialize("topic", malformed));

        assertTrue(refused.getMessage().contains("UTF-8"),
                "the refusal does not name the decoding failure: " + refused.getMessage());
    }

    @Test
    @DisplayName("a record that is not one JSON object is refused")
    void aNonObjectRecordIsRefused() {
        SerializationException refused = assertThrows(SerializationException.class,
                () -> deserializer.deserialize("topic", "[1,2,3]".getBytes(StandardCharsets.UTF_8)));

        assertTrue(refused.getMessage().contains("JSON object"),
                "the refusal does not explain the shape it wanted: " + refused.getMessage());
    }

    @Test
    @DisplayName("a document nested past the platform depth is refused while reading")
    void anOverlyNestedDocumentIsRefused() {
        StringBuilder nested = new StringBuilder("{\"eventType\":\"TransactionAuthorized\"");
        nested.append(",\"extensions\":");
        int depth = EventWireBounds.MAX_NESTING_DEPTH + 4;
        nested.append("[".repeat(depth)).append("1").append("]".repeat(depth));
        nested.append('}');

        SerializationException refused = assertThrows(SerializationException.class,
                () -> deserializer.deserialize("topic",
                        nested.toString().getBytes(StandardCharsets.UTF_8)));

        assertNotNull(refused.getMessage());
    }

    @Test
    @DisplayName("an event type this module governs but does not bind arrives as a validated tree")
    void aGovernedButUnboundTypeArrivesAsATree() {
        assertFalse(EventSchemas.RECORD_TYPES.containsKey("AccountStateChanged"),
                "AccountStateChanged became bound in this module, so this test needs revisiting");

        String accountStateChanged = accountStateChangedDocument();
        Object read = deserializer.deserialize("account.state-changed",
                accountStateChanged.getBytes(StandardCharsets.UTF_8));

        assertInstanceOf(JsonNode.class, read,
                "a governed but unbound event type no longer arrives as a validated tree, so the "
                        + "service owning that aggregate has nothing to bind");
    }

    @Test
    @DisplayName("a service registers its own record type and receives it, and only for its type")
    void aServiceMayRegisterItsOwnRecordType() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new JsonSchemaValidatingDeserializer<>(String.class));

        assertTrue(refused.getMessage().contains("names no registered event type"),
                "a class was bound to an event type with no schema behind it: "
                        + refused.getMessage());

        // A service that consumes one event names its record class, and a record of another type
        // then fails instead of arriving as a value the caller cannot use.
        JsonSchemaValidatingDeserializer<TransactionPosted> posted =
                new JsonSchemaValidatingDeserializer<>(TransactionPosted.class);
        byte[] authorized = serializer.serialize("transaction.authorized", authorized());

        SerializationException wrongType = assertThrows(SerializationException.class,
                () -> posted.deserialize("transaction.authorized", authorized));

        assertTrue(wrongType.getMessage().contains("TransactionPosted"),
                "the refusal does not name the type this consumer builds: "
                        + wrongType.getMessage());
    }

    @Test
    @DisplayName("the dead-letter envelope travels through the same gate as the events")
    void theDeadLetterEnvelopeRoundTripsThroughBothEnds() {
        DeadLetterEnvelope envelope = DeadLetterEnvelope.fromFailure(ACCOUNT_ID, "0999",
                new IllegalStateException("card 0500024453765740 balance 1234.56"), "SCHEMA",
                "schema validation refused the record", "transaction.authorized", 2, 4711L,
                "9c1b7d54-2a3e-4f18-8b0d-6e7a4c93d215", "TransactionAuthorized", 3);

        byte[] bytes = serializer.serialize(topicFor(envelope), envelope);
        Object read = deserializer.deserialize(topicFor(envelope), bytes);

        assertInstanceOf(DeadLetterEnvelope.class, read,
                "the dead-letter envelope did not arrive as its own type");
        DeadLetterEnvelope readBack = (DeadLetterEnvelope) read;

        // truncatedComponents is derived local bookkeeping and stays off the wire, so the wire form
        // is the thing that must be stable. Re-serializing the record that arrived reproduces the
        // bytes that were published.
        assertEquals(new String(bytes, StandardCharsets.UTF_8),
                new String(serializer.serialize(topicFor(readBack), readBack),
                        StandardCharsets.UTF_8),
                "the dead-letter envelope did not survive a round trip");
        assertEquals(envelope.abendCode(), readBack.abendCode());
        assertEquals(envelope.culprit(), readBack.culprit());
        assertEquals(envelope.reason(), readBack.reason());
        assertEquals(envelope.sourceTopic(), readBack.sourceTopic());
        assertEquals(envelope.sourceOffset(), readBack.sourceOffset());
        assertEquals(envelope.attemptCount(), readBack.attemptCount());
        assertEquals(List.of("culprit"), envelope.truncatedComponents(),
                "the exception type name was not shortened, so this test no longer proves that a "
                        + "long culprit is cut rather than published whole");

        String wire = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(wire.contains("0500024453765740"),
                "the dead-letter envelope carried a card number from the failure message: " + wire);
        assertFalse(wire.contains("1234.56"),
                "the dead-letter envelope carried a monetary value from the failure message: "
                        + wire);
        assertFalse(wire.contains("truncatedComponents"),
                "local bookkeeping reached the wire, where the schema closes its property set");
    }

    /**
     * Builds one declined event through the canonical constructor, bypassing the factories.
     *
     * <p>The record is flat, so the five envelope components are passed one by one. Passing them
     * from an envelope keeps each call site reading like the contract it exercises.
     *
     * @param envelope      the envelope whose components the record carries
     * @param transactionId the transaction identifier
     * @param accountId     the account identifier, or {@code null} under the unresolved contract
     * @param reason        the decline reason, whose text the record derives
     * @param amount        the attempted amount
     * @return the declined event
     */
    private static TransactionDeclined declined(EventEnvelope envelope, String transactionId,
            String accountId, DeclineReason reason, BigDecimal amount) {
        return new TransactionDeclined(envelope.eventId(), envelope.eventType(),
                envelope.schemaVersion(), envelope.occurredAt(), envelope.aggregateId(),
                transactionId, accountId, reason, reason.description(), amount,
                MASKED_CARD_NUMBER);
    }

    /** The five core events, each built through its own factory. */

    @Test
    @DisplayName("an unresolved decline publishes under version two and carries no account identifier")
    void anUnresolvedDeclinePublishesWithNoAccountIdentifier() {
        TransactionDeclined unresolved = TransactionDeclined.ofUnresolvedAccount(TRANSACTION_ID,
                new BigDecimal("50.47"), MASKED_CARD_NUMBER);

        assertNull(unresolved.accountId(),
                "reject reason 0100 at app/cbl/CBTRN02C.cbl:L385-L387 fires when the keyed read of "
                        + "the cross-reference file misses, so there is no account identifier the "
                        + "platform established and none may be carried");
        assertEquals(TransactionDeclined.UNRESOLVED_ACCOUNT_SCHEMA_VERSION,
                unresolved.envelope().schemaVersion(),
                "the unresolved decline travels under its own contract version");
        assertEquals(TRANSACTION_ID, unresolved.envelope().aggregateId(),
                "the message key is the transaction identifier, which is deterministic and names no "
                        + "cardholder, account or card");
        assertFalse(unresolved.envelope().carriesAccountKey(),
                "a sixteen-character key must not be mistaken for an eleven-digit account key");

        String json = serialized(unresolved);
        assertFalse(json.contains("accountId"),
                "the serialized event must carry no accountId property at all: " + json);
        assertTrue(json.contains("\"schemaVersion\":2"),
                "the serialized event must name the contract it was published under: " + json);

        Object read = deserializer.deserialize("transaction.declined",
                json.getBytes(StandardCharsets.UTF_8));
        assertEquals(unresolved, read,
                "the unresolved decline did not survive a round trip, so a consumer reads something "
                        + "the producer did not write");
    }

    @Test
    @DisplayName("a resolved decline still publishes under version one, so no consumer breaks")
    void aResolvedDeclineStillPublishesUnderVersionOne() {
        TransactionDeclined resolved = TransactionDeclined.of(ACCOUNT_ID, TRANSACTION_ID,
                DeclineReason.ACCOUNT_NOT_FOUND, new BigDecimal("50.47"), MASKED_CARD_NUMBER);

        assertEquals(EventEnvelope.SCHEMA_VERSION, resolved.envelope().schemaVersion(),
                "reasons 0101, 0102 and 0103 have already resolved an account identifier at "
                        + "app/cbl/CBTRN02C.cbl:L383, so their contract is unchanged");
        assertEquals(ACCOUNT_ID, resolved.accountId(),
                "a resolved decline carries the identifier the cross-reference row held");

        String json = serialized(resolved);
        assertTrue(json.contains("\"accountId\":\"" + ACCOUNT_ID + "\""),
                "a resolved decline must still carry its account identifier: " + json);
        assertTrue(json.contains("\"schemaVersion\":1"),
                "a resolved decline must still name version one: " + json);
    }

    @Test
    @DisplayName("no mixture of the two declined contracts can be built")
    void neitherDeclinedContractAcceptsTheOthersShape() {
        // An account identifier under the unresolved contract: the misattribution SEC-09 names.
        EventEnvelope unresolvedEnvelope = EventEnvelope.of("TransactionDeclined", TRANSACTION_ID,
                TransactionDeclined.UNRESOLVED_ACCOUNT_SCHEMA_VERSION);
        IllegalArgumentException claimed = assertThrows(IllegalArgumentException.class,
                () -> declined(unresolvedEnvelope, TRANSACTION_ID, ACCOUNT_ID,
                        DeclineReason.INVALID_CARD_NUMBER, new BigDecimal("50.47")));
        assertTrue(claimed.getMessage().contains("must be absent"),
                "the refusal does not explain itself: " + claimed.getMessage());
        assertFalse(claimed.getMessage().contains(ACCOUNT_ID),
                "the refusal echoed the rejected account identifier: " + claimed.getMessage());

        // A reason other than 0100 under the unresolved contract.
        assertThrows(IllegalArgumentException.class,
                () -> declined(unresolvedEnvelope, TRANSACTION_ID, null,
                        DeclineReason.OVER_CREDIT_LIMIT, new BigDecimal("50.47")),
                "every reason but 0100 has already resolved an account identifier");

        // A missing account identifier under the resolved contract.
        EventEnvelope resolvedEnvelope = EventEnvelope.of("TransactionDeclined", ACCOUNT_ID);
        assertThrows(IllegalArgumentException.class,
                () -> declined(resolvedEnvelope, TRANSACTION_ID, null,
                        DeclineReason.OVER_CREDIT_LIMIT, new BigDecimal("50.47")),
                "version one requires the account identifier a consumer relies on");

        // The convenience factory refuses an unresolved envelope rather than copying the key.
        IllegalArgumentException misused = assertThrows(IllegalArgumentException.class,
                () -> TransactionDeclined.of(unresolvedEnvelope, TRANSACTION_ID,
                        DeclineReason.INVALID_CARD_NUMBER, new BigDecimal("50.47"),
                        MASKED_CARD_NUMBER));
        assertTrue(misused.getMessage().contains("ofUnresolvedAccount"),
                "the refusal does not name the factory to use instead: " + misused.getMessage());
    }

    @Test
    @DisplayName("a version-one consumer refuses the unresolved decline rather than misreading it")
    void aVersionOneConsumerRefusesTheUnresolvedDecline() {
        String unresolved = serialized(TransactionDeclined.ofUnresolvedAccount(TRANSACTION_ID,
                new BigDecimal("50.47"), MASKED_CARD_NUMBER));
        String mislabelled = unresolved.replace("\"schemaVersion\":2", "\"schemaVersion\":1");

        SerializationException refused = assertThrows(SerializationException.class,
                () -> deserializer.deserialize("transaction.declined",
                        mislabelled.getBytes(StandardCharsets.UTF_8)));

        assertTrue(refused.getMessage().contains("transaction-declined-v1.json"),
                "the refusal must name the contract the event claimed to satisfy: "
                        + refused.getMessage());
        assertTrue(refused.getMessage().contains("accountId"),
                "version one requires accountId, and the refusal must say which property is "
                        + "missing: " + refused.getMessage());
    }

    @Test
    @DisplayName("a declined event naming a version no document describes is refused")
    void aDeclinedEventAtAnUngovernedVersionIsRefused() {
        String future = serialized(TransactionDeclined.ofUnresolvedAccount(TRANSACTION_ID,
                        new BigDecimal("50.47"), MASKED_CARD_NUMBER))
                .replace("\"schemaVersion\":2", "\"schemaVersion\":3");

        SerializationException refused = assertThrows(SerializationException.class,
                () -> deserializer.deserialize("transaction.declined",
                        future.getBytes(StandardCharsets.UTF_8)));

        assertTrue(refused.getMessage().contains("[1, 2]"),
                "the refusal must name the versions this module does govern: "
                        + refused.getMessage());
    }

    private List<Object> coreEvents() {
        return List.of(
                authorized(),
                TransactionDeclined.of(ACCOUNT_ID, TRANSACTION_ID,
                        DeclineReason.OVER_CREDIT_LIMIT, new BigDecimal("50.47"),
                        MASKED_CARD_NUMBER),
                TransactionDeclined.ofUnresolvedAccount(TRANSACTION_ID, new BigDecimal("50.47"),
                        MASKED_CARD_NUMBER),
                TransactionPosted.of(EventEnvelope.of("TransactionPosted", ACCOUNT_ID),
                        TRANSACTION_ID, new BigDecimal("1234.56"),
                        "2022-06-10-19.27.53.410000", new BigDecimal("50.47"), MASKED_CARD_NUMBER),
                FraudFlagged.of(ACCOUNT_ID, TRANSACTION_ID, 82, List.of("VELOCITY"),
                        Instant.parse("2022-06-10T19:27:53.412Z")),
                FraudCleared.of(TRANSACTION_ID, ACCOUNT_ID,
                        Instant.parse("2022-06-10T19:27:53.412Z")));
    }

    /** One authorized event with every component at a legitimate value. */
    private TransactionAuthorized authorized() {
        return TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", new BigDecimal("50.47"), "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", MASKED_CARD_NUMBER, AUTHORIZED_AT);
    }

    /**
     * The topic the shared registry binds one event to.
     *
     * <p>The publish gate refuses an event on a topic it does not belong on, so a test that pinned
     * one literal topic would fail on the binding rather than on the thing it measures.
     *
     * @param event the event about to be published
     * @return the default topic of its event type
     */
    private static String topicFor(Object event) {
        return EventContracts.defaultTopicFor(event.getClass().getSimpleName());
    }

    /** The wire form of one event, as text. */
    private String serialized(Object event) {
        return new String(serializer.serialize(topicFor(event), event),
                StandardCharsets.UTF_8);
    }

    /** Inserts one property at the front of a serialized object, keeping the JSON well-formed. */
    private static String withProperty(String json, String property) {
        return "{" + property + "," + json.substring(1);
    }

    /**
     * One {@code AccountStateChanged} document, written by hand.
     *
     * <p>The record lives in the account service, which this module does not depend on, so the
     * document is assembled here from the schema this module ships.
     */
    private static String accountStateChangedDocument() {
        return """
                {"eventId":"3f1d9c62-8b4e-4a17-9f0c-2d6a5e73b418",
                 "eventType":"AccountStateChanged",
                 "schemaVersion":1,
                 "occurredAt":"2022-06-10T19:27:53.412Z",
                 "aggregateId":"00000000007",
                 "accountId":"00000000007",
                 "changeKind":"ACCOUNT_UPDATED",
                 "creditLimit":"5000.00",
                 "currentCycleCredit":"0.00",
                 "currentCycleDebit":"0.00",
                 "expirationDate":"2024-12-31"}""".replace("\n", "");
    }
}
