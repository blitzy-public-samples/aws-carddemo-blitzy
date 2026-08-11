package com.carddemo.card.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.events.EventEnvelope;
import com.carddemo.events.serde.EventJsonValidator;
import com.carddemo.events.serde.EventSchemas;
import com.carddemo.events.serde.EventWireBounds;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Holds {@link CardUpdated} to the contract the Agent Action Plan specifies for it.
 *
 * <p>Three properties matter most, and each has its own test. The record carries four payload
 * components and no cardholder name. The card verification value appears in no component and no serialized
 * form. The wire form is flat, so the five envelope properties sit beside the payload properties at
 * one level.
 *
 * <p>The card service publishes this event and consumes nothing, so these tests construct the record
 * directly and serialize it with Jackson, which is how {@code outbox/OutboxWriter} will use it.
 *
 * <p>This service once published its mutation event with a plain string serializer while the shared
 * schema-validating serializer governed only the five core events. The payload of a card change was
 * therefore never checked against the contract that describes it, so neither the closed property set
 * that keeps an undeclared field out of a known event type, nor the size ceiling, nor the
 * governed-type list applied to it. The event now travels through the shared serializer and is
 * checked again by the shared validator on the way to the broker, and the four tests at the end of
 * this suite are what keep it that way.
 */
class CardUpdatedTest {

    /** An account identifier that opens with a zero, as all 50 fixture accounts do. */
    private static final String ACCOUNT_ID = "00000000050";

    /** The full card number of record one of {@code app/data/ASCII/carddata.txt}. */
    private static final String FULL_CARD_NUMBER = "0500024453765740";

    /** The masked form of {@link #FULL_CARD_NUMBER}. */
    private static final String MASKED_CARD_NUMBER = "************5740";

    /** The embossed cardholder name, from {@code CARD-EMBOSSED-NAME PIC X(50)}. */
    private static final String EMBOSSED_NAME = "Aniya Von";

    /** The card verification value of fixture record one, which no component of this event carries. */
    private static final String CARD_VERIFICATION_VALUE = "747";

    /** Serializes a record the way the outbox writer will. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("the record declares the flat envelope and exactly four payload components")
    void theRecordDeclaresFourPayloadComponents() {
        List<String> components = Arrays.stream(CardUpdated.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(List.of("eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId",
                "maskedCardNumber", "accountId", "expirationDate", "activeStatus"),
                components,
                "the five envelope components written flat, then the four payload components, in "
                        + "this order and no other");
        assertFalse(components.contains("changeType"),
                "no change-kind discriminator: one mutation produces one event");
    }

    @Test
    @DisplayName("no component names the card verification value")
    void noComponentNamesTheCardVerificationValue() {
        List<String> forbidden =
                List.of("cvv", "cardverificationvalue", "securitycode", "cvc", "cvv2", "cid");

        for (RecordComponent component : CardUpdated.class.getRecordComponents()) {
            String name = component.getName().toLowerCase();
            assertFalse(forbidden.contains(name),
                    "the card verification value is one of the six fields "
                            + "app/cbl/COCRDUPC.cbl:L1503-L1508 compares, and it reaches no event");
        }

        var tree = MAPPER.readTree(MAPPER.writeValueAsString(event()));

        tree.propertyNames().forEach(property -> assertFalse(
                forbidden.contains(property.toLowerCase()),
                "the serialized event carries no property named " + property));

        // The verification value of fixture record one. Checking property values rather than the
        // raw text keeps the assertion exact: a random event identifier or a millisecond field can
        // hold the same three digits by chance.
        tree.properties().forEach(property -> assertFalse("747".equals(property.getValue().asString("")),
                "property " + property.getKey() + " carries the verification value of fixture "
                        + "record one"));
    }

    @Test
    @DisplayName("the wire form is flat, with the envelope properties beside the payload properties")
    void theWireFormIsFlat() {
        var tree = MAPPER.readTree(MAPPER.writeValueAsString(event()));

        assertFalse(tree.has("envelope"),
                "a nested envelope object would break the flat convention the five authored "
                        + "schemas follow");
        List.of("eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId")
                .forEach(property -> assertTrue(tree.has(property),
                        "envelope property " + property + " sits at the top level"));
        List.of("maskedCardNumber", "accountId", "expirationDate", "activeStatus")
                .forEach(property -> assertTrue(tree.has(property),
                        "payload property " + property + " sits at the top level"));

        assertEquals(9, tree.size(), "five envelope properties plus four payload properties");
        assertEquals("CardUpdated", tree.get("eventType").asString());
        assertEquals(CardUpdated.SCHEMA_VERSION, tree.get("schemaVersion").asInt());
    }

    @Test
    @DisplayName("the factory masks the full card number and keeps the account identifier")
    void theFactoryMasksTheFullCardNumber() {
        CardUpdated event = CardUpdated.ofUnmaskedCardNumber(FULL_CARD_NUMBER, ACCOUNT_ID,
                "2023-03-09", "Y");

        assertEquals(MASKED_CARD_NUMBER, event.maskedCardNumber(),
                "twelve mask characters then the last four digits");
        assertEquals(16, event.maskedCardNumber().length(), "the masked form keeps the width");
        assertFalse(event.maskedCardNumber().contains("0500024453765"),
                "no part of the leading digits survives masking");
        assertEquals(ACCOUNT_ID, event.accountId(), "the account identifier keeps its leading zero");
        assertEquals(ACCOUNT_ID, event.envelope().aggregateId(),
                "the envelope carries the account identifier as the message key");
        assertEquals(CardUpdated.EVENT_TYPE, event.envelope().eventType());
    }

    @Test
    @DisplayName("the canonical constructor refuses an unmasked card number")
    void theCanonicalConstructorRefusesAnUnmaskedCardNumber() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CardUpdated.from(envelope(), FULL_CARD_NUMBER, ACCOUNT_ID,
                        "2023-03-09", "Y"));

        assertTrue(failure.getMessage().contains("maskedCardNumber"),
                "the message names the component that failed");
        assertFalse(failure.getMessage().contains(FULL_CARD_NUMBER),
                "the message reports the length and never the card number");
    }

    @ParameterizedTest
    @ValueSource(strings = {"5000244537657401", "***********5740", "*************740",
            "************574a", "0500024453765740"})
    @DisplayName("only the masked form is accepted")
    void onlyTheMaskedFormIsAccepted(String candidate) {
        assertThrows(IllegalArgumentException.class,
                () -> CardUpdated.from(envelope(), candidate, ACCOUNT_ID, "2023-03-09", "Y"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"50", "0000000005", "000000000500", "0000000005a"})
    @DisplayName("an account identifier of any shape other than eleven digits is refused")
    void theAccountIdentifierMustHoldElevenDigits(String candidate) {
        assertThrows(IllegalArgumentException.class,
                () -> CardUpdated.from(envelope(candidate), MASKED_CARD_NUMBER, candidate,
                        "2023-03-09", "Y"));
    }

    @Test
    @DisplayName("the remaining two components are held to their source widths")
    void theRemainingComponentsAreHeldToTheirSourceWidths() {
        assertThrows(IllegalArgumentException.class,
                () -> CardUpdated.from(envelope(), MASKED_CARD_NUMBER, ACCOUNT_ID,
                        "2023-03-9", "Y"),
                "CARD-EXPIRAION-DATE PIC X(10) is exactly ten characters");
        assertThrows(IllegalArgumentException.class,
                () -> CardUpdated.from(envelope(), MASKED_CARD_NUMBER, ACCOUNT_ID,
                        "2023-03-09", "A"),
                "app/cbl/COCRDUPC.cbl:L91 fixes the status domain to Y and N");
    }

    @Test
    @DisplayName("an envelope naming another event type is refused")
    void anEnvelopeNamingAnotherEventTypeIsRefused() {
        EventEnvelope wrongType = new EventEnvelope(UUID.randomUUID(), "TransactionAuthorized",
                EventEnvelope.SCHEMA_VERSION, java.time.Instant.now(), ACCOUNT_ID);

        assertThrows(IllegalArgumentException.class,
                () -> CardUpdated.from(wrongType, MASKED_CARD_NUMBER, ACCOUNT_ID,
                        "2023-03-09", "Y"));
    }

    @Test
    @DisplayName("a serialized event deserializes to an equal record")
    void aSerializedEventDeserializesToAnEqualRecord() {
        CardUpdated event = event();

        CardUpdated returned =
                MAPPER.readValue(MAPPER.writeValueAsString(event), CardUpdated.class);

        assertEquals(event, returned, "every component survives the round trip unchanged");
    }

    @Test
    @DisplayName("the event type is CardUpdated and the platform governs a schema for it")
    void theEventTypeIsGovernedByTheSharedTable() {
        assertEquals("CardUpdated", CardUpdated.EVENT_TYPE,
                "the event type changed, so the schema its name selects no longer describes it");
        assertEquals("schemas/card-updated-v2.json",
                EventSchemas.SCHEMA_RESOURCES.get(CardUpdated.EVENT_TYPE),
                "the shared table no longer governs this event, so it would leave this service "
                        + "unchecked");
    }

    @Test
    @DisplayName("the event serializes through the shared gate and passes it again on publish")
    void theEventPassesTheSharedGateTwice() {
        String json = event().toValidatedJson();

        // The publish side validates the stored text a second time, exactly as the relay does.
        EventJsonValidator.shared().validate(json);

        assertTrue(json.startsWith("{") && json.endsWith("}"),
                "the wire form is not one JSON object: " + json);
        assertTrue(json.contains("\"eventType\":\"CardUpdated\""),
                "the wire form does not name its event type: " + json);
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= EventWireBounds.MAX_EVENT_BYTES,
                "the event exceeds the platform ceiling, so it would not fit the outbox row");
    }

    @Test
    @DisplayName("no full card number, card verification value or cardholder name reaches the wire")
    void noUnneededCardholderDataReachesTheWire() {
        String json = event().toValidatedJson();

        assertFalse(json.contains(FULL_CARD_NUMBER),
                "the full card number reached the wire: " + json);
        assertTrue(json.contains(MASKED_CARD_NUMBER),
                "the masked card number is absent, so a consumer has nothing to display: " + json);
        assertFalse(json.contains(CARD_VERIFICATION_VALUE + "\""),
                "a card verification value reached the wire: " + json);
        assertFalse(json.toLowerCase().contains("verification"),
                "the wire form names a card verification value field: " + json);
        assertFalse(json.toLowerCase().contains("cvv"),
                "the wire form names a card verification value field: " + json);
        assertFalse(json.contains(EMBOSSED_NAME),
                "the wire form carries an embossed cardholder name: " + json);
        assertFalse(json.contains("\"embossedName\""),
                "the wire form declares an embossed-name property: " + json);
    }

    /**
     * Holds this event to having no free-text component, which is why no screen can be defeated here.
     *
     * <p>The publish gate screens every value on the way into an outbox row, and the shapes it looks
     * for — a long digit run, a social security number, a labelled or bare verification code — can
     * only arrive through a value a person wrote. This event has no such value: the masked card
     * number, the account identifier, the expiry text and the active-status flag are each bound to a
     * pattern by the record itself, from {@code app/cpy/CVACT02Y.cpy:L5-L11} and
     * {@code app/cbl/COCRDUPC.cbl:L190-L202}.
     *
     * <p>Recording it as a test rather than as a comment is what keeps it true. A component added
     * later that accepts prose fails this test, and whoever adds it is told where the screens then
     * have to be exercised.
     */
    @Test
    @DisplayName("no component of this event is free text, so the screens have nothing to catch")
    void noComponentOfThisEventIsFreeText() {
        assertThrows(IllegalArgumentException.class,
                () -> CardUpdated.ofUnmaskedCardNumber(FULL_CARD_NUMBER, ACCOUNT_ID,
                        "Card 4111111111111111", "Y"),
                "the expiry component accepted prose, so a screened shape can now reach a payload");
        assertThrows(IllegalArgumentException.class,
                () -> CardUpdated.ofUnmaskedCardNumber(FULL_CARD_NUMBER, ACCOUNT_ID, "2023-03-09",
                        "cvv 123"),
                "the status component accepted prose, so a screened shape can now reach a payload");

        List<String> componentNames = Arrays.stream(CardUpdated.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(List.of("eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId",
                        "maskedCardNumber", "accountId", "expirationDate", "activeStatus"),
                componentNames,
                "a component was added or renamed, so the free-text claim above needs rechecking");
    }

    @Test
    @DisplayName("the diagnostic rendering carries no card value")
    void theRenderingCarriesNoCardValue() {
        String rendering = event().toString();

        assertFalse(rendering.contains(FULL_CARD_NUMBER),
                "the rendering carries the full card number: " + rendering);
        assertFalse(rendering.contains(EMBOSSED_NAME),
                "the rendering carries the cardholder name: " + rendering);
    }

    /**
     * Returns one valid event carrying fixture record one.
     *
     * @return the event
     */
    private static CardUpdated event() {
        return CardUpdated.ofUnmaskedCardNumber(
                FULL_CARD_NUMBER, ACCOUNT_ID, "2023-03-09", "Y");
    }

    /**
     * Returns one envelope naming this event type and the default account identifier.
     *
     * @return the envelope
     */
    private static EventEnvelope envelope() {
        return envelope(ACCOUNT_ID);
    }

    /**
     * Returns one envelope naming this event type and the given aggregate identifier.
     *
     * @param aggregateId the account identifier the envelope carries
     * @return the envelope
     */
    private static EventEnvelope envelope(String aggregateId) {
        return EventEnvelope.of(CardUpdated.EVENT_TYPE, aggregateId, CardUpdated.SCHEMA_VERSION);
    }
}
