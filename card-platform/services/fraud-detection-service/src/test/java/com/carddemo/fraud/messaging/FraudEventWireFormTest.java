package com.carddemo.fraud.messaging;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.serde.JsonSchemaValidatingDeserializer;
import com.carddemo.events.serde.JsonSchemaValidatingSerializer;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asserts the flat wire form of the events the fraud detection service reads and writes.
 *
 * <p>The service is net new; no COBOL ancestor defines it or its events. Three groups run here: the
 * flat JavaScript Object Notation (JSON) shape, the routing discriminator, and three kinds of
 * timestamp, one outside International Organization for Standardization (ISO) 8601. Each assertion
 * reads a schema document from the classpath or the bytes one serde class wrote, never a record
 * component through reflection. Every test runs in memory and reaches no broker, no database and no
 * network.
 *
 * <p>Versions: Java 25, JUnit Jupiter 6.0.3, {@code json-schema-validator 3.0.6} for JSON Schema
 * Draft 2020-12, and {@code kafka-clients 4.2.1} for the two serde interfaces.
 */
@DisplayName("fraud event wire form")
class FraudEventWireFormTest {

    /**
     * Card token of the fixture card number, sixty-four lower-case hexadecimal characters.
     *
     * <p>Additive. No source field exists. {@code com.carddemo.cobol.PanMasker#tokenOf} writes this
     * value from the full card number {@code 4859452612877065}, and the width and case are that
     * method's.</p>
     */
    private static final String CARD_TOKEN =
            "f8da0217fb8bd2e172d427a2ef66d54656a59baa9fe8f9bc2ce9d383b90e1173";

    /** The classpath resource holding the tokenized contract an approved producer now writes. */
    private static final String AUTHORIZED_SCHEMA = "schemas/transaction-authorized-v2.json";

    /** The classpath resource holding the version 1 contract of a refused authorization. */
    private static final String DECLINED_SCHEMA = "schemas/transaction-declined-v1.json";

    /** The classpath resource holding the enriched posted-transaction contract. */
    private static final String POSTED_SCHEMA = "schemas/transaction-posted-v2.json";

    /** The classpath resource holding the contract of a flagged assessment. */
    private static final String FLAGGED_SCHEMA = "schemas/fraud-flagged-v1.json";

    /** The classpath resource holding the contract of a cleared assessment. */
    private static final String CLEARED_SCHEMA = "schemas/fraud-cleared-v1.json";

    /** The five documents these assertions measure, named one by one. */
    private static final List<String> EVERY_SCHEMA = List.of(AUTHORIZED_SCHEMA, DECLINED_SCHEMA,
            POSTED_SCHEMA, FLAGGED_SCHEMA, CLEARED_SCHEMA);

    /** The count of names each document lists in its top-level {@code required} array. */
    private static final Map<String, Integer> REQUIRED_COUNTS = Map.of(
            AUTHORIZED_SCHEMA, 20,
            DECLINED_SCHEMA, 11,
            POSTED_SCHEMA, 21,
            FLAGGED_SCHEMA, 10,
            CLEARED_SCHEMA, 8);

    /** The five envelope names every document lists beside its payload names. */
    private static final List<String> ENVELOPE_PROPERTIES =
            List.of("eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId");

    /** The one topic that carries a flagged assessment and a cleared assessment together. */
    private static final String FRAUD_TOPIC = "fraud.assessed";

    /** The topic the fraud detection service reads. */
    private static final String AUTHORIZED_TOPIC = "transaction.authorized";

    /** The account identifier the first flagged card of the demonstration feed resolves to. */
    private static final String ACCOUNT_ID = "00000000007";

    /** The transaction identifier of record 1 of the demonstration feed, sixteen characters. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /** The authorization timestamp every one of the 300 feed records carries. */
    private static final String AUTHORIZED_AT = "2022-06-10 19:27:53.000000";

    /** The moment the risk rules finished, as an ISO 8601 timestamp. */
    private static final Instant ASSESSED_AT = Instant.parse("2022-06-10T19:27:53.512Z");

    /** The score a flagged assessment carries in these assertions. */
    private static final int RISK_SCORE = 82;

    /** Twelve asterisks and the last four digits, the one form a published event carries. */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /** A full sixteen-digit value, used once, in a document these assertions expect refused. */
    private static final String UNMASKED_VALUE = "4859452612877065";

    /** Record 1's amount, decoded from its overpunched field, as a decimal string. */
    private static final String AMOUNT = "504.77";

    /** A negative amount the feed carries, as a decimal string. */
    private static final String NEGATIVE_AMOUNT = "-919.00";

    /** A second negative amount the feed carries, as a decimal string. */
    private static final String SECOND_NEGATIVE_AMOUNT = "-56.77";

    /** A balance of ten integer digits, which the amount width refuses and the balance allows. */
    private static final String TEN_INTEGER_DIGIT_VALUE = "9876543210.99";

    /** Reads a schema document or a serialized event as a tree. Writes nothing to a topic. */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** The registry that reads JSON Schema Draft 2020-12, the dialect all five documents use. */
    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    /** The publish side, constructed directly and shared by all three groups. */
    private static final JsonSchemaValidatingSerializer<Object> SERIALIZER =
            new JsonSchemaValidatingSerializer<>();

    /** The consume side, constructed directly and accepting every governed event type. */
    private static final JsonSchemaValidatingDeserializer<Object> DESERIALIZER =
            new JsonSchemaValidatingDeserializer<>();

    /** A flat flagged assessment carrying all ten required properties at the top level. */
    private static final String FLAGGED_DOCUMENT = """
            {
              "eventId": "3f1d9c62-8b4e-4a17-9f0c-2d6a5e73b418",
              "eventType": "FraudFlagged",
              "schemaVersion": 1,
              "occurredAt": "2022-06-10T19:27:53.412Z",
              "aggregateId": "00000000007",
              "transactionId": "%s",
              "accountId": "00000000007",
              "riskScore": 82,
              "triggeredRules": ["VELOCITY"],
              "assessedAt": "2022-06-10T19:27:53.512Z"
            }""".formatted(TRANSACTION_ID);

    /** A flat cleared assessment carrying all eight required properties at the top level. */
    private static final String CLEARED_DOCUMENT = """
            {
              "eventId": "9c0b7a41-2e58-4d63-8f1a-6b4c5d7e9012",
              "eventType": "FraudCleared",
              "schemaVersion": 1,
              "occurredAt": "2022-06-10T19:27:53.412Z",
              "aggregateId": "00000000007",
              "transactionId": "%s",
              "accountId": "00000000007",
              "assessedAt": "2022-06-10T19:27:53.512Z"
            }""".formatted(TRANSACTION_ID);

    /** A flat authorization carrying all twenty version-two properties at the top level. */
    private static final String AUTHORIZED_DOCUMENT = """
            {
              "eventId": "7a2e4c18-5b39-4f07-9d6a-1c8b3e5f7042",
              "eventType": "TransactionAuthorized",
              "schemaVersion": 2,
              "occurredAt": "2022-06-10T19:27:53.412Z",
              "aggregateId": "00000000007",
              "transactionId": "%s",
              "accountId": "00000000007",
              "transactionTypeCode": "01",
              "merchantCategoryCode": "0001",
              "source": "POS TERM  ",
              "description": "Purchase at Abshire-Lowe",
              "amount": "504.77",
              "merchantId": "800000000",
              "merchantName": "Abshire-Lowe",
              "merchantCity": "North Enoshaven",
              "merchantZip": "72112     ",
              "maskedCardNumber": "************7065",
              "cardToken": "87a66184e8551b4490858314b32730140e680761f8280a47cfc19c3d81577db4",
              "authorizedAt": "2022-06-10 19:27:53.000000",
              "currency": "USD"
            }""".formatted(TRANSACTION_ID);

    /** A flagged assessment whose five envelope properties sit under an {@code envelope} key. */
    private static final String NESTED_FLAGGED_DOCUMENT = """
            {
              "envelope": {
                "eventId": "3f1d9c62-8b4e-4a17-9f0c-2d6a5e73b418",
                "eventType": "FraudFlagged",
                "schemaVersion": 1,
                "occurredAt": "2022-06-10T19:27:53.412Z",
                "aggregateId": "00000000007"
              },
              "transactionId": "%s",
              "accountId": "00000000007",
              "riskScore": 82,
              "triggeredRules": ["VELOCITY"],
              "assessedAt": "2022-06-10T19:27:53.512Z"
            }""".formatted(TRANSACTION_ID);

    /** A cleared assessment whose five envelope properties sit under an {@code envelope} key. */
    private static final String NESTED_CLEARED_DOCUMENT = """
            {
              "envelope": {
                "eventId": "9c0b7a41-2e58-4d63-8f1a-6b4c5d7e9012",
                "eventType": "FraudCleared",
                "schemaVersion": 1,
                "occurredAt": "2022-06-10T19:27:53.412Z",
                "aggregateId": "00000000007"
              },
              "transactionId": "%s",
              "accountId": "00000000007",
              "assessedAt": "2022-06-10T19:27:53.512Z"
            }""".formatted(TRANSACTION_ID);

    /** An authorization whose five envelope properties sit under an {@code envelope} key. */
    private static final String NESTED_AUTHORIZED_DOCUMENT = """
            {
              "envelope": {
                "eventId": "7a2e4c18-5b39-4f07-9d6a-1c8b3e5f7042",
                "eventType": "TransactionAuthorized",
                "schemaVersion": 2,
                "occurredAt": "2022-06-10T19:27:53.412Z",
                "aggregateId": "00000000007"
              },
              "transactionId": "%s",
              "accountId": "00000000007",
              "transactionTypeCode": "01",
              "merchantCategoryCode": "0001",
              "source": "POS TERM  ",
              "description": "Purchase at Abshire-Lowe",
              "amount": "504.77",
              "merchantId": "800000000",
              "merchantName": "Abshire-Lowe",
              "merchantCity": "North Enoshaven",
              "merchantZip": "72112     ",
              "maskedCardNumber": "************7065",
              "cardToken": "87a66184e8551b4490858314b32730140e680761f8280a47cfc19c3d81577db4",
              "authorizedAt": "2022-06-10 19:27:53.000000",
              "currency": "USD"
            }""".formatted(TRANSACTION_ID);

    /** The three nested documents, one shape per event type the fraud service handles. */
    private static final List<String> NESTED_DOCUMENTS = List.of(NESTED_FLAGGED_DOCUMENT,
            NESTED_CLEARED_DOCUMENT, NESTED_AUTHORIZED_DOCUMENT);

    /**
     * Reads one schema document from the classpath as a tree.
     *
     * @param resource the classpath name of the document
     * @return the parsed document
     */
    private static JsonNode schemaDocument(String resource) {
        try (InputStream document = openSchema(resource)) {
            return MAPPER.readTree(document);
        } catch (IOException cause) {
            throw new UncheckedIOException("Reading " + resource + " failed.", cause);
        }
    }

    /**
     * Compiles one schema document read from the classpath.
     *
     * @param resource the classpath name of the document
     * @return the compiled schema
     */
    private static Schema compiledSchema(String resource) {
        try (InputStream document = openSchema(resource)) {
            return REGISTRY.getSchema(document, InputFormat.JSON);
        } catch (IOException cause) {
            throw new UncheckedIOException("Reading " + resource + " failed.", cause);
        }
    }

    /**
     * Opens one schema document and asserts the classpath carries it.
     *
     * @param resource the classpath name of the document
     * @return the open stream
     */
    private static InputStream openSchema(String resource) {
        InputStream document =
                FraudEventWireFormTest.class.getClassLoader().getResourceAsStream(resource);

        assertNotNull(document, resource + " ships inside the event-contracts module");
        return document;
    }

    /**
     * Measures one document against one schema.
     *
     * @param resource the classpath name of the schema document
     * @param json     the document to measure
     * @return one entry per failure, each a JSON pointer and the keyword it broke
     */
    private static List<String> violations(String resource, String json) {
        List<String> reported = new ArrayList<>();
        for (Error violation : compiledSchema(resource).validate(json, InputFormat.JSON)) {
            String property = violation.getProperty();
            String location = violation.getInstanceLocation().toString();
            reported.add((property == null || property.isBlank() ? location
                    : location + "/" + property) + " (" + violation.getKeyword() + ")");
        }
        return List.copyOf(reported);
    }

    /**
     * The names one JSON object declares, in declaration order.
     *
     * @param object the object to read
     * @return its property names
     */
    private static List<String> propertyNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.propertyNames().forEach(names::add);
        return List.copyOf(names);
    }

    /**
     * The names one document lists in its top-level {@code required} array.
     *
     * @param resource the classpath name of the schema document
     * @return the required names, in the order the document lists them
     */
    private static List<String> requiredNames(String resource) {
        List<String> names = new ArrayList<>();
        schemaDocument(resource).path("required").forEach(name -> names.add(name.stringValue()));
        return List.copyOf(names);
    }

    /**
     * The event type one document pins with {@code const}, derived from the document name.
     *
     * <p>A name of {@code schemas/fraud-flagged-v1.json} yields {@code FraudFlagged}, so a document
     * name and the discriminator it pins cannot drift apart without a failure here.
     *
     * @param resource the classpath name of the schema document
     * @return the event type the document name spells
     */
    private static String eventTypeOf(String resource) {
        String stem = resource.substring(resource.indexOf('/') + 1, resource.lastIndexOf("-v"));
        StringBuilder eventType = new StringBuilder();
        for (String word : stem.split("-")) {
            eventType.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return eventType.toString();
    }

    /**
     * Replaces one property of a document and asserts the original property was present.
     *
     * @param document the document to change
     * @param original the exact text to replace
     * @param altered  the text to put in its place
     * @return the changed document
     */
    private static String withProperty(String document, String original, String altered) {
        assertTrue(document.contains(original), "the document carries " + original);
        return document.replace(original, altered);
    }

    /**
     * The authorization document carrying one amount in place of record 1's amount.
     *
     * @param amount the amount text, quoted by the caller when it is a string
     * @return the changed document
     */
    private static String withAmount(String amount) {
        return withProperty(AUTHORIZED_DOCUMENT, "\"amount\": \"" + AMOUNT + "\"",
                "\"amount\": " + amount);
    }

    /**
     * Writes one event through the publish side and returns the checked text.
     *
     * @param topic the topic the event belongs on
     * @param event the event to write
     * @return the checked JSON text
     */
    private static String wireFormOf(String topic, Object event) {
        byte[] bytes = SERIALIZER.serialize(topic, event);

        assertNotNull(bytes, "the publish side wrote bytes for " + topic);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * A flagged assessment naming one rule.
     *
     * @return an event every check accepts
     */
    private static FraudFlagged aFlaggedAssessment() {
        return FraudFlagged.of(ACCOUNT_ID, TRANSACTION_ID, RISK_SCORE,
                List.of(FraudFlagged.VELOCITY_RULE), ASSESSED_AT);
    }

    /**
     * A cleared assessment, the smallest event of the platform.
     *
     * @return an event every check accepts
     */
    private static FraudCleared aClearedAssessment() {
        return FraudCleared.of(TRANSACTION_ID, ACCOUNT_ID, ASSESSED_AT);
    }

    /**
     * An authorization carrying record 1 of the feed, with its card number masked.
     *
     * @return an event every check accepts
     */
    private static TransactionAuthorized anAuthorization() {
        return TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS TERM  ",
                "Purchase at Abshire-Lowe", new BigDecimal(AMOUNT), "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112     ", MASKED_CARD_NUMBER, CARD_TOKEN, AUTHORIZED_AT);
    }

    /**
     * Reads one authorization document through a consumer built for that event type.
     *
     * @param document the flat document to read
     * @return the record the consume side built
     */
    private static TransactionAuthorized readAuthorization(String document) {
        return readAs(TransactionAuthorized.class, AUTHORIZED_TOPIC, document);
    }

    /**
     * Reads one flagged assessment through a consumer built for that event type.
     *
     * @param document the flat document to read
     * @return the record the consume side built
     */
    private static FraudFlagged readFlaggedAssessment(String document) {
        return readAs(FraudFlagged.class, FRAUD_TOPIC, document);
    }

    /**
     * Reads one cleared assessment through a consumer built for that event type.
     *
     * @param document the flat document to read
     * @return the record the consume side built
     */
    private static FraudCleared readClearedAssessment(String document) {
        return readAs(FraudCleared.class, FRAUD_TOPIC, document);
    }

    /**
     * Reads one document through a consumer built for one event type.
     *
     * @param type     the record class the consumer builds
     * @param topic    the topic the record arrived on
     * @param document the flat document to read
     * @param <T>      the record type
     * @return the record the consume side built
     */
    private static <T> T readAs(Class<T> type, String topic, String document) {
        try (JsonSchemaValidatingDeserializer<T> consumer =
                new JsonSchemaValidatingDeserializer<>(type)) {
            T arrived = consumer.deserialize(topic, document.getBytes(StandardCharsets.UTF_8));

            assertNotNull(arrived, "the consume side built a " + type.getSimpleName());
            return arrived;
        }
    }

    /**
     * Asserts that one event is one JSON object, with its five envelope properties beside its
     * payload properties.
     *
     * <p>Each document closes its property set and lists every name in one top-level
     * {@code required} array, so a nested envelope fails twice over.
     */
    @Nested
    @DisplayName("group A: the flat wire form")
    class FlatWireForm {

        @Test
        @DisplayName("each document requires 20, 11, 21, 10 and 8 properties at the top level")
        void eachDocumentRequiresItsCountOfProperties() {
            assertAll(EVERY_SCHEMA.stream().map(resource -> () -> assertEquals(
                    REQUIRED_COUNTS.get(resource).intValue(), requiredNames(resource).size(),
                    resource + " requires " + REQUIRED_COUNTS.get(resource) + " properties")));
        }

        @Test
        @DisplayName("each document is one closed object")
        void eachDocumentIsOneClosedObject() {
            assertAll(EVERY_SCHEMA.stream().map(resource -> () -> {
                JsonNode document = schemaDocument(resource);

                assertEquals("object", document.path("type").stringValue(),
                        resource + " declares one object");
                assertTrue(document.path("additionalProperties").isBoolean(),
                        resource + " states additionalProperties as a boolean");
                assertFalse(document.path("additionalProperties").booleanValue(),
                        resource + " admits no undeclared property");
            }));
        }

        @Test
        @DisplayName("each document requires the five envelope names at the top level")
        void eachDocumentRequiresTheEnvelopeNamesAtTheTopLevel() {
            assertAll(EVERY_SCHEMA.stream().map(resource -> () -> {
                List<String> required = requiredNames(resource);
                List<String> declared = propertyNames(schemaDocument(resource).path("properties"));

                assertTrue(required.containsAll(ENVELOPE_PROPERTIES),
                        resource + " requires " + ENVELOPE_PROPERTIES + ", and requires "
                                + required);
                assertTrue(declared.containsAll(ENVELOPE_PROPERTIES),
                        resource + " declares the five envelope names beside its payload names");
                assertFalse(declared.contains("envelope"),
                        resource + " declares no envelope property");
            }));
        }

        @Test
        @DisplayName("a flagged assessment serializes to exactly ten top-level properties")
        void aFlaggedAssessmentSerializesToTenProperties() {
            JsonNode written = MAPPER.readTree(wireFormOf(FRAUD_TOPIC, aFlaggedAssessment()));

            assertEquals(REQUIRED_COUNTS.get(FLAGGED_SCHEMA).intValue(), written.size(),
                    "the written assessment holds ten properties: " + propertyNames(written));
            assertEquals(requiredNames(FLAGGED_SCHEMA).stream().sorted().toList(),
                    propertyNames(written).stream().sorted().toList(),
                    "the written names are the required names");
        }

        @Test
        @DisplayName("a cleared assessment serializes to exactly eight top-level properties")
        void aClearedAssessmentSerializesToEightProperties() {
            JsonNode written = MAPPER.readTree(wireFormOf(FRAUD_TOPIC, aClearedAssessment()));

            assertEquals(REQUIRED_COUNTS.get(CLEARED_SCHEMA).intValue(), written.size(),
                    "the written assessment holds eight properties: " + propertyNames(written));
            assertEquals(requiredNames(CLEARED_SCHEMA).stream().sorted().toList(),
                    propertyNames(written).stream().sorted().toList(),
                    "the written names are the required names");
        }

        @Test
        @DisplayName("a twenty-property flat authorization binds every field")
        void aFlatAuthorizationBindsEveryField() {
            TransactionAuthorized arrived = readAuthorization(AUTHORIZED_DOCUMENT);

            assertAll(
                    () -> assertEquals("7a2e4c18-5b39-4f07-9d6a-1c8b3e5f7042",
                            arrived.eventId().toString(), "eventId"),
                    () -> assertEquals(TransactionAuthorized.EVENT_TYPE, arrived.eventType(),
                            "eventType"),
                    () -> assertEquals(TransactionAuthorized.CARD_TOKEN_SCHEMA_VERSION,
                            arrived.schemaVersion(),
                            "schemaVersion"),
                    () -> assertEquals(Instant.parse("2022-06-10T19:27:53.412Z"),
                            arrived.occurredAt(), "occurredAt"),
                    () -> assertEquals(ACCOUNT_ID, arrived.aggregateId(), "aggregateId"),
                    () -> assertEquals(TRANSACTION_ID, arrived.transactionId(), "transactionId"),
                    () -> assertEquals(ACCOUNT_ID, arrived.accountId(), "accountId"),
                    () -> assertEquals("01", arrived.transactionTypeCode(), "transactionTypeCode"),
                    () -> assertEquals("0001", arrived.merchantCategoryCode(),
                            "merchantCategoryCode"),
                    () -> assertEquals("POS TERM  ", arrived.source(), "source"),
                    () -> assertEquals("Purchase at Abshire-Lowe", arrived.description(),
                            "description"),
                    () -> assertEquals(new BigDecimal(AMOUNT), arrived.amount(), "amount"),
                    () -> assertEquals("800000000", arrived.merchantId(), "merchantId"),
                    () -> assertEquals("Abshire-Lowe", arrived.merchantName(), "merchantName"),
                    () -> assertEquals("North Enoshaven", arrived.merchantCity(), "merchantCity"),
                    () -> assertEquals("72112     ", arrived.merchantZip(), "merchantZip"),
                    () -> assertEquals(MASKED_CARD_NUMBER, arrived.maskedCardNumber(),
                            "maskedCardNumber"),
                    () -> assertEquals(AUTHORIZED_AT, arrived.authorizedAt(), "authorizedAt"),
                    () -> assertEquals(TransactionAuthorized.CURRENCY, arrived.currency(),
                            "currency"));
        }

        @Test
        @DisplayName("a nested envelope fails all five documents, on two counts each")
        void aNestedEnvelopeFailsAllFiveDocuments() {
            assertAll(EVERY_SCHEMA.stream().map(resource -> () ->
                    NESTED_DOCUMENTS.forEach(document -> {
                        List<String> reported = violations(resource, document);

                        assertTrue(reported.contains("/envelope (additionalProperties)"),
                                resource + " declares no envelope name: " + reported);
                        ENVELOPE_PROPERTIES.forEach(name -> assertTrue(
                                reported.contains("/" + name + " (required)"),
                                name + " is missing from the top level: " + reported));
                    })));
        }

        @Test
        @DisplayName("a nested envelope reaches no record")
        void aNestedEnvelopeReachesNoRecord() {
            byte[] bytes = NESTED_FLAGGED_DOCUMENT.getBytes(StandardCharsets.UTF_8);

            assertThrows(SerializationException.class,
                    () -> DESERIALIZER.deserialize(FRAUD_TOPIC, bytes),
                    "a nested envelope builds no event");
        }

        @Test
        @DisplayName("schemaVersion and riskScore travel as bare numbers")
        void theTwoIntegerPropertiesTravelAsBareNumbers() {
            String written = wireFormOf(FRAUD_TOPIC, aFlaggedAssessment());
            JsonNode tree = MAPPER.readTree(written);
            JsonNode pinned = schemaDocument(FLAGGED_SCHEMA).path("properties")
                    .path("schemaVersion").path("const");

            assertAll(
                    () -> assertTrue(tree.path("schemaVersion").isInt(),
                            "schemaVersion is a JSON integer: " + written),
                    () -> assertEquals(EventEnvelope.SCHEMA_VERSION,
                            tree.path("schemaVersion").intValue(), "schemaVersion"),
                    () -> assertTrue(written.contains("\"schemaVersion\":1"),
                            "the text carries an unquoted 1: " + written),
                    () -> assertTrue(pinned.isInt(), "the document pins an integer"),
                    () -> assertEquals(EventEnvelope.SCHEMA_VERSION, pinned.intValue(),
                            "the pinned version"),
                    () -> assertTrue(tree.path("riskScore").isInt(),
                            "riskScore is a JSON integer: " + written),
                    () -> assertEquals(RISK_SCORE, tree.path("riskScore").intValue(), "riskScore"));
        }

        @Test
        @DisplayName("a quoted schemaVersion is refused")
        void aQuotedSchemaVersionIsRefused() {
            String quoted = withProperty(FLAGGED_DOCUMENT, "\"schemaVersion\": 1",
                    "\"schemaVersion\": \"1\"");

            assertFalse(violations(FLAGGED_SCHEMA, quoted).isEmpty(),
                    "a quoted contract version breaks the integer type");
            assertThrows(SerializationException.class, () -> DESERIALIZER.deserialize(FRAUD_TOPIC,
                    quoted.getBytes(StandardCharsets.UTF_8)), "a quoted version builds no event");
        }

        @Test
        @DisplayName("aggregateId takes eleven digits and refuses ten and twelve")
        void aggregateIdTakesElevenDigits() {
            String tenDigits = withProperty(FLAGGED_DOCUMENT,
                    "\"aggregateId\": \"00000000007\"", "\"aggregateId\": \"0000000007\"");
            String twelveDigits = withProperty(FLAGGED_DOCUMENT,
                    "\"aggregateId\": \"00000000007\"", "\"aggregateId\": \"000000000007\"");

            assertAll(
                    () -> assertTrue(violations(FLAGGED_SCHEMA, FLAGGED_DOCUMENT).isEmpty(),
                            "eleven digits pass"),
                    () -> assertFalse(violations(FLAGGED_SCHEMA, tenDigits).isEmpty(),
                            "ten digits fail"),
                    () -> assertFalse(violations(FLAGGED_SCHEMA, twelveDigits).isEmpty(),
                            "twelve digits fail"),
                    () -> assertEquals(EventEnvelope.AGGREGATE_ID_PATTERN,
                            schemaDocument(FLAGGED_SCHEMA).path("properties").path("aggregateId")
                                    .path("pattern").stringValue(),
                            "the document and the record share one pattern"));
        }

        @Test
        @DisplayName("an amount travels as a decimal string, and a negative one is ordinary")
        void anAmountTravelsAsADecimalString() {
            String written = wireFormOf(AUTHORIZED_TOPIC, anAuthorization());
            String bareNumber = withAmount("504.77");

            assertTrue(MAPPER.readTree(written).path("amount").isString(),
                    "the written amount is a string: " + written);
            assertTrue(written.contains("\"amount\":\"" + AMOUNT + "\""),
                    "the written amount keeps two fractional digits: " + written);
            assertFalse(violations(AUTHORIZED_SCHEMA, bareNumber).isEmpty(),
                    "a bare number in the amount position fails");
            assertAll(List.of(NEGATIVE_AMOUNT, SECOND_NEGATIVE_AMOUNT).stream()
                    .map(amount -> () -> assertTrue(
                            violations(AUTHORIZED_SCHEMA, withAmount("\"" + amount + "\""))
                                    .isEmpty(),
                            amount + " passes the amount width")));
        }

        @Test
        @DisplayName("the amount width allows nine integer digits and the balance width allows ten")
        void theTwoMoneyWidthsDiffer() {
            String amountPattern = schemaDocument(AUTHORIZED_SCHEMA).path("properties")
                    .path("amount").path("pattern").stringValue();
            String balancePattern = schemaDocument(POSTED_SCHEMA).path("properties")
                    .path("newBalance").path("pattern").stringValue();

            assertNotEquals(amountPattern, balancePattern,
                    "the two money widths are declared apart");
            assertFalse(Pattern.compile(amountPattern).matcher(TEN_INTEGER_DIGIT_VALUE).matches(),
                    "ten integer digits break the amount width " + amountPattern);
            assertTrue(Pattern.compile(balancePattern).matcher(TEN_INTEGER_DIGIT_VALUE).matches(),
                    "ten integer digits fit the balance width " + balancePattern);
            assertFalse(violations(AUTHORIZED_SCHEMA,
                            withAmount("\"" + TEN_INTEGER_DIGIT_VALUE + "\"")).isEmpty(),
                    "a ten-integer-digit amount fails the authorization document");
        }

        @Test
        @DisplayName("an unknown property is refused, and no partly built event is returned")
        void anUnknownPropertyIsRefused() {
            String extra = withProperty(FLAGGED_DOCUMENT, "\"riskScore\": 82",
                    "\"unexpectedProperty\": \"x\",\n  \"riskScore\": 82");

            assertTrue(violations(FLAGGED_SCHEMA, extra)
                            .contains("/unexpectedProperty (additionalProperties)"),
                    "the closed object refuses an undeclared name");
            assertThrows(SerializationException.class, () -> DESERIALIZER.deserialize(FRAUD_TOPIC,
                    extra.getBytes(StandardCharsets.UTF_8)), "no event is built");
        }

        @Test
        @DisplayName("a full sixteen-digit value in the masked position is refused")
        void aFullValueInTheMaskedPositionIsRefused() {
            String unmasked = withProperty(AUTHORIZED_DOCUMENT,
                    "\"maskedCardNumber\": \"" + MASKED_CARD_NUMBER + "\"",
                    "\"maskedCardNumber\": \"" + UNMASKED_VALUE + "\"");

            assertTrue(violations(AUTHORIZED_SCHEMA, unmasked)
                            .contains("/maskedCardNumber (pattern)"),
                    "the masked pattern refuses an unmasked value");
            assertEquals(TransactionAuthorized.MASKED_CARD_NUMBER_PATTERN,
                    schemaDocument(AUTHORIZED_SCHEMA).path("properties").path("maskedCardNumber")
                            .path("pattern").stringValue(),
                    "the document and the record share one masked pattern");
        }

        @Test
        @DisplayName("null yields null at both ends, and empty bytes throw")
        void nullYieldsNullAtBothEndsAndEmptyBytesThrow() {
            assertNull(SERIALIZER.serialize(FRAUD_TOPIC, null), "a null payload is a tombstone");
            assertNull(DESERIALIZER.deserialize(FRAUD_TOPIC, null), "null bytes are a tombstone");
            assertThrows(SerializationException.class,
                    () -> DESERIALIZER.deserialize(FRAUD_TOPIC, new byte[0]),
                    "an empty array carries no object");
        }

        @Test
        @DisplayName("a failure escapes, names the document, counts the faults and points at each")
        void aFailureEscapesAndNamesTheDocumentTheCountAndEachPointer() {
            String overMaximum = withProperty(FLAGGED_DOCUMENT, "\"riskScore\": 82",
                    "\"riskScore\": 101");

            SerializationException refused = assertThrows(SerializationException.class,
                    () -> DESERIALIZER.deserialize(FRAUD_TOPIC,
                            overMaximum.getBytes(StandardCharsets.UTF_8)));
            String reported = refused.getMessage();

            assertAll(
                    () -> assertTrue(reported.contains(FLAGGED_SCHEMA),
                            "the message names the document: " + reported),
                    () -> assertTrue(reported.contains("1 property"),
                            "the message counts the faults: " + reported),
                    () -> assertTrue(reported.contains("/riskScore"),
                            "the message points at the property: " + reported),
                    () -> assertFalse(reported.contains("could not build"),
                            "the check ran before any binding started: " + reported),
                    () -> assertFalse(reported.contains("101"),
                            "the message withholds the value: " + reported),
                    () -> assertFalse(reported.contains(TRANSACTION_ID),
                            "the message withholds the transaction identifier: " + reported),
                    () -> assertFalse(reported.contains(ACCOUNT_ID),
                            "the message withholds the account identifier: " + reported));
        }

        @Test
        @DisplayName("a misconfigured producer is stopped at the publish gate")
        void aMisconfiguredProducerIsStoppedAtThePublishGate() {
            ObjectMapper epochMapper = JsonMapper.builder()
                    .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();
            FraudFlagged assessment = aFlaggedAssessment();

            try (JsonSchemaValidatingSerializer<FraudFlagged> misconfigured =
                    new JsonSchemaValidatingSerializer<>(epochMapper)) {
                SerializationException refused = assertThrows(SerializationException.class,
                        () -> misconfigured.serialize(FRAUD_TOPIC, assessment));

                assertTrue(refused.getMessage().contains(FLAGGED_SCHEMA),
                        "the message names the document: " + refused.getMessage());
                assertTrue(refused.getMessage().contains("/occurredAt"),
                        "the message points at the property: " + refused.getMessage());
                assertFalse(refused.getMessage().contains(TRANSACTION_ID),
                        "the message withholds the payload: " + refused.getMessage());
            }
        }

        @Test
        @DisplayName("a payload that is no registered event reaches no topic")
        void aPayloadThatIsNoRegisteredEventReachesNoTopic() {
            EventEnvelope carrier = EventEnvelope.of(FraudFlagged.EVENT_TYPE, ACCOUNT_ID);

            assertThrows(SerializationException.class,
                    () -> SERIALIZER.serialize(FRAUD_TOPIC, carrier),
                    "the publish side writes a registered event and nothing else");
        }
    }

    /**
     * Asserts that {@code eventType} separates the two assessments sharing one topic, and that a
     * cleared assessment carries three payload properties.
     *
     * <p>A cleared assessment is a strict subset of a flagged one, so a check for a score property
     * would misread a malformed message and {@code eventType} settles every routing question.
     */
    @Nested
    @DisplayName("group B: the discriminator and the small cleared payload")
    class Discriminator {

        @Test
        @DisplayName("each document pins eventType to the simple name of its record")
        void eachDocumentPinsItsEventType() {
            assertAll(EVERY_SCHEMA.stream().map(resource -> () -> assertEquals(
                    eventTypeOf(resource),
                    schemaDocument(resource).path("properties").path("eventType").path("const")
                            .stringValue(),
                    resource + " pins its own event type")));
            assertEquals(FraudFlagged.class.getSimpleName(), eventTypeOf(FLAGGED_SCHEMA),
                    "the flagged record and its document carry one name");
            assertEquals(FraudCleared.class.getSimpleName(), eventTypeOf(CLEARED_SCHEMA),
                    "the cleared record and its document carry one name");
            assertEquals(TransactionAuthorized.class.getSimpleName(),
                    eventTypeOf(AUTHORIZED_SCHEMA),
                    "the authorization record and its document carry one name");
        }

        @Test
        @DisplayName("a document naming another event type is refused by the document it meets")
        void aDocumentNamingAnotherEventTypeIsRefused() {
            String relabelled = withProperty(CLEARED_DOCUMENT,
                    "\"eventType\": \"" + FraudCleared.EVENT_TYPE + "\"",
                    "\"eventType\": \"" + FraudFlagged.EVENT_TYPE + "\"");

            assertFalse(violations(CLEARED_SCHEMA, relabelled).isEmpty(),
                    "the cleared document pins its own event type with const");
            assertFalse(violations(FLAGGED_SCHEMA, relabelled).isEmpty(),
                    "the flagged document requires a score and a rule list the payload lacks");
        }

        @Test
        @DisplayName("a consumer built for one assessment refuses the other")
        void aConsumerBuiltForOneAssessmentRefusesTheOther() {
            byte[] flagged = wireFormOf(FRAUD_TOPIC, aFlaggedAssessment())
                    .getBytes(StandardCharsets.UTF_8);

            try (JsonSchemaValidatingDeserializer<FraudCleared> clearedOnly =
                    new JsonSchemaValidatingDeserializer<>(FraudCleared.class)) {
                SerializationException refused = assertThrows(SerializationException.class,
                        () -> clearedOnly.deserialize(FRAUD_TOPIC, flagged));

                assertTrue(refused.getMessage().contains(FraudCleared.EVENT_TYPE),
                        "the message names the event type wanted: " + refused.getMessage());
                assertTrue(refused.getMessage().contains(FraudFlagged.EVENT_TYPE),
                        "the message names the event type that arrived: " + refused.getMessage());
            }
        }

        @Test
        @DisplayName("one topic carries both assessments and each meets its own document")
        void oneTopicCarriesBothAssessments() {
            String flagged = wireFormOf(FRAUD_TOPIC, aFlaggedAssessment());
            String cleared = wireFormOf(FRAUD_TOPIC, aClearedAssessment());

            assertEquals(FraudFlagged.EVENT_TYPE, MAPPER.readTree(flagged).path("eventType")
                    .stringValue(), "the flagged assessment names itself");
            assertEquals(FraudCleared.EVENT_TYPE, MAPPER.readTree(cleared).path("eventType")
                    .stringValue(), "the cleared assessment names itself");
            assertTrue(violations(FLAGGED_SCHEMA, flagged).isEmpty(),
                    "the flagged assessment meets the flagged document");
            assertTrue(violations(CLEARED_SCHEMA, cleared).isEmpty(),
                    "the cleared assessment meets the cleared document");
            assertFalse(violations(CLEARED_SCHEMA, flagged).isEmpty(),
                    "the flagged assessment does not meet the cleared document");
            assertThrows(SerializationException.class,
                    () -> SERIALIZER.serialize(AUTHORIZED_TOPIC, aFlaggedAssessment()),
                    "the publish side keeps an assessment off the topic it consumes");
        }

        @Test
        @DisplayName("the topic name selects no document")
        void theTopicNameSelectsNoDocument() {
            byte[] flagged = wireFormOf(FRAUD_TOPIC, aFlaggedAssessment())
                    .getBytes(StandardCharsets.UTF_8);

            Object arrived = DESERIALIZER.deserialize("a.name.no.deployment.uses", flagged);

            assertNotNull(arrived, "the consume side read the bytes");
            assertEquals(FraudFlagged.class, arrived.getClass(),
                    "eventType alone chose the record");
        }

        @Test
        @DisplayName("eventType is read at the top level only")
        void eventTypeIsReadAtTheTopLevelOnly() {
            byte[] nested = NESTED_CLEARED_DOCUMENT.getBytes(StandardCharsets.UTF_8);

            SerializationException refused = assertThrows(SerializationException.class,
                    () -> DESERIALIZER.deserialize(FRAUD_TOPIC, nested));

            assertTrue(refused.getMessage().contains("eventType"),
                    "the message names the property it looked for: " + refused.getMessage());
        }

        @Test
        @DisplayName("a blank, an absent and an unknown eventType are each refused")
        void threeFaultyEventTypesAreRefused() {
            String blank = withProperty(FLAGGED_DOCUMENT,
                    "\"eventType\": \"" + FraudFlagged.EVENT_TYPE + "\"", "\"eventType\": \"  \"");
            String absent = withProperty(FLAGGED_DOCUMENT,
                    "  \"eventType\": \"" + FraudFlagged.EVENT_TYPE + "\",\n", "");
            String unknown = withProperty(FLAGGED_DOCUMENT,
                    "\"eventType\": \"" + FraudFlagged.EVENT_TYPE + "\"",
                    "\"eventType\": \"NoSuchEvent\"");

            assertAll(List.of(blank, absent, unknown).stream().map(document -> () ->
                    assertThrows(SerializationException.class, () -> DESERIALIZER.deserialize(
                            FRAUD_TOPIC, document.getBytes(StandardCharsets.UTF_8)),
                            "no schema selects the document")));
        }

        @Test
        @DisplayName("configure changes nothing the consume side does")
        void configureChangesNothingTheConsumeSideDoes() {
            byte[] flagged = wireFormOf(FRAUD_TOPIC, aFlaggedAssessment())
                    .getBytes(StandardCharsets.UTF_8);

            DESERIALIZER.configure(Map.of(), false);
            Object afterEmpty = DESERIALIZER.deserialize(FRAUD_TOPIC, flagged);
            DESERIALIZER.configure(Map.of("a.property.no.key.matches", "a value"), true);
            Object afterPopulated = DESERIALIZER.deserialize(FRAUD_TOPIC, flagged);

            assertEquals(FraudFlagged.class, afterEmpty.getClass(), "an empty map changes nothing");
            assertEquals(afterEmpty, afterPopulated, "a populated map changes nothing");
        }

        @Test
        @DisplayName("a cleared assessment carries exactly transactionId, accountId and assessedAt")
        void aClearedAssessmentCarriesThreePayloadProperties() {
            List<String> required = requiredNames(CLEARED_SCHEMA);
            List<String> payload = required.stream()
                    .filter(name -> !ENVELOPE_PROPERTIES.contains(name))
                    .toList();

            assertEquals(List.of("transactionId", "accountId", "assessedAt"), payload,
                    "the cleared payload is three properties");
            assertEquals(ENVELOPE_PROPERTIES.size() + payload.size(), required.size(),
                    "the required names are the envelope names and the three payload names");
            assertTrue(payload.size() < ENVELOPE_PROPERTIES.size(),
                    "a cleared assessment carries fewer payload names than envelope names");
        }

        @Test
        @DisplayName("six plausible additions are absent from the cleared document and its bytes")
        void sixPlausibleAdditionsAreAbsent() {
            List<String> declared = propertyNames(schemaDocument(CLEARED_SCHEMA)
                    .path("properties"));
            String written = wireFormOf(FRAUD_TOPIC, aClearedAssessment());
            List<String> writtenNames = propertyNames(MAPPER.readTree(written));
            List<String> absent = List.of("riskScore", "triggeredRules", "clearedRules",
                    "evaluatedRules", "amount", "newBalance", "balance", "currency",
                    "maskedCardNumber", "cardNumber", "pan", "reason", "reasonCode", "note",
                    "notes", "comment");

            assertAll(absent.stream().map(name -> () -> {
                assertFalse(declared.contains(name),
                        "the cleared document declares no " + name + ": " + declared);
                assertFalse(writtenNames.contains(name),
                        "the written assessment carries no " + name + ": " + writtenNames);
            }));
        }

        @Test
        @DisplayName("neither fraud document declares a card number in any form")
        void neitherFraudDocumentDeclaresACardNumber() {
            assertAll(List.of(FLAGGED_SCHEMA, CLEARED_SCHEMA).stream().map(resource -> () -> {
                List<String> declared = propertyNames(schemaDocument(resource).path("properties"));

                assertAll(declared.stream().map(name -> () -> {
                    String folded = name.toLowerCase(Locale.ROOT);

                    assertFalse(folded.contains("cardnumber"), resource + " declares " + name);
                    assertFalse(folded.contains("maskedcard"), resource + " declares " + name);
                    assertFalse(folded.equals("pan"), resource + " declares " + name);
                }));
            }));
        }

        @Test
        @DisplayName("the risk score is an integer from 0 through 100")
        void theRiskScoreIsAnIntegerFromZeroThroughOneHundred() {
            JsonNode score = schemaDocument(FLAGGED_SCHEMA).path("properties").path("riskScore");

            assertEquals("integer", score.path("type").stringValue(), "the declared type");
            assertEquals(FraudFlagged.MINIMUM_RISK_SCORE, score.path("minimum").intValue(),
                    "the lowest score");
            assertEquals(FraudFlagged.MAXIMUM_RISK_SCORE, score.path("maximum").intValue(),
                    "the highest score");
            assertFalse(score.has("pattern"), "a score carries no pattern");
            assertFalse(score.has("multipleOf"), "a score carries no scale");
        }

        @Test
        @DisplayName("scores 0, 82 and 100 pass and a quoted score, -1 and 101 fail")
        void theSixRiskScoreCasesBehaveAsDeclared() {
            assertAll(
                    () -> assertTrue(scoreViolations("82").isEmpty(), "82 passes"),
                    () -> assertTrue(scoreViolations("0").isEmpty(), "0 passes"),
                    () -> assertTrue(scoreViolations("100").isEmpty(), "100 passes"),
                    () -> assertFalse(scoreViolations("\"82\"").isEmpty(), "a quoted score fails"),
                    () -> assertFalse(scoreViolations("-1").isEmpty(), "-1 fails"),
                    () -> assertFalse(scoreViolations("101").isEmpty(), "101 fails"));
        }

        @Test
        @DisplayName("the rule list caps nothing, needs one entry and repeats none")
        void theRuleListCapsNothingAndNeedsOneEntry() {
            JsonNode rules = schemaDocument(FLAGGED_SCHEMA).path("properties")
                    .path("triggeredRules");
            List<String> enumerated = new ArrayList<>();
            rules.path("items").path("enum").forEach(value -> enumerated.add(value.stringValue()));

            assertEquals("array", rules.path("type").stringValue(), "the declared type");
            assertFalse(rules.has("maxItems"), "the rule list carries no ceiling");
            assertEquals(1, rules.path("minItems").intValue(), "one entry is the floor");
            assertTrue(rules.path("uniqueItems").booleanValue(), "no entry repeats");
            assertEquals(List.of(FraudFlagged.VELOCITY_RULE, FraudFlagged.AMOUNT_ANOMALY_RULE,
                    FraudFlagged.MERCHANT_CATEGORY_RULE), enumerated, "the three identifiers");
            assertEquals(FraudFlagged.RULE_IDENTIFIERS.size(), enumerated.size(),
                    "the record and the document name the same count");
        }

        @Test
        @DisplayName("one rule passes, and an empty list, a repeat and an unknown name fail")
        void theFourRuleListCasesBehaveAsDeclared() {
            assertAll(
                    () -> assertTrue(ruleViolations("[\"VELOCITY\"]").isEmpty(),
                            "one rule passes"),
                    () -> assertTrue(ruleViolations("[\"VELOCITY\", \"AMOUNT_ANOMALY\", "
                            + "\"MERCHANT_CATEGORY\"]").isEmpty(), "all three pass"),
                    () -> assertFalse(ruleViolations("[]").isEmpty(), "an empty list fails"),
                    () -> assertFalse(ruleViolations("[\"VELOCITY\", \"VELOCITY\"]").isEmpty(),
                            "a repeated entry fails"),
                    () -> assertFalse(ruleViolations("[\"NO_SUCH_RULE\"]").isEmpty(),
                            "a name outside the three fails"));
        }

        @Test
        @DisplayName("the score and the rule list reach the record as the document carried them")
        void theScoreAndTheRuleListReachTheRecord() {
            String threeRules = withProperty(FLAGGED_DOCUMENT, "\"triggeredRules\": [\"VELOCITY\"]",
                    "\"triggeredRules\": [\"VELOCITY\", \"AMOUNT_ANOMALY\", "
                            + "\"MERCHANT_CATEGORY\"]");

            FraudFlagged arrived = readFlaggedAssessment(threeRules);

            assertEquals(RISK_SCORE, arrived.riskScore(), "the score the document carried");
            assertEquals(List.of(FraudFlagged.VELOCITY_RULE, FraudFlagged.AMOUNT_ANOMALY_RULE,
                    FraudFlagged.MERCHANT_CATEGORY_RULE), arrived.triggeredRules(),
                    "the three identifiers arrived");
        }

        /**
         * Measures a flagged document carrying one score against the flagged document.
         *
         * @param score the score text to put in the document
         * @return one entry per failure
         */
        private List<String> scoreViolations(String score) {
            return violations(FLAGGED_SCHEMA, withProperty(FLAGGED_DOCUMENT, "\"riskScore\": 82",
                    "\"riskScore\": " + score));
        }

        /**
         * Measures a flagged document carrying one rule list against the flagged document.
         *
         * @param rules the array text to put in the document
         * @return one entry per failure
         */
        private List<String> ruleViolations(String rules) {
            return violations(FLAGGED_SCHEMA, withProperty(FLAGGED_DOCUMENT,
                    "\"triggeredRules\": [\"VELOCITY\"]", "\"triggeredRules\": " + rules));
        }
    }

    /**
     * Asserts the three kinds of timestamp this service handles, and asserts that a fourth kind is
     * absent.
     *
     * <p>Two properties are ISO 8601 instants, one property is twenty-six characters of
     * fixed-width text, and no property carries a posting time.
     */
    @Nested
    @DisplayName("group C: three kinds of timestamp")
    class TimestampKinds {

        @Test
        @DisplayName("occurredAt and assessedAt are the only two date-time properties")
        void occurredAtAndAssessedAtAreTheOnlyDateTimeProperties() {
            assertAll(List.of(FLAGGED_SCHEMA, CLEARED_SCHEMA).stream().map(resource -> () -> {
                JsonNode properties = schemaDocument(resource).path("properties");
                List<String> dateTimes = propertyNames(properties).stream()
                        .filter(name -> "date-time"
                                .equals(properties.path(name).path("format").stringValue("")))
                        .sorted()
                        .toList();

                assertEquals(List.of("assessedAt", "occurredAt"), dateTimes,
                        resource + " marks two properties date-time");
            }));
        }

        @Test
        @DisplayName("assessedAt survives a write and a read through Instant")
        void assessedAtSurvivesAWriteAndARead() {
            FraudFlagged flagged = aFlaggedAssessment();
            FraudCleared cleared = aClearedAssessment();

            FraudFlagged arrivedFlagged =
                    readFlaggedAssessment(wireFormOf(FRAUD_TOPIC, flagged));
            FraudCleared arrivedCleared =
                    readClearedAssessment(wireFormOf(FRAUD_TOPIC, cleared));

            assertEquals(ASSESSED_AT, arrivedFlagged.assessedAt(), "the flagged assessment time");
            assertEquals(ASSESSED_AT, arrivedCleared.assessedAt(), "the cleared assessment time");
            assertEquals(flagged.occurredAt(), arrivedFlagged.occurredAt(), "the publish time");
            assertEquals(cleared.occurredAt(), arrivedCleared.occurredAt(), "the publish time");
        }

        @Test
        @DisplayName("the two instants travel as text and never as a numeric epoch")
        void theTwoInstantsTravelAsText() {
            JsonNode written = MAPPER.readTree(wireFormOf(FRAUD_TOPIC, aFlaggedAssessment()));

            assertTrue(written.path("occurredAt").isString(), "occurredAt is a JSON string");
            assertTrue(written.path("assessedAt").isString(), "assessedAt is a JSON string");
            assertEquals(ASSESSED_AT.toString(), written.path("assessedAt").stringValue(),
                    "the text is the ISO 8601 form of the instant");
        }

        @Test
        @DisplayName("authorizedAt holds twenty-six characters and declares no format")
        void authorizedAtHoldsTwentySixCharactersAndDeclaresNoFormat() {
            JsonNode authorizedAt = schemaDocument(AUTHORIZED_SCHEMA).path("properties")
                    .path("authorizedAt");

            assertEquals("string", authorizedAt.path("type").stringValue(), "the declared type");
            assertEquals(TransactionAuthorized.AUTHORIZED_AT_LENGTH,
                    authorizedAt.path("minLength").intValue(), "the floor on the length");
            assertEquals(TransactionAuthorized.AUTHORIZED_AT_LENGTH,
                    authorizedAt.path("maxLength").intValue(), "the ceiling on the length");
            assertEquals(TransactionAuthorized.AUTHORIZED_AT_PATTERN,
                    authorizedAt.path("pattern").stringValue(), "the declared pattern");
            assertFalse(authorizedAt.has("format"),
                    "authorizedAt is not an ISO 8601 timestamp and carries no format keyword");
        }

        @Test
        @DisplayName("authorizedAt reaches the record as text, character for character")
        void authorizedAtReachesTheRecordAsText() {
            String carried = readAuthorization(AUTHORIZED_DOCUMENT).authorizedAt();
            String written = wireFormOf(AUTHORIZED_TOPIC, anAuthorization());

            assertAll(
                    () -> assertEquals(AUTHORIZED_AT, carried, "the value the document carried"),
                    () -> assertEquals(TransactionAuthorized.AUTHORIZED_AT_LENGTH, carried.length(),
                            "twenty-six characters arrived"),
                    () -> assertEquals(' ', carried.charAt(10),
                            "a space separates the date from the time"),
                    () -> assertTrue(Pattern.compile(TransactionAuthorized.AUTHORIZED_AT_PATTERN)
                            .matcher(carried).matches(), "the value keeps its fixed-width layout"),
                    () -> assertEquals(AUTHORIZED_AT, MAPPER.readTree(written).path("authorizedAt")
                            .stringValue(), "a write leaves the value alone"),
                    () -> assertEquals(AUTHORIZED_AT, readAuthorization(written).authorizedAt(),
                            "a write and a read leave the value alone"));
        }

        @Test
        @DisplayName("a T separator and a three-digit fraction are each refused")
        void aTseparatorAndAThreeDigitFractionAreRefused() {
            String separator = withProperty(AUTHORIZED_DOCUMENT,
                    "\"authorizedAt\": \"" + AUTHORIZED_AT + "\"",
                    "\"authorizedAt\": \"2022-06-10T19:27:53.000000\"");
            String shortFraction = withProperty(AUTHORIZED_DOCUMENT,
                    "\"authorizedAt\": \"" + AUTHORIZED_AT + "\"",
                    "\"authorizedAt\": \"2022-06-10 19:27:53.000\"");

            assertTrue(violations(AUTHORIZED_SCHEMA, separator)
                    .contains("/authorizedAt (pattern)"), "a T separator breaks the pattern");
            assertFalse(violations(AUTHORIZED_SCHEMA, shortFraction).isEmpty(),
                    "three fractional digits break the length and the pattern");
            assertAll(List.of(separator, shortFraction).stream().map(document -> () ->
                    assertThrows(SerializationException.class,
                            () -> DESERIALIZER.deserialize(AUTHORIZED_TOPIC,
                                    document.getBytes(StandardCharsets.UTF_8)),
                            "no authorization is built")));
        }

        @Test
        @DisplayName("no posting timestamp exists on the document or in the bytes")
        void noPostingTimestampExists() {
            List<String> declared = propertyNames(schemaDocument(AUTHORIZED_SCHEMA)
                    .path("properties"));
            List<String> written =
                    propertyNames(MAPPER.readTree(wireFormOf(AUTHORIZED_TOPIC, anAuthorization())));

            assertAll(declared.stream().map(name -> () -> {
                String folded = name.toLowerCase(Locale.ROOT);

                assertFalse(folded.contains("processed"), AUTHORIZED_SCHEMA + " declares " + name);
                assertFalse(folded.contains("procts"), AUTHORIZED_SCHEMA + " declares " + name);
                assertFalse(folded.equals("postedat"), AUTHORIZED_SCHEMA + " declares " + name);
            }));
            assertAll(written.stream().map(name -> () -> assertFalse(
                    name.toLowerCase(Locale.ROOT).contains("proc"),
                    "the written authorization carries " + name)));
            assertFalse(written.contains("postedAt"),
                    "the written authorization carries no posting time: " + written);
        }
    }
}
