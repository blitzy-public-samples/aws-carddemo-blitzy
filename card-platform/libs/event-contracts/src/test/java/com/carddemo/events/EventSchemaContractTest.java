package com.carddemo.events;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the four authored JSON Schema documents behave as one contract.
 *
 * <p>ADDITIVE. This class has no COBOL ancestor. The CardDemo source holds no Java and no test of
 * any kind, so nothing here is a translation. The locators below fix the widths, the texts, and the
 * codes the documents reproduce.
 *
 * <p>The four documents load from the classpath at {@code schemas/}. Every assertion runs offline:
 * no {@code $id} is dereferenced, no schema registry service is contacted, and no Kafka broker
 * starts. Loading, validation, round trip, malformed-payload rejection, full Primary Account Number
 * (PAN) rejection, decline code and text pairing, the account identity rule, and additive-only
 * evolution each have their own test.
 *
 * <p>Source locators. The four decline codes and their texts sit at
 * {@code app/cbl/CBTRN02C.cbl:L385-L387}, {@code :L397-L399}, {@code :L410-L412} and
 * {@code :L417-L419}. The reason width is {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at
 * {@code app/cbl/CBTRN02C.cbl:L181} and the description width is
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at {@code :L182}. The nine-integer-digit amount
 * is {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10} and the ten-integer-digit
 * balance is {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7}. The
 * eleven-digit account identifier is {@code XREF-ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT03Y.cpy:L7}. The card verification value that no event carries is
 * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} and the card status no event
 * carries is {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code :L10}.
 *
 * <p>Two pitfalls this suite guards. Every module declares {@code java.version} 25; the framework
 * parent defaults the language level to 17 and a module that omits the override compiles at 17 with
 * no warning. The wire form is flat. A payload nesting the five envelope fields under an
 * {@code envelope} key fails every document.
 *
 * <p>Adding a consumer needs no producer change. The fixed required sets asserted here and the open
 * property set every document declares are what keeps that true. Versions in use: Java 25, Apache
 * Maven 3.9.16, junit-jupiter 6.0.3, spring-boot-starter-test 4.1.0, json-schema-validator 3.0.6,
 * jackson-databind 3.1.4, kafka-clients 4.2.1.
 */
class EventSchemaContractTest {

    /** The classpath resource holding the approved authorization contract. */
    private static final String AUTHORIZED = "schemas/transaction-authorized-v1.json";

    /** The classpath resource holding the declined authorization contract. */
    private static final String DECLINED = "schemas/transaction-declined-v1.json";

    /** The classpath resource holding the ledger posting contract. */
    private static final String POSTED = "schemas/transaction-posted-v1.json";

    /** The classpath resource holding the flagged risk assessment contract. */
    private static final String FLAGGED = "schemas/fraud-flagged-v1.json";

    /** The four resources this suite reads, in contract order. */
    private static final List<String> DOCUMENTS = List.of(AUTHORIZED, DECLINED, POSTED, FLAGGED);

    /** The dialect every document declares. */
    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    /** The envelope property carrying the account identifier and the Kafka message key. */
    private static final String ENVELOPE_ACCOUNT_PROPERTY = "aggregateId";

    /** The payload property carrying the same account identifier. */
    private static final String PAYLOAD_ACCOUNT_PROPERTY = "accountId";

    /** The account identifier every valid instance in this suite carries. */
    private static final String ACCOUNT_IDENTIFIER = "00000000007";

    /**
     * The shape both account identifiers take, from {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7}.
     */
    private static final String ACCOUNT_IDENTIFIER_PATTERN = "^[0-9]{11}$";

    /** A second account identifier, used to build a mismatched pair. */
    private static final String OTHER_ACCOUNT_IDENTIFIER = "00000000011";

    /** The masked card number every valid instance carries. */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /**
     * A synthetic token at the width of a card number, which no event may carry. It opens with
     * twelve zeros, and no card number opens with a zero, so this class holds no card number of any
     * fixture. Its last four characters match {@link #MASKED_CARD_NUMBER}, so the pattern check is
     * the only reason the document rejects it.
     */
    private static final String FULL_CARD_NUMBER = "0".repeat(12) + "7065";

    /** The property that carries a masked card number on three of the four documents. */
    private static final String MASKED_CARD_PROPERTY = "maskedCardNumber";

    /** The amount property, present on three documents. */
    private static final String AMOUNT_PROPERTY = "amount";

    /** The balance property, present on the posting document alone. */
    private static final String BALANCE_PROPERTY = "newBalance";

    /** The nine-integer-digit money pattern, from {@code TRAN-AMT PIC S9(09)V99}. */
    private static final String NINE_INTEGER_DIGIT_MONEY_PATTERN = "^-?\\d{1,9}\\.\\d{2}$";

    /** The ten-integer-digit money pattern, from {@code ACCT-CURR-BAL PIC S9(10)V99}. */
    private static final String TEN_INTEGER_DIGIT_MONEY_PATTERN = "^-?\\d{1,10}\\.\\d{2}$";

    /** The masked card number pattern: twelve mask characters then four digits. */
    private static final String MASKED_CARD_PATTERN = "^\\*{12}[0-9]{4}$";

    /** The reason code property on the declined document. */
    private static final String DECLINE_CODE_PROPERTY = "declineReasonCode";

    /** The reason text property on the declined document. */
    private static final String DECLINE_TEXT_PROPERTY = "declineReasonDescription";

    /** The width of {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}. */
    private static final int DECLINE_TEXT_WIDTH = 76;

    /** The four reason codes paired with the text the source moves beside each one. */
    private static final Map<String, String> DECLINE_PAIRS = declinePairs();

    /** The required-property count each document declares. */
    private static final Map<String, Integer> REQUIRED_COUNTS = Map.of(
            AUTHORIZED, 19,
            DECLINED, 11,
            POSTED, 11,
            FLAGGED, 10);

    /**
     * The documents that declare the payload property {@code accountId} beside the envelope
     * property {@code aggregateId}, matching the components their Java records carry. All four
     * carry it, because the event contract names an account identifier in the authorized, declined,
     * posted and flagged events. A document entering or leaving this set fails
     * {@link #theExecutableRuleEnforcesTheAccountIdentityTheSchemaCannot}.
     */
    private static final Set<String> PAYLOAD_ACCOUNT_DOCUMENTS =
            Set.of(AUTHORIZED, DECLINED, POSTED, FLAGGED);

    /** Reads and writes JSON trees. Jackson 3 only; no Jackson 2 type appears in this class. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Compiles a document into a validating schema. */
    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    /**
     * Asserts that all four documents load from the classpath and compile as Draft 2020-12
     * schemas, and that each declares the closed-object skeleton the wire form depends on.
     *
     * <p>The top-level property set is closed, so a validator refuses a property no document
     * declares and a producer cannot carry an undeclared field, an unmasked card number among them,
     * inside a known event type. Additive room lives in the bounded {@code extensions} object
     * instead, which a consumer validating against version one already accepts, so the first
     * additive change breaks no existing consumer.</p>
     */
    @Test
    void everyDocumentLoadsFromTheClasspathAndCompiles() {
        for (String document : DOCUMENTS) {
            JsonNode tree = readDocument(document);

            assertEquals(DRAFT_2020_12, tree.get("$schema").asString(),
                    document + " stopped declaring the Draft 2020-12 dialect");
            assertNotNull(tree.get("$id"), document + " stopped declaring an $id");
            assertNotNull(tree.get("title"), document + " stopped declaring a title");
            assertEquals("object", tree.get("type").asString(),
                    document + " stopped declaring an object type");
            assertFalse(tree.get("additionalProperties").asBoolean(),
                    document + " reopened its top-level property set, so a producer could carry a "
                            + "field no schema describes inside a known event type");
            assertEquals(tree.get("properties").size(), tree.get("maxProperties").asInt(),
                    document + " declares a maxProperties ceiling that does not match the number "
                            + "of properties it declares");
            assertNotNull(tree.get("properties").get("extensions"),
                    document + " stopped declaring the bounded extensions object, so a later "
                            + "version has nowhere to put an added value");

            assertNotNull(schemaFor(document), document + " failed to compile");
            assertFalse(containsReference(tree),
                    document + " introduced a $ref, which this suite resolves nowhere");
        }
    }

    @Test
    void everyRequiredEntryNamesADeclaredPropertyAndTheTitleMatchesTheEventType() {
        for (String document : DOCUMENTS) {
            JsonNode tree = readDocument(document);
            JsonNode properties = tree.get("properties");

            assertEquals(tree.get("title").asString(),
                    properties.get("eventType").get("const").asString(),
                    document + " lost the title and eventType agreement");

            for (JsonNode entry : tree.get("required")) {
                assertTrue(properties.has(entry.asString()),
                        document + " requires " + entry.asString() + " and declares no schema "
                                + "for it");
            }
        }
    }

    @Test
    void theRequiredPropertyCountOfEachDocumentIsFixed() {
        for (String document : DOCUMENTS) {
            assertEquals(REQUIRED_COUNTS.get(document), readDocument(document).get("required").size(),
                    document + " changed its required-property count");
        }
    }

    @Test
    void moneyTravelsAsAStringAndTheTwoWidthsStayApart() {
        for (String document : DOCUMENTS) {
            JsonNode properties = readDocument(document).get("properties");

            for (Map.Entry<String, JsonNode> property : properties.properties()) {
                assertFalse("number".equals(typeOf(property.getValue())),
                        document + " declared property " + property.getKey() + " as a JSON number");
            }

            if (properties.has(AMOUNT_PROPERTY)) {
                assertEquals("string", typeOf(properties.get(AMOUNT_PROPERTY)),
                        document + " stopped carrying the amount as a string");
                assertEquals(NINE_INTEGER_DIGIT_MONEY_PATTERN,
                        properties.get(AMOUNT_PROPERTY).get("pattern").asString(),
                        document + " changed the nine-integer-digit amount pattern");
            }

            if (properties.has(BALANCE_PROPERTY)) {
                assertEquals("string", typeOf(properties.get(BALANCE_PROPERTY)),
                        document + " stopped carrying the balance as a string");
                assertEquals(TEN_INTEGER_DIGIT_MONEY_PATTERN,
                        properties.get(BALANCE_PROPERTY).get("pattern").asString(),
                        document + " changed the ten-integer-digit balance pattern");
            }
        }

        JsonNode postedProperties = readDocument(POSTED).get("properties");
        assertFalse(postedProperties.get(AMOUNT_PROPERTY).get("pattern").asString()
                        .equals(postedProperties.get(BALANCE_PROPERTY).get("pattern").asString()),
                POSTED + " gave the amount and the balance one pattern");
    }

    @Test
    void integerAppearsOnSchemaVersionEverywhereAndOnRiskScoreOnce() {
        for (String document : DOCUMENTS) {
            JsonNode properties = readDocument(document).get("properties");

            Set<String> integerProperties = new LinkedHashSet<>();
            for (Map.Entry<String, JsonNode> property : properties.properties()) {
                if ("integer".equals(typeOf(property.getValue()))) {
                    integerProperties.add(property.getKey());
                }
            }

            Set<String> expected = FLAGGED.equals(document)
                    ? Set.of("schemaVersion", "riskScore")
                    : Set.of("schemaVersion");
            assertEquals(expected, integerProperties,
                    document + " changed which properties are JSON integers");
        }

        JsonNode riskScore = readDocument(FLAGGED).get("properties").get("riskScore");
        assertEquals(0, riskScore.get("minimum").asInt(), FLAGGED + " changed the score floor");
        assertEquals(100, riskScore.get("maximum").asInt(), FLAGGED + " changed the score ceiling");
    }

    @Test
    void aValidInstanceRoundTripsWithItsAmountScaleIntact() {
        for (String document : DOCUMENTS) {
            ObjectNode instance = validInstance(document);
            String written = MAPPER.writeValueAsString(instance);

            assertEquals(List.of(), validate(document, written),
                    document + " rejected its own valid instance");

            JsonNode reread = MAPPER.readTree(written);
            String rewritten = MAPPER.writeValueAsString(reread);

            assertEquals(written, rewritten, document + " changed shape across a round trip");
            assertEquals(List.of(), validate(document, rewritten),
                    document + " rejected its instance after a round trip");
            assertEquals(instance, reread, document + " lost a value across a round trip");

            for (String moneyProperty : List.of(AMOUNT_PROPERTY, BALANCE_PROPERTY)) {
                if (!reread.has(moneyProperty)) {
                    continue;
                }
                assertTrue(reread.get(moneyProperty).isString(),
                        document + " turned " + moneyProperty + " into a non-string node");
                assertEquals(instance.get(moneyProperty).asString(),
                        reread.get(moneyProperty).asString(),
                        document + " changed " + moneyProperty + " across a round trip");
            }
        }
    }

    @Test
    void droppingAnyRequiredPropertyFailsValidation() {
        for (String document : DOCUMENTS) {
            ObjectNode valid = validInstance(document);

            for (JsonNode entry : readDocument(document).get("required")) {
                String property = entry.asString();
                ObjectNode broken = valid.deepCopy();
                broken.remove(property);

                List<String> errors = validate(document, MAPPER.writeValueAsString(broken));

                assertFalse(errors.isEmpty(),
                        document + " accepted an instance missing " + property);
                assertTrue(errors.stream().anyMatch(message -> message.contains(property)),
                        document + " failed without naming the missing property " + property);
            }
        }
    }

    /**
     * Asserts that an unknown top-level property fails validation while an added value inside the
     * bounded {@code extensions} object passes.
     *
     * <p>The two halves belong together. Refusing the unknown top-level property is what stops a
     * producer from carrying a field no document describes inside a known event type. Accepting the
     * value inside {@code extensions} is what lets a later version add something without breaking a
     * consumer that validates against this one.
     */
    @Test
    void anUnknownPropertyIsRefusedWhileAnAddedExtensionValueKeepsAConsumerWorking() {
        for (String document : DOCUMENTS) {
            ObjectNode smuggled = validInstance(document);
            smuggled.put("unmappedField", "value");

            assertFalse(validate(document, MAPPER.writeValueAsString(smuggled)).isEmpty(),
                    document + " accepted an undeclared top-level property, so a producer could "
                            + "carry a field no schema describes inside a known event type");

            ObjectNode enriched = validInstance(document);
            enriched.set("extensions", MAPPER.createObjectNode().put("unmappedField", "value"));

            assertEquals(List.of(), validate(document, MAPPER.writeValueAsString(enriched)),
                    document + " rejected a value a later version could add inside extensions, "
                            + "which would break every consumer validating against this version");
        }
    }

    @Test
    void aNestedEnvelopeShapeFailsEveryDocument() {
        for (String document : DOCUMENTS) {
            ObjectNode valid = validInstance(document);
            ObjectNode envelope = MAPPER.createObjectNode();
            ObjectNode nested = MAPPER.createObjectNode();

            for (Map.Entry<String, JsonNode> property : valid.properties()) {
                if (envelopeProperties().contains(property.getKey())) {
                    envelope.set(property.getKey(), property.getValue());
                } else {
                    nested.set(property.getKey(), property.getValue());
                }
            }
            nested.set("envelope", envelope);

            List<String> errors = validate(document, MAPPER.writeValueAsString(nested));

            assertFalse(errors.isEmpty(), document + " accepted a nested envelope shape");
            for (String envelopeProperty : envelopeProperties()) {
                assertTrue(errors.stream().anyMatch(message -> message.contains(envelopeProperty)),
                        document + " failed without naming the missing envelope property "
                                + envelopeProperty);
            }
        }
    }

    @Test
    void theWrongEventTypeFailsValidation() {
        for (String document : DOCUMENTS) {
            ObjectNode broken = validInstance(document);
            broken.put("eventType", "SomeOtherEvent");

            assertFalse(validate(document, MAPPER.writeValueAsString(broken)).isEmpty(),
                    document + " accepted a payload declaring another event type");
        }
    }

    @Test
    void aMalformedMoneyValueFailsValidation() {
        String[] rejected = {"50.4", "50.475", "1,000.00", "$50.47", "50", "", "-50.4"};

        for (String document : List.of(AUTHORIZED, DECLINED, POSTED)) {
            for (String value : rejected) {
                ObjectNode broken = validInstance(document);
                broken.put(AMOUNT_PROPERTY, value);

                assertFalse(validate(document, MAPPER.writeValueAsString(broken)).isEmpty(),
                        document + " accepted a money value of length " + value.length());
            }

            ObjectNode numeric = validInstance(document);
            numeric.remove(AMOUNT_PROPERTY);
            String withJsonNumber = MAPPER.writeValueAsString(numeric)
                    .replaceFirst("\\{", "{\"" + AMOUNT_PROPERTY + "\":504.77,");

            assertFalse(validate(document, withJsonNumber).isEmpty(),
                    document + " accepted an amount as a JSON number");
        }

        ObjectNode elevenIntegerDigits = validInstance(POSTED);
        elevenIntegerDigits.put(BALANCE_PROPERTY, "12345678901.00");
        assertFalse(validate(POSTED, MAPPER.writeValueAsString(elevenIntegerDigits)).isEmpty(),
                POSTED + " accepted a balance of eleven integer digits");

        ObjectNode negativeBalance = validInstance(POSTED);
        negativeBalance.put(BALANCE_PROPERTY, "-43.10");
        assertEquals(List.of(), validate(POSTED, MAPPER.writeValueAsString(negativeBalance)),
                POSTED + " rejected a negative balance");
    }

    @Test
    void aFullPrimaryAccountNumberFailsValidationAndNoDocumentCarriesACardSecret() {
        for (String document : List.of(AUTHORIZED, DECLINED, POSTED)) {
            JsonNode masked = readDocument(document).get("properties").get(MASKED_CARD_PROPERTY);

            assertEquals(MASKED_CARD_PATTERN, masked.get("pattern").asString(),
                    document + " changed the masked card number pattern");
            assertEquals(16, masked.get("minLength").asInt(),
                    document + " changed the masked card number minimum width");
            assertEquals(16, masked.get("maxLength").asInt(),
                    document + " changed the masked card number maximum width");

            ObjectNode broken = validInstance(document);
            broken.put(MASKED_CARD_PROPERTY, FULL_CARD_NUMBER);
            assertFalse(validate(document, MAPPER.writeValueAsString(broken)).isEmpty(),
                    document + " accepted an unmasked card number");

            ObjectNode accepted = validInstance(document);
            accepted.put(MASKED_CARD_PROPERTY, MASKED_CARD_NUMBER);
            assertEquals(List.of(), validate(document, MAPPER.writeValueAsString(accepted)),
                    document + " rejected a masked card number");
        }

        assertFalse(readDocument(FLAGGED).get("properties").has(MASKED_CARD_PROPERTY),
                FLAGGED + " began carrying a card number");

        String[] forbiddenFragments = {"cvv", "activestatus", "active_status", "cardstatus",
                "accountstatus", "luhn", "checksum"};
        for (String document : DOCUMENTS) {
            for (String propertyName : readDocument(document).get("properties").propertyNames()) {
                String folded = propertyName.toLowerCase(Locale.ROOT);
                for (String fragment : forbiddenFragments) {
                    assertFalse(folded.contains(fragment),
                            document + " declared a forbidden property named " + propertyName);
                }
            }
        }
    }

    /**
     * Asserts that each of the four decline codes validates only beside the text the source moves
     * with it, and that every mismatched pair fails.
     *
     * <p>Codes and texts sit at {@code app/cbl/CBTRN02C.cbl:L385-L387}, {@code :L397-L399},
     * {@code :L410-L412} and {@code :L417-L419}.</p>
     */
    @Test
    void everyDeclineCodePairsOnlyWithItsOwnText() {
        JsonNode properties = readDocument(DECLINED).get("properties");

        List<String> declaredCodes = new ArrayList<>();
        for (JsonNode code : properties.get(DECLINE_CODE_PROPERTY).get("enum")) {
            declaredCodes.add(code.asString());
        }
        assertEquals(List.copyOf(DECLINE_PAIRS.keySet()), declaredCodes,
                DECLINED + " changed the reason-code set");

        List<String> declaredTexts = new ArrayList<>();
        for (JsonNode text : properties.get(DECLINE_TEXT_PROPERTY).get("enum")) {
            declaredTexts.add(text.asString());
        }
        assertEquals(List.copyOf(DECLINE_PAIRS.values()), declaredTexts,
                DECLINED + " changed the reason-text set");
        assertEquals(DECLINE_TEXT_WIDTH,
                properties.get(DECLINE_TEXT_PROPERTY).get("maxLength").asInt(),
                DECLINED + " changed the reason-text width");
        assertFalse(properties.get(DECLINE_CODE_PROPERTY).get("type").isArray(),
                DECLINED + " turned the reason code into more than one value");

        for (Map.Entry<String, String> pair : DECLINE_PAIRS.entrySet()) {
            ObjectNode matching = validInstance(DECLINED);
            matching.put(DECLINE_CODE_PROPERTY, pair.getKey());
            matching.put(DECLINE_TEXT_PROPERTY, pair.getValue());

            assertEquals(List.of(), validate(DECLINED, MAPPER.writeValueAsString(matching)),
                    DECLINED + " rejected the source pair for code " + pair.getKey());

            for (Map.Entry<String, String> other : DECLINE_PAIRS.entrySet()) {
                if (other.getKey().equals(pair.getKey())) {
                    continue;
                }
                ObjectNode mismatched = validInstance(DECLINED);
                mismatched.put(DECLINE_CODE_PROPERTY, pair.getKey());
                mismatched.put(DECLINE_TEXT_PROPERTY, other.getValue());

                assertFalse(validate(DECLINED, MAPPER.writeValueAsString(mismatched)).isEmpty(),
                        DECLINED + " accepted code " + pair.getKey() + " beside the text of code "
                                + other.getKey());
            }
        }
    }

    /**
     * Asserts that a code outside the four fails validation, whether it arrives as a string or as a
     * bare number.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L556-L558} writes a fifth value after the account rewrite hits
     * an invalid key. Nothing reads it, so it is not a decline; in the target it becomes a consumer
     * failure routed to the dead-letter topic and it is excluded from the enum.</p>
     */
    @Test
    void aCodeOutsideTheFourFailsValidation() {
        for (String value : new String[] {"0109", "0104", "100", "0100 ", ""}) {
            ObjectNode broken = validInstance(DECLINED);
            broken.put(DECLINE_CODE_PROPERTY, value);

            assertFalse(validate(DECLINED, MAPPER.writeValueAsString(broken)).isEmpty(),
                    DECLINED + " accepted a reason code of length " + value.length());
        }

        ObjectNode withoutCode = validInstance(DECLINED);
        withoutCode.remove(DECLINE_CODE_PROPERTY);
        String withBareNumber = MAPPER.writeValueAsString(withoutCode)
                .replaceFirst("\\{", "{\"" + DECLINE_CODE_PROPERTY + "\":9999,");

        assertFalse(validate(DECLINED, withBareNumber).isEmpty(),
                DECLINED + " accepted a reason code as a bare number");
    }

    /**
     * Asserts that both account properties are declared, that the keyword vocabulary alone cannot
     * police their agreement, and that the executable rule does.
     *
     * <p>{@code aggregateId} is the Kafka message key and {@code accountId} carries the same value
     * in the payload, so a consumer reads one account identity. Both hold the eleven-digit
     * identifier from {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}.</p>
     *
     * <p>Four documents declare an {@code accountId} of their own beside the envelope one, because
     * the user's event contract names an account identifier in the authorized, declined, posted and
     * flagged events and the records that carry them emit it. Draft 2020-12 has no keyword comparing one property to
     * another, so {@link #accountIdentityErrors} carries that check, exactly as the publisher does
     * before it sends. This test pins both halves: which documents declare the payload property,
     * and that the two properties carry one value under one width.</p>
     */
    @Test
    void theExecutableRuleEnforcesTheAccountIdentityTheSchemaCannot() {
        for (String document : DOCUMENTS) {
            JsonNode tree = readDocument(document);
            JsonNode properties = tree.get("properties");

            assertTrue(properties.has(ENVELOPE_ACCOUNT_PROPERTY),
                    document + " stopped carrying " + ENVELOPE_ACCOUNT_PROPERTY);
            assertEquals(PAYLOAD_ACCOUNT_DOCUMENTS.contains(document),
                    properties.has(PAYLOAD_ACCOUNT_PROPERTY),
                    document + " changed whether it declares the payload property "
                            + PAYLOAD_ACCOUNT_PROPERTY);
            if (properties.has(PAYLOAD_ACCOUNT_PROPERTY)) {
                assertTrue(requiredSet(document).contains(PAYLOAD_ACCOUNT_PROPERTY),
                        document + " declares " + PAYLOAD_ACCOUNT_PROPERTY + " without requiring "
                                + "it, so a producer could omit the identifier the contract names");
                assertEquals(properties.get(ENVELOPE_ACCOUNT_PROPERTY).get("pattern").asString(),
                        properties.get(PAYLOAD_ACCOUNT_PROPERTY).get("pattern").asString(),
                        document + " gave " + PAYLOAD_ACCOUNT_PROPERTY + " a width other than the "
                                + "one " + ENVELOPE_ACCOUNT_PROPERTY + " carries");
            }
            assertTrue(declaresAccountIdentity(tree),
                    document + " stopped declaring which properties hold account identity");

            ObjectNode single = validInstance(document);
            assertEquals(List.of(), validate(document, MAPPER.writeValueAsString(single)),
                    document + " rejected an instance carrying one account identity");
            assertEquals(List.of(), accountIdentityErrors(document, single),
                    document + " reported a mismatch where one account identity is carried");

            ObjectNode agreeing = validInstance(document);
            assertEquals(List.of(), validate(document, MAPPER.writeValueAsString(agreeing)),
                    document + " rejected an instance whose two account identifiers agree");
            assertEquals(List.of(), accountIdentityErrors(document, agreeing),
                    document + " reported a mismatch where both identifiers agree");

            ObjectNode mismatched = validInstance(document);
            mismatched.put(PAYLOAD_ACCOUNT_PROPERTY, OTHER_ACCOUNT_IDENTIFIER);

            if (declaresPayloadAccountProperty(document)) {
                assertEquals(List.of(), validate(document, MAPPER.writeValueAsString(mismatched)),
                        document + " began expressing property equality as a keyword, so the "
                                + "executable rule needs revisiting");
            } else {
                assertFalse(validate(document, MAPPER.writeValueAsString(mismatched)).isEmpty(),
                        document + " declares no payload account identifier, so its closed "
                                + "property set must refuse a second one outright");
            }
            assertEquals(1, accountIdentityErrors(document, mismatched).size(),
                    document + " let two disagreeing account identifiers through the rule");
        }
    }

    @Test
    void evolutionStaysAdditiveSoAnExistingConsumerKeepsWorking() {
        assertEquals(Set.of("eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId",
                        "transactionId", "transactionTypeCode", "merchantCategoryCode", "source",
                        "description", "amount", "merchantId", "merchantName", "merchantCity",
                        "merchantZip", "maskedCardNumber", "authorizedAt",
                        PAYLOAD_ACCOUNT_PROPERTY, "currency"),
                requiredSet(AUTHORIZED), AUTHORIZED + " changed its required set");

        assertEquals(Set.of("eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId",
                        "transactionId", PAYLOAD_ACCOUNT_PROPERTY, DECLINE_CODE_PROPERTY,
                        DECLINE_TEXT_PROPERTY, "amount", "maskedCardNumber"),
                requiredSet(DECLINED), DECLINED + " changed its required set");

        assertEquals(Set.of("eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId",
                        "transactionId", PAYLOAD_ACCOUNT_PROPERTY, "newBalance", "postedAt",
                        "amount", "maskedCardNumber"),
                requiredSet(POSTED), POSTED + " changed its required set");

        assertEquals(Set.of("eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId",
                        "transactionId", "riskScore", "triggeredRules", "assessedAt",
                        PAYLOAD_ACCOUNT_PROPERTY),
                requiredSet(FLAGGED), FLAGGED + " changed its required set");

        for (String document : DOCUMENTS) {
            JsonNode tree = readDocument(document);
            assertEquals(1, tree.get("properties").get("schemaVersion").get("const").asInt(),
                    document + " changed its version constant");
            assertTrue(tree.get("$id").asString().endsWith("-v1.json"),
                    document + " lost the version suffix in its $id");
            assertTrue(document.endsWith("-v1.json"),
                    document + " lost the version suffix in its file name");
        }

        JsonNode rules = readDocument(FLAGGED).get("properties").get("triggeredRules");
        assertEquals(1, rules.get("minItems").asInt(), FLAGGED + " changed the rule-list floor");
        assertTrue(rules.get("uniqueItems").asBoolean(), FLAGGED + " allowed a repeated rule");
        assertFalse(rules.has("maxItems"),
                FLAGGED + " capped the rule list, so a new rule value would break the contract");

        List<String> ruleValues = new ArrayList<>();
        for (JsonNode value : rules.get("items").get("enum")) {
            ruleValues.add(value.asString());
        }
        assertEquals(List.of("VELOCITY", "AMOUNT_ANOMALY", "MERCHANT_CATEGORY"), ruleValues,
                FLAGGED + " changed the rule-identifier set");
    }

    @Test
    void theEnvelopeImposesIdenticalConstraintsInEveryDocument() {
        JsonNode reference = readDocument(AUTHORIZED).get("properties");

        for (String document : DOCUMENTS) {
            JsonNode properties = readDocument(document).get("properties");

            for (String envelopeProperty : envelopeProperties()) {
                if ("eventType".equals(envelopeProperty)) {
                    continue;
                }
                assertEquals(constraintsOf(reference.get(envelopeProperty)),
                        constraintsOf(properties.get(envelopeProperty)),
                        document + " changed the constraints of the envelope property "
                                + envelopeProperty);
            }

            assertEquals("string", typeOf(properties.get("eventType")),
                    document + " changed the routing discriminator type");
            assertEquals("uuid", properties.get("eventId").get("format").asString(),
                    document + " changed the event identifier format");
            assertEquals("date-time", properties.get("occurredAt").get("format").asString(),
                    document + " changed the publish timestamp format");
            assertEquals("^[0-9]{11}$",
                    properties.get(ENVELOPE_ACCOUNT_PROPERTY).get("pattern").asString(),
                    document + " changed the account identifier width");
        }
    }

    @Test
    void onlyTheThreeIsoTimestampsDeclareADateTimeFormat() {
        JsonNode authorizedAt = readDocument(AUTHORIZED).get("properties").get("authorizedAt");
        assertFalse(authorizedAt.has("format"),
                AUTHORIZED + " declared a format on the fixed-width authorization timestamp");
        assertEquals(26, authorizedAt.get("maxLength").asInt(),
                AUTHORIZED + " changed the authorization timestamp width");

        JsonNode postedAt = readDocument(POSTED).get("properties").get("postedAt");
        assertFalse(postedAt.has("format"),
                POSTED + " declared a format on the fixed-width posting timestamp");
        assertEquals(26, postedAt.get("maxLength").asInt(),
                POSTED + " changed the posting timestamp width");

        for (String document : DOCUMENTS) {
            assertEquals("date-time",
                    readDocument(document).get("properties").get("occurredAt").get("format")
                            .asString(),
                    document + " changed the publish timestamp format");
        }
        assertEquals("date-time",
                readDocument(FLAGGED).get("properties").get("assessedAt").get("format").asString(),
                FLAGGED + " changed the assessment timestamp format");

        ObjectNode isoAuthorizedAt = validInstance(AUTHORIZED);
        isoAuthorizedAt.put("authorizedAt", "2022-06-10T19:27:53.000000");
        assertFalse(validate(AUTHORIZED, MAPPER.writeValueAsString(isoAuthorizedAt)).isEmpty(),
                AUTHORIZED + " accepted an ISO-8601 separator in the authorization timestamp");

        for (String rejected : new String[] {"2022-07-19 23:16:01.470000",
                "2022-07-19T23:16:01.470000", "2022-07-19-23.16.01.470123"}) {
            ObjectNode broken = validInstance(POSTED);
            broken.put("postedAt", rejected);
            assertFalse(validate(POSTED, MAPPER.writeValueAsString(broken)).isEmpty(),
                    POSTED + " accepted a posting timestamp of length " + rejected.length()
                            + " in the wrong layout");
        }
    }

    /**
     * The four reason codes in source order, each paired with the text the source moves beside it.
     *
     * @return the pairs, in the order the validation chain assigns them
     */
    private static Map<String, String> declinePairs() {
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put("0100", "INVALID CARD NUMBER FOUND");
        pairs.put("0101", "ACCOUNT RECORD NOT FOUND");
        pairs.put("0102", "OVERLIMIT TRANSACTION");
        pairs.put("0103", "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
        return pairs;
    }

    /**
     * The five envelope property names every document declares.
     *
     * @return the names, in declaration order
     */
    private static Set<String> envelopeProperties() {
        return new LinkedHashSet<>(List.of("eventId", "eventType", "schemaVersion", "occurredAt",
                ENVELOPE_ACCOUNT_PROPERTY));
    }

    /**
     * Reads a schema document from the classpath as a JSON tree.
     *
     * @param resource the classpath path of the document
     * @return the parsed document
     */
    private static JsonNode readDocument(String resource) {
        try (InputStream stream =
                     EventSchemaContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "the classpath holds no resource named " + resource);
            return MAPPER.readTree(stream);
        } catch (IOException failure) {
            throw new IllegalStateException("closing the stream for " + resource + " failed",
                    failure);
        }
    }

    /**
     * Compiles a schema document into a validating schema.
     *
     * @param resource the classpath path of the document
     * @return the compiled schema
     */
    private static Schema schemaFor(String resource) {
        return REGISTRY.getSchema(MAPPER.writeValueAsString(readDocument(resource)),
                InputFormat.JSON);
    }

    /**
     * Validates an instance document against one schema.
     *
     * @param resource the classpath path of the schema document
     * @param instance the instance document, as text
     * @return one message per failure, empty when the instance validates
     */
    private static List<String> validate(String resource, String instance) {
        List<String> messages = new ArrayList<>();
        for (Error error : schemaFor(resource).validate(instance, InputFormat.JSON)) {
            messages.add(error.getMessage());
        }
        return messages;
    }

    /**
     * Reports the required-property names of one document.
     *
     * @param resource the classpath path of the document
     * @return the names
     */
    private static Set<String> requiredSet(String resource) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode entry : readDocument(resource).get("required")) {
            names.add(entry.asString());
        }
        return names;
    }

    /**
     * Reports the constraint keywords of one property schema, dropping the two annotation
     * keywords a validator ignores.
     *
     * @param property the property schema
     * @return the keyword names mapped to their values
     */
    private static Map<String, JsonNode> constraintsOf(JsonNode property) {
        Map<String, JsonNode> constraints = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> keyword : property.properties()) {
            if ("description".equals(keyword.getKey()) || "examples".equals(keyword.getKey())
                    || "$comment".equals(keyword.getKey())) {
                continue;
            }
            constraints.put(keyword.getKey(), keyword.getValue());
        }
        return constraints;
    }

    /**
     * Reports the declared {@code type} of one property schema.
     *
     * @param property the property schema
     * @return the type name, or {@code null} when the property declares none
     */
    private static String typeOf(JsonNode property) {
        JsonNode type = property.get("type");
        return type == null ? null : type.asString();
    }

    /**
     * Reports whether a document holds a {@code $ref} at any depth.
     *
     * @param node the node to walk
     * @return {@code true} when a reference is present
     */
    private static boolean containsReference(JsonNode node) {
        if (node.isObject()) {
            if (node.has("$ref")) {
                return true;
            }
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                if (containsReference(property.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                if (containsReference(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Reports whether a document states that account identity has a single source.
     *
     * @param tree the parsed document
     * @return {@code true} when the statement is present
     */
    private static boolean declaresAccountIdentity(JsonNode tree) {
        String topLevelComment = tree.has("$comment") ? tree.get("$comment").asString() : "";
        String envelopeDescription = tree.get("properties").get(ENVELOPE_ACCOUNT_PROPERTY)
                .get("description").asString();

        return statesOneValue(topLevelComment) || statesOneValue(envelopeDescription);
    }

    /**
     * Reports whether a sentence names the property that carries one account identifier.
     *
     * @param text the sentence to inspect
     * @return {@code true} when it does
     */
    private static boolean statesOneValue(String text) {
        return text.contains(ENVELOPE_ACCOUNT_PROPERTY)
                && text.contains("one account identifier");
    }

    /**
     * Checks that the envelope account identifier and the payload account identifier hold one
     * value.
     *
     * <p>This is the executable half of the account identity rule. Draft 2020-12 declares no
     * keyword comparing one property to another, so the check cannot live in the document.</p>
     *
     * @param documentName the document the instance claims to follow
     * @param event        the instance document
     * @return one message per failure, empty when the instance carries one account identity
     */
    /**
     * Reports whether a document declares a payload account identifier beside the envelope one.
     *
     * <p>Only a document that declares both can express a disagreeing pair at all. Now that every
     * document closes its top-level property set, a document declaring only {@code aggregateId}
     * refuses a second identifier before the executable rule is ever consulted, which is the
     * stronger of the two outcomes.
     *
     * @param resource the classpath path of the schema document
     * @return {@code true} when the document declares {@value #PAYLOAD_ACCOUNT_PROPERTY}
     */
    private static boolean declaresPayloadAccountProperty(String resource) {
        return readDocument(resource).get("properties").get(PAYLOAD_ACCOUNT_PROPERTY) != null;
    }

    private static List<String> accountIdentityErrors(String documentName, JsonNode event) {
        List<String> errors = new ArrayList<>();
        JsonNode envelopeAccount = event.get(ENVELOPE_ACCOUNT_PROPERTY);

        if (envelopeAccount == null) {
            errors.add(documentName + " omits " + ENVELOPE_ACCOUNT_PROPERTY);
            return errors;
        }
        JsonNode payloadAccount = event.get(PAYLOAD_ACCOUNT_PROPERTY);
        if (payloadAccount != null
                && !envelopeAccount.asString().equals(payloadAccount.asString())) {
            errors.add(documentName + " carries two different account identifiers");
        }
        return errors;
    }

    /**
     * Builds an instance document that validates against one schema.
     *
     * @param resource the classpath path of the schema document
     * @return the instance, ready to mutate
     */
    private static ObjectNode validInstance(String resource) {
        ObjectNode instance = MAPPER.createObjectNode();
        instance.put("eventId", "3f1d9c62-8b4e-4a17-9f0c-2d6a5e73b418");
        instance.put("eventType", readDocument(resource).get("title").asString());
        instance.put("schemaVersion", 1);
        instance.put("occurredAt", "2022-06-10T19:27:53.412Z");
        instance.put(ENVELOPE_ACCOUNT_PROPERTY, ACCOUNT_IDENTIFIER);
        instance.put("transactionId", "0000000000683580");
        instance.put(PAYLOAD_ACCOUNT_PROPERTY, ACCOUNT_IDENTIFIER);

        // The declined and posted documents declare a payload account identifier beside the
        // envelope one, and both require it. The user's event contract names an account
        // identifier in both events, and the records that carry them have always emitted it.
        if (DECLINED.equals(resource) || POSTED.equals(resource)) {
            instance.put(PAYLOAD_ACCOUNT_PROPERTY, ACCOUNT_IDENTIFIER);
        }

        if (AUTHORIZED.equals(resource)) {
            instance.put("transactionTypeCode", "01");
            instance.put("merchantCategoryCode", "0001");
            instance.put("source", "POS TERM");
            instance.put("description", "Purchase at Abshire-Lowe");
            instance.put(AMOUNT_PROPERTY, "504.77");
            instance.put("merchantId", "800000000");
            instance.put("merchantName", "Abshire-Lowe");
            instance.put("merchantCity", "North Enoshaven");
            instance.put("merchantZip", "72112");
            instance.put(MASKED_CARD_PROPERTY, MASKED_CARD_NUMBER);
            instance.put("authorizedAt", "2022-06-10 19:27:53.000000");
            instance.put("currency", "USD");
        } else if (DECLINED.equals(resource)) {
            instance.put(PAYLOAD_ACCOUNT_PROPERTY, ACCOUNT_IDENTIFIER);
            instance.put(DECLINE_CODE_PROPERTY, "0102");
            instance.put(DECLINE_TEXT_PROPERTY, "OVERLIMIT TRANSACTION");
            instance.put(AMOUNT_PROPERTY, "504.77");
            instance.put(MASKED_CARD_PROPERTY, MASKED_CARD_NUMBER);
        } else if (POSTED.equals(resource)) {
            instance.put(PAYLOAD_ACCOUNT_PROPERTY, ACCOUNT_IDENTIFIER);
            instance.put(BALANCE_PROPERTY, "1250.75");
            instance.put("postedAt", "2022-07-19-23.16.01.470000");
            instance.put(AMOUNT_PROPERTY, "504.77");
            instance.put(MASKED_CARD_PROPERTY, MASKED_CARD_NUMBER);
        } else {
            instance.put("riskScore", 82);
            instance.putArray("triggeredRules").add("VELOCITY").add("AMOUNT_ANOMALY");
            instance.put("assessedAt", "2022-06-10T19:27:53.512Z");
        }
        return instance;
    }
}
