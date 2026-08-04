package com.carddemo.card.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.carddemo.events.serde.JsonSchemaValidatingDeserializer;
import com.carddemo.events.serde.JsonSchemaValidatingSerializer;

import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs {@link CardUpdated} through the publish path and the consume path this service uses.
 *
 * <p>ADDITIVE. No COBOL program publishes an event, so nothing here translates a source construct.
 * The card fields the event carries come from {@code app/cpy/CVACT02Y.cpy}:
 * {@code CARD-NUM PIC X(16)} at {@code :L5}, {@code CARD-EMBOSSED-NAME PIC X(50)} at {@code :L8},
 * {@code CARD-EXPIRAION-DATE PIC X(10)} at {@code :L9} and
 * {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code :L10}. The account identifier is
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} and is the Kafka message key.
 *
 * <p>{@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} is stored by this service and
 * reaches no event. The record declares no component for it, and this class asserts that neither the
 * document nor the wire form names it.
 *
 * <p>The full card number never travels. {@code app/cbl/CBTRN02C.cbl:L382-L383} keys the
 * cross-reference read on all sixteen characters, and masking happens at the serialization boundary
 * afterwards, so the wire form carries twelve mask characters and the last four digits.
 *
 * <p>{@code schemas/card-updated-v1.json} ships in the event-contracts module and is the
 * contract this service publishes against. The serializer validates the bytes before returning them,
 * so a call that returns bytes is a call whose payload satisfied that document.
 *
 * <p>{@code card-platform/docs/decision-log.md} (planned) holds the rationale for these choices.
 *
 * <p>Versions in use: Java 25, Apache Maven 3.9.16, junit-jupiter 6.0.3,
 * spring-boot-starter-test 4.1.0, json-schema-validator 3.0.6 and jackson-databind 3.1.4.
 */
@DisplayName("CardUpdated on the publish and consume paths")
class CardUpdatedPublishPathTest {

    /** The contract this service publishes against, on the classpath from event-contracts. */
    private static final String DOCUMENT = "schemas/card-updated-v1.json";

    /** The topic the card service publishes a card mutation to. */
    private static final String TOPIC = "card.updated";

    /** The card number of the first row of {@code app/data/ASCII/carddata.txt}, sixteen digits. */
    private static final String UNMASKED_CARD_NUMBER = "4859452612877065";

    /** {@link #UNMASKED_CARD_NUMBER} with twelve mask characters ahead of its last four digits. */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /** An eleven-digit account identifier, from {@code app/data/ASCII/cardxref.txt}. */
    private static final String ACCOUNT_ID = "00000000001";

    /** A cardholder name inside the fifty characters of {@code CARD-EMBOSSED-NAME PIC X(50)}. */
    private static final String EMBOSSED_NAME = "PAULA A CHRISTOFFERSEN";

    /** A ten-character expiry text, from {@code CARD-EXPIRAION-DATE PIC X(10)}. */
    private static final String EXPIRATION_DATE = "2025-12-28";

    /** The three digits of {@code CARD-CVV-CD PIC 9(03)}, which reach no event. */
    private static final String CARD_VERIFICATION_VALUE = "123";

    /** Property names no event may carry, each one the guard refuses. */
    private static final List<String> FORBIDDEN_PROPERTIES =
            List.of("cvv", "cardVerificationValue", "cardNumber", "pan", "password", "ssn");

    /** Reads and writes JSON trees. Jackson 3 only. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The publish path, which validates against the document before it returns bytes. */
    private static final JsonSchemaValidatingSerializer<CardUpdated> SERIALIZER =
            new JsonSchemaValidatingSerializer<>();

    /** The consume path bound to the record. */
    private static final JsonSchemaValidatingDeserializer<CardUpdated> DESERIALIZER =
            new JsonSchemaValidatingDeserializer<>(CardUpdated.class);

    /** The consume path bound to a tree, for a payload this test built by hand. */
    private static final JsonSchemaValidatingDeserializer<JsonNode> TREE_DESERIALIZER =
            new JsonSchemaValidatingDeserializer<>(JsonNode.class);

    /**
     * Asserts the event publishes exactly the properties the document declares, and that the account
     * identifier reaches both the payload and the message key.
     *
     * <p>The property set stays open, so a property the document never declared would validate and
     * travel unseen. This comparison is what stops that.
     */
    @Test
    @DisplayName("the event publishes exactly the properties the document declares")
    void theEventPublishesTheDeclaredPropertySet() {
        JsonNode wire = wireForm();

        assertEquals(declaredProperties(), new LinkedHashSet<>(wire.propertyNames()),
                "CardUpdated and " + DOCUMENT + " disagree on the property set of the wire "
                        + "form");
        assertEquals(ACCOUNT_ID, wire.get("aggregateId").asString(),
                "the Kafka message key stopped carrying the account identifier");
        assertEquals(ACCOUNT_ID, wire.get("accountId").asString(),
                "the payload account identifier stopped agreeing with the message key");
        assertEquals(CardUpdated.EVENT_TYPE,
                wire.get("eventType").asString(),
                "the wire form stopped carrying the one change kind the card service publishes");
    }

    /**
     * Asserts the record survives a serialize and deserialize round trip.
     */
    @Test
    @DisplayName("the record survives a round trip through both serde directions")
    void theRecordSurvivesARoundTrip() {
        CardUpdated event = event();

        CardUpdated read = DESERIALIZER.deserialize(TOPIC, SERIALIZER.serialize(TOPIC, event));

        assertEquals(event, read,
                "CardUpdated lost a value in the round trip through the two serde classes");
        assertEquals(MASKED_CARD_NUMBER, read.maskedCardNumber(),
                "the masked card number came back holding another value");
    }

    /**
     * Asserts the wire form carries the masked card number and never the sixteen digits the decision
     * runs on.
     */
    @Test
    @DisplayName("the wire form carries the masked card number and not the sixteen digits")
    void theWireFormCarriesTheMaskedCardNumberOnly() {
        String rendered = MAPPER.writeValueAsString(wireForm());

        assertTrue(rendered.contains(MASKED_CARD_NUMBER),
                "the wire form stopped carrying the masked card number");
        assertFalse(rendered.contains(UNMASKED_CARD_NUMBER),
                "the wire form carries the sixteen digits of the card number, and masking happens "
                        + "at the serialization boundary");
        assertFalse(rendered.contains("\"" + CARD_VERIFICATION_VALUE + "\""),
                "the wire form carries a value the card verification field could hold");
    }

    /**
     * Asserts the consume path refuses a payload with any one required property removed, a change
     * kind the document does not enumerate, and a full card number in the masked property.
     */
    @Test
    @DisplayName("the consume path refuses a payload that breaks the document")
    void theConsumePathRefusesAPayloadThatBreaksTheDocument() {
        ObjectNode valid = wireForm();

        for (String property : valid.propertyNames()) {
            ObjectNode reduced = valid.deepCopy();
            reduced.remove(property);
            byte[] bytes = MAPPER.writeValueAsString(reduced).getBytes(StandardCharsets.UTF_8);

            assertThrows(SerializationException.class,
                    () -> TREE_DESERIALIZER.deserialize(TOPIC, bytes),
                    DOCUMENT + " accepted a payload with the required property " + property
                            + " removed");
        }

        ObjectNode undeclared = valid.deepCopy();
        undeclared.put("changeType", "CARD_CLOSED");
        byte[] undeclaredBytes =
                MAPPER.writeValueAsString(undeclared).getBytes(StandardCharsets.UTF_8);
        assertThrows(SerializationException.class,
                () -> TREE_DESERIALIZER.deserialize(TOPIC, undeclaredBytes),
                DOCUMENT + " accepted a property it does not declare");

        ObjectNode unmasked = valid.deepCopy();
        unmasked.put("maskedCardNumber", UNMASKED_CARD_NUMBER);
        byte[] unmaskedBytes = MAPPER.writeValueAsString(unmasked).getBytes(StandardCharsets.UTF_8);
        assertThrows(SerializationException.class,
                () -> TREE_DESERIALIZER.deserialize(TOPIC, unmaskedBytes),
                DOCUMENT + " accepted a full sixteen-digit card number in the masked property");
    }

    /**
     * Asserts the consume path refuses a payload carrying a property no event may carry.
     *
     * <p>The document accepts an undeclared property, so the guard rather than the document is what
     * keeps {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} off the wire.
     */
    @Test
    @DisplayName("the consume path refuses a property no event may carry")
    void theConsumePathRefusesAPropertyNoEventMayCarry() {
        ObjectNode valid = wireForm();

        for (String forbidden : FORBIDDEN_PROPERTIES) {
            ObjectNode carrying = valid.deepCopy();
            carrying.put(forbidden, CARD_VERIFICATION_VALUE);
            byte[] bytes = MAPPER.writeValueAsString(carrying).getBytes(StandardCharsets.UTF_8);

            SerializationException failure = assertThrows(SerializationException.class,
                    () -> TREE_DESERIALIZER.deserialize(TOPIC, bytes),
                    "the consume path accepted a payload carrying " + forbidden);
            assertTrue(failure.getMessage().contains(forbidden),
                    "the failure for " + forbidden + " stopped naming the property, and the "
                            + "message read: " + failure.getMessage());
        }
    }

    /**
     * Asserts the document declares one card property and that property is the masked one.
     */
    @Test
    @DisplayName("the document declares one card property and it is the masked one")
    void theDocumentDeclaresOneCardPropertyAndItIsTheMaskedOne() {
        Set<String> cardProperties = new LinkedHashSet<>();

        for (String property : declaredProperties()) {
            String folded = property.toLowerCase(Locale.ROOT);

            assertFalse(folded.contains("cvv"),
                    DOCUMENT + " declares " + property + ", and CARD-CVV-CD at "
                            + "app/cpy/CVACT02Y.cpy:L7 reaches no event");
            if (folded.contains("card")) {
                cardProperties.add(property);
            }
        }

        assertEquals(Set.of("maskedCardNumber"), cardProperties,
                DOCUMENT + " changed which card properties it declares, and only the masked card "
                        + "number may travel");
    }

    /**
     * One card mutation event carrying the card values above.
     *
     * @return an event that satisfies the document
     */
    private static CardUpdated event() {
        return CardUpdated.ofUnmaskedCardNumber(UNMASKED_CARD_NUMBER, ACCOUNT_ID,
                EMBOSSED_NAME, EXPIRATION_DATE, CardUpdated.ACTIVE_STATUS_ACTIVE);
    }

    /**
     * The wire form the event writes, read back as a tree.
     *
     * @return the serialized event as a JSON object
     */
    private static ObjectNode wireForm() {
        byte[] bytes = SERIALIZER.serialize(TOPIC, event());

        if (bytes == null) {
            return fail("CardUpdated serialized to no bytes");
        }
        JsonNode tree = MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
        if (!tree.isObject()) {
            return fail("CardUpdated serialized to something other than a JSON object");
        }
        return (ObjectNode) tree;
    }

    /**
     * The property names the document declares, in document order, apart from {@code extensions}.
     *
     * @return the keys of the {@code properties} block, without the extension point
     */
    private static Set<String> declaredProperties() {
        try (InputStream stream = CardUpdatedPublishPathTest.class.getClassLoader()
                .getResourceAsStream(DOCUMENT)) {
            if (stream == null) {
                return fail("classpath resource " + DOCUMENT + " is missing from the "
                        + "event-contracts module, so this service has no contract to publish "
                        + "against");
            }
            Set<String> declared =
                    new LinkedHashSet<>(MAPPER.readTree(stream).get("properties").propertyNames());
            // Every document of this platform declares one bounded extensions object, declared and
            // never required, so a consumer added later can read a property a producer added later
            // without the document having to change first. No producer writes it today, so it is
            // not part of the property set a wire form is compared against.
            declared.remove("extensions");
            return declared;
        } catch (IOException failure) {
            return fail("classpath resource " + DOCUMENT + " could not be read: "
                    + failure.getMessage());
        }
    }

    /**
     * Asserts the serializer refuses an event whose type no document covers.
     *
     * <p>The card service publishes one event type. A type with no document would otherwise reach a
     * topic unchecked.
     */
    @Test
    @DisplayName("the publish path refuses an event type no document covers")
    void thePublishPathRefusesAnEventTypeNoDocumentCovers() {
        ObjectNode renamed = wireForm();
        renamed.put("eventType", "CardReissued");
        byte[] bytes = MAPPER.writeValueAsString(renamed).getBytes(StandardCharsets.UTF_8);

        SerializationException failure = assertThrows(SerializationException.class,
                () -> TREE_DESERIALIZER.deserialize(TOPIC, bytes),
                "the consume path accepted an event type no document covers");

        assertNotNull(failure.getMessage(), "the failure carried no message");
        assertTrue(failure.getMessage().contains("CardReissued"),
                "the failure stopped naming the unknown event type, and the message read: "
                        + failure.getMessage());
    }
}
