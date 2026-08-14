package com.carddemo.events.serde;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.carddemo.events.DeclineReason;
import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import com.carddemo.events.TransactionPosted;

import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the three guards a publish passes: the event must be a known contract type, it must be
 * bound to the topic it is sent to, and its serialized form must satisfy its schema document.
 *
 * <p>This class has no COBOL ancestor. The CardDemo source has no event bus, so nothing
 * here translates a source construct. The one source locator it needs is
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}, which fixes the eleven-digit
 * account identifier that becomes the Kafka message key.
 *
 * <p>Every assertion runs offline. No schema registry service is contacted, no {@code $id} is
 * dereferenced and no Kafka broker starts.
 *
 * <p>Versions in use: Java 25, Apache Maven 3.9.16, junit-jupiter 6.0.3,
 * spring-boot-starter-test 4.1.0, json-schema-validator 3.0.6, jackson-databind 3.1.5 and
 * kafka-clients 4.2.1.
 */
class EventPublicationGuardTest {

    /** The eleven-digit account identifier every sample event carries. */
    private static final String ACCOUNT_IDENTIFIER = "00000000007";

    /** The sixteen-character transaction identifier every sample event carries. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /** The masked card form every card-carrying document accepts. */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /** Card token every card-bearing sample carries. Sixty-four lower-case hexadecimal characters. */
    private static final String CARD_TOKEN =
            "c41b7e6039fa25d81c0b94e7635af8021d4e9c78b6035f1ae284d70b9c3f6512";

    /** The authorization timestamp form the authorized document accepts. */
    private static final String AUTHORIZED_AT = "2022-06-10 19:27:53.000000";

    /** The posting timestamp form the posted document accepts. */
    private static final String POSTED_AT = "2022-07-19-23.16.01.470000";

    /** The moment the sample fraud events report. */
    private static final Instant ASSESSED_AT = Instant.parse("2022-06-10T19:27:53.512Z");

    /**
     * Asserts every event type the platform publishes has a contract, and that the count is exact.
     *
     * <p>An event type absent from the registry has no schema document, so nothing validates it and
     * the versioning guarantee does not reach it.
     */
    @Test
    void everyRuntimeEventTypeIsRegisteredWithADocumentAndATopic() {
        List<String> expected = List.of(EventContracts.TRANSACTION_AUTHORIZED,
                EventContracts.TRANSACTION_DECLINED, EventContracts.TRANSACTION_POSTED,
                EventContracts.FRAUD_FLAGGED, EventContracts.FRAUD_CLEARED,
                EventContracts.ACCOUNT_STATE_CHANGED, EventContracts.CUSTOMER_CONTEXT_CHANGED,
                EventContracts.CARD_UPDATED, EventContracts.DEAD_LETTER);

        assertEquals(expected.size(), EventContracts.eventTypes().size(),
                "the registry changed its event-type count, so an event either lost its contract "
                        + "or gained one without a document");
        for (String eventType : expected) {
            assertTrue(EventContracts.isRegistered(eventType),
                    eventType + " has no contract in the registry");
            assertNotNull(EventContracts.schemaResourceFor(eventType),
                    eventType + " names no schema document");
            assertNotNull(EventContracts.defaultTopicFor(eventType),
                    eventType + " names no topic");
        }
        assertFalse(EventContracts.isRegistered("SettlementCleared"),
                "the registry accepted an event type that has no schema document");
    }

    /**
     * Asserts the two fraud event types share one topic and every other event type has its own.
     *
     * <p>A consumer of the shared topic reads {@code eventType} to learn which payload arrived.
     */
    @Test
    void theTwoFraudEventTypesShareOneTopicAndTheOthersDoNot() {
        assertEquals("fraud.assessed", EventContracts.defaultTopicFor(EventContracts.FRAUD_FLAGGED),
                "FraudFlagged moved off the shared fraud topic");
        assertEquals("fraud.assessed", EventContracts.defaultTopicFor(EventContracts.FRAUD_CLEARED),
                "FraudCleared moved off the shared fraud topic");
        assertEquals("transaction.authorized",
                EventContracts.defaultTopicFor(EventContracts.TRANSACTION_AUTHORIZED),
                "TransactionAuthorized changed its topic");
        assertEquals("transaction.declined",
                EventContracts.defaultTopicFor(EventContracts.TRANSACTION_DECLINED),
                "TransactionDeclined changed its topic");
        assertEquals("transaction.posted",
                EventContracts.defaultTopicFor(EventContracts.TRANSACTION_POSTED),
                "TransactionPosted changed its topic");
    }

    /**
     * Asserts the topic binding accepts the default name and a configured rename, and refuses any
     * other name.
     */
    @Test
    void theTopicBindingAcceptsTheDefaultNameAndAConfiguredRenameOnly() {
        String eventType = EventContracts.TRANSACTION_AUTHORIZED;

        assertTrue(EventContracts.isBoundToTopic(eventType, "transaction.authorized"),
                "the binding rejected the default topic of " + eventType);
        assertTrue(EventContracts.isBoundToTopic(eventType, "demo.transaction.authorized",
                        "demo.transaction.authorized"),
                "the binding rejected the topic this deployment configured");
        assertFalse(EventContracts.isBoundToTopic(eventType, "transaction.declined"),
                "the binding accepted a topic that carries another event type");
        assertFalse(EventContracts.isBoundToTopic(eventType, null),
                "the binding accepted an absent topic");
    }

    /**
     * Asserts a value whose class {@code EVENT_TYPES_BY_CLASS} does not name cannot be serialized,
     * whatever its JSON looks like.
     *
     * <p>The map below carries every property the authorized document requires and a supported
     * {@code eventType}. Selecting the event type from the class of the argument is what refuses it.
     */
    @Test
    void aValueOutsideTheFiveEventRecordsCannotMasqueradeAsAnEvent() {
        Map<String, Object> impostor = new LinkedHashMap<>();
        impostor.put("eventId", "3f1d9c62-8b4e-4a17-9f0c-2d6a5e73b418");
        impostor.put("eventType", EventContracts.TRANSACTION_AUTHORIZED);
        impostor.put("schemaVersion", 1);
        impostor.put("occurredAt", "2022-06-10T19:27:53.412Z");
        impostor.put("aggregateId", ACCOUNT_IDENTIFIER);
        impostor.put("accountId", ACCOUNT_IDENTIFIER);
        impostor.put("transactionId", TRANSACTION_ID);

        try (JsonSchemaValidatingSerializer<Object> serializer =
                new JsonSchemaValidatingSerializer<>()) {
            SerializationException refused = assertThrows(SerializationException.class,
                    () -> serializer.serialize("transaction.authorized", impostor),
                    "a map carrying a supported eventType was serialized as a platform event");

            assertTrue(refused.getMessage().contains(EventContracts.TRANSACTION_AUTHORIZED),
                    "the refusal did not name the registered event types a record must be named "
                            + "for: " + refused.getMessage());
            assertTrue(refused.getMessage().contains(impostor.getClass().getSimpleName())
                            || refused.getMessage().contains("names none of them"),
                    "the refusal did not say the supplied value names no registered type: "
                            + refused.getMessage());
        }
    }

    /**
     * Asserts an event sent to a topic it does not belong on is refused before the send.
     *
     * <p>Without this guard an approval could reach the declined topic, where every consumer would
     * read it as a decline.
     */
    @Test
    void anEventSentToTheWrongTopicIsRefusedAndTheMessageNamesTheRightOne() {
        try (JsonSchemaValidatingSerializer<TransactionAuthorized> serializer =
                new JsonSchemaValidatingSerializer<>()) {
            SerializationException refused = assertThrows(SerializationException.class,
                    () -> serializer.serialize("transaction.declined", sampleAuthorized()),
                    "an approval was serialized for the declined topic");

            assertTrue(refused.getMessage().contains("transaction.authorized"),
                    "the refusal did not name the topic the event belongs on: "
                            + refused.getMessage());
        }
    }

    /**
     * Asserts a configured rename lets the same event reach the renamed topic.
     */
    @Test
    void aConfiguredTopicRenameLetsTheEventReachTheRenamedTopic() {
        try (JsonSchemaValidatingSerializer<TransactionAuthorized> serializer =
                new JsonSchemaValidatingSerializer<>()) {
            serializer.configure(Map.of(JsonSchemaValidatingSerializer.TOPIC_OVERRIDE_PREFIX
                    + EventContracts.TRANSACTION_AUTHORIZED, "demo.transaction.authorized"), false);

            assertNotNull(serializer.serialize("demo.transaction.authorized", sampleAuthorized()),
                    "the configured topic name was refused");
        }
    }

    /**
     * Asserts each of the five domain records serializes onto its own topic and returns bytes.
     *
     * <p>{@code DeadLetterEnvelope} is the sixth record the serializer accepts. It travels on the one
     * dead-letter topic rather than on a topic of its own, so it has no place in a per-topic
     * assertion, and {@code EventSerdeSecurityTest} is what holds its publish path.
     */
    @Test
    void everyEventRecordSerializesOntoItsOwnTopic() {
        assertPublishes(EventContracts.TRANSACTION_AUTHORIZED, sampleAuthorized());
        assertPublishes(EventContracts.TRANSACTION_DECLINED, sampleDeclined());
        assertPublishes(EventContracts.TRANSACTION_POSTED, samplePosted());
        assertPublishes(EventContracts.FRAUD_FLAGGED, sampleFlagged());
        assertPublishes(EventContracts.FRAUD_CLEARED, sampleCleared());
    }

    /**
     * Asserts a {@code null} value returns {@code null}, which is Kafka's tombstone contract.
     */
    @Test
    void aNullValueSerializesToTheKafkaTombstone() {
        try (JsonSchemaValidatingSerializer<TransactionAuthorized> serializer =
                new JsonSchemaValidatingSerializer<>()) {
            assertNull(serializer.serialize("transaction.authorized", null),
                    "a null value stopped serializing to the Kafka tombstone");
        }
    }

    /**
     * Asserts a validation failure reports a JSON pointer and the broken keyword and nothing else.
     *
     * <p>A message that appended the text a parser or a validator produced could carry the value
     * that failed, which for a card field is a full Primary Account Number (PAN). The payload below
     * carries such a number in {@code maskedCardNumber}.
     */
    @Test
    void aValidationFailureNamesThePointerAndTheKeywordAndNeverTheValue() {
        String fullCardNumber = syntheticCardNumber(452612877065L);
        String payload = "{\"eventId\":\"3f1d9c62-8b4e-4a17-9f0c-2d6a5e73b418\","
                + "\"eventType\":\"TransactionAuthorized\",\"schemaVersion\":1,"
                + "\"occurredAt\":\"2022-06-10T19:27:53.412Z\",\"aggregateId\":\""
                + ACCOUNT_IDENTIFIER + "\",\"transactionId\":\"" + TRANSACTION_ID + "\","
                + "\"accountId\":\"" + ACCOUNT_IDENTIFIER + "\",\"transactionTypeCode\":\"01\","
                + "\"merchantCategoryCode\":\"0001\",\"source\":\"POS TERM\","
                + "\"description\":\"Purchase\",\"amount\":\"504.77\","
                + "\"merchantId\":\"800000000\",\"merchantName\":\"Abshire-Lowe\","
                + "\"merchantCity\":\"North Enoshaven\",\"merchantZip\":\"72112\","
                + "\"maskedCardNumber\":\"" + fullCardNumber + "\","
                + "\"cardToken\":\"" + CARD_TOKEN + "\","
                + "\"authorizedAt\":\"" + AUTHORIZED_AT + "\",\"currency\":\"USD\"}";

        List<String> violations = EventContracts.violationsOf(
                EventContracts.TRANSACTION_AUTHORIZED, payload);

        assertFalse(violations.isEmpty(),
                "the authorized document accepted a full card number in maskedCardNumber");
        String reported = EventContracts.describeViolations(
                EventContracts.TRANSACTION_AUTHORIZED, violations);
        assertTrue(reported.contains("/maskedCardNumber"),
                "the report did not name the failing property: " + reported);
        assertTrue(reported.contains("pattern"),
                "the report did not name the broken keyword: " + reported);
        assertFalse(reported.contains(fullCardNumber),
                "the report carried the card number that failed, so a full Primary Account Number "
                        + "can reach a log through a validation failure");
    }

    /**
     * Asserts an unregistered event type is refused by the registry rather than silently skipped.
     */
    @Test
    void anUnregisteredEventTypeIsRefusedByTheRegistry() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> EventContracts.schemaResourceFor("SettlementCleared"),
                "the registry named a document for an event type it does not hold");

        assertTrue(refused.getMessage().contains("TransactionAuthorized"),
                "the refusal did not list the registered event types: " + refused.getMessage());
    }

    /**
     * Builds a sixteen-digit card number this repository does not carry.
     *
     * <p>The four leading digits are {@code 9999}, and none of the fifty records of
     * {@code app/data/ASCII/carddata.txt} begins with them. The value is derived without a
     * committed literal, so no card-number literal reaches this source file.
     *
     * @param serial the trailing serial, at most twelve digits
     * @return sixteen digits, opening with {@code 9999}
     */
    private static String syntheticCardNumber(long serial) {
        return "9999" + String.format("%012d", serial);
    }

    /**
     * Serializes one event onto its default topic and asserts bytes come back.
     *
     * @param eventType the routing discriminator naming the topic
     * @param event     the event to publish
     */
    private static void assertPublishes(String eventType, Object event) {
        try (JsonSchemaValidatingSerializer<Object> serializer =
                new JsonSchemaValidatingSerializer<>()) {
            byte[] bytes = serializer.serialize(EventContracts.defaultTopicFor(eventType), event);

            assertNotNull(bytes, eventType + " serialized to nothing");
            assertTrue(bytes.length > 0, eventType + " serialized to an empty payload");
        }
    }

    /**
     * Builds the sample approved event.
     *
     * @return one TransactionAuthorized the authorized document accepts
     */
    private static TransactionAuthorized sampleAuthorized() {
        return TransactionAuthorized.of(ACCOUNT_IDENTIFIER, TRANSACTION_ID, "01", "0001",
                "POS TERM", "Purchase at Abshire-Lowe", new BigDecimal("504.77"), "800000000",
                "Abshire-Lowe", "North Enoshaven", "72112", MASKED_CARD_NUMBER, CARD_TOKEN,
                AUTHORIZED_AT);
    }

    /**
     * Builds the sample declined event.
     *
     * @return one TransactionDeclined carrying reason 0102 and its source text
     */
    private static TransactionDeclined sampleDeclined() {
        return TransactionDeclined.of(ACCOUNT_IDENTIFIER, TRANSACTION_ID,
                DeclineReason.OVER_CREDIT_LIMIT, new BigDecimal("-125.00"), MASKED_CARD_NUMBER);
    }

    /**
     * Builds the sample posted event.
     *
     * @return one TransactionPosted carrying a ten-integer-digit balance
     */
    private static TransactionPosted samplePosted() {
        return TransactionPosted.forAuthorized(sampleAuthorized(), new BigDecimal("1250.75"),
                POSTED_AT);
    }

    /**
     * Builds the sample flagged event.
     *
     * @return one FraudFlagged naming one rule
     */
    private static FraudFlagged sampleFlagged() {
        return FraudFlagged.of(ACCOUNT_IDENTIFIER, TRANSACTION_ID, 82,
                List.of(FraudFlagged.VELOCITY_RULE), ASSESSED_AT);
    }

    /**
     * Builds the sample cleared event.
     *
     * @return one FraudCleared carrying the three payload components
     */
    private static FraudCleared sampleCleared() {
        return FraudCleared.of(TRANSACTION_ID, ACCOUNT_IDENTIFIER, ASSESSED_AT);
    }
}
