package com.carddemo.events;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.carddemo.events.serde.EventContracts;
import com.carddemo.events.serde.JsonSchemaValidatingDeserializer;
import com.carddemo.events.serde.JsonSchemaValidatingSerializer;

import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sends each event through the publish-side serializer and the consume-side deserializer, and reads
 * back what arrived.
 *
 * <p>ADDITIVE. This class has no COBOL ancestor. The CardDemo source holds no Java and no test of
 * any kind, so nothing here translates a source construct.
 *
 * <p>Both ends validate against the same JSON Schema Draft 2020-12 document, so a round trip proves
 * three things at once: the wire form is flat, the schema accepts what the producer writes, and the
 * consumer accepts the same bytes. A failure on either end names the schema and the failing property
 * and carries no value from the event.
 *
 * <p>Money is asserted at its scale. {@code TRAN-AMT PIC S9(09)V99} at
 * {@code app/cpy/CVTRA05Y.cpy:L10} carries two fractional digits and
 * {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7} carries two as well, so a
 * value that arrives with a different scale has lost or gained a digit. Each amount travels as a
 * JSON string, which keeps it clear of binary floating point.
 *
 * <p>Every assertion runs offline. No {@code $id} is dereferenced, no schema registry service is
 * contacted, and no Kafka broker starts.
 */
class EventRoundTripTest {

    /** The eleven-digit account identifier, from {@code app/cpy/CVACT03Y.cpy:L7}. */
    private static final String ACCOUNT_IDENTIFIER = "00000000007";

    /** A transaction identifier at the sixteen-character width of {@code TRAN-ID PIC X(16)}. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /** A masked card number: twelve asterisks and the last four digits. */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /** A full Primary Account Number (PAN), used only to prove it never reaches a message. */
    private static final String FULL_CARD_NUMBER = "4859452612877065";

    /** The authorization timestamp form, from {@code TRAN-ORIG-TS PIC X(26)}. */
    private static final String AUTHORIZED_AT = "2022-06-10 19:27:53.000000";

    /** The posting timestamp form, hundredths then four zeros, from {@code TRAN-PROC-TS}. */
    private static final String POSTED_AT = "2022-07-19-23.16.01.470000";

    /** A risk assessment timestamp. */
    private static final Instant ASSESSED_AT = Instant.parse("2022-06-10T19:27:53.512Z");

    /** The topic name each call passes. Schema selection ignores it. */
    private static final String TOPIC = "transaction.authorized";

    /** Writes and checks each event. One instance serves every test in this class. */
    private final JsonSchemaValidatingSerializer<Object> serializer =
            new JsonSchemaValidatingSerializer<>();

    /** Reads and checks each event. One instance serves every test in this class. */
    private final JsonSchemaValidatingDeserializer<JsonNode> deserializer =
            new JsonSchemaValidatingDeserializer<>(JsonNode.class);

    /**
     * Asserts an authorized transaction survives a round trip with every property intact, and that
     * the amount arrives at its two-digit scale as a string.
     */
    @Test
    void anAuthorizedTransactionSurvivesARoundTripWithItsAmountScaleIntact() {
        TransactionAuthorized event = TransactionAuthorized.of(ACCOUNT_IDENTIFIER, TRANSACTION_ID,
                "01", "0001", "POS TERM", "Purchase at Abshire-Lowe", new BigDecimal("1250.75"),
                "800000000", "Abshire-Lowe", "North Enoshaven", "72112", MASKED_CARD_NUMBER,
                AUTHORIZED_AT);

        JsonNode arrived = roundTrip(event);

        assertEnvelope(arrived, "TransactionAuthorized");
        assertEquals(TRANSACTION_ID, arrived.get("transactionId").stringValue());
        assertEquals("01", arrived.get("transactionTypeCode").stringValue());
        assertEquals("0001", arrived.get("merchantCategoryCode").stringValue());
        assertEquals("POS TERM", arrived.get("source").stringValue());
        assertEquals("800000000", arrived.get("merchantId").stringValue());
        assertEquals(MASKED_CARD_NUMBER, arrived.get("maskedCardNumber").stringValue());
        assertEquals(AUTHORIZED_AT, arrived.get("authorizedAt").stringValue());
        assertEquals("USD", arrived.get("currency").stringValue());
        assertAmount(arrived, "amount", "1250.75");
    }

    /**
     * Asserts a declined transaction survives a round trip, that the reject reason arrives as its
     * four-character code, and that a negative amount keeps its sign.
     *
     * <p>The code and its text sit at {@code app/cbl/CBTRN02C.cbl:L410-L412}.</p>
     */
    @Test
    void aDeclinedTransactionSurvivesARoundTripWithItsRejectCodeAndSign() {
        TransactionDeclined event = TransactionDeclined.of(ACCOUNT_IDENTIFIER, TRANSACTION_ID,
                DeclineReason.OVER_CREDIT_LIMIT, new BigDecimal("-125.00"), MASKED_CARD_NUMBER);

        JsonNode arrived = roundTrip(event);

        assertEnvelope(arrived, "TransactionDeclined");
        assertEquals("0102", arrived.get("declineReasonCode").stringValue());
        assertEquals("OVERLIMIT TRANSACTION", arrived.get("declineReasonDescription").stringValue());
        assertAmount(arrived, "amount", "-125.00");
    }

    /**
     * Asserts a posted transaction survives a round trip, and that the new balance arrives at the
     * ten-integer-digit scale of {@code ACCT-CURR-BAL}.
     */
    @Test
    void aPostedTransactionSurvivesARoundTripWithItsBalanceScaleIntact() {
        TransactionPosted event = TransactionPosted.forAccount(ACCOUNT_IDENTIFIER, TRANSACTION_ID,
                new BigDecimal("9876543210.99"), POSTED_AT, new BigDecimal("1250.75"),
                MASKED_CARD_NUMBER);

        JsonNode arrived = roundTrip(event);

        assertEnvelope(arrived, "TransactionPosted");
        assertEquals(POSTED_AT, arrived.get("postedAt").stringValue());
        assertAmount(arrived, "newBalance", "9876543210.99");
        assertAmount(arrived, "amount", "1250.75");
    }

    /**
     * Asserts a flagged assessment survives a round trip with its score and its rule list, and that
     * it carries no card number in any form.
     */
    @Test
    void aFlaggedAssessmentSurvivesARoundTripAndCarriesNoCardNumber() {
        FraudFlagged event = FraudFlagged.of(ACCOUNT_IDENTIFIER, TRANSACTION_ID, 82,
                List.of("VELOCITY", "AMOUNT_ANOMALY"), ASSESSED_AT);

        JsonNode arrived = roundTrip(event);

        assertEnvelope(arrived, "FraudFlagged");
        assertEquals(82, arrived.get("riskScore").intValue());
        assertTrue(arrived.get("riskScore").isIntegralNumber(),
                "riskScore stopped travelling as a JSON integer");
        assertEquals(2, arrived.get("triggeredRules").size());
        assertEquals("VELOCITY", arrived.get("triggeredRules").get(0).stringValue());
        assertFalse(arrived.has("maskedCardNumber"),
                "a risk assessment carries no card number in any form");
    }

    /**
     * Asserts a cleared assessment survives a round trip, and that both of its timestamps arrive as
     * text rather than as a numeric epoch.
     */
    @Test
    void aClearedAssessmentSurvivesARoundTripWithItsTimestampsAsText() {
        FraudCleared event = FraudCleared.of(TRANSACTION_ID, ACCOUNT_IDENTIFIER, ASSESSED_AT);

        JsonNode arrived = roundTrip(event);

        assertEnvelope(arrived, "FraudCleared");
        assertEquals(ACCOUNT_IDENTIFIER, arrived.get("accountId").stringValue());
        assertTrue(arrived.get("assessedAt").isString(),
                "assessedAt stopped travelling as text, so a consumer reads a numeric epoch");
        assertEquals(ASSESSED_AT.toString(), arrived.get("assessedAt").stringValue());
    }

    /**
     * Asserts a {@code null} payload deserializes to {@code null}, which is Kafka's tombstone
     * contract.
     */
    @Test
    void aTombstoneDeserializesToNullRatherThanFailing() {
        assertNull(deserializer.deserialize(TOPIC, null),
                "a tombstone stopped deserializing to null, so a compacted topic breaks a listener");
    }

    /** Asserts bytes that are not JSON fail with the byte count and the topic named. */
    @Test
    void bytesThatAreNotJsonFailWithTheTopicNamed() {
        byte[] payload = "not json at all".getBytes(StandardCharsets.UTF_8);

        SerializationException failure = assertThrows(SerializationException.class,
                () -> deserializer.deserialize(TOPIC, payload));

        assertTrue(failure.getMessage().contains(TOPIC),
                "the failure stopped naming the topic the message arrived on");
    }

    /** Asserts a JSON value that is not an object fails rather than reaching a listener. */
    @Test
    void aJsonValueThatIsNotAnObjectFails() {
        byte[] payload = "[]".getBytes(StandardCharsets.UTF_8);

        SerializationException failure = assertThrows(SerializationException.class,
                () -> deserializer.deserialize(TOPIC, payload));

        assertTrue(failure.getMessage().contains("not a JSON object"),
                "a JSON array stopped failing, so a listener reads a value with no eventType");
    }

    /** Asserts a message with no {@code eventType} property fails, because no schema selects it. */
    @Test
    void aMessageWithNoEventTypeFailsBecauseNoSchemaSelectsIt() {
        byte[] payload = "{\"transactionId\":\"0000000000683580\"}"
                .getBytes(StandardCharsets.UTF_8);

        SerializationException failure = assertThrows(SerializationException.class,
                () -> deserializer.deserialize(TOPIC, payload));

        assertTrue(failure.getMessage().contains("no eventType property"),
                "a message with no eventType stopped failing, so it would reach a listener "
                        + "unchecked");
    }

    /** Asserts an event type this module holds no schema for fails with the type named. */
    @Test
    void anUnknownEventTypeFailsWithTheTypeNamed() {
        byte[] payload = "{\"eventType\":\"TransactionSettled\"}".getBytes(StandardCharsets.UTF_8);

        SerializationException failure = assertThrows(SerializationException.class,
                () -> deserializer.deserialize(TOPIC, payload));

        assertTrue(failure.getMessage().contains("TransactionSettled"),
                "the failure stopped naming the event type that selected no schema");
    }

    /**
     * Asserts a message that breaks its schema fails on the consume side, that the failure names the
     * failing property as a JSON pointer, and that it carries no value from the message.
     *
     * <p>A full Primary Account Number (PAN) in the failing property must not appear in the
     * message, because a failure text reaches a log.</p>
     */
    @Test
    void aMessageBreakingItsSchemaFailsAndTheTextCarriesNoValueFromIt() {
        TransactionAuthorized event = TransactionAuthorized.of(ACCOUNT_IDENTIFIER, TRANSACTION_ID,
                "01", "0001", "POS TERM", "Purchase at Abshire-Lowe", new BigDecimal("1250.75"),
                "800000000", "Abshire-Lowe", "North Enoshaven", "72112", MASKED_CARD_NUMBER,
                AUTHORIZED_AT);
        String json = new String(serializer.serialize(
                        EventContracts.defaultTopicFor(TransactionAuthorized.EVENT_TYPE), event),
                StandardCharsets.UTF_8)
                .replace(MASKED_CARD_NUMBER, FULL_CARD_NUMBER);

        SerializationException failure = assertThrows(SerializationException.class,
                () -> deserializer.deserialize(TOPIC, json.getBytes(StandardCharsets.UTF_8)));

        assertTrue(failure.getMessage().contains("/maskedCardNumber"),
                "the failure stopped naming the failing property as a JSON pointer");
        assertFalse(failure.getMessage().contains(FULL_CARD_NUMBER),
                "the failure text carried the card number that broke the schema, so a full "
                        + "Primary Account Number (PAN) would reach a log");
    }

    /**
     * Writes one event, then reads the bytes back through the deserializer.
     *
     * @param event the event to send through both ends
     * @return the tree the deserializer returned
     */
    private JsonNode roundTrip(Object event) {
        String topic = EventContracts.defaultTopicFor(event.getClass().getSimpleName());
        byte[] bytes = serializer.serialize(topic, event);

        assertNotNull(bytes, "the serializer returned no bytes for " + event.getClass().getName());

        JsonNode arrived = deserializer.deserialize(topic, bytes);
        assertNotNull(arrived, "the deserializer returned no tree for " + event.getClass().getName());
        return arrived;
    }

    /**
     * Asserts the five envelope properties arrived flat, beside the payload properties.
     *
     * @param arrived   the tree the deserializer returned
     * @param eventType the routing discriminator the event carries
     */
    private static void assertEnvelope(JsonNode arrived, String eventType) {
        assertFalse(arrived.has("envelope"),
                "the envelope arrived nested under an envelope key, and the wire form is flat");
        assertEquals(eventType, arrived.get("eventType").stringValue());
        assertEquals(EventEnvelope.SCHEMA_VERSION, arrived.get("schemaVersion").intValue());
        assertEquals(ACCOUNT_IDENTIFIER, arrived.get("aggregateId").stringValue());
        assertTrue(arrived.get("eventId").isString(),
                "eventId stopped travelling as text");
        assertTrue(arrived.get("occurredAt").isString(),
                "occurredAt stopped travelling as text, so a consumer reads a numeric epoch");
    }

    /**
     * Asserts one monetary property arrived as a string at the exact scale it was written with.
     *
     * @param arrived  the tree the deserializer returned
     * @param property the monetary property name
     * @param expected the plain-string form the producer wrote
     */
    private static void assertAmount(JsonNode arrived, String property, String expected) {
        JsonNode value = arrived.get(property);

        assertTrue(value.isString(),
                property + " stopped travelling as a string, and a JSON number parses into a "
                        + "binary floating-point value");
        assertEquals(expected, value.stringValue(),
                property + " changed value or scale between the producer and the consumer");
        assertEquals(new BigDecimal(expected).scale(), new BigDecimal(value.stringValue()).scale(),
                property + " arrived at a different scale, so a fractional digit was lost or "
                        + "gained");
    }
}
