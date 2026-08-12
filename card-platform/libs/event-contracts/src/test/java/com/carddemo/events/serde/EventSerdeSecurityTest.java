package com.carddemo.events.serde;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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

    /** Reads a serialized event back into a tree, so a publish-side case can send one. */
    private static final ObjectMapper MAPPER_TREE = JsonMapper.builder().build();

    /** The account identifier every event in this suite belongs to. */
    private static final String ACCOUNT_ID = "00000000007";

    /** The masked card number every event in this suite carries. */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /** Card token every card-bearing sample carries. Sixty-four lower-case hexadecimal characters. */
    private static final String CARD_TOKEN =
            "c41b7e6039fa25d81c0b94e7635af8021d4e9c78b6035f1ae284d70b9c3f6512";

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
                List.of("AccountStateChanged", "CardUpdated", "CustomerContextChanged",
                        "DeadLetterEnvelope", "FraudCleared", "FraudFlagged",
                        "TransactionAuthorized", "TransactionDeclined", "TransactionPosted"),
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
        String smuggled = withProperty(serialized(authorized()),
                "\"pan\":\"" + syntheticCardNumber(24453765740L) + "\"");

        SerializationException refused = assertThrows(SerializationException.class,
                () -> deserializer.deserialize("topic",
                        smuggled.getBytes(StandardCharsets.UTF_8)));

        assertTrue(refused.getMessage().contains("TransactionAuthorized"),
                "the refusal does not name the event type: " + refused.getMessage());
        assertFalse(refused.getMessage().contains(syntheticCardNumber(24453765740L)),
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
    @DisplayName("an ungoverned schema version is refused before the document is validated")
    void anotherSchemaVersionIsRefused() {
        String futureVersion = serialized(authorized())
                .replace("\"schemaVersion\":2", "\"schemaVersion\":3");

        SerializationException refused = assertThrows(SerializationException.class,
                () -> deserializer.deserialize("topic",
                        futureVersion.getBytes(StandardCharsets.UTF_8)));

        assertTrue(refused.getMessage().contains("schemaVersion"),
                "the refusal does not name the version mismatch: " + refused.getMessage());
    }

    @Test
    @DisplayName("a value added inside the bounded extensions object still reaches a v1 consumer")
    void anAddedExtensionValueIsAccepted() {
        TransactionAuthorized published = authorized();
        String enriched = withProperty(serialized(published),
                "\"extensions\":{\"replayOf\":\"3f1d9c62-8b4e-4a17-9f0c-2d6a5e73b418\"}");

        Object read = deserializer.deserialize("topic",
                enriched.getBytes(StandardCharsets.UTF_8));

        assertEquals(published, read,
                "a version-one consumer has to read the enriched event as the event it already"
                        + " understands, with the added property ignored rather than merged into a"
                        + " field. Surviving the read is only half of it: reading something the"
                        + " producer did not write is the other half, and both are the breakage the"
                        + " extensions object exists to prevent");
    }

    @Test
    @DisplayName("a card number written into a free-text property is refused on publish")
    void aCardNumberInsideFreeTextIsRefusedOnPublish() {
        TransactionAuthorized smuggled = TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01",
                "0001", "POS TERM", "Purchase for 4111111111111111", new BigDecimal("50.47"),
                "800000000", "Abshire-Lowe", "North Enoshaven", "72112", MASKED_CARD_NUMBER,
                CARD_TOKEN, AUTHORIZED_AT);

        SerializationException refused = assertThrows(SerializationException.class,
                () -> serializer.serialize(topicFor(smuggled), smuggled),
                "a sixteen-digit run inside the description reached a topic");

        assertTrue(refused.getMessage().contains("description"),
                "the refusal does not name the property that carried it: " + refused.getMessage());
    }

    @Test
    @DisplayName("a grouped card number inside the extensions object is refused on consume")
    void aGroupedCardNumberInsideExtensionsIsRefusedOnConsume() {
        String smuggled = withProperty(serialized(authorized()),
                "\"extensions\":{\"note\":\"4111 1111 1111 1111\"}");

        SerializationException refused = assertThrows(SerializationException.class,
                () -> deserializer.deserialize("topic",
                        smuggled.getBytes(StandardCharsets.UTF_8)),
                "a card number grouped for a human reader passed the consume-side screen");

        assertTrue(refused.getMessage().contains("note"),
                "the refusal does not name the property inside the open subtree that carried it: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("a separated government identifier inside a free-text property is refused")
    void aSeparatedGovernmentIdentifierIsRefused() {
        String smuggled = withProperty(serialized(authorized()),
                "\"extensions\":{\"note\":\"holder 020-97-3888\"}");

        assertThrows(SerializationException.class,
                () -> deserializer.deserialize("topic",
                        smuggled.getBytes(StandardCharsets.UTF_8)),
                "a three-two-four government identifier passed the consume-side screen");
    }

    /**
     * Every punctuation a caller may write between the groups of a card number.
     *
     * <p>The screen removed the space and the hyphen and nothing else, so each spelling below
     * reached a topic, an outbox row, a ledger row and the notification renderer. A separator list
     * cannot be enumerated from the outside, which is why the screen now removes whatever stands
     * between two digits. The last entry mixes four separators in one value, because a value that
     * defeats a per-separator remedy is the one worth asserting on.
     *
     * @return one card number per punctuation, each holding the same sixteen digits
     */
    private static java.util.stream.Stream<String> punctuatedCardNumbers() {
        return java.util.stream.Stream.of(
                "4111.1111.1111.1111",
                "4111/1111/1111/1111",
                "4111,1111,1111,1111",
                "4111_1111_1111_1111",
                "4111:1111:1111:1111",
                "4111*1111*1111*1111",
                "(4111)(1111)(1111)(1111)",
                "4111 - 1111 . 1111 / 1111");
    }

    /**
     * Card numbers grouped by a separator outside printable ASCII.
     *
     * <p>These reach the consume side alone. A typed free-text field is constrained to
     * {@code ^[ -~]*$} by the record that declares it, so a non-breaking space in a description is
     * refused before serialization begins and {@link #aTypedFreeTextFieldRefusesNonAscii} asserts
     * that. The {@code extensions} subtree is open by design, so it is the path these values arrive
     * on and the path the screen has to cover.
     *
     * @return one card number per non-ASCII separator, each holding the same sixteen digits
     */
    private static java.util.stream.Stream<String> unicodeSeparatedCardNumbers() {
        return java.util.stream.Stream.of(
                "4111\u00a01111\u00a01111\u00a01111",
                "4111\u20091111\u20091111\u20091111",
                "4111\u20111111\u20111111\u20111111",
                "4111\u200b1111\u200b1111\u200b1111");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("punctuatedCardNumbers")
    @DisplayName("a punctuated card number in a free-text property is refused on publish")
    void aPunctuatedCardNumberIsRefusedOnPublish(String punctuated) {
        TransactionAuthorized smuggled = TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01",
                "0001", "POS TERM", "Purchase for " + punctuated, new BigDecimal("50.47"),
                "800000000", "Abshire-Lowe", "North Enoshaven", "72112", MASKED_CARD_NUMBER,
                CARD_TOKEN, AUTHORIZED_AT);

        SerializationException refused = assertThrows(SerializationException.class,
                () -> serializer.serialize(topicFor(smuggled), smuggled),
                "a card number written as " + punctuated + " reached a topic");

        assertTrue(refused.getMessage().contains("description"),
                "the refusal does not name the property that carried it: " + refused.getMessage());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("punctuatedCardNumbers")
    @DisplayName("a punctuated card number inside the extensions object is refused on consume")
    void aPunctuatedCardNumberIsRefusedOnConsume(String punctuated) {
        String smuggled = withProperty(serialized(authorized()),
                "\"extensions\":{\"note\":" + quoted(punctuated) + "}");

        SerializationException refused = assertThrows(SerializationException.class,
                () -> deserializer.deserialize("topic",
                        smuggled.getBytes(StandardCharsets.UTF_8)),
                "a card number written as " + punctuated + " passed the consume-side screen");

        assertTrue(refused.getMessage().contains("note"),
                "the refusal does not name the property inside the open subtree that carried it: "
                        + refused.getMessage());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("unicodeSeparatedCardNumbers")
    @DisplayName("a card number grouped by a non-ASCII separator is refused on consume")
    void aUnicodeSeparatedCardNumberIsRefusedOnConsume(String separated) {
        String smuggled = withProperty(serialized(authorized()),
                "\"extensions\":{\"note\":" + quoted(separated) + "}");

        assertThrows(SerializationException.class,
                () -> deserializer.deserialize("topic",
                        smuggled.getBytes(StandardCharsets.UTF_8)),
                "a card number grouped by a non-ASCII separator passed the consume-side screen");
    }

    @Test
    @DisplayName("a typed free-text field refuses a non-ASCII character before serialization begins")
    void aTypedFreeTextFieldRefusesNonAscii() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001",
                        "POS TERM", "Purchase for 4111\u00a01111\u00a01111\u00a01111",
                        new BigDecimal("50.47"), "800000000", "Abshire-Lowe", "North Enoshaven",
                        "72112", MASKED_CARD_NUMBER, CARD_TOKEN, AUTHORIZED_AT),
                "a typed description accepted a non-breaking space, so the consume-side screen is "
                        + "the only control covering that spelling");

        assertTrue(refused.getMessage().contains("^[ -~]*$"),
                "the refusal does not name the shape it wanted: " + refused.getMessage());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "020.97.3888", "020/97/3888", "020_97_3888", "020 97 3888", "020-97.3888",
            "020.97-3888"})
    @DisplayName("a government identifier is refused whatever separates its groups")
    void aPunctuatedGovernmentIdentifierIsRefused(String punctuated) {
        String smuggled = withProperty(serialized(authorized()),
                "\"extensions\":{\"note\":\"holder " + punctuated + "\"}");

        assertThrows(SerializationException.class,
                () -> deserializer.deserialize("topic",
                        smuggled.getBytes(StandardCharsets.UTF_8)),
                "a government identifier written as " + punctuated + " passed the consume-side "
                        + "screen");
    }

    @Test
    @DisplayName("full-width digits are folded before the screen runs, so they are refused too")
    void fullWidthDigitsAreRefused() {
        String smuggled = withProperty(serialized(authorized()),
                "\"extensions\":{\"note\":\"\uff14\uff11\uff11\uff11\uff11\uff11\uff11\uff11"
                        + "\uff11\uff11\uff11\uff11\uff11\uff11\uff11\uff11\"}");

        assertThrows(SerializationException.class,
                () -> deserializer.deserialize("topic",
                        smuggled.getBytes(StandardCharsets.UTF_8)),
                "sixteen full-width digits passed the consume-side screen, and a reader sees a "
                        + "card number in them");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "REF 1234 SEQ 5678 LOT 9012",
            "aisle 12 bay 34 shelf 56 bin 78 row 90 slot 12",
            "order 2022-06-10 line 7",
            "terminal 800-000-0000 lane 4",
            "basket of 12 at 4.99 each"})
    @DisplayName("digits separated by words are not joined, so ordinary text still passes")
    void digitsSeparatedByWordsStillPass(String ordinary) {
        TransactionAuthorized published = authorized();
        String accepted = withProperty(serialized(published),
                "\"extensions\":{\"note\":" + quoted(ordinary) + "}");

        Object read = deserializer.deserialize("topic",
                accepted.getBytes(StandardCharsets.UTF_8));

        assertEquals(published, read,
                "the screen either joined digit groups a letter stands between, which would refuse "
                        + "ordinary merchant text, or it altered the event around them: " + ordinary);
    }

    /**
     * Wraps one value as a JSON string, escaping the two characters a description may hold.
     *
     * @param value the value to wrap
     * @return the value as a JSON string literal
     */
    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    @DisplayName("a card number grouped by a dot, slash, underscore or mixture of separators is refused")
    void aCardNumberGroupedByAnySeparatorIsRefused() {
        for (String written : List.of("4111.1111.1111.1111", "4111/1111/1111/1111",
                "4111_1111_1111_1111", "4111-1111.1111 1111", "4111 1111/1111-1111")) {
            String smuggled = withProperty(serialized(authorized()),
                    "\"extensions\":{\"note\":\"card " + written + "\"}");

            SerializationException refused = assertThrows(SerializationException.class,
                    () -> deserializer.deserialize("topic",
                            smuggled.getBytes(StandardCharsets.UTF_8)),
                    "a card number written as " + written + " passed the value screen: stripping "
                            + "the space and the hyphen alone left three of these five shapes "
                            + "unread");

            assertTrue(refused.getMessage().contains("note"),
                    "the refusal does not name the property that carried " + written + ": "
                            + refused.getMessage());
        }
    }

    @Test
    @DisplayName("a card verification value a label names is refused wherever it is written")
    void aLabelledCardVerificationValueIsRefused() {
        for (String written : List.of("CVV 123", "cvc: 4321", "cv2=999", "CID 1234",
                "security code = 999", "card-verification-value#123", "CSC 4321")) {
            String smuggled = withProperty(serialized(authorized()),
                    "\"extensions\":{\"note\":\"" + written + "\"}");

            assertThrows(SerializationException.class,
                    () -> deserializer.deserialize("topic",
                            smuggled.getBytes(StandardCharsets.UTF_8)),
                    "a verification value written as " + written + " reached a consumer: no"
                            + " property name reveals a code written inside free text");
        }
    }

    @Test
    @DisplayName("an amount, a timestamp and a short reference code inside free text still pass")
    void anAmountATimestampAndAShortCodeStillPass() {
        for (String written : List.of("total 1234567890.12", "posted 2026-08-07 19:12:06",
                "category 0001 type 01", "merchant 800000000", "zip 72112-1234")) {
            TransactionAuthorized published = authorized();
            String allowed = withProperty(serialized(published),
                    "\"extensions\":{\"note\":\"" + written + "\"}");

            Object read = deserializer.deserialize("topic",
                    allowed.getBytes(StandardCharsets.UTF_8));

            assertEquals(published, read,
                    "reading the grouping instead of erasing it either refused legitimate text or"
                            + " changed the event carrying it, and the first would refuse valid"
                            + " traffic: " + written);
        }
    }

    @Test
    @DisplayName("the value screen is field-specific, so a sixteen-digit transaction identifier passes")
    void aSixteenDigitTransactionIdentifierStillPasses() {
        assertEquals(16, TRANSACTION_ID.length(),
                "TRAN-ID PIC X(16) at app/cpy/CVTRA05Y.cpy:L5 holds sixteen characters");
        assertTrue(TRANSACTION_ID.chars().allMatch(Character::isDigit),
                "this test only means something while the identifier is all digits, which is what "
                        + "makes it indistinguishable from a card number by shape");

        Object read = assertDoesNotThrow(() -> deserializer.deserialize("topic",
                        serialized(authorized()).getBytes(StandardCharsets.UTF_8)),
                "the value screen refused a legitimate all-digit transaction identifier, which "
                        + "would refuse every event the fixtures produce");

        TransactionAuthorized bound = assertInstanceOf(TransactionAuthorized.class, read,
                "the registered record type no longer binds, so a consumer of this topic would "
                        + "receive a tree it has to read by field name");
        assertEquals(TRANSACTION_ID, bound.transactionId(),
                "the identifier did not survive the value screen intact, so passing the screen "
                        + "would say nothing about what a consumer receives");
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
        String cardNumber = syntheticCardNumber(24453765740L);
        DeadLetterEnvelope envelope = DeadLetterEnvelope.fromFailure(ACCOUNT_ID, "0999",
                new IllegalStateException("card " + cardNumber + " balance 1234.56"), "SCHEMA",
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
        assertFalse(wire.contains(cardNumber),
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
     * <p>The record is flat, so the five envelope components are passed one by one. The nine
     * descriptive components version 3 adds are passed absent, because these tests assert on the
     * envelope and the money and card fields rather than on the reject render.
     *
     * @param envelope      the envelope whose components the record carries
     * @param transactionId the transaction identifier
     * @param accountId     the account identifier the cross-reference resolved
     * @param reason        the decline reason, whose text the record derives
     * @param amount        the attempted amount
     * @return the declined event
     */
    private static TransactionDeclined declined(EventEnvelope envelope, String transactionId,
            String accountId, DeclineReason reason, BigDecimal amount) {
        return new TransactionDeclined(envelope.eventId(), envelope.eventType(),
                envelope.schemaVersion(), envelope.occurredAt(), envelope.aggregateId(),
                transactionId, accountId, reason, reason.description(), amount,
                MASKED_CARD_NUMBER, null, null, null, null, null, null, null, null, null);
    }

    /** The five core events, each built through its own factory. */

    @Test
    @DisplayName("no decline can be published without the account identifier its reason resolved")
    void noDeclineIsPublishedWithoutItsAccountIdentifier() {
        IllegalArgumentException absent = assertThrows(IllegalArgumentException.class,
                () -> declined(EventEnvelope.of("TransactionDeclined", ACCOUNT_ID), TRANSACTION_ID,
                        null, DeclineReason.OVER_CREDIT_LIMIT, new BigDecimal("50.47")),
                "a decline with no account identifier was built and leaves its account unknown");

        assertTrue(absent.getMessage().contains("accountId"),
                "the refusal does not name the missing component: " + absent.getMessage());
        assertFalse(absent.getMessage().contains(ACCOUNT_ID),
                "the refusal echoed an account identifier: " + absent.getMessage());
    }

    @Test
    @DisplayName("reject reason 0100 names no account and keys on the identifier this platform minted")
    void rejectReasonOneHundredNamesNoAccountAndKeysOnItsTransactionIdentifier() {
        assertFalse(DeclineReason.INVALID_CARD_NUMBER.resolvesAccount(),
                "app/cbl/CBTRN02C.cbl:L383-L387 assigns reason 0100 inside the INVALID KEY limb of "
                        + "the cross-reference read, so no account identifier has been read by then");

        TransactionDeclined accountLess = TransactionDeclined.ofUnresolvedAccount(
                TRANSACTION_ID, new BigDecimal("50.47"), MASKED_CARD_NUMBER);

        assertEquals(TransactionDeclined.UNRESOLVED_ACCOUNT_SCHEMA_VERSION,
                accountLess.schemaVersion(),
                "reason 0100 moved off the one document that declares no accountId, and no other "
                        + "declined document can carry an outcome that resolved no account");
        assertNull(accountLess.accountId(),
                "reason 0100 started carrying an account identifier, and the only candidate is the "
                        + "value a caller declared, which no stored row of this platform "
                        + "corroborates");
        assertEquals(TRANSACTION_ID, accountLess.aggregateId(),
                "reason 0100 stopped keying on the identifier this platform minted, which is the "
                        + "one subject it has that no caller chose");
        assertTrue(EventContracts.publishViolationsOf(TransactionDeclined.EVENT_TYPE,
                        serialized(accountLess)).isEmpty(),
                "a producer stopped being able to write the account-less contract, which would "
                        + "leave the one outcome that resolves no account with no event: "
                        + EventContracts.publishViolationsOf(TransactionDeclined.EVENT_TYPE,
                                serialized(accountLess)));
        assertTrue(EventContracts.violationsOf(TransactionDeclined.EVENT_TYPE,
                        serialized(accountLess)).isEmpty(),
                "a record published under the account-less contract stopped being readable");
        assertFalse(accountLess.carriesTransactionDetail(),
                "the nine descriptive values app/cbl/CBTRN02C.cbl:L446-L465 writes exist only on "
                        + "the request the read rejected, so this record carries none of them and "
                        + "the ledger takes its no-detail path");

        assertFalse(serialized(accountLess).contains("\"accountId\""),
                "and no account property reaches the wire: " + serialized(accountLess));
    }

    @Test
    @DisplayName("no factory pairs reject reason 0100 with an account identifier")
    void noFactoryPairsRejectReasonOneHundredWithAnAccountIdentifier() {
        IllegalArgumentException viaVersionOne = assertThrows(IllegalArgumentException.class,
                () -> TransactionDeclined.of(ACCOUNT_ID, TRANSACTION_ID,
                        DeclineReason.INVALID_CARD_NUMBER, new BigDecimal("50.47"),
                        MASKED_CARD_NUMBER),
                "the version-one factory paired reason 0100 with an account a caller declared");

        assertTrue(viaVersionOne.getMessage().contains(DeclineReason.INVALID_CARD_NUMBER.code()),
                "the refusal names the reason that resolves no account: "
                        + viaVersionOne.getMessage());

        IllegalArgumentException viaDetail = assertThrows(IllegalArgumentException.class,
                () -> TransactionDeclined.withTransactionDetail(ACCOUNT_ID, TRANSACTION_ID,
                        DeclineReason.INVALID_CARD_NUMBER, "01", "0001", "POS TERM",
                        "Purchase at Abshire-Lowe", new BigDecimal("50.47"), "800000000",
                        "Abshire-Lowe", "North Enoshaven", "72112", MASKED_CARD_NUMBER,
                        AUTHORIZED_AT),
                "the detail-bearing factory paired reason 0100 with an account a caller declared");

        assertTrue(viaDetail.getMessage().contains(DeclineReason.INVALID_CARD_NUMBER.code()),
                "the refusal names the reason that resolves no account: " + viaDetail.getMessage());
    }

    @Test
    @DisplayName("every decline publishes under version one and names its account identifier")
    void everyDeclinePublishesUnderVersionOne() {
        TransactionDeclined resolved = TransactionDeclined.of(ACCOUNT_ID, TRANSACTION_ID,
                DeclineReason.ACCOUNT_NOT_FOUND, new BigDecimal("50.47"), MASKED_CARD_NUMBER);

        assertEquals(EventEnvelope.SCHEMA_VERSION, resolved.envelope().schemaVersion(),
                "reasons 0101, 0102 and 0103 have already resolved an account identifier at "
                        + "app/cbl/CBTRN02C.cbl:L383, and one contract governs all three");
        assertEquals(ACCOUNT_ID, resolved.accountId(),
                "a decline carries the identifier the cross-reference row held");
        assertEquals(ACCOUNT_ID, resolved.envelope().aggregateId(),
                "the Kafka message key is the account identifier, so every event of one account "
                        + "stays on one partition");

        String json = serialized(resolved);
        assertTrue(json.contains("\"accountId\":\"" + ACCOUNT_ID + "\""),
                "a decline must carry its account identifier: " + json);
        assertTrue(json.contains("\"schemaVersion\":1"),
                "a decline must name version one: " + json);
    }

    @Test
    @DisplayName("a decline arriving without an account identifier is refused on the consume side")
    void aDeclineArrivingWithoutAnAccountIdentifierIsRefused() {
        String resolved = serialized(TransactionDeclined.of(ACCOUNT_ID, TRANSACTION_ID,
                DeclineReason.ACCOUNT_NOT_FOUND, new BigDecimal("50.47"), MASKED_CARD_NUMBER));
        String stripped = resolved.replace("\"accountId\":\"" + ACCOUNT_ID + "\",", "");

        assertNotEquals(resolved, stripped,
                "the property this test removes was not present, so the test measures nothing");

        SerializationException refused = assertThrows(SerializationException.class,
                () -> deserializer.deserialize("transaction.declined",
                        stripped.getBytes(StandardCharsets.UTF_8)));

        assertTrue(refused.getMessage().contains("transaction-declined-v1.json"),
                "the refusal must name the contract the event claimed to satisfy: "
                        + refused.getMessage());
        assertTrue(refused.getMessage().contains("accountId"),
                "the contract requires accountId, and the refusal must say which property is "
                        + "missing: " + refused.getMessage());
    }

    @Test
    @DisplayName("a declined event naming a version no document describes is refused")
    void aDeclinedEventAtAnUngovernedVersionIsRefused() {
        String future = serialized(TransactionDeclined.of(ACCOUNT_ID, TRANSACTION_ID,
                        DeclineReason.ACCOUNT_NOT_FOUND, new BigDecimal("50.47"),
                        MASKED_CARD_NUMBER))
                .replace("\"schemaVersion\":1", "\"schemaVersion\":4");

        SerializationException refused = assertThrows(SerializationException.class,
                () -> deserializer.deserialize("transaction.declined",
                        future.getBytes(StandardCharsets.UTF_8)));

        assertTrue(refused.getMessage().contains("[1, 2, 3]"),
                "the refusal must name the versions this module does govern: "
                        + refused.getMessage());
    }

    /**
     * The property names a caller might reach for when it means a value no event may carry.
     *
     * <p>Every card scheme abbreviates the verification value differently, and a screen that knew
     * only {@code cvv} let {@code cvc}, {@code cvc2}, {@code cv2}, {@code cid} and {@code csc}
     * through as extension keys: the name was unrecognised and three digits are too short for the
     * long-digit-run screen, so {@code {"extensions":{"cvc":"123"}}} reached a topic with an
     * allowed key and an allowed value. Each name below is measured on both ends of the wire.
     *
     * @return one name per case
     */
    private static java.util.stream.Stream<String> sensitivePropertyAliases() {
        return java.util.stream.Stream.of("cvv", "cvv2", "cvc", "cvc2", "cv2", "cid", "csc", "cvn",
                "cvd", "cav2", "cavv", "cardVerificationValue", "card_verification_code",
                "securityCode", "cardSecurityCode", "verificationCode", "pin", "PIN", "pin_block",
                "pinOffset", "password", "passwd", "passPhrase", "passcode", "ssn",
                "social_security_number", "pan", "fullPan", "cardNumber");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("sensitivePropertyAliases")
    @DisplayName("a sensitive property alias is refused on publish, whatever value it carries")
    void aSensitivePropertyAliasIsRefusedOnPublish(String alias) {
        assertTrue(SensitiveEventProperties.isForbidden(alias),
                "the property name " + alias + " names a value no event on this platform carries,"
                        + " and the name screen admits it");

        for (Object event : coreEvents()) {
            String smuggled = withProperty(serialized(event),
                    quoted("extensions") + ":{" + quoted(alias) + ":" + quoted("123") + "}");
            SerializationException refused = assertThrows(SerializationException.class,
                    () -> serializer.serialize(topicFor(event), MAPPER_TREE.readTree(smuggled)),
                    "the publish gate accepted an extension named " + alias + " on "
                            + event.getClass().getSimpleName());
            assertFalse(refused.getMessage().contains("123"),
                    "the refusal quoted the value it refused: " + refused.getMessage());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("sensitivePropertyAliases")
    @DisplayName("a sensitive property alias is refused on consume, whatever value it carries")
    void aSensitivePropertyAliasIsRefusedOnConsume(String alias) {
        for (Object event : coreEvents()) {
            String smuggled = withProperty(serialized(event),
                    quoted("extensions") + ":{" + quoted(alias) + ":" + quoted("1234") + "}");
            SerializationException refused = assertThrows(SerializationException.class,
                    () -> deserializer.deserialize("topic",
                            smuggled.getBytes(StandardCharsets.UTF_8)),
                    "the consume gate accepted an extension named " + alias + " on "
                            + event.getClass().getSimpleName());
            assertFalse(refused.getMessage().contains("1234"),
                    "the refusal quoted the value it refused: " + refused.getMessage());
        }
    }

    @Test
    @DisplayName("an extension name in a card-code context refuses a bare three or four digit value")
    void anExtensionNameInACardCodeContextRefusesAShortDigitRun() {
        for (String name : List.of("authCode", "cardCode", "secCode", "code", "entry_code")) {
            String smuggled = withProperty(serialized(authorized()),
                    quoted("extensions") + ":{" + quoted(name) + ":" + quoted("123") + "}");
            SerializationException refused = assertThrows(SerializationException.class,
                    () -> deserializer.deserialize("topic",
                            smuggled.getBytes(StandardCharsets.UTF_8)),
                    "the consume gate accepted three digits under the extension name " + name
                            + ", where the name is the label the value does not carry");
            assertTrue(refused.getMessage().contains(name),
                    "the refusal does not name the property that carried it: "
                            + refused.getMessage());
        }
    }

    @Test
    @DisplayName("an extension name in a credential context refuses any value")
    void anExtensionNameInACredentialContextRefusesAnyValue() {
        for (String name : List.of("clientSecret", "credential", "apiKey", "accessKey",
                "privateKey", "sessionKey", "bearer", "cardToken")) {
            String smuggled = withProperty(serialized(authorized()),
                    quoted("extensions") + ":{" + quoted(name) + ":" + quoted("anything") + "}");
            assertThrows(SerializationException.class,
                    () -> deserializer.deserialize("topic",
                            smuggled.getBytes(StandardCharsets.UTF_8)),
                    "the consume gate accepted a value under the extension name " + name
                            + ", which says what it carries");
        }
    }

    /**
     * Holds the consume side open to ordinary additive traffic, which is what the subtree is for.
     *
     * <p>The produce side closes the same subtree, asserted by
     * {@link #theProduceSideClosesTheExtensionsSubtreeTheConsumeSideKeepsOpen()}. This test is the
     * other half: a control that refused every extension on the read side would refuse the enriched
     * record a later version publishes, which is the compatibility this platform promises.
     */
    @Test
    @DisplayName("an ordinary extension name and value still reaches a consumer")
    void anOrdinaryExtensionStillReachesAConsumer() {
        for (String pair : List.of(
                quoted("replayOf") + ":" + quoted("3f1d9c62-8b4e-4a17-9f0c-2d6a5e73b418"),
                quoted("posEntryMode") + ":" + quoted("chip"),
                quoted("retryCount") + ":" + quoted("2"),
                quoted("settlementBatch") + ":" + quoted("0007"))) {

            String enriched = withProperty(serialized(authorized()),
                    quoted("extensions") + ":{" + pair + "}");
            assertDoesNotThrow(() -> deserializer.deserialize("topic",
                            enriched.getBytes(StandardCharsets.UTF_8)),
                    "a screen that refuses " + pair + " refuses ordinary additive traffic, and a"
                            + " control that fires on everything gets switched off");
        }
    }

    @Test
    @DisplayName("a bare card code fills a narrative property and is refused on both sides")
    void aBareCardCodeInANarrativePropertyIsRefused() {
        for (String bare : List.of("123", "4321", "0007")) {
            SerializationException publishRefused = assertThrows(SerializationException.class,
                    () -> serializer.serialize(topicFor(authorized()),
                            authorizedWithDescription(bare)),
                    "a description of " + bare + " reached a topic, and three or four digits is the"
                            + " width of CARD-CVV-CD PIC 9(03) at app/cpy/CVACT02Y.cpy:L7");
            assertTrue(publishRefused.getMessage().contains("description"),
                    "the refusal has to name the property a reader must look at: "
                            + publishRefused.getMessage());
            assertFalse(publishRefused.getMessage().contains(bare),
                    "the refusal repeated the value it refused, which puts it in a log");

            String smuggled = serializedWithNarrative("description", bare);
            assertThrows(SerializationException.class,
                    () -> deserializer.deserialize("topic",
                            smuggled.getBytes(StandardCharsets.UTF_8)),
                    "the consume side accepted a description of " + bare
                            + ", so the two ends disagree about what a narrative property may hold");
        }

        // The record refuses a full-width digit before a gate is reached, because
        // TransactionAuthorized holds its description to the characters the source field carries. A
        // document built by hand reaches the screen, and the screen reads the value normalized.
        String fullWidth = serializedWithNarrative("description", "\uff11\uff12\uff13");
        assertThrows(SerializationException.class,
                () -> deserializer.deserialize("topic", fullWidth.getBytes(StandardCharsets.UTF_8)),
                "three full-width digits render as a card code to a reader and passed the screen");
    }

    @Test
    @DisplayName("an unseparated government identifier inside narrative text is refused")
    void anUnseparatedGovernmentIdentifierInsideNarrativeTextIsRefused() {
        for (String written : List.of("020973888", "holder 020973888", "SSN020973888",
                "0209738881", "020973888123")) {
            SerializationException refused = assertThrows(SerializationException.class,
                    () -> serializer.serialize(topicFor(authorized()),
                            authorizedWithMerchantName(written)),
                    "a merchant name of " + written + " reached a topic. CUST-SSN PIC 9(09) at"
                            + " app/cpy/CVCUS01Y.cpy:L20 holds nine digits with no separator, and"
                            + " the separated screen cannot see that form");
            assertTrue(refused.getMessage().contains("merchantName"),
                    "the refusal has to name merchantName: " + refused.getMessage());
        }
    }

    @Test
    @DisplayName("a postal code keeps both of the forms the repository holds")
    void aPostalCodeKeepsBothOfTheFormsTheRepositoryHolds() {
        for (String written : List.of("72112", "72112-1234", "721121234", "00022")) {
            TransactionAuthorized published = authorizedWithMerchantZip(written);

            byte[] bytes = assertDoesNotThrow(
                    () -> serializer.serialize(topicFor(published), published),
                    "a merchant postal code of " + written + " was refused. Records of"
                            + " app/data/ASCII/dailytran.txt hold the five-digit and the hyphenated"
                            + " nine-digit form, and a screen that refuses valid traffic gets"
                            + " switched off");

            assertEquals(published, deserializer.deserialize("topic", bytes),
                    "the postal code did not survive the round trip it passed");
        }
    }

    @Test
    @DisplayName("a narrative property keeps ordinary prose that happens to carry short runs")
    void aNarrativePropertyKeepsOrdinaryProse() {
        for (String written : List.of("Purchase at STORE 101", "Pizza 4 U", "Refund 12.34",
                "Order 1234 of 5678", "Purchase at Abshire-Lowe")) {
            TransactionAuthorized published = authorizedWithDescription(written);

            assertDoesNotThrow(() -> serializer.serialize(topicFor(published), published),
                    "the description " + written + " was refused, and a control that fires on"
                            + " ordinary prose is one an operator turns off");
        }
    }

    @Test
    @DisplayName("the property that owns an array is what a refusal names")
    void thePropertyThatOwnsAnArrayIsWhatARefusalNames() {
        String smuggled = withProperty(serialized(
                        FraudFlagged.of(ACCOUNT_ID, TRANSACTION_ID, 82, List.of("VELOCITY"),
                                Instant.parse("2022-06-10T19:27:53.412Z")))
                        .replace("[\"VELOCITY\"]", "[\"4111 1111 1111 1111\"]"), quoted("unused")
                        + ":" + quoted("x"));

        SerializationException refused = assertThrows(SerializationException.class,
                () -> deserializer.deserialize("topic", smuggled.getBytes(StandardCharsets.UTF_8)));

        assertTrue(refused.getMessage().contains("triggeredRules")
                        || refused.getMessage().contains("unused"),
                "a value inside an array used to be reported as \"extensions\", which names a"
                        + " property the event does not carry: " + refused.getMessage());
        assertFalse(refused.getMessage().contains("4111"),
                "the refusal repeated the value it refused: " + refused.getMessage());
    }

    @Test
    @DisplayName("a cardholder name or address line is screened as narrative text")
    void aCardholderNameOrAddressLineIsScreenedAsNarrativeText() {
        for (String property : List.of("firstName", "lastName", "addressLine1", "addressLine2",
                "addressLine3")) {
            String smuggled = customerContextDocument(property, "4111111111111111");

            SerializationException refused = assertThrows(SerializationException.class,
                    () -> deserializer.deserialize("topic",
                            smuggled.getBytes(StandardCharsets.UTF_8)),
                    "a card number written into " + property + " reached a consumer. The account"
                            + " service publishes these ten fields to the notification read model,"
                            + " and until this screen covered them nothing read their values");
            assertTrue(refused.getMessage().contains(property),
                    "the refusal has to name " + property + ": " + refused.getMessage());
        }
    }

    @Test
    @DisplayName("a cardholder postal code keeps its nine-digit form")
    void aCardholderPostalCodeKeepsItsNineDigitForm() {
        String allowed = customerContextDocument("zipCode", "198526716");

        assertDoesNotThrow(() -> deserializer.deserialize("topic",
                        allowed.getBytes(StandardCharsets.UTF_8)),
                "app/data/ASCII/custdata.txt holds a hyphenated nine-digit postal code, so the"
                        + " unseparated form is legitimate traffic and not a government identifier");
    }

    @Test
    @DisplayName("a full-width property name folds onto the name it renders as")
    void aFullWidthPropertyNameFoldsOntoTheNameItRendersAs() {
        String smuggled = withProperty(serialized(authorized()),
                quoted("\uff43\uff56\uff56") + ":" + quoted("123"));

        SerializationException refused = assertThrows(SerializationException.class,
                () -> deserializer.deserialize("topic", smuggled.getBytes(StandardCharsets.UTF_8)),
                "a property named in full-width characters folded to nothing and passed the name"
                        + " screens, so the same three digits travelled under a name that reads as"
                        + " cvv");

        assertNotNull(refused.getMessage());
    }

    @Test
    @DisplayName("the produce side closes the extensions subtree the consume side keeps open")
    void theProduceSideClosesTheExtensionsSubtreeTheConsumeSideKeepsOpen() {
        String enriched = withProperty(serialized(authorized()),
                quoted("extensions") + ":{" + quoted("posEntryMode") + ":" + quoted("chip") + "}");

        List<String> refusals = EventContracts.publishViolationsOf(
                EventContracts.TRANSACTION_AUTHORIZED, enriched);

        assertFalse(refusals.isEmpty(),
                "a producer wrote an extensions object, and no record of this platform declares"
                        + " one, so the JSON was built outside its own record");
        assertTrue(refusals.stream().anyMatch(entry -> entry.contains("extensions")),
                "the refusal has to name the property: " + refusals);
        assertDoesNotThrow(() -> deserializer.deserialize("topic",
                        enriched.getBytes(StandardCharsets.UTF_8)),
                "the consume side has to keep reading an enriched record, which is the"
                        + " compatibility every schema document promises through extensions");
    }

    @Test
    @DisplayName("the text-form producer check refuses what the record-form gate refuses")
    void theTextFormProducerCheckRefusesWhatTheRecordFormGateRefuses() {
        String smuggled = serializedWithNarrative("description", "4111111111111111");

        List<String> refusals = EventContracts.publishViolationsOf(
                EventContracts.TRANSACTION_AUTHORIZED, smuggled);

        assertFalse(refusals.isEmpty(),
                "the text-form check accepted a card number in a free-text property while the"
                        + " record-form gate refused it, which is the split a security review found");
        assertTrue(refusals.stream().anyMatch(entry -> entry.contains("description")), refusals::toString);
        assertFalse(refusals.stream().anyMatch(entry -> entry.contains("4111")), refusals::toString);
    }

    @Test
    @DisplayName("the text-form producer check applies the platform byte ceiling")
    void theTextFormProducerCheckAppliesThePlatformByteCeiling() {
        String oversized = withProperty(serialized(authorized()),
                quoted("filler") + ":" + quoted("x".repeat(EventWireBounds.MAX_EVENT_BYTES)));

        List<String> refusals = EventContracts.publishViolationsOf(
                EventContracts.TRANSACTION_AUTHORIZED, oversized);

        assertTrue(refusals.stream().anyMatch(entry -> entry.contains("byte ceiling")),
                "a payload wider than the platform ceiling was measurable only by the serializer,"
                        + " so a relay re-checking stored text could publish one: " + refusals);
    }

    /** One authorized event whose description carries the supplied narrative text. */
    private TransactionAuthorized authorizedWithDescription(String description) {
        return TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS TERM",
                description, new BigDecimal("50.47"), "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", MASKED_CARD_NUMBER, CARD_TOKEN, AUTHORIZED_AT);
    }

    /** One authorized event whose merchant name carries the supplied narrative text. */
    private TransactionAuthorized authorizedWithMerchantName(String merchantName) {
        return TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", new BigDecimal("50.47"), "800000000", merchantName,
                "North Enoshaven", "72112", MASKED_CARD_NUMBER, CARD_TOKEN, AUTHORIZED_AT);
    }

    /** One authorized event whose merchant postal code carries the supplied value. */
    private TransactionAuthorized authorizedWithMerchantZip(String merchantZip) {
        return TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", new BigDecimal("50.47"), "800000000", "Abshire-Lowe",
                "North Enoshaven", merchantZip, MASKED_CARD_NUMBER, CARD_TOKEN, AUTHORIZED_AT);
    }

    /**
     * One authorized document whose named narrative property carries the supplied text.
     *
     * <p>Written by replacing the value in a document the serializer produced, so every other
     * property stays the one the record writes and the document still validates.
     *
     * @param property the narrative property to overwrite
     * @param value    the text to write into it
     * @return the document, as text
     */
    private String serializedWithNarrative(String property, String value) {
        JsonNode tree = MAPPER_TREE.readTree(serialized(authorized()));
        return MAPPER_TREE.writeValueAsString(
                ((tools.jackson.databind.node.ObjectNode) tree).put(property, value));
    }

    /**
     * One {@code CustomerContextChanged} document, written by hand, with one property overwritten.
     *
     * <p>The record lives in the account service, which this module does not depend on, so the
     * document is assembled here from the schema this module ships. Every value is the shape
     * {@code app/cpy/CVCUS01Y.cpy} declares, so the document validates and the screens are what
     * decide the outcome.
     *
     * @param property the property to overwrite
     * @param value    the value to write into it
     * @return the document, as text
     */
    private String customerContextDocument(String property, String value) {
        String document = """
                {"eventId":"3f1d9c62-8b4e-4a17-9f0c-2d6a5e73b418",
                 "eventType":"CustomerContextChanged",
                 "schemaVersion":1,
                 "occurredAt":"2022-06-10T19:27:53.412Z",
                 "aggregateId":"00000000007",
                 "accountId":"00000000007",
                 "firstName":"Aniya",
                 "middleName":"Rae",
                 "lastName":"Von",
                 "addressLine1":"618 Deshaun Route",
                 "addressLine2":"Suite 4",
                 "addressLine3":"Lake Wilfrid",
                 "stateCode":"CO",
                 "countryCode":"USA",
                 "zipCode":"12546",
                 "ficoScore":"747"}""".replace("\n", "");
        JsonNode tree = MAPPER_TREE.readTree(document);
        return MAPPER_TREE.writeValueAsString(
                ((tools.jackson.databind.node.ObjectNode) tree).put(property, value));
    }

    private List<Object> coreEvents() {
        return List.of(
                authorized(),
                TransactionDeclined.of(ACCOUNT_ID, TRANSACTION_ID,
                        DeclineReason.OVER_CREDIT_LIMIT, new BigDecimal("50.47"),
                        MASKED_CARD_NUMBER),
                TransactionDeclined.ofUnresolvedAccount(TRANSACTION_ID, new BigDecimal("50.47"),
                        MASKED_CARD_NUMBER),
                TransactionPosted.forAccount(ACCOUNT_ID, TRANSACTION_ID,
                        new BigDecimal("1234.56"), "2022-06-10-19.27.53.410000",
                        new BigDecimal("50.47"), MASKED_CARD_NUMBER),
                TransactionPosted.forAuthorized(authorized(), new BigDecimal("1234.56"),
                        "2022-06-10-19.27.53.410000"),
                FraudFlagged.of(ACCOUNT_ID, TRANSACTION_ID, 82, List.of("VELOCITY"),
                        Instant.parse("2022-06-10T19:27:53.412Z")),
                FraudCleared.of(TRANSACTION_ID, ACCOUNT_ID,
                        Instant.parse("2022-06-10T19:27:53.412Z")));
    }

    /** One authorized event with every component at a legitimate value. */
    private TransactionAuthorized authorized() {
        return TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", new BigDecimal("50.47"), "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", MASKED_CARD_NUMBER, CARD_TOKEN, AUTHORIZED_AT);
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
                 "currentBalance":"1250.75",
                 "creditLimit":"5000.00",
                 "currentCycleCredit":"0.00",
                 "currentCycleDebit":"0.00",
                 "expirationDate":"2024-12-31"}""".replace("\n", "");
    }

    /**
     * Builds a sixteen-digit card number this repository does not carry.
     *
     * <p>The four leading digits are {@code 9999}, which none of the fifty records of
     * {@code app/data/ASCII/carddata.txt} begins with, so no card number of the repository reaches
     * this source file as a literal.
     *
     * @param serial the trailing serial, at most twelve digits
     * @return sixteen digits, opening with {@code 9999}
     */
    private static String syntheticCardNumber(long serial) {
        return "9999" + String.format("%012d", serial);
    }
}
