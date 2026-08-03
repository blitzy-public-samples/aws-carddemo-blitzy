package com.carddemo.events;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Freezes the version-one baseline of the five event schema documents.
 *
 * <p>ADDITIVE. This class has no COBOL ancestor. The CardDemo source holds no Java and no test of
 * any kind, so nothing here translates a source construct. The locators below fix the widths, the
 * texts and the codes that the five documents reproduce.
 *
 * <p>Each test reads one structural invariant of the documents at {@code schemas/} on the
 * classpath and asserts it. A later edit that would break a consumer already reading the topic
 * turns one of these tests red, which fails the build. Every assertion runs offline: no
 * {@code $id} is dereferenced, no schema registry service is contacted, and no Kafka broker
 * starts.
 *
 * <p>Decline locators. The four codes and their texts sit at
 * {@code app/cbl/CBTRN02C.cbl:L385-L387}, {@code :L397-L399}, {@code :L410-L412} and
 * {@code :L417-L419}. The condition blocks that reach the last two are {@code :L403-L413} and
 * {@code :L414-L420}. The reason width is {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at
 * {@code :L181} and the description width is {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at
 * {@code :L182}. {@code app/jcl/POSTTRAN.jcl:L36} allocates the reject dataset at 430 bytes and
 * corroborates both widths.
 *
 * <p>Money and timestamp locators. The nine-integer-digit amount is
 * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10} and the ten-integer-digit
 * balance is {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7}. The two
 * fixed-width timestamps are {@code TRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L16}
 * and {@code TRAN-PROC-TS PIC X(26)} at {@code :L17}. The second carries hundredths of a second,
 * from {@code DB2-MIL PIC 9(002)} at {@code app/cbl/CBTRN02C.cbl:L173} and the four-zero move at
 * {@code :L701}.
 *
 * <p>Identifier and omitted-field locators. The eleven-digit account identifier is
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. The card verification value
 * no event carries is {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}, and the
 * card status no event carries is {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code :L10}.
 *
 * <p>A decline is expected traffic. {@code app/cbl/CBTRN02C.cbl:L229-L230} sets return code 4 when
 * the reject count is positive, so no test here treats a decline as an error.
 *
 * <p>Two pitfalls. Every module declares {@code java.version} 25; the framework parent defaults
 * the language level to 17, and a module that omits the override compiles at 17 with no warning
 * and no failure. The wire form is flat: a payload that nests the five envelope fields under an
 * {@code envelope} key fails all five documents.
 *
 * <p>Extending the platform. Adding a consumer needs no producer change, since a new consumer
 * group reads an event that already exists. The fixed required sets and the open property set
 * asserted here are what keep that true. {@code card-platform/docs/event-flow.md} traces where
 * each event travels.
 *
 * <p>{@code card-platform/docs/decision-log.md} holds the rationale for every choice these five
 * documents make.
 *
 * <p>Versions in use: Java 25, Apache Maven 3.9.16, junit-jupiter 6.0.3,
 * spring-boot-starter-test 4.1.0, json-schema-validator 3.0.6, jackson-databind 3.1.4 and
 * kafka-clients 4.2.1.
 */
class SchemaBackwardCompatibilityTest {

    /** The approved authorization contract, and the reference copy of the envelope block. */
    private static final String AUTHORIZED = "schemas/transaction-authorized-v1.json";

    /** The declined authorization contract, holding the four reject reasons. */
    private static final String DECLINED = "schemas/transaction-declined-v1.json";

    /** The ledger posting contract, holding the new account balance. */
    private static final String POSTED = "schemas/transaction-posted-v1.json";

    /** The flagged risk assessment contract. */
    private static final String FLAGGED = "schemas/fraud-flagged-v1.json";

    /** The cleared risk assessment contract. */
    private static final String CLEARED = "schemas/fraud-cleared-v1.json";

    /** Every document this baseline covers, written as literals and never derived from a name. */
    private static final List<String> DOCUMENTS =
            List.of(AUTHORIZED, DECLINED, POSTED, FLAGGED, CLEARED);

    /** The dialect every document declares. */
    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    /** The simple class name each document titles itself with, and its {@code eventType} const. */
    private static final Map<String, String> TITLES = Map.of(
            AUTHORIZED, "TransactionAuthorized",
            DECLINED, "TransactionDeclined",
            POSTED, "TransactionPosted",
            FLAGGED, "FraudFlagged",
            CLEARED, "FraudCleared");

    /** The eight keywords every document declares, in the relative order they appear. */
    private static final List<String> ORDERED_TOP_LEVEL_KEYWORDS = List.of(
            "$schema", "$id", "title", "description", "type", "properties", "required",
            "additionalProperties");

    /** The five envelope properties, in the order every document declares them. */
    private static final List<String> ENVELOPE_PROPERTIES = List.of(
            "eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId");

    /**
     * Keywords that annotate a property and assert nothing about an instance. A difference in one
     * of these carries no wire effect, so the envelope comparison skips them.
     */
    private static final Set<String> ANNOTATION_KEYWORDS = Set.of(
            "description", "examples", "$comment", "title", "default", "deprecated", "readOnly",
            "writeOnly");

    /** The exact required set of each document. A name entering or leaving this set is breaking. */
    private static final Map<String, Set<String>> REQUIRED_SETS = requiredSets();

    /** Nine integer digits and two fractional digits, from {@code app/cpy/CVTRA05Y.cpy:L10}. */
    private static final String NINE_INTEGER_DIGIT_AMOUNT_PATTERN = "^-?\\d{1,9}\\.\\d{2}$";

    /** Ten integer digits and two fractional digits, from {@code app/cpy/CVACT01Y.cpy:L7}. */
    private static final String TEN_INTEGER_DIGIT_BALANCE_PATTERN = "^-?\\d{1,10}\\.\\d{2}$";

    /** Twelve asterisks and the last four digits of the Primary Account Number (PAN). */
    private static final String MASKED_CARD_PATTERN = "^\\*{12}[0-9]{4}$";

    /** The eleven-digit account identifier, from {@code app/cpy/CVACT03Y.cpy:L7}. */
    private static final String ACCOUNT_IDENTIFIER_PATTERN = "^[0-9]{11}$";

    /** Space separator, colons, and six fractional digits. From {@code TRAN-ORIG-TS}. */
    private static final String AUTHORIZED_AT_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}$";

    /** Three dashes, three dots, hundredths, then four zeros. From {@code TRAN-PROC-TS}. */
    private static final String POSTED_AT_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}0000$";

    /** The property carrying the transaction amount on three of the five documents. */
    private static final String AMOUNT_PROPERTY = "amount";

    /** The property carrying the account balance after a posting. */
    private static final String BALANCE_PROPERTY = "newBalance";

    /** The property carrying the masked Primary Account Number (PAN) on three documents. */
    private static final String MASKED_CARD_PROPERTY = "maskedCardNumber";

    /** The property carrying the reject reason code. */
    private static final String DECLINE_CODE_PROPERTY = "declineReasonCode";

    /** The property carrying the reject reason text. */
    private static final String DECLINE_TEXT_PROPERTY = "declineReasonDescription";

    /** The reason text width, from {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}. */
    private static final int DECLINE_TEXT_WIDTH = 76;

    /** Each reject reason code paired with the text the source writes beside it. */
    private static final Map<String, String> DECLINE_PAIRS = declinePairs();

    /**
     * The code the source writes at {@code app/cbl/CBTRN02C.cbl:L556} after the account rewrite
     * hits an invalid key, excluded from the decline enum and routed to the dead-letter topic.
     */
    private static final String EXCLUDED_REASON_CODE = "0109";

    /** The three rule identifiers the flagged document enumerates. */
    private static final List<String> TRIGGERED_RULE_VALUES = List.of(
            "VELOCITY", "AMOUNT_ANOMALY", "MERCHANT_CATEGORY");

    /** Card secrets and status flags that no document may name. Lower case for a text scan. */
    private static final List<String> FORBIDDEN_SOURCE_FIELDS = List.of(
            "cvv", "activestatus", "active_status", "cardstatus", "accountstatus");

    /** Card-number checks the source does not perform, so no document may declare one. */
    private static final List<String> FORBIDDEN_CARD_CHECKS = List.of(
            "luhn", "checksum", "mod10", "mod 10");

    /** Property-name fragments each document must not carry. Lower case for a name scan. */
    private static final Map<String, List<String>> FORBIDDEN_PROPERTY_FRAGMENTS = Map.of(
            AUTHORIZED, List.of("processedat", "procts", "proc-ts", "filler"),
            DECLINED, List.of("filler", "rejecttrandata", "reject-tran-data"),
            POSTED, List.of("authorizedat", "origts", "orig-ts", "cyccredit", "cycdebit",
                    "creditlimit", "filler"),
            FLAGGED, List.of("cardnumber", "maskedcard", "pan", "amount", "balance", "authorizedat",
                    "postedat"),
            CLEARED, List.of("riskscore", "triggered", "rules", "amount", "balance", "cardnumber",
                    "maskedcard", "pan", "cvv", "reason", "note"));

    /** A well-formed event identifier, matching the envelope pattern. */
    private static final String EVENT_ID = "3f1d9c62-8b4e-4a17-9f0c-2d6a5e73b418";

    /** A well-formed publish timestamp, in Coordinated Universal Time. */
    private static final String OCCURRED_AT = "2022-06-10T19:27:53.412Z";

    /** A well-formed risk assessment timestamp, in Coordinated Universal Time. */
    private static final String ASSESSED_AT = "2022-06-10T19:27:53.512Z";

    /** A well-formed account identifier, eleven digits. */
    private static final String ACCOUNT_IDENTIFIER = "00000000007";

    /** A well-formed transaction identifier, sixteen characters. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /** A masked card number that the pattern accepts. */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /** A full sixteen-digit Primary Account Number (PAN) that the pattern rejects. */
    private static final String FULL_CARD_NUMBER = "4859452612877065";

    /** A positive amount the nine-digit pattern accepts. */
    private static final String POSITIVE_AMOUNT = "1250.75";

    /** A negative amount the nine-digit pattern accepts. */
    private static final String NEGATIVE_AMOUNT = "-125.00";

    /** A positive balance the ten-digit pattern accepts. */
    private static final String POSITIVE_BALANCE = "1250.75";

    /** A negative balance the ten-digit pattern accepts. */
    private static final String NEGATIVE_BALANCE = "-43.10";

    /** The authorization timestamp form the authorized document accepts. */
    private static final String AUTHORIZED_AT_VALUE = "2022-06-10 19:27:53.000000";

    /** The posting timestamp form the posted document accepts. */
    private static final String POSTED_AT_VALUE = "2022-07-19-23.16.01.470000";

    /** Reads and writes JSON trees. Jackson 3 only; no Jackson 2 type appears in this class. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Compiles a document into a validating schema, offline. */
    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    /**
     * Asserts that all five documents load from the classpath, compile as Draft 2020-12 schemas,
     * and declare a title, an {@code $id} and an object type.
     *
     * <p>No document holds a {@code $ref} or a {@code $defs} block, so nothing here resolves a
     * reference and no network call can occur.</p>
     */
    @Test
    void everyDocumentLoadsFromTheClasspathAndCompilesOffline() {
        for (String document : DOCUMENTS) {
            JsonNode tree = readDocument(document);

            assertEquals(DRAFT_2020_12, tree.get("$schema").asString(),
                    document + " stopped declaring the Draft 2020-12 dialect, so a consumer "
                            + "validating against version one changes dialect underneath it");
            assertNotNull(tree.get("$id"),
                    document + " stopped declaring an $id, so the document lost its stable "
                            + "identity");
            assertEquals("object", tree.get("type").asString(),
                    document + " stopped declaring an object type");
            assertNotNull(schemaFor(document),
                    document + " failed to compile as a Draft 2020-12 schema");

            String text = rawTextOf(document);
            assertFalse(text.contains("\"$ref\""),
                    document + " introduced a $ref, and this baseline resolves no reference");
            assertFalse(text.contains("\"$defs\""),
                    document + " introduced a $defs block, and this baseline resolves no "
                            + "reference");
        }
    }

    /**
     * Asserts that a resource path with no document behind it fails with the path named.
     *
     * <p>A loader returning null would surface later as a null dereference that names nothing.</p>
     */
    @Test
    void anAbsentSchemaResourceFailsWithTheResourcePathNamed() {
        String absent = "schemas/transaction-settled-v1.json";

        AssertionError failure = assertThrows(AssertionError.class, () -> readDocument(absent),
                "loading an absent schema resource stopped failing, so a renamed document would "
                        + "pass unnoticed");

        assertTrue(failure.getMessage().contains(absent),
                "the failure for an absent schema resource stopped naming the path, and the "
                        + "message read: " + failure.getMessage());
    }

    /**
     * Asserts the relative order of the eight keywords every document declares.
     *
     * <p>The check filters the document keys down to those eight, so a top-level {@code $comment}
     * and the {@code oneOf} block on the declined document are both tolerated. No absolute
     * position and no key count is asserted.</p>
     */
    @Test
    void everyDocumentKeepsTheRelativeOrderOfItsEightTopLevelKeywords() {
        for (String document : DOCUMENTS) {
            List<String> declared = new ArrayList<>();
            for (String keyword : readDocument(document).propertyNames()) {
                if (ORDERED_TOP_LEVEL_KEYWORDS.contains(keyword)) {
                    declared.add(keyword);
                }
            }

            assertEquals(ORDERED_TOP_LEVEL_KEYWORDS, declared,
                    document + " changed the presence or the relative order of its top-level "
                            + "keywords, and declared " + declared);
        }
    }

    /**
     * Asserts the {@code title} of each document equals the {@code const} of its
     * {@code eventType} property, and that both equal the simple class name of the event record.
     */
    @Test
    void theTitleOfEveryDocumentEqualsItsEventTypeConst() {
        for (String document : DOCUMENTS) {
            JsonNode tree = readDocument(document);
            String expected = TITLES.get(document);

            assertEquals(expected, tree.get("title").asString(),
                    document + " changed its title, which routing and the event record name both "
                            + "follow");
            assertEquals(expected, propertiesOf(document).get("eventType").get("const").asString(),
                    document + " lost the agreement between its title and its eventType const, so "
                            + "a consumer routing on eventType would read a value the title "
                            + "denies");
        }
    }

    /**
     * Asserts the five envelope properties appear first and in the same order in every document.
     */
    @Test
    void everyDocumentDeclaresTheFiveEnvelopePropertiesFirstAndInOneOrder() {
        for (String document : DOCUMENTS) {
            List<String> declared = new ArrayList<>(propertiesOf(document).propertyNames());

            assertTrue(declared.size() > ENVELOPE_PROPERTIES.size(),
                    document + " declares " + declared.size() + " properties, too few to carry "
                            + "the five envelope fields and a payload");
            assertEquals(ENVELOPE_PROPERTIES, declared.subList(0, ENVELOPE_PROPERTIES.size()),
                    document + " reordered or dropped an envelope property, and its first five "
                            + "properties read " + declared.subList(0, ENVELOPE_PROPERTIES.size()));
        }
    }

    /**
     * Asserts the envelope block of every document matches the authorized document's, keyword for
     * keyword, with the {@code const} of {@code eventType} the one permitted difference.
     *
     * <p>The comparison covers assertion keywords only. {@code description} and {@code examples}
     * annotate a property and assert nothing about an instance, so a difference in either carries
     * no wire effect.</p>
     */
    @Test
    void theEnvelopeBlockIsIdenticalAcrossEveryDocumentExceptTheEventTypeConst() {
        JsonNode reference = propertiesOf(AUTHORIZED);

        String accountPattern = reference.get("aggregateId").get("pattern").asString();
        assertEquals(ACCOUNT_IDENTIFIER_PATTERN, accountPattern,
                AUTHORIZED + " changed the aggregateId pattern away from the eleven digits of "
                        + "XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy:L7, and that value is "
                        + "the Kafka message key");

        for (String document : DOCUMENTS) {
            JsonNode properties = propertiesOf(document);

            for (String property : ENVELOPE_PROPERTIES) {
                Map<String, String> expected = assertionKeywords(reference.get(property));
                Map<String, String> actual = assertionKeywords(properties.get(property));

                if ("eventType".equals(property)) {
                    expected.remove("const");
                    actual.remove("const");
                }

                assertEquals(expected, actual,
                        document + " changed the envelope property " + property + ", which every "
                                + "consumer reads before it looks at a payload");
            }
        }
    }

    /**
     * Asserts the exact required set of each document, and that every required name has a schema.
     *
     * <p>A name entering the set breaks a producer on the older version. A name leaving it breaks
     * a consumer that already reads the field. A required name with no schema declared is a defect
     * no other assertion here catches.</p>
     */
    @Test
    void everyDocumentKeepsItsExactRequiredPropertySet() {
        for (String document : DOCUMENTS) {
            JsonNode tree = readDocument(document);
            JsonNode properties = tree.get("properties");
            Set<String> declared = new LinkedHashSet<>();

            for (JsonNode entry : tree.get("required")) {
                String name = entry.asString();
                declared.add(name);
                assertTrue(properties.has(name),
                        document + " requires " + name + " and declares no schema for it");
            }

            Set<String> expected = REQUIRED_SETS.get(document);
            assertEquals(expected.size(), tree.get("required").size(),
                    document + " changed its required-property count from " + expected.size()
                            + ", so a field joined or left the version-one contract");
            assertEquals(expected, declared,
                    document + " changed its required-property set, and now requires " + declared);
        }
    }

    /**
     * Asserts every document keeps its property set open.
     *
     * <p>An open set lets a consumer validating against version one accept an event carrying a
     * field a later version adds. Closing the set turns the next additive change into a failure
     * for every consumer already on the topic.</p>
     */
    @Test
    void everyDocumentKeepsItsPropertySetOpenSoAnAddedFieldDoesNotBreakAConsumer() {
        for (String document : DOCUMENTS) {
            JsonNode declared = readDocument(document).get("additionalProperties");

            assertNotNull(declared,
                    document + " stopped declaring additionalProperties, so its stance on an "
                            + "added field is no longer written down");
            assertTrue(declared.asBoolean(),
                    document + " closed its property set, so the next field added to this event "
                            + "would fail validation for every consumer on version one");
        }
    }

    /**
     * Asserts a payload that nests the five envelope fields under an {@code envelope} key fails
     * every document, and that the failure names each missing envelope property.
     *
     * <p>The wire form is flat: envelope and payload properties sit at one level.</p>
     */
    @Test
    void aNestedEnvelopeShapeFailsEveryDocumentAndNamesTheMissingEnvelopeProperties() {
        for (String document : DOCUMENTS) {
            ObjectNode flat = validPayloadFor(document);
            ObjectNode nested = MAPPER.createObjectNode();
            ObjectNode envelope = MAPPER.createObjectNode();

            for (Map.Entry<String, JsonNode> property : flat.properties()) {
                if (ENVELOPE_PROPERTIES.contains(property.getKey())) {
                    envelope.set(property.getKey(), property.getValue());
                } else {
                    nested.set(property.getKey(), property.getValue());
                }
            }
            nested.set("envelope", envelope);

            List<Error> errors = validate(document, nested);
            assertFalse(errors.isEmpty(),
                    document + " accepted a payload nesting the envelope under an envelope key, so "
                            + "the flat wire form is no longer enforced");

            String reported = messagesOf(errors);
            for (String property : ENVELOPE_PROPERTIES) {
                assertTrue(reported.contains(property),
                        document + " rejected the nested shape without naming the missing envelope "
                                + "property " + property + ", and reported: " + reported);
            }
        }
    }

    /**
     * Asserts no document declares any property as a JSON number.
     *
     * <p>The scan covers the property trees and the document text in both the spaced and the
     * unspaced spelling of the keyword.</p>
     */
    @Test
    void noDocumentDeclaresAPropertyAsAJsonNumber() {
        for (String document : DOCUMENTS) {
            for (Map.Entry<String, JsonNode> property : propertiesOf(document).properties()) {
                assertFalse("number".equals(typeOf(property.getValue())),
                        document + " declared property " + property.getKey() + " as a JSON number, "
                                + "and a number parses into a binary floating-point value");
            }

            String text = rawTextOf(document);
            boolean spelled = text.contains("\"type\": \"number\"")
                    || text.contains("\"type\":\"number\"");
            assertFalse(spelled,
                    document + " introduced a JSON number type, and a number parses into a binary "
                            + "floating-point value");
        }
    }

    /**
     * Asserts the amount pattern keeps nine integer digits and the balance pattern keeps ten, and
     * that the two are not swapped.
     */
    @Test
    void theNineDigitAmountAndTheTenDigitBalancePatternsAreNotSwapped() {
        for (String document : DOCUMENTS) {
            JsonNode properties = propertiesOf(document);

            if (properties.has(AMOUNT_PROPERTY)) {
                JsonNode amount = properties.get(AMOUNT_PROPERTY);
                assertEquals("string", typeOf(amount),
                        document + " stopped carrying " + AMOUNT_PROPERTY + " as a string");
                assertEquals(NINE_INTEGER_DIGIT_AMOUNT_PATTERN, amount.get("pattern").asString(),
                        document + " changed the nine-integer-digit amount pattern, which follows "
                                + "TRAN-AMT PIC S9(09)V99 at app/cpy/CVTRA05Y.cpy:L10");
            }

            if (properties.has(BALANCE_PROPERTY)) {
                JsonNode balance = properties.get(BALANCE_PROPERTY);
                assertEquals("string", typeOf(balance),
                        document + " stopped carrying " + BALANCE_PROPERTY + " as a string");
                assertEquals(TEN_INTEGER_DIGIT_BALANCE_PATTERN, balance.get("pattern").asString(),
                        document + " changed the ten-integer-digit balance pattern, which follows "
                                + "ACCT-CURR-BAL PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L7");
            }
        }

        assertNotEquals(NINE_INTEGER_DIGIT_AMOUNT_PATTERN, TEN_INTEGER_DIGIT_BALANCE_PATTERN,
                "the amount pattern and the balance pattern became the same expression, so one of "
                        + "the two source widths was lost");
    }

    /**
     * Asserts both money patterns accept a negative value.
     *
     * <p>A negative amount reaches the cycle debit accumulator at
     * {@code app/cbl/CBTRN02C.cbl:L551}, so a pattern matching only non-negative values would
     * reject part of the daily feed.</p>
     */
    @Test
    void bothMoneyPatternsAcceptANegativeValue() {
        ObjectNode declinedRefund = validPayloadFor(DECLINED);
        declinedRefund.put(AMOUNT_PROPERTY, NEGATIVE_AMOUNT);
        assertValid(DECLINED, declinedRefund,
                "the amount pattern stopped accepting a negative value");

        ObjectNode postedRefund = validPayloadFor(POSTED);
        postedRefund.put(AMOUNT_PROPERTY, NEGATIVE_AMOUNT);
        postedRefund.put(BALANCE_PROPERTY, NEGATIVE_BALANCE);
        assertValid(POSTED, postedRefund,
                "the amount or the balance pattern stopped accepting a negative value");
    }

    /**
     * Asserts the amount pattern rejects a one-digit fraction, a three-digit fraction, a thousands
     * separator and a currency symbol, and that the balance pattern rejects eleven integer digits.
     */
    @Test
    void bothMoneyPatternsRejectMalformedText() {
        for (String malformed : List.of("50.4", "50.475", "1,000.00", "$50.47")) {
            ObjectNode payload = validPayloadFor(POSTED);
            payload.put(AMOUNT_PROPERTY, malformed);
            assertInvalid(POSTED, payload,
                    "the amount pattern accepted the malformed value " + malformed);
        }

        ObjectNode overWidth = validPayloadFor(POSTED);
        overWidth.put(BALANCE_PROPERTY, "12345678901.00");
        assertInvalid(POSTED, overWidth,
                "the balance pattern accepted eleven integer digits, one more than "
                        + "ACCT-CURR-BAL PIC S9(10)V99 carries");

        ObjectNode shortFraction = validPayloadFor(POSTED);
        shortFraction.put(BALANCE_PROPERTY, "1250.7");
        assertInvalid(POSTED, shortFraction,
                "the balance pattern accepted a one-digit fraction");
    }

    /**
     * Asserts {@code integer} appears on {@code schemaVersion} in every document, on
     * {@code riskScore} in the flagged document, and on no other property.
     *
     * <p>Both are correctly JSON numbers, so an assertion covering every numeric property would be
     * wrong. {@code riskScore} keeps its bounds of 0 and 100.</p>
     */
    @Test
    void integerAppearsOnlyOnSchemaVersionAndOnRiskScoreInTheFlaggedDocument() {
        for (String document : DOCUMENTS) {
            JsonNode properties = propertiesOf(document);
            Set<String> integers = new LinkedHashSet<>();

            for (Map.Entry<String, JsonNode> property : properties.properties()) {
                if ("integer".equals(typeOf(property.getValue()))) {
                    integers.add(property.getKey());
                }
            }

            Set<String> expected = FLAGGED.equals(document)
                    ? new LinkedHashSet<>(List.of("schemaVersion", "riskScore"))
                    : new LinkedHashSet<>(List.of("schemaVersion"));
            assertEquals(expected, integers,
                    document + " changed which properties travel as a JSON integer, and now "
                            + "declares " + integers);

            assertEquals(1, properties.get("schemaVersion").get("const").intValue(),
                    document + " changed its schemaVersion const away from 1, which the -v1 "
                            + "filename suffix follows");
        }

        JsonNode riskScore = propertiesOf(FLAGGED).get("riskScore");
        assertEquals(0, riskScore.get("minimum").intValue(),
                FLAGGED + " changed the riskScore lower bound away from 0");
        assertEquals(100, riskScore.get("maximum").intValue(),
                FLAGGED + " changed the riskScore upper bound away from 100");
    }

    /**
     * Asserts the declined document enumerates exactly four reject reason codes, each a
     * four-character zero-padded string, and that the code is a single value.
     *
     * <p>One record carries one code. {@code app/cbl/CBTRN02C.cbl:L370-L378} runs the
     * cross-reference check first, and the account check only while the code is still zero. The
     * two tests at {@code :L403-L413} and {@code :L414-L420} share one branch, so the later test
     * overwrites the earlier code.</p>
     */
    @Test
    void theDeclinedDocumentEnumeratesExactlyFourReasonCodes() {
        JsonNode code = propertiesOf(DECLINED).get(DECLINE_CODE_PROPERTY);

        assertEquals("string", typeOf(code),
                DECLINED + " stopped carrying " + DECLINE_CODE_PROPERTY + " as a string, and the "
                        + "wire form is the four-character zero-padded code");
        assertFalse("array".equals(typeOf(code)),
                DECLINED + " turned " + DECLINE_CODE_PROPERTY + " into an array, and one event "
                        + "carries one code");

        List<String> declared = stringsOf(code.get("enum"));
        assertEquals(new ArrayList<>(DECLINE_PAIRS.keySet()), declared,
                DECLINED + " changed the reject reason enum, and now enumerates " + declared);

        for (String value : declared) {
            assertEquals(4, value.length(),
                    DECLINED + " enumerated the reason code " + value + ", which is not the "
                            + "four-character width of WS-VALIDATION-FAIL-REASON PIC 9(04) at "
                            + "app/cbl/CBTRN02C.cbl:L181");
        }
    }

    /**
     * Asserts the four reject reason texts match the source character for character, and that the
     * text keeps its 76-character limit.
     */
    @Test
    void theFourDeclineDescriptionsMatchTheSourceTextsCharacterForCharacter() {
        JsonNode text = propertiesOf(DECLINED).get(DECLINE_TEXT_PROPERTY);

        assertEquals(DECLINE_TEXT_WIDTH, text.get("maxLength").intValue(),
                DECLINED + " changed the reason text limit away from the 76 characters of "
                        + "WS-VALIDATION-FAIL-REASON-DESC PIC X(76) at app/cbl/CBTRN02C.cbl:L182, "
                        + "corroborated by the 430-byte reject record at app/jcl/POSTTRAN.jcl:L36");

        List<String> declared = stringsOf(text.get("enum"));
        assertEquals(new ArrayList<>(DECLINE_PAIRS.values()), declared,
                DECLINED + " changed the reject reason texts, and now enumerates " + declared);

        for (String value : declared) {
            assertTrue(value.length() <= DECLINE_TEXT_WIDTH,
                    DECLINED + " enumerated a reason text of " + value.length() + " characters, "
                            + "which does not fit the 76-character description field");
        }
    }

    /**
     * Asserts a payload for each of the four reject reasons validates.
     *
     * <p>A decline is expected traffic. {@code app/cbl/CBTRN02C.cbl:L229-L230} sets return code 4
     * when the reject count is positive, and treats the run as normal.</p>
     */
    @Test
    void everyDeclineReasonValidatesAsExpectedTraffic() {
        for (Map.Entry<String, String> pair : DECLINE_PAIRS.entrySet()) {
            ObjectNode payload = validPayloadFor(DECLINED);
            payload.put(DECLINE_CODE_PROPERTY, pair.getKey());
            payload.put(DECLINE_TEXT_PROPERTY, pair.getValue());

            assertValid(DECLINED, payload,
                    "the declined document rejected reason " + pair.getKey() + ", which the source "
                            + "writes as a normal outcome");
        }
    }

    /**
     * Asserts a reason code outside the four fails, whether it arrives as a four-character string
     * or as a bare integer.
     */
    @Test
    void aReasonCodeOutsideTheFourFailsValidation() {
        ObjectNode unknownCode = validPayloadFor(DECLINED);
        unknownCode.put(DECLINE_CODE_PROPERTY, EXCLUDED_REASON_CODE);
        assertInvalid(DECLINED, unknownCode,
                "the declined document accepted the reason code " + EXCLUDED_REASON_CODE
                        + ", which the decline enum excludes");

        ObjectNode bareInteger = validPayloadFor(DECLINED);
        bareInteger.put(DECLINE_CODE_PROPERTY, 9999);
        assertInvalid(DECLINED, bareInteger,
                "the declined document accepted a bare integer reason code, and the wire form is "
                        + "the four-character zero-padded string");
    }

    /**
     * Asserts {@code authorizedAt} keeps the space-separated form with six fractional digits, and
     * rejects a value using the letter T as its separator.
     *
     * <p>The field is 26 characters of fixed-width text, from {@code TRAN-ORIG-TS PIC X(26)} at
     * {@code app/cpy/CVTRA05Y.cpy:L16}.</p>
     */
    @Test
    void authorizedAtKeepsItsSpaceSeparatedSixDigitFractionForm() {
        JsonNode authorizedAt = propertiesOf(AUTHORIZED).get("authorizedAt");

        assertEquals(AUTHORIZED_AT_PATTERN, authorizedAt.get("pattern").asString(),
                AUTHORIZED + " changed the authorizedAt pattern away from the form TRAN-ORIG-TS "
                        + "carries at app/cpy/CVTRA05Y.cpy:L16");
        assertEquals(26, authorizedAt.get("minLength").intValue(),
                AUTHORIZED + " changed the authorizedAt minimum length away from 26");
        assertEquals(26, authorizedAt.get("maxLength").intValue(),
                AUTHORIZED + " changed the authorizedAt maximum length away from 26");

        ObjectNode accepted = validPayloadFor(AUTHORIZED);
        accepted.put("authorizedAt", AUTHORIZED_AT_VALUE);
        assertValid(AUTHORIZED, accepted,
                "authorizedAt rejected " + AUTHORIZED_AT_VALUE + ", the form the daily feed holds");

        ObjectNode isoSeparator = validPayloadFor(AUTHORIZED);
        isoSeparator.put("authorizedAt", "2022-06-10T19:27:53.000000");
        assertInvalid(AUTHORIZED, isoSeparator,
                "authorizedAt accepted a T separator, and the source writes a space at "
                        + "position 11");
    }

    /**
     * Asserts {@code postedAt} keeps hundredths of a second followed by four zeros, and rejects the
     * space-separated form, the T-separated form, and six significant fractional digits.
     *
     * <p>Precision comes from {@code DB2-MIL PIC 9(002)} at {@code app/cbl/CBTRN02C.cbl:L173} and
     * the four-zero move at {@code :L701}. A consumer truncates to hundredths before any
     * byte-for-byte comparison.</p>
     */
    @Test
    void postedAtKeepsItsHundredthsPrecisionAndFourTrailingZeros() {
        JsonNode postedAt = propertiesOf(POSTED).get("postedAt");

        assertEquals(POSTED_AT_PATTERN, postedAt.get("pattern").asString(),
                POSTED + " changed the postedAt pattern away from the form TRAN-PROC-TS carries at "
                        + "app/cpy/CVTRA05Y.cpy:L17");
        assertEquals(26, postedAt.get("minLength").intValue(),
                POSTED + " changed the postedAt minimum length away from 26");
        assertEquals(26, postedAt.get("maxLength").intValue(),
                POSTED + " changed the postedAt maximum length away from 26");

        ObjectNode accepted = validPayloadFor(POSTED);
        accepted.put("postedAt", POSTED_AT_VALUE);
        assertValid(POSTED, accepted,
                "postedAt rejected " + POSTED_AT_VALUE + ", the form the posting routine writes");

        for (String rejected : List.of("2022-07-19 23:16:01.470000", "2022-07-19T23:16:01.470000",
                "2022-07-19-23.16.01.470123")) {
            ObjectNode payload = validPayloadFor(POSTED);
            payload.put("postedAt", rejected);
            assertInvalid(POSTED, payload,
                    "postedAt accepted " + rejected + ", which is not the dash-and-dot form with "
                            + "hundredths precision and four trailing zeros");
        }
    }

    /**
     * Asserts neither fixed-width COBOL timestamp claims an ISO-8601 format, and that the three
     * ISO-8601 timestamps do.
     *
     * <p>{@code occurredAt} on the envelope and {@code assessedAt} on the two fraud documents are
     * the only ISO-8601 timestamps in this module. {@code authorizedAt} and {@code postedAt} are
     * opaque fixed-width text.</p>
     */
    @Test
    void neitherCobolTimestampClaimsAnIsoDateTimeFormat() {
        assertFalse(propertiesOf(AUTHORIZED).get("authorizedAt").has("format"),
                AUTHORIZED + " gave authorizedAt a format keyword, and the field is fixed-width "
                        + "text that no ISO-8601 parser reads");
        assertFalse(propertiesOf(POSTED).get("postedAt").has("format"),
                POSTED + " gave postedAt a format keyword, and the field is fixed-width text that "
                        + "no ISO-8601 parser reads");

        for (String document : DOCUMENTS) {
            assertEquals("date-time", formatOf(propertiesOf(document).get("occurredAt")),
                    document + " changed the occurredAt format away from date-time");
        }

        for (String document : List.of(FLAGGED, CLEARED)) {
            assertEquals("date-time", formatOf(propertiesOf(document).get("assessedAt")),
                    document + " changed the assessedAt format away from date-time");
        }

        for (String document : DOCUMENTS) {
            Set<String> declared = new LinkedHashSet<>();
            for (Map.Entry<String, JsonNode> property : propertiesOf(document).properties()) {
                if ("date-time".equals(formatOf(property.getValue()))) {
                    declared.add(property.getKey());
                }
            }

            Set<String> expected = new LinkedHashSet<>(List.of("occurredAt"));
            if (FLAGGED.equals(document) || CLEARED.equals(document)) {
                expected.add("assessedAt");
            }
            assertEquals(expected, declared,
                    document + " changed which timestamps claim the ISO-8601 date-time format, and "
                            + "now declares " + declared);
        }
    }

    /**
     * Asserts {@code triggeredRules} keeps a lower bound of one item, keeps its items unique, and
     * declares no upper bound.
     *
     * <p>An upper bound would turn a fourth rule identifier into a breaking change.</p>
     */
    @Test
    void triggeredRulesStaysUnboundedSoAFourthRuleValueIsNotBreaking() {
        JsonNode triggeredRules = propertiesOf(FLAGGED).get("triggeredRules");

        assertEquals("array", typeOf(triggeredRules),
                FLAGGED + " stopped carrying triggeredRules as an array");
        assertEquals(1, triggeredRules.get("minItems").intValue(),
                FLAGGED + " changed the triggeredRules lower bound away from one item, and a "
                        + "flagged event names at least one rule");
        assertTrue(triggeredRules.get("uniqueItems").asBoolean(),
                FLAGGED + " stopped requiring unique triggeredRules entries");
        assertFalse(triggeredRules.has("maxItems"),
                FLAGGED + " gave triggeredRules a maxItems bound, which turns a fourth rule "
                        + "identifier into a breaking change for every consumer on version one");

        List<String> declared = stringsOf(triggeredRules.get("items").get("enum"));
        assertEquals(TRIGGERED_RULE_VALUES, declared,
                FLAGGED + " changed the triggeredRules identifiers, and now enumerates "
                        + declared);
    }

    /**
     * Asserts no document names a card verification value, a card status or an account status, and
     * that no document declares a card-number checksum.
     *
     * <p>The source stores the card verification value in the clear at
     * {@code app/cpy/CVACT02Y.cpy:L7} and the target emits it nowhere. The posting path never opens
     * the card file, so it reads neither {@code CARD-ACTIVE-STATUS PIC X(01)} at
     * {@code app/cpy/CVACT02Y.cpy:L10} nor {@code ACCT-ACTIVE-STATUS PIC X(01)} at
     * {@code app/cpy/CVACT01Y.cpy:L6}, and {@code app/jcl/POSTTRAN.jcl} allocates no card dataset.
     * The source validates a card number as sixteen numeric digits and nothing more.</p>
     */
    @Test
    void noDocumentCarriesACardSecretAStatusFlagOrAChecksumRule() {
        for (String document : DOCUMENTS) {
            String text = rawTextOf(document).toLowerCase(Locale.ROOT);

            for (String field : FORBIDDEN_SOURCE_FIELDS) {
                assertFalse(text.contains(field),
                        document + " names " + field + ", and no event carries a card secret or a "
                                + "status flag");
            }

            for (String check : FORBIDDEN_CARD_CHECKS) {
                assertFalse(text.contains(check),
                        document + " declares the card-number check " + check + ", which the "
                                + "source does not perform");
            }
        }
    }

    /**
     * Asserts each document omits the properties a sibling document owns.
     *
     * <p>The daily feed carries 26 blanks in its posting timestamp field, and
     * {@code app/cbl/CBTRN02C.cbl:L437-L438} fills that field at posting time. The posted document
     * therefore carries a posting timestamp and the authorized document does not. Trailing filler
     * is dropped: {@code FILLER PIC X(20)} at {@code app/cpy/CVTRA05Y.cpy:L18} and
     * {@code FILLER PIC X(178)} at {@code app/cpy/CVACT01Y.cpy:L17} reach no target field.</p>
     */
    @Test
    void everyDocumentOmitsThePropertiesItsSiblingsOwn() {
        for (String document : DOCUMENTS) {
            for (String declared : propertiesOf(document).propertyNames()) {
                String name = declared.toLowerCase(Locale.ROOT);

                for (String fragment : FORBIDDEN_PROPERTY_FRAGMENTS.get(document)) {
                    assertFalse(name.contains(fragment),
                            document + " declared the property " + declared + ", which carries the "
                                    + "fragment " + fragment + " that belongs to another document "
                                    + "or to dropped filler");
                }
            }
        }
    }

    /**
     * Asserts the masked card number keeps twelve asterisks and four digits on the three documents
     * that carry it. The pattern rejects a full Primary Account Number (PAN), and neither fraud
     * document declares the property.
     *
     * <p>The authorization decision reads the full sixteen characters at
     * {@code app/cbl/CBTRN02C.cbl:L382-L383}. Masking happens at the serialization boundary
     * alone.</p>
     */
    @Test
    void maskedCardNumberRejectsAFullPrimaryAccountNumberAndIsAbsentFromBothFraudDocuments() {
        for (String document : List.of(AUTHORIZED, DECLINED, POSTED)) {
            JsonNode masked = propertiesOf(document).get(MASKED_CARD_PROPERTY);

            assertNotNull(masked,
                    document + " dropped " + MASKED_CARD_PROPERTY + ", which every consumer of "
                            + "this event displays");
            assertEquals(MASKED_CARD_PATTERN, masked.get("pattern").asString(),
                    document + " changed the masking pattern away from twelve asterisks and four "
                            + "digits");
            assertEquals(16, masked.get("minLength").intValue(),
                    document + " changed the masked card minimum length away from 16");
            assertEquals(16, masked.get("maxLength").intValue(),
                    document + " changed the masked card maximum length away from 16");

            ObjectNode accepted = validPayloadFor(document);
            accepted.put(MASKED_CARD_PROPERTY, MASKED_CARD_NUMBER);
            assertValid(document, accepted,
                    MASKED_CARD_PROPERTY + " rejected the masked form " + MASKED_CARD_NUMBER);

            ObjectNode unmasked = validPayloadFor(document);
            unmasked.put(MASKED_CARD_PROPERTY, FULL_CARD_NUMBER);
            assertInvalid(document, unmasked,
                    MASKED_CARD_PROPERTY + " accepted a full sixteen-digit Primary Account Number "
                            + "(PAN), so an unmasked card number could reach the topic");
        }

        for (String document : List.of(FLAGGED, CLEARED)) {
            assertFalse(propertiesOf(document).has(MASKED_CARD_PROPERTY),
                    document + " added " + MASKED_CARD_PROPERTY + ", and a risk assessment carries "
                            + "no card number in any form");
        }
    }

    /**
     * Asserts the minimal payload of every document validates, and that dropping any one required
     * property fails.
     *
     * <p>This pins both directions of the additive-only rule: the version-one shape stays valid,
     * and a removal turns red.</p>
     */
    @Test
    void everyDocumentAcceptsItsVersionOneShapeAndRejectsTheLossOfAnyRequiredProperty() {
        for (String document : DOCUMENTS) {
            ObjectNode payload = validPayloadFor(document);
            assertValid(document, payload,
                    "the version-one shape of " + document + " stopped validating");

            for (String property : REQUIRED_SETS.get(document)) {
                ObjectNode reduced = payload.deepCopy();
                reduced.remove(property);
                assertInvalid(document, reduced,
                        document + " accepted a payload missing the required property " + property);
            }
        }
    }

    /**
     * Asserts a document accepts a payload carrying a property it does not declare.
     *
     * <p>A consumer validating against version one keeps working when a later version adds a
     * field.</p>
     */
    @Test
    void everyDocumentAcceptsAnAddedPropertySoANewFieldDoesNotBreakAConsumer() {
        for (String document : DOCUMENTS) {
            ObjectNode extended = validPayloadFor(document);
            extended.put("settlementReference", "0000000000000001");

            assertValid(document, extended,
                    document + " rejected a payload carrying one added property, so the next field "
                            + "added to this event would break every consumer on version one");
        }
    }

    /**
     * The exact required set of each document, written as five envelope properties followed by the
     * payload properties that document declares.
     *
     * @return each classpath resource mapped to the names its {@code required} array holds
     */
    private static Map<String, Set<String>> requiredSets() {
        Map<String, Set<String>> sets = new LinkedHashMap<>();
        sets.put(AUTHORIZED, requiredSet("transactionId", "transactionTypeCode",
                "merchantCategoryCode", "source", "description", "amount", "merchantId",
                "merchantName", "merchantCity", "merchantZip", "maskedCardNumber", "authorizedAt",
                "currency"));
        sets.put(DECLINED, requiredSet("transactionId", "declineReasonCode",
                "declineReasonDescription", "amount", "maskedCardNumber"));
        sets.put(POSTED, requiredSet("transactionId", "newBalance", "postedAt", "amount",
                "maskedCardNumber"));
        sets.put(FLAGGED, requiredSet("transactionId", "riskScore", "triggeredRules",
                "assessedAt"));
        sets.put(CLEARED, requiredSet("transactionId", "accountId", "assessedAt"));
        return Map.copyOf(sets);
    }

    /**
     * Prefixes the five envelope properties to the payload properties of one document.
     *
     * @param payloadProperties the payload property names, in document order
     * @return the envelope names followed by the payload names
     */
    private static Set<String> requiredSet(String... payloadProperties) {
        Set<String> names = new LinkedHashSet<>(ENVELOPE_PROPERTIES);
        names.addAll(List.of(payloadProperties));
        return names;
    }

    /**
     * Each reject reason code paired with the text the source writes beside it, at
     * {@code app/cbl/CBTRN02C.cbl:L385-L387}, {@code :L397-L399}, {@code :L410-L412} and
     * {@code :L417-L419}. Reproduced character for character.
     *
     * @return each code mapped to its text, in the order the source assigns them
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
     * Opens a document from the classpath, or fails naming the resource path.
     *
     * @param document the classpath path of the document
     * @return an open stream over the document
     */
    private static InputStream openResource(String document) {
        InputStream stream = SchemaBackwardCompatibilityTest.class.getClassLoader()
                .getResourceAsStream(document);

        if (stream == null) {
            return fail("classpath resource " + document + " is missing from "
                    + "libs/event-contracts/src/main/resources, so the version-one baseline of "
                    + "that event cannot be read");
        }
        return stream;
    }

    /**
     * Reads one document as a JSON tree.
     *
     * @param document the classpath path of the document
     * @return the parsed document
     */
    private static JsonNode readDocument(String document) {
        try (InputStream stream = openResource(document)) {
            return MAPPER.readTree(stream);
        } catch (IOException failure) {
            return fail("classpath resource " + document + " could not be read: "
                    + failure.getMessage());
        }
    }

    /**
     * Reads the {@code properties} block of one document.
     *
     * @param document the classpath path of the document
     * @return the property definitions, in document order
     */
    private static JsonNode propertiesOf(String document) {
        JsonNode properties = readDocument(document).get("properties");

        if (properties == null) {
            return fail(document + " stopped declaring a properties block");
        }
        return properties;
    }

    /**
     * Reads one document as text, for the keyword and field-name scans.
     *
     * @param document the classpath path of the document
     * @return the document text
     */
    private static String rawTextOf(String document) {
        try (InputStream stream = openResource(document)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            return fail("classpath resource " + document + " could not be read as text: "
                    + failure.getMessage());
        }
    }

    /**
     * Compiles one document into a validating schema.
     *
     * @param document the classpath path of the document
     * @return the compiled schema
     */
    private static Schema schemaFor(String document) {
        try (InputStream stream = openResource(document)) {
            return REGISTRY.getSchema(stream);
        } catch (IOException failure) {
            return fail("classpath resource " + document + " could not be compiled: "
                    + failure.getMessage());
        }
    }

    /**
     * Validates a payload as text against one document.
     *
     * @param document the classpath path of the document
     * @param payload  the payload to validate
     * @return one error per failure, empty when the payload validates
     */
    private static List<Error> validate(String document, JsonNode payload) {
        return schemaFor(document).validate(MAPPER.writeValueAsString(payload), InputFormat.JSON);
    }

    /**
     * Asserts a payload validates, and names every reported error when it does not.
     *
     * @param document the classpath path of the document
     * @param payload  the payload to validate
     * @param message  what a reader should do when this assertion fails
     */
    private static void assertValid(String document, JsonNode payload, String message) {
        List<Error> errors = validate(document, payload);

        assertTrue(errors.isEmpty(), message + " (" + document + " reported: "
                + messagesOf(errors) + ")");
    }

    /**
     * Asserts a payload fails validation.
     *
     * @param document the classpath path of the document
     * @param payload  the payload to validate
     * @param message  what a reader should do when this assertion fails
     */
    private static void assertInvalid(String document, JsonNode payload, String message) {
        assertFalse(validate(document, payload).isEmpty(), message + " (" + document + ")");
    }

    /**
     * Joins the reported validation errors into one readable line.
     *
     * @param errors the reported errors
     * @return the messages joined by a semicolon
     */
    private static String messagesOf(List<Error> errors) {
        List<String> messages = new ArrayList<>();

        for (Error error : errors) {
            messages.add(error.getMessage());
        }
        return String.join("; ", messages);
    }

    /**
     * Projects a property schema down to the keywords that assert something about an instance.
     * Annotation keywords are skipped.
     *
     * @param propertySchema the property schema
     * @return each assertion keyword mapped to its serialized value
     */
    private static Map<String, String> assertionKeywords(JsonNode propertySchema) {
        Map<String, String> keywords = new LinkedHashMap<>();

        for (Map.Entry<String, JsonNode> keyword : propertySchema.properties()) {
            if (!ANNOTATION_KEYWORDS.contains(keyword.getKey())) {
                keywords.put(keyword.getKey(), keyword.getValue().toString());
            }
        }
        return keywords;
    }

    /**
     * Reads the {@code type} keyword of a property schema.
     *
     * @param propertySchema the property schema
     * @return the type name, or {@code null} when the property declares none
     */
    private static String typeOf(JsonNode propertySchema) {
        JsonNode type = propertySchema.get("type");

        return type == null ? null : type.asString();
    }

    /**
     * Reads the {@code format} keyword of a property schema.
     *
     * @param propertySchema the property schema
     * @return the format name, or {@code null} when the property declares none
     */
    private static String formatOf(JsonNode propertySchema) {
        JsonNode format = propertySchema.get("format");

        return format == null ? null : format.asString();
    }

    /**
     * Reads a JSON array of strings into a list, preserving order.
     *
     * @param array the array node to read
     * @return the string values, in array order
     */
    private static List<String> stringsOf(JsonNode array) {
        if (array == null || !array.isArray()) {
            return fail("a keyword expected to hold an array of strings held " + array);
        }

        List<String> values = new ArrayList<>();
        for (JsonNode entry : array) {
            values.add(entry.asString());
        }
        return values;
    }

    /**
     * Builds the five envelope properties of a payload for one event type.
     *
     * @param eventType the routing discriminator the payload carries
     * @return a payload holding the envelope and nothing more
     */
    private static ObjectNode envelopeFor(String eventType) {
        ObjectNode payload = MAPPER.createObjectNode();

        payload.put("eventId", EVENT_ID);
        payload.put("eventType", eventType);
        payload.put("schemaVersion", 1);
        payload.put("occurredAt", OCCURRED_AT);
        payload.put("aggregateId", ACCOUNT_IDENTIFIER);
        return payload;
    }

    /**
     * Builds a payload carrying every required property of one document and nothing more.
     *
     * @param document the classpath path of the document
     * @return a payload that validates against that document
     */
    private static ObjectNode validPayloadFor(String document) {
        ObjectNode payload = envelopeFor(TITLES.get(document));
        payload.put("transactionId", TRANSACTION_ID);

        switch (document) {
            case AUTHORIZED -> {
                payload.put("transactionTypeCode", "01");
                payload.put("merchantCategoryCode", "0001");
                payload.put("source", "POS TERM");
                payload.put("description", "Purchase at Abshire-Lowe");
                payload.put(AMOUNT_PROPERTY, POSITIVE_AMOUNT);
                payload.put("merchantId", "800000000");
                payload.put("merchantName", "Abshire-Lowe");
                payload.put("merchantCity", "North Enoshaven");
                payload.put("merchantZip", "72112");
                payload.put(MASKED_CARD_PROPERTY, MASKED_CARD_NUMBER);
                payload.put("authorizedAt", AUTHORIZED_AT_VALUE);
                payload.put("currency", "USD");
            }
            case DECLINED -> {
                payload.put(DECLINE_CODE_PROPERTY, "0102");
                payload.put(DECLINE_TEXT_PROPERTY, "OVERLIMIT TRANSACTION");
                payload.put(AMOUNT_PROPERTY, POSITIVE_AMOUNT);
                payload.put(MASKED_CARD_PROPERTY, MASKED_CARD_NUMBER);
            }
            case POSTED -> {
                payload.put(BALANCE_PROPERTY, POSITIVE_BALANCE);
                payload.put("postedAt", POSTED_AT_VALUE);
                payload.put(AMOUNT_PROPERTY, POSITIVE_AMOUNT);
                payload.put(MASKED_CARD_PROPERTY, MASKED_CARD_NUMBER);
            }
            case FLAGGED -> {
                payload.put("riskScore", 82);
                ArrayNode rules = payload.putArray("triggeredRules");
                rules.add(TRIGGERED_RULE_VALUES.get(0));
                rules.add(TRIGGERED_RULE_VALUES.get(1));
                payload.put("assessedAt", ASSESSED_AT);
            }
            case CLEARED -> {
                payload.put("accountId", ACCOUNT_IDENTIFIER);
                payload.put("assessedAt", ASSESSED_AT);
            }
            default -> fail("no payload is defined for the document " + document);
        }
        return payload;
    }
}
