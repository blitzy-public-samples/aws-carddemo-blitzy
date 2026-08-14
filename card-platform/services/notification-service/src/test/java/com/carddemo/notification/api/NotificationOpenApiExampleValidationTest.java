package com.carddemo.notification.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs every published example of {@code src/main/resources/openapi.yaml} through the schema that
 * document declares for it, and runs a body breaking each stated invariant through the same schema.
 *
 * <p>The document states invariants a reader relies on: the envelope names the card once and by its
 * masked form, every member is present on every response, no item repeats the card, and no schema
 * admits a property it does not declare. A schema that describes those in prose and enforces none of
 * them tells a caller a body is valid that the service never writes.
 *
 * <p>The validator is the one {@code libs/event-contracts} uses for the five event contracts, reading
 * JSON Schema Draft 2020-12. The whole {@code components} section is registered under one identifier,
 * so every {@code $ref} inside the document resolves the way a reader's tooling resolves it.
 *
 * <p>No body below carries a card number a seeded row holds. The masked forms are display values, and
 * the one full card number the document publishes is the all-zero placeholder of the path parameter.
 */
@DisplayName("Published schemas against published examples")
class NotificationOpenApiExampleValidationTest {

    /** The document, parsed once. */
    private static Map<String, Object> openApi;

    /** The registry every schema below is compiled through. */
    private static SchemaRegistry registry;

    /** Renders a body as the JavaScript Object Notation text the validator reads. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Identifier the document is registered under, so a local reference resolves against it. */
    private static final String DOCUMENT_URI = "https://carddemo.example/notification-openapi";

    /** The one route the document declares. */
    private static final String ROUTE = "/notifications/{cardToken}";

    /** A masked card number, the form every response names its card by. */
    private static final String MASKED_CARD_NUMBER = "*".repeat(12) + "0000";

    /** Reads the document and registers its component section. */
    @BeforeAll
    @SuppressWarnings("unchecked")
    static void readDocument() throws Exception {
        try (InputStream document = NotificationOpenApiExampleValidationTest.class
                .getResourceAsStream("/openapi.yaml")) {
            openApi = new Yaml().load(document);
        }

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("$id", DOCUMENT_URI);
        components.put("$schema", SpecificationVersion.DRAFT_2020_12.getDialectId());
        components.put("components", openApi.get("components"));

        String document = JSON.writeValueAsString(components);
        registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemas(Map.of(DOCUMENT_URI, document)));
    }

    /**
     * Asserts every response example validates against the schema its status declares.
     *
     * <p>Six statuses carry a body and an example: {@code 400}, {@code 401}, {@code 403},
     * {@code 405}, {@code 429} and {@code 500}. The {@code 200} declares a body and publishes no
     * example: every identifier of the read model is sixteen digits wide, and a sixteen-digit example
     * in this document would read as a card number. {@code 406} carries no body at all.
     */
    @Test
    @DisplayName("every published response example validates")
    @SuppressWarnings("unchecked")
    void everyPublishedResponseExampleValidates() {
        Map<String, Object> answers = (Map<String, Object>) node(
                (Map<String, Object>) openApi.get("paths"), ROUTE, "get", "responses");

        int validated = 0;
        for (Map.Entry<String, Object> answer : answers.entrySet()) {
            Map<String, Object> declared = (Map<String, Object>) answer.getValue();
            Map<String, Object> content = (Map<String, Object>) declared.get("content");
            if (content == null) {
                continue;
            }
            for (Map.Entry<String, Object> media : content.entrySet()) {
                Map<String, Object> body = (Map<String, Object>) media.getValue();
                Schema schema = schemaFor(nameOf(
                        String.valueOf(((Map<String, Object>) body.get("schema")).get("$ref"))));

                Map<String, Object> examples = (Map<String, Object>) body.get("examples");
                if (examples == null) {
                    if (body.get("example") == null) {
                        continue;
                    }
                    assertValid(schema, body.get("example"),
                            answer.getKey() + " carries one example");
                    validated++;
                    continue;
                }
                for (Map.Entry<String, Object> example : examples.entrySet()) {
                    assertValid(schema, valueOf(example.getValue()),
                            answer.getKey() + " example " + example.getKey());
                    validated++;
                }
            }
        }
        assertEquals(7, validated, "every documented body carries an example that validates");
    }

    /**
     * Asserts the path parameter example matches the shape the parameter declares.
     *
     * <p>The example is a placeholder rather than a value, so the assertion also holds it apart from
     * every masked form the document publishes. The two values a caller might reach for instead of a
     * token are both asserted invalid: a full card number, which is what keeps a Primary Account
     * Number out of a request line, and a masked number, which names every card sharing four digits
     * and therefore names no single row.
     */
    @Test
    @DisplayName("the path parameter example matches the parameter schema")
    @SuppressWarnings("unchecked")
    void thePathParameterExampleMatchesItsSchema() {
        Map<String, Object> parameter = ((List<Map<String, Object>>) node(
                (Map<String, Object>) openApi.get("paths"), ROUTE, "get", "parameters")).getFirst();
        Map<String, Object> schema = (Map<String, Object>) parameter.get("schema");
        Object published = valueOf(((Map<String, Object>) parameter.get("examples")).get("token"));

        Schema compiled = registry.getSchema(JSON.writeValueAsString(schema), InputFormat.JSON);

        assertEquals("cardToken", parameter.get("name"), "the parameter this route reads");
        assertValid(compiled, published, "the path parameter example");
        assertInvalid(compiled, MASKED_CARD_NUMBER, "a masked card number in the path");
        assertInvalid(compiled, "0".repeat(16), "a full card number in the path");
        assertInvalid(compiled, "0".repeat(63), "a token one character under its width");
        assertInvalid(compiled, "0".repeat(65), "a token one character over its width");
        assertInvalid(compiled, "A".repeat(64), "an upper-case rendering of a token");
    }

    /**
     * Asserts the envelope schema enforces the invariants the document states in prose.
     *
     * <p>Every member but the cursor is required, the card is named by its masked form alone, and the
     * schema admits no property it does not declare. {@code app/cbl/CBSTM03A.CBL:L429} totals every row
     * of one card, so the count carries no ceiling; the array carries one page and publishes the one
     * the route enforces.
     */
    @Test
    @DisplayName("the envelope schema enforces the shape the service writes")
    void theEnvelopeSchemaEnforcesTheShapeTheServiceWrites() {
        Schema history = schemaFor("NotificationHistory");

        assertValid(history, history(MASKED_CARD_NUMBER, 0, "0.00", List.of()),
                "a card with no row, naming its card");
        assertValid(history, history(MASKED_CARD_NUMBER, 1, "504.77", List.of(item())),
                "a card with one row");
        assertValid(history, history(MASKED_CARD_NUMBER, 300, "-125.00", List.of(item())),
                "a count above two hundred, which no ceiling refuses");

        assertInvalid(history, withoutMember(history(MASKED_CARD_NUMBER, 0, "0.00", List.of()),
                        "cardNumber"),
                "an envelope naming no card");
        assertInvalid(history, history(null, 0, "0.00", List.of()),
                "an envelope whose card is an explicit null");
        assertInvalid(history, history("0".repeat(16), 0, "0.00", List.of()),
                "an envelope carrying a full card number");
        assertInvalid(history, history(MASKED_CARD_NUMBER, -1, "0.00", List.of()),
                "an envelope carrying a negative count");
        assertInvalid(history, history(MASKED_CARD_NUMBER, 0, "0.000", List.of()),
                "an envelope carrying a total at three fractional digits");
        assertInvalid(history, history(MASKED_CARD_NUMBER, 0, "1234567890.00", List.of()),
                "an envelope carrying a total at ten integer digits");

        Map<String, Object> withToken = history(MASKED_CARD_NUMBER, 0, "0.00", List.of());
        withToken.put("cardToken", "a1b2c3d4".repeat(8));
        assertInvalid(history, withToken, "an envelope carrying the storage key");
    }

    /**
     * Asserts the item schema enforces its twelve members and admits no card value.
     *
     * <p>The card is named once, on the envelope. {@code TRNX-CARD-NUM PIC X(16)} at
     * {@code app/cpy/COSTM01.CPY:L22} sits inside the group {@code 05 TRNX-KEY.} at
     * {@code app/cpy/COSTM01.CPY:L21}, and {@code app/cbl/CBSTM03A.CBL:L421} moves it once per card
     * group where {@code app/cbl/CBSTM03A.CBL:L424-L427} runs once per transaction.
     */
    @Test
    @DisplayName("the item schema enforces its twelve members and names no card")
    void theItemSchemaEnforcesItsTwelveMembersAndNamesNoCard() {
        Schema transaction = schemaFor("StatementTransaction");

        assertValid(transaction, item(), "one item carrying every member");

        for (String member : item().keySet()) {
            assertInvalid(transaction, withoutMember(item(), member),
                    "an item omitting " + member);
        }

        Map<String, Object> withMasked = item();
        withMasked.put("maskedCardNumber", MASKED_CARD_NUMBER);
        assertInvalid(transaction, withMasked, "an item repeating the card of its history");

        Map<String, Object> withToken = item();
        withToken.put("cardToken", "a1b2c3d4".repeat(8));
        assertInvalid(transaction, withToken, "an item carrying the storage key");

        Map<String, Object> numericAmount = item();
        numericAmount.put("amount", 504.77);
        assertInvalid(transaction, numericAmount, "an item whose amount travels as a number");
    }

    /**
     * Builds one response envelope.
     *
     * @param cardNumber the masked card number, or {@code null} to send an explicit null
     * @param count the entry count
     * @param total the rendered total
     * @param items the entries
     * @return the body
     */
    private static Map<String, Object> history(String cardNumber, int count, String total,
            List<Map<String, Object>> items) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cardNumber", cardNumber);
        body.put("transactionCount", count);
        body.put("totalAmount", total);
        body.put("transactions", new ArrayList<>(items));
        // A last page, which is what every body of this class is. nextPageExists is required and
        // nextCursor is present exactly when it is true, so a last page omits the cursor rather than
        // carrying a null the schema would refuse.
        body.put("nextPageExists", false);
        return body;
    }

    /**
     * Builds one entry carrying all twelve members.
     *
     * @return the entry
     */
    private static Map<String, Object> item() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactionId", "TRN0000000000001");
        body.put("typeCode", "01");
        body.put("categoryCode", "0001");
        body.put("source", "POS TERM");
        body.put("description", "Purchase at Abshire-Lowe");
        body.put("amount", "504.77");
        body.put("merchantId", "800000000");
        body.put("merchantName", "Abshire-Lowe");
        body.put("merchantCity", "North Enoshaven");
        body.put("merchantZip", "72112");
        body.put("originTimestamp", "2022-06-10 19:27:53.000000");
        body.put("processingTimestamp", "2022-07-19-23.16.01.470000");
        return body;
    }

    /**
     * Copies one body with a member dropped.
     *
     * @param body the body
     * @param member the member to drop
     * @return the copy
     */
    private static Map<String, Object> withoutMember(Map<String, Object> body, String member) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.remove(member);
        return copy;
    }

    /**
     * Asserts one body validates against one schema.
     *
     * @param schema the schema
     * @param body the body
     * @param description what the body is, for the failure message
     */
    private static void assertValid(Schema schema, Object body, String description) {
        List<com.networknt.schema.Error> violations = violationsOf(schema, body);
        assertTrue(violations.isEmpty(), description + " is refused by the schema it is published "
                + "against, reporting " + violations.size() + " violations, the first being "
                + (violations.isEmpty() ? "none" : violations.getFirst().getMessage()));
    }

    /**
     * Asserts one body is refused by one schema.
     *
     * @param schema the schema
     * @param body the body
     * @param description what the body is, for the failure message
     */
    private static void assertInvalid(Schema schema, Object body, String description) {
        assertFalse(violationsOf(schema, body).isEmpty(),
                description + " is accepted by the schema, so the document states an invariant it "
                        + "does not enforce");
    }

    /**
     * Validates one body against one schema.
     *
     * @param schema the schema
     * @param body the body
     * @return the violations, empty when the body validates
     */
    private static List<com.networknt.schema.Error> violationsOf(Schema schema, Object body) {
        return schema.validate(JSON.writeValueAsString(body), InputFormat.JSON);
    }

    /**
     * Compiles one named schema of the document.
     *
     * @param schemaName the schema
     * @return the compiled schema
     */
    private static Schema schemaFor(String schemaName) {
        return registry.getSchema(
                "{\"$ref\":\"" + DOCUMENT_URI + "#/components/schemas/" + schemaName + "\"}",
                InputFormat.JSON);
    }

    /**
     * Reads the schema name out of a local reference.
     *
     * @param reference the reference
     * @return the schema name
     */
    private static String nameOf(String reference) {
        return reference.substring(reference.lastIndexOf('/') + 1);
    }

    /**
     * Reads the value of one example node.
     *
     * @param example the example node
     * @return the body it carries
     */
    @SuppressWarnings("unchecked")
    private static Object valueOf(Object example) {
        return ((Map<String, Object>) example).get("value");
    }

    /**
     * Walks a chain of keys through nested mappings.
     *
     * @param from the mapping to start at
     * @param keys the keys to follow
     * @return the node the last key names
     */
    @SuppressWarnings("unchecked")
    private static Object node(Map<String, Object> from, String... keys) {
        Object at = from;
        for (String key : keys) {
            at = ((Map<String, Object>) at).get(key);
            assertTrue(at != null, "the document declares " + key);
        }
        return at;
    }
}
