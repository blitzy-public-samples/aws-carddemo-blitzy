package com.carddemo.account.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
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
 * Runs {@link AccountStateChanged} through the publish path and the consume path this service uses.
 *
 * <p>ADDITIVE. No COBOL program publishes an event, so nothing here translates a source construct.
 * The account fields the event carries come from {@code app/cpy/CVACT01Y.cpy}:
 * {@code ACCT-ACTIVE-STATUS PIC X(01)} at {@code :L6}, {@code ACCT-CURR-BAL PIC S9(10)V99} at
 * {@code :L7}, {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at {@code :L8},
 * {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} at {@code :L9},
 * {@code ACCT-EXPIRAION-DATE PIC X(10)} at {@code :L11}, and the two accumulators at
 * {@code :L12-L13}. The account identifier is {@code XREF-ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT03Y.cpy:L7} and is the Kafka message key.
 *
 * <p>{@code schemas/account-state-changed-v1.json} ships in the event-contracts module and is the
 * contract this service publishes against. The serializer validates the bytes before returning them,
 * so a call that returns bytes is a call whose payload satisfied that document.
 *
 * <p>Four properties are checked. The wire property set equals the set the document declares. The
 * record survives a serialize and deserialize round trip. A payload that breaks the document is
 * refused on the consume path. A property no event may carry is refused on both paths.
 *
 * <p>Money travels as a decimal string. Each amount holds two fractional digits and truncates toward
 * zero, since the rounding phrase appears in none of the twenty-eight programs under
 * {@code app/cbl}.
 *
 * <p>{@code card-platform/docs/decision-log.md} (planned) holds the rationale for these choices.
 *
 * <p>Versions in use: Java 25, Apache Maven 3.9.16, junit-jupiter 6.0.3,
 * spring-boot-starter-test 4.1.0, json-schema-validator 3.0.6 and jackson-databind 3.1.4.
 */
@DisplayName("AccountStateChanged on the publish and consume paths")
class AccountStateChangedPublishPathTest {

    /** The contract this service publishes against, on the classpath from event-contracts. */
    private static final String DOCUMENT = "schemas/account-state-changed-v1.json";

    /** The topic the account service publishes an account mutation to. */
    private static final String TOPIC = "account.state-changed";

    /** An eleven-digit account identifier, the first row of {@code app/data/ASCII/acctdata.txt}. */
    private static final String ACCOUNT_ID = "00000000001";

    /** The status byte all 50 records of {@code app/data/ASCII/acctdata.txt} hold. */
    private static final String ACTIVE_STATUS = "Y";

    /** A ten-character expiry text, from {@code ACCT-EXPIRAION-DATE PIC X(10)}. */
    private static final String EXPIRATION_DATE = "2025-12-28";

    /** A balance inside the ten integer digits of {@code ACCT-CURR-BAL PIC S9(10)V99}. */
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("843.00");

    /** A credit limit inside the ten integer digits of {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}. */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("9750.00");

    /** A cash credit limit inside {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99}. */
    private static final BigDecimal CASH_CREDIT_LIMIT = new BigDecimal("2500.00");

    /** The value both cycle accumulators hold in all 50 records of the account fixture. */
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    /** Property names no event may carry, each one the publish-side guard refuses. */
    private static final List<String> FORBIDDEN_PROPERTIES =
            List.of("cvv", "cardVerificationValue", "cardNumber", "pan", "password", "ssn");

    /** Reads and writes JSON trees. Jackson 3 only. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The publish path, which validates against the document before it returns bytes. */
    private static final JsonSchemaValidatingSerializer<AccountStateChanged> SERIALIZER =
            new JsonSchemaValidatingSerializer<>();

    /** The consume path bound to the record. */
    private static final JsonSchemaValidatingDeserializer<AccountStateChanged> DESERIALIZER =
            new JsonSchemaValidatingDeserializer<>(AccountStateChanged.class);

    /** The consume path bound to a tree, for a payload this test built by hand. */
    private static final JsonSchemaValidatingDeserializer<JsonNode> TREE_DESERIALIZER =
            new JsonSchemaValidatingDeserializer<>(JsonNode.class);

    /**
     * Asserts each change kind publishes, and that the wire property set equals the set the document
     * declares.
     *
     * <p>The document closes its property set, so a property it never declared fails validation.
     * This comparison catches the other direction: a declared property the record never writes.
     */
    @Test
    @DisplayName("every change kind publishes exactly the properties the document declares")
    void everyChangeKindPublishesTheDeclaredPropertySet() {
        Set<String> declared = declaredProperties();

        for (AccountStateChanged.ChangeKind changeType : AccountStateChanged.ChangeKind.values()) {
            byte[] bytes = SERIALIZER.serialize(TOPIC, eventOf(changeType));

            assertNotNull(bytes, changeType + " serialized to no bytes");
            JsonNode wire = MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
            Set<String> written = new LinkedHashSet<>(wire.propertyNames());

            assertEquals(declared, written,
                    "AccountStateChanged and " + DOCUMENT + " disagree on the property set of the "
                            + "wire form for " + changeType);
            assertEquals(changeType.name(), wire.get("changeKind").asString(),
                    "the wire form stopped carrying the change kind " + changeType);
            assertEquals(ACCOUNT_ID, wire.get("aggregateId").asString(),
                    "the Kafka message key stopped carrying the account identifier");
            assertEquals(ACCOUNT_ID, wire.get("accountId").asString(),
                    "the payload account identifier stopped agreeing with the message key");
        }
    }

    /**
     * Asserts the record survives a serialize and deserialize round trip, with every money value
     * back at two fractional digits.
     */
    @Test
    @DisplayName("the record survives a round trip through both serde directions")
    void theRecordSurvivesARoundTrip() {
        AccountStateChanged event = eventOf(AccountStateChanged.ChangeKind.ACCOUNT_UPDATED);

        AccountStateChanged read =
                DESERIALIZER.deserialize(TOPIC, SERIALIZER.serialize(TOPIC, event));

        assertEquals(event, read,
                "AccountStateChanged lost a value in the round trip through the two serde classes");
        assertEquals(2, read.creditLimit().scale(),
                "the credit limit came back at a scale other than two fractional digits");
        assertEquals(CREDIT_LIMIT, read.creditLimit(),
                "the credit limit came back holding another value");
    }

    /**
     * Asserts every money property travels as a JSON string, since a JSON number parses into a
     * binary floating-point value.
     */
    @Test
    @DisplayName("every money property travels as a decimal string")
    void everyMoneyPropertyTravelsAsADecimalString() {
        JsonNode wire = wireForm(AccountStateChanged.ChangeKind.ACCOUNT_UPDATED);

        for (String property : List.of("creditLimit", "currentCycleCredit",
                "currentCycleDebit")) {
            JsonNode value = wire.get(property);

            assertNotNull(value, "the wire form stopped carrying " + property);
            assertTrue(value.isString(),
                    property + " travels as a JSON number, and a number parses into a binary "
                            + "floating-point value");
            assertTrue(value.asString().matches("^-?\\d{1,10}\\.\\d{2}$"),
                    property + " stopped holding ten integer digits and two fractional digits, "
                            + "which ACCT-CURR-BAL PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L7 gives");
        }
    }

    /**
     * Asserts the consume path refuses a payload with any one required property removed, and refuses
     * a change kind the document does not enumerate.
     */
    @Test
    @DisplayName("the consume path refuses a payload that breaks the document")
    void theConsumePathRefusesAPayloadThatBreaksTheDocument() {
        ObjectNode valid = wireForm(AccountStateChanged.ChangeKind.ACCOUNT_UPDATED).deepCopy();

        for (String property : valid.propertyNames()) {
            ObjectNode reduced = valid.deepCopy();
            reduced.remove(property);
            byte[] bytes = MAPPER.writeValueAsString(reduced).getBytes(StandardCharsets.UTF_8);

            assertThrows(SerializationException.class,
                    () -> TREE_DESERIALIZER.deserialize(TOPIC, bytes),
                    DOCUMENT + " accepted a payload with the required property " + property
                            + " removed");
        }

        ObjectNode unknownChange = valid.deepCopy();
        unknownChange.put("changeType", "ACCOUNT_CLOSED");
        byte[] unknownBytes =
                MAPPER.writeValueAsString(unknownChange).getBytes(StandardCharsets.UTF_8);
        assertThrows(SerializationException.class,
                () -> TREE_DESERIALIZER.deserialize(TOPIC, unknownBytes),
                DOCUMENT + " accepted a change kind its enumeration does not declare");
    }

    /**
     * Asserts both serde directions refuse a payload carrying a property no event may carry.
     *
     * <p>{@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} and
     * {@code CUST-SSN PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L17} reach no event. The document
     * accepts an undeclared property, so the guard rather than the document is what stops them.
     */
    @Test
    @DisplayName("both serde directions refuse a property no event may carry")
    void bothSerdeDirectionsRefuseAPropertyNoEventMayCarry() {
        ObjectNode valid = wireForm(AccountStateChanged.ChangeKind.ACCOUNT_UPDATED).deepCopy();

        for (String forbidden : FORBIDDEN_PROPERTIES) {
            ObjectNode carrying = valid.deepCopy();
            carrying.put(forbidden, "0123456789012345");
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
     * Asserts the document declares no card number and no card secret.
     *
     * <p>An account mutation carries no card data. The card service publishes
     * {@code schemas/card-updated-v1.json} for that.
     */
    @Test
    @DisplayName("the document declares no card number and no card secret")
    void theDocumentDeclaresNoCardData() {
        for (String property : declaredProperties()) {
            String folded = property.toLowerCase(java.util.Locale.ROOT);

            assertFalse(folded.contains("card"),
                    DOCUMENT + " declares " + property + ", and an account mutation carries no "
                            + "card data");
            assertFalse(folded.contains("cvv"),
                    DOCUMENT + " declares " + property + ", and CARD-CVV-CD at "
                            + "app/cpy/CVACT02Y.cpy:L7 reaches no event");
        }
    }

    /**
     * One event for a given change kind, carrying the account values above.
     *
     * @param changeType which mutation produced the event
     * @return an event that satisfies the document
     */
    private static AccountStateChanged eventOf(AccountStateChanged.ChangeKind changeType) {
        return AccountStateChanged.of(ACCOUNT_ID, changeType, CREDIT_LIMIT, ZERO, ZERO,
                EXPIRATION_DATE);
    }

    /**
     * The wire form one change kind writes, read back as a tree.
     *
     * @param changeType which mutation produced the event
     * @return the serialized event as a JSON object
     */
    private static ObjectNode wireForm(AccountStateChanged.ChangeKind changeType) {
        byte[] bytes = SERIALIZER.serialize(TOPIC, eventOf(changeType));

        if (bytes == null) {
            return fail("AccountStateChanged serialized to no bytes for " + changeType);
        }
        JsonNode tree = MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
        if (!tree.isObject()) {
            return fail("AccountStateChanged serialized to something other than a JSON object");
        }
        return (ObjectNode) tree;
    }

    /**
     * The property names the document declares, in document order, apart from {@code extensions}.
     *
     * @return the keys of the {@code properties} block, without the extension point
     */
    private static Set<String> declaredProperties() {
        try (InputStream stream = AccountStateChangedPublishPathTest.class.getClassLoader()
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
}
